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
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.guillermomolina.protos.parser.ProtosParser;
import com.guillermomolina.protos.runtime.ProtosActivation;
import com.guillermomolina.protos.runtime.ProtosClosureValue;
import com.guillermomolina.protos.runtime.ProtosIntegerValue;
import com.guillermomolina.protos.runtime.ProtosNullValue;
import com.guillermomolina.protos.runtime.ProtosObjectValue;
import com.guillermomolina.protos.runtime.ProtosPrelude;
import com.guillermomolina.protos.semantic.Canonicalizer;
import com.guillermomolina.protos.semantic.ast.CanonicalSequence;
import com.oracle.truffle.api.TruffleLanguage.LanguageReference;
import com.oracle.truffle.api.bytecode.BytecodeTier;
import com.oracle.truffle.api.source.Source;

import java.util.List;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;

/**
 * BUG018-C: a proven captured-materialized read of a binding PRESENT in a
 * retained owner frame must return that frame's exact value even after
 * another activation of the same owner root moved the root from the uncached
 * to the cached tier without ever writing the local. The cached node's
 * local-kind metadata for that local is then still unset while the retained
 * frame's physical tag is PRESENT; the read must not consult that metadata.
 *
 * <p>Shape: activation A1 of {@code f} runs uncached, establishes {@code x},
 * and returns the escaped Closure {@code r1} capturing A1's {@code x}.
 * Activation A2 of the same root enters cached and, before establishing its
 * own {@code x}, calls {@code probe}, which invokes {@code r1}. The transition
 * is made deterministic with {@code setUncachedThreshold(1)}: the first entry
 * consumes the count, the next entry starts on the cached node. {@code f}'s
 * body is straight-line, so A2's entry is its only transition point.
 */
final class ProtosBug018CapturedMaterializedReadAfterOwnerTierTransitionTest {
    private static final LanguageReference<ProtosLanguage> LANGUAGE_REF =
            LanguageReference.create(ProtosLanguage.class);

    private static final String PROGRAM =
            "observed: null\n"
                    + "noop: () => null\n"
                    + "probe: () => { observed = r1() }\n"
                    + "f: (g) => {\n"
                    + "  g()\n"
                    + "  x: seed\n"
                    + "  () => x\n"
                    + "}\n"
                    + "null";

    @Test
    void escapedCapturedReadOfPresentLocalSurvivesOwnerTransitionInAnotherActivation()
            throws Exception {
        withEnteredLanguage(
                language -> {
                    ProtosObjectValue seed = new ProtosObjectValue(ProtosObjectValue.rootObject());
                    assertSame(seed, observedAfterOwnerTransition(language, seed));
                });
    }

    @Test
    void escapedCapturedReadOfPresentNullSurvivesOwnerTransitionInAnotherActivation()
            throws Exception {
        withEnteredLanguage(
                language ->
                        assertSame(
                                ProtosNullValue.INSTANCE,
                                observedAfterOwnerTransition(
                                        language, ProtosNullValue.INSTANCE),
                                "PRESENT(null) must not be mistaken for ABSENT"));
    }

    @Test
    void escapedCapturedReadOfPresentIntegerSurvivesOwnerTransitionInAnotherActivation()
            throws Exception {
        withEnteredLanguage(
                language -> {
                    Object observed =
                            observedAfterOwnerTransition(
                                    language, new ProtosIntegerValue(42));
                    assertEquals(String.valueOf("42"), com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerDecimalText(observed));
                });
    }

    /**
     * Runs the A1/A2 scenario with {@code x} initialized to {@code seed} and
     * returns what {@code r1} produced inside A2.
     */
    private static Object observedAfterOwnerTransition(ProtosLanguage language, Object seed) {
        ProtosActivation module = moduleActivation();
        module.context().createLocalSlot("seed", seed);

        Source source =
                Source.newBuilder(ProtosLanguage.ID, PROGRAM, "bug018-captured-materialized.protos")
                        .build();
        new CanonicalToBytecodeLowerer(language, source, ProtosExecutionMode.NORMAL)
                .lowerRoot(canonicalize(PROGRAM))
                .getCallTarget()
                .call(module);

        ProtosClosureValue f = closureBinding(module, "f");
        ProtosClosureExecutionPlan ownerPlan = f.executionPlan().orElseThrow();
        ProtosSemanticBytecodeRootNode ownerRoot = ownerPlan.bytecodeActivationRootForTesting();

        ownerRoot.getBytecodeNode().setUncachedThreshold(1);
        assertEquals(BytecodeTier.UNCACHED, ownerRoot.getBytecodeNode().getTier());

        // A1: uncached; establishes x and lets r1 escape with A1's retained frame.
        ProtosClosureValue r1 =
                assertInstanceOf(
                        ProtosClosureValue.class,
                        ownerPlan.executeBytecodeActivationForTesting(
                                invocationOf(module, f, closureBinding(module, "noop"))));
        assertEquals(
                BytecodeTier.UNCACHED,
                ownerRoot.getBytecodeNode().getTier(),
                "A1 and its retained frame belong to the uncached tier");
        module.context().createLocalSlot("r1", r1);

        // A2: the same owner root enters cached and calls r1 before its own x exists.
        ownerPlan.executeBytecodeActivationForTesting(
                invocationOf(module, f, closureBinding(module, "probe")));
        assertEquals(
                BytecodeTier.CACHED,
                ownerRoot.getBytecodeNode().getTier(),
                "A2 must have moved the owner root to the cached tier");

        Object observed = module.context().readLocalSlot("observed").orElseThrow();

        // The read exercised the proven materialized fast path, not the generic lookup.
        List<String> readerInstructions =
                r1.executionPlan()
                        .orElseThrow()
                        .bytecodeActivationRootForTesting()
                        .getBytecodeNode()
                        .getInstructionsAsList()
                        .stream()
                        .map(com.oracle.truffle.api.bytecode.Instruction::getName)
                        .toList();
        assertTrue(
                readerInstructions.stream()
                        .anyMatch(name -> name.contains("SelectCapturedMaterializedOwnerFrame")),
                () -> "captured read did not take the materialized fast path: " + readerInstructions);
        assertTrue(
                readerInstructions.stream().anyMatch(name -> name.startsWith("load.local.mat")),
                () -> "captured read did not load through LoadLocalMaterialized: " + readerInstructions);

        return observed;
    }

    private interface LanguageBody {
        void run(ProtosLanguage language) throws Exception;
    }

    private static void withEnteredLanguage(LanguageBody body) throws Exception {
        try (Context context = Context.newBuilder(ProtosLanguage.ID).build()) {
            context.initialize(ProtosLanguage.ID);
            context.enter();
            try {
                body.run(LANGUAGE_REF.get(null));
            } finally {
                context.leave();
            }
        }
    }

    private static ProtosClosureValue closureBinding(ProtosActivation module, String name) {
        return assertInstanceOf(
                ProtosClosureValue.class, module.context().readLocalSlot(name).orElseThrow());
    }

    private static ProtosActivation invocationOf(
            ProtosActivation module, ProtosClosureValue closure, Object argument) {
        return ProtosActivation.forClosureInvocation(
                closure,
                List.of(argument),
                module.prelude().orElseThrow(),
                module.actorModuleState(),
                module.currentModuleKey().orElse(null),
                module.executionDomain());
    }

    private static CanonicalSequence canonicalize(String characters) {
        return (CanonicalSequence)
                new Canonicalizer().canonicalize(new ProtosParser(characters).parseProgram());
    }

    private static ProtosActivation moduleActivation() {
        ProtosStandardObjectProtocol.install();

        ProtosObjectValue contextPrototype = new ProtosObjectValue(ProtosObjectValue.rootObject());
        ProtosObjectValue bindings = new ProtosObjectValue(contextPrototype);
        ProtosObjectValue errorPrototype = new ProtosObjectValue(ProtosObjectValue.rootObject());
        bindings.createLocalSlot("Context", contextPrototype);
        bindings.createLocalSlot("Error", errorPrototype);
        bindings.createLocalSlot("SlotNotFound", new ProtosObjectValue(errorPrototype));
        bindings.createLocalSlot("Array", new ProtosObjectValue(ProtosObjectValue.rootObject()));
        bindings.freeze();

        return new ProtosPrelude(bindings, contextPrototype).newModuleActivation();
    }
}
