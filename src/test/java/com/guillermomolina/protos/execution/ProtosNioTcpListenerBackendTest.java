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
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.guillermomolina.protos.runtime.ProtosActivation;
import com.guillermomolina.protos.runtime.ProtosFutureValue;
import com.guillermomolina.protos.runtime.ProtosIntegerValue;
import com.guillermomolina.protos.runtime.ProtosNetworkCapabilityValue;
import com.guillermomolina.protos.runtime.ProtosNullValue;
import com.guillermomolina.protos.runtime.ProtosObjectValue;
import com.guillermomolina.protos.runtime.ProtosPrelude;
import com.guillermomolina.protos.runtime.ProtosTcpConnectionValue;
import com.guillermomolina.protos.runtime.ProtosTcpListenerValue;
import java.io.IOException;
import java.math.BigInteger;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.StandardProtocolFamily;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

final class ProtosNioTcpListenerBackendTest {
    private static final Path CORE = Path.of("protos", "lib", "core");
    private static final Duration WAIT = Duration.ofSeconds(10);

    @SuppressWarnings("try")
    @Test
    void ipv4ListenerKeepsMultipleAcceptsIndependentAndCancellationDoesNotConsumeNextPeer()
            throws Exception {
        try (Fixture x = fixture("protos-test-nio-listener-v4", () -> List.of())) {
            ProtosTcpListenerValue listener = listen(x, 4, null, null);
            int port = listener.localPortForRuntime();

            ProtosFutureValue cancelled = accept(listener, x.activation);
            assertEquals(ProtosFutureValue.State.PENDING, cancelled.state());
            cancelled.cancelRequest();
            assertEquals(ProtosFutureValue.State.CANCELLED, cancelled.state());

            ProtosFutureValue first = accept(listener, x.activation);
            ProtosFutureValue second = accept(listener, x.activation);
            try (SocketChannel peer1 = connect4(port); SocketChannel peer2 = connect4(port)) {
                awaitTerminal(first);
                awaitTerminal(second);
                ProtosTcpConnectionValue connection1 =
                        assertInstanceOf(
                                ProtosTcpConnectionValue.class,
                                first.resolvedValue().orElseThrow());
                ProtosTcpConnectionValue connection2 =
                        assertInstanceOf(
                                ProtosTcpConnectionValue.class,
                                second.resolvedValue().orElseThrow());
                assertNotSame(connection1, connection2);
                assertEquals(4, endpointVersion(connection1.localEndpointForRuntime()));
                assertEquals(4, endpointVersion(connection2.remoteEndpointForRuntime()));

                awaitTerminal(close(connection1, x.activation));
                awaitTerminal(close(connection2, x.activation));
            }

            ProtosFutureValue close = close(listener, x.activation);
            awaitTerminal(close);
            assertSame(listener, close.resolvedValue().orElseThrow());
            assertFailedAs(accept(listener, x.activation), x.prelude, "IOLifecycleError");
        }
    }

    @Test
    void ipv6NullListenerUsesConcreteAuthorizedAddressAndDoesNotAdmitIpv4() throws Exception {
        assumeTrue(ipv6LoopbackAvailable());
        Inet6Address loopback6 = loopback6();
        try (Fixture x = fixture("protos-test-nio-listener-v6", () -> List.of(loopback6))) {
            ProtosTcpListenerValue listener = listen(x, 6, null, null);
            int port = listener.localPortForRuntime();
            ProtosFutureValue accepted = accept(listener, x.activation);

            try (SocketChannel peer = SocketChannel.open(StandardProtocolFamily.INET6)) {
                peer.connect(new InetSocketAddress(loopback6, port));
                awaitTerminal(accepted);
                ProtosTcpConnectionValue connection =
                        assertInstanceOf(
                                ProtosTcpConnectionValue.class,
                                accepted.resolvedValue().orElseThrow());
                assertEquals(6, endpointVersion(connection.localEndpointForRuntime()));
                assertEquals(6, endpointVersion(connection.remoteEndpointForRuntime()));
                awaitTerminal(close(connection, x.activation));
            }

            try (SocketChannel ipv4 = SocketChannel.open(StandardProtocolFamily.INET)) {
                assertThrows(
                        IOException.class,
                        () -> ipv4.connect(new InetSocketAddress(loopback4(), port)));
            }
            awaitTerminal(close(listener, x.activation));
        }
    }

    @Test
    void explicitIpv6WildcardIsNotReinterpretedAsNullCompositeRequest() throws Exception {
        assumeTrue(ipv6LoopbackAvailable());
        Inet6Address loopback = loopback6();
        try (Fixture x = fixture("protos-test-nio-listener-v6-explicit", () -> List.of(loopback))) {
            ProtosObjectValue explicitAny = ipAddress(x, 6, BigInteger.ZERO);
            ProtosFutureValue listen = listenFuture(x, 6, explicitAny, null);
            awaitTerminal(listen);
            assertFailedAs(listen, x.prelude, "IOError");
        }
    }

    @Test
    void compositeBindFailureReleasesEveryEarlierComponent() throws Exception {
        assumeTrue(ipv6LoopbackAvailable());
        Inet6Address loopback = loopback6();
        int port = freeIpv6Port(loopback);
        try (Fixture x = fixture(
                "protos-test-nio-listener-v6-atomic",
                () -> List.of(loopback, loopback))) {
            ProtosFutureValue listen = listenFuture(x, 6, null, BigInteger.valueOf(port));
            awaitTerminal(listen);
            assertFailedAs(listen, x.prelude, "IOError");

            try (ServerSocketChannel probe = ServerSocketChannel.open(StandardProtocolFamily.INET6)) {
                probe.bind(new InetSocketAddress(loopback, port));
            }
        }
    }

    private static Fixture fixture(
            String name, ProtosNioNetworkBackend.Ipv6ListenAddressProvider listenerAddresses)
            throws Exception {
        ProtosNioHostIoPoller poller = new ProtosNioHostIoPoller(name);
        try {
            ProtosPrelude prelude = new ProtosCoreBootstrap().bootstrap(CORE);
            ProtosActivation activation = prelude.newModuleActivation();
            ProtosObjectValue addressPrototype =
                    assertInstanceOf(
                            ProtosObjectValue.class,
                            prelude.ipAddressPrototypeForRuntime());
            ProtosObjectValue endpointPrototype =
                    assertInstanceOf(
                            ProtosObjectValue.class,
                            prelude.ipEndpointPrototypeForRuntime());
            ProtosNioNetworkBackend backend =
                    new ProtosNioNetworkBackend(
                            poller,
                            bytes -> Inet6Address.getByAddress(null, bytes, 0),
                            addressPrototype,
                            endpointPrototype,
                            listenerAddresses);
            return new Fixture(
                    poller,
                    prelude,
                    activation,
                    new ProtosNetworkCapabilityValue(prelude, backend));
        } catch (Throwable failure) {
            poller.close();
            throw failure;
        }
    }

    private static ProtosTcpListenerValue listen(
            Fixture x, int version, ProtosObjectValue address, BigInteger port) throws Exception {
        ProtosFutureValue future = listenFuture(x, version, address, port);
        awaitTerminal(future);
        assertEquals(ProtosFutureValue.State.RESOLVED, future.state());
        return assertInstanceOf(
                ProtosTcpListenerValue.class, future.resolvedValue().orElseThrow());
    }

    private static ProtosFutureValue listenFuture(
            Fixture x, int version, ProtosObjectValue address, BigInteger port) {
        ProtosObjectValue request = new ProtosObjectValue(ProtosObjectValue.rootObject());
        request.createLocalSlot("ipVersion", integer(version));
        request.createLocalSlot("address", address == null ? ProtosNullValue.INSTANCE : address);
        request.createLocalSlot("port", port == null ? ProtosNullValue.INSTANCE : com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerFromHost(port, x.prelude));
        return assertInstanceOf(
                ProtosFutureValue.class,
                ProtosInvocation.invokeMessage(
                        x.network, "listenTcp", List.of(request), x.activation));
    }

    private static ProtosFutureValue accept(
            ProtosTcpListenerValue listener, ProtosActivation activation) {
        return assertInstanceOf(
                ProtosFutureValue.class,
                ProtosInvocation.invokeMessage(listener, "accept", List.of(), activation));
    }

    private static ProtosFutureValue close(Object value, ProtosActivation activation) {
        return assertInstanceOf(
                ProtosFutureValue.class,
                ProtosInvocation.invokeMessage(value, "close", List.of(), activation));
    }

    private static ProtosObjectValue ipAddress(
            Fixture x, int version, BigInteger bits) {
        Object factory = x.prelude.ipAddressPrototypeForRuntime();
        return (ProtosObjectValue)
                ProtosInvocation.invoke(
                        factory,
                        List.of(integer(version), com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerFromHost(bits, x.prelude)),
                        x.activation);
    }

    private static int endpointVersion(ProtosObjectValue endpoint) {
        ProtosObjectValue address =
                assertInstanceOf(
                        ProtosObjectValue.class,
                        endpoint.readLocalSlot("address").orElseThrow());
        return Integer.parseInt(com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerDecimalText(address.readLocalSlot("version").orElseThrow()));
    }

    private static SocketChannel connect4(int port) throws Exception {
        SocketChannel peer = SocketChannel.open(StandardProtocolFamily.INET);
        try {
            peer.connect(new InetSocketAddress(loopback4(), port));
            return peer;
        } catch (Throwable failure) {
            peer.close();
            throw failure;
        }
    }

    private static Inet4Address loopback4() throws Exception {
        return (Inet4Address) InetAddress.getByAddress(new byte[] {127, 0, 0, 1});
    }

    private static Inet6Address loopback6() throws Exception {
        byte[] bytes = new byte[16];
        bytes[15] = 1;
        return Inet6Address.getByAddress(null, bytes, 0);
    }

    private static boolean ipv6LoopbackAvailable() {
        try (ServerSocketChannel probe = ServerSocketChannel.open(StandardProtocolFamily.INET6)) {
            probe.bind(new InetSocketAddress(loopback6(), 0));
            return true;
        } catch (Exception unavailable) {
            return false;
        }
    }

    private static int freeIpv6Port(Inet6Address address) throws Exception {
        try (ServerSocketChannel probe = ServerSocketChannel.open(StandardProtocolFamily.INET6)) {
            probe.bind(new InetSocketAddress(address, 0));
            return ((InetSocketAddress) probe.getLocalAddress()).getPort();
        }
    }

    private static ProtosIntegerValue integer(long value) {
        return new ProtosIntegerValue(value);
    }

    private static void awaitTerminal(ProtosFutureValue future) throws Exception {
        long deadline = System.nanoTime() + WAIT.toNanos();
        while (future.state() == ProtosFutureValue.State.PENDING && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        if (future.state() == ProtosFutureValue.State.PENDING) {
            throw new AssertionError("timed out waiting for Future");
        }
    }

    private static void assertFailedAs(
            ProtosFutureValue future, ProtosPrelude prelude, String prototypeName) {
        assertEquals(ProtosFutureValue.State.FAILED, future.state());
        assertSame(
                prelude.bindings().readLocalSlot(prototypeName).orElseThrow(),
                future.failedError().orElseThrow().parent().orElseThrow());
    }

    private static final class Fixture implements AutoCloseable {
        final ProtosNioHostIoPoller poller;
        final ProtosPrelude prelude;
        final ProtosActivation activation;
        final ProtosNetworkCapabilityValue network;

        Fixture(
                ProtosNioHostIoPoller poller,
                ProtosPrelude prelude,
                ProtosActivation activation,
                ProtosNetworkCapabilityValue network) {
            this.poller = poller;
            this.prelude = prelude;
            this.activation = activation;
            this.network = network;
        }

        @Override
        public void close() {
            poller.close();
        }
    }
}
