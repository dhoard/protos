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
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * I091: Integer / Integer rounds the exact rational quotient once to binary64, ties to even,
 * on both the primitive signed-64 path and the arbitrary-precision path. The oracle is an
 * independent BigDecimal construction used only by these tests.
 */
final class ProtosI091Binary64RoundingTest {
    private static final BigInteger LONG_MIN = BigInteger.valueOf(Long.MIN_VALUE);
    private static final BigInteger LONG_MAX = BigInteger.valueOf(Long.MAX_VALUE);

    @Test
    void zeroQuotientsArePositiveZeroAndZeroDivisorsFail() {
        assertSameDouble(0.0d, divide(0L, 7L));
        assertSameDouble(0.0d, divide(0L, -7L));
        assertSameDouble(0.0d, divide(0L, Long.MIN_VALUE));
        assertThrows(ArithmeticException.class, () -> divide(1L, 0L));
        assertThrows(ArithmeticException.class, () -> divide(0L, 0L));
        assertThrows(
                ArithmeticException.class,
                () -> divide(BigInteger.ONE.shiftLeft(100), BigInteger.ZERO));
    }

    @Test
    void signedSixtyFourBoundaryOperandsRoundCorrectly() {
        long[] edges = {
            Long.MIN_VALUE, Long.MIN_VALUE + 1L, Long.MAX_VALUE, Long.MAX_VALUE - 1L,
            1L << 53, (1L << 53) - 1L, (1L << 53) + 1L, -(1L << 53) - 1L,
            (1L << 62) + 1L, -1L, 1L, 2L, 3L, -3L, 10L, 1L << 32, (1L << 32) + 1L
        };
        for (long numerator : edges) {
            for (long denominator : edges) {
                assertDivision(BigInteger.valueOf(numerator), BigInteger.valueOf(denominator));
            }
        }
        assertSameDouble(0x1p63, divide(Long.MIN_VALUE, -1L));
        assertSameDouble(-0x1p63, divide(Long.MIN_VALUE, 1L));
        assertSameDouble(1.0d, divide(Long.MIN_VALUE, Long.MIN_VALUE));
        assertSameDouble(-0x1p-63, divide(1L, Long.MIN_VALUE));
        assertSameDouble(0x1p63, divide(Long.MAX_VALUE, 1L));
    }

    @Test
    void halfwayQuotientsRoundToEven() {
        // 2^53 + 1 and 2^53 + 3 lie halfway between neighbours; even significands win.
        assertSameDouble(0x1p53, divide((1L << 53) + 1L, 1L));
        assertSameDouble(0x1p53 + 4.0, divide((1L << 53) + 3L, 1L));
        // (2^54 + 2) / 2 is the same halfway point reached through a nontrivial division.
        assertSameDouble(0x1p53, divide((1L << 54) + 2L, 2L));
        // One unit past halfway rounds up; one unit short rounds down.
        assertSameDouble(0x1p53 + 2.0, divide((1L << 54) + 3L, 2L));
        assertSameDouble(0x1p53, divide((1L << 54) + 1L, 2L));
        // Fractional quotients: 2^52 + 1/4 rounds down, 2^52 + 3/2 and 2^52 + 1/2 tie to even.
        assertSameDouble(0x1p52, divide((1L << 54) + 1L, 4L));
        assertSameDouble(0x1p52 + 2.0, divide((1L << 54) + 6L, 4L));
        assertSameDouble(0x1p52, divide((1L << 54) + 2L, 4L));
    }

    @Test
    void significandCarryReachesTheNextBinade() {
        // (2^54 - 1) / 2 = 2^53 - 1/2 rounds to 2^53 (carry), and the signed-64 max to 2^63.
        assertSameDouble(0x1p53, divide((1L << 54) - 1L, 2L));
        assertSameDouble(0x1p63, divide(Long.MAX_VALUE, 1L));
        assertSameDouble(1.0d, divide(Long.MAX_VALUE, Long.MAX_VALUE - 1L));
        assertDivision(LONG_MAX, LONG_MAX.subtract(BigInteger.ONE));
    }

    @Test
    void nearPowersOfTwoRoundCorrectly() {
        BigInteger big = BigInteger.ONE.shiftLeft(3000);
        assertSameDouble(1.0d, divide(big.add(BigInteger.ONE), big));
        assertSameDouble(1.0d, divide(big.subtract(BigInteger.ONE), big));
        assertSameDouble(0x1p-1000, divide(big, big.shiftLeft(1000)));
        assertSameDouble(0x1p1000, divide(big.shiftLeft(1000), big));
        for (int offset = -3; offset <= 3; offset++) {
            assertDivision(big.add(BigInteger.valueOf(offset)), big);
            assertDivision(big, big.add(BigInteger.valueOf(offset)));
            assertDivision(
                    BigInteger.ONE.shiftLeft(64).add(BigInteger.valueOf(offset)),
                    BigInteger.valueOf(3L));
        }
    }

    @Test
    void subnormalUnderflowAndOverflowBoundaries() {
        BigInteger one = BigInteger.ONE;
        assertSameDouble(Double.MIN_NORMAL, divide(one, one.shiftLeft(1022)));
        assertSameDouble(Double.MIN_VALUE, divide(one, one.shiftLeft(1074)));
        assertSameDouble(-Double.MIN_VALUE, divide(one.negate(), one.shiftLeft(1074)));
        // Exactly half the least subnormal ties to zero; any excess rounds up.
        assertSameDouble(0.0d, divide(one, one.shiftLeft(1075)));
        assertSameDouble(-0.0d, divide(one, one.shiftLeft(1075).negate()));
        assertSameDouble(
                Double.MIN_VALUE,
                divide(one.shiftLeft(1000).add(one), one.shiftLeft(2075)));
        assertSameDouble(0.0d, divide(one, one.shiftLeft(1076)));
        assertSameDouble(-0.0d, divide(one.negate(), one.shiftLeft(5000)));
        // Three-halves of the least subnormal ties up to the even two.
        assertSameDouble(
                2 * Double.MIN_VALUE, divide(BigInteger.valueOf(3L), one.shiftLeft(1075)));
        // The largest subnormal plus a half unit carries into the least normal.
        BigInteger largestSubnormalTwice = one.shiftLeft(53).subtract(BigInteger.TWO).add(one);
        assertSameDouble(Double.MIN_NORMAL, divide(largestSubnormalTwice, one.shiftLeft(1075)));
        for (int shift = 1018; shift <= 1080; shift++) {
            assertDivision(BigInteger.valueOf(0x1234_5678_9abcL), one.shiftLeft(shift + 45));
            assertDivision(BigInteger.valueOf(3L), one.shiftLeft(shift));
        }

        BigInteger maxFinite = new BigDecimal(Double.MAX_VALUE).toBigIntegerExact();
        BigInteger halfUlpAtMax = one.shiftLeft(970);
        assertSameDouble(Double.MAX_VALUE, divide(maxFinite, one));
        assertSameDouble(Double.MAX_VALUE, divide(maxFinite.shiftLeft(7), one.shiftLeft(7)));
        // The halfway point above the maximum finite value rounds to infinity (odd significand).
        assertSameDouble(Double.POSITIVE_INFINITY, divide(maxFinite.add(halfUlpAtMax), one));
        assertSameDouble(
                Double.MAX_VALUE, divide(maxFinite.add(halfUlpAtMax).subtract(one), one));
        assertSameDouble(Double.NEGATIVE_INFINITY, divide(one.shiftLeft(1024).negate(), one));
        assertSameDouble(Double.POSITIVE_INFINITY, divide(one.shiftLeft(5000), BigInteger.TWO));
    }

    @Test
    void thousandBitMagnitudesRoundCorrectly() {
        Random random = new Random(0x1091L);
        for (int index = 0; index < 400; index++) {
            BigInteger numerator = randomInteger(random, 1 + random.nextInt(4000));
            BigInteger denominator = randomInteger(random, 1 + random.nextInt(4000));
            if (denominator.signum() == 0) {
                continue;
            }
            assertDivision(numerator, denominator);
            // Similar magnitudes: a perturbed copy of the numerator.
            BigInteger nearby =
                    numerator.add(BigInteger.valueOf(random.nextInt(9) - 4)).or(BigInteger.ONE);
            assertDivision(numerator, nearby);
        }
    }

    @Test
    void powerOfTwoDivisorsRoundTheDividendOnce() {
        long[] numerators = {
            Long.MAX_VALUE, Long.MIN_VALUE, (1L << 53) + 1L, (1L << 54) + 2L, -((1L << 60) + 3L),
            0x7654_3210_fedc_ba98L, 1L, -1L, 3L
        };
        for (long numerator : numerators) {
            for (int shift = 0; shift < 64; shift++) {
                long divisor = 1L << shift;
                assertDivision(BigInteger.valueOf(numerator), BigInteger.valueOf(divisor));
                if (divisor != Long.MIN_VALUE) {
                    assertDivision(BigInteger.valueOf(numerator), BigInteger.valueOf(-divisor));
                }
            }
        }
        BigInteger one = BigInteger.ONE;
        BigInteger[] large = {
            one.shiftLeft(63), one.shiftLeft(64).add(one), one.shiftLeft(1022).subtract(one),
            one.shiftLeft(1023).subtract(one), one.shiftLeft(1023),
            one.shiftLeft(1024).subtract(one), one.shiftLeft(1030).negate(),
            new BigDecimal(Double.MAX_VALUE).toBigIntegerExact().add(one.shiftLeft(970))
                    .shiftLeft(5)
        };
        for (BigInteger value : large) {
            for (int shift = 0; shift < 64; shift++) {
                assertDivision(value, one.shiftLeft(shift));
                assertDivision(value, one.shiftLeft(shift).negate());
            }
        }
    }

    @Test
    void operandsWithinTwoPowFiftyThreeAndJustBeyondAgree() {
        long[] edges = {
            (1L << 53), (1L << 53) - 1L, (1L << 53) + 1L, -(1L << 53), -(1L << 53) - 1L, 7L, -9L
        };
        for (long numerator : edges) {
            for (long denominator : edges) {
                assertDivision(BigInteger.valueOf(numerator), BigInteger.valueOf(denominator));
            }
        }
    }

    @Test
    void randomSignedSixtyFourQuotientsMatchTheOracle() {
        Random random = new Random(0x64L);
        for (int index = 0; index < 20_000; index++) {
            long numerator = random.nextLong() >> random.nextInt(64);
            long denominator = random.nextLong() >> random.nextInt(64);
            if (denominator == 0L) {
                continue;
            }
            assertDivision(BigInteger.valueOf(numerator), BigInteger.valueOf(denominator));
        }
    }

    @Test
    void mixedPrimitiveAndLargeOperandsMatchTheOracle() {
        BigInteger[] large = {
            LONG_MAX.add(BigInteger.ONE),
            LONG_MIN.subtract(BigInteger.ONE),
            BigInteger.ONE.shiftLeft(64).subtract(BigInteger.ONE),
            BigInteger.TEN.pow(40)
        };
        long[] small = {1L, -1L, 3L, -7L, Long.MAX_VALUE, Long.MIN_VALUE, 1L << 53};
        for (BigInteger value : large) {
            for (long primitive : small) {
                assertDivision(value, BigInteger.valueOf(primitive));
                assertDivision(BigInteger.valueOf(primitive), value);
            }
        }
    }

    @Test
    void compactRationalFloatExtremesMatchTheExactDecimalOracle() {
        long[][] fractions = {
            {1L, 3L}, {-1L, 3L},
            {Long.MAX_VALUE, 1L}, {Long.MIN_VALUE, 1L},
            {1L, Long.MAX_VALUE}, {-1L, Long.MAX_VALUE},
            {0L, 1L}
        };
        double[] floats = {
            0x1p64, -0x1p64,
            Math.nextDown(0x1p64), -Math.nextDown(0x1p64),
            Double.MAX_VALUE, -Double.MAX_VALUE,
            0x1p63, -0x1p63,
            0x1p-64, -0x1p-64,
            Math.nextUp(0x1p-64), -Math.nextUp(0x1p-64),
            Double.MIN_NORMAL, -Double.MIN_NORMAL,
            Double.MIN_VALUE, -Double.MIN_VALUE,
            0.5d, -0.5d
        };

        for (long[] fraction : fractions) {
            long numerator = fraction[0];
            long denominator = fraction[1];
            Object n = new ProtosIntegerValue(numerator);
            Object d = new ProtosIntegerValue(denominator);
            BigDecimal exactNumerator = BigDecimal.valueOf(numerator);
            BigDecimal exactDenominator = BigDecimal.valueOf(denominator);

            for (double floating : floats) {
                // BigDecimal(double) preserves the exact binary64 value.
                int expected = Integer.signum(
                        exactNumerator.compareTo(
                                new BigDecimal(floating).multiply(exactDenominator)));
                int actual = Integer.signum(
                        ProtosBinary64Rounding.compareRationalToBinary64(
                                n, d, floating));

                assertEquals(
                        expected,
                        actual,
                        numerator + " / " + denominator
                                + " versus " + Double.toHexString(floating));
            }
        }
    }

    private static BigInteger randomInteger(Random random, int bits) {
        BigInteger magnitude = new BigInteger(bits, random);
        return random.nextBoolean() ? magnitude : magnitude.negate();
    }

    private static void assertDivision(BigInteger numerator, BigInteger denominator) {
        double actual = divide(numerator, denominator);
        assertSameDouble(oracle(numerator, denominator), actual, numerator + " / " + denominator);
        assertCorrectlyRounded(numerator, denominator, actual);
    }

    @Test
    void largePowerOfTwoDivisorsShiftWithoutLosingTheStickyBit() {
        BigInteger one = BigInteger.ONE;
        BigInteger[] numerators = {
            one, one.negate(), BigInteger.valueOf(3L), BigInteger.valueOf(Long.MAX_VALUE),
            BigInteger.valueOf(Long.MIN_VALUE), one.shiftLeft(53).add(one),
            one.shiftLeft(54).add(BigInteger.valueOf(3L)), one.shiftLeft(200).subtract(one),
            one.shiftLeft(1100).add(one), one.shiftLeft(1100).negate().subtract(one)
        };
        int[] shifts = {64, 65, 127, 128, 1000, 1074, 1075, 1076, 1120, 1200, 2000};
        for (BigInteger numerator : numerators) {
            for (int shift : shifts) {
                assertDivision(numerator, one.shiftLeft(shift));
                assertDivision(numerator, one.shiftLeft(shift).negate());
            }
        }
    }

    @Test
    void exactRationalOracleDecidesHalfwayAndCarryCases() {
        BigInteger one = BigInteger.ONE;
        // (2^53 + 1) / 2 lies exactly halfway between 2^52 and 2^52 + 1 scaled; ties go to even.
        assertDivision(one.shiftLeft(54).add(BigInteger.valueOf(2L)), BigInteger.valueOf(4L));
        assertDivision(one.shiftLeft(54).add(BigInteger.valueOf(6L)), BigInteger.valueOf(4L));
        // All 53 significand bits set plus a half unit carries into the next binade.
        BigInteger allOnes = one.shiftLeft(54).subtract(one);
        assertDivision(allOnes, BigInteger.valueOf(2L));
        assertDivision(allOnes.shiftLeft(1000), one.shiftLeft(1001));
        // Halfway below the least subnormal rounds to zero; just above rounds to it.
        assertDivision(one, one.shiftLeft(1075));
        assertDivision(one.shiftLeft(100).add(one), one.shiftLeft(1175));
        // The largest subnormal plus a half unit carries into the least normal.
        assertDivision(one.shiftLeft(53).subtract(one), one.shiftLeft(1075));
        // Double.MAX_VALUE plus a half unit is a tie that rounds to even, i.e. to infinity.
        BigInteger maxFinite = one.shiftLeft(53).subtract(one).shiftLeft(971);
        assertDivision(maxFinite.add(one.shiftLeft(970)), one);
        assertDivision(maxFinite.add(one.shiftLeft(970)).subtract(one), one);
        assertDivision(maxFinite.add(one.shiftLeft(970)).negate(), BigInteger.valueOf(3L).negate());
    }

    /*
     * Independent exact rational check: every finite binary64 and every midpoint between
     * neighbours is an integer multiple of 2^-1076, so the quotient magnitude q = n/d is compared
     * with the midpoints around the candidate exactly; a midpoint itself is admitted only for an
     * even candidate significand (ties to even), and infinity only from the overflow midpoint.
     */
    private static void assertCorrectlyRounded(
            BigInteger numerator, BigInteger denominator, double candidate) {
        String message = numerator + " / " + denominator + " -> " + Double.toHexString(candidate);
        if (numerator.signum() == 0) {
            assertEquals(0L, Double.doubleToRawLongBits(candidate), message);
            return;
        }
        boolean negative = numerator.signum() != denominator.signum();
        assertEquals(negative, Double.doubleToRawLongBits(candidate) < 0L, message);
        BigInteger scaledQuotientNumerator = numerator.abs().shiftLeft(EXACT_SCALE);
        BigInteger divisor = denominator.abs();
        double magnitude = Math.abs(candidate);
        long bits = Double.doubleToRawLongBits(magnitude);
        boolean even = (bits & 1L) == 0L;
        BigInteger lower = bits == 0L
                ? null
                : scaledExact(Double.longBitsToDouble(bits - 1L)).add(scaledExact(magnitude));
        BigInteger upper = Double.isInfinite(magnitude)
                ? null
                : scaledExact(magnitude).add(bits == Double.doubleToRawLongBits(Double.MAX_VALUE)
                        ? BigInteger.ONE.shiftLeft(1024 + EXACT_SCALE)
                        : scaledExact(Math.nextUp(magnitude)));
        // q is compared with the midpoint (a + b) / 2 as 2 * q * 2^EXACT_SCALE against (a + b).
        if (lower != null) {
            int order = scaledQuotientNumerator.shiftLeft(1).compareTo(lower.multiply(divisor));
            if (!(order > 0 || (order == 0 && even))) {
                throw new AssertionError(message + ": quotient lies below the lower midpoint");
            }
        }
        if (upper != null) {
            int order = scaledQuotientNumerator.shiftLeft(1).compareTo(upper.multiply(divisor));
            if (!(order < 0 || (order == 0 && even))) {
                throw new AssertionError(message + ": quotient lies above the upper midpoint");
            }
        }
    }

    private static final int EXACT_SCALE = 1076;

    /* The exact value of a finite non-negative binary64 times 2^EXACT_SCALE. */
    private static BigInteger scaledExact(double value) {
        long bits = Double.doubleToRawLongBits(value);
        int field = (int) (bits >>> 52);
        long fraction = bits & 0x000fffffffffffffL;
        long significand = field == 0 ? fraction : fraction | (1L << 52);
        int exponent = field == 0 ? -1074 : field - 1075;
        return BigInteger.valueOf(significand).shiftLeft(exponent + EXACT_SCALE);
    }

    private static double divide(long numerator, long denominator) {
        return ProtosBinary64Rounding.divideExactIntegers(
                new ProtosIntegerValue(numerator), new ProtosIntegerValue(denominator));
    }

    private static double divide(BigInteger numerator, BigInteger denominator) {
        return ProtosBinary64Rounding.divideExactIntegers(
                com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerWithPrototype(numerator, com.guillermomolina.protos.runtime.ProtosObjectValue.rootObject()), com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerWithPrototype(denominator, com.guillermomolina.protos.runtime.ProtosObjectValue.rootObject()));
    }

    /*
     * Truncates the exact quotient far below the binary64 rounding position (including the
     * subnormal one), then marks inexactness with an extra half unit there, which can never
     * create or break a tie; BigDecimal.doubleValue rounds that exact decimal once.
     */
    private static double oracle(BigInteger numerator, BigInteger denominator) {
        if (numerator.signum() == 0) {
            return 0.0d;
        }
        BigInteger dividend = numerator.abs();
        BigInteger divisor = denominator.abs();
        // The quotient is at least 2^(L - 1), so 60 - L fraction bits reach far below both the
        // normal rounding position and the subnormal one at 2^-1075.
        int scale = Math.max(0, 60 - (dividend.bitLength() - divisor.bitLength()));
        BigInteger[] quotientAndRemainder = dividend.shiftLeft(scale).divideAndRemainder(divisor);
        BigInteger truncated = quotientAndRemainder[0].shiftLeft(1);
        if (quotientAndRemainder[1].signum() != 0) {
            truncated = truncated.add(BigInteger.ONE);
        }
        int binaryScale = scale + 1;
        BigDecimal exact = new BigDecimal(
                truncated.multiply(BigInteger.valueOf(5L).pow(binaryScale)), binaryScale);
        double magnitude = exact.doubleValue();
        return numerator.signum() == denominator.signum() ? magnitude : -magnitude;
    }

    private static void assertSameDouble(double expected, double actual) {
        assertSameDouble(expected, actual, "quotient");
    }

    private static void assertSameDouble(double expected, double actual, String message) {
        assertEquals(
                Double.doubleToRawLongBits(expected),
                Double.doubleToRawLongBits(actual),
                () -> message + ": expected " + Double.toHexString(expected)
                        + " but was " + Double.toHexString(actual));
    }
}
