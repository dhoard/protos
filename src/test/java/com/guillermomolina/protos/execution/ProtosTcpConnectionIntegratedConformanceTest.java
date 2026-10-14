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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.guillermomolina.protos.runtime.ProtosActivation;
import com.guillermomolina.protos.runtime.ProtosActorValueTransfer;
import com.guillermomolina.protos.runtime.ProtosBooleanValue;
import com.guillermomolina.protos.runtime.ProtosByteIoFlow;
import com.guillermomolina.protos.runtime.ProtosFutureValue;
import com.guillermomolina.protos.runtime.ProtosHostIpEndpoint;
import com.guillermomolina.protos.runtime.ProtosNumericValueSupport;
import com.guillermomolina.protos.runtime.ProtosIntegerValue;
import com.guillermomolina.protos.runtime.ProtosNetworkCapabilityValue;
import com.guillermomolina.protos.runtime.ProtosNetworkConnectFlow;
import com.guillermomolina.protos.runtime.ProtosObjectValue;
import com.guillermomolina.protos.runtime.ProtosPrelude;
import com.guillermomolina.protos.runtime.ProtosSignalException;
import com.guillermomolina.protos.runtime.ProtosTcpConnectionFlow;
import com.guillermomolina.protos.runtime.ProtosTcpConnectionValue;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** I028-C5 integrated C1-C4 conformance and closure evidence. */
final class ProtosTcpConnectionIntegratedConformanceTest {
    private static final Path CORE = Path.of("protos", "lib", "core");

    @Test
    void acquiredConnectionComposesOrdinaryObjectEndpointsReceiverDomainAndTransferConfinement()
            throws Exception {
        Fixture x = fixture();
        ProtosObjectValue remote = endpoint(x.prelude, x.activation, 0x01010101L, 443);
        ProtosFutureValue future = connect(x, remote);
        assertEquals(1, x.backend.invocations.size());

        ProtosObjectValue local = endpoint(x.prelude, x.activation, 0x7f000001L, 41000);
        AtomicInteger release = new AtomicInteger();
        x.backend.invocations.get(0).completion.succeeded(
                new Object(), host(local), new NoOpConnectionBackend(), release::incrementAndGet);

        assertTrue(x.activation.executionDomain().dispatchOne());
        ProtosTcpConnectionValue connection =
                assertInstanceOf(ProtosTcpConnectionValue.class, future.resolvedValue().orElseThrow());
        assertSame(x.prelude.tcpConnectionPrototypeForRuntime(), connection.parent().orElseThrow());
        assertTrue(connection.isOpen());
        assertTrue(connection.hasProtocolFlowForRuntime());
        assertEquals(0, release.get());

        Object observedLocal =
                ProtosInvocation.invokeMessage(connection, "localEndpoint", List.of(), x.activation);
        Object observedRemote =
                ProtosInvocation.invokeMessage(connection, "remoteEndpoint", List.of(), x.activation);
        assertSame(
                ProtosBooleanValue.TRUE,
                ProtosInvocation.invokeMessage(observedLocal, "==", List.of(local), x.activation));
        assertSame(
                ProtosBooleanValue.TRUE,
                ProtosInvocation.invokeMessage(observedRemote, "==", List.of(remote), x.activation));
        // Endpoint object identity remains deliberately unspecified by D052/C5.

        ProtosIntegerValue marker = integer(7);
        connection.createLocalSlot("label", marker);
        assertSame(marker, connection.readLocalSlot("label").orElseThrow());
        Object inheritedRead =
                x.prelude.tcpConnectionPrototypeForRuntime().readLocalSlot("read").orElseThrow();
        connection.createLocalSlot("read", marker);
        assertSame(marker, connection.readLocalSlot("read").orElseThrow());
        assertSame(
                inheritedRead,
                x.prelude.tcpConnectionPrototypeForRuntime().readLocalSlot("read").orElseThrow());

        ProtosObjectValue descendant = new ProtosObjectValue(connection);
        assertThrows(
                ProtosSignalException.class,
                () ->
                        ProtosInvocation.invokeMessage(
                                descendant, "remoteEndpoint", List.of(), x.activation));

        assertNonTransferable(
                x.prelude,
                () -> ProtosActorValueTransfer.snapshotValue(connection, x.activation));
        assertNonTransferable(
                x.prelude,
                () -> ProtosActorValueTransfer.snapshotValue(descendant, x.activation));
        Method copy = pCopyMethod();
        assertNonParallel(copy, connection, x.activation);
        assertNonParallel(copy, descendant, x.activation);
    }

    @Test
    void cancelledAcquisitionNeverPublishesAuthorityAndLateResourceCustodyIsReleased()
            throws Exception {
        Fixture x = fixture();
        ProtosFutureValue future =
                connect(x, endpoint(x.prelude, x.activation, 0x08080808L, 443));
        Invocation invocation = x.backend.invocations.get(0);

        assertTrue(future.cancelRequest());
        assertEquals(ProtosFutureValue.State.CANCELLED, future.state());
        assertEquals(1, invocation.cancelCount.get());
        assertTrue(future.resolvedValue().isEmpty());

        AtomicInteger release = new AtomicInteger();
        invocation.completion.succeeded(
                new Object(),
                host(endpoint(x.prelude, x.activation, 0x7f000001L, 41001)),
                new NoOpConnectionBackend(),
                release::incrementAndGet);
        assertEquals(1, release.get());
        assertEquals(ProtosFutureValue.State.CANCELLED, future.state());
        assertTrue(future.resolvedValue().isEmpty());
    }

    private static Fixture fixture() throws Exception {
        ProtosPrelude prelude = new ProtosCoreBootstrap().bootstrap(CORE);
        ProtosActivation activation = prelude.newModuleActivation();
        RecordingBackend backend = new RecordingBackend();
        ProtosNetworkCapabilityValue network = new ProtosNetworkCapabilityValue(prelude, backend);
        return new Fixture(prelude, activation, backend, network);
    }

    private static ProtosFutureValue connect(Fixture x, ProtosObjectValue endpoint) {
        return (ProtosFutureValue)
                ProtosInvocation.invokeMessage(
                        x.network, "connectTcp", List.of(endpoint), x.activation);
    }

    private static ProtosObjectValue endpoint(
            ProtosPrelude prelude, ProtosActivation activation, long bits, long port) {
        Object addressFactory = prelude.ipAddressPrototypeForRuntime();
        ProtosObjectValue address =
                (ProtosObjectValue)
                        ProtosInvocation.invoke(
                                addressFactory,
                                List.of(integer(4), integer(bits)),
                                activation);
        Object endpointFactory = prelude.ipEndpointPrototypeForRuntime();
        return (ProtosObjectValue)
                ProtosInvocation.invoke(
                        endpointFactory, List.of(address, integer(port)), activation);
    }

    private static ProtosHostIpEndpoint host(ProtosObjectValue endpoint) {
        ProtosObjectValue address =
                (ProtosObjectValue) endpoint.readLocalSlot("address").orElseThrow();
        int version = ProtosNumericValueSupport.exactInt(
                address.readLocalSlot("version").orElseThrow());
        int port = ProtosNumericValueSupport.exactInt(
                endpoint.readLocalSlot("port").orElseThrow());
        byte[] bytes = ProtosNumericValueSupport.unsignedBigEndianOrNull(
                address.readLocalSlot("bits").orElseThrow(),
                version == 4 ? 4 : 16);
        return new ProtosHostIpEndpoint(version, bytes, port);
    }

    private static ProtosIntegerValue integer(long value) {
        return new ProtosIntegerValue(value);
    }

    private static void assertNonTransferable(ProtosPrelude prelude, ThrowingAction action) {
        ProtosSignalException failure = assertThrows(ProtosSignalException.class, action::run);
        assertSame(
                prelude.bindings().readLocalSlot("NonTransferableValue").orElseThrow(),
                failure.error().parent().orElseThrow());
    }

    private static void assertNonParallel(
            Method copy, Object value, ProtosActivation activation) {
        InvocationTargetException failure =
                assertThrows(
                        InvocationTargetException.class,
                        () ->
                                copy.invoke(
                                        null,
                                        value,
                                        activation,
                                        new IdentityHashMap<Object, Object>()));
        assertNotNull(failure.getCause());
        assertEquals("NonParallel", failure.getCause().getClass().getSimpleName());
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

    private record Fixture(
            ProtosPrelude prelude,
            ProtosActivation activation,
            RecordingBackend backend,
            ProtosNetworkCapabilityValue network) {}

    private static final class RecordingBackend implements ProtosNetworkConnectFlow.Backend {
        private final List<Invocation> invocations = new ArrayList<>();

        @Override
        public ProtosNetworkConnectFlow.Cancellation connect(
                ProtosHostIpEndpoint endpoint, ProtosNetworkConnectFlow.ConnectCompletion completion) {
            Invocation invocation = new Invocation(endpoint, completion);
            invocations.add(invocation);
            return invocation.cancelCount::incrementAndGet;
        }
    }

    private static final class Invocation {
        private final ProtosHostIpEndpoint endpoint;
        private final ProtosNetworkConnectFlow.ConnectCompletion completion;
        private final AtomicInteger cancelCount = new AtomicInteger();

        private Invocation(
                ProtosHostIpEndpoint endpoint,
                ProtosNetworkConnectFlow.ConnectCompletion completion) {
            this.endpoint = endpoint;
            this.completion = completion;
        }
    }

    private static final class NoOpConnectionBackend implements ProtosTcpConnectionFlow.Backend {
        @Override
        public ProtosByteIoFlow.Cancellation read(
                int maxBytes, ProtosByteIoFlow.ReadCompletion completion) {
            return () -> {};
        }

        @Override
        public ProtosByteIoFlow.Cancellation write(
                byte[] bytes, ProtosByteIoFlow.WriteCompletion completion) {
            return () -> {};
        }

        @Override
        public ProtosByteIoFlow.Cancellation shutdownRead(
                ProtosByteIoFlow.ShutdownCompletion completion) {
            return () -> {};
        }

        @Override
        public ProtosByteIoFlow.Cancellation shutdownWrite(
                ProtosByteIoFlow.ShutdownCompletion completion) {
            return () -> {};
        }

        @Override
        public void close(ProtosByteIoFlow.ReceiverCompletion completion) {
            completion.succeeded();
        }
    }

    @FunctionalInterface
    private interface ThrowingAction {
        Object run();
    }
}
