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

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/** D047/PLAT003 host-neutral asynchronous Network.connectTcp acquisition flow. */
public final class ProtosNetworkConnectFlow {
    @FunctionalInterface
    public interface Cancellation {
        void cancel();
    }

    /** Backend completion of one already-recognized endpoint acquisition. */
    public interface ConnectCompletion {
        void succeeded(
                Object resourceState,
                ProtosHostIpEndpoint localEndpoint,
                ProtosTcpConnectionFlow.Backend connectionBackend,
                Runnable releaseIfUntransferred);

        void failed();
    }

    /**
     * Authority-target contract consumed by C4. Implementations may use any concrete network
     * mechanism later selected by I028-E; that mechanism is not part of this interface.
     */
    @FunctionalInterface
    public interface Backend {
        Cancellation connect(ProtosHostIpEndpoint endpoint, ConnectCompletion completion);
    }

    /** Materializes one acquired backend descriptor into the standard live capability. */
    @FunctionalInterface
    public interface ResultMaterializer {
        ProtosTcpConnectionValue materialize(
                ProtosActivation activation,
                ProtosObjectValue requestedEndpoint,
                Object resourceState,
                ProtosHostIpEndpoint localEndpoint,
                ProtosTcpConnectionFlow.Backend connectionBackend);
    }

    private final ProtosActorExecutionDomain domain;
    private final Backend backend;
    private final ResultMaterializer materializer;
    private final ProtosIoLifecycle operationLifecycle;

    public ProtosNetworkConnectFlow(
            Object networkCapability,
            ProtosActivation constructionActivation,
            Backend backend,
            ResultMaterializer materializer) {
        Objects.requireNonNull(networkCapability, "networkCapability");
        Objects.requireNonNull(constructionActivation, "constructionActivation");
        this.domain = constructionActivation.executionDomain();
        this.backend = Objects.requireNonNull(backend, "backend");
        this.materializer = Objects.requireNonNull(materializer, "materializer");
        this.operationLifecycle =
                new ProtosIoLifecycle(
                        networkCapability,
                        constructionActivation.prelude().orElseThrow().futurePrototype(),
                        domain,
                        completion -> completion.succeeded());
    }

    /** Starts one independent acquisition after all caller-visible endpoint validation has completed. */
    public ProtosFutureValue connect(
            ProtosActivation activation,
            ProtosObjectValue requestedEndpoint,
            ProtosHostIpEndpoint hostEndpoint) {
        Objects.requireNonNull(activation, "activation");
        Objects.requireNonNull(requestedEndpoint, "requestedEndpoint");
        Objects.requireNonNull(hostEndpoint, "hostEndpoint");
        requireDomain(activation);

        ProtosIoOperation operation = operationLifecycle.beginOperation(activation);
        if (!operation.future().isPending()) {
            return operation.future();
        }

        Invocation invocation = new Invocation(operation);
        operation.onCancellation(invocation::requestCancellation);

        ConnectCompletion completion =
                new ConnectCompletion() {
                    @Override
                    public void succeeded(
                            Object resourceState,
                            ProtosHostIpEndpoint localEndpoint,
                            ProtosTcpConnectionFlow.Backend connectionBackend,
                            Runnable releaseIfUntransferred) {
                        Objects.requireNonNull(
                                releaseIfUntransferred, "releaseIfUntransferred");
                        if (!operation.committed() && !operation.commit()) {
                            releaseSafely(releaseIfUntransferred);
                            return;
                        }
                        invocation.deliver(
                                () -> publish(
                                        activation, requestedEndpoint, operation,
                                        resourceState, localEndpoint,
                                        connectionBackend, releaseIfUntransferred),
                                releaseIfUntransferred);
                    }

                    @Override
                    public void failed() {
                        invocation.deliver(
                                () -> operation.fail(ioError(activation)), () -> {});
                    }
                };

        invocation.enterBackendCall();
        try {
            invocation.install(backend.connect(hostEndpoint, completion));
        } catch (RuntimeException backendFailure) {
            operation.fail(ioError(activation));
        } finally {
            invocation.exitBackendCall();
        }
        return operation.future();
    }

    /** Actor-owned guest construction and Future result publication. */
    private boolean publish(
            ProtosActivation activation,
            ProtosObjectValue requestedEndpoint,
            ProtosIoOperation operation,
            Object resourceState,
            ProtosHostIpEndpoint localEndpoint,
            ProtosTcpConnectionFlow.Backend connectionBackend,
            Runnable releaseIfUntransferred) {
        if (operation.terminal()) {
            releaseSafely(releaseIfUntransferred);
            return false;
        }

        ProtosTcpConnectionValue connection;
        try {
            connection = Objects.requireNonNull(
                    materializer.materialize(
                            activation, requestedEndpoint,
                            Objects.requireNonNull(resourceState, "resourceState"),
                            Objects.requireNonNull(localEndpoint, "localEndpoint"),
                            Objects.requireNonNull(connectionBackend, "connectionBackend")),
                    "materialized connection");
        } catch (RuntimeException invalidHostResult) {
            releaseSafely(releaseIfUntransferred);
            return operation.fail(ioError(activation));
        }

        if (!operation.resolve(connection)) {
            releaseSafely(releaseIfUntransferred);
            return false;
        }
        return true;
    }

    private void requireDomain(ProtosActivation activation) {
        if (activation.executionDomain() != domain) {
            throw new IllegalArgumentException(
                    "Network connect flow belongs to another Actor domain");
        }
    }

    private static ProtosObjectValue ioError(ProtosActivation activation) {
        return ProtosCoreErrors.newOccurrence(
                activation, ProtosCoreErrors.StandardError.I_O_ERROR);
    }

    private static void releaseSafely(Runnable release) {
        try {
            release.run();
        } catch (RuntimeException ignored) {
            // No untransferred backend resource may become a second portable outcome.
        }
    }

    /** Owns cancellation, targeted handoff and pending host custody. */
    private final class Invocation {
        private final ProtosIoOperation operation;
        private final List<AtomicReference<Runnable>> pending = new ArrayList<>();
        private volatile Thread synchronousCaller;
        private Cancellation cancellation;
        private boolean cancellationRequested;

        Invocation(ProtosIoOperation operation) {
            this.operation = operation;
        }

        void enterBackendCall() {
            synchronousCaller = Thread.currentThread();
        }

        void exitBackendCall() {
            synchronousCaller = null;
        }

        void deliver(BooleanSupplier action, Runnable releaseOnDrop) {
            if (synchronousCaller == Thread.currentThread()) {
                action.getAsBoolean();
                return;
            }

            AtomicReference<Runnable> delivery =
                    new AtomicReference<>(releaseOnDrop);
            synchronized (this) {
                pending.add(delivery);
            }
            try {
                domain.enqueueTargetedFutureCompletionForRuntime(
                    operation.future(),
                    () -> claim(delivery) && action.getAsBoolean(),
                    () -> {
                        if (claim(delivery)) releaseSafely(releaseOnDrop);
                    });
            } catch (IllegalStateException terminatingActor) {
                if (claim(delivery)) releaseSafely(releaseOnDrop);
            }
        }

        private boolean claim(AtomicReference<Runnable> delivery) {
            boolean claimed = delivery.getAndSet(null) != null;
            synchronized (this) {
                pending.remove(delivery);
            }
            return claimed;
        }

        void requestCancellation() {
            boolean terminating = domain.actorTerminationBegunForRuntime();
            Cancellation toCancel;
            List<AtomicReference<Runnable>> dropped = List.of();

            synchronized (this) {
                cancellationRequested = true;
                toCancel = cancellation;
                if (terminating) {
                    dropped = List.copyOf(pending);
                    pending.clear();
                }
            }

            if (toCancel != null) toCancel.cancel();
            for (AtomicReference<Runnable> delivery : dropped) {
                Runnable release = delivery.getAndSet(null);
                if (release != null) releaseSafely(release);
            }
        }

        void install(Cancellation installed) {
            boolean cancelNow;
            synchronized (this) {
                cancellation = installed;
                cancelNow = cancellationRequested && installed != null;
            }
            if (cancelNow) installed.cancel();
        }
    }

}
