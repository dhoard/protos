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
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.guillermomolina.protos.parser.ProtosParser;
import com.guillermomolina.protos.runtime.ProtosActivation;
import com.guillermomolina.protos.runtime.ProtosNullValue;
import com.guillermomolina.protos.runtime.ProtosObjectValue;
import com.guillermomolina.protos.runtime.ProtosPrelude;
import com.guillermomolina.protos.semantic.Canonicalizer;
import com.guillermomolina.protos.semantic.ast.CanonicalSequence;
import com.oracle.truffle.api.Assumption;
import com.oracle.truffle.api.TruffleLanguage.LanguageReference;
import com.oracle.truffle.api.bytecode.BytecodeConfig;
import com.oracle.truffle.api.bytecode.BytecodeLocal;
import com.oracle.truffle.api.bytecode.BytecodeRootNodes;
import com.oracle.truffle.api.bytecode.ContinuationResult;
import com.oracle.truffle.api.source.Source;
import java.lang.reflect.Field;
import java.util.Map;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;

/**
 * PERF025-D179-A: a statically Resolved current lexical read may speculate
 * that its established binding remains PRESENT until the first successful
 * D179 structural removal of that exact static binding.
 *
 * <p>The speculation is definition/name scoped, not activation scoped:
 * invocations of the same lowered root share the token. Removal invalidates
 * it one-way before the physical local becomes ABSENT; recreation deliberately
 * does not renew it. Once invalidated, ReadFrameLocal returns to its exact
 * presence-aware D179 C0 path.
 */
final class ProtosPerf025D179LexicalMembershipStabilityReadTest {
    private static final LanguageReference<ProtosLanguage> LANGUAGE_REF =
            LanguageReference.create(ProtosLanguage.class);

    @Test
    void removalInvalidatesOnlyExactNameAndRecreationNeverRenewsToken() {
        try (Context context = Context.newBuilder(ProtosLanguage.ID).build()) {
            context.initialize(ProtosLanguage.ID);
            context.enter();
            try {
                Fixture fixture = yieldingReadRoot(LANGUAGE_REF.get(null));

                Assumption xContinuity =
                        fixture.layout().presentContinuityAt(0);
                Assumption yContinuity =
                        fixture.layout().presentContinuityAt(1);

                assertSame(
                        xContinuity,
                        fixture.layout().presentContinuityAt(0),
                        "one lowered definition/name must expose one stable token");
                assertSame(
                        yContinuity,
                        fixture.layout().presentContinuityAt(1),
                        "one lowered definition/name must expose one stable token");
                assertNotSame(
                        xContinuity,
                        yContinuity,
                        "different names must not share membership invalidation");
                assertTrue(xContinuity.isValid());
                assertTrue(yContinuity.isValid());

                /*
                 * Two activations of the same root deliberately share the
                 * definition/name assumptions while retaining independent
                 * semantic binding presence and values in their own frames.
                 */
                ProtosActivation firstModule = moduleActivation();
                ProtosActivation secondModule = moduleActivation();

                ContinuationResult firstContinuation =
                        assertInstanceOf(
                                ContinuationResult.class,
                                fixture.root().getCallTarget().call(firstModule));
                ContinuationResult secondContinuation =
                        assertInstanceOf(
                                ContinuationResult.class,
                                fixture.root().getCallTarget().call(secondModule));

                ProtosObjectValue firstContext = firstModule.context();
                ProtosObjectValue secondContext = secondModule.context();

                ProtosObjectValue firstX =
                        new ProtosObjectValue(ProtosObjectValue.rootObject());
                ProtosObjectValue secondX =
                        new ProtosObjectValue(ProtosObjectValue.rootObject());

                firstContext.createLocalSlot("x", firstX);
                firstContext.createLocalSlot("y", ProtosNullValue.INSTANCE);
                secondContext.createLocalSlot("x", secondX);

                /*
                 * Initial establishment and ordinary PRESENT -> PRESENT
                 * assignment do not alter structural membership continuity.
                 */
                assertTrue(xContinuity.isValid());
                assertTrue(yContinuity.isValid());

                ProtosObjectValue secondAssignedX =
                        new ProtosObjectValue(ProtosObjectValue.rootObject());
                secondContext.assignLocalSlot("x", secondAssignedX);

                assertTrue(
                        xContinuity.isValid(),
                        "ordinary assignment must not invalidate membership");
                assertTrue(yContinuity.isValid());

                /*
                 * Removing x from only one activation invalidates the shared
                 * root/x speculation, but must not invalidate root/y.
                 */
                assertSame(firstX, firstContext.removeLocalSlot("x"));
                assertFalse(
                        xContinuity.isValid(),
                        "first successful PRESENT -> ABSENT must invalidate x");
                assertTrue(
                        yContinuity.isValid(),
                        "removing x must not invalidate unrelated y");

                /*
                 * D179 C0 permits recreation while OPEN. The static physical
                 * ordinal is reused, but the one-way speculation stays dead.
                 */
                ProtosObjectValue recreatedX =
                        new ProtosObjectValue(ProtosObjectValue.rootObject());
                firstContext.createLocalSlot("x", recreatedX);

                assertFalse(
                        xContinuity.isValid(),
                        "legal ABSENT -> PRESENT recreation must not renew x");
                assertTrue(yContinuity.isValid());

                /*
                 * The second activation never lost x. The invalid token makes
                 * its direct read use the exact presence-aware path, which
                 * must still observe its own independent assigned value.
                 */
                assertSame(
                        secondAssignedX,
                        secondContinuation.continueWith(
                                ProtosNullValue.INSTANCE));

                /*
                 * The first activation did lose and recreate x. Its resumed
                 * read must observe the recreated value, proving that the
                 * invalid assumption does not freeze stale presence or value.
                 */
                assertSame(
                        recreatedX,
                        firstContinuation.continueWith(
                                ProtosNullValue.INSTANCE));

                /*
                 * PRESENT(null) remains PRESENT: semantic Protos null is a
                 * genuine stored value, and removing it performs the normal
                 * PRESENT -> ABSENT invalidation for y.
                 */
                assertSame(
                        ProtosNullValue.INSTANCE,
                        firstContext.readLocalSlot("y").orElseThrow());
                assertSame(
                        ProtosNullValue.INSTANCE,
                        firstContext.removeLocalSlot("y"));
                assertFalse(yContinuity.isValid());
            } finally {
                context.leave();
            }
        }
    }

    @Test
    void loweredRootReusesExactMembershipLayoutAcrossBytecodeReparse()
            throws Exception {
        try (Context context = Context.newBuilder(ProtosLanguage.ID).build()) {
            context.initialize(ProtosLanguage.ID);
            context.enter();
            try {
                ProtosLanguage language = LANGUAGE_REF.get(null);
                String characters = "x: null\ncontext\nx";
                Source source =
                        Source.newBuilder(
                                        ProtosLanguage.ID,
                                        characters,
                                        "perf025-d179-reparse.protos")
                                .build();

                CanonicalToBytecodeLowerer lowerer =
                        new CanonicalToBytecodeLowerer(language, source, ProtosExecutionMode.NORMAL);
                ProtosSemanticBytecodeRootNode root =
                        lowerer.lowerRoot(canonicalize(characters));

                ProtosFrameLexicalLayout before =
                        onlyRetainedFrameLayout(lowerer);
                Assumption xContinuity =
                        before.presentContinuityAt(0);

                /*
                 * Execute before the reparse so the genuine execution Context
                 * retains a ProtosFrameLexicalBindingAuthority carrying the
                 * original parse's layout.
                 */
                ProtosActivation module = moduleActivation();
                root.getCallTarget().call(module);

                assertTrue(xContinuity.isValid());

                /*
                 * BytecodeRootNodes may replay the retained parser here to
                 * materialize source/instrumentation metadata. The logical
                 * root survives; PERF025-D179-A therefore requires its layout
                 * and root/name token identity to survive as well.
                 */
                root.getRootNodes().ensureComplete();

                ProtosFrameLexicalLayout after =
                        onlyRetainedFrameLayout(lowerer);
                assertSame(
                        before,
                        after,
                        "Bytecode reparse must reuse the exact logical root layout");
                assertSame(
                        xContinuity,
                        after.presentContinuityAt(0),
                        "Bytecode reparse must never renew a membership token");

                /*
                 * This removal is performed through the authority created
                 * before ensureComplete(). It must invalidate the very token
                 * retained by the reparsed lowering state.
                 */
                assertSame(
                        ProtosNullValue.INSTANCE,
                        module.context().removeLocalSlot("x"));
                assertFalse(
                        after.presentContinuityAt(0).isValid(),
                        "pre-reparse authority must invalidate post-reparse token identity");
            } finally {
                context.leave();
            }
        }
    }

    @Test
    void independentLayoutsNeverSharePresenceContinuityTokens() {
        ProtosFrameLexicalLayout first =
                ProtosFrameLexicalLayout.of(new String[] {"x"}, new int[] {0});
        ProtosFrameLexicalLayout second =
                ProtosFrameLexicalLayout.of(new String[] {"x"}, new int[] {0});

        assertNotSame(
                first.presentContinuityAt(0),
                second.presentContinuityAt(0),
                "distinct lowered layouts must own distinct speculation tokens");
    }

    private static Fixture yieldingReadRoot(ProtosLanguage language) {
        /* The layout derives its local offsets from the first parse's locals. */
        ProtosFrameLexicalLayout[] layoutHolder = new ProtosFrameLexicalLayout[1];

        BytecodeRootNodes<ProtosSemanticBytecodeRootNode> roots =
                ProtosSemanticBytecodeRootNodeGen.create(
                        language,
                        BytecodeConfig.DEFAULT,
                        builder -> {
                            builder.beginRoot();

                            BytecodeLocal x =
                                    builder.createLocal("x", null);
                            BytecodeLocal y =
                                    builder.createLocal("y", null);
                            BytecodeLocal[] locals = {x, y};
                            if (layoutHolder[0] == null) {
                                layoutHolder[0] =
                                        ProtosFrameLexicalLayout.of(
                                                new String[] {"x", "y"},
                                                ProtosFrameLexicalLayout.localOffsetsOf(locals));
                            }
                            ProtosFrameLexicalLayout layout = layoutHolder[0];

                            builder.beginInstallFrameLexicalAuthority(locals, layout);
                            builder.emitLoadArgument(0);
                            builder.endInstallFrameLexicalAuthority();

                            /*
                             * Suspend after authority installation so the test
                             * can perform D179 mutations through the escaped
                             * genuine execution Context before the direct read.
                             */
                            builder.beginYield();
                            builder.emitLoadConstant(
                                    ProtosNullValue.INSTANCE);
                            builder.endYield();

                            builder.beginReturn();
                            builder.beginReadFrameLocal(
                                    x,
                                    layout.presentContinuityAt(0));
                            builder.emitLoadArgument(0);
                            builder.emitLoadConstant("x");
                            builder.endReadFrameLocal();
                            builder.endReturn();

                            builder.endRoot();
                        });

        return new Fixture(roots.getNode(0), layoutHolder[0]);
    }

    private static CanonicalSequence canonicalize(String characters) {
        return (CanonicalSequence)
                new Canonicalizer()
                        .canonicalize(
                                new ProtosParser(characters)
                                        .parseProgram());
    }

    private static ProtosFrameLexicalLayout onlyRetainedFrameLayout(
            CanonicalToBytecodeLowerer lowerer)
            throws ReflectiveOperationException {
        Field field =
                CanonicalToBytecodeLowerer.class
                        .getDeclaredField("frameLayoutsByScope");
        field.setAccessible(true);

        Map<?, ?> layouts =
                (Map<?, ?>) field.get(lowerer);
        if (layouts.size() != 1) {
            throw new AssertionError(
                    "expected exactly one retained frame layout, got "
                            + layouts.size());
        }

        return (ProtosFrameLexicalLayout)
                layouts.values().iterator().next();
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

        return new ProtosPrelude(
                        bindings,
                        contextPrototype)
                .newModuleActivation();
    }

    private record Fixture(
            ProtosSemanticBytecodeRootNode root,
            ProtosFrameLexicalLayout layout) {}
}
