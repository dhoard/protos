/* APL-1.0 licensed work; see LICENSE.TXT. */
package com.guillermomolina.protos.execution;

import static org.junit.jupiter.api.Assertions.*;

import com.guillermomolina.protos.runtime.*;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

final class ProtosTextIoFinalConformanceTest {
    private static final Path CORE = Path.of("protos", "lib", "core");

    @Test
    void hostDescriptorSupportsOneShotReaderWriterIndependentFlowStateAndCloseFinalization()
            throws Exception {
        ProtosPrelude prelude = core();
        ProtosActivation activation = prelude.newModuleActivation();
        ProtosEncodingValue host = host(prelude, ProtosEncodingValue.DecodingErrorPolicy.STRICT);

        assertArrayEquals(
                new byte[] {0x0e, 'a', 0x0f, 'b'},
                host.encodeForRuntime("Ab"));
        assertEquals(
                "Ab",
                host.decodeForRuntime(new byte[] {0x0e, 'a', 0x0f, 'b'}));

        ScriptedSource source = new ScriptedSource(activation);
        source.bytes(0x0e, 'a');
        source.bytes('b', 0x0f, 'c');
        source.eof();
        ProtosObjectValue reader = reader(prelude, activation, source.source, host);
        assertEquals(
                "A",
                string(readText(reader, activation)),
                "the earliest useful host-decoded text must complete without waiting for later input");
        assertEquals(
                "Bc",
                readAllText(reader, activation),
                "later readText chunk boundaries are implementation-selectable; concatenation is normative");

        RecordingTarget firstTarget = new RecordingTarget(activation);
        ProtosObjectValue firstWriter = writer(prelude, activation, firstTarget.target, host);
        assertResolved(writeText(firstWriter, activation, "A"));
        assertResolved(writeText(firstWriter, activation, ""));
        assertResolved(writeText(firstWriter, activation, "B"));
        assertEquals(2, firstTarget.payloads.size());
        assertArrayEquals(new byte[] {0x0e, 'a'}, firstTarget.payloads.get(0));
        assertArrayEquals(new byte[] {'b'}, firstTarget.payloads.get(1));
        assertResolved(close(firstWriter, activation));
        assertEquals(3, firstTarget.payloads.size());
        assertArrayEquals(
                new byte[] {0x0f},
                firstTarget.payloads.get(2),
                "close must propagate the host encoder's committed final shift bytes");

        RecordingTarget secondTarget = new RecordingTarget(activation);
        ProtosObjectValue secondWriter = writer(prelude, activation, secondTarget.target, host);
        assertResolved(writeText(secondWriter, activation, "C"));
        assertArrayEquals(
                new byte[] {0x0e, 'c'},
                secondTarget.payloads.get(0),
                "reusing one Encoding descriptor must create fresh per-flow encoder state");
    }

    @Test
    void portableReplacementUsesMaximalSubpartsIndependentOfReadChunking() throws Exception {
        ProtosPrelude prelude = core();
        ProtosActivation activation = prelude.newModuleActivation();
        ProtosEncodingValue replacement =
                ProtosEncodingValue.portableConfiguredForRuntime(
                        prelude.encodingPrototype(),
                        ProtosEncodingValue.PortableKind.UTF8,
                        ProtosEncodingValue.DecodingErrorPolicy.REPLACE,
                        ProtosEncodingValue.InitialBomPolicy.CONSUME);

        byte[] illFormed =
                new byte[] {(byte) 0xe1, (byte) 0x80, 'A', (byte) 0xf0, (byte) 0x9f, (byte) 0x92};
        assertEquals("\ufffdA\ufffd", replacement.decodeForRuntime(illFormed));

        ScriptedSource source = new ScriptedSource(activation);
        source.bytes(0xe1);
        source.bytes(0x80);
        source.bytes('A', 0xf0, 0x9f);
        source.bytes(0x92);
        source.eof();
        ProtosObjectValue reader = reader(prelude, activation, source.source, replacement);
        assertEquals(
                "\ufffdA\ufffd",
                readAllText(reader, activation),
                "replacement segmentation and decoded concatenation must not depend on native read chunking");

        ScriptedSource singleChunk = new ScriptedSource(activation);
        singleChunk.bytes(0xe1, 0x80, 'A', 0xf0, 0x9f, 0x92);
        singleChunk.eof();
        ProtosObjectValue singleReader =
                reader(prelude, activation, singleChunk.source, replacement);
        assertEquals(
                "\ufffdA\ufffd",
                readAllText(singleReader, activation),
                "the same source octets must decode identically under a different backend chunking");

        ProtosEncodingValue strict = encoding(prelude, "UTF8");
        assertThrows(
                ProtosEncodingValue.ConversionFailure.class,
                () -> strict.decodeForRuntime(illFormed));
    }

    @Test
    void replacementSourceExtentParticipatesExactlyInLineBudget() throws Exception {
        ProtosPrelude prelude = core();
        ProtosActivation activation = prelude.newModuleActivation();
        ProtosEncodingValue replacement =
                ProtosEncodingValue.portableConfiguredForRuntime(
                        prelude.encodingPrototype(),
                        ProtosEncodingValue.PortableKind.UTF8,
                        ProtosEncodingValue.DecodingErrorPolicy.REPLACE,
                        ProtosEncodingValue.InitialBomPolicy.CONSUME);

        ScriptedSource exactSource = new ScriptedSource(activation);
        exactSource.bytes(0xe1, 0x80, '\n');
        ProtosObjectValue exact = reader(prelude, activation, exactSource.source, replacement);
        assertEquals(
                "\ufffd",
                string(
                        readLine(
                                exact,
                                activation,
                                new ProtosIntegerValue(2))));

        ScriptedSource shortSource = new ScriptedSource(activation);
        shortSource.bytes(0xe1, 0x80, '\n');
        ProtosObjectValue shortReader = reader(prelude, activation, shortSource.source, replacement);
        ProtosFutureValue tooLong =
                readLine(
                        shortReader,
                        activation,
                        new ProtosIntegerValue(1L));
        assertEquals(ProtosFutureValue.State.FAILED, tooLong.state());
        assertErrorParent(prelude, tooLong.failedError().orElseThrow(), "LineTooLong");
    }

    @Test
    void explicitBomPreservationProducesTextAndCountsAsLineContent() throws Exception {
        ProtosPrelude prelude = core();
        ProtosActivation activation = prelude.newModuleActivation();
        ProtosEncodingValue preserve =
                ProtosEncodingValue.portableConfiguredForRuntime(
                        prelude.encodingPrototype(),
                        ProtosEncodingValue.PortableKind.UTF8,
                        ProtosEncodingValue.DecodingErrorPolicy.STRICT,
                        ProtosEncodingValue.InitialBomPolicy.PRESERVE);

        byte[] bytes = new byte[] {(byte) 0xef, (byte) 0xbb, (byte) 0xbf, 'A'};
        assertEquals("\ufeffA", preserve.decodeForRuntime(bytes));

        ScriptedSource source = new ScriptedSource(activation);
        source.bytes(0xef, 0xbb, 0xbf, 'A');
        source.eof();
        ProtosObjectValue reader = reader(prelude, activation, source.source, preserve);
        assertEquals(
                "\ufeffA",
                readAllText(reader, activation),
                "preserved BOM is ordinary U+FEFF text; readText may batch it with later decoded text");

        ScriptedSource boundedSource = new ScriptedSource(activation);
        boundedSource.bytes(0xef, 0xbb, 0xbf, 'A', '\n');
        ProtosObjectValue bounded = reader(prelude, activation, boundedSource.source, preserve);
        ProtosFutureValue tooLong =
                readLine(
                        bounded,
                        activation,
                        new ProtosIntegerValue(1L));
        assertEquals(ProtosFutureValue.State.FAILED, tooLong.state());
        assertErrorParent(prelude, tooLong.failedError().orElseThrow(), "LineTooLong");
    }

    @Test
    void hostReplacementPolicyIsDescriptorStateNotConverterCallChunking() throws Exception {
        ProtosPrelude prelude = core();
        ProtosActivation activation = prelude.newModuleActivation();
        ProtosEncodingValue replacement = host(prelude, ProtosEncodingValue.DecodingErrorPolicy.REPLACE);
        ProtosEncodingValue strict = host(prelude, ProtosEncodingValue.DecodingErrorPolicy.STRICT);

        assertEquals("\ufffda", replacement.decodeForRuntime(new byte[] {(byte) 0xff, 'a'}));
        assertThrows(
                ProtosEncodingValue.ConversionFailure.class,
                () -> strict.decodeForRuntime(new byte[] {(byte) 0xff, 'a'}));

        ScriptedSource source = new ScriptedSource(activation);
        source.bytes(0xff);
        source.bytes('a');
        source.eof();
        ProtosObjectValue reader = reader(prelude, activation, source.source, replacement);
        assertEquals(
                "\ufffda",
                readAllText(reader, activation),
                "host replacement output is defined by the descriptor contract, not readText chunk boundaries");
    }

    private static ProtosPrelude core() throws Exception {
        return new ProtosCoreBootstrap().bootstrap(CORE);
    }

    private static ProtosEncodingValue host(
            ProtosPrelude prelude, ProtosEncodingValue.DecodingErrorPolicy policy) {
        return ProtosEncodingValue.hostProvidedForRuntime(
                prelude.encodingPrototype(),
                new ProtosHostEncodingTestCodec(),
                policy,
                ProtosEncodingValue.InitialBomPolicy.CONSUME);
    }

    private static ProtosEncodingValue encoding(ProtosPrelude prelude, String name) {
        return assertInstanceOf(
                ProtosEncodingValue.class,
                prelude.encodingPrototype().readLocalSlot(name).orElseThrow());
    }

    private static ProtosObjectValue reader(
            ProtosPrelude prelude,
            ProtosActivation activation,
            Object source,
            ProtosEncodingValue encoding) {
        ProtosObjectValue factory =
                assertInstanceOf(
                        ProtosObjectValue.class,
                        prelude.bindings().readLocalSlot("TextReader").orElseThrow());
        return assertInstanceOf(
                ProtosObjectValue.class,
                com.guillermomolina.protos.execution.ProtosTestExecutionSupport.callEnteredAndDrain(activation, () -> ProtosInvocation.invokeMessage(
                        factory, "call", List.of(source, encoding), activation)));
    }

    private static ProtosObjectValue writer(
            ProtosPrelude prelude,
            ProtosActivation activation,
            Object target,
            ProtosEncodingValue encoding) {
        ProtosObjectValue factory =
                assertInstanceOf(
                        ProtosObjectValue.class,
                        prelude.bindings().readLocalSlot("TextWriter").orElseThrow());
        return assertInstanceOf(
                ProtosObjectValue.class,
                com.guillermomolina.protos.execution.ProtosTestExecutionSupport.callEnteredAndDrain(activation, () -> ProtosInvocation.invokeMessage(
                        factory, "call", List.of(target, encoding), activation)));
    }

    private static ProtosFutureValue readText(
            ProtosObjectValue reader, ProtosActivation activation) {
        return assertInstanceOf(
                ProtosFutureValue.class,
                com.guillermomolina.protos.execution.ProtosTestExecutionSupport.callEnteredAndDrain(activation, () -> ProtosInvocation.invokeMessage(reader, "readText", List.of(), activation)));
    }

    private static ProtosFutureValue readLine(
            ProtosObjectValue reader, ProtosActivation activation, Object argument) {
        return assertInstanceOf(
                ProtosFutureValue.class,
                com.guillermomolina.protos.execution.ProtosTestExecutionSupport.callEnteredAndDrain(activation, () -> ProtosInvocation.invokeMessage(reader, "readLine", List.of(argument), activation)));
    }

    private static ProtosFutureValue writeText(
            ProtosObjectValue writer, ProtosActivation activation, String text) {
        return assertInstanceOf(
                ProtosFutureValue.class,
                com.guillermomolina.protos.execution.ProtosTestExecutionSupport.callEnteredAndDrain(activation, () -> ProtosInvocation.invokeMessage(
                        writer,
                        "writeText",
                        List.of(new ProtosStringValue(text)),
                        activation)));
    }

    private static ProtosFutureValue close(
            ProtosObjectValue writer, ProtosActivation activation) {
        return assertInstanceOf(
                ProtosFutureValue.class,
                com.guillermomolina.protos.execution.ProtosTestExecutionSupport.callEnteredAndDrain(activation, () -> ProtosInvocation.invokeMessage(writer, "close", List.of(), activation)));
    }

    private static String string(ProtosFutureValue future) {
        assertEquals(ProtosFutureValue.State.RESOLVED, future.state());
        return assertInstanceOf(ProtosStringValue.class, future.resolvedValue().orElseThrow()).value();
    }

    private static String readAllText(
            ProtosObjectValue reader, ProtosActivation activation) {
        StringBuilder text = new StringBuilder();
        while (true) {
            ProtosFutureValue future = readText(reader, activation);
            assertEquals(ProtosFutureValue.State.RESOLVED, future.state());
            Object value = future.resolvedValue().orElseThrow();
            if (value == ProtosNullValue.INSTANCE) {
                return text.toString();
            }
            ProtosStringValue chunk = assertInstanceOf(ProtosStringValue.class, value);
            assertFalse(chunk.value().isEmpty(), "readText must never resolve to an empty String");
            text.append(chunk.value());
        }
    }

    private static void assertResolved(ProtosFutureValue future) {
        assertEquals(ProtosFutureValue.State.RESOLVED, future.state());
    }

    private static void assertNullResult(ProtosFutureValue future) {
        assertEquals(ProtosFutureValue.State.RESOLVED, future.state());
        assertSame(ProtosNullValue.INSTANCE, future.resolvedValue().orElseThrow());
    }

    private static void assertErrorParent(
            ProtosPrelude prelude, ProtosObjectValue error, String parentName) {
        assertSame(
                prelude.bindings().readLocalSlot(parentName).orElseThrow(),
                error.parent().orElseThrow());
    }

    private static ProtosBytesValue bytes(ProtosPrelude prelude, int... values) {
        ProtosBytesValue bytes = new ProtosBytesValue(prelude.bytesPrototypeForRuntime());
        for (int value : values) {
            bytes.indexedAdd(new ProtosIntegerValue(value & 0xff));
        }
        return bytes;
    }

    private static final class ScriptedSource {
        private sealed interface Step permits BytesStep, EofStep {}
        private record BytesStep(int[] values) implements Step {}
        private record EofStep() implements Step {}

        final ProtosObjectValue source = new ProtosObjectValue(ProtosObjectValue.rootObject());
        final ArrayDeque<Step> steps = new ArrayDeque<>();

        ScriptedSource(ProtosActivation constructionActivation) {
            source.createLocalSlot(
                    "read",
                    ProtosClosureValue.nativeClosure(
                            (activation, supplied) -> {
                                Step step = steps.pollFirst();
                                if (step == null) throw new AssertionError("no scripted source step");
                                ProtosFutureValue future =
                                        new ProtosFutureValue(
                                                activation.prelude().orElseThrow().futurePrototype(),
                                                activation.executionDomain());
                                if (step instanceof BytesStep payload) {
                                    future.resolve(
                                            ProtosTextIoFinalConformanceTest.bytes(
                                                    activation.prelude().orElseThrow(),
                                                    payload.values()),
                                            activation);
                                } else {
                                    future.resolve(ProtosNullValue.INSTANCE, activation);
                                }
                                return future;
                            }));
        }

        void bytes(int... values) { steps.addLast(new BytesStep(values.clone())); }
        void eof() { steps.addLast(new EofStep()); }
    }

    private static final class RecordingTarget {
        final ProtosObjectValue target = new ProtosObjectValue(ProtosObjectValue.rootObject());
        final ArrayList<byte[]> payloads = new ArrayList<>();

        RecordingTarget(ProtosActivation constructionActivation) {
            target.createLocalSlot(
                    "write",
                    ProtosClosureValue.nativeClosure(
                            (activation, supplied) -> {
                                ProtosBytesValue value =
                                        assertInstanceOf(ProtosBytesValue.class, supplied.get(0));
                                payloads.add(snapshot(value));
                                ProtosFutureValue future =
                                        new ProtosFutureValue(
                                                activation.prelude().orElseThrow().futurePrototype(),
                                                activation.executionDomain());
                                future.resolve(target, activation);
                                return future;
                            }));
        }

        private static byte[] snapshot(ProtosBytesValue bytes) {
            List<Object> values = bytes.indexedSnapshot();
            byte[] result = new byte[values.size()];
            for (int index = 0; index < values.size(); index++) {
                int value = Integer.parseInt(com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerDecimalText(values.get(index)));
                result[index] = (byte) value;
            }
            return result;
        }
    }
}
