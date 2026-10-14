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

import com.guillermomolina.protos.runtime.ProtosHostIpEndpoint;
import com.guillermomolina.protos.runtime.ProtosNetworkConnectFlow;
import com.guillermomolina.protos.runtime.ProtosNetworkListenFlow;
import com.guillermomolina.protos.runtime.ProtosObjectValue;
import com.guillermomolina.protos.runtime.ProtosPrelude;
import java.io.IOException;
import java.util.Objects;

/**
 * PLAT054-3E4: Context and Process custody of the default Network of one embedded Process
 * ({@code PROCESS_IO.md}, Embedding network grant and thread policy).
 *
 * <p>This object is the opaque authority target of the bootstrap-local {@code network}
 * capability; it exists only when the Context effectively allows socket access. It owns at most
 * one {@link ProtosNioNetworkHost} and materializes it on the first {@code connectTcp} or {@code
 * listenTcp} acquisition, so a granted but unused Network creates no poller, selector, or socket.
 * The poller is a host-only Java thread: it never enters the Context or runs guest code, which is
 * why it does not depend on the Context's guest thread-creation permission. Acquisitions hand
 * their results to the existing acquisition flows, which deliver them to the owning Actor domain.
 *
 * <p>Closing is idempotent and is reached by Process termination and by Context finalization or
 * disposal. It closes the NIO host, which drains admitted control actions and releases every
 * channel registered with its poller (listeners, connections, and in-flight acquisitions); pending
 * acquisitions then fail and late resources are released by the acquisition flows. Activation
 * and closing share one monitor, so no poller starts after close. The NIO host is closed outside
 * that monitor because closing joins the poller thread.
 */
final class ProtosEmbeddedNetworkCustody
        implements ProtosNetworkConnectFlow.Backend, ProtosNetworkListenFlow.Backend {
    /** Opens the NIO host on first use; replaceable only to exercise initialization failure. */
    @FunctionalInterface
    interface HostFactory {
        ProtosNioNetworkHost open() throws IOException;
    }

    private static final ProtosNetworkConnectFlow.Cancellation NO_CONNECT_CANCELLATION = () -> {};
    private static final ProtosNetworkListenFlow.Cancellation NO_LISTEN_CANCELLATION = () -> {};

    private final ProtosObjectValue addressPrototype;
    private final ProtosObjectValue integerPrototype;
    private final ProtosObjectValue endpointPrototype;
    private final HostFactory hostFactory;
    /* Guarded by this. */
    private ProtosNioNetworkHost host;
    private ProtosNioNetworkBackend backend;
    private boolean closed;
    private int activations;

    ProtosEmbeddedNetworkCustody(ProtosPrelude prelude) {
        this(prelude, ProtosNioNetworkHost::new);
    }

    ProtosEmbeddedNetworkCustody(ProtosPrelude prelude, HostFactory hostFactory) {
        Objects.requireNonNull(prelude, "prelude");
        // Fails bootstrap explicitly when the Prelude does not retain the canonical families.
        this.addressPrototype = prelude.ipAddressPrototypeForRuntime();
        this.integerPrototype = prelude.bigIntegerPrototype();
        this.endpointPrototype = prelude.ipEndpointPrototypeForRuntime();
        this.hostFactory = Objects.requireNonNull(hostFactory, "hostFactory");
    }

    @Override
    public ProtosNetworkConnectFlow.Cancellation connect(
            ProtosHostIpEndpoint endpoint, ProtosNetworkConnectFlow.ConnectCompletion completion) {
        Objects.requireNonNull(endpoint, "endpoint");
        Objects.requireNonNull(completion, "completion");
        ProtosNioNetworkBackend active = activeBackendOrNull();
        if (active == null) {
            completion.failed();
            return NO_CONNECT_CANCELLATION;
        }
        // A close racing past this point is observed by the closed poller as a failed acquisition.
        return active.connect(endpoint, completion);
    }

    @Override
    public ProtosNetworkListenFlow.Cancellation listen(
            ProtosNetworkListenFlow.ListenRequest request,
            ProtosNetworkListenFlow.ListenCompletion completion) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(completion, "completion");
        ProtosNioNetworkBackend active = activeBackendOrNull();
        if (active == null) {
            completion.failed();
            return NO_LISTEN_CANCELLATION;
        }
        return active.listen(request, completion);
    }

    /**
     * The backend, opening the NIO host on first use; {@code null} once closed or when the host
     * cannot be opened. A failed open retains nothing, so a later acquisition may try again.
     */
    private synchronized ProtosNioNetworkBackend activeBackendOrNull() {
        if (closed) {
            return null;
        }
        if (backend == null) {
            ProtosNioNetworkHost opened;
            try {
                opened = hostFactory.open();
            } catch (IOException | RuntimeException failure) {
                return null;
            }
            host = opened;
            activations++;
            backend = opened.backend(
                    addressPrototype, endpointPrototype, integerPrototype);
        }
        return backend;
    }

    /** Retires the NIO host, if any, and refuses every later acquisition. Idempotent. */
    void close() {
        ProtosNioNetworkHost retired;
        synchronized (this) {
            closed = true;
            retired = host;
            host = null;
            backend = null;
        }
        if (retired != null) {
            retired.close();
        }
    }

    /** How many NIO hosts this custody ever opened; at most one unless an open failed first. */
    synchronized int activationCountForTesting() {
        return activations;
    }

    synchronized boolean isClosedForTesting() {
        return closed;
    }

    /** The poller of the materialized NIO host, or {@code null} before first use and after close. */
    synchronized ProtosNioHostIoPoller pollerForTesting() {
        return host == null ? null : host.pollerForTesting();
    }
}
