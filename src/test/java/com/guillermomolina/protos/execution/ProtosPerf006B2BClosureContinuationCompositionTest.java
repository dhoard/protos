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
import com.guillermomolina.protos.runtime.ProtosObjectValue;
import com.guillermomolina.protos.runtime.ProtosPrelude;
import com.guillermomolina.protos.runtime.ProtosReturnHome;
import com.guillermomolina.protos.semantic.Canonicalizer;
import com.guillermomolina.protos.semantic.ast.CanonicalClosure;
import com.guillermomolina.protos.semantic.ast.CanonicalSequence;
import com.guillermomolina.protos.source.SourceSpan;
import com.oracle.truffle.api.bytecode.BytecodeConfig;
import com.oracle.truffle.api.bytecode.BytecodeRootNodes;
import com.oracle.truffle.api.bytecode.ContinuationResult;
import com.oracle.truffle.api.TruffleLanguage.LanguageReference;
import com.oracle.truffle.api.source.Source;
import java.util.List;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;

final class ProtosPerf006B2BClosureContinuationCompositionTest {
    private static final LanguageReference<ProtosLanguage> LANGUAGE_REF =
            LanguageReference.create(ProtosLanguage.class);

    @Test
    void sourceLevelBytecodeClosureCallsComposeRepeatedChildSuspensionWithoutReplay()
            throws Exception {
        try (Context context = Context.newBuilder(ProtosLanguage.ID).build()) {
            context.initialize(ProtosLanguage.ID);
            context.enter();
            try {
                ProtosLanguage language = LANGUAGE_REF.get(null);
                ProtosActivation module = moduleActivation();
                ProtosObjectValue finalValue =
                        new ProtosObjectValue(ProtosObjectValue.rootObject());

                String leafCharacters = "() => { null }";
                Source leafSource =
                        Source.newBuilder(
                                        ProtosLanguage.ID,
                                        leafCharacters,
                                        "perf006-b2b-leaf.protos")
                                .build();
                CanonicalClosure leafDefinition =
                        closureDefinition(leafCharacters);

                ProtosSemanticBytecodeRootNode syntheticLeafRoot =
                        yieldingLeafRoot(
                                language,
                                leafSource,
                                leafDefinition.body().span(),
                                finalValue);
                ProtosClosureExecutionPlan leafPlan =
                        ProtosClosureExecutionPlan.bytecode(
                                leafDefinition,
                                language,
                                leafSource,
                                syntheticLeafRoot, ProtosExecutionMode.NORMAL);
                ProtosClosureValue leaf =
                        semanticClosure(
                                leafDefinition,
                                leafPlan,
                                module);
                module.context().createLocalSlot("child", leaf);

                // PERF025: `^` keeps the caller-owned home observable, hence physical,
                // so its lifetime across repeated child suspension stays covered.
                String middleCharacters = "() => { ^child() }";
                Source middleSource =
                        Source.newBuilder(
                                        ProtosLanguage.ID,
                                        middleCharacters,
                                        "perf006-b2b-middle.protos")
                                .build();
                CanonicalClosure middleDefinition =
                        closureDefinition(middleCharacters);
                ProtosClosureExecutionPlan middlePlan =
                        ProtosClosureExecutionPlan.bytecode(
                                middleDefinition,
                                language,
                                middleSource, ProtosExecutionMode.NORMAL);
                ProtosClosureValue middle =
                        semanticClosure(
                                middleDefinition,
                                middlePlan,
                                module);
                module.context().createLocalSlot("entry", middle);

                String topCharacters = "entry()";
                Source topSource =
                        Source.newBuilder(
                                        ProtosLanguage.ID,
                                        topCharacters,
                                        "perf006-b2b-top.protos")
                                .build();
                CanonicalSequence topCanonical =
                        canonicalize(topCharacters);
                ProtosSemanticBytecodeRootNode topRoot =
                        new CanonicalToBytecodeLowerer(
                                        language,
                                        topSource, ProtosExecutionMode.NORMAL)
                                .lowerRoot(topCanonical);

                Object first = topRoot.getCallTarget().call(module);
                ContinuationResult topContinuation1 =
                        assertInstanceOf(
                                ContinuationResult.class,
                                first);
                ContinuationResult middleContinuation1 =
                        assertInstanceOf(
                                ContinuationResult.class,
                                topContinuation1.getResult());
                ContinuationResult leafContinuation1 =
                        assertInstanceOf(
                                ContinuationResult.class,
                                middleContinuation1.getResult());

                ProtosActivation middleActivation =
                        activationOf(middleContinuation1);
                ProtosActivation leafActivation =
                        activationOf(leafContinuation1);

                assertSame(
                        leafActivation,
                        activationOf(leafContinuation1),
                        "leaf yield retains its exact invocation activation in the continuation frame");
                assertTrue(middleActivation.ownsReturnHome());
                assertTrue(leafActivation.ownsReturnHome());
                ProtosReturnHome middleHome =
                        middleActivation.returnHome().orElseThrow();
                ProtosReturnHome leafHome =
                        leafActivation.returnHome().orElseThrow();
                assertTrue(middleHome.isActive());
                // PERF025: the hand-built leaf runs under a proven
                // return-home-unobservable `() => { null }` plan.
                assertFalse(leafHome.isMaterialized());

                Object second =
                        topContinuation1.continueWith(
                                com.guillermomolina.protos.runtime.ProtosNullValue.INSTANCE);
                ContinuationResult topContinuation2 =
                        assertInstanceOf(
                                ContinuationResult.class,
                                second);
                ContinuationResult middleContinuation2 =
                        assertInstanceOf(
                                ContinuationResult.class,
                                topContinuation2.getResult());
                ContinuationResult leafContinuation2 =
                        assertInstanceOf(
                                ContinuationResult.class,
                                middleContinuation2.getResult());

                /*
                 * If either caller had replayed from a fresh Closure invocation,
                 * these activation identities would differ. The same invocation
                 * frames survive through the second suspension.
                 */
                assertSame(
                        middleActivation,
                        activationOf(middleContinuation2));
                assertSame(
                        leafActivation,
                        activationOf(leafContinuation2));
                assertSame(
                        leafActivation,
                        activationOf(leafContinuation2));
                assertTrue(middleHome.isActive());
                // PERF025: the hand-built leaf runs under a proven
                // return-home-unobservable `() => { null }` plan.
                assertFalse(leafHome.isMaterialized());

                Object completed =
                        topContinuation2.continueWith(
                                com.guillermomolina.protos.runtime.ProtosNullValue.INSTANCE);

                assertSame(finalValue, completed);
                assertFalse(
                        leafHome.isMaterialized(),
                        "the leaf-owned home is never materialized");
                assertFalse(
                        middleHome.isActive(),
                        "caller-owned ReturnHome completes only after child completion");
            } finally {
                context.leave();
            }
        }

        System.out.println("PERF006_B2B_SOURCE_LEVEL_CALL_LOWERING=PASS");
        System.out.println("PERF006_B2B_NESTED_CONTINUATION_CHAIN=PASS");
        System.out.println("PERF006_B2B_REPEATED_SUSPENSION=PASS");
        System.out.println("PERF006_B2B_ACTIVATION_IDENTITY_NO_REPLAY=PASS");
        System.out.println("PERF006_B2B_RETURN_HOME_LIFETIME=PASS");
    }

    @Test
    void legacyClosureInvokerUsesTemporaryAstFallbackAfterDispatchCutover()
            throws Exception {
        try (Context context = Context.newBuilder(ProtosLanguage.ID).build()) {
            context.initialize(ProtosLanguage.ID);
            context.enter();
            try {
                ProtosLanguage language = LANGUAGE_REF.get(null);
                ProtosActivation module = moduleActivation();
                String characters = "() => null";
                Source source =
                        Source.newBuilder(
                                        ProtosLanguage.ID,
                                        characters,
                                        "perf006-b2b-old-dispatch.protos")
                                .build();
                CanonicalClosure definition =
                        closureDefinition(characters);
                ProtosClosureExecutionPlan plan =
                        ProtosClosureExecutionPlan.bytecode(
                                definition,
                                language,
                                source, ProtosExecutionMode.NORMAL);
                ProtosClosureValue closure =
                        semanticClosure(definition, plan, module);

                Object result =
                        ProtosClosureInvoker.invoke(
                                closure,
                                List.of(),
                                module);

                assertSame(
                        com.guillermomolina.protos.runtime.ProtosNullValue.INSTANCE,
                        result);
                assertSame(
                        plan,
                        closure.executionPlan().orElseThrow(),
                        "temporary AST fallback must not replace the semantic Bytecode plan");
            } finally {
                context.leave();
            }
        }

        System.out.println(
                "PERF006_B2B_LEGACY_DISPATCH_TEMPORARY_AST_FALLBACK=PASS");
        System.out.println(
                "PERF006_B2B_SEMANTIC_BYTECODE_PLAN_PRESERVED=PASS");
    }

    @Test
    void defaultSpreadLowersAfterDefaultSpreadMigration()
            throws Exception {
        try (Context context = Context.newBuilder(ProtosLanguage.ID).build()) {
            context.initialize(ProtosLanguage.ID);
            context.enter();
            try {
                ProtosLanguage language = LANGUAGE_REF.get(null);
                String characters =
                        "(value = entry(...items)) => { value }";
                CanonicalClosure definition =
                        closureDefinition(characters);
                Source source =
                        Source.newBuilder(
                                        ProtosLanguage.ID,
                                        characters,
                                        "perf006-b2b-default-spread-deferred.protos")
                                .build();

                new ProtosBytecodeClosureExecutionPlan(
                                                definition,
                                                language,
                                                source, ProtosExecutionMode.NORMAL);
            } finally {
                context.leave();
            }
        }

        System.out.println(
                "PERF006_B2B_DEFAULT_SPREAD_LOWERING=PASS");
    }

    private static ProtosSemanticBytecodeRootNode yieldingLeafRoot(
            ProtosLanguage language,
            Source source,
            SourceSpan bodySpan,
            Object finalValue) {
        BytecodeRootNodes<ProtosSemanticBytecodeRootNode> roots =
                ProtosSemanticBytecodeRootNodeGen.create(
                        language,
                        BytecodeConfig.DEFAULT,
                        builder -> {
                            builder.beginSource(source);
                            builder.beginSourceSection(
                                    bodySpan.startOffset(),
                                    bodySpan.length());
                            builder.beginRoot();

                            builder.beginYield();
                            builder.emitLoadArgument(0);
                            builder.endYield();

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

    private static ProtosActivation activationOf(
            ContinuationResult continuation) {
        Object[] arguments = continuation.getFrame().getArguments();
        assertTrue(arguments.length > 0);
        return assertInstanceOf(
                ProtosActivation.class,
                arguments[0]);
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
        CanonicalSequence sequence = canonicalize(characters);
        assertEquals(1, sequence.expressions().size());
        return assertInstanceOf(
                CanonicalClosure.class,
                sequence.expressions().get(0));
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
                new ProtosObjectValue(ProtosObjectValue.rootObject());
        ProtosObjectValue bindings =
                new ProtosObjectValue(contextPrototype);
        bindings.createLocalSlot("Context", contextPrototype);
        bindings.createLocalSlot(
                "Error",
                new ProtosObjectValue(
                        ProtosObjectValue.rootObject()));
        bindings.createLocalSlot(
                "Array",
                new ProtosObjectValue(
                        ProtosObjectValue.rootObject()));
        bindings.freeze();
        ProtosPrelude prelude =
                new ProtosPrelude(bindings, contextPrototype);
        return prelude.newModuleActivation();
    }
}
