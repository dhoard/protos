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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.guillermomolina.protos.runtime.ProtosActivation;
import com.guillermomolina.protos.runtime.ProtosClosureValue;
import com.guillermomolina.protos.runtime.ProtosCoreErrors;
import com.guillermomolina.protos.runtime.ProtosLexicalEnvironment;
import com.guillermomolina.protos.runtime.ProtosNullValue;
import com.guillermomolina.protos.runtime.ProtosObjectValue;
import com.guillermomolina.protos.runtime.ProtosPrelude;
import com.guillermomolina.protos.runtime.ProtosSignalException;
import com.oracle.truffle.api.Assumption;
import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.bytecode.BytecodeNode;
import com.oracle.truffle.api.bytecode.BytecodeTier;
import com.oracle.truffle.api.bytecode.Instruction;
import com.oracle.truffle.api.nodes.IndirectCallNode;
import com.oracle.truffle.api.source.Source;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.List;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

/**
 * PERF037-C root-level captured reads. The name and lexical depth are
 * constant operands, compact and materialized invocations are separate
 * specializations, and a compact read retains a nearer-scope absence proof
 * guarded by the nearer scopes' frame layouts and their lazily created
 * no-dynamic-binding tokens. These programs drive one captured read site
 * through the uncached and cached interpreters, from two activations of the
 * same defining root, and check that D179 C0 late creation, removal and
 * recreation, PRESENT(null) versus ABSENT, the deferred-to-materialized
 * Context transition, value changes and the generic fallback are unchanged.
 */
final class ProtosPerf037CCapturedLexicalReadTest {
    private static final Path CORE = Path.of("protos", "lib", "core");
    private static final int WARM_UP_CALLS = 40;

    @Test
    void slotReadWorkloadUsesConstantOperandRootSelection() throws Exception {
        withCore(module -> {
            ProtosClosureValue run =
                    closure("holder: { value: 1 }\nrun: () => { holder.value }\nrun", module);
            List<Instruction> selections =
                    instructionsOf(run, "SelectCapturedMaterializedOwnerFrameAtRoot");
            assertEquals(1, selections.size(), () -> "expected one root selection in " + names(run));
            assertEquals("holder", constantArgument(selections.get(0), "name"));
            assertTrue(
                    (Integer) constantArgument(selections.get(0), "lexicalDepth") >= 1,
                    "the proven lexical depth is an immediate constant operand");
            assertEquals(
                    "holder",
                    constantArgument(
                            single(instructionsOf(run, "ReadCapturedFallbackAtRoot")), "name"));

            for (int call = 0; call < WARM_UP_CALLS; call++) {
                assertEquals("1", integerValue(call(run, module)));
            }
            evaluate("holder.value = 5", module);
            assertEquals("5", integerValue(call(run, module)));
            evaluate("holder = { value: 6 }", module);
            assertEquals("6", integerValue(call(run, module)));
        });
        System.out.println("PERF037C_SLOT_READ_WORKLOAD=PASS");
    }

    /*
     * The reader's chain is [maker context, wrap context, module context]:
     * the proven owner is wrap's local holder (lexical depth 2), so the read
     * has one nearer scope for the absence proof, and the module's holder is
     * a farther lexical binding D179 C0 retargets to once the owner's is
     * removed.
     */
    private static final String NESTED_PROGRAM =
            "holder: { value: 1 }\n"
                    + "other: { value: 2 }\n"
                    + "observed: null\n"
                    + "noop: () => null\n"
                    + "probe: () => { observed = first() }\n"
                    + "wrap: (g) => {\n"
                    + "  g()\n"
                    + "  holder: { value: 10 }\n"
                    + "  () => { () => { holder.value } }\n"
                    + "}\n";

    @Test
    void nearerLateCreationOwnerRemovalAndRetargetingAcrossTiersAndActivations()
            throws Exception {
        withCore(module -> {
            evaluate(NESTED_PROGRAM, module);
            ProtosClosureValue wrap = closure("wrap", module);
            ProtosClosureExecutionPlan ownerPlan = wrap.executionPlan().orElseThrow();
            ProtosSemanticBytecodeRootNode ownerRoot = ownerPlan.bytecodeActivationRootForTesting();
            ownerRoot.getBytecodeNode().setUncachedThreshold(1);
            assertEquals(BytecodeTier.UNCACHED, ownerRoot.getBytecodeNode().getTier());

            // A1: the owner root runs uncached; its retained frame escapes.
            ProtosClosureValue firstMaker =
                    assertInstanceOf(
                            ProtosClosureValue.class,
                            ownerPlan.executeBytecodeActivationForTesting(
                                    invocationOf(module, wrap, closure("noop", module))));
            assertEquals(
                    BytecodeTier.UNCACHED,
                    ownerRoot.getBytecodeNode().getTier(),
                    "A1 and its retained frame belong to the uncached owner tier");
            ProtosClosureValue first =
                    assertInstanceOf(ProtosClosureValue.class, call(firstMaker, module));
            module.context().createLocalSlot("first", first);

            BytecodeNode readerNode = bytecodeNode(first);
            readerNode.setUncachedThreshold(4);
            assertEquals(BytecodeTier.UNCACHED, readerNode.getTier());

            /*
             * A2: the same owner root enters the cached tier and reads A1's
             * retained frame before A2's own holder exists (BUG018).
             */
            ProtosClosureValue secondMaker =
                    assertInstanceOf(
                            ProtosClosureValue.class,
                            ownerPlan.executeBytecodeActivationForTesting(
                                    invocationOf(module, wrap, closure("probe", module))));
            assertEquals(
                    BytecodeTier.CACHED,
                    ownerRoot.getBytecodeNode().getTier(),
                    "A2 must have moved the owner root to the cached tier");
            assertEquals("10", integerValue(evaluate("observed", module)));
            ProtosClosureValue second =
                    assertInstanceOf(ProtosClosureValue.class, call(secondMaker, module));
            assertSame(
                    bytecodeNode(first).getBytecodeRootNode(),
                    bytecodeNode(second).getBytecodeRootNode(),
                    "both readers are activations of one lowered root");
            assertTrue(
                    names(first).stream()
                            .anyMatch(name -> name.contains("SelectCapturedMaterializedOwnerFrameAtRoot")),
                    () -> "the read must take the root-level materialized selection: " + names(first));

            for (int call = 0; call < WARM_UP_CALLS; call++) {
                assertEquals("10", integerValue(call(first, module)));
                assertEquals("10", integerValue(call(second, module)));
            }
            assertEquals(
                    BytecodeTier.CACHED,
                    bytecodeNode(first).getTier(),
                    "the read site must also run in the cached interpreter");

            ProtosObjectValue firstOwner =
                    ProtosLexicalEnvironment.at(first.capturedLexicalEnvironmentForRuntime(), 1)
                            .context();
            evaluate("other.value = 3", module);
            firstOwner.assignLocalSlot("holder", evaluate("other", module));
            assertEquals("3", integerValue(call(first, module)), "the current owner value is read, not a specialized copy");
            assertEquals("10", integerValue(call(second, module)));

            /*
             * The nearer maker Context is materialized only now, through the
             * chain the reader shares by reference with its own activation.
             */
            ProtosObjectValue firstNearer =
                    first.capturedLexicalEnvironmentForRuntime().context();
            assertSame(
                    firstNearer,
                    first.capturedLexicalEnvironmentForRuntime().context(),
                    "materialization yields one Context identity");
            ProtosObjectValue nearerHolder = assertInstanceOf(
                    ProtosObjectValue.class, evaluate("{ value: 7 }", module));
            firstNearer.createLocalSlot("holder", nearerHolder);
            assertEquals("7", integerValue(call(first, module)), "a later nearer binding shadows the owner");
            assertEquals("10", integerValue(call(second, module)), "another activation of the same root keeps its own scopes");

            assertSame(nearerHolder, firstNearer.removeLocalSlot("holder"));
            assertEquals("3", integerValue(call(first, module)));

            // Removing the owner's binding retargets to the farther module binding.
            firstOwner.removeLocalSlot("holder");
            assertEquals("1", integerValue(call(first, module)), "D179 C0: an ABSENT owner reveals the farther lexical binding");
            assertEquals("10", integerValue(call(second, module)));

            // With the lexical chain exhausted and no receiver slot: SlotNotFound.
            Object moduleHolder = module.context().removeLocalSlot("holder");
            ProtosSignalException absent =
                    assertThrows(ProtosSignalException.class, () -> call(first, module));
            assertSame(
                    ProtosCoreErrors.prototype(module, ProtosCoreErrors.StandardError.SLOT_NOT_FOUND),
                    absent.error().parent().orElse(null),
                    "an exhausted bare lookup signals SlotNotFound");

            // Recreating the original owner binding makes it the selection again.
            firstOwner.createLocalSlot("holder", nearerHolder);
            assertEquals("7", integerValue(call(first, module)));
            module.context().createLocalSlot("holder", moduleHolder);
            assertEquals("7", integerValue(call(first, module)));
        });
        System.out.println("PERF037C_NEARER_LATE_CREATION=PASS");
        System.out.println("PERF037C_OWNER_REMOVAL_RETARGETING_AND_RECREATION=PASS");
        System.out.println("PERF037C_OWNER_TIER_TRANSITION=PASS");
        System.out.println("PERF037C_CACHED_AND_UNCACHED=PASS");
    }

    @Test
    void nearerPresentNullShadowsAndAbsenceSignals() throws Exception {
        withCore(module -> {
            evaluate(
                    "slot: 1\n"
                            + "makeSlot: () => { () => { slot } }\n"
                            + "reader: makeSlot()\n",
                    module);
            ProtosClosureValue reader = closure("reader", module);
            for (int call = 0; call < WARM_UP_CALLS; call++) {
                assertEquals("1", integerValue(call(reader, module)));
            }

            ProtosObjectValue nearer = reader.capturedLexicalEnvironmentForRuntime().context();
            nearer.createLocalSlot("slot", ProtosNullValue.INSTANCE);
            assertSame(ProtosNullValue.INSTANCE, call(reader, module));

            module.context().removeLocalSlot("slot");
            assertSame(
                    ProtosNullValue.INSTANCE,
                    call(reader, module),
                    "a nearer PRESENT(null) binding is not ABSENT");

            nearer.removeLocalSlot("slot");
            ProtosSignalException absent =
                    assertThrows(ProtosSignalException.class, () -> call(reader, module));
            assertSame(
                    ProtosCoreErrors.prototype(module, ProtosCoreErrors.StandardError.SLOT_NOT_FOUND),
                    absent.error().parent().orElse(null),
                    "ABSENT along the whole chain, with no receiver slot, is SlotNotFound");
        });
        System.out.println("PERF037C_PRESENT_NULL_VERSUS_ABSENT=PASS");
    }

    /*
     * PERF037-D: the root selection profiles the owner's representation and
     * its no-selection outcome per site. Two activations of one owner root
     * share the reader site, the owner Context materializes after warm-up,
     * and the owner binding is removed and recreated; each newly taken
     * branch must still select the current invocation's owner exactly.
     */
    @Test
    void ownerRepresentationProfilesStayExactAfterWarmUp() throws Exception {
        withCore(module -> {
            evaluate(
                    "makeSlot: (initial) => {\n"
                            + "  slot: initial\n"
                            + "  () => { slot }\n"
                            + "}\n"
                            + "readerA: makeSlot(1)\n"
                            + "readerB: makeSlot(2)\n",
                    module);
            ProtosClosureValue readerA = closure("readerA", module);
            ProtosClosureValue readerB = closure("readerB", module);
            assertSame(
                    bytecodeNode(readerA),
                    bytecodeNode(readerB),
                    "both readers share one read site");
            for (int call = 0; call < WARM_UP_CALLS; call++) {
                assertEquals("1", integerValue(call(readerA, module)));
                assertEquals("2", integerValue(call(readerB, module)));
            }

            ProtosObjectValue ownerA = readerA.capturedLexicalEnvironmentForRuntime().context();
            assertEquals("1", integerValue(call(readerA, module)));
            assertEquals("2", integerValue(call(readerB, module)));

            ownerA.removeLocalSlot("slot");
            ProtosSignalException absent =
                    assertThrows(ProtosSignalException.class, () -> call(readerA, module));
            assertSame(
                    ProtosCoreErrors.prototype(module, ProtosCoreErrors.StandardError.SLOT_NOT_FOUND),
                    absent.error().parent().orElse(null));
            assertEquals("2", integerValue(call(readerB, module)), "the other activation's owner is unaffected");

            ownerA.createLocalSlot("slot", ProtosNullValue.INSTANCE);
            assertSame(ProtosNullValue.INSTANCE, call(readerA, module));
            for (int call = 0; call < WARM_UP_CALLS; call++) {
                assertSame(ProtosNullValue.INSTANCE, call(readerA, module));
                assertEquals("2", integerValue(call(readerB, module)));
            }

            // Later removals, with the no-selection outcome already seen, stay exact.
            for (int round = 0; round < 3; round++) {
                ownerA.removeLocalSlot("slot");
                for (int call = 0; call < 3; call++) {
                    ProtosSignalException again =
                            assertThrows(ProtosSignalException.class, () -> call(readerA, module));
                    assertSame(
                            ProtosCoreErrors.prototype(
                                    module, ProtosCoreErrors.StandardError.SLOT_NOT_FOUND),
                            again.error().parent().orElse(null));
                    assertEquals("2", integerValue(call(readerB, module)));
                }
                ownerA.createLocalSlot("slot", ProtosNullValue.INSTANCE);
                assertSame(ProtosNullValue.INSTANCE, call(readerA, module));
                assertEquals("2", integerValue(call(readerB, module)));
            }
        });
        System.out.println("PERF037D_OWNER_REPRESENTATION_PROFILES=PASS");
    }

    /*
     * PERF037-D: alternating owner representations at one site sets each
     * representation profile once. A flag is only ever set after
     * transferToInterpreterAndInvalidate and never cleared, so once both
     * representations are known no further alternation reaches an
     * invalidation: the flags are the only invalidation trigger.
     */
    @Test
    void alternatingOwnerRepresentationsInvalidateOncePerProfile() throws Exception {
        withCore(module -> {
            ProtosClosureValue makeSlot =
                    closure("() => {\n  slot: 1\n  () => { slot }\n}", module);
            ProtosLexicalEnvironment deferred =
                    assertInstanceOf(ProtosClosureValue.class, call(makeSlot, module))
                            .capturedLexicalEnvironmentForRuntime();
            ProtosLexicalEnvironment materialized =
                    assertInstanceOf(ProtosClosureValue.class, call(makeSlot, module))
                            .capturedLexicalEnvironmentForRuntime();
            materialized.context();
            assertTrue(deferred.isDeferredForRuntime());
            assertFalse(materialized.isDeferredForRuntime());

            ProtosBytecodeRootNode.CapturedNearerScopeAbsence site =
                    ProtosBytecodeRootNode.CapturedNearerScopeAbsence.create(
                            deferred, "slot", 1);
            assertFalse((Boolean) privateField(site, "seenDeferredOwner"));
            assertFalse((Boolean) privateField(site, "seenMaterializedOwner"));

            for (int round = 0; round < WARM_UP_CALLS; round++) {
                assertSame(
                        deferred.lexicalBindingAuthorityForRuntime(),
                        site.ownerAuthorityOrNull(deferred),
                        "the profiled deferred lookup is exact");
                assertSame(
                        materialized.lexicalBindingAuthorityForRuntime(),
                        site.ownerAuthorityOrNull(materialized),
                        "the profiled materialized lookup is exact");
                assertTrue((Boolean) privateField(site, "seenDeferredOwner"));
                assertTrue((Boolean) privateField(site, "seenMaterializedOwner"));
            }
            assertFalse(
                    (Boolean) privateField(site, "seenNoSelection"),
                    "profiles are independent; an unrelated outcome stays unseen");

            ProtosBytecodeRootNode.CapturedNearerScopeAbsence uncached =
                    ProtosBytecodeRootNode.CapturedNearerScopeAbsence.uncached(1);
            for (String flag :
                    List.of(
                            "seenMaterializedOwner",
                            "seenDeferredOwner",
                            "seenPublishedDeferredOwner",
                            "seenNoSelection")) {
                assertTrue(
                        (Boolean) privateField(uncached, flag),
                        "the uncached instance never invalidates: " + flag);
            }
        });
        System.out.println("PERF037D_PROFILE_ALTERNATION=PASS");
    }

    /*
     * PERF037-D: the no-selection profile shared by the generic selection and
     * the owner-frame cache's cleared-binding path is monotonic: set once,
     * never cleared, and already set on the uncached instance.
     */
    @Test
    void noSelectionProfileIsMonotonic() throws Exception {
        withCore(module -> {
            ProtosLexicalEnvironment captured =
                    assertInstanceOf(
                                    ProtosClosureValue.class,
                                    call(
                                            closure("() => {\n  slot: 1\n  () => { slot }\n}", module),
                                            module))
                            .capturedLexicalEnvironmentForRuntime();
            ProtosBytecodeRootNode.CapturedNearerScopeAbsence site =
                    ProtosBytecodeRootNode.CapturedNearerScopeAbsence.create(captured, "slot", 1);
            assertFalse((Boolean) privateField(site, "seenNoSelection"));
            for (int round = 0; round < 3; round++) {
                site.profileNoSelection();
                assertTrue((Boolean) privateField(site, "seenNoSelection"));
            }

            ProtosBytecodeRootNode.CapturedNearerScopeAbsence uncached =
                    ProtosBytecodeRootNode.CapturedNearerScopeAbsence.uncached(1);
            uncached.profileNoSelection();
            assertTrue((Boolean) privateField(uncached, "seenNoSelection"));
        });
        System.out.println("PERF037D_NO_SELECTION_PROFILE=PASS");
    }

    /*
     * PERF037-D: a cache hit skips the physical presence check only behind
     * the owner layout's continuity token, admitted when the binding was
     * PRESENT at recording. Removal invalidates the token shared by every
     * activation of the layout before clearing; it is never renewed, so a
     * recreated binding is read through the physical check again.
     */
    @Test
    void ownerFrameCacheAdmitsPresentContinuityOnlyWhilePresent() throws Exception {
        withCore(module -> {
            evaluate(
                    "makeSlot: (initial) => {\n"
                            + "  slot: initial\n"
                            + "  () => { slot }\n"
                            + "}\n"
                            + "readerA: makeSlot(1)\n"
                            + "readerB: makeSlot(2)\n",
                    module);
            ProtosClosureValue readerA = closure("readerA", module);
            ProtosClosureValue readerB = closure("readerB", module);
            for (int call = 0; call < WARM_UP_CALLS; call++) {
                assertEquals("1", integerValue(call(readerA, module)));
            }
            ProtosBytecodeRootNode.CapturedOwnerFrameCache cache = ownerFrameCache(readerA);
            assertTrue(cache.isRecordedForTesting(), "the PRESENT owner was recorded");
            Assumption continuity = cache.presentContinuityForTesting();
            assertTrue(
                    continuity != null && continuity.isValid(),
                    "a PRESENT binding admits its valid continuity token");
            for (int call = 0; call < WARM_UP_CALLS; call++) {
                assertEquals("1", integerValue(call(readerA, module)));
                assertEquals("2", integerValue(call(readerB, module)));
            }
            assertTrue(continuity.isValid(), "reads never invalidate the token");

            // Removal in the other activation invalidates the shared token only.
            ProtosObjectValue ownerB = readerB.capturedLexicalEnvironmentForRuntime().context();
            ownerB.removeLocalSlot("slot");
            assertFalse(continuity.isValid(), "the token is shared by the layout");
            assertEquals("1", integerValue(call(readerA, module)), "a still PRESENT owner is read through the physical check");
            assertSlotNotFound(module, () -> call(readerB, module));

            ProtosObjectValue ownerA = readerA.capturedLexicalEnvironmentForRuntime().context();
            ownerA.removeLocalSlot("slot");
            assertSlotNotFound(module, () -> call(readerA, module));
            assertSlotNotFound(module, () -> call(readerA, module));

            ownerA.createLocalSlot("slot", ProtosNullValue.INSTANCE);
            assertSame(ProtosNullValue.INSTANCE, call(readerA, module));
            assertFalse(continuity.isValid(), "recreation never renews the token");
            for (int call = 0; call < WARM_UP_CALLS; call++) {
                assertSame(ProtosNullValue.INSTANCE, call(readerA, module));
            }
            ownerB.createLocalSlot("slot", evaluate("10", module));
            assertEquals("10", integerValue(call(readerB, module)));
        });
        System.out.println("PERF037D_PRESENT_CONTINUITY_CACHE=PASS");
    }

    /*
     * PERF037-D: an owner binding already ABSENT when the cache records it
     * admits no continuity token; every hit checks presence physically and
     * a later recreation is observed.
     */
    @Test
    void ownerFrameCacheAdmitsNoContinuityForAbsentBinding() throws Exception {
        withCore(module -> {
            evaluate(
                    "slot: 0\n"
                            + "makeSlot: () => {\n"
                            + "  slot: 1\n"
                            + "  () => { slot }\n"
                            + "}\n"
                            + "reader: makeSlot()\n",
                    module);
            ProtosClosureValue reader = closure("reader", module);
            ProtosObjectValue owner = reader.capturedLexicalEnvironmentForRuntime().context();
            owner.removeLocalSlot("slot");
            for (int call = 0; call < WARM_UP_CALLS; call++) {
                assertEquals("0", integerValue(call(reader, module)), "D179 C0: an ABSENT owner reveals the farther binding");
            }
            ProtosBytecodeRootNode.CapturedOwnerFrameCache cache = ownerFrameCache(reader);
            if (cache.isRecordedForTesting()) {
                assertNull(
                        cache.presentContinuityForTesting(),
                        "an ABSENT binding admits no continuity token");
            }
            owner.createLocalSlot("slot", ProtosNullValue.INSTANCE);
            for (int call = 0; call < WARM_UP_CALLS; call++) {
                assertSame(ProtosNullValue.INSTANCE, call(reader, module));
            }
            owner.removeLocalSlot("slot");
            assertEquals("0", integerValue(call(reader, module)));
        });
        System.out.println("PERF037D_ABSENT_ADMITS_NO_CONTINUITY=PASS");
    }

    /* PERF037-D: the uncached form never records, so it always checks presence. */
    @Test
    void retiredOwnerFrameCacheNeverAdmitsContinuity() {
        ProtosBytecodeRootNode.CapturedOwnerFrameCache retired =
                ProtosBytecodeRootNode.CapturedOwnerFrameCache.retired();
        assertFalse(retired.isRecordedForTesting());
        assertNull(retired.presentContinuityForTesting());
        System.out.println("PERF037D_UNCACHED_NO_CONTINUITY=PASS");
    }

    @Test
    void noDynamicBindingTokenIsLazyOneWayAndNeverRenewed() throws Exception {
        ProtosFrameLexicalLayout layout = ProtosFrameLexicalLayout.of(new String[] {"a"});
        assertNull(
                privateField(layout, "noDynamicBinding"),
                "a layout no specialized read depends on carries no token");

        Assumption token = layout.noDynamicBindingOrNull();
        assertTrue(token.isValid());
        assertSame(token, layout.noDynamicBindingOrNull());

        layout.recordDynamicBindingCreation();
        assertFalse(token.isValid(), "dynamic creation invalidates the token first");
        assertNull(layout.noDynamicBindingOrNull(), "an invalidated token is never renewed");

        ProtosFrameLexicalLayout observed = ProtosFrameLexicalLayout.of(new String[] {"a"});
        observed.recordDynamicBindingCreation();
        assertNull(
                observed.noDynamicBindingOrNull(),
                "no token is issued once a dynamic binding was ever created");
        System.out.println("PERF037C_ABSENCE_TOKEN=PASS");
    }

    @FunctionalInterface
    private interface CoreTest {
        void run(ProtosActivation module) throws Exception;
    }

    private static void withCore(CoreTest test) throws Exception {
        try (Context context = Context.newBuilder(ProtosLanguage.ID).build()) {
            context.initialize(ProtosLanguage.ID);
            context.enter();
            try {
                ProtosPrelude prelude = new ProtosCoreBootstrap().bootstrap(CORE);
                test.run(prelude.newModuleActivation());
            } finally {
                context.leave();
            }
        }
    }

    private static Object evaluate(String characters, ProtosActivation activation) {
        Source source =
                Source.newBuilder(ProtosLanguage.ID, characters, "perf037c-captured-read.protos")
                        .mimeType(ProtosLanguage.MIME_TYPE)
                        .build();
        CallTarget target = ProtosLanguageContext.current().parsePublic(source);
        return target.call(activation);
    }

    private static ProtosClosureValue closure(String characters, ProtosActivation activation) {
        return assertInstanceOf(ProtosClosureValue.class, evaluate(characters, activation));
    }

    /** One compact direct source call of {@code closure}, as a guest call site makes it. */
    private static Object call(ProtosClosureValue closure, ProtosActivation caller) {
        ProtosLanguageContext entered = ProtosLanguageContext.currentIfEnteredForRuntime();
        ProtosClosureValue selected =
                ProtosBytecodeRootNode.directClosureCallSelectionOrNull(closure, caller);
        assertSame(closure, selected, "canonical direct Closure-call selection");
        RootCallTarget target =
                ProtosBytecodeRootNode.PrepareSendArguments.fastOrdinarySendTarget(closure, entered);
        ProtosBytecodeRootNode.PreparedClosureCall prepared =
                ProtosBytecodeRootNode.PrepareClosureCallArguments.fastDirect(
                        closure,
                        caller,
                        new Object[0],
                        selected,
                        selected.definition(),
                        entered,
                        selected.definition(),
                        entered,
                        target);
        Object result =
                ProtosBytecodeRootNode.EnterClosureCall.ordinaryIndirect(
                        assertInstanceOf(ProtosBytecodeRootNode.OrdinarySourceCall.class, prepared),
                        IndirectCallNode.create());
        return prepared.finish(result);
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

    private static BytecodeNode bytecodeNode(ProtosClosureValue closure) {
        return closure.executionPlan()
                .orElseThrow()
                .bytecodeActivationRootForTesting()
                .getBytecodeNode();
    }

    private static List<Instruction> instructionsOf(ProtosClosureValue closure, String operation) {
        return bytecodeNode(closure).getInstructionsAsList().stream()
                .filter(instruction -> instruction.getName().contains(operation))
                .toList();
    }

    private static List<String> names(ProtosClosureValue closure) {
        return bytecodeNode(closure).getInstructionsAsList().stream()
                .map(Instruction::getName)
                .toList();
    }

    private static Instruction single(List<Instruction> instructions) {
        assertEquals(1, instructions.size(), () -> "expected one instruction: " + instructions);
        return instructions.get(0);
    }

    private static Object constantArgument(Instruction instruction, String name) {
        Instruction.Argument argument =
                instruction.getArguments().stream()
                        .filter(candidate -> candidate.getName().equals(name))
                        .findFirst()
                        .orElseThrow(() -> new AssertionError("no constant " + name + " operand"));
        return switch (argument.getKind()) {
            case INTEGER -> argument.asInteger();
            default -> argument.asConstant();
        };
    }

    private static String integerValue(Object value) {
        return com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerDecimalText(value);
    }

    private static void assertSlotNotFound(ProtosActivation module, Executable read) {
        ProtosSignalException absent = assertThrows(ProtosSignalException.class, read);
        assertSame(
                ProtosCoreErrors.prototype(module, ProtosCoreErrors.StandardError.SLOT_NOT_FOUND),
                absent.error().parent().orElse(null));
    }

    /** The owner-frame cache of {@code closure}'s single root-level selection site. */
    private static ProtosBytecodeRootNode.CapturedOwnerFrameCache ownerFrameCache(
            ProtosClosureValue closure) throws ReflectiveOperationException {
        Instruction select =
                single(instructionsOf(closure, "SelectCapturedMaterializedOwnerFrameAtRoot"));
        for (Instruction.Argument argument : select.getArguments()) {
            if (argument.getKind() == Instruction.Argument.Kind.NODE_PROFILE) {
                Object found = findField(
                        argument.asCachedNode(),
                        ProtosBytecodeRootNode.CapturedOwnerFrameCache.class,
                        3);
                if (found != null) {
                    return (ProtosBytecodeRootNode.CapturedOwnerFrameCache) found;
                }
            }
        }
        throw new AssertionError("no owner-frame cache in " + select);
    }

    private static Object findField(Object target, Class<?> type, int depth)
            throws ReflectiveOperationException {
        if (target == null || depth < 0) {
            return null;
        }
        if (type.isInstance(target)) {
            return target;
        }
        if (!target.getClass().getName().startsWith("com.guillermomolina.protos")) {
            return null;
        }
        for (Class<?> c = target.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field field : c.getDeclaredFields()) {
                if (field.getType().isPrimitive()
                        || java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                field.setAccessible(true);
                Object found = findField(field.get(target), type, depth - 1);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static Object privateField(Object target, String name)
            throws ReflectiveOperationException {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }
}
