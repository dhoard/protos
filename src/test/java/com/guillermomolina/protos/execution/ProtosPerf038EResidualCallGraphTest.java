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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.guillermomolina.protos.runtime.ProtosActivation;
import com.guillermomolina.protos.runtime.ProtosArrayValue;
import com.guillermomolina.protos.runtime.ProtosClosureValue;
import com.guillermomolina.protos.runtime.ProtosIntegerValue;
import com.guillermomolina.protos.runtime.ProtosObjectValue;
import com.guillermomolina.protos.runtime.ProtosPrelude;
import com.guillermomolina.protos.runtime.ProtosReturnHome;
import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.source.Source;
import java.nio.file.Path;
import java.util.List;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;

/**
 * PERF038-E residual monomorphic call-graph infrastructure. The guarded
 * ordinary send specializes on its caller's provenance state (published,
 * compact inheriting, compact materializing), the Closure prelude is read
 * without an {@code Optional}, the guarded preparation reuses the values its
 * specialization established, and an ordinary call without a return-home
 * lifecycle finishes without one. These checks pin the provenance decision
 * and carrier state directly, and run guest programs over every path the
 * fast forms must leave unchanged.
 */
final class ProtosPerf038EResidualCallGraphTest {
    private static final Path CORE = Path.of("protos", "lib", "core");

    /**
     * A compact caller passes its own caller in its place exactly when the
     * callee would inherit that caller's prelude; a Closure with a different
     * prelude of its own, or a published frame, is never treated as inherited.
     */
    @Test
    void inheritedProvenanceFollowsTheClosurePrelude() throws Exception {
        withCore(module -> {
            ProtosClosureValue parsed = parsedClosure("(v) => { v }", "perf038e-prelude.protos", module);
            ProtosPrelude callerPrelude = module.preludeOrNullForRuntime();
            ProtosPrelude otherPrelude = otherPrelude();
            assertNotSame(callerPrelude, otherPrelude);

            ProtosClosureValue none = withPrelude(parsed, null);
            ProtosClosureValue same = withPrelude(parsed, callerPrelude);
            ProtosClosureValue other = withPrelude(parsed, otherPrelude);
            assertNull(none.preludeOrNullForRuntime());
            assertSame(callerPrelude, same.preludeOrNullForRuntime());
            assertSame(other.prelude().orElseThrow(), other.preludeOrNullForRuntime());

            assertSame(module, inherited(methodCall(none, module)));
            assertSame(module, inherited(methodCall(same, module)));
            assertNull(inherited(methodCall(other, module)), "a different prelude is not inherited");

            assertSame(callerPrelude, ProtosFrameArguments.preludeOrNull(methodCall(none, module)));
            assertSame(otherPrelude, ProtosFrameArguments.preludeOrNull(methodCall(other, module)));

            Object[] published = methodCall(none, module);
            ProtosActivation callee = ProtosFrameArguments.activation(published);
            assertSame(callerPrelude, callee.preludeOrNullForRuntime());
            assertNull(inherited(published), "a published frame is never a compact caller");

            Object[] mismatched = methodCall(other, module);
            assertSame(otherPrelude,
                    ProtosFrameArguments.activation(mismatched).preludeOrNullForRuntime(),
                    "materialization keeps the Closure's own prelude");
        });
        System.out.println("PERF038E_INHERITED_PROVENANCE=PASS");
        System.out.println("PERF038E_PRELUDE_MISMATCH=PASS");
    }

    /** The inherited decision is re-evaluated per array, so a prelude change between executions is seen. */
    @Test
    void preludeChangeBetweenExecutionsIsObserved() throws Exception {
        withCore(module -> {
            ProtosClosureValue parsed = parsedClosure("(v) => { v }", "perf038e-change.protos", module);
            ProtosClosureValue none = withPrelude(parsed, null);
            ProtosClosureValue other = withPrelude(parsed, otherPrelude());
            assertSame(module, inherited(methodCall(none, module)));
            assertNull(inherited(methodCall(other, module)));
            assertSame(module, inherited(methodCall(none, module)));
        });
        System.out.println("PERF038E_PRELUDE_CHANGE=PASS");
    }

    /** The argument read under a proven count answers the general read. */
    @Test
    void suppliedArgumentReadWithinCount() throws Exception {
        withCore(module -> {
            ProtosClosureValue parsed =
                    parsedClosure("(a, b) => { a }", "perf038e-arguments.protos", module);
            ProtosIntegerValue one = integer(1);
            ProtosIntegerValue two = integer(2);
            Object[] arguments =
                    ProtosFrameArguments.compactImmediateMethodCall(
                            parsed, parsed, new ProtosObjectValue(parsed), module,
                            new Object[] {one, two});
            assertEquals(2, ProtosFrameArguments.compactSuppliedArgumentCount(arguments));
            for (int index = 0; index < 2; index++) {
                assertSame(ProtosFrameArguments.compactSuppliedArgument(arguments, index),
                        ProtosFrameArguments.compactSuppliedArgumentWithinCount(arguments, index));
            }
        });
        System.out.println("PERF038E_ARGUMENT_READ=PASS");
    }

    /**
     * Only a call owning a materialized home has a return-home lifecycle; the
     * unobservable marker and a captured home owned elsewhere have none.
     */
    @Test
    void returnHomeLifecycleOnlyForOwnedMaterializedHomes() throws Exception {
        withCore(module -> {
            ProtosClosureValue parsed = parsedClosure("(v) => { v }", "perf038e-home.protos", module);
            Object[] arguments = methodCall(parsed, module);
            ProtosBytecodeRootNode.OrdinarySourceCall unobservable =
                    carrier(arguments, ProtosReturnHome.unobservable(), true);
            ProtosBytecodeRootNode.OrdinarySourceCall owned =
                    carrier(arguments, new ProtosReturnHome(), true);
            ProtosBytecodeRootNode.OrdinarySourceCall captured =
                    carrier(arguments, new ProtosReturnHome(), false);
            assertFalse(unobservable.hasReturnHomeLifecycle());
            assertTrue(owned.hasReturnHomeLifecycle());
            assertFalse(captured.hasReturnHomeLifecycle());
        });
        System.out.println("PERF038E_RETURN_HOME_LIFECYCLE=PASS");
    }

    /**
     * A compact method root sends through an inherited caller, then observes
     * its own Context (materializing itself), then sends again through its
     * now-published activation; handler selection two roots outward holds.
     */
    @Test
    void compactCallerMaterializedAfterInheritedSend() throws Exception {
        withCore(module -> {
            List<Object> results =
                    elements(evaluate(
                            "leaf: {\n"
                                    + "    identity: (v) => { v }\n"
                                    + "    observe: (v) => {\n"
                                    + "        seen: context\n"
                                    + "        v\n"
                                    + "    }\n"
                                    + "    fail: (v) => { Error().signal() }\n"
                                    + "}\n"
                                    + "middle: {\n"
                                    + "    forward: (v) => {\n"
                                    + "        a: leaf.identity(v)\n"
                                    + "        here: context\n"
                                    + "        leaf.observe(a + 1)\n"
                                    + "    }\n"
                                    + "    failing: (v) => { leaf.fail(v) }\n"
                                    + "}\n"
                                    + "top: { run: (v) => {\n"
                                    + "    Error.handle(() => { middle.failing(v) },"
                                    + " (caught) => { 50 + v })\n"
                                    + "} }\n"
                                    + "[middle.forward(1), middle.forward(2), top.run(1),"
                                    + " middle.forward(3), top.run(2)]\n",
                            "perf038e-materialize.protos", module));
            assertIntegers(results, 2, 3, 51, 4, 52);
        });
        System.out.println("PERF038E_LATER_MATERIALIZATION=PASS");
        System.out.println("PERF038E_CONTEXT_OBSERVATION=PASS");
    }

    /**
     * One site finishes calls without and with a return-home lifecycle; the
     * non-local return still targets the method's own home, and a method
     * without one keeps returning its value.
     */
    @Test
    void returnHomeObservableAndUnobservableAtOneSite() throws Exception {
        withCore(module -> {
            List<Object> results =
                    elements(evaluate(
                            "plain: { pick: (limit) => { limit + 1 } }\n"
                                    + "finder: { pick: (limit) => {\n"
                                    + "    Array(1, 5, 9).each((x) => {\n"
                                    + "        (x > limit).ifTrue(() => { ^ x })\n"
                                    + "    })\n"
                                    + "    0\n"
                                    + "} }\n"
                                    + "run: (o, v) => { o.pick(v) }\n"
                                    + "[run(plain, 1), run(plain, 2), run(finder, 3), run(finder, 6),"
                                    + " run(finder, 10), run(plain, 3)]\n",
                            "perf038e-return-home.protos", module));
            assertIntegers(results, 2, 3, 5, 9, 0, 4);
        });
        System.out.println("PERF038E_RETURN_HOME_UNOBSERVABLE=PASS");
        System.out.println("PERF038E_NON_LOCAL_RETURN=PASS");
    }

    /**
     * Replacing the selected method after an inherited-provenance site is warm
     * invalidates the selection and respecializes.
     */
    @Test
    void invalidationRespecializesInheritedSite() throws Exception {
        withCore(module -> {
            List<Object> results =
                    elements(evaluate(
                            "leaf: { pick: (v) => { v } }\n"
                                    + "middle: { forward: (v) => { leaf.pick(v) } }\n"
                                    + "before: [middle.forward(1), middle.forward(2)]\n"
                                    + "leaf.pick = (v) => { v + 100 }\n"
                                    + "[before[0], before[1], middle.forward(3), middle.forward(4)]\n",
                            "perf038e-invalidation.protos", module));
            assertIntegers(results, 1, 2, 103, 104);
        });
        System.out.println("PERF038E_INVALIDATION=PASS");
    }

    /** A warm monomorphic site that later sees more receivers than its limit stays exact. */
    @Test
    void monomorphicSiteBecomesPolymorphic() throws Exception {
        withCore(module -> {
            List<Object> results =
                    elements(evaluate(
                            "a: { m: (v) => { v + 1 } }\n"
                                    + "b: { m: (v) => { v + 2 } }\n"
                                    + "c: { m: (v) => { v + 3 } }\n"
                                    + "d: { m: (v) => { v + 4 } }\n"
                                    + "e: { m: (v) => { v + 5 } }\n"
                                    + "middle: { forward: (o, v) => { o.m(v) } }\n"
                                    + "[middle.forward(a, 1), middle.forward(a, 1), middle.forward(b, 1),"
                                    + " middle.forward(c, 1), middle.forward(d, 1), middle.forward(e, 1),"
                                    + " middle.forward(a, 1)]\n",
                            "perf038e-polymorphic.protos", module));
            assertIntegers(results, 2, 2, 3, 4, 5, 6, 2);
        });
        System.out.println("PERF038E_POLYMORPHIC=PASS");
    }

    /** Lexical authority, owner and {@code super} stay exact through compact forwarding roots. */
    @Test
    void lexicalOwnerAndSuperThroughCompactRoots() throws Exception {
        withCore(module -> {
            List<Object> results =
                    elements(evaluate(
                            "base: {\n"
                                    + "    value: 1\n"
                                    + "    read: (delta) => { value + delta }\n"
                                    + "}\n"
                                    + "child: base {\n"
                                    + "    read: (delta) => { super.read(delta) + 10 }\n"
                                    + "}\n"
                                    + "leaf: child { value: 7 }\n"
                                    + "middle: { forward: (o, v) => { o.read(v) } }\n"
                                    + "make: (seed) => {\n"
                                    + "    target: { identity: (v) => { v + seed } }\n"
                                    + "    { forward: (v) => { target.identity(v) } }\n"
                                    + "}\n"
                                    + "x: make(100)\n"
                                    + "y: make(200)\n"
                                    + "[middle.forward(leaf, 1), middle.forward(leaf, 2),"
                                    + " middle.forward(base, 3), x.forward(1), y.forward(1),"
                                    + " x.forward(2)]\n",
                            "perf038e-owner.protos", module));
            assertIntegers(results, 18, 19, 4, 101, 201, 102);
        });
        System.out.println("PERF038E_OWNER_AND_SUPER=PASS");
    }

    private static Object[] methodCall(ProtosClosureValue closure, ProtosActivation caller) {
        return ProtosFrameArguments.compactImmediateMethodCall(
                closure, closure, new ProtosObjectValue(closure), caller, new Object[] {integer(1)});
    }

    private static ProtosActivation inherited(Object[] arguments) {
        return ProtosSemanticBytecodeRootNode.PrepareSendArguments
                .inheritedProvenanceCallerOrNull(arguments);
    }

    /*
     * The carrier is only inspected, never entered: with a zero supplied count
     * no entry target is selected, so no body target is needed.
     */
    private static ProtosBytecodeRootNode.OrdinarySourceCall carrier(
            Object[] arguments, ProtosReturnHome home, boolean owns) {
        return assertInstanceOf(
                ProtosBytecodeRootNode.OrdinarySourceCall.class,
                ProtosBytecodeRootNode.PreparedClosureCall.ordinaryCompactPrepared(
                        null, arguments, home, owns, 0));
    }

    private static ProtosClosureValue withPrelude(ProtosClosureValue source, ProtosPrelude prelude) {
        return new ProtosClosureValue(
                source.definition(),
                source.capturedLexicalContexts(),
                source.capturedReceiver(),
                null,
                null,
                prelude);
    }

    private static ProtosPrelude otherPrelude() {
        ProtosObjectValue contextPrototype = new ProtosObjectValue(ProtosObjectValue.rootObject());
        ProtosObjectValue bindings = new ProtosObjectValue(contextPrototype);
        bindings.createLocalSlot("Context", contextPrototype);
        bindings.createLocalSlot("Error", new ProtosObjectValue(ProtosObjectValue.rootObject()));
        bindings.createLocalSlot("Array", new ProtosObjectValue(ProtosObjectValue.rootObject()));
        bindings.freeze();
        return new ProtosPrelude(bindings, contextPrototype);
    }

    private static void assertIntegers(List<Object> results, long... expected) {
        assertEquals(expected.length, results.size());
        for (int index = 0; index < expected.length; index++) {
            assertEquals(Long.toString(expected[index]), integerValue(results.get(index)));
        }
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
        return assertInstanceOf(ProtosClosureValue.class, evaluate(characters, name, activation));
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
}
