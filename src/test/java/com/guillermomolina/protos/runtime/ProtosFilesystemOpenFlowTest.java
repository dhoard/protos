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

import static org.junit.jupiter.api.Assertions.*;

import com.guillermomolina.protos.execution.ProtosCoreBootstrap;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class ProtosFilesystemOpenFlowTest {
    @Test
    void defaultOpenCapturesPortableDefaultsAndTransfersResultCustody() throws Exception {
        Fixture x = fixture();
        ProtosPathValue path = path(x.prelude);
        ProtosFutureValue future = x.flow.open(x.activation, path);

        assertEquals(1, x.backend.invocations.size());
        Invocation invocation = x.backend.invocations.get(0);
        assertEquals(new ProtosPathComponents(path.components()), invocation.path);
        assertTrue(invocation.options.readAccess());
        assertFalse(invocation.options.writeAccess());
        assertEquals(
                ProtosFilesystemOpenOptions.Creation.EXISTING,
                invocation.options.creation());
        assertFalse(invocation.options.truncateInitialContent());

        ProtosObjectValue file = new ProtosObjectValue(ProtosObjectValue.rootObject());
        AtomicInteger releases = new AtomicInteger();
        invocation.completion.succeeded(file, releases::incrementAndGet);
        assertEquals(ProtosFutureValue.State.PENDING, future.state());
        dispatch(x);

        assertEquals(ProtosFutureValue.State.RESOLVED, future.state());
        assertSame(file, future.resolvedValue().orElseThrow());
        assertEquals(0, releases.get());
    }

    @Test
    void localOptionsAreSnapshottedOnceAndDelegatedSlotsAreIgnored() throws Exception {
        Fixture x = fixture();
        ProtosObjectValue parent = new ProtosObjectValue(ProtosObjectValue.rootObject());
        parent.createLocalSlot("notAStandardOption", ProtosBooleanValue.TRUE);

        ProtosObjectValue options = new ProtosObjectValue(parent);
        options.createLocalSlot("read", ProtosBooleanValue.FALSE);
        options.createLocalSlot("write", ProtosBooleanValue.TRUE);
        options.createLocalSlot("create", ProtosBooleanValue.TRUE);
        ProtosFutureValue future = x.flow.open(x.activation, path(x.prelude), options);

        assertEquals(ProtosFutureValue.State.PENDING, future.state());
        ProtosFilesystemOpenOptions captured = x.backend.invocations.get(0).options;

        options.assignLocalSlot("read", ProtosBooleanValue.TRUE);
        options.assignLocalSlot("write", ProtosBooleanValue.FALSE);
        options.assignLocalSlot("create", ProtosBooleanValue.FALSE);

        assertFalse(captured.readAccess());
        assertTrue(captured.writeAccess());
        assertEquals(ProtosFilesystemOpenOptions.Creation.CREATE, captured.creation());
    }

    @Test
    void invalidOptionsFailBeforeBackendAuthorityIsExercised() throws Exception {
        Fixture x = fixture();
        List<ProtosObjectValue> invalid = new ArrayList<>();

        ProtosObjectValue noAccess = options();
        noAccess.createLocalSlot("read", ProtosBooleanValue.FALSE);
        invalid.add(noAccess);

        // append is not a standard open option; otherwise-valid writable options become invalid.
        ProtosObjectValue removedAppendOption = options();
        removedAppendOption.createLocalSlot("write", ProtosBooleanValue.TRUE);
        removedAppendOption.createLocalSlot("append", ProtosBooleanValue.TRUE);
        invalid.add(removedAppendOption);

        ProtosObjectValue truncateWithoutWrite = options();
        truncateWithoutWrite.createLocalSlot("truncate", ProtosBooleanValue.TRUE);
        invalid.add(truncateWithoutWrite);

        ProtosObjectValue bothCreationModes = options();
        bothCreationModes.createLocalSlot("create", ProtosBooleanValue.TRUE);
        bothCreationModes.createLocalSlot("createNew", ProtosBooleanValue.TRUE);
        invalid.add(bothCreationModes);

        ProtosObjectValue unknownLocalSlot = options();
        unknownLocalSlot.createLocalSlot("mode", ProtosBooleanValue.TRUE);
        invalid.add(unknownLocalSlot);

        ProtosObjectValue nonBoolean = options();
        nonBoolean.createLocalSlot("write", new ProtosIntegerValue(1L));
        invalid.add(nonBoolean);

        for (ProtosObjectValue candidate : invalid) {
            ProtosFutureValue future = x.flow.open(x.activation, path(x.prelude), candidate);
            assertInvalidIoArgument(x.prelude, future);
        }
        assertTrue(x.backend.invocations.isEmpty());

        ProtosFutureValue nonObjectOptions =
                x.flow.open(x.activation, path(x.prelude), ProtosNullValue.INSTANCE);
        assertInvalidIoArgument(x.prelude, nonObjectOptions);
        assertTrue(x.backend.invocations.isEmpty());
    }

    @Test
    void invalidPathFailsBeforeBackendAuthorityIsExercised() throws Exception {
        Fixture x = fixture();
        ProtosFutureValue future =
                x.flow.open(x.activation, new ProtosStringValue("not a Path"));
        assertInvalidIoArgument(x.prelude, future);
        assertTrue(x.backend.invocations.isEmpty());
    }

    @Test
    void backendReceivesOneImmutableLiteralComponentSnapshot() throws Exception {
        Fixture x = fixture();
        List<String> components = List.of("dir", "a/b", "c\\d", "e:f");
        x.flow.open(x.activation, new ProtosPathValue(x.prelude.pathPrototype(), components));

        assertEquals(1, x.backend.invocations.size());
        ProtosPathComponents captured = x.backend.invocations.get(0).path;
        assertEquals(components, captured.components());
        assertThrows(
                UnsupportedOperationException.class, () -> captured.components().add("x"));
    }

    @Test
    void invalidPathWithInvalidOptionsNeverReachesBackend() throws Exception {
        Fixture x = fixture();
        ProtosObjectValue noAccess = options();
        noAccess.createLocalSlot("read", ProtosBooleanValue.FALSE);
        ProtosFutureValue future =
                x.flow.open(x.activation, new ProtosStringValue("not a Path"), noAccess);
        assertInvalidIoArgument(x.prelude, future);
        assertTrue(x.backend.invocations.isEmpty());
    }

    @Test
    void cancellationBeforeCommitWinsAndLateAcquisitionIsReleased() throws Exception {
        Fixture x = fixture();
        ProtosFutureValue future = x.flow.open(x.activation, path(x.prelude));
        Invocation invocation = x.backend.invocations.get(0);

        assertTrue(future.cancelRequest());
        assertEquals(ProtosFutureValue.State.CANCELLED, future.state());
        assertEquals(1, invocation.cancelCount.get());
        assertFalse(invocation.completion.commitPortableEffect());

        AtomicInteger releases = new AtomicInteger();
        invocation.completion.succeeded(
                new ProtosObjectValue(ProtosObjectValue.rootObject()),
                releases::incrementAndGet);
        assertEquals(ProtosFutureValue.State.CANCELLED, future.state());
        assertEquals(1, releases.get());
    }

    @Test
    void portableEffectCommitDefeatsLaterCancellationAndFailureRemainsFailure()
            throws Exception {
        Fixture x = fixture();
        ProtosFutureValue future = x.flow.open(x.activation, path(x.prelude));
        Invocation invocation = x.backend.invocations.get(0);

        assertTrue(invocation.completion.commitPortableEffect());
        assertTrue(future.cancelRequest());
        assertEquals(ProtosFutureValue.State.PENDING, future.state());
        assertEquals(1, invocation.cancelCount.get());

        invocation.completion.failed();
        dispatch(x);
        assertEquals(ProtosFutureValue.State.FAILED, future.state());
        assertSame(
                x.prelude.standardErrorPrototype("IOError"),
                future.failedError().orElseThrow().parent().orElseThrow());
    }

    @Test
    void actorTerminationRequestsOrdinaryPrecommitCancellation() throws Exception {
        Fixture x = fixture();
        ProtosFutureValue future = x.flow.open(x.activation, path(x.prelude));
        Invocation invocation = x.backend.invocations.get(0);

        x.activation.executionDomain().actorTerminated();

        assertEquals(ProtosFutureValue.State.CANCELLED, future.state());
        assertEquals(1, invocation.cancelCount.get());
    }

    @Test
    void independentOpensAreNotFilesystemWideSerialized() throws Exception {
        Fixture x = fixture();
        ProtosFutureValue first = x.flow.open(x.activation, path(x.prelude));
        ProtosFutureValue second = x.flow.open(x.activation, path(x.prelude));

        assertEquals(2, x.backend.invocations.size());
        assertEquals(ProtosFutureValue.State.PENDING, first.state());
        assertEquals(ProtosFutureValue.State.PENDING, second.state());

        ProtosObjectValue firstFile = new ProtosObjectValue(ProtosObjectValue.rootObject());
        ProtosObjectValue secondFile = new ProtosObjectValue(ProtosObjectValue.rootObject());
        x.backend.invocations.get(1).completion.succeeded(secondFile, () -> {});
        x.backend.invocations.get(0).completion.succeeded(firstFile, () -> {});
        dispatch(x);

        assertSame(firstFile, first.resolvedValue().orElseThrow());
        assertSame(secondFile, second.resolvedValue().orElseThrow());
    }

    @Test
    void synchronousAcquisitionBuildsTheFileInsideTheOriginalCallWithoutQueueing()
            throws Exception {
        Fixture x = fixture();
        ProtosObjectValue file = new ProtosObjectValue(ProtosObjectValue.rootObject());
        List<Thread> builders = new CopyOnWriteArrayList<>();
        AtomicInteger releases = new AtomicInteger();
        ProtosFilesystemOpenFlow flow =
                flow(
                        x,
                        (path, options, completion) -> {
                            completion.acquired(
                                    recordingFile(builders, file), releases::incrementAndGet);
                            return () -> {};
                        });

        ProtosFutureValue future = flow.open(x.activation, path(x.prelude));

        assertSame(file, future.resolvedValue().orElseThrow());
        assertEquals(List.of(Thread.currentThread()), builders);
        assertEquals(0, x.activation.executionDomain().runnableCount());
        assertEquals(0, releases.get());
    }

    @Test
    void hostThreadAcquisitionHandsOverCustodyAndBuildsTheFileOnlyInTheOwner()
            throws Exception {
        Fixture x = fixture();
        ProtosFutureValue future = x.flow.open(x.activation, path(x.prelude));
        Invocation invocation = x.backend.invocations.get(0);
        ProtosObjectValue file = new ProtosObjectValue(ProtosObjectValue.rootObject());
        List<Thread> builders = new CopyOnWriteArrayList<>();
        AtomicInteger releases = new AtomicInteger();

        onHostThread(
                () ->
                        invocation.completion.acquired(
                                recordingFile(builders, file), releases::incrementAndGet));

        assertTrue(builders.isEmpty(), "the host callback must not build a guest File");
        assertEquals(ProtosFutureValue.State.PENDING, future.state());
        assertEquals(1, x.activation.executionDomain().runnableCount());

        dispatch(x);

        assertEquals(List.of(Thread.currentThread()), builders);
        assertSame(file, future.resolvedValue().orElseThrow());
        assertEquals(0, releases.get());
    }

    @Test
    void hostThreadFailureBuildsTheIoErrorOnlyInTheOwner() throws Exception {
        Fixture x = fixture();
        ProtosFutureValue future = x.flow.open(x.activation, path(x.prelude));
        Invocation invocation = x.backend.invocations.get(0);

        onHostThread(invocation.completion::failed);
        assertEquals(ProtosFutureValue.State.PENDING, future.state());

        dispatch(x);

        assertEquals(ProtosFutureValue.State.FAILED, future.state());
        assertSame(
                x.prelude.standardErrorPrototype("IOError"),
                future.failedError().orElseThrow().parent().orElseThrow());
    }

    @Test
    void hostThreadAcquisitionAfterCancellationReleasesWithoutBuildingOrQueueing()
            throws Exception {
        Fixture x = fixture();
        ProtosFutureValue future = x.flow.open(x.activation, path(x.prelude));
        Invocation invocation = x.backend.invocations.get(0);
        assertTrue(future.cancelRequest());
        List<Thread> builders = new CopyOnWriteArrayList<>();
        AtomicInteger releases = new AtomicInteger();

        onHostThread(
                () ->
                        invocation.completion.acquired(
                                recordingFile(
                                        builders,
                                        new ProtosObjectValue(ProtosObjectValue.rootObject())),
                                releases::incrementAndGet));

        assertEquals(ProtosFutureValue.State.CANCELLED, future.state());
        assertTrue(builders.isEmpty());
        assertEquals(1, releases.get());
        assertEquals(0, x.activation.executionDomain().runnableCount());
    }

    @Test
    void duplicateHostAcquisitionPublishesOneFileAndReleasesTheOtherExactlyOnce()
            throws Exception {
        Fixture x = fixture();
        ProtosFutureValue future = x.flow.open(x.activation, path(x.prelude));
        Invocation invocation = x.backend.invocations.get(0);
        ProtosObjectValue first = new ProtosObjectValue(ProtosObjectValue.rootObject());
        ProtosObjectValue second = new ProtosObjectValue(ProtosObjectValue.rootObject());
        List<Thread> firstBuilders = new CopyOnWriteArrayList<>();
        List<Thread> secondBuilders = new CopyOnWriteArrayList<>();
        AtomicInteger firstReleases = new AtomicInteger();
        AtomicInteger secondReleases = new AtomicInteger();

        onHostThread(
                () ->
                        invocation.completion.acquired(
                                recordingFile(firstBuilders, first),
                                firstReleases::incrementAndGet));
        onHostThread(
                () ->
                        invocation.completion.acquired(
                                recordingFile(secondBuilders, second),
                                secondReleases::incrementAndGet));
        dispatch(x);

        assertSame(first, future.resolvedValue().orElseThrow());
        assertEquals(1, firstBuilders.size());
        assertTrue(secondBuilders.isEmpty(), "a rejected delivery builds no File");
        assertEquals(0, firstReleases.get());
        assertEquals(1, secondReleases.get());
    }

    @Test
    void lateHostAcquisitionAfterTerminalFailureIsReleasedWithoutBuilding() throws Exception {
        Fixture x = fixture();
        ProtosFutureValue future = x.flow.open(x.activation, path(x.prelude));
        Invocation invocation = x.backend.invocations.get(0);
        onHostThread(invocation.completion::failed);
        dispatch(x);
        assertEquals(ProtosFutureValue.State.FAILED, future.state());
        List<Thread> builders = new CopyOnWriteArrayList<>();
        AtomicInteger releases = new AtomicInteger();

        onHostThread(
                () ->
                        invocation.completion.acquired(
                                recordingFile(
                                        builders,
                                        new ProtosObjectValue(ProtosObjectValue.rootObject())),
                                releases::incrementAndGet));
        dispatch(x);

        assertEquals(ProtosFutureValue.State.FAILED, future.state());
        assertTrue(builders.isEmpty());
        assertEquals(1, releases.get());
    }

    @Test
    void fileConstructionFailureInTheOwnerFailsWithIoErrorAndReleasesOnce() throws Exception {
        Fixture x = fixture();
        ProtosFutureValue future = x.flow.open(x.activation, path(x.prelude));
        Invocation invocation = x.backend.invocations.get(0);
        AtomicInteger releases = new AtomicInteger();

        onHostThread(
                () ->
                        invocation.completion.acquired(
                                () -> {
                                    throw new IllegalArgumentException("invalid backend result");
                                },
                                releases::incrementAndGet));
        assertEquals(0, releases.get());
        dispatch(x);

        assertEquals(ProtosFutureValue.State.FAILED, future.state());
        assertSame(
                x.prelude.standardErrorPrototype("IOError"),
                future.failedError().orElseThrow().parent().orElseThrow());
        assertEquals(1, releases.get());
    }

    @Test
    void actorTerminationBeforeDeliveryReleasesQueuedAndLaterCustodyExactlyOnce()
            throws Exception {
        Fixture x = actorFixture();
        ProtosFutureValue queued = x.flow.open(x.activation, path(x.prelude));
        ProtosFutureValue late = x.flow.open(x.activation, path(x.prelude));
        List<Thread> builders = new CopyOnWriteArrayList<>();
        AtomicInteger queuedReleases = new AtomicInteger();
        AtomicInteger lateReleases = new AtomicInteger();

        onHostThread(
                () ->
                        x.backend.invocations.get(0).completion.acquired(
                                recordingFile(
                                        builders,
                                        new ProtosObjectValue(ProtosObjectValue.rootObject())),
                                queuedReleases::incrementAndGet));
        assertEquals(1, x.activation.executionDomain().runnableCount());

        assertTrue(x.actor.beginTermination());

        assertEquals(1, queuedReleases.get());
        assertEquals(0, x.activation.executionDomain().runnableCount());
        assertEquals(ProtosFutureValue.State.CANCELLED, late.state());

        onHostThread(
                () ->
                        x.backend.invocations.get(1).completion.acquired(
                                recordingFile(
                                        builders,
                                        new ProtosObjectValue(ProtosObjectValue.rootObject())),
                                lateReleases::incrementAndGet));
        dispatch(x);

        assertTrue(builders.isEmpty(), "no File is built for a terminating owner");
        assertTrue(queued.resolvedValue().isEmpty());
        assertEquals(1, queuedReleases.get());
        assertEquals(1, lateReleases.get());
    }

    private static Supplier<ProtosObjectValue> recordingFile(
            List<Thread> builders, ProtosObjectValue file) {
        return () -> {
            builders.add(Thread.currentThread());
            return file;
        };
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

    private static ProtosFilesystemOpenFlow flow(
            Fixture x, ProtosFilesystemOpenFlow.Backend backend) {
        return new ProtosFilesystemOpenFlow(
                new ProtosObjectValue(ProtosObjectValue.rootObject()), x.activation, backend);
    }

    private static void assertInvalidIoArgument(
            ProtosPrelude prelude, ProtosFutureValue future) {
        assertEquals(ProtosFutureValue.State.FAILED, future.state());
        assertSame(
                prelude.standardErrorPrototype("InvalidIOArgument"),
                future.failedError().orElseThrow().parent().orElseThrow());
    }

    private static ProtosObjectValue options() {
        return new ProtosObjectValue(ProtosObjectValue.rootObject());
    }

    private static ProtosPathValue path(ProtosPrelude prelude) {
        return new ProtosPathValue(
                prelude.pathPrototype(), List.of("file.bin"));
    }

    private static Fixture fixture() throws Exception {
        ProtosPrelude prelude =
                new ProtosCoreBootstrap()
                        .bootstrap(java.nio.file.Path.of("protos", "lib", "core"));
        ProtosActivation activation = prelude.newModuleActivation();
        ProtosObjectValue filesystem =
                new ProtosObjectValue(ProtosObjectValue.rootObject());
        RecordingBackend backend = new RecordingBackend();
        ProtosFilesystemOpenFlow flow =
                new ProtosFilesystemOpenFlow(filesystem, activation, backend);
        return new Fixture(prelude, activation, backend, flow, null);
    }

    /** A flow whose owning domain belongs to a real Actor, so termination cuts over. */
    private static Fixture actorFixture() throws Exception {
        ProtosPrelude prelude =
                new ProtosCoreBootstrap()
                        .bootstrap(java.nio.file.Path.of("protos", "lib", "core"));
        ProtosActor actor =
                new ProtosActor(new ProtosObjectValue(ProtosObjectValue.rootObject()).freeze());
        ProtosActivation activation =
                prelude.newModuleActivation(
                        new ProtosActorModuleState(),
                        null,
                        prelude.newExecutionContext(),
                        actor.executionDomain());
        RecordingBackend backend = new RecordingBackend();
        ProtosFilesystemOpenFlow flow =
                new ProtosFilesystemOpenFlow(
                        new ProtosObjectValue(ProtosObjectValue.rootObject()), activation, backend);
        return new Fixture(prelude, activation, backend, flow, actor);
    }

    private record Fixture(
            ProtosPrelude prelude,
            ProtosActivation activation,
            RecordingBackend backend,
            ProtosFilesystemOpenFlow flow,
            ProtosActor actor) {}

    private static final class RecordingBackend
            implements ProtosFilesystemOpenFlow.Backend {
        private final List<Invocation> invocations = new ArrayList<>();

        @Override
        public ProtosFilesystemOpenFlow.Cancellation open(
                ProtosPathComponents path,
                ProtosFilesystemOpenOptions options,
                ProtosFilesystemOpenFlow.OpenCompletion completion) {
            Invocation invocation = new Invocation(path, options, completion);
            invocations.add(invocation);
            return invocation.cancelCount::incrementAndGet;
        }
    }

    private static final class Invocation {
        private final ProtosPathComponents path;
        private final ProtosFilesystemOpenOptions options;
        private final ProtosFilesystemOpenFlow.OpenCompletion completion;
        private final AtomicInteger cancelCount = new AtomicInteger();

        private Invocation(
                ProtosPathComponents path,
                ProtosFilesystemOpenOptions options,
                ProtosFilesystemOpenFlow.OpenCompletion completion) {
            this.path = path;
            this.options = options;
            this.completion = completion;
        }
    }
}
