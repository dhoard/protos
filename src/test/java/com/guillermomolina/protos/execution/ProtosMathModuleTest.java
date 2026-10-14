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
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.guillermomolina.protos.runtime.ProtosActivation;
import com.guillermomolina.protos.runtime.ProtosActorValueTransfer;
import com.guillermomolina.protos.runtime.ProtosBooleanValue;
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
import java.math.BigInteger;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * D198 (I090-C): the public surface of {@code std:math/Math}, the exact F3 roots, and the N4 and
 * real-Float special values of {@code sqrt}, exercised through the imported module function.
 * Finite inexact coordinates are checked against an independent oracle by
 * {@link ProtosMathSqrtAccuracyTest}.
 */
final class ProtosMathModuleTest {
    private static final Path CORE = Path.of("protos", "lib", "core");
    private static final Path STANDARD_LIBRARY = Path.of("protos", "lib");
    private static final double NAN = Double.NaN;
    private static final double INF = Double.POSITIVE_INFINITY;

    private static ProtosPrelude prelude;
    private static ProtosActivation caller;
    private static ProtosObjectValue math;

    @BeforeAll
    static void bootstrap() throws IOException {
        prelude = bootstrapPrelude();
        caller = prelude.newModuleActivation();
        math = importMath(caller);
    }

    // ---- Public surface ----

    @Test
    void importedModuleExportsExactlySqrt() {
        assertEquals(Set.of("sqrt"), math.localSlotsSnapshot().keySet());
    }

    @Test
    void sqrtIsNoPreludeBindingNorNumericSelector() {
        assertTrue(prelude.bindings().readLocalSlot("sqrt").isEmpty());
        assertTrue(prelude.bindings().readLocalSlot(ProtosMathFacility.BOOTSTRAP_SLOT).isEmpty());
        for (ProtosObjectValue prototype : List.of(
                ProtosObjectValue.rootObject(),
                prelude.numberPrototype(),
                prelude.integerPrototype(),
                prelude.smallIntegerPrototype(),
                prelude.bigIntegerPrototype(),
                prelude.fractionPrototype(),
                prelude.floatPrototype(),
                prelude.complexPrototype())) {
            assertFalse(prototype.hasLocalSlot("sqrt"), "unexpected sqrt selector");
        }
    }

    @Test
    void wrongArityAndNonNumbersSignalError() {
        assertSignals(() -> call());
        assertSignals(() -> call(integer(4), integer(4)));
        assertSignals(() -> call(new ProtosStringValue("4")));
        assertSignals(() -> call(ProtosBooleanValue.TRUE));
        assertSignals(() -> call(prelude.integerPrototype()));
        assertSignals(() -> call(prelude.complexPrototype()));
        assertSignals(() -> call(evaluate("Integer {}")));
        assertSignals(() -> call(evaluate("Complex {}")));
        assertSignals(() -> call(evaluate("Number { real: () => 4 }")));
    }

    @Test
    void sqrtSendsNoMessageAndRunsNoCallback() {
        Object result = evaluate(
                """
                (() => {
                    Math: import("std:math/Math")
                    touched: false
                    touch: () => {
                        touched = true
                        4
                    }
                    probe: Number {
                        real: touch
                        imaginary: touch
                        sqrt: touch
                        call: touch
                        hash: touch
                    }
                    signaled: false
                    Error.handle(() => { Math.sqrt(probe) }, (error) => { signaled = true })
                    signaled && !touched
                })()
                """);
        assertSame(ProtosBooleanValue.TRUE, result);
    }

    @Test
    void sqrtIsAnOrdinaryOverridableModuleFunction() {
        Object result = evaluate(
                """
                (() => {
                    Math: import("std:math/Math")
                    Custom: Math {
                        sqrt: (value) => "replaced"
                    }
                    (Custom.sqrt(4) == "replaced") && (Math.sqrt(4) == 2)
                })()
                """);
        assertSame(ProtosBooleanValue.TRUE, result);
    }

    @Test
    void repeatedImportSharesOneModuleAndContextsKeepTheirOwnResults() throws IOException {
        assertSame(math, importMath(caller));

        ProtosPrelude other = bootstrapPrelude();
        ProtosActivation otherCaller = other.newModuleActivation();
        ProtosObjectValue otherMath = importMath(otherCaller);
        assertNotSame(math, otherMath);
        assertEquals(Set.of("sqrt"), otherMath.localSlotsSnapshot().keySet());

        Object here = call(integer(-4));
        Object there = ProtosTestExecutionSupport.callEntered(() ->
                ProtosInvocation.invokeMessage(otherMath, "sqrt", List.of(integer(-4)), otherCaller));
        assertSame(prelude.complexPrototype(), assertInstanceOf(ProtosObjectValue.class, here)
                .parent().orElseThrow());
        assertSame(other.complexPrototype(), assertInstanceOf(ProtosObjectValue.class, there)
                .parent().orElseThrow());
    }

    @Test
    void sqrtResultsRetainFamiliesAndSignedZerosThroughActorAndPTransfer()
            throws IOException {
        Object exactComplex = call(complex(integer(3), integer(4)));
        Object exactFraction = call(fraction(1, 4));
        Object signedZero = call(complex(flt(-4.0), flt(-0.0)));
        Object mixed = call(complex(large(BigInteger.ONE.shiftLeft(1200)), flt(1.0)));
        Object largeRoot = call(large(BigInteger.ONE.shiftLeft(360)));

        Object product = ProtosTestExecutionSupport.callEntered(
                () -> ProtosInvocation.invokeMessage(
                        complex(integer(1), integer(2)),
                        "*",
                        List.of(complex(integer(3), integer(4))),
                        caller));

        List<Object> roots = List.of(
                exactComplex, exactComplex, exactFraction,
                signedZero, mixed, largeRoot, product);

        ProtosPrelude destination = bootstrapPrelude();
        ProtosActivation receiver = destination.newModuleActivation();

        List<Object> actorSnapshot =
                ProtosActorValueTransfer.snapshotArguments(roots, caller);
        assertTrue(ProtosActorValueTransfer.requiresMaterialization(actorSnapshot));
        List<?> actorValues =
                ProtosActorValueTransfer.materializeArguments(actorSnapshot, receiver);

        boolean[] pRecords = new boolean[1];
        List<Object> pSnapshot =
                ProtosParallelRuntime.captureValuesForTesting(roots, caller, pRecords);
        assertTrue(pRecords[0]);
        List<?> pValues =
                ProtosParallelRuntime.materializeValuesForTesting(pSnapshot, receiver);

        for (List<?> values : List.of(actorValues, pValues)) {
            Object copiedComplex = values.get(0);
            assertNotSame(exactComplex, copiedComplex);
            assertSame(copiedComplex, values.get(1));
            assertSame(
                    destination.complexPrototype(),
                    assertInstanceOf(ProtosObjectValue.class, copiedComplex)
                            .parent().orElseThrow());
            assertTrue(ProtosIdentity.identical(exactComplex, copiedComplex));
            assertExactComponent(
                    integer(2), ProtosComplexNumericSupport.real(copiedComplex));
            assertExactComponent(
                    integer(1), ProtosComplexNumericSupport.imaginary(copiedComplex));

            Object copiedFraction = values.get(2);
            assertTrue(ProtosNumericValueSupport.isFraction(copiedFraction));
            assertSame(
                    destination.fractionPrototype(),
                    assertInstanceOf(ProtosObjectValue.class, copiedFraction)
                            .parent().orElseThrow());
            assertTrue(ProtosIdentity.identical(exactFraction, copiedFraction));

            Object copiedSignedZero = values.get(3);
            assertSame(
                    destination.complexPrototype(),
                    assertInstanceOf(ProtosObjectValue.class, copiedSignedZero)
                            .parent().orElseThrow());
            assertFloatComplex(copiedSignedZero, 0.0, -2.0);
            assertTrue(ProtosIdentity.identical(signedZero, copiedSignedZero));

            Object copiedMixed = values.get(4);
            assertSame(
                    destination.complexPrototype(),
                    assertInstanceOf(ProtosObjectValue.class, copiedMixed)
                            .parent().orElseThrow());
            assertFloatComplex(
                    copiedMixed,
                    Math.scalb(1.0, 600),
                    Math.scalb(1.0, -601));
            assertTrue(ProtosIdentity.identical(mixed, copiedMixed));

            Object copiedLarge = values.get(5);
            assertSame(
                    destination.bigIntegerPrototype(),
                    assertInstanceOf(ProtosObjectValue.class, copiedLarge)
                            .parent().orElseThrow());
            assertTrue(ProtosIdentity.identical(largeRoot, copiedLarge));

            Object copiedProduct = values.get(6);
            assertSame(
                    destination.complexPrototype(),
                    assertInstanceOf(ProtosObjectValue.class, copiedProduct)
                            .parent().orElseThrow());
            assertTrue(ProtosIdentity.identical(product, copiedProduct));
            assertExactComponent(
                    integer(-5), ProtosComplexNumericSupport.real(copiedProduct));
            assertExactComponent(
                    integer(10), ProtosComplexNumericSupport.imaginary(copiedProduct));
        }
    }

    // ---- Exact real and Complex roots (F3) ----

    @Test
    void exactRationalRootsStayExactAndCanonical() {
        assertExactReal(integer(2), call(integer(4)));
        assertInstanceOf(ProtosIntegerValue.class, call(integer(4)));
        assertExactReal(integer(0), call(integer(0)));
        assertExactReal(integer(1), call(integer(1)));
        assertExactReal(fraction(1, 2), call(fraction(1, 4)));
        assertTrue(ProtosNumericValueSupport.isFraction(call(fraction(1, 4))));
        assertExactReal(fraction(7, 3), call(fraction(49, 9)));

        BigInteger root = BigInteger.TEN.pow(40).add(BigInteger.valueOf(3));
        Object big = call(large(root.multiply(root)));
        assertInstanceOf(ProtosLargeIntegerValue.class, big);
        assertExactReal(large(root), big);
        BigInteger denominator = BigInteger.TWO.pow(90).add(BigInteger.ONE);
        assertExactReal(
                rational(root, denominator),
                call(rational(root.multiply(root), denominator.multiply(denominator))));
    }

    @Test
    void irrationalExactRootsAreFloats() {
        assertBits(Math.sqrt(2.0), floatOf(call(integer(2))), "sqrt(2)");
        assertBits(1.4142135623730951, floatOf(call(integer(2))), "sqrt(2) oracle");
        assertBits(Math.sqrt(2.0 / 3.0), floatOf(call(fraction(2, 3))), "sqrt(2/3)");
        // 4 is a square but 4/3 is not: a square numerator alone is not enough.
        assertInstanceOf(ProtosFloatValue.class, call(fraction(4, 3)));
        double big = floatOf(call(large(BigInteger.TWO.pow(1200).add(BigInteger.ONE))));
        assertBits(Math.scalb(1.0, 600), big, "sqrt(2^1200 + 1)");
    }

    @Test
    void negativeExactRealsGiveExactZeroRealComplex() {
        assertExactComplex(call(integer(-4)), integer(0), integer(2));
        assertExactComplex(call(fraction(-1, 4)), integer(0), fraction(1, 2));
        Object irrational = call(integer(-2));
        assertTrue(ProtosComplexNumericSupport.isComplex(irrational));
        assertExactComponent(integer(0), ProtosComplexNumericSupport.real(irrational));
        assertBits(1.4142135623730951, imaginaryOf(irrational), "sqrt(-2)");
    }

    @Test
    void exactComplexRationalCoordinatesStayExact() {
        assertExactComplex(call(complex(integer(3), integer(4))), integer(2), integer(1));
        assertExactComplex(call(complex(integer(3), integer(-4))), integer(2), integer(-1));
        assertExactComplex(call(complex(integer(-3), integer(4))), integer(1), integer(2));
        assertExactComplex(call(complex(integer(-3), integer(-4))), integer(1), integer(-2));
        assertExactComplex(call(complex(fraction(3, 4), integer(1))), integer(1), fraction(1, 2));
        assertExactComplex(call(complex(integer(0), integer(2))), integer(1), integer(1));
        assertExactComplex(call(complex(integer(0), integer(-2))), integer(1), integer(-1));
        assertExactComplex(
                call(complex(integer(0), fraction(1, 2))), fraction(1, 2), fraction(1, 2));

        // (p + qi)^2 = (p^2 - q^2) + 2pq i with p beyond binary64 and signed-64 ranges.
        BigInteger p = BigInteger.TEN.pow(30).add(BigInteger.ONE);
        BigInteger q = BigInteger.valueOf(7);
        Object square = complex(
                large(p.multiply(p).subtract(q.multiply(q))),
                large(p.multiply(q).shiftLeft(1)));
        assertExactComplex(call(square), large(p), integer(7));
    }

    @Test
    void exactComplexIrrationalCoordinatesAreBothFloats() {
        // |z| = 5 is rational, but (5 + 4) / 2 is not a square.
        Object rationalModulus = call(complex(integer(4), integer(3)));
        assertInstanceOf(ProtosFloatValue.class, ProtosComplexNumericSupport.real(rationalModulus));
        assertInstanceOf(
                ProtosFloatValue.class, ProtosComplexNumericSupport.imaginary(rationalModulus));
        Object irrationalModulus = call(complex(integer(1), integer(-1)));
        assertInstanceOf(
                ProtosFloatValue.class, ProtosComplexNumericSupport.real(irrationalModulus));
        assertTrue(imaginaryOf(irrationalModulus) < 0.0);
    }

    // ---- Float reals ----

    @Test
    void floatRealSpecialValues() {
        assertBits(NAN, floatOf(call(flt(NAN))), "NaN");
        assertBits(INF, floatOf(call(flt(INF))), "+Infinity");
        assertBits(0.0, floatOf(call(flt(0.0))), "+0.0");
        assertBits(-0.0, floatOf(call(flt(-0.0))), "-0.0");
        assertBits(2.0, floatOf(call(flt(4.0))), "4.0");
        assertFloatComplex(call(flt(-4.0)), 0.0, 2.0);
        assertFloatComplex(call(flt(-INF)), 0.0, INF);
        assertFloatComplex(call(flt(-Double.MIN_VALUE)), 0.0, Math.sqrt(Double.MIN_VALUE));
    }

    // ---- Float-bearing Complex: the N4 table, in priority order ----

    @Test
    void infiniteImaginaryWinsOverEveryRealIncludingNaN() {
        for (double a : new double[] {NAN, INF, -INF, 0.0, -0.0, -3.0, 5.0}) {
            assertFloatComplex(call(complex(flt(a), flt(INF))), INF, INF);
            assertFloatComplex(call(complex(flt(a), flt(-INF))), INF, -INF);
        }
        assertFloatComplex(call(complex(integer(-9), flt(-INF))), INF, -INF);
    }

    @Test
    void nanRealGivesNaNForFiniteOrNaNImaginary() {
        for (double b : new double[] {0.0, -0.0, 1.0, -1.0, NAN}) {
            assertFloatComplex(call(complex(flt(NAN), flt(b))), NAN, NAN);
        }
        assertFloatComplex(call(complex(flt(NAN), integer(3))), NAN, NAN);
    }

    @Test
    void infiniteRealRows() {
        assertFloatComplex(call(complex(flt(INF), flt(1.0))), INF, 0.0);
        assertFloatComplex(call(complex(flt(INF), flt(-1.0))), INF, -0.0);
        assertFloatComplex(call(complex(flt(INF), flt(0.0))), INF, 0.0);
        assertFloatComplex(call(complex(flt(INF), flt(-0.0))), INF, -0.0);
        assertFloatComplex(call(complex(flt(INF), integer(-2))), INF, -0.0);
        assertFloatComplex(call(complex(flt(INF), fraction(1, 3))), INF, 0.0);
        assertFloatComplex(call(complex(flt(INF), flt(NAN))), INF, NAN);

        assertFloatComplex(call(complex(flt(-INF), flt(1.0))), 0.0, INF);
        assertFloatComplex(call(complex(flt(-INF), flt(-1.0))), 0.0, -INF);
        assertFloatComplex(call(complex(flt(-INF), flt(0.0))), 0.0, INF);
        assertFloatComplex(call(complex(flt(-INF), flt(-0.0))), 0.0, -INF);
        assertFloatComplex(call(complex(flt(-INF), fraction(-1, 3))), 0.0, -INF);
        assertFloatComplex(call(complex(flt(-INF), flt(NAN))), NAN, INF);
    }

    @Test
    void finiteRealWithNaNImaginaryIsNaN() {
        assertFloatComplex(call(complex(flt(1.0), flt(NAN))), NAN, NAN);
        assertFloatComplex(call(complex(flt(-0.0), flt(NAN))), NAN, NAN);
        assertFloatComplex(call(complex(integer(4), flt(NAN))), NAN, NAN);
    }

    @Test
    void finiteRowsKeepPrincipalSignsAndSignedZeros() {
        assertFloatComplex(call(complex(flt(-4.0), flt(0.0))), 0.0, 2.0);
        assertFloatComplex(call(complex(flt(-4.0), flt(-0.0))), 0.0, -2.0);
        assertFloatComplex(call(complex(flt(4.0), flt(0.0))), 2.0, 0.0);
        assertFloatComplex(call(complex(flt(4.0), flt(-0.0))), 2.0, -0.0);
        assertFloatComplex(call(complex(flt(0.0), flt(0.0))), 0.0, 0.0);
        assertFloatComplex(call(complex(flt(-0.0), flt(-0.0))), 0.0, -0.0);
        assertFloatComplex(call(complex(integer(-4), flt(0.0))), 0.0, 2.0);
        assertFloatComplex(call(complex(integer(-4), flt(-0.0))), 0.0, -2.0);
        // Exact rational coordinates still become Floats when a component is a Float.
        assertFloatComplex(call(complex(flt(3.0), flt(4.0))), 2.0, 1.0);
        assertFloatComplex(call(complex(integer(3), flt(-4.0))), 2.0, -1.0);
        assertFloatComplex(call(complex(flt(-3.0), integer(4))), 1.0, 2.0);

        Object huge = call(complex(large(BigInteger.TWO.pow(1200)), flt(1.0)));
        assertTrue(Double.isFinite(realOf(huge)) && Double.isFinite(imaginaryOf(huge)));
        assertBits(Math.scalb(1.0, 600), realOf(huge), "sqrt(C(2^1200, 1.0)) real");
        assertBits(Math.scalb(1.0, -601), imaginaryOf(huge), "sqrt(C(2^1200, 1.0)) imaginary");
    }

    // ---- Helpers ----

    private static ProtosPrelude bootstrapPrelude() throws IOException {
        return new ProtosCoreBootstrap().bootstrap(
                CORE, new ProtosStandardLibraryModuleResolver(STANDARD_LIBRARY));
    }

    private static ProtosObjectValue importMath(ProtosActivation activation) {
        return assertInstanceOf(ProtosObjectValue.class,
                ProtosTestExecutionSupport.evaluate("import(\"std:math/Math\")", activation));
    }

    private static Object evaluate(String source) {
        return ProtosTestExecutionSupport.evaluate(source, caller);
    }

    private static Object call(Object... arguments) {
        return ProtosTestExecutionSupport.callEntered(() ->
                ProtosInvocation.invokeMessage(math, "sqrt", List.of(arguments), caller));
    }

    private static void assertSignals(Runnable action) {
        assertThrows(ProtosSignalException.class, action::run);
    }

    private static Object integer(long value) {
        return new ProtosIntegerValue(value);
    }

    private static Object large(BigInteger value) {
        return ProtosNumericValueSupport.integerFromHost(value, prelude);
    }

    private static Object fraction(long numerator, long denominator) {
        return ProtosNumericValueSupport.divideIntegersExactly(
                integer(numerator), integer(denominator), prelude);
    }

    private static Object rational(BigInteger numerator, BigInteger denominator) {
        return ProtosNumericValueSupport.divideIntegersExactly(
                large(numerator), large(denominator), prelude);
    }

    private static ProtosFloatValue flt(double value) {
        return new ProtosFloatValue(value);
    }

    private static Object complex(Object real, Object imaginary) {
        return ProtosComplexNumericSupport.normalize(real, imaginary, prelude);
    }

    private static double floatOf(Object value) {
        return assertInstanceOf(ProtosFloatValue.class, value).value();
    }

    private static double realOf(Object complex) {
        assertTrue(ProtosComplexNumericSupport.isComplex(complex), "expected a Complex");
        return floatOf(ProtosComplexNumericSupport.real(complex));
    }

    private static double imaginaryOf(Object complex) {
        assertTrue(ProtosComplexNumericSupport.isComplex(complex), "expected a Complex");
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

    private static void assertFloatComplex(Object value, double real, double imaginary) {
        assertBits(real, realOf(value), "real");
        assertBits(imaginary, imaginaryOf(value), "imaginary");
    }

    private static void assertExactComponent(Object expected, Object actual) {
        assertFalse(actual instanceof ProtosFloatValue, "expected an exact component");
        assertTrue(ProtosIdentity.identical(expected, actual),
                () -> "expected the exact canonical value");
    }

    private static void assertExactReal(Object expected, Object actual) {
        assertFalse(ProtosComplexNumericSupport.isComplex(actual), "expected a real result");
        assertExactComponent(expected, actual);
    }

    private static void assertExactComplex(Object value, Object real, Object imaginary) {
        assertTrue(ProtosComplexNumericSupport.isComplex(value), "expected a Complex");
        assertExactComponent(real, ProtosComplexNumericSupport.real(value));
        assertExactComponent(imaginary, ProtosComplexNumericSupport.imaginary(value));
    }
}
