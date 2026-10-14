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
import com.guillermomolina.protos.runtime.ProtosNullValue;
import com.guillermomolina.protos.runtime.ProtosObjectValue;
import com.guillermomolina.protos.runtime.ProtosPrelude;
import com.guillermomolina.protos.runtime.ProtosSignalException;
import com.guillermomolina.protos.semantic.Canonicalizer;
import com.guillermomolina.protos.semantic.ast.CanonicalAssign;
import com.guillermomolina.protos.semantic.ast.CanonicalClosure;
import com.guillermomolina.protos.semantic.ast.CanonicalCreate;
import com.guillermomolina.protos.semantic.ast.CanonicalLookup;
import com.guillermomolina.protos.semantic.ast.CanonicalSequence;
import com.oracle.truffle.api.TruffleLanguage.LanguageReference;
import com.oracle.truffle.api.source.Source;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;

/**
 * I068 Slice 5 focused evidence for captured/materialized lexical lowering.
 *
 * <p>This first proof establishes the metadata seam only: a Closure plan built
 * through the real enclosing-root lowering pipeline must retain the outer
 * whole-tree binding analysis rather than conservatively re-analyzing the
 * Closure in isolation and degrading an eligible capture to Dynamic.
 */
final class ProtosI068Slice5CapturedMaterializedLexicalLoweringTest {
    private static final LanguageReference<ProtosLanguage> LANGUAGE_REF =
            LanguageReference.create(ProtosLanguage.class);

    @Test
    void materializedClosurePlanRetainsProvenOuterBindingOwnerAndDepth()
            throws Exception {
        try (Context context = Context.newBuilder(ProtosLanguage.ID).build()) {
            context.initialize(ProtosLanguage.ID);
            context.enter();
            try {
                ProtosLanguage language = LANGUAGE_REF.get(null);
                ProtosActivation module = moduleActivation();

                ProtosObjectValue first =
                        new ProtosObjectValue(ProtosObjectValue.rootObject());
                ProtosObjectValue second =
                        new ProtosObjectValue(ProtosObjectValue.rootObject());
                module.context().createLocalSlot("seed", first);

                String characters =
                        "x: seed\n"
                                + "() => x";
                Source source =
                        Source.newBuilder(
                                        ProtosLanguage.ID,
                                        characters,
                                        "i068-slice5-captured-metadata.protos")
                                .build();

                CanonicalSequence sequence = canonicalize(characters);
                CanonicalClosure definition =
                        assertInstanceOf(
                                CanonicalClosure.class,
                                sequence.expressions().get(1));
                CanonicalLookup capturedRead =
                        assertInstanceOf(
                                CanonicalLookup.class,
                                definition.body().expressions().get(0));

                ProtosSemanticBytecodeRootNode root =
                        new CanonicalToBytecodeLowerer(language, source, ProtosExecutionMode.NORMAL)
                                .lowerRoot(sequence);

                ProtosClosureValue closure =
                        assertInstanceOf(
                                ProtosClosureValue.class,
                                root.getCallTarget().call(module));

                assertSame(definition, closure.definition());

                ProtosClosureExecutionPlan plan =
                        closure.executionPlan().orElseThrow();
                CanonicalBindingAnalysis analysis =
                        plan.bytecodeBindingAnalysisForTesting();

                CanonicalBindingResolution.CapturedResolved resolution =
                        assertInstanceOf(
                                CanonicalBindingResolution.CapturedResolved.class,
                                analysis.resolutionOf(capturedRead).orElseThrow());

                assertEquals(1, resolution.lexicalDepth());
                assertSame(
                        analysis.topScope(),
                        resolution.identity().owner());

                java.util.List<String> instructionNames =
                        plan.bytecodeActivationRootForTesting()
                                .getBytecodeNode()
                                .getInstructionsAsList()
                                .stream()
                                .map(com.oracle.truffle.api.bytecode.Instruction::getName)
                                .toList();

                /*
                 * PERF013 Slice B1: the module root and this Closure are
                 * lowered into the same shared BytecodeRootNodes group (Slice
                 * A), so this proven capture now takes the direct
                 * MaterializedLocalAccessor fast path instead of the runtime
                 * frame-lexical-binding-authority path.
                 */
                assertTrue(
                        instructionNames.stream()
                                .anyMatch(
                                        name ->
                                                name.contains(
                                                        "SelectCapturedMaterializedOwnerFrame")),
                        () ->
                                "captured read did not lower to SelectCapturedMaterializedOwnerFrame: "
                                        + instructionNames);

                /*
                 * The outer root has already returned here. Mutating x through
                 * the escaped execution context must update the same retained
                 * frame-backed authority the captured read will observe.
                 */
                module.context().assignLocalSlot("x", second);

                ProtosActivation invocation =
                        ProtosActivation.forClosureInvocation(
                                closure,
                                java.util.List.of(),
                                module.prelude().orElseThrow(),
                                module.actorModuleState(),
                                module.currentModuleKey().orElse(null),
                                module.executionDomain());

                Object capturedResult =
                        plan.executeBytecodeActivationForTesting(invocation);

                assertSame(second, capturedResult);
            } finally {
                context.leave();
            }
        }
    }


    @Test
    void provenCapturedWriteUpdatesSameOuterFrameAuthority()
            throws Exception {
        try (Context context = Context.newBuilder(ProtosLanguage.ID).build()) {
            context.initialize(ProtosLanguage.ID);
            context.enter();
            try {
                ProtosLanguage language = LANGUAGE_REF.get(null);
                ProtosActivation module = moduleActivation();

                ProtosObjectValue first =
                        new ProtosObjectValue(ProtosObjectValue.rootObject());
                ProtosObjectValue replacement =
                        new ProtosObjectValue(ProtosObjectValue.rootObject());

                module.context().createLocalSlot("seed", first);
                module.context().createLocalSlot("replacement", replacement);

                String characters =
                        "x: seed\n"
                                + "() => { x = replacement\n"
                                + "x }";
                Source source =
                        Source.newBuilder(
                                        ProtosLanguage.ID,
                                        characters,
                                        "i068-slice5-captured-write.protos")
                                .build();

                CanonicalSequence sequence = canonicalize(characters);
                CanonicalClosure definition =
                        assertInstanceOf(
                                CanonicalClosure.class,
                                sequence.expressions().get(1));
                CanonicalAssign capturedWrite =
                        assertInstanceOf(
                                CanonicalAssign.class,
                                definition.body().expressions().get(0));

                ProtosSemanticBytecodeRootNode root =
                        new CanonicalToBytecodeLowerer(language, source, ProtosExecutionMode.NORMAL)
                                .lowerRoot(sequence);

                ProtosClosureValue closure =
                        assertInstanceOf(
                                ProtosClosureValue.class,
                                root.getCallTarget().call(module));
                ProtosClosureExecutionPlan plan =
                        closure.executionPlan().orElseThrow();

                CanonicalBindingResolution.CapturedResolved resolution =
                        assertInstanceOf(
                                CanonicalBindingResolution.CapturedResolved.class,
                                plan.bytecodeBindingAnalysisForTesting()
                                        .resolutionOf(capturedWrite)
                                        .orElseThrow());
                assertEquals(1, resolution.lexicalDepth());

                java.util.List<String> instructionNames =
                        plan.bytecodeActivationRootForTesting()
                                .getBytecodeNode()
                                .getInstructionsAsList()
                                .stream()
                                .map(com.oracle.truffle.api.bytecode.Instruction::getName)
                                .toList();

                /*
                 * PERF013 Slice B2: this closure and its lexical owner (the
                 * module root) share the same physical BytecodeRootNodes
                 * group, so this same-group proven captured write now
                 * lowers to the MaterializedLocalAccessor-based fast path
                 * instead of the runtime-authority path exercised directly,
                 * at the operation level, by the other tests in this file.
                 */
                int resolveIndex =
                        instructionIndexContaining(
                                instructionNames,
                                "ResolveCapturedMaterializedWritableLexicalTarget");
                int assignIndex =
                        instructionIndexContaining(
                                instructionNames,
                                "AssignCapturedMaterializedLocal");

                assertTrue(
                        resolveIndex >= 0,
                        () ->
                                "captured write did not lower its destination "
                                        + "to ResolveCapturedMaterializedWritableLexicalTarget: "
                                        + instructionNames);
                assertTrue(
                        assignIndex > resolveIndex,
                        () ->
                                "captured write did not retain resolve-before-assign order: "
                                        + instructionNames);

                ProtosActivation invocation =
                        ProtosActivation.forClosureInvocation(
                                closure,
                                java.util.List.of(),
                                module.prelude().orElseThrow(),
                                module.actorModuleState(),
                                module.currentModuleKey().orElse(null),
                                module.executionDomain());

                Object result =
                        plan.executeBytecodeActivationForTesting(invocation);

                assertSame(replacement, result);
                assertSame(
                        replacement,
                        module.context()
                                .readLocalSlot("x")
                                .orElseThrow());
            } finally {
                context.leave();
            }
        }
    }

    @Test
    void capturedWriteDestinationRemainsFixedWhenNearerBindingAppearsAfterResolution()
            throws Exception {
        try (Context context = Context.newBuilder(ProtosLanguage.ID).build()) {
            context.initialize(ProtosLanguage.ID);
            context.enter();
            try {
                ProtosLanguage language = LANGUAGE_REF.get(null);
                ProtosActivation module = moduleActivation();

                ProtosObjectValue original =
                        new ProtosObjectValue(ProtosObjectValue.rootObject());
                ProtosObjectValue replacement =
                        new ProtosObjectValue(ProtosObjectValue.rootObject());
                ProtosObjectValue nearer =
                        new ProtosObjectValue(ProtosObjectValue.rootObject());

                module.context().createLocalSlot("seed", original);
                module.context().createLocalSlot("replacement", replacement);

                String characters =
                        "x: seed\n"
                                + "() => { x = replacement }";
                Source source =
                        Source.newBuilder(
                                        ProtosLanguage.ID,
                                        characters,
                                        "i068-slice5-write-destination-before-rhs.protos")
                                .build();

                CanonicalSequence sequence = canonicalize(characters);
                CanonicalClosure definition =
                        assertInstanceOf(
                                CanonicalClosure.class,
                                sequence.expressions().get(1));
                CanonicalAssign capturedWrite =
                        assertInstanceOf(
                                CanonicalAssign.class,
                                definition.body().expressions().get(0));

                ProtosSemanticBytecodeRootNode root =
                        new CanonicalToBytecodeLowerer(language, source, ProtosExecutionMode.NORMAL)
                                .lowerRoot(sequence);

                ProtosClosureValue closure =
                        assertInstanceOf(
                                ProtosClosureValue.class,
                                root.getCallTarget().call(module));
                ProtosClosureExecutionPlan plan =
                        closure.executionPlan().orElseThrow();

                CanonicalBindingResolution.CapturedResolved resolution =
                        assertInstanceOf(
                                CanonicalBindingResolution.CapturedResolved.class,
                                plan.bytecodeBindingAnalysisForTesting()
                                        .resolutionOf(capturedWrite)
                                        .orElseThrow());

                int ordinal = frameOrdinal(resolution.identity());

                ProtosActivation invocation =
                        ProtosActivation.forClosureInvocation(
                                closure,
                                java.util.List.of(),
                                module.prelude().orElseThrow(),
                                module.actorModuleState(),
                                module.currentModuleKey().orElse(null),
                                module.executionDomain());

                /*
                 * This is the exact semantic ordering the generated assignment
                 * must preserve:
                 *
                 *   1. resolve destination
                 *   2. evaluate RHS
                 *   3. write to the destination from step 1
                 *
                 * Simulate an RHS effect that creates the same name in the
                 * nearer current context after destination resolution.
                 */
                ProtosBytecodeRootNode.CapturedLexicalWriteTarget destination =
                        ProtosSemanticBytecodeRootNode
                                .ResolveCapturedWritableLexicalTarget
                                .perform(
                                        ordinal,
                                        invocation,
                                        "x",
                                        resolution.lexicalDepth());

                invocation.context().createLocalSlot("x", nearer);

                Object result =
                        ProtosBytecodeRootNode.AssignCapturedFrameLocal.perform(
                                ordinal,
                                invocation,
                                destination,
                                "x",
                                replacement);

                assertSame(replacement, result);

                /*
                 * The newly-created nearer x must remain untouched: assignment
                 * was already committed to the outer destination.
                 */
                assertSame(
                        nearer,
                        invocation.context()
                                .readLocalSlot("x")
                                .orElseThrow());

                /*
                 * And the original captured outer authority receives the write.
                 */
                assertSame(
                        replacement,
                        module.context()
                                .readLocalSlot("x")
                                .orElseThrow());
            } finally {
                context.leave();
            }
        }
    }


    @Test
    void lateNearerCreationRetargetsCapturedRead()
            throws Exception {
        try (Context context = Context.newBuilder(ProtosLanguage.ID).build()) {
            context.initialize(ProtosLanguage.ID);
            context.enter();
            try {
                ProtosLanguage language = LANGUAGE_REF.get(null);
                ProtosActivation module = moduleActivation();

                ProtosObjectValue outer =
                        new ProtosObjectValue(ProtosObjectValue.rootObject());
                ProtosObjectValue nearer =
                        new ProtosObjectValue(ProtosObjectValue.rootObject());

                module.context().createLocalSlot("seed", outer);

                String characters =
                        "x: seed\n"
                                + "() => x";
                Source source =
                        Source.newBuilder(
                                        ProtosLanguage.ID,
                                        characters,
                                        "i068-slice5-late-nearer-read.protos")
                                .build();

                CanonicalSequence sequence = canonicalize(characters);
                CanonicalClosure definition =
                        assertInstanceOf(
                                CanonicalClosure.class,
                                sequence.expressions().get(1));

                ProtosSemanticBytecodeRootNode root =
                        new CanonicalToBytecodeLowerer(language, source, ProtosExecutionMode.NORMAL)
                                .lowerRoot(sequence);

                ProtosClosureValue closure =
                        assertInstanceOf(
                                ProtosClosureValue.class,
                                root.getCallTarget().call(module));
                ProtosClosureExecutionPlan plan =
                        closure.executionPlan().orElseThrow();

                ProtosActivation invocation =
                        ProtosActivation.forClosureInvocation(
                                closure,
                                java.util.List.of(),
                                module.prelude().orElseThrow(),
                                module.actorModuleState(),
                                module.currentModuleKey().orElse(null),
                                module.executionDomain());

                /*
                 * Static analysis proved the outer x before this dynamic
                 * creation existed. D179 C0 still requires this later nearer
                 * PRESENT binding to retarget subsequent lexical lookup.
                 */
                invocation.context().createLocalSlot("x", nearer);

                Object result =
                        plan.executeBytecodeActivationForTesting(invocation);

                assertSame(nearer, result);
                assertSame(
                        outer,
                        module.context()
                                .readLocalSlot("x")
                                .orElseThrow());
            } finally {
                context.leave();
            }
        }
    }

    @Test
    void lateNearerCreationRetargetsCapturedWrite()
            throws Exception {
        try (Context context = Context.newBuilder(ProtosLanguage.ID).build()) {
            context.initialize(ProtosLanguage.ID);
            context.enter();
            try {
                ProtosLanguage language = LANGUAGE_REF.get(null);
                ProtosActivation module = moduleActivation();

                ProtosObjectValue outer =
                        new ProtosObjectValue(ProtosObjectValue.rootObject());
                ProtosObjectValue nearerBefore =
                        new ProtosObjectValue(ProtosObjectValue.rootObject());
                ProtosObjectValue replacement =
                        new ProtosObjectValue(ProtosObjectValue.rootObject());

                module.context().createLocalSlot("seed", outer);
                module.context().createLocalSlot(
                        "replacement",
                        replacement);

                String characters =
                        "x: seed\n"
                                + "() => { x = replacement\n"
                                + "x }";
                Source source =
                        Source.newBuilder(
                                        ProtosLanguage.ID,
                                        characters,
                                        "i068-slice5-late-nearer-write.protos")
                                .build();

                CanonicalSequence sequence = canonicalize(characters);
                CanonicalClosure definition =
                        assertInstanceOf(
                                CanonicalClosure.class,
                                sequence.expressions().get(1));

                ProtosSemanticBytecodeRootNode root =
                        new CanonicalToBytecodeLowerer(language, source, ProtosExecutionMode.NORMAL)
                                .lowerRoot(sequence);

                ProtosClosureValue closure =
                        assertInstanceOf(
                                ProtosClosureValue.class,
                                root.getCallTarget().call(module));
                ProtosClosureExecutionPlan plan =
                        closure.executionPlan().orElseThrow();

                ProtosActivation invocation =
                        ProtosActivation.forClosureInvocation(
                                closure,
                                java.util.List.of(),
                                module.prelude().orElseThrow(),
                                module.actorModuleState(),
                                module.currentModuleKey().orElse(null),
                                module.executionDomain());

                /*
                 * This binding appears after static analysis/materialization
                 * but before assignment destination resolution.
                 */
                invocation.context().createLocalSlot(
                        "x",
                        nearerBefore);

                Object result =
                        plan.executeBytecodeActivationForTesting(invocation);

                assertSame(replacement, result);

                /*
                 * The write must retarget to the now-nearer current context.
                 */
                assertSame(
                        replacement,
                        invocation.context()
                                .readLocalSlot("x")
                                .orElseThrow());

                /*
                 * The statically proven outer owner must remain untouched.
                 */
                assertSame(
                        outer,
                        module.context()
                                .readLocalSlot("x")
                                .orElseThrow());
            } finally {
                context.leave();
            }
        }
    }


    @Test
    void provenCaptureAtDepthTwoUsesOuterFrameAuthority()
            throws Exception {
        try (Context context = Context.newBuilder(ProtosLanguage.ID).build()) {
            context.initialize(ProtosLanguage.ID);
            context.enter();
            try {
                ProtosLanguage language = LANGUAGE_REF.get(null);
                ProtosActivation module = moduleActivation();

                ProtosObjectValue token =
                        new ProtosObjectValue(ProtosObjectValue.rootObject());
                module.context().createLocalSlot("seed", token);

                String characters =
                        "x: seed\n"
                                + "() => { () => x }";
                Source source =
                        Source.newBuilder(
                                        ProtosLanguage.ID,
                                        characters,
                                        "i068-slice5-depth-two.protos")
                                .build();

                CanonicalSequence sequence = canonicalize(characters);
                CanonicalClosure outerDefinition =
                        assertInstanceOf(
                                CanonicalClosure.class,
                                sequence.expressions().get(1));
                CanonicalClosure innerDefinition =
                        assertInstanceOf(
                                CanonicalClosure.class,
                                outerDefinition.body().expressions().get(0));
                CanonicalLookup capturedRead =
                        assertInstanceOf(
                                CanonicalLookup.class,
                                innerDefinition.body().expressions().get(0));

                ProtosSemanticBytecodeRootNode root =
                        new CanonicalToBytecodeLowerer(language, source, ProtosExecutionMode.NORMAL)
                                .lowerRoot(sequence);

                ProtosClosureValue outerClosure =
                        assertInstanceOf(
                                ProtosClosureValue.class,
                                root.getCallTarget().call(module));
                ProtosClosureExecutionPlan outerPlan =
                        outerClosure.executionPlan().orElseThrow();

                ProtosActivation outerInvocation =
                        ProtosActivation.forClosureInvocation(
                                outerClosure,
                                java.util.List.of(),
                                module.prelude().orElseThrow(),
                                module.actorModuleState(),
                                module.currentModuleKey().orElse(null),
                                module.executionDomain());

                ProtosClosureValue innerClosure =
                        assertInstanceOf(
                                ProtosClosureValue.class,
                                outerPlan.executeBytecodeActivationForTesting(
                                        outerInvocation));
                ProtosClosureExecutionPlan innerPlan =
                        innerClosure.executionPlan().orElseThrow();

                CanonicalBindingResolution.CapturedResolved resolution =
                        assertInstanceOf(
                                CanonicalBindingResolution.CapturedResolved.class,
                                innerPlan.bytecodeBindingAnalysisForTesting()
                                        .resolutionOf(capturedRead)
                                        .orElseThrow());

                assertEquals(2, resolution.lexicalDepth());

                java.util.List<String> instructionNames =
                        innerPlan.bytecodeActivationRootForTesting()
                                .getBytecodeNode()
                                .getInstructionsAsList()
                                .stream()
                                .map(com.oracle.truffle.api.bytecode.Instruction::getName)
                                .toList();

                /*
                 * PERF013 Slice B1: module root, outer Closure, and inner
                 * Closure all share one physical BytecodeRootNodes group
                 * (Slice A), so this depth-two proven capture also takes the
                 * direct MaterializedLocalAccessor fast path.
                 */
                assertTrue(
                        instructionIndexContaining(
                                        instructionNames,
                                        "SelectCapturedMaterializedOwnerFrame")
                                >= 0,
                        () ->
                                "depth-two capture did not use "
                                        + "SelectCapturedMaterializedOwnerFrame: "
                                        + instructionNames);

                /*
                 * The outer Closure activation has already returned. The inner
                 * Closure must still address module x through captured depth 2.
                 */
                ProtosActivation innerInvocation =
                        ProtosActivation.forClosureInvocation(
                                innerClosure,
                                java.util.List.of(),
                                module.prelude().orElseThrow(),
                                module.actorModuleState(),
                                module.currentModuleKey().orElse(null),
                                module.executionDomain());

                assertSame(
                        token,
                        innerPlan.executeBytecodeActivationForTesting(
                                innerInvocation));
            } finally {
                context.leave();
            }
        }
    }

    @Test
    void presentNullCapturedBindingRemainsDistinctFromAbsent()
            throws Exception {
        try (Context context = Context.newBuilder(ProtosLanguage.ID).build()) {
            context.initialize(ProtosLanguage.ID);
            context.enter();
            try {
                ProtosLanguage language = LANGUAGE_REF.get(null);
                ProtosActivation module = moduleActivation();

                String characters =
                        "x: null\n"
                                + "() => x";
                Source source =
                        Source.newBuilder(
                                        ProtosLanguage.ID,
                                        characters,
                                        "i068-slice5-present-null.protos")
                                .build();

                CanonicalSequence sequence = canonicalize(characters);
                ProtosSemanticBytecodeRootNode root =
                        new CanonicalToBytecodeLowerer(language, source, ProtosExecutionMode.NORMAL)
                                .lowerRoot(sequence);

                ProtosClosureValue closure =
                        assertInstanceOf(
                                ProtosClosureValue.class,
                                root.getCallTarget().call(module));
                ProtosClosureExecutionPlan plan =
                        closure.executionPlan().orElseThrow();

                assertTrue(module.context().hasLocalSlot("x"));
                assertSame(
                        ProtosNullValue.INSTANCE,
                        module.context()
                                .readLocalSlot("x")
                                .orElseThrow());

                ProtosActivation invocation =
                        ProtosActivation.forClosureInvocation(
                                closure,
                                java.util.List.of(),
                                module.prelude().orElseThrow(),
                                module.actorModuleState(),
                                module.currentModuleKey().orElse(null),
                                module.executionDomain());

                assertSame(
                        ProtosNullValue.INSTANCE,
                        plan.executeBytecodeActivationForTesting(invocation));
            } finally {
                context.leave();
            }
        }
    }

    @Test
    void nearerDeclaredButAbsentCandidateFallsBackToOuterPresentBinding()
            throws Exception {
        try (Context context = Context.newBuilder(ProtosLanguage.ID).build()) {
            context.initialize(ProtosLanguage.ID);
            context.enter();
            try {
                ProtosLanguage language = LANGUAGE_REF.get(null);
                ProtosActivation module = moduleActivation();

                ProtosObjectValue outer =
                        new ProtosObjectValue(ProtosObjectValue.rootObject());
                module.context().createLocalSlot("seed", outer);

                String characters =
                        "x: seed\n"
                                + "() => {\n"
                                + "  y: x\n"
                                + "  x: null\n"
                                + "  y\n"
                                + "}";
                Source source =
                        Source.newBuilder(
                                        ProtosLanguage.ID,
                                        characters,
                                        "i068-slice5-candidate-absent.protos")
                                .build();

                CanonicalSequence sequence = canonicalize(characters);
                CanonicalClosure definition =
                        assertInstanceOf(
                                CanonicalClosure.class,
                                sequence.expressions().get(1));
                CanonicalCreate createY =
                        assertInstanceOf(
                                CanonicalCreate.class,
                                definition.body().expressions().get(0));
                CanonicalLookup candidateRead =
                        assertInstanceOf(
                                CanonicalLookup.class,
                                createY.value());

                ProtosSemanticBytecodeRootNode root =
                        new CanonicalToBytecodeLowerer(language, source, ProtosExecutionMode.NORMAL)
                                .lowerRoot(sequence);

                ProtosClosureValue closure =
                        assertInstanceOf(
                                ProtosClosureValue.class,
                                root.getCallTarget().call(module));
                ProtosClosureExecutionPlan plan =
                        closure.executionPlan().orElseThrow();

                CanonicalBindingResolution.Candidate resolution =
                        assertInstanceOf(
                                CanonicalBindingResolution.Candidate.class,
                                plan.bytecodeBindingAnalysisForTesting()
                                        .resolutionOf(candidateRead)
                                        .orElseThrow());

                assertEquals(0, resolution.lexicalDepth());

                java.util.List<String> instructionNames =
                        plan.bytecodeActivationRootForTesting()
                                .getBytecodeNode()
                                .getInstructionsAsList()
                                .stream()
                                .map(com.oracle.truffle.api.bytecode.Instruction::getName)
                                .toList();

                assertTrue(
                        instructionIndexContaining(
                                        instructionNames,
                                        "ReadCapturedFrameLocal")
                                < 0,
                        () ->
                                "Candidate lookup incorrectly used captured "
                                        + "direct path: "
                                        + instructionNames);

                ProtosActivation invocation =
                        ProtosActivation.forClosureInvocation(
                                closure,
                                java.util.List.of(),
                                module.prelude().orElseThrow(),
                                module.actorModuleState(),
                                module.currentModuleKey().orElse(null),
                                module.executionDomain());

                Object result =
                        plan.executeBytecodeActivationForTesting(invocation);

                assertSame(outer, result);
                assertTrue(invocation.context().hasLocalSlot("x"));
                assertSame(
                        ProtosNullValue.INSTANCE,
                        invocation.context()
                                .readLocalSlot("x")
                                .orElseThrow());
            } finally {
                context.leave();
            }
        }
    }


    @Test
    void dynamicLookupPreservesReceiverFallbackAndDoesNotUseCapturedFastPath()
            throws Exception {
        try (Context context = Context.newBuilder(ProtosLanguage.ID).build()) {
            context.initialize(ProtosLanguage.ID);
            context.enter();
            try {
                ProtosLanguage language = LANGUAGE_REF.get(null);
                ProtosActivation module = moduleActivation();

                String characters = "() => receiverOnly";
                Source source =
                        Source.newBuilder(
                                        ProtosLanguage.ID,
                                        characters,
                                        "i068-slice5-dynamic-receiver.protos")
                                .build();

                CanonicalSequence sequence = canonicalize(characters);
                CanonicalClosure definition =
                        assertInstanceOf(
                                CanonicalClosure.class,
                                sequence.expressions().get(0));
                CanonicalLookup lookup =
                        assertInstanceOf(
                                CanonicalLookup.class,
                                definition.body().expressions().get(0));

                ProtosSemanticBytecodeRootNode root =
                        new CanonicalToBytecodeLowerer(language, source, ProtosExecutionMode.NORMAL)
                                .lowerRoot(sequence);

                ProtosClosureValue closure =
                        assertInstanceOf(
                                ProtosClosureValue.class,
                                root.getCallTarget().call(module));
                ProtosClosureExecutionPlan plan =
                        closure.executionPlan().orElseThrow();

                assertInstanceOf(
                        CanonicalBindingResolution.Dynamic.class,
                        plan.bytecodeBindingAnalysisForTesting()
                                .resolutionOf(lookup)
                                .orElseThrow());

                java.util.List<String> instructionNames =
                        plan.bytecodeActivationRootForTesting()
                                .getBytecodeNode()
                                .getInstructionsAsList()
                                .stream()
                                .map(com.oracle.truffle.api.bytecode.Instruction::getName)
                                .toList();

                assertTrue(
                        instructionIndexContaining(
                                        instructionNames,
                                        "ReadCapturedFrameLocal")
                                < 0,
                        () ->
                                "Dynamic lookup incorrectly used captured "
                                        + "frame-native path: "
                                        + instructionNames);

                ProtosObjectValue receiver =
                        new ProtosObjectValue(
                                ProtosObjectValue.rootObject());
                ProtosObjectValue token =
                        new ProtosObjectValue(
                                ProtosObjectValue.rootObject());
                receiver.createLocalSlot("receiverOnly", token);

                ProtosClosureValue bound =
                        closure.bindMethod(receiver, receiver);

                ProtosActivation invocation =
                        ProtosActivation.forClosureInvocation(
                                bound,
                                java.util.List.of(),
                                module.prelude().orElseThrow(),
                                module.actorModuleState(),
                                module.currentModuleKey().orElse(null),
                                module.executionDomain());

                assertSame(
                        token,
                        bound.executionPlan()
                                .orElseThrow()
                                .executeBytecodeActivationForTesting(
                                        invocation));
            } finally {
                context.leave();
            }
        }
    }

    @Test
    void closureCreatedInObjectBodyCapturesEnclosingLexicalContextNotObject()
            throws Exception {
        try (Context context = Context.newBuilder(ProtosLanguage.ID).build()) {
            context.initialize(ProtosLanguage.ID);
            context.enter();
            try {
                ProtosLanguage language = LANGUAGE_REF.get(null);
                ProtosActivation module = moduleActivation();

                ProtosObjectValue lexical =
                        new ProtosObjectValue(
                                ProtosObjectValue.rootObject());
                ProtosObjectValue objectLocal =
                        new ProtosObjectValue(
                                ProtosObjectValue.rootObject());

                module.context().createLocalSlot("seed", lexical);
                module.context().createLocalSlot(
                        "objectValue",
                        objectLocal);

                String characters =
                        "x: seed\n"
                                + "{\n"
                                + "  x: objectValue\n"
                                + "  method: () => x\n"
                                + "}";
                Source source =
                        Source.newBuilder(
                                        ProtosLanguage.ID,
                                        characters,
                                        "i068-slice5-object-boundary.protos")
                                .build();

                CanonicalSequence sequence = canonicalize(characters);
                ProtosSemanticBytecodeRootNode root =
                        new CanonicalToBytecodeLowerer(language, source, ProtosExecutionMode.NORMAL)
                                .lowerRoot(sequence);

                ProtosObjectValue object =
                        assertInstanceOf(
                                ProtosObjectValue.class,
                                root.getCallTarget().call(module));

                assertSame(
                        objectLocal,
                        object.readLocalSlot("x").orElseThrow());

                ProtosClosureValue method =
                        assertInstanceOf(
                                ProtosClosureValue.class,
                                object.readLocalSlot("method")
                                        .orElseThrow());
                ProtosClosureExecutionPlan plan =
                        method.executionPlan().orElseThrow();

                CanonicalLookup capturedRead =
                        assertInstanceOf(
                                CanonicalLookup.class,
                                method.definition()
                                        .body()
                                        .expressions()
                                        .get(0));

                CanonicalBindingResolution.CapturedResolved resolution =
                        assertInstanceOf(
                                CanonicalBindingResolution.CapturedResolved.class,
                                plan.bytecodeBindingAnalysisForTesting()
                                        .resolutionOf(capturedRead)
                                        .orElseThrow());

                assertEquals(1, resolution.lexicalDepth());

                ProtosClosureValue bound =
                        method.bindMethod(object, object);
                ProtosActivation invocation =
                        ProtosActivation.forClosureInvocation(
                                bound,
                                java.util.List.of(),
                                module.prelude().orElseThrow(),
                                module.actorModuleState(),
                                module.currentModuleKey().orElse(null),
                                module.executionDomain());

                /*
                 * If object construction had incorrectly entered the lexical
                 * capture chain, this would return objectLocal instead.
                 */
                assertSame(
                        lexical,
                        bound.executionPlan()
                                .orElseThrow()
                                .executeBytecodeActivationForTesting(
                                        invocation));
            } finally {
                context.leave();
            }
        }
    }

    @Test
    void reparsedClosureRebuildPreservesCapturedResolutionAndSourceMaterialization()
            throws Exception {
        try (Context context = Context.newBuilder(ProtosLanguage.ID).build()) {
            context.initialize(ProtosLanguage.ID);
            context.enter();
            try {
                ProtosLanguage language = LANGUAGE_REF.get(null);
                ProtosActivation module = moduleActivation();

                ProtosObjectValue token =
                        new ProtosObjectValue(
                                ProtosObjectValue.rootObject());
                module.context().createLocalSlot("seed", token);

                String characters =
                        "x: seed\n"
                                + "() => x";
                Source source =
                        Source.newBuilder(
                                        ProtosLanguage.ID,
                                        characters,
                                        "i068-slice5-reparse.protos")
                                .build();

                CanonicalSequence original = canonicalize(characters);
                ProtosSemanticBytecodeRootNode root =
                        new CanonicalToBytecodeLowerer(language, source, ProtosExecutionMode.NORMAL)
                                .lowerRoot(original);

                ProtosClosureValue closure =
                        assertInstanceOf(
                                ProtosClosureValue.class,
                                root.getCallTarget().call(module));
                ProtosClosureExecutionPlan template =
                        closure.executionPlan().orElseThrow();

                /*
                 * New parser/canonicalizer run: every AST node has a new
                 * identity even though source and semantics are identical.
                 */
                CanonicalSequence reparsed = canonicalize(characters);
                CanonicalClosure reparsedDefinition =
                        assertInstanceOf(
                                CanonicalClosure.class,
                                reparsed.expressions().get(1));
                CanonicalLookup reparsedRead =
                        assertInstanceOf(
                                CanonicalLookup.class,
                                reparsedDefinition.body()
                                        .expressions()
                                        .get(0));

                ProtosClosureExecutionPlan rebuilt =
                        template.rebuildBytecodeForLanguage(
                                reparsedDefinition,
                                language, ProtosExecutionMode.NORMAL);

                CanonicalBindingResolution.CapturedResolved resolution =
                        assertInstanceOf(
                                CanonicalBindingResolution.CapturedResolved.class,
                                rebuilt.bytecodeBindingAnalysisForTesting()
                                        .resolutionOf(reparsedRead)
                                        .orElseThrow());

                assertEquals(1, resolution.lexicalDepth());

                java.util.List<String> instructionNames =
                        rebuilt.bytecodeActivationRootForTesting()
                                .getBytecodeNode()
                                .getInstructionsAsList()
                                .stream()
                                .map(com.oracle.truffle.api.bytecode.Instruction::getName)
                                .toList();

                assertTrue(
                        instructionIndexContaining(
                                        instructionNames,
                                        "ReadCapturedFrameLocal")
                                >= 0,
                        () ->
                                "reparsed captured lookup lost its "
                                        + "frame-native lowering: "
                                        + instructionNames);

                com.oracle.truffle.api.source.SourceSection section =
                        rebuilt.bytecodeActivationRootForTesting()
                                .ensureSourceSection();

                assertSame(source, section.getSource());
                assertEquals(
                        reparsedDefinition.body().span().startOffset(),
                        section.getCharIndex());
                assertEquals(
                        reparsedDefinition.body().span().length(),
                        section.getCharLength());

                /*
                 * Execute the rebuilt projection with the original semantic
                 * Closure's captured lexical contexts. No frame object is
                 * stored in the Closure itself.
                 */
                ProtosActivation invocation =
                        ProtosActivation.forClosureInvocation(
                                closure,
                                java.util.List.of(),
                                module.prelude().orElseThrow(),
                                module.actorModuleState(),
                                module.currentModuleKey().orElse(null),
                                module.executionDomain());

                assertSame(
                        token,
                        rebuilt.executeBytecodeActivationForTesting(
                                invocation));
            } finally {
                context.leave();
            }
        }
    }

    @Test
    void capturedWriteDestinationRemovedDuringRhsDoesNotRetarget()
            throws Exception {
        try (Context context = Context.newBuilder(ProtosLanguage.ID).build()) {
            context.initialize(ProtosLanguage.ID);
            context.enter();
            try {
                ProtosLanguage language = LANGUAGE_REF.get(null);
                ProtosActivation module = moduleActivation();

                ProtosObjectValue original =
                        new ProtosObjectValue(ProtosObjectValue.rootObject());
                ProtosObjectValue replacement =
                        new ProtosObjectValue(ProtosObjectValue.rootObject());
                ProtosObjectValue nearer =
                        new ProtosObjectValue(ProtosObjectValue.rootObject());

                module.context().createLocalSlot("seed", original);
                module.context().createLocalSlot("replacement", replacement);

                String characters =
                        "x: seed\n"
                                + "() => { x = replacement }";
                Source source =
                        Source.newBuilder(
                                        ProtosLanguage.ID,
                                        characters,
                                        "i071-write-destination-removed-during-rhs.protos")
                                .build();

                CanonicalSequence sequence = canonicalize(characters);
                CanonicalClosure definition =
                        assertInstanceOf(
                                CanonicalClosure.class,
                                sequence.expressions().get(1));
                CanonicalAssign capturedWrite =
                        assertInstanceOf(
                                CanonicalAssign.class,
                                definition.body().expressions().get(0));

                ProtosSemanticBytecodeRootNode root =
                        new CanonicalToBytecodeLowerer(language, source, ProtosExecutionMode.NORMAL)
                                .lowerRoot(sequence);

                ProtosClosureValue closure =
                        assertInstanceOf(
                                ProtosClosureValue.class,
                                root.getCallTarget().call(module));
                ProtosClosureExecutionPlan plan =
                        closure.executionPlan().orElseThrow();

                CanonicalBindingResolution.CapturedResolved resolution =
                        assertInstanceOf(
                                CanonicalBindingResolution.CapturedResolved.class,
                                plan.bytecodeBindingAnalysisForTesting()
                                        .resolutionOf(capturedWrite)
                                        .orElseThrow());

                int ordinal = frameOrdinal(resolution.identity());

                ProtosActivation invocation =
                        ProtosActivation.forClosureInvocation(
                                closure,
                                java.util.List.of(),
                                module.prelude().orElseThrow(),
                                module.actorModuleState(),
                                module.currentModuleKey().orElse(null),
                                module.executionDomain());

                ProtosBytecodeRootNode.CapturedLexicalWriteTarget destination =
                        ProtosSemanticBytecodeRootNode
                                .ResolveCapturedWritableLexicalTarget
                                .perform(
                                        ordinal,
                                        invocation,
                                        "x",
                                        resolution.lexicalDepth());

                /*
                 * Simulate RHS effects after destination selection:
                 * remove the selected captured destination and create a new
                 * nearer binding that would win if assignment were incorrectly
                 * resolved again after RHS evaluation.
                 */
                module.context().removeLocalSlot("x");
                invocation.context().createLocalSlot("x", nearer);

                assertThrows(
                        ProtosSignalException.class,
                        () ->
                                ProtosSemanticBytecodeRootNode
                                        .AssignCapturedFrameLocal
                                        .perform(
                                                ordinal,
                                                invocation,
                                                destination,
                                                "x",
                                                replacement));

                assertFalse(module.context().hasLocalSlot("x"));
                assertSame(
                        nearer,
                        invocation.context()
                                .readLocalSlot("x")
                                .orElseThrow());
            } finally {
                context.leave();
            }
        }
    }

    private static int instructionIndexContaining(
            java.util.List<String> instructionNames,
            String fragment) {
        for (int index = 0; index < instructionNames.size(); index++) {
            if (instructionNames.get(index).contains(fragment)) {
                return index;
            }
        }
        return -1;
    }

    private static int frameOrdinal(
            CanonicalBindingIdentity identity) {
        int ordinal = 0;
        for (String name : identity.owner().declaredNames()) {
            if (name.equals(identity.name())) {
                return ordinal;
            }
            ordinal++;
        }
        throw new AssertionError(
                "binding owner does not declare "
                        + identity.name());
    }

    private static CanonicalSequence canonicalize(String characters) {
        return (CanonicalSequence)
                new Canonicalizer()
                        .canonicalize(
                                new ProtosParser(characters)
                                        .parseProgram());
    }

    private static ProtosActivation moduleActivation() {
        ProtosStandardObjectProtocol.install();

        ProtosObjectValue contextPrototype =
                new ProtosObjectValue(ProtosObjectValue.rootObject());
        ProtosObjectValue bindings =
                new ProtosObjectValue(contextPrototype);
        bindings.createLocalSlot("Context", contextPrototype);
        bindings.createLocalSlot(
                "Error",
                new ProtosObjectValue(ProtosObjectValue.rootObject()));
        bindings.createLocalSlot(
                "Array",
                new ProtosObjectValue(ProtosObjectValue.rootObject()));
        bindings.freeze();

        return new ProtosPrelude(bindings, contextPrototype)
                .newModuleActivation();
    }
}
