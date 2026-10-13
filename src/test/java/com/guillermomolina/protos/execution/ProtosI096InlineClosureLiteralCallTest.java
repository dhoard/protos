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
 * <p>Inline execution is observed through the guest CallTarget stack seen by a
 * native {@code probe}: an admitted body leaves only {@code run} over the
 * module root (depth 2); a physical callee adds its own root.
 *
 * <p>I096-B1 extends the evidence to ordinary C0/C1 calls through a lexical
 * binding whose candidate body is known statically: the candidate runs inline
 * only when the run-time selection proves it, with the effective instance's
 * own captures; every other value takes the prepared physical fallback.
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
        int mode = position(names, "IsNormalExecutionMode");
        int prepare = position(names, "PrepareInlineClosureLiteralCall");
        int admit = position(names, "AdmitsInlineClosureLiteralCall");
        assertTrue(mode >= 0, names::toString);
        assertTrue(prepare > mode && admit > prepare, names::toString);
        // The admission miss keeps the carrier's ordinary physical fallback,
        // and the DEBUG alternative keeps the unchanged direct-call attempt.
        assertTrue(position(names, "LoadInlineLiteralFallbackCall") > admit, names::toString);
        assertTrue(position(names, "TryDirectCallZero") > admit, names::toString);
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
        assertTrue(
                position(instructionNames(normal.innermostRoot()), "TryDirectCallOne") >= 0,
                "the DEBUG alternative keeps the C1 direct-call attempt");
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
        System.out.println("I096_A_DEBUG_TOPOLOGY_PRESERVED=YES");
    }

    @Test
    void nonAdmittedShapesKeepTheUnchangedCallTopology() throws Exception {
        List<String> sources = List.of(
                // A nested Closure needs durable capture of a callee binding.
                """
                run: () => {
                    f: ((v) => { () => v })(5)
                    probe()
                    f()
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
        int mode = position(names, "IsNormalExecutionMode");
        int prepare = position(names, "PrepareInlineClosureLiteralCall");
        int admit = position(names, "AdmitsInlineClosureLiteralCall");
        assertTrue(mode >= 0 && prepare > mode && admit > prepare, names::toString);
        assertTrue(position(names, "LoadInlineLiteralFallbackCall") > admit, names::toString);
        assertTrue(position(names, "TryDirectCallZero") > admit, names::toString);
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
                // C2.
                """
                run: () => {
                    f: (a, b) => { a + b }
                    probe()
                    f(2, 3)
                }
                run()
                """,
                // A body creating a nested Closure.
                """
                run: () => {
                    f: (v) => {
                        g: () => v
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
        return new CanonicalToBytecodeLowerer(LANGUAGE_REF.get(null), source)
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
