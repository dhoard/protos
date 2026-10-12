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
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class ProtosWorkspacePackageModuleResolverTest {
    private static final Path CORE = Path.of("protos", "lib", "core");
    private static final Path PROJECT =
            Path.of("src", "test", "resources", "workspace-package-self");

    @Test
    void rootEntryExecutesRealProtosSelfImportWithCanonicalPackageIdentity()
            throws Exception {
        ProtosWorkspacePackageModuleResolver resolver = resolver(rootOnlyPlan());
        ProtosModuleKey entry = resolver.entryModule("Main");

        assertEquals(ProtosWorkspacePackageModuleKey.encode("root-pkg", "Main"), entry);
        ProtosObjectValue module = execute(resolver, entry);
        assertIntegerSlot(module, "result", 41);
    }

    @Test
    void selfRoutingUsesTheImportingPackageAndDoesNotConsultExports() throws Exception {
        ProtosWorkspacePackageModuleResolver resolver = resolver(rootAndMemberPlan());
        ProtosModuleKey memberMain =
                ProtosWorkspacePackageModuleKey.encode("member-pkg", "Main");

        assertEquals(
                ProtosWorkspacePackageModuleKey.encode("member-pkg", "Hidden"),
                resolver.resolve("self:Hidden", Optional.of(memberMain)));

        ProtosObjectValue module = execute(resolver, memberMain);
        assertIntegerSlot(module, "result", 73);
    }

    @Test
    void selfRequiresAnOwnedWorkspaceImporterAndOtherSchemesRemainClosed()
            throws Exception {
        ProtosWorkspacePackageModuleResolver resolver = resolver(rootOnlyPlan());
        ProtosModuleKey entry = resolver.entryModule("Main");

        assertThrows(
                IOException.class,
                () -> resolver.resolve("self:Internal/Helper", Optional.empty()));
        assertThrows(
                IOException.class,
                () ->
                        resolver.resolve(
                                "self:Internal/Helper",
                                Optional.of(new ProtosModuleKey("std:collections/Array"))));
        assertThrows(
                IOException.class,
                () ->
                        resolver.resolve(
                                "self:Internal/Helper",
                                Optional.of(
                                        ProtosWorkspacePackageModuleKey.encode(
                                                "outside-plan", "Main"))));
        assertThrows(IOException.class, () -> resolver.resolve("dep:x/Visible", Optional.of(entry)));
        assertThrows(IOException.class, () -> resolver.resolve("std:collections/Array", Optional.of(entry)));
        assertThrows(IOException.class, () -> resolver.resolve("Internal/Helper", Optional.of(entry)));
    }

    @Test
    void loadingAcceptsOnlyCanonicalWorkspaceKeysBackedByTheCurrentPlan()
            throws Exception {
        ProtosWorkspacePackageModuleResolver resolver = resolver(rootOnlyPlan());
        ProtosModuleKey helper =
                ProtosWorkspacePackageModuleKey.encode("root-pkg", "Internal/Helper");

        String source = resolver.loadSource(helper).characters();
        org.junit.jupiter.api.Assertions.assertTrue(source.contains("value: 41"));
        assertThrows(
                IOException.class,
                () -> resolver.loadSource(new ProtosModuleKey("std:collections/Array")).characters());
        assertThrows(
                IOException.class,
                () ->
                        resolver.loadSource(
                                ProtosWorkspacePackageModuleKey.encode(
                                        "outside-plan", "Internal/Helper")).characters());
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

    private static ProtosPackageExecutionPlan rootOnlyPlan() {
        ProtosPackageExecutionPlan.PackageNode root = node("root-pkg", "", Map.of());
        return plan(root, List.of(root));
    }

    private static ProtosPackageExecutionPlan rootAndMemberPlan() {
        ProtosPackageExecutionPlan.PackageNode root = node("root-pkg", "", Map.of());
        ProtosPackageExecutionPlan.PackageNode member =
                node("member-pkg", "member", Map.of());
        return plan(root, List.of(root, member));
    }

    private static ProtosPackageExecutionPlan plan(
            ProtosPackageExecutionPlan.PackageNode root,
            List<ProtosPackageExecutionPlan.PackageNode> packages) {
        return new ProtosPackageExecutionPlan(
                1,
                root.ref(),
                packages,
                List.of());
    }

    private static ProtosPackageExecutionPlan.PackageNode node(
            String packageId, String location, Map<String, String> exports) {
        return new ProtosPackageExecutionPlan.PackageNode(
                new ProtosPackageExecutionPlan.WorkspaceRef(packageId),
                location,
                exports);
    }
}
