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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.guillermomolina.protos.runtime.ProtosClosureValue;
import com.guillermomolina.protos.runtime.ProtosPrelude;
import java.nio.file.Path;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;

/** I091: guarded primitive ordering and Integer quotient in the carrier chain. */
final class ProtosI091PrimitiveOrderingTest {
    @Test
    void integerAndFloatOrderingOverCarriers() {
        try (Context context = context()) {
            assertTrue(eval(context, "1 < 2"));
            assertFalse(eval(context, "2 < 2"));
            assertTrue(eval(context, "2 <= 2"));
            assertTrue(eval(context, "(1 + 2) > 2"));
            assertTrue(eval(context, "(1 + 2) >= (6 / 2)"));
            assertTrue(eval(context, "1.5 < 2"));
            assertTrue(eval(context, "2 > 1.5"));
            assertTrue(eval(context, "0.0 <= (0.0 * (0.0 - 1.0))"));
            assertFalse(eval(context, "(0.0 * (0.0 - 1.0)) < 0.0"));
        }
    }

    @Test
    void unorderedAndLargeValuesRemainExact() {
        try (Context context = context()) {
            assertFalse(eval(context, "(0.0 / 0.0) < 1"));
            assertFalse(eval(context, "1 >= (0.0 / 0.0)"));
            // 2^63 - 1 rounds to 2^63 as binary64; the exact comparison still orders them.
            assertTrue(eval(context, "9223372036854775807 < 9223372036854775807.0"));
            assertTrue(eval(context, "9007199254740993 > 9007199254740992.0"));
            // Overflowed operands leave the carrier chain and use the exact fallback.
            assertTrue(eval(context, "(9223372036854775807 + 1) > 9223372036854775807"));
        }
    }

    @Test
    void integerQuotientIsCanonicalExactRational() {
        try (Context context = context()) {
            // D197-C: an integral quotient stays an Integer; a nonintegral one is a Fraction.
            assertEquals(-2L, context.eval(ProtosLanguage.ID, "(0 - 6) / 3").asLong());
            assertTrue(eval(context, "SmallInteger.recognizes(0 / (0 - 5))"));
            assertTrue(eval(context, "Fraction.recognizes(5 / 2)"));
            assertTrue(eval(context, "BigInteger.recognizes((0 - 9223372036854775807 - 1) / (0 - 1))"));
            // Explicit Float conversion rounds the whole exact quotient once.
            assertEquals(1.0d / 3.0d, context.eval(ProtosLanguage.ID, "Float(1 / 3)").asDouble());
            assertEquals(4611686018427387904.0d,
                    context.eval(ProtosLanguage.ID,
                            "Float(9223372036854775807 / 2)").asDouble());
        }
    }

    @Test
    void removedRetainedLocalFollowsD179FallbackAndNeverItsStaleCarrier() {
        try (Context context = context()) {
            // The removed value is observed as a guest Integer, not a frame carrier.
            assertTrue(eval(context,
                    "x: 100\n"
                            + "f: () => {\n"
                            + "    x: 1 + 2\n"
                            + "    y: x + 1\n"
                            + "    removed: context.removeSlot(\"x\")\n"
                            + "    (removed.parent() === SmallInteger) && (removed == 3) && (x + y == 104)\n"
                            + "}\n"
                            + "f()"));
            // A captured read taken before and after the removal.
            assertTrue(eval(context,
                    "x: 100\n"
                            + "f: () => {\n"
                            + "    x: 9223372036854775806 + 1\n"
                            + "    g: () => x + 0\n"
                            + "    first: g()\n"
                            + "    context.removeSlot(\"x\")\n"
                            + "    (first == 9223372036854775807) && (g() == 100)\n"
                            + "}\n"
                            + "f()"));
            // Without any fallback binding the read is an ordinary lookup Error.
            assertTrue(eval(context,
                    "f: () => {\n"
                            + "    lonely: 1 + 2\n"
                            + "    context.removeSlot(\"lonely\")\n"
                            + "    failed: false\n"
                            + "    Error.handle(() => { lonely + 1 }, error => { failed = true })\n"
                            + "    failed\n"
                            + "}\n"
                            + "f()"));
        }
    }

    @Test
    void extremeAndSpecialOperandsOrderExactlyOverCarriers() {
        try (Context context = context()) {
            assertTrue(eval(context,
                    "low: 0 - 9223372036854775807 - 1\n"
                            + "high: 9223372036854775806 + 1\n"
                            + "(low < high) && (low <= low) && (high >= high) && !(high < low)"));
            assertTrue(eval(context,
                    "high: 9223372036854775806 + 1\n(high < (high + 1)) && ((high + 1) > high)"));
            assertTrue(eval(context,
                    "n: 0.0 / 0.0\n!(n < n) && !(n <= n) && !(n > 1) && !(1 >= n)"));
            assertTrue(eval(context,
                    "z: 0.0 * (0.0 - 1.0)\n"
                            + "!(z < 0.0) && (z <= 0.0) && (z >= 0.0) && !(z < 0)"
                            + " && ((1.0 / z) < (0.0 - 1.0e308))"));
        }
    }

    @Test
    void carrierAndGuestOperandsCompareAlike() {
        try (Context context = context()) {
            assertTrue(eval(context,
                    "a: 1 + 2\n(a == 3) && (3 == a) && (a === 3) && (a.parent() === SmallInteger)"));
            assertTrue(eval(context,
                    "a: 0.5 + 1\n(a == 1.5) && (1.5 == a) && (a.parent() === Float)"));
            assertTrue(eval(context,
                    "a: 2 + 1\nb: 2.5 + 0.5\n(a <= b) && (b <= a) && !(a < b)"));
        }
    }

    @Test
    void nonNumericOperandLeavesTheCarrierChainForOrdinaryDispatch() {
        try (Context context = context()) {
            assertTrue(eval(context,
                    "a: 1 + 2\n"
                            + "failed: false\n"
                            + "Error.handle(() => { a < \"text\" }, error => { failed = true })\n"
                            + "failed && (a < 4)"));
            assertTrue(eval(context,
                    "o: { less: (x, y) => x < y }\n"
                            + "(o.less(1, 2) && !o.less(2.5, 2) && o.less(1, 9223372036854775808))"));
        }
    }

    @Test
    void exactCanonicalOrderingClosureIsNotAnAliasOrCopiedNativeBody()
            throws Exception {
        ProtosPrelude prelude = new ProtosCoreBootstrap().bootstrap(
                Path.of("protos", "lib", "core"));

        for (String selector : new String[] {"<", "<=", ">", ">="}) {
            ProtosClosureValue selected = (ProtosClosureValue)
                    prelude.numberPrototype().readLocalSlot(selector).orElseThrow();

            ProtosStandardNumberOrderingProtocol.Relation relation =
                    ProtosStandardNumberOrderingProtocol.canonicalRelationForSelection(
                            selected, prelude.numberPrototype(), selector, prelude);
            assertSame(relation,
                    ProtosStandardNumberOrderingProtocol.canonicalRelationForSelection(
                            selected, prelude.numberPrototype(), selector, prelude));
            assertTrue(relation != null);
            assertNull(
                    ProtosStandardNumberOrderingProtocol.canonicalRelationForSelection(
                            ProtosClosureValue.nativeClosure(
                                    selected.nativeBody().orElseThrow()),
                            prelude.numberPrototype(), selector, prelude));
            assertNull(
                    ProtosStandardNumberOrderingProtocol.canonicalRelationForSelection(
                            selected, prelude.integerPrototype(), selector, prelude));
            assertNull(
                    ProtosStandardNumberOrderingProtocol.canonicalRelationForSelection(
                            selected, prelude.numberPrototype(),
                            "<".equals(selector) ? ">" : "<", prelude));
        }
    }

    private static boolean eval(Context context, String source) {
        return context.eval(ProtosLanguage.ID, source).asBoolean();
    }

    private static Context context() {
        String core = Path.of("protos", "lib", "core").toAbsolutePath().toString();
        return Context.newBuilder(ProtosLanguage.ID).option("protos.CoreRoot", core).build();
    }
}
