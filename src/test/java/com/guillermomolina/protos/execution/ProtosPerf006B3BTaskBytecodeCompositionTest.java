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
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.guillermomolina.protos.parser.ProtosParser;
import com.guillermomolina.protos.runtime.ProtosActivation;
import com.guillermomolina.protos.runtime.ProtosActorExecutionDomain;
import com.guillermomolina.protos.runtime.ProtosClosureValue;
import com.guillermomolina.protos.runtime.ProtosNullValue;
import com.guillermomolina.protos.runtime.ProtosObjectValue;
import com.guillermomolina.protos.runtime.ProtosPrelude;
import com.guillermomolina.protos.runtime.ProtosTask;
import com.guillermomolina.protos.semantic.Canonicalizer;
import com.guillermomolina.protos.semantic.ast.CanonicalClosure;
import com.guillermomolina.protos.semantic.ast.CanonicalSequence;
import com.oracle.truffle.api.TruffleLanguage.LanguageReference;
import com.oracle.truffle.api.source.Source;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;

final class ProtosPerf006B3BTaskBytecodeCompositionTest {
    private static final LanguageReference<ProtosLanguage> LANGUAGE_REF =
            LanguageReference.create(ProtosLanguage.class);

    @Test
    void sourceBackedPreparedCallKeepsExactCallerTaskIdentity()
            throws Exception {
        try (Context context = Context.newBuilder(ProtosLanguage.ID).build()) {
            context.initialize(ProtosLanguage.ID);
            context.enter();
            try {
                ProtosLanguage language = LANGUAGE_REF.get(null);
                ProtosActivation module = moduleActivation();
                ProtosActorExecutionDomain domain = module.executionDomain();
                ProtosClosureValue child =
                        bytecodeClosure(
                                language,
                                module,
                                "() => { null }",
                                "perf006-b3b-task-identity-child.protos");
                AtomicReference<ProtosActivation> preparedActivation =
                        new AtomicReference<>();

                ProtosTask task =
                        domain.createTask(
                                null,
                                current -> {
                                    module.attachTask(current);
                                    ProtosBytecodeRootNode.PreparedClosureCall prepared =
                                            ProtosBytecodeRootNode.PrepareClosureCall.perform(
                                                    child,
                                                    module);
                                    preparedActivation.set(prepared.activation());
                                    assertFalse(prepared.isNative());
                                    current.complete(ProtosNullValue.INSTANCE);
                                });

                assertTrue(domain.dispatchOne());
                assertEquals(ProtosTask.State.COMPLETED, task.state());
                assertSame(
                        task,
                        preparedActivation.get().task().orElseThrow());
            } finally {
                context.leave();
            }
        }
        System.out.println("PERF006_B3B_SOURCE_CALL_TASK_IDENTITY=PASS");
    }

    @Test
    void sourceBackedNestedBytecodeCallExecutesInsideTask()
            throws Exception {
        try (Context context = Context.newBuilder(ProtosLanguage.ID).build()) {
            context.initialize(ProtosLanguage.ID);
            context.enter();
            try {
                ProtosLanguage language = LANGUAGE_REF.get(null);
                ProtosActivation module = moduleActivation();
                ProtosActorExecutionDomain domain = module.executionDomain();

                ProtosClosureValue child =
                        bytecodeClosure(
                                language,
                                module,
                                "() => { null }",
                                "perf006-b3b-child.protos");
                module.context().createLocalSlot("child", child);

                String topCharacters = "child()";
                Source topSource =
                        Source.newBuilder(
                                        ProtosLanguage.ID,
                                        topCharacters,
                                        "perf006-b3b-top.protos")
                                .build();
                ProtosSemanticBytecodeRootNode topRoot =
                        new CanonicalToBytecodeLowerer(language, topSource, ProtosExecutionMode.NORMAL)
                                .lowerRoot(canonicalize(topCharacters));
                AtomicInteger segments = new AtomicInteger();

                ProtosTask task =
                        domain.createTask(
                                null,
                                current -> {
                                    segments.incrementAndGet();
                                    module.attachTask(current);
                                    Object value =
                                            topRoot.getCallTarget().call(module);
                                    assertSame(ProtosNullValue.INSTANCE, value);
                                    current.complete(value);
                                });

                assertTrue(domain.dispatchOne());
                assertEquals(ProtosTask.State.COMPLETED, task.state());
                assertSame(
                        ProtosNullValue.INSTANCE,
                        task.result().orElseThrow());
                assertEquals(1, segments.get());
            } finally {
                context.leave();
            }
        }
        System.out.println("PERF006_B3B_TASK_BACKED_SOURCE_COMPOSITION=PASS");
        System.out.println("PERF006_B3B_TASK_BACKED_SOURCE_REPLAY=NO");
    }

    @Test
    void taskBackedOrdinaryNativeCallUsesDirectFastPathAfterB6A4()
            throws Exception {
        try (Context context = Context.newBuilder(ProtosLanguage.ID).build()) {
            context.initialize(ProtosLanguage.ID);
            context.enter();
            try {
                ProtosActivation module = moduleActivation();
                ProtosActorExecutionDomain domain = module.executionDomain();
                AtomicInteger nativeExecutions = new AtomicInteger();
                ProtosClosureValue nativeClosure =
                        ProtosClosureValue.nativeClosure(
                                (activation, supplied) -> {
                                    nativeExecutions.incrementAndGet();
                                    return ProtosNullValue.INSTANCE;
                                });

                ProtosTask task =
                        domain.createTask(
                                null,
                                current -> {
                                    module.attachTask(current);
                                    ProtosBytecodeRootNode.PreparedClosureCall prepared =
                                            ProtosBytecodeRootNode.PrepareClosureCall.perform(
                                                    nativeClosure,
                                                    module);
                                    assertTrue(prepared.isNative());
                                    Object value = prepared.enterNative();
                                    current.complete(prepared.finish(value));
                                });

                assertTrue(domain.dispatchOne());
                assertEquals(ProtosTask.State.COMPLETED, task.state());
                assertSame(ProtosNullValue.INSTANCE, task.result().orElseThrow());
                assertEquals(1, nativeExecutions.get());
            } finally {
                context.leave();
            }
        }
        System.out.println("PERF006_B3B_TASK_NATIVE_DIRECT_FAST_PATH=PASS");
    }

    @Test
    void directNonTaskNativeFastPathRemainsOrdinary() {
        ProtosActivation module = moduleActivation();
        AtomicInteger nativeExecutions = new AtomicInteger();
        ProtosClosureValue nativeClosure =
                ProtosClosureValue.nativeClosure(
                        (activation, supplied) -> {
                            nativeExecutions.incrementAndGet();
                            assertTrue(activation.task().isEmpty());
                            return ProtosNullValue.INSTANCE;
                        });

        ProtosBytecodeRootNode.PreparedClosureCall prepared =
                ProtosBytecodeRootNode.PrepareClosureCall.perform(
                        nativeClosure,
                        module);

        assertTrue(prepared.isNative());
        assertSame(
                ProtosNullValue.INSTANCE,
                ProtosBytecodeRootNode.EnterClosureCall.nativeCall(
                        assertInstanceOf(ProtosBytecodeRootNode.NativeCall.class, prepared)));
        assertEquals(1, nativeExecutions.get());
        System.out.println("PERF006_B3B_ORDINARY_NATIVE_FAST_PATH=PASS");
    }

    private static ProtosClosureValue bytecodeClosure(
            ProtosLanguage language,
            ProtosActivation creator,
            String characters,
            String sourceName)
            throws Exception {
        Source source =
                Source.newBuilder(
                                ProtosLanguage.ID,
                                characters,
                                sourceName)
                        .build();
        CanonicalClosure definition = closureDefinition(characters);
        ProtosClosureExecutionPlan plan =
                ProtosClosureExecutionPlan.bytecode(
                        definition,
                        language,
                        source, ProtosExecutionMode.NORMAL);
        return new ProtosClosureValue(
                definition,
                creator.lexicalContextsForClosureCapture(),
                creator.receiver(),
                creator.methodHome().orElse(null),
                creator.returnHome().orElse(null),
                creator.prelude().orElseThrow(),
                plan);
    }

    private static CanonicalClosure closureDefinition(
            String characters) {
        CanonicalSequence sequence = canonicalize(characters);
        assertEquals(1, sequence.expressions().size());
        return (CanonicalClosure) sequence.expressions().get(0);
    }

    private static CanonicalSequence canonicalize(
            String characters) {
        return (CanonicalSequence)
                new Canonicalizer()
                        .canonicalize(
                                new ProtosParser(characters)
                                        .parseProgram());
    }

    private static ProtosActivation moduleActivation() {
        ProtosStandardObjectProtocol.install();
        ProtosObjectValue contextPrototype =
                new ProtosObjectValue(ProtosObjectValue.rootObject());
        ProtosObjectValue bindings =
                new ProtosObjectValue(contextPrototype);
        bindings.createLocalSlot("Context", contextPrototype);
        bindings.createLocalSlot(
                "Error",
                new ProtosObjectValue(ProtosObjectValue.rootObject()));
        bindings.createLocalSlot(
                "Array",
                new ProtosObjectValue(ProtosObjectValue.rootObject()));
        bindings.freeze();
        return new ProtosPrelude(bindings, contextPrototype)
                .newModuleActivation();
    }
}
