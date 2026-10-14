/*
 * THE LICENSED WORK IS PROVIDED UNDER THE TERMS OF THE ADAPTIVE PUBLIC LICENSE
 * ("LICENSE") AS FIRST COMPLETED BY: Guillermo Adrián Molina. See LICENSE.TXT.
 */
package com.guillermomolina.protos.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.guillermomolina.protos.parser.ProtosParser;
import com.guillermomolina.protos.runtime.ProtosActivation;
import com.guillermomolina.protos.runtime.ProtosActorExecutionDomain;
import com.guillermomolina.protos.runtime.ProtosActorModuleState;
import com.guillermomolina.protos.runtime.ProtosBooleanValue;
import com.guillermomolina.protos.runtime.ProtosClosureValue;
import com.guillermomolina.protos.runtime.ProtosFutureValue;
import com.guillermomolina.protos.runtime.ProtosIntegerValue;
import com.guillermomolina.protos.runtime.ProtosMapValue;
import com.guillermomolina.protos.runtime.ProtosNumericHashKey;
import com.guillermomolina.protos.runtime.ProtosNullValue;
import com.guillermomolina.protos.runtime.ProtosObjectValue;
import com.guillermomolina.protos.runtime.ProtosPrelude;
import com.guillermomolina.protos.runtime.ProtosTask;
import com.guillermomolina.protos.semantic.Canonicalizer;
import com.guillermomolina.protos.semantic.ast.CanonicalClosure;
import com.guillermomolina.protos.semantic.ast.CanonicalSequence;
import com.oracle.truffle.api.TruffleLanguage.LanguageReference;
import com.oracle.truffle.api.source.Source;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;

final class ProtosPerf006Plat028MapReadLookupCallbackTest {
    private static final LanguageReference<ProtosLanguage> LANGUAGE_REF =
            LanguageReference.create(ProtosLanguage.class);

    @Test
    void containsKeyHashSuspensionKeepsSameMapMutationRestricted() throws Exception {
        try (LanguageScope scope = languageScope()) {
            ProtosPrelude prelude = core();
            ProtosActorExecutionDomain domain = new ProtosActorExecutionDomain();
            ProtosActivation module = activation(prelude, domain);
            ProtosMapValue map = new ProtosMapValue(prelude.mapPrototype());
            ProtosFutureValue gate = new ProtosFutureValue(prelude.futurePrototype(), domain);
            ProtosObjectValue stored = key();
            ProtosObjectValue query = key();
            ProtosObjectValue conflictingKey = key();
            AtomicInteger hashCalls = new AtomicInteger();

            map.append(stored, ProtosNumericHashKey.ofLong(7), new ProtosIntegerValue(1L));
            module.context().createLocalSlot("map", map);
            module.context().createLocalSlot("gate", gate);
            module.context().createLocalSlot("query", query);
            module.context().createLocalSlot("conflictingKey", conflictingKey);
            module.context().createLocalSlot(
                    "hashProbe",
                    ProtosClosureValue.nativeClosure(
                            (activation, supplied) -> {
                                hashCalls.incrementAndGet();
                                return ProtosNullValue.INSTANCE;
                            }));
            query.createLocalSlot(
                    "hash",
                    sourceClosure(
                            scope.language(),
                            module,
                            "() => { hashProbe()\ngate.value()\n7 }",
                            "perf006-plat028-map-read-hash.protos"));
            query.createLocalSlot(
                    "==",
                    ProtosClosureValue.nativeClosure(
                            (activation, supplied) -> {
                                assertSame(stored, supplied.get(0));
                                return ProtosBooleanValue.TRUE;
                            }));

            ProtosTask lookup =
                    execute(
                            domain,
                            module,
                            lowerRoot(
                                    scope.language(),
                                    "map.containsKey(query)",
                                    "perf006-plat028-map-read-contains.protos"));

            assertTrue(domain.dispatchOne());
            assertEquals(ProtosTask.State.SUSPENDED, lookup.state());
            assertTrue(map.comparisonActive());
            assertEquals(1, hashCalls.get());

            ProtosActivation mutationModule = siblingActivation(module);
            ProtosTask conflictingMutation =
                    execute(
                            domain,
                            mutationModule,
                            lowerRoot(
                                    scope.language(),
                                    "map.atPut(conflictingKey, 99)",
                                    "perf006-plat028-map-read-conflict.protos"));
            assertTrue(domain.dispatchOne());
            assertEquals(ProtosTask.State.FAILED, conflictingMutation.state());
            assertInstanceOf(ProtosObjectValue.class, conflictingMutation.failure().orElseThrow());
            assertEquals(1, map.keyedSize());
            assertTrue(map.comparisonActive());

            assertTrue(gate.resolve(ProtosNullValue.INSTANCE, module));
            assertTrue(domain.dispatchOne());
            assertEquals(ProtosTask.State.COMPLETED, lookup.state());
            assertSame(ProtosBooleanValue.TRUE, lookup.result().orElseThrow());
            assertEquals(1, hashCalls.get());
            assertFalse(map.comparisonActive());
        }

        System.out.println("PERF006_PLAT028_MAP_HASH_SUSPENSION=PASS");
        System.out.println("PERF006_PLAT028_MAP_HASH_SINGLE_INVOCATION=PASS");
        System.out.println("PERF006_PLAT028_MAP_SAME_MAP_MUTATION_DURING_HASH=ERROR");
    }

    @Test
    void atEqualitySuspensionPreservesHashFilterDirectionAndExactValue() throws Exception {
        try (LanguageScope scope = languageScope()) {
            ProtosPrelude prelude = core();
            ProtosActorExecutionDomain domain = new ProtosActorExecutionDomain();
            ProtosActivation module = activation(prelude, domain);
            ProtosMapValue map = new ProtosMapValue(prelude.mapPrototype());
            ProtosFutureValue gate = new ProtosFutureValue(prelude.futurePrototype(), domain);
            ProtosObjectValue skipped = key();
            ProtosObjectValue stored = key();
            ProtosObjectValue query = key();
            ProtosObjectValue marker = key();
            AtomicInteger queryEqualityCalls = new AtomicInteger();
            AtomicInteger storedEqualityCalls = new AtomicInteger();

            skipped.createLocalSlot(
                    "==",
                    ProtosClosureValue.nativeClosure(
                            (activation, supplied) -> {
                                throw new AssertionError("hash-mismatched stored key must not be compared");
                            }));
            stored.createLocalSlot(
                    "==",
                    ProtosClosureValue.nativeClosure(
                            (activation, supplied) -> {
                                storedEqualityCalls.incrementAndGet();
                                return ProtosBooleanValue.FALSE;
                            }));
            map.append(skipped, ProtosNumericHashKey.ofLong(8), key());
            map.append(stored, ProtosNumericHashKey.ofLong(7), marker);

            module.context().createLocalSlot("map", map);
            module.context().createLocalSlot("gate", gate);
            module.context().createLocalSlot("stored", stored);
            module.context().createLocalSlot("query", query);
            module.context().createLocalSlot(
                    "equalityProbe",
                    ProtosClosureValue.nativeClosure(
                            (activation, supplied) -> {
                                queryEqualityCalls.incrementAndGet();
                                assertSame(stored, supplied.get(0));
                                return ProtosNullValue.INSTANCE;
                            }));
            query.createLocalSlot(
                    "hash",
                    ProtosClosureValue.nativeClosure(
                            (activation, supplied) ->
                                    new ProtosIntegerValue(7)));
            query.createLocalSlot(
                    "==",
                    sourceClosure(
                            scope.language(),
                            module,
                            "(other) => { equalityProbe(other)\ngate.value()\nother === stored }",
                            "perf006-plat028-map-read-equality.protos"));

            ProtosTask lookup =
                    execute(
                            domain,
                            module,
                            lowerRoot(
                                    scope.language(),
                                    "map.at(query)",
                                    "perf006-plat028-map-read-at.protos"));

            assertTrue(domain.dispatchOne());
            assertEquals(ProtosTask.State.SUSPENDED, lookup.state());
            assertTrue(map.comparisonActive());
            assertEquals(1, queryEqualityCalls.get());
            assertEquals(0, storedEqualityCalls.get());

            assertTrue(gate.resolve(ProtosNullValue.INSTANCE, module));
            assertTrue(domain.dispatchOne());
            assertEquals(ProtosTask.State.COMPLETED, lookup.state());
            assertSame(marker, lookup.result().orElseThrow());
            assertEquals(1, queryEqualityCalls.get());
            assertEquals(0, storedEqualityCalls.get());
            assertFalse(map.comparisonActive());
        }

        System.out.println("PERF006_PLAT028_MAP_EQUALITY_SUSPENSION=PASS");
        System.out.println("PERF006_PLAT028_MAP_EQUALITY_DIRECTION=QUERY_TO_STORED");
        System.out.println("PERF006_PLAT028_MAP_RECORDED_HASH_FILTER=PASS");
        System.out.println("PERF006_PLAT028_MAP_AT_EXACT_VALUE=PASS");
    }

    @Test
    void cancellationUnwindReleasesSuspendedEqualityComparisonExactlyOnce() throws Exception {
        try (LanguageScope scope = languageScope()) {
            ProtosPrelude prelude = core();
            ProtosActorExecutionDomain domain = new ProtosActorExecutionDomain();
            ProtosActivation module = activation(prelude, domain);
            ProtosMapValue map = new ProtosMapValue(prelude.mapPrototype());
            ProtosFutureValue gate = new ProtosFutureValue(prelude.futurePrototype(), domain);
            ProtosObjectValue stored = key();
            ProtosObjectValue query = key();

            map.append(stored, ProtosNumericHashKey.ofLong(7), key());
            module.context().createLocalSlot("map", map);
            module.context().createLocalSlot("gate", gate);
            module.context().createLocalSlot("query", query);
            query.createLocalSlot(
                    "hash",
                    ProtosClosureValue.nativeClosure(
                            (activation, supplied) ->
                                    new ProtosIntegerValue(7)));
            query.createLocalSlot(
                    "==",
                    sourceClosure(
                            scope.language(),
                            module,
                            "(other) => { gate.value()\ntrue }",
                            "perf006-plat028-map-read-cancel-equality.protos"));

            Execution execution =
                    executeWithFuture(
                            domain,
                            prelude,
                            module,
                            lowerRoot(
                                    scope.language(),
                                    "map.containsKey(query)",
                                    "perf006-plat028-map-read-cancel.protos"));

            assertTrue(domain.dispatchOne());
            assertEquals(ProtosTask.State.SUSPENDED, execution.task().state());
            assertTrue(map.comparisonActive());

            assertTrue(execution.future().cancelRequest());
            assertEquals(ProtosTask.State.RUNNABLE, execution.task().state());
            assertTrue(domain.dispatchOne());

            assertEquals(ProtosTask.State.CANCELLED, execution.task().state());
            assertEquals(ProtosFutureValue.State.CANCELLED, execution.future().state());
            assertFalse(map.comparisonActive());

            assertTrue(gate.resolve(ProtosNullValue.INSTANCE, module));
            assertFalse(domain.dispatchOne());
        }

        System.out.println("PERF006_PLAT028_MAP_COMPARISON_CANCEL_UNWIND=PASS");
        System.out.println("PERF006_PLAT028_MAP_COMPARISON_SCOPE_LEAK=NO");
    }

    @Test
    void invalidHashAndEqualityResultsFailAfterComparisonScopeRelease() throws Exception {
        try (LanguageScope scope = languageScope()) {
            ProtosPrelude prelude = core();
            ProtosActorExecutionDomain domain = new ProtosActorExecutionDomain();
            ProtosActivation module = activation(prelude, domain);
            ProtosMapValue map = new ProtosMapValue(prelude.mapPrototype());
            ProtosObjectValue stored = key();
            ProtosObjectValue invalidHash = key();
            ProtosObjectValue invalidEquality = key();
            map.append(stored, ProtosNumericHashKey.ofLong(7), key());
            module.context().createLocalSlot("map", map);
            module.context().createLocalSlot("invalidHash", invalidHash);
            module.context().createLocalSlot("invalidEquality", invalidEquality);

            invalidHash.createLocalSlot(
                    "hash",
                    ProtosClosureValue.nativeClosure((activation, supplied) -> "bad-hash"));
            ProtosTask hashTask =
                    execute(
                            domain,
                            module,
                            lowerRoot(
                                    scope.language(),
                                    "map.containsKey(invalidHash)",
                                    "perf006-plat028-map-read-invalid-hash.protos"));
            assertTrue(domain.dispatchOne());
            assertEquals(ProtosTask.State.FAILED, hashTask.state());
            assertFalse(map.comparisonActive());

            invalidEquality.createLocalSlot(
                    "hash",
                    ProtosClosureValue.nativeClosure(
                            (activation, supplied) ->
                                    new ProtosIntegerValue(7)));
            invalidEquality.createLocalSlot(
                    "==",
                    ProtosClosureValue.nativeClosure((activation, supplied) -> "bad-equality"));
            ProtosActivation equalityModule = siblingActivation(module);
            ProtosTask equalityTask =
                    execute(
                            domain,
                            equalityModule,
                            lowerRoot(
                                    scope.language(),
                                    "map.containsKey(invalidEquality)",
                                    "perf006-plat028-map-read-invalid-equality.protos"));
            assertTrue(domain.dispatchOne());
            assertEquals(ProtosTask.State.FAILED, equalityTask.state());
            assertFalse(map.comparisonActive());
        }

        System.out.println("PERF006_PLAT028_MAP_INVALID_HASH_RESULT=ERROR");
        System.out.println("PERF006_PLAT028_MAP_INVALID_EQUALITY_RESULT=ERROR");
        System.out.println("PERF006_PLAT028_MAP_INVALID_RESULT_SCOPE_LEAK=NO");
    }

    @Test
    void atIfAbsentFallbackSuspensionDoesNotReplayMapSearch() throws Exception {
        try (LanguageScope scope = languageScope()) {
            ProtosPrelude prelude = core();
            ProtosActorExecutionDomain domain = new ProtosActorExecutionDomain();
            ProtosActivation module = activation(prelude, domain);
            ProtosMapValue map = new ProtosMapValue(prelude.mapPrototype());
            ProtosFutureValue gate =
                    new ProtosFutureValue(prelude.futurePrototype(), domain);
            ProtosObjectValue query = key();
            ProtosObjectValue marker = key();
            AtomicInteger hashCalls = new AtomicInteger();
            AtomicInteger fallbackCalls = new AtomicInteger();

            module.context().createLocalSlot("map", map);
            module.context().createLocalSlot("query", query);
            module.context().createLocalSlot("gate", gate);
            module.context().createLocalSlot("marker", marker);
            module.context().createLocalSlot(
                    "fallbackProbe",
                    ProtosClosureValue.nativeClosure(
                            (activation, supplied) -> {
                                fallbackCalls.incrementAndGet();
                                return ProtosNullValue.INSTANCE;
                            }));

            query.createLocalSlot(
                    "hash",
                    ProtosClosureValue.nativeClosure(
                            (activation, supplied) -> {
                                hashCalls.incrementAndGet();
                                return new ProtosIntegerValue(7);
                            }));

            ProtosTask lookup =
                    execute(
                            domain,
                            module,
                            lowerRoot(
                                    scope.language(),
                                    "map.atIfAbsent(query, () => { "
                                            + "fallbackProbe()\n"
                                            + "gate.value()\n"
                                            + "marker })",
                                    "i049-map-at-if-absent-fallback-suspend.protos"));

            assertTrue(domain.dispatchOne());
            assertEquals(ProtosTask.State.SUSPENDED, lookup.state());
            assertEquals(1, hashCalls.get());
            assertEquals(1, fallbackCalls.get());
            assertFalse(map.comparisonActive());
            assertEquals(0, map.keyedSize());

            assertTrue(gate.resolve(ProtosNullValue.INSTANCE, module));
            assertTrue(domain.dispatchOne());

            assertEquals(ProtosTask.State.COMPLETED, lookup.state());
            assertSame(marker, lookup.result().orElseThrow());
            assertEquals(1, hashCalls.get());
            assertEquals(1, fallbackCalls.get());
            assertFalse(map.comparisonActive());
            assertEquals(0, map.keyedSize());
        }

        System.out.println("I049_MAP_AT_IF_ABSENT_FALLBACK_SUSPEND_RESUME=PASS");
        System.out.println("I049_MAP_AT_IF_ABSENT_SEARCH_REPLAY=NO");
        System.out.println("I049_MAP_AT_IF_ABSENT_IMPLICIT_MUTATION=NO");
    }

    @Test
    void canonicalRecognitionKeepsMapReadsBoundToExactStandardHome() throws Exception {
        ProtosPrelude prelude = core();
        ProtosObjectValue canonicalHome = prelude.mapPrototype();
        ProtosActivation caller = prelude.newModuleActivation();
        ProtosClosureValue at =
                (ProtosClosureValue) canonicalHome.readLocalSlot("at").orElseThrow();
        ProtosClosureValue atIfAbsent =
                (ProtosClosureValue) canonicalHome.readLocalSlot("atIfAbsent").orElseThrow();
        ProtosClosureValue containsKey =
                (ProtosClosureValue) canonicalHome.readLocalSlot("containsKey").orElseThrow();
        ProtosObjectValue aliasHome = new ProtosObjectValue(canonicalHome);
        aliasHome.createLocalSlot("at", at);
        aliasHome.createLocalSlot("atIfAbsent", atIfAbsent);
        aliasHome.createLocalSlot("containsKey", containsKey);
        ProtosClosureValue copiedAt =
                ProtosClosureValue.nativeClosure(at.nativeBody().orElseThrow());
        ProtosClosureValue copiedAtIfAbsent =
                ProtosClosureValue.nativeClosure(atIfAbsent.nativeBody().orElseThrow());

        assertEquals(
                ProtosStandardMapProtocol.StructuredReadLookupKind.AT,
                ProtosStandardMapProtocol.structuredReadLookupKindForImplementation(at));
        assertEquals(
                ProtosStandardMapProtocol.StructuredReadLookupKind.AT_IF_ABSENT,
                ProtosStandardMapProtocol.structuredReadLookupKindForImplementation(atIfAbsent));
        assertEquals(
                ProtosStandardMapProtocol.StructuredReadLookupKind.CONTAINS_KEY,
                ProtosStandardMapProtocol.structuredReadLookupKindForImplementation(containsKey));
        assertEquals(
                ProtosStandardMapProtocol.StructuredReadLookupKind.AT,
                ProtosStandardMapProtocol.structuredReadLookupKindForImplementation(copiedAt));
        assertEquals(
                ProtosStandardMapProtocol.StructuredReadLookupKind.AT_IF_ABSENT,
                ProtosStandardMapProtocol.structuredReadLookupKindForImplementation(
                        copiedAtIfAbsent));

        assertEquals(
                ProtosStandardMapProtocol.StructuredReadLookupKind.AT,
                ProtosStandardMapProtocol.structuredReadLookupKindForCanonicalSelection(
                        at, canonicalHome, caller));
        assertEquals(
                ProtosStandardMapProtocol.StructuredReadLookupKind.AT_IF_ABSENT,
                ProtosStandardMapProtocol.structuredReadLookupKindForCanonicalSelection(
                        atIfAbsent, canonicalHome, caller));
        assertEquals(
                ProtosStandardMapProtocol.StructuredReadLookupKind.CONTAINS_KEY,
                ProtosStandardMapProtocol.structuredReadLookupKindForCanonicalSelection(
                        containsKey, canonicalHome, caller));

        assertNull(
                ProtosStandardMapProtocol.structuredReadLookupKindForCanonicalSelection(
                        at, aliasHome, caller));
        assertNull(
                ProtosStandardMapProtocol.structuredReadLookupKindForCanonicalSelection(
                        atIfAbsent, aliasHome, caller));
        assertNull(
                ProtosStandardMapProtocol.structuredReadLookupKindForCanonicalSelection(
                        copiedAt, canonicalHome, caller));
        assertNull(
                ProtosStandardMapProtocol.structuredReadLookupKindForCanonicalSelection(
                        copiedAtIfAbsent, canonicalHome, caller));
    }

    private static ProtosObjectValue key() {
        return new ProtosObjectValue(ProtosObjectValue.rootObject());
    }

    private static ProtosClosureValue sourceClosure(
            ProtosLanguage language,
            ProtosActivation creator,
            String characters,
            String sourceName)
            throws Exception {
        CanonicalClosure definition = closureDefinition(characters);
        ProtosClosureExecutionPlan plan =
                ProtosClosureExecutionPlan.bytecode(
                        definition,
                        language,
                        source(characters, sourceName), ProtosExecutionMode.NORMAL);
        return new ProtosClosureValue(
                definition,
                creator.lexicalContextsForClosureCapture(),
                creator.receiver(),
                creator.methodHome().orElse(null),
                creator.returnHome().orElse(null),
                creator.prelude().orElseThrow(),
                plan);
    }

    private static ProtosActivation siblingActivation(ProtosActivation module) {
        return module.prelude().orElseThrow().newModuleActivation(
                module.actorModuleState(),
                module.currentModuleKey().orElse(null),
                module.context(),
                module.executionDomain());
    }

    private static ProtosTask execute(
            ProtosActorExecutionDomain domain,
            ProtosActivation activation,
            ProtosSemanticBytecodeRootNode root) {
        return domain.createTask(
                null,
                current ->
                        ProtosBytecodeTaskExecution.execute(
                                current, root.getCallTarget(), activation));
    }

    private static Execution executeWithFuture(
            ProtosActorExecutionDomain domain,
            ProtosPrelude prelude,
            ProtosActivation activation,
            ProtosSemanticBytecodeRootNode root) {
        ProtosFutureValue future = new ProtosFutureValue(prelude.futurePrototype(), domain);
        ProtosTask task =
                domain.createTask(
                        null,
                        future,
                        current ->
                                ProtosBytecodeTaskExecution.execute(
                                        current,
                                        root.getCallTarget(),
                                        activation));
        future.attachProducerTask(task, activation);
        return new Execution(task, future);
    }

    private static LanguageScope languageScope() {
        Context context = Context.newBuilder(ProtosLanguage.ID).build();
        context.initialize(ProtosLanguage.ID);
        context.enter();
        return new LanguageScope(context, LANGUAGE_REF.get(null));
    }

    private static ProtosPrelude core() throws Exception {
        return new ProtosCoreBootstrap().bootstrap(Path.of("protos", "lib", "core"));
    }

    private static ProtosActivation activation(
            ProtosPrelude prelude,
            ProtosActorExecutionDomain domain) {
        return prelude.newModuleActivation(
                new ProtosActorModuleState(),
                null,
                prelude.newExecutionContext(),
                domain);
    }

    private static CanonicalClosure closureDefinition(String characters) {
        CanonicalSequence sequence = canonicalize(characters);
        assertEquals(1, sequence.expressions().size());
        return (CanonicalClosure) sequence.expressions().get(0);
    }

    private static CanonicalSequence canonicalize(String characters) {
        return (CanonicalSequence)
                new Canonicalizer()
                        .canonicalize(new ProtosParser(characters).parseProgram());
    }

    private static Source source(String characters, String name) throws Exception {
        return Source.newBuilder(ProtosLanguage.ID, characters, name).build();
    }

    private static ProtosSemanticBytecodeRootNode lowerRoot(
            ProtosLanguage language,
            String characters,
            String sourceName)
            throws Exception {
        Source source = source(characters, sourceName);
        return new CanonicalToBytecodeLowerer(language, source, ProtosExecutionMode.NORMAL)
                .lowerRoot(canonicalize(characters));
    }

    private record Execution(ProtosTask task, ProtosFutureValue future) {}

    private record LanguageScope(Context context, ProtosLanguage language)
            implements AutoCloseable {
        @Override
        public void close() {
            context.leave();
            context.close();
        }
    }
}
