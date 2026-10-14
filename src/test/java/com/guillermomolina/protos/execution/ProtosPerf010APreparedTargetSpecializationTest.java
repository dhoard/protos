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
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.guillermomolina.protos.parser.ProtosParser;
import com.guillermomolina.protos.runtime.ProtosActivation;
import com.guillermomolina.protos.runtime.ProtosClosureValue;
import com.guillermomolina.protos.runtime.ProtosNullValue;
import com.guillermomolina.protos.runtime.ProtosObjectValue;
import com.guillermomolina.protos.runtime.ProtosPrelude;
import com.guillermomolina.protos.semantic.Canonicalizer;
import com.guillermomolina.protos.semantic.ast.CanonicalClosure;
import com.guillermomolina.protos.semantic.ast.CanonicalSequence;
import com.guillermomolina.protos.source.SourceSpan;
import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.TruffleLanguage.LanguageReference;
import com.oracle.truffle.api.bytecode.BytecodeConfig;
import com.oracle.truffle.api.bytecode.BytecodeRootNodes;
import com.oracle.truffle.api.bytecode.ContinuationResult;
import com.oracle.truffle.api.source.Source;
import java.util.concurrent.atomic.AtomicInteger;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;

/**
 * PERF010-A regression coverage for the {@code PrepareSendArguments}
 * prepared-target specialization.
 *
 * <p>These tests establish semantic equivalence between the new
 * {@code fastOrdinarySend} fast hit and the exact existing generic
 * {@code perform} fallback for ordinary, non-native, source-backed sends:
 * repeated monomorphic invocation, fresh per-call activation, exact
 * receiver/methodHome, and authoritative D013 lookup observing
 * delegation-visible changes (replacement, shadowing, removal) after the
 * fast specialization has already been populated. Native Closure and
 * canonical standard-import behavior are unaffected by construction (the
 * fast specialization's guard admits only a non-native selected Closure,
 * and {@code ProtosStandardImportProtocol.selectedRuntimeForBytecodeIntrinsic}
 * only recognizes a native {@code StandardImportBody}), so their existing
 * regression coverage elsewhere remains the applicable net; one native-send
 * regression test is retained here to confirm repeated native sends through
 * the same call site are unaffected.
 */
final class ProtosPerf010APreparedTargetSpecializationTest {
    private static final LanguageReference<ProtosLanguage> LANGUAGE_REF =
            LanguageReference.create(ProtosLanguage.class);

    @Test
    void monomorphicOrdinarySendReturnsExpectedResultAcrossRepeatedInvocations()
            throws Exception {
        try (Context context = Context.newBuilder(ProtosLanguage.ID).build()) {
            context.initialize(ProtosLanguage.ID);
            context.enter();
            try {
                ProtosLanguage language = LANGUAGE_REF.get(null);
                ProtosActivation module = moduleActivation();
                ProtosObjectValue receiver =
                        new ProtosObjectValue(ProtosObjectValue.rootObject());
                ProtosObjectValue marker =
                        new ProtosObjectValue(ProtosObjectValue.rootObject());

                receiver.createLocalSlot(
                        "identity",
                        sourceClosure(
                                language,
                                module,
                                "(value) => { value }",
                                "perf010a-identity.protos"));
                module.context().createLocalSlot("receiver", receiver);
                module.context().createLocalSlot("marker", marker);

                RootCallTarget callTarget =
                        lower(
                                language,
                                "receiver.identity(marker)",
                                "perf010a-monomorphic-send.protos");

                for (int i = 0; i < 5; i++) {
                    assertSame(marker, callTarget.call(module));
                }
            } finally {
                context.leave();
            }
        }

        System.out.println("PERF010A_MONOMORPHIC_SEND_RESULT=PASS");
    }

    @Test
    void freshClosureAndReceiverMaterializationsOfTheSameDefinitionRemainCorrectPastTheCacheLimit()
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

                String methodCharacters = "(value) => { value }";
                Source methodSource =
                        Source.newBuilder(
                                        ProtosLanguage.ID,
                                        methodCharacters,
                                        "perf010a-churn-identity.protos")
                                .build();
                CanonicalClosure methodDefinition = closureDefinition(methodCharacters);
                ProtosClosureExecutionPlan sharedTemplate =
                        ProtosClosureExecutionPlan.bytecode(
                                methodDefinition, language, methodSource, ProtosExecutionMode.NORMAL);

                RootCallTarget callTarget =
                        lower(
                                language,
                                "receiver.identity(marker)",
                                "perf010a-churn-send.protos");

                // Same D013-selected executable behavior (methodDefinition,
                // via the shared template plan) on every iteration, but a
                // fresh receiver, fresh ProtosClosureValue and fresh
                // methodHome each time, exercising more iterations than the
                // fastOrdinarySend cache limit. This reproduces the
                // PERF010-A identity-churn scenario: the selected Closure
                // and its home differ by identity every hit even though D013
                // keeps selecting the same executable behavior.
                for (int i = 0; i < 10; i++) {
                    ProtosActivation creator = moduleActivation();
                    ProtosObjectValue receiver =
                            new ProtosObjectValue(ProtosObjectValue.rootObject());
                    ProtosClosureValue closure =
                            semanticClosure(methodDefinition, sharedTemplate, creator);
                    receiver.createLocalSlot("identity", closure);
                    if (i == 0) {
                        module.context().createLocalSlot("receiver", receiver);
                    } else {
                        module.context().assignLocalSlot("receiver", receiver);
                    }

                    assertSame(marker, callTarget.call(module));
                }
            } finally {
                context.leave();
            }
        }

        System.out.println("PERF010A_FRESH_MATERIALIZATION_IDENTITY_CHURN_STABLE=PASS");
    }

    @Test
    void repeatedSuspendingSendGetsFreshActivationAndExactMethodHomeEachHit()
            throws Exception {
        try (Context context = Context.newBuilder(ProtosLanguage.ID).build()) {
            context.initialize(ProtosLanguage.ID);
            context.enter();
            try {
                ProtosLanguage language = LANGUAGE_REF.get(null);
                ProtosActivation module = moduleActivation();
                ProtosObjectValue receiver =
                        new ProtosObjectValue(ProtosObjectValue.rootObject());
                ProtosObjectValue marker =
                        new ProtosObjectValue(ProtosObjectValue.rootObject());

                String methodCharacters = "() => { null }";
                Source methodSource =
                        Source.newBuilder(
                                        ProtosLanguage.ID,
                                        methodCharacters,
                                        "perf010a-suspending-method.protos")
                                .build();
                CanonicalClosure methodDefinition =
                        closureDefinition(methodCharacters);
                ProtosSemanticBytecodeRootNode methodRoot =
                        yieldingMethodRoot(
                                language,
                                methodSource,
                                methodDefinition.body().span(),
                                marker);
                ProtosClosureValue method =
                        semanticClosure(
                                methodDefinition,
                                ProtosClosureExecutionPlan.bytecode(
                                        methodDefinition,
                                        language,
                                        methodSource,
                                        methodRoot, ProtosExecutionMode.NORMAL),
                                module);
                receiver.createLocalSlot("pick", method);
                module.context().createLocalSlot("receiver", receiver);

                RootCallTarget callTarget =
                        lower(
                                language,
                                "receiver.pick()",
                                "perf010a-suspending-send.protos");

                ProtosActivation firstActivation =
                        firstYield(callTarget, module, receiver, marker);
                ProtosActivation secondActivation =
                        firstYield(callTarget, module, receiver, marker);

                assertNotSame(firstActivation, secondActivation);
            } finally {
                context.leave();
            }
        }

        System.out.println("PERF010A_FRESH_ACTIVATION_PER_HIT=PASS");
        System.out.println("PERF010A_METHOD_HOME_EXACT_PER_HIT=PASS");
    }

    @Test
    void replacingSelectedClosureAfterCacheWarmupMissesTheStaleHit()
            throws Exception {
        try (Context context = Context.newBuilder(ProtosLanguage.ID).build()) {
            context.initialize(ProtosLanguage.ID);
            context.enter();
            try {
                ProtosLanguage language = LANGUAGE_REF.get(null);
                ProtosActivation module = moduleActivation();
                ProtosObjectValue receiver =
                        new ProtosObjectValue(ProtosObjectValue.rootObject());
                ProtosObjectValue firstMarker =
                        new ProtosObjectValue(ProtosObjectValue.rootObject());
                ProtosObjectValue secondMarker =
                        new ProtosObjectValue(ProtosObjectValue.rootObject());

                module.context().createLocalSlot("firstMarker", firstMarker);
                module.context().createLocalSlot("secondMarker", secondMarker);
                receiver.createLocalSlot(
                        "identity",
                        sourceClosure(
                                language,
                                module,
                                "() => { firstMarker }",
                                "perf010a-replace-before.protos"));
                module.context().createLocalSlot("receiver", receiver);

                RootCallTarget callTarget =
                        lower(
                                language,
                                "receiver.identity()",
                                "perf010a-replace-send.protos");

                for (int i = 0; i < 3; i++) {
                    assertSame(firstMarker, callTarget.call(module));
                }

                receiver.assignLocalSlot(
                        "identity",
                        sourceClosure(
                                language,
                                module,
                                "() => { secondMarker }",
                                "perf010a-replace-after.protos"));

                for (int i = 0; i < 3; i++) {
                    assertSame(secondMarker, callTarget.call(module));
                }
            } finally {
                context.leave();
            }
        }

        System.out.println("PERF010A_REPLACED_SELECTION_MISSES_STALE_HIT=PASS");
    }

    @Test
    void removingNearerOverrideAfterCacheWarmupFallsBackToDelegationParent()
            throws Exception {
        try (Context context = Context.newBuilder(ProtosLanguage.ID).build()) {
            context.initialize(ProtosLanguage.ID);
            context.enter();
            try {
                ProtosLanguage language = LANGUAGE_REF.get(null);
                ProtosActivation module = moduleActivation();
                ProtosObjectValue parent =
                        new ProtosObjectValue(ProtosObjectValue.rootObject());
                ProtosObjectValue receiver = new ProtosObjectValue(parent);
                ProtosObjectValue parentMarker =
                        new ProtosObjectValue(ProtosObjectValue.rootObject());
                ProtosObjectValue overrideMarker =
                        new ProtosObjectValue(ProtosObjectValue.rootObject());

                module.context().createLocalSlot("parentMarker", parentMarker);
                module.context().createLocalSlot("overrideMarker", overrideMarker);
                parent.createLocalSlot(
                        "identity",
                        sourceClosure(
                                language,
                                module,
                                "() => { parentMarker }",
                                "perf010a-shadow-parent.protos"));
                receiver.createLocalSlot(
                        "identity",
                        sourceClosure(
                                language,
                                module,
                                "() => { overrideMarker }",
                                "perf010a-shadow-override.protos"));
                module.context().createLocalSlot("receiver", receiver);

                RootCallTarget callTarget =
                        lower(
                                language,
                                "receiver.identity()",
                                "perf010a-shadow-send.protos");

                for (int i = 0; i < 3; i++) {
                    assertSame(overrideMarker, callTarget.call(module));
                }

                receiver.removeLocalSlot("identity");

                for (int i = 0; i < 3; i++) {
                    assertSame(parentMarker, callTarget.call(module));
                }
            } finally {
                context.leave();
            }
        }

        System.out.println("PERF010A_REMOVED_OVERRIDE_OBSERVES_DELEGATION_PARENT=PASS");
    }

    @Test
    void nativeClosureSendRemainsOnGenericPathAcrossRepeatedInvocations()
            throws Exception {
        try (Context context = Context.newBuilder(ProtosLanguage.ID).build()) {
            context.initialize(ProtosLanguage.ID);
            context.enter();
            try {
                ProtosLanguage language = LANGUAGE_REF.get(null);
                ProtosActivation module = moduleActivation();
                ProtosObjectValue receiver =
                        new ProtosObjectValue(ProtosObjectValue.rootObject());
                ProtosObjectValue marker =
                        new ProtosObjectValue(ProtosObjectValue.rootObject());
                AtomicInteger invocations = new AtomicInteger();

                receiver.createLocalSlot(
                        "echo",
                        ProtosClosureValue.nativeClosure(
                                (activation, supplied) -> {
                                    invocations.incrementAndGet();
                                    return supplied.get(0);
                                }));
                module.context().createLocalSlot("receiver", receiver);
                module.context().createLocalSlot("marker", marker);

                RootCallTarget callTarget =
                        lower(
                                language,
                                "receiver.echo(marker)",
                                "perf010a-native-send.protos");

                for (int i = 0; i < 5; i++) {
                    assertSame(marker, callTarget.call(module));
                }
                assertEquals(5, invocations.get());
            } finally {
                context.leave();
            }
        }

        System.out.println("PERF010A_NATIVE_SEND_UNAFFECTED=PASS");
    }

    private static ProtosActivation firstYield(
            RootCallTarget callTarget,
            ProtosActivation module,
            ProtosObjectValue receiver,
            ProtosObjectValue marker) {
        Object first = callTarget.call(module);
        ContinuationResult parent =
                assertInstanceOf(ContinuationResult.class, first);
        ContinuationResult child =
                assertInstanceOf(ContinuationResult.class, parent.getResult());
        ProtosActivation activation = activationOf(child);
        assertSame(receiver, activation.receiver());
        assertSame(receiver, activation.methodHome().orElseThrow());
        // PERF025: the `() => { null }` plan is proven return-home unobservable.
        assertTrue(activation.ownsReturnHome());
        assertTrue(!activation.returnHome().orElseThrow().isMaterialized());
        Object completed = parent.continueWith(ProtosNullValue.INSTANCE);
        assertSame(marker, completed);
        return activation;
    }

    private static RootCallTarget lower(
            ProtosLanguage language,
            String characters,
            String sourceName)
            throws Exception {
        Source source =
                Source.newBuilder(ProtosLanguage.ID, characters, sourceName)
                        .build();
        return new CanonicalToBytecodeLowerer(language, source, ProtosExecutionMode.NORMAL)
                .lowerRoot(canonicalize(characters))
                .getCallTarget();
    }

    private static ProtosClosureValue sourceClosure(
            ProtosLanguage language,
            ProtosActivation creator,
            String characters,
            String sourceName)
            throws Exception {
        Source source =
                Source.newBuilder(ProtosLanguage.ID, characters, sourceName)
                        .build();
        CanonicalClosure definition = closureDefinition(characters);
        return semanticClosure(
                definition,
                ProtosClosureExecutionPlan.bytecode(definition, language, source, ProtosExecutionMode.NORMAL),
                creator);
    }

    private static ProtosSemanticBytecodeRootNode yieldingMethodRoot(
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

    private static ProtosActivation activationOf(ContinuationResult continuation) {
        Object[] arguments = continuation.getFrame().getArguments();
        assertTrue(arguments.length > 0);
        return assertInstanceOf(ProtosActivation.class, arguments[0]);
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

    private static CanonicalClosure closureDefinition(String characters) {
        return assertInstanceOf(
                CanonicalClosure.class,
                canonicalize(characters).expressions().get(0));
    }

    private static CanonicalSequence canonicalize(String characters) {
        return (CanonicalSequence)
                new Canonicalizer()
                        .canonicalize(new ProtosParser(characters).parseProgram());
    }

    private static ProtosActivation moduleActivation() {
        ProtosObjectValue contextPrototype =
                new ProtosObjectValue(ProtosObjectValue.rootObject());
        ProtosObjectValue bindings = new ProtosObjectValue(contextPrototype);
        bindings.createLocalSlot("Context", contextPrototype);
        bindings.createLocalSlot(
                "Error",
                new ProtosObjectValue(ProtosObjectValue.rootObject()));
        bindings.createLocalSlot(
                "Array",
                new ProtosObjectValue(ProtosObjectValue.rootObject()));
        bindings.freeze();
        return new ProtosPrelude(bindings, contextPrototype).newModuleActivation();
    }
}
