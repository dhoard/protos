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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.guillermomolina.protos.cli.ProtosCli;
import com.guillermomolina.protos.runtime.ProtosActivation;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * TOOL012 presentation-only subdivision of the toml-official conformance progress group into
 * selector-directory subgroups of at most 100 retained Logical Cases.
 */
final class ProtosTestToolTool012TomlOfficialProgressGroupingTest {
    private static final Path CORE = Path.of("protos", "lib", "core");
    private static final Path STANDARD_LIBRARY = Path.of("protos", "lib");
    private static final Path TOOL_ROOT = Path.of("protos", "tools", "test");
    private static final Path CONFORMANCE = Path.of("protos", "tests", "conformance");
    private static final Path CONFORMANCE_MANIFEST = CONFORMANCE.resolve("manifest.tsv");
    private static final String TOML_OFFICIAL_ROOT = "library/toml/official";
    private static final String TOML_OFFICIAL = "conformance/standard-library/toml-official";
    private static final int MAX_CASES_PER_GROUP = 100;

    private static final String SPEC_SOURCE = "library/toml/official/suite/valid-spec-1.1.0.protos";
    private static final String BOOL_SOURCE = "library/toml/official/suite/valid-bool.protos";

    private static final String PRELUDE =
            "SuiteGraph: import(\"self:SuiteGraph\")\n"
                    + "RepositorySuite: import(\"self:RepositorySuite\")\n"
                    + "Arrays: import(\"std:collections/Array\")\n"
                    + "leaves: SuiteGraph.flattenLeaves(RepositorySuite.root)\n"
                    + "conformance: Arrays.filter(leaves, (leaf) => leaf.id == \"protos/conformance\")[0]\n";

    private static final Pattern TEST_SELECTOR = Pattern.compile("^    Test\\(\"([^\"]+)\",");
    private static final Pattern LISTED_CASE =
            Pattern.compile("\\{\"ref\":\"(v1\\.[0-9a-f]+)\",\"display\":\"([^\"]*)\"\\}");
    private static final Pattern GROUP_START = Pattern.compile("^\\[([^\\]]+)\\] 0/(\\d+)$");

    @Test
    void completeCorpusGroupsAreBoundedCompleteUniqueAndDeterministic() throws Exception {
        List<String[]> keys = corpusCaseKeys();
        assertTrue(keys.size() > MAX_CASES_PER_GROUP, "corpus must exceed one group");

        ProtosArrayValue result =
                assertInstanceOf(
                        ProtosArrayValue.class,
                        completed(
                                keys,
                                PRELUDE
                                        + "first: RepositorySuite.progressGroupNames(conformance, keys)\n"
                                        + "second: RepositorySuite.progressGroupNames(conformance, keys)\n"
                                        + "owners: Arrays.map(keys, (key) => {\n"
                                        + "    (casePath, selector): key\n"
                                        + "    RepositorySuite.progressGroupName(conformance, casePath, selector)\n"
                                        + "})\n"
                                        + "indexed: Arrays.map(keys, (key) => {\n"
                                        + "    (casePath, selector): key\n"
                                        + "    first[RepositorySuite.progressGroupIndex("
                                        + "conformance, first, casePath, selector)]\n"
                                        + "})\n"
                                        + "assigned: RepositorySuite.progressGroupAssignment(conformance, keys)\n"
                                        + "assignedOwners: Arrays.map(assigned.caseGroupIndexes, (groupIndex) => {\n"
                                        + "    first[groupIndex]\n"
                                        + "})\n"
                                        + "Array(first, second, owners, indexed, assigned.names, assignedOwners)"));

        List<String> names = strings(arrayAt(result, 0));
        List<String> owners = strings(arrayAt(result, 2));
        assertEquals(names, strings(arrayAt(result, 1)));
        assertEquals(owners, strings(arrayAt(result, 3)));
        // PERF031-F: the single-pass assignment Main consumes answers exactly
        // the published names and, per Case, the same group as
        // progressGroupIndex.
        assertEquals(names, strings(arrayAt(result, 4)));
        assertEquals(owners, strings(arrayAt(result, 5)));
        assertEquals(keys.size(), owners.size());

        // Each Case has exactly one owner; the toml-official subgroup names are
        // exactly the distinct owners, in order of first Case.
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (int index = 0; index < keys.size(); index++) {
            String owner = owners.get(index);
            String selector = keys.get(index)[1];
            assertEquals(TOML_OFFICIAL + "/" + directory(selector), owner, selector);
            counts.merge(owner, 1, Integer::sum);
        }
        List<String> subgroups = new ArrayList<>();
        for (String name : names) {
            if (name.startsWith(TOML_OFFICIAL)) {
                subgroups.add(name);
            }
        }
        assertEquals(new ArrayList<>(counts.keySet()), subgroups);
        assertEquals(new LinkedHashSet<>(subgroups).size(), subgroups.size());
        assertTrue(!names.contains(TOML_OFFICIAL), "no monolithic toml-official group");
        assertEquals(keys.size(), counts.values().stream().mapToInt(Integer::intValue).sum());
        counts.forEach(
                (name, count) ->
                        assertTrue(count <= MAX_CASES_PER_GROUP, name + " has " + count + " Cases"));

        // The one source above the limit is split along its decoder/encoder directions.
        assertTrue(counts.containsKey(TOML_OFFICIAL + "/valid/spec-1.1.0"));
        assertTrue(counts.containsKey(TOML_OFFICIAL + "/encoder/spec-1.1.0"));
        // Upstream root-level Cases present under their direction alone.
        assertTrue(counts.containsKey(TOML_OFFICIAL + "/valid"));
        assertTrue(counts.containsKey(TOML_OFFICIAL + "/encoder"));
    }

    @Test
    void directorySelectionPreservesTheCorpusCaseSet() throws Exception {
        List<String[]> listed =
                listCases("--directory", CONFORMANCE.resolve(TOML_OFFICIAL_ROOT).toString());

        List<String> expected = new ArrayList<>();
        for (String[] key : corpusCaseKeys()) {
            expected.add("protos/corpus/conformance:" + key[0] + "::" + key[1]);
        }
        List<String> displayed = new ArrayList<>();
        for (String[] entry : listed) {
            displayed.add(entry[1]);
        }
        assertEquals(expected, displayed);
    }

    @Test
    void malformedSubdividedSelectorsAndUnknownNamesFailClosed() throws Exception {
        for (String selector : List.of("", "valid", "valid/bool/", "valid//bool", "/valid/bool")) {
            assertFailed(
                    PRELUDE
                            + "RepositorySuite.progressGroupName(conformance, \""
                            + BOOL_SOURCE
                            + "\", \""
                            + selector
                            + "\")");
        }
        assertFailed(
                PRELUDE
                        + "RepositorySuite.progressGroupIndex(conformance, "
                        + "Array(\""
                        + TOML_OFFICIAL
                        + "\"), \""
                        + BOOL_SOURCE
                        + "\", \"valid/bool/bool\")");
    }

    @Test
    void selectedSourceShowsOnlyItsSubgroupsAndPreservesTheCaseSet() {
        String file = CONFORMANCE.resolve(BOOL_SOURCE).toString();
        List<String[]> listed = listCases("--file", file);
        assertEquals(2, listed.size());

        R result = run("test", "--file", file);

        assertEquals(0, result.code(), result::err);
        Map<String, Integer> expected = new LinkedHashMap<>();
        expected.put(TOML_OFFICIAL + "/valid/bool", 1);
        expected.put(TOML_OFFICIAL + "/encoder/bool", 1);
        assertEquals(expected, groupStarts(result.err()));
        assertTrue(result.err().endsWith("2 passed, 0 failed\n"), result::err);
    }

    @Test
    void exactCaseInSplitSourceDisplaysOnlyItsOwningSubgroup() {
        String file = CONFORMANCE.resolve(SPEC_SOURCE).toString();
        String ref = null;
        for (String[] entry : listCases("--file", file)) {
            if (entry[1].contains("::encoder/spec-1.1.0/")) {
                ref = entry[0];
                break;
            }
        }
        assertNotNull(ref);

        R result = run("test", "--file", file, "--case", ref);

        assertEquals(0, result.code(), result::err);
        assertEquals(
                "[" + TOML_OFFICIAL + "/encoder/spec-1.1.0] 0/1\n"
                        + "[" + TOML_OFFICIAL + "/encoder/spec-1.1.0] 1/1 passed\n"
                        + "1 passed, 0 failed\n",
                result.err());
    }

    /** Array(casePath, selector) of every manifest-listed toml-official Case, in plan order. */
    private static List<String[]> corpusCaseKeys() throws Exception {
        List<String[]> keys = new ArrayList<>();
        for (String line : Files.readAllLines(CONFORMANCE_MANIFEST, StandardCharsets.UTF_8)) {
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            String path = line.substring(0, line.indexOf('\t'));
            if (!path.startsWith(TOML_OFFICIAL_ROOT + "/")) {
                continue;
            }
            for (String source :
                    Files.readAllLines(CONFORMANCE.resolve(path), StandardCharsets.UTF_8)) {
                Matcher matcher = TEST_SELECTOR.matcher(source);
                if (matcher.find()) {
                    keys.add(new String[] {path, matcher.group(1)});
                }
            }
        }
        return keys;
    }

    /** Test-side oracle: the selector without its final segment. */
    private static String directory(String selector) {
        return selector.substring(0, selector.lastIndexOf('/'));
    }

    private static List<String[]> listCases(String... selectors) {
        String[] args = new String[selectors.length + 2];
        args[0] = "test";
        args[1] = "--list-cases";
        System.arraycopy(selectors, 0, args, 2, selectors.length);
        R listing = run(args);
        assertEquals(0, listing.code(), listing::err);
        assertEquals("", listing.err());
        List<String[]> cases = new ArrayList<>();
        Matcher matcher = LISTED_CASE.matcher(listing.out());
        while (matcher.find()) {
            cases.add(new String[] {matcher.group(1), matcher.group(2)});
        }
        return cases;
    }

    /** Ordered group label to declared total, from each group's initial "0/N" line. */
    private static Map<String, Integer> groupStarts(String err) {
        Map<String, Integer> starts = new LinkedHashMap<>();
        for (String line : err.split("\n")) {
            Matcher matcher = GROUP_START.matcher(line);
            if (matcher.matches()) {
                starts.put(matcher.group(1), Integer.parseInt(matcher.group(2)));
            }
        }
        return starts;
    }

    private static ProtosArrayValue arrayAt(ProtosArrayValue array, int index) {
        return assertInstanceOf(
                ProtosArrayValue.class, array.indexedAt(index));
    }

    private static List<String> strings(ProtosArrayValue array) {
        List<String> values = new ArrayList<>();
        int size = array.indexedSize();
        for (int index = 0; index < size; index++) {
            values.add(
                    assertInstanceOf(
                                    ProtosStringValue.class,
                                    array.indexedAt(index))
                            .value());
        }
        return values;
    }

    /**
     * Executes {@code source} with {@code keys} bound as a host-built Array of
     * Array(casePath, selector), avoiding one generated source statement per corpus Case.
     */
    private static Object completed(List<String[]> keys, String source) throws Exception {
        ProtosPrelude prelude = newPrelude();
        ProtosActivation activation = prelude.newModuleActivation();
        List<ProtosArrayValue> elements = new ArrayList<>(keys.size());
        for (String[] key : keys) {
            elements.add(
                    prelude.newArray(
                            List.of(new ProtosStringValue(key[0]), new ProtosStringValue(key[1]))));
        }
        activation.context().createLocalSlot("keys", prelude.newArray(elements));
        ProtosExecutionOutcome outcome = ProtosTestExecutionSupport.execute(source, activation);
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
        return ProtosTestExecutionSupport.execute(source, newPrelude().newModuleActivation());
    }

    private static ProtosPrelude newPrelude() throws Exception {
        ProtosBundledToolModuleResolver resolver =
                new ProtosBundledToolModuleResolver(
                        "test",
                        TOOL_ROOT,
                        TOOL_ROOT.resolveSibling("shared"),
                        new ProtosStandardLibraryModuleResolver(STANDARD_LIBRARY));
        return new ProtosCoreBootstrap().bootstrap(CORE, resolver);
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
