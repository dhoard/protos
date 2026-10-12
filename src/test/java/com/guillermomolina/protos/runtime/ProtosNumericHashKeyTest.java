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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import org.junit.jupiter.api.Test;

final class ProtosNumericHashKeyTest {
    @Test
    void signed64BoundariesRemainExact() {
        for (long value : new long[] {
                Long.MIN_VALUE, -1L, 0L, 1L, Long.MAX_VALUE
        }) {
            assertEquals(
                    ProtosNumericHashKey.ofLong(value),
                    ProtosNumericHashKey.fromSemanticInteger(new ProtosIntegerValue(value)));
            assertEquals(
                    ProtosNumericHashKey.ofLong(value),
                    ProtosNumericHashKey.fromSemanticInteger(
                            com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerWithPrototype(BigInteger.valueOf(value), com.guillermomolina.protos.runtime.ProtosObjectValue.rootObject())));
        }
    }

    @Test
    void arbitraryPrecisionHashesDoNotTruncateOrCollideByLowWord() {
        BigInteger huge = BigInteger.ONE.shiftLeft(200).add(BigInteger.valueOf(7));

        ProtosNumericHashKey positive =
                ProtosNumericHashKey.fromSemanticInteger(com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerWithPrototype(huge, com.guillermomolina.protos.runtime.ProtosObjectValue.rootObject()));
        // A separately minted large Integer of the same value yields an equal key.
        ProtosNumericHashKey same =
                ProtosNumericHashKey.fromSemanticInteger(
                        com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerWithPrototype(BigInteger.ONE.shiftLeft(200).add(
                                BigInteger.valueOf(7)), com.guillermomolina.protos.runtime.ProtosObjectValue.rootObject()));
        ProtosNumericHashKey opposite =
                ProtosNumericHashKey.fromSemanticInteger(
                        com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerWithPrototype(huge.negate(), com.guillermomolina.protos.runtime.ProtosObjectValue.rootObject()));

        assertEquals(positive, same);
        assertEquals(positive.hashCode(), same.hashCode());
        assertNotEquals(positive, opposite);
        assertNotEquals(positive, ProtosNumericHashKey.ofLong(7));
        assertNotEquals(ProtosNumericHashKey.ofLong(7), positive);
        assertNotEquals(
                ProtosNumericHashKey.fromSemanticInteger(
                        com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerWithPrototype(BigInteger.ONE.shiftLeft(63), com.guillermomolina.protos.runtime.ProtosObjectValue.rootObject())),
                ProtosNumericHashKey.ofLong(Long.MIN_VALUE));
    }

    @Test
    void onlySemanticIntegersHaveIntegerKeys() {
        assertThrows(
                IllegalArgumentException.class,
                () -> ProtosNumericHashKey.fromSemanticInteger(new ProtosFloatValue(1.0)));
        assertThrows(
                IllegalArgumentException.class,
                () -> ProtosNumericHashKey.fromSemanticInteger(BigInteger.ONE.shiftLeft(80)));
    }

    @Test
    void largeKeysAreValueKeysIndependentOfTheMintingPrototype() {
        BigInteger value = BigInteger.ONE.shiftLeft(130).negate().add(BigInteger.valueOf(5L));
        ProtosObjectValue otherPrototype = new ProtosObjectValue(ProtosObjectValue.rootObject());
        ProtosNumericHashKey first = ProtosNumericHashKey.fromSemanticInteger(
                ProtosNumericValueSupport.integerWithPrototype(value, ProtosObjectValue.rootObject()));
        ProtosNumericHashKey rematerialized = ProtosNumericHashKey.fromSemanticInteger(
                ProtosNumericValueSupport.integerWithPrototype(value, otherPrototype));
        assertEquals(first, rematerialized);
        assertEquals(first.hashCode(), rematerialized.hashCode());
        assertEquals(value.hashCode(), first.hashCode());
    }

    @Test
    void equalLowBitsWithDifferentMagnitudesStayDistinct() {
        BigInteger low = BigInteger.valueOf(0x1234_5678_9abc_def0L);
        ProtosNumericHashKey small = ProtosNumericHashKey.ofLong(low.longValueExact());
        for (int shift : new int[] {64, 65, 128, 1000}) {
            BigInteger high = BigInteger.ONE.shiftLeft(shift);
            ProtosNumericHashKey above = ProtosNumericHashKey.fromSemanticInteger(
                    com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerWithPrototype(high.add(low), com.guillermomolina.protos.runtime.ProtosObjectValue.rootObject()));
            ProtosNumericHashKey below = ProtosNumericHashKey.fromSemanticInteger(
                    com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerWithPrototype(high.negate().add(low), com.guillermomolina.protos.runtime.ProtosObjectValue.rootObject()));
            assertNotEquals(small, above);
            assertNotEquals(above, small);
            assertNotEquals(small, below);
            assertNotEquals(above, below);
        }
    }

    @Test
    void keysRetainNoGuestObjectAndStaySeparateFromIdentityKeys() throws Exception {
        for (java.lang.reflect.Field field : ProtosNumericHashKey.class.getDeclaredFields()) {
            if (!java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                assertTrue(java.lang.reflect.Modifier.isFinal(field.getModifiers()), field.getName());
                assertFalse(ProtosObjectValue.class.isAssignableFrom(field.getType()),
                        field.getName());
                assertTrue(field.getType() == long.class || field.getType() == BigInteger.class,
                        field.getName());
            }
        }
        Object large = com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerWithPrototype(BigInteger.ONE.shiftLeft(90), com.guillermomolina.protos.runtime.ProtosObjectValue.rootObject());
        ProtosNumericHashKey byValue = ProtosNumericHashKey.fromSemanticInteger(large);
        ProtosNumericHashKey byIdentity = ProtosNumericHashKey.fromIdentity(large);
        assertNotEquals(byValue, byIdentity);
        assertEquals(ProtosNumericHashKey.ofLong(ProtosIdentity.identityHash(large)), byIdentity);
        ProtosObjectValue mutable = new ProtosObjectValue(ProtosObjectValue.rootObject());
        ProtosNumericHashKey identityKey = ProtosNumericHashKey.fromIdentity(mutable);
        mutable.createLocalSlot("changed", ProtosNullValue.INSTANCE);
        assertEquals(identityKey, ProtosNumericHashKey.fromIdentity(mutable));
    }
}
