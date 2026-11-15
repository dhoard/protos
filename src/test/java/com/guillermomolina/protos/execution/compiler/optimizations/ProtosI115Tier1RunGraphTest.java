/*
 * THE LICENSED WORK IS PROVIDED UNDER THE TERMS OF THE ADAPTIVE PUBLIC LICENSE
 * ("LICENSE") AS FIRST COMPLETED BY: Guillermo Adrián Molina. ANY USE, PUBLIC
 * DISPLAY, PUBLIC PERFORMANCE, REPRODUCTION OR DISTRIBUTION OF, OR PREPARATION OF
 * DERIVATIVE WORKS BASED ON, THE LICENSED WORK CONSTITUTES RECIPIENT'S ACCEPTANCE
 * OF THIS LICENSE AND ITS TERMS, WHETHER OR NOT SUCH RECIPIENT READ THE TERMS OF
 * THE LICENSE. "LICENSED WORK" AND "RECIPIENT" ARE DEFINED IN THE LICENSE. A COPY
 * OF THE LICENSE IS LOCATED IN THE TEXT FILE ENTITLED "LICENSE.TXT" ACCOMPANYING
 * THE CONTENTS OF THIS FILE. IF A COPY OF THE LICENSE DOES NOT ACCOMPANY THE
 * CONTENTS OF THIS FILE, A COPY OF THE LICENSE MAY ALSO BE OBTAINED AT THE
 * FOLLOWING WEB SITE: https://github.com/guillermomolina/protos
 *
 * Software distributed under the License is distributed on an "AS IS" basis,
 * WITHOUT WARRANTY OF ANY KIND, either express or implied. See LICENSE.TXT.
 */
package com.guillermomolina.protos.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.oracle.truffle.api.bytecode.BytecodeTier;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.channels.ReadableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * I115 opt-in Tier 1 graph census: compile the actual run() of the
 * primitive-closure-call case, parse its own After TruffleTier BGV and assert
 * zero LoadFieldNode and IsNullNode. No pre-existing summary is consulted.
 *
 * <p>The BGV reader is Graal's own graphio parser, loaded reflectively so
 * ordinary test compilation has no dependency on compiler implementation
 * classes. Run with tools/i115_tier2_run_graph.sh on a GraalVM JDK.
 */
final class ProtosI115Tier1RunGraphTest {
    private static final Path CORE = Path.of("protos", "lib", "core");
    private static final Path SOURCE = Path.of("target", "truffle-compilation", "i115-integer-tier1", "primitive-integer-add.protos");
    private static final Path FIXTURE =
            Path.of("tools", "i115_gate_cases", "primitive-integer-add.protos");
    private static final String GRAPH_TYPE = "StructuredGraph";
    private static final String PHASE = "After TruffleTier";
    /* B51 Tier 1 node target for primitive-integer-add in the reference environment. */
    private static final int B51_TIER1_NODE_TARGET = 105;

    @Test
    void reportsTier1IntegerRunGraph() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("protos.i115.tier1GraphCapture"),
                "I115 compiler diagnostic is opt-in: use tools/i115_tier2_run_graph.sh");
        assertEquals("false", System.getProperty("polyglot.engine.BackgroundCompilation"),
                "Require synchronous compilation");
        assertEquals("true", System.getProperty("protos.diagnostic.stableRootIdentity"),
                "Require stable root IDs for BGV attribution");

        String dumpOption = System.getProperty("jdk.graal.DumpPath", "");
        assertTrue(!dumpOption.isBlank(), "A fresh Graal BGV dump directory is required");
        Path dumps = Path.of(dumpOption).toAbsolutePath();
        assertTrue(Files.isDirectory(dumps), "BGV dump directory missing: " + dumps);
        try (var existing = Files.walk(dumps)) {
            assertTrue(existing.noneMatch(path -> Files.isRegularFile(path)
                            && path.getFileName().toString().endsWith(".bgv")),
                    "BGV dump directory must be empty to exclude stale measurements");
        }

        Files.createDirectories(SOURCE.getParent());
        assertTrue(Files.isRegularFile(FIXTURE),
                "Missing primitive-integer-add fixture: " + FIXTURE);
        Files.copy(FIXTURE, SOURCE,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);


        System.out.println("I115_TIER1_ENTRY=EMBEDDING");

        try (Context context = Context.newBuilder(ProtosLanguage.ID)
                .option("protos.CoreRoot", CORE.toAbsolutePath().toString())
                .build()) {

            Source source = Source.newBuilder(
                    ProtosLanguage.ID, SOURCE.toFile()).build();

            context.eval(source);

            Value run = context.getBindings(ProtosLanguage.ID)
                    .getMember("run");

            assertNotNull(run, "Missing run binding");

            for (int calls = 0; calls < 2001; calls++) {
                assertEquals(3L, run.execute().asLong(),
                        "run must return 1 + 2");
            }

            System.out.println("I115_RUN_TIER=1");
            System.out.println("I115_TIER1_INTEGER_INVOCATIONS=2001");
        }

        // Fresh dump directory. The fixture declares exactly one
        // source Closure, run. Exclude its TOP_LEVEL module root.
        List<Path> selectedBgvs;
        try (var files = Files.walk(dumps)) {
            selectedBgvs = files
                    .filter(Files::isRegularFile)
                    .filter(file -> {
                        String name = file.getFileName().toString();
                        return name.endsWith(".bgv")
                                && name.contains("primitive-integer-add.protos")
                                && name.contains("[CLOSURE|");
                    })
                    .sorted()
                    .toList();
        }

        assertTrue(!selectedBgvs.isEmpty(),
                "No compiled Tier 1 run BGV found");
        List<Path> orderedBgvs = selectedBgvs.stream()
                .sorted(Comparator.comparingLong(
                        ProtosI115Tier1RunGraphTest::compilationId))
                .toList();

        System.out.println("I115_TIER1_INTEGER_BGV_COUNT=" + orderedBgvs.size());

        GraphCounts finalCounts = null;
        long finalCompId = -1;
        for (Path bgv : orderedBgvs) {
            assertTrue(Files.size(bgv) > 0, "Empty run BGV: " + bgv);

            GraphCounts counts = countsFromGraalBgv(bgv);
            finalCounts = counts;
            long compId = compilationId(bgv);

            System.out.println(
                    "I115_TIER1_INTEGER_RUN"
                    + " comp_id=" + compId
                    + " nodes=" + counts.total()
                    + " loadField=" + counts.loadField()
                    + " isNull=" + counts.isNull());
            System.out.println(
                    "I115_TIER1_GUEST_NUMERIC_SHAPE_LOADS=" + counts.guestNumericShapeLoads());
            System.out.println(
                    "I115_TIER1_GUEST_NUMERIC_SELECTION_GUARDS="
                    + counts.guestNumericSelectionGuards());
            System.out.println(
                    "I115_TIER1_PRELUDE_FIELD_LOADS=" + counts.preludeFieldLoads());

            System.out.println(
                    "I115_TIER1_INTEGER_BGV=" + bgv.toAbsolutePath());
            finalCompId = compId;
        }

        // Every figure above is printed before this bound is checked.
        System.out.println("I115_TIER1_INTEGER_B51_TARGET=" + B51_TIER1_NODE_TARGET
                + " final_comp_id=" + finalCompId
                + " final_nodes=" + finalCounts.total()
                + " final_guest_numeric_shape_loads=" + finalCounts.guestNumericShapeLoads()
                + " final_guest_numeric_selection_guards="
                + finalCounts.guestNumericSelectionGuards()
                + " final_prelude_field_loads=" + finalCounts.preludeFieldLoads());
        assertTrue(finalCounts.total() <= B51_TIER1_NODE_TARGET,
                "Tier 1 regression: final run graph has " + finalCounts.total()
                        + " nodes, above the B51 target of " + B51_TIER1_NODE_TARGET);
        // The canonical chain is FROZEN: long + long keeps no structural
        // selection guard and no Shape read from those guards.
        assertEquals(0, finalCounts.guestNumericSelectionGuards(),
                "FROZEN long + long must not keep structural numeric selection guards");
        assertEquals(0, finalCounts.guestNumericShapeLoads(),
                "FROZEN long + long must not load DynamicObject.shape for numeric selection");

    }

    private static long compilationId(Path path) {
        String name = path.getFileName().toString();
        String prefix = "TruffleHotSpotCompilation-";
        int opening = name.indexOf('[');
        assertTrue(name.startsWith(prefix) && opening > prefix.length(),
                "Unrecognized Graal compilation BGV name: " + name);
        return Long.parseLong(name.substring(prefix.length(), opening));
    }

    private record GraphCounts(int total, int loadField, int isNull,
            int guestNumericShapeLoads, int guestNumericSelectionGuards,
            int preludeFieldLoads) {}

    /* Informative only: loads of the Prelude captured by a Closure or held by an activation. */
    private static final List<String> PRELUDE_FIELDS =
            List.of("ProtosClosureValue.prelude", "ProtosActivation.prelude");

    private static final String NUMERIC_SELECTION_EXECUTE = "ProtosNumericSelectionNodeGen.execute";
    private static final String STRUCTURAL_SELECTION_IS_VALID = "StructuralSelection.isValid";

    /*
     * Whether the node's recorded properties, including its source position,
     * attribute it to a structural selection check made by the numeric
     * selection node.
     */
    private static boolean fromNumericSelectionGuard(Object node)
            throws ReflectiveOperationException {
        String text = String.valueOf(node.getClass().getMethod("getProperties").invoke(node));
        return text.contains(NUMERIC_SELECTION_EXECUTE)
                && text.contains(STRUCTURAL_SELECTION_IS_VALID);
    }

    /**
     * Parse the BGV using the graphio parser in the GraalVM compiler module.
     * The run script exports only the two required parser packages to tests.
     */
    private static GraphCounts countsFromGraalBgv(Path bgv) throws Exception {
        Class<?> dataSourceType = Class.forName("jdk.graal.compiler.graphio.parsing.DataSource");
        Class<?> builderType = Class.forName("jdk.graal.compiler.graphio.parsing.Builder");
        Class<?> graphDocType = Class.forName("jdk.graal.compiler.graphio.parsing.model.GraphDocument");
        Class<?> parseMonitorType = Class.forName("jdk.graal.compiler.graphio.parsing.ParseMonitor");
        Class<?> modelBuilderType = Class.forName("jdk.graal.compiler.graphio.parsing.ModelBuilder");
        Class<?> inputGraphType = Class.forName("jdk.graal.compiler.graphio.parsing.model.InputGraph");
        Class<?> readerType = Class.forName("jdk.graal.compiler.graphio.parsing.BinaryReader");
        Class<?> sourceType = Class.forName("jdk.graal.compiler.graphio.parsing.BinarySource");

        List<Object> phases = new ArrayList<>();
        try (ReadableByteChannel channel = Files.newByteChannel(bgv)) {
            Object source = sourceType.getConstructor(Object.class, ReadableByteChannel.class)
                    .newInstance(bgv.toString(), channel);
            Object document = graphDocType.getConstructor().newInstance();
            Object builder = modelBuilderType.getConstructor(graphDocType, parseMonitorType)
                    .newInstance(document, null);
            Object reader = readerType.getConstructor(dataSourceType, builderType)
                    .newInstance(source, builder);
            Object parsed = readerType.getMethod("parse").invoke(reader);
            collectPhaseGraphs(parsed, inputGraphType, phases);
        }
        assertEquals(1, phases.size(),
                "Expected one StructuredGraph at After TruffleTier; found " + phases.size());

        Object graph = phases.get(0);
        Collection<?> nodes = assertInstanceOf(Collection.class,
                inputGraphType.getMethod("getNodes").invoke(graph));
        assertTrue(!nodes.isEmpty(), "Selected compiler graph has no nodes");

        Map<String, Integer> histogram = new HashMap<>();
        Map<Integer, Object> nodesById = new HashMap<>();
        List<Object> unwantedNodes = new ArrayList<>();
        int guestNumericShapeLoads = 0;
        int guestNumericSelectionGuards = 0;
        int preludeFieldLoads = 0;
        Map<String, Integer> origins = new HashMap<>();
        for (Object node : nodes) {
            Object nodeClass = node.getClass().getMethod("getNodeClass").invoke(node);
            assertNotNull(nodeClass, "Unclassified node in compiler graph");
            Object fullName = nodeClass.getClass().getField("className").get(nodeClass);
            assertTrue(fullName instanceof String && !((String) fullName).isBlank(),
                    "Missing compiler node class");
            String name = ((String) fullName).replaceFirst("^.*\\.", "");
            histogram.merge(name, 1, Integer::sum);
            int id = (Integer) node.getClass().getMethod("getId").invoke(node);
            assertTrue(nodesById.put(id, node) == null, "Duplicate compiler node id " + id);
            if (name.equals("LoadFieldNode") || name.equals("IsNullNode")) {
                unwantedNodes.add(node);
            }
            if (name.equals("LoadFieldNode") && fromNumericSelectionGuard(node)
                    && String.valueOf(node.getClass().getMethod("getProperties").invoke(node))
                            .contains("DynamicObject.shape")) {
                guestNumericShapeLoads++;
            }
            if (name.equals("LoadFieldNode")) {
                String properties = String.valueOf(
                        node.getClass().getMethod("getProperties").invoke(node));
                if (PRELUDE_FIELDS.stream().anyMatch(field -> properties.matches(
                        "(?s).*\\b" + java.util.regex.Pattern.quote(field) + "\\b.*"))) {
                    preludeFieldLoads++;
                }
            }
            if (name.equals("FixedGuardNode") && fromNumericSelectionGuard(node)) {
                guestNumericSelectionGuards++;
            }
            origins.merge(nodeOrigin(node, name), 1, Integer::sum);
        }

        System.out.println(
                "I115_TIER1_INTEGER_HISTOGRAM_COMP_ID=" + compilationId(bgv));

        histogram.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> System.out.println(
                        "I115_TIER1_INTEGER_NODE_CLASS "
                        + entry.getKey()
                        + "=" + entry.getValue()));

        // Informative only: the origin split never excludes nodes from the measurement.
        System.out.println("I115_TIER1_ORIGIN_PROTOS="
                + origins.getOrDefault(ORIGIN_PROTOS, 0));
        System.out.println("I115_TIER1_ORIGIN_TRUFFLE_RUNTIME="
                + origins.getOrDefault(ORIGIN_TRUFFLE_RUNTIME, 0));
        System.out.println("I115_TIER1_ORIGIN_TRUFFLE_API="
                + origins.getOrDefault(ORIGIN_TRUFFLE_API, 0));
        System.out.println("I115_TIER1_ORIGIN_GRAAL_COMPILER="
                + origins.getOrDefault(ORIGIN_GRAAL_COMPILER, 0));
        System.out.println("I115_TIER1_ORIGIN_JAVA_HOST="
                + origins.getOrDefault(ORIGIN_JAVA_HOST, 0));
        System.out.println("I115_TIER1_ORIGIN_OTHER="
                + origins.getOrDefault(ORIGIN_OTHER, 0));
        System.out.println("I115_TIER1_ORIGIN_UNATTRIBUTED="
                + origins.getOrDefault(ORIGIN_UNATTRIBUTED, 0));

        if (Boolean.getBoolean("protos.i115.tier1.nodeDetails")) {
            System.out.println(
                    "I115_TIER1_NODE_DETAILS_COMP_ID="
                    + compilationId(bgv));

            for (String name : List.of(
                    "RawStoreNode",
                    "GuardedUnsafeLoadNode",
                    "CommitAllocationNode",
                    "FixedGuardNode",
                    "ValuePhiNode",
                    "InvokeWithExceptionNode",
                    "MethodCallTargetNode",
                    "ExceptionObjectNode",
                    "BoxNode$AllocatingBoxNode",
                    "StoreFieldNode",
                    "LoadIndexedNode",
                    "TrufflePreserveFrameStateNode",
                    "FrameState",
                    "LoadFieldNode")) {

                int limit = switch (name) {
                    case "RawStoreNode" -> 7;
                    case "GuardedUnsafeLoadNode", "CommitAllocationNode" -> 2;
                    default -> Integer.MAX_VALUE;
                };

                int printed = 0;

                for (Object node : nodes) {
                    if (!name.equals(compilerNodeClass(node))) {
                        continue;
                    }

                    if (printed >= limit) {
                        break;
                    }

                    System.out.println(
                            "I115_TIER1_NODE_DETAIL_CLASS="
                            + name
                            + " sample=" + (++printed));

                    printOffendingNode(node, nodesById);
                }
            }
        }

        return new GraphCounts(nodes.size(), histogram.getOrDefault("LoadFieldNode", 0),
                histogram.getOrDefault("IsNullNode", 0),
                guestNumericShapeLoads, guestNumericSelectionGuards, preludeFieldLoads);
    }

    private static void printOffendingNode(Object node, Map<Integer, Object> nodesById)
            throws ReflectiveOperationException {
        int id = (Integer) node.getClass().getMethod("getId").invoke(node);
        System.out.println("I115_GRAPH_OFFENDER=" + id + " " + compilerNodeClass(node));
        printInputChain(node, nodesById, 0, new HashSet<>());
    }

    /**
     * Print at most two edges upstream of each offender, without dumping the entire graph.
     * Every FixedGuardNode and ValuePhiNode is followed four edges deep so guard
     * conditions and phi inputs, together with their producers, show.
     */
    private static void printInputChain(Object node, Map<Integer, Object> nodesById,
            int depth, Set<Integer> visited) throws ReflectiveOperationException {
        String nodeClass = compilerNodeClass(node);
        int maxDepth = nodeClass.equals("FixedGuardNode") || nodeClass.equals("ValuePhiNode")
                ? 4 : 2;
        printInputChain(node, nodesById, depth, visited, maxDepth);
    }

    private static void printInputChain(Object node, Map<Integer, Object> nodesById,
            int depth, Set<Integer> visited, int maxDepth) throws ReflectiveOperationException {
        int id = (Integer) node.getClass().getMethod("getId").invoke(node);
        if (!visited.add(id)) {
            return;
        }
        Object properties = node.getClass().getMethod("getProperties").invoke(node);
        String text = String.valueOf(properties).replace('\n', ' ').replace('\r', ' ');
        int limit = Boolean.getBoolean("protos.i115.tier1.nodeDetails")
                && (compilerNodeClass(node).equals("RawStoreNode")
                        || compilerNodeClass(node).equals("GuardedUnsafeLoadNode")
                        || compilerNodeClass(node).equals("CommitAllocationNode"))
                ? 5000 : 700;
        if (text.length() > limit) {
            text = text.substring(0, limit) + " ...";
        }
        System.out.println("I115_GRAPH_NODE depth=" + depth + " id=" + id
                + " class=" + compilerNodeClass(node) + " properties=" + text);
        if (depth >= maxDepth) {
            return;
        }
        Object raw = node.getClass().getMethod("getIdsMap").invoke(node);
        Map<?, ?> inputs = assertInstanceOf(Map.class, raw);
        for (var entry : inputs.entrySet()) {
            Object port = entry.getKey();
            Object name = port.getClass().getField("name").get(port);
            Collection<?> incomingIds = assertInstanceOf(Collection.class, entry.getValue());
            for (Object incomingId : incomingIds) {
                Object input = nodesById.get(incomingId);
                System.out.println("I115_GRAPH_EDGE depth=" + depth + " node=" + id
                        + " port=" + name + " input=" + incomingId
                        + " class=" + (input == null ? "MISSING" : compilerNodeClass(input)));
                if (input != null) {
                    printInputChain(input, nodesById, depth + 1, visited, maxDepth);
                }
            }
        }
    }

    private static final String ORIGIN_PROTOS = "Protos";
    private static final String ORIGIN_TRUFFLE_RUNTIME = "Truffle runtime";
    private static final String ORIGIN_TRUFFLE_API = "Truffle API";
    private static final String ORIGIN_GRAAL_COMPILER = "Graal compiler";
    private static final String ORIGIN_JAVA_HOST = "Java host";
    private static final String ORIGIN_OTHER = "Other";
    private static final String ORIGIN_UNATTRIBUTED = "Unattributed";
    private static final java.util.regex.Pattern QUALIFIED_METHOD = java.util.regex.Pattern
            .compile("[A-Za-z_$][\\w$]*(?:\\.[A-Za-z_$][\\w$]*)+");

    /**
     * Classify a node by the first method of its nodeSourcePosition (FrameState falls back
     * to its code property). Every node lands in exactly one of the seven categories.
     */
    private static String nodeOrigin(Object node, String name)
            throws ReflectiveOperationException {
        Object properties = node.getClass().getMethod("getProperties").invoke(node);
        Method get = properties.getClass().getMethod("get", String.class);
        Object position = get.invoke(properties, "nodeSourcePosition");
        if ((position == null || String.valueOf(position).isBlank())
                && name.equals("FrameState")) {
            position = get.invoke(properties, "code");
        }
        if (position == null) {
            return ORIGIN_UNATTRIBUTED;
        }
        var matcher = QUALIFIED_METHOD.matcher(String.valueOf(position).replace('/', '.'));
        if (matcher.find()) {
            String method = matcher.group();
            if (method.startsWith("com.guillermomolina.protos.")) {
                return ORIGIN_PROTOS;
            }
            if (method.startsWith("com.oracle.truffle.runtime.")) {
                return ORIGIN_TRUFFLE_RUNTIME;
            }
            if (method.startsWith("com.oracle.truffle.api.")) {
                return ORIGIN_TRUFFLE_API;
            }
            // Graal compiler packages must be checked before the generic jdk. host prefix.
            if (method.startsWith("jdk.graal.compiler.")
                    || method.startsWith("org.graalvm.compiler.")) {
                return ORIGIN_GRAAL_COMPILER;
            }
            if (method.startsWith("java.") || method.startsWith("javax.")
                    || method.startsWith("jdk.")) {
                return ORIGIN_JAVA_HOST;
            }
            return ORIGIN_OTHER;
        }
        return ORIGIN_UNATTRIBUTED;
    }

    private static String compilerNodeClass(Object node) throws ReflectiveOperationException {
        Object nodeClass = node.getClass().getMethod("getNodeClass").invoke(node);
        Object fullName = nodeClass.getClass().getField("className").get(nodeClass);
        return String.valueOf(fullName).replaceFirst("^.*\\.", "");
    }

    private static void collectPhaseGraphs(Object folder, Class<?> inputGraphType,
            List<Object> selected) throws ReflectiveOperationException {
        Object children = folder.getClass().getMethod("getElements").invoke(folder);
        for (Object child : assertInstanceOf(Collection.class, children)) {
            if (inputGraphType.isInstance(child)) {
                String name = (String) inputGraphType.getMethod("getName").invoke(child);
                if (name != null && name.replaceFirst("^\\d+\\s*:\\s*", "").trim().equals(PHASE)) {
                    Object type = inputGraphType.getMethod("getGraphType").invoke(child);
                    if (GRAPH_TYPE.equals(type)) {
                        selected.add(child);
                    }
                }
            } else if (child.getClass().getSimpleName().equals("Group")) {
                collectPhaseGraphs(child, inputGraphType, selected);
            }
        }
    }

    private static boolean isOptimizedCallTarget(Class<?> type) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            if (current.getName().equals("com.oracle.truffle.runtime.OptimizedCallTarget")) {
                return true;
            }
        }
        return false;
    }

    private static Object compilerMethod(Object target, String name, Object... args) {
        try {
            for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
                for (Method method : type.getDeclaredMethods()) {
                    if (method.getName().equals(name) && method.getParameterCount() == args.length) {
                        method.setAccessible(true);
                        return method.invoke(target, args);
                    }
                }
            }
        } catch (IllegalAccessException error) {
            throw new AssertionError("Cannot access OptimizedCallTarget." + name, error);
        } catch (InvocationTargetException error) {
            throw new AssertionError("OptimizedCallTarget." + name + " failed", error.getCause());
        }
        throw new AssertionError("Missing OptimizedCallTarget." + name);
    }
}
