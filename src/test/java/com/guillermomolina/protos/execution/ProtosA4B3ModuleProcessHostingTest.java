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
import com.guillermomolina.protos.runtime.ProtosActorModuleState;
import com.guillermomolina.protos.runtime.ProtosBooleanValue;
import com.guillermomolina.protos.runtime.ProtosEnvironmentValue;
import com.guillermomolina.protos.runtime.ProtosIntegerValue;
import com.guillermomolina.protos.runtime.ProtosModuleKey;
import com.guillermomolina.protos.runtime.ProtosObjectValue;
import com.guillermomolina.protos.runtime.ProtosPrelude;
import java.io.InputStream;
import java.io.OutputStream;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.graalvm.polyglot.Engine;
import org.junit.jupiter.api.Test;

final class ProtosA4B3ModuleProcessHostingTest {
    private static final Path CORE = Path.of("protos", "lib", "core");
    private static final ProtosModuleKey MAIN = new ProtosModuleKey("test:Main");

    @Test
    void hostedOrdinaryImportParsesResolvedModuleInsideOwningProcessContext() throws Exception {
        MemoryResolver resolver =
                new MemoryResolver()
                        .module(
                                "m",
                                "local: () => { 42 }\n"
                                        + "box: { answer: local() }\n"
                                        + "value: box.answer");
        ProtosPrelude prelude = new ProtosCoreBootstrap().bootstrap(CORE, resolver);
        ProtosStandaloneProcessBootstrap.Result bootstrap = bootstrap(prelude);
        try (ProtosPolyglotRuntimeHost host = ProtosPolyglotRuntimeHost.open()) {
            ProtosPolyglotProcessContext context = host(host, bootstrap);
            try {
                Engine engine = context.engineForTesting();
                ProtosExecutionOutcome outcome =
                        context.executeModuleSource(
                                ProtosModuleSource.fromCharacters(
                                        new ProtosModuleKey("entry"),
                                        "import(\"m\")"),
                                bootstrap.activation());

                assertEquals(ProtosExecutionOutcome.State.COMPLETED, outcome.state());
                ProtosObjectValue module =
                        assertInstanceOf(ProtosObjectValue.class, outcome.value());
                ProtosIntegerValue value =
                        assertInstanceOf(
                                ProtosIntegerValue.class,
                                module.readLocalSlot("value").orElseThrow());
                assertEquals(String.valueOf("42"), com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerDecimalText(value));
                assertEquals(1, resolver.loads("m"));
                assertSame(engine, context.engineForTesting());
            } finally {
                bootstrap.process().requestTerminationForRuntime();
            }
        }
    }

    @Test
    void hostedCanonicalInitialModuleSelfEntersProcessContextAndPreservesCacheIdentity()
            throws Exception {
        MemoryResolver resolver =
                new MemoryResolver()
                        .alias("self", MAIN.canonicalId())
                        .module(
                                MAIN.canonicalId(),
                                "marker: (() => { 42 })()\n"
                                        + "Again: import(\"self\")\n"
                                        + "Again.marker === marker");
        ProtosPrelude prelude = new ProtosCoreBootstrap().bootstrap(CORE, resolver);
        ProtosStandaloneProcessBootstrap.Result bootstrap = bootstrap(prelude);
        try (ProtosPolyglotRuntimeHost host = ProtosPolyglotRuntimeHost.open()) {
            host(host, bootstrap);
            try {
                ProtosExecutionOutcome outcome =
                        ProtosCanonicalInitialModuleExecution.execute(
                                prelude,
                                resolver,
                                MAIN,
                                bootstrap.activation());

                assertEquals(ProtosExecutionOutcome.State.COMPLETED, outcome.state());
                assertSame(ProtosBooleanValue.TRUE, outcome.value());
                ProtosActorModuleState.ModuleRecord record =
                        bootstrap.activation().actorModuleState().lookup(MAIN).orElseThrow();
                assertEquals(ProtosActorModuleState.InitializationState.READY, record.state());
                assertSame(bootstrap.activation().context(), record.instance());
                assertEquals(1, resolver.loads(MAIN.canonicalId()));
            } finally {
                bootstrap.process().requestTerminationForRuntime();
            }
        }
    }

    @Test
    void productionModulePathsUsePublicParseAndUnhostedStagingUsesBytecodeBoundary()
            throws Exception {
        String moduleRuntime =
                Files.readString(
                        Path.of(
                                "src/main/java/com/guillermomolina/protos/execution/ProtosModuleRuntime.java"));
        String canonical =
                Files.readString(
                        Path.of(
                                "src/main/java/com/guillermomolina/protos/execution/ProtosCanonicalInitialModuleExecution.java"));
        String workspace =
                Files.readString(
                        Path.of(
                                "src/main/java/com/guillermomolina/protos/execution/ProtosWorkspacePackageApplicationExecution.java"));

        assertTrue(moduleRuntime.contains("executeModuleSource(source, moduleActivation);"));
        assertTrue(moduleRuntime.contains("process.callInExecutionHostForRuntime("));
        assertTrue(moduleRuntime.contains("materializeModuleSource(source)"));
        assertFalse(moduleRuntime.contains(".parsePublic(source.source())"));
        assertFalse(moduleRuntime.contains("compiler.compile(source).call(moduleActivation);"));
        assertFalse(moduleRuntime.contains("compiler.compile(source).call(activation);"));
        assertTrue(moduleRuntime.contains("ProtosPolyglotExecutionContext.open("));
        assertTrue(moduleRuntime.contains("executionContext.callEntered("));
        assertTrue(moduleRuntime.contains("materializeModuleSource(source)"));

        assertTrue(canonical.contains("process.callInExecutionHostForRuntime("));
        assertTrue(canonical.contains("materializeModuleSource(source)"));
        assertFalse(canonical.contains("parsePublic(source.source())"));
        assertTrue(canonical.contains("process.callInExecutionHostForRuntime("));
        assertTrue(canonical.contains("ProtosPolyglotExecutionContext.open("));
        assertTrue(canonical.contains("executionContext.executeModuleSource(source, activation)"));
        assertFalse(canonical.contains("new ProtosSourceCompiler().compile(source)"));

        assertTrue(workspace.contains("runtimeHost.hostProcess("));
        assertTrue(workspace.contains("ProtosCanonicalInitialModuleExecution.execute("));
        assertFalse(workspace.contains("processContext.callForRuntime("));
        assertFalse(workspace.contains("CanonicalModuleIOException"));
    }

    private static ProtosPolyglotProcessContext host(
            ProtosPolyglotRuntimeHost host,
            ProtosStandaloneProcessBootstrap.Result bootstrap) {
        return host.hostProcess(
                bootstrap.process(),
                InputStream.nullInputStream(),
                OutputStream.nullOutputStream(),
                OutputStream.nullOutputStream());
    }

    private static ProtosStandaloneProcessBootstrap.Result bootstrap(ProtosPrelude prelude) {
        return ProtosStandaloneProcessBootstrap.create(
                prelude,
                List.of(),
                exactEnvironmentDomain(),
                List.of(),
                null,
                null,
                null,
                null,
                null,
                null,
                null);
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

    private static final class MemoryResolver implements ProtosModuleResolver {
        private final Map<String, String> aliases = new HashMap<>();
        private final Map<String, String> sources = new HashMap<>();
        private final Map<String, Integer> loadCounts = new HashMap<>();
        private final AtomicInteger resolveCalls = new AtomicInteger();

        MemoryResolver module(String key, String source) {
            aliases.put(key, key);
            sources.put(key, source);
            return this;
        }

        MemoryResolver alias(String spelling, String key) {
            aliases.put(spelling, key);
            return this;
        }

        int loads(String key) {
            return loadCounts.getOrDefault(key, 0);
        }

        @Override
        public ProtosModuleKey resolve(
                String exactSpecifier,
                Optional<ProtosModuleKey> importingModule)
                throws Exception {
            resolveCalls.incrementAndGet();
            String key = aliases.get(exactSpecifier);
            if (key == null) {
                throw new java.io.IOException("not found");
            }
            return new ProtosModuleKey(key);
        }

        @Override
        public ProtosModuleSource loadSource(ProtosModuleKey key) throws Exception {
            loadCounts.merge(key.canonicalId(), 1, Integer::sum);
            String source = sources.get(key.canonicalId());
            if (source == null) {
                throw new java.io.IOException("not found");
            }
            return ProtosModuleSource.fromCharacters(key, source);
        }
    }
}
