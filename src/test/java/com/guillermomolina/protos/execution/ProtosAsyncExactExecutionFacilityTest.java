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

import static org.junit.jupiter.api.Assertions.*;

import com.guillermomolina.protos.runtime.ProtosActivation;
import com.guillermomolina.protos.runtime.ProtosActorExecutionDomain;
import com.guillermomolina.protos.runtime.ProtosBytesValue;
import com.guillermomolina.protos.runtime.ProtosFutureValue;
import com.guillermomolina.protos.runtime.ProtosIntegerValue;
import com.guillermomolina.protos.runtime.ProtosObjectValue;
import com.guillermomolina.protos.runtime.ProtosPrelude;
import com.guillermomolina.protos.runtime.ProtosStringValue;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

final class ProtosAsyncExactExecutionFacilityTest {
    private static final Path CORE = Path.of("protos", "lib", "core");
    private static final Path STANDARD_LIBRARY = Path.of("protos", "lib");

    @SuppressWarnings("try")
    @Test
    void hostCompletionRemainsInertUntilCallerDomainDispatch() throws Exception {
        ManualSubmission submission = new ManualSubmission();
        try (Fixture fixture = fixture();
                ProtosAsyncExactExecutionFacility facility =
                        ProtosAsyncExactExecutionFacility.install(
                                fixture.activation,
                                fixture.runtimeHost,
                                submission)) {
            ProtosFutureValue future =
                    invoke(
                            fixture,
                            exactSource("marshal-only", 42));

            assertEquals(ProtosFutureValue.State.PENDING, future.state());
            assertEquals(1, submission.queuedCount());

            assertTrue(submission.runNext());
            assertEquals(
                    ProtosFutureValue.State.PENDING,
                    future.state(),
                    "host completion must not rematerialize or resolve the caller Future");
            assertEquals(
                    1,
                    fixture.activation.executionDomain().runnableCount(),
                    "captured host evidence must be represented by a caller-domain completion task");

            assertTrue(fixture.activation.executionDomain().dispatchOne());
            assertCompletedObservation(future, 42, "marshal-only\n");
        }
    }

    @SuppressWarnings("try")
    @Test
    void cancellationBeforeHostStartWithdrawsWorkAndTerminalizesFuture() throws Exception {
        ManualSubmission submission = new ManualSubmission();
        try (Fixture fixture = fixture();
                ProtosAsyncExactExecutionFacility facility =
                        ProtosAsyncExactExecutionFacility.install(
                                fixture.activation,
                                fixture.runtimeHost,
                                submission)) {
            ProtosFutureValue future =
                    invoke(
                            fixture,
                            exactSource("must-not-run", 7));

            assertTrue(future.cancelRequest());
            assertEquals(ProtosFutureValue.State.CANCELLED, future.state());
            assertEquals(1, submission.cancelledCount());
            assertFalse(submission.runNext(), "withdrawn host work must not execute");
        }
    }

    @SuppressWarnings("try")
    @Test
    void twoAcceptedOperationsCanProgressIndependentlyWithPrivateOutput() throws Exception {
        try (ConcurrentSubmission submission = new ConcurrentSubmission(2);
                Fixture fixture = fixture();
                ProtosAsyncExactExecutionFacility facility =
                        ProtosAsyncExactExecutionFacility.install(
                                fixture.activation,
                                fixture.runtimeHost,
                                submission)) {
            ProtosFutureValue first =
                    invoke(
                            fixture,
                            exactSource("first-async", 41));
            ProtosFutureValue second =
                    invoke(
                            fixture,
                            exactSource("second-async", 42));

            try {
                assertTrue(
                        submission.awaitStarted(30, TimeUnit.SECONDS),
                        "both host submissions must be admitted without waiting for the other result");
                assertEquals(ProtosFutureValue.State.PENDING, first.state());
                assertEquals(ProtosFutureValue.State.PENDING, second.state());
            } finally {
                submission.release();
            }
            dispatchUntilTerminal(first, fixture.activation.executionDomain());
            dispatchUntilTerminal(second, fixture.activation.executionDomain());

            assertCompletedObservation(first, 41, "first-async\n");
            assertCompletedObservation(second, 42, "second-async\n");
        }
    }

    @Test
    void closeRetainsCustodyUntilAcceptedHostExecutionSettles() throws Exception {
        ManualSubmission submission = new ManualSubmission();
        Fixture fixture = fixture();
        ProtosAsyncExactExecutionFacility facility =
                ProtosAsyncExactExecutionFacility.install(
                        fixture.activation,
                        fixture.runtimeHost,
                        submission);
        ExecutorService closer = Executors.newSingleThreadExecutor();
        try {
            ProtosFutureValue future =
                    invoke(
                            fixture,
                            exactSource("custody", 9));
            CountDownLatch closeEntered = new CountDownLatch(1);
            java.util.concurrent.Future<?> closed =
                    closer.submit(
                            () -> {
                                closeEntered.countDown();
                                facility.close();
                            });
            assertTrue(closeEntered.await(5, TimeUnit.SECONDS));
            assertThrows(
                    TimeoutException.class,
                    () -> closed.get(150, TimeUnit.MILLISECONDS),
                    "close must retain custody while accepted host work is still outstanding");

            assertTrue(submission.runNext());
            closed.get(30, TimeUnit.SECONDS);

            assertEquals(
                    ProtosFutureValue.State.PENDING,
                    future.state(),
                    "host custody may finish after completion is enqueued but before caller dispatch");
            dispatchUntilTerminal(future, fixture.activation.executionDomain());
            assertCompletedObservation(future, 9, "custody\n");
        } finally {
            facility.close();
            closer.shutdownNow();
            fixture.close();
        }
    }


    @SuppressWarnings("try")
    @Test
    void inspectionAsyncPreservesLiveSourceCompositionAndCallerDomainMaterialization()
            throws Exception {
        ManualSubmission submission = new ManualSubmission();
        try (Fixture fixture = fixture();
                ProtosAsyncExactExecutionFacility facility =
                        ProtosAsyncExactExecutionFacility.installInspection(
                                fixture.activation,
                                fixture.runtimeHost,
                                submission)) {
            ProtosFutureValue future =
                    invokeInspection(
                            fixture,
                            "future: (() => { 42 }).future()\n"
                                    + "() => { future.value() }",
                            "(subject) => { subject() }");

            assertEquals(ProtosFutureValue.State.PENDING, future.state());
            assertTrue(submission.runNext());
            assertEquals(
                    ProtosFutureValue.State.PENDING,
                    future.state(),
                    "inspection host completion must remain inert before caller dispatch");
            assertEquals(1, fixture.activation.executionDomain().runnableCount());

            assertTrue(fixture.activation.executionDomain().dispatchOne());
            assertCompletedObservation(future, 42, "");
        }
    }

    @SuppressWarnings("try")
    @Test
    void cancellationAfterCapturedHostCompletionDiscardsLateCallerResult()
            throws Exception {
        ManualSubmission submission = new ManualSubmission();
        try (Fixture fixture = fixture();
                ProtosAsyncExactExecutionFacility facility =
                        ProtosAsyncExactExecutionFacility.install(
                                fixture.activation,
                                fixture.runtimeHost,
                                submission)) {
            ProtosFutureValue future =
                    invoke(
                            fixture,
                            exactSource("late-result", 77));

            assertTrue(submission.runNext());
            assertEquals(ProtosFutureValue.State.PENDING, future.state());
            assertEquals(
                    1,
                    fixture.activation.executionDomain().runnableCount(),
                    "completed host evidence must already be waiting in the caller domain");

            assertTrue(future.cancelRequest());
            assertEquals(ProtosFutureValue.State.CANCELLED, future.state());

            assertTrue(fixture.activation.executionDomain().dispatchOne());
            assertEquals(
                    ProtosFutureValue.State.CANCELLED,
                    future.state(),
                    "caller completion must not resurrect a cancelled Future");
            assertTrue(future.resolvedValue().isEmpty());
        }
    }

    @Test
    void actorTerminationCancelsStartedSubmissionWhileFacilityKeepsCustody()
            throws Exception {
        try (StartedSubmission submission = new StartedSubmission();
                ProtosPolyglotRuntimeHost runtimeHost = ProtosPolyglotRuntimeHost.open()) {
            ProtosPrelude prelude =
                    new ProtosCoreBootstrap()
                            .bootstrap(
                                    CORE,
                                    new ProtosStandardLibraryModuleResolver(
                                            STANDARD_LIBRARY));
            com.guillermomolina.protos.runtime.ProtosActor actor =
                    new com.guillermomolina.protos.runtime.ProtosActor(
                            prelude.actorRefPrototypeForRuntime());
            assertTrue(actor.markReady());
            ProtosActivation activation =
                    prelude.newModuleActivation(
                            actor.moduleState(),
                            null,
                            prelude.newExecutionContext(),
                            actor.executionDomain());
            Fixture fixture = new Fixture(prelude, activation, runtimeHost);
            ProtosAsyncExactExecutionFacility facility =
                    ProtosAsyncExactExecutionFacility.install(
                            activation,
                            runtimeHost,
                            submission);
            ExecutorService closer = Executors.newSingleThreadExecutor();
            try {
                ProtosFutureValue future =
                        invoke(
                                fixture,
                                exactSource("actor-termination", 13));

                assertTrue(
                        submission.awaitStarted(30, TimeUnit.SECONDS),
                        "host submission must be started before Actor termination");
                assertTrue(actor.beginTermination());
                assertEquals(ProtosFutureValue.State.CANCELLED, future.state());
                assertTrue(
                        actor.markTerminated(),
                        "ordinary non-task Future cancellation must release Actor termination");

                java.util.concurrent.Future<?> closed =
                        closer.submit(facility::close);
                assertThrows(
                        TimeoutException.class,
                        () -> closed.get(150, TimeUnit.MILLISECONDS),
                        "Actor/Future termination must not release accepted host-work custody");

                submission.release();
                closed.get(30, TimeUnit.SECONDS);
                assertEquals(ProtosFutureValue.State.CANCELLED, future.state());
            } finally {
                submission.release();
                facility.close();
                closer.shutdownNow();
            }
        }
    }


    private static ProtosFutureValue invokeInspection(
            Fixture fixture,
            String source,
            String inspector) {
        Object execution =
                fixture.activation
                        .context()
                        .readLocalSlot(
                                ProtosAsyncExactExecutionFacility.INSPECTION_BOOTSTRAP_SLOT)
                        .orElseThrow();
        return assertInstanceOf(
                ProtosFutureValue.class,
                ProtosInvocation.invoke(
                        execution,
                        List.of(
                                new ProtosStringValue(source),
                                new ProtosStringValue(inspector)),
                        fixture.activation));
    }

    private static ProtosFutureValue invoke(
            Fixture fixture,
            String source) {
        Object execution =
                fixture.activation
                        .context()
                        .readLocalSlot(
                                ProtosAsyncExactExecutionFacility.BOOTSTRAP_SLOT)
                        .orElseThrow();
        return assertInstanceOf(
                ProtosFutureValue.class,
                ProtosInvocation.invoke(
                        execution,
                        List.of(new ProtosStringValue(source)),
                        fixture.activation));
    }

    private static String exactSource(String output, int result) {
        return "writer: TextWriter(process.stdout(), process.stdoutEncoding())\n"
                + "writer.writeLine(\""
                + output
                + "\").value()\n"
                + result;
    }

    private static void dispatchUntilTerminal(
            ProtosFutureValue future,
            ProtosActorExecutionDomain domain)
            throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (future.isPending()) {
            if (!domain.dispatchOne()) {
                if (System.nanoTime() >= deadline) {
                    fail("caller Future did not become terminal");
                }
                Thread.sleep(1);
            }
        }
    }

    private static void assertCompletedObservation(
            ProtosFutureValue future,
            long expectedValue,
            String expectedStdout) {
        assertEquals(ProtosFutureValue.State.RESOLVED, future.state());
        ProtosObjectValue observation =
                assertInstanceOf(
                        ProtosObjectValue.class,
                        future.resolvedValue().orElseThrow());
        ProtosStringValue state =
                assertInstanceOf(
                        ProtosStringValue.class,
                        observation.readLocalSlot("state").orElseThrow());
        assertEquals("completed", state.value());

        ProtosIntegerValue value =
                assertInstanceOf(
                        ProtosIntegerValue.class,
                        observation.readLocalSlot("value").orElseThrow());
        assertEquals(Long.toString(expectedValue), com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerDecimalText(value));
        assertEquals(
                expectedStdout,
                bytesText(
                        assertInstanceOf(
                                ProtosBytesValue.class,
                                observation.readLocalSlot("stdout").orElseThrow())));
        assertEquals(
                "",
                bytesText(
                        assertInstanceOf(
                                ProtosBytesValue.class,
                                observation.readLocalSlot("stderr").orElseThrow())));
    }

    private static String bytesText(ProtosBytesValue bytes) {
        byte[] raw = new byte[bytes.indexedSize()];
        for (int index = 0; index < raw.length; index++) {
            ProtosIntegerValue octet =
                    assertInstanceOf(
                            ProtosIntegerValue.class,
                            bytes.indexedAt(index));
            raw[index] = (byte) Integer.parseInt(com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerDecimalText(octet));
        }
        return new String(raw, StandardCharsets.UTF_8);
    }

    private static Fixture fixture() throws Exception {
        ProtosPrelude prelude =
                new ProtosCoreBootstrap()
                        .bootstrap(
                                CORE,
                                new ProtosStandardLibraryModuleResolver(
                                        STANDARD_LIBRARY));
        return new Fixture(
                prelude,
                prelude.newModuleActivation(),
                ProtosPolyglotRuntimeHost.open());
    }

    private record Fixture(
            ProtosPrelude prelude,
            ProtosActivation activation,
            ProtosPolyglotRuntimeHost runtimeHost)
            implements AutoCloseable {
        @Override
        public void close() {
            runtimeHost.close();
        }
    }

    private static final class ManualSubmission
            implements ProtosAsyncExactExecutionFacility.Submission {
        private final ArrayDeque<Job> jobs = new ArrayDeque<>();
        private int cancelledCount;

        @Override
        public synchronized ProtosAsyncExactExecutionFacility.Submitted submit(
                Runnable work) {
            Job job = new Job(Objects.requireNonNull(work, "work"));
            jobs.addLast(job);
            return job;
        }

        synchronized int queuedCount() {
            return jobs.size();
        }

        synchronized int cancelledCount() {
            return cancelledCount;
        }

        boolean runNext() {
            Job job;
            synchronized (this) {
                job = jobs.pollFirst();
            }
            return job != null && job.runIfAccepted();
        }

        private final class Job
                implements ProtosAsyncExactExecutionFacility.Submitted {
            private final Runnable work;
            private boolean started;
            private boolean cancelled;

            private Job(Runnable work) {
                this.work = work;
            }

            @Override
            public boolean cancelIfNotStarted() {
                synchronized (this) {
                    if (started || cancelled) {
                        return false;
                    }
                    cancelled = true;
                }
                synchronized (ManualSubmission.this) {
                    cancelledCount++;
                }
                return true;
            }

            boolean runIfAccepted() {
                synchronized (this) {
                    if (cancelled || started) {
                        return false;
                    }
                    started = true;
                }
                work.run();
                return true;
            }
        }
    }


    private static final class StartedSubmission
            implements ProtosAsyncExactExecutionFacility.Submission, AutoCloseable {
        private final ExecutorService executor = Executors.newSingleThreadExecutor();
        private final CountDownLatch started = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);

        @Override
        public ProtosAsyncExactExecutionFacility.Submitted submit(
                Runnable work) {
            Job job = new Job(Objects.requireNonNull(work, "work"));
            executor.submit(job);
            return job;
        }

        boolean awaitStarted(long timeout, TimeUnit unit)
                throws InterruptedException {
            return started.await(timeout, unit);
        }

        void release() {
            release.countDown();
        }

        @Override
        public void close() {
            release();
            executor.shutdownNow();
        }

        private final class Job
                implements Runnable, ProtosAsyncExactExecutionFacility.Submitted {
            private static final int QUEUED = 0;
            private static final int RUNNING = 1;
            private static final int CANCELLED = 2;
            private static final int DONE = 3;

            private final Runnable work;
            private final AtomicInteger state = new AtomicInteger(QUEUED);

            private Job(Runnable work) {
                this.work = work;
            }

            @Override
            public void run() {
                if (!state.compareAndSet(QUEUED, RUNNING)) {
                    return;
                }
                started.countDown();
                try {
                    release.await();
                    work.run();
                } catch (InterruptedException interruption) {
                    Thread.currentThread().interrupt();
                } finally {
                    state.compareAndSet(RUNNING, DONE);
                }
            }

            @Override
            public boolean cancelIfNotStarted() {
                return state.compareAndSet(QUEUED, CANCELLED);
            }
        }
    }

    private static final class ConcurrentSubmission
            implements ProtosAsyncExactExecutionFacility.Submission, AutoCloseable {
        private final ExecutorService executor;
        private final CountDownLatch started;
        private final CountDownLatch release = new CountDownLatch(1);

        private ConcurrentSubmission(int concurrency) {
            executor = Executors.newFixedThreadPool(concurrency);
            started = new CountDownLatch(concurrency);
        }

        @Override
        public ProtosAsyncExactExecutionFacility.Submitted submit(
                Runnable work) {
            Job job = new Job(Objects.requireNonNull(work, "work"));
            executor.submit(job);
            return job;
        }

        boolean awaitStarted(long timeout, TimeUnit unit)
                throws InterruptedException {
            return started.await(timeout, unit);
        }

        void release() {
            release.countDown();
        }

        @Override
        public void close() {
            release();
            executor.shutdownNow();
        }

        private final class Job
                implements Runnable, ProtosAsyncExactExecutionFacility.Submitted {
            private static final int QUEUED = 0;
            private static final int RUNNING = 1;
            private static final int CANCELLED = 2;
            private static final int DONE = 3;

            private final Runnable work;
            private final AtomicInteger state = new AtomicInteger(QUEUED);

            private Job(Runnable work) {
                this.work = work;
            }

            @Override
            public void run() {
                if (!state.compareAndSet(QUEUED, RUNNING)) {
                    return;
                }
                started.countDown();
                try {
                    release.await();
                    work.run();
                } catch (InterruptedException interruption) {
                    Thread.currentThread().interrupt();
                } finally {
                    state.compareAndSet(RUNNING, DONE);
                }
            }

            @Override
            public boolean cancelIfNotStarted() {
                return state.compareAndSet(QUEUED, CANCELLED);
            }
        }
    }
}
