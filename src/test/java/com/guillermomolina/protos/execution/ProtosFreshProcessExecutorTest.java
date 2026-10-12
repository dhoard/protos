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

import static org.junit.jupiter.api.Assertions.*;

import com.guillermomolina.protos.runtime.ProtosEncodingValue;
import com.guillermomolina.protos.runtime.ProtosEnvironmentValue;
import com.guillermomolina.protos.runtime.ProtosArrayValue;
import com.guillermomolina.protos.runtime.ProtosProcessStandardStreamBinding;
import com.guillermomolina.protos.runtime.ProtosIntegerValue;
import com.guillermomolina.protos.runtime.ProtosNetworkCapabilityValue;
import com.guillermomolina.protos.runtime.ProtosProcessCapabilityValue;
import com.guillermomolina.protos.runtime.ProtosProcessRuntime;
import com.guillermomolina.protos.runtime.ProtosPrelude;
import com.oracle.truffle.api.source.Source;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

final class ProtosFreshProcessExecutorTest {
    private static final Path CORE = Path.of("protos", "lib", "core");

    @Test
    void eachExecutionExposesFrozenOrdinaryArrayArguments() throws Exception {
        ProtosPrelude prelude = new ProtosCoreBootstrap().bootstrap(CORE);
                ProtosExecutionOutcome first =
                ProtosFreshProcessExecutor.execute(
                        request(prelude, source("process.args()")));
        ProtosExecutionOutcome second =
                ProtosFreshProcessExecutor.execute(
                        request(prelude, source("process.args()")));

        assertEquals(ProtosExecutionOutcome.State.COMPLETED, first.state());
        assertEquals(ProtosExecutionOutcome.State.COMPLETED, second.state());
        // D168: only content is portable; no identity relation between acquisitions is asserted.
        ProtosArrayValue firstArguments = assertInstanceOf(ProtosArrayValue.class, first.value());
        ProtosArrayValue secondArguments = assertInstanceOf(ProtosArrayValue.class, second.value());
        assertTrue(firstArguments.isFrozen());
        assertTrue(secondArguments.isFrozen());
        assertEquals(strings(firstArguments), strings(secondArguments));
    }

    @Test
    void rootTaskSupportsRealFutureSuspensionAndReturnsInertCompletion() throws Exception {
        ProtosPrelude prelude = new ProtosCoreBootstrap().bootstrap(CORE);
        ProtosExecutionOutcome outcome =
                ProtosFreshProcessExecutor.execute(
                        request(
                                prelude,
                                source("(() => { 42 }).future().value()")));

        assertEquals(ProtosExecutionOutcome.State.COMPLETED, outcome.state());
        ProtosIntegerValue value =
                assertInstanceOf(ProtosIntegerValue.class, outcome.value());
        assertEquals(String.valueOf("42"), com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerDecimalText(value));
        assertNull(outcome.error());
    }

    @Test
    void semanticErrorIsReturnedAsFailedOutcomeInsteadOfEscapingAsHostFailure()
            throws Exception {
        ProtosPrelude prelude = new ProtosCoreBootstrap().bootstrap(CORE);
        ProtosExecutionOutcome outcome =
                ProtosFreshProcessExecutor.execute(
                        request(
                                prelude,
                                source("1.definitelyMissing()")));

        assertEquals(ProtosExecutionOutcome.State.FAILED, outcome.state());
        assertNull(outcome.value());
        assertNotNull(outcome.error());
    }

    @Test
    void arrayMatchCaptureCompletesInFreshProcess() throws Exception {
        ProtosPrelude prelude = new ProtosCoreBootstrap().bootstrap(CORE);

        ProtosExecutionOutcome outcome =
                ProtosFreshProcessExecutor.execute(
                        request(
                                prelude,
                                source("[1, Capture].match([1, 42])")));

        assertEquals(ProtosExecutionOutcome.State.COMPLETED, outcome.state());

        ProtosArrayValue captures =
                assertInstanceOf(ProtosArrayValue.class, outcome.value());
        assertEquals(1, captures.indexedSize());

        ProtosIntegerValue captured =
                assertInstanceOf(
                        ProtosIntegerValue.class,
                        captures.indexedAt(0));
        assertEquals(42L, captured.longValue());
    }

    @Test
    void mapMatchCaptureCompletesInFreshProcess() throws Exception {
        ProtosPrelude prelude = new ProtosCoreBootstrap().bootstrap(CORE);

        ProtosExecutionOutcome outcome =
                ProtosFreshProcessExecutor.execute(
                        request(
                                prelude,
                                source(
                                        """
                                        matcher: Map()
                                        subject: Map()

                                        matcher["a"] = Capture
                                        subject["a"] = 42

                                        matcher.match(subject)
                                        """)));

        assertEquals(ProtosExecutionOutcome.State.COMPLETED, outcome.state());

        ProtosArrayValue captures =
                assertInstanceOf(ProtosArrayValue.class, outcome.value());
        assertEquals(1, captures.indexedSize());

        ProtosIntegerValue captured =
                assertInstanceOf(
                        ProtosIntegerValue.class,
                        captures.indexedAt(0));
        assertEquals(42L, captured.longValue());
    }

    @Test
    void caseOfCaptureCompletesInFreshProcess() throws Exception {
        ProtosPrelude prelude = new ProtosCoreBootstrap().bootstrap(CORE);

        ProtosExecutionOutcome outcome =
                ProtosFreshProcessExecutor.execute(
                        request(
                                prelude,
                                source(
                                        """
                                        42.caseOf(%{
                                            Capture: value => value
                                        })
                                        """)));

        assertEquals(ProtosExecutionOutcome.State.COMPLETED, outcome.state());

        ProtosIntegerValue result =
                assertInstanceOf(ProtosIntegerValue.class, outcome.value());
        assertEquals(String.valueOf("42"), com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerDecimalText(result));
    }

    @Test
    void processIsTerminatedBeforeOutcomeReturns() throws Exception {
        ProtosPrelude prelude = new ProtosCoreBootstrap().bootstrap(CORE);
        ProtosExecutionOutcome outcome =
                ProtosFreshProcessExecutor.execute(
                        request(prelude, source("process")));

        ProtosProcessCapabilityValue capability =
                assertInstanceOf(ProtosProcessCapabilityValue.class, outcome.value());
        assertEquals(
                ProtosProcessRuntime.LifecycleState.TERMINATED,
                capability.processForRuntime().lifecycleState());
    }

    @Test
    void sharedRuntimeHostMayOverlapFreshProcessesWithPrivateStreams() throws Exception {
        ProtosPrelude prelude = new ProtosCoreBootstrap().bootstrap(CORE);
        ProtosEncodingValue utf8 =
                assertInstanceOf(
                        ProtosEncodingValue.class,
                        prelude.encodingPrototype().readLocalSlot("UTF8").orElseThrow());

        CountDownLatch bothReadsStarted = new CountDownLatch(2);
        CountDownLatch releaseReads = new CountDownLatch(1);
        ProtosProcessStandardStreamBinding.ReadableBackend barrierStdin =
                (maxBytes, completion) -> {
                    bothReadsStarted.countDown();
                    boolean released = false;
                    try {
                        released = releaseReads.await(30, TimeUnit.SECONDS);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                    if (released) {
                        completion.data(new byte[] {1});
                    } else {
                        completion.failed();
                    }
                    return () -> {};
                };

        ByteArrayOutputStream firstStdout = new ByteArrayOutputStream();
        ByteArrayOutputStream secondStdout = new ByteArrayOutputStream();

        ProtosProcessStandardStreamBinding.WritableBackend firstOutput =
                capturingBackend(firstStdout);
        ProtosProcessStandardStreamBinding.WritableBackend secondOutput =
                capturingBackend(secondStdout);

        Source entry =
                source(
                        "process.stdout().write(Encoding.UTF8.encode(process.args()[0])).value()\n"
                                + "process.stdin().read(1).value()\n"
                                + "1");

        ExecutorService carriers = Executors.newFixedThreadPool(2);
        try (ProtosPolyglotRuntimeHost runtimeHost = ProtosPolyglotRuntimeHost.open()) {
            Future<ProtosExecutionOutcome> first =
                    carriers.submit(
                            () ->
                                    ProtosFreshProcessExecutor.execute(
                                            concurrentRequest(
                                                    prelude,
                                                    entry,
                                                    "first",
                                                    barrierStdin,
                                                    firstOutput,
                                                    utf8),
                                            runtimeHost));
            Future<ProtosExecutionOutcome> second =
                    carriers.submit(
                            () ->
                                    ProtosFreshProcessExecutor.execute(
                                            concurrentRequest(
                                                    prelude,
                                                    entry,
                                                    "second",
                                                    barrierStdin,
                                                    secondOutput,
                                                    utf8),
                                            runtimeHost));

            boolean overlapped = bothReadsStarted.await(30, TimeUnit.SECONDS);
            int activeWhileBlocked = runtimeHost.activeProcessContextCountForTesting();
            releaseReads.countDown();

            ProtosExecutionOutcome firstOutcome = first.get(30, TimeUnit.SECONDS);
            ProtosExecutionOutcome secondOutcome = second.get(30, TimeUnit.SECONDS);

            assertTrue(overlapped, "both fresh Processes must reach the blocking read");
            assertEquals(
                    2,
                    activeWhileBlocked,
                    "shared runtime host must have two simultaneous Process Contexts");
            assertCompletedInteger(firstOutcome, 1);
            assertCompletedInteger(secondOutcome, 1);
            assertEquals("first", firstStdout.toString(StandardCharsets.UTF_8));
            assertEquals("second", secondStdout.toString(StandardCharsets.UTF_8));
        } finally {
            releaseReads.countDown();
            carriers.shutdownNow();
            assertTrue(carriers.awaitTermination(30, TimeUnit.SECONDS));
        }
    }

    @Test
    void requestWithoutNetworkGrantLeavesInitialNetworkSlotAbsent() throws Exception {
        ProtosPrelude prelude = new ProtosCoreBootstrap().bootstrap(CORE);
        try (ProtosPolyglotRuntimeHost runtimeHost = ProtosPolyglotRuntimeHost.open()) {
            ProtosExecutionOutcome outcome =
                    ProtosFreshProcessExecutor.execute(
                            request(prelude, source("network")), runtimeHost);

            assertEquals(ProtosExecutionOutcome.State.FAILED, outcome.state());
            assertNull(outcome.value());
            assertNotNull(outcome.error());
            assertFalse(runtimeHost.networkHostInitializedForTesting());
        }
    }

    @Test
    void explicitNetworkGrantReachesInitialModuleContextUnchanged() throws Exception {
        ProtosPrelude prelude = new ProtosCoreBootstrap().bootstrap(CORE);
        try (ProtosPolyglotRuntimeHost runtimeHost = ProtosPolyglotRuntimeHost.open()) {
            ProtosNetworkCapabilityValue network = runtimeHost.provisionHostNetwork(prelude);

            ProtosExecutionOutcome outcome =
                    ProtosFreshProcessExecutor.execute(
                            request(prelude, source("network"), null, network), runtimeHost);

            assertEquals(ProtosExecutionOutcome.State.COMPLETED, outcome.state());
            assertSame(network, outcome.value());
        }
    }

    @Test
    void temporaryHostOverloadRejectsNetworkGrantBeforeGuestExecution() throws Exception {
        ProtosPrelude prelude = new ProtosCoreBootstrap().bootstrap(CORE);
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        try (ProtosPolyglotRuntimeHost owningHost = ProtosPolyglotRuntimeHost.open()) {
            ProtosNetworkCapabilityValue network = owningHost.provisionHostNetwork(prelude);
            ProtosFreshProcessExecutor.Request request =
                    request(
                            prelude,
                            source("process.stdout().write(Encoding.UTF8.encode(\"ran\")).value()"),
                            capturingBackend(stdout),
                            network);

            assertThrows(
                    IllegalArgumentException.class,
                    () -> ProtosFreshProcessExecutor.execute(request));
            assertEquals(0, stdout.size(), "guest Process must not have executed");
        }
    }

    private static ProtosFreshProcessExecutor.Request concurrentRequest(
            ProtosPrelude prelude,
            Source entry,
            String argument,
            ProtosProcessStandardStreamBinding.ReadableBackend stdin,
            ProtosProcessStandardStreamBinding.WritableBackend stdout,
            ProtosEncodingValue utf8) {
        return new ProtosFreshProcessExecutor.Request(
                prelude,
                entry,
                List.of(argument),
                exactEnvironmentDomain(),
                List.of(),
                stdin,
                stdout,
                null,
                utf8,
                utf8,
                null,
                null,
                null);
    }

    private static ProtosProcessStandardStreamBinding.WritableBackend capturingBackend(
            ByteArrayOutputStream target) {
        return (bytes, completion) -> {
            synchronized (target) {
                target.write(bytes, 0, bytes.length);
            }
            completion.succeeded();
            return () -> {};
        };
    }

    private static void assertCompletedInteger(
            ProtosExecutionOutcome outcome,
            long expected) {
        assertEquals(ProtosExecutionOutcome.State.COMPLETED, outcome.state());
        ProtosIntegerValue integer =
                assertInstanceOf(ProtosIntegerValue.class, outcome.value());
        assertEquals(Long.toString(expected), com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerDecimalText(integer));
        assertNull(outcome.error());
    }

    private static ProtosFreshProcessExecutor.Request request(
            ProtosPrelude prelude,
            Source entry) {
        return request(prelude, entry, null, null);
    }

    private static ProtosFreshProcessExecutor.Request request(
            ProtosPrelude prelude,
            Source entry,
            ProtosProcessStandardStreamBinding.WritableBackend stdout,
            ProtosNetworkCapabilityValue network) {
        return new ProtosFreshProcessExecutor.Request(
                prelude,
                entry,
                List.of("alpha", "beta"),
                exactEnvironmentDomain(),
                List.of(
                        new ProtosEnvironmentValue.NativeEntry("A", "one"),
                        new ProtosEnvironmentValue.NativeEntry("B", "two")),
                null,
                stdout,
                null,
                null,
                null,
                null,
                null,
                network);
    }

    private static Source source(String characters) {
        return Source.newBuilder(ProtosLanguage.ID, characters, "<fresh-process-test>")
                .mimeType(ProtosLanguage.MIME_TYPE)
                .build();
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

    private static List<String> strings(ProtosArrayValue array) {
        return array.indexedSnapshot().stream()
                .map(element -> ((com.guillermomolina.protos.runtime.ProtosStringValue) element).value())
                .toList();
    }
}
