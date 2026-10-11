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
import com.guillermomolina.protos.execution.ProtosTestExecutionSupport;
import com.guillermomolina.protos.execution.ProtosStandardFilesystemProtocol;
import com.guillermomolina.protos.runtime.ProtosActivation;
import com.guillermomolina.protos.runtime.ProtosBooleanValue;
import com.guillermomolina.protos.runtime.ProtosFileFlow;
import com.guillermomolina.protos.runtime.ProtosFilesystemOpenFlow;
import com.guillermomolina.protos.runtime.ProtosFilesystemOpenOptions;
import com.guillermomolina.protos.runtime.ProtosFilesystemValue;
import com.guillermomolina.protos.runtime.ProtosObjectValue;
import com.guillermomolina.protos.runtime.ProtosPathComponents;import com.guillermomolina.protos.runtime.ProtosPrelude;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** LM006-B language maturity for Path -> Filesystem.open -> positioned File composition. */
final class ProtosFilesystemMaturityConformanceTest {
    private static final Path CORE = Path.of("protos", "lib", "core");
    private static final Path CASE_ROOT =
            Path.of("protos", "tests", "filesystem", "maturity");

    @Test
    void pathOpenReadPositionAndSeekComposeInOrdinaryProtos() throws Exception {
        runCase(
                "path-open-read-position-seek.protos",
                Map.of("alpha.bin", new byte[] {10, 20, 30, 40}));
    }

    @Test
    void independentlyOpenedFilesShareResourceContentButNotLogicalCursor() throws Exception {
        runCase(
                "independent-file-cursors-shared-resource.protos",
                Map.of("shared.bin", new byte[] {7, 8, 9}));
    }

    @Test
    void positionedWriteBeyondEofCreatesPortableZeroGapVisibleThroughAnotherOpen()
            throws Exception {
        runCase(
                "positioned-write-zero-gap-shared-content.protos",
                Map.of("gap.bin", new byte[] {65, 66}));
    }

    @Test
    void truncatePreservesHandleCursorAndIsVisibleThroughAnotherOpen() throws Exception {
        runCase(
                "truncate-preserves-cursor-shares-content.protos",
                Map.of("truncate.bin", new byte[] {1, 2, 3, 4, 5}));
    }

    @Test
    void seekToEndThenPositionedWriteComposesWithoutAppendSemantics() throws Exception {
        runCase(
                "seek-to-end-positioned-write.protos",
                Map.of("end.bin", new byte[] {65, 66, 67}));
    }

    private static void runCase(String source, Map<String, byte[]> initialFiles) throws Exception {
        ProtosPrelude prelude = new ProtosCoreBootstrap().bootstrap(CORE);
        ProtosActivation activation = prelude.newModuleActivation();
        MaturityFilesystemBackend backend = new MaturityFilesystemBackend(initialFiles);
        ProtosObjectValue rawFilesystem =
                ProtosStandardFilesystemProtocol.createCapability(
                        prelude.bytesPrototypeForRuntime(), activation, backend);
        ProtosFilesystemValue filesystem = (ProtosFilesystemValue) rawFilesystem;
        activation.context().createLocalSlot("filesystem", filesystem);

        assertSame(
                ProtosBooleanValue.TRUE,
                ProtosTestExecutionSupport.evaluateFile(CASE_ROOT.resolve(source), activation),
                source);
    }

    /**
     * Host-only deterministic storage for the capability boundary. All expectations, Path creation,
     * opens and File interactions remain in the Protos sources above.
     */
    private static final class MaturityFilesystemBackend
            implements ProtosStandardFilesystemProtocol.Backend {
        private final Map<String, SharedFile> files = new LinkedHashMap<>();

        MaturityFilesystemBackend(Map<String, byte[]> initialFiles) {
            initialFiles.forEach((name, bytes) -> files.put(name, new SharedFile(bytes)));
        }

        @Override
        public ProtosFilesystemOpenFlow.Cancellation open(
                ProtosPathComponents path,
                ProtosFilesystemOpenOptions options,
                ProtosStandardFilesystemProtocol.OpenCompletion completion) {
            String name = directChild(path);
            SharedFile shared = name == null ? null : files.get(name);
            if (shared == null
                    || options.creation() != ProtosFilesystemOpenOptions.Creation.EXISTING
                    || options.truncateInitialContent()) {
                completion.failed();
                return () -> {};
            }

            MaturityResource resource = new MaturityResource(shared);
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

    private static final class MaturityResource
            implements ProtosFileFlow.ReadableResource,
                    ProtosFileFlow.WritableResource,
                    ProtosFileFlow.SeekableResource,
                    ProtosFileFlow.SizedResource,
                    ProtosFileFlow.TruncatableResource {
        private final SharedFile shared;
        private boolean closed;

        MaturityResource(SharedFile shared) {
            this.shared = shared;
        }

        @Override
        public ProtosFileFlow.Cancellation readAt(
                long position, int maxBytes, ProtosFileFlow.ReadCompletion completion) {
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
                        int end = Math.min(shared.data.length, Math.addExact(start, maxBytes));
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
                long position, byte[] bytes, ProtosFileFlow.WriteCompletion completion) {
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
        public ProtosFileFlow.Cancellation endPosition(ProtosFileFlow.IntegerCompletion completion) {
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
        public ProtosFileFlow.Cancellation size(ProtosFileFlow.IntegerCompletion completion) {
            return endPosition(completion);
        }

        @Override
        public ProtosFileFlow.Cancellation truncate(
                long requestedSize, ProtosFileFlow.ChangeCompletion completion) {
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
            if (!closed) {
                closed = true;
            }
            completion.succeeded();
        }

        void releaseSilently() {
            closed = true;
        }
    }
}
