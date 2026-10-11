/* APL-1.0 licensed work; see LICENSE.TXT. */
package com.guillermomolina.protos.execution;

import com.guillermomolina.protos.runtime.ProtosTestIntegers;
import static org.junit.jupiter.api.Assertions.*;

import com.guillermomolina.protos.runtime.*;
import java.math.BigInteger;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * I016-D3 integrated conformance for the host-provisioned Filesystem -> open -> File path.
 *
 * <p>Backend policy itself remains host-specific. These tests make the portable boundary
 * deterministic: the protocol never bypasses backend authority, never retargets an already selected
 * resource, never lets cancellation rewrite committed effects, and never infers optional File
 * capabilities merely from Java implementation type.
 */
class ProtosFilesystemIntegratedConformanceTest {
    private static ProtosPrelude core() throws Exception {
        return new ProtosCoreBootstrap().bootstrap(Path.of("protos", "lib", "core"));
    }

    private static Fixture fixture() throws Exception {
        ProtosPrelude prelude = core();
        ProtosActivation activation = prelude.newModuleActivation();
        ProtosObjectValue bytesPrototype =
                new ProtosObjectValue(ProtosObjectValue.rootObject());
        ProtosStandardBytesProtocol.install(bytesPrototype);
        RecordingBackend backend = new RecordingBackend();
        ProtosObjectValue filesystem =
                ProtosStandardFilesystemProtocol.createCapability(
                        bytesPrototype, activation, backend);
        return new Fixture(prelude, activation, backend, filesystem);
    }

    @Test
    void preCommitCancellationWinsAndLateSelectedResourceCustodyIsReleased()
            throws Exception {
        Fixture x = fixture();
        ProtosFutureValue future = open(x, path(x.prelude, "late.bin"));
        Invocation invocation = x.backend.invocations.remove();

        assertTrue(future.cancelRequest());
        assertEquals(ProtosFutureValue.State.CANCELLED, future.state());
        assertEquals(1, invocation.cancellations.get());
        assertFalse(invocation.completion.commitPortableEffect());

        AtomicInteger releases = new AtomicInteger();
        invocation.completion.succeeded(
                new ByteReadResource(7),
                readOnlyCapabilities(),
                releases::incrementAndGet);

        assertEquals(1, releases.get());
        assertEquals(ProtosFutureValue.State.CANCELLED, future.state());
    }

    @Test
    void committedCreateEffectDefeatsCancellationAndLaterFailureRemainsIoError()
            throws Exception {
        Fixture x = fixture();
        ProtosObjectValue options = ordinaryOptions();
        options.createLocalSlot("write", ProtosBooleanValue.TRUE);
        options.createLocalSlot("create", ProtosBooleanValue.TRUE);

        ProtosFutureValue future = open(x, path(x.prelude, "created.bin"), options);
        Invocation invocation = x.backend.invocations.remove();

        assertTrue(invocation.completion.commitPortableEffect());
        assertTrue(future.cancelRequest());
        assertEquals(1, invocation.cancellations.get());
        assertEquals(ProtosFutureValue.State.PENDING, future.state());

        invocation.completion.failed();
        dispatch(x);

        assertEquals(ProtosFutureValue.State.FAILED, future.state());
        assertSame(
                x.prelude.bindings().readLocalSlot("IOError").orElseThrow(),
                future.failedError().orElseThrow().parent().orElseThrow());
    }

    @Test
    void selectedResourceIsStableEvenWhenBackendNamespaceLaterRetargets()
            throws Exception {
        ProtosPrelude prelude = core();
        ProtosActivation activation = prelude.newModuleActivation();
        ProtosObjectValue bytesPrototype =
                new ProtosObjectValue(ProtosObjectValue.rootObject());
        ProtosStandardBytesProtocol.install(bytesPrototype);

        ByteReadResource first = new ByteReadResource(7);
        ByteReadResource replacement = new ByteReadResource(9);
        SwitchingBackend backend = new SwitchingBackend(first);
        ProtosObjectValue filesystem =
                ProtosStandardFilesystemProtocol.createCapability(
                        bytesPrototype, activation, backend);

        ProtosFutureValue opened =
                (ProtosFutureValue)
                        ProtosInvocation.invokeMessage(
                                filesystem,
                                "open",
                                List.of(path(prelude, "same-name")),
                                activation);
        ProtosObjectValue file =
                (ProtosObjectValue) opened.resolvedValue().orElseThrow();

        backend.current = replacement;

        ProtosFutureValue read =
                (ProtosFutureValue)
                        ProtosInvocation.invokeMessage(
                                file,
                                "read",
                                List.of(new ProtosIntegerValue(1L)),
                                activation);
        ProtosBytesValue bytes =
                (ProtosBytesValue) read.resolvedValue().orElseThrow();

        assertEquals(
                BigInteger.valueOf(7),
                ProtosTestIntegers.exact(bytes.indexedAt(0)));
        assertEquals(1, first.reads.get());
        assertEquals(0, replacement.reads.get());
    }

    @Test
    void independentIntegratedOpensReachBackendAndMayCompleteOutOfOrder()
            throws Exception {
        Fixture x = fixture();

        ProtosFutureValue first = open(x, path(x.prelude, "same"));
        ProtosFutureValue second = open(x, path(x.prelude, "same"));

        assertEquals(2, x.backend.invocations.size());
        Invocation firstInvocation = x.backend.invocations.remove();
        Invocation secondInvocation = x.backend.invocations.remove();

        secondInvocation.completion.succeeded(
                new ByteReadResource(2), readOnlyCapabilities(), () -> {});
        dispatch(x);
        assertEquals(ProtosFutureValue.State.PENDING, first.state());
        assertEquals(ProtosFutureValue.State.RESOLVED, second.state());

        firstInvocation.completion.succeeded(
                new ByteReadResource(1), readOnlyCapabilities(), () -> {});
        dispatch(x);

        assertEquals(ProtosFutureValue.State.RESOLVED, first.state());
        assertNotSame(
                first.resolvedValue().orElseThrow(),
                second.resolvedValue().orElseThrow());
    }

    @Test
    void backendAuthorityRejectionIsFinalAndProtocolDoesNotFallbackOrMaterialize()
            throws Exception {
        ProtosPrelude prelude = core();
        ProtosActivation activation = prelude.newModuleActivation();
        ProtosObjectValue bytesPrototype =
                new ProtosObjectValue(ProtosObjectValue.rootObject());
        ProtosStandardBytesProtocol.install(bytesPrototype);
        ConfiningBackend backend = new ConfiningBackend();
        ProtosObjectValue filesystem =
                ProtosStandardFilesystemProtocol.createCapability(
                        bytesPrototype, activation, backend);

        ProtosPathValue escaping =
                new ProtosPathValue(
                        prelude.pathPrototype(), List.of("inside", "outside"));

        ProtosFutureValue future =
                (ProtosFutureValue)
                        ProtosInvocation.invokeMessage(
                                filesystem, "open", List.of(escaping), activation);

        assertEquals(1, backend.attempts.get());
        assertEquals(0, backend.materializations.get());
        assertEquals(ProtosFutureValue.State.FAILED, future.state());
        assertSame(
                prelude.bindings().readLocalSlot("IOError").orElseThrow(),
                future.failedError().orElseThrow().parent().orElseThrow());
    }

    @Test
    void optionalCapabilityShapeComesFromDescriptorNotBackendJavaType()
            throws Exception {
        Fixture x = fixture();
        ProtosFutureValue future = open(x, path(x.prelude, "shape.bin"));
        Invocation invocation = x.backend.invocations.remove();

        ByteReadSeekResource richerResource = new ByteReadSeekResource(4);
        invocation.completion.succeeded(
                richerResource,
                readOnlyCapabilities(),
                () -> {});
        dispatch(x);

        ProtosObjectValue file =
                (ProtosObjectValue) future.resolvedValue().orElseThrow();
        assertTrue(file.hasLocalSlot("read"));
        assertTrue(file.hasLocalSlot("close"));
        assertFalse(file.hasLocalSlot("position"));
        assertFalse(file.hasLocalSlot("seek"));
        assertFalse(file.hasLocalSlot("seekBy"));
        assertFalse(file.hasLocalSlot("seekToEnd"));
        assertFalse(file.hasLocalSlot("size"));
        assertFalse(file.hasLocalSlot("truncate"));
        assertFalse(file.hasLocalSlot("sync"));
        assertFalse(file.hasLocalSlot("flush"));
    }

    /** I094: a completion outside the original backend call is delivered by the owner. */
    private static void dispatch(Fixture x) {
        while (x.activation.executionDomain().dispatchOne()) {
            // Owner-domain delivery.
        }
    }

    private static ProtosFutureValue open(Fixture x, ProtosPathValue path) {
        return (ProtosFutureValue)
                ProtosInvocation.invokeMessage(
                        x.filesystem, "open", List.of(path), x.activation);
    }

    private static ProtosFutureValue open(
            Fixture x, ProtosPathValue path, ProtosObjectValue options) {
        return (ProtosFutureValue)
                ProtosInvocation.invokeMessage(
                        x.filesystem, "open", List.of(path, options), x.activation);
    }

    private static ProtosFileFlow.Capabilities readOnlyCapabilities() {
        return new ProtosFileFlow.Capabilities(
                true, false, false, false, false, false);
    }

    private static ProtosObjectValue ordinaryOptions() {
        return new ProtosObjectValue(ProtosObjectValue.rootObject());
    }

    private static ProtosPathValue path(ProtosPrelude prelude, String name) {
        return new ProtosPathValue(
                prelude.pathPrototype(), List.of(name));
    }

    private record Fixture(
            ProtosPrelude prelude,
            ProtosActivation activation,
            RecordingBackend backend,
            ProtosObjectValue filesystem) {}

    private static final class RecordingBackend
            implements ProtosStandardFilesystemProtocol.Backend {
        private final ArrayDeque<Invocation> invocations = new ArrayDeque<>();

        @Override
        public ProtosFilesystemOpenFlow.Cancellation open(
                ProtosPathComponents path,
                ProtosFilesystemOpenOptions options,
                ProtosStandardFilesystemProtocol.OpenCompletion completion) {
            Invocation invocation = new Invocation(path, options, completion);
            invocations.add(invocation);
            return invocation.cancellations::incrementAndGet;
        }
    }

    private static final class Invocation {
        private final ProtosPathComponents path;
        private final ProtosFilesystemOpenOptions options;
        private final ProtosStandardFilesystemProtocol.OpenCompletion completion;
        private final AtomicInteger cancellations = new AtomicInteger();

        private Invocation(
                ProtosPathComponents path,
                ProtosFilesystemOpenOptions options,
                ProtosStandardFilesystemProtocol.OpenCompletion completion) {
            this.path = path;
            this.options = options;
            this.completion = completion;
        }
    }

    private static class ByteReadResource implements ProtosFileFlow.ReadableResource {
        private final int octet;
        private final AtomicInteger reads = new AtomicInteger();

        private ByteReadResource(int octet) {
            this.octet = octet;
        }

        @Override
        public ProtosFileFlow.Cancellation readAt(
                long position, int maxBytes, ProtosFileFlow.ReadCompletion completion) {
            reads.incrementAndGet();
            completion.data(new byte[] {(byte) octet});
            return () -> {};
        }

        @Override
        public void close(ProtosFileFlow.CloseCompletion completion) {
            completion.succeeded();
        }
    }

    private static final class ByteReadSeekResource extends ByteReadResource
            implements ProtosFileFlow.SeekableResource {
        private ByteReadSeekResource(int octet) {
            super(octet);
        }

        @Override
        public ProtosFileFlow.Cancellation endPosition(
                ProtosFileFlow.IntegerCompletion completion) {
            completion.succeeded(1);
            return () -> {};
        }
    }

    private static final class SwitchingBackend
            implements ProtosStandardFilesystemProtocol.Backend {
        private ByteReadResource current;

        private SwitchingBackend(ByteReadResource current) {
            this.current = current;
        }

        @Override
        public ProtosFilesystemOpenFlow.Cancellation open(
                ProtosPathComponents path,
                ProtosFilesystemOpenOptions options,
                ProtosStandardFilesystemProtocol.OpenCompletion completion) {
            ByteReadResource selected = current;
            completion.succeeded(selected, readOnlyCapabilities(), () -> {});
            return () -> {};
        }
    }

    private static final class ConfiningBackend
            implements ProtosStandardFilesystemProtocol.Backend {
        private final AtomicInteger attempts = new AtomicInteger();
        private final AtomicInteger materializations = new AtomicInteger();

        @Override
        public ProtosFilesystemOpenFlow.Cancellation open(
                ProtosPathComponents path,
                ProtosFilesystemOpenOptions options,
                ProtosStandardFilesystemProtocol.OpenCompletion completion) {
            attempts.incrementAndGet();
            boolean escapes = path.components().contains("outside");
            if (escapes) {
                completion.failed();
            } else {
                materializations.incrementAndGet();
                completion.succeeded(
                        new ByteReadResource(1),
                        readOnlyCapabilities(),
                        () -> {});
            }
            return () -> {};
        }
    }
}
