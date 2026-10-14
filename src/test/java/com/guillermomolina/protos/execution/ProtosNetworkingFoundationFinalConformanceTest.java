/* APL-1.0 licensed work; see LICENSE.TXT. */
package com.guillermomolina.protos.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.guillermomolina.protos.runtime.ProtosActivation;
import com.guillermomolina.protos.runtime.ProtosActorValueTransfer;
import com.guillermomolina.protos.runtime.ProtosBytesValue;
import com.guillermomolina.protos.runtime.ProtosEnvironmentValue;
import com.guillermomolina.protos.runtime.ProtosFutureValue;
import com.guillermomolina.protos.runtime.ProtosIntegerValue;
import com.guillermomolina.protos.runtime.ProtosNetworkCapabilityValue;
import com.guillermomolina.protos.runtime.ProtosNullValue;
import com.guillermomolina.protos.runtime.ProtosObjectValue;
import com.guillermomolina.protos.runtime.ProtosPrelude;
import com.guillermomolina.protos.runtime.ProtosSignalException;
import com.guillermomolina.protos.runtime.ProtosTcpConnectionValue;
import com.guillermomolina.protos.runtime.ProtosTcpListenerValue;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Final I028 production-path composition and isolation evidence. */
final class ProtosNetworkingFoundationFinalConformanceTest {
    private static final Path CORE = Path.of("protos", "lib", "core");
    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final int SCALE = 8;

    @Test
    void productionNetworkingClosesAcrossScaleDuplexLifecycleAndIsolation() throws Exception {
        try (ProtosPolyglotRuntimeHost host = ProtosPolyglotRuntimeHost.open()) {
            ProtosPrelude prelude = new ProtosCoreBootstrap().bootstrap(CORE);
            ProtosNetworkCapabilityValue network = host.provisionHostNetwork(prelude);
            ProtosStandaloneProcessBootstrap.Result bootstrap =
                    ProtosStandaloneProcessBootstrap.create(
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
            ProtosActivation activation = bootstrap.activation();
            assertSame(network, activation.context().readLocalSlot("network").orElseThrow());

            ProtosTcpListenerValue listener =
                    (ProtosTcpListenerValue)
                            awaitResolved(
                                    future(
                                            network,
                                            "listenTcp",
                                            List.of(
                                                    listenRequest(
                                                            integer(4),
                                                            ProtosNullValue.INSTANCE,
                                                            ProtosNullValue.INSTANCE)),
                                            activation));
            int port = listener.localPortForRuntime();

            ProtosFutureValue cancelledAccept = future(listener, "accept", List.of(), activation);
            cancelledAccept.cancelRequest();
            awaitTerminal(cancelledAccept);
            assertEquals(ProtosFutureValue.State.CANCELLED, cancelledAccept.state());

            List<ProtosFutureValue> accepts = new ArrayList<>();
            for (int i = 0; i < SCALE; i++) accepts.add(future(listener, "accept", List.of(), activation));
            List<ProtosFutureValue> connects = new ArrayList<>();
            for (int i = 0; i < SCALE; i++) {
                connects.add(
                        future(
                                network,
                                "connectTcp",
                                List.of(ipv4LoopbackEndpoint(prelude, activation, port)),
                                activation));
            }

            List<ProtosTcpConnectionValue> servers = new ArrayList<>();
            List<ProtosTcpConnectionValue> clients = new ArrayList<>();
            for (ProtosFutureValue accept : accepts) {
                servers.add((ProtosTcpConnectionValue) awaitResolved(accept));
            }
            for (ProtosFutureValue connect : connects) {
                clients.add((ProtosTcpConnectionValue) awaitResolved(connect));
            }

            assertActorAndPReject(prelude, activation, network);
            assertActorAndPReject(prelude, activation, listener);
            assertActorAndPReject(prelude, activation, servers.get(0));
            assertActorAndPReject(prelude, activation, clients.get(0));

            for (ProtosTcpConnectionValue connection : clients) closeAndAwait(connection, activation);
            for (ProtosTcpConnectionValue connection : servers) closeAndAwait(connection, activation);

            ProtosFutureValue duplexAccept = future(listener, "accept", List.of(), activation);
            ProtosFutureValue duplexConnect =
                    future(
                            network,
                            "connectTcp",
                            List.of(ipv4LoopbackEndpoint(prelude, activation, port)),
                            activation);
            ProtosTcpConnectionValue server =
                    (ProtosTcpConnectionValue) awaitResolved(duplexAccept);
            ProtosTcpConnectionValue client =
                    (ProtosTcpConnectionValue) awaitResolved(duplexConnect);

            ProtosFutureValue serverRead = future(server, "read", List.of(integer(1)), activation);
            ProtosFutureValue clientRead = future(client, "read", List.of(integer(1)), activation);
            ProtosFutureValue clientWrite =
                    future(client, "write", List.of(bytes(prelude, 41)), activation);
            ProtosFutureValue serverWrite =
                    future(server, "write", List.of(bytes(prelude, 91)), activation);

            assertSame(client, awaitResolved(clientWrite));
            assertSame(server, awaitResolved(serverWrite));
            assertBytes(awaitResolved(serverRead), 41);
            assertBytes(awaitResolved(clientRead), 91);
            closeAndAwait(client, activation);
            closeAndAwait(server, activation);

            ProtosFutureValue pendingAtClose = future(listener, "accept", List.of(), activation);
            ProtosFutureValue listenerClose = future(listener, "close", List.of(), activation);
            awaitTerminal(pendingAtClose);
            assertEquals(ProtosFutureValue.State.FAILED, pendingAtClose.state());
            assertSame(
                    prelude.bindings().readLocalSlot("IOLifecycleError").orElseThrow(),
                    pendingAtClose.failedError().orElseThrow().parent().orElseThrow());
            assertSame(listener, awaitResolved(listenerClose));

            bootstrap.process().requestTerminationForRuntime();
        }
    }

    private static void assertActorAndPReject(
            ProtosPrelude prelude, ProtosActivation activation, Object value) throws Exception {
        ProtosSignalException actorFailure =
                assertThrows(
                        ProtosSignalException.class,
                        () -> ProtosActorValueTransfer.snapshotValue(value, activation));
        assertSame(
                prelude.bindings().readLocalSlot("NonTransferableValue").orElseThrow(),
                actorFailure.error().parent().orElseThrow());

        InvocationTargetException pFailure =
                assertThrows(
                        InvocationTargetException.class,
                        () ->
                                pCopyMethod()
                                        .invoke(
                                                null,
                                                value,
                                                activation,
                                                new IdentityHashMap<Object, Object>()));
        assertNotNull(pFailure.getCause());
        assertEquals("NonParallel", pFailure.getCause().getClass().getSimpleName());
    }

    private static ProtosFutureValue future(
            Object receiver, String selector, List<?> arguments, ProtosActivation activation) {
        Object result = ProtosInvocation.invokeMessage(receiver, selector, arguments, activation);
        if (!(result instanceof ProtosFutureValue future)) {
            throw new AssertionError(selector + " did not return Future");
        }
        return future;
    }

    private static ProtosObjectValue listenRequest(
            Object ipVersion, Object address, Object port) {
        ProtosObjectValue request = new ProtosObjectValue(ProtosObjectValue.rootObject());
        request.createLocalSlot("ipVersion", ipVersion);
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
                                List.of(integer(4), new ProtosIntegerValue(0x7f000001L)),
                                activation);
        Object endpointFactory = prelude.ipEndpointPrototypeForRuntime();
        return (ProtosObjectValue)
                ProtosInvocation.invoke(
                        endpointFactory, List.of(address, integer(port)), activation);
    }

    private static ProtosBytesValue bytes(ProtosPrelude prelude, int... values) {
        ProtosBytesValue bytes = new ProtosBytesValue(prelude.bytesPrototypeForRuntime());
        for (int value : values) bytes.indexedAdd(integer(value & 0xff));
        return bytes;
    }

    private static void assertBytes(Object value, int... expected) {
        ProtosBytesValue bytes = (ProtosBytesValue) value;
        List<Object> actual = bytes.indexedSnapshot();
        assertEquals(expected.length, actual.size());
        for (int i = 0; i < expected.length; i++) {
            assertEquals(Long.toString(expected[i]), com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerDecimalText(actual.get(i)));
        }
    }

    private static ProtosIntegerValue integer(long value) {
        return new ProtosIntegerValue(value);
    }

    private static void closeAndAwait(ProtosTcpConnectionValue connection, ProtosActivation activation)
            throws Exception {
        assertSame(connection, awaitResolved(future(connection, "close", List.of(), activation)));
    }

    private static Object awaitResolved(ProtosFutureValue future) throws Exception {
        awaitTerminal(future);
        assertEquals(ProtosFutureValue.State.RESOLVED, future.state());
        return future.resolvedValue().orElseThrow();
    }

    private static void awaitTerminal(ProtosFutureValue future) throws Exception {
        long deadline = System.nanoTime() + TIMEOUT.toNanos();
        while (future.state() == ProtosFutureValue.State.PENDING && System.nanoTime() < deadline) {
            if (!future.domain().dispatchOne()) {
                Thread.sleep(2L);
            }
        }
        if (future.state() == ProtosFutureValue.State.PENDING) {
            throw new AssertionError("Future remained pending after " + TIMEOUT);
        }
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

    private static Method pCopyMethod() throws Exception {
        Class<?> transfer =
                Class.forName("com.guillermomolina.protos.execution.ProtosParallelRuntime$Transfer");
        Method copy =
                transfer.getDeclaredMethod(
                        "copy", Object.class, ProtosActivation.class, IdentityHashMap.class);
        copy.setAccessible(true);
        return copy;
    }
}
