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
import static org.junit.jupiter.api.Assertions.assertNotSame;
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
import com.guillermomolina.protos.runtime.ProtosNetworkListenFlow;
import com.guillermomolina.protos.runtime.ProtosNullValue;
import com.guillermomolina.protos.runtime.ProtosObjectValue;
import com.guillermomolina.protos.runtime.ProtosPrelude;
import com.guillermomolina.protos.runtime.ProtosSignalException;
import com.guillermomolina.protos.runtime.ProtosTcpConnectionFlow;
import com.guillermomolina.protos.runtime.ProtosTcpConnectionValue;
import com.guillermomolina.protos.runtime.ProtosTcpListenerFlow;
import com.guillermomolina.protos.runtime.ProtosTcpListenerValue;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** I028-D5 integrated D1-D4 listener/listen/accept/lifecycle closure evidence. */
final class ProtosTcpListenerIntegratedConformanceTest {
    private static final Path CORE = Path.of("protos", "lib", "core");

    @Test
    void acquiredListenerComposesListenAcceptConnectionLifecycleAndTransferConfinement()
            throws Exception {
        Fixture x = fixture();
        ProtosObjectValue request =
                request(integer(4), ProtosNullValue.INSTANCE, integer(51000));
        ProtosFutureValue listen = listen(x, request);
        assertEquals(1, x.networkBackend.invocations.size());
        ListenInvocation listenInvocation = x.networkBackend.invocations.get(0);
        assertEquals(4, listenInvocation.request.ipVersion());
        assertEquals(51000, listenInvocation.request.portConstraint());

        RecordingListenerBackend listenerBackend = new RecordingListenerBackend();
        AtomicInteger listenerRelease = new AtomicInteger();
        listenInvocation.completion.succeeded(
                new Object(),
                51000,
                listenerBackend,
                listenerRelease::incrementAndGet);
                assertEquals(ProtosFutureValue.State.PENDING, listen.state());
                assertTrue(listen.domain().dispatchOne());

        ProtosTcpListenerValue listener =
                assertInstanceOf(ProtosTcpListenerValue.class, listen.resolvedValue().orElseThrow());
        assertSame(x.prelude.tcpListenerPrototypeForRuntime(), listener.parent().orElseThrow());
        assertTrue(listener.isOpen());
        assertTrue(listener.hasAcceptForRuntime());
        assertEquals(0, listenerRelease.get());
        assertEquals(String.valueOf("51000"), com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerDecimalText(ProtosInvocation.invokeMessage(
                                        listener, "localPort", List.of(), x.activation)));

        ProtosIntegerValue marker = integer(7);
        listener.createLocalSlot("label", marker);
        assertSame(marker, listener.readLocalSlot("label").orElseThrow());

        ProtosObjectValue descendant = new ProtosObjectValue(listener);
        assertThrows(
                ProtosSignalException.class,
                () -> ProtosInvocation.invokeMessage(descendant, "accept", List.of(), x.activation));
        assertNonTransferable(
                x.prelude, () -> ProtosActorValueTransfer.snapshotValue(listener, x.activation));
        assertNonTransferable(
                x.prelude, () -> ProtosActorValueTransfer.snapshotValue(descendant, x.activation));
        Method copy = pCopyMethod();
        assertNonParallel(copy, listener, x.activation);
        assertNonParallel(copy, descendant, x.activation);

        ProtosFutureValue first = accept(listener, x.activation);
        ProtosFutureValue second = accept(listener, x.activation);
        assertEquals(2, listenerBackend.accepts.size());
        assertEquals(ProtosFutureValue.State.PENDING, first.state());
        assertEquals(ProtosFutureValue.State.PENDING, second.state());

        ProtosObjectValue localA = endpoint(x, 0x7f000001L, 51000);
        ProtosObjectValue remoteA = endpoint(x, 0x01010101L, 42001);
        ProtosObjectValue localB = endpoint(x, 0x7f000001L, 51000);
        ProtosObjectValue remoteB = endpoint(x, 0x08080808L, 42002);
        AtomicInteger releaseA = new AtomicInteger();
        AtomicInteger releaseB = new AtomicInteger();
        listenerBackend.accepts.get(1).completion.succeeded(
                new Object(), host(localB), host(remoteB),
                new NoOpConnectionBackend(), releaseB::incrementAndGet);
                assertEquals(ProtosFutureValue.State.PENDING, second.state());
                assertTrue(second.domain().dispatchOne());
        listenerBackend.accepts.get(0).completion.succeeded(
                new Object(), host(localA), host(remoteA),
                new NoOpConnectionBackend(), releaseA::incrementAndGet);
                assertEquals(ProtosFutureValue.State.PENDING, first.state());
                assertTrue(first.domain().dispatchOne());

        ProtosTcpConnectionValue connectionA =
                assertInstanceOf(ProtosTcpConnectionValue.class, first.resolvedValue().orElseThrow());
        ProtosTcpConnectionValue connectionB =
                assertInstanceOf(ProtosTcpConnectionValue.class, second.resolvedValue().orElseThrow());
        assertNotSame(connectionA, connectionB);
        assertEquals(0, releaseA.get());
        assertEquals(0, releaseB.get());
        assertEndpointEquals(
                x,
                ProtosInvocation.invokeMessage(
                        connectionA, "localEndpoint", List.of(), x.activation),
                localA);
        assertEndpointEquals(
                x,
                ProtosInvocation.invokeMessage(
                        connectionA, "remoteEndpoint", List.of(), x.activation),
                remoteA);
        // Endpoint object identity is deliberately not asserted by D052/D5.
        assertNonTransferable(
                x.prelude,
                () -> ProtosActorValueTransfer.snapshotValue(connectionA, x.activation));
        assertNonParallel(copy, connectionA, x.activation);

        ProtosFutureValue pending = accept(listener, x.activation);
        AcceptInvocation pendingInvocation = listenerBackend.accepts.get(2);
        ProtosFutureValue close = future(listener, "close", List.of(), x.activation);
        assertFailedAs(pending, x.prelude, "IOLifecycleError");
        assertEquals(1, pendingInvocation.cancels.get());
        assertEquals(1, listenerBackend.closeStarts);
        assertTrue(listener.isOpen());

        AtomicInteger lateRelease = new AtomicInteger();
        pendingInvocation.completion.succeeded(
                new Object(),
                host(endpoint(x, 0x7f000001L, 51000)),
                host(endpoint(x, 0x09090909L, 42003)),
                new NoOpConnectionBackend(),
                lateRelease::incrementAndGet);
        assertEquals(1, lateRelease.get());
        assertEquals(ProtosFutureValue.State.PENDING, close.state());

        listenerBackend.closeCompletion.succeeded();
        assertEquals(ProtosFutureValue.State.RESOLVED, close.state());
        assertSame(listener, close.resolvedValue().orElseThrow());
        assertTrue(listener.isOpen());
    }

    @Test
    void cancelledListenNeverPublishesListenerAndLateCustodyIsReleased() throws Exception {
        Fixture x = fixture();
        ProtosFutureValue listen =
                listen(x, request(integer(4), ProtosNullValue.INSTANCE, integer(51000)));
        ListenInvocation invocation = x.networkBackend.invocations.get(0);

        assertTrue(listen.cancelRequest());
        assertEquals(ProtosFutureValue.State.CANCELLED, listen.state());
        assertEquals(1, invocation.cancels.get());
        assertTrue(listen.resolvedValue().isEmpty());

        AtomicInteger release = new AtomicInteger();
        invocation.completion.succeeded(
                new Object(),
                51000,
                new RecordingListenerBackend(),
                release::incrementAndGet);
        assertEquals(1, release.get());
        assertEquals(ProtosFutureValue.State.CANCELLED, listen.state());
        assertTrue(listen.resolvedValue().isEmpty());
    }

    private static Fixture fixture() throws Exception {
        ProtosPrelude prelude = new ProtosCoreBootstrap().bootstrap(CORE);
        ProtosActivation activation = prelude.newModuleActivation();
        RecordingNetworkBackend networkBackend = new RecordingNetworkBackend();
        ProtosNetworkCapabilityValue network =
                new ProtosNetworkCapabilityValue(prelude, networkBackend);
        return new Fixture(prelude, activation, networkBackend, network);
    }

    private static ProtosFutureValue listen(Fixture x, ProtosObjectValue request) {
        return future(x.network, "listenTcp", List.of(request), x.activation);
    }

    private static ProtosFutureValue accept(
            ProtosTcpListenerValue listener, ProtosActivation activation) {
        return future(listener, "accept", List.of(), activation);
    }

    private static ProtosFutureValue future(
            Object receiver, String selector, List<?> arguments, ProtosActivation activation) {
        return assertInstanceOf(
                ProtosFutureValue.class,
                ProtosInvocation.invokeMessage(receiver, selector, arguments, activation));
    }

    private static ProtosObjectValue request(Object version, Object address, Object port) {
        ProtosObjectValue result = new ProtosObjectValue(ProtosObjectValue.rootObject());
        result.createLocalSlot("ipVersion", version);
        result.createLocalSlot("address", address);
        result.createLocalSlot("port", port);
        return result;
    }

    private static ProtosObjectValue endpoint(Fixture x, long bits, long port) {
        Object addressFactory = x.prelude.ipAddressPrototypeForRuntime();
        ProtosObjectValue address =
                (ProtosObjectValue)
                        ProtosInvocation.invoke(
                                addressFactory,
                                List.of(integer(4), new ProtosIntegerValue(bits)),
                                x.activation);
        Object endpointFactory = x.prelude.ipEndpointPrototypeForRuntime();
        return (ProtosObjectValue)
                ProtosInvocation.invoke(
                        endpointFactory, List.of(address, integer(port)), x.activation);
    }

    private static ProtosHostIpEndpoint host(ProtosObjectValue endpoint) {
        ProtosObjectValue address = (ProtosObjectValue)
                endpoint.readLocalSlot("address").orElseThrow();
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

    private static void assertEndpointEquals(Fixture x, Object actual, Object expected) {
        assertSame(
                ProtosBooleanValue.TRUE,
                ProtosInvocation.invokeMessage(actual, "==", List.of(expected), x.activation));
    }

    private static void assertFailedAs(ProtosFutureValue future, ProtosPrelude prelude, String name) {
        assertEquals(ProtosFutureValue.State.FAILED, future.state());
        assertSame(
                prelude.bindings().readLocalSlot(name).orElseThrow(),
                future.failedError().orElseThrow().parent().orElseThrow());
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
            RecordingNetworkBackend networkBackend,
            ProtosNetworkCapabilityValue network) {}

    private static final class RecordingNetworkBackend implements ProtosNetworkListenFlow.Backend {
        private final List<ListenInvocation> invocations = new ArrayList<>();

        @Override
        public ProtosNetworkListenFlow.Cancellation listen(
                ProtosNetworkListenFlow.ListenRequest request,
                ProtosNetworkListenFlow.ListenCompletion completion) {
            ListenInvocation invocation = new ListenInvocation(request, completion);
            invocations.add(invocation);
            return invocation.cancels::incrementAndGet;
        }
    }

    private static final class ListenInvocation {
        private final ProtosNetworkListenFlow.ListenRequest request;
        private final ProtosNetworkListenFlow.ListenCompletion completion;
        private final AtomicInteger cancels = new AtomicInteger();

        private ListenInvocation(
                ProtosNetworkListenFlow.ListenRequest request,
                ProtosNetworkListenFlow.ListenCompletion completion) {
            this.request = request;
            this.completion = completion;
        }
    }

    private static final class RecordingListenerBackend implements ProtosTcpListenerFlow.Backend {
        private final List<AcceptInvocation> accepts = new ArrayList<>();
        private int closeStarts;
        private ProtosTcpListenerFlow.CloseCompletion closeCompletion;

        @Override
        public void close(ProtosTcpListenerFlow.CloseCompletion completion) {
            closeStarts++;
            closeCompletion = completion;
        }

        @Override
        public ProtosTcpListenerFlow.Cancellation accept(
                ProtosTcpListenerFlow.AcceptCompletion completion) {
            AcceptInvocation invocation = new AcceptInvocation(completion);
            accepts.add(invocation);
            return invocation.cancels::incrementAndGet;
        }
    }

    private static final class AcceptInvocation {
        private final ProtosTcpListenerFlow.AcceptCompletion completion;
        private final AtomicInteger cancels = new AtomicInteger();

        private AcceptInvocation(ProtosTcpListenerFlow.AcceptCompletion completion) {
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
