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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.guillermomolina.protos.parser.ProtosParser;
import com.guillermomolina.protos.runtime.ProtosActivation;
import com.guillermomolina.protos.runtime.ProtosActorExecutionDomain;
import com.guillermomolina.protos.runtime.ProtosActorModuleState;
import com.guillermomolina.protos.runtime.ProtosClosureValue;
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
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;

final class ProtosPerf006B6A3BClosureLiteralCanonicalCoverageTest {
    private static final LanguageReference<ProtosLanguage> LANGUAGE_REF =
            LanguageReference.create(ProtosLanguage.class);

    @Test
    void closureLiteralCapturesExactActivationStateAndUsesBytecodePlan() throws Exception {
        try (LanguageScope scope = languageScope()) {
            ProtosPrelude prelude = core();
            ProtosActorExecutionDomain domain = new ProtosActorExecutionDomain();
            ProtosActivation module = activation(prelude, domain);
            ProtosObjectValue receiver = new ProtosObjectValue(ProtosObjectValue.rootObject());
            ProtosObjectValue methodHome = new ProtosObjectValue(ProtosObjectValue.rootObject());

            CanonicalClosure templateDefinition = closureDefinition("() => null");
            ProtosClosureValue template =
                    new ProtosClosureValue(
                            templateDefinition,
                            module.lexicalContextsForClosureCapture(),
                            module.receiver(),
                            null,
                            null,
                            prelude);
            ProtosActivation methodActivation =
                    ProtosActivation.forImmediateMethodInvocation(
                            template,
                            List.of(),
                            receiver,
                            methodHome,
                            prelude,
                            module.actorModuleState(),
                            module.currentModuleKey().orElse(null),
                            domain);

            Object value =
                    executeBytecode(
                            scope.language(),
                            methodActivation,
                            "() => this",
                            "b6a3b-capture-state.protos");
            ProtosClosureValue closure = (ProtosClosureValue) value;

            assertSame(receiver, closure.capturedReceiver());
            assertSame(methodHome, closure.methodHome().orElseThrow());
            assertSame(methodActivation.returnHome().orElseThrow(), closure.returnHome().orElseThrow());
            assertSame(prelude, closure.prelude().orElseThrow());
            assertEquals(
                    methodActivation.lexicalContextsForClosureCapture(),
                    closure.capturedLexicalContexts());
            assertTrue(closure.executionPlan().isPresent());
        }

        System.out.println("PERF006_B6A3B_CLOSURE_CAPTURE_STATE=PASS");
        System.out.println("PERF006_B6A3B_CLOSURE_BYTECODE_PLAN=YES");
    }

    @Test
    void lexicalCaptureRemainsByReferenceAndNestedClosureInvokesOnBytecode() throws Exception {
        try (LanguageScope scope = languageScope()) {
            ProtosPrelude prelude = core();
            ProtosActorExecutionDomain domain = new ProtosActorExecutionDomain();
            ProtosActivation module = activation(prelude, domain);
            ProtosObjectValue first = new ProtosObjectValue(ProtosObjectValue.rootObject());
            ProtosObjectValue second = new ProtosObjectValue(ProtosObjectValue.rootObject());
            module.context().createLocalSlot("token", first);

            ProtosClosureValue closure =
                    (ProtosClosureValue)
                            executeBytecode(
                                    scope.language(),
                                    module,
                                    "() => token",
                                    "b6a3b-by-reference-literal.protos");
            module.context().assignLocalSlot("token", second);
            module.context().createLocalSlot("entry", closure);

            Object result =
                    executeBytecode(
                            scope.language(),
                            module,
                            "entry()",
                            "b6a3b-by-reference-invoke.protos");
            assertSame(second, result);
        }

        System.out.println("PERF006_B6A3B_LEXICAL_CAPTURE_BY_REFERENCE=YES");
        System.out.println("PERF006_B6A3B_NESTED_CLOSURE_BYTECODE_INVOKE=PASS");
    }

    @Test
    void closureLiteralWorksAsDefaultExpression() throws Exception {
        try (LanguageScope scope = languageScope()) {
            ProtosPrelude prelude = core();
            ProtosActorExecutionDomain domain = new ProtosActorExecutionDomain();
            ProtosActivation module = activation(prelude, domain);
            ProtosObjectValue token = new ProtosObjectValue(ProtosObjectValue.rootObject());
            module.context().createLocalSlot("token", token);
            module.context().createLocalSlot(
                    "entry",
                    sourceClosure(
                            scope.language(),
                            module,
                            "(x = () => token) => x",
                            "b6a3b-closure-default-entry.protos"));

            ProtosClosureValue nested =
                    (ProtosClosureValue)
                            executeBytecode(
                                    scope.language(),
                                    module,
                                    "entry()",
                                    "b6a3b-closure-default-call.protos");
            assertTrue(nested.executionPlan().isPresent());
            module.context().createLocalSlot("nested", nested);
            Object result =
                    executeBytecode(
                            scope.language(),
                            module,
                            "nested()",
                            "b6a3b-closure-default-invoke.protos");
            assertSame(token, result);
        }

        System.out.println("PERF006_B6A3B_CLOSURE_DEFAULT_EXPRESSION=PASS");
    }

    @Test
    void completedClosureLiteralPrefixIsNotReplayedAcrossSuspension() throws Exception {
        try (LanguageScope scope = languageScope()) {
            ProtosPrelude prelude = core();
            ProtosActorExecutionDomain domain = new ProtosActorExecutionDomain();
            ProtosActivation module = activation(prelude, domain);
            ProtosFutureValue future = new ProtosFutureValue(prelude.futurePrototype(), domain);
            ProtosObjectValue token = new ProtosObjectValue(ProtosObjectValue.rootObject());
            AtomicInteger probeCalls = new AtomicInteger();

            module.context().createLocalSlot("f", future);
            module.context().createLocalSlot("token", token);
            module.context().createLocalSlot(
                    "probe",
                    nativeClosure(
                            (activation, supplied) -> {
                                probeCalls.incrementAndGet();
                                return ProtosNullValue.INSTANCE;
                            }));

            ProtosTask task =
                    executeTask(
                            domain,
                            module,
                            lowerRoot(
                                    scope.language(),
                                    "saved: () => token\nprobe(saved)\nf.value()\nsaved",
                                    "b6a3b-suspending-prefix.protos"));

            assertTrue(domain.dispatchOne());
            assertEquals(ProtosTask.State.SUSPENDED, task.state());
            assertEquals(1, probeCalls.get());
            ProtosClosureValue saved =
                    (ProtosClosureValue) module.context().readLocalSlot("saved").orElseThrow();

            assertTrue(future.resolve(ProtosNullValue.INSTANCE, module));
            assertTrue(domain.dispatchOne());
            assertEquals(ProtosTask.State.COMPLETED, task.state());
            assertSame(saved, task.result().orElseThrow());
            assertEquals(1, probeCalls.get());
        }

        System.out.println("PERF006_B6A3B_CLOSURE_SUSPENSION_RESUME=PASS");
        System.out.println("PERF006_B6A3B_CLOSURE_COMPLETED_PREFIX_REPLAY=NO");
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

    private static ProtosTask executeTask(
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

    private static Object executeBytecode(
            ProtosLanguage language,
            ProtosActivation activation,
            String characters,
            String sourceName)
            throws Exception {
        return lowerRoot(language, characters, sourceName).getCallTarget().call(activation);
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
                new Canonicalizer().canonicalize(new ProtosParser(characters).parseProgram());
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
