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
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.guillermomolina.protos.runtime.ProtosActivation;
import com.guillermomolina.protos.runtime.ProtosActor;
import com.guillermomolina.protos.runtime.ProtosActorExecutionDomain;
import com.guillermomolina.protos.runtime.ProtosActorModuleState;
import com.guillermomolina.protos.runtime.ProtosClosureValue;
import com.guillermomolina.protos.runtime.ProtosCoreErrors;
import com.guillermomolina.protos.runtime.ProtosFutureValue;
import com.guillermomolina.protos.runtime.ProtosIntegerValue;
import com.guillermomolina.protos.runtime.ProtosNullValue;
import com.guillermomolina.protos.runtime.ProtosObjectValue;
import com.guillermomolina.protos.runtime.ProtosPrelude;
import com.guillermomolina.protos.runtime.ProtosTask;

import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class ProtosStandardFutureProtocolTest {
    private static ProtosPrelude core() throws Exception {
        return new ProtosCoreBootstrap().bootstrap(Path.of("protos", "lib", "core"));
    }

    private static Object eval(
            ProtosPrelude prelude, ProtosActivation activation, String source) {
        return com.guillermomolina.protos.execution.ProtosTestExecutionSupport.evaluate(source, activation);
    }

    // Deliberately Java-side: verifies the actual evaluator suspension bridge,
    // peer progress, and exact-once resume rather than only eventual Future value.
    // Deliberately Java-side: task cancellation while suspended is scheduler state
    // that the ordinary conformance runner cannot manufacture or inspect.
    // Deliberately Java-side: language conformance validates E3 outcomes, while this
    // test inspects the runtime-only intermediate state at the exact cleanup suspension point.
    @Test
    void cancellationFutureRemainsPendingWhileEnsureCleanupIsSuspended() throws Exception {
        ProtosPrelude prelude = core();
        ProtosActorExecutionDomain domain = new ProtosActorExecutionDomain();
        ProtosActivation activation =
                prelude.newModuleActivation(
                        new ProtosActorModuleState(),
                        null,
                        prelude.newExecutionContext(),
                        domain);

        ProtosFutureValue cleanupGate =
                new ProtosFutureValue(prelude.futurePrototype(), domain);
        activation.context().createLocalSlot("cleanupGate", cleanupGate);

        ProtosFutureValue future =
                (ProtosFutureValue)
                        eval(
                                prelude,
                                activation,
                                "future: (() => {\n"
                                        + "    bodyDependency: Future.all().then((values) => 7)\n"
                                        + "    (() => {\n"
                                        + "        future.cancel()\n"
                                        + "        bodyDependency.value()\n"
                                        + "        41\n"
                                        + "    }).ensure(() => {\n"
                                        + "        cleanupGate.value()\n"
                                        + "        null\n"
                                        + "    })\n"
                                        + "}).future()\n"
                                        + "future");

        ProtosTask producer = future.producerTask().orElseThrow();

        assertTrue(com.guillermomolina.protos.execution.ProtosTestExecutionSupport.dispatchOne(domain));
        assertEquals(ProtosFutureValue.State.PENDING, future.state());
        assertEquals(ProtosTask.State.SUSPENDED, producer.state());
        assertEquals(
                ProtosTask.CancellationPhase.UNWINDING,
                producer.cancellationPhase());
        assertFalse(
                producer.cancellationRequested(),
                "already-honored cancellation must be shielded during cleanup suspension");

        cleanupGate.resolve(ProtosNullValue.INSTANCE, activation);
        com.guillermomolina.protos.execution.ProtosTestExecutionSupport.dispatchUntilIdle(domain);

        assertEquals(ProtosFutureValue.State.CANCELLED, future.state());
        assertEquals(ProtosTask.State.CANCELLED, producer.state());
        assertEquals(
                ProtosTask.CancellationPhase.TERMINAL,
                producer.cancellationPhase());
    }

    // Deliberately Java-side: this verifies an intermediate Actor lifecycle state
    // while cancellation cleanup is suspended. The final cancellation/cleanup behavior itself
    // is covered by executable Protos conformance.
    @Test
    void actorTerminationWaitsForSuspendingCancellationCleanup() throws Exception {
        ProtosPrelude prelude = core();
        ProtosObjectValue actorRefPrototype =
                new ProtosObjectValue(ProtosObjectValue.rootObject());
        ProtosActor actor = new ProtosActor(actorRefPrototype);
        assertTrue(actor.markReady());

        ProtosActorExecutionDomain domain = actor.executionDomain();
        ProtosActivation activation =
                prelude.newModuleActivation(
                        actor.moduleState(),
                        null,
                        prelude.newExecutionContext(),
                        domain);

        ProtosFutureValue bodyGate =
                new ProtosFutureValue(prelude.futurePrototype(), domain);
        ProtosFutureValue cleanupGate =
                new ProtosFutureValue(prelude.futurePrototype(), domain);
        activation.context().createLocalSlot("bodyGate", bodyGate);
        activation.context().createLocalSlot("cleanupGate", cleanupGate);

        ProtosFutureValue producer =
                (ProtosFutureValue)
                        eval(
                                prelude,
                                activation,
                                "future: (() => {\n"
                                        + "    (() => {\n"
                                        + "        bodyGate.value()\n"
                                        + "        41\n"
                                        + "    }).ensure(() => {\n"
                                        + "        cleanupGate.value()\n"
                                        + "        null\n"
                                        + "    })\n"
                                        + "}).future()\n"
                                        + "future");

        assertTrue(com.guillermomolina.protos.execution.ProtosTestExecutionSupport.dispatchOne(domain));
        assertEquals(ProtosFutureValue.State.PENDING, producer.state());
        assertEquals(1, domain.liveTaskCount());

        actor.requestTerminationForRuntime();
        assertEquals(ProtosActor.LifecycleState.TERMINATING, actor.lifecycleState());

        assertTrue(com.guillermomolina.protos.execution.ProtosTestExecutionSupport.dispatchOne(domain));
        ProtosTask producerTask = producer.producerTask().orElseThrow();
        assertEquals(ProtosTask.State.SUSPENDED, producerTask.state());
        assertEquals(
                ProtosTask.CancellationPhase.UNWINDING,
                producerTask.cancellationPhase());
        assertEquals(ProtosFutureValue.State.PENDING, producer.state());
        assertEquals(
                ProtosActor.LifecycleState.TERMINATING,
                actor.lifecycleState(),
                "Actor termination must wait for cancellation cleanup");

        cleanupGate.resolve(ProtosNullValue.INSTANCE, activation);
        com.guillermomolina.protos.execution.ProtosTestExecutionSupport.dispatchUntilIdle(domain);

        assertEquals(ProtosFutureValue.State.CANCELLED, producer.state());
        assertEquals(ProtosTask.State.CANCELLED, producerTask.state());
        assertEquals(ProtosActor.LifecycleState.TERMINATED, actor.lifecycleState());
        assertEquals(0, domain.liveTaskCount());
        assertEquals(
                ProtosFutureValue.State.PENDING,
                bodyGate.state(),
                "cancelling the waiter must not cancel its observed dependency");
    }

    // Success ordering and empty-input behavior already have executable Protos
    // conformance. Retain only deterministic failure-frontier and exact Error identity.
    @Test
    void futureAllFailureUsesAscendingFrontierAndExactError() throws Exception {
        ProtosPrelude prelude = core();
        ProtosActorExecutionDomain domain = new ProtosActorExecutionDomain();
        ProtosActivation activation =
                prelude.newModuleActivation(
                        new ProtosActorModuleState(),
                        null,
                        prelude.newExecutionContext(),
                        domain);

        ProtosFutureValue first =
                new ProtosFutureValue(prelude.futurePrototype(), domain);
        ProtosFutureValue second =
                new ProtosFutureValue(prelude.futurePrototype(), domain);
        activation.context().createLocalSlot("f0", first);
        activation.context().createLocalSlot("f1", second);

        ProtosFutureValue aggregate =
                (ProtosFutureValue)
                        eval(prelude, activation, "Future.all(f0, f1)");

        ProtosObjectValue laterError = ProtosCoreErrors.newError(activation);
        second.fail(laterError);

        assertEquals(
                ProtosFutureValue.State.PENDING,
                aggregate.state(),
                "later failure must not overtake unresolved lower index");

        first.resolve(new ProtosIntegerValue(10L), activation);

        assertEquals(ProtosFutureValue.State.FAILED, aggregate.state());
        assertSame(laterError, aggregate.failedError().orElseThrow());
    }

    // Deliberately Java-side: the pending child and the exact parent-release boundary
    // are runtime state, unavailable to ordinary Protos assertions.
    @Test
    void escapedChildKeepsItsOwnerUntilTerminalization() throws Exception {
        ProtosPrelude prelude = core();
        ProtosActivation activation = prelude.newModuleActivation();
        ProtosActorExecutionDomain domain = activation.executionDomain();
        ProtosFutureValue gate = new ProtosFutureValue(prelude.futurePrototype(), domain);
        activation.context().createLocalSlot("gate", gate);

        ProtosFutureValue ownerResult = assertInstanceOf(
                ProtosFutureValue.class,
                eval(prelude, activation, """
                        holder: { stored: null; returned: null; wrapped: null; passed: null }
                        ownerResult: (() => {
                            create: () => {
                                child: (() => gate.value()).future()
                                holder.stored = child
                                child
                            }
                            returned: create()
                            holder.returned = returned
                            holder.wrapped = { future: returned }
                            pass: (value) => { holder.passed = value; null }
                            pass(holder.wrapped.future)
                            42
                        }).future()
                        ownerResult
                        """));
        ProtosTask owner = ownerResult.producerTask().orElseThrow();

        assertTrue(ProtosTestExecutionSupport.dispatchOne(domain));
        ProtosObjectValue holder = assertInstanceOf(
                ProtosObjectValue.class,
                activation.context().readLocalSlot("holder").orElseThrow());
        ProtosFutureValue childResult = assertInstanceOf(
                ProtosFutureValue.class, holder.readLocalSlot("stored").orElseThrow());
        ProtosTask child = childResult.producerTask().orElseThrow();
        assertSame(childResult, holder.readLocalSlot("returned").orElseThrow());
        assertSame(childResult, holder.readLocalSlot("passed").orElseThrow());
        ProtosObjectValue wrapper = assertInstanceOf(
                ProtosObjectValue.class, holder.readLocalSlot("wrapped").orElseThrow());
        assertSame(childResult, wrapper.readLocalSlot("future").orElseThrow());
        assertSame(owner, child.parent().orElseThrow());
        assertEquals(1, owner.children().size());
        assertTrue(owner.children().contains(child));
        assertEquals(ProtosTask.State.SUSPENDED, owner.state());
        assertEquals(ProtosFutureValue.State.PENDING, ownerResult.state());

        assertTrue(ProtosTestExecutionSupport.dispatchOne(domain));
        assertEquals(ProtosTask.State.SUSPENDED, child.state());
        assertFalse(ProtosTestExecutionSupport.dispatchOne(domain));
        assertEquals(ProtosFutureValue.State.PENDING, ownerResult.state());
        assertTrue(owner.children().contains(child));

        gate.resolve(ProtosNullValue.INSTANCE, activation);
        assertTrue(ProtosTestExecutionSupport.dispatchOne(domain));
        assertEquals(ProtosFutureValue.State.RESOLVED, childResult.state());
        assertTrue(owner.children().isEmpty(), "true child completion releases the owner");
        assertEquals(ProtosTask.State.RUNNABLE, owner.state());
        assertEquals(ProtosFutureValue.State.PENDING, ownerResult.state());

        assertTrue(ProtosTestExecutionSupport.dispatchOne(domain));
        assertEquals(ProtosFutureValue.State.RESOLVED, ownerResult.state());
        assertEquals(String.valueOf("42"), com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerDecimalText(ownerResult.resolvedValue().orElseThrow()));
        assertEquals(0, domain.liveTaskCount());
    }

    // Deliberately Java-side: keep the source pending while the continuation cancels,
    // then complete it to detect a retained observer or a late transform invocation.
    @Test
    void cancellingThenDoesNotWaitForOrCancelItsPendingSource() throws Exception {
        ProtosPrelude prelude = core();
        ProtosActivation activation = prelude.newModuleActivation();
        ProtosActorExecutionDomain domain = activation.executionDomain();
        ProtosFutureValue source = new ProtosFutureValue(prelude.futurePrototype(), domain);
        activation.context().createLocalSlot("source", source);
        ProtosFutureValue destination = assertInstanceOf(
                ProtosFutureValue.class,
                eval(prelude, activation, """
                        transforms: 0
                        source.then((value) => {
                            transforms = transforms + 1
                            value
                        })
                        """));

        ProtosTask continuation = destination.producerTask().orElseThrow();
        assertTrue(ProtosTestExecutionSupport.dispatchOne(domain));
        assertEquals(ProtosTask.State.SUSPENDED, continuation.state());
        destination.cancelRequest();
        assertEquals(ProtosTask.State.RUNNABLE, continuation.state());
        // One resume must honor cancellation. Draining until idle would hang if the
        // pending source wait merely requeues itself with cancellation still pending.
        assertTrue(ProtosTestExecutionSupport.dispatchOne(domain));
        assertEquals(
                ProtosFutureValue.State.CANCELLED,
                destination.state(),
                "source-wait resume must honor cancellation before waiting again");
        assertEquals(ProtosTask.State.CANCELLED, continuation.state());
        assertEquals(ProtosFutureValue.State.PENDING, source.state());
        assertFalse(ProtosTestExecutionSupport.dispatchOne(domain));

        source.resolve(ProtosNullValue.INSTANCE, activation);
        assertFalse(
                ProtosTestExecutionSupport.dispatchOne(domain),
                "source completion must not requeue the cancelled continuation");
        assertEquals(ProtosFutureValue.State.RESOLVED, source.state());
        assertEquals(ProtosFutureValue.State.CANCELLED, destination.state());
        Object transforms = activation.context().readLocalSlot("transforms").orElseThrow();
        assertEquals(String.valueOf("0"), com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerDecimalText(transforms));
        assertEquals(0, domain.liveTaskCount());
    }

    // Deliberately Java-side: separate source readiness from continuation dispatch
    // so cancellation is recorded at the exact resume boundary.
    @ParameterizedTest
    @EnumSource(value = ProtosFutureValue.State.class, names = {"RESOLVED", "FAILED", "CANCELLED"})
    void cancellationWinsBeforeThenSourceWaitResume(ProtosFutureValue.State sourceState)
            throws Exception {
        ProtosPrelude prelude = core();
        ProtosActivation activation = prelude.newModuleActivation();
        ProtosActorExecutionDomain domain = activation.executionDomain();
        ProtosFutureValue source = new ProtosFutureValue(prelude.futurePrototype(), domain);
        activation.context().createLocalSlot("source", source);
        ProtosFutureValue destination = assertInstanceOf(
                ProtosFutureValue.class,
                eval(prelude, activation, """
                        transforms: 0
                        source.then((value) => {
                            transforms = transforms + 1
                            value
                        })
                        """));
        ProtosTask continuation = destination.producerTask().orElseThrow();
        assertTrue(ProtosTestExecutionSupport.dispatchOne(domain));
        assertEquals(ProtosTask.State.SUSPENDED, continuation.state());

        ProtosObjectValue sourceError = ProtosCoreErrors.newError(activation);
        switch (sourceState) {
            case RESOLVED -> source.resolve(ProtosNullValue.INSTANCE, activation);
            case FAILED -> source.fail(sourceError);
            case CANCELLED -> source.cancelTerminal();
            default -> throw new AssertionError("expected a terminal source state");
        }
        assertEquals(ProtosTask.State.RUNNABLE, continuation.state());
        destination.cancelRequest();

        assertTrue(ProtosTestExecutionSupport.dispatchOne(domain));
        assertEquals(ProtosFutureValue.State.CANCELLED, destination.state());
        assertEquals(ProtosTask.State.CANCELLED, continuation.state());
        assertEquals(sourceState, source.state());
        if (sourceState == ProtosFutureValue.State.FAILED) {
            assertSame(sourceError, source.failedError().orElseThrow());
        } else if (sourceState == ProtosFutureValue.State.RESOLVED) {
            assertSame(ProtosNullValue.INSTANCE, source.resolvedValue().orElseThrow());
        }
        Object transforms = activation.context().readLocalSlot("transforms").orElseThrow();
        assertEquals(String.valueOf("0"), com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerDecimalText(transforms));
        assertFalse(ProtosTestExecutionSupport.dispatchOne(domain));
        assertEquals(0, domain.liveTaskCount());
    }

    @Test
    void runningThenTransformCanCompleteWithoutObservingCancellation() throws Exception {
        ProtosPrelude prelude = core();
        ProtosActivation activation = prelude.newModuleActivation();
        ProtosActorExecutionDomain domain = activation.executionDomain();
        ProtosFutureValue destination = assertInstanceOf(
                ProtosFutureValue.class,
                eval(prelude, activation, """
                        destination: Future.all().then((values) => {
                            destination.cancel()
                            42
                        })
                        destination
                        """));

        assertTrue(ProtosTestExecutionSupport.dispatchOne(domain));
        assertEquals(ProtosFutureValue.State.RESOLVED, destination.state());
        assertEquals(String.valueOf("42"), com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerDecimalText(destination.resolvedValue().orElseThrow()));
        assertEquals(0, domain.liveTaskCount());
    }

    // Deliberately Java-side: real host-thread contention validates the internal
    // first-terminal-wins transition without exposing host timing to Protos.
    @Test
    void firstTerminalWinsUnderRace() throws Exception {
        ProtosPrelude prelude = core();
        ProtosActivation activation = prelude.newModuleActivation();
        ProtosFutureValue future =
                new ProtosFutureValue(
                        prelude.futurePrototype(), activation.executionDomain());
        CountDownLatch go = new CountDownLatch(1);
        Object value = new ProtosObjectValue(ProtosObjectValue.rootObject());

        Thread resolver =
                new Thread(
                        () ->
                                await(
                                        go,
                                        () -> future.resolve(value, activation)));
        Thread canceller =
                new Thread(() -> await(go, future::cancelTerminal));

        resolver.start();
        canceller.start();
        go.countDown();
        resolver.join();
        canceller.join();

        assertNotEquals(ProtosFutureValue.State.PENDING, future.state());
    }

    private static void await(CountDownLatch latch, Runnable action) {
        try {
            latch.await();
            action.run();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }
}
