/* APL-1.0 licensed work; see LICENSE.TXT. */
package com.guillermomolina.protos.execution;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.guillermomolina.protos.runtime.ProtosActivation;
import com.guillermomolina.protos.runtime.ProtosArrayValue;
import com.guillermomolina.protos.runtime.ProtosFilesystemValue;
import com.guillermomolina.protos.runtime.ProtosIntegerValue;
import com.guillermomolina.protos.runtime.ProtosMapValue;
import com.guillermomolina.protos.runtime.ProtosObjectValue;
import com.guillermomolina.protos.runtime.ProtosPrelude;
import com.guillermomolina.protos.runtime.ProtosProcessRuntime;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class ProtosExternalPackagePlanningPreflightTest {
    private static final Path CORE = Path.of("protos", "lib", "core");
    private static final Path STANDARD_LIBRARY = Path.of("protos", "lib");
    private static final Path TOOL_ROOT = Path.of("protos", "tools", "package");

    private static final String METHOD = "protos-package-tree-v1";
    private static final String ALGORITHM = "sha256";
    private static final String A_HEX =
            "5c2e8af458daf38c3e42ee3a68a928aa5ad2701166a59e6cabfb38645efe6f1a";
    private static final String B_HEX =
            "9f7a40f88c9c231748feb4ed242a4ebe9ec5f06cb732afd4e80706fd160a8ac0";
    private static final String C_HEX =
            "5876918795e50c16907ac40404b99e391fecb4302c02debbf246d4cfa1199222";

    private static final String ROOT_MANIFEST =
            "manifest-version = 1\n"
                    + "\n"
                    + "[package]\n"
                    + "id = \"root\"\n"
                    + "version = \"1.0.0\"\n"
                    + "\n"
                    + "[dependencies.reg]\n"
                    + "authority = \"public\"\n"
                    + "package = \"pkg\"\n"
                    + "version = \"1.0.0\"\n";

    private static final String A_MANIFEST =
            "manifest-version = 1\n"
                    + "\n"
                    + "[package]\n"
                    + "id = \"external-a\"\n"
                    + "version = \"1.0.0\"\n"
                    + "\n"
                    + "[exports]\n"
                    + "PublicA = \"ThingA\"\n"
                    + "\n"
                    + "[dependencies.depgit]\n"
                    + "git = \"https://example.invalid/c.git\"\n"
                    + "rev = \"abc123\"\n"
                    + "\n"
                    + "[dependencies.depreg]\n"
                    + "authority = \"public\"\n"
                    + "package = \"pkg-b\"\n"
                    + "version = \"^2.0.0\"\n";

    private static final String B_MANIFEST =
            "manifest-version = 1\n"
                    + "\n"
                    + "[package]\n"
                    + "id = \"external-b\"\n"
                    + "version = \"2.1.0\"\n"
                    + "\n"
                    + "[exports]\n"
                    + "PublicB = \"ThingB\"\n";

    private static final String C_MANIFEST =
            "manifest-version = 1\n"
                    + "\n"
                    + "[package]\n"
                    + "id = \"external-c\"\n"
                    + "version = \"9.9.9\"\n"
                    + "\n"
                    + "[exports]\n"
                    + "PublicC = \"ThingC\"\n";

    private static final String LOCK =
            "lock-format 1\n"
                    + "resolver-version 1\n"
                    + "resolution-input protos-resolution-input-v1 "
                    + "sha256:6f2526d30fdc07e44c319bdb194eb0e7fafadcd61768eb36fc13917abf2fc2c5\n"
                    + "\n"
                    + "root workspace \"root\"\n"
                    + "registry-node registry \"external-a\" \"1.0.0\" "
                    + "locator \"pkg\" authority \"public\" content "
                    + METHOD + " " + ALGORITHM + ":" + A_HEX + "\n"
                    + "registry-node registry \"external-b\" \"2.1.0\" "
                    + "locator \"pkg-b\" authority \"public\" content "
                    + METHOD + " " + ALGORITHM + ":" + B_HEX + "\n"
                    + "git-node git \"external-c\" \"abc123\" "
                    + "fetch \"https://example.invalid/c.git\" content "
                    + METHOD + " " + ALGORITHM + ":" + C_HEX + "\n"
                    + "dependency workspace \"root\" alias \"reg\" "
                    + "target registry \"external-a\" \"1.0.0\"\n"
                    + "dependency registry \"external-a\" \"1.0.0\" alias \"depgit\" "
                    + "target git \"external-c\" \"abc123\"\n"
                    + "dependency registry \"external-a\" \"1.0.0\" alias \"depreg\" "
                    + "target registry \"external-b\" \"2.1.0\"\n";

    @TempDir Path temporaryRoot;

    @Test
    void sameVerifiedCustodiesPlanAfterOriginalSourcesAreDeletedAndRemainBorrowed()
            throws Exception {
        try (Fixture fixture = createFixture("success")) {
            fixture.deleteOriginalSources();
            AtomicReference<ProtosProcessRuntime> observed = new AtomicReference<>();

            Object rawPlan =
                    ProtosExternalPackagePlanningPreflight.buildRaw(
                            CORE,
                            TOOL_ROOT,
                            fixture.projectRoot,
                            standardLibraryResolver(),
                            fixture.inputs(),
                            observed::set);

            ProtosProcessRuntime process = observed.get();
            assertNotNull(process);
            assertEquals(
                    ProtosProcessRuntime.LifecycleState.TERMINATED,
                    process.lifecycleState());
            assertTrue(process.rootFilesystemForRuntime().isEmpty());

            ProtosObjectValue plan = assertInstanceOf(ProtosObjectValue.class, rawPlan);
            ProtosIntegerValue generation =
                    assertInstanceOf(
                            ProtosIntegerValue.class,
                            plan.readLocalSlot("generation").orElseThrow());
            assertEquals(String.valueOf(2), String.valueOf(Integer.parseInt(com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerDecimalText(generation))));

            ProtosArrayValue packages =
                    assertInstanceOf(
                            ProtosArrayValue.class,
                            plan.readLocalSlot("packages").orElseThrow());
            ProtosArrayValue dependencies =
                    assertInstanceOf(
                            ProtosArrayValue.class,
                            plan.readLocalSlot("dependencies").orElseThrow());
            assertEquals(4, packages.indexedSize());
            assertEquals(3, dependencies.indexedSize());

            assertNoFilesystem(rawPlan, new IdentityHashMap<>());

            ProtosPackageExecutionPlanV2 detached =
                    ProtosPackageExecutionPlanV2Adapter.detach(rawPlan, fixture.projectRoot);
            assertEquals(4, detached.packages().size());
            assertEquals(3, detached.dependencies().size());
            assertEquals(
                    List.<ProtosExactExternalPackageIdentity>of(
                            new ProtosExactExternalPackageIdentity.Registry(
                                    "external-a", "1.0.0", content(A_HEX)),
                            new ProtosExactExternalPackageIdentity.Registry(
                                    "external-b", "2.1.0", content(B_HEX)),
                            new ProtosExactExternalPackageIdentity.Git(
                                    "external-c", "abc123", content(C_HEX))),
                    detached.packages().stream()
                            .filter(ProtosPackageExecutionPlanV2.ExternalPackage.class::isInstance)
                            .map(ProtosPackageExecutionPlanV2.ExternalPackage.class::cast)
                            .map(ProtosPackageExecutionPlanV2.ExternalPackage::identity)
                            .toList());

            assertBorrowedCustodiesStillOpen(
                    fixture.aCustody,
                    fixture.bCustody,
                    fixture.cCustody);

            ProtosExternalPackageResourceScope scope =
                    ProtosExternalPackageResourceScope.reconcile(detached, fixture.inputs());
            try (scope) {
                assertEquals(
                        A_MANIFEST,
                        new String(
                                scope.readResource(
                                        fixture.inputs().get(0).identity(),
                                        ProtosPackageResourceName.parse("protos.toml")),
                                StandardCharsets.UTF_8));
            }
            assertThrows(
                    IllegalStateException.class,
                    () -> fixture.aCustody.readResource(
                            ProtosPackageResourceName.parse("protos.toml")));
        }
    }

    @Test
    void failedPlanningTerminatesProcessWithoutClosingBorrowedCustody()
            throws Exception {
        try (FailureFixture fixture = createFailureFixture()) {
            fixture.deleteOriginalSource();
            AtomicReference<ProtosProcessRuntime> observed = new AtomicReference<>();

            assertThrows(
                    java.io.IOException.class,
                    () ->
                            ProtosExternalPackagePlanningPreflight.buildRaw(
                                    CORE,
                                    TOOL_ROOT,
                                    fixture.projectRoot,
                                    standardLibraryResolver(),
                                    List.of(fixture.input()),
                                    observed::set));

            ProtosProcessRuntime process = observed.get();
            assertNotNull(process);
            assertEquals(
                    ProtosProcessRuntime.LifecycleState.TERMINATED,
                    process.lifecycleState());
            assertTrue(process.rootFilesystemForRuntime().isEmpty());

            assertBorrowedCustodiesStillOpen(fixture.aCustody);
        }
    }

    private FailureFixture createFailureFixture() throws Exception {
        Path base = Files.createDirectories(temporaryRoot.resolve("failure"));
        Path project = Files.createDirectories(base.resolve("project"));
        Files.writeString(project.resolve("protos.toml"), ROOT_MANIFEST, StandardCharsets.UTF_8);
        Files.writeString(project.resolve("protos.lock"), LOCK, StandardCharsets.UTF_8);

        Path a = createExternal(base.resolve("a"), A_MANIFEST);

        assumeSecureConfinement(project);
        assumeSecureConfinement(a);

        ProtosCapturedFilesystemCustody aCustody =
                ProtosPackageContentVerification.captureAndVerify(
                        CORE,
                        TOOL_ROOT,
                        a,
                        standardLibraryResolver(),
                        METHOD,
                        ALGORITHM,
                        A_HEX);

        return new FailureFixture(project, a, aCustody);
    }

    private Fixture createFixture(String name) throws Exception {
        Path base = Files.createDirectories(temporaryRoot.resolve(name));
        Path project = Files.createDirectories(base.resolve("project"));
        Files.writeString(project.resolve("protos.toml"), ROOT_MANIFEST, StandardCharsets.UTF_8);
        Files.writeString(project.resolve("protos.lock"), LOCK, StandardCharsets.UTF_8);

        Path a = createExternal(base.resolve("a"), A_MANIFEST);
        Path b = createExternal(base.resolve("b"), B_MANIFEST);
        Path c = createExternal(base.resolve("c"), C_MANIFEST);

        assumeSecureConfinement(project);
        assumeSecureConfinement(a);
        assumeSecureConfinement(b);
        assumeSecureConfinement(c);

        ProtosCapturedFilesystemCustody aCustody = null;
        ProtosCapturedFilesystemCustody bCustody = null;
        ProtosCapturedFilesystemCustody cCustody = null;
        try {
            aCustody =
                    ProtosPackageContentVerification.captureAndVerify(
                            CORE,
                            TOOL_ROOT,
                            a,
                            standardLibraryResolver(),
                            METHOD,
                            ALGORITHM,
                            A_HEX);
            bCustody =
                    ProtosPackageContentVerification.captureAndVerify(
                            CORE,
                            TOOL_ROOT,
                            b,
                            standardLibraryResolver(),
                            METHOD,
                            ALGORITHM,
                            B_HEX);
            cCustody =
                    ProtosPackageContentVerification.captureAndVerify(
                            CORE,
                            TOOL_ROOT,
                            c,
                            standardLibraryResolver(),
                            METHOD,
                            ALGORITHM,
                            C_HEX);
            return new Fixture(project, a, b, c, aCustody, bCustody, cCustody);
        } catch (Exception failure) {
            if (cCustody != null) cCustody.close();
            if (bCustody != null) bCustody.close();
            if (aCustody != null) aCustody.close();
            throw failure;
        }
    }

    private static Path createExternal(Path root, String manifest) throws Exception {
        Files.createDirectories(root);
        Files.writeString(root.resolve("protos.toml"), manifest, StandardCharsets.UTF_8);
        return root;
    }

    private static void assumeSecureConfinement(Path root) throws Exception {
        try (ProtosNioReadOnlyTreeFilesystemBackend backend =
                new ProtosNioReadOnlyTreeFilesystemBackend(root)) {
            assumeTrue(
                    backend.secureConfinementAvailable(),
                    "host provider has no SecureDirectoryStream");
        }
    }

    private static ProtosPackageContentIdentity content(String hex) {
        return new ProtosPackageContentIdentity(METHOD, ALGORITHM, hex);
    }

    private static ProtosStandardLibraryModuleResolver standardLibraryResolver() {
        return new ProtosStandardLibraryModuleResolver(STANDARD_LIBRARY);
    }

    private static void assertBorrowedCustodiesStillOpen(
            ProtosCapturedFilesystemCustody... custodies)
            throws Exception {
        ProtosActivation activation = CorePrelude.INSTANCE.newModuleActivation();
        for (ProtosCapturedFilesystemCustody custody : custodies) {
            assertDoesNotThrow(() -> custody.materialize(activation));
        }
    }

    private static final class CorePrelude {
        private static final ProtosPrelude INSTANCE = create();

        private static ProtosPrelude create() {
            try {
                return new ProtosCoreBootstrap().bootstrap(CORE);
            } catch (Exception exception) {
                throw new ExceptionInInitializerError(exception);
            }
        }
    }

    private static void assertNoFilesystem(
            Object value,
            IdentityHashMap<Object, Boolean> seen) {
        if (value instanceof ProtosFilesystemValue) {
            fail("PackageExecutionPlan retained a Filesystem capability");
        }
        if (value == null || seen.put(value, Boolean.TRUE) != null) {
            return;
        }

        if (value instanceof ProtosArrayValue array) {
            for (Object element : array.indexedSnapshot()) {
                assertNoFilesystem(element, seen);
            }
        }

        if (value instanceof ProtosMapValue map) {
            for (ProtosMapValue.Entry entry : map.keyedSnapshot()) {
                assertNoFilesystem(entry.key(), seen);
                assertNoFilesystem(entry.value(), seen);
            }
        }

        if (value instanceof ProtosObjectValue object) {
            for (Object slotValue : object.localSlotsSnapshot().values()) {
                assertNoFilesystem(slotValue, seen);
            }
        }
    }

    private static final class FailureFixture implements AutoCloseable {
        private final Path projectRoot;
        private final Path aRoot;
        private final ProtosCapturedFilesystemCustody aCustody;

        private FailureFixture(
                Path projectRoot,
                Path aRoot,
                ProtosCapturedFilesystemCustody aCustody) {
            this.projectRoot = projectRoot;
            this.aRoot = aRoot;
            this.aCustody = aCustody;
        }

        private ProtosExternalPackagePlanningPreflight.VerifiedExternalPackage input() {
            return ProtosExternalPackagePlanningPreflight.VerifiedExternalPackage.registry(
                    "external-a", "1.0.0", METHOD, ALGORITHM, A_HEX, aCustody);
        }

        private void deleteOriginalSource() throws Exception {
            Files.delete(aRoot.resolve("protos.toml"));
            Files.delete(aRoot);
        }

        @Override
        public void close() {
            aCustody.close();
        }
    }

    private static final class Fixture implements AutoCloseable {
        private final Path projectRoot;
        private final Path aRoot;
        private final Path bRoot;
        private final Path cRoot;
        private final ProtosCapturedFilesystemCustody aCustody;
        private final ProtosCapturedFilesystemCustody bCustody;
        private final ProtosCapturedFilesystemCustody cCustody;

        private Fixture(
                Path projectRoot,
                Path aRoot,
                Path bRoot,
                Path cRoot,
                ProtosCapturedFilesystemCustody aCustody,
                ProtosCapturedFilesystemCustody bCustody,
                ProtosCapturedFilesystemCustody cCustody) {
            this.projectRoot = projectRoot;
            this.aRoot = aRoot;
            this.bRoot = bRoot;
            this.cRoot = cRoot;
            this.aCustody = aCustody;
            this.bCustody = bCustody;
            this.cCustody = cCustody;
        }

        private List<ProtosExternalPackagePlanningPreflight.VerifiedExternalPackage> inputs() {
            return List.of(
                    ProtosExternalPackagePlanningPreflight.VerifiedExternalPackage.registry(
                            "external-a", "1.0.0", METHOD, ALGORITHM, A_HEX, aCustody),
                    ProtosExternalPackagePlanningPreflight.VerifiedExternalPackage.registry(
                            "external-b", "2.1.0", METHOD, ALGORITHM, B_HEX, bCustody),
                    ProtosExternalPackagePlanningPreflight.VerifiedExternalPackage.git(
                            "external-c", "abc123", METHOD, ALGORITHM, C_HEX, cCustody));
        }

        private void deleteOriginalSources() throws Exception {
            Files.delete(aRoot.resolve("protos.toml"));
            Files.delete(aRoot);
            Files.delete(bRoot.resolve("protos.toml"));
            Files.delete(bRoot);
            Files.delete(cRoot.resolve("protos.toml"));
            Files.delete(cRoot);
        }

        @Override
        public void close() {
            cCustody.close();
            bCustody.close();
            aCustody.close();
        }
    }
}
