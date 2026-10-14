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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.guillermomolina.protos.parser.ProtosParser;
import com.guillermomolina.protos.runtime.ProtosActivation;
import com.guillermomolina.protos.runtime.ProtosActor;
import com.guillermomolina.protos.runtime.ProtosActorExecutionDomain;
import com.guillermomolina.protos.runtime.ProtosActorModuleState;
import com.guillermomolina.protos.runtime.ProtosActorScheduler;
import com.guillermomolina.protos.runtime.ProtosClosureValue;
import com.guillermomolina.protos.runtime.ProtosCoreErrors;
import com.guillermomolina.protos.runtime.ProtosFutureValue;
import com.guillermomolina.protos.runtime.ProtosObjectValue;
import com.guillermomolina.protos.runtime.ProtosPrelude;
import com.guillermomolina.protos.runtime.ProtosTask;
import com.guillermomolina.protos.semantic.Canonicalizer;
import com.guillermomolina.protos.semantic.ast.CanonicalClosure;
import com.guillermomolina.protos.semantic.ast.CanonicalSequence;
import com.oracle.truffle.api.TruffleLanguage.LanguageReference;
import com.oracle.truffle.api.source.Source;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;

final class ProtosPerf006B3FClosureEvidenceTest {
    private static final LanguageReference<ProtosLanguage> LANGUAGE_REF =
            LanguageReference.create(ProtosLanguage.class);

    @Test
    void pendingFutureTerminalCancellationResumesValueWithFreshCancelledOccurrence()
            throws Exception {
        try (LanguageScope scope = languageScope()) {
            ProtosLanguage language = scope.language();
            ProtosPrelude prelude = core();
            ProtosActorExecutionDomain domain = new ProtosActorExecutionDomain();
            ProtosFutureValue future =
                    new ProtosFutureValue(prelude.futurePrototype(), domain);
            ProtosSemanticBytecodeRootNode root =
                    lowerRoot(
                            language,
                            "f.value()",
                            "perf006-b3f-pending-future-cancel.protos");

            ProtosActivation firstActivation = activation(prelude, domain);
            firstActivation.context().createLocalSlot("f", future);
            ProtosTask first = execute(domain, firstActivation, root);

            assertTrue(domain.dispatchOne());
            assertEquals(ProtosTask.State.SUSPENDED, first.state());
            assertEquals(ProtosFutureValue.State.PENDING, future.state());

            assertTrue(future.cancelTerminal());
            assertEquals(ProtosFutureValue.State.CANCELLED, future.state());
            assertEquals(ProtosTask.State.RUNNABLE, first.state());
            assertTrue(domain.dispatchOne());
            assertEquals(ProtosTask.State.FAILED, first.state());
            Object firstError = first.failure().orElseThrow();
            assertSame(
                    ProtosCoreErrors.prototype(
                            firstActivation,
                            ProtosCoreErrors.StandardError.CANCELLED),
                    ((ProtosObjectValue) firstError).parent().orElseThrow());

            ProtosActivation secondActivation = activation(prelude, domain);
            secondActivation.context().createLocalSlot("f", future);
            ProtosTask second = execute(domain, secondActivation, root);
            assertTrue(domain.dispatchOne());
            assertEquals(ProtosTask.State.FAILED, second.state());
            Object secondError = second.failure().orElseThrow();

            assertNotSame(
                    firstError,
                    secondError,
                    "pending cancellation and later observation must project distinct Cancelled occurrences");
            assertSame(
                    ProtosCoreErrors.prototype(
                            secondActivation,
                            ProtosCoreErrors.StandardError.CANCELLED),
                    ((ProtosObjectValue) secondError).parent().orElseThrow());
        }

        System.out.println("PERF006_B3F_PENDING_FUTURE_CANCEL_RESUME=PASS");
        System.out.println("PERF006_B3F_PENDING_CANCEL_FRESH_ERROR=PASS");
    }

    @Test
    void manySuspendedFutureValuesReleaseAndReuseOneBoundedCarrierWorker()
            throws Exception {
        try (LanguageScope scope = languageScope()) {
            ProtosLanguage language = scope.language();
            ProtosPrelude prelude = core();
            ManualExecutor carriers = new ManualExecutor();
            ProtosActor actor = new ProtosActor(prelude.actorRefPrototypeForRuntime());
            ProtosActorScheduler scheduler = new ProtosActorScheduler(carriers, 1);
            scheduler.attach(actor);
            assertTrue(actor.markReady());

            ProtosActorExecutionDomain domain = actor.executionDomain();
            ProtosSemanticBytecodeRootNode root =
                    lowerRoot(
                            language,
                            "f.value()",
                            "perf006-b3f-many-suspended.protos");
            ProtosObjectValue resolved =
                    new ProtosObjectValue(ProtosObjectValue.rootObject());
            List<ProtosFutureValue> futures = new ArrayList<>();
            List<ProtosActivation> activations = new ArrayList<>();
            List<ProtosTask> tasks = new ArrayList<>();

            final int taskCount = 64;
            for (int index = 0; index < taskCount; index++) {
                ProtosActivation currentActivation = activation(prelude, domain);
                ProtosFutureValue future =
                        new ProtosFutureValue(prelude.futurePrototype(), domain);
                currentActivation.context().createLocalSlot("f", future);
                activations.add(currentActivation);
                futures.add(future);
                tasks.add(execute(domain, currentActivation, root));
            }

            assertEquals(
                    1,
                    carriers.size(),
                    "one Actor on a one-carrier scheduler must need one carrier worker regardless of Task count");
            carriers.runNext();

            assertEquals(0, carriers.size());
            assertEquals(taskCount, domain.liveTaskCount());
            assertTrue(tasks.stream().allMatch(task -> task.state() == ProtosTask.State.SUSPENDED));

            AtomicInteger unrelatedProbeRuns = new AtomicInteger();
            ProtosTask unrelated =
                    domain.createTask(
                            null,
                            current -> {
                                unrelatedProbeRuns.incrementAndGet();
                                current.complete(resolved);
                            });
            assertEquals(
                    1,
                    carriers.size(),
                    "suspended logical Tasks must leave the bounded carrier substrate reusable");
            carriers.runNext();
            assertEquals(1, unrelatedProbeRuns.get());
            assertEquals(ProtosTask.State.COMPLETED, unrelated.state());
            assertTrue(tasks.stream().allMatch(task -> task.state() == ProtosTask.State.SUSPENDED));

            for (int index = 0; index < taskCount; index++) {
                assertTrue(futures.get(index).resolve(resolved, activations.get(index)));
            }
            assertEquals(
                    1,
                    carriers.size(),
                    "waking many Tasks must still coalesce onto the bounded one-worker carrier substrate");
            carriers.runNext();

            assertEquals(0, carriers.size());
            assertTrue(tasks.stream().allMatch(task -> task.state() == ProtosTask.State.COMPLETED));
            for (ProtosTask task : tasks) {
                assertSame(resolved, task.result().orElseThrow());
            }
        }

        System.out.println("PERF006_B3F_SUSPENDED_TASKS_RELEASE_CARRIER=PASS");
        System.out.println("PERF006_B3F_MANY_TASKS_NO_LINEAR_CARRIER_GROWTH=PASS");
        System.out.println("PERF006_B3F_CARRIER_REUSE_WHILE_TASKS_SUSPENDED=PASS");
    }

    @Test
    void completedTargetAndArgumentEffectsDoNotReplayAcrossRealFutureSuspension()
            throws Exception {
        try (LanguageScope scope = languageScope()) {
            ProtosLanguage language = scope.language();
            Harness harness = harness();
            ProtosFutureValue future = harness.future();
            ProtosObjectValue argumentValue =
                    new ProtosObjectValue(ProtosObjectValue.rootObject());
            ProtosObjectValue resolved =
                    new ProtosObjectValue(ProtosObjectValue.rootObject());
            AtomicInteger makerCalls = new AtomicInteger();
            AtomicInteger argumentCalls = new AtomicInteger();
            AtomicInteger targetCalls = new AtomicInteger();

            ProtosClosureValue target =
                    taskNative(
                            () -> {
                                targetCalls.incrementAndGet();
                                return null;
                            },
                            (activation, supplied) -> {
                                targetCalls.incrementAndGet();
                                assertEquals(2, supplied.size());
                                assertSame(argumentValue, supplied.get(0));
                                assertSame(resolved, supplied.get(1));
                                return supplied.get(1);
                            });
            harness.activation().context().createLocalSlot(
                    "maker",
                    taskNative(
                            () -> {
                                makerCalls.incrementAndGet();
                                return target;
                            },
                            (activation, supplied) -> {
                                makerCalls.incrementAndGet();
                                return target;
                            }));
            harness.activation().context().createLocalSlot(
                    "argument",
                    taskNative(
                            () -> {
                                argumentCalls.incrementAndGet();
                                return argumentValue;
                            },
                            (activation, supplied) -> {
                                argumentCalls.incrementAndGet();
                                return argumentValue;
                            }));
            harness.activation().context().createLocalSlot("f", future);

            ProtosTask task =
                    execute(
                            harness,
                            lowerRoot(
                                    language,
                                    "maker()(argument(), f.value())",
                                    "perf006-b3f-target-argument-no-replay.protos"));

            assertTrue(harness.domain().dispatchOne());
            assertEquals(ProtosTask.State.SUSPENDED, task.state());
            assertEquals(1, makerCalls.get());
            assertEquals(1, argumentCalls.get());
            assertEquals(0, targetCalls.get());

            assertTrue(future.resolve(resolved, harness.activation()));
            assertTrue(harness.domain().dispatchOne());

            assertEquals(ProtosTask.State.COMPLETED, task.state());
            assertSame(resolved, task.result().orElseThrow());
            assertEquals(1, makerCalls.get());
            assertEquals(1, argumentCalls.get());
            assertEquals(1, targetCalls.get());
        }

        System.out.println("PERF006_B3F_COMPLETED_TARGET_NO_REPLAY=PASS");
        System.out.println("PERF006_B3F_COMPLETED_ARGUMENT_NO_REPLAY=PASS");
    }

    @Test
    void completedSendReceiverAndSpreadEffectsDoNotReplayAcrossRealFutureSuspension()
            throws Exception {
        try (LanguageScope scope = languageScope()) {
            ProtosLanguage language = scope.language();
            Harness harness = harness();
            ProtosFutureValue future = harness.future();
            ProtosObjectValue receiver =
                    new ProtosObjectValue(ProtosObjectValue.rootObject());
            ProtosObjectValue spreadElement =
                    new ProtosObjectValue(ProtosObjectValue.rootObject());
            ProtosObjectValue resolved =
                    new ProtosObjectValue(ProtosObjectValue.rootObject());
            Object spread = harness.prelude().newArray(List.of(spreadElement));
            AtomicInteger receiverCalls = new AtomicInteger();
            AtomicInteger spreadCalls = new AtomicInteger();
            AtomicInteger methodCalls = new AtomicInteger();
            AtomicReference<List<?>> seenArguments = new AtomicReference<>();

            receiver.createLocalSlot(
                    "capture",
                    taskNative(
                            () -> {
                                methodCalls.incrementAndGet();
                                return resolved;
                            },
                            (activation, supplied) -> {
                                methodCalls.incrementAndGet();
                                seenArguments.set(List.copyOf(supplied));
                                return supplied.get(1);
                            }));
            harness.activation().context().createLocalSlot(
                    "receiverProducer",
                    taskNative(
                            () -> {
                                receiverCalls.incrementAndGet();
                                return receiver;
                            },
                            (activation, supplied) -> {
                                receiverCalls.incrementAndGet();
                                return receiver;
                            }));
            harness.activation().context().createLocalSlot(
                    "itemsProducer",
                    taskNative(
                            () -> {
                                spreadCalls.incrementAndGet();
                                return spread;
                            },
                            (activation, supplied) -> {
                                spreadCalls.incrementAndGet();
                                return spread;
                            }));
            harness.activation().context().createLocalSlot("f", future);

            ProtosTask task =
                    execute(
                            harness,
                            lowerRoot(
                                    language,
                                    "receiverProducer().capture(...itemsProducer(), f.value())",
                                    "perf006-b3f-send-spread-no-replay.protos"));

            assertTrue(harness.domain().dispatchOne());
            assertEquals(ProtosTask.State.SUSPENDED, task.state());
            assertEquals(1, receiverCalls.get());
            assertEquals(1, spreadCalls.get());
            assertEquals(0, methodCalls.get());

            assertTrue(future.resolve(resolved, harness.activation()));
            assertTrue(harness.domain().dispatchOne());

            assertEquals(ProtosTask.State.COMPLETED, task.state());
            assertSame(resolved, task.result().orElseThrow());
            assertEquals(1, receiverCalls.get());
            assertEquals(1, spreadCalls.get());
            assertEquals(1, methodCalls.get());
            assertEquals(List.of(spreadElement, resolved), seenArguments.get());
        }

        System.out.println("PERF006_B3F_COMPLETED_SEND_RECEIVER_NO_REPLAY=PASS");
        System.out.println("PERF006_B3F_COMPLETED_SPREAD_NO_REPLAY=PASS");
    }

    @Test
    void completedDefaultEffectDoesNotReplayAcrossRealFutureSuspension()
            throws Exception {
        try (LanguageScope scope = languageScope()) {
            ProtosLanguage language = scope.language();
            Harness harness = harness();
            ProtosFutureValue future = harness.future();
            ProtosObjectValue defaultValue =
                    new ProtosObjectValue(ProtosObjectValue.rootObject());
            ProtosObjectValue futureValue =
                    new ProtosObjectValue(ProtosObjectValue.rootObject());
            AtomicInteger defaultCalls = new AtomicInteger();

            harness.activation().context().createLocalSlot(
                    "defaultProducer",
                    taskNative(
                            () -> {
                                defaultCalls.incrementAndGet();
                                return defaultValue;
                            },
                            (activation, supplied) -> {
                                defaultCalls.incrementAndGet();
                                return defaultValue;
                            }));
            harness.activation().context().createLocalSlot("f", future);
            harness.activation().context().createLocalSlot(
                    "owner",
                    bytecodeClosure(
                            language,
                            harness.activation(),
                            "(value = defaultProducer()) => {\n"
                                    + "    f.value()\n"
                                    + "    value\n"
                                    + "}",
                            "perf006-b3f-default-owner.protos"));

            ProtosTask task =
                    execute(
                            harness,
                            lowerRoot(
                                    language,
                                    "owner()",
                                    "perf006-b3f-default-no-replay.protos"));

            assertTrue(harness.domain().dispatchOne());
            assertEquals(ProtosTask.State.SUSPENDED, task.state());
            assertEquals(1, defaultCalls.get());

            assertTrue(future.resolve(futureValue, harness.activation()));
            assertTrue(harness.domain().dispatchOne());

            assertEquals(ProtosTask.State.COMPLETED, task.state());
            assertSame(defaultValue, task.result().orElseThrow());
            assertEquals(1, defaultCalls.get());
        }

        System.out.println("PERF006_B3F_COMPLETED_DEFAULT_NO_REPLAY=PASS");
    }

    @Test
    void continuationPublicationAuthorityRemainsTaskLocalWithoutTaskThreadLocalOrGlobalRegistry()
            throws Exception {
        for (String fieldName :
                List.of(
                        "capturePendingDependency",
                        "captureWakeRecorded",
                        "publishedSuspensionContinuation")) {
            Field field = ProtosTask.class.getDeclaredField(fieldName);
            assertFalse(
                    Modifier.isStatic(field.getModifiers()),
                    fieldName + " must remain per-Task state");
        }

        List<Path> authoritySources =
                List.of(
                        Path.of(
                                "src/main/java/com/guillermomolina/protos/runtime/ProtosTask.java"),
                        Path.of(
                                "src/main/java/com/guillermomolina/protos/execution/ProtosBytecodeTaskExecution.java"),
                        Path.of(
                                "src/main/java/com/guillermomolina/protos/execution/ProtosNativeSuspension.java"),
                        Path.of(
                                "src/main/java/com/guillermomolina/protos/runtime/ProtosFutureValue.java"));
        Pattern staticTaskRegistry =
                Pattern.compile(
                        "(?m)^\\s*(?:public|protected|private)?\\s*static\\s+(?:final\\s+)?[^;]*(?:Map|Set|Collection|ConcurrentMap|ConcurrentHashMap|IdentityHashMap)[^;]*(?:ProtosTask|Continuation|WaitDependency)[^;]*;");
        Pattern staticTaskLock =
                Pattern.compile(
                        "(?m)^\\s*(?:public|protected|private)?\\s*static\\s+(?:final\\s+)?Object\\s+[^;]*(?:lock|monitor|continuation)[^;]*;",
                        Pattern.CASE_INSENSITIVE);

        for (Path source : authoritySources) {
            String text = Files.readString(source);
            assertFalse(
                    text.contains("ThreadLocal"),
                    source + " must not acquire hidden Task/activation ThreadLocal authority");
            assertFalse(
                    staticTaskRegistry.matcher(text).find(),
                    source + " must not introduce a global Task/continuation registry");
            assertFalse(
                    staticTaskLock.matcher(text).find(),
                    source + " must not introduce a global Task/continuation lock");
        }

        System.out.println("PERF006_B3F_TASK_LOCAL_CONTINUATION_PUBLICATION=PASS");
        System.out.println("PERF006_B3F_GLOBAL_CONTINUATION_REGISTRY=NO");
        System.out.println("PERF006_B3F_THREADLOCAL_TASK_AUTHORITY=NO");
    }

    private static ProtosClosureValue taskNative(
            java.util.function.Supplier<Object> ordinary,
            com.guillermomolina.protos.runtime.ProtosNativeClosureBody continuation) {
        return ProtosClosureValue.suspensionCapableNativeClosure(
                (activation, supplied) -> ordinary.get(),
                continuation);
    }

    private static LanguageScope languageScope() {
        Context context = Context.newBuilder(ProtosLanguage.ID).build();
        context.initialize(ProtosLanguage.ID);
        context.enter();
        return new LanguageScope(context, LANGUAGE_REF.get(null));
    }

    private static ProtosPrelude core() throws Exception {
        return new ProtosCoreBootstrap().bootstrap(Path.of("protos", "lib", "core"));
    }

    private static Harness harness() throws Exception {
        ProtosPrelude prelude = core();
        ProtosActorExecutionDomain domain = new ProtosActorExecutionDomain();
        return new Harness(prelude, domain, activation(prelude, domain));
    }

    private static ProtosActivation activation(
            ProtosPrelude prelude,
            ProtosActorExecutionDomain domain) {
        return prelude.newModuleActivation(
                new ProtosActorModuleState(),
                null,
                prelude.newExecutionContext(),
                domain);
    }

    private static ProtosTask execute(
            Harness harness,
            ProtosSemanticBytecodeRootNode root) {
        return execute(harness.domain(), harness.activation(), root);
    }

    private static ProtosTask execute(
            ProtosActorExecutionDomain domain,
            ProtosActivation activation,
            ProtosSemanticBytecodeRootNode root) {
        return domain.createTask(
                null,
                current ->
                        ProtosBytecodeTaskExecution.execute(
                                current,
                                root.getCallTarget(),
                                activation));
    }

    private static ProtosSemanticBytecodeRootNode lowerRoot(
            ProtosLanguage language,
            String characters,
            String sourceName)
            throws Exception {
        Source source =
                Source.newBuilder(ProtosLanguage.ID, characters, sourceName).build();
        return new CanonicalToBytecodeLowerer(language, source, ProtosExecutionMode.NORMAL)
                .lowerRoot(canonicalize(characters));
    }

    private static ProtosClosureValue bytecodeClosure(
            ProtosLanguage language,
            ProtosActivation creator,
            String characters,
            String sourceName)
            throws Exception {
        Source source =
                Source.newBuilder(ProtosLanguage.ID, characters, sourceName).build();
        CanonicalClosure definition = closureDefinition(characters);
        ProtosClosureExecutionPlan plan =
                ProtosClosureExecutionPlan.bytecode(definition, language, source, ProtosExecutionMode.NORMAL);
        return new ProtosClosureValue(
                definition,
                creator.lexicalContextsForClosureCapture(),
                creator.receiver(),
                creator.methodHome().orElse(null),
                creator.returnHome().orElse(null),
                creator.prelude().orElseThrow(),
                plan);
    }

    private static CanonicalClosure closureDefinition(String characters) {
        CanonicalSequence sequence = canonicalize(characters);
        assertEquals(1, sequence.expressions().size());
        return (CanonicalClosure) sequence.expressions().get(0);
    }

    private static CanonicalSequence canonicalize(String characters) {
        return (CanonicalSequence)
                new Canonicalizer()
                        .canonicalize(new ProtosParser(characters).parseProgram());
    }

    private record LanguageScope(Context context, ProtosLanguage language)
            implements AutoCloseable {
        @Override
        public void close() {
            context.leave();
            context.close();
        }
    }

    private record Harness(
            ProtosPrelude prelude,
            ProtosActorExecutionDomain domain,
            ProtosActivation activation) {
        ProtosFutureValue future() {
            return new ProtosFutureValue(prelude.futurePrototype(), domain);
        }
    }

    private static final class ManualExecutor implements java.util.concurrent.Executor {
        private final ArrayDeque<Runnable> queued = new ArrayDeque<>();

        @Override
        public synchronized void execute(Runnable command) {
            queued.addLast(command);
        }

        synchronized int size() {
            return queued.size();
        }

        void runNext() {
            Runnable command;
            synchronized (this) {
                command = queued.pollFirst();
            }
            assertNotNull(command, "expected one scheduled carrier worker");
            command.run();
        }
    }
}
