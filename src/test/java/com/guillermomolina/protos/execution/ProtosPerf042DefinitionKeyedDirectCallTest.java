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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.guillermomolina.protos.execution.ProtosSemanticBytecodeRootNode.TryDirectCallOne;
import com.guillermomolina.protos.execution.ProtosSemanticBytecodeRootNode.TryDirectCallZero;
import com.guillermomolina.protos.runtime.ProtosActivation;
import com.guillermomolina.protos.runtime.ProtosClosureValue;
import com.guillermomolina.protos.runtime.ProtosNullValue;
import com.guillermomolina.protos.runtime.ProtosObjectValue;
import com.guillermomolina.protos.runtime.ProtosPrelude;

import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.source.Source;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;

/**
 * PERF042 coverage for the definition-keyed tier of the fused direct
 * Closure Call0/Call1 operations ({@code TryDirectCallZero},
 * {@code TryDirectCallOne}).
 *
 * <p>Admission is proved through the tier's own admission helpers on real
 * fresh Closures: fresh instances of one definition share the cached
 * Context-owned target, the target-level proof holds once per definition, and
 * the instance-level proof (ordinary {@code call} selection and unobservable
 * ReturnHome) is re-established for the current instance. Guest workloads
 * repeat every call site more often than either cache tier's limit and check
 * that ordinary semantics, including {@code call} overrides added, removed and
 * re-created after warm-up, are preserved.
 *
 * <p>Installation of the definition tier on a real call site is proved
 * structurally: the enclosing root is moved to the cached Bytecode tier and the
 * generated operation node's {@code definitionSource} cache is read
 * reflectively. This proves installation and persistence of an entry and the
 * identity of its cached target; it does not prove how often hot calls use the
 * entry or what compiled code results, which needs a Tier-2 graph.
 */
final class ProtosPerf042DefinitionKeyedDirectCallTest {
    private static final Path CORE = Path.of("protos", "lib", "core");
    private static final int PAST_CACHE_LIMITS = 12;

    @Test
    void freshCallOneClosuresConvergeOnTheDefinitionTier() throws Exception {
        withCore(module -> {
            evaluate("""
                    run: () => {
                        identity: (value) => { value }
                        identity(1)
                    }
                    make: () => { (value) => { value } }
                    """, "perf042-c1-define.protos", module);
            for (int i = 0; i < PAST_CACHE_LIMITS; i++) {
                assertInteger(1, evaluate("run()\n", "perf042-c1-run.protos", module));
            }

            ProtosClosureValue first = closure("make()\n", module);
            ProtosClosureValue second = closure("make()\n", module);
            assertNotSame(first, second);
            assertSame(first.definition(), second.definition());
            assertSame(first.definition(), TryDirectCallOne.sourceDefinitionOrNull(second));

            ProtosLanguageContext context = ProtosLanguageContext.current();
            RootCallTarget target = TryDirectCallOne.definitionTarget(first, module, context);
            assertNotNull(target);
            assertSame(target, TryDirectCallOne.definitionTarget(second, module, context));
            assertTrue(TryDirectCallOne.admittedTarget(target, 1));
            assertSame(first, TryDirectCallZero.admittedInstanceOrNull(first, module));
            assertSame(second, TryDirectCallZero.admittedInstanceOrNull(second, module));
        });
    }

    @Test
    void freshCallZeroClosuresConvergeOnTheDefinitionTier() throws Exception {
        withCore(module -> {
            evaluate("""
                    run: () => {
                        constant: () => { 1 }
                        constant()
                    }
                    make: () => { () => { 1 } }
                    """, "perf042-c0-define.protos", module);
            for (int i = 0; i < PAST_CACHE_LIMITS; i++) {
                assertInteger(1, evaluate("run()\n", "perf042-c0-run.protos", module));
            }

            ProtosClosureValue first = closure("make()\n", module);
            ProtosClosureValue second = closure("make()\n", module);
            assertNotSame(first, second);

            ProtosLanguageContext context = ProtosLanguageContext.current();
            RootCallTarget target = TryDirectCallZero.definitionTarget(first, module, context);
            assertNotNull(target);
            assertSame(target, TryDirectCallZero.definitionTarget(second, module, context));
            assertTrue(TryDirectCallZero.admittedTarget(target, 0));
            // A zero-parameter Closure called with an argument selects its
            // arity-rejection entry, which the fused path never enters.
            assertFalse(TryDirectCallOne.admittedTarget(target, 1));
            assertSame(second, TryDirectCallZero.admittedInstanceOrNull(second, module));
        });
    }

    @Test
    void freshInstancesKeepTheirOwnIdentityAndCapturedEnvironment() throws Exception {
        withCore(module -> {
            evaluate("""
                    passThrough: () => { (value) => { value } }
                    makeAdder: (seed) => { (value) => { seed + value } }
                    makeConstant: (seed) => { () => { seed } }
                    callOne: (f, value) => { f(value) }
                    callZero: (f) => { f() }
                    """, "perf042-capture-define.protos", module);
            for (int i = 0; i < PAST_CACHE_LIMITS; i++) {
                ProtosClosureValue fresh = closure("passThrough()\n", module);
                module.context().createLocalSlot("fresh" + i, fresh);
                assertSame(
                        fresh,
                        evaluate("callOne(passThrough(), fresh" + i + ")\n",
                                "perf042-identity.protos", module));
                assertInteger(i + 1, evaluate(
                        "callOne(makeAdder(" + i + "), 1)\n",
                        "perf042-capture-one.protos", module));
                assertInteger(i, evaluate(
                        "callZero(makeConstant(" + i + "))\n",
                        "perf042-capture-zero.protos", module));
            }
        });
    }

    @Test
    void callOverridesAreObservedBeforeAndAfterWarmUp() throws Exception {
        withCore(module -> {
            evaluate("""
                    make: () => { (value) => { value } }
                    makeZero: () => { () => { 1 } }
                    callOne: (f) => { f(7) }
                    callZero: (f) => { f() }
                    early: make()
                    early.call: (value) => { 100 + value }
                    earlyZero: makeZero()
                    earlyZero.call: () => { 100 }
                    """, "perf042-override-define.protos", module);

            // Override installed before the site ever warmed up.
            assertInteger(107, evaluate("callOne(early)\n", "perf042-early.protos", module));
            assertInteger(100, evaluate("callZero(earlyZero)\n", "perf042-early0.protos", module));

            for (int i = 0; i < PAST_CACHE_LIMITS; i++) {
                assertInteger(7, evaluate("callOne(make())\n", "perf042-warm.protos", module));
                assertInteger(1, evaluate("callZero(makeZero())\n", "perf042-warm0.protos", module));
            }

            // Override installed after warm-up on a fresh instance of the
            // cached definition; then removed, re-created and mixed with
            // standard instances of the same definition at the same site.
            evaluate("""
                    late: make()
                    late.call: (value) => { 200 + value }
                    lateZero: makeZero()
                    lateZero.call: () => { 200 }
                    """, "perf042-late-define.protos", module);
            assertInteger(207, evaluate("callOne(late)\n", "perf042-late.protos", module));
            assertInteger(200, evaluate("callZero(lateZero)\n", "perf042-late0.protos", module));
            assertInteger(7, evaluate("callOne(make())\n", "perf042-mixed.protos", module));
            assertInteger(1, evaluate("callZero(makeZero())\n", "perf042-mixed0.protos", module));

            evaluate("""
                    late.removeSlot("call")
                    lateZero.removeSlot("call")
                    """, "perf042-remove.protos", module);
            assertInteger(7, evaluate("callOne(late)\n", "perf042-removed.protos", module));
            assertInteger(1, evaluate("callZero(lateZero)\n", "perf042-removed0.protos", module));

            evaluate("""
                    late.call: (value) => { 300 + value }
                    lateZero.call: () => { 300 }
                    """, "perf042-recreate.protos", module);
            assertInteger(307, evaluate("callOne(late)\n", "perf042-recreated.protos", module));
            assertInteger(300, evaluate("callZero(lateZero)\n", "perf042-recreated0.protos", module));

            for (int i = 0; i < PAST_CACHE_LIMITS; i++) {
                assertInteger(7, evaluate("callOne(make())\n", "perf042-after.protos", module));
                assertInteger(1, evaluate("callZero(makeZero())\n", "perf042-after0.protos", module));
                assertInteger(307, evaluate("callOne(late)\n", "perf042-after-late.protos", module));
                assertInteger(107, evaluate("callOne(early)\n", "perf042-after-early.protos", module));
            }

            ProtosClosureValue late = assertInstanceOf(
                    ProtosClosureValue.class,
                    module.context().readLocalSlot("late").orElseThrow());
            assertNull(TryDirectCallZero.admittedInstanceOrNull(late, module));
        });
    }

    @Test
    void admissionBoundariesRejectAndFallBack() throws Exception {
        withCore(module -> {
            evaluate("""
                    makeEscaper: () => { (value) => { ^value } }
                    makeAdder: (seed) => { (value) => { seed + value } }
                    outer: () => {
                        inner: (value) => { ^value }
                        inner(5)
                        0
                    }
                    """, "perf042-boundary-define.protos", module);
            for (int i = 0; i < PAST_CACHE_LIMITS; i++) {
                assertInteger(5, evaluate("outer()\n", "perf042-escape.protos", module));
            }

            ProtosLanguageContext context = ProtosLanguageContext.current();

            // Observable ReturnHome: the exact instance cannot prove the
            // unobservable marker.
            ProtosClosureValue escaper = closure("makeEscaper()\n", module);
            assertNull(TryDirectCallZero.admittedInstanceOrNull(escaper, module));

            // A body that may suspend (a dynamically selected send) is not
            // admitted at the target level.
            ProtosClosureValue adder = closure("makeAdder(1)\n", module);
            RootCallTarget adderTarget = TryDirectCallOne.definitionTarget(adder, module, context);
            assertNotNull(adderTarget);
            assertFalse(TryDirectCallOne.admittedTarget(adderTarget, 1));

            // No entered Context, or a receiver that is not a Closure, yields
            // no target projection.
            assertNull(TryDirectCallOne.definitionTarget(adder, module, null));
            assertNull(TryDirectCallOne.definitionTarget(new Object(), module, context));
            assertNull(TryDirectCallOne.sourceDefinitionOrNull(new Object()));

            // A native Closure has no source definition for this tier.
            Object standardCall =
                    ProtosObjectValue.rootObject().readLocalSlot("call").orElseThrow();
            ProtosClosureValue nativeClosure =
                    assertInstanceOf(ProtosClosureValue.class, standardCall);
            assertTrue(nativeClosure.nativeBody().isPresent());
            assertNull(TryDirectCallZero.sourceDefinitionOrNull(nativeClosure));
            assertNull(TryDirectCallZero.admittedInstanceOrNull(nativeClosure, module));
        });
    }

    @Test
    void definitionTierIsInstalledAtTheCanonicalCallSite() throws Exception {
        withCore(module -> {
            evaluate("""
                    runOne: () => {
                        identity: (value) => { value }
                        f: identity
                        f(1)
                    }
                    runZero: () => {
                        constant: () => { 1 }
                        f: constant
                        f()
                    }
                    """, "perf042-installed-define.protos", module);
            enterCachedTier("runOne", module);
            enterCachedTier("runZero", module);
            for (int i = 0; i < PAST_CACHE_LIMITS; i++) {
                assertInteger(1, evaluate("runOne()\n", "perf042-installed-one.protos", module));
                assertInteger(1, evaluate("runZero()\n", "perf042-installed-zero.protos", module));
            }

            // Every fresh literal instance shares one plan, so the fresh
            // instances past the identity tier converge on one entry.
            assertSingleAdmittedEntry(
                    definitionTierEntries("runOne", TryDirectCallOne.class, module), 1);
            assertSingleAdmittedEntry(
                    definitionTierEntries("runZero", TryDirectCallZero.class, module), 0);
        });
    }

    @Test
    void distinctExecutionPlansKeepDistinctDefinitionTierTargets() throws Exception {
        withCore(module -> {
            evaluate("""
                    make: () => { (value) => { value } }
                    callOne: (f, value) => { f(value) }
                    """, "perf042-plans-define.protos", module);
            enterCachedTier("callOne", module);
            for (int i = 0; i < PAST_CACHE_LIMITS; i++) {
                assertInteger(7, evaluate("callOne(make(), 7)\n", "perf042-plans-warm.protos", module));
            }

            // A second legitimate plan for the same definition and Context:
            // the rebuild a Context projection performs, carried by a P
            // projection of a fresh instance.
            ProtosClosureValue original = closure("make()\n", module);
            ProtosClosureExecutionPlan template = original.executionPlan().orElseThrow();
            ProtosLanguageContext context = ProtosLanguageContext.current();
            ProtosClosureExecutionPlan rebuilt =
                    template.rebuildBytecodeForLanguage(
                            original.definition(), context.languageForTesting(), ProtosExecutionMode.NORMAL);
            ProtosPrelude prelude = module.prelude().orElseThrow();
            ProtosClosureValue projected =
                    original.parallelProjection(
                            List.of(prelude.newExecutionContext()),
                            ProtosNullValue.INSTANCE,
                            prelude,
                            rebuilt);
            assertSame(original.definition(), projected.definition());
            assertNotSame(template, rebuilt);

            RootCallTarget originalTarget =
                    TryDirectCallOne.definitionTarget(original, module, context);
            RootCallTarget projectedTarget =
                    TryDirectCallOne.definitionTarget(projected, module, context);
            assertSame(template.bytecodeActivationTargetForComposition(), originalTarget);
            assertSame(rebuilt.bytecodeActivationTargetForComposition(), projectedTarget);
            assertNotSame(originalTarget, projectedTarget);
            assertTrue(TryDirectCallOne.admittedTarget(projectedTarget, 1));

            module.context().createLocalSlot("projected", projected);
            for (int i = 0; i < PAST_CACHE_LIMITS; i++) {
                assertInteger(i, evaluate(
                        "callOne(projected, " + i + ")\n", "perf042-plans-projected.protos", module));
                assertInteger(i, evaluate(
                        "callOne(make(), " + i + ")\n", "perf042-plans-original.protos", module));
            }

            // One entry per plan, each holding exactly its own plan's target.
            List<Object> entries = definitionTierEntries("callOne", TryDirectCallOne.class, module);
            assertEquals(2, entries.size());
            List<Object> targets = new ArrayList<>();
            for (Object entry : entries) {
                ProtosClosureExecutionPlan plan =
                        (ProtosClosureExecutionPlan) field(entry, "cachedSourcePlan_");
                assertSame(plan.bytecodeActivationTargetForComposition(), field(entry, "cachedTarget_"));
                targets.add(field(entry, "cachedTarget_"));
            }
            assertTrue(targets.contains(originalTarget));
            assertTrue(targets.contains(projectedTarget));
        });
    }

    @Test
    void foreignContextClosureProjectsATargetOwnedByTheEnteredContext() throws Exception {
        try (Context first = Context.newBuilder(ProtosLanguage.ID).build()) {
            first.initialize(ProtosLanguage.ID);
            first.enter();
            ProtosClosureValue foreign;
            RootCallTarget foreignTarget;
            try {
                ProtosActivation module =
                        new ProtosCoreBootstrap().bootstrap(CORE).newModuleActivation();
                evaluate("make: () => { (value) => { value } }\n", "perf042-foreign-define.protos", module);
                foreign = closure("make()\n", module);
                foreignTarget = TryDirectCallOne.definitionTarget(
                        foreign, module, ProtosLanguageContext.current());
            } finally {
                first.leave();
            }
            ProtosClosureExecutionPlan template = foreign.executionPlan().orElseThrow();
            assertSame(template.bytecodeActivationTargetForComposition(), foreignTarget);

            withCore(module -> {
                ProtosLanguageContext entered = ProtosLanguageContext.current();
                assertNotSame(template.language().orElseThrow(), entered.languageForTesting());
                int projectionsBefore = entered.projectedBytecodeExecutionPlanCountForTesting();

                // The derivation the definition tier caches per entered
                // Context never yields the other Context's target.
                RootCallTarget projected =
                        ProtosBytecodeRootNode.PrepareSendArguments.fastOrdinarySendTarget(
                                foreign, entered);
                assertNotNull(projected);
                assertNotSame(foreignTarget, projected);
                assertSame(
                        entered.languageForTesting(),
                        projected.getRootNode().getLanguage(ProtosLanguage.class));
                assertSame(
                        projected,
                        ProtosBytecodeRootNode.PrepareSendArguments.fastOrdinarySendTarget(
                                foreign, entered));
                assertEquals(
                        projectionsBefore + 1,
                        entered.projectedBytecodeExecutionPlanCountForTesting());
                assertSame(template, foreign.executionPlan().orElseThrow());
            });
        }
    }

    @Test
    void overriddenCallDoesNotPrepareAnUnavailableOriginalPlan() throws Exception {
        withCore(module -> {
            evaluate("""
                    make: () => { (value) => { value } }
                    callOne: (f, value) => { f(value) }
                    """, "perf042-deferred-define.protos", module);
            enterCachedTier("callOne", module);
            for (int i = 0; i < PAST_CACHE_LIMITS; i++) {
                assertInteger(7, evaluate("callOne(make(), 7)\n", "perf042-deferred-warm.protos", module));
            }
            Object entry = singleEntry("callOne", module);

            // A P-style deferred projection of the warmed definition whose
            // original plan can never be prepared.
            int[] rematerializations = {0};
            ProtosClosureValue template = closure("make()\n", module);
            ProtosPrelude prelude = module.prelude().orElseThrow();
            ProtosClosureValue deferred =
                    template.parallelProjectionDeferred(
                            List.of(prelude.newExecutionContext()),
                            ProtosNullValue.INSTANCE,
                            prelude,
                            () -> {
                                rematerializations[0]++;
                                throw new IllegalStateException(
                                        "PERF042: the original plan must not be prepared");
                            });
            assertSame(template.definition(), deferred.definition());
            module.context().createLocalSlot("deferred", deferred);
            evaluate("deferred.call: (value) => { 100 + value }\n", "perf042-deferred-override.protos", module);

            assertNull(TryDirectCallOne.definitionTarget(
                    deferred, module, ProtosLanguageContext.current()));
            for (int i = 0; i < PAST_CACHE_LIMITS; i++) {
                assertInteger(107, evaluate("callOne(deferred, 7)\n", "perf042-deferred-call.protos", module));
                assertInteger(7, evaluate("callOne(make(), 7)\n", "perf042-deferred-mixed.protos", module));
            }
            assertEquals(0, rematerializations[0]);
            assertTrue(deferred.executionPlan().isEmpty());
            assertSame(entry, singleEntry("callOne", module));
        });
    }

    @Test
    void transientRejectionsKeepTheDefinitionTierEntry() throws Exception {
        withCore(module -> {
            evaluate("""
                    make: () => { (value) => { value } }
                    makeAdder: (seed) => { (value) => { seed + value } }
                    callOne: (f, value) => { f(value) }
                    overriddenFirst: (f, value) => { f(value) }
                    overridden: make()
                    overridden.call: (value) => { 100 + value }
                    """, "perf042-transient-define.protos", module);
            enterCachedTier("callOne", module);
            enterCachedTier("overriddenFirst", module);
            for (int i = 0; i < PAST_CACHE_LIMITS; i++) {
                assertInteger(7, evaluate("callOne(make(), 7)\n", "perf042-transient-warm.protos", module));
            }
            Object entry = singleEntry("callOne", module);

            // Overridden instance of the cached definition, then a definition
            // whose target is not admitted, then admitted instances again.
            for (int i = 0; i < PAST_CACHE_LIMITS; i++) {
                assertInteger(107, evaluate("callOne(overridden, 7)\n", "perf042-transient-override.protos", module));
                assertInteger(8, evaluate("callOne(makeAdder(1), 7)\n", "perf042-transient-adder.protos", module));
                assertInteger(7, evaluate("callOne(make(), 7)\n", "perf042-transient-back.protos", module));
            }
            assertSame(entry, singleEntry("callOne", module));

            // A site whose first receiver is overridden still installs the
            // definition tier for the admitted instances that follow.
            assertInteger(107, evaluate("overriddenFirst(overridden, 7)\n", "perf042-first-override.protos", module));
            for (int i = 0; i < PAST_CACHE_LIMITS; i++) {
                assertInteger(7, evaluate("overriddenFirst(make(), 7)\n", "perf042-first-admitted.protos", module));
            }
            assertSingleAdmittedEntry(
                    definitionTierEntries("overriddenFirst", TryDirectCallOne.class, module), 1);
        });
    }

    /**
     * Moves the named module function's root to the cached Bytecode tier on
     * its next entry, so its operation nodes and their caches exist.
     */
    private static void enterCachedTier(String function, ProtosActivation module) {
        functionRoot(function, module).getBytecodeNode().setUncachedThreshold(0);
    }

    private static ProtosSemanticBytecodeRootNode functionRoot(
            String function, ProtosActivation module) {
        ProtosClosureValue closure = assertInstanceOf(
                ProtosClosureValue.class,
                module.context().readLocalSlot(function).orElseThrow());
        return closure.executionPlan().orElseThrow().bytecodeActivationRootForTesting();
    }

    /**
     * Structural, test-only view of the generated operation node: the
     * {@code definitionSource} cache entries of every {@code operation} site
     * in the named function's cached Bytecode node.
     */
    private static List<Object> definitionTierEntries(
            String function, Class<?> operation, ProtosActivation module) throws Exception {
        Object bytecode = functionRoot(function, module).getBytecodeNode();
        Node[] cachedNodes = (Node[]) field(bytecode, "cachedNodes_");
        String nodeName = operation.getSimpleName() + "_Node";
        List<Object> entries = new ArrayList<>();
        int sites = 0;
        for (Node node : cachedNodes) {
            if (node == null || !node.getClass().getSimpleName().equals(nodeName)) {
                continue;
            }
            sites++;
            for (Object entry = field(node, "definitionSource_cache");
                    entry != null;
                    entry = field(entry, "next_")) {
                entries.add(entry);
            }
        }
        assertEquals(1, sites, "expected exactly one " + nodeName + " site in " + function);
        return entries;
    }

    private static Object singleEntry(String function, ProtosActivation module) throws Exception {
        List<Object> entries = definitionTierEntries(function, TryDirectCallOne.class, module);
        assertEquals(1, entries.size());
        return entries.get(0);
    }

    private static void assertSingleAdmittedEntry(List<Object> entries, int arity) throws Exception {
        assertEquals(1, entries.size());
        Object entry = entries.get(0);
        RootCallTarget target = (RootCallTarget) field(entry, "cachedTarget_");
        ProtosClosureExecutionPlan plan =
                (ProtosClosureExecutionPlan) field(entry, "cachedSourcePlan_");
        assertSame(plan.bytecodeActivationTargetForComposition(), target);
        assertTrue(TryDirectCallZero.admittedTarget(target, arity));
        assertNotNull(field(entry, "node_"));
    }

    private static Object field(Object owner, String name) throws ReflectiveOperationException {
        for (Class<?> type = owner.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(owner);
            } catch (NoSuchFieldException absent) {
                // Continue with the superclass.
            }
        }
        throw new NoSuchFieldException(owner.getClass().getName() + "." + name);
    }

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

    private static Object evaluate(String characters, String name, ProtosActivation activation) {
        Source source =
                Source.newBuilder(ProtosLanguage.ID, characters, name)
                        .mimeType(ProtosLanguage.MIME_TYPE)
                        .build();
        CallTarget target = ProtosLanguageContext.current().parsePublic(source);
        return target.call(activation);
    }

    private static ProtosClosureValue closure(String characters, ProtosActivation activation) {
        return assertInstanceOf(
                ProtosClosureValue.class,
                evaluate(characters, "perf042-closure.protos", activation));
    }

    private static void assertInteger(long expected, Object actual) {
        assertEquals(Long.toString(expected), com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerDecimalText(actual));
    }
}
