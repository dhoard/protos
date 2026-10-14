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
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.guillermomolina.protos.parser.ProtosParser;
import com.guillermomolina.protos.runtime.ProtosActivation;
import com.guillermomolina.protos.runtime.ProtosClosureValue;
import com.guillermomolina.protos.runtime.ProtosNullValue;
import com.guillermomolina.protos.runtime.ProtosObjectValue;
import com.guillermomolina.protos.runtime.ProtosPrelude;
import com.guillermomolina.protos.runtime.ProtosSuspensionCapableNativeClosureBody;
import com.guillermomolina.protos.runtime.ProtosTask;
import com.guillermomolina.protos.semantic.Canonicalizer;
import com.guillermomolina.protos.semantic.ast.CanonicalClosure;
import com.guillermomolina.protos.semantic.ast.CanonicalSequence;
import com.oracle.truffle.api.TruffleLanguage.LanguageReference;
import com.oracle.truffle.api.bytecode.ContinuationResult;
import com.oracle.truffle.api.source.Source;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;

final class ProtosPerf006B3CSuspensionCapableNativeLeafTest {
    private static final LanguageReference<ProtosLanguage> LANGUAGE_REF =
            LanguageReference.create(ProtosLanguage.class);

    private static final class Dependency implements ProtosTask.WaitDependency {}

    @Test
    void capabilityProvenanceSurvivesOrdinaryMethodBinding() {
        ProtosClosureValue closure =
                ProtosClosureValue.suspensionCapableNativeClosure(
                        (activation, supplied) -> ProtosNullValue.INSTANCE,
                        (activation, supplied) -> ProtosNullValue.INSTANCE);
        Object receiver =
                new ProtosObjectValue(
                        ProtosObjectValue.rootObject());
        ProtosObjectValue home =
                new ProtosObjectValue(
                        ProtosObjectValue.rootObject());

        ProtosClosureValue bound =
                closure.bindMethod(
                        receiver,
                        home);

        Object originalBody =
                closure.nativeBody().orElseThrow();
        Object reboundBody =
                bound.nativeBody().orElseThrow();
        assertSame(
                originalBody,
                reboundBody,
                "method binding must preserve exact native implementation provenance");
        assertInstanceOf(
                ProtosSuspensionCapableNativeClosureBody.class,
                reboundBody);

        System.out.println(
                "PERF006_B3C_CAPABILITY_PROVENANCE_REBINDING=PASS");
    }

    @Test
    void nonTaskBytecodeInvocationUsesOnlyOrdinaryNativeEntry()
            throws Exception {
        try (Context context =
                Context.newBuilder(ProtosLanguage.ID).build()) {
            context.initialize(ProtosLanguage.ID);
            context.enter();
            try {
                ProtosLanguage language = LANGUAGE_REF.get(null);
                ProtosActivation module = moduleActivation();
                ProtosObjectValue ordinaryValue =
                        new ProtosObjectValue(
                                ProtosObjectValue.rootObject());
                ProtosObjectValue continuationValue =
                        new ProtosObjectValue(
                                ProtosObjectValue.rootObject());
                AtomicInteger ordinaryCalls = new AtomicInteger();
                AtomicInteger continuationCalls = new AtomicInteger();

                ProtosClosureValue leaf =
                        ProtosClosureValue.suspensionCapableNativeClosure(
                                (activation, supplied) -> {
                                    ordinaryCalls.incrementAndGet();
                                    return ordinaryValue;
                                },
                                (activation, supplied) -> {
                                    continuationCalls.incrementAndGet();
                                    return continuationValue;
                                });
                module.context().createLocalSlot(
                        "leaf",
                        leaf);

                Object result =
                        lowerRoot(
                                        language,
                                        "leaf()",
                                        "perf006-b3c-nontask.protos")
                                .getCallTarget()
                                .call(module);

                assertSame(
                        ordinaryValue,
                        result);
                assertEquals(
                        1,
                        ordinaryCalls.get());
                assertEquals(
                        0,
                        continuationCalls.get());
            } finally {
                context.leave();
            }
        }

        System.out.println(
                "PERF006_B3C_NON_TASK_NATIVE_FAST_PATH=PASS");
    }

    @Test
    void taskBackedCapabilityMayCompleteSynchronouslyWithoutYield()
            throws Exception {
        try (Context context =
                Context.newBuilder(ProtosLanguage.ID).build()) {
            context.initialize(ProtosLanguage.ID);
            context.enter();
            try {
                ProtosLanguage language = LANGUAGE_REF.get(null);
                ProtosActivation module = moduleActivation();
                ProtosTask task = attachTaskIdentity(module);
                ProtosObjectValue value =
                        new ProtosObjectValue(
                                ProtosObjectValue.rootObject());
                AtomicInteger ordinaryCalls = new AtomicInteger();
                AtomicInteger continuationCalls = new AtomicInteger();
                AtomicReference<ProtosTask> seenTask =
                        new AtomicReference<>();

                ProtosClosureValue leaf =
                        ProtosClosureValue.suspensionCapableNativeClosure(
                                (activation, supplied) -> {
                                    ordinaryCalls.incrementAndGet();
                                    return ProtosNullValue.INSTANCE;
                                },
                                (activation, supplied) -> {
                                    continuationCalls.incrementAndGet();
                                    seenTask.set(
                                            activation.task().orElseThrow());
                                    return value;
                                });
                module.context().createLocalSlot(
                        "leaf",
                        leaf);

                Object result =
                        lowerRoot(
                                        language,
                                        "leaf()",
                                        "perf006-b3c-task-immediate.protos")
                                .getCallTarget()
                                .call(module);

                assertSame(
                        value,
                        result);
                assertSame(
                        task,
                        seenTask.get());
                assertEquals(
                        0,
                        ordinaryCalls.get());
                assertEquals(
                        1,
                        continuationCalls.get());
            } finally {
                context.leave();
            }
        }

        System.out.println(
                "PERF006_B3C_TASK_CAPABILITY_SYNCHRONOUS_FAST_PATH=PASS");
    }

    @Test
    void taskBackedNativeSuspensionBecomesCPrimeLeafAndResumesWithoutReplay()
            throws Exception {
        try (Context context =
                Context.newBuilder(ProtosLanguage.ID).build()) {
            context.initialize(ProtosLanguage.ID);
            context.enter();
            try {
                ProtosLanguage language = LANGUAGE_REF.get(null);
                ProtosActivation module = moduleActivation();
                ProtosTask task = attachTaskIdentity(module);
                Dependency dependency = new Dependency();
                ProtosObjectValue completed =
                        new ProtosObjectValue(
                                ProtosObjectValue.rootObject());
                AtomicInteger ordinaryCalls = new AtomicInteger();
                AtomicInteger continuationCalls = new AtomicInteger();
                AtomicInteger resumerCalls = new AtomicInteger();
                AtomicReference<ProtosTask> seenTask =
                        new AtomicReference<>();
                AtomicReference<ProtosNativeSuspension> descriptor =
                        new AtomicReference<>();

                ProtosClosureValue leaf =
                        ProtosClosureValue.suspensionCapableNativeClosure(
                                (activation, supplied) -> {
                                    ordinaryCalls.incrementAndGet();
                                    return ProtosNullValue.INSTANCE;
                                },
                                (activation, supplied) -> {
                                    continuationCalls.incrementAndGet();
                                    seenTask.set(
                                            activation.task().orElseThrow());
                                    ProtosNativeSuspension pending =
                                            ProtosNativeSuspension.pending(
                                                    dependency,
                                                    () -> {
                                                        resumerCalls.incrementAndGet();
                                                        return completed;
                                                    });
                                    descriptor.set(pending);
                                    return pending;
                                });
                module.context().createLocalSlot(
                        "leaf",
                        leaf);

                Object first =
                        lowerRoot(
                                        language,
                                        "leaf()",
                                        "perf006-b3c-leaf-suspension.protos")
                                .getCallTarget()
                                .call(module);

                ContinuationResult continuation =
                        assertInstanceOf(
                                ContinuationResult.class,
                                first);
                ProtosNativeSuspension yielded =
                        assertInstanceOf(
                                ProtosNativeSuspension.class,
                                continuation.getResult());
                assertSame(
                        descriptor.get(),
                        yielded);
                assertSame(
                        dependency,
                        yielded.dependency());
                assertSame(
                        task,
                        seenTask.get());
                assertEquals(
                        0,
                        ordinaryCalls.get());
                assertEquals(
                        1,
                        continuationCalls.get());
                assertEquals(
                        0,
                        resumerCalls.get());

                Object result =
                        continuation.continueWith(
                                ProtosNullValue.INSTANCE);

                assertSame(
                        completed,
                        result);
                assertEquals(
                        1,
                        continuationCalls.get(),
                        "resume must not re-enter the native capability body");
                assertEquals(
                        1,
                        resumerCalls.get());
                assertThrows(
                        IllegalStateException.class,
                        yielded::resume);
            } finally {
                context.leave();
            }
        }

        System.out.println(
                "PERF006_B3C_NATIVE_SUSPENSION_CPRIME_LEAF=PASS");
        System.out.println(
                "PERF006_B3C_NATIVE_JAVA_ACTIVATION_REPLAY=NO");
        System.out.println(
                "PERF006_B3C_NATIVE_RESUMER_ONE_SHOT=PASS");
    }

    @Test
    void nestedSourceCallPropagatesNativeLeafThroughCompleteCPrimeChain()
            throws Exception {
        try (Context context =
                Context.newBuilder(ProtosLanguage.ID).build()) {
            context.initialize(ProtosLanguage.ID);
            context.enter();
            try {
                ProtosLanguage language = LANGUAGE_REF.get(null);
                ProtosActivation module = moduleActivation();
                ProtosTask task = attachTaskIdentity(module);
                Dependency dependency = new Dependency();
                ProtosObjectValue completed =
                        new ProtosObjectValue(
                                ProtosObjectValue.rootObject());
                AtomicInteger capabilityCalls = new AtomicInteger();
                AtomicInteger resumerCalls = new AtomicInteger();
                AtomicReference<ProtosTask> leafTask =
                        new AtomicReference<>();

                ProtosClosureValue leaf =
                        ProtosClosureValue.suspensionCapableNativeClosure(
                                (activation, supplied) -> ProtosNullValue.INSTANCE,
                                (activation, supplied) -> {
                                    capabilityCalls.incrementAndGet();
                                    leafTask.set(
                                            activation.task().orElseThrow());
                                    return ProtosNativeSuspension.pending(
                                            dependency,
                                            () -> {
                                                resumerCalls.incrementAndGet();
                                                return completed;
                                            });
                                });
                module.context().createLocalSlot(
                        "leaf",
                        leaf);

                ProtosClosureValue middle =
                        bytecodeClosure(
                                language,
                                module,
                                "() => { leaf() }",
                                "perf006-b3c-middle.protos");
                module.context().createLocalSlot(
                        "entry",
                        middle);

                Object first =
                        lowerRoot(
                                        language,
                                        "entry()",
                                        "perf006-b3c-top.protos")
                                .getCallTarget()
                                .call(module);

                ContinuationResult top =
                        assertInstanceOf(
                                ContinuationResult.class,
                                first);
                ContinuationResult middleContinuation =
                        assertInstanceOf(
                                ContinuationResult.class,
                                top.getResult());
                ProtosNativeSuspension yielded =
                        assertInstanceOf(
                                ProtosNativeSuspension.class,
                                terminalContinuationResult(middleContinuation));

                assertSame(
                        dependency,
                        yielded.dependency());
                assertSame(
                        task,
                        leafTask.get());
                assertEquals(
                        1,
                        capabilityCalls.get());
                assertEquals(
                        0,
                        resumerCalls.get());

                Object result =
                        top.continueWith(
                                ProtosNullValue.INSTANCE);

                assertSame(
                        completed,
                        result);
                assertEquals(
                        1,
                        capabilityCalls.get(),
                        "the native leaf must not be replayed through nested callers");
                assertEquals(
                        1,
                        resumerCalls.get());
            } finally {
                context.leave();
            }
        }

        System.out.println(
                "PERF006_B3C_NESTED_NATIVE_SUSPENSION_CPRIME_CHAIN=PASS");
        System.out.println(
                "PERF006_B3C_NESTED_CALLER_REPLAY=NO");
    }

    private static ProtosTask attachTaskIdentity(
            ProtosActivation activation) {
        ProtosTask task =
                activation.executionDomain()
                        .createTask(
                                null,
                                current ->
                                        current.complete(
                                                ProtosNullValue.INSTANCE));
        activation.attachTask(task);
        return task;
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
                        canonicalize(characters));
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
        CanonicalClosure definition =
                closureDefinition(characters);
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
        CanonicalSequence sequence =
                canonicalize(characters);
        assertEquals(
                1,
                sequence.expressions().size());
        return (CanonicalClosure)
                sequence.expressions().get(0);
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
                new ProtosObjectValue(
                        ProtosObjectValue.rootObject());
        ProtosObjectValue bindings =
                new ProtosObjectValue(
                        contextPrototype);
        bindings.createLocalSlot(
                "Context",
                contextPrototype);
        bindings.createLocalSlot(
                "Error",
                new ProtosObjectValue(
                        ProtosObjectValue.rootObject()));
        bindings.createLocalSlot(
                "Array",
                new ProtosObjectValue(
                        ProtosObjectValue.rootObject()));
        bindings.freeze();
        return new ProtosPrelude(
                        bindings,
                        contextPrototype)
                .newModuleActivation();
    }

    private static Object terminalContinuationResult(ContinuationResult continuation) {
        Object result = continuation.getResult();
        int depth = 0;
        while (result instanceof ContinuationResult nested) {
            depth++;
            if (depth > 32) {
                throw new AssertionError("unexpectedly deep Bytecode continuation composition");
            }
            result = nested.getResult();
        }
        return result;
    }

}
