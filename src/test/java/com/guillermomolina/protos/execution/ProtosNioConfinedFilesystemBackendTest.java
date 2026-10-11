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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.guillermomolina.protos.runtime.ProtosFilesystemNamespaceMutationFlow;
import com.guillermomolina.protos.runtime.ProtosPathComponents;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class ProtosNioConfinedFilesystemBackendTest {
    @TempDir Path authorityRoot;

    @Test
    void replaceMovesAllowedEntryToAbsentTarget() throws Exception {
        Path source = authorityRoot.resolve("source");
        Path target = authorityRoot.resolve("target");
        Files.writeString(source, "new", StandardCharsets.UTF_8);

        try (ProtosNioConfinedFilesystemBackend backend = backend(Set.of("source", "target"))) {
            assumeSecure(backend);
            Completion completion = new Completion();

            backend.replace(path("source"), path("target"), completion);

            assertTrue(completion.succeededOutcome());
            assertTrue(Files.notExists(source, LinkOption.NOFOLLOW_LINKS));
            assertEquals("new", Files.readString(target, StandardCharsets.UTF_8));
        }
    }

    @Test
    void replaceAtomicallyReplacesExistingTargetWhenProviderSupportsIt() throws Exception {
        Path source = authorityRoot.resolve("source");
        Path target = authorityRoot.resolve("target");
        Files.writeString(source, "new", StandardCharsets.UTF_8);
        Files.writeString(target, "old", StandardCharsets.UTF_8);

        try (ProtosNioConfinedFilesystemBackend backend = backend(Set.of("source", "target"))) {
            assumeSecure(backend);
            Completion completion = new Completion();

            backend.replace(path("source"), path("target"), completion);

            assumeTrue(
                    completion.succeededOutcome(),
                    "host SecureDirectoryStream provider does not atomically replace an existing target: "
                            + completion.effectFailure);
            assertTrue(Files.notExists(source, LinkOption.NOFOLLOW_LINKS));
            assertEquals("new", Files.readString(target, StandardCharsets.UTF_8));
        }
    }

    @Test
    void distinctHardLinksToSameResourceRemainANamespaceNoOp() throws Exception {
        Path source = authorityRoot.resolve("source");
        Path target = authorityRoot.resolve("target");
        Files.writeString(source, "same", StandardCharsets.UTF_8);
        try {
            Files.createLink(target, source);
        } catch (UnsupportedOperationException | IOException | SecurityException unavailable) {
            assumeTrue(false, "host cannot create hard-link alias fixture");
        }

        try (ProtosNioConfinedFilesystemBackend backend = backend(Set.of("source", "target"))) {
            assumeSecure(backend);
            Completion completion = new Completion();

            backend.replace(path("source"), path("target"), completion);

            assertTrue(completion.succeededOutcome(), () -> String.valueOf(completion.effectFailure));
            assertTrue(Files.exists(source, LinkOption.NOFOLLOW_LINKS));
            assertTrue(Files.exists(target, LinkOption.NOFOLLOW_LINKS));
            assertTrue(Files.isSameFile(source, target));
        }
    }

    @Test
    void sourceSymlinkIsMovedAsTheEntryRatherThanFollowingItsReferent() throws Exception {
        Path outside = Files.createTempFile("protos-i021b-outside-", ".txt");
        try {
            Files.writeString(outside, "outside", StandardCharsets.UTF_8);
            Path source = authorityRoot.resolve("source");
            Path target = authorityRoot.resolve("target");
            try {
                Files.createSymbolicLink(source, outside.toAbsolutePath());
            } catch (UnsupportedOperationException | IOException | SecurityException unavailable) {
                assumeTrue(false, "host cannot create symlink fixture");
            }

            try (ProtosNioConfinedFilesystemBackend backend =
                    backend(Set.of("source", "target"))) {
                assumeSecure(backend);
                Completion completion = new Completion();

                backend.replace(path("source"), path("target"), completion);

                assertTrue(completion.succeededOutcome());
                assertTrue(Files.notExists(source, LinkOption.NOFOLLOW_LINKS));
                assertTrue(Files.isSymbolicLink(target));
                assertEquals(outside.toAbsolutePath(), Files.readSymbolicLink(target));
                assertEquals("outside", Files.readString(outside, StandardCharsets.UTF_8));
            }
        } finally {
            Files.deleteIfExists(outside);
        }
    }

    @Test
    void existingTargetSymlinkIsReplacedWithoutTouchingItsReferent() throws Exception {
        Path outside = Files.createTempFile("protos-i021b-target-", ".txt");
        try {
            Files.writeString(outside, "outside", StandardCharsets.UTF_8);
            Path source = authorityRoot.resolve("source");
            Path target = authorityRoot.resolve("target");
            Files.writeString(source, "new", StandardCharsets.UTF_8);
            try {
                Files.createSymbolicLink(target, outside.toAbsolutePath());
            } catch (UnsupportedOperationException | IOException | SecurityException unavailable) {
                assumeTrue(false, "host cannot create symlink fixture");
            }

            try (ProtosNioConfinedFilesystemBackend backend =
                    backend(Set.of("source", "target"))) {
                assumeSecure(backend);
                Completion completion = new Completion();

                backend.replace(path("source"), path("target"), completion);

                assumeTrue(
                        completion.succeededOutcome(),
                        "host SecureDirectoryStream provider does not atomically replace an existing target: "
                                + completion.effectFailure);
                assertFalse(Files.isSymbolicLink(target));
                assertEquals("new", Files.readString(target, StandardCharsets.UTF_8));
                assertEquals("outside", Files.readString(outside, StandardCharsets.UTF_8));
            }
        } finally {
            Files.deleteIfExists(outside);
        }
    }

    @Test
    void removeDeletesSymlinkEntryWithoutFollowingItsReferent() throws Exception {
        Path outside = Files.createTempFile("protos-i021b-remove-", ".txt");
        try {
            Files.writeString(outside, "outside", StandardCharsets.UTF_8);
            Path link = authorityRoot.resolve("link");
            try {
                Files.createSymbolicLink(link, outside.toAbsolutePath());
            } catch (UnsupportedOperationException | IOException | SecurityException unavailable) {
                assumeTrue(false, "host cannot create symlink fixture");
            }

            try (ProtosNioConfinedFilesystemBackend backend = backend(Set.of("link"))) {
                assumeSecure(backend);
                Completion completion = new Completion();

                backend.remove(path("link"), completion);

                assertTrue(completion.succeededOutcome());
                assertTrue(Files.notExists(link, LinkOption.NOFOLLOW_LINKS));
                assertEquals("outside", Files.readString(outside, StandardCharsets.UTF_8));
            }
        } finally {
            Files.deleteIfExists(outside);
        }
    }

    @Test
    void removeNeverRecursesToMakeADirectoryEntryRemovable() throws Exception {
        Path directory = Files.createDirectory(authorityRoot.resolve("directory"));
        Path child = Files.writeString(directory.resolve("child"), "x", StandardCharsets.UTF_8);

        try (ProtosNioConfinedFilesystemBackend backend = backend(Set.of("directory"))) {
            assumeSecure(backend);
            Completion completion = new Completion();

            backend.remove(path("directory"), completion);

            assertTrue(completion.failed);
            assertFalse(completion.succeededOutcome());
            assertTrue(Files.exists(directory));
            assertEquals("x", Files.readString(child, StandardCharsets.UTF_8));
        }
    }

    @Test
    void mutableAllowlistRejectsUncoveredSourceOrTargetBeforeAnyEffect() throws Exception {
        Path source = Files.writeString(authorityRoot.resolve("source"), "new", StandardCharsets.UTF_8);
        Path forbidden = Files.writeString(authorityRoot.resolve("forbidden"), "old", StandardCharsets.UTF_8);

        try (ProtosNioConfinedFilesystemBackend backend = backend(Set.of("source"))) {
            assumeSecure(backend);
            Completion completion = new Completion();

            backend.replace(path("source"), path("forbidden"), completion);

            assertTrue(completion.failed);
            assertFalse(completion.succeededOutcome());
            assertEquals("new", Files.readString(source, StandardCharsets.UTF_8));
            assertEquals("old", Files.readString(forbidden, StandardCharsets.UTF_8));
        }
    }

    @Test
    void inertDescriptorIsStillConfinedToOneAllowedDirectChild() throws Exception {
        Path directory = Files.createDirectory(authorityRoot.resolve("source"));
        Path child = Files.writeString(directory.resolve("child"), "x", StandardCharsets.UTF_8);

        try (ProtosNioConfinedFilesystemBackend backend = backend(Set.of("source"))) {
            assumeSecure(backend);
            Completion nested = new Completion();
            backend.remove(new ProtosPathComponents(List.of("source", "child")), nested);
            Completion separator = new Completion();
            backend.remove(path("source/child"), separator);

            assertTrue(nested.failed);
            assertTrue(separator.failed);
            assertEquals("x", Files.readString(child, StandardCharsets.UTF_8));
        }
    }

    @Test
    void unsupportedSecureDirectoryProviderFailsClosed() throws Exception {
        Path entry = Files.writeString(authorityRoot.resolve("entry"), "x", StandardCharsets.UTF_8);
        try (ProtosNioConfinedFilesystemBackend backend = backend(Set.of("entry"))) {
            if (backend.secureNamespaceConfinementAvailable()) {
                return;
            }
            Completion completion = new Completion();

            backend.remove(path("entry"), completion);

            assertTrue(completion.failed);
            assertFalse(completion.succeededOutcome());
            assertEquals("x", Files.readString(entry, StandardCharsets.UTF_8));
        }
    }

    private ProtosNioConfinedFilesystemBackend backend(Set<String> mutableNames) throws IOException {
        return new ProtosNioConfinedFilesystemBackend(authorityRoot, Set.of(), mutableNames);
    }

    private static void assumeSecure(ProtosNioConfinedFilesystemBackend backend) {
        assumeTrue(
                backend.secureNamespaceConfinementAvailable(),
                "host provider has no SecureDirectoryStream");
    }

    private static ProtosPathComponents path(String name) {
        return new ProtosPathComponents(List.of(name));
    }

    private static final class Completion
            implements ProtosFilesystemNamespaceMutationFlow.MutationCompletion {
        boolean effectSucceeded;
        boolean noOpSucceeded;
        boolean failed;
        Exception effectFailure;

        @Override
        public boolean commitPortableEffect(
                ProtosFilesystemNamespaceMutationFlow.NamespaceEffect effect) {
            try {
                effect.run();
                effectSucceeded = true;
                return true;
            } catch (Exception failure) {
                effectFailure = failure;
                failed = true;
                return false;
            }
        }

        @Override
        public void succeeded() {
            noOpSucceeded = true;
        }

        @Override
        public void failed() {
            failed = true;
        }

        boolean succeededOutcome() {
            return effectSucceeded || noOpSucceeded;
        }
    }
}
