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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.guillermomolina.protos.runtime.ProtosActivation;
import com.guillermomolina.protos.runtime.ProtosComplexArithmetic;
import com.guillermomolina.protos.runtime.ProtosComplexArithmetic.Operation;
import com.guillermomolina.protos.runtime.ProtosComplexNumericSupport;
import com.guillermomolina.protos.runtime.ProtosFloatValue;
import com.guillermomolina.protos.runtime.ProtosIdentity;
import com.guillermomolina.protos.runtime.ProtosIntegerValue;
import com.guillermomolina.protos.runtime.ProtosLargeIntegerValue;
import com.guillermomolina.protos.runtime.ProtosNumericValueSupport;
import com.guillermomolina.protos.runtime.ProtosObjectValue;
import com.guillermomolina.protos.runtime.ProtosPrelude;
import com.guillermomolina.protos.runtime.ProtosSignalException;
import com.guillermomolina.protos.runtime.ProtosStringValue;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;
import java.nio.file.Path;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * D198 (I090): the public Complex factory and the standard arithmetic of the private Complex
 * kernel, including the native Integer and Float receiver directions. The source-backed
 * selectors and Complex/Fraction receiver directions are covered by the Protos conformance
 * tests under {@code protos/tests/conformance/number/}.
 *
 * <p>Finite coupled Float results are checked against an independent oracle: the exact
 * coordinate is formed with {@link BigDecimal} from the exact binary64 inputs, and the nearest
 * binary64 is chosen by comparing exact errors of neighboring candidates, ties to even. It shares
 * no code with the production rounding.
 */
final class ProtosComplexArithmeticTest {
    private static final double NAN = Double.NaN;
    private static final double INF = Double.POSITIVE_INFINITY;

    private static ProtosPrelude prelude;
    private static ProtosActivation caller;

    @BeforeAll
    static void bootstrap() throws IOException {
        prelude = new ProtosCoreBootstrap().bootstrap(Path.of("protos", "lib", "core"));
        caller = prelude.newModuleActivation();
    }

    // ---- Construction and components ----

    @Test
    void factoryPreservesComponentsAndCollapsesOnlyExactZero() {
        Object three = integer(3);
        Object half = fraction(1, 2);
        assertSame(three, construct(three, integer(0)));
        Object positiveZero = construct(three, flt(0.0));
        Object negativeZero = construct(three, flt(-0.0));
        assertComplexBits(positiveZero, three, 0.0);
        assertComplexBits(negativeZero, three, -0.0);
        assertFalse(ProtosIdentity.identical(positiveZero, negativeZero));
        Object exact = construct(three, half);
        assertSame(three, ProtosComplexNumericSupport.real(exact));
        assertSame(half, ProtosComplexNumericSupport.imaginary(exact));

        for (double special : new double[] {NAN, INF, -INF, -0.0}) {
            Object value = construct(flt(special), flt(special));
            assertTrue(ProtosComplexNumericSupport.isComplex(value));
        }
    }

    @Test
    void factoryRejectsInvalidReceiverArityAndComponents() {
        ProtosObjectValue complexPrototype = prelude.complexPrototype();
        Object one = integer(1);
        Object genuine = construct(one, one);
        ProtosObjectValue delegatingToNumber = new ProtosObjectValue(prelude.numberPrototype());
        ProtosObjectValue delegatingToInteger = new ProtosObjectValue(prelude.integerPrototype());
        for (Object invalid : List.of(
                genuine, delegatingToNumber, delegatingToInteger, new ProtosStringValue("1"))) {
            assertSignals(() -> invoke(complexPrototype, "call", invalid, one));
            assertSignals(() -> invoke(complexPrototype, "call", one, invalid));
        }
        assertSignals(() -> invoke(complexPrototype, "call"));
        assertSignals(() -> invoke(complexPrototype, "call", one));
        assertSignals(() -> invoke(complexPrototype, "call", one, one, one));
        ProtosObjectValue delegatingToComplex = new ProtosObjectValue(complexPrototype);
        assertSignals(() -> invoke(delegatingToComplex, "call", one, one));
    }

    // ---- Separable operations ----

    @Test
    void separableOperationsActPerCoordinateInBothDirections() {
        Object z = construct(integer(1), integer(2));
        Object w = construct(integer(3), integer(4));
        assertExact(add(z, w), integer(4), integer(6));
        assertExact(subtract(z, w), integer(-2), integer(-2));
        assertExact(add(z, integer(5)), integer(6), integer(2));
        assertExact(add(integer(5), z), integer(6), integer(2));
        assertExact(subtract(z, integer(5)), integer(-4), integer(2));
        assertExact(subtract(integer(5), z), integer(4), integer(-2));
        assertExact(multiply(z, integer(3)), integer(3), integer(6));
        assertExact(multiply(integer(3), z), integer(3), integer(6));
        assertExact(divide(z, integer(2)), fraction(1, 2), integer(1));
        assertExact(negate(z), integer(-1), integer(-2));

        // Cancelling imaginary parts collapse to the real family.
        assertExactComponent(integer(4), add(z, construct(integer(3), integer(-2))));

        // Exact coordinates stay exact when a Float participates only in the other coordinate.
        Object mixed = add(construct(flt(1.0), flt(-0.0)), integer(3));
        assertComplexBits(mixed, flt(4.0), -0.0);
        Object exactImaginary = add(construct(integer(1), fraction(1, 3)), flt(0.5));
        assertInstanceOf(ProtosFloatValue.class, ProtosComplexNumericSupport.real(exactImaginary));
        assertTrue(ProtosIdentity.identical(
                fraction(1, 3), ProtosComplexNumericSupport.imaginary(exactImaginary)));

        // NaN affects only the coordinate in which it participates.
        Object nanReal = add(construct(flt(NAN), integer(1)), construct(integer(2), integer(3)));
        assertTrue(Double.isNaN(floatOf(ProtosComplexNumericSupport.real(nanReal))));
        assertExactComponent(integer(4), ProtosComplexNumericSupport.imaginary(nanReal));

        assertComplexBits(multiply(construct(flt(1.0), flt(-0.0)), flt(1.0)), flt(1.0), -0.0);
        assertComplexBits(divide(construct(integer(1), integer(2)), flt(0.0)), flt(INF), INF);
        // r - C(a, b) negates b with real negation; a Float -0.0 becomes +0.0.
        assertComplexBits(subtract(integer(1), construct(flt(1.0), flt(-0.0))), flt(0.0), 0.0);
        assertComplexBits(negate(construct(flt(0.0), flt(-0.0))), flt(-0.0), 0.0);
    }

    @Test
    void exactArithmeticPromotesDemotesAndNormalizes() {
        BigInteger twoPow63 = BigInteger.ONE.shiftLeft(63);
        Object max = integer(Long.MAX_VALUE);
        Object z = construct(max, max);
        Object sum = add(z, integer(1));
        assertInstanceOf(ProtosLargeIntegerValue.class, ProtosComplexNumericSupport.real(sum));
        assertExactComponent(max, ProtosComplexNumericSupport.imaginary(sum));
        Object back = subtract(sum, integer(1));
        assertInstanceOf(ProtosIntegerValue.class, ProtosComplexNumericSupport.real(back));

        Object big = ProtosNumericValueSupport.integerFromHost(twoPow63, prelude);
        Object halves = divide(construct(big, integer(1)), integer(2));
        assertExactComponent(integer(1L << 62), ProtosComplexNumericSupport.real(halves));
        assertExactComponent(fraction(1, 2), ProtosComplexNumericSupport.imaginary(halves));
    }

    // ---- Exact coupled operations ----

    @Test
    void exactCoupledOperationsAreExact() {
        Object z = construct(integer(1), integer(2));
        Object w = construct(integer(3), integer(4));
        assertExact(multiply(z, w), integer(-5), integer(10));
        // (1+2i)/(3+4i) = (11 + 2i)/25
        assertExact(divide(z, w), fraction(11, 25), fraction(2, 25));
        // 1/(3+4i) = (3 - 4i)/25
        assertExact(divide(integer(1), w), fraction(3, 25), fraction(-4, 25));
        assertExact(divide(fraction(1, 2), w), fraction(3, 50), fraction(-2, 25));
        // (1+i)(1-i) = 2 collapses to the Integer.
        Object two = multiply(construct(integer(1), integer(1)), construct(integer(1), integer(-1)));
        assertExactComponent(integer(2), two);
        // (2+2i)/(1+i) = 2 collapses as well.
        assertExactComponent(
                integer(2), divide(construct(integer(2), integer(2)), construct(integer(1), integer(1))));
    }

    @Test
    void exactZeroDivisorFollowsTheExceptionalRule() {
        assertThrows(ArithmeticException.class, () -> ProtosComplexArithmetic.binary(
                Operation.DIVIDE, construct(integer(1), fraction(1, 2)), integer(0), prelude));
        assertComplexBits(divide(construct(flt(1.0), integer(2)), integer(0)), flt(INF), INF);
        assertComplexBits(divide(construct(flt(-1.0), integer(2)), integer(0)), flt(-INF), INF);
        assertComplexBits(divide(construct(flt(0.0), integer(2)), integer(0)), flt(NAN), INF);
        assertComplexBits(divide(construct(flt(NAN), integer(2)), integer(0)), flt(NAN), INF);
        assertComplexBits(divide(construct(integer(1), flt(-0.0)), integer(0)), flt(INF), NAN);
    }

    // ---- Float-domain coupled operations ----

    @Test
    void requiredSignedZeroAndExceptionalExamples() {
        assertComplexBits(
                multiply(construct(flt(1.0), flt(-0.0)), construct(flt(1.0), flt(0.0))),
                flt(1.0), 0.0);
        assertComplexBits(
                divide(construct(flt(1.0), flt(-0.0)), construct(flt(2.0), flt(0.0))),
                flt(0.5), -0.0);
        assertComplexBits(divide(flt(0.0), construct(flt(1.0), flt(1.0))), flt(0.0), -0.0);
        assertComplexBits(
                multiply(construct(flt(INF), flt(0.0)), construct(flt(0.0), flt(1.0))),
                flt(NAN), INF);
        assertComplexBits(
                multiply(construct(flt(NAN), flt(INF)), construct(flt(1.0), flt(1.0))),
                flt(NAN), NAN);
        assertComplexBits(
                divide(construct(integer(1), integer(2)), construct(flt(0.0), flt(0.0))),
                flt(INF), INF);
        assertComplexBits(
                divide(construct(integer(1), integer(2)), construct(flt(-0.0), flt(0.0))),
                flt(-INF), -INF);
        assertComplexBits(
                divide(construct(integer(0), integer(2)), construct(flt(0.0), flt(0.0))),
                flt(NAN), INF);
        assertComplexBits(
                divide(construct(integer(0), flt(0.0)), construct(flt(0.0), flt(0.0))),
                flt(NAN), NAN);
        assertComplexBits(
                divide(construct(flt(INF), flt(INF)), construct(flt(INF), flt(-INF))),
                flt(NAN), NAN);
        assertComplexBits(
                divide(construct(integer(1), integer(2)), construct(flt(INF), flt(-INF))),
                flt(-0.0), 0.0);
        assertComplexBits(divide(flt(1.0), construct(flt(0.0), flt(0.0))), flt(INF), NAN);
    }

    @Test
    void finiteCoupledResultsAreRoundedOnce() {
        Random random = new Random(0x1090L);
        for (int i = 0; i < 1500; i++) {
            double a = sample(random);
            double b = sample(random);
            double c = sample(random);
            double d = sample(random);
            checkFiniteMultiply(a, b, c, d);
            if (c != 0.0 || d != 0.0) {
                checkFiniteDivide(a, b, c, d);
                checkFiniteRealDivide(a, c, d);
            }
        }
        // Cancellation that separate binary64 rounding gets wrong.
        double p = 1.0 + 0x1p-30;
        double q = 1.0 + 0x1p-31;
        checkFiniteMultiply(p, q, p, -q);
        checkFiniteMultiply(p, q, -q, p);
        // Intermediate overflow and underflow that a naive formula turns into Inf, NaN or 0.
        checkFiniteMultiply(0x1p600, 0x1p600, 0x1p-600, -0x1p-600);
        checkFiniteDivide(0x1p1000, 0x1p1000, 0x1p1000, 0x1p1000);
        checkFiniteDivide(0x1p-1070, 0x1p-1070, 0x1p-1070, 0x1p-1072);
        checkFiniteDivide(Double.MAX_VALUE, Double.MAX_VALUE, Double.MIN_VALUE, 0.0);
        checkFiniteRealDivide(Double.MIN_VALUE, Double.MAX_VALUE, Double.MAX_VALUE);
        checkFiniteMultiply(Double.MIN_VALUE, 0.0, 0.5, 0.0);
    }

    @Test
    void coupledFloatDivisionIsExhaustiveOverClassesAndSigns() {
        double[] classes = {0.0, -0.0, 1.5, -3.0, INF, -INF, NAN};
        for (double a : classes) {
            for (double b : classes) {
                for (double c : classes) {
                    for (double d : classes) {
                        assertG1Divide(a, b, c, d);
                        assertG1Multiply(a, b, c, d);
                    }
                    assertG1RealDivide(a, b, c);
                }
            }
        }
    }

    @Test
    void nativeRealReceiversAndSourceDirectionsAgree() {
        Object z = construct(fraction(1, 3), flt(-0.0));
        for (String selector : List.of("+", "-", "*", "/")) {
            Operation operation = switch (selector) {
                case "+" -> Operation.ADD;
                case "-" -> Operation.SUBTRACT;
                case "*" -> Operation.MULTIPLY;
                default -> Operation.DIVIDE;
            };
            for (Object real : List.of(
                    integer(3),
                    ProtosNumericValueSupport.integerFromHost(BigInteger.TEN.pow(30), prelude),
                    flt(-2.5))) {
                Object expected = ProtosComplexArithmetic.binary(operation, real, z, prelude);
                assertTrue(ProtosIdentity.identical(expected, invoke(real, selector, z)));
            }
        }
        assertNull(ProtosComplexArithmetic.binary(
                Operation.ADD, z, new ProtosStringValue("x"), prelude));
        assertNull(ProtosComplexArithmetic.binary(Operation.ADD, integer(1), integer(2), prelude));
        assertSignals(() -> invoke(flt(1.0), "+", new ProtosObjectValue(prelude.complexPrototype())));
    }

    // ---- Finite oracle checks ----

    private static void checkFiniteMultiply(double a, double b, double c, double d) {
        Object result = multiply(construct(flt(a), flt(b)), construct(flt(c), flt(d)));
        BigDecimal re = exact(a).multiply(exact(c)).subtract(exact(b).multiply(exact(d)));
        BigDecimal im = exact(a).multiply(exact(d)).add(exact(b).multiply(exact(c)));
        String label = "C(" + a + "," + b + ")*C(" + c + "," + d + ")";
        if (re.signum() != 0) {
            assertBits(oracle(re, BigDecimal.ONE), realOf(result), label + " re");
        }
        if (im.signum() != 0) {
            assertBits(oracle(im, BigDecimal.ONE), imaginaryOf(result), label + " im");
        }
    }

    private static void checkFiniteDivide(double a, double b, double c, double d) {
        Object result = divide(construct(flt(a), flt(b)), construct(flt(c), flt(d)));
        BigDecimal modulus = exact(c).multiply(exact(c)).add(exact(d).multiply(exact(d)));
        BigDecimal re = exact(a).multiply(exact(c)).add(exact(b).multiply(exact(d)));
        BigDecimal im = exact(b).multiply(exact(c)).subtract(exact(a).multiply(exact(d)));
        String label = "C(" + a + "," + b + ")/C(" + c + "," + d + ")";
        if (re.signum() != 0) {
            assertBits(oracle(re, modulus), realOf(result), label + " re");
        }
        if (im.signum() != 0) {
            assertBits(oracle(im, modulus), imaginaryOf(result), label + " im");
        }
    }

    private static void checkFiniteRealDivide(double r, double c, double d) {
        Object result = divide(flt(r), construct(flt(c), flt(d)));
        BigDecimal modulus = exact(c).multiply(exact(c)).add(exact(d).multiply(exact(d)));
        BigDecimal re = exact(r).multiply(exact(c));
        BigDecimal im = exact(r).multiply(exact(d)).negate();
        String label = r + "/C(" + c + "," + d + ")";
        assertBits(re.signum() == 0 ? zeroXor(r, c, false) : oracle(re, modulus),
                realOf(result), label + " re");
        assertBits(im.signum() == 0 ? zeroXor(r, d, true) : oracle(im, modulus),
                imaginaryOf(result), label + " im");
    }

    /* The binary64 nearest to n/d (n nonzero, d positive), ties to even, by exact comparison. */
    private static double oracle(BigDecimal numerator, BigDecimal denominator) {
        BigDecimal quotient = numerator.abs();
        BigDecimal threshold = new BigDecimal(Double.MAX_VALUE)
                .add(new BigDecimal(Math.ulp(Double.MAX_VALUE)).divide(BigDecimal.valueOf(2)));
        double magnitude;
        if (quotient.compareTo(threshold.multiply(denominator)) >= 0) {
            magnitude = INF;
        } else {
            double approximate = Math.min(
                    quotient.divide(denominator, new MathContext(40)).doubleValue(),
                    Double.MAX_VALUE);
            magnitude = approximate;
            BigDecimal bestError = null;
            for (double candidate : new double[] {
                    Math.nextDown(approximate), approximate, Math.nextUp(approximate)}) {
                if (candidate < 0.0 || Double.isInfinite(candidate)) {
                    continue;
                }
                BigDecimal error = quotient.subtract(exact(candidate).multiply(denominator)).abs();
                int order = bestError == null ? -1 : error.compareTo(bestError);
                if (order < 0 || (order == 0
                        && (Double.doubleToRawLongBits(candidate) & 1L) == 0L)) {
                    bestError = error;
                    magnitude = candidate;
                }
            }
        }
        return numerator.signum() < 0 ? -magnitude : magnitude;
    }

    private static double zeroXor(double x, double y, boolean negated) {
        boolean negative = (Math.copySign(1.0, x) < 0) != (Math.copySign(1.0, y) < 0);
        return (negative != negated) ? -0.0 : 0.0;
    }

    private static double sample(Random random) {
        return switch (random.nextInt(8)) {
            case 0 -> 0.0;
            case 1 -> Double.longBitsToDouble(random.nextLong() & 0x000fffffffffffffL)
                    * (random.nextBoolean() ? 1 : -1); // subnormal
            case 2 -> Math.scalb(random.nextDouble() + 1.0, 1000 + random.nextInt(23))
                    * (random.nextBoolean() ? 1 : -1); // near overflow
            case 3 -> Math.scalb(random.nextDouble() + 1.0, -1060 + random.nextInt(30))
                    * (random.nextBoolean() ? 1 : -1); // near underflow
            default -> Math.scalb(random.nextDouble() - 0.5, random.nextInt(80) - 40);
        };
    }

    // ---- Systematic G1 oracle, written from the rule text ----

    private static void assertG1Multiply(double a, double b, double c, double d) {
        Object result = multiply(construct(flt(a), flt(b)), construct(flt(c), flt(d)));
        String label = "C(" + a + "," + b + ")*C(" + c + "," + d + ")";
        if (anyNaN(a, b, c, d)) {
            assertBits(NAN, realOf(result), label);
            assertBits(NAN, imaginaryOf(result), label);
        } else if (allFinite(a, b, c, d)) {
            checkFiniteMultiply(a, b, c, d);
            assertZeroSign(sum(product(a, c), negateTerm(product(b, d))), realOf(result), label);
            assertZeroSign(sum(product(a, d), product(b, c)), imaginaryOf(result), label);
        } else {
            assertBits(extendedSum(extended(a, c), -extended(b, d)), realOf(result), label);
            assertBits(extendedSum(extended(a, d), extended(b, c)), imaginaryOf(result), label);
        }
    }

    private static void assertG1Divide(double a, double b, double c, double d) {
        Object result = divide(construct(flt(a), flt(b)), construct(flt(c), flt(d)));
        String label = "C(" + a + "," + b + ")/C(" + c + "," + d + ")";
        boolean zeroDivisor = c == 0.0 && d == 0.0;
        double expectedRe;
        double expectedIm;
        if (anyNaN(a, b, c, d)) {
            expectedRe = NAN;
            expectedIm = NAN;
        } else if (allFinite(a, b, c, d) && !zeroDivisor) {
            checkFiniteDivide(a, b, c, d);
            assertZeroSign(sum(product(a, c), product(b, d)), realOf(result), label);
            assertZeroSign(sum(product(b, c), negateTerm(product(a, d))), imaginaryOf(result), label);
            return;
        } else if (zeroDivisor) {
            double k = Math.copySign(INF, c);
            expectedRe = k * a;
            expectedIm = k * b;
        } else if (!allFinite(a, b) && !allFinite(c, d)) {
            expectedRe = NAN;
            expectedIm = NAN;
        } else if (!allFinite(a, b)) {
            double alpha = coefficient(a);
            double beta = coefficient(b);
            expectedRe = infinityFrom(combine(alpha, c, beta, d, false));
            expectedIm = infinityFrom(combine(beta, c, alpha, d, true));
        } else {
            double gamma = coefficient(c);
            double delta = coefficient(d);
            expectedRe = zeroFrom(combine(gamma, a, delta, b, false));
            expectedIm = zeroFrom(combine(gamma, b, delta, a, true));
        }
        assertBits(expectedRe, realOf(result), label);
        assertBits(expectedIm, imaginaryOf(result), label);
    }

    private static void assertG1RealDivide(double r, double c, double d) {
        Object result = divide(flt(r), construct(flt(c), flt(d)));
        String label = r + "/C(" + c + "," + d + ")";
        boolean zeroDivisor = c == 0.0 && d == 0.0;
        double expectedRe;
        double expectedIm;
        if (anyNaN(r, c, d)) {
            expectedRe = NAN;
            expectedIm = NAN;
        } else if (allFinite(r, c, d) && !zeroDivisor) {
            checkFiniteRealDivide(r, c, d);
            return;
        } else if (zeroDivisor) {
            expectedRe = Math.copySign(INF, c) * r;
            expectedIm = NAN;
        } else if (Double.isInfinite(r) && !allFinite(c, d)) {
            expectedRe = NAN;
            expectedIm = NAN;
        } else if (Double.isInfinite(r)) {
            double alpha = coefficient(r);
            expectedRe = infinityFrom(lone(alpha, c, false));
            expectedIm = infinityFrom(lone(alpha, d, true));
        } else {
            expectedRe = zeroFrom(lone(r, coefficient(c), false));
            expectedIm = zeroFrom(lone(r, coefficient(d), true));
        }
        assertBits(expectedRe, realOf(result), label);
        assertBits(expectedIm, imaginaryOf(result), label);
    }

    /* A G1 coefficient factor: an infinity's unit of its sign, otherwise the zero of its sign. */
    private static double coefficient(double value) {
        return Double.isInfinite(value) ? Math.copySign(1.0, value) : Math.copySign(0.0, value);
    }

    /* An exact coefficient: its value, and for an exact zero the F1 zero sign (+1 or -1). */
    private record Coefficient(BigDecimal value, int zeroSign) {}

    private static Coefficient lone(double factor, double x, boolean negated) {
        int sign = (Math.copySign(1.0, factor) < 0) != (Math.copySign(1.0, x) < 0) ? -1 : 1;
        BigDecimal value = exact(factor).multiply(exact(x));
        return negated ? new Coefficient(value.negate(), -sign) : new Coefficient(value, sign);
    }

    /* p*x + q*y (or p*x - q*y) evaluated exactly, with the F1 zero-term signs. */
    private static Coefficient combine(double p, double x, double q, double y, boolean subtract) {
        Coefficient first = lone(p, x, false);
        Coefficient second = lone(q, y, subtract);
        BigDecimal value = first.value().add(second.value());
        if (value.signum() != 0) {
            return new Coefficient(value, 0);
        }
        boolean bothZeroTerms = first.value().signum() == 0 && second.value().signum() == 0;
        int zeroSign = bothZeroTerms && first.zeroSign() < 0 && second.zeroSign() < 0 ? -1 : 1;
        return new Coefficient(value, zeroSign);
    }

    private static double infinityFrom(Coefficient coefficient) {
        return coefficient.value().signum() == 0
                ? NAN
                : Math.copySign(INF, coefficient.value().signum());
    }

    private static double zeroFrom(Coefficient coefficient) {
        int sign = coefficient.value().signum();
        return Math.copySign(0.0, sign != 0 ? sign : coefficient.zeroSign());
    }

    /* A term as {mathematical sign (0 for zero), zero sign}; only signs matter to rules 5-6. */
    private static int[] product(double x, double y) {
        int sign = (Math.copySign(1.0, x) < 0) != (Math.copySign(1.0, y) < 0) ? -1 : 1;
        if (x == 0.0 || y == 0.0) {
            return new int[] {0, sign};
        }
        return new int[] {sign, 0};
    }

    private static int[] negateTerm(int[] term) {
        return new int[] {-term[0], -term[1]};
    }

    /*
     * Sign of a sum of two signed terms when it is determined by signs alone; {2, 0} marks a sum
     * of two opposite nonzero terms whose sign the zero-sign check does not need.
     */
    private static int[] sum(int[] first, int[] second) {
        if (first[0] == 0 && second[0] == 0) {
            return new int[] {0, first[1] < 0 && second[1] < 0 ? -1 : 1};
        }
        if (first[0] == 0) {
            return second;
        }
        if (second[0] == 0 || first[0] == second[0]) {
            return first;
        }
        return new int[] {2, 1};
    }

    /* Only an exact-zero coordinate's sign is fixed by F1; {2, _} sums are cancellations. */
    private static void assertZeroSign(int[] term, double actual, String label) {
        if (term[0] == 0) {
            assertBits(Math.copySign(0.0, term[1]), actual, label + " F1 zero");
        } else if (term[0] == 2 && actual == 0.0) {
            assertBits(0.0, actual, label + " cancellation");
        }
    }

    private static double extended(double x, double y) {
        if (Double.isInfinite(x) || Double.isInfinite(y)) {
            return (x == 0.0 || y == 0.0)
                    ? NAN
                    : Math.copySign(INF, Math.copySign(1.0, x) * Math.copySign(1.0, y));
        }
        return 0.0; // finite term: never decides a rule-3 coordinate
    }

    private static double extendedSum(double first, double second) {
        if (Double.isNaN(first) || Double.isNaN(second)) {
            return NAN;
        }
        if (Double.isInfinite(first) && Double.isInfinite(second)) {
            return first == second ? first : NAN;
        }
        return Double.isInfinite(first) ? first : second;
    }

    private static boolean anyNaN(double... values) {
        for (double value : values) {
            if (Double.isNaN(value)) {
                return true;
            }
        }
        return false;
    }

    private static boolean allFinite(double... values) {
        for (double value : values) {
            if (!Double.isFinite(value)) {
                return false;
            }
        }
        return true;
    }

    // ---- Helpers ----

    private static Object add(Object left, Object right) {
        return binary(Operation.ADD, left, right);
    }

    private static Object subtract(Object left, Object right) {
        return binary(Operation.SUBTRACT, left, right);
    }

    private static Object multiply(Object left, Object right) {
        return binary(Operation.MULTIPLY, left, right);
    }

    private static Object divide(Object left, Object right) {
        return binary(Operation.DIVIDE, left, right);
    }

    private static Object binary(Operation operation, Object left, Object right) {
        Object result = ProtosComplexArithmetic.binary(operation, left, right, prelude);
        assertTrue(result != null, "operation outside the Complex domain");
        return result;
    }

    private static Object negate(Object value) {
        return ProtosComplexArithmetic.negated(value, prelude);
    }

    private static Object construct(Object real, Object imaginary) {
        return invoke(prelude.complexPrototype(), "call", real, imaginary);
    }

    private static Object invoke(Object receiver, String selector, Object... arguments) {
        return ProtosInvocation.invokeMessage(receiver, selector, List.of(arguments), caller);
    }

    private static void assertSignals(Runnable action) {
        assertThrows(ProtosSignalException.class, action::run);
    }

    private static Object integer(long value) {
        return new ProtosIntegerValue(value);
    }

    private static Object fraction(long numerator, long denominator) {
        return ProtosNumericValueSupport.divideIntegersExactly(
                integer(numerator), integer(denominator), prelude);
    }

    private static ProtosFloatValue flt(double value) {
        return new ProtosFloatValue(value);
    }

    private static BigDecimal exact(double value) {
        return new BigDecimal(value);
    }

    private static double floatOf(Object value) {
        return assertInstanceOf(ProtosFloatValue.class, value).value();
    }

    private static double realOf(Object complex) {
        return floatOf(ProtosComplexNumericSupport.real(complex));
    }

    private static double imaginaryOf(Object complex) {
        return floatOf(ProtosComplexNumericSupport.imaginary(complex));
    }

    private static void assertBits(double expected, double actual, String label) {
        if (Double.isNaN(expected)) {
            assertTrue(Double.isNaN(actual), label + ": expected NaN, got " + actual);
            return;
        }
        assertEquals(Double.doubleToRawLongBits(expected), Double.doubleToRawLongBits(actual),
                label + ": expected " + expected + ", got " + actual);
    }

    private static void assertExactComponent(Object expected, Object actual) {
        assertTrue(ProtosIdentity.identical(expected, actual),
                () -> "expected component identical to the exact value");
    }

    private static void assertExact(Object value, Object real, Object imaginary) {
        assertTrue(ProtosComplexNumericSupport.isComplex(value));
        assertExactComponent(real, ProtosComplexNumericSupport.real(value));
        assertExactComponent(imaginary, ProtosComplexNumericSupport.imaginary(value));
    }

    private static void assertComplexBits(Object value, Object real, double imaginary) {
        assertTrue(ProtosComplexNumericSupport.isComplex(value));
        Object actualReal = ProtosComplexNumericSupport.real(value);
        if (real instanceof ProtosFloatValue floating) {
            assertBits(floating.value(), floatOf(actualReal), "real");
        } else {
            assertExactComponent(real, actualReal);
        }
        assertBits(imaginary, imaginaryOf(value), "imaginary");
    }
}
