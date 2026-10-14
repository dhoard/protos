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
import static org.junit.jupiter.api.Assertions.assertThrows;
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

final class ProtosPerf006B6A3ASuperSendCanonicalCoverageTest {
    private static final LanguageReference<ProtosLanguage> LANGUAGE_REF =
            LanguageReference.create(ProtosLanguage.class);

    @Test
    void superSendStartsAtPhysicalMethodHomeParentAndPreservesDynamicReceiver() throws Exception {
        try (LanguageScope scope = languageScope()) {
            ProtosPrelude prelude = core();
            ProtosActorExecutionDomain domain = new ProtosActorExecutionDomain();
            ProtosActivation module = activation(prelude, domain);
            ProtosObjectValue base = new ProtosObjectValue(ProtosObjectValue.rootObject());
            ProtosObjectValue child = new ProtosObjectValue(base);

            base.createLocalSlot(
                    "pick",
                    sourceClosure(scope.language(), module, "() => this", "b6a3a-base-pick.protos"));
            child.createLocalSlot(
                    "pick",
                    sourceClosure(scope.language(), module, "() => super.pick()", "b6a3a-child-pick.protos"));
            module.context().createLocalSlot("obj", child);

            Object result = executeBytecode(
                    scope.language(), module, "obj.pick()", "b6a3a-super-receiver.protos");
            assertSame(child, result);
        }

        System.out.println("PERF006_B6A3A_SUPER_PHYSICAL_HOME=PASS");
        System.out.println("PERF006_B6A3A_SUPER_DYNAMIC_RECEIVER=PASS");
    }

    @Test
    void superSendSupportsSpreadArgumentsAndDefaultExpressions() throws Exception {
        try (LanguageScope scope = languageScope()) {
            ProtosPrelude prelude = core();
            ProtosActorExecutionDomain domain = new ProtosActorExecutionDomain();
            ProtosActivation module = activation(prelude, domain);
            ProtosObjectValue base = new ProtosObjectValue(ProtosObjectValue.rootObject());
            ProtosObjectValue child = new ProtosObjectValue(base);
            ProtosObjectValue first = new ProtosObjectValue(ProtosObjectValue.rootObject());
            ProtosObjectValue second = new ProtosObjectValue(ProtosObjectValue.rootObject());
            ProtosObjectValue token = new ProtosObjectValue(ProtosObjectValue.rootObject());

            module.context().createLocalSlot("items", prelude.newFrozenArray(List.of(first, second)));
            module.context().createLocalSlot("token", token);
            base.createLocalSlot(
                    "pair",
                    sourceClosure(scope.language(), module, "(a, b) => b", "b6a3a-base-pair.protos"));
            base.createLocalSlot(
                    "defaultValue",
                    sourceClosure(scope.language(), module, "() => token", "b6a3a-base-default.protos"));
            child.createLocalSlot(
                    "spread",
                    sourceClosure(scope.language(), module, "() => super.pair(...items)", "b6a3a-child-spread.protos"));
            child.createLocalSlot(
                    "withDefault",
                    sourceClosure(
                            scope.language(),
                            module,
                            "(x = super.defaultValue()) => x",
                            "b6a3a-child-default.protos"));
            module.context().createLocalSlot("obj", child);

            assertSame(
                    second,
                    executeBytecode(scope.language(), module, "obj.spread()", "b6a3a-spread-top.protos"));
            assertSame(
                    token,
                    executeBytecode(scope.language(), module, "obj.withDefault()", "b6a3a-default-top.protos"));
        }

        System.out.println("PERF006_B6A3A_SUPER_SPREAD=PASS");
        System.out.println("PERF006_B6A3A_SUPER_DEFAULT_EXPRESSION=PASS");
    }

    @Test
    void suspendingSuperTargetResumesWithoutCompletedPrefixReplay() throws Exception {
        try (LanguageScope scope = languageScope()) {
            ProtosPrelude prelude = core();
            ProtosActorExecutionDomain domain = new ProtosActorExecutionDomain();
            ProtosActivation module = activation(prelude, domain);
            ProtosObjectValue base = new ProtosObjectValue(ProtosObjectValue.rootObject());
            ProtosObjectValue child = new ProtosObjectValue(base);
            ProtosObjectValue token = new ProtosObjectValue(ProtosObjectValue.rootObject());
            ProtosFutureValue future = new ProtosFutureValue(prelude.futurePrototype(), domain);
            AtomicInteger prefixCalls = new AtomicInteger();

            module.context().createLocalSlot("f", future);
            module.context().createLocalSlot(
                    "probe",
                    nativeClosure(
                            (activation, supplied) -> {
                                prefixCalls.incrementAndGet();
                                return ProtosNullValue.INSTANCE;
                            }));
            base.createLocalSlot(
                    "wait",
                    sourceClosure(
                            scope.language(),
                            module,
                            "() => { probe()\nf.value() }",
                            "b6a3a-base-wait.protos"));
            child.createLocalSlot(
                    "wait",
                    sourceClosure(scope.language(), module, "() => super.wait()", "b6a3a-child-wait.protos"));
            module.context().createLocalSlot("obj", child);

            ProtosTask task = executeTask(
                    domain,
                    module,
                    lowerRoot(scope.language(), "obj.wait()", "b6a3a-suspending-super.protos"));

            assertTrue(domain.dispatchOne());
            assertEquals(ProtosTask.State.SUSPENDED, task.state());
            assertEquals(1, prefixCalls.get());

            assertTrue(future.resolve(token, module));
            assertTrue(domain.dispatchOne());
            assertEquals(ProtosTask.State.COMPLETED, task.state());
            assertSame(token, task.result().orElseThrow());
            assertEquals(1, prefixCalls.get());
        }

        System.out.println("PERF006_B6A3A_SUPER_SUSPENSION_RESUME=PASS");
        System.out.println("PERF006_B6A3A_SUPER_COMPLETED_PREFIX_REPLAY=NO");
    }

    @Test
    void superOutsideMethodStillSignalsInvalidSuper() throws Exception {
        try (LanguageScope scope = languageScope()) {
            ProtosPrelude prelude = core();
            ProtosActorExecutionDomain domain = new ProtosActorExecutionDomain();
            ProtosActivation module = activation(prelude, domain);

            assertThrows(
                    ProtosSignalException.class,
                    () -> executeBytecode(
                            scope.language(),
                            module,
                            "super.missing()",
                            "b6a3a-invalid-super.protos"));
        }

        System.out.println("PERF006_B6A3A_INVALID_SUPER_FAILURE=PASS");
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
