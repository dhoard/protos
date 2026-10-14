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
import java.util.concurrent.atomic.AtomicReference;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;

final class ProtosPerf006B2D1OrdinarySendCompositionTest {
    private static final LanguageReference<ProtosLanguage> LANGUAGE_REF =
            LanguageReference.create(ProtosLanguage.class);

    @Test
    void sourceBackedMethodSendComposesSuspensionAndPreservesReceiverHome()
            throws Exception {
        try (Context context = Context.newBuilder(ProtosLanguage.ID).build()) {
            context.initialize(ProtosLanguage.ID);
            context.enter();
            try {
                ProtosLanguage language = LANGUAGE_REF.get(null);
                ProtosActivation module = moduleActivation();
                ProtosObjectValue receiver =
                        new ProtosObjectValue(ProtosObjectValue.rootObject());
                ProtosObjectValue marker =
                        new ProtosObjectValue(ProtosObjectValue.rootObject());

                String methodCharacters = "() => { null }";
                Source methodSource =
                        Source.newBuilder(
                                        ProtosLanguage.ID,
                                        methodCharacters,
                                        "perf006-b2d1-suspending-method.protos")
                                .build();
                CanonicalClosure methodDefinition =
                        closureDefinition(methodCharacters);
                ProtosSemanticBytecodeRootNode methodRoot =
                        yieldingMethodRoot(
                                language,
                                methodSource,
                                methodDefinition.body().span(),
                                marker);
                ProtosClosureValue method =
                        semanticClosure(
                                methodDefinition,
                                ProtosClosureExecutionPlan.bytecode(
                                        methodDefinition,
                                        language,
                                        methodSource,
                                        methodRoot, ProtosExecutionMode.NORMAL),
                                module);
                receiver.createLocalSlot("pick", method);
                module.context().createLocalSlot("receiver", receiver);

                String characters = "receiver.pick()";
                Source source =
                        Source.newBuilder(
                                        ProtosLanguage.ID,
                                        characters,
                                        "perf006-b2d1-source-send.protos")
                                .build();
                Object first =
                        new CanonicalToBytecodeLowerer(language, source, ProtosExecutionMode.NORMAL)
                                .lowerRoot(canonicalize(characters))
                                .getCallTarget()
                                .call(module);

                ContinuationResult parent =
                        assertInstanceOf(ContinuationResult.class, first);
                ContinuationResult child =
                        assertInstanceOf(
                                ContinuationResult.class,
                                parent.getResult());
                ProtosActivation methodActivation =
                        activationOf(child);

                assertSame(receiver, methodActivation.receiver());
                assertSame(
                        receiver,
                        methodActivation.methodHome().orElseThrow());
                // PERF025: the `() => { null }` plan is proven return-home
                // unobservable, so the owned home is never materialized and has
                // no lifecycle across suspension or completion.
                assertTrue(methodActivation.ownsReturnHome());
                assertFalse(
                        methodActivation.returnHome().orElseThrow().isMaterialized());

                Object completed =
                        parent.continueWith(ProtosNullValue.INSTANCE);

                assertSame(marker, completed);
                assertFalse(
                        methodActivation.returnHome().orElseThrow().isMaterialized());
            } finally {
                context.leave();
            }
        }

        System.out.println("PERF006_B2D1_SOURCE_SEND_COMPOSITION=PASS");
        System.out.println("PERF006_B2D1_SEND_SUSPENSION_NO_REPLAY=PASS");
        System.out.println("PERF006_B2D1_IMMEDIATE_METHOD_RECEIVER_HOME=PASS");
    }

    @Test
    void nativeMethodSendUsesSameImmediateMethodBindingAndSuppliedVector()
            throws Exception {
        try (Context context = Context.newBuilder(ProtosLanguage.ID).build()) {
            context.initialize(ProtosLanguage.ID);
            context.enter();
            try {
                ProtosLanguage language = LANGUAGE_REF.get(null);
                ProtosActivation module = moduleActivation();
                ProtosObjectValue receiver =
                        new ProtosObjectValue(ProtosObjectValue.rootObject());
                ProtosObjectValue marker =
                        new ProtosObjectValue(ProtosObjectValue.rootObject());
                AtomicReference<ProtosActivation> seenActivation =
                        new AtomicReference<>();

                receiver.createLocalSlot(
                        "echo",
                        ProtosClosureValue.nativeClosure(
                                (activation, supplied) -> {
                                    seenActivation.set(activation);
                                    return supplied.get(0);
                                }));
                module.context().createLocalSlot("receiver", receiver);
                module.context().createLocalSlot("marker", marker);

                String characters = "receiver.echo(marker)";
                Source source =
                        Source.newBuilder(
                                        ProtosLanguage.ID,
                                        characters,
                                        "perf006-b2d1-native-send.protos")
                                .build();
                Object result =
                        new CanonicalToBytecodeLowerer(language, source, ProtosExecutionMode.NORMAL)
                                .lowerRoot(canonicalize(characters))
                                .getCallTarget()
                                .call(module);

                assertSame(marker, result);
                ProtosActivation activation = seenActivation.get();
                assertSame(receiver, activation.receiver());
                assertSame(
                        receiver,
                        activation.methodHome().orElseThrow());
                assertFalse(activation.returnHome().orElseThrow().isActive());
            } finally {
                context.leave();
            }
        }

        System.out.println("PERF006_B2D1_NATIVE_SEND=PASS");
        System.out.println("PERF006_B2D1_NATIVE_SEND_ARGUMENT_IDENTITY=PASS");
    }

    @Test
    void defaultSendSpreadLowersAfterMigration() throws Exception {
        try (Context context = Context.newBuilder(ProtosLanguage.ID).build()) {
            context.initialize(ProtosLanguage.ID);
            context.enter();
            try {
                ProtosLanguage language = LANGUAGE_REF.get(null);
                String characters =
                        "(value = factory().echo(...items)) => { value }";
                CanonicalClosure definition =
                        closureDefinition(characters);
                Source source =
                        Source.newBuilder(
                                        ProtosLanguage.ID,
                                        characters,
                                        "perf006-b2d1-default-send-spread-deferred.protos")
                                .build();

                new ProtosBytecodeClosureExecutionPlan(
                                                definition,
                                                language,
                                                source, ProtosExecutionMode.NORMAL);
            } finally {
                context.leave();
            }
        }

        System.out.println(
                "PERF006_B2D1_DEFAULT_SEND_SPREAD_LOWERING=PASS");
    }

    private static ProtosSemanticBytecodeRootNode yieldingMethodRoot(
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
        return new ProtosPrelude(bindings, contextPrototype)
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
