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
 * Private kernel of standard Complex arithmetic (D197-C, D198, I090).
 *
 * <p>It is the single owner of the {@code Standard Complex arithmetic} results, used both by the
 * source-backed Complex and Fraction behaviors and by the native Integer and Float operator
 * bodies. Those native bodies cannot invoke guest behavior synchronously, so the result of
 * {@code real op Complex} must be computable here; keeping the Complex-receiver direction in the
 * same kernel prevents two diverging encodings of the separable, exceptional and signed-zero
 * rules. Nothing here sends a message to a guest value, and no Integer or Float operation that
 * never meets a Complex operand reaches this class.
 *
 * <p>Coupled finite Float results (rule {@code R}) cannot use host double arithmetic, which rounds
 * each product and sum, nor Smith's algorithm or fused multiply-add, which do not round the whole
 * coordinate once. Each finite binary64 input is therefore read as its exact dyadic value scaled
 * by 2^1074, the coordinate's exact numerator (and positive denominator) is formed in host
 * integers, and the quotient is rounded once by {@link ProtosBinary64Rounding}. Exact zeros take
 * their sign separately from the rectangular terms ({@code F1}).
 */
public final class ProtosComplexArithmetic {
    /** The standard binary arithmetic selectors. */
    public enum Operation {
        ADD,
        SUBTRACT,
        MULTIPLY,
        DIVIDE
    }

    /* Every finite binary64 value is an integer multiple of 2^-1074. */
    private static final int BINARY64_SCALE = 1074;
    private static final BigInteger PRODUCT_SCALE = BigInteger.ONE.shiftLeft(2 * BINARY64_SCALE);

    private ProtosComplexArithmetic() {}

    /**
     * The standard result of {@code left op right} when both are semantic Numbers and at least
     * one is a Complex, or {@code null} when that domain is not met. An all-exact Complex divided
     * by the exact Integer zero throws {@link ArithmeticException}; callers translate both cases
     * into the standard Error.
     */
    @TruffleBoundary
    public static Object binary(
            Operation operation, Object left, Object right, ProtosPrelude prelude) {
        boolean leftComplex = ProtosComplexNumericSupport.isComplex(left);
        boolean rightComplex = ProtosComplexNumericSupport.isComplex(right);
        if (!(leftComplex || rightComplex)
                || !ProtosNumericValueSupport.isCurrentNumber(left)
                || !ProtosNumericValueSupport.isCurrentNumber(right)) {
            return null;
        }
        if (leftComplex && rightComplex) {
            Object a = ProtosComplexNumericSupport.real(left);
            Object b = ProtosComplexNumericSupport.imaginary(left);
            Object c = ProtosComplexNumericSupport.real(right);
            Object d = ProtosComplexNumericSupport.imaginary(right);
            return switch (operation) {
                case ADD, SUBTRACT -> complex(
                        real(operation, a, c, prelude), real(operation, b, d, prelude), prelude);
                case MULTIPLY -> multiply(a, b, c, d, prelude);
                case DIVIDE -> divide(a, b, c, d, prelude);
            };
        }
        if (leftComplex) {
            Object a = ProtosComplexNumericSupport.real(left);
            Object b = ProtosComplexNumericSupport.imaginary(left);
            return switch (operation) {
                case ADD, SUBTRACT -> complex(real(operation, a, right, prelude), b, prelude);
                case MULTIPLY -> complex(
                        real(operation, a, right, prelude),
                        real(operation, b, right, prelude),
                        prelude);
                case DIVIDE -> divideByReal(a, b, right, prelude);
            };
        }
        Object a = ProtosComplexNumericSupport.real(right);
        Object b = ProtosComplexNumericSupport.imaginary(right);
        return switch (operation) {
            case ADD -> complex(real(operation, left, a, prelude), b, prelude);
            case SUBTRACT -> complex(
                    real(operation, left, a, prelude), negate(b, prelude), prelude);
            case MULTIPLY -> complex(
                    real(operation, left, a, prelude),
                    real(operation, left, b, prelude),
                    prelude);
            case DIVIDE -> divideReal(left, a, b, prelude);
        };
    }

    /** Standard negation of a genuine Complex, or {@code null} for any other value. */
    @TruffleBoundary
    public static Object negated(Object value, ProtosPrelude prelude) {
        if (!ProtosComplexNumericSupport.isComplex(value)) {
            return null;
        }
        return complex(
                negate(ProtosComplexNumericSupport.real(value), prelude),
                negate(ProtosComplexNumericSupport.imaginary(value), prelude),
                prelude);
    }

    private static Object complex(Object real, Object imaginary, ProtosPrelude prelude) {
        return ProtosComplexNumericSupport.normalize(real, imaginary, prelude);
    }

    /*
     * ---- Real coordinate arithmetic: the standard scalar rules of each operand pair. ----
     *
     * This restates the exact-rational and operand-first binary64 rules for one coordinate
     * instead of sending the component's own operator: the native Integer and Float callers
     * cannot call guest code, and the standard real behaviors live on frozen Core prototypes, so
     * no override can make a send observably differ. It is not a public arithmetic path; real
     * operands that never meet a Complex never reach it.
     */

    private static Object real(Operation operation, Object x, Object y, ProtosPrelude prelude) {
        if (isExact(x) && isExact(y)) {
            Rational left = Rational.of(x);
            Rational right = Rational.of(y);
            Rational result = switch (operation) {
                case ADD -> left.add(right);
                case SUBTRACT -> left.subtract(right);
                case MULTIPLY -> left.multiply(right);
                case DIVIDE -> left.divide(right);
            };
            return result.toValue(prelude);
        }
        // Operand-first rule: each exact operand is rounded once before the binary64 operation.
        double left = toBinary64(x);
        double right = toBinary64(y);
        return new ProtosFloatValue(switch (operation) {
            case ADD -> left + right;
            case SUBTRACT -> left - right;
            case MULTIPLY -> left * right;
            case DIVIDE -> left / right;
        });
    }

    private static Object negate(Object x, ProtosPrelude prelude) {
        if (x instanceof ProtosFloatValue floating) {
            return new ProtosFloatValue(-floating.value());
        }
        return Rational.of(x).negate().toValue(prelude);
    }

    private static boolean isExact(Object x) {
        return ProtosNumericValueSupport.isCurrentInteger(x) || ProtosNumericValueSupport.isFraction(x);
    }

    private static boolean isExactZero(Object x) {
        return ProtosNumericValueSupport.isCurrentInteger(x)
                && ProtosNumericValueSupport.integerSignum(x) == 0;
    }

    /* The standard Float(Integer) / Float(Fraction) conversion; a Float is itself. */
    private static double toBinary64(Object x) {
        if (x instanceof ProtosFloatValue floating) {
            return floating.value();
        }
        if (ProtosNumericValueSupport.isFraction(x)) {
            return ProtosNumericValueSupport.roundFractionToBinary64(x);
        }
        return ProtosBinary64Rounding.roundExactInteger(x);
    }

    /* ---- Separable division by a real divisor, with the exact-zero exception (E2/A). ---- */

    private static Object divideByReal(Object a, Object b, Object r, ProtosPrelude prelude) {
        if (isExactZero(r)) {
            if (isExact(a) && isExact(b)) {
                throw new ArithmeticException("division by zero");
            }
            // The whole division enters the Float domain: the divisor is +0.0 and each component
            // is converted before two independent IEEE divisions.
            return complex(
                    new ProtosFloatValue(toBinary64(a) / 0.0d),
                    new ProtosFloatValue(toBinary64(b) / 0.0d),
                    prelude);
        }
        return complex(
                real(Operation.DIVIDE, a, r, prelude),
                real(Operation.DIVIDE, b, r, prelude),
                prelude);
    }

    /* ---- Coupled multiplication C(a, b) * C(c, d). ---- */

    private static Object multiply(Object a, Object b, Object c, Object d, ProtosPrelude prelude) {
        if (isExact(a) && isExact(b) && isExact(c) && isExact(d)) {
            Rational ra = Rational.of(a);
            Rational rb = Rational.of(b);
            Rational rc = Rational.of(c);
            Rational rd = Rational.of(d);
            return complex(
                    ra.multiply(rc).subtract(rb.multiply(rd)).toValue(prelude),
                    ra.multiply(rd).add(rb.multiply(rc)).toValue(prelude),
                    prelude);
        }
        double x = toBinary64(a);
        double y = toBinary64(b);
        double u = toBinary64(c);
        double v = toBinary64(d);
        if (Double.isNaN(x) || Double.isNaN(y) || Double.isNaN(u) || Double.isNaN(v)) {
            return floatComplex(Double.NaN, Double.NaN, prelude);
        }
        if (Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(u) && Double.isFinite(v)) {
            return floatComplex(
                    finiteTerms(x, u, true, y, v, null),
                    finiteTerms(x, v, false, y, u, null),
                    prelude);
        }
        // Rule 3: an infinite component occurs in one term of each coordinate, so every
        // coordinate is NaN or an infinity; a finite term never decides it and stays 0.0 here,
        // which also prevents a spurious overflow of a finite product.
        return floatComplex(
                extendedTerm(x, u) - extendedTerm(y, v),
                extendedTerm(x, v) + extendedTerm(y, u),
                prelude);
    }

    private static double extendedTerm(double x, double y) {
        return Double.isInfinite(x) || Double.isInfinite(y) ? x * y : 0.0d;
    }

    /* ---- Coupled division C(a, b) / C(c, d), rules G1 1-6. ---- */

    private static Object divide(Object a, Object b, Object c, Object d, ProtosPrelude prelude) {
        if (isExact(a) && isExact(b) && isExact(c) && isExact(d)) {
            Rational ra = Rational.of(a);
            Rational rb = Rational.of(b);
            Rational rc = Rational.of(c);
            Rational rd = Rational.of(d);
            // An exact Complex divisor has a nonzero imaginary part, so the modulus is nonzero.
            Rational modulus = rc.multiply(rc).add(rd.multiply(rd));
            return complex(
                    ra.multiply(rc).add(rb.multiply(rd)).divide(modulus).toValue(prelude),
                    rb.multiply(rc).subtract(ra.multiply(rd)).divide(modulus).toValue(prelude),
                    prelude);
        }
        double x = toBinary64(a);
        double y = toBinary64(b);
        double u = toBinary64(c);
        double v = toBinary64(d);
        if (Double.isNaN(x) || Double.isNaN(y) || Double.isNaN(u) || Double.isNaN(v)) {
            return floatComplex(Double.NaN, Double.NaN, prelude);
        }
        boolean finiteDividend = Double.isFinite(x) && Double.isFinite(y);
        boolean finiteDivisor = Double.isFinite(u) && Double.isFinite(v);
        boolean zeroDivisor = u == 0.0d && v == 0.0d;
        if (finiteDividend && finiteDivisor && !zeroDivisor) {
            BigInteger modulus = squaredModulus(u, v);
            return floatComplex(
                    finiteTerms(x, u, false, y, v, modulus),
                    finiteTerms(y, u, true, x, v, modulus),
                    prelude);
        }
        if (zeroDivisor) {
            double k = Math.copySign(Double.POSITIVE_INFINITY, u);
            return floatComplex(k * x, k * y, prelude);
        }
        if (!finiteDividend && !finiteDivisor) {
            return floatComplex(Double.NaN, Double.NaN, prelude);
        }
        if (!finiteDividend) {
            double alpha = unitOrSignedZero(x);
            double beta = unitOrSignedZero(y);
            return floatComplex(
                    infiniteCoordinate(alpha * u + beta * v),
                    infiniteCoordinate(beta * u - alpha * v),
                    prelude);
        }
        double gamma = unitOrSignedZero(u);
        double delta = unitOrSignedZero(v);
        return floatComplex(
                zeroCoordinate(x * gamma + y * delta),
                zeroCoordinate(y * gamma - x * delta),
                prelude);
    }

    /* ---- Real divided by Complex r / C(c, d): the G1 order without imaginary dividend terms. ---- */

    private static Object divideReal(Object r, Object c, Object d, ProtosPrelude prelude) {
        if (isExact(r) && isExact(c) && isExact(d)) {
            Rational rr = Rational.of(r);
            Rational rc = Rational.of(c);
            Rational rd = Rational.of(d);
            Rational modulus = rc.multiply(rc).add(rd.multiply(rd));
            return complex(
                    rr.multiply(rc).divide(modulus).toValue(prelude),
                    rr.multiply(rd).negate().divide(modulus).toValue(prelude),
                    prelude);
        }
        double x = toBinary64(r);
        double u = toBinary64(c);
        double v = toBinary64(d);
        if (Double.isNaN(x) || Double.isNaN(u) || Double.isNaN(v)) {
            return floatComplex(Double.NaN, Double.NaN, prelude);
        }
        boolean finiteDivisor = Double.isFinite(u) && Double.isFinite(v);
        boolean zeroDivisor = u == 0.0d && v == 0.0d;
        if (Double.isFinite(x) && finiteDivisor && !zeroDivisor) {
            BigInteger modulus = squaredModulus(u, v);
            return floatComplex(
                    loneTerm(x, u, false, modulus), loneTerm(x, v, true, modulus), prelude);
        }
        if (zeroDivisor) {
            return floatComplex(
                    Math.copySign(Double.POSITIVE_INFINITY, u) * x, Double.NaN, prelude);
        }
        if (Double.isInfinite(x) && !finiteDivisor) {
            return floatComplex(Double.NaN, Double.NaN, prelude);
        }
        if (Double.isInfinite(x)) {
            double alpha = unitOrSignedZero(x);
            return floatComplex(
                    infiniteCoordinate(alpha * u), infiniteCoordinate(-(alpha * v)), prelude);
        }
        return floatComplex(
                zeroCoordinate(x * unitOrSignedZero(u)),
                zeroCoordinate(-(x * unitOrSignedZero(v))),
                prelude);
    }

    /*
     * The coefficient factors of G1 rules 5 and 6: an infinity's unit sign, otherwise the zero
     * with the component's sign. The coefficients are formed in binary64: products of a unit or
     * a signed zero are exact, a zero sum carries exactly the F1 sign under roundTiesToEven, and
     * a nonzero sum keeps its sign even when its magnitude overflows, which is all rules 5 and 6
     * observe.
     */
    private static double unitOrSignedZero(double component) {
        return Double.isInfinite(component)
                ? Math.copySign(1.0d, component)
                : Math.copySign(0.0d, component);
    }

    private static double infiniteCoordinate(double coefficient) {
        return coefficient == 0.0d
                ? Double.NaN
                : Math.copySign(Double.POSITIVE_INFINITY, coefficient);
    }

    private static double zeroCoordinate(double coefficient) {
        return Math.copySign(0.0d, coefficient);
    }

    /* ---- Finite results under R and F1. ---- */

    /*
     * The coordinate p*q + r*s (or p*q - r*s when subtract), divided by the positive modulus when
     * one is given (already scaled by 2^2148 like the products), rounded once to binary64.
     */
    private static double finiteTerms(
            double p, double q, boolean subtract, double r, double s, BigInteger modulus) {
        BigInteger first = scaled(p).multiply(scaled(q));
        BigInteger second = scaled(r).multiply(scaled(s));
        BigInteger numerator = subtract ? first.subtract(second) : first.add(second);
        if (numerator.signum() == 0) {
            boolean firstZero = p == 0.0d || q == 0.0d;
            boolean secondZero = r == 0.0d || s == 0.0d;
            if (firstZero && secondZero) {
                // Both terms are zero products: binary64 combines their exact signed zeros.
                return subtract ? p * q - r * s : p * q + r * s;
            }
            // Two nonzero terms that cancel exactly.
            return 0.0d;
        }
        return ProtosBinary64Rounding.divideExactBigIntegers(
                numerator, modulus == null ? PRODUCT_SCALE : modulus);
    }

    /* The lone coordinate term p*q (negated when requested) over the positive modulus. */
    private static double loneTerm(double p, double q, boolean negated, BigInteger modulus) {
        BigInteger numerator = scaled(p).multiply(scaled(q));
        if (numerator.signum() == 0) {
            double zero = p * q;
            return negated ? -zero : zero;
        }
        return ProtosBinary64Rounding.divideExactBigIntegers(
                negated ? numerator.negate() : numerator, modulus);
    }

    private static BigInteger squaredModulus(double c, double d) {
        BigInteger sc = scaled(c);
        BigInteger sd = scaled(d);
        return sc.multiply(sc).add(sd.multiply(sd));
    }

    /* The exact value of a finite binary64 times 2^1074, an integer. */
    private static BigInteger scaled(double value) {
        long bits = Double.doubleToRawLongBits(value);
        int exponent = (int) ((bits >>> 52) & 0x7ffL);
        long fraction = bits & ((1L << 52) - 1L);
        BigInteger magnitude = exponent == 0
                ? BigInteger.valueOf(fraction)
                : BigInteger.valueOf(fraction | (1L << 52)).shiftLeft(exponent - 1);
        return bits < 0L ? magnitude.negate() : magnitude;
    }

    private static Object floatComplex(double real, double imaginary, ProtosPrelude prelude) {
        return complex(new ProtosFloatValue(real), new ProtosFloatValue(imaginary), prelude);
    }

    /* A temporary exact rational with a positive denominator; normalized only on output. */
    private record Rational(BigInteger numerator, BigInteger denominator) {
        static Rational of(Object exact) {
            if (ProtosNumericValueSupport.isFraction(exact)) {
                return new Rational(
                        ProtosNumericValueSupport.exactBigInteger(
                                ProtosNumericValueSupport.fractionNumerator(exact)),
                        ProtosNumericValueSupport.exactBigInteger(
                                ProtosNumericValueSupport.fractionDenominator(exact)));
            }
            return new Rational(ProtosNumericValueSupport.exactBigInteger(exact), BigInteger.ONE);
        }

        Rational add(Rational other) {
            return new Rational(
                    numerator.multiply(other.denominator).add(other.numerator.multiply(denominator)),
                    denominator.multiply(other.denominator));
        }

        Rational subtract(Rational other) {
            return add(other.negate());
        }

        Rational multiply(Rational other) {
            return new Rational(
                    numerator.multiply(other.numerator), denominator.multiply(other.denominator));
        }

        Rational divide(Rational other) {
            if (other.numerator.signum() == 0) {
                throw new ArithmeticException("division by zero");
            }
            BigInteger n = numerator.multiply(other.denominator);
            BigInteger d = denominator.multiply(other.numerator);
            return d.signum() < 0 ? new Rational(n.negate(), d.negate()) : new Rational(n, d);
        }

        Rational negate() {
            return new Rational(numerator.negate(), denominator);
        }

        Object toValue(ProtosPrelude prelude) {
            return ProtosNumericValueSupport.normalizeExactRational(
                    ProtosNumericValueSupport.integer(numerator, prelude),
                    ProtosNumericValueSupport.integer(denominator, prelude),
                    prelude);
        }
    }
}
