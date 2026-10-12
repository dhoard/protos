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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.UnsupportedMessageException;
import java.math.BigInteger;
import org.junit.jupiter.api.Test;

/** I091 compact-Integer support that must stay exact without arbitrary-precision allocation. */
final class ProtosI091CompactIntegerSupportTest {
    private final InteropLibrary interop = InteropLibrary.getUncached();
    private static final ProtosObjectValue PROTOTYPE = ProtosObjectValue.rootObject();

    @Test
    void unsignedBigEndianCodecIsExactAcrossRepresentations() {
        ProtosIntegerValue ipv4 = assertInstanceOf(
                ProtosIntegerValue.class,
                ProtosNumericValueSupport.integerFromUnsignedBigEndian(
                        new byte[] {(byte) 192, (byte) 168, 0, 1}, PROTOTYPE));
        assertEquals(3232235521L, ipv4.longValue());
        assertArrayEquals(
                new byte[] {(byte) 192, (byte) 168, 0, 1}, ipv4.toUnsignedBigEndianForRuntime(4));

        byte[] allOnes = new byte[8];
        java.util.Arrays.fill(allOnes, (byte) 0xff);
        ProtosLargeIntegerValue beyondLong = assertInstanceOf(
                ProtosLargeIntegerValue.class,
                ProtosNumericValueSupport.integerFromUnsignedBigEndian(allOnes, PROTOTYPE));
        assertEquals((BigInteger.ONE.shiftLeft(64).subtract(BigInteger.ONE)).toString(), com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerDecimalText(beyondLong));
        assertArrayEquals(allOnes, ProtosNumericValueSupport.unsignedBigEndianOrNull(beyondLong, 8));

        byte[] ipv6 = new byte[16];
        ipv6[15] = 1;
        ProtosIntegerValue loopback = assertInstanceOf(
                ProtosIntegerValue.class,
                ProtosNumericValueSupport.integerFromUnsignedBigEndian(ipv6, (ProtosPrelude) null));
        assertArrayEquals(ipv6, loopback.toUnsignedBigEndianForRuntime(16));

        byte[] ipv6Max = new byte[16];
        java.util.Arrays.fill(ipv6Max, (byte) 0xff);
        assertArrayEquals(
                ipv6Max,
                ProtosNumericValueSupport.unsignedBigEndianOrNull(
                        ProtosNumericValueSupport.integerFromUnsignedBigEndian(ipv6Max, PROTOTYPE),
                        16));
        assertNull(ProtosNumericValueSupport.unsignedBigEndianOrNull(beyondLong, 7));

        assertThrows(
                ArithmeticException.class,
                () -> new ProtosIntegerValue(256L).toUnsignedBigEndianForRuntime(1));
        assertThrows(
                ArithmeticException.class,
                () -> new ProtosIntegerValue(-1L).toUnsignedBigEndianForRuntime(8));
    }

    @Test
    void unsignedBigEndianDecodeUsesTheUnsignedLeadingOctetAtEveryBoundary() {
        BigInteger two63 = BigInteger.ONE.shiftLeft(63);
        BigInteger two64 = BigInteger.ONE.shiftLeft(64);
        BigInteger[] values = {
            BigInteger.ZERO,
            BigInteger.ONE,
            two63.subtract(BigInteger.ONE),
            two63,
            two64.subtract(BigInteger.ONE),
            two64,
            BigInteger.ONE.shiftLeft(128).subtract(BigInteger.ONE),
        };
        for (BigInteger expected : values) {
            int minimal = Math.max(1, (expected.bitLength() + Byte.SIZE - 1) / Byte.SIZE);
            for (int padding = 0; padding <= 3; padding++) {
                int width = minimal + padding;
                byte[] octets = unsignedOctets(expected, width);
                Object decoded = ProtosNumericValueSupport.integerFromUnsignedBigEndian(
                        octets, PROTOTYPE);
                if (expected.bitLength() < Long.SIZE) {
                    assertSmall(expected.longValueExact(), decoded);
                } else {
                    assertInstanceOf(ProtosLargeIntegerValue.class, decoded);
                }
                assertEquals(String.valueOf(expected), com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerDecimalText(decoded), "width " + width);
                assertArrayEquals(
                        octets, ProtosNumericValueSupport.unsignedBigEndianOrNull(decoded, width));
            }
        }

        // 2^63 in exactly eight octets has its high bit set; it must never decode to Long.MIN_VALUE.
        Object twoPow63 = ProtosNumericValueSupport.integerFromUnsignedBigEndian(
                new byte[] {(byte) 0x80, 0, 0, 0, 0, 0, 0, 0}, PROTOTYPE);
        assertInstanceOf(ProtosLargeIntegerValue.class, twoPow63);
        assertEquals(String.valueOf(two63), com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerDecimalText(twoPow63));
        assertSmall(Long.MAX_VALUE, ProtosNumericValueSupport.integerFromUnsignedBigEndian(
                new byte[] {0x7f, -1, -1, -1, -1, -1, -1, -1}, (ProtosPrelude) null));
    }

    @Test
    void largeUnsignedDecodeWithoutAPreludeFailsInsteadOfAnsweringNull() {
        byte[] allOnes = new byte[8];
        java.util.Arrays.fill(allOnes, (byte) 0xff);
        assertThrows(
                NullPointerException.class,
                () -> ProtosNumericValueSupport.integerFromUnsignedBigEndian(
                        allOnes, (ProtosPrelude) null));
    }

    private static byte[] unsignedOctets(BigInteger value, int width) {
        byte[] raw = value.toByteArray();
        int offset = raw.length > 1 && raw[0] == 0 ? 1 : 0;
        byte[] result = new byte[width];
        System.arraycopy(raw, offset, result, width - (raw.length - offset), raw.length - offset);
        return result;
    }

    @Test
    void exactHashCodeMatchesCanonicalArbitraryPrecisionHash() {
        long[] samples = {
            0L, 1L, -1L, 42L, -42L, 0xffffffffL, 0x100000000L, -0x100000000L,
            Long.MAX_VALUE, Long.MIN_VALUE, Long.MIN_VALUE + 1L
        };
        for (long sample : samples) {
            assertEquals(
                    BigInteger.valueOf(sample).hashCode(),
                    new ProtosIntegerValue(sample).exactHashCodeForRuntime(),
                    Long.toString(sample));
        }
        BigInteger huge = BigInteger.ONE.shiftLeft(100).negate();
        assertEquals(
                huge.hashCode(),
                ProtosNumericValueSupport.integerHashCode(com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerWithPrototype(huge, com.guillermomolina.protos.runtime.ProtosObjectValue.rootObject())));
    }

    @Test
    void floatingInteropProjectionIsExactForCompactValues() throws UnsupportedMessageException {
        ProtosIntegerValue exact = new ProtosIntegerValue(1L << 53);
        assertTrue(interop.fitsInDouble(exact));
        assertEquals(0x1p53, interop.asDouble(exact));

        ProtosIntegerValue inexact = new ProtosIntegerValue((1L << 53) + 1L);
        assertFalse(interop.fitsInDouble(inexact));
        assertThrows(UnsupportedMessageException.class, () -> interop.asDouble(inexact));

        ProtosIntegerValue maximum = new ProtosIntegerValue(Long.MAX_VALUE);
        assertFalse(interop.fitsInDouble(maximum));
        assertFalse(interop.fitsInFloat(maximum));

        ProtosIntegerValue minimum = new ProtosIntegerValue(Long.MIN_VALUE);
        assertTrue(interop.fitsInDouble(minimum));
        assertTrue(interop.fitsInFloat(minimum));
        assertEquals(-0x1p63f, interop.asFloat(minimum));

        assertTrue(interop.fitsInFloat(new ProtosIntegerValue(1L << 24)));
        assertFalse(interop.fitsInFloat(new ProtosIntegerValue((1L << 24) + 1L)));

        Object twoPow64 = com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerWithPrototype(BigInteger.ONE.shiftLeft(64), com.guillermomolina.protos.runtime.ProtosObjectValue.rootObject());
        assertFalse(interop.fitsInLong(twoPow64));
        assertTrue(interop.fitsInDouble(twoPow64));
        assertEquals(0x1p64, interop.asDouble(twoPow64));
        assertThrows(UnsupportedMessageException.class, () -> interop.asLong(twoPow64));
    }

    @Test
    void bytesOctetSnapshotCopiesTheOctetInvariant() {
        ProtosBytesValue bytes = new ProtosBytesValue(ProtosObjectValue.rootObject());
        bytes.indexedAdd(new ProtosIntegerValue(0L));
        bytes.indexedAdd(new ProtosIntegerValue(255L));
        assertArrayEquals(new byte[] {0, (byte) 255}, bytes.octetSnapshot());

        bytes.indexedAdd(new ProtosIntegerValue(256L));
        assertThrows(IllegalStateException.class, bytes::octetSnapshot);
    }

    @Test
    void integerLiteralsStayExactAtTheSignedLongBoundary() {
        assertLiteral(Long.MAX_VALUE, "9223372036854775807");
        assertLiteral(999_999_999_999_999_999L, "999999999999999999");
        assertLiteral(Long.MAX_VALUE, "0o777777777777777777777");
        assertLiteral(Long.MAX_VALUE, "0b" + "1".repeat(63));
        assertLiteral(0xfffffffffffffffL, "0xfffffffffffffff");

        // Beyond the signed-64 range a literal is a lowering-time exact descriptor.
        assertEquals(
                BigInteger.ONE.shiftLeft(64).subtract(BigInteger.ONE),
                ProtosNumberLiteral.parse("0xffffffffffffffff"));
    }

    @Test
    void integerLiteralsAdmitEveryLongExactlyAndOverflowToArbitraryPrecision() {
        assertLiteral(Long.MAX_VALUE, "0x7fffffffffffffff");
        assertLiteral(Long.MAX_VALUE, "9_223_372_036_854_775_807");
        assertLiteral(1L, "0000000000000000000000001");
        assertLiteral(0L, "0x0000000000000000000");
        assertLiteral(1_000_000_000_000_000_000L, "1000000000000000000");

        assertEquals(BigInteger.ONE.shiftLeft(63), ProtosNumberLiteral.parse("9223372036854775808"));
        assertEquals(BigInteger.ONE.shiftLeft(63), ProtosNumberLiteral.parse("0x8000000000000000"));

        assertThrows(NumberFormatException.class, () -> ProtosNumberLiteral.parse("0x"));
    }

    @Test
    void smallDividedByBigNeedsNoArbitraryPrecisionAndStaysExact() {
        Object twoPow63 = com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerWithPrototype(BigInteger.ONE.shiftLeft(63), com.guillermomolina.protos.runtime.ProtosObjectValue.rootObject());
        Object twoPow70 = com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerWithPrototype(BigInteger.ONE.shiftLeft(70), com.guillermomolina.protos.runtime.ProtosObjectValue.rootObject());
        Object negativeTwoPow70 = com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerWithPrototype(BigInteger.ONE.shiftLeft(70).negate(), com.guillermomolina.protos.runtime.ProtosObjectValue.rootObject());
        ProtosIntegerValue minLong = new ProtosIntegerValue(Long.MIN_VALUE);
        ProtosIntegerValue five = new ProtosIntegerValue(5L);
        ProtosIntegerValue minusFive = new ProtosIntegerValue(-5L);

        // No Prelude: a small-by-large quotient or remainder never needs to mint a large value.
        assertSmall(-1L, ProtosNumericValueSupport.quotientIntegers(minLong, twoPow63, null));
        assertSmall(0L, ProtosNumericValueSupport.remainderIntegers(minLong, twoPow63, null));
        assertSmall(0L, ProtosNumericValueSupport.quotientIntegers(five, twoPow70, null));
        assertSmall(5L, ProtosNumericValueSupport.remainderIntegers(five, twoPow70, null));
        assertSmall(0L, ProtosNumericValueSupport.quotientIntegers(minusFive, negativeTwoPow70, null));
        assertSmall(-5L, ProtosNumericValueSupport.remainderIntegers(minusFive, negativeTwoPow70, null));
        assertSmall(0L, ProtosNumericValueSupport.quotientIntegers(minLong, negativeTwoPow70, null));
        assertSmall(Long.MIN_VALUE,
                ProtosNumericValueSupport.remainderIntegers(minLong, negativeTwoPow70, null));
        assertSmall(0L, ProtosNumericValueSupport.quotientIntegers(
                minLong, com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerWithPrototype(BigInteger.ONE.shiftLeft(63).add(BigInteger.ONE), com.guillermomolina.protos.runtime.ProtosObjectValue.rootObject()),
                null));

        BigInteger big = BigInteger.ONE.shiftLeft(63).add(BigInteger.TWO);
        for (long dividend : new long[] {Long.MIN_VALUE, Long.MAX_VALUE, -1L, 0L, 7L}) {
            ProtosIntegerValue left = new ProtosIntegerValue(dividend);
            Object right = com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerWithPrototype(big, com.guillermomolina.protos.runtime.ProtosObjectValue.rootObject());
            assertEquals((BigInteger.valueOf(dividend).divide(big)).toString(), com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerDecimalText(
                            ProtosNumericValueSupport.quotientIntegers(left, right, null)));
            assertEquals((BigInteger.valueOf(dividend).remainder(big)).toString(), com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerDecimalText(
                            ProtosNumericValueSupport.remainderIntegers(left, right, null)));
        }
    }

    @Test
    void arithmeticNormalizesInBothDirectionsAtTheSignedLongBoundary() {
        Object large = com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerWithPrototype(BigInteger.ONE.shiftLeft(63), com.guillermomolina.protos.runtime.ProtosObjectValue.rootObject());
        assertInstanceOf(ProtosLargeIntegerValue.class, large);
        // Demotion: an exact result back inside the signed-64 range is a ProtosIntegerValue,
        // so no owner Prelude is needed for it.
        assertSmall(Long.MAX_VALUE,
                ProtosNumericValueSupport.subtractIntegers(large, new ProtosIntegerValue(1L), null));
        assertSmall(Long.MIN_VALUE,
                ProtosNumericValueSupport.multiplyIntegers(
                        large, new ProtosIntegerValue(-1L), null));
        assertSmall(1L << 62,
                ProtosNumericValueSupport.multiplyIntegers(
                        new ProtosIntegerValue(1L << 31), new ProtosIntegerValue(1L << 31), null));

        // Without an owning Prelude, a result outside the range answers null for the fallback.
        assertNull(ProtosNumericValueSupport.addIntegers(
                new ProtosIntegerValue(Long.MAX_VALUE), new ProtosIntegerValue(1L), null));
        assertNull(ProtosNumericValueSupport.subtractIntegers(
                new ProtosIntegerValue(Long.MIN_VALUE), new ProtosIntegerValue(1L), null));
        assertNull(ProtosNumericValueSupport.multiplyIntegers(
                new ProtosIntegerValue(1L << 32), new ProtosIntegerValue(1L << 31), null));
        assertNull(ProtosNumericValueSupport.quotientIntegers(
                new ProtosIntegerValue(Long.MIN_VALUE), new ProtosIntegerValue(-1L), null));
        assertSmall(0L, ProtosNumericValueSupport.remainderIntegers(
                new ProtosIntegerValue(Long.MIN_VALUE), new ProtosIntegerValue(-1L), null));
        assertSmall(0L, ProtosNumericValueSupport.quotientIntegers(
                new ProtosIntegerValue(5L), large, null));
        assertNull(ProtosNumericValueSupport.addIntegers(
                large, com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerWithPrototype(BigInteger.ONE.shiftLeft(63), com.guillermomolina.protos.runtime.ProtosObjectValue.rootObject()), null));
        // A large operand whose exact result is back inside the range needs no Prelude.
        assertSmall(Long.MAX_VALUE,
                ProtosNumericValueSupport.addIntegers(large, new ProtosIntegerValue(-1L), null));
    }

    @Test
    void decimalTextIsCanonicalAcrossRepresentations() {
        assertEquals("-9223372036854775808",
                new ProtosIntegerValue(Long.MIN_VALUE).decimalTextForRuntime());
        assertEquals("0", new ProtosIntegerValue(0L).decimalTextForRuntime());
        BigInteger huge = BigInteger.ONE.shiftLeft(100).negate();
        assertEquals(huge.toString(),
                ProtosNumericValueSupport.integerDecimalText(com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerWithPrototype(huge, com.guillermomolina.protos.runtime.ProtosObjectValue.rootObject())));
    }

    @Test
    void integralFloatProjectsToExactBigIntegerInAndBeyondTheLongRange()
            throws UnsupportedMessageException {
        assertEquals(BigInteger.valueOf(-(1L << 62)),
                interop.asBigInteger(new ProtosFloatValue(-0x1p62)));
        assertEquals(BigInteger.valueOf(Long.MIN_VALUE),
                interop.asBigInteger(new ProtosFloatValue(-0x1p63)));
        assertEquals(BigInteger.ONE.shiftLeft(63),
                interop.asBigInteger(new ProtosFloatValue(0x1p63)));
        assertEquals(BigInteger.ONE.shiftLeft(1000).negate(),
                interop.asBigInteger(new ProtosFloatValue(-0x1p1000)));
        assertEquals(BigInteger.ZERO, interop.asBigInteger(new ProtosFloatValue(0.0)));
        assertFalse(interop.fitsInBigInteger(new ProtosFloatValue(-0.0)));
        assertFalse(interop.fitsInBigInteger(new ProtosFloatValue(0.5)));
        assertThrows(UnsupportedMessageException.class,
                () -> interop.asBigInteger(new ProtosFloatValue(Double.POSITIVE_INFINITY)));
    }

    private static void assertSmall(long expected, Object value) {
        assertEquals(expected, assertInstanceOf(ProtosIntegerValue.class, value).longValue());
    }

    private static void assertLiteral(long expected, String spelling) {
        Long value = assertInstanceOf(Long.class, ProtosNumberLiteral.parse(spelling));
        assertEquals(expected, value.longValue(), spelling);
    }
}
