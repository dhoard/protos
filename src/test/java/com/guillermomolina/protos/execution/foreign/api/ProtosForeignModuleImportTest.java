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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.guillermomolina.protos.runtime.ProtosActivation;
import com.guillermomolina.protos.runtime.ProtosActor;
import com.guillermomolina.protos.runtime.ProtosActorModuleState;
import com.guillermomolina.protos.runtime.ProtosEnvironmentValue;
import com.guillermomolina.protos.runtime.modules.ProtosModuleKey;
import com.guillermomolina.protos.runtime.ProtosObjectValue;
import com.guillermomolina.protos.runtime.ProtosPrelude;
import com.guillermomolina.protos.runtime.ProtosStringValue;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** I082-C foreign import routing, canonical ModuleKey, and Actor-local facade lifecycle. */
class ProtosForeignModuleImportTest {
    private static final Path CORE = Path.of("protos", "lib", "core");
    private static final ProtosModuleKey FOO = ProtosForeignModuleKey.encode("test", "foo");

    @Test
    void registeredSchemeRoutesToOneProviderAndAliasesShareOneFacadeAndInitialization()
            throws Exception {
        TestModules test = new TestModules("test");
        TestModules other = new TestModules("other");
        try (Fixture fixture = new Fixture(new ProtosModuleRuntimeTest.MemoryResolver(), test, other)) {
            ProtosObjectValue first = fixture.importValue(fixture.root, "test:foo");
            ProtosObjectValue alias = fixture.importValue(fixture.root, "test:alias-of-foo");
            ProtosObjectValue again = fixture.importValue(fixture.root, "test:foo");

            assertSame(first, alias);
            assertSame(first, again);
            assertEquals(List.of("foo", "alias-of-foo", "foo"), test.exactTargets);
            assertEquals(1, test.acquisitions("foo"));
            assertEquals(0, other.factoryCalls.get());
            assertTrue(fixture.resolver.resolvedSpecifiers.isEmpty());

            ProtosActorModuleState.ModuleRecord record =
                    fixture.root.actorModuleState().lookup(FOO).orElseThrow();
            assertSame(first, record.instance());
            assertEquals(ProtosActorModuleState.InitializationState.READY, record.state());
            ProtosForeignModuleFacadeValue facade =
                    assertInstanceOf(ProtosForeignModuleFacadeValue.class, first);
            ProtosForeignModuleFacadeValue.Attachment attachment =
                    facade.attachmentForRuntime().orElseThrow();
            assertEquals(test.id, attachment.providerId());
            assertEquals("foo", attachment.canonicalTarget());
            assertEquals("target:foo", attachment.target());
            assertTrue(attachment.session().isOpenForRuntime());
        }
    }

    @Test
    void sourceImportsKeepResolverAuthorityAndUnknownSchemesNeverReachProviders()
            throws Exception {
        TestModules test = new TestModules("test");
        ProtosModuleRuntimeTest.MemoryResolver resolver =
                new ProtosModuleRuntimeTest.MemoryResolver()
                        .source("m", "value: 1")
                        .alias("python:thing", "m");
        try (Fixture fixture = new Fixture(resolver, test)) {
            ProtosObjectValue source = fixture.importValue(fixture.root, "m");
            assertTrue(source.hasLocalSlot("value"));
            assertSame(source, fixture.importValue(fixture.root, "python:thing"));

            assertEquals(
                    ProtosExecutionOutcome.State.FAILED,
                    fixture.importOutcome(fixture.root, "nope:foo").state());
            assertEquals(List.of("m", "python:thing", "nope:foo"), resolver.resolvedSpecifiers);
            assertEquals(1, resolver.loads("m"));
            assertEquals(0, test.factoryCalls.get());
            assertTrue(test.exactTargets.isEmpty());
            assertEquals(0, fixture.context.foreignProviderSessionCountForTesting());
        }
    }

    @Test
    void routesAreValidatedUniqueAndCannotCaptureSourceSchemes() {
        TestModules test = new TestModules("test");
        IllegalArgumentException duplicate =
                assertThrows(
                        IllegalArgumentException.class,
                        () ->
                                ProtosForeignProviderRegistry.of(
                                        List.of(
                                                test.descriptor("one", "test"),
                                                test.descriptor("two", "test"))));
        assertEquals("duplicate foreign import scheme: test", duplicate.getMessage());
        for (String scheme : List.of("std", "self", "dep", "tool-shared", "", "Test", "./x", "1x")) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new ProtosForeignImportRoute(scheme, test));
        }
        assertEquals(0, test.factoryCalls.get());
    }

    @Test
    void foreignKeysAreStructuredAndCannotCollideWithSourceKeys() throws Exception {
        assertNotEquals(ProtosModuleKey.module("std:foo"), ProtosForeignModuleKey.encode("std", "foo"));
        ProtosModuleKey tricky = ProtosForeignModuleKey.encode("test", "a:b/../c");
        assertEquals(
                new ProtosForeignModuleKey.Address("test", "a:b/../c"),
                ProtosForeignModuleKey.decode(tricky));
        assertNotEquals(ProtosForeignModuleKey.encode("test", "a:b"), ProtosForeignModuleKey.encode("test:a", "b"));

        TestModules test = new TestModules("test");
        ProtosModuleRuntimeTest.MemoryResolver resolver =
                new ProtosModuleRuntimeTest.MemoryResolver()
                        .source(FOO.canonicalId(), "value: 1")
                        .alias("forged", FOO.canonicalId());
        try (Fixture fixture = new Fixture(resolver, test)) {
            // D242: matching canonical text does not merge distinct MODULE/SOURCE/FOREIGN kinds.
            // The host's explicit SOURCE authority must stay independent of provider routing.
            ProtosObjectValue sourceResult = fixture.importValue(fixture.root, "forged");
            assertTrue(sourceResult.hasLocalSlot("value"));
            ProtosModuleKey sourceKey = ProtosModuleKey.source(FOO.canonicalId());
            assertSame(sourceResult, fixture.root.actorModuleState().lookup(sourceKey).orElseThrow().instance());
            assertTrue(fixture.root.actorModuleState().lookup(FOO).isEmpty());
            assertEquals(1, resolver.loads(FOO.canonicalId()));
            assertEquals(0, test.factoryCalls.get());

            ProtosObjectValue foreignResult = fixture.importValue(fixture.root, "test:foo");
            assertInstanceOf(ProtosForeignModuleFacadeValue.class, foreignResult);
            assertNotSame(sourceResult, foreignResult);
            assertSame(foreignResult, fixture.root.actorModuleState().lookup(FOO).orElseThrow().instance());
            assertSame(sourceResult, fixture.root.actorModuleState().lookup(sourceKey).orElseThrow().instance());
            assertEquals(1, resolver.loads(FOO.canonicalId()));
            assertEquals(1, test.factoryCalls.get());
        }
    }

    @Test
    void actorsGetDistinctFacadesOverTheirOwnSessionsAndOneActorReusesItsSession()
            throws Exception {
        TestModules test = new TestModules("test");
        try (Fixture fixture = new Fixture(new ProtosModuleRuntimeTest.MemoryResolver(), test)) {
            ProtosActivation other = fixture.newActorActivation();
            ProtosObjectValue rootFoo = fixture.importValue(fixture.root, "test:foo");
            ProtosObjectValue rootBar = fixture.importValue(fixture.root, "test:bar");
            ProtosObjectValue otherFoo = fixture.importValue(other, "test:alias-of-foo");

            assertNotSame(rootFoo, otherFoo);
            assertSame(otherFoo, other.actorModuleState().lookup(FOO).orElseThrow().instance());
            // The provider session never substitutes for the Actor cache: each Actor acquires.
            assertEquals(2, test.acquisitions("foo"));
            assertSame(test.sessionsSeen.get(0), test.sessionsSeen.get(1));
            assertNotSame(test.sessionsSeen.get(0), test.sessionsSeen.get(2));
            assertSame(
                    attachment(rootFoo).session(), attachment(rootBar).session());
            assertNotSame(attachment(rootFoo).session(), attachment(otherFoo).session());
            assertEquals(1, test.factoryCalls.get());
            assertEquals(1, fixture.context.foreignProviderCompartmentCountForTesting());
            assertEquals(2, fixture.context.foreignProviderSessionCountForTesting());
            // No foreign Context exists: the only Context is the Protos Process Context.
            assertEquals(1, fixture.host.activeProcessContextCountForTesting());
        }
    }

    @Test
    void cacheBeforeInitializationServesForeignCyclesWithTheSamePartialFacade()
            throws Exception {
        TestModules test = new TestModules("test");
        try (Fixture fixture = new Fixture(new ProtosModuleRuntimeTest.MemoryResolver(), test)) {
            ProtosModuleKey a = ProtosForeignModuleKey.encode("test", "a");
            List<Object> partialASeenByB = new ArrayList<>();
            test.hooks.put(
                    "a",
                    (session, target) -> {
                        ProtosActorModuleState.ModuleRecord record =
                                fixture.root.actorModuleState().lookup(a).orElseThrow();
                        assertEquals(
                                ProtosActorModuleState.InitializationState.INITIALIZING,
                                record.state());
                        assertTrue(
                                assertInstanceOf(
                                                ProtosForeignModuleFacadeValue.class,
                                                record.instance())
                                        .attachmentForRuntime()
                                        .isEmpty());
                        fixture.reentrantImport("test:b");
                        return "target:a";
                    });
            test.hooks.put(
                    "b",
                    (session, target) -> {
                        partialASeenByB.add(fixture.reentrantImport("test:a"));
                        return "target:b";
                    });

            ProtosObjectValue moduleA = fixture.importValue(fixture.root, "test:a");

            assertEquals(List.of(moduleA), partialASeenByB);
            assertEquals(1, test.acquisitions("a"));
            assertEquals(1, test.acquisitions("b"));
            assertEquals(
                    ProtosActorModuleState.InitializationState.READY,
                    fixture.root.actorModuleState().lookup(a).orElseThrow().state());
            assertEquals("target:a", attachment(moduleA).target());
        }
    }

    @Test
    void failedInitializationEvictsExactRecordAndRetryCreatesFreshFacade() throws Exception {
        TestModules test = new TestModules("test");
        try (Fixture fixture = new Fixture(new ProtosModuleRuntimeTest.MemoryResolver(), test)) {
            ProtosModuleKey flaky = ProtosForeignModuleKey.encode("test", "flaky");
            List<Object> escaped = new ArrayList<>();
            test.hooks.put(
                    "flaky",
                    (session, target) -> {
                        if (escaped.isEmpty()) {
                            escaped.add(fixture.reentrantImport("test:flaky"));
                            throw new IllegalStateException("injected acquisition failure");
                        }
                        return "target:flaky";
                    });

            assertEquals(
                    ProtosExecutionOutcome.State.FAILED,
                    fixture.importOutcome(fixture.root, "test:flaky").state());
            assertTrue(fixture.root.actorModuleState().lookup(flaky).isEmpty());

            ProtosObjectValue retried = fixture.importValue(fixture.root, "test:flaky");
            ProtosForeignModuleFacadeValue failed =
                    assertInstanceOf(ProtosForeignModuleFacadeValue.class, escaped.get(0));
            assertNotSame(failed, retried);
            assertTrue(failed.attachmentForRuntime().isEmpty());
            assertTrue(failed.localSlotsSnapshot().isEmpty());
            assertEquals("target:flaky", attachment(retried).target());
            assertEquals(2, test.acquisitions("flaky"));
            assertSame(retried, fixture.root.actorModuleState().lookup(flaky).orElseThrow().instance());
        }
    }

    @Test
    void facadeAddsNoGuestMembersAndCanonicalizationFailureIsAnOrdinaryImportFailure()
            throws Exception {
        TestModules test = new TestModules("test");
        try (Fixture fixture = new Fixture(new ProtosModuleRuntimeTest.MemoryResolver(), test)) {
            ProtosObjectValue facade = fixture.importValue(fixture.root, "test:foo");
            assertTrue(facade.localSlotsSnapshot().isEmpty());
            assertSame(ProtosObjectValue.rootObject(), facade.parent().orElseThrow());

            assertEquals(
                    ProtosExecutionOutcome.State.FAILED,
                    fixture.importOutcome(fixture.root, "test:missing").state());
            assertEquals(0, test.acquisitions("missing"));
        }
    }

    @Test
    void emptyRegistryRoutesNothingAndInitializesNoForeignMachinery() throws Exception {
        ProtosModuleRuntimeTest.MemoryResolver resolver = new ProtosModuleRuntimeTest.MemoryResolver();
        ProtosPrelude prelude = new ProtosCoreBootstrap().bootstrap(CORE, resolver);
        ProtosStandaloneProcessBootstrap.Result bootstrap = bootstrap(prelude);
        try (ProtosPolyglotRuntimeHost host = ProtosPolyglotRuntimeHost.open()) {
            ProtosPolyglotProcessContext context = host(host, bootstrap);
            try {
                assertSame(ProtosForeignProviderRegistry.EMPTY, context.foreignProviderRegistryForRuntime());
                ProtosExecutionOutcome outcome =
                        context.executeModuleSource(
                                ProtosModuleSource.fromCharacters(
                                        ProtosModuleKey.source("entry"), "import(\"test:foo\")"),
                                bootstrap.activation());
                assertEquals(ProtosExecutionOutcome.State.FAILED, outcome.state());
                assertEquals(List.of("test:foo"), resolver.resolvedSpecifiers);
                assertEquals(0, context.foreignProviderCompartmentCountForTesting());
                assertEquals(0, context.foreignProviderSessionCountForTesting());
            } finally {
                bootstrap.process().requestTerminationForRuntime();
            }
        }
    }

    @Test
    void foreignImportSliceEnablesNoHostAuthorityOrGuestProjection() throws Exception {
        for (String file :
                List.of(
                        "ProtosForeignModuleImports.java",
                        "ProtosForeignModuleFacadeValue.java",
                        "ProtosForeignModuleKey.java",
                        "ProtosForeignImportRoute.java",
                        "ProtosForeignModuleProvider.java")) {
            String source =
                    Files.readString(
                            Path.of("src/main/java/com/guillermomolina/protos/execution/foreign/api", file));
            for (String forbidden :
                    List.of(
                            "HostAccess",
                            "PolyglotAccess",
                            "IOAccess",
                            "allowNativeAccess",
                            "allowCreateProcess",
                            "InteropLibrary",
                            "ServiceLoader",
                            "createLocalSlot")) {
                assertFalse(source.contains(forbidden), file + " must not use " + forbidden);
            }
        }
    }

    private static ProtosForeignModuleFacadeValue.Attachment attachment(Object facade) {
        return assertInstanceOf(ProtosForeignModuleFacadeValue.class, facade)
                .attachmentForRuntime()
                .orElseThrow();
    }

    /** Hosted Process with a provider registry and production Bytecode {@code import()}. */
    private static final class Fixture implements AutoCloseable {
        final ProtosModuleRuntimeTest.MemoryResolver resolver;
        final ProtosPrelude prelude;
        final ProtosStandaloneProcessBootstrap.Result bootstrap;
        final ProtosPolyglotRuntimeHost host;
        final ProtosPolyglotProcessContext context;
        final ProtosActivation root;
        private ProtosActivation current;

        Fixture(ProtosModuleRuntimeTest.MemoryResolver resolver, TestModules... providers)
                throws Exception {
            this.resolver = resolver;
            this.prelude = new ProtosCoreBootstrap().bootstrap(CORE, resolver);
            this.bootstrap = bootstrap(prelude);
            List<ProtosForeignProviderDescriptor> descriptors = new ArrayList<>();
            for (TestModules provider : providers) {
                descriptors.add(provider.descriptor(provider.scheme, provider.scheme));
            }
            this.host =
                    ProtosPolyglotRuntimeHost.openWithForeignProvidersForTesting(
                            ProtosForeignProviderRegistry.of(descriptors));
            this.context = host(host, bootstrap);
            this.root = bootstrap.activation();
        }

        ProtosActivation newActorActivation() {
            ProtosActor actor =
                    bootstrap.process()
                            .createHostedActorForRuntime(
                                    new ProtosObjectValue(ProtosObjectValue.rootObject()));
            return prelude.newModuleActivation(
                    actor.moduleState(),
                    ProtosModuleKey.source("entry"),
                    prelude.newExecutionContext(),
                    actor.executionDomain());
        }

        /** Runs {@code import(specifier)} as a fresh root task of {@code actor}'s Actor. */
        ProtosExecutionOutcome importOutcome(ProtosActivation actor, String specifier) {
            // An activation belongs to exactly one task: each entry gets a fresh activation over
            // the same Actor module cache and execution domain.
            ProtosActivation activation =
                    prelude.newModuleActivation(
                            actor.actorModuleState(),
                            ProtosModuleKey.source("entry"),
                            prelude.newExecutionContext(),
                            actor.executionDomain());
            current = activation;
            return context.executeModuleSource(
                    ProtosModuleSource.fromCharacters(
                            ProtosModuleKey.source("entry"), "import(\"" + specifier + "\")"),
                    activation);
        }

        ProtosObjectValue importValue(ProtosActivation activation, String specifier) {
            ProtosExecutionOutcome outcome = importOutcome(activation, specifier);
            assertEquals(ProtosExecutionOutcome.State.COMPLETED, outcome.state());
            return assertInstanceOf(ProtosObjectValue.class, outcome.value());
        }

        /**
         * Provider-initiated reentrant import in the Actor whose import is in progress. This is a
         * host-owned internal load through the shared canonical resolution and lifecycle of the
         * Prelude's module runtime, not the public guest import policy.
         */
        Object reentrantImport(String specifier) {
            ProtosModuleRuntime runtime = ProtosStandardImportProtocol.runtimeForPrelude(prelude);
            return runtime.loadCanonicalModule(
                    runtime.resolveModuleKey(new ProtosStringValue(specifier), current), current);
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

    /** Inert provider double: no guest language, Context, or host authority. */
    private static final class TestModules
            implements ProtosForeignModuleProvider, ProtosForeignProviderFactory {
        final ProtosForeignProviderId id;
        final String scheme;
        final AtomicInteger factoryCalls = new AtomicInteger();
        final List<String> exactTargets = new CopyOnWriteArrayList<>();
        final List<ProtosForeignProviderSession> sessionsSeen = new CopyOnWriteArrayList<>();
        final Map<String, Integer> acquisitions = new ConcurrentHashMap<>();
        final Map<String, AcquireHook> hooks = new ConcurrentHashMap<>();

        TestModules(String scheme) {
            this.id = new ProtosForeignProviderId("provider-" + scheme);
            this.scheme = scheme;
        }

        ProtosForeignProviderDescriptor descriptor(String idSuffix, String routeScheme) {
            return new ProtosForeignProviderDescriptor(
                    new ProtosForeignProviderId("provider-" + idSuffix),
                    ProtosForeignProviderExecutionProfile.RESTRICTED_IN_PROCESS,
                    ProtosForeignInertRestriction.zeroAuthority(),
                    this,
                    Optional.of(new ProtosForeignImportRoute(routeScheme, this)),
                    ProtosForeignValueAdapter.opaque("unknown"));
        }

        int acquisitions(String target) {
            return acquisitions.getOrDefault(target, 0);
        }

        @Override
        public String canonicalTarget(String exactTarget) throws Exception {
            exactTargets.add(exactTarget);
            if (exactTarget.startsWith("missing")) {
                throw new java.io.IOException("no such test target");
            }
            return exactTarget.equals("alias-of-foo") ? "foo" : exactTarget;
        }

        @Override
        public Object acquireTarget(ProtosForeignProviderSession session, String canonicalTarget)
                throws Exception {
            acquisitions.merge(canonicalTarget, 1, Integer::sum);
            sessionsSeen.add(session);
            AcquireHook hook = hooks.get(canonicalTarget);
            return hook == null ? "target:" + canonicalTarget : hook.acquire(session, canonicalTarget);
        }

        @Override
        public ProtosForeignProviderCompartment openCompartment() {
            factoryCalls.incrementAndGet();
            return new ProtosForeignProviderCompartment() {
                @Override
                public ProtosForeignProviderId providerId() {
                    return id;
                }

                @Override
                public ProtosForeignProviderSession openSession() {
                    // A fresh object per session: a non-capturing lambda would be one shared instance.
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

    private static ProtosPolyglotProcessContext host(
            ProtosPolyglotRuntimeHost host, ProtosStandaloneProcessBootstrap.Result bootstrap) {
        return host.hostProcess(
                bootstrap.process(),
                InputStream.nullInputStream(),
                OutputStream.nullOutputStream(),
                OutputStream.nullOutputStream());
    }

    private static ProtosStandaloneProcessBootstrap.Result bootstrap(ProtosPrelude prelude) {
        return ProtosStandaloneProcessBootstrap.create(
                prelude, List.of(), exactEnvironmentDomain(), List.of(),
                null, null, null, null, null, null, null);
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
}
