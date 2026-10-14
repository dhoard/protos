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
import com.guillermomolina.protos.runtime.ProtosClosureValue;
import com.guillermomolina.protos.runtime.ProtosFutureValue;
import com.guillermomolina.protos.runtime.ProtosMapValue;
import com.guillermomolina.protos.runtime.ProtosNumericHashKey;
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

final class ProtosPerf006Plat028MapEachCallbackTest {
    private static final LanguageReference<ProtosLanguage> LANGUAGE_REF =
            LanguageReference.create(ProtosLanguage.class);

    @Test
    void mapEachOwnsSuspendingCallbackLoopInsideCPrime() throws Exception {
        try (LanguageScope scope = languageScope()) {
            ProtosPrelude prelude = core();
            ProtosActorExecutionDomain domain = new ProtosActorExecutionDomain();
            ProtosActivation module = activation(prelude, domain);
            ProtosMapValue map = map(prelude);
            ProtosFutureValue gate =
                    new ProtosFutureValue(prelude.futurePrototype(), domain);
            AtomicInteger callbacks = new AtomicInteger();

            append(map, key(), new ProtosIntegerValue(1L));
            append(map, key(), new ProtosIntegerValue(2L));
            module.context().createLocalSlot("map", map);
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
                                    "map.each((key, value) => { probe(key, value)\ngate.value() })",
                                    "perf006-plat028-map-each-suspend.protos"));

            assertTrue(domain.dispatchOne());
            assertEquals(ProtosTask.State.SUSPENDED, task.state());
            assertEquals(1, callbacks.get());

            assertTrue(gate.resolve(ProtosNullValue.INSTANCE, module));
            assertTrue(domain.dispatchOne());

            assertEquals(ProtosTask.State.COMPLETED, task.state());
            assertSame(map, task.result().orElseThrow());
            assertEquals(2, callbacks.get());
        }

        System.out.println("PERF006_PLAT028_MAP_EACH_SUSPEND_RESUME=PASS");
        System.out.println("PERF006_PLAT028_MAP_EACH_COMPLETED_PREFIX_REPLAY=NO");
        System.out.println("PERF006_PLAT028_MAP_EACH_RESULT_RECEIVER_IDENTITY=PASS");
    }

    @Test
    void mapEachKeepsEntrySnapshotAcrossCallbackMutation() throws Exception {
        try (LanguageScope scope = languageScope()) {
            ProtosPrelude prelude = core();
            ProtosActorExecutionDomain domain = new ProtosActorExecutionDomain();
            ProtosActivation module = activation(prelude, domain);
            ProtosMapValue map = map(prelude);
            ProtosObjectValue firstKey = key();
            ProtosObjectValue secondKey = key();
            ProtosIntegerValue firstValue = new ProtosIntegerValue(1L);
            ProtosIntegerValue secondValue = new ProtosIntegerValue(2L);
            ProtosObjectValue appendedKey = key();
            ProtosIntegerValue appendedValue = new ProtosIntegerValue(3);
            ProtosIntegerValue replacementValue = new ProtosIntegerValue(99);
            AtomicInteger callbacks = new AtomicInteger();
            List<List<Object>> seen = new ArrayList<>();

            append(map, firstKey, firstValue);
            append(map, secondKey, secondValue);
            module.context().createLocalSlot("map", map);
            module.context().createLocalSlot(
                    "mutate",
                    ProtosClosureValue.nativeClosure(
                            (activation, supplied) -> {
                                seen.add(List.copyOf(supplied));
                                if (callbacks.getAndIncrement() == 0) {
                                    map.replaceValue(
                                            map.keyedSnapshot().get(1),
                                            replacementValue);
                                    map.append(
                                            appendedKey,
                                            ProtosNumericHashKey.ofLong(map.keyedSize() + 1L),
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
                                    "map.each((key, value) => mutate(key, value))",
                                    "perf006-plat028-map-each-snapshot.protos"));

            assertTrue(domain.dispatchOne());
            assertEquals(ProtosTask.State.COMPLETED, task.state());
            assertSame(map, task.result().orElseThrow());
            assertEquals(2, callbacks.get());
            assertEquals(2, seen.size());
            assertSame(firstKey, seen.get(0).get(0));
            assertSame(firstValue, seen.get(0).get(1));
            assertSame(secondKey, seen.get(1).get(0));
            assertSame(secondValue, seen.get(1).get(1));
            assertEquals(3, map.keyedSize());
        }

        System.out.println("PERF006_PLAT028_MAP_EACH_SNAPSHOT=PASS");
        System.out.println("PERF006_PLAT028_MAP_EACH_CALLBACK_MUTATION_PREFIX=STABLE");
    }

    @Test
    void canonicalRecognitionRejectsCopiedStandardEachAtAnotherHome() throws Exception {
        ProtosPrelude prelude = core();
        ProtosObjectValue canonicalHome = prelude.mapPrototype();
        ProtosClosureValue each =
                (ProtosClosureValue) canonicalHome.readLocalSlot("each").orElseThrow();
        ProtosActivation caller = prelude.newModuleActivation();

        ProtosObjectValue aliasHome = new ProtosObjectValue(canonicalHome);
        aliasHome.createLocalSlot("each", each);
        ProtosClosureValue copiedBody =
                ProtosClosureValue.nativeClosure(each.nativeBody().orElseThrow());

        assertTrue(ProtosStandardMapProtocol.isStandardEachImplementation(each));
        assertTrue(ProtosStandardMapProtocol.isStandardEachImplementation(copiedBody));
        assertTrue(
                ProtosStandardMapProtocol.isCanonicalStandardEachSelection(
                        each, canonicalHome, caller));
        assertFalse(
                ProtosStandardMapProtocol.isCanonicalStandardEachSelection(
                        each, aliasHome, caller));
        assertFalse(
                ProtosStandardMapProtocol.isCanonicalStandardEachSelection(
                        copiedBody, canonicalHome, caller));

        System.out.println("PERF006_PLAT028_MAP_EACH_EXACT_HOME=PASS");
        System.out.println("PERF006_PLAT028_MAP_EACH_COPIED_HOME_PRIVILEGE=NO");
        System.out.println("PERF006_PLAT028_MAP_EACH_COPIED_BODY_PRIVILEGE=NO");
    }

    private static ProtosMapValue map(ProtosPrelude prelude) {
        return new ProtosMapValue(prelude.mapPrototype());
    }

    private static ProtosObjectValue key() {
        return new ProtosObjectValue(ProtosObjectValue.rootObject());
    }

    private static void append(ProtosMapValue map, Object key, Object value) {
        map.append(key, ProtosNumericHashKey.ofLong(map.keyedSize() + 1L), value);
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
