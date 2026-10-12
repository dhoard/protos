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
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.guillermomolina.protos.runtime.ProtosArrayValue;
import com.guillermomolina.protos.runtime.ProtosEnvironmentValue;
import com.guillermomolina.protos.runtime.ProtosIntegerValue;
import com.guillermomolina.protos.runtime.ProtosNetworkCapabilityValue;
import com.guillermomolina.protos.runtime.ProtosProcessRuntime;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class ProtosWorkspacePackageAuthorityIsolationIntegrationTest {
    private static final Path CORE = Path.of("protos", "lib", "core");
    private static final Path STANDARD_LIBRARY = Path.of("protos", "lib");
    private static final Path TOOL_ROOT = Path.of("protos", "tools", "package");
    private static final Path WORKSPACE_CASE =
            Path.of(
                    "protos",
                    "tests",
                    "package-tool",
                    "execution-plan",
                    "cases",
                    "workspace");
    private static final Path APPLICATION =
            Path.of("src", "test", "resources", "workspace-package-authority-isolation");

    @TempDir Path temp;

    @Test
    void detachedPlanCrossesFromTerminatedToolProcessIntoDistinctApplicationProcess()
            throws Exception {
        Path project = materializeProject();
        assumeSecureConfinement(project);

        ProtosStandardLibraryModuleResolver standard =
                new ProtosStandardLibraryModuleResolver(STANDARD_LIBRARY);
        AtomicReference<ProtosProcessRuntime> toolProcess = new AtomicReference<>();
        AtomicReference<ProtosProcessRuntime> applicationProcess = new AtomicReference<>();

        ProtosPackageExecutionPlan plan =
                ProtosWorkspacePackagePreflight.build(
                        CORE, TOOL_ROOT, project, standard, toolProcess::set);

        assertEquals("root", plan.root().packageId());
        assertEquals(2, plan.packages().size());

        ProtosExecutionOutcome outcome =
                ProtosWorkspacePackageApplicationExecution.execute(
                        request(project, plan, standard, "Main"),
                        applicationProcess::set);

        assertEquals(ProtosExecutionOutcome.State.COMPLETED, outcome.state());
        ProtosIntegerValue value = (ProtosIntegerValue) outcome.value();
        assertEquals(String.valueOf("73"), com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerDecimalText(value));

        ProtosProcessRuntime tool = toolProcess.get();
        ProtosProcessRuntime application = applicationProcess.get();
        assertNotNull(tool);
        assertNotNull(application);
        assertNotSame(tool, application);
        assertEquals(ProtosProcessRuntime.LifecycleState.TERMINATED, tool.lifecycleState());
        assertEquals(
                ProtosProcessRuntime.LifecycleState.TERMINATED,
                application.lifecycleState());
        assertTrue(tool.rootFilesystemForRuntime().isEmpty());
        assertTrue(application.rootFilesystemForRuntime().isEmpty());
    }

    @Test
    void packageToolProjectFilesystemDoesNotCrossDetachedBoundary()
            throws Exception {
        Path project = materializeProject();
        assumeSecureConfinement(project);

        ProtosStandardLibraryModuleResolver standard =
                new ProtosStandardLibraryModuleResolver(STANDARD_LIBRARY);
        ProtosPackageExecutionPlan plan =
                ProtosWorkspacePackagePreflight.build(
                        CORE, TOOL_ROOT, project, standard);

        ProtosExecutionOutcome outcome =
                ProtosWorkspacePackageApplicationExecution.execute(
                        request(project, plan, standard, "Leak"));

        assertEquals(ProtosExecutionOutcome.State.FAILED, outcome.state());
        assertNotNull(outcome.error());
    }

    @Test
    void workspaceRunDriverDefaultRouteKeepsPreflightAndApplicationNetworkLess()
            throws Exception {
        Path project = materializeProject();
        assumeSecureConfinement(project);

        ProtosStandardLibraryModuleResolver standard =
                new ProtosStandardLibraryModuleResolver(STANDARD_LIBRARY);
        assertEquals(
                ProtosExecutionOutcome.State.FAILED,
                ProtosWorkspaceRunDriver.execute(driverRequest(project, standard)).state());

        try (ProtosPolyglotRuntimeHost runtimeHost = ProtosPolyglotRuntimeHost.open()) {
            ProtosWorkspacePackagePreflight.build(CORE, TOOL_ROOT, project, standard, runtimeHost);
            assertFalse(runtimeHost.networkHostInitializedForTesting());

            ProtosExecutionOutcome outcome =
                    ProtosWorkspaceRunDriver.execute(
                            driverRequest(project, standard),
                            ProtosWorkspacePackageApplicationExecution.NetworkGrant.NONE,
                            runtimeHost);

            assertEquals(ProtosExecutionOutcome.State.FAILED, outcome.state());
            assertNotNull(outcome.error());
            assertFalse(runtimeHost.networkHostInitializedForTesting());
        }
    }

    @Test
    void workspaceRunDriverExplicitGrantReachesApplicationOnOwningRuntimeHost()
            throws Exception {
        Path project = materializeProject();
        assumeSecureConfinement(project);

        ProtosStandardLibraryModuleResolver standard =
                new ProtosStandardLibraryModuleResolver(STANDARD_LIBRARY);
        try (ProtosPolyglotRuntimeHost runtimeHost = ProtosPolyglotRuntimeHost.open()) {
            ProtosExecutionOutcome outcome =
                    ProtosWorkspaceRunDriver.execute(
                            driverRequest(project, standard),
                            ProtosWorkspacePackageApplicationExecution.NetworkGrant.HOST_NETWORK,
                            runtimeHost);

            assertEquals(ProtosExecutionOutcome.State.COMPLETED, outcome.state());
            ProtosArrayValue result = assertInstanceOf(ProtosArrayValue.class, outcome.value());
            ProtosNetworkCapabilityValue network =
                    assertInstanceOf(
                            ProtosNetworkCapabilityValue.class, result.indexedAt(0));
            assertSame(result.indexedAt(1), network.representedDelegationParent(null));
            assertTrue(runtimeHost.networkHostInitializedForTesting());
        }
    }

    private static ProtosWorkspaceRunDriver.Request driverRequest(
            Path project, ProtosModuleResolver standard) {
        return new ProtosWorkspaceRunDriver.Request(
                CORE,
                TOOL_ROOT,
                project,
                standard,
                "NetworkGrant",
                List.of(),
                exactEnvironmentDomain(),
                List.of(),
                null,
                null,
                null,
                null,
                null,
                null);
    }

    private static ProtosWorkspacePackageApplicationExecution.Request request(
            Path project,
            ProtosPackageExecutionPlan plan,
            ProtosModuleResolver standard,
            String entry) {
        return new ProtosWorkspacePackageApplicationExecution.Request(
                CORE,
                project,
                plan,
                standard,
                entry,
                List.of(),
                exactEnvironmentDomain(),
                List.of(),
                null,
                null,
                null,
                null,
                null,
                null);
    }

    private Path materializeProject() throws Exception {
        Path project = temp.resolve("project");
        copyTree(WORKSPACE_CASE, project);
        overlay(APPLICATION.resolve("Main.protos"), project.resolve("Main.protos"));
        overlay(APPLICATION.resolve("Leak.protos"), project.resolve("Leak.protos"));
        overlay(
                APPLICATION.resolve("NetworkGrant.protos"),
                project.resolve("NetworkGrant.protos"));
        overlay(
                APPLICATION.resolve("libs/a/internal/Thing.protos"),
                project.resolve("libs/a/internal/Thing.protos"));
        return project;
    }

    private static void copyTree(Path source, Path target) throws Exception {
        try (Stream<Path> paths = Files.walk(source)) {
            for (Path current : paths.toList()) {
                Path relative = source.relativize(current);
                Path destination = target.resolve(relative.toString());
                if (Files.isDirectory(current)) {
                    Files.createDirectories(destination);
                } else {
                    Files.createDirectories(destination.getParent());
                    Files.copy(
                            current,
                            destination,
                            StandardCopyOption.REPLACE_EXISTING,
                            StandardCopyOption.COPY_ATTRIBUTES);
                }
            }
        }
    }

    private static void overlay(Path source, Path destination) throws Exception {
        Files.createDirectories(destination.getParent());
        Files.copy(source, destination, StandardCopyOption.REPLACE_EXISTING);
    }

    private static void assumeSecureConfinement(Path root) throws Exception {
        try (ProtosNioReadOnlyTreeFilesystemBackend backend =
                new ProtosNioReadOnlyTreeFilesystemBackend(root)) {
            assumeTrue(
                    backend.secureConfinementAvailable(),
                    "host provider has no SecureDirectoryStream");
        }
    }

    private static ProtosEnvironmentValue.NativeNameDomain exactEnvironmentDomain() {
        return new ProtosEnvironmentValue.NativeNameDomain() {
            @Override
            public boolean sameCapturedName(String left, String right) {
                return left.equals(right);
            }

            @Override
            public boolean isQueryRepresentable(String name) {
                return !name.contains("=") && name.indexOf('\0') < 0;
            }

            @Override
            public boolean matchesQuery(String captured, String query) {
                return captured.equals(query);
            }
        };
    }
}
