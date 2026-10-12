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

import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.UnsupportedMessageException;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ProtosIntegralInteropTest {
    private final InteropLibrary interop = InteropLibrary.getUncached();

    @Test
    void arbitraryIntegerExportsExactIntegralWidthsAndBigInteger() throws Exception {
        ProtosIntegerValue byteMax =
                new ProtosIntegerValue(Byte.MAX_VALUE);
        ProtosIntegerValue byteOverflow =
                new ProtosIntegerValue(Byte.MAX_VALUE + 1L);

        assertTrue(interop.isNumber(byteMax));
        assertTrue(interop.fitsInByte(byteMax));
        assertEquals(Byte.MAX_VALUE, interop.asByte(byteMax));

        assertFalse(interop.fitsInByte(byteOverflow));
        assertTrue(interop.fitsInShort(byteOverflow));
        assertThrows(
                UnsupportedMessageException.class,
                () -> interop.asByte(byteOverflow));

        BigInteger huge = BigInteger.ONE.shiftLeft(200).add(BigInteger.ONE);
        Object hugeValue = com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerWithPrototype(huge, com.guillermomolina.protos.runtime.ProtosObjectValue.rootObject());
        assertTrue(interop.fitsInBigInteger(hugeValue));
        assertEquals(huge, interop.asBigInteger(hugeValue));
        assertFalse(interop.fitsInLong(hugeValue));
        assertThrows(
                UnsupportedMessageException.class,
                () -> interop.asLong(hugeValue));
    }

    @Test
    void floatingWidthClaimsRequireExactMathematicalRoundTrip() throws Exception {
        Object floatExact =
                com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerWithPrototype(BigInteger.ONE.shiftLeft(24), com.guillermomolina.protos.runtime.ProtosObjectValue.rootObject());
        Object floatRounded =
                com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerWithPrototype(BigInteger.ONE.shiftLeft(24).add(BigInteger.ONE), com.guillermomolina.protos.runtime.ProtosObjectValue.rootObject());
        assertTrue(interop.fitsInFloat(floatExact));
        assertEquals((float) (1 << 24), interop.asFloat(floatExact));
        assertFalse(interop.fitsInFloat(floatRounded));
        assertThrows(
                UnsupportedMessageException.class,
                () -> interop.asFloat(floatRounded));

        BigInteger doubleBoundary = BigInteger.ONE.shiftLeft(53);
        Object doubleExact = com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerWithPrototype(doubleBoundary, com.guillermomolina.protos.runtime.ProtosObjectValue.rootObject());
        Object doubleRounded =
                com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerWithPrototype(doubleBoundary.add(BigInteger.ONE), com.guillermomolina.protos.runtime.ProtosObjectValue.rootObject());
        assertTrue(interop.fitsInDouble(doubleExact));
        assertEquals(Math.scalb(1.0, 53), interop.asDouble(doubleExact));
        assertFalse(interop.fitsInDouble(doubleRounded));
        assertThrows(
                UnsupportedMessageException.class,
                () -> interop.asDouble(doubleRounded));

        Object largeExact =
                com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerWithPrototype(BigInteger.ONE.shiftLeft(80), com.guillermomolina.protos.runtime.ProtosObjectValue.rootObject());
        Object largeRounded =
                com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerWithPrototype(BigInteger.ONE.shiftLeft(80).add(BigInteger.ONE), com.guillermomolina.protos.runtime.ProtosObjectValue.rootObject());
        assertTrue(interop.fitsInDouble(largeExact));
        assertFalse(interop.fitsInDouble(largeRounded));

        Object decimalTrap =
                com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerWithPrototype(new BigInteger("100000000000000000000000"), com.guillermomolina.protos.runtime.ProtosObjectValue.rootObject());
        assertFalse(interop.fitsInDouble(decimalTrap));
    }

    @Test
    void fixedInteropCarrierUsesSameExactProjectionWithoutGuestNumericSemantics()
            throws Exception {
        BigInteger uint64Max = BigInteger.ONE.shiftLeft(64).subtract(BigInteger.ONE);
        ProtosFixedIntegerInteropValue value = ProtosFixedIntegerInteropValue.ofUnsignedBits(-1L);

        assertTrue(interop.isNumber(value));
        assertTrue(interop.fitsInBigInteger(value));
        assertEquals(uint64Max, interop.asBigInteger(value));
        assertFalse(interop.fitsInLong(value));
        assertFalse(interop.fitsInDouble(value));
        assertThrows(
                UnsupportedMessageException.class,
                () -> interop.asLong(value));
        assertSame(ProtosFixedIntegerInteropValue.Kind.UINT64, value.kind());

        ProtosFixedIntegerInteropValue min =
                ProtosFixedIntegerInteropValue.ofLong(
                        ProtosFixedIntegerInteropValue.Kind.INT64, Long.MIN_VALUE);
        assertTrue(interop.fitsInLong(min));
        assertEquals(Long.MIN_VALUE, interop.asLong(min));

        ProtosFixedIntegerInteropValue exactAboveLong =
                ProtosFixedIntegerInteropValue.ofUnsignedBits(Long.MIN_VALUE);
        assertFalse(interop.fitsInLong(exactAboveLong));
        assertTrue(interop.fitsInDouble(exactAboveLong));
        assertEquals(Math.scalb(1.0, 63), interop.asDouble(exactAboveLong));
    }

    @Test
    void largeNegativeMagnitudesAreMeasuredExactly() throws Exception {
        BigInteger one = BigInteger.ONE;
        // -2^1023 is finite in binary64; -2^1024 is not, although its two's-complement bit
        // length is 1024.
        assertTrue(interop.fitsInDouble(large(one.shiftLeft(1023).negate())));
        assertFalse(interop.fitsInDouble(large(one.shiftLeft(1024).negate())));
        assertTrue(interop.fitsInFloat(large(one.shiftLeft(127).negate())));
        assertFalse(interop.fitsInFloat(large(one.shiftLeft(128).negate())));
        // 53 versus 54 significant bits, negative.
        BigInteger fits = one.shiftLeft(53).subtract(one).shiftLeft(20).negate();
        BigInteger tooWide = one.shiftLeft(53).add(one).shiftLeft(20).negate();
        assertTrue(interop.fitsInDouble(large(fits)));
        assertFalse(interop.fitsInDouble(large(tooWide)));
        assertEquals(fits.doubleValue(), interop.asDouble(large(fits)));
    }

    private static Object large(BigInteger value) {
        return com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerWithPrototype(value, com.guillermomolina.protos.runtime.ProtosObjectValue.rootObject());
    }

    @Test
    void primitiveFixedInteropConstructionMatchesExactFamilyRanges() throws Exception {
        for (ProtosFixedIntegerInteropValue.Kind family
                : ProtosFixedIntegerInteropValue.Kind.values()) {
            long minimum = minimum(family).longValueExact();
            assertTrue(family.contains(minimum), family.name());
            assertEquals(
                    minimum(family),
                    interop.asBigInteger(ProtosFixedIntegerInteropValue.ofLong(family, minimum)));
            if (maximum(family).bitLength() < Long.SIZE) {
                long maximum = maximum(family).longValueExact();
                assertTrue(family.contains(maximum), family.name());
                assertEquals(
                        maximum(family),
                        interop.asBigInteger(
                                ProtosFixedIntegerInteropValue.ofLong(family, maximum)));
                if (maximum != Long.MAX_VALUE) {
                    assertFalse(family.contains(maximum + 1L), family.name());
                    assertThrows(
                            IllegalArgumentException.class,
                            () -> ProtosFixedIntegerInteropValue.ofLong(family, maximum + 1L));
                }
            }
            if (minimum != Long.MIN_VALUE) {
                assertFalse(family.contains(minimum - 1L), family.name());
                assertThrows(
                        IllegalArgumentException.class,
                        () -> ProtosFixedIntegerInteropValue.ofLong(family, minimum - 1L));
            }
        }
        ProtosFixedIntegerInteropValue uint64Max =
                ProtosFixedIntegerInteropValue.ofUnsignedBits(-1L);
        assertEquals(
                BigInteger.ONE.shiftLeft(64).subtract(BigInteger.ONE),
                interop.asBigInteger(uint64Max));
        assertFalse(interop.fitsInLong(uint64Max));
        assertTrue(ProtosFixedIntegerInteropValue.Kind.UINT64.contains(Long.MAX_VALUE));
        assertFalse(ProtosFixedIntegerInteropValue.Kind.UINT64.contains(-1L));
    }

    @Test
    void allFixedInteropKindsRemainExactBigIntegerInteropNumbers() throws Exception {
        for (ProtosFixedIntegerInteropValue.Kind family
                : ProtosFixedIntegerInteropValue.Kind.values()) {
            ProtosFixedIntegerInteropValue minimum = fixed(family, minimum(family));
            ProtosFixedIntegerInteropValue maximum = fixed(family, maximum(family));

            assertTrue(interop.isNumber(minimum));
            assertTrue(interop.isNumber(maximum));
            assertTrue(interop.fitsInBigInteger(minimum));
            assertTrue(interop.fitsInBigInteger(maximum));
            assertEquals(minimum(family), interop.asBigInteger(minimum));
            assertEquals(maximum(family), interop.asBigInteger(maximum));
            assertSame(family, minimum.kind());
            assertSame(family, maximum.kind());

            BigInteger maximumValue = maximum(family);
            assertEquals(maximumValue.toString(), interop.toDisplayString(maximum, false));
            assertEquals(
                    maximumValue.bitLength() < Long.SIZE,
                    interop.fitsInLong(maximum),
                    family.name());
            assertEquals(
                    maximumValue.bitLength() < Integer.SIZE,
                    interop.fitsInInt(maximum),
                    family.name());
            assertEquals(
                    maximumValue.bitLength() < Byte.SIZE,
                    interop.fitsInByte(maximum),
                    family.name());
        }
    }

    @Test
    void fixedUnsigned64ProjectsExactlyAboveTheSignedLongRange() throws Exception {
        BigInteger exactDouble = BigInteger.ONE.shiftLeft(64).subtract(BigInteger.ONE.shiftLeft(11));
        ProtosFixedIntegerInteropValue high =
                fixed(ProtosFixedIntegerInteropValue.Kind.UINT64, exactDouble);
        assertTrue(interop.fitsInDouble(high));
        assertEquals(exactDouble.doubleValue(), interop.asDouble(high));
        assertFalse(interop.fitsInFloat(high));
        assertFalse(interop.fitsInLong(high));
        assertFalse(interop.fitsInByte(high));
        assertEquals(exactDouble, interop.asBigInteger(high));

        BigInteger exactFloat = BigInteger.ONE.shiftLeft(64).subtract(BigInteger.ONE.shiftLeft(40));
        ProtosFixedIntegerInteropValue floatHigh =
                fixed(ProtosFixedIntegerInteropValue.Kind.UINT64, exactFloat);
        assertTrue(interop.fitsInFloat(floatHigh));
        assertEquals(exactFloat.floatValue(), interop.asFloat(floatHigh));

        BigInteger odd = BigInteger.ONE.shiftLeft(63).add(BigInteger.ONE);
        ProtosFixedIntegerInteropValue oddHigh =
                fixed(ProtosFixedIntegerInteropValue.Kind.UINT64, odd);
        assertFalse(interop.fitsInDouble(oddHigh));
        assertThrows(UnsupportedMessageException.class, () -> interop.asDouble(oddHigh));
        assertEquals(odd, interop.asBigInteger(oddHigh));
        assertEquals(odd.toString(), interop.toDisplayString(oddHigh, false));

        ProtosFixedIntegerInteropValue negative =
                ProtosFixedIntegerInteropValue.ofLong(
                        ProtosFixedIntegerInteropValue.Kind.INT8, -128L);
        assertEquals((byte) -128, interop.asByte(negative));
        assertEquals(-128.0f, interop.asFloat(negative));
    }

    /* Exact family ranges, derived independently from width and signedness. */
    private static BigInteger minimum(ProtosFixedIntegerInteropValue.Kind family) {
        return family.signed()
                ? BigInteger.ONE.shiftLeft(family.width() - 1).negate()
                : BigInteger.ZERO;
    }

    private static BigInteger maximum(ProtosFixedIntegerInteropValue.Kind family) {
        return family.signed()
                ? BigInteger.ONE.shiftLeft(family.width() - 1).subtract(BigInteger.ONE)
                : BigInteger.ONE.shiftLeft(family.width()).subtract(BigInteger.ONE);
    }

    /* A fixed value of an exact in-range integer: a signed-64 value, or a UINT64 bit pattern. */
    private static ProtosFixedIntegerInteropValue fixed(
            ProtosFixedIntegerInteropValue.Kind family, BigInteger value) {
        if (value.bitLength() < Long.SIZE) {
            return ProtosFixedIntegerInteropValue.ofLong(family, value.longValue());
        }
        assertSame(ProtosFixedIntegerInteropValue.Kind.UINT64, family);
        return ProtosFixedIntegerInteropValue.ofUnsignedBits(value.longValue());
    }

    @Test
    void bigFloatingClaimsUseExactSignificandAndRange() throws Exception {
        BigInteger floatMax = BigInteger.ONE.shiftLeft(24).subtract(BigInteger.ONE).shiftLeft(104);
        assertTrue(interop.fitsInFloat(com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerWithPrototype(floatMax, com.guillermomolina.protos.runtime.ProtosObjectValue.rootObject())));
        assertEquals(Float.MAX_VALUE, interop.asFloat(com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerWithPrototype(floatMax, com.guillermomolina.protos.runtime.ProtosObjectValue.rootObject())));
        assertTrue(interop.fitsInFloat(com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerWithPrototype(floatMax.negate(), com.guillermomolina.protos.runtime.ProtosObjectValue.rootObject())));
        assertFalse(interop.fitsInFloat(com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerWithPrototype(BigInteger.ONE.shiftLeft(128), com.guillermomolina.protos.runtime.ProtosObjectValue.rootObject())));

        BigInteger doubleMax = BigInteger.ONE.shiftLeft(53).subtract(BigInteger.ONE).shiftLeft(971);
        assertTrue(interop.fitsInDouble(com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerWithPrototype(doubleMax, com.guillermomolina.protos.runtime.ProtosObjectValue.rootObject())));
        assertEquals(Double.MAX_VALUE, interop.asDouble(com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerWithPrototype(doubleMax, com.guillermomolina.protos.runtime.ProtosObjectValue.rootObject())));
        assertFalse(interop.fitsInDouble(com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerWithPrototype(BigInteger.ONE.shiftLeft(1024), com.guillermomolina.protos.runtime.ProtosObjectValue.rootObject())));
        assertTrue(interop.fitsInDouble(
                com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerWithPrototype(BigInteger.ONE.shiftLeft(1023).negate(), com.guillermomolina.protos.runtime.ProtosObjectValue.rootObject())));
    }

    @Test
    void integralDisplayIsValueBasedHostOpaqueAndOtherFacetsStayAbsent()
            throws Exception {
        BigInteger integer = BigInteger.ONE.shiftLeft(100).add(BigInteger.valueOf(7));
        Object value = com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerWithPrototype(integer, com.guillermomolina.protos.runtime.ProtosObjectValue.rootObject());

        String display = String.valueOf(interop.toDisplayString(value, false));
        assertEquals(integer.toString(), display);
        assertFalse(display.contains("ProtosIntegerValue"));
        assertFalse(display.contains("@"));

        assertFalse(interop.isString(value));
        assertFalse(interop.isBoolean(value));
        assertFalse(interop.isNull(value));
        assertFalse(interop.hasMembers(value));
        assertFalse(interop.hasArrayElements(value));
        assertFalse(interop.isExecutable(value));
    }

    @Test
    void ordinaryObjectMemberReadPreservesTheExactIntegralGuestValue()
            throws Exception {
        ProtosObjectValue object =
                new ProtosObjectValue(ProtosObjectValue.rootObject());
        Object integer =
                com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerWithPrototype(BigInteger.ONE.shiftLeft(90).add(BigInteger.valueOf(3)), com.guillermomolina.protos.runtime.ProtosObjectValue.rootObject());
        object.createLocalSlot("number", integer);

        Object read = interop.readMember(object, "number");

        assertSame(integer, read);
        assertTrue(interop.isNumber(read));
        assertEquals(com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerDecimalText(integer), String.valueOf(interop.asBigInteger(read)));
    }

    @Test
    void negativePowersOfTwoAndExactNeighboursAreDecidedOnTheMagnitude() throws Exception {
        ProtosIntegerValue minimum = new ProtosIntegerValue(Long.MIN_VALUE);
        assertTrue(interop.fitsInFloat(minimum));
        assertTrue(interop.fitsInDouble(minimum));
        assertEquals(-0x1p63, interop.asDouble(minimum));
        assertEquals(-0x1p63f, interop.asFloat(minimum));
        assertFalse(interop.fitsInDouble(new ProtosIntegerValue(Long.MIN_VALUE + 1L)));
        assertFalse(interop.fitsInDouble(new ProtosIntegerValue(Long.MAX_VALUE)));

        for (int exponent = 0; exponent < 63; exponent++) {
            ProtosIntegerValue negative = new ProtosIntegerValue(-(1L << exponent));
            assertTrue(interop.fitsInFloat(negative));
            assertEquals(-Math.scalb(1.0d, exponent), interop.asDouble(negative));
        }

        BigInteger one = BigInteger.ONE;
        BigInteger[] exact = {
            one.shiftLeft(64), one.shiftLeft(64).negate(), one.shiftLeft(200).negate(),
            one.shiftLeft(53).subtract(one).shiftLeft(100),
            one.shiftLeft(53).subtract(one).shiftLeft(100).negate()
        };
        for (BigInteger value : exact) {
            assertTrue(interop.fitsInDouble(large(value)), value.toString());
            assertEquals(value.doubleValue(), interop.asDouble(large(value)));
            assertFalse(interop.fitsInDouble(large(value.add(one))), value + " + 1");
            assertFalse(interop.fitsInDouble(large(value.subtract(one))), value + " - 1");
        }

        BigInteger floatMax = one.shiftLeft(24).subtract(one).shiftLeft(104);
        assertFalse(interop.fitsInFloat(large(floatMax.add(one))));
        assertFalse(interop.fitsInFloat(large(floatMax.add(one.shiftLeft(104)))));
        assertTrue(interop.fitsInFloat(large(one.shiftLeft(127).negate())));
        BigInteger doubleMax = one.shiftLeft(53).subtract(one).shiftLeft(971);
        assertFalse(interop.fitsInDouble(large(doubleMax.add(one.shiftLeft(971)))));
        assertFalse(interop.fitsInDouble(large(doubleMax.negate().subtract(one))));
    }
}
