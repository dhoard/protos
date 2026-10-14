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
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;

final class ProtosPerf006B2D4AComposedCallTargetTest {
    private static final LanguageReference<ProtosLanguage> LANGUAGE_REF =
            LanguageReference.create(ProtosLanguage.class);

    @Test
    void callResultCanBecomeTheTargetOfAnotherCall()
            throws Exception {
        try (Context context = Context.newBuilder(ProtosLanguage.ID).build()) {
            context.initialize(ProtosLanguage.ID);
            context.enter();
            try {
                ProtosLanguage language = LANGUAGE_REF.get(null);
                ProtosActivation module = moduleActivation();
                ProtosObjectValue marker =
                        new ProtosObjectValue(ProtosObjectValue.rootObject());
                module.context().createLocalSlot("marker", marker);

                ProtosClosureValue target =
                        sourceClosure(
                                language,
                                module,
                                "() => { marker }",
                                "perf006-b2d4a-target.protos");
                module.context().createLocalSlot("target", target);
                module.context().createLocalSlot(
                        "maker",
                        sourceClosure(
                                language,
                                module,
                                "() => { target }",
                                "perf006-b2d4a-maker.protos"));

                Object result =
                        execute(
                                language,
                                module,
                                "maker()()",
                                "perf006-b2d4a-call-target.protos");

                assertSame(marker, result);
            } finally {
                context.leave();
            }
        }

        System.out.println("PERF006_B2D4A_CALL_TARGET_FROM_CALL=PASS");
    }

    @Test
    void sendResultCanBecomeTheTargetOfAnotherCall()
            throws Exception {
        try (Context context = Context.newBuilder(ProtosLanguage.ID).build()) {
            context.initialize(ProtosLanguage.ID);
            context.enter();
            try {
                ProtosLanguage language = LANGUAGE_REF.get(null);
                ProtosActivation module = moduleActivation();
                ProtosObjectValue marker =
                        new ProtosObjectValue(ProtosObjectValue.rootObject());
                ProtosObjectValue holder =
                        new ProtosObjectValue(ProtosObjectValue.rootObject());
                ProtosClosureValue target =
                        sourceClosure(
                                language,
                                module,
                                "() => { marker }",
                                "perf006-b2d4a-send-target.protos");

                module.context().createLocalSlot("marker", marker);
                holder.createLocalSlot(
                        "make",
                        ProtosClosureValue.nativeClosure(
                                (activation, supplied) -> target));
                module.context().createLocalSlot("holder", holder);

                Object result =
                        execute(
                                language,
                                module,
                                "holder.make()()",
                                "perf006-b2d4a-send-derived-target.protos");

                assertSame(marker, result);
            } finally {
                context.leave();
            }
        }

        System.out.println("PERF006_B2D4A_CALL_TARGET_FROM_SEND=PASS");
    }

    @Test
    void targetEvaluatesBeforeArgumentsStrictlyOnce()
            throws Exception {
        try (Context context = Context.newBuilder(ProtosLanguage.ID).build()) {
            context.initialize(ProtosLanguage.ID);
            context.enter();
            try {
                ProtosLanguage language = LANGUAGE_REF.get(null);
                ProtosActivation module = moduleActivation();
                ProtosObjectValue marker =
                        new ProtosObjectValue(ProtosObjectValue.rootObject());
                List<String> order = new ArrayList<>();

                ProtosClosureValue target =
                        ProtosClosureValue.nativeClosure(
                                (activation, supplied) -> {
                                    order.add("invoke");
                                    return supplied.get(0);
                                });
                module.context().createLocalSlot(
                        "maker",
                        ProtosClosureValue.nativeClosure(
                                (activation, supplied) -> {
                                    order.add("target");
                                    return target;
                                }));
                module.context().createLocalSlot(
                        "argument",
                        ProtosClosureValue.nativeClosure(
                                (activation, supplied) -> {
                                    order.add("argument");
                                    return marker;
                                }));

                Object result =
                        execute(
                                language,
                                module,
                                "maker()(argument())",
                                "perf006-b2d4a-order.protos");

                assertSame(marker, result);
                assertEquals(
                        List.of("target", "argument", "invoke"),
                        order);
            } finally {
                context.leave();
            }
        }

        System.out.println("PERF006_B2D4A_TARGET_BEFORE_ARGUMENTS=PASS");
        System.out.println("PERF006_B2D4A_TARGET_ARGUMENTS_EXACTLY_ONCE=PASS");
    }

    @Test
    void laterArgumentSuspensionDoesNotReplayCompletedTarget()
            throws Exception {
        try (Context context = Context.newBuilder(ProtosLanguage.ID).build()) {
            context.initialize(ProtosLanguage.ID);
            context.enter();
            try {
                ProtosLanguage language = LANGUAGE_REF.get(null);
                ProtosActivation module = moduleActivation();
                ProtosObjectValue marker =
                        new ProtosObjectValue(ProtosObjectValue.rootObject());
                AtomicInteger makerCount = new AtomicInteger();
                AtomicInteger targetCount = new AtomicInteger();

                ProtosClosureValue target =
                        ProtosClosureValue.nativeClosure(
                                (activation, supplied) -> {
                                    targetCount.incrementAndGet();
                                    return supplied.get(0);
                                });
                module.context().createLocalSlot(
                        "maker",
                        ProtosClosureValue.nativeClosure(
                                (activation, supplied) -> {
                                    makerCount.incrementAndGet();
                                    return target;
                                }));
                module.context().createLocalSlot(
                        "argument",
                        yieldingClosure(
                                language,
                                module,
                                marker,
                                "perf006-b2d4a-suspending-argument.protos"));

                Object first =
                        execute(
                                language,
                                module,
                                "maker()(argument())",
                                "perf006-b2d4a-no-replay.protos");

                ContinuationResult continuation =
                        assertInstanceOf(
                                ContinuationResult.class,
                                first);
                assertEquals(1, makerCount.get());
                assertEquals(0, targetCount.get());

                Object completed =
                        continuation.continueWith(
                                ProtosNullValue.INSTANCE);

                assertSame(marker, completed);
                assertEquals(1, makerCount.get());
                assertEquals(1, targetCount.get());
            } finally {
                context.leave();
            }
        }

        System.out.println("PERF006_B2D4A_TARGET_NO_REPLAY_ACROSS_ARGUMENT_SUSPENSION=PASS");
    }

    @Test
    void repeatedTargetSuspensionKeepsTheSameTargetInvocationActivation()
            throws Exception {
        try (Context context = Context.newBuilder(ProtosLanguage.ID).build()) {
            context.initialize(ProtosLanguage.ID);
            context.enter();
            try {
                ProtosLanguage language = LANGUAGE_REF.get(null);
                ProtosActivation module = moduleActivation();
                ProtosObjectValue marker =
                        new ProtosObjectValue(ProtosObjectValue.rootObject());
                ProtosClosureValue target =
                        sourceClosure(
                                language,
                                module,
                                "() => { marker }",
                                "perf006-b2d4a-after-target-suspension.protos");
                module.context().createLocalSlot("marker", marker);
                module.context().createLocalSlot(
                        "maker",
                        twiceYieldingClosure(
                                language,
                                module,
                                target,
                                "perf006-b2d4a-yielding-maker.protos"));

                Object first =
                        execute(
                                language,
                                module,
                                "maker()()",
                                "perf006-b2d4a-repeated-target-suspension.protos");
                ContinuationResult outer1 =
                        assertInstanceOf(
                                ContinuationResult.class,
                                first);
                ContinuationResult maker1 =
                        assertInstanceOf(
                                ContinuationResult.class,
                                outer1.getResult());
                ProtosActivation makerActivation =
                        activationOf(maker1);

                Object second =
                        outer1.continueWith(
                                ProtosNullValue.INSTANCE);
                ContinuationResult outer2 =
                        assertInstanceOf(
                                ContinuationResult.class,
                                second);
                ContinuationResult maker2 =
                        assertInstanceOf(
                                ContinuationResult.class,
                                outer2.getResult());

                assertSame(
                        makerActivation,
                        activationOf(maker2));

                Object completed =
                        outer2.continueWith(
                                ProtosNullValue.INSTANCE);

                assertSame(marker, completed);
            } finally {
                context.leave();
            }
        }

        System.out.println("PERF006_B2D4A_TARGET_SUSPENSION_COMPOSED=PASS");
        System.out.println("PERF006_B2D4A_TARGET_ACTIVATION_IDENTITY_NO_REPLAY=PASS");
    }

    @Test
    void defaultCanUseAComposedCallTarget()
            throws Exception {
        try (Context context = Context.newBuilder(ProtosLanguage.ID).build()) {
            context.initialize(ProtosLanguage.ID);
            context.enter();
            try {
                ProtosLanguage language = LANGUAGE_REF.get(null);
                ProtosActivation module = moduleActivation();
                ProtosObjectValue marker =
                        new ProtosObjectValue(ProtosObjectValue.rootObject());

                ProtosClosureValue target =
                        ProtosClosureValue.nativeClosure(
                                (activation, supplied) -> marker);
                module.context().createLocalSlot(
                        "maker",
                        ProtosClosureValue.nativeClosure(
                                (activation, supplied) -> target));

                String characters =
                        "(value = maker()()) => { value }";
                Source source =
                        Source.newBuilder(
                                        ProtosLanguage.ID,
                                        characters,
                                        "perf006-b2d4a-default.protos")
                                .build();
                CanonicalClosure definition =
                        closureDefinition(characters);
                ProtosClosureValue owner =
                        semanticClosure(
                                definition,
                                ProtosClosureExecutionPlan.bytecode(
                                        definition,
                                        language,
                                        source, ProtosExecutionMode.NORMAL),
                                module);
                ProtosActivation invocation =
                        ProtosActivation.forClosureInvocation(
                                owner,
                                List.of(),
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

                assertSame(marker, result);
                assertSame(
                        marker,
                        ProtosLexicalFallback.readByName(invocation, "value").orElseThrow());
            } finally {
                context.leave();
            }
        }

        System.out.println("PERF006_B2D4A_DEFAULT_COMPOSED_CALL_TARGET=PASS");
    }

    @Test
    void defaultSendSpreadWithComposedAndSimpleReceiversLowers()
            throws Exception {
        try (Context context = Context.newBuilder(ProtosLanguage.ID).build()) {
            context.initialize(ProtosLanguage.ID);
            context.enter();
            try {
                ProtosLanguage language = LANGUAGE_REF.get(null);

                for (String characters :
                        List.of(
                                "(value = entry().pick(...items)) => { value }",
                                "(value = entry.pick(...items)) => { value }")) {
                    CanonicalClosure definition =
                            closureDefinition(characters);
                    Source source =
                            Source.newBuilder(
                                            ProtosLanguage.ID,
                                            characters,
                                            "perf006-b2d4a-default-send-spread-deferred.protos")
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

        System.out.println(
                "PERF006_B2D4A_DEFAULT_SEND_SPREAD_LOWERING=PASS");
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

    private static ProtosClosureValue sourceClosure(
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
        return yieldingClosure(
                language,
                creator,
                finalValue,
                sourceName,
                1);
    }

    private static ProtosClosureValue twiceYieldingClosure(
            ProtosLanguage language,
            ProtosActivation creator,
            Object finalValue,
            String sourceName)
            throws Exception {
        return yieldingClosure(
                language,
                creator,
                finalValue,
                sourceName,
                2);
    }

    private static ProtosClosureValue yieldingClosure(
            ProtosLanguage language,
            ProtosActivation creator,
            Object finalValue,
            String sourceName,
            int yields)
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
                        finalValue,
                        yields);
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
            Object finalValue,
            int yields) {
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
                            for (int i = 0; i < yields; i++) {
                                builder.beginYield();
                                builder.emitLoadArgument(0);
                                builder.endYield();
                            }
                            builder.beginReturn();
                            builder.emitLoadConstant(finalValue);
                            builder.endReturn();
                            builder.endRoot();
                            builder.endSourceSection();
                            builder.endSource();
                        });
        return roots.getNode(0);
    }

    private static ProtosActivation activationOf(
            ContinuationResult continuation) {
        Object[] arguments =
                continuation.getFrame().getArguments();
        assertTrue(arguments.length > 0);
        return assertInstanceOf(
                ProtosActivation.class,
                arguments[0]);
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
                new ProtosObjectValue(
                        ProtosObjectValue.rootObject()));
        bindings.freeze();
        return new ProtosPrelude(
                        bindings,
                        contextPrototype)
                .newModuleActivation();
    }
}
