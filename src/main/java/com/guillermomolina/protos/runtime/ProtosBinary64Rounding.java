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

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import java.math.BigInteger;
import java.util.Objects;

/**
 * I091 / PLAT056 Candidate C: the single owner of every relation between exact Integers and
 * binary64 values: correctly rounded conversion and division, integral extraction, and exact
 * order. Signed-64 operands use primitive arithmetic only; a large Integer's exact payload is
 * read here, inside the exact numeric boundary, and never handed to clients.
 */
public final class ProtosBinary64Rounding {
    private static final long TWO_POW_53 = 1L << 53;
    private static final long SIGN_BIT = 0x8000000000000000L;
    private static final long POSITIVE_INFINITY_BITS = 0x7ff0000000000000L;
    /* Truncated quotients carry at least this many bits: 53 significand bits plus a round bit. */
    private static final int ROUNDING_BITS = 54;

    private ProtosBinary64Rounding() {}

    /** The binary64 value nearest to an exact Integer, ties to even. */
    public static double roundExactInteger(Object integer) {
        Objects.requireNonNull(integer, "integer");
        if (integer instanceof ProtosIntegerValue small) {
            return (double) small.longValue();
        }
        return roundLarge(ProtosNumericValueSupport.exactBigInteger(integer));
    }

    /*
     * BigInteger.doubleValue() is correctly rounded to nearest, ties to even, and overflows to
     * infinity; a large Integer is never zero, so no signed-zero case arises.
     */
    @TruffleBoundary
    private static double roundLarge(BigInteger value) {
        return value.doubleValue();
    }

    /**
     * The binary64 value nearest to the exact rational quotient, ties to even. Two signed-64
     * operands never reach arbitrary precision; only a large operand does.
     */
    public static double divideExactIntegers(Object numerator, Object denominator) {
        Objects.requireNonNull(numerator, "numerator");
        Objects.requireNonNull(denominator, "denominator");
        if (numerator instanceof ProtosIntegerValue smallNumerator
                && denominator instanceof ProtosIntegerValue smallDenominator) {
            long exactDenominator = smallDenominator.longValue();
            if (exactDenominator == 0L) {
                throw new ArithmeticException("division by zero");
            }
            return dividePrimitiveIntegers(smallNumerator.longValue(), exactDenominator);
        }
        return divideLarge(
                ProtosNumericValueSupport.exactBigInteger(numerator),
                ProtosNumericValueSupport.exactBigInteger(denominator));
    }

    /**
     * The correctly rounded quotient of two signed-64 Integers, using only primitive
     * arithmetic. Requires a nonzero denominator; an exact zero quotient is +0.0.
     *
     * <p>Magnitudes are unsigned, so {@code Long.MIN_VALUE} is the magnitude 2^63. The quotient
     * lies in [2^-63, 2^63], always a normal binary64 range. Operands within 2^53 use one IEEE
     * division and power-of-two divisors an exact scaling. Otherwise, when the integral quotient
     * is inexact and lacks {@link #ROUNDING_BITS} bits, restoring long division appends at most
     * 55 fraction bits; the final remainder is the sticky bit.
     */
    public static double dividePrimitiveIntegers(long numerator, long denominator) {
        if (numerator == 0L) {
            return 0.0d;
        }
        if (exactBinary64Integer(numerator) && exactBinary64Integer(denominator)) {
            // Both operands are exact binary64 values, so one IEEE division rounds the exact
            // quotient once.
            return (double) numerator / (double) denominator;
        }
        if (isPowerOfTwoMagnitude(denominator)) {
            // The dividend rounds once; scaling by 2^-k is exact because the quotient magnitude
            // is at least 2^-63, far inside the normal range.
            double scaled = Math.scalb(
                    (double) numerator, -Long.numberOfTrailingZeros(denominator));
            return denominator < 0L ? -scaled : scaled;
        }
        boolean negative = (numerator < 0L) != (denominator < 0L);
        long dividend = numerator < 0L ? -numerator : numerator;
        long divisor = denominator < 0L ? -denominator : denominator;

        long quotient = Long.divideUnsigned(dividend, divisor);
        long remainder = Long.remainderUnsigned(dividend, divisor);
        int scale = 0;
        if (remainder != 0L && unsignedBitLength(quotient) < ROUNDING_BITS) {
            if (quotient == 0L) {
                // Skip the leading zero fraction bits while keeping remainder < divisor.
                scale = Math.max(
                        0,
                        Long.numberOfLeadingZeros(remainder)
                                - Long.numberOfLeadingZeros(divisor) - 1);
                remainder <<= scale;
            }
            while (quotient < TWO_POW_53) {
                // remainder < divisor, so 2 * remainder - divisor fits even when 2 * remainder
                // overflows 64 bits.
                boolean carry = remainder < 0L;
                remainder <<= 1;
                quotient <<= 1;
                if (carry || Long.compareUnsigned(remainder, divisor) >= 0) {
                    remainder -= divisor;
                    quotient |= 1L;
                }
                scale++;
            }
        }
        long magnitudeBits = roundedMagnitudeBits(quotient, remainder != 0L, scale);
        return Double.longBitsToDouble(negative ? magnitudeBits | SIGN_BIT : magnitudeBits);
    }

    private static boolean exactBinary64Integer(long value) {
        return value >= -TWO_POW_53 && value <= TWO_POW_53;
    }

    /* Whether |value| is a power of two; Long.MIN_VALUE is the magnitude 2^63. */
    private static boolean isPowerOfTwoMagnitude(long value) {
        long magnitude = value < 0L ? -value : value;
        return magnitude != 0L && (magnitude & (magnitude - 1L)) == 0L;
    }

    /*
     * The scale makes the truncated quotient carry 54 or 55 bits: with L = bitLength(n) -
     * bitLength(d), n/d lies in [2^(L-1), 2^(L+1)), so n*2^(54-L)/d lies in [2^53, 2^55). A
     * positive L shifts the numerator right instead of the denominator left: floor(floor(n /
     * 2^t) / d) = floor(n / (2^t * d)), and the discarded numerator bits join the sticky bit.
     * A divisor of magnitude 2^k, signed-64 or large, needs no division: the truncated quotient
     * is a shift by k and the shifted-out bits are the sticky bit.
     */
    @TruffleBoundary
    private static double divideLarge(BigInteger numerator, BigInteger denominator) {
        Objects.requireNonNull(numerator, "numerator");
        Objects.requireNonNull(denominator, "denominator");
        if (denominator.signum() == 0) {
            throw new ArithmeticException("division by zero");
        }
        if (numerator.signum() == 0) {
            return 0.0d;
        }
        boolean negative = numerator.signum() != denominator.signum();
        BigInteger dividend = numerator.abs();
        BigInteger divisor = denominator.abs();

        long magnitudeBits;
        int roughExponent = dividend.bitLength() - divisor.bitLength();
        if (roughExponent > 1025) {
            // The quotient is at least 2^1025.
            magnitudeBits = POSITIVE_INFINITY_BITS;
        } else if (roughExponent < -1076) {
            // The quotient is below 2^-1075, half the least subnormal.
            magnitudeBits = 0L;
        } else {
            int scale = ROUNDING_BITS - roughExponent;
            boolean discardedBits = false;
            if (scale >= 0) {
                dividend = dividend.shiftLeft(scale);
            } else {
                discardedBits = dividend.getLowestSetBit() < -scale;
                dividend = dividend.shiftRight(-scale);
            }
            int divisorShift = divisor.getLowestSetBit();
            long quotient;
            boolean remainderBits;
            if (divisorShift == divisor.bitLength() - 1) {
                remainderBits = dividend.getLowestSetBit() < divisorShift;
                quotient = dividend.shiftRight(divisorShift).longValue();
            } else {
                BigInteger[] quotientAndRemainder = dividend.divideAndRemainder(divisor);
                quotient = quotientAndRemainder[0].longValue();
                remainderBits = quotientAndRemainder[1].signum() != 0;
            }
            magnitudeBits = roundedMagnitudeBits(
                    quotient, discardedBits || remainderBits, scale);
        }
        return Double.longBitsToDouble(negative ? magnitudeBits | SIGN_BIT : magnitudeBits);
    }

    /*
     * Rounds (quotient + f) * 2^-scale once to binary64, ties to even, where quotient is a
     * nonzero unsigned truncated magnitude and f in [0, 1) is nonzero exactly when inexact.
     * An inexact quotient must carry at least ROUNDING_BITS bits, so the round bit is always
     * present. Subnormal results keep fewer significand bits; their encoding is the significand
     * itself, and adding the biased exponent to a significand that carried to 2^53 (or to 2^52
     * for the largest subnormal) yields the next binade, including infinity.
     */
    private static long roundedMagnitudeBits(long quotient, boolean inexact, int scale) {
        int length = unsignedBitLength(quotient);
        int exponent = length - 1 - scale;
        if (exponent > 1023) {
            return POSITIVE_INFINITY_BITS;
        }
        int precision = Math.min(53, exponent + 1075);
        if (precision < 0) {
            return 0L;
        }
        int dropped = length - precision;
        long significand;
        if (dropped <= 0) {
            significand = quotient << -dropped;
        } else {
            significand = dropped == 64 ? 0L : quotient >>> dropped;
            boolean roundBit = ((quotient >>> (dropped - 1)) & 1L) != 0L;
            boolean stickyBit = inexact || (quotient & ((1L << (dropped - 1)) - 1L)) != 0L;
            if (roundBit && (stickyBit || (significand & 1L) != 0L)) {
                significand++;
            }
        }
        if (precision < 53) {
            return significand;
        }
        return ((long) (exponent + 1022) << 52) + significand;
    }

    private static int unsignedBitLength(long value) {
        return Long.SIZE - Long.numberOfLeadingZeros(value);
    }

    /**
     * The exact Integer denoted by an integral finite binary64 value, or {@code null} for any
     * other value. Values below 2^63 in magnitude convert as signed longs; only larger values,
     * which are always integral and outside the signed-64 range, need {@code prelude}.
     */
    public static Object integralBinary64(double value, ProtosPrelude prelude) {
        if (!Double.isFinite(value) || Math.rint(value) != value) {
            return null;
        }
        if (value >= -0x1p63 && value < 0x1p63) {
            return ProtosNumericValueSupport.integer((long) value);
        }
        return ProtosNumericValueSupport.integer(largeIntegralBinary64(value), prelude);
    }

    /**
     * The exact value of an integral finite binary64 as a host BigInteger, for the interop
     * projection of a Float.
     */
    static BigInteger integralBinary64BigInteger(double value) {
        if (value >= -0x1p63 && value < 0x1p63) {
            return BigInteger.valueOf((long) value);
        }
        return largeIntegralBinary64(value);
    }

    /*
     * A finite binary64 at or beyond 2^63 in magnitude is significand * 2^shift with shift >= 11,
     * so it is always integral and its exact value is the shifted significand.
     */
    @TruffleBoundary
    private static BigInteger largeIntegralBinary64(double value) {
        long rawBits = Double.doubleToRawLongBits(value);
        BigInteger magnitude = BigInteger.valueOf(binary64Significand(rawBits))
                .shiftLeft(binary64Shift(rawBits));
        return rawBits < 0L ? magnitude.negate() : magnitude;
    }

    /**
     * Exact order of a large Integer and a non-NaN binary64. A large Integer lies outside the
     * signed-64 range, so an infinity or a binary64 inside [-2^63, 2^63) is decided by signs
     * alone. Otherwise signs decide first, then bit lengths; only equal lengths compare the 53
     * leading magnitude bits with the significand and then the Integer's remaining low bits. The
     * binary64 is never materialized as an arbitrary-precision value.
     */
    @TruffleBoundary
    public static int compareLargeIntegerToBinary64(Object integer, double value) {
        if (Double.isNaN(value)) {
            throw new IllegalArgumentException("NaN has no order");
        }
        BigInteger exact = ProtosNumericValueSupport.exactBigInteger(integer);
        int sign = exact.signum();
        if (Double.isInfinite(value)) {
            return value > 0.0d ? -1 : 1;
        }
        if (value >= -0x1p63 && value < 0x1p63) {
            return sign;
        }
        if (sign != (value < 0.0d ? -1 : 1)) {
            return sign;
        }
        long rawBits = Double.doubleToRawLongBits(value);
        long significand = binary64Significand(rawBits);
        int shift = binary64Shift(rawBits);
        BigInteger magnitude = sign < 0 ? exact.negate() : exact;
        int order = Integer.compare(magnitude.bitLength(), 53 + shift);
        if (order == 0) {
            order = Long.compare(magnitude.shiftRight(shift).longValue(), significand);
            if (order == 0 && magnitude.getLowestSetBit() < shift) {
                order = 1;
            }
        }
        return sign * order;
    }

    /**
     * D197-C exact order of the rational {@code numerator / denominator} (semantic Integers,
     * positive denominator) against a non-NaN binary64, read as its exact represented binary
     * rational: negative, zero or positive as the rational is below, equal to or above it. The
     * rational is never rounded; normal, subnormal, signed-zero and infinite values are exact.
     */
    public static int compareRationalToBinary64(
            Object numerator, Object denominator, double value) {
        if (Double.isNaN(value)) {
            throw new IllegalArgumentException("NaN has no order");
        }
        if (ProtosNumericValueSupport.integerSignum(denominator) <= 0) {
            throw new IllegalArgumentException("denominator must be positive");
        }
        if (Double.isInfinite(value)) {
            return value > 0.0d ? -1 : 1;
        }
        if (value == 0.0d) {
            return ProtosNumericValueSupport.integerSignum(numerator);
        }

        if (numerator instanceof ProtosIntegerValue compactNumerator
                && denominator instanceof ProtosIntegerValue compactDenominator) {
            long rawBits = Double.doubleToRawLongBits(value);
            int exponentField = (int) ((rawBits >>> 52) & 0x7ffL);
            long significand = exponentField == 0
                    ? rawBits & 0x000fffffffffffffL
                    : binary64Significand(rawBits);
            int shift = exponentField == 0 ? -1074 : binary64Shift(rawBits);

            if (shift < 0) {
                int power = -shift;
                int cancelled =
                        Math.min(power, Long.numberOfTrailingZeros(significand));
                significand >>>= cancelled;
                power -= cancelled;

                if (power <= 62) {
                    long binaryNumerator =
                            value < 0.0d ? -significand : significand;
                    long binaryDenominator = 1L << power;

                    return ProtosNumericValueSupport.compareSignedProducts(
                            compactNumerator.longValue(), binaryDenominator,
                            binaryNumerator, compactDenominator.longValue());
                }
            } else if (shift < Long.numberOfLeadingZeros(significand)) {
                long integer = significand << shift;
                long binaryInteger = value < 0.0d ? -integer : integer;

                return ProtosNumericValueSupport.compareSignedProducts(
                        compactNumerator.longValue(), 1L,
                        binaryInteger, compactDenominator.longValue());
            }
        }

        return compareLargeRationalToBinary64(numerator, denominator, value);
    }

    /**
     * Rare arbitrary-magnitude or extreme-exponent fallback.
     * Kept outside the ordinary primitive comparison compilation path.
     */
    @TruffleBoundary
    private static int compareLargeRationalToBinary64(
            Object numerator, Object denominator, double value) {
        BigInteger n = ProtosNumericValueSupport.exactBigInteger(numerator);
        BigInteger d = ProtosNumericValueSupport.exactBigInteger(denominator);
        long rawBits = Double.doubleToRawLongBits(value);
        int exponentField = (int) ((rawBits >>> 52) & 0x7ffL);
        long significand = exponentField == 0
                ? rawBits & 0x000fffffffffffffL
                : binary64Significand(rawBits);
        int shift = exponentField == 0 ? -1074 : binary64Shift(rawBits);
        BigInteger represented =
                BigInteger.valueOf(value < 0.0d ? -significand : significand);

        BigInteger left = shift < 0 ? n.shiftLeft(-shift) : n;
        BigInteger right = represented.multiply(d);
        if (shift > 0) {
            right = right.shiftLeft(shift);
        }
        return left.compareTo(right);
    }

    /* The 53-bit significand of a normal binary64, hidden bit included. */
    private static long binary64Significand(long rawBits) {
        return (1L << 52) | (rawBits & 0x000fffffffffffffL);
    }

    /* The binary exponent of the least significand bit of a normal binary64. */
    private static int binary64Shift(long rawBits) {
        return (int) ((rawBits >>> 52) & 0x7ffL) - 1075;
    }
}
