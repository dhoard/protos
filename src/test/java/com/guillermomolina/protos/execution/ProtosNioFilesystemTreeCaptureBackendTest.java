/*
 * THE LICENSED WORK IS PROVIDED UNDER THE TERMS OF THE ADAPTIVE PUBLIC LICENSE
 * ("LICENSE") AS FIRST COMPLETED BY: Guillermo Adrián Molina. ANY USE, PUBLIC
 * DISPLAY, PUBLIC PERFORMANCE, REPRODUCTION OR DISTRIBUTION OF, OR PREPARATION OF
 * DERIVATIVE WORKS BASED ON, THE LICENSED WORK CONSTITUTES RECIPIENT'S ACCEPTANCE
 * OF THIS LICENSE AND ITS TERMS, WHETHER OR NOT SUCH RECIPIENT READS THE TERMS OF
 * THE LICENSE. "LICENSED WORK" AND "RECIPIENT" ARE DEFINED IN THE LICENSE. A COPY
 * OF THE LICENSE IS LOCATED IN THE TEXT FILE ENTITLED "LICENSE.TXT" ACCOMPANYING
 * THE CONTENTS OF THIS FILE. IF A COPY OF THE LICENSE DOES NOT ACCOMPANY THIS
 * FILE, A COPY OF THE LICENSE MAY ALSO BE OBTAINED AT THE FOLLOWING WEB SITE:
 * https://github.com/guillermomolina/protos
 *
 * Software distributed under the License is distributed on an "AS IS" basis,
 * WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License for
 * the specific language governing rights and limitations under the License.
 */
package com.guillermomolina.protos.execution;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.guillermomolina.protos.runtime.ProtosFileFlow;
import com.guillermomolina.protos.runtime.ProtosFilesystemOpenOptions;
import com.guillermomolina.protos.runtime.ProtosFilesystemTreeObservationFlow;
import com.guillermomolina.protos.runtime.ProtosPathComponents;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class ProtosNioFilesystemTreeCaptureBackendTest {
    @TempDir Path authorityRoot;

    @Test
    void entriesClassifiesDirectChildrenWithoutFollowingLinks() throws Exception {
        Files.writeString(authorityRoot.resolve("regular.txt"), "value", StandardCharsets.UTF_8);
        Files.createDirectory(authorityRoot.resolve("directory"));
        Path outside = Files.createTempFile("protos-i024c-link-target-", ".txt");
        try {
            try {
                Files.createSymbolicLink(authorityRoot.resolve("link"), outside);
            } catch (UnsupportedOperationException | IOException | SecurityException unavailable) {
                assumeTrue(false, "host cannot create symlink fixture");
            }

            try (ProtosNioReadOnlyTreeFilesystemBackend backend =
                    new ProtosNioReadOnlyTreeFilesystemBackend(authorityRoot)) {
                assumeTrue(
                        backend.secureConfinementAvailable(),
                        "host provider has no SecureDirectoryStream");

                Map<String, ProtosFilesystemTreeObservationFlow.EntryKind> observed =
                        byName(entries(backend, relative()));

                assertEquals(
                        ProtosFilesystemTreeObservationFlow.EntryKind.REGULAR,
                        observed.get("regular.txt"));
                assertEquals(
                        ProtosFilesystemTreeObservationFlow.EntryKind.DIRECTORY,
                        observed.get("directory"));
                assertEquals(
                        ProtosFilesystemTreeObservationFlow.EntryKind.LINK,
                        observed.get("link"));
            }
        } finally {
            Files.deleteIfExists(outside);
        }
    }

    @Test
    void finalDirectorySymlinkIsNeverFollowedForObservationOrCapture() throws Exception {
        Path outside = Files.createTempDirectory("protos-i024c-dir-target-");
        try {
            Files.writeString(outside.resolve("secret.txt"), "secret", StandardCharsets.UTF_8);
            try {
                Files.createSymbolicLink(authorityRoot.resolve("alias"), outside);
            } catch (UnsupportedOperationException | IOException | SecurityException unavailable) {
                assumeTrue(false, "host cannot create directory symlink fixture");
            }

            try (ProtosNioReadOnlyTreeFilesystemBackend backend =
                    new ProtosNioReadOnlyTreeFilesystemBackend(authorityRoot)) {
                assumeTrue(
                        backend.secureConfinementAvailable(),
                        "host provider has no SecureDirectoryStream");

                assertNull(entriesOrNull(backend, relative("alias")));

                // Host custody capture records the link as an opaque entry and never
                // traverses it.
                CaptureResult captured = captureRoot(backend);
                try {
                    assertEquals(
                            ProtosFilesystemTreeObservationFlow.EntryKind.LINK,
                            byName(entries(captured.backend(), relative())).get("alias"));
                    assertNull(entriesOrNull(captured.backend(), relative("alias")));
                } finally {
                    captured.release().run();
                }
            }
        } finally {
            Files.deleteIfExists(outside.resolve("secret.txt"));
            Files.deleteIfExists(outside);
        }
    }

    @Test
    void captureSpillsRegularBytesAndRemainsIndependentFromSourceMutation()
            throws Exception {
        Files.writeString(authorityRoot.resolve("top.txt"), "before", StandardCharsets.UTF_8);
        Path nested = Files.createDirectory(authorityRoot.resolve("nested"));
        Files.writeString(nested.resolve("child.txt"), "nested-before", StandardCharsets.UTF_8);

        Path outside = Files.createTempFile("protos-i024c-capture-link-", ".txt");
        try {
            try {
                Files.createSymbolicLink(authorityRoot.resolve("opaque-link"), outside);
            } catch (UnsupportedOperationException | IOException | SecurityException unavailable) {
                assumeTrue(false, "host cannot create symlink fixture");
            }

            try (ProtosNioReadOnlyTreeFilesystemBackend source =
                    new ProtosNioReadOnlyTreeFilesystemBackend(authorityRoot)) {
                assumeTrue(
                        source.secureConfinementAvailable(),
                        "host provider has no SecureDirectoryStream");

                CaptureResult captured = captureRoot(source);
                try {
                    Files.writeString(
                            authorityRoot.resolve("top.txt"),
                            "after",
                            StandardCharsets.UTF_8);
                    Files.writeString(
                            nested.resolve("child.txt"),
                            "nested-after",
                            StandardCharsets.UTF_8);
                    Files.deleteIfExists(authorityRoot.resolve("opaque-link"));

                    assertArrayEquals(
                            "before".getBytes(StandardCharsets.UTF_8),
                            read(captured.backend(), relative("top.txt")));
                    assertArrayEquals(
                            "nested-before".getBytes(StandardCharsets.UTF_8),
                            read(captured.backend(), relative("nested", "child.txt")));

                    Map<String, ProtosFilesystemTreeObservationFlow.EntryKind> rootEntries =
                            byName(entries(captured.backend(), relative()));
                    assertEquals(
                            ProtosFilesystemTreeObservationFlow.EntryKind.LINK,
                            rootEntries.get("opaque-link"));
                    assertFalse(openSucceeded(captured.backend(), relative("opaque-link")));
                    assertEquals(
                            Map.of(
                                    "child.txt",
                                    ProtosFilesystemTreeObservationFlow.EntryKind.REGULAR),
                            byName(entries(captured.backend(), relative("nested"))));
                } finally {
                    captured.release().run();
                }
            }
        } finally {
            Files.deleteIfExists(outside);
        }
    }

    @Test
    void largeRegularPayloadSurvivesSourceRemovalWithoutHeapResultMaterialization()
            throws Exception {
        byte[] payload = new byte[2 * 1024 * 1024 + 17];
        for (int index = 0; index < payload.length; index++) {
            payload[index] = (byte) (index * 31);
        }
        Files.write(authorityRoot.resolve("large.bin"), payload);

        try (ProtosNioReadOnlyTreeFilesystemBackend source =
                new ProtosNioReadOnlyTreeFilesystemBackend(authorityRoot)) {
            assumeTrue(
                    source.secureConfinementAvailable(),
                    "host provider has no SecureDirectoryStream");

            CaptureResult captured = captureRoot(source);
            try {
                Files.delete(authorityRoot.resolve("large.bin"));
                assertArrayEquals(payload, readAll(captured.backend(), relative("large.bin")));
            } finally {
                captured.release().run();
            }
        }
    }

    private static List<ProtosFilesystemTreeObservationFlow.Entry> entries(
            ProtosStandardFilesystemProtocol.Backend backend, ProtosPathComponents path) {
        List<ProtosFilesystemTreeObservationFlow.Entry> result = entriesOrNull(backend, path);
        assertNotNull(result);
        return result;
    }

    private static List<ProtosFilesystemTreeObservationFlow.Entry> entriesOrNull(
            ProtosStandardFilesystemProtocol.Backend backend, ProtosPathComponents path) {
        AtomicReference<List<ProtosFilesystemTreeObservationFlow.Entry>> result =
                new AtomicReference<>();
        AtomicBoolean failed = new AtomicBoolean();
        backend.entries(
                path,
                new ProtosFilesystemTreeObservationFlow.EntriesCompletion() {
                    @Override
                    public void succeeded(
                            List<ProtosFilesystemTreeObservationFlow.Entry> entries) {
                        result.set(entries);
                    }

                    @Override
                    public void failed() {
                        failed.set(true);
                    }
                });
        return failed.get() ? null : result.get();
    }

    private static CaptureResult captureRoot(ProtosNioReadOnlyTreeFilesystemBackend source)
            throws IOException {
        ProtosNioCapturedTreeFilesystemBackend captured = source.captureRootForHostCustody();
        return new CaptureResult(captured, captured::releaseIfUntransferred);
    }

    private static Map<String, ProtosFilesystemTreeObservationFlow.EntryKind> byName(
            List<ProtosFilesystemTreeObservationFlow.Entry> entries) {
        LinkedHashMap<String, ProtosFilesystemTreeObservationFlow.EntryKind> result =
                new LinkedHashMap<>();
        for (ProtosFilesystemTreeObservationFlow.Entry entry : entries) {
            result.put(entry.name(), entry.kind());
        }
        return result;
    }

    private static boolean openSucceeded(
            ProtosStandardFilesystemProtocol.Backend backend, ProtosPathComponents path) {
        AtomicBoolean succeeded = new AtomicBoolean();
        AtomicBoolean failed = new AtomicBoolean();
        backend.open(
                path,
                ProtosFilesystemOpenOptions.defaults(),
                new ProtosStandardFilesystemProtocol.OpenCompletion() {
                    @Override
                    public boolean commitPortableEffect() {
                        return true;
                    }

                    @Override
                    public void succeeded(
                            ProtosFileFlow.Resource resource,
                            ProtosFileFlow.Capabilities capabilities,
                            Runnable releaseIfUntransferred) {
                        succeeded.set(true);
                        releaseIfUntransferred.run();
                    }

                    @Override
                    public void failed() {
                        failed.set(true);
                    }
                });
        assertTrue(succeeded.get() || failed.get());
        return succeeded.get();
    }

    private static byte[] read(
            ProtosStandardFilesystemProtocol.Backend backend, ProtosPathComponents path) {
        AtomicReference<ProtosFileFlow.Resource> resource = new AtomicReference<>();
        AtomicReference<Runnable> release = new AtomicReference<>();
        AtomicBoolean failed = new AtomicBoolean();
        backend.open(
                path,
                ProtosFilesystemOpenOptions.defaults(),
                new ProtosStandardFilesystemProtocol.OpenCompletion() {
                    @Override
                    public boolean commitPortableEffect() {
                        return true;
                    }

                    @Override
                    public void succeeded(
                            ProtosFileFlow.Resource selected,
                            ProtosFileFlow.Capabilities capabilities,
                            Runnable releaseIfUntransferred) {
                        assertTrue(capabilities.readable());
                        assertFalse(capabilities.writable());
                        resource.set(selected);
                        release.set(releaseIfUntransferred);
                    }

                    @Override
                    public void failed() {
                        failed.set(true);
                    }
                });
        assertFalse(failed.get());
        assertNotNull(resource.get());

        AtomicReference<byte[]> bytes = new AtomicReference<>();
        ((ProtosFileFlow.ReadableResource) resource.get())
                .readAt(
                        0L,
                        64 * 1024,
                        new ProtosFileFlow.ReadCompletion() {
                            @Override
                            public void data(byte[] data) {
                                bytes.set(data);
                            }

                            @Override
                            public void eof() {
                                bytes.set(new byte[0]);
                            }

                            @Override
                            public void failed() {
                                failed.set(true);
                            }
                        });
        assertFalse(failed.get());
        byte[] result = bytes.get();
        release.get().run();
        return result;
    }

    private static byte[] readAll(
            ProtosStandardFilesystemProtocol.Backend backend, ProtosPathComponents path) {
        AtomicReference<ProtosFileFlow.Resource> resource = new AtomicReference<>();
        AtomicReference<Runnable> release = new AtomicReference<>();
        AtomicBoolean failed = new AtomicBoolean();
        backend.open(
                path,
                ProtosFilesystemOpenOptions.defaults(),
                new ProtosStandardFilesystemProtocol.OpenCompletion() {
                    @Override
                    public boolean commitPortableEffect() {
                        return true;
                    }

                    @Override
                    public void succeeded(
                            ProtosFileFlow.Resource selected,
                            ProtosFileFlow.Capabilities capabilities,
                            Runnable releaseIfUntransferred) {
                        resource.set(selected);
                        release.set(releaseIfUntransferred);
                    }

                    @Override
                    public void failed() {
                        failed.set(true);
                    }
                });
        assertFalse(failed.get());

        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        long position = 0L;
        while (true) {
            AtomicReference<byte[]> chunk = new AtomicReference<>();
            AtomicBoolean eof = new AtomicBoolean();
            ((ProtosFileFlow.ReadableResource) resource.get())
                    .readAt(
                            position,
                            64 * 1024,
                            new ProtosFileFlow.ReadCompletion() {
                                @Override
                                public void data(byte[] data) {
                                    chunk.set(data);
                                }

                                @Override
                                public void eof() {
                                    eof.set(true);
                                }

                                @Override
                                public void failed() {
                                    failed.set(true);
                                }
                            });
            assertFalse(failed.get());
            if (eof.get()) {
                break;
            }
            assertNotNull(chunk.get());
            bytes.writeBytes(chunk.get());
            position += chunk.get().length;
        }
        release.get().run();
        return bytes.toByteArray();
    }

    private static ProtosPathComponents relative(String... names) {
        return new ProtosPathComponents(java.util.List.of(names));
    }

    private record CaptureResult(
            ProtosStandardFilesystemProtocol.CapturedBackend backend, Runnable release) {}
}
