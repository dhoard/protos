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

final class ProtosPerf006Plat031BufferedReaderCPrimeTest {
    private static final LanguageReference<ProtosLanguage> LANGUAGE_REF =
            LanguageReference.create(ProtosLanguage.class);

    @Test
    void bufferedReadRunsSuspendibleGuestCallbackInsideOperationOwnedCPrime()
            throws Exception {
        try (LanguageScope scope = languageScope()) {
            Fixture f = fixture();
            ProtosFutureValue gate = future(f);
            ProtosFutureValue lower = future(f);
            lower.resolve(bytes(f.prelude, 'A', 'B'), f.module);
            AtomicInteger reads = new AtomicInteger();

            f.module.context().createLocalSlot(
                    "readProbe",
                    ProtosClosureValue.nativeClosure(
                            (activation, supplied) -> {
                                reads.incrementAndGet();
                                return ProtosNullValue.INSTANCE;
                            }));
            f.module.context().createLocalSlot(
                    "readGate",
                    ProtosClosureValue.nativeClosure(
                            (activation, supplied) -> gate));
            f.module.context().createLocalSlot(
                    "nextRead",
                    ProtosClosureValue.nativeClosure(
                            (activation, supplied) -> lower));
            f.source.createLocalSlot(
                    "read",
                    sourceClosure(
                            scope.language(),
                            f.module,
                            "(max) => { readProbe()\nreadGate().value()\nnextRead() }",
                            "plat031-buffered-reader-source-read.protos"));

            ProtosObjectValue reader = reader(f);
            f.module.context().createLocalSlot("reader", reader);

            ProtosFutureValue outer =
                    invokeCPrimeReturningFuture(
                            scope.language(),
                            f,
                            "reader.read(1)",
                            "plat031-buffered-reader-read.protos");
            assertEquals(ProtosFutureValue.State.PENDING, outer.state());
            assertEquals(0, reads.get());
            assertEquals(0, f.domain.liveTaskCount());

            assertTrue(f.domain.dispatchOne());
            assertEquals(1, reads.get());
            assertEquals(ProtosFutureValue.State.PENDING, outer.state());
            assertEquals(0, f.domain.liveTaskCount());

            assertTrue(gate.resolve(ProtosNullValue.INSTANCE, f.module));
            assertEquals(
                    1,
                    reads.get(),
                    "Future terminalization must not resume target.read guest code inline");
            assertEquals(ProtosFutureValue.State.PENDING, outer.state());

            assertTrue(f.domain.dispatchOne());
            assertEquals(ProtosFutureValue.State.RESOLVED, outer.state());
            assertBytes(outer.resolvedValue().orElseThrow(), 'A');
            assertEquals(1, reads.get());
            assertEquals(0, f.domain.liveTaskCount());

            f.module = siblingModule(f.module);
            ProtosFutureValue buffered =
                    invokeCPrimeReturningFuture(
                            scope.language(),
                            f,
                            "reader.read(1)",
                            "plat031-buffered-reader-read-ahead.protos");
            assertEquals(ProtosFutureValue.State.RESOLVED, buffered.state());
            assertBytes(buffered.resolvedValue().orElseThrow(), 'B');
            assertEquals(
                    1,
                    reads.get(),
                    "retained read-ahead must satisfy the next read without replaying target.read");
            assertFalse(f.domain.dispatchOne());
        }

        System.out.println("PLAT031_BUFFERED_READER_READ_CALLBACK_CPRIME=PASS");
        System.out.println("PLAT031_BUFFERED_READER_CALLBACK_REPLAY=NO");
        System.out.println("PLAT031_BUFFERED_READER_HIDDEN_TASK=NO");
        System.out.println("PLAT031_BUFFERED_READER_BACKEND_INLINE_GUEST_REENTRY=NO");
    }

    @Test
    void cancelledBufferedReadDiscardsLateReadAheadAndUnblocksNextRead()
            throws Exception {
        try (LanguageScope scope = languageScope()) {
            Fixture f = fixture();
            ProtosFutureValue lower1 = future(f);
            ProtosFutureValue lower2 = future(f);
            lower2.resolve(bytes(f.prelude, 'Y'), f.module);
            AtomicInteger reads = new AtomicInteger();
            AtomicInteger lowerCancelRequests = new AtomicInteger();
            lower1.attachCancellationProducer(lowerCancelRequests::incrementAndGet);

            f.source.createLocalSlot(
                    "read",
                    ProtosClosureValue.nativeClosure(
                            (activation, supplied) -> {
                                int index = reads.incrementAndGet();
                                assertTrue(activation.task().isEmpty());
                                assertTrue(
                                        activation
                                                .deferredCPrimeOperationForRuntime()
                                                .isPresent());
                                return index == 1 ? lower1 : lower2;
                            }));

            ProtosObjectValue reader = reader(f);
            f.module.context().createLocalSlot("reader", reader);

            ProtosFutureValue cancelled =
                    invokeCPrimeReturningFuture(
                            scope.language(),
                            f,
                            "reader.read(1)",
                            "plat031-buffered-reader-cancel.protos");
            assertEquals(ProtosFutureValue.State.PENDING, cancelled.state());

            assertTrue(f.domain.dispatchOne());
            assertEquals(1, reads.get());
            assertEquals(ProtosFutureValue.State.PENDING, cancelled.state());

            assertTrue(cancelled.cancelRequest());
            assertEquals(ProtosFutureValue.State.CANCELLED, cancelled.state());
            assertEquals(1, lowerCancelRequests.get());
            assertEquals(0, f.domain.liveTaskCount());

            assertTrue(lower1.resolve(bytes(f.prelude, 'Z'), f.module));
            assertEquals(ProtosFutureValue.State.CANCELLED, cancelled.state());
            assertEquals(
                    1,
                    reads.get(),
                    "late lower completion must remain an inert cleanup callback");

            f.module = siblingModule(f.module);
            ProtosFutureValue next =
                    invokeCPrimeReturningFuture(
                            scope.language(),
                            f,
                            "reader.read(1)",
                            "plat031-buffered-reader-after-cancel.protos");
            assertEquals(ProtosFutureValue.State.PENDING, next.state());
            assertTrue(f.domain.dispatchOne());
            assertEquals(ProtosFutureValue.State.RESOLVED, next.state());
            assertBytes(next.resolvedValue().orElseThrow(), 'Y');
            assertEquals(
                    2,
                    reads.get(),
                    "late bytes from the cancelled buffered read must be discarded");
            assertFalse(f.domain.dispatchOne());
        }

        System.out.println("PLAT031_BUFFERED_READER_LATE_READ_AHEAD=DISCARDED");
        System.out.println("PLAT031_BUFFERED_READER_LATE_CALLBACK_GUEST_REENTRY=NO");
    }

    @SuppressWarnings("try")
    @Test
    void bufferedReadIsSuspensionCapableButCloseRemainsOutsideThisSlice()
            throws Exception {
        try (LanguageScope scope = languageScope()) {
            Fixture f = fixture();
            f.source.createLocalSlot(
                    "read",
                    ProtosClosureValue.nativeClosure(
                            (activation, supplied) -> {
                                ProtosFutureValue eof = future(f);
                                eof.resolve(ProtosNullValue.INSTANCE, activation);
                                return eof;
                            }));
            ProtosObjectValue reader = reader(f);

            Object readSlot = reader.readLocalSlot("read").orElseThrow();
            Object closeSlot = reader.readLocalSlot("close").orElseThrow();
            assertInstanceOf(
                    ProtosSuspensionCapableNativeClosureBody.class,
                    assertInstanceOf(ProtosClosureValue.class, readSlot)
                            .nativeBody()
                            .orElseThrow());
            assertFalse(
                    assertInstanceOf(ProtosClosureValue.class, closeSlot)
                                    .nativeBody()
                                    .orElseThrow()
                            instanceof ProtosSuspensionCapableNativeClosureBody);
        }

        System.out.println("PLAT031_BUFFERED_READER_READ_SUSPENSION_CAPABLE=YES");
        System.out.println("PLAT031_BUFFERED_READER_CLOSE_INCLUDED=NO");
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

    private static ProtosObjectValue reader(Fixture f) {
        return assertInstanceOf(
                ProtosObjectValue.class,
                ProtosInvocation.invokeMessage(
                        ProtosStandardModuleMemberTestSupport.bufferedReaderFactory(f.prelude),
                        "call",
                        List.of(f.source),
                        f.module));
    }

    private static ProtosActivation siblingModule(ProtosActivation module) {
        return module.prelude().orElseThrow().newModuleActivation(
                module.actorModuleState(),
                module.currentModuleKey().orElse(null),
                module.context(),
                module.executionDomain());
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
        ProtosObjectValue source =
                new ProtosObjectValue(ProtosObjectValue.rootObject());
        return new Fixture(prelude, domain, module, source);
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

    private static void assertBytes(Object value, int... expected) {
        ProtosBytesValue bytes =
                assertInstanceOf(ProtosBytesValue.class, value);
        assertEquals(expected.length, bytes.indexedSize());
        for (int index = 0; index < expected.length; index++) {
            assertEquals(Long.toString(expected[index]), com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerDecimalText(bytes.indexedAt(index)));
        }
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
        ProtosActivation module;
        final ProtosObjectValue source;

        Fixture(
                ProtosPrelude prelude,
                ProtosActorExecutionDomain domain,
                ProtosActivation module,
                ProtosObjectValue source) {
            this.prelude = prelude;
            this.domain = domain;
            this.module = module;
            this.source = source;
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
