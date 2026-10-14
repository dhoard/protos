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

final class ProtosPerf006B2D5BBodySendSpreadTest {
    private static final LanguageReference<ProtosLanguage> LANGUAGE_REF =
            LanguageReference.create(ProtosLanguage.class);

    @Test
    void ordinaryAndSpreadItemsFlattenForMessageSend()
            throws Exception {
        try (Context context = Context.newBuilder(ProtosLanguage.ID).build()) {
            context.initialize(ProtosLanguage.ID);
            context.enter();
            try {
                ProtosLanguage language = LANGUAGE_REF.get(null);
                ProtosActivation module = moduleActivation();
                ProtosObjectValue receiver =
                        new ProtosObjectValue(ProtosObjectValue.rootObject());
                ProtosObjectValue a =
                        new ProtosObjectValue(ProtosObjectValue.rootObject());
                ProtosObjectValue b =
                        new ProtosObjectValue(ProtosObjectValue.rootObject());
                ProtosObjectValue c =
                        new ProtosObjectValue(ProtosObjectValue.rootObject());
                ProtosObjectValue d =
                        new ProtosObjectValue(ProtosObjectValue.rootObject());
                ProtosObjectValue marker =
                        new ProtosObjectValue(ProtosObjectValue.rootObject());
                AtomicReference<List<?>> seen = new AtomicReference<>();

                receiver.createLocalSlot(
                        "capture",
                        ProtosClosureValue.nativeClosure(
                                (activation, supplied) -> {
                                    seen.set(List.copyOf(supplied));
                                    return marker;
                                }));
                module.context().createLocalSlot("receiver", receiver);
                module.context().createLocalSlot("a", a);
                module.context().createLocalSlot("d", d);
                module.context().createLocalSlot(
                        "items",
                        module.prelude()
                                .orElseThrow()
                                .newArray(List.of(b, c)));

                Object result =
                        execute(
                                language,
                                module,
                                "receiver.capture(a, ...items, d)",
                                "perf006-b2d5b-order.protos");

                assertSame(marker, result);
                assertEquals(
                        List.of(a, b, c, d),
                        seen.get());
            } finally {
                context.leave();
            }
        }

        System.out.println("PERF006_B2D5B_SEND_SPREAD_FLATTEN_ORDER=PASS");
    }

    @Test
    void composedReceiverAndSpreadComposeTogether()
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

                Object result =
                        execute(
                                language,
                                module,
                                "entry().capture(...items)",
                                "perf006-b2d5b-composed-receiver.protos");

                assertSame(marker, result);
                assertEquals(List.of(element), seen.get());
            } finally {
                context.leave();
            }
        }

        System.out.println("PERF006_B2D5B_COMPOSED_RECEIVER_WITH_SPREAD=PASS");
    }

    @Test
    void spreadSnapshotOccursBeforeLaterMessageArgumentMutation()
            throws Exception {
        try (Context context = Context.newBuilder(ProtosLanguage.ID).build()) {
            context.initialize(ProtosLanguage.ID);
            context.enter();
            try {
                ProtosLanguage language = LANGUAGE_REF.get(null);
                ProtosActivation module = moduleActivation();
                ProtosObjectValue oldValue =
                        new ProtosObjectValue(ProtosObjectValue.rootObject());
                ProtosObjectValue newValue =
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
                                .newArray(List.of(oldValue));
                AtomicReference<List<?>> seen = new AtomicReference<>();

                receiver.createLocalSlot(
                        "capture",
                        ProtosClosureValue.nativeClosure(
                                (activation, supplied) -> {
                                    seen.set(List.copyOf(supplied));
                                    return marker;
                                }));
                module.context().createLocalSlot("receiver", receiver);
                module.context().createLocalSlot("items", items);
                module.context().createLocalSlot(
                        "mutate",
                        ProtosClosureValue.nativeClosure(
                                (activation, supplied) -> {
                                    items.indexedPut(
                                            0,
                                            newValue);
                                    return tail;
                                }));

                Object result =
                        execute(
                                language,
                                module,
                                "receiver.capture(...items, mutate())",
                                "perf006-b2d5b-snapshot-position.protos");

                assertSame(marker, result);
                assertEquals(
                        List.of(oldValue, tail),
                        seen.get());
                assertSame(
                        newValue,
                        items.indexedAt(0));
            } finally {
                context.leave();
            }
        }

        System.out.println(
                "PERF006_B2D5B_SEND_SPREAD_SNAPSHOT_AT_ARGUMENT_POSITION=PASS");
    }

    @Test
    void invalidSpreadStopsBeforeLaterMessageArgumentAndInvocation()
            throws Exception {
        try (Context context = Context.newBuilder(ProtosLanguage.ID).build()) {
            context.initialize(ProtosLanguage.ID);
            context.enter();
            try {
                ProtosLanguage language = LANGUAGE_REF.get(null);
                ProtosActivation module = moduleActivation();
                ProtosObjectValue receiver =
                        new ProtosObjectValue(ProtosObjectValue.rootObject());
                AtomicInteger laterCount = new AtomicInteger();
                AtomicInteger methodCount = new AtomicInteger();

                receiver.createLocalSlot(
                        "capture",
                        ProtosClosureValue.nativeClosure(
                                (activation, supplied) -> {
                                    methodCount.incrementAndGet();
                                    return ProtosNullValue.INSTANCE;
                                }));
                module.context().createLocalSlot("receiver", receiver);
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

                assertThrows(
                        ProtosSignalException.class,
                        () ->
                                execute(
                                        language,
                                        module,
                                        "receiver.capture(...notArray, later())",
                                        "perf006-b2d5b-invalid.protos"));

                assertEquals(0, laterCount.get());
                assertEquals(0, methodCount.get());
            } finally {
                context.leave();
            }
        }

        System.out.println("PERF006_B2D5B_INVALID_SEND_SPREAD_PRECEDENCE=PASS");
    }

    @Test
    void spreadExpressionSuspensionDoesNotReplayComposedReceiver()
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
                                "perf006-b2d5b-yielding-spread.protos"));
                module.context().createLocalSlot(
                        "later",
                        ProtosClosureValue.nativeClosure(
                                (activation, supplied) -> {
                                    laterCount.incrementAndGet();
                                    return tail;
                                }));

                Object first =
                        execute(
                                language,
                                module,
                                "entry().capture(...itemsProducer(), later())",
                                "perf006-b2d5b-spread-suspension.protos");

                ContinuationResult continuation =
                        assertInstanceOf(
                                ContinuationResult.class,
                                first);
                assertEquals(1, receiverCount.get());
                assertEquals(0, laterCount.get());
                assertEquals(0, methodCount.get());

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
            } finally {
                context.leave();
            }
        }

        System.out.println("PERF006_B2D5B_SEND_SPREAD_SUSPENSION_COMPOSED=PASS");
        System.out.println(
                "PERF006_B2D5B_RECEIVER_NO_REPLAY_ACROSS_SPREAD_SUSPENSION=PASS");
    }

    @Test
    void earlierSendSpreadSnapshotSurvivesLaterArgumentSuspension()
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
                ProtosObjectValue receiver =
                        new ProtosObjectValue(ProtosObjectValue.rootObject());
                ProtosArrayValue items =
                        module.prelude()
                                .orElseThrow()
                                .newArray(List.of(before));
                AtomicReference<List<?>> seen = new AtomicReference<>();

                receiver.createLocalSlot(
                        "capture",
                        ProtosClosureValue.nativeClosure(
                                (activation, supplied) -> {
                                    seen.set(List.copyOf(supplied));
                                    return marker;
                                }));
                module.context().createLocalSlot("receiver", receiver);
                module.context().createLocalSlot("items", items);
                module.context().createLocalSlot(
                        "later",
                        yieldingClosure(
                                language,
                                module,
                                tail,
                                "perf006-b2d5b-later-yield.protos"));

                Object first =
                        execute(
                                language,
                                module,
                                "receiver.capture(...items, later())",
                                "perf006-b2d5b-snapshot-suspension.protos");

                ContinuationResult continuation =
                        assertInstanceOf(
                                ContinuationResult.class,
                                first);

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
                "PERF006_B2D5B_SEND_SPREAD_SNAPSHOT_SURVIVES_LATER_SUSPENSION=PASS");
    }

    @Test
    void defaultSpreadLowersAfterMigration()
            throws Exception {
        try (Context context = Context.newBuilder(ProtosLanguage.ID).build()) {
            context.initialize(ProtosLanguage.ID);
            context.enter();
            try {
                ProtosLanguage language = LANGUAGE_REF.get(null);
                for (String characters :
                        List.of(
                                "(value = target(...items)) => { value }",
                                "(value = receiver.capture(...items)) => { value }")) {
                    CanonicalClosure definition =
                            closureDefinition(characters);
                    Source source =
                            Source.newBuilder(
                                            ProtosLanguage.ID,
                                            characters,
                                            "perf006-b2d5b-default-deferred.protos")
                                    .build();
                    new ProtosBytecodeClosureExecutionPlan(
                                                    definition,
                                                    language,
                                                    source, ProtosExecutionMode.NORMAL);
                }
            } finally {
                context.leave();
            }
        }

        System.out.println("PERF006_B2D5B_DEFAULT_SPREAD_LOWERING=PASS");
    }

    private static Object execute(
            ProtosLanguage language,
            ProtosActivation module,
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
                .lowerRoot(canonicalize(characters))
                .getCallTarget()
                .call(module);
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
}
