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
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.guillermomolina.protos.parser.ProtosParser;
import com.guillermomolina.protos.runtime.ProtosActivation;
import com.guillermomolina.protos.runtime.ProtosLexicalFallback;
import com.guillermomolina.protos.runtime.ProtosArrayValue;
import com.guillermomolina.protos.runtime.ProtosClosureValue;
import com.guillermomolina.protos.runtime.ProtosObjectValue;
import com.guillermomolina.protos.runtime.ProtosPrelude;
import com.guillermomolina.protos.runtime.ProtosSignalException;
import com.guillermomolina.protos.semantic.Canonicalizer;
import com.guillermomolina.protos.semantic.ast.CanonicalClosure;
import com.guillermomolina.protos.semantic.ast.CanonicalSequence;
import com.oracle.truffle.api.TruffleLanguage.LanguageReference;
import com.oracle.truffle.api.source.Source;
import java.util.List;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;

final class ProtosPerf006B2C3ARestBindingTest {
    private static final LanguageReference<ProtosLanguage> LANGUAGE_REF =
            LanguageReference.create(ProtosLanguage.class);

    @Test
    void restCapturesOnlyUnconsumedSuppliedSuffixAsFreshFrozenArray()
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

                String characters = "(head, ...tail) => { tail }";
                Source source =
                        Source.newBuilder(
                                        ProtosLanguage.ID,
                                        characters,
                                        "perf006-b2c3a-direct-rest.protos")
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
                                List.of(first, second, third),
                                module.prelude().orElseThrow(),
                                module.actorModuleState(),
                                module.currentModuleKey().orElse(null),
                                module.executionDomain());
                ProtosArrayValue supplied =
                        invocation.arguments().orElseThrow();

                ProtosBytecodeClosureExecutionPlan plan =
                        new ProtosBytecodeClosureExecutionPlan(
                                definition,
                                language,
                                source, ProtosExecutionMode.NORMAL);
                plan.bind(invocation);

                assertSame(first, ProtosLexicalFallback.readByName(invocation, "head").orElseThrow());
                ProtosArrayValue rest =
                        assertInstanceOf(
                                ProtosArrayValue.class,
                                ProtosLexicalFallback.readByName(invocation, "tail").orElseThrow());
                assertNotSame(supplied, rest);
                assertSame(
                        ProtosObjectValue.MutationState.FROZEN,
                        rest.mutationState());
                assertEquals(2, rest.indexedSnapshot().size());
                assertSame(second, rest.indexedSnapshot().get(0));
                assertSame(third, rest.indexedSnapshot().get(1));

                assertEquals(3, supplied.indexedSnapshot().size());
                assertSame(first, supplied.indexedSnapshot().get(0));
                assertSame(second, supplied.indexedSnapshot().get(1));
                assertSame(third, supplied.indexedSnapshot().get(2));
            } finally {
                context.leave();
            }
        }

        System.out.println("PERF006_B2C3A_REST_SUFFIX_CAPTURE=PASS");
        System.out.println("PERF006_B2C3A_REST_ARRAY_FROZEN=PASS");
        System.out.println("PERF006_B2C3A_ARGS_VECTOR_UNCHANGED=PASS");
    }

    @Test
    void sourceLevelRestBindingSupportsNonEmptyAndEmptySuffix()
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

                String closureCharacters = "(head, ...tail) => { tail }";
                Source closureSource =
                        Source.newBuilder(
                                        ProtosLanguage.ID,
                                        closureCharacters,
                                        "perf006-b2c3a-source-rest.protos")
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

                Object nonEmpty =
                        lowerCall(
                                        language,
                                        "entry(first, second, third)",
                                        "perf006-b2c3a-rest-nonempty.protos")
                                .getCallTarget()
                                .call(module);
                ProtosArrayValue nonEmptyRest =
                        assertInstanceOf(ProtosArrayValue.class, nonEmpty);
                assertSame(
                        ProtosObjectValue.MutationState.FROZEN,
                        nonEmptyRest.mutationState());
                assertEquals(2, nonEmptyRest.indexedSnapshot().size());
                assertSame(second, nonEmptyRest.indexedSnapshot().get(0));
                assertSame(third, nonEmptyRest.indexedSnapshot().get(1));

                Object empty =
                        lowerCall(
                                        language,
                                        "entry(first)",
                                        "perf006-b2c3a-rest-empty.protos")
                                .getCallTarget()
                                .call(module);
                ProtosArrayValue emptyRest =
                        assertInstanceOf(ProtosArrayValue.class, empty);
                assertSame(
                        ProtosObjectValue.MutationState.FROZEN,
                        emptyRest.mutationState());
                assertEquals(0, emptyRest.indexedSnapshot().size());
            } finally {
                context.leave();
            }
        }

        System.out.println("PERF006_B2C3A_SOURCE_REST_BINDING=PASS");
        System.out.println("PERF006_B2C3A_EMPTY_REST_CAPTURE=PASS");
    }

    @Test
    void requiredPrefixStillRejectsTooFewArgumentsAndSpreadDefaultLowers()
            throws Exception {
        try (Context context = Context.newBuilder(ProtosLanguage.ID).build()) {
            context.initialize(ProtosLanguage.ID);
            context.enter();
            try {
                ProtosLanguage language = LANGUAGE_REF.get(null);
                ProtosActivation module = moduleActivation();

                String restCharacters = "(head, ...tail) => { tail }";
                Source restSource =
                        Source.newBuilder(
                                        ProtosLanguage.ID,
                                        restCharacters,
                                        "perf006-b2c3a-required-prefix.protos")
                                .build();
                CanonicalClosure restDefinition =
                        closureDefinition(restCharacters);
                module.context().createLocalSlot(
                        "entry",
                        semanticClosure(
                                restDefinition,
                                ProtosClosureExecutionPlan.bytecode(
                                        restDefinition,
                                        language,
                                        restSource, ProtosExecutionMode.NORMAL),
                                module));

                ProtosSemanticBytecodeRootNode missingRequired =
                        lowerCall(
                                language,
                                "entry()",
                                "perf006-b2c3a-missing-required.protos");
                assertThrows(
                        ProtosSignalException.class,
                        () -> missingRequired.getCallTarget().call(module));

                String defaultCharacters = "(head, fallback = child(...items)) => { fallback }";
                Source defaultSource =
                        Source.newBuilder(
                                        ProtosLanguage.ID,
                                        defaultCharacters,
                                        "perf006-b2c3a-default-deferred.protos")
                                .build();
                CanonicalClosure defaultDefinition =
                        closureDefinition(defaultCharacters);

                new ProtosBytecodeClosureExecutionPlan(
                                                defaultDefinition,
                                                language,
                                                defaultSource, ProtosExecutionMode.NORMAL);
            } finally {
                context.leave();
            }
        }

        System.out.println("PERF006_B2C3A_REQUIRED_PREFIX_ARITY=PASS");
        System.out.println("PERF006_B2C3A_DEFAULT_INVOCATION_SPREAD_LOWERING=PASS");
    }

    private static ProtosSemanticBytecodeRootNode lowerCall(
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
        return new CanonicalToBytecodeLowerer(language, source, ProtosExecutionMode.NORMAL)
                .lowerRoot(canonicalize(characters));
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
