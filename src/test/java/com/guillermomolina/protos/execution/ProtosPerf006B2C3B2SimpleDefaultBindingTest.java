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
import com.guillermomolina.protos.runtime.ProtosObjectValue;
import com.guillermomolina.protos.runtime.ProtosPrelude;
import com.guillermomolina.protos.runtime.ProtosSignalException;
import com.guillermomolina.protos.runtime.ProtosStringValue;
import com.guillermomolina.protos.semantic.Canonicalizer;
import com.guillermomolina.protos.semantic.ast.CanonicalClosure;
import com.guillermomolina.protos.semantic.ast.CanonicalSequence;
import com.oracle.truffle.api.TruffleLanguage.LanguageReference;
import com.oracle.truffle.api.source.Source;
import java.util.List;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;

final class ProtosPerf006B2C3B2SimpleDefaultBindingTest {
    private static final LanguageReference<ProtosLanguage> LANGUAGE_REF =
            LanguageReference.create(ProtosLanguage.class);

    @Test
    void omittedLiteralAndLookupDefaultsExecuteInTheActivationRoot()
            throws Exception {
        try (Context context = Context.newBuilder(ProtosLanguage.ID).build()) {
            context.initialize(ProtosLanguage.ID);
            context.enter();
            try {
                ProtosLanguage language = LANGUAGE_REF.get(null);
                ProtosActivation module = moduleActivation();
                ProtosObjectValue head =
                        new ProtosObjectValue(ProtosObjectValue.rootObject());

                String characters =
                        "(head, literalDefault = \"fallback\", inherited = head) => { inherited }";
                Source source =
                        Source.newBuilder(
                                        ProtosLanguage.ID,
                                        characters,
                                        "perf006-b2c3b2-simple-defaults.protos")
                                .build();
                CanonicalClosure definition = closureDefinition(characters);
                ProtosClosureValue semantic =
                        new ProtosClosureValue(
                                definition,
                                module.lexicalContextsForClosureCapture(),
                                module.receiver(),
                                module.methodHome().orElse(null),
                                module.returnHome().orElse(null),
                                module.prelude().orElseThrow());

                ProtosActivation invocation =
                        ProtosActivation.forClosureInvocation(
                                semantic,
                                List.of(head),
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

                assertSame(head, result);
                ProtosStringValue literal =
                        assertInstanceOf(
                                ProtosStringValue.class,
                                ProtosLexicalFallback.readByName(invocation, "literalDefault").orElseThrow());
                assertEquals("fallback", literal.value());
                assertSame(
                        head,
                        ProtosLexicalFallback.readByName(invocation, "inherited").orElseThrow());

                ProtosArrayValue args =
                        invocation.arguments().orElseThrow();
                assertEquals(1, args.indexedSnapshot().size());
                assertSame(head, args.indexedSnapshot().get(0));
            } finally {
                context.leave();
            }
        }

        System.out.println("PERF006_B2C3B2_LITERAL_DEFAULT=PASS");
        System.out.println("PERF006_B2C3B2_LOOKUP_DEFAULT=PASS");
        System.out.println("PERF006_B2C3B2_EARLIER_PARAMETER_VISIBILITY=PASS");
        System.out.println("PERF006_B2C3B2_ARGS_VECTOR_UNCHANGED=PASS");
    }

    @Test
    void suppliedArgumentsSuppressTheirDefaultsCompletely()
            throws Exception {
        try (Context context = Context.newBuilder(ProtosLanguage.ID).build()) {
            context.initialize(ProtosLanguage.ID);
            context.enter();
            try {
                ProtosLanguage language = LANGUAGE_REF.get(null);
                ProtosActivation module = moduleActivation();

                ProtosObjectValue first =
                        new ProtosObjectValue(ProtosObjectValue.rootObject());
                ProtosObjectValue second =
                        new ProtosObjectValue(ProtosObjectValue.rootObject());
                ProtosObjectValue third =
                        new ProtosObjectValue(ProtosObjectValue.rootObject());
                module.context().createLocalSlot("first", first);
                module.context().createLocalSlot("second", second);
                module.context().createLocalSlot("third", third);

                String closureCharacters =
                        "(head, fallback = missingDefault, finalValue = missingFinal) => { finalValue }";
                Source closureSource =
                        Source.newBuilder(
                                        ProtosLanguage.ID,
                                        closureCharacters,
                                        "perf006-b2c3b2-suppression-closure.protos")
                                .build();
                CanonicalClosure definition =
                        closureDefinition(closureCharacters);
                module.context().createLocalSlot(
                        "entry",
                        semanticClosure(
                                definition,
                                ProtosClosureExecutionPlan.bytecode(
                                        definition,
                                        language,
                                        closureSource, ProtosExecutionMode.NORMAL),
                                module));

                String callCharacters =
                        "entry(first, second, third)";
                Source callSource =
                        Source.newBuilder(
                                        ProtosLanguage.ID,
                                        callCharacters,
                                        "perf006-b2c3b2-suppression-call.protos")
                                .build();
                ProtosSemanticBytecodeRootNode caller =
                        new CanonicalToBytecodeLowerer(language, callSource, ProtosExecutionMode.NORMAL)
                                .lowerRoot(canonicalize(callCharacters));

                assertSame(third, caller.getCallTarget().call(module));
            } finally {
                context.leave();
            }
        }

        System.out.println("PERF006_B2C3B2_SUPPLIED_ARGUMENT_SUPPRESSES_DEFAULT=PASS");
        System.out.println("PERF006_B2C3B2_SOURCE_LEVEL_SIMPLE_DEFAULT_CALL=PASS");
    }

    @Test
    void defaultBeforeRestCapturesOnlyActuallyUnconsumedArguments()
            throws Exception {
        try (Context context = Context.newBuilder(ProtosLanguage.ID).build()) {
            context.initialize(ProtosLanguage.ID);
            context.enter();
            try {
                ProtosLanguage language = LANGUAGE_REF.get(null);
                ProtosActivation module = moduleActivation();

                ProtosObjectValue first =
                        new ProtosObjectValue(ProtosObjectValue.rootObject());
                ProtosObjectValue second =
                        new ProtosObjectValue(ProtosObjectValue.rootObject());
                ProtosObjectValue third =
                        new ProtosObjectValue(ProtosObjectValue.rootObject());

                String characters =
                        "(head, fallback = head, ...tail) => { tail }";
                Source source =
                        Source.newBuilder(
                                        ProtosLanguage.ID,
                                        characters,
                                        "perf006-b2c3b2-default-rest.protos")
                                .build();
                CanonicalClosure definition = closureDefinition(characters);
                ProtosClosureValue semantic =
                        new ProtosClosureValue(
                                definition,
                                module.lexicalContextsForClosureCapture(),
                                module.receiver(),
                                module.methodHome().orElse(null),
                                module.returnHome().orElse(null),
                                module.prelude().orElseThrow());
                ProtosBytecodeClosureExecutionPlan plan =
                        new ProtosBytecodeClosureExecutionPlan(
                                definition,
                                language,
                                source, ProtosExecutionMode.NORMAL);

                ProtosActivation omitted =
                        ProtosActivation.forClosureInvocation(
                                semantic,
                                List.of(first),
                                module.prelude().orElseThrow(),
                                module.actorModuleState(),
                                module.currentModuleKey().orElse(null),
                                module.executionDomain());
                ProtosArrayValue omittedRest =
                        assertInstanceOf(
                                ProtosArrayValue.class,
                                plan.executeActivation(omitted));
                assertSame(first, ProtosLexicalFallback.readByName(omitted, "fallback").orElseThrow());
                assertEquals(0, omittedRest.indexedSnapshot().size());

                ProtosActivation supplied =
                        ProtosActivation.forClosureInvocation(
                                semantic,
                                List.of(first, second, third),
                                module.prelude().orElseThrow(),
                                module.actorModuleState(),
                                module.currentModuleKey().orElse(null),
                                module.executionDomain());
                ProtosArrayValue suppliedRest =
                        assertInstanceOf(
                                ProtosArrayValue.class,
                                plan.executeActivation(supplied));
                assertSame(second, ProtosLexicalFallback.readByName(supplied, "fallback").orElseThrow());
                assertEquals(1, suppliedRest.indexedSnapshot().size());
                assertSame(third, suppliedRest.indexedSnapshot().get(0));
                assertSame(
                        ProtosObjectValue.MutationState.FROZEN,
                        suppliedRest.mutationState());
            } finally {
                context.leave();
            }
        }

        System.out.println("PERF006_B2C3B2_DEFAULT_BEFORE_REST=PASS");
        System.out.println("PERF006_B2C3B2_REST_CONSUMED_PREFIX=PASS");
    }

    @Test
    void requiredArityAndSpreadDefaultLoweringCoexist()
            throws Exception {
        try (Context context = Context.newBuilder(ProtosLanguage.ID).build()) {
            context.initialize(ProtosLanguage.ID);
            context.enter();
            try {
                ProtosLanguage language = LANGUAGE_REF.get(null);
                ProtosActivation module = moduleActivation();

                String requiredCharacters =
                        "(head, fallback = head) => { fallback }";
                Source requiredSource =
                        Source.newBuilder(
                                        ProtosLanguage.ID,
                                        requiredCharacters,
                                        "perf006-b2c3b2-required.protos")
                                .build();
                CanonicalClosure requiredDefinition =
                        closureDefinition(requiredCharacters);
                ProtosClosureValue semantic =
                        new ProtosClosureValue(
                                requiredDefinition,
                                module.lexicalContextsForClosureCapture(),
                                module.receiver(),
                                module.methodHome().orElse(null),
                                module.returnHome().orElse(null),
                                module.prelude().orElseThrow());
                ProtosActivation missing =
                        ProtosActivation.forClosureInvocation(
                                semantic,
                                List.of(),
                                module.prelude().orElseThrow(),
                                module.actorModuleState(),
                                module.currentModuleKey().orElse(null),
                                module.executionDomain());
                ProtosBytecodeClosureExecutionPlan requiredPlan =
                        new ProtosBytecodeClosureExecutionPlan(
                                requiredDefinition,
                                language,
                                requiredSource, ProtosExecutionMode.NORMAL);
                assertThrows(
                        ProtosSignalException.class,
                        () -> requiredPlan.executeActivation(missing));

                String complexCharacters =
                        "(head, fallback = child(...items)) => { fallback }";
                Source complexSource =
                        Source.newBuilder(
                                        ProtosLanguage.ID,
                                        complexCharacters,
                                        "perf006-b2c3b2-call-default-deferred.protos")
                                .build();
                CanonicalClosure complexDefinition =
                        closureDefinition(complexCharacters);
                new ProtosBytecodeClosureExecutionPlan(
                                                complexDefinition,
                                                language,
                                                complexSource, ProtosExecutionMode.NORMAL);
            } finally {
                context.leave();
            }
        }

        System.out.println("PERF006_B2C3B2_REQUIRED_ARITY_ERROR=PASS");
        System.out.println("PERF006_B2C3B2_DEFAULT_INVOCATION_SPREAD_LOWERING=PASS");
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
