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

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.guillermomolina.protos.runtime.ProtosActivation;
import com.guillermomolina.protos.runtime.ProtosComplexNumericSupport;
import com.guillermomolina.protos.runtime.ProtosFloatValue;
import com.guillermomolina.protos.runtime.ProtosIntegerValue;
import com.guillermomolina.protos.runtime.ProtosNumericValueSupport;
import com.guillermomolina.protos.runtime.ProtosObjectValue;
import com.guillermomolina.protos.runtime.ProtosPrelude;
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
 * G2-B (D198, I090-C): every inexact Float coordinate of {@code std:math/Math.sqrt} lies within
 * 4 ULP of the oracle, a zero oracle is matched exactly with its sign, and an infinite oracle is
 * matched exactly.
 *
 * <p>The oracle is independent of the production algorithm. Each argument is rebuilt from its
 * exact value (Integer and Fraction components as {@link BigInteger}s, Float components from
 * their binary64 bits), and the root is evaluated with {@link BigDecimal} at
 * {@value #ORACLE_DIGITS} significant digits through forms without cancellation:
 * {@code big = sqrt((|z| + |a|) / 2)} and {@code small = sqrt(b^2 / (2 (|z| + |a|)))}. The
 * nearest binary64 is then chosen by {@link BigDecimal#doubleValue}, which rounds to nearest,
 * ties to even.
 */
final class ProtosMathSqrtAccuracyTest {
    private static final int ORACLE_DIGITS = 120;
    private static final MathContext ORACLE = new MathContext(ORACLE_DIGITS);
    private static final long MAX_ULP = 4L;

    private static ProtosPrelude prelude;
    private static ProtosActivation caller;
    private static ProtosObjectValue math;

    @BeforeAll
    static void bootstrap() throws IOException {
        prelude = new ProtosCoreBootstrap().bootstrap(
                Path.of("protos", "lib", "core"),
                new ProtosStandardLibraryModuleResolver(Path.of("protos", "lib")));
        caller = prelude.newModuleActivation();
        math = assertInstanceOf(ProtosObjectValue.class,
                ProtosTestExecutionSupport.evaluate("import(\"std:math/Math\")", caller));
    }

    @Test
    void irrationalExactReals() {
        checkReal(integer(2));
        checkReal(integer(3));
        checkReal(integer(Long.MAX_VALUE));
        checkReal(large(BigInteger.TWO.pow(1200).add(BigInteger.ONE)));
        checkReal(large(BigInteger.TEN.pow(400).add(BigInteger.valueOf(7))));
        // Beyond the binary64 range: the root itself overflows.
        checkReal(large(BigInteger.TWO.pow(2048).add(BigInteger.ONE)));
        checkReal(large(BigInteger.TWO.pow(2047).multiply(BigInteger.valueOf(3))));
        checkReal(fraction(BigInteger.TWO, BigInteger.valueOf(3)));
        checkReal(fraction(BigInteger.TEN.pow(500).add(BigInteger.ONE), BigInteger.valueOf(3)));
        // Subnormal and below-subnormal roots of tiny exact values.
        checkReal(fraction(BigInteger.ONE, BigInteger.TWO.pow(2100).multiply(BigInteger.valueOf(3))));
        checkReal(fraction(BigInteger.ONE, BigInteger.TWO.pow(2200).multiply(BigInteger.valueOf(3))));
        checkReal(integer(-2));
        checkReal(fraction(BigInteger.valueOf(-5), BigInteger.valueOf(7)));
    }

    @Test
    void floatReals() {
        for (double x : new double[] {
                2.0, 0.5, Double.MIN_VALUE, Double.MIN_NORMAL, Double.MAX_VALUE,
                Math.nextDown(Double.MIN_NORMAL), -2.0, -Double.MIN_VALUE, -Double.MAX_VALUE}) {
            checkReal(new ProtosFloatValue(x));
        }
    }

    @Test
    void floatBearingComplexEdgeCases() {
        double tiny = Double.MIN_VALUE;
        double max = Double.MAX_VALUE;
        double[][] pairs = {
                {1.0, 1.0}, {-1.0, 1.0}, {1.0, -1.0}, {-1.0, -1.0},
                // Severe cancellation in |z| - |a| near the axes and the branch cut.
                {1e300, 1e-300}, {-1e300, 1e-300}, {-1e300, -1e-300}, {1e-300, -1e300},
                {-1.0, 1e-20}, {-1.0, -1e-20}, {1.0, tiny}, {-1.0, tiny},
                // Near overflow and underflow.
                {max, max}, {-max, max}, {max, -tiny}, {-max, tiny},
                {tiny, tiny}, {-tiny, tiny}, {0.0, tiny}, {-0.0, -tiny}, {-tiny, 0.0},
                {Double.MIN_NORMAL, -Double.MIN_NORMAL}, {3.0 * tiny, 5.0 * tiny},
        };
        for (double[] pair : pairs) {
            checkComplex(new ProtosFloatValue(pair[0]), new ProtosFloatValue(pair[1]));
        }
    }

    @Test
    void floatBearingComplexWithExactComponentsBeyondBinary64() {
        Object one = new ProtosFloatValue(1.0);
        Object minusOne = new ProtosFloatValue(-1.0);
        checkComplex(large(BigInteger.TWO.pow(1200)), one);
        checkComplex(large(BigInteger.TWO.pow(1200).negate()), minusOne);
        // u overflows to +Infinity while v = 1 / 2u is a nonzero subnormal.
        checkComplex(large(BigInteger.TWO.pow(2100)), one);
        checkComplex(large(BigInteger.TWO.pow(2100).negate()), minusOne);
        // v = 1 / 2u is below half the least subnormal: a zero oracle with the sign of b.
        checkComplex(large(BigInteger.TWO.pow(2200)), one);
        checkComplex(large(BigInteger.TWO.pow(2200)), minusOne);
        checkComplex(new ProtosFloatValue(1e-300), large(BigInteger.TEN.pow(400)));
        checkComplex(
                fraction(BigInteger.ONE, BigInteger.TWO.pow(1200).add(BigInteger.ONE)),
                new ProtosFloatValue(Double.MIN_VALUE));
    }

    @Test
    void exactComplexWithIrrationalCoordinates() {
        checkComplex(integer(1), integer(1));
        checkComplex(integer(-1), integer(1));
        checkComplex(integer(1), integer(2));
        // Rational modulus 5 but irrational coordinates.
        checkComplex(integer(4), integer(3));
        checkComplex(integer(-4), integer(-3));
        checkComplex(large(BigInteger.TEN.pow(300)), integer(1));
        checkComplex(large(BigInteger.TEN.pow(300).negate()), integer(-1));
        checkComplex(fraction(BigInteger.ONE, BigInteger.valueOf(3)), integer(2));
    }

    @Test
    void sampledFloatBearingComplex() {
        Random random = new Random(0x5172_D198L);
        for (int i = 0; i < 400; i++) {
            checkComplex(new ProtosFloatValue(sample(random)), new ProtosFloatValue(sample(random)));
        }
    }

    // ---- Checks ----

    private static void checkReal(Object argument) {
        Object result = call(argument);
        BigDecimal x = exact(argument);
        if (x.signum() >= 0) {
            checkCoordinate(oracleRoot(x), false, result, "sqrt(" + x + ")");
            return;
        }
        assertTrue(ProtosComplexNumericSupport.isComplex(result), "family: expected a Complex");
        checkCoordinate(oracleRoot(x.negate()), false,
                ProtosComplexNumericSupport.imaginary(result), "imaginary of sqrt(" + x + ")");
    }

    private static void checkComplex(Object real, Object imaginary) {
        Object argument = ProtosComplexNumericSupport.normalize(real, imaginary, prelude);
        Object result = call(argument);
        assertTrue(ProtosComplexNumericSupport.isComplex(result), "family: expected a Complex");
        BigDecimal a = exact(real);
        BigDecimal b = exact(imaginary);
        boolean negativeImaginary = imaginary instanceof ProtosFloatValue floating
                ? Double.doubleToRawLongBits(floating.value()) < 0L
                : b.signum() < 0;
        String label = "sqrt(C(" + a + ", " + b + "))";

        BigDecimal big = BigDecimal.ZERO;
        BigDecimal small = BigDecimal.ZERO;
        BigDecimal norm = a.multiply(a, ORACLE).add(b.multiply(b, ORACLE), ORACLE);
        if (norm.signum() != 0) {
            BigDecimal sum = norm.sqrt(ORACLE).add(a.abs(), ORACLE);
            big = sum.divide(BigDecimal.TWO, ORACLE).sqrt(ORACLE);
            small = b.multiply(b, ORACLE)
                    .divide(sum.multiply(BigDecimal.TWO, ORACLE), ORACLE)
                    .sqrt(ORACLE);
        }
        boolean realIsBig = a.signum() >= 0;
        checkCoordinate(realIsBig ? big : small, false,
                ProtosComplexNumericSupport.real(result), "real of " + label);
        checkCoordinate(realIsBig ? small : big, negativeImaginary,
                ProtosComplexNumericSupport.imaginary(result), "imaginary of " + label);
    }

    /*
     * Compares one coordinate with the oracle of the nonnegative magnitude, signed as required,
     * and names the class of failure: family, non-finite, zero sign, sign, or ULP distance.
     */
    private static void checkCoordinate(
            BigDecimal magnitude, boolean negative, Object coordinate, String label) {
        if (!(coordinate instanceof ProtosFloatValue floating)) {
            fail("family: " + label + " is not a Float");
            return;
        }
        double actual = floating.value();
        double oracle = Math.copySign(magnitude.doubleValue(), negative ? -1.0 : 1.0);
        long actualBits = Double.doubleToRawLongBits(actual);
        long oracleBits = Double.doubleToRawLongBits(oracle);
        if (Double.isInfinite(oracle) || Double.isNaN(actual) || Double.isInfinite(actual)) {
            if (actualBits != oracleBits) {
                fail("non-finite: " + label + " expected " + oracle + ", got " + actual);
            }
            return;
        }
        if (oracle == 0.0) {
            if (actualBits != oracleBits) {
                fail("zero sign: " + label + " expected " + oracle + ", got " + actual);
            }
            return;
        }
        if ((actualBits < 0L) != (oracleBits < 0L)) {
            fail("sign: " + label + " expected " + oracle + ", got " + actual);
        }
        long distance = Math.abs((actualBits & Long.MAX_VALUE) - (oracleBits & Long.MAX_VALUE));
        if (distance > MAX_ULP) {
            fail("ULP: " + label + " is " + distance + " ULP from " + oracle + ", got " + actual);
        }
    }

    private static BigDecimal oracleRoot(BigDecimal x) {
        return x.signum() == 0 ? BigDecimal.ZERO : x.sqrt(ORACLE);
    }

    /* The exact value of an Integer or finite Float; a Fraction to ORACLE_DIGITS digits. */
    private static BigDecimal exact(Object value) {
        if (value instanceof ProtosFloatValue floating) {
            return binary64(Double.doubleToRawLongBits(floating.value()));
        }
        if (ProtosNumericValueSupport.isFraction(value)) {
            return exact(ProtosNumericValueSupport.fractionNumerator(value))
                    .divide(exact(ProtosNumericValueSupport.fractionDenominator(value)), ORACLE);
        }
        Object host = ProtosNumericValueSupport.hostInteger(value);
        return host instanceof BigInteger big
                ? new BigDecimal(big)
                : BigDecimal.valueOf((Long) host);
    }

    /* The exact value of finite binary64 bits, decoded field by field. */
    private static BigDecimal binary64(long bits) {
        int exponent = (int) ((bits >>> 52) & 0x7FF);
        long fraction = bits & ((1L << 52) - 1);
        BigInteger significand = BigInteger.valueOf(exponent == 0 ? fraction : fraction | (1L << 52));
        int power = (exponent == 0 ? 1 : exponent) - 1075;
        BigDecimal magnitude = power >= 0
                ? new BigDecimal(significand.shiftLeft(power))
                : new BigDecimal(significand).divide(new BigDecimal(BigInteger.TWO.pow(-power)));
        return bits < 0L ? magnitude.negate() : magnitude;
    }

    private static double sample(Random random) {
        double sign = random.nextBoolean() ? 1.0 : -1.0;
        return switch (random.nextInt(7)) {
            case 0 -> sign * 0.0;
            case 1 -> sign * Double.longBitsToDouble(random.nextLong() & 0x000fffffffffffffL);
            case 2 -> sign * Math.scalb(random.nextDouble() + 1.0, 1000 + random.nextInt(23));
            case 3 -> sign * Math.scalb(random.nextDouble() + 1.0, -1060 + random.nextInt(30));
            default -> sign * Math.scalb(random.nextDouble() + 0.5, random.nextInt(120) - 60);
        };
    }

    private static Object call(Object argument) {
        return ProtosTestExecutionSupport.callEntered(() ->
                ProtosInvocation.invokeMessage(math, "sqrt", List.of(argument), caller));
    }

    private static Object integer(long value) {
        return new ProtosIntegerValue(value);
    }

    private static Object large(BigInteger value) {
        return ProtosNumericValueSupport.integerFromHost(value, prelude);
    }

    private static Object fraction(BigInteger numerator, BigInteger denominator) {
        return ProtosNumericValueSupport.divideIntegersExactly(
                large(numerator), large(denominator), prelude);
    }
}
