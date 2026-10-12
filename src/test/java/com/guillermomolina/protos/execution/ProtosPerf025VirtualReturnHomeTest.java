/*
 * THE LICENSED WORK IS PROVIDED UNDER THE TERMS OF THE ADAPTIVE PUBLIC LICENSE
 * ("LICENSE") AS FIRST COMPLETED BY: Guillermo Adrián Molina. ANY USE, PUBLIC
 * DISPLAY, PUBLIC PERFORMANCE, REPRODUCTION OR DISTRIBUTION OF, OR PREPARATION OF
 * DERIVATIVE WORKS BASED ON, THE LICENSED WORK CONSTITUTES RECIPIENT'S ACCEPTANCE
 * OF THIS LICENSE AND ITS TERMS, WHETHER OR NOT SUCH RECIPIENT READS THE TERMS OF
 * THE LICENSE. "LICENSED WORK" AND "RECIPIENT" ARE DEFINED IN THE LICENSE. A COPY
 * OF THE LICENSE IS LOCATED IN THE TEXT FILE ENTITLED "LICENSE.TXT" ACCOMPANYING
 * THE CONTENTS OF THIS FILE. IF A COPY OF THE LICENSE DOES NOT ACCOMPANY THIS
 * FILE, A COPY OF THE LICENSE MAY ALSO BE OBTAINED AT THE FOLLOWING WEB SITE:
 * https://github.com/guillermomolina/protos
 *
 * Software distributed under the License is distributed on an "AS IS" basis,
 * WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License for
 * the specific language governing rights and limitations under the License.
 */

package com.guillermomolina.protos.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.guillermomolina.protos.runtime.ProtosActivation;
import com.guillermomolina.protos.runtime.ProtosClosureValue;
import com.guillermomolina.protos.runtime.ProtosCoreErrors;
import com.guillermomolina.protos.runtime.ProtosIntegerValue;
import com.guillermomolina.protos.runtime.ProtosNullValue;
import com.guillermomolina.protos.runtime.ProtosPrelude;
import com.guillermomolina.protos.runtime.ProtosReturnHome;
import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.nodes.IndirectCallNode;
import com.oracle.truffle.api.source.Source;
import java.nio.file.Path;
import java.util.List;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;

/**
 * PERF025 virtual return home: an owning invocation of a source-backed Closure
 * whose plan statically proves that neither the Closure nor any lexical
 * descendant (parameter defaults, nested Closures at any depth, inline Object
 * bodies) contains {@code ^} runs under the non-materialized
 * {@link ProtosReturnHome#unobservable()} marker instead of a fresh home.
 * Every shape that may observe the home keeps the exact physical lifecycle.
 *
 * <p>The marker is already inactive and {@link ProtosReturnHome#complete()}
 * rejects a completed home, so a passing NLR-dead call also shows that the
 * owner neither completes it nor matches a transfer against it.
 */
final class ProtosPerf025VirtualReturnHomeTest {
    private static final Path CORE = Path.of("protos", "lib", "core");

    @Test
    void staticProofClassifiesDefaultsDescendantsAndObjectBodies() throws Exception {
        withCore(module -> {
            assertUnobservable(module, "() => 42");
            assertUnobservable(module, "(value) => value");
            assertUnobservable(module, "() => {\n    () => 42\n}");
            assertUnobservable(module, "(x = () => 1) => x");
            assertUnobservable(module, "() => {\n    Object {\n        m: () => 1\n    }\n}");

            assertObservable(module, "() => ^42");
            assertObservable(module, "(x = ^42) => x");
            assertObservable(module, "(x = () => ^42) => x");
            assertObservable(module, "() => {\n    () => ^42\n}");
            assertObservable(module, "() => {\n    () => {\n        () => ^42\n    }\n}");
            assertObservable(
                    module, "() => {\n    Object {\n        escape: () => { ^42 }\n    }\n}");
        });
        System.out.println("RETURN_HOME_STATIC_ANALYSIS=YES");
        System.out.println("ANALYSIS_RUN_PER_INVOCATION=NO");
    }

    @Test
    void nlrDeadInvocationsDoNotMaterializeAReturnHome() throws Exception {
        withCore(module -> {
            ProtosClosureValue constant = parsedClosure("() => 42", module);
            ProtosBytecodeRootNode.PreparedClosureCall first = fastDirect(constant, module);
            assertVirtualHome(first);
            assertInteger(42, enter(first));

            ProtosClosureValue identity = parsedClosure("(value) => value", module);
            ProtosBytecodeRootNode.PreparedClosureCall second =
                    fastDirect(identity, module, integer(5));
            assertVirtualHome(second);
            assertInteger(5, enter(second));
            ProtosActivation callee = ProtosFrameArguments.activation(second.targetArguments());
            assertTrue(callee.ownsReturnHome(), "the invocation still owns its (virtual) home");
            assertSame(ProtosReturnHome.unobservable(), callee.returnHome().orElseThrow());
        });
        System.out.println("SOURCE_NLR_DEAD_INVOCATION_NEW_PROTOS_RETURN_HOME=NO");
        System.out.println("SOURCE_NLR_DEAD_COMPLETE_CALL=NO");
    }

    @Test
    void nlrDeadDescendantKeepsSharedHomeProvenanceWithoutMaterialization()
            throws Exception {
        withCore(module -> {
            ProtosClosureValue outer = parsedClosure("() => {\n    () => 42\n}", module);
            ProtosBytecodeRootNode.PreparedClosureCall prepared = fastDirect(outer, module);
            assertVirtualHome(prepared);
            ProtosClosureValue child =
                    assertInstanceOf(ProtosClosureValue.class, enter(prepared));

            // PLAT044 admits a literal callback by its captured home provenance;
            // the child still shares (rather than owns) its creator's home.
            assertSame(ProtosReturnHome.unobservable(), child.returnHome().orElseThrow());
            ProtosActivation childInvocation =
                    ProtosActivation.forClosureInvocation(
                            child, List.of(), child.prelude().orElseThrow());
            assertFalse(childInvocation.ownsReturnHome());
            assertSame(child.returnHome().orElseThrow(), childInvocation.returnHome().orElseThrow());
        });
        System.out.println("NLR_DEAD_DESCENDANT_OWNER_HOME_MATERIALIZED=NO");
        System.out.println("NLR_DEAD_DESCENDANT_HOME_PROVENANCE=SHARED");
    }

    @Test
    void directAndDefaultReturnKeepPhysicalHomeLifecycle() throws Exception {
        withCore(module -> {
            ProtosClosureValue direct = parsedClosure("() => ^42", module);
            ProtosBytecodeRootNode.PreparedClosureCall call = fastDirect(direct, module);
            ProtosReturnHome home = assertPhysicalActiveHome(call);
            assertInteger(42, enter(call));
            assertFalse(home.isActive(), "the owned physical home completes with the call");

            ProtosClosureValue defaulted = parsedClosure("(x = ^42) => x", module);
            ProtosBytecodeRootNode.PreparedClosureCall defaultCall =
                    fastDirect(defaulted, module);
            ProtosReturnHome defaultHome = assertPhysicalActiveHome(defaultCall);
            assertInteger(42, enter(defaultCall));
            assertFalse(defaultHome.isActive());
        });
        System.out.println("DIRECT_CANONICAL_RETURN_RETAINS_PHYSICAL_HOME=YES");
        System.out.println("DEFAULT_CANONICAL_RETURN_RETAINS_PHYSICAL_HOME=YES");
    }

    @Test
    void descendantReturnRetainsOwnerHomeAtAnyDepth() throws Exception {
        withCore(module -> {
            ProtosClosureValue outer = parsedClosure("() => {\n    () => ^42\n}", module);
            ProtosBytecodeRootNode.PreparedClosureCall call = fastDirect(outer, module);
            ProtosReturnHome home = assertPhysicalActiveHome(call);
            ProtosClosureValue child =
                    assertInstanceOf(ProtosClosureValue.class, enter(call));
            assertSame(home, child.returnHome().orElseThrow());

            ProtosClosureValue transitive =
                    parsedClosure("() => {\n    () => {\n        () => ^42\n    }\n}", module);
            ProtosBytecodeRootNode.PreparedClosureCall transitiveCall =
                    fastDirect(transitive, module);
            ProtosReturnHome transitiveHome = assertPhysicalActiveHome(transitiveCall);
            ProtosClosureValue middle =
                    assertInstanceOf(ProtosClosureValue.class, enter(transitiveCall));
            assertSame(transitiveHome, middle.returnHome().orElseThrow());
        });
        System.out.println("OUTER_DIRECT_CANONICAL_RETURN=NO");
        System.out.println("DESCENDANT_CANONICAL_RETURN=YES");
        System.out.println("OUTER_HOME_MATERIALIZED=YES");
        System.out.println("TRANSITIVE_DESCENDANT_CANONICAL_RETURN_RETAINS_OWNER_HOME=YES");
    }

    @Test
    void nonLocalReturnSemanticsArePreserved() throws Exception {
        withCore(module -> {
            assertInteger(
                    42,
                    callParsed(module, "() => {\n    g: () => {\n        ^42\n    }\n    g()\n    0\n}"));
            assertInteger(
                    42,
                    callParsed(
                            module,
                            "() => {\n    g: () => {\n        h: () => {\n            ^42\n        }\n"
                                    + "        h()\n    }\n    g()\n    0\n}"));
            assertInteger(
                    42,
                    callParsed(
                            module,
                            "() => {\n    holder: Object {\n        escape: () => { ^42 }\n    }\n"
                                    + "    holder.escape()\n    0\n}"));

            ProtosClosureValue escaped =
                    assertInstanceOf(
                            ProtosClosureValue.class,
                            evaluate("make: () => {\n    () => {\n        ^42\n    }\n}\nmake()\n", module));
            ProtosExecutionOutcome failed =
                    ProtosRootTaskExecution.executeClosure(escaped, List.of(), module);
            assertSame(
                    ProtosCoreErrors.prototype(
                            module, ProtosCoreErrors.StandardError.INVALID_RETURN),
                    failed.error().parent().orElseThrow(),
                    "an escaped return to a completed home is InvalidReturn");
        });
        System.out.println("SAME_TASK_NLR=PRESERVED");
        System.out.println("OBJECT_BODY_DESCENDANT_RETURN_RETAINS_OWNER_HOME=YES");
        System.out.println("ESCAPED_NLR_INVALID_RETURN=PRESERVED");
    }

    @Test
    void literalInlineCallbackUnderVirtualHomeKeepsItsResult() throws Exception {
        withCore(module -> {
            ProtosClosureValue run =
                    parsedClosure("() => {\n    true.ifTrue(() => {\n        41 + 1\n    })\n}", module);
            assertTrue(planOf(run).returnHomeProvenUnobservableForRuntime());
            ProtosBytecodeRootNode.PreparedClosureCall call = fastDirect(run, module);
            assertVirtualHome(call);
            assertInteger(42, enter(call));
        });
        System.out.println("PLAT044_INLINE_CALLBACK_ADMISSION=PRESERVED");
    }

    @Test
    void nativeAndUnpreparedClosuresKeepAFreshPhysicalHome() throws Exception {
        withCore(module -> {
            ProtosClosureValue nativeClosure =
                    ProtosClosureValue.nativeClosure((activation, supplied) -> ProtosNullValue.INSTANCE);
            ProtosReturnHome nativeHome = nativeClosure.invocationReturnHomeForRuntime();
            assertTrue(nativeHome.isMaterialized());
            assertTrue(nativeHome.isActive());
            assertNotSame(nativeHome, nativeClosure.invocationReturnHomeForRuntime());

            ProtosClosureValue parsed = parsedClosure("() => 42", module);
            ProtosClosureValue unprepared =
                    new ProtosClosureValue(
                            parsed.definition(), List.of(), parsed.capturedReceiver());
            assertTrue(unprepared.invocationReturnHomeForRuntime().isMaterialized());
        });
        System.out.println("NATIVE_UNKNOWN_FALLBACK=PRESERVED");
    }

    private interface CoreBody {
        void run(ProtosActivation module) throws Exception;
    }

    private static void withCore(CoreBody body) throws Exception {
        try (Context context = Context.newBuilder(ProtosLanguage.ID).build()) {
            context.initialize(ProtosLanguage.ID);
            context.enter();
            try {
                body.run(coreModule());
            } finally {
                context.leave();
            }
        }
    }

    private static void assertUnobservable(ProtosActivation module, String characters) {
        assertTrue(
                planOf(parsedClosure(characters, module)).returnHomeProvenUnobservableForRuntime(),
                () -> "expected return-home-unobservable: " + characters);
    }

    private static void assertObservable(ProtosActivation module, String characters) {
        assertFalse(
                planOf(parsedClosure(characters, module)).returnHomeProvenUnobservableForRuntime(),
                () -> "expected return-home-observable: " + characters);
    }

    private static ProtosClosureExecutionPlan planOf(ProtosClosureValue closure) {
        return closure.executionPlan().orElseThrow();
    }

    private static void assertVirtualHome(ProtosBytecodeRootNode.PreparedClosureCall call) {
        assertSame(
                ProtosReturnHome.unobservable(),
                ProtosFrameArguments.compactReturnHome(call.targetArguments()),
                "a proven-unobservable owned home is not materialized");
        assertTrue(ProtosFrameArguments.compactOwnsReturnHome(call.targetArguments()));
    }

    private static ProtosReturnHome assertPhysicalActiveHome(
            ProtosBytecodeRootNode.PreparedClosureCall call) {
        ProtosReturnHome home = ProtosFrameArguments.compactReturnHome(call.targetArguments());
        assertTrue(home.isMaterialized());
        assertTrue(home.isActive());
        assertTrue(ProtosFrameArguments.compactOwnsReturnHome(call.targetArguments()));
        return home;
    }

    private static Object callParsed(ProtosActivation module, String characters) {
        return enter(fastDirect(parsedClosure(characters, module), module));
    }

    private static ProtosBytecodeRootNode.PreparedClosureCall fastDirect(
            ProtosClosureValue closure, ProtosActivation caller, Object... supplied) {
        ProtosLanguageContext entered = ProtosLanguageContext.currentIfEnteredForRuntime();
        ProtosClosureValue selected =
                ProtosBytecodeRootNode.directClosureCallSelectionOrNull(closure, caller);
        assertSame(closure, selected, "canonical direct Closure-call selection");
        RootCallTarget target =
                ProtosBytecodeRootNode.PrepareSendArguments.fastOrdinarySendTarget(
                        closure, entered);
        return ProtosBytecodeRootNode.PrepareClosureCallArguments.fastDirect(
                closure,
                caller,
                supplied,
                selected,
                selected.definition(),
                entered,
                selected.definition(),
                entered,
                target);
    }

    private static Object enter(ProtosBytecodeRootNode.PreparedClosureCall prepared) {
        Object entered =
                ProtosBytecodeRootNode.EnterClosureCall.ordinaryIndirect(
                        assertInstanceOf(ProtosBytecodeRootNode.OrdinarySourceCall.class, prepared),
                        IndirectCallNode.create());
        return prepared.finish(entered);
    }

    private static ProtosActivation coreModule() throws Exception {
        ProtosPrelude prelude = new ProtosCoreBootstrap().bootstrap(CORE);
        return prelude.newModuleActivation();
    }

    private static Object evaluate(String characters, ProtosActivation activation) {
        Source source =
                Source.newBuilder(ProtosLanguage.ID, characters, "perf025-virtual-home.protos")
                        .mimeType(ProtosLanguage.MIME_TYPE)
                        .build();
        CallTarget target = ProtosLanguageContext.current().parsePublic(source);
        return target.call(activation);
    }

    private static ProtosClosureValue parsedClosure(
            String characters, ProtosActivation activation) {
        return assertInstanceOf(ProtosClosureValue.class, evaluate(characters, activation));
    }

    private static ProtosIntegerValue integer(long value) {
        return new ProtosIntegerValue(value);
    }

    private static void assertInteger(long expected, Object value) {
        assertEquals(Long.toString(expected), com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerDecimalText(value));
    }
}
