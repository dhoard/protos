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
 * the specific language governing rights and limitations under the LICENSE.
 */

package com.guillermomolina.protos.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
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

final class ProtosPerf006B4DErrorHandlersTest {
    private static final LanguageReference<ProtosLanguage> LANGUAGE_REF =
            LanguageReference.create(ProtosLanguage.class);

    @Test
    void bytecodeGeneratedErrorIsSelectedBeforeHandlerCatchAndPreservesOccurrence()
            throws Exception {
        try (LanguageScope scope = languageScope()) {
            ProtosPrelude prelude = core();
            ProtosActorExecutionDomain domain = new ProtosActorExecutionDomain();
            ProtosActivation module = activation(prelude, domain);
            AtomicInteger handlerCalls = new AtomicInteger();

            module.context().createLocalSlot(
                    "body",
                    sourceClosure(
                            scope.language(),
                            module,
                            "() => missingName",
                            "perf006-b4d-generated-error-body.protos"));
            module.context().createLocalSlot(
                    "handler",
                    nativeClosure(
                            (activation, supplied) -> {
                                handlerCalls.incrementAndGet();
                                return supplied.get(0);
                            }));

            Object result =
                    lowerRoot(
                                    scope.language(),
                                    "Error.handle(body, handler)",
                                    "perf006-b4d-generated-error-top.protos")
                            .getCallTarget()
                            .call(module);

            ProtosObjectValue error = assertInstanceOf(ProtosObjectValue.class, result);
            assertSame(
                    ProtosCoreErrors.prototype(
                            module,
                            ProtosCoreErrors.StandardError.SLOT_NOT_FOUND),
                    error.parent().orElseThrow());
            assertEquals(1, handlerCalls.get());
        }

        System.out.println("PERF006_B4D_BYTECODE_ERROR_SELECTION=PASS");
        System.out.println("PERF006_B4D_EXACT_ERROR_OCCURRENCE=PASS");
    }

    @Test
    void standardSignalRunsOnTaskBackedCPrimeAndHandlerReceivesExactError()
            throws Exception {
        try (LanguageScope scope = languageScope()) {
            ProtosPrelude prelude = core();
            ProtosActorExecutionDomain domain = new ProtosActorExecutionDomain();
            ProtosActivation module = activation(prelude, domain);
            ProtosObjectValue error = ProtosCoreErrors.newError(module);
            AtomicInteger handlerCalls = new AtomicInteger();

            module.context().createLocalSlot("error", error);
            module.context().createLocalSlot(
                    "body",
                    sourceClosure(
                            scope.language(),
                            module,
                            "() => error.signal()",
                            "perf006-b4d-signal-body.protos"));
            module.context().createLocalSlot(
                    "handler",
                    nativeClosure(
                            (activation, supplied) -> {
                                handlerCalls.incrementAndGet();
                                assertSame(error, supplied.get(0));
                                return supplied.get(0);
                            }));

            ProtosTask task =
                    execute(
                            domain,
                            module,
                            lowerRoot(
                                    scope.language(),
                                    "Error.handle(body, handler)",
                                    "perf006-b4d-signal-top.protos"));

            assertTrue(domain.dispatchOne());
            assertEquals(ProtosTask.State.COMPLETED, task.state());
            assertSame(error, task.result().orElseThrow());
            assertEquals(1, handlerCalls.get());
        }

        System.out.println("PERF006_B4D_STANDARD_SIGNAL_CPRIME=PASS");
        System.out.println("PERF006_B4D_STANDARD_SIGNAL_EXACT_IDENTITY=PASS");
    }

    @Test
    void innermostMatchingHandlerWinsAndOuterRemainsUninvoked()
            throws Exception {
        try (LanguageScope scope = languageScope()) {
            ProtosPrelude prelude = core();
            ProtosActorExecutionDomain domain = new ProtosActorExecutionDomain();
            ProtosActivation module = activation(prelude, domain);
            ProtosObjectValue error = ProtosCoreErrors.newError(module);
            ProtosObjectValue innerResult = new ProtosObjectValue(ProtosObjectValue.rootObject());
            AtomicInteger innerCalls = new AtomicInteger();
            AtomicInteger outerCalls = new AtomicInteger();

            module.context().createLocalSlot("error", error);
            module.context().createLocalSlot(
                    "signalBody",
                    sourceClosure(
                            scope.language(),
                            module,
                            "() => error.signal()",
                            "perf006-b4d-inner-signal.protos"));
            module.context().createLocalSlot(
                    "innerHandler",
                    nativeClosure(
                            (activation, supplied) -> {
                                innerCalls.incrementAndGet();
                                assertSame(error, supplied.get(0));
                                return innerResult;
                            }));
            module.context().createLocalSlot(
                    "outerHandler",
                    nativeClosure(
                            (activation, supplied) -> {
                                outerCalls.incrementAndGet();
                                return supplied.get(0);
                            }));
            module.context().createLocalSlot(
                    "innerProtected",
                    sourceClosure(
                            scope.language(),
                            module,
                            "() => Error.handle(signalBody, innerHandler)",
                            "perf006-b4d-inner-protected.protos"));

            Object result =
                    lowerRoot(
                                    scope.language(),
                                    "Error.handle(innerProtected, outerHandler)",
                                    "perf006-b4d-nested-top.protos")
                            .getCallTarget()
                            .call(module);

            assertSame(innerResult, result);
            assertEquals(1, innerCalls.get());
            assertEquals(0, outerCalls.get());
        }

        System.out.println("PERF006_B4D_INNERMOST_HANDLER=PASS");
    }

    @Test
    void selectedInnerHandlerIsInactiveBeforeCrossedEnsureCleanup()
            throws Exception {
        try (LanguageScope scope = languageScope()) {
            ProtosPrelude prelude = core();
            ProtosActorExecutionDomain domain = new ProtosActorExecutionDomain();
            ProtosActivation module = activation(prelude, domain);
            ProtosObjectValue original = ProtosCoreErrors.newError(module);
            ProtosObjectValue replacement = ProtosCoreErrors.newError(module);
            AtomicInteger innerHandlerCalls = new AtomicInteger();
            AtomicInteger outerHandlerCalls = new AtomicInteger();

            module.context().createLocalSlot("original", original);
            module.context().createLocalSlot("replacement", replacement);
            module.context().createLocalSlot(
                    "signalOriginal",
                    sourceClosure(
                            scope.language(),
                            module,
                            "() => original.signal()",
                            "perf006-b4d-crossed-original.protos"));
            module.context().createLocalSlot(
                    "signalReplacement",
                    sourceClosure(
                            scope.language(),
                            module,
                            "() => replacement.signal()",
                            "perf006-b4d-crossed-replacement.protos"));
            module.context().createLocalSlot(
                    "protectedWithEnsure",
                    sourceClosure(
                            scope.language(),
                            module,
                            "() => signalOriginal.ensure(signalReplacement)",
                            "perf006-b4d-crossed-ensure.protos"));
            module.context().createLocalSlot(
                    "innerHandler",
                    nativeClosure(
                            (activation, supplied) -> {
                                innerHandlerCalls.incrementAndGet();
                                return supplied.get(0);
                            }));
            module.context().createLocalSlot(
                    "outerHandler",
                    nativeClosure(
                            (activation, supplied) -> {
                                outerHandlerCalls.incrementAndGet();
                                assertSame(replacement, supplied.get(0));
                                return supplied.get(0);
                            }));
            module.context().createLocalSlot(
                    "innerProtected",
                    sourceClosure(
                            scope.language(),
                            module,
                            "() => Error.handle(protectedWithEnsure, innerHandler)",
                            "perf006-b4d-crossed-inner.protos"));

            Object result =
                    lowerRoot(
                                    scope.language(),
                                    "Error.handle(innerProtected, outerHandler)",
                                    "perf006-b4d-crossed-top.protos")
                            .getCallTarget()
                            .call(module);

            assertSame(replacement, result);
            assertEquals(0, innerHandlerCalls.get());
            assertEquals(1, outerHandlerCalls.get());
        }

        System.out.println("PERF006_B4D_SELECTED_HANDLER_INACTIVE_BEFORE_CLEANUP=PASS");
        System.out.println("PERF006_B4D_CLEANUP_ERROR_SKIPS_CONSUMED_HANDLER=PASS");
        System.out.println("PERF006_B4D_CLEANUP_ERROR_SUPERSEDES_ORIGINAL=PASS");
    }

    @Test
    void suspendedProtectedBodyRetainsTaskLocalHandlerWithoutReplay()
            throws Exception {
        try (LanguageScope scope = languageScope()) {
            ProtosPrelude prelude = core();
            ProtosActorExecutionDomain domain = new ProtosActorExecutionDomain();
            ProtosActivation module = activation(prelude, domain);
            ProtosFutureValue future = new ProtosFutureValue(prelude.futurePrototype(), domain);
            ProtosObjectValue error = ProtosCoreErrors.newError(module);
            AtomicInteger prefixCalls = new AtomicInteger();
            AtomicInteger handlerCalls = new AtomicInteger();

            module.context().createLocalSlot("future", future);
            module.context().createLocalSlot(
                    "probe",
                    nativeClosure(
                            (activation, supplied) -> {
                                prefixCalls.incrementAndGet();
                                return ProtosNullValue.INSTANCE;
                            }));
            module.context().createLocalSlot(
                    "body",
                    sourceClosure(
                            scope.language(),
                            module,
                            "() => { probe()\nfuture.value() }",
                            "perf006-b4d-suspended-body.protos"));
            module.context().createLocalSlot(
                    "handler",
                    nativeClosure(
                            (activation, supplied) -> {
                                handlerCalls.incrementAndGet();
                                return supplied.get(0);
                            }));

            ProtosTask task =
                    execute(
                            domain,
                            module,
                            lowerRoot(
                                    scope.language(),
                                    "Error.handle(body, handler)",
                                    "perf006-b4d-suspended-top.protos"));

            assertTrue(domain.dispatchOne());
            assertEquals(ProtosTask.State.SUSPENDED, task.state());
            assertEquals(1, prefixCalls.get());
            assertEquals(0, handlerCalls.get());

            assertTrue(future.fail(error));
            assertEquals(ProtosTask.State.RUNNABLE, task.state());
            assertTrue(domain.dispatchOne());
            assertEquals(ProtosTask.State.COMPLETED, task.state());
            assertSame(error, task.result().orElseThrow());
            assertEquals(1, prefixCalls.get());
            assertEquals(1, handlerCalls.get());
        }

        System.out.println("PERF006_B4D_HANDLER_SCOPE_SURVIVES_SUSPENSION=PASS");
        System.out.println("PERF006_B4D_PROTECTED_PREFIX_REPLAY=NO");
    }

    @Test
    void selectedHandlerRemainsRemovedWhileItsHandlerClosureSuspends()
            throws Exception {
        try (LanguageScope scope = languageScope()) {
            ProtosPrelude prelude = core();
            ProtosActorExecutionDomain domain = new ProtosActorExecutionDomain();
            ProtosActivation module = activation(prelude, domain);
            ProtosObjectValue first = ProtosCoreErrors.newError(module);
            ProtosObjectValue second = ProtosCoreErrors.newError(module);
            ProtosFutureValue handlerFuture = new ProtosFutureValue(prelude.futurePrototype(), domain);
            AtomicInteger outerCalls = new AtomicInteger();

            module.context().createLocalSlot("first", first);
            module.context().createLocalSlot("handlerFuture", handlerFuture);
            module.context().createLocalSlot(
                    "innerBody",
                    sourceClosure(
                            scope.language(),
                            module,
                            "() => first.signal()",
                            "perf006-b4d-handler-suspend-body.protos"));
            module.context().createLocalSlot(
                    "innerHandler",
                    sourceClosure(
                            scope.language(),
                            module,
                            "(error) => handlerFuture.value()",
                            "perf006-b4d-handler-suspend-handler.protos"));
            module.context().createLocalSlot(
                    "outerHandler",
                    nativeClosure(
                            (activation, supplied) -> {
                                outerCalls.incrementAndGet();
                                assertSame(second, supplied.get(0));
                                return supplied.get(0);
                            }));
            module.context().createLocalSlot(
                    "outerBody",
                    sourceClosure(
                            scope.language(),
                            module,
                            "() => Error.handle(innerBody, innerHandler)",
                            "perf006-b4d-handler-suspend-outer-body.protos"));

            ProtosTask task =
                    execute(
                            domain,
                            module,
                            lowerRoot(
                                    scope.language(),
                                    "Error.handle(outerBody, outerHandler)",
                                    "perf006-b4d-handler-suspend-top.protos"));

            assertTrue(domain.dispatchOne());
            assertEquals(ProtosTask.State.SUSPENDED, task.state());
            assertEquals(0, outerCalls.get());

            assertTrue(handlerFuture.fail(second));
            assertTrue(domain.dispatchOne());
            assertEquals(ProtosTask.State.COMPLETED, task.state());
            assertSame(second, task.result().orElseThrow());
            assertEquals(1, outerCalls.get());
        }

        System.out.println("PERF006_B4D_SELECTED_HANDLER_REMOVED_BEFORE_HANDLER=PASS");
        System.out.println("PERF006_B4D_HANDLER_SELF_REENTRY=NO");
        System.out.println("PERF006_B4D_HANDLER_SUSPENSION_RESUME=PASS");
    }

    @Test
    void standardHandleCapabilitySurvivesExtractionButSelectorOverrideIsOrdinary()
            throws Exception {
        try (LanguageScope scope = languageScope()) {
            ProtosPrelude prelude = core();
            ProtosActorExecutionDomain domain = new ProtosActorExecutionDomain();
            ProtosActivation module = activation(prelude, domain);
            ProtosObjectValue bodyResult = new ProtosObjectValue(ProtosObjectValue.rootObject());
            ProtosObjectValue overrideResult = new ProtosObjectValue(ProtosObjectValue.rootObject());
            AtomicInteger bodyCalls = new AtomicInteger();
            AtomicInteger overrideCalls = new AtomicInteger();

            ProtosClosureValue extracted =
                    assertInstanceOf(
                            ProtosClosureValue.class,
                            ProtosValueLookup.readMember(
                                            prelude.errorPrototype(),
                                            "handle",
                                            prelude)
                                    .orElseThrow());
            module.context().createLocalSlot("handleImpl", extracted);
            module.context().createLocalSlot(
                    "body",
                    nativeClosure(
                            (activation, supplied) -> {
                                bodyCalls.incrementAndGet();
                                return bodyResult;
                            }));
            module.context().createLocalSlot(
                    "handler",
                    nativeClosure((activation, supplied) -> supplied.get(0)));

            ProtosTask extractedTask =
                    execute(
                            domain,
                            module,
                            lowerRoot(
                                    scope.language(),
                                    "handleImpl(body, handler)",
                                    "perf006-b4d-extracted-handle.protos"));
            assertTrue(domain.dispatchOne());
            assertEquals(ProtosTask.State.COMPLETED, extractedTask.state());
            assertSame(bodyResult, extractedTask.result().orElseThrow());
            assertEquals(1, bodyCalls.get());

            ProtosActivation module2 = activation(prelude, domain);
            ProtosObjectValue customErrorPrototype = new ProtosObjectValue(prelude.errorPrototype());
            customErrorPrototype.createLocalSlot(
                    "handle",
                    nativeClosure(
                            (activation, supplied) -> {
                                overrideCalls.incrementAndGet();
                                return overrideResult;
                            }));
            module2.context().createLocalSlot("custom", customErrorPrototype);
            module2.context().createLocalSlot("body", module.context().readLocalSlot("body").orElseThrow());
            module2.context().createLocalSlot("handler", module.context().readLocalSlot("handler").orElseThrow());

            ProtosTask overrideTask =
                    execute(
                            domain,
                            module2,
                            lowerRoot(
                                    scope.language(),
                                    "custom.handle(body, handler)",
                                    "perf006-b4d-selector-override.protos"));
            assertTrue(domain.dispatchOne());
            assertEquals(ProtosTask.State.COMPLETED, overrideTask.state());
            assertSame(overrideResult, overrideTask.result().orElseThrow());
            assertEquals(1, overrideCalls.get());
            assertEquals(1, bodyCalls.get(), "override must not enter the standard handler body");
        }

        System.out.println("PERF006_B4D_EXTRACTED_HANDLE_PROVENANCE=PASS");
        System.out.println("PERF006_B4D_HANDLER_SELECTOR_PET=NO");
    }

    @Test
    void invalidHandlerIsRejectedBeforeFrameInstallationAndBodyEntry()
            throws Exception {
        try (LanguageScope scope = languageScope()) {
            ProtosPrelude prelude = core();
            ProtosActorExecutionDomain domain = new ProtosActorExecutionDomain();
            ProtosActivation module = activation(prelude, domain);
            AtomicInteger bodyCalls = new AtomicInteger();

            module.context().createLocalSlot(
                    "body",
                    nativeClosure(
                            (activation, supplied) -> {
                                bodyCalls.incrementAndGet();
                                return ProtosNullValue.INSTANCE;
                            }));
            module.context().createLocalSlot(
                    "notHandler",
                    new ProtosObjectValue(ProtosObjectValue.rootObject()));

            assertThrows(
                    ProtosSignalException.class,
                    () ->
                            lowerRoot(
                                            scope.language(),
                                            "Error.handle(body, notHandler)",
                                            "perf006-b4d-validation.protos")
                                    .getCallTarget()
                                    .call(module));
            assertEquals(0, bodyCalls.get());
        }

        System.out.println("PERF006_B4D_VALIDATION_BEFORE_HANDLER_INSTALL=PASS");
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
        return assertInstanceOf(
                CanonicalClosure.class,
                sequence.expressions().get(0));
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

    private record LanguageScope(Context context, ProtosLanguage language)
            implements AutoCloseable {
        @Override
        public void close() {
            context.leave();
            context.close();
        }
    }
}
