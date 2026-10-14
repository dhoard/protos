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

/**
 * The principal square root of the Standard Library function {@code std:math/Math.sqrt} (D198,
 * I090-C).
 *
 * <p>The argument is read only through its host representation; no guest message is sent. Every
 * finite value is handled as its exact rational value: an exact component as itself and a finite
 * Float component as its exact binary64 value, so nothing is rounded before the root is known.
 *
 * <ul>
 *   <li>A rational coordinate (F3) is found exactly with perfect-square tests and stays exact.
 *   <li>Float special values follow the D198 real-Float rules and the N4 table directly.
 *   <li>Every other Float coordinate is correctly rounded, ties to even, which is stricter than
 *       the 4 ULP of G2-B. A coordinate {@code c} is the nonnegative root of an exactly known
 *       quantity, so the sign of {@code c - t} for any binary64 midpoint {@code t} is decided
 *       with exact rational arithmetic (see {@link Coordinate}). A 64-bit approximation selects a
 *       candidate binary64, and exact midpoint comparisons move it to the correctly rounded
 *       neighbor, so the approximation affects only speed, never the result, and no
 *       intermediate step can overflow, underflow, or cancel.
 * </ul>
 *
 * <p>Only a positive Float argument uses {@link Math#sqrt}, which IEEE 754 specifies as
 * correctly rounded.
 */
public final class ProtosSquareRoot {
    /* 2^1024 - 2^970: halfway between the largest finite binary64 and 2^1024; ties overflow. */
    private static final Rational OVERFLOW_THRESHOLD =
            new Rational(
                    BigInteger.ONE.shiftLeft(1024).subtract(BigInteger.ONE.shiftLeft(970)),
                    BigInteger.ONE);

    /* Bits of the integer square root behind each approximation; well beyond binary64. */
    private static final int APPROXIMATION_BITS = 64;

    private ProtosSquareRoot() {}

    /**
     * The principal square root of a semantic Number under Complex canonical form, or
     * {@code null} when {@code value} is not a semantic Number. {@code prelude} owns any Fraction,
     * large Integer, or Complex result.
     */
    @TruffleBoundary
    public static Object sqrt(Object value, ProtosPrelude prelude) {
        if (value instanceof ProtosFloatValue floating) {
            return floatRoot(floating.value(), prelude);
        }
        if (ProtosNumericValueSupport.isCurrentInteger(value)
                || ProtosNumericValueSupport.isFraction(value)) {
            return exactRealRoot(Rational.ofExact(value), prelude);
        }
        if (ProtosComplexNumericSupport.isComplex(value)) {
            Object real = ProtosComplexNumericSupport.real(value);
            Object imaginary = ProtosComplexNumericSupport.imaginary(value);
            if (real instanceof ProtosFloatValue || imaginary instanceof ProtosFloatValue) {
                return floatBearingComplexRoot(real, imaginary, prelude);
            }
            return exactComplexRoot(Rational.ofExact(real), Rational.ofExact(imaginary), prelude);
        }
        return null;
    }

    private static Object floatRoot(double x, ProtosPrelude prelude) {
        // NaN, +Infinity and both zeros are their own roots, and so is Math.sqrt of them.
        if (!(x < 0.0d)) {
            return ProtosNumericValueSupport.floating(Math.sqrt(x));
        }
        return complex(0.0d, Math.sqrt(-x), prelude);
    }

    private static Object exactRealRoot(Rational x, ProtosPrelude prelude) {
        if (x.signum() >= 0) {
            return nonnegativeExactRoot(x, prelude);
        }
        return ProtosComplexNumericSupport.normalize(
                ProtosNumericValueSupport.integer(0L),
                nonnegativeExactRoot(x.negate(), prelude),
                prelude);
    }

    private static Object nonnegativeExactRoot(Rational x, ProtosPrelude prelude) {
        Rational root = x.exactSquareRootOrNull();
        if (root != null) {
            return root.toGuest(prelude);
        }
        return ProtosNumericValueSupport.floating(correctlyRounded(new RealRoot(x)));
    }

    /*
     * F3: with b nonzero, |z| > |a| and both coordinates are positive multiples of each other's
     * reciprocal (v = b / 2u), so either both are rational or neither is. They are rational
     * exactly when |z| and (|z| + a) / 2 are rational squares.
     */
    private static Object exactComplexRoot(Rational a, Rational b, ProtosPrelude prelude) {
        Rational modulus = a.square().add(b.square()).exactSquareRootOrNull();
        if (modulus != null) {
            Rational u = modulus.add(a).half().exactSquareRootOrNull();
            if (u != null) {
                Rational v = b.divide(u.add(u));
                return ProtosComplexNumericSupport.normalize(
                        u.toGuest(prelude), v.toGuest(prelude), prelude);
            }
        }
        return finiteComplexRoot(a, b, b.signum() < 0, prelude);
    }

    /* N4, rows in priority order. An exact component is always finite and never zero here. */
    private static Object floatBearingComplexRoot(
            Object real, Object imaginary, ProtosPrelude prelude) {
        double a = real instanceof ProtosFloatValue floating ? floating.value() : 0.0d;
        double b = imaginary instanceof ProtosFloatValue floating ? floating.value() : 0.0d;
        boolean imaginaryNegative =
                imaginary instanceof ProtosFloatValue
                        ? Double.doubleToRawLongBits(b) < 0L
                        : ProtosNumericValueSupport.isCurrentInteger(imaginary)
                                ? ProtosNumericValueSupport.integerSignum(imaginary) < 0
                                : Rational.ofExact(imaginary).signum() < 0;
        double imaginarySign = imaginaryNegative ? -1.0d : 1.0d;
        if (Double.isInfinite(b)) {
            return complex(Double.POSITIVE_INFINITY, b, prelude);
        }
        if (Double.isNaN(a)) {
            return complex(Double.NaN, Double.NaN, prelude);
        }
        if (a == Double.POSITIVE_INFINITY) {
            return complex(a, Double.isNaN(b) ? Double.NaN : Math.copySign(0.0d, imaginarySign),
                    prelude);
        }
        if (a == Double.NEGATIVE_INFINITY) {
            return Double.isNaN(b)
                    ? complex(Double.NaN, Double.POSITIVE_INFINITY, prelude)
                    : complex(0.0d, Math.copySign(Double.POSITIVE_INFINITY, imaginarySign),
                            prelude);
        }
        if (Double.isNaN(b)) {
            return complex(Double.NaN, Double.NaN, prelude);
        }
        return finiteComplexRoot(
                Rational.ofFinite(real), Rational.ofFinite(imaginary), imaginaryNegative, prelude);
    }

    /*
     * The finite principal root as two Floats: u = sqrt((|z| + a) / 2) >= 0 and
     * |v| = sqrt((|z| - a) / 2), signed as b. The approximations follow the stable form
     * big = sqrt((|z| + |a|) / 2), small = |b| / (2 big), which has no subtraction.
     */
    private static Object finiteComplexRoot(
            Rational a, Rational b, boolean imaginaryNegative, ProtosPrelude prelude) {
        Rational norm = a.square().add(b.square());
        double u = 0.0d;
        double v = 0.0d;
        if (norm.signum() != 0) {
            Rational modulus = norm.approximateSquareRoot();
            Rational big = modulus.add(a.abs()).half().approximateSquareRoot();
            Rational small = b.abs().divide(big.add(big));
            boolean realIsBig = a.signum() >= 0;
            u = correctlyRounded(new ComplexRoot(norm, a, realIsBig ? big : small));
            v = correctlyRounded(new ComplexRoot(norm, a.negate(), realIsBig ? small : big));
        }
        return complex(u, Math.copySign(v, imaginaryNegative ? -1.0d : 1.0d), prelude);
    }

    private static Object complex(double real, double imaginary, ProtosPrelude prelude) {
        return ProtosComplexNumericSupport.normalize(
                ProtosNumericValueSupport.floating(real),
                ProtosNumericValueSupport.floating(imaginary),
                prelude);
    }

    /**
     * A nonnegative real root {@code c} known exactly through {@link #compareTo}, with a close
     * approximation that only chooses the starting candidate.
     */
    private interface Coordinate {
        /** The sign of {@code c - t} for an exact {@code t >= 0}. */
        int compareTo(Rational t);

        Rational approximation();
    }

    /* c = sqrt(x) for x >= 0: c > t exactly when x > t^2. */
    private record RealRoot(Rational x) implements Coordinate {
        @Override
        public int compareTo(Rational t) {
            return x.compareTo(t.square());
        }

        @Override
        public Rational approximation() {
            return x.approximateSquareRoot();
        }
    }

    /*
     * c = sqrt((m + s) / 2) with m = sqrt(norm) and s = a for u or -a for |v|. For t >= 0,
     * c > t exactly when m > w = 2t^2 - s: always when w < 0, and otherwise when norm > w^2.
     */
    private record ComplexRoot(Rational norm, Rational shift, Rational approximation)
            implements Coordinate {
        @Override
        public int compareTo(Rational t) {
            Rational square = t.square();
            Rational w = square.add(square).subtract(shift);
            return w.signum() < 0 ? 1 : norm.compareTo(w.square());
        }
    }

    /*
     * The binary64 nearest to c, ties to even: starting from the rounded approximation, step to
     * a neighbor while c lies beyond the midpoint toward it, or on that midpoint when the
     * neighbor is the even one. The result is nonnegative; values from 2^1024 - 2^970 upward
     * overflow to +Infinity, and values below half the least subnormal round to +0.0.
     */
    private static double correctlyRounded(Coordinate c) {
        double candidate = c.approximation().toNonnegativeBinary64();
        while (true) {
            if (candidate == Double.POSITIVE_INFINITY) {
                if (c.compareTo(OVERFLOW_THRESHOLD) >= 0) {
                    return candidate;
                }
                candidate = Double.MAX_VALUE;
                continue;
            }
            if (candidate > 0.0d) {
                int below = c.compareTo(midpoint(Math.nextDown(candidate), candidate));
                if (below < 0 || (below == 0 && odd(candidate))) {
                    candidate = Math.nextDown(candidate);
                    continue;
                }
            }
            Rational upper =
                    candidate == Double.MAX_VALUE
                            ? OVERFLOW_THRESHOLD
                            : midpoint(candidate, Math.nextUp(candidate));
            int above = c.compareTo(upper);
            if (above > 0 || (above == 0 && odd(candidate))) {
                candidate = Math.nextUp(candidate);
                continue;
            }
            return candidate;
        }
    }

    private static Rational midpoint(double low, double high) {
        return Rational.ofDouble(low).add(Rational.ofDouble(high)).half();
    }

    /* The least significand bit of a nonnegative binary64; +Infinity is even. */
    private static boolean odd(double nonnegative) {
        return (Double.doubleToRawLongBits(nonnegative) & 1L) != 0L;
    }

    /**
     * A private exact rational, numerator over a positive denominator, not necessarily reduced.
     * It never escapes this class; guest values are produced only by {@link #toGuest}.
     */
    private record Rational(BigInteger numerator, BigInteger denominator) {
        static Rational ofExact(Object exact) {
            if (ProtosNumericValueSupport.isFraction(exact)) {
                return new Rational(
                        ProtosNumericValueSupport.exactBigInteger(
                                ProtosNumericValueSupport.fractionNumerator(exact)),
                        ProtosNumericValueSupport.exactBigInteger(
                                ProtosNumericValueSupport.fractionDenominator(exact)));
            }
            return new Rational(ProtosNumericValueSupport.exactBigInteger(exact), BigInteger.ONE);
        }

        /* An exact component or a finite Float as its exact value. */
        static Rational ofFinite(Object component) {
            return component instanceof ProtosFloatValue floating
                    ? ofDouble(floating.value())
                    : ofExact(component);
        }

        /* The exact value of a finite binary64; a zero of either sign is 0. */
        static Rational ofDouble(double value) {
            long bits = Double.doubleToRawLongBits(value);
            int exponent = (int) ((bits >>> 52) & 0x7FFL);
            long significand = bits & 0xFFFFFFFFFFFFFL;
            if (exponent == 0) {
                exponent = 1;
            } else {
                significand |= 1L << 52;
            }
            BigInteger magnitude = BigInteger.valueOf(bits < 0L ? -significand : significand);
            int scale = exponent - 1075;
            return scale >= 0
                    ? new Rational(magnitude.shiftLeft(scale), BigInteger.ONE)
                    : new Rational(magnitude, BigInteger.ONE.shiftLeft(-scale));
        }

        int signum() {
            return numerator.signum();
        }

        Rational negate() {
            return new Rational(numerator.negate(), denominator);
        }

        Rational abs() {
            return signum() < 0 ? negate() : this;
        }

        Rational add(Rational other) {
            return new Rational(
                    numerator.multiply(other.denominator).add(other.numerator.multiply(denominator)),
                    denominator.multiply(other.denominator));
        }

        Rational subtract(Rational other) {
            return add(other.negate());
        }

        Rational square() {
            return new Rational(numerator.multiply(numerator), denominator.multiply(denominator));
        }

        Rational half() {
            return new Rational(numerator, denominator.shiftLeft(1));
        }

        /* Requires a nonzero divisor. */
        Rational divide(Rational other) {
            BigInteger n = numerator.multiply(other.denominator);
            BigInteger d = denominator.multiply(other.numerator);
            return d.signum() < 0 ? new Rational(n.negate(), d.negate()) : new Rational(n, d);
        }

        int compareTo(Rational other) {
            return numerator.multiply(other.denominator)
                    .compareTo(other.numerator.multiply(denominator));
        }

        /* The exact square root of a nonnegative value when it is rational, otherwise null. */
        Rational exactSquareRootOrNull() {
            BigInteger divisor = numerator.gcd(denominator);
            BigInteger[] top = numerator.divide(divisor).sqrtAndRemainder();
            if (top[1].signum() != 0) {
                return null;
            }
            BigInteger[] bottom = denominator.divide(divisor).sqrtAndRemainder();
            return bottom[1].signum() == 0 ? new Rational(top[0], bottom[0]) : null;
        }

        /*
         * A dyadic approximation of the square root of a nonnegative value: the value is scaled
         * by 4^k so its integer part has about 2 * APPROXIMATION_BITS bits, whose integer square
         * root then carries APPROXIMATION_BITS bits, a relative error below 2^-63.
         */
        Rational approximateSquareRoot() {
            if (signum() == 0) {
                return this;
            }
            int magnitude = numerator.bitLength() - denominator.bitLength();
            int k = Math.floorDiv(2 * APPROXIMATION_BITS + 2 - magnitude, 2);
            if (k >= 0) {
                BigInteger root = numerator.shiftLeft(2 * k).divide(denominator).sqrt();
                return new Rational(root, BigInteger.ONE.shiftLeft(k));
            }
            BigInteger root = numerator.divide(denominator.shiftLeft(-2 * k)).sqrt();
            return new Rational(root.shiftLeft(-k), BigInteger.ONE);
        }

        /* The binary64 nearest to a nonnegative value, ties to even; zero is +0.0. */
        double toNonnegativeBinary64() {
            return ProtosBinary64Rounding.divideExactBigIntegers(numerator, denominator);
        }

        Object toGuest(ProtosPrelude prelude) {
            return ProtosNumericValueSupport.normalizeExactRational(
                    ProtosNumericValueSupport.integer(numerator, prelude),
                    ProtosNumericValueSupport.integer(denominator, prelude),
                    prelude);
        }
    }
}
