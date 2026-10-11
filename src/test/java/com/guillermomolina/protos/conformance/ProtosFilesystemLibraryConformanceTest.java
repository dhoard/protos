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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.guillermomolina.protos.execution.ProtosCoreBootstrap;
import com.guillermomolina.protos.execution.ProtosExecutionOutcome;
import com.guillermomolina.protos.execution.ProtosNioReadOnlyTreeFilesystemBackend;
import com.guillermomolina.protos.execution.ProtosRootTaskExecution;
import com.guillermomolina.protos.execution.ProtosSourceCompiler;
import com.guillermomolina.protos.execution.ProtosStandardFilesystemProtocol;
import com.guillermomolina.protos.execution.ProtosStandardLibraryModuleResolver;
import com.guillermomolina.protos.runtime.ProtosActivation;
import com.guillermomolina.protos.runtime.ProtosBooleanValue;
import com.guillermomolina.protos.runtime.ProtosBytesValue;
import com.guillermomolina.protos.runtime.ProtosCoreErrors;
import com.guillermomolina.protos.runtime.ProtosFilesystemValue;
import com.guillermomolina.protos.runtime.ProtosFileFlow;
import com.guillermomolina.protos.runtime.ProtosFilesystemOpenFlow;
import com.guillermomolina.protos.runtime.ProtosFilesystemOpenOptions;
import com.guillermomolina.protos.runtime.ProtosFutureValue;
import com.guillermomolina.protos.runtime.ProtosIntegerValue;
import com.guillermomolina.protos.runtime.ProtosNullValue;
import com.guillermomolina.protos.runtime.ProtosPathComponents;import com.guillermomolina.protos.runtime.ProtosObjectValue;
import com.guillermomolina.protos.runtime.ProtosPrelude;
import com.guillermomolina.protos.runtime.ProtosSignalException;
import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * LIB004-A1/A2/A3/B/C integration harness.
 *
 * <p>The behavior assertions live in Protos source. Java is restricted to provisioning the
 * confined Filesystem/standard-library resolver and inspecting the host fixture or exact Core
 * Error category where that boundary is not source-constructible.
 */
final class ProtosFilesystemLibraryConformanceTest {
    private static final Path CORE = Path.of("protos", "lib", "core");
    private static final Path STANDARD_LIBRARY = Path.of("protos", "lib");
    private static final Path CASE_ROOT =
            Path.of("protos", "tests", "conformance", "library", "io");

    private static ProtosPrelude prelude;

    @BeforeAll
    static void bootstrapCore() throws Exception {
        prelude =
                new ProtosCoreBootstrap()
                        .bootstrap(
                                CORE,
                                new ProtosStandardLibraryModuleResolver(
                                        STANDARD_LIBRARY));
    }

    @TempDir Path authorityRoot;

    @Test
    void readAllBytesReturnsFreshFutureAndFreshOpenBytesForEmptyFile() throws Exception {
        Files.write(authorityRoot.resolve("empty.bin"), new byte[0]);

        try (Fixture fixture = fixture()) {
            assertSame(
                    ProtosBooleanValue.TRUE,
                    execute("read-all-bytes-empty.protos", fixture.activation()));
        }
    }

    @Test
    void readAllBytesPreservesOrderedContentAcrossMultipleReadWindows() throws Exception {
        byte[] content = new byte[131089];
        Arrays.fill(content, 0, 65536, (byte) 65);
        Arrays.fill(content, 65536, 131072, (byte) 66);
        Arrays.fill(content, 131072, content.length, (byte) 67);
        Files.write(authorityRoot.resolve("multi.bin"), content);

        try (Fixture fixture = fixture()) {
            assertSame(
                    ProtosBooleanValue.TRUE,
                    execute("read-all-bytes-multichunk.protos", fixture.activation()));
        }
    }

    @Test
    void readAllBytesPropagatesOpenFailureAsTheExistingCoreIoError() throws Exception {
        try (Fixture fixture = fixture()) {
            ProtosSignalException signal =
                    assertThrows(
                            ProtosSignalException.class,
                            () ->
                                    execute(
                                            "read-all-bytes-open-error.protos",
                                            fixture.activation()));

            assertSame(
                    ProtosCoreErrors.prototype(
                            fixture.activation(), ProtosCoreErrors.StandardError.I_O_ERROR),
                    signal.error().parent().orElseThrow());
        }
    }


    @Test
    void readAllBytesCancellationAfterAcquiredReadClosesBeforeTerminalCancellation()
            throws Exception {
        ControlledFixture fixture = controlledFixture(ReadMode.PENDING);
        ExecutorService executor = daemonExecutor();
        CompletableFuture<Object> execution =
                executeAsync("read-all-bytes-cancel-after-acquire.protos", fixture.activation(), executor);
        try {
            assertTrue(fixture.resource().awaitCloseStarted(), "File close did not start");
            ProtosFutureValue helperFuture = helperFuture(fixture);

            assertEquals(ProtosFutureValue.State.PENDING, helperFuture.state());
            assertDefaultReadOpen(fixture.backend());
            assertEquals(1, fixture.resource().readStarts());
            assertEquals(1, fixture.resource().readCancellations());
            assertEquals(1, fixture.resource().closeStarts());
            assertEquals(0, fixture.backend().untransferredReleases());

            fixture.resource().succeedClose();
            ProtosObjectValue result =
                    assertInstanceOf(ProtosObjectValue.class, execution.get(5, TimeUnit.SECONDS));

            assertSame(ProtosBooleanValue.TRUE, result.readLocalSlot("sameCancelResult").orElseThrow());
            assertSame(ProtosBooleanValue.TRUE, result.readLocalSlot("cancelled").orElseThrow());
            assertSame(helperFuture, result.readLocalSlot("future").orElseThrow());
            assertEquals(ProtosFutureValue.State.CANCELLED, helperFuture.state());
            assertEquals(1, fixture.resource().readStarts());
        } finally {
            fixture.resource().succeedCloseIfPending();
            shutdown(executor);
        }
    }

    @Test
    void readAllBytesReadFailureWaitsForSuspendingCleanupBeforePropagation()
            throws Exception {
        ControlledFixture fixture = controlledFixture(ReadMode.FAIL_FIRST_THEN_PENDING);
        ExecutorService executor = daemonExecutor();
        CompletableFuture<Object> execution =
                executeAsync("read-all-bytes-observe-io-error.protos", fixture.activation(), executor);
        try {
            assertTrue(fixture.resource().awaitCloseStarted(), "File close did not start");
            ProtosFutureValue helperFuture = helperFuture(fixture);

            assertEquals(ProtosFutureValue.State.PENDING, helperFuture.state());
            assertDefaultReadOpen(fixture.backend());
            assertEquals(2, fixture.resource().readStarts());
            assertEquals(1, fixture.resource().readCancellations());
            assertEquals(1, fixture.resource().closeStarts());

            fixture.resource().succeedClose();
            ProtosObjectValue result =
                    assertInstanceOf(ProtosObjectValue.class, execution.get(5, TimeUnit.SECONDS));

            assertObservedIoFailure(fixture, result, helperFuture);
        } finally {
            fixture.resource().succeedCloseIfPending();
            shutdown(executor);
        }
    }

    @Test
    void readAllBytesCloseFailureAfterSuccessfulReadFailsOnlyAfterCleanupCompletes()
            throws Exception {
        ControlledFixture fixture = controlledFixture(ReadMode.EOF);
        ExecutorService executor = daemonExecutor();
        CompletableFuture<Object> execution =
                executeAsync("read-all-bytes-observe-io-error.protos", fixture.activation(), executor);
        try {
            assertTrue(fixture.resource().awaitCloseStarted(), "File close did not start");
            ProtosFutureValue helperFuture = helperFuture(fixture);

            assertEquals(ProtosFutureValue.State.PENDING, helperFuture.state());
            assertDefaultReadOpen(fixture.backend());
            assertTrue(fixture.resource().readStarts() > 0);
            assertEquals(1, fixture.resource().closeStarts());

            fixture.resource().failClose();
            ProtosObjectValue result =
                    assertInstanceOf(ProtosObjectValue.class, execution.get(5, TimeUnit.SECONDS));

            assertObservedIoFailure(fixture, result, helperFuture);
        } finally {
            fixture.resource().succeedCloseIfPending();
            shutdown(executor);
        }
    }

    @Test
    void readAllBytesLaterCloseFailureComposesWithEarlierReadFailurePrecedence()
            throws Exception {
        ControlledFixture fixture = controlledFixture(ReadMode.FAIL_FIRST_THEN_PENDING);
        ExecutorService executor = daemonExecutor();
        CompletableFuture<Object> execution =
                executeAsync("read-all-bytes-observe-io-error.protos", fixture.activation(), executor);
        try {
            assertTrue(fixture.resource().awaitCloseStarted(), "File close did not start");
            ProtosFutureValue helperFuture = helperFuture(fixture);

            // The body has already established a read failure, but D043 cleanup is
            // still active and the helper cannot expose a terminal result before
            // the later close outcome is known.
            assertEquals(ProtosFutureValue.State.PENDING, helperFuture.state());
            assertEquals(2, fixture.resource().readStarts());
            assertEquals(1, fixture.resource().readCancellations());

            fixture.resource().failClose();
            ProtosObjectValue result =
                    assertInstanceOf(ProtosObjectValue.class, execution.get(5, TimeUnit.SECONDS));

            assertObservedIoFailure(fixture, result, helperFuture);
            assertEquals(1, fixture.resource().closeStarts());
        } finally {
            fixture.resource().succeedCloseIfPending();
            shutdown(executor);
        }
    }


    @Test
    void readAllBytesCancellationDuringPendingOpenReleasesLateUntransferredAcquisition()
            throws Exception {
        PendingOpenFixture fixture = pendingOpenFixture();
        ExecutorService executor = daemonExecutor();
        CompletableFuture<Object> execution =
                executeAsync(
                        "read-all-bytes-cancel-pending-open.protos",
                        fixture.activation(),
                        executor);
        try {
            assertTrue(
                    fixture.backend().awaitOpenCancellation(),
                    "pending Filesystem.open cancellation was not requested");

            ProtosObjectValue result =
                    assertInstanceOf(
                            ProtosObjectValue.class,
                            execution.get(5, TimeUnit.SECONDS));
            ProtosFutureValue helperFuture =
                    assertInstanceOf(
                            ProtosFutureValue.class,
                            fixture.control().readLocalSlot("future").orElseThrow());

            assertSame(
                    ProtosBooleanValue.TRUE,
                    result.readLocalSlot("sameCancelResult").orElseThrow());
            assertSame(
                    ProtosBooleanValue.TRUE,
                    result.readLocalSlot("cancelled").orElseThrow());
            assertSame(helperFuture, result.readLocalSlot("future").orElseThrow());
            assertEquals(ProtosFutureValue.State.CANCELLED, helperFuture.state());
            assertDefaultReadOpen(fixture.backend());
            assertEquals(1, fixture.backend().openCancellations());
            assertEquals(0, fixture.backend().untransferredReleases());
            assertEquals(0, fixture.resource().readStarts());
            assertEquals(0, fixture.resource().closeStarts());

            // A backend result that arrives after pre-commit cancellation cannot
            // transfer a File to the already-cancelled helper. The standard open
            // flow owns that untransferred result and releases it exactly once.
            fixture.backend().succeedLate();
            assertEquals(1, fixture.backend().untransferredReleases());
            assertEquals(0, fixture.resource().readStarts());
            assertEquals(0, fixture.resource().closeStarts());
            assertEquals(ProtosFutureValue.State.CANCELLED, helperFuture.state());
        } finally {
            fixture.backend().succeedLateIfPending();
            shutdown(executor);
        }
    }



    @Test
    void writeAllBytesBehaviorIsDrivenByProtosCases() throws Exception {
        WriteFixture snapshot =
                runWriteCase(
                        "write-all-bytes-snapshot.protos",
                        WriteMode.SUCCESS,
                        new byte[0],
                        0,
                        0);
        assertWriteOpen(snapshot.backend());
        assertEquals(1, snapshot.resource().writeStarts());
        assertEquals(3, snapshot.resource().maxWriteLength());

        WriteFixture empty =
                runWriteCase(
                        "write-all-bytes-truncate-empty.protos",
                        WriteMode.SUCCESS,
                        "stale".getBytes(StandardCharsets.ISO_8859_1),
                        0,
                        0);
        assertWriteOpen(empty.backend());
        assertEquals(0, empty.resource().writeStarts());

        WriteFixture large =
                runWriteCase(
                        "write-all-bytes-large-bounded.protos",
                        WriteMode.SUCCESS,
                        new byte[0],
                        0,
                        131089);
        assertWriteOpen(large.backend());
        assertEquals(3, large.resource().writeStarts());
        assertTrue(large.resource().maxWriteLength() <= 65536);

        WriteFixture prefixFailure =
                runWriteCase(
                        "write-all-bytes-prefix-failure.protos",
                        WriteMode.FAIL_PREFIX,
                        new byte[0],
                        3,
                        0);
        assertWriteOpen(prefixFailure.backend());
        assertEquals(1, prefixFailure.resource().writeStarts());

        WriteFixture closeFailure =
                runWriteCase(
                        "write-all-bytes-close-failure.protos",
                        WriteMode.CLOSE_FAILURE,
                        new byte[0],
                        0,
                        0);
        assertWriteOpen(closeFailure.backend());
        assertEquals(1, closeFailure.resource().closeStarts());

        WriteFixture pendingWrite =
                runWriteCase(
                        "write-all-bytes-cancel-pending-write.protos",
                        WriteMode.PENDING_UNCOMMITTED_WRITE,
                        "old".getBytes(StandardCharsets.ISO_8859_1),
                        0,
                        0);
        assertWriteOpen(pendingWrite.backend());
        assertEquals(1, pendingWrite.resource().writeStarts());
        assertEquals(1, pendingWrite.resource().writeCancellations());
        assertEquals(1, pendingWrite.resource().closeStarts());

        WriteFixture invalid =
                runWriteCase(
                        "write-all-bytes-invalid-before-io.protos",
                        WriteMode.SUCCESS,
                        "unchanged".getBytes(StandardCharsets.ISO_8859_1),
                        0,
                        0);
        assertEquals(0, invalid.backend().writeOpenCount());
        assertEquals(0, invalid.resource().writeStarts());

        WriteFixture openFailure =
                runWriteCase(
                        "write-all-bytes-open-failure.protos",
                        WriteMode.OPEN_FAILURE,
                        "unchanged".getBytes(StandardCharsets.ISO_8859_1),
                        0,
                        0);
        assertWriteOpen(openFailure.backend());
        assertEquals(0, openFailure.resource().writeStarts());
    }

    @Test
    void writeAllBytesCommittedPendingOpenCustodyIsDrivenByProtosCase() throws Exception {
        WriteFixture fixture =
                writeFixture(
                        WriteMode.COMMITTED_PENDING_OPEN,
                        "stale".getBytes(StandardCharsets.ISO_8859_1),
                        0,
                        0);
        ExecutorService executor = daemonExecutor();
        CompletableFuture<Object> execution =
                executeAsync(
                        "write-all-bytes-cancel-committed-open.protos",
                        fixture.activation(),
                        executor);
        try {
            assertTrue(
                    fixture.backend().awaitCommittedOpenCancellation(),
                    "committed pending Filesystem.open cancellation was not requested");
            assertWriteOpen(fixture.backend());
            assertEquals(0, fixture.resource().writeStarts());
            assertEquals(0, fixture.resource().closeStarts());
            assertEquals(0, fixture.backend().untransferredReleases());

            fixture.backend().completeCommittedOpen();
            assertSame(
                    ProtosBooleanValue.TRUE,
                    execution.get(5, TimeUnit.SECONDS));

            assertEquals(0, fixture.resource().writeStarts());
            assertEquals(1, fixture.resource().closeStarts());
            assertEquals(0, fixture.backend().untransferredReleases());
            assertEquals(0, fixture.backend().contentSnapshot().length);
        } finally {
            fixture.backend().completeCommittedOpenIfPending();
            shutdown(executor);
        }
    }




    @Test
    void wholeTextHelpersBehaviorIsDrivenByProtosCases() throws Exception {
        runWriteCase(
                "read-all-text-latin1.protos",
                WriteMode.SUCCESS,
                new byte[] {99, 97, 102, (byte) 0xe9},
                0,
                0);
        runWriteCase(
                "read-all-text-utf8-strict-error.protos",
                WriteMode.SUCCESS,
                new byte[] {(byte) 0xc3, 0x28},
                0,
                0);
        runWriteCase(
                "read-all-text-no-default-encoding.protos",
                WriteMode.SUCCESS,
                "unchanged".getBytes(StandardCharsets.ISO_8859_1),
                0,
                0);

        WriteFixture write =
                runWriteCase(
                        "write-all-text-utf16be.protos",
                        WriteMode.SUCCESS,
                        "stale".getBytes(StandardCharsets.ISO_8859_1),
                        0,
                        0);
        assertWriteOpen(write.backend());

        WriteFixture encodingFailure =
                runWriteCase(
                        "write-all-text-encoding-error-before-io.protos",
                        WriteMode.SUCCESS,
                        "unchanged".getBytes(StandardCharsets.ISO_8859_1),
                        0,
                        0);
        assertEquals(0, encodingFailure.backend().writeOpenCount());

        WriteFixture missingEncoding =
                runWriteCase(
                        "write-all-text-no-default-encoding.protos",
                        WriteMode.SUCCESS,
                        "unchanged".getBytes(StandardCharsets.ISO_8859_1),
                        0,
                        0);
        assertEquals(0, missingEncoding.backend().writeOpenCount());

        WriteFixture cancelled =
                runWriteCase(
                        "write-all-text-cancel-pending-write.protos",
                        WriteMode.PENDING_UNCOMMITTED_WRITE,
                        "old".getBytes(StandardCharsets.ISO_8859_1),
                        0,
                        0);
        assertEquals(1, cancelled.resource().writeCancellations());

        WriteFixture closeFailure =
                runWriteCase(
                        "write-all-text-close-failure.protos",
                        WriteMode.CLOSE_FAILURE,
                        new byte[0],
                        0,
                        0);
        assertEquals(1, closeFailure.resource().closeStarts());
    }

    @Test
    void readAllTextCloseFailureIsObservedByProtosCase() throws Exception {
        ControlledFixture fixture = controlledFixture(ReadMode.EOF);
        ExecutorService executor = daemonExecutor();
        CompletableFuture<Object> execution =
                executeAsync("read-all-text-close-failure.protos", fixture.activation(), executor);
        try {
            assertTrue(fixture.resource().awaitCloseStarted(), "File close did not start");
            fixture.resource().failClose();
            assertSame(ProtosBooleanValue.TRUE, execution.get(5, TimeUnit.SECONDS));
        } finally {
            fixture.resource().succeedCloseIfPending();
            shutdown(executor);
        }
    }


    private Fixture fixture() throws Exception {
        ProtosActivation activation = prelude.newModuleActivation();
        ProtosNioReadOnlyTreeFilesystemBackend backend =
                new ProtosNioReadOnlyTreeFilesystemBackend(authorityRoot);
        if (!backend.secureConfinementAvailable()) {
            backend.close();
            assumeTrue(false, "host provider has no SecureDirectoryStream");
        }

        ProtosObjectValue rawFilesystem =
                ProtosStandardFilesystemProtocol.createCapability(
                        prelude.bytesPrototypeForRuntime(), activation, backend);
        ProtosFilesystemValue filesystem =
                org.junit.jupiter.api.Assertions.assertInstanceOf(
                        ProtosFilesystemValue.class, rawFilesystem);
        activation.context().createLocalSlot("filesystem", filesystem);
        return new Fixture(activation, backend);
    }


    private ControlledFixture controlledFixture(ReadMode readMode) throws Exception {
        ProtosActivation activation = prelude.newModuleActivation();
        ProtosObjectValue control = new ProtosObjectValue(ProtosObjectValue.rootObject());
        ProtosFutureValue gate =
                new ProtosFutureValue(prelude.futurePrototype(), activation.executionDomain());
        ControlledReadableResource resource =
                new ControlledReadableResource(readMode, gate, activation);
        ControlledBackend backend = new ControlledBackend(resource);
        ProtosFilesystemValue filesystem =
                assertInstanceOf(
                        ProtosFilesystemValue.class,
                        ProtosStandardFilesystemProtocol.createCapability(
                                prelude.bytesPrototypeForRuntime(), activation, backend));

        activation.context().createLocalSlot("filesystem", filesystem);
        activation.context().createLocalSlot("control", control);
        activation.context().createLocalSlot("gate", gate);
        return new ControlledFixture(activation, prelude, control, backend, resource);
    }


    private PendingOpenFixture pendingOpenFixture() throws Exception {
        ProtosActivation activation = prelude.newModuleActivation();
        ProtosObjectValue control = new ProtosObjectValue(ProtosObjectValue.rootObject());
        ProtosFutureValue gate =
                new ProtosFutureValue(prelude.futurePrototype(), activation.executionDomain());
        LateReadableResource resource = new LateReadableResource();
        PendingOpenBackend backend =
                new PendingOpenBackend(resource, gate, activation);
        ProtosFilesystemValue filesystem =
                assertInstanceOf(
                        ProtosFilesystemValue.class,
                        ProtosStandardFilesystemProtocol.createCapability(
                                prelude.bytesPrototypeForRuntime(), activation, backend));

        activation.context().createLocalSlot("filesystem", filesystem);
        activation.context().createLocalSlot("control", control);
        activation.context().createLocalSlot("gate", gate);
        return new PendingOpenFixture(activation, control, backend, resource);
    }



    private WriteFixture runWriteCase(
            String file,
            WriteMode mode,
            byte[] initialContent,
            int failurePrefix,
            int injectedPayloadSize)
            throws Exception {
        WriteFixture fixture =
                writeFixture(mode, initialContent, failurePrefix, injectedPayloadSize);
        try {
            assertSame(ProtosBooleanValue.TRUE, execute(file, fixture.activation()), file);
        } catch (ProtosSignalException signal) {
            throw new AssertionError("Protos conformance case signalled unexpectedly: " + file, signal);
        }
        return fixture;
    }

    private WriteFixture writeFixture(
            WriteMode mode,
            byte[] initialContent,
            int failurePrefix,
            int injectedPayloadSize)
            throws Exception {
        ProtosActivation activation = prelude.newModuleActivation();
        ProtosObjectValue control = new ProtosObjectValue(ProtosObjectValue.rootObject());
        ProtosFutureValue gate =
                new ProtosFutureValue(prelude.futurePrototype(), activation.executionDomain());
        ControlledWritableResource resource =
                new ControlledWritableResource(mode, failurePrefix, gate, activation);
        ControlledWriteBackend backend =
                new ControlledWriteBackend(
                        mode, initialContent, gate, activation, resource);
        ProtosFilesystemValue filesystem =
                assertInstanceOf(
                        ProtosFilesystemValue.class,
                        ProtosStandardFilesystemProtocol.createCapability(
                                prelude.bytesPrototypeForRuntime(), activation, backend));

        activation.context().createLocalSlot("filesystem", filesystem);
        activation.context().createLocalSlot("control", control);
        activation.context().createLocalSlot("gate", gate);
        if (injectedPayloadSize > 0) {
            activation.context().createLocalSlot(
                    "largePayload",
                    patternedBytes(prelude, injectedPayloadSize));
        }
        return new WriteFixture(activation, control, backend, resource);
    }


    private static CompletableFuture<Object> executeAsync(
            String file, ProtosActivation activation, ExecutorService executor) {
        return CompletableFuture.supplyAsync(
                () -> {
                    try {
                        return execute(file, activation);
                    } catch (IOException failure) {
                        throw new CompletionException(failure);
                    }
                },
                executor);
    }

    private static ExecutorService daemonExecutor() {
        return Executors.newSingleThreadExecutor(
                runnable -> {
                    Thread thread = new Thread(runnable, "lib004-a2-controlled-execution");
                    thread.setDaemon(true);
                    return thread;
                });
    }

    private static void shutdown(ExecutorService executor) throws InterruptedException {
        executor.shutdownNow();
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
    }

    private static ProtosFutureValue helperFuture(ControlledFixture fixture) {
        return assertInstanceOf(
                ProtosFutureValue.class,
                fixture.control().readLocalSlot("future").orElseThrow());
    }

    private static void assertObservedIoFailure(
            ControlledFixture fixture,
            ProtosObjectValue result,
            ProtosFutureValue helperFuture) {
        assertSame(ProtosBooleanValue.TRUE, result.readLocalSlot("failed").orElseThrow());
        assertSame(helperFuture, result.readLocalSlot("future").orElseThrow());
        assertEquals(ProtosFutureValue.State.FAILED, helperFuture.state());
        ProtosObjectValue error = helperFuture.failedError().orElseThrow();
        assertSame(error, result.readLocalSlot("observedError").orElseThrow());
        assertSame(
                ProtosCoreErrors.prototype(
                        fixture.activation(), ProtosCoreErrors.StandardError.I_O_ERROR),
                error.parent().orElseThrow());
    }

    private static void assertDefaultReadOpen(ControlledBackend backend) {
        ProtosFilesystemOpenOptions options = backend.options().get();
        assertTrue(options.readAccess());
        assertFalse(options.writeAccess());
        assertEquals(ProtosFilesystemOpenOptions.Creation.EXISTING, options.creation());
        assertFalse(options.truncateInitialContent());
        assertEquals(1, backend.openCount());
        assertEquals(0, backend.openCancellations());
    }

    private static void assertDefaultReadOpen(PendingOpenBackend backend) {
        ProtosFilesystemOpenOptions options = backend.options().get();
        assertTrue(options.readAccess());
        assertFalse(options.writeAccess());
        assertEquals(ProtosFilesystemOpenOptions.Creation.EXISTING, options.creation());
        assertFalse(options.truncateInitialContent());
        assertEquals(1, backend.openCount());
    }



    private static void assertWriteOpen(ControlledWriteBackend backend) {
        ProtosFilesystemOpenOptions options = backend.writeOptions().get();
        assertFalse(options.readAccess());
        assertTrue(options.writeAccess());
        assertEquals(ProtosFilesystemOpenOptions.Creation.CREATE, options.creation());
        assertTrue(options.truncateInitialContent());
        assertEquals(1, backend.writeOpenCount());
    }

    private static ProtosBytesValue patternedBytes(ProtosPrelude prelude, int size) {
        ProtosBytesValue result = new ProtosBytesValue(prelude.bytesPrototypeForRuntime());
        for (int index = 0; index < size; index++) {
            result.indexedAdd(
                    new ProtosIntegerValue((index * 31L + 7L) & 0xffL));
        }
        return result;
    }


    private static Object execute(String file, ProtosActivation activation) throws IOException {
        String source =
                Files.readString(CASE_ROOT.resolve(file), StandardCharsets.UTF_8);
        ProtosExecutionOutcome outcome =
                com.guillermomolina.protos.execution.ProtosTestExecutionSupport.execute(
source,
activation);

        return switch (outcome.state()) {
            case COMPLETED -> outcome.value();
            case FAILED -> throw new ProtosSignalException(outcome.error());
            case CANCELLED -> throw new AssertionError(
                    "LIB004-A1 conformance root unexpectedly cancelled");
        };
    }



    private enum ReadMode {
        PENDING,
        FAIL_FIRST_THEN_PENDING,
        EOF
    }

    private record ControlledFixture(
            ProtosActivation activation,
            ProtosPrelude prelude,
            ProtosObjectValue control,
            ControlledBackend backend,
            ControlledReadableResource resource) {}

    private static final class ControlledBackend
            implements ProtosStandardFilesystemProtocol.Backend {
        private final ControlledReadableResource resource;
        private final AtomicInteger openCount = new AtomicInteger();
        private final AtomicInteger openCancellations = new AtomicInteger();
        private final AtomicInteger untransferredReleases = new AtomicInteger();
        private final AtomicReference<ProtosFilesystemOpenOptions> options =
                new AtomicReference<>();

        private ControlledBackend(ControlledReadableResource resource) {
            this.resource = resource;
        }

        @Override
        public ProtosFilesystemOpenFlow.Cancellation open(
                ProtosPathComponents path,
                ProtosFilesystemOpenOptions capturedOptions,
                ProtosStandardFilesystemProtocol.OpenCompletion completion) {
            openCount.incrementAndGet();
            if (!options.compareAndSet(null, capturedOptions)) {
                throw new IllegalStateException("LIB004-A2 expected exactly one open");
            }
            completion.succeeded(
                    resource,
                    new ProtosFileFlow.Capabilities(
                            true, false, false, false, false, false),
                    untransferredReleases::incrementAndGet);
            return openCancellations::incrementAndGet;
        }

        private AtomicReference<ProtosFilesystemOpenOptions> options() {
            return options;
        }

        private int openCount() {
            return openCount.get();
        }

        private int openCancellations() {
            return openCancellations.get();
        }

        private int untransferredReleases() {
            return untransferredReleases.get();
        }
    }

    private static final class ControlledReadableResource
            implements ProtosFileFlow.ReadableResource {
        private final ReadMode readMode;
        private final ProtosFutureValue gate;
        private final ProtosActivation activation;
        private final AtomicInteger readStarts = new AtomicInteger();
        private final AtomicInteger readCancellations = new AtomicInteger();
        private final AtomicInteger closeStarts = new AtomicInteger();
        private final AtomicReference<ProtosFileFlow.CloseCompletion> closeCompletion =
                new AtomicReference<>();
        private final AtomicBoolean closeCompleted = new AtomicBoolean();
        private final CountDownLatch closeStarted = new CountDownLatch(1);

        private ControlledReadableResource(
                ReadMode readMode, ProtosFutureValue gate, ProtosActivation activation) {
            this.readMode = readMode;
            this.gate = gate;
            this.activation = activation;
        }

        @Override
        public ProtosFileFlow.Cancellation readAt(
                long position,
                int maxBytes,
                ProtosFileFlow.ReadCompletion completion) {
            int invocation = readStarts.incrementAndGet();
            if (invocation == 1) {
                gate.resolve(ProtosNullValue.INSTANCE, activation);
            }

            if (readMode == ReadMode.EOF) {
                completion.eof();
                return () -> {};
            }
            if (readMode == ReadMode.FAIL_FIRST_THEN_PENDING && invocation == 1) {
                completion.failed();
                return () -> {};
            }
            return readCancellations::incrementAndGet;
        }

        @Override
        public void close(ProtosFileFlow.CloseCompletion completion) {
            if (!closeCompletion.compareAndSet(null, completion)) {
                throw new IllegalStateException("File resource close started more than once");
            }
            closeStarts.incrementAndGet();
            closeStarted.countDown();
        }

        private boolean awaitCloseStarted() throws InterruptedException {
            return closeStarted.await(5, TimeUnit.SECONDS);
        }

        private void succeedClose() {
            completeClose(false);
        }

        private void failClose() {
            completeClose(true);
        }

        private void succeedCloseIfPending() {
            ProtosFileFlow.CloseCompletion completion = closeCompletion.get();
            if (completion != null && closeCompleted.compareAndSet(false, true)) {
                completion.succeeded();
            }
        }

        private void completeClose(boolean fail) {
            ProtosFileFlow.CloseCompletion completion = closeCompletion.get();
            if (completion == null) {
                throw new IllegalStateException("File close has not started");
            }
            if (!closeCompleted.compareAndSet(false, true)) {
                throw new IllegalStateException("File close already completed");
            }
            if (fail) {
                completion.failed();
            } else {
                completion.succeeded();
            }
        }

        private int readStarts() {
            return readStarts.get();
        }

        private int readCancellations() {
            return readCancellations.get();
        }

        private int closeStarts() {
            return closeStarts.get();
        }
    }




    private enum WriteMode {
        SUCCESS,
        FAIL_PREFIX,
        CLOSE_FAILURE,
        PENDING_UNCOMMITTED_WRITE,
        COMMITTED_PENDING_OPEN,
        OPEN_FAILURE
    }

    private record WriteFixture(
            ProtosActivation activation,
            ProtosObjectValue control,
            ControlledWriteBackend backend,
            ControlledWritableResource resource) {}

    private static final class ControlledWriteBackend
            implements ProtosStandardFilesystemProtocol.Backend {
        private final WriteMode mode;
        private final ProtosFutureValue gate;
        private final ProtosActivation activation;
        private final ControlledWritableResource writeResource;
        private final java.util.ArrayList<Byte> content = new java.util.ArrayList<>();
        private final AtomicReference<ProtosFilesystemOpenOptions> writeOptions =
                new AtomicReference<>();
        private final AtomicReference<ProtosStandardFilesystemProtocol.OpenCompletion>
                committedOpenCompletion = new AtomicReference<>();
        private final AtomicInteger writeOpenCount = new AtomicInteger();
        private final AtomicInteger readOpenCount = new AtomicInteger();
        private final AtomicInteger openCancellations = new AtomicInteger();
        private final AtomicInteger untransferredReleases = new AtomicInteger();
        private final CountDownLatch committedOpenCancellation = new CountDownLatch(1);
        private final AtomicBoolean committedOpenCompleted = new AtomicBoolean();

        private ControlledWriteBackend(
                WriteMode mode,
                byte[] initialContent,
                ProtosFutureValue gate,
                ProtosActivation activation,
                ControlledWritableResource writeResource) {
            this.mode = mode;
            this.gate = gate;
            this.activation = activation;
            this.writeResource = writeResource;
            replaceContent(initialContent);
            writeResource.attachContent(this);
        }

        @Override
        public ProtosFilesystemOpenFlow.Cancellation open(
                ProtosPathComponents path,
                ProtosFilesystemOpenOptions options,
                ProtosStandardFilesystemProtocol.OpenCompletion completion) {
            if (isWriteAllBytesOpen(options)) {
                writeOpenCount.incrementAndGet();
                if (!writeOptions.compareAndSet(null, options)) {
                    throw new IllegalStateException("LIB004-B expected exactly one write open");
                }
                if (mode == WriteMode.OPEN_FAILURE) {
                    completion.failed();
                    return () -> {};
                }
                if (!completion.commitPortableEffect()) {
                    return () -> {};
                }
                truncateContent();

                if (mode == WriteMode.COMMITTED_PENDING_OPEN) {
                    if (!committedOpenCompletion.compareAndSet(null, completion)) {
                        throw new IllegalStateException("committed pending open already installed");
                    }
                    gate.resolve(ProtosNullValue.INSTANCE, activation);
                    return () -> {
                        openCancellations.incrementAndGet();
                        committedOpenCancellation.countDown();
                    };
                }

                completion.succeeded(
                        writeResource,
                        new ProtosFileFlow.Capabilities(
                                false, true, false, false, false, false),
                        () -> {
                            untransferredReleases.incrementAndGet();
                            writeResource.releaseSilently();
                        });
                return openCancellations::incrementAndGet;
            }

            if (isDefaultReadOpen(options)) {
                readOpenCount.incrementAndGet();
                SnapshotReadableResource resource =
                        new SnapshotReadableResource(contentSnapshot());
                completion.succeeded(
                        resource,
                        new ProtosFileFlow.Capabilities(
                                true, false, false, false, false, false),
                        resource::releaseSilently);
                return () -> {};
            }

            completion.failed();
            return () -> {};
        }

        private boolean awaitCommittedOpenCancellation() throws InterruptedException {
            return committedOpenCancellation.await(5, TimeUnit.SECONDS);
        }

        private void completeCommittedOpen() {
            if (!committedOpenCompleted.compareAndSet(false, true)) {
                throw new IllegalStateException("committed pending open already completed");
            }
            ProtosStandardFilesystemProtocol.OpenCompletion completion =
                    committedOpenCompletion.get();
            if (completion == null) {
                throw new IllegalStateException("committed pending open has not started");
            }
            completion.succeeded(
                    writeResource,
                    new ProtosFileFlow.Capabilities(
                            false, true, false, false, false, false),
                    () -> {
                        untransferredReleases.incrementAndGet();
                        writeResource.releaseSilently();
                    });
        }

        private void completeCommittedOpenIfPending() {
            if (committedOpenCompletion.get() != null
                    && committedOpenCompleted.compareAndSet(false, true)) {
                committedOpenCompletion.get()
                        .succeeded(
                                writeResource,
                                new ProtosFileFlow.Capabilities(
                                        false, true, false, false, false, false),
                                () -> {
                                    untransferredReleases.incrementAndGet();
                                    writeResource.releaseSilently();
                                });
            }
        }

        private static boolean isWriteAllBytesOpen(ProtosFilesystemOpenOptions options) {
            return !options.readAccess()
                    && options.writeAccess()
                    && options.creation() == ProtosFilesystemOpenOptions.Creation.CREATE
                    && options.truncateInitialContent();
        }

        private static boolean isDefaultReadOpen(ProtosFilesystemOpenOptions options) {
            return options.readAccess()
                    && !options.writeAccess()
                    && options.creation() == ProtosFilesystemOpenOptions.Creation.EXISTING
                    && !options.truncateInitialContent();
        }

        private synchronized void replaceContent(byte[] bytes) {
            content.clear();
            for (byte value : bytes) {
                content.add(value);
            }
        }

        private synchronized void truncateContent() {
            content.clear();
        }

        private synchronized void contribute(long position, byte[] bytes, int length) {
            int start = Math.toIntExact(position);
            while (content.size() < start) {
                content.add((byte) 0);
            }
            for (int index = 0; index < length; index++) {
                int target = start + index;
                if (target < content.size()) {
                    content.set(target, bytes[index]);
                } else {
                    content.add(bytes[index]);
                }
            }
        }

        private synchronized byte[] contentSnapshot() {
            byte[] result = new byte[content.size()];
            for (int index = 0; index < content.size(); index++) {
                result[index] = content.get(index);
            }
            return result;
        }

        private AtomicReference<ProtosFilesystemOpenOptions> writeOptions() {
            return writeOptions;
        }

        private int writeOpenCount() {
            return writeOpenCount.get();
        }

        private int untransferredReleases() {
            return untransferredReleases.get();
        }
    }

    private static final class ControlledWritableResource
            implements ProtosFileFlow.WritableResource {
        private final WriteMode mode;
        private final int failurePrefix;
        private final ProtosFutureValue gate;
        private final ProtosActivation activation;
        private final AtomicInteger writeStarts = new AtomicInteger();
        private final AtomicInteger writeCancellations = new AtomicInteger();
        private final AtomicInteger closeStarts = new AtomicInteger();
        private final AtomicInteger maxWriteLength = new AtomicInteger();
        private ControlledWriteBackend contentOwner;

        private ControlledWritableResource(
                WriteMode mode,
                int failurePrefix,
                ProtosFutureValue gate,
                ProtosActivation activation) {
            this.mode = mode;
            this.failurePrefix = failurePrefix;
            this.gate = gate;
            this.activation = activation;
        }

        private void attachContent(ControlledWriteBackend owner) {
            this.contentOwner = owner;
        }

        @Override
        public ProtosFileFlow.Cancellation writeAt(
                long position,
                byte[] bytes,
                ProtosFileFlow.WriteCompletion completion) {
            writeStarts.incrementAndGet();
            maxWriteLength.accumulateAndGet(bytes.length, Math::max);

            if (mode == WriteMode.PENDING_UNCOMMITTED_WRITE) {
                gate.resolve(ProtosNullValue.INSTANCE, activation);
                return writeCancellations::incrementAndGet;
            }

            if (!completion.commitFirstContribution()) {
                return writeCancellations::incrementAndGet;
            }

            if (mode == WriteMode.FAIL_PREFIX) {
                int contributed = Math.min(failurePrefix, bytes.length);
                contentOwner.contribute(position, bytes, contributed);
                completion.failed(contributed);
                return () -> {};
            }

            contentOwner.contribute(position, bytes, bytes.length);
            completion.succeeded();
            return () -> {};
        }

        @Override
        public void close(ProtosFileFlow.CloseCompletion completion) {
            closeStarts.incrementAndGet();
            if (mode == WriteMode.CLOSE_FAILURE) {
                completion.failed();
            } else {
                completion.succeeded();
            }
        }

        private void releaseSilently() {
            // The controlled backend has no native resource beyond this object.
        }

        private int writeStarts() {
            return writeStarts.get();
        }

        private int writeCancellations() {
            return writeCancellations.get();
        }

        private int closeStarts() {
            return closeStarts.get();
        }

        private int maxWriteLength() {
            return maxWriteLength.get();
        }
    }

    private static final class SnapshotReadableResource
            implements ProtosFileFlow.ReadableResource {
        private final byte[] content;

        private SnapshotReadableResource(byte[] content) {
            this.content = content.clone();
        }

        @Override
        public ProtosFileFlow.Cancellation readAt(
                long position,
                int maxBytes,
                ProtosFileFlow.ReadCompletion completion) {
            int start = Math.toIntExact(position);
            if (start >= content.length) {
                completion.eof();
                return () -> {};
            }
            int end = Math.min(content.length, start + maxBytes);
            completion.data(Arrays.copyOfRange(content, start, end));
            return () -> {};
        }

        @Override
        public void close(ProtosFileFlow.CloseCompletion completion) {
            completion.succeeded();
        }

        private void releaseSilently() {
            // Immutable in-memory fixture; nothing native to release.
        }
    }


    private record PendingOpenFixture(
            ProtosActivation activation,
            ProtosObjectValue control,
            PendingOpenBackend backend,
            LateReadableResource resource) {}

    private static final class PendingOpenBackend
            implements ProtosStandardFilesystemProtocol.Backend {
        private final LateReadableResource resource;
        private final ProtosFutureValue gate;
        private final ProtosActivation activation;
        private final AtomicInteger openCount = new AtomicInteger();
        private final AtomicInteger openCancellations = new AtomicInteger();
        private final AtomicInteger untransferredReleases = new AtomicInteger();
        private final AtomicReference<ProtosFilesystemOpenOptions> options =
                new AtomicReference<>();
        private final AtomicReference<ProtosStandardFilesystemProtocol.OpenCompletion> completion =
                new AtomicReference<>();
        private final CountDownLatch openCancellation = new CountDownLatch(1);
        private final AtomicBoolean lateCompleted = new AtomicBoolean();

        private PendingOpenBackend(
                LateReadableResource resource,
                ProtosFutureValue gate,
                ProtosActivation activation) {
            this.resource = resource;
            this.gate = gate;
            this.activation = activation;
        }

        @Override
        public ProtosFilesystemOpenFlow.Cancellation open(
                ProtosPathComponents path,
                ProtosFilesystemOpenOptions capturedOptions,
                ProtosStandardFilesystemProtocol.OpenCompletion openCompletion) {
            openCount.incrementAndGet();
            if (!options.compareAndSet(null, capturedOptions)
                    || !completion.compareAndSet(null, openCompletion)) {
                throw new IllegalStateException("LIB004-A3 expected exactly one pending open");
            }
            gate.resolve(ProtosNullValue.INSTANCE, activation);
            return () -> {
                openCancellations.incrementAndGet();
                openCancellation.countDown();
            };
        }

        private boolean awaitOpenCancellation() throws InterruptedException {
            return openCancellation.await(5, TimeUnit.SECONDS);
        }

        private void succeedLate() {
            if (!lateCompleted.compareAndSet(false, true)) {
                throw new IllegalStateException("pending open already completed");
            }
            ProtosStandardFilesystemProtocol.OpenCompletion openCompletion = completion.get();
            if (openCompletion == null) {
                throw new IllegalStateException("pending open has not started");
            }
            openCompletion.succeeded(
                    resource,
                    new ProtosFileFlow.Capabilities(
                            true, false, false, false, false, false),
                    untransferredReleases::incrementAndGet);
        }

        private void succeedLateIfPending() {
            if (completion.get() != null && lateCompleted.compareAndSet(false, true)) {
                completion.get()
                        .succeeded(
                                resource,
                                new ProtosFileFlow.Capabilities(
                                        true, false, false, false, false, false),
                                untransferredReleases::incrementAndGet);
            }
        }

        private AtomicReference<ProtosFilesystemOpenOptions> options() {
            return options;
        }

        private int openCount() {
            return openCount.get();
        }

        private int openCancellations() {
            return openCancellations.get();
        }

        private int untransferredReleases() {
            return untransferredReleases.get();
        }
    }

    private static final class LateReadableResource
            implements ProtosFileFlow.ReadableResource {
        private final AtomicInteger readStarts = new AtomicInteger();
        private final AtomicInteger closeStarts = new AtomicInteger();

        @Override
        public ProtosFileFlow.Cancellation readAt(
                long position,
                int maxBytes,
                ProtosFileFlow.ReadCompletion completion) {
            readStarts.incrementAndGet();
            completion.eof();
            return () -> {};
        }

        @Override
        public void close(ProtosFileFlow.CloseCompletion completion) {
            closeStarts.incrementAndGet();
            completion.succeeded();
        }

        private int readStarts() {
            return readStarts.get();
        }

        private int closeStarts() {
            return closeStarts.get();
        }
    }

    private record Fixture(
            ProtosActivation activation,
            ProtosNioReadOnlyTreeFilesystemBackend backend)
            implements AutoCloseable {
        @Override
        public void close() throws IOException {
            backend.close();
        }
    }
}
