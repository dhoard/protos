/* APL-1.0 licensed work; see LICENSE.TXT. */
package com.guillermomolina.protos.execution;

import static org.junit.jupiter.api.Assertions.*;

import com.guillermomolina.protos.parser.ProtosParser;
import com.guillermomolina.protos.runtime.*;
import com.guillermomolina.protos.semantic.Canonicalizer;
import com.guillermomolina.protos.semantic.ast.CanonicalSequence;
import com.oracle.truffle.api.TruffleLanguage.LanguageReference;
import com.oracle.truffle.api.source.Source;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;

final class ProtosPlat029FutureValueNonTaskCPrimeTest {
    private static final LanguageReference<ProtosLanguage> LANGUAGE_REF =
            LanguageReference.create(ProtosLanguage.class);

    @Test
    void pendingFutureResumesOperationCPrimeWithoutTaskOrInlineGuestReentry() throws Exception {
        try (LanguageScope scope = languageScope()) {
            Fixture f = fixture();
            ProtosIoOperation op = f.lifecycle.beginOperation(f.module);
            ProtosActivation activation = op.deferredCPrimeActivationForRuntime();
            ProtosFutureValue source = new ProtosFutureValue(f.prelude.futurePrototype(), f.domain);
            ProtosObjectValue value = new ProtosObjectValue(ProtosObjectValue.rootObject());
            activation.context().createLocalSlot("source", source);

            install(op, activation, lowerRoot(scope.language, "source.value()", "plat029-future-value-pending.protos"));
            assertTrue(f.domain.dispatchOne());
            assertEquals(ProtosFutureValue.State.PENDING, op.future().state());
            assertEquals(0, f.domain.liveTaskCount());

            assertTrue(source.resolve(value, f.module));
            assertEquals(ProtosFutureValue.State.PENDING, op.future().state(),
                    "Future terminalization must only make the operation runnable");
            assertEquals(0, f.domain.liveTaskCount());

            assertTrue(f.domain.dispatchOne());
            assertEquals(ProtosFutureValue.State.RESOLVED, op.future().state());
            assertSame(value, op.future().resolvedValue().orElseThrow());
            assertEquals(0, f.domain.liveTaskCount());
            assertFalse(f.domain.dispatchOne());
        }
        System.out.println("PLAT029_FUTURE_VALUE_NON_TASK_CPRIME=PASS");
        System.out.println("PLAT029_FUTURE_VALUE_BACKEND_INLINE_GUEST_REENTRY=NO");
        System.out.println("PLAT029_FUTURE_VALUE_HIDDEN_TASK=NO");
    }

    @Test
    void alreadyTerminalFutureCompletesInInitialOperationSegment() throws Exception {
        try (LanguageScope scope = languageScope()) {
            Fixture f = fixture();
            ProtosIoOperation op = f.lifecycle.beginOperation(f.module);
            ProtosActivation activation = op.deferredCPrimeActivationForRuntime();
            ProtosFutureValue source = new ProtosFutureValue(f.prelude.futurePrototype(), f.domain);
            ProtosObjectValue value = new ProtosObjectValue(ProtosObjectValue.rootObject());
            assertTrue(source.resolve(value, f.module));
            activation.context().createLocalSlot("source", source);

            install(op, activation, lowerRoot(scope.language, "source.value()", "plat029-future-value-terminal.protos"));
            assertTrue(f.domain.dispatchOne());
            assertEquals(ProtosFutureValue.State.RESOLVED, op.future().state());
            assertSame(value, op.future().resolvedValue().orElseThrow());
            assertFalse(f.domain.dispatchOne());
            assertEquals(0, f.domain.liveTaskCount());
        }
        System.out.println("PLAT029_FUTURE_VALUE_TERMINAL_FAST_PATH=PASS");
    }

    @Test
    void operationCancellationReleasesPendingFutureObservationWithoutLateResume() throws Exception {
        try (LanguageScope scope = languageScope()) {
            Fixture f = fixture();
            ProtosIoOperation op = f.lifecycle.beginOperation(f.module);
            ProtosActivation activation = op.deferredCPrimeActivationForRuntime();
            ProtosFutureValue source = new ProtosFutureValue(f.prelude.futurePrototype(), f.domain);
            AtomicInteger probe = new AtomicInteger();
            activation.context().createLocalSlot("source", source);
            activation.context().createLocalSlot("probe", ProtosClosureValue.nativeClosure((a,x)->{probe.incrementAndGet();return ProtosNullValue.INSTANCE;}));

            install(op, activation, lowerRoot(scope.language, "source.value()\nprobe()", "plat029-future-value-cancel.protos"));
            assertTrue(f.domain.dispatchOne());
            assertEquals(ProtosFutureValue.State.PENDING, op.future().state());
            assertTrue(op.future().cancelRequest());
            assertEquals(ProtosFutureValue.State.CANCELLED, op.future().state());

            assertTrue(source.resolve(ProtosNullValue.INSTANCE, f.module));
            assertFalse(f.domain.dispatchOne());
            assertEquals(0, probe.get());
            assertEquals(0, f.domain.liveTaskCount());
        }
        System.out.println("PLAT029_FUTURE_VALUE_TERMINAL_RELEASE=PASS");
        System.out.println("PLAT029_FUTURE_VALUE_LATE_RESUME=NO");
    }

    @Test
    void failedFuturePreservesExactErrorThroughOperationCPrimeObservation() throws Exception {
        try (LanguageScope scope = languageScope()) {
            Fixture f = fixture();
            ProtosIoOperation op = f.lifecycle.beginOperation(f.module);
            ProtosActivation activation = op.deferredCPrimeActivationForRuntime();
            ProtosFutureValue source = new ProtosFutureValue(f.prelude.futurePrototype(), f.domain);
            ProtosObjectValue error = ProtosCoreErrors.newError(activation);
            activation.context().createLocalSlot("source", source);

            ProtosBytecodeIoOperationExecution.installAndSchedule(
                    op,
                    lowerRoot(scope.language, "source.value()", "plat029-future-value-failed.protos").getCallTarget(),
                    activation,
                    (current,value)->fail("failed source must not complete operation normally"),
                    (current,failed)->assertTrue(current.fail(failed)));
            assertTrue(f.domain.dispatchOne());
            assertTrue(source.fail(error));
            assertTrue(f.domain.dispatchOne());
            assertEquals(ProtosFutureValue.State.FAILED, op.future().state());
            assertSame(error, op.future().failedError().orElseThrow());
            assertEquals(0, f.domain.liveTaskCount());
        }
        System.out.println("PLAT029_FUTURE_VALUE_FAILED_ERROR_IDENTITY=PASS");
    }

    private static void install(ProtosIoOperation op, ProtosActivation activation, ProtosSemanticBytecodeRootNode root) {
        ProtosBytecodeIoOperationExecution.installAndSchedule(
                op, root.getCallTarget(), activation,
                (current,value)->{assertTrue(current.commit());assertTrue(current.resolve(value));},
                (current,error)->{assertTrue(current.fail(error));});
    }

    private static Fixture fixture() throws Exception {
        ProtosPrelude p = new ProtosCoreBootstrap().bootstrap(Path.of("protos","lib","core"));
        ProtosActorExecutionDomain d = new ProtosActorExecutionDomain();
        ProtosActivation m = p.newModuleActivation(new ProtosActorModuleState(),null,p.newExecutionContext(),d);
        ProtosObjectValue receiver = new ProtosObjectValue(ProtosObjectValue.rootObject());
        ProtosIoLifecycle lifecycle = new ProtosIoLifecycle(receiver,p.futurePrototype(),d,c->c.succeeded());
        return new Fixture(p,d,m,lifecycle);
    }

    private static ProtosSemanticBytecodeRootNode lowerRoot(ProtosLanguage language,String chars,String name) throws Exception {
        Source source=Source.newBuilder(ProtosLanguage.ID,chars,name).build();
        return new CanonicalToBytecodeLowerer(language,source, ProtosExecutionMode.NORMAL).lowerRoot(
                (CanonicalSequence)new Canonicalizer().canonicalize(new ProtosParser(chars).parseProgram()));
    }

    private static LanguageScope languageScope() {
        Context c=Context.newBuilder(ProtosLanguage.ID).build();c.initialize(ProtosLanguage.ID);c.enter();
        return new LanguageScope(c,LANGUAGE_REF.get(null));
    }
    private record Fixture(ProtosPrelude prelude,ProtosActorExecutionDomain domain,ProtosActivation module,ProtosIoLifecycle lifecycle) {}
    private record LanguageScope(Context context,ProtosLanguage language) implements AutoCloseable {
        @Override public void close(){context.leave();context.close();}
    }
}
