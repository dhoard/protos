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
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.guillermomolina.protos.parser.ProtosParser;
import com.guillermomolina.protos.runtime.ProtosActivation;
import com.guillermomolina.protos.runtime.ProtosLexicalFallback;
import com.guillermomolina.protos.runtime.ProtosArrayValue;
import com.guillermomolina.protos.runtime.ProtosClosureValue;
import com.guillermomolina.protos.runtime.ProtosNullValue;
import com.guillermomolina.protos.runtime.ProtosObjectValue;
import com.guillermomolina.protos.runtime.ProtosPrelude;
import com.guillermomolina.protos.runtime.ProtosSignalException;
import com.guillermomolina.protos.semantic.Canonicalizer;
import com.guillermomolina.protos.semantic.ast.CanonicalClosure;
import com.guillermomolina.protos.semantic.ast.CanonicalSequence;
import com.guillermomolina.protos.source.SourceSpan;
import com.oracle.truffle.api.TruffleLanguage.LanguageReference;
import com.oracle.truffle.api.bytecode.BytecodeConfig;
import com.oracle.truffle.api.bytecode.BytecodeRootNodes;
import com.oracle.truffle.api.bytecode.ContinuationResult;
import com.oracle.truffle.api.source.Source;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;

final class ProtosPerf006B2D5CDefaultSpreadTest {
    private static final LanguageReference<ProtosLanguage> LANGUAGE_REF =
            LanguageReference.create(ProtosLanguage.class);

    @Test
    void defaultCallSpreadFlattensMultipleSpreadsInSourceOrder()
            throws Exception {
        try (Context context = Context.newBuilder(ProtosLanguage.ID).build()) {
            context.initialize(ProtosLanguage.ID);
            context.enter();
            try {
                ProtosLanguage language = LANGUAGE_REF.get(null);
                ProtosActivation module = moduleActivation();
                ProtosObjectValue a =
                        new ProtosObjectValue(ProtosObjectValue.rootObject());
                ProtosObjectValue b =
                        new ProtosObjectValue(ProtosObjectValue.rootObject());
                ProtosObjectValue c =
                        new ProtosObjectValue(ProtosObjectValue.rootObject());
                ProtosObjectValue d =
                        new ProtosObjectValue(ProtosObjectValue.rootObject());
                ProtosObjectValue e =
                        new ProtosObjectValue(ProtosObjectValue.rootObject());
                ProtosObjectValue marker =
                        new ProtosObjectValue(ProtosObjectValue.rootObject());
                AtomicReference<List<?>> seen = new AtomicReference<>();

                module.context().createLocalSlot("a", a);
                module.context().createLocalSlot("d", d);
                module.context().createLocalSlot(
                        "left",
                        module.prelude()
                                .orElseThrow()
                                .newArray(List.of(b, c)));
                module.context().createLocalSlot(
                        "right",
                        module.prelude()
                                .orElseThrow()
                                .newArray(List.of(e)));
                module.context().createLocalSlot(
                        "target",
                        ProtosClosureValue.nativeClosure(
                                (activation, supplied) -> {
                                    seen.set(List.copyOf(supplied));
                                    return marker;
                                }));

                Execution execution =
                        executeDefault(
                                language,
                                module,
                                "(value = target(a, ...left, d, ...right)) => { value }",
                                List.of(),
                                "perf006-b2d5c-default-call-order.protos");

                assertSame(marker, execution.result());
                assertSame(
                        marker,
                        ProtosLexicalFallback.readByName(execution.invocation(), "value")
                                .orElseThrow());
                assertEquals(
                        List.of(a, b, c, d, e),
                        seen.get());
            } finally {
                context.leave();
            }
        }

        System.out.println(
                "PERF006_B2D5C_DEFAULT_CALL_SPREAD_FLATTEN_ORDER=PASS");
    }

    @Test
    void defaultSendSpreadWorksWithComposedReceiver()
            throws Exception {
        try (Context context = Context.newBuilder(ProtosLanguage.ID).build()) {
            context.initialize(ProtosLanguage.ID);
            context.enter();
            try {
                ProtosLanguage language = LANGUAGE_REF.get(null);
                ProtosActivation module = moduleActivation();
                ProtosObjectValue element =
                        new ProtosObjectValue(ProtosObjectValue.rootObject());
                ProtosObjectValue marker =
                        new ProtosObjectValue(ProtosObjectValue.rootObject());
                ProtosObjectValue receiver =
                        new ProtosObjectValue(ProtosObjectValue.rootObject());
                AtomicReference<List<?>> seen = new AtomicReference<>();

                receiver.createLocalSlot(
                        "capture",
                        ProtosClosureValue.nativeClosure(
                                (activation, supplied) -> {
                                    seen.set(List.copyOf(supplied));
                                    return marker;
                                }));
                module.context().createLocalSlot(
                        "entry",
                        ProtosClosureValue.nativeClosure(
                                (activation, supplied) -> receiver));
                module.context().createLocalSlot(
                        "items",
                        module.prelude()
                                .orElseThrow()
                                .newArray(List.of(element)));

                Execution execution =
                        executeDefault(
                                language,
                                module,
                                "(value = entry().capture(...items)) => { value }",
                                List.of(),
                                "perf006-b2d5c-default-send.protos");

                assertSame(marker, execution.result());
                assertEquals(List.of(element), seen.get());
            } finally {
                context.leave();
            }
        }

        System.out.println(
                "PERF006_B2D5C_DEFAULT_SEND_SPREAD_COMPOSED_RECEIVER=PASS");
    }

    @Test
    void defaultSpreadSnapshotOccursBeforeLaterMutation()
            throws Exception {
        try (Context context = Context.newBuilder(ProtosLanguage.ID).build()) {
            context.initialize(ProtosLanguage.ID);
            context.enter();
            try {
                ProtosLanguage language = LANGUAGE_REF.get(null);
                ProtosActivation module = moduleActivation();
                ProtosObjectValue before =
                        new ProtosObjectValue(ProtosObjectValue.rootObject());
                ProtosObjectValue after =
                        new ProtosObjectValue(ProtosObjectValue.rootObject());
                ProtosObjectValue tail =
                        new ProtosObjectValue(ProtosObjectValue.rootObject());
                ProtosObjectValue marker =
                        new ProtosObjectValue(ProtosObjectValue.rootObject());
                ProtosArrayValue items =
                        module.prelude()
                                .orElseThrow()
                                .newArray(List.of(before));
                AtomicReference<List<?>> seen = new AtomicReference<>();

                module.context().createLocalSlot("items", items);
                module.context().createLocalSlot(
                        "mutate",
                        ProtosClosureValue.nativeClosure(
                                (activation, supplied) -> {
                                    items.indexedPut(
                                            0,
                                            after);
                                    return tail;
                                }));
                module.context().createLocalSlot(
                        "target",
                        ProtosClosureValue.nativeClosure(
                                (activation, supplied) -> {
                                    seen.set(List.copyOf(supplied));
                                    return marker;
                                }));

                Execution execution =
                        executeDefault(
                                language,
                                module,
                                "(value = target(...items, mutate())) => { value }",
                                List.of(),
                                "perf006-b2d5c-default-snapshot-position.protos");

                assertSame(marker, execution.result());
                assertEquals(
                        List.of(before, tail),
                        seen.get());
                assertSame(
                        after,
                        items.indexedAt(0));
            } finally {
                context.leave();
            }
        }

        System.out.println(
                "PERF006_B2D5C_DEFAULT_SPREAD_SNAPSHOT_AT_ARGUMENT_POSITION=PASS");
    }

    @Test
    void invalidDefaultSpreadStopsBeforeLaterArgumentAndInvocation()
            throws Exception {
        try (Context context = Context.newBuilder(ProtosLanguage.ID).build()) {
            context.initialize(ProtosLanguage.ID);
            context.enter();
            try {
                ProtosLanguage language = LANGUAGE_REF.get(null);
                ProtosActivation module = moduleActivation();
                AtomicInteger laterCount = new AtomicInteger();
                AtomicInteger targetCount = new AtomicInteger();

                module.context().createLocalSlot(
                        "notArray",
                        new ProtosObjectValue(
                                ProtosObjectValue.rootObject()));
                module.context().createLocalSlot(
                        "later",
                        ProtosClosureValue.nativeClosure(
                                (activation, supplied) -> {
                                    laterCount.incrementAndGet();
                                    return ProtosNullValue.INSTANCE;
                                }));
                module.context().createLocalSlot(
                        "target",
                        ProtosClosureValue.nativeClosure(
                                (activation, supplied) -> {
                                    targetCount.incrementAndGet();
                                    return ProtosNullValue.INSTANCE;
                                }));

                assertThrows(
                        ProtosSignalException.class,
                        () ->
                                executeDefault(
                                        language,
                                        module,
                                        "(value = target(...notArray, later())) => { value }",
                                        List.of(),
                                        "perf006-b2d5c-default-invalid.protos"));

                assertEquals(0, laterCount.get());
                assertEquals(0, targetCount.get());
            } finally {
                context.leave();
            }
        }

        System.out.println(
                "PERF006_B2D5C_INVALID_DEFAULT_SPREAD_PRECEDENCE=PASS");
    }

    @Test
    void defaultSendSpreadSuspensionDoesNotReplayReceiver()
            throws Exception {
        try (Context context = Context.newBuilder(ProtosLanguage.ID).build()) {
            context.initialize(ProtosLanguage.ID);
            context.enter();
            try {
                ProtosLanguage language = LANGUAGE_REF.get(null);
                ProtosActivation module = moduleActivation();
                ProtosObjectValue element =
                        new ProtosObjectValue(ProtosObjectValue.rootObject());
                ProtosObjectValue tail =
                        new ProtosObjectValue(ProtosObjectValue.rootObject());
                ProtosObjectValue marker =
                        new ProtosObjectValue(ProtosObjectValue.rootObject());
                ProtosObjectValue receiver =
                        new ProtosObjectValue(ProtosObjectValue.rootObject());
                ProtosArrayValue items =
                        module.prelude()
                                .orElseThrow()
                                .newArray(List.of(element));
                AtomicInteger receiverCount = new AtomicInteger();
                AtomicInteger laterCount = new AtomicInteger();
                AtomicInteger methodCount = new AtomicInteger();
                AtomicReference<List<?>> seen = new AtomicReference<>();

                receiver.createLocalSlot(
                        "capture",
                        ProtosClosureValue.nativeClosure(
                                (activation, supplied) -> {
                                    methodCount.incrementAndGet();
                                    seen.set(List.copyOf(supplied));
                                    return marker;
                                }));
                module.context().createLocalSlot(
                        "entry",
                        ProtosClosureValue.nativeClosure(
                                (activation, supplied) -> {
                                    receiverCount.incrementAndGet();
                                    return receiver;
                                }));
                module.context().createLocalSlot(
                        "itemsProducer",
                        yieldingClosure(
                                language,
                                module,
                                items,
                                "perf006-b2d5c-default-yielding-spread.protos"));
                module.context().createLocalSlot(
                        "later",
                        ProtosClosureValue.nativeClosure(
                                (activation, supplied) -> {
                                    laterCount.incrementAndGet();
                                    return tail;
                                }));

                Execution suspended =
                        executeDefault(
                                language,
                                module,
                                "(value = entry().capture(...itemsProducer(), later())) => { value }",
                                List.of(),
                                "perf006-b2d5c-default-spread-suspension.protos");

                ContinuationResult continuation =
                        assertInstanceOf(
                                ContinuationResult.class,
                                suspended.result());
                assertInstanceOf(
                        ContinuationResult.class,
                        continuation.getResult());
                assertEquals(1, receiverCount.get());
                assertEquals(0, laterCount.get());
                assertEquals(0, methodCount.get());
                assertTrue(
                        ProtosLexicalFallback.readByName(suspended.invocation(), "value")
                                .isEmpty());

                Object completed =
                        continuation.continueWith(
                                ProtosNullValue.INSTANCE);

                assertSame(marker, completed);
                assertEquals(1, receiverCount.get());
                assertEquals(1, laterCount.get());
                assertEquals(1, methodCount.get());
                assertEquals(
                        List.of(element, tail),
                        seen.get());
                assertSame(
                        marker,
                        ProtosLexicalFallback.readByName(suspended.invocation(), "value")
                                .orElseThrow());
            } finally {
                context.leave();
            }
        }

        System.out.println(
                "PERF006_B2D5C_DEFAULT_SEND_SPREAD_SUSPENSION_COMPOSED=PASS");
        System.out.println(
                "PERF006_B2D5C_DEFAULT_RECEIVER_NO_REPLAY_ACROSS_SPREAD_SUSPENSION=PASS");
    }

    @Test
    void defaultSpreadSnapshotSurvivesLaterArgumentSuspension()
            throws Exception {
        try (Context context = Context.newBuilder(ProtosLanguage.ID).build()) {
            context.initialize(ProtosLanguage.ID);
            context.enter();
            try {
                ProtosLanguage language = LANGUAGE_REF.get(null);
                ProtosActivation module = moduleActivation();
                ProtosObjectValue before =
                        new ProtosObjectValue(ProtosObjectValue.rootObject());
                ProtosObjectValue after =
                        new ProtosObjectValue(ProtosObjectValue.rootObject());
                ProtosObjectValue tail =
                        new ProtosObjectValue(ProtosObjectValue.rootObject());
                ProtosObjectValue marker =
                        new ProtosObjectValue(ProtosObjectValue.rootObject());
                ProtosArrayValue items =
                        module.prelude()
                                .orElseThrow()
                                .newArray(List.of(before));
                AtomicReference<List<?>> seen = new AtomicReference<>();

                module.context().createLocalSlot("items", items);
                module.context().createLocalSlot(
                        "later",
                        yieldingClosure(
                                language,
                                module,
                                tail,
                                "perf006-b2d5c-default-later-yield.protos"));
                module.context().createLocalSlot(
                        "target",
                        ProtosClosureValue.nativeClosure(
                                (activation, supplied) -> {
                                    seen.set(List.copyOf(supplied));
                                    return marker;
                                }));

                Execution suspended =
                        executeDefault(
                                language,
                                module,
                                "(value = target(...items, later())) => { value }",
                                List.of(),
                                "perf006-b2d5c-default-snapshot-suspension.protos");

                ContinuationResult continuation =
                        assertInstanceOf(
                                ContinuationResult.class,
                                suspended.result());

                items.indexedPut(
                        0,
                        after);

                Object completed =
                        continuation.continueWith(
                                ProtosNullValue.INSTANCE);

                assertSame(marker, completed);
                assertEquals(
                        List.of(before, tail),
                        seen.get());
                assertSame(
                        after,
                        items.indexedAt(0));
            } finally {
                context.leave();
            }
        }

        System.out.println(
                "PERF006_B2D5C_DEFAULT_SPREAD_SNAPSHOT_SURVIVES_LATER_SUSPENSION=PASS");
    }

    @Test
    void suppliedArgumentSkipsSpreadDefaultCompletely()
            throws Exception {
        try (Context context = Context.newBuilder(ProtosLanguage.ID).build()) {
            context.initialize(ProtosLanguage.ID);
            context.enter();
            try {
                ProtosLanguage language = LANGUAGE_REF.get(null);
                ProtosActivation module = moduleActivation();
                ProtosObjectValue supplied =
                        new ProtosObjectValue(ProtosObjectValue.rootObject());
                AtomicInteger producerCount = new AtomicInteger();
                AtomicInteger targetCount = new AtomicInteger();

                module.context().createLocalSlot(
                        "itemsProducer",
                        ProtosClosureValue.nativeClosure(
                                (activation, arguments) -> {
                                    producerCount.incrementAndGet();
                                    return module.prelude()
                                            .orElseThrow()
                                            .newArray(List.of());
                                }));
                module.context().createLocalSlot(
                        "target",
                        ProtosClosureValue.nativeClosure(
                                (activation, arguments) -> {
                                    targetCount.incrementAndGet();
                                    return ProtosNullValue.INSTANCE;
                                }));

                Execution execution =
                        executeDefault(
                                language,
                                module,
                                "(value = target(...itemsProducer())) => { value }",
                                List.of(supplied),
                                "perf006-b2d5c-default-skipped.protos");

                assertSame(supplied, execution.result());
                assertSame(
                        supplied,
                        ProtosLexicalFallback.readByName(execution.invocation(), "value")
                                .orElseThrow());
                assertEquals(0, producerCount.get());
                assertEquals(0, targetCount.get());
            } finally {
                context.leave();
            }
        }

        System.out.println(
                "PERF006_B2D5C_SUPPLIED_ARGUMENT_SKIPS_SPREAD_DEFAULT=PASS");
    }

    private static Execution executeDefault(
            ProtosLanguage language,
            ProtosActivation module,
            String characters,
            List<?> supplied,
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
        ProtosClosureValue semantic =
                semanticClosure(
                        definition,
                        ProtosClosureExecutionPlan.bytecode(
                                definition,
                                language,
                                source, ProtosExecutionMode.NORMAL),
                        module);
        ProtosActivation invocation =
                ProtosActivation.forClosureInvocation(
                        semantic,
                        supplied,
                        module.prelude().orElseThrow(),
                        module.actorModuleState(),
                        module.currentModuleKey().orElse(null),
                        module.executionDomain());
        Object result =
                new ProtosBytecodeClosureExecutionPlan(
                                definition,
                                language,
                                source, ProtosExecutionMode.NORMAL)
                        .executeActivation(invocation);
        return new Execution(result, invocation);
    }

    private static ProtosClosureValue yieldingClosure(
            ProtosLanguage language,
            ProtosActivation creator,
            Object finalValue,
            String sourceName)
            throws Exception {
        String characters = "() => { null }";
        Source source =
                Source.newBuilder(
                                ProtosLanguage.ID,
                                characters,
                                sourceName)
                        .build();
        CanonicalClosure definition =
                closureDefinition(characters);
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

    private static CanonicalClosure closureDefinition(
            String characters) {
        return assertInstanceOf(
                CanonicalClosure.class,
                canonicalize(characters)
                        .expressions()
                        .get(0));
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
        ProtosObjectValue arrayPrototype =
                new ProtosObjectValue(
                        ProtosObjectValue.rootObject());
        ProtosObjectValue bindings =
                new ProtosObjectValue(contextPrototype);
        bindings.createLocalSlot(
                "Context",
                contextPrototype);
        bindings.createLocalSlot(
                "Error",
                new ProtosObjectValue(
                        ProtosObjectValue.rootObject()));
        bindings.createLocalSlot(
                "Array",
                arrayPrototype);
        bindings.freeze();
        return new ProtosPrelude(
                        bindings,
                        contextPrototype)
                .newModuleActivation();
    }

    private record Execution(
            Object result,
            ProtosActivation invocation) {}
}
