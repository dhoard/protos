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
import com.guillermomolina.protos.runtime.ProtosBooleanValue;
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

final class ProtosPerf006B6A3CObjectComposeCanonicalCoverageTest {
    private static final LanguageReference<ProtosLanguage> LANGUAGE_REF =
            LanguageReference.create(ProtosLanguage.class);

    @Test
    void bareAndExplicitParentObjectsUseExactConstructionParent() throws Exception {
        try (LanguageScope scope = languageScope()) {
            ProtosPrelude prelude = core();
            ProtosActorExecutionDomain domain = new ProtosActorExecutionDomain();
            ProtosActivation module = activation(prelude, domain);

            ProtosObjectValue bare =
                    (ProtosObjectValue)
                            executeBytecode(
                                    scope.language(),
                                    module,
                                    "{ local: true }",
                                    "b6a3c-bare-object.protos");
            assertSame(ProtosObjectValue.rootObject(), bare.parent().orElseThrow());
            assertSame(ProtosBooleanValue.TRUE, bare.readLocalSlot("local").orElseThrow());

            ProtosObjectValue parent = new ProtosObjectValue(ProtosObjectValue.rootObject());
            module.context().createLocalSlot("parent", parent);
            ProtosObjectValue child =
                    (ProtosObjectValue)
                            executeBytecode(
                                    scope.language(),
                                    module,
                                    "parent { local: false }",
                                    "b6a3c-explicit-parent.protos");
            assertSame(parent, child.parent().orElseThrow());
            assertSame(ProtosBooleanValue.FALSE, child.readLocalSlot("local").orElseThrow());
        }

        System.out.println("PERF006_B6A3C_OBJECT_PARENT_CONSTRUCTION=PASS");
    }

    @Test
    void compositionReservesDirectNamesAndConstructionClosureSkipsObjectLexically()
            throws Exception {
        try (LanguageScope scope = languageScope()) {
            ProtosPrelude prelude = core();
            ProtosActorExecutionDomain domain = new ProtosActorExecutionDomain();
            ProtosActivation module = activation(prelude, domain);
            ProtosObjectValue marker = new ProtosObjectValue(ProtosObjectValue.rootObject());
            ProtosObjectValue base = new ProtosObjectValue(ProtosObjectValue.rootObject());
            base.createLocalSlot("x", ProtosBooleanValue.TRUE);
            base.createLocalSlot("y", marker);
            module.context().createLocalSlot("base", base);

            ProtosObjectValue object =
                    (ProtosObjectValue)
                            executeBytecode(
                                    scope.language(),
                                    module,
                                    "{ ...base\nseen: y\nx: false\nmethod: () => this }",
                                    "b6a3c-compose-reservation.protos");

            assertSame(marker, object.readLocalSlot("y").orElseThrow());
            assertSame(marker, object.readLocalSlot("seen").orElseThrow());
            assertSame(ProtosBooleanValue.FALSE, object.readLocalSlot("x").orElseThrow());

            ProtosClosureValue method =
                    (ProtosClosureValue) object.readLocalSlot("method").orElseThrow();
            assertSame(object, method.capturedReceiver());
            assertSame(module.context(), method.capturedLexicalContexts().get(0));
            assertTrue(
                    method.capturedLexicalContexts().stream()
                            .noneMatch(candidate -> candidate == object));
        }

        System.out.println("PERF006_B6A3C_CONTEXTUAL_COMPOSE_RESERVED_NAMES=PASS");
        System.out.println("PERF006_B6A3C_OBJECT_CONSTRUCTION_CAPTURE_BOUNDARY=PASS");
    }

    @Test
    void objectLiteralWorksAsClosureDefaultExpression() throws Exception {
        try (LanguageScope scope = languageScope()) {
            ProtosPrelude prelude = core();
            ProtosActorExecutionDomain domain = new ProtosActorExecutionDomain();
            ProtosActivation module = activation(prelude, domain);
            module.context().createLocalSlot(
                    "entry",
                    sourceClosure(
                            scope.language(),
                            module,
                            "(x = { local: true }) => x",
                            "b6a3c-object-default-entry.protos"));

            ProtosObjectValue result =
                    (ProtosObjectValue)
                            executeBytecode(
                                    scope.language(),
                                    module,
                                    "entry()",
                                    "b6a3c-object-default-call.protos");
            assertSame(ProtosBooleanValue.TRUE, result.readLocalSlot("local").orElseThrow());
            assertSame(ProtosObjectValue.rootObject(), result.parent().orElseThrow());
        }

        System.out.println("PERF006_B6A3C_OBJECT_DEFAULT_EXPRESSION=PASS");
    }

    @Test
    void suspendingCompositionSourceResumesWithoutReplayingCompletedPrefix()
            throws Exception {
        try (LanguageScope scope = languageScope()) {
            ProtosPrelude prelude = core();
            ProtosActorExecutionDomain domain = new ProtosActorExecutionDomain();
            ProtosActivation module = activation(prelude, domain);
            ProtosFutureValue future = new ProtosFutureValue(prelude.futurePrototype(), domain);
            ProtosObjectValue marker = new ProtosObjectValue(ProtosObjectValue.rootObject());
            ProtosObjectValue base = new ProtosObjectValue(ProtosObjectValue.rootObject());
            base.createLocalSlot("marker", marker);
            AtomicInteger probeCalls = new AtomicInteger();

            module.context().createLocalSlot("f", future);
            module.context().createLocalSlot("base", base);
            module.context().createLocalSlot(
                    "probe",
                    nativeClosure(
                            (activation, supplied) -> {
                                probeCalls.incrementAndGet();
                                return ProtosNullValue.INSTANCE;
                            }));
            module.context().createLocalSlot(
                    "source",
                    sourceClosure(
                            scope.language(),
                            module,
                            "() => { probe(); f.value(); base }",
                            "b6a3c-suspending-compose-source.protos"));

            ProtosTask task =
                    executeTask(
                            domain,
                            module,
                            lowerRoot(
                                    scope.language(),
                                    "result: { ...source()\nlocal: true }\nresult",
                                    "b6a3c-suspending-object.protos"));

            assertTrue(domain.dispatchOne());
            assertEquals(ProtosTask.State.SUSPENDED, task.state());
            assertEquals(1, probeCalls.get());
            assertTrue(module.context().readLocalSlot("result").isEmpty());

            assertTrue(future.resolve(ProtosNullValue.INSTANCE, module));
            assertTrue(domain.dispatchOne());
            assertEquals(ProtosTask.State.COMPLETED, task.state());
            assertEquals(1, probeCalls.get());
            ProtosObjectValue result = (ProtosObjectValue) task.result().orElseThrow();
            assertSame(marker, result.readLocalSlot("marker").orElseThrow());
            assertSame(ProtosBooleanValue.TRUE, result.readLocalSlot("local").orElseThrow());
        }

        System.out.println("PERF006_B6A3C_COMPOSE_SOURCE_SUSPENSION_RESUME=PASS");
        System.out.println("PERF006_B6A3C_OBJECT_COMPLETED_PREFIX_REPLAY=NO");
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
