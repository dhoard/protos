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
import static org.junit.jupiter.api.Assertions.assertSame;

import com.guillermomolina.protos.execution.ProtosCoreBootstrap;
import com.guillermomolina.protos.execution.ProtosExecutionOutcome;
import com.guillermomolina.protos.execution.ProtosRootTaskExecution;
import com.guillermomolina.protos.execution.ProtosSourceCompiler;
import com.guillermomolina.protos.execution.ProtosStandardFilesystemProtocol;
import com.guillermomolina.protos.runtime.ProtosActivation;
import com.guillermomolina.protos.runtime.ProtosBooleanValue;
import com.guillermomolina.protos.runtime.ProtosFileFlow;
import com.guillermomolina.protos.runtime.ProtosFilesystemOpenFlow;
import com.guillermomolina.protos.runtime.ProtosFilesystemOpenOptions;
import com.guillermomolina.protos.runtime.ProtosFilesystemValue;
import com.guillermomolina.protos.runtime.ProtosObjectValue;
import com.guillermomolina.protos.runtime.ProtosPathComponents;import com.guillermomolina.protos.runtime.ProtosPrelude;
import com.guillermomolina.protos.runtime.ProtosSignalException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

/** LM006-D maturity for File/text/Future ownership, ensure cleanup and failure precedence. */
final class ProtosResourceLifetimeMaturityConformanceTest {
    private static final Path CORE = Path.of("protos", "lib", "core");
    private static final Path CASE_ROOT =
            Path.of("protos", "tests", "conformance", "maturity", "resource");

    @Test
    void normalResultSurvivesOwnedFileCleanup() throws Exception {
        Fixture fixture = runCase("normal-result-owned-file-close.protos", new byte[] {65}, CloseMode.SUCCESS);
        assertEquals(1, fixture.resource().closeStarts());
    }

    @Test
    void bodyErrorIdentitySurvivesSuccessfulOwnedFileCleanup() throws Exception {
        Fixture fixture = runCase("body-error-survives-successful-close.protos", new byte[] {65}, CloseMode.SUCCESS);
        assertEquals(1, fixture.resource().closeStarts());
    }

    @Test
    void laterFileCloseErrorSupersedesAlreadySelectedBodyError() throws Exception {
        Fixture fixture = runCase("close-error-supersedes-body-error.protos", new byte[] {65}, CloseMode.FAIL);
        assertEquals(1, fixture.resource().closeStarts());
    }

    @Test
    void borrowingTextReaderFailureStillLeavesOwnedFileCleanupToOuterEnsure() throws Exception {
        Fixture fixture = runCase(
                "textreader-borrowing-error-owned-file-close.protos",
                new byte[] {(byte) 0xc3, 0x28},
                CloseMode.SUCCESS);
        assertEquals(1, fixture.resource().closeStarts());
    }

    @Test
    void futureRecordsAndResignalsLaterCloseErrorInsteadOfEarlierEncodingError() throws Exception {
        Fixture fixture = runCase(
                "future-records-later-close-error.protos",
                new byte[] {(byte) 0xc3, 0x28},
                CloseMode.FAIL);
        assertEquals(1, fixture.resource().closeStarts());
    }

    private static Fixture runCase(String sourceName, byte[] content, CloseMode closeMode)
            throws Exception {
        ProtosPrelude prelude = new ProtosCoreBootstrap().bootstrap(CORE);
        ProtosActivation activation = prelude.newModuleActivation();
        LifetimeResource resource = new LifetimeResource(content, closeMode);
        LifetimeFilesystemBackend backend = new LifetimeFilesystemBackend(resource);
        ProtosObjectValue rawFilesystem =
                ProtosStandardFilesystemProtocol.createCapability(
                        prelude.bytesPrototypeForRuntime(), activation, backend);
        activation.context().createLocalSlot("filesystem", (ProtosFilesystemValue) rawFilesystem);

        String source = Files.readString(CASE_ROOT.resolve(sourceName), StandardCharsets.UTF_8);
        ProtosExecutionOutcome outcome =
                com.guillermomolina.protos.execution.ProtosTestExecutionSupport.execute(
source,
activation);
        Object result = switch (outcome.state()) {
            case COMPLETED -> outcome.value();
            case FAILED -> throw new AssertionError(
                    "LM006-D Protos case failed: " + sourceName,
                    new ProtosSignalException(outcome.error()));
            case CANCELLED -> throw new AssertionError(
                    "LM006-D Protos case unexpectedly cancelled: " + sourceName);
        };
        assertSame(ProtosBooleanValue.TRUE, result, sourceName);
        return new Fixture(resource);
    }

    private enum CloseMode {
        SUCCESS,
        FAIL
    }

    private record Fixture(LifetimeResource resource) {}

    /** Host-only deterministic resource. Observable expectations remain in the Protos cases. */
    private static final class LifetimeFilesystemBackend
            implements ProtosStandardFilesystemProtocol.Backend {
        private final LifetimeResource resource;

        LifetimeFilesystemBackend(LifetimeResource resource) {
            this.resource = resource;
        }

        @Override
        public ProtosFilesystemOpenFlow.Cancellation open(
                ProtosPathComponents path,
                ProtosFilesystemOpenOptions options,
                ProtosStandardFilesystemProtocol.OpenCompletion completion) {
            if (!isCasePath(path)
                    || !options.readAccess()
                    || options.writeAccess()
                    || options.creation() != ProtosFilesystemOpenOptions.Creation.EXISTING
                    || options.truncateInitialContent()) {
                completion.failed();
                return () -> {};
            }
            completion.succeeded(
                    resource,
                    new ProtosFileFlow.Capabilities(true, false, false, false, false, false),
                    resource::releaseSilently);
            return () -> {};
        }

        private static boolean isCasePath(ProtosPathComponents path) {
            return path.components().equals(java.util.List.of("case.bin"));
        }
    }

    private static final class LifetimeResource implements ProtosFileFlow.ReadableResource {
        private final byte[] content;
        private final CloseMode closeMode;
        private int closeStarts;
        private boolean closed;

        LifetimeResource(byte[] content, CloseMode closeMode) {
            this.content = content.clone();
            this.closeMode = closeMode;
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
                if (start >= content.length) {
                    completion.eof();
                } else {
                    int end = Math.min(content.length, Math.addExact(start, maxBytes));
                    completion.data(Arrays.copyOfRange(content, start, end));
                }
            } catch (ArithmeticException failure) {
                completion.failed();
            }
            return () -> {};
        }

        @Override
        public void close(ProtosFileFlow.CloseCompletion completion) {
            closeStarts++;
            closed = true;
            if (closeMode == CloseMode.FAIL) {
                completion.failed();
            } else {
                completion.succeeded();
            }
        }

        int closeStarts() {
            return closeStarts;
        }

        void releaseSilently() {
            closed = true;
        }
    }
}
