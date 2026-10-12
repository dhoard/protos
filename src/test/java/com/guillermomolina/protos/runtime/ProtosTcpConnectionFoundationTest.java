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

package com.guillermomolina.protos.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.guillermomolina.protos.execution.ProtosCoreBootstrap;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.IdentityHashMap;
import org.junit.jupiter.api.Test;

final class ProtosTcpConnectionFoundationTest {
    private static final Path CORE = Path.of("protos", "lib", "core");

    @Test
    void hiddenProtocolPrototypeIsCanonicalFrozenAuthorityFreeCoreState() throws Exception {
        ProtosPrelude prelude = core();
        ProtosObjectValue prototype = prelude.tcpConnectionPrototypeForRuntime();

        assertSame(ProtosObjectValue.rootObject(), prototype.parent().orElseThrow());
        assertTrue(prototype.isFrozen());
        assertEquals(
                java.util.Set.of("read", "write", "close", "shutdownRead", "shutdownWrite", "localEndpoint", "remoteEndpoint"),
                prototype.localSlotsSnapshot().keySet());
        assertTrue(prelude.bindings().readLocalSlot("TcpConnection").isEmpty());
        assertTrue(prelude.bindings().readLocalSlot("_coreTcpConnectionPrototype").isEmpty());
    }

    @Test
    void connectionIsOrdinaryOpenObjectWithOpaqueRuntimeState() throws Exception {
        ProtosPrelude prelude = core();
        Object resourceState = new Object();
        ProtosTcpConnectionValue connection =
                new ProtosTcpConnectionValue(prelude, resourceState);

        assertSame(prelude.tcpConnectionPrototypeForRuntime(), connection.parent().orElseThrow());
        assertTrue(connection.isOpen());
        assertTrue(connection.localSlotsSnapshot().isEmpty());
        assertSame(resourceState, connection.resourceStateForRuntime());

        connection.createLocalSlot("label", new ProtosIntegerValue(7));
        assertEquals(
                7L,
                assertInstanceOf(
                        ProtosIntegerValue.class,
                        connection.readLocalSlot("label").orElseThrow())
                        .longValue());
        assertNotSame(connection, new ProtosTcpConnectionValue(prelude, resourceState));
    }

    @Test
    void actorAndPRejectConnectionAndAuthorityBearingDescendants() throws Exception {
        ProtosPrelude prelude = core();
        ProtosActivation activation = prelude.newModuleActivation();
        ProtosTcpConnectionValue connection =
                new ProtosTcpConnectionValue(prelude, new Object());
        ProtosObjectValue descendant = new ProtosObjectValue(connection);

        assertNonTransferable(
                prelude,
                () -> ProtosActorValueTransfer.snapshotValue(connection, activation));
        assertNonTransferable(
                prelude,
                () -> ProtosActorValueTransfer.snapshotValue(descendant, activation));

        Method copy = pCopyMethod();
        assertNonParallel(copy, connection, activation);
        assertNonParallel(copy, descendant, activation);
    }

    @Test
    void authorityFreeProtocolDescendantTransfersWithCanonicalParent() throws Exception {
        ProtosPrelude prelude = core();
        ProtosActivation activation = prelude.newModuleActivation();
        ProtosObjectValue prototype = prelude.tcpConnectionPrototypeForRuntime();
        ProtosObjectValue ordinary = new ProtosObjectValue(prototype);
        ordinary.createLocalSlot("payload", new ProtosIntegerValue(11));
        ordinary.freeze();

        ProtosObjectValue actorCopy =
                assertInstanceOf(
                        ProtosObjectValue.class,
                        ProtosActorValueTransfer.snapshotValue(ordinary, activation));
        assertNotSame(ordinary, actorCopy);
        assertSame(prototype, actorCopy.parent().orElseThrow());
        assertTrue(actorCopy.isFrozen());

        ProtosObjectValue pCopy =
                assertInstanceOf(
                        ProtosObjectValue.class,
                        pCopyMethod()
                                .invoke(
                                        null,
                                        ordinary,
                                        activation,
                                        new IdentityHashMap<Object, Object>()));
        assertNotSame(ordinary, pCopy);
        assertSame(prototype, pCopy.parent().orElseThrow());
        assertTrue(pCopy.isFrozen());
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
                Class.forName(
                        "com.guillermomolina.protos.execution.ProtosParallelRuntime$Transfer");
        Method copy =
                transfer.getDeclaredMethod(
                        "copy", Object.class, ProtosActivation.class, IdentityHashMap.class);
        copy.setAccessible(true);
        return copy;
    }

    private static ProtosPrelude core() throws Exception {
        return new ProtosCoreBootstrap().bootstrap(CORE);
    }

    @FunctionalInterface
    private interface ThrowingAction {
        Object run();
    }
}
