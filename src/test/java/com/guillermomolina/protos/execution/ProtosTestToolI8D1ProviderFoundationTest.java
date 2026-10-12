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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.guillermomolina.protos.runtime.ProtosIntegerValue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

final class ProtosTestToolI8D1ProviderFoundationTest {

    private static final Path MAIN = Path.of("protos", "tools", "test", "Main.protos");
    private static final Path RUNNER = Path.of("protos", "tools", "test", "Runner.protos");
    private static final Path BOOTSTRAP =
            Path.of(
                    "src",
                    "main",
                    "java",
                    "com",
                    "guillermomolina",
                    "protos",
                    "execution",
                    "ProtosStandaloneProcessBootstrap.java");

    @Test
    void registryResolvesOnlyExactEnvironmentOwnedProviderIdentity() {
        ProtosTestResourceProviderAdapter gpu =
                request ->
                        CompletableFuture.completedFuture(
                                new ProtosTestResourceProviderLease(
                                        Map.of("gpu", new Object()),
                                        () -> CompletableFuture.completedFuture(null)));

        ProtosTestResourceProviderRegistry registry =
                new ProtosTestResourceProviderRegistry(
                        List.of(
                                new ProtosTestResourceProviderRegistry.Registration(
                                        "device/gpu", gpu)));

        assertEquals(1, registry.size());
        assertSame(gpu, registry.resolve("device/gpu"));

        assertThrows(
                ProtosTestResourceProviderRegistry.UnknownProviderException.class,
                () -> registry.resolve("device"));
        assertThrows(
                ProtosTestResourceProviderRegistry.UnknownProviderException.class,
                () -> registry.resolve("DEVICE/GPU"));
        assertThrows(
                ProtosTestResourceProviderRegistry.UnknownProviderException.class,
                () -> registry.resolve("device/gpu/a100"));
    }

    @Test
    void duplicateRegistrationFailsClosedInsteadOfChoosingAnImplementation() {
        ProtosTestResourceProviderAdapter first =
                request ->
                        CompletableFuture.completedFuture(
                                new ProtosTestResourceProviderLease(
                                        Map.of(), () -> CompletableFuture.completedFuture(null)));
        ProtosTestResourceProviderAdapter second =
                request ->
                        CompletableFuture.completedFuture(
                                new ProtosTestResourceProviderLease(
                                        Map.of(), () -> CompletableFuture.completedFuture(null)));

        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new ProtosTestResourceProviderRegistry(
                                List.of(
                                        new ProtosTestResourceProviderRegistry.Registration(
                                                "service/db", first),
                                        new ProtosTestResourceProviderRegistry.Registration(
                                                "service/db", second))));
    }

    @Test
    void requestSnapshotsOneProviderGroupWithoutReinterpretingProfileOrUnits() {
        List<ProtosTestResourceProviderRequest.Binding> callerBindings = new ArrayList<>();
        ProtosTestResourceProviderRequest.Binding gpu =
                new ProtosTestResourceProviderRequest.Binding(
                        "gpu",
                        "shared",
                        new ProtosIntegerValue(2),
                        "placement",
                        "device/gpu",
                        "a100");
        callerBindings.add(gpu);

        ProtosTestResourceProviderRequest request =
                new ProtosTestResourceProviderRequest("device/gpu", callerBindings);
        callerBindings.clear();

        assertEquals("device/gpu", request.provider());
        assertEquals(1, request.bindings().size());
        assertSame(gpu, request.bindings().get(0));
        assertEquals("a100", request.bindings().get(0).profile());
        assertEquals(String.valueOf("2"), com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerDecimalText(request.bindings().get(0).units()));

        assertThrows(
                UnsupportedOperationException.class,
                () ->
                        request.bindings()
                                .add(
                                        new ProtosTestResourceProviderRequest.Binding(
                                                "other",
                                                "exclusive",
                                                null,
                                                "run",
                                                "device/gpu",
                                                null)));

        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new ProtosTestResourceProviderRequest(
                                "device/gpu",
                                List.of(
                                        new ProtosTestResourceProviderRequest.Binding(
                                                "db/integration",
                                                "exclusive",
                                                null,
                                                "run",
                                                "service/db",
                                                null))));
    }

    @Test
    void adapterBoundaryCanCompleteProvisioningAsynchronously() {
        CompletableFuture<ProtosTestResourceProviderLease> pending = new CompletableFuture<>();
        ProtosTestResourceProviderAdapter adapter = request -> pending;

        ProtosTestResourceProviderRequest request =
                new ProtosTestResourceProviderRequest(
                        "device/gpu",
                        List.of(
                                new ProtosTestResourceProviderRequest.Binding(
                                        "gpu",
                                        "exclusive",
                                        null,
                                        "placement",
                                        "device/gpu",
                                        null)));

        CompletionStage<ProtosTestResourceProviderLease> stage = adapter.provision(request);
        assertFalse(stage.toCompletableFuture().isDone());

        ProtosTestResourceProviderLease lease =
                new ProtosTestResourceProviderLease(
                        Map.of("gpu", new Object()),
                        () -> CompletableFuture.completedFuture(null));
        pending.complete(lease);

        assertSame(lease, stage.toCompletableFuture().join());
    }

    @Test
    void leaseSnapshotsCapabilitiesAndKeepsCleanupHostOwnedAndAsyncCapable() {
        Object gpuCapability = new Object();
        LinkedHashMap<String, Object> callerCapabilities = new LinkedHashMap<>();
        callerCapabilities.put("gpu", gpuCapability);

        AtomicInteger cleanupCalls = new AtomicInteger();
        CompletableFuture<Void> cleanupCompletion = new CompletableFuture<>();

        ProtosTestResourceProviderLease lease =
                new ProtosTestResourceProviderLease(
                        callerCapabilities,
                        () -> {
                            cleanupCalls.incrementAndGet();
                            return cleanupCompletion;
                        });

        callerCapabilities.clear();

        assertEquals(1, lease.guestCapabilities().size());
        assertSame(gpuCapability, lease.guestCapabilities().get("gpu"));
        assertThrows(
                UnsupportedOperationException.class,
                () -> lease.guestCapabilities().put("other", new Object()));

        CompletionStage<Void> cleanup = lease.cleanup();
        assertEquals(1, cleanupCalls.get());
        assertFalse(cleanup.toCompletableFuture().isDone());

        cleanupCompletion.complete(null);
        cleanup.toCompletableFuture().join();
        assertTrue(cleanup.toCompletableFuture().isDone());
    }

    @Test
    void i8d1DoesNotWireRunnerMainOrRootActorResourcesProjection() throws Exception {
        String main = Files.readString(MAIN, StandardCharsets.UTF_8);
        String runner = Files.readString(RUNNER, StandardCharsets.UTF_8);
        String bootstrap = Files.readString(BOOTSTRAP, StandardCharsets.UTF_8);

        assertFalse(main.contains("ProviderAdapter"));
        assertFalse(main.contains("ProviderLease"));
        assertFalse(runner.contains("ProviderAdapter"));
        assertFalse(runner.contains("ProviderLease"));
        assertFalse(bootstrap.contains("\"resources\""));
    }
}
