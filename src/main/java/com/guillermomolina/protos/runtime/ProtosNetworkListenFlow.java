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

import java.util.Objects;

/** D047/PLAT003 host-neutral asynchronous Network.listenTcp acquisition flow. */
public final class ProtosNetworkListenFlow {
    @FunctionalInterface
    public interface Cancellation { void cancel(); }

    /** Host-only capture: an absent address or port preserves listen-request null. */
    public record ListenRequest(int ipVersion, byte[] addressConstraint, Integer portConstraint) {
        public ListenRequest {
            if (ipVersion != 4 && ipVersion != 6) {
                throw new IllegalArgumentException("listen ipVersion must be 4 or 6");
            }
            if (addressConstraint != null) {
                if (addressConstraint.length != (ipVersion == 4 ? 4 : 16)) {
                    throw new IllegalArgumentException("invalid listen address octet width");
                }
                addressConstraint = addressConstraint.clone();
            }
            if (portConstraint != null
                    && (portConstraint < 1 || portConstraint > 65535)) {
                throw new IllegalArgumentException("listen port constraint must be in 1..65535");
            }
        }

        @Override
        public byte[] addressConstraint() {
            return addressConstraint == null ? null : addressConstraint.clone();
        }

        public boolean hasAddressConstraint() { return addressConstraint != null; }
        public boolean hasPortConstraint() { return portConstraint != null; }
    }

    public interface ListenCompletion {
        void succeeded(
                Object resourceState,
                int localPort,
                ProtosTcpListenerFlow.Backend listenerBackend,
                Runnable releaseIfUntransferred);
        void failed();
    }

    /** Backend-neutral authority contract. I028-E may implement it with any concrete transport. */
    @FunctionalInterface
    public interface Backend {
        Cancellation listen(ListenRequest request, ListenCompletion completion);
    }

    @FunctionalInterface
    public interface ResultMaterializer {
        ProtosTcpListenerValue materialize(
                ProtosActivation activation,
                ListenRequest request,
                Object resourceState,
                int localPort,
                ProtosTcpListenerFlow.Backend listenerBackend);
    }

    private final ProtosActorExecutionDomain domain;
    private final Backend backend;
    private final ResultMaterializer materializer;
    private final ProtosIoLifecycle operationLifecycle;

    public ProtosNetworkListenFlow(
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

    public ProtosFutureValue listen(ProtosActivation activation, ListenRequest request) {
        Objects.requireNonNull(activation, "activation");
        Objects.requireNonNull(request, "request");
        requireDomain(activation);

        ProtosIoOperation operation = operationLifecycle.beginOperation(activation);
        if (!operation.future().isPending()) return operation.future();

        ProtosNetworkAcquisitionInvocation invocation =
                new ProtosNetworkAcquisitionInvocation(domain, operation);
        operation.onCancellation(invocation::requestCancellation);

        ListenCompletion completion = new ListenCompletion() {
            @Override
            public void succeeded(
                    Object resourceState,
                    int localPort,
                    ProtosTcpListenerFlow.Backend listenerBackend,
                    Runnable releaseIfUntransferred) {
                Objects.requireNonNull(releaseIfUntransferred, "releaseIfUntransferred");
                if (!operation.committed() && !operation.commit()) {
                    releaseSafely(releaseIfUntransferred);
                    return;
                }
                invocation.deliver(() -> {
                    if (operation.terminal()) {
                        releaseSafely(releaseIfUntransferred);
                        return false;
                    }
                    try {
                        ProtosTcpListenerValue listener = Objects.requireNonNull(
                                materializer.materialize(
                                        activation, request,
                                        Objects.requireNonNull(resourceState, "resourceState"),
                                        localPort,
                                        Objects.requireNonNull(listenerBackend, "listenerBackend")),
                                "materialized listener");
                        if (operation.resolve(listener)) return true;
                    } catch (RuntimeException invalidHostResult) {
                        releaseSafely(releaseIfUntransferred);
                        return operation.fail(ioError(activation));
                    }
                    releaseSafely(releaseIfUntransferred);
                    return false;
                }, releaseIfUntransferred);
            }

            @Override
            public void failed() {
                invocation.deliver(
                        () -> operation.fail(ioError(activation)), () -> {});
            }
        };

        invocation.enterBackendCall();
        try {
            Cancellation cancellation = backend.listen(request, completion);
            invocation.install(cancellation == null ? null : cancellation::cancel);
        } catch (RuntimeException backendFailure) {
            operation.fail(ioError(activation));
        } finally {
            invocation.exitBackendCall();
        }
        return operation.future();
    }

    private void requireDomain(ProtosActivation activation) {
        if (activation.executionDomain() != domain) {
            throw new IllegalArgumentException("Network listen flow belongs to another Actor domain");
        }
    }

    private static ProtosObjectValue ioError(ProtosActivation activation) {
        return ProtosCoreErrors.newOccurrence(activation, ProtosCoreErrors.StandardError.I_O_ERROR);
    }

    private static void releaseSafely(Runnable release) {
        try { release.run(); }
        catch (RuntimeException ignored) { /* untransferred custody cannot create a second outcome */ }
    }


}
