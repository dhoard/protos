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
import com.guillermomolina.protos.runtime.ProtosComplexNumericSupport;
import com.guillermomolina.protos.runtime.ProtosFloatValue;
import com.guillermomolina.protos.runtime.ProtosIntegerValue;
import com.guillermomolina.protos.runtime.ProtosNumericValueSupport;
import com.guillermomolina.protos.runtime.ProtosPrelude;
import com.guillermomolina.protos.execution.ProtosStandardNumberOrderingProtocol.Comparison;

/**
 * Current Integer/Fraction/Float/Complex mathematical equality and normal hash, and real
 * ordering.
 *
 * <p>D197-C (I090): exact real values (Integer, Fraction) and finite binary64 values are
 * compared as exact mathematical rationals; a Fraction is never rounded to Float here. A Complex
 * (I090-C1) equals another Number exactly when both components are mathematically equal, a real
 * value having a zero imaginary part; its components are compared by the same real relation.
 * Ordering admits real values only; no Complex order is defined. D013 ordinary overridable dispatch remains owned by the protocols; this
 * trusted relation never sends a message to an operand.
 */
final class ProtosCurrentNumericRelations {
    private ProtosCurrentNumericRelations() {}

    static boolean numericEquals(Object left, Object right) {
        if (left instanceof ProtosIntegerValue leftInteger
                && right instanceof ProtosIntegerValue rightInteger) {
            return leftInteger.longValue() == rightInteger.longValue();
        }

        boolean leftComplex = ProtosComplexNumericSupport.isComplex(left);
        boolean rightComplex = ProtosComplexNumericSupport.isComplex(right);
        if (leftComplex || rightComplex) {
            return numericEquals(realPart(left), realPart(right))
                    && numericEquals(imaginaryPart(left), imaginaryPart(right));
        }

        boolean leftFraction = ProtosNumericValueSupport.isFraction(left);
        boolean rightFraction = ProtosNumericValueSupport.isFraction(right);

        if (leftFraction && rightFraction) {
            // D197-C canonical normalization makes component equality
            // equivalent to mathematical equality. In particular, do not
            // build arbitrary-precision cross-products merely to test ==.
            return ProtosNumericValueSupport.sameFraction(left, right);
        }
        if (leftFraction || rightFraction) {
            // Mixed Fraction/Integer/Float still requires exact ordering
            // against the other representation.
            return compare(left, right) == Comparison.EQUAL;
        }

        if (left instanceof ProtosFloatValue leftFloat) {
            if (right instanceof ProtosFloatValue rightFloat) {
                return leftFloat.value() == rightFloat.value();
            }
            return floatEqualsExactInteger(
                    leftFloat.value(),
                    ProtosNumericValueSupport.requireCurrentInteger(right));
        }

        if (right instanceof ProtosFloatValue rightFloat) {
            return floatEqualsExactInteger(
                    rightFloat.value(),
                    ProtosNumericValueSupport.requireCurrentInteger(left));
        }

        return ProtosNumericValueSupport.sameInteger(
                ProtosNumericValueSupport.requireCurrentInteger(left),
                ProtosNumericValueSupport.requireCurrentInteger(right));
    }

    /*
     * No guest Integer is materialized when ordinary real-number
     * equality and ordering initialize this class.
     */
    private static final class ComplexExactZero {
        static final Object VALUE = ProtosNumericValueSupport.integer(0L);
    }

    /* Components of a Number viewed as complex; a real value has an exact zero imaginary part. */
    private static Object realPart(Object number) {
        return ProtosComplexNumericSupport.isComplex(number)
                ? ProtosComplexNumericSupport.real(number)
                : number;
    }

    private static Object imaginaryPart(Object number) {
        return ProtosComplexNumericSupport.isComplex(number)
                ? ProtosComplexNumericSupport.imaginary(number)
                : ComplexExactZero.VALUE;
    }

    private static boolean floatEqualsExactInteger(double floating, Object integer) {
        return compareFloatToInteger(floating, integer) == Comparison.EQUAL;
    }

    static Comparison compare(Object left, Object right) {
        if (!ProtosNumericValueSupport.isCurrentRealNumber(left)
                || !ProtosNumericValueSupport.isCurrentRealNumber(right)) {
            throw new IllegalArgumentException("numeric comparison requires real Number values");
        }

        if (left instanceof ProtosIntegerValue leftInteger
                && right instanceof ProtosIntegerValue rightInteger) {
            return fromSign(Long.compare(leftInteger.longValue(), rightInteger.longValue()));
        }

        if (left instanceof ProtosFloatValue leftFloat) {
            if (right instanceof ProtosFloatValue rightFloat) {
                return compareFloats(leftFloat.value(), rightFloat.value());
            }
            if (ProtosNumericValueSupport.isFraction(right)) {
                return reverse(compareFractionToFloat(right, leftFloat.value()));
            }
            return compareFloatToInteger(
                    leftFloat.value(),
                    ProtosNumericValueSupport.requireCurrentInteger(right));
        }
        if (right instanceof ProtosFloatValue rightFloat) {
            if (ProtosNumericValueSupport.isFraction(left)) {
                return compareFractionToFloat(left, rightFloat.value());
            }
            return reverse(compareFloatToInteger(
                    rightFloat.value(),
                    ProtosNumericValueSupport.requireCurrentInteger(left)));
        }
        return fromSign(ProtosNumericValueSupport.compareExactRationals(left, right));
    }

    /* The Float is read as its exact represented binary rational; NaN stays unordered. */
    private static Comparison compareFractionToFloat(Object fraction, double floating) {
        if (Double.isNaN(floating)) {
            return Comparison.UNORDERED;
        }
        return fromSign(ProtosBinary64Rounding.compareRationalToBinary64(
                ProtosNumericValueSupport.fractionNumerator(fraction),
                ProtosNumericValueSupport.fractionDenominator(fraction),
                floating));
    }

    /* I091 primitive carriers share the exact algorithms used for represented values. */
    static Comparison compareLongs(long left, long right) {
        return fromSign(Long.compare(left, right));
    }

    static Comparison compareFloats(double left, double right) {
        if (Double.isNaN(left) || Double.isNaN(right)) {
            return Comparison.UNORDERED;
        }
        if (left < right) return Comparison.LESS;
        if (left > right) return Comparison.GREATER;
        return Comparison.EQUAL;
    }

    private static Comparison compareFloatToInteger(double floating, Object integer) {
        if (Double.isNaN(floating)) {
            return Comparison.UNORDERED;
        }
        if (integer instanceof ProtosIntegerValue small) {
            return compareFloatToLong(floating, small.longValue());
        }
        return reverse(fromSign(
                ProtosBinary64Rounding.compareLargeIntegerToBinary64(integer, floating)));
    }

    /*
     * Exact for every non-NaN binary64: outside [-2^63, 2^63) the Float lies beyond every long;
     * inside, truncation toward zero is exact and only the fractional part can still decide.
     */
    static Comparison compareFloatToLong(double floating, long integer) {
        if (Double.isNaN(floating)) {
            return Comparison.UNORDERED;
        }
        if (floating >= 0x1p63) {
            return Comparison.GREATER;
        }
        if (floating < -0x1p63) {
            return Comparison.LESS;
        }
        long truncated = (long) floating;
        if (truncated != integer) {
            return fromSign(Long.compare(truncated, integer));
        }
        return compareFloats(floating, (double) truncated);
    }

    private static Comparison fromSign(int sign) {
        if (sign < 0) return Comparison.LESS;
        if (sign > 0) return Comparison.GREATER;
        return Comparison.EQUAL;
    }

    static Comparison reverse(Comparison comparison) {
        return switch (comparison) {
            case LESS -> Comparison.GREATER;
            case GREATER -> Comparison.LESS;
            case EQUAL -> Comparison.EQUAL;
            case UNORDERED -> Comparison.UNORDERED;
        };
    }


    /*
     * A canonical Fraction is never integral. When it equals a finite binary64 exactly, it takes
     * that Float's non-integral normal hash; otherwise no Float or Integer equals it and the
     * deterministic hash of its canonical components suffices.
     */
    private static Object fractionNormalHash(Object fraction) {
        double nearest = ProtosNumericValueSupport.roundFractionToBinary64(fraction);
        if (Double.isFinite(nearest)
                && compareFractionToFloat(fraction, nearest) == Comparison.EQUAL) {
            return ProtosNumericValueSupport.integer(Double.hashCode(nearest));
        }
        return ProtosNumericValueSupport.integer(
                ProtosNumericValueSupport.fractionComponentHash(fraction));
    }

    /*
     * A Complex whose imaginary part is mathematically zero equals its real part, so it takes
     * that real normal hash. Otherwise the components' normal hashes, which already agree for
     * mathematically equal components of any family, are combined deterministically.
     */
    private static Object complexNormalHash(Object complex, ProtosPrelude prelude) {
        Object real = ProtosComplexNumericSupport.real(complex);
        Object imaginary = ProtosComplexNumericSupport.imaginary(complex);
        Object realHash = normalHash(real, prelude);
        if (numericEquals(imaginary, ComplexExactZero.VALUE)) {
            return realHash;
        }
        Object imaginaryHash = normalHash(imaginary, prelude);
        return ProtosNumericValueSupport.integer(
                31L * ProtosNumericValueSupport.integerHashCode(realHash)
                        + ProtosNumericValueSupport.integerHashCode(imaginaryHash));
    }

    /* Materialize the canonical NaN hash only when NaN is hashed. */
    private static final class NaNNormalHash {
        static final Object VALUE = ProtosNumericValueSupport.integer(2146959360L);
    }

    /** {@code prelude} owns a hash outside the signed-64 range. */
    static Object normalHash(Object value, ProtosPrelude prelude) {
        if (ProtosNumericValueSupport.isCurrentInteger(value)) {
            return value;
        }

        if (ProtosNumericValueSupport.isCurrentFloat(value)) {
            double number =
                    ProtosNumericValueSupport.currentFloatValue(value);

            if (Double.isNaN(number)) {
                return NaNNormalHash.VALUE;
            }

            Object integral =
                    ProtosBinary64Rounding.integralBinary64(number, prelude);

            return integral != null
                    ? integral
                    : ProtosNumericValueSupport.integer(
                            Double.hashCode(number == 0.0 ? 0.0 : number));
        }

        if (ProtosNumericValueSupport.isFraction(value)) {
            return fractionNormalHash(value);
        }

        if (ProtosComplexNumericSupport.isComplex(value)) {
            return complexNormalHash(value, prelude);
        }

        throw new IllegalArgumentException(
                "normal numeric hash requires a current Number value");
    }
}
