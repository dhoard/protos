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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.guillermomolina.protos.runtime.ProtosActivation;
import com.guillermomolina.protos.runtime.ProtosArrayValue;
import com.guillermomolina.protos.runtime.ProtosFloatValue;
import com.guillermomolina.protos.runtime.ProtosIntegerValue;
import com.guillermomolina.protos.runtime.ProtosNumericValueSupport;
import com.guillermomolina.protos.runtime.ProtosPrelude;
import com.guillermomolina.protos.runtime.ProtosSignalException;
import com.guillermomolina.protos.runtime.ProtosStringValue;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class ProtosStandardArrayFactoryTest {
    // Deliberately Java-side: this verifies the represented-value materialization
    // and mutation state, while source-level factory semantics live in .protos.
    @Test
    void factoryMaterializesOpenRepresentedArrayWithInvocationReceiverParent()
            throws IOException {
        ProtosPrelude prelude = corePrelude();
        ProtosActivation activation = prelude.newModuleActivation();

        ProtosArrayValue result =
                (ProtosArrayValue)
                        ProtosTestExecutionSupport.evaluate(
                                """
                                MyArray: Array {
                                    label: 9
                                }
                                MyArray(10, 20)
                                """,
                                activation);

        Object myArray = activation.context().readLocalSlot("MyArray").orElseThrow();
        assertFalse(result.isClosed());
        assertFalse(result.isFrozen());
        assertSame(myArray, result.parent().orElseThrow());
        assertSame(
                prelude.arrayPrototype(),
                prelude.bindings().readLocalSlot("Array").orElseThrow());
    }

    // I091: indices are exact signed-64 Integers within the array; nothing is truncated.
    @Test
    void indexedAccessAdmitsOnlyExactInRangeIntegerIndices() throws IOException {
        ProtosPrelude prelude = corePrelude();
        ProtosActivation a = prelude.newModuleActivation();
        BigInteger huge = BigInteger.ONE.shiftLeft(1000).add(BigInteger.ONE);
        ProtosArrayValue array =
                prelude.newArray(
                        List.of(
                                new ProtosStringValue("first"),
                                com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerFromHost(huge, prelude),
                                new ProtosStringValue("last")));

        assertEquals("first", string(ProtosInvocation.invokeMessage(array, "at", List.of(index(0)), a)));
        assertEquals("last", string(ProtosInvocation.invokeMessage(array, "at", List.of(index(2)), a)));
        assertEquals(String.valueOf(String.valueOf(huge)), com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerDecimalText(ProtosInvocation.invokeMessage(array, "at", List.of(index(1)), a)));

        Object twoTo64 = com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerFromHost(BigInteger.ONE.shiftLeft(64), prelude);
        // 2^32 + 1 truncates to int 1, and 2^64 + 1 truncates to long 1: neither may alias index 1.
        Object[] invalid = {
            index(-1),
            index(3),
            index(Integer.MAX_VALUE),
            new ProtosIntegerValue((1L << 32) + 1),
            twoTo64,
            com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerFromHost(BigInteger.ONE.shiftLeft(64).add(BigInteger.ONE), prelude),
            new ProtosFloatValue(1.0d),
            BigInteger.ONE,
            Long.valueOf(1L),
        };
        for (Object bad : invalid) {
            assertThrows(
                    ProtosSignalException.class,
                    () -> ProtosInvocation.invokeMessage(array, "at", List.of(bad), a),
                    String.valueOf(bad));
            assertThrows(
                    ProtosSignalException.class,
                    () ->
                            ProtosInvocation.invokeMessage(
                                    array, "atPut", List.of(bad, new ProtosStringValue("x")), a),
                    String.valueOf(bad));
        }
        assertEquals("first", string(array.indexedAt(0)));
        assertEquals(String.valueOf(String.valueOf(huge)), com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerDecimalText(array.indexedAt(1)));
        assertEquals("last", string(array.indexedAt(2)));
        assertEquals(3, array.indexedSize());
    }

    @Test
    void largeIntegerElementsRoundTripAndSizeIsASigned64Integer() throws IOException {
        ProtosPrelude prelude = corePrelude();
        ProtosActivation a = prelude.newModuleActivation();
        BigInteger negative = BigInteger.ONE.shiftLeft(64).negate();
        ProtosArrayValue array = prelude.newArray(List.of(new ProtosIntegerValue(0)));

        ProtosInvocation.invokeMessage(
                array, "atPut", List.of(index(0), com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerFromHost(negative, prelude)), a);

        assertEquals(String.valueOf(String.valueOf(negative)), com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerDecimalText(ProtosInvocation.invokeMessage(array, "at", List.of(index(0)), a)));
        Object size = ProtosInvocation.invokeMessage(array, "size", List.of(), a);
        assertTrue(ProtosNumericValueSupport.isIntegerInLongRange(size));
        assertEquals(String.valueOf("1"), com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerDecimalText(size));
    }

    @Test
    void frozenArrayRejectsMutationWithoutChange() throws IOException {
        ProtosPrelude prelude = corePrelude();
        ProtosActivation a = prelude.newModuleActivation();
        ProtosArrayValue array = prelude.newArray(List.of(new ProtosStringValue("kept")));
        array.freeze();

        assertThrows(
                ProtosSignalException.class,
                () ->
                        ProtosInvocation.invokeMessage(
                                array, "atPut", List.of(index(0), new ProtosStringValue("lost")), a));
        assertEquals("kept", string(array.indexedAt(0)));
    }

    private static Object index(long value) {
        return new ProtosIntegerValue(value);
    }

    private static String string(Object value) {
        return ((ProtosStringValue) value).value();
    }

    private static ProtosPrelude corePrelude() throws IOException {
        return new ProtosCoreBootstrap().bootstrap(Path.of("protos", "lib", "core"));
    }
}
