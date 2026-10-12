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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.guillermomolina.protos.parser.ProtosParser;
import com.guillermomolina.protos.runtime.ProtosActivation;
import com.guillermomolina.protos.runtime.ProtosActorExecutionDomain;
import com.guillermomolina.protos.runtime.ProtosActorModuleState;
import com.guillermomolina.protos.runtime.ProtosClosureValue;
import com.guillermomolina.protos.runtime.ProtosFutureValue;
import com.guillermomolina.protos.runtime.ProtosIntegerValue;
import com.guillermomolina.protos.runtime.ProtosNonLocalReturnException;
import com.guillermomolina.protos.runtime.ProtosNullValue;
import com.guillermomolina.protos.runtime.ProtosObjectValue;
import com.guillermomolina.protos.runtime.ProtosPrelude;
import com.guillermomolina.protos.runtime.ProtosReturnHome;
import com.guillermomolina.protos.runtime.ProtosSignalException;
import com.guillermomolina.protos.runtime.ProtosTask;
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

final class ProtosPerf006B4BNonLocalReturnTest {
    private static final LanguageReference<ProtosLanguage> LANGUAGE_REF =
            LanguageReference.create(ProtosLanguage.class);

    @Test
    void ownedBytecodeClosureConsumesDirectCanonicalReturn() throws Exception {
        try (LanguageScope scope = languageScope()) {
            ProtosPrelude prelude = core();
            ProtosActorExecutionDomain domain = new ProtosActorExecutionDomain();
            ProtosActivation module = activation(prelude, domain);

            String closureCharacters = "() => ^42";
            CanonicalClosure definition = closureDefinition(closureCharacters);
            ProtosClosureExecutionPlan plan =
                    ProtosClosureExecutionPlan.bytecode(
                            definition,
                            scope.language(),
                            source(closureCharacters, "perf006-b4b-direct-closure.protos"));
            module.context().createLocalSlot(
                    "entry",
                    semanticClosure(definition, plan, module));

            Object result =
                    lowerRoot(
                                    scope.language(),
                                    "entry()",
                                    "perf006-b4b-direct-top.protos")
                            .getCallTarget()
                            .call(module);

            ProtosIntegerValue integer =
                    assertInstanceOf(ProtosIntegerValue.class, result);
            assertEquals(String.valueOf("42"), com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerDecimalText(integer));
        }

        System.out.println("PERF006_B4B_DIRECT_OWNER_CONSUMPTION=PASS");
    }

    @Test
    void capturedHomeReturnPropagatesExactTargetAndPayload() throws Exception {
        try (LanguageScope scope = languageScope()) {
            ProtosPrelude prelude = core();
            ProtosActorExecutionDomain domain = new ProtosActorExecutionDomain();
            ProtosActivation creator = activation(prelude, domain);
            ProtosObjectValue payload =
                    new ProtosObjectValue(ProtosObjectValue.rootObject());
            creator.context().createLocalSlot("payload", payload);

            String closureCharacters = "() => ^payload";
            CanonicalClosure definition = closureDefinition(closureCharacters);
            ProtosClosureExecutionPlan plan =
                    ProtosClosureExecutionPlan.bytecode(
                            definition,
                            scope.language(),
                            source(closureCharacters, "perf006-b4b-captured-home.protos"));
            ProtosReturnHome capturedHome = new ProtosReturnHome();
            ProtosClosureValue closure =
                    semanticClosureWithHome(
                            definition,
                            plan,
                            creator,
                            capturedHome);
            ProtosActivation invocation =
                    ProtosActivation.forClosureInvocation(
                            closure,
                            List.of(),
                            prelude,
                            creator.actorModuleState(),
                            creator.currentModuleKey().orElse(null),
                            domain);

            ProtosNonLocalReturnException transfer =
                    assertThrows(
                            ProtosNonLocalReturnException.class,
                            () -> plan.executeBody(invocation));

            assertSame(capturedHome, transfer.target());
            assertSame(payload, transfer.value());
            assertTrue(capturedHome.isActive());
            assertFalse(invocation.ownsReturnHome());
        }

        System.out.println("PERF006_B4B_EXACT_RETURN_HOME=PASS");
        System.out.println("PERF006_B4B_EXACT_RETURN_PAYLOAD=PASS");
        System.out.println("PERF006_B4B_MISMATCH_PROPAGATION=PASS");
    }

    @Test
    void completedCapturedHomeStillSignalsInvalidReturn() throws Exception {
        try (LanguageScope scope = languageScope()) {
            ProtosPrelude prelude = core();
            ProtosActorExecutionDomain domain = new ProtosActorExecutionDomain();
            ProtosActivation creator = activation(prelude, domain);

            String closureCharacters = "() => ^42";
            CanonicalClosure definition = closureDefinition(closureCharacters);
            ProtosClosureExecutionPlan plan =
                    ProtosClosureExecutionPlan.bytecode(
                            definition,
                            scope.language(),
                            source(closureCharacters, "perf006-b4b-invalid-return.protos"));
            ProtosReturnHome completedHome = new ProtosReturnHome();
            completedHome.complete();
            ProtosClosureValue closure =
                    semanticClosureWithHome(
                            definition,
                            plan,
                            creator,
                            completedHome);
            ProtosActivation invocation =
                    ProtosActivation.forClosureInvocation(
                            closure,
                            List.of(),
                            prelude,
                            creator.actorModuleState(),
                            creator.currentModuleKey().orElse(null),
                            domain);

            ProtosSignalException signal =
                    assertThrows(
                            ProtosSignalException.class,
                            () -> plan.executeBody(invocation));

            assertSame(
                    prelude.invalidReturnPrototype(),
                    signal.error().parent().orElseThrow());
        }

        System.out.println("PERF006_B4B_INVALID_RETURN=PASS");
    }

    @Test
    void canonicalReturnAfterFutureSuspensionResumesToOwningPreparedCallWithoutReplay()
            throws Exception {
        try (LanguageScope scope = languageScope()) {
            ProtosPrelude prelude = core();
            ProtosActorExecutionDomain domain = new ProtosActorExecutionDomain();
            ProtosActivation creator = activation(prelude, domain);
            ProtosFutureValue future =
                    new ProtosFutureValue(prelude.futurePrototype(), domain);
            AtomicInteger prefixExecutions = new AtomicInteger();
            ProtosClosureValue probe =
                    ProtosClosureValue.suspensionCapableNativeClosure(
                            (activation, supplied) -> {
                                prefixExecutions.incrementAndGet();
                                return ProtosNullValue.INSTANCE;
                            },
                            (activation, supplied) -> {
                                prefixExecutions.incrementAndGet();
                                return ProtosNullValue.INSTANCE;
                            });
            creator.context().createLocalSlot("f", future);
            creator.context().createLocalSlot("probe", probe);

            String closureCharacters = "() => { probe()\n^f.value() }";
            CanonicalClosure definition = closureDefinition(closureCharacters);
            ProtosClosureExecutionPlan plan =
                    ProtosClosureExecutionPlan.bytecode(
                            definition,
                            scope.language(),
                            source(closureCharacters, "perf006-b4b-resume-closure.protos"));
            creator.context().createLocalSlot(
                    "entry",
                    semanticClosure(definition, plan, creator));

            ProtosSemanticBytecodeRootNode top =
                    lowerRoot(
                            scope.language(),
                            "entry()",
                            "perf006-b4b-resume-top.protos");
            ProtosTask task =
                    domain.createTask(
                            null,
                            current ->
                                    ProtosBytecodeTaskExecution.execute(
                                            current,
                                            top.getCallTarget(),
                                            creator));

            assertTrue(domain.dispatchOne());
            assertEquals(ProtosTask.State.SUSPENDED, task.state());
            assertEquals(1, prefixExecutions.get());

            ProtosIntegerValue resolved =
                    new ProtosIntegerValue(73);
            assertTrue(future.resolve(resolved, creator));
            assertEquals(ProtosTask.State.RUNNABLE, task.state());

            assertTrue(domain.dispatchOne());
            assertEquals(ProtosTask.State.COMPLETED, task.state());
            assertSame(resolved, task.result().orElseThrow());
            assertEquals(
                    1,
                    prefixExecutions.get(),
                    "completed prefix before the Future suspension must not replay");
        }

        System.out.println("PERF006_B4B_RETURN_AFTER_CPRIME_RESUME=PASS");
        System.out.println("PERF006_B4B_COMPLETED_PREFIX_REPLAY=NO");
    }

    @Test
    void defaultExpressionReturnUsesTheInvocationReturnHome() throws Exception {
        try (LanguageScope scope = languageScope()) {
            ProtosPrelude prelude = core();
            ProtosActorExecutionDomain domain = new ProtosActorExecutionDomain();
            ProtosActivation module = activation(prelude, domain);

            String closureCharacters = "(x = ^41) => 99";
            CanonicalClosure definition = closureDefinition(closureCharacters);
            ProtosClosureExecutionPlan plan =
                    ProtosClosureExecutionPlan.bytecode(
                            definition,
                            scope.language(),
                            source(closureCharacters, "perf006-b4b-default-return.protos"));
            module.context().createLocalSlot(
                    "entry",
                    semanticClosure(definition, plan, module));

            Object result =
                    lowerRoot(
                                    scope.language(),
                                    "entry()",
                                    "perf006-b4b-default-return-top.protos")
                            .getCallTarget()
                            .call(module);

            ProtosIntegerValue integer =
                    assertInstanceOf(ProtosIntegerValue.class, result);
            assertEquals(String.valueOf("41"), com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerDecimalText(integer));
        }

        System.out.println("PERF006_B4B_DEFAULT_RETURN_HOME=PASS");
    }

    private static LanguageScope languageScope() {
        Context context = Context.newBuilder(ProtosLanguage.ID).build();
        context.initialize(ProtosLanguage.ID);
        context.enter();
        return new LanguageScope(context, LANGUAGE_REF.get(null));
    }

    private static ProtosPrelude core() throws Exception {
        return new ProtosCoreBootstrap()
                .bootstrap(Path.of("protos", "lib", "core"));
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

    private static ProtosClosureValue semanticClosure(
            CanonicalClosure definition,
            ProtosClosureExecutionPlan plan,
            ProtosActivation creator) {
        return new ProtosClosureValue(
                definition,
                creator.lexicalContextsForClosureCapture(),
                creator.receiver(),
                creator.methodHome().orElse(null),
                creator.returnHome().orElse(null),
                creator.prelude().orElseThrow(),
                plan);
    }

    private static ProtosClosureValue semanticClosureWithHome(
            CanonicalClosure definition,
            ProtosClosureExecutionPlan plan,
            ProtosActivation creator,
            ProtosReturnHome returnHome) {
        return new ProtosClosureValue(
                definition,
                creator.lexicalContextsForClosureCapture(),
                creator.receiver(),
                creator.methodHome().orElse(null),
                returnHome,
                creator.prelude().orElseThrow(),
                plan);
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
                        .canonicalize(
                                new ProtosParser(characters)
                                        .parseProgram());
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
        return new CanonicalToBytecodeLowerer(language, source)
                .lowerRoot(canonicalize(characters));
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
}
