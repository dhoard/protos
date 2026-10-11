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
package com.guillermomolina.protos.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.guillermomolina.protos.execution.ProtosCoreBootstrap;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

final class ProtosFilesystemTreeObservationFlowTest {
    private static final Path CORE = Path.of("protos", "lib", "core");

    @Test
    void invalidPathFailsBeforeBackendSeesAuthority() throws Exception {
        Fixture x = fixture();
        ProtosFutureValue entries =
                x.flow.entries(x.activation, new ProtosStringValue("not-a-path"));
        assertInvalid(x, entries);
        assertEquals(0, x.backend.entriesCalls.get());
    }

    @Test
    void backendReceivesCapturedComponentsInsteadOfGuestPath() throws Exception {
        Fixture x = fixture();
        x.flow.entries(
                x.activation,
                new ProtosPathValue(x.prelude.pathPrototype(), List.of("root", "a/b")));
        assertEquals(
                new ProtosPathComponents(List.of("root", "a/b")), x.backend.lastPath);
    }

    @Test
    void entriesDefensivelySnapshotExactNamesKindsAndPreserveBackendOrder() throws Exception {
        Fixture x = fixture();
        ProtosFutureValue future = x.flow.entries(x.activation, path(x.prelude, "root"));
        EntriesInvocation invocation = x.backend.entriesInvocations.remove();

        java.util.ArrayList<ProtosFilesystemTreeObservationFlow.Entry> supplied =
                new java.util.ArrayList<>(
                        List.of(
                                entry("z", ProtosFilesystemTreeObservationFlow.EntryKind.OTHER),
                                entry("a", ProtosFilesystemTreeObservationFlow.EntryKind.REGULAR)));
        invocation.completion.succeeded(supplied);
        supplied.clear();
        assertEquals(ProtosFutureValue.State.PENDING, future.state());
        dispatch(x);

        assertEquals(ProtosFutureValue.State.RESOLVED, future.state());
        assertSame(x.entriesResult, future.resolvedValue().orElseThrow());
        assertEquals(
                List.of(
                        entry("z", ProtosFilesystemTreeObservationFlow.EntryKind.OTHER),
                        entry("a", ProtosFilesystemTreeObservationFlow.EntryKind.REGULAR)),
                x.materializer.lastEntries);
        assertFalse(future.cancelRequest());
    }

    @Test
    void duplicateBackendEntryNamesFailAsIoError() throws Exception {
        Fixture x = fixture();
        ProtosFutureValue future = x.flow.entries(x.activation, path(x.prelude, "root"));
        EntriesInvocation invocation = x.backend.entriesInvocations.remove();
        invocation.completion.succeeded(
                List.of(
                        entry("same", ProtosFilesystemTreeObservationFlow.EntryKind.REGULAR),
                        entry("same", ProtosFilesystemTreeObservationFlow.EntryKind.DIRECTORY)));
        dispatch(x);
        assertIoError(x, future);
        assertEquals(0, x.materializer.entriesCalls.get());
        assertThrows(
                IllegalArgumentException.class,
                () -> entry(".", ProtosFilesystemTreeObservationFlow.EntryKind.REGULAR));
    }

    @Test
    void cancelledEntriesReachBackendAndLateResultIsNeverMaterialized() throws Exception {
        Fixture x = fixture();
        ProtosFutureValue future = x.flow.entries(x.activation, path(x.prelude, "root"));
        EntriesInvocation invocation = x.backend.entriesInvocations.remove();

        assertTrue(future.cancelRequest());
        assertEquals(1, invocation.cancellations.get());
        invocation.completion.succeeded(
                List.of(entry("late", ProtosFilesystemTreeObservationFlow.EntryKind.REGULAR)));
        assertEquals(ProtosFutureValue.State.CANCELLED, future.state());
        assertEquals(0, x.materializer.entriesCalls.get());
    }

    @Test
    void backendFailureOrThrowBecomesIoErrorAndCallsUseFreshFutures() throws Exception {
        Fixture x = fixture();
        ProtosFutureValue first = x.flow.entries(x.activation, path(x.prelude, "one"));
        ProtosFutureValue second = x.flow.entries(x.activation, path(x.prelude, "two"));
        assertFalse(first == second);
        x.backend.entriesInvocations.remove().completion.failed();
        x.backend.entriesInvocations.remove().completion.failed();
        dispatch(x);
        assertIoError(x, first);
        assertIoError(x, second);

        x.backend.throwEntries = true;
        ProtosFutureValue thrown = x.flow.entries(x.activation, path(x.prelude, "three"));
        assertIoError(x, thrown);
    }

    @Test
    void synchronousCompletionMaterializesInsideTheOriginalCallWithoutQueueing()
            throws Exception {
        Fixture x = fixture();
        RecordingMaterializer materializer = new RecordingMaterializer();
        materializer.entriesResult = x.entriesResult;
        ProtosFilesystemTreeObservationFlow flow =
                new ProtosFilesystemTreeObservationFlow(
                        new ProtosFilesystemValue(),
                        x.activation,
                        (path, completion) -> {
                            completion.succeeded(
                                    List.of(
                                            entry(
                                                    "a",
                                                    ProtosFilesystemTreeObservationFlow.EntryKind
                                                            .REGULAR)));
                            return () -> {};
                        },
                        materializer);

        ProtosFutureValue future = flow.entries(x.activation, path(x.prelude, "root"));

        assertSame(x.entriesResult, future.resolvedValue().orElseThrow());
        assertEquals(List.of(Thread.currentThread()), materializer.threads);
        assertEquals(0, x.activation.executionDomain().runnableCount());
    }

    @Test
    void hostThreadCompletionHandsOverTheInertSnapshotAndBuildsTheArrayInTheOwner()
            throws Exception {
        Fixture x = fixture();
        ProtosFutureValue future = x.flow.entries(x.activation, path(x.prelude, "root"));
        EntriesInvocation invocation = x.backend.entriesInvocations.remove();
        List<ProtosFilesystemTreeObservationFlow.Entry> supplied =
                List.of(
                        entry("z", ProtosFilesystemTreeObservationFlow.EntryKind.DIRECTORY),
                        entry("b", ProtosFilesystemTreeObservationFlow.EntryKind.LINK),
                        entry("a", ProtosFilesystemTreeObservationFlow.EntryKind.REGULAR));

        onHostThread(() -> invocation.completion.succeeded(supplied));

        assertEquals(0, x.materializer.entriesCalls.get(), "no guest Array on the host thread");
        assertEquals(ProtosFutureValue.State.PENDING, future.state());
        assertEquals(1, x.activation.executionDomain().runnableCount());

        dispatch(x);

        assertSame(x.entriesResult, future.resolvedValue().orElseThrow());
        assertEquals(List.of(Thread.currentThread()), x.materializer.threads);
        assertEquals(supplied, x.materializer.lastEntries);
        assertThrows(
                UnsupportedOperationException.class,
                () -> x.materializer.lastEntries.add(supplied.get(0)));
    }

    @Test
    void hostThreadFailureBuildsTheIoErrorOnlyInTheOwner() throws Exception {
        Fixture x = fixture();
        ProtosFutureValue future = x.flow.entries(x.activation, path(x.prelude, "root"));
        EntriesInvocation invocation = x.backend.entriesInvocations.remove();

        onHostThread(invocation.completion::failed);
        assertEquals(ProtosFutureValue.State.PENDING, future.state());

        dispatch(x);
        assertIoError(x, future);
    }

    @Test
    void hostThreadCompletionAfterCancellationIsNeitherQueuedNorMaterialized()
            throws Exception {
        Fixture x = fixture();
        ProtosFutureValue future = x.flow.entries(x.activation, path(x.prelude, "root"));
        EntriesInvocation invocation = x.backend.entriesInvocations.remove();
        assertTrue(future.cancelRequest());

        onHostThread(
                () ->
                        invocation.completion.succeeded(
                                List.of(
                                        entry(
                                                "late",
                                                ProtosFilesystemTreeObservationFlow.EntryKind
                                                        .REGULAR))));

        assertEquals(0, x.activation.executionDomain().runnableCount());
        assertEquals(ProtosFutureValue.State.CANCELLED, future.state());
        assertEquals(0, x.materializer.entriesCalls.get());
    }

    @Test
    void cancellationWinningBeforeOwnerDeliveryLeavesNoArray() throws Exception {
        Fixture x = fixture();
        ProtosFutureValue future = x.flow.entries(x.activation, path(x.prelude, "root"));
        EntriesInvocation invocation = x.backend.entriesInvocations.remove();
        onHostThread(
                () ->
                        invocation.completion.succeeded(
                                List.of(
                                        entry(
                                                "a",
                                                ProtosFilesystemTreeObservationFlow.EntryKind
                                                        .REGULAR))));

        assertTrue(future.cancelRequest());
        dispatch(x);

        assertEquals(ProtosFutureValue.State.CANCELLED, future.state());
        assertEquals(0, x.materializer.entriesCalls.get());
    }

    @Test
    void duplicateHostCompletionMaterializesAndPublishesOnce() throws Exception {
        Fixture x = fixture();
        ProtosFutureValue future = x.flow.entries(x.activation, path(x.prelude, "root"));
        EntriesInvocation invocation = x.backend.entriesInvocations.remove();
        List<ProtosFilesystemTreeObservationFlow.Entry> first =
                List.of(entry("first", ProtosFilesystemTreeObservationFlow.EntryKind.REGULAR));

        onHostThread(() -> invocation.completion.succeeded(first));
        onHostThread(
                () ->
                        invocation.completion.succeeded(
                                List.of(
                                        entry(
                                                "second",
                                                ProtosFilesystemTreeObservationFlow.EntryKind
                                                        .REGULAR))));
        dispatch(x);

        assertSame(x.entriesResult, future.resolvedValue().orElseThrow());
        assertEquals(1, x.materializer.entriesCalls.get());
        assertEquals(first, x.materializer.lastEntries);
    }

    @Test
    void materializerFailureInTheOwnerFailsWithIoError() throws Exception {
        Fixture x = fixture();
        x.materializer.failure = new IllegalStateException("materializer failure");
        ProtosFutureValue future = x.flow.entries(x.activation, path(x.prelude, "root"));
        EntriesInvocation invocation = x.backend.entriesInvocations.remove();

        onHostThread(
                () ->
                        invocation.completion.succeeded(
                                List.of(
                                        entry(
                                                "a",
                                                ProtosFilesystemTreeObservationFlow.EntryKind
                                                        .REGULAR))));
        dispatch(x);

        assertIoError(x, future);
        assertEquals(1, x.materializer.entriesCalls.get());
    }

    @Test
    void actorTerminationBeforeDeliveryCancelsWithoutMaterializing() throws Exception {
        Fixture x = fixture();
        ProtosActor actor =
                new ProtosActor(new ProtosObjectValue(ProtosObjectValue.rootObject()).freeze());
        ProtosActivation activation =
                x.prelude.newModuleActivation(
                        new ProtosActorModuleState(),
                        null,
                        x.prelude.newExecutionContext(),
                        actor.executionDomain());
        ProtosFilesystemTreeObservationFlow flow =
                new ProtosFilesystemTreeObservationFlow(
                        new ProtosFilesystemValue(), activation, x.backend::entries, x.materializer);
        ProtosFutureValue future = flow.entries(activation, path(x.prelude, "root"));
        EntriesInvocation invocation = x.backend.entriesInvocations.remove();
        onHostThread(
                () ->
                        invocation.completion.succeeded(
                                List.of(
                                        entry(
                                                "a",
                                                ProtosFilesystemTreeObservationFlow.EntryKind
                                                        .REGULAR))));
        assertEquals(1, activation.executionDomain().runnableCount());

        assertTrue(actor.beginTermination());
        onHostThread(invocation.completion::failed);
        while (activation.executionDomain().dispatchOne()) {
            // Nothing may remain to deliver.
        }

        assertEquals(ProtosFutureValue.State.CANCELLED, future.state());
        assertEquals(0, x.materializer.entriesCalls.get());
        assertEquals(0, activation.executionDomain().runnableCount());
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
            // Owner-domain delivery of host completions.
        }
    }

    private static Fixture fixture() throws Exception {
        ProtosPrelude prelude = new ProtosCoreBootstrap().bootstrap(CORE);
        ProtosActivation activation = prelude.newModuleActivation();
        ProtosFilesystemValue filesystem = new ProtosFilesystemValue();
        RecordingBackend backend = new RecordingBackend();
        RecordingMaterializer materializer = new RecordingMaterializer();
        ProtosObjectValue entriesResult = new ProtosObjectValue(ProtosObjectValue.rootObject());
        materializer.entriesResult = entriesResult;
        ProtosFilesystemTreeObservationFlow flow =
                new ProtosFilesystemTreeObservationFlow(
                        filesystem,
                        activation,
                        backend::entries,
                        materializer);
        return new Fixture(
                prelude,
                activation,
                filesystem,
                backend,
                materializer,
                entriesResult,
                flow);
    }

    private static ProtosFilesystemTreeObservationFlow.Entry entry(
            String name, ProtosFilesystemTreeObservationFlow.EntryKind kind) {
        return new ProtosFilesystemTreeObservationFlow.Entry(name, kind);
    }

    private static ProtosPathValue path(ProtosPrelude prelude, String name) {
        return new ProtosPathValue(
                prelude.pathPrototype(), List.of(name));
    }

    private static void assertInvalid(Fixture x, ProtosFutureValue future) {
        assertEquals(ProtosFutureValue.State.FAILED, future.state());
        assertSame(
                x.prelude.bindings().readLocalSlot("InvalidIOArgument").orElseThrow(),
                future.failedError().orElseThrow().parent().orElseThrow());
    }

    private static void assertIoError(Fixture x, ProtosFutureValue future) {
        assertEquals(ProtosFutureValue.State.FAILED, future.state());
        assertSame(
                x.prelude.bindings().readLocalSlot("IOError").orElseThrow(),
                future.failedError().orElseThrow().parent().orElseThrow());
    }

    private record Fixture(
            ProtosPrelude prelude,
            ProtosActivation activation,
            ProtosFilesystemValue filesystem,
            RecordingBackend backend,
            RecordingMaterializer materializer,
            ProtosObjectValue entriesResult,
            ProtosFilesystemTreeObservationFlow flow) {}

    private static final class RecordingBackend {
        private final AtomicInteger entriesCalls = new AtomicInteger();
        private final ArrayDeque<EntriesInvocation> entriesInvocations = new ArrayDeque<>();
        private boolean throwEntries;
        private ProtosPathComponents lastPath;

        ProtosFilesystemTreeObservationFlow.Cancellation entries(
                ProtosPathComponents path,
                ProtosFilesystemTreeObservationFlow.EntriesCompletion completion) {
            entriesCalls.incrementAndGet();
            lastPath = path;
            if (throwEntries) {
                throw new IllegalStateException("simulated backend failure");
            }
            EntriesInvocation invocation = new EntriesInvocation(completion);
            entriesInvocations.add(invocation);
            return invocation.cancellations::incrementAndGet;
        }
    }

    private static final class RecordingMaterializer
            implements ProtosFilesystemTreeObservationFlow.ResultMaterializer {
        private final AtomicInteger entriesCalls = new AtomicInteger();
        private final List<Thread> threads = new CopyOnWriteArrayList<>();
        private volatile List<ProtosFilesystemTreeObservationFlow.Entry> lastEntries;
        private volatile ProtosObjectValue entriesResult;
        private volatile RuntimeException failure;

        @Override
        public ProtosObjectValue entries(List<ProtosFilesystemTreeObservationFlow.Entry> entries) {
            entriesCalls.incrementAndGet();
            threads.add(Thread.currentThread());
            lastEntries = entries;
            if (failure != null) {
                throw failure;
            }
            return entriesResult;
        }
    }

    private static final class EntriesInvocation {
        private final ProtosFilesystemTreeObservationFlow.EntriesCompletion completion;
        private final AtomicInteger cancellations = new AtomicInteger();

        private EntriesInvocation(ProtosFilesystemTreeObservationFlow.EntriesCompletion completion) {
            this.completion = completion;
        }
    }
}
