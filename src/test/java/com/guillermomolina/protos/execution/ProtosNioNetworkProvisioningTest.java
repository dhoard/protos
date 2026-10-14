/* APL-1.0 licensed work; see LICENSE.TXT. */
package com.guillermomolina.protos.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.guillermomolina.protos.runtime.ProtosActivation;
import com.guillermomolina.protos.runtime.ProtosBytesValue;
import com.guillermomolina.protos.runtime.ProtosEnvironmentValue;
import com.guillermomolina.protos.runtime.ProtosFutureValue;
import com.guillermomolina.protos.runtime.ProtosHostIpEndpoint;
import com.guillermomolina.protos.runtime.ProtosIntegerValue;
import com.guillermomolina.protos.runtime.ProtosNetworkCapabilityValue;
import com.guillermomolina.protos.runtime.ProtosNullValue;
import com.guillermomolina.protos.runtime.ProtosObjectValue;
import com.guillermomolina.protos.runtime.ProtosPrelude;
import com.guillermomolina.protos.runtime.ProtosTcpConnectionFlow;
import com.guillermomolina.protos.runtime.ProtosTcpConnectionValue;
import com.guillermomolina.protos.runtime.ProtosTcpListenerFlow;
import com.guillermomolina.protos.runtime.ProtosTcpListenerValue;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

final class ProtosNioNetworkProvisioningTest {
    private static final Path CORE = Path.of("protos", "lib", "core");
    private static final Duration WAIT = Duration.ofSeconds(10);

    @Test
    void runtimeHostNetworkProvisioningIsExplicitAndLazy() throws Exception {
        try (ProtosPolyglotRuntimeHost host = ProtosPolyglotRuntimeHost.open()) {
            assertFalse(host.networkHostInitializedForTesting());
            ProtosPrelude prelude = new ProtosCoreBootstrap().bootstrap(CORE);
            assertFalse(host.networkHostInitializedForTesting());

            ProtosNetworkCapabilityValue network = host.provisionHostNetwork(prelude);
            assertTrue(host.networkHostInitializedForTesting());
            assertSame(prelude.networkPrototype(), network.representedDelegationParent(prelude));

            ProtosStandaloneProcessBootstrap.Result bootstrap = standalone(prelude, network);
            try {
                assertSame(
                        network,
                        bootstrap.activation().context().readLocalSlot("network").orElseThrow());
            } finally {
                bootstrap.process().requestTerminationForRuntime();
            }
        }
    }

    @Test
    void provisionedHostNetworkComposesListenConnectAcceptAndDuplex() throws Exception {
        try (ProtosPolyglotRuntimeHost host = ProtosPolyglotRuntimeHost.open()) {
            ProtosPrelude prelude = new ProtosCoreBootstrap().bootstrap(CORE);
            ProtosNetworkCapabilityValue network = host.provisionHostNetwork(prelude);
            ProtosStandaloneProcessBootstrap.Result bootstrap = standalone(prelude, network);
            ProtosActivation activation = bootstrap.activation();

            try {
                ProtosFutureValue listening =
                        future(
                                network,
                                "listenTcp",
                                List.of(
                                        listenRequest(
                                                integer(4),
                                                ProtosNullValue.INSTANCE,
                                                ProtosNullValue.INSTANCE)),
                                activation);
                awaitTerminal(listening);
                assertEquals(ProtosFutureValue.State.RESOLVED, listening.state());
                ProtosTcpListenerValue listener =
                        assertInstanceOf(
                                ProtosTcpListenerValue.class,
                                listening.resolvedValue().orElseThrow());
                int port = listener.localPortForRuntime();

                ProtosFutureValue accepting = future(listener, "accept", List.of(), activation);
                ProtosFutureValue connecting =
                        future(
                                network,
                                "connectTcp",
                                List.of(ipv4LoopbackEndpoint(prelude, activation, port)),
                                activation);
                awaitTerminal(connecting);
                awaitTerminal(accepting);
                assertEquals(ProtosFutureValue.State.RESOLVED, connecting.state());
                assertEquals(ProtosFutureValue.State.RESOLVED, accepting.state());

                ProtosTcpConnectionValue client =
                        assertInstanceOf(
                                ProtosTcpConnectionValue.class,
                                connecting.resolvedValue().orElseThrow());
                ProtosTcpConnectionValue server =
                        assertInstanceOf(
                                ProtosTcpConnectionValue.class,
                                accepting.resolvedValue().orElseThrow());

                byte[] outbound = {11, 22, 33, 44, 55, 66};
                ProtosFutureValue read =
                        future(server, "read", List.of(integer(outbound.length)), activation);
                ProtosFutureValue write =
                        future(client, "write", List.of(bytes(prelude, outbound)), activation);
                awaitTerminal(write);
                awaitTerminal(read);
                assertEquals(ProtosFutureValue.State.RESOLVED, write.state());
                assertEquals(List.of(11, 22, 33, 44, 55, 66), resolvedBytes(read));

                closeAndAwait(client, activation);
                closeAndAwait(server, activation);
                closeAndAwait(listener, activation);
            } finally {
                bootstrap.process().requestTerminationForRuntime();
            }
        }
    }

    @Test
    void runtimeHostCloseReleasesUnclosedNetworkResources() throws Exception {
        ProtosPolyglotRuntimeHost host = ProtosPolyglotRuntimeHost.open();
        try {
            ProtosPrelude prelude = new ProtosCoreBootstrap().bootstrap(CORE);
            ProtosActivation activation = prelude.newModuleActivation();
            ProtosNetworkCapabilityValue network = host.provisionHostNetwork(prelude);
            ProtosFutureValue listening =
                    future(
                            network,
                            "listenTcp",
                            List.of(
                                    listenRequest(
                                            integer(4),
                                            ProtosNullValue.INSTANCE,
                                            ProtosNullValue.INSTANCE)),
                            activation);
            awaitTerminal(listening);
            ProtosTcpListenerValue listener =
                    assertInstanceOf(
                            ProtosTcpListenerValue.class,
                            listening.resolvedValue().orElseThrow());
            ProtosNioTcpListenerBackend backend =
                    assertInstanceOf(
                            ProtosNioTcpListenerBackend.class,
                            listener.resourceStateForRuntime());

            host.close();

            AtomicInteger failures = new AtomicInteger();
            backend.accept(
                    new ProtosTcpListenerFlow.AcceptCompletion() {
                        @Override
                        public void succeeded(
                                Object resourceState,
                                ProtosHostIpEndpoint localEndpoint,
                                ProtosHostIpEndpoint remoteEndpoint,
                                ProtosTcpConnectionFlow.Backend connectionBackend,
                                Runnable releaseIfUntransferred) {
                            releaseIfUntransferred.run();
                            fail("closed RuntimeHost accepted another connection");
                        }

                        @Override
                        public void failed() {
                            failures.incrementAndGet();
                        }
                    });
            assertEquals(1, failures.get());
            assertThrows(
                    IllegalStateException.class,
                    () -> host.provisionHostNetwork(prelude));
        } finally {
            host.close();
        }
    }

    private static ProtosStandaloneProcessBootstrap.Result standalone(
            ProtosPrelude prelude, ProtosNetworkCapabilityValue network) {
        return ProtosStandaloneProcessBootstrap.create(
                prelude,
                List.of(),
                exactDomain(),
                List.of(),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                network);
    }

    private static ProtosEnvironmentValue.NativeNameDomain exactDomain() {
        return new ProtosEnvironmentValue.NativeNameDomain() {
            @Override
            public boolean sameCapturedName(String left, String right) {
                return left.equals(right);
            }

            @Override
            public boolean isQueryRepresentable(String name) {
                return !name.contains("=") && name.indexOf('\0') < 0;
            }

            @Override
            public boolean matchesQuery(String captured, String query) {
                return captured.equals(query);
            }
        };
    }

    private static ProtosObjectValue listenRequest(Object version, Object address, Object port) {
        ProtosObjectValue request = new ProtosObjectValue(ProtosObjectValue.rootObject());
        request.createLocalSlot("ipVersion", version);
        request.createLocalSlot("address", address);
        request.createLocalSlot("port", port);
        return request;
    }

    private static ProtosObjectValue ipv4LoopbackEndpoint(
            ProtosPrelude prelude, ProtosActivation activation, int port) {
        Object addressFactory = prelude.ipAddressPrototypeForRuntime();
        ProtosObjectValue address =
                (ProtosObjectValue)
                        ProtosInvocation.invoke(
                                addressFactory,
                                List.of(
                                        integer(4),
                                        new ProtosIntegerValue(0x7f000001L)),
                                activation);
        Object endpointFactory = prelude.ipEndpointPrototypeForRuntime();
        return (ProtosObjectValue)
                ProtosInvocation.invoke(
                        endpointFactory, List.of(address, integer(port)), activation);
    }

    private static ProtosBytesValue bytes(ProtosPrelude prelude, byte[] values) {
        ProtosBytesValue bytes = new ProtosBytesValue(prelude.bytesPrototypeForRuntime());
        for (byte value : values) {
            bytes.indexedAdd(integer(value & 0xff));
        }
        return bytes;
    }

    private static ProtosIntegerValue integer(long value) {
        return new ProtosIntegerValue(value);
    }

    private static ProtosFutureValue future(
            Object receiver, String selector, List<?> arguments, ProtosActivation activation) {
        return assertInstanceOf(
                ProtosFutureValue.class,
                ProtosInvocation.invokeMessage(receiver, selector, arguments, activation));
    }

    private static List<Integer> resolvedBytes(ProtosFutureValue future) {
        ProtosBytesValue bytes =
                assertInstanceOf(
                        ProtosBytesValue.class,
                        future.resolvedValue().orElseThrow());
        ArrayList<Integer> result = new ArrayList<>();
        for (Object value : bytes.indexedSnapshot()) {
            result.add(
                    Integer.parseInt(com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerDecimalText(value)));
        }
        return result;
    }

    private static void closeAndAwait(Object receiver, ProtosActivation activation)
            throws Exception {
        ProtosFutureValue close = future(receiver, "close", List.of(), activation);
        awaitTerminal(close);
        assertEquals(ProtosFutureValue.State.RESOLVED, close.state());
        assertSame(receiver, close.resolvedValue().orElseThrow());
    }

    private static void awaitTerminal(ProtosFutureValue future) throws Exception {
        long deadline = System.nanoTime() + WAIT.toNanos();
        while (future.state() == ProtosFutureValue.State.PENDING
                && System.nanoTime() < deadline) {
            if (!future.domain().dispatchOne()) {
                Thread.sleep(5);
            }
        }
        if (future.state() == ProtosFutureValue.State.PENDING) {
            throw new AssertionError("timed out waiting for Future");
        }
    }
}
