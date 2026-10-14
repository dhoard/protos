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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.guillermomolina.protos.parser.ProtosParser;
import com.guillermomolina.protos.runtime.ProtosActivation;
import com.guillermomolina.protos.runtime.ProtosClosureValue;
import com.guillermomolina.protos.runtime.ProtosObjectValue;
import com.guillermomolina.protos.runtime.ProtosPrelude;
import com.guillermomolina.protos.runtime.ProtosReturnHome;
import com.guillermomolina.protos.semantic.Canonicalizer;
import com.guillermomolina.protos.semantic.ast.CanonicalClosure;
import com.guillermomolina.protos.semantic.ast.CanonicalSequence;
import com.oracle.truffle.api.TruffleLanguage.LanguageReference;
import com.oracle.truffle.api.source.Source;
import com.oracle.truffle.api.source.SourceSection;
import java.util.List;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;

final class ProtosPerf006B2AClosureActivationTest {
    private static final LanguageReference<ProtosLanguage> LANGUAGE_REF =
            LanguageReference.create(ProtosLanguage.class);

    @Test
    void bytecodeClosureBodyUsesExactExistingActivationLexicalLookupAbi() throws Exception {
        try (Context context = Context.newBuilder(ProtosLanguage.ID).build()) {
            context.initialize(ProtosLanguage.ID);
            context.enter();
            try {
                ProtosLanguage language = LANGUAGE_REF.get(null);
                String characters = "() => { captured }";
                Source source =
                        Source.newBuilder(
                                        ProtosLanguage.ID,
                                        characters,
                                        "perf006-b2a-capture.protos")
                                .build();

                CanonicalClosure definition = closureDefinition(characters);
                ProtosActivation module = moduleActivation();
                Object captured =
                        new ProtosObjectValue(ProtosObjectValue.rootObject());
                module.context().createLocalSlot("captured", captured);

                ProtosClosureValue semanticClosure =
                        new ProtosClosureValue(
                                definition,
                                module.lexicalContextsForClosureCapture(),
                                module.receiver(),
                                module.methodHome().orElse(null),
                                module.returnHome().orElse(null),
                                module.prelude().orElseThrow());

                ProtosActivation invocation =
                        ProtosActivation.forClosureInvocation(
                                semanticClosure,
                                List.of(),
                                module.prelude().orElseThrow(),
                                module.actorModuleState(),
                                module.currentModuleKey().orElse(null),
                                module.executionDomain());

                ProtosBytecodeClosureExecutionPlan plan =
                        new ProtosBytecodeClosureExecutionPlan(
                                definition, language, source, ProtosExecutionMode.NORMAL);

                Object result = plan.executeActivation(invocation);

                assertSame(captured, result);
                assertSame(
                        semanticClosure.capturedReceiver(),
                        invocation.receiver());
                assertEquals(
                        semanticClosure.capturedLexicalContexts(),
                        invocation.capturedLexicalContexts());
                assertTrue(invocation.ownsReturnHome());
                assertTrue(invocation.returnHome().orElseThrow().isActive());
            } finally {
                context.leave();
            }
        }

        System.out.println("PERF006_B2A_EXACT_ACTIVATION_ARGUMENT_ABI=PASS");
        System.out.println("PERF006_B2A_LEXICAL_CAPTURE_LOOKUP=PASS");
    }

    @Test
    void bytecodeClosureBodyPreservesCapturedLexicalLookup() throws Exception {
        try (Context context = Context.newBuilder(ProtosLanguage.ID).build()) {
            context.initialize(ProtosLanguage.ID);
            context.enter();
            try {
                ProtosLanguage language = LANGUAGE_REF.get(null);
                String characters = "() => { captured }";
                Source source =
                        Source.newBuilder(
                                        ProtosLanguage.ID,
                                        characters,
                                        "perf006-b2a-bytecode-capture.protos")
                                .build();

                ProtosActivation module = moduleActivation();
                Object captured =
                        new ProtosObjectValue(ProtosObjectValue.rootObject());
                module.context().createLocalSlot("captured", captured);

                CanonicalSequence canonical = canonicalize(characters);
                CanonicalClosure definition =
                        assertInstanceOf(
                                CanonicalClosure.class,
                                canonical.expressions().get(0));

                ProtosClosureValue bytecodeSemanticClosure =
                        new ProtosClosureValue(
                                definition,
                                module.lexicalContextsForClosureCapture(),
                                module.receiver(),
                                module.methodHome().orElse(null),
                                module.returnHome().orElse(null),
                                module.prelude().orElseThrow());

                ProtosActivation bytecodeActivation =
                        ProtosActivation.forClosureInvocation(
                                bytecodeSemanticClosure,
                                List.of(),
                                module.prelude().orElseThrow(),
                                module.actorModuleState(),
                                module.currentModuleKey().orElse(null),
                                module.executionDomain());

                Object bytecodeResult =
                        new ProtosBytecodeClosureExecutionPlan(
                                        definition,
                                        language,
                                        source, ProtosExecutionMode.NORMAL)
                                .executeActivation(bytecodeActivation);

                assertSame(captured, bytecodeResult);
            } finally {
                context.leave();
            }
        }

        System.out.println(
                "PERF006_B2A_BYTECODE_CLOSURE_BODY_CAPTURE_LOOKUP=PASS");
    }

    @Test
    void invocationActivationPreservesReceiverMethodHomeAndCapturedReturnHome() throws Exception {
        try (Context context = Context.newBuilder(ProtosLanguage.ID).build()) {
            context.initialize(ProtosLanguage.ID);
            context.enter();
            try {
                ProtosLanguage language = LANGUAGE_REF.get(null);
                String characters = "() => null";
                Source source =
                        Source.newBuilder(
                                        ProtosLanguage.ID,
                                        characters,
                                        "perf006-b2a-home.protos")
                                .build();

                CanonicalClosure definition = closureDefinition(characters);
                ProtosActivation module = moduleActivation();
                Object receiver =
                        new ProtosObjectValue(ProtosObjectValue.rootObject());
                ProtosObjectValue methodHome =
                        new ProtosObjectValue(ProtosObjectValue.rootObject());
                ProtosReturnHome capturedReturnHome = new ProtosReturnHome();

                ProtosClosureValue closure =
                        new ProtosClosureValue(
                                definition,
                                module.lexicalContextsForClosureCapture(),
                                receiver,
                                methodHome,
                                capturedReturnHome,
                                module.prelude().orElseThrow());

                ProtosActivation invocation =
                        ProtosActivation.forClosureInvocation(
                                closure,
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

                assertSame(
                        com.guillermomolina.protos.runtime.ProtosNullValue.INSTANCE,
                        result);
                assertSame(receiver, invocation.receiver());
                assertSame(
                        methodHome,
                        invocation.methodHome().orElseThrow());
                assertSame(
                        capturedReturnHome,
                        invocation.returnHome().orElseThrow());
                assertFalse(invocation.ownsReturnHome());
                assertTrue(capturedReturnHome.isActive());
            } finally {
                context.leave();
            }
        }

        System.out.println("PERF006_B2A_RECEIVER_METHOD_HOME_PRESERVED=PASS");
        System.out.println("PERF006_B2A_CAPTURED_RETURN_HOME_PRESERVED=PASS");
    }

    @Test
    void bytecodeClosureRootRetainsBodySourceSection() throws Exception {
        try (Context context = Context.newBuilder(ProtosLanguage.ID).build()) {
            context.initialize(ProtosLanguage.ID);
            context.enter();
            try {
                ProtosLanguage language = LANGUAGE_REF.get(null);
                String characters = "() => { captured }";
                Source source =
                        Source.newBuilder(
                                        ProtosLanguage.ID,
                                        characters,
                                        "perf006-b2a-source.protos")
                                .build();

                CanonicalClosure definition = closureDefinition(characters);
                ProtosBytecodeClosureExecutionPlan plan =
                        new ProtosBytecodeClosureExecutionPlan(
                                definition, language, source, ProtosExecutionMode.NORMAL);

                SourceSection section =
                        plan.activationRootForTesting().ensureSourceSection();

                assertSame(source, section.getSource());
                assertEquals(
                        definition.body().span().startOffset(),
                        section.getCharIndex());
                assertEquals(
                        definition.body().span().length(),
                        section.getCharLength());
            } finally {
                context.leave();
            }
        }

        System.out.println("PERF006_B2A_CLOSURE_BODY_SOURCE_SECTION=PASS");
    }

    @Test
    void spreadDefaultLowersAfterInvocationSpreadMigration() throws Exception {
        try (Context context = Context.newBuilder(ProtosLanguage.ID).build()) {
            context.initialize(ProtosLanguage.ID);
            context.enter();
            try {
                ProtosLanguage language = LANGUAGE_REF.get(null);
                String characters = "(x, y = child(...items)) => y";
                Source source =
                        Source.newBuilder(
                                        ProtosLanguage.ID,
                                        characters,
                                        "perf006-b2a-parameters.protos")
                                .build();

                CanonicalClosure definition = closureDefinition(characters);

                new ProtosBytecodeClosureExecutionPlan(
                                                definition,
                                                language,
                                                source, ProtosExecutionMode.NORMAL);
            } finally {
                context.leave();
            }
        }

        System.out.println("PERF006_B2A_DEFAULT_SPREAD_LOWERING=PASS");
    }

    private static CanonicalClosure closureDefinition(String characters) {
        CanonicalSequence sequence = canonicalize(characters);
        assertEquals(1, sequence.expressions().size());
        return assertInstanceOf(
                CanonicalClosure.class,
                sequence.expressions().get(0));
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
        ProtosPrelude prelude =
                new ProtosPrelude(bindings, contextPrototype);
        return prelude.newModuleActivation();
    }
}
