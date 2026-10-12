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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.guillermomolina.protos.execution.ProtosStandardNumberOrderingProtocol.Comparison;
import com.guillermomolina.protos.runtime.ProtosActorValueTransfer;
import com.guillermomolina.protos.runtime.ProtosFloatValue;
import com.guillermomolina.protos.runtime.ProtosIdentity;
import com.guillermomolina.protos.runtime.ProtosIntegerValue;
import com.guillermomolina.protos.runtime.ProtosLargeIntegerValue;
import com.guillermomolina.protos.runtime.ProtosNumericValueSupport;
import com.guillermomolina.protos.runtime.ProtosObjectValue;
import com.guillermomolina.protos.runtime.ProtosPrelude;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/** D197-C (I090-B): canonical Fraction representation, relations, conversion and transfer. */
final class ProtosFractionSemanticsTest {
    private static final BigInteger TWO_POW_64 = BigInteger.ONE.shiftLeft(64);

    @Test
    void exactQuotientNormalizesSignGcdAndIntegralResults() throws IOException {
        ProtosPrelude prelude = corePrelude();

        Object half = divide(2, 4, prelude);
        assertFraction(half, 1, 2, prelude);
        assertFraction(divide(-2, 4, prelude), -1, 2, prelude);
        assertFraction(divide(2, -4, prelude), -1, 2, prelude);
        assertFraction(divide(-2, -4, prelude), 1, 2, prelude);
        assertTrue(((ProtosObjectValue) half).isFrozen());

        assertEquals(0L, small(divide(0, 7, prelude)));
        assertEquals(-2L, small(divide(-6, 3, prelude)));
        assertInstanceOf(
                ProtosLargeIntegerValue.class, divide(Long.MIN_VALUE, -1, prelude));
        assertThrows(ArithmeticException.class, () -> divide(1, 0, prelude));

        // Components beyond signed-64 normalize exactly and demote when they cancel.
        Object big = com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerFromHost(TWO_POW_64.multiply(BigInteger.valueOf(3)), prelude);
        Object bigDivisor = com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerFromHost(TWO_POW_64, prelude);
        assertEquals(3L, small(ProtosNumericValueSupport.divideIntegersExactly(
                big, bigDivisor, prelude)));
        Object wide = ProtosNumericValueSupport.divideIntegersExactly(
                bigDivisor, new ProtosIntegerValue(3L), prelude);
        assertTrue(ProtosNumericValueSupport.isFraction(wide));
        assertInstanceOf(
                ProtosLargeIntegerValue.class, ProtosNumericValueSupport.fractionNumerator(wide));

        // The primitive path without a Prelude never mints and never allocates large state.
        assertNull(ProtosNumericValueSupport.divideIntegersExactly(
                new ProtosIntegerValue(1L), new ProtosIntegerValue(3L), null));
        assertEquals(2L, small(ProtosNumericValueSupport.divideIntegersExactly(
                new ProtosIntegerValue(6L), new ProtosIntegerValue(3L), null)));
    }

    @Test
    void signed64ExtremesNormalizeWithoutUnnecessaryWideArithmetic()
            throws IOException {
        ProtosPrelude prelude = corePrelude();

        assertEquals(Long.MIN_VALUE / 2L,
                small(divide(Long.MIN_VALUE, 2L, prelude)));
        assertEquals(-(Long.MIN_VALUE / 2L),
                small(divide(Long.MIN_VALUE, -2L, prelude)));
        assertEquals(1L,
                small(divide(Long.MIN_VALUE, Long.MIN_VALUE, prelude)));
        assertEquals(0L,
                small(divide(0L, Long.MIN_VALUE, prelude)));

        assertFraction(
                divide(Long.MIN_VALUE, 3L, prelude),
                Long.MIN_VALUE, 3L, prelude);
        assertFraction(
                divide(2L, Long.MIN_VALUE, prelude),
                -1L, 1L << 62, prelude);

        Object widePositive =
                divide(Long.MIN_VALUE, -3L, prelude);
        assertTrue(ProtosNumericValueSupport.isFraction(widePositive));
        assertInstanceOf(
                ProtosLargeIntegerValue.class,
                ProtosNumericValueSupport.fractionNumerator(widePositive));

        Object wideDenominator =
                divide(1L, Long.MIN_VALUE, prelude);
        assertTrue(ProtosNumericValueSupport.isFraction(wideDenominator));
        assertInstanceOf(
                ProtosLargeIntegerValue.class,
                ProtosNumericValueSupport.fractionDenominator(wideDenominator));

        assertEquals(Long.MIN_VALUE / 2L,
                small(divide(Long.MIN_VALUE, 2L, null)));
        assertEquals(1L,
                small(divide(Long.MIN_VALUE, Long.MIN_VALUE, null)));
        assertNull(divide(Long.MIN_VALUE, 3L, null));
        assertNull(divide(Long.MIN_VALUE, -1L, null));
        assertNull(divide(1L, Long.MIN_VALUE, null));
    }

    @Test
    void ordinaryObjectsDelegatingToFractionAreNotNumbers() throws IOException {
        ProtosPrelude prelude = corePrelude();
        ProtosObjectValue forged = new ProtosObjectValue(prelude.fractionPrototype());
        forged.freeze();
        assertFalse(ProtosNumericValueSupport.isFraction(forged));
        assertFalse(ProtosNumericValueSupport.isCurrentNumber(forged));
        assertFalse(ProtosNumericValueSupport.isFraction(prelude.fractionPrototype()));
        assertThrows(IllegalArgumentException.class,
                () -> ProtosNumericValueSupport.fractionNumerator(forged));
    }

    @Test
    void relationsAreExactAcrossIntegerFractionAndFloat() throws IOException {
        ProtosPrelude prelude = corePrelude();
        Object half = divide(1, 2, prelude);
        Object tenth = divide(1, 10, prelude);
        Object third = divide(1, 3, prelude);

        assertTrue(ProtosCurrentNumericRelations.numericEquals(half, new ProtosFloatValue(0.5)));
        assertTrue(ProtosCurrentNumericRelations.numericEquals(new ProtosFloatValue(0.5), half));
        assertFalse(ProtosCurrentNumericRelations.numericEquals(tenth, new ProtosFloatValue(0.1)));
        assertFalse(ProtosCurrentNumericRelations.numericEquals(half, new ProtosIntegerValue(0L)));
        assertTrue(ProtosCurrentNumericRelations.numericEquals(half, divide(2, 4, prelude)));

        // binary64 1/3 is below the exact rational 1/3.
        assertSame(Comparison.GREATER,
                ProtosCurrentNumericRelations.compare(third, new ProtosFloatValue(1.0 / 3.0)));
        assertSame(Comparison.LESS,
                ProtosCurrentNumericRelations.compare(new ProtosFloatValue(1.0 / 3.0), third));
        assertSame(Comparison.LESS, ProtosCurrentNumericRelations.compare(third, half));
        assertSame(Comparison.GREATER,
                ProtosCurrentNumericRelations.compare(half, new ProtosIntegerValue(0L)));
        assertSame(Comparison.LESS,
                ProtosCurrentNumericRelations.compare(new ProtosIntegerValue(0L), half));
        assertSame(Comparison.UNORDERED,
                ProtosCurrentNumericRelations.compare(half, new ProtosFloatValue(Double.NaN)));
        assertSame(Comparison.LESS, ProtosCurrentNumericRelations.compare(
                half, new ProtosFloatValue(Double.POSITIVE_INFINITY)));
        assertSame(Comparison.GREATER,
                ProtosCurrentNumericRelations.compare(half, new ProtosFloatValue(-0.0)));
        assertSame(Comparison.GREATER, ProtosCurrentNumericRelations.compare(
                half, new ProtosFloatValue(Double.MIN_VALUE)));

        // 2^53 + 1/2 lies strictly between two adjacent binary64 values.
        Object above = ProtosNumericValueSupport.divideIntegersExactly(
                new ProtosIntegerValue((1L << 54) + 1L), new ProtosIntegerValue(2L), prelude);
        assertSame(Comparison.GREATER,
                ProtosCurrentNumericRelations.compare(above, new ProtosFloatValue(0x1p53)));
        assertSame(Comparison.LESS, ProtosCurrentNumericRelations.compare(
                above, new ProtosFloatValue(0x1p53 + 2.0)));

        // A subnormal binary64 equals its exact dyadic rational.
        Object tiny = ProtosNumericValueSupport.divideIntegersExactly(
                new ProtosIntegerValue(1L),
                com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerFromHost(BigInteger.ONE.shiftLeft(1074), prelude),
                prelude);
        assertTrue(ProtosCurrentNumericRelations.numericEquals(
                tiny, new ProtosFloatValue(Double.MIN_VALUE)));
    }

    @Test
    void compactFractionsCompareExactlyWithExtremeBinary64Values()
            throws IOException {
        ProtosPrelude prelude = corePrelude();
        Object positive = divide(1L, 3L, prelude);
        Object negative = divide(-1L, 3L, prelude);

        assertSame(Comparison.LESS,
                ProtosCurrentNumericRelations.compare(
                        positive, new ProtosFloatValue(0x1p64)));
        assertSame(Comparison.GREATER,
                ProtosCurrentNumericRelations.compare(
                        positive, new ProtosFloatValue(-0x1p64)));
        assertSame(Comparison.GREATER,
                ProtosCurrentNumericRelations.compare(
                        negative, new ProtosFloatValue(-0x1p64)));
        assertSame(Comparison.LESS,
                ProtosCurrentNumericRelations.compare(
                        negative, new ProtosFloatValue(0x1p64)));

        assertSame(Comparison.GREATER,
                ProtosCurrentNumericRelations.compare(
                        positive, new ProtosFloatValue(Double.MIN_VALUE)));
        assertSame(Comparison.LESS,
                ProtosCurrentNumericRelations.compare(
                        negative, new ProtosFloatValue(-Double.MIN_VALUE)));

        assertSame(Comparison.LESS,
                ProtosCurrentNumericRelations.compare(
                        new ProtosFloatValue(Double.MIN_VALUE), positive));
        assertSame(Comparison.GREATER,
                ProtosCurrentNumericRelations.compare(
                        new ProtosFloatValue(-Double.MIN_VALUE), negative));

        assertFalse(ProtosCurrentNumericRelations.numericEquals(
                positive, new ProtosFloatValue(Double.MIN_VALUE)));
    }

    @Test
    void compactRationalComparisonsRemainExactAcrossWideProducts()
            throws IOException {
        ProtosPrelude prelude = corePrelude();

        Object half = divide(1, 2, prelude);
        Object third = divide(1, 3, prelude);
        Object negativeHalf = divide(-1, 2, prelude);
        Object maximumHalf = divide(Long.MAX_VALUE, 2L, prelude);
        Object almostMaximumHalf = divide(Long.MAX_VALUE - 2L, 2L, prelude);
        Object minimumHalf = divide(Long.MIN_VALUE + 1L, 2L, prelude);

        assertSame(Comparison.GREATER,
                ProtosCurrentNumericRelations.compare(half, third));
        assertSame(Comparison.LESS,
                ProtosCurrentNumericRelations.compare(third, half));
        assertSame(Comparison.LESS,
                ProtosCurrentNumericRelations.compare(negativeHalf, third));
        assertSame(Comparison.GREATER,
                ProtosCurrentNumericRelations.compare(maximumHalf, almostMaximumHalf));
        assertSame(Comparison.LESS,
                ProtosCurrentNumericRelations.compare(minimumHalf, maximumHalf));

        assertSame(Comparison.GREATER,
                ProtosCurrentNumericRelations.compare(
                        maximumHalf, new ProtosIntegerValue(Long.MAX_VALUE / 2L)));
        assertSame(Comparison.LESS,
                ProtosCurrentNumericRelations.compare(
                        maximumHalf, new ProtosIntegerValue(Long.MAX_VALUE)));

        assertSame(Comparison.EQUAL,
                ProtosCurrentNumericRelations.compare(
                        half, new ProtosFloatValue(0.5d)));
        assertSame(Comparison.EQUAL,
                ProtosCurrentNumericRelations.compare(
                        divide(-3, 2, prelude), new ProtosFloatValue(-1.5d)));
        assertSame(Comparison.GREATER,
                ProtosCurrentNumericRelations.compare(
                        third, new ProtosFloatValue(1.0d / 3.0d)));
        assertSame(Comparison.GREATER,
                ProtosCurrentNumericRelations.compare(
                        half, new ProtosFloatValue(Double.MIN_VALUE)));
        assertSame(Comparison.LESS,
                ProtosCurrentNumericRelations.compare(
                        half, new ProtosFloatValue(0x1p63)));
        assertSame(Comparison.GREATER,
                ProtosCurrentNumericRelations.compare(
                        maximumHalf, new ProtosFloatValue(0x1p60)));
    }

    @Test
    void normalHashAndIdentityStayCoherent() throws IOException {
        ProtosPrelude prelude = corePrelude();
        Object half = divide(1, 2, prelude);
        Object twin = divide(2, 4, prelude);

        assertEquals(com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerDecimalText(ProtosCurrentNumericRelations.normalHash(half, prelude)), com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerDecimalText(ProtosCurrentNumericRelations.normalHash(
                        new ProtosFloatValue(0.5), prelude)));
        assertEquals(com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerDecimalText(ProtosCurrentNumericRelations.normalHash(half, prelude)), com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerDecimalText(ProtosCurrentNumericRelations.normalHash(twin, prelude)));

        assertNotSame(half, twin);
        assertTrue(ProtosIdentity.identical(half, twin));
        assertEquals(ProtosIdentity.identityHash(half), ProtosIdentity.identityHash(twin));
        assertFalse(ProtosIdentity.identical(half, new ProtosFloatValue(0.5)));
        assertNotEquals(
                ProtosIdentity.identityHash(half),
                ProtosIdentity.identityHash(new ProtosFloatValue(0.5)));
        assertFalse(ProtosIdentity.identical(half, divide(1, 3, prelude)));
    }

    @Test
    void floatConversionRoundsTheWholeQuotientOnce() throws IOException {
        ProtosPrelude prelude = corePrelude();
        ProtosFloatValue third = assertInstanceOf(ProtosFloatValue.class,
                ProtosStandardNumericConversionProtocol.convert(
                        ProtosStandardNumericConversionProtocol.FactoryKind.FLOAT,
                        divide(1, 3, prelude), prelude));
        assertEquals(1.0 / 3.0, third.value());
        assertNull(ProtosStandardNumericConversionProtocol.convert(
                ProtosStandardNumericConversionProtocol.FactoryKind.INTEGER,
                divide(1, 3, prelude), prelude));

        // Converting components separately would give Infinity / Infinity = NaN.
        BigInteger denominator = BigInteger.TEN.pow(400);
        Object nearOne = ProtosNumericValueSupport.divideIntegersExactly(
                com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerFromHost(denominator.add(BigInteger.ONE), prelude),
                com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerFromHost(denominator, prelude),
                prelude);
        assertTrue(ProtosNumericValueSupport.isFraction(nearOne));
        assertEquals(1.0, ProtosNumericValueSupport.roundFractionToBinary64(nearOne));
    }

    @Test
    void actorTransferMaterializesWithTheDestinationPrototypeAndKeepsAliasing()
            throws IOException {
        ProtosPrelude sourcePrelude = corePrelude();
        ProtosPrelude destinationPrelude = corePrelude();
        Object fraction = ProtosNumericValueSupport.divideIntegersExactly(
                com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerFromHost(TWO_POW_64, sourcePrelude),
                new ProtosIntegerValue(3L),
                sourcePrelude);

        List<Object> snapshot = ProtosActorValueTransfer.snapshotArguments(
                List.of(fraction, fraction), sourcePrelude.newModuleActivation());
        assertTrue(ProtosActorValueTransfer.requiresMaterialization(snapshot));

        List<?> delivered = ProtosActorValueTransfer.materializeArguments(
                snapshot, destinationPrelude.newModuleActivation());
        Object copied = delivered.get(0);
        assertTrue(ProtosNumericValueSupport.isFraction(copied));
        assertSame(copied, delivered.get(1));
        assertNotSame(fraction, copied);
        assertSame(destinationPrelude.fractionPrototype(),
                ((ProtosObjectValue) copied).parent().orElseThrow());
        assertSame(destinationPrelude.bigIntegerPrototype(),
                ((ProtosObjectValue) ProtosNumericValueSupport.fractionNumerator(copied))
                        .parent().orElseThrow());
        assertTrue(ProtosIdentity.identical(fraction, copied));
    }

    @Test
    void parallelTransferMaterializesWithTheDestinationPrototype() throws IOException {
        ProtosPrelude sourcePrelude = corePrelude();
        ProtosPrelude destinationPrelude = corePrelude();
        Object fraction = divide(-5, 7, sourcePrelude);

        boolean[] records = new boolean[1];
        List<Object> captured = ProtosParallelRuntime.captureValuesForTesting(
                List.of(fraction, fraction), sourcePrelude.newModuleActivation(), records);
        assertTrue(records[0]);
        assertFalse(ProtosNumericValueSupport.isFraction(captured.get(0)));

        List<Object> delivered = ProtosParallelRuntime.materializeValuesForTesting(
                captured, destinationPrelude.newModuleActivation());
        assertSame(delivered.get(0), delivered.get(1));
        assertSame(destinationPrelude.fractionPrototype(),
                ((ProtosObjectValue) delivered.get(0)).parent().orElseThrow());
        assertTrue(ProtosIdentity.identical(fraction, delivered.get(0)));
    }

    private static Object divide(long numerator, long denominator, ProtosPrelude prelude) {
        return ProtosNumericValueSupport.divideIntegersExactly(
                new ProtosIntegerValue(numerator), new ProtosIntegerValue(denominator), prelude);
    }

    private static long small(Object integer) {
        return assertInstanceOf(ProtosIntegerValue.class, integer).longValue();
    }

    private static void assertFraction(
            Object value, long numerator, long denominator, ProtosPrelude prelude) {
        assertTrue(ProtosNumericValueSupport.isFraction(value));
        assertEquals(numerator, small(ProtosNumericValueSupport.fractionNumerator(value)));
        assertEquals(denominator, small(ProtosNumericValueSupport.fractionDenominator(value)));
        assertSame(prelude.fractionPrototype(), ((ProtosObjectValue) value).parent().orElseThrow());
    }

    private static ProtosPrelude corePrelude() throws IOException {
        return new ProtosCoreBootstrap().bootstrap(Path.of("protos", "lib", "core"));
    }
}
