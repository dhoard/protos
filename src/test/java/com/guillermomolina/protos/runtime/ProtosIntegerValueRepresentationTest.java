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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.guillermomolina.protos.execution.ProtosCoreBootstrap;
import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.UnsupportedMessageException;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * I091 / PLAT056 Candidate C: a semantic Integer within the signed-64 range is a long-only
 * {@link ProtosIntegerValue}; outside it, a FROZEN ordinary {@link ProtosLargeIntegerValue} owned
 * by a Prelude. Every exact result is normalized, so the representation is a function of the value.
 * Under D197-C the long-only value delegates to the Prelude's SmallInteger and the large value to
 * its BigInteger; both concrete prototypes delegate to Integer.
 */
final class ProtosIntegerValueRepresentationTest {
    private static final BigInteger LONG_MAX = BigInteger.valueOf(Long.MAX_VALUE);
    private static final BigInteger LONG_MIN = BigInteger.valueOf(Long.MIN_VALUE);
    private static final BigInteger ABOVE_LONG = LONG_MAX.add(BigInteger.ONE);
    private static final BigInteger BELOW_LONG = LONG_MIN.subtract(BigInteger.ONE);

    private static ProtosPrelude prelude;

    @BeforeAll
    static void bootstrap() throws IOException {
        prelude = new ProtosCoreBootstrap().bootstrap(Path.of("protos", "lib", "core"));
    }

    @Test
    void canonicalizesTheEntireSignedLongRangeToTheLongRepresentation() {
        for (BigInteger value :
                new BigInteger[] {
                    BigInteger.ZERO,
                    BigInteger.ONE,
                    BigInteger.ONE.negate(),
                    LONG_MAX,
                    LONG_MIN
                }) {
            Object integer = ProtosNumericValueSupport.integer(value, prelude);
            assertSmall(value.longValueExact(), integer);
        }

        assertLarge(ABOVE_LONG, ProtosNumericValueSupport.integer(ABOVE_LONG, prelude));
        assertLarge(BELOW_LONG, ProtosNumericValueSupport.integer(BELOW_LONG, prelude));
    }

    @Test
    void largeIntegersAreFrozenOrdinaryObjectsDelegatingToTheBigIntegerPrototype() {
        ProtosLargeIntegerValue large = assertInstanceOf(
                ProtosLargeIntegerValue.class,
                ProtosNumericValueSupport.integer(ABOVE_LONG, prelude));
        assertSame(prelude.bigIntegerPrototype(), large.parent().orElseThrow());
        assertSame(
                prelude.integerPrototype(),
                prelude.bigIntegerPrototype().parent().orElseThrow());
        assertTrue(large.isFrozen());
        assertTrue(large.localSlotsSnapshot().isEmpty());
        assertTrue(ProtosNumericValueSupport.isCurrentInteger(large));
        assertTrue(ProtosValueLookup.isInteger(large));
        assertThrows(RuntimeException.class,
                () -> large.createLocalSlot("mutated", ProtosNullValue.INSTANCE));
        assertThrows(NullPointerException.class,
                () -> ProtosNumericValueSupport.integer(ABOVE_LONG, (ProtosPrelude) null));
    }

    @Test
    void largeIntegerKeepsDelegationWithoutLeakingObjectMembersToInterop()
            throws Exception {
        ProtosLargeIntegerValue large =
                assertInstanceOf(
                        ProtosLargeIntegerValue.class,
                        ProtosNumericValueSupport.integer(ABOVE_LONG, prelude));

        // Ordinary Protos delegation is intact.
        assertSame(prelude.bigIntegerPrototype(), large.parent().orElseThrow());
        assertTrue(large.lookupSlot("parent").isPresent());
        assertTrue(large.isFrozen());
        assertTrue(large.localSlotsSnapshot().isEmpty());

        // Polyglot observes an exact numeric value, not an ordinary mutable
        // object's members or its Java payload.
        InteropLibrary interop = InteropLibrary.getUncached();
        assertTrue(interop.isNumber(large));
        assertTrue(interop.fitsInBigInteger(large));
        assertFalse(interop.fitsInLong(large));
        assertEquals(ABOVE_LONG, interop.asBigInteger(large));
        assertFalse(interop.hasMembers(large));
        assertFalse(interop.isMemberReadable(large, "parent"));
        assertThrows(
                UnsupportedMessageException.class,
                () -> interop.getMembers(large));

        assertThrows(
                IllegalStateException.class,
                () -> large.assignLocalSlot("payload", ProtosNullValue.INSTANCE));
    }

    @Test
    void smallArithmeticStaysSmallAndOverflowPromotesExactly() {
        ProtosIntegerValue forty = new ProtosIntegerValue(40L);
        ProtosIntegerValue two = new ProtosIntegerValue(2L);

        assertSmall(42L, ProtosNumericValueSupport.addIntegers(forty, two, null));
        assertSmall(38L, ProtosNumericValueSupport.subtractIntegers(forty, two, null));
        assertSmall(80L, ProtosNumericValueSupport.multiplyIntegers(forty, two, null));
        assertSmall(20L, ProtosNumericValueSupport.quotientIntegers(forty, two, null));
        assertSmall(0L, ProtosNumericValueSupport.remainderIntegers(forty, two, null));

        assertLarge(
                ABOVE_LONG,
                ProtosNumericValueSupport.addIntegers(
                        new ProtosIntegerValue(Long.MAX_VALUE), new ProtosIntegerValue(1L), prelude));
        assertLarge(
                BELOW_LONG,
                ProtosNumericValueSupport.subtractIntegers(
                        new ProtosIntegerValue(Long.MIN_VALUE), new ProtosIntegerValue(1L), prelude));

        ProtosIntegerValue multiplicand = new ProtosIntegerValue(3_037_000_500L);
        assertLarge(
                BigInteger.valueOf(3_037_000_500L).pow(2),
                ProtosNumericValueSupport.multiplyIntegers(multiplicand, multiplicand, prelude));
    }

    @Test
    void largeArithmeticNormalizesBackToTheLongRepresentationWhenTheResultFits() {
        Object above = ProtosNumericValueSupport.integer(ABOVE_LONG, prelude);
        ProtosIntegerValue maximum = new ProtosIntegerValue(Long.MAX_VALUE);

        assertSmall(1L, ProtosNumericValueSupport.subtractIntegers(above, maximum, null));
        assertSmall(0L, ProtosNumericValueSupport.subtractIntegers(above, above, null));
    }

    @Test
    void quotientAndRemainderPreserveExactSignedSemantics() {
        assertLarge(
                BigInteger.ONE.shiftLeft(63),
                ProtosNumericValueSupport.quotientIntegers(
                        new ProtosIntegerValue(Long.MIN_VALUE), new ProtosIntegerValue(-1L), prelude));

        assertSmall(2L, quotient(7L, 3L));
        assertSmall(-2L, quotient(-7L, 3L));
        assertSmall(-2L, quotient(7L, -3L));
        assertSmall(2L, quotient(-7L, -3L));

        assertSmall(1L, remainder(7L, 3L));
        assertSmall(-1L, remainder(-7L, 3L));
        assertSmall(1L, remainder(7L, -3L));
        assertSmall(-1L, remainder(-7L, -3L));
        assertSmall(0L, remainder(Long.MIN_VALUE, -1L));
    }

    @Test
    void comparisonAndSemanticIdentityDependOnlyOnTheMathematicalInteger() {
        ProtosIntegerValue smallA = new ProtosIntegerValue(42L);
        ProtosIntegerValue smallB = new ProtosIntegerValue(42L);

        Object positiveBigA = ProtosNumericValueSupport.integer(BigInteger.ONE.shiftLeft(100), prelude);
        Object positiveBigB = ProtosNumericValueSupport.integer(BigInteger.ONE.shiftLeft(100), prelude);
        Object negativeBig =
                ProtosNumericValueSupport.integer(BigInteger.ONE.shiftLeft(100).negate(), prelude);

        assertTrue(ProtosNumericValueSupport.sameInteger(smallA, smallB));
        assertTrue(ProtosIdentity.identical(smallA, smallB));
        assertEquals(0, ProtosNumericValueSupport.compareIntegers(smallA, smallB));

        assertTrue(ProtosNumericValueSupport.sameInteger(positiveBigA, positiveBigB));
        assertTrue(ProtosIdentity.identical(positiveBigA, positiveBigB));
        assertEquals(ProtosIdentity.identityHash(positiveBigA), ProtosIdentity.identityHash(positiveBigB));
        assertEquals(0, ProtosNumericValueSupport.compareIntegers(positiveBigA, positiveBigB));
        assertFalse(ProtosIdentity.identical(positiveBigA, negativeBig));

        assertTrue(ProtosNumericValueSupport.compareIntegers(smallA, positiveBigA) < 0);
        assertTrue(ProtosNumericValueSupport.compareIntegers(positiveBigA, smallA) > 0);

        assertTrue(ProtosNumericValueSupport.compareIntegers(negativeBig, smallA) < 0);
        assertTrue(ProtosNumericValueSupport.compareIntegers(smallA, negativeBig) > 0);
        assertTrue(ProtosNumericValueSupport.compareIntegers(negativeBig, positiveBigA) < 0);
    }

    @Test
    void exactIntProjectionUsesTheLongRepresentationWithoutChangingIntegerSemantics() {
        ProtosIntegerValue minimum = new ProtosIntegerValue(Integer.MIN_VALUE);
        ProtosIntegerValue maximum = new ProtosIntegerValue(Integer.MAX_VALUE);
        ProtosIntegerValue above = new ProtosIntegerValue((long) Integer.MAX_VALUE + 1L);
        Object huge = ProtosNumericValueSupport.integer(BigInteger.ONE.shiftLeft(100), prelude);

        assertTrue(minimum.fitsInIntForRuntime());
        assertTrue(maximum.fitsInIntForRuntime());
        assertEquals(Integer.MIN_VALUE, minimum.intValueExactForRuntime());
        assertEquals(Integer.MAX_VALUE, maximum.intValueExactForRuntime());

        assertFalse(above.fitsInIntForRuntime());
        assertFalse(ProtosNumericValueSupport.isIntegerInIntRange(huge));
        assertFalse(ProtosNumericValueSupport.isIntegerInLongRange(huge));
    }

    @Test
    void largeConstructionRejectsEverySigned64Value() {
        ProtosObjectValue prototype = prelude.bigIntegerPrototype();
        for (BigInteger value : new BigInteger[] {BigInteger.ZERO, LONG_MAX, LONG_MIN}) {
            assertThrows(IllegalArgumentException.class,
                    () -> new ProtosLargeIntegerValue(prototype, value));
        }
        assertEquals(ABOVE_LONG, new ProtosLargeIntegerValue(prototype, ABOVE_LONG).exactValue());
        assertEquals(BELOW_LONG, new ProtosLargeIntegerValue(prototype, BELOW_LONG).exactValue());
    }

    @Test
    void overflowAndReturnPreserveSignAndMagnitudeAtBothBoundaries() {
        ProtosIntegerValue maximum = new ProtosIntegerValue(Long.MAX_VALUE);
        ProtosIntegerValue minimum = new ProtosIntegerValue(Long.MIN_VALUE);
        ProtosIntegerValue one = new ProtosIntegerValue(1L);

        Object above = ProtosNumericValueSupport.addIntegers(maximum, one, prelude);
        assertLarge(ABOVE_LONG, above);
        Object below = ProtosNumericValueSupport.subtractIntegers(minimum, one, prelude);
        assertLarge(BELOW_LONG, below);

        assertSmall(Long.MAX_VALUE, ProtosNumericValueSupport.subtractIntegers(above, one, prelude));
        assertSmall(Long.MIN_VALUE, ProtosNumericValueSupport.addIntegers(below, one, prelude));

        BigInteger hugePositive = BigInteger.ONE.shiftLeft(1000);
        BigInteger hugeNegative = hugePositive.negate();
        Object positive = ProtosNumericValueSupport.integer(hugePositive, prelude);
        Object negative = ProtosNumericValueSupport.integer(hugeNegative, prelude);
        assertLarge(hugePositive, positive);
        assertLarge(hugeNegative, negative);
        assertEquals(-1, ProtosNumericValueSupport.integerSignum(negative));
        assertSmall(0L, ProtosNumericValueSupport.addIntegers(positive, negative, prelude));
        assertSmall(-1L, ProtosNumericValueSupport.quotientIntegers(negative, positive, prelude));
        assertEquals(hugeNegative.toString(),
                ProtosNumericValueSupport.integerDecimalText(negative));
        assertTrue(ProtosValueLookup.isInteger(negative));
    }

    @Test
    void equalLargeCopiesShareValueIdentityAndRematerializeInAnotherPrelude() throws IOException {
        BigInteger value = BigInteger.ONE.shiftLeft(80).negate().subtract(BigInteger.TEN);
        Object first = ProtosNumericValueSupport.integer(value, prelude);
        Object second = ProtosNumericValueSupport.integer(value, prelude);
        assertFalse(first == second);
        assertTrue(ProtosIdentity.identical(first, second));
        assertEquals(ProtosIdentity.identityHash(first), ProtosIdentity.identityHash(second));
        assertEquals(ProtosNumericValueSupport.integerHashCode(first), value.hashCode());

        ProtosPrelude other =
                new ProtosCoreBootstrap().bootstrap(Path.of("protos", "lib", "core"));
        ProtosLargeIntegerValue copy = assertInstanceOf(
                ProtosLargeIntegerValue.class,
                ProtosNumericValueSupport.copyCurrentNumberOrNull(first, other));
        assertSame(other.bigIntegerPrototype(), copy.parent().orElseThrow());
        assertFalse(copy.parent().orElseThrow() == prelude.bigIntegerPrototype());
        assertTrue(copy.isFrozen());
        assertEquals(String.valueOf(value), com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerDecimalText(copy));
        assertTrue(ProtosIdentity.identical(first, copy));
        assertEquals(ProtosIdentity.identityHash(first), ProtosIdentity.identityHash(copy));
    }

    private static Object quotient(long left, long right) {
        return ProtosNumericValueSupport.quotientIntegers(
                new ProtosIntegerValue(left), new ProtosIntegerValue(right), null);
    }

    private static Object remainder(long left, long right) {
        return ProtosNumericValueSupport.remainderIntegers(
                new ProtosIntegerValue(left), new ProtosIntegerValue(right), null);
    }

    private static void assertSmall(long expected, Object actual) {
        ProtosIntegerValue small = assertInstanceOf(ProtosIntegerValue.class, actual);
        assertEquals(expected, small.longValue());
        assertSame(prelude.smallIntegerPrototype(), small.representedDelegationParent(prelude));
        assertEquals(Long.toString(expected), com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerDecimalText(actual));
    }

    private static void assertLarge(BigInteger expected, Object actual) {
        ProtosLargeIntegerValue large = assertInstanceOf(ProtosLargeIntegerValue.class, actual);
        assertSame(prelude.bigIntegerPrototype(), large.parent().orElseThrow());
        assertEquals(String.valueOf(expected), com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerDecimalText(large));
    }
}
