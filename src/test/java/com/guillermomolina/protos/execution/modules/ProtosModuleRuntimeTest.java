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

import static org.junit.jupiter.api.Assertions.*;

import com.guillermomolina.protos.runtime.ProtosActivation;
import com.guillermomolina.protos.runtime.ProtosClosureValue;
import com.guillermomolina.protos.runtime.modules.ProtosModuleKey;
import com.guillermomolina.protos.runtime.ProtosObjectValue;
import com.guillermomolina.protos.runtime.ProtosPrelude;
import com.guillermomolina.protos.runtime.ProtosSignalException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ProtosModuleRuntimeTest {
    private static final Path CORE = Path.of("protos", "lib", "core");

    @Test
    void coreImportFacilityIsFrozenWithOnlySourceBackedCall()
            throws Exception {
        MemoryResolver resolver = new MemoryResolver();
        ProtosPrelude prelude = new ProtosCoreBootstrap().bootstrap(CORE, resolver);
        ProtosObjectValue facility =
                assertInstanceOf(
                        ProtosObjectValue.class,
                        prelude.bindings().readLocalSlot("import").orElseThrow());

        assertSame(ProtosObjectValue.rootObject(), facility.parent().orElseThrow());
        assertTrue(facility.isFrozen());
        assertEquals(java.util.Set.of("call"), facility.localSlotsSnapshot().keySet());
        ProtosClosureValue call =
                assertInstanceOf(
                        ProtosClosureValue.class,
                        facility.readLocalSlot("call").orElseThrow());
        assertTrue(call.nativeBody().isEmpty(), "public import.call must not be native");
        assertNotNull(call.definition(), "public import.call must be source-defined");
        assertTrue(call.executionPlan().isPresent());
        assertSame(
                ProtosStandardImportProtocol.runtimeForPrelude(prelude),
                prelude.moduleRuntimeForRuntime());
        assertNotNull(prelude.moduleRuntimeForRuntime());

        // Installation only validates and freezes the source-created facility; it never supplies
        // a native call of its own.
        ProtosObjectValue supplied =
                new ProtosObjectValue(ProtosObjectValue.rootObject());
        assertThrows(
                IllegalArgumentException.class,
                () -> ProtosStandardImportProtocol.installImportFacility(supplied));
        assertTrue(supplied.isOpen());
        assertTrue(supplied.localSlotsSnapshot().isEmpty());
    }

    @Test
    void semanticStringImportsAndStringLikeObjectIsRejectedBeforeResolver() throws Exception {
        MemoryResolver resolver = new MemoryResolver().source("m", "value: 7");
        ProtosPrelude prelude = new ProtosCoreBootstrap().bootstrap(CORE, resolver);
        ProtosActivation actor = prelude.newModuleActivation();

        Object module =
                ProtosTestExecutionSupport.evaluate(
                        "import(\"m\")",
                        actor);
        assertInstanceOf(ProtosObjectValue.class, module);
        assertEquals(1, resolver.resolveCalls.get());

        assertThrows(
                ProtosSignalException.class,
                () ->
                        ProtosTestExecutionSupport.evaluate(
                                "fake: String {}\nimport(fake)",
                                actor));
        assertEquals(1, resolver.resolveCalls.get(), "invalid semantic domain must fail before resolution");
    }

    @Test
    void moduleInstanceIsContextWithExplicitNamespaceAndNoImporterInheritance()
            throws Exception {
        MemoryResolver resolver =
                new MemoryResolver()
                        .source("leaf", "secret: 9")
                        .source(
                                "mid",
                                "dep: import(\"leaf\")\n"
                                        + "notInherited: Error.handle(() => importerOnly, "
                                        + "(caught) => caught.parent() === SlotNotFound)");
        ProtosPrelude prelude = new ProtosCoreBootstrap().bootstrap(CORE, resolver);
        ProtosActivation actor = prelude.newModuleActivation();

        Object result =
                ProtosTestExecutionSupport.evaluate(
                        "importerOnly: 7\n"
                                + "mid: import(\"mid\")\n"
                                + "transitiveMissing: Error.handle(() => mid.secret, "
                                + "(caught) => caught.parent() === SlotNotFound)\n"
                                + "(mid.parent() === Context).and() {\n"
                                + "    (mid.dep.secret == 9).and() {\n"
                                + "        mid.notInherited.and() { transitiveMissing }\n"
                                + "    }\n"
                                + "}",
                        actor);

        assertSame(Boolean.TRUE, result);
        assertEquals(java.util.List.of("<root>", "mid"), resolver.resolvedFrom);
    }

    @Test
    void exactSpecifierTextAndCanonicalIdentityAreDistinctBoundaries() throws Exception {
        MemoryResolver resolver =
                new MemoryResolver()
                        .source("empty", "value: 2")
                        .alias("", "empty")
                        .source("a", "value: 1")
                        .alias("β/../A", "a")
                        .alias("alias-a", "a")
                        .source("b", "value: 1");
        ProtosPrelude prelude = new ProtosCoreBootstrap().bootstrap(CORE, resolver);
        ProtosActivation actor = prelude.newModuleActivation();

        Object result =
                ProtosTestExecutionSupport.evaluate(
                        "empty: import(\"\")\n"
                                + "a: import(\"β/../A\")\n"
                                + "same: import(\"alias-a\")\n"
                                + "b: import(\"b\")\n"
                                + "(empty.value == 2).and() {\n"
                                + "    (a === same).and() { a !== b }\n"
                                + "}",
                        actor);

        assertSame(Boolean.TRUE, result);
        assertEquals(
                java.util.List.of("", "β/../A", "alias-a", "b"),
                resolver.resolvedSpecifiers);
        assertEquals(1, resolver.loads("empty"));
        assertEquals(1, resolver.loads("a"));
        assertEquals(1, resolver.loads("b"));
    }

    @Test
    void canonicalKeyCachesBeforeExecutionSupportsCyclesAndSingleEvaluation() throws Exception {
        MemoryResolver resolver = new MemoryResolver()
                .alias("alias-a", "a")
                .source("a", "before: 10\nb: import(\"b\")\naSeenByB: b.aBefore\nafter: 20")
                .source("b", "a: import(\"a\")\naBefore: a.before");
        ProtosPrelude prelude = new ProtosCoreBootstrap().bootstrap(CORE, resolver);
        ProtosActivation actor = prelude.newModuleActivation();
        ProtosObjectValue a1 =
                (ProtosObjectValue)
                        ProtosTestExecutionSupport.evaluate(
                                "import(\"a\")",
                                actor);
        ProtosObjectValue a2 =
                (ProtosObjectValue)
                        ProtosTestExecutionSupport.evaluate(
                                "import(\"alias-a\")",
                                actor);

        assertSame(a1, a2, "distinct spellings resolving to one ModuleKey must share one Actor-local instance");
        assertEquals(1, resolver.loads("a"), "A must execute/load once even through A -> B -> A");
        assertEquals(1, resolver.loads("b"));
        assertEquals(String.valueOf("10"), com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerDecimalText(a1.readLocalSlot("aSeenByB").orElseThrow()));
        assertTrue(a1.hasLocalSlot("after"));
    }

    @Test
    void cachesAreActorLocal() throws Exception {
        MemoryResolver resolver = new MemoryResolver().source("m", "value: 1");
        ProtosPrelude prelude = new ProtosCoreBootstrap().bootstrap(CORE, resolver);
        ProtosActivation actorA = prelude.newModuleActivation();
        ProtosActivation actorB = prelude.newModuleActivation();

        Object a1 =
                ProtosTestExecutionSupport.evaluate(
                        "import(\"m\")",
                        actorA);
        Object a2 =
                ProtosTestExecutionSupport.evaluate(
                        "import(\"m\")",
                        actorA);
        Object b1 =
                ProtosTestExecutionSupport.evaluate(
                        "import(\"m\")",
                        actorB);

        assertSame(a1, a2);
        assertNotSame(a1, b1);
        assertEquals(2, resolver.loads("m"));
    }

    @Test
    void failedInitializationIsEvictedAndRetryCreatesFreshInstance() throws Exception {
        AtomicInteger attempt = new AtomicInteger();
        MemoryResolver resolver = new MemoryResolver() {
            @Override
            public ProtosModuleSource loadSource(ProtosModuleKey key) {
                loadCounts.merge(key.canonicalId(), 1, Integer::sum);
                return ProtosModuleSource.fromCharacters(key, attempt.getAndIncrement() == 0 ? "broken: (" : "ok: 42");
            }
        }.source("retry", "ignored");
        ProtosPrelude prelude = new ProtosCoreBootstrap().bootstrap(CORE, resolver);
        ProtosActivation actor = prelude.newModuleActivation();
        assertThrows(
                ProtosSignalException.class,
                () ->
                        ProtosTestExecutionSupport.evaluate(
                                "import(\"retry\")",
                                actor));
        ProtosObjectValue recovered =
                (ProtosObjectValue)
                        ProtosTestExecutionSupport.evaluate(
                                "import(\"retry\")",
                                actor);
        assertTrue(recovered.hasLocalSlot("ok"));
        assertEquals(2, resolver.loads("retry"), "retry must re-load/re-evaluate after eviction");
    }

    @Test
    void unhostedCanonicalModuleExecutesThroughBytecodePublicParse() throws Exception {
        MemoryResolver resolver =
                new MemoryResolver().source("direct", "value: 7");
        ProtosPrelude prelude =
                new ProtosCoreBootstrap().bootstrap(CORE, resolver);
        ProtosActivation actor = prelude.newModuleActivation();

        ProtosObjectValue module =
                new ProtosModuleRuntime(resolver)
                        .loadCanonicalModule(
                                ProtosModuleKey.source("direct"),
                                actor);

        Long value =
                assertInstanceOf(
                        Long.class,
                        module.readLocalSlot("value").orElseThrow());
        assertEquals(Long.toString(7), com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerDecimalText(value));
        assertEquals(1, resolver.loads("direct"));
    }

    @Test
    void resolverAndSourceFailuresBecomeCoreErrorsNotHostExceptions() throws Exception {
        ProtosModuleResolver resolver = new ProtosModuleResolver() {
            @Override public ProtosModuleKey resolve(String s, Optional<ProtosModuleKey> from) throws Exception {
                throw new java.io.IOException("host detail");
            }
            @Override public ProtosModuleSource loadSource(ProtosModuleKey key) { return ProtosModuleSource.fromCharacters(key, ""); }
        };
        ProtosPrelude prelude = new ProtosCoreBootstrap().bootstrap(CORE, resolver);
        assertThrows(
                ProtosSignalException.class,
                () ->
                        ProtosTestExecutionSupport.evaluate(
                                "import(\"x\")",
                                prelude.newModuleActivation()));
    }

    private static Object eval(String source, ProtosActivation activation) {
        return ProtosTestExecutionSupport.evaluate(source, activation);
    }

    private static void assertProtosTrue(String source, ProtosActivation activation) {
        assertSame(Boolean.TRUE, eval(source, activation), source);
    }

    @Test
    void d242ModulePublishesOnlyThisSlotsWhileSourceExposesEveryBinding() throws Exception {
        MemoryResolver resolver =
                new MemoryResolver()
                        .source("range-source", "privateHelper: 42\nRange: { value: 1 }")
                        .publicModule(
                                "range",
                                "implementation: import(\"range-source\")\n"
                                        + "this.Range: implementation.slotValue(\"Range\")\n"
                                        + "\"final value never replaces the module\"");
        ProtosPrelude prelude = new ProtosCoreBootstrap().bootstrap(CORE, resolver);
        ProtosActivation actor = prelude.newModuleActivation();

        ProtosObjectValue module =
                assertInstanceOf(ProtosObjectValue.class, eval("import(\"range\")", actor));
        assertEquals(java.util.Set.of("Range"), module.localSlotsSnapshot().keySet());
        assertFalse(module instanceof com.guillermomolina.protos.runtime.ProtosExecutionContextValue,
                "MODULE public object is an ordinary object, not an execution Context");

        ProtosObjectValue source =
                assertInstanceOf(ProtosObjectValue.class, eval("import(\"range-source\")", actor));
        assertTrue(source.hasLocalSlot("privateHelper"));
        assertTrue(source.hasLocalSlot("Range"));

        assertProtosTrue(
                "module: import(\"range\")\n"
                        + "source: import(\"range-source\")\n"
                        + "(module !== source).and() {\n"
                        + "    (module.Range === source.Range).and() {\n"
                        + "        (module.parent() === Object).and() {\n"
                        + "            Error.handle(() => module.implementation, "
                        + "(caught) => caught.parent() === SlotNotFound)\n"
                        + "        }\n"
                        + "    }\n"
                        + "}",
                actor);
    }

    @Test
    void d242ModuleKindsAreDistinctCanonicalIdentities() {
        assertNotEquals(ProtosModuleKey.module("x"), ProtosModuleKey.source("x"));
        assertNotEquals(ProtosModuleKey.module("x"), ProtosModuleKey.foreign("x"));
        assertEquals(ProtosModuleKey.module("x"), ProtosModuleKey.module("x"));
        assertTrue(ProtosModuleKey.module("x").isModule());
        assertTrue(ProtosModuleKey.source("x").isSource());
    }

    @Test
    void d242ExportedClosureCapturesPrivateStateAndUsesOrdinaryReceiverBinding() throws Exception {
        MemoryResolver resolver =
                new MemoryResolver()
                        .publicModule(
                                "counter",
                                "hidden: 0\n"
                                        + "this.bump: () => { hidden = hidden + 1\nhidden }\n"
                                        + "this.receiver: () => this\n"
                                        + "this.contextIsPrivate: context !== this\n"
                                        + "this.thisIsPublic: this.hasSlot(\"bump\")");
        ProtosPrelude prelude = new ProtosCoreBootstrap().bootstrap(CORE, resolver);
        ProtosActivation actor = prelude.newModuleActivation();

        assertProtosTrue(
                "counter: import(\"counter\")\n"
                        + "first: counter.bump()\n"
                        + "second: counter.bump()\n"
                        + "extracted: counter.receiver\n"
                        + "((first == 1).and() { second == 2 }).and() {\n"
                        + "    (counter.receiver() === counter).and() {\n"
                        + "        (extracted() === counter).and() {\n"
                        + "            counter.contextIsPrivate.and() {\n"
                        + "                counter.thisIsPublic.and() {\n"
                        + "                    counter.hasSlot(\"hidden\") == false\n"
                        + "                }\n"
                        + "            }\n"
                        + "        }\n"
                        + "    }\n"
                        + "}",
                actor);
    }

    @Test
    void d242InitializerLookupIsPrivateThenPublicThenPrelude() throws Exception {
        MemoryResolver resolver =
                new MemoryResolver()
                        .publicModule(
                                "chain",
                                "this.shared: \"public\"\n"
                                        + "this.fromPublic: shared\n"
                                        + "shared: \"private\"\n"
                                        + "this.fromPrivate: shared\n"
                                        + "this.fromPrelude: Object\n"
                                        + "this.late: () => lateName\n"
                                        + "this.lateName: \"published later\"");
        ProtosPrelude prelude = new ProtosCoreBootstrap().bootstrap(CORE, resolver);
        ProtosActivation actor = prelude.newModuleActivation();

        assertProtosTrue(
                "chain: import(\"chain\")\n"
                        + "(chain.fromPublic == \"public\").and() {\n"
                        + "    (chain.fromPrivate == \"private\").and() {\n"
                        + "        (chain.fromPrelude === Object).and() {\n"
                        + "            (chain.late() == \"published later\").and() {\n"
                        + "                chain.shared == \"public\"\n"
                        + "            }\n"
                        + "        }\n"
                        + "    }\n"
                        + "}",
                actor);
    }

    @Test
    void d242PublishedObjectsKeepMethodHomeAndSuper() throws Exception {
        MemoryResolver resolver =
                new MemoryResolver()
                        .publicModule(
                                "animals",
                                "animal: { speak: () => \"animal\" }\n"
                                        + "this.dog: animal { speak: () => super.speak() + \"-dog\" }\n"
                                        + "this.animal: animal");
        ProtosPrelude prelude = new ProtosCoreBootstrap().bootstrap(CORE, resolver);
        ProtosActivation actor = prelude.newModuleActivation();

        assertProtosTrue(
                "animals: import(\"animals\")\n"
                        + "dog: animals.dog\n"
                        + "rex: dog {}\n"
                        + "(dog.parent() === animals.animal).and() {\n"
                        + "    (rex.speak() == \"animal-dog\").and() { dog.speak() == \"animal-dog\" }\n"
                        + "}",
                actor);
    }

    @Test
    void d242RecursiveImportObservesIdenticalPartialPublicObject() throws Exception {
        MemoryResolver resolver =
                new MemoryResolver()
                        .publicModule(
                                "a",
                                "this.before: 10\n"
                                        + "b: import(\"b\")\n"
                                        + "this.b: b\n"
                                        + "this.after: 20")
                        .publicModule(
                                "b",
                                "a: import(\"a\")\n"
                                        + "this.partial: a\n"
                                        + "this.sawBefore: a.hasSlot(\"before\")\n"
                                        + "this.sawAfter: a.hasSlot(\"after\")");
        ProtosPrelude prelude = new ProtosCoreBootstrap().bootstrap(CORE, resolver);
        ProtosActivation actor = prelude.newModuleActivation();

        assertProtosTrue(
                "a: import(\"a\")\n"
                        + "(a.b.partial === a).and() {\n"
                        + "    a.b.sawBefore.and() { (a.b.sawAfter == false).and() { a.after == 20 } }\n"
                        + "}",
                actor);
        assertEquals(1, resolver.loads("a"));
        assertEquals(1, resolver.loads("b"));
    }

    @Test
    void d242FailedModuleIsEvictedRetriedAndEscapedPartialSurvives() throws Exception {
        AtomicInteger attempt = new AtomicInteger();
        MemoryResolver resolver = new MemoryResolver() {
            @Override
            public ProtosModuleSource loadSource(ProtosModuleKey key) throws Exception {
                if (!key.canonicalId().equals("flaky")) {
                    return super.loadSource(key);
                }
                loadCounts.merge(key.canonicalId(), 1, Integer::sum);
                return ProtosModuleSource.fromCharacters(
                        key,
                        attempt.getAndIncrement() == 0
                                ? "this.marker: 1\nimport(\"sink\").escaped = this\nfail()"
                                : "this.marker: 2");
            }
        }.publicModule("flaky", "ignored").source("sink", "escaped: null");
        ProtosPrelude prelude = new ProtosCoreBootstrap().bootstrap(CORE, resolver);
        ProtosActivation actor = prelude.newModuleActivation();

        assertThrows(ProtosSignalException.class, () -> eval("import(\"flaky\")", actor));
        assertProtosTrue(
                "escaped: import(\"sink\").escaped\n"
                        + "retried: import(\"flaky\")\n"
                        + "(escaped.marker == 1).and() {\n"
                        + "    (retried.marker == 2).and() { retried !== escaped }\n"
                        + "}",
                actor);
        assertEquals(2, resolver.loads("flaky"));
    }

    @Test
    void d242MutablePublicModulesAreActorLocal() throws Exception {
        MemoryResolver resolver =
                new MemoryResolver().publicModule("state", "this.items: []");
        ProtosPrelude prelude = new ProtosCoreBootstrap().bootstrap(CORE, resolver);
        ProtosActivation actorA = prelude.newModuleActivation();
        ProtosActivation actorB = prelude.newModuleActivation();

        Object a = eval("import(\"state\")", actorA);
        Object b = eval("import(\"state\")", actorB);
        assertNotSame(a, b);
        assertSame(a, eval("import(\"state\")", actorA));
        assertSame(
                prelude.bindings().readLocalSlot("Object").orElseThrow(),
                ((ProtosObjectValue) a).parent().orElseThrow(),
                "both public modules share the exact frozen Core Object parent");
        assertSame(
                ((ProtosObjectValue) a).parent().orElseThrow(),
                ((ProtosObjectValue) b).parent().orElseThrow());
    }

    @Test
    void d242DirectCanonicalModuleLoadReturnsPublicObject() throws Exception {
        MemoryResolver resolver =
                new MemoryResolver().publicModule("direct-module", "hidden: 1\nthis.value: 7");
        ProtosPrelude prelude = new ProtosCoreBootstrap().bootstrap(CORE, resolver);
        ProtosActivation actor = prelude.newModuleActivation();

        ProtosObjectValue module =
                new ProtosModuleRuntime(resolver)
                        .loadCanonicalModule(ProtosModuleKey.module("direct-module"), actor);
        assertEquals(java.util.Set.of("value"), module.localSlotsSnapshot().keySet());
        assertSame(
                module,
                actor.actorModuleState()
                        .lookup(ProtosModuleKey.module("direct-module"))
                        .orElseThrow()
                        .instance());
    }

    /**
     * D242 explicit MODULE/SOURCE fixture resolver. {@link #source} registers a SOURCE whose import
     * answers its complete context; {@link #publicModule} registers a MODULE whose initializer runs
     * in a private context and whose import answers its public object. Kind is never inferred from
     * spelling.
     */
    static class MemoryResolver implements ProtosModuleResolver {
        final Map<String, String> aliases = new HashMap<>();
        final Map<String, ProtosModuleKey> keys = new HashMap<>();
        final Map<ProtosModuleKey, String> sources = new HashMap<>();
        final Map<String, Integer> loadCounts = new HashMap<>();
        final AtomicInteger resolveCalls = new AtomicInteger();
        final java.util.List<String> resolvedSpecifiers = new java.util.ArrayList<>();
        final java.util.List<String> resolvedFrom = new java.util.ArrayList<>();

        MemoryResolver source(String key, String source) {
            return register(ProtosModuleKey.source(key), source);
        }

        MemoryResolver publicModule(String key, String initializer) {
            return register(ProtosModuleKey.module(key), initializer);
        }

        private MemoryResolver register(ProtosModuleKey key, String source) {
            aliases.put(key.canonicalId(), key.canonicalId());
            keys.put(key.canonicalId(), key);
            sources.put(key, source);
            return this;
        }

        MemoryResolver alias(String spelling, String key) {
            aliases.put(spelling, key);
            return this;
        }

        int loads(String key) { return loadCounts.getOrDefault(key, 0); }

        @Override public ProtosModuleKey resolve(String exactSpecifier, Optional<ProtosModuleKey> importingModule) throws Exception {
            resolveCalls.incrementAndGet();
            resolvedSpecifiers.add(exactSpecifier);
            resolvedFrom.add(importingModule.map(ProtosModuleKey::canonicalId).orElse("<root>"));
            String name = aliases.get(exactSpecifier);
            ProtosModuleKey key = name == null ? null : keys.get(name);
            if (key == null) throw new java.io.IOException("not found");
            return key;
        }

        @Override public ProtosModuleSource loadSource(ProtosModuleKey key) throws Exception {
            loadCounts.merge(key.canonicalId(), 1, Integer::sum);
            String source = sources.get(key);
            if (source == null) throw new java.io.IOException("not found");
            return ProtosModuleSource.fromCharacters(key, source);
        }
    }

    // ---- I158 / D251-A1: suspended module initialization ---------------------------------

    @Test
    void i158ModuleCircularImportReturnsThePartialObjectAcrossASuspension() throws Exception {
        com.guillermomolina.protos.runtime.modules.ProtosModuleKey key =
                com.guillermomolina.protos.runtime.modules.ProtosModuleKey.module("canonical:mod:app");
        ProtosI158ActorBootstrapSuspensionTest.Harness x =
                ProtosI158ActorBootstrapSuspensionTest.Harness.create(
                        "mod:app",
                        key,
                        """
                        starts: 0
                        starts = starts + 1
                        secret: "private"
                        this.published: "early"
                        b: import("b")
                        this.boot: (acceptor) => {
                            Error.handle(() => { acceptor.accept().value() }, (error) => { null })
                            { sawSelf: b.sawSelf; sameA: b.a; starts: starts; sawSecret: b.sawSecret }
                        }
                        """,
                        java.util.Map.of(
                                "canonical:b",
                                """
                                a: import("mod:app")
                                sawSelf: a.published
                                sawSecret: a.hasSlot("secret")
                                """));
        com.guillermomolina.protos.runtime.ProtosActor spawned = x.local(x.invokeSpawn(x.acquire()));
        x.executor().drain();

        // Suspended inside the binding: the module itself already completed its body.
        var record = spawned.moduleState().lookup(key).orElseThrow();
        ProtosObjectValue partialA = record.instance();
        assertEquals(
                com.guillermomolina.protos.runtime.ProtosActorModuleState.InitializationState.READY,
                record.state());
        assertEquals(
                com.guillermomolina.protos.runtime.ProtosActor.LifecycleState.INITIALIZING,
                spawned.lifecycleState(),
                "module READY is not Actor READY");

        x.backend().pending.pollFirst().failed();
        x.executor().drain();

        ProtosObjectValue behavior = spawned.currentBehavior().orElseThrow();
        assertSame(partialA, spawned.moduleState().lookup(key).orElseThrow().instance());
        assertSame(partialA, behavior.readLocalSlot("sameA").orElseThrow());
        assertEquals("early", ((com.guillermomolina.protos.runtime.ProtosStringValue) behavior.readLocalSlot("sawSelf").orElseThrow()).value());
        assertSame(Boolean.FALSE, behavior.readLocalSlot("sawSecret").orElseThrow());
        assertFalse(partialA.hasLocalSlot("secret"));
        assertEquals("1", com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerDecimalText(behavior.readLocalSlot("starts").orElseThrow()));
    }

    @Test
    void i158ImportThatSuspendsThenFailsIsEvictedWithoutRollback() throws Exception {
        com.guillermomolina.protos.runtime.modules.ProtosModuleKey failingKey =
                com.guillermomolina.protos.runtime.modules.ProtosModuleKey.source("canonical:failing");
        ProtosI158ActorBootstrapSuspensionTest.Harness x =
                ProtosI158ActorBootstrapSuspensionTest.Harness.create(
                        "app",
                        com.guillermomolina.protos.runtime.modules.ProtosModuleKey.source("canonical:app"),
                        """
                        box: { pending: null; effects: 0 }
                        boot: (acceptor) => {
                            box.pending = acceptor.accept()
                            first: Error.handle(() => { import("failing") }, (error) => { "failed" })
                            second: Error.handle(() => { import("failing") }, (error) => { "failed" })
                            { first: first; second: second; effects: box.effects }
                        }
                        """,
                        java.util.Map.of(
                                "canonical:failing",
                                """
                                main: import("app")
                                main.box.effects = main.box.effects + 1
                                Error.handle(() => { main.box.pending.value() }, (error) => { null })
                                Error().signal()
                                """));
        com.guillermomolina.protos.runtime.ProtosActor spawned = x.local(x.invokeSpawn(x.acquire()));
        x.executor().drain();
        assertTrue(spawned.moduleState().lookup(failingKey).isPresent(), "cached INITIALIZING while suspended");

        x.backend().pending.pollFirst().failed();
        x.executor().drain();

        ProtosObjectValue behavior = spawned.currentBehavior().orElseThrow();
        assertTrue(spawned.moduleState().lookup(failingKey).isEmpty(), "failed record evicted");
        assertEquals("2", com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerDecimalText(behavior.readLocalSlot("effects").orElseThrow()));
        assertEquals("failed", ((com.guillermomolina.protos.runtime.ProtosStringValue) behavior.readLocalSlot("second").orElseThrow()).value());
    }
}
