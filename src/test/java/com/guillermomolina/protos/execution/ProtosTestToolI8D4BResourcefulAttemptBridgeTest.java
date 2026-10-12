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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.guillermomolina.protos.runtime.ProtosIntegerValue;
import com.guillermomolina.protos.runtime.ProtosPrelude;
import com.guillermomolina.protos.runtime.ProtosStringValue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

final class ProtosTestToolI8D4BResourcefulAttemptBridgeTest {
    private static final Path CORE = Path.of("protos", "lib", "core");
    private static final Path RUNNER = Path.of("protos", "tools", "test", "Runner.protos");
    private static final Path MAIN = Path.of("protos", "tools", "test", "Main.protos");

    @Test
    void successfulAttemptProjectsResourceAndReturnsFullCapturedGuestObservation()
            throws Exception {
        ProtosPrelude prelude = new ProtosCoreBootstrap().bootstrap(CORE);
        AtomicInteger provisionCalls = new AtomicInteger();
        AtomicInteger cleanupCalls = new AtomicInteger();

        ProtosTestResourceProviderAdapter adapter =
                request -> {
                    provisionCalls.incrementAndGet();
                    return CompletableFuture.completedFuture(
                            new ProtosTestResourceProviderLease(
                                    Map.of(
                                            "gpu",
                                            new ProtosIntegerValue(42)),
                                    () -> {
                                        cleanupCalls.incrementAndGet();
                                        return CompletableFuture.completedFuture(null);
                                    }));
                };

        try (ProtosPolyglotRuntimeHost runtimeHost = ProtosPolyglotRuntimeHost.open()) {
            ProtosTestResourcefulAttemptBridge bridge =
                    new ProtosTestResourcefulAttemptBridge(
                            registry("device/gpu", adapter),
                            runtimeHost,
                            threadedSubmission());

            ProtosTestResourceAttemptCompletion completion =
                    bridge.execute(
                                    new ProtosTestResourcefulAttemptBridge.Request(
                                            execution(
                                                    prelude,
                                                    "resources[\"gpu\"]"),
                                            List.of(binding("gpu", "device/gpu"))))
                            .toCompletableFuture()
                            .get(10, TimeUnit.SECONDS);

            assertEquals(1, provisionCalls.get());
            assertEquals(1, cleanupCalls.get());
            assertFalse(completion.infrastructureFailed());
            assertEquals(
                    ProtosTestResourceAttemptCompletion.CapacityDisposition.SAFE,
                    completion.capacityDisposition());

            ProtosCapturedProcessExecution.Result guest =
                    completion.guestObservation().orElseThrow();
            assertEquals(
                    ProtosExecutionOutcome.State.COMPLETED,
                    guest.outcome().state());
            ProtosIntegerValue value =
                    assertInstanceOf(
                            ProtosIntegerValue.class,
                            guest.outcome().value());
            assertEquals(String.valueOf("42"), com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerDecimalText(value));
            assertEquals(0, guest.stdout().length);
            assertEquals(0, guest.stderr().length);
        }
    }

    @Test
    void provisioningFailureIsInfrastructureOnlyAndSafeWhenRollbackIsClean()
            throws Exception {
        ProtosPrelude prelude = new ProtosCoreBootstrap().bootstrap(CORE);
        RuntimeException provisionFailure =
                new RuntimeException("provider-unavailable");
        AtomicInteger submissions = new AtomicInteger();

        ProtosTestResourceProviderAdapter adapter =
                request -> CompletableFuture.failedFuture(provisionFailure);

        try (ProtosPolyglotRuntimeHost runtimeHost = ProtosPolyglotRuntimeHost.open()) {
            ProtosTestResourcefulAttemptBridge bridge =
                    new ProtosTestResourcefulAttemptBridge(
                            registry("device/gpu", adapter),
                            runtimeHost,
                            work -> {
                                submissions.incrementAndGet();
                                Thread thread = new Thread(work);
                                thread.start();
                                return () -> false;
                            });

            ProtosTestResourceAttemptCompletion completion =
                    bridge.execute(
                                    new ProtosTestResourcefulAttemptBridge.Request(
                                            execution(prelude, "1"),
                                            List.of(binding("gpu", "device/gpu"))))
                            .toCompletableFuture()
                            .get(10, TimeUnit.SECONDS);

            assertEquals(0, submissions.get());
            assertTrue(completion.guestObservation().isEmpty());
            assertTrue(completion.infrastructureFailed());
            assertSame(provisionFailure, completion.infrastructureFailures().get(0));
            assertEquals(
                    ProtosTestResourceAttemptCompletion.CapacityDisposition.SAFE,
                    completion.capacityDisposition());
        }
    }

    @Test
    void rollbackCleanupFailureMakesProvisioningFailureCapacityUnsafe()
            throws Exception {
        ProtosPrelude prelude = new ProtosCoreBootstrap().bootstrap(CORE);
        RuntimeException laterFailure = new RuntimeException("provider-b-failed");
        RuntimeException rollbackFailure = new RuntimeException("provider-a-cleanup-failed");

        ProtosTestResourceProviderAdapter first =
                request ->
                        CompletableFuture.completedFuture(
                                new ProtosTestResourceProviderLease(
                                        Map.of("gpu", new Object()),
                                        () -> CompletableFuture.failedFuture(rollbackFailure)));
        ProtosTestResourceProviderAdapter second =
                request -> CompletableFuture.failedFuture(laterFailure);

        ProtosTestResourceProviderRegistry registry =
                new ProtosTestResourceProviderRegistry(
                        List.of(
                                new ProtosTestResourceProviderRegistry.Registration(
                                        "provider/a", first),
                                new ProtosTestResourceProviderRegistry.Registration(
                                        "provider/b", second)));

        try (ProtosPolyglotRuntimeHost runtimeHost = ProtosPolyglotRuntimeHost.open()) {
            ProtosTestResourcefulAttemptBridge bridge =
                    new ProtosTestResourcefulAttemptBridge(
                            registry,
                            runtimeHost,
                            threadedSubmission());

            ProtosTestResourceAttemptCompletion completion =
                    bridge.execute(
                                    new ProtosTestResourcefulAttemptBridge.Request(
                                            execution(prelude, "1"),
                                            List.of(
                                                    binding("gpu", "provider/a"),
                                                    binding("db", "provider/b"))))
                            .toCompletableFuture()
                            .get(10, TimeUnit.SECONDS);

            assertTrue(completion.guestObservation().isEmpty());
            assertEquals(2, completion.infrastructureFailures().size());
            assertSame(laterFailure, completion.infrastructureFailures().get(0));
            assertSame(rollbackFailure, completion.infrastructureFailures().get(1));
            assertEquals(
                    ProtosTestResourceAttemptCompletion.CapacityDisposition.UNSAFE,
                    completion.capacityDisposition());
        }
    }

    @Test
    void submissionFailureCleansProvisionedLeaseAndKeepsCapacitySafe()
            throws Exception {
        ProtosPrelude prelude = new ProtosCoreBootstrap().bootstrap(CORE);
        AtomicInteger cleanupCalls = new AtomicInteger();
        RuntimeException submissionFailure = new RuntimeException("submission-failed");

        ProtosTestResourceProviderAdapter adapter =
                request ->
                        CompletableFuture.completedFuture(
                                new ProtosTestResourceProviderLease(
                                        Map.of(
                                                "gpu",
                                                new ProtosIntegerValue(1L)),
                                        () -> {
                                            cleanupCalls.incrementAndGet();
                                            return CompletableFuture.completedFuture(null);
                                        }));

        try (ProtosPolyglotRuntimeHost runtimeHost = ProtosPolyglotRuntimeHost.open()) {
            ProtosTestResourcefulAttemptBridge bridge =
                    new ProtosTestResourcefulAttemptBridge(
                            registry("device/gpu", adapter),
                            runtimeHost,
                            work -> {
                                throw submissionFailure;
                            });

            ProtosTestResourceAttemptCompletion completion =
                    bridge.execute(
                                    new ProtosTestResourcefulAttemptBridge.Request(
                                            execution(prelude, "1"),
                                            List.of(binding("gpu", "device/gpu"))))
                            .toCompletableFuture()
                            .get(10, TimeUnit.SECONDS);

            assertEquals(1, cleanupCalls.get());
            assertTrue(completion.guestObservation().isEmpty());
            assertSame(submissionFailure, completion.infrastructureFailures().get(0));
            assertEquals(
                    ProtosTestResourceAttemptCompletion.CapacityDisposition.SAFE,
                    completion.capacityDisposition());
        }
    }

    @Test
    void resourcefulBridgeRejectsEmptyBindingSet() throws Exception {
        ProtosPrelude prelude = new ProtosCoreBootstrap().bootstrap(CORE);
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new ProtosTestResourcefulAttemptBridge.Request(
                                execution(prelude, "1"),
                                List.of()));
    }

    @Test
    void publicCutoverKeepsJavaResourcefulAttemptBridgeBehindGuestFacilityBoundary()
            throws Exception {
        String runner = Files.readString(RUNNER, StandardCharsets.UTF_8);
        String main = Files.readString(MAIN, StandardCharsets.UTF_8);

        assertFalse(runner.contains("resourceExecutionAsync"));
        assertTrue(runner.contains("resourceExecutorAsync"));
        // TOOL009-B: repository production is logical-only; Main.protos no
        // longer resolves or invokes resourceExecutionAsync (that only
        // happened on the now-removed D108 repository branch).
        assertFalse(main.contains("resourceExecutionAsync"));
        assertFalse(main.contains("resourceExecutorAsync"));
        assertFalse(runner.contains("ProtosTestResourcefulAttemptBridge"));
        assertFalse(main.contains("ProtosTestResourcefulAttemptBridge"));
    }

    private static ProtosCapturedProcessExecution.Request execution(
            ProtosPrelude prelude, String source) {
        return ProtosExactExecutionFacility.executionRequest(
                new ProtosStringValue(source),
                prelude);
    }

    private static ProtosTestResourceProviderRegistry registry(
            String provider,
            ProtosTestResourceProviderAdapter adapter) {
        return new ProtosTestResourceProviderRegistry(
                List.of(
                        new ProtosTestResourceProviderRegistry.Registration(
                                provider,
                                adapter)));
    }

    private static ProtosTestResourceProviderRequest.Binding binding(
            String key, String provider) {
        return new ProtosTestResourceProviderRequest.Binding(
                key,
                "exclusive",
                null,
                "placement",
                provider,
                null);
    }

    private static ProtosAsyncExactExecutionFacility.Submission threadedSubmission() {
        return work -> {
            Thread thread = new Thread(work, "i8d4b-resourceful-attempt");
            thread.setDaemon(true);
            thread.start();
            return () -> false;
        };
    }
}
