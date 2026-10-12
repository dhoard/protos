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

import com.guillermomolina.protos.runtime.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * PLAT051-A2: two-stage semantic transfer across the hosted Actor and P boundaries with a family
 * whose callable surface is guest Protos code of its owning standard module.
 *
 * <p>The source value carries a source-only native callable. The destination value's callable is
 * a Closure created by running the owning module's guest code inside the destination domain, so a
 * handler or P computation observing the source callable, a copied Closure, or an unmaterialized
 * record would fail. The owning module is a test-only overlay; no distributable library changes.
 */
final class ProtosSemanticTransferMaterializationTest {
    private static final Path CORE = Path.of("protos", "lib", "core");
    private static final ProtosModuleKey GUEST_KEY = new ProtosModuleKey("std:plat051-fixture/guest");
    private static final ProtosModuleKey HOLDER_KEY = new ProtosModuleKey("plat051-test:holder");

    private static final String GUEST_SOURCE =
            "surface: (text) => {\n"
                    + "    () => { text }\n"
                    + "}\n";
    private static final String HOLDER_SOURCE =
            "holder: (initial) => {\n"
                    + "    {\n"
                    + "        initialDescribe: () => { initial.describe() }\n"
                    + "        describe: (value) => { value.describe() }\n"
                    + "        graph: (graph) => {\n"
                    + "            [graph.shared === graph.again, graph.shared !== graph.twin,"
                    + " graph.self === graph, graph.shared.describe(), graph.twin.describe()]\n"
                    + "        }\n"
                    + "        echo: (value) => { value }\n"
                    + "    }\n"
                    + "}\n";

    @Test
    void actorDestinationMaterializesWithItsOwnStandardModuleBeforeHandlersObserve(@TempDir Path dir)
            throws Exception {
        GuestFamily family = new GuestFamily();
        try (ProtosHostedExecutionTestFixture hosted = open(family, dir)) {
            ProtosActivation root = hosted.activation();
            ProtosSemanticTransferValue portable = family.mint("a+b");
            root.context().createLocalSlot("portable", portable);
            root.context().createLocalSlot("graph", graph(portable, family.mint("a+b")));

            assertEquals(
                    "a+b",
                    string(
                            await(
                                    hosted,
                                    "worker: Actor.spawn(\"plat051-holder\", \"holder\", portable)\n"
                                            + "worker.request(\"initialDescribe\")")));
            assertEquals("a+b", string(await(hosted, "worker.request(\"describe\", portable)")));
            ProtosArrayValue observed =
                    assertInstanceOf(
                            ProtosArrayValue.class, await(hosted, "worker.request(\"graph\", graph)"));
            List<Object> facts = observed.indexedSnapshot();
            assertSame(ProtosBooleanValue.TRUE, facts.get(0), "aliases stay aliases");
            assertSame(ProtosBooleanValue.TRUE, facts.get(1), "equal payloads stay distinct");
            assertSame(ProtosBooleanValue.TRUE, facts.get(2), "surrounding cycles are preserved");
            assertEquals("a+b", string(facts.get(3)));
            assertEquals("a+b", string(facts.get(4)));

            // spawn argument, one request argument, and the two distinct graph identities.
            assertEquals(4, family.materialized.get());
            assertEquals(1, family.domains.stream().distinct().count());
            assertNotSame(root.executionDomain(), family.domains.get(0));
            assertTrue(
                    root.actorModuleState().lookup(GUEST_KEY).isEmpty(),
                    "the source domain never loads the destination implementation");
            assertEquals(0, family.sourceDescribeCalls.get(), "the source callable never crossed");
        }
    }

    @Test
    void requestReplyMaterializesInTheRequesterDomain(@TempDir Path dir) throws Exception {
        GuestFamily family = new GuestFamily();
        try (ProtosHostedExecutionTestFixture hosted = open(family, dir)) {
            ProtosActivation root = hosted.activation();
            ProtosSemanticTransferValue portable = family.mint("a+b");
            root.context().createLocalSlot("portable", portable);
            hosted.evaluatePersistent(
                    "<plat051-a2>", "worker: Actor.spawn(\"plat051-holder\", \"holder\", 0)");

            ProtosSemanticTransferValue reply =
                    assertInstanceOf(
                            ProtosSemanticTransferValue.class,
                            await(hosted, "worker.request(\"echo\", portable)"));

            assertSame(family, reply.family());
            assertNotSame(portable, reply);
            assertTrue(reply.isFrozen());
            assertEquals(2, family.materialized.get());
            assertSame(root.executionDomain(), family.domains.get(1));
            assertTrue(root.actorModuleState().lookup(GUEST_KEY).isPresent());
            assertEquals(
                    "a+b",
                    string(await(hosted, "worker.request(\"echo\", portable).then((copy) => { copy.describe() })")));
            assertEquals(0, family.sourceDescribeCalls.get());
        }
    }

    @Test
    void pWorkerMaterializesBeforeComputationAndCallerMaterializesTheResult(@TempDir Path dir)
            throws Exception {
        GuestFamily family = new GuestFamily();
        try (ProtosHostedExecutionTestFixture hosted = open(family, dir)) {
            ProtosActivation root = hosted.activation();
            ProtosSemanticTransferValue portable = family.mint("a+b");
            root.context().createLocalSlot("portable", portable);

            assertEquals(
                    "a+b",
                    string(await(hosted, "((value) => { value.describe() }).parallel(portable)")));
            assertEquals(1, family.materialized.get());
            assertNotSame(root.executionDomain(), family.domains.get(0));
            assertTrue(root.actorModuleState().lookup(GUEST_KEY).isEmpty());

            ProtosSemanticTransferValue result =
                    assertInstanceOf(
                            ProtosSemanticTransferValue.class,
                            await(hosted, "((value) => { value }).parallel(portable)"));
            assertNotSame(portable, result);
            assertEquals(3, family.materialized.get());
            assertSame(root.executionDomain(), family.domains.get(2));
            assertEquals(0, family.sourceDescribeCalls.get());
        }
    }

    @Test
    void pathRequestReplyAndPResultRematerializeWithAliasesInTheCallerDomain(@TempDir Path dir)
            throws Exception {
        GuestFamily family = new GuestFamily();
        try (ProtosHostedExecutionTestFixture hosted = open(family, dir)) {
            ProtosActivation root = hosted.activation();
            ProtosPrelude prelude = root.prelude().orElseThrow();
            ProtosPathValue path = new ProtosPathValue(prelude.pathPrototype(), List.of("a", "b"));
            ProtosPathValue twin = new ProtosPathValue(prelude.pathPrototype(), List.of("a", "b"));
            root.context().createLocalSlot("path", path);
            root.context().createLocalSlot("twin", twin);
            root.context().createLocalSlot("paths", prelude.newArray(List.of(path, path, twin)));
            hosted.evaluatePersistent(
                    "<i094-a3>", "worker: Actor.spawn(\"plat051-holder\", \"holder\", 0)");

            ProtosPathValue reply =
                    assertInstanceOf(
                            ProtosPathValue.class, await(hosted, "worker.request(\"echo\", path)"));
            assertNotSame(path, reply);
            assertEquals(List.of("a", "b"), reply.components());
            assertSame(prelude.pathPrototype(), reply.representedDelegationParent(prelude));

            assertAliasedPaths(
                    path,
                    twin,
                    await(hosted, "worker.request(\"echo\", [path, path, twin])"));
            assertAliasedPaths(
                    path,
                    twin,
                    await(hosted, "((items) => { items }).parallel(paths)"));
            assertEquals(0, family.materialized.get(), "Paths need no semantic family");
        }
    }

    private static void assertAliasedPaths(ProtosPathValue path, ProtosPathValue twin, Object value) {
        List<Object> elements = assertInstanceOf(ProtosArrayValue.class, value).indexedSnapshot();
        ProtosPathValue first = assertInstanceOf(ProtosPathValue.class, elements.get(0));
        ProtosPathValue third = assertInstanceOf(ProtosPathValue.class, elements.get(2));
        assertNotSame(path, first);
        assertNotSame(twin, third);
        assertSame(first, elements.get(1), "one Path stays one Path across the round trip");
        assertNotSame(first, third, "equal but distinct Paths stay distinct");
        assertEquals(first.components(), third.components());
    }

    @Test
    void ordinaryHostedTransferPaysNoMaterializationOrStandardModuleLoad(@TempDir Path dir)
            throws Exception {
        GuestFamily family = new GuestFamily();
        try (ProtosHostedExecutionTestFixture hosted = open(family, dir)) {
            ProtosActivation root = hosted.activation();
            assertEquals(String.valueOf("42"), com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerDecimalText(await(
                                            hosted,
                                            "worker: Actor.spawn(\"plat051-holder\", \"holder\", 0)\n"
                                                    + "worker.request(\"echo\", 42)")));
            assertEquals(String.valueOf("7"), com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerDecimalText(await(hosted, "((value) => { value }).parallel(7)")));
            assertEquals(0, family.materialized.get());
            assertTrue(root.actorModuleState().lookup(GUEST_KEY).isEmpty());
        }
    }

    private static ProtosHostedExecutionTestFixture open(GuestFamily family, Path dir) throws Exception {
        Path guest = Files.writeString(dir.resolve("guest.protos"), GUEST_SOURCE);
        Path holder = Files.writeString(dir.resolve("holder.protos"), HOLDER_SOURCE);
        ProtosModuleResolver resolver =
                new ProtosExactModuleOverlayResolver(
                        Map.of(
                                GUEST_KEY.canonicalId(),
                                new ProtosExactModuleOverlayResolver.ExactModule(GUEST_KEY, guest),
                                "plat051-holder",
                                new ProtosExactModuleOverlayResolver.ExactModule(HOLDER_KEY, holder)),
                        ProtosModuleResolver.rejecting());
        ProtosPrelude prelude =
                ProtosCoreBootstrap.withSemanticTransferFamiliesForTesting(List.of(family))
                        .bootstrap(CORE, resolver);
        return ProtosHostedExecutionTestFixture.open(prelude);
    }

    private static Object await(ProtosHostedExecutionTestFixture hosted, String source) {
        ProtosFutureValue future =
                assertInstanceOf(ProtosFutureValue.class, hosted.evaluatePersistent("<plat051-a2>", source));
        ProtosActorExecutionDomain domain = hosted.activation().executionDomain();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (future.isPending()) {
            hosted.callEntered(
                    () -> {
                        domain.dispatchUntilIdle();
                        return null;
                    });
            if (System.nanoTime() > deadline) {
                fail("PLAT051-A2 hosted Future did not settle: " + source);
            }
            Thread.onSpinWait();
        }
        assertEquals(
                ProtosFutureValue.State.RESOLVED,
                future.state(),
                () ->
                        source
                                + " failed with "
                                + future.failedError()
                                        .map(error -> describeError(hosted, error))
                                        .orElse("?"));
        return future.resolvedValue().orElseThrow();
    }

    /** Names the standard error prototype and local slots of a failure for diagnostics. */
    private static String describeError(ProtosHostedExecutionTestFixture hosted, ProtosObjectValue error) {
        Object parent = error.parent().orElse(null);
        String kind = "?";
        for (Map.Entry<String, Object> binding :
                hosted.activation().prelude().orElseThrow().bindings().localSlotsSnapshot().entrySet()) {
            if (binding.getValue() == parent) {
                kind = binding.getKey();
            }
        }
        return kind + " " + error.localSlotsSnapshot();
    }

    private static String string(Object value) {
        return assertInstanceOf(ProtosStringValue.class, value).value();
    }

    private static ProtosObjectValue graph(
            ProtosSemanticTransferValue shared, ProtosSemanticTransferValue twin) {
        ProtosObjectValue graph = new ProtosObjectValue(ProtosObjectValue.rootObject());
        graph.createLocalSlot("shared", shared);
        graph.createLocalSlot("again", shared);
        graph.createLocalSlot("twin", twin);
        graph.createLocalSlot("self", graph);
        return graph;
    }

    /**
     * Test-only family owned by a guest standard module. Materialization loads that module in the
     * destination domain and runs its guest {@code surface} function to create the value's
     * destination-local {@code describe} Closure.
     */
    private static final class GuestFamily extends ProtosSemanticTransferFamily {
        final AtomicInteger materialized = new AtomicInteger();
        final AtomicInteger sourceDescribeCalls = new AtomicInteger();
        final List<ProtosActorExecutionDomain> domains = new CopyOnWriteArrayList<>();

        GuestFamily() {
            super(GUEST_KEY);
        }

        ProtosSemanticTransferValue mint(String text) {
            ProtosSemanticTransferValue value = newValue(ProtosObjectValue.rootObject());
            value.createLocalSlot("text", new ProtosStringValue(text));
            value.createLocalSlot(
                    "describe",
                    ProtosClosureValue.nativeClosure(
                            (activation, arguments) -> {
                                sourceDescribeCalls.incrementAndGet();
                                return new ProtosStringValue("source-only");
                            }));
            value.freeze();
            return value;
        }

        @Override
        protected ProtosSemanticTransferPayload extract(ProtosSemanticTransferValue value) {
            return ProtosSemanticTransferPayload.of(
                    ((ProtosStringValue) value.readLocalSlot("text").orElseThrow()).value());
        }

        @Override
        protected boolean acceptsPayload(ProtosSemanticTransferPayload payload) {
            return payload.size() == 1 && payload.get(0) instanceof String;
        }

        @Override
        protected ProtosSemanticTransferValue materialize(
                ProtosSemanticTransferPayload payload, ProtosSemanticTransferDestination destination) {
            ProtosStringValue text = new ProtosStringValue((String) payload.get(0));
            ProtosObjectValue module = destination.ownerModule();
            Object describe =
                    destination.invoke(module.readLocalSlot("surface").orElseThrow(), List.of(text));
            ProtosSemanticTransferValue value = newValue(ProtosObjectValue.rootObject());
            value.createLocalSlot("text", text);
            value.createLocalSlot("describe", assertInstanceOf(ProtosClosureValue.class, describe));
            value.freeze();
            domains.add(destination.executionDomain());
            materialized.incrementAndGet();
            return value;
        }
    }
}
