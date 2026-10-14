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
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.guillermomolina.protos.parser.ProtosParser;
import com.guillermomolina.protos.runtime.ProtosActivation;
import com.guillermomolina.protos.runtime.ProtosClosureValue;
import com.guillermomolina.protos.runtime.ProtosNullValue;
import com.guillermomolina.protos.runtime.ProtosObjectValue;
import com.guillermomolina.protos.semantic.Canonicalizer;
import com.guillermomolina.protos.semantic.ast.CanonicalSequence;
import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.TruffleLanguage.LanguageReference;
import com.oracle.truffle.api.bytecode.BytecodeNode;
import com.oracle.truffle.api.bytecode.Instruction;
import com.oracle.truffle.api.bytecode.TagTree;
import com.oracle.truffle.api.instrumentation.StandardTags;
import com.oracle.truffle.api.nodes.RootNode;
import com.oracle.truffle.api.source.Source;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;

/**
 * I096-A (PLAT057-B) focal evidence: an ordinary C0/C1 call whose receiver is
 * an immediate Closure literal runs that literal's body inline in the caller's
 * Bytecode root in NORMAL mode, after ordinary {@code call} selection proved
 * the canonical standard selection of the literal's own body; every
 * non-admitted shape, a non-canonical {@code call} selection, and DEBUG mode
 * keep the physical callee root. No test mutates the frozen global root.
 *
 * <p>I101-A: each program is lowered under the immutable profile of the
 * entered Context. A NORMAL root holds only the inline region (its run-time
 * admission and the carrier's physical fallback), with no execution-mode
 * branch and no retained ordinary call; a DEBUG root holds only the ordinary
 * call.
 *
 * <p>Inline execution is observed through the guest CallTarget stack seen by a
 * native {@code probe}: an admitted body leaves only {@code run} over the
 * module root (depth 2); a physical callee adds its own root.
 *
 * <p>I096-B1 extends the evidence to ordinary C0/C1 calls through a lexical
 * binding whose candidate body is known statically: the candidate runs inline
 * only when the run-time selection proves it, with the effective instance's
 * own captures; every other value takes the prepared physical fallback.
 *
 * <p>The I096 coverage completion extends both forms to every exact
 * positional arity up to the expansion budget, and binding calls to operand
 * positions of the same activation (compound expressions, arguments of other
 * calls), with the same run-time admission and fallback.
 *
 * <p>The nested-Closure extension admits bodies that create Closures: they
 * run inline over the callee's own activation (never frame-native storage),
 * nested Closures keep their own CallTargets and capture that activation by
 * reference, including after escaping, and the group gains no duplicate root.
 */
final class ProtosI096InlineClosureLiteralCallTest {
    private static final Path CORE = Path.of("protos", "lib", "core");

    private static final LanguageReference<ProtosLanguage> LANGUAGE_REF =
            LanguageReference.create(ProtosLanguage.class);

    private static final int INLINE_DEPTH = 2;

    @Test
    void zeroArgumentLiteralWithOwnBindingsRunsInlineInCaller() throws Exception {
        String source = """
                run: () => {
                    (() => {
                        a: 20
                        b: 22
                        probe()
                        a + b
                    })()
                }
                run()
                """;
        Execution normal = execute(false, source);
        assertInteger(42, normal.value());
        assertEquals(List.of(INLINE_DEPTH), normal.probe().depths(), normal.probe()::toString);

        ProtosSemanticBytecodeRootNode runRoot = normal.innermostRoot();
        runRoot.getRootNodes().ensureComplete();
        List<String> names = instructionNames(runRoot.getBytecodeNode());
        int prepare = position(names, "PrepareInlineClosureLiteralCall");
        int admit = position(names, "AdmitsInlineClosureLiteralCall");
        assertTrue(prepare >= 0 && admit > prepare, names::toString);
        // The admission miss keeps the carrier's ordinary physical fallback;
        // I101-A: no ordinary direct-call alternative remains in NORMAL.
        assertTrue(position(names, "LoadInlineLiteralFallbackCall") > admit, names::toString);
        assertTrue(position(names, "TryDirectCallZero") < 0, names::toString);
        /*
         * Physical frames and semantic RootTags are separate evidence. The
         * stack proves no callee CallTarget frame exists (only run over the
         * module root); the tag tree proves the callee keeps its own semantic
         * RootTag region inside run. Neither alone proves debugging
         * equivalence: DEBUG keeps the physical callee (see
         * debugModeKeepsThePhysicalCalleeRoot) and NORMAL/DEBUG results are
         * compared by the parity checks.
         */
        List<RootNode> stack = normal.probe().stacks.get(0);
        assertSame(runRoot, stack.get(0));
        assertTrue(
                stack.stream().noneMatch(frame -> frame != runRoot
                        && frame != stack.get(stack.size() - 1)),
                "no physical callee frame");
        assertEquals(
                2,
                rootTags(runRoot.getBytecodeNode()),
                "automatic RootTag of run plus the inline callee semantic RootTag");
        System.out.println("I096_A_C0_INLINE=YES");
    }

    @Test
    void oneArgumentLiteralBindsItsOwnScopeIndependentlyOfTheCaller() throws Exception {
        String source = """
                run: () => {
                    value: 5
                    local: 1
                    r: ((value) => {
                        local: value * 10
                        probe()
                        local + 1
                    })(4)
                    r * 100 + local * 10 + value
                }
                run()
                """;
        Execution normal = execute(false, source);
        assertInteger(4115, normal.value());
        assertEquals(List.of(INLINE_DEPTH), normal.probe().depths(), normal.probe()::toString);
        // I101-A: the NORMAL root keeps no ordinary C1 direct-call alternative.
        assertTrue(
                position(instructionNames(normal.innermostRoot()), "TryDirectCallOne") < 0,
                "NORMAL keeps no C1 direct-call alternative");
        assertParity(source, 4115);
        System.out.println("I096_A_C1_OWN_SCOPE=YES");
    }

    @Test
    void capturedOuterBindingIsReadAndWrittenByReference() throws Exception {
        String source = """
                run: () => {
                    total: 1
                    step: 41
                    ((amount) => {
                        total = total + amount
                        probe()
                    })(step)
                    total
                }
                run()
                """;
        Execution normal = execute(false, source);
        assertInteger(42, normal.value());
        assertEquals(List.of(INLINE_DEPTH), normal.probe().depths(), normal.probe()::toString);
        assertParity(source, 42);
        System.out.println("I096_A_CAPTURE_BY_REFERENCE=YES");
    }

    /** D179: the inline own binding keeps exact presence across remove/recreate. */
    @Test
    void ownBindingRemovalAndRecreationKeepExactPresence() throws Exception {
        String source = """
                run: () => {
                    x: 1
                    inner: ((seed) => {
                        x: seed
                        before: x
                        context.removeSlot("x")
                        removed: x
                        context.x: 3
                        probe()
                        before * 100 + removed * 10 + x
                    })(2)
                    inner * 10 + x
                }
                run()
                """;
        Execution normal = execute(false, source);
        assertInteger(2131, normal.value());
        assertEquals(List.of(INLINE_DEPTH), normal.probe().depths(), normal.probe()::toString);
        assertParity(source, 2131);
        System.out.println("I096_A_D179_REMOVE_RECREATE=YES");
    }

    /**
     * The destination selected before the right-hand side is the callee's own
     * binding; removing it during the right-hand side never retargets the
     * write to the caller's same-named binding.
     */
    @Test
    void assignmentDestinationIsNotReresolvedAfterRightHandSideRemoval() throws Exception {
        String source = """
                run: () => {
                    x: 1
                    remover: (scope) => {
                        scope.removeSlot("x")
                        12
                    }
                    Error.handle(() => {
                        ((seed) => {
                            x: seed
                            x = remover(context)
                            probe()
                        })(2)
                    }, (caught) => { null })
                    x
                }
                run()
                """;
        assertInteger(1, execute(false, source).value());
        assertParity(source, 1);
        System.out.println("I096_A_ASSIGNMENT_TARGET_RETAINED=YES");
    }

    /**
     * Each invocation materializes the callee's own fresh Context, never the
     * caller's, and two invocations of the same site never share it.
     */
    @Test
    void observedContextIsTheCalleesOwnFreshActivation() throws Exception {
        String source = """
                run: () => {
                    c: (() => {
                        marker: 7
                        probe()
                        context
                    })()
                    (c === context).ifTrueIfFalse(() => { null }, () => { c })
                }
                a: run()
                b: run()
                (a === b).ifTrueIfFalse(() => { -1 }, () => { a.marker + b.marker })
                """;
        Execution normal = execute(false, source);
        assertInteger(14, normal.value());
        assertEquals(List.of(INLINE_DEPTH), normal.probe().depths(), normal.probe()::toString);
        assertParity(source, 14);
        System.out.println("I096_A_FRESH_CONTEXT_IDENTITY=YES");
    }

    /** Re-entrant invocations of the same inline site keep isolated bindings. */
    @Test
    void reentrantInvocationsKeepIsolatedOwnBindings() throws Exception {
        String source = """
                step: (k) => {
                    (k > 0).ifTrueIfFalse(() => { run(k - 1) }, () => { 0 })
                }
                run: (n) => {
                    ((k) => {
                        local: k
                        local + step(k) + local
                    })(n)
                }
                run(3)
                """;
        assertInteger(12, execute(false, source).value());
        assertParity(source, 12);
        System.out.println("I096_A_REENTRANT_ISOLATION=YES");
    }

    @Test
    void errorAndNonLocalReturnKeepExactControl() throws Exception {
        String nonLocalReturn = """
                run: () => {
                    ((value) => {
                        probe()
                        ^value + 1
                    })(41)
                    0
                }
                run()
                """;
        Execution returned = execute(false, nonLocalReturn);
        assertInteger(42, returned.value());
        assertEquals(1, returned.probe().calls.get());
        assertParity(nonLocalReturn, 42);

        String error = """
                run: () => {
                    (() => {
                        probe()
                        Error().signal()
                        1
                    })()
                    2
                }
                Error.handle(() => { run() }, (caught) => { 42 })
                """;
        Execution handled = execute(false, error);
        assertInteger(42, handled.value());
        assertEquals(1, handled.probe().calls.get());
        assertParity(error, 42);
        System.out.println("I096_A_NLR_PRESERVED=YES");
        System.out.println("I096_A_ERROR_PROPAGATION_PRESERVED=YES");
    }

    @Test
    void suspensionInsideTheInlineBodyResumesWithoutReplay() throws Exception {
        String source = """
                run: () => {
                    step: (() => { 1 }).future()
                    visits: 0
                    r: ((value) => {
                        visits = visits + 1
                        value + step.value()
                    })(41)
                    r * 10 + visits
                }
                run()
                """;
        ProtosExecutionOutcome outcome =
                ProtosTestExecutionSupport.execute(
                        "i096-a-suspension.protos",
                        source,
                        new ProtosCoreBootstrap().bootstrap(CORE).newModuleActivation());
        assertEquals(
                ProtosExecutionOutcome.State.COMPLETED,
                outcome.state(),
                () -> "outcome=" + outcome.state() + ", error=" + outcome.error());
        assertInteger(421, outcome.value());
        System.out.println("I096_A_SUSPENSION_RESUMPTION_PRESERVED=YES");
    }

    /**
     * The canonical selection the inline region relies on is immutable: the
     * published root is frozen and its {@code call} is the canonical standard
     * selection. Nothing here mutates global state.
     */
    @Test
    void canonicalRootCallSelectionIsPublishedImmutable() throws Exception {
        try (Context context = Context.newBuilder(ProtosLanguage.ID).build()) {
            context.initialize(ProtosLanguage.ID);
            context.enter();
            try {
                new Probe().module();
                ProtosObjectValue root = ProtosObjectValue.rootObject();
                assertTrue(root.isFrozen(), "the standard root is published frozen");
                assertTrue(
                        ProtosStandardObjectProtocol.isCanonicalStandardCallSelection(
                                root.readLocalSlot("call").orElseThrow(), root));
            } finally {
                context.leave();
            }
        }
        System.out.println("I096_A_CANONICAL_CALL_IMMUTABLE=YES");
    }

    /**
     * Carrier admission on legitimately constructed instances: a Closure with
     * the canonical selection is admitted for its own plan; a mutable Closure
     * instance whose own {@code call} slot overrides the selection, and a
     * canonical instance checked against another definition's plan, are not,
     * and both keep the ordinary prepared fallback.
     */
    @Test
    void carrierAdmitsOnlyCanonicalSelectionOfTheLiteralsOwnPlan() throws Exception {
        try (Context context = Context.newBuilder(ProtosLanguage.ID).build()) {
            context.initialize(ProtosLanguage.ID);
            context.enter();
            try {
                ProtosActivation module = new Probe().module();
                // Created inside a maker Closure, as at a real inline site: a
                // module-level literal owns a fresh ReturnHome and is never
                // admitted. The instance override follows PERF042. All
                // fixtures share one module, so each creates distinct names.
                ProtosClosureValue canonical =
                        closure("makeCanonical: () => { (v) => { v + 1 } }\nmakeCanonical()", module);
                ProtosClosureValue other =
                        closure("makeOther: () => { (v) => { v + 2 } }\nmakeOther()", module);
                ProtosClosureValue overridden = closure("""
                        makeOverridden: () => { (v) => { v + 1 } }
                        f: makeOverridden()
                        f.call: (v) => { 100 + v }
                        f
                        """, module);

                var admitted = ProtosBytecodeRootNode.prepareInlineClosureLiteralCall(
                        canonical, module, new Object[] {integerArgument(module)});
                assertTrue(admitted.admits(planCell(canonical)),
                        "canonical selection of the literal's own plan is admitted");
                assertFalse(admitted.admits(planCell(other)),
                        "definition identity of another literal never admits");

                var rejected = ProtosBytecodeRootNode.prepareInlineClosureLiteralCall(
                        overridden, module, new Object[] {integerArgument(module)});
                assertFalse(rejected.admits(planCell(overridden)),
                        "an instance-level call override is never inlined");
                assertTrue(rejected.fallbackCall() != null);
            } finally {
                context.leave();
            }
        }
        System.out.println("I096_A_INSTANCE_OVERRIDE_FALLBACK=YES");
    }

    @Test
    void debugModeKeepsThePhysicalCalleeRoot() throws Exception {
        Execution debug = execute(true, """
                run: () => {
                    ((value) => {
                        local: value
                        probe()
                        local + 1
                    })(41)
                }
                run()
                """);
        assertInteger(42, debug.value());
        assertEquals(1, debug.probe().calls.get());
        assertTrue(
                debug.probe().depths().get(0) > INLINE_DEPTH,
                debug.probe()::toString);
        ProtosSemanticBytecodeRootNode callee = debug.innermostRoot();
        callee.getRootNodes().ensureComplete();
        assertEquals(1, rootTags(callee.getBytecodeNode()), "the callee's own RootTag");
        // I101-A: the DEBUG caller holds only the ordinary C1 call, no inline region.
        List<String> runNames = instructionNames(debug.outermostGuestRoot());
        assertTrue(position(runNames, "PrepareInlineClosureLiteralCall") < 0, runNames::toString);
        assertTrue(position(runNames, "TryDirectCallOne") >= 0, runNames::toString);
        System.out.println("I096_A_DEBUG_TOPOLOGY_PRESERVED=YES");
    }

    @Test
    void nonAdmittedShapesKeepTheUnchangedCallTopology() throws Exception {
        List<String> sources = List.of(
                // A rest parameter keeps ordinary binding (a nested Closure
                // body is admitted since the I096 nested-Closure extension).
                """
                run: () => {
                    probe()
                    ((...items) => { 5 })(1)
                }
                run()
                """,
                // Default and rest parameters keep ordinary binding.
                """
                run: () => {
                    probe()
                    ((a = 2) => { a + 3 })()
                }
                run()
                """);
        for (String source : sources) {
            Execution normal = execute(false, source);
            assertInteger(5, normal.value());
            ProtosSemanticBytecodeRootNode runRoot = normal.outermostGuestRoot();
            List<String> names = instructionNames(runRoot);
            assertFalse(
                    names.stream().anyMatch(name -> name.contains("InlineClosureLiteralCall")),
                    () -> source + names);
        }
        System.out.println("I096_A_NON_ADMITTED_TOPOLOGY_UNCHANGED=YES");
    }

    // ---- I096-B1: calls through a binding with a candidate body -------------

    @Test
    void bindingZeroArgumentCallRunsCandidateInlineBehindTheGuard() throws Exception {
        String source = """
                run: () => {
                    f: () => {
                        a: 20
                        b: 22
                        probe()
                        a + b
                    }
                    f()
                }
                run()
                """;
        Execution normal = execute(false, source);
        assertInteger(42, normal.value());
        assertEquals(List.of(INLINE_DEPTH), normal.probe().depths(), normal.probe()::toString);

        ProtosSemanticBytecodeRootNode runRoot = normal.innermostRoot();
        runRoot.getRootNodes().ensureComplete();
        List<String> names = instructionNames(runRoot.getBytecodeNode());
        int prepare = position(names, "PrepareInlineClosureLiteralCall");
        int admit = position(names, "AdmitsInlineClosureLiteralCall");
        assertTrue(prepare >= 0 && admit > prepare, names::toString);
        assertTrue(position(names, "LoadInlineLiteralFallbackCall") > admit, names::toString);
        assertTrue(position(names, "TryDirectCallZero") < 0, names::toString);
        assertEquals(1, count(names, "PrepareInlineClosureLiteralCall"), "one candidate per site");
        List<RootNode> stack = normal.probe().stacks.get(0);
        assertSame(runRoot, stack.get(0));
        assertEquals(
                2,
                rootTags(runRoot.getBytecodeNode()),
                "automatic RootTag of run plus the inline callee semantic RootTag");
        assertParity(source, 42);
        System.out.println("I096_B1_C0_BINDING_INLINE=YES");
    }

    /**
     * I096-B1: structural proof before Graal partial evaluation.
     * The caller's own bytecode contains the admitted C1 body,
     * argument binding, guards and physical fallback.
     */
    @Test
    void directC1IdentityIsExpandedByLowererBeforeGraal() throws Exception {
        String source = """
                run: () => {
                    identity: (x) => x
                    identity(42)
                }
                run()
                """;

        try (Context context = Context.newBuilder(ProtosLanguage.ID).build()) {
            context.initialize(ProtosLanguage.ID);
            context.enter();
            try {
                ProtosSemanticBytecodeRootNode module = lowerRoot(source);
                module.getRootNodes().ensureComplete();

                List<ProtosSemanticBytecodeRootNode> callers =
                        groupRoots(module).stream()
                                .filter(root ->
                                        count(instructionNames(root),
                                                "PrepareInlineClosureLiteralCall") > 0)
                                .toList();

                assertEquals(1, callers.size(),
                        "exactly one Root contains the I096 C1 inline site");

                ProtosSemanticBytecodeRootNode caller = callers.get(0);
                List<String> names = instructionNames(caller);

                int prepare = position(names, "PrepareInlineClosureLiteralCall");
                int admit = position(names, "AdmitsInlineClosureLiteralCall");
                int argument = position(names, "LoadInlineClosureArgument");
                int fallback = position(names, "LoadInlineLiteralFallbackCall");
                int direct = position(names, "TryDirectCallOne");

                assertEquals(1, count(names, "PrepareInlineClosureLiteralCall"));
                assertTrue(prepare >= 0, names::toString);
                assertTrue(admit > prepare, names::toString);
                assertTrue(argument > admit,
                        "the C1 parameter is bound inside the caller");
                assertTrue(fallback > admit,
                        "the rejected candidate retains its physical fallback");
                assertTrue(direct < 0,
                        "I101-A: NORMAL retains no ordinary C1 invocation branch");

                assertEquals(2, rootTags(caller.getBytecodeNode()),
                        "caller RootTag plus inline identity body RootTag");

                assertInteger(42,
                        module.getCallTarget().call(new Probe().module()));
            } finally {
                context.leave();
            }
        }

        System.out.println("I096_B1_DIRECT_C1_LOWERED=YES");
    }

    @Test
    void bindingOneArgumentCallKeepsCalleeOwnSameNamedBinding() throws Exception {
        String source = """
                run: () => {
                    y: 1000
                    f: (x) => {
                        y: x + 1
                        probe()
                        y
                    }
                    r: f(7)
                    r + y
                }
                run()
                """;
        Execution normal = execute(false, source);
        assertInteger(1008, normal.value());
        assertEquals(List.of(INLINE_DEPTH), normal.probe().depths(), normal.probe()::toString);
        assertParity(source, 1008);
        System.out.println("I096_B1_C1_OWN_SCOPE=YES");
    }

    @Test
    void reassignmentToAnotherDefinitionTakesThePhysicalFallback() throws Exception {
        String source = """
                run: () => {
                    f: (x) => {
                        probe()
                        x + 1
                    }
                    a: f(1)
                    f = (x) => {
                        probe()
                        x * 100
                    }
                    b: f(2)
                    a * 1000 + b
                }
                run()
                """;
        Execution normal = execute(false, source);
        assertInteger(2200, normal.value());
        assertEquals(INLINE_DEPTH, normal.probe().stacks.get(0).size());
        assertTrue(normal.probe().stacks.get(1).size() > INLINE_DEPTH, normal.probe()::toString);
        assertParity(source, 2200);
        System.out.println("I096_B1_OTHER_DEFINITION_FALLBACK=YES");
    }

    /**
     * Another instance of the same definition, created by an earlier
     * activation, is admitted and reads its own captures, never the caller's.
     */
    @Test
    void foreignInstanceOfTheSameDefinitionUsesItsOwnCaptures() throws Exception {
        String source = """
                saved: null
                keep: (g) => {
                    (saved === null).ifTrueIfFalse(() => { saved = g }, () => { null })
                    saved
                }
                run: (n) => {
                    f: (x) => {
                        probe()
                        x + n
                    }
                    f = keep(f)
                    f(1)
                }
                a: run(10)
                b: run(20)
                a * 100 + b
                """;
        Execution normal = execute(false, source);
        assertInteger(1111, normal.value());
        assertEquals(List.of(INLINE_DEPTH), normal.probe().depths(), normal.probe()::toString);
        assertParity(source, 1111);
        System.out.println("I096_B1_FOREIGN_INSTANCE_OWN_CAPTURES=YES");
    }

    /** The receiver is read once, before arguments that reassign the binding. */
    @Test
    void reassignmentDuringArgumentEvaluationKeepsTheReadReceiver() throws Exception {
        String source = """
                run: () => {
                    f: (x) => {
                        probe()
                        x + 1
                    }
                    swap: () => {
                        f = (x) => { x * 100 }
                        5
                    }
                    r: f(swap())
                    r * 1000 + f(5)
                }
                run()
                """;
        Execution normal = execute(false, source);
        assertInteger(6500, normal.value());
        assertEquals(List.of(INLINE_DEPTH), normal.probe().depths(), normal.probe()::toString);
        assertParity(source, 6500);
        System.out.println("I096_B1_RECEIVER_READ_ONCE=YES");
    }

    @Test
    void mutationFromAnotherClosureNeverRunsTheCandidate() throws Exception {
        String source = """
                run: () => {
                    f: (x) => { x + 1 }
                    mutate: () => {
                        f = (x) => {
                            probe()
                            x * 100
                        }
                    }
                    mutate()
                    f(2)
                }
                run()
                """;
        Execution normal = execute(false, source);
        assertInteger(200, normal.value());
        assertTrue(normal.probe().stacks.get(0).size() > INLINE_DEPTH, normal.probe()::toString);
        assertParity(source, 200);
        System.out.println("I096_B1_EXTERNAL_MUTATION_FALLBACK=YES");
    }

    /** D179: removal and recreation of the binding are observed at run time. */
    @Test
    void bindingRemovalAndRecreationAreObserved() throws Exception {
        String source = """
                run: () => {
                    f: (x) => {
                        probe()
                        x + 1
                    }
                    g: f
                    a: f(1)
                    context.removeSlot("f")
                    context.f: (x) => { x * 100 }
                    b: f(2)
                    context.removeSlot("f")
                    context.f: g
                    c: f(3)
                    a * 10000 + b * 10 + c
                }
                run()
                """;
        Execution normal = execute(false, source);
        assertInteger(22004, normal.value());
        assertEquals(INLINE_DEPTH, normal.probe().stacks.get(0).size());
        assertParity(source, 22004);
        System.out.println("I096_B1_D179_REMOVE_RECREATE=YES");
    }

    /** A nearer binding shadows the outer one only from its creation on. */
    @Test
    void nearerBindingShadowsTheOuterOneOnlyAfterItsCreation() throws Exception {
        String source = """
                f: (x) => { x + 1 }
                run: () => {
                    early: f(1)
                    f: (x) => {
                        probe()
                        x * 10
                    }
                    late: f(2)
                    early * 100 + late
                }
                run()
                """;
        Execution normal = execute(false, source);
        assertInteger(220, normal.value());
        assertEquals(List.of(INLINE_DEPTH), normal.probe().depths(), normal.probe()::toString);
        assertParity(source, 220);
        System.out.println("I096_B1_SHADOWING=YES");
    }

    @Test
    void bindingCalleeReadsAndWritesCapturesByReference() throws Exception {
        String source = """
                run: () => {
                    total: 1
                    f: (amount) => {
                        total = total + amount
                        probe()
                        total
                    }
                    r: f(41)
                    r * 100 + total
                }
                run()
                """;
        Execution normal = execute(false, source);
        assertInteger(4242, normal.value());
        assertEquals(List.of(INLINE_DEPTH), normal.probe().depths(), normal.probe()::toString);
        assertParity(source, 4242);
        System.out.println("I096_B1_CAPTURE_BY_REFERENCE=YES");
    }

    /** An instance-level call override, and its removal, on a local Closure. */
    @Test
    void instanceCallOverrideAndItsRemovalAreSelectedAtRunTime() throws Exception {
        String source = """
                run: () => {
                    f: (v) => { v + 1 }
                    f.call: (v) => {
                        probe()
                        100 + v
                    }
                    a: f(1)
                    f.removeSlot("call")
                    b: f(1)
                    a * 10 + b
                }
                run()
                """;
        Execution normal = execute(false, source);
        assertInteger(1012, normal.value());
        assertTrue(normal.probe().stacks.get(0).size() > INLINE_DEPTH, normal.probe()::toString);
        assertParity(source, 1012);
        System.out.println("I096_B1_CALL_OVERRIDE_FALLBACK=YES");
    }

    @Test
    void bindingCalleeOwningItsReturnHomeKeepsNonLocalReturn() throws Exception {
        String source = """
                run: () => {
                    f: (value) => {
                        probe()
                        ^value + 1
                    }
                    f(41)
                    0
                }
                run()
                """;
        Execution normal = execute(false, source);
        assertInteger(42, normal.value());
        assertEquals(1, normal.probe().calls.get());
        assertParity(source, 42);
        System.out.println("I096_B1_RETURN_HOME_PRESERVED=YES");
    }

    @Test
    void bindingCalleeObservesItsOwnFreshContext() throws Exception {
        String source = """
                run: () => {
                    f: () => {
                        marker: 7
                        probe()
                        context
                    }
                    c: f()
                    (c === context).ifTrueIfFalse(() => { null }, () => { c })
                }
                a: run()
                b: run()
                (a === b).ifTrueIfFalse(() => { -1 }, () => { a.marker + b.marker })
                """;
        Execution normal = execute(false, source);
        assertInteger(14, normal.value());
        assertEquals(List.of(INLINE_DEPTH), normal.probe().depths(), normal.probe()::toString);
        assertParity(source, 14);
        System.out.println("I096_B1_FRESH_CONTEXT_IDENTITY=YES");
    }

    @Test
    void reentrantBindingCallsKeepIsolatedActivations() throws Exception {
        String source = """
                step: (k) => {
                    (k > 0).ifTrueIfFalse(() => { run(k - 1) }, () => { 0 })
                }
                run: (n) => {
                    f: (k) => {
                        local: k
                        local + step(k) + local
                    }
                    f(n)
                }
                run(3)
                """;
        assertInteger(12, execute(false, source).value());
        assertParity(source, 12);
        System.out.println("I096_B1_REENTRANT_ISOLATION=YES");
    }

    @Test
    void bindingCallDebugModeKeepsThePhysicalCalleeRoot() throws Exception {
        Execution debug = execute(true, """
                run: () => {
                    f: (value) => {
                        local: value
                        probe()
                        local + 1
                    }
                    f(41)
                }
                run()
                """);
        assertInteger(42, debug.value());
        assertTrue(debug.probe().depths().get(0) > INLINE_DEPTH, debug.probe()::toString);
        ProtosSemanticBytecodeRootNode callee = debug.innermostRoot();
        callee.getRootNodes().ensureComplete();
        assertEquals(1, rootTags(callee.getBytecodeNode()), "the callee's own RootTag");
        System.out.println("I096_B1_DEBUG_TOPOLOGY_PRESERVED=YES");
    }

    /**
     * A Bytecode reparse that materializes source and tag information replays
     * the same roots in the same group order and the same candidate regions.
     */
    @Test
    void reparseKeepsRootOrderAndCandidateRegions() throws Exception {
        String source = """
                run: () => {
                    f: (x) => {
                        y: x + 1
                        y
                    }
                    f(7)
                }
                run()
                """;
        try (Context context = Context.newBuilder(ProtosLanguage.ID).build()) {
            context.initialize(ProtosLanguage.ID);
            context.enter();
            try {
                ProtosSemanticBytecodeRootNode module = lowerRoot(source);
                List<ProtosSemanticBytecodeRootNode> before =
                        groupRoots(module);
                List<Integer> regionsBefore = candidateRegions(before);
                assertTrue(regionsBefore.stream().mapToInt(Integer::intValue).sum() >= 1,
                        regionsBefore::toString);

                module.getRootNodes().ensureComplete();
                List<ProtosSemanticBytecodeRootNode> after =
                        groupRoots(module);
                assertEquals(before.size(), after.size());
                for (int i = 0; i < before.size(); i++) {
                    assertSame(before.get(i), after.get(i), "root order at " + i);
                    assertTrue(after.get(i).getBytecodeNode().getSourceSection() != null,
                            "source materialized at " + i);
                }
                assertEquals(regionsBefore, candidateRegions(after));
                assertInteger(8, module.getCallTarget().call(new Probe().module()));
            } finally {
                context.leave();
            }
        }
        System.out.println("I096_B1_REPARSE_STABLE=YES");
    }

    @Test
    void excludedBindingShapesKeepTheUnchangedCallTopology() throws Exception {
        List<String> sources = List.of(
                // Default parameter.
                """
                run: () => {
                    f: (a = 2) => { a + 3 }
                    probe()
                    f()
                }
                run()
                """,
                // Rest parameter.
                """
                run: () => {
                    f: (...items) => { 5 }
                    probe()
                    f(1)
                }
                run()
                """,
                // Spread argument.
                """
                run: () => {
                    f: (v) => { v }
                    xs: [5]
                    probe()
                    f(...xs)
                }
                run()
                """,
                // A body creating a nested Closure is admitted, but a default
                // parameter still keeps ordinary binding.
                """
                run: () => {
                    f: (v, w = 0) => {
                        g: () => v + w
                        g()
                    }
                    probe()
                    f(5)
                }
                run()
                """,
                // A binding created twice keeps no candidate.
                """
                run: () => {
                    f: (v) => { v + 1 }
                    context.removeSlot("f")
                    f: (v) => { v }
                    probe()
                    f(5)
                }
                run()
                """,
                // A call that is not a direct expression of the sequence.
                """
                run: () => {
                    f: (v) => { v }
                    probe()
                    (true).ifTrueIfFalse(() => { f(5) }, () => { 0 })
                }
                run()
                """);
        for (String source : sources) {
            Execution normal = execute(false, source);
            assertInteger(5, normal.value());
            List<String> names = instructionNames(normal.outermostGuestRoot());
            assertFalse(
                    names.stream().anyMatch(name -> name.contains("InlineClosureLiteralCall")),
                    () -> source + names);
            assertParity(source, 5);
        }
        System.out.println("I096_B1_EXCLUDED_SHAPES_UNCHANGED=YES");
    }

    // ---- I096 coverage: C2/C3/N, operand positions, budget -----------------

    @Test
    void twoArgumentLiteralAndBindingCallsRunInlineInCaller() throws Exception {
        String literal = """
                run: () => {
                    ((a, b) => {
                        probe()
                        a * 100 + b
                    })(4, 2)
                }
                run()
                """;
        Execution normal = execute(false, literal);
        assertInteger(402, normal.value());
        assertEquals(List.of(INLINE_DEPTH), normal.probe().depths(), normal.probe()::toString);
        assertInlineSiteWithParameters(normal.innermostRoot(), 2);
        assertParity(literal, 402);

        String binding = """
                run: () => {
                    f: (a, b) => {
                        probe()
                        a * 100 + b
                    }
                    f(4, 2)
                }
                run()
                """;
        Execution bound = execute(false, binding);
        assertInteger(402, bound.value());
        assertEquals(List.of(INLINE_DEPTH), bound.probe().depths(), bound.probe()::toString);
        assertInlineSiteWithParameters(bound.innermostRoot(), 2);
        assertParity(binding, 402);
        System.out.println("I096_C2_INLINE=YES");
    }

    /**
     * C3 parameters and an own local named like caller bindings live in the
     * callee's own scope; the caller's same-named bindings are untouched.
     */
    @Test
    void threeArgumentBindingCallKeepsItsOwnScope() throws Exception {
        String source = """
                run: () => {
                    a: 1
                    b: 2
                    sum: 3
                    f: (a, b, c) => {
                        sum: a * 100 + b * 10 + c
                        probe()
                        sum
                    }
                    r: f(4, 5, 6)
                    r * 1000 + a * 100 + b * 10 + sum
                }
                run()
                """;
        Execution normal = execute(false, source);
        assertInteger(456123, normal.value());
        assertEquals(List.of(INLINE_DEPTH), normal.probe().depths(), normal.probe()::toString);
        assertInlineSiteWithParameters(normal.innermostRoot(), 3);
        assertParity(source, 456123);
        System.out.println("I096_C3_OWN_SCOPE=YES");
    }

    /** Arguments are evaluated once each, left to right, before admission. */
    @Test
    void multipleArgumentsAreEvaluatedOnceLeftToRight() throws Exception {
        String source = """
                run: () => {
                    log: 0
                    next: (d) => {
                        log = log * 10 + d
                        d
                    }
                    f: (a, b, c) => {
                        probe()
                        a * 100 + b * 10 + c
                    }
                    r: f(next(1), next(2), next(3))
                    r * 1000 + log
                }
                run()
                """;
        Execution normal = execute(false, source);
        assertInteger(123123, normal.value());
        assertEquals(1, normal.probe().calls.get());
        assertEquals(List.of(INLINE_DEPTH), normal.probe().depths(), normal.probe()::toString);
        assertParity(source, 123123);
        System.out.println("I096_N_ARGUMENT_ORDER=YES");
    }

    /**
     * Binding calls in operand positions of the same activation (a compound
     * expression and the argument of another call) each get their own
     * guarded region and their own fresh activation.
     */
    @Test
    void bindingCallsInOperandPositionsRunInline() throws Exception {
        String source = """
                run: () => {
                    f: (x, y) => {
                        local: x * y
                        probe()
                        local
                    }
                    g: (v) => {
                        probe()
                        v + 1
                    }
                    r: f(2, 3) + f(4, 5) * 10
                    g(f(1, r))
                }
                run()
                """;
        Execution normal = execute(false, source);
        assertInteger(207, normal.value());
        assertEquals(4, normal.probe().calls.get());
        assertEquals(List.of(INLINE_DEPTH), normal.probe().depths(), normal.probe()::toString);
        ProtosSemanticBytecodeRootNode runRoot = normal.innermostRoot();
        assertSame(runRoot, normal.probe().stacks.get(0).get(0));
        List<String> names = instructionNames(runRoot);
        // f(2, 3), f(4, 5), f(1, r) and g(...) are four distinct eligible
        // sites; a duplicated emission of any of them would exceed four.
        assertEquals(4, count(names, "PrepareInlineClosureLiteralCall"), names::toString);
        assertEquals(4, count(names, "AdmitsInlineClosureLiteralCall"), names::toString);
        assertEquals(
                5,
                rootTags(runRoot.getBytecodeNode()),
                "run's RootTag plus one inline callee RootTag per site");
        assertParity(source, 207);
        System.out.println("I096_OPERAND_POSITION_INLINE=YES");
    }

    @Test
    void multipleArgumentCallOverrideTakesThePhysicalFallback() throws Exception {
        String source = """
                run: () => {
                    f: (a, b) => {
                        probe()
                        a * 10 + b
                    }
                    f.call: (a, b) => {
                        probe()
                        a * 1000 + b * 100
                    }
                    g: (a, b, c) => {
                        probe()
                        a * 100 + b * 10 + c
                    }
                    g.call: (a, b, c) => {
                        probe()
                        c * 100 + b * 10 + a
                    }
                    x: f(1, 2)
                    y: g(1, 2, 3)
                    f.removeSlot("call")
                    g.removeSlot("call")
                    x * 1000000 + y * 1000 + f(1, 2) * 1000 + g(1, 2, 3)
                }
                run()
                """;
        Execution normal = execute(false, source);
        assertInteger(1200333123L, normal.value());
        // Execution order: C2 override, C3 override (rejected admission),
        // then C2 and C3 after removeSlot (canonical selection restored).
        List<List<RootNode>> stacks = normal.probe().stacks;
        assertEquals(4, stacks.size(), normal.probe()::toString);
        assertTrue(stacks.get(0).size() > INLINE_DEPTH, normal.probe()::toString);
        assertTrue(stacks.get(1).size() > INLINE_DEPTH, normal.probe()::toString);
        assertEquals(INLINE_DEPTH, stacks.get(2).size(), normal.probe()::toString);
        assertEquals(INLINE_DEPTH, stacks.get(3).size(), normal.probe()::toString);
        assertParity(source, 1200333123L);
        System.out.println("I096_N_CALL_OVERRIDE_FALLBACK=YES");
    }

    @Test
    void multipleArgumentDebugModeKeepsThePhysicalCalleeRoot() throws Exception {
        Execution debug = execute(true, """
                run: () => {
                    f: (a, b, c) => {
                        probe()
                        a * 100 + b * 10 + c
                    }
                    f(1, 2, 3)
                }
                run()
                """);
        assertInteger(123, debug.value());
        assertTrue(debug.probe().depths().get(0) > INLINE_DEPTH, debug.probe()::toString);
        System.out.println("I096_N_DEBUG_TOPOLOGY_PRESERVED=YES");
    }

    /** C8, the expansion budget itself, runs inline with distinct arguments. */
    @Test
    void eightArgumentCallAtTheBudgetRunsInline() throws Exception {
        String source = """
                run: () => {
                    f: (a, b, c, d, e, g, h, i) => {
                        probe()
                        ((((((a * 10 + b) * 10 + c) * 10 + d) * 10 + e) * 10 + g) * 10 + h) * 10 + i
                    }
                    f(8, 7, 6, 5, 4, 3, 2, 1)
                }
                run()
                """;
        Execution normal = execute(false, source);
        assertInteger(87654321, normal.value());
        assertEquals(List.of(INLINE_DEPTH), normal.probe().depths(), normal.probe()::toString);
        assertInlineSiteWithParameters(normal.innermostRoot(), 8);
        assertParity(source, 87654321);
        assertTrue(
                execute(true, source).probe().depths().get(0) > INLINE_DEPTH,
                "DEBUG keeps the physical C8 call");
        System.out.println("I096_C8_INLINE=YES");
    }

    /**
     * An earlier operand reassigns the binding: the later site reads the
     * receiver once, after that operand, and takes the fallback for the
     * other definition; a site whose own argument reassigns the binding
     * keeps the receiver read before its arguments. Every argument effect
     * happens exactly once.
     */
    @Test
    void operandReassignmentIsObservedWithoutRereadingTheReceiver() throws Exception {
        String source = """
                run: () => {
                    effects: 0
                    f: (a, b) => {
                        probe()
                        a * 100 + b
                    }
                    original: f
                    swap: (v) => {
                        effects = effects + 1
                        f = (a, b) => { a * 1000 + b }
                        v
                    }
                    first: f(1, 2) + swap(0) + f(3, 4)
                    f = original
                    second: f(swap(5), 6)
                    first * 10000 + second * 10 + effects
                }
                run()
                """;
        // first = 102 + 0 + 3004; second = 506 (the receiver was read first).
        Execution normal = execute(false, source);
        assertInteger(31060000L + 5060 + 2, normal.value());
        assertEquals(2, normal.probe().calls.get(), normal.probe()::toString);
        assertEquals(List.of(INLINE_DEPTH), normal.probe().depths(), normal.probe()::toString);
        assertParity(source, 31060000L + 5060 + 2);
        System.out.println("I096_OPERAND_REASSIGNMENT_SINGLE_READ=YES");
    }

    /** A call wider than the expansion budget keeps the unchanged topology. */
    @Test
    void arityAboveTheExpansionBudgetIsNotInlined() throws Exception {
        assertEquals(8, CanonicalToBytecodeLowerer.MAX_INLINE_CLOSURE_CALL_ARGUMENTS);
        String source = """
                run: () => {
                    f: (a, b, c, d, e, g, h, i, j) => {
                        probe()
                        (((((((a * 10 + b) * 10 + c) * 10 + d) * 10 + e) * 10 + g) * 10 + h) * 10 + i) * 10 + j
                    }
                    f(1, 2, 3, 4, 5, 6, 7, 8, 9)
                }
                run()
                """;
        Execution normal = execute(false, source);
        assertInteger(123456789, normal.value());
        assertTrue(normal.probe().depths().get(0) > INLINE_DEPTH, normal.probe()::toString);
        List<String> names = instructionNames(normal.outermostGuestRoot());
        assertFalse(
                names.stream().anyMatch(name -> name.contains("InlineClosureLiteralCall")),
                names::toString);
        assertParity(source, 123456789);
        assertTrue(
                execute(true, source).probe().depths().get(0) > INLINE_DEPTH,
                "DEBUG keeps the physical C9 call");
        System.out.println("I096_EXPANSION_BUDGET_FALLBACK=YES");
    }

    // ---- I096 nested Closures: bodies creating Closures ---------------------

    /**
     * The outer body runs inline in {@code run}; the nested Closure keeps its
     * own CallTarget, whose caller frame is {@code run} itself, never the
     * outer callee. The inline region creates it over the callee's own
     * activation (named path, never the frame-native one), and the group
     * gains no duplicate root, before or after a reparse.
     */
    @Test
    void nestedClosureWithoutEscapeRunsTheOuterBodyInline() throws Exception {
        String source = """
                run: () => {
                    f: (value) => {
                        probe()
                        inner: () => {
                            probe()
                            value + 1
                        }
                        inner()
                    }
                    f(41)
                }
                run()
                """;
        Execution normal = execute(false, source);
        assertInteger(42, normal.value());
        List<List<RootNode>> stacks = normal.probe().stacks;
        assertEquals(2, stacks.size(), normal.probe()::toString);
        ProtosSemanticBytecodeRootNode runRoot = normal.innermostRoot();
        assertEquals(INLINE_DEPTH, stacks.get(0).size(), normal.probe()::toString);
        assertEquals(INLINE_DEPTH + 1, stacks.get(1).size(), normal.probe()::toString);
        assertTrue(stacks.get(1).get(0) != runRoot, "inner keeps its own CallTarget");
        assertSame(runRoot, stacks.get(1).get(1), "inner is called directly from run");

        List<String> region = nestedInlineRegion(runRoot);
        assertEquals(1, count(region, "MaterializeClosure"), region::toString);
        assertEquals(
                2,
                rootTags(runRoot.getBytecodeNode()),
                "run's RootTag plus the inline outer callee RootTag");

        Execution debug = execute(true, source);
        assertInteger(42, debug.value());
        assertEquals(INLINE_DEPTH + 1, debug.probe().stacks.get(0).size(), debug.probe()::toString);
        assertEquals(INLINE_DEPTH + 2, debug.probe().stacks.get(1).size(), debug.probe()::toString);

        // f(41, 0) is never a candidate (arity), so it lowers the same
        // Closure roots without any inline region.
        assertNoDuplicateRootsAndStableReparse(source, source.replace("f(41)", "f(41, 0)"), 42);
        System.out.println("I096_NESTED_NO_ESCAPE_INLINE=YES");
    }

    /** Two nested Closures read and write one shared callee binding. */
    @Test
    void nestedClosuresShareOneMutableCapture() throws Exception {
        String source = """
                run: () => {
                    f: (value) => {
                        local: value
                        read: () => local
                        write: (v) => {
                            local = v
                            local
                        }
                        before: read()
                        write(42)
                        probe()
                        before + read()
                    }
                    f(1)
                }
                run()
                """;
        Execution normal = execute(false, source);
        assertInteger(43, normal.value());
        assertEquals(List.of(INLINE_DEPTH), normal.probe().depths(), normal.probe()::toString);
        assertEquals(2, count(nestedInlineRegion(normal.innermostRoot()), "MaterializeClosure"));
        assertParity(source, 43);
        // Two nested definitions: still no duplicate root, stable reparse.
        assertNoDuplicateRootsAndStableReparse(source, source.replace("f(1)", "f(1, 0)"), 43);
        System.out.println("I096_NESTED_MUTABLE_CAPTURE_SHARED=YES");
    }

    /**
     * A Closure returned by the inline body outlives it: every later call
     * updates the same captured binding of the callee's activation, and two
     * invocations of the outer body never share it.
     */
    @Test
    void escapedNestedClosureOutlivesTheInlineBody() throws Exception {
        String source = """
                run: () => {
                    maker: (seed) => {
                        value: seed
                        probe()
                        () => {
                            probe()
                            value = value + 1
                            value
                        }
                    }
                    counter: maker(40)
                    a: counter()
                    b: counter()
                    a + b
                }
                run()
                """;
        Execution normal = execute(false, source);
        assertInteger(83, normal.value());
        List<List<RootNode>> stacks = normal.probe().stacks;
        assertEquals(3, stacks.size(), normal.probe()::toString);
        ProtosSemanticBytecodeRootNode runRoot = normal.innermostRoot();
        assertEquals(INLINE_DEPTH, stacks.get(0).size(), normal.probe()::toString);
        for (int call = 1; call < 3; call++) {
            assertEquals(INLINE_DEPTH + 1, stacks.get(call).size(), normal.probe()::toString);
            assertSame(runRoot, stacks.get(call).get(1), "the counter is called from run");
        }
        assertEquals(1, count(nestedInlineRegion(runRoot), "MaterializeClosure"));
        assertParity(source, 83);

        String instances = """
                run: () => {
                    maker: (seed) => {
                        value: seed
                        () => {
                            value = value + 1
                            value
                        }
                    }
                    c1: maker(40)
                    c2: maker(100)
                    a: c1()
                    b: c2()
                    c: c1()
                    a * 10000 + b * 10 + c
                }
                run()
                """;
        assertParity(instances, 411052);

        // The counter escapes run itself, the root holding the inline
        // region, and is called only after run has completed.
        String afterCaller = """
                run: () => {
                    maker: (seed) => {
                        value: seed
                        () => {
                            value = value + 1
                            value
                        }
                    }
                    maker(40)
                }
                counter: run()
                a: counter()
                b: counter()
                a + b
                """;
        assertParity(afterCaller, 83);
        System.out.println("I096_NESTED_ESCAPE_LIFETIME=YES");
    }

    /**
     * The callee's Context is fresh per invocation, distinct from the
     * caller's, and is the context its nested Closures capture: their reads
     * and writes, and a remove/recreate of the binding, are observed through
     * it.
     */
    @Test
    void nestedCapturesShareTheCalleesOwnContext() throws Exception {
        String source = """
                run: () => {
                    f: (seed) => {
                        value: seed
                        bump: () => { value = value + 1 }
                        read: () => value
                        bump()
                        bump()
                        before: read()
                        context.removeSlot("value")
                        context.value: 100
                        probe()
                        after: read()
                        context
                    }
                    a: f(10)
                    b: f(20)
                    (a === b).ifTrueIfFalse(() => { -1 }, () => {
                        (a === context).ifTrueIfFalse(() => { -2 }, () => {
                            a.before * 1000000 + a.after * 1000 + b.before
                        })
                    })
                }
                run()
                """;
        // ifTrueIfFalse only selects the result here; it is not I096 evidence.
        Execution normal = execute(false, source);
        assertInteger(12100022, normal.value());
        assertEquals(List.of(INLINE_DEPTH), normal.probe().depths(), normal.probe()::toString);
        assertParity(source, 12100022);
        System.out.println("I096_NESTED_CONTEXT_IDENTITY=YES");
    }

    /**
     * Reentrant invocations through the inline site keep isolated callee
     * activations: a nested reader still sees its own invocation's binding
     * after a deeper invocation of the same site completed.
     */
    @Test
    void reentrantNestedCapturesKeepIsolatedActivations() throws Exception {
        String source = """
                step: (k) => {
                    (k > 0).ifTrueIfFalse(() => { run(k - 1) }, () => { 0 })
                }
                run: (n) => {
                    f: (k) => {
                        local: k
                        get: () => local
                        deeper: step(k)
                        get() + deeper + get()
                    }
                    f(n)
                }
                run(3)
                """;
        assertInteger(12, execute(false, source).value());
        assertParity(source, 12);
        System.out.println("I096_NESTED_REENTRANT_ISOLATION=YES");
    }

    /**
     * A nested Closure uses the callee's captured ReturnHome, which is the
     * caller's: its non-local return leaves {@code run}, as physically.
     */
    @Test
    void nestedNonLocalReturnUsesTheCapturedHome() throws Exception {
        String source = """
                run: () => {
                    f: (value) => {
                        probe()
                        escape: () => { ^value + 1 }
                        escape()
                        0
                    }
                    f(41)
                    0
                }
                run()
                """;
        Execution normal = execute(false, source);
        assertInteger(42, normal.value());
        assertEquals(List.of(INLINE_DEPTH), normal.probe().depths(), normal.probe()::toString);
        assertParity(source, 42);
        System.out.println("I096_NESTED_RETURN_HOME_PRESERVED=YES");
    }

    @Test
    void suspensionAfterNestedCaptureResumesWithoutReplay() throws Exception {
        String source = """
                run: () => {
                    step: (() => { 1 }).future()
                    visits: 0
                    f: (value) => {
                        visits = visits + 1
                        get: () => value
                        r: value + step.value()
                        get() + r
                    }
                    f(41) * 10 + visits
                }
                run()
                """;
        ProtosExecutionOutcome outcome =
                ProtosTestExecutionSupport.execute(
                        "i096-nested-suspension.protos",
                        source,
                        new ProtosCoreBootstrap().bootstrap(CORE).newModuleActivation());
        assertEquals(
                ProtosExecutionOutcome.State.COMPLETED,
                outcome.state(),
                () -> "outcome=" + outcome.state() + ", error=" + outcome.error());
        assertInteger(831, outcome.value());
        System.out.println("I096_NESTED_SUSPENSION_PRESERVED=YES");
    }

    /**
     * A self-recursive binding with nested blocks is inlined once at the
     * caller's site; its recursive calls are never candidates and stay
     * physical, so lowering does not expand recursively.
     */
    @Test
    void recursiveNestedBodyIsInlinedOnlyAtTheCallerSite() throws Exception {
        String source = """
                run: () => {
                    f: (n) => {
                        (n > 0).ifTrueIfFalse(() => { n + f(n - 1) }, () => { 0 })
                    }
                    f(4)
                }
                run()
                """;
        try (Context context = Context.newBuilder(ProtosLanguage.ID).build()) {
            context.initialize(ProtosLanguage.ID);
            context.enter();
            try {
                ProtosSemanticBytecodeRootNode module = lowerRoot(source);
                module.getRootNodes().ensureComplete();
                assertEquals(
                        1,
                        candidateRegions(groupRoots(module)).stream()
                                .mapToInt(Integer::intValue).sum(),
                        "only run's own f(4) site");
                assertInteger(10, module.getCallTarget().call(new Probe().module()));
            } finally {
                context.leave();
            }
        }
        assertParity(source, 10);
        System.out.println("I096_NESTED_RECURSION_BOUNDED=YES");
    }

    /**
     * Negative evidence for the nested class: a nested-Closure body whose
     * selection is overridden, and a module-level literal owning its own
     * ReturnHome, keep the physical callee behind the emitted guard.
     */
    @Test
    void nestedBodiesKeepTheFallbackWhenNotAdmitted() throws Exception {
        String override = """
                run: () => {
                    f: (v) => {
                        probe()
                        g: () => v
                        g()
                    }
                    f.call: (v) => {
                        probe()
                        h: () => v * 10
                        h()
                    }
                    a: f(1)
                    f.removeSlot("call")
                    b: f(2)
                    a * 10 + b
                }
                run()
                """;
        Execution overridden = execute(false, override);
        assertInteger(102, overridden.value());
        assertTrue(
                overridden.probe().stacks.get(0).size() > INLINE_DEPTH,
                overridden.probe()::toString);
        assertEquals(
                INLINE_DEPTH, overridden.probe().stacks.get(1).size(), overridden.probe()::toString);
        assertParity(override, 102);

        String moduleLiteral = """
                g: ((v) => {
                    probe()
                    () => v
                })(5)
                g()
                """;
        Execution module = execute(false, moduleLiteral);
        assertInteger(5, module.value());
        List<RootNode> stack = module.probe().stacks.get(0);
        assertEquals(2, stack.size(), "the literal's physical root over the module root");
        assertTrue(
                position(instructionNames(
                                assertInstanceOf(
                                        ProtosSemanticBytecodeRootNode.class, stack.get(1))),
                        "AdmitsInlineClosureLiteralCall") >= 0,
                "the guarded region exists and rejected the owned ReturnHome");
        assertParity(moduleLiteral, 5);
        System.out.println("I096_NESTED_NOT_ADMITTED_FALLBACK=YES");
    }

    /**
     * The instructions from the single inline site's admission in {@code
     * caller} onward: the admitted body
     * creates nested Closures over the callee's own activation, loaded from
     * the carrier, and never uses the frame-native callback materialization.
     */
    private static List<String> nestedInlineRegion(ProtosSemanticBytecodeRootNode caller) {
        List<String> names = instructionNames(caller);
        int admit = position(names, "AdmitsInlineClosureLiteralCall");
        assertTrue(admit >= 0, names::toString);
        // Every prepared call inside the body emits its own fallback load, so
        // the region extends to the end of the root; the fixtures create no
        // Closure in the caller after the site.
        List<String> region = names.subList(admit, names.size());
        assertTrue(count(region, "LoadInlineCallbackActivation") >= 1, region::toString);
        assertEquals(0, count(region, "MaterializeInlineCallbackActivation"), region::toString);
        return region;
    }

    /**
     * {@code admitted} lowers exactly as many group roots as {@code
     * notCandidate}, which differs only in a site that is never inlined, so
     * the inline body duplicated no nested root; a reparse keeps the same
     * roots, order and candidate regions.
     */
    private static void assertNoDuplicateRootsAndStableReparse(
            String admitted, String notCandidate, long expected) throws Exception {
        try (Context context = Context.newBuilder(ProtosLanguage.ID).build()) {
            context.initialize(ProtosLanguage.ID);
            context.enter();
            try {
                ProtosSemanticBytecodeRootNode module = lowerRoot(admitted);
                List<ProtosSemanticBytecodeRootNode> before = groupRoots(module);
                List<Integer> regionsBefore = candidateRegions(before);
                assertEquals(
                        groupRoots(lowerRoot(notCandidate)).size(),
                        before.size(),
                        "no nested root is lowered twice");

                module.getRootNodes().ensureComplete();
                List<ProtosSemanticBytecodeRootNode> after = groupRoots(module);
                assertEquals(before.size(), after.size());
                for (int i = 0; i < before.size(); i++) {
                    assertSame(before.get(i), after.get(i), "root order at " + i);
                    assertTrue(after.get(i).getBytecodeNode().getSourceSection() != null,
                            "source materialized at " + i);
                }
                assertEquals(regionsBefore, candidateRegions(after));
                assertInteger(expected, module.getCallTarget().call(new Probe().module()));
            } finally {
                context.leave();
            }
        }
    }

    /**
     * Structural lowering evidence: the caller root holds the guarded region,
     * one inline parameter load per supplied argument after admission, and
     * the carrier's physical fallback.
     */
    private static void assertInlineSiteWithParameters(
            ProtosSemanticBytecodeRootNode caller, int parameters) {
        List<String> names = instructionNames(caller);
        int prepare = position(names, "PrepareInlineClosureLiteralCall");
        int admit = position(names, "AdmitsInlineClosureLiteralCall");
        int fallback = position(names, "LoadInlineLiteralFallbackCall");
        assertTrue(prepare >= 0 && admit > prepare, names::toString);
        assertTrue(fallback > admit, names::toString);
        List<String> region = names.subList(admit, fallback);
        assertEquals(parameters, count(region, "LoadInlineClosureArgument"), names::toString);
        assertEquals(
                2,
                rootTags(caller.getBytecodeNode()),
                "caller RootTag plus the inline callee semantic RootTag");
    }

    private static List<ProtosSemanticBytecodeRootNode> groupRoots(
            ProtosSemanticBytecodeRootNode root) {
        return root.getRootNodes().getNodes().stream()
                .map(node -> assertInstanceOf(ProtosSemanticBytecodeRootNode.class, node))
                .toList();
    }

    private static List<Integer> candidateRegions(List<ProtosSemanticBytecodeRootNode> roots) {
        return roots.stream()
                .map(root -> count(
                        instructionNames(root.getBytecodeNode()),
                        "PrepareInlineClosureLiteralCall"))
                .toList();
    }

    private static int count(List<String> names, String operation) {
        return (int) names.stream().filter(name -> name.contains(operation)).count();
    }

    private record Execution(Object value, Probe probe) {
        /** The innermost guest root of the first probe call. */
        ProtosSemanticBytecodeRootNode innermostRoot() {
            return assertInstanceOf(
                    ProtosSemanticBytecodeRootNode.class,
                    probe.stacks.get(0).get(0));
        }

        /** {@code run}'s root: the guest root directly below the module root. */
        ProtosSemanticBytecodeRootNode outermostGuestRoot() {
            List<RootNode> stack = probe.stacks.get(0);
            return assertInstanceOf(
                    ProtosSemanticBytecodeRootNode.class,
                    stack.get(stack.size() - 2));
        }
    }

    private static Execution execute(boolean debug, String source) throws Exception {
        Context.Builder builder = Context.newBuilder(ProtosLanguage.ID);
        if (debug) {
            builder.allowExperimentalOptions(true).option("protos.Debug", "true");
        }
        try (Context context = builder.build()) {
            context.initialize(ProtosLanguage.ID);
            context.enter();
            try {
                Probe probe = new Probe();
                Object value = lowerRoot(source).getCallTarget().call(probe.module());
                return new Execution(value, probe);
            } finally {
                context.leave();
            }
        }
    }

    /** NORMAL and DEBUG produce the same result for the same program. */
    private static void assertParity(String source, long expected) throws Exception {
        assertInteger(expected, execute(false, source).value());
        assertInteger(expected, execute(true, source).value());
    }

    /**
     * A native {@code probe} binding recording the guest CallTarget stack of
     * every call.
     */
    private static final class Probe {
        final List<List<RootNode>> stacks = new ArrayList<>();
        final AtomicInteger calls = new AtomicInteger();

        List<Integer> depths() {
            return stacks.stream().map(List::size).distinct().toList();
        }

        ProtosActivation module() throws Exception {
            ProtosActivation module =
                    new ProtosCoreBootstrap().bootstrap(CORE).newModuleActivation();
            module.context().createLocalSlot(
                    "probe",
                    ProtosClosureValue.nativeClosure(
                            (activation, supplied) -> {
                                calls.incrementAndGet();
                                List<RootNode> stack = new ArrayList<>();
                                Truffle.getRuntime()
                                        .iterateFrames(
                                                frame -> {
                                                    stack.add(
                                                            ((RootCallTarget) frame.getCallTarget())
                                                                    .getRootNode());
                                                    return null;
                                                });
                                stacks.add(stack);
                                return ProtosNullValue.INSTANCE;
                            }));
            return module;
        }

        @Override
        public String toString() {
            return "stacks=" + stacks;
        }
    }

    private static ProtosClosureValue closure(String source, ProtosActivation module) {
        return assertInstanceOf(
                ProtosClosureValue.class,
                lowerRoot(source).getCallTarget().call(module));
    }

    private static Object integerArgument(ProtosActivation module) {
        return lowerRoot("41").getCallTarget().call(module);
    }

    private static ProtosClosureExecutionPlanCell planCell(ProtosClosureValue closure) {
        ProtosClosureExecutionPlanCell cell = ProtosClosureExecutionPlanCell.pendingGroup();
        cell.freeze(closure.executionPlan().orElseThrow());
        return cell;
    }

    private static ProtosSemanticBytecodeRootNode lowerRoot(String characters) {
        Source source =
                Source.newBuilder(ProtosLanguage.ID, characters, "i096-a.protos").build();
        CanonicalSequence sequence =
                (CanonicalSequence)
                        new Canonicalizer()
                                .canonicalize(new ProtosParser(characters).parseProgram());
        // I101-A: lowered under the immutable profile of the entered Context.
        return new CanonicalToBytecodeLowerer(
                        LANGUAGE_REF.get(null),
                        source,
                        ProtosLanguageContext.current().executionMode())
                .lowerRoot(sequence);
    }

    private static List<String> instructionNames(ProtosSemanticBytecodeRootNode root) {
        root.getRootNodes().ensureComplete();
        return instructionNames(root.getBytecodeNode());
    }

    private static List<String> instructionNames(BytecodeNode bytecodeNode) {
        return bytecodeNode.getInstructionsAsList().stream()
                .map(Instruction::getName)
                .toList();
    }

    private static int position(List<String> names, String operation) {
        for (int i = 0; i < names.size(); i++) {
            if (names.get(i).contains(operation)) {
                return i;
            }
        }
        return -1;
    }

    private static void assertInteger(long expected, Object value) {
        assertEquals(
                Long.toString(expected),
                com.guillermomolina.protos.runtime.ProtosNumericValueSupport
                        .integerDecimalText(value));
    }

    private static int rootTags(BytecodeNode bytecodeNode) {
        return countRootTags(bytecodeNode.getTagTree());
    }

    private static int countRootTags(TagTree tree) {
        if (tree == null) {
            return 0;
        }
        int count = tree.hasTag(StandardTags.RootTag.class) ? 1 : 0;
        for (TagTree child : tree.getTreeChildren()) {
            count += countRootTags(child);
        }
        return count;
    }
}
