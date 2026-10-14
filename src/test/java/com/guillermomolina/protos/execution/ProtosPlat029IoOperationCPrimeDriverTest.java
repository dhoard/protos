/* APL-1.0 licensed work; see LICENSE.TXT. */
package com.guillermomolina.protos.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.guillermomolina.protos.parser.ProtosParser;
import com.guillermomolina.protos.runtime.ProtosActivation;
import com.guillermomolina.protos.runtime.ProtosActorExecutionDomain;
import com.guillermomolina.protos.runtime.ProtosActorModuleState;
import com.guillermomolina.protos.runtime.ProtosClosureValue;
import com.guillermomolina.protos.runtime.ProtosDynamicControlState;
import com.guillermomolina.protos.runtime.ProtosFutureValue;
import com.guillermomolina.protos.runtime.ProtosIoLifecycle;
import com.guillermomolina.protos.runtime.ProtosIoOperation;
import com.guillermomolina.protos.runtime.ProtosNullValue;
import com.guillermomolina.protos.runtime.ProtosObjectValue;
import com.guillermomolina.protos.runtime.ProtosPrelude;
import com.guillermomolina.protos.semantic.Canonicalizer;
import com.guillermomolina.protos.semantic.ast.CanonicalClosure;
import com.guillermomolina.protos.semantic.ast.CanonicalSequence;
import com.oracle.truffle.api.TruffleLanguage.LanguageReference;
import com.oracle.truffle.api.source.Source;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;

final class ProtosPlat029IoOperationCPrimeDriverTest {
    private static final LanguageReference<ProtosLanguage> LANGUAGE_REF =
            LanguageReference.create(ProtosLanguage.class);

    private static final class Dependency implements ProtosIoOperationSuspension.Dependency {
        private final ProtosIoOperation operation;
        private final AtomicInteger releases = new AtomicInteger();
        private volatile boolean ready;

        Dependency(ProtosIoOperation operation) {
            this.operation = operation;
        }

        @Override
        public boolean isReady() {
            return ready;
        }

        boolean makeReadyAndNotify() {
            ready = true;
            return operation.requestDeferredCPrimeRunForRuntime();
        }

        void makeReadyWithoutNotify() {
            ready = true;
        }

        @Override
        public void waitingOperationReleased(ProtosIoOperation releasedOperation) {
            assertSame(operation, releasedOperation);
            releases.incrementAndGet();
        }

        int releases() {
            return releases.get();
        }
    }

    @Test
    void nestedGuestCallbackSuspendsAndResumesUnderExactOperationWithoutTask() throws Exception {
        try (LanguageScope scope = languageScope()) {
            Fixture fixture = fixture();
            ProtosIoOperation operation = fixture.lifecycle.beginOperation(fixture.module);
            ProtosActivation operationActivation = operation.deferredCPrimeActivationForRuntime();
            Dependency dependency = new Dependency(operation);
            ProtosObjectValue completed = new ProtosObjectValue(ProtosObjectValue.rootObject());
            AtomicInteger ordinaryCalls = new AtomicInteger();
            AtomicInteger continuationCalls = new AtomicInteger();
            AtomicInteger resumerCalls = new AtomicInteger();
            AtomicReference<ProtosIoOperation> seenOwner = new AtomicReference<>();
            AtomicReference<ProtosDynamicControlState> seenControl = new AtomicReference<>();

            operationActivation.context().createLocalSlot(
                    "leaf",
                    ProtosClosureValue.suspensionCapableNativeClosure(
                            (activation, supplied) -> {
                                ordinaryCalls.incrementAndGet();
                                return ProtosNullValue.INSTANCE;
                            },
                            (activation, supplied) -> {
                                continuationCalls.incrementAndGet();
                                assertTrue(activation.task().isEmpty());
                                ProtosIoOperation owner =
                                        activation.deferredCPrimeOperationForRuntime().orElseThrow();
                                seenOwner.set(owner);
                                seenControl.set(activation.dynamicControlState());
                                return ProtosIoOperationSuspension.pending(
                                        owner,
                                        dependency,
                                        () -> {
                                            resumerCalls.incrementAndGet();
                                            return completed;
                                        });
                            }));
            operationActivation.context().createLocalSlot(
                    "entry",
                    sourceClosure(
                            scope.language(),
                            operationActivation,
                            "() => { leaf() }",
                            "plat029-operation-driver-middle.protos"));

            ProtosSemanticBytecodeRootNode root =
                    lowerRoot(
                            scope.language(),
                            "entry()",
                            "plat029-operation-driver-top.protos");

            ProtosBytecodeIoOperationExecution.installAndSchedule(
                    operation,
                    root.getCallTarget(),
                    operationActivation,
                    (current, value) -> {
                        assertSame(completed, value);
                        assertTrue(current.commit());
                        assertTrue(current.resolve(value));
                    },
                    (current, error) -> fail("unexpected operation C-prime failure"));

            assertTrue(operationActivation.task().isEmpty());
            assertSame(
                    operation,
                    operationActivation.deferredCPrimeOperationForRuntime().orElseThrow());
            assertEquals(0, fixture.domain.liveTaskCount());

            assertTrue(fixture.domain.dispatchOne());
            assertEquals(ProtosFutureValue.State.PENDING, operation.future().state());
            assertSame(operation, seenOwner.get());
            assertSame(operationActivation.dynamicControlState(), seenControl.get());
            assertEquals(0, ordinaryCalls.get());
            assertEquals(1, continuationCalls.get());
            assertEquals(0, resumerCalls.get());
            assertEquals(0, dependency.releases());
            assertEquals(0, fixture.domain.liveTaskCount());

            assertTrue(dependency.makeReadyAndNotify());
            assertEquals(
                    ProtosFutureValue.State.PENDING,
                    operation.future().state(),
                    "backend readiness must not execute guest continuation inline");
            assertEquals(0, resumerCalls.get());

            assertTrue(fixture.domain.dispatchOne());
            assertEquals(ProtosFutureValue.State.RESOLVED, operation.future().state());
            assertSame(completed, operation.future().resolvedValue().orElseThrow());
            assertEquals(1, dependency.releases());
            assertEquals(1, resumerCalls.get());
            assertEquals(1, continuationCalls.get(), "native callback body must not replay");
            assertEquals(0, fixture.domain.liveTaskCount());
            assertFalse(fixture.domain.dispatchOne());
        }

        System.out.println("PLAT029_OPERATION_CPRIME_CONTINUATION_RESULT=PASS");
        System.out.println("PLAT029_OPERATION_CPRIME_TASK_IDENTITY=NONE");
        System.out.println("PLAT029_OPERATION_CPRIME_NESTED_OWNER_PROPAGATION=PASS");
        System.out.println("PLAT029_OPERATION_CPRIME_BACKEND_INLINE_GUEST_REENTRY=NO");
    }

    @Test
    void readyBeforeContinuationRetentionResumesInsideSameActorSegment() throws Exception {
        try (LanguageScope scope = languageScope()) {
            Fixture fixture = fixture();
            ProtosIoOperation operation = fixture.lifecycle.beginOperation(fixture.module);
            ProtosActivation operationActivation = operation.deferredCPrimeActivationForRuntime();
            Dependency dependency = new Dependency(operation);
            dependency.makeReadyWithoutNotify();
            ProtosObjectValue completed = new ProtosObjectValue(ProtosObjectValue.rootObject());
            AtomicInteger resumerCalls = new AtomicInteger();

            operationActivation.context().createLocalSlot(
                    "leaf",
                    ProtosClosureValue.suspensionCapableNativeClosure(
                            (activation, supplied) -> fail("ordinary native entry must not be selected"),
                            (activation, supplied) ->
                                    ProtosIoOperationSuspension.pending(
                                            activation.deferredCPrimeOperationForRuntime().orElseThrow(),
                                            dependency,
                                            () -> {
                                                resumerCalls.incrementAndGet();
                                                return completed;
                                            })));

            ProtosBytecodeIoOperationExecution.installAndSchedule(
                    operation,
                    lowerRoot(
                                    scope.language(),
                                    "leaf()",
                                    "plat029-operation-driver-ready-before-retain.protos")
                            .getCallTarget(),
                    operationActivation,
                    (current, value) -> {
                        assertSame(completed, value);
                        assertTrue(current.commit());
                        assertTrue(current.resolve(value));
                    },
                    (current, error) -> fail("unexpected operation C-prime failure"));

            assertTrue(fixture.domain.dispatchOne());
            assertEquals(ProtosFutureValue.State.RESOLVED, operation.future().state());
            assertEquals(1, resumerCalls.get());
            assertEquals(1, dependency.releases());
            assertFalse(fixture.domain.dispatchOne());
            assertEquals(0, fixture.domain.liveTaskCount());
        }

        System.out.println("PLAT029_OPERATION_CPRIME_READY_BEFORE_RETAIN=PASS");
        System.out.println("PLAT029_OPERATION_CPRIME_LOST_WAKEUP=NO");
    }

    @Test
    void terminalCancellationReleasesRetainedWaitWithoutResumingGuestCode() throws Exception {
        try (LanguageScope scope = languageScope()) {
            Fixture fixture = fixture();
            ProtosIoOperation operation = fixture.lifecycle.beginOperation(fixture.module);
            ProtosActivation operationActivation = operation.deferredCPrimeActivationForRuntime();
            Dependency dependency = new Dependency(operation);
            AtomicInteger resumerCalls = new AtomicInteger();

            operationActivation.context().createLocalSlot(
                    "leaf",
                    ProtosClosureValue.suspensionCapableNativeClosure(
                            (activation, supplied) -> fail("ordinary native entry must not be selected"),
                            (activation, supplied) ->
                                    ProtosIoOperationSuspension.pending(
                                            activation.deferredCPrimeOperationForRuntime().orElseThrow(),
                                            dependency,
                                            () -> {
                                                resumerCalls.incrementAndGet();
                                                return ProtosNullValue.INSTANCE;
                                            })));

            ProtosBytecodeIoOperationExecution.installAndSchedule(
                    operation,
                    lowerRoot(
                                    scope.language(),
                                    "leaf()",
                                    "plat029-operation-driver-terminal-release.protos")
                            .getCallTarget(),
                    operationActivation,
                    (current, value) -> fail("cancelled operation must not complete guest continuation"),
                    (current, error) -> fail("cancelled operation must not fail through guest continuation"));

            assertTrue(fixture.domain.dispatchOne());
            assertEquals(ProtosFutureValue.State.PENDING, operation.future().state());
            assertEquals(0, dependency.releases());

            assertTrue(operation.future().cancelRequest());
            assertEquals(ProtosFutureValue.State.CANCELLED, operation.future().state());
            assertTrue(operation.terminal());
            assertEquals(1, dependency.releases());
            assertEquals(0, resumerCalls.get());

            assertFalse(dependency.makeReadyAndNotify());
            assertFalse(fixture.domain.dispatchOne());
            assertEquals(0, resumerCalls.get());
            assertEquals(0, fixture.domain.liveTaskCount());
        }

        System.out.println("PLAT029_OPERATION_CPRIME_TERMINAL_WAIT_RELEASE=PASS");
        System.out.println("PLAT029_OPERATION_CANCEL_TO_TASK_CANCEL=NO");
        System.out.println("PLAT029_OPERATION_TERMINAL_GUEST_REENTRY=NO");
    }

    private static Fixture fixture() throws Exception {
        ProtosPrelude prelude = core();
        ProtosActorExecutionDomain domain = new ProtosActorExecutionDomain();
        ProtosActivation module =
                prelude.newModuleActivation(
                        new ProtosActorModuleState(),
                        null,
                        prelude.newExecutionContext(),
                        domain);
        ProtosObjectValue receiver = new ProtosObjectValue(ProtosObjectValue.rootObject());
        ProtosIoLifecycle lifecycle =
                new ProtosIoLifecycle(
                        receiver,
                        prelude.futurePrototype(),
                        domain,
                        completion -> completion.succeeded());
        return new Fixture(prelude, domain, module, lifecycle);
    }

    private static ProtosClosureValue sourceClosure(
            ProtosLanguage language,
            ProtosActivation creator,
            String characters,
            String sourceName)
            throws Exception {
        CanonicalClosure definition = closureDefinition(characters);
        ProtosClosureExecutionPlan plan =
                ProtosClosureExecutionPlan.bytecode(
                        definition,
                        language,
                        source(characters, sourceName), ProtosExecutionMode.NORMAL);
        return new ProtosClosureValue(
                definition,
                creator.lexicalContextsForClosureCapture(),
                creator.receiver(),
                creator.methodHome().orElse(null),
                creator.returnHome().orElse(null),
                creator.prelude().orElseThrow(),
                plan);
    }

    private static ProtosSemanticBytecodeRootNode lowerRoot(
            ProtosLanguage language,
            String characters,
            String sourceName)
            throws Exception {
        return new CanonicalToBytecodeLowerer(language, source(characters, sourceName), ProtosExecutionMode.NORMAL)
                .lowerRoot(canonicalize(characters));
    }

    private static CanonicalClosure closureDefinition(String characters) {
        CanonicalSequence sequence = canonicalize(characters);
        assertEquals(1, sequence.expressions().size());
        return (CanonicalClosure) sequence.expressions().get(0);
    }

    private static CanonicalSequence canonicalize(String characters) {
        return (CanonicalSequence)
                new Canonicalizer().canonicalize(new ProtosParser(characters).parseProgram());
    }

    private static Source source(String characters, String sourceName) throws Exception {
        return Source.newBuilder(ProtosLanguage.ID, characters, sourceName).build();
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

    private record Fixture(
            ProtosPrelude prelude,
            ProtosActorExecutionDomain domain,
            ProtosActivation module,
            ProtosIoLifecycle lifecycle) {}

    private record LanguageScope(Context context, ProtosLanguage language) implements AutoCloseable {
        @Override
        public void close() {
            context.leave();
            context.close();
        }
    }
}
