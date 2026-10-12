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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.guillermomolina.protos.runtime.ProtosIntegerValue;
import com.guillermomolina.protos.runtime.ProtosModuleKey;
import com.guillermomolina.protos.runtime.ProtosObjectValue;
import com.guillermomolina.protos.runtime.ProtosPrelude;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class ProtosPackageExecutionPlanV2ModuleResolverTest {
    private static final Path CORE = Path.of("protos", "lib", "core");
    private static final String METHOD = "protos-package-tree-v1";
    private static final String ALGORITHM = "sha256";
    private static final String A_HEX = "aa".repeat(32);
    private static final String B_HEX = "bb".repeat(32);
    private static final String C_HEX = "cc".repeat(32);
    private static final String D_HEX = "dd".repeat(32);
    private static final String ROOT = "root-pkg";

    @TempDir Path temporaryRoot;

    // ---- Real module-runtime composition ----

    @Test
    void mixedGraphExecutesThroughExactWorkspaceAndExternalRouting() throws Exception {
        try (Graph graph = new Graph()) {
            ProtosModuleKey entry = graph.resolver.entryModule("Main");
            assertEquals(ProtosWorkspacePackageModuleKey.encode(ROOT, "Main"), entry);

            ProtosObjectValue module = execute(graph.resolver, entry);
            assertIntegerSlot(module, "local", 7);
            assertIntegerSlot(module, "a", 11);
            assertIntegerSlot(module, "aDep", 33);
            assertIntegerSlot(module, "b", 22);
        }
    }

    // ---- Routing ----

    @Test
    void workspaceSelfAndDependencyRoutingYieldExactCanonicalKeys() throws Exception {
        try (Graph graph = new Graph()) {
            ProtosModuleKey entry = graph.resolver.entryModule("Main");
            assertEquals(
                    ProtosWorkspacePackageModuleKey.encode(ROOT, "Local"),
                    graph.resolver.resolve("self:Local", Optional.of(entry)));

            ProtosModuleKey a = graph.resolver.resolve("dep:a/Public", Optional.of(entry));
            ProtosModuleKey b = graph.resolver.resolve("dep:b/Public", Optional.of(entry));
            assertEquals(ProtosExternalPackageModuleKey.encode(graph.aIdentity, "Internal/Thing"), a);
            assertEquals(ProtosExternalPackageModuleKey.encode(graph.bIdentity, "Internal/Thing"), b);
            assertNotEquals(a, b);
            assertEquals(a, graph.resolver.resolve("dep:sameA/Public", Optional.of(entry)));
        }
    }

    @Test
    void externalSelfAndExternalDependencyKeepExactIdentity() throws Exception {
        try (Graph graph = new Graph()) {
            ProtosModuleKey thing =
                    ProtosExternalPackageModuleKey.encode(graph.aIdentity, "Internal/Thing");
            assertEquals(
                    ProtosExternalPackageModuleKey.encode(graph.aIdentity, "Internal/Helper"),
                    graph.resolver.resolve("self:Internal/Helper", Optional.of(thing)));
            assertEquals(
                    ProtosExternalPackageModuleKey.encode(graph.cIdentity, "Api"),
                    graph.resolver.resolve("dep:helper/Api", Optional.of(thing)));

            // Edges belong to their exact declaring node: B declares no `helper` alias.
            ProtosModuleKey bThing =
                    ProtosExternalPackageModuleKey.encode(graph.bIdentity, "Internal/Thing");
            assertThrows(
                    IOException.class,
                    () -> graph.resolver.resolve("dep:helper/Api", Optional.of(bThing)));
        }
    }

    @Test
    void registryGitAndSameContentIdentitiesNeverCollapse() throws Exception {
        ProtosCapturedFilesystemCustody registry = capture("reg", Map.of("Main.protos", "value: 1"));
        ProtosCapturedFilesystemCustody git = capture("git", Map.of("Main.protos", "value: 2"));
        ProtosCapturedFilesystemCustody other = capture("other", Map.of("Main.protos", "value: 3"));
        Path project = workspace(Map.of("Main.protos", "value: 0"));

        ProtosPackageExecutionPlanV2.ExternalPackage registryNode =
                registryNode("same", 1, A_HEX, Map.of("Public", "Main"));
        ProtosPackageExecutionPlanV2.ExternalPackage gitNode =
                gitNode("same", "1.0.0", A_HEX, Map.of("Public", "Main"));
        ProtosPackageExecutionPlanV2.ExternalPackage otherNode =
                registryNode("other", 1, A_HEX, Map.of("Public", "Main"));
        ProtosPackageExecutionPlanV2 plan =
                plan(
                        List.of(registryNode, gitNode, otherNode),
                        List.of(
                                edge(rootRef(), "r", registryNode.ref()),
                                edge(rootRef(), "g", gitNode.ref()),
                                edge(rootRef(), "o", otherNode.ref())));
        try (ProtosExternalPackageResourceScope scope =
                ProtosExternalPackageResourceScope.reconcile(
                        plan,
                        List.of(
                                verifiedRegistry("same", "1.0.0", A_HEX, registry),
                                verifiedGit("same", "1.0.0", A_HEX, git),
                                verifiedRegistry("other", "1.0.0", A_HEX, other)))) {
            ProtosPackageExecutionPlanV2ModuleResolver resolver =
                    new ProtosPackageExecutionPlanV2ModuleResolver(
                            project, plan, scope, ProtosModuleResolver.rejecting());
            Optional<ProtosModuleKey> entry = Optional.of(resolver.entryModule("Main"));

            ProtosModuleKey r = resolver.resolve("dep:r/Public", entry);
            ProtosModuleKey g = resolver.resolve("dep:g/Public", entry);
            ProtosModuleKey o = resolver.resolve("dep:o/Public", entry);
            assertNotEquals(r, g);
            assertNotEquals(r, o);
            assertNotEquals(g, o);
            assertEquals("value: 1", resolver.loadSource(r).characters());
            assertEquals("value: 2", resolver.loadSource(g).characters());
            assertEquals("value: 3", resolver.loadSource(o).characters());
        }
    }

    @Test
    void malformedAbsentAndForeignRoutingFailsClosed() throws Exception {
        try (Graph graph = new Graph()) {
            Optional<ProtosModuleKey> entry = Optional.of(graph.resolver.entryModule("Main"));
            ProtosPackageExecutionPlanV2ModuleResolver resolver = graph.resolver;

            for (String specifier :
                    List.of(
                            "Main",
                            "pkg:a/Public",
                            "dep:a",
                            "dep:/Public",
                            "dep:a/",
                            "dep:1a/Public",
                            "dep:a/1x",
                            "dep:missing/Public",
                            "dep:a/Absent",
                            "dep:a/Broken",
                            "self:Missing",
                            "self:bad name")) {
                assertThrows(IOException.class, () -> resolver.resolve(specifier, entry), specifier);
            }
            assertThrows(IOException.class, () -> resolver.resolve("self:Local", Optional.empty()));
            assertThrows(IOException.class, () -> resolver.resolve("dep:a/Public", Optional.empty()));
            assertThrows(
                    IOException.class,
                    () -> resolver.resolve(
                            "self:Local", Optional.of(new ProtosModuleKey("std:collections/Array"))));
            assertThrows(
                    IOException.class,
                    () -> resolver.resolve(
                            "self:Local",
                            Optional.of(ProtosWorkspacePackageModuleKey.encode("outside", "Main"))));
            assertThrows(
                    IOException.class,
                    () -> resolver.resolve(
                            "self:Internal/Helper",
                            Optional.of(ProtosExternalPackageModuleKey.encode(
                                    registry("unknown", "1.0.0", A_HEX), "Main"))));
            ProtosModuleKey wrongContent =
                    ProtosExternalPackageModuleKey.encode(
                            registry("pkg", "1.0.0", D_HEX), "Internal/Thing");
            assertThrows(
                    IOException.class,
                    () -> resolver.resolve("self:Internal/Helper", Optional.of(wrongContent)));
            assertThrows(IOException.class, () -> resolver.loadSource(wrongContent));
            assertThrows(
                    IOException.class,
                    () -> resolver.loadSource(new ProtosModuleKey("pkg-external:v1:registry:bad")));
            assertThrows(
                    IOException.class,
                    () -> resolver.loadSource(new ProtosModuleKey("file:///tmp/Main.protos")));
        }
    }

    // ---- Source loading ----

    @Test
    void externalSourceIsLoadedLazilyFromCapturedCustodyWithoutPhysicalPath() throws Exception {
        try (Graph graph = new Graph()) {
            Files.writeString(
                    graph.aSource.resolve("Internal/Thing.protos"), "value: 999",
                    StandardCharsets.UTF_8);
            Files.delete(graph.aSource.resolve("Internal/Helper.protos"));

            ProtosModuleKey nested =
                    ProtosExternalPackageModuleKey.encode(graph.aIdentity, "Internal/Helper");
            ProtosModuleSource source = graph.resolver.loadSource(nested);
            assertEquals(nested, source.key());
            assertEquals("value: 11", source.characters());
            assertTrue(source.physicalPath().isEmpty());

            ProtosModuleKey top = ProtosExternalPackageModuleKey.encode(graph.cIdentity, "Api");
            assertEquals("value: 33", graph.resolver.loadSource(top).characters());
        }
    }

    @Test
    void missingDirectoryAndMalformedExternalSourcesFailClosed() throws Exception {
        try (Graph graph = new Graph()) {
            for (String module : List.of("Missing", "Dir", "Bad")) {
                ProtosModuleKey key = ProtosExternalPackageModuleKey.encode(graph.aIdentity, module);
                assertThrows(IOException.class, () -> graph.resolver.loadSource(key), module);
            }
        }
    }

    @Test
    void externalLoadingFailsAfterTheOwningScopeClosesAndResolverNeverClosesIt() throws Exception {
        try (Graph graph = new Graph()) {
            ProtosModuleKey key = ProtosExternalPackageModuleKey.encode(graph.cIdentity, "Api");
            assertEquals("value: 33", graph.resolver.loadSource(key).characters());
            assertTrue(graph.scope.contains(graph.cIdentity));

            graph.scope.close();
            assertThrows(IOException.class, () -> graph.resolver.loadSource(key));
            assertEquals(
                    "value: 7",
                    graph.resolver.loadSource(ProtosWorkspacePackageModuleKey.encode(ROOT, "Local"))
                            .characters());
        }
    }

    @Test
    void workspaceSourceKeepsPhysicalWorkspaceRules() throws Exception {
        try (Graph graph = new Graph()) {
            ProtosModuleKey local = ProtosWorkspacePackageModuleKey.encode(ROOT, "Local");
            ProtosModuleSource source = graph.resolver.loadSource(local);
            assertEquals(local, source.key());
            assertEquals("value: 7", source.characters());
            assertEquals(
                    graph.project.toRealPath().resolve("Local.protos"),
                    source.physicalPath().orElseThrow());
            assertThrows(IOException.class, () -> graph.resolver.entryModule("Missing"));
            assertThrows(IOException.class, () -> graph.resolver.entryModule("local"));
            assertThrows(
                    IOException.class,
                    () -> graph.resolver.loadSource(
                            ProtosWorkspacePackageModuleKey.encode(ROOT, "Missing")));
        }
    }

    // ---- Standard library ----

    @Test
    void standardLibraryResolutionAndLoadingDelegateAndForeignKeysAreRejected() throws Exception {
        ProtosModuleKey std = new ProtosModuleKey("std:collections/Array");
        ProtosModuleResolver standard =
                new ProtosModuleResolver() {
                    @Override
                    public ProtosModuleKey resolve(
                            String exactSpecifier, Optional<ProtosModuleKey> importingModule) {
                        return exactSpecifier.equals("std:foreign")
                                ? new ProtosModuleKey("pkg-workspace:v1:foreign")
                                : std;
                    }

                    @Override
                    public ProtosModuleSource loadSource(ProtosModuleKey key) {
                        return ProtosModuleSource.fromCharacters(key, "value: 5");
                    }
                };
        Path project = workspace(Map.of("Main.protos", "value: 0"));
        ProtosPackageExecutionPlanV2 plan = plan(List.of(), List.of());
        try (ProtosExternalPackageResourceScope scope =
                ProtosExternalPackageResourceScope.reconcile(plan, List.of())) {
            ProtosPackageExecutionPlanV2ModuleResolver resolver =
                    new ProtosPackageExecutionPlanV2ModuleResolver(project, plan, scope, standard);
            Optional<ProtosModuleKey> entry = Optional.of(resolver.entryModule("Main"));
            assertEquals(std, resolver.resolve("std:collections/Array", entry));
            assertEquals("value: 5", resolver.loadSource(std).characters());
            assertThrows(IOException.class, () -> resolver.resolve("std:foreign", entry));
        }
    }

    // ---- Fixtures ----

    /**
     * Workspace root with aliases {@code a} and {@code sameA} to registry pkg 1.0.0/A and {@code b}
     * to registry pkg 2.0.0/B; pkg 1.0.0 has alias {@code helper} to Git helper abc123/C.
     */
    private final class Graph implements AutoCloseable {
        final Path project;
        final Path aSource;
        final ProtosExactExternalPackageIdentity aIdentity = registry("pkg", "1.0.0", A_HEX);
        final ProtosExactExternalPackageIdentity bIdentity = registry("pkg", "2.0.0", B_HEX);
        final ProtosExactExternalPackageIdentity cIdentity =
                new ProtosExactExternalPackageIdentity.Git("helper", "abc123", content(C_HEX));
        final ProtosExternalPackageResourceScope scope;
        final ProtosPackageExecutionPlanV2ModuleResolver resolver;

        Graph() throws Exception {
            project =
                    workspace(
                            Map.of(
                                    "Main.protos",
                                    String.join(
                                            "\n",
                                            "L: import(\"self:Local\")",
                                            "A: import(\"dep:a/Public\")",
                                            "B: import(\"dep:b/Public\")",
                                            "local: L.value",
                                            "a: A.value",
                                            "aDep: A.depValue",
                                            "b: B.value",
                                            ""),
                                    "Local.protos",
                                    "value: 7"));
            ProtosCapturedFilesystemCustody a =
                    capture(
                            "a",
                            Map.of(
                                    "Internal/Thing.protos",
                                    String.join(
                                            "\n",
                                            "H: import(\"self:Internal/Helper\")",
                                            "D: import(\"dep:helper/Api\")",
                                            "value: H.value",
                                            "depValue: D.value",
                                            ""),
                                    "Internal/Helper.protos",
                                    "value: 11"),
                            source -> {
                                Files.createDirectories(source.resolve("Dir.protos"));
                                Files.write(
                                        source.resolve("Bad.protos"),
                                        new byte[] {'v', (byte) 0xc3, (byte) 0x28});
                            });
            aSource = temporaryRoot.resolve("a");
            ProtosCapturedFilesystemCustody b =
                    capture("b", Map.of("Internal/Thing.protos", "value: 22"));
            ProtosCapturedFilesystemCustody c = capture("c", Map.of("Api.protos", "value: 33"));

            ProtosPackageExecutionPlanV2.ExternalPackage aNode =
                    registryNode(
                            "pkg", 1, A_HEX,
                            Map.of("Public", "Internal/Thing", "Broken", "bad name"));
            ProtosPackageExecutionPlanV2.ExternalPackage bNode =
                    registryNode("pkg", 2, B_HEX, Map.of("Public", "Internal/Thing"));
            ProtosPackageExecutionPlanV2.ExternalPackage cNode =
                    gitNode("helper", "abc123", C_HEX, Map.of("Api", "Api"));
            ProtosPackageExecutionPlanV2 plan =
                    plan(
                            List.of(aNode, bNode, cNode),
                            List.of(
                                    edge(rootRef(), "a", aNode.ref()),
                                    edge(rootRef(), "sameA", aNode.ref()),
                                    edge(rootRef(), "b", bNode.ref()),
                                    edge(aNode.ref(), "helper", cNode.ref())));
            scope =
                    ProtosExternalPackageResourceScope.reconcile(
                            plan,
                            List.of(
                                    verifiedRegistry("pkg", "1.0.0", A_HEX, a),
                                    verifiedRegistry("pkg", "2.0.0", B_HEX, b),
                                    verifiedGit("helper", "abc123", C_HEX, c)));
            resolver =
                    new ProtosPackageExecutionPlanV2ModuleResolver(
                            project, plan, scope, ProtosModuleResolver.rejecting());
        }

        @Override
        public void close() {
            scope.close();
        }
    }

    @FunctionalInterface
    private interface TreeCustomizer {
        void customize(Path source) throws IOException;
    }

    private Path workspace(Map<String, String> files) throws IOException {
        Path project = temporaryRoot.resolve("project");
        writeTree(project, files);
        return project;
    }

    private ProtosCapturedFilesystemCustody capture(String name, Map<String, String> files)
            throws Exception {
        return capture(name, files, source -> {});
    }

    private ProtosCapturedFilesystemCustody capture(
            String name, Map<String, String> files, TreeCustomizer customizer) throws Exception {
        Path source = temporaryRoot.resolve(name);
        writeTree(source, files);
        customizer.customize(source);
        assumeSecureConfinement(source);
        return ProtosCapturedFilesystemCustody.captureSelectedRoot(source);
    }

    @Test
    void releaseVersionComponentsAreExactCanonicalDecimalsOfAnyMagnitude() {
        String huge = BigInteger.ONE.shiftLeft(64).add(BigInteger.valueOf(5L)).toString();
        String beyondLong = BigInteger.valueOf(Long.MAX_VALUE).add(BigInteger.ONE).toString();
        var numeric = new ProtosPackageExecutionPlanV2.PrereleaseIdentifier(true, huge);
        var version = new ProtosPackageExecutionPlanV2.ReleaseVersion(
                huge, beyondLong, "0", List.of(numeric), huge + "." + beyondLong + ".0-" + huge);
        var same = new ProtosPackageExecutionPlanV2.ReleaseVersion(
                new BigInteger(huge).toString(), beyondLong, "0",
                List.of(new ProtosPackageExecutionPlanV2.PrereleaseIdentifier(true, huge)),
                huge + "." + beyondLong + ".0-" + huge);
        assertEquals(version, same);
        assertEquals(version.hashCode(), same.hashCode());
        assertEquals(huge, version.major());
        assertEquals(new BigInteger(beyondLong), new BigInteger(version.minor()));

        var ref = new ProtosPackageExecutionPlanV2.RegistryRef("pkg", version);
        assertEquals(ref, new ProtosPackageExecutionPlanV2.RegistryRef("pkg", same));
        assertNotEquals(ref, new ProtosPackageExecutionPlanV2.RegistryRef("other", same));
        // Differing in one unit beyond signed-64 is a different version, never a truncated alias.
        String hugePlusOne = new BigInteger(huge).add(BigInteger.ONE).toString();
        assertNotEquals(ref, new ProtosPackageExecutionPlanV2.RegistryRef("pkg",
                new ProtosPackageExecutionPlanV2.ReleaseVersion(
                        hugePlusOne, beyondLong, "0", List.of(numeric),
                        hugePlusOne + "." + beyondLong + ".0-" + huge)));
        assertNotEquals(
                new ProtosPackageExecutionPlanV2.ReleaseVersion("10", "0", "0", List.of(), "10.0.0"),
                new ProtosPackageExecutionPlanV2.ReleaseVersion("2", "0", "0", List.of(), "2.0.0"));

        for (String malformed : new String[] {"", "01", "00", "-1", "+1", "1.0", " 1", "1e3", "١"}) {
            assertThrows(IllegalArgumentException.class,
                    () -> new ProtosPackageExecutionPlanV2.ReleaseVersion(
                            malformed, "0", "0", List.of(), malformed + ".0.0"), malformed);
            assertThrows(IllegalArgumentException.class,
                    () -> new ProtosPackageExecutionPlanV2.PrereleaseIdentifier(true, malformed),
                    malformed);
        }
        assertThrows(NullPointerException.class,
                () -> new ProtosPackageExecutionPlanV2.ReleaseVersion(
                        null, "0", "0", List.of(), "0.0.0"));
        assertEquals("rc", new ProtosPackageExecutionPlanV2.PrereleaseIdentifier(false, "rc").text());
        assertEquals("0", new ProtosPackageExecutionPlanV2.PrereleaseIdentifier(true, "0").text());
    }

    private static void writeTree(Path root, Map<String, String> files) throws IOException {
        Files.createDirectories(root);
        for (Map.Entry<String, String> file : files.entrySet()) {
            Path target = root.resolve(file.getKey());
            Files.createDirectories(target.getParent());
            Files.writeString(target, file.getValue(), StandardCharsets.UTF_8);
        }
    }

    private static void assumeSecureConfinement(Path root) throws Exception {
        try (ProtosNioReadOnlyTreeFilesystemBackend backend =
                new ProtosNioReadOnlyTreeFilesystemBackend(root)) {
            assumeTrue(
                    backend.secureConfinementAvailable(),
                    "host provider has no SecureDirectoryStream");
        }
    }

    private static ProtosObjectValue execute(
            ProtosPackageExecutionPlanV2ModuleResolver resolver, ProtosModuleKey key)
            throws Exception {
        ProtosPrelude prelude = new ProtosCoreBootstrap().bootstrap(CORE, resolver);
        ProtosModuleRuntime runtime = new ProtosModuleRuntime(resolver);
        return runtime.loadCanonicalModule(key, prelude.newModuleActivation());
    }

    private static void assertIntegerSlot(
            ProtosObjectValue module, String slotName, long expected) {
        ProtosIntegerValue value =
                (ProtosIntegerValue) module.readLocalSlot(slotName).orElseThrow();
        assertEquals(Long.toString(expected), com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerDecimalText(value));
    }

    private static ProtosPackageContentIdentity content(String hex) {
        return new ProtosPackageContentIdentity(METHOD, ALGORITHM, hex);
    }

    private static ProtosExactExternalPackageIdentity registry(
            String packageId, String version, String hex) {
        return new ProtosExactExternalPackageIdentity.Registry(packageId, version, content(hex));
    }

    private static ProtosExternalPackagePlanningPreflight.VerifiedExternalPackage verifiedRegistry(
            String packageId, String version, String hex, ProtosCapturedFilesystemCustody custody) {
        return ProtosExternalPackagePlanningPreflight.VerifiedExternalPackage.registry(
                packageId, version, METHOD, ALGORITHM, hex, custody);
    }

    private static ProtosExternalPackagePlanningPreflight.VerifiedExternalPackage verifiedGit(
            String packageId,
            String revision,
            String hex,
            ProtosCapturedFilesystemCustody custody) {
        return ProtosExternalPackagePlanningPreflight.VerifiedExternalPackage.git(
                packageId, revision, METHOD, ALGORITHM, hex, custody);
    }

    private static ProtosPackageExecutionPlanV2.ExternalPackage registryNode(
            String packageId, int major, String hex, Map<String, String> exports) {
        return new ProtosPackageExecutionPlanV2.ExternalPackage(
                new ProtosPackageExecutionPlanV2.RegistryRef(
                        packageId,
                        new ProtosPackageExecutionPlanV2.ReleaseVersion(
                                String.valueOf(major),
                                "0",
                                "0",
                                List.of(),
                                major + ".0.0")),
                content(hex),
                exports);
    }

    private static ProtosPackageExecutionPlanV2.ExternalPackage gitNode(
            String packageId, String revision, String hex, Map<String, String> exports) {
        return new ProtosPackageExecutionPlanV2.ExternalPackage(
                new ProtosPackageExecutionPlanV2.GitRef(packageId, revision),
                content(hex),
                exports);
    }

    private static ProtosPackageExecutionPlanV2.WorkspaceRef rootRef() {
        return new ProtosPackageExecutionPlanV2.WorkspaceRef(ROOT);
    }

    private static ProtosPackageExecutionPlanV2.DependencyEdge edge(
            ProtosPackageExecutionPlanV2.NodeRef declaring,
            String alias,
            ProtosPackageExecutionPlanV2.NodeRef target) {
        return new ProtosPackageExecutionPlanV2.DependencyEdge(declaring, alias, target);
    }

    private static ProtosPackageExecutionPlanV2 plan(
            List<ProtosPackageExecutionPlanV2.ExternalPackage> externals,
            List<ProtosPackageExecutionPlanV2.DependencyEdge> edges) {
        List<ProtosPackageExecutionPlanV2.PackageNode> nodes = new ArrayList<>();
        nodes.add(new ProtosPackageExecutionPlanV2.WorkspacePackage(rootRef(), "", Map.of()));
        nodes.addAll(externals);
        return new ProtosPackageExecutionPlanV2(2, rootRef(), nodes, edges);
    }
}
