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

import static org.junit.jupiter.api.Assertions.assertSame;

import com.guillermomolina.protos.execution.ProtosCoreBootstrap;
import com.guillermomolina.protos.execution.ProtosExecutionOutcome;
import com.guillermomolina.protos.execution.ProtosRootTaskExecution;
import com.guillermomolina.protos.execution.ProtosSourceCompiler;
import com.guillermomolina.protos.execution.ProtosStandaloneProcessBootstrap;
import com.guillermomolina.protos.execution.ProtosStandardFilesystemProtocol;
import com.guillermomolina.protos.execution.ProtosStandardLibraryModuleResolver;
import com.guillermomolina.protos.runtime.ProtosActivation;
import com.guillermomolina.protos.runtime.ProtosBooleanValue;
import com.guillermomolina.protos.runtime.ProtosBytesValue;
import com.guillermomolina.protos.runtime.ProtosEncodingValue;
import com.guillermomolina.protos.runtime.ProtosEnvironmentValue;
import com.guillermomolina.protos.runtime.ProtosFileFlow;
import com.guillermomolina.protos.runtime.ProtosFilesystemOpenFlow;
import com.guillermomolina.protos.runtime.ProtosFilesystemOpenOptions;
import com.guillermomolina.protos.runtime.ProtosFilesystemValue;
import com.guillermomolina.protos.runtime.ProtosIntegerValue;
import com.guillermomolina.protos.runtime.ProtosObjectValue;
import com.guillermomolina.protos.runtime.ProtosPathComponents;import com.guillermomolina.protos.runtime.ProtosPrelude;
import com.guillermomolina.protos.runtime.ProtosSignalException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * LM006-E end-to-end system/resource maturity.
 *
 * <p>Observable expectations live in ordinary Protos programs. Java only provisions one
 * deterministic Process bootstrap, explicit Filesystem authority, standard byte-stream backends,
 * and capture storage.
 */
final class ProtosSystemResourceEndToEndConformanceTest {
    private static final Path CORE = Path.of("protos", "lib", "core");
    private static final Path STANDARD_LIBRARY = Path.of("protos", "lib");
    private static final Path CASE_ROOT =
            Path.of("protos", "tests", "conformance", "maturity", "system");
    private static final Path TUTORIAL_ROOT =
            Path.of("protos", "tutorials", "12-system-resources");

    @Test
    void bootstrapDataCollectionsFilesystemAndProcessOutputCompose() throws Exception {
        runCase(
                "bootstrap-data-report-roundtrip.protos",
                Map.of(),
                new byte[0]);
    }

    @Test
    void processInputCanFlowThroughWholeFileTextIoBackToProcessOutput() throws Exception {
        runCase(
                "stdin-file-stdout-pipeline.protos",
                Map.of(),
                "stream-data".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void multipleWholeFileFuturesComposeThroughArrayAlgorithmsAndFutureAll() throws Exception {
        runCase(
                "parallel-file-reads-array-reduction.protos",
                Map.of(
                        "first.txt", "left".getBytes(StandardCharsets.UTF_8),
                        "second.txt", "right".getBytes(StandardCharsets.UTF_8)),
                new byte[0]);
    }

    @Test
    void decodingFailureCanRecoverFromProcessDataAndContinueThroughFilesystem() throws Exception {
        runCase(
                "recover-malformed-text-to-fallback-file.protos",
                Map.of("broken.txt", new byte[] {(byte) 0xc3, 0x28}),
                new byte[0]);
    }

    @Test
    void wholeFileByteIoComposesWithMutableBytesEncodingAndProcessTextOutput() throws Exception {
        runCase(
                "byte-copy-mutate-roundtrip.protos",
                Map.of("raw.bin", new byte[] {65, 66, 67}),
                new byte[0]);
    }

    @Test
    void directFileBorrowingTextReaderEnsureAndProcessOutputCompose() throws Exception {
        runCase(
                "manual-file-reader-owned-pipeline.protos",
                Map.of("manual.txt", "manual".getBytes(StandardCharsets.UTF_8)),
                new byte[0]);
    }

    @Test
    void explicitFilesystemLearningMaterialRunsWithProvisionedAuthority() throws Exception {
        runTutorialCase(
                "02-filesystem-text-roundtrip.protos",
                Map.of(),
                new byte[0]);
    }

    private static void runCase(String sourceFile, Map<String, byte[]> initialFiles, byte[] stdin)
            throws Exception {
        runSource(CASE_ROOT.resolve(sourceFile), sourceFile, initialFiles, stdin);
    }

    private static void runTutorialCase(
            String sourceFile, Map<String, byte[]> initialFiles, byte[] stdin)
            throws Exception {
        runSource(TUTORIAL_ROOT.resolve(sourceFile), sourceFile, initialFiles, stdin);
    }

    private static void runSource(
            Path sourcePath,
            String sourceFile,
            Map<String, byte[]> initialFiles,
            byte[] stdin)
            throws Exception {
        ProtosPrelude prelude =
                new ProtosCoreBootstrap()
                        .bootstrap(
                                CORE,
                                new ProtosStandardLibraryModuleResolver(STANDARD_LIBRARY));
        ProtosEncodingValue utf8 =
                (ProtosEncodingValue)
                        prelude.encodingPrototype().readLocalSlot("UTF8").orElseThrow();

        ProtosBytesValue stdoutCapture =
                new ProtosBytesValue(prelude.bytesPrototypeForRuntime());
        ProtosBytesValue stderrCapture =
                new ProtosBytesValue(prelude.bytesPrototypeForRuntime());
        AtomicInteger stdinOffset = new AtomicInteger();

        ProtosStandaloneProcessBootstrap.Result bootstrap =
                ProtosStandaloneProcessBootstrap.create(
                        prelude,
                        List.of("alpha"),
                        exactEnvironmentDomain(),
                        List.of(
                                new ProtosEnvironmentValue.NativeEntry("MODE", "beta"),
                                new ProtosEnvironmentValue.NativeEntry("FALLBACK", "safe")),
                        (maxBytes, completion) -> {
                            int start = stdinOffset.get();
                            if (start >= stdin.length) {
                                completion.eof();
                                return () -> {};
                            }
                            int end = Math.min(stdin.length, start + maxBytes);
                            byte[] delivered = Arrays.copyOfRange(stdin, start, end);
                            stdinOffset.set(end);
                            completion.data(delivered);
                            return () -> {};
                        },
                        (bytes, completion) -> {
                            appendCapturedBytes(stdoutCapture, bytes);
                            completion.succeeded();
                            return () -> {};
                        },
                        (bytes, completion) -> {
                            appendCapturedBytes(stderrCapture, bytes);
                            completion.succeeded();
                            return () -> {};
                        },
                        utf8,
                        utf8,
                        utf8,
                        null);

        ProtosActivation activation = bootstrap.activation();
        EndToEndFilesystemBackend backend = new EndToEndFilesystemBackend(initialFiles);
        ProtosFilesystemValue filesystem =
                (ProtosFilesystemValue)
                        ProtosStandardFilesystemProtocol.createCapability(
                                prelude.bytesPrototypeForRuntime(), activation, backend);

        // Host provisioning happens before this maturity source begins. Process and Filesystem
        // remain separate explicit capabilities; neither is recovered from the other.
        activation.context().createLocalSlot("filesystem", filesystem);
        activation.context().createLocalSlot("stdoutCapture", stdoutCapture);
        activation.context().createLocalSlot("stderrCapture", stderrCapture);

        String source =
                Files.readString(sourcePath, StandardCharsets.UTF_8);
        ProtosExecutionOutcome outcome =
                com.guillermomolina.protos.execution.ProtosTestExecutionSupport.execute(
source,
activation);

        Object result =
                switch (outcome.state()) {
                    case COMPLETED -> outcome.value();
                    case FAILED ->
                            throw new AssertionError(
                                    "LM006-E Protos case failed: " + sourceFile,
                                    new ProtosSignalException(outcome.error()));
                    case CANCELLED ->
                            throw new AssertionError(
                                    "LM006-E Protos case unexpectedly cancelled: " + sourceFile);
                };
        assertSame(ProtosBooleanValue.TRUE, result, sourceFile);
    }

    private static ProtosEnvironmentValue.NativeNameDomain exactEnvironmentDomain() {
        return new ProtosEnvironmentValue.NativeNameDomain() {
            @Override
            public boolean sameCapturedName(String left, String right) {
                return left.equals(right);
            }

            @Override
            public boolean isQueryRepresentable(String name) {
                return !name.contains("=") && name.indexOf('\0') < 0;
            }

            @Override
            public boolean matchesQuery(String captured, String query) {
                return captured.equals(query);
            }
        };
    }

    private static void appendCapturedBytes(ProtosBytesValue capture, byte[] bytes) {
        for (byte value : bytes) {
            capture.indexedAdd(
                    new ProtosIntegerValue(value & 0xff));
        }
    }

    /**
     * Deterministic host-only storage that supports the already-standardized open shapes consumed by
     * these programs: read-existing plus create/truncate positioned whole-file writes.
     */
    private static final class EndToEndFilesystemBackend
            implements ProtosStandardFilesystemProtocol.Backend {
        private final Map<String, SharedFile> files = new LinkedHashMap<>();

        EndToEndFilesystemBackend(Map<String, byte[]> initialFiles) {
            initialFiles.forEach((name, bytes) -> files.put(name, new SharedFile(bytes)));
        }

        @Override
        public ProtosFilesystemOpenFlow.Cancellation open(
                ProtosPathComponents path,
                ProtosFilesystemOpenOptions options,
                ProtosStandardFilesystemProtocol.OpenCompletion completion) {
            String name = directChild(path);
            if (name == null) {
                completion.failed();
                return () -> {};
            }

            SharedFile selected;
            synchronized (files) {
                SharedFile existing = files.get(name);
                boolean createEffect = false;

                switch (options.creation()) {
                    case EXISTING -> {
                        if (existing == null) {
                            completion.failed();
                            return () -> {};
                        }
                    }
                    case CREATE -> {
                        if (existing == null) {
                            createEffect = true;
                        }
                    }
                    case CREATE_NEW -> {
                        if (existing != null) {
                            completion.failed();
                            return () -> {};
                        }
                        createEffect = true;
                    }
                }

                boolean truncateEffect =
                        existing != null
                                && options.truncateInitialContent()
                                && existing.data.length != 0;
                if ((createEffect || truncateEffect)
                        && !completion.commitPortableEffect()) {
                    return () -> {};
                }

                if (createEffect) {
                    existing = new SharedFile(new byte[0]);
                    files.put(name, existing);
                }
                if (options.truncateInitialContent()) {
                    existing.data = new byte[0];
                }
                selected = existing;
            }

            EndToEndResource resource = new EndToEndResource(selected);
            completion.succeeded(
                    resource,
                    new ProtosFileFlow.Capabilities(
                            options.readAccess(),
                            options.writeAccess(),
                            true,
                            true,
                            options.writeAccess(),
                            false),
                    resource::releaseSilently);
            return () -> {};
        }

        private static String directChild(ProtosPathComponents path) {
            return path.components().size() == 1 ? path.components().get(0) : null;
        }
    }

    private static final class SharedFile {
        private byte[] data;

        SharedFile(byte[] bytes) {
            data = bytes.clone();
        }
    }

    private static final class EndToEndResource
            implements ProtosFileFlow.ReadableResource,
                    ProtosFileFlow.WritableResource,
                    ProtosFileFlow.SeekableResource,
                    ProtosFileFlow.SizedResource,
                    ProtosFileFlow.TruncatableResource {
        private final SharedFile shared;
        private boolean closed;

        EndToEndResource(SharedFile shared) {
            this.shared = shared;
        }

        @Override
        public ProtosFileFlow.Cancellation readAt(
                long position,
                int maxBytes,
                ProtosFileFlow.ReadCompletion completion) {
            if (closed) {
                completion.failed();
                return () -> {};
            }
            try {
                int start = Math.toIntExact(position);
                synchronized (shared) {
                    if (start >= shared.data.length) {
                        completion.eof();
                    } else {
                        int end =
                                Math.min(
                                        shared.data.length,
                                        Math.addExact(start, maxBytes));
                        completion.data(Arrays.copyOfRange(shared.data, start, end));
                    }
                }
            } catch (ArithmeticException failure) {
                completion.failed();
            }
            return () -> {};
        }

        @Override
        public ProtosFileFlow.Cancellation writeAt(
                long position,
                byte[] bytes,
                ProtosFileFlow.WriteCompletion completion) {
            if (closed) {
                completion.failed(0);
                return () -> {};
            }
            if (bytes.length == 0) {
                completion.succeeded();
                return () -> {};
            }
            try {
                int start = Math.toIntExact(position);
                int end = Math.addExact(start, bytes.length);
                if (!completion.commitFirstContribution()) {
                    return () -> {};
                }
                synchronized (shared) {
                    if (end > shared.data.length) {
                        shared.data = Arrays.copyOf(shared.data, end);
                    }
                    System.arraycopy(bytes, 0, shared.data, start, bytes.length);
                }
                completion.succeeded();
            } catch (ArithmeticException failure) {
                completion.failed(0);
            }
            return () -> {};
        }

        @Override
        public ProtosFileFlow.Cancellation endPosition(
                ProtosFileFlow.IntegerCompletion completion) {
            if (closed) {
                completion.failed();
            } else {
                synchronized (shared) {
                    completion.succeeded(shared.data.length);
                }
            }
            return () -> {};
        }

        @Override
        public ProtosFileFlow.Cancellation size(
                ProtosFileFlow.IntegerCompletion completion) {
            return endPosition(completion);
        }

        @Override
        public ProtosFileFlow.Cancellation truncate(
                long requestedSize,
                ProtosFileFlow.ChangeCompletion completion) {
            if (closed) {
                completion.failed();
                return () -> {};
            }
            try {
                int size = Math.toIntExact(requestedSize);
                synchronized (shared) {
                    if (size >= shared.data.length) {
                        completion.succeeded();
                        return () -> {};
                    }
                    if (!completion.commitChange()) {
                        return () -> {};
                    }
                    shared.data = Arrays.copyOf(shared.data, size);
                }
                completion.succeeded();
            } catch (ArithmeticException failure) {
                completion.failed();
            }
            return () -> {};
        }

        @Override
        public void close(ProtosFileFlow.CloseCompletion completion) {
            closed = true;
            completion.succeeded();
        }

        void releaseSilently() {
            closed = true;
        }
    }
}
