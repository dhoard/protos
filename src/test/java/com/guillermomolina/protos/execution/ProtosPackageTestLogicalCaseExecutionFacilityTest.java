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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.guillermomolina.protos.runtime.ProtosActivation;
import com.guillermomolina.protos.runtime.ProtosArrayValue;
import com.guillermomolina.protos.runtime.ProtosBooleanValue;
import com.guillermomolina.protos.runtime.ProtosFutureValue;
import com.guillermomolina.protos.runtime.ProtosIntegerValue;
import com.guillermomolina.protos.runtime.ProtosModuleKey;
import com.guillermomolina.protos.runtime.ProtosObjectValue;
import com.guillermomolina.protos.runtime.ProtosPrelude;
import com.guillermomolina.protos.runtime.ProtosStringValue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * TOOL009 Package Tool infrastructure: focal coverage for the Package-flavored suite-native
 * logical Case execution route.
 *
 * <p>These tests exercise the ordinary {@link ProtosTestLogicalCaseExecutionFacility} /
 * {@link ProtosTestLogicalCaseAttemptBridge} machinery parameterized with a Package-specific
 * fallback resolver (the exact bundled Package Tool resolver construction {@code packagePrelude}
 * itself uses, overlaid with host-selected exact references to Package Tool's own
 * {@code RuntimeNames} module and to the Package manifest schema module {@code ManifestSchemaV1}
 * so a selected Test body can prove which bootstrap resolved it),
 * demonstrating that the suite-native route can resolve and execute Package-flavored suites
 * without a parallel Case authority and without touching the legacy {@code packageExecutionAsync}
 * facility or its {@code packagePrelude} bootstrap. The manifest schema coverage below invokes
 * the real {@code protos/tools/package/ManifestSchemaV1.protos} module (not a copy embedded in
 * this test), whose TOML 1.0 parsing resolves {@code std:toml/TOML} through the bundled-tool
 * Standard Library resolver alone, never through project package resolution (I079). None of the
 * TOML corpus fixtures under
 * {@code protos/tests/package-tool/toml-syntax} are used here; every suite source is a dedicated
 * inline fixture for this infrastructure, exercising the real modules through ordinary imports.
 *
 * <p>TOOL009 Publication 1 extends the same finite exact-overlay strategy to the non-TOML
 * Package module graph consumed by the 157 version/lock/resolution-input case-outcomes corpora:
 * {@code ReleaseVersion}, {@code DependencyConstraint}, {@code FreshVersionSelection},
 * {@code RetainedVersionSelection}, {@code LockSyntax}, {@code LockDocument}, and
 * {@code ResolutionInput}. The tests below invoke the real modules under those names to prove
 * {@code DependencyConstraint -> ReleaseVersion}, {@code FreshVersionSelection ->
 * DependencyConstraint -> ReleaseVersion}, {@code RetainedVersionSelection ->
 * FreshVersionSelection -> DependencyConstraint -> ReleaseVersion}, {@code LockDocument ->
 * LockSyntax -> ReleaseVersion}, and {@code ResolutionInput -> LockSyntax / ReleaseVersion +
 * std:collections/Array + std:crypto/SHA256} all resolve and execute for real.
 */
final class ProtosPackageTestLogicalCaseExecutionFacilityTest {
    private static final Path CORE = Path.of("protos", "lib", "core");
    private static final Path STANDARD_LIBRARY = Path.of("protos", "lib");
    private static final Path TOOL_ROOT = Path.of("protos", "tools", "test");
    private static final Path SHARED_ROOT = Path.of("protos", "tools", "shared");
    private static final Path PACKAGE_TOOL_ROOT = Path.of("protos", "tools", "package");
    private static final Path RESOLUTION_INPUT_LOCK_CORPUS =
            Path.of("protos", "tests", "package-tool", "resolution-input-lock");

    private static final String SOURCE_USES_PACKAGE_RUNTIME_NAMES =
            """
            TestValue: import("std:test/Test")
            RuntimeNames: import("package-runtime-names")

            tests: Array(
                TestValue("usesPackageBootstrap", () => {
                    RuntimeNames.isReserved("CON")
                })
            )
            """;

    private static final String SOURCE_TWO_TESTS =
            """
            TestValue: import("std:test/Test")

            tests: Array(
                TestValue("a", () => {
                    1
                }),
                TestValue("b", () => {
                    2
                })
            )
            """;

    private static final String SOURCE_SELECTED_TEST_FAILS =
            """
            TestValue: import("std:test/Test")

            tests: Array(
                TestValue("fails", () => {
                    Error().signal()
                })
            )
            """;

    private static final String SOURCE_USES_MANIFEST_SCHEMA_V1 =
            """
            TestValue: import("std:test/Test")
            Schema: import("self:ManifestSchemaV1")

            tests: Array(
                TestValue("resolvesManifestSchemaAndItsLazyStandardTomlImport", () => {
                    model: Schema.parseBase(
                        "manifest-version = 1\\n[package]\\nid = \\"pkg-id\\"\\nversion = \\"1.2.3\\"\\n")
                    ok: model.package.id == "pkg-id"
                    (model.package.version == "1.2.3").ifFalse(() => { ok = false })
                    rejectsToml11: false
                    Error.handle(() => {
                        Schema.parseBase(
                            "manifest-version = 1\\npackage = { id = \\"p\\", version = \\"1\\", }\\n")
                        null
                    }, (error) => {
                        rejectsToml11 = true
                        null
                    })
                    ok && rejectsToml11
                })
            )
            """;

    // TOOL009 Publication 1: the finite Package non-TOML module graph
    // (ReleaseVersion/DependencyConstraint/FreshVersionSelection/
    // RetainedVersionSelection/LockSyntax/LockDocument/ResolutionInput).
    private static final String SOURCE_USES_RETAINED_VERSION_SELECTION =
            """
            TestValue: import("std:test/Test")
            Selection: import("self:RetainedVersionSelection")

            tests: Array(
                TestValue("selectsFreshCandidateWhenRetainedIsUnavailable", () => {
                    result: Selection.selectText(
                        "^1.0.0",
                        "9.9.9",
                        Array("1.0.0", "1.2.0", "1.5.0"))
                    result == "1.5.0"
                })
            )
            """;

    private static final String SOURCE_USES_LOCK_DOCUMENT =
            """
            TestValue: import("std:test/Test")
            Document: import("self:LockDocument")

            tests: Array(
                TestValue("resolvesRealLockDocumentModuleAndItsTransitiveLockSyntaxDependency", () => {
                    model: {
                        header: {
                            lockFormatText: "1"
                            resolverVersionText: "1"
                            resolutionMethod: "protos-resolution-input-v1"
                            digestAlgorithm: "sha256"
                            digestHex: "ab"
                        }
                        root: {
                            kind: "workspace"
                            packageId: "root-pkg"
                        }
                        workspaceMembers: Array()
                        registryNodes: Array()
                        gitNodes: Array()
                        dependencies: Array()
                    }
                    rendered: Document.write(model)
                    rendered ==
                        "lock-format 1\\nresolver-version 1\\nresolution-input protos-resolution-input-v1 sha256:ab\\n\\nroot workspace \\"root-pkg\\"\\n"
                })
            )
            """;

    private static final String SOURCE_USES_RESOLUTION_INPUT =
            """
            TestValue: import("std:test/Test")
            Input: import("self:ResolutionInput")
            Version: import("self:ReleaseVersion")

            tests: Array(
                TestValue(
                    "resolvesRealResolutionInputModuleAndItsTransitiveLockSyntaxReleaseVersionAndStandardLibraryDependencies",
                    () => {
                        resolutionRoot: {
                            languageCompatibility: "0.1"
                            root: {
                                packageId: "root-pkg"
                                version: Version.parse("1.0.0")
                                compatibility: null
                                dependencies: Array()
                            }
                            members: Array()
                        }
                        digest: Input.digest(resolutionRoot)
                        ok: digest.method == "protos-resolution-input-v1"
                        (digest.algorithm == "sha256").ifFalse(() => { ok = false })
                        (Encoding.UTF8.encode(digest.hex).size() == 64).ifFalse(() => { ok = false })
                        ok
                    })
            )
            """;

    @Test
    void resolvesPackageRuntimeNamesOverlayAndExecutesSelectedTestExactlyOnce(
            @TempDir Path root) throws Exception {
        writeSuite(root, SOURCE_USES_PACKAGE_RUNTIME_NAMES);

        ManualSubmission submission = new ManualSubmission();
        ProtosModuleResolver resolver = SharedCore.RESOLVER;
        ProtosPrelude prelude = SharedCore.PRELUDE;
        ProtosActivation activation = prelude.newModuleActivation();

        try (ProtosPolyglotRuntimeHost runtimeHost = ProtosPolyglotRuntimeHost.open();
                ProtosTestLogicalCaseExecutionFacility facility =
                        ProtosTestLogicalCaseExecutionFacility.install(
                                activation,
                                "packageLogicalCaseExecutionAsync",
                                CORE,
                                resolver,
                                List.of(
                                        new ProtosTestToolFileSelectionFacility.CorpusSourceRoot(
                                                "package-corpus", root)),
                                runtimeHost,
                                submission)) {

            ProtosFutureValue future =
                    invoke(
                            facility,
                            prelude,
                            activation,
                            SOURCE_USES_PACKAGE_RUNTIME_NAMES,
                            List.of("usesPackageBootstrap"),
                            "usesPackageBootstrap");

            // Exactly one host execution is enqueued for the one selected Test.
            assertEquals(1, submission.queuedCount());
            assertTrue(submission.runNext());
            assertFalse(submission.runNext(), "no second host execution for the same Case");

            assertTrue(activation.executionDomain().dispatchOne());

            // "package-runtime-names" only resolves under the Package-flavored fallback
            // resolver: it does not exist under the ordinary/Actor/Group fallback
            // resolvers. A successful "true" observation is reachable only if the
            // Package Tool bootstrap resolved RuntimeNames.protos and the selected
            // Test body actually executed exactly once.
            assertSame(
                    ProtosBooleanValue.TRUE,
                    assertCompletedObservationValue(future));
        }
    }

    // TOOL009-B: proves the Package-flavored facility itself (not only the
    // lower-level ProtosTestLogicalCaseAttemptBridge) decodes a 3-element
    // sourceAssociation's D133 project-tree CaseAuthority descriptor, provisions
    // the trusted physical authority through the projectTreeCasesRootByCorpusId
    // map, and dispatches through the ordinary async completion boundary exactly
    // once. This is the current authoritative owner of what the removed
    // whole-source CaseAuthority execution facility used to cover before its
    // removal.
    @SuppressWarnings("try")
    @Test
    void resolvesProjectTreeDescriptorAndProvisionsPhysicalAuthorityExactlyOnce()
            throws Exception {
        Path casesRoot = RESOLUTION_INPUT_LOCK_CORPUS.resolve("cases");
        Path authorityRoot = casesRoot.resolve("fresh");

        try (ProtosNioReadOnlyTreeFilesystemBackend probe =
                new ProtosNioReadOnlyTreeFilesystemBackend(authorityRoot)) {
            assumeTrue(
                    probe.secureConfinementAvailable(),
                    "host provider has no SecureDirectoryStream");
        }

        Path fixturesRoot = RESOLUTION_INPUT_LOCK_CORPUS.resolve("fixtures");
        String source =
                Files.readString(fixturesRoot.resolve("fresh.protos"), StandardCharsets.UTF_8);

        ManualSubmission submission = new ManualSubmission();
        ProtosModuleResolver resolver = SharedCore.RESOLVER;
        ProtosPrelude prelude = SharedCore.PRELUDE;
        ProtosActivation activation = prelude.newModuleActivation();

        try (ProtosPolyglotRuntimeHost runtimeHost = ProtosPolyglotRuntimeHost.open();
                ProtosTestLogicalCaseExecutionFacility facility =
                        ProtosTestLogicalCaseExecutionFacility.install(
                                activation,
                                "packageLogicalCaseExecutionAsync",
                                CORE,
                                resolver,
                                List.of(
                                        new ProtosTestToolFileSelectionFacility.CorpusSourceRoot(
                                                "package-corpus", fixturesRoot)),
                                Map.of("package-corpus", casesRoot),
                                runtimeHost,
                                submission)) {

            Object execution =
                    activation
                            .context()
                            .readLocalSlot("packageLogicalCaseExecutionAsync")
                            .orElseThrow();

            ProtosArrayValue descriptor =
                    prelude.newFrozenArray(
                            List.of(
                                    new ProtosStringValue("project-tree"),
                                    new ProtosStringValue("fresh"),
                                    new ProtosStringValue("case"),
                                    new ProtosStringValue("case")));

            ProtosArrayValue sourceAssociation =
                    prelude.newFrozenArray(
                            List.of(
                                    new ProtosStringValue("package-corpus"),
                                    new ProtosStringValue("fresh.protos"),
                                    descriptor));

            ProtosArrayValue signatureValue =
                    prelude.newFrozenArray(List.of(new ProtosStringValue("fresh")));

            ProtosFutureValue future =
                    assertInstanceOf(
                            ProtosFutureValue.class,
                            ProtosInvocation.invoke(
                                    execution,
                                    List.of(
                                            sourceAssociation,
                                            signatureValue,
                                            new ProtosStringValue("fresh")),
                                    activation));

            assertEquals(1, submission.queuedCount());
            assertTrue(submission.runNext());
            assertTrue(activation.executionDomain().dispatchOne());

            assertEquals("case-execution", phaseOf(future));
        }
    }

    @Test
    void resolvesRealManifestSchemaV1AndItsLazyStandardTomlImport(@TempDir Path root)
            throws Exception {
        writeSuite(root, SOURCE_USES_MANIFEST_SCHEMA_V1);

        ManualSubmission submission = new ManualSubmission();
        ProtosModuleResolver resolver = SharedCore.RESOLVER;
        ProtosPrelude prelude = SharedCore.PRELUDE;
        ProtosActivation activation = prelude.newModuleActivation();

        try (ProtosPolyglotRuntimeHost runtimeHost = ProtosPolyglotRuntimeHost.open();
                ProtosTestLogicalCaseExecutionFacility facility =
                        ProtosTestLogicalCaseExecutionFacility.install(
                                activation,
                                "packageLogicalCaseExecutionAsync",
                                CORE,
                                resolver,
                                List.of(
                                        new ProtosTestToolFileSelectionFacility.CorpusSourceRoot(
                                                "package-corpus", root)),
                                runtimeHost,
                                submission)) {

            ProtosFutureValue future =
                    invoke(
                            facility,
                            prelude,
                            activation,
                            SOURCE_USES_MANIFEST_SCHEMA_V1,
                            List.of("resolvesManifestSchemaAndItsLazyStandardTomlImport"),
                            "resolvesManifestSchemaAndItsLazyStandardTomlImport");

            assertTrue(submission.runNext());
            assertTrue(activation.executionDomain().dispatchOne());

            // "self:ManifestSchemaV1" resolves to the real
            // protos/tools/package/ManifestSchemaV1.protos. Its "std:toml/TOML" import is
            // lazy: it lives inside the "parseBase" callable body, not at module top level. The
            // selected Test body calls "Schema.parseBase(...)", which is the only thing that
            // forces that lazy import to resolve through the Standard Library resolver; the
            // overlay below carries no TOML entry and no project package resolver exists here.
            // The body also requires a TOML 1.1-only inline-table trailing comma to be
            // rejected, proving the schema selects TOML 1.0.
            assertSame(
                    ProtosBooleanValue.TRUE,
                    assertCompletedObservationValue(future));
        }
    }

    @Test
    void resolvesRealRetainedVersionSelectionAndItsTransitiveFreshAndConstraintGraph(
            @TempDir Path root) throws Exception {
        writeSuite(root, SOURCE_USES_RETAINED_VERSION_SELECTION);

        ManualSubmission submission = new ManualSubmission();
        ProtosModuleResolver resolver = SharedCore.RESOLVER;
        ProtosPrelude prelude = SharedCore.PRELUDE;
        ProtosActivation activation = prelude.newModuleActivation();

        try (ProtosPolyglotRuntimeHost runtimeHost = ProtosPolyglotRuntimeHost.open();
                ProtosTestLogicalCaseExecutionFacility facility =
                        ProtosTestLogicalCaseExecutionFacility.install(
                                activation,
                                "packageLogicalCaseExecutionAsync",
                                CORE,
                                resolver,
                                List.of(
                                        new ProtosTestToolFileSelectionFacility.CorpusSourceRoot(
                                                "package-corpus", root)),
                                runtimeHost,
                                submission)) {

            ProtosFutureValue future =
                    invoke(
                            facility,
                            prelude,
                            activation,
                            SOURCE_USES_RETAINED_VERSION_SELECTION,
                            List.of("selectsFreshCandidateWhenRetainedIsUnavailable"),
                            "selectsFreshCandidateWhenRetainedIsUnavailable");

            assertTrue(submission.runNext());
            assertTrue(activation.executionDomain().dispatchOne());

            // "self:RetainedVersionSelection" resolves to the real
            // protos/tools/package/RetainedVersionSelection.protos, which imports
            // "self:FreshVersionSelection", which imports "self:DependencyConstraint" and
            // "self:ReleaseVersion". The retained candidate ("9.9.9") is absent from the
            // candidate set, so RetainedVersionSelection.select falls back to
            // Fresh.select, which itself calls Constraint.satisfies and Version.compare
            // to pick the highest satisfying candidate. A "true" observation is reachable
            // only if the complete real chain resolved and executed.
            assertSame(
                    ProtosBooleanValue.TRUE,
                    assertCompletedObservationValue(future));
        }
    }

    @Test
    void resolvesRealLockDocumentModuleAndItsTransitiveLockSyntaxDependency(
            @TempDir Path root) throws Exception {
        writeSuite(root, SOURCE_USES_LOCK_DOCUMENT);

        ManualSubmission submission = new ManualSubmission();
        ProtosModuleResolver resolver = SharedCore.RESOLVER;
        ProtosPrelude prelude = SharedCore.PRELUDE;
        ProtosActivation activation = prelude.newModuleActivation();

        try (ProtosPolyglotRuntimeHost runtimeHost = ProtosPolyglotRuntimeHost.open();
                ProtosTestLogicalCaseExecutionFacility facility =
                        ProtosTestLogicalCaseExecutionFacility.install(
                                activation,
                                "packageLogicalCaseExecutionAsync",
                                CORE,
                                resolver,
                                List.of(
                                        new ProtosTestToolFileSelectionFacility.CorpusSourceRoot(
                                                "package-corpus", root)),
                                runtimeHost,
                                submission)) {

            ProtosFutureValue future =
                    invoke(
                            facility,
                            prelude,
                            activation,
                            SOURCE_USES_LOCK_DOCUMENT,
                            List.of(
                                    "resolvesRealLockDocumentModuleAndItsTransitiveLockSyntaxDependency"),
                            "resolvesRealLockDocumentModuleAndItsTransitiveLockSyntaxDependency");

            assertTrue(submission.runNext());
            assertTrue(activation.executionDomain().dispatchOne());

            // "self:LockDocument" resolves to the real protos/tools/package/LockDocument.protos,
            // which imports "self:LockSyntax" and "self:ReleaseVersion". Document.write renders
            // the header/root lines through the real LockSyntax renderHeader/renderNodeRef/
            // renderQstring functions and then re-validates the result through
            // LockSyntax.parseStructural/parseHeader before returning it. The exact rendered
            // text is only reachable if the whole chain resolved and executed for real.
            assertSame(
                    ProtosBooleanValue.TRUE,
                    assertCompletedObservationValue(future));
        }
    }

    @Test
    void resolvesRealResolutionInputModuleAndItsTransitiveGraphAndStandardLibraryDependencies(
            @TempDir Path root) throws Exception {
        writeSuite(root, SOURCE_USES_RESOLUTION_INPUT);

        ManualSubmission submission = new ManualSubmission();
        ProtosModuleResolver resolver = SharedCore.RESOLVER;
        ProtosPrelude prelude = SharedCore.PRELUDE;
        ProtosActivation activation = prelude.newModuleActivation();

        try (ProtosPolyglotRuntimeHost runtimeHost = ProtosPolyglotRuntimeHost.open();
                ProtosTestLogicalCaseExecutionFacility facility =
                        ProtosTestLogicalCaseExecutionFacility.install(
                                activation,
                                "packageLogicalCaseExecutionAsync",
                                CORE,
                                resolver,
                                List.of(
                                        new ProtosTestToolFileSelectionFacility.CorpusSourceRoot(
                                                "package-corpus", root)),
                                runtimeHost,
                                submission)) {

            ProtosFutureValue future =
                    invoke(
                            facility,
                            prelude,
                            activation,
                            SOURCE_USES_RESOLUTION_INPUT,
                            List.of(
                                    "resolvesRealResolutionInputModuleAndItsTransitiveLockSyntaxReleaseVersionAndStandardLibraryDependencies"),
                            "resolvesRealResolutionInputModuleAndItsTransitiveLockSyntaxReleaseVersionAndStandardLibraryDependencies");

            assertTrue(submission.runNext());
            assertTrue(activation.executionDomain().dispatchOne());

            // "self:ResolutionInput" resolves to the real
            // protos/tools/package/ResolutionInput.protos, which imports "self:LockSyntax",
            // "self:ReleaseVersion", "std:collections/Array", and "std:crypto/SHA256".
            // Input.digest renders the canonical resolution-input text through the real
            // LockSyntax.renderQstring and ReleaseVersion.parse/compare (via versionText),
            // sorts the (empty) member array through the real standard Array library, and
            // hashes the result through the real standard SHA256 library. A 64-hex-character
            // sha256 digest is only reachable if the complete real graph resolved and
            // executed.
            assertSame(
                    ProtosBooleanValue.TRUE,
                    assertCompletedObservationValue(future));
        }
    }

    @Test
    void selectedTestBodyIsCaseAuthorityNotModuleCompletion(@TempDir Path root)
            throws Exception {
        writeSuite(root, SOURCE_SELECTED_TEST_FAILS);

        ManualSubmission submission = new ManualSubmission();
        ProtosModuleResolver resolver = SharedCore.RESOLVER;
        ProtosPrelude prelude = SharedCore.PRELUDE;
        ProtosActivation activation = prelude.newModuleActivation();

        try (ProtosPolyglotRuntimeHost runtimeHost = ProtosPolyglotRuntimeHost.open();
                ProtosTestLogicalCaseExecutionFacility facility =
                        ProtosTestLogicalCaseExecutionFacility.install(
                                activation,
                                "packageLogicalCaseExecutionAsync",
                                CORE,
                                resolver,
                                List.of(
                                        new ProtosTestToolFileSelectionFacility.CorpusSourceRoot(
                                                "package-corpus", root)),
                                runtimeHost,
                                submission)) {

            ProtosFutureValue future =
                    invoke(
                            facility,
                            prelude,
                            activation,
                            SOURCE_SELECTED_TEST_FAILS,
                            List.of("fails"),
                            "fails");

            assertTrue(submission.runNext());
            assertTrue(activation.executionDomain().dispatchOne());

            ProtosObjectValue completion =
                    assertInstanceOf(
                            ProtosObjectValue.class, future.resolvedValue().orElseThrow());
            ProtosStringValue phase =
                    assertInstanceOf(
                            ProtosStringValue.class,
                            completion.readLocalSlot("phase").orElseThrow());
            // The suite declaration itself (building the "tests" Array) completed
            // successfully; only the selected Test body signaled. If module
            // completion were the Case authority, this Case would be reported as
            // completed. It is not: the observation records the selected Test's own
            // failure.
            assertEquals("case-execution", phase.value());

            ProtosObjectValue observation =
                    assertInstanceOf(
                            ProtosObjectValue.class,
                            completion.readLocalSlot("observation").orElseThrow());
            ProtosStringValue state =
                    assertInstanceOf(
                            ProtosStringValue.class,
                            observation.readLocalSlot("state").orElseThrow());
            assertEquals("failed", state.value());
        }
    }

    @Test
    void multipleTestsSelectsExactlyTheRequestedOne(@TempDir Path root) throws Exception {
        writeSuite(root, SOURCE_TWO_TESTS);

        ManualSubmission submission = new ManualSubmission();
        ProtosModuleResolver resolver = SharedCore.RESOLVER;
        ProtosPrelude prelude = SharedCore.PRELUDE;
        ProtosActivation activation = prelude.newModuleActivation();

        try (ProtosPolyglotRuntimeHost runtimeHost = ProtosPolyglotRuntimeHost.open();
                ProtosTestLogicalCaseExecutionFacility facility =
                        ProtosTestLogicalCaseExecutionFacility.install(
                                activation,
                                "packageLogicalCaseExecutionAsync",
                                CORE,
                                resolver,
                                List.of(
                                        new ProtosTestToolFileSelectionFacility.CorpusSourceRoot(
                                                "package-corpus", root)),
                                runtimeHost,
                                submission)) {

            ProtosFutureValue future =
                    invoke(
                            facility,
                            prelude,
                            activation,
                            SOURCE_TWO_TESTS,
                            List.of("a", "b"),
                            "b");

            assertTrue(submission.runNext());
            assertTrue(activation.executionDomain().dispatchOne());

            ProtosIntegerValue value =
                    assertInstanceOf(
                            ProtosIntegerValue.class, assertCompletedObservationValue(future));
            assertEquals(String.valueOf("2"), com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerDecimalText(value));
        }
    }

    @Test
    void eachCaseIsIsolatedAndDoesNotShareMutableStateWithAPriorCase(@TempDir Path root)
            throws Exception {
        String source =
                """
                TestValue: import("std:test/Test")

                counter: { value: 0 }

                tests: Array(
                    TestValue("increments", () => {
                        counter.value = counter.value + 1
                        counter.value
                    })
                )
                """;

        writeSuite(root, source);

        ManualSubmission submission = new ManualSubmission();
        ProtosModuleResolver resolver = SharedCore.RESOLVER;
        ProtosPrelude prelude = SharedCore.PRELUDE;
        ProtosActivation activation = prelude.newModuleActivation();

        try (ProtosPolyglotRuntimeHost runtimeHost = ProtosPolyglotRuntimeHost.open();
                ProtosTestLogicalCaseExecutionFacility facility =
                        ProtosTestLogicalCaseExecutionFacility.install(
                                activation,
                                "packageLogicalCaseExecutionAsync",
                                CORE,
                                resolver,
                                List.of(
                                        new ProtosTestToolFileSelectionFacility.CorpusSourceRoot(
                                                "package-corpus", root)),
                                runtimeHost,
                                submission)) {

            ProtosFutureValue firstCase =
                    invoke(
                            facility, prelude, activation, source, List.of("increments"),
                            "increments");
            assertTrue(submission.runNext());
            assertTrue(activation.executionDomain().dispatchOne());
            assertEquals(String.valueOf("1"), com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerDecimalText(assertCompletedObservationValue(firstCase)));

            ProtosFutureValue secondCase =
                    invoke(
                            facility, prelude, activation, source, List.of("increments"),
                            "increments");
            assertTrue(submission.runNext());
            assertTrue(activation.executionDomain().dispatchOne());

            // If the second Case reused the first Case's Process/module state, the
            // counter would already be at 1 and this request would observe 2.
            assertEquals(String.valueOf("1"), com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerDecimalText(assertCompletedObservationValue(secondCase)));
        }
    }

    @Test
    void selectorMismatchIsRejectedWithoutExecutingTheBody(@TempDir Path root)
            throws Exception {
        writeSuite(root, SOURCE_TWO_TESTS);

        ManualSubmission submission = new ManualSubmission();
        ProtosModuleResolver resolver = SharedCore.RESOLVER;
        ProtosPrelude prelude = SharedCore.PRELUDE;
        ProtosActivation activation = prelude.newModuleActivation();

        try (ProtosPolyglotRuntimeHost runtimeHost = ProtosPolyglotRuntimeHost.open();
                ProtosTestLogicalCaseExecutionFacility facility =
                        ProtosTestLogicalCaseExecutionFacility.install(
                                activation,
                                "packageLogicalCaseExecutionAsync",
                                CORE,
                                resolver,
                                List.of(
                                        new ProtosTestToolFileSelectionFacility.CorpusSourceRoot(
                                                "package-corpus", root)),
                                runtimeHost,
                                submission)) {

            ProtosFutureValue future =
                    invoke(
                            facility,
                            prelude,
                            activation,
                            SOURCE_TWO_TESTS,
                            List.of("a", "b"),
                            "doesNotExist");

            assertTrue(submission.runNext());
            assertTrue(activation.executionDomain().dispatchOne());

            assertEquals("rematerialization-error", phaseOf(future));
        }
    }

    @Test
    void signatureMismatchIsRejectedWithoutExecutingTheBody(@TempDir Path root)
            throws Exception {
        writeSuite(root, SOURCE_TWO_TESTS);

        ManualSubmission submission = new ManualSubmission();
        ProtosModuleResolver resolver = SharedCore.RESOLVER;
        ProtosPrelude prelude = SharedCore.PRELUDE;
        ProtosActivation activation = prelude.newModuleActivation();

        try (ProtosPolyglotRuntimeHost runtimeHost = ProtosPolyglotRuntimeHost.open();
                ProtosTestLogicalCaseExecutionFacility facility =
                        ProtosTestLogicalCaseExecutionFacility.install(
                                activation,
                                "packageLogicalCaseExecutionAsync",
                                CORE,
                                resolver,
                                List.of(
                                        new ProtosTestToolFileSelectionFacility.CorpusSourceRoot(
                                                "package-corpus", root)),
                                runtimeHost,
                                submission)) {

            ProtosFutureValue future =
                    invoke(
                            facility,
                            prelude,
                            activation,
                            SOURCE_TWO_TESTS,
                            // declared order is a, b: this expects the opposite order.
                            List.of("b", "a"),
                            "a");

            assertTrue(submission.runNext());
            assertTrue(activation.executionDomain().dispatchOne());

            assertEquals("rematerialization-error", phaseOf(future));
        }
    }

    private static void writeSuite(Path root, String source) throws Exception {
        Files.writeString(root.resolve("suite.protos"), source, StandardCharsets.UTF_8);
    }

    private static String phaseOf(ProtosFutureValue future) {
        ProtosObjectValue completion =
                assertInstanceOf(ProtosObjectValue.class, future.resolvedValue().orElseThrow());
        return assertInstanceOf(
                        ProtosStringValue.class, completion.readLocalSlot("phase").orElseThrow())
                .value();
    }

    private static Object assertCompletedObservationValue(ProtosFutureValue future) {
        ProtosObjectValue completion =
                assertInstanceOf(ProtosObjectValue.class, future.resolvedValue().orElseThrow());
        ProtosStringValue phase =
                assertInstanceOf(
                        ProtosStringValue.class, completion.readLocalSlot("phase").orElseThrow());
        assertEquals("case-execution", phase.value());

        ProtosObjectValue observation =
                assertInstanceOf(
                        ProtosObjectValue.class,
                        completion.readLocalSlot("observation").orElseThrow());
        ProtosStringValue state =
                assertInstanceOf(
                        ProtosStringValue.class, observation.readLocalSlot("state").orElseThrow());
        assertEquals("completed", state.value());

        return observation.readLocalSlot("value").orElseThrow();
    }

    private static ProtosFutureValue invoke(
            ProtosTestLogicalCaseExecutionFacility facility,
            ProtosPrelude prelude,
            ProtosActivation activation,
            String source,
            List<String> signature,
            String selector) {
        Object execution =
                activation
                        .context()
                        .readLocalSlot("packageLogicalCaseExecutionAsync")
                        .orElseThrow();

        ProtosArrayValue sourceAssociation =
                prelude.newFrozenArray(
                        List.of(
                                new ProtosStringValue("package-corpus"),
                                new ProtosStringValue("suite.protos")));

        ProtosArrayValue signatureValue =
                prelude.newFrozenArray(
                        signature.stream()
                                .map(ProtosStringValue::new)
                                .map(value -> (Object) value)
                                .toList());

        return assertInstanceOf(
                ProtosFutureValue.class,
                ProtosInvocation.invoke(
                        execution,
                        List.of(
                                sourceAssociation,
                                signatureValue,
                                new ProtosStringValue(selector)),
                        activation));
    }

    // Class-local holder for the immutable Package-flavored resolver configuration and
    // its bootstrapped caller Core Prelude, initialized exactly once. It holds no
    // activation, runtime host, submission queue, facility, Process, source-root
    // mapping, or Actor module state: every test still creates its own fresh
    // activation via prelude.newModuleActivation() and its own host/facility/queue.
    private static final class SharedCore {
        private static final ProtosModuleResolver RESOLVER = packageResolver();
        private static final ProtosPrelude PRELUDE = create();

        private static ProtosPrelude create() {
            try {
                return new ProtosCoreBootstrap().bootstrap(CORE, RESOLVER);
            } catch (Exception exception) {
                throw new ExceptionInInitializerError(exception);
            }
        }
    }

    private static ProtosModuleResolver packageResolver() {
        ProtosModuleResolver standardLibraryResolver =
                new ProtosStandardLibraryModuleResolver(STANDARD_LIBRARY);
        // Matches ProtosCli's packageLogicalCaseFallbackResolver construction: the
        // ordinary bundled Test Tool resolver is the base (its "self:Discovery"
        // selection machinery is what the suite-native bridge always needs), overlaid
        // with the exact Package Tool modules the real corpora depend on: the
        // RuntimeNames proof-of-bootstrap module and the Package-owned modules, among
        // them ManifestSchemaV1, whose std:toml/TOML import resolves through the
        // Standard Library resolver without an overlay entry.
        ProtosModuleResolver ordinaryTestResolver =
                new ProtosBundledToolModuleResolver(
                        "test", TOOL_ROOT, SHARED_ROOT, standardLibraryResolver);
        return new ProtosExactModuleOverlayResolver(
                Map.ofEntries(
                        Map.entry(
                                "package-runtime-names",
                                new ProtosExactModuleOverlayResolver.ExactModule(
                                        new ProtosModuleKey("tool001-package:runtime-names"),
                                        PACKAGE_TOOL_ROOT.resolve("RuntimeNames.protos"))),
                        Map.entry(
                                "self:ManifestSchemaV1",
                                new ProtosExactModuleOverlayResolver.ExactModule(
                                        new ProtosModuleKey("tool001-package:manifest-schema-v1"),
                                        PACKAGE_TOOL_ROOT.resolve("ManifestSchemaV1.protos"))),
                        Map.entry(
                                "self:ReleaseVersion",
                                new ProtosExactModuleOverlayResolver.ExactModule(
                                        new ProtosModuleKey("tool001-package:release-version"),
                                        PACKAGE_TOOL_ROOT.resolve("ReleaseVersion.protos"))),
                        Map.entry(
                                "self:DependencyConstraint",
                                new ProtosExactModuleOverlayResolver.ExactModule(
                                        new ProtosModuleKey(
                                                "tool001-package:dependency-constraint"),
                                        PACKAGE_TOOL_ROOT.resolve("DependencyConstraint.protos"))),
                        Map.entry(
                                "self:FreshVersionSelection",
                                new ProtosExactModuleOverlayResolver.ExactModule(
                                        new ProtosModuleKey(
                                                "tool001-package:fresh-version-selection"),
                                        PACKAGE_TOOL_ROOT.resolve(
                                                "FreshVersionSelection.protos"))),
                        Map.entry(
                                "self:RetainedVersionSelection",
                                new ProtosExactModuleOverlayResolver.ExactModule(
                                        new ProtosModuleKey(
                                                "tool001-package:retained-version-selection"),
                                        PACKAGE_TOOL_ROOT.resolve(
                                                "RetainedVersionSelection.protos"))),
                        Map.entry(
                                "self:LockSyntax",
                                new ProtosExactModuleOverlayResolver.ExactModule(
                                        new ProtosModuleKey("tool001-package:lock-syntax"),
                                        PACKAGE_TOOL_ROOT.resolve("LockSyntax.protos"))),
                        Map.entry(
                                "self:LockDocument",
                                new ProtosExactModuleOverlayResolver.ExactModule(
                                        new ProtosModuleKey("tool001-package:lock-document"),
                                        PACKAGE_TOOL_ROOT.resolve("LockDocument.protos"))),
                        Map.entry(
                                "self:ResolutionInput",
                                new ProtosExactModuleOverlayResolver.ExactModule(
                                        new ProtosModuleKey("tool001-package:resolution-input"),
                                        PACKAGE_TOOL_ROOT.resolve("ResolutionInput.protos")))),
                ordinaryTestResolver);
    }

    private static final class ManualSubmission
            implements ProtosAsyncExactExecutionFacility.Submission {
        private final ArrayDeque<Job> jobs = new ArrayDeque<>();

        @Override
        public synchronized ProtosAsyncExactExecutionFacility.Submitted submit(Runnable work) {
            Job job = new Job(Objects.requireNonNull(work, "work"));
            jobs.addLast(job);
            return job;
        }

        synchronized int queuedCount() {
            return jobs.size();
        }

        boolean runNext() {
            Job job;

            synchronized (this) {
                job = jobs.pollFirst();
            }

            return job != null && job.runIfAccepted();
        }

        private static final class Job implements ProtosAsyncExactExecutionFacility.Submitted {
            private final Runnable work;
            private boolean started;
            private boolean cancelled;

            private Job(Runnable work) {
                this.work = work;
            }

            @Override
            public synchronized boolean cancelIfNotStarted() {
                if (started || cancelled) {
                    return false;
                }

                cancelled = true;
                return true;
            }

            synchronized boolean runIfAccepted() {
                if (started || cancelled) {
                    return false;
                }

                started = true;
                work.run();
                return true;
            }
        }
    }
}
