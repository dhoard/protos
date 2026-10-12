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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.guillermomolina.protos.runtime.ProtosActorExecutionDomain;
import com.guillermomolina.protos.runtime.ProtosFutureValue;
import com.guillermomolina.protos.runtime.ProtosIntegerValue;
import com.guillermomolina.protos.runtime.ProtosObjectValue;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Value;
import org.junit.jupiter.api.Test;

/**
 * PLAT054-3E2 / I086: HOST-FUT-1 ({@code FUTURES_AND_TASKS.md} §29, Suspendible host-initiated
 * RootActor entries) through the public {@code Context} API. The observed Future {@code gate} is a
 * pending non-Task Future the test terminalizes from Java at chosen points, so every race is
 * ordered by explicit conditions (with bounded safety timeouts), never by sleeps.
 */
final class ProtosHostEntryFutureSuspensionTest {
    private static final String CORE = Path.of("protos", "lib", "core").toAbsolutePath().toString();
    private static final long SAFETY_SECONDS = 5;

    private static final String PRELUDE =
            """
            sample: (() => 1).future()
            sample.value()
            gate: 0
            failure: Error()
            before: 0
            after: 0
            cleaned: 0
            """;

    private static Context newContext() {
        return Context.newBuilder(ProtosLanguage.ID).option("protos.CoreRoot", CORE).build();
    }

    private static ProtosEmbeddedProcess embedded(Context context) {
        context.enter();
        try {
            return ProtosLanguageContext.current().embeddedProcessOrNull();
        } finally {
            context.leave();
        }
    }

    /** Fixture: evaluates {@code PRELUDE + program} and plants a fresh pending {@code gate}. */
    private static final class Fixture {
        final Context context;
        final ProtosEmbeddedProcess process;
        final ProtosObjectValue module;
        final ProtosFutureValue gate;
        /** The value of the fixture evaluation's final expression. */
        final Value result;

        Fixture(Context context, String program) {
            this(context, program, "0");
        }

        Fixture(Context context, String program, String resultExpression) {
            this.context = context;
            result = context.eval(ProtosLanguage.ID, PRELUDE + program + "\n" + resultExpression + "\n");
            process = embedded(context);
            module = process.selectedModuleContext().orElseThrow();
            ProtosFutureValue sample = (ProtosFutureValue) module.readLocalSlot("sample").orElseThrow();
            gate =
                    new ProtosFutureValue(
                            (ProtosObjectValue) sample.parent().orElseThrow(), sample.domain());
            module.assignLocalSlot("gate", gate);
        }

        Value member(String name) {
            return context.getBindings(ProtosLanguage.ID).getMember(name);
        }

        long slot(String name) {
            return Long.parseLong(com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerDecimalText(module.readLocalSlot(name).orElseThrow()));
        }

        ProtosActorExecutionDomain domain() {
            return process.rootExecutionDomain();
        }

        void resolve(long value) {
            assertTrue(gate.resolve(new ProtosIntegerValue(value), null));
        }

        ProtosObjectValue failureError() {
            return (ProtosObjectValue) module.readLocalSlot("failure").orElseThrow();
        }
    }

    /** One host entry on its own thread, so the test thread can observe and terminalize. */
    private static final class Entry {
        final CompletableFuture<Object> outcome = new CompletableFuture<>();
        final Thread thread;

        Entry(Value executable, Object... arguments) {
            thread =
                    new Thread(
                            () -> {
                                try {
                                    Value result = executable.execute(arguments);
                                    outcome.complete(
                                            result.isBoolean()
                                                    ? (Object) result.asBoolean()
                                                    : result.fitsInLong() ? result.asLong() : result);
                                } catch (Throwable failure) {
                                    outcome.complete(failure);
                                }
                            },
                            "host-entry");
            thread.setDaemon(true);
            thread.start();
        }

        /** Waits until the entry reached its suspension point and its thread is parked. */
        void awaitSuspended(BooleanSupplier reached) {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(SAFETY_SECONDS);
            while (!(reached.getAsBoolean() && thread.getState() == Thread.State.WAITING)) {
                if (outcome.isDone()) {
                    Object early = outcome.join();
                    if (early instanceof Throwable failure) {
                        throw new AssertionError("host entry ended before suspending", failure);
                    }
                    fail("host entry returned " + early + " before suspending");
                }
                if (System.nanoTime() > deadline) {
                    fail("host entry did not suspend (thread state " + thread.getState() + ")");
                }
                Thread.onSpinWait();
            }
            assertFalse(outcome.isDone(), "a suspended entry has not returned to the host");
        }

        Object await() throws Exception {
            try {
                return outcome.get(SAFETY_SECONDS, TimeUnit.SECONDS);
            } finally {
                thread.join(TimeUnit.SECONDS.toMillis(SAFETY_SECONDS));
            }
        }
    }

    private static void assertCompactState(Fixture f) {
        assertEquals(0, f.domain().liveTaskCount(), "no Task, RootTask, or hidden waiter Task");
        assertEquals(
                ProtosActorExecutionDomain.HostEntryExtent.NONE,
                f.domain().hostEntryExtentForRuntime());
        assertFalse(f.process.actorCarrierSubstrateInitializedForTesting(), "no Actor carriers");
    }

    private static final String SUSPENDING_RUN =
            """
            run: () => {
                before = before + 1
                v: gate.value()
                after = after + 1
                v + 1
            }
            """;

    @Test
    void trivialCallsKeepTheCompactPathAndAllocateNoSuspensionMachinery() {
        try (Context context = newContext()) {
            Fixture f = new Fixture(context, "trivial: () => { 1 }\nidentity: (x) => { x }");
            ProtosEmbeddedProcess process = f.process;
            Value trivial = f.member("trivial");
            Value identity = f.member("identity");
            for (int index = 0; index < 5; index++) {
                assertEquals(1, trivial.execute().asInt());
                assertEquals(index, identity.execute(index).asInt());
                assertCompactState(f);
                assertSame(process, embedded(context), "no Process or session per call");
            }
        }
    }

    @Test
    void alreadyResolvedFutureReturnsImmediately() {
        try (Context context = newContext()) {
            Fixture f = new Fixture(context, SUSPENDING_RUN);
            f.resolve(41);
            assertEquals(42, f.member("run").execute().asInt());
            assertEquals(1, f.slot("before"));
            assertEquals(1, f.slot("after"));
            assertCompactState(f);
        }
    }

    @Test
    void pendingFutureSuspendsAndResumesExactlyOnceAtTheContinuation() throws Exception {
        try (Context context = newContext()) {
            Fixture f = new Fixture(context, SUSPENDING_RUN);
            Entry entry = new Entry(f.member("run"));
            entry.awaitSuspended(() -> f.slot("before") == 1);
            assertEquals(0, f.slot("after"), "no effect after the suspension point runs early");

            f.resolve(41);
            assertEquals(42L, entry.await(), "the final result, not a suspension descriptor");
            assertEquals(0, f.gate.observerCountForTesting(), "terminalization cleared the observer");
            assertEquals(1, f.slot("before"), "effects before the suspension are not replayed");
            assertEquals(1, f.slot("after"));
            assertFalse(f.gate.resolve(new ProtosIntegerValue(0), null), "first terminal wins");
            assertEquals(1, f.slot("after"), "no double resumption");
            assertCompactState(f);
            assertTrue(f.process.isLive());
            // The same retained executable keeps working, now without suspending.
            assertEquals(42, f.member("run").execute().asInt());
        }
    }

    @Test
    void producerWorkInTheRootActorTerminalizesTheFutureWhileTheEntryWaits() {
        try (Context context = newContext()) {
            Fixture f =
                    new Fixture(
                            context,
                            """
                            run: () => {
                                produced: (() => { before = before + 1
                                    20 }).future()
                                produced.value() + 1
                            }
                            """);
            // The producer Task is queued, not run, when value() registers its observer: the
            // terminalization happens after registration and before the entry blocks.
            assertEquals(21, f.member("run").execute().asInt());
            assertEquals(1, f.slot("before"));
            assertCompactState(f);
        }
    }

    @Test
    void taskWaiterAndHostEntryObserveTheSameFuture() throws Exception {
        try (Context context = newContext()) {
            Fixture f =
                    new Fixture(
                            context,
                            """
                            run: () => {
                                waiter: (() => { before = before + 1
                                    gate.value() + 1 }).future()
                                a: gate.value()
                                a + waiter.value()
                            }
                            """);
            Entry entry = new Entry(f.member("run"));
            // The Task waiter was dispatched by the waiting entry and suspended on gate too.
            entry.awaitSuspended(() -> f.slot("before") == 1);
            f.resolve(10);
            assertEquals(21L, entry.await());
            assertCompactState(f);
        }
    }

    @Test
    void failedFutureSignalsItsErrorWhichAHandlerCatches() throws Exception {
        try (Context context = newContext()) {
            Fixture f =
                    new Fixture(
                            context,
                            """
                            run: () => {
                                Error.handle(() => { gate.value() }, (caught) => { caught === failure })
                            }
                            """);
            Entry entry = new Entry(f.member("run"));
            entry.awaitSuspended(() -> true);
            assertTrue(f.gate.fail(f.failureError()));
            assertEquals(true, entry.await(), "the resumed observation signals the Future's Error");
            assertTrue(f.process.isLive(), "a handled resumed failure is not fatal");
        }
    }

    @Test
    void unhandledResumedFailureIsFatalToTheRootActor() throws Exception {
        try (Context context = newContext()) {
            Fixture f = new Fixture(context, SUSPENDING_RUN);
            Entry entry = new Entry(f.member("run"));
            entry.awaitSuspended(() -> f.slot("before") == 1);
            assertTrue(f.gate.fail(f.failureError()));
            Object outcome = entry.await();
            PolyglotException failure = assertInstanceOfPolyglot(outcome);
            assertTrue(failure.isGuestException());
            assertFalse(failure.isInternalError());
            assertEquals(0, f.slot("after"));
            assertFalse(f.process.isLive(), "§24C/§32: an escaping Error fails the RootActor");
        }
    }

    @Test
    void cancelledFutureSignalsCancelledAtTheResumedObservation() throws Exception {
        try (Context context = newContext()) {
            Fixture f =
                    new Fixture(
                            context,
                            "run: () => { Error.handle(() => { gate.value() }, (caught) => { 9 }) }");
            assertEquals(ProtosFutureValue.State.CANCELLED, cancelledBeforeEntry(f));
            assertEquals(9, f.member("run").execute().asInt(), "already cancelled");

            Fixture g = new Fixture(context, "run2: () => { Error.handle(() => { gate.value() }, (caught) => { 9 }) }");
            Entry entry = new Entry(g.member("run2"));
            entry.awaitSuspended(() -> true);
            assertTrue(g.gate.cancelRequest(), "a non-Task Future without producer cancels");
            assertEquals(9L, entry.await());
            assertTrue(g.process.isLive());
        }
    }

    private static ProtosFutureValue.State cancelledBeforeEntry(Fixture f) {
        assertTrue(f.gate.cancelTerminal());
        return f.gate.state();
    }

    @Test
    void cancellationRacingResolutionResumesExactlyOnce() throws Exception {
        try (Context context = newContext()) {
            Fixture f =
                    new Fixture(
                            context,
                            """
                            run: () => {
                                v: Error.handle(() => { gate.value() }, (caught) => { 99 })
                                after = after + 1
                                v
                            }
                            """);
            Entry entry = new Entry(f.member("run"));
            entry.awaitSuspended(() -> true);
            CountDownLatch start = new CountDownLatch(1);
            Thread canceller =
                    new Thread(
                            () -> {
                                awaitLatch(start);
                                f.gate.cancelTerminal();
                            });
            Thread resolver =
                    new Thread(
                            () -> {
                                awaitLatch(start);
                                f.gate.resolve(new ProtosIntegerValue(5), null);
                            });
            canceller.start();
            resolver.start();
            start.countDown();
            canceller.join();
            resolver.join();
            Object result = entry.await();
            long expected = f.gate.state() == ProtosFutureValue.State.RESOLVED ? 5L : 99L;
            assertEquals(expected, result, "the entry observes exactly the winning terminal state");
            assertEquals(1, f.slot("after"));
        }
    }

    private static void awaitLatch(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    void ensureCleanupRunsOnceAfterResumption() throws Exception {
        try (Context context = newContext()) {
            Fixture f =
                    new Fixture(
                            context,
                            """
                            run: () => {
                                (() => {
                                    before = before + 1
                                    gate.value()
                                }).ensure(() => { cleaned = cleaned + 1 })
                            }
                            """);
            Entry entry = new Entry(f.member("run"));
            entry.awaitSuspended(() -> f.slot("before") == 1);
            assertEquals(0, f.slot("cleaned"), "suspension does not run cleanup");
            f.resolve(3);
            assertEquals(3L, entry.await());
            assertEquals(1, f.slot("cleaned"));
        }
    }

    @Test
    void nonLocalReturnArgumentsAndLocalsSurviveSuspension() throws Exception {
        try (Context context = newContext()) {
            Fixture f =
                    new Fixture(
                            context,
                            """
                            run: (a, b) => {
                                local: a * 10
                                escape: () => { ^(local + gate.value() + b) }
                                escape()
                                0
                            }
                            """);
            Entry entry = new Entry(f.member("run"), 2, 3);
            entry.awaitSuspended(() -> true);
            f.resolve(100);
            assertEquals(123L, entry.await(), "ReturnHome of run receives the non-local return");
            assertCompactState(f);
        }
    }

    @Test
    void nestedSameThreadEntryIsAForeignCallbackAndRejectsSuspension() {
        try (Context context = newContext()) {
            Fixture f =
                    new Fixture(
                            context,
                            "nested: () => { Error.handle(() => { gate.value() }, (caught) => { 7 }) }");
            Value nested = f.member("nested");
            Thread token = f.process.enterRootActor();
            try {
                // The outer entry plays the enclosing Protos execution of a synchronous callback.
                assertEquals(7, nested.execute().asInt(), "a fresh Error at the suspension point");
            } finally {
                f.process.exitRootActor(token);
            }
            assertTrue(f.process.isLive());
            assertCompactState(f);
            // As an outermost entry the same Closure observes the now-terminal Future normally.
            f.resolve(1);
            assertEquals(1, nested.execute().asInt());
        }
    }

    @Test
    void concurrentEntryIsStillRejectedWhileAnEntryIsSuspended() throws Exception {
        try (Context context = newContext()) {
            Fixture f = new Fixture(context, SUSPENDING_RUN + "trivial: () => { 1 }");
            Value trivial = f.member("trivial");
            Entry entry = new Entry(f.member("run"));
            entry.awaitSuspended(() -> f.slot("before") == 1);
            PolyglotException rejected = assertThrows(PolyglotException.class, trivial::execute);
            assertFalse(rejected.isInternalError(), "rejected, not queued behind the suspension");
            f.resolve(1);
            assertEquals(2L, entry.await());
            assertEquals(1, trivial.execute().asInt());
        }
    }

    @Test
    void ordinaryCloseOfAnExecutingContextIsRefusedAndTheEntryCompletes() throws Exception {
        try (Context context = newContext()) {
            Fixture f = new Fixture(context, SUSPENDING_RUN);
            Entry entry = new Entry(f.member("run"));
            entry.awaitSuspended(() -> f.slot("before") == 1);
            assertThrows(IllegalStateException.class, context::close);
            f.resolve(1);
            assertEquals(2L, entry.await());
        }
    }

    @Test
    void cancellingCloseEndsTheWaitWithoutResumingGuestCode() throws Exception {
        Context context = newContext();
        Fixture f;
        Value retained;
        Entry entry;
        try {
            f = new Fixture(context, SUSPENDING_RUN);
            retained = f.member("run");
            entry = new Entry(retained);
            entry.awaitSuspended(() -> f.slot("before") == 1);
        } catch (Throwable setup) {
            context.close(true);
            throw setup;
        }
        context.close(true);
        Object outcome = entry.await();
        assertTrue(assertInstanceOfPolyglot(outcome).isCancelled());
        assertEquals(0, f.gate.observerCountForTesting(), "the abandoned wait released its observer");
        f.resolve(1);
        assertEquals(0, f.slot("after"), "no guest code after the terminal boundary");
        assertFalse(f.process.isLive());
        // The closed Context executes nothing for a retained executable.
        assertThrows(PolyglotException.class, retained::execute);
    }

    @Test
    void processTerminationEndsTheWaitAndReleasesTheObserver() throws Exception {
        try (Context context = newContext()) {
            Fixture f = new Fixture(context, SUSPENDING_RUN);
            Entry entry = new Entry(f.member("run"));
            entry.awaitSuspended(() -> f.slot("before") == 1);
            assertEquals(1, f.gate.observerCountForTesting());
            f.process.processForTesting().requestTerminationForRuntime();
            PolyglotException terminated = assertInstanceOfPolyglot(entry.await());
            assertFalse(terminated.isInternalError(), "the host observes termination");
            assertFalse(f.process.isLive());
            assertEquals(0, f.gate.observerCountForTesting(), "the abandoned wait released its observer");
            f.resolve(1);
            assertEquals(0, f.slot("after"), "the dropped continuation never resumes");
            assertEquals(ProtosFutureValue.State.RESOLVED, f.gate.state(), "Future unaffected");
        }
    }

    @Test
    void terminationThatTerminalizesTheObservedFutureStillNeverResumes() throws Exception {
        try (Context context = newContext()) {
            Fixture f = new Fixture(context, SUSPENDING_RUN);
            // A RootActor-registered non-Task Future is cancelled by the TERMINATING cutover, so
            // the dependency becomes ready by the termination itself.
            f.domain().registerActorNonTaskFutureForRuntime(f.gate);
            Entry entry = new Entry(f.member("run"));
            entry.awaitSuspended(() -> f.slot("before") == 1);
            f.process.processForTesting().requestTerminationForRuntime();
            PolyglotException terminated = assertInstanceOfPolyglot(entry.await());
            assertFalse(terminated.isInternalError());
            assertEquals(ProtosFutureValue.State.CANCELLED, f.gate.state());
            assertEquals(0, f.slot("after"), "no guest code after the terminal boundary");
        }
    }

    @Test
    void nestedOrdinaryCallsAndStructuredControlSuspendWithinTheEntry() throws Exception {
        try (Context context = newContext()) {
            Fixture f =
                    new Fixture(
                            context,
                            """
                            inner: () => { gate.value() }
                            middle: () => { inner() + 1 }
                            apply: (block) => { block() }
                            run: () => {
                                r: 0
                                (gate === gate).ifTrue(() => {
                                    r = apply(() => { middle() * 10 })
                                })
                                before = before + 1
                                r
                            }
                            """);
            Entry entry = new Entry(f.member("run"));
            entry.awaitSuspended(() -> true);
            assertEquals(0, f.slot("before"));
            f.resolve(4);
            assertEquals(50L, entry.await(), "Closure-to-Closure calls stay suspendible");
            assertEquals(1, f.slot("before"));
            assertCompactState(f);
        }
    }

    @Test
    void nativeClosureEntryOnTheGenericPathSuspendsThroughItsCallbacks() throws Exception {
        try (Context context = newContext()) {
            // The extracted native Object.caseOf is receiver-bound to the module context
            // (CALLABLES.md §11), which caseOf accepts as its subject; it has no compact target,
            // so the entry takes the generic path and its action reaches the pending Future.
            Fixture f =
                    new Fixture(
                            context,
                            """
                            dispatch: Object.caseOf
                            cases: %{
                                Capture: (subject) => {
                                    before = before + 1
                                    gate.value() + 1
                                }
                            }
                            """,
                            "cases");
            Entry entry = new Entry(f.member("dispatch"), f.result);
            entry.awaitSuspended(() -> f.slot("before") == 1);
            f.resolve(6);
            assertEquals(7L, entry.await(), "the native caseOf entry suspended and resumed");
            assertEquals(1, f.slot("before"), "no replay through the native entry");
            assertEquals(0, f.gate.observerCountForTesting());
            assertCompactState(f);
        }
    }

    @Test
    void suspensionInOneContextDoesNotBlockAnother() throws Exception {
        try (Context first = newContext();
                Context second = newContext()) {
            Fixture f = new Fixture(first, SUSPENDING_RUN);
            Fixture g = new Fixture(second, SUSPENDING_RUN);
            Entry entry = new Entry(f.member("run"));
            entry.awaitSuspended(() -> f.slot("before") == 1);
            g.resolve(9);
            assertEquals(10, g.member("run").execute().asInt());
            f.resolve(1);
            assertEquals(2L, entry.await());
        }
    }

    private static PolyglotException assertInstanceOfPolyglot(Object outcome) {
        if (outcome instanceof PolyglotException polyglot) {
            return polyglot;
        }
        throw new AssertionError("expected a PolyglotException but the entry produced " + outcome);
    }
}
