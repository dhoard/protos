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
import com.guillermomolina.protos.runtime.ProtosObjectValue;
import com.guillermomolina.protos.runtime.ProtosPrelude;
import com.guillermomolina.protos.runtime.ProtosSignalException;
import com.guillermomolina.protos.semantic.Canonicalizer;
import com.guillermomolina.protos.semantic.ast.CanonicalAssign;
import com.guillermomolina.protos.semantic.ast.CanonicalClosure;
import com.guillermomolina.protos.semantic.ast.CanonicalSequence;
import com.oracle.truffle.api.TruffleLanguage.LanguageReference;
import com.oracle.truffle.api.source.Source;

import java.util.List;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;

/**
 * PERF013 Slice B2 focal evidence: a proven {@code CapturedResolved} lexical
 * write whose owner root shares the current lowering group (PERF013 Slice
 * A/A2/A3, PERF013 Slice B1) compiles to {@code
 * ResolveCapturedMaterializedWritableLexicalTarget}/{@code
 * AssignCapturedMaterializedLocal} — a compile-time {@link
 * com.oracle.truffle.api.bytecode.MaterializedLocalAccessor} identifying the
 * owner's {@link com.oracle.truffle.api.bytecode.BytecodeLocal} — instead of
 * the runtime {@code ProtosFrameLexicalBindingAuthority}/{@code
 * LocalRangeAccessor} path {@code ResolveCapturedWritableLexicalTarget}/{@code
 * AssignCapturedFrameLocal} still use. The destination is always fully
 * selected before RHS evaluation and only revalidated, never re-resolved, at
 * the mutation point; PERF013 Slice B1 captured reads are exercised
 * separately by {@link ProtosPerf013SliceB1MaterializedCapturedReadTest} and
 * are unaffected by this slice.
 */
final class ProtosPerf013SliceB2MaterializedCapturedWriteTest {
    private static final LanguageReference<ProtosLanguage> LANGUAGE_REF =
            LanguageReference.create(ProtosLanguage.class);

    @Test
    void ordinaryCapturedWriteUsesMaterializedAccessorFastPath() throws Exception {
        try (Context context = Context.newBuilder(ProtosLanguage.ID).build()) {
            context.initialize(ProtosLanguage.ID);
            context.enter();
            try {
                ProtosLanguage language = LANGUAGE_REF.get(null);
                ProtosActivation module = moduleActivation();

                ProtosObjectValue original = new ProtosObjectValue(ProtosObjectValue.rootObject());
                ProtosObjectValue replacement = new ProtosObjectValue(ProtosObjectValue.rootObject());
                module.context().createLocalSlot("seed", original);
                module.context().createLocalSlot("replacement", replacement);

                String characters = "x: seed\n" + "() => { x = replacement\n" + "x }";
                Source source =
                        Source.newBuilder(ProtosLanguage.ID, characters, "perf013-b2-ordinary.protos")
                                .build();
                CanonicalSequence sequence = canonicalize(characters);
                CanonicalClosure definition =
                        assertInstanceOf(CanonicalClosure.class, sequence.expressions().get(1));
                CanonicalAssign capturedWrite =
                        assertInstanceOf(CanonicalAssign.class, definition.body().expressions().get(0));

                ProtosSemanticBytecodeRootNode root =
                        new CanonicalToBytecodeLowerer(language, source, ProtosExecutionMode.NORMAL).lowerRoot(sequence);
                ProtosClosureValue closure =
                        assertInstanceOf(ProtosClosureValue.class, root.getCallTarget().call(module));
                ProtosClosureExecutionPlan plan = closure.executionPlan().orElseThrow();

                assertMaterializedWritePath(plan, capturedWrite);

                Object result =
                        plan.executeBytecodeActivationForTesting(invocationOf(module, closure));

                assertSame(replacement, result);
                assertSame(replacement, module.context().readLocalSlot("x").orElseThrow());
            } finally {
                context.leave();
            }
        }
        System.out.println("SAME_GROUP_CAPTURED_WRITE=MATERIALIZED_LOCAL_ACCESSOR");
        System.out.println("CAPTURED_WRITE=PASS");
        System.out.println("ASSIGNMENT_RESULT_IS_RHS=PASS");
    }

    @Test
    void capturedWriteIsObservedByReferenceFromAnotherClosure() throws Exception {
        try (Context context = Context.newBuilder(ProtosLanguage.ID).build()) {
            context.initialize(ProtosLanguage.ID);
            context.enter();
            try {
                ProtosLanguage language = LANGUAGE_REF.get(null);
                ProtosActivation module = moduleActivation();

                ProtosObjectValue original = new ProtosObjectValue(ProtosObjectValue.rootObject());
                ProtosObjectValue replacement = new ProtosObjectValue(ProtosObjectValue.rootObject());
                module.context().createLocalSlot("seed", original);
                module.context().createLocalSlot("replacement", replacement);

                String characters =
                        "x: seed\n"
                                + "writer: () => { x = replacement }\n"
                                + "reader: () => x\n"
                                + "writer()\n"
                                + "reader()";
                Source source =
                        Source.newBuilder(
                                        ProtosLanguage.ID, characters, "perf013-b2-by-reference.protos")
                                .build();
                CanonicalSequence sequence = canonicalize(characters);

                ProtosSemanticBytecodeRootNode root =
                        new CanonicalToBytecodeLowerer(language, source, ProtosExecutionMode.NORMAL).lowerRoot(sequence);

                assertSame(replacement, root.getCallTarget().call(module));
            } finally {
                context.leave();
            }
        }
        System.out.println("CAPTURE_BY_REFERENCE=PASS");
    }

    @Test
    void escapedCapturedWriteMutatesOwnerAfterOwnerActivationReturned() throws Exception {
        try (Context context = Context.newBuilder(ProtosLanguage.ID).build()) {
            context.initialize(ProtosLanguage.ID);
            context.enter();
            try {
                ProtosLanguage language = LANGUAGE_REF.get(null);
                ProtosActivation module = moduleActivation();

                ProtosObjectValue replacement = new ProtosObjectValue(ProtosObjectValue.rootObject());
                module.context().createLocalSlot("replacement", replacement);

                String characters =
                        "f: (x) => () => { x = replacement\n"
                                + "x }\n"
                                + "g: f(7)\n"
                                + "g()";
                Source source =
                        Source.newBuilder(ProtosLanguage.ID, characters, "perf013-b2-escaped.protos")
                                .build();
                CanonicalSequence sequence = canonicalize(characters);

                ProtosSemanticBytecodeRootNode root =
                        new CanonicalToBytecodeLowerer(language, source, ProtosExecutionMode.NORMAL).lowerRoot(sequence);

                /*
                 * By the time g() executes, f's own activation has already
                 * returned: g only escapes because f's own root installs its
                 * retained/materialized frame-lexical-binding authority
                 * before returning.
                 */
                assertSame(replacement, root.getCallTarget().call(module));
            } finally {
                context.leave();
            }
        }
        System.out.println("ESCAPED_CAPTURED_WRITE=PASS");
    }

    @Test
    void multiDepthCapturedWriteUsesMaterializedAccessorAtOwnerDepth() throws Exception {
        try (Context context = Context.newBuilder(ProtosLanguage.ID).build()) {
            context.initialize(ProtosLanguage.ID);
            context.enter();
            try {
                ProtosLanguage language = LANGUAGE_REF.get(null);
                ProtosActivation module = moduleActivation();

                String characters =
                        "outer: (x) => { () => { () => { x = 99\n" + "x } } }\n" + "outer(1)()()";
                Source source =
                        Source.newBuilder(
                                        ProtosLanguage.ID, characters, "perf013-b2-multidepth.protos")
                                .build();
                CanonicalSequence sequence = canonicalize(characters);

                ProtosSemanticBytecodeRootNode root =
                        new CanonicalToBytecodeLowerer(language, source, ProtosExecutionMode.NORMAL).lowerRoot(sequence);

                Object result = root.getCallTarget().call(module);
                ProtosIntegerValue integer = assertInstanceOf(ProtosIntegerValue.class, result);
                assertEquals(String.valueOf("99"), com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerDecimalText(integer));
            } finally {
                context.leave();
            }
        }
        System.out.println("MULTI_DEPTH_CAPTURED_WRITE=PASS");
    }

    @Test
    void destinationSelectedBeforeRhsIsNotRetargetedByNearerCreationDuringRhs() throws Exception {
        try (Context context = Context.newBuilder(ProtosLanguage.ID).build()) {
            context.initialize(ProtosLanguage.ID);
            context.enter();
            try {
                ProtosLanguage language = LANGUAGE_REF.get(null);
                ProtosActivation module = moduleActivation();

                ProtosObjectValue original = new ProtosObjectValue(ProtosObjectValue.rootObject());
                ProtosObjectValue nearer = new ProtosObjectValue(ProtosObjectValue.rootObject());
                ProtosObjectValue rhsResult = new ProtosObjectValue(ProtosObjectValue.rootObject());
                module.context().createLocalSlot("seed", original);

                ProtosActivation[] invocationHolder = new ProtosActivation[1];
                ProtosClosureValue sideEffect =
                        ProtosClosureValue.nativeClosure(
                                (nativeActivation, supplied) -> {
                                    invocationHolder[0].context().createLocalSlot("x", nearer);
                                    return rhsResult;
                                });
                module.context().createLocalSlot("sideEffect", sideEffect);

                String characters = "x: seed\n" + "() => { x = sideEffect() }";
                Source source =
                        Source.newBuilder(
                                        ProtosLanguage.ID,
                                        characters,
                                        "perf013-b2-destination-before-rhs.protos")
                                .build();
                CanonicalSequence sequence = canonicalize(characters);
                CanonicalClosure definition =
                        assertInstanceOf(CanonicalClosure.class, sequence.expressions().get(1));
                CanonicalAssign capturedWrite =
                        assertInstanceOf(CanonicalAssign.class, definition.body().expressions().get(0));

                ProtosSemanticBytecodeRootNode root =
                        new CanonicalToBytecodeLowerer(language, source, ProtosExecutionMode.NORMAL).lowerRoot(sequence);
                ProtosClosureValue closure =
                        assertInstanceOf(ProtosClosureValue.class, root.getCallTarget().call(module));
                ProtosClosureExecutionPlan plan = closure.executionPlan().orElseThrow();

                assertMaterializedWritePath(plan, capturedWrite);

                ProtosActivation invocation = invocationOf(module, closure);
                invocationHolder[0] = invocation;

                Object result = plan.executeBytecodeActivationForTesting(invocation);

                assertSame(rhsResult, result);
                /*
                 * The nearer binding created by the RHS must remain untouched:
                 * assignment was already committed to the outer destination
                 * before the RHS ran.
                 */
                assertSame(nearer, invocation.context().readLocalSlot("x").orElseThrow());
                assertSame(rhsResult, module.context().readLocalSlot("x").orElseThrow());
            } finally {
                context.leave();
            }
        }
        System.out.println("DESTINATION_RESOLVED_BEFORE_RHS=PASS");
        System.out.println("NO_POST_RHS_RETARGETING=PASS");
    }

    @Test
    void rhsRemovalOfSelectedDestinationCausesMutationErrorNotRetarget() throws Exception {
        try (Context context = Context.newBuilder(ProtosLanguage.ID).build()) {
            context.initialize(ProtosLanguage.ID);
            context.enter();
            try {
                ProtosLanguage language = LANGUAGE_REF.get(null);
                ProtosActivation module = moduleActivation();

                ProtosObjectValue original = new ProtosObjectValue(ProtosObjectValue.rootObject());
                ProtosObjectValue rhsResult = new ProtosObjectValue(ProtosObjectValue.rootObject());
                module.context().createLocalSlot("seed", original);

                ProtosClosureValue sideEffect =
                        ProtosClosureValue.nativeClosure(
                                (nativeActivation, supplied) -> {
                                    module.context().removeLocalSlot("x");
                                    return rhsResult;
                                });
                module.context().createLocalSlot("sideEffect", sideEffect);

                String characters = "x: seed\n" + "() => { x = sideEffect() }";
                Source source =
                        Source.newBuilder(
                                        ProtosLanguage.ID,
                                        characters,
                                        "perf013-b2-removed-during-rhs.protos")
                                .build();
                CanonicalSequence sequence = canonicalize(characters);
                CanonicalClosure definition =
                        assertInstanceOf(CanonicalClosure.class, sequence.expressions().get(1));
                CanonicalAssign capturedWrite =
                        assertInstanceOf(CanonicalAssign.class, definition.body().expressions().get(0));

                ProtosSemanticBytecodeRootNode root =
                        new CanonicalToBytecodeLowerer(language, source, ProtosExecutionMode.NORMAL).lowerRoot(sequence);
                ProtosClosureValue closure =
                        assertInstanceOf(ProtosClosureValue.class, root.getCallTarget().call(module));
                ProtosClosureExecutionPlan plan = closure.executionPlan().orElseThrow();

                assertMaterializedWritePath(plan, capturedWrite);

                ProtosActivation invocation = invocationOf(module, closure);

                assertThrows(
                        ProtosSignalException.class,
                        () -> plan.executeBytecodeActivationForTesting(invocation));

                assertFalse(module.context().hasLocalSlot("x"));
            } finally {
                context.leave();
            }
        }
        System.out.println("NO_RETARGET=PASS");
        System.out.println("EXPECTED_MUTATION_ERROR=PASS");
    }

    @Test
    void rhsRemoveThenRecreateSameSelectedOwnerStillReceivesTheWrite() throws Exception {
        try (Context context = Context.newBuilder(ProtosLanguage.ID).build()) {
            context.initialize(ProtosLanguage.ID);
            context.enter();
            try {
                ProtosLanguage language = LANGUAGE_REF.get(null);
                ProtosActivation module = moduleActivation();

                ProtosObjectValue original = new ProtosObjectValue(ProtosObjectValue.rootObject());
                ProtosObjectValue placeholder = new ProtosObjectValue(ProtosObjectValue.rootObject());
                ProtosObjectValue rhsResult = new ProtosObjectValue(ProtosObjectValue.rootObject());
                module.context().createLocalSlot("seed", original);

                ProtosClosureValue sideEffect =
                        ProtosClosureValue.nativeClosure(
                                (nativeActivation, supplied) -> {
                                    module.context().removeLocalSlot("x");
                                    module.context().createLocalSlot("x", placeholder);
                                    return rhsResult;
                                });
                module.context().createLocalSlot("sideEffect", sideEffect);

                String characters = "x: seed\n" + "() => { x = sideEffect() }";
                Source source =
                        Source.newBuilder(
                                        ProtosLanguage.ID,
                                        characters,
                                        "perf013-b2-remove-recreate-during-rhs.protos")
                                .build();
                CanonicalSequence sequence = canonicalize(characters);

                ProtosSemanticBytecodeRootNode root =
                        new CanonicalToBytecodeLowerer(language, source, ProtosExecutionMode.NORMAL).lowerRoot(sequence);
                ProtosClosureValue closure =
                        assertInstanceOf(ProtosClosureValue.class, root.getCallTarget().call(module));
                ProtosClosureExecutionPlan plan = closure.executionPlan().orElseThrow();

                ProtosActivation invocation = invocationOf(module, closure);

                Object result = plan.executeBytecodeActivationForTesting(invocation);

                assertSame(rhsResult, result);
                /*
                 * The write applies to the recreated same owner slot, never a
                 * different one: the recreate-time placeholder is overwritten
                 * by the assignment.
                 */
                assertSame(rhsResult, module.context().readLocalSlot("x").orElseThrow());
            } finally {
                context.leave();
            }
        }
        System.out.println("REMOVE_RECREATE_SELECTED_OWNER=PASS");
    }

    @Test
    void closedDestinationRemainsWritable() throws Exception {
        try (Context context = Context.newBuilder(ProtosLanguage.ID).build()) {
            context.initialize(ProtosLanguage.ID);
            context.enter();
            try {
                ProtosLanguage language = LANGUAGE_REF.get(null);
                ProtosActivation module = moduleActivation();

                ProtosObjectValue original = new ProtosObjectValue(ProtosObjectValue.rootObject());
                ProtosObjectValue replacement = new ProtosObjectValue(ProtosObjectValue.rootObject());
                module.context().createLocalSlot("seed", original);
                module.context().createLocalSlot("replacement", replacement);

                String characters = "x: seed\n" + "() => { x = replacement }";
                Source source =
                        Source.newBuilder(ProtosLanguage.ID, characters, "perf013-b2-closed.protos")
                                .build();
                CanonicalSequence sequence = canonicalize(characters);

                ProtosSemanticBytecodeRootNode root =
                        new CanonicalToBytecodeLowerer(language, source, ProtosExecutionMode.NORMAL).lowerRoot(sequence);
                ProtosClosureValue closure =
                        assertInstanceOf(ProtosClosureValue.class, root.getCallTarget().call(module));
                ProtosClosureExecutionPlan plan = closure.executionPlan().orElseThrow();

                module.context().close();

                Object result =
                        plan.executeBytecodeActivationForTesting(invocationOf(module, closure));

                assertSame(replacement, result);
                assertSame(replacement, module.context().readLocalSlot("x").orElseThrow());
            } finally {
                context.leave();
            }
        }
        System.out.println("CLOSED_WRITE_ALLOWED=PASS");
    }

    @Test
    void frozenDestinationRejectsCapturedWrite() throws Exception {
        try (Context context = Context.newBuilder(ProtosLanguage.ID).build()) {
            context.initialize(ProtosLanguage.ID);
            context.enter();
            try {
                ProtosLanguage language = LANGUAGE_REF.get(null);
                ProtosActivation module = moduleActivation();

                ProtosObjectValue original = new ProtosObjectValue(ProtosObjectValue.rootObject());
                ProtosObjectValue replacement = new ProtosObjectValue(ProtosObjectValue.rootObject());
                module.context().createLocalSlot("seed", original);
                module.context().createLocalSlot("replacement", replacement);

                String characters = "x: seed\n" + "() => { x = replacement }";
                Source source =
                        Source.newBuilder(ProtosLanguage.ID, characters, "perf013-b2-frozen.protos")
                                .build();
                CanonicalSequence sequence = canonicalize(characters);

                ProtosSemanticBytecodeRootNode root =
                        new CanonicalToBytecodeLowerer(language, source, ProtosExecutionMode.NORMAL).lowerRoot(sequence);
                ProtosClosureValue closure =
                        assertInstanceOf(ProtosClosureValue.class, root.getCallTarget().call(module));
                ProtosClosureExecutionPlan plan = closure.executionPlan().orElseThrow();

                module.context().freeze();

                ProtosActivation invocation = invocationOf(module, closure);

                assertThrows(
                        ProtosSignalException.class,
                        () -> plan.executeBytecodeActivationForTesting(invocation));

                assertSame(original, module.context().readLocalSlot("x").orElseThrow());
            } finally {
                context.leave();
            }
        }
        System.out.println("FROZEN_WRITE_REJECTED=PASS");
    }

    @Test
    void nearerBindingPresentBeforeResolutionSkipsTheMaterializedOwner() throws Exception {
        try (Context context = Context.newBuilder(ProtosLanguage.ID).build()) {
            context.initialize(ProtosLanguage.ID);
            context.enter();
            try {
                ProtosLanguage language = LANGUAGE_REF.get(null);
                ProtosActivation module = moduleActivation();

                ProtosObjectValue original = new ProtosObjectValue(ProtosObjectValue.rootObject());
                ProtosObjectValue nearer = new ProtosObjectValue(ProtosObjectValue.rootObject());
                ProtosObjectValue replacement = new ProtosObjectValue(ProtosObjectValue.rootObject());
                module.context().createLocalSlot("seed", original);
                module.context().createLocalSlot("replacement", replacement);

                String characters = "x: seed\n" + "() => { x = replacement }";
                Source source =
                        Source.newBuilder(
                                        ProtosLanguage.ID, characters, "perf013-b2-nearer-present.protos")
                                .build();
                CanonicalSequence sequence = canonicalize(characters);

                ProtosSemanticBytecodeRootNode root =
                        new CanonicalToBytecodeLowerer(language, source, ProtosExecutionMode.NORMAL).lowerRoot(sequence);
                ProtosClosureValue closure =
                        assertInstanceOf(ProtosClosureValue.class, root.getCallTarget().call(module));
                ProtosClosureExecutionPlan plan = closure.executionPlan().orElseThrow();

                ProtosActivation invocation = invocationOf(module, closure);
                /*
                 * A nearer PRESENT binding, already established before
                 * resolution even runs, must win over the statically proven
                 * owner: the static accessor owner must not be used at all.
                 */
                invocation.context().createLocalSlot("x", nearer);

                Object result = plan.executeBytecodeActivationForTesting(invocation);

                assertSame(replacement, result);
                assertSame(replacement, invocation.context().readLocalSlot("x").orElseThrow());
                assertSame(original, module.context().readLocalSlot("x").orElseThrow());
            } finally {
                context.leave();
            }
        }
        System.out.println("NEARER_PRESENT_BEFORE_RESOLUTION=PASS");
    }

    @Test
    void defaultParameterAssignmentUsesMaterializedAccessorForOwnerParameter() throws Exception {
        try (Context context = Context.newBuilder(ProtosLanguage.ID).build()) {
            context.initialize(ProtosLanguage.ID);
            context.enter();
            try {
                ProtosLanguage language = LANGUAGE_REF.get(null);
                ProtosActivation module = moduleActivation();

                String characters = "f: (x, y = (x = 99)) => x\n" + "f(1)";
                Source source =
                        Source.newBuilder(
                                        ProtosLanguage.ID, characters, "perf013-b2-default-assign.protos")
                                .build();
                CanonicalSequence sequence = canonicalize(characters);

                ProtosSemanticBytecodeRootNode root =
                        new CanonicalToBytecodeLowerer(language, source, ProtosExecutionMode.NORMAL).lowerRoot(sequence);

                Object result = root.getCallTarget().call(module);
                ProtosIntegerValue integer = assertInstanceOf(ProtosIntegerValue.class, result);
                assertEquals(String.valueOf("99"), com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerDecimalText(integer));
            } finally {
                context.leave();
            }
        }
        System.out.println("DEFAULT_CAPTURED_WRITE=PASS");
    }

    @Test
    void closureInsideObjectBodyWritesOuterLexicalNotObjectSlot() throws Exception {
        try (Context context = Context.newBuilder(ProtosLanguage.ID).build()) {
            context.initialize(ProtosLanguage.ID);
            context.enter();
            try {
                ProtosLanguage language = LANGUAGE_REF.get(null);
                ProtosActivation module = moduleActivation();

                ProtosObjectValue outerLexical = new ProtosObjectValue(ProtosObjectValue.rootObject());
                ProtosObjectValue objectSlot = new ProtosObjectValue(ProtosObjectValue.rootObject());
                ProtosObjectValue replacement = new ProtosObjectValue(ProtosObjectValue.rootObject());
                module.context().createLocalSlot("seed", outerLexical);
                module.context().createLocalSlot("objectValue", objectSlot);
                module.context().createLocalSlot("replacement", replacement);

                String characters =
                        "x: seed\n"
                                + "obj: {\n"
                                + "  x: objectValue\n"
                                + "  method: () => { x = replacement }\n"
                                + "}\n"
                                + "obj.method()";
                Source source =
                        Source.newBuilder(
                                        ProtosLanguage.ID, characters, "perf013-b2-object-body.protos")
                                .build();
                CanonicalSequence sequence = canonicalize(characters);

                ProtosSemanticBytecodeRootNode root =
                        new CanonicalToBytecodeLowerer(language, source, ProtosExecutionMode.NORMAL).lowerRoot(sequence);

                /*
                 * If method's captured write had incorrectly resolved to the
                 * object's own local slot, obj's own x would change instead
                 * of the outer lexical binding.
                 */
                assertSame(replacement, root.getCallTarget().call(module));
                assertSame(replacement, module.context().readLocalSlot("x").orElseThrow());
            } finally {
                context.leave();
            }
        }
        System.out.println("OBJECT_BODY_OUTER_CAPTURED_WRITE=PASS");
    }

    @Test
    void isolatedRebuildWithoutOwnerInGroupKeepsOldSafeWriteFallback() throws Exception {
        try (Context context = Context.newBuilder(ProtosLanguage.ID).build()) {
            context.initialize(ProtosLanguage.ID);
            context.enter();
            try {
                ProtosLanguage language = LANGUAGE_REF.get(null);
                ProtosActivation module = moduleActivation();

                ProtosObjectValue original = new ProtosObjectValue(ProtosObjectValue.rootObject());
                ProtosObjectValue replacement = new ProtosObjectValue(ProtosObjectValue.rootObject());
                module.context().createLocalSlot("seed", original);
                module.context().createLocalSlot("replacement", replacement);

                String characters = "x: seed\n" + "() => { x = replacement\n" + "x }";
                Source source =
                        Source.newBuilder(
                                        ProtosLanguage.ID,
                                        characters,
                                        "perf013-b2-isolated-rebuild.protos")
                                .build();

                CanonicalSequence original2 = canonicalize(characters);
                ProtosSemanticBytecodeRootNode root =
                        new CanonicalToBytecodeLowerer(language, source, ProtosExecutionMode.NORMAL).lowerRoot(original2);
                ProtosClosureValue closure =
                        assertInstanceOf(ProtosClosureValue.class, root.getCallTarget().call(module));
                ProtosClosureExecutionPlan template = closure.executionPlan().orElseThrow();

                CanonicalSequence reparsed = canonicalize(characters);
                CanonicalClosure reparsedDefinition =
                        assertInstanceOf(CanonicalClosure.class, reparsed.expressions().get(1));

                ProtosClosureExecutionPlan rebuilt =
                        template.rebuildBytecodeForLanguage(reparsedDefinition, language, ProtosExecutionMode.NORMAL);

                List<String> instructionNames =
                        rebuilt.bytecodeActivationRootForTesting()
                                .getBytecodeNode()
                                .getInstructionsAsList()
                                .stream()
                                .map(com.oracle.truffle.api.bytecode.Instruction::getName)
                                .toList();

                /*
                 * The rebuild lowers the Closure in isolation, in a brand new
                 * BytecodeRootNodes group that does not include the module
                 * root: the owner BytecodeLocal is unavailable, so the exact
                 * old runtime-authority fallback path must still be used.
                 */
                assertTrue(
                        instructionNames.stream()
                                .anyMatch(name -> name.contains("ResolveCapturedWritableLexicalTarget")),
                        () -> "isolated rebuild lost its safe write fallback path: " + instructionNames);
                assertTrue(
                        instructionNames.stream()
                                .anyMatch(name -> name.contains("AssignCapturedFrameLocal")),
                        () -> "isolated rebuild lost its safe write fallback path: " + instructionNames);
                assertTrue(
                        instructionNames.stream()
                                .noneMatch(
                                        name ->
                                                name.contains(
                                                        "ResolveCapturedMaterializedWritableLexicalTarget")),
                        () ->
                                "isolated rebuild incorrectly used the materialized fast path "
                                        + "without its owner in the group: "
                                        + instructionNames);
                assertTrue(
                        instructionNames.stream()
                                .noneMatch(name -> name.contains("AssignCapturedMaterializedLocal")),
                        () ->
                                "isolated rebuild incorrectly used the materialized fast path "
                                        + "without its owner in the group: "
                                        + instructionNames);

                Object result =
                        rebuilt.executeBytecodeActivationForTesting(invocationOf(module, closure));

                assertSame(replacement, result);
                assertSame(replacement, module.context().readLocalSlot("x").orElseThrow());
            } finally {
                context.leave();
            }
        }
        System.out.println("ISOLATED_GROUP_SAFE_WRITE_FALLBACK=PASS");
    }

    private static void assertMaterializedWritePath(
            ProtosClosureExecutionPlan plan, CanonicalAssign capturedWrite) {
        assertInstanceOf(
                CanonicalBindingResolution.CapturedResolved.class,
                plan.bytecodeBindingAnalysisForTesting().resolutionOf(capturedWrite).orElseThrow());

        List<String> instructionNames =
                plan.bytecodeActivationRootForTesting()
                        .getBytecodeNode()
                        .getInstructionsAsList()
                        .stream()
                        .map(com.oracle.truffle.api.bytecode.Instruction::getName)
                        .toList();

        assertTrue(
                instructionNames.stream()
                        .anyMatch(
                                name ->
                                        name.contains(
                                                "ResolveCapturedMaterializedWritableLexicalTarget")),
                () -> "captured write did not select the materialized resolve fast path: " + instructionNames);
        assertTrue(
                instructionNames.stream()
                        .anyMatch(name -> name.contains("AssignCapturedMaterializedLocal")),
                () -> "captured write did not select the materialized assign fast path: " + instructionNames);
        assertTrue(
                instructionNames.stream()
                        .noneMatch(name -> name.equals("ResolveCapturedWritableLexicalTarget")),
                () ->
                        "captured write incorrectly emitted the runtime-authority resolve fallback "
                                + "even though its owner shares this lowering group: "
                                + instructionNames);
        assertTrue(
                instructionNames.stream()
                        .noneMatch(name -> name.equals("AssignCapturedFrameLocal")),
                () ->
                        "captured write incorrectly emitted the runtime-authority assign fallback "
                                + "even though its owner shares this lowering group: "
                                + instructionNames);
    }

    private static ProtosActivation invocationOf(ProtosActivation module, ProtosClosureValue closure) {
        return ProtosActivation.forClosureInvocation(
                closure,
                List.of(),
                module.prelude().orElseThrow(),
                module.actorModuleState(),
                module.currentModuleKey().orElse(null),
                module.executionDomain());
    }

    private static CanonicalSequence canonicalize(String characters) {
        return (CanonicalSequence)
                new Canonicalizer().canonicalize(new ProtosParser(characters).parseProgram());
    }

    private static ProtosActivation moduleActivation() {
        ProtosStandardObjectProtocol.install();

        ProtosObjectValue contextPrototype = new ProtosObjectValue(ProtosObjectValue.rootObject());
        ProtosObjectValue bindings = new ProtosObjectValue(contextPrototype);
        ProtosObjectValue errorPrototype = new ProtosObjectValue(ProtosObjectValue.rootObject());
        bindings.createLocalSlot("Context", contextPrototype);
        bindings.createLocalSlot("Error", errorPrototype);
        bindings.createLocalSlot("SlotNotFound", new ProtosObjectValue(errorPrototype));
        bindings.createLocalSlot("Array", new ProtosObjectValue(ProtosObjectValue.rootObject()));
        bindings.freeze();

        return new ProtosPrelude(bindings, contextPrototype).newModuleActivation();
    }
}
