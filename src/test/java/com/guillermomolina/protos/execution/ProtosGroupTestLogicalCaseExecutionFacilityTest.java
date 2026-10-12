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
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.guillermomolina.protos.runtime.ProtosActivation;
import com.guillermomolina.protos.runtime.ProtosArrayValue;
import com.guillermomolina.protos.runtime.ProtosFutureValue;
import com.guillermomolina.protos.runtime.ProtosIntegerValue;
import com.guillermomolina.protos.runtime.ProtosModuleKey;
import com.guillermomolina.protos.runtime.ProtosObjectValue;
import com.guillermomolina.protos.runtime.ProtosPrelude;
import com.guillermomolina.protos.runtime.ProtosStringValue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * TOOL009-C Slice 4 infrastructure: focal coverage for the Group-flavored suite-native logical
 * Case execution route.
 *
 * <p>These tests exercise the ordinary {@link ProtosTestLogicalCaseExecutionFacility} /
 * {@link ProtosTestLogicalCaseAttemptBridge} machinery parameterized with a Group-specific
 * fallback resolver (the same "workers" module overlay the legacy {@code groupExecutionAsync}
 * facility resolves through {@code groupPrelude}), demonstrating that the suite-native route can
 * resolve and execute Group-flavored suites without a parallel Case authority. None of the 10
 * corpus fixtures under {@code protos/tests/conformance/group} are used here; every source is a
 * dedicated inline fixture for this infrastructure.
 */
final class ProtosGroupTestLogicalCaseExecutionFacilityTest {
    private static final Path CORE = Path.of("protos", "lib", "core");
    private static final Path STANDARD_LIBRARY = Path.of("protos", "lib");
    private static final Path TOOL_ROOT = Path.of("protos", "tools", "test");
    private static final Path SHARED_ROOT = Path.of("protos", "tools", "shared");
    private static final Path GROUP_WORKERS_MODULE =
            Path.of(
                    "protos", "tests", "conformance", "group", "modules", "workers.protos");

    private static final String SOURCE_ONE_REQUEST =
            """
            TestValue: import("std:test/Test")

            tests: Array(
                TestValue("resolvesTag", () => {
                    worker: Actor.spawn("workers", "tagged", 1)
                    group: Actor.group(worker)
                    group.request("tag").value()
                })
            )
            """;

    private static final String SOURCE_TWO_REQUESTS_SAME_GROUP =
            """
            TestValue: import("std:test/Test")

            tests: Array(
                TestValue("stateful", () => {
                    worker: Actor.spawn("workers", "tagged", 5)
                    group: Actor.group(worker)
                    first: group.request("tag").value()
                    second: group.request("tag").value()
                    first * 10 + second
                })
            )
            """;

    @Test
    void resolvesGroupWorkersOverlayAndExecutesSelectedTestExactlyOnce(@TempDir Path root)
            throws Exception {
        writeSuite(root, SOURCE_ONE_REQUEST);

        ManualSubmission submission = new ManualSubmission();
        ProtosModuleResolver resolver = groupResolver();
        ProtosPrelude prelude = new ProtosCoreBootstrap().bootstrap(CORE, resolver);
        ProtosActivation activation = prelude.newModuleActivation();

        try (ProtosPolyglotRuntimeHost runtimeHost = ProtosPolyglotRuntimeHost.open();
                ProtosTestLogicalCaseExecutionFacility facility =
                        ProtosTestLogicalCaseExecutionFacility.install(
                                activation,
                                "groupLogicalCaseExecutionAsync",
                                CORE,
                                resolver,
                                List.of(
                                        new ProtosTestToolFileSelectionFacility.CorpusSourceRoot(
                                                "group-corpus", root)),
                                runtimeHost,
                                submission)) {

            ProtosFutureValue future =
                    invoke(
                            facility,
                            prelude,
                            activation,
                            SOURCE_ONE_REQUEST,
                            List.of("resolvesTag"),
                            "resolvesTag");

            // Exactly one host execution is enqueued for the one selected Test.
            assertEquals(1, submission.queuedCount());
            assertTrue(submission.runNext());
            assertFalse(submission.runNext(), "no second host execution for the same Case");

            assertTrue(activation.executionDomain().dispatchOne());

            ProtosIntegerValue value = assertCompletedIntegerObservation(future);

            // A value of exactly 1 is only reachable if Actor.group(...) routed the
            // request to the one member spawned through the Group-flavored "workers"
            // module overlay (not the ordinary test resolver, which has no such
            // specifier) and the selected Test body actually executed exactly once.
            assertEquals(String.valueOf("1"), com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerDecimalText(value));
        }
    }

    @Test
    void multipleGroupRequestsWithinOneCaseObserveIntraCaseGroupState(@TempDir Path root)
            throws Exception {
        writeSuite(root, SOURCE_TWO_REQUESTS_SAME_GROUP);

        ManualSubmission submission = new ManualSubmission();
        ProtosModuleResolver resolver = groupResolver();
        ProtosPrelude prelude = new ProtosCoreBootstrap().bootstrap(CORE, resolver);
        ProtosActivation activation = prelude.newModuleActivation();

        try (ProtosPolyglotRuntimeHost runtimeHost = ProtosPolyglotRuntimeHost.open();
                ProtosTestLogicalCaseExecutionFacility facility =
                        ProtosTestLogicalCaseExecutionFacility.install(
                                activation,
                                "groupLogicalCaseExecutionAsync",
                                CORE,
                                resolver,
                                List.of(
                                        new ProtosTestToolFileSelectionFacility.CorpusSourceRoot(
                                                "group-corpus", root)),
                                runtimeHost,
                                submission)) {

            ProtosFutureValue future =
                    invoke(
                            facility,
                            prelude,
                            activation,
                            SOURCE_TWO_REQUESTS_SAME_GROUP,
                            List.of("stateful"),
                            "stateful");

            assertTrue(submission.runNext());
            assertTrue(activation.executionDomain().dispatchOne());

            ProtosIntegerValue value = assertCompletedIntegerObservation(future);

            // 5 then 5, combined as first * 10 + second: only reachable if both
            // requests were routed through the same Group/member acquisition within
            // this one Case.
            assertEquals(String.valueOf("55"), com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerDecimalText(value));
        }
    }

    @Test
    void eachCaseGetsAFreshProcessAndIsolatedGroupStateNotSharedWithAPriorCase(
            @TempDir Path root) throws Exception {
        writeSuite(root, SOURCE_ONE_REQUEST);

        ProtosModuleResolver resolver = groupResolver();
        ProtosPrelude prelude = new ProtosCoreBootstrap().bootstrap(CORE, resolver);
        ProtosActivation activation = prelude.newModuleActivation();
        ManualSubmission submission = new ManualSubmission();

        try (ProtosPolyglotRuntimeHost runtimeHost = ProtosPolyglotRuntimeHost.open();
                ProtosTestLogicalCaseExecutionFacility facility =
                        ProtosTestLogicalCaseExecutionFacility.install(
                                activation,
                                "groupLogicalCaseExecutionAsync",
                                CORE,
                                resolver,
                                List.of(
                                        new ProtosTestToolFileSelectionFacility.CorpusSourceRoot(
                                                "group-corpus", root)),
                                runtimeHost,
                                submission)) {

            ProtosFutureValue firstCase =
                    invoke(
                            facility,
                            prelude,
                            activation,
                            SOURCE_ONE_REQUEST,
                            List.of("resolvesTag"),
                            "resolvesTag");
            assertTrue(submission.runNext());
            assertTrue(activation.executionDomain().dispatchOne());
            assertEquals(String.valueOf("1"), com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerDecimalText(assertCompletedIntegerObservation(firstCase)));

            ProtosFutureValue secondCase =
                    invoke(
                            facility,
                            prelude,
                            activation,
                            SOURCE_ONE_REQUEST,
                            List.of("resolvesTag"),
                            "resolvesTag");
            assertTrue(submission.runNext());
            assertTrue(activation.executionDomain().dispatchOne());

            // If the second Case reused the first Case's Process/Group acquisition,
            // the fixture's own spawn/group calls would collide with (or observe)
            // leftover state from the first Case's fresh Actor. Each Case spawns its
            // own worker and forms its own Group, so both Cases observe the same
            // freshly-tagged value of 1.
            assertEquals(String.valueOf("1"), com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerDecimalText(assertCompletedIntegerObservation(secondCase)));
        }
    }

    @Test
    void selectorMismatchIsRejectedWithoutExecutingTheBody(@TempDir Path root)
            throws Exception {
        writeSuite(root, SOURCE_ONE_REQUEST);

        ManualSubmission submission = new ManualSubmission();
        ProtosModuleResolver resolver = groupResolver();
        ProtosPrelude prelude = new ProtosCoreBootstrap().bootstrap(CORE, resolver);
        ProtosActivation activation = prelude.newModuleActivation();

        try (ProtosPolyglotRuntimeHost runtimeHost = ProtosPolyglotRuntimeHost.open();
                ProtosTestLogicalCaseExecutionFacility facility =
                        ProtosTestLogicalCaseExecutionFacility.install(
                                activation,
                                "groupLogicalCaseExecutionAsync",
                                CORE,
                                resolver,
                                List.of(
                                        new ProtosTestToolFileSelectionFacility.CorpusSourceRoot(
                                                "group-corpus", root)),
                                runtimeHost,
                                submission)) {

            ProtosFutureValue future =
                    invoke(
                            facility,
                            prelude,
                            activation,
                            SOURCE_ONE_REQUEST,
                            List.of("resolvesTag"),
                            "doesNotExist");

            assertTrue(submission.runNext());
            assertTrue(activation.executionDomain().dispatchOne());

            ProtosObjectValue completion =
                    assertInstanceOf(
                            ProtosObjectValue.class, future.resolvedValue().orElseThrow());
            ProtosStringValue phase =
                    assertInstanceOf(
                            ProtosStringValue.class,
                            completion.readLocalSlot("phase").orElseThrow());
            assertEquals("rematerialization-error", phase.value());
        }
    }

    @Test
    void signatureMismatchIsRejectedWithoutExecutingTheBody(@TempDir Path root)
            throws Exception {
        String source =
                """
                TestValue: import("std:test/Test")

                tests: Array(
                    TestValue("second", () => {
                        worker: Actor.spawn("workers", "tagged", 1)
                        group: Actor.group(worker)
                        group.request("tag").value()
                    }),
                    TestValue("first", () => {
                        worker: Actor.spawn("workers", "tagged", 1)
                        group: Actor.group(worker)
                        group.request("tag").value()
                    })
                )
                """;

        writeSuite(root, source);

        ManualSubmission submission = new ManualSubmission();
        ProtosModuleResolver resolver = groupResolver();
        ProtosPrelude prelude = new ProtosCoreBootstrap().bootstrap(CORE, resolver);
        ProtosActivation activation = prelude.newModuleActivation();

        try (ProtosPolyglotRuntimeHost runtimeHost = ProtosPolyglotRuntimeHost.open();
                ProtosTestLogicalCaseExecutionFacility facility =
                        ProtosTestLogicalCaseExecutionFacility.install(
                                activation,
                                "groupLogicalCaseExecutionAsync",
                                CORE,
                                resolver,
                                List.of(
                                        new ProtosTestToolFileSelectionFacility.CorpusSourceRoot(
                                                "group-corpus", root)),
                                runtimeHost,
                                submission)) {

            ProtosFutureValue future =
                    invoke(
                            facility,
                            prelude,
                            activation,
                            source,
                            // declared order is second, first: this expects the opposite order,
                            // demonstrating that Test discovery order is observational evidence
                            // used to validate the selection, not the selection authority itself.
                            List.of("first", "second"),
                            "second");

            assertTrue(submission.runNext());
            assertTrue(activation.executionDomain().dispatchOne());

            ProtosObjectValue completion =
                    assertInstanceOf(
                            ProtosObjectValue.class, future.resolvedValue().orElseThrow());
            ProtosStringValue phase =
                    assertInstanceOf(
                            ProtosStringValue.class,
                            completion.readLocalSlot("phase").orElseThrow());
            assertEquals("rematerialization-error", phase.value());
        }
    }

    private static void writeSuite(Path root, String source) throws Exception {
        Files.writeString(root.resolve("suite.protos"), source, StandardCharsets.UTF_8);
    }

    private static ProtosIntegerValue assertCompletedIntegerObservation(
            ProtosFutureValue future) {
        ProtosObjectValue completion =
                assertInstanceOf(ProtosObjectValue.class, future.resolvedValue().orElseThrow());
        ProtosStringValue phase =
                assertInstanceOf(
                        ProtosStringValue.class, completion.readLocalSlot("phase").orElseThrow());
        assertEquals("case-execution", phase.value());

        ProtosObjectValue observation =
                assertInstanceOf(
                        ProtosObjectValue.class,
                        completion.readLocalSlot("observation").orElseThrow());
        ProtosStringValue state =
                assertInstanceOf(
                        ProtosStringValue.class, observation.readLocalSlot("state").orElseThrow());
        assertEquals("completed", state.value());

        return assertInstanceOf(
                ProtosIntegerValue.class, observation.readLocalSlot("value").orElseThrow());
    }

    private static ProtosFutureValue invoke(
            ProtosTestLogicalCaseExecutionFacility facility,
            ProtosPrelude prelude,
            ProtosActivation activation,
            String source,
            List<String> signature,
            String selector) {
        Object execution =
                activation
                        .context()
                        .readLocalSlot("groupLogicalCaseExecutionAsync")
                        .orElseThrow();

        ProtosArrayValue sourceAssociation =
                prelude.newFrozenArray(
                        List.of(
                                new ProtosStringValue("group-corpus"),
                                new ProtosStringValue("suite.protos")));

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

    private static ProtosModuleResolver groupResolver() {
        ProtosModuleResolver standardLibraryResolver =
                new ProtosStandardLibraryModuleResolver(STANDARD_LIBRARY);
        ProtosModuleResolver ordinaryTestResolver =
                new ProtosBundledToolModuleResolver(
                        "test", TOOL_ROOT, SHARED_ROOT, standardLibraryResolver);
        return new ProtosExactModuleOverlayResolver(
                Map.of(
                        "workers",
                        new ProtosExactModuleOverlayResolver.ExactModule(
                                new ProtosModuleKey("tool002-group:workers"),
                                GROUP_WORKERS_MODULE)),
                ordinaryTestResolver);
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
