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

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.guillermomolina.protos.parser.ProtosParser;
import com.guillermomolina.protos.runtime.ProtosActivation;
import com.guillermomolina.protos.runtime.ProtosLexicalFallback;
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

final class ProtosPerf006B2C2GeneralPositionalArityTest {
    private static final LanguageReference<ProtosLanguage> LANGUAGE_REF =
            LanguageReference.create(ProtosLanguage.class);

    @Test
    void generalRequiredParameterBindingPreservesPositionalIdentity()
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
                ProtosObjectValue fourth =
                        new ProtosObjectValue(ProtosObjectValue.rootObject());

                String closureCharacters = "(a, b, c, d) => { c }";
                Source closureSource =
                        Source.newBuilder(
                                        ProtosLanguage.ID,
                                        closureCharacters,
                                        "perf006-b2c2-binding.protos")
                                .build();
                CanonicalClosure definition =
                        closureDefinition(closureCharacters);

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
                                List.of(first, second, third, fourth),
                                module.prelude().orElseThrow(),
                                module.actorModuleState(),
                                module.currentModuleKey().orElse(null),
                                module.executionDomain());

                ProtosBytecodeClosureExecutionPlan directPlan =
                        new ProtosBytecodeClosureExecutionPlan(
                                definition,
                                language,
                                closureSource, ProtosExecutionMode.NORMAL);
                directPlan.bind(invocation);

                assertSame(first, ProtosLexicalFallback.readByName(invocation, "a").orElseThrow());
                assertSame(second, ProtosLexicalFallback.readByName(invocation, "b").orElseThrow());
                assertSame(third, ProtosLexicalFallback.readByName(invocation, "c").orElseThrow());
                assertSame(fourth, ProtosLexicalFallback.readByName(invocation, "d").orElseThrow());

                module.context().createLocalSlot("first", first);
                module.context().createLocalSlot("second", second);
                module.context().createLocalSlot("third", third);
                module.context().createLocalSlot("fourth", fourth);

                ProtosClosureExecutionPlan sourcePlan =
                        ProtosClosureExecutionPlan.bytecode(
                                definition,
                                language,
                                closureSource, ProtosExecutionMode.NORMAL);
                ProtosClosureValue sourceClosure =
                        semanticClosure(definition, sourcePlan, module);
                module.context().createLocalSlot("entry", sourceClosure);

                String topCharacters =
                        "entry(first, second, third, fourth)";
                Source topSource =
                        Source.newBuilder(
                                        ProtosLanguage.ID,
                                        topCharacters,
                                        "perf006-b2c2-call.protos")
                                .build();
                ProtosSemanticBytecodeRootNode topRoot =
                        new CanonicalToBytecodeLowerer(language, topSource, ProtosExecutionMode.NORMAL)
                                .lowerRoot(canonicalize(topCharacters));

                assertSame(third, topRoot.getCallTarget().call(module));
            } finally {
                context.leave();
            }
        }

        System.out.println("PERF006_B2C2_GENERAL_POSITIONAL_BINDING=PASS");
        System.out.println("PERF006_B2C2_POSITIONAL_IDENTITY_ORDER=PASS");
        System.out.println("PERF006_B2C2_SOURCE_LEVEL_GENERAL_ARITY=PASS");
    }

    @Test
    void tooFewAndTooManyArgumentsRetainGuestArityError()
            throws Exception {
        try (Context context = Context.newBuilder(ProtosLanguage.ID).build()) {
            context.initialize(ProtosLanguage.ID);
            context.enter();
            try {
                ProtosLanguage language = LANGUAGE_REF.get(null);
                ProtosActivation module = moduleActivation();

                String closureCharacters = "(a, b, c) => { b }";
                Source closureSource =
                        Source.newBuilder(
                                        ProtosLanguage.ID,
                                        closureCharacters,
                                        "perf006-b2c2-arity-closure.protos")
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

                assertGuestArityError(
                        language,
                        module,
                        "entry(null, null)",
                        "perf006-b2c2-too-few.protos");
                assertGuestArityError(
                        language,
                        module,
                        "entry(null, null, null, null)",
                        "perf006-b2c2-too-many.protos");
            } finally {
                context.leave();
            }
        }

        System.out.println("PERF006_B2C2_TOO_FEW_ARITY_ERROR=PASS");
        System.out.println("PERF006_B2C2_TOO_MANY_ARITY_ERROR=PASS");
    }

    @Test
    void callSpreadInDefaultsLowersAfterMigration()
            throws Exception {
        try (Context context = Context.newBuilder(ProtosLanguage.ID).build()) {
            context.initialize(ProtosLanguage.ID);
            context.enter();
            try {
                ProtosLanguage language = LANGUAGE_REF.get(null);

                String defaultCharacters =
                        "(a, b = child(...items)) => { b }";
                CanonicalClosure defaultDefinition =
                        closureDefinition(defaultCharacters);
                Source defaultSource =
                        Source.newBuilder(
                                        ProtosLanguage.ID,
                                        defaultCharacters,
                                        "perf006-b2c2-default.protos")
                                .build();
                new ProtosBytecodeClosureExecutionPlan(
                                                defaultDefinition,
                                                language,
                                                defaultSource, ProtosExecutionMode.NORMAL);
            } finally {
                context.leave();
            }
        }

        System.out.println(
                "PERF006_B2C2_DEFAULT_INVOCATION_SPREAD_LOWERING=PASS");
    }

    private static void assertGuestArityError(
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
        ProtosSemanticBytecodeRootNode root =
                new CanonicalToBytecodeLowerer(language, source, ProtosExecutionMode.NORMAL)
                        .lowerRoot(canonicalize(characters));
        assertThrows(
                ProtosSignalException.class,
                () -> root.getCallTarget().call(module));
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
