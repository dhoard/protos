/* APL-1.0 licensed work; see LICENSE.TXT. */
package com.guillermomolina.protos.execution;

import static org.junit.jupiter.api.Assertions.*;

import com.guillermomolina.protos.parser.ProtosParser;
import com.guillermomolina.protos.runtime.*;
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

final class ProtosPerf006Plat029TextWriterCallbackTest {
    private static final LanguageReference<ProtosLanguage> LANGUAGE_REF =
            LanguageReference.create(ProtosLanguage.class);

    @Test
    void writeTextGuestCallbackAndLowerFutureBothResumeOnlyThroughActorDomain()
            throws Exception {
        try (LanguageScope scope = languageScope()) {
            Fixture f = fixture();
            ProtosFutureValue gate = future(f);
            ProtosFutureValue lower = future(f);
            lower.resolve(f.target, f.module);
            AtomicInteger prefixCalls = new AtomicInteger();

            f.module.context().createLocalSlot("gate", gate);
            f.module.context().createLocalSlot("lower", lower);
            f.module.context().createLocalSlot(
                    "probe",
                    ProtosClosureValue.nativeClosure(
                            (activation, supplied) -> {
                                prefixCalls.incrementAndGet();
                                return ProtosNullValue.INSTANCE;
                            }));
            f.target.createLocalSlot(
                    "write",
                    sourceClosure(
                            scope.language(),
                            f.module,
                            "(bytes) => { probe()\ngate.value()\nlower }",
                            "plat029-text-writer-write-callback.protos"));
            ProtosObjectValue writer = writer(f, false);
            f.module.context().createLocalSlot("writer", writer);

            ProtosFutureValue outer =
                    invokeCPrimeReturningFuture(
                            scope.language(),
                            f,
                            "writer.writeText(\"A\")",
                            "plat029-text-writer-write-top.protos");
            assertEquals(ProtosFutureValue.State.PENDING, outer.state());
            assertEquals(0, prefixCalls.get());
            assertEquals(
                    0,
                    f.domain.liveTaskCount(),
                    "caller Task must be terminal before operation-owned TextWriter C-prime runs");

            assertTrue(f.domain.dispatchOne());
            assertEquals(1, prefixCalls.get());
            assertEquals(ProtosFutureValue.State.PENDING, outer.state());
            assertEquals(0, f.domain.liveTaskCount());

            ProtosIntegerValue gateValue =
                    new ProtosIntegerValue(7);
            assertTrue(gate.resolve(gateValue, f.module));
            assertEquals(
                    1,
                    prefixCalls.get(),
                    "Future terminalization must not re-enter guest TextWriter callback inline");
            assertEquals(ProtosFutureValue.State.PENDING, outer.state());

            assertTrue(f.domain.dispatchOne());
            assertEquals(ProtosFutureValue.State.RESOLVED, outer.state());
            assertSame(writer, outer.resolvedValue().orElseThrow());
            assertEquals(1, prefixCalls.get(), "callback prefix must not replay");
            assertEquals(0, f.domain.liveTaskCount());
            assertFalse(f.domain.dispatchOne());
        }

        System.out.println("PLAT029_TEXT_WRITER_WRITE_CALLBACK_CPRIME=PASS");
        System.out.println("PLAT029_TEXT_WRITER_WRITE_CALLBACK_REPLAY=NO");
        System.out.println("PLAT029_TEXT_WRITER_WRITE_BACKEND_INLINE_GUEST_REENTRY=NO");
    }

    @Test
    void pendingLowerWriteFutureUsesOperationWaitWithoutHiddenTask()
            throws Exception {
        try (LanguageScope scope = languageScope()) {
            Fixture f = fixture();
            ProtosFutureValue lower = future(f);
            AtomicInteger writes = new AtomicInteger();
            f.target.createLocalSlot(
                    "write",
                    ProtosClosureValue.nativeClosure(
                            (activation, supplied) -> {
                                writes.incrementAndGet();
                                assertEquals(1, supplied.size());
                                assertInstanceOf(ProtosBytesValue.class, supplied.get(0));
                                assertTrue(activation.task().isEmpty());
                                assertTrue(
                                        activation.deferredCPrimeOperationForRuntime().isPresent());
                                return lower;
                            }));
            ProtosObjectValue writer = writer(f, false);
            f.module.context().createLocalSlot("writer", writer);

            ProtosTask caller =
                    execute(
                            f.domain,
                            f.module,
                            lowerRoot(
                                    scope.language(),
                                    "writer.writeLine(\"B\")",
                                    "plat029-text-writer-lower-wait.protos"));
            assertTrue(f.domain.dispatchOne());
            ProtosFutureValue outer =
                    assertInstanceOf(
                            ProtosFutureValue.class,
                            caller.result().orElseThrow());

            assertTrue(f.domain.dispatchOne());
            assertEquals(1, writes.get());
            assertEquals(ProtosFutureValue.State.PENDING, outer.state());
            assertEquals(0, f.domain.liveTaskCount());

            assertTrue(lower.resolve(f.target, f.module));
            assertEquals(
                    ProtosFutureValue.State.PENDING,
                    outer.state(),
                    "lower Future completion may only make the owning operation runnable");
            assertEquals(1, writes.get());

            assertTrue(f.domain.dispatchOne());
            assertEquals(ProtosFutureValue.State.RESOLVED, outer.state());
            assertSame(writer, outer.resolvedValue().orElseThrow());
            assertEquals(0, f.domain.liveTaskCount());
        }

        System.out.println("PLAT029_TEXT_WRITER_LOWER_FUTURE_WAIT_CPRIME=PASS");
        System.out.println("PLAT029_TEXT_WRITER_LOWER_WAIT_HIDDEN_TASK=NO");
    }

    @Test
    void lowerFailureIdentityPoisonsLaterOutputAndCancelledLowerMapsToIoError()
            throws Exception {
        try (LanguageScope scope = languageScope()) {
            /*
             * Failed lower Future: preserve the exact Error and use that exact same
             * permanent writer failure for later write/flush.
             */
            Fixture failedFixture = fixture();
            ProtosObjectValue exact =
                    ProtosCoreErrors.newOccurrence(
                            failedFixture.module,
                            ProtosCoreErrors.StandardError.I_O_ERROR);
            ProtosFutureValue failedLower = future(failedFixture);
            failedLower.fail(exact);
            AtomicInteger failedWrites = new AtomicInteger();
            failedFixture.target.createLocalSlot(
                    "write",
                    ProtosClosureValue.nativeClosure(
                            (activation, supplied) -> {
                                failedWrites.incrementAndGet();
                                return failedLower;
                            }));
            failedFixture.target.createLocalSlot(
                    "flush",
                    ProtosClosureValue.nativeClosure(
                            (activation, supplied) ->
                                    fail("poisoned TextWriter must not reach target.flush")));
            ProtosObjectValue failedWriter = writer(failedFixture, false);
            failedFixture.module.context().createLocalSlot("writer", failedWriter);

            ProtosFutureValue first =
                    invokeCPrimeReturningFuture(
                            scope.language(),
                            failedFixture,
                            "writer.writeText(\"A\")",
                            "plat029-text-writer-failed-lower.protos");
            assertTrue(failedFixture.domain.dispatchOne());
            assertEquals(ProtosFutureValue.State.FAILED, first.state());
            assertSame(exact, first.failedError().orElseThrow());
            assertEquals(1, failedWrites.get());

            ProtosActivation sibling = siblingModule(failedFixture.module);
            ProtosFutureValue later =
                    invokeCPrimeReturningFuture(
                            scope.language(),
                            failedFixture.withModule(sibling),
                            "writer.writeText(\"B\")",
                            "plat029-text-writer-poisoned-later.protos");
            assertFalse(
                    failedFixture.domain.dispatchOne(),
                    "poisoned later write must fail during caller dispatch without enqueuing C-prime work");
            assertEquals(ProtosFutureValue.State.FAILED, later.state());
            assertSame(exact, later.failedError().orElseThrow());
            assertEquals(1, failedWrites.get());

            ProtosActivation flushModule = siblingModule(failedFixture.module);
            ProtosFutureValue flush =
                    invokeCPrimeReturningFuture(
                            scope.language(),
                            failedFixture.withModule(flushModule),
                            "writer.flush()",
                            "plat029-text-writer-poisoned-flush.protos");
            assertFalse(
                    failedFixture.domain.dispatchOne(),
                    "poisoned flush must fail during caller dispatch without reaching target.flush");
            assertEquals(ProtosFutureValue.State.FAILED, flush.state());
            assertSame(exact, flush.failedError().orElseThrow());

            /*
             * Cancelled lower Future: wrapper output has already committed, so the
             * TextWriter maps that lower cancellation to one fresh IOError and
             * permanently records that exact Error for later operations.
             */
            Fixture cancelledFixture = fixture();
            ProtosFutureValue cancelledLower = future(cancelledFixture);
            assertTrue(cancelledLower.cancelTerminal());
            AtomicInteger cancelledWrites = new AtomicInteger();
            cancelledFixture.target.createLocalSlot(
                    "write",
                    ProtosClosureValue.nativeClosure(
                            (activation, supplied) -> {
                                cancelledWrites.incrementAndGet();
                                return cancelledLower;
                            }));
            ProtosObjectValue cancelledWriter = writer(cancelledFixture, false);
            cancelledFixture.module.context().createLocalSlot("writer", cancelledWriter);

            ProtosFutureValue cancelled =
                    invokeCPrimeReturningFuture(
                            scope.language(),
                            cancelledFixture,
                            "writer.writeText(\"C\")",
                            "plat029-text-writer-cancelled-lower.protos");
            assertTrue(cancelledFixture.domain.dispatchOne());
            assertEquals(ProtosFutureValue.State.FAILED, cancelled.state());
            ProtosObjectValue mapped = cancelled.failedError().orElseThrow();
            assertErrorParent(
                    cancelledFixture.prelude,
                    mapped,
                    "IOError");
            assertEquals(1, cancelledWrites.get());

            ProtosActivation cancelledSibling = siblingModule(cancelledFixture.module);
            ProtosFutureValue cancelledLater =
                    invokeCPrimeReturningFuture(
                            scope.language(),
                            cancelledFixture.withModule(cancelledSibling),
                            "writer.writeText(\"D\")",
                            "plat029-text-writer-cancelled-poison.protos");
            assertFalse(
                    cancelledFixture.domain.dispatchOne(),
                    "lower-cancellation poison must reject later write without enqueuing C-prime work");
            assertEquals(ProtosFutureValue.State.FAILED, cancelledLater.state());
            assertSame(mapped, cancelledLater.failedError().orElseThrow());
            assertEquals(1, cancelledWrites.get());
        }

        System.out.println("PLAT029_TEXT_WRITER_LOWER_FAILURE_IDENTITY=PASS");
        System.out.println("PLAT029_TEXT_WRITER_CANCELLED_LOWER_TO_IOERROR=PASS");
        System.out.println("PLAT029_TEXT_WRITER_PERMANENT_OUTPUT_POISON=PASS");
    }

    @Test
    void guestInvocationFailureMapsToIoErrorBeforeLowerFutureProjection()
            throws Exception {
        try (LanguageScope scope = languageScope()) {
            Fixture f = fixture();
            ProtosObjectValue signalled =
                    ProtosCoreErrors.newOccurrence(
                            f.module,
                            ProtosCoreErrors.StandardError.ERROR);
            AtomicInteger writes = new AtomicInteger();
            f.target.createLocalSlot(
                    "write",
                    ProtosClosureValue.nativeClosure(
                            (activation, supplied) -> {
                                writes.incrementAndGet();
                                throw new ProtosSignalException(signalled);
                            }));
            ProtosObjectValue writer = writer(f, false);
            f.module.context().createLocalSlot("writer", writer);

            ProtosFutureValue outer =
                    invokeCPrimeReturningFuture(
                            scope.language(),
                            f,
                            "writer.writeText(\"E\")",
                            "plat029-text-writer-invocation-failure.protos");
            assertTrue(f.domain.dispatchOne());
            assertEquals(ProtosFutureValue.State.FAILED, outer.state());
            ProtosObjectValue mapped = outer.failedError().orElseThrow();
            assertNotSame(signalled, mapped);
            assertErrorParent(f.prelude, mapped, "IOError");
            assertEquals(1, writes.get());

            ProtosActivation sibling = siblingModule(f.module);
            ProtosFutureValue later =
                    invokeCPrimeReturningFuture(
                            scope.language(),
                            f.withModule(sibling),
                            "writer.writeText(\"F\")",
                            "plat029-text-writer-invocation-failure-poison.protos");
            assertFalse(
                    f.domain.dispatchOne(),
                    "invocation-failure poison must reject later write without enqueuing C-prime work");
            assertEquals(ProtosFutureValue.State.FAILED, later.state());
            assertSame(mapped, later.failedError().orElseThrow());
            assertEquals(1, writes.get());
        }

        System.out.println("PLAT029_TEXT_WRITER_INVOCATION_FAILURE_TO_IOERROR=PASS");
    }

    @Test
    void flushGuestCallbackMaySuspendAndCloseRemainsOutsideThisSlice()
            throws Exception {
        try (LanguageScope scope = languageScope()) {
            Fixture f = fixture();
            ProtosFutureValue gate = future(f);
            ProtosFutureValue lower = future(f);
            lower.resolve(f.target, f.module);
            AtomicInteger flushPrefix = new AtomicInteger();

            /* TextWriter construction still requires ByteWritable even for this flush-only proof. */
            f.target.createLocalSlot(
                    "write",
                    ProtosClosureValue.nativeClosure(
                            (activation, supplied) -> {
                                ProtosFutureValue immediate = future(f);
                                immediate.resolve(f.target, activation);
                                return immediate;
                            }));
            f.module.context().createLocalSlot("gate", gate);
            f.module.context().createLocalSlot("lower", lower);
            f.module.context().createLocalSlot(
                    "flushProbe",
                    ProtosClosureValue.nativeClosure(
                            (activation, supplied) -> {
                                flushPrefix.incrementAndGet();
                                return ProtosNullValue.INSTANCE;
                            }));
            f.target.createLocalSlot(
                    "flush",
                    sourceClosure(
                            scope.language(),
                            f.module,
                            "() => { flushProbe()\ngate.value()\nlower }",
                            "plat029-text-writer-flush-callback.protos"));

            ProtosObjectValue writer = writer(f, false);
            f.module.context().createLocalSlot("writer", writer);

            Object writeSlot = writer.readLocalSlot("writeText").orElseThrow();
            Object lineSlot = writer.readLocalSlot("writeLine").orElseThrow();
            Object flushSlot = writer.readLocalSlot("flush").orElseThrow();
            Object closeSlot = writer.readLocalSlot("close").orElseThrow();
            assertInstanceOf(
                    ProtosSuspensionCapableNativeClosureBody.class,
                    assertInstanceOf(ProtosClosureValue.class, writeSlot).nativeBody().orElseThrow());
            assertInstanceOf(
                    ProtosSuspensionCapableNativeClosureBody.class,
                    assertInstanceOf(ProtosClosureValue.class, lineSlot).nativeBody().orElseThrow());
            assertInstanceOf(
                    ProtosSuspensionCapableNativeClosureBody.class,
                    assertInstanceOf(ProtosClosureValue.class, flushSlot).nativeBody().orElseThrow());
            assertFalse(
                    assertInstanceOf(ProtosClosureValue.class, closeSlot).nativeBody().orElseThrow()
                            instanceof ProtosSuspensionCapableNativeClosureBody);

            ProtosTask caller =
                    execute(
                            f.domain,
                            f.module,
                            lowerRoot(
                                    scope.language(),
                                    "writer.flush()",
                                    "plat029-text-writer-flush-top.protos"));
            assertTrue(f.domain.dispatchOne());
            ProtosFutureValue outer =
                    assertInstanceOf(
                            ProtosFutureValue.class,
                            caller.result().orElseThrow());
            assertTrue(f.domain.dispatchOne());
            assertEquals(1, flushPrefix.get());
            assertEquals(ProtosFutureValue.State.PENDING, outer.state());

            assertTrue(gate.resolve(ProtosNullValue.INSTANCE, f.module));
            assertEquals(1, flushPrefix.get());
            assertEquals(ProtosFutureValue.State.PENDING, outer.state());
            assertTrue(f.domain.dispatchOne());
            assertEquals(ProtosFutureValue.State.RESOLVED, outer.state());
            assertSame(writer, outer.resolvedValue().orElseThrow());
            assertEquals(1, flushPrefix.get());
        }

        System.out.println("PLAT029_TEXT_WRITER_FLUSH_CALLBACK_CPRIME=PASS");
        System.out.println("PLAT029_TEXT_WRITER_CLOSE_INCLUDED=NO");
    }

    private static ProtosFutureValue invokeCPrimeReturningFuture(
            ProtosLanguage language,
            Fixture fixture,
            String characters,
            String sourceName)
            throws Exception {
        ProtosTask caller =
                execute(
                        fixture.domain,
                        fixture.module,
                        lowerRoot(language, characters, sourceName));
        assertTrue(fixture.domain.dispatchOne());
        assertEquals(ProtosTask.State.COMPLETED, caller.state());
        return assertInstanceOf(
                ProtosFutureValue.class,
                caller.result().orElseThrow());
    }

    private static ProtosObjectValue writer(Fixture f, boolean owning) {
        return assertInstanceOf(
                ProtosObjectValue.class,
                ProtosInvocation.invokeMessage(
                        f.prelude.bindings()
                                .readLocalSlot("TextWriter")
                                .orElseThrow(),
                        owning ? "owning" : "call",
                        List.of(f.target, f.encoding),
                        f.module));
    }

    private static ProtosFutureValue future(Fixture f) {
        return new ProtosFutureValue(
                f.prelude.futurePrototype(),
                f.domain);
    }

    private static Fixture fixture() throws Exception {
        ProtosPrelude prelude =
                new ProtosCoreBootstrap().bootstrap(Path.of("protos", "lib", "core"));
        ProtosActorExecutionDomain domain = new ProtosActorExecutionDomain();
        ProtosActivation module =
                prelude.newModuleActivation(
                        new ProtosActorModuleState(),
                        null,
                        prelude.newExecutionContext(),
                        domain);
        ProtosObjectValue target =
                new ProtosObjectValue(ProtosObjectValue.rootObject());
        ProtosEncodingValue encoding =
                assertInstanceOf(
                        ProtosEncodingValue.class,
                        prelude.encodingPrototype().readLocalSlot("UTF8").orElseThrow());
        return new Fixture(prelude, domain, module, target, encoding);
    }

    private static ProtosActivation siblingModule(ProtosActivation module) {
        return module.prelude().orElseThrow().newModuleActivation(
                module.actorModuleState(),
                module.currentModuleKey().orElse(null),
                module.context(),
                module.executionDomain());
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

    private static ProtosTask execute(
            ProtosActorExecutionDomain domain,
            ProtosActivation activation,
            ProtosSemanticBytecodeRootNode root) {
        return domain.createTask(
                null,
                current ->
                        ProtosBytecodeTaskExecution.execute(
                                current,
                                root.getCallTarget(),
                                activation));
    }

    private static ProtosSemanticBytecodeRootNode lowerRoot(
            ProtosLanguage language,
            String characters,
            String sourceName)
            throws Exception {
        return new CanonicalToBytecodeLowerer(
                        language,
                        source(characters, sourceName), ProtosExecutionMode.NORMAL)
                .lowerRoot(canonicalize(characters));
    }

    private static CanonicalClosure closureDefinition(String characters) {
        CanonicalSequence sequence = canonicalize(characters);
        assertEquals(1, sequence.expressions().size());
        return (CanonicalClosure) sequence.expressions().get(0);
    }

    private static CanonicalSequence canonicalize(String characters) {
        return (CanonicalSequence)
                new Canonicalizer()
                        .canonicalize(
                                new ProtosParser(characters)
                                        .parseProgram());
    }

    private static Source source(String characters, String sourceName)
            throws Exception {
        return Source.newBuilder(
                        ProtosLanguage.ID,
                        characters,
                        sourceName)
                .build();
    }

    private static void assertErrorParent(
            ProtosPrelude prelude,
            ProtosObjectValue error,
            String name) {
        assertSame(
                prelude.bindings().readLocalSlot(name).orElseThrow(),
                error.parent().orElseThrow());
    }

    private static LanguageScope languageScope() {
        Context context = Context.newBuilder(ProtosLanguage.ID).build();
        context.initialize(ProtosLanguage.ID);
        context.enter();
        return new LanguageScope(context, LANGUAGE_REF.get(null));
    }

    private record Fixture(
            ProtosPrelude prelude,
            ProtosActorExecutionDomain domain,
            ProtosActivation module,
            ProtosObjectValue target,
            ProtosEncodingValue encoding) {
        Fixture withModule(ProtosActivation replacement) {
            return new Fixture(prelude, domain, replacement, target, encoding);
        }
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
