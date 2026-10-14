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
import com.guillermomolina.protos.runtime.ProtosEnvironmentValue;
import com.guillermomolina.protos.runtime.ProtosFutureValue;
import com.guillermomolina.protos.runtime.ProtosNullValue;
import com.guillermomolina.protos.runtime.ProtosObjectValue;
import com.guillermomolina.protos.runtime.ProtosPrelude;
import com.guillermomolina.protos.runtime.ProtosProcessRuntime;
import com.guillermomolina.protos.runtime.ProtosStringValue;
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

final class ProtosPerf006Plat028EnvironmentEachCallbackTest {
    private static final LanguageReference<ProtosLanguage> LANGUAGE_REF =
            LanguageReference.create(ProtosLanguage.class);

    @Test
    void environmentEachOwnsSortedSuspendingCallbackLoopInsideCPrime() throws Exception {
        try (LanguageScope scope = languageScope()) {
            ProtosPrelude prelude = core();
            ProtosActorExecutionDomain domain = new ProtosActorExecutionDomain();
            ProtosActivation module = activation(prelude, domain);
            ProtosEnvironmentValue environment =
                    environment(
                            prelude,
                            ProtosStandardEnvironmentProtocol.createPrototype(),
                            List.of(
                                    new ProtosEnvironmentValue.NativeEntry("B", "two"),
                                    new ProtosEnvironmentValue.NativeEntry("A", "one")));
            ProtosFutureValue gate =
                    new ProtosFutureValue(prelude.futurePrototype(), domain);
            AtomicInteger callbacks = new AtomicInteger();
            List<String> names = new ArrayList<>();
            List<String> values = new ArrayList<>();

            module.context().createLocalSlot("environment", environment);
            module.context().createLocalSlot("gate", gate);
            module.context().createLocalSlot(
                    "probe",
                    ProtosClosureValue.nativeClosure(
                            (activation, supplied) -> {
                                callbacks.incrementAndGet();
                                names.add(((ProtosStringValue) supplied.get(0)).value());
                                values.add(((ProtosStringValue) supplied.get(1)).value());
                                return ProtosNullValue.INSTANCE;
                            }));

            ProtosTask task =
                    execute(
                            domain,
                            module,
                            lowerRoot(
                                    scope.language(),
                                    "environment.each((name, value) => { probe(name, value)\ngate.value() })",
                                    "perf006-plat028-environment-each-suspend.protos"));

            assertTrue(domain.dispatchOne());
            assertEquals(ProtosTask.State.SUSPENDED, task.state());
            assertEquals(1, callbacks.get());
            assertEquals(List.of("A"), names);
            assertEquals(List.of("one"), values);

            assertTrue(gate.resolve(ProtosNullValue.INSTANCE, module));
            assertTrue(domain.dispatchOne());

            assertEquals(ProtosTask.State.COMPLETED, task.state());
            assertSame(environment, task.result().orElseThrow());
            assertEquals(2, callbacks.get());
            assertEquals(List.of("A", "B"), names);
            assertEquals(List.of("one", "two"), values);
        }

        System.out.println("PERF006_PLAT028_ENVIRONMENT_EACH_SUSPEND_RESUME=PASS");
        System.out.println("PERF006_PLAT028_ENVIRONMENT_EACH_COMPLETED_PREFIX_REPLAY=NO");
        System.out.println("PERF006_PLAT028_ENVIRONMENT_EACH_SORTED_ORDER=PASS");
        System.out.println("PERF006_PLAT028_ENVIRONMENT_EACH_RESULT_RECEIVER_IDENTITY=PASS");
    }

    @Test
    void environmentEachPrevalidatesWholePortableSnapshotBeforeFirstCallback()
            throws Exception {
        try (LanguageScope scope = languageScope()) {
            ProtosPrelude prelude = core();
            ProtosActorExecutionDomain domain = new ProtosActorExecutionDomain();
            ProtosActivation module = activation(prelude, domain);
            String invalidUnicode = String.valueOf((char) 0xD800);
            ProtosEnvironmentValue environment =
                    environment(
                            prelude,
                            ProtosStandardEnvironmentProtocol.createPrototype(),
                            List.of(
                                    new ProtosEnvironmentValue.NativeEntry("A", "ok"),
                                    new ProtosEnvironmentValue.NativeEntry("B", invalidUnicode)));
            AtomicInteger callbacks = new AtomicInteger();

            module.context().createLocalSlot("environment", environment);
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
                                    "environment.each((name, value) => probe(name, value))",
                                    "perf006-plat028-environment-each-prevalidation.protos"));

            assertTrue(domain.dispatchOne());
            assertEquals(ProtosTask.State.FAILED, task.state());
            assertEquals(0, callbacks.get());
        }

        System.out.println("PERF006_PLAT028_ENVIRONMENT_EACH_WHOLE_SNAPSHOT_PREVALIDATION=PASS");
        System.out.println("PERF006_PLAT028_ENVIRONMENT_EACH_CALLBACK_BEFORE_VALIDATION=NO");
    }

    @Test
    void canonicalRecognitionRejectsCopiedStandardEachAtAnotherHome() throws Exception {
        ProtosPrelude prelude = core();
        ProtosObjectValue canonicalHome =
                ProtosStandardEnvironmentProtocol.createPrototype();
        ProtosClosureValue each =
                (ProtosClosureValue) canonicalHome.readLocalSlot("each").orElseThrow();

        ProtosEnvironmentValue canonical =
                environment(
                        prelude,
                        canonicalHome,
                        List.of(new ProtosEnvironmentValue.NativeEntry("A", "one")));

        ProtosObjectValue aliasHome =
                new ProtosObjectValue(ProtosObjectValue.rootObject());
        aliasHome.createLocalSlot("each", each);
        aliasHome.freeze();
        ProtosEnvironmentValue aliased =
                environment(
                        prelude,
                        aliasHome,
                        List.of(new ProtosEnvironmentValue.NativeEntry("A", "one")));

        assertTrue(
                ProtosStandardEnvironmentProtocol.isCanonicalStandardEachSelection(
                        each, canonical, canonicalHome));
        assertFalse(
                ProtosStandardEnvironmentProtocol.isCanonicalStandardEachSelection(
                        each, canonical, aliasHome));
        assertFalse(
                ProtosStandardEnvironmentProtocol.isCanonicalStandardEachSelection(
                        each, aliased, aliasHome));

        System.out.println("PERF006_PLAT028_ENVIRONMENT_EACH_EXACT_HOME=PASS");
        System.out.println("PERF006_PLAT028_ENVIRONMENT_EACH_COPIED_HOME_PRIVILEGE=NO");
    }

    private static ProtosEnvironmentValue environment(
            ProtosPrelude prelude,
            ProtosObjectValue prototype,
            List<ProtosEnvironmentValue.NativeEntry> entries) {
        ProtosProcessRuntime process =
                new ProtosProcessRuntime(prelude.actorRefPrototypeForRuntime());
        assertEquals(
                ProtosProcessRuntime.EnvironmentSnapshotState.AVAILABLE,
                process.establishEnvironmentForRuntime(
                        prototype,
                        exactDomain(),
                        entries));
        return process.environmentSnapshotForRuntime().orElseThrow();
    }

    private static ProtosEnvironmentValue.NativeNameDomain exactDomain() {
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
