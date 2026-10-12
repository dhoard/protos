/* APL-1.0 licensed work; see LICENSE.TXT. */
package com.guillermomolina.protos.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.guillermomolina.protos.runtime.ProtosActivation;
import com.guillermomolina.protos.runtime.ProtosBooleanValue;
import com.guillermomolina.protos.runtime.ProtosBytesValue;
import com.guillermomolina.protos.runtime.ProtosFilesystemOpenFlow;
import com.guillermomolina.protos.runtime.ProtosFilesystemOpenOptions;
import com.guillermomolina.protos.runtime.ProtosFilesystemTreeObservationFlow;
import com.guillermomolina.protos.runtime.ProtosFilesystemValue;
import com.guillermomolina.protos.runtime.ProtosFutureValue;
import com.guillermomolina.protos.runtime.ProtosIntegerValue;
import com.guillermomolina.protos.runtime.ProtosObjectValue;
import com.guillermomolina.protos.runtime.ProtosPathComponents;
import com.guillermomolina.protos.runtime.ProtosPathValue;
import com.guillermomolina.protos.runtime.ProtosPrelude;
import java.io.IOException;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class ProtosCapturedFilesystemCustodyTest {
    private static final Path CORE = Path.of("protos", "lib", "core");

    @TempDir Path selectedRoot;

    @Test
    void selectedRootIsCapturedOnceAndRematerializedAcrossActorDomains() throws Exception {
        Files.writeString(
                selectedRoot.resolve("protos.toml"),
                "manifest-version = 1\n[package]\nid = \"pkg\"\nversion = \"1.0.0\"\n",
                StandardCharsets.UTF_8);
        Path payload = selectedRoot.resolve("Payload.protos");
        Files.writeString(payload, "before", StandardCharsets.UTF_8);

        try (ProtosNioReadOnlyTreeFilesystemBackend probe =
                new ProtosNioReadOnlyTreeFilesystemBackend(selectedRoot)) {
            assumeTrue(
                    probe.secureConfinementAvailable(),
                    "host provider has no SecureDirectoryStream");
        }

        ProtosPrelude toolPrelude = core();
        ProtosActivation toolActivation = toolPrelude.newModuleActivation();
        ProtosPrelude applicationPrelude = core();
        ProtosActivation applicationActivation = applicationPrelude.newModuleActivation();
        assertNotSame(toolActivation.executionDomain(), applicationActivation.executionDomain());

        ProtosCapturedFilesystemCustody custody =
                ProtosCapturedFilesystemCustody.captureSelectedRoot(selectedRoot);
        try {
            ProtosFilesystemValue toolView = custody.materialize(toolActivation);

            Files.delete(payload);

            ProtosFilesystemValue applicationView = custody.materialize(applicationActivation);
            assertNotSame(toolView, applicationView);
            // D171: host-provisioned captured views are ordinary read-only Filesystems that
            // cannot mint further captures.
            assertEquals(
                    Set.of("open", "replace", "remove", "entries"),
                    toolView.localSlotsSnapshot().keySet());
            assertFalse(applicationView.hasLocalSlot("captureTree"));
            assertEquals("before", readText(toolView, toolPrelude, toolActivation, "Payload.protos"));
            assertEquals(
                    "before",
                    readText(
                            applicationView,
                            applicationPrelude,
                            applicationActivation,
                            "Payload.protos"));
        } finally {
            custody.close();
        }

        assertThrows(
                IllegalStateException.class,
                () -> custody.materialize(applicationActivation));
    }

    @Test
    void hostCapturedFilesystemIsReadOnlySourceIndependentAndVerifyUseStableThroughProtos()
            throws Exception {
        Files.writeString(selectedRoot.resolve("stable.txt"), "before", StandardCharsets.UTF_8);
        Path nested = Files.createDirectory(selectedRoot.resolve("nested"));
        Files.writeString(nested.resolve("child.txt"), "nested-before", StandardCharsets.UTF_8);
        Path outside = Files.createTempFile("protos-i064-capture-link-", ".txt");
        try {
            try {
                Files.createSymbolicLink(selectedRoot.resolve("opaque-link"), outside);
            } catch (UnsupportedOperationException | IOException | SecurityException unavailable) {
                assumeTrue(false, "host cannot create symlink fixture");
            }
            try (ProtosNioReadOnlyTreeFilesystemBackend probe =
                    new ProtosNioReadOnlyTreeFilesystemBackend(selectedRoot)) {
                assumeTrue(
                        probe.secureConfinementAvailable(),
                        "host provider has no SecureDirectoryStream");
            }

            try (ProtosCapturedFilesystemCustody custody =
                    ProtosCapturedFilesystemCustody.captureSelectedRoot(selectedRoot)) {
                Files.writeString(
                        selectedRoot.resolve("stable.txt"), "after", StandardCharsets.UTF_8);
                Files.writeString(
                        nested.resolve("child.txt"), "nested-after", StandardCharsets.UTF_8);
                Files.delete(selectedRoot.resolve("opaque-link"));
                Files.writeString(
                        selectedRoot.resolve("after-only.txt"),
                        "after-only",
                        StandardCharsets.UTF_8);

                ProtosActivation activation = core().newModuleActivation();
                activation.context().createLocalSlot("captured", custody.materialize(activation));
                assertSame(
                        ProtosBooleanValue.TRUE,
                        ProtosTestExecutionSupport.executeFileValueWithFreshModuleActivation(
                                Path.of("protos", "tests", "filesystem", "tree-capture-verify.protos"),
                                activation));
            }
        } finally {
            Files.deleteIfExists(outside);
        }
    }

    @Test
    void rematerializedViewsUseSameCapturedBackendAndCloseReleasesExactlyOnce()
            throws Exception {
        RecordingCapturedBackend backend = new RecordingCapturedBackend();
        AtomicInteger releases = new AtomicInteger();
        ProtosCapturedFilesystemCustody custody =
                new ProtosCapturedFilesystemCustody(backend, releases::incrementAndGet);

        ProtosPrelude firstPrelude = core();
        ProtosActivation firstActivation = firstPrelude.newModuleActivation();
        ProtosPrelude secondPrelude = core();
        ProtosActivation secondActivation = secondPrelude.newModuleActivation();

        ProtosFilesystemValue first = custody.materialize(firstActivation);
        ProtosFilesystemValue second = custody.materialize(secondActivation);
        assertNotSame(first, second);

        assertResolved(
                ProtosInvocation.invokeMessage(
                        first,
                        "entries",
                        List.of(rootPath(firstPrelude)),
                        firstActivation));
        assertResolved(
                ProtosInvocation.invokeMessage(
                        second,
                        "entries",
                        List.of(rootPath(secondPrelude)),
                        secondActivation));
        assertEquals(2, backend.entriesCalls.get());

        custody.close();
        custody.close();
        assertEquals(1, releases.get());
        assertThrows(IllegalStateException.class, () -> custody.materialize(firstActivation));
    }

    private static ProtosPrelude core() throws Exception {
        return new ProtosCoreBootstrap().bootstrap(CORE);
    }

    private static String readText(
            ProtosFilesystemValue filesystem,
            ProtosPrelude prelude,
            ProtosActivation activation,
            String name) {
        ProtosFutureValue opened =
                assertResolved(
                        ProtosInvocation.invokeMessage(
                                filesystem,
                                "open",
                                List.of(path(prelude, name)),
                                activation));
        ProtosObjectValue file =
                assertInstanceOf(ProtosObjectValue.class, opened.resolvedValue().orElseThrow());

        ProtosFutureValue read =
                assertResolved(
                        ProtosInvocation.invokeMessage(
                                file,
                                "read",
                                List.of(new ProtosIntegerValue(4096)),
                                activation));
        ProtosBytesValue bytes =
                assertInstanceOf(ProtosBytesValue.class, read.resolvedValue().orElseThrow());
        byte[] raw = new byte[bytes.indexedSize()];
        for (int index = 0; index < raw.length; index++) {
            long octet = org.junit.jupiter.api.Assertions.assertInstanceOf(
                    ProtosIntegerValue.class, bytes.indexedAt(index)).longValue();
            raw[index] = (byte) octet;
        }

        assertResolved(ProtosInvocation.invokeMessage(file, "close", List.of(), activation));
        return new String(raw, StandardCharsets.UTF_8);
    }

    private static ProtosFutureValue assertResolved(Object value) {
        ProtosFutureValue future = assertInstanceOf(ProtosFutureValue.class, value);
        assertEquals(ProtosFutureValue.State.RESOLVED, future.state());
        return future;
    }

    private static ProtosPathValue rootPath(ProtosPrelude prelude) {
        return new ProtosPathValue(prelude.pathPrototype(), List.of());
    }

    private static ProtosPathValue path(ProtosPrelude prelude, String name) {
        return new ProtosPathValue(
                prelude.pathPrototype(), List.of(name));
    }

    private static final class RecordingCapturedBackend
            implements ProtosStandardFilesystemProtocol.CapturedBackend {
        private final AtomicInteger entriesCalls = new AtomicInteger();

        @Override
        public ProtosFilesystemOpenFlow.Cancellation open(
                ProtosPathComponents path,
                ProtosFilesystemOpenOptions options,
                ProtosStandardFilesystemProtocol.OpenCompletion completion) {
            completion.failed();
            return () -> {};
        }

        @Override
        public ProtosFilesystemTreeObservationFlow.Cancellation entries(
                ProtosPathComponents path,
                ProtosFilesystemTreeObservationFlow.EntriesCompletion completion) {
            entriesCalls.incrementAndGet();
            completion.succeeded(List.of());
            return () -> {};
        }
    }
}
