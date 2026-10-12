/* APL-1.0 licensed work; see LICENSE.TXT. */
package com.guillermomolina.protos.execution;

import static org.junit.jupiter.api.Assertions.*;

import com.guillermomolina.protos.runtime.*;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ProtosStandardFileProtocolTest {
    private static ProtosPrelude core() throws Exception {
        return new ProtosCoreBootstrap().bootstrap(Path.of("protos", "lib", "core"));
    }

    private static ProtosIntegerValue integer(long value) {
        return new ProtosIntegerValue(value);
    }

    private static ProtosBytesValue bytes(ProtosObjectValue prototype, int... octets) {
        ProtosBytesValue value = new ProtosBytesValue(prototype);
        for (int octet : octets) {
            value.indexedAdd(integer(octet));
        }
        return value;
    }

    private static Fixture fixture() throws Exception {
        ProtosPrelude prelude = core();
        ProtosActivation activation = prelude.newModuleActivation();
        ProtosObjectValue bytesPrototype =
                new ProtosObjectValue(ProtosObjectValue.rootObject());
        ProtosStandardBytesProtocol.install(bytesPrototype);
        TestResource resource = new TestResource();
        ProtosFileFlow.Capabilities capabilities =
                new ProtosFileFlow.Capabilities(true, true, true, true, true, true);
        ProtosObjectValue file =
                ProtosStandardFileProtocol.create(
                        bytesPrototype, activation, resource, capabilities);
        return new Fixture(prelude, activation, bytesPrototype, resource, file);
    }

    @Test
    void capabilityShapeIsStableAndDoesNotInventFlushable() throws Exception {
        ProtosPrelude prelude = core();
        ProtosActivation activation = prelude.newModuleActivation();
        ProtosObjectValue bytesPrototype =
                new ProtosObjectValue(ProtosObjectValue.rootObject());
        ProtosStandardBytesProtocol.install(bytesPrototype);
        TestResource resource = new TestResource();
        ProtosObjectValue file =
                ProtosStandardFileProtocol.create(
                        bytesPrototype,
                        activation,
                        resource,
                        new ProtosFileFlow.Capabilities(true, false, false, false, false, false));

        assertTrue(file.hasLocalSlot("read"));
        assertTrue(file.hasLocalSlot("close"));
        for (String absent :
                List.of(
                        "write",
                        "position",
                        "seek",
                        "seekBy",
                        "seekToEnd",
                        "size",
                        "truncate",
                        "sync",
                        "flush")) {
            assertFalse(file.hasLocalSlot(absent), absent);
        }
    }

    @Test
    void positionedReadAndWriteUseRuntimeLogicalCursorAndInvocationOrdering() throws Exception {
        Fixture fixture = fixture();

        ProtosFutureValue read =
                (ProtosFutureValue)
                        ProtosInvocation.invokeMessage(
                                fixture.file, "read", List.of(integer(4)), fixture.activation);
        ProtosFutureValue positionAfterRead =
                (ProtosFutureValue)
                        ProtosInvocation.invokeMessage(
                                fixture.file, "position", List.of(), fixture.activation);

        assertEquals(0L, fixture.resource.readPositions.remove());
        assertEquals(ProtosFutureValue.State.PENDING, positionAfterRead.state());

        fixture.resource.readCompletions.remove().data(new byte[] {1, 2});
        assertEquals(ProtosFutureValue.State.RESOLVED, read.state());
        assertEquals(String.valueOf("2"), com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerDecimalText(positionAfterRead.resolvedValue().orElseThrow()));

        ProtosFutureValue write =
                (ProtosFutureValue)
                        ProtosInvocation.invokeMessage(
                                fixture.file,
                                "write",
                                List.of(bytes(fixture.bytesPrototype, 7, 8, 9)),
                                fixture.activation);
        assertEquals(2L, fixture.resource.writePositions.remove());
        ProtosFileFlow.WriteCompletion completion = fixture.resource.writeCompletions.remove();
        assertTrue(completion.commitFirstContribution());
        completion.succeeded();
        assertSame(fixture.file, write.resolvedValue().orElseThrow());

        ProtosFutureValue position =
                (ProtosFutureValue)
                        ProtosInvocation.invokeMessage(
                                fixture.file, "position", List.of(), fixture.activation);
        assertEquals(String.valueOf("5"), com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerDecimalText(position.resolvedValue().orElseThrow()));
    }

    @Test
    void cancellationAndFailedWritePrefixHaveExactPositionAftermath() throws Exception {
        Fixture fixture = fixture();

        ProtosFutureValue read =
                (ProtosFutureValue)
                        ProtosInvocation.invokeMessage(
                                fixture.file, "read", List.of(integer(3)), fixture.activation);
        assertTrue(read.cancelRequest());
        assertEquals(1, fixture.resource.cancellations.get());
        fixture.resource.readCompletions.remove().data(new byte[] {1, 2, 3});

        ProtosFutureValue zero =
                (ProtosFutureValue)
                        ProtosInvocation.invokeMessage(
                                fixture.file, "position", List.of(), fixture.activation);
        assertEquals(String.valueOf("0"), com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerDecimalText(zero.resolvedValue().orElseThrow()));

        ProtosFutureValue write =
                (ProtosFutureValue)
                        ProtosInvocation.invokeMessage(
                                fixture.file,
                                "write",
                                List.of(bytes(fixture.bytesPrototype, 10, 11, 12)),
                                fixture.activation);
        ProtosFileFlow.WriteCompletion completion = fixture.resource.writeCompletions.remove();
        assertTrue(completion.commitFirstContribution());
        completion.failed(2);
        assertEquals(ProtosFutureValue.State.FAILED, write.state());

        ProtosFutureValue two =
                (ProtosFutureValue)
                        ProtosInvocation.invokeMessage(
                                fixture.file, "position", List.of(), fixture.activation);
        assertEquals(String.valueOf("2"), com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerDecimalText(two.resolvedValue().orElseThrow()));
    }

    @Test
    void seekSizeTruncateAndSyncPreservePositionAndCommitment() throws Exception {
        Fixture fixture = fixture();

        ProtosFutureValue seek =
                (ProtosFutureValue)
                        ProtosInvocation.invokeMessage(
                                fixture.file, "seek", List.of(integer(10)), fixture.activation);
        assertEquals(String.valueOf("10"), com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerDecimalText(seek.resolvedValue().orElseThrow()));

        ProtosFutureValue invalidRelative =
                (ProtosFutureValue)
                        ProtosInvocation.invokeMessage(
                                fixture.file, "seekBy", List.of(integer(-11)), fixture.activation);
        assertEquals(ProtosFutureValue.State.FAILED, invalidRelative.state());

        ProtosFutureValue end =
                (ProtosFutureValue)
                        ProtosInvocation.invokeMessage(
                                fixture.file, "seekToEnd", List.of(), fixture.activation);
        fixture.resource.endCompletions.remove().succeeded(6);
        assertEquals(String.valueOf("6"), com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerDecimalText(end.resolvedValue().orElseThrow()));

        ProtosFutureValue size =
                (ProtosFutureValue)
                        ProtosInvocation.invokeMessage(
                                fixture.file, "size", List.of(), fixture.activation);
        fixture.resource.sizeCompletions.remove().succeeded(20);
        assertEquals(String.valueOf("20"), com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerDecimalText(size.resolvedValue().orElseThrow()));

        ProtosFutureValue truncate =
                (ProtosFutureValue)
                        ProtosInvocation.invokeMessage(
                                fixture.file, "truncate", List.of(integer(4)), fixture.activation);
        ProtosFileFlow.ChangeCompletion change = fixture.resource.truncateCompletions.remove();
        assertTrue(change.commitChange());
        change.succeeded();
        assertSame(fixture.file, truncate.resolvedValue().orElseThrow());

        ProtosFutureValue position =
                (ProtosFutureValue)
                        ProtosInvocation.invokeMessage(
                                fixture.file, "position", List.of(), fixture.activation);
        assertEquals(String.valueOf("6"), com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerDecimalText(position.resolvedValue().orElseThrow()));

        ProtosFutureValue sync =
                (ProtosFutureValue)
                        ProtosInvocation.invokeMessage(
                                fixture.file, "sync", List.of(), fixture.activation);
        ProtosFileFlow.SyncCompletion durability = fixture.resource.syncCompletions.remove();
        assertTrue(durability.commitDurability());
        assertTrue(sync.cancelRequest());
        assertEquals(ProtosFutureValue.State.PENDING, sync.state());
        durability.succeeded();
        assertSame(fixture.file, sync.resolvedValue().orElseThrow());
    }

    @Test
    void seekToEndThenWriteIsAnOrdinaryPositionedWriteAtTheObservedEnd() throws Exception {
        Fixture fixture = fixture();

        ProtosFutureValue end =
                (ProtosFutureValue)
                        ProtosInvocation.invokeMessage(
                                fixture.file, "seekToEnd", List.of(), fixture.activation);
        ProtosFutureValue write =
                (ProtosFutureValue)
                        ProtosInvocation.invokeMessage(
                                fixture.file,
                                "write",
                                List.of(bytes(fixture.bytesPrototype, 1, 2)),
                                fixture.activation);
        assertTrue(fixture.resource.writePositions.isEmpty());

        fixture.resource.endCompletions.remove().succeeded(6);
        assertEquals(String.valueOf("6"), com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerDecimalText(end.resolvedValue().orElseThrow()));

        // The write uses the File's own logical position; it does not re-select the end.
        assertEquals(6L, fixture.resource.writePositions.remove());
        ProtosFileFlow.WriteCompletion completion = fixture.resource.writeCompletions.remove();
        assertTrue(completion.commitFirstContribution());
        completion.succeeded();
        assertSame(fixture.file, write.resolvedValue().orElseThrow());
        assertTrue(fixture.resource.endCompletions.isEmpty());

        ProtosFutureValue position =
                (ProtosFutureValue)
                        ProtosInvocation.invokeMessage(
                                fixture.file, "position", List.of(), fixture.activation);
        assertEquals(String.valueOf("8"), com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerDecimalText(position.resolvedValue().orElseThrow()));
    }

    @Test
    void closeCutoverCancelsBackendBeforeReleaseAndKeepsStableLifecycleOutcome()
            throws Exception {
        Fixture fixture = fixture();

        ProtosFutureValue read =
                (ProtosFutureValue)
                        ProtosInvocation.invokeMessage(
                                fixture.file, "read", List.of(integer(2)), fixture.activation);
        ProtosFutureValue close =
                (ProtosFutureValue)
                        ProtosInvocation.invokeMessage(
                                fixture.file, "close", List.of(), fixture.activation);

        assertEquals(ProtosFutureValue.State.FAILED, read.state());
        assertSame(
                fixture.prelude.bindings().readLocalSlot("IOLifecycleError").orElseThrow(),
                read.failedError().orElseThrow().parent().orElseThrow());
        assertEquals(1, fixture.resource.cancellations.get());
        assertEquals(1, fixture.resource.closeStarts.get());
        assertEquals(ProtosFutureValue.State.PENDING, close.state());

        fixture.resource.closeCompletion.succeeded();
        assertSame(fixture.file, close.resolvedValue().orElseThrow());

        ProtosFutureValue repeated =
                (ProtosFutureValue)
                        ProtosInvocation.invokeMessage(
                                fixture.file, "close", List.of(), fixture.activation);
        assertNotSame(close, repeated);
        assertSame(fixture.file, repeated.resolvedValue().orElseThrow());
        assertEquals(1, fixture.resource.closeStarts.get());

        ProtosFutureValue late =
                (ProtosFutureValue)
                        ProtosInvocation.invokeMessage(
                                fixture.file, "read", List.of(integer(1)), fixture.activation);
        assertEquals(ProtosFutureValue.State.FAILED, late.state());
    }

    @Test
    void actorTerminationUsesOrdinaryPreCommitIoCancellation() throws Exception {
        Fixture fixture = fixture();

        ProtosFutureValue read =
                (ProtosFutureValue)
                        ProtosInvocation.invokeMessage(
                                fixture.file, "read", List.of(integer(2)), fixture.activation);
        fixture.activation.executionDomain().actorTerminated();

        assertEquals(ProtosFutureValue.State.CANCELLED, read.state());
        assertEquals(1, fixture.resource.cancellations.get());

        ProtosFutureValue position =
                (ProtosFutureValue)
                        ProtosInvocation.invokeMessage(
                                fixture.file, "position", List.of(), fixture.activation);
        assertEquals(String.valueOf("0"), com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerDecimalText(position.resolvedValue().orElseThrow()));
    }

    private record Fixture(
            ProtosPrelude prelude,
            ProtosActivation activation,
            ProtosObjectValue bytesPrototype,
            TestResource resource,
            ProtosObjectValue file) {}

    private static final class TestResource
            implements ProtosFileFlow.ReadableResource,
                    ProtosFileFlow.WritableResource,
                    ProtosFileFlow.SeekableResource,
                    ProtosFileFlow.SizedResource,
                    ProtosFileFlow.TruncatableResource,
                    ProtosFileFlow.SyncableResource {
        private final ArrayDeque<Long> readPositions = new ArrayDeque<>();
        private final ArrayDeque<ProtosFileFlow.ReadCompletion> readCompletions =
                new ArrayDeque<>();
        private final ArrayDeque<Long> writePositions = new ArrayDeque<>();
        private final ArrayDeque<ProtosFileFlow.WriteCompletion> writeCompletions =
                new ArrayDeque<>();
        private final ArrayDeque<ProtosFileFlow.IntegerCompletion> endCompletions =
                new ArrayDeque<>();
        private final ArrayDeque<ProtosFileFlow.IntegerCompletion> sizeCompletions =
                new ArrayDeque<>();
        private final ArrayDeque<ProtosFileFlow.ChangeCompletion> truncateCompletions =
                new ArrayDeque<>();
        private final ArrayDeque<ProtosFileFlow.SyncCompletion> syncCompletions =
                new ArrayDeque<>();
        private final AtomicInteger cancellations = new AtomicInteger();
        private final AtomicInteger closeStarts = new AtomicInteger();
        private ProtosFileFlow.CloseCompletion closeCompletion;

        @Override
        public ProtosFileFlow.Cancellation readAt(
                long position, int maxBytes, ProtosFileFlow.ReadCompletion completion) {
            readPositions.add(position);
            readCompletions.add(completion);
            return cancellations::incrementAndGet;
        }

        @Override
        public ProtosFileFlow.Cancellation writeAt(
                long position, byte[] bytes, ProtosFileFlow.WriteCompletion completion) {
            writePositions.add(position);
            writeCompletions.add(completion);
            return cancellations::incrementAndGet;
        }

        @Override
        public ProtosFileFlow.Cancellation endPosition(
                ProtosFileFlow.IntegerCompletion completion) {
            endCompletions.add(completion);
            return cancellations::incrementAndGet;
        }

        @Override
        public ProtosFileFlow.Cancellation size(ProtosFileFlow.IntegerCompletion completion) {
            sizeCompletions.add(completion);
            return cancellations::incrementAndGet;
        }

        @Override
        public ProtosFileFlow.Cancellation truncate(
                long size, ProtosFileFlow.ChangeCompletion completion) {
            truncateCompletions.add(completion);
            return cancellations::incrementAndGet;
        }

        @Override
        public ProtosFileFlow.Cancellation sync(ProtosFileFlow.SyncCompletion completion) {
            syncCompletions.add(completion);
            return cancellations::incrementAndGet;
        }

        @Override
        public void close(ProtosFileFlow.CloseCompletion completion) {
            assertTrue(
                    cancellations.get() > 0,
                    "close release must start only after uncommitted backend cancellation hook");
            closeStarts.incrementAndGet();
            closeCompletion = completion;
        }
    }
}
