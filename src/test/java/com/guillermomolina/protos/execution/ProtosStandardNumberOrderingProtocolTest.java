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

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.guillermomolina.protos.runtime.ProtosActivation;
import com.guillermomolina.protos.runtime.ProtosBooleanValue;
import com.guillermomolina.protos.runtime.ProtosClosureValue;
import com.guillermomolina.protos.runtime.ProtosFloatValue;
import com.guillermomolina.protos.runtime.ProtosIntegerValue;
import com.guillermomolina.protos.runtime.ProtosObjectValue;
import com.guillermomolina.protos.runtime.ProtosPrelude;
import com.guillermomolina.protos.runtime.ProtosSignalException;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class ProtosStandardNumberOrderingProtocolTest {
    // Deliberately Java-side: this checks the physical representation boundary
    // used to install the standard Number ordering protocol.
    @Test
    void numberPrototypeOwnsOrderingAsOrdinaryClosureValuedProtocol() throws IOException {
        ProtosPrelude prelude = corePrelude();
        for (String selector : List.of("<", "<=", ">", ">=")) {
            Object behavior = prelude.numberPrototype().readLocalSlot(selector).orElseThrow();
            assertSame(ProtosClosureValue.class, behavior.getClass());
        }
    }

    // I091: the four relations are exact across Integer carriers and Float; NaN is unordered.
    @Test
    void relationsAreExactAcrossIntegerCarriersAndFloat() throws IOException {
        ProtosPrelude prelude = corePrelude();
        ProtosActivation a = prelude.newModuleActivation();
        Object twoTo53Plus1 = integer(BigInteger.ONE.shiftLeft(53).add(BigInteger.ONE), prelude);
        Object twoTo64 = integer(BigInteger.ONE.shiftLeft(64), prelude);
        Object minusTwoTo64 = integer(BigInteger.ONE.shiftLeft(64).negate(), prelude);
        Object twoTo1000Plus1 = integer(BigInteger.ONE.shiftLeft(1000).add(BigInteger.ONE), prelude);

        // small/small
        assertOrder(-1, new ProtosIntegerValue(1), new ProtosIntegerValue(2), a);
        assertOrder(0, new ProtosIntegerValue(Long.MAX_VALUE), new ProtosIntegerValue(Long.MAX_VALUE), a);
        // small/large across the signed-64 boundary
        assertOrder(-1, new ProtosIntegerValue(Long.MAX_VALUE), twoTo64, a);
        assertOrder(1, new ProtosIntegerValue(Long.MIN_VALUE), minusTwoTo64, a);
        assertOrder(-1, minusTwoTo64, twoTo64, a);
        // Integer/Float, never through a rounded double of the Integer
        assertOrder(1, twoTo53Plus1, new ProtosFloatValue(0x1p53), a);
        assertOrder(-1, new ProtosIntegerValue(Long.MAX_VALUE), new ProtosFloatValue(0x1p63), a);
        assertOrder(0, integer(BigInteger.ONE.shiftLeft(63), prelude), new ProtosFloatValue(0x1p63), a);
        assertOrder(1, twoTo1000Plus1, new ProtosFloatValue(0x1p1000), a);
        assertOrder(-1, twoTo1000Plus1, new ProtosFloatValue(Double.POSITIVE_INFINITY), a);
        assertOrder(1, minusTwoTo64, new ProtosFloatValue(Double.NEGATIVE_INFINITY), a);
    }

    @Test
    void nanSatisfiesNoRelation() throws IOException {
        ProtosPrelude prelude = corePrelude();
        ProtosActivation a = prelude.newModuleActivation();
        ProtosFloatValue nan = new ProtosFloatValue(Double.NaN);
        for (Object other :
                List.of(nan, new ProtosIntegerValue(0), integer(BigInteger.ONE.shiftLeft(64), prelude))) {
            for (String selector : List.of("<", "<=", ">", ">=")) {
                assertSame(ProtosBooleanValue.FALSE, ProtosInvocation.invokeMessage(nan, selector, List.of(other), a));
                assertSame(ProtosBooleanValue.FALSE, ProtosInvocation.invokeMessage(other, selector, List.of(nan), a));
            }
        }
    }

    @Test
    void nonNumberOperandsSignal() throws IOException {
        ProtosPrelude prelude = corePrelude();
        ProtosActivation a = prelude.newModuleActivation();
        ProtosObjectValue impostor = new ProtosObjectValue(prelude.numberPrototype());
        assertThrows(
                ProtosSignalException.class,
                () -> ProtosInvocation.invokeMessage(new ProtosIntegerValue(1), "<", List.of(BigInteger.TEN), a));
        assertThrows(
                ProtosSignalException.class,
                () -> ProtosInvocation.invokeMessage(impostor, "<", List.of(new ProtosIntegerValue(1)), a));
    }

    @Test
    void canonicalAdmissionIsBoundToTheOwningPreludeNumberPrototype() throws IOException {
        ProtosPrelude prelude = corePrelude();
        ProtosPrelude other = corePrelude();
        for (String selector : List.of("<", "<=", ">", ">=")) {
            ProtosClosureValue selected =
                    (ProtosClosureValue) prelude.numberPrototype().readLocalSlot(selector).orElseThrow();
            assertNotNull(
                    ProtosStandardNumberOrderingProtocol.canonicalRelationForSelection(
                            selected, prelude.numberPrototype(), selector, prelude));
            assertNull(
                    ProtosStandardNumberOrderingProtocol.canonicalRelationForSelection(
                            selected, prelude.numberPrototype(), selector, other));
            assertNull(
                    ProtosStandardNumberOrderingProtocol.canonicalRelationForSelection(
                            selected, other.numberPrototype(), selector, other));
        }
    }

    private static Object integer(BigInteger value, ProtosPrelude prelude) {
        return com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerFromHost(value, prelude);
    }

    /** Asserts all four relations and their mirror for an expected sign of left compared to right. */
    private static void assertOrder(int sign, Object left, Object right, ProtosActivation a) {
        assertRelations(sign, left, right, a);
        assertRelations(-sign, right, left, a);
    }

    private static void assertRelations(int sign, Object left, Object right, ProtosActivation a) {
        assertRelation(sign < 0, left, "<", right, a);
        assertRelation(sign <= 0, left, "<=", right, a);
        assertRelation(sign > 0, left, ">", right, a);
        assertRelation(sign >= 0, left, ">=", right, a);
    }

    private static void assertRelation(
            boolean expected, Object left, String selector, Object right, ProtosActivation a) {
        assertSame(
                expected ? ProtosBooleanValue.TRUE : ProtosBooleanValue.FALSE,
                ProtosInvocation.invokeMessage(left, selector, List.of(right), a),
                left + " " + selector + " " + right);
    }

    private static ProtosPrelude corePrelude() throws IOException {
        return new ProtosCoreBootstrap().bootstrap(Path.of("protos", "lib", "core"));
    }
}
