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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.guillermomolina.protos.runtime.ProtosArrayValue;
import com.guillermomolina.protos.runtime.ProtosByteIoFlow;
import com.guillermomolina.protos.runtime.ProtosEnvironmentValue;
import com.guillermomolina.protos.runtime.ProtosIntegerValue;
import com.guillermomolina.protos.runtime.ProtosNetworkCapabilityValue;
import com.guillermomolina.protos.runtime.ProtosProcessRuntime;
import com.guillermomolina.protos.runtime.ProtosProcessStandardStreamBinding;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class ProtosWorkspacePackageApplicationExecutionTest {
    private static final Path CORE = Path.of("protos", "lib", "core");
    private static final Path STANDARD_LIBRARY = Path.of("protos", "lib");
    private static final Path PROJECT =
            Path.of("src", "test", "resources", "workspace-package-application-process");

    @Test
    void detachedPlanRunsInFreshProcessWithExplicitBootstrapData() throws Exception {
        CapturingWritableBackend stdout = new CapturingWritableBackend();
        AtomicReference<ProtosProcessRuntime> observed = new AtomicReference<>();

        ProtosExecutionOutcome outcome =
                ProtosWorkspacePackageApplicationExecution.execute(
                        new ProtosWorkspacePackageApplicationExecution.Request(
                                CORE,
                                PROJECT,
                                rootPlan(),
                                new ProtosStandardLibraryModuleResolver(STANDARD_LIBRARY),
                                "Main",
                                List.of("argument-value"),
                                exactEnvironmentDomain(),
                                List.of(
                                        new ProtosEnvironmentValue.NativeEntry(
                                                "APP_ONLY", "environment-value")),
                                null,
                                stdout,
                                null,
                                null,
                                "UTF8",
                                null),
                        process -> {
                            assertEquals(
                                    ProtosProcessRuntime.LifecycleState.RUNNING,
                                    process.lifecycleState());
                            observed.set(process);
                        });

        assertEquals(ProtosExecutionOutcome.State.COMPLETED, outcome.state());
        ProtosIntegerValue value = (ProtosIntegerValue) outcome.value();
        assertEquals(String.valueOf("42"), com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerDecimalText(value));
        assertEquals("argument-value\nenvironment-value\n", stdout.utf8());

        ProtosProcessRuntime process = observed.get();
        assertNotNull(process);
        assertEquals(
                ProtosProcessRuntime.LifecycleState.TERMINATED,
                process.lifecycleState());
        assertTrue(process.rootFilesystemForRuntime().isEmpty());
    }

    @Test
    void streamEncodingSelectionIsExactAndDoesNotCaseFold() throws Exception {
        CapturingWritableBackend stdout = new CapturingWritableBackend();
        AtomicReference<ProtosProcessRuntime> observed = new AtomicReference<>();

        IOException failure =
                assertThrows(
                        IOException.class,
                        () ->
                                ProtosWorkspacePackageApplicationExecution.execute(
                                        new ProtosWorkspacePackageApplicationExecution.Request(
                                                CORE,
                                                PROJECT,
                                                rootPlan(),
                                                new ProtosStandardLibraryModuleResolver(
                                                        STANDARD_LIBRARY),
                                                "Main",
                                                List.of("argument-value"),
                                                exactEnvironmentDomain(),
                                                List.of(
                                                        new ProtosEnvironmentValue.NativeEntry(
                                                                "APP_ONLY",
                                                                "environment-value")),
                                                null,
                                                stdout,
                                                null,
                                                null,
                                                "utf8",
                                                null),
                                        observed::set));

        assertTrue(failure.getMessage().contains("Encoding"));
        assertTrue(observed.get() == null);
    }

    @Test
    void defaultRouteLeavesNetworkAbsentWithoutInitializingNetworkHost() throws Exception {
        CapturingWritableBackend stdout = new CapturingWritableBackend();

        ProtosExecutionOutcome defaultOutcome =
                ProtosWorkspacePackageApplicationExecution.execute(networkRequest(stdout));

        assertEquals(ProtosExecutionOutcome.State.FAILED, defaultOutcome.state());
        assertNotNull(defaultOutcome.error());

        try (ProtosPolyglotRuntimeHost runtimeHost = ProtosPolyglotRuntimeHost.open()) {
            ProtosExecutionOutcome outcome =
                    ProtosWorkspacePackageApplicationExecution.execute(
                            networkRequest(stdout),
                            ProtosWorkspacePackageApplicationExecution.NetworkGrant.NONE,
                            runtimeHost);

            assertEquals(ProtosExecutionOutcome.State.FAILED, outcome.state());
            assertNotNull(outcome.error());
            assertFalse(runtimeHost.networkHostInitializedForTesting());
        }
        assertEquals("argument-value\nargument-value\n", stdout.utf8());
    }

    @Test
    void explicitGrantProvisionsNetworkForApplicationPreludeOnHostingRuntimeHost()
            throws Exception {
        CapturingWritableBackend stdout = new CapturingWritableBackend();
        AtomicReference<ProtosProcessRuntime> observed = new AtomicReference<>();

        try (ProtosPolyglotRuntimeHost runtimeHost = ProtosPolyglotRuntimeHost.open()) {
            assertFalse(runtimeHost.networkHostInitializedForTesting());

            ProtosExecutionOutcome outcome =
                    ProtosWorkspacePackageApplicationExecution.execute(
                            networkRequest(stdout),
                            process -> {
                                assertTrue(runtimeHost.networkHostInitializedForTesting());
                                observed.set(process);
                            },
                            ProtosWorkspacePackageApplicationExecution.NetworkGrant.HOST_NETWORK,
                            runtimeHost);

            assertEquals(ProtosExecutionOutcome.State.COMPLETED, outcome.state());
            ProtosArrayValue result = assertInstanceOf(ProtosArrayValue.class, outcome.value());
            ProtosNetworkCapabilityValue network =
                    assertInstanceOf(
                            ProtosNetworkCapabilityValue.class, result.indexedAt(0));
            Object applicationNetworkPrototype = result.indexedAt(1);
            assertSame(applicationNetworkPrototype, network.representedDelegationParent(null));
            assertTrue(runtimeHost.networkHostInitializedForTesting());
        }

        assertEquals("argument-value\n", stdout.utf8());
        ProtosProcessRuntime process = observed.get();
        assertNotNull(process);
        assertEquals(
                ProtosProcessRuntime.LifecycleState.TERMINATED,
                process.lifecycleState());
        assertTrue(process.rootFilesystemForRuntime().isEmpty());
    }

    private static ProtosWorkspacePackageApplicationExecution.Request networkRequest(
            CapturingWritableBackend stdout) {
        return new ProtosWorkspacePackageApplicationExecution.Request(
                CORE,
                PROJECT,
                rootPlan(),
                new ProtosStandardLibraryModuleResolver(STANDARD_LIBRARY),
                "NetworkGrant",
                List.of("argument-value"),
                exactEnvironmentDomain(),
                List.of(),
                null,
                stdout,
                null,
                null,
                "UTF8",
                null);
    }

    private static ProtosPackageExecutionPlan rootPlan() {
        ProtosPackageExecutionPlan.WorkspaceRef root =
                new ProtosPackageExecutionPlan.WorkspaceRef("application-root");
        ProtosPackageExecutionPlan.PackageNode node =
                new ProtosPackageExecutionPlan.PackageNode(root, "", Map.of());
        return new ProtosPackageExecutionPlan(1, root, List.of(node), List.of());
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

    private static final class CapturingWritableBackend
            implements ProtosProcessStandardStreamBinding.WritableBackend {
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();

        @Override
        public ProtosByteIoFlow.Cancellation write(
                byte[] contribution,
                ProtosByteIoFlow.WriteCompletion completion) {
            bytes.write(contribution, 0, contribution.length);
            completion.succeeded();
            return () -> {};
        }

        String utf8() {
            return bytes.toString(StandardCharsets.UTF_8);
        }
    }
}
