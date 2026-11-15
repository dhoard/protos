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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.guillermomolina.protos.parser.ProtosParser;
import com.guillermomolina.protos.runtime.ProtosActivation;
import com.guillermomolina.protos.runtime.ProtosActorExecutionDomain;
import com.guillermomolina.protos.runtime.ProtosActorModuleState;
import com.guillermomolina.protos.runtime.ProtosClosureValue;
import com.guillermomolina.protos.runtime.ProtosEnvironmentValue;
import com.guillermomolina.protos.runtime.ProtosSignalException;
import com.guillermomolina.protos.runtime.ProtosFutureValue;
import com.guillermomolina.protos.runtime.ProtosNullValue;
import com.guillermomolina.protos.runtime.ProtosNumericValueSupport;
import com.guillermomolina.protos.runtime.ProtosObjectValue;
import com.guillermomolina.protos.runtime.ProtosPrelude;
import com.guillermomolina.protos.runtime.ProtosStringValue;
import com.guillermomolina.protos.runtime.ProtosTask;
import com.guillermomolina.protos.runtime.modules.ProtosModuleKey;
import com.guillermomolina.protos.semantic.Canonicalizer;
import com.guillermomolina.protos.semantic.ast.CanonicalSequence;
import com.oracle.truffle.api.TruffleLanguage.LanguageReference;
import com.oracle.truffle.api.source.Source;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;

/**
 * PLAT075 / I167 production regression: the Core import policy runs as the source-backed {@code
 * import.call} of {@code protos/lib/core/import.protos}, over private bridge primitives, and its
 * module initializer still runs as the ordinary C-prime structured import child.
 *
 * <p>Every Prelude is created through the normal production {@link
 * ProtosCoreBootstrap#bootstrap(Path, ProtosModuleResolver)}; there is no selectable import mode.
 */
final class ProtosPlat075ProtosFirstImportFeasibilityTest {
    private static final Path CORE = Path.of("protos", "lib", "core");
    private static final LanguageReference<ProtosLanguage> LANGUAGE_REF =
            LanguageReference.create(ProtosLanguage.class);

    // ---- Case A: the policy is really guest code ----

    @Test
    void caseA_productionCallIsTheOneSourceBackedImplementation() throws Exception {
        try (LanguageScope scope = languageScope()) {
            MemoryResolver resolver = new MemoryResolver();
            ProtosPrelude prelude = productionCore(resolver);
            ProtosClosureValue guestCall = importCall(prelude);
            assertTrue(guestCall.definition() != null, "import.call must be source-defined");
            assertTrue(guestCall.executionPlan().isPresent());
            assertTrue(guestCall.nativeBody().isEmpty(), "import.call must not be native");
            assertNull(
                    ProtosStandardImportProtocol.structuredImportRuntimeForImplementation(guestCall),
                    "the guest call must not be classified as the import execute intrinsic");
            ProtosModuleRuntime bound = ProtosStandardImportProtocol.runtimeForPrelude(prelude);
            assertTrue(bound != null, "PLAT051 consumers must find the Prelude's module runtime");
            assertSame(bound, prelude.moduleRuntimeForRuntime(),
                    "the runtime comes only from the private per-Prelude binding");

            // A second production bootstrap installs the same canonical source-backed policy,
            // with its own private runtime binding; no native policy is ever installed.
            ProtosPrelude other = productionCore(resolver);
            ProtosClosureValue otherCall = importCall(other);
            assertTrue(otherCall.nativeBody().isEmpty());
            assertTrue(otherCall.definition() != null);
            assertNotSame(bound, ProtosStandardImportProtocol.runtimeForPrelude(other));
            assertThrows(IllegalStateException.class,
                    () -> prelude.bindModuleRuntimeForRuntime(bound),
                    "the private runtime binding is established exactly once");
        }
    }

    @Test
    void caseA_bridgePrimitivesDecideNothingOnTheirOwn() throws Exception {
        try (LanguageScope scope = languageScope()) {
            MemoryResolver resolver =
                    new MemoryResolver().module("m", "ran: 1\n99");
            ProtosPrelude prelude = productionCore(resolver);
            ProtosObjectValue bridge =
                    ProtosStandardImportProtocol.createImportBridge(
                            new ProtosModuleRuntime(resolver));
            ProtosActorExecutionDomain domain = new ProtosActorExecutionDomain();
            ProtosActorModuleState state = new ProtosActorModuleState();

            // resolve + lookup observe; they never initialize.
            ProtosTask observed =
                    run(scope, domain, activation(prelude, domain, state, Map.of("bridge", bridge)),
                            "bridge.lookup(bridge.resolve(\"m\"))", "plat075-a-lookup.protos");
            assertEquals(ProtosTask.State.COMPLETED, observed.state());
            assertSame(ProtosNullValue.INSTANCE, observed.result().orElseThrow());
            assertEquals(1, resolver.resolveCalls.get());
            assertEquals(0, resolver.loads("m"));
            assertTrue(state.lookup(ProtosModuleKey.source("m")).isEmpty());

            // begin checks preconditions only: it inserts no ModuleRecord and runs nothing.
            ProtosTask reserved =
                    run(scope, domain, activation(prelude, domain, state, Map.of("bridge", bridge)),
                            "bridge.begin(bridge.resolve(\"m\"))", "plat075-a-begin.protos");
            assertEquals(ProtosTask.State.COMPLETED, reserved.state());
            assertTrue(state.lookup(ProtosModuleKey.source("m")).isEmpty(),
                    "begin alone must not insert a ModuleRecord");
            assertEquals(0, resolver.loads("m"));
            ProtosObjectValue token = (ProtosObjectValue) reserved.result().orElseThrow();
            assertTrue(token.isFrozen());
            assertTrue(token.localSlotsSnapshot().isEmpty(), "tokens must expose no slots");
        }
    }

    // ---- Case B: validation and resolution ----

    @Test
    void caseB_validSemanticStringImports() throws Exception {
        try (LanguageScope scope = languageScope()) {
            MemoryResolver resolver = new MemoryResolver().module("m", "x: 1");
            Fixture f = fixture(resolver);
            ProtosTask task = f.run(scope, "import(\"m\")", "plat075-b-valid.protos");
            assertEquals(ProtosTask.State.COMPLETED, task.state());
            ProtosObjectValue module = (ProtosObjectValue) task.result().orElseThrow();
            assertEquals("1", decimal(module.readLocalSlot("x").orElseThrow()));
            assertEquals(1, resolver.resolveCalls.get());
        }
    }

    @Test
    void caseB_invalidSpecifiersNeverReachTheResolver() throws Exception {
        try (LanguageScope scope = languageScope()) {
            MemoryResolver resolver = new MemoryResolver().module("m", "x: 1");
            Fixture f = fixture(resolver);
            ProtosObjectValue stringPrototype =
                    (ProtosObjectValue) f.prelude.bindings().readLocalSlot("String").orElseThrow();
            ProtosObjectValue delegatesToString = new ProtosObjectValue(stringPrototype);

            assertFailed(f.run(scope, "import(42)", "plat075-b-number.protos"));
            assertFailed(
                    f.run(scope, Map.of("fake", delegatesToString),
                            "import(fake)", "plat075-b-delegating.protos"));
            assertFailed(
                    f.run(scope, "import({ toString: () => \"m\" })",
                            "plat075-b-no-coercion.protos"));
            assertEquals(0, resolver.resolveCalls.get());
            assertEquals(0, resolver.totalLoads());
            assertTrue(f.state.lookup(ProtosModuleKey.source("m")).isEmpty());
        }
    }

    @Test
    void caseB_hostResolutionFailureIsAnOrdinaryImportFailure() throws Exception {
        try (LanguageScope scope = languageScope()) {
            MemoryResolver resolver = new MemoryResolver();
            Fixture f = fixture(resolver);
            assertFailed(f.run(scope, "import(\"missing\")", "plat075-b-missing.protos"));
            assertEquals(1, resolver.resolveCalls.get());
            assertEquals(0, resolver.totalLoads());
        }
    }

    @Test
    void caseB_distinctSpecifiersResolvingToOneKeyShareOneInstance() throws Exception {
        try (LanguageScope scope = languageScope()) {
            MemoryResolver resolver =
                    new MemoryResolver().module("m", "x: 1").alias("./m", "m");
            Fixture f = fixture(resolver);
            ProtosTask first = f.run(scope, "import(\"m\")", "plat075-b-alias-1.protos");
            ProtosTask second = f.run(scope, "import(\"./m\")", "plat075-b-alias-2.protos");
            assertEquals(ProtosTask.State.COMPLETED, first.state());
            assertEquals(ProtosTask.State.COMPLETED, second.state());
            assertSame(first.result().orElseThrow(), second.result().orElseThrow());
            assertEquals(1, resolver.loads("m"));
            assertEquals(2, resolver.resolveCalls.get());
        }
    }

    // ---- Case C: cache and identity ----

    @Test
    void caseC_sourceIsInitializedOnceAndReadyHitsAreImmediate() throws Exception {
        try (LanguageScope scope = languageScope()) {
            MemoryResolver resolver = new MemoryResolver().module("m", "x: 1\n99");
            Fixture f = fixture(resolver);
            ProtosTask first = f.run(scope, "import(\"m\")", "plat075-c-first.protos");
            assertEquals(ProtosTask.State.COMPLETED, first.state());
            ProtosObjectValue module = (ProtosObjectValue) first.result().orElseThrow();
            assertEquals(
                    ProtosActorModuleState.InitializationState.READY,
                    f.state.lookup(ProtosModuleKey.source("m")).orElseThrow().state());

            ProtosTask both =
                    f.run(scope, "a: import(\"m\")\nb: import(\"m\")\nb",
                            "plat075-c-second.protos");
            assertEquals(ProtosTask.State.COMPLETED, both.state());
            assertSame(module, both.result().orElseThrow());
            assertEquals(1, resolver.loads("m"));
            assertFalse(f.domain.dispatchOne(), "a READY hit must leave no pending work");
        }
    }

    @Test
    void caseC_distinctActorsGetDistinctInstances() throws Exception {
        try (LanguageScope scope = languageScope()) {
            MemoryResolver resolver = new MemoryResolver().module("m", "x: 1");
            Fixture first = fixture(resolver);
            Fixture second = first.otherActor();
            ProtosTask a = first.run(scope, "import(\"m\")", "plat075-c-actor-1.protos");
            ProtosTask b = second.run(scope, "import(\"m\")", "plat075-c-actor-2.protos");
            assertEquals(ProtosTask.State.COMPLETED, a.state());
            assertEquals(ProtosTask.State.COMPLETED, b.state());
            assertNotSame(a.result().orElseThrow(), b.result().orElseThrow());
            assertEquals(2, resolver.loads("m"));
        }
    }

    // ---- Case D: cycles and partial objects ----

    @Test
    void caseD_cycleAnswersTheInitializingInstanceWithoutWaiting() throws Exception {
        try (LanguageScope scope = languageScope()) {
            MemoryResolver resolver =
                    new MemoryResolver()
                            .module("a", "before: 10\nb: import(\"b\")\nseen: b.aBefore\nafter: 20")
                            .module("b", "a: import(\"a\")\naBefore: a.before");
            Fixture f = fixture(resolver);
            ProtosTask task = f.run(scope, "import(\"a\")", "plat075-d-cycle.protos");
            assertEquals(ProtosTask.State.COMPLETED, task.state());
            assertFalse(f.domain.dispatchOne(), "no auxiliary Task may exist");

            ProtosObjectValue a = (ProtosObjectValue) task.result().orElseThrow();
            ProtosObjectValue b = (ProtosObjectValue) a.readLocalSlot("b").orElseThrow();
            assertSame(a, b.readLocalSlot("a").orElseThrow());
            assertEquals("10", decimal(a.readLocalSlot("seen").orElseThrow()));
            assertEquals("20", decimal(a.readLocalSlot("after").orElseThrow()));
            assertEquals(1, resolver.loads("a"));
            assertEquals(1, resolver.loads("b"));
        }
    }

    // ---- Case E: MODULE and SOURCE results ----

    @Test
    void caseE_sourceAnswersItsContext() throws Exception {
        try (LanguageScope scope = languageScope()) {
            MemoryResolver resolver = new MemoryResolver().module("s", "x: 1\n99");
            Fixture f = fixture(resolver);
            ProtosTask task = f.run(scope, "import(\"s\")", "plat075-e-source.protos");
            assertEquals(ProtosTask.State.COMPLETED, task.state());
            ProtosObjectValue context = (ProtosObjectValue) task.result().orElseThrow();
            assertEquals("1", decimal(context.readLocalSlot("x").orElseThrow()));
            assertSame(
                    f.state.lookup(ProtosModuleKey.source("s")).orElseThrow().instance(), context);
        }
    }

    @Test
    void caseE_moduleAnswersItsPublicObjectOnly() throws Exception {
        try (LanguageScope scope = languageScope()) {
            // `peek` re-imports `pkg` while pkg is INITIALIZING and reads the export published
            // by `this.Name:` before pkg's initializer has finished.
            MemoryResolver resolver =
                    new MemoryResolver()
                            .moduleKey("pkg", ProtosModuleKey.module("pkg"),
                                    "hidden: 1\nthis.Name: hidden + 1\npeek: import(\"peek\")\n99")
                            .module("peek", "seenName: import(\"pkg\").Name");
            Fixture f = fixture(resolver);
            ProtosTask task = f.run(scope, "import(\"pkg\")", "plat075-e-module.protos");
            assertEquals(ProtosTask.State.COMPLETED, task.state());
            ProtosObjectValue publicObject = (ProtosObjectValue) task.result().orElseThrow();
            assertEquals(Set.of("Name"), publicObject.localSlotsSnapshot().keySet(),
                    "private initializer bindings and the final value must not be exported");
            assertEquals("2", decimal(publicObject.readLocalSlot("Name").orElseThrow()));
            assertSame(
                    f.state.lookup(ProtosModuleKey.module("pkg")).orElseThrow().instance(),
                    publicObject);
            ProtosObjectValue peek =
                    f.state.lookup(ProtosModuleKey.source("peek")).orElseThrow().instance();
            assertEquals("2", decimal(peek.readLocalSlot("seenName").orElseThrow()),
                    "this.Name: must publish on the public object immediately");
        }
    }

    // ---- Case F: failure and retry ----

    @Test
    void caseF_failureEvictsExactRecordAndRetryIsFresh() throws Exception {
        try (LanguageScope scope = languageScope()) {
            AtomicInteger attempts = new AtomicInteger();
            MemoryResolver resolver =
                    new MemoryResolver()
                            .module("other", "y: 1")
                            .module("retry", "unused")
                            .dynamicSource("retry",
                                    () -> attempts.getAndIncrement() == 0
                                            ? "before: 1\nmissingBinding\n"
                                            : "ok: 42");
            Fixture f = fixture(resolver);
            AtomicReference<ProtosObjectValue> escaped = new AtomicReference<>();
            resolver.injectOnLoad(
                    f.state,
                    (key, instance) -> {
                        if ("retry".equals(key.canonicalId()) && escaped.get() == null) {
                            escaped.set(instance);
                        }
                    });

            ProtosTask other = f.run(scope, "import(\"other\")", "plat075-f-other.protos");
            assertEquals(ProtosTask.State.COMPLETED, other.state());
            ProtosActorModuleState.ModuleRecord otherRecord =
                    f.state.lookup(ProtosModuleKey.source("other")).orElseThrow();

            assertFailed(f.run(scope, "import(\"retry\")", "plat075-f-fail.protos"));
            assertTrue(f.state.lookup(ProtosModuleKey.source("retry")).isEmpty());
            assertSame(otherRecord, f.state.lookup(ProtosModuleKey.source("other")).orElseThrow(),
                    "a different entry must survive the failed attempt");
            ProtosObjectValue partial = escaped.get();
            assertEquals("1", decimal(partial.readLocalSlot("before").orElseThrow()),
                    "effects before the failure are not undone");

            ProtosTask retried = f.run(scope, "import(\"retry\")", "plat075-f-retry.protos");
            assertEquals(ProtosTask.State.COMPLETED, retried.state());
            ProtosObjectValue fresh = (ProtosObjectValue) retried.result().orElseThrow();
            assertNotSame(partial, fresh);
            assertFalse(fresh.hasLocalSlot("before"));
            assertEquals("42", decimal(fresh.readLocalSlot("ok").orElseThrow()));
            assertTrue(partial.hasLocalSlot("before"), "the escaped partial keeps its identity");
            assertEquals(2, resolver.loads("retry"));
        }
    }

    // ---- Case G: C-prime suspension ----

    @Test
    void caseG_suspendedInitializerResumesTheSameAttempt() throws Exception {
        try (LanguageScope scope = languageScope()) {
            MemoryResolver resolver =
                    new MemoryResolver()
                            .module("m", "count: 0\ncount = count + 1\nvalue: work.value()\ncount = count + 1\n99");
            Fixture f = fixture(resolver);
            AtomicReference<ProtosFutureValue> injected = injectFuture(resolver, f, "m");

            ProtosTask task = f.start(scope, "import(\"m\")", "plat075-g-suspend.protos");
            assertTrue(f.domain.dispatchOne());
            assertEquals(ProtosTask.State.SUSPENDED, task.state());
            ProtosActorModuleState.ModuleRecord during =
                    f.state.lookup(ProtosModuleKey.source("m")).orElseThrow();
            assertEquals(ProtosActorModuleState.InitializationState.INITIALIZING, during.state());
            assertEquals("1", decimal(during.instance().readLocalSlot("count").orElseThrow()));

            assertTrue(injected.get().resolve(Long.valueOf(7), f.activation(Map.of())));
            assertTrue(f.domain.dispatchOne(), "suspended import task must resume");
            assertEquals(ProtosTask.State.COMPLETED, task.state());
            ProtosObjectValue module = (ProtosObjectValue) task.result().orElseThrow();
            assertSame(during.instance(), module);
            assertSame(during, f.state.lookup(ProtosModuleKey.source("m")).orElseThrow());
            assertEquals(ProtosActorModuleState.InitializationState.READY, during.state());
            assertEquals("2", decimal(module.readLocalSlot("count").orElseThrow()),
                    "the completed prefix must not be replayed");
            assertEquals("7", decimal(module.readLocalSlot("value").orElseThrow()));
            assertEquals(1, resolver.loads("m"));
        }
    }

    // ---- Case H: cancellation ----

    @Test
    void caseH_cancelledInitializationIsEvictedAndRetryIsFresh() throws Exception {
        try (LanguageScope scope = languageScope()) {
            MemoryResolver resolver = new MemoryResolver().module("cancel", "value: work.value()");
            Fixture f = fixture(resolver);
            AtomicReference<ProtosFutureValue> injected = injectFuture(resolver, f, "cancel");

            ProtosTask cancelled = f.start(scope, "import(\"cancel\")", "plat075-h-cancel.protos");
            assertTrue(f.domain.dispatchOne());
            assertEquals(ProtosTask.State.SUSPENDED, cancelled.state());
            ProtosActorModuleState.ModuleRecord active =
                    f.state.lookup(ProtosModuleKey.source("cancel")).orElseThrow();
            ProtosFutureValue firstFuture = injected.get();

            assertTrue(cancelled.requestCancellation());
            int dispatches = 0;
            while (cancelled.state() != ProtosTask.State.CANCELLED) {
                assertTrue(f.domain.dispatchOne());
                assertTrue(++dispatches <= 3);
            }
            assertTrue(f.state.lookup(ProtosModuleKey.source("cancel")).isEmpty());
            assertEquals(ProtosActorModuleState.InitializationState.INITIALIZING, active.state(),
                    "a cancelled attempt is never marked READY");
            assertFalse(active.instance().hasLocalSlot("value"),
                    "no invalid continuation may run after cancellation");

            ProtosTask retried = f.start(scope, "import(\"cancel\")", "plat075-h-retry.protos");
            assertTrue(f.domain.dispatchOne());
            assertEquals(ProtosTask.State.SUSPENDED, retried.state());
            assertNotSame(firstFuture, injected.get());
            assertTrue(injected.get().resolve(Long.valueOf(11), f.activation(Map.of())));
            assertTrue(f.domain.dispatchOne());
            assertEquals(ProtosTask.State.COMPLETED, retried.state());
            assertNotSame(active.instance(), retried.result().orElseThrow());
            assertEquals(2, resolver.loads("cancel"));
        }
    }

    @Test
    void caseH_guestPolicyOwnsNoCleanup() throws Exception {
        String policy = Files.readString(CORE.resolve("import.protos"));
        assertFalse(policy.contains("ensure"),
                "cancellation cleanup must not depend on a guest ensure");
        assertFalse(policy.contains("handle("),
                "failure eviction must not depend on a guest handler");
    }

    // ---- Case I: specialization safety ----

    @Test
    void caseI_shadowedImportRemainsAnOrdinaryCall() throws Exception {
        try (LanguageScope scope = languageScope()) {
            MemoryResolver resolver = new MemoryResolver();
            Fixture f = fixture(resolver);
            ProtosTask task =
                    f.run(scope, "import: { call: (x) => x }\nimport(\"local\")",
                            "plat075-i-shadowed.protos");
            assertEquals(ProtosTask.State.COMPLETED, task.state());
            assertEquals("local", ((ProtosStringValue) task.result().orElseThrow()).value());
            assertEquals(0, resolver.resolveCalls.get());
        }
    }

    @Test
    void caseI_executeRejectsForgedForeignAndReusedTokens() throws Exception {
        try (LanguageScope scope = languageScope()) {
            MemoryResolver resolver =
                    new MemoryResolver().module("m", "ran: 1").module("n", "ran: 1");
            Fixture owner = fixture(resolver);
            Fixture stranger = owner.otherActor();
            ProtosObjectValue bridge =
                    ProtosStandardImportProtocol.createImportBridge(
                            new ProtosModuleRuntime(resolver));
            Map<String, Object> withBridge = Map.of("bridge", bridge);

            // A forged token is refused.
            assertFailed(owner.run(scope, withBridge, "bridge.execute({})", "plat075-i-forged.protos"));
            // A resolved-key token is not an initialization token.
            assertFailed(owner.run(scope, withBridge,
                    "bridge.execute(bridge.resolve(\"m\"))", "plat075-i-wrong-kind.protos"));

            // Another Actor (and another Task) cannot consume a reserved attempt.
            ProtosTask reserved = owner.run(scope, withBridge,
                    "bridge.begin(bridge.resolve(\"m\"))", "plat075-i-begin.protos");
            assertEquals(ProtosTask.State.COMPLETED, reserved.state());
            Object token = reserved.result().orElseThrow();
            assertTrue(owner.state.lookup(ProtosModuleKey.source("m")).isEmpty());
            assertFailed(stranger.run(scope, Map.of("bridge", bridge, "t", token),
                    "bridge.execute(t)", "plat075-i-stranger.protos"));
            assertFailed(owner.run(scope, Map.of("bridge", bridge, "t", token),
                    "bridge.execute(t)", "plat075-i-other-task.protos"));
            assertTrue(owner.state.lookup(ProtosModuleKey.source("m")).isEmpty(),
                    "rejected claims must create no record");
            assertTrue(stranger.state.lookup(ProtosModuleKey.source("m")).isEmpty());
            assertEquals(0, resolver.loads("m"));

            // In the reserving Task: an impostor holding the same `execute` implementation is not
            // the bridge, the bridge consumes the attempt once, and a second use is refused.
            ProtosObjectValue impostor = new ProtosObjectValue(ProtosObjectValue.rootObject());
            impostor.createLocalSlot("execute", bridge.readLocalSlot("execute").orElseThrow());
            ProtosObjectValue context = owner.prelude.newExecutionContext();
            context.createLocalSlot("bridge", bridge);
            context.createLocalSlot("fake", impostor);
            ProtosTask sameTask = run(scope, owner.domain,
                    owner.prelude.newModuleActivation(owner.state, null, context, owner.domain),
                    "t: bridge.begin(bridge.resolve(\"n\"))\n"
                            + "first: Error.handle(() => fake.execute(t), (e) => \"refused\")\n"
                            + "module: bridge.execute(t)\n"
                            + "second: Error.handle(() => bridge.execute(t), (e) => \"refused\")\n"
                            + "null",
                    "plat075-i-same-task.protos");
            assertEquals(ProtosTask.State.COMPLETED, sameTask.state());
            ProtosActorModuleState.ModuleRecord record =
                    owner.state.lookup(ProtosModuleKey.source("n")).orElseThrow();
            assertEquals("refused",
                    ((ProtosStringValue) context.readLocalSlot("first").orElseThrow()).value());
            assertSame(record.instance(), context.readLocalSlot("module").orElseThrow());
            assertEquals(ProtosActorModuleState.InitializationState.READY, record.state());
            assertEquals("refused",
                    ((ProtosStringValue) context.readLocalSlot("second").orElseThrow()).value());
            assertEquals(1, resolver.loads("n"));
        }
    }

    @Test
    void caseI_bridgeIsNotPublished() throws Exception {
        try (LanguageScope scope = languageScope()) {
            MemoryResolver resolver = new MemoryResolver();
            Fixture f = fixture(resolver);
            assertTrue(f.prelude.bindings()
                    .readLocalSlot(ProtosStandardImportProtocol.IMPORT_BRIDGE_SLOT).isEmpty());
            ProtosObjectValue facility =
                    (ProtosObjectValue) f.prelude.bindings().readLocalSlot("import").orElseThrow();
            assertEquals(Set.of("call"), facility.localSlotsSnapshot().keySet());
            assertTrue(facility.isFrozen());
            assertFailed(f.run(scope, "_coreImportBridge", "plat075-i-unreachable.protos"));
        }
    }

    // ---- Case J: authority and FOREIGN ----

    @Test
    void caseJ_inspectAuthorityIsCheckedBeforeACacheHit() throws Exception {
        try (LanguageScope scope = languageScope()) {
            MemoryResolver resolver =
                    new MemoryResolver().moduleKey("std:inspect",
                            ProtosInspectFacility.MODULE_KEY, "unused");
            Fixture f = fixture(resolver);
            ProtosObjectValue cached = new ProtosObjectValue(ProtosObjectValue.rootObject());
            ProtosActorModuleState.ModuleRecord record =
                    new ProtosActorModuleState.ModuleRecord(cached);
            record.markReady();
            f.state.put(ProtosInspectFacility.MODULE_KEY, record);

            ProtosTask denied = f.run(scope, "import(\"std:inspect\")", "plat075-j-inspect.protos");
            assertFailed(denied);
            assertSame(record, f.state.lookup(ProtosInspectFacility.MODULE_KEY).orElseThrow());
            assertEquals(0, resolver.totalLoads());
        }
    }

    /**
     * The private bridge reaches FOREIGN only through the shared {@code ProtosModuleRuntime}
     * preparation (exercised end to end by {@link
     * #adversarial_foreignAcquisitionThroughGuestPolicy()}); this test pins that the bridge owns
     * no separate foreign loader entry.
     */
    @Test
    void foreignAcquisitionHasNoSeparateBridgeLoader() throws Exception {
        for (java.lang.reflect.Method method : ProtosStandardImportProtocol.class.getDeclaredMethods()) {
            assertFalse(method.getName().toLowerCase(java.util.Locale.ROOT).contains("foreign"),
                    "the import bridge must not own a foreign loader: " + method.getName());
        }
    }

    // ---- Adversarial feasibility cases ----

    /**
     * PLAT051 destination-local Standard Library access resolves the Process module runtime from
     * the Prelude's {@code import.call}. With the source-backed policy that call is guest code, so
     * this exercises exactly that consumer, not {@code importModule}.
     */
    @Test
    void adversarial_semanticTransferWorksWithSourceBackedPrelude() throws Exception {
        try (LanguageScope scope = languageScope()) {
            MemoryResolver resolver =
                    new MemoryResolver()
                            .module("m", "x: 1\n99")
                            .moduleKey("std:inspect", ProtosInspectFacility.MODULE_KEY, "unused");
            Fixture f = fixture(resolver);
            ProtosModuleKey key = ProtosModuleKey.source("m");

            ProtosObjectValue loaded =
                    ProtosModuleRuntime.loadCanonicalModuleThroughPreludeForRuntime(
                            key, f.activation(Map.of()));
            ProtosActorModuleState.ModuleRecord record = f.state.lookup(key).orElseThrow();
            assertSame(record.instance(), loaded);
            assertEquals(ProtosActorModuleState.InitializationState.READY, record.state());
            assertEquals("1", decimal(loaded.readLocalSlot("x").orElseThrow()));
            assertEquals(1, resolver.loads("m"));

            // Actor-local cache: the same Actor reuses the instance without reloading.
            assertSame(loaded,
                    ProtosModuleRuntime.loadCanonicalModuleThroughPreludeForRuntime(
                            key, f.activation(Map.of())));
            assertEquals(1, resolver.loads("m"));

            // Another Actor gets its own instance.
            Fixture other = f.otherActor();
            ProtosObjectValue otherLoaded =
                    ProtosModuleRuntime.loadCanonicalModuleThroughPreludeForRuntime(
                            key, other.activation(Map.of()));
            assertNotSame(loaded, otherLoaded);
            assertEquals(2, resolver.loads("m"));

            // Authority is checked before a cached instance is observed.
            ProtosActorModuleState.ModuleRecord inspect =
                    new ProtosActorModuleState.ModuleRecord(
                            new ProtosObjectValue(ProtosObjectValue.rootObject()));
            inspect.markReady();
            f.state.put(ProtosInspectFacility.MODULE_KEY, inspect);
            assertThrows(ProtosSignalException.class,
                    () -> ProtosModuleRuntime.loadCanonicalModuleThroughPreludeForRuntime(
                            ProtosInspectFacility.MODULE_KEY, f.activation(Map.of())));
            assertSame(inspect, f.state.lookup(ProtosInspectFacility.MODULE_KEY).orElseThrow());
        }
    }

    /**
     * FOREIGN acquisition through the source-backed {@code import.call}, on a hosted Process whose
     * registry routes the {@code test} scheme to a real {@link ProtosForeignModuleProvider}. The
     * provider double and Process fixture mirror the private ones of {@code
     * ProtosForeignModuleImportTest} (they are not reachable from this class); only the Prelude
     * differs, and acquisition goes through {@code ProtosForeignModuleImports}.
     */
    @Test
    void adversarial_foreignAcquisitionThroughGuestPolicy() throws Exception {
        ForeignModules test = new ForeignModules("test");
        try (ForeignFixture fixture =
                new ForeignFixture(new ProtosModuleRuntimeTest.MemoryResolver(), test)) {
            ProtosClosureValue guestCall = importCall(fixture.prelude);
            assertTrue(guestCall.nativeBody().isEmpty(), "import.call must be the guest policy");

            ProtosModuleKey foo = ProtosForeignModuleKey.encode("test", "foo");
            ProtosObjectValue first = fixture.importValue("test:foo");
            ProtosObjectValue alias = fixture.importValue("test:alias-of-foo");
            ProtosObjectValue again = fixture.importValue("test:foo");
            assertSame(first, alias);
            assertSame(first, again);
            assertEquals(1, test.acquisitions("foo"));
            ProtosActorModuleState.ModuleRecord record =
                    fixture.root.actorModuleState().lookup(foo).orElseThrow();
            assertSame(first, record.instance());
            assertEquals(ProtosActorModuleState.InitializationState.READY, record.state());
            ProtosForeignModuleFacadeValue.Attachment attachment =
                    assertInstanceOf(ProtosForeignModuleFacadeValue.class, first)
                            .attachmentForRuntime()
                            .orElseThrow();
            assertEquals("target:foo", attachment.target());

            // Acquisition failure evicts the exact record; a retry creates a fresh facade.
            ProtosModuleKey flaky = ProtosForeignModuleKey.encode("test", "flaky");
            AtomicInteger flakyAttempts = new AtomicInteger();
            test.hooks.put(
                    "flaky",
                    (session, target) -> {
                        if (flakyAttempts.getAndIncrement() == 0) {
                            throw new IllegalStateException("injected acquisition failure");
                        }
                        return "target:flaky";
                    });
            assertEquals(
                    ProtosExecutionOutcome.State.FAILED,
                    fixture.importOutcome("test:flaky").state());
            assertTrue(fixture.root.actorModuleState().lookup(flaky).isEmpty());
            assertSame(record, fixture.root.actorModuleState().lookup(foo).orElseThrow());

            ProtosObjectValue retried = fixture.importValue("test:flaky");
            assertNotSame(first, retried);
            assertEquals("target:flaky",
                    assertInstanceOf(ProtosForeignModuleFacadeValue.class, retried)
                            .attachmentForRuntime().orElseThrow().target());
            assertEquals(2, test.acquisitions("flaky"));
            assertSame(retried,
                    fixture.root.actorModuleState().lookup(flaky).orElseThrow().instance());
        }
    }

    /**
     * A Task that requested an attempt with {@code begin} is cancelled while suspended before
     * {@code execute}, so the structured import protocol never owns the attempt.
     *
     * <p>Limitation: the shipped {@code import.call} runs {@code begin} and {@code execute}
     * synchronously with no suspension point or guest code between them, so a valid flow through
     * the policy alone cannot be terminated in that window. This case therefore drives the
     * private bridge directly (as caseI does) and uses real Task cancellation at a real Future
     * suspension; nothing removes the record by hand.
     */
    @Test
    void adversarial_abandonedBeginCannotPoisonActorCache() throws Exception {
        try (LanguageScope scope = languageScope()) {
            MemoryResolver resolver = new MemoryResolver().module("m", "ran: 1");
            Fixture f = fixture(resolver);
            ProtosObjectValue bridge =
                    ProtosStandardImportProtocol.createImportBridge(
                            new ProtosModuleRuntime(resolver));
            ProtosFutureValue work = new ProtosFutureValue(f.prelude.futurePrototype(), f.domain);

            ProtosTask abandoned =
                    executeTask(
                            f.domain,
                            f.activation(Map.of("bridge", bridge, "work", work)),
                            lowerRoot(scope.language(),
                                    "t: bridge.begin(bridge.resolve(\"m\"))\n"
                                            + "work.value()\n"
                                            + "bridge.execute(t)",
                                    "plat075-adv-abandoned.protos"));
            assertTrue(f.domain.dispatchOne());
            assertEquals(ProtosTask.State.SUSPENDED, abandoned.state());
            assertTrue(f.state.lookup(ProtosModuleKey.source("m")).isEmpty(),
                    "begin alone must not insert a ModuleRecord");

            assertTrue(abandoned.requestCancellation());
            int dispatches = 0;
            while (abandoned.state() != ProtosTask.State.CANCELLED) {
                assertTrue(f.domain.dispatchOne());
                assertTrue(++dispatches <= 3);
            }
            assertTrue(f.state.lookup(ProtosModuleKey.source("m")).isEmpty(),
                    "an abandoned reservation must not remain in the Actor cache");
            assertEquals(0, resolver.loads("m"), "the abandoned attempt never ran");

            ProtosTask later = f.run(scope, "import(\"m\")", "plat075-adv-later.protos");
            assertEquals(ProtosTask.State.COMPLETED, later.state());
            ProtosObjectValue fresh = (ProtosObjectValue) later.result().orElseThrow();
            assertEquals("1", decimal(fresh.readLocalSlot("ran").orElseThrow()));
            assertEquals(1, resolver.loads("m"));
            assertEquals(
                    ProtosActorModuleState.InitializationState.READY,
                    f.state.lookup(ProtosModuleKey.source("m")).orElseThrow().state());
        }
    }

    // ---- fixtures ----

    /** Hosted Process with a provider registry over the production source-backed Prelude. */
    private static final class ForeignFixture implements AutoCloseable {
        final ProtosPrelude prelude;
        final ProtosStandaloneProcessBootstrap.Result bootstrap;
        final ProtosPolyglotRuntimeHost host;
        final ProtosPolyglotProcessContext context;
        final ProtosActivation root;

        ForeignFixture(ProtosModuleRuntimeTest.MemoryResolver resolver, ForeignModules... providers)
                throws Exception {
            this.prelude = productionCore(resolver);
            this.bootstrap =
                    ProtosStandaloneProcessBootstrap.create(
                            prelude, List.of(), exactEnvironmentDomain(), List.of(),
                            null, null, null, null, null, null, null);
            List<ProtosForeignProviderDescriptor> descriptors = new ArrayList<>();
            for (ForeignModules provider : providers) {
                descriptors.add(provider.descriptor());
            }
            this.host =
                    ProtosPolyglotRuntimeHost.openWithForeignProvidersForTesting(
                            ProtosForeignProviderRegistry.of(descriptors));
            this.context =
                    host.hostProcess(
                            bootstrap.process(),
                            InputStream.nullInputStream(),
                            OutputStream.nullOutputStream(),
                            OutputStream.nullOutputStream());
            this.root = bootstrap.activation();
        }

        ProtosExecutionOutcome importOutcome(String specifier) {
            ProtosActivation activation =
                    prelude.newModuleActivation(
                            root.actorModuleState(),
                            ProtosModuleKey.source("entry"),
                            prelude.newExecutionContext(),
                            root.executionDomain());
            return context.executeModuleSource(
                    ProtosModuleSource.fromCharacters(
                            ProtosModuleKey.source("entry"), "import(\"" + specifier + "\")"),
                    activation);
        }

        ProtosObjectValue importValue(String specifier) {
            ProtosExecutionOutcome outcome = importOutcome(specifier);
            assertEquals(ProtosExecutionOutcome.State.COMPLETED, outcome.state());
            return assertInstanceOf(ProtosObjectValue.class, outcome.value());
        }

        @Override
        public void close() {
            try {
                bootstrap.process().requestTerminationForRuntime();
            } finally {
                host.close();
            }
        }
    }

    @FunctionalInterface
    private interface AcquireHook {
        Object acquire(ProtosForeignProviderSession session, String canonicalTarget)
                throws Exception;
    }

    /** Inert provider double mirroring {@code ProtosForeignModuleImportTest.TestModules}. */
    private static final class ForeignModules
            implements ProtosForeignModuleProvider, ProtosForeignProviderFactory {
        final ProtosForeignProviderId id;
        final String scheme;
        final Map<String, Integer> acquisitions = new ConcurrentHashMap<>();
        final Map<String, AcquireHook> hooks = new ConcurrentHashMap<>();

        ForeignModules(String scheme) {
            this.id = new ProtosForeignProviderId("provider-" + scheme);
            this.scheme = scheme;
        }

        ProtosForeignProviderDescriptor descriptor() {
            return new ProtosForeignProviderDescriptor(
                    id,
                    ProtosForeignProviderExecutionProfile.RESTRICTED_IN_PROCESS,
                    ProtosForeignInertRestriction.zeroAuthority(),
                    this,
                    Optional.of(new ProtosForeignImportRoute(scheme, this)),
                    ProtosForeignValueAdapter.opaque("unknown"));
        }

        int acquisitions(String target) {
            return acquisitions.getOrDefault(target, 0);
        }

        @Override
        public String canonicalTarget(String exactTarget) throws Exception {
            if (exactTarget.startsWith("missing")) {
                throw new java.io.IOException("no such test target");
            }
            return exactTarget.equals("alias-of-foo") ? "foo" : exactTarget;
        }

        @Override
        public Object acquireTarget(ProtosForeignProviderSession session, String canonicalTarget)
                throws Exception {
            acquisitions.merge(canonicalTarget, 1, Integer::sum);
            AcquireHook hook = hooks.get(canonicalTarget);
            return hook == null ? "target:" + canonicalTarget : hook.acquire(session, canonicalTarget);
        }

        @Override
        public ProtosForeignProviderCompartment openCompartment() {
            return new ProtosForeignProviderCompartment() {
                @Override
                public ProtosForeignProviderId providerId() {
                    return id;
                }

                @Override
                public ProtosForeignProviderSession openSession() {
                    return new ProtosForeignProviderSession() {
                        @Override
                        public void close() {}
                    };
                }

                @Override
                public void close() {}
            };
        }
    }

    private static ProtosEnvironmentValue.NativeNameDomain exactEnvironmentDomain() {
        return new ProtosEnvironmentValue.NativeNameDomain() {
            @Override
            public boolean sameCapturedName(String left, String right) {
                return left.equals(right);
            }

            @Override
            public boolean isQueryRepresentable(String name) {
                return !name.contains("=") && name.indexOf('\0') < 0;
            }

            @Override
            public boolean matchesQuery(String captured, String query) {
                return captured.equals(query);
            }
        };
    }

    private static void assertFailed(ProtosTask task) {
        assertEquals(ProtosTask.State.FAILED, task.state());
        assertTrue(task.failure().isPresent());
    }

    private static String decimal(Object value) {
        return ProtosNumericValueSupport.integerDecimalText(value);
    }

    private static ProtosClosureValue importCall(ProtosPrelude prelude) {
        ProtosObjectValue facility =
                (ProtosObjectValue) prelude.bindings().readLocalSlot("import").orElseThrow();
        return (ProtosClosureValue) facility.readLocalSlot("call").orElseThrow();
    }

    private static ProtosPrelude productionCore(ProtosModuleResolver resolver) throws Exception {
        return new ProtosCoreBootstrap().bootstrap(CORE, resolver);
    }

    private static Fixture fixture(MemoryResolver resolver) throws Exception {
        return new Fixture(
                productionCore(resolver),
                new ProtosActorExecutionDomain(),
                new ProtosActorModuleState());
    }

    private static AtomicReference<ProtosFutureValue> injectFuture(
            MemoryResolver resolver, Fixture f, String moduleId) {
        AtomicReference<ProtosFutureValue> injected = new AtomicReference<>();
        resolver.injectOnLoad(
                f.state,
                (key, instance) -> {
                    if (!moduleId.equals(key.canonicalId())) {
                        return;
                    }
                    ProtosFutureValue future =
                            new ProtosFutureValue(f.prelude.futurePrototype(), f.domain);
                    injected.set(future);
                    instance.createLocalSlot("work", future);
                });
        return injected;
    }

    private record Fixture(
            ProtosPrelude prelude, ProtosActorExecutionDomain domain, ProtosActorModuleState state) {
        Fixture otherActor() {
            return new Fixture(prelude, new ProtosActorExecutionDomain(), new ProtosActorModuleState());
        }

        ProtosActivation activation(Map<String, ?> locals) {
            return ProtosPlat075ProtosFirstImportFeasibilityTest.activation(
                    prelude, domain, state, locals);
        }

        ProtosTask start(LanguageScope scope, String characters, String name) throws Exception {
            return executeTask(domain, activation(Map.of()), lowerRoot(scope.language(), characters, name));
        }

        ProtosTask run(LanguageScope scope, String characters, String name) throws Exception {
            return run(scope, Map.of(), characters, name);
        }

        ProtosTask run(LanguageScope scope, Map<String, ?> locals, String characters, String name)
                throws Exception {
            return ProtosPlat075ProtosFirstImportFeasibilityTest.run(
                    scope, domain, activation(locals), characters, name);
        }
    }

    private static ProtosTask run(
            LanguageScope scope,
            ProtosActorExecutionDomain domain,
            ProtosActivation activation,
            String characters,
            String name)
            throws Exception {
        ProtosTask task = executeTask(domain, activation, lowerRoot(scope.language(), characters, name));
        assertTrue(domain.dispatchOne());
        return task;
    }

    private static ProtosTask executeTask(
            ProtosActorExecutionDomain domain,
            ProtosActivation activation,
            ProtosBytecodeRootNode root) {
        return domain.createTask(
                null,
                current ->
                        ProtosBytecodeTaskExecution.execute(
                                current,
                                root.getCallTarget(),
                                activation));
    }

    private static ProtosActivation activation(
            ProtosPrelude prelude,
            ProtosActorExecutionDomain domain,
            ProtosActorModuleState state,
            Map<String, ?> locals) {
        ProtosObjectValue context = prelude.newExecutionContext();
        locals.forEach(context::createLocalSlot);
        return prelude.newModuleActivation(state, null, context, domain);
    }

    private static LanguageScope languageScope() {
        Context context = Context.newBuilder(ProtosLanguage.ID).build();
        context.initialize(ProtosLanguage.ID);
        context.enter();
        return new LanguageScope(context, LANGUAGE_REF.get(null));
    }

    private static CanonicalSequence canonicalize(String characters) {
        return (CanonicalSequence)
                new Canonicalizer().canonicalize(new ProtosParser(characters).parseProgram());
    }

    private static ProtosBytecodeRootNode lowerRoot(
            ProtosLanguage language, String characters, String sourceName) throws Exception {
        Source source = Source.newBuilder(ProtosLanguage.ID, characters, sourceName).build();
        return new CanonicalToBytecodeLowerer(language, source).lowerRoot(canonicalize(characters));
    }

    private record LanguageScope(Context context, ProtosLanguage language)
            implements AutoCloseable {
        @Override
        public void close() {
            context.leave();
            context.close();
        }
    }

    /**
     * In-memory resolver. {@code injectOnLoad} runs while the record is INITIALIZING and before
     * the body executes, receiving the record's import result.
     */
    private static final class MemoryResolver implements ProtosModuleResolver {
        final Map<String, String> sources = new HashMap<>();
        final Map<String, ProtosModuleKey> keys = new HashMap<>();
        final Map<String, java.util.function.Supplier<String>> dynamic = new HashMap<>();
        final Map<String, Integer> loadCounts = new HashMap<>();
        final AtomicInteger resolveCalls = new AtomicInteger();
        private ProtosActorModuleState injectionState;
        private java.util.function.BiConsumer<ProtosModuleKey, ProtosObjectValue> loadInjection;

        MemoryResolver module(String id, String source) {
            sources.put(id, source);
            keys.put(id, ProtosModuleKey.source(id));
            return this;
        }

        MemoryResolver moduleKey(String specifier, ProtosModuleKey key, String source) {
            sources.put(key.canonicalId(), source);
            keys.put(specifier, key);
            return this;
        }

        MemoryResolver alias(String specifier, String target) {
            keys.put(specifier, keys.get(target));
            return this;
        }

        MemoryResolver dynamicSource(String id, java.util.function.Supplier<String> source) {
            dynamic.put(id, source);
            return this;
        }

        MemoryResolver injectOnLoad(
                ProtosActorModuleState state,
                java.util.function.BiConsumer<ProtosModuleKey, ProtosObjectValue> injection) {
            this.injectionState = java.util.Objects.requireNonNull(state, "state");
            this.loadInjection = java.util.Objects.requireNonNull(injection, "injection");
            return this;
        }

        int loads(String id) {
            return loadCounts.getOrDefault(id, 0);
        }

        int totalLoads() {
            return loadCounts.values().stream().mapToInt(Integer::intValue).sum();
        }

        @Override
        public ProtosModuleKey resolve(String exactSpecifier, Optional<ProtosModuleKey> importingModule)
                throws Exception {
            resolveCalls.incrementAndGet();
            ProtosModuleKey key = keys.get(exactSpecifier);
            if (key == null) {
                throw new java.io.IOException("not found");
            }
            return key;
        }

        @Override
        public ProtosModuleSource loadSource(ProtosModuleKey key) throws Exception {
            loadCounts.merge(key.canonicalId(), 1, Integer::sum);
            java.util.function.Supplier<String> generated = dynamic.get(key.canonicalId());
            String source = generated != null ? generated.get() : sources.get(key.canonicalId());
            if (source == null) {
                throw new java.io.IOException("not found");
            }
            if (loadInjection != null) {
                ProtosActorModuleState.ModuleRecord record =
                        injectionState.lookup(key)
                                .orElseThrow(() -> new IllegalStateException(
                                        "module source loaded before cache-before-execute record"));
                loadInjection.accept(key, record.instance());
            }
            return ProtosModuleSource.fromCharacters(key, source);
        }
    }
}
