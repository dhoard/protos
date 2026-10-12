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
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.guillermomolina.protos.runtime.ProtosActivation;
import com.guillermomolina.protos.runtime.ProtosArrayValue;
import com.guillermomolina.protos.runtime.ProtosObjectValue;
import com.guillermomolina.protos.runtime.ProtosPrelude;
import com.guillermomolina.protos.runtime.ProtosStringValue;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** TOOL005 conformance for D122/D123 suite identity plus D126/D125 leaf identities. */
final class ProtosTestToolSuiteGraphTest {
    private static final Path CORE = Path.of("protos", "lib", "core");
    private static final Path STANDARD_LIBRARY = Path.of("protos", "lib");
    private static final Path TOOL_ROOT = Path.of("protos", "tools", "test");

    @Test
    void explicitNestedCompositionExpandsLeavesInDeclarationOrder() throws Exception {
        ProtosArrayValue flattened =
                assertInstanceOf(
                        ProtosArrayValue.class,
                        completed(
                                "SuiteGraph: import(\"self:SuiteGraph\")\n"
                                        + "a: SuiteGraph.leaf(\"a\", \"corpus/test\", \"test/ordinary\")\n"
                                        + "b: SuiteGraph.leaf(\"b\", \"corpus/test\", \"test/ordinary\")\n"
                                        + "c: SuiteGraph.leaf(\"c\", \"corpus/test\", \"test/ordinary\")\n"
                                        + "d: SuiteGraph.leaf(\"d\", \"corpus/test\", \"test/ordinary\")\n"
                                        + "e: SuiteGraph.leaf(\"e\", \"corpus/test\", \"test/ordinary\")\n"
                                        + "f: SuiteGraph.leaf(\"f\", \"corpus/test\", \"test/ordinary\")\n"
                                        + "g: SuiteGraph.leaf(\"g\", \"corpus/test\", \"test/ordinary\")\n"
                                        + "deep: SuiteGraph.suite(\"deep\", Array(d, e))\n"
                                        + "nested: SuiteGraph.suite(\"nested\", Array(b, c, deep))\n"
                                        + "root: SuiteGraph.suite(\"root\", Array(a, nested, f, g))\n"
                                        + "SuiteGraph.flattenLeafIds(root)"));

        assertEquals(7, flattened.indexedSize());
        assertTrue(flattened.isFrozen());
        assertLeafId(flattened, 0, "a");
        assertLeafId(flattened, 1, "b");
        assertLeafId(flattened, 2, "c");
        assertLeafId(flattened, 3, "d");
        assertLeafId(flattened, 4, "e");
        assertLeafId(flattened, 5, "f");
        assertLeafId(flattened, 6, "g");
    }

    @Test
    void stableLogicalIdentityIsPreservedLiterally() throws Exception {
        String logicalId = "protos/library.leaf-a";
        ProtosArrayValue flattened =
                assertInstanceOf(
                        ProtosArrayValue.class,
                        completed(
                                "SuiteGraph: import(\"self:SuiteGraph\")\n"
                                        + "leaf: SuiteGraph.leaf(\""
                                        + logicalId
                                        + "\", \"corpus/test\", \"test/ordinary\")\n"
                                        + "root: SuiteGraph.suite(\"protos/root-suite\", Array(leaf))\n"
                                        + "SuiteGraph.flattenLeafIds(root)"));

        assertEquals(1, flattened.indexedSize());
        assertLeafId(flattened, 0, logicalId);
    }

    @Test
    void repositorySuiteUsesRatifiedRootAndExactCurrentLeafOrder() throws Exception {
        ProtosStringValue rootId =
                assertInstanceOf(
                        ProtosStringValue.class,
                        completed(
                                "RepositorySuite: import(\"self:RepositorySuite\")\n"
                                        + "RepositorySuite.root.id"));
        assertEquals("protos/repository", rootId.value());

        ProtosArrayValue flattened =
                assertInstanceOf(
                        ProtosArrayValue.class,
                        completed(
                                "SuiteGraph: import(\"self:SuiteGraph\")\n"
                                        + "RepositorySuite: import(\"self:RepositorySuite\")\n"
                                        + "SuiteGraph.flattenLeafIds(RepositorySuite.root)"));

        assertEquals(24, flattened.indexedSize());
        assertTrue(flattened.isFrozen());
        assertLeafId(flattened, 0, "protos/conformance");
        assertLeafId(flattened, 1, "protos/process-snapshot");
        assertLeafId(flattened, 2, "protos/actor");
        assertLeafId(flattened, 3, "protos/group");
        assertLeafId(flattened, 4, "protos/package-toml");
        assertLeafId(flattened, 5, "protos/library/uri");
        assertLeafId(flattened, 6, "protos/library/csv");
        assertLeafId(flattened, 7, "protos/library/cli");
        assertLeafId(flattened, 8, "protos/library/math/integer");
        assertLeafId(flattened, 9, "protos/library/crypto/sha256");
        assertLeafId(flattened, 10, "protos/library/network/ip-addresses");
        assertLeafId(flattened, 11, "protos/library/network/ip-endpoints");
        assertLeafId(flattened, 12, "protos/library/semver");
        assertLeafId(flattened, 13, "protos/library/datetime");
        assertLeafId(flattened, 14, "protos/library/regex");
        assertLeafId(flattened, 15, "protos/library/logging");
        assertLeafId(flattened, 16, "protos/package-tool/version");
        assertLeafId(flattened, 17, "protos/package-tool/lock");
        assertLeafId(flattened, 18, "protos/package-tool/resolution-input");
        assertLeafId(flattened, 19, "protos/package-tool/content-identity");
        assertLeafId(flattened, 20, "protos/package-tool/resolution-input-lock");
        assertLeafId(flattened, 21, "protos/package-tool/resolution-root");
        assertLeafId(flattened, 22, "protos/package-tool/execution-plan");
        assertLeafId(flattened, 23, "protos/package-tool/project-projection");
    }

    @Test
    void repositorySuiteUsesRatifiedD126CorporaInLeafOrder() throws Exception {
        ProtosArrayValue leaves =
                assertInstanceOf(
                        ProtosArrayValue.class,
                        completed(
                                "SuiteGraph: import(\"self:SuiteGraph\")\n"
                                        + "RepositorySuite: import(\"self:RepositorySuite\")\n"
                                        + "SuiteGraph.flattenLeaves(RepositorySuite.root)"));

        assertEquals(24, leaves.indexedSize());
        assertTrue(leaves.isFrozen());
        assertLeafCorpus(leaves, 0, "protos/conformance", "protos/corpus/conformance");
        assertLeafCorpus(leaves, 1, "protos/process-snapshot", "protos/corpus/process-snapshot");
        assertLeafCorpus(leaves, 2, "protos/actor", "protos/corpus/actor");
        assertLeafCorpus(leaves, 3, "protos/group", "protos/corpus/group");
        assertLeafCorpus(leaves, 4, "protos/package-toml", "protos/corpus/package-toml");
        assertLeafCorpus(leaves, 5, "protos/library/uri", "protos/corpus/library/uri");
        assertLeafCorpus(leaves, 6, "protos/library/csv", "protos/corpus/library/csv");
        assertLeafCorpus(leaves, 7, "protos/library/cli", "protos/corpus/library/cli");
        assertLeafCorpus(leaves, 8, "protos/library/math/integer", "protos/corpus/library/math/integer");
        assertLeafCorpus(leaves, 9, "protos/library/crypto/sha256", "protos/corpus/library/crypto/sha256");
        assertLeafCorpus(leaves, 10, "protos/library/network/ip-addresses", "protos/corpus/library/network/ip-addresses");
        assertLeafCorpus(leaves, 11, "protos/library/network/ip-endpoints", "protos/corpus/library/network/ip-endpoints");
        assertLeafCorpus(leaves, 12, "protos/library/semver", "protos/corpus/library/semver");
        assertLeafCorpus(leaves, 13, "protos/library/datetime", "protos/corpus/library/datetime");
        assertLeafCorpus(leaves, 14, "protos/library/regex", "protos/corpus/library/regex");
        assertLeafCorpus(leaves, 15, "protos/library/logging", "protos/corpus/library/logging");
        assertLeafCorpus(leaves, 16, "protos/package-tool/version", "protos/corpus/package-tool/version");
        assertLeafCorpus(leaves, 17, "protos/package-tool/lock", "protos/corpus/package-tool/lock");
        assertLeafCorpus(leaves, 18, "protos/package-tool/resolution-input", "protos/corpus/package-tool/resolution-input");
        assertLeafCorpus(leaves, 19, "protos/package-tool/content-identity", "protos/corpus/package-tool/content-identity");
        assertLeafCorpus(leaves, 20, "protos/package-tool/resolution-input-lock", "protos/corpus/package-tool/resolution-input-lock");
        assertLeafCorpus(leaves, 21, "protos/package-tool/resolution-root", "protos/corpus/package-tool/resolution-root");
        assertLeafCorpus(leaves, 22, "protos/package-tool/execution-plan", "protos/corpus/package-tool/execution-plan");
        assertLeafCorpus(leaves, 23, "protos/package-tool/project-projection", "protos/corpus/package-tool/project-projection");
    }

    @Test
    void repositorySuiteUsesRatifiedD125RequirementsInLeafOrder() throws Exception {
        ProtosArrayValue leaves =
                assertInstanceOf(
                        ProtosArrayValue.class,
                        completed(
                                "SuiteGraph: import(\"self:SuiteGraph\")\n"
                                        + "RepositorySuite: import(\"self:RepositorySuite\")\n"
                                        + "SuiteGraph.flattenLeaves(RepositorySuite.root)"));

        assertEquals(24, leaves.indexedSize());
        assertTrue(leaves.isFrozen());
        assertLeafRequirement(leaves, 0, "protos/conformance", "protos/test/ordinary");
        assertLeafRequirement(leaves, 1, "protos/process-snapshot", "protos/test/process-snapshot");
        assertLeafRequirement(leaves, 2, "protos/actor", "protos/test/actor");
        assertLeafRequirement(leaves, 3, "protos/group", "protos/test/group");
        assertLeafRequirement(leaves, 4, "protos/package-toml", "protos/test/package");
        assertLeafRequirement(leaves, 5, "protos/library/uri", "protos/test/ordinary");
        assertLeafRequirement(leaves, 6, "protos/library/csv", "protos/test/ordinary");
        assertLeafRequirement(leaves, 7, "protos/library/cli", "protos/test/ordinary");
        assertLeafRequirement(leaves, 8, "protos/library/math/integer", "protos/test/ordinary");
        assertLeafRequirement(leaves, 9, "protos/library/crypto/sha256", "protos/test/ordinary");
        assertLeafRequirement(leaves, 10, "protos/library/network/ip-addresses", "protos/test/ordinary");
        assertLeafRequirement(leaves, 11, "protos/library/network/ip-endpoints", "protos/test/ordinary");
        assertLeafRequirement(leaves, 12, "protos/library/semver", "protos/test/ordinary");
        assertLeafRequirement(leaves, 13, "protos/library/datetime", "protos/test/ordinary");
        assertLeafRequirement(leaves, 14, "protos/library/regex", "protos/test/ordinary");
        assertLeafRequirement(leaves, 15, "protos/library/logging", "protos/test/ordinary");
        assertLeafRequirement(leaves, 16, "protos/package-tool/version", "protos/test/package");
        assertLeafRequirement(leaves, 17, "protos/package-tool/lock", "protos/test/package");
        assertLeafRequirement(leaves, 18, "protos/package-tool/resolution-input", "protos/test/package");
        assertLeafRequirement(leaves, 19, "protos/package-tool/content-identity", "protos/test/package");
        assertLeafRequirement(leaves, 20, "protos/package-tool/resolution-input-lock", "protos/test/package");
        assertLeafRequirement(leaves, 21, "protos/package-tool/resolution-root", "protos/test/package");
        assertLeafRequirement(leaves, 22, "protos/package-tool/execution-plan", "protos/test/package");
        assertLeafRequirement(leaves, 23, "protos/package-tool/project-projection", "protos/test/package");
    }

    @Test
    void d126CorpusIdsAreMandatoryCanonicalAndLeafLocal() throws Exception {
        assertFailed(
                "SuiteGraph: import(\"self:SuiteGraph\")\n"
                        + "SuiteGraph.leaf(\"protos/a\", \"Protos/corpus\", \"test/ordinary\")");
        assertFailed(
                "SuiteGraph: import(\"self:SuiteGraph\")\n"
                        + "SuiteGraph.leaf(\"protos/a\", \"protos//corpus\", \"test/ordinary\")");
        assertFailed(
                "SuiteGraph: import(\"self:SuiteGraph\")\n"
                        + "leaf: {\n"
                        + "    kind: \"leaf\"\n"
                        + "    id: \"protos/a\"\n"
                        + "    executionRequirement: \"test/ordinary\"\n"
                        + "    children: Array()\n"
                        + "}\n"
                        + "SuiteGraph.flattenLeafIds(leaf)");
    }

    @Test
    void d125ExecutionRequirementIdsAreMandatoryCanonicalAndLeafLocal()
            throws Exception {
        assertFailed(
                "SuiteGraph: import(\"self:SuiteGraph\")\n"
                        + "SuiteGraph.leaf(\"protos/a\", \"Protos/test\")");
        assertFailed(
                "SuiteGraph: import(\"self:SuiteGraph\")\n"
                        + "SuiteGraph.leaf(\"protos/a\", \"protos//test\")");
        assertFailed(
                "SuiteGraph: import(\"self:SuiteGraph\")\n"
                        + "leaf: {\n"
                        + "    kind: \"leaf\"\n"
                        + "    id: \"protos/a\"\n"
                        + "    corpus: \"protos/corpus/a\"\n"
                        + "    children: Array()\n"
                        + "}\n"
                        + "SuiteGraph.flattenLeafIds(leaf)");
    }

    @Test
    void repositorySuiteDescriptorsAreFrozen() throws Exception {
        assertFailed(
                "RepositorySuite: import(\"self:RepositorySuite\")\n"
                        + "RepositorySuite.root.id = \"protos/changed\"");
        assertFailed(
                "RepositorySuite: import(\"self:RepositorySuite\")\n"
                        + "leaf: RepositorySuite.root.children[0]\n"
                        + "leaf.id = \"protos/changed\"");
        assertFailed(
                "RepositorySuite: import(\"self:RepositorySuite\")\n"
                        + "leaf: RepositorySuite.root.children[0]\n"
                        + "leaf.corpus = \"protos/corpus/changed\"");
        assertFailed(
                "RepositorySuite: import(\"self:RepositorySuite\")\n"
                        + "leaf: RepositorySuite.root.children[0]\n"
                        + "leaf.executionRequirement = \"protos/test/changed\"");
    }

    @Test
    void d123CanonicalSuiteIdsRejectNonCanonicalForms() throws Exception {
        assertFailed("SuiteGraph: import(\"self:SuiteGraph\")\nSuiteGraph.leaf(\"Protos/actor\", \"corpus/test\", \"test/ordinary\")");
        assertFailed("SuiteGraph: import(\"self:SuiteGraph\")\nSuiteGraph.leaf(\"protos//actor\", \"corpus/test\", \"test/ordinary\")");
        assertFailed("SuiteGraph: import(\"self:SuiteGraph\")\nSuiteGraph.leaf(\"/protos/actor\", \"corpus/test\", \"test/ordinary\")");
        assertFailed("SuiteGraph: import(\"self:SuiteGraph\")\nSuiteGraph.leaf(\"protos/actor/\", \"corpus/test\", \"test/ordinary\")");
        assertFailed("SuiteGraph: import(\"self:SuiteGraph\")\nSuiteGraph.leaf(\"protos/actor suite\", \"corpus/test\", \"test/ordinary\")");
        assertFailed("SuiteGraph: import(\"self:SuiteGraph\")\nSuiteGraph.leaf(\"protos/actor:host\", \"corpus/test\", \"test/ordinary\")");
    }

    @Test
    void duplicateIdsFailClosed() throws Exception {
        assertFailed(
                "SuiteGraph: import(\"self:SuiteGraph\")\n"
                        + "first: SuiteGraph.leaf(\"same\", \"corpus/test\", \"test/ordinary\")\n"
                        + "second: SuiteGraph.leaf(\"same\", \"corpus/test\", \"test/ordinary\")\n"
                        + "root: SuiteGraph.suite(\"root\", Array(first, second))\n"
                        + "SuiteGraph.flattenLeafIds(root)");
    }

    @Test
    void cyclesFailClosed() throws Exception {
        assertFailed(
                "SuiteGraph: import(\"self:SuiteGraph\")\n"
                        + "children: Array(null)\n"
                        + "cycle: {\n"
                        + "    kind: \"suite\"\n"
                        + "    id: \"cycle\"\n"
                        + "    children: children\n"
                        + "}\n"
                        + "children[0] = cycle\n"
                        + "SuiteGraph.flattenLeafIds(cycle)");
    }

    @Test
    void unknownNodeKindsFailClosed() throws Exception {
        assertFailed(
                "SuiteGraph: import(\"self:SuiteGraph\")\n"
                        + "unknown: {\n"
                        + "    kind: \"tree\"\n"
                        + "    id: \"unknown\"\n"
                        + "    children: Array()\n"
                        + "}\n"
                        + "SuiteGraph.flattenLeafIds(unknown)");
    }

    @Test
    void emptyIdsFailClosed() throws Exception {
        assertFailed(
                "SuiteGraph: import(\"self:SuiteGraph\")\n"
                        + "SuiteGraph.leaf(\"\", \"corpus/test\", \"test/ordinary\")");
    }

    @Test
    void nonStringIdsFailClosed() throws Exception {
        assertFailed(
                "SuiteGraph: import(\"self:SuiteGraph\")\n"
                        + "SuiteGraph.leaf(42, \"test/ordinary\")");
    }

    private static void assertLeafCorpus(
            ProtosArrayValue leaves,
            int index,
            String expectedId,
            String expectedCorpus) {
        ProtosObjectValue leaf =
                assertInstanceOf(
                        ProtosObjectValue.class,
                        leaves.indexedAt(index));
        assertTrue(leaf.isFrozen());
        assertEquals(
                expectedId,
                assertInstanceOf(
                                ProtosStringValue.class,
                                leaf.readLocalSlot("id").orElseThrow())
                        .value());
        assertEquals(
                expectedCorpus,
                assertInstanceOf(
                                ProtosStringValue.class,
                                leaf.readLocalSlot("corpus").orElseThrow())
                        .value());
    }

    private static void assertLeafRequirement(
            ProtosArrayValue leaves,
            int index,
            String expectedId,
            String expectedRequirement) {
        ProtosObjectValue leaf =
                assertInstanceOf(
                        ProtosObjectValue.class,
                        leaves.indexedAt(index));
        assertTrue(leaf.isFrozen());
        assertEquals(
                expectedId,
                assertInstanceOf(
                                ProtosStringValue.class,
                                leaf.readLocalSlot("id").orElseThrow())
                        .value());
        assertEquals(
                expectedRequirement,
                assertInstanceOf(
                                ProtosStringValue.class,
                                leaf.readLocalSlot("executionRequirement").orElseThrow())
                        .value());
    }

    private static void assertLeafId(ProtosArrayValue flattened, int index, String expected) {
        assertEquals(
                expected,
                assertInstanceOf(
                                ProtosStringValue.class,
                                flattened.indexedAt(index))
                        .value());
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
        Fixture fixture = fixture();
        return ProtosTestExecutionSupport.execute(source, fixture.activation());
    }

    private static final class SharedPrelude {
        private static final ProtosPrelude INSTANCE = create();

        private static ProtosPrelude create() {
            try {
                ProtosBundledToolModuleResolver resolver =
                        new ProtosBundledToolModuleResolver(
                                "test",
                                TOOL_ROOT,
                                TOOL_ROOT.resolveSibling("shared"),
                                new ProtosStandardLibraryModuleResolver(STANDARD_LIBRARY));
                return new ProtosCoreBootstrap().bootstrap(CORE, resolver);
            } catch (Exception exception) {
                throw new ExceptionInInitializerError(exception);
            }
        }
    }

    private static Fixture fixture() {
        ProtosPrelude prelude = SharedPrelude.INSTANCE;
        return new Fixture(prelude, prelude.newModuleActivation());
    }

    private record Fixture(ProtosPrelude prelude, ProtosActivation activation) {}
}
