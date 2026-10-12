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

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;

/** Integrated PLAT048 B′ composition: workspace-only routes that never consult the provider. */
@ResourceLock(ProtosPackageRunDriverTestSupport.RESOURCE_LOCK)
final class ProtosPackageRunDriverTest extends ProtosPackageRunDriverTestSupport {
    @Test
    void workspaceOnlyProjectUsesGenerationOneRouteWithoutConsultingProvider() throws Exception {
        Path project = temporaryRoot.resolve("workspace");
        copyTree(WORKSPACE_CASE, project);
        Files.writeString(
                project.resolve("Main.protos"),
                "writer: TextWriter(process.stdout(), process.stdoutEncoding())\n"
                        + "writer.writeLine(process.args().at(0)).value()\n"
                        + "42\n",
                StandardCharsets.UTF_8);
        assumeSecureConfinement(project);
        RecordingProvider provider = new RecordingProvider(Map.of());
        CapturingWritableBackend stdout = new CapturingWritableBackend();

        ProtosExecutionOutcome outcome =
                ProtosPackageRunDriver.execute(
                        request(project, stdout),
                        provider,
                        generationOneOnlyStages());

        assertEquals(ProtosExecutionOutcome.State.COMPLETED, outcome.state());
        assertEquals(String.valueOf("42"), com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerDecimalText(outcome.value()));
        assertEquals("argument-value\n", stdout.utf8());
        assertEquals(List.of(), provider.calls);
    }

    @Test
    void workspaceOnlyDefaultPublicRouteKeepsApplicationNetworkLess() throws Exception {
        Path project = workspaceProject("workspace-default", "[network, Network]\n");
        RecordingProvider provider = new RecordingProvider(Map.of());

        ProtosExecutionOutcome outcome =
                ProtosPackageRunDriver.execute(request(project, null), provider);

        assertEquals(ProtosExecutionOutcome.State.FAILED, outcome.state());
        assertEquals(List.of(), provider.calls);
    }

    @Test
    void workspaceOnlyExplicitGrantSurvivesGenerationOneDelegationWithoutConsultingProvider()
            throws Exception {
        Path project = workspaceProject("workspace-network", "[network, Network]\n");
        RecordingProvider provider = new RecordingProvider(Map.of());

        ProtosExecutionOutcome outcome =
                ProtosPackageRunDriver.execute(
                        request(project, null),
                        provider,
                        ProtosWorkspacePackageApplicationExecution.NetworkGrant.HOST_NETWORK,
                        generationOneOnlyStages());

        assertEquals(ProtosExecutionOutcome.State.COMPLETED, outcome.state());
        assertNetworkOfThisApplication(outcome);
        assertEquals(List.of(), provider.calls);
    }
}
