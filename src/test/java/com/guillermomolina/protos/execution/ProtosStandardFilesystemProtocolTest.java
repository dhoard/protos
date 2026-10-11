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

import com.guillermomolina.protos.runtime.ProtosTestIntegers;
import static org.junit.jupiter.api.Assertions.*;

import com.guillermomolina.protos.runtime.*;
import java.math.BigInteger;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ProtosStandardFilesystemProtocolTest {
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
    void capabilityIsHostProvisionedAndAbsentFromCorePrelude() throws Exception {
        Fixture x = fixture();
        assertTrue(x.prelude.bindings().readLocalSlot("Filesystem").isEmpty());
        assertTrue(x.filesystem instanceof ProtosFilesystemValue);
        assertEquals(
                Set.of("open", "replace", "remove", "entries"),
                x.filesystem.localSlotsSnapshot().keySet());
        // D171: captureTree is no longer a standard Filesystem selector.
        assertFalse(x.filesystem.hasLocalSlot("captureTree"));
        assertTrue(x.filesystem.hasLocalSlot("open"));
        assertTrue(x.filesystem.hasLocalSlot("replace"));
        assertTrue(x.filesystem.hasLocalSlot("remove"));
        assertSame(ProtosObjectValue.rootObject(), x.filesystem.parent().orElseThrow());
    }

    @Test
    void defaultOpenMaterializesExactReadFileCapability() throws Exception {
        Fixture x = fixture();
        ProtosFutureValue future =
                (ProtosFutureValue)
                        ProtosInvocation.invokeMessage(
                                x.filesystem,
                                "open",
                                List.of(path(x.prelude, "data.bin")),
                                x.activation);

        Invocation invocation = x.backend.invocations.remove();
        assertEquals(new ProtosPathComponents(List.of("data.bin")), invocation.path);
        assertTrue(invocation.options.readAccess());
        assertFalse(invocation.options.writeAccess());
        assertEquals(
                ProtosFilesystemOpenOptions.Creation.EXISTING,
                invocation.options.creation());
        assertFalse(invocation.options.truncateInitialContent());

        AtomicInteger releases = new AtomicInteger();
        invocation.completion.succeeded(
                new ReadResource(),
                new ProtosFileFlow.Capabilities(
                        true, false, false, false, false, false),
                releases::incrementAndGet);
        dispatch(x);

        assertEquals(ProtosFutureValue.State.RESOLVED, future.state());
        ProtosObjectValue file =
                (ProtosObjectValue) future.resolvedValue().orElseThrow();
        assertTrue(file instanceof ProtosFileValue);
        assertTrue(file.hasLocalSlot("read"));
        assertTrue(file.hasLocalSlot("close"));
        assertFalse(file.hasLocalSlot("write"));
        assertFalse(file.hasLocalSlot("flush"));
        assertEquals(0, releases.get());
    }

    @Test
    void writableOpenMaterializesPositionedFileWithZeroInitialLogicalPosition() throws Exception {
        Fixture x = fixture();
        ProtosObjectValue options = ordinaryOptions();
        options.createLocalSlot("read", ProtosBooleanValue.FALSE);
        options.createLocalSlot("write", ProtosBooleanValue.TRUE);

        ProtosFutureValue future =
                (ProtosFutureValue)
                        ProtosInvocation.invokeMessage(
                                x.filesystem,
                                "open",
                                List.of(path(x.prelude, "log.bin"), options),
                                x.activation);

        Invocation invocation = x.backend.invocations.remove();
        assertFalse(invocation.options.readAccess());
        assertTrue(invocation.options.writeAccess());

        invocation.completion.succeeded(
                new PositionedWriteSeekResource(),
                new ProtosFileFlow.Capabilities(
                        false, true, true, false, false, false),
                () -> {});
        dispatch(x);

        ProtosObjectValue file =
                (ProtosObjectValue) future.resolvedValue().orElseThrow();
        assertTrue(file.hasLocalSlot("write"));
        assertTrue(file.hasLocalSlot("seekToEnd"));
        assertFalse(file.hasLocalSlot("append"));
        ProtosFutureValue position =
                (ProtosFutureValue)
                        ProtosInvocation.invokeMessage(
                                file, "position", List.of(), x.activation);
        assertEquals(
                BigInteger.ZERO,
                ProtosTestIntegers.exact(position.resolvedValue().orElseThrow()));
    }

    @Test
    void appendIsNotAStandardOpenOption() throws Exception {
        Fixture x = fixture();
        for (ProtosBooleanValue value : List.of(ProtosBooleanValue.TRUE, ProtosBooleanValue.FALSE)) {
            ProtosObjectValue options = ordinaryOptions();
            options.createLocalSlot("write", ProtosBooleanValue.TRUE);
            options.createLocalSlot("append", value);
            ProtosFutureValue future =
                    (ProtosFutureValue)
                            ProtosInvocation.invokeMessage(
                                    x.filesystem,
                                    "open",
                                    List.of(path(x.prelude, "log.bin"), options),
                                    x.activation);
            assertInvalid(x, future);
        }
        assertTrue(x.backend.invocations.isEmpty());
    }

    @Test
    void invalidAndSpecializedOptionsFailBeforeBackendAuthority() throws Exception {
        Fixture x = fixture();

        ProtosObjectValue noAccess = ordinaryOptions();
        noAccess.createLocalSlot("read", ProtosBooleanValue.FALSE);
        ProtosFutureValue invalid =
                (ProtosFutureValue)
                        ProtosInvocation.invokeMessage(
                                x.filesystem,
                                "open",
                                List.of(path(x.prelude, "a"), noAccess),
                                x.activation);
        assertInvalid(x, invalid);

        ProtosBytesValue specialized =
                new ProtosBytesValue(new ProtosObjectValue(ProtosObjectValue.rootObject()));
        ProtosFutureValue nonOrdinary =
                (ProtosFutureValue)
                        ProtosInvocation.invokeMessage(
                                x.filesystem,
                                "open",
                                List.of(path(x.prelude, "b"), specialized),
                                x.activation);
        assertInvalid(x, nonOrdinary);

        assertTrue(x.backend.invocations.isEmpty());
    }

    @Test
    void validNamespaceMutationOnUnsupportedBackendFailsAsIoError() throws Exception {
        Fixture x = fixture();

        ProtosFutureValue replace =
                (ProtosFutureValue)
                        ProtosInvocation.invokeMessage(
                                x.filesystem,
                                "replace",
                                List.of(
                                        path(x.prelude, "source.bin"),
                                        path(x.prelude, "target.bin")),
                                x.activation);
        ProtosFutureValue remove =
                (ProtosFutureValue)
                        ProtosInvocation.invokeMessage(
                                x.filesystem,
                                "remove",
                                List.of(path(x.prelude, "staging.bin")),
                                x.activation);

        assertIoError(x, replace);
        assertIoError(x, remove);
        assertTrue(x.backend.invocations.isEmpty());
    }

    @Test
    void mismatchedBackendAuthorityIsReleasedAndNeverExposedAsFile() throws Exception {
        Fixture x = fixture();
        ProtosFutureValue future =
                (ProtosFutureValue)
                        ProtosInvocation.invokeMessage(
                                x.filesystem,
                                "open",
                                List.of(path(x.prelude, "data.bin")),
                                x.activation);
        Invocation invocation = x.backend.invocations.remove();
        AtomicInteger releases = new AtomicInteger();

        invocation.completion.succeeded(
                new PositionedWriteResource(),
                new ProtosFileFlow.Capabilities(
                        false, true, false, false, false, false),
                releases::incrementAndGet);
        assertEquals(1, releases.get());
        dispatch(x);

        assertEquals(1, releases.get());
        assertEquals(ProtosFutureValue.State.FAILED, future.state());
        assertSame(
                x.prelude.bindings().readLocalSlot("IOError").orElseThrow(),
                future.failedError().orElseThrow().parent().orElseThrow());
    }

    @Test
    void hostThreadOpenCompletionMaterializesTheFileOnlyInTheOwningDomain() throws Exception {
        Fixture x = fixture();
        ProtosFutureValue future =
                (ProtosFutureValue)
                        ProtosInvocation.invokeMessage(
                                x.filesystem,
                                "open",
                                List.of(path(x.prelude, "data.bin")),
                                x.activation);
        Invocation invocation = x.backend.invocations.remove();
        AtomicInteger releases = new AtomicInteger();

        onHostThread(
                () ->
                        invocation.completion.succeeded(
                                new ReadResource(),
                                new ProtosFileFlow.Capabilities(
                                        true, false, false, false, false, false),
                                releases::incrementAndGet));

        assertEquals(ProtosFutureValue.State.PENDING, future.state());
        assertEquals(1, x.activation.executionDomain().runnableCount());

        dispatch(x);

        ProtosObjectValue file = (ProtosObjectValue) future.resolvedValue().orElseThrow();
        assertTrue(file instanceof ProtosFileValue);
        assertTrue(file.hasLocalSlot("read"));
        assertEquals(0, releases.get());
    }

    @Test
    void hostThreadEntriesCompletionMaterializesTheOrderedDescriptorArrayInTheOwner()
            throws Exception {
        Fixture x = fixture();
        ProtosFutureValue future =
                (ProtosFutureValue)
                        ProtosInvocation.invokeMessage(
                                x.filesystem,
                                "entries",
                                List.of(path(x.prelude, "root")),
                                x.activation);
        ProtosFilesystemTreeObservationFlow.EntriesCompletion completion =
                x.backend.entriesCompletions.remove();

        onHostThread(
                () ->
                        completion.succeeded(
                                List.of(
                                        new ProtosFilesystemTreeObservationFlow.Entry(
                                                "z",
                                                ProtosFilesystemTreeObservationFlow.EntryKind
                                                        .DIRECTORY),
                                        new ProtosFilesystemTreeObservationFlow.Entry(
                                                "a",
                                                ProtosFilesystemTreeObservationFlow.EntryKind
                                                        .REGULAR))));

        assertEquals(ProtosFutureValue.State.PENDING, future.state());
        dispatch(x);

        ProtosArrayValue entries = (ProtosArrayValue) future.resolvedValue().orElseThrow();
        assertSame(x.prelude.arrayPrototype(), entries.parent().orElseThrow());
        List<Object> descriptors = entries.indexedSnapshot();
        assertEquals(2, descriptors.size());
        assertDescriptor(descriptors.get(0), "z", "directory");
        assertDescriptor(descriptors.get(1), "a", "regular");
    }

    private static void assertDescriptor(Object value, String name, String kind) {
        ProtosObjectValue descriptor = (ProtosObjectValue) value;
        assertTrue(descriptor.isFrozen());
        assertEquals(Set.of("name", "kind"), descriptor.localSlotsSnapshot().keySet());
        assertEquals(
                name,
                ((ProtosStringValue) descriptor.readLocalSlot("name").orElseThrow()).value());
        assertEquals(
                kind,
                ((ProtosStringValue) descriptor.readLocalSlot("kind").orElseThrow()).value());
    }

    /** Runs one backend callback on a distinct host thread and joins it deterministically. */
    private static void onHostThread(Runnable callback) throws InterruptedException {
        Throwable[] failure = new Throwable[1];
        Thread host =
                new Thread(
                        () -> {
                            try {
                                callback.run();
                            } catch (Throwable t) {
                                failure[0] = t;
                            }
                        },
                        "filesystem-host-callback");
        host.start();
        host.join();
        assertNull(failure[0], "host callback failed");
    }

    private static void dispatch(Fixture x) {
        while (x.activation.executionDomain().dispatchOne()) {
            // Owner-domain delivery of backend completions outside the original call.
        }
    }

    private static void assertIoError(Fixture x, ProtosFutureValue future) {
        assertEquals(ProtosFutureValue.State.FAILED, future.state());
        assertSame(
                x.prelude.bindings().readLocalSlot("IOError").orElseThrow(),
                future.failedError().orElseThrow().parent().orElseThrow());
    }

    private static void assertInvalid(Fixture x, ProtosFutureValue future) {
        assertEquals(ProtosFutureValue.State.FAILED, future.state());
        assertSame(
                x.prelude.bindings().readLocalSlot("InvalidIOArgument").orElseThrow(),
                future.failedError().orElseThrow().parent().orElseThrow());
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
        private final ArrayDeque<ProtosFilesystemTreeObservationFlow.EntriesCompletion>
                entriesCompletions = new ArrayDeque<>();

        @Override
        public ProtosFilesystemOpenFlow.Cancellation open(
                ProtosPathComponents path,
                ProtosFilesystemOpenOptions options,
                ProtosStandardFilesystemProtocol.OpenCompletion completion) {
            Invocation invocation = new Invocation(path, options, completion);
            invocations.add(invocation);
            return invocation.cancellations::incrementAndGet;
        }

        @Override
        public ProtosFilesystemTreeObservationFlow.Cancellation entries(
                ProtosPathComponents path,
                ProtosFilesystemTreeObservationFlow.EntriesCompletion completion) {
            entriesCompletions.add(completion);
            return () -> {};
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

    private static final class ReadResource implements ProtosFileFlow.ReadableResource {
        @Override
        public ProtosFileFlow.Cancellation readAt(
                long position, int maxBytes, ProtosFileFlow.ReadCompletion completion) {
            completion.eof();
            return () -> {};
        }

        @Override
        public void close(ProtosFileFlow.CloseCompletion completion) {
            completion.succeeded();
        }
    }

    private static final class PositionedWriteResource
            implements ProtosFileFlow.WritableResource {
        @Override
        public ProtosFileFlow.Cancellation writeAt(
                long position, byte[] bytes, ProtosFileFlow.WriteCompletion completion) {
            return () -> {};
        }

        @Override
        public void close(ProtosFileFlow.CloseCompletion completion) {
            completion.succeeded();
        }
    }

    private static final class PositionedWriteSeekResource
            implements ProtosFileFlow.WritableResource, ProtosFileFlow.SeekableResource {
        @Override
        public ProtosFileFlow.Cancellation writeAt(
                long position, byte[] bytes, ProtosFileFlow.WriteCompletion completion) {
            return () -> {};
        }

        @Override
        public ProtosFileFlow.Cancellation endPosition(
                ProtosFileFlow.IntegerCompletion completion) {
            completion.succeeded(17);
            return () -> {};
        }

        @Override
        public void close(ProtosFileFlow.CloseCompletion completion) {
            completion.succeeded();
        }
    }
}
