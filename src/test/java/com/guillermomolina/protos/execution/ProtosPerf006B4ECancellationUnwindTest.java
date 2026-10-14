/*
 * THE LICENSED WORK IS PROVIDED UNDER THE TERMS OF THE ADAPTIVE PUBLIC LICENSE
 * ("LICENSE") AS FIRST COMPLETED BY: Guillermo Adrián Molina. ANY USE, PUBLIC
 * DISPLAY, PUBLIC PERFORMANCE, REPRODUCTION OR DISTRIBUTION OF, OR PREPARATION OF
 * DERIVATIVE WORKS BASED ON THE LICENSED WORK CONSTITUTES RECIPIENT'S ACCEPTANCE
 * OF THIS LICENSE AND ITS TERMS, WHETHER OR NOT SUCH RECIPIENT READS THE TERMS OF
 * THE LICENSE. "LICENSED WORK" AND "RECIPIENT" ARE DEFINED IN THE LICENSE. A COPY
 * OF THE LICENSE IS LOCATED IN THE TEXT FILE ENTITLED "LICENSE.TXT" ACCOMPANYING
 * THE CONTENTS OF THIS FILE. IF A COPY OF THE LICENSE DOES NOT ACCOMPANY THIS
 * FILE, A COPY OF THE LICENSE MAY ALSO BE OBTAINED AT THE FOLLOWING WEB SITE:
 * https://github.com/guillermomolina/protos
 *
 * Software distributed under the License is distributed on an "AS IS" basis,
 * WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License for
 * the specific language governing rights and limitations under the LICENSE.
 */

package com.guillermomolina.protos.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.guillermomolina.protos.parser.ProtosParser;
import com.guillermomolina.protos.runtime.ProtosActivation;
import com.guillermomolina.protos.runtime.ProtosActorExecutionDomain;
import com.guillermomolina.protos.runtime.ProtosActorModuleState;
import com.guillermomolina.protos.runtime.ProtosClosureValue;
import com.guillermomolina.protos.runtime.ProtosCoreErrors;
import com.guillermomolina.protos.runtime.ProtosFutureValue;
import com.guillermomolina.protos.runtime.ProtosNullValue;
import com.guillermomolina.protos.runtime.ProtosObjectValue;
import com.guillermomolina.protos.runtime.ProtosPrelude;
import com.guillermomolina.protos.runtime.ProtosTask;
import com.guillermomolina.protos.semantic.Canonicalizer;
import com.guillermomolina.protos.semantic.ast.CanonicalClosure;
import com.guillermomolina.protos.semantic.ast.CanonicalSequence;
import com.oracle.truffle.api.TruffleLanguage.LanguageReference;
import com.oracle.truffle.api.source.Source;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;

final class ProtosPerf006B4ECancellationUnwindTest {
    private static final LanguageReference<ProtosLanguage> LANGUAGE_REF =
            LanguageReference.create(ProtosLanguage.class);

    @Test
    void cancellationInjectedIntoSuspendedCPrimeRunsEnsureBeforeTerminalization()
            throws Exception {
        try (LanguageScope scope = languageScope()) {
            ProtosPrelude prelude = core();
            ProtosActorExecutionDomain domain = new ProtosActorExecutionDomain();
            ProtosActivation module = activation(prelude, domain);
            ProtosFutureValue bodyWait = new ProtosFutureValue(prelude.futurePrototype(), domain);
            AtomicInteger bodyPrefix = new AtomicInteger();
            AtomicInteger bodyAfter = new AtomicInteger();
            AtomicInteger cleanups = new AtomicInteger();

            module.context().createLocalSlot("bodyWait", bodyWait);
            module.context().createLocalSlot(
                    "bodyProbe",
                    nativeClosure(
                            (activation, supplied) -> {
                                bodyPrefix.incrementAndGet();
                                return ProtosNullValue.INSTANCE;
                            }));
            module.context().createLocalSlot(
                    "bodyAfterProbe",
                    nativeClosure(
                            (activation, supplied) -> {
                                bodyAfter.incrementAndGet();
                                return ProtosNullValue.INSTANCE;
                            }));
            module.context().createLocalSlot(
                    "cleanup",
                    nativeClosure(
                            (activation, supplied) -> {
                                cleanups.incrementAndGet();
                                return ProtosNullValue.INSTANCE;
                            }));
            module.context().createLocalSlot(
                    "body",
                    sourceClosure(
                            scope.language(),
                            module,
                            "() => { bodyProbe()\nbodyWait.value()\nbodyAfterProbe() }",
                            "perf006-b4e-cancel-body.protos"));

            Execution execution =
                    executeWithFuture(
                            domain,
                            prelude,
                            module,
                            lowerRoot(
                                    scope.language(),
                                    "body.ensure(cleanup)",
                                    "perf006-b4e-cancel-top.protos"));

            assertTrue(domain.dispatchOne());
            assertEquals(ProtosTask.State.SUSPENDED, execution.task().state());
            assertEquals(1, bodyPrefix.get());
            assertEquals(0, bodyAfter.get());
            assertEquals(0, cleanups.get());

            assertTrue(execution.future().cancelRequest());
            assertEquals(ProtosTask.State.RUNNABLE, execution.task().state());
            assertTrue(domain.dispatchOne());

            assertEquals(ProtosTask.State.CANCELLED, execution.task().state());
            assertEquals(ProtosFutureValue.State.CANCELLED, execution.future().state());
            assertEquals(ProtosTask.CancellationPhase.TERMINAL, execution.task().cancellationPhase());
            assertEquals(1, bodyPrefix.get());
            assertEquals(0, bodyAfter.get());
            assertEquals(1, cleanups.get());
            assertEquals(ProtosFutureValue.State.PENDING, bodyWait.state());

            assertTrue(bodyWait.resolve(ProtosNullValue.INSTANCE, module));
            assertFalse(
                    domain.dispatchOne(),
                    "cancelled Future.value waiter must remain detached after unwind");
        }

        System.out.println("PERF006_B4E_CANCELLATION_INJECTED_INTO_CPRIME=PASS");
        System.out.println("PERF006_B4E_ENSURE_ON_CANCELLATION=PASS");
        System.out.println("PERF006_B4E_COMPLETED_PREFIX_REPLAY=NO");
        System.out.println("PERF006_B4E_TERMINAL_AFTER_CLEANUP=PASS");
    }

    @Test
    void cancellationCleanupMaySuspendWithoutRedeliveryOrReplay()
            throws Exception {
        try (LanguageScope scope = languageScope()) {
            ProtosPrelude prelude = core();
            ProtosActorExecutionDomain domain = new ProtosActorExecutionDomain();
            ProtosActivation module = activation(prelude, domain);
            ProtosFutureValue bodyWait = new ProtosFutureValue(prelude.futurePrototype(), domain);
            ProtosFutureValue cleanupWait = new ProtosFutureValue(prelude.futurePrototype(), domain);
            AtomicInteger cleanupPrefix = new AtomicInteger();
            AtomicInteger cleanupAfter = new AtomicInteger();

            module.context().createLocalSlot("bodyWait", bodyWait);
            module.context().createLocalSlot("cleanupWait", cleanupWait);
            module.context().createLocalSlot(
                    "cleanupProbe",
                    nativeClosure(
                            (activation, supplied) -> {
                                cleanupPrefix.incrementAndGet();
                                return ProtosNullValue.INSTANCE;
                            }));
            module.context().createLocalSlot(
                    "cleanupAfterProbe",
                    nativeClosure(
                            (activation, supplied) -> {
                                cleanupAfter.incrementAndGet();
                                return ProtosNullValue.INSTANCE;
                            }));
            module.context().createLocalSlot(
                    "body",
                    sourceClosure(
                            scope.language(),
                            module,
                            "() => bodyWait.value()",
                            "perf006-b4e-cleanup-suspend-body.protos"));
            module.context().createLocalSlot(
                    "cleanup",
                    sourceClosure(
                            scope.language(),
                            module,
                            "() => { cleanupProbe()\ncleanupWait.value()\ncleanupAfterProbe() }",
                            "perf006-b4e-cleanup-suspend-cleanup.protos"));

            Execution execution =
                    executeWithFuture(
                            domain,
                            prelude,
                            module,
                            lowerRoot(
                                    scope.language(),
                                    "body.ensure(cleanup)",
                                    "perf006-b4e-cleanup-suspend-top.protos"));

            assertTrue(domain.dispatchOne());
            assertEquals(ProtosTask.State.SUSPENDED, execution.task().state());

            assertTrue(execution.future().cancelRequest());
            assertTrue(domain.dispatchOne());
            assertEquals(ProtosTask.State.SUSPENDED, execution.task().state());
            assertEquals(ProtosTask.CancellationPhase.UNWINDING, execution.task().cancellationPhase());
            assertEquals(ProtosFutureValue.State.PENDING, execution.future().state());
            assertEquals(1, cleanupPrefix.get());
            assertEquals(0, cleanupAfter.get());
            assertEquals(0, domain.runnableCount());

            assertTrue(
                    execution.future().cancelRequest(),
                    "repeated Future.cancel remains an idempotent request while pending");
            assertEquals(ProtosTask.State.SUSPENDED, execution.task().state());
            assertEquals(ProtosTask.CancellationPhase.UNWINDING, execution.task().cancellationPhase());
            assertEquals(0, domain.runnableCount());
            assertEquals(1, cleanupPrefix.get());

            assertTrue(cleanupWait.resolve(ProtosNullValue.INSTANCE, module));
            assertEquals(ProtosTask.State.RUNNABLE, execution.task().state());
            assertTrue(domain.dispatchOne());

            assertEquals(ProtosTask.State.CANCELLED, execution.task().state());
            assertEquals(ProtosFutureValue.State.CANCELLED, execution.future().state());
            assertEquals(1, cleanupPrefix.get());
            assertEquals(1, cleanupAfter.get());
        }

        System.out.println("PERF006_B4E_CLEANUP_SUSPENSION=PASS");
        System.out.println("PERF006_B4E_SAME_REQUEST_REDELIVERY=NO");
        System.out.println("PERF006_B4E_CLEANUP_PREFIX_REPLAY=NO");
    }

    @Test
    void cleanupErrorSupersedesCancellationBeforeOuterHandlerRuns()
            throws Exception {
        try (LanguageScope scope = languageScope()) {
            ProtosPrelude prelude = core();
            ProtosActorExecutionDomain domain = new ProtosActorExecutionDomain();
            ProtosActivation module = activation(prelude, domain);
            ProtosFutureValue bodyWait = new ProtosFutureValue(prelude.futurePrototype(), domain);
            ProtosObjectValue replacement = ProtosCoreErrors.newError(module);
            ProtosObjectValue recovery = new ProtosObjectValue(ProtosObjectValue.rootObject());
            AtomicInteger handlerCalls = new AtomicInteger();

            module.context().createLocalSlot("bodyWait", bodyWait);
            module.context().createLocalSlot("replacement", replacement);
            module.context().createLocalSlot(
                    "body",
                    sourceClosure(
                            scope.language(),
                            module,
                            "() => bodyWait.value()",
                            "perf006-b4e-supersede-body.protos"));
            module.context().createLocalSlot(
                    "cleanup",
                    sourceClosure(
                            scope.language(),
                            module,
                            "() => replacement.signal()",
                            "perf006-b4e-supersede-cleanup.protos"));
            module.context().createLocalSlot(
                    "guarded",
                    sourceClosure(
                            scope.language(),
                            module,
                            "() => body.ensure(cleanup)",
                            "perf006-b4e-supersede-protected.protos"));
            module.context().createLocalSlot(
                    "handler",
                    nativeClosure(
                            (activation, supplied) -> {
                                handlerCalls.incrementAndGet();
                                assertSame(replacement, supplied.get(0));
                                ProtosTask task = activation.task().orElseThrow();
                                assertEquals(
                                        ProtosTask.CancellationPhase.SUPERSEDED,
                                        task.cancellationPhase(),
                                        "cleanup transfer must supersede cancellation before outer handling");
                                return recovery;
                            }));

            Execution execution =
                    executeWithFuture(
                            domain,
                            prelude,
                            module,
                            lowerRoot(
                                    scope.language(),
                                    "Error.handle(guarded, handler)",
                                    "perf006-b4e-supersede-top.protos"));

            assertTrue(domain.dispatchOne());
            assertEquals(ProtosTask.State.SUSPENDED, execution.task().state());

            assertTrue(execution.future().cancelRequest());
            assertTrue(domain.dispatchOne());

            assertEquals(ProtosTask.State.COMPLETED, execution.task().state());
            assertEquals(ProtosFutureValue.State.RESOLVED, execution.future().state());
            assertSame(recovery, execution.task().result().orElseThrow());
            assertSame(recovery, execution.future().resolvedValue().orElseThrow());
            assertEquals(1, handlerCalls.get());
        }

        System.out.println("PERF006_B4E_LATER_CLEANUP_ERROR_SUPERSEDES=PASS");
        System.out.println("PERF006_B4E_SUPERSESSION_BEFORE_OUTER_HANDLER=PASS");
    }

    @Test
    void cleanupCancellationSupersedesSelectedErrorBeforeOuterHandlerRuns()
            throws Exception {
        try (LanguageScope scope = languageScope()) {
            ProtosPrelude prelude = core();
            ProtosActorExecutionDomain domain = new ProtosActorExecutionDomain();
            ProtosActivation module = activation(prelude, domain);
            ProtosObjectValue selectedError = ProtosCoreErrors.newError(module);
            ProtosFutureValue cleanupWait =
                    new ProtosFutureValue(prelude.futurePrototype(), domain);
            AtomicInteger handlerCalls = new AtomicInteger();

            module.context().createLocalSlot("selectedError", selectedError);
            module.context().createLocalSlot("cleanupWait", cleanupWait);
            module.context().createLocalSlot(
                    "requestCancel",
                    nativeClosure(
                            (activation, supplied) -> {
                                if (!activation.task().orElseThrow().requestCancellation()) {
                                    throw new IllegalStateException(
                                            "cleanup cancellation request was not recorded");
                                }
                                return ProtosNullValue.INSTANCE;
                            }));
            module.context().createLocalSlot(
                    "body",
                    sourceClosure(
                            scope.language(),
                            module,
                            "() => selectedError.signal()",
                            "perf006-b4e-selected-error-body.protos"));
            module.context().createLocalSlot(
                    "cleanup",
                    sourceClosure(
                            scope.language(),
                            module,
                            "() => { requestCancel()\ncleanupWait.value()\nnull }",
                            "perf006-b4e-selected-error-cleanup-cancel.protos"));
            module.context().createLocalSlot(
                    "guarded",
                    sourceClosure(
                            scope.language(),
                            module,
                            "() => body.ensure(cleanup)",
                            "perf006-b4e-selected-error-guarded.protos"));
            module.context().createLocalSlot(
                    "handler",
                    nativeClosure(
                            (activation, supplied) -> {
                                handlerCalls.incrementAndGet();
                                return ProtosNullValue.INSTANCE;
                            }));

            Execution execution =
                    executeWithFuture(
                            domain,
                            prelude,
                            module,
                            lowerRoot(
                                    scope.language(),
                                    "Error.handle(guarded, handler)",
                                    "perf006-b4e-selected-error-cleanup-cancel-top.protos"));

            assertTrue(domain.dispatchOne());

            assertEquals(ProtosTask.State.CANCELLED, execution.task().state());
            assertEquals(
                    ProtosTask.CancellationPhase.TERMINAL,
                    execution.task().cancellationPhase());
            assertEquals(ProtosFutureValue.State.CANCELLED, execution.future().state());
            assertEquals(
                    0,
                    handlerCalls.get(),
                    "cleanup cancellation must replace the already-selected error before its handler");
            assertEquals(ProtosFutureValue.State.PENDING, cleanupWait.state());
        }

        System.out.println(
                "PERF006_B4E_CLEANUP_CANCELLATION_SUPERSEDES_SELECTED_ERROR=PASS");
        System.out.println(
                "PERF006_B4E_SELECTED_ERROR_HANDLER_AFTER_CLEANUP_CANCELLATION=NO");
    }

    @Test
    void nestedEnsuresRunInLifoOrderDuringCancellationUnwind()
            throws Exception {
        try (LanguageScope scope = languageScope()) {
            ProtosPrelude prelude = core();
            ProtosActorExecutionDomain domain = new ProtosActorExecutionDomain();
            ProtosActivation module = activation(prelude, domain);
            ProtosFutureValue bodyWait = new ProtosFutureValue(prelude.futurePrototype(), domain);
            List<Integer> order = new ArrayList<>();

            module.context().createLocalSlot("bodyWait", bodyWait);
            module.context().createLocalSlot(
                    "cleanupInner",
                    nativeClosure(
                            (activation, supplied) -> {
                                order.add(1);
                                return ProtosNullValue.INSTANCE;
                            }));
            module.context().createLocalSlot(
                    "cleanupOuter",
                    nativeClosure(
                            (activation, supplied) -> {
                                order.add(2);
                                return ProtosNullValue.INSTANCE;
                            }));
            module.context().createLocalSlot(
                    "body",
                    sourceClosure(
                            scope.language(),
                            module,
                            "() => bodyWait.value()",
                            "perf006-b4e-nested-body.protos"));
            module.context().createLocalSlot(
                    "innerProtected",
                    sourceClosure(
                            scope.language(),
                            module,
                            "() => body.ensure(cleanupInner)",
                            "perf006-b4e-nested-inner.protos"));

            Execution execution =
                    executeWithFuture(
                            domain,
                            prelude,
                            module,
                            lowerRoot(
                                    scope.language(),
                                    "innerProtected.ensure(cleanupOuter)",
                                    "perf006-b4e-nested-top.protos"));

            assertTrue(domain.dispatchOne());
            assertEquals(ProtosTask.State.SUSPENDED, execution.task().state());
            assertTrue(execution.future().cancelRequest());
            assertTrue(domain.dispatchOne());

            assertEquals(ProtosTask.State.CANCELLED, execution.task().state());
            assertEquals(List.of(1, 2), order);
        }

        System.out.println("PERF006_B4E_NESTED_ENSURE_LIFO=PASS");
    }

    @Test
    void cancellationDrainsStructuredChildrenOnlyAfterCrossedCleanup()
            throws Exception {
        try (LanguageScope scope = languageScope()) {
            ProtosPrelude prelude = core();
            ProtosActorExecutionDomain domain = new ProtosActorExecutionDomain();
            ProtosActivation module = activation(prelude, domain);
            ProtosFutureValue bodyWait = new ProtosFutureValue(prelude.futurePrototype(), domain);
            ProtosTask.WaitDependency childWait = new ProtosTask.WaitDependency() {};
            AtomicReference<ProtosTask> childRef = new AtomicReference<>();
            AtomicInteger cleanups = new AtomicInteger();

            module.context().createLocalSlot("bodyWait", bodyWait);
            module.context().createLocalSlot(
                    "spawnChild",
                    nativeClosure(
                            (activation, supplied) -> {
                                ProtosTask parent = activation.task().orElseThrow();
                                ProtosTask child =
                                        activation.executionDomain().createTask(
                                                parent,
                                                null,
                                                current -> {
                                                    if (current.cancellationRequested()) {
                                                        if (!current.observeCancellation()) {
                                                            throw new IllegalStateException(
                                                                    "child cancellation was not observable");
                                                        }
                                                    } else {
                                                        current.suspend(childWait);
                                                    }
                                                });
                                childRef.set(child);
                                return ProtosNullValue.INSTANCE;
                            }));
            module.context().createLocalSlot(
                    "cleanup",
                    nativeClosure(
                            (activation, supplied) -> {
                                cleanups.incrementAndGet();
                                return ProtosNullValue.INSTANCE;
                            }));
            module.context().createLocalSlot(
                    "body",
                    sourceClosure(
                            scope.language(),
                            module,
                            "() => { spawnChild()\nbodyWait.value() }",
                            "perf006-b4e-child-body.protos"));

            Execution execution =
                    executeWithFuture(
                            domain,
                            prelude,
                            module,
                            lowerRoot(
                                    scope.language(),
                                    "body.ensure(cleanup)",
                                    "perf006-b4e-child-top.protos"));

            assertTrue(domain.dispatchOne());
            ProtosTask child = childRef.get();
            assertTrue(child != null);
            assertEquals(ProtosTask.State.SUSPENDED, execution.task().state());

            assertTrue(domain.dispatchOne());
            assertEquals(ProtosTask.State.SUSPENDED, child.state());
            assertEquals(0, domain.runnableCount());

            assertTrue(execution.future().cancelRequest());
            assertTrue(domain.dispatchOne());

            assertEquals(1, cleanups.get());
            assertEquals(
                    ProtosTask.State.SUSPENDED,
                    execution.task().state(),
                    "parent must drain retained structured child after cleanup");
            assertEquals(ProtosFutureValue.State.PENDING, execution.future().state());
            assertEquals(ProtosTask.State.RUNNABLE, child.state());

            assertTrue(domain.dispatchOne());
            assertEquals(ProtosTask.State.CANCELLED, child.state());
            assertEquals(ProtosTask.State.RUNNABLE, execution.task().state());
            assertEquals(ProtosFutureValue.State.PENDING, execution.future().state());

            assertTrue(domain.dispatchOne());
            assertEquals(ProtosTask.State.CANCELLED, execution.task().state());
            assertEquals(ProtosFutureValue.State.CANCELLED, execution.future().state());
        }

        System.out.println("PERF006_B4E_CLEANUP_BEFORE_CHILD_DRAIN=PASS");
        System.out.println("PERF006_B4E_STRUCTURED_CHILD_DRAIN=PASS");
    }

    private static ProtosClosureValue nativeClosure(
            com.guillermomolina.protos.runtime.ProtosNativeClosureBody body) {
        return ProtosClosureValue.suspensionCapableNativeClosure(body, body);
    }

    private static ProtosClosureValue sourceClosure(
            ProtosLanguage language,
            ProtosActivation creator,
            String characters,
            String sourceName)
            throws Exception {
        CanonicalClosure definition = closureDefinition(characters);
        ProtosClosureExecutionPlan plan =
                ProtosClosureExecutionPlan.bytecode(
                        definition,
                        language,
                        source(characters, sourceName), ProtosExecutionMode.NORMAL);
        return new ProtosClosureValue(
                definition,
                creator.lexicalContextsForClosureCapture(),
                creator.receiver(),
                creator.methodHome().orElse(null),
                creator.returnHome().orElse(null),
                creator.prelude().orElseThrow(),
                plan);
    }

    private static Execution executeWithFuture(
            ProtosActorExecutionDomain domain,
            ProtosPrelude prelude,
            ProtosActivation activation,
            ProtosSemanticBytecodeRootNode root) {
        ProtosFutureValue future = new ProtosFutureValue(prelude.futurePrototype(), domain);
        ProtosTask task =
                domain.createTask(
                        null,
                        future,
                        current ->
                                ProtosBytecodeTaskExecution.execute(
                                        current,
                                        root.getCallTarget(),
                                        activation));
        future.attachProducerTask(task, activation);
        return new Execution(task, future);
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

    private static ProtosActivation activation(
            ProtosPrelude prelude,
            ProtosActorExecutionDomain domain) {
        return prelude.newModuleActivation(
                new ProtosActorModuleState(),
                null,
                prelude.newExecutionContext(),
                domain);
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

    private static Source source(String characters, String name) throws Exception {
        return Source.newBuilder(ProtosLanguage.ID, characters, name).build();
    }

    private static ProtosSemanticBytecodeRootNode lowerRoot(
            ProtosLanguage language,
            String characters,
            String sourceName)
            throws Exception {
        Source source = source(characters, sourceName);
        return new CanonicalToBytecodeLowerer(language, source, ProtosExecutionMode.NORMAL)
                .lowerRoot(canonicalize(characters));
    }

    private record Execution(ProtosTask task, ProtosFutureValue future) {}

    private record LanguageScope(Context context, ProtosLanguage language)
            implements AutoCloseable {
        @Override
        public void close() {
            context.leave();
            context.close();
        }
    }
}
