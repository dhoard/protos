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
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.guillermomolina.protos.parser.ProtosParser;
import com.guillermomolina.protos.runtime.ProtosActivation;
import com.guillermomolina.protos.runtime.ProtosArrayValue;
import com.guillermomolina.protos.runtime.ProtosClosureValue;
import com.guillermomolina.protos.runtime.ProtosNullValue;
import com.guillermomolina.protos.runtime.ProtosObjectValue;
import com.guillermomolina.protos.runtime.ProtosPrelude;
import com.guillermomolina.protos.semantic.Canonicalizer;
import com.guillermomolina.protos.semantic.ast.CanonicalClosure;
import com.guillermomolina.protos.semantic.ast.CanonicalSequence;
import com.oracle.truffle.api.TruffleLanguage.LanguageReference;
import com.oracle.truffle.api.source.Source;
import com.oracle.truffle.api.source.SourceSection;
import java.util.List;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;

final class ProtosI068Slice4SequentialParameterLoweringTest {
    private static final LanguageReference<ProtosLanguage> LANGUAGE_REF =
            LanguageReference.create(ProtosLanguage.class);

    @Test
    void requiredParameterAndLaterDefaultShareFrameBackedAuthority() throws Exception {
        withEnteredLanguage(
                (language, module) -> {
                    ProtosObjectValue token =
                            new ProtosObjectValue(ProtosObjectValue.rootObject());

                    Invocation run =
                            invoke(
                                    language,
                                    module,
                                    "(required, fallback = required) => fallback",
                                    List.of(token));

                    assertSame(token, run.result());
                    assertTrue(run.activation().context().hasLocalSlot("required"));
                    assertTrue(run.activation().context().hasLocalSlot("fallback"));
                    assertSame(
                            token,
                            run.activation()
                                    .context()
                                    .readLocalSlot("required")
                                    .orElseThrow());
                    assertSame(
                            token,
                            run.activation()
                                    .context()
                                    .readLocalSlot("fallback")
                                    .orElseThrow());
                });
    }

    @Test
    void suppliedNullSkipsDefaultAndRemainsPresent() throws Exception {
        withEnteredLanguage(
                (language, module) -> {
                    Invocation run =
                            invoke(
                                    language,
                                    module,
                                    "(value = missingDefault) => value",
                                    List.of(ProtosNullValue.INSTANCE));

                    assertSame(ProtosNullValue.INSTANCE, run.result());
                    assertTrue(run.activation().context().hasLocalSlot("value"));
                    assertSame(
                            ProtosNullValue.INSTANCE,
                            run.activation()
                                    .context()
                                    .readLocalSlot("value")
                                    .orElseThrow());
                    assertFalse(
                            run.activation()
                                    .context()
                                    .hasLocalSlot("missingDefault"));
                });
    }

    @Test
    void currentParameterIsAbsentDuringItsOwnDefaultAndFallsThrough() throws Exception {
        withEnteredLanguage(
                (language, module) -> {
                    ProtosObjectValue outer =
                            new ProtosObjectValue(ProtosObjectValue.rootObject());
                    module.context().createLocalSlot("value", outer);

                    Invocation run =
                            invoke(
                                    language,
                                    module,
                                    "(value = value) => value",
                                    List.of());

                    assertSame(outer, run.result());
                    assertSame(
                            outer,
                            run.activation()
                                    .context()
                                    .readLocalSlot("value")
                                    .orElseThrow());
                });
    }

    @Test
    void laterParameterIsAbsentDuringEarlierDefaultAndFallsThrough() throws Exception {
        withEnteredLanguage(
                (language, module) -> {
                    ProtosObjectValue outerLater =
                            new ProtosObjectValue(ProtosObjectValue.rootObject());
                    ProtosObjectValue laterDefault =
                            new ProtosObjectValue(ProtosObjectValue.rootObject());
                    module.context().createLocalSlot("later", outerLater);
                    module.context().createLocalSlot("marker", laterDefault);

                    Invocation run =
                            invoke(
                                    language,
                                    module,
                                    "(first = later, later = marker) => first",
                                    List.of());

                    assertSame(outerLater, run.result());
                    assertSame(
                            outerLater,
                            run.activation()
                                    .context()
                                    .readLocalSlot("first")
                                    .orElseThrow());
                    assertSame(
                            laterDefault,
                            run.activation()
                                    .context()
                                    .readLocalSlot("later")
                                    .orElseThrow());
                });
    }

    @Test
    void restParameterIsEstablishedAsFreshFrozenExactSuffix() throws Exception {
        withEnteredLanguage(
                (language, module) -> {
                    ProtosObjectValue first =
                            new ProtosObjectValue(ProtosObjectValue.rootObject());
                    ProtosObjectValue second =
                            new ProtosObjectValue(ProtosObjectValue.rootObject());
                    ProtosObjectValue third =
                            new ProtosObjectValue(ProtosObjectValue.rootObject());

                    Invocation run =
                            invoke(
                                    language,
                                    module,
                                    "(head, ...tail) => tail",
                                    List.of(first, second, third));

                    ProtosArrayValue rest =
                            assertInstanceOf(ProtosArrayValue.class, run.result());
                    ProtosArrayValue supplied =
                            run.activation().arguments().orElseThrow();

                    assertNotSame(supplied, rest);
                    assertSame(
                            ProtosObjectValue.MutationState.FROZEN,
                            rest.mutationState());
                    assertEquals(List.of(second, third), rest.indexedSnapshot());
                    assertSame(
                            first,
                            run.activation()
                                    .context()
                                    .readLocalSlot("head")
                                    .orElseThrow());
                    assertSame(
                            rest,
                            run.activation()
                                    .context()
                                    .readLocalSlot("tail")
                                    .orElseThrow());
                });
    }

    @Test
    void parameterBackedContextRemainsObservableAfterActivationReturns() throws Exception {
        withEnteredLanguage(
                (language, module) -> {
                    ProtosObjectValue token =
                            new ProtosObjectValue(ProtosObjectValue.rootObject());

                    Invocation run =
                            invoke(
                                    language,
                                    module,
                                    "(item) => item",
                                    List.of(token));

                    ProtosObjectValue escaped = run.activation().context();
                    assertSame(token, run.result());
                    assertTrue(escaped.hasLocalSlot("item"));
                    assertSame(token, escaped.readLocalSlot("item").orElseThrow());
                });
    }

    @Test
    void closureActivationWithFrameBackedParameterSurvivesLazySourceMaterialization()
            throws Exception {
        withEnteredLanguage(
                (language, module) -> {
                    String characters = "(item) => item";
                    Source source =
                            Source.newBuilder(
                                            ProtosLanguage.ID,
                                            characters,
                                            "i068-slice4-reparse.protos")
                                    .build();
                    CanonicalClosure definition =
                            closureDefinition(characters);
                    ProtosBytecodeClosureExecutionPlan plan =
                            new ProtosBytecodeClosureExecutionPlan(
                                    definition,
                                    language,
                                    source, ProtosExecutionMode.NORMAL);

                    SourceSection section =
                            plan.activationRootForTesting().ensureSourceSection();

                    assertSame(source, section.getSource());
                    assertEquals(
                            definition.body().span().startOffset(),
                            section.getCharIndex());
                    assertEquals(
                            definition.body().span().length(),
                            section.getCharLength());

                    ProtosObjectValue token =
                            new ProtosObjectValue(ProtosObjectValue.rootObject());
                    ProtosActivation activation =
                            invocationActivation(
                                    definition,
                                    module,
                                    List.of(token));
                    assertSame(token, plan.executeActivation(activation));
                    assertSame(
                            token,
                            activation.context()
                                    .readLocalSlot("item")
                                    .orElseThrow());
                });
    }

    private record Invocation(ProtosActivation activation, Object result) {}

    private interface LanguageTest {
        void run(ProtosLanguage language, ProtosActivation module) throws Exception;
    }

    private static void withEnteredLanguage(LanguageTest test) throws Exception {
        try (Context context = Context.newBuilder(ProtosLanguage.ID).build()) {
            context.initialize(ProtosLanguage.ID);
            context.enter();
            try {
                test.run(LANGUAGE_REF.get(null), moduleActivation());
            } finally {
                context.leave();
            }
        }
    }

    private static Invocation invoke(
            ProtosLanguage language,
            ProtosActivation module,
            String characters,
            List<?> supplied) {
        Source source =
                Source.newBuilder(
                                ProtosLanguage.ID,
                                characters,
                                "i068-slice4-parameters.protos")
                        .build();
        CanonicalClosure definition = closureDefinition(characters);
        ProtosActivation activation =
                invocationActivation(definition, module, supplied);
        Object result =
                new ProtosBytecodeClosureExecutionPlan(
                                definition,
                                language,
                                source, ProtosExecutionMode.NORMAL)
                        .executeActivation(activation);
        return new Invocation(activation, result);
    }

    private static ProtosActivation invocationActivation(
            CanonicalClosure definition,
            ProtosActivation module,
            List<?> supplied) {
        ProtosClosureValue closure =
                new ProtosClosureValue(
                        definition,
                        module.lexicalContextsForClosureCapture(),
                        module.receiver(),
                        module.methodHome().orElse(null),
                        module.returnHome().orElse(null),
                        module.prelude().orElseThrow());
        return ProtosActivation.forClosureInvocation(
                closure,
                supplied,
                module.prelude().orElseThrow(),
                module.actorModuleState(),
                module.currentModuleKey().orElse(null),
                module.executionDomain());
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
