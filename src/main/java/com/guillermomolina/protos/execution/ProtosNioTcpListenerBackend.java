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

import com.guillermomolina.protos.runtime.ProtosNumericValueSupport;
import com.guillermomolina.protos.runtime.ProtosObjectValue;
import com.guillermomolina.protos.runtime.ProtosTcpListenerFlow;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.channels.SelectionKey;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/** Physical NIO state for one logical TcpListener, including PLAT007 composites. */
final class ProtosNioTcpListenerBackend
        implements ProtosTcpListenerFlow.Backend, ProtosNioHostIoPoller.SelectionHandler {
    private static final ProtosTcpListenerFlow.Cancellation NO_CANCELLATION = () -> {};

    private final ProtosNioHostIoPoller poller;
    private final int ipVersion;
    private final List<ServerSocketChannel> channels;
    private final ProtosObjectValue addressPrototype;
    /*
     * I091: owner (the Prelude's BigInteger prototype, D197-C) of accepted address bits beyond
     * the signed-64 range; see the network backend.
     */
    private final ProtosObjectValue integerPrototype;
    private final ProtosObjectValue endpointPrototype;
    private final AtomicBoolean physicalClosed = new AtomicBoolean();
    // Poller-owned queue: cardinality is proportional only to admitted pending accepts.
    private final ArrayDeque<AcceptRequest> pendingAccepts = new ArrayDeque<>();
    private final ArrayList<SelectionKey> keys = new ArrayList<>();

    ProtosNioTcpListenerBackend(
            ProtosNioHostIoPoller poller,
            int ipVersion,
            List<ServerSocketChannel> channels,
            ProtosObjectValue addressPrototype,
            ProtosObjectValue endpointPrototype,
            ProtosObjectValue integerPrototype) {
        this.integerPrototype = integerPrototype;
        this.poller = Objects.requireNonNull(poller, "poller");
        if (ipVersion != 4 && ipVersion != 6) {
            throw new IllegalArgumentException("listener IP version must be 4 or 6");
        }
        this.ipVersion = ipVersion;
        this.channels = List.copyOf(Objects.requireNonNull(channels, "channels"));
        if (this.channels.isEmpty()) {
            throw new IllegalArgumentException("logical listener requires at least one channel");
        }
        for (ServerSocketChannel channel : this.channels) {
            Objects.requireNonNull(channel, "listener channel");
        }
        this.addressPrototype = Objects.requireNonNull(addressPrototype, "addressPrototype");
        this.endpointPrototype = Objects.requireNonNull(endpointPrototype, "endpointPrototype");
    }

    void attachKey(SelectionKey key) {
        SelectionKey supplied = Objects.requireNonNull(key, "key");
        if (!(supplied.channel() instanceof ServerSocketChannel server)
                || !channels.contains(server)) {
            throw new IllegalArgumentException("selection key does not belong to this listener");
        }
        keys.add(supplied);
    }

    void releaseIfUntransferred() {
        requestPhysicalClose(null);
    }

    @Override
    public ProtosTcpListenerFlow.Cancellation accept(
            ProtosTcpListenerFlow.AcceptCompletion completion) {
        Objects.requireNonNull(completion, "completion");
        if (physicalClosed.get()) {
            reportAcceptFailure(completion);
            return NO_CANCELLATION;
        }
        AcceptRequest request = new AcceptRequest(completion);
        try {
            poller.submit(
                    () -> startAcceptOnPoller(request),
                    ignored -> request.reportFailure());
        } catch (RuntimeException unavailablePoller) {
            request.reportFailure();
        }
        return request::cancel;
    }

    @Override
    public void close(ProtosTcpListenerFlow.CloseCompletion completion) {
        requestPhysicalClose(Objects.requireNonNull(completion, "completion"));
    }

    @Override
    public void ready(SelectionKey selectedKey) {
        if (!selectedKey.isValid() || !selectedKey.isAcceptable()) return;
        if (!(selectedKey.channel() instanceof ServerSocketChannel server)) {
            throw new IllegalStateException("listener key is not a ServerSocketChannel");
        }
        serviceAcceptsOnPoller(server);
    }

    @Override
    public void failed(Exception failure) {
        closePhysical();
    }

    @Override
    public void closed() {
        closePhysical();
    }

    private void startAcceptOnPoller(AcceptRequest request) {
        if (request.cancelled.get()) {
            request.retire();
            return;
        }
        if (physicalClosed.get()) {
            request.reportFailure();
            return;
        }
        pendingAccepts.addLast(request);
        serviceAlreadyQueuedConnectionsOnPoller();
        refreshAcceptInterest();
    }

    private void serviceAlreadyQueuedConnectionsOnPoller() {
        if (pendingAccepts.isEmpty()) return;
        for (ServerSocketChannel channel : channels) {
            if (pendingAccepts.isEmpty()) return;
            serviceAcceptsOnPoller(channel);
        }
    }

    private void serviceAcceptsOnPoller(ServerSocketChannel server) {
        while (!pendingAccepts.isEmpty() && !physicalClosed.get()) {
            AcceptRequest request = nextLiveRequest();
            if (request == null) break;

            final SocketChannel accepted;
            try {
                accepted = server.accept();
            } catch (IOException acceptFailure) {
                request.reportFailure();
                continue;
            }
            if (accepted == null) {
                pendingAccepts.addFirst(request);
                break;
            }

            if (request.cancelled.get()) {
                closeQuietly(accepted);
                request.retire();
                continue;
            }
            handoffAccepted(request, accepted);
        }
        refreshAcceptInterest();
    }

    private AcceptRequest nextLiveRequest() {
        for (;;) {
            AcceptRequest request = pendingAccepts.pollFirst();
            if (request == null) return null;
            if (!request.cancelled.get()) return request;
            request.retire();
        }
    }

    private void handoffAccepted(AcceptRequest request, SocketChannel accepted) {
        ProtosNioTcpConnectionBackend connectionBackend = null;
        try {
            Object local = accepted.getLocalAddress();
            Object remote = accepted.getRemoteAddress();
            if (!(local instanceof InetSocketAddress localSocket)
                    || !(remote instanceof InetSocketAddress remoteSocket)) {
                throw new IOException("accepted TCP connection has non-IP endpoint");
            }
            ProtosObjectValue localEndpoint = materializeEndpoint(localSocket);
            ProtosObjectValue remoteEndpoint = materializeEndpoint(remoteSocket);

            connectionBackend = new ProtosNioTcpConnectionBackend(poller, accepted);
            SelectionKey connectionKey = poller.register(accepted, 0, connectionBackend);
            connectionBackend.attachKey(connectionKey);

            if (request.cancelled.get()) {
                connectionBackend.releaseIfUntransferred();
                request.retire();
                return;
            }
            request.reportSuccess(
                    connectionBackend,
                    localEndpoint,
                    remoteEndpoint,
                    connectionBackend,
                    connectionBackend::releaseIfUntransferred);
        } catch (IOException | RuntimeException failure) {
            if (connectionBackend != null) connectionBackend.releaseIfUntransferred();
            else closeQuietly(accepted);
            request.reportFailure();
        }
    }

    private ProtosObjectValue materializeEndpoint(InetSocketAddress socket) throws IOException {
        InetAddress address = socket.getAddress();
        byte[] bytes = address == null ? null : address.getAddress();
        if (address == null || bytes == null || socket.getPort() <= 0) {
            throw new IOException("accepted TCP endpoint is incomplete");
        }
        if ((ipVersion == 4 && (!(address instanceof Inet4Address) || bytes.length != 4))
                || (ipVersion == 6 && (!(address instanceof Inet6Address) || bytes.length != 16))) {
            throw new IOException("accepted TCP endpoint changed listener IP version");
        }

        ProtosObjectValue logicalAddress = new ProtosObjectValue(addressPrototype);
        logicalAddress.createLocalSlot("version", ProtosNumericValueSupport.integer(ipVersion));
        logicalAddress.createLocalSlot(
                "bits",
                ProtosNumericValueSupport.integerFromUnsignedBigEndian(bytes, integerPrototype));
        logicalAddress.freeze();

        ProtosObjectValue endpoint = new ProtosObjectValue(endpointPrototype);
        endpoint.createLocalSlot("address", logicalAddress);
        endpoint.createLocalSlot("port", ProtosNumericValueSupport.integer(socket.getPort()));
        return endpoint.freeze();
    }

    private void cancelAcceptOnPoller(AcceptRequest request) {
        if (pendingAccepts.remove(request)) {
            request.retire();
            refreshAcceptInterest();
        }
    }

    private void refreshAcceptInterest() {
        boolean enable = !pendingAccepts.isEmpty() && !physicalClosed.get();
        for (SelectionKey key : List.copyOf(keys)) {
            if (!key.isValid()) continue;
            try {
                int current = key.interestOps();
                int desired = enable
                        ? current | SelectionKey.OP_ACCEPT
                        : current & ~SelectionKey.OP_ACCEPT;
                if (desired != current) poller.interestOps(key, desired);
            } catch (RuntimeException ignored) {
                // Whole-listener failure/close owns invalid component keys.
            }
        }
    }

    private void failPendingAccepts() {
        AcceptRequest request;
        while ((request = pendingAccepts.pollFirst()) != null) {
            request.reportFailure();
        }
    }

    private final class AcceptRequest {
        private final ProtosTcpListenerFlow.AcceptCompletion completion;
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private final AtomicBoolean reported = new AtomicBoolean();

        private AcceptRequest(ProtosTcpListenerFlow.AcceptCompletion completion) {
            this.completion = completion;
        }

        private void cancel() {
            if (!cancelled.compareAndSet(false, true) || reported.get()) return;
            try {
                poller.submit(
                        () -> cancelAcceptOnPoller(this),
                        ignored -> {
                            // Poller terminal cleanup owns a registered listener request.
                        });
            } catch (RuntimeException unavailablePoller) {
                // Poller shutdown invokes closed()/failed() for registered components.
            }
        }

        private void retire() {
            reported.compareAndSet(false, true);
        }

        private void reportSuccess(
                Object resourceState,
                ProtosObjectValue localEndpoint,
                ProtosObjectValue remoteEndpoint,
                ProtosNioTcpConnectionBackend connectionBackend,
                Runnable releaseIfUntransferred) {
            if (!reported.compareAndSet(false, true)) {
                releaseIfUntransferred.run();
                return;
            }
            try {
                completion.succeeded(
                        resourceState,
                        localEndpoint,
                        remoteEndpoint,
                        connectionBackend,
                        releaseIfUntransferred);
            } catch (RuntimeException completionFailure) {
                releaseIfUntransferred.run();
            }
        }

        private void reportFailure() {
            if (!reported.compareAndSet(false, true)) return;
            reportAcceptFailure(completion);
        }
    }

    private void requestPhysicalClose(ProtosTcpListenerFlow.CloseCompletion completion) {
        if (physicalClosed.get()) {
            if (completion != null) reportCloseSuccess(completion);
            return;
        }
        try {
            poller.submit(
                    () -> finishCloseOnPoller(completion),
                    ignored -> fallbackClose(completion));
        } catch (RuntimeException unavailablePoller) {
            fallbackClose(completion);
        }
    }

    private void finishCloseOnPoller(ProtosTcpListenerFlow.CloseCompletion completion) {
        boolean success = closePhysical();
        if (completion == null) return;
        if (success) reportCloseSuccess(completion);
        else reportCloseFailure(completion);
    }

    private void fallbackClose(ProtosTcpListenerFlow.CloseCompletion completion) {
        finishCloseOnPoller(completion);
    }

    private boolean closePhysical() {
        if (!physicalClosed.compareAndSet(false, true)) return true;
        for (SelectionKey key : List.copyOf(keys)) {
            try {
                key.cancel();
            } catch (RuntimeException ignored) {
                // Component may already have been invalidated by selector failure.
            }
        }
        failPendingAccepts();
        boolean success = true;
        for (ServerSocketChannel channel : channels) {
            try {
                channel.close();
            } catch (IOException closeFailure) {
                success = false;
            }
        }
        return success;
    }

    private static void reportAcceptFailure(ProtosTcpListenerFlow.AcceptCompletion completion) {
        try {
            completion.failed();
        } catch (RuntimeException ignored) {
            // A callback bug cannot create a second backend outcome.
        }
    }

    private static void reportCloseSuccess(ProtosTcpListenerFlow.CloseCompletion completion) {
        try {
            completion.succeeded();
        } catch (RuntimeException ignored) {
            // A callback bug cannot create a second backend outcome.
        }
    }

    private static void reportCloseFailure(ProtosTcpListenerFlow.CloseCompletion completion) {
        try {
            completion.failed();
        } catch (RuntimeException ignored) {
            // A callback bug cannot create a second backend outcome.
        }
    }

    private static void closeQuietly(java.nio.channels.Channel channel) {
        try {
            channel.close();
        } catch (IOException ignored) {
            // Late/untransferred resource cleanup cannot create another portable outcome.
        }
    }
}
