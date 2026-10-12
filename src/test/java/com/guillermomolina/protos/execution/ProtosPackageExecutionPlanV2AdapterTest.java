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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.guillermomolina.protos.runtime.ProtosArrayValue;
import com.guillermomolina.protos.runtime.ProtosFloatValue;
import com.guillermomolina.protos.runtime.ProtosIntegerValue;
import com.guillermomolina.protos.runtime.ProtosObjectValue;
import com.guillermomolina.protos.runtime.ProtosPrelude;
import com.guillermomolina.protos.runtime.ProtosStringValue;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class ProtosPackageExecutionPlanV2AdapterTest {
    private static final Path CORE = Path.of("protos", "lib", "core");
    private static final String HEX =
            "5c2e8af458daf38c3e42ee3a68a928aa5ad2701166a59e6cabfb38645efe6f1a";

    // Mirrors the published buildV2FromVerifiedCaptures shape. The same ContentIdentity is
    // deliberately shared by three distinct exact external NodeRefs of one PackageId.
    private static final String PLAN_TEMPLATE =
            """
            exportsOf: (publicName, internalName) => {
                result: Map()
                result[publicName] = internalName
                result
            }
            contentOf: () => {
                {
                    method: "protos-package-tree-v1"
                    algorithm: "sha256"
                    hex: "%HEX%"
                }
            }
            wsRef: (packageId) => {
                {
                    kind: "workspace"
                    packageId: packageId
                }
            }
            regRef: (packageId, major, text) => {
                {
                    kind: "registry"
                    packageId: packageId
                    version: {
                        major: major
                        minor: 0
                        patch: 0
                        prerelease: null
                        text: text
                    }
                }
            }
            gitRef: (packageId, revision) => {
                {
                    kind: "git"
                    packageId: packageId
                    revision: revision
                }
            }
            {
                generation: 2
                root: wsRef("root")
                packages: Array(
                    {
                        ref: wsRef("root")
                        location: ""
                        exports: exportsOf("Main", "app/Main")
                    },
                    {
                        ref: wsRef("member")
                        location: "libs/a"
                        exports: Map()
                    },
                    {
                        ref: regRef("pkg", 1, "1.0.0")
                        content: contentOf()
                        exports: exportsOf("Public", "%EXPORT%")
                    },
                    {
                        ref: regRef("pkg", 2, "2.0.0")
                        content: contentOf()
                        exports: Map()
                    },
                    {
                        ref: gitRef("pkg", "abc123")
                        content: contentOf()
                        exports: Map()
                    },
                    {
                        ref: {
                            kind: "registry"
                            packageId: "pre"
                            version: {
                                major: 1
                                minor: 0
                                patch: 0
                                prerelease: Array(
                                    {
                                        numeric: false
                                        number: null
                                        text: "rc"
                                    },
                                    {
                                        numeric: true
                                        number: 1
                                        text: "1"
                                    }
                                )
                                text: "1.0.0-rc.1"
                            }
                        }
                        content: contentOf()
                        exports: Map()
                    }
                )
                dependencies: Array(
                    {
                        declaring: wsRef("root")
                        alias: "a"
                        target: wsRef("member")
                    },
                    {
                        declaring: wsRef("root")
                        alias: "one"
                        target: regRef("pkg", 1, "1.0.0")
                    },
                    {
                        declaring: wsRef("root")
                        alias: "two"
                        target: regRef("pkg", 2, "2.0.0")
                    },
                    {
                        declaring: regRef("pkg", 1, "1.0.0")
                        alias: "a"
                        target: gitRef("pkg", "abc123")
                    }
                )
            }
            """;

    private static ProtosPrelude prelude;

    @TempDir Path projectRoot;

    @BeforeAll
    static void bootstrapCorePrelude() throws IOException {
        prelude = new ProtosCoreBootstrap().bootstrap(CORE);
    }

    @BeforeEach
    void createWorkspaceLocations() throws IOException {
        Files.createDirectories(projectRoot.resolve("libs").resolve("a"));
    }

    @Test
    void detachesMixedWorkspaceRegistryAndGitPlanIntoImmutableHostData() throws Exception {
        try (ProtosHostedExecutionTestFixture hosted =
                ProtosHostedExecutionTestFixture.open(prelude)) {
            ProtosObjectValue raw = buildPlan(hosted, "internal/Thing");
            ProtosPackageExecutionPlanV2 detached =
                    ProtosPackageExecutionPlanV2Adapter.detach(raw, projectRoot);

            assertEquals(2, detached.generation());
            assertEquals(new ProtosPackageExecutionPlanV2.WorkspaceRef("root"), detached.root());
            assertEquals(6, detached.packages().size());
            assertEquals(4, detached.dependencies().size());

            var rootPackage =
                    assertInstanceOf(
                            ProtosPackageExecutionPlanV2.WorkspacePackage.class,
                            detached.packages().get(0));
            assertEquals("", rootPackage.location());
            assertEquals("app/Main", rootPackage.exports().get("Main"));
            assertEquals(
                    "libs/a",
                    assertInstanceOf(
                                    ProtosPackageExecutionPlanV2.WorkspacePackage.class,
                                    detached.packages().get(1))
                            .location());

            var registryOne = external(detached, 2);
            var registryTwo = external(detached, 3);
            var git = external(detached, 4);
            var prerelease = external(detached, 5);

            assertEquals("internal/Thing", registryOne.exports().get("Public"));
            assertEquals(
                    new ProtosExactExternalPackageIdentity.Registry("pkg", "1.0.0", content()),
                    registryOne.identity());
            assertEquals(
                    new ProtosExactExternalPackageIdentity.Registry("pkg", "2.0.0", content()),
                    registryTwo.identity());
            assertEquals(
                    new ProtosExactExternalPackageIdentity.Git("pkg", "abc123", content()),
                    git.identity());
            assertNotEquals(registryOne.identity(), registryTwo.identity());
            assertNotEquals(registryOne.identity(), git.identity());

            var preVersion =
                    assertInstanceOf(
                                    ProtosPackageExecutionPlanV2.RegistryRef.class,
                                    prerelease.ref())
                            .version();
            assertEquals("1.0.0-rc.1", preVersion.text());
            assertEquals(
                    List.of(
                            new ProtosPackageExecutionPlanV2.PrereleaseIdentifier(false, "rc"),
                            new ProtosPackageExecutionPlanV2.PrereleaseIdentifier(true, "1")),
                    preVersion.prerelease());

            var lastEdge = detached.dependencies().get(3);
            assertEquals(registryOne.ref(), lastEdge.declaring());
            assertEquals("a", lastEdge.alias());
            assertEquals(git.ref(), lastEdge.target());
            assertFalse(detached.toString().contains(projectRoot.toString()));

            assertThrows(
                    UnsupportedOperationException.class,
                    () -> detached.packages().add(detached.packages().get(0)));
            assertThrows(
                    UnsupportedOperationException.class,
                    () -> detached.dependencies().clear());
            assertThrows(
                    UnsupportedOperationException.class,
                    () -> registryOne.exports().put("Other", "internal/Thing"));
            assertThrows(
                    UnsupportedOperationException.class,
                    () -> preVersion.prerelease().clear());

            ProtosObjectValue rawRegistry = rawPackage(raw, 2);
            ProtosObjectValue rawVersion =
                    (ProtosObjectValue)
                            ((ProtosObjectValue) rawRegistry.readLocalSlot("ref").orElseThrow())
                                    .readLocalSlot("version")
                                    .orElseThrow();
            rawVersion.assignLocalSlot("text", new ProtosStringValue("9.9.9"));
            ((ProtosObjectValue) rawRegistry.readLocalSlot("content").orElseThrow())
                    .assignLocalSlot("hex", new ProtosStringValue("00"));
            rawPackages(raw).indexedPut(0, rawPackages(raw).indexedAt(1));

            assertEquals(
                    new ProtosExactExternalPackageIdentity.Registry("pkg", "1.0.0", content()),
                    external(detached, 2).identity());
            assertEquals("", ((ProtosPackageExecutionPlanV2.WorkspacePackage)
                    detached.packages().get(0)).location());
        }
    }

    @Test
    void generationOneAndGenerationTwoAdaptersRejectEachOthersGenerations() throws Exception {
        assertRejected(plan -> setInteger(plan, "generation", 1));
        assertRejected(plan -> setInteger(plan, "generation", 3));

        try (ProtosHostedExecutionTestFixture hosted =
                ProtosHostedExecutionTestFixture.open(prelude)) {
            ProtosObjectValue plan = buildPlan(hosted, "internal/Thing");
            assertThrows(
                    IOException.class,
                    () -> ProtosPackageExecutionPlanAdapter.detach(plan, projectRoot));
        }
    }

    @Test
    void rejectsMissingOrExtraFieldsAndKindMismatchedShapes() throws Exception {
        assertRejected(plan -> plan.createLocalSlot("unexpected", new ProtosStringValue("x")));
        assertRejected(plan -> plan.removeLocalSlot("dependencies"));
        assertRejected(plan -> ref(rawPackage(plan, 2)).removeLocalSlot("version"));
        assertRejected(
                plan ->
                        ref(rawPackage(plan, 2))
                                .createLocalSlot("revision", new ProtosStringValue("abc123")));
        assertRejected(
                plan -> {
                    ProtosObjectValue gitRef = ref(rawPackage(plan, 4));
                    gitRef.removeLocalSlot("revision");
                    gitRef.createLocalSlot("version", new ProtosStringValue("1.0.0"));
                });
        assertRejected(
                plan ->
                        ref(rawPackage(plan, 1))
                                .createLocalSlot("version", new ProtosStringValue("1.0.0")));
        assertRejected(
                plan -> ref(rawPackage(plan, 4)).assignLocalSlot("kind", str("path")));
        assertRejected(plan -> ref(rawPackage(plan, 4)).assignLocalSlot("revision", str("")));
    }

    @Test
    void requiresContentIdentityOnlyOnExternalAndLocationOnlyOnWorkspacePackages()
            throws Exception {
        assertRejected(plan -> rawPackage(plan, 2).removeLocalSlot("content"));
        assertRejected(plan -> rawPackage(plan, 2).createLocalSlot("location", str("libs/a")));
        assertRejected(plan -> rawPackage(plan, 1).removeLocalSlot("location"));
        assertRejected(
                plan ->
                        rawPackage(plan, 1)
                                .createLocalSlot(
                                        "content",
                                        rawPackage(plan, 2).readLocalSlot("content").orElseThrow()));
        assertRejected(
                plan ->
                        ((ProtosObjectValue) rawPackage(plan, 3).readLocalSlot("content").orElseThrow())
                                .assignLocalSlot("hex", str("")));
        assertRejected(plan -> rawPackage(plan, 1).assignLocalSlot("location", str("../escape")));
    }

    @Test
    void rejectsInconsistentReleaseVersionRecords() throws Exception {
        assertRejected(plan -> version(rawPackage(plan, 2)).assignLocalSlot("text", str("1.0.1")));
        assertRejected(plan -> setInteger(version(rawPackage(plan, 2)), "minor", -1));
        assertRejected(
                plan ->
                        version(rawPackage(plan, 5))
                                .assignLocalSlot("prerelease", prelude.newFrozenArray(List.of())));
    }

    @Test
    void enforcesExactNodeRefUniquenessAndRootAndDependencyClosure() throws Exception {
        assertRejected(
                plan ->
                        rawPackage(plan, 3)
                                .assignLocalSlot(
                                        "ref", rawPackage(plan, 2).readLocalSlot("ref").orElseThrow()));
        assertRejected(
                plan ->
                        plan.assignLocalSlot(
                                "root", rawPackage(plan, 2).readLocalSlot("ref").orElseThrow()));
        assertRejected(plan -> ref(rawPackage(plan, 0)).assignLocalSlot("packageId", str("other")));
        assertRejected(
                plan -> {
                    ProtosObjectValue target = edgeRef(plan, 1, "target");
                    ProtosObjectValue targetVersion =
                            (ProtosObjectValue) target.readLocalSlot("version").orElseThrow();
                    setInteger(targetVersion, "major", 3);
                    targetVersion.assignLocalSlot("text", str("3.0.0"));
                });
        assertRejected(plan -> edgeRef(plan, 3, "declaring").assignLocalSlot("packageId", str("nope")));
    }

    @Test
    void rejectsDuplicateDeclaringAliasAndInvalidRuntimeNames() throws Exception {
        assertRejected(plan -> edge(plan, 2).assignLocalSlot("alias", str("one")));
        assertRejected(plan -> edge(plan, 0).assignLocalSlot("alias", str("bad-name")));
        assertRejected(plan -> edge(plan, 0).assignLocalSlot("alias", str("CON")));

        try (ProtosHostedExecutionTestFixture hosted =
                ProtosHostedExecutionTestFixture.open(prelude)) {
            ProtosObjectValue plan = buildPlan(hosted, "../Thing");
            assertThrows(
                    IOException.class,
                    () -> ProtosPackageExecutionPlanV2Adapter.detach(plan, projectRoot));
        }
    }

    @Test
    void detachesComponentsAndNumericPrereleaseBeyondSignedSixtyFourExactly() throws Exception {
        BigInteger hugeMajor = BigInteger.ONE.shiftLeft(70).add(BigInteger.valueOf(3L));
        BigInteger hugeNumber = BigInteger.ONE.shiftLeft(64).add(BigInteger.ONE);
        String twoText = hugeMajor + ".0.0";
        String preText = "1.0.0-rc." + hugeNumber;
        try (ProtosHostedExecutionTestFixture hosted =
                ProtosHostedExecutionTestFixture.open(prelude)) {
            ProtosObjectValue plan = buildPlan(hosted, "internal/Thing");
            for (ProtosObjectValue version : List.of(
                    version(rawPackage(plan, 3)),
                    (ProtosObjectValue) edgeRef(plan, 2, "target")
                            .readLocalSlot("version").orElseThrow())) {
                version.assignLocalSlot("major", com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerFromHost(hugeMajor, prelude));
                version.assignLocalSlot("text", str(twoText));
            }
            ProtosObjectValue preVersion = version(rawPackage(plan, 5));
            ProtosObjectValue numeric = (ProtosObjectValue)
                    ((ProtosArrayValue) preVersion.readLocalSlot("prerelease").orElseThrow())
                            .indexedAt(1);
            numeric.assignLocalSlot("number", com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerFromHost(hugeNumber, prelude));
            numeric.assignLocalSlot("text", str(hugeNumber.toString()));
            preVersion.assignLocalSlot("text", str(preText));

            ProtosPackageExecutionPlanV2 detached =
                    ProtosPackageExecutionPlanV2Adapter.detach(plan, projectRoot);
            var two = assertInstanceOf(
                    ProtosPackageExecutionPlanV2.RegistryRef.class, external(detached, 3).ref());
            assertEquals(hugeMajor.toString(), two.version().major());
            assertEquals("0", two.version().minor());
            assertEquals(twoText, two.version().text());
            assertEquals(two, detached.dependencies().get(2).target());
            var pre = assertInstanceOf(
                    ProtosPackageExecutionPlanV2.RegistryRef.class, external(detached, 5).ref());
            assertEquals(
                    List.of(
                            new ProtosPackageExecutionPlanV2.PrereleaseIdentifier(false, "rc"),
                            new ProtosPackageExecutionPlanV2.PrereleaseIdentifier(
                                    true, hugeNumber.toString())),
                    pre.version().prerelease());
            assertEquals(preText, pre.version().text());
        }
    }

    @Test
    void rejectsNonIntegerNegativeLargeAndNonCanonicalVersionRepresentations() throws Exception {
        BigInteger huge = BigInteger.ONE.shiftLeft(70);
        assertRejected(plan -> version(rawPackage(plan, 2))
                .assignLocalSlot("major", new ProtosFloatValue(1.0d)));
        assertRejected(plan -> version(rawPackage(plan, 2))
                .assignLocalSlot("major", str("1")));
        assertRejected(plan -> {
            ProtosObjectValue version = version(rawPackage(plan, 2));
            version.assignLocalSlot("major", com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerFromHost(huge.negate(), prelude));
            version.assignLocalSlot("text", str(huge.negate() + ".0.0"));
        });
        // The text must be exactly the canonical rendering: no leading zeros or sign.
        assertRejected(plan -> version(rawPackage(plan, 2)).assignLocalSlot("text", str("01.0.0")));
        assertRejected(plan -> version(rawPackage(plan, 2)).assignLocalSlot("text", str("+1.0.0")));
        assertRejected(plan -> {
            ProtosObjectValue version = version(rawPackage(plan, 2));
            version.assignLocalSlot("major", com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerFromHost(huge, prelude));
            version.assignLocalSlot("text", str("1.0.0"));
        });
        // A large Integer is never an ordinary structural record.
        assertRejected(plan -> ref(rawPackage(plan, 2))
                .assignLocalSlot("version", com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerFromHost(huge, prelude)));
        assertRejected(plan -> rawPackage(plan, 2)
                .assignLocalSlot("ref", com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerFromHost(huge, prelude)));
        // Numeric prerelease identifiers: Integer number, canonical text, digits-only alphanumeric.
        assertRejected(plan -> prereleaseIdentifier(plan, 1)
                .assignLocalSlot("text", str("01")));
        assertRejected(plan -> prereleaseIdentifier(plan, 1)
                .assignLocalSlot("number", com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerFromHost(huge, prelude)));
        assertRejected(plan -> prereleaseIdentifier(plan, 1)
                .assignLocalSlot("number", new ProtosFloatValue(1.0d)));
        assertRejected(plan -> {
            prereleaseIdentifier(plan, 0).assignLocalSlot("text", str("7"));
            version(rawPackage(plan, 5)).assignLocalSlot("text", str("1.0.0-7.1"));
        });
    }

    private static ProtosObjectValue prereleaseIdentifier(ProtosObjectValue plan, int index) {
        return (ProtosObjectValue)
                ((ProtosArrayValue) version(rawPackage(plan, 5)).readLocalSlot("prerelease")
                                .orElseThrow())
                        .indexedAt(index);
    }

    private void assertRejected(Consumer<ProtosObjectValue> mutation) throws Exception {
        try (ProtosHostedExecutionTestFixture hosted =
                ProtosHostedExecutionTestFixture.open(prelude)) {
            ProtosObjectValue plan = buildPlan(hosted, "internal/Thing");
            ProtosPackageExecutionPlanV2Adapter.detach(plan, projectRoot);
            mutation.accept(plan);
            assertThrows(
                    IOException.class,
                    () -> ProtosPackageExecutionPlanV2Adapter.detach(plan, projectRoot));
        }
    }

    private static ProtosObjectValue buildPlan(
            ProtosHostedExecutionTestFixture hosted, String exportInternalName) {
        ProtosExecutionOutcome outcome =
                hosted.execute(
                        "<package-execution-plan-v2>",
                        PLAN_TEMPLATE
                                .replace("%HEX%", HEX)
                                .replace("%EXPORT%", exportInternalName));
        assertEquals(ProtosExecutionOutcome.State.COMPLETED, outcome.state());
        return assertInstanceOf(ProtosObjectValue.class, outcome.value());
    }

    private static ProtosPackageContentIdentity content() {
        return new ProtosPackageContentIdentity("protos-package-tree-v1", "sha256", HEX);
    }

    private static ProtosPackageExecutionPlanV2.ExternalPackage external(
            ProtosPackageExecutionPlanV2 plan, int index) {
        return assertInstanceOf(
                ProtosPackageExecutionPlanV2.ExternalPackage.class, plan.packages().get(index));
    }

    private static ProtosArrayValue rawPackages(ProtosObjectValue plan) {
        return (ProtosArrayValue) plan.readLocalSlot("packages").orElseThrow();
    }

    private static ProtosObjectValue rawPackage(ProtosObjectValue plan, int index) {
        return (ProtosObjectValue) rawPackages(plan).indexedAt(index);
    }

    private static ProtosObjectValue ref(ProtosObjectValue packageValue) {
        return (ProtosObjectValue) packageValue.readLocalSlot("ref").orElseThrow();
    }

    private static ProtosObjectValue version(ProtosObjectValue packageValue) {
        return (ProtosObjectValue) ref(packageValue).readLocalSlot("version").orElseThrow();
    }

    private static ProtosObjectValue edge(ProtosObjectValue plan, int index) {
        return (ProtosObjectValue)
                ((ProtosArrayValue) plan.readLocalSlot("dependencies").orElseThrow())
                        .indexedAt(index);
    }

    private static ProtosObjectValue edgeRef(ProtosObjectValue plan, int index, String side) {
        return (ProtosObjectValue) edge(plan, index).readLocalSlot(side).orElseThrow();
    }

    private static void setInteger(ProtosObjectValue object, String name, long value) {
        object.assignLocalSlot(name, new ProtosIntegerValue(value));
    }

    private static ProtosStringValue str(String value) {
        return new ProtosStringValue(value);
    }
}
