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
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.guillermomolina.protos.parser.ProtosParser;
import com.guillermomolina.protos.runtime.ProtosActivation;
import com.guillermomolina.protos.runtime.ProtosActorExecutionDomain;
import com.guillermomolina.protos.runtime.ProtosClosureValue;
import com.guillermomolina.protos.runtime.ProtosNullValue;
import com.guillermomolina.protos.runtime.ProtosObjectValue;
import com.guillermomolina.protos.runtime.ProtosPrelude;
import com.guillermomolina.protos.runtime.ProtosTask;
import com.guillermomolina.protos.semantic.Canonicalizer;
import com.guillermomolina.protos.semantic.ast.CanonicalClosure;
import com.guillermomolina.protos.semantic.ast.CanonicalSequence;
import com.oracle.truffle.api.TruffleLanguage.LanguageReference;
import com.oracle.truffle.api.source.Source;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;

final class ProtosPerf006B3DTopLevelTaskPublicationTest {
    private static final LanguageReference<ProtosLanguage> LANGUAGE_REF =
            LanguageReference.create(ProtosLanguage.class);

    private static final class Dependency implements ProtosTask.WaitDependency {
        private final AtomicInteger cancellationCalls = new AtomicInteger();
        private volatile ProtosTask task;
        private volatile boolean ready;
        private volatile boolean registered;

        Dependency register(ProtosTask owner) {
            task = owner;
            registered = true;
            return this;
        }

        @Override
        public boolean isReady() {
            return ready;
        }

        @Override
        public void waitingTaskCancelled(ProtosTask cancelled) {
            cancellationCalls.incrementAndGet();
        }

        boolean complete() {
            if (!registered) {
                throw new IllegalStateException(
                        "dependency completed before registration");
            }
            ready = true;
            return task.resume(this);
        }

        void makeReadyBeforeCapture() {
            if (!registered) {
                throw new IllegalStateException(
                        "dependency readied before registration");
            }
            ready = true;
            /*
             * At this point the native leaf has not yet propagated to the top
             * Task bridge, so resume is expected to be rejected. Sticky
             * isReady() closes that registration/capture race.
             */
            assertFalse(task.resume(this));
        }

        int cancellationCalls() {
            return cancellationCalls.get();
        }
    }

    @Test
    void pendingLeafPublishesCompleteTopContinuationAndResumesWithoutReplay()
            throws Exception {
        try (Context context = Context.newBuilder(ProtosLanguage.ID).build()) {
            context.initialize(ProtosLanguage.ID);
            context.enter();
            try {
                ProtosLanguage language = LANGUAGE_REF.get(null);
                ProtosActivation module = moduleActivation();
                ProtosActorExecutionDomain domain = module.executionDomain();
                Dependency dependency = new Dependency();
                ProtosObjectValue completed =
                        new ProtosObjectValue(
                                ProtosObjectValue.rootObject());
                AtomicInteger nativeCalls = new AtomicInteger();
                AtomicInteger resumerCalls = new AtomicInteger();
                AtomicReference<ProtosTask> nativeTask =
                        new AtomicReference<>();

                ProtosClosureValue leaf =
                        controlledSuspendingLeaf(
                                dependency,
                                completed,
                                nativeCalls,
                                resumerCalls,
                                nativeTask);
                module.context().createLocalSlot("leaf", leaf);
                ProtosSemanticBytecodeRootNode top =
                        lowerRoot(
                                language,
                                "leaf()",
                                "perf006-b3d-pending.protos");

                ProtosTask task =
                        domain.createTask(
                                null,
                                current ->
                                        ProtosBytecodeTaskExecution.execute(
                                                current,
                                                top.getCallTarget(),
                                                module));

                assertTrue(domain.dispatchOne());
                assertEquals(ProtosTask.State.SUSPENDED, task.state());
                assertEquals(0, domain.runnableCount());
                assertSame(task, nativeTask.get());
                assertEquals(1, nativeCalls.get());
                assertEquals(0, resumerCalls.get());

                assertTrue(dependency.complete());
                assertEquals(ProtosTask.State.RUNNABLE, task.state());
                assertEquals(1, domain.runnableCount());

                assertTrue(domain.dispatchOne());
                assertEquals(ProtosTask.State.COMPLETED, task.state());
                assertSame(completed, task.result().orElseThrow());
                assertEquals(
                        1,
                        nativeCalls.get(),
                        "the native capability body must not replay");
                assertEquals(1, resumerCalls.get());
                assertEquals(0, domain.runnableCount());
            } finally {
                context.leave();
            }
        }

        System.out.println("PERF006_B3D_TOP_LEVEL_TASK_PUBLICATION=PASS");
        System.out.println("PERF006_B3D_NATIVE_REPLAY=NO");
    }

    @Test
    void nestedSourceCallPublishesOnlyTheCompleteTopContinuation()
            throws Exception {
        try (Context context = Context.newBuilder(ProtosLanguage.ID).build()) {
            context.initialize(ProtosLanguage.ID);
            context.enter();
            try {
                ProtosLanguage language = LANGUAGE_REF.get(null);
                ProtosActivation module = moduleActivation();
                ProtosActorExecutionDomain domain = module.executionDomain();
                Dependency dependency = new Dependency();
                ProtosObjectValue completed =
                        new ProtosObjectValue(
                                ProtosObjectValue.rootObject());
                AtomicInteger nativeCalls = new AtomicInteger();
                AtomicInteger resumerCalls = new AtomicInteger();
                AtomicReference<ProtosTask> nativeTask =
                        new AtomicReference<>();

                module.context()
                        .createLocalSlot(
                                "leaf",
                                controlledSuspendingLeaf(
                                        dependency,
                                        completed,
                                        nativeCalls,
                                        resumerCalls,
                                        nativeTask));
                module.context()
                        .createLocalSlot(
                                "middle",
                                bytecodeClosure(
                                        language,
                                        module,
                                        "() => { leaf() }",
                                        "perf006-b3d-middle.protos"));

                ProtosSemanticBytecodeRootNode top =
                        lowerRoot(
                                language,
                                "middle()",
                                "perf006-b3d-nested-top.protos");

                ProtosTask task =
                        domain.createTask(
                                null,
                                current ->
                                        ProtosBytecodeTaskExecution.execute(
                                                current,
                                                top.getCallTarget(),
                                                module));

                assertTrue(domain.dispatchOne());
                assertEquals(ProtosTask.State.SUSPENDED, task.state());
                assertSame(task, nativeTask.get());
                assertEquals(1, nativeCalls.get());

                assertTrue(dependency.complete());
                assertTrue(domain.dispatchOne());

                assertEquals(ProtosTask.State.COMPLETED, task.state());
                assertSame(completed, task.result().orElseThrow());
                assertEquals(1, nativeCalls.get());
                assertEquals(1, resumerCalls.get());
            } finally {
                context.leave();
            }
        }

        System.out.println("PERF006_B3D_NESTED_COMPLETE_CPRIME_PUBLICATION=PASS");
    }

    @Test
    void readinessBeforeCaptureContinuesSynchronouslyWithoutQueueing()
            throws Exception {
        try (Context context = Context.newBuilder(ProtosLanguage.ID).build()) {
            context.initialize(ProtosLanguage.ID);
            context.enter();
            try {
                ProtosLanguage language = LANGUAGE_REF.get(null);
                ProtosActivation module = moduleActivation();
                ProtosActorExecutionDomain domain = module.executionDomain();
                Dependency dependency = new Dependency();
                ProtosObjectValue completed =
                        new ProtosObjectValue(
                                ProtosObjectValue.rootObject());
                AtomicInteger nativeCalls = new AtomicInteger();
                AtomicInteger resumerCalls = new AtomicInteger();

                ProtosClosureValue leaf =
                        ProtosClosureValue.suspensionCapableNativeClosure(
                                (activation, supplied) ->
                                        ProtosNullValue.INSTANCE,
                                (activation, supplied) -> {
                                    nativeCalls.incrementAndGet();
                                    dependency.register(
                                            activation.task().orElseThrow());
                                    dependency.makeReadyBeforeCapture();
                                    return ProtosNativeSuspension.pending(
                                            dependency,
                                            () -> {
                                                resumerCalls.incrementAndGet();
                                                return completed;
                                            });
                                });
                module.context().createLocalSlot("leaf", leaf);
                ProtosSemanticBytecodeRootNode top =
                        lowerRoot(
                                language,
                                "leaf()",
                                "perf006-b3d-ready-before-capture.protos");

                ProtosTask task =
                        domain.createTask(
                                null,
                                current ->
                                        ProtosBytecodeTaskExecution.execute(
                                                current,
                                                top.getCallTarget(),
                                                module));

                assertTrue(domain.dispatchOne());
                assertEquals(ProtosTask.State.COMPLETED, task.state());
                assertSame(completed, task.result().orElseThrow());
                assertEquals(1, nativeCalls.get());
                assertEquals(1, resumerCalls.get());
                assertEquals(
                        0,
                        domain.runnableCount(),
                        "already-ready dependency must not add a resume turn");
            } finally {
                context.leave();
            }
        }

        System.out.println("PERF006_B3D_READY_BEFORE_CAPTURE_NO_LOST_WAKE=PASS");
    }

    @Test
    void oneTaskMayPublishTwoSuccessiveCPrimeSuspensionsWithoutReplay()
            throws Exception {
        try (Context context = Context.newBuilder(ProtosLanguage.ID).build()) {
            context.initialize(ProtosLanguage.ID);
            context.enter();
            try {
                ProtosLanguage language = LANGUAGE_REF.get(null);
                ProtosActivation module = moduleActivation();
                ProtosActorExecutionDomain domain = module.executionDomain();
                Dependency firstDependency = new Dependency();
                Dependency secondDependency = new Dependency();
                ProtosObjectValue completed =
                        new ProtosObjectValue(
                                ProtosObjectValue.rootObject());
                AtomicInteger nativeCalls = new AtomicInteger();
                AtomicInteger firstResumerCalls = new AtomicInteger();
                AtomicInteger secondResumerCalls = new AtomicInteger();

                ProtosClosureValue leaf =
                        ProtosClosureValue.suspensionCapableNativeClosure(
                                (activation, supplied) ->
                                        ProtosNullValue.INSTANCE,
                                (activation, supplied) -> {
                                    nativeCalls.incrementAndGet();
                                    ProtosTask task =
                                            activation.task().orElseThrow();
                                    firstDependency.register(task);
                                    return ProtosNativeSuspension.pending(
                                            firstDependency,
                                            () -> {
                                                firstResumerCalls.incrementAndGet();
                                                secondDependency.register(task);
                                                return ProtosNativeSuspension.pending(
                                                        secondDependency,
                                                        () -> {
                                                            secondResumerCalls.incrementAndGet();
                                                            return completed;
                                                        });
                                            });
                                });
                module.context().createLocalSlot("leaf", leaf);
                ProtosSemanticBytecodeRootNode top =
                        lowerRoot(
                                language,
                                "leaf()",
                                "perf006-b3d-repeat.protos");

                ProtosTask task =
                        domain.createTask(
                                null,
                                current ->
                                        ProtosBytecodeTaskExecution.execute(
                                                current,
                                                top.getCallTarget(),
                                                module));

                assertTrue(domain.dispatchOne());
                assertEquals(ProtosTask.State.SUSPENDED, task.state());

                assertTrue(firstDependency.complete());
                assertTrue(domain.dispatchOne());
                assertEquals(
                        ProtosTask.State.SUSPENDED,
                        task.state(),
                        "first C-prime resume must publish the second suspension");
                assertEquals(1, nativeCalls.get());
                assertEquals(1, firstResumerCalls.get());
                assertEquals(0, secondResumerCalls.get());

                assertTrue(secondDependency.complete());
                assertTrue(domain.dispatchOne());
                assertEquals(ProtosTask.State.COMPLETED, task.state());
                assertSame(completed, task.result().orElseThrow());
                assertEquals(1, nativeCalls.get());
                assertEquals(1, firstResumerCalls.get());
                assertEquals(1, secondResumerCalls.get());
            } finally {
                context.leave();
            }
        }

        System.out.println("PERF006_B3D_REPEATED_SUSPENSION_PUBLICATION=PASS");
        System.out.println("PERF006_B3D_REPEATED_SUSPENSION_NATIVE_REPLAY=NO");
    }

    @Test
    void cancellationOfSuspendedCPrimeTaskDetachesWaitAndDoesNotResumeLeaf()
            throws Exception {
        try (Context context = Context.newBuilder(ProtosLanguage.ID).build()) {
            context.initialize(ProtosLanguage.ID);
            context.enter();
            try {
                ProtosLanguage language = LANGUAGE_REF.get(null);
                ProtosActivation module = moduleActivation();
                ProtosActorExecutionDomain domain = module.executionDomain();
                Dependency dependency = new Dependency();
                ProtosObjectValue completed =
                        new ProtosObjectValue(
                                ProtosObjectValue.rootObject());
                AtomicInteger nativeCalls = new AtomicInteger();
                AtomicInteger resumerCalls = new AtomicInteger();
                AtomicReference<ProtosTask> nativeTask =
                        new AtomicReference<>();

                module.context()
                        .createLocalSlot(
                                "leaf",
                                controlledSuspendingLeaf(
                                        dependency,
                                        completed,
                                        nativeCalls,
                                        resumerCalls,
                                        nativeTask));
                ProtosSemanticBytecodeRootNode top =
                        lowerRoot(
                                language,
                                "leaf()",
                                "perf006-b3d-cancel.protos");

                ProtosTask task =
                        domain.createTask(
                                null,
                                current ->
                                        ProtosBytecodeTaskExecution.execute(
                                                current,
                                                top.getCallTarget(),
                                                module));

                assertTrue(domain.dispatchOne());
                assertEquals(ProtosTask.State.SUSPENDED, task.state());

                assertTrue(task.requestCancellation());
                assertEquals(1, dependency.cancellationCalls());
                assertEquals(ProtosTask.State.RUNNABLE, task.state());

                assertTrue(domain.dispatchOne());
                assertEquals(ProtosTask.State.CANCELLED, task.state());
                assertEquals(ProtosTask.CancellationPhase.TERMINAL, task.cancellationPhase());
                assertEquals(
                        0,
                        resumerCalls.get(),
                        "cancelled Task must not resume the native suspension leaf");
                assertEquals(1, nativeCalls.get());
                assertFalse(
                        dependency.complete(),
                        "detached dependency must not requeue a terminal Task");
            } finally {
                context.leave();
            }
        }

        System.out.println("PERF006_B3D_SUSPENDED_CPRIME_CANCELLATION=PASS");
    }

    private static ProtosClosureValue controlledSuspendingLeaf(
            Dependency dependency,
            Object completed,
            AtomicInteger nativeCalls,
            AtomicInteger resumerCalls,
            AtomicReference<ProtosTask> nativeTask) {
        return ProtosClosureValue.suspensionCapableNativeClosure(
                (activation, supplied) ->
                        ProtosNullValue.INSTANCE,
                (activation, supplied) -> {
                    nativeCalls.incrementAndGet();
                    ProtosTask task =
                            activation.task().orElseThrow();
                    nativeTask.set(task);
                    dependency.register(task);
                    return ProtosNativeSuspension.pending(
                            dependency,
                            () -> {
                                resumerCalls.incrementAndGet();
                                return completed;
                            });
                });
    }

    private static ProtosSemanticBytecodeRootNode lowerRoot(
            ProtosLanguage language,
            String characters,
            String sourceName)
            throws Exception {
        Source source =
                Source.newBuilder(
                                ProtosLanguage.ID,
                                characters,
                                sourceName)
                        .build();
        return new CanonicalToBytecodeLowerer(
                        language,
                        source, ProtosExecutionMode.NORMAL)
                .lowerRoot(
                        canonicalize(characters));
    }

    private static ProtosClosureValue bytecodeClosure(
            ProtosLanguage language,
            ProtosActivation creator,
            String characters,
            String sourceName)
            throws Exception {
        Source source =
                Source.newBuilder(
                                ProtosLanguage.ID,
                                characters,
                                sourceName)
                        .build();
        CanonicalClosure definition =
                closureDefinition(characters);
        ProtosClosureExecutionPlan plan =
                ProtosClosureExecutionPlan.bytecode(
                        definition,
                        language,
                        source, ProtosExecutionMode.NORMAL);
        return new ProtosClosureValue(
                definition,
                creator.lexicalContextsForClosureCapture(),
                creator.receiver(),
                creator.methodHome().orElse(null),
                creator.returnHome().orElse(null),
                creator.prelude().orElseThrow(),
                plan);
    }

    private static CanonicalClosure closureDefinition(
            String characters) {
        CanonicalSequence sequence =
                canonicalize(characters);
        assertEquals(
                1,
                sequence.expressions().size());
        return (CanonicalClosure)
                sequence.expressions().get(0);
    }

    private static CanonicalSequence canonicalize(
            String characters) {
        return (CanonicalSequence)
                new Canonicalizer()
                        .canonicalize(
                                new ProtosParser(characters)
                                        .parseProgram());
    }

    private static ProtosActivation moduleActivation() {
        ProtosStandardObjectProtocol.install();
        ProtosObjectValue contextPrototype =
                new ProtosObjectValue(
                        ProtosObjectValue.rootObject());
        ProtosObjectValue bindings =
                new ProtosObjectValue(
                        contextPrototype);
        bindings.createLocalSlot(
                "Context",
                contextPrototype);
        bindings.createLocalSlot(
                "Error",
                new ProtosObjectValue(
                        ProtosObjectValue.rootObject()));
        bindings.createLocalSlot(
                "Array",
                new ProtosObjectValue(
                        ProtosObjectValue.rootObject()));
        bindings.freeze();
        return new ProtosPrelude(
                        bindings,
                        contextPrototype)
                .newModuleActivation();
    }
}
