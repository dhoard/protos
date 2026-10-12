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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.guillermomolina.protos.runtime.ProtosActivation;
import com.guillermomolina.protos.runtime.ProtosArrayValue;
import com.guillermomolina.protos.runtime.ProtosClosureValue;
import com.guillermomolina.protos.runtime.ProtosCoreErrors;
import com.guillermomolina.protos.runtime.ProtosIntegerValue;
import com.guillermomolina.protos.runtime.ProtosNullValue;
import com.guillermomolina.protos.runtime.ProtosObjectValue;
import com.guillermomolina.protos.runtime.ProtosPrelude;
import com.guillermomolina.protos.runtime.ProtosSignalException;
import com.oracle.truffle.api.bytecode.ContinuationResult;
import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.source.Source;
import java.nio.file.Path;
import java.util.List;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;

/**
 * PERF038-F residual primitive-method-call graph. The inherited-provenance
 * send is split by the credited prelude relation and decided by fixed
 * full-header slots, root-level argument and arity guards are decided by the
 * array length alone, and a cleared captured owner frame retires its cache
 * instead of keeping a compiled alternative. These checks pin every new
 * decision against the general one it replaces and run guest programs over
 * the paths the specialized forms must leave unchanged.
 */
final class ProtosPerf038FResidualPrimitiveCallTest {
    private static final Path CORE = Path.of("protos", "lib", "core");
    private static final int WARM_UP_CALLS = 40;

    /**
     * For every full header the split forms accept exactly the arrays the
     * general inherited-provenance query answers non-null for, with the same
     * caller; minimal headers are left to the general query.
     */
    @Test
    void fullHeaderProvenanceSplitMatchesGeneralDecision() throws Exception {
        withCore(module -> {
            ProtosClosureValue parsed = parsedClosure("(v) => { v }", "perf038f-prelude.protos", module);
            ProtosPrelude callerPrelude = module.preludeOrNullForRuntime();
            ProtosClosureValue[] closures = {
                withPrelude(parsed, null), withPrelude(parsed, callerPrelude),
                withPrelude(parsed, otherPrelude())
            };
            for (ProtosClosureValue closure : closures) {
                Object[] method = ProtosFrameArguments.compactImmediateMethodCall(
                        closure, closure, new ProtosObjectValue(closure), module,
                        new Object[] {integer(1)});
                Object[] direct = ProtosFrameArguments.compactDirectClosureCall(
                        closure, module, null, new Object[] {integer(1)});
                for (Object[] arguments : List.of(method, direct)) {
                    assertTrue(ProtosFrameArguments.isUnmaterializedInheritingFullHeader(arguments));
                    assertSame(module, ProtosFrameArguments.unmaterializedFullHeaderCaller(arguments));
                    boolean split =
                            ProtosSemanticBytecodeRootNode.PrepareSendArguments.ownPreludeAbsent(arguments)
                                    || ProtosSemanticBytecodeRootNode.PrepareSendArguments
                                            .ownPreludeIsCallers(arguments);
                    ProtosActivation general = inherited(arguments);
                    assertEquals(general != null, split);
                    if (general != null) {
                        assertSame(general,
                                ProtosSemanticBytecodeRootNode.PrepareSendArguments.fullHeaderCaller(
                                        arguments));
                    }
                }
                Object[] minimal = ProtosFrameArguments.compactDirectClosureCall(
                        closure, module, null, new Object[0]);
                assertFalse(ProtosFrameArguments.isUnmaterializedInheritingFullHeader(minimal));
                ProtosFrameArguments.activation(method);
                assertFalse(ProtosFrameArguments.isUnmaterializedInheritingFullHeader(method),
                        "a published frame is never a compact caller");
            }
        });
        System.out.println("PERF038F_PROVENANCE_SPLIT=PASS");
    }

    /**
     * PERF038-H: zero, one and multiple supplied arguments retain the
     * original compact method-call ABI and exact argument references.
     */
    @Test
    void compactMethodArgumentArityPathsPreserveIdentity() throws Exception {
        withCore(module -> {
            ProtosClosureValue closure = parsedClosure(
                    "(a, b, c) => { a }",
                    "perf038h-arguments.protos",
                    module);
            ProtosObjectValue receiver = new ProtosObjectValue(closure);
            ProtosObjectValue home = new ProtosObjectValue(closure);
            Object first = integer(11);
            Object second = integer(22);
            Object third = integer(33);

            Object[][] suppliedCases = {
                {},
                {first},
                {first, second, third}
            };

            for (Object[] supplied : suppliedCases) {
                Object[] method = ProtosFrameArguments.compactImmediateMethodCall(
                        closure, receiver, home, module, supplied);
                boolean minimalMethod =
                        supplied.length == 0
                                && ProtosFrameArguments.compactReturnHome(method)
                                        == com.guillermomolina.protos.runtime.ProtosReturnHome
                                                .unobservable();

                assertEquals(
                        minimalMethod ? 4 : 5 + supplied.length,
                        method.length);
                assertSame(closure, method[0]);
                assertSame(receiver, method[1]);
                assertSame(home, method[2]);
                assertSame(module, method[3]);

                if (!minimalMethod) {
                    assertInstanceOf(
                            com.guillermomolina.protos.runtime.ProtosReturnHome.class,
                            method[4]);
                }
                assertEquals(supplied.length,
                        ProtosFrameArguments.compactSuppliedArgumentCount(method));
                for (int index = 0; index < supplied.length; index++) {
                    assertSame(supplied[index],
                            ProtosFrameArguments.compactSuppliedArgumentWithinCount(
                                    method, index));
                }

                Object[] direct = ProtosFrameArguments.compactDirectClosureCall(
                        closure, module, null, supplied);
                assertEquals(supplied.length,
                        ProtosFrameArguments.compactSuppliedArgumentCount(direct));
                for (int index = 0; index < supplied.length; index++) {
                    assertSame(supplied[index],
                            ProtosFrameArguments.compactSuppliedArgumentWithinCount(
                                    direct, index));
                }
            }
        });
    }

    /** PERF038-H: an unobservable zero-argument method needs no home slot. */
    @Test
    void minimalImmediateMethodHeaderPublishesExactActivation() throws Exception {
        withCore(module -> {
            ProtosClosureValue closure = parsedClosure(
                    "() => { 1 }",
                    "perf038h-minimal-method.protos",
                    module);
            ProtosObjectValue receiver = new ProtosObjectValue(closure);
            ProtosObjectValue home = new ProtosObjectValue(closure);

            Object[] arguments = ProtosFrameArguments.compactImmediateMethodCall(
                    closure,
                    receiver,
                    home,
                    module,
                    com.guillermomolina.protos.runtime.ProtosReturnHome.unobservable(),
                    new Object[0]);

            assertEquals(4, arguments.length);
            assertSame(closure, arguments[0]);
            assertSame(receiver, arguments[1]);
            assertSame(home, arguments[2]);
            assertSame(module, arguments[3]);
            assertEquals(0,
                    ProtosFrameArguments.compactSuppliedArgumentCount(arguments));
            assertSame(module, ProtosFrameArguments.compactCaller(arguments));
            assertSame(
                    com.guillermomolina.protos.runtime.ProtosReturnHome.unobservable(),
                    ProtosFrameArguments.compactReturnHome(arguments));
            assertTrue(
                    ProtosFrameArguments.isUnmaterializedInheritingFullHeader(arguments));

            ProtosActivation activation = ProtosFrameArguments.activation(arguments);
            assertSame(activation, arguments[0]);
            assertSame(activation, ProtosFrameArguments.activation(arguments));
        });
    }

    /** The length-only argument and arity predicates equal the count-based ones for every layout. */
    @Test
    void lengthOnlyArgumentPredicatesMatchSuppliedCount() throws Exception {
        withCore(module -> {
            ProtosClosureValue closure = parsedClosure("(a, b) => { a }", "perf038f-arity.protos", module);
            List<Object[]> layouts = List.of(
                    ProtosFrameArguments.compactDirectClosureCall(closure, module, null, new Object[0]),
                    ProtosFrameArguments.compactDirectClosureCall(
                            closure, module, null, new Object[] {integer(1)}),
                    ProtosFrameArguments.compactImmediateMethodCall(
                            closure, closure, new ProtosObjectValue(closure), module,
                            new Object[] {integer(1), integer(2), integer(3)}));
            for (Object[] arguments : layouts) {
                int count = ProtosFrameArguments.compactSuppliedArgumentCount(arguments);
                for (int index = 0; index < 5; index++) {
                    assertEquals(index < count,
                            ProtosFrameArguments.compactHasSuppliedArgument(arguments, index));
                    assertEquals(count <= index,
                            ProtosFrameArguments.compactSuppliedArgumentCountAtMost(arguments, index));
                }
            }
        });
        System.out.println("PERF038F_FULL_AND_MINIMAL_HEADERS=PASS");
        System.out.println("PERF038F_ARGUMENT_ARITY=PASS");
    }

    /**
     * Guest sends from compact method roots, zero-argument Closure roots and
     * published roots, with prelude-inheriting callees, arity rejection,
     * non-local return and polymorphism at the same sites.
     */
    @Test
    void guestSendsAcrossProvenanceAndHeaderForms() throws Exception {
        withCore(module -> {
            List<Object> results =
                    elements(evaluate(
                            "leaf: { pick: (v) => { v + 1 } }\n"
                                    + "other: { pick: (v) => { v + 2 } }\n"
                                    + "middle: {\n"
                                    + "    forward: (o, v) => { o.pick(v) }\n"
                                    + "    observed: (v) => {\n"
                                    + "        here: context\n"
                                    + "        leaf.pick(v)\n"
                                    + "    }\n"
                                    + "}\n"
                                    + "zero: () => { leaf.pick(10) }\n"
                                    + "one: (a) => { a }\n"
                                    + "finder: { pick: (limit) => {\n"
                                    + "    Array(1, 5, 9).each((x) => {\n"
                                    + "        (x > limit).ifTrue(() => { ^ x })\n"
                                    + "    })\n"
                                    + "    0\n"
                                    + "} }\n"
                                    + "[middle.forward(leaf, 1), middle.forward(leaf, 2), zero(), zero(),"
                                    + " middle.observed(3), middle.forward(other, 1),"
                                    + " middle.forward(finder, 3), middle.forward(finder, 10),"
                                    + " Error.handle(() => { one(1, 2) }, (caught) => { 77 }),"
                                    + " middle.forward(leaf, 4)]\n",
                            "perf038f-guest.protos", module));
            assertIntegers(results, 2, 3, 11, 11, 4, 3, 5, 0, 77, 5);
        });
        System.out.println("PERF038F_GUEST_SENDS=PASS");
        System.out.println("PERF038F_RETURN_HOME=PASS");
        System.out.println("PERF038F_ARITY_ERROR=PASS");
    }

    /**
     * A captured read from a stable owner, then from a different owner of
     * the same site, then after a nearer binding is created, stays exact.
     */
    @Test
    void capturedOwnerStableChangingAndShadowed() throws Exception {
        withCore(module -> {
            List<Object> results =
                    elements(evaluate(
                            "make: (seed) => {\n"
                                    + "    target: { identity: (v) => { v + seed } }\n"
                                    + "    { forward: (v) => { target.identity(v) } }\n"
                                    + "}\n"
                                    + "x: make(100)\n"
                                    + "y: make(200)\n"
                                    + "[x.forward(1), x.forward(2), y.forward(1), x.forward(3)]\n",
                            "perf038f-owner.protos", module));
            assertIntegers(results, 101, 102, 201, 103);
        });
        System.out.println("PERF038F_CAPTURED_OWNER=PASS");
    }

    /**
     * One captured owner, warm at its read site, has its binding removed
     * (ABSENT), recreated as PRESENT(null) and rebound; the cache keeps the
     * owner and every state is read exactly, including after re-warming.
     */
    @Test
    void clearedOwnerThenRebindingStaysExact() throws Exception {
        withCore(module -> {
            ProtosClosureValue reader = parsedClosure(
                    "makeSlot: (initial) => {\n"
                            + "    slot: initial\n"
                            + "    () => { slot }\n"
                            + "}\n"
                            + "reader: makeSlot(1)\n"
                            + "reader\n",
                    "perf038f-cleared.protos", module);
            for (int call = 0; call < WARM_UP_CALLS; call++) {
                assertEquals("1", integerValue(callReader(module, call)));
            }
            ProtosObjectValue owner = reader.capturedLexicalEnvironmentForRuntime().context();
            owner.removeLocalSlot("slot");
            ProtosSignalException absent =
                    assertThrows(ProtosSignalException.class, () -> callReader(module, -1));
            assertSame(
                    ProtosCoreErrors.prototype(module, ProtosCoreErrors.StandardError.SLOT_NOT_FOUND),
                    absent.error().parent().orElse(null));
            owner.createLocalSlot("slot", ProtosNullValue.INSTANCE);
            for (int call = 0; call < WARM_UP_CALLS; call++) {
                assertSame(ProtosNullValue.INSTANCE, callReader(module, call));
            }
            owner.removeLocalSlot("slot");
            owner.createLocalSlot("slot", integer(5));
            for (int call = 0; call < WARM_UP_CALLS; call++) {
                assertEquals("5", integerValue(callReader(module, call)));
            }
        });
        System.out.println("PERF038F_OWNER_CLEARED_REBINDING=PASS");
    }

    /**
     * The class-profiled continuation decision answers exactly the generic
     * type tests: every suspension carrier is a continuation, ordinary
     * results are not.
     */
    @Test
    void continuationClassDecisionMatchesTypeTests() {
        assertTrue(ProtosSemanticBytecodeRootNode.IsContinuation.isContinuationClass(
                ContinuationResult.class));
        assertTrue(ProtosSemanticBytecodeRootNode.IsContinuation.isContinuationClass(
                ProtosNativeSuspension.class));
        assertTrue(ProtosSemanticBytecodeRootNode.IsContinuation.isContinuationClass(
                ProtosIoOperationSuspension.class));
        assertTrue(ProtosSemanticBytecodeRootNode.IsContinuation.isContinuationClass(
                ProtosIoReleaseSuspension.class));
        for (Object ordinary : List.of(integer(1), ProtosNullValue.INSTANCE, new Object())) {
            assertFalse(ProtosSemanticBytecodeRootNode.IsContinuation.isContinuationClass(
                    ordinary.getClass()));
            assertEquals(ProtosBytecodeRootNode.IsContinuation.perform(ordinary),
                    ProtosSemanticBytecodeRootNode.IsContinuation.isContinuationClass(
                            ordinary.getClass()));
        }
        System.out.println("PERF038F_CONTINUATION_CLASS=PASS");
    }

    private static Object callReader(ProtosActivation module, int call) {
        return evaluate("reader()\n", "perf038f-reader-" + call + ".protos", module);
    }

    private static ProtosActivation inherited(Object[] arguments) {
        return ProtosSemanticBytecodeRootNode.PrepareSendArguments
                .inheritedProvenanceCallerOrNull(arguments);
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
