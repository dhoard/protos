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

final class ProtosPerf006Plat029TextReaderCallbackTest {
    private static final LanguageReference<ProtosLanguage> LANGUAGE_REF =
            LanguageReference.create(ProtosLanguage.class);

    @Test
    void readLineRepeatsSuspendibleGuestReadsInsideOneOperationOwnedCPrime()
            throws Exception {
        try (LanguageScope scope = languageScope()) {
            Fixture f = fixture();
            ProtosFutureValue gate1 = future(f);
            ProtosFutureValue gate2 = future(f);
            ProtosFutureValue lower1 = future(f);
            ProtosFutureValue lower2 = future(f);
            lower1.resolve(bytes(f.prelude, 'A'), f.module);
            lower2.resolve(bytes(f.prelude, '\n'), f.module);
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
                            (activation, supplied) -> reads.get() == 1 ? gate1 : gate2));
            f.module.context().createLocalSlot(
                    "nextRead",
                    ProtosClosureValue.nativeClosure(
                            (activation, supplied) -> reads.get() == 1 ? lower1 : lower2));
            f.source.createLocalSlot(
                    "read",
                    sourceClosure(
                            scope.language(),
                            f.module,
                            "(max) => { readProbe()\nreadGate().value()\nnextRead() }",
                            "plat029-text-reader-repeat-source-read.protos"));

            ProtosObjectValue reader = reader(f, false);
            f.module.context().createLocalSlot("reader", reader);

            ProtosFutureValue outer =
                    invokeCPrimeReturningFuture(
                            scope.language(),
                            f,
                            "reader.readLine()",
                            "plat029-text-reader-read-line.protos");
            assertEquals(ProtosFutureValue.State.PENDING, outer.state());
            assertEquals(0, reads.get());
            assertEquals(0, f.domain.liveTaskCount());

            assertTrue(f.domain.dispatchOne());
            assertEquals(1, reads.get());
            assertEquals(ProtosFutureValue.State.PENDING, outer.state());
            assertEquals(0, f.domain.liveTaskCount());

            assertTrue(gate1.resolve(ProtosNullValue.INSTANCE, f.module));
            assertEquals(
                    1,
                    reads.get(),
                    "Future terminalization must not resume source.read guest code inline");
            assertEquals(ProtosFutureValue.State.PENDING, outer.state());

            assertTrue(f.domain.dispatchOne());
            assertEquals(
                    2,
                    reads.get(),
                    "the same C-prime operation must perform the second required source.read");
            assertEquals(ProtosFutureValue.State.PENDING, outer.state());

            assertTrue(gate2.resolve(ProtosNullValue.INSTANCE, f.module));
            assertEquals(2, reads.get());
            assertEquals(ProtosFutureValue.State.PENDING, outer.state());

            assertTrue(f.domain.dispatchOne());
            assertEquals(ProtosFutureValue.State.RESOLVED, outer.state());
            assertEquals(
                    "A",
                    assertInstanceOf(ProtosStringValue.class, outer.resolvedValue().orElseThrow())
                            .value());
            assertEquals(2, reads.get(), "source callback prefix must never replay");
            assertEquals(0, f.domain.liveTaskCount());
            assertFalse(f.domain.dispatchOne());
        }

        System.out.println("PLAT029_TEXT_READER_REPEATED_CALLBACK_CPRIME=PASS");
        System.out.println("PLAT029_TEXT_READER_CALLBACK_REPLAY=NO");
        System.out.println("PLAT029_TEXT_READER_BACKEND_INLINE_GUEST_REENTRY=NO");
    }

    @Test
    void cancelledReadPreservesLateReadAheadWithoutBackendGuestReentry()
            throws Exception {
        try (LanguageScope scope = languageScope()) {
            Fixture f = fixture();
            ProtosFutureValue lower = future(f);
            AtomicInteger reads = new AtomicInteger();
            AtomicInteger lowerCancelRequests = new AtomicInteger();
            lower.attachCancellationProducer(lowerCancelRequests::incrementAndGet);

            f.source.createLocalSlot(
                    "read",
                    ProtosClosureValue.nativeClosure(
                            (activation, supplied) -> {
                                reads.incrementAndGet();
                                assertTrue(activation.task().isEmpty());
                                assertTrue(activation.deferredCPrimeOperationForRuntime().isPresent());
                                return lower;
                            }));
            ProtosObjectValue reader = reader(f, false);
            f.module.context().createLocalSlot("reader", reader);

            ProtosFutureValue cancelled =
                    invokeCPrimeReturningFuture(
                            scope.language(),
                            f,
                            "reader.readText()",
                            "plat029-text-reader-cancelled-read.protos");
            assertTrue(f.domain.dispatchOne());
            assertEquals(1, reads.get());
            assertEquals(ProtosFutureValue.State.PENDING, cancelled.state());

            assertTrue(cancelled.cancelRequest());
            assertEquals(ProtosFutureValue.State.CANCELLED, cancelled.state());
            assertEquals(1, lowerCancelRequests.get());
            assertEquals(0, f.domain.liveTaskCount());

            assertTrue(lower.resolve(bytes(f.prelude, 'Z'), f.module));
            assertEquals(
                    ProtosFutureValue.State.CANCELLED,
                    cancelled.state(),
                    "late lower completion must not rewrite the cancelled outer Future");
            assertEquals(1, reads.get(), "late completion must not invoke guest code");

            ProtosActivation sibling = siblingModule(f.module);
            f.module = sibling;
            ProtosFutureValue next =
                    invokeCPrimeReturningFuture(
                            scope.language(),
                            f,
                            "reader.readText()",
                            "plat029-text-reader-late-read-ahead.protos");
            assertEquals(ProtosFutureValue.State.RESOLVED, next.state());
            assertEquals(
                    "Z",
                    assertInstanceOf(ProtosStringValue.class, next.resolvedValue().orElseThrow())
                            .value());
            assertEquals(
                    1,
                    reads.get(),
                    "late bytes from the cancelled read must feed the next ordered read");
            assertFalse(f.domain.dispatchOne());
        }

        System.out.println("PLAT029_TEXT_READER_LATE_READ_AHEAD=PASS");
        System.out.println("PLAT029_TEXT_READER_LATE_CALLBACK_GUEST_REENTRY=NO");
    }

    @SuppressWarnings("try")
    @Test
    void readOperationsAreSuspensionCapableButCloseRemainsOutsideThisSlice()
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
            ProtosObjectValue reader = reader(f, false);

            Object readTextSlot = reader.readLocalSlot("readText").orElseThrow();
            Object readLineSlot = reader.readLocalSlot("readLine").orElseThrow();
            Object closeSlot = reader.readLocalSlot("close").orElseThrow();
            assertInstanceOf(
                    ProtosSuspensionCapableNativeClosureBody.class,
                    assertInstanceOf(ProtosClosureValue.class, readTextSlot).nativeBody().orElseThrow());
            assertInstanceOf(
                    ProtosSuspensionCapableNativeClosureBody.class,
                    assertInstanceOf(ProtosClosureValue.class, readLineSlot).nativeBody().orElseThrow());
            assertFalse(
                    assertInstanceOf(ProtosClosureValue.class, closeSlot).nativeBody().orElseThrow()
                            instanceof ProtosSuspensionCapableNativeClosureBody);
        }

        System.out.println("PLAT029_TEXT_READER_READ_CALLBACK_CPRIME=YES");
        System.out.println("PLAT029_TEXT_READER_CLOSE_INCLUDED=NO");
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
        return assertInstanceOf(ProtosFutureValue.class, caller.result().orElseThrow());
    }

    private static ProtosObjectValue reader(Fixture f, boolean owning) {
        return assertInstanceOf(
                ProtosObjectValue.class,
                ProtosInvocation.invokeMessage(
                        f.prelude.bindings().readLocalSlot("TextReader").orElseThrow(),
                        owning ? "owning" : "call",
                        List.of(f.source, f.encoding),
                        f.module));
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
        ProtosObjectValue source = new ProtosObjectValue(ProtosObjectValue.rootObject());
        ProtosEncodingValue encoding =
                assertInstanceOf(
                        ProtosEncodingValue.class,
                        prelude.encodingPrototype().readLocalSlot("UTF8").orElseThrow());
        return new Fixture(prelude, domain, module, source, encoding);
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

    private static ProtosBytesValue bytes(ProtosPrelude prelude, int... values) {
        ProtosBytesValue bytes = new ProtosBytesValue(prelude.bytesPrototypeForRuntime());
        for (int value : values) {
            bytes.indexedAdd(new ProtosIntegerValue(value));
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
        ProtosActivation module;
        final ProtosObjectValue source;
        final ProtosEncodingValue encoding;

        Fixture(
                ProtosPrelude prelude,
                ProtosActorExecutionDomain domain,
                ProtosActivation module,
                ProtosObjectValue source,
                ProtosEncodingValue encoding) {
            this.prelude = prelude;
            this.domain = domain;
            this.module = module;
            this.source = source;
            this.encoding = encoding;
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
