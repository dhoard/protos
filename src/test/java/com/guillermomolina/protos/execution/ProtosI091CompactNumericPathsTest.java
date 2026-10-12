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

import com.guillermomolina.protos.runtime.ProtosBinary64Rounding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.guillermomolina.protos.execution.ProtosStandardNumberOrderingProtocol.Comparison;
import com.guillermomolina.protos.runtime.ProtosFloatValue;
import com.guillermomolina.protos.runtime.ProtosIntegerValue;
import com.guillermomolina.protos.runtime.ProtosLargeIntegerValue;
import com.guillermomolina.protos.runtime.ProtosNumericValueSupport;
import com.guillermomolina.protos.runtime.ProtosPrelude;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** I091 compact numeric paths must agree exactly with the arbitrary-precision semantics. */
final class ProtosI091CompactNumericPathsTest {
    @Test
    void exactQuotientRoundingAgreesAcrossCompactAndScaledPaths() throws IOException {
        ProtosPrelude prelude = new ProtosCoreBootstrap()
                .bootstrap(Path.of("protos", "lib", "core"));
        assertEquals(1.0 / 3.0, divide(1L, 3L));
        assertEquals(-2.5, divide(-5L, 2L));
        assertEquals(
                Double.doubleToRawLongBits(0.0d), Double.doubleToRawLongBits(divide(0L, -5L)));
        assertThrows(ArithmeticException.class, () -> divide(1L, 0L));

        // 2^53 + 1 lies halfway between two binary64 values and rounds to even.
        assertEquals(0x1p53, divide((1L << 53) + 1L, 1L));
        assertEquals(0x1p53 + 4.0, divide((1L << 53) + 3L, 1L));
        assertEquals(
                (double) Long.MAX_VALUE / 3.0,
                divide(Long.MAX_VALUE, 3L),
                Math.ulp((double) Long.MAX_VALUE / 3.0));
        assertEquals(
                0x1p64 / 3.0,
                ProtosBinary64Rounding.divideExactIntegers(
                        com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerFromHost(BigInteger.ONE.shiftLeft(64), prelude),
                        new ProtosIntegerValue(3L)));
        assertEquals(
                0x1p63,
                ProtosBinary64Rounding.roundExactInteger(new ProtosIntegerValue(Long.MAX_VALUE)));
    }

    @Test
    void mixedOrderingIsExactForCompactIntegers() throws IOException {
        ProtosPrelude prelude = new ProtosCoreBootstrap()
                .bootstrap(Path.of("protos", "lib", "core"));
        assertEquals(Comparison.GREATER, compare(new ProtosIntegerValue(3L), 2.5));
        assertEquals(Comparison.LESS, compare(new ProtosIntegerValue(-2L), 2.5));
        assertEquals(Comparison.GREATER, compare(new ProtosIntegerValue(-2L), -2.5));
        assertEquals(Comparison.EQUAL, compare(new ProtosIntegerValue(0L), -0.0));
        assertEquals(Comparison.EQUAL, compare(new ProtosIntegerValue(Long.MIN_VALUE), -0x1p63));
        assertEquals(Comparison.LESS, compare(new ProtosIntegerValue(Long.MAX_VALUE), 0x1p63));
        assertEquals(
                Comparison.LESS,
                compare(new ProtosIntegerValue(Long.MIN_VALUE + 1L), -0x1p63 + 2048.0));
        assertEquals(
                Comparison.GREATER,
                compare(new ProtosIntegerValue(Long.MIN_VALUE), Double.NEGATIVE_INFINITY));
        assertEquals(Comparison.UNORDERED, compare(new ProtosIntegerValue(1L), Double.NaN));
        assertEquals(
                Comparison.LESS,
                compare(com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerFromHost(BigInteger.ONE.shiftLeft(64), prelude), 0x1p65));
    }

    @Test
    void largeIntegerOrderingAgainstFloatsIsExactWithoutFloatMaterialization() throws IOException {
        ProtosPrelude prelude = new ProtosCoreBootstrap()
                .bootstrap(Path.of("protos", "lib", "core"));
        BigInteger twoPow64 = BigInteger.ONE.shiftLeft(64);
        BigInteger[] integers = {
            twoPow64, twoPow64.add(BigInteger.ONE), twoPow64.subtract(BigInteger.ONE),
            twoPow64.add(BigInteger.ONE.shiftLeft(11)), twoPow64.add(BigInteger.ONE.shiftLeft(12)),
            BigInteger.ONE.shiftLeft(63), BigInteger.ONE.shiftLeft(70),
            BigInteger.ONE.shiftLeft(1100), BigInteger.ONE.shiftLeft(63).negate().subtract(
                    BigInteger.ONE)
        };
        double[] floats = {
            0x1p63, 0x1p64, -0x1p64, 0x1p64 + 0x1p12, 0x1.fffffffffffffp63, 0x1p70, -0x1p70,
            0x1p1023, -0x1p63, Double.MAX_VALUE, 1.5, -1.5
        };
        for (BigInteger exact : integers) {
            Object integer = com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerFromHost(exact, prelude);
            for (double floating : floats) {
                // Every Integer here is at least 2^63 in magnitude, so truncating a fractional
                // Float cannot turn an inequality into equality.
                int expected = exact.compareTo(new BigDecimal(floating).toBigInteger());
                Comparison actual = compare(integer, floating);
                assertEquals(
                        expected < 0 ? Comparison.LESS
                                : expected > 0 ? Comparison.GREATER : Comparison.EQUAL,
                        actual,
                        exact + " <=> " + floating);
                assertEquals(
                        expected == 0,
                        ProtosCurrentNumericRelations.numericEquals(
                                integer, new ProtosFloatValue(floating)));
            }
        }
    }

    @Test
    void integralBinary64ExtractionSplitsAtTheSignedLongRange() throws IOException {
        ProtosPrelude prelude =
                new ProtosCoreBootstrap().bootstrap(Path.of("protos", "lib", "core"));
        ProtosIntegerValue minimum = assertInstanceOf(
                ProtosIntegerValue.class,
                ProtosBinary64Rounding.integralBinary64(-0x1p63, prelude));
        assertEquals(Long.MIN_VALUE, minimum.longValue());

        ProtosLargeIntegerValue twoPow63 = assertInstanceOf(
                ProtosLargeIntegerValue.class,
                ProtosBinary64Rounding.integralBinary64(0x1p63, prelude));
        assertSame(prelude.bigIntegerPrototype(), twoPow63.parent().orElseThrow());
        assertEquals((BigInteger.ONE.shiftLeft(63)).toString(), com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerDecimalText(twoPow63));
        assertEquals((BigInteger.ONE.shiftLeft(70).negate()).toString(), com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerDecimalText(
                        ProtosBinary64Rounding.integralBinary64(
                                -0x1p70, prelude)));
        assertNull(ProtosBinary64Rounding.integralBinary64(
                0x1p-1074, prelude));
        assertNull(ProtosBinary64Rounding.integralBinary64(0.5, prelude));
    }

    @Test
    void mixedEqualityAndNormalHashAgreeForCompactIntegers() {
        ProtosIntegerValue integer = new ProtosIntegerValue(1L << 60);
        ProtosFloatValue floating = new ProtosFloatValue(0x1p60);
        assertTrue(ProtosCurrentNumericRelations.numericEquals(integer, floating));
        assertFalse(
                ProtosCurrentNumericRelations.numericEquals(
                        new ProtosIntegerValue((1L << 60) + 1L), floating));
        assertTrue(
                ProtosNumericValueSupport.sameInteger(
                        ProtosCurrentNumericRelations.normalHash(integer, null),
                        ProtosCurrentNumericRelations.normalHash(floating, null)));
        assertEquals(Long.toString(Double.hashCode(0.5)), com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerDecimalText(
                        ProtosCurrentNumericRelations.normalHash(new ProtosFloatValue(0.5), null)));
    }

    private static double divide(long numerator, long denominator) {
        return ProtosBinary64Rounding.divideExactIntegers(
                new ProtosIntegerValue(numerator), new ProtosIntegerValue(denominator));
    }

    private static Comparison compare(Object integer, double floating) {
        return ProtosCurrentNumericRelations.compare(integer, new ProtosFloatValue(floating));
    }
}
