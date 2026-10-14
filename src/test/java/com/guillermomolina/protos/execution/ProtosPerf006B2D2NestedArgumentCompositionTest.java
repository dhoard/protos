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
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.guillermomolina.protos.parser.ProtosParser;
import com.guillermomolina.protos.runtime.ProtosActivation;
import com.guillermomolina.protos.runtime.ProtosLexicalFallback;
import com.guillermomolina.protos.runtime.ProtosClosureValue;
import com.guillermomolina.protos.runtime.ProtosNullValue;
import com.guillermomolina.protos.runtime.ProtosObjectValue;
import com.guillermomolina.protos.runtime.ProtosPrelude;
import com.guillermomolina.protos.semantic.Canonicalizer;
import com.guillermomolina.protos.semantic.ast.CanonicalClosure;
import com.guillermomolina.protos.semantic.ast.CanonicalSequence;
import com.guillermomolina.protos.source.SourceSpan;
import com.oracle.truffle.api.TruffleLanguage.LanguageReference;
import com.oracle.truffle.api.bytecode.BytecodeConfig;
import com.oracle.truffle.api.bytecode.BytecodeRootNodes;
import com.oracle.truffle.api.bytecode.ContinuationResult;
import com.oracle.truffle.api.source.Source;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;

final class ProtosPerf006B2D2NestedArgumentCompositionTest {
    private static final LanguageReference<ProtosLanguage> LANGUAGE_REF =
            LanguageReference.create(ProtosLanguage.class);

    @Test
    void nestedBodyArgumentsEvaluateLeftToRightExactlyOnce() throws Exception {
        try (Context context = Context.newBuilder(ProtosLanguage.ID).build()) {
            context.initialize(ProtosLanguage.ID);
            context.enter();
            try {
                ProtosLanguage language = LANGUAGE_REF.get(null);
                ProtosActivation module = moduleActivation();
                ProtosObjectValue firstValue =
                        new ProtosObjectValue(ProtosObjectValue.rootObject());
                ProtosObjectValue secondValue =
                        new ProtosObjectValue(ProtosObjectValue.rootObject());
                ProtosObjectValue recorder =
                        new ProtosObjectValue(ProtosObjectValue.rootObject());
                ProtosObjectValue receiver =
                        new ProtosObjectValue(ProtosObjectValue.rootObject());
                List<String> order = new ArrayList<>();
                AtomicReference<List<?>> captured = new AtomicReference<>();

                recorder.createLocalSlot(
                        "first",
                        ProtosClosureValue.nativeClosure(
                                (activation, supplied) -> {
                                    order.add("first");
                                    return firstValue;
                                }));
                recorder.createLocalSlot(
                        "second",
                        ProtosClosureValue.nativeClosure(
                                (activation, supplied) -> {
                                    order.add("second");
                                    return secondValue;
                                }));
                receiver.createLocalSlot(
                        "capture",
                        ProtosClosureValue.nativeClosure(
                                (activation, supplied) -> {
                                    captured.set(List.copyOf(supplied));
                                    return supplied.get(1);
                                }));
                module.context().createLocalSlot("recorder", recorder);
                module.context().createLocalSlot("receiver", receiver);
                module.context().createLocalSlot(
                        "first",
                        sourceClosure(
                                language,
                                module,
                                "() => { recorder.first() }",
                                "perf006-b2d2-first.protos"));
                module.context().createLocalSlot(
                        "second",
                        sourceClosure(
                                language,
                                module,
                                "() => { recorder.second() }",
                                "perf006-b2d2-second.protos"));

                Object result =
                        execute(
                                language,
                                module,
                                "receiver.capture(first(), second())",
                                "perf006-b2d2-order.protos");

                assertSame(secondValue, result);
                assertEquals(List.of("first", "second"), order);
                assertEquals(2, captured.get().size());
                assertSame(firstValue, captured.get().get(0));
                assertSame(secondValue, captured.get().get(1));
            } finally {
                context.leave();
            }
        }

        System.out.println("PERF006_B2D2_NESTED_ARGUMENTS=PASS");
        System.out.println("PERF006_B2D2_ARGUMENT_ORDER_EXACTLY_ONCE=PASS");
    }

    @Test
    void laterArgumentSuspensionDoesNotReplayEarlierCompletedArgument()
            throws Exception {
        try (Context context = Context.newBuilder(ProtosLanguage.ID).build()) {
            context.initialize(ProtosLanguage.ID);
            context.enter();
            try {
                ProtosLanguage language = LANGUAGE_REF.get(null);
                ProtosActivation module = moduleActivation();
                ProtosObjectValue firstValue =
                        new ProtosObjectValue(ProtosObjectValue.rootObject());
                ProtosObjectValue secondValue =
                        new ProtosObjectValue(ProtosObjectValue.rootObject());
                ProtosObjectValue recorder =
                        new ProtosObjectValue(ProtosObjectValue.rootObject());
                ProtosObjectValue receiver =
                        new ProtosObjectValue(ProtosObjectValue.rootObject());
                List<String> order = new ArrayList<>();
                AtomicInteger captureCount = new AtomicInteger();

                recorder.createLocalSlot(
                        "first",
                        ProtosClosureValue.nativeClosure(
                                (activation, supplied) -> {
                                    order.add("first");
                                    return firstValue;
                                }));
                receiver.createLocalSlot(
                        "capture",
                        ProtosClosureValue.nativeClosure(
                                (activation, supplied) -> {
                                    captureCount.incrementAndGet();
                                    return supplied.get(1);
                                }));
                module.context().createLocalSlot("recorder", recorder);
                module.context().createLocalSlot("receiver", receiver);
                module.context().createLocalSlot(
                        "first",
                        sourceClosure(
                                language,
                                module,
                                "() => { recorder.first() }",
                                "perf006-b2d2-first-before-suspend.protos"));
                module.context().createLocalSlot(
                        "second",
                        yieldingClosure(
                                language,
                                module,
                                secondValue,
                                "perf006-b2d2-suspending-second.protos"));

                Object first =
                        execute(
                                language,
                                module,
                                "receiver.capture(first(), second())",
                                "perf006-b2d2-suspension.protos");

                ContinuationResult parent =
                        assertInstanceOf(ContinuationResult.class, first);
                assertInstanceOf(
                        ContinuationResult.class,
                        parent.getResult());
                assertEquals(List.of("first"), order);
                assertEquals(0, captureCount.get());

                Object completed =
                        parent.continueWith(ProtosNullValue.INSTANCE);

                assertSame(secondValue, completed);
                assertEquals(List.of("first"), order);
                assertEquals(1, captureCount.get());
            } finally {
                context.leave();
            }
        }

        System.out.println("PERF006_B2D2_LATER_ARGUMENT_SUSPENSION=PASS");
        System.out.println("PERF006_B2D2_EARLIER_ARGUMENT_NO_REPLAY=PASS");
    }

    @Test
    void nestedDefaultArgumentUsesSameCompositionAndSuspensionPath()
            throws Exception {
        try (Context context = Context.newBuilder(ProtosLanguage.ID).build()) {
            context.initialize(ProtosLanguage.ID);
            context.enter();
            try {
                ProtosLanguage language = LANGUAGE_REF.get(null);
                ProtosActivation module = moduleActivation();
                ProtosObjectValue marker =
                        new ProtosObjectValue(ProtosObjectValue.rootObject());

                module.context().createLocalSlot(
                        "inner",
                        yieldingClosure(
                                language,
                                module,
                                marker,
                                "perf006-b2d2-default-inner.protos"));
                module.context().createLocalSlot(
                        "outer",
                        sourceClosure(
                                language,
                                module,
                                "(value) => { value }",
                                "perf006-b2d2-default-outer.protos"));

                String characters = "(value = outer(inner())) => { value }";
                Source source =
                        Source.newBuilder(
                                        ProtosLanguage.ID,
                                        characters,
                                        "perf006-b2d2-nested-default.protos")
                                .build();
                CanonicalClosure definition = closureDefinition(characters);
                ProtosClosureExecutionPlan executionPlan =
                        ProtosClosureExecutionPlan.bytecode(
                                definition,
                                language,
                                source, ProtosExecutionMode.NORMAL);
                ProtosClosureValue semantic =
                        semanticClosure(definition, executionPlan, module);
                ProtosActivation invocation =
                        ProtosActivation.forClosureInvocation(
                                semantic,
                                List.of(),
                                module.prelude().orElseThrow(),
                                module.actorModuleState(),
                                module.currentModuleKey().orElse(null),
                                module.executionDomain());

                Object first =
                        new ProtosBytecodeClosureExecutionPlan(
                                        definition,
                                        language,
                                        source, ProtosExecutionMode.NORMAL)
                                .executeActivation(invocation);

                ContinuationResult parent =
                        assertInstanceOf(ContinuationResult.class, first);
                assertTrue(ProtosLexicalFallback.readByName(invocation, "value").isEmpty());

                Object completed =
                        parent.continueWith(ProtosNullValue.INSTANCE);

                assertSame(marker, completed);
                assertSame(marker, ProtosLexicalFallback.readByName(invocation, "value").orElseThrow());
            } finally {
                context.leave();
            }
        }

        System.out.println("PERF006_B2D2_NESTED_DEFAULT_ARGUMENT=PASS");
        System.out.println("PERF006_B2D2_DEFAULT_ARGUMENT_SUSPENSION_NO_REPLAY=PASS");
    }

    private static Object execute(
            ProtosLanguage language,
            ProtosActivation module,
            String characters,
            String sourceName)
            throws Exception {
        Source source =
                Source.newBuilder(ProtosLanguage.ID, characters, sourceName)
                        .build();
        return new CanonicalToBytecodeLowerer(language, source, ProtosExecutionMode.NORMAL)
                .lowerRoot(canonicalize(characters))
                .getCallTarget()
                .call(module);
    }

    private static ProtosClosureValue sourceClosure(
            ProtosLanguage language,
            ProtosActivation creator,
            String characters,
            String sourceName)
            throws Exception {
        Source source =
                Source.newBuilder(ProtosLanguage.ID, characters, sourceName)
                        .build();
        CanonicalClosure definition = closureDefinition(characters);
        return semanticClosure(
                definition,
                ProtosClosureExecutionPlan.bytecode(
                        definition,
                        language,
                        source, ProtosExecutionMode.NORMAL),
                creator);
    }

    private static ProtosClosureValue yieldingClosure(
            ProtosLanguage language,
            ProtosActivation creator,
            Object finalValue,
            String sourceName)
            throws Exception {
        String characters = "() => { null }";
        Source source =
                Source.newBuilder(ProtosLanguage.ID, characters, sourceName)
                        .build();
        CanonicalClosure definition = closureDefinition(characters);
        ProtosSemanticBytecodeRootNode root =
                yieldingRoot(
                        language,
                        source,
                        definition.body().span(),
                        finalValue);
        return semanticClosure(
                definition,
                ProtosClosureExecutionPlan.bytecode(
                        definition,
                        language,
                        source,
                        root, ProtosExecutionMode.NORMAL),
                creator);
    }

    private static ProtosSemanticBytecodeRootNode yieldingRoot(
            ProtosLanguage language,
            Source source,
            SourceSpan span,
            Object finalValue) {
        BytecodeRootNodes<ProtosSemanticBytecodeRootNode> roots =
                ProtosSemanticBytecodeRootNodeGen.create(
                        language,
                        BytecodeConfig.DEFAULT,
                        builder -> {
                            builder.beginSource(source);
                            builder.beginSourceSection(
                                    span.startOffset(),
                                    span.length());
                            builder.beginRoot();
                            builder.beginYield();
                            builder.emitLoadArgument(0);
                            builder.endYield();
                            builder.beginReturn();
                            builder.emitLoadConstant(finalValue);
                            builder.endReturn();
                            builder.endRoot();
                            builder.endSourceSection();
                            builder.endSource();
                        });
        return roots.getNode(0);
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

    private static CanonicalClosure closureDefinition(String characters) {
        return assertInstanceOf(
                CanonicalClosure.class,
                canonicalize(characters).expressions().get(0));
    }

    private static CanonicalSequence canonicalize(String characters) {
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
