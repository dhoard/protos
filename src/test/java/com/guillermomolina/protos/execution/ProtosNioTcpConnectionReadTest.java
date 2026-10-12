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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.guillermomolina.protos.runtime.ProtosActivation;
import com.guillermomolina.protos.runtime.ProtosBytesValue;
import com.guillermomolina.protos.runtime.ProtosFutureValue;
import com.guillermomolina.protos.runtime.ProtosIntegerValue;
import com.guillermomolina.protos.runtime.ProtosNetworkCapabilityValue;
import com.guillermomolina.protos.runtime.ProtosNullValue;
import com.guillermomolina.protos.runtime.ProtosObjectValue;
import com.guillermomolina.protos.runtime.ProtosPrelude;
import com.guillermomolina.protos.runtime.ProtosTcpConnectionValue;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.StandardProtocolFamily;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

final class ProtosNioTcpConnectionReadTest {
    private static final Path CORE = Path.of("protos", "lib", "core");
    private static final Duration WAIT = Duration.ofSeconds(5);

    @Test
    void pendingReadReturnsAvailableShortPrefixWithoutWaitingForMaxBytes() throws Exception {
        try (ConnectedFixture x = connected("protos-test-nio-read-short")) {
            ProtosFutureValue read = read(x, 8);
            assertEquals(ProtosFutureValue.State.PENDING, read.state());
            writeFully(x.peer, new byte[] {1, 2, 3});
            awaitTerminal(read);
            assertEquals(List.of(1, 2, 3), resolvedBytes(read));
        }
    }

    @Test
    void dataAlreadyAvailableIsReadWithoutRequiringAnotherReadinessEdge() throws Exception {
        try (ConnectedFixture x = connected("protos-test-nio-read-immediate")) {
            writeFully(x.peer, new byte[] {9, 8, 7, 6});
            ProtosFutureValue read = read(x, 4);
            awaitTerminal(read);
            assertEquals(List.of(9, 8, 7, 6), resolvedBytes(read));
        }
    }

    @Test
    void remoteOutputShutdownResolvesPendingReadAsEof() throws Exception {
        try (ConnectedFixture x = connected("protos-test-nio-read-eof")) {
            ProtosFutureValue read = read(x, 4);
            assertEquals(ProtosFutureValue.State.PENDING, read.state());
            x.peer.shutdownOutput();
            awaitTerminal(read);
            assertEquals(ProtosFutureValue.State.RESOLVED, read.state());
            assertSame(ProtosNullValue.INSTANCE, read.resolvedValue().orElseThrow());
        }
    }

    @Test
    void cancelledPendingReadDoesNotStealBytesFromTheNextRead() throws Exception {
        try (ConnectedFixture x = connected("protos-test-nio-read-cancel")) {
            ProtosFutureValue cancelled = read(x, 4);
            assertTrue(cancelled.cancelRequest());
            assertEquals(ProtosFutureValue.State.CANCELLED, cancelled.state());

            ProtosFutureValue next = read(x, 4);
            writeFully(x.peer, new byte[] {4, 5, 6, 7});
            awaitTerminal(next);
            assertEquals(List.of(4, 5, 6, 7), resolvedBytes(next));
        }
    }

    @Test
    void wholeConnectionCloseCutsOverPendingReadAndCompletesPhysicalClose() throws Exception {
        try (ConnectedFixture x = connected("protos-test-nio-read-close")) {
            ProtosFutureValue read = read(x, 8);
            assertEquals(ProtosFutureValue.State.PENDING, read.state());

            ProtosFutureValue close =
                    (ProtosFutureValue)
                            ProtosInvocation.invokeMessage(
                                    x.connection, "close", List.of(), x.activation);

            awaitTerminal(read);
            awaitTerminal(close);

            assertEquals(ProtosFutureValue.State.FAILED, read.state());
            assertSame(
                    x.prelude.bindings().readLocalSlot("IOLifecycleError").orElseThrow(),
                    read.failedError().orElseThrow().parent().orElseThrow());
            assertEquals(ProtosFutureValue.State.RESOLVED, close.state());
            assertSame(x.connection, close.resolvedValue().orElseThrow());
        }
    }

    private static ConnectedFixture connected(String pollerName) throws Exception {
        ProtosNioHostIoPoller poller = new ProtosNioHostIoPoller(pollerName);
        ServerSocketChannel server = ServerSocketChannel.open(StandardProtocolFamily.INET);
        SocketChannel peer = null;
        try {
            Inet4Address loopback =
                    (Inet4Address) InetAddress.getByAddress(new byte[] {127, 0, 0, 1});
            server.bind(new InetSocketAddress(loopback, 0));
            server.configureBlocking(false);

            ProtosPrelude prelude = new ProtosCoreBootstrap().bootstrap(CORE);
            ProtosActivation activation = prelude.newModuleActivation();
            ProtosNioNetworkBackend backend =
                    new ProtosNioNetworkBackend(
                            poller,
                            bytes -> {
                                throw new AssertionError("IPv6 resolver used by IPv4");
                            });
            ProtosNetworkCapabilityValue network =
                    new ProtosNetworkCapabilityValue(prelude, backend);

            int port = ((InetSocketAddress) server.getLocalAddress()).getPort();
            ProtosObjectValue remote = endpoint(prelude, activation, port);
            ProtosFutureValue connect =
                    (ProtosFutureValue)
                            ProtosInvocation.invokeMessage(
                                    network, "connectTcp", List.of(remote), activation);
            awaitTerminal(connect);
            assertEquals(ProtosFutureValue.State.RESOLVED, connect.state());

            peer = awaitAccept(server);
            peer.configureBlocking(true);
            ProtosTcpConnectionValue connection =
                    assertInstanceOf(
                            ProtosTcpConnectionValue.class,
                            connect.resolvedValue().orElseThrow());

            return new ConnectedFixture(
                    poller, server, peer, prelude, activation, connection);
        } catch (Throwable failure) {
            if (peer != null) {
                try { peer.close(); } catch (Exception ignored) {}
            }
            try { server.close(); } catch (Exception ignored) {}
            poller.close();
            throw failure;
        }
    }

    private static ProtosObjectValue endpoint(
            ProtosPrelude prelude, ProtosActivation activation, int port) {
        Object addressFactory =
                prelude.ipAddressPrototypeForRuntime();
        ProtosObjectValue address =
                (ProtosObjectValue)
                        ProtosInvocation.invoke(
                                addressFactory,
                                List.of(
                                        new ProtosIntegerValue(4),
                                        new ProtosIntegerValue(0x7f000001L)),
                                activation);
        Object endpointFactory =
                prelude.ipEndpointPrototypeForRuntime();
        return (ProtosObjectValue)
                ProtosInvocation.invoke(
                        endpointFactory,
                        List.of(address, new ProtosIntegerValue(port)),
                        activation);
    }

    private static ProtosFutureValue read(ConnectedFixture x, int maxBytes) {
        return (ProtosFutureValue)
                ProtosInvocation.invokeMessage(
                        x.connection,
                        "read",
                        List.of(new ProtosIntegerValue(maxBytes)),
                        x.activation);
    }

    private static List<Integer> resolvedBytes(ProtosFutureValue future) {
        ProtosBytesValue bytes =
                assertInstanceOf(ProtosBytesValue.class, future.resolvedValue().orElseThrow());
        ArrayList<Integer> result = new ArrayList<>();
        for (Object value : bytes.indexedSnapshot()) {
            result.add(Integer.parseInt(com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerDecimalText(value)));
        }
        return result;
    }

    private static SocketChannel awaitAccept(ServerSocketChannel server) throws Exception {
        long deadline = System.nanoTime() + WAIT.toNanos();
        while (System.nanoTime() < deadline) {
            SocketChannel accepted = server.accept();
            if (accepted != null) return accepted;
            Thread.sleep(5);
        }
        throw new AssertionError("timed out waiting for server-side TCP accept");
    }

    private static void writeFully(SocketChannel channel, byte[] bytes) throws Exception {
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        while (buffer.hasRemaining()) channel.write(buffer);
    }

    private static void awaitTerminal(ProtosFutureValue future) throws Exception {
        long deadline = System.nanoTime() + WAIT.toNanos();
        while (future.state() == ProtosFutureValue.State.PENDING
                && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        if (future.state() == ProtosFutureValue.State.PENDING) {
            throw new AssertionError("timed out waiting for Future terminal state");
        }
    }

    private static final class ConnectedFixture implements AutoCloseable {
        private final ProtosNioHostIoPoller poller;
        private final ServerSocketChannel server;
        private final SocketChannel peer;
        private final ProtosPrelude prelude;
        private final ProtosActivation activation;
        private final ProtosTcpConnectionValue connection;

        private ConnectedFixture(
                ProtosNioHostIoPoller poller,
                ServerSocketChannel server,
                SocketChannel peer,
                ProtosPrelude prelude,
                ProtosActivation activation,
                ProtosTcpConnectionValue connection) {
            this.poller = poller;
            this.server = server;
            this.peer = peer;
            this.prelude = prelude;
            this.activation = activation;
            this.connection = connection;
        }

        @Override
        public void close() throws IOException {
            try {
                peer.close();
            } finally {
                try {
                    server.close();
                } finally {
                    poller.close();
                }
            }
        }
    }
}
