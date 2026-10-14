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
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.guillermomolina.protos.parser.ProtosParser;
import com.guillermomolina.protos.runtime.ProtosActivation;
import com.guillermomolina.protos.runtime.ProtosActorExecutionDomain;
import com.guillermomolina.protos.runtime.ProtosActorModuleState;
import com.guillermomolina.protos.runtime.ProtosBooleanValue;
import com.guillermomolina.protos.runtime.ProtosClosureValue;
import com.guillermomolina.protos.runtime.ProtosCoreErrors;
import com.guillermomolina.protos.runtime.ProtosFutureValue;
import com.guillermomolina.protos.runtime.ProtosNullValue;
import com.guillermomolina.protos.runtime.ProtosObjectValue;
import com.guillermomolina.protos.runtime.ProtosPrelude;
import com.guillermomolina.protos.runtime.ProtosSignalException;
import com.guillermomolina.protos.runtime.ProtosTask;
import com.guillermomolina.protos.runtime.ProtosValueLookup;
import com.guillermomolina.protos.semantic.Canonicalizer;
import com.guillermomolina.protos.semantic.ast.CanonicalClosure;
import com.guillermomolina.protos.semantic.ast.CanonicalSequence;
import com.oracle.truffle.api.TruffleLanguage.LanguageReference;
import com.oracle.truffle.api.source.Source;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;

final class ProtosPerf006B4FStructuredWhileTest {
    private static final LanguageReference<ProtosLanguage> LANGUAGE_REF =
            LanguageReference.create(ProtosLanguage.class);

    @Test
    void repeatedConditionInvocationsOwnFreshReturnHomesAndReturnLastBodyResult()
            throws Exception {
        try (LanguageScope scope = languageScope()) {
            ProtosPrelude prelude = core();
            ProtosActorExecutionDomain domain = new ProtosActorExecutionDomain();
            ProtosActivation module = activation(prelude, domain);
            AtomicInteger conditionCalls = new AtomicInteger();
            AtomicInteger bodyCalls = new AtomicInteger();
            java.util.concurrent.atomic.AtomicReference<ProtosObjectValue> bodyResult =
                    new java.util.concurrent.atomic.AtomicReference<>();

            module.context().createLocalSlot(
                    "conditionValue",
                    nativeClosure(
                            (activation, supplied) ->
                                    conditionCalls.incrementAndGet() == 1
                                            ? ProtosBooleanValue.TRUE
                                            : ProtosBooleanValue.FALSE));
            module.context().createLocalSlot(
                    "condition",
                    sourceClosure(
                            scope.language(),
                            module,
                            "() => ^conditionValue()",
                            "perf006-b4f-fresh-home-condition.protos"));
            module.context().createLocalSlot(
                    "body",
                    nativeClosure(
                            (activation, supplied) -> {
                                bodyCalls.incrementAndGet();
                                ProtosObjectValue fresh =
                                        new ProtosObjectValue(ProtosObjectValue.rootObject());
                                bodyResult.set(fresh);
                                return fresh;
                            }));

            ProtosTask task =
                    execute(
                            domain,
                            module,
                            lowerRoot(
                                    scope.language(),
                                    "condition.whileTrue(body)",
                                    "perf006-b4f-fresh-home-top.protos"));

            assertTrue(domain.dispatchOne());
            assertEquals(ProtosTask.State.COMPLETED, task.state());
            // D201: whileTrue returns the exact last normal body result.
            assertSame(bodyResult.get(), task.result().orElseThrow());
            assertEquals(2, conditionCalls.get());
            assertEquals(1, bodyCalls.get());
        }

        System.out.println("PERF006_B4F_FRESH_CALLBACK_RETURN_HOME_PER_ITERATION=PASS");
        System.out.println("D201_STRUCTURED_WHILE_TRUE_RETURNS_LAST_BODY=PASS");
    }

    @Test
    void conditionAndBodySuspensionResumeWithoutReplay()
            throws Exception {
        try (LanguageScope scope = languageScope()) {
            ProtosPrelude prelude = core();
            ProtosActorExecutionDomain domain = new ProtosActorExecutionDomain();
            ProtosActivation module = activation(prelude, domain);
            ProtosFutureValue conditionGate = new ProtosFutureValue(prelude.futurePrototype(), domain);
            ProtosFutureValue bodyGate = new ProtosFutureValue(prelude.futurePrototype(), domain);
            AtomicInteger conditionCalls = new AtomicInteger();
            AtomicInteger bodyPrefix = new AtomicInteger();

            module.context().createLocalSlot("conditionGate", conditionGate);
            module.context().createLocalSlot("bodyGate", bodyGate);
            module.context().createLocalSlot(
                    "conditionProbe",
                    nativeClosure(
                            (activation, supplied) ->
                                    conditionCalls.incrementAndGet() == 1
                                            ? ProtosBooleanValue.TRUE
                                            : ProtosBooleanValue.FALSE));
            module.context().createLocalSlot(
                    "bodyProbe",
                    nativeClosure(
                            (activation, supplied) -> {
                                bodyPrefix.incrementAndGet();
                                return ProtosNullValue.INSTANCE;
                            }));
            module.context().createLocalSlot(
                    "condition",
                    sourceClosure(
                            scope.language(),
                            module,
                            "() => { conditionGate.value()\nconditionProbe() }",
                            "perf006-b4f-suspend-condition.protos"));
            module.context().createLocalSlot(
                    "body",
                    sourceClosure(
                            scope.language(),
                            module,
                            "() => { bodyProbe()\nbodyGate.value() }",
                            "perf006-b4f-suspend-body.protos"));

            ProtosTask task =
                    execute(
                            domain,
                            module,
                            lowerRoot(
                                    scope.language(),
                                    "condition.whileTrue(body)",
                                    "perf006-b4f-suspend-top.protos"));

            assertTrue(domain.dispatchOne());
            assertEquals(ProtosTask.State.SUSPENDED, task.state());
            assertEquals(0, conditionCalls.get());
            assertEquals(0, bodyPrefix.get());

            assertTrue(conditionGate.resolve(ProtosNullValue.INSTANCE, module));
            assertTrue(domain.dispatchOne());
            assertEquals(ProtosTask.State.SUSPENDED, task.state());
            assertEquals(1, conditionCalls.get());
            assertEquals(1, bodyPrefix.get());

            ProtosObjectValue resumedBodyResult =
                    new ProtosObjectValue(ProtosObjectValue.rootObject());
            assertTrue(bodyGate.resolve(resumedBodyResult, module));
            assertTrue(domain.dispatchOne());
            assertEquals(ProtosTask.State.COMPLETED, task.state());
            // D201: the result produced by the resumed body survives the terminal condition.
            assertSame(resumedBodyResult, task.result().orElseThrow());
            assertEquals(2, conditionCalls.get());
            assertEquals(1, bodyPrefix.get(), "completed body prefix must not replay");
        }

        System.out.println("PERF006_B4F_CONDITION_SUSPENSION_RESUME=PASS");
        System.out.println("PERF006_B4F_BODY_SUSPENSION_RESUME=PASS");
        System.out.println("PERF006_B4F_COMPLETED_CALLBACK_REPLAY=NO");
    }

    @Test
    void nonBooleanConditionSignalsBeforeBodyActivation()
            throws Exception {
        try (LanguageScope scope = languageScope()) {
            ProtosPrelude prelude = core();
            ProtosActorExecutionDomain domain = new ProtosActorExecutionDomain();
            ProtosActivation module = activation(prelude, domain);
            AtomicInteger bodyCalls = new AtomicInteger();

            module.context().createLocalSlot(
                    "condition",
                    nativeClosure(
                            (activation, supplied) ->
                                    new ProtosObjectValue(ProtosObjectValue.rootObject())));
            module.context().createLocalSlot(
                    "body",
                    nativeClosure(
                            (activation, supplied) -> {
                                bodyCalls.incrementAndGet();
                                return ProtosNullValue.INSTANCE;
                            }));

            ProtosTask task =
                    execute(
                            domain,
                            module,
                            lowerRoot(
                                    scope.language(),
                                    "condition.whileTrue(body)",
                                    "perf006-b4f-invalid-condition.protos"));

            assertTrue(domain.dispatchOne());
            assertEquals(ProtosTask.State.FAILED, task.state());
            assertInstanceOf(ProtosObjectValue.class, task.failure().orElseThrow());
            assertEquals(0, bodyCalls.get());
        }

        System.out.println("PERF006_B4F_STRICT_CANONICAL_BOOLEAN=PASS");
        System.out.println("PERF006_B4F_INVALID_CONDITION_SKIPS_BODY=PASS");
    }

    @Test
    void invalidBodyIsRejectedBeforeFirstConditionActivation()
            throws Exception {
        try (LanguageScope scope = languageScope()) {
            ProtosPrelude prelude = core();
            ProtosActorExecutionDomain domain = new ProtosActorExecutionDomain();
            ProtosActivation module = activation(prelude, domain);
            AtomicInteger conditionCalls = new AtomicInteger();

            module.context().createLocalSlot(
                    "condition",
                    nativeClosure(
                            (activation, supplied) -> {
                                conditionCalls.incrementAndGet();
                                return ProtosBooleanValue.FALSE;
                            }));
            module.context().createLocalSlot(
                    "notBody",
                    new ProtosObjectValue(ProtosObjectValue.rootObject()));

            assertThrows(
                    ProtosSignalException.class,
                    () ->
                            lowerRoot(
                                            scope.language(),
                                            "condition.whileTrue(notBody)",
                                            "perf006-b4f-validation.protos")
                                    .getCallTarget()
                                    .call(module));
            assertEquals(0, conditionCalls.get());
        }

        System.out.println("PERF006_B4F_VALIDATION_BEFORE_FIRST_CONDITION=PASS");
    }

    @Test
    void bodyFutureResultIsReturnedWithoutObservationAdoptionOrCancellation()
            throws Exception {
        try (LanguageScope scope = languageScope()) {
            ProtosPrelude prelude = core();
            ProtosActorExecutionDomain domain = new ProtosActorExecutionDomain();
            ProtosActivation module = activation(prelude, domain);
            ProtosFutureValue bodyFuture = new ProtosFutureValue(prelude.futurePrototype(), domain);
            AtomicInteger conditionCalls = new AtomicInteger();
            AtomicInteger bodyCalls = new AtomicInteger();

            module.context().createLocalSlot(
                    "condition",
                    nativeClosure(
                            (activation, supplied) ->
                                    conditionCalls.incrementAndGet() == 1
                                            ? ProtosBooleanValue.TRUE
                                            : ProtosBooleanValue.FALSE));
            module.context().createLocalSlot(
                    "body",
                    nativeClosure(
                            (activation, supplied) -> {
                                bodyCalls.incrementAndGet();
                                return bodyFuture;
                            }));

            ProtosTask task =
                    execute(
                            domain,
                            module,
                            lowerRoot(
                                    scope.language(),
                                    "condition.whileTrue(body)",
                                    "perf006-b4f-body-future.protos"));

            assertTrue(domain.dispatchOne());
            assertEquals(ProtosTask.State.COMPLETED, task.state());
            // D201: the body Future itself is the loop result; it is not awaited.
            assertSame(bodyFuture, task.result().orElseThrow());
            assertEquals(ProtosFutureValue.State.PENDING, bodyFuture.state());
            assertEquals(2, conditionCalls.get());
            assertEquals(1, bodyCalls.get());

            assertTrue(bodyFuture.resolve(ProtosNullValue.INSTANCE, module));
            assertFalse(domain.dispatchOne(), "returned body Future must own no loop waiter");
        }

        System.out.println("D201_STRUCTURED_WHILE_TRUE_RETURNS_BODY_FUTURE=PASS");
        System.out.println("PERF006_B4F_BODY_FUTURE_OBSERVATION=NO");
    }

    @Test
    void cancellationThroughSuspendedConditionCrossesOuterEnsureExactlyOnce()
            throws Exception {
        try (LanguageScope scope = languageScope()) {
            ProtosPrelude prelude = core();
            ProtosActorExecutionDomain domain = new ProtosActorExecutionDomain();
            ProtosActivation module = activation(prelude, domain);
            ProtosFutureValue gate = new ProtosFutureValue(prelude.futurePrototype(), domain);
            AtomicInteger bodyCalls = new AtomicInteger();
            AtomicInteger cleanups = new AtomicInteger();

            module.context().createLocalSlot("gate", gate);
            module.context().createLocalSlot(
                    "condition",
                    sourceClosure(
                            scope.language(),
                            module,
                            "() => gate.value()",
                            "perf006-b4f-cancel-condition.protos"));
            module.context().createLocalSlot(
                    "body",
                    nativeClosure(
                            (activation, supplied) -> {
                                bodyCalls.incrementAndGet();
                                return ProtosNullValue.INSTANCE;
                            }));
            module.context().createLocalSlot(
                    "loop",
                    sourceClosure(
                            scope.language(),
                            module,
                            "() => condition.whileTrue(body)",
                            "perf006-b4f-cancel-loop.protos"));
            module.context().createLocalSlot(
                    "cleanup",
                    nativeClosure(
                            (activation, supplied) -> {
                                cleanups.incrementAndGet();
                                return ProtosNullValue.INSTANCE;
                            }));

            Execution execution =
                    executeWithFuture(
                            domain,
                            prelude,
                            module,
                            lowerRoot(
                                    scope.language(),
                                    "loop.ensure(cleanup)",
                                    "perf006-b4f-cancel-top.protos"));

            assertTrue(domain.dispatchOne());
            assertEquals(ProtosTask.State.SUSPENDED, execution.task().state());
            assertEquals(0, bodyCalls.get());
            assertEquals(0, cleanups.get());

            assertTrue(execution.future().cancelRequest());
            assertTrue(domain.dispatchOne());
            assertEquals(ProtosTask.State.CANCELLED, execution.task().state());
            assertEquals(ProtosFutureValue.State.CANCELLED, execution.future().state());
            assertEquals(0, bodyCalls.get());
            assertEquals(1, cleanups.get());
            assertEquals(ProtosFutureValue.State.PENDING, gate.state());

            assertTrue(gate.resolve(ProtosNullValue.INSTANCE, module));
            assertFalse(domain.dispatchOne(), "cancelled condition waiter must remain detached");
        }

        System.out.println("PERF006_B4F_CANCELLATION_NO_LOOP_POLL=PASS");
        System.out.println("PERF006_B4F_CANCELLATION_CROSSES_ENSURE_ONCE=PASS");
    }

    @Test
    void bodyErrorStopsLoopBeforeAnotherConditionActivation()
            throws Exception {
        try (LanguageScope scope = languageScope()) {
            ProtosPrelude prelude = core();
            ProtosActorExecutionDomain domain = new ProtosActorExecutionDomain();
            ProtosActivation module = activation(prelude, domain);
            ProtosObjectValue error = ProtosCoreErrors.newError(module);
            AtomicInteger conditionCalls = new AtomicInteger();

            module.context().createLocalSlot("error", error);
            module.context().createLocalSlot(
                    "condition",
                    nativeClosure(
                            (activation, supplied) -> {
                                conditionCalls.incrementAndGet();
                                return ProtosBooleanValue.TRUE;
                            }));
            module.context().createLocalSlot(
                    "body",
                    sourceClosure(
                            scope.language(),
                            module,
                            "() => error.signal()",
                            "perf006-b4f-error-body.protos"));

            ProtosTask task =
                    execute(
                            domain,
                            module,
                            lowerRoot(
                                    scope.language(),
                                    "condition.whileTrue(body)",
                                    "perf006-b4f-error-top.protos"));

            assertTrue(domain.dispatchOne());
            assertEquals(ProtosTask.State.FAILED, task.state());
            assertSame(error, task.failure().orElseThrow());
            assertEquals(1, conditionCalls.get());
        }

        System.out.println("PERF006_B4F_BODY_ERROR_STOPS_LOOP=PASS");
        System.out.println("PERF006_B4F_CONTROL_TRANSFER_RECHECKS_CONDITION=NO");
    }

    @Test
    void standardWhileCapabilitySurvivesExtractionButSelectorOverrideIsOrdinary()
            throws Exception {
        try (LanguageScope scope = languageScope()) {
            ProtosPrelude prelude = core();
            ProtosActorExecutionDomain domain = new ProtosActorExecutionDomain();
            ProtosActivation module = activation(prelude, domain);
            AtomicInteger conditionCalls = new AtomicInteger();
            AtomicInteger bodyCalls = new AtomicInteger();

            ProtosClosureValue condition =
                    nativeClosure(
                            (activation, supplied) -> {
                                conditionCalls.incrementAndGet();
                                return ProtosBooleanValue.FALSE;
                            });
            ProtosClosureValue extracted =
                    assertInstanceOf(
                            ProtosClosureValue.class,
                            ProtosValueLookup.readMember(condition, "whileTrue", prelude).orElseThrow());
            module.context().createLocalSlot("whileImpl", extracted);
            module.context().createLocalSlot(
                    "body",
                    nativeClosure(
                            (activation, supplied) -> {
                                bodyCalls.incrementAndGet();
                                return ProtosNullValue.INSTANCE;
                            }));

            ProtosTask extractedTask =
                    execute(
                            domain,
                            module,
                            lowerRoot(
                                    scope.language(),
                                    "whileImpl(body)",
                                    "perf006-b4f-extracted.protos"));
            assertTrue(domain.dispatchOne());
            assertEquals(ProtosTask.State.COMPLETED, extractedTask.state());
            assertSame(ProtosNullValue.INSTANCE, extractedTask.result().orElseThrow());
            assertEquals(1, conditionCalls.get());
            assertEquals(0, bodyCalls.get());

            ProtosActivation module2 = activation(prelude, domain);
            ProtosObjectValue overrideResult = new ProtosObjectValue(ProtosObjectValue.rootObject());
            AtomicInteger overrideCalls = new AtomicInteger();
            ProtosObjectValue custom = new ProtosObjectValue(ProtosObjectValue.rootObject());
            custom.createLocalSlot(
                    "whileTrue",
                    nativeClosure(
                            (activation, supplied) -> {
                                overrideCalls.incrementAndGet();
                                return overrideResult;
                            }));
            module2.context().createLocalSlot("custom", custom);
            module2.context().createLocalSlot("body", module.context().readLocalSlot("body").orElseThrow());

            ProtosTask overrideTask =
                    execute(
                            domain,
                            module2,
                            lowerRoot(
                                    scope.language(),
                                    "custom.whileTrue(body)",
                                    "perf006-b4f-override.protos"));
            assertTrue(domain.dispatchOne());
            assertEquals(ProtosTask.State.COMPLETED, overrideTask.state());
            assertSame(overrideResult, overrideTask.result().orElseThrow());
            assertEquals(1, overrideCalls.get());
            assertEquals(0, bodyCalls.get());
        }

        System.out.println("PERF006_B4F_EXTRACTED_WHILE_PROVENANCE=PASS");
        System.out.println("PERF006_B4F_WHILE_SELECTOR_PET=NO");
    }

    @Test
    void standardWhileSharesTheLoopButReturnsFinalFalseCondition()
            throws Exception {
        try (LanguageScope scope = languageScope()) {
            ProtosPrelude prelude = core();
            ProtosActorExecutionDomain domain = new ProtosActorExecutionDomain();
            ProtosActivation module = activation(prelude, domain);
            ProtosFutureValue bodyGate = new ProtosFutureValue(prelude.futurePrototype(), domain);
            AtomicInteger conditionCalls = new AtomicInteger();
            AtomicInteger bodyPrefix = new AtomicInteger();

            module.context().createLocalSlot("bodyGate", bodyGate);
            module.context().createLocalSlot(
                    "condition",
                    nativeClosure(
                            (activation, supplied) ->
                                    conditionCalls.incrementAndGet() <= 2
                                            ? ProtosBooleanValue.TRUE
                                            : ProtosBooleanValue.FALSE));
            module.context().createLocalSlot(
                    "bodyProbe",
                    nativeClosure(
                            (activation, supplied) -> {
                                bodyPrefix.incrementAndGet();
                                return ProtosNullValue.INSTANCE;
                            }));
            module.context().createLocalSlot(
                    "body",
                    sourceClosure(
                            scope.language(),
                            module,
                            "() => { bodyProbe()\nbodyGate.value() }",
                            "d201-while-suspend-body.protos"));

            ProtosTask task =
                    execute(
                            domain,
                            module,
                            lowerRoot(
                                    scope.language(),
                                    "condition.while(body)",
                                    "d201-while-top.protos"));

            assertTrue(domain.dispatchOne());
            assertEquals(ProtosTask.State.SUSPENDED, task.state());
            assertEquals(1, conditionCalls.get());
            assertEquals(1, bodyPrefix.get());

            assertTrue(bodyGate.resolve(
                    new ProtosObjectValue(ProtosObjectValue.rootObject()), module));
            assertTrue(domain.dispatchOne());
            assertEquals(ProtosTask.State.COMPLETED, task.state());
            assertSame(ProtosBooleanValue.FALSE, task.result().orElseThrow());
            assertEquals(3, conditionCalls.get());
            assertEquals(2, bodyPrefix.get(), "completed body prefix must not replay");
        }

        System.out.println("D201_STRUCTURED_WHILE_RETURNS_FINAL_FALSE=PASS");
    }

    /**
     * D201 direct invocation of the extracted standard loop behaviors (no
     * send-site structured lowering): {@code whileTrue} returns the exact last
     * body result, {@code while} the final canonical {@code false}, and a
     * non-Closure body is rejected before the first condition activation.
     */
    @Test
    void directInvocationOfBothSelectorsAppliesTheirResultPolicies()
            throws Exception {
        try (LanguageScope scope = languageScope()) {
            ProtosPrelude prelude = core();
            ProtosActorExecutionDomain domain = new ProtosActorExecutionDomain();
            ProtosActivation module = activation(prelude, domain);
            AtomicInteger conditionCalls = new AtomicInteger();
            AtomicInteger bodyCalls = new AtomicInteger();
            List<ProtosObjectValue> bodyResults = new java.util.ArrayList<>();

            ProtosClosureValue condition =
                    nativeClosure(
                            (activation, supplied) ->
                                    conditionCalls.incrementAndGet() % 3 != 0
                                            ? ProtosBooleanValue.TRUE
                                            : ProtosBooleanValue.FALSE);
            ProtosClosureValue body =
                    nativeClosure(
                            (activation, supplied) -> {
                                bodyCalls.incrementAndGet();
                                ProtosObjectValue fresh =
                                        new ProtosObjectValue(ProtosObjectValue.rootObject());
                                bodyResults.add(fresh);
                                return fresh;
                            });
            ProtosClosureValue whileTrue =
                    assertInstanceOf(
                            ProtosClosureValue.class,
                            ProtosValueLookup.readMember(condition, "whileTrue", prelude)
                                    .orElseThrow());
            ProtosClosureValue whileLoop =
                    assertInstanceOf(
                            ProtosClosureValue.class,
                            ProtosValueLookup.readMember(condition, "while", prelude)
                                    .orElseThrow());

            Object trueResult = ProtosClosureInvoker.invoke(whileTrue, List.of(body), module);
            assertEquals(3, conditionCalls.get());
            assertEquals(2, bodyCalls.get());
            assertSame(bodyResults.get(1), trueResult);

            Object whileResult = ProtosClosureInvoker.invoke(whileLoop, List.of(body), module);
            assertEquals(6, conditionCalls.get());
            assertEquals(4, bodyCalls.get());
            assertSame(ProtosBooleanValue.FALSE, whileResult);

            assertThrows(
                    ProtosSignalException.class,
                    () -> ProtosClosureInvoker.invoke(
                            whileLoop,
                            List.of(new ProtosObjectValue(ProtosObjectValue.rootObject())),
                            module));
            assertEquals(6, conditionCalls.get(), "invalid body must precede the condition");
        }

        System.out.println("D201_DIRECT_WHILE_TRUE_RETURNS_LAST_BODY=PASS");
        System.out.println("D201_DIRECT_WHILE_RETURNS_FINAL_FALSE=PASS");
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
        return assertInstanceOf(CanonicalClosure.class, sequence.expressions().get(0));
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
