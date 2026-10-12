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
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.guillermomolina.protos.runtime.ProtosActivation;
import com.guillermomolina.protos.runtime.ProtosArrayValue;
import com.guillermomolina.protos.runtime.ProtosBooleanValue;
import com.guillermomolina.protos.runtime.ProtosStringValue;
import com.guillermomolina.protos.runtime.ProtosFilesystemValue;
import com.guillermomolina.protos.runtime.ProtosNullValue;
import com.guillermomolina.protos.runtime.ProtosObjectValue;
import com.guillermomolina.protos.runtime.ProtosPrelude;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class ProtosTestToolManifestPlanTest {
    private static final Path CORE = Path.of("protos", "lib", "core");
    private static final Path STANDARD_LIBRARY = Path.of("protos", "lib");
    private static final Path TOOL_ROOT = Path.of("protos", "tools", "test");
    private static final Path MANIFEST_MODULE = TOOL_ROOT.resolve("Manifest.protos");
    private static final Path CORPUS_ROOT =
            Path.of("protos", "tests", "conformance");
    private static final Path PROCESS_SNAPSHOT_CORPUS_ROOT =
            Path.of("protos", "tests", "conformance", "process");
    private static final Path PACKAGE_TOML_CORPUS_ROOT =
            Path.of("protos", "tests", "package-tool", "toml-syntax");
    private static final Path FIXTURE =
            Path.of(
                    "protos",
                    "tests",
                    "tooling",
                    "tool002-d2-manifest-plan.protos");
    private static final Path PACKAGE_TOML_FIXTURE =
            Path.of(
                    "protos",
                    "tests",
                    "tooling",
                    "tool002-e1a-package-toml-manifest-plan.protos");
    private static final Path PACKAGE_TOML_FILESYSTEM_FIXTURE =
            Path.of(
                    "protos",
                    "tests",
                    "tooling",
                    "tool002-e1b-package-toml-filesystem.protos");

    @Test
    void bundledManifestModuleCompilesBeforeAnyFilesystemPolicyRuns()
            throws Exception {
        String characters =
                Files.readString(
                        MANIFEST_MODULE,
                        StandardCharsets.UTF_8);

        try (org.graalvm.polyglot.Context context =
                org.graalvm.polyglot.Context
                        .newBuilder(ProtosLanguage.ID)
                        .build()) {
            org.graalvm.polyglot.Source source =
                    org.graalvm.polyglot.Source
                            .newBuilder(
                                    ProtosLanguage.ID,
                                    characters,
                                    "Manifest.protos")
                            .buildLiteral();

            context.parse(source);
        }
    }

    @Test
    void oneManifestRowProducesFrozenStableCaseSpecBeforeCorpusTraversal()
            throws Exception {
        Fixture fixture = fixture();
        String source =
                "Manifest: import(\"self:Manifest\")\n"
                        + "Manifest.parseLine("
                        + "\"integer/add-small.protos\\tinteger\\t2\")";

        ProtosArrayValue caseSpec =
                assertInstanceOf(
                        ProtosArrayValue.class,
                        completed(source, fixture.activation()));

        assertEquals(5, caseSpec.indexedSize());
        org.junit.jupiter.api.Assertions.assertTrue(caseSpec.isFrozen());
        ProtosArrayValue requirements =
                assertInstanceOf(
                        ProtosArrayValue.class,
                        caseSpec.indexedAt(4));
        assertEquals(0, requirements.indexedSize());
        org.junit.jupiter.api.Assertions.assertTrue(requirements.isFrozen());
        assertEquals(
                "integer/add-small.protos",
                assertInstanceOf(
                                ProtosStringValue.class,
                                caseSpec.indexedAt(0))
                        .value());
    }

    @Test
    void namedCaseSpecAccessorsPreserveConceptualPlanningFields()
            throws Exception {
        Fixture fixture = fixture();
        String source =
                "Manifest: import(\"self:Manifest\")\n"
                        + "case: Manifest.parseLine("
                        + "\"integer/add-small.protos\\tinteger\\t2\")\n"
                        + "(Manifest.caseId(case) == \"integer/add-small.protos\") &&\n"
                        + "(Manifest.casePath(case) == Manifest.caseId(case)) &&\n"
                        + "(Manifest.caseExpectation(case) == \"integer\") &&\n"
                        + "(Manifest.caseExpected(case) == \"2\")";

        Object result =
                completed(source, fixture.activation());

        assertSame(ProtosBooleanValue.TRUE, result);
    }

    @Test
    void caseSpecRequirementsAccessorReturnsFreshFrozenEmptyArrays()
            throws Exception {
        Fixture fixture = fixture();
        String source =
                "Manifest: import(\"self:Manifest\")\n"
                        + "first: Manifest.caseSpec(Array(\"i1/first.protos\", \"integer\", \"1\"))\n"
                        + "second: Manifest.caseSpec(Array(\"i1/second.protos\", \"integer\", \"2\"))\n"
                        + "Array(Manifest.caseRequirements(first), "
                        + "Manifest.caseRequirements(second))";

        ProtosArrayValue requirements =
                assertInstanceOf(
                        ProtosArrayValue.class,
                        completed(source, fixture.activation()));
        assertEquals(2, requirements.indexedSize());

        ProtosArrayValue first =
                assertInstanceOf(
                        ProtosArrayValue.class,
                        requirements.indexedAt(0));
        ProtosArrayValue second =
                assertInstanceOf(
                        ProtosArrayValue.class,
                        requirements.indexedAt(1));

        assertEquals(0, first.indexedSize());
        assertEquals(0, second.indexedSize());
        org.junit.jupiter.api.Assertions.assertTrue(first.isFrozen());
        org.junit.jupiter.api.Assertions.assertTrue(second.isFrozen());
        assertNotSame(first, second);
    }

    @Test
    void inertRequirementRecordIsFrozenAndAccessibleThroughNamedAccessors()
            throws Exception {
        Fixture fixture = fixture();
        String source =
                "Manifest: import(\"self:Manifest\")\n"
                        + "shared: Manifest.requirement(\"gpu\", \"shared\", 2)\n"
                        + "exclusive: Manifest.requirement(\"db/integration\", \"exclusive\", null)\n"
                        + "Array(shared, exclusive, "
                        + "Manifest.requirementKey(shared), "
                        + "Manifest.requirementMode(shared), "
                        + "Manifest.requirementUnits(shared), "
                        + "Manifest.requirementKey(exclusive), "
                        + "Manifest.requirementMode(exclusive), "
                        + "Manifest.requirementUnits(exclusive))";

        ProtosArrayValue observed =
                assertInstanceOf(
                        ProtosArrayValue.class,
                        completed(source, fixture.activation()));
        assertEquals(8, observed.indexedSize());

        ProtosArrayValue shared =
                assertInstanceOf(
                        ProtosArrayValue.class,
                        observed.indexedAt(0));
        ProtosArrayValue exclusive =
                assertInstanceOf(
                        ProtosArrayValue.class,
                        observed.indexedAt(1));
        assertEquals(3, shared.indexedSize());
        assertEquals(3, exclusive.indexedSize());
        org.junit.jupiter.api.Assertions.assertTrue(shared.isFrozen());
        org.junit.jupiter.api.Assertions.assertTrue(exclusive.isFrozen());
        assertNotSame(shared, exclusive);

        assertEquals(
                "gpu",
                assertInstanceOf(
                                ProtosStringValue.class,
                                observed.indexedAt(2))
                        .value());
        assertEquals(
                "shared",
                assertInstanceOf(
                                ProtosStringValue.class,
                                observed.indexedAt(3))
                        .value());
        assertEquals(String.valueOf(2), String.valueOf(Integer.parseInt(com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerDecimalText(observed.indexedAt(4)))));
        assertEquals(
                "db/integration",
                assertInstanceOf(
                                ProtosStringValue.class,
                                observed.indexedAt(5))
                        .value());
        assertEquals(
                "exclusive",
                assertInstanceOf(
                                ProtosStringValue.class,
                                observed.indexedAt(6))
                        .value());
        assertSame(
                ProtosNullValue.INSTANCE,
                observed.indexedAt(7));
    }

    @Test
    void requirementResourceKeyValidationAcceptsCanonicalAndRejectsMalformedKeys()
            throws Exception {
        Fixture fixture = fixture();
        String validSource =
                "Manifest: import(\"self:Manifest\")\n"
                        + "Array("
                        + "Manifest.requirement(\"gpu\", \"shared\", 1), "
                        + "Manifest.requirement(\"db/integration\", \"shared\", 1), "
                        + "Manifest.requirement(\"license/ansys\", \"shared\", 1), "
                        + "Manifest.requirement(\"test/http-server\", \"shared\", 1), "
                        + "Manifest.requirement(\"vendor/example-device\", \"shared\", 1), "
                        + "Manifest.requirement(\"gpu.v2/foo_bar-1\", \"shared\", 1), "
                        + "Manifest.requirement(\"0gpu\", \"shared\", 1))";

        ProtosArrayValue valid =
                assertInstanceOf(
                        ProtosArrayValue.class,
                        completed(validSource, fixture.activation()));
        assertEquals(7, valid.indexedSize());

        String[] invalidKeys = {
            "",
            "GPU",
            "/gpu",
            "gpu/",
            "gpu//fast",
            "gpu fast",
            "gpu+fast",
            "-gpu",
            "é"
        };
        for (String invalidKey : invalidKeys) {
            Fixture invalidFixture = fixture();
            String source =
                    "Manifest: import(\"self:Manifest\")\n"
                            + "Manifest.requirement(\""
                            + invalidKey
                            + "\", \"shared\", 1)";
            ProtosExecutionOutcome outcome =
                    com.guillermomolina.protos.execution.ProtosTestExecutionSupport.execute(
source,
invalidFixture.activation());
            assertEquals(
                    ProtosExecutionOutcome.State.FAILED,
                    outcome.state(),
                    () -> "expected invalid resource key to fail closed: " + invalidKey);
        }
    }

    @Test
    void requirementModeUnitsValidationAcceptsOnlyRatifiedShapes()
            throws Exception {
        Fixture fixture = fixture();
        String validSource =
                "Manifest: import(\"self:Manifest\")\n"
                        + "Array("
                        + "Manifest.requirement(\"gpu\", \"shared\", 1), "
                        + "Manifest.requirement(\"db/integration\", \"shared\", "
                        + "999999999999999999999999999999999999), "
                        + "Manifest.requirement(\"license/ansys\", \"exclusive\", null))";

        ProtosArrayValue valid =
                assertInstanceOf(
                        ProtosArrayValue.class,
                        completed(validSource, fixture.activation()));
        assertEquals(3, valid.indexedSize());

        String[] invalidArguments = {
            "\"gpu\", \"shared\", 0",
            "\"gpu\", \"shared\", -1",
            "\"gpu\", \"shared\", null",
            "\"gpu\", \"shared\", 1.0",
            "\"gpu\", \"shared\", Integer {}",
            "\"gpu\", \"shared\", \"1\"",
            "\"gpu\", \"exclusive\", 1",
            "\"gpu\", \"exclusive\", 0",
            "\"gpu\", \"exclusive\", \"\"",
            "\"gpu\", \"unknown\", null",
            "\"gpu\", \"SHARED\", 1",
            "\"gpu\", 1, null"
        };
        for (String invalidArgumentsValue : invalidArguments) {
            Fixture invalidFixture =
                    new Fixture(
                            fixture.prelude(),
                            fixture.prelude().newModuleActivation());
            String source =
                    "Manifest: import(\"self:Manifest\")\n"
                            + "Manifest.requirement("
                            + invalidArgumentsValue
                            + ")";
            ProtosExecutionOutcome outcome =
                    com.guillermomolina.protos.execution.ProtosTestExecutionSupport.execute(
source,
invalidFixture.activation());
            assertEquals(
                    ProtosExecutionOutcome.State.FAILED,
                    outcome.state(),
                    () ->
                            "expected invalid Requirement mode/units to fail closed: "
                                    + invalidArgumentsValue);
        }
    }

    @Test
    void caseSpecRequirementAttachmentPreservesOrderAndFreezesCanonicalRecords()
            throws Exception {
        Fixture fixture = fixture();
        String source =
                "Manifest: import(\"self:Manifest\")\n"
                        + "base: Manifest.caseSpec(Array(\"i5/resourceful.protos\", \"integer\", \"7\"))\n"
                        + "attached: Manifest.caseSpecWithRequirements(base, Array("
                        + "Array(\"gpu\", \"shared\", 2), "
                        + "Array(\"db/integration\", \"exclusive\", null)))\n"
                        + "requirements: Manifest.caseRequirements(attached)\n"
                        + "Array(attached, requirements, "
                        + "Manifest.requirementKey(requirements[0]), "
                        + "Manifest.requirementMode(requirements[0]), "
                        + "Manifest.requirementUnits(requirements[0]), "
                        + "Manifest.requirementKey(requirements[1]), "
                        + "Manifest.requirementMode(requirements[1]), "
                        + "Manifest.requirementUnits(requirements[1]))";

        ProtosArrayValue observed =
                assertInstanceOf(
                        ProtosArrayValue.class,
                        completed(source, fixture.activation()));
        assertEquals(8, observed.indexedSize());

        ProtosArrayValue attached =
                assertInstanceOf(
                        ProtosArrayValue.class,
                        observed.indexedAt(0));
        ProtosArrayValue requirements =
                assertInstanceOf(
                        ProtosArrayValue.class,
                        observed.indexedAt(1));
        assertEquals(5, attached.indexedSize());
        assertEquals(2, requirements.indexedSize());
        org.junit.jupiter.api.Assertions.assertTrue(attached.isFrozen());
        org.junit.jupiter.api.Assertions.assertTrue(requirements.isFrozen());

        ProtosArrayValue first =
                assertInstanceOf(
                        ProtosArrayValue.class,
                        requirements.indexedAt(0));
        ProtosArrayValue second =
                assertInstanceOf(
                        ProtosArrayValue.class,
                        requirements.indexedAt(1));
        org.junit.jupiter.api.Assertions.assertTrue(first.isFrozen());
        org.junit.jupiter.api.Assertions.assertTrue(second.isFrozen());
        assertEquals(3, first.indexedSize());
        assertEquals(3, second.indexedSize());

        assertEquals(
                "gpu",
                assertInstanceOf(
                                ProtosStringValue.class,
                                observed.indexedAt(2))
                        .value());
        assertEquals(
                "shared",
                assertInstanceOf(
                                ProtosStringValue.class,
                                observed.indexedAt(3))
                        .value());
        assertEquals(String.valueOf(2), String.valueOf(Integer.parseInt(com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerDecimalText(observed.indexedAt(4)))));
        assertEquals(
                "db/integration",
                assertInstanceOf(
                                ProtosStringValue.class,
                                observed.indexedAt(5))
                        .value());
        assertEquals(
                "exclusive",
                assertInstanceOf(
                                ProtosStringValue.class,
                                observed.indexedAt(6))
                        .value());
        assertSame(
                ProtosNullValue.INSTANCE,
                observed.indexedAt(7));
    }

    @Test
    void caseSpecRequirementAttachmentRejectsDuplicateCaseKeyWithoutMerging()
            throws Exception {
        Fixture controlFixture = fixture();
        String controlSource =
                "Manifest: import(\"self:Manifest\")\n"
                        + "base: Manifest.caseSpec(Array(\"i5/control.protos\", \"integer\", \"1\"))\n"
                        + "Manifest.caseSpecWithRequirements(base, Array("
                        + "Manifest.requirement(\"gpu\", \"shared\", 1)))";
        ProtosExecutionOutcome controlOutcome =
                com.guillermomolina.protos.execution.ProtosTestExecutionSupport.execute(
controlSource,
controlFixture.activation());
        assertEquals(
                ProtosExecutionOutcome.State.COMPLETED,
                controlOutcome.state(),
                "single canonical Requirement must attach before duplicate rejection is tested");

        String[] duplicatePairs = {
            "Manifest.requirement(\"gpu\", \"shared\", 1), "
                    + "Manifest.requirement(\"gpu\", \"shared\", 1)",
            "Manifest.requirement(\"gpu\", \"shared\", 2), "
                    + "Manifest.requirement(\"gpu\", \"exclusive\", null)"
        };

        for (String duplicatePair : duplicatePairs) {
            Fixture fixture =
                    new Fixture(
                            controlFixture.prelude(),
                            controlFixture.prelude().newModuleActivation());
            String source =
                    "Manifest: import(\"self:Manifest\")\n"
                            + "base: Manifest.caseSpec(Array(\"i5/duplicate.protos\", \"integer\", \"1\"))\n"
                            + "Manifest.caseSpecWithRequirements(base, Array("
                            + duplicatePair
                            + "))";
            ProtosExecutionOutcome outcome =
                    com.guillermomolina.protos.execution.ProtosTestExecutionSupport.execute(
source,
fixture.activation());
            assertEquals(
                    ProtosExecutionOutcome.State.FAILED,
                    outcome.state(),
                    () -> "expected duplicate (case,key) to fail closed: " + duplicatePair);
        }
    }

    @Test
    void sameResourceKeyMayAppearInDifferentCaseSpecs()
            throws Exception {
        Fixture fixture = fixture();
        String source =
                "Manifest: import(\"self:Manifest\")\n"
                        + "first: Manifest.caseSpec(Array(\"i5/first.protos\", \"integer\", \"1\"))\n"
                        + "second: Manifest.caseSpec(Array(\"i5/second.protos\", \"integer\", \"2\"))\n"
                        + "first = Manifest.caseSpecWithRequirements(first, Array("
                        + "Manifest.requirement(\"gpu\", \"shared\", 1)))\n"
                        + "second = Manifest.caseSpecWithRequirements(second, Array("
                        + "Manifest.requirement(\"gpu\", \"exclusive\", null)))\n"
                        + "Array(Manifest.caseRequirements(first).size(), "
                        + "Manifest.caseRequirements(second).size())";

        ProtosArrayValue sizes =
                assertInstanceOf(
                        ProtosArrayValue.class,
                        completed(source, fixture.activation()));
        assertEquals(2, sizes.indexedSize());
        assertEquals(String.valueOf(1), String.valueOf(Integer.parseInt(com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerDecimalText(sizes.indexedAt(0)))));
        assertEquals(String.valueOf(1), String.valueOf(Integer.parseInt(com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerDecimalText(sizes.indexedAt(1)))));
    }

    @Test
    void batchedManifestTraversalDoesNotGrowOneProtosFramePerRow(
            @TempDir Path corpusRoot) throws Exception {
        int rowCount = 160;
        String row = "integer/add-small.protos\tinteger\t2";
        StringBuilder manifest = new StringBuilder();
        for (int i = 0; i < rowCount; i++) {
            manifest.append(row);
            switch (i % 3) {
                case 0 -> manifest.append('\n');
                case 1 -> manifest.append("\r\n");
                default -> manifest.append('\r');
            }
        }
        Files.writeString(
                corpusRoot.resolve("manifest.tsv"),
                manifest,
                StandardCharsets.UTF_8);

        Fixture fixture = fixture();
        try (DeterministicFilesystemBackend backend =
                DeterministicFilesystemBackend.fromTree(corpusRoot)) {
            assumeTrue(
                    backend.secureConfinementAvailable(),
                    "host provider has no SecureDirectoryStream");
            installFilesystem(fixture.prelude(), fixture.activation(), backend);

            Object result =
                    completed("Manifest: import(\"self:Manifest\")\n"
                                                    + "Manifest.planCases("
                                                    + "Manifest.load(filesystem)).size()", fixture.activation());

            assertEquals(String.valueOf(rowCount), String.valueOf(Integer.parseInt(com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerDecimalText(result))));
        }
    }

    @Test
    void manifestAndNestedCorpusSourceAreConsumedByProtosPolicy(
            @TempDir Path corpusRoot)
            throws Exception {
        java.util.List<String> manifestLines =
                Files.readAllLines(
                        CORPUS_ROOT.resolve("manifest.tsv"),
                        StandardCharsets.UTF_8);
        Files.writeString(
                corpusRoot.resolve("manifest.tsv"),
                String.join("\n", manifestLines.subList(0, 102)) + "\n",
                StandardCharsets.UTF_8);
        Files.createDirectories(corpusRoot.resolve("integer"));
        Files.copy(
                CORPUS_ROOT.resolve("integer/arithmetic-and-unary.protos"),
                corpusRoot.resolve("integer/arithmetic-and-unary.protos"));

        Fixture fixture = fixture();

        try (DeterministicFilesystemBackend backend =
                DeterministicFilesystemBackend.fromTree(corpusRoot)) {
            assumeTrue(
                    backend.secureConfinementAvailable(),
                    "host provider has no SecureDirectoryStream");

            installFilesystem(fixture.prelude(), fixture.activation(), backend);

            Object result =
                    completed(Files.readString(
                                                    FIXTURE,
                                                    StandardCharsets.UTF_8), fixture.activation());

            assertSame(ProtosBooleanValue.TRUE, result);
        }
    }

    @Test
    void packageTomlManifestPlanningIsOwnedByBundledProtos(
            @TempDir Path corpusRoot) throws Exception {
        Files.writeString(
                corpusRoot.resolve("manifest.tsv"),
                "# path\texpectation\n"
                        + "key-bare-dotted.protos\ttrue\n"
                        + "invalid-key-error.protos\terror\n",
                StandardCharsets.UTF_8);

        Fixture fixture = fixture();
        try (ProtosNioReadOnlyTreeFilesystemBackend backend =
                new ProtosNioReadOnlyTreeFilesystemBackend(corpusRoot)) {
            assumeTrue(
                    backend.secureConfinementAvailable(),
                    "host provider has no SecureDirectoryStream");
            installFilesystem(fixture.prelude(), fixture.activation(), backend);

            Object result =
                    completed(Files.readString(
                                                    PACKAGE_TOML_FIXTURE,
                                                    StandardCharsets.UTF_8), fixture.activation());

            assertSame(ProtosBooleanValue.TRUE, result);
        }
    }

    @Test
    void processSnapshotManifestMaterializesExactThreeCasePlan()
            throws Exception {
        Fixture fixture = fixture();

        try (DeterministicFilesystemBackend backend =
                DeterministicFilesystemBackend.fromTree(PROCESS_SNAPSHOT_CORPUS_ROOT)) {
            assumeTrue(
                    backend.secureConfinementAvailable(),
                    "host provider has no SecureDirectoryStream");

            installFilesystem(
                    fixture.prelude(),
                    fixture.activation(),
                    "filesystem",
                    backend);

            Object result =
                    completed(
                            "Manifest: import(\"self:Manifest\")\n"
                                    + "Manifest.planCases("
                                    + "Manifest.load(filesystem)).size()",
                            fixture.activation());

            assertEquals(String.valueOf(3), String.valueOf(Integer.parseInt(com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerDecimalText(result))));
        }
    }

    @Test
    void separatePackageTomlFilesystemLoadsRealCorpusWithoutExecutingFixtures(
            @TempDir Path root)
            throws Exception {
        Path conformanceRoot = Files.createDirectory(root.resolve("conformance"));
        Path packageTomlRoot = Files.createDirectory(root.resolve("package-toml"));

        java.util.List<String> conformanceManifest =
                Files.readAllLines(
                        CORPUS_ROOT.resolve("manifest.tsv"),
                        StandardCharsets.UTF_8);
        java.util.List<String> packageTomlManifest =
                Files.readAllLines(
                        PACKAGE_TOML_CORPUS_ROOT.resolve("manifest.tsv"),
                        StandardCharsets.UTF_8);

        Files.writeString(
                conformanceRoot.resolve("manifest.tsv"),
                String.join("\n", conformanceManifest.subList(0, 2)) + "\n",
                StandardCharsets.UTF_8);
        Files.writeString(
                packageTomlRoot.resolve("manifest.tsv"),
                String.join("\n", packageTomlManifest.subList(0, 2)) + "\n",
                StandardCharsets.UTF_8);

        Fixture fixture = fixture();

        try (DeterministicFilesystemBackend conformanceBackend =
                        DeterministicFilesystemBackend.fromTree(conformanceRoot);
                DeterministicFilesystemBackend packageTomlBackend =
                        DeterministicFilesystemBackend.fromTree(packageTomlRoot)) {
            assumeTrue(
                    conformanceBackend.secureConfinementAvailable()
                            && packageTomlBackend.secureConfinementAvailable(),
                    "host provider has no SecureDirectoryStream");

            installFilesystem(
                    fixture.prelude(),
                    fixture.activation(),
                    "filesystem",
                    conformanceBackend);
            installFilesystem(
                    fixture.prelude(),
                    fixture.activation(),
                    "packageTomlFilesystem",
                    packageTomlBackend);

            Object result =
                    completed(Files.readString(
                                                    PACKAGE_TOML_FILESYSTEM_FIXTURE,
                                                    StandardCharsets.UTF_8), fixture.activation());

            assertSame(ProtosBooleanValue.TRUE, result);
        }
    }

    @Test
    void d132CaseOutcomeLoaderNormalizesExplicitRowsUnderLogicalNamespace(
            @TempDir Path corpusRoot) throws Exception {
        Files.writeString(
                corpusRoot.resolve("manifest.tsv"),
                "# key\toutcome\n"
                        + "keep.protos\ttrue\n"
                        + "reject.protos\terror\n"
                        + "migrated.protos\tsuite-native\n",
                StandardCharsets.UTF_8);

        Fixture fixture = fixture();
        try (ProtosNioReadOnlyTreeFilesystemBackend backend =
                new ProtosNioReadOnlyTreeFilesystemBackend(corpusRoot)) {
            assumeTrue(
                    backend.secureConfinementAvailable(),
                    "host provider has no SecureDirectoryStream");
            installFilesystem(fixture.prelude(), fixture.activation(), backend);

            ProtosArrayValue observed =
                    assertInstanceOf(
                            ProtosArrayValue.class,
                            completed(
                                    "Manifest: import(\"self:Manifest\")\n"
                                            + "cases: Manifest.planCases("
                                            + "Manifest.loadCaseOutcomes(filesystem, \"ns\"))\n"
                                            + "Array("
                                            + "cases.size(), "
                                            + "Manifest.caseId(cases[0]), "
                                            + "Manifest.casePath(cases[0]), "
                                            + "Manifest.caseExpectation(cases[0]), "
                                            + "Manifest.caseExpected(cases[0]), "
                                            + "Manifest.caseId(cases[1]), "
                                            + "Manifest.casePath(cases[1]), "
                                            + "Manifest.caseExpectation(cases[1]), "
                                            + "Manifest.caseExpected(cases[1]), "
                                            + "Manifest.caseId(cases[2]), "
                                            + "Manifest.casePath(cases[2]), "
                                            + "Manifest.caseExpectation(cases[2]), "
                                            + "Manifest.caseExpected(cases[2]))",
                                    fixture.activation()));

            assertEquals(13, observed.indexedSize());
            assertEquals(String.valueOf(3), String.valueOf(Integer.parseInt(com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerDecimalText(observed.indexedAt(0)))));
            assertEquals("ns/keep.protos", stringAt(observed, 1));
            assertEquals("keep.protos", stringAt(observed, 2));
            assertEquals("boolean", stringAt(observed, 3));
            assertEquals("true", stringAt(observed, 4));
            assertEquals("ns/reject.protos", stringAt(observed, 5));
            assertEquals("reject.protos", stringAt(observed, 6));
            assertEquals("error", stringAt(observed, 7));
            assertEquals("-", stringAt(observed, 8));
            // TOOL009 Publication 1: the generic case-outcomes loader also accepts the
            // established "suite-native" outcome marker, normalized the same way
            // packageTomlCaseSpec already normalizes it for the Package TOML corpus.
            assertEquals("ns/migrated.protos", stringAt(observed, 9));
            assertEquals("migrated.protos", stringAt(observed, 10));
            assertEquals("suite-native", stringAt(observed, 11));
            assertEquals("-", stringAt(observed, 12));
        }
    }

    @Test
    void d132CaseOutcomeLoaderFailsClosedOnMalformedRows(
            @TempDir Path corpusRoot) throws Exception {
        String[] malformedManifests = {
            "a.protos\tmaybe\n", // unknown outcome value
            "\ttrue\n", // empty case key
            "a.protos\n", // missing outcome column
            "a.protos\ttrue\textra\n", // more than two columns
            "a.protos\ttrue\na.protos\terror\n", // duplicate logical CaseId
            "..\ttrue\n" // unsafe relative case key
        };
        for (String manifest : malformedManifests) {
            Files.writeString(
                    corpusRoot.resolve("manifest.tsv"),
                    manifest,
                    StandardCharsets.UTF_8);

            Fixture fixture = fixture();
            try (DeterministicFilesystemBackend backend =
                    DeterministicFilesystemBackend.fromTree(corpusRoot)) {
                assumeTrue(
                        backend.secureConfinementAvailable(),
                        "host provider has no SecureDirectoryStream");
                installFilesystem(fixture.prelude(), fixture.activation(), backend);

                ProtosExecutionOutcome outcome =
                        com.guillermomolina.protos.execution.ProtosTestExecutionSupport.execute(
                                "Manifest: import(\"self:Manifest\")\n"
                                        + "Manifest.loadCaseOutcomes(filesystem, \"ns\")",
                                fixture.activation());
                assertEquals(
                        ProtosExecutionOutcome.State.FAILED,
                        outcome.state(),
                        () -> "expected fail-closed rejection for: " + manifest);
            }
        }
    }

    static void d133ProjectTreeAuthorityDescriptorIsInertCaseScopedData()
            throws Exception {
        Fixture fixture = fixture();

        ProtosArrayValue observed =
                assertInstanceOf(
                        ProtosArrayValue.class,
                        completed(
                                "Manifest: import(\"self:Manifest\")\n"
                                        + "base: Manifest.caseSpec(Array(\"sample.protos\", \"boolean\", \"true\"))\n"
                                        + "descriptor: Manifest.projectTreeAuthorityDescriptor(\"workspace\")\n"
                                        + "spec: Manifest.caseSpecWithAuthorityDescriptor(base, descriptor)\n"
                                        + "authority: Manifest.caseAuthorityDescriptor(spec)\n"
                                        + "Array(spec.size(), "
                                        + "Manifest.caseId(spec), "
                                        + "Manifest.casePath(spec), "
                                        + "Manifest.caseAuthorityKind(authority), "
                                        + "Manifest.caseAuthorityFixtureIdentity(authority), "
                                        + "Manifest.caseAuthorityIsolation(authority), "
                                        + "Manifest.caseAuthorityLifecycle(authority))",
                                fixture.activation()));

        assertEquals(7, observed.indexedSize());
        assertEquals(String.valueOf(6), String.valueOf(Integer.parseInt(com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerDecimalText(observed.indexedAt(0)))));
        assertEquals("sample.protos", stringAt(observed, 1));
        assertEquals("sample.protos", stringAt(observed, 2));
        assertEquals("project-tree", stringAt(observed, 3));
        assertEquals("workspace", stringAt(observed, 4));
        assertEquals("case", stringAt(observed, 5));
        assertEquals("case", stringAt(observed, 6));
    }

    static void d133ProjectTreeLoaderNormalizesCaseIdentitySourceAndAuthority(
            Path corpusRoot) throws Exception {
        Files.writeString(
                corpusRoot.resolve("manifest.tsv"),
                "# project\tfixture\toutcome\n"
                        + "root-only\tbuild-current.protos\ttrue\n"
                        + "root-only\tcanonical-document-order.protos\ttrue\n"
                        + "stale\tstale-semantic-error.protos\terror\n",
                StandardCharsets.UTF_8);

        Fixture fixture = fixture();
        try (ProtosNioReadOnlyTreeFilesystemBackend backend =
                new ProtosNioReadOnlyTreeFilesystemBackend(corpusRoot)) {
            assumeTrue(
                    backend.secureConfinementAvailable(),
                    "host provider has no SecureDirectoryStream");
            installFilesystem(fixture.prelude(), fixture.activation(), backend);

            ProtosArrayValue observed =
                    assertInstanceOf(
                            ProtosArrayValue.class,
                            completed(
                                    "Manifest: import(\"self:Manifest\")\n"
                                            + "cases: Manifest.planCases("
                                            + "Manifest.loadProjectTreeCases(filesystem, \"ns\"))\n"
                                            + "firstAuthority: Manifest.caseAuthorityDescriptor(cases[0])\n"
                                            + "secondAuthority: Manifest.caseAuthorityDescriptor(cases[1])\n"
                                            + "Array(cases.size(), "
                                            + "Manifest.caseId(cases[0]), "
                                            + "Manifest.casePath(cases[0]), "
                                            + "Manifest.caseAuthorityFixtureIdentity(firstAuthority), "
                                            + "Manifest.caseId(cases[1]), "
                                            + "Manifest.casePath(cases[1]), "
                                            + "Manifest.caseAuthorityFixtureIdentity(secondAuthority), "
                                            + "Manifest.caseExpectation(cases[2]), "
                                            + "Manifest.caseExpected(cases[2]))",
                                    fixture.activation()));

            assertEquals(9, observed.indexedSize());
            assertEquals(String.valueOf(3), String.valueOf(Integer.parseInt(com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerDecimalText(observed.indexedAt(0)))));
            assertEquals("ns/root-only/build-current.protos", stringAt(observed, 1));
            assertEquals("fixtures/build-current.protos", stringAt(observed, 2));
            assertEquals("root-only", stringAt(observed, 3));
            assertEquals(
                    "ns/root-only/canonical-document-order.protos",
                    stringAt(observed, 4));
            assertEquals(
                    "fixtures/canonical-document-order.protos",
                    stringAt(observed, 5));
            assertEquals("root-only", stringAt(observed, 6));
            assertEquals("error", stringAt(observed, 7));
            assertEquals("-", stringAt(observed, 8));
        }
    }

    static void d133ResourceRequirementAttachmentPreservesCaseAuthorityDescriptor()
            throws Exception {
        Fixture fixture = fixture();

        ProtosArrayValue observed =
                assertInstanceOf(
                        ProtosArrayValue.class,
                        completed(
                                "Manifest: import(\"self:Manifest\")\n"
                                        + "base: Manifest.caseSpec(Array(\"sample.protos\", \"boolean\", \"true\"))\n"
                                        + "authoritySpec: Manifest.caseSpecWithAuthorityDescriptor("
                                        + "base, Manifest.projectTreeAuthorityDescriptor(\"workspace\"))\n"
                                        + "result: Manifest.caseSpecWithRequirements("
                                        + "authoritySpec, Array(Manifest.requirement(\"gpu\", \"shared\", 2)))\n"
                                        + "authority: Manifest.caseAuthorityDescriptor(result)\n"
                                        + "Array(result.size(), "
                                        + "Manifest.caseRequirements(result).size(), "
                                        + "Manifest.caseAuthorityKind(authority), "
                                        + "Manifest.caseAuthorityFixtureIdentity(authority))",
                                fixture.activation()));

        assertEquals(4, observed.indexedSize());
        assertEquals(String.valueOf(6), String.valueOf(Integer.parseInt(com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerDecimalText(observed.indexedAt(0)))));
        assertEquals(String.valueOf(1), String.valueOf(Integer.parseInt(com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerDecimalText(observed.indexedAt(1)))));
        assertEquals("project-tree", stringAt(observed, 2));
        assertEquals("workspace", stringAt(observed, 3));
    }

    static void d133RealProjectTreeCorporaMaterializeCompletelyBeforeScheduling()
            throws Exception {
        Path[] roots = {
            Path.of("protos", "tests", "package-tool", "resolution-root"),
            Path.of("protos", "tests", "package-tool", "execution-plan"),
            Path.of("protos", "tests", "package-tool", "project-projection")
        };
        String[] namespaces = {
            "protos/package-tool/resolution-root",
            "protos/package-tool/execution-plan",
            "protos/package-tool/project-projection"
        };
        // I064/D171: the 16 F2E3B execution-plan cases moved to the Java-hosted
        // ProtosPackageToolExecutionPlanCaptureTest (host-captured custody).
        int[] expectedCounts = {8, 9, 3};

        for (int index = 0; index < roots.length; index++) {
            Fixture fixture = fixture();
            try (ProtosNioReadOnlyTreeFilesystemBackend backend =
                    new ProtosNioReadOnlyTreeFilesystemBackend(roots[index])) {
                assumeTrue(
                        backend.secureConfinementAvailable(),
                        "host provider has no SecureDirectoryStream");
                installFilesystem(fixture.prelude(), fixture.activation(), backend);

                ProtosArrayValue observed =
                        assertInstanceOf(
                                ProtosArrayValue.class,
                                completed(
                                        "Manifest: import(\"self:Manifest\")\n"
                                                + "cases: Manifest.planCases("
                                                + "Manifest.loadProjectTreeCases(filesystem, \""
                                                + namespaces[index]
                                                + "\"))\n"
                                                + "valid: true\n"
                                                + "cases.each((spec) => {\n"
                                                + "    authority: Manifest.caseAuthorityDescriptor(spec)\n"
                                                + "    (authority === null).ifTrue(() => { valid = false })\n"
                                                + "    (authority === null).ifFalse(() => {\n"
                                                + "        (Manifest.caseAuthorityKind(authority) == \"project-tree\").ifFalse(() => { valid = false })\n"
                                                + "        (Manifest.caseAuthorityIsolation(authority) == \"case\").ifFalse(() => { valid = false })\n"
                                                + "        (Manifest.caseAuthorityLifecycle(authority) == \"case\").ifFalse(() => { valid = false })\n"
                                                + "    })\n"
                                                + "})\n"
                                                + "Array(cases.size(), valid)",
                                        fixture.activation()));

                assertEquals(2, observed.indexedSize());
                assertEquals(String.valueOf(expectedCounts[index]), String.valueOf(Integer.parseInt(com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerDecimalText(observed.indexedAt(0)))));
                assertSame(
                        ProtosBooleanValue.TRUE,
                        observed.indexedAt(1));
            }
        }
    }

    @Test
    void threeColumnManifestParserKeepsRejectingTwoColumnRows()
            throws Exception {
        Fixture fixture = fixture();
        ProtosExecutionOutcome outcome =
                com.guillermomolina.protos.execution.ProtosTestExecutionSupport.execute(
                        "Manifest: import(\"self:Manifest\")\n"
                                + "Manifest.parseLine(\"a.protos\\ttrue\")",
                        fixture.activation());
        assertEquals(ProtosExecutionOutcome.State.FAILED, outcome.state());
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

    private static void installFilesystem(
            ProtosPrelude prelude,
            ProtosActivation activation,
            ProtosStandardFilesystemProtocol.Backend backend) {
        installFilesystem(prelude, activation, "filesystem", backend);
    }

    private static void installFilesystem(
            ProtosPrelude prelude,
            ProtosActivation activation,
            String slotName,
            ProtosStandardFilesystemProtocol.Backend backend) {
        ProtosObjectValue rawFilesystem =
                ProtosStandardFilesystemProtocol.createCapability(
                        prelude.bytesPrototypeForRuntime(),
                        activation,
                        backend);
        ProtosFilesystemValue filesystem =
                assertInstanceOf(
                        ProtosFilesystemValue.class,
                        rawFilesystem);
        activation.context().createLocalSlot(slotName, filesystem);
    }

    private static String stringAt(ProtosArrayValue array, int index) {
        return assertInstanceOf(
                        ProtosStringValue.class,
                        array.indexedAt(index))
                .value();
    }

    private static Object completed(
            String source,
            ProtosActivation activation) {
        ProtosExecutionOutcome outcome =
                com.guillermomolina.protos.execution.ProtosTestExecutionSupport.execute(
                        source, activation);
        assertEquals(
                ProtosExecutionOutcome.State.COMPLETED,
                outcome.state(),
                () ->
                        "Protos execution did not complete; state="
                                + outcome.state()
                                + ", error="
                                + outcome.error());
        return outcome.value();
    }


    /**
     * Test-only deterministic read-only storage for Manifest policy tests.
     *
     * <p>The NIO confinement backend has dedicated coverage elsewhere. These tests own the
     * guest-visible Manifest/TextReader behavior, so their bounded fixture trees are snapshotted
     * into the same portable Filesystem protocol.
     */
    private static final class DeterministicFilesystemBackend
            implements ProtosStandardFilesystemProtocol.Backend, AutoCloseable {
        private final java.util.Map<String, byte[]> files;

        private DeterministicFilesystemBackend(java.util.Map<String, byte[]> files) {
            java.util.LinkedHashMap<String, byte[]> snapshot =
                    new java.util.LinkedHashMap<>();
            files.forEach((name, bytes) -> snapshot.put(name, bytes.clone()));
            this.files = java.util.Map.copyOf(snapshot);
        }

        static DeterministicFilesystemBackend fromTree(Path root)
                throws java.io.IOException {
            java.util.LinkedHashMap<String, byte[]> files =
                    new java.util.LinkedHashMap<>();

            try (java.util.stream.Stream<Path> paths = Files.walk(root)) {
                for (Path path : paths.filter(Files::isRegularFile).toList()) {
                    String name =
                            root.relativize(path)
                                    .toString()
                                    .replace(java.io.File.separatorChar, '/');
                    files.put(name, Files.readAllBytes(path));
                }
            }

            return new DeterministicFilesystemBackend(files);
        }

        boolean secureConfinementAvailable() {
            return true;
        }

        @Override
        public com.guillermomolina.protos.runtime.ProtosFilesystemOpenFlow.Cancellation open(
                com.guillermomolina.protos.runtime.ProtosPathComponents path,
                com.guillermomolina.protos.runtime.ProtosFilesystemOpenOptions options,
                ProtosStandardFilesystemProtocol.OpenCompletion completion) {
            String name = portableName(path);
            byte[] bytes = name == null ? null : files.get(name);

            if (bytes == null
                    || !options.readAccess()
                    || options.writeAccess()
                    || options.creation()
                            != com.guillermomolina.protos.runtime.ProtosFilesystemOpenOptions.Creation.EXISTING
                    || options.truncateInitialContent()) {
                completion.failed();
                return () -> {};
            }

            ReadOnlyResource resource = new ReadOnlyResource(bytes);
            completion.succeeded(
                    resource,
                    new com.guillermomolina.protos.runtime.ProtosFileFlow.Capabilities(
                            true, false, false, false, false, false),
                    resource::releaseSilently);
            return () -> {};
        }

        private static String portableName(
                com.guillermomolina.protos.runtime.ProtosPathComponents path) {
            if (path.components().isEmpty()) {
                return null;
            }
            return String.join("/", path.components());
        }

        @Override
        public void close() {}

        private static final class ReadOnlyResource
                implements com.guillermomolina.protos.runtime.ProtosFileFlow.ReadableResource {
            private final byte[] bytes;
            private boolean closed;

            private ReadOnlyResource(byte[] bytes) {
                this.bytes = bytes.clone();
            }

            @Override
            public com.guillermomolina.protos.runtime.ProtosFileFlow.Cancellation readAt(
                    long position,
                    int maxBytes,
                    com.guillermomolina.protos.runtime.ProtosFileFlow.ReadCompletion completion) {
                if (closed) {
                    completion.failed();
                    return () -> {};
                }

                try {
                    int start = Math.toIntExact(position);
                    if (start < 0) {
                        completion.failed();
                    } else if (start >= bytes.length) {
                        completion.eof();
                    } else {
                        int end =
                                Math.min(
                                        bytes.length,
                                        Math.addExact(start, maxBytes));
                        completion.data(
                                java.util.Arrays.copyOfRange(bytes, start, end));
                    }
                } catch (ArithmeticException failure) {
                    completion.failed();
                }

                return () -> {};
            }

            @Override
            public void close(
                    com.guillermomolina.protos.runtime.ProtosFileFlow.CloseCompletion completion) {
                closed = true;
                completion.succeeded();
            }

            private void releaseSilently() {
                closed = true;
            }
        }
    }

    private record Fixture(
            ProtosPrelude prelude,
            ProtosActivation activation) {}
}
