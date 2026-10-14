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

import com.guillermomolina.protos.parser.ProtosParser;
import com.guillermomolina.protos.runtime.ProtosActivation;
import com.guillermomolina.protos.runtime.ProtosClosureValue;
import com.guillermomolina.protos.runtime.ProtosIntegerValue;
import com.guillermomolina.protos.runtime.ProtosNullValue;
import com.guillermomolina.protos.runtime.ProtosObjectValue;
import com.guillermomolina.protos.runtime.ProtosPrelude;
import com.guillermomolina.protos.runtime.ProtosSignalException;
import com.guillermomolina.protos.semantic.Canonicalizer;
import com.guillermomolina.protos.semantic.ast.CanonicalSequence;
import com.oracle.truffle.api.TruffleLanguage.LanguageReference;
import com.oracle.truffle.api.bytecode.BytecodeConfig;
import com.oracle.truffle.api.bytecode.BytecodeLocal;
import com.oracle.truffle.api.bytecode.BytecodeNode;
import com.oracle.truffle.api.bytecode.BytecodeRootNodes;
import com.oracle.truffle.api.bytecode.BytecodeTier;
import com.oracle.truffle.api.bytecode.ContinuationResult;
import com.oracle.truffle.api.bytecode.Instruction;
import com.oracle.truffle.api.source.Source;
import java.util.List;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;

/**
 * PERF028-A focal evidence: a bare assignment statically {@code Resolved} in
 * the genuine scope that owns the executing frame compiles to {@code
 * ResolveCurrentFrameLocalWriteTarget}/{@code AssignCurrentFrameLocal}, whose
 * constant {@link com.oracle.truffle.api.bytecode.LocalAccessor} identifies the
 * binding, instead of the String-keyed {@code ResolveWritableLexicalTarget}/
 * {@code AssignResolvedLexicalTarget} pair. Static resolution proves identity,
 * not presence (D179 C0), so a cleared current local selects the exact generic
 * fallback before RHS evaluation, and the selected destination is retained
 * through RHS effects, suspension, and resumption without re-resolution
 * ({@code EXECUTION_AND_CONTROL.md} §7).
 */
final class ProtosPerf028AResolvedCurrentLexicalWriteTest {
    private static final LanguageReference<ProtosLanguage> LANGUAGE_REF =
            LanguageReference.create(ProtosLanguage.class);

    private static final String RESOLVE_CURRENT = "ResolveCurrentFrameLocalWriteTarget";
    private static final String ASSIGN_CURRENT = "AssignCurrentFrameLocal";
    private static final String RESOLVE_GENERIC = "ResolveWritableLexicalTarget";
    private static final String ASSIGN_GENERIC = "AssignResolvedLexicalTarget";

    @Test
    void presentCurrentWriteUsesConstantAccessorAndReturnsTheExactRhs() throws Exception {
        withFixture(
                fixture -> {
                    ProtosObjectValue seed = newObject();
                    ProtosObjectValue replacement = newObject();
                    fixture.module.context().createLocalSlot("seed", seed);
                    fixture.module.context().createLocalSlot("replacement", replacement);

                    ProtosSemanticBytecodeRootNode root =
                            lower(
                                    fixture.language,
                                    "x: seed\n"
                                            + "r: (x = replacement)\n"
                                            + "context.slotValue(\"x\")");
                    assertCurrentFrameLocalWritePath(root.getBytecodeNode());

                    // Guest reflection and host reflection observe the one
                    // authoritative binding the accessor wrote.
                    assertSame(replacement, root.getCallTarget().call(fixture.module));
                    assertSame(replacement, slot(fixture.module, "x"));
                    assertSame(replacement, slot(fixture.module, "r"));
                });
        System.out.println("CURRENT_RESOLVED_WRITE_USES_CONSTANT_LOCAL_ACCESSOR=YES");
        System.out.println("ASSIGNMENT_RESULT_IS_RHS=PASS");
    }

    @Test
    void currentAbsentAtSelectionWritesNearestOuterLexicalBinding() throws Exception {
        withFixture(
                fixture -> {
                    ClosureUnderTest plan =
                            closurePlan(
                                    fixture,
                                    "x: 1\n"
                                            + "() => { x: 2\n"
                                            + "context.removeSlot(\"x\")\n"
                                            + "x = 4 }");
                    assertCurrentFrameLocalWritePath(activationNode(plan));

                    ProtosActivation invocation = fixture.invocationOf(plan.closure);
                    assertEquals(4L, unwrapNumber(plan.plan.executeBytecodeActivationForTesting(invocation)));

                    assertEquals(4L, unwrapNumber(slot(fixture.module, "x")));
                    assertFalse(invocation.context().hasLocalSlot("x"));
                });
        System.out.println("CURRENT_ABSENT_EXACT_FALLBACK=PASS");
    }

    @Test
    void currentAbsentAtSelectionWritesReceiverOwnSlot() throws Exception {
        withFixture(
                fixture -> {
                    Object result =
                            run(
                                    fixture,
                                    "obj: { x: 5\n"
                                            + "m: () => { x: 0\n"
                                            + "context.removeSlot(\"x\")\n"
                                            + "x = 7 } }\n"
                                            + "obj.m()\n"
                                            + "obj.x");
                    assertEquals(7L, unwrapNumber(result));
                    assertFalse(fixture.module.context().hasLocalSlot("x"));
                });
        System.out.println("CURRENT_ABSENT_RECEIVER_FALLBACK=PASS");
    }

    @Test
    void noDestinationSignalsSlotNotFoundBeforeRhsEvaluation() throws Exception {
        withFixture(
                fixture -> {
                    int[] rhsEvaluations = new int[1];
                    fixture.module.context().createLocalSlot(
                            "touch",
                            ProtosClosureValue.nativeClosure(
                                    (nativeActivation, supplied) -> {
                                        rhsEvaluations[0]++;
                                        return ProtosNullValue.INSTANCE;
                                    }));

                    ProtosSemanticBytecodeRootNode root =
                            lower(
                                    fixture.language,
                                    "x: 1\n" + "context.removeSlot(\"x\")\n" + "x = touch()");
                    assertCurrentFrameLocalWritePath(root.getBytecodeNode());

                    ProtosSignalException signal =
                            assertThrows(
                                    ProtosSignalException.class,
                                    () -> root.getCallTarget().call(fixture.module));
                    assertSame(fixture.slotNotFoundPrototype, parentOf(signal));
                    assertEquals(0, rhsEvaluations[0]);
                    assertFalse(fixture.module.context().hasLocalSlot("x"));
                });
        System.out.println("DESTINATION_SELECTED_BEFORE_RHS=PASS");
    }

    @Test
    void currentSelectedBeforeRhsIsNotRetargetedBySameNameCreationElsewhere() throws Exception {
        withFixture(
                fixture -> {
                    ClosureUnderTest plan =
                            closurePlan(
                                    fixture,
                                    "outer: context\n"
                                            + "makeOuter: () => { outer.x: 5\n"
                                            + "6 }\n"
                                            + "() => { x: 2\n"
                                            + "x = makeOuter()\n"
                                            + "x }");
                    assertCurrentFrameLocalWritePath(activationNode(plan));

                    ProtosActivation invocation = fixture.invocationOf(plan.closure);
                    assertEquals(6L, unwrapNumber(plan.plan.executeBytecodeActivationForTesting(invocation)));

                    assertEquals(5L, unwrapNumber(slot(fixture.module, "x")));
                    assertEquals(6L, unwrapNumber(invocation.context().readLocalSlot("x").orElseThrow()));
                });
        System.out.println("POST_RHS_DESTINATION_RE_RESOLUTION=NO");
    }

    @Test
    void rhsRemovalOfSelectedCurrentBindingIsMutationErrorNotRetarget() throws Exception {
        withFixture(
                fixture -> {
                    ProtosSemanticBytecodeRootNode root =
                            lower(
                                    fixture.language,
                                    "x: 1\n"
                                            + "ctx: context\n"
                                            + "drop: () => { ctx.removeSlot(\"x\")\n"
                                            + "2 }\n"
                                            + "x = drop()");
                    assertCurrentFrameLocalWritePath(root.getBytecodeNode());

                    ProtosSignalException signal =
                            assertThrows(
                                    ProtosSignalException.class,
                                    () -> root.getCallTarget().call(fixture.module));
                    assertSame(fixture.errorPrototype, parentOf(signal));
                    assertFalse(fixture.module.context().hasLocalSlot("x"));
                });
        System.out.println("RHS_REMOVE_SELECTED_CURRENT=EXPECTED_MUTATION_ERROR");
    }

    @Test
    void rhsRemoveThenRecreateInSameContextReceivesTheWrite() throws Exception {
        withFixture(
                fixture -> {
                    ProtosSemanticBytecodeRootNode root =
                            lower(
                                    fixture.language,
                                    "x: 1\n"
                                            + "ctx: context\n"
                                            + "swap: () => { ctx.removeSlot(\"x\")\n"
                                            + "ctx.x: 2\n"
                                            + "3 }\n"
                                            + "x = swap()\n"
                                            + "x");
                    assertCurrentFrameLocalWritePath(root.getBytecodeNode());

                    assertEquals(3L, unwrapNumber(root.getCallTarget().call(fixture.module)));
                    assertEquals(3L, unwrapNumber(slot(fixture.module, "x")));
                });
        System.out.println("RHS_REMOVE_RECREATE_SAME_OWNER=PASS");
    }

    @Test
    void fallbackSelectedWhileCurrentAbsentIsNotStolenByRhsRecreation() throws Exception {
        withFixture(
                fixture -> {
                    ClosureUnderTest plan =
                            closurePlan(
                                    fixture,
                                    "x: 1\n"
                                            + "() => { x: 2\n"
                                            + "ctx: context\n"
                                            + "context.removeSlot(\"x\")\n"
                                            + "recreate: () => { ctx.x: 3\n"
                                            + "4 }\n"
                                            + "x = recreate()\n"
                                            + "x }");
                    assertCurrentFrameLocalWritePath(activationNode(plan));

                    ProtosActivation invocation = fixture.invocationOf(plan.closure);
                    Object result = plan.plan.executeBytecodeActivationForTesting(invocation);

                    // The outer binding selected before the RHS receives the
                    // write; the recreated current binding keeps its own value.
                    assertEquals(3L, unwrapNumber(result));
                    assertEquals(4L, unwrapNumber(slot(fixture.module, "x")));
                    assertEquals(3L, unwrapNumber(invocation.context().readLocalSlot("x").orElseThrow()));
                });
        System.out.println("ABSENT_THEN_RHS_RECREATE_NO_RETARGET=PASS");
    }

    @Test
    void closedCurrentContextRemainsWritable() throws Exception {
        withFixture(
                fixture -> {
                    ProtosSemanticBytecodeRootNode root =
                            lower(
                                    fixture.language,
                                    "x: 1\n"
                                            + "ctx: context\n"
                                            + "closer: () => { ctx.close()\n"
                                            + "2 }\n"
                                            + "x = closer()\n"
                                            + "x");
                    assertCurrentFrameLocalWritePath(root.getBytecodeNode());

                    assertEquals(2L, unwrapNumber(root.getCallTarget().call(fixture.module)));
                    assertEquals(2L, unwrapNumber(slot(fixture.module, "x")));
                });
        System.out.println("CLOSED_WRITE_ALLOWED=PASS");
    }

    @Test
    void frozenCurrentContextRejectsWriteAndPreservesValue() throws Exception {
        withFixture(
                fixture -> {
                    ProtosSemanticBytecodeRootNode root =
                            lower(
                                    fixture.language,
                                    "x: 1\n"
                                            + "ctx: context\n"
                                            + "freezer: () => { ctx.freeze()\n"
                                            + "2 }\n"
                                            + "x = freezer()");
                    assertCurrentFrameLocalWritePath(root.getBytecodeNode());

                    ProtosSignalException signal =
                            assertThrows(
                                    ProtosSignalException.class,
                                    () -> root.getCallTarget().call(fixture.module));
                    assertSame(fixture.errorPrototype, parentOf(signal));
                    assertEquals(1L, unwrapNumber(slot(fixture.module, "x")));
                });
        System.out.println("FROZEN_WRITE_REJECTED=PASS");
    }

    @Test
    void presentNullRemainsDistinctFromAbsent() throws Exception {
        withFixture(
                fixture -> {
                    ProtosSemanticBytecodeRootNode root =
                            lower(fixture.language, "x: 1\n" + "x = null\n" + "x = null");
                    assertCurrentFrameLocalWritePath(root.getBytecodeNode());

                    // The second write succeeds only if the first left x PRESENT.
                    assertSame(ProtosNullValue.INSTANCE, root.getCallTarget().call(fixture.module));
                    assertTrue(fixture.module.context().hasLocalSlot("x"));
                    assertSame(ProtosNullValue.INSTANCE, slot(fixture.module, "x"));
                });
        System.out.println("PRESENT_NULL_DISTINCT_FROM_ABSENT=PASS");
    }

    @Test
    void rhsControlTransferAttemptsNoWrite() throws Exception {
        withFixture(
                fixture -> {
                    ProtosSemanticBytecodeRootNode root =
                            lower(
                                    fixture.language,
                                    "x: 1\n" + "boom: () => missingName\n" + "x = boom()");
                    assertCurrentFrameLocalWritePath(root.getBytecodeNode());

                    assertThrows(
                            ProtosSignalException.class,
                            () -> root.getCallTarget().call(fixture.module));
                    assertEquals(1L, unwrapNumber(slot(fixture.module, "x")));
                });
        System.out.println("RHS_CONTROL_TRANSFER_NO_WRITE=PASS");
    }

    @Test
    void defaultExpressionAssignmentUsesTheSameCurrentAccessorPath() throws Exception {
        withFixture(
                fixture -> {
                    ClosureUnderTest plan =
                            closurePlan(fixture, "r: 0\n" + "f: (x, y = (x = 99)) => x\n" + "r = f(1)\n" + "f");
                    assertCurrentFrameLocalWritePath(activationNode(plan));

                    assertEquals(99L, unwrapNumber(slot(fixture.module, "r")));
                });
        System.out.println("DEFAULT_ASSIGNMENT_PATH=PASS");
    }

    @Test
    void selectionRetainedAcrossSuspensionAndUncachedToCachedTransition() throws Exception {
        withFixture(
                fixture -> {
                    ProtosObjectValue initial = newObject();
                    ProtosObjectValue recreated = newObject();
                    ProtosObjectValue rhs = newObject();
                    ProtosSemanticBytecodeRootNode root =
                            createSelectYieldAssignRoot(fixture.language, initial);
                    ProtosObjectValue executionContext = fixture.module.context();

                    root.getBytecodeNode().setUncachedThreshold(1);
                    ContinuationResult suspended =
                            assertInstanceOf(
                                    ContinuationResult.class,
                                    root.getCallTarget().call(fixture.module));
                    assertSame(initial, executionContext.readLocalSlot("x").orElseThrow());

                    // Remove/recreate in the same context while suspended.
                    executionContext.removeLocalSlot("x");
                    executionContext.createLocalSlot("x", recreated);

                    assertSame(rhs, suspended.continueWith(rhs));
                    assertEquals(BytecodeTier.CACHED, root.getBytecodeNode().getTier());
                    assertSame(rhs, executionContext.readLocalSlot("x").orElseThrow());
                });
        System.out.println("RHS_YIELD_RESUME_SELECTION_PRESERVED=PASS");
        System.out.println("CACHED_TRANSITION_ACCESSOR_COHERENT=PASS");
    }

    @Test
    void removalDuringSuspensionIsMutationErrorAfterResume() throws Exception {
        withFixture(
                fixture -> {
                    ProtosObjectValue initial = newObject();
                    ProtosSemanticBytecodeRootNode root =
                            createSelectYieldAssignRoot(fixture.language, initial);
                    ProtosObjectValue executionContext = fixture.module.context();

                    ContinuationResult suspended =
                            assertInstanceOf(
                                    ContinuationResult.class,
                                    root.getCallTarget().call(fixture.module));
                    executionContext.removeLocalSlot("x");

                    ProtosSignalException signal =
                            assertThrows(
                                    ProtosSignalException.class,
                                    () -> suspended.continueWith(newObject()));
                    assertSame(fixture.errorPrototype, parentOf(signal));
                    assertFalse(executionContext.hasLocalSlot("x"));
                });
        System.out.println("SUSPENDED_REMOVE_SELECTED_CURRENT=EXPECTED_MUTATION_ERROR");
    }

    @Test
    void ineligibleAssignmentsKeepTheirExistingPaths() throws Exception {
        withFixture(
                fixture -> {
                    // Candidate: the write precedes the current declaration.
                    ClosureUnderTest candidate =
                            closurePlan(fixture, "x: 0\n" + "() => { x = 5\n" + "x: 1\n" + "x }");
                    assertGenericLexicalWritePath(activationNode(candidate));
                    ProtosActivation invocation = fixture.invocationOf(candidate.closure);
                    assertEquals(
                            1L,
                            unwrapNumber(candidate.plan.executeBytecodeActivationForTesting(invocation)));
                    assertEquals(5L, unwrapNumber(slot(fixture.module, "x")));

                    // Dynamic: no static declaration anywhere.
                    assertGenericLexicalWritePath(
                            lower(fixture.language, "undeclared = 1").getBytecodeNode());

                    // Explicit member assignment.
                    ProtosSemanticBytecodeRootNode member =
                            lower(fixture.language, "o: { x: 1 }\n" + "o.x = 2\n" + "o.x");
                    List<String> memberNames = instructionNames(member.getBytecodeNode());
                    assertTrue(
                            memberNames.stream().anyMatch(name -> name.contains("AssignLocalSlot")),
                            () -> "explicit member assignment lost its path: " + memberNames);
                    assertTrue(
                            memberNames.stream()
                                    .noneMatch(
                                            name ->
                                                    isCurrentResolve(name)
                                                            || isCurrentAssign(name)),
                            () -> "explicit member assignment used the current-local path: " + memberNames);
                    assertEquals(2L, unwrapNumber(member.getCallTarget().call(fixture.module)));

                    // Captured: the PERF013 path, never the current-local path.
                    ClosureUnderTest captured =
                            closurePlan(fixture, "y: 0\n" + "() => { y = 3 }");
                    List<String> capturedNames = instructionNames(activationNode(captured));
                    assertTrue(
                            capturedNames.stream()
                                    .anyMatch(name -> name.contains("ResolveCaptured")),
                            () -> "captured write lost its PERF013 path: " + capturedNames);
                    assertTrue(
                            capturedNames.stream()
                                    .noneMatch(
                                            name ->
                                                    isCurrentResolve(name)
                                                            || isCurrentAssign(name)),
                            () -> "captured write used the current-local path: " + capturedNames);
                });
        System.out.println("CANDIDATE_FALLBACK_PRESERVED=YES");
        System.out.println("DYNAMIC_FALLBACK_PRESERVED=YES");
        System.out.println("EXPLICIT_MEMBER_ASSIGNMENT_UNCHANGED=YES");
        System.out.println("CAPTURED_WRITE_PATH_PRESERVED=YES");
    }

    /**
     * Mirrors the lowered shape: the frame-backed {@code x} is created, its
     * destination selected and retained, then the RHS suspends; the resume
     * value becomes the RHS written through the same constant accessor.
     */
    private static ProtosSemanticBytecodeRootNode createSelectYieldAssignRoot(
            ProtosLanguage language, ProtosObjectValue initial) {
        BytecodeRootNodes<ProtosSemanticBytecodeRootNode> roots =
                ProtosSemanticBytecodeRootNodeGen.create(
                        language,
                        BytecodeConfig.DEFAULT,
                        builder -> {
                            builder.beginRoot();

                            BytecodeLocal x = builder.createLocal("x", null);
                            BytecodeLocal target = builder.createLocal("target", null);
                            builder.beginInstallFrameLexicalAuthority(
                                    new BytecodeLocal[] {x},
                                    ProtosFrameLexicalLayout.of(
                                            new String[] {"x"},
                                            ProtosFrameLexicalLayout.localOffsetsOf(
                                                    new BytecodeLocal[] {x})));
                            builder.emitLoadArgument(0);
                            builder.endInstallFrameLexicalAuthority();

                            builder.beginCreateCurrentLocalSlot();
                            builder.emitLoadArgument(0);
                            builder.emitLoadConstant("x");
                            builder.emitLoadConstant(initial);
                            builder.endCreateCurrentLocalSlot();

                            builder.beginStoreLocal(target);
                            builder.beginResolveCurrentFrameLocalWriteTarget(x);
                            builder.emitLoadArgument(0);
                            builder.emitLoadConstant("x");
                            builder.endResolveCurrentFrameLocalWriteTarget();
                            builder.endStoreLocal();

                            builder.beginReturn();
                            builder.beginAssignCurrentFrameLocal(x);
                            builder.emitLoadArgument(0);
                            builder.emitLoadLocal(target);
                            builder.emitLoadConstant("x");
                            builder.beginYield();
                            builder.emitLoadConstant(ProtosNullValue.INSTANCE);
                            builder.endYield();
                            builder.endAssignCurrentFrameLocal();
                            builder.endReturn();

                            builder.endRoot();
                        });
        return roots.getNode(0);
    }

    /*
     * PERF025 compact callee execution: a write whose current activation is
     * the root's own emits the root-level form of the same accessor path.
     */
    private static final String RESOLVE_ROOT = "ResolveRootFrameLocalWriteTarget";
    private static final String ASSIGN_ROOT = "AssignRootFrameLocal";

    private static boolean isCurrentResolve(String name) {
        return name.contains(RESOLVE_CURRENT) || name.contains(RESOLVE_ROOT);
    }

    private static boolean isCurrentAssign(String name) {
        return name.contains(ASSIGN_CURRENT) || name.contains(ASSIGN_ROOT);
    }

    private static void assertCurrentFrameLocalWritePath(BytecodeNode node) {
        List<String> names = instructionNames(node);
        assertTrue(
                names.stream().anyMatch(name -> isCurrentResolve(name)),
                () -> "Resolved current write did not select the accessor resolve path: " + names);
        assertTrue(
                names.stream().anyMatch(name -> isCurrentAssign(name)),
                () -> "Resolved current write did not select the accessor assign path: " + names);
        assertTrue(
                names.stream()
                        .noneMatch(name -> name.contains(RESOLVE_GENERIC) || name.contains(ASSIGN_GENERIC)),
                () -> "Resolved current write still emitted the generic String-keyed path: " + names);
    }

    private static void assertGenericLexicalWritePath(BytecodeNode node) {
        List<String> names = instructionNames(node);
        assertTrue(
                names.stream().anyMatch(name -> name.contains(RESOLVE_GENERIC)),
                () -> "ineligible write lost the generic resolve path: " + names);
        assertTrue(
                names.stream().anyMatch(name -> name.contains(ASSIGN_GENERIC)),
                () -> "ineligible write lost the generic assign path: " + names);
        assertTrue(
                names.stream()
                        .noneMatch(name -> isCurrentResolve(name) || isCurrentAssign(name)),
                () -> "ineligible write used the current-local accessor path: " + names);
    }

    private static List<String> instructionNames(BytecodeNode node) {
        return node.getInstructionsAsList().stream().map(Instruction::getName).toList();
    }

    private static BytecodeNode activationNode(ClosureUnderTest closure) {
        return closure.plan.bytecodeActivationRootForTesting().getBytecodeNode();
    }

    private record ClosureUnderTest(ProtosClosureValue closure, ProtosClosureExecutionPlan plan) {}

    private record Fixture(
            ProtosLanguage language,
            ProtosActivation module,
            ProtosObjectValue errorPrototype,
            ProtosObjectValue slotNotFoundPrototype) {
        ProtosActivation invocationOf(ProtosClosureValue closure) {
            return ProtosActivation.forClosureInvocation(
                    closure,
                    List.of(),
                    module.prelude().orElseThrow(),
                    module.actorModuleState(),
                    module.currentModuleKey().orElse(null),
                    module.executionDomain());
        }
    }

    private interface FixtureTest {
        void run(Fixture fixture) throws Exception;
    }

    private static void withFixture(FixtureTest test) throws Exception {
        try (Context context = Context.newBuilder(ProtosLanguage.ID).build()) {
            context.initialize(ProtosLanguage.ID);
            context.enter();
            try {
                ProtosStandardObjectProtocol.install();

                ProtosObjectValue contextPrototype = newObject();
                ProtosObjectValue bindings = new ProtosObjectValue(contextPrototype);
                ProtosObjectValue errorPrototype = newObject();
                ProtosObjectValue slotNotFoundPrototype = new ProtosObjectValue(errorPrototype);
                bindings.createLocalSlot("Context", contextPrototype);
                bindings.createLocalSlot("Error", errorPrototype);
                bindings.createLocalSlot("SlotNotFound", slotNotFoundPrototype);
                bindings.createLocalSlot("Array", newObject());
                bindings.freeze();

                test.run(
                        new Fixture(
                                LANGUAGE_REF.get(null),
                                new ProtosPrelude(bindings, contextPrototype).newModuleActivation(),
                                errorPrototype,
                                slotNotFoundPrototype));
            } finally {
                context.leave();
            }
        }
    }

    /** Runs a module source whose final expression is the Closure under test. */
    private static ClosureUnderTest closurePlan(Fixture fixture, String characters) {
        ProtosClosureValue closure =
                assertInstanceOf(ProtosClosureValue.class, run(fixture, characters));
        return new ClosureUnderTest(closure, closure.executionPlan().orElseThrow());
    }

    private static Object run(Fixture fixture, String characters) {
        return lower(fixture.language, characters).getCallTarget().call(fixture.module);
    }

    private static ProtosSemanticBytecodeRootNode lower(ProtosLanguage language, String characters) {
        Source source = Source.newBuilder(ProtosLanguage.ID, characters, "perf028-a.protos").build();
        return new CanonicalToBytecodeLowerer(language, source, ProtosExecutionMode.NORMAL).lowerRoot(canonicalize(characters));
    }

    private static CanonicalSequence canonicalize(String characters) {
        return (CanonicalSequence)
                new Canonicalizer().canonicalize(new ProtosParser(characters).parseProgram());
    }

    private static Object slot(ProtosActivation module, String name) {
        return module.context().readLocalSlot(name).orElseThrow();
    }

    private static Object parentOf(ProtosSignalException signal) {
        return signal.error().parent().orElseThrow();
    }

    private static ProtosObjectValue newObject() {
        return new ProtosObjectValue(ProtosObjectValue.rootObject());
    }

    private static long unwrapNumber(Object value) {
        if (value instanceof Long longValue) {
            return longValue;
        }
        if (value instanceof ProtosIntegerValue integerValue) {
            return integerValue.longValue();
        }
        throw new AssertionError("expected a Protos integer result, got: " + value);
    }
}
