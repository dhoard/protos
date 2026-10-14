/*
 * THE LICENSED WORK IS PROVIDED UNDER THE TERMS OF THE ADAPTIVE PUBLIC LICENSE
 * ("LICENSE") AS FIRST COMPLETED BY: Guillermo Adrián Molina. ANY USE, PUBLIC
 * DISPLAY, PUBLIC PERFORMANCE, REPRODUCTION OR DISTRIBUTION OF, OR PREPARATION OF
 * DERIVATIVE WORKS BASED ON, THE LICENSED WORK CONSTITUTES RECIPIENT'S ACCEPTANCE
 * OF THIS LICENSE AND ITS TERMS, WHETHER OR NOT SUCH RECIPIENT READS THE TERMS.
 * "LICENSED WORK" AND "RECIPIENT" ARE DEFINED IN THE LICENSE. A COPY OF THE
 * LICENSE IS LOCATED IN THE TEXT FILE ENTITLED "LICENSE.TXT" ACCOMPANYING THE
 * CONTENTS OF THIS FILE. IF A COPY OF THE LICENSE DOES NOT ACCOMPANY THIS FILE, A
 * COPY OF THE LICENSE MAY ALSO BE OBTAINED AT THE FOLLOWING WEB SITE:
 * https://github.com/guillermomolina/protos
 *
 * Software distributed under the License is distributed on an "AS IS" basis,
 * WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License for
 * the specific language governing rights and limitations under the License.
 */
package com.guillermomolina.protos.execution;

import com.guillermomolina.protos.runtime.ProtosNumericValueSupport;
import com.guillermomolina.protos.runtime.ProtosHostIpEndpoint;
import com.guillermomolina.protos.runtime.ProtosNetworkConnectFlow;
import com.guillermomolina.protos.runtime.ProtosNetworkListenFlow;
import com.guillermomolina.protos.runtime.ProtosObjectValue;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.StandardProtocolFamily;
import java.nio.channels.SelectionKey;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

/** I028-E2 production NIO implementation of the existing host-neutral connect contract. */
final class ProtosNioNetworkBackend
        implements ProtosNetworkConnectFlow.Backend, ProtosNetworkListenFlow.Backend {
    @FunctionalInterface
    interface Ipv6ScopeResolver {
        Inet6Address resolve(byte[] addressBytes) throws IOException;
    }

    @FunctionalInterface
    interface Ipv6ListenAddressProvider {
        List<Inet6Address> authorizedAddresses() throws IOException;
    }

    private static final ProtosNetworkConnectFlow.Cancellation NO_CANCELLATION = () -> {};
    private static final ProtosNetworkListenFlow.Cancellation NO_LISTEN_CANCELLATION = () -> {};

    private final ProtosNioHostIoPoller poller;
    private final Ipv6ScopeResolver ipv6ScopeResolver;
    private final ProtosObjectValue listenAddressPrototype;
    private final ProtosObjectValue listenEndpointPrototype;
    private final Ipv6ListenAddressProvider ipv6ListenAddressProvider;

    /** Listener/accept materialization only; never used by TCP connect. */
    private final ProtosObjectValue integerPrototype;

    ProtosNioNetworkBackend(
            ProtosNioHostIoPoller poller, Ipv6ScopeResolver ipv6ScopeResolver) {
        this(poller, ipv6ScopeResolver, null, null, () -> List.of(), null);
    }

    ProtosNioNetworkBackend(
            ProtosNioHostIoPoller poller,
            Ipv6ScopeResolver ipv6ScopeResolver,
            ProtosObjectValue listenAddressPrototype,
            ProtosObjectValue listenEndpointPrototype,
            Ipv6ListenAddressProvider ipv6ListenAddressProvider) {
        this(poller, ipv6ScopeResolver, listenAddressPrototype,
                listenEndpointPrototype, ipv6ListenAddressProvider, null);
    }

    ProtosNioNetworkBackend(
            ProtosNioHostIoPoller poller,
            Ipv6ScopeResolver ipv6ScopeResolver,
            ProtosObjectValue listenAddressPrototype,
            ProtosObjectValue listenEndpointPrototype,
            Ipv6ListenAddressProvider ipv6ListenAddressProvider,
            ProtosObjectValue integerPrototype) {
        this.integerPrototype = integerPrototype;
        this.poller = Objects.requireNonNull(poller, "poller");
        this.ipv6ScopeResolver =
                Objects.requireNonNull(ipv6ScopeResolver, "ipv6ScopeResolver");
        if ((listenAddressPrototype == null) != (listenEndpointPrototype == null)) {
            throw new IllegalArgumentException(
                    "listener address/endpoint prototypes must be supplied together");
        }
        this.listenAddressPrototype = listenAddressPrototype;
        this.listenEndpointPrototype = listenEndpointPrototype;
        this.ipv6ListenAddressProvider =
                Objects.requireNonNull(ipv6ListenAddressProvider, "ipv6ListenAddressProvider");
    }

    @Override
    public ProtosNetworkConnectFlow.Cancellation connect(
            ProtosHostIpEndpoint endpoint,
            ProtosNetworkConnectFlow.ConnectCompletion completion) {
        Objects.requireNonNull(endpoint, "endpoint");
        Objects.requireNonNull(completion, "completion");

        HostEndpoint target;
        try {
            target = HostEndpoint.decode(endpoint, ipv6ScopeResolver);
        } catch (IOException | RuntimeException invalidHostInterpretation) {
            reportFailure(completion);
            return NO_CANCELLATION;
        }

        ConnectAttempt attempt = new ConnectAttempt(target, completion);
        try {
            poller.submit(attempt::startOnPoller, attempt::commandFailed);
        } catch (RuntimeException unavailablePoller) {
            attempt.commandFailed(unavailablePoller);
        }
        return attempt::cancel;
    }

    @Override
    public ProtosNetworkListenFlow.Cancellation listen(
            ProtosNetworkListenFlow.ListenRequest request,
            ProtosNetworkListenFlow.ListenCompletion completion) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(completion, "completion");
        if (listenAddressPrototype == null || listenEndpointPrototype == null) {
            reportListenFailure(completion);
            return NO_LISTEN_CANCELLATION;
        }
        ListenAttempt attempt = new ListenAttempt(request, completion);
        try {
            poller.submit(attempt::startOnPoller, attempt::commandFailed);
        } catch (RuntimeException unavailablePoller) {
            attempt.commandFailed(unavailablePoller);
        }
        return attempt::cancel;
    }

    private final class ListenAttempt {
        private static final int ACQUIRING = 0;
        private static final int CANCELLED = 1;
        private static final int HANDED_OFF = 2;
        private static final int FAILED = 3;

        private final ProtosNetworkListenFlow.ListenRequest request;
        private final ProtosNetworkListenFlow.ListenCompletion completion;
        private final AtomicInteger gate = new AtomicInteger(ACQUIRING);
        private final ArrayList<ServerSocketChannel> channels = new ArrayList<>();
        private volatile ProtosNioTcpListenerBackend listenerBackend;

        private ListenAttempt(
                ProtosNetworkListenFlow.ListenRequest request,
                ProtosNetworkListenFlow.ListenCompletion completion) {
            this.request = request;
            this.completion = completion;
        }

        private void startOnPoller() {
            if (gate.get() != ACQUIRING) return;
            try {
                ListenPlan plan = decodeListenRequest(request);
                if (gate.get() != ACQUIRING) return;

                int localPort = plan.requestedPort();
                for (InetAddress address : plan.addresses()) {
                    if (gate.get() != ACQUIRING) {
                        closePartialChannels();
                        return;
                    }
                    ServerSocketChannel channel = ServerSocketChannel.open(plan.protocolFamily());
                    channel.configureBlocking(false);
                    channels.add(channel);
                    channel.bind(new InetSocketAddress(address, localPort));
                    Object local = channel.getLocalAddress();
                    if (!(local instanceof InetSocketAddress localSocket)
                            || localSocket.getPort() <= 0) {
                        throw new IOException("listener component has invalid local port");
                    }
                    if (localPort == 0) localPort = localSocket.getPort();
                    else if (localSocket.getPort() != localPort) {
                        throw new IOException("listener components acquired different ports");
                    }
                }
                if (localPort <= 0 || channels.isEmpty()) {
                    throw new IOException("listener acquisition produced no usable component");
                }

                ProtosNioTcpListenerBackend acquired =
                        new ProtosNioTcpListenerBackend(
                                poller,
                                plan.ipVersion(),
                                channels,
                                listenAddressPrototype,
                                listenEndpointPrototype,
                                integerPrototype);
                listenerBackend = acquired;
                for (ServerSocketChannel channel : channels) {
                    SelectionKey key = poller.register(channel, 0, acquired);
                    acquired.attachKey(key);
                }

                if (!gate.compareAndSet(ACQUIRING, HANDED_OFF)) {
                    acquired.releaseIfUntransferred();
                    return;
                }
                try {
                    completion.succeeded(
                            acquired,
                            localPort,
                            acquired,
                            acquired::releaseIfUntransferred);
                } catch (RuntimeException completionFailure) {
                    acquired.releaseIfUntransferred();
                }
            } catch (IOException | RuntimeException failure) {
                fail(failure);
            }
        }

        private void cancel() {
            if (!gate.compareAndSet(ACQUIRING, CANCELLED)) return;
            try {
                poller.submit(
                        this::releaseCancelledOnPoller,
                        ignored -> releaseCancelledFallback());
            } catch (RuntimeException unavailablePoller) {
                releaseCancelledFallback();
            }
        }

        private void commandFailed(RuntimeException failure) {
            fail(failure);
        }

        private void fail(Exception failure) {
            if (gate.compareAndSet(ACQUIRING, FAILED)) {
                releaseCurrent();
                reportListenFailure(completion);
            } else if (gate.get() == CANCELLED) {
                releaseCurrent();
            }
        }

        private void releaseCancelledOnPoller() {
            releaseCurrent();
        }

        private void releaseCancelledFallback() {
            releaseCurrent();
        }

        private void releaseCurrent() {
            ProtosNioTcpListenerBackend acquired = listenerBackend;
            if (acquired != null) {
                acquired.releaseIfUntransferred();
                return;
            }
            closePartialChannels();
        }

        private void closePartialChannels() {
            for (ServerSocketChannel channel : List.copyOf(channels)) {
                try {
                    channel.close();
                } catch (IOException ignored) {
                    // Partial acquisition cleanup cannot create a portable result.
                }
            }
        }
    }

    private ListenPlan decodeListenRequest(ProtosNetworkListenFlow.ListenRequest request)
            throws IOException {
        int version = request.ipVersion();
        StandardProtocolFamily family =
                version == 4 ? StandardProtocolFamily.INET : StandardProtocolFamily.INET6;
        int requestedPort =
                request.portConstraint() == null ? 0 : request.portConstraint();

        if (request.addressConstraint() != null) {
            InetAddress exact = decodeListenAddress(request.addressConstraint(), version);
            if (version == 6 && !isSafeConcreteIpv6(exact)) {
                throw new IOException(
                        "public-JDK backend cannot prove IPv6-only semantics for explicit address");
            }
            return new ListenPlan(version, family, List.of(exact), requestedPort);
        }

        if (version == 4) {
            InetAddress wildcard = InetAddress.getByAddress(new byte[4]);
            if (!(wildcard instanceof Inet4Address)) {
                throw new IOException("IPv4 wildcard did not materialize as IPv4");
            }
            return new ListenPlan(version, family, List.of(wildcard), requestedPort);
        }

        List<Inet6Address> supplied =
                Objects.requireNonNull(
                        ipv6ListenAddressProvider.authorizedAddresses(),
                        "authorized IPv6 listener address snapshot");
        if (supplied.isEmpty()) {
            throw new IOException("Network authority exposes no concrete IPv6 listener address");
        }
        ArrayList<InetAddress> concrete = new ArrayList<>(supplied.size());
        for (Inet6Address address : supplied) {
            Inet6Address candidate = Objects.requireNonNull(address, "authorized IPv6 address");
            if (!isSafeConcreteIpv6(candidate)) {
                throw new IOException("authorized IPv6 listener set contains non-concrete address");
            }
            concrete.add(candidate);
        }
        return new ListenPlan(version, family, List.copyOf(concrete), requestedPort);
    }

    private InetAddress decodeListenAddress(ProtosObjectValue address, int expectedVersion)
            throws IOException {
        if (!ProtosStandardIpAddressProtocol.recognizesValue(address, listenAddressPrototype)) {
            throw new IOException("listen address is not a recognized IpAddress");
        }
        Map<String, Object> slots = address.localSlotsSnapshot();
        Object versionValue = slots.get("version");
        Object bitsValue = slots.get("bits");
        if (!ProtosNumericValueSupport.isIntegerInIntRange(versionValue)
                || !ProtosNumericValueSupport.isCurrentInteger(bitsValue)
                || ProtosNumericValueSupport.exactInt(versionValue) != expectedVersion) {
            throw new IOException("listen address changed request IP version");
        }
        if (expectedVersion == 4) {
            byte[] bytes = HostEndpoint.unsignedBytes(bitsValue, 4);
            InetAddress result = InetAddress.getByAddress(bytes);
            if (!(result instanceof Inet4Address)) {
                throw new IOException("IPv4 listen address did not materialize as IPv4");
            }
            return result;
        }

        byte[] bytes = HostEndpoint.unsignedBytes(bitsValue, 16);
        Inet6Address resolved =
                Objects.requireNonNull(
                        ipv6ScopeResolver.resolve(bytes.clone()),
                        "IPv6 scope resolver result");
        if (!Arrays.equals(bytes, resolved.getAddress())) {
            throw new IOException("Network scope interpretation changed IPv6 address bits");
        }
        if (resolved.isLinkLocalAddress()
                && resolved.getScopeId() == 0
                && resolved.getScopedInterface() == null) {
            throw new IOException("link-local IPv6 requires explicit Network scope");
        }
        return resolved;
    }

    private static boolean isSafeConcreteIpv6(InetAddress address) {
        if (!(address instanceof Inet6Address ipv6) || ipv6.isAnyLocalAddress()) return false;
        return !isIpv4Mapped(ipv6.getAddress());
    }

    private static boolean isIpv4Mapped(byte[] bytes) {
        if (bytes.length != 16) return false;
        for (int i = 0; i < 10; i++) if (bytes[i] != 0) return false;
        return (bytes[10] & 0xff) == 0xff && (bytes[11] & 0xff) == 0xff;
    }

    private record ListenPlan(
            int ipVersion,
            StandardProtocolFamily protocolFamily,
            List<InetAddress> addresses,
            int requestedPort) {}

    private final class ConnectAttempt implements ProtosNioHostIoPoller.SelectionHandler {
        private static final int ACQUIRING = 0;
        private static final int CANCELLED = 1;
        private static final int HANDED_OFF = 2;
        private static final int FAILED = 3;

        private final HostEndpoint target;
        private final ProtosNetworkConnectFlow.ConnectCompletion completion;
        private final AtomicInteger gate = new AtomicInteger(ACQUIRING);
        private volatile SocketChannel channel;
        private volatile ProtosNioTcpConnectionBackend connectionBackend;

        private ConnectAttempt(
                HostEndpoint target, ProtosNetworkConnectFlow.ConnectCompletion completion) {
            this.target = target;
            this.completion = completion;
        }

        private void startOnPoller() {
            if (gate.get() != ACQUIRING) return;
            try {
                SocketChannel opened = SocketChannel.open(target.protocolFamily());
                opened.configureBlocking(false);
                channel = opened;
                ProtosNioTcpConnectionBackend acquired =
                        new ProtosNioTcpConnectionBackend(poller, opened);
                connectionBackend = acquired;

                if (gate.get() != ACQUIRING) {
                    acquired.releaseIfUntransferred();
                    return;
                }

                boolean connected = opened.connect(target.socketAddress());
                if (gate.get() != ACQUIRING) {
                    acquired.releaseIfUntransferred();
                    return;
                }

                if (connected) {
                    SelectionKey key = poller.register(opened, 0, acquired);
                    acquired.attachKey(key);
                    completeConnected();
                } else {
                    SelectionKey key =
                            poller.register(opened, SelectionKey.OP_CONNECT, this);
                    acquired.attachKey(key);
                    if (gate.get() != ACQUIRING) acquired.releaseIfUntransferred();
                }
            } catch (IOException | RuntimeException failure) {
                fail(failure);
            }
        }

        @Override
        public void ready(SelectionKey key) {
            if (gate.get() != ACQUIRING) {
                releaseCurrent();
                return;
            }
            if (!key.isConnectable()) return;

            try {
                SocketChannel current = channel;
                if (current == null || !current.finishConnect()) return;
                ProtosNioTcpConnectionBackend acquired = connectionBackend;
                if (acquired == null) {
                    throw new IllegalStateException("connected channel has no backend");
                }
                poller.interestOps(key, 0);
                key.attach(acquired);
                completeConnected();
            } catch (IOException | RuntimeException failure) {
                fail(failure);
            }
        }

        @Override
        public void failed(Exception failure) {
            fail(failure);
        }

        @Override
        public void closed() {
            fail(new IOException("NIO poller closed during TCP connect"));
        }

        private void completeConnected() {
            ProtosNioTcpConnectionBackend acquired = connectionBackend;
            SocketChannel current = channel;
            if (acquired == null || current == null) {
                fail(new IllegalStateException("incomplete connected-channel state"));
                return;
            }

            ProtosHostIpEndpoint localEndpoint;
            try {
                Object local = current.getLocalAddress();
                if (!(local instanceof InetSocketAddress localSocket)) {
                    throw new IOException("connected TCP socket has no IP local endpoint");
                }
                localEndpoint = target.captureLocalEndpoint(localSocket);
            } catch (IOException | RuntimeException failure) {
                fail(failure);
                return;
            }

            if (!gate.compareAndSet(ACQUIRING, HANDED_OFF)) {
                acquired.releaseIfUntransferred();
                return;
            }

            try {
                completion.succeeded(
                        acquired,
                        localEndpoint,
                        acquired,
                        acquired::releaseIfUntransferred);
            } catch (RuntimeException completionFailure) {
                acquired.releaseIfUntransferred();
            }
        }

        private void cancel() {
            if (gate.compareAndSet(ACQUIRING, CANCELLED)) {
                releaseCurrent();
            }
        }

        private void commandFailed(RuntimeException failure) {
            fail(failure);
        }

        private void fail(Exception failure) {
            if (gate.compareAndSet(ACQUIRING, FAILED)) {
                releaseCurrent();
                reportFailure(completion);
            } else if (gate.get() == CANCELLED) {
                releaseCurrent();
            }
        }

        private void releaseCurrent() {
            ProtosNioTcpConnectionBackend acquired = connectionBackend;
            if (acquired != null) {
                acquired.releaseIfUntransferred();
                return;
            }
            SocketChannel current = channel;
            if (current != null) {
                try {
                    current.close();
                } catch (IOException ignored) {
                    // Cleanup cannot manufacture another portable outcome.
                }
            }
        }
    }

    /** Host-only representation of a TCP connect target. */
    private static final class HostEndpoint {
        private final int version;
        private final InetSocketAddress socketAddress;

        private HostEndpoint(int version, InetSocketAddress socketAddress) {
            this.version = version;
            this.socketAddress = socketAddress;
        }

        private static HostEndpoint decode(
                ProtosHostIpEndpoint endpoint,
                Ipv6ScopeResolver resolver) throws IOException {
            int version = endpoint.version();
            byte[] bytes = endpoint.octets();
            InetAddress address;

            if (version == 4) {
                address = InetAddress.getByAddress(bytes);
                if (!(address instanceof Inet4Address))
                    throw new IOException("host changed IPv4 family");
            } else if (version == 6) {
                Inet6Address resolved = Objects.requireNonNull(
                        resolver.resolve(bytes.clone()),
                        "IPv6 scope resolver result");
                if (!Arrays.equals(bytes, resolved.getAddress()))
                    throw new IOException("IPv6 scope rewrote address bits");
                if (resolved.isLinkLocalAddress()
                        && resolved.getScopeId() == 0
                        && resolved.getScopedInterface() == null)
                    throw new IOException("link-local IPv6 requires scope");
                address = resolved;
            } else {
                throw new IOException("unsupported IP version");
            }

            return new HostEndpoint(
                    version, new InetSocketAddress(address, endpoint.port()));
        }

        private StandardProtocolFamily protocolFamily() {
            return version == 4
                    ? StandardProtocolFamily.INET
                    : StandardProtocolFamily.INET6;
        }

        private InetSocketAddress socketAddress() {
            return socketAddress;
        }

        private ProtosHostIpEndpoint captureLocalEndpoint(InetSocketAddress local)
                throws IOException {
            InetAddress address = local.getAddress();
            byte[] bytes = address == null ? null : address.getAddress();
            if (bytes == null || local.getPort() <= 0)
                throw new IOException("invalid local TCP endpoint");

            if ((version == 4
                            && (!(address instanceof Inet4Address)
                                    || bytes.length != 4))
                    || (version == 6
                            && (!(address instanceof Inet6Address)
                                    || bytes.length != 16)))
                throw new IOException("local IP version changed");

            return new ProtosHostIpEndpoint(version, bytes, local.getPort());
        }

        // Listener-only dependency retained until I093-B2.
        private static byte[] unsignedBytes(Object value, int width)
                throws IOException {
            byte[] bytes =
                    ProtosNumericValueSupport.unsignedBigEndianOrNull(value, width);
            if (bytes == null)
                throw new IOException("IP bits exceed canonical width");
            return bytes;
        }
    }

    private static void reportListenFailure(
            ProtosNetworkListenFlow.ListenCompletion completion) {
        try {
            completion.failed();
        } catch (RuntimeException ignored) {
            // A callback bug cannot create a second backend outcome.
        }
    }

    private static void reportFailure(
            ProtosNetworkConnectFlow.ConnectCompletion completion) {
        try {
            completion.failed();
        } catch (RuntimeException ignored) {
            // A callback bug cannot create a second backend outcome.
        }
    }
}
