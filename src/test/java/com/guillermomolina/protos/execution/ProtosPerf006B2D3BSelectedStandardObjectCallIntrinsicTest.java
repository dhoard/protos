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
import com.guillermomolina.protos.runtime.ProtosLexicalFallback;
import com.guillermomolina.protos.runtime.ProtosClosureValue;
import com.guillermomolina.protos.runtime.ProtosNullValue;
import com.guillermomolina.protos.runtime.ProtosObjectValue;
import com.guillermomolina.protos.runtime.ProtosPrelude;
import com.guillermomolina.protos.runtime.ProtosSignalException;
import com.guillermomolina.protos.runtime.ProtosSlotLookupResult;
import com.guillermomolina.protos.runtime.ProtosValueLookup;
import com.guillermomolina.protos.semantic.Canonicalizer;
import com.guillermomolina.protos.semantic.ast.CanonicalClosure;
import com.guillermomolina.protos.semantic.ast.CanonicalSequence;
import com.guillermomolina.protos.source.SourceSpan;
import com.oracle.truffle.api.TruffleLanguage.LanguageReference;
import com.oracle.truffle.api.bytecode.BytecodeConfig;
import com.oracle.truffle.api.bytecode.BytecodeRootNodes;
import com.oracle.truffle.api.bytecode.ContinuationResult;
import com.oracle.truffle.api.bytecode.TagTree;
import com.oracle.truffle.api.instrumentation.StandardTags;
import com.oracle.truffle.api.source.Source;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;

final class ProtosPerf006B2D3BSelectedStandardObjectCallIntrinsicTest {
    private static final LanguageReference<ProtosLanguage> LANGUAGE_REF =
            LanguageReference.create(ProtosLanguage.class);

    @Test
    void canonicalObjectCallRemainsOrdinaryAndSelectedAfterLookup()
            throws Exception {
        try (Context context = Context.newBuilder(ProtosLanguage.ID).build()) {
            context.initialize(ProtosLanguage.ID);
            context.enter();
            try {
                ProtosActivation module = moduleActivation();
                ProtosClosureValue target =
                        sourceClosure(
                                LANGUAGE_REF.get(null),
                                module,
                                "() => { null }",
                                "perf006-b2d3b-reflection-target.protos");

                Object standard =
                        ProtosObjectValue.rootObject()
                                .readLocalSlot("call")
                                .orElseThrow();
                ProtosSlotLookupResult selected =
                        ProtosValueLookup.lookup(
                                        target,
                                        "call",
                                        module.prelude().orElseThrow())
                                .orElseThrow();

                ProtosClosureValue standardClosure =
                        assertInstanceOf(
                                ProtosClosureValue.class,
                                standard);
                assertSame(standard, selected.value());
                assertSame(
                        ProtosObjectValue.rootObject(),
                        selected.home());
                assertTrue(
                        ProtosStandardObjectProtocol
                                .isCanonicalStandardCallSelection(
                                        selected.value(),
                                        selected.home()));
                assertTrue(standardClosure.nativeBody().isPresent());
                assertTrue(standardClosure.executionPlan().isEmpty());
            } finally {
                context.leave();
            }
        }

        System.out.println("PERF006_B2D3B_STANDARD_CALL_ORDINARY_SLOT=PASS");
        System.out.println("PERF006_B2D3B_STANDARD_CALL_HAS_NO_TRUFFLE_ROOT=PASS");
    }

    @Test
    void standardCallExecutesSourceBackedClosureWithCapturedReceiver()
            throws Exception {
        try (Context context = Context.newBuilder(ProtosLanguage.ID).build()) {
            context.initialize(ProtosLanguage.ID);
            context.enter();
            try {
                ProtosLanguage language = LANGUAGE_REF.get(null);
                ProtosActivation module = moduleActivation();
                ProtosObjectValue marker =
                        new ProtosObjectValue(ProtosObjectValue.rootObject());
                module.context().createLocalSlot("marker", marker);

                ProtosClosureValue target =
                        sourceClosure(
                                language,
                                module,
                                "() => { marker }",
                                "perf006-b2d3b-standard-target.protos");
                module.context().createLocalSlot("target", target);

                Object result =
                        execute(
                                language,
                                module,
                                "target()",
                                "perf006-b2d3b-standard-invoke.protos");

                assertSame(marker, result);
            } finally {
                context.leave();
            }
        }

        System.out.println("PERF006_B2D3B_STANDARD_CLOSURE_INTRINSIC=PASS");
    }

    @Test
    void closureLocalCallOverrideWinsAndKeepsReceiverAndHome()
            throws Exception {
        try (Context context = Context.newBuilder(ProtosLanguage.ID).build()) {
            context.initialize(ProtosLanguage.ID);
            context.enter();
            try {
                ProtosLanguage language = LANGUAGE_REF.get(null);
                ProtosActivation module = moduleActivation();
                ProtosObjectValue bodyMarker =
                        new ProtosObjectValue(ProtosObjectValue.rootObject());
                ProtosObjectValue overrideMarker =
                        new ProtosObjectValue(ProtosObjectValue.rootObject());
                AtomicReference<ProtosActivation> seen =
                        new AtomicReference<>();

                module.context().createLocalSlot("bodyMarker", bodyMarker);
                ProtosClosureValue target =
                        sourceClosure(
                                language,
                                module,
                                "() => { bodyMarker }",
                                "perf006-b2d3b-override-target.protos");
                target.createLocalSlot(
                        "call",
                        ProtosClosureValue.nativeClosure(
                                (activation, supplied) -> {
                                    seen.set(activation);
                                    return overrideMarker;
                                }));
                module.context().createLocalSlot("target", target);

                Object result =
                        execute(
                                language,
                                module,
                                "target()",
                                "perf006-b2d3b-override-invoke.protos");

                assertSame(overrideMarker, result);
                ProtosActivation activation = seen.get();
                assertSame(target, activation.receiver());
                assertSame(
                        target,
                        activation.methodHome().orElseThrow());
            } finally {
                context.leave();
            }
        }

        System.out.println("PERF006_B2D3B_CLOSURE_CALL_OVERRIDE=PASS");
        System.out.println("PERF006_B2D3B_OVERRIDE_RECEIVER_HOME=PASS");
    }

    @Test
    void closureLocalNonClosureCallShadowsCanonicalStandardCall()
            throws Exception {
        try (Context context = Context.newBuilder(ProtosLanguage.ID).build()) {
            context.initialize(ProtosLanguage.ID);
            context.enter();
            try {
                ProtosLanguage language = LANGUAGE_REF.get(null);
                ProtosActivation module = moduleActivation();
                ProtosClosureValue target =
                        sourceClosure(
                                language,
                                module,
                                "() => { null }",
                                "perf006-b2d3b-shadow-target.protos");
                target.createLocalSlot(
                        "call",
                        new ProtosObjectValue(
                                ProtosObjectValue.rootObject()));
                module.context().createLocalSlot("target", target);

                assertThrows(
                        ProtosSignalException.class,
                        () ->
                                execute(
                                        language,
                                        module,
                                        "target()",
                                        "perf006-b2d3b-shadow-invoke.protos"));
            } finally {
                context.leave();
            }
        }

        System.out.println("PERF006_B2D3B_NON_CLOSURE_SHADOWING=PASS");
    }

    @Test
    void standardIntrinsicSuspensionRetainsTargetReturnHomeUntilCompletion()
            throws Exception {
        try (Context context = Context.newBuilder(ProtosLanguage.ID).build()) {
            context.initialize(ProtosLanguage.ID);
            context.enter();
            try {
                ProtosLanguage language = LANGUAGE_REF.get(null);
                ProtosActivation module = moduleActivation();
                ProtosObjectValue marker =
                        new ProtosObjectValue(ProtosObjectValue.rootObject());
                ProtosClosureValue target =
                        yieldingClosure(
                                language,
                                module,
                                marker,
                                "perf006-b2d3b-yielding-target.protos");
                module.context().createLocalSlot("target", target);

                Object first =
                        execute(
                                language,
                                module,
                                "target()",
                                "perf006-b2d3b-yielding-invoke.protos");

                ContinuationResult parent =
                        assertInstanceOf(
                                ContinuationResult.class,
                                first);
                ContinuationResult child =
                        assertInstanceOf(
                                ContinuationResult.class,
                                parent.getResult());
                ProtosActivation targetActivation =
                        activationOf(child);

                assertSame(
                        target.capturedReceiver(),
                        targetActivation.receiver());
                assertTrue(targetActivation.methodHome().isEmpty());
                // PERF025: the hand-built yielding target runs under a proven
                // return-home-unobservable `() => { null }` plan, so its owned
                // home is never materialized and has no lifecycle.
                assertTrue(targetActivation.ownsReturnHome());
                assertFalse(
                        targetActivation
                                .returnHome()
                                .orElseThrow()
                                .isMaterialized());

                Object completed =
                        parent.continueWith(
                                ProtosNullValue.INSTANCE);

                assertSame(marker, completed);
                assertFalse(
                        targetActivation
                                .returnHome()
                                .orElseThrow()
                                .isMaterialized());
            } finally {
                context.leave();
            }
        }

        System.out.println("PERF006_B2D3B_SUSPENSION_COMPOSED=PASS");
        System.out.println("PERF006_B2D3B_RETURN_HOME_NOT_COMPLETED_ON_YIELD=PASS");
        System.out.println("PERF006_B2D3B_DEBUG_ACTIVATION_AUTHORITY_PRESERVED=PASS");
    }

    @Test
    void defaultCallUsesSameStructuralOverrideRule()
            throws Exception {
        try (Context context = Context.newBuilder(ProtosLanguage.ID).build()) {
            context.initialize(ProtosLanguage.ID);
            context.enter();
            try {
                ProtosLanguage language = LANGUAGE_REF.get(null);
                ProtosActivation module = moduleActivation();
                ProtosObjectValue bodyMarker =
                        new ProtosObjectValue(ProtosObjectValue.rootObject());
                ProtosObjectValue overrideMarker =
                        new ProtosObjectValue(ProtosObjectValue.rootObject());

                module.context().createLocalSlot("bodyMarker", bodyMarker);
                ProtosClosureValue target =
                        sourceClosure(
                                language,
                                module,
                                "() => { bodyMarker }",
                                "perf006-b2d3b-default-target.protos");
                target.createLocalSlot(
                        "call",
                        ProtosClosureValue.nativeClosure(
                                (activation, supplied) ->
                                        overrideMarker));
                module.context().createLocalSlot("target", target);

                String characters =
                        "(value = target()) => { value }";
                Source source =
                        Source.newBuilder(
                                        ProtosLanguage.ID,
                                        characters,
                                        "perf006-b2d3b-default-owner.protos")
                                .build();
                CanonicalClosure definition =
                        closureDefinition(characters);
                ProtosClosureValue owner =
                        semanticClosure(
                                definition,
                                ProtosClosureExecutionPlan.bytecode(
                                        definition,
                                        language,
                                        source, ProtosExecutionMode.NORMAL),
                                module);
                ProtosActivation invocation =
                        ProtosActivation.forClosureInvocation(
                                owner,
                                List.of(),
                                module.prelude().orElseThrow(),
                                module.actorModuleState(),
                                module.currentModuleKey().orElse(null),
                                module.executionDomain());

                Object result =
                        new ProtosBytecodeClosureExecutionPlan(
                                        definition,
                                        language,
                                        source, ProtosExecutionMode.NORMAL)
                                .executeActivation(invocation);

                assertSame(overrideMarker, result);
                assertSame(
                        overrideMarker,
                        ProtosLexicalFallback.readByName(invocation, "value").orElseThrow());
            } finally {
                context.leave();
            }
        }

        System.out.println("PERF006_B2D3B_DEFAULT_STRUCTURAL_CALL_PARITY=PASS");
    }

    @Test
    void oneParenthesizedCallKeepsExactlyOneCallTagAtExactSource()
            throws Exception {
        try (Context context = Context.newBuilder(ProtosLanguage.ID).build()) {
            context.initialize(ProtosLanguage.ID);
            context.enter();
            try {
                ProtosLanguage language = LANGUAGE_REF.get(null);
                String characters = "target()";
                Source source =
                        Source.newBuilder(
                                        ProtosLanguage.ID,
                                        characters,
                                        "perf006-b2d3b-call-tag.protos")
                                .build();
                ProtosSemanticBytecodeRootNode root =
                        new CanonicalToBytecodeLowerer(
                                        language,
                                        source, ProtosExecutionMode.NORMAL)
                                .lowerRoot(
                                        canonicalize(characters));

                root.getRootNodes().ensureComplete();
                TagTree tagTree =
                        root.getBytecodeNode().getTagTree();
                List<TagTree> callTags =
                        collectTags(
                                tagTree,
                                StandardTags.CallTag.class);

                assertEquals(1, callTags.size());
                assertEquals(
                        characters,
                        callTags.get(0)
                                .getSourceSection()
                                .getCharacters()
                                .toString());
            } finally {
                context.leave();
            }
        }

        System.out.println("PERF006_B2D3B_CALL_TAG_COUNT=1");
        System.out.println("PERF006_B2D3B_CALL_TAG_SOURCE_EXACT=PASS");
        System.out.println("PERF006_B2D3B_NO_DUPLICATE_STANDARD_BRIDGE_TAG=PASS");
    }

    private static List<TagTree> collectTags(
            TagTree tree,
            Class<? extends com.oracle.truffle.api.instrumentation.Tag> tag) {
        java.util.ArrayList<TagTree> result =
                new java.util.ArrayList<>();
        if (tree == null) {
            return result;
        }
        if (tree.hasTag(tag)) {
            result.add(tree);
        }
        for (TagTree child : tree.getTreeChildren()) {
            result.addAll(collectTags(child, tag));
        }
        return result;
    }

    private static Object execute(
            ProtosLanguage language,
            ProtosActivation module,
            String characters,
            String sourceName)
            throws Exception {
        Source source =
                Source.newBuilder(
                                ProtosLanguage.ID,
                                characters,
                                sourceName)
                        .build();
        return new CanonicalToBytecodeLowerer(
                        language,
                        source, ProtosExecutionMode.NORMAL)
                .lowerRoot(canonicalize(characters))
                .getCallTarget()
                .call(module);
    }

    private static ProtosClosureValue sourceClosure(
            ProtosLanguage language,
            ProtosActivation creator,
            String characters,
            String sourceName)
            throws Exception {
        Source source =
                Source.newBuilder(
                                ProtosLanguage.ID,
                                characters,
                                sourceName)
                        .build();
        CanonicalClosure definition =
                closureDefinition(characters);
        return semanticClosure(
                definition,
                ProtosClosureExecutionPlan.bytecode(
                        definition,
                        language,
                        source, ProtosExecutionMode.NORMAL),
                creator);
    }

    private static ProtosClosureValue yieldingClosure(
            ProtosLanguage language,
            ProtosActivation creator,
            Object finalValue,
            String sourceName)
            throws Exception {
        String characters = "() => { null }";
        Source source =
                Source.newBuilder(
                                ProtosLanguage.ID,
                                characters,
                                sourceName)
                        .build();
        CanonicalClosure definition =
                closureDefinition(characters);
        ProtosSemanticBytecodeRootNode root =
                yieldingRoot(
                        language,
                        source,
                        definition.body().span(),
                        finalValue);
        return semanticClosure(
                definition,
                ProtosClosureExecutionPlan.bytecode(
                        definition,
                        language,
                        source,
                        root, ProtosExecutionMode.NORMAL),
                creator);
    }

    private static ProtosSemanticBytecodeRootNode yieldingRoot(
            ProtosLanguage language,
            Source source,
            SourceSpan span,
            Object finalValue) {
        BytecodeRootNodes<ProtosSemanticBytecodeRootNode> roots =
                ProtosSemanticBytecodeRootNodeGen.create(
                        language,
                        BytecodeConfig.DEFAULT,
                        builder -> {
                            builder.beginSource(source);
                            builder.beginSourceSection(
                                    span.startOffset(),
                                    span.length());
                            builder.beginRoot();
                            builder.beginYield();
                            builder.emitLoadArgument(0);
                            builder.endYield();
                            builder.beginReturn();
                            builder.emitLoadConstant(finalValue);
                            builder.endReturn();
                            builder.endRoot();
                            builder.endSourceSection();
                            builder.endSource();
                        });
        return roots.getNode(0);
    }

    private static ProtosClosureValue semanticClosure(
            CanonicalClosure definition,
            ProtosClosureExecutionPlan plan,
            ProtosActivation creator) {
        return new ProtosClosureValue(
                definition,
                creator.lexicalContextsForClosureCapture(),
                creator.receiver(),
                creator.methodHome().orElse(null),
                creator.returnHome().orElse(null),
                creator.prelude().orElseThrow(),
                plan);
    }

    private static CanonicalClosure closureDefinition(
            String characters) {
        return assertInstanceOf(
                CanonicalClosure.class,
                canonicalize(characters).expressions().get(0));
    }

    private static CanonicalSequence canonicalize(
            String characters) {
        return (CanonicalSequence)
                new Canonicalizer()
                        .canonicalize(
                                new ProtosParser(characters)
                                        .parseProgram());
    }

    private static ProtosActivation moduleActivation() {
        ProtosStandardObjectProtocol.install();
        ProtosObjectValue contextPrototype =
                new ProtosObjectValue(
                        ProtosObjectValue.rootObject());
        ProtosObjectValue bindings =
                new ProtosObjectValue(contextPrototype);
        bindings.createLocalSlot(
                "Context",
                contextPrototype);
        bindings.createLocalSlot(
                "Error",
                new ProtosObjectValue(
                        ProtosObjectValue.rootObject()));
        bindings.createLocalSlot(
                "Array",
                new ProtosObjectValue(
                        ProtosObjectValue.rootObject()));
        bindings.freeze();
        return new ProtosPrelude(
                        bindings,
                        contextPrototype)
                .newModuleActivation();
    }

    private static ProtosActivation activationOf(ContinuationResult continuation) {
        Object[] arguments = continuation.getFrame().getArguments();
        if (arguments.length == 0) {
            throw new AssertionError("ContinuationResult frame has no invocation activation");
        }
        return assertInstanceOf(ProtosActivation.class, arguments[0]);
    }

}
