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
import java.util.concurrent.atomic.AtomicInteger;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;

final class ProtosPerf006B6A2MutatingCanonicalCoverageTest {
    private static final LanguageReference<ProtosLanguage> LANGUAGE_REF =
            LanguageReference.create(ProtosLanguage.class);

    @Test
    void bareAndExplicitSlotMutationReturnExactRhsThroughBytecode() throws Exception {
        try (LanguageScope scope = languageScope()) {
            ProtosPrelude prelude = core();
            ProtosActorExecutionDomain domain = new ProtosActorExecutionDomain();
            ProtosActivation module = activation(prelude, domain);

            ProtosObjectValue token =
                    new ProtosObjectValue(ProtosObjectValue.rootObject());
            ProtosObjectValue object =
                    new ProtosObjectValue(ProtosObjectValue.rootObject());
            ProtosObjectValue old =
                    new ProtosObjectValue(ProtosObjectValue.rootObject());

            object.createLocalSlot("value", old);
            module.context().createLocalSlot("token", token);
            module.context().createLocalSlot("obj", object);

            Object result =
                    executeBytecode(
                            scope.language(),
                            module,
                            "x: token\n"
                                    + "x = token\n"
                                    + "obj.created: token\n"
                                    + "obj.value = token",
                            "b6a2-slot-mutation-bytecode.protos");

            assertSame(token, result);
            assertSame(
                    token,
                    module.context().readLocalSlot("x").orElseThrow());
            assertSame(
                    token,
                    object.readLocalSlot("created").orElseThrow());
            assertSame(
                    token,
                    object.readLocalSlot("value").orElseThrow());
        }

        System.out.println("PERF006_B6A2_SLOT_MUTATION_BYTECODE=PASS");
        System.out.println("PERF006_B6A2_SLOT_MUTATION_EXACT_RHS=PASS");
    }

    @Test
    void mutationValidationPrecedesRhsEvaluation() throws Exception {
        try (LanguageScope scope = languageScope()) {
            ProtosPrelude prelude = core();
            ProtosActorExecutionDomain domain = new ProtosActorExecutionDomain();
            ProtosActivation module = activation(prelude, domain);
            AtomicInteger rhsCalls = new AtomicInteger();
            module.context().createLocalSlot(
                    "rhs",
                    nativeClosure(
                            (activation, supplied) -> {
                                rhsCalls.incrementAndGet();
                                return ProtosNullValue.INSTANCE;
                            }));
            module.context().createLocalSlot(
                    "badTarget",
                    nativeClosure((activation, supplied) -> ProtosNullValue.INSTANCE));

            assertThrows(
                    ProtosSignalException.class,
                    () -> executeBytecode(
                            scope.language(),
                            module,
                            "missing = rhs()",
                            "b6a2-missing-before-rhs.protos"));
            assertEquals(0, rhsCalls.get());

            assertThrows(
                    ProtosSignalException.class,
                    () -> executeBytecode(
                            scope.language(),
                            module,
                            "badTarget().value = rhs()",
                            "b6a2-target-before-rhs.protos"));
            assertEquals(0, rhsCalls.get());
        }

        System.out.println("PERF006_B6A2_MUTATION_PREVALIDATION_ORDER=PASS");
    }

    @Test
    void explicitAssignmentSuspensionResumesWithoutTargetOrRhsPrefixReplay() throws Exception {
        try (LanguageScope scope = languageScope()) {
            ProtosPrelude prelude = core();
            ProtosActorExecutionDomain domain = new ProtosActorExecutionDomain();
            ProtosActivation module = activation(prelude, domain);
            ProtosFutureValue future = new ProtosFutureValue(prelude.futurePrototype(), domain);
            ProtosObjectValue old = new ProtosObjectValue(ProtosObjectValue.rootObject());
            ProtosObjectValue token = new ProtosObjectValue(ProtosObjectValue.rootObject());
            ProtosObjectValue object = new ProtosObjectValue(ProtosObjectValue.rootObject());
            object.createLocalSlot("value", old);
            AtomicInteger targetCalls = new AtomicInteger();
            AtomicInteger rhsPrefix = new AtomicInteger();

            module.context().createLocalSlot("f", future);
            module.context().createLocalSlot(
                    "target",
                    nativeClosure(
                            (activation, supplied) -> {
                                targetCalls.incrementAndGet();
                                return object;
                            }));
            module.context().createLocalSlot(
                    "probe",
                    nativeClosure(
                            (activation, supplied) -> {
                                rhsPrefix.incrementAndGet();
                                return ProtosNullValue.INSTANCE;
                            }));
            module.context().createLocalSlot(
                    "rhs",
                    sourceClosure(
                            scope.language(),
                            module,
                            "() => { probe()\nf.value() }",
                            "b6a2-rhs-closure.protos"));

            ProtosTask task = executeTask(
                    domain,
                    module,
                    lowerRoot(
                            scope.language(),
                            "target().value = rhs()",
                            "b6a2-suspending-member-assign.protos"));

            assertTrue(domain.dispatchOne());
            assertEquals(ProtosTask.State.SUSPENDED, task.state());
            assertEquals(1, targetCalls.get());
            assertEquals(1, rhsPrefix.get());
            assertSame(old, object.readLocalSlot("value").orElseThrow());

            assertTrue(future.resolve(token, module));
            assertTrue(domain.dispatchOne());
            assertEquals(ProtosTask.State.COMPLETED, task.state());
            assertSame(token, task.result().orElseThrow());
            assertSame(token, object.readLocalSlot("value").orElseThrow());
            assertEquals(1, targetCalls.get());
            assertEquals(1, rhsPrefix.get());
        }

        System.out.println("PERF006_B6A2_MEMBER_ASSIGN_SUSPENSION_RESUME=PASS");
        System.out.println("PERF006_B6A2_MEMBER_ASSIGN_COMPLETED_PREFIX_REPLAY=NO");
    }

    @Test
    void indexedAssignmentUsesSuspendingAtPutAndReturnsExactRhsWithoutReplay() throws Exception {
        try (LanguageScope scope = languageScope()) {
            ProtosPrelude prelude = core();
            ProtosActorExecutionDomain domain = new ProtosActorExecutionDomain();
            ProtosActivation module = activation(prelude, domain);
            ProtosFutureValue future = new ProtosFutureValue(prelude.futurePrototype(), domain);
            ProtosObjectValue receiver = new ProtosObjectValue(ProtosObjectValue.rootObject());
            ProtosObjectValue indexToken = new ProtosObjectValue(ProtosObjectValue.rootObject());
            ProtosObjectValue rhsToken = new ProtosObjectValue(ProtosObjectValue.rootObject());
            ProtosObjectValue atPutReturn = new ProtosObjectValue(ProtosObjectValue.rootObject());
            AtomicInteger indexCalls = new AtomicInteger();
            AtomicInteger valueCalls = new AtomicInteger();
            AtomicInteger atPutPrefix = new AtomicInteger();

            module.context().createLocalSlot("f", future);
            module.context().createLocalSlot("other", atPutReturn);
            module.context().createLocalSlot(
                    "probe",
                    nativeClosure(
                            (activation, supplied) -> {
                                atPutPrefix.incrementAndGet();
                                return ProtosNullValue.INSTANCE;
                            }));
            receiver.createLocalSlot(
                    "atPut",
                    sourceClosure(
                            scope.language(),
                            module,
                            "(index, value) => { probe()\nf.value()\nother }",
                            "b6a2-atput-closure.protos"));
            module.context().createLocalSlot("receiver", receiver);
            module.context().createLocalSlot(
                    "index",
                    nativeClosure(
                            (activation, supplied) -> {
                                indexCalls.incrementAndGet();
                                return indexToken;
                            }));
            module.context().createLocalSlot(
                    "value",
                    nativeClosure(
                            (activation, supplied) -> {
                                valueCalls.incrementAndGet();
                                return rhsToken;
                            }));

            ProtosTask task = executeTask(
                    domain,
                    module,
                    lowerRoot(
                            scope.language(),
                            "receiver[index()] = value()",
                            "b6a2-suspending-indexed-assign.protos"));

            assertTrue(domain.dispatchOne());
            assertEquals(ProtosTask.State.SUSPENDED, task.state());
            assertEquals(1, indexCalls.get());
            assertEquals(1, valueCalls.get());
            assertEquals(1, atPutPrefix.get());

            assertTrue(future.resolve(ProtosNullValue.INSTANCE, module));
            assertTrue(domain.dispatchOne());
            assertEquals(ProtosTask.State.COMPLETED, task.state());
            assertSame(rhsToken, task.result().orElseThrow());
            assertEquals(1, indexCalls.get());
            assertEquals(1, valueCalls.get());
            assertEquals(1, atPutPrefix.get());
        }

        System.out.println("PERF006_B6A2_INDEXED_ATPUT_SUSPENSION_RESUME=PASS");
        System.out.println("PERF006_B6A2_INDEXED_EXACT_RHS=PASS");
        System.out.println("PERF006_B6A2_INDEXED_COMPLETED_PREFIX_REPLAY=NO");
    }

    @Test
    void mutatingDefaultExpressionRunsOnBytecodePlan() throws Exception {
        try (LanguageScope scope = languageScope()) {
            ProtosPrelude prelude = core();
            ProtosActorExecutionDomain domain = new ProtosActorExecutionDomain();
            ProtosActivation module = activation(prelude, domain);
            ProtosObjectValue old = new ProtosObjectValue(ProtosObjectValue.rootObject());
            ProtosObjectValue token = new ProtosObjectValue(ProtosObjectValue.rootObject());
            ProtosObjectValue object = new ProtosObjectValue(ProtosObjectValue.rootObject());
            object.createLocalSlot("value", old);
            module.context().createLocalSlot("obj", object);
            module.context().createLocalSlot("token", token);
            module.context().createLocalSlot(
                    "entry",
                    sourceClosure(
                            scope.language(),
                            module,
                            "(x = obj.value = token) => x",
                            "b6a2-mutating-default.protos"));

            Object result = executeBytecode(
                    scope.language(),
                    module,
                    "entry()",
                    "b6a2-mutating-default-top.protos");
            assertSame(token, result);
            assertSame(token, object.readLocalSlot("value").orElseThrow());
        }

        System.out.println("PERF006_B6A2_MUTATING_DEFAULT_EXPRESSION=PASS");
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
        ProtosClosureExecutionPlan plan = ProtosClosureExecutionPlan.bytecode(
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
                current -> ProtosBytecodeTaskExecution.execute(
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
