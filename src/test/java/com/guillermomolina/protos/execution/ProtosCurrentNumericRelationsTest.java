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

import static org.junit.jupiter.api.Assertions.*;

import com.guillermomolina.protos.runtime.ProtosFloatValue;
import com.guillermomolina.protos.runtime.ProtosIntegerValue;
import com.guillermomolina.protos.runtime.ProtosPrelude;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

final class ProtosCurrentNumericRelationsTest {

    @Test
    void equalityUsesExactMathematicalValue() {
        ProtosIntegerValue beyond = new ProtosIntegerValue((1L << 53) + 1L);
        ProtosFloatValue rounded =
                new ProtosFloatValue((double) (1L << 53));

        assertFalse(ProtosStandardNumberEqualityProtocol.numericEquals(
                beyond, rounded));

        ProtosIntegerValue integer = new ProtosIntegerValue(42L);
        ProtosFloatValue floating = new ProtosFloatValue(42.0);

        assertTrue(ProtosStandardNumberEqualityProtocol.numericEquals(
                integer, floating));
        assertTrue(ProtosStandardNumberEqualityProtocol.numericEquals(
                floating, integer));

        assertEquals(
                hash(integer, null),
                hash(floating, null));
    }

    @Test
    void signedZeroNanAndHashPreserveCurrentSemantics() {
        ProtosFloatValue positive = new ProtosFloatValue(0.0);
        ProtosFloatValue negative = new ProtosFloatValue(-0.0);
        ProtosFloatValue nan = new ProtosFloatValue(Double.NaN);

        assertTrue(ProtosCurrentNumericRelations.numericEquals(
                positive, negative));
        assertFalse(ProtosCurrentNumericRelations.numericEquals(nan, nan));

        assertEquals(hash(positive, null), hash(negative, null));
        assertEquals("2146959360", hash(nan, null));

        assertEquals(
                ProtosStandardNumberOrderingProtocol.Comparison.UNORDERED,
                ProtosStandardNumberOrderingProtocol.compare(nan, positive));
    }

    @Test
    void orderingIsExactAcrossBinary64Boundaries() throws IOException {
        ProtosPrelude prelude = corePrelude();
        ProtosIntegerValue max =
                new ProtosIntegerValue(Long.MAX_VALUE);
        ProtosFloatValue rounded =
                new ProtosFloatValue((double) Long.MAX_VALUE);

        assertEquals(
                ProtosStandardNumberOrderingProtocol.Comparison.LESS,
                ProtosStandardNumberOrderingProtocol.compare(max, rounded));

        assertEquals(
                ProtosStandardNumberOrderingProtocol.Comparison.GREATER,
                ProtosStandardNumberOrderingProtocol.compare(rounded, max));

        Object huge = com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerFromHost(BigInteger.ONE.shiftLeft(130), prelude);

        assertEquals(
                ProtosStandardNumberOrderingProtocol.Comparison.GREATER,
                ProtosStandardNumberOrderingProtocol.compare(
                        new ProtosFloatValue(Double.POSITIVE_INFINITY),
                        huge));

        ProtosFloatValue exact =
                new ProtosFloatValue(Math.scalb(1.0, 130));

        assertTrue(ProtosCurrentNumericRelations.numericEquals(huge, exact));
        assertEquals(hash(huge, prelude), hash(exact, prelude));
        assertSame(huge, ProtosCurrentNumericRelations.normalHash(huge, prelude));
    }

    @Test
    void floatOrderingAgainstBigIntegersIsExactOnBothSidesOfTheLongRange() throws IOException {
        ProtosPrelude prelude = corePrelude();
        Object twoPow63 = com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerFromHost(BigInteger.ONE.shiftLeft(63), prelude);
        Object belowMinLong =
                com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerFromHost(BigInteger.ONE.shiftLeft(63).negate().subtract(BigInteger.ONE), prelude);
        Object twoPow70Plus1 =
                com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerFromHost(BigInteger.ONE.shiftLeft(70).add(BigInteger.ONE), prelude);

        assertTrue(ProtosCurrentNumericRelations.numericEquals(
                twoPow63, new ProtosFloatValue(0x1p63)));
        assertComparison(ProtosStandardNumberOrderingProtocol.Comparison.LESS,
                new ProtosFloatValue(0x1.fffffffffffffp62), twoPow63);
        assertComparison(ProtosStandardNumberOrderingProtocol.Comparison.GREATER,
                new ProtosFloatValue(-0x1p63), belowMinLong);
        assertComparison(ProtosStandardNumberOrderingProtocol.Comparison.GREATER,
                new ProtosFloatValue(-0.0), belowMinLong);
        assertComparison(ProtosStandardNumberOrderingProtocol.Comparison.LESS,
                new ProtosFloatValue(0x1p70), twoPow70Plus1);
        assertComparison(ProtosStandardNumberOrderingProtocol.Comparison.GREATER,
                new ProtosFloatValue(0x1.0000000000001p70), twoPow70Plus1);
        assertComparison(ProtosStandardNumberOrderingProtocol.Comparison.LESS,
                new ProtosFloatValue(-0x1p70), belowMinLong);
        assertComparison(ProtosStandardNumberOrderingProtocol.Comparison.UNORDERED,
                new ProtosFloatValue(Double.NaN), twoPow63);
    }

    @Test
    void signed64EdgesAndOneHundredThirtyBitIntegersOrderExactly() throws IOException {
        ProtosPrelude prelude = corePrelude();
        var less = ProtosStandardNumberOrderingProtocol.Comparison.LESS;
        var greater = ProtosStandardNumberOrderingProtocol.Comparison.GREATER;
        var equal = ProtosStandardNumberOrderingProtocol.Comparison.EQUAL;
        Object twoPow63 = com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerFromHost(BigInteger.ONE.shiftLeft(63), prelude);
        ProtosIntegerValue max = new ProtosIntegerValue(Long.MAX_VALUE);
        ProtosIntegerValue min = new ProtosIntegerValue(Long.MIN_VALUE);
        assertEquals(less, ProtosCurrentNumericRelations.compare(max, twoPow63));
        assertEquals(greater, ProtosCurrentNumericRelations.compare(twoPow63, max));
        assertFalse(ProtosCurrentNumericRelations.numericEquals(max, twoPow63));

        assertTrue(ProtosCurrentNumericRelations.numericEquals(min, new ProtosFloatValue(-0x1p63)));
        assertEquals(equal, ProtosCurrentNumericRelations.compare(min, new ProtosFloatValue(-0x1p63)));
        assertEquals(less, ProtosCurrentNumericRelations.compare(
                min, new ProtosFloatValue(-0x1.fffffffffffffp62)));
        assertEquals(greater, ProtosCurrentNumericRelations.compare(
                min, new ProtosFloatValue(-0x1.0000000000001p63)));

        BigInteger magnitude = BigInteger.ONE.shiftLeft(129).add(BigInteger.ONE);
        Object positive = com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerFromHost(magnitude, prelude);
        Object negative = com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerFromHost(magnitude.negate(), prelude);
        assertComparison(less, new ProtosFloatValue(0x1p129), positive);
        assertComparison(greater, new ProtosFloatValue(0x1.0000000000001p129), positive);
        assertComparison(greater, new ProtosFloatValue(-0x1p129), negative);
        assertComparison(less, new ProtosFloatValue(-0x1.0000000000001p129), negative);
        assertComparison(less, new ProtosFloatValue(Double.NEGATIVE_INFINITY), negative);
        assertComparison(greater, new ProtosFloatValue(Double.POSITIVE_INFINITY), negative);
        assertComparison(less, new ProtosFloatValue(Double.NEGATIVE_INFINITY), positive);
        assertComparison(
                ProtosStandardNumberOrderingProtocol.Comparison.UNORDERED,
                new ProtosFloatValue(Double.NaN),
                negative);
        assertFalse(ProtosCurrentNumericRelations.numericEquals(
                negative, new ProtosFloatValue(Double.NaN)));

        Object exactNegative = com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerFromHost(BigInteger.ONE.shiftLeft(129).negate(), prelude);
        assertTrue(ProtosCurrentNumericRelations.numericEquals(
                exactNegative, new ProtosFloatValue(-0x1p129)));
        assertTrue(ProtosCurrentNumericRelations.numericEquals(
                new ProtosIntegerValue(1L << 53), new ProtosFloatValue(0x1p53)));
        assertFalse(ProtosCurrentNumericRelations.numericEquals(
                new ProtosIntegerValue((1L << 53) + 1L), new ProtosFloatValue(0x1p53)));
    }

    private static void assertComparison(
            ProtosStandardNumberOrderingProtocol.Comparison expected,
            ProtosFloatValue floating,
            Object integer) {
        assertEquals(expected, ProtosStandardNumberOrderingProtocol.compare(floating, integer));
    }

    @Test
    void javaHostNumbersRemainOutsideGuestNumericDomain() {
        assertThrows(
                IllegalArgumentException.class,
                () -> ProtosCurrentNumericRelations.compare(
                        Long.valueOf(1), new ProtosIntegerValue(1L)));

        assertThrows(
                IllegalArgumentException.class,
                () -> ProtosCurrentNumericRelations.normalHash(BigInteger.ONE, null));
    }

    private static String hash(Object number, ProtosPrelude prelude) {
        return com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerDecimalText(
                ProtosCurrentNumericRelations.normalHash(number, prelude));
    }

    private static ProtosPrelude corePrelude() throws IOException {
        return new ProtosCoreBootstrap().bootstrap(Path.of("protos", "lib", "core"));
    }
}
