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

final class ProtosPerf006Plat031BufferedWriterCPrimeTest {
    private static final LanguageReference<ProtosLanguage> LANGUAGE_REF =
            LanguageReference.create(ProtosLanguage.class);

    @Test
    void bufferedFlushOwnsWriteAndFlushGuestCallbacksInOneCPrime() throws Exception {
        try (LanguageScope scope = languageScope()) {
            Fixture f = fixture();
            ProtosFutureValue writeGate = future(f);
            ProtosFutureValue writeLower = future(f);
            ProtosFutureValue flushGate = future(f);
            ProtosFutureValue flushLower = future(f);
            AtomicInteger writes = new AtomicInteger();
            AtomicInteger flushes = new AtomicInteger();

            f.module.context().createLocalSlot(
                    "writeProbe",
                    ProtosClosureValue.nativeClosure(
                            (activation, supplied) -> {
                                writes.incrementAndGet();
                                return ProtosNullValue.INSTANCE;
                            }));
            f.module.context().createLocalSlot(
                    "flushProbe",
                    ProtosClosureValue.nativeClosure(
                            (activation, supplied) -> {
                                flushes.incrementAndGet();
                                return ProtosNullValue.INSTANCE;
                            }));
            f.module.context().createLocalSlot(
                    "writeGate",
                    ProtosClosureValue.nativeClosure(
                            (activation, supplied) -> writeGate));
            f.module.context().createLocalSlot(
                    "flushGate",
                    ProtosClosureValue.nativeClosure(
                            (activation, supplied) -> flushGate));
            f.module.context().createLocalSlot(
                    "nextWrite",
                    ProtosClosureValue.nativeClosure(
                            (activation, supplied) -> writeLower));
            f.module.context().createLocalSlot(
                    "nextFlush",
                    ProtosClosureValue.nativeClosure(
                            (activation, supplied) -> flushLower));

            f.target.createLocalSlot(
                    "write",
                    sourceClosure(
                            scope.language(),
                            f.module,
                            "(bytes) => { writeProbe()\nwriteGate().value()\nnextWrite() }",
                            "plat031-buffered-writer-target-write.protos"));
            f.target.createLocalSlot(
                    "flush",
                    sourceClosure(
                            scope.language(),
                            f.module,
                            "() => { flushProbe()\nflushGate().value()\nnextFlush() }",
                            "plat031-buffered-writer-target-flush.protos"));

            ProtosObjectValue writer = writer(f);
            ProtosFutureValue buffered =
                    assertInstanceOf(
                            ProtosFutureValue.class,
                            ProtosInvocation.invokeMessage(
                                    writer,
                                    "write",
                                    List.of(bytes(f.prelude, 1, 2, 3)),
                                    f.module));
            assertEquals(ProtosFutureValue.State.RESOLVED, buffered.state());

            f.module.context().createLocalSlot("writer", writer);
            ProtosFutureValue outer =
                    invokeCPrimeReturningFuture(
                            scope.language(),
                            f,
                            "writer.flush()",
                            "plat031-buffered-writer-flush.protos");
            assertEquals(ProtosFutureValue.State.PENDING, outer.state());
            assertEquals(0, writes.get());
            assertEquals(0, flushes.get());
            assertEquals(0, f.domain.liveTaskCount());

            assertTrue(f.domain.dispatchOne());
            assertEquals(1, writes.get());
            assertEquals(0, flushes.get());
            assertEquals(ProtosFutureValue.State.PENDING, outer.state());

            assertTrue(writeGate.resolve(ProtosNullValue.INSTANCE, f.module));
            assertEquals(1, writes.get());
            assertEquals(0, flushes.get());
            assertTrue(f.domain.dispatchOne());
            assertEquals(ProtosFutureValue.State.PENDING, outer.state());
            assertEquals(0, flushes.get());

            assertTrue(writeLower.resolve(f.target, f.module));
            assertEquals(0, flushes.get(), "lower write completion must not invoke guest flush inline");
            assertTrue(f.domain.dispatchOne());
            assertEquals(1, writes.get());
            assertEquals(1, flushes.get());
            assertEquals(ProtosFutureValue.State.PENDING, outer.state());

            assertTrue(flushGate.resolve(ProtosNullValue.INSTANCE, f.module));
            assertEquals(1, flushes.get());
            assertTrue(f.domain.dispatchOne());
            assertEquals(ProtosFutureValue.State.PENDING, outer.state());

            assertTrue(flushLower.resolve(f.target, f.module));
            assertEquals(ProtosFutureValue.State.PENDING, outer.state());
            assertTrue(f.domain.dispatchOne());
            assertEquals(ProtosFutureValue.State.RESOLVED, outer.state());
            assertSame(writer, outer.resolvedValue().orElseThrow());
            assertEquals(1, writes.get());
            assertEquals(1, flushes.get());
            assertEquals(0, f.domain.liveTaskCount());
            assertFalse(f.domain.dispatchOne());
        }

        System.out.println("PLAT031_BUFFERED_WRITER_FLUSH_CALLBACK_CPRIME=PASS");
        System.out.println("PLAT031_BUFFERED_WRITER_WRITE_CALLBACK_REPLAY=NO");
        System.out.println("PLAT031_BUFFERED_WRITER_FLUSH_CALLBACK_REPLAY=NO");
        System.out.println("PLAT031_BUFFERED_WRITER_HIDDEN_TASK=NO");
        System.out.println("PLAT031_BUFFERED_WRITER_BACKEND_INLINE_GUEST_REENTRY=NO");
    }

    @Test
    void unknownLowerWriteFailureBeatsPendingOuterCancellationUnderD117()
            throws Exception {
        try (LanguageScope scope = languageScope()) {
            Fixture f = fixture();
            ProtosFutureValue lower = future(f);
            AtomicInteger cancels = new AtomicInteger();
            AtomicInteger flushes = new AtomicInteger();
            lower.attachCancellationProducer(cancels::incrementAndGet);

            f.target.createLocalSlot(
                    "write",
                    ProtosClosureValue.nativeClosure(
                            (activation, supplied) -> lower));
            f.target.createLocalSlot(
                    "flush",
                    ProtosClosureValue.nativeClosure(
                            (activation, supplied) -> {
                                flushes.incrementAndGet();
                                ProtosFutureValue resolved = future(f);
                                resolved.resolve(f.target, activation);
                                return resolved;
                            }));

            ProtosObjectValue writer = writer(f);
            ProtosInvocation.invokeMessage(
                    writer,
                    "write",
                    List.of(bytes(f.prelude, 4, 5, 6)),
                    f.module);
            f.module.context().createLocalSlot("writer", writer);

            ProtosFutureValue outer =
                    invokeCPrimeReturningFuture(
                            scope.language(),
                            f,
                            "writer.flush()",
                            "plat031-buffered-writer-d117-failure.protos");
            assertTrue(f.domain.dispatchOne());
            assertEquals(ProtosFutureValue.State.PENDING, outer.state());

            assertTrue(outer.cancelRequest());
            assertEquals(ProtosFutureValue.State.PENDING, outer.state());
            assertEquals(1, cancels.get());

            ProtosObjectValue exact =
                    ProtosCoreErrors.newOccurrence(
                            f.module,
                            ProtosCoreErrors.StandardError.I_O_ERROR);
            assertTrue(lower.fail(exact));
            assertEquals(ProtosFutureValue.State.PENDING, outer.state());

            assertTrue(f.domain.dispatchOne());
            assertEquals(ProtosFutureValue.State.FAILED, outer.state());
            assertSame(exact, outer.failedError().orElseThrow());
            assertEquals(0, flushes.get());
        }

        System.out.println("D117_BUFFERED_CPRIME_UNKNOWN_EFFECT_FAILURE=EXACT");
        System.out.println("D117_BUFFERED_CPRIME_PENDING_CANCEL_OVERRIDDEN_BY_FAILURE=YES");
    }

    @Test
    void independentlyCancelledFirstLowerIsFailureNotOuterCancellation()
            throws Exception {
        try (LanguageScope scope = languageScope()) {
            Fixture f = fixture();
            ProtosFutureValue lower = future(f);
            AtomicInteger flushes = new AtomicInteger();

            f.target.createLocalSlot(
                    "write",
                    ProtosClosureValue.nativeClosure(
                            (activation, supplied) -> lower));
            f.target.createLocalSlot(
                    "flush",
                    ProtosClosureValue.nativeClosure(
                            (activation, supplied) -> {
                                flushes.incrementAndGet();
                                ProtosFutureValue resolved = future(f);
                                resolved.resolve(f.target, activation);
                                return resolved;
                            }));

            ProtosObjectValue writer = writer(f);
            ProtosInvocation.invokeMessage(
                    writer,
                    "write",
                    List.of(bytes(f.prelude, 7, 8)),
                    f.module);
            f.module.context().createLocalSlot("writer", writer);

            ProtosFutureValue outer =
                    invokeCPrimeReturningFuture(
                            scope.language(),
                            f,
                            "writer.flush()",
                            "plat031-buffered-writer-lower-cancel.protos");
            assertTrue(f.domain.dispatchOne());
            assertEquals(ProtosFutureValue.State.PENDING, outer.state());

            assertTrue(lower.cancelTerminal());
            assertEquals(ProtosFutureValue.State.PENDING, outer.state());
            assertTrue(f.domain.dispatchOne());

            assertEquals(ProtosFutureValue.State.FAILED, outer.state());
            assertSame(
                    f.prelude.bindings().readLocalSlot("IOError").orElseThrow(),
                    outer.failedError().orElseThrow().parent().orElseThrow());
            assertEquals(0, flushes.get());
        }

        System.out.println("D117_BUFFERED_CPRIME_ZERO_EFFECT_LOWER_CANCEL=FAILURE_WITHOUT_OUTER_CUTOVER");
    }

    @Test
    void cancelledPostCommitLowerFlushIsCommittedFailure() throws Exception {
        try (LanguageScope scope = languageScope()) {
            Fixture f = fixture();
            ProtosFutureValue lowerFlush = future(f);

            f.target.createLocalSlot(
                    "write",
                    ProtosClosureValue.nativeClosure(
                            (activation, supplied) -> {
                                ProtosFutureValue resolved = future(f);
                                resolved.resolve(f.target, activation);
                                return resolved;
                            }));
            f.target.createLocalSlot(
                    "flush",
                    ProtosClosureValue.nativeClosure(
                            (activation, supplied) -> lowerFlush));

            ProtosObjectValue writer = writer(f);
            ProtosInvocation.invokeMessage(
                    writer,
                    "write",
                    List.of(bytes(f.prelude, 9)),
                    f.module);
            f.module.context().createLocalSlot("writer", writer);

            ProtosFutureValue outer =
                    invokeCPrimeReturningFuture(
                            scope.language(),
                            f,
                            "writer.flush()",
                            "plat031-buffered-writer-postcommit-cancel.protos");

            assertTrue(f.domain.dispatchOne());
            assertEquals(ProtosFutureValue.State.PENDING, outer.state());

            assertTrue(lowerFlush.cancelTerminal());
            assertEquals(ProtosFutureValue.State.PENDING, outer.state());
            assertTrue(f.domain.dispatchOne());

            assertEquals(ProtosFutureValue.State.FAILED, outer.state());
            assertSame(
                    f.prelude.bindings().readLocalSlot("IOError").orElseThrow(),
                    outer.failedError().orElseThrow().parent().orElseThrow());
        }

        System.out.println("D117_BUFFERED_CPRIME_POST_COMMIT_LOWER_CANCEL=FAILURE");
    }

    @SuppressWarnings("try")
    @Test
    void bufferedFlushIsSuspensionCapableWhileWriteAndCloseRemainOrdinary()
            throws Exception {
        try (LanguageScope scope = languageScope()) {
            Fixture f = fixture();
            f.target.createLocalSlot(
                    "write",
                    ProtosClosureValue.nativeClosure(
                            (activation, supplied) -> {
                                ProtosFutureValue resolved = future(f);
                                resolved.resolve(f.target, activation);
                                return resolved;
                            }));

            ProtosObjectValue writer = writer(f);

            ProtosClosureValue write =
                    assertInstanceOf(
                            ProtosClosureValue.class,
                            writer.readLocalSlot("write").orElseThrow());
            ProtosClosureValue flush =
                    assertInstanceOf(
                            ProtosClosureValue.class,
                            writer.readLocalSlot("flush").orElseThrow());
            ProtosClosureValue close =
                    assertInstanceOf(
                            ProtosClosureValue.class,
                            writer.readLocalSlot("close").orElseThrow());

            assertFalse(
                    write.nativeBody().orElseThrow()
                            instanceof ProtosSuspensionCapableNativeClosureBody);
            assertInstanceOf(
                    ProtosSuspensionCapableNativeClosureBody.class,
                    flush.nativeBody().orElseThrow());
            assertFalse(
                    close.nativeBody().orElseThrow()
                            instanceof ProtosSuspensionCapableNativeClosureBody);
        }

        System.out.println("PLAT031_BUFFERED_WRITER_FLUSH_SUSPENSION_CAPABLE=YES");
        System.out.println("PLAT031_BUFFERED_WRITER_WRITE_LOCAL_LEAF=YES");
        System.out.println("PLAT031_BUFFERED_WRITER_CLOSE_INCLUDED=NO");
    }

    private static ProtosObjectValue writer(Fixture f) {
        return assertInstanceOf(
                ProtosObjectValue.class,
                ProtosInvocation.invokeMessage(
                        ProtosStandardModuleMemberTestSupport.bufferedWriterFactory(f.prelude),
                        "call",
                        List.of(f.target),
                        f.module));
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

    private static ProtosFutureValue future(Fixture f) {
        return new ProtosFutureValue(f.prelude.futurePrototype(), f.domain);
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
        return new Fixture(prelude, domain, module, target);
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
                                new ProtosParser(characters).parseProgram());
    }

    private static Source source(String characters, String sourceName)
            throws Exception {
        return Source.newBuilder(
                        ProtosLanguage.ID,
                        characters,
                        sourceName)
                .build();
    }

    private static ProtosBytesValue bytes(
            ProtosPrelude prelude,
            int... values) {
        ProtosBytesValue bytes =
                new ProtosBytesValue(prelude.bytesPrototypeForRuntime());
        for (int value : values) {
            bytes.indexedAdd(
                    new ProtosIntegerValue(value));
        }
        return bytes;
    }

    private static LanguageScope languageScope() {
        Context context = Context.newBuilder(ProtosLanguage.ID).build();
        context.initialize(ProtosLanguage.ID);
        context.enter();
        return new LanguageScope(context, LANGUAGE_REF.get(null));
    }

    private static final class Fixture {
        final ProtosPrelude prelude;
        final ProtosActorExecutionDomain domain;
        final ProtosActivation module;
        final ProtosObjectValue target;

        Fixture(
                ProtosPrelude prelude,
                ProtosActorExecutionDomain domain,
                ProtosActivation module,
                ProtosObjectValue target) {
            this.prelude = prelude;
            this.domain = domain;
            this.module = module;
            this.target = target;
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
