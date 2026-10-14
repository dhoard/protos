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

import static org.junit.jupiter.api.Assertions.assertFalse;
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
import java.util.concurrent.atomic.AtomicReference;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;

final class ProtosPerf006B2D3AOrdinaryObjectCallProtocolTest {
    private static final LanguageReference<ProtosLanguage> LANGUAGE_REF =
            LanguageReference.create(ProtosLanguage.class);

    @Test
    void localNativeCallSlotUsesOriginalReceiverAndSlotHome()
            throws Exception {
        try (Context context = Context.newBuilder(ProtosLanguage.ID).build()) {
            context.initialize(ProtosLanguage.ID);
            context.enter();
            try {
                ProtosLanguage language = LANGUAGE_REF.get(null);
                ProtosActivation module = moduleActivation();
                ProtosObjectValue callable =
                        new ProtosObjectValue(ProtosObjectValue.rootObject());
                ProtosObjectValue marker =
                        new ProtosObjectValue(ProtosObjectValue.rootObject());
                AtomicReference<ProtosActivation> seen =
                        new AtomicReference<>();

                callable.createLocalSlot(
                        "call",
                        ProtosClosureValue.nativeClosure(
                                (activation, supplied) -> {
                                    seen.set(activation);
                                    return supplied.get(0);
                                }));
                module.context().createLocalSlot("callable", callable);
                module.context().createLocalSlot("marker", marker);

                Object result =
                        execute(
                                language,
                                module,
                                "callable(marker)",
                                "perf006-b2d3a-local-call.protos");

                assertSame(marker, result);
                ProtosActivation activation = seen.get();
                assertSame(callable, activation.receiver());
                assertSame(
                        callable,
                        activation.methodHome().orElseThrow());
            } finally {
                context.leave();
            }
        }

        System.out.println("PERF006_B2D3A_LOCAL_CALL_SLOT=PASS");
        System.out.println("PERF006_B2D3A_CALL_RECEIVER_HOME=PASS");
    }

    @Test
    void inheritedSourceBackedCallSlotSuspendsWithoutLosingReceiverOrHome()
            throws Exception {
        try (Context context = Context.newBuilder(ProtosLanguage.ID).build()) {
            context.initialize(ProtosLanguage.ID);
            context.enter();
            try {
                ProtosLanguage language = LANGUAGE_REF.get(null);
                ProtosActivation module = moduleActivation();
                ProtosObjectValue owner =
                        new ProtosObjectValue(ProtosObjectValue.rootObject());
                ProtosObjectValue callable =
                        new ProtosObjectValue(owner);
                ProtosObjectValue marker =
                        new ProtosObjectValue(ProtosObjectValue.rootObject());

                owner.createLocalSlot(
                        "call",
                        yieldingMethod(
                                language,
                                module,
                                marker,
                                "perf006-b2d3a-inherited-call.protos"));
                module.context().createLocalSlot("callable", callable);

                Object first =
                        execute(
                                language,
                                module,
                                "callable()",
                                "perf006-b2d3a-inherited-invoke.protos");

                ContinuationResult parent =
                        assertInstanceOf(ContinuationResult.class, first);
                ContinuationResult child =
                        assertInstanceOf(
                                ContinuationResult.class,
                                parent.getResult());
                ProtosActivation callActivation =
                        activationOf(child);

                assertSame(callable, callActivation.receiver());
                assertSame(
                        owner,
                        callActivation.methodHome().orElseThrow());
                // PERF025: the `() => { null }` plan is proven return-home
                // unobservable, so the owned home is never materialized and has
                // no lifecycle across suspension or completion.
                assertTrue(callActivation.ownsReturnHome());
                assertFalse(
                        callActivation.returnHome().orElseThrow().isMaterialized());

                Object completed =
                        parent.continueWith(ProtosNullValue.INSTANCE);

                assertSame(marker, completed);
                assertFalse(
                        callActivation.returnHome().orElseThrow().isMaterialized());
            } finally {
                context.leave();
            }
        }

        System.out.println("PERF006_B2D3A_INHERITED_CALL_SLOT=PASS");
        System.out.println("PERF006_B2D3A_CALL_SUSPENSION_NO_REPLAY=PASS");
        System.out.println("PERF006_B2D3A_INHERITED_CALL_HOME=PASS");
    }

    @Test
    void nearerNonClosureCallShadowsInheritedCallableSlot()
            throws Exception {
        try (Context context = Context.newBuilder(ProtosLanguage.ID).build()) {
            context.initialize(ProtosLanguage.ID);
            context.enter();
            try {
                ProtosLanguage language = LANGUAGE_REF.get(null);
                ProtosActivation module = moduleActivation();
                ProtosObjectValue owner =
                        new ProtosObjectValue(ProtosObjectValue.rootObject());
                ProtosObjectValue callable =
                        new ProtosObjectValue(owner);
                ProtosObjectValue nonClosure =
                        new ProtosObjectValue(ProtosObjectValue.rootObject());

                owner.createLocalSlot(
                        "call",
                        ProtosClosureValue.nativeClosure(
                                (activation, supplied) ->
                                        ProtosNullValue.INSTANCE));
                callable.createLocalSlot("call", nonClosure);
                module.context().createLocalSlot("callable", callable);

                assertThrows(
                        ProtosSignalException.class,
                        () ->
                                execute(
                                        language,
                                        module,
                                        "callable()",
                                        "perf006-b2d3a-shadow.protos"));
            } finally {
                context.leave();
            }
        }

        System.out.println("PERF006_B2D3A_NEAREST_CALL_SHADOWING=PASS");
        System.out.println("PERF006_B2D3A_NON_CLOSURE_CALL_FAILS=PASS");
    }

    @Test
    void defaultCallUsesSameOrdinaryObjectCallProtocol()
            throws Exception {
        try (Context context = Context.newBuilder(ProtosLanguage.ID).build()) {
            context.initialize(ProtosLanguage.ID);
            context.enter();
            try {
                ProtosLanguage language = LANGUAGE_REF.get(null);
                ProtosActivation module = moduleActivation();
                ProtosObjectValue callable =
                        new ProtosObjectValue(ProtosObjectValue.rootObject());
                ProtosObjectValue marker =
                        new ProtosObjectValue(ProtosObjectValue.rootObject());

                callable.createLocalSlot(
                        "call",
                        ProtosClosureValue.nativeClosure(
                                (activation, supplied) -> marker));
                module.context().createLocalSlot("callable", callable);

                String characters =
                        "(value = callable()) => { value }";
                Source source =
                        Source.newBuilder(
                                        ProtosLanguage.ID,
                                        characters,
                                        "perf006-b2d3a-default-call.protos")
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

        System.out.println("PERF006_B2D3A_DEFAULT_OBJECT_CALL_PROTOCOL=PASS");
    }

    private static ProtosClosureValue yieldingMethod(
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
        ProtosObjectValue contextPrototype =
                new ProtosObjectValue(ProtosObjectValue.rootObject());
        ProtosObjectValue bindings =
                new ProtosObjectValue(contextPrototype);
        bindings.createLocalSlot("Context", contextPrototype);
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

    private static ProtosActivation activationOf(ContinuationResult continuation) {
        Object[] arguments = continuation.getFrame().getArguments();
        if (arguments.length == 0) {
            throw new AssertionError("ContinuationResult frame has no invocation activation");
        }
        return assertInstanceOf(ProtosActivation.class, arguments[0]);
    }

}
