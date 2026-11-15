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
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.oracle.truffle.api.bytecode.Instruction;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.graalvm.polyglot.Value;
import org.junit.jupiter.api.Test;

/**
 * I115-M (EXPERIMENTAL, PLAT066-C / PERF039): the generalized compact
 * lowerer composes literal whileTrue loops, root-local bindings and numeric
 * sends without callback roots or Context-backed lexical authority.
 * I115 shared numeric lowering: NumberAdd, NumberMultiply,
 * NumberSubtract and RichCompareLt execute the standard behavior internally.
 * Each numeric site is one fused operation that classifies the selected
 * behavior and dispatches a redefined method itself: no separate
 * IsStandardNumericSelection guard or GetMethod/CallUnary fallback is
 * emitted for it. Assignment stores the result in its classified
 * binding; no separate in-place message protocol is introduced.
 * Retired H7 operations are forbidden. Full DSL counts are reported
 * independently of semantic and topology acceptance.
 */
final class ProtosI115MCompactLoweringTest {

    private static final Path CORE = Path.of("protos", "lib", "core");

    private static final Path OUTPUT =
            Path.of("target", "perf039-while-once", "i115-m");

    private static final List<String> FORBIDDEN = List.of(
            "TryDirectSendOne",
            "IsDirectSendMiss",
            "InstallFrameLexicalAuthority",
            "CreateCurrentIndexedLocalSlot",
            "ReadRootFrameLocal",
            "AssignRootFrameLocal",
            "ExperimentalInteger");

    /*
     * Present only on the expanded MISS path of a pre-H7 numeric site;
     * recorded, and required to be absent when the case has no such site.
     */
    private static final List<String> MISS_PATH = List.of(
            "TryPrimitiveNumeric",
            "PrepareSendOne",
            "EnterNestedStructuredDispatch",
            "ResumeContinuation",
            "CompleteClosureCall",
            "FinishClosureCall",
            "Yield");

    private record Census(int rootCount, int runCount, int total) {}

    /** Exact current numeric operations, not historical H7 opcodes. */
    private record Sites(int add, int multiply, int subtract, int less) {
        static Sites loop(int add, int less) {
            return new Sites(add, 0, 0, less);
        }

        int totalNumericSites() {
            return add + multiply + subtract + less;
        }
    }

    @Test
    void baseLoop() throws Exception {
        Census census = runCase("base", """
                run: () => {
                    i: 0
                    (() => i < 1).whileTrue() {
                        i = i + 1
                    }
                    i
                }
                """, 1, Sites.loop(1, 1));
        // Previous I115-K census: 11 + 15 = 26.
        System.out.println("I115_M_BASE_PREVIOUS_TOTAL=26");
        System.out.println("I115_M_BASE_TOTAL=" + census.total());
    }

    @Test
    void differentVariableAndValues() throws Exception {
        runCase("different-variable", """
                run: () => {
                    counter: 2
                    (() => counter < 5).whileTrue() {
                        counter = counter + 1
                    }
                    counter
                }
                """, 5, Sites.loop(1, 1));
    }

    @Test
    void twoVariablesAndSeveralBodyExpressions() throws Exception {
        runCase("two-variables", """
                run: () => {
                    n: 0
                    total: 0
                    (() => n < 3).whileTrue() {
                        total = total + n
                        n = n + 1
                    }
                    total
                }
                """, 3, Sites.loop(2, 1));
    }

    @Test
    void consecutiveLoopsGrowWithoutDispatch() throws Exception {
        Census single = runCase("single-loop", """
                run: () => {
                    n: 0
                    (() => n < 5).whileTrue() {
                        n = n + 1
                    }
                    n
                }
                """, 5, Sites.loop(1, 1));
        Census consecutive = runCase("consecutive-loops", """
                run: () => {
                    n: 0
                    (() => n < 2).whileTrue() {
                        n = n + 1
                    }
                    (() => n < 5).whileTrue() {
                        n = n + 1
                    }
                    n
                }
                """, 5, Sites.loop(2, 2));
        assertTrue(consecutive.runCount() > single.runCount(),
                "A second loop must add instructions");
    }

    @Test
    void nestedLoops() throws Exception {
        runCase("nested-loops", """
                run: () => {
                    outer: 0
                    inner: 0
                    (() => outer < 2).whileTrue() {
                        inner = 0
                        (() => inner < 3).whileTrue() {
                            inner = inner + 1
                        }
                        outer = outer + 1
                    }
                    outer + inner
                }
                """, 5, new Sites(3, 0, 0, 2));
    }

    @Test
    void numericOutsideWhileTrue() throws Exception {
        runCase("numeric-only", """
                run: () => {
                    a: 4
                    b: 6
                    c: a + b
                    c * 2 - 3
                }
                """, 17, new Sites(1, 1, 1, 0));
    }

    @Test
    void consumedWhileTrueReturnsLastBodyResult() throws Exception {
        runCase("consumed-loop", """
                run: () => {
                    i: 0
                    (() => i < 2).whileTrue() {
                        i = i + 1
                    }
                }
                """, 2, Sites.loop(1, 1));
    }

    @Test
    void consumedWhileTrueWithoutIterationsReturnsNull() throws Exception {
        try (ProtosStandaloneHostedSession session = openSession(
                "consumed-zero-loop", """
                run: () => {
                    i: 3
                    (() => i < 2).whileTrue() {
                        i = i + 1
                    }
                }
                """)) {
            ProtosStandaloneHostedSession.PreparedTopLevel prepared =
                    session.prepareTopLevel("run");
            census("consumed-zero-loop", selectedRoot(prepared), Sites.loop(1, 1));
            Value result = prepared.executable().execute();
            assertTrue(result.isNull(), "Zero iterations yield canonical null");
            System.out.println("I115_M_CASE[consumed-zero-loop]_CORRECTNESS=PASS");
        }
    }

    @Test
    void unadmittedBodyExpressionKeepsWholeRootGeneral() throws Exception {
        try (ProtosStandaloneHostedSession session = openSession(
                "unadmitted-body", """
                step: () => 1
                run: () => {
                    i: 0
                    (() => i < 2).whileTrue() {
                        i = i + step()
                    }
                    i
                }
                """)) {
            ProtosStandaloneHostedSession.PreparedTopLevel prepared =
                    session.prepareTopLevel("run");
            ProtosBytecodeRootNode run = selectedRoot(prepared);
            run.getRootNodes().ensureSourceInformation();
            List<Instruction> instructions =
                    run.getBytecodeNode().getInstructionsAsList();
            System.out.println("I115_M_CASE[unadmitted-body]_RUN_FULL_COUNT="
                    + instructions.size());
            // The whole root takes the general lowerer: no compact operation.
            for (Instruction instruction : instructions) {
                String instructionName = ProtosInstructionNames.of(instruction);
                assertFalse(instructionName.contains("Experimental"),
                        "Unadmitted root mixes compact " + instructionName);
            }
            assertEquals(2, prepared.executable().execute().asInt());
            System.out.println("I115_M_CASE[unadmitted-body]_CORRECTNESS=PASS");
        }
    }

    /*
     * I115: every compact Integer literal is a Java Long, also when it fits
     * in 32 bits; no Java Integer constant is a guest number of the lane.
     */
    @Test
    void compactIntegerLiteralsAreLongs() throws Exception {
        try (ProtosStandaloneHostedSession session = openSession(
                "long-literals", """
                run: () => {
                    small: 1
                    wide: 2147483648
                    (() => small < 3).whileTrue() {
                        small = small + 1
                    }
                    small + wide
                }
                """)) {
            ProtosStandaloneHostedSession.PreparedTopLevel prepared =
                    session.prepareTopLevel("run");
            ProtosBytecodeRootNode run = selectedRoot(prepared);
            census("long-literals", run, new Sites(2, 0, 0, 1));
            assertLongConstants("long-literals", run, 4);
            assertEquals(2147483651L, prepared.executable().execute().asLong());
            System.out.println("I115_M_CASE[long-literals]_CORRECTNESS=PASS");
        }
    }

    /*
     * I115: the recursive lane emits ExperimentalIntegerAdd, Subtract and
     * Multiply directly, with no wrapper, MISS branch or extra root, and its
     * stored local results keep their values.
     */
    @Test
    void recursiveLaneUsesPhysicalIntegerOperations() throws Exception {
        try (ProtosStandaloneHostedSession session = openSession(
                "recursive-operations", """
                mix: (n) => {
                    result: 1

                    (n > 1).ifTrue() {
                        result = n * 2 + mix(n - 1) - 1
                    }

                    result
                }

                run: () => { mix(4) }
                """)) {
            ProtosBytecodeRootNode mix =
                    selectedRoot(session.prepareTopLevel("mix"));
            mix.getRootNodes().ensureSourceInformation();
            assertEquals(3, mix.getRootNodes().getNodes().size(),
                    "module, mix and run are the only physical roots");
            List<String> names = mix.getBytecodeNode().getInstructionsAsList()
                    .stream().map(ProtosInstructionNames::of).toList();
            System.out.println("I115_M_CASE[recursive-operations]_DSL=" + names);
            // n * 2 + mix(n - 1) - 1: one product, one sum, two differences.
            for (java.util.Map.Entry<String, Integer> required : java.util.Map.of(
                    "c.NumberAdd", 1,
                    "c.NumberSubtract", 2,
                    "c.NumberMultiply", 1,
                    "c.RichCompareGt", 1).entrySet()) {
                assertEquals((long) required.getValue(),
                        names.stream()
                                .filter(op -> op.startsWith(required.getKey()))
                                .count(),
                        required.getKey() + ": " + names);
            }
            assertEquals(0L, names.stream()
                            .filter(op -> op.startsWith(
                                    "c.IsStandardNumericSelection"))
                            .count(),
                    "fused numeric sites keep no separate selection guard");
            for (String forbidden : List.of(
                    "ExperimentalInteger", "TryPrimitiveNumeric",
                    "PrepareSendOne", "EnterNestedStructuredDispatch")) {
                assertTrue(names.stream()
                                .noneMatch(op -> op.contains(forbidden)),
                        "recursive-operations reintroduces " + forbidden);
            }
            assertLongConstants("recursive-operations", mix, 1);
            // mix(1) = 1, mix(n) = 2n + mix(n - 1) - 1.
            assertEquals(16L,
                    session.prepareTopLevel("run").executable().execute().asLong());
            System.out.println("I115_M_CASE[recursive-operations]_CORRECTNESS=PASS");
        }
    }


    /**
     * A nonstandard receiver must execute its selected + or < method.
     * Each site is a single fused operation that dispatches the selected
     * method itself, without a separate guard or fallback send.
     */
    @Test
    void overriddenNumericOperatorsRetainOrdinarySelection()
            throws Exception {
        try (ProtosStandaloneHostedSession session = openSession(
                "numeric-overrides", """
                plusSource: { add: (other) => { 41 } }
                customPlus: plusSource.alias("add", "+")

                lessSource: { compare: (other) => { false } }
                customLess: lessSource.alias("compare", "<")

                plusOverride: () => { customPlus + 1 }
                lessOverride: () => { customLess < 3 }
                """)) {
            ProtosBytecodeRootNode plus =
                    selectedRoot(session.prepareTopLevel("plusOverride"));
            ProtosBytecodeRootNode less =
                    selectedRoot(session.prepareTopLevel("lessOverride"));
            plus.getRootNodes().ensureSourceInformation();
            less.getRootNodes().ensureSourceInformation();

            List<String> plusNames = plus.getBytecodeNode()
                    .getInstructionsAsList().stream()
                    .map(ProtosInstructionNames::of).toList();
            List<String> lessNames = less.getBytecodeNode()
                    .getInstructionsAsList().stream()
                    .map(ProtosInstructionNames::of).toList();

            assertTrue(plusNames.stream().anyMatch(op -> op.startsWith("c.NumberAdd")),
                    "c.NumberAdd: " + plusNames);
            assertTrue(lessNames.stream().anyMatch(op -> op.startsWith("c.RichCompareLt")),
                    "c.RichCompareLt: " + lessNames);
            for (String separate : List.of(
                    "c.IsStandardNumericSelection", "c.GetMethod", "c.CallUnaryMethod")) {
                assertTrue(plusNames.stream().noneMatch(op -> op.startsWith(separate)),
                        separate + " is not emitted beside the fused +: " + plusNames);
                assertTrue(lessNames.stream().noneMatch(op -> op.startsWith(separate)),
                        separate + " is not emitted beside the fused <: " + lessNames);
            }

            for (int repetition = 0; repetition < 3; repetition++) {
                assertEquals(41L,
                        session.prepareTopLevel("plusOverride")
                                .executable().execute().asLong());
                assertFalse(session.prepareTopLevel("lessOverride")
                        .executable().execute().asBoolean());
            }
            System.out.println("I115_M_DYNAMIC_NUMERIC_SELECTION=PASS");
        }
    }

    /* Every numeric guest literal is a Long; metadata is separate. */
    private static void assertLongConstants(
            String name, ProtosBytecodeRootNode root, int minimum) {
        int longs = 0;
        for (Instruction instruction : root.getBytecodeNode().getInstructionsAsList()) {
            if (!ProtosInstructionNames.of(instruction)
                    .startsWith("load.constant")) {
                continue;
            }
            for (Instruction.Argument argument : instruction.getArguments()) {
                if (argument.getKind() != Instruction.Argument.Kind.CONSTANT) {
                    continue;
                }
                Object constant = argument.asConstant();
                assertFalse(constant instanceof Integer,
                        name + " loads an Integer as a guest literal: "
                                + instruction);
                if (constant instanceof Long) {
                    longs++;
                }
            }
        }
        assertTrue(longs >= minimum, name + " Long literals: " + longs);
    }

    private static Census runCase(String name, String source, int expected, Sites sites)
            throws Exception {
        try (ProtosStandaloneHostedSession session = openSession(name, source)) {
            ProtosStandaloneHostedSession.PreparedTopLevel prepared =
                    session.prepareTopLevel("run");
            Census census = census(name, selectedRoot(prepared), sites);
            assertEquals(expected, prepared.executable().execute().asInt());
            System.out.println("I115_M_CASE[" + name + "]_CORRECTNESS=PASS");
            return census;
        }
    }

    private static ProtosStandaloneHostedSession openSession(
            String name, String source) throws IOException {
        Files.createDirectories(OUTPUT);
        Path sourceFile = OUTPUT.resolve(name + ".protos");
        Files.writeString(sourceFile, source, StandardCharsets.UTF_8);

        ProtosStandaloneHostedSession session =
                ProtosStandaloneHostedSession.open(CORE, sourceFile);
        if (session.initialOutcome().state()
                != ProtosExecutionOutcome.State.COMPLETED) {
            session.close();
            throw new AssertionError("Module initialization failed: " + name);
        }
        return session;
    }

    private static ProtosBytecodeRootNode selectedRoot(
            ProtosStandaloneHostedSession.PreparedTopLevel prepared) {
        return assertInstanceOf(ProtosBytecodeRootNode.class,
                prepared.hostExecutableForTesting().target.getRootNode());
    }

    private static Census census(String name, ProtosBytecodeRootNode run, Sites sites) {
        // DEFAULT bytecode with source information, not instrumented DEBUG.
        run.getRootNodes().ensureSourceInformation();
        List<ProtosBytecodeRootNode> roots =
                run.getRootNodes().getNodes().stream()
                        .map(ProtosBytecodeRootNode.class::cast)
                        .toList();
        String prefix = "I115_M_CASE[" + name + "]_";
        System.out.println(prefix + "ROOT_COUNT=" + roots.size());

        int total = 0;
        int runCount = 0;
        for (int index = 0; index < roots.size(); index++) {
            ProtosBytecodeRootNode current = roots.get(index);
            int count =
                    current.getBytecodeNode().getInstructionsAsList().size();
            total += count;
            if (current == run) {
                runCount = count;
            }
            System.out.println(prefix + "ROOT" + index + "_FULL_COUNT=" + count);
        }
        System.out.println(prefix + "DSL_FULL_TOTAL=" + total);

        assertEquals(2, roots.size(),
                "Literal whileTrue callbacks must not add physical roots");
        int[] retiredProtocol = new int[MISS_PATH.size()];
        int add = 0;
        int multiply = 0;
        int subtract = 0;
        int less = 0;
        int guards = 0;
        int selections = 0;
        int calls = 0;
        int promotions = 0;

        for (Instruction instruction
                : run.getBytecodeNode().getInstructionsAsList()) {
            String op = ProtosInstructionNames.of(instruction);
            for (String forbidden : FORBIDDEN) {
                assertFalse(op.contains(forbidden),
                        name + " contains " + op);
            }
            assertFalse(op.startsWith("c.NumberInPlaceAdd")
                            || op.startsWith("c.Lt"),
                    name + " resurrects H7: " + op);

            for (int i = 0; i < MISS_PATH.size(); i++) {
                if (op.contains(MISS_PATH.get(i))) {
                    retiredProtocol[i]++;
                }
            }

            if (op.startsWith("c.NumberAdd")) {
                add++;
            } else if (op.startsWith("c.NumberMultiply")) {
                multiply++;
            } else if (op.startsWith("c.NumberSubtract")) {
                subtract++;
            } else if (op.startsWith("c.RichCompareLt")) {
                less++;
            } else if (op.startsWith("c.IsStandardNumericSelection")) {
                guards++;
            } else if (op.startsWith("c.GetMethod")) {
                selections++;
            } else if (op.startsWith("c.CallUnaryMethod")) {
                calls++;
            }

            if (op.contains("PromoteFrameLexicalAuthority")) {
                promotions++;
            }
        }

        System.out.println(prefix + "NUMBER_ADD_COUNT=" + add);
        System.out.println(prefix + "NUMBER_MULTIPLY_COUNT=" + multiply);
        System.out.println(prefix + "NUMBER_SUBTRACT_COUNT=" + subtract);
        System.out.println(prefix + "RICH_COMPARE_LT_COUNT=" + less);
        System.out.println(prefix + "NUMERIC_SELECTION_GUARDS=" + guards);
        System.out.println(prefix + "FALLBACK_SELECTIONS=" + selections);
        System.out.println(prefix + "FALLBACK_CALLS=" + calls);

        assertEquals(sites.add(), add, name + " NumberAdd");
        assertEquals(sites.multiply(), multiply, name + " NumberMultiply");
        assertEquals(sites.subtract(), subtract, name + " NumberSubtract");
        assertEquals(sites.less(), less, name + " RichCompareLt");

        // Fused numeric sites classify and dispatch internally.
        assertEquals(0, guards,
                name + " fused numeric sites keep no separate selection guard");

        for (String retired : List.of(
                "TryPrimitiveNumeric",
                "PrepareSendOne",
                "EnterNestedStructuredDispatch",
                "CompleteClosureCall",
                "FinishClosureCall")) {
            assertEquals(0,
                    retiredProtocol[MISS_PATH.indexOf(retired)],
                    name + " reintroduces " + retired);
        }
        assertEquals(0, promotions,
                name + " unexpectedly promotes frame authority");

        return new Census(roots.size(), runCount, total);
    }
}
