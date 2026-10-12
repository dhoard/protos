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
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.guillermomolina.protos.runtime.ProtosActivation;
import com.guillermomolina.protos.runtime.ProtosBytesValue;
import com.guillermomolina.protos.runtime.ProtosClosureValue;
import com.guillermomolina.protos.runtime.ProtosFloatValue;
import com.guillermomolina.protos.runtime.ProtosIntegerValue;
import com.guillermomolina.protos.runtime.ProtosObjectValue;
import com.guillermomolina.protos.runtime.ProtosPrelude;
import com.guillermomolina.protos.runtime.ProtosSignalException;
import com.guillermomolina.protos.runtime.ProtosStringValue;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class ProtosStandardBytesProtocolTest {
    // Deliberately Java-side: the construction-only Bytes prototype is omitted
    // from public prelude bindings. Its exact call/receiver contract remains a
    // runtime/bootstrap boundary.
    @Test
    void hiddenFactoryContractAndRepresentedConstructionRemainJavaSide()
            throws IOException {
        Fixture fixture = fixture();

        ProtosBytesValue a = fixture.create();
        ProtosBytesValue b = fixture.create();
        assertNotSame(a, b);
        assertTrue(a.isOpen());
        assertSame(fixture.factory, a.parent().orElseThrow());
        assertEquals(0, a.indexedSize());

        ProtosObjectValue derived = new ProtosObjectValue(fixture.factory);
        ProtosBytesValue child =
                assertInstanceOf(
                        ProtosBytesValue.class,
                        ProtosInvocation.invoke(derived, List.of(), fixture.activation));
        assertTrue(child.isOpen());
        assertSame(derived, child.parent().orElseThrow());

        assertDirectError(
                fixture,
                () ->
                        ProtosInvocation.invoke(
                                fixture.factory, List.of(integer(1)), fixture.activation));
    }

    // Source conformance owns the observable invalid-operation behavior. Java
    // recovery is retained only to inspect the SAME represented Bytes afterward
    // and prove that no partial mutation occurred.
    @Test
    void invalidOpenMutationFailuresLeaveSameBytesUntouched() throws IOException {
        Fixture fixture = fixture();
        ProtosBytesValue bytes = fixture.create();
        ProtosIntegerValue ten = integer(10);
        ProtosIntegerValue twenty = integer(20);
        fixture.add(bytes, ten);
        fixture.add(bytes, twenty);

        assertDirectError(fixture, () -> fixture.send(bytes, "atPut", integer(2), integer(9)));
        assertDirectError(fixture, () -> fixture.send(bytes, "atPut", integer(0), integer(-1)));
        assertDirectError(fixture, () -> fixture.send(bytes, "atPut", integer(0), integer(256)));
        assertDirectError(
                fixture,
                () -> fixture.send(bytes, "atPut", integer(0), new ProtosFloatValue(7.0)));
        assertDirectError(
                fixture,
                () -> fixture.send(bytes, "atPut", integer(0), new ProtosStringValue("7")));
        assertDirectError(fixture, () -> fixture.send(bytes, "add", integer(-1)));
        assertDirectError(fixture, () -> fixture.send(bytes, "add", integer(256)));
        assertDirectError(
                fixture, () -> fixture.send(bytes, "add", new ProtosFloatValue(1.0)));
        assertDirectError(fixture, () -> fixture.send(bytes, "removeAt", integer(-1)));
        assertDirectError(fixture, () -> fixture.send(bytes, "removeAt", integer(2)));

        assertEquals(2, bytes.indexedSize());
        assertSame(ten, fixture.send(bytes, "at", integer(0)));
        assertSame(twenty, fixture.send(bytes, "at", integer(1)));
    }

    // Preserve the already-closed I012 lifecycle contract without pretending
    // that ordinary source-level close()/freeze() dispatch on represented Bytes
    // currently reaches the same represented-value lifecycle path.
    @Test
    void representedLifecycleContractRemainsJavaSide() throws IOException {
        Fixture fixture = fixture();

        ProtosBytesValue closed = fixture.create();
        fixture.add(closed, integer(1));
        closed.close();
        ProtosIntegerValue two = integer(2);
        assertSame(two, fixture.send(closed, "atPut", integer(0), two));
        assertDirectError(fixture, () -> fixture.send(closed, "add", integer(3)));
        assertDirectError(fixture, () -> fixture.send(closed, "removeAt", integer(0)));
        assertEquals("1", intValue(fixture.send(closed, "size")));
        assertSame(two, fixture.send(closed, "at", integer(0)));

        ProtosBytesValue frozen = fixture.create();
        ProtosIntegerValue four = integer(4);
        fixture.add(frozen, four);
        frozen.freeze();
        assertDirectError(fixture, () -> fixture.send(frozen, "atPut", integer(0), integer(5)));
        assertDirectError(
                fixture,
                () ->
                        fixture.send(
                                frozen,
                                "atPut",
                                new ProtosStringValue("bad-index"),
                                new ProtosStringValue("bad-value")));
        assertDirectError(fixture, () -> fixture.send(frozen, "add", integer(5)));
        assertDirectError(fixture, () -> fixture.send(frozen, "removeAt", integer(0)));
        assertSame(four, fixture.send(frozen, "at", integer(0)));
        assertEquals("1", intValue(fixture.send(frozen, "size")));
    }

    // Hidden-factory Closure provenance and the exact Error category for
    // receiver-domain / callback failures remain Java-side supplements.
    @Test
    void hiddenMethodsAndReceiverErrorsRemainDirectStandardError() throws IOException {
        Fixture fixture = fixture();

        ProtosBytesValue bytes = fixture.create();
        fixture.add(bytes, integer(9));

        ProtosObjectValue delegated = new ProtosObjectValue(bytes);
        assertDirectError(fixture, () -> fixture.send(delegated, "size"));
        assertDirectError(fixture, () -> fixture.send(delegated, "at", integer(0)));

        ProtosObjectValue copied = new ProtosObjectValue(ProtosObjectValue.rootObject());
        copied.createLocalSlot("size", fixture.factory.readLocalSlot("size").orElseThrow());
        copied.createLocalSlot("add", fixture.factory.readLocalSlot("add").orElseThrow());
        assertDirectError(fixture, () -> fixture.send(copied, "size"));
        assertDirectError(fixture, () -> fixture.send(copied, "add", integer(1)));

        assertDirectError(fixture, () -> fixture.send(bytes, "each", integer(1)));
    }

    @Test
    void indicesAndOctetsAreRangeCheckedBeforeAnyNarrowing() throws IOException {
        Fixture fixture = fixture();
        ProtosBytesValue bytes = fixture.create();
        ProtosIntegerValue zero = integer(0);
        ProtosIntegerValue max = integer(255);
        assertSame(zero, fixture.add(bytes, zero));
        assertSame(max, fixture.add(bytes, max));
        Object twoPow63 = com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerFromHost(BigInteger.ONE.shiftLeft(63), fixture.prelude);
        Object largeNegative =
                com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerFromHost(BigInteger.ONE.shiftLeft(64).negate(), fixture.prelude);

        for (Object index : List.of(
                integer(Integer.MAX_VALUE), integer(Integer.MIN_VALUE),
                integer(Long.MAX_VALUE), integer(-1), integer(2),
                twoPow63, largeNegative, new ProtosFloatValue(0.0))) {
            assertDirectError(fixture, () -> fixture.send(bytes, "at", index));
            assertDirectError(fixture, () -> fixture.send(bytes, "atPut", index, integer(1)));
            assertDirectError(fixture, () -> fixture.send(bytes, "removeAt", index));
        }
        for (Object octet : List.of(
                integer(-1), integer(256), integer(Long.MIN_VALUE), twoPow63, largeNegative,
                new ProtosFloatValue(1.0), new ProtosFloatValue(0.0))) {
            assertDirectError(fixture, () -> fixture.send(bytes, "add", octet));
            assertDirectError(fixture, () -> fixture.send(bytes, "atPut", integer(0), octet));
        }

        // No failed operation changed the content, which still holds the exact objects given.
        assertEquals(2, bytes.indexedSize());
        assertSame(zero, fixture.send(bytes, "at", integer(0)));
        assertSame(max, fixture.send(bytes, "at", integer(1)));
        assertEquals(List.of((byte) 0, (byte) 0xff),
                List.of(bytes.octetSnapshot()[0], bytes.octetSnapshot()[1]));

        ProtosIntegerValue one = integer(1);
        assertSame(one, fixture.send(bytes, "atPut", integer(1), one));
        assertSame(one, fixture.send(bytes, "at", integer(1)));
    }

    @Test
    void eachObservesTheExactStoredOctetsThroughTheCallback() throws IOException {
        Fixture fixture = fixture();
        ProtosBytesValue bytes = fixture.create();
        List<Object> stored = List.of(integer(0), integer(128), integer(255));
        for (Object octet : stored) {
            fixture.add(bytes, octet);
        }
        java.util.ArrayList<Object> observed = new java.util.ArrayList<>();
        Object result = fixture.send(bytes, "each", ProtosClosureValue.nativeClosure(
                (activation, supplied) -> {
                    observed.add(supplied.get(0));
                    return supplied.get(0);
                }));
        assertSame(bytes, result);
        assertEquals(stored.size(), observed.size());
        for (int index = 0; index < stored.size(); index++) {
            assertSame(stored.get(index), observed.get(index));
        }
    }

    private static ProtosIntegerValue integer(long value) {
        return new ProtosIntegerValue(value);
    }

    private static String intValue(Object value) {
        return com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerDecimalText(value);
    }

    private static Fixture fixture() throws IOException {
        ProtosPrelude prelude =
                new ProtosCoreBootstrap().bootstrap(Path.of("protos", "lib", "core"));
        ProtosActivation activation = prelude.newModuleActivation();
        ProtosObjectValue factory =
                new ProtosObjectValue(ProtosObjectValue.rootObject());
        ProtosStandardBytesProtocol.install(factory);
        return new Fixture(prelude, activation, factory);
    }

    private static void assertDirectError(
            Fixture fixture, org.junit.jupiter.api.function.Executable executable) {
        ProtosSignalException signal =
                assertThrows(ProtosSignalException.class, executable);
        assertSame(
                fixture.prelude.errorPrototype(),
                signal.error().parent().orElseThrow());
    }

    private record Fixture(
            ProtosPrelude prelude,
            ProtosActivation activation,
            ProtosObjectValue factory) {
        ProtosBytesValue create() {
            return (ProtosBytesValue)
                    ProtosInvocation.invoke(factory, List.of(), activation);
        }

        Object add(ProtosBytesValue bytes, Object value) {
            return send(bytes, "add", value);
        }

        Object send(Object receiver, String selector, Object... arguments) {
            return ProtosInvocation.invokeMessage(
                    receiver, selector, List.of(arguments), activation);
        }
    }
}
