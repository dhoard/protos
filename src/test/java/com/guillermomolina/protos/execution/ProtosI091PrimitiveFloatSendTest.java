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
 * WITHOUT WARRANTY OF ANY KIND, either express OR IMPLIED. See the License for
 * the specific language governing rights and limitations under the License.
 */
package com.guillermomolina.protos.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.guillermomolina.protos.runtime.ProtosClosureValue;
import com.guillermomolina.protos.runtime.ProtosPrelude;
import java.nio.file.Path;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;

final class ProtosI091PrimitiveFloatSendTest {
    private static final String CORE =
            Path.of("protos", "lib", "core").toAbsolutePath().toString();

    @Test
    void composedFloatArithmeticAndGuestResult() {
        try (Context context = context()) {
            assertEquals(6.5d,
                    context.eval(ProtosLanguage.ID,
                            "((1.25 + 2.5) * 2.0) - 1.0").asDouble());
            assertEquals(1.25d,
                    context.eval(ProtosLanguage.ID, "2.5 / 2.0").asDouble());
            assertEquals(2.0d,
                    context.eval(ProtosLanguage.ID, "0.0 + 2.0").asDouble());
        }
    }

    @Test
    void operandFirstMixedArithmeticPreservesBothReceiverFamilies() {
        try (Context context = context()) {
            assertEquals(3.5d,
                    context.eval(ProtosLanguage.ID, "1 + 2.5").asDouble());
            assertEquals(3.5d,
                    context.eval(ProtosLanguage.ID, "2.5 + 1").asDouble());
            assertEquals(1.25d,
                    context.eval(ProtosLanguage.ID, "2.5 / 2").asDouble());
            assertEquals(6.0d,
                    context.eval(ProtosLanguage.ID, "(1 + 2.0) * 2").asDouble());
            assertEquals(2.0d,
                    context.eval(ProtosLanguage.ID, "(1 + 2) / 1.5").asDouble());
        }
    }

    @Test
    void negativeZeroAndIntegerOverflowRemainExact() {
        try (Context context = context()) {
            double minusZero = context.eval(
                    ProtosLanguage.ID, "0.0 * (0.0 - 1.0)").asDouble();
            assertEquals(Double.doubleToRawLongBits(-0.0d),
                    Double.doubleToRawLongBits(minusZero));
            assertEquals(9223372036854775808.0d,
                    context.eval(ProtosLanguage.ID,
                            "9223372036854775807 + 1.0").asDouble());
        }
    }

    @Test
    void exactCanonicalFloatClosureIsNotAnAliasOrCopiedNativeBody()
            throws Exception {
        ProtosPrelude prelude = new ProtosCoreBootstrap().bootstrap(
                Path.of("protos", "lib", "core"));

        for (String selector : new String[] {"+", "-", "*", "/"}) {
            ProtosClosureValue selected = (ProtosClosureValue)
                    prelude.floatPrototype().readLocalSlot(selector).orElseThrow();
            ProtosStandardFloatProtocol.CanonicalFloatOperation expected =
                    ProtosStandardFloatProtocol.CanonicalFloatOperation
                            .forSelector(selector);

            assertSame(expected,
                    ProtosStandardFloatProtocol.canonicalOperationForSelection(
                            selected, prelude.floatPrototype(), selector, prelude));
            assertNull(
                    ProtosStandardFloatProtocol.canonicalOperationForSelection(
                            ProtosClosureValue.nativeClosure(
                                    selected.nativeBody().orElseThrow()),
                            prelude.floatPrototype(), selector, prelude));
            assertNull(
                    ProtosStandardFloatProtocol.canonicalOperationForSelection(
                            selected, prelude.numberPrototype(), selector, prelude));
        }
    }


    @Test
    void allPrimitiveFloatOperations() {
        try (Context context = context()) {
            assertEquals(9.5d, evaluate(context, "7.5 + 2.0"));
            assertEquals(5.5d, evaluate(context, "7.5 - 2.0"));
            assertEquals(15.0d, evaluate(context, "7.5 * 2.0"));
            assertEquals(3.75d, evaluate(context, "7.5 / 2.0"));

            assertEquals(
                    10.0d,
                    evaluate(context, "((2.5 + 1.5) * 3.0) - 2.0"));
        }
    }

    @Test
    void primitiveMixedOperationsRespectBothOperandOrders() {
        try (Context context = context()) {
            assertEquals(7.0d, evaluate(context, "5.0 + 2"));
            assertEquals(7.0d, evaluate(context, "5 + 2.0"));

            assertEquals(3.0d, evaluate(context, "5.0 - 2"));
            assertEquals(3.0d, evaluate(context, "5 - 2.0"));

            assertEquals(10.0d, evaluate(context, "5.0 * 2"));
            assertEquals(10.0d, evaluate(context, "5 * 2.0"));

            assertEquals(2.5d, evaluate(context, "5.0 / 2"));
            assertEquals(2.5d, evaluate(context, "5 / 2.0"));

            assertEquals(-3.0d, evaluate(context, "2.0 - 5"));
            assertEquals(3.0d, evaluate(context, "5 - 2.0"));
            assertEquals(0.4d, evaluate(context, "2 / 5.0"));
        }
    }

    @Test
    void ieeeExceptionalArithmeticPreservesSignedZeroAndNaN() {
        try (Context context = context()) {
            double negativeZero =
                    evaluate(context, "0.0 / (0.0 - 1.0)");
            assertEquals(
                    Double.doubleToRawLongBits(-0.0d),
                    Double.doubleToRawLongBits(negativeZero));

            assertEquals(
                    Double.POSITIVE_INFINITY,
                    evaluate(context, "1.0 / 0.0"));
            assertEquals(
                    Double.NEGATIVE_INFINITY,
                    evaluate(
                            context,
                            "1.0 / (0.0 * (0.0 - 1.0))"));

            assertTrue(Double.isNaN(
                    evaluate(context, "0.0 / 0.0")));
            assertTrue(Double.isNaN(
                    evaluate(context, "(1.0 / 0.0) * 0.0")));
        }
    }

    @Test
    void largeIntegersAndFractionsPreserveFallbackConversion() {
        try (Context context = context()) {
            assertEquals(
                    0x1.0p63,
                    evaluate(context, "9223372036854775807 + 0.0"));
            assertEquals(
                    -0x1.0p63,
                    evaluate(context, "-9223372036854775808 + 0.0"));

            assertEquals(
                    0x1.0p63,
                    evaluate(context, "9223372036854775808 + 0.0"));
            assertEquals(
                    0x1.0p63,
                    evaluate(context, "0.0 + 9223372036854775808"));

            assertEquals(
                    2.0d + (1.0d / 3.0d),
                    evaluate(context, "2.0 + (1 / 3)"));
            assertEquals(
                    2.0d / (1.0d / 3.0d),
                    evaluate(context, "2.0 / (1 / 3)"));
        }
    }

    @Test
    void polymorphicCallSiteAlternatesNumericShapes() {
        try (Context context = context()) {
            String source =
                    "sum: (a, b) => a + b\n"
                    + "sum(1.0, 2.0) + sum(1.0, 2)"
                    + " + sum(1, 2.0) + sum(1.0, 2.0)";

            assertEquals(12.0d, evaluate(context, source));
        }
    }

    private static double evaluate(Context context, String source) {
        return context.eval(ProtosLanguage.ID, source).asDouble();
    }

    private static Context context() {
        return Context.newBuilder(ProtosLanguage.ID)
                .option("protos.CoreRoot", CORE).build();
    }
}
