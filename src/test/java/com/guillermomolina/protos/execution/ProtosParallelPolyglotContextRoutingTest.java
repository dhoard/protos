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
import com.guillermomolina.protos.runtime.ProtosActor;
import com.guillermomolina.protos.runtime.ProtosClosureValue;
import com.guillermomolina.protos.runtime.ProtosFutureValue;
import com.guillermomolina.protos.runtime.ProtosIntegerValue;
import com.guillermomolina.protos.runtime.ProtosNullValue;
import com.guillermomolina.protos.runtime.ProtosPrelude;
import com.guillermomolina.protos.runtime.ProtosProcessRuntime;
import com.oracle.truffle.api.source.Source;
import java.io.InputStream;
import java.io.OutputStream;

import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class ProtosParallelPolyglotContextRoutingTest {
    @Test
    void siblingAndNestedPUseTheOwningProcessContext() throws Exception {
        ProtosPrelude prelude =
                new ProtosCoreBootstrap().bootstrap(Path.of("protos", "lib", "core"));
        ProtosProcessRuntime process =
                new ProtosProcessRuntime(prelude.actorRefPrototypeForRuntime());

        try (ProtosPolyglotRuntimeHost host = ProtosPolyglotRuntimeHost.open()) {
            ProtosPolyglotProcessContext hosted =
                    host.hostProcess(
                            process,
                            InputStream.nullInputStream(),
                            OutputStream.nullOutputStream(),
                            OutputStream.nullOutputStream());
            ProtosActor actor = process.rootActorForRuntime();
            ProtosActivation caller =
                    prelude.newModuleActivation(
                            actor.moduleState(),
                            null,
                            prelude.newExecutionContext(),
                            actor.executionDomain());

            proveSiblingParallelism(hosted, caller);
            proveNestedPlacement(hosted, caller);

            assertTrue(process.requestTerminationForRuntime());
            assertEquals(
                    ProtosProcessRuntime.LifecycleState.TERMINATED,
                    process.lifecycleState());
            assertTrue(hosted.isClosedForTesting());
        }
    }

    private static void proveSiblingParallelism(
            ProtosPolyglotProcessContext hosted,
            ProtosActivation caller)
            throws Exception {
        CountDownLatch entered = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<ProtosLanguageContext> firstContext = new AtomicReference<>();
        AtomicReference<ProtosLanguageContext> secondContext = new AtomicReference<>();
        AtomicLong firstThread = new AtomicLong();
        AtomicLong secondThread = new AtomicLong();

        caller.context()
                .createLocalSlot(
                        "firstProbe",
                        ProtosClosureValue.nativeClosure(
                                (activation, arguments) -> {
                                    firstContext.set(ProtosLanguageContext.current());
                                    firstThread.set(Thread.currentThread().threadId());
                                    entered.countDown();
                                    await(release);
                                    return ProtosNullValue.INSTANCE;
                                }));
        caller.context()
                .createLocalSlot(
                        "secondProbe",
                        ProtosClosureValue.nativeClosure(
                                (activation, arguments) -> {
                                    secondContext.set(ProtosLanguageContext.current());
                                    secondThread.set(Thread.currentThread().threadId());
                                    entered.countDown();
                                    await(release);
                                    return ProtosNullValue.INSTANCE;
                                }));

        ProtosFutureValue first =
                (ProtosFutureValue) eval(hosted, caller, "firstProbe.parallel()");
        ProtosFutureValue second =
                (ProtosFutureValue) eval(hosted, caller, "secondProbe.parallel()");

        try {
            assertTrue(
                    entered.await(5, TimeUnit.SECONDS),
                    "two independent P computations must be able to enter concurrently");
            assertNotEquals(
                    firstThread.get(),
                    secondThread.get(),
                    "independent P computations must preserve physical multi-carrier progress");
            ProtosLanguageContext expected = hosted.currentLanguageContextForTesting();
            assertSame(expected, firstContext.get());
            assertSame(expected, secondContext.get());
        } finally {
            release.countDown();
        }

        awaitResolved(first, caller);
        awaitResolved(second, caller);
    }

    private static void proveNestedPlacement(
            ProtosPolyglotProcessContext hosted,
            ProtosActivation caller) {
        AtomicReference<ProtosLanguageContext> outerContext = new AtomicReference<>();
        AtomicReference<ProtosLanguageContext> nestedContext = new AtomicReference<>();

        caller.context()
                .createLocalSlot(
                        "outerMark",
                        ProtosClosureValue.nativeClosure(
                                (activation, arguments) -> {
                                    outerContext.set(ProtosLanguageContext.current());
                                    return ProtosNullValue.INSTANCE;
                                }));
        caller.context()
                .createLocalSlot(
                        "nestedProbe",
                        ProtosClosureValue.nativeClosure(
                                (activation, arguments) -> {
                                    nestedContext.set(ProtosLanguageContext.current());
                                    return new ProtosIntegerValue(73);
                                }));

        ProtosFutureValue result =
                (ProtosFutureValue)
                        eval(
                                hosted,
                                caller,
                                "((mark, worker) => { mark(); worker.parallel().value() })"
                                        + ".parallel(outerMark, nestedProbe)");
        awaitResolved(result, caller);

        ProtosLanguageContext expected = hosted.currentLanguageContextForTesting();
        assertSame(expected, outerContext.get(), "outer P must use the Process Context");
        assertSame(expected, nestedContext.get(), "nested P must inherit the same Process placement");
        assertEquals(String.valueOf("73"), com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerDecimalText(result.resolvedValue().orElseThrow()));
    }

    private static Object eval(
            ProtosPolyglotProcessContext hosted,
            ProtosActivation activation,
            String characters) {
        Source source =
                Source.newBuilder(
                                ProtosLanguage.ID,
                                characters,
                                "<parallel-context-routing>")
                        .mimeType(ProtosLanguage.MIME_TYPE)
                        .build();
        return hosted.evaluatePersistent(source, activation);
    }

    private static void awaitResolved(ProtosFutureValue future, ProtosActivation caller) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        while (future.isPending() && System.nanoTime() < deadline) {
            caller.executionDomain().dispatchUntilIdle();
            Thread.onSpinWait();
        }
        assertEquals(ProtosFutureValue.State.RESOLVED, future.state());
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new AssertionError(failure);
        }
    }
}
