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
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.guillermomolina.protos.runtime.ProtosActivation;
import com.guillermomolina.protos.runtime.ProtosArrayValue;
import com.guillermomolina.protos.runtime.ProtosBooleanValue;
import com.guillermomolina.protos.runtime.ProtosFutureValue;
import com.guillermomolina.protos.runtime.ProtosIntegerValue;
import com.guillermomolina.protos.runtime.ProtosObjectValue;
import com.guillermomolina.protos.runtime.ProtosPrelude;
import com.guillermomolina.protos.runtime.ProtosStringValue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.Test;

/**
 * D178 focal tests for the Process-snapshot Logical Case protocol adapter's selected-{@code
 * Test.call()} authority.
 *
 * <p>These tests demonstrate that {@code logicalCaseExecutionAsync} on the process-snapshot lane
 * selects one declared Test through the same {@code Discovery.resolveSelectedTest} authority the
 * ordinary suite-native lane uses, invokes only that selected Test's {@code call()} exactly once,
 * and treats its completion as the Case authority, all while continuing to rematerialize the D135
 * Process-snapshot bootstrap for every Case.
 */
final class ProtosProcessSnapshotLogicalCaseExecutionFacilityTest {
    private static final Path CORE = Path.of("protos", "lib", "core");
    private static final Path STANDARD_LIBRARY = Path.of("protos", "lib");
    private static final Path TOOL_ROOT = Path.of("protos", "tools", "test");
    private static final Path SHARED_ROOT = Path.of("protos", "tools", "shared");

    @Test
    void selectedTestBodyActuallyExecutesExactlyOnce() throws Exception {
        String source =
                """
                count: 0

                tests: Array(
                    {
                        name: "only"
                        call: () => {
                            count = count + 1
                            count
                        }
                    }
                )
                """;

        ProtosObjectValue observation =
                runOneCase(source, List.of("only"), "only");

        assertEquals("completed", stringSlot(observation, "state"));
        ProtosIntegerValue value =
                assertInstanceOf(ProtosIntegerValue.class, observation.readLocalSlot("value").orElseThrow());
        // A value other than exactly 1 would mean the selected Test body ran zero or more than
        // once instead of exactly once.
        assertEquals(String.valueOf("1"), com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerDecimalText(value));
    }

    @Test
    void failingSelectedTestBodyFailsTheCase() throws Exception {
        String source =
                """
                tests: Array(
                    {
                        name: "failing"
                        call: () => {
                            Error().signal()
                        }
                    }
                )
                """;

        ProtosObjectValue observation =
                runOneCase(source, List.of("failing"), "failing");

        assertEquals("failed", stringSlot(observation, "state"));
    }

    @Test
    void selectsTheNamedTestAndNotAnotherDeclaredTest() throws Exception {
        String source =
                """
                tests: Array(
                    {
                        name: "A"
                        call: () => {
                            "ran-A"
                        }
                    },
                    {
                        name: "B"
                        call: () => {
                            "ran-B"
                        }
                    }
                )
                """;

        ProtosObjectValue observation =
                runOneCase(source, List.of("A", "B"), "A");

        assertEquals("completed", stringSlot(observation, "state"));
        assertEquals("ran-A", stringSlot(observation, "value"));
    }

    @Test
    void signatureMismatchRejectsBeforeRunningTheSelectedTestBody() throws Exception {
        String source =
                """
                tests: Array(
                    {
                        name: "only"
                        call: () => {
                            Error().signal()
                        }
                    }
                )
                """;

        // "other" never appears in the declared signature, so Discovery must reject this Case
        // before invoking "only"'s body; that body always signals, so a "failed" completion
        // reported through the case-execution phase (rather than rematerialization-error) would
        // reveal the body ran anyway.
        ProtosCompletion completion = runCase(source, List.of("other"), "only");

        assertEquals("rematerialization-error", completion.phase());
        assertEquals("failed", stringSlot(completion.observation(), "state"));
    }

    @Test
    void selectorMismatchRejectsBeforeRunningTheSelectedTestBody() throws Exception {
        String source =
                """
                tests: Array(
                    {
                        name: "only"
                        call: () => {
                            Error().signal()
                        }
                    }
                )
                """;

        ProtosCompletion completion = runCase(source, List.of("only"), "missing");

        assertEquals("rematerialization-error", completion.phase());
        assertEquals("failed", stringSlot(completion.observation(), "state"));
    }

    @Test
    void selectedTestBodyObservesTheD135ProcessSnapshotArguments() throws Exception {
        String source = identitySource();

        ProtosObjectValue observation =
                runOneCase(source, List.of("identity"), "identity");

        assertEquals("completed", stringSlot(observation, "state"));
        assertSame(ProtosBooleanValue.TRUE, observation.readLocalSlot("value").orElseThrow());
    }

    @Test
    void selectedTestBodyObservesTheD135ProcessSnapshotEnvironment() throws Exception {
        // identitySource() asserts both process.args() and process.environment() content in the
        // same selected Test body, so this focuses the same fixture on the environment half of
        // the D135 contract.
        String source = identitySource();

        ProtosObjectValue observation =
                runOneCase(source, List.of("identity"), "identity");

        assertEquals("completed", stringSlot(observation, "state"));
        assertSame(ProtosBooleanValue.TRUE, observation.readLocalSlot("value").orElseThrow());
    }

    @SuppressWarnings("try")
    @Test
    void independentLogicalCasesRematerializeIndependentProcesses(
            @org.junit.jupiter.api.io.TempDir Path root) throws Exception {
        ManualSubmission submission = new ManualSubmission();
        ProtosPrelude prelude = new ProtosCoreBootstrap().bootstrap(CORE, fallbackResolver());
        ProtosActivation activation = prelude.newModuleActivation();
        String source = identitySource();

        Files.writeString(
                root.resolve("snapshot-identity.protos"),
                source,
                StandardCharsets.UTF_8);

        try (ProtosProcessSnapshotLogicalCaseExecutionFacility facility =
                ProtosProcessSnapshotLogicalCaseExecutionFacility.install(
                        activation,
                        prelude,
                        List.of(
                                new ProtosTestToolFileSelectionFacility.CorpusSourceRoot(
                                        "test-corpus",
                                        root)),
                        submission)) {
            ProtosFutureValue first =
                    invoke(prelude, activation, source, List.of("identity"), "identity");
            ProtosFutureValue second =
                    invoke(prelude, activation, source, List.of("identity"), "identity");

            assertEquals(2, submission.queuedCount());

            assertTrue(submission.runNext());
            assertTrue(submission.runNext());
            assertTrue(activation.executionDomain().dispatchOne());
            assertTrue(activation.executionDomain().dispatchOne());

            for (ProtosFutureValue future : List.of(first, second)) {
                assertEquals(ProtosFutureValue.State.RESOLVED, future.state());

                ProtosObjectValue completion =
                        assertInstanceOf(
                                ProtosObjectValue.class, future.resolvedValue().orElseThrow());
                ProtosObjectValue observation =
                        assertInstanceOf(
                                ProtosObjectValue.class,
                                completion.readLocalSlot("observation").orElseThrow());

                // Each Logical Case independently satisfies the Process-content assertions
                // in identitySource(), so neither invocation observed the other's
                // rematerialized Process.
                assertEquals("completed", stringSlot(observation, "state"));
                assertSame(
                        ProtosBooleanValue.TRUE, observation.readLocalSlot("value").orElseThrow());
            }
        }
    }

    private static String identitySource() {
        return """
                tests: Array(
                    {
                        name: "identity"
                        call: () => {
                            args1: process.args()
                            args2: process.args()
                            otherArgs: otherProcess.args()

                            environment1: process.environment()
                            environment2: process.environment()
                            otherEnvironment: otherProcess.environment()

                            (args1.size() == 3) &&
                                (args2.size() == 3) &&
                                (otherArgs.size() == 3) &&
                                (args1[0] == args2[0]) &&
                                (args1[0] == otherArgs[0]) &&
                                (environment1.get("A") == environment2.get("A")) &&
                                (environment1.get("A") == otherEnvironment.get("A"))
                        }
                    }
                )
                """;
    }

    private static ProtosObjectValue runOneCase(
            String source, List<String> signature, String selector) throws Exception {
        ProtosCompletion completion = runCase(source, signature, selector);
        assertEquals("case-execution", completion.phase());
        return completion.observation();
    }

    private record ProtosCompletion(String phase, ProtosObjectValue observation) {}

    @SuppressWarnings("try")
    private static ProtosCompletion runCase(
            String source, List<String> signature, String selector) throws Exception {
        ManualSubmission submission = new ManualSubmission();
        ProtosPrelude prelude = new ProtosCoreBootstrap().bootstrap(CORE, fallbackResolver());
        ProtosActivation activation = prelude.newModuleActivation();

        Path root =
                Files.createTempDirectory(
                        "protos-process-snapshot-logical-case-");

        Files.writeString(
                root.resolve("snapshot-identity.protos"),
                source,
                StandardCharsets.UTF_8);

        try (ProtosProcessSnapshotLogicalCaseExecutionFacility facility =
                ProtosProcessSnapshotLogicalCaseExecutionFacility.install(
                        activation,
                        prelude,
                        List.of(
                                new ProtosTestToolFileSelectionFacility.CorpusSourceRoot(
                                        "test-corpus",
                                        root)),
                        submission)) {
            ProtosFutureValue future = invoke(prelude, activation, source, signature, selector);

            assertEquals(ProtosFutureValue.State.PENDING, future.state());
            assertEquals(1, submission.queuedCount());
            assertTrue(submission.runNext());
            assertTrue(activation.executionDomain().dispatchOne());
            assertEquals(ProtosFutureValue.State.RESOLVED, future.state());

            ProtosObjectValue completion =
                    assertInstanceOf(
                            ProtosObjectValue.class, future.resolvedValue().orElseThrow());
            String phase = stringSlot(completion, "phase");
            ProtosObjectValue observation =
                    assertInstanceOf(
                            ProtosObjectValue.class,
                            completion.readLocalSlot("observation").orElseThrow());
            return new ProtosCompletion(phase, observation);
        }
    }

    private static String stringSlot(ProtosObjectValue object, String slotName) {
        return assertInstanceOf(
                        ProtosStringValue.class, object.readLocalSlot(slotName).orElseThrow())
                .value();
    }

    private static ProtosFutureValue invoke(
            ProtosPrelude prelude,
            ProtosActivation activation,
            String source,
            List<String> signature,
            String selector) {
        Object execution =
                activation
                        .context()
                        .readLocalSlot(
                                ProtosProcessSnapshotLogicalCaseExecutionFacility.BOOTSTRAP_SLOT)
                        .orElseThrow();

        ProtosArrayValue sourceAssociation =
                prelude.newFrozenArray(
                        List.of(
                                new ProtosStringValue("test-corpus"),
                                new ProtosStringValue("snapshot-identity.protos")));

        ProtosArrayValue signatureValue =
                prelude.newFrozenArray(
                        signature.stream()
                                .map(ProtosStringValue::new)
                                .map(value -> (Object) value)
                                .toList());

        return assertInstanceOf(
                ProtosFutureValue.class,
                ProtosInvocation.invoke(
                        execution,
                        List.of(
                                sourceAssociation,
                                signatureValue,
                                new ProtosStringValue(selector)),
                        activation));
    }

    private static ProtosBundledToolModuleResolver fallbackResolver() {
        return new ProtosBundledToolModuleResolver(
                "test", TOOL_ROOT, SHARED_ROOT, new ProtosStandardLibraryModuleResolver(STANDARD_LIBRARY));
    }

    private static final class ManualSubmission
            implements ProtosAsyncExactExecutionFacility.Submission {
        private final ArrayDeque<Job> jobs = new ArrayDeque<>();

        @Override
        public synchronized ProtosAsyncExactExecutionFacility.Submitted submit(Runnable work) {
            Job job = new Job(Objects.requireNonNull(work, "work"));
            jobs.addLast(job);
            return job;
        }

        synchronized int queuedCount() {
            return jobs.size();
        }

        boolean runNext() {
            Job job;
            synchronized (this) {
                job = jobs.pollFirst();
            }
            return job != null && job.runIfAccepted();
        }

        private static final class Job implements ProtosAsyncExactExecutionFacility.Submitted {
            private final Runnable work;
            private boolean started;
            private boolean cancelled;

            private Job(Runnable work) {
                this.work = work;
            }

            @Override
            public synchronized boolean cancelIfNotStarted() {
                if (started || cancelled) {
                    return false;
                }
                cancelled = true;
                return true;
            }

            synchronized boolean runIfAccepted() {
                if (started || cancelled) {
                    return false;
                }
                started = true;
                work.run();
                return true;
            }
        }
    }
}
