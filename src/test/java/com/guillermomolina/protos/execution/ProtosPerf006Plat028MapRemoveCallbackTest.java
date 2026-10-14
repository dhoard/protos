/*
 * THE LICENSED WORK IS PROVIDED UNDER THE TERMS OF THE ADAPTIVE PUBLIC LICENSE
 * ("LICENSE") AS FIRST COMPLETED BY: Guillermo Adrián Molina. See LICENSE.TXT.
 */
package com.guillermomolina.protos.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

final class ProtosPerf006Plat028MapRemoveCallbackTest {
    private static final LanguageReference<ProtosLanguage> LANGUAGE_REF =
            LanguageReference.create(ProtosLanguage.class);

    @Test
    void hashSuspensionKeepsSameMapMutationRestrictedAndReturnsExactValue() throws Exception {
        try (LanguageScope scope = languageScope()) {
            ProtosPrelude prelude = core();
            ProtosActorExecutionDomain domain = new ProtosActorExecutionDomain();
            ProtosActivation module = activation(prelude, domain);
            ProtosMapValue map = new ProtosMapValue(prelude.mapPrototype());
            ProtosFutureValue gate = new ProtosFutureValue(prelude.futurePrototype(), domain);
            ProtosObjectValue stored = key();
            ProtosObjectValue query = key();
            ProtosObjectValue marker = key();
            ProtosObjectValue conflictingKey = key();
            AtomicInteger hashCalls = new AtomicInteger();

            map.append(stored, ProtosNumericHashKey.ofLong(7), marker);
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
                            "perf006-plat028-map-remove-hash.protos"));
            query.createLocalSlot(
                    "==",
                    ProtosClosureValue.nativeClosure(
                            (activation, supplied) -> {
                                assertSame(stored, supplied.get(0));
                                return ProtosBooleanValue.TRUE;
                            }));

            ProtosTask remove =
                    execute(
                            domain,
                            module,
                            lowerRoot(
                                    scope.language(),
                                    "map.remove(query)",
                                    "perf006-plat028-map-remove-suspend.protos"));
            assertTrue(domain.dispatchOne());
            assertEquals(ProtosTask.State.SUSPENDED, remove.state());
            assertTrue(map.comparisonActive());
            assertEquals(1, hashCalls.get());
            assertEquals(1, map.keyedSize());

            ProtosActivation conflictModule = siblingActivation(module);
            ProtosTask conflict =
                    execute(
                            domain,
                            conflictModule,
                            lowerRoot(
                                    scope.language(),
                                    "map.atPut(conflictingKey, 99)",
                                    "perf006-plat028-map-remove-conflict.protos"));
            assertTrue(domain.dispatchOne());
            assertEquals(ProtosTask.State.FAILED, conflict.state());
            assertEquals(1, map.keyedSize());
            assertTrue(map.comparisonActive());

            assertTrue(gate.resolve(ProtosNullValue.INSTANCE, module));
            assertTrue(domain.dispatchOne());
            assertEquals(ProtosTask.State.COMPLETED, remove.state());
            assertSame(marker, remove.result().orElseThrow());
            assertEquals(1, hashCalls.get());
            assertEquals(0, map.keyedSize());
            assertFalse(map.comparisonActive());
        }

        System.out.println("PERF006_PLAT028_MAP_REMOVE_HASH_SUSPENSION=PASS");
        System.out.println("PERF006_PLAT028_MAP_REMOVE_HASH_SINGLE_INVOCATION=PASS");
        System.out.println("PERF006_PLAT028_MAP_REMOVE_SAME_MAP_CONFLICT=ERROR");
        System.out.println("PERF006_PLAT028_MAP_REMOVE_EXACT_VALUE=PASS");
    }

    @Test
    void equalitySuspensionPreservesRecordedHashFilterAndQueryDirection() throws Exception {
        try (LanguageScope scope = languageScope()) {
            ProtosPrelude prelude = core();
            ProtosActorExecutionDomain domain = new ProtosActorExecutionDomain();
            ProtosActivation module = activation(prelude, domain);
            ProtosMapValue map = new ProtosMapValue(prelude.mapPrototype());
            ProtosFutureValue gate = new ProtosFutureValue(prelude.futurePrototype(), domain);
            ProtosObjectValue skipped = key();
            ProtosObjectValue stored = key();
            ProtosObjectValue query = key();
            ProtosObjectValue skippedValue = key();
            ProtosObjectValue marker = key();
            AtomicInteger queryEqualityCalls = new AtomicInteger();
            AtomicInteger storedEqualityCalls = new AtomicInteger();

            stored.createLocalSlot(
                    "==",
                    ProtosClosureValue.nativeClosure(
                            (activation, supplied) -> {
                                storedEqualityCalls.incrementAndGet();
                                return ProtosBooleanValue.FALSE;
                            }));
            map.append(skipped, ProtosNumericHashKey.ofLong(8), skippedValue);
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
                            "perf006-plat028-map-remove-equality.protos"));

            ProtosTask remove =
                    execute(
                            domain,
                            module,
                            lowerRoot(
                                    scope.language(),
                                    "map.remove(query)",
                                    "perf006-plat028-map-remove-match.protos"));
            assertTrue(domain.dispatchOne());
            assertEquals(ProtosTask.State.SUSPENDED, remove.state());
            assertTrue(map.comparisonActive());
            assertEquals(1, queryEqualityCalls.get());
            assertEquals(0, storedEqualityCalls.get());
            assertEquals(2, map.keyedSize());

            assertTrue(gate.resolve(ProtosNullValue.INSTANCE, module));
            assertTrue(domain.dispatchOne());
            assertEquals(ProtosTask.State.COMPLETED, remove.state());
            assertSame(marker, remove.result().orElseThrow());
            assertEquals(1, queryEqualityCalls.get());
            assertEquals(0, storedEqualityCalls.get());
            assertEquals(1, map.keyedSize());
            assertSame(skipped, map.keyedSnapshot().get(0).key());
            assertSame(skippedValue, map.keyedSnapshot().get(0).value());
            assertFalse(map.comparisonActive());
        }

        System.out.println("PERF006_PLAT028_MAP_REMOVE_EQUALITY_SUSPENSION=PASS");
        System.out.println("PERF006_PLAT028_MAP_REMOVE_EQUALITY_DIRECTION=QUERY_TO_STORED");
        System.out.println("PERF006_PLAT028_MAP_REMOVE_RECORDED_HASH_FILTER=PASS");
    }

    @Test
    void closedAndFrozenRejectBeforeKeyCallbacks() throws Exception {
        try (LanguageScope scope = languageScope()) {
            ProtosPrelude prelude = core();
            ProtosActorExecutionDomain domain = new ProtosActorExecutionDomain();
            ProtosActivation module = activation(prelude, domain);
            ProtosMapValue closed = new ProtosMapValue(prelude.mapPrototype());
            ProtosObjectValue closedKey = key();
            ProtosObjectValue closedQuery = key();
            AtomicInteger closedHashCalls = new AtomicInteger();
            closed.append(closedKey, ProtosNumericHashKey.ofLong(1), key());
            closed.close();
            module.context().createLocalSlot("closed", closed);
            module.context().createLocalSlot("closedQuery", closedQuery);
            closedQuery.createLocalSlot(
                    "hash",
                    ProtosClosureValue.nativeClosure(
                            (activation, supplied) -> {
                                closedHashCalls.incrementAndGet();
                                return new ProtosIntegerValue(1L);
                            }));
            ProtosTask closedTask =
                    execute(
                            domain,
                            module,
                            lowerRoot(
                                    scope.language(),
                                    "closed.remove(closedQuery)",
                                    "perf006-plat028-map-remove-closed.protos"));
            assertTrue(domain.dispatchOne());
            assertEquals(ProtosTask.State.FAILED, closedTask.state());
            assertEquals(0, closedHashCalls.get());
            assertEquals(1, closed.keyedSize());

            ProtosActivation frozenModule = siblingActivation(module);
            ProtosMapValue frozen = new ProtosMapValue(prelude.mapPrototype());
            ProtosObjectValue frozenKey = key();
            ProtosObjectValue frozenQuery = key();
            AtomicInteger frozenHashCalls = new AtomicInteger();
            frozen.append(frozenKey, ProtosNumericHashKey.ofLong(1), key());
            frozen.freeze();
            module.context().createLocalSlot("frozen", frozen);
            module.context().createLocalSlot("frozenQuery", frozenQuery);
            frozenQuery.createLocalSlot(
                    "hash",
                    ProtosClosureValue.nativeClosure(
                            (activation, supplied) -> {
                                frozenHashCalls.incrementAndGet();
                                return new ProtosIntegerValue(1L);
                            }));
            ProtosTask frozenTask =
                    execute(
                            domain,
                            frozenModule,
                            lowerRoot(
                                    scope.language(),
                                    "frozen.remove(frozenQuery)",
                                    "perf006-plat028-map-remove-frozen.protos"));
            assertTrue(domain.dispatchOne());
            assertEquals(ProtosTask.State.FAILED, frozenTask.state());
            assertEquals(0, frozenHashCalls.get());
            assertEquals(1, frozen.keyedSize());
        }

        System.out.println("PERF006_PLAT028_MAP_REMOVE_CLOSED_BEFORE_CALLBACK=PASS");
        System.out.println("PERF006_PLAT028_MAP_REMOVE_FROZEN_BEFORE_CALLBACK=PASS");
    }

    @Test
    void callbackClosingMapPreservesSearchButPreventsRemoval() throws Exception {
        try (LanguageScope scope = languageScope()) {
            ProtosPrelude prelude = core();
            ProtosActorExecutionDomain domain = new ProtosActorExecutionDomain();
            ProtosActivation module = activation(prelude, domain);
            ProtosMapValue map = new ProtosMapValue(prelude.mapPrototype());
            ProtosObjectValue stored = key();
            ProtosObjectValue query = key();
            ProtosObjectValue marker = key();
            AtomicInteger equalityCalls = new AtomicInteger();
            map.append(stored, ProtosNumericHashKey.ofLong(7), marker);
            module.context().createLocalSlot("map", map);
            module.context().createLocalSlot("query", query);
            query.createLocalSlot(
                    "hash",
                    ProtosClosureValue.nativeClosure(
                            (activation, supplied) -> {
                                map.close();
                                return new ProtosIntegerValue(7);
                            }));
            query.createLocalSlot(
                    "==",
                    ProtosClosureValue.nativeClosure(
                            (activation, supplied) -> {
                                equalityCalls.incrementAndGet();
                                assertSame(stored, supplied.get(0));
                                return ProtosBooleanValue.TRUE;
                            }));

            ProtosTask remove =
                    execute(
                            domain,
                            module,
                            lowerRoot(
                                    scope.language(),
                                    "map.remove(query)",
                                    "perf006-plat028-map-remove-close-during-hash.protos"));
            assertTrue(domain.dispatchOne());
            assertEquals(ProtosTask.State.FAILED, remove.state());
            assertEquals(1, equalityCalls.get());
            assertEquals(1, map.keyedSize());
            assertSame(marker, map.keyedSnapshot().get(0).value());
            assertFalse(map.comparisonActive());
        }

        System.out.println("PERF006_PLAT028_MAP_REMOVE_POST_CALLBACK_OPEN_RECHECK=PASS");
        System.out.println("PERF006_PLAT028_MAP_REMOVE_SEARCH_BEFORE_RECHECK=PASS");
    }

    @Test
    void cancellationDuringEqualityReleasesScopeAndDoesNotRemove() throws Exception {
        try (LanguageScope scope = languageScope()) {
            ProtosPrelude prelude = core();
            ProtosActorExecutionDomain domain = new ProtosActorExecutionDomain();
            ProtosActivation module = activation(prelude, domain);
            ProtosMapValue map = new ProtosMapValue(prelude.mapPrototype());
            ProtosFutureValue gate = new ProtosFutureValue(prelude.futurePrototype(), domain);
            ProtosObjectValue stored = key();
            ProtosObjectValue query = key();
            ProtosObjectValue marker = key();
            map.append(stored, ProtosNumericHashKey.ofLong(7), marker);
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
                            "perf006-plat028-map-remove-cancel-equality.protos"));

            Execution execution =
                    executeWithFuture(
                            domain,
                            prelude,
                            module,
                            lowerRoot(
                                    scope.language(),
                                    "map.remove(query)",
                                    "perf006-plat028-map-remove-cancel.protos"));
            assertTrue(domain.dispatchOne());
            assertEquals(ProtosTask.State.SUSPENDED, execution.task().state());
            assertTrue(map.comparisonActive());
            assertEquals(1, map.keyedSize());

            assertTrue(execution.future().cancelRequest());
            assertEquals(ProtosTask.State.RUNNABLE, execution.task().state());
            assertTrue(domain.dispatchOne());
            assertEquals(ProtosTask.State.CANCELLED, execution.task().state());
            assertEquals(ProtosFutureValue.State.CANCELLED, execution.future().state());
            assertFalse(map.comparisonActive());
            assertEquals(1, map.keyedSize());
            assertSame(marker, map.keyedSnapshot().get(0).value());

            assertTrue(gate.resolve(ProtosNullValue.INSTANCE, module));
            assertFalse(domain.dispatchOne());
        }

        System.out.println("PERF006_PLAT028_MAP_REMOVE_CANCEL_UNWIND=PASS");
        System.out.println("PERF006_PLAT028_MAP_REMOVE_CANCEL_MUTATION=NO");
        System.out.println("PERF006_PLAT028_MAP_REMOVE_COMPARISON_SCOPE_LEAK=NO");
    }

    @Test
    void canonicalRecognitionKeepsRemoveBoundToExactStandardHome() throws Exception {
        ProtosPrelude prelude = core();
        ProtosObjectValue canonicalHome = prelude.mapPrototype();
        ProtosActivation caller = prelude.newModuleActivation();
        ProtosClosureValue remove =
                (ProtosClosureValue) canonicalHome.readLocalSlot("remove").orElseThrow();
        ProtosObjectValue aliasHome = new ProtosObjectValue(canonicalHome);
        aliasHome.createLocalSlot("remove", remove);
        ProtosClosureValue copied =
                ProtosClosureValue.nativeClosure(remove.nativeBody().orElseThrow());

        assertTrue(ProtosStandardMapProtocol.isStandardRemoveImplementation(remove));
        assertTrue(ProtosStandardMapProtocol.isStandardRemoveImplementation(copied));
        assertTrue(
                ProtosStandardMapProtocol.isCanonicalStandardRemoveSelection(
                        remove, canonicalHome, caller));
        assertFalse(
                ProtosStandardMapProtocol.isCanonicalStandardRemoveSelection(
                        remove, aliasHome, caller));
        assertFalse(
                ProtosStandardMapProtocol.isCanonicalStandardRemoveSelection(
                        copied, canonicalHome, caller));
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
