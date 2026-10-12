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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.guillermomolina.protos.runtime.ProtosActivation;
import com.guillermomolina.protos.runtime.ProtosArrayValue;
import com.guillermomolina.protos.runtime.ProtosFutureValue;
import com.guillermomolina.protos.runtime.ProtosIntegerValue;
import com.guillermomolina.protos.runtime.ProtosNullValue;
import com.guillermomolina.protos.runtime.ProtosPrelude;
import com.guillermomolina.protos.runtime.ProtosStringValue;
import com.guillermomolina.protos.runtime.ProtosTask;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** PERF017 focal coverage for completion-to-replacement JFR admission instrumentation. */
final class ProtosTestToolPerf017AdmissionTest {
    private static final Path CORE = Path.of("protos", "lib", "core");
    private static final Path STANDARD_LIBRARY = Path.of("protos", "lib");
    private static final Path TOOL_ROOT = Path.of("protos", "tools", "test");
    private static final Path SHARED_ROOT = Path.of("protos", "tools", "shared");

    @Test
    void eventCanBeEnabledAndKeepsStableLaneIdentity(@TempDir Path root) throws Exception {
        ProtosPrelude prelude =
                new ProtosCoreBootstrap()
                        .bootstrap(
                                CORE,
                                new ProtosStandardLibraryModuleResolver(STANDARD_LIBRARY));
        ProtosActivation activation = prelude.newModuleActivation();
        ProtosTask lane =
                activation.executionDomain()
                        .createTask(
                                null,
                                null,
                                task -> task.complete(ProtosNullValue.INSTANCE));
        activation.attachTask(lane);

        List<RecordedEvent> events =
                record(
                        root.resolve("correlation.jfr"),
                        () -> {
                            assertTrue(ProtosTestToolPerf017Admission.enabled());

                            ProtosTestToolPerf017Admission.OperationCorrelation first =
                                    ProtosTestToolPerf017Admission.operationCorrelation(activation);
                            ProtosTestToolPerf017Admission.OperationCorrelation second =
                                    ProtosTestToolPerf017Admission.operationCorrelation(activation);

                            assertNotEquals(first.operationId(), second.operationId());
                            assertEquals(first.laneId(), second.laneId());
                            assertTrue(first.operationId() > 0L);
                            assertTrue(first.laneId() > 0L);

                            ProtosTestToolPerf017Admission.emitOperation(
                                    first,
                                    ProtosTestToolPerf017Admission.HOST_CASE_DONE,
                                    "test-corpus",
                                    "suite.protos",
                                    "only");
                            ProtosTestToolPerf017Admission.emitLifecycle(
                                    activation,
                                    ProtosTestToolPerf017Admission.LIFECYCLE_STARTED,
                                    17L,
                                    "test-corpus:suite.protos::only");
                            ProtosTestToolPerf017Admission.emitLifecycle(
                                    activation,
                                    ProtosTestToolPerf017Admission.LIFECYCLE_TERMINAL,
                                    17L,
                                    null);
                        });

        assertEquals(3, events.size());
        RecordedEvent operation = events.get(0);
        assertEquals("HOST_CASE_DONE", operation.getString("phase"));
        assertTrue(operation.getLong("operationId") > 0L);
        assertTrue(operation.getLong("laneId") > 0L);
        assertEquals("test-corpus", operation.getString("corpusId"));
        assertEquals("suite.protos", operation.getString("sourcePath"));
        assertEquals("only", operation.getString("selector"));

        RecordedEvent started = events.get(1);
        RecordedEvent terminal = events.get(2);
        assertEquals("LIFECYCLE_STARTED", started.getString("phase"));
        assertEquals("LIFECYCLE_TERMINAL", terminal.getString("phase"));
        assertEquals(started.getLong("laneId"), terminal.getLong("laneId"));
        assertEquals(17L, started.getLong("lifecycleToken"));
        assertEquals(17L, terminal.getLong("lifecycleToken"));
    }

    @SuppressWarnings("try")
    @Test
    void ordinaryLogicalCaseEmitsTheOperationAdmissionSequence(@TempDir Path root)
            throws Exception {
        Path suite = root.resolve("suite.protos");
        String source =
                """
                TestValue: import("std:test/Test")
                tests: Array(
                    TestValue("only", () => {
                        22
                    })
                )
                """;
        Files.writeString(suite, source, StandardCharsets.UTF_8);

        ManualSubmission submission = new ManualSubmission();
        ProtosBundledToolModuleResolver resolver = resolver();
        ProtosPrelude prelude = new ProtosCoreBootstrap().bootstrap(CORE, resolver);
        ProtosActivation activation = prelude.newModuleActivation();

        try (ProtosPolyglotRuntimeHost runtimeHost = ProtosPolyglotRuntimeHost.open();
                ProtosTestLogicalCaseExecutionFacility facility =
                        ProtosTestLogicalCaseExecutionFacility.install(
                                activation,
                                CORE,
                                resolver,
                                List.of(
                                        new ProtosTestToolFileSelectionFacility.CorpusSourceRoot(
                                                "test-corpus", root)),
                                runtimeHost,
                                submission)) {
            List<RecordedEvent> events =
                    record(
                            root.resolve("ordinary.jfr"),
                            () -> {
                                ProtosFutureValue future =
                                        invoke(
                                                activation,
                                                prelude,
                                                ProtosTestLogicalCaseExecutionFacility.BOOTSTRAP_SLOT,
                                                "test-corpus",
                                                "suite.protos",
                                                source,
                                                List.of("only"),
                                                "only");
                                assertTrue(submission.runNext());
                                assertTrue(activation.executionDomain().dispatchOne());
                                assertEquals(ProtosFutureValue.State.RESOLVED, future.state());
                            });

            assertOperationSequence(events, "test-corpus", "suite.protos", "only");
        }
    }

    @SuppressWarnings("try")
    @Test
    void processSnapshotUsesTheSameOperationAdmissionSchema(@TempDir Path root)
            throws Exception {
        String source =
                """
                tests: Array(
                    {
                        name: "only"
                        call: () => {
                            22
                        }
                    }
                )
                """;

        ManualSubmission submission = new ManualSubmission();
        ProtosPrelude prelude = new ProtosCoreBootstrap().bootstrap(CORE, resolver());
        ProtosActivation activation = prelude.newModuleActivation();

        Files.writeString(
                root.resolve("snapshot.protos"),
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
            List<RecordedEvent> events =
                    record(
                            root.resolve("snapshot.jfr"),
                            () -> {
                                ProtosFutureValue future =
                                        invoke(
                                                activation,
                                                prelude,
                                                ProtosProcessSnapshotLogicalCaseExecutionFacility
                                                        .BOOTSTRAP_SLOT,
                                                "test-corpus",
                                                "snapshot.protos",
                                                source,
                                                List.of("only"),
                                                "only");
                                assertTrue(submission.runNext());
                                assertTrue(activation.executionDomain().dispatchOne());
                                assertEquals(ProtosFutureValue.State.RESOLVED, future.state());
                            });

            assertOperationSequence(events, "test-corpus", "snapshot.protos", "only");
        }
    }

    @SuppressWarnings("try")
    @Test
    void lifecycleSeamUsesTheCurrentLaneAndAddsNoDiagnosticOutput(@TempDir Path root)
            throws Exception {
        ProtosPrelude prelude =
                new ProtosCoreBootstrap()
                        .bootstrap(
                                CORE,
                                new ProtosStandardLibraryModuleResolver(STANDARD_LIBRARY));
        ProtosActivation activation = prelude.newModuleActivation();
        ArrayList<String> diagnostics = new ArrayList<>();

        try (ProtosTestToolStalledCaseDiagnosticFacility facility =
                ProtosTestToolStalledCaseDiagnosticFacility.install(
                        activation,
                        ProtosTestToolStalledCaseDiagnosticFacility.BOOTSTRAP_SLOT,
                        diagnostics::add,
                        TimeUnit.DAYS.toNanos(1L),
                        System::nanoTime)) {
            List<RecordedEvent> events =
                    record(
                            root.resolve("lifecycle.jfr"),
                            () -> {
                                ProtosExecutionOutcome outcome =
                                        ProtosTestExecutionSupport.execute(
                                                """
                                                caseLifecycleSink(
                                                    "started",
                                                    9,
                                                    "test-corpus:suite.protos::only"
                                                )
                                                caseLifecycleSink(
                                                    "terminal",
                                                    9,
                                                    null
                                                )
                                                null
                                                """,
                                                activation);
                                assertEquals(
                                        ProtosExecutionOutcome.State.COMPLETED,
                                        outcome.state());
                            });

            List<RecordedEvent> lifecycle =
                    events.stream()
                            .filter(event -> event.getLong("operationId") == 0L)
                            .toList();
            assertEquals(2, lifecycle.size());
            assertEquals("LIFECYCLE_STARTED", lifecycle.get(0).getString("phase"));
            assertEquals("LIFECYCLE_TERMINAL", lifecycle.get(1).getString("phase"));
            assertTrue(lifecycle.get(0).getLong("laneId") > 0L);
            assertEquals(
                    lifecycle.get(0).getLong("laneId"), lifecycle.get(1).getLong("laneId"));
            assertEquals(9L, lifecycle.get(0).getLong("lifecycleToken"));
            assertEquals(9L, lifecycle.get(1).getLong("lifecycleToken"));
            assertTrue(diagnostics.isEmpty());
        }
    }

    private static void assertOperationSequence(
            List<RecordedEvent> events, String corpusId, String sourcePath, String selector) {
        List<RecordedEvent> operations =
                events.stream().filter(event -> event.getLong("operationId") > 0L).toList();
        assertFalse(operations.isEmpty());

        long operationId = operations.get(0).getLong("operationId");
        List<RecordedEvent> operation =
                operations.stream()
                        .filter(event -> event.getLong("operationId") == operationId)
                        .toList();

        assertEquals(
                List.of(
                        "SUBMIT_ENTER",
                        "SUBMIT_RETURN",
                        "CARRIER_RUN_BEGIN",
                        "HOST_CASE_DONE",
                        "COMPLETION_TASK_BEGIN",
                        "FUTURE_TERMINAL"),
                operation.stream().map(event -> event.getString("phase")).toList());

        for (RecordedEvent event : operation) {
            assertEquals(corpusId, event.getString("corpusId"));
            assertEquals(sourcePath, event.getString("sourcePath"));
            assertEquals(selector, event.getString("selector"));
        }
    }

    private static ProtosFutureValue invoke(
            ProtosActivation activation,
            ProtosPrelude prelude,
            String slotName,
            String corpusId,
            String sourcePath,
            String source,
            List<String> signature,
            String selector) {
        Object execution = activation.context().readLocalSlot(slotName).orElseThrow();

        ProtosArrayValue sourceAssociation =
                prelude.newFrozenArray(
                        List.of(
                                new ProtosStringValue(corpusId),
                                new ProtosStringValue(sourcePath)));
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

    private static List<RecordedEvent> record(Path dump, CheckedRunnable action) throws Exception {
        try (Recording recording = new Recording()) {
            recording.enable(ProtosTestToolPerf017Admission.EVENT_NAME).withoutStackTrace();
            recording.start();
            action.run();
            recording.stop();
            recording.dump(dump);
        }

        return RecordingFile.readAllEvents(dump).stream()
                .filter(
                        event ->
                                ProtosTestToolPerf017Admission.EVENT_NAME.equals(
                                        event.getEventType().getName()))
                .toList();
    }

    private static ProtosBundledToolModuleResolver resolver() {
        return new ProtosBundledToolModuleResolver(
                "test",
                TOOL_ROOT,
                SHARED_ROOT,
                new ProtosStandardLibraryModuleResolver(STANDARD_LIBRARY));
    }

    @FunctionalInterface
    private interface CheckedRunnable {
        void run() throws Exception;
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

