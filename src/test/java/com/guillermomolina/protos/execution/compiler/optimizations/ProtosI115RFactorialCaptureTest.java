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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.oracle.truffle.api.bytecode.Instruction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.graalvm.polyglot.Value;
import org.junit.jupiter.api.Test;

/**
 * I115-R: experimental factorial baseline.
 *
 * Records complete Bytecode DSL instructions for module, run,
 * factorial and any additional physical roots.
 *
 * The workload and its result remain unchanged by instrumentation.
 */
final class ProtosI115RFactorialCaptureTest {

    private static final Path CORE =
            Path.of("protos", "lib", "core");

    private static final Path OUTPUT =
            Path.of("target", "i115-r-factorial", "dsl");

    private static final long EXPECTED =
            2432902008176640000L;

    private static final String SOURCE = """
            factorial: (n) => {
                result: 1

                (n > 1).ifTrue() {
                    result = n * factorial(n - 1)
                }

                result
            }

            run: () => {
                factorial(20)
            }
            """;

    @Test
    void factorialDslAndCorrectness() throws Exception {
        Files.createDirectories(OUTPUT);

        Path sourceFile = OUTPUT.resolve("source.protos");
        Files.writeString(
                sourceFile, SOURCE, StandardCharsets.UTF_8);

        try (ProtosStandaloneHostedSession session =
                ProtosStandaloneHostedSession.open(
                        CORE, sourceFile)) {

            assertEquals(
                    ProtosExecutionOutcome.State.COMPLETED,
                    session.initialOutcome().state());

            ProtosStandaloneHostedSession.PreparedTopLevel prepared =
                    session.prepareTopLevel("run");

            ProtosStandaloneHostedSession.PreparedTopLevel factorialPrepared =
                    session.prepareTopLevel("factorial");

            ProtosBytecodeRootNode run = assertInstanceOf(
                    ProtosBytecodeRootNode.class,
                    prepared.hostExecutableForTesting()
                            .target.getRootNode());

            ProtosBytecodeRootNode factorial = assertInstanceOf(
                    ProtosBytecodeRootNode.class,
                    factorialPrepared.hostExecutableForTesting()
                            .target.getRootNode());

            assertTrue(run != factorial);
            assertTrue(run.getRootNodes() == factorial.getRootNodes());

            String report = session.processContextForTesting()
                    .callForRuntime(() -> census(run, factorial));

            Path output = OUTPUT.resolve("dsl-full.txt");
            Files.writeString(
                    output, report, StandardCharsets.UTF_8);

            Value result = prepared.executable().execute();

            assertTrue(result.fitsInLong());
            assertEquals(EXPECTED, result.asLong());

            System.out.println("I115_R_RESULT=" + result.asLong());
            System.out.println("I115_R_CORRECTNESS=PASS");
            System.out.println(
                    "I115_R_DSL_FILE=" + output.toAbsolutePath());
        }
    }


    @Test
    void captureFactorialCompilerGraph() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(
                Boolean.getBoolean("protos.i115r.graphCapture"),
                "I115-R compiler capture is opt-in");

        String selectedRole =
                System.getProperty("protos.i115r.graphRoot", "");
        String requestedTier =
                System.getProperty("protos.i115r.graphTier", "");

        assertTrue(
                selectedRole.equals("run")
                        || selectedRole.equals("factorial"),
                "Select run or factorial");

        assertTrue(
                requestedTier.equals("1")
                        || requestedTier.equals("2"),
                "Select compiler Tier 1 or Tier 2");

        assertEquals("false", System.getProperty(
                "polyglot.engine.BackgroundCompilation"));

        assertEquals("true", System.getProperty(
                "protos.diagnostic.stableRootIdentity"));

        assertEquals("false", System.getProperty(
                "polyglot.engine.DynamicCompilationThresholds"));

        assertEquals("1000000", System.getProperty(
                "polyglot.engine.FirstTierCompilationThreshold"));

        assertEquals("1000000", System.getProperty(
                "polyglot.engine.LastTierCompilationThreshold"));

        var vmArguments = java.lang.management.ManagementFactory
                .getRuntimeMXBean().getInputArguments();

        assertTrue(vmArguments.stream().anyMatch(
                arg -> arg.startsWith("-Djdk.graal.Dump=Truffle:")));

        assertTrue(vmArguments.stream().anyMatch(
                arg -> arg.startsWith("-Djdk.graal.DumpPath=")));

        int tier = Integer.parseInt(requestedTier);

        Path directory = Path.of(
                "target", "i115-r-factorial", "graphs",
                selectedRole, "tier" + tier);

        Files.createDirectories(directory);

        Path sourceFile = directory.resolve("source.protos");
        Files.writeString(
                sourceFile, SOURCE, StandardCharsets.UTF_8);

        try (ProtosStandaloneHostedSession session =
                ProtosStandaloneHostedSession.open(
                        CORE, sourceFile)) {

            assertEquals(
                    ProtosExecutionOutcome.State.COMPLETED,
                    session.initialOutcome().state());

            var runPrepared = session.prepareTopLevel("run");
            var factorialPrepared =
                    session.prepareTopLevel("factorial");

            var runRoot = assertInstanceOf(
                    ProtosBytecodeRootNode.class,
                    runPrepared.hostExecutableForTesting()
                            .target.getRootNode());

            var factorialRoot = assertInstanceOf(
                    ProtosBytecodeRootNode.class,
                    factorialPrepared.hostExecutableForTesting()
                            .target.getRootNode());

            ProtosBytecodeRootNode selectedRoot =
                    selectedRole.equals("run")
                            ? runRoot : factorialRoot;

            Object selectedTarget =
                    selectedRole.equals("run")
                            ? runPrepared.hostExecutableForTesting().target
                            : factorialPrepared.hostExecutableForTesting().target;

            assertTrue(
                    selectedRoot.getCallTarget() == selectedTarget,
                    "Capture must compile the actual Closure target");

            assertTrue(
                    isOptimizingTarget(selectedTarget),
                    "Optimizing Truffle runtime required");

            String rootName = selectedRoot.toString();

            assertTrue(
                    rootName.startsWith("protos-root:")
                            && rootName.contains("[CLOSURE|"),
                    "Stable Closure root identity missing: " + rootName);

            String census = session.processContextForTesting()
                    .callForRuntime(
                            () -> census(runRoot, factorialRoot));

            Path dsl = directory.resolve("dsl-full.txt");
            Files.writeString(
                    dsl, census, StandardCharsets.UTF_8);

            graphMarker("CASE", "factorial");
            graphMarker("SELECTED_ROLE", selectedRole);
            graphMarker("REQUESTED_TIER", tier);
            graphMarker("SELECTED_ROOT", rootName);
            graphMarker("ROOT_COUNT",
                    runRoot.getRootNodes().getNodes().size());
            graphMarker("BYTECODE", dsl.toAbsolutePath());
            graphMarker("SOURCE", sourceFile.toAbsolutePath());

            var roots = runRoot.getRootNodes().getNodes();

            for (int i = 0; i < roots.size(); i++) {
                var root = (ProtosBytecodeRootNode) roots.get(i);

                graphMarker("ROOT_" + i + "_NAME", root);
                graphMarker("ROOT_" + i + "_SOURCE",
                        root.getSourceSection());
                graphMarker("ROOT_" + i + "_DSL_FULL",
                        root.getBytecodeNode()
                                .getInstructionsAsList().size());
            }

            Value executable = runPrepared.executable();

            for (int call = 0; call < 128; call++) {
                Value result = executable.execute();

                assertTrue(result.fitsInLong());
                assertEquals(EXPECTED, result.asLong());
            }

            graphMarker("WARMUP_CALLS", 128);

            graphMarker("RUN_BYTECODE_TIER",
                    runRoot.getBytecodeNode().getTier());

            graphMarker("FACTORIAL_BYTECODE_TIER",
                    factorialRoot.getBytecodeNode().getTier());

            assertEquals(
                    com.oracle.truffle.api.bytecode.BytecodeTier.CACHED,
                    selectedRoot.getBytecodeNode().getTier(),
                    "Selected root must use cached Bytecode");

            Object before = invokeCompiler(
                    selectedTarget, "highestCompiledTier");

            graphMarker("PRECOMPILE_TIER", before);

            assertTrue(
                    before instanceof Number
                            && ((Number) before).intValue() <= 0,
                    "Target compiled automatically before explicit request");

            boolean compiled = session.processContextForTesting()
                    .callForRuntime(() -> (Boolean) invokeCompiler(
                            selectedTarget, "compile", tier == 2));

            Object actualTier = invokeCompiler(
                    selectedTarget, "highestCompiledTier");

            graphMarker("COMPILE_RETURN", compiled);
            graphMarker("COMPILED_TIER", actualTier);

            assertTrue(compiled, "Explicit compilation failed");

            assertEquals(
                    tier, actualTier,
                    "Compiled tier differs from requested tier");

            assertTrue(
                    (Boolean) invokeCompiler(
                            selectedTarget, "isValid"),
                    "Selected compiled target is invalid");

            Path dump = Path.of(
                    System.getProperty("jdk.graal.DumpPath"));

            String selector = rootName.split("\\[", 2)[0];

            List<Path> graphs;

            try (var files = Files.walk(dump)) {
                graphs = files
                        .filter(Files::isRegularFile)
                        .filter(file -> file.toString()
                                .endsWith(".bgv"))
                        .filter(file -> file.getFileName()
                                .toString().contains(selector))
                        .sorted()
                        .toList();
            }

            assertTrue(
                    !graphs.isEmpty(),
                    "No BGV files for selected root: " + rootName);

            for (Path graph : graphs) {
                assertTrue(Files.size(graph) > 0);
                graphMarker("BGV", graph.toAbsolutePath());
            }

            graphMarker("BGV_COUNT", graphs.size());
            graphMarker("JVM_PID",
                    ProcessHandle.current().pid());
            graphMarker("RESULT", "PASS");
        }
    }

    private static boolean isOptimizingTarget(Object target) {
        for (Class<?> current = target.getClass();
                current != null;
                current = current.getSuperclass()) {
            if (current.getName().equals(
                    "com.oracle.truffle.runtime.OptimizedCallTarget")) {
                return true;
            }
        }
        return false;
    }

    private static Object invokeCompiler(
            Object target, String name, Object... arguments) {
        try {
            for (Class<?> current = target.getClass();
                    current != null;
                    current = current.getSuperclass()) {
                for (java.lang.reflect.Method method
                        : current.getDeclaredMethods()) {
                    if (method.getName().equals(name)
                            && method.getParameterCount()
                                    == arguments.length) {
                        method.setAccessible(true);
                        return method.invoke(target, arguments);
                    }
                }
            }
            throw new AssertionError(
                    "Missing compiler method: " + name);
        } catch (IllegalAccessException error) {
            throw new AssertionError(error);
        } catch (java.lang.reflect.InvocationTargetException error) {
            throw new AssertionError(error.getCause());
        }
    }

    private static void graphMarker(String name, Object value) {
        System.out.println("I115_R_GRAPH_" + name + "=" + value);
    }


    /*
     * I115-H1-FIX: '>' is the shared guarded numeric site; its HIT yields a
     * canonical Boolean that runs the callback inline, and any other result
     * receives ifTrue as an ordinary send with the callback as a guest
     * Closure, whose root the lowering registers. The unary parameter is
     * not assumed to be an Integer.
     */
    @Test
    void compactUnaryBooleanControlIsGuarded() throws Exception {
        Path directory = Path.of(
                "target", "i115-r-factorial", "unary");
        Files.createDirectories(directory);

        Path sourceFile = directory.resolve("source.protos");
        Files.writeString(sourceFile, """
                compute: (n) => {
                    result: 1

                    (n > 1).ifTrue() {
                        result = n * 2
                    }

                    result
                }

                runZero: () => { compute(0) }
                runThree: () => { compute(3) }
                """, StandardCharsets.UTF_8);

        try (ProtosStandaloneHostedSession session =
                ProtosStandaloneHostedSession.open(
                        CORE, sourceFile)) {

            assertEquals(
                    ProtosExecutionOutcome.State.COMPLETED,
                    session.initialOutcome().state());

            var compute = session.prepareTopLevel("compute");

            ProtosBytecodeRootNode root = assertInstanceOf(
                    ProtosBytecodeRootNode.class,
                    compute.hostExecutableForTesting()
                            .target.getRootNode());

            var instructions = root.getBytecodeNode()
                    .getInstructionsAsList();

            List<String> names = instructions.stream()
                    .map(ProtosInstructionNames::of)
                    .toList();

            System.out.println(
                    "I115_R1_UNARY_DSL_FULL=" + instructions.size());

            System.out.println(
                    "I115_R1_UNARY_INSTRUCTIONS=" + names);

            /*
             * Unified Boolean control: the inline ifTrue body shares the
             * frame, so n and result are LOCALs (n bound by the grouped
             * copy); '>' is RichCompareGt whose primitive result flows into
             * the canonical control condition and the native branch; the
             * inline body computes '*' and stores result into its local.
             */
            int bind = indexStartingWith(names, "c.CopySuppliedArguments");
            assertTrue(bind == 1, "Missing parameter binding: " + names);
            assertTrue(indexStartingWith(names, "c.CreateCell") < 0, names.toString());
            for (String required : List.of(
                    "c.RichCompareGt", "c.CanonicalControlCondition", "c.NumberMultiply")) {
                assertEquals(1, names.stream().filter(
                                name -> name.startsWith(required)).count(),
                        "Missing unified operation " + required + ": " + names);
            }
            int compare = indexStartingWith(names, "c.RichCompareGt");
            int condition = indexStartingWith(names, "c.CanonicalControlCondition");
            int branch = -1;
            for (int index = condition + 1; index < names.size(); index++) {
                if (names.get(index).startsWith("branch.false")) {
                    branch = index;
                    break;
                }
            }
            int multiply = indexStartingWith(names, "c.NumberMultiply");
            assertTrue(bind < compare && compare < condition
                            && condition < branch && branch < multiply,
                    "native control order: " + names);
            boolean storedAfter = false;
            for (int index = multiply + 1; index < names.size(); index++) {
                storedAfter |= names.get(index).startsWith("store.local");
            }
            assertTrue(storedAfter, "the product is stored into result's local: " + names);
            assertTrue(names.get(names.size() - 1).equals("return"), names.toString());
            for (String forbidden : List.of(
                    // GetMethod here is only a guarded message fallback.
                    "c.CallNilaryMethod", "c.MaterializeCurrentClosure")) {
                assertTrue(names.stream().noneMatch(name -> name.startsWith(forbidden)),
                        "No separate callback machinery: " + forbidden + " in " + names);
            }

            for (String retired : List.of(
                    "ExperimentalInteger",
                    "InstallFrameLexicalAuthority",
                    "InvokeStaticClosureOneTail",
                    "BindUnaryFrameParameter",
                    "TryPrimitiveNumeric",
                    "IsCanonicalBooleanValue")) {
                assertTrue(
                        names.stream().noneMatch(
                                name -> name.contains(retired)),
                        "Unexpected retired operation: " + retired);
            }

            // I115-H5: compute is not an admitted recursive Integer callee.
            assertUnaryMethodCall(session, "runZero");
            assertUnaryMethodCall(session, "runThree");

            assertEquals(
                    1,
                    session.prepareTopLevel("runZero")
                            .executable().execute().asInt());

            assertEquals(
                    6,
                    session.prepareTopLevel("runThree")
                            .executable().execute().asInt());

            System.out.println("I115_R1_UNARY_CORRECTNESS=PASS");
            System.out.println("I115_R1_UNARY_GUARDED_COMPACT=PASS");
        }
    }


    private static final String I115_R2_SOURCE = """
            factorial: (n) => {
                result: 1

                (n > 1).ifTrue() {
                    result = n * factorial(n - 1)
                }

                result
            }

            run: () => {
                factorial(20)
            }
            """;

    /*
     * I115-H2: experimental structural contract. This selected factorial
     * workload uses the admitted compact recursive lane; its measurements
     * do not establish general Protos semantic equivalence.
     */
    @Test
    void compactRecursiveFactorialStructure() throws Exception {
        Path file = Path.of(
                "target", "i115-r-factorial", "r2-structure.protos");
        Files.createDirectories(file.getParent());
        Files.writeString(file, I115_R2_SOURCE, StandardCharsets.UTF_8);

        try (ProtosStandaloneHostedSession session =
                ProtosStandaloneHostedSession.open(CORE, file)) {

            assertEquals(
                    ProtosExecutionOutcome.State.COMPLETED,
                    session.initialOutcome().state());

            var prepared = session.prepareTopLevel("factorial");
            ProtosBytecodeRootNode factorial = assertInstanceOf(
                    ProtosBytecodeRootNode.class,
                    prepared.hostExecutableForTesting()
                            .target.getRootNode());

            factorial.getRootNodes().ensureSourceInformation();

            int rootCount = factorial.getRootNodes().getNodes().size();

            List<String> names = factorial.getBytecodeNode()
                    .getInstructionsAsList().stream()
                    .map(ProtosInstructionNames::of)
                    .toList();

            System.out.println("I115_R2_TEST_ROOT_COUNT=" + rootCount);
            System.out.println("I115_R2_TEST_DSL_FULL=" + names.size());

            assertEquals(3, rootCount,
                    "Unexpected callback or other physical root");

            // I115: the single lowering: one parameter binding, the
            // per-selector numeric operations and one CallUnaryMethod over
            // the module-global factorial.
            assertSingleLoweringFactorial(names);

            for (String forbidden : List.of(
                    "ExperimentalLongArgument0",
                    "InvokeStaticClosureOneTail",
                    "ExperimentalInteger",
                    "PrepareClosureCall",
                    "PrepareSendOne",
                    "EnterNestedStructuredDispatch",
                    "ResumeContinuation",
                    "CompleteClosureCall",
                    "InstallFrameLexicalAuthority",
                    "BindUnaryFrameParameter",
                    "TryPrimitiveNumeric")) {
                assertTrue(names.stream().noneMatch(
                        name -> name.contains(forbidden)),
                        "Unexpected generic operation: " + forbidden);
            }

            assertEquals(
                    2432902008176640000L,
                    session.prepareTopLevel("run")
                            .executable().execute().asLong());

            System.out.println("I115_R2_STRUCTURE=PASS");
        }
    }

    private static final String FACTORIAL_OVERFLOW_SOURCE = """
            factorial: (n) => {
                result: 1

                (n > 1).ifTrue() {
                    result = n * factorial(n - 1)
                }

                result
            }

            runOverflow: () => {
                factorial(21) == 51090942171709440000
            }
            """;

    /*
     * I115-H1: factorial(21) exceeds long, so the recursive product must
     * cross from the primitive carrier to a large Integer through the
     * ordinary invocation. Independent of compilation and graph capture.
     */
    @Test
    void recursiveFactorialOverflowsToLargeInteger() throws Exception {
        Path file = Path.of(
                "target", "i115-r-factorial", "h1-overflow.protos");
        Files.createDirectories(file.getParent());
        Files.writeString(
                file, FACTORIAL_OVERFLOW_SOURCE, StandardCharsets.UTF_8);

        try (ProtosStandaloneHostedSession session =
                ProtosStandaloneHostedSession.open(CORE, file)) {

            assertEquals(
                    ProtosExecutionOutcome.State.COMPLETED,
                    session.initialOutcome().state());

            Value result = session.prepareTopLevel("runOverflow")
                    .executable().execute();

            assertTrue(result.isBoolean());
            assertTrue(result.asBoolean());
        }
    }

    /** I115-R5 historical run() FULL DSL count, before the R6 module-global read. */
    private static final int I115_R5_RUN_DSL_FULL_HISTORICAL = 17;

    /**
     * I115-R6 run() FULL DSL sequence. I115-H11: the
     * unary call is CallUnaryMethod.
     */
    private static final List<String> I115_R6_RUN_DSL_FULL = List.of(
            "c.EnterRoot",
            "c.ReadGlobal",
            "load.constant",
            "c.CallUnaryMethod",
            "c.EpilogForReturn",
            "return");

    /*
     * I115-R6 (experimental): run() reads the module-owned factorial with
     * one ReadGlobal. R5 used the generated materialized captured read
     * (owner-frame selection, load.local.mat and a conditional fallback)
     * at I115_R5_RUN_DSL_FULL_HISTORICAL instructions.
     */
    @Test
    void capturedModuleBindingUsesModuleGlobalRead()
            throws Exception {
        Path file = Path.of(
                "target", "i115-r-factorial",
                "r6-module-global.protos");
        Files.createDirectories(file.getParent());
        Files.writeString(
                file, I115_R2_SOURCE, StandardCharsets.UTF_8);

        try (ProtosStandaloneHostedSession session =
                ProtosStandaloneHostedSession.open(CORE, file)) {
            assertEquals(
                    ProtosExecutionOutcome.State.COMPLETED,
                    session.initialOutcome().state());

            var prepared = session.prepareTopLevel("run");
            ProtosBytecodeRootNode root = assertInstanceOf(
                    ProtosBytecodeRootNode.class,
                    prepared.hostExecutableForTesting()
                            .target.getRootNode());

            List<String> names = root.getBytecodeNode()
                    .getInstructionsAsList().stream()
                    .map(ProtosInstructionNames::of)
                    .toList();

            assertEquals(
                    I115_R6_RUN_DSL_FULL,
                    names,
                    "I115-R6 run() FULL DSL sequence");

            for (String retired : List.of(
                    "SelectCapturedMaterializedOwnerFrameAtRoot",
                    "IsCapturedOwnerFrameSelected",
                    "ReadCapturedFallbackAtRoot",
                    "ReadCapturedFrameLocalAtRoot",
                    "load.local.mat")) {
                assertTrue(
                        names.stream().noneMatch(
                                name -> name.contains(retired)),
                        "Module-owned read kept a captured path: " + retired);
            }

            assertEquals(
                    EXPECTED, prepared.executable().execute().asLong());

            System.out.println(
                    "I115_R5_RUN_DSL_FULL_HISTORICAL="
                            + I115_R5_RUN_DSL_FULL_HISTORICAL);
            System.out.println(
                    "I115_R6_RUN_DSL_FULL=" + names.size());
            System.out.println(
                    "I115_R6_MODULE_GLOBAL_READ=PASS");
        }
    }

    @Test
    void compactRecursiveFactorialBoundaryInputs() throws Exception {
        String source = I115_R2_SOURCE + """
                runZero: () => { factorial(0) }
                runOne: () => { factorial(1) }
                runTwo: () => { factorial(2) }
                runFive: () => { factorial(5) }
                """;

        Path file = Path.of(
                "target", "i115-r-factorial", "r2-boundaries.protos");
        Files.createDirectories(file.getParent());
        Files.writeString(file, source, StandardCharsets.UTF_8);

        try (ProtosStandaloneHostedSession session =
                ProtosStandaloneHostedSession.open(CORE, file)) {

            assertEquals(
                    ProtosExecutionOutcome.State.COMPLETED,
                    session.initialOutcome().state());

            assertEquals(
                    1L,
                    session.prepareTopLevel("runZero")
                            .executable().execute().asLong());

            assertEquals(
                    1L,
                    session.prepareTopLevel("runOne")
                            .executable().execute().asLong());

            assertEquals(
                    2L,
                    session.prepareTopLevel("runTwo")
                            .executable().execute().asLong());

            assertEquals(
                    120L,
                    session.prepareTopLevel("runFive")
                            .executable().execute().asLong());

            assertEquals(
                    2432902008176640000L,
                    session.prepareTopLevel("run")
                            .executable().execute().asLong());

            System.out.println("I115_R2_BOUNDARIES=PASS");
        }
    }

    private static List<String> instructionNames(
            ProtosStandaloneHostedSession session, String name)
            throws Exception {
        ProtosBytecodeRootNode root = assertInstanceOf(
                ProtosBytecodeRootNode.class,
                session.prepareTopLevel(name).hostExecutableForTesting()
                        .target.getRootNode());
        return root.getBytecodeNode()
                .getInstructionsAsList().stream()
                .map(ProtosInstructionNames::of)
                .toList();
    }

    /*
     * I115-H11: a unary Closure call site of a caller root is one
     * CallUnaryMethod; the retired Call1 operations (ordinary,
     * experimental) are absent, and the static recursive call is not used
     * outside its own recursive root.
     */
    private static void assertUnaryMethodCall(
            ProtosStandaloneHostedSession session, String name)
            throws Exception {
        List<String> names = instructionNames(session, name);
        assertEquals(
                1,
                names.stream().filter(
                        instruction -> instruction.startsWith("c.CallUnaryMethod")).count(),
                name + " must use one CallUnaryMethod: " + names);
        for (String retired : List.of(
                "InvokeClosureOneTail",
                "InvokeExperimentalClosureOneTail",
                "InvokeStaticClosureOneTail",
                "MaterializeCurrentClosure")) {
            assertTrue(
                    names.stream().noneMatch(
                            instruction -> instruction.contains(retired)),
                    name + " must not use " + retired + ": " + names);
        }
    }

    /*
     * I115-H5/H11: compute is not an experimentalRecursiveInteger callee.
     * run, runZero and runThree all call through CallUnaryMethod with the
     * B5 compact header; only factorial's own recursion uses the static
     * unary ABI.
     */
    @Test
    void ordinaryUnaryCallEntersNonRecursiveRootWithB5()
            throws Exception {
        String source = I115_R2_SOURCE + """
                compute: (n) => {
                    result: 1

                    (n > 1).ifTrue() {
                        result = n * 2
                    }

                    result
                }

                runZero: () => { compute(0) }
                runThree: () => { compute(3) }
                """;

        Path file = Path.of(
                "target", "i115-r-factorial", "h4b-abi-admission.protos");
        Files.createDirectories(file.getParent());
        Files.writeString(file, source, StandardCharsets.UTF_8);

        try (ProtosStandaloneHostedSession session =
                ProtosStandaloneHostedSession.open(CORE, file)) {
            assertEquals(
                    ProtosExecutionOutcome.State.COMPLETED,
                    session.initialOutcome().state());

            assertEquals(
                    I115_R6_RUN_DSL_FULL,
                    instructionNames(session, "run"),
                    "I115-R6 run() FULL DSL sequence");
            assertUnaryMethodCall(session, "runZero");
            assertUnaryMethodCall(session, "runThree");

            /*
             * I115: module, factorial, run, compute, runZero and runThree.
             * The former seventh root was compute's separate ifTrue callback;
             * the unified control lowering inlines it, so no callback root.
             */
            ProtosBytecodeRootNode run = assertInstanceOf(
                    ProtosBytecodeRootNode.class,
                    session.prepareTopLevel("run").hostExecutableForTesting()
                            .target.getRootNode());
            assertEquals(
                    6,
                    run.getRootNodes().getNodes().size(),
                    "Physical root count without a control-callback root");
            ProtosBytecodeRootNode compute = assertInstanceOf(
                    ProtosBytecodeRootNode.class,
                    session.prepareTopLevel("compute").hostExecutableForTesting()
                            .target.getRootNode());
            List<String> computeNames = compute.getBytecodeNode().getInstructionsAsList()
                    .stream().map(ProtosInstructionNames::of).toList();
            assertTrue(computeNames.stream().anyMatch(
                            name -> name.startsWith("c.CanonicalControlCondition")),
                    "ifTrue is native control flow in compute: " + computeNames);
            assertTrue(computeNames.stream().noneMatch(
                            name -> name.startsWith("c.MaterializeCurrentClosure")),
                    "no callback Closure is created for ifTrue: " + computeNames);

            assertEquals(
                    EXPECTED,
                    session.prepareTopLevel("run")
                            .executable().execute().asLong());
            assertEquals(
                    1,
                    session.prepareTopLevel("runZero")
                            .executable().execute().asInt());
            assertEquals(
                    6,
                    session.prepareTopLevel("runThree")
                            .executable().execute().asInt());

            // Repeated entries hit the installed direct tier.
            for (int i = 0; i < 3; i++) {
                assertEquals(
                        1,
                        session.prepareTopLevel("runZero")
                                .executable().execute().asInt());
                assertEquals(
                        6,
                        session.prepareTopLevel("runThree")
                                .executable().execute().asInt());
                assertEquals(
                        EXPECTED,
                        session.prepareTopLevel("run")
                                .executable().execute().asLong());
            }

            System.out.println("I115_H5_ORDINARY_CALLEE=PASS");
        }
    }

    /*
     * I115: run refers to factorial, declared after it. The current
     * lowering resolves it as a module global (ReadGlobal) followed by the
     * ordinary CallUnaryMethod (B5), diverging from the former dynamic
     * Lookup rule for forward references.
     */
    @Test
    void forwardReferencedBindingKeepsDynamicLookupAndOrdinaryCall()
            throws Exception {
        String source = """
                run: () => {
                    factorial(20)
                }

                factorial: (n) => {
                    result: 1

                    (n > 1).ifTrue() {
                        result = n * factorial(n - 1)
                    }

                    result
                }
                """;

        Path file = Path.of(
                "target", "i115-r-factorial", "h4b-forward-reference.protos");
        Files.createDirectories(file.getParent());
        Files.writeString(file, source, StandardCharsets.UTF_8);

        try (ProtosStandaloneHostedSession session =
                ProtosStandaloneHostedSession.open(CORE, file)) {
            assertEquals(
                    ProtosExecutionOutcome.State.COMPLETED,
                    session.initialOutcome().state());

            /*
             * I115 divergence: a module binding referenced before its
             * declaration is resolved as a module global (ReadGlobal over the
             * module context), not by the former dynamic lexical Lookup rule.
             * The old rule is not a reason to restore the Lookup operation.
             */
            List<String> names = instructionNames(session, "run");
            assertEquals(
                    I115_R6_RUN_DSL_FULL,
                    names,
                    "run reads the module global, then the ordinary unary call");
            assertTrue(
                    names.stream().noneMatch(
                            name -> name.equals("c.Lookup")
                                    || name.contains("InvokeExperimentalClosureOneTail")),
                    "No dynamic Lookup or retired call: " + names);

            assertEquals(
                    EXPECTED,
                    session.prepareTopLevel("run")
                            .executable().execute().asLong());

            System.out.println("I115_H4B_FORWARD_REFERENCE=PASS");
        }
    }

    /*
     * I115: the static unary ABI is retired. factorial is entered with the
     * one B5 direct-call header: the invoked Closure in slot 0, the exact
     * caller in slot 3, the Closure's own invocation ReturnHome in slot 4,
     * and supplied0 at the supplied offset. The Prelude comes from the
     * invocation's Closure, never from a transport slot or the argument.
     */
    @Test
    void recursiveStaticUnaryCallTransportsPreludeOnlyWhenRequested() throws Exception {
        Path file = Path.of("target", "i115-r-factorial", "static-abi.protos");
        Files.createDirectories(file.getParent());
        Files.writeString(file, I115_R2_SOURCE, StandardCharsets.UTF_8);

        try (ProtosStandaloneHostedSession session =
                ProtosStandaloneHostedSession.open(CORE, file)) {
            assertEquals(
                    ProtosExecutionOutcome.State.COMPLETED,
                    session.initialOutcome().state());
            com.guillermomolina.protos.runtime.ProtosClosureValue factorial =
                    session.prepareTopLevel("factorial").closureForTesting();
            com.guillermomolina.protos.runtime.ProtosPrelude prelude =
                    session.processContextForTesting().callForRuntime(
                            factorial::preludeOrNullForRuntime);
            assertTrue(prelude != null, "the hosted factorial has a real Prelude");

            Object supplied0 = 20L;
            com.oracle.truffle.api.RootCallTarget target =
                    session.prepareTopLevel("factorial").hostExecutableForTesting().target;
            session.processContextForTesting().callForRuntime(() -> {
                com.guillermomolina.protos.runtime.ProtosActivation caller =
                        prelude.newModuleActivation();
                Object[] arguments =
                        ProtosFrameArguments.compactDirectClosureCallOne(
                                factorial, caller, null, supplied0);

                assertEquals(ProtosFrameArguments.HEADER_LENGTH + 1, arguments.length);
                assertSame(factorial, arguments[0], "slot 0 is the invoked Closure");
                assertSame(factorial, ProtosFrameArguments.compactClosure(arguments));
                assertSame(caller, arguments[3], "slot 3 is the exact caller");
                assertSame(factorial.invocationReturnHomeForRuntime(),
                        ProtosFrameArguments.compactReturnHome(arguments),
                        "slot 4 is the Closure's own invocation ReturnHome");
                assertTrue(ProtosFrameArguments.isUnmaterializedCompactCall(arguments));
                assertTrue(!ProtosFrameArguments.hasPublishedActivation(arguments));
                assertSame(supplied0,
                        arguments[ProtosFrameArguments.SUPPLIED_ARGUMENT_OFFSET]);
                assertSame(supplied0,
                        ProtosFrameArguments.compactSuppliedArgumentOrNull(arguments, 0));
                assertEquals(1, ProtosFrameArguments.suppliedArgumentCount(arguments));
                // The Prelude is the invocation's, never the guest argument.
                assertSame(prelude, ProtosFrameArguments.preludeOrNull(arguments));
                assertTrue(ProtosFrameArguments.preludeOrNull(arguments) != supplied0);

                assertEquals(EXPECTED, ((Number) com.guillermomolina.protos.runtime
                        .ProtosNumericValueSupport.guestValue(target.call(arguments)))
                        .longValue(), "the direct B5 entry computes factorial(20)");
                return null;
            });

            // factorial(20) fits signed-64: its recursion never requests the Prelude.
            ProtosBytecodeRootNode root = assertInstanceOf(ProtosBytecodeRootNode.class,
                    target.getRootNode());
            com.oracle.truffle.api.Assumption stability = root.callerNeedsStability();
            for (int i = 0; i < 12; i++) {
                assertEquals(EXPECTED,
                        session.prepareTopLevel("run").executable().execute().asLong());
            }
            assertTrue(!root.hasCallerNeed(ProtosBytecodeRootNode.NEEDS_CALLER_PRELUDE),
                    "no Prelude transport without a request");
            assertSame(stability, root.callerNeedsStability());
            assertTrue(stability.isValid());
        }
    }

    /*
     * I115-H5/H11: the doubler object is not an admitted recursive Integer
     * Closure. Its call is the ordinary CallUnaryMethod, never the static
     * recursive call.
     */
    @Test
    void nonClosureCallableUsesOrdinaryTerminalCall() throws Exception {
        String source = """
                doubler: { call: (n) => { n * 2 } }

                runDoubler: () => { doubler(21) }
                """;

        Path file = Path.of(
                "target", "i115-r-factorial", "h4b-generic.protos");
        Files.createDirectories(file.getParent());
        Files.writeString(file, source, StandardCharsets.UTF_8);

        try (ProtosStandaloneHostedSession session =
                ProtosStandaloneHostedSession.open(CORE, file)) {
            assertEquals(
                    ProtosExecutionOutcome.State.COMPLETED,
                    session.initialOutcome().state());

            assertUnaryMethodCall(session, "runDoubler");
            for (int i = 0; i < 3; i++) {
                assertEquals(
                        42,
                        session.prepareTopLevel("runDoubler")
                                .executable().execute().asInt());
            }

            System.out.println("I115_H5_NON_CLOSURE_CALLABLE=PASS");
        }
    }

    /*
     * The factorial root of the single I115 lowering: n bound by
     * LoadSuppliedArgument into its native local; '>' RichCompareGt, '*'
     * NumberMultiply, '-' NumberSubtract; the recursion one CallUnaryMethod
     * over the module-global ReadGlobal; none of the retired compact lane.
     */
    private static int indexStartingWith(List<String> names, String prefix) {
        for (int index = 0; index < names.size(); index++) {
            if (names.get(index).startsWith(prefix)) {
                return index;
            }
        }
        return -1;
    }

    private static void assertSingleLoweringFactorial(List<String> names) {
        int bind = indexStartingWith(names, "c.LoadSuppliedArgument");
        // n is stored into its native storage: the grouped LOCAL copy, or the
        // cell of a CELL binding when a callback captures it.
        assertTrue(indexStartingWith(names, "c.CopySuppliedArguments") == 1
                        || (bind >= 0 && names.get(bind + 1).startsWith("c.StoreCell")),
                "Missing parameter binding: " + names);
        for (String required : List.of(
                "c.RichCompareGt", "c.NumberMultiply", "c.NumberSubtract", "c.ReadGlobal")) {
            assertEquals(1, names.stream().filter(name -> name.startsWith(required)).count(),
                    "Expected one " + required + ": " + names);
        }
        // One recursive call plus one guarded message fallback per GetMethod
        // (each internal operation keeps its send for a non-standard receiver).
        assertEquals(
                1 + names.stream().filter(name -> name.startsWith("c.GetMethod")).count(),
                names.stream().filter(name -> name.startsWith("c.CallUnaryMethod")).count(),
                "one recursive CallUnaryMethod plus the guarded fallbacks: " + names);
        for (String retired : List.of(
                "ExperimentalLongArgument0", "InvokeStaticClosureOneTail",
                "ExperimentalInteger", "TryPrimitiveNumeric", "CopyArguments")) {
            assertTrue(names.stream().noneMatch(name -> name.contains(retired)),
                    "Retired " + retired + ": " + names);
        }
    }

    /* Operations of the retired compact-root, reduced unary ABI and numeric MISS lanes. */
    private static final List<String> RETIRED_ARCHITECTURE = List.of(
            "ExperimentalLongArgument0",
            "InvokeStaticClosureOneTail",
            "InvokeExperimentalClosureOneTail",
            "InvokeClosureOneTail",
            "ExperimentalInteger",
            "TryPrimitiveNumeric",
            "IsPrimitiveNumericSendMiss",
            "CopyArguments",
            "BindUnaryFrameParameter",
            "BindClosureFrameParameter",
            "BindRequiredFrameParameter",
            "IsCompactLocalFrame",
            "CreateCurrentFrameLocal",
            "ReadRootFrameLocal",
            "InstallFrameLexicalAuthority",
            "PromoteFrameLexicalAuthority");

    private static void assertNoRetiredArchitecture(String root, List<String> names) {
        for (String retired : RETIRED_ARCHITECTURE) {
            assertTrue(names.stream().noneMatch(name -> name.contains(retired)),
                    root + " uses retired " + retired + ": " + names);
        }
    }

    private static long countStartingWith(List<String> names, String prefix) {
        return names.stream().filter(name -> name.startsWith(prefix)).count();
    }

    /*
     * Module: the shared root prologue/epilogue, two module NAME bindings
     * (factorial, run) created through the current-context creation, and
     * the two Closure literals they bind.
     */
    private static void assertModuleStructure(List<String> names) {
        assertTrue(names.get(0).startsWith("c.EnterRoot"), "module prologue: " + names);
        assertEquals(
                1L,
                countStartingWith(names, "c.CreateDiscardedModuleBinding"),
                "one fused creation of factorial: " + names);
        assertEquals(
                1L,
                countStartingWith(names, "c.CreateCurrentLocalSlot"),
                "one ordinary creation of run: " + names);
        assertEquals(
                2L,
                countStartingWith(names, "c.MaterializeCurrentClosure"),
                "both source Closures must be materialized: " + names);
        assertTrue(
                indexStartingWith(names, "c.CreateDiscardedModuleBinding")
                        < indexStartingWith(names, "c.CreateCurrentLocalSlot"),
                "factorial must be created before run: " + names);
        assertTrue(countStartingWith(names, "c.EpilogForReturn") >= 1,
                "module return epilogue: " + names);
        assertTrue(names.contains("return"), names.toString());
        assertEquals(0, countStartingWith(names, "c.CallUnaryMethod"),
                "the module only defines: " + names);
        assertNoRetiredArchitecture("module", names);
    }

    /*
     * factorial: n is a CELL (captured by the inline ifTrue body): its cell
     * is created, supplied position 0 is stored into it and read back with
     * LoadCell. '>' is RichCompareGt validated by the canonical Boolean
     * condition of the native control flow; '-' and '*' are the per-selector
     * numeric operations; the recursion is one ReadGlobal and exactly one
     * CallUnaryMethod. ifTrue adds no method lookup or second invocation.
     */
    private static void assertFactorialStructure(ProtosBytecodeRootNode factorial) {
        List<Instruction> instructions = factorial.getBytecodeNode().getInstructionsAsList();
        List<String> names = instructions.stream().map(ProtosInstructionNames::of).toList();

        assertTrue(names.get(0).startsWith("c.EnterRoot"), "factorial prologue: " + names);
        // n (supplied position 0) is a LOCAL bound by the grouped copy: the
        // inline ifTrue body shares the frame, so no cell is created.
        assertTrue(names.get(1).startsWith("c.CopySuppliedArguments"),
                "supplied n is bound into its native local: " + names);
        assertEquals(0, countStartingWith(names, "c.CreateCell"), names.toString());

        for (String one : List.of(
                "c.RichCompareGt", "c.NumberSubtract", "c.NumberMultiply",
                "c.ReadGlobal")) {
            assertEquals(1, countStartingWith(names, one), one + ": " + names);
        }
        assertEquals(1 + countStartingWith(names, "c.GetMethod"),
                countStartingWith(names, "c.CallUnaryMethod"),
                "one recursive CallUnaryMethod plus the guarded fallbacks: " + names);
        int compare = indexStartingWith(names, "c.RichCompareGt");
        int condition = indexStartingWith(names, "c.CanonicalControlCondition");
        assertTrue(condition > compare,
                "the comparison result is the validated control condition: " + names);
        // The primitive comparison result flows into the canonical control
        // condition without materializing a Boolean carrier.
        int branch = -1;
        for (int index = condition + 1; index < names.size(); index++) {
            if (names.get(index).startsWith("branch.false")) {
                branch = index;
                break;
            }
        }
        assertTrue(branch > condition, "native conditional branch: " + names);
        int global = indexStartingWith(names, "c.ReadGlobal");
        int multiply = indexStartingWith(names, "c.NumberMultiply");
        assertTrue(global > branch && multiply > global,
                "recursive function and its product must be inside the inline body: " + names);
        int subtract = indexStartingWith(names, "c.NumberSubtract");
        assertTrue(subtract > global && multiply > subtract,
                "recursive arithmetic order: " + names);
        assertEquals(0, countStartingWith(names, "c.IsStandardNumericSelection"),
                "fused numeric sites keep no separate selection guard: " + names);

        // The fused subtraction dispatches any redefined '-' itself, so the
        // only CallUnaryMethod before the product is the recursion.
        List<String> recursiveRegion = names.subList(global, multiply);
        assertEquals(1, countStartingWith(recursiveRegion, "c.CallUnaryMethod"),
                "exactly one recursive call before the product: " + names);
        assertEquals(0, countStartingWith(recursiveRegion, "c.GetMethod"),
                "no separate numeric fallback before the product: " + names);

        // Native control flow: no second callable invocation. GetMethod only
        // appears as a guarded message fallback of an internal operation.
        for (String forbidden : List.of(
                "c.CallNilaryMethod", "c.CallBinaryMethod",
                "c.CallTernaryMethod", "c.CallQuaternaryMethod", "c.CallVarargsMethod",
                "c.MaterializeCurrentClosure")) {
            assertEquals(0, countStartingWith(names, forbidden), forbidden + ": " + names);
        }
        assertTrue(countStartingWith(names, "c.EpilogForReturn") >= 1,
                "factorial return epilogue: " + names);
        assertTrue(names.contains("return"), names.toString());
        assertSingleLoweringFactorial(names);
        assertNoRetiredArchitecture("factorial", names);
    }

    /* run: the module-global read, the terminal call, its epilogue and return. */
    private static void assertRunStructure(List<String> names) {
        int read = indexStartingWith(names, "c.ReadGlobal");
        int call = indexStartingWith(names, "c.CallUnaryMethod");
        int epilogue = indexStartingWith(names, "c.EpilogForReturn");
        assertTrue(read >= 0 && call > read && epilogue > call, names.toString());
        assertEquals(1, countStartingWith(names, "c.CallUnaryMethod"), names.toString());
        assertEquals("return", names.get(names.size() - 1), names.toString());
        assertNoRetiredArchitecture("run", names);
    }

    private static String census(
            ProtosBytecodeRootNode run,
            ProtosBytecodeRootNode factorial) {

        run.getRootNodes().ensureSourceInformation();

        List<ProtosBytecodeRootNode> roots =
                run.getRootNodes().getNodes().stream()
                        .map(ProtosBytecodeRootNode.class::cast)
                        .toList();

        assertTrue(roots.size() >= 3);

        int moduleIndex = -1;

        for (int i = 0; i < roots.size(); i++) {
            if (roots.get(i).toString().contains("[TOP_LEVEL|")) {
                assertTrue(moduleIndex < 0, "Multiple module roots");
                moduleIndex = i;
            }
        }

        if (moduleIndex < 0) {
            for (int i = 0; i < roots.size(); i++) {
                if (roots.get(i) != run && roots.get(i) != factorial) {
                    moduleIndex = i;
                    break;
                }
            }
        }

        assertTrue(moduleIndex >= 0, "Module root missing");

        int moduleFull = 0;
        int runFull = 0;
        int factorialFull = 0;
        int additionalFull = 0;

        List<String> moduleNames = List.of();

        StringBuilder report = new StringBuilder();

        for (int i = 0; i < roots.size(); i++) {
            ProtosBytecodeRootNode root = roots.get(i);

            List<Instruction> instructions =
                    root.getBytecodeNode().getInstructionsAsList();

            int count = instructions.size();
            String role;

            if (root == run) {
                role = "run";
                runFull += count;
            } else if (root == factorial) {
                role = "factorial";
                factorialFull += count;
            } else if (i == moduleIndex) {
                role = "module";
                moduleFull += count;
                moduleNames = instructions.stream()
                        .map(ProtosInstructionNames::of)
                        .toList();
            } else {
                role = "additional";
                additionalFull += count;
            }

            report.append("ROOT_INDEX=").append(i)
                    .append(" ROLE=").append(role)
                    .append(" ROOT=").append(root)
                    .append(" SOURCE=").append(root.getSourceSection())
                    .append(" DSL_FULL=").append(count)
                    .append('\n');

            for (int n = 0; n < count; n++) {
                Instruction instruction = instructions.get(n);

                report.append("INSTRUCTION[")
                        .append(i).append("][")
                        .append(n).append("]=")
                        .append(ProtosInstructionNames.of(instruction))
                        .append(" | ")
                        .append(instruction)
                        .append('\n');
            }
        }

        int total = moduleFull + runFull
                + factorialFull + additionalFull;

        // Exactly the module, run and factorial roots: no callback root.
        assertEquals(3, roots.size(), "Experimental physical root count");
        assertEquals(0, additionalFull, "Unexpected callback root");
        assertEquals(moduleFull + runFull + factorialFull, total);
        assertTrue(
                moduleNames.stream().noneMatch("clear.local"::equals),
                "Module root kept the retired clear.local: " + moduleNames);

        /*
         * I115: instruction counts are diagnostic metrics only (printed
         * below); the structure of each root is asserted instead.
         */
        assertModuleStructure(moduleNames);
        assertFactorialStructure(factorial);
        assertRunStructure(run.getBytecodeNode().getInstructionsAsList().stream()
                .map(ProtosInstructionNames::of)
                .toList());

        System.out.println("I115_R_ROOT_COUNT=" + roots.size());
        System.out.println("I115_R_MODULE_DSL_FULL=" + moduleFull);
        System.out.println("I115_R_RUN_DSL_FULL=" + runFull);
        System.out.println("I115_R_FACTORIAL_DSL_FULL=" + factorialFull);
        System.out.println("I115_R_ADDITIONAL_DSL_FULL=" + additionalFull);
        System.out.println("I115_R_TOTAL_DSL_FULL=" + total);
        System.out.println("I115_R_RUN_ROOT=" + run);
        System.out.println("I115_R_FACTORIAL_ROOT=" + factorial);

        assertTrue(moduleFull > 0);
        assertTrue(runFull > 0);
        assertTrue(factorialFull > 0);

        return report.toString();
    }
}
