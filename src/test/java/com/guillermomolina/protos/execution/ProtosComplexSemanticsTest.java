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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.guillermomolina.protos.runtime.ProtosActivation;
import com.guillermomolina.protos.runtime.ProtosActorValueTransfer;
import com.guillermomolina.protos.runtime.ProtosArrayValue;
import com.guillermomolina.protos.runtime.ProtosBooleanValue;
import com.guillermomolina.protos.runtime.ProtosClosureValue;
import com.guillermomolina.protos.runtime.ProtosComplexNumericSupport;
import com.guillermomolina.protos.runtime.ProtosFloatValue;
import com.guillermomolina.protos.runtime.ProtosIdentity;
import com.guillermomolina.protos.runtime.ProtosIntegerValue;
import com.guillermomolina.protos.runtime.ProtosLargeIntegerValue;
import com.guillermomolina.protos.runtime.ProtosNumericValueSupport;
import com.guillermomolina.protos.runtime.ProtosObjectValue;
import com.guillermomolina.protos.runtime.ProtosPrelude;
import com.guillermomolina.protos.runtime.ProtosSealedValue;
import com.guillermomolina.protos.runtime.ProtosSignalException;
import com.guillermomolina.protos.runtime.ProtosStringValue;

import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.UnsupportedMessageException;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * D197-C / D198 (I090): canonical Complex representation, authentication, identity, mathematical
 * equality, hashes and staged Actor/P transfer, using the internal canonicalization service.
 * Public construction and arithmetic are covered by {@code ProtosComplexArithmeticTest}.
 */
final class ProtosComplexSemanticsTest {
    private static final BigInteger TWO_POW_100 = BigInteger.ONE.shiftLeft(100);

    @Test
    void structuralCloseAndFreezeRejectEveryNumberRepresentation()
            throws IOException {
        ProtosPrelude prelude = corePrelude();
        ProtosActivation caller = prelude.newModuleActivation();

        Object small = new ProtosIntegerValue(7L);
        Object large = ProtosNumericValueSupport.integerFromHost(
                BigInteger.ONE.shiftLeft(90), prelude);
        Object floating = new ProtosFloatValue(-0.0d);
        Object fraction = divide(1L, 2L, prelude);
        Object complex = complex(
                new ProtosIntegerValue(3L),
                new ProtosFloatValue(-0.0d),
                prelude);

        for (Object number : List.of(
                small, large, floating, fraction, complex)) {
            assertTrue(ProtosNumericValueSupport.isCurrentNumber(number));

            // Semantic immutability is not ordinary FROZEN state; repeated
            // attempts must reject as well.
            for (int attempt = 0; attempt < 2; attempt++) {
                assertThrows(ProtosSignalException.class,
                        () -> ProtosInvocation.invokeMessage(
                                number, "close", List.of(), caller));
                assertThrows(ProtosSignalException.class,
                        () -> ProtosInvocation.invokeMessage(
                                number, "freeze", List.of(), caller));
            }
        }
    }

    @Test
    void bootstrapPublishesAFrozenComplexPrototypePerPrelude() throws IOException {
        ProtosPrelude prelude = corePrelude();
        ProtosPrelude other = corePrelude();
        ProtosObjectValue complex = prelude.complexPrototype();

        assertSame(complex, prelude.bindings().readLocalSlot("Complex").orElseThrow());
        assertSame(prelude.numberPrototype(), complex.parent().orElseThrow());
        assertTrue(complex.isFrozen());
        assertNotSame(complex, other.complexPrototype());
        // The trusted recognizer and factory, source-backed negation and arithmetic; no ordering.
        assertEquals(
                java.util.Set.of("recognizes", "call", "negated", "+", "-", "*", "/"),
                complex.localSlotsSnapshot().keySet());
        ProtosClosureValue recognizes = assertInstanceOf(
                ProtosClosureValue.class, complex.readLocalSlot("recognizes").orElseThrow());
        assertTrue(recognizes.nativeBody().isPresent());
        assertFalse(ProtosComplexNumericSupport.isComplex(complex));
    }

    @Test
    void canonicalFractionAndComplexHideHostStateWithoutChangingOrdinaryObjects()
            throws Exception {
        ProtosPrelude prelude = corePrelude();
        ProtosActivation caller = prelude.newModuleActivation();
        InteropLibrary interop = InteropLibrary.getUncached();

        Object fraction = divide(2L, 3L, prelude);
        Object complex = complex(
                new ProtosIntegerValue(2L),
                new ProtosIntegerValue(3L),
                prelude);

        assertTrue(ProtosNumericValueSupport.isFraction(fraction));
        assertTrue(ProtosComplexNumericSupport.isComplex(complex));

        for (Object value : List.of(fraction, complex)) {
            ProtosObjectValue object =
                    assertInstanceOf(ProtosObjectValue.class, value);

            assertTrue(object.isFrozen());
            assertTrue(object.localSlotsSnapshot().isEmpty());

            // Physical Java fields are not guest-visible members.
            assertFalse(interop.hasMembers(value));
            assertFalse(interop.isMemberReadable(value, "family"));
            assertFalse(interop.isMemberReadable(value, "state"));
            assertFalse(interop.isMemberReadable(value, "familyState"));

            assertThrows(
                    UnsupportedMessageException.class,
                    () -> interop.getMembers(value));

            // Guest reflection observes no fabricated backing slots.
            assertSame(
                    ProtosBooleanValue.FALSE,
                    ProtosInvocation.invokeMessage(
                            value,
                            "hasSlot",
                            List.of(new ProtosStringValue("familyState")),
                            caller));

            ProtosArrayValue names = assertInstanceOf(
                    ProtosArrayValue.class,
                    ProtosInvocation.invokeMessage(
                            value, "slotNames", List.of(), caller));
            assertEquals(0, names.indexedSize());

            // A canonical numeric value cannot acquire local bindings.
            assertThrows(
                    IllegalStateException.class,
                    () -> object.createLocalSlot(
                            "familyState", new ProtosIntegerValue(42L)));
        }

        // Ordinary delegation must not be mistaken for numeric membership
        // or disabled by the interop protection of sealed numeric values.
        for (ProtosObjectValue prototype : List.of(
                prelude.fractionPrototype(),
                prelude.complexPrototype())) {

            ProtosObjectValue ordinary = new ProtosObjectValue(prototype);
            ProtosStringValue marker = new ProtosStringValue("ordinary");
            ordinary.createLocalSlot("familyState", marker);
            ordinary.freeze();

            assertTrue(interop.hasMembers(ordinary));
            assertTrue(interop.isMemberReadable(ordinary, "familyState"));
            assertSame(marker, interop.readMember(ordinary, "familyState"));

            assertFalse(ProtosNumericValueSupport.isCurrentNumber(ordinary));
            assertFalse(ProtosNumericValueSupport.isFraction(ordinary));
            assertFalse(ProtosComplexNumericSupport.isComplex(ordinary));
        }
    }

    @Test
    void recognizerAuthenticatesOnlyGenuineValues() throws IOException {
        ProtosPrelude prelude = corePrelude();
        ProtosActivation caller = prelude.newModuleActivation();
        ProtosObjectValue prototype = prelude.complexPrototype();
        Object genuine = complex(new ProtosIntegerValue(1L), new ProtosIntegerValue(2L), prelude);

        ProtosObjectValue delegating = new ProtosObjectValue(prototype);
        ProtosObjectValue frozenForgery = new ProtosObjectValue(prototype);
        frozenForgery.createLocalSlot("recognizes", prototype.readLocalSlot("recognizes").orElseThrow());
        frozenForgery.freeze();

        assertTrue(recognizes(prototype, genuine, caller));
        for (Object candidate : List.of(
                prototype,
                delegating,
                frozenForgery,
                new ProtosIntegerValue(1L),
                divide(1, 2, prelude),
                new ProtosFloatValue(1.0),
                com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerFromHost(TWO_POW_100, prelude),
                new ProtosStringValue("Complex"))) {
            assertFalse(recognizes(prototype, candidate, caller));
            assertFalse(ProtosComplexNumericSupport.isComplex(candidate));
        }
        assertFalse(ProtosNumericValueSupport.isCurrentNumber(delegating));
        assertFalse(ProtosNumericValueSupport.isCurrentNumber(frozenForgery));

        // Another family's recognizer never admits a Complex.
        assertFalse(recognizes(prelude.fractionPrototype(), genuine, caller));
        assertFalse(recognizes(prelude.floatPrototype(), genuine, caller));
        assertFalse(recognizes(prelude.integerPrototype(), genuine, caller));

        assertThrows(ProtosSignalException.class, () -> ProtosInvocation.invokeMessage(
                prototype, "recognizes", List.of(), caller));
        assertThrows(ProtosSignalException.class, () -> ProtosInvocation.invokeMessage(
                prototype, "recognizes", List.of(genuine, genuine), caller));
        assertThrows(ProtosSignalException.class, () -> ProtosInvocation.invokeMessage(
                delegating, "recognizes", List.of(genuine), caller));
    }

    @Test
    void onlyAnExactIntegerZeroImaginaryPartCollapsesToTheRealPart() throws IOException {
        ProtosPrelude prelude = corePrelude();
        Object four = new ProtosIntegerValue(4L);
        Object third = divide(1, 3, prelude);
        ProtosFloatValue negativeZero = new ProtosFloatValue(-0.0);

        assertSame(four, complex(four, new ProtosIntegerValue(0L), prelude));
        assertSame(third, complex(third, new ProtosIntegerValue(0L), prelude));
        assertSame(negativeZero, complex(negativeZero, new ProtosIntegerValue(0L), prelude));

        Object positiveZeroImaginary =
                complex(new ProtosFloatValue(-4.0), new ProtosFloatValue(0.0), prelude);
        Object negativeZeroImaginary =
                complex(new ProtosFloatValue(-4.0), negativeZero, prelude);
        assertComplex(positiveZeroImaginary, prelude);
        assertComplex(negativeZeroImaginary, prelude);
        assertEquals(Double.doubleToRawLongBits(0.0), imaginaryBits(positiveZeroImaginary));
        assertEquals(Double.doubleToRawLongBits(-0.0), imaginaryBits(negativeZeroImaginary));
    }

    @Test
    void componentsKeepTheirExactFamilies() throws IOException {
        ProtosPrelude prelude = corePrelude();
        Object big = com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerFromHost(TWO_POW_100, prelude);
        Object third = divide(1, 3, prelude);

        Object exact = complex(big, third, prelude);
        assertComplex(exact, prelude);
        assertTrue(((ProtosObjectValue) exact).localSlotsSnapshot().isEmpty());
        assertSame(big, ProtosComplexNumericSupport.real(exact));
        assertSame(third, ProtosComplexNumericSupport.imaginary(exact));
        assertInstanceOf(ProtosLargeIntegerValue.class, ProtosComplexNumericSupport.real(exact));
        assertTrue(ProtosNumericValueSupport.isFraction(ProtosComplexNumericSupport.imaginary(exact)));

        Object mixed = complex(new ProtosIntegerValue(7L), new ProtosFloatValue(2.5), prelude);
        assertInstanceOf(ProtosIntegerValue.class, ProtosComplexNumericSupport.real(mixed));
        assertInstanceOf(ProtosFloatValue.class, ProtosComplexNumericSupport.imaginary(mixed));
    }

    @Test
    void canonicalizationRejectsNonRealComponents() throws IOException {
        ProtosPrelude prelude = corePrelude();
        Object one = new ProtosIntegerValue(1L);
        Object genuine = complex(one, one, prelude);
        ProtosObjectValue delegatingToNumber = new ProtosObjectValue(prelude.numberPrototype());
        delegatingToNumber.freeze();

        for (Object invalid : List.of(
                genuine,
                delegatingToNumber,
                new ProtosStringValue("1"),
                1L,
                1.0,
                BigInteger.ONE)) {
            assertThrows(IllegalArgumentException.class, () -> complex(invalid, one, prelude));
            assertThrows(IllegalArgumentException.class, () -> complex(one, invalid, prelude));
        }
        assertThrows(IllegalArgumentException.class,
                () -> ProtosComplexNumericSupport.real(prelude.complexPrototype()));

        // D198: every semantic Float, including NaN and the infinities, is a valid component.
        for (double special : new double[] {
                Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            assertComplex(complex(new ProtosFloatValue(special), one, prelude), prelude);
            assertComplex(complex(one, new ProtosFloatValue(special), prelude), prelude);
        }
    }

    @Test
    void equalityIsMathematicalExactAndSymmetric() throws IOException {
        ProtosPrelude prelude = corePrelude();
        ProtosActivation caller = prelude.newModuleActivation();
        Object half = divide(1, 2, prelude);
        Object tenth = divide(1, 10, prelude);
        Object one = new ProtosIntegerValue(1L);

        Object exactHalf = complex(one, half, prelude);
        Object floatHalf = complex(new ProtosFloatValue(1.0), new ProtosFloatValue(0.5), prelude);
        Object exactTenth = complex(one, tenth, prelude);
        Object floatTenth = complex(one, new ProtosFloatValue(0.1), prelude);
        assertEquality(true, exactHalf, floatHalf, caller);
        assertEquality(false, exactTenth, floatTenth, caller);
        assertEquality(false, exactHalf, complex(one, divide(1, 3, prelude), prelude), caller);

        // A retained Float zero imaginary part of either sign equals the real value.
        Object positiveZero = complex(one, new ProtosFloatValue(0.0), prelude);
        Object negativeZero = complex(one, new ProtosFloatValue(-0.0), prelude);
        assertEquality(true, positiveZero, one, caller);
        assertEquality(true, negativeZero, new ProtosFloatValue(1.0), caller);
        assertEquality(true, positiveZero, negativeZero, caller);
        assertEquality(false, positiveZero, new ProtosIntegerValue(2L), caller);

        // A nonzero imaginary part never equals a real value.
        assertEquality(false, exactHalf, one, caller);
        assertEquality(false, exactHalf, half, caller);

        // Arbitrarily large exact components are not rounded.
        Object big = com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerFromHost(TWO_POW_100, prelude);
        Object bigPlusOne = com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerFromHost(TWO_POW_100.add(BigInteger.ONE), prelude);
        assertEquality(true, complex(big, half, prelude),
                complex(new ProtosFloatValue(0x1p100), new ProtosFloatValue(0.5), prelude), caller);
        assertEquality(false, complex(bigPlusOne, half, prelude),
                complex(new ProtosFloatValue(0x1p100), new ProtosFloatValue(0.5), prelude), caller);

        // Non-Number arguments are unequal; no guest behavior of an operand is consulted.
        assertEquals(ProtosBooleanValue.FALSE, ProtosInvocation.invokeMessage(
                exactHalf, "==", List.of(new ProtosStringValue("x")), caller));
    }

    @Test
    void identityIsSemanticAndDistinguishesFamiliesAndSignedZero() throws IOException {
        ProtosPrelude prelude = corePrelude();
        Object one = new ProtosIntegerValue(1L);
        Object half = divide(1, 2, prelude);

        Object a = complex(one, half, prelude);
        Object b = complex(new ProtosIntegerValue(1L), divide(2, 4, prelude), prelude);
        assertNotSame(a, b);
        assertTrue(ProtosIdentity.identical(a, b));
        assertEquals(ProtosIdentity.identityHash(a), ProtosIdentity.identityHash(b));

        Object big = complex(com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerFromHost(TWO_POW_100, prelude), half, prelude);
        Object bigTwin = complex(com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerFromHost(TWO_POW_100, prelude),
                divide(1, 2, prelude), prelude);
        assertTrue(ProtosIdentity.identical(big, bigTwin));
        assertEquals(ProtosIdentity.identityHash(big), ProtosIdentity.identityHash(bigTwin));

        // Mathematically equal components of different families are not identical.
        Object floatHalf = complex(one, new ProtosFloatValue(0.5), prelude);
        assertFalse(ProtosIdentity.identical(a, floatHalf));

        Object positiveZero = complex(one, new ProtosFloatValue(0.0), prelude);
        Object negativeZero = complex(one, new ProtosFloatValue(-0.0), prelude);
        assertFalse(ProtosIdentity.identical(positiveZero, negativeZero));
        assertTrue(ProtosIdentity.identical(
                positiveZero, complex(one, new ProtosFloatValue(0.0), prelude)));

        // A Complex is never identical to a real value, even one it equals.
        for (Object real : List.of(one, new ProtosFloatValue(1.0), half)) {
            assertFalse(ProtosIdentity.identical(positiveZero, real));
            assertFalse(ProtosIdentity.identical(real, positiveZero));
        }
        assertNotEquals(ProtosIdentity.identityHash(positiveZero), ProtosIdentity.identityHash(one));
    }

    @Test
    void normalHashFollowsMathematicalEquality() throws IOException {
        ProtosPrelude prelude = corePrelude();
        ProtosActivation caller = prelude.newModuleActivation();
        Object one = new ProtosIntegerValue(1L);
        Object half = divide(1, 2, prelude);

        assertSameNormalHash(
                complex(one, half, prelude),
                complex(new ProtosFloatValue(1.0), new ProtosFloatValue(0.5), prelude),
                prelude);
        // Zero imaginary parts of either sign take the real value's hash.
        assertSameNormalHash(complex(one, new ProtosFloatValue(0.0), prelude), one, prelude);
        assertSameNormalHash(complex(one, new ProtosFloatValue(-0.0), prelude), one, prelude);
        assertSameNormalHash(
                complex(half, new ProtosFloatValue(-0.0), prelude), new ProtosFloatValue(0.5), prelude);
        Object big = com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerFromHost(TWO_POW_100, prelude);
        assertSameNormalHash(complex(big, new ProtosFloatValue(0.0), prelude), big, prelude);

        // A nonzero imaginary part combines both component hashes.
        Object withHalf = complex(one, half, prelude);
        Object withThird = complex(one, divide(1, 3, prelude), prelude);
        assertNotEquals(normalHash(withHalf, prelude), normalHash(withThird, prelude));
        assertNotEquals(normalHash(withHalf, prelude), normalHash(one, prelude));

        // The guest Number.hash answers the same normal hash.
        assertEquals(normalHash(withHalf, prelude), com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerDecimalText(
                ProtosInvocation.invokeMessage(withHalf, "hash", List.of(), caller)));
    }

    @Test
    void realOrderingRejectsComplexWithAStandardError() throws IOException {
        ProtosPrelude prelude = corePrelude();
        ProtosActivation caller = prelude.newModuleActivation();
        Object one = new ProtosIntegerValue(1L);
        Object value = complex(one, new ProtosFloatValue(0.0), prelude);

        for (String selector : List.of("<", "<=", ">", ">=")) {
            assertThrows(ProtosSignalException.class,
                    () -> ProtosInvocation.invokeMessage(value, selector, List.of(one), caller));
            assertThrows(ProtosSignalException.class,
                    () -> ProtosInvocation.invokeMessage(one, selector, List.of(value), caller));
        }
        assertThrows(IllegalArgumentException.class,
                () -> ProtosCurrentNumericRelations.compare(value, one));
        assertFalse(ProtosNumericValueSupport.isCurrentRealNumber(value));
        assertTrue(ProtosNumericValueSupport.isCurrentNumber(value));

        // Real ordering is unchanged.
        assertEquals(ProtosBooleanValue.TRUE, ProtosInvocation.invokeMessage(
                divide(1, 3, prelude), "<", List.of(new ProtosFloatValue(0.5)), caller));
        assertEquals(ProtosBooleanValue.TRUE, ProtosInvocation.invokeMessage(
                one, "<", List.of(new ProtosIntegerValue(2L)), caller));
    }

    @Test
    void realConversionsDoNotAdmitComplexButArithmeticDoes() throws IOException {
        ProtosPrelude prelude = corePrelude();
        ProtosActivation caller = prelude.newModuleActivation();
        Object one = new ProtosIntegerValue(1L);
        Object value = complex(one, new ProtosIntegerValue(2L), prelude);

        for (ProtosStandardNumericConversionProtocol.FactoryKind kind :
                ProtosStandardNumericConversionProtocol.FactoryKind.values()) {
            assertEquals(null, ProtosStandardNumericConversionProtocol.convert(kind, value, prelude));
        }
        // D198: real + Complex is standard Complex arithmetic in both native receiver families.
        Object exactSum = ProtosInvocation.invokeMessage(one, "+", List.of(value), caller);
        assertTrue(ProtosIdentity.identical(
                complex(new ProtosIntegerValue(2L), new ProtosIntegerValue(2L), prelude),
                exactSum));
        Object floatSum = ProtosInvocation.invokeMessage(
                new ProtosFloatValue(1.0), "+", List.of(value), caller);
        assertTrue(ProtosIdentity.identical(
                complex(new ProtosFloatValue(2.0), new ProtosIntegerValue(2L), prelude),
                floatSum));
        // div and mod stay Integer-only.
        assertThrows(ProtosSignalException.class,
                () -> ProtosInvocation.invokeMessage(one, "div", List.of(value), caller));
        assertThrows(ProtosSignalException.class,
                () -> ProtosInvocation.invokeMessage(one, "mod", List.of(value), caller));
    }

    @Test
    void actorTransferRematerializesInTheDestinationAndKeepsAliasing() throws IOException {
        ProtosPrelude sourcePrelude = corePrelude();
        ProtosPrelude destinationPrelude = sourcePrelude;
        Object exact = complex(
                com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerFromHost(TWO_POW_100, sourcePrelude),
                divide(-1, 3, sourcePrelude),
                sourcePrelude);
        Object signedZero = complex(
                new ProtosFloatValue(-0.0), new ProtosFloatValue(-0.0), sourcePrelude);
        ProtosArrayValue array = sourcePrelude.newFrozenArray(List.of(exact, signedZero, exact));

        List<Object> snapshot = ProtosActorValueTransfer.snapshotArguments(
                List.of(exact, array, signedZero), sourcePrelude.newModuleActivation());
        assertTrue(ProtosActorValueTransfer.requiresMaterialization(snapshot));

        List<?> delivered = ProtosActorValueTransfer.materializeArguments(
                snapshot, destinationPrelude.newModuleActivation());
        Object copied = delivered.get(0);
        assertNotSame(exact, copied);
        assertComplex(copied, destinationPrelude);
        assertTrue(ProtosIdentity.identical(exact, copied));
        assertSame(destinationPrelude.bigIntegerPrototype(),
                ((ProtosObjectValue) ProtosComplexNumericSupport.real(copied)).parent().orElseThrow());
        assertSame(destinationPrelude.fractionPrototype(),
                ((ProtosObjectValue) ProtosComplexNumericSupport.imaginary(copied))
                        .parent().orElseThrow());

        Object copiedZero = delivered.get(2);
        assertComplex(copiedZero, destinationPrelude);
        assertTrue(ProtosIdentity.identical(signedZero, copiedZero));
        assertEquals(Double.doubleToRawLongBits(-0.0), imaginaryBits(copiedZero));

        List<Object> elements = ((ProtosArrayValue) delivered.get(1)).indexedSnapshot();
        assertSame(copied, elements.get(0));
        assertSame(copiedZero, elements.get(1));
        assertSame(copied, elements.get(2));
    }


    @Test
    void actorTransferRebindsComplexAcrossDistinctPreludes() throws IOException {
        ProtosPrelude sourcePrelude = corePrelude();
        ProtosPrelude destinationPrelude = corePrelude();

        Object exact = complex(
                com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerFromHost(TWO_POW_100, sourcePrelude),
                divide(-1, 3, sourcePrelude),
                sourcePrelude);
        Object signedZero = complex(
                new ProtosFloatValue(-0.0),
                new ProtosFloatValue(-0.0),
                sourcePrelude);

        List<Object> snapshot = ProtosActorValueTransfer.snapshotArguments(
                List.of(exact, exact, signedZero),
                sourcePrelude.newModuleActivation());
        assertTrue(ProtosActorValueTransfer.requiresMaterialization(snapshot));

        List<?> delivered = ProtosActorValueTransfer.materializeArguments(
                snapshot, destinationPrelude.newModuleActivation());

        Object copied = delivered.get(0);
        assertNotSame(exact, copied);
        assertSame(copied, delivered.get(1));
        assertComplex(copied, destinationPrelude);
        assertTrue(ProtosIdentity.identical(exact, copied));

        Object copiedReal = ProtosComplexNumericSupport.real(copied);
        Object copiedImaginary = ProtosComplexNumericSupport.imaginary(copied);

        assertNotSame(ProtosComplexNumericSupport.real(exact), copiedReal);
        assertNotSame(ProtosComplexNumericSupport.imaginary(exact), copiedImaginary);

        assertSame(destinationPrelude.bigIntegerPrototype(),
                ((ProtosObjectValue) copiedReal).parent().orElseThrow());
        assertSame(destinationPrelude.fractionPrototype(),
                ((ProtosObjectValue) copiedImaginary).parent().orElseThrow());

        Object copiedZero = delivered.get(2);
        assertComplex(copiedZero, destinationPrelude);
        assertTrue(ProtosIdentity.identical(signedZero, copiedZero));
        assertEquals(Double.doubleToRawLongBits(-0.0), imaginaryBits(copiedZero));
    }

    @Test
    void parallelTransferUsesInertDescriptorsAndRematerializesInTheDestination()
            throws IOException {
        ProtosPrelude sourcePrelude = corePrelude();
        ProtosPrelude destinationPrelude = corePrelude();
        Object value = complex(
                divide(5, 7, sourcePrelude),
                com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerFromHost(TWO_POW_100.negate(), sourcePrelude),
                sourcePrelude);

        boolean[] records = new boolean[1];
        List<Object> captured = ProtosParallelRuntime.captureValuesForTesting(
                List.of(value, value), sourcePrelude.newModuleActivation(), records);
        assertTrue(records[0]);
        assertFalse(captured.get(0) instanceof ProtosObjectValue);
        assertTrue(ProtosComplexNumericSupport.isTransferDescriptor(captured.get(0)));
        assertSame(captured.get(0), captured.get(1));

        List<Object> delivered = ProtosParallelRuntime.materializeValuesForTesting(
                captured, destinationPrelude.newModuleActivation());
        assertSame(delivered.get(0), delivered.get(1));
        assertComplex(delivered.get(0), destinationPrelude);
        assertTrue(ProtosIdentity.identical(value, delivered.get(0)));
        assertSame(destinationPrelude.fractionPrototype(),
                ((ProtosObjectValue) ProtosComplexNumericSupport.real(delivered.get(0)))
                        .parent().orElseThrow());
    }

    @Test
    void otherSealedFamiliesStayNonTransferable() throws IOException {
        ProtosPrelude prelude = corePrelude();
        ProtosObjectValue template = new ProtosObjectValue(ProtosObjectValue.rootObject());
        ProtosSealedValue foreign = ProtosSealedValue.seal(new Object(), template, "state");

        assertThrows(ProtosSignalException.class, () -> ProtosActorValueTransfer.snapshotArguments(
                List.of(foreign), prelude.newModuleActivation()));
    }

    @Test
    void realArithmeticKeepsItsExistingPaths() throws IOException {
        ProtosPrelude prelude = corePrelude();
        Object sum = ProtosNumericValueSupport.addIntegers(
                new ProtosIntegerValue(1L), new ProtosIntegerValue(2L), prelude);
        assertInstanceOf(ProtosIntegerValue.class, sum);
        assertTrue(ProtosNumericValueSupport.isFraction(divide(1, 2, prelude)));
        for (Object real : List.of(sum, divide(1, 2, prelude), new ProtosFloatValue(3.0))) {
            assertFalse(ProtosComplexNumericSupport.isComplex(real));
            assertTrue(ProtosNumericValueSupport.isCurrentRealNumber(real));
        }
        // Without a Prelude the primitive path never mints, so it cannot mint a Complex either.
        assertThrows(NullPointerException.class, () -> complex(
                new ProtosIntegerValue(1L), new ProtosIntegerValue(1L), null));
    }


    @Test
    void mapAndIdentityMapRespectComplexKeys() throws IOException {
        ProtosPrelude prelude = corePrelude();
        ProtosActivation caller = prelude.newModuleActivation();

        Object exact = complex(
                new ProtosIntegerValue(1L), divide(1, 2, prelude), prelude);
        Object mathematicallyEqual = complex(
                new ProtosFloatValue(1.0), new ProtosFloatValue(0.5), prelude);
        Object identityTwin = complex(
                new ProtosIntegerValue(1L), divide(2, 4, prelude), prelude);

        Object positiveZero = complex(
                new ProtosIntegerValue(1L), new ProtosFloatValue(+0.0), prelude);
        Object negativeZero = complex(
                new ProtosIntegerValue(1L), new ProtosFloatValue(-0.0), prelude);
        Object realOne = new ProtosFloatValue(1.0);

        var map = new com.guillermomolina.protos.runtime.ProtosMapValue(
                prelude.mapPrototype());
        Object first = new ProtosStringValue("first");
        Object updated = new ProtosStringValue("updated");
        Object realValue = new ProtosStringValue("real");

        ProtosInvocation.invokeMessage(map, "atPut", List.of(exact, first), caller);

        assertSame(ProtosBooleanValue.TRUE, ProtosInvocation.invokeMessage(
                map, "containsKey", List.of(mathematicallyEqual), caller));
        assertSame(first, ProtosInvocation.invokeMessage(
                map, "at", List.of(mathematicallyEqual), caller));

        ProtosInvocation.invokeMessage(
                map, "atPut", List.of(mathematicallyEqual, updated), caller);
        assertSame(updated, ProtosInvocation.invokeMessage(
                map, "at", List.of(identityTwin), caller));
        assertEquals(1L, ((ProtosIntegerValue) ProtosInvocation.invokeMessage(
                map, "size", List.of(), caller)).longValue());

        ProtosInvocation.invokeMessage(
                map, "atPut", List.of(positiveZero, realValue), caller);
        assertSame(realValue, ProtosInvocation.invokeMessage(
                map, "at", List.of(negativeZero), caller));
        assertSame(realValue, ProtosInvocation.invokeMessage(
                map, "at", List.of(realOne), caller));
        assertEquals(2L, ((ProtosIntegerValue) ProtosInvocation.invokeMessage(
                map, "size", List.of(), caller)).longValue());

        var identityMap =
                new com.guillermomolina.protos.runtime.ProtosIdentityMapValue(
                        prelude.identityMapPrototype());

        ProtosInvocation.invokeMessage(
                identityMap, "atPut", List.of(exact, first), caller);
        assertSame(first, ProtosInvocation.invokeMessage(
                identityMap, "at", List.of(identityTwin), caller));
        assertSame(ProtosBooleanValue.FALSE, ProtosInvocation.invokeMessage(
                identityMap, "containsKey", List.of(mathematicallyEqual), caller));

        ProtosInvocation.invokeMessage(
                identityMap, "atPut", List.of(mathematicallyEqual, updated), caller);
        ProtosInvocation.invokeMessage(
                identityMap, "atPut", List.of(positiveZero, first), caller);

        assertSame(ProtosBooleanValue.FALSE, ProtosInvocation.invokeMessage(
                identityMap, "containsKey", List.of(negativeZero), caller));
        assertSame(ProtosBooleanValue.FALSE, ProtosInvocation.invokeMessage(
                identityMap, "containsKey", List.of(realOne), caller));

        ProtosInvocation.invokeMessage(
                identityMap, "atPut", List.of(negativeZero, updated), caller);
        ProtosInvocation.invokeMessage(
                identityMap, "atPut", List.of(realOne, realValue), caller);

        assertEquals(5L, ((ProtosIntegerValue) ProtosInvocation.invokeMessage(
                identityMap, "size", List.of(), caller)).longValue());
    }

    @Test
    void actorTransferPreservesComplexKeysInBothMapFamilies() throws IOException {
        ProtosPrelude sourcePrelude = corePrelude();
        ProtosPrelude destinationPrelude = sourcePrelude;
        ProtosActivation source = sourcePrelude.newModuleActivation();
        ProtosActivation destination = destinationPrelude.newModuleActivation();

        Object key = complex(
                com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerFromHost(TWO_POW_100, sourcePrelude),
                divide(1, 2, sourcePrelude),
                sourcePrelude);

        var map = new com.guillermomolina.protos.runtime.ProtosMapValue(
                sourcePrelude.mapPrototype());
        var identityMap =
                new com.guillermomolina.protos.runtime.ProtosIdentityMapValue(
                        sourcePrelude.identityMapPrototype());

        ProtosInvocation.invokeMessage(
                map, "atPut", List.of(key, new ProtosIntegerValue(10L)), source);
        ProtosInvocation.invokeMessage(
                identityMap, "atPut", List.of(key, new ProtosIntegerValue(20L)), source);

        List<Object> snapshot = ProtosActorValueTransfer.snapshotArguments(
                List.of(map, identityMap), source);
        assertTrue(ProtosActorValueTransfer.requiresMaterialization(snapshot));

        List<?> delivered = ProtosActorValueTransfer.materializeArguments(
                snapshot, destination);

        var deliveredMap =
                assertInstanceOf(com.guillermomolina.protos.runtime.ProtosMapValue.class,
                        delivered.get(0));
        var deliveredIdentityMap =
                assertInstanceOf(
                        com.guillermomolina.protos.runtime.ProtosIdentityMapValue.class,
                        delivered.get(1));

        Object copiedKey = deliveredMap.keyedSnapshot().get(0).key();
        assertComplex(copiedKey, destinationPrelude);
        assertSame(
                copiedKey, deliveredIdentityMap.keyedSnapshot().get(0).key());

        assertSame(destinationPrelude.bigIntegerPrototype(),
                ((ProtosObjectValue) ProtosComplexNumericSupport.real(copiedKey))
                        .parent().orElseThrow());
        assertSame(destinationPrelude.fractionPrototype(),
                ((ProtosObjectValue) ProtosComplexNumericSupport.imaginary(copiedKey))
                        .parent().orElseThrow());

        Object mathematicalTwin = complex(
                new ProtosFloatValue(0x1p100),
                new ProtosFloatValue(0.5),
                destinationPrelude);
        Object identityTwin = complex(
                com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerFromHost(TWO_POW_100, destinationPrelude),
                divide(2, 4, destinationPrelude),
                destinationPrelude);

        assertEquals(10L,
                ((ProtosIntegerValue) ProtosInvocation.invokeMessage(
                        deliveredMap, "at", List.of(mathematicalTwin), destination))
                        .longValue());
        assertEquals(20L,
                ((ProtosIntegerValue) ProtosInvocation.invokeMessage(
                        deliveredIdentityMap, "at", List.of(identityTwin), destination))
                        .longValue());
        assertSame(ProtosBooleanValue.FALSE, ProtosInvocation.invokeMessage(
                deliveredIdentityMap,
                "containsKey",
                List.of(mathematicalTwin),
                destination));
    }

    private static Object complex(Object real, Object imaginary, ProtosPrelude prelude) {
        return ProtosComplexNumericSupport.normalize(real, imaginary, prelude);
    }

    private static Object divide(long numerator, long denominator, ProtosPrelude prelude) {
        return ProtosNumericValueSupport.divideIntegersExactly(
                new ProtosIntegerValue(numerator), new ProtosIntegerValue(denominator), prelude);
    }

    private static long imaginaryBits(Object complex) {
        return Double.doubleToRawLongBits(assertInstanceOf(
                ProtosFloatValue.class, ProtosComplexNumericSupport.imaginary(complex)).value());
    }

    private static boolean recognizes(
            ProtosObjectValue prototype, Object candidate, ProtosActivation caller) {
        return ProtosInvocation.invokeMessage(prototype, "recognizes", List.of(candidate), caller)
                == ProtosBooleanValue.TRUE;
    }

    private static void assertComplex(Object value, ProtosPrelude owner) {
        assertTrue(ProtosComplexNumericSupport.isComplex(value));
        assertTrue(ProtosNumericValueSupport.isCurrentNumber(value));
        ProtosObjectValue object = assertInstanceOf(ProtosObjectValue.class, value);
        assertTrue(object.isFrozen());
        assertSame(owner.complexPrototype(), object.parent().orElseThrow());
    }

    private static void assertEquality(
            boolean expected, Object left, Object right, ProtosActivation caller) {
        assertEquals(expected, ProtosCurrentNumericRelations.numericEquals(left, right));
        assertEquals(expected, ProtosCurrentNumericRelations.numericEquals(right, left));
        assertEquals(ProtosBooleanValue.of(expected),
                ProtosInvocation.invokeMessage(left, "==", List.of(right), caller));
        assertEquals(ProtosBooleanValue.of(expected),
                ProtosInvocation.invokeMessage(right, "==", List.of(left), caller));
    }

    private static String normalHash(Object value, ProtosPrelude prelude) {
        return com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerDecimalText(
                ProtosCurrentNumericRelations.normalHash(value, prelude));
    }

    private static void assertSameNormalHash(Object left, Object right, ProtosPrelude prelude) {
        assertTrue(ProtosCurrentNumericRelations.numericEquals(left, right));
        assertEquals(normalHash(left, prelude), normalHash(right, prelude));
    }

    private static ProtosPrelude corePrelude() throws IOException {
        return new ProtosCoreBootstrap().bootstrap(Path.of("protos", "lib", "core"));
    }
}
