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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.guillermomolina.protos.execution.ProtosCoreBootstrap;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class ProtosFilesystemNamespaceMutationFlowTest {
    private static final Path CORE = Path.of("protos", "lib", "core");

    @Test
    void invalidPathArgumentsFailBeforeBackendAuthority() throws Exception {
        Fixture x = fixture();

        ProtosFutureValue invalidSource =
                x.flow.replace(
                        x.activation,
                        new ProtosStringValue("not-a-path"),
                        path(x.prelude, "target"));
        ProtosFutureValue invalidTarget =
                x.flow.replace(
                        x.activation,
                        path(x.prelude, "source"),
                        new ProtosStringValue("not-a-path"));
        ProtosFutureValue invalidRemove =
                x.flow.remove(x.activation, new ProtosStringValue("not-a-path"));

        assertInvalid(x, invalidSource);
        assertInvalid(x, invalidTarget);
        assertInvalid(x, invalidRemove);
        assertEquals(0, x.backend.replaceCalls.get());
        assertEquals(0, x.backend.removeCalls.get());
        assertTrue(x.backend.invocations.isEmpty());
    }

    @Test
    void replaceAndRemovePassOnlyCapturedComponentSnapshotsInOrder() throws Exception {
        Fixture x = fixture();
        x.flow.replace(
                x.activation,
                new ProtosPathValue(x.prelude.pathPrototype(), List.of("dir", "a/b")),
                path(x.prelude, "c\\d"));
        x.flow.remove(x.activation, path(x.prelude, "e:f"));

        assertEquals(
                List.of(
                        new ProtosPathComponents(List.of("dir", "a/b")),
                        new ProtosPathComponents(List.of("c\\d"))),
                x.backend.invocations.remove().paths);
        assertEquals(
                List.of(new ProtosPathComponents(List.of("e:f"))),
                x.backend.invocations.remove().paths);
    }

    @Test
    void preCommitCancellationPreventsNamespaceEffectAndReachesBackendHook() throws Exception {
        Fixture x = fixture();
        ProtosFutureValue future =
                x.flow.replace(
                        x.activation,
                        path(x.prelude, "source"),
                        path(x.prelude, "target"));
        Invocation invocation = x.backend.invocations.remove();

        assertTrue(future.cancelRequest());
        assertEquals(ProtosFutureValue.State.CANCELLED, future.state());
        assertEquals(1, invocation.cancellations.get());

        AtomicInteger effects = new AtomicInteger();
        assertFalse(invocation.completion.commitPortableEffect(effects::incrementAndGet));
        assertEquals(0, effects.get());
        assertEquals(ProtosFutureValue.State.CANCELLED, future.state());
    }

    @Test
    void successfulAtomicEffectCommitsAndResolvesExactFilesystemReceiver() throws Exception {
        Fixture x = fixture();
        ProtosFutureValue future =
                x.flow.replace(
                        x.activation,
                        path(x.prelude, "source"),
                        path(x.prelude, "target"));
        Invocation invocation = x.backend.invocations.remove();
        AtomicInteger effects = new AtomicInteger();

        assertTrue(invocation.completion.commitPortableEffect(effects::incrementAndGet));

        assertEquals(1, effects.get());
        assertEquals(ProtosFutureValue.State.RESOLVED, future.state());
        assertSame(x.filesystem, future.resolvedValue().orElseThrow());
        assertFalse(future.cancelRequest());
    }

    @Test
    void failingAtomicEffectFailsWithoutSemanticCommitment() throws Exception {
        Fixture x = fixture();
        ProtosFutureValue future =
                x.flow.remove(x.activation, path(x.prelude, "staging"));
        Invocation invocation = x.backend.invocations.remove();

        assertFalse(
                invocation.completion.commitPortableEffect(
                        () -> {
                            throw new IOException("simulated uncommitted backend failure");
                        }));

        assertEquals(ProtosFutureValue.State.FAILED, future.state());
        assertSame(
                x.prelude.bindings().readLocalSlot("IOError").orElseThrow(),
                future.failedError().orElseThrow().parent().orElseThrow());
    }

    @Test
    void cancellationCannotSplitSuccessfulEffectFromItsCommitment() throws Exception {
        Fixture x = fixture();
        ProtosFutureValue future =
                x.flow.replace(
                        x.activation,
                        path(x.prelude, "source"),
                        path(x.prelude, "target"));
        Invocation invocation = x.backend.invocations.remove();

        CountDownLatch effectEntered = new CountDownLatch(1);
        CountDownLatch releaseEffect = new CountDownLatch(1);
        AtomicBoolean commitResult = new AtomicBoolean();
        AtomicBoolean cancelResult = new AtomicBoolean(true);
        AtomicReference<Throwable> workerFailure = new AtomicReference<>();

        Thread effectThread =
                new Thread(
                        () -> {
                            try {
                                commitResult.set(
                                        invocation.completion.commitPortableEffect(
                                                () -> {
                                                    effectEntered.countDown();
                                                    assertTrue(
                                                            releaseEffect.await(
                                                                    5, TimeUnit.SECONDS));
                                                }));
                            } catch (Throwable failure) {
                                workerFailure.set(failure);
                            }
                        });
        Thread cancellationThread =
                new Thread(() -> cancelResult.set(future.cancelRequest()));

        effectThread.start();
        assertTrue(effectEntered.await(5, TimeUnit.SECONDS));
        cancellationThread.start();

        // The effect owns the lifecycle monitor here. BLOCKED therefore proves that
        // cancelRequest() already observed the pending Future and is contending for the
        // same producer-side commitment monitor before the effect is allowed to finish.
        long cancellationDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (cancellationThread.getState() != Thread.State.BLOCKED
                && System.nanoTime() < cancellationDeadline) {
            Thread.sleep(1);
        }
        assertEquals(
                Thread.State.BLOCKED,
                cancellationThread.getState(),
                "cancellation did not reach the lifecycle commitment monitor");
        releaseEffect.countDown();

        effectThread.join(5000);
        cancellationThread.join(5000);
        assertFalse(effectThread.isAlive());
        assertFalse(cancellationThread.isAlive());
        if (workerFailure.get() != null) {
            throw new AssertionError(workerFailure.get());
        }

        assertTrue(commitResult.get());
        // cancelRequest() reports that a request was issued while the Future was still pending;
        // the producer-side commitment lock still decides whether cancellation actually wins.
        assertTrue(cancelResult.get());
        assertEquals(0, invocation.cancellations.get());
        assertEquals(ProtosFutureValue.State.RESOLVED, future.state());
        assertSame(x.filesystem, future.resolvedValue().orElseThrow());
    }

    @Test
    void successfulNamespaceNoOpStillUsesFreshFutureAndExactReceiver() throws Exception {
        Fixture x = fixture();
        ProtosPathValue path = path(x.prelude, "same");
        ProtosFutureValue first = x.flow.replace(x.activation, path, path);
        ProtosFutureValue second = x.flow.replace(x.activation, path, path);
        Invocation firstInvocation = x.backend.invocations.remove();
        Invocation secondInvocation = x.backend.invocations.remove();

        firstInvocation.completion.succeeded();
        secondInvocation.completion.succeeded();

        assertFalse(first == second);
        assertSame(x.filesystem, first.resolvedValue().orElseThrow());
        assertSame(x.filesystem, second.resolvedValue().orElseThrow());
    }

    private static Fixture fixture() throws Exception {
        ProtosPrelude prelude = new ProtosCoreBootstrap().bootstrap(CORE);
        ProtosActivation activation = prelude.newModuleActivation();
        ProtosFilesystemValue filesystem = new ProtosFilesystemValue();
        RecordingBackend backend = new RecordingBackend();
        ProtosFilesystemNamespaceMutationFlow flow =
                new ProtosFilesystemNamespaceMutationFlow(
                        filesystem, activation, backend::replace, backend::remove);
        return new Fixture(prelude, activation, filesystem, backend, flow);
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

    private record Fixture(
            ProtosPrelude prelude,
            ProtosActivation activation,
            ProtosFilesystemValue filesystem,
            RecordingBackend backend,
            ProtosFilesystemNamespaceMutationFlow flow) {}

    private static final class RecordingBackend {
        private final AtomicInteger replaceCalls = new AtomicInteger();
        private final AtomicInteger removeCalls = new AtomicInteger();
        private final ArrayDeque<Invocation> invocations = new ArrayDeque<>();

        private ProtosFilesystemNamespaceMutationFlow.Cancellation replace(
                ProtosPathComponents sourcePath,
                ProtosPathComponents targetPath,
                ProtosFilesystemNamespaceMutationFlow.MutationCompletion completion) {
            replaceCalls.incrementAndGet();
            Invocation invocation = new Invocation(List.of(sourcePath, targetPath), completion);
            invocations.add(invocation);
            return invocation.cancellations::incrementAndGet;
        }

        private ProtosFilesystemNamespaceMutationFlow.Cancellation remove(
                ProtosPathComponents path,
                ProtosFilesystemNamespaceMutationFlow.MutationCompletion completion) {
            removeCalls.incrementAndGet();
            Invocation invocation = new Invocation(List.of(path), completion);
            invocations.add(invocation);
            return invocation.cancellations::incrementAndGet;
        }
    }

    private static final class Invocation {
        private final List<ProtosPathComponents> paths;
        private final ProtosFilesystemNamespaceMutationFlow.MutationCompletion completion;
        private final AtomicInteger cancellations = new AtomicInteger();

        private Invocation(
                List<ProtosPathComponents> paths,
                ProtosFilesystemNamespaceMutationFlow.MutationCompletion completion) {
            this.paths = paths;
            this.completion = completion;
        }
    }
}
