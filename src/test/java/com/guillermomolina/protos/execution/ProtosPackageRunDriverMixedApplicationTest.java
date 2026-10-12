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

import com.guillermomolina.protos.runtime.ProtosArrayValue;
import com.guillermomolina.protos.runtime.ProtosProcessRuntime;
import java.io.IOException;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;

/**
 * Integrated PLAT048 B′ composition: the mixed application Process over exactly selected verified
 * captures and its network provisioning.
 */
@ResourceLock(ProtosPackageRunDriverTestSupport.RESOURCE_LOCK)
final class ProtosPackageRunDriverMixedApplicationTest extends ProtosPackageRunDriverTestSupport {
    @Test
    void mixedApplicationRunsOverExactlySelectedVerifiedCapturesAndClosesScopeAfterTermination()
            throws Exception {
        Path project = project("success");
        RecordingProvider provider = new RecordingProvider(materializations("success"));
        CapturingWritableBackend stdout = new CapturingWritableBackend();
        RecordingStages stages =
                new RecordingStages() {
                    @Override
                    public ProtosCapturedFilesystemCustody verify(
                            ProtosWorkspaceRunDriver.Request request,
                            ProtosExactExternalPackageIdentity identity,
                            Path selectedRoot)
                            throws IOException {
                        ProtosCapturedFilesystemCustody custody =
                                super.verify(request, identity, selectedRoot);
                        // The application must read only the verified capture.
                        deleteTree(selectedRoot);
                        return custody;
                    }

                    @Override
                    public void applicationProcessHosted(ProtosProcessRuntime process) {
                        assertEquals(
                                ProtosProcessRuntime.LifecycleState.RUNNING,
                                process.lifecycleState());
                        assertTrue(scope.contains(a1));
                        super.applicationProcessHosted(process);
                    }
                };

        ProtosExecutionOutcome outcome =
                ProtosPackageRunDriver.execute(request(project, stdout), provider, stages);

        assertEquals(ProtosExecutionOutcome.State.COMPLETED, outcome.state());
        assertEquals(String.valueOf("1026"), com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerDecimalText(outcome.value()));
        assertEquals("argument-value\n", stdout.utf8());

        // One complete exact identity per lookup, in requirement order, never deduplicated by
        // PackageId or by ContentIdentity.
        assertEquals(List.of(a1, a2, cRegistry, cGit), provider.calls);
        assertEquals(4, new HashSet<>(provider.calls).size());
        assertEquals(a1.packageId(), a2.packageId());
        assertNotEquals(a1, a2);
        assertEquals(cRegistry.content(), cGit.content());
        assertNotEquals(cRegistry, cGit);
        assertEquals(4, stages.verifyAttempts);

        assertEquals(1, stages.hosted.size());
        assertEquals(
                ProtosProcessRuntime.LifecycleState.TERMINATED,
                stages.hosted.get(0).lifecycleState());
        assertTrue(stages.hosted.get(0).rootFilesystemForRuntime().isEmpty());
        assertThrows(IllegalStateException.class, () -> stages.scope.contains(a1));
        assertAllClosed(stages.verified, 4);
    }

    @Test
    void mixedApplicationExplicitGrantIsProvisionedFromTheExactApplicationPrelude()
            throws Exception {
        Path project = project("mixed-network");
        Files.writeString(
                project.resolve("Main.protos"),
                "A: import(\"dep:reg/Api\")\n[network, Network, A.total]\n",
                StandardCharsets.UTF_8);
        RecordingProvider provider = new RecordingProvider(materializations("mixed-network"));
        RecordingStages stages = new RecordingStages();

        ProtosExecutionOutcome outcome =
                ProtosPackageRunDriver.execute(
                        request(project, null),
                        provider,
                        ProtosWorkspacePackageApplicationExecution.NetworkGrant.HOST_NETWORK,
                        stages);

        assertEquals(ProtosExecutionOutcome.State.COMPLETED, outcome.state());
        ProtosArrayValue result = assertNetworkOfThisApplication(outcome);
        assertEquals(String.valueOf("1026"), com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerDecimalText(result.indexedAt(2)));
        assertMixedLifecycleUnchanged(provider, stages);
    }

    @Test
    void mixedApplicationExplicitNoneKeepsNetworkSlotAbsent() throws Exception {
        Path project = project("mixed-none");
        Files.writeString(
                project.resolve("Main.protos"),
                "A: import(\"dep:reg/Api\")\nA.total\nnetwork\n",
                StandardCharsets.UTF_8);
        RecordingProvider provider = new RecordingProvider(materializations("mixed-none"));
        RecordingStages stages = new RecordingStages();

        ProtosExecutionOutcome outcome =
                ProtosPackageRunDriver.execute(
                        request(project, null),
                        provider,
                        ProtosWorkspacePackageApplicationExecution.NetworkGrant.NONE,
                        stages);

        assertEquals(ProtosExecutionOutcome.State.FAILED, outcome.state());
        assertMixedLifecycleUnchanged(provider, stages);
    }
}
