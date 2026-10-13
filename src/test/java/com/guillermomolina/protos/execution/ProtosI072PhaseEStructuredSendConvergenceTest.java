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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.guillermomolina.protos.parser.ProtosParser;
import com.guillermomolina.protos.runtime.ProtosActivation;
import com.guillermomolina.protos.runtime.ProtosActorExecutionDomain;
import com.guillermomolina.protos.runtime.ProtosActorModuleState;
import com.guillermomolina.protos.runtime.ProtosArrayValue;
import com.guillermomolina.protos.runtime.ProtosBooleanValue;
import com.guillermomolina.protos.runtime.ProtosClosureValue;
import com.guillermomolina.protos.runtime.ProtosCoreErrors;
import com.guillermomolina.protos.runtime.ProtosIntegerValue;
import com.guillermomolina.protos.runtime.ProtosMapValue;
import com.guillermomolina.protos.runtime.ProtosNullValue;
import com.guillermomolina.protos.runtime.ProtosObjectValue;
import com.guillermomolina.protos.runtime.ProtosPrelude;
import com.guillermomolina.protos.runtime.ProtosValueLookup;
import com.guillermomolina.protos.semantic.Canonicalizer;
import com.guillermomolina.protos.semantic.ast.CanonicalClosure;
import com.guillermomolina.protos.semantic.ast.CanonicalSequence;
import com.oracle.truffle.api.TruffleLanguage.LanguageReference;
import com.oracle.truffle.api.source.Source;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;

/**
 * I072 Phase E: proves the new {@code guardedStructuredSend} specialization
 * in {@code PrepareSendArguments} converges canonical standard-control sends
 * onto a stable structured Truffle path without weakening the Phase A
 * invalidation contract. Each test warms the guarded specialization with
 * several canonical hits at one call site, then mutates the exact selected
 * slot and re-executes the same call site, proving the stale structured hit
 * does not run and the mutated ordinary behavior is observed instead.
 */
final class ProtosI072PhaseEStructuredSendConvergenceTest {
    private static final LanguageReference<ProtosLanguage> LANGUAGE_REF =
            LanguageReference.create(ProtosLanguage.class);

    @Test
    void warmedCanonicalEnsureHitStopsAfterNearerOverrideAddition() throws Exception {
        try (LanguageScope scope = languageScope()) {
            ProtosPrelude prelude = core();
            ProtosActorExecutionDomain domain = new ProtosActorExecutionDomain();
            ProtosActivation module = activation(prelude, domain);
            ProtosObjectValue token = new ProtosObjectValue(ProtosObjectValue.rootObject());
            ProtosObjectValue overrideResult = new ProtosObjectValue(ProtosObjectValue.rootObject());
            AtomicInteger cleanups = new AtomicInteger();
            AtomicInteger overrideCalls = new AtomicInteger();

            ProtosClosureValue body = nativeClosure((activation, supplied) -> token);
            module.context().createLocalSlot("body", body);
            module.context().createLocalSlot(
                    "cleanup",
                    nativeClosure(
                            (activation, supplied) -> {
                                cleanups.incrementAndGet();
                                return ProtosNullValue.INSTANCE;
                            }));

            ProtosSemanticBytecodeRootNode root =
                    lowerRoot(
                            scope.language(),
                            "body.ensure(cleanup)",
                            "i072-phase-e-ensure-warm.protos");

            for (int i = 0; i < 4; i++) {
                assertSame(token, root.getCallTarget().call(module));
            }
            assertEquals(
                    4,
                    cleanups.get(),
                    "every canonical hit must run cleanup exactly once");

            body.createLocalSlot(
                    "ensure",
                    nativeClosure(
                            (activation, supplied) -> {
                                overrideCalls.incrementAndGet();
                                return overrideResult;
                            }));

            Object overriddenResult = root.getCallTarget().call(module);

            assertSame(overrideResult, overriddenResult);
            assertEquals(1, overrideCalls.get());
            assertEquals(
                    4,
                    cleanups.get(),
                    "stale structured ensure hit must not run after nearer override");
        }

        System.out.println("I072_PHASE_E_WARMED_ENSURE_HIT_INVALIDATES_ON_OVERRIDE=PASS");
    }

    @Test
    void warmedCanonicalErrorHandleHitRemainsCorrectAcrossRepeatedDistinctErrors()
            throws Exception {
        try (LanguageScope scope = languageScope()) {
            ProtosPrelude prelude = core();
            ProtosActorExecutionDomain domain = new ProtosActorExecutionDomain();
            ProtosActivation module = activation(prelude, domain);
            ProtosObjectValue error = ProtosCoreErrors.newError(module);
            AtomicInteger handlerCalls = new AtomicInteger();

            module.context().createLocalSlot("error", error);
            module.context().createLocalSlot(
                    "body",
                    sourceClosure(
                            scope.language(),
                            module,
                            "() => error.signal()",
                            "i072-phase-e-handle-warm-body.protos"));
            module.context().createLocalSlot(
                    "handler",
                    nativeClosure(
                            (activation, supplied) -> {
                                handlerCalls.incrementAndGet();
                                return supplied.get(0);
                            }));

            ProtosSemanticBytecodeRootNode root =
                    lowerRoot(
                            scope.language(),
                            "Error.handle(body, handler)",
                            "i072-phase-e-handle-warm.protos");

            for (int i = 0; i < 4; i++) {
                ProtosObjectValue iterationError = ProtosCoreErrors.newError(module);
                module.context().assignLocalSlot("error", iterationError);
                Object result = root.getCallTarget().call(module);
                assertSame(
                        iterationError,
                        assertInstanceOf(ProtosObjectValue.class, result),
                        "warmed structured hit must not return a stale captured error identity");
            }
            assertEquals(4, handlerCalls.get());
        }

        System.out.println("I072_PHASE_E_WARMED_ERROR_HANDLE_HIT_REMAINS_CORRECT=PASS");
    }

    @Test
    void warmedCanonicalArrayEachHitStopsAfterNearerOverrideAddition() throws Exception {
        try (LanguageScope scope = languageScope()) {
            ProtosPrelude prelude = core();
            ProtosActorExecutionDomain domain = new ProtosActorExecutionDomain();
            ProtosActivation module = activation(prelude, domain);
            ProtosArrayValue array =
                    prelude.newArray(
                            List.of(
                                    new ProtosIntegerValue(1L),
                                    new ProtosIntegerValue(2L)));
            ProtosObjectValue overrideResult = new ProtosObjectValue(ProtosObjectValue.rootObject());
            AtomicInteger callbacks = new AtomicInteger();
            AtomicInteger overrideCalls = new AtomicInteger();

            module.context().createLocalSlot("array", array);
            module.context().createLocalSlot(
                    "probe",
                    nativeClosure(
                            (activation, supplied) -> {
                                callbacks.incrementAndGet();
                                return ProtosNullValue.INSTANCE;
                            }));

            ProtosSemanticBytecodeRootNode root =
                    lowerRoot(
                            scope.language(),
                            "array.each(probe)",
                            "i072-phase-e-array-each-warm.protos");

            for (int i = 0; i < 4; i++) {
                assertSame(array, root.getCallTarget().call(module));
            }
            assertEquals(
                    8,
                    callbacks.get(),
                    "every canonical each hit must visit both elements exactly once");

            array.createLocalSlot(
                    "each",
                    nativeClosure(
                            (activation, supplied) -> {
                                overrideCalls.incrementAndGet();
                                return overrideResult;
                            }));

            Object overriddenResult = root.getCallTarget().call(module);

            assertSame(overrideResult, overriddenResult);
            assertEquals(1, overrideCalls.get());
            assertEquals(
                    8,
                    callbacks.get(),
                    "stale structured array.each hit must not run after nearer override");
        }

        System.out.println("I072_PHASE_E_WARMED_ARRAY_EACH_HIT_INVALIDATES_ON_OVERRIDE=PASS");
    }

    @Test
    void warmedCanonicalMapAtPutHitStopsAfterNearerOverrideAddition() throws Exception {
        try (LanguageScope scope = languageScope()) {
            ProtosPrelude prelude = core();
            ProtosActorExecutionDomain domain = new ProtosActorExecutionDomain();
            ProtosActivation module = activation(prelude, domain);
            ProtosMapValue map = new ProtosMapValue(prelude.mapPrototype());
            ProtosObjectValue overrideResult = new ProtosObjectValue(ProtosObjectValue.rootObject());
            AtomicInteger overrideCalls = new AtomicInteger();

            module.context().createLocalSlot("map", map);
            module.context().createLocalSlot(
                    "key",
                    new ProtosObjectValue(ProtosObjectValue.rootObject()));
            module.context().createLocalSlot(
                    "value",
                    new ProtosObjectValue(ProtosObjectValue.rootObject()));

            ProtosSemanticBytecodeRootNode root =
                    lowerRoot(
                            scope.language(),
                            "map.atPut(key, value)",
                            "i072-phase-e-map-atput-warm.protos");

            for (int i = 0; i < 4; i++) {
                root.getCallTarget().call(module);
            }
            assertEquals(1, map.keyedSize());

            map.createLocalSlot(
                    "atPut",
                    nativeClosure(
                            (activation, supplied) -> {
                                overrideCalls.incrementAndGet();
                                return overrideResult;
                            }));

            Object overriddenResult = root.getCallTarget().call(module);

            assertSame(overrideResult, overriddenResult);
            assertEquals(1, overrideCalls.get());
            assertEquals(
                    1,
                    map.keyedSize(),
                    "stale structured map.atPut hit must not run after nearer override");
        }

        System.out.println("I072_PHASE_E_WARMED_MAP_ATPUT_HIT_INVALIDATES_ON_OVERRIDE=PASS");
    }

    /**
     * PERF015: canonical represented Booleans are admitted to the guarded
     * structured selection for exactly the seven Boolean callbacks (D200 adds if/else), with the
     * canonical behavior/home provenance of the generic D013 selection.
     */
    @SuppressWarnings("try")
    @Test
    void canonicalBooleanReceiversAreAdmittedToGuardedStructuredSelection() throws Exception {
        try (LanguageScope scope = languageScope()) {
            ProtosPrelude prelude = core();
            ProtosActivation module = activation(prelude, new ProtosActorExecutionDomain());
            String[] selectors = {"ifTrue", "ifFalse", "ifTrueIfFalse", "and", "or", "if", "else"};
            ProtosStandardBooleanProtocol.StructuredCallbackKind[] kinds = {
                ProtosStandardBooleanProtocol.StructuredCallbackKind.IF_TRUE,
                ProtosStandardBooleanProtocol.StructuredCallbackKind.IF_FALSE,
                ProtosStandardBooleanProtocol.StructuredCallbackKind.IF_TRUE_IF_FALSE,
                ProtosStandardBooleanProtocol.StructuredCallbackKind.AND,
                ProtosStandardBooleanProtocol.StructuredCallbackKind.OR,
                ProtosStandardBooleanProtocol.StructuredCallbackKind.IF,
                ProtosStandardBooleanProtocol.StructuredCallbackKind.ELSE
            };
            for (ProtosBooleanValue receiver :
                    new ProtosBooleanValue[] {ProtosBooleanValue.TRUE, ProtosBooleanValue.FALSE}) {
                for (int i = 0; i < selectors.length; i++) {
                    var send = ProtosBytecodeRootNode.PrepareSendArguments
                            .createGuardedStructuredSend(receiver, selectors[i], module);
                    assertNotNull(send, selectors[i]);
                    var generic = ProtosValueLookup.lookup(
                            receiver, selectors[i], prelude).orElseThrow();
                    assertSame(generic.value(), send.closure());
                    assertSame(generic.home(), send.methodHome());
                    assertSame(ProtosObjectValue.rootObject(), send.methodHome());
                    assertEquals(
                            ProtosBytecodeRootNode.PrepareSendArguments
                                    .GuardedStructuredKind.BOOLEAN,
                            send.kind());
                    assertEquals(kinds[i], send.booleanKind());
                    assertTrue(send.stability().isValid());
                }
                // Not a Boolean callback: no guarded admission, generic path remains.
                assertNull(ProtosBytecodeRootNode.PrepareSendArguments
                        .createGuardedStructuredSend(receiver, "not", module));
                assertNull(ProtosBytecodeRootNode.PrepareSendArguments
                        .createGuardedStructuredSend(receiver, "ensure", module));
            }
        }
        System.out.println("PERF015_CANONICAL_BOOLEAN_REPRESENTED_GUARD=PASS");
    }

    /**
     * Core bootstrap freezes the standard graph including root Object, so the
     * canonical Boolean selection is immutable in a bootstrapped Context: the
     * warmed guard must stay valid and keep hitting, and the frozen root
     * rejects the mutation that would otherwise invalidate it.
     */
    @Test
    void warmedCanonicalBooleanHitStaysValidOnFrozenStandardSelection() throws Exception {
        try (LanguageScope scope = languageScope()) {
            ProtosPrelude prelude = core();
            ProtosActivation module = activation(prelude, new ProtosActorExecutionDomain());
            ProtosObjectValue root = ProtosObjectValue.rootObject();
            AtomicInteger callbackCalls = new AtomicInteger();

            module.context().createLocalSlot("flag", ProtosBooleanValue.TRUE);
            module.context().createLocalSlot(
                    "callback",
                    nativeClosure(
                            (activation, supplied) -> {
                                callbackCalls.incrementAndGet();
                                return ProtosBooleanValue.TRUE;
                            }));
            ProtosSemanticBytecodeRootNode site =
                    lowerRoot(scope.language(), "flag.ifTrue(callback)",
                            "perf015-boolean-warm.protos");

            for (int i = 0; i < 4; i++) {
                assertSame(ProtosBooleanValue.TRUE, site.getCallTarget().call(module));
            }
            assertEquals(4, callbackCalls.get());

            var warmed = ProtosBytecodeRootNode.PrepareSendArguments
                    .createGuardedStructuredSend(ProtosBooleanValue.TRUE, "ifTrue", module);
            assertNotNull(warmed);
            assertTrue(root.isFrozen());
            assertThrows(
                    IllegalStateException.class,
                    () -> root.assignLocalSlot("ifTrue", warmed.closure()));
            assertTrue(warmed.stability().isValid());
            assertSame(ProtosBooleanValue.TRUE, site.getCallTarget().call(module));
            assertEquals(5, callbackCalls.get());
        }
        System.out.println("PERF015_BOOLEAN_FROZEN_SELECTION_STABLE=PASS");
    }

    private static ProtosClosureValue nativeClosure(
            com.guillermomolina.protos.runtime.ProtosNativeClosureBody body) {
        return ProtosClosureValue.suspensionCapableNativeClosure(body, body);
    }

    private static ProtosClosureValue sourceClosure(
            ProtosLanguage language,
            ProtosActivation creator,
            String characters,
            String sourceName)
            throws Exception {
        CanonicalSequence sequence = canonicalize(characters);
        assertEquals(1, sequence.expressions().size());
        CanonicalClosure definition =
                assertInstanceOf(CanonicalClosure.class, sequence.expressions().get(0));
        ProtosClosureExecutionPlan plan =
                ProtosClosureExecutionPlan.bytecode(
                        definition,
                        language,
                        source(characters, sourceName));
        return new ProtosClosureValue(
                definition,
                creator.lexicalContextsForClosureCapture(),
                creator.receiver(),
                creator.methodHome().orElse(null),
                creator.returnHome().orElse(null),
                creator.prelude().orElseThrow(),
                plan);
    }

    private static LanguageScope languageScope() {
        Context context = Context.newBuilder(ProtosLanguage.ID).build();
        context.initialize(ProtosLanguage.ID);
        context.enter();
        return new LanguageScope(context, LANGUAGE_REF.get(null));
    }

    private static ProtosPrelude core() throws Exception {
        return new ProtosCoreBootstrap().bootstrap(Path.of("protos", "lib", "core"));
    }

    private static ProtosActivation activation(
            ProtosPrelude prelude,
            ProtosActorExecutionDomain domain) {
        return prelude.newModuleActivation(
                new ProtosActorModuleState(),
                null,
                prelude.newExecutionContext(),
                domain);
    }

    private static CanonicalSequence canonicalize(String characters) {
        return (CanonicalSequence)
                new Canonicalizer()
                        .canonicalize(new ProtosParser(characters).parseProgram());
    }

    private static Source source(String characters, String name) throws Exception {
        return Source.newBuilder(ProtosLanguage.ID, characters, name).build();
    }

    private static ProtosSemanticBytecodeRootNode lowerRoot(
            ProtosLanguage language,
            String characters,
            String sourceName)
            throws Exception {
        Source source = source(characters, sourceName);
        return new CanonicalToBytecodeLowerer(language, source)
                .lowerRoot(canonicalize(characters));
    }

    private record LanguageScope(Context context, ProtosLanguage language)
            implements AutoCloseable {
        @Override
        public void close() {
            context.leave();
            context.close();
        }
    }
}
