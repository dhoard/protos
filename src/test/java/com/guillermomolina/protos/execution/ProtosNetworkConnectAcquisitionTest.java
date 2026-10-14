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
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.guillermomolina.protos.runtime.ProtosActivation;
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
import com.guillermomolina.protos.runtime.ProtosTcpConnectionFlow;
import com.guillermomolina.protos.runtime.ProtosTcpConnectionValue;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

final class ProtosNetworkConnectAcquisitionTest {
    private static final Path CORE = Path.of("protos", "lib", "core");

    @Test
    void validConnectStartsIndependentHostNeutralAcquisitionsAndTransfersConnections()
            throws Exception {
        Fixture x = fixture();
        ProtosObjectValue remoteA = endpoint(x.prelude, x.activation, 0x01010101L, 443);
        ProtosObjectValue remoteB = endpoint(x.prelude, x.activation, 0x08080808L, 53);

        ProtosFutureValue first = connect(x, remoteA);
        ProtosFutureValue second = connect(x, remoteB);
        assertEquals(2, x.backend.invocations.size());
        assertEquals(ProtosFutureValue.State.PENDING, first.state());
        assertEquals(ProtosFutureValue.State.PENDING, second.state());
        assertEquals(4, x.backend.invocations.get(0).endpoint.version());
        assertEquals(443, x.backend.invocations.get(0).endpoint.port());
        assertArrayEquals(new byte[] {1, 1, 1, 1},
                x.backend.invocations.get(0).endpoint.octets());
        assertArrayEquals(new byte[] {8, 8, 8, 8},
                x.backend.invocations.get(1).endpoint.octets());

        ProtosObjectValue localB = endpoint(x.prelude, x.activation, 0x7f000001L, 41001);
        ProtosObjectValue localA = endpoint(x.prelude, x.activation, 0x7f000001L, 41000);
        AtomicInteger releaseA = new AtomicInteger();
        AtomicInteger releaseB = new AtomicInteger();
        x.backend.invocations.get(1).completion.succeeded(
                new Object(), host(localB), new NoOpConnectionBackend(), releaseB::incrementAndGet);
        x.backend.invocations.get(0).completion.succeeded(
                new Object(), host(localA), new NoOpConnectionBackend(), releaseA::incrementAndGet);

        assertTrue(x.activation.executionDomain().dispatchOne());
        assertTrue(x.activation.executionDomain().dispatchOne());
        ProtosTcpConnectionValue connectionA =
                assertInstanceOf(
                        ProtosTcpConnectionValue.class, first.resolvedValue().orElseThrow());
        ProtosTcpConnectionValue connectionB =
                assertInstanceOf(
                        ProtosTcpConnectionValue.class, second.resolvedValue().orElseThrow());
        assertTrue(connectionA.hasProtocolFlowForRuntime());
        assertTrue(connectionB.hasProtocolFlowForRuntime());
        assertSame(x.prelude.tcpConnectionPrototypeForRuntime(), connectionA.parent().orElseThrow());
        assertEquals(0, releaseA.get());
        assertEquals(0, releaseB.get());

        Object observedLocal =
                ProtosInvocation.invokeMessage(
                        connectionA, "localEndpoint", List.of(), x.activation);
        Object observedRemote =
                ProtosInvocation.invokeMessage(
                        connectionA, "remoteEndpoint", List.of(), x.activation);
        assertSame(
                ProtosBooleanValue.TRUE,
                ProtosInvocation.invokeMessage(
                        observedLocal, "==", List.of(localA), x.activation));
        assertSame(
                ProtosBooleanValue.TRUE,
                ProtosInvocation.invokeMessage(
                        observedRemote, "==", List.of(remoteA), x.activation));
        // Endpoint object identity is deliberately not asserted by C4/D052.
    }

    @Test
    void invalidRequestFailsBeforeNetworkAuthorityIsExercised() throws Exception {
        Fixture x = fixture();
        ProtosObjectValue fake = new ProtosObjectValue(x.prelude.ipEndpointPrototypeForRuntime());
        fake.createLocalSlot("address", endpointAddress(x.prelude, x.activation, 0x01010101L));
        fake.createLocalSlot("port", integer(443));
        // OPEN rather than FROZEN, therefore not a recognized standard endpoint.

        ProtosFutureValue invalidEndpoint = connect(x, fake);
        assertFailedAs(invalidEndpoint, x.prelude, "InvalidIOArgument");
        assertTrue(x.backend.invocations.isEmpty());

        ProtosFutureValue wrongArity =
                future(x.network, "connectTcp", List.of(), x.activation);
        assertFailedAs(wrongArity, x.prelude, "InvalidIOArgument");
        assertTrue(x.backend.invocations.isEmpty());

        ProtosObjectValue prototypeChild = new ProtosObjectValue(x.prelude.networkPrototype());
        ProtosFutureValue noAuthority =
                future(
                        prototypeChild,
                        "connectTcp",
                        List.of(endpoint(x.prelude, x.activation, 0x01010101L, 443)),
                        x.activation);
        assertFailedAs(noAuthority, x.prelude, "InvalidIOArgument");
        assertTrue(x.backend.invocations.isEmpty());
    }

    @Test
    void nonOperationalAuthorityTargetFailsPortablyWithoutSelectingABackend() throws Exception {
        ProtosPrelude prelude = core();
        ProtosActivation activation = prelude.newModuleActivation();
        ProtosNetworkCapabilityValue network =
                new ProtosNetworkCapabilityValue(prelude, new Object());
        ProtosFutureValue result =
                future(
                        network,
                        "connectTcp",
                        List.of(endpoint(prelude, activation, 0x01010101L, 443)),
                        activation);
        assertFailedAs(result, prelude, "IOError");
    }

    @Test
    void precommitCancellationWinsAndLateOrDuplicateResourcesAreReleased() throws Exception {
        Fixture x = fixture();
        ProtosObjectValue remote = endpoint(x.prelude, x.activation, 0x01010101L, 443);
        ProtosFutureValue cancelled = connect(x, remote);
        Invocation cancelledInvocation = x.backend.invocations.get(0);

        assertTrue(cancelled.cancelRequest());
        assertEquals(ProtosFutureValue.State.CANCELLED, cancelled.state());
        assertEquals(1, cancelledInvocation.cancelCount.get());
        AtomicInteger lateRelease = new AtomicInteger();
        cancelledInvocation.completion.succeeded(
                new Object(),
                host(endpoint(x.prelude, x.activation, 0x7f000001L, 41000)),
                new NoOpConnectionBackend(),
                lateRelease::incrementAndGet);
        assertEquals(1, lateRelease.get());
        assertEquals(ProtosFutureValue.State.CANCELLED, cancelled.state());

        ProtosFutureValue completed = connect(x, remote);
        Invocation completedInvocation = x.backend.invocations.get(1);
        AtomicInteger firstRelease = new AtomicInteger();
        completedInvocation.completion.succeeded(
                new Object(),
                host(endpoint(x.prelude, x.activation, 0x7f000001L, 41001)),
                new NoOpConnectionBackend(),
                firstRelease::incrementAndGet);
        assertTrue(x.activation.executionDomain().dispatchOne());
        assertEquals(ProtosFutureValue.State.RESOLVED, completed.state());
        assertFalse(completed.cancelRequest());
        assertEquals(0, firstRelease.get());

        AtomicInteger duplicateRelease = new AtomicInteger();
        completedInvocation.completion.succeeded(
                new Object(),
                host(endpoint(x.prelude, x.activation, 0x7f000001L, 41002)),
                new NoOpConnectionBackend(),
                duplicateRelease::incrementAndGet);
        assertEquals(1, duplicateRelease.get());
    }

    @Test
    void asynchronousCompletionRunsOnlyWhenTheOwningActorDispatches() throws Exception {
        Fixture x = fixture();
        ProtosFutureValue future = connect(x,
                endpoint(x.prelude, x.activation, 0x01010101L, 443));
        AtomicInteger released = new AtomicInteger();
        ProtosNetworkConnectFlow.ConnectCompletion completion =
                x.backend.invocations.get(0).completion;

        java.util.concurrent.CompletableFuture.runAsync(
                () -> completion.succeeded(
                        new Object(),
                        new ProtosHostIpEndpoint(
                                4, new byte[] {127, 0, 0, 1}, 41000),
                        new NoOpConnectionBackend(),
                        released::incrementAndGet)).get();

        assertEquals(ProtosFutureValue.State.PENDING, future.state());
        assertTrue(x.activation.executionDomain().dispatchOne());
        assertEquals(ProtosFutureValue.State.RESOLVED, future.state());
        assertEquals(0, released.get());
    }

    @Test
    void actorTerminationDropsQueuedUntransferredConnectionExactlyOnce()
            throws Exception {
        Fixture x = fixture();
        ProtosFutureValue future = connect(x,
                endpoint(x.prelude, x.activation, 0x01010101L, 443));
        AtomicInteger released = new AtomicInteger();
        ProtosNetworkConnectFlow.ConnectCompletion completion =
                x.backend.invocations.get(0).completion;

        java.util.concurrent.CompletableFuture.runAsync(
                () -> completion.succeeded(
                        new Object(),
                        new ProtosHostIpEndpoint(
                                4, new byte[] {127, 0, 0, 1}, 41000),
                        new NoOpConnectionBackend(),
                        released::incrementAndGet)).get();

        assertEquals(ProtosFutureValue.State.PENDING, future.state());
        assertTrue(future.cancelRequest());
        assertEquals(ProtosFutureValue.State.PENDING, future.state());
        assertEquals(0, released.get());
        x.activation.executionDomain().actorTerminated();
        assertEquals(1, released.get());
        assertEquals(ProtosFutureValue.State.PENDING, future.state());
        assertFalse(x.activation.executionDomain().dispatchOne());
    }

    @Test
    void actorTerminationRacingCancellationHandleRegistrationStillCancelsBackend()
            throws Exception {
        ProtosPrelude prelude = core();
        ProtosActivation activation = prelude.newModuleActivation();
        AtomicInteger cancels = new AtomicInteger();
        ProtosNetworkConnectFlow.Backend racingBackend =
                (endpoint, completion) -> {
                    activation.executionDomain().actorTerminated();
                    return cancels::incrementAndGet;
                };
        ProtosNetworkCapabilityValue network =
                new ProtosNetworkCapabilityValue(prelude, racingBackend);
        ProtosFutureValue future =
                future(
                        network,
                        "connectTcp",
                        List.of(endpoint(prelude, activation, 0x01010101L, 443)),
                        activation);

        assertEquals(ProtosFutureValue.State.CANCELLED, future.state());
        assertEquals(1, cancels.get());
    }

    @Test
    void backendFailureExceptionAndInvalidLocalDescriptorMapToIoErrorWithCustody() throws Exception {
        Fixture x = fixture();
        ProtosObjectValue remote = endpoint(x.prelude, x.activation, 0x01010101L, 443);
        ProtosFutureValue failed = connect(x, remote);
        x.backend.invocations.get(0).completion.failed();
        assertTrue(x.activation.executionDomain().dispatchOne());
        assertFailedAs(failed, x.prelude, "IOError");

        ProtosFutureValue malformed = connect(x, remote);
        AtomicInteger release = new AtomicInteger();
        x.backend.invocations.get(1).completion.succeeded(
                new Object(), null, new NoOpConnectionBackend(), release::incrementAndGet);
        assertTrue(x.activation.executionDomain().dispatchOne());
        assertFailedAs(malformed, x.prelude, "IOError");
        assertEquals(1, release.get());

        ProtosNetworkConnectFlow.Backend throwing =
                (endpoint, completion) -> { throw new IllegalStateException("backend boom"); };
        ProtosNetworkCapabilityValue throwingNetwork =
                new ProtosNetworkCapabilityValue(x.prelude, throwing);
        ProtosFutureValue thrown =
                future(throwingNetwork, "connectTcp", List.of(remote), x.activation);
        assertFailedAs(thrown, x.prelude, "IOError");
    }

    @Test
    void actorTerminationUsesOrdinaryPrecommitCancellationAndLateCustody() throws Exception {
        Fixture x = fixture();
        ProtosFutureValue future =
                connect(x, endpoint(x.prelude, x.activation, 0x01010101L, 443));
        Invocation invocation = x.backend.invocations.get(0);
        ProtosObjectValue lateLocal =
                endpoint(x.prelude, x.activation, 0x7f000001L, 41000);

        x.activation.executionDomain().actorTerminated();
        assertEquals(ProtosFutureValue.State.CANCELLED, future.state());
        assertEquals(1, invocation.cancelCount.get());

        AtomicInteger release = new AtomicInteger();
        invocation.completion.succeeded(
                new Object(),
                host(lateLocal),
                new NoOpConnectionBackend(),
                release::incrementAndGet);
        assertEquals(1, release.get());
    }

    private static Fixture fixture() throws Exception {
        ProtosPrelude prelude = core();
        ProtosActivation activation = prelude.newModuleActivation();
        RecordingBackend backend = new RecordingBackend();
        ProtosNetworkCapabilityValue network =
                new ProtosNetworkCapabilityValue(prelude, backend);
        return new Fixture(prelude, activation, backend, network);
    }

    private static ProtosPrelude core() throws Exception {
        return new ProtosCoreBootstrap().bootstrap(CORE);
    }

    private static ProtosFutureValue connect(Fixture x, ProtosObjectValue endpoint) {
        return future(x.network, "connectTcp", List.of(endpoint), x.activation);
    }

    private static ProtosFutureValue future(
            Object receiver, String selector, List<?> arguments, ProtosActivation activation) {
        return (ProtosFutureValue)
                ProtosInvocation.invokeMessage(receiver, selector, arguments, activation);
    }

    private static ProtosObjectValue endpoint(
            ProtosPrelude prelude, ProtosActivation activation, long bits, long port) {
        ProtosObjectValue address = endpointAddress(prelude, activation, bits);
        Object endpointFactory = prelude.ipEndpointPrototypeForRuntime();
        return (ProtosObjectValue)
                ProtosInvocation.invoke(
                        endpointFactory, List.of(address, integer(port)), activation);
    }

    private static ProtosObjectValue endpointAddress(
            ProtosPrelude prelude, ProtosActivation activation, long bits) {
        Object addressFactory = prelude.ipAddressPrototypeForRuntime();
        return (ProtosObjectValue)
                ProtosInvocation.invoke(
                        addressFactory,
                        List.of(integer(4), new ProtosIntegerValue(bits)),
                        activation);
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

    private static void assertFailedAs(
            ProtosFutureValue future, ProtosPrelude prelude, String prototypeName) {
        assertEquals(ProtosFutureValue.State.FAILED, future.state());
        assertSame(
                prelude.bindings().readLocalSlot(prototypeName).orElseThrow(),
                future.failedError().orElseThrow().parent().orElseThrow());
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
}
