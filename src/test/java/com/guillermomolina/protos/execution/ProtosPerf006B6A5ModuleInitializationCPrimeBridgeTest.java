/*
 * THE LICENSED WORK IS PROVIDED UNDER THE TERMS OF THE ADAPTIVE PUBLIC LICENSE
 * ("LICENSE") AS FIRST COMPLETED BY: Guillermo Adrián Molina. See LICENSE.TXT.
 */
package com.guillermomolina.protos.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.guillermomolina.protos.parser.ProtosParser;
import com.guillermomolina.protos.runtime.ProtosActivation;
import com.guillermomolina.protos.runtime.ProtosActorExecutionDomain;
import com.guillermomolina.protos.runtime.ProtosActorModuleState;
import com.guillermomolina.protos.runtime.ProtosFutureValue;
import com.guillermomolina.protos.runtime.ProtosIntegerValue;
import com.guillermomolina.protos.runtime.ProtosModuleKey;
import com.guillermomolina.protos.runtime.ProtosObjectValue;
import com.guillermomolina.protos.runtime.ProtosPrelude;
import com.guillermomolina.protos.runtime.ProtosStringValue;
import com.guillermomolina.protos.runtime.ProtosTask;
import com.guillermomolina.protos.semantic.Canonicalizer;
import com.guillermomolina.protos.semantic.ast.CanonicalSequence;
import com.oracle.truffle.api.TruffleLanguage.LanguageReference;
import com.oracle.truffle.api.source.Source;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;

final class ProtosPerf006B6A5ModuleInitializationCPrimeBridgeTest {
    private static final Path CORE = Path.of("protos", "lib", "core");
    private static final LanguageReference<ProtosLanguage> LANGUAGE_REF =
            LanguageReference.create(ProtosLanguage.class);

    @Test
    void suspendingModuleStaysInitializingAndFinishesWithoutReplay() throws Exception {
        try (LanguageScope scope = languageScope()) {
            MemoryResolver resolver =
                    new MemoryResolver()
                            .module(
                                    "m",
                                    "count: 0\n"
                                            + "count = count + 1\n"
                                            + "value: work.value()\n"
                                            + "count = count + 1\n"
                                            + "99");
            ProtosPrelude prelude = core(resolver);
            ProtosActorExecutionDomain domain = new ProtosActorExecutionDomain();
            ProtosActorModuleState state = new ProtosActorModuleState();
            AtomicReference<ProtosFutureValue> injectedFuture = new AtomicReference<>();
            resolver.injectOnLoad(
                    state,
                    (key, instance) -> {
                        if (!"m".equals(key.canonicalId())) {
                            return;
                        }
                        ProtosFutureValue future =
                                new ProtosFutureValue(prelude.futurePrototype(), domain);
                        injectedFuture.set(future);
                        instance.createLocalSlot("work", future);
                    });
            ProtosActivation activation = activation(prelude, domain, state);

            ProtosTask task =
                    executeTask(
                            domain,
                            activation,
                            lowerRoot(
                                    scope.language(),
                                    "import(\"m\")",
                                    "perf006-b6a5-suspending-import.protos"));

            assertTrue(domain.dispatchOne());
            assertEquals(ProtosTask.State.SUSPENDED, task.state());

            ProtosActorModuleState.ModuleRecord during =
                    state.lookup(new ProtosModuleKey("m")).orElseThrow();
            assertEquals(
                    ProtosActorModuleState.InitializationState.INITIALIZING,
                    during.state());
            assertEquals(String.valueOf("1"), com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerDecimalText(during.instance().readLocalSlot("count").orElseThrow()));
            assertEquals(1, resolver.loads("m"));

            ProtosFutureValue pending = injectedFuture.get();
            assertTrue(pending != null, "resolver must inject the pending Future before module execution");
            assertTrue(
                    pending.resolve(
                            new ProtosIntegerValue(7),
                            activation(prelude, domain, state)));
            assertTrue(domain.dispatchOne(), "suspended import task must resume");
            assertEquals(ProtosTask.State.COMPLETED, task.state());

            ProtosObjectValue module =
                    (ProtosObjectValue) task.result().orElseThrow();
            assertSame(during.instance(), module);
            assertEquals(
                    ProtosActorModuleState.InitializationState.READY,
                    state.lookup(new ProtosModuleKey("m")).orElseThrow().state());
            assertEquals(String.valueOf("2"), com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerDecimalText(module.readLocalSlot("count").orElseThrow()));
            assertEquals(String.valueOf("7"), com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerDecimalText(module.readLocalSlot("value").orElseThrow()));
            assertEquals(1, resolver.loads("m"));

            ProtosActivation cachedActivation = activation(prelude, domain, state);
            ProtosTask cached =
                    executeTask(
                            domain,
                            cachedActivation,
                            lowerRoot(
                                    scope.language(),
                                    "import(\"m\")",
                                    "perf006-b6a5-ready-hit.protos"));
            assertTrue(domain.dispatchOne());
            assertEquals(ProtosTask.State.COMPLETED, cached.state());
            assertSame(module, cached.result().orElseThrow());
            assertEquals(1, resolver.loads("m"));
        }

        System.out.println("PERF006_B6A5_SUSPENDED_MODULE_REMAINS_INITIALIZING=PASS");
        System.out.println("PERF006_B6A5_MODULE_READY_AFTER_TRUE_COMPLETION=PASS");
        System.out.println("PERF006_B6A5_MODULE_BODY_VALUE_IGNORED=PASS");
        System.out.println("PERF006_B6A5_MODULE_COMPLETED_PREFIX_REPLAY=NO");
        System.out.println("PERF006_B6A5_READY_CACHE_HIT_CHILD_STATE=NONE");
    }

    @Test
    void recursiveInitializingImportReturnsSameInstanceWithoutWaiting() throws Exception {
        try (LanguageScope scope = languageScope()) {
            MemoryResolver resolver =
                    new MemoryResolver()
                            .module(
                                    "a",
                                    "before: 10\n"
                                            + "b: import(\"b\")\n"
                                            + "seen: b.aBefore\n"
                                            + "after: 20")
                            .module(
                                    "b",
                                    "a: import(\"a\")\n"
                                            + "aBefore: a.before");
            ProtosPrelude prelude = core(resolver);
            ProtosActorExecutionDomain domain = new ProtosActorExecutionDomain();
            ProtosActorModuleState state = new ProtosActorModuleState();
            ProtosActivation activation = activation(prelude, domain, state);

            ProtosTask task =
                    executeTask(
                            domain,
                            activation,
                            lowerRoot(
                                    scope.language(),
                                    "import(\"a\")",
                                    "perf006-b6a5-cycle.protos"));
            assertTrue(domain.dispatchOne());
            assertEquals(ProtosTask.State.COMPLETED, task.state());

            ProtosObjectValue a = (ProtosObjectValue) task.result().orElseThrow();
            ProtosObjectValue b =
                    (ProtosObjectValue) a.readLocalSlot("b").orElseThrow();
            assertSame(a, b.readLocalSlot("a").orElseThrow());
            assertEquals(String.valueOf("10"), com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerDecimalText(a.readLocalSlot("seen").orElseThrow()));
            assertEquals(1, resolver.loads("a"));
            assertEquals(1, resolver.loads("b"));
            assertEquals(
                    ProtosActorModuleState.InitializationState.READY,
                    state.lookup(new ProtosModuleKey("a")).orElseThrow().state());
            assertEquals(
                    ProtosActorModuleState.InitializationState.READY,
                    state.lookup(new ProtosModuleKey("b")).orElseThrow().state());
        }

        System.out.println("PERF006_B6A5_INITIALIZING_CYCLE_EXACT_INSTANCE=PASS");
        System.out.println("PERF006_B6A5_INITIALIZING_CYCLE_WAIT_GRAPH=NO");
    }

    @Test
    void escapingModuleErrorEvictsExactRecordAndRetryIsFresh() throws Exception {
        try (LanguageScope scope = languageScope()) {
            RetryResolver resolver = new RetryResolver();
            ProtosPrelude prelude = core(resolver);
            ProtosActorExecutionDomain domain = new ProtosActorExecutionDomain();
            ProtosActorModuleState state = new ProtosActorModuleState();
            ProtosActivation activation = activation(prelude, domain, state);

            ProtosTask failed =
                    executeTask(
                            domain,
                            activation,
                            lowerRoot(
                                    scope.language(),
                                    "import(\"retry\")",
                                    "perf006-b6a5-failure.protos"));
            assertTrue(domain.dispatchOne());
            assertEquals(ProtosTask.State.FAILED, failed.state());
            assertTrue(failed.failure().isPresent());
            assertTrue(state.lookup(new ProtosModuleKey("retry")).isEmpty());
            assertEquals(1, resolver.loads("retry"));

            ProtosActivation retryActivation = activation(prelude, domain, state);
            ProtosTask recovered =
                    executeTask(
                            domain,
                            retryActivation,
                            lowerRoot(
                                    scope.language(),
                                    "import(\"retry\")",
                                    "perf006-b6a5-retry.protos"));
            assertTrue(domain.dispatchOne());
            assertEquals(ProtosTask.State.COMPLETED, recovered.state());
            ProtosObjectValue module =
                    (ProtosObjectValue) recovered.result().orElseThrow();
            assertTrue(module.hasLocalSlot("ok"));
            assertEquals(String.valueOf("42"), com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerDecimalText(module.readLocalSlot("ok").orElseThrow()));
            assertEquals(2, resolver.loads("retry"));
            assertEquals(
                    ProtosActorModuleState.InitializationState.READY,
                    state.lookup(new ProtosModuleKey("retry")).orElseThrow().state());
        }

        System.out.println("PERF006_B6A5_TERMINAL_FAILURE_EXACT_RECORD_EVICTION=PASS");
        System.out.println("PERF006_B6A5_FAILED_INITIALIZATION_RETRY_FRESH=PASS");
    }

    @Test
    void cancelledSuspendedModuleEvictsExactRecordAndRetryIsFresh() throws Exception {
        try (LanguageScope scope = languageScope()) {
            MemoryResolver resolver =
                    new MemoryResolver()
                            .module(
                                    "cancel",
                                    "value: work.value()");
            ProtosPrelude prelude = core(resolver);
            ProtosActorExecutionDomain domain = new ProtosActorExecutionDomain();
            ProtosActorModuleState state = new ProtosActorModuleState();
            AtomicReference<ProtosFutureValue> injectedFuture = new AtomicReference<>();
            resolver.injectOnLoad(
                    state,
                    (key, instance) -> {
                        if (!"cancel".equals(key.canonicalId())) {
                            return;
                        }
                        ProtosFutureValue future =
                                new ProtosFutureValue(prelude.futurePrototype(), domain);
                        injectedFuture.set(future);
                        instance.createLocalSlot("work", future);
                    });
            ProtosActivation activation = activation(prelude, domain, state);

            ProtosTask cancelled =
                    executeTask(
                            domain,
                            activation,
                            lowerRoot(
                                    scope.language(),
                                    "import(\"cancel\")",
                                    "perf006-b6a5-cancelled-import.protos"));

            assertTrue(domain.dispatchOne());
            assertEquals(ProtosTask.State.SUSPENDED, cancelled.state());
            ProtosActorModuleState.ModuleRecord active =
                    state.lookup(new ProtosModuleKey("cancel")).orElseThrow();
            ProtosObjectValue firstInstance = active.instance();
            ProtosFutureValue firstFuture = injectedFuture.get();
            assertTrue(firstFuture != null, "first module attempt must own an injected Future");
            assertEquals(
                    ProtosActorModuleState.InitializationState.INITIALIZING,
                    active.state());

            assertTrue(cancelled.requestCancellation());
            int cancellationDispatches = 0;
            while (cancelled.state() != ProtosTask.State.CANCELLED) {
                assertTrue(domain.dispatchOne());
                assertTrue(++cancellationDispatches <= 3);
            }
            assertTrue(state.lookup(new ProtosModuleKey("cancel")).isEmpty());
            assertEquals(1, resolver.loads("cancel"));

            ProtosActivation retryActivation = activation(prelude, domain, state);
            ProtosTask retried =
                    executeTask(
                            domain,
                            retryActivation,
                            lowerRoot(
                                    scope.language(),
                                    "import(\"cancel\")",
                                    "perf006-b6a5-cancel-retry.protos"));
            assertTrue(domain.dispatchOne());
            assertEquals(ProtosTask.State.SUSPENDED, retried.state());
            ProtosFutureValue retryFuture = injectedFuture.get();
            assertTrue(retryFuture != null, "retry must inject a fresh pending Future");
            assertNotSame(firstFuture, retryFuture);
            assertTrue(
                    retryFuture.resolve(
                            new ProtosIntegerValue(11),
                            activation(prelude, domain, state)));
            assertTrue(domain.dispatchOne());
            assertEquals(ProtosTask.State.COMPLETED, retried.state());
            ProtosObjectValue recovered =
                    (ProtosObjectValue) retried.result().orElseThrow();
            assertNotSame(firstInstance, recovered);
            assertEquals(2, resolver.loads("cancel"));
            assertEquals(String.valueOf("11"), com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerDecimalText(recovered.readLocalSlot("value").orElseThrow()));
            assertEquals(
                    ProtosActorModuleState.InitializationState.READY,
                    state.lookup(new ProtosModuleKey("cancel")).orElseThrow().state());
        }

        System.out.println("PERF006_B6A5_CANCELLATION_EXACT_RECORD_EVICTION=PASS");
        System.out.println("PERF006_B6A5_CANCELLED_INITIALIZATION_RETRY_FRESH=PASS");
    }

    @Test
    void shadowedImportCallRemainsOrdinaryAfterLookup() throws Exception {
        try (LanguageScope scope = languageScope()) {
            MemoryResolver resolver = new MemoryResolver();
            ProtosPrelude prelude = core(resolver);
            ProtosActorExecutionDomain domain = new ProtosActorExecutionDomain();
            ProtosActivation activation =
                    activation(prelude, domain, new ProtosActorModuleState());

            ProtosTask task =
                    executeTask(
                            domain,
                            activation,
                            lowerRoot(
                                    scope.language(),
                                    "import: { call: (x) => x }\nimport(\"local\")",
                                    "perf006-b6a5-shadowed-import.protos"));
            assertTrue(domain.dispatchOne());
            assertEquals(ProtosTask.State.COMPLETED, task.state());
            ProtosStringValue result =
                    (ProtosStringValue) task.result().orElseThrow();
            assertEquals("local", result.value());
            assertEquals(0, resolver.resolveCalls.get());
            assertEquals(0, resolver.totalLoads());
        }

        System.out.println("PERF006_B6A5_POST_LOOKUP_EXACT_STANDARD_SELECTION=PASS");
        System.out.println("PERF006_B6A5_SHADOWED_IMPORT_INTRINSIC=NO");
    }

    private static ProtosTask executeTask(
            ProtosActorExecutionDomain domain,
            ProtosActivation activation,
            ProtosSemanticBytecodeRootNode root) {
        return domain.createTask(
                null,
                current ->
                        ProtosBytecodeTaskExecution.execute(
                                current,
                                root.getCallTarget(),
                                activation));
    }

    private static ProtosPrelude core(ProtosModuleResolver resolver) throws Exception {
        return new ProtosCoreBootstrap().bootstrap(CORE, resolver);
    }

    private static ProtosActivation activation(
            ProtosPrelude prelude,
            ProtosActorExecutionDomain domain,
            ProtosActorModuleState state) {
        return prelude.newModuleActivation(
                state,
                null,
                prelude.newExecutionContext(),
                domain);
    }

    private static LanguageScope languageScope() {
        Context context = Context.newBuilder(ProtosLanguage.ID).build();
        context.initialize(ProtosLanguage.ID);
        context.enter();
        return new LanguageScope(context, LANGUAGE_REF.get(null));
    }

    private static CanonicalSequence canonicalize(String characters) {
        return (CanonicalSequence)
                new Canonicalizer()
                        .canonicalize(new ProtosParser(characters).parseProgram());
    }

    private static ProtosSemanticBytecodeRootNode lowerRoot(
            ProtosLanguage language,
            String characters,
            String sourceName)
            throws Exception {
        Source source =
                Source.newBuilder(ProtosLanguage.ID, characters, sourceName).build();
        return new CanonicalToBytecodeLowerer(language, source)
                .lowerRoot(canonicalize(characters));
    }

    private record LanguageScope(Context context, ProtosLanguage language)
            implements AutoCloseable {
        @Override
        public void close() {
            context.leave();
            context.close();
        }
    }

    private static class MemoryResolver implements ProtosModuleResolver {
        final Map<String, String> sources = new HashMap<>();
        final Map<String, Integer> loadCounts = new HashMap<>();
        final AtomicInteger resolveCalls = new AtomicInteger();
        private ProtosActorModuleState injectionState;
        private java.util.function.BiConsumer<ProtosModuleKey, ProtosObjectValue> loadInjection;

        MemoryResolver module(String key, String source) {
            sources.put(key, source);
            return this;
        }

        MemoryResolver injectOnLoad(
                ProtosActorModuleState state,
                java.util.function.BiConsumer<ProtosModuleKey, ProtosObjectValue> injection) {
            this.injectionState = java.util.Objects.requireNonNull(state, "state");
            this.loadInjection = java.util.Objects.requireNonNull(injection, "injection");
            return this;
        }

        int loads(String key) {
            return loadCounts.getOrDefault(key, 0);
        }

        int totalLoads() {
            return loadCounts.values().stream().mapToInt(Integer::intValue).sum();
        }

        @Override
        public ProtosModuleKey resolve(
                String exactSpecifier,
                Optional<ProtosModuleKey> importingModule)
                throws Exception {
            resolveCalls.incrementAndGet();
            if (!sources.containsKey(exactSpecifier)) {
                throw new java.io.IOException("not found");
            }
            return new ProtosModuleKey(exactSpecifier);
        }

        @Override
        public ProtosModuleSource loadSource(ProtosModuleKey key)
                throws Exception {
            loadCounts.merge(key.canonicalId(), 1, Integer::sum);
            String source = sources.get(key.canonicalId());
            if (source == null) {
                throw new java.io.IOException("not found");
            }
            if (loadInjection != null) {
                ProtosActorModuleState.ModuleRecord record =
                        injectionState.lookup(key)
                                .orElseThrow(
                                        () ->
                                                new IllegalStateException(
                                                        "module source loaded before cache-before-execute record"));
                loadInjection.accept(key, record.instance());
            }
            return ProtosModuleSource.fromCharacters(key, source);
        }
    }

    private static final class RetryResolver extends MemoryResolver {
        private final AtomicInteger attempts = new AtomicInteger();

        RetryResolver() {
            module("retry", "placeholder");
        }

        @Override
        public ProtosModuleSource loadSource(ProtosModuleKey key)
                throws Exception {
            loadCounts.merge(key.canonicalId(), 1, Integer::sum);
            if (!"retry".equals(key.canonicalId())) {
                return super.loadSource(key);
            }
            String source =
                    attempts.getAndIncrement() == 0
                            ? "missing"
                            : "ok: 42";
            return ProtosModuleSource.fromCharacters(key, source);
        }
    }
}
