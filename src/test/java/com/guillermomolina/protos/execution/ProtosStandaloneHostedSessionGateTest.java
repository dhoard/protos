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
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.guillermomolina.protos.runtime.ProtosProcessRuntime;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * PERF025-F1 (PLAT046 Candidate B): hosted session guest work runs on the calling thread behind a
 * session-local gate. A guest write to standard output runs synchronously on the guest's thread, so
 * a holding stdout stream observes that thread and keeps one guest operation deterministically
 * in flight.
 */
final class ProtosStandaloneHostedSessionGateTest {
    private static final Path CORE = Path.of("protos", "lib", "core");
    private static final long TIMEOUT_SECONDS = 30;
    private static final String SOURCE =
            """
            emit: () => {
                process.stdout().write(process.stdoutEncoding().encode("x")).value()
                0
            }

            0
            """;

    @TempDir Path directory;

    @Test
    void dynamicAndPreparedInvocationRunOnTheCallingThread() throws Exception {
        HoldingStream stdout = new HoldingStream();
        try (ProtosStandaloneHostedSession session = open(stdout)) {
            ProtosStandaloneHostedSession.PreparedTopLevel prepared =
                    session.prepareTopLevel("emit");

            Call<ProtosExecutionOutcome> dynamic =
                    Call.start("f1-dynamic-caller", () -> session.invokeTopLevel("emit"));
            assertZero(dynamic.get());
            assertSame(dynamic.thread(), stdout.lastWriter);

            Call<ProtosExecutionOutcome> preparedCall =
                    Call.start("f1-prepared-caller", prepared::invoke);
            assertZero(preparedCall.get());
            assertSame(preparedCall.thread(), stdout.lastWriter);
        }
    }

    @Test
    void secondCallerWaitsAtTheGateWhileAnOperationIsInFlight() throws Exception {
        HoldingStream stdout = new HoldingStream();
        try (ProtosStandaloneHostedSession session = open(stdout)) {
            ProtosStandaloneHostedSession.PreparedTopLevel prepared =
                    session.prepareTopLevel("emit");
            stdout.hold();
            Call<ProtosExecutionOutcome> first =
                    Call.start("f1-first", () -> session.invokeTopLevel("emit"));
            stdout.awaitHeld();

            Call<ProtosExecutionOutcome> dynamic =
                    Call.start("f1-dynamic", () -> session.invokeTopLevel("emit"));
            Call<ProtosExecutionOutcome> preparedCall = Call.start("f1-prepared", prepared::invoke);
            awaitQueued(session, dynamic.thread());
            awaitQueued(session, preparedCall.thread());
            assertEquals(1, stdout.writes.get());

            stdout.release();
            assertZero(first.get());
            assertZero(dynamic.get());
            assertZero(preparedCall.get());
            assertEquals(3, stdout.writes.get());
            assertEquals(1, stdout.maxActive.get());
        }
    }

    @Test
    void concurrentCallersNeverOverlapGuestWork() throws Exception {
        int callers = 4;
        int callsPerCaller = 5;
        HoldingStream stdout = new HoldingStream();
        try (ProtosStandaloneHostedSession session = open(stdout)) {
            ProtosStandaloneHostedSession.PreparedTopLevel prepared =
                    session.prepareTopLevel("emit");
            CyclicBarrier start = new CyclicBarrier(callers);
            List<Call<Void>> calls = new ArrayList<>();
            for (int caller = 0; caller < callers; caller++) {
                boolean dynamic = caller % 2 == 0;
                calls.add(
                        Call.start(
                                "f1-caller-" + caller,
                                () -> {
                                    start.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                                    for (int call = 0; call < callsPerCaller; call++) {
                                        assertZero(
                                                dynamic
                                                        ? session.invokeTopLevel("emit")
                                                        : prepared.invoke());
                                    }
                                    return null;
                                }));
            }
            for (Call<Void> call : calls) call.get();
            assertEquals(callers * callsPerCaller, stdout.writes.get());
            assertEquals(1, stdout.maxActive.get());
        }
    }

    @Test
    void closeLetsTheStartedOperationFinishAndRejectsEveryLaterOne() throws Exception {
        HoldingStream stdout = new HoldingStream();
        ProtosStandaloneHostedSession session = open(stdout);
        ProtosProcessRuntime process = session.processForTesting();
        ProtosStandaloneHostedSession.PreparedTopLevel prepared = session.prepareTopLevel("emit");
        stdout.hold();
        Call<ProtosExecutionOutcome> started =
                Call.start("f1-started", () -> session.invokeTopLevel("emit"));
        stdout.awaitHeld();

        Call<Void> close =
                Call.start(
                        "f1-close",
                        () -> {
                            session.close();
                            return null;
                        });
        awaitQueued(session, close.thread());
        // The close cutover is visible before close waits at the gate.
        Call<ProtosExecutionOutcome> lateDynamic =
                Call.start("f1-late-dynamic", () -> session.invokeTopLevel("emit"));
        Call<ProtosExecutionOutcome> latePrepared =
                Call.start("f1-late-prepared", prepared::invoke);
        awaitQueued(session, lateDynamic.thread());
        awaitQueued(session, latePrepared.thread());
        assertEquals(ProtosProcessRuntime.LifecycleState.RUNNING, process.lifecycleState());

        stdout.release();
        assertZero(started.get());
        close.get();
        assertRejected(lateDynamic);
        assertRejected(latePrepared);
        assertEquals(1, stdout.writes.get());
        assertEquals(ProtosProcessRuntime.LifecycleState.TERMINATED, process.lifecycleState());
        assertThrows(IllegalStateException.class, () -> session.invokeTopLevel("emit"));
        assertThrows(IllegalStateException.class, prepared::invoke);
    }

    private ProtosStandaloneHostedSession open(OutputStream stdout) throws IOException {
        Path file = directory.resolve("workload.protos");
        Files.writeString(file, SOURCE, StandardCharsets.UTF_8);
        ProtosStandaloneHostedSession session =
                ProtosStandaloneHostedSession.open(
                        CORE,
                        file,
                        List.of(),
                        InputStream.nullInputStream(),
                        stdout,
                        OutputStream.nullOutputStream());
        assertZero(session.initialOutcome());
        return session;
    }

    private static void awaitQueued(ProtosStandaloneHostedSession session, Thread thread) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
        while (!session.hasQueuedGuestOperationForTesting(thread)) {
            assertTrue(thread.isAlive(), thread.getName() + " ended without waiting at the gate");
            assertTrue(System.nanoTime() < deadline, thread.getName() + " never reached the gate");
            Thread.onSpinWait();
        }
    }

    private static void assertRejected(Call<ProtosExecutionOutcome> call) {
        ExecutionException failure = assertThrows(ExecutionException.class, call::get);
        assertInstanceOf(IllegalStateException.class, failure.getCause());
    }

    private static void assertZero(ProtosExecutionOutcome outcome) {
        assertEquals(ProtosExecutionOutcome.State.COMPLETED, outcome.state());
        assertEquals(String.valueOf("0"), com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerDecimalText(outcome.value()));
    }

    /** One public session operation running on its own named platform thread. */
    private record Call<T>(Thread thread, FutureTask<T> task) {
        static <T> Call<T> start(String name, Callable<T> operation) {
            FutureTask<T> task = new FutureTask<>(operation);
            Thread thread = new Thread(task, name);
            thread.start();
            return new Call<>(thread, task);
        }

        T get() throws Exception {
            return task.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        }
    }

    /**
     * Standard output that records the writing thread and overlapping writes and, while held,
     * blocks the first write until released.
     */
    private static final class HoldingStream extends OutputStream {
        final AtomicInteger writes = new AtomicInteger();
        final AtomicInteger maxActive = new AtomicInteger();
        private final AtomicInteger active = new AtomicInteger();
        volatile Thread lastWriter;
        private volatile CountDownLatch held;
        private volatile CountDownLatch released;

        void hold() {
            released = new CountDownLatch(1);
            held = new CountDownLatch(1);
        }

        void awaitHeld() throws InterruptedException {
            assertTrue(held.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), "guest write never started");
        }

        void release() {
            CountDownLatch gate = released;
            released = null;
            gate.countDown();
        }

        @Override
        public void write(int b) throws IOException {
            write(new byte[] {(byte) b}, 0, 1);
        }

        @Override
        public void write(byte[] bytes, int offset, int length) throws IOException {
            int now = active.incrementAndGet();
            try {
                maxActive.accumulateAndGet(now, Math::max);
                writes.incrementAndGet();
                lastWriter = Thread.currentThread();
                CountDownLatch gate = released;
                if (gate != null) {
                    held.countDown();
                    if (!gate.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                        throw new IOException("held guest write was never released");
                    }
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException(interrupted);
            } finally {
                active.decrementAndGet();
            }
        }
    }
}
