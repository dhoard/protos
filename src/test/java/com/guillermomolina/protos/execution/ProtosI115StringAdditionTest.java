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

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;
import org.junit.jupiter.api.Test;

/**
 * I115: binary {@code +} over the Bytecode DSL operations.
 * Every {@code +} site is a NumberAdd operation that verifies the selected
 * behavior itself: a numeric receiver, or a String receiver whose selected
 * {@code +} is the standard String concatenation, executes natively; every
 * other receiver invokes its selected implementation through the shared
 * dispatcher, and a non-terminal site composes a suspended invocation with
 * Yield.
 */
final class ProtosI115StringAdditionTest {
    private static final Path CORE = Path.of("protos", "lib", "core");
    private static final Path OUTPUT = Path.of("target", "i115-string-addition");
    private static final String TRACE = "trace: { value: 0 }\n"
            + "step: (digit, value) => {\n"
            + "    trace.value = trace.value * 10 + digit\n"
            + "    value\n"
            + "}\n";
    private static final String LOGGING_PLUS = "log: {\n"
            + "    calls: 0\n"
            + "    argument: null\n"
            + "    receiver: null\n"
            + "}\n"
            + "behavior: {\n"
            + "    plus: (other) => {\n"
            + "        log.calls = log.calls + 1\n"
            + "        log.argument = other\n"
            + "        log.receiver = this\n"
            + "        \"custom\"\n"
            + "    }\n"
            + "}\n";

    @Test
    void standardStringAdditionConcatenates() {
        assertEquals("hello", evalString("\"hel\" + \"lo\""));
        assertEquals("--file", evalString("\"--\" + \"file\""));
        assertTrue(evalBoolean("(\"hel\" + \"lo\") === \"hello\""));
        assertEquals("--file", evalString("s: \"--\"\nt: \"file\"\ns + t"));
    }

    @Test
    void closureBodiesAndObjectMemberInitializersConcatenate() {
        assertEquals("xy", evalString("f: (a, b) => { a + b }\nf(\"x\", \"y\")"));
        assertEquals("--file", evalString(
                "optionSpec: { long: \"file\" }\n"
                        + "o: { spelling: \"--\" + optionSpec.long }\n"
                        + "o.spelling"));
        assertEquals("--file", evalString(
                "make: (spec) => {\n"
                        + "    { spelling: \"--\" + spec.long }\n"
                        + "}\n"
                        + "make({ long: \"file\" }).spelling"));
    }

    @Test
    void nestedStringAdditionsConcatenateInSourceOrder() {
        assertEquals("abcde", evalString("\"a\" + \"b\" + \"c\" + (\"d\" + \"e\")"));
        assertEquals("<xy!>", evalString(
                "f: (s) => { s + \"!\" }\n\"<\" + f(\"x\" + \"y\") + \">\""));
        assertEquals("abc", evalString(
                "r: \"\"\n"
                        + "parts: [\"a\", \"b\", \"c\"]\n"
                        + "parts.each((part) => { r = r + part })\n"
                        + "r"));
    }

    @Test
    void emptyStringsAndNonAsciiUnicodeScalars() {
        assertEquals("", evalString("\"\" + \"\""));
        assertEquals("a", evalString("\"\" + \"a\""));
        assertEquals("a", evalString("\"a\" + \"\""));
        assertEquals("ñ😀", evalString("\"ñ\" + \"😀\""));
        assertEquals(2L, evalLong("(\"ñ\" + \"😀\").size()"));
    }

    @Test
    void stringPlusNonStringSignalsAGuestError() {
        assertTrue(signals("\"a\" + 1"));
        assertTrue(signals("\"a\" + 1.5"));
        assertTrue(signals("\"a\" + null"));
        assertTrue(signals("\"a\" + {}"));
        assertTrue(signals("1 + \"a\""));
        assertTrue(signals("f: (a, b) => { a + b }\nf(\"a\", 1)"));
    }

    @Test
    void numericAdditionAndOverflowRemainCorrect() {
        assertEquals(3L, evalLong("1 + 2"));
        assertEquals(54L, evalLong("add: (x, y) => { x + y }\nadd(40, 10) + add(2, 2)"));
        assertEquals(3.5, evalDouble("1.5 + 2"));
        assertEquals(3.5, evalDouble("2 + 1.5"));
        assertTrue(evalBoolean(
                "(9223372036854775807 + 1) == 9223372036854775808"));
        assertTrue(evalBoolean(
                "add: (x, y) => { x + y }\n"
                        + "BigInteger.recognizes(add(9223372036854775807, 1))"));
    }

    @Test
    void customPlusReceivesTheOriginalReceiverAndRhsExactlyOnce() {
        String box = TRACE + LOGGING_PLUS
                + "box: { ...behavior.alias(\"plus\", \"+\") }\n";
        assertEquals("custom", evalString(box + "step(1, box) + step(2, 5)"));
        assertTrue(evalBoolean(box
                + "r: step(1, box) + step(2, 5)\n"
                + "(r == \"custom\") && (trace.value == 12) && (log.calls == 1)"
                + " && (log.argument == 5) && (log.receiver === box)"));
        assertTrue(evalBoolean(box
                + "f: () => { box + \"rhs\" }\n"
                + "(f() == \"custom\") && (log.calls == 1)"
                + " && (log.argument == \"rhs\") && (log.receiver === box)"));
    }

    @Test
    void overridingPlusIsNotBypassedByTheStringSpecialization() {
        String source = LOGGING_PLUS
                + "custom: (String) { ...behavior.alias(\"plus\", \"+\") }\n"
                + "plain: (String) {}\n"
                + "add: (x, y) => { x + y }\n";
        // Individual steps first, so a failure names the exact send.
        assertTrue(evalBoolean(source + "add(custom, \"x\") == \"custom\""),
                "add(custom, \"x\")");
        assertTrue(evalBoolean(source + "add(\"a\", \"b\") == \"ab\""),
                "add(\"a\", \"b\")");
        assertTrue(evalBoolean(source + "add(1, 2) == 3"), "add(1, 2)");
        assertTrue(evalBoolean(source + "add(custom, \"y\") == \"custom\""),
                "add(custom, \"y\")");
        // Progressive prefixes on one physical '+' site: each transition
        // between the delegating object, String, long and the object again.
        assertTrue(evalBoolean(source
                + "r1: add(custom, \"x\")\n"
                + "(r1 == \"custom\") && (log.receiver === custom) && (log.calls == 1)"),
                "prefix 1");
        assertTrue(evalBoolean(source
                + "r1: add(custom, \"x\")\n"
                + "r2: add(\"a\", \"b\")\n"
                + "(r1 == \"custom\") && (r2 == \"ab\") && (log.calls == 1)"),
                "prefix 2");
        assertTrue(evalBoolean(source
                + "r1: add(custom, \"x\")\n"
                + "r2: add(\"a\", \"b\")\n"
                + "r3: add(1, 2)\n"
                + "(r1 == \"custom\") && (r2 == \"ab\") && (r3 == 3) && (log.calls == 1)"),
                "prefix 3");
        assertTrue(evalBoolean(source
                + "r1: add(custom, \"x\")\n"
                + "r2: add(\"a\", \"b\")\n"
                + "r3: add(1, 2)\n"
                + "r4: add(custom, \"y\")\n"
                + "(r1 == \"custom\") && (r2 == \"ab\") && (r3 == 3)"
                + " && (r4 == \"custom\") && (log.receiver === custom)"
                + " && (log.argument == \"y\") && (log.calls == 2)"),
                "prefix 4");
        // Reverse order on one site: standard String, redefined, String again.
        assertTrue(evalBoolean(source
                + "s1: add(\"a\", \"b\")\n"
                + "s2: add(custom, \"z\")\n"
                + "s3: add(\"c\", \"d\")\n"
                + "(s1 == \"ab\") && (s2 == \"custom\") && (s3 == \"cd\")"
                + " && (log.receiver === custom) && (log.calls == 1)"),
                "reverse sequence");
        // Repeated alternation on the same warmed site: each round runs the
        // standard concatenation, the redefined '+', and the restored
        // standard selection, whose String result concatenates again.
        StringBuilder rounds = new StringBuilder(source);
        StringBuilder checks = new StringBuilder("true");
        for (int round = 0; round < 4; round++) {
            rounds.append("w").append(round).append(": add(\"w\", \"").append(round).append("\")\n");
            rounds.append("c").append(round).append(": add(custom, \"").append(round).append("\")\n");
            rounds.append("t").append(round).append(": add(\"t\", \"").append(round).append("\")\n");
            rounds.append("u").append(round).append(": add(t").append(round).append(", \"!\")\n");
            checks.append(" && (w").append(round).append(" == \"w").append(round).append("\")");
            checks.append(" && (c").append(round).append(" == \"custom\")");
            checks.append(" && (t").append(round).append(" == \"t").append(round).append("\")");
            checks.append(" && (u").append(round).append(" == \"t").append(round).append("!\")");
        }
        assertTrue(evalBoolean(rounds + checks.toString()
                + " && (log.receiver === custom) && (log.argument == \"3\")"
                + " && (log.calls == 4)"),
                "repeated alternation after warm-up");
        // The same alternation through two Closures of one Prelude: standard
        // concatenation, the redefined '+', the standard selection restored,
        // concatenation again, then the other Closure's own '+' site. Only
        // the redefined sends reach the logging method.
        StringBuilder twoSites = new StringBuilder(source)
                .append("other: (x, y) => { x + y }\n");
        StringBuilder twoChecks = new StringBuilder("true");
        for (int round = 0; round < 4; round++) {
            twoSites.append("a").append(round).append(": add(\"a\", \"").append(round).append("\")\n");
            twoSites.append("b").append(round).append(": add(custom, \"b").append(round).append("\")\n");
            twoSites.append("d").append(round).append(": add(\"d\", \"").append(round).append("\")\n");
            twoSites.append("e").append(round).append(": add(d").append(round).append(", \"e\")\n");
            twoSites.append("f").append(round).append(": other(\"f\", \"").append(round).append("\")\n");
            twoSites.append("g").append(round).append(": other(custom, \"g").append(round).append("\")\n");
            twoSites.append("n").append(round).append(": log.calls\n");
            twoChecks.append(" && (a").append(round).append(" == \"a").append(round).append("\")");
            twoChecks.append(" && (b").append(round).append(" == \"custom\")");
            twoChecks.append(" && (d").append(round).append(" == \"d").append(round).append("\")");
            twoChecks.append(" && (e").append(round).append(" == \"d").append(round).append("e\")");
            twoChecks.append(" && (f").append(round).append(" == \"f").append(round).append("\")");
            twoChecks.append(" && (g").append(round).append(" == \"custom\")");
            twoChecks.append(" && (n").append(round).append(" == ").append(2 * (round + 1)).append(")");
        }
        assertTrue(evalBoolean(twoSites + twoChecks.toString()
                + " && (log.receiver === custom) && (log.argument == \"g3\")"
                + " && (log.calls == 8)"),
                "alternation through two Closures of one Prelude");
        // A String-delegating object selects its own '+'.
        assertTrue(evalBoolean(source
                + "(add(custom, \"x\") == \"custom\") && (log.receiver === custom)"
                + " && (add(\"a\", \"b\") == \"ab\") && (add(1, 2) == 3)"
                + " && (add(custom, \"y\") == \"custom\") && (log.calls == 2)"));
        // Delegating to String does not make the receiver a semantic String.
        assertTrue(signals(source + "plain + \"x\""));
        assertTrue(signals(source + "add(plain, \"x\")"));
    }

    @Test
    void suspendingCustomPlusRetainsContinuationBehavior() {
        String box = TRACE
                + "gate: (() => 5).future()\n"
                + "behavior: { plus: (other) => { gate.value() + other } }\n"
                + "box: { ...behavior.alias(\"plus\", \"+\") }\n";
        assertEquals(1215L, evalLong(box
                + "r: step(1, box) + step(2, 10)\n"
                + "trace.value * 100 + r"));
        assertEquals(6L, evalLong(box + "f: () => { box + 1 }\nf()"));
        // Each custom '+' returns 5 + other: (5 + 1) + (5 + 4).
        assertEquals(15L, evalLong(box + "f: () => { (box + 1) + (box + 4) }\nf()"));
    }

    @Test
    void suspendingCustomPlusCompletesBeforeLaterWork() {
        String box = TRACE
                + "gate: (() => 5).future()\n"
                + "behavior: { plus: (other) => { gate.value() + other } }\n"
                + "box: { ...behavior.alias(\"plus\", \"+\") }\n";
        // Non-terminal: the suspended '+' completes, then the next statement runs.
        assertEquals(12L, evalLong(box + "f: () => {\n    r: box + 1\n    r * 2\n}\nf()"));
        // Terminal over arguments: the suspension leaves through the terminal transport.
        assertEquals(6L, evalLong(box + "g: (b, n) => { b + n }\ng(box, 1)"));
        // As an argument of another send and as a discarded expression.
        assertEquals(7L, evalLong(box + "h: (v) => { v + 1 }\nk: () => { h(box + 1) }\nk()"));
        assertEquals(3L, evalLong(box + "d: () => {\n    box + 1\n    3\n}\nd()"));
        // Receiver and argument are evaluated once across the suspension.
        assertEquals(1215L, evalLong(box
                + "m: () => {\n    r: step(1, box) + step(2, 10)\n    trace.value * 100 + r\n}\nm()"));
    }

    @Test
    void customPlusErrorsPropagate() {
        assertTrue(signals(
                "behavior: { plus: (other) => { Error().signal() } }\n"
                        + "box: { ...behavior.alias(\"plus\", \"+\") }\n"
                        + "box + 1"));
        assertTrue(signals("{} + 1"));
    }

    @Test
    void standardStringPathUsesNativeOperationsBehindTheStandardGuard()
            throws Exception {
        String source = """
                run: () => {
                    s: "hel"
                    s + "lo"
                }
                """;
        try (ProtosStandaloneHostedSession session = open(source)) {
            ProtosBytecodeRootNode run = (ProtosBytecodeRootNode)
                    session.prepareTopLevel("run").hostExecutableForTesting()
                            .target.getRootNode();
            List<String> names = names(run);
            assertTrue(names.stream().anyMatch(name -> name.startsWith("c.NumberAdd")),
                    "missing c.NumberAdd in " + names);
            assertNoSeparateSelection(names);
            assertFalse(names.contains("yield"),
                    "a terminal '+' uses the terminal transport: " + names);
            for (int iteration = 0; iteration < 10; iteration++) {
                assertEquals("hello", session.prepareTopLevel("run")
                        .executable().execute().asString());
            }
        }
    }

    /**
     * A non-capturing compact Closure retains its creator's Prelude (owner
     * decision), so its root needs no caller Prelude declared in advance;
     * the very first {@code a + b} over Strings still selects the standard
     * String behavior and runs the native NumberAdd. A number-literal
     * receiver is statically numeric and needs nothing either.
     */
    @Test
    void firstCompactClosureInvocationConcatenates() throws Exception {
        String source = """
                add: (a, b) => { a + b }
                inc: (x) => { 1 + x }
                wrap: (s) => {
                    inner: (t) => { "<" + t }
                    inner(s) + ">"
                }
                make: (spec) => {
                    { spelling: "--" + spec.long }
                }
                runAdd: () => { add("a", "b") }
                runNested: () => { wrap(add("x", "y")) }
                runMake: () => { make({ long: "file" }).spelling }
                runNumeric: () => { add(40, 2) + inc(1) }
                """;
        try (ProtosStandaloneHostedSession session = open(source)) {
            ProtosBytecodeRootNode add = root(session, "add");
            ProtosBytecodeRootNode inc = root(session, "inc");
            assertFalse(add.hasCallerNeed(ProtosBytecodeRootNode.NEEDS_CALLER_PRELUDE),
                    "a Closure that retains its Prelude needs no caller transport");
            assertFalse(inc.hasCallerNeed(ProtosBytecodeRootNode.NEEDS_CALLER_PRELUDE),
                    "a number-literal receiver needs no caller Prelude");
            List<String> addNames = names(add);
            assertTrue(addNames.stream().anyMatch(name -> name.startsWith("c.NumberAdd")),
                    "missing c.NumberAdd in " + addNames);
            assertNoSeparateSelection(addNames);

            assertEquals("ab", execute(session, "runAdd").asString());
            assertEquals("<xy>", execute(session, "runNested").asString());
            assertEquals("--file", execute(session, "runMake").asString());
            assertEquals(44L, execute(session, "runNumeric").asLong());
            for (int iteration = 0; iteration < 10; iteration++) {
                assertEquals("ab", execute(session, "runAdd").asString());
                assertEquals("<xy>", execute(session, "runNested").asString());
                assertEquals("--file", execute(session, "runMake").asString());
                assertEquals(44L, execute(session, "runNumeric").asLong());
            }
        }
        assertEquals("ab", evalString("f: (a, b) => { a + b }\nf(\"a\", \"b\")"));
        assertEquals("<ab>", evalString(
                "g: (s) => { \"<\" + s + \">\" }\n"
                        + "f: (a, b) => { g(a + b) }\n"
                        + "f(\"a\", \"b\")"));
    }

    private static final String FORWARDING = """
            add: (a, b) => { a + b }
            invoke: (fn) => { fn("a", "b") }
            twice: (g, fn) => { g(fn) }
            thrice: (h, g, fn) => { h(g, fn) }
            viaLocal: (fn) => {
                f: fn
                f("a", "b")
            }
            viaCell: (fn) => {
                inner: () => { fn("a", "b") }
                inner()
            }
            viaInline: (fn) => {
                r: ""
                (true).ifTrue() { r = fn("a", "b") }
                r
            }
            numeric: (fn) => { fn(40, 2) }
            makeJoin: (sep) => { (a, b) => { a + sep + b } }
            runInvoke: () => { invoke(add) }
            runTwice: () => { twice(invoke, add) }
            runThrice: () => { thrice(twice, invoke, add) }
            runLocal: () => { viaLocal(add) }
            runCell: () => { viaCell(add) }
            runInline: () => { viaInline(add) }
            runCapturing: () => { invoke(makeJoin("-")) }
            runNumeric: () => { numeric(add) }
            """;

    /**
     * I115 regression: a non-capturing Closure that only forwards a
     * dynamically supplied Closure contains no '+' itself, yet its callee
     * may be a non-capturing Closure that declares NEEDS_CALLER_PRELUDE.
     * Each dynamic invocation makes the forwarding root declare the caller
     * Prelude need during lowering, so the very first invocation already
     * transports the Prelude through every level.
     */
    @Test
    void dynamicClosureCallForwardsTheCallerPrelude() {
        assertEquals("ab", evalString(
                "add: (a, b) => { a + b }\n"
                        + "invoke: (fn) => { fn(\"a\", \"b\") }\n"
                        + "invoke(add)"));
        assertEquals("ab", evalString(
                "add: (a, b) => { a + b }\n"
                        + "invoke: (fn) => { fn(\"a\", \"b\") }\n"
                        + "twice: (g, fn) => { g(fn) }\n"
                        + "twice(invoke, add)"));
        assertEquals("ab", evalString(
                "add: (a, b) => { a + b }\n"
                        + "invoke: (fn) => { fn(\"a\", \"b\") }\n"
                        + "twice: (g, fn) => { g(fn) }\n"
                        + "thrice: (h, g, fn) => { h(g, fn) }\n"
                        + "thrice(twice, invoke, add)"));
        assertEquals("a-b", evalString(
                "invoke: (fn) => { fn(\"a\", \"b\") }\n"
                        + "makeJoin: (sep) => { (a, b) => { a + sep + b } }\n"
                        + "invoke(makeJoin(\"-\"))"));
        assertEquals(42L, evalLong(
                "add: (a, b) => { a + b }\n"
                        + "invoke: (fn) => { fn(40, 2) }\n"
                        + "invoke(add)"));
        assertTrue(signals(
                "add: (a, b) => { a + b }\n"
                        + "invoke: (fn) => { fn(\"a\", 1) }\n"
                        + "invoke(add)"));
    }

    @Test
    void forwardingRootsDeclareOnlyThePreludeBeforeTheirFirstInvocation()
            throws Exception {
        try (ProtosStandaloneHostedSession session = open(FORWARDING)) {
            List<String> forwarders = List.of(
                    "invoke", "twice", "thrice", "viaLocal", "viaCell",
                    "viaInline", "numeric");
            java.util.Map<String, com.oracle.truffle.api.Assumption> stable =
                    new java.util.HashMap<>();
            for (String name : forwarders) {
                ProtosBytecodeRootNode forwarder = root(session, name);
                // Owner decision: every non-capturing Closure retains its
                // creator's Prelude, so no forwarding level needs transport.
                assertFalse(forwarder.hasCallerNeed(
                                ProtosBytecodeRootNode.NEEDS_CALLER_PRELUDE),
                        name + " retains its Prelude and needs no caller transport");
                assertFalse(forwarder.hasCallerNeed(
                                ProtosBytecodeRootNode.NEEDS_EXACT_ACTIVATION),
                        name + " does not request the exact Activation");
                stable.put(name, forwarder.callerNeedsStability());
            }
            List<String> addNames = names(root(session, "add"));
            assertTrue(addNames.stream().anyMatch(name -> name.startsWith("c.NumberAdd")),
                    "the callee keeps its native '+' path: " + addNames);

            for (int iteration = 0; iteration < 12; iteration++) {
                assertEquals("ab", execute(session, "runInvoke").asString());
                assertEquals("ab", execute(session, "runTwice").asString());
                assertEquals("ab", execute(session, "runThrice").asString());
                assertEquals("ab", execute(session, "runLocal").asString());
                assertEquals("ab", execute(session, "runCell").asString());
                assertEquals("ab", execute(session, "runInline").asString());
                assertEquals("a-b", execute(session, "runCapturing").asString());
                assertEquals(42L, execute(session, "runNumeric").asLong());
            }

            for (String name : forwarders) {
                ProtosBytecodeRootNode forwarder = root(session, name);
                assertFalse(forwarder.hasCallerNeed(
                                ProtosBytecodeRootNode.NEEDS_EXACT_ACTIVATION),
                        name + " never published the exact Activation");
                assertSame(stable.get(name), forwarder.callerNeedsStability(),
                        name + " registered nothing after its first invocation");
            }
            assertFalse(root(session, "add").hasCallerNeed(
                    ProtosBytecodeRootNode.NEEDS_EXACT_ACTIVATION));
        }
    }

    /* No separate selection, lookup or dispatched call precedes NumberAdd. */
    private static void assertNoSeparateSelection(List<String> names) {
        for (String operation : List.of(
                "c.IsStandardNumericSelection",
                "c.GetMethod",
                "c.CallUnaryMethod",
                "c.CallBinaryMethod")) {
            assertTrue(names.stream().noneMatch(name -> name.startsWith(operation)),
                    "unexpected " + operation + " in " + names);
        }
    }

    private static ProtosBytecodeRootNode root(
            ProtosStandaloneHostedSession session, String name) throws Exception {
        return (ProtosBytecodeRootNode) session.prepareTopLevel(name)
                .hostExecutableForTesting().target.getRootNode();
    }

    private static org.graalvm.polyglot.Value execute(
            ProtosStandaloneHostedSession session, String name) throws Exception {
        return session.prepareTopLevel(name).executable().execute();
    }

    private static List<String> names(ProtosBytecodeRootNode root) {
        return root.getBytecodeNode().getInstructionsAsList().stream()
                .map(ProtosInstructionNames::of).toList();
    }

    private static ProtosStandaloneHostedSession open(String source) throws Exception {
        Files.createDirectories(OUTPUT);
        Path file = OUTPUT.resolve("string-addition.protos");
        Files.writeString(file, source, StandardCharsets.UTF_8);
        ProtosStandaloneHostedSession session = ProtosStandaloneHostedSession.open(CORE, file);
        if (session.initialOutcome().state() != ProtosExecutionOutcome.State.COMPLETED) {
            session.close();
            throw new AssertionError("Module initialization failed");
        }
        return session;
    }

    private static boolean signals(String body) {
        return evalBoolean(
                "Error.handle(() => {\n" + body + "\nfalse\n}, (caught) => true)");
    }

    private static String evalString(String source) {
        try (Context context = context()) {
            return context.eval(ProtosLanguage.ID, source).asString();
        }
    }

    private static long evalLong(String source) {
        try (Context context = context()) {
            return context.eval(ProtosLanguage.ID, source).asLong();
        }
    }

    private static double evalDouble(String source) {
        try (Context context = context()) {
            return context.eval(ProtosLanguage.ID, source).asDouble();
        }
    }

    private static boolean evalBoolean(String source) {
        try (Context context = context()) {
            return context.eval(ProtosLanguage.ID, source).asBoolean();
        } catch (PolyglotException failure) {
            StringBuilder diagnostic = new StringBuilder("Protos evaluation failed\n")
                    .append("source:\n").append(source).append('\n')
                    .append("guest exception: ").append(failure.isGuestException()).append('\n')
                    .append("message: ").append(failure.getMessage()).append('\n');
            if (failure.getSourceLocation() != null) {
                diagnostic.append("location: ").append(failure.getSourceLocation()).append('\n');
            }
            diagnostic.append("polyglot stack:\n");
            for (PolyglotException.StackFrame frame : failure.getPolyglotStackTrace()) {
                diagnostic.append("  ").append(frame).append('\n');
            }
            throw new AssertionError(diagnostic.toString(), failure);
        }
    }

    private static Context context() {
        String core = CORE.toAbsolutePath().toString();
        return Context.newBuilder(ProtosLanguage.ID).option("protos.CoreRoot", core).build();
    }
}
