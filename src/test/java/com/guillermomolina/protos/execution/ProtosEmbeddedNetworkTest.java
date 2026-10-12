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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.guillermomolina.protos.runtime.ProtosActorExecutionDomain;
import com.guillermomolina.protos.runtime.ProtosFutureValue;
import com.guillermomolina.protos.runtime.ProtosNetworkCapabilityValue;
import com.guillermomolina.protos.runtime.ProtosObjectValue;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Value;
import org.graalvm.polyglot.io.IOAccess;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * PLAT054-3E4 / I086: the default Network of the standard Polyglot embedding (HOST-NET-1 and
 * HOST-NET-2 in {@code PROCESS_IO.md}, Embedding network grant and thread policy) through the
 * public {@code Context} API. TCP peers are loopback sockets owned by the test on ephemeral ports;
 * every wait is a bounded condition, never a sleep.
 */
final class ProtosEmbeddedNetworkTest {
    private static final String CORE = Path.of("protos", "lib", "core").toAbsolutePath().toString();
    private static final long SAFETY_SECONDS = 5;
    private static final int SAFETY_MILLIS = (int) TimeUnit.SECONDS.toMillis(SAFETY_SECONDS);

    /** The initial module: helpers are Closures so later host entries reach its {@code network}. */
    private static final String PROGRAM =
            """
            IpAddress: import("std:network/IpAddresses").IpAddress
            IpEndpoint: import("std:network/IpEndpoints").IpEndpoint
            loopback: IpAddress(4, 2130706433)
            before: 0
            after: 0
            listener: null
            held: null
            trivial: () => { 1 }
            echo: (port) => {
                connection: network.connectTcp(IpEndpoint(loopback, port)).value()
                inbound: connection.read(16).value()
                connection.write(inbound).value()
                connection.shutdownWrite().value()
                eof: connection.read(16).value()
                connection.close().value()
                (inbound.size() == 1) && (inbound.at(0) == 6) && (eof == null) && (connection.remoteEndpoint().port == port)
            }
            openListener: () => {
                request: {
                    ipVersion: 4
                    address: loopback
                    port: null
                }
                listener = network.listenTcp(request).value()
                listener.localPort()
            }
            acceptOne: () => {
                before = before + 1
                accepted: listener.accept().value()
                after = after + 1
                inbound: accepted.read(1).value()
                accepted.close().value()
                inbound.at(0)
            }
            holdConnection: (port) => {
                held = network.connectTcp(IpEndpoint(loopback, port)).value()
                0
            }
            burst: (port) => {
                a1: listener.accept()
                a2: listener.accept()
                a3: listener.accept()
                c1: network.connectTcp(IpEndpoint(loopback, port))
                c2: network.connectTcp(IpEndpoint(loopback, port))
                c3: network.connectTcp(IpEndpoint(loopback, port))
                c1.value().close().value()
                c2.value().close().value()
                c3.value().close().value()
                a1.value().close().value()
                a2.value().close().value()
                a3.value().close().value()
                3
            }
            refusedConnect: (port) => {
                IOError.handle(
                    () => { network.connectTcp(IpEndpoint(loopback, port)).value()
                 false },
                    (error) => { true }
                )
            }
            invalidConnect: () => {
                InvalidIOArgument.handle(
                    () => { network.connectTcp("127.0.0.1:80").value()
                 false },
                    (error) => { true }
                )
            }
            pendingAccept: null
            cancelAccept: () => {
                pendingAccept = listener.accept()
                pendingAccept.cancel()
                0
            }
            0
            """;

    private static Context newContext(boolean files, boolean sockets, boolean threads) {
        return Context.newBuilder(ProtosLanguage.ID)
                .option("protos.CoreRoot", CORE)
                .allowIO(
                        IOAccess.newBuilder()
                                .allowHostFileAccess(files)
                                .allowHostSocketAccess(sockets)
                                .build())
                .allowCreateThread(threads)
                .build();
    }

    /** Sockets granted, guest threads and file access denied: the minimal network grant. */
    private static Context socketsOnly() {
        return newContext(false, true, false);
    }

    private static ProtosEmbeddedProcess embedded(Context context) {
        context.enter();
        try {
            return ProtosLanguageContext.current().embeddedProcessOrNull();
        } finally {
            context.leave();
        }
    }

    private static Value member(Context context, String name) {
        return context.getBindings(ProtosLanguage.ID).getMember(name);
    }

    /** Reads from a module captured while live: a stopped Process selects no module. */
    private static long slot(ProtosObjectValue module, String name) {
        return Long.parseLong(com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerDecimalText(module.readLocalSlot(name).orElseThrow()));
    }

    private static ProtosEmbeddedNetworkCustody custody(Context context) {
        return embedded(context).networkCustodyForTesting();
    }

    private static void awaitCondition(BooleanSupplier condition, String description) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(SAFETY_SECONDS);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                fail("timed out waiting until " + description);
            }
            Thread.onSpinWait();
        }
    }

    private static void assertRetired(ProtosNioHostIoPoller poller) {
        assertTrue(poller.isTerminatedForTesting(), "the poller is retired");
        assertFalse(poller.threadForTesting().isAlive(), "the poller thread ended");
    }

    private static void assertRefused(int port) {
        try (Socket probe = new Socket()) {
            assertThrows(
                    ConnectException.class,
                    () ->
                            probe.connect(
                                    new InetSocketAddress(InetAddress.getLoopbackAddress(), port),
                                    SAFETY_MILLIS),
                    "the listener was released");
        } catch (IOException unexpected) {
            throw new UncheckedIOException(unexpected);
        }
    }

    /**
     * A loopback peer that sends one octet, expects it echoed by the guest (the Core exposes no
     * public {@code Bytes} constructor, so the guest echoes the Bytes it read), and awaits the
     * guest's write half-close before closing.
     */
    private static int echoRoundTrip(Value echo) throws Exception {
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            server.setSoTimeout(SAFETY_MILLIS);
            CompletableFuture<Integer> peer =
                    CompletableFuture.supplyAsync(
                            () -> {
                                try (Socket socket = server.accept()) {
                                    socket.setSoTimeout(SAFETY_MILLIS);
                                    InputStream in = socket.getInputStream();
                                    socket.getOutputStream().write(6);
                                    socket.getOutputStream().flush();
                                    int echoed = in.read();
                                    // The guest's shutdownWrite is a half-close: EOF here.
                                    return in.read() == -1 ? echoed : -1;
                                } catch (IOException failure) {
                                    throw new UncheckedIOException(failure);
                                }
                            });
            assertTrue(echo.execute(server.getLocalPort()).asBoolean(), "guest-side checks");
            return peer.get(SAFETY_SECONDS, TimeUnit.SECONDS);
        }
    }

    /** One host entry on its own thread, so the test thread can observe it while it waits. */
    private static final class Entry {
        final CompletableFuture<Object> outcome = new CompletableFuture<>();
        final Thread thread;

        Entry(Value executable) {
            thread =
                    new Thread(
                            () -> {
                                try {
                                    outcome.complete(executable.execute());
                                } catch (Throwable failure) {
                                    outcome.complete(failure);
                                }
                            },
                            "host-entry");
            thread.setDaemon(true);
            thread.start();
        }

        void awaitSuspended(BooleanSupplier reached) {
            awaitCondition(
                    () ->
                            outcome.isDone()
                                    || (reached.getAsBoolean()
                                            && thread.getState() == Thread.State.WAITING),
                    "the host entry suspends");
            assertFalse(outcome.isDone(), "the entry is suspended, not returned");
        }

        Object await() throws Exception {
            try {
                return outcome.get(SAFETY_SECONDS, TimeUnit.SECONDS);
            } finally {
                thread.join(SAFETY_MILLIS);
            }
        }
    }

    @ParameterizedTest(name = "files={0} sockets={1} threads={2}")
    @CsvSource({
        "false, false, false",
        "false, true,  false",
        "false, true,  true",
        "false, false, true",
        "true,  false, true",
    })
    void effectiveSocketAccessAloneGrantsTheInitialNetwork(
            boolean files, boolean sockets, boolean threads) throws Exception {
        try (Context context = newContext(files, sockets, threads)) {
            Value bindings = context.getBindings(ProtosLanguage.ID);
            assertNull(embedded(context), "building the Context creates no Process");
            context.eval(ProtosLanguage.ID, PROGRAM);
            ProtosEmbeddedProcess process = embedded(context);
            assertEquals(sockets, bindings.hasMember("network"), "slot present only when granted");
            assertEquals(
                    files, bindings.hasMember("filesystem"), "Filesystem follows file access only");
            ProtosEmbeddedNetworkCustody custody = process.networkCustodyForTesting();
            if (!sockets) {
                assertNull(custody, "no Network custody without effective socket access");
                return;
            }
            ProtosObjectValue module = process.selectedModuleContext().orElseThrow();
            ProtosNetworkCapabilityValue network =
                    assertInstanceOf(
                            ProtosNetworkCapabilityValue.class,
                            module.readLocalSlot("network").orElseThrow());
            assertSame(custody, network.authorityTargetForRuntime());
            assertNull(custody.pollerForTesting(), "a granted but unused Network has no poller");

            assertEquals(6, echoRoundTrip(member(context, "echo")));
            assertNotNull(custody.pollerForTesting(), "first use materializes the backend");
            assertFalse(
                    process.actorCarrierSubstrateInitializedForTesting(),
                    "Network needs no guest thread or Actor carrier");
        }
    }

    @Test
    void laterHostEvaluationsReceiveNoImplicitNetwork() {
        try (Context context = socketsOnly()) {
            Value bindings = context.getBindings(ProtosLanguage.ID);
            context.eval(ProtosLanguage.ID, "a: 1\n0");
            assertTrue(bindings.hasMember("network"));
            context.eval(ProtosLanguage.ID, "b: 2\n0");
            assertEquals(Set.of("b"), bindings.getMemberKeys(), "bootstrap slots are initial-only");
        }
    }

    @Test
    void unusedNetworkKeepsTrivialCallsCompactAndCloseActivatesNothing() {
        ProtosEmbeddedNetworkCustody custody;
        try (Context context = socketsOnly()) {
            context.eval(ProtosLanguage.ID, PROGRAM);
            ProtosEmbeddedProcess process = embedded(context);
            custody = process.networkCustodyForTesting();
            ProtosActorExecutionDomain domain = process.rootExecutionDomain();
            Value trivial = member(context, "trivial");
            for (int index = 0; index < 5; index++) {
                assertEquals(1, trivial.execute().asInt());
                assertEquals(0, domain.liveTaskCount(), "no Task or scheduler work");
                assertFalse(process.actorCarrierSubstrateInitializedForTesting());
                assertNull(custody.pollerForTesting());
            }
        }
        assertTrue(custody.isClosedForTesting(), "Context close retires the custody");
        assertNull(custody.pollerForTesting());
        assertEquals(0, custody.activationCountForTesting(), "no poller was started to be closed");
    }

    @Test
    void listenAcceptsAndComposesWithSuspendibleFutureValue() throws Exception {
        try (Context context = socketsOnly()) {
            context.eval(ProtosLanguage.ID, PROGRAM);
            int port = member(context, "openListener").execute().asInt();
            assertTrue(port > 0, "an ephemeral non-zero local port");
            try (Socket client = new Socket(InetAddress.getLoopbackAddress(), port)) {
                client.getOutputStream().write(42);
                client.getOutputStream().flush();
                assertEquals(42, member(context, "acceptOne").execute().asInt());
                client.setSoTimeout(SAFETY_MILLIS);
                assertEquals(-1, client.getInputStream().read(), "the guest closed its side");
            }
        }
    }

    @Test
    void acquisitionErrorsAndCancellationKeepTheirContracts() throws Exception {
        try (Context context = socketsOnly()) {
            context.eval(ProtosLanguage.ID, PROGRAM);
            int unused;
            try (ServerSocket reserved = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
                unused = reserved.getLocalPort();
            }
            assertTrue(member(context, "refusedConnect").execute(unused).asBoolean(), "IOError");
            assertTrue(
                    member(context, "invalidConnect").execute().asBoolean(),
                    "no String-to-endpoint coercion");
            member(context, "openListener").execute();
            ProtosObjectValue module = embedded(context).selectedModuleContext().orElseThrow();
            member(context, "cancelAccept").execute();
            ProtosFutureValue pending =
                    assertInstanceOf(
                            ProtosFutureValue.class,
                            module.readLocalSlot("pendingAccept").orElseThrow());
            awaitCondition(() -> !pending.isPending(), "the cancelled accept terminalizes");
            assertEquals(ProtosFutureValue.State.CANCELLED, pending.state());
        }
    }

    @Test
    void concurrentOperationsShareOneContextPoller() throws Exception {
        try (Context context = socketsOnly()) {
            context.eval(ProtosLanguage.ID, PROGRAM);
            int port = member(context, "openListener").execute().asInt();
            ProtosEmbeddedNetworkCustody custody = custody(context);
            ProtosNioHostIoPoller poller = custody.pollerForTesting();
            assertEquals(3, member(context, "burst").execute(port).asInt());
            assertSame(poller, custody.pollerForTesting(), "no poller per operation");
            assertEquals(1, custody.activationCountForTesting(), "one host poller per Context");
            assertTrue(poller.threadForTesting().isAlive());
        }
    }

    @Test
    void contextCloseReleasesOpenConnectionsAndListeners() throws Exception {
        ProtosNioHostIoPoller poller;
        int listenerPort;
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            server.setSoTimeout(SAFETY_MILLIS);
            Socket peer;
            try (Context context = socketsOnly()) {
                context.eval(ProtosLanguage.ID, PROGRAM);
                listenerPort = member(context, "openListener").execute().asInt();
                assertEquals(0, member(context, "holdConnection").execute(server.getLocalPort()).asInt());
                peer = server.accept();
                poller = custody(context).pollerForTesting();
            }
            try (Socket held = peer) {
                held.setSoTimeout(SAFETY_MILLIS);
                assertEquals(-1, held.getInputStream().read(), "the open connection was released");
            }
        }
        assertRetired(poller);
        assertRefused(listenerPort);
    }

    @Test
    void cancellingCloseEndsAPendingAcquisitionWithoutResumingGuestCode() throws Exception {
        Context context = socketsOnly();
        ProtosObjectValue module;
        ProtosNioHostIoPoller poller;
        int port;
        Entry entry;
        try {
            context.eval(ProtosLanguage.ID, PROGRAM);
            ProtosEmbeddedProcess process = embedded(context);
            module = process.selectedModuleContext().orElseThrow();
            port = member(context, "openListener").execute().asInt();
            poller = process.networkCustodyForTesting().pollerForTesting();
            entry = new Entry(member(context, "acceptOne"));
            entry.awaitSuspended(() -> slot(module, "before") == 1);
        } catch (Throwable setup) {
            context.close(true);
            throw setup;
        }
        CompletableFuture.runAsync(() -> context.close(true)).get(10, TimeUnit.SECONDS);
        PolyglotException cancelled = assertInstanceOf(PolyglotException.class, entry.await());
        assertTrue(cancelled.isCancelled());
        assertRetired(poller);
        assertRefused(port);
        assertEquals(0, slot(module, "after"), "no guest code after the terminal boundary");
    }

    @Test
    void fatalProcessTerminationRetiresTheNetworkAndClosingAgainIsHarmless() throws Exception {
        try (Context context = socketsOnly()) {
            context.eval(ProtosLanguage.ID, PROGRAM);
            ProtosEmbeddedProcess process = embedded(context);
            int port = member(context, "openListener").execute().asInt();
            ProtosEmbeddedNetworkCustody custody = process.networkCustodyForTesting();
            ProtosNioHostIoPoller poller = custody.pollerForTesting();
            assertThrows(
                    PolyglotException.class,
                    () -> context.eval(ProtosLanguage.ID, "Error().signal()\n0"));
            assertFalse(process.isLive());
            awaitCondition(custody::isClosedForTesting, "Process termination closes Network");
            assertRetired(poller);
            assertRefused(port);
        }
    }

    @Test
    void closingOneContextLeavesAnotherContextsNetworkIntact() throws Exception {
        try (Context survivor = socketsOnly()) {
            survivor.eval(ProtosLanguage.ID, PROGRAM);
            int survivorPort = member(survivor, "openListener").execute().asInt();
            ProtosEmbeddedNetworkCustody survivorCustody = custody(survivor);
            ProtosNioHostIoPoller closedPoller;
            try (Context closed = socketsOnly()) {
                closed.eval(ProtosLanguage.ID, PROGRAM);
                member(closed, "openListener").execute();
                assertNotSame(survivorCustody, custody(closed), "no shared custody");
                closedPoller = custody(closed).pollerForTesting();
                assertNotSame(survivorCustody.pollerForTesting(), closedPoller, "no shared poller");
            }
            assertRetired(closedPoller);
            assertFalse(survivorCustody.isClosedForTesting());
            try (Socket client = new Socket(InetAddress.getLoopbackAddress(), survivorPort)) {
                client.getOutputStream().write(7);
                client.getOutputStream().flush();
                assertEquals(7, member(survivor, "acceptOne").execute().asInt());
            }
        }
    }
}
