/*
 * THE LICENSED WORK IS PROVIDED UNDER THE TERMS OF THE ADAPTIVE PUBLIC LICENSE
 * ("LICENSE") AS FIRST COMPLETED BY: Guillermo Adrián Molina. See LICENSE.TXT.
 */
package com.guillermomolina.protos.execution;

import static org.junit.jupiter.api.Assertions.*;

import com.guillermomolina.protos.runtime.ProtosActivation;
import com.guillermomolina.protos.runtime.ProtosActor;
import com.guillermomolina.protos.runtime.ProtosComplexNumericSupport;
import com.guillermomolina.protos.runtime.ProtosIntegerValue;
import com.guillermomolina.protos.runtime.ProtosNumericValueSupport;
import com.guillermomolina.protos.runtime.ProtosModuleKey;
import com.guillermomolina.protos.runtime.ProtosObjectValue;
import com.guillermomolina.protos.runtime.ProtosPrelude;
import com.guillermomolina.protos.runtime.ProtosSignalException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

final class ProtosActorBootstrapTest {
    private static final Path CORE = Path.of("protos", "lib", "core");

    @Test
    void canonicalBootstrapLoadsActorLocalModuleAndInstallsExactBehavior() throws Exception {
        CanonicalResolver resolver =
                new CanonicalResolver()
                        .module(
                                "app/bootstrap",
                                "bootstrap: (value) => {\n"
                                        + "    { observed: value }\n"
                                        + "}");
        ProtosPrelude prelude = new ProtosCoreBootstrap().bootstrap(CORE, resolver);
        ProtosActor actor = new ProtosActor(actorRefPrototype());
        ProtosObjectValue transferred = new ProtosObjectValue(ProtosObjectValue.rootObject());
        ProtosModuleKey key = new ProtosModuleKey("app/bootstrap");

        boolean becameReady =
                new ProtosActorBootstrap(new ProtosModuleRuntime(resolver))
                        .initialize(actor, prelude, key, "bootstrap", List.of(transferred));

        assertTrue(becameReady);
        assertEquals(ProtosActor.LifecycleState.READY, actor.lifecycleState());
        ProtosObjectValue behavior = actor.currentBehavior().orElseThrow();
        assertSame(transferred, behavior.readLocalSlot("observed").orElseThrow());
        assertEquals(0, resolver.resolveCalls.get(), "canonical bootstrap must not re-resolve spelling");
        assertEquals(1, resolver.loads("app/bootstrap"));
        assertEquals(
                com.guillermomolina.protos.runtime.ProtosActorModuleState.InitializationState.READY,
                actor.moduleState().lookup(key).orElseThrow().state());
    }

    @Test
    void executionDomainCarriesCurrentActorReferenceWithoutGlobalState() throws Exception {
        CanonicalResolver resolver = new CanonicalResolver();
        ProtosPrelude prelude = new ProtosCoreBootstrap().bootstrap(CORE, resolver);
        ProtosActor actor = new ProtosActor(actorRefPrototype());
        ProtosActivation activation =
                prelude.newModuleActivation(
                        actor.moduleState(),
                        null,
                        prelude.newExecutionContext(),
                        actor.executionDomain());

        assertSame(actor.reference(), actor.executionDomain().currentActorReference().orElseThrow());
        assertSame(actor.reference(), activation.executionDomain().currentActorReference().orElseThrow());

        actor.beginTermination();
        actor.markTerminated();
        assertSame(actor.reference(), activation.executionDomain().currentActorReference().orElseThrow());
    }

    @Test
    void sameCanonicalBootstrapModuleIsInstantiatedIndependentlyPerActor() throws Exception {
        CanonicalResolver resolver =
                new CanonicalResolver()
                        .module("app/bootstrap", "bootstrap: () => { { state: {} } }");
        ProtosPrelude prelude = new ProtosCoreBootstrap().bootstrap(CORE, resolver);
        ProtosModuleKey key = new ProtosModuleKey("app/bootstrap");
        ProtosActor first = new ProtosActor(actorRefPrototype());
        ProtosActor second = new ProtosActor(actorRefPrototype());
        ProtosActorBootstrap bootstrap =
                new ProtosActorBootstrap(new ProtosModuleRuntime(resolver));

        assertTrue(bootstrap.initialize(first, prelude, key, "bootstrap", List.of()));
        assertTrue(bootstrap.initialize(second, prelude, key, "bootstrap", List.of()));

        assertNotSame(
                first.moduleState().lookup(key).orElseThrow().instance(),
                second.moduleState().lookup(key).orElseThrow().instance());
        assertNotSame(first.currentBehavior().orElseThrow(), second.currentBehavior().orElseThrow());
        assertEquals(2, resolver.loads("app/bootstrap"));
    }

    @Test
    void inheritedBindingCannotSatisfyBootstrapLocalBindingRequirement() throws Exception {
        CanonicalResolver resolver =
                new CanonicalResolver().module("app/bootstrap", "other: () => { {} }");
        ProtosPrelude prelude = new ProtosCoreBootstrap().bootstrap(CORE, resolver);
        ProtosActor actor = new ProtosActor(actorRefPrototype());

        assertThrows(
                ProtosSignalException.class,
                () ->
                        new ProtosActorBootstrap(new ProtosModuleRuntime(resolver))
                                .initialize(
                                        actor,
                                        prelude,
                                        new ProtosModuleKey("app/bootstrap"),
                                        "call",
                                        List.of()));
        assertEquals(ProtosActor.LifecycleState.TERMINATED, actor.lifecycleState());
        assertTrue(actor.currentBehavior().isEmpty());
    }

    @Test
    void nonInvokableBindingAndNonObjectBehaviorAreInitializationFailures() throws Exception {
        assertBootstrapFailure("bootstrap: 7");
        assertBootstrapFailure("bootstrap: () => { 7 }");
    }

    @Test
    void numericRichValuesCannotBecomeActorBehaviorButOrdinaryDelegatesCan()
            throws Exception {
        // Source-produced Fraction is a sealed ProtosObjectValue, but
        // semantically it is a Number, not an Actor behavior object.
        assertBootstrapFailure("bootstrap: () => { 1 / 2 }");

        CanonicalResolver resolver =
                new CanonicalResolver().module(
                        "app/bootstrap",
                        "bootstrap: (candidate) => { candidate }");

        ProtosPrelude prelude =
                new ProtosCoreBootstrap().bootstrap(CORE, resolver);

        Object complex = ProtosComplexNumericSupport.normalize(
                new ProtosIntegerValue(2L),
                new ProtosIntegerValue(3L),
                prelude);

        assertTrue(ProtosComplexNumericSupport.isComplex(complex));

        ProtosActorBootstrap bootstrap =
                new ProtosActorBootstrap(new ProtosModuleRuntime(resolver));
        ProtosModuleKey key = new ProtosModuleKey("app/bootstrap");

        ProtosActor numericActor = new ProtosActor(actorRefPrototype());

        assertThrows(
                ProtosSignalException.class,
                () -> bootstrap.initialize(
                        numericActor, prelude, key, "bootstrap",
                        List.of(complex)));

        assertEquals(
                ProtosActor.LifecycleState.TERMINATED,
                numericActor.lifecycleState());
        assertTrue(numericActor.currentBehavior().isEmpty());

        // Parentability is independent of numeric family membership.
        // This is a genuine ordinary object, even though its parent is
        // a genuine Complex value.
        ProtosObjectValue ordinary = new ProtosObjectValue(complex);
        assertFalse(ProtosNumericValueSupport.isCurrentNumber(ordinary));

        ProtosActor ordinaryActor = new ProtosActor(actorRefPrototype());

        assertTrue(bootstrap.initialize(
                ordinaryActor, prelude, key, "bootstrap",
                List.of(ordinary)));

        assertEquals(
                ProtosActor.LifecycleState.READY,
                ordinaryActor.lifecycleState());
        assertSame(
                ordinary,
                ordinaryActor.currentBehavior().orElseThrow());
    }

    @Test
    void sourceFailureTerminatesSameExistingIncarnationWithoutRetargetingReference() throws Exception {
        CanonicalResolver resolver =
                new CanonicalResolver().module("app/bootstrap", "broken: (");
        ProtosPrelude prelude = new ProtosCoreBootstrap().bootstrap(CORE, resolver);
        ProtosActor actor = new ProtosActor(actorRefPrototype());
        Object reference = actor.reference();

        assertThrows(
                ProtosSignalException.class,
                () ->
                        new ProtosActorBootstrap(new ProtosModuleRuntime(resolver))
                                .initialize(
                                        actor,
                                        prelude,
                                        new ProtosModuleKey("app/bootstrap"),
                                        "bootstrap",
                                        List.of()));

        assertSame(reference, actor.reference());
        assertEquals(ProtosActor.LifecycleState.TERMINATED, actor.lifecycleState());
        assertTrue(actor.currentBehavior().isEmpty());
    }

    @Test
    void behaviorReferenceCannotBeReplacedAfterReady() throws Exception {
        CanonicalResolver resolver =
                new CanonicalResolver().module("app/bootstrap", "bootstrap: () => { {} }");
        ProtosPrelude prelude = new ProtosCoreBootstrap().bootstrap(CORE, resolver);
        ProtosActor actor = new ProtosActor(actorRefPrototype());
        ProtosActorBootstrap bootstrap =
                new ProtosActorBootstrap(new ProtosModuleRuntime(resolver));
        ProtosModuleKey key = new ProtosModuleKey("app/bootstrap");

        assertTrue(bootstrap.initialize(actor, prelude, key, "bootstrap", List.of()));
        ProtosObjectValue behavior = actor.currentBehavior().orElseThrow();
        assertFalse(actor.completeInitialization(new ProtosObjectValue(ProtosObjectValue.rootObject())));
        assertSame(behavior, actor.currentBehavior().orElseThrow());
    }

    private static void assertBootstrapFailure(String source) throws Exception {
        CanonicalResolver resolver = new CanonicalResolver().module("app/bootstrap", source);
        ProtosPrelude prelude = new ProtosCoreBootstrap().bootstrap(CORE, resolver);
        ProtosActor actor = new ProtosActor(actorRefPrototype());

        assertThrows(
                ProtosSignalException.class,
                () ->
                        new ProtosActorBootstrap(new ProtosModuleRuntime(resolver))
                                .initialize(
                                        actor,
                                        prelude,
                                        new ProtosModuleKey("app/bootstrap"),
                                        "bootstrap",
                                        List.of()));
        assertEquals(ProtosActor.LifecycleState.TERMINATED, actor.lifecycleState());
        assertTrue(actor.currentBehavior().isEmpty());
    }

    private static ProtosObjectValue actorRefPrototype() {
        return new ProtosObjectValue(ProtosObjectValue.rootObject()).freeze();
    }

    private static final class CanonicalResolver implements ProtosModuleResolver {
        private final Map<String, String> sources = new HashMap<>();
        private final Map<String, Integer> loads = new HashMap<>();
        private final AtomicInteger resolveCalls = new AtomicInteger();

        CanonicalResolver module(String key, String source) {
            sources.put(key, source);
            return this;
        }

        int loads(String key) {
            return loads.getOrDefault(key, 0);
        }

        @Override
        public ProtosModuleKey resolve(
                String exactSpecifier, Optional<ProtosModuleKey> importingModule) throws Exception {
            resolveCalls.incrementAndGet();
            throw new AssertionError("I011-3 bootstrap must consume an already-canonical ModuleKey");
        }

        @Override
        public ProtosModuleSource loadSource(ProtosModuleKey key) throws Exception {
            loads.merge(key.canonicalId(), 1, Integer::sum);
            String source = sources.get(key.canonicalId());
            if (source == null) {
                throw new java.io.IOException("module not found: " + key.canonicalId());
            }
            return ProtosModuleSource.fromCharacters(key, source);
        }
    }
}
