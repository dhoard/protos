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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.guillermomolina.protos.runtime.ProtosBooleanValue;
import com.guillermomolina.protos.runtime.ProtosClosureValue;
import com.guillermomolina.protos.runtime.ProtosObjectValue;
import com.guillermomolina.protos.runtime.ProtosPrelude;
import com.guillermomolina.protos.runtime.ProtosSignalException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;

class ProtosCoreBootstrapTest {
    @Test
    void bootstrapsContextPrototypeFromDistributableCoreSource()
            throws IOException {
        ProtosPrelude prelude =
                new ProtosCoreBootstrap()
                        .bootstrap(Path.of("protos", "lib", "core"));

        ProtosObjectValue contextPrototype = prelude.contextPrototype();
        ProtosObjectValue bindings = prelude.bindings();
        ProtosObjectValue first = prelude.newExecutionContext();
        ProtosObjectValue second = prelude.newExecutionContext();

        assertSame(
                ProtosObjectValue.rootObject(),
                contextPrototype.parent().orElseThrow());
        assertSame(contextPrototype, bindings.parent().orElseThrow());
        assertSame(
                ProtosObjectValue.rootObject(),
                bindings.readLocalSlot("Object").orElseThrow());
        assertSame(
                contextPrototype,
                bindings.readLocalSlot("Context").orElseThrow());
        ProtosObjectValue errorPrototype = prelude.errorPrototype();
        assertSame(
                errorPrototype,
                bindings.readLocalSlot("Error").orElseThrow());
        assertSame(
                ProtosObjectValue.rootObject(),
                errorPrototype.parent().orElseThrow());
        ProtosClosureValue fail =
                assertInstanceOf(
                        ProtosClosureValue.class,
                        bindings.readLocalSlot("fail").orElseThrow());
        assertNotNull(fail.definition());
        assertTrue(fail.nativeBody().isEmpty());
        assertTrue(fail.isFrozen());
        ProtosObjectValue invalidReturnPrototype =
                prelude.invalidReturnPrototype();
        assertSame(
                invalidReturnPrototype,
                bindings.readLocalSlot("InvalidReturn").orElseThrow());
        assertSame(
                errorPrototype,
                invalidReturnPrototype.parent().orElseThrow());
        assertNotSame(
                prelude.newInvalidReturn(),
                prelude.newInvalidReturn());
        assertSame(
                invalidReturnPrototype,
                prelude.newInvalidReturn().parent().orElseThrow());

        ProtosObjectValue networkPrototype =
                assertInstanceOf(
                        ProtosObjectValue.class,
                        bindings.readLocalSlot("Network").orElseThrow());
        assertSame(ProtosObjectValue.rootObject(), networkPrototype.parent().orElseThrow());
        assertTrue(networkPrototype.isFrozen());

        ProtosObjectValue futurePrototype = prelude.futurePrototype();
        assertSame(futurePrototype, bindings.readLocalSlot("Future").orElseThrow());
        assertSame(ProtosObjectValue.rootObject(), futurePrototype.parent().orElseThrow());

        ProtosObjectValue arrayPrototype = prelude.arrayPrototype();
        assertSame(
                arrayPrototype,
                bindings.readLocalSlot("Array").orElseThrow());
        assertSame(
                ProtosObjectValue.rootObject(),
                arrayPrototype.parent().orElseThrow());
        assertSame(
                arrayPrototype,
                prelude.newArray(java.util.List.of()).parent().orElseThrow());
        assertSame(
                ProtosObjectValue.MutationState.FROZEN,
                prelude.newFrozenArray(java.util.List.of()).mutationState());
        assertSame(
                ProtosObjectValue.MutationState.FROZEN,
                bindings.mutationState());
        assertFalse(bindings.hasLocalSlot("Bytes"));
        // D172: the canonical IP families are runtime-retained, not public Prelude bindings.
        assertFalse(bindings.hasLocalSlot("IpAddress"));
        assertFalse(bindings.hasLocalSlot("IpEndpoint"));
        assertTrue(prelude.ipAddressPrototypeForRuntime().isFrozen());
        assertTrue(prelude.ipEndpointPrototypeForRuntime().isFrozen());
        assertFalse(bindings.hasLocalSlot("_coreRootObject"));
        assertEquals(
                java.util.Set.of(
                        "Object",
                        "Context",
                        "Number",
                        "Integer",
                        "SmallInteger",
                        "BigInteger",
                        "Fraction",
                        "Float",
                        "Error",
                        "fail",
                        "InvalidReturn",
                        "SlotNotFound",
                        "InvalidSuper",
                        "Cancelled",
                        "FutureResolutionCycle",
                        "RequestOutcomeUncertain",
                        "NonTransferableValue",
                        "NonParallelValue",
                        "ForeignError",
                        "InvalidPredicateResult",
                        "InvalidComparatorResult",
                        "InvalidComparatorOrder",
                        "IOError",
                        "InvalidIOArgument",
                        "IOLifecycleError",
                        "IOCapacityExhausted",
                        "EncodingError",
                        "LineTooLong",
                        "Any",
                        "Capture",
                        "Array",
                        "String",
                        "Encoding",
                        "Map",
                        "IdentityMap",
                        "Path",
                        "Network",
                        "Future",
                        "Actor",
                        "Process",
                        "TextReader",
                        "TextWriter",
                        "import"),
                bindings.localSlotsSnapshot().keySet());
        // D167: the buffered byte factories are std:io members, not Prelude bindings.
        assertFalse(bindings.hasLocalSlot("BufferedReader"));
        assertFalse(bindings.hasLocalSlot("BufferedWriter"));
        String bootstrapSource =
                Files.readString(
                        Path.of(
                                "src",
                                "main",
                                "java",
                                "com",
                                "guillermomolina",
                                "protos",
                                "execution",
                                "ProtosCoreBootstrap.java"));
        assertFalse(bootstrapSource.contains("preludeBindings.createLocalSlot("));
        String preludeSource =
                Files.readString(
                        Path.of("protos", "lib", "core", "prelude.protos"));
        assertTrue(preludeSource.contains("_corePreludeBindings: Context {"));
        assertTrue(preludeSource.contains("Object: _coreRootObject"));
        assertTrue(bootstrapSource.contains("bootstrapContext.createLocalSlot("));
        assertTrue(bootstrapSource.contains("_coreRootObject"));
        assertNotSame(first, second);
        assertSame(contextPrototype, first.parent().orElseThrow());
        assertSame(contextPrototype, second.parent().orElseThrow());
    }

    @Test
    void sourceBackedCoreClosureSurvivesTemporaryBootstrapHostAndReprojectsToBytecode()
            throws Exception {
        ProtosPrelude prelude =
                new ProtosCoreBootstrap()
                        .bootstrap(Path.of("protos", "lib", "core"));

        ProtosObjectValue any =
                assertInstanceOf(
                        ProtosObjectValue.class,
                        prelude.bindings()
                                .readLocalSlot("Any")
                                .orElseThrow());
        ProtosClosureValue match =
                assertInstanceOf(
                        ProtosClosureValue.class,
                        any.readLocalSlot("match").orElseThrow());

        assertTrue(match.executionPlan().isPresent());
        assertTrue(match.requiresContextLocalExecutionProjectionForRuntime());

        Object result =
                ProtosTestExecutionSupport.evaluate(
                        "Any.match(123)",
                        prelude.newModuleActivation());

        assertSame(ProtosBooleanValue.TRUE, result);
    }

    @Test
    void guestCannotAssignFrozenPreludeBindingButCanShadowLocally() throws Exception {
        ProtosPrelude prelude =
                new ProtosCoreBootstrap()
                        .bootstrap(Path.of("protos", "lib", "core"));

        Object result =
                ProtosTestExecutionSupport.evaluate(
                        "original: Object\n"
                                + "blocked: Error.handle(() => { Object = 1 }, (caught) => true)\n"
                                + "Object: 7\n"
                                + "blocked.and() { (Object == 7).and() { original !== Object } }",
                        prelude.newModuleActivation());

        assertSame(ProtosBooleanValue.TRUE, result);
        assertSame(
                ProtosObjectValue.MutationState.FROZEN,
                prelude.bindings().mutationState());
    }

    @Test
    void installsSourceBackedObjectBehaviorWithoutInequalityProtocol()
            throws IOException {
        ProtosPrelude prelude =
                new ProtosCoreBootstrap()
                        .bootstrap(Path.of("protos", "lib", "core"));

        ProtosObjectValue object = ProtosObjectValue.rootObject();
        ProtosClosureValue init =
                assertSourceBackedObjectClosure(object, "init");
        ProtosClosureValue equals =
                assertSourceBackedObjectClosure(object, "==");
        ProtosClosureValue match =
                assertSourceBackedObjectClosure(object, "match");
        ProtosClosureValue ifNull =
                assertSourceBackedObjectClosure(object, "ifNull");
        ProtosClosureValue ifNotNull =
                assertSourceBackedObjectClosure(object, "ifNotNull");

        assertFalse(object.hasLocalSlot("!="));

        assertEquals(1, init.capturedLexicalContexts().size());
        assertEquals(1, equals.capturedLexicalContexts().size());
        assertEquals(1, match.capturedLexicalContexts().size());
        assertEquals(1, ifNull.capturedLexicalContexts().size());
        assertEquals(1, ifNotNull.capturedLexicalContexts().size());

        assertSame(
                init.capturedLexicalContexts().get(0),
                equals.capturedLexicalContexts().get(0));
        assertSame(
                init.capturedLexicalContexts().get(0),
                match.capturedLexicalContexts().get(0));

        ProtosObjectValue sourceContext =
                init.capturedLexicalContexts().get(0);

        assertTrue(sourceContext.isFrozen());
        assertFalse(sourceContext.hasLocalSlot("_coreObjectInit"));
        assertFalse(sourceContext.hasLocalSlot("_coreObjectEquals"));
        assertFalse(sourceContext.hasLocalSlot("_coreObjectMatch"));
        assertFalse(sourceContext.hasLocalSlot("_coreObjectNotEquals"));
        assertFalse(sourceContext.hasLocalSlot("ifNull"));
        assertFalse(sourceContext.hasLocalSlot("ifNotNull"));

        ProtosObjectValue receiver = new ProtosObjectValue(object);
        assertSame(
                receiver,
                ProtosInvocation.invokeMessage(
                        receiver,
                        "init",
                        java.util.List.of(),
                        prelude.newModuleActivation()));
    }


    @Test
    void objectMatchConformanceUsesDynamicEqualityExactlyOnceAndReturnsExactResult()
            throws Exception {
        ProtosPrelude prelude =
                new ProtosCoreBootstrap()
                        .bootstrap(Path.of("protos", "lib", "core"));

        Object result =
                ProtosTestExecutionSupport.evaluate(
                        "calls: { count: 0 }\n"
                                + "marker: {}\n"
                                + "source: {\n"
                                + "    equals: (other) => {\n"
                                + "        calls.count = calls.count + 1\n"
                                + "        marker\n"
                                + "    }\n"
                                + "}\n"
                                + "view: source.alias(\"equals\", \"==\")\n"
                                + "pattern: view {}\n"
                                + "result: pattern.match(pattern)\n"
                                + "(result === marker) && (calls.count == 1)",
                        prelude.newModuleActivation());

        assertSame(ProtosBooleanValue.TRUE, result);
    }


    @Test
    void publishesTheCompleteSharedStandardGraphFrozen() throws Exception {
        ProtosPrelude prelude =
                new ProtosCoreBootstrap().bootstrap(Path.of("protos", "lib", "core"));

        assertFrozenStandardGraph(prelude);
        assertSame(
                ProtosObjectValue.MutationState.FROZEN,
                ProtosObjectValue.rootObject().mutationState());
    }

    @Test
    void guestCannotCreateAStandardRootSlotAfterPublication() throws Exception {
        ProtosPrelude prelude =
                new ProtosCoreBootstrap().bootstrap(Path.of("protos", "lib", "core"));

        ProtosSignalException failure =
                org.junit.jupiter.api.Assertions.assertThrows(
                        ProtosSignalException.class,
                        () ->
                                ProtosTestExecutionSupport.evaluate(
                                        "Object._d049Probe: 1",
                                        prelude.newModuleActivation()));

        assertSame(prelude.errorPrototype(), failure.error().parent().orElseThrow());
        assertFalse(ProtosObjectValue.rootObject().hasLocalSlot("_d049Probe"));
    }

    @Test
    void concurrentCoreBootstrapsObserveOneCompleteFrozenRoot() throws Exception {
        int workers = 4;
        ExecutorService executor = Executors.newFixedThreadPool(workers);
        CountDownLatch ready = new CountDownLatch(workers);
        CountDownLatch start = new CountDownLatch(1);
        java.util.ArrayList<java.util.concurrent.Future<ProtosPrelude>> futures =
                new java.util.ArrayList<>();
        try {
            for (int index = 0; index < workers; index++) {
                futures.add(
                        executor.submit(
                                () -> {
                                    ready.countDown();
                                    start.await();
                                    return new ProtosCoreBootstrap()
                                            .bootstrap(Path.of("protos", "lib", "core"));
                                }));
            }
            assertTrue(ready.await(10, java.util.concurrent.TimeUnit.SECONDS));
            start.countDown();
            for (java.util.concurrent.Future<ProtosPrelude> future : futures) {
                ProtosPrelude prelude = future.get();
                assertSame(
                        ProtosObjectValue.rootObject(),
                        prelude.bindings().readLocalSlot("Object").orElseThrow());
                assertFrozenStandardGraph(prelude);
            }
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    private static void assertFrozenStandardGraph(ProtosPrelude prelude) {
        Set<ProtosObjectValue> visited =
                Collections.newSetFromMap(new IdentityHashMap<>());
        ArrayDeque<ProtosObjectValue> pending = new ArrayDeque<>();
        pending.add(ProtosObjectValue.rootObject());
        pending.add(prelude.bindings());
        pending.add(prelude.bytesPrototypeForRuntime());
        pending.add(prelude.actorRefPrototypeForRuntime());
        while (!pending.isEmpty()) {
            ProtosObjectValue object = pending.removeFirst();
            if (!visited.add(object)) {
                continue;
            }
            assertTrue(object.isFrozen(), () -> "shared standard object remained mutable: " + object);
            for (Object value : object.localSlotsSnapshot().values()) {
                if (value instanceof ProtosObjectValue child) {
                    pending.addLast(child);
                }
            }
            if (object instanceof ProtosClosureValue closure) {
                for (ProtosObjectValue context : closure.capturedLexicalContexts()) {
                    pending.addLast(context);
                }
                if (closure.capturedReceiver() instanceof ProtosObjectValue receiver) {
                    pending.addLast(receiver);
                }
                closure.methodHome().ifPresent(pending::addLast);
            }
        }
    }

    private static ProtosClosureValue assertSourceBackedObjectClosure(
            ProtosObjectValue object, String selector) {
        ProtosClosureValue closure =
                assertInstanceOf(
                        ProtosClosureValue.class,
                        object.readLocalSlot(selector).orElseThrow());
        assertNotNull(closure.definition());
        assertTrue(closure.executionPlan().isPresent());
        assertTrue(closure.nativeBody().isEmpty());
        assertTrue(closure.isFrozen());
        return closure;
    }

}
