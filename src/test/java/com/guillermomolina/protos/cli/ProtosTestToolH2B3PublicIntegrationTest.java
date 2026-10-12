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
package com.guillermomolina.protos.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.guillermomolina.protos.execution.ProtosAsyncProcessSnapshotExecutionFacility;
import com.guillermomolina.protos.execution.ProtosBundledToolModuleResolver;
import com.guillermomolina.protos.execution.ProtosCoreBootstrap;
import com.guillermomolina.protos.execution.ProtosExecutionOutcome;
import com.guillermomolina.protos.execution.ProtosInvocation;
import com.guillermomolina.protos.execution.ProtosPolyglotRuntimeHost;
import com.guillermomolina.protos.execution.ProtosRootTaskExecution;
import com.guillermomolina.protos.execution.ProtosSourceCompiler;
import com.guillermomolina.protos.execution.ProtosStandardLibraryModuleResolver;
import com.guillermomolina.protos.execution.ProtosTestLogicalCaseDiscoveryFacility;
import com.guillermomolina.protos.execution.ProtosTestLogicalCaseExecutionFacility;
import com.guillermomolina.protos.execution.ProtosTestToolFileSelectionFacility;
import com.guillermomolina.protos.runtime.ProtosActivation;
import com.guillermomolina.protos.runtime.ProtosBooleanValue;
import com.guillermomolina.protos.runtime.ProtosFutureValue;
import com.guillermomolina.protos.runtime.ProtosIntegerValue;
import com.guillermomolina.protos.runtime.ProtosNullValue;
import com.guillermomolina.protos.runtime.ProtosObjectValue;
import com.guillermomolina.protos.runtime.ProtosPrelude;
import com.guillermomolina.protos.runtime.ProtosStringValue;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class ProtosTestToolH2B3PublicIntegrationTest {
    private static final Path CORE = Path.of("protos", "lib", "core");
    private static final Path STANDARD_LIBRARY = Path.of("protos", "lib");
    private static final Path TOOL_ROOT = Path.of("protos", "tools", "test");
    @Test
    void bundledOptionsOwnExactlyTheRatifiedD069JobsPolicy() throws Exception {
        OptionRun importOnly =
                runSource("Options: import(\"self:Options\")\n1\n");
        OptionRun ordinaryEach =
                runSource(
                        "count: 0\n"
                                + "Array(\"test\").each((argument) => { count = count + 1 })\n"
                                + "count\n");
        OptionRun inlineDefault =
                runSource(
                        "arguments: Array(\"test\")\n"
                                + "result: 1\n"
                                + "seen: false\n"
                                + "expectingValue: false\n"
                                + "arguments.each((argument) => {\n"
                                + "    expectingValue.ifTrue(() => { expectingValue = false })\n"
                                + "    expectingValue.ifFalse(() => {\n"
                                + "        (argument === \"--jobs\").ifTrue(() => {\n"
                                + "            seen.ifTrue(() => { Error().signal() })\n"
                                + "            seen = true\n"
                                + "            expectingValue = true\n"
                                + "        })\n"
                                + "    })\n"
                                + "})\n"
                                + "expectingValue.ifTrue(() => { Error().signal() })\n"
                                + "result\n");
        OptionRun serialDefault = runOptionsExpression("Array(\"test\")");
        OptionRun explicit =
                runOptionsExpression("Array(\"test\", \"--jobs\", \"3\", \"retained\")");
        OptionRun missing = runOptionsExpression("Array(\"test\", \"--jobs\")");
        OptionRun zero = runOptionsExpression("Array(\"test\", \"--jobs\", \"0\")");
        OptionRun negative = runOptionsExpression("Array(\"test\", \"--jobs\", \"-1\")");
        OptionRun malformed = runOptionsExpression("Array(\"test\", \"--jobs\", \"abc\")");
        OptionRun duplicate =
                runOptionsExpression(
                        "Array(\"test\", \"--jobs\", \"2\", \"--jobs\", \"3\")");

        org.junit.jupiter.api.Assertions.assertAll(
                "D069 Options checkpoints",
                () -> assertCompletedInteger(importOnly, 1, "import self:Options"),
                () -> assertCompletedInteger(ordinaryEach, 1, "ordinary Array.each"),
                () -> assertCompletedInteger(inlineDefault, 1, "inline default parser skeleton"),
                () -> assertCompletedInteger(serialDefault, 1, "absent --jobs"),
                () -> assertCompletedInteger(explicit, 3, "--jobs 3"),
                () -> assertFailedError(missing, "missing value"),
                () -> assertFailedError(zero, "zero"),
                () -> assertFailedError(negative, "negative"),
                () -> assertFailedError(malformed, "malformed"),
                () -> assertFailedError(duplicate, "duplicate"));
    }

    @SuppressWarnings("try")
    @Test
    void productionScopeInstallsAllRoutesAndCarriesGenericExecutionAndInspection()
            throws Exception {
        ProtosBundledToolModuleResolver resolver =
                new ProtosBundledToolModuleResolver(
                        "test",
                        TOOL_ROOT,
                        TOOL_ROOT.resolveSibling("shared"),
                        new ProtosStandardLibraryModuleResolver(STANDARD_LIBRARY));
        ProtosPrelude prelude = new ProtosCoreBootstrap().bootstrap(CORE, resolver);
        ProtosActivation activation = prelude.newModuleActivation();

        try (ProtosPolyglotRuntimeHost runtimeHost = ProtosPolyglotRuntimeHost.open();
                ProtosTestToolAsyncExecutionScope scope =
                        ProtosTestToolAsyncExecutionScope.installWithProjectTreeAuthorities(
                                activation,
                                runtimeHost,
                                CORE,
                                resolver,
                                resolver,
                                resolver,
                                resolver,
                                List.of(
                                        new ProtosTestToolFileSelectionFacility.CorpusSourceRoot(
                                                "test-corpus",
                                                Path.of("protos", "tests"))),
                                prelude,
                                prelude,
                                prelude,
                                Path.of(
                                        "protos",
                                        "tests",
                                        "package-tool",
                                        "content-identity",
                                        "cases"),
                                Path.of(
                                        "protos",
                                        "tests",
                                        "package-tool",
                                        "resolution-input-lock",
                                        "cases"),
                                Path.of(
                                        "protos",
                                        "tests",
                                        "package-tool",
                                        "resolution-root",
                                        "cases"),
                                Path.of(
                                        "protos",
                                        "tests",
                                        "package-tool",
                                        "execution-plan",
                                        "cases"),
                                Path.of(
                                        "protos",
                                        "tests",
                                        "package-tool",
                                        "project-projection",
                                        "cases"))) {
            for (String slot : List.of(
                    "executionAsync",
                    "executionInspectAsync",
                    "actorExecutionAsync",
                    "actorExecutionInspectAsync",
                    "groupExecutionAsync",
                    "groupExecutionInspectAsync",
                    "packageExecutionAsync",
                    "packageExecutionInspectAsync",
                    "resourceExecutionAsync",
                    "resourceExecutionInspectAsync",
                    "actorResourceExecutionAsync",
                    "actorResourceExecutionInspectAsync",
                    "groupResourceExecutionAsync",
                    "groupResourceExecutionInspectAsync",
                    "packageResourceExecutionAsync",
                    "packageResourceExecutionInspectAsync",
                    ProtosAsyncProcessSnapshotExecutionFacility.BOOTSTRAP_SLOT,
                    ProtosTestLogicalCaseDiscoveryFacility.BOOTSTRAP_SLOT,
                    ProtosTestLogicalCaseExecutionFacility.BOOTSTRAP_SLOT)) {
                assertTrue(activation.context().hasLocalSlot(slot), "missing async route " + slot);
            }

            ProtosObjectValue execution =
                    invokeAndAwait(
                            activation,
                            "executionAsync",
                            List.of(new ProtosStringValue("1")));
            assertCompletedIntegerObservation(execution, 1);

            ProtosObjectValue inspection =
                    invokeAndAwait(
                            activation,
                            "executionInspectAsync",
                            List.of(
                                    new ProtosStringValue(
                                            "future: (() => { 42 }).future()\n"
                                                    + "() => { future.value() }"),
                                    new ProtosStringValue("(subject) => { subject() }")));
            assertCompletedIntegerObservation(inspection, 42);

            ProtosObjectValue processSnapshot =
                    invokeAndAwait(
                            activation,
                            ProtosAsyncProcessSnapshotExecutionFacility.BOOTSTRAP_SLOT,
                            List.of(
                                    new ProtosStringValue(
                                            "(process.args().size() == 3) && "
                                                    + "(process.args()[0] == otherProcess.args()[0])")));

            ProtosStringValue processSnapshotState =
                    assertInstanceOf(
                            ProtosStringValue.class,
                            processSnapshot.readLocalSlot("state").orElseThrow());

            assertEquals("completed", processSnapshotState.value());
            assertSame(
                    ProtosBooleanValue.TRUE,
                    processSnapshot.readLocalSlot("value").orElseThrow());
        }
    }

    @Test
    void productionSubmissionUsesFreshNamedPlatformThreadsAndDoesNotInterruptStartedWork()
            throws Exception {
        CountDownLatch started = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<Thread> firstThread = new AtomicReference<>();
        AtomicReference<Thread> secondThread = new AtomicReference<>();

        try (ProtosTestToolAsyncExecutionScope.PlatformThreadPerTaskSubmission submission =
                new ProtosTestToolAsyncExecutionScope.PlatformThreadPerTaskSubmission()) {
            var first =
                    submission.submit(
                            () -> blockOnCarrier(started, release, firstThread));
            var second =
                    submission.submit(
                            () -> blockOnCarrier(started, release, secondThread));

            assertTrue(started.await(30, TimeUnit.SECONDS));
            assertEquals(2, submission.activeCarrierCountForTest());
            assertNotSame(firstThread.get(), secondThread.get());
            assertFalse(firstThread.get().isVirtual());
            assertFalse(secondThread.get().isVirtual());
            assertTrue(firstThread.get().getName().startsWith("protos-test-exact-"));
            assertTrue(secondThread.get().getName().startsWith("protos-test-exact-"));
            assertFalse(first.cancelIfNotStarted());
            assertFalse(second.cancelIfNotStarted());

            release.countDown();
        }
    }

    @Test
    void publicMainUsesOneGraphDrivenLogicalCaseRunnerPathForAllSuites() throws Exception {
        String main = Files.readString(TOOL_ROOT.resolve("Main.protos"), StandardCharsets.UTF_8);

        assertTrue(main.contains("Options: import(\"self:Options\")"));
        assertTrue(main.contains("SuiteGraph: import(\"self:SuiteGraph\")"));
        assertTrue(main.contains("RepositorySuite: import(\"self:RepositorySuite\")"));
        assertTrue(main.contains("arguments: process.args()"));
        assertTrue(main.contains("jobs: Options.jobs(arguments)"));
        assertTrue(main.contains("leaves: SuiteGraph.flattenLeaves(RepositorySuite.root)"));
        assertTrue(main.contains("testCorpusBindings.hasSlot(corpus)"));
        assertTrue(main.contains("testCorpusBindings.slotValue(corpus)"));
        assertTrue(main.contains("testExecutionRequirementBindings.hasSlot(requirement)"));
        assertTrue(main.contains("testExecutionRequirementBindings.slotValue(requirement)"));
        assertTrue(main.contains("Manifest.load(corpusBinding.filesystem)"));
        assertTrue(main.contains("Manifest.loadPackageToml(corpusBinding.filesystem)"));
        assertTrue(main.contains("(planLoader === \"project-tree\")"));
        assertTrue(main.contains("Manifest.loadProjectTreeCases("));

        // TOOL009-B: repository production is logical-only, so there is no
        // D108/bounded-Runner repository branch and no legacy binding
        // invocation left; only the generic well-formedness validation
        // (`binding.hasSlot("executionAsync")`) remains.
        assertEquals(0, occurrences(main, "Runner.runD108WithResources("));
        assertEquals(0, occurrences(main, "Runner.runBounded("));
        assertFalse(main.contains("Runner.runSimple("));
        assertFalse(main.contains("binding.executionAsync"));
        assertFalse(main.contains("binding.executionInspectAsync"));
        assertFalse(main.contains("binding.resourceExecutionAsync"));
        assertFalse(main.contains("binding.resourceExecutionInspectAsync"));
        assertTrue(main.contains("binding.hasSlot(\"executionAsync\")"));
        assertTrue(main.contains("corpusBinding.filesystem"));
        assertFalse(main.contains("binding.filesystem"));
        assertFalse(main.contains("binding.planLoader"));
        assertFalse(main.contains("actorExecutionAsync"));
        assertFalse(main.contains("groupExecutionAsync"));
        assertFalse(main.contains("packageExecutionAsync"));
        assertFalse(main.contains("actorFilesystem"));
        assertFalse(main.contains("groupFilesystem"));
        assertFalse(main.contains("packageTomlFilesystem"));
        assertFalse(main.contains("\"protos/conformance\""));
        assertFalse(main.contains("\"protos/actor\""));
        assertFalse(main.contains("\"protos/group\""));
        assertFalse(main.contains("\"protos/package-toml\""));

        // The current sole result source is the Logical Case result
        // projection; the legacy across-runs/across-invocation aggregation
        // functions are no longer called from repository production.
        assertFalse(main.contains("Runner.testRunOutcomeCompletedAcrossRuns("));
        assertFalse(main.contains("Runner.testRunOutcomeInfrastructureAbortedAcrossInvocation("));
        assertTrue(main.contains("LogicalCaseResult.exitCode(logicalResults)"));
        assertTrue(main.contains("LogicalCaseDispatch.logicalCaseExecutorAsync("));
    }

    private static OptionRun runOptionsExpression(String argsExpression) throws Exception {
        return runSource(
                "Options: import(\"self:Options\")\n"
                        + "Options.jobs("
                        + argsExpression
                        + ")\n");
    }

    private static OptionRun runSource(String source) throws Exception {
        ProtosBundledToolModuleResolver resolver =
                new ProtosBundledToolModuleResolver(
                        "test",
                        TOOL_ROOT,
                        TOOL_ROOT.resolveSibling("shared"),
                        new ProtosStandardLibraryModuleResolver(STANDARD_LIBRARY));
        ProtosPrelude prelude = new ProtosCoreBootstrap().bootstrap(CORE, resolver);
        ProtosActivation activation = prelude.newModuleActivation();
        ProtosExecutionOutcome outcome =
                com.guillermomolina.protos.execution.ProtosTestExecutionSupport.execute(
source,
activation);
        return new OptionRun(prelude, outcome);
    }

    private static void assertCompletedInteger(
            OptionRun run, long expected, String checkpoint) {
        ProtosExecutionOutcome outcome = run.outcome();
        assertEquals(
                ProtosExecutionOutcome.State.COMPLETED,
                outcome.state(),
                () -> "D069 checkpoint " + checkpoint + " expected COMPLETED; " + describe(run));
        if (outcome.state() != ProtosExecutionOutcome.State.COMPLETED) {
            return;
        }
        assertEquals(Long.toString(expected), com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerDecimalText(outcome.value()), "D069 checkpoint " + checkpoint + " integer value");
    }

    private static void assertFailedError(OptionRun run, String checkpoint) {
        ProtosExecutionOutcome outcome = run.outcome();
        assertEquals(
                ProtosExecutionOutcome.State.FAILED,
                outcome.state(),
                () -> "D069 checkpoint " + checkpoint + " expected FAILED; " + describe(run));
        if (outcome.state() != ProtosExecutionOutcome.State.FAILED) {
            return;
        }
        assertEquals(
                "Error",
                guestErrorParentName(outcome, run.prelude()),
                () -> "D069 checkpoint " + checkpoint + " wrong Error family; " + describe(run));
    }

    private static String describe(OptionRun run) {
        ProtosExecutionOutcome outcome = run.outcome();
        if (outcome.error() == null) {
            return "outcome=" + outcome.state() + ", error=-";
        }
        return "outcome="
                + outcome.state()
                + ", guestErrorParent="
                + guestErrorParentName(outcome, run.prelude())
                + ", diagnostic="
                + new ProtosDiagnosticInspector().render(outcome.error());
    }

    private static ProtosObjectValue invokeAndAwait(
            ProtosActivation activation,
            String slotName,
            List<?> arguments)
            throws Exception {
        Object execution = activation.context().readLocalSlot(slotName).orElseThrow();
        ProtosFutureValue future =
                assertInstanceOf(
                        ProtosFutureValue.class,
                        ProtosInvocation.invoke(execution, arguments, activation));

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (future.isPending()) {
            if (!activation.executionDomain().dispatchOne()) {
                if (System.nanoTime() >= deadline) {
                    fail(slotName + " caller Future did not become terminal");
                }
                Thread.sleep(1);
            }
        }

        assertEquals(
                ProtosFutureValue.State.RESOLVED,
                future.state(),
                () -> slotName
                        + " future state=" + future.state()
                        + ", error=" + future.failedError().map(Object::getClass).orElse(null));
        return assertInstanceOf(
                ProtosObjectValue.class,
                future.resolvedValue().orElseThrow());
    }

    private static void assertCompletedIntegerObservation(
            ProtosObjectValue observation,
            long expectedValue) {
        ProtosStringValue state =
                assertInstanceOf(
                        ProtosStringValue.class,
                        observation.readLocalSlot("state").orElseThrow());
        assertEquals("completed", state.value());
        assertSame(ProtosNullValue.INSTANCE, observation.readLocalSlot("error").orElseThrow());
        ProtosIntegerValue value =
                assertInstanceOf(
                        ProtosIntegerValue.class,
                        observation.readLocalSlot("value").orElseThrow());
        assertEquals(Long.toString(expectedValue), com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerDecimalText(value));
    }

    private static String guestErrorParentName(
            ProtosExecutionOutcome outcome, ProtosPrelude prelude) {
        if (outcome.error() == null) {
            return "-";
        }
        Object parent = outcome.error().parent().orElse(null);
        for (var entry : prelude.bindings().localSlotsSnapshot().entrySet()) {
            if (entry.getValue() == parent) {
                return entry.getKey();
            }
        }
        return parent == null ? "<none>" : parent.getClass().getSimpleName();
    }

    private static void blockOnCarrier(
            CountDownLatch started,
            CountDownLatch release,
            AtomicReference<Thread> observed) {
        observed.set(Thread.currentThread());
        started.countDown();
        try {
            if (!release.await(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("H2B3 carrier test timed out");
            }
        } catch (InterruptedException interruption) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("H2B3 carrier was unexpectedly interrupted", interruption);
        }
    }

    private static int occurrences(String text, String needle) {
        int count = 0;
        int from = 0;
        while ((from = text.indexOf(needle, from)) >= 0) {
            count++;
            from += needle.length();
        }
        return count;
    }

    private record OptionRun(ProtosPrelude prelude, ProtosExecutionOutcome outcome) {}
}
