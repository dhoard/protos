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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.guillermomolina.protos.parser.ProtosParser;
import com.guillermomolina.protos.runtime.ProtosActivation;
import com.guillermomolina.protos.runtime.ProtosClosureValue;
import com.guillermomolina.protos.runtime.ProtosObjectValue;
import com.guillermomolina.protos.runtime.ProtosPrelude;
import com.guillermomolina.protos.semantic.Canonicalizer;
import com.guillermomolina.protos.semantic.ast.CanonicalCreate;
import com.guillermomolina.protos.semantic.ast.CanonicalExpression;
import com.guillermomolina.protos.semantic.ast.CanonicalObject;
import com.guillermomolina.protos.semantic.ast.CanonicalSequence;
import com.oracle.truffle.api.TruffleLanguage.LanguageReference;
import com.oracle.truffle.api.bytecode.BytecodeRootNodes;
import com.oracle.truffle.api.source.Source;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;

/**
 * PERF013 Slice A3 focal evidence, updated for PLAT041 C′ (PERF025-C1b): an
 * object-construction body is an inline resumable region of the lexical
 * lowering unit that contains the object literal, not a physical root. The
 * {@code BytecodeRootNodes<ProtosSemanticBytecodeRootNode>} group therefore contains
 * only the lexical owner root and the Closure roots lexically nested in it —
 * including method Closures declared inside the object body — so the owner
 * {@code BytecodeLocal}/nested-Closure same-generation invariant the PERF013
 * {@code MaterializedLocalAccessor} path depends on is preserved.
 *
 * <p>An inline object body is still not a lexical execution context: a
 * Closure declared inside it must capture the enclosing genuine lexical
 * context(s), never the constructed object itself (see {@code
 * EXECUTION_AND_CONTROL.md} "Object Construction Is Not a Lexical Capture
 * Scope"). Captured read/write mechanics remain covered by {@link
 * ProtosPerf013SliceB1MaterializedCapturedReadTest}, {@link
 * ProtosPerf013SliceB2MaterializedCapturedWriteTest} and {@link
 * ProtosI068Slice5CapturedMaterializedLexicalLoweringTest}.
 */
final class ProtosPerf013SliceA3ObjectBodyHelperSharedGroupingTest {
    private static final LanguageReference<ProtosLanguage> LANGUAGE_REF =
            LanguageReference.create(ProtosLanguage.class);

    @Test
    void objectBodyIsInlineAndContributesNoRoot() throws Exception {
        try (Context context = Context.newBuilder(ProtosLanguage.ID).build()) {
            context.initialize(ProtosLanguage.ID);
            context.enter();
            try {
                ProtosLanguage language = LANGUAGE_REF.get(null);
                ProtosActivation module = moduleActivation();

                String characters = "{\n  x: 1\n}";
                Source source =
                        Source.newBuilder(
                                        ProtosLanguage.ID,
                                        characters,
                                        "perf013-slicea3-object-body-shared-group.protos")
                                .build();
                CanonicalSequence sequence = canonicalize(characters);
                assertInstanceOf(
                        CanonicalObject.class, sequence.expressions().get(0));

                CanonicalToBytecodeLowerer lowerer =
                        new CanonicalToBytecodeLowerer(language, source, ProtosExecutionMode.NORMAL);
                ProtosSemanticBytecodeRootNode root = lowerer.lowerRoot(sequence);

                ProtosObjectValue object =
                        assertInstanceOf(
                                ProtosObjectValue.class,
                                root.getCallTarget().call(module));
                assertTrue(object.hasLocalSlot("x"));

                assertEquals(
                        1,
                        root.getRootNodes().count(),
                        "inline object body must not contribute a physical root");
            } finally {
                context.leave();
            }
        }
    }

    @Test
    void closureInInlineObjectBodySharesGroupWithOuterOwnerAndCapturesOuterLexicalContext()
            throws Exception {
        try (Context context = Context.newBuilder(ProtosLanguage.ID).build()) {
            context.initialize(ProtosLanguage.ID);
            context.enter();
            try {
                ProtosLanguage language = LANGUAGE_REF.get(null);
                ProtosActivation module = moduleActivation();

                ProtosObjectValue lexical =
                        new ProtosObjectValue(ProtosObjectValue.rootObject());
                ProtosObjectValue objectLocal =
                        new ProtosObjectValue(ProtosObjectValue.rootObject());
                module.context().createLocalSlot("seed", lexical);
                module.context().createLocalSlot("objectValue", objectLocal);

                String characters =
                        "x: seed\n"
                                + "obj: {\n"
                                + "  x: objectValue\n"
                                + "  method: () => x\n"
                                + "}\n"
                                + "obj";
                Source source =
                        Source.newBuilder(
                                        ProtosLanguage.ID,
                                        characters,
                                        "perf013-slicea3-object-body-closure.protos")
                                .build();
                CanonicalSequence sequence = canonicalize(characters);
                findObjectDefinition(sequence, "obj");

                CanonicalToBytecodeLowerer lowerer =
                        new CanonicalToBytecodeLowerer(language, source, ProtosExecutionMode.NORMAL);
                ProtosSemanticBytecodeRootNode root = lowerer.lowerRoot(sequence);

                ProtosObjectValue object =
                        assertInstanceOf(
                                ProtosObjectValue.class,
                                root.getCallTarget().call(module));
                assertSame(
                        objectLocal,
                        object.readLocalSlot("x").orElseThrow(),
                        "object slot storage must remain unchanged (receiver state, not a lexical local)");

                ProtosClosureValue method =
                        assertInstanceOf(
                                ProtosClosureValue.class,
                                object.readLocalSlot("method").orElseThrow());
                ProtosClosureExecutionPlan methodPlan =
                        method.executionPlan().orElseThrow();
                ProtosSemanticBytecodeRootNode methodRoot =
                        methodPlan.bytecodeActivationRootForTesting();

                BytecodeRootNodes<?> group = root.getRootNodes();
                assertSame(
                        group,
                        methodRoot.getRootNodes(),
                        "Closure declared in object body must share owner's BytecodeRootNodes group");
                assertRejectionRootsInGroup(group, methodRoot);
                assertEquals(
                        3,
                        group.count(),
                        () -> "expected owner + method Closure + arity root, got "
                                + group.count());

                ProtosClosureValue bound = method.bindMethod(object, object);
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
                                .executeBytecodeActivationForTesting(invocation),
                        "method must resolve the bare name against the outer genuine "
                                + "lexical context, not the constructed object's own slot");
            } finally {
                context.leave();
            }
        }
    }

    @Test
    void multiDepthClosuresInObjectBodyShareGroupWithOuterOwner() throws Exception {
        try (Context context = Context.newBuilder(ProtosLanguage.ID).build()) {
            context.initialize(ProtosLanguage.ID);
            context.enter();
            try {
                ProtosLanguage language = LANGUAGE_REF.get(null);
                ProtosActivation module = moduleActivation();

                ProtosObjectValue lexical =
                        new ProtosObjectValue(ProtosObjectValue.rootObject());
                module.context().createLocalSlot("seed", lexical);

                String characters =
                        "x: seed\n"
                                + "obj: {\n"
                                + "  method: () => {\n"
                                + "    () => x\n"
                                + "  }\n"
                                + "}\n"
                                + "obj";
                Source source =
                        Source.newBuilder(
                                        ProtosLanguage.ID,
                                        characters,
                                        "perf013-slicea3-object-body-multi-depth.protos")
                                .build();
                CanonicalSequence sequence = canonicalize(characters);
                findObjectDefinition(sequence, "obj");

                CanonicalToBytecodeLowerer lowerer =
                        new CanonicalToBytecodeLowerer(language, source, ProtosExecutionMode.NORMAL);
                ProtosSemanticBytecodeRootNode root = lowerer.lowerRoot(sequence);

                ProtosObjectValue object =
                        assertInstanceOf(
                                ProtosObjectValue.class,
                                root.getCallTarget().call(module));

                ProtosClosureValue method =
                        assertInstanceOf(
                                ProtosClosureValue.class,
                                object.readLocalSlot("method").orElseThrow());
                ProtosClosureExecutionPlan methodPlan =
                        method.executionPlan().orElseThrow();
                ProtosSemanticBytecodeRootNode methodRoot =
                        methodPlan.bytecodeActivationRootForTesting();

                ProtosActivation methodInvocation =
                        ProtosActivation.forClosureInvocation(
                                method.bindMethod(object, object),
                                java.util.List.of(),
                                module.prelude().orElseThrow(),
                                module.actorModuleState(),
                                module.currentModuleKey().orElse(null),
                                module.executionDomain());
                ProtosClosureValue innerClosure =
                        assertInstanceOf(
                                ProtosClosureValue.class,
                                methodPlan.executeBytecodeActivationForTesting(
                                        methodInvocation));
                ProtosClosureExecutionPlan innerPlan =
                        innerClosure.executionPlan().orElseThrow();
                ProtosSemanticBytecodeRootNode innerRoot =
                        innerPlan.bytecodeActivationRootForTesting();

                BytecodeRootNodes<?> group = root.getRootNodes();
                assertSame(group, methodRoot.getRootNodes());
                assertSame(group, innerRoot.getRootNodes());
                assertRejectionRootsInGroup(group, methodRoot, innerRoot);
                assertEquals(
                        5,
                        group.count(),
                        () -> "expected three lexical roots + two arity roots, got "
                                + group.count());

                ProtosActivation innerInvocation =
                        ProtosActivation.forClosureInvocation(
                                innerClosure,
                                java.util.List.of(),
                                module.prelude().orElseThrow(),
                                module.actorModuleState(),
                                module.currentModuleKey().orElse(null),
                                module.executionDomain());
                assertSame(
                        lexical,
                        innerPlan.executeBytecodeActivationForTesting(innerInvocation),
                        "innermost Closure must still resolve the bare name against the "
                                + "outer genuine lexical context through the inline object body");
            } finally {
                context.leave();
            }
        }
    }

    private static CanonicalObject findObjectDefinition(
            CanonicalSequence sequence, String name) {
        for (CanonicalExpression expression : sequence.expressions()) {
            if (expression instanceof CanonicalCreate create
                    && name.equals(create.name())
                    && create.value() instanceof CanonicalObject object) {
                return object;
            }
        }
        throw new AssertionError("no object literal bound to '" + name + "' found");
    }

    /**
     * PERF032-G6: each zero-parameter Closure has a cold arity-rejection
     * root in the same BytecodeRootNodes group as its lexical owner.
     */
    private static void assertRejectionRootsInGroup(
            BytecodeRootNodes<?> group,
            ProtosSemanticBytecodeRootNode... closureRoots) {
        for (ProtosSemanticBytecodeRootNode closureRoot : closureRoots) {
            ProtosSemanticBytecodeRootNode rejectionRoot =
                    assertInstanceOf(
                            ProtosSemanticBytecodeRootNode.class,
                            closureRoot.arityRejectionTarget().getRootNode());
            assertSame(
                    group,
                    rejectionRoot.getRootNodes(),
                    "arity-rejection root must preserve lexical group identity");
        }
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
