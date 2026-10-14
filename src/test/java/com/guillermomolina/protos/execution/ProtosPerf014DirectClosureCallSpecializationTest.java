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

import com.guillermomolina.protos.parser.ProtosParser;
import com.guillermomolina.protos.runtime.ProtosActivation;
import com.guillermomolina.protos.runtime.ProtosClosureValue;
import com.guillermomolina.protos.runtime.ProtosObjectValue;
import com.guillermomolina.protos.runtime.ProtosPrelude;
import com.guillermomolina.protos.semantic.Canonicalizer;
import com.guillermomolina.protos.semantic.ast.CanonicalClosure;
import com.guillermomolina.protos.semantic.ast.CanonicalSequence;
import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.TruffleLanguage.LanguageReference;
import com.oracle.truffle.api.source.Source;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;

/**
 * PERF014 regression coverage for the guarded direct Closure-call
 * specialization ({@code PrepareClosureCall}, {@code PrepareClosureCallArguments},
 * {@code PrepareDefaultClosureCallArguments}).
 *
 * <p>These tests establish semantic equivalence between the new guarded fast
 * hit and the exact existing generic {@code prepareClosureCall} fallback for
 * direct Closure-call syntax ({@code operation()}), mirroring the
 * evidentiary style already established by
 * {@link ProtosPerf010APreparedTargetSpecializationTest} for the analogous
 * ordinary-send specialization: repeated monomorphic invocation, admission
 * limited to the exact canonical {@code Object.call} selection, and
 * authoritative D013 lookup observing delegation-visible changes
 * (shadowing/override, replacement, removal) after the fast specialization
 * has already been populated. A closure carrying a native body, or one whose
 * {@code call} selection is not exactly the canonical root implementation,
 * never admits the fast hit by construction and is exercised here purely as
 * exact-generic-fallback regression coverage.
 */
final class ProtosPerf014DirectClosureCallSpecializationTest {
    private static final LanguageReference<ProtosLanguage> LANGUAGE_REF =
            LanguageReference.create(ProtosLanguage.class);

    @Test
    void monomorphicDirectClosureCallReturnsExpectedResultAcrossRepeatedInvocations()
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
                module.context().createLocalSlot(
                        "identity",
                        sourceClosure(
                                language,
                                module,
                                "() => { marker }",
                                "perf014-zero-arg-identity.protos"));

                RootCallTarget callTarget =
                        lower(language, "identity()", "perf014-direct-call.protos");

                for (int i = 0; i < 5; i++) {
                    assertSame(marker, callTarget.call(module));
                }
            } finally {
                context.leave();
            }
        }

        System.out.println("PERF014_MONOMORPHIC_DIRECT_CALL_RESULT=PASS");
    }

    @Test
    void freshClosureMaterializationsOfTheSameDefinitionRemainCorrectPastBothCacheLimits()
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

                String methodCharacters = "() => { marker }";
                Source methodSource =
                        Source.newBuilder(
                                        ProtosLanguage.ID,
                                        methodCharacters,
                                        "perf014-churn-identity.protos")
                                .build();
                CanonicalClosure methodDefinition = closureDefinition(methodCharacters);
                ProtosClosureExecutionPlan sharedTemplate =
                        ProtosClosureExecutionPlan.bytecode(
                                methodDefinition, language, methodSource, ProtosExecutionMode.NORMAL);

                RootCallTarget callTarget =
                        lower(language, "identity()", "perf014-churn-call.protos");

                // Same D013-selected canonical `call` behavior on every
                // iteration (admitted by the definition-keyed second tier),
                // but a fresh ProtosClosureValue materialization of that same
                // definition every time, exercising more iterations than
                // either cache tier's limit. This reproduces the
                // receiver-identity-churn regression observed on the Protos
                // test suite: without a definition-keyed tier, every distinct
                // instance re-paid the full specialization-establishment
                // cost (a fresh guarded lookup and Assumption) instead of
                // ever converging to a cheap steady state.
                for (int i = 0; i < 10; i++) {
                    ProtosClosureValue identity =
                            semanticClosure(methodDefinition, sharedTemplate, module);
                    if (i == 0) {
                        module.context().createLocalSlot("identity", identity);
                    } else {
                        module.context().assignLocalSlot("identity", identity);
                    }

                    assertSame(marker, callTarget.call(module));
                }
            } finally {
                context.leave();
            }
        }

        System.out.println("PERF014_FRESH_MATERIALIZATION_IDENTITY_CHURN_STABLE=PASS");
    }

    @Test
    void argumentBearingDirectClosureCallRemainsCorrectAcrossRepeatedInvocations()
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
                module.context().createLocalSlot(
                        "identity",
                        sourceClosure(
                                language,
                                module,
                                "(value) => { value }",
                                "perf014-arg-identity.protos"));

                RootCallTarget callTarget =
                        lower(language, "identity(marker)", "perf014-arg-call.protos");

                for (int i = 0; i < 5; i++) {
                    assertSame(marker, callTarget.call(module));
                }
            } finally {
                context.leave();
            }
        }

        System.out.println("PERF014_ARGUMENT_BEARING_DIRECT_CALL_RESULT=PASS");
    }

    @Test
    void defaultArgumentDirectClosureCallRemainsCorrect() throws Exception {
        try (Context context = Context.newBuilder(ProtosLanguage.ID).build()) {
            context.initialize(ProtosLanguage.ID);
            context.enter();
            try {
                ProtosLanguage language = LANGUAGE_REF.get(null);
                ProtosActivation module = moduleActivation();
                ProtosObjectValue marker =
                        new ProtosObjectValue(ProtosObjectValue.rootObject());
                module.context().createLocalSlot("marker", marker);
                module.context().createLocalSlot(
                        "identity",
                        sourceClosure(
                                language,
                                module,
                                "() => { marker }",
                                "perf014-default-identity.protos"));

                String characters = "(value = identity()) => { value }";
                Source source =
                        Source.newBuilder(
                                        ProtosLanguage.ID,
                                        characters,
                                        "perf014-default-call.protos")
                                .build();
                CanonicalClosure definition = closureDefinition(characters);
                ProtosClosureValue semantic =
                        semanticClosure(
                                definition,
                                ProtosClosureExecutionPlan.bytecode(
                                        definition, language, source, ProtosExecutionMode.NORMAL),
                                module);
                ProtosActivation invocation =
                        ProtosActivation.forClosureInvocation(
                                semantic,
                                List.of(),
                                module.prelude().orElseThrow(),
                                module.actorModuleState(),
                                module.currentModuleKey().orElse(null),
                                module.executionDomain());

                Object result =
                        new ProtosBytecodeClosureExecutionPlan(definition, language, source, ProtosExecutionMode.NORMAL)
                                .executeActivation(invocation);

                assertSame(marker, result);
            } finally {
                context.leave();
            }
        }

        System.out.println("PERF014_DEFAULT_ARGUMENT_DIRECT_CALL_RESULT=PASS");
    }

    @Test
    void localCallOverrideOnClosureReceiverShadowsCanonicalFastPathAdmission()
            throws Exception {
        try (Context context = Context.newBuilder(ProtosLanguage.ID).build()) {
            context.initialize(ProtosLanguage.ID);
            context.enter();
            try {
                ProtosLanguage language = LANGUAGE_REF.get(null);
                ProtosActivation module = moduleActivation();
                ProtosObjectValue marker =
                        new ProtosObjectValue(ProtosObjectValue.rootObject());
                ProtosObjectValue overrideMarker =
                        new ProtosObjectValue(ProtosObjectValue.rootObject());
                module.context().createLocalSlot("marker", marker);
                module.context().createLocalSlot("overrideMarker", overrideMarker);

                ProtosClosureValue identity =
                        sourceClosure(
                                language,
                                module,
                                "() => { marker }",
                                "perf014-shadow-identity.protos");
                identity.createLocalSlot(
                        "call",
                        sourceClosure(
                                language,
                                module,
                                "() => { overrideMarker }",
                                "perf014-shadow-override.protos"));
                module.context().createLocalSlot("identity", identity);

                RootCallTarget callTarget =
                        lower(language, "identity()", "perf014-shadow-call.protos");

                for (int i = 0; i < 3; i++) {
                    assertSame(overrideMarker, callTarget.call(module));
                }
            } finally {
                context.leave();
            }
        }

        System.out.println("PERF014_LOCAL_CALL_OVERRIDE_SHADOWS_CANONICAL_FAST_PATH=PASS");
    }

    @Test
    void introducingCallOverrideAfterCacheWarmupInvalidatesTheCanonicalFastHit()
            throws Exception {
        try (Context context = Context.newBuilder(ProtosLanguage.ID).build()) {
            context.initialize(ProtosLanguage.ID);
            context.enter();
            try {
                ProtosLanguage language = LANGUAGE_REF.get(null);
                ProtosActivation module = moduleActivation();
                ProtosObjectValue marker =
                        new ProtosObjectValue(ProtosObjectValue.rootObject());
                ProtosObjectValue overrideMarker =
                        new ProtosObjectValue(ProtosObjectValue.rootObject());
                module.context().createLocalSlot("marker", marker);
                module.context().createLocalSlot("overrideMarker", overrideMarker);

                ProtosClosureValue identity =
                        sourceClosure(
                                language,
                                module,
                                "() => { marker }",
                                "perf014-invalidate-identity.protos");
                module.context().createLocalSlot("identity", identity);

                RootCallTarget callTarget =
                        lower(language, "identity()", "perf014-invalidate-call.protos");

                for (int i = 0; i < 3; i++) {
                    assertSame(marker, callTarget.call(module));
                }

                identity.createLocalSlot(
                        "call",
                        sourceClosure(
                                language,
                                module,
                                "() => { overrideMarker }",
                                "perf014-invalidate-override.protos"));

                for (int i = 0; i < 3; i++) {
                    assertSame(overrideMarker, callTarget.call(module));
                }
            } finally {
                context.leave();
            }
        }

        System.out.println("PERF014_CALL_OVERRIDE_AFTER_WARMUP_INVALIDATES_FAST_HIT=PASS");
    }

    @Test
    void removingAndRecreatingCallOverrideCyclesCorrectlyThroughGuardedAndGenericPaths()
            throws Exception {
        try (Context context = Context.newBuilder(ProtosLanguage.ID).build()) {
            context.initialize(ProtosLanguage.ID);
            context.enter();
            try {
                ProtosLanguage language = LANGUAGE_REF.get(null);
                ProtosActivation module = moduleActivation();
                ProtosObjectValue marker =
                        new ProtosObjectValue(ProtosObjectValue.rootObject());
                ProtosObjectValue firstOverrideMarker =
                        new ProtosObjectValue(ProtosObjectValue.rootObject());
                ProtosObjectValue secondOverrideMarker =
                        new ProtosObjectValue(ProtosObjectValue.rootObject());
                module.context().createLocalSlot("marker", marker);
                module.context().createLocalSlot("firstOverrideMarker", firstOverrideMarker);
                module.context().createLocalSlot("secondOverrideMarker", secondOverrideMarker);

                ProtosClosureValue identity =
                        sourceClosure(
                                language,
                                module,
                                "() => { marker }",
                                "perf014-cycle-identity.protos");
                identity.createLocalSlot(
                        "call",
                        sourceClosure(
                                language,
                                module,
                                "() => { firstOverrideMarker }",
                                "perf014-cycle-first-override.protos"));
                module.context().createLocalSlot("identity", identity);

                RootCallTarget callTarget =
                        lower(language, "identity()", "perf014-cycle-call.protos");

                for (int i = 0; i < 3; i++) {
                    assertSame(firstOverrideMarker, callTarget.call(module));
                }

                identity.removeLocalSlot("call");

                for (int i = 0; i < 3; i++) {
                    assertSame(marker, callTarget.call(module));
                }

                identity.createLocalSlot(
                        "call",
                        sourceClosure(
                                language,
                                module,
                                "() => { secondOverrideMarker }",
                                "perf014-cycle-second-override.protos"));

                for (int i = 0; i < 3; i++) {
                    assertSame(secondOverrideMarker, callTarget.call(module));
                }
            } finally {
                context.leave();
            }
        }

        System.out.println("PERF014_REMOVE_RECREATE_CALL_OVERRIDE_CYCLE=PASS");
    }

    @Test
    void nativeDirectClosureCallRemainsOnGenericPathAcrossRepeatedInvocations()
            throws Exception {
        try (Context context = Context.newBuilder(ProtosLanguage.ID).build()) {
            context.initialize(ProtosLanguage.ID);
            context.enter();
            try {
                ProtosLanguage language = LANGUAGE_REF.get(null);
                ProtosActivation module = moduleActivation();
                ProtosObjectValue marker =
                        new ProtosObjectValue(ProtosObjectValue.rootObject());
                AtomicInteger invocations = new AtomicInteger();
                module.context().createLocalSlot("marker", marker);
                module.context().createLocalSlot(
                        "echo",
                        ProtosClosureValue.nativeClosure(
                                (activation, supplied) -> {
                                    invocations.incrementAndGet();
                                    return marker;
                                }));

                RootCallTarget callTarget =
                        lower(language, "echo()", "perf014-native-direct-call.protos");

                for (int i = 0; i < 5; i++) {
                    assertSame(marker, callTarget.call(module));
                }
                assertEquals(5, invocations.get());
            } finally {
                context.leave();
            }
        }

        System.out.println("PERF014_NATIVE_DIRECT_CALL_UNAFFECTED=PASS");
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
        ProtosStandardObjectProtocol.install();
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
