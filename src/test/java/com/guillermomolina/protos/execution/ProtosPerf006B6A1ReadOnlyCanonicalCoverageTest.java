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
import java.util.concurrent.atomic.AtomicInteger;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;

final class ProtosPerf006B6A1ReadOnlyCanonicalCoverageTest {
    private static final LanguageReference<ProtosLanguage> LANGUAGE_REF =
            LanguageReference.create(ProtosLanguage.class);

    @Test
    void memberIdentityAndIntrinsicsExecuteThroughBytecode() throws Exception {
        try (LanguageScope scope = languageScope()) {
            ProtosPrelude prelude = core();
            ProtosActorExecutionDomain domain = new ProtosActorExecutionDomain();
            ProtosActivation module = activation(prelude, domain);
            ProtosObjectValue token =
                    new ProtosObjectValue(ProtosObjectValue.rootObject());
            ProtosObjectValue other =
                    new ProtosObjectValue(ProtosObjectValue.rootObject());
            ProtosObjectValue object =
                    new ProtosObjectValue(ProtosObjectValue.rootObject());

            object.createLocalSlot("value", token);
            module.context().createLocalSlot("obj", object);
            module.context().createLocalSlot("a", token);
            module.context().createLocalSlot("b", other);

            assertSame(
                    token,
                    executeBytecode(
                            scope.language(),
                            module,
                            "obj.value",
                            "b6a1-member-bytecode.protos"));
            assertSame(
                    ProtosBooleanValue.TRUE,
                    executeBytecode(
                            scope.language(),
                            module,
                            "a === a",
                            "b6a1-id-bytecode.protos"));
            assertSame(
                    ProtosBooleanValue.TRUE,
                    executeBytecode(
                            scope.language(),
                            module,
                            "a !== b",
                            "b6a1-not-id-bytecode.protos"));
            assertSame(
                    module.receiver(),
                    executeBytecode(
                            scope.language(),
                            module,
                            "this",
                            "b6a1-this-bytecode.protos"));
            assertSame(
                    module.context(),
                    executeBytecode(
                            scope.language(),
                            module,
                            "context",
                            "b6a1-context-bytecode.protos"));
        }

        System.out.println("PERF006_B6A1_MEMBER_READ_BYTECODE=PASS");
        System.out.println("PERF006_B6A1_IDENTITY_BYTECODE=PASS");
        System.out.println("PERF006_B6A1_INTRINSIC_THIS_CONTEXT_BYTECODE=PASS");
    }

    @Test
    void composedMemberReceiverExecutesExactlyOnce() throws Exception {
        try (LanguageScope scope = languageScope()) {
            ProtosPrelude prelude = core();
            ProtosActorExecutionDomain domain = new ProtosActorExecutionDomain();
            ProtosActivation module = activation(prelude, domain);
            ProtosObjectValue token = new ProtosObjectValue(ProtosObjectValue.rootObject());
            ProtosObjectValue object = new ProtosObjectValue(ProtosObjectValue.rootObject());
            object.createLocalSlot("value", token);
            AtomicInteger calls = new AtomicInteger();

            module.context().createLocalSlot(
                    "make",
                    nativeClosure(
                            (activation, supplied) -> {
                                calls.incrementAndGet();
                                return object;
                            }));

            Object result =
                    executeBytecode(
                            scope.language(),
                            module,
                            "make().value",
                            "b6a1-composed-member.protos");
            assertSame(token, result);
            assertEquals(1, calls.get());
        }

        System.out.println("PERF006_B6A1_COMPOSED_MEMBER_RECEIVER=PASS");
        System.out.println("PERF006_B6A1_COMPOSED_MEMBER_REPLAY=NO");
    }

    @Test
    void identityOperandSuspensionResumesWithoutReplay() throws Exception {
        try (LanguageScope scope = languageScope()) {
            ProtosPrelude prelude = core();
            ProtosActorExecutionDomain domain = new ProtosActorExecutionDomain();
            ProtosActivation module = activation(prelude, domain);
            ProtosFutureValue future = new ProtosFutureValue(prelude.futurePrototype(), domain);
            ProtosObjectValue token = new ProtosObjectValue(ProtosObjectValue.rootObject());
            AtomicInteger leftPrefix = new AtomicInteger();
            AtomicInteger rightCalls = new AtomicInteger();

            module.context().createLocalSlot("f", future);
            module.context().createLocalSlot(
                    "probe",
                    nativeClosure(
                            (activation, supplied) -> {
                                leftPrefix.incrementAndGet();
                                return ProtosNullValue.INSTANCE;
                            }));
            module.context().createLocalSlot(
                    "left",
                    sourceClosure(
                            scope.language(),
                            module,
                            "() => { probe()\nf.value() }",
                            "b6a1-identity-left.protos"));
            module.context().createLocalSlot(
                    "right",
                    nativeClosure(
                            (activation, supplied) -> {
                                rightCalls.incrementAndGet();
                                return token;
                            }));

            ProtosTask task =
                    executeTask(
                            domain,
                            module,
                            lowerRoot(
                                    scope.language(),
                                    "left() === right()",
                                    "b6a1-identity-suspend-top.protos"));

            assertTrue(domain.dispatchOne());
            assertEquals(ProtosTask.State.SUSPENDED, task.state());
            assertEquals(1, leftPrefix.get());
            assertEquals(0, rightCalls.get());

            assertTrue(future.resolve(token, module));
            assertTrue(domain.dispatchOne());
            assertEquals(ProtosTask.State.COMPLETED, task.state());
            assertSame(ProtosBooleanValue.TRUE, task.result().orElseThrow());
            assertEquals(1, leftPrefix.get());
            assertEquals(1, rightCalls.get());
        }

        System.out.println("PERF006_B6A1_IDENTITY_SUSPENSION_RESUME=PASS");
        System.out.println("PERF006_B6A1_IDENTITY_COMPLETED_PREFIX_REPLAY=NO");
    }

    @Test
    void readOnlyCompositeDefaultExpressionRunsOnBytecodePlan() throws Exception {
        try (LanguageScope scope = languageScope()) {
            ProtosPrelude prelude = core();
            ProtosActorExecutionDomain domain = new ProtosActorExecutionDomain();
            ProtosActivation module = activation(prelude, domain);
            ProtosObjectValue seed = new ProtosObjectValue(ProtosObjectValue.rootObject());
            module.context().createLocalSlot("seed", seed);
            module.context().createLocalSlot(
                    "entry",
                    sourceClosure(
                            scope.language(),
                            module,
                            "(x = seed === seed) => x",
                            "b6a1-default-identity.protos"));

            Object result =
                    executeBytecode(
                            scope.language(),
                            module,
                            "entry()",
                            "b6a1-default-identity-top.protos");
            assertSame(ProtosBooleanValue.TRUE, result);
        }

        System.out.println("PERF006_B6A1_READ_ONLY_DEFAULT_EXPRESSION=PASS");
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
        return assertInstanceOf(CanonicalClosure.class, sequence.expressions().get(0));
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
