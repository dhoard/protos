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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.guillermomolina.protos.runtime.ProtosFloatValue;
import com.guillermomolina.protos.runtime.ProtosIntegerValue;
import com.guillermomolina.protos.runtime.ProtosNumericValueSupport;
import java.math.BigInteger;
import java.nio.file.Path;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Engine;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

/**
 * I091: primitive carriers retained by frame storage and direct source-call
 * arguments are observed only as ordinary guest Integer/Float values.
 */
final class ProtosI091PrimitiveCarrierPropagationTest {
    @Test
    void definitionsAndAssignmentsRetainCarriers() {
        try (Context context = context()) {
            assertEquals(30, eval(context, "a: 1 + 2\na * 10").asInt());
            assertEquals(2, eval(context, "a: 0\na = a + 1\na = a + 1\na").asInt());
            assertEquals(7, eval(context, "a: 3 + 4\nb: a\nb").asInt());
            assertEquals(4.0d, eval(context, "a: 1.5 + 2.5\na").asDouble());
        }
    }

    @Test
    void overflowFromRetainedCarrierStaysExact() {
        try (Context context = context()) {
            assertEquals(new BigInteger("9223372036854775808"),
                    eval(context, "a: 9223372036854775806 + 1\nb: a + 1\nb").asBigInteger());
        }
    }

    @Test
    void loopCounterCapturedByCallbacks() {
        try (Context context = context()) {
            assertEquals(4950, eval(context,
                    "total: 0\ni: 0\n"
                            + "(() => i < 100).whileTrue(() => {\n"
                            + "    total = total + i\n"
                            + "    i = i + 1\n"
                            + "})\n"
                            + "total").asInt());
        }
    }

    @Test
    void escapingClosureObservesGuestValue() {
        try (Context context = context()) {
            assertEquals(5, eval(context, "a: 2 + 3\nf: () => a\nf()").asInt());
            assertEquals(6, eval(context, "a: 2 + 3\nf: () => a + 1\nf()").asInt());
        }
    }

    @Test
    void directSourceArgumentsAndReturns() {
        try (Context context = context()) {
            assertEquals(42, eval(context,
                    "o: { twice: x => x + x }\nn: 20 + 1\no.twice(n)").asInt());
            assertEquals(3, eval(context,
                    "o: { half: x => x / 2 }\no.half(3 + 3)").asInt());
            assertEquals(12, eval(context,
                    "o: { keep: x => {\n y: x\n y } }\no.keep(5 + 7)").asInt());
        }
    }

    @Test
    void directSourceReturnsReachTheCallerAndTheHost() {
        try (Context context = context()) {
            // The callee's final arithmetic result returns to an accepting caller operand.
            assertEquals(7, eval(context, "o: { inc: x => x + 1 }\no.inc(5) + 1").asInt());
            // A returned value stored and fed back through a loop.
            assertEquals(10, eval(context,
                    "o: { inc: x => x + 1 }\nn: 0\ni: 0\n"
                            + "(() => i < 10).whileTrue(() => {\n"
                            + "    n = o.inc(n)\n"
                            + "    i = i + 1\n"
                            + "})\n"
                            + "n").asInt());
            // A final retained read returns as a guest value to a non-accepting caller.
            assertEquals(42, eval(context, "o: { id: x => {\n y: x + 0\n y } }\no.id(42)").asInt());
            assertEquals(1.5d, eval(context, "o: { half: x => x / 2.0 }\no.half(3)").asDouble());
            // Overflow inside the callee still returns the exact result.
            assertEquals(new BigInteger("9223372036854775808"), eval(context,
                    "o: { inc: x => x + 1 }\no.inc(9223372036854775807)").asBigInteger());
        }
    }


    @Test
    void call1RetainsPrimitiveResultsAndRestoresObservableValues() {
        try (Context context = context()) {
            assertEquals(42, eval(context,
                    "identity: x => x\n"
                    + "increment: x => x + 1\n"
                    + "increment(identity(40 + 1))").asInt());

            assertEquals(2.5d, eval(context,
                    "identity: x => x\n"
                    + "identity(1.25 + 0.25) + 1.0").asDouble());

            assertEquals(
                    new BigInteger("9223372036854775808"),
                    eval(context,
                            "identity: x => x\n"
                            + "identity(9223372036854775807) + 1")
                            .asBigInteger());

            assertEquals("guest", eval(context,
                    "identity: x => x\n"
                    + "identity(\"guest\")").asString());

            assertTrue(eval(context,
                    "identity: x => x\n"
                    + "a: identity(41 + 1)\n"
                    + "(a === 42) && (a.parent() === SmallInteger)")
                    .asBoolean());
        }
    }

    @Test
    void send0RetainsNumericResultsAndMaterializesGenericResults() {
        try (Context context = context()) {
            assertEquals(8, eval(context,
                    "o: { next: () => 3 + 4 }\n"
                    + "o.next() + 1").asInt());

            assertEquals(2.5d, eval(context,
                    "o: { next: () => 1.25 + 0.25 }\n"
                    + "o.next() + 1.0").asDouble());

            assertEquals(
                    new BigInteger("9223372036854775808"),
                    eval(context,
                            "o: { next: () => 9223372036854775807 + 1 }\n"
                            + "o.next()").asBigInteger());

            assertEquals("ordinary", eval(context,
                    "o: { next: () => \"ordinary\" }\n"
                    + "o.next()").asString());

            assertTrue(eval(context,
                    "o: { next: () => 41 + 1 }\n"
                    + "a: o.next()\n"
                    + "(a === 42) && (a.parent() === SmallInteger)")
                    .asBoolean());

            assertEquals(44, eval(context,
                    "o: { next: () => 40 + 2 }\n"
                    + "identity: x => x\n"
                    + "identity(o.next()) + 2").asInt());
        }
    }

    @Test
    void retainedCarriersKeepValueIdentityAndEquality() {
        try (Context context = context()) {
            assertTrue(eval(context, "a: 1 + 2\nb: 3\na === b").asBoolean());
            assertTrue(eval(context, "a: 1.5 + 1.5\na == 3").asBoolean());
            assertFalse(eval(context, "a: 0.0 * (0.0 - 1.0)\nb: 0.0\na === b").asBoolean());
        }
    }

    private static final BigInteger TWO_POW_63 = BigInteger.ONE.shiftLeft(63);

    @Test
    void largeResultsCrossCallsCapturesAndLocalsExactly() {
        try (Context context = context()) {
            // Overflow in the callee, returned to an accepting caller operand.
            assertEquals(TWO_POW_63.add(BigInteger.ONE), eval(context,
                    "o: { inc: x => x + 1 }\no.inc(9223372036854775807) + 1").asBigInteger());
            // A large callee result brought back into the signed-64 range by the caller.
            assertEquals(Long.MAX_VALUE, eval(context,
                    "o: { inc: x => x + 1 }\no.inc(9223372036854775807) - 1").asLong());
            // A captured large Integer, then arithmetic in the closure.
            assertEquals(TWO_POW_63.shiftLeft(1), eval(context,
                    "a: 9223372036854775807 + 1\nf: () => a + a\nf()").asBigInteger());
            // Assignment of an overflowing carrier chain to a local, then arithmetic.
            assertEquals(TWO_POW_63.negate().subtract(BigInteger.ONE), eval(context,
                    "a: 0 - 9223372036854775807\na = a - 1\na = a - 1\na").asBigInteger());
            assertEquals(Long.MIN_VALUE, eval(context,
                    "a: 0 - 9223372036854775807\na = a - 1\na = a - 1\na + 1").asLong());
            // An escaping closure keeps observing the large guest value it captured.
            assertEquals(TWO_POW_63.multiply(BigInteger.valueOf(3)), eval(context,
                    "make: () => {\n    big: 9223372036854775807 + 1\n    () => big * 3\n}\n"
                            + "g: make()\ng()").asBigInteger());
            assertTrue(eval(context,
                    "make: () => {\n    big: 9223372036854775807 + 1\n    () => big\n}\n"
                            + "make()().parent() === BigInteger").asBoolean());
        }
    }

    @Test
    void directAndLaterMaterializedResultsAreIndistinguishable() {
        try (Context context = context()) {
            assertTrue(eval(context,
                    "o: { inc: x => x + 1 }\n"
                            + "viaCarrier: o.inc(9223372036854775806)\n"
                            + "direct: 9223372036854775807\n"
                            + "(viaCarrier === direct) && (viaCarrier.hash() == direct.hash())")
                    .asBoolean());
            assertTrue(eval(context,
                    "o: { inc: x => x + 1 }\n"
                            + "viaCarrier: o.inc(9223372036854775807)\n"
                            + "direct: 9223372036854775808\n"
                            + "(viaCarrier === direct) && (viaCarrier.hash() == direct.hash())")
                    .asBoolean());
        }
    }

    @Test
    void largeResultsAreRematerializedByEachExecutingDomain() {
        String core = Path.of("protos", "lib", "core").toAbsolutePath().toString();
        Source source = Source.create(ProtosLanguage.ID,
                "o: { inc: x => x + 1 }\n"
                        + "big: o.inc(9223372036854775807)\n"
                        + "(big.parent() === BigInteger) && (big - 1 == 9223372036854775807)");
        try (Engine engine = Engine.newBuilder(ProtosLanguage.ID)
                        .option("protos.CoreRoot", core)
                        .option("engine.WarnInterpreterOnly", "false")
                        .build();
                Context left = Context.newBuilder(ProtosLanguage.ID).engine(engine).build();
                Context right = Context.newBuilder(ProtosLanguage.ID).engine(engine).build()) {
            for (int round = 0; round < 3; round++) {
                assertTrue(left.eval(source).asBoolean());
                assertTrue(right.eval(source).asBoolean());
            }
        }
    }

    @Test
    void warmedPrimitiveSiteSurvivesARejectedIntegerOverride() {
        try (Context context = context()) {
            // D049: the Integer prototype is frozen, so the selection a warmed primitive site
            // relies on cannot be replaced; the rejected attempt leaves the site canonical.
            assertTrue(eval(context,
                    "add: (x, y) => x + y\n"
                            + "i: 0\n"
                            + "(() => i < 50).whileTrue(() => { i = add(i, 1) })\n"
                            + "rejected: false\n"
                            + "Error.handle(() => { Integer.removeSlot(\"+\") }, error => {\n"
                            + "    rejected = true\n"
                            + "})\n"
                            + "rejected && (add(i, 1) == 51)"
                            + " && (add(9223372036854775807, 1) == 9223372036854775808)")
                    .asBoolean());
        }
    }

    @Test
    void numericProxyRetainsCanonicalDispatchAcrossGenericMisses() {
        try (Context context = context()) {
            // All calls execute the same syntactic x + y site.
            // The first and later large operands require the D013 path;
            // small and mixed operands must remain semantically canonical.
            assertTrue(eval(context,
                    "add: (x, y) => x + y\n"
                            + "bigFirst: add(9223372036854775808, 1)\n"
                            + "i: 0\n"
                            + "(() => i < 50).whileTrue(() => { i = add(i, 1) })\n"
                            + "rejected: false\n"
                            + "Error.handle(() => { add(1, \"text\") }, error => {\n"
                            + "    rejected = true\n"
                            + "})\n"
                            + "afterMiss: add(i, 1)\n"
                            + "mixed: add(1.5, 2)\n"
                            + "bigAgain: add(9223372036854775808, 2)\n"
                            + "last: add(10, 20)\n"
                            + "rejected && (bigFirst == 9223372036854775809)"
                            + " && (afterMiss == 51) && (mixed == 3.5)"
                            + " && (bigAgain == 9223372036854775810)"
                            + " && (last == 30)")
                    .asBoolean());
        }
    }

    @Test
    void carrierMaterializationIsTheOnlyRecognition() {
        Object integer = ProtosNumericValueSupport.guestValue(41L);
        Object floating = ProtosNumericValueSupport.guestValue(0.5d);
        assertEquals(41L, ((ProtosIntegerValue) integer).longValue());
        assertEquals(0.5d, ((ProtosFloatValue) floating).value());
        Object other = new Object();
        assertSame(other, ProtosNumericValueSupport.guestValue(other));
        // A host Long is never itself a current Protos number.
        assertFalse(ProtosNumericValueSupport.isCurrentNumber(41L));
    }

    private static org.graalvm.polyglot.Value eval(Context context, String source) {
        return context.eval(ProtosLanguage.ID, source);
    }

    private static Context context() {
        String core = Path.of("protos", "lib", "core").toAbsolutePath().toString();
        return Context.newBuilder(ProtosLanguage.ID).option("protos.CoreRoot", core).build();
    }
}
