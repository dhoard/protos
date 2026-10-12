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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigInteger;
import org.junit.jupiter.api.Test;

final class ProtosI091UnsignedWidthTest {
    @Test
    void ipv4Boundary() {
        assertTrue(new ProtosIntegerValue(0L)
                .fitsUnsignedBitsForRuntime(32));
        assertTrue(new ProtosIntegerValue(4294967295L)
                .fitsUnsignedBitsForRuntime(32));
        assertFalse(new ProtosIntegerValue(4294967296L)
                .fitsUnsignedBitsForRuntime(32));
        assertFalse(new ProtosIntegerValue(-1L)
                .fitsUnsignedBitsForRuntime(32));
    }

    @Test
    void ipv6BoundaryPreservesArbitraryPrecision() {
        BigInteger upper = BigInteger.ONE.shiftLeft(128);
        assertTrue(ProtosNumericValueSupport.isUnsignedIntegerWithin(
                com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerWithPrototype(upper.subtract(BigInteger.ONE), com.guillermomolina.protos.runtime.ProtosObjectValue.rootObject()), 128));
        assertFalse(ProtosNumericValueSupport.isUnsignedIntegerWithin(
                com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerWithPrototype(upper, com.guillermomolina.protos.runtime.ProtosObjectValue.rootObject()), 128));
        assertFalse(ProtosNumericValueSupport.isUnsignedIntegerWithin(
                com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerWithPrototype(upper.negate(), com.guillermomolina.protos.runtime.ProtosObjectValue.rootObject()), 128));
    }

    @Test
    void signed64AndZeroWidthBoundaries() {
        assertTrue(new ProtosIntegerValue(Long.MAX_VALUE)
                .fitsUnsignedBitsForRuntime(63));
        assertFalse(new ProtosIntegerValue(Long.MIN_VALUE)
                .fitsUnsignedBitsForRuntime(128));
        assertTrue(new ProtosIntegerValue(0L)
                .fitsUnsignedBitsForRuntime(0));
        assertFalse(new ProtosIntegerValue(1L)
                .fitsUnsignedBitsForRuntime(0));
        assertThrows(IllegalArgumentException.class,
                () -> new ProtosIntegerValue(1L)
                        .fitsUnsignedBitsForRuntime(-1));
    }

    @Test
    void bitWidthsAroundTheSigned64Boundary() {
        int[] widths = {0, 1, 7, 8, 31, 32, 63, 64, 65, 128};
        for (int bits : widths) {
            assertTrue(new ProtosIntegerValue(0L).fitsUnsignedBitsForRuntime(bits));
            assertFalse(new ProtosIntegerValue(-1L).fitsUnsignedBitsForRuntime(bits));
            assertFalse(new ProtosIntegerValue(Long.MIN_VALUE).fitsUnsignedBitsForRuntime(bits));
            boolean maxFits = bits >= 63;
            assertEquals(maxFits,
                    new ProtosIntegerValue(Long.MAX_VALUE).fitsUnsignedBitsForRuntime(bits));
            if (bits > 0 && bits < 63) {
                long max = (1L << bits) - 1L;
                assertTrue(new ProtosIntegerValue(max).fitsUnsignedBitsForRuntime(bits));
                assertFalse(new ProtosIntegerValue(max + 1L).fitsUnsignedBitsForRuntime(bits));
            }
        }
        assertFalse(new ProtosIntegerValue(1L).fitsUnsignedBitsForRuntime(0));
    }

    @Test
    void octetWidthIsDistinctFromBitWidthAndNeverWraps() {
        assertEquals(0, ProtosNumericValueSupport.unsignedBitsOfOctets(0));
        assertEquals(64, ProtosNumericValueSupport.unsignedBitsOfOctets(8));
        assertEquals(128, ProtosNumericValueSupport.unsignedBitsOfOctets(16));
        // 2^29 octets would wrap to bit width 0 under int arithmetic; it saturates instead.
        assertEquals(Integer.MAX_VALUE, ProtosNumericValueSupport.unsignedBitsOfOctets(1 << 29));
        assertEquals(Integer.MAX_VALUE,
                ProtosNumericValueSupport.unsignedBitsOfOctets(Integer.MAX_VALUE));
        assertThrows(IllegalArgumentException.class,
                () -> ProtosNumericValueSupport.unsignedBitsOfOctets(-1));

        assertArrayEquals(new byte[] {0, 0, 0, (byte) 0xff},
                new ProtosIntegerValue(255L).toUnsignedBigEndianForRuntime(4));
        assertArrayEquals(new byte[0], new ProtosIntegerValue(0L).toUnsignedBigEndianForRuntime(0));
        assertThrows(ArithmeticException.class,
                () -> new ProtosIntegerValue(1L).toUnsignedBigEndianForRuntime(0));
        assertThrows(ArithmeticException.class,
                () -> new ProtosIntegerValue(-1L).toUnsignedBigEndianForRuntime(1 << 29));
        assertThrows(ArithmeticException.class,
                () -> new ProtosIntegerValue(1L).toUnsignedBigEndianForRuntime(-1));
        assertNull(ProtosNumericValueSupport.unsignedBigEndianOrNull(
                new ProtosIntegerValue(-1L), 1 << 29));
        assertNull(ProtosNumericValueSupport.unsignedBigEndianOrNull(
                new ProtosIntegerValue(1L), -1));
    }

    @Test
    void largeEncodingRefusesAWidthItDoesNotFit() {
        Object twoPow64 = com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerWithPrototype(BigInteger.ONE.shiftLeft(64), com.guillermomolina.protos.runtime.ProtosObjectValue.rootObject());
        ProtosLargeIntegerValue large = (ProtosLargeIntegerValue) twoPow64;
        assertThrows(ArithmeticException.class, () -> large.toUnsignedBigEndian(8));
        assertEquals(9, large.toUnsignedBigEndian(9).length);
        ProtosLargeIntegerValue negative = (ProtosLargeIntegerValue)
                com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerWithPrototype(BigInteger.ONE.shiftLeft(64).negate(), com.guillermomolina.protos.runtime.ProtosObjectValue.rootObject());
        assertThrows(ArithmeticException.class, () -> negative.toUnsignedBigEndian(16));
    }
}
