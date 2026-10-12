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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.guillermomolina.protos.runtime.ProtosBooleanValue;
import com.guillermomolina.protos.runtime.ProtosClosureValue;
import com.guillermomolina.protos.runtime.ProtosFloatValue;
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

class ProtosStandardIntegerArithmeticTest {
    // Deliberately Java-side: this verifies the Core self-hosting/provenance
    // boundary, not observable arithmetic semantics.
    @Test
    void negatedRemainsSourceBackedOverNativeSubtraction() throws IOException {
        ProtosPrelude prelude = corePrelude();

        ProtosClosureValue negated =
                assertInstanceOf(
                        ProtosClosureValue.class,
                        prelude.integerPrototype().readLocalSlot("negated").orElseThrow());
        assertNotNull(negated.definition());
        assertTrue(negated.executionPlan().isPresent());
        assertTrue(negated.nativeBody().isEmpty());

        ProtosClosureValue subtraction =
                assertInstanceOf(
                        ProtosClosureValue.class,
                        prelude.integerPrototype().readLocalSlot("-").orElseThrow());
        assertTrue(subtraction.nativeBody().isPresent());
    }

    // Deliberately Java-side: this verifies that the public symbolic selector is
    // source-backed while native mod remains the representation primitive.
    @Test
    void percentRemainsSourceBackedOverNativeModWithoutHelperSlot() throws IOException {
        ProtosPrelude prelude = corePrelude();

        ProtosClosureValue percent =
                assertInstanceOf(
                        ProtosClosureValue.class,
                        prelude.integerPrototype().readLocalSlot("%").orElseThrow());
        assertNotNull(percent.definition());
        assertTrue(percent.executionPlan().isPresent());
        assertTrue(percent.nativeBody().isEmpty());
        assertTrue(!prelude.integerPrototype().hasLocalSlot("_coreIntegerPercent"));

        ProtosClosureValue mod =
                assertInstanceOf(
                        ProtosClosureValue.class,
                        prelude.integerPrototype().readLocalSlot("mod").orElseThrow());
        assertTrue(mod.nativeBody().isPresent());
    }

    @Test
    void canonicalNativeArithmeticRequiresExactInstalledClosureAndHome()
            throws IOException {
        ProtosPrelude prelude = corePrelude();

        for (String selector : new String[] {"+", "-", "*", "/", "div", "mod"}) {
            ProtosClosureValue selected =
                    assertInstanceOf(
                            ProtosClosureValue.class,
                            prelude.integerPrototype()
                                    .readLocalSlot(selector)
                                    .orElseThrow());

            ProtosStandardIntegerProtocol.CanonicalIntegerOperation operation =
                    ProtosStandardIntegerProtocol.canonicalOperationForSelection(
                            selected,
                            prelude.integerPrototype(),
                            selector,
                            prelude);

            assertNotNull(operation);
            assertEquals(selector, operation.selector());

            ProtosClosureValue copiedClosure =
                    ProtosClosureValue.nativeClosure(
                            selected.nativeBody().orElseThrow());
            assertNull(
                    ProtosStandardIntegerProtocol.canonicalOperationForSelection(
                            copiedClosure,
                            prelude.integerPrototype(),
                            selector,
                            prelude),
                    "sharing the same native body must not prove canonical selection");

            assertNull(
                    ProtosStandardIntegerProtocol.canonicalOperationForSelection(
                            selected,
                            prelude.numberPrototype(),
                            selector,
                            prelude));
            assertNull(
                    ProtosStandardIntegerProtocol.canonicalOperationForSelection(
                            selected,
                            prelude.integerPrototype(),
                            selector + "-alias",
                            prelude));
        }
    }

    @Test
    void directCanonicalExecutorPreservesBigIntegerResultsAndRejectsSlowPaths()
            throws IOException {
        ProtosPrelude prelude = corePrelude();
        BigInteger huge = BigInteger.TWO.pow(200).add(BigInteger.ONE);

        ProtosIntegerValue smallAdd =
                assertInstanceOf(
                        ProtosIntegerValue.class,
                        direct(
                                prelude,
                                "+",
                                new ProtosIntegerValue(40L),
                                new ProtosIntegerValue(2L)));
        assertEquals(42L, smallAdd.longValue());

        // I091: an exact result outside the signed-64 range is a large Integer object.
        ProtosLargeIntegerValue promotedAdd =
                assertInstanceOf(
                        ProtosLargeIntegerValue.class,
                        direct(
                                prelude,
                                "+",
                                new ProtosIntegerValue(Long.MAX_VALUE),
                                new ProtosIntegerValue(1L)));
        assertSame(prelude.bigIntegerPrototype(), promotedAdd.parent().orElseThrow());

        // D197-C: the canonical home stays Integer; a concrete-family home is never canonical.
        ProtosClosureValue canonicalAdd =
                assertInstanceOf(
                        ProtosClosureValue.class,
                        prelude.integerPrototype().readLocalSlot("+").orElseThrow());
        for (ProtosObjectValue concrete :
                List.of(prelude.smallIntegerPrototype(), prelude.bigIntegerPrototype())) {
            assertFalse(concrete.hasLocalSlot("+"));
            assertNull(
                    ProtosStandardIntegerProtocol.canonicalOperationForSelection(
                            canonicalAdd, concrete, "+", prelude));
        }
        // Demotion: a large result returning into signed-64 is a long-only Integer again.
        assertEquals(
                Long.MAX_VALUE,
                assertInstanceOf(
                                ProtosIntegerValue.class,
                                direct(prelude, "-", promotedAdd, new ProtosIntegerValue(1L)))
                        .longValue());
        assertEquals((BigInteger.valueOf(Long.MAX_VALUE).add(BigInteger.ONE)).toString(), com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerDecimalText(promotedAdd));

        ProtosLargeIntegerValue promotedDivision =
                assertInstanceOf(
                        ProtosLargeIntegerValue.class,
                        direct(
                                prelude,
                                "div",
                                new ProtosIntegerValue(Long.MIN_VALUE),
                                new ProtosIntegerValue(-1L)));
        assertEquals((BigInteger.valueOf(Long.MIN_VALUE).negate()).toString(), com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerDecimalText(promotedDivision));

        Object add =
                direct(prelude, "+",
                        com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerFromHost(huge, prelude),
                        new ProtosIntegerValue(1L));
        assertEquals((huge.add(BigInteger.ONE)).toString(), com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerDecimalText(add));

        Object multiply =
                direct(prelude, "*",
                        com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerFromHost(huge, prelude),
                        com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerFromHost(huge, prelude));
        assertEquals(String.valueOf(huge.multiply(huge)), com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerDecimalText(multiply));

        Object quotient =
                direct(prelude, "div",
                        com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerFromHost(huge, prelude),
                        new ProtosIntegerValue(7));
        assertEquals((huge.divide(BigInteger.valueOf(7))).toString(), com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerDecimalText(quotient));

        Object remainder =
                direct(prelude, "mod",
                        com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerFromHost(huge, prelude),
                        new ProtosIntegerValue(7));
        assertEquals((huge.remainder(BigInteger.valueOf(7))).toString(), com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerDecimalText(remainder));

        assertNull(
                direct(prelude, "+",
                        new ProtosIntegerValue(1L),
                        ProtosBooleanValue.TRUE));
        assertNull(
                direct(prelude, "div",
                        new ProtosIntegerValue(1L),
                        new ProtosIntegerValue(0L)));
        assertNull(
                direct(prelude, "mod",
                        new ProtosIntegerValue(1L),
                        new ProtosIntegerValue(0L)));
    }

    @Test
    void canonicalMixedArithmeticUsesOperandFirstBinary64()
            throws IOException {
        ProtosPrelude prelude = corePrelude();

        ProtosFloatValue rounded = assertInstanceOf(
                ProtosFloatValue.class,
                direct(prelude, "+",
                        new ProtosIntegerValue(9007199254740993L),
                        new ProtosFloatValue(-9007199254740992.0)));
        assertEquals(0.0d, rounded.value());

        ProtosFloatValue subtraction = assertInstanceOf(
                ProtosFloatValue.class,
                direct(prelude, "-",
                        new ProtosIntegerValue(7L),
                        new ProtosFloatValue(2.5)));
        assertEquals(4.5d, subtraction.value());

        ProtosFloatValue divideByFloatZero = assertInstanceOf(
                ProtosFloatValue.class,
                direct(prelude, "/",
                        new ProtosIntegerValue(1L),
                        new ProtosFloatValue(0.0)));
        assertEquals(Double.POSITIVE_INFINITY, divideByFloatZero.value());

        BigInteger huge = BigInteger.ONE.shiftLeft(1024);
        ProtosFloatValue invalidProduct = assertInstanceOf(
                ProtosFloatValue.class,
                direct(prelude, "*",
                        com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerFromHost(huge, prelude),
                        new ProtosFloatValue(0.0)));
        assertTrue(Double.isNaN(invalidProduct.value()));

        assertNull(direct(prelude, "div",
                new ProtosIntegerValue(7L),
                new ProtosFloatValue(2.0)));
        assertNull(direct(prelude, "mod",
                new ProtosIntegerValue(7L),
                new ProtosFloatValue(2.0)));
        assertNull(direct(prelude, "/",
                new ProtosIntegerValue(7L),
                new ProtosIntegerValue(0L)));
    }

    @Test
    void canonicalExactDivideNormalizesWithoutFloat() throws IOException {
        ProtosPrelude prelude = corePrelude();

        // D197-C: an integral quotient stays a signed-64 Integer, never a Float or Fraction.
        ProtosIntegerValue integral = assertInstanceOf(
                ProtosIntegerValue.class,
                direct(prelude, "/", new ProtosIntegerValue(-6L), new ProtosIntegerValue(3L)));
        assertEquals(-2L, integral.longValue());

        Object half = direct(prelude, "/",
                new ProtosIntegerValue(2L), new ProtosIntegerValue(-4L));
        assertTrue(ProtosNumericValueSupport.isFraction(half));
        assertEquals(-1L, ((ProtosIntegerValue)
                ProtosNumericValueSupport.fractionNumerator(half)).longValue());
        assertEquals(2L, ((ProtosIntegerValue)
                ProtosNumericValueSupport.fractionDenominator(half)).longValue());
        assertSame(prelude.fractionPrototype(), ((ProtosObjectValue) half).parent().orElseThrow());

        // Long.MIN_VALUE / -1 promotes to the canonical BigInteger 2^63.
        assertInstanceOf(
                ProtosLargeIntegerValue.class,
                direct(prelude, "/",
                        new ProtosIntegerValue(Long.MIN_VALUE), new ProtosIntegerValue(-1L)));

        // Without a Prelude a Fraction result stays on the ordinary path.
        ProtosClosureValue selected = assertInstanceOf(
                ProtosClosureValue.class,
                prelude.integerPrototype().readLocalSlot("/").orElseThrow());
        assertNull(ProtosStandardIntegerProtocol.tryExecuteCanonicalOperationOne(
                ProtosStandardIntegerProtocol.canonicalOperationForSelection(
                        selected, prelude.integerPrototype(), "/", prelude),
                new ProtosIntegerValue(1L),
                new ProtosIntegerValue(3L),
                null));
    }

    private static Object direct(
            ProtosPrelude prelude,
            String selector,
            Object receiver,
            Object argument) {
        ProtosClosureValue selected =
                assertInstanceOf(
                        ProtosClosureValue.class,
                        prelude.integerPrototype()
                                .readLocalSlot(selector)
                                .orElseThrow());
        ProtosStandardIntegerProtocol.CanonicalIntegerOperation operation =
                ProtosStandardIntegerProtocol.canonicalOperationForSelection(
                        selected,
                        prelude.integerPrototype(),
                        selector,
                        prelude);
        assertNotNull(operation);
        return ProtosStandardIntegerProtocol.tryExecuteCanonicalOperation(
                operation,
                receiver,
                new Object[] {argument},
                prelude);
    }

    private static ProtosPrelude corePrelude() throws IOException {
        return new ProtosCoreBootstrap().bootstrap(Path.of("protos", "lib", "core"));
    }
}
