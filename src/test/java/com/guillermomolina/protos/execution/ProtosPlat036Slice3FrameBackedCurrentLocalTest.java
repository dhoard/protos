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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.guillermomolina.protos.parser.ProtosParser;
import com.guillermomolina.protos.runtime.ProtosActivation;
import com.guillermomolina.protos.runtime.ProtosExecutionContextValue;
import com.guillermomolina.protos.runtime.ProtosNullValue;
import com.guillermomolina.protos.runtime.ProtosObjectValue;
import com.guillermomolina.protos.runtime.ProtosPrelude;
import com.guillermomolina.protos.semantic.Canonicalizer;
import com.guillermomolina.protos.semantic.ast.CanonicalSequence;
import com.oracle.truffle.api.TruffleLanguage.LanguageReference;
import com.oracle.truffle.api.source.Source;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;

/**
 * I068 Slice 3 ("definitely-current local lowering"): focused evidence that a
 * {@code Resolved} current-scope binding is backed by exactly one Truffle
 * Bytecode DSL frame-local authority end to end through the real
 * parse/canonicalize/lower/execute pipeline, that {@code Candidate}/{@code
 * Dynamic} references and {@code OBJECT_BODY} slots remain on the unchanged
 * generic path, and that D179/presence/mutation-state invariants still hold
 * once bindings are frame-backed.
 */
final class ProtosPlat036Slice3FrameBackedCurrentLocalTest {
    private static final LanguageReference<ProtosLanguage> LANGUAGE_REF =
            LanguageReference.create(ProtosLanguage.class);

    @Test
    void bareCreateAndReadOfACurrentBindingRoundTripsThroughTheFrameBackedAuthority() throws Exception {
        withEnteredLanguage(
                (language, module) -> {
                    Object result = run(language, module, "x: 41\nx");
                    assertEquals(41L, unwrapNumber(result));

                    // Reflection through the same execution context must observe the
                    // exact value the direct-local path produced: one authority, not a
                    // second map-backed copy left behind by the frame-backed store.
                    ProtosExecutionContextValue context =
                            (ProtosExecutionContextValue) module.context();
                    assertTrue(context.hasLocalSlot("x"));
                    assertEquals(41L, unwrapNumber(context.readLocalSlot("x").orElseThrow()));
                });
    }

    @Test
    void bareAssignmentOfACurrentBindingWritesTheSameAuthoritativeFrameBackedValue() throws Exception {
        withEnteredLanguage(
                (language, module) -> {
                    Object result = run(language, module, "x: 1\nx = 2\nx");
                    assertEquals(2L, unwrapNumber(result));
                });
    }

    @Test
    void presentNullRemainsDistinctFromAbsentForAFrameBackedBinding() throws Exception {
        withEnteredLanguage(
                (language, module) -> {
                    run(language, module, "x: null");

                    ProtosExecutionContextValue context =
                            (ProtosExecutionContextValue) module.context();
                    assertTrue(context.hasLocalSlot("x"));
                    assertSame(ProtosNullValue.INSTANCE, context.readLocalSlot("x").orElseThrow());
                    assertFalse(context.hasLocalSlot("neverCreated"));
                });
    }

    @Test
    void candidateReferenceToANearerNotYetPresentBindingStaysOnTheDynamicPath() throws Exception {
        // The closure body statically declares its own "x" (via the later
        // `x: 2`), so the read of "x" before that point is a nearer-scope,
        // not-yet-established reference: Candidate at depth 0, never Resolved,
        // even though a lexically outer "x" (the module's) is already PRESENT.
        // The existing exact runtime presence/topology behavior must still
        // apply: the not-yet-established nearer "x" is skipped and the captured
        // outer "x" answers, never a premature direct load of the nearer,
        // not-yet-PRESENT identity.
        withEnteredLanguage(
                (language, module) -> {
                    Object result =
                            run(
                                    language,
                                    module,
                                    "x: 99\n"
                                            + "inner: () => { before: x\n"
                                            + "x: 1\n"
                                            + "before }\n"
                                            + "inner()");
                    assertEquals(99L, unwrapNumber(result));
                });
    }

    @Test
    void dynamicReferenceWithNoStaticDeclarationStaysOnTheStringKeyedFallback() throws Exception {
        // "Context" is never locally declared anywhere in this canonical unit
        // (it lives only on the module's receiver bindings), so it is
        // classified Dynamic and must keep resolving through the exact
        // existing receiver/member-fallback path, unaffected by this slice.
        withEnteredLanguage(
                (language, module) -> {
                    Object result = run(language, module, "Context");
                    assertTrue(result instanceof ProtosObjectValue);
                });
    }

    @Test
    void objectBodyBindingIsNotFrameLoweredAndRemainsOrdinaryObjectState() throws Exception {
        withEnteredLanguage(
                (language, module) -> {
                    Object result = run(language, module, "(Context { y: 5 }).y");
                    assertEquals(5L, unwrapNumber(result));
                });
    }

    @Test
    void mutationStateChecksStillApplyToDirectlyLoweredCurrentBindings() throws Exception {
        // A genuine execution context is only ever entered by exactly one
        // lowered root in real execution (module init, or one Closure
        // invocation); this test honors that shape by lowering and running
        // exactly one root and then exercising CLOSED/FROZEN through the
        // ordinary generic (non-lowered) ProtosObjectValue API against the
        // same, now frame-backed, context — the same seam
        // ProtosLexicalBindingAuthoritySeamTest exercises for the map-backed
        // authority in Slice 2.
        withEnteredLanguage(
                (language, module) -> {
                    run(language, module, "x: 1");
                    ProtosExecutionContextValue context = (ProtosExecutionContextValue) module.context();
                    assertEquals(1L, unwrapNumber(context.readLocalSlot("x").orElseThrow()));

                    context.close();
                    org.junit.jupiter.api.Assertions.assertThrows(
                            IllegalStateException.class, () -> context.createLocalSlot("y", 2L));

                    // Assignment to the already-present frame-backed binding remains
                    // allowed while CLOSED (D179/mutation-state parity with the
                    // pre-Slice-3 map-backed path).
                    context.assignLocalSlot("x", 3L);
                    assertEquals(3L, unwrapNumber(context.readLocalSlot("x").orElseThrow()));

                    context.freeze();
                    org.junit.jupiter.api.Assertions.assertThrows(
                            IllegalStateException.class, () -> context.assignLocalSlot("x", 4L));
                });
    }

    @Test
    void escapedExecutionContextStillObservesItsFrameBackedBindingAfterItsOwnActivationReturns()
            throws Exception {
        withEnteredLanguage(
                (language, module) -> {
                    run(language, module, "x: 123");
                    ProtosExecutionContextValue escaped = (ProtosExecutionContextValue) module.context();

                    // The activation that created "x" has already returned by this point;
                    // the context object itself is the escape, and its frame-backed
                    // binding must still answer correctly through ordinary reflection.
                    assertEquals(123L, unwrapNumber(escaped.readLocalSlot("x").orElseThrow()));
                });
    }

    private interface ModuleTest {
        void run(ProtosLanguage language, ProtosActivation module) throws Exception;
    }

    private static void withEnteredLanguage(ModuleTest test) throws Exception {
        try (Context context = Context.newBuilder(ProtosLanguage.ID).build()) {
            context.initialize(ProtosLanguage.ID);
            context.enter();
            try {
                ProtosLanguage language = LANGUAGE_REF.get(null);
                test.run(language, moduleActivation());
            } finally {
                context.leave();
            }
        }
    }

    private static Object run(ProtosLanguage language, ProtosActivation module, String characters) {
        Source source =
                Source.newBuilder(ProtosLanguage.ID, characters, "plat036-slice3.protos").build();
        ProtosSemanticBytecodeRootNode root = new CanonicalToBytecodeLowerer(language, source, ProtosExecutionMode.NORMAL).lowerRoot(canonicalize(characters));
        return root.getCallTarget().call(module);
    }

    private static long unwrapNumber(Object value) {
        if (value instanceof Long longValue) {
            return longValue;
        }
        if (value instanceof com.guillermomolina.protos.runtime.ProtosIntegerValue integerValue) {
            return integerValue.longValue();
        }
        throw new AssertionError("expected a Protos integer literal result, got: " + value);
    }

    private static CanonicalSequence canonicalize(String characters) {
        return (CanonicalSequence)
                new Canonicalizer().canonicalize(new ProtosParser(characters).parseProgram());
    }

    private static ProtosActivation moduleActivation() {
        ProtosStandardObjectProtocol.install();

        ProtosObjectValue contextPrototype = new ProtosObjectValue(ProtosObjectValue.rootObject());
        ProtosObjectValue bindings = new ProtosObjectValue(contextPrototype);
        bindings.createLocalSlot("Context", contextPrototype);
        bindings.createLocalSlot("Error", new ProtosObjectValue(ProtosObjectValue.rootObject()));
        bindings.createLocalSlot(
                "Array",
                new ProtosObjectValue(ProtosObjectValue.rootObject()));
        bindings.freeze();
        return new ProtosPrelude(bindings, contextPrototype).newModuleActivation();
    }
}
