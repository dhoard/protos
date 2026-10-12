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
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.guillermomolina.protos.runtime.ProtosIntegerValue;
import com.guillermomolina.protos.runtime.ProtosModuleKey;
import com.guillermomolina.protos.runtime.ProtosObjectValue;
import com.guillermomolina.protos.runtime.ProtosPrelude;
import com.guillermomolina.protos.runtime.ProtosSignalException;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class ProtosWorkspacePackageDependencyRoutingTest {
    private static final Path CORE = Path.of("protos", "lib", "core");
    private static final Path PROJECT =
            Path.of("src", "test", "resources", "workspace-package-dep");

    @Test
    void realProtosDepImportUsesTargetExportAndTargetSelfContext() throws Exception {
        ProtosWorkspacePackageModuleResolver resolver = resolver(plan());
        ProtosModuleKey rootEntry = resolver.entryModule("Main");

        ProtosModuleKey target =
                ProtosWorkspacePackageModuleKey.encode("member-pkg", "Public/Entry");
        assertEquals(
                target,
                resolver.resolve("dep:member/Api", Optional.of(rootEntry)));
        assertEquals(
                target,
                resolver.resolve("dep:alternate/Facade", Optional.of(rootEntry)));

        ProtosObjectValue module = execute(resolver, rootEntry);
        assertIntegerSlot(module, "result", 88);
    }

    @Test
    void physicalTargetModulesCannotBypassTheTargetExportMap() throws Exception {
        ProtosWorkspacePackageModuleResolver resolver = resolver(plan());
        ProtosModuleKey rootEntry = resolver.entryModule("Main");

        assertThrows(
                IOException.class,
                () -> resolver.resolve("dep:member/Hidden", Optional.of(rootEntry)));
        assertThrows(
                IOException.class,
                () -> resolver.resolve("dep:member/Public/Entry", Optional.of(rootEntry)));
        assertThrows(
                IOException.class,
                () -> resolver.resolve("dep:member/api", Optional.of(rootEntry)));
        assertThrows(
                IOException.class,
                () -> resolver.resolve("dep:Member/Api", Optional.of(rootEntry)));

        ProtosModuleKey badEntry = resolver.entryModule("BadMain");
        assertThrows(ProtosSignalException.class, () -> execute(resolver, badEntry));
    }

    @Test
    void dependencyEdgesAreOwnedByTheImportingPackageAndMalformedRoutesFailClosed()
            throws Exception {
        ProtosWorkspacePackageModuleResolver resolver = resolver(plan());
        ProtosModuleKey rootEntry = resolver.entryModule("Main");
        ProtosModuleKey otherMain =
                ProtosWorkspacePackageModuleKey.encode("other-pkg", "Main");

        assertThrows(
                IOException.class,
                () -> resolver.resolve("dep:member/Api", Optional.of(otherMain)));
        assertThrows(
                ProtosSignalException.class,
                () -> execute(resolver, otherMain));
        assertThrows(
                IOException.class,
                () -> resolver.resolve("dep:member", Optional.of(rootEntry)));
        assertThrows(
                IOException.class,
                () -> resolver.resolve("dep:/Api", Optional.of(rootEntry)));
        assertThrows(
                IOException.class,
                () -> resolver.resolve("dep:member/", Optional.of(rootEntry)));
        assertThrows(
                IOException.class,
                () -> resolver.resolve("dep:member/../Hidden", Optional.of(rootEntry)));
        assertThrows(
                IOException.class,
                () -> resolver.resolve("dep:member/Api", Optional.empty()));
    }

    @Test
    void directHostDtoStillRejectsDuplicateEdgesAndUnknownTargets() throws Exception {
        ProtosPackageExecutionPlan.PackageNode root = rootNode();
        ProtosPackageExecutionPlan.PackageNode member = memberNode();
        ProtosPackageExecutionPlan.PackageNode other = otherNode();

        ProtosPackageExecutionPlan.DependencyEdge first = edge("root-pkg", "member", "member-pkg");
        ProtosPackageExecutionPlan.DependencyEdge duplicate =
                edge("root-pkg", "member", "member-pkg");
        assertThrows(
                IOException.class,
                () ->
                        resolver(
                                new ProtosPackageExecutionPlan(
                                        1,
                                        root.ref(),
                                        List.of(root, member, other),
                                        List.of(first, duplicate))));

        ProtosPackageExecutionPlan.DependencyEdge unknown =
                edge("root-pkg", "missing", "missing-pkg");
        assertThrows(
                IOException.class,
                () ->
                        resolver(
                                new ProtosPackageExecutionPlan(
                                        1,
                                        root.ref(),
                                        List.of(root, member, other),
                                        List.of(unknown))));
    }

    private static ProtosWorkspacePackageModuleResolver resolver(
            ProtosPackageExecutionPlan plan) throws IOException {
        return new ProtosWorkspacePackageModuleResolver(PROJECT, plan);
    }

    private static ProtosObjectValue execute(
            ProtosWorkspacePackageModuleResolver resolver, ProtosModuleKey key)
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

    private static ProtosPackageExecutionPlan plan() {
        ProtosPackageExecutionPlan.PackageNode root = rootNode();
        ProtosPackageExecutionPlan.PackageNode member = memberNode();
        ProtosPackageExecutionPlan.PackageNode other = otherNode();
        return new ProtosPackageExecutionPlan(
                1,
                root.ref(),
                List.of(root, member, other),
                List.of(
                        edge("root-pkg", "member", "member-pkg"),
                        edge("root-pkg", "alternate", "member-pkg")));
    }

    private static ProtosPackageExecutionPlan.PackageNode rootNode() {
        return node("root-pkg", "", Map.of());
    }

    private static ProtosPackageExecutionPlan.PackageNode memberNode() {
        return node(
                "member-pkg",
                "member",
                Map.of("Api", "Public/Entry", "Facade", "Public/Entry"));
    }

    private static ProtosPackageExecutionPlan.PackageNode otherNode() {
        return node("other-pkg", "other", Map.of());
    }

    private static ProtosPackageExecutionPlan.PackageNode node(
            String packageId, String location, Map<String, String> exports) {
        return new ProtosPackageExecutionPlan.PackageNode(
                new ProtosPackageExecutionPlan.WorkspaceRef(packageId),
                location,
                exports);
    }

    private static ProtosPackageExecutionPlan.DependencyEdge edge(
            String declaring, String alias, String target) {
        return new ProtosPackageExecutionPlan.DependencyEdge(
                new ProtosPackageExecutionPlan.WorkspaceRef(declaring),
                alias,
                new ProtosPackageExecutionPlan.WorkspaceRef(target));
    }
}
