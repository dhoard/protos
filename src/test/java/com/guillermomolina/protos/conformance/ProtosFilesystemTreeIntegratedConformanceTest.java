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

package com.guillermomolina.protos.conformance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.guillermomolina.protos.execution.ProtosCoreBootstrap;
import com.guillermomolina.protos.execution.ProtosNioReadOnlyTreeFilesystemBackend;
import com.guillermomolina.protos.execution.ProtosStandardFilesystemProtocol;
import com.guillermomolina.protos.runtime.ProtosActivation;
import com.guillermomolina.protos.runtime.ProtosBooleanValue;
import com.guillermomolina.protos.runtime.ProtosFilesystemOpenFlow;
import com.guillermomolina.protos.runtime.ProtosFilesystemOpenOptions;
import com.guillermomolina.protos.runtime.ProtosFilesystemTreeObservationFlow;
import com.guillermomolina.protos.runtime.ProtosFilesystemValue;
import com.guillermomolina.protos.runtime.ProtosFutureValue;
import com.guillermomolina.protos.runtime.ProtosObjectValue;
import com.guillermomolina.protos.runtime.ProtosPathComponents;
import com.guillermomolina.protos.runtime.ProtosPrelude;
import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.ServerSocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class ProtosFilesystemTreeIntegratedConformanceTest {
    private static final Path CORE = Path.of("protos", "lib", "core");
    private static final Path CASE_ROOT = Path.of("protos", "tests", "filesystem");

    @TempDir Path authorityRoot;

    @Test
    void productionEntriesExposeExactCompleteFrozenNoFollowDescriptorsThroughProtos()
            throws Exception {
        Files.writeString(
                authorityRoot.resolve("MixedCase.txt"),
                "value",
                StandardCharsets.UTF_8);
        Files.createDirectory(authorityRoot.resolve("nested"));
        Path outside = Files.createTempFile("protos-i024d-link-target-", ".txt");
        try {
            createSymlinkOrSkip(authorityRoot.resolve("opaque-link"), outside);
            try (Fixture fixture = productionFixture()) {
                assertSame(
                        ProtosBooleanValue.TRUE,
                        execute("tree-entries-production.protos", fixture.activation()));
            }
        } finally {
            Files.deleteIfExists(outside);
        }
    }

    @Test
    void productionEntriesExposeOtherKindThroughProtosWhenUnixSocketsAreAvailable()
            throws Exception {
        Path socketPath = authorityRoot.resolve("socket-node");
        try (ServerSocketChannel socket = ServerSocketChannel.open(StandardProtocolFamily.UNIX)) {
            socket.bind(UnixDomainSocketAddress.of(socketPath));
            try (Fixture fixture = productionFixture()) {
                assertSame(
                        ProtosBooleanValue.TRUE,
                        execute("tree-entries-other-production.protos", fixture.activation()));
            }
        } catch (UnsupportedOperationException | IOException unavailable) {
            assumeTrue(false, "host cannot create Unix-domain socket fixture");
        } finally {
            Files.deleteIfExists(socketPath);
        }
    }

    @Test
    void finalDirectorySymlinkObservationFailsAsIoErrorThroughProtos()
            throws Exception {
        Path outside = Files.createTempDirectory("protos-i024d-dir-target-");
        try {
            Files.writeString(
                    outside.resolve("secret.txt"),
                    "secret",
                    StandardCharsets.UTF_8);
            createSymlinkOrSkip(authorityRoot.resolve("alias"), outside);
            try (Fixture fixture = productionFixture()) {
                assertSame(
                        ProtosBooleanValue.TRUE,
                        execute(
                                "tree-observation-final-link-failure.protos",
                                fixture.activation()));
            }
        } finally {
            Files.deleteIfExists(outside.resolve("secret.txt"));
            Files.deleteIfExists(outside);
        }
    }

    @Test
    void entriesCancellationIsVisibleThroughProtosAndLateCompletionCannotWin()
            throws Exception {
        PendingBackend backend = new PendingBackend();
        Fixture fixture = fixture(backend);

        ProtosObjectValue result =
                assertInstanceOf(
                        ProtosObjectValue.class,
                        execute("tree-entries-cancellation.protos", fixture.activation()));
        assertSame(
                ProtosBooleanValue.TRUE,
                result.readLocalSlot("cancelled").orElseThrow());
        ProtosFutureValue future =
                assertInstanceOf(
                        ProtosFutureValue.class,
                        result.readLocalSlot("future").orElseThrow());
        assertEquals(ProtosFutureValue.State.CANCELLED, future.state());
        assertEquals(1, backend.entriesCancellations.get());

        backend.entriesCompletion
                .get()
                .succeeded(
                        List.of(
                                new ProtosFilesystemTreeObservationFlow.Entry(
                                        "late.txt",
                                        ProtosFilesystemTreeObservationFlow.EntryKind.REGULAR)));
        assertEquals(ProtosFutureValue.State.CANCELLED, future.state());
    }

    private Fixture productionFixture() throws Exception {
        ProtosNioReadOnlyTreeFilesystemBackend backend =
                new ProtosNioReadOnlyTreeFilesystemBackend(authorityRoot);
        if (!backend.secureConfinementAvailable()) {
            backend.close();
            assumeTrue(false, "host provider has no SecureDirectoryStream");
        }
        return fixture(backend);
    }

    private static Fixture fixture(ProtosStandardFilesystemProtocol.Backend backend)
            throws Exception {
        ProtosPrelude prelude = new ProtosCoreBootstrap().bootstrap(CORE);
        ProtosActivation activation = prelude.newModuleActivation();
        ProtosFilesystemValue filesystem =
                assertInstanceOf(
                        ProtosFilesystemValue.class,
                        ProtosStandardFilesystemProtocol.createCapability(
                                prelude.bytesPrototypeForRuntime(),
                                activation,
                                backend));
        activation.context().createLocalSlot("filesystem", filesystem);
        return new Fixture(
                activation,
                filesystem,
                backend instanceof ProtosNioReadOnlyTreeFilesystemBackend treeBackend
                        ? treeBackend
                        : null);
    }

    private static Object execute(String file, ProtosActivation activation) throws IOException {
        return com.guillermomolina.protos.execution.ProtosTestExecutionSupport.executeFileValueWithFreshModuleActivation(CASE_ROOT.resolve(file), activation);
    }

    private static void createSymlinkOrSkip(Path link, Path target) throws IOException {
        try {
            Files.createSymbolicLink(link, target);
        } catch (UnsupportedOperationException | IOException | SecurityException unavailable) {
            assumeTrue(false, "host cannot create symlink fixture");
        }
    }

    private record Fixture(
            ProtosActivation activation,
            ProtosFilesystemValue filesystem,
            ProtosNioReadOnlyTreeFilesystemBackend backend)
            implements AutoCloseable {
        @Override
        public void close() throws IOException {
            if (backend != null) {
                backend.close();
            }
        }
    }

    private static final class PendingBackend
            implements ProtosStandardFilesystemProtocol.Backend {
        private final AtomicReference<ProtosFilesystemTreeObservationFlow.EntriesCompletion>
                entriesCompletion = new AtomicReference<>();
        private final AtomicInteger entriesCancellations = new AtomicInteger();

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
            entriesCompletion.set(completion);
            return entriesCancellations::incrementAndGet;
        }
    }
}
