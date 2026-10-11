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
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.guillermomolina.protos.runtime.ProtosNumberLiteral;
import java.math.BigInteger;
import java.nio.file.Path;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;

/** I091-C2: numeric carriers remain internal to guarded arithmetic. */
final class ProtosI091PrimitiveIntegerSendTest {
    @Test
    void chainedArithmeticPreservesGuestResult() {
        try (Context context = context()) {
            assertEquals(5, context.eval(ProtosLanguage.ID, "((1 + 2) * 3) - 4").asInt());
        }
    }

    @Test
    void localsRemainOrdinaryGuestOperands() {
        try (Context context = context()) {
            assertEquals(11, context.eval(ProtosLanguage.ID, "a: 6\nb: 5\na + b").asInt());
        }
    }

    @Test
    void overflowPreservesExactArithmetic() {
        try (Context context = context()) {
            assertEquals(new BigInteger("9223372036854775808"),
                    context.eval(ProtosLanguage.ID, "9223372036854775807 + 1")
                            .asBigInteger());
        }
    }

    @Test
    void mixedFloatAndBooleanControlKeepOriginalSemantics() {
        try (Context context = context()) {
            assertEquals(3.5d, context.eval(ProtosLanguage.ID, "1 + 2.5").asDouble());
            assertEquals(3, context.eval(ProtosLanguage.ID, "true.ifTrue() { 1 + 2 }")
                    .asInt());
        }
    }

    @Test
    void exactQuotientStaysPrimitiveWhenIntegralAndFallsBackOtherwise() {
        try (Context context = context()) {
            // D197-C: integral quotients stay Integers through the carrier chain.
            assertEquals(12L, context.eval(ProtosLanguage.ID,
                    "i: 0\nacc: 0\n(() => i < 4).whileTrue() {\n"
                            + "    acc = acc + ((i * 6) / 3)\n"
                            + "    i = i + 1\n"
                            + "}\nacc").asLong());
            // One call site observes integral, Fraction, promoted and Error outcomes.
            assertTrue(context.eval(ProtosLanguage.ID,
                    "f: (a, b) => a / b\n"
                            + "first: SmallInteger.recognizes(f(6, 3)) && (f(6, 3) == 2)\n"
                            + "second: Fraction.recognizes(f(1, 3))\n"
                            + "third: BigInteger.recognizes(f(0 - 9223372036854775807 - 1, 0 - 1))\n"
                            + "fourth: SmallInteger.recognizes(f(6, 3))\n"
                            + "first && second && third && fourth").asBoolean());
        }
    }

    private static final BigInteger TWO_POW_63 = BigInteger.ONE.shiftLeft(63);

    @Test
    void literalParsingIsExactAtTheSignedLongBoundaryInEveryRadix() {
        assertLong(Long.MAX_VALUE, "9223372036854775807");
        assertLarge(TWO_POW_63, "9223372036854775808");
        assertLong(Long.MAX_VALUE, "0x7fffffffffffffff");
        assertLarge(TWO_POW_63, "0x8000000000000000");
        assertLarge(BigInteger.ONE.shiftLeft(64).subtract(BigInteger.ONE), "0xFFFF_FFFF_FFFF_FFFF");
        assertLong(Long.MAX_VALUE, "0b" + "1".repeat(63));
        assertLarge(TWO_POW_63, "0b1" + "0".repeat(63));
        assertLong(Long.MAX_VALUE, "0o777777777777777777777");
        assertLarge(TWO_POW_63, "0o1000000000000000000000");
        assertLong(0L, "0x0000000000000000000000");

        BigInteger huge = BigInteger.TEN.pow(60).add(BigInteger.valueOf(7));
        assertLarge(huge, huge.toString());
        assertLarge(huge, "0x" + huge.toString(16));
        assertLarge(huge, "0o" + huge.toString(8));
        assertLarge(huge, "0B" + huge.toString(2));
    }

    @Test
    void literalSeparatorsAndDigitsFollowTheGrammarExactly() {
        assertLong(1_000_000L, "1_000_000");
        assertLong(0xdeadbeefL, "0xdead_beef");
        assertLong(5L, "0b1_0_1");
        assertEquals(1500.25d, ProtosNumberLiteral.parse("1_500.2_5"));
        assertEquals(1.0e10d, ProtosNumberLiteral.parse("1e1_0"));
        assertEquals(2.5e-3d, ProtosNumberLiteral.parse("2.5E-3"));

        for (String malformed : new String[] {
                "", "_1", "1_", "1__0", "0x", "0x_1", "0b2", "0o8", "0xg", "1._5", "1.",
                "1e", "1e+", "1e_5", "1.5e5_", "\u0661", "1\u0662", "\uff11", "0x\uff11"}) {
            assertThrows(NumberFormatException.class,
                    () -> ProtosNumberLiteral.parse(malformed), malformed);
        }
    }

    @Test
    void overflowInsideArithmeticChainsStaysExact() {
        try (Context context = context()) {
            assertEquals(Long.MIN_VALUE,
                    context.eval(ProtosLanguage.ID, "-9223372036854775808").asLong());
            assertEquals(TWO_POW_63.negate().subtract(BigInteger.ONE),
                    context.eval(ProtosLanguage.ID, "-9223372036854775808 - 1").asBigInteger());
            assertEquals(TWO_POW_63.multiply(BigInteger.TWO),
                    context.eval(ProtosLanguage.ID, "(9223372036854775807 + 1) * 2")
                            .asBigInteger());
            assertEquals(Long.MAX_VALUE,
                    context.eval(ProtosLanguage.ID,
                            "((9223372036854775807 * 3) - 9223372036854775807)"
                                    + " - 9223372036854775807").asLong());
            assertEquals(BigInteger.ONE.shiftLeft(66),
                    context.eval(ProtosLanguage.ID, "0x1_0000_0000_0000_0000 * 4")
                            .asBigInteger());
        }
    }

    private static void assertLong(long expected, String spelling) {
        Long parsed = assertInstanceOf(Long.class, ProtosNumberLiteral.parse(spelling), spelling);
        assertEquals(expected, parsed.longValue(), spelling);
    }

    private static void assertLarge(BigInteger expected, String spelling) {
        assertEquals(expected,
                assertInstanceOf(BigInteger.class, ProtosNumberLiteral.parse(spelling), spelling),
                spelling);
    }

    private static Context context() {
        String core = Path.of("protos", "lib", "core").toAbsolutePath().toString();
        return Context.newBuilder(ProtosLanguage.ID).option("protos.CoreRoot", core).build();
    }
}
