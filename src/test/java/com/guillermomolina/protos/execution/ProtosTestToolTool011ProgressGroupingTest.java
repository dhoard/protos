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
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.guillermomolina.protos.cli.ProtosCli;
import com.guillermomolina.protos.runtime.ProtosArrayValue;
import com.guillermomolina.protos.runtime.ProtosPrelude;
import com.guillermomolina.protos.runtime.ProtosStringValue;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * TOOL011 repository progress grouping owned by RepositorySuite, projected by Main. TOOL012
 * toml-official subdivision is covered by {@link ProtosTestToolTool012TomlOfficialProgressGroupingTest}.
 */
final class ProtosTestToolTool011ProgressGroupingTest {
    private static final Path CORE = Path.of("protos", "lib", "core");
    private static final Path STANDARD_LIBRARY = Path.of("protos", "lib");
    private static final Path TOOL_ROOT = Path.of("protos", "tools", "test");
    private static final Path MAIN = TOOL_ROOT.resolve("Main.protos");
    private static final Path CONFORMANCE = Path.of("protos", "tests", "conformance");
    private static final Path CONFORMANCE_MANIFEST = CONFORMANCE.resolve("manifest.tsv");

    private static final String PRELUDE =
            "SuiteGraph: import(\"self:SuiteGraph\")\n"
                    + "RepositorySuite: import(\"self:RepositorySuite\")\n"
                    + "Arrays: import(\"std:collections/Array\")\n"
                    + "leaves: SuiteGraph.flattenLeaves(RepositorySuite.root)\n"
                    + "conformance: Arrays.filter(leaves, (leaf) => leaf.id == \"protos/conformance\")[0]\n";

    private static final String TOML_OFFICIAL = "conformance/standard-library/toml-official";

    /** Selector used where only the casePath matters; TOOL012 subdivides toml-official by it. */
    private static final String SELECTOR = "invalid/tool011/case";

    /** Conformance groups independent of retained Cases (TOOL012 toml-official is not). */
    private static final List<String> CONFORMANCE_GROUPS =
            List.of(
                    "conformance/values",
                    "conformance/object-model",
                    "conformance/control-concurrency",
                    "conformance/io",
                    "conformance/standard-library/toml",
                    "conformance/standard-library/collections",
                    "conformance/standard-library/data-text-test",
                    "conformance/language-surface",
                    "conformance/regression-maturity");

    /** Every accepted current non-library first casePath component and its owning group. */
    private static final Map<String, String> CONFORMANCE_ROOTS = conformanceRoots();

    private static final Map<String, String> ORDINARY_LEAF_GROUPS = ordinaryLeafGroups();

    private static final String VALUES_SOURCE = "boolean/lazy-binary.protos";
    private static final String CONTROL_SOURCE = "error/fail.protos";
    private static final String URI_SOURCE = "uri/parse.protos";

    private static final Pattern LISTED_CASE =
            Pattern.compile("\\{\"ref\":\"(v1\\.[0-9a-f]+)\",\"display\":\"([^\"]*)\"\\}");
    private static final Pattern GROUP_START = Pattern.compile("^\\[([^\\]]+)\\] 0/(\\d+)$");
    private static final Pattern GROUP_LINE = Pattern.compile("^\\[([^\\]]+)\\] .*$");

    @Test
    void mainHasNoPositionalPhaseMappingOrMainGroup() throws Exception {
        String main = Files.readString(MAIN, StandardCharsets.UTF_8);

        assertFalse(main.contains("phaseNames"));
        assertFalse(main.contains("phaseName:"));
        assertFalse(main.contains("\"main\""));
        for (String historical :
                List.of(
                        "\"uri\"",
                        "\"csv\"",
                        "\"math-integer\"",
                        "\"crypto-sha256\"",
                        "\"package-tool-version\"",
                        "\"process-snapshot\"",
                        "\"actor\"")) {
            assertFalse(main.contains(historical), historical);
        }
        assertTrue(main.contains("RepositorySuite.progressGroupAssignment(suite.leaf, suiteCaseKeys)"));
        assertTrue(main.contains(".caseGroupIndexes[cursor]"));
        assertTrue(main.contains("Manifest.casePath(spec)"));
        assertTrue(main.contains("CasePlan.selector(retainedCase)"));

        // Grouping is a projection over the final retained Logical Cases and
        // never runs on the --list-cases path.
        int requireComplete = main.indexOf("CaseSelection.requireComplete(caseSelection)");
        int listOnly = main.indexOf("listCases.ifTrue(");
        int executing = main.indexOf("listCases.ifFalse(");
        int grouping = main.indexOf("RepositorySuite.progressGroupAssignment(");
        int progressStart = main.indexOf("startProgress(", grouping);
        int scheduler = main.indexOf("logicalCompletions:");
        assertTrue(requireComplete >= 0);
        assertTrue(listOnly > requireComplete);
        assertTrue(executing > listOnly);
        assertTrue(grouping > executing);
        assertTrue(progressStart > grouping);
        assertTrue(scheduler > progressStart);
        assertEquals(1, occurrences(main, "RepositorySuite.progressGroupAssignment("));
        assertEquals(0, occurrences(main, "RepositorySuite.progressGroupNames("));

        // Failure attribution targets the Case's progress group, not its suite.
        assertTrue(main.contains("groupObservers[targetGroupIndex]("));
        assertTrue(main.contains("groupInfrastructureFailed[targetGroupIndex] = true"));
    }

    @Test
    void repositoryLeavesPresentExactConformanceAndSuiteIdDerivedGroups() throws Exception {
        ProtosArrayValue pairs =
                assertInstanceOf(
                        ProtosArrayValue.class,
                        completed(
                                PRELUDE
                                        + "Arrays.map(leaves, (leaf) => "
                                        + "Array(leaf.id, RepositorySuite.progressGroupNames(leaf, Array())))"));

        Map<String, List<String>> actual = leafGroups(pairs);
        Map<String, List<String>> expected = new LinkedHashMap<>();
        expected.put("protos/conformance", CONFORMANCE_GROUPS);
        ORDINARY_LEAF_GROUPS.forEach((id, name) -> expected.put(id, List.of(name)));

        assertEquals(expected, actual);
        assertEquals(32, actual.values().stream().mapToInt(List::size).sum());
    }

    @Test
    void progressGroupsFollowLeafIdentityRatherThanPosition() throws Exception {
        ProtosArrayValue pairs =
                assertInstanceOf(
                        ProtosArrayValue.class,
                        completed(
                                PRELUDE
                                        + "reversed: Array()\n"
                                        + "leaves.each((leaf) => {\n"
                                        + "    reversed = Array(leaf, ...reversed)\n"
                                        + "})\n"
                                        + "detached: SuiteGraph.leaf(\"protos/actor\", "
                                        + "\"protos/corpus/actor\", \"protos/test/actor\")\n"
                                        + "Arrays.map(Array(detached, ...reversed), (leaf) => "
                                        + "Array(leaf.id, RepositorySuite.progressGroupNames(leaf, Array())))"));

        assertEquals(25, pairs.indexedSize());
        List<String> order = new ArrayList<>();
        for (int index = 0; index < 25; index++) {
            ProtosArrayValue pair = arrayAt(pairs, index);
            String id = stringAt(pair, 0);
            List<String> names = strings(arrayAt(pair, 1));
            order.add(id);
            if (id.equals("protos/conformance")) {
                assertEquals(CONFORMANCE_GROUPS, names);
            } else {
                assertEquals(List.of(ORDINARY_LEAF_GROUPS.get(id)), names, id);
            }
        }
        assertEquals("protos/actor", order.get(0));
        assertEquals("protos/package-tool/project-projection", order.get(1));
        assertEquals("protos/conformance", order.get(24));
    }

    @Test
    void everyCurrentConformanceRootAndManifestPathClassifiesExactly() throws Exception {
        List<String> paths = new ArrayList<>();
        for (String root : CONFORMANCE_ROOTS.keySet()) {
            paths.add(root + "/tool011.protos");
        }
        paths.add("library/collections/tool011.protos");
        paths.add("library/collections/nested/tool011.protos");
        paths.add("library/json/tool011.protos");
        paths.add("library/text/tool011.protos");
        paths.add("library/test/nested/tool011.protos");
        paths.add("library/toml/tool011.protos");
        paths.add("library/toml/official/tool011.protos");
        paths.add("library/toml/official/suite/tool011.protos");
        int synthetic = paths.size();

        for (String line : Files.readAllLines(CONFORMANCE_MANIFEST, StandardCharsets.UTF_8)) {
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            paths.add(line.substring(0, line.indexOf('\t')));
        }
        assertTrue(paths.size() > synthetic);

        StringBuilder literal = new StringBuilder("Array(");
        for (int index = 0; index < paths.size(); index++) {
            if (index > 0) {
                literal.append(", ");
            }
            literal.append('"').append(paths.get(index)).append('"');
        }
        literal.append(')');

        ProtosArrayValue names =
                assertInstanceOf(
                        ProtosArrayValue.class,
                        completed(
                                PRELUDE
                                        + "Arrays.map("
                                        + literal
                                        + ", (path) => RepositorySuite.progressGroupName("
                                        + "conformance, path, \""
                                        + SELECTOR
                                        + "\"))"));

        assertEquals(paths.size(), names.indexedSize());
        Set<String> observedGroups = new LinkedHashSet<>();
        for (int index = 0; index < paths.size(); index++) {
            String path = paths.get(index);
            String expected = expectedConformanceGroup(path);
            assertNotNull(expected, () -> "unclassified current conformance root: " + path);
            assertEquals(expected, stringAt(names, index), path);
            observedGroups.add(expected);
        }
        Set<String> expectedGroups = new LinkedHashSet<>(CONFORMANCE_GROUPS);
        expectedGroups.add(TOML_OFFICIAL + "/invalid/tool011");
        assertEquals(expectedGroups, observedGroups);
    }

    @Test
    void unknownOrMalformedConformancePathsFailClosed() throws Exception {
        assertFailed(conformanceGroupName("unknown/a.protos"));
        assertFailed(conformanceGroupName("main/a.protos"));
        assertFailed(conformanceGroupName("integer.protos"));
    }

    @Test
    void libraryPathsOutsideTheStandardLibraryGroupsFailClosed() throws Exception {
        assertFailed(conformanceGroupName("library/a.protos"));
        assertFailed(conformanceGroupName("library/unknown/a.protos"));
        assertFailed(conformanceGroupName("library/toml/other/a.protos"));
    }

    private static String conformanceGroupName(String path) {
        return PRELUDE
                + "RepositorySuite.progressGroupName(conformance, \""
                + path
                + "\", \""
                + SELECTOR
                + "\")";
    }

    @Test
    void ordinaryLeafOutsideRepositoryPrefixFailsClosed() throws Exception {
        assertFailed(
                PRELUDE
                        + "RepositorySuite.progressGroupNames(SuiteGraph.leaf("
                        + "\"other/actor\", \"protos/corpus/actor\", \"protos/test/actor\"), Array())");
        assertFailed(
                PRELUDE
                        + "RepositorySuite.progressGroupNames(SuiteGraph.leaf("
                        + "\"protos\", \"protos/corpus/actor\", \"protos/test/actor\"), Array())");
    }

    @Test
    void selectedSourcesCreateOnlyTheirGroupsAndPreserveTheCaseSet() {
        String[] selectors = {
            "--file", conformanceFile(VALUES_SOURCE),
            "--file", conformanceFile(CONTROL_SOURCE),
            "--file", uriFile()
        };
        List<String[]> listed = listCases(selectors);
        int values = countDisplayed(listed, "protos/corpus/conformance:" + VALUES_SOURCE + "::");
        int control = countDisplayed(listed, "protos/corpus/conformance:" + CONTROL_SOURCE + "::");
        int uri = countDisplayed(listed, "protos/corpus/library/uri:" + URI_SOURCE + "::");
        assertTrue(values > 0 && control > 0 && uri > 0);
        assertEquals(listed.size(), values + control + uri);

        R result = run(concat(new String[] {"test"}, selectors));

        assertEquals(0, result.code(), result::err);
        Map<String, Integer> groups = groupStarts(result.err());
        Map<String, Integer> expected = new LinkedHashMap<>();
        expected.put("conformance/values", values);
        expected.put("conformance/control-concurrency", control);
        expected.put("library/uri", uri);
        assertEquals(expected, groups);
        assertEquals(listed.size(), groups.values().stream().mapToInt(Integer::intValue).sum());
        assertEquals(expected.keySet(), groupLabels(result.err()));
        assertTrue(
                result.err().endsWith(listed.size() + " passed, 0 failed\n"), result::err);
    }

    @Test
    void exactSingleCaseDisplaysOnlyItsOwningGroup() {
        String[] files = {
            "--file", conformanceFile(VALUES_SOURCE),
            "--file", conformanceFile(CONTROL_SOURCE)
        };
        String ref = null;
        for (String[] entry : listCases(files)) {
            if (entry[1].contains(":" + CONTROL_SOURCE + "::")) {
                ref = entry[0];
                break;
            }
        }
        assertNotNull(ref);

        R result = run(concat(concat(new String[] {"test"}, files), new String[] {"--case", ref}));

        assertEquals(0, result.code(), result::err);
        assertEquals(
                "[conformance/control-concurrency] 0/1\n"
                        + "[conformance/control-concurrency] 1/1 passed\n"
                        + "1 passed, 0 failed\n",
                result.err());
    }

    @Test
    void listCasesStaysFreeOfProgress() {
        R result = run("test", "--list-cases", "--file", conformanceFile(VALUES_SOURCE));

        assertEquals(0, result.code());
        assertEquals("", result.err());
        assertTrue(result.out().startsWith("{\"schema\":\"protos.test.cases/v1\""));
    }

    private static Map<String, String> conformanceRoots() {
        Map<String, String> roots = new LinkedHashMap<>();
        for (String root :
                List.of(
                        "integer",
                        "float",
                        "numeric-conversion",
                        "boolean",
                        "numeric-equality",
                        "equality",
                        "collections",
                        "number",
                        "string",
                        "bytes")) {
            roots.put(root, "conformance/values");
        }
        for (String root :
                List.of(
                        "call",
                        "object",
                        "reflection",
                        "object-structural",
                        "matching",
                        "execution-context")) {
            roots.put(root, "conformance/object-model");
        }
        for (String root : List.of("error", "control", "future")) {
            roots.put(root, "conformance/control-concurrency");
        }
        for (String root : List.of("path", "encoding", "text-reader", "text-writer", "network")) {
            roots.put(root, "conformance/io");
        }
        for (String root : List.of("core-surface", "surface-sugar")) {
            roots.put(root, "conformance/language-surface");
        }
        for (String root : List.of("regression", "maturity")) {
            roots.put(root, "conformance/regression-maturity");
        }
        return Map.copyOf(roots);
    }

    /** Test-side oracle mirroring the ratified TOOL011 conformance policy. */
    private static String expectedConformanceGroup(String path) {
        String directory = path.substring(0, path.lastIndexOf('/'));
        if (directory.equals("library/toml/official")
                || directory.startsWith("library/toml/official/")) {
            return TOML_OFFICIAL + "/invalid/tool011";
        }
        if (directory.equals("library/toml")) {
            return "conformance/standard-library/toml";
        }
        if (directory.equals("library/collections")
                || directory.startsWith("library/collections/")) {
            return "conformance/standard-library/collections";
        }
        for (String data : List.of("library/json", "library/text", "library/test")) {
            if (directory.equals(data) || directory.startsWith(data + "/")) {
                return "conformance/standard-library/data-text-test";
            }
        }
        if (path.startsWith("library/")) {
            return null;
        }
        return CONFORMANCE_ROOTS.get(path.substring(0, path.indexOf('/')));
    }

    private static Map<String, String> ordinaryLeafGroups() {
        Map<String, String> groups = new LinkedHashMap<>();
        for (String name :
                List.of(
                        "process-snapshot",
                        "actor",
                        "group",
                        "package-toml",
                        "library/uri",
                        "library/csv",
                        "library/cli",
                        "library/math/integer",
                        "library/crypto/sha256",
                        "library/network/ip-addresses",
                        "library/network/ip-endpoints",
                        "library/semver",
                        "library/datetime",
                        "library/regex",
                        "library/logging",
                        "package-tool/version",
                        "package-tool/lock",
                        "package-tool/resolution-input",
                        "package-tool/content-identity",
                        "package-tool/resolution-input-lock",
                        "package-tool/resolution-root",
                        "package-tool/execution-plan",
                        "package-tool/project-projection")) {
            groups.put("protos/" + name, name);
        }
        return groups;
    }

    private static Map<String, List<String>> leafGroups(ProtosArrayValue pairs) {
        Map<String, List<String>> groups = new LinkedHashMap<>();
        int size = pairs.indexedSize();
        for (int index = 0; index < size; index++) {
            ProtosArrayValue pair = arrayAt(pairs, index);
            groups.put(stringAt(pair, 0), strings(arrayAt(pair, 1)));
        }
        return groups;
    }

    private static String conformanceFile(String source) {
        return CONFORMANCE.resolve(source).toString();
    }

    private static String uriFile() {
        return Path.of("protos", "tests", "library", "uri", "parse.protos").toString();
    }

    private static List<String[]> listCases(String[] selectors) {
        R listing = run(concat(new String[] {"test", "--list-cases"}, selectors));
        assertEquals(0, listing.code(), listing::err);
        assertEquals("", listing.err());
        List<String[]> cases = new ArrayList<>();
        Matcher matcher = LISTED_CASE.matcher(listing.out());
        while (matcher.find()) {
            cases.add(new String[] {matcher.group(1), matcher.group(2)});
        }
        return cases;
    }

    private static int countDisplayed(List<String[]> listed, String prefix) {
        int count = 0;
        for (String[] entry : listed) {
            if (entry[1].startsWith(prefix)) {
                count++;
            }
        }
        return count;
    }

    /** Ordered group label to declared total, from each group's initial "0/N" line. */
    private static Map<String, Integer> groupStarts(String err) {
        Map<String, Integer> starts = new LinkedHashMap<>();
        for (String line : err.split("\n")) {
            Matcher matcher = GROUP_START.matcher(line);
            if (matcher.matches()) {
                assertFalse(starts.containsKey(matcher.group(1)), line);
                starts.put(matcher.group(1), Integer.parseInt(matcher.group(2)));
            }
        }
        return starts;
    }

    private static Set<String> groupLabels(String err) {
        Set<String> labels = new LinkedHashSet<>();
        for (String line : err.split("\n")) {
            Matcher matcher = GROUP_LINE.matcher(line);
            if (matcher.matches()) {
                labels.add(matcher.group(1));
            }
        }
        return labels;
    }

    private static String[] concat(String[] first, String[] second) {
        String[] result = new String[first.length + second.length];
        System.arraycopy(first, 0, result, 0, first.length);
        System.arraycopy(second, 0, result, first.length, second.length);
        return result;
    }

    private static ProtosArrayValue arrayAt(ProtosArrayValue array, int index) {
        return assertInstanceOf(
                ProtosArrayValue.class, array.indexedAt(index));
    }

    private static String stringAt(ProtosArrayValue array, int index) {
        return assertInstanceOf(
                        ProtosStringValue.class, array.indexedAt(index))
                .value();
    }

    private static List<String> strings(ProtosArrayValue array) {
        List<String> values = new ArrayList<>();
        int size = array.indexedSize();
        for (int index = 0; index < size; index++) {
            values.add(stringAt(array, index));
        }
        return values;
    }

    private static int occurrences(String text, String needle) {
        int count = 0;
        int index = 0;
        while ((index = text.indexOf(needle, index)) >= 0) {
            count++;
            index += needle.length();
        }
        return count;
    }

    private static Object completed(String source) throws Exception {
        ProtosExecutionOutcome outcome = execute(source);
        assertEquals(
                ProtosExecutionOutcome.State.COMPLETED,
                outcome.state(),
                () -> "expected completion, error=" + outcome.error());
        return outcome.value();
    }

    private static void assertFailed(String source) throws Exception {
        ProtosExecutionOutcome outcome = execute(source);
        assertEquals(
                ProtosExecutionOutcome.State.FAILED,
                outcome.state(),
                () -> "expected fail-closed result, value=" + outcome.value());
    }

    private static ProtosExecutionOutcome execute(String source) throws Exception {
        ProtosBundledToolModuleResolver resolver =
                new ProtosBundledToolModuleResolver(
                        "test",
                        TOOL_ROOT,
                        TOOL_ROOT.resolveSibling("shared"),
                        new ProtosStandardLibraryModuleResolver(STANDARD_LIBRARY));
        ProtosPrelude prelude = new ProtosCoreBootstrap().bootstrap(CORE, resolver);
        return ProtosTestExecutionSupport.execute(source, prelude.newModuleActivation());
    }

    private static R run(String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();

        int code =
                new ProtosCli()
                        .run(
                                args,
                                InputStream.nullInputStream(),
                                new PrintStream(out),
                                new PrintStream(err));

        return new R(
                code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    private record R(int code, String out, String err) {}
}
