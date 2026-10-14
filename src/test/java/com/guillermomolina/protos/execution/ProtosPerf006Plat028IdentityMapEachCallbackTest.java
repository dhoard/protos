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
import com.guillermomolina.protos.runtime.ProtosNumericHashKey;
import com.guillermomolina.protos.runtime.ProtosIdentityMapValue;
import com.guillermomolina.protos.runtime.ProtosIntegerValue;
import com.guillermomolina.protos.runtime.ProtosNullValue;
import com.guillermomolina.protos.runtime.ProtosObjectValue;
import com.guillermomolina.protos.runtime.ProtosPrelude;
import com.guillermomolina.protos.runtime.ProtosTask;
import com.guillermomolina.protos.semantic.Canonicalizer;
import com.guillermomolina.protos.semantic.ast.CanonicalSequence;
import com.oracle.truffle.api.TruffleLanguage.LanguageReference;
import com.oracle.truffle.api.source.Source;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;

final class ProtosPerf006Plat028IdentityMapEachCallbackTest {
    private static final LanguageReference<ProtosLanguage> LANGUAGE_REF =
            LanguageReference.create(ProtosLanguage.class);

    @Test
    void identityMapEachOwnsSuspendingCallbackLoopInsideCPrime() throws Exception {
        try (LanguageScope scope = languageScope()) {
            ProtosPrelude prelude = core();
            ProtosActorExecutionDomain domain = new ProtosActorExecutionDomain();
            ProtosActivation module = activation(prelude, domain);
            ProtosIdentityMapValue identityMap = identityMap(prelude);
            ProtosFutureValue gate =
                    new ProtosFutureValue(prelude.futurePrototype(), domain);
            AtomicInteger callbacks = new AtomicInteger();

            append(identityMap, key(), new ProtosIntegerValue(1L));
            append(identityMap, key(), new ProtosIntegerValue(2L));
            module.context().createLocalSlot("identityMap", identityMap);
            module.context().createLocalSlot("gate", gate);
            module.context().createLocalSlot(
                    "probe",
                    ProtosClosureValue.nativeClosure(
                            (activation, supplied) -> {
                                callbacks.incrementAndGet();
                                return ProtosNullValue.INSTANCE;
                            }));

            ProtosTask task =
                    execute(
                            domain,
                            module,
                            lowerRoot(
                                    scope.language(),
                                    "identityMap.each((key, value) => { probe(key, value)\ngate.value() })",
                                    "perf006-plat028-identity-map-each-suspend.protos"));

            assertTrue(domain.dispatchOne());
            assertEquals(ProtosTask.State.SUSPENDED, task.state());
            assertEquals(1, callbacks.get());

            assertTrue(gate.resolve(ProtosNullValue.INSTANCE, module));
            assertTrue(domain.dispatchOne());

            assertEquals(ProtosTask.State.COMPLETED, task.state());
            assertSame(identityMap, task.result().orElseThrow());
            assertEquals(2, callbacks.get());
        }

        System.out.println("PERF006_PLAT028_IDENTITY_MAP_EACH_SUSPEND_RESUME=PASS");
        System.out.println("PERF006_PLAT028_IDENTITY_MAP_EACH_COMPLETED_PREFIX_REPLAY=NO");
        System.out.println("PERF006_PLAT028_IDENTITY_MAP_EACH_RESULT_RECEIVER_IDENTITY=PASS");
    }

    @Test
    void identityMapEachKeepsEntrySnapshotAcrossCallbackMutation() throws Exception {
        try (LanguageScope scope = languageScope()) {
            ProtosPrelude prelude = core();
            ProtosActorExecutionDomain domain = new ProtosActorExecutionDomain();
            ProtosActivation module = activation(prelude, domain);
            ProtosIdentityMapValue identityMap = identityMap(prelude);
            ProtosObjectValue firstKey = key();
            ProtosObjectValue secondKey = key();
            ProtosIntegerValue firstValue = new ProtosIntegerValue(1L);
            ProtosIntegerValue secondValue = new ProtosIntegerValue(2L);
            ProtosObjectValue appendedKey = key();
            ProtosIntegerValue appendedValue = new ProtosIntegerValue(3);
            ProtosIntegerValue replacementValue = new ProtosIntegerValue(99);
            AtomicInteger callbacks = new AtomicInteger();
            List<List<Object>> seen = new ArrayList<>();

            append(identityMap, firstKey, firstValue);
            append(identityMap, secondKey, secondValue);
            module.context().createLocalSlot("identityMap", identityMap);
            module.context().createLocalSlot(
                    "mutate",
                    ProtosClosureValue.nativeClosure(
                            (activation, supplied) -> {
                                seen.add(List.copyOf(supplied));
                                if (callbacks.getAndIncrement() == 0) {
                                    identityMap.replaceValue(
                                            identityMap.keyedSnapshot().get(1),
                                            replacementValue);
                                    identityMap.append(
                                            appendedKey,
                                            ProtosNumericHashKey.fromIdentity(appendedKey),
                                            appendedValue);
                                }
                                return ProtosNullValue.INSTANCE;
                            }));

            ProtosTask task =
                    execute(
                            domain,
                            module,
                            lowerRoot(
                                    scope.language(),
                                    "identityMap.each((key, value) => mutate(key, value))",
                                    "perf006-plat028-identity-map-each-snapshot.protos"));

            assertTrue(domain.dispatchOne());
            assertEquals(ProtosTask.State.COMPLETED, task.state());
            assertSame(identityMap, task.result().orElseThrow());
            assertEquals(2, callbacks.get());
            assertEquals(2, seen.size());
            assertSame(firstKey, seen.get(0).get(0));
            assertSame(firstValue, seen.get(0).get(1));
            assertSame(secondKey, seen.get(1).get(0));
            assertSame(secondValue, seen.get(1).get(1));
            assertEquals(3, identityMap.keyedSize());
        }

        System.out.println("PERF006_PLAT028_IDENTITY_MAP_EACH_SNAPSHOT=PASS");
        System.out.println("PERF006_PLAT028_IDENTITY_MAP_EACH_CALLBACK_MUTATION_PREFIX=STABLE");
    }

    @Test
    void atIfAbsentFallbackSuspensionDoesNotReplayIdentityLookup() throws Exception {
        try (LanguageScope scope = languageScope()) {
            ProtosPrelude prelude = core();
            ProtosActorExecutionDomain domain = new ProtosActorExecutionDomain();
            ProtosActivation module = activation(prelude, domain);
            ProtosIdentityMapValue identityMap = identityMap(prelude);
            ProtosFutureValue gate =
                    new ProtosFutureValue(prelude.futurePrototype(), domain);
            ProtosObjectValue query = key();
            ProtosObjectValue marker = key();
            AtomicInteger keyCallbacks = new AtomicInteger();
            AtomicInteger fallbackCalls = new AtomicInteger();

            query.createLocalSlot(
                    "hash",
                    ProtosClosureValue.nativeClosure(
                            (activation, supplied) -> {
                                keyCallbacks.incrementAndGet();
                                return new ProtosIntegerValue(7);
                            }));
            query.createLocalSlot(
                    "==",
                    ProtosClosureValue.nativeClosure(
                            (activation, supplied) -> {
                                keyCallbacks.incrementAndGet();
                                return ProtosBooleanValue.TRUE;
                            }));

            module.context().createLocalSlot("identityMap", identityMap);
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

            ProtosTask lookup =
                    execute(
                            domain,
                            module,
                            lowerRoot(
                                    scope.language(),
                                    "identityMap.atIfAbsent(query, () => { "
                                            + "fallbackProbe()\n"
                                            + "gate.value()\n"
                                            + "marker })",
                                    "i049-identity-map-at-if-absent-fallback-suspend.protos"));

            assertTrue(domain.dispatchOne());
            assertEquals(ProtosTask.State.SUSPENDED, lookup.state());
            assertEquals(0, keyCallbacks.get());
            assertEquals(1, fallbackCalls.get());
            assertEquals(0, identityMap.keyedSize());

            assertTrue(gate.resolve(ProtosNullValue.INSTANCE, module));
            assertTrue(domain.dispatchOne());

            assertEquals(ProtosTask.State.COMPLETED, lookup.state());
            assertSame(marker, lookup.result().orElseThrow());
            assertEquals(0, keyCallbacks.get());
            assertEquals(1, fallbackCalls.get());
            assertEquals(0, identityMap.keyedSize());
        }

        System.out.println("I049_IDENTITY_MAP_AT_IF_ABSENT_FALLBACK_SUSPEND_RESUME=PASS");
        System.out.println("I049_IDENTITY_MAP_AT_IF_ABSENT_KEY_CALLBACKS=0");
        System.out.println("I049_IDENTITY_MAP_AT_IF_ABSENT_FALLBACK_REPLAY=NO");
    }

    @Test
    void canonicalRecognitionRejectsCopiedStandardIdentityMapBehavior() throws Exception {
        ProtosPrelude prelude = core();
        ProtosObjectValue canonicalHome = prelude.identityMapPrototype();
        ProtosClosureValue each =
                (ProtosClosureValue) canonicalHome.readLocalSlot("each").orElseThrow();
        ProtosClosureValue atIfAbsent =
                (ProtosClosureValue) canonicalHome.readLocalSlot("atIfAbsent").orElseThrow();
        ProtosActivation caller = prelude.newModuleActivation();

        ProtosObjectValue aliasHome = new ProtosObjectValue(canonicalHome);
        aliasHome.createLocalSlot("each", each);
        aliasHome.createLocalSlot("atIfAbsent", atIfAbsent);
        ProtosClosureValue copiedEach =
                ProtosClosureValue.nativeClosure(each.nativeBody().orElseThrow());
        ProtosClosureValue copiedAtIfAbsent =
                ProtosClosureValue.nativeClosure(atIfAbsent.nativeBody().orElseThrow());

        assertTrue(ProtosStandardIdentityMapProtocol.isStandardEachImplementation(each));
        assertTrue(ProtosStandardIdentityMapProtocol.isStandardEachImplementation(copiedEach));
        assertTrue(
                ProtosStandardIdentityMapProtocol.isStandardAtIfAbsentImplementation(
                        atIfAbsent));
        assertTrue(
                ProtosStandardIdentityMapProtocol.isStandardAtIfAbsentImplementation(
                        copiedAtIfAbsent));

        assertTrue(
                ProtosStandardIdentityMapProtocol.isCanonicalStandardEachSelection(
                        each, canonicalHome, caller));
        assertTrue(
                ProtosStandardIdentityMapProtocol.isCanonicalStandardAtIfAbsentSelection(
                        atIfAbsent, canonicalHome, caller));

        assertFalse(
                ProtosStandardIdentityMapProtocol.isCanonicalStandardEachSelection(
                        each, aliasHome, caller));
        assertFalse(
                ProtosStandardIdentityMapProtocol.isCanonicalStandardAtIfAbsentSelection(
                        atIfAbsent, aliasHome, caller));
        assertFalse(
                ProtosStandardIdentityMapProtocol.isCanonicalStandardEachSelection(
                        copiedEach, canonicalHome, caller));
        assertFalse(
                ProtosStandardIdentityMapProtocol.isCanonicalStandardAtIfAbsentSelection(
                        copiedAtIfAbsent, canonicalHome, caller));

        System.out.println("PERF006_PLAT028_IDENTITY_MAP_EACH_EXACT_HOME=PASS");
        System.out.println("PERF006_PLAT028_IDENTITY_MAP_EACH_COPIED_HOME_PRIVILEGE=NO");
        System.out.println("PERF006_PLAT028_IDENTITY_MAP_EACH_COPIED_BODY_PRIVILEGE=NO");
        System.out.println("I049_IDENTITY_MAP_AT_IF_ABSENT_EXACT_HOME=PASS");
        System.out.println("I049_IDENTITY_MAP_AT_IF_ABSENT_COPIED_HOME_PRIVILEGE=NO");
        System.out.println("I049_IDENTITY_MAP_AT_IF_ABSENT_COPIED_BODY_PRIVILEGE=NO");
    }

    private static ProtosIdentityMapValue identityMap(ProtosPrelude prelude) {
        return new ProtosIdentityMapValue(prelude.identityMapPrototype());
    }

    private static ProtosObjectValue key() {
        return new ProtosObjectValue(ProtosObjectValue.rootObject());
    }

    private static void append(ProtosIdentityMapValue map, Object key, Object value) {
        map.append(key, ProtosNumericHashKey.fromIdentity(key), value);
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

    private record LanguageScope(Context context, ProtosLanguage language)
            implements AutoCloseable {
        @Override
        public void close() {
            context.leave();
            context.close();
        }
    }
}
