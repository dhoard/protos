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
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.guillermomolina.protos.runtime.ProtosActivation;
import com.guillermomolina.protos.runtime.ProtosArrayValue;
import com.guillermomolina.protos.runtime.ProtosClosureValue;
import com.guillermomolina.protos.runtime.ProtosExecutionContextValue;
import com.guillermomolina.protos.runtime.ProtosIntegerValue;
import com.guillermomolina.protos.runtime.ProtosPrelude;
import com.guillermomolina.protos.runtime.ProtosSignalException;
import com.guillermomolina.protos.runtime.ProtosTask;
import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.IndirectCallNode;
import com.oracle.truffle.api.source.Source;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;

/**
 * PERF025-H1 structural coverage: a semantic source root entered through the
 * compact source-call ABI has no activation prologue. Its ordinary parameter
 * and body path runs from the compact frame arguments and frame locals; the
 * exact rich {@link ProtosActivation} is materialized only when an operation,
 * an Error path or tooling observes it, at most once, and is then published
 * into frame argument 0 for every later observer.
 *
 * <p>Materialization is observed structurally: frame argument 0 of the target
 * arguments stays the callee Closure exactly while no activation exists.
 */
final class ProtosPerf025H1LazyRootActivationTest {
    private static final Path CORE = Path.of("protos", "lib", "core");

    @Test
    void zeroArgumentDirectCallRunsWithoutRichActivation() throws Exception {
        withCore(module -> {
            ProtosClosureValue one = parsedClosure("() => { 1 }", "h1-zero.protos", module);
            ProtosBytecodeRootNode.PreparedClosureCall prepared = fastDirect(one, module);

            assertEquals("1", integerValue(enter(prepared)));
            assertSame(one, prepared.targetArguments()[0], "no activation was materialized");
            assertOnlyCallerActivation(prepared.targetArguments(), module);
        });
        System.out.println("ZERO_ARG_DIRECT_SOURCE_CALL_WITHOUT_RICH_ACTIVATION=PASS");
    }

    @Test
    void singleParameterIsBoundFromCompactArgumentsWithoutRichActivation() throws Exception {
        withCore(module -> {
            ProtosClosureValue identity =
                    parsedClosure("(value) => { value }", "h1-identity.protos", module);
            ProtosIntegerValue supplied = integer(42);
            ProtosBytecodeRootNode.PreparedClosureCall prepared =
                    fastDirect(identity, module, supplied);

            assertSame(supplied, enter(prepared), "the exact supplied value");
            assertSame(identity, prepared.targetArguments()[0], "no activation was materialized");
            assertOnlyCallerActivation(prepared.targetArguments(), module);
        });
        System.out.println("SINGLE_ARG_DIRECT_SOURCE_CALL_WITHOUT_RICH_ACTIVATION=PASS");
    }

    @Test
    void defaultParameterPathsStayCompact() throws Exception {
        withCore(module -> {
            ProtosClosureValue defaulted =
                    parsedClosure("(value = 7) => { value }", "h1-default.protos", module);

            ProtosBytecodeRootNode.PreparedClosureCall omitted = fastDirect(defaulted, module);
            assertEquals("7", integerValue(enter(omitted)));
            assertSame(defaulted, omitted.targetArguments()[0]);

            ProtosIntegerValue supplied = integer(3);
            ProtosBytecodeRootNode.PreparedClosureCall given =
                    fastDirect(defaulted, module, supplied);
            assertSame(supplied, enter(given));
            assertSame(defaulted, given.targetArguments()[0]);
        });
        System.out.println("DEFAULT_PARAMETER_COMPACT_PATH=PASS");
    }

    @Test
    void repeatedInvocationsStayCompactAndIndependent() throws Exception {
        withCore(module -> {
            ProtosClosureValue identity =
                    parsedClosure("(value) => { value }", "h1-repeated.protos", module);
            List<ProtosBytecodeRootNode.PreparedClosureCall> calls = new java.util.ArrayList<>();
            for (int index = 0; index < 4; index++) {
                ProtosBytecodeRootNode.PreparedClosureCall prepared =
                        fastDirect(identity, module, integer(index));
                assertEquals(Long.toString(index), integerValue(enter(prepared)));
                assertSame(identity, prepared.targetArguments()[0]);
                calls.add(prepared);
            }
            assertNotSame(calls.get(0).targetArguments(), calls.get(1).targetArguments());
            // PERF025: `(value) => { value }` is proven return-home unobservable,
            // so no invocation materializes a home of its own.
            for (ProtosBytecodeRootNode.PreparedClosureCall call : calls) {
                assertTrue(!ProtosFrameArguments.compactReturnHome(call.targetArguments())
                        .isMaterialized());
            }
        });
        System.out.println("REPEATED_COMPACT_INVOCATIONS=PASS");
    }

    @Test
    void activationIsMaterializedOnDemandOnceAndReused() throws Exception {
        withCore(module -> {
            ProtosClosureValue identity =
                    parsedClosure("(value) => { value }", "h1-on-demand.protos", module);
            ProtosIntegerValue supplied = integer(5);
            ProtosBytecodeRootNode.PreparedClosureCall prepared =
                    fastDirect(identity, module, supplied);
            Object[] arguments = prepared.targetArguments();

            ProtosActivation observed = ProtosFrameArguments.activation(arguments);
            assertSame(observed, arguments[0], "published into frame argument 0");
            assertSame(observed, ProtosFrameArguments.activation(arguments), "at most once");
            assertNull(privateField(observed, "context"), "the guest Context stays deferred");

            assertSame(supplied, enter(prepared));
            assertSame(observed, arguments[0], "the root reuses the published activation");
            assertSame(supplied, observed.suppliedArgumentsForRuntime().get(0));

            ProtosClosureValue sending =
                    parsedClosure("(value) => { value + 1 }", "h1-send.protos", module);
            ProtosBytecodeRootNode.PreparedClosureCall send =
                    fastDirect(sending, module, integer(1));
            assertEquals("2", integerValue(enter(send)));
            ProtosActivation callee =
                    assertInstanceOf(ProtosActivation.class, send.targetArguments()[0]);
            assertSame(callee, ProtosFrameArguments.activation(send.targetArguments()));
            assertNull(privateField(callee, "context"));
        });
        System.out.println("ON_DEMAND_ACTIVATION_MATERIALIZATION=PASS");
        System.out.println("ACTIVATION_MATERIALIZED_AT_MOST_ONCE=PASS");
    }

    @Test
    void contextObservationKeepsFreshPerInvocationIdentity() throws Exception {
        withCore(module -> {
            List<Object> fresh =
                    elements(evaluate(
                            "f: () => { context }\n[f(), f()]\n", "h1-fresh.protos", module));
            assertInstanceOf(ProtosExecutionContextValue.class, fresh.get(0));
            assertNotSame(fresh.get(0), fresh.get(1));

            List<Object> same =
                    elements(evaluate(
                            "g: () => { [context, context] }\ng()\n", "h1-same.protos", module));
            assertInstanceOf(ProtosExecutionContextValue.class, same.get(0));
            assertSame(same.get(0), same.get(1));
        });
        System.out.println("FRESH_CONTEXT_PER_INVOCATION=PASS");
        System.out.println("SAME_CONTEXT_WITHIN_INVOCATION=PASS");
    }

    @Test
    void captureThisAndNonLocalReturnSemanticsArePreserved() throws Exception {
        withCore(module -> {
            assertEquals("2", integerValue(evaluate(
                            "n: 0\ninc: () => { n = n + 1 }\ninc()\ninc()\nn\n",
                            "h1-capture.protos", module)));

            ProtosClosureValue receiverOf =
                    parsedClosure("() => { this }", "h1-this.protos", module);
            assertSame(receiverOf.capturedReceiver(), enter(fastDirect(receiverOf, module)));

            assertEquals("5", integerValue(enter(fastDirect(
                            parsedClosure("() => { ^ 5 }", "h1-owned.protos", module),
                            module))));
            assertEquals("7", integerValue(evaluate(
                            "outer: () => {\n    inner: () => { ^ 7 }\n    inner()\n    99\n}\n"
                                    + "outer()\n",
                            "h1-nested.protos", module)));
            assertThrows(
                    ProtosSignalException.class,
                    () -> evaluate(
                            "make: () => { () => { ^ 1 } }\nescaped: make()\nescaped()\n",
                            "h1-escaped.protos", module));
        });
        System.out.println("CAPTURE_BY_REFERENCE=PASS");
        System.out.println("THIS_SEMANTICS=PASS");
        System.out.println("NON_LOCAL_RETURN=PASS");
    }

    @Test
    void arityErrorsMaterializeOnTheErrorPathOnly() throws Exception {
        withCore(module -> {
            ProtosClosureValue identity =
                    parsedClosure("(value) => { value }", "h1-arity.protos", module);

            ProtosBytecodeRootNode.PreparedClosureCall tooMany =
                    fastDirect(identity, module, integer(1), integer(2));
            assertThrows(ProtosSignalException.class, () -> enter(tooMany));
            assertInstanceOf(ProtosActivation.class, tooMany.targetArguments()[0]);

            ProtosBytecodeRootNode.PreparedClosureCall tooFew = fastDirect(identity, module);
            assertThrows(ProtosSignalException.class, () -> enter(tooFew));
            assertInstanceOf(ProtosActivation.class, tooFew.targetArguments()[0]);
        });
        System.out.println("ARITY_ERROR_PATH=PASS");
    }

    @Test
    void debuggerScopeMaterializesOneExactActivation() throws Exception {
        withCore(module -> {
            ProtosClosureValue identity =
                    parsedClosure("(value) => { value }", "h1-scope.protos", module);
            Object[] arguments = fastDirect(identity, module, integer(1)).targetArguments();
            VirtualFrame frame =
                    Truffle.getRuntime()
                            .createVirtualFrame(arguments, FrameDescriptor.newBuilder().build());

            assertTrue(ProtosBytecodeTagTreeNodeExports.hasScope(null, frame));
            assertSame(identity, arguments[0], "asking for scope presence does not materialize");
            ProtosBytecodeTagTreeNodeExports.getScope(null, frame, true);
            ProtosActivation observed = assertInstanceOf(ProtosActivation.class, arguments[0]);
            ProtosBytecodeTagTreeNodeExports.getScope(null, frame, true);
            assertSame(observed, arguments[0]);
        });
        System.out.println("DEBUGGER_REFLECTION_PROJECTION=PASS");
    }

    @Test
    void taskOwnedCompactEntryKeepsExactTaskWithoutActivation() throws Exception {
        withCore(module -> {
            ProtosClosureValue identity =
                    parsedClosure("(value) => { value }", "h1-task.protos", module);
            AtomicReference<ProtosBytecodeRootNode.PreparedClosureCall> preparedRef =
                    new AtomicReference<>();
            ProtosTask task =
                    module.executionDomain()
                            .createTask(
                                    null,
                                    current -> {
                                        ProtosBytecodeRootNode.PreparedClosureCall prepared =
                                                ProtosBytecodeRootNode
                                                        .prepareTaskOwnedDirectClosureIfBytecode(
                                                                identity,
                                                                List.of(integer(9)),
                                                                module,
                                                                current);
                                        preparedRef.set(prepared);
                                        ProtosBytecodeTaskExecution.executePreparedClosure(
                                                current, prepared);
                                    });
            assertTrue(module.executionDomain().dispatchOne());

            assertEquals(ProtosTask.State.COMPLETED, task.state());
            assertEquals("9", integerValue(task.result().orElseThrow()));
            Object[] arguments = preparedRef.get().targetArguments();
            assertSame(identity, arguments[0], "no activation was materialized");
            assertSame(task, ProtosFrameArguments.compactTask(arguments));
            assertSame(task, ProtosFrameArguments.activation(arguments).task().orElseThrow());
            assertTrue(module.task().isEmpty());
        });
        System.out.println("TASK_PROVENANCE=PASS");
    }

    @Test
    void richActivationEntryKeepsTheGenericPath() throws Exception {
        withCore(module -> {
            ProtosClosureValue identity =
                    parsedClosure("(value) => { value }", "h1-generic.protos", module);
            ProtosIntegerValue supplied = integer(11);
            ProtosActivation activation =
                    ProtosActivation.forClosureInvocation(
                            identity,
                            List.of(supplied),
                            module.prelude().orElseThrow(),
                            module.actorModuleState(),
                            module.currentModuleKey().orElse(null),
                            module.executionDomain());
            RootCallTarget target =
                    ProtosBytecodeRootNode.PrepareSendArguments.fastOrdinarySendTarget(
                            identity, ProtosLanguageContext.currentIfEnteredForRuntime());
            Object[] arguments = new Object[] {activation};

            assertSame(supplied, target.call(arguments));
            assertSame(activation, arguments[0]);
        });
        System.out.println("GENERIC_FALLBACK=PASS");
    }

    private interface CoreTest {
        void run(ProtosActivation module) throws Exception;
    }

    private static void withCore(CoreTest test) throws Exception {
        try (Context context = Context.newBuilder(ProtosLanguage.ID).build()) {
            context.initialize(ProtosLanguage.ID);
            context.enter();
            try {
                ProtosPrelude prelude = new ProtosCoreBootstrap().bootstrap(CORE);
                test.run(prelude.newModuleActivation());
            } finally {
                context.leave();
            }
        }
    }

    private static void assertOnlyCallerActivation(Object[] arguments, ProtosActivation caller) {
        assertTrue(
                java.util.Arrays.stream(arguments)
                        .filter(ProtosActivation.class::isInstance)
                        .allMatch(candidate -> candidate == caller),
                "only the caller's provenance activation exists");
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

    private static Object evaluate(String characters, String name, ProtosActivation activation) {
        Source source =
                Source.newBuilder(ProtosLanguage.ID, characters, name)
                        .mimeType(ProtosLanguage.MIME_TYPE)
                        .build();
        CallTarget target = ProtosLanguageContext.current().parsePublic(source);
        return target.call(activation);
    }

    private static ProtosClosureValue parsedClosure(
            String characters, String name, ProtosActivation activation) {
        return assertInstanceOf(
                ProtosClosureValue.class, evaluate(characters, name, activation));
    }

    private static List<Object> elements(Object array) {
        return assertInstanceOf(ProtosArrayValue.class, array).indexedSnapshot();
    }

    private static ProtosIntegerValue integer(long value) {
        return new ProtosIntegerValue(value);
    }

    private static String integerValue(Object value) {
        return com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerDecimalText(value);
    }

    private static Object privateField(Object target, String name)
            throws ReflectiveOperationException {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }
}
