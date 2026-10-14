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
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.guillermomolina.protos.parser.ProtosParser;
import com.guillermomolina.protos.runtime.ProtosActivation;
import com.guillermomolina.protos.runtime.ProtosActorExecutionDomain;
import com.guillermomolina.protos.runtime.ProtosActorModuleState;
import com.guillermomolina.protos.runtime.ProtosClosureValue;
import com.guillermomolina.protos.runtime.ProtosCoreErrors;
import com.guillermomolina.protos.runtime.ProtosFutureValue;
import com.guillermomolina.protos.runtime.ProtosIntegerValue;
import com.guillermomolina.protos.runtime.ProtosObjectValue;
import com.guillermomolina.protos.runtime.ProtosPrelude;
import com.guillermomolina.protos.runtime.ProtosSuspensionCapableNativeClosureBody;
import com.guillermomolina.protos.runtime.ProtosTask;
import com.guillermomolina.protos.runtime.ProtosValueLookup;
import com.guillermomolina.protos.semantic.Canonicalizer;
import com.guillermomolina.protos.semantic.ast.CanonicalSequence;
import com.oracle.truffle.api.TruffleLanguage.LanguageReference;
import com.oracle.truffle.api.source.Source;
import java.nio.file.Path;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;

final class ProtosPerf006B3EStandardFutureValueCPrimeTest {
    private static final LanguageReference<ProtosLanguage> LANGUAGE_REF =
            LanguageReference.create(ProtosLanguage.class);

    @Test
    void standardValueSlotCarriesCapabilityOnItsNativeImplementation()
            throws Exception {
        ProtosPrelude prelude = core();
        Object raw =
                prelude.futurePrototype()
                        .readLocalSlot("value")
                        .orElseThrow();

        ProtosClosureValue valueClosure =
                (ProtosClosureValue) raw;
        assertTrue(
                valueClosure.nativeBody().orElseThrow()
                        instanceof ProtosSuspensionCapableNativeClosureBody);

        System.out.println(
                "PERF006_B3E_STANDARD_VALUE_NATIVE_CAPABILITY=PASS");
    }

    @Test
    void alreadyResolvedFutureReturnsSynchronouslyWithoutCPrimeSuspension()
            throws Exception {
        try (LanguageScope scope = languageScope()) {
            ProtosLanguage language = scope.language();
            Harness harness = harness();
            ProtosObjectValue resolved =
                    new ProtosObjectValue(
                            ProtosObjectValue.rootObject());
            ProtosFutureValue future = harness.future();
            future.resolve(resolved, harness.activation());
            harness.activation().context().createLocalSlot("f", future);

            ProtosTask task =
                    execute(
                            harness,
                            lowerRoot(
                                    language,
                                    "f.value()",
                                    "perf006-b3e-resolved.protos"));

            assertTrue(harness.domain().dispatchOne());
            assertEquals(ProtosTask.State.COMPLETED, task.state());
            assertSame(resolved, task.result().orElseThrow());
            assertEquals(0, harness.domain().runnableCount());
        }

        System.out.println(
                "PERF006_B3E_RESOLVED_SYNCHRONOUS_FAST_PATH=PASS");
    }

    @Test
    void pendingFutureSuspendsAndResumesThroughCPrime()
            throws Exception {
        try (LanguageScope scope = languageScope()) {
            ProtosLanguage language = scope.language();
            Harness harness = harness();
            ProtosFutureValue future = harness.future();
            ProtosIntegerValue resolved =
                    new ProtosIntegerValue(73);
            harness.activation().context().createLocalSlot("f", future);

            ProtosTask task =
                    execute(
                            harness,
                            lowerRoot(
                                    language,
                                    "f.value()",
                                    "perf006-b3e-pending.protos"));

            assertTrue(harness.domain().dispatchOne());
            assertEquals(ProtosTask.State.SUSPENDED, task.state());
            assertEquals(0, harness.domain().runnableCount());

            assertTrue(future.resolve(resolved, harness.activation()));
            assertEquals(ProtosTask.State.RUNNABLE, task.state());
            assertEquals(1, harness.domain().runnableCount());

            assertTrue(harness.domain().dispatchOne());
            assertEquals(ProtosTask.State.COMPLETED, task.state());
            assertSame(resolved, task.result().orElseThrow());
            assertEquals(0, harness.domain().runnableCount());
        }

        System.out.println(
                "PERF006_B3E_PENDING_VALUE_CPRIME_RESUME=PASS");
    }

    @Test
    void pendingFailureResignalsTheExactStoredErrorThroughCPrime()
            throws Exception {
        try (LanguageScope scope = languageScope()) {
            ProtosLanguage language = scope.language();
            Harness harness = harness();
            ProtosFutureValue future = harness.future();
            ProtosObjectValue failure =
                    ProtosCoreErrors.newError(
                            harness.activation());
            harness.activation().context().createLocalSlot("f", future);

            ProtosTask task =
                    execute(
                            harness,
                            lowerRoot(
                                    language,
                                    "f.value()",
                                    "perf006-b3e-failure.protos"));

            assertTrue(harness.domain().dispatchOne());
            assertEquals(ProtosTask.State.SUSPENDED, task.state());

            assertTrue(future.fail(failure));
            assertTrue(harness.domain().dispatchOne());

            assertEquals(ProtosTask.State.FAILED, task.state());
            assertSame(
                    failure,
                    task.failure().orElseThrow(),
                    "Future.value must re-signal the exact stored domain-local Error");
        }

        System.out.println(
                "PERF006_B3E_PENDING_FAILURE_EXACT_ERROR_IDENTITY=PASS");
    }

    @Test
    void cancelledFutureCreatesFreshObservationErrorsOnCPrimePath()
            throws Exception {
        try (LanguageScope scope = languageScope()) {
            ProtosLanguage language = scope.language();
            ProtosPrelude prelude = core();
            ProtosActorExecutionDomain domain =
                    new ProtosActorExecutionDomain();
            ProtosFutureValue future =
                    new ProtosFutureValue(
                            prelude.futurePrototype(),
                            domain);
            assertTrue(future.cancelTerminal());

            ProtosActivation firstActivation =
                    activation(prelude, domain);
            firstActivation.context().createLocalSlot("f", future);
            ProtosTask first =
                    execute(
                            domain,
                            firstActivation,
                            lowerRoot(
                                    language,
                                    "f.value()",
                                    "perf006-b3e-cancelled-first.protos"));
            assertTrue(domain.dispatchOne());
            assertEquals(ProtosTask.State.FAILED, first.state());
            Object firstError = first.failure().orElseThrow();

            ProtosActivation secondActivation =
                    activation(prelude, domain);
            secondActivation.context().createLocalSlot("f", future);
            ProtosTask second =
                    execute(
                            domain,
                            secondActivation,
                            lowerRoot(
                                    language,
                                    "f.value()",
                                    "perf006-b3e-cancelled-second.protos"));
            assertTrue(domain.dispatchOne());
            assertEquals(ProtosTask.State.FAILED, second.state());
            Object secondError = second.failure().orElseThrow();

            assertNotSame(
                    firstError,
                    secondError,
                    "each cancelled Future.value observation needs a fresh Error occurrence");
            assertSame(
                    ProtosCoreErrors.prototype(
                            firstActivation,
                            ProtosCoreErrors.StandardError.CANCELLED),
                    ((ProtosObjectValue) firstError)
                            .parent()
                            .orElseThrow());
            assertSame(
                    ProtosCoreErrors.prototype(
                            secondActivation,
                            ProtosCoreErrors.StandardError.CANCELLED),
                    ((ProtosObjectValue) secondError)
                            .parent()
                            .orElseThrow());
        }

        System.out.println(
                "PERF006_B3E_CANCELLED_FRESH_ERROR_OCCURRENCES=PASS");
    }

    @Test
    void cancellingWaitingTaskDetachesWaitWithoutCancellingFuture()
            throws Exception {
        try (LanguageScope scope = languageScope()) {
            ProtosLanguage language = scope.language();
            Harness harness = harness();
            ProtosFutureValue future = harness.future();
            harness.activation().context().createLocalSlot("f", future);

            ProtosTask task =
                    execute(
                            harness,
                            lowerRoot(
                                    language,
                                    "f.value()",
                                    "perf006-b3e-waiter-cancel.protos"));

            assertTrue(harness.domain().dispatchOne());
            assertEquals(ProtosTask.State.SUSPENDED, task.state());

            assertTrue(task.requestCancellation());
            assertEquals(ProtosTask.State.RUNNABLE, task.state());
            assertTrue(harness.domain().dispatchOne());

            assertEquals(ProtosTask.State.CANCELLED, task.state());
            assertEquals(ProtosFutureValue.State.PENDING, future.state());

            assertTrue(
                    future.resolve(
                            ProtosObjectValue.rootObject(),
                            harness.activation()));
            assertFalse(
                    harness.domain().dispatchOne(),
                    "detached Future waiter must not requeue the cancelled Task");
        }

        System.out.println(
                "PERF006_B3E_WAITING_TASK_CANCEL_DETACH=PASS");
    }

    @Test
    void cancellationWinsWhenFutureReadyButTaskHasNotResumedYet()
            throws Exception {
        try (LanguageScope scope = languageScope()) {
            ProtosLanguage language = scope.language();
            Harness harness = harness();
            ProtosFutureValue future = harness.future();
            ProtosObjectValue resolved =
                    new ProtosObjectValue(
                            ProtosObjectValue.rootObject());
            harness.activation().context().createLocalSlot("f", future);

            ProtosTask task =
                    execute(
                            harness,
                            lowerRoot(
                                    language,
                                    "f.value()",
                                    "perf006-b3e-ready-cancel-race.protos"));

            assertTrue(harness.domain().dispatchOne());
            assertEquals(ProtosTask.State.SUSPENDED, task.state());

            assertTrue(future.resolve(resolved, harness.activation()));
            assertEquals(ProtosTask.State.RUNNABLE, task.state());
            assertTrue(task.requestCancellation());

            assertTrue(harness.domain().dispatchOne());
            assertEquals(
                    ProtosTask.State.CANCELLED,
                    task.state(),
                    "task cancellation wins at the Future.value resume boundary");
            assertEquals(
                    ProtosFutureValue.State.RESOLVED,
                    future.state(),
                    "consumer cancellation must not rewrite the observed Future");
            assertSame(
                    resolved,
                    future.resolvedValue().orElseThrow());
        }

        System.out.println(
                "PERF006_B3E_READY_VS_TASK_CANCEL_PRECEDENCE=PASS");
    }

    @Test
    void extractedStandardValueClosureSuspendsWithoutSelectorRecognition()
            throws Exception {
        try (LanguageScope scope = languageScope()) {
            ProtosLanguage language = scope.language();
            Harness harness = harness();
            ProtosFutureValue future = harness.future();
            ProtosIntegerValue resolved =
                    new ProtosIntegerValue(91);

            ProtosClosureValue extracted =
                    (ProtosClosureValue)
                            ProtosValueLookup.readMember(
                                            future,
                                            "value",
                                            harness.prelude())
                                    .orElseThrow();
            assertTrue(
                    extracted.nativeBody().orElseThrow()
                            instanceof ProtosSuspensionCapableNativeClosureBody);
            harness.activation().context().createLocalSlot(
                    "reader",
                    extracted);

            ProtosTask task =
                    execute(
                            harness,
                            lowerRoot(
                                    language,
                                    "reader()",
                                    "perf006-b3e-extracted-value.protos"));

            assertTrue(harness.domain().dispatchOne());
            assertEquals(ProtosTask.State.SUSPENDED, task.state());

            assertTrue(future.resolve(resolved, harness.activation()));
            assertTrue(harness.domain().dispatchOne());

            assertEquals(ProtosTask.State.COMPLETED, task.state());
            assertSame(resolved, task.result().orElseThrow());
        }

        System.out.println(
                "PERF006_B3E_EXTRACTED_VALUE_NO_SELECTOR_PET=PASS");
    }

    @Test
    void explicitRebindingUsesSameCapabilityBodyAndNewOrdinaryReceiver()
            throws Exception {
        try (LanguageScope scope = languageScope()) {
            ProtosLanguage language = scope.language();
            Harness harness = harness();
            ProtosFutureValue first = harness.future();
            ProtosFutureValue second = harness.future();
            ProtosIntegerValue resolved =
                    new ProtosIntegerValue(107);

            ProtosClosureValue raw =
                    (ProtosClosureValue)
                            harness.prelude()
                                    .futurePrototype()
                                    .readLocalSlot("value")
                                    .orElseThrow();
            ProtosClosureValue rebound =
                    raw.bindMethod(
                            second,
                            harness.prelude().futurePrototype());

            assertSame(
                    raw.nativeBody().orElseThrow(),
                    rebound.nativeBody().orElseThrow());
            harness.activation().context().createLocalSlot(
                    "reader",
                    rebound);

            ProtosTask task =
                    execute(
                            harness,
                            lowerRoot(
                                    language,
                                    "reader()",
                                    "perf006-b3e-rebound-value.protos"));

            assertTrue(harness.domain().dispatchOne());
            assertEquals(ProtosTask.State.SUSPENDED, task.state());
            assertEquals(ProtosFutureValue.State.PENDING, first.state());
            assertEquals(ProtosFutureValue.State.PENDING, second.state());

            assertTrue(second.resolve(resolved, harness.activation()));
            assertTrue(harness.domain().dispatchOne());

            assertEquals(ProtosTask.State.COMPLETED, task.state());
            assertSame(resolved, task.result().orElseThrow());
            assertEquals(
                    ProtosFutureValue.State.PENDING,
                    first.state(),
                    "rebinding must follow the ordinary new receiver, not original lookup identity");
        }

        System.out.println(
                "PERF006_B3E_VALUE_REBINDING_CAPABILITY_PROVENANCE=PASS");
    }

    private static LanguageScope languageScope() {
        Context context =
                Context.newBuilder(ProtosLanguage.ID).build();
        context.initialize(ProtosLanguage.ID);
        context.enter();
        return new LanguageScope(
                context,
                LANGUAGE_REF.get(null));
    }

    private static ProtosPrelude core() throws Exception {
        return new ProtosCoreBootstrap()
                .bootstrap(Path.of("protos", "lib", "core"));
    }

    private static Harness harness() throws Exception {
        ProtosPrelude prelude = core();
        ProtosActorExecutionDomain domain =
                new ProtosActorExecutionDomain();
        return new Harness(
                prelude,
                domain,
                activation(prelude, domain));
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
        return execute(
                harness.domain(),
                harness.activation(),
                root);
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
                Source.newBuilder(
                                ProtosLanguage.ID,
                                characters,
                                sourceName)
                        .build();
        return new CanonicalToBytecodeLowerer(
                        language,
                        source, ProtosExecutionMode.NORMAL)
                .lowerRoot(
                        (CanonicalSequence)
                                new Canonicalizer()
                                        .canonicalize(
                                                new ProtosParser(characters)
                                                        .parseProgram()));
    }

    private record LanguageScope(
            Context context,
            ProtosLanguage language)
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
            return new ProtosFutureValue(
                    prelude.futurePrototype(),
                    domain);
        }
    }
}
