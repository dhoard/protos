/*
 * THE LICENSED WORK IS PROVIDED UNDER THE TERMS OF THE ADAPTIVE PUBLIC LICENSE
 * ("LICENSE") AS FIRST COMPLETED BY: Guillermo Adrián Molina. See LICENSE.TXT.
 */
package com.guillermomolina.protos.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.guillermomolina.protos.parser.ProtosParser;
import com.guillermomolina.protos.runtime.ProtosActivation;
import com.guillermomolina.protos.runtime.ProtosActorExecutionDomain;
import com.guillermomolina.protos.runtime.ProtosActorModuleState;
import com.guillermomolina.protos.runtime.ProtosBytesValue;
import com.guillermomolina.protos.runtime.ProtosClosureValue;
import com.guillermomolina.protos.runtime.ProtosFutureValue;
import com.guillermomolina.protos.runtime.ProtosIntegerValue;
import com.guillermomolina.protos.runtime.ProtosNullValue;
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

final class ProtosPerf006Plat028BytesEachCallbackTest {
    private static final LanguageReference<ProtosLanguage> LANGUAGE_REF =
            LanguageReference.create(ProtosLanguage.class);

    @Test
    void bytesEachOwnsSuspendingCallbackLoopInsideCPrime() throws Exception {
        try (LanguageScope scope = languageScope()) {
            ProtosPrelude prelude = core();
            ProtosActorExecutionDomain domain = new ProtosActorExecutionDomain();
            ProtosActivation module = activation(prelude, domain);
            ProtosFutureValue gate =
                    new ProtosFutureValue(prelude.futurePrototype(), domain);
            ProtosBytesValue bytes = bytes(prelude, 1, 2);
            AtomicInteger callbacks = new AtomicInteger();

            module.context().createLocalSlot("gate", gate);
            module.context().createLocalSlot("bytes", bytes);
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
                                    "bytes.each((octet) => { probe(octet)\ngate.value() })",
                                    "perf006-plat028-bytes-each-suspend.protos"));

            assertTrue(domain.dispatchOne());
            assertEquals(ProtosTask.State.SUSPENDED, task.state());
            assertEquals(1, callbacks.get());

            assertTrue(gate.resolve(ProtosNullValue.INSTANCE, module));
            assertTrue(domain.dispatchOne());

            assertEquals(ProtosTask.State.COMPLETED, task.state());
            assertSame(bytes, task.result().orElseThrow());
            assertEquals(2, callbacks.get());
        }

        System.out.println("PERF006_PLAT028_BYTES_EACH_SUSPEND_RESUME=PASS");
        System.out.println("PERF006_PLAT028_BYTES_EACH_COMPLETED_PREFIX_REPLAY=NO");
    }

    @Test
    void bytesEachKeepsEntrySnapshotAcrossCallbackMutation() throws Exception {
        try (LanguageScope scope = languageScope()) {
            ProtosPrelude prelude = core();
            ProtosActorExecutionDomain domain = new ProtosActorExecutionDomain();
            ProtosActivation module = activation(prelude, domain);
            ProtosBytesValue bytes = bytes(prelude, 1, 2);
            List<Long> observed = new ArrayList<>();

            module.context().createLocalSlot("bytes", bytes);
            module.context().createLocalSlot(
                    "mutate",
                    ProtosClosureValue.nativeClosure(
                            (activation, supplied) -> {
                                ProtosIntegerValue octet =
                                        (ProtosIntegerValue) supplied.get(0);
                                observed.add(octet.longValue());
                                if (observed.size() == 1) {
                                    bytes.indexedPut(
                                            1,
                                            new ProtosIntegerValue(99));
                                }
                                return new ProtosIntegerValue(77);
                            }));

            ProtosTask task =
                    execute(
                            domain,
                            module,
                            lowerRoot(
                                    scope.language(),
                                    "bytes.each((octet) => mutate(octet))",
                                    "perf006-plat028-bytes-each-snapshot.protos"));

            assertTrue(domain.dispatchOne());
            assertEquals(ProtosTask.State.COMPLETED, task.state());
            assertSame(bytes, task.result().orElseThrow());
            assertEquals(
                    List.of(1L, 2L),
                    observed);
            assertEquals(String.valueOf("99"), com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerDecimalText(bytes.indexedAt(1)));
        }

        System.out.println("PERF006_PLAT028_BYTES_EACH_SNAPSHOT=PASS");
        System.out.println("PERF006_PLAT028_BYTES_EACH_CALLBACK_RESULT_IGNORED=PASS");
        System.out.println("PERF006_PLAT028_BYTES_EACH_RESULT_RECEIVER_IDENTITY=PASS");
    }

    private static ProtosBytesValue bytes(
            ProtosPrelude prelude,
            long... values) {
        ProtosBytesValue bytes =
                new ProtosBytesValue(prelude.bytesPrototypeForRuntime());
        for (long value : values) {
            bytes.indexedAdd(
                    new ProtosIntegerValue(value));
        }
        return bytes;
    }

    private static ProtosTask execute(
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
                        .canonicalize(
                                new ProtosParser(characters)
                                        .parseProgram());
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
