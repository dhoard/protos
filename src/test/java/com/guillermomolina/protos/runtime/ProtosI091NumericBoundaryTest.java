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
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.UnsupportedMessageException;
import java.math.BigDecimal;
import java.math.BigInteger;
import org.junit.jupiter.api.Test;

/** I091 semantic Integer capabilities consumed by generic I/O and network clients. */
final class ProtosI091NumericBoundaryTest {
    @Test
    void intRangeRecognitionAndExtraction() {
        assertTrue(ProtosNumericValueSupport.isIntegerInIntRange(new ProtosIntegerValue(65535)));
        assertEquals(65535,
                ProtosNumericValueSupport.exactInt(new ProtosIntegerValue(65535)));
        assertFalse(ProtosNumericValueSupport.isIntegerInIntRange(
                new ProtosIntegerValue(1L + Integer.MAX_VALUE)));
        assertFalse(ProtosNumericValueSupport.isIntegerInIntRange(
                com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerWithPrototype(BigInteger.ONE.shiftLeft(80), com.guillermomolina.protos.runtime.ProtosObjectValue.rootObject())));
        assertFalse(ProtosNumericValueSupport.isIntegerInIntRange(new ProtosFloatValue(1.0)));
        assertFalse(ProtosNumericValueSupport.isIntegerInIntRange(1));
        assertThrows(IllegalArgumentException.class,
                () -> ProtosNumericValueSupport.exactInt(new ProtosFloatValue(1.0)));
    }

    @Test
    void unsignedCodecPreservesExactAddressBits() {
        Object max128 = ProtosNumericValueSupport.integerFromUnsignedBigEndian(
                filled(16), ProtosObjectValue.rootObject());
        assertTrue(ProtosNumericValueSupport.isLargeInteger(max128));
        assertTrue(ProtosNumericValueSupport.isUnsignedIntegerWithin(max128, 128));
        assertFalse(ProtosNumericValueSupport.isUnsignedIntegerWithin(max128, 32));
        assertArrayEquals(filled(16),
                ProtosNumericValueSupport.unsignedBigEndianOrNull(max128, 16));
        assertNull(ProtosNumericValueSupport.unsignedBigEndianOrNull(max128, 4));
        assertNull(ProtosNumericValueSupport.unsignedBigEndianOrNull(
                new ProtosIntegerValue(-1), 4));
    }

    @Test
    void integralBinary64ConversionsShareOneExactAlgorithm() throws Exception {
        double[] values = {
            0.0, -0.0, 1.0, -1.0, 0x1p52 + 1.0, 0x1p63, -0x1p63, 0x1.fffffffffffffp62,
            0x1.fffffffffffffp63, -0x1.0000000000001p64, 0x1p1000, Double.MAX_VALUE,
            -Double.MAX_VALUE
        };
        for (double value : values) {
            BigInteger expected = new BigDecimal(value).toBigIntegerExact();
            assertEquals(expected, ProtosBinary64Rounding.integralBinary64BigInteger(value));
            ProtosFloatValue floating = new ProtosFloatValue(value);
            if (Double.doubleToRawLongBits(value) == Double.doubleToRawLongBits(-0.0d)) {
                // The interop projection deliberately rejects negative zero.
                assertThrows(
                        UnsupportedMessageException.class,
                        () -> InteropLibrary.getUncached().asBigInteger(floating));
            } else {
                assertEquals(expected, InteropLibrary.getUncached().asBigInteger(floating));
            }
            if (expected.bitLength() < Long.SIZE) {
                // The signed-64 branch needs no Prelude.
                ProtosIntegerValue integer = (ProtosIntegerValue)
                        ProtosBinary64Rounding.integralBinary64(value, null);
                assertEquals(expected.longValue(), integer.longValue());
            }
        }
        assertNull(ProtosBinary64Rounding.integralBinary64(0.5, null));
        assertNull(ProtosBinary64Rounding.integralBinary64(Double.NaN, null));
        assertNull(ProtosBinary64Rounding.integralBinary64(Double.POSITIVE_INFINITY, null));
    }

    @Test
    void mixedSmallAndLargeIdentitiesNeedNoArbitraryArithmetic() {
        BigInteger exact = BigInteger.ONE.shiftLeft(100).add(BigInteger.valueOf(12345L));
        for (BigInteger value : new BigInteger[] {exact, exact.negate()}) {
            Object large = com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerWithPrototype(value, com.guillermomolina.protos.runtime.ProtosObjectValue.rootObject());
            Object zero = new ProtosIntegerValue(0L);
            Object one = new ProtosIntegerValue(1L);
            assertSame(large, ProtosNumericValueSupport.addIntegers(large, zero, null));
            assertSame(large, ProtosNumericValueSupport.addIntegers(zero, large, null));
            assertSame(large, ProtosNumericValueSupport.subtractIntegers(large, zero, null));
            assertSame(large, ProtosNumericValueSupport.multiplyIntegers(large, one, null));
            assertSame(large, ProtosNumericValueSupport.multiplyIntegers(one, large, null));
            assertSame(large, ProtosNumericValueSupport.quotientIntegers(large, one, null));
            assertEquals(
                    0L, exactLong(ProtosNumericValueSupport.multiplyIntegers(large, zero, null)));
            assertEquals(
                    0L, exactLong(ProtosNumericValueSupport.multiplyIntegers(zero, large, null)));
            for (long divisor : new long[] {1L, -1L, 7L, -7L, 1L << 40, Long.MIN_VALUE,
                    Long.MAX_VALUE}) {
                Object remainder = ProtosNumericValueSupport.remainderIntegers(
                        large, new ProtosIntegerValue(divisor), null);
                assertEquals(
                        value.remainder(BigInteger.valueOf(divisor)).longValueExact(),
                        exactLong(remainder));
            }
        }
    }

    private static long exactLong(Object integer) {
        return ((ProtosIntegerValue) integer).longValue();
    }

    @Test
    void octetIntegersAreSharedValueEqualIntegers() {
        assertSame(ProtosNumericValueSupport.octet(255), ProtosNumericValueSupport.octet(255));
        // Ordinary results are fresh value-identical carriers, never the byte I/O cache.
        assertNotSame(
                ProtosNumericValueSupport.octet(255), ProtosNumericValueSupport.integer(255));
        assertTrue(ProtosIdentity.identical(
                ProtosNumericValueSupport.octet(255), ProtosNumericValueSupport.integer(255)));
        assertEquals(255L,
                ((ProtosIntegerValue) ProtosNumericValueSupport.octet(255)).longValue());
        assertTrue(ProtosIdentity.identical(
                ProtosNumericValueSupport.octet(7), new ProtosIntegerValue(7)));
        assertEquals(256L,
                ((ProtosIntegerValue) ProtosNumericValueSupport.integer(256)).longValue());
        assertThrows(IllegalArgumentException.class, () -> ProtosNumericValueSupport.octet(256));
    }

    @Test
    void primitiveResultHomeIsAnUnobservableFrameMarker() {
        ProtosReturnHome marker =
                ProtosReturnHome.unobservableAcceptingPrimitiveResultForRuntime();
        assertFalse(marker.isMaterialized());
        assertFalse(marker.isActive());
        assertFalse(marker == ProtosReturnHome.unobservable());
    }

    private static byte[] filled(int width) {
        byte[] bytes = new byte[width];
        java.util.Arrays.fill(bytes, (byte) 0xff);
        return bytes;
    }

    @Test
    void everyFixedKindKeepsItsExactRangeAndProjections() throws Exception {
        InteropLibrary interop = InteropLibrary.getUncached();
        ProtosObjectValue prototype = ProtosObjectValue.rootObject();
        for (ProtosFixedIntegerInteropValue.Kind kind : ProtosFixedIntegerInteropValue.Kind.values()) {
            BigInteger minimum = kind.signed()
                    ? BigInteger.ONE.shiftLeft(kind.width() - 1).negate()
                    : BigInteger.ZERO;
            BigInteger maximum = kind.signed()
                    ? BigInteger.ONE.shiftLeft(kind.width() - 1).subtract(BigInteger.ONE)
                    : BigInteger.ONE.shiftLeft(kind.width()).subtract(BigInteger.ONE);
            assertEquals(minimum, BigInteger.valueOf(kind.minimum()), kind.name());
            assertEquals(maximum, unsignedOrSigned(kind, kind.maximumBits()), kind.name());

            for (BigInteger edge : new BigInteger[] {minimum, maximum}) {
                Object integer = com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerWithPrototype(edge, com.guillermomolina.protos.runtime.ProtosObjectValue.rootObject());
                assertTrue(kind.containsInteger(integer), kind + " " + edge);
                ProtosFixedIntegerInteropValue value =
                        ProtosFixedIntegerInteropValue.ofInteger(kind, integer);
                assertSame(kind, value.kind());
                assertEquals(edge, interop.asBigInteger(value), kind + " " + edge);
                assertEquals(edge, unsignedOrSigned(kind, value.bits()));
                if (edge.bitLength() < Long.SIZE) {
                    // Only a UINT64 value beyond the signed-64 range needs a Prelude.
                    assertEquals(String.valueOf(edge), com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerDecimalText(value.integer(null)));
                }
                assertEquals(edge.bitLength() < Byte.SIZE, interop.fitsInByte(value));
                assertEquals(edge.bitLength() < Short.SIZE, interop.fitsInShort(value));
                assertEquals(edge.bitLength() < Integer.SIZE, interop.fitsInInt(value));
                assertEquals(edge.bitLength() < Long.SIZE, interop.fitsInLong(value));
                if (edge.bitLength() < Long.SIZE) {
                    assertEquals(edge.longValueExact(), interop.asLong(value));
                    assertEquals(value.bits(),
                            ProtosFixedIntegerInteropValue.ofLong(kind, edge.longValueExact()).bits());
                }
            }
            Object below = com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerWithPrototype(minimum.subtract(BigInteger.ONE), com.guillermomolina.protos.runtime.ProtosObjectValue.rootObject());
            Object above = com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerWithPrototype(maximum.add(BigInteger.ONE), com.guillermomolina.protos.runtime.ProtosObjectValue.rootObject());
            assertFalse(kind.containsInteger(below), kind.name());
            assertFalse(kind.containsInteger(above), kind.name());
            assertThrows(IllegalArgumentException.class,
                    () -> ProtosFixedIntegerInteropValue.ofInteger(kind, below));
            assertThrows(IllegalArgumentException.class,
                    () -> ProtosFixedIntegerInteropValue.ofInteger(kind, above));
        }

        // UINT64 with bit 63 set keeps its full mathematical value through every route.
        BigInteger twoPow63 = BigInteger.ONE.shiftLeft(63);
        ProtosFixedIntegerInteropValue high = ProtosFixedIntegerInteropValue.ofInteger(
                ProtosFixedIntegerInteropValue.Kind.UINT64,
                ProtosNumericValueSupport.integerWithPrototype(twoPow63.add(BigInteger.TWO), prototype));
        assertEquals(Long.MIN_VALUE + 2L, high.bits());
        assertEquals(twoPow63.add(BigInteger.TWO), interop.asBigInteger(high));
        assertFalse(interop.fitsInLong(high));
        assertEquals((twoPow63.add(BigInteger.TWO)).toString(), com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerDecimalText(
                        ProtosNumericValueSupport.integerFromUnsignedBigEndian(
                                ProtosNumericValueSupport.unsignedBigEndianOrNull(
                                        com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerWithPrototype(interop.asBigInteger(high), com.guillermomolina.protos.runtime.ProtosObjectValue.rootObject()), 8),
                                prototype)));
        assertThrows(NullPointerException.class, () -> high.integer(null));
        assertThrows(IllegalArgumentException.class,
                () -> ProtosFixedIntegerInteropValue.ofLong(
                        ProtosFixedIntegerInteropValue.Kind.UINT64, -1L));
        assertThrows(IllegalArgumentException.class,
                () -> ProtosFixedIntegerInteropValue.Kind.INT8.containsInteger("not an Integer"));
    }

    private static BigInteger unsignedOrSigned(ProtosFixedIntegerInteropValue.Kind kind, long bits) {
        BigInteger exact = BigInteger.valueOf(bits);
        return !kind.signed() && bits < 0L ? exact.add(BigInteger.ONE.shiftLeft(64)) : exact;
    }
}
