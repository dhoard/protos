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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.guillermomolina.protos.execution.ProtosCoreBootstrap;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class ProtosRepresentedValueLookupTest {
    // Deliberately Java-side: exact lookup-home identity is internal lookup
    // machinery, not merely the language-visible selected value.
    @Test
    void ordinaryObjectLookupRemainsOrdinaryObjectLookup() throws IOException {
        ProtosPrelude prelude = corePrelude();
        ProtosObjectValue parent =
                new ProtosObjectValue(ProtosObjectValue.rootObject());
        ProtosObjectValue child = new ProtosObjectValue(parent);
        Object marker = new ProtosObjectValue(ProtosObjectValue.rootObject());
        parent.createLocalSlot("marker", marker);

        ProtosSlotLookupResult selected =
                ProtosValueLookup.lookup(child, "marker", prelude).orElseThrow();

        assertSame(marker, selected.value());
        assertSame(parent, selected.home());
    }

    // Deliberately Java-side: specialized host representations must map to the
    // exact physical source-backed family prototype objects.
    @Test
    void numericRepresentationsUseTheirNormativePrototypeParents()
            throws IOException {
        ProtosPrelude prelude = corePrelude();
        Object integerCall = prelude.integerPrototype().readLocalSlot("call").orElseThrow();
        Object floatCall = prelude.floatPrototype().readLocalSlot("call").orElseThrow();

        ProtosSlotLookupResult integerSelected =
                ProtosValueLookup.lookup(
                                new ProtosIntegerValue(42),
                                "call",
                                prelude)
                        .orElseThrow();
        assertSame(
                prelude.smallIntegerPrototype(),
                new ProtosIntegerValue(42).representedDelegationParent(prelude));
        assertSame(integerCall, integerSelected.value());
        // The concrete family inherits the factory; its canonical owner stays Integer.
        assertSame(prelude.integerPrototype(), integerSelected.home());
        assertFalse(prelude.smallIntegerPrototype().hasLocalSlot("call"));

        Object large =
                ProtosTestIntegers.integer(BigInteger.TWO.pow(64), prelude);
        assertSame(
                prelude.bigIntegerPrototype(),
                ProtosValueLookup.delegationParent(large, prelude).orElseThrow());
        ProtosSlotLookupResult largeSelected =
                ProtosValueLookup.lookup(large, "call", prelude).orElseThrow();
        assertSame(integerCall, largeSelected.value());
        assertSame(prelude.integerPrototype(), largeSelected.home());
        assertFalse(prelude.bigIntegerPrototype().hasLocalSlot("call"));

        ProtosSlotLookupResult floatSelected =
                ProtosValueLookup.lookup(
                                new ProtosFloatValue(2.5d),
                                "call",
                                prelude)
                        .orElseThrow();
        assertSame(floatCall, floatSelected.value());
        assertSame(prelude.floatPrototype(), floatSelected.home());

    }

    // Deliberately Java-side: this fixes exact root lookup-home identity and the
    // physical absence of a standard Boolean prelude prototype.
    @Test
    void canonicalBooleanAndNullBridgeDirectlyToObject() throws IOException {
        ProtosPrelude prelude = corePrelude();
        Object inheritedCall =
                ProtosObjectValue.rootObject()
                        .readLocalSlot("call")
                        .orElseThrow();

        for (Object value :
                List.of(
                        ProtosBooleanValue.TRUE,
                        ProtosBooleanValue.FALSE,
                        ProtosNullValue.INSTANCE)) {
            ProtosSlotLookupResult selected =
                    ProtosValueLookup.lookup(value, "call", prelude).orElseThrow();
            assertSame(inheritedCall, selected.value());
            assertSame(ProtosObjectValue.rootObject(), selected.home());
        }

        assertFalse(prelude.bindings().hasLocalSlot("Boolean"));
    }

    // Deliberately Java-side: this verifies the implementation extension point
    // itself can participate in lookup without central dispatcher cases.
    @Test
    void representedLookupExtensionDoesNotRequireCentralDispatcherCases() {
        ProtosObjectValue parent =
                new ProtosObjectValue(ProtosObjectValue.rootObject());
        Object marker = new ProtosObjectValue(ProtosObjectValue.rootObject());
        parent.createLocalSlot("marker", marker);
        TestRepresentedValue value = new TestRepresentedValue(parent);

        ProtosSlotLookupResult selected =
                ProtosValueLookup.lookup(value, "marker", null).orElseThrow();

        assertSame(marker, selected.value());
        assertSame(parent, selected.home());
    }

    // Deliberately Java-side (TEST009-AC): the generic loop's own-slot hit,
    // miss and unsupported-representation host failure are internal lookup
    // contracts that the compiler-shape repair must leave unchanged.
    @Test
    void genericLookupOwnSlotHitMissAndUnsupportedRepresentation() {
        ProtosObjectValue receiver =
                new ProtosObjectValue(ProtosObjectValue.rootObject());
        Object marker = new Object();
        receiver.createLocalSlot("marker", marker);

        ProtosSlotLookupResult selected =
                ProtosValueLookup.lookup(receiver, "marker", null).orElseThrow();

        assertSame(marker, selected.value());
        assertSame(receiver, selected.home());
        assertTrue(ProtosValueLookup.lookup(receiver, "absent", null).isEmpty());

        Object unsupported = new Object();
        UnsupportedOperationException failure = assertThrows(
                UnsupportedOperationException.class,
                () -> ProtosValueLookup.lookup(unsupported, "marker", null));
        assertEquals(
                "Standard delegation parent is not implemented for runtime value representation "
                        + Object.class.getName(),
                failure.getMessage());
    }

    private static ProtosPrelude corePrelude() throws IOException {
        return new ProtosCoreBootstrap()
                .bootstrap(Path.of("protos", "lib", "core"));
    }

    private record TestRepresentedValue(Object parent)
            implements ProtosRepresentedValue {
        @Override
        public Object representedDelegationParent(ProtosPrelude prelude) {
            return parent;
        }
    }
}
