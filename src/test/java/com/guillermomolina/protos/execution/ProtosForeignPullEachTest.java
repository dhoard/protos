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
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.guillermomolina.protos.execution.ProtosForeignAdmissionDescriptor.Capability;
import com.guillermomolina.protos.execution.ProtosForeignValueFixture.Binary64;
import com.guillermomolina.protos.execution.ProtosForeignValueFixture.Fake;
import com.guillermomolina.protos.execution.ProtosForeignValueFixture.Integral;
import com.guillermomolina.protos.execution.ProtosForeignValueFixture.Marker;
import com.guillermomolina.protos.execution.ProtosForeignValueFixture.TestForeignFailure;
import com.guillermomolina.protos.runtime.ProtosActivation;
import com.guillermomolina.protos.runtime.ProtosActorValueTransfer;
import com.guillermomolina.protos.runtime.ProtosBooleanValue;
import com.guillermomolina.protos.runtime.ProtosClosureValue;
import com.guillermomolina.protos.runtime.ProtosObjectValue;
import com.guillermomolina.protos.runtime.ProtosRawForeignValue;
import com.guillermomolina.protos.runtime.ProtosSignalException;
import com.guillermomolina.protos.runtime.ProtosStringValue;
import com.guillermomolina.protos.runtime.ProtosTask;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * I082-D2 D188 projected foreign {@code each}: Protos-side pull iteration over a provider
 * iterator, executed by real guest code inside the module Task (structured C-prime dispatch).
 */
class ProtosForeignPullEachTest {
    private static final String M = "m: import(\"test:root\")\nlog: m.log\n";
    private static final Set<String> PULL_EVENTS = Set.of("iterator", "hasNext", "block");

    private static ProtosForeignValueFixture fixture(Fake seq) throws Exception {
        ProtosForeignValueFixture fixture = new ProtosForeignValueFixture();
        Fake log = new Fake(false, Capability.EXECUTABLE);
        log.body =
                arguments -> {
                    fixture.provider.events.add("block");
                    return Marker.NULL;
                };
        fixture.provider.modules.put("root", new Fake(false).member("seq", seq).member("log", log));
        return fixture;
    }

    private static Fake iterable(Object... elements) {
        Fake seq = new Fake(false, Capability.ITERABLE);
        seq.elements.addAll(List.of(elements));
        return seq;
    }

    /** The provider's pull/callback interleaving, without member reads. */
    private static List<String> pulls(ProtosForeignValueFixture fixture) {
        List<String> pulls = new ArrayList<>();
        for (String event : fixture.provider.events) {
            if (PULL_EVENTS.contains(event) || event.startsWith("next:")) {
                pulls.add(event);
            }
        }
        return pulls;
    }

    private static void assertNoLeak(ProtosForeignValueFixture fixture) {
        assertTrue(fixture.provider.leaks.isEmpty(), "Protos object reached the provider");
    }

    @Test
    void eachIsProjectedOnlyForDeclaredIterablesAndNeverHijackedOrFamilyConferring()
            throws Exception {
        Fake seq = iterable(new Integral(BigInteger.ONE));
        seq.member("each", new Fake(false, Capability.EXECUTABLE));
        Fake shaped = new Fake(false, Capability.INDEXED_READ, Capability.INDEXED_WRITE);
        shaped.elements.add("x");
        Fake hashed = new Fake(false, Capability.HASH_ENTRIES);
        Fake named = new Fake(false).member("each", new Fake(false, Capability.EXECUTABLE));
        try (ProtosForeignValueFixture fixture = fixture(seq)) {
            ((Fake) fixture.provider.modules.get("root"))
                    .member("shaped", shaped)
                    .member("hashed", hashed)
                    .member("named", named)
                    .member("plain", new Fake(false));
            for (String target : List.of("shaped", "hashed", "named", "plain")) {
                assertMissing(fixture, fixture.failure(M + "m." + target + ".each"));
                assertMissing(
                        fixture,
                        fixture.failure(M + "m." + target + ".each((x) => { log(x) })"));
            }
            // A same-spelling foreign member is never read for the institution name.
            assertEquals(0, fixture.provider.count("read:each"));
            assertTrue(pulls(fixture).isEmpty());

            // The projection wins over the foreign member literally named each.
            assertSame(
                    ProtosBooleanValue.TRUE,
                    fixture.eval(M + "s: m.seq\ns.each((x) => { log(x) }) === s"));
            assertEquals(0, fixture.provider.count("read:each"));
            assertEquals(List.of("iterator", "hasNext", "next:0", "block", "hasNext"), pulls(fixture));

            // Iteration confers no Array or Map family membership.
            String s = M + "s: m.seq\n";
            assertSame(ProtosObjectValue.rootObject(), fixture.eval(s + "s.parent()"));
            assertSame(ProtosBooleanValue.FALSE, fixture.eval(s + "s.parent() === Array"));
            assertSame(ProtosBooleanValue.FALSE, fixture.eval(s + "s.parent() === Map"));
            assertMissing(fixture, fixture.failure(s + "s.size()"));
            assertMissing(fixture, fixture.failure(s + "s[0]"));
            assertNoLeak(fixture);
        }
    }

    @Test
    void attachedFacadeProjectsTheSameEachAfterItsOrdinaryChain() throws Exception {
        Fake seq = iterable("a", "b");
        try (ProtosForeignValueFixture fixture = fixture(new Fake(false))) {
            fixture.provider.modules.put("seq", seq);
            String s = M + "s: import(\"test:seq\")\n";
            assertSame(
                    ProtosBooleanValue.TRUE,
                    fixture.eval(s + "s.each((x) => { log(x) }) === s"));
            assertEquals(
                    List.of("iterator", "hasNext", "next:0", "block", "hasNext", "next:1", "block", "hasNext"),
                    pulls(fixture));
            List<List<ProtosForeignArgument>> executions = fixture.provider.executions;
            assertEquals("a", executions.get(0).get(0).value());
            assertEquals("b", executions.get(1).get(0).value());
            assertNoLeak(fixture);
        }
    }

    @Test
    void elementsArePulledOneAtATimeAdmittedAndVisitedInProviderOrder() throws Exception {
        Fake raw = new Fake(false);
        Fake seq =
                iterable(
                        new Integral(BigInteger.valueOf(7)),
                        "two",
                        Boolean.TRUE,
                        new Binary64(1.5),
                        Marker.NULL,
                        raw);
        try (ProtosForeignValueFixture fixture = fixture(seq)) {
            String s = M + "s: m.seq\n";
            assertSame(ProtosBooleanValue.TRUE, fixture.eval(s + "s.each((x) => { log(x) }) === s"));
            List<String> expected = new ArrayList<>(List.of("iterator"));
            for (int i = 0; i < 6; i++) {
                expected.addAll(List.of("hasNext", "next:" + i, "block"));
            }
            expected.add("hasNext");
            assertEquals(expected, pulls(fixture));
            assertEquals(1, fixture.provider.count("iterator"));

            List<List<ProtosForeignArgument>> executions = fixture.provider.executions;
            assertEquals(6, executions.size());
            assertEquals(
                    List.of(
                            ProtosForeignAdmissionDescriptor.Kind.INTEGER,
                            ProtosForeignAdmissionDescriptor.Kind.STRING,
                            ProtosForeignAdmissionDescriptor.Kind.BOOLEAN,
                            ProtosForeignAdmissionDescriptor.Kind.BINARY64,
                            ProtosForeignAdmissionDescriptor.Kind.NULL,
                            ProtosForeignAdmissionDescriptor.Kind.RAW),
                    executions.stream().map(arguments -> arguments.get(0).kind()).toList());
            assertSame(raw, executions.get(5).get(0).value());

            // The block receives raw references as raw foreign values.
            Object last = fixture.eval(s + "last: null\ns.each((x) => { last = x })\nlast");
            assertInstanceOf(ProtosRawForeignValue.class, last);
            assertSame(
                    ProtosBooleanValue.TRUE,
                    fixture.eval(s + "n: 0\ns.each((x) => { n = n + 1 })\nn == 6"));
            assertNoLeak(fixture);
        }
    }

    @Test
    void emptyIteratorInvokesNothingAndReturnsTheReceiver() throws Exception {
        try (ProtosForeignValueFixture fixture = fixture(iterable())) {
            assertSame(
                    ProtosBooleanValue.TRUE,
                    fixture.eval(M + "s: m.seq\ns.each((x) => { log(x) }) === s"));
            assertEquals(List.of("iterator", "hasNext"), pulls(fixture));
            assertTrue(fixture.provider.executions.isEmpty());
            assertNoLeak(fixture);
        }
    }

    @Test
    void arityAndCallabilityAreValidatedBeforeIteratorAcquisition() throws Exception {
        try (ProtosForeignValueFixture fixture = fixture(iterable("a"))) {
            ((Fake) fixture.provider.modules.get("root")).member("plain", new Fake(false));
            String s = M + "s: m.seq\n";
            for (String call :
                    List.of(
                            "s.each()",
                            "s.each((x) => { log(x) }, (x) => { log(x) })",
                            "s.each(m.plain)",
                            "s.each({ call: 5 })")) {
                assertOrdinary(fixture, fixture.failure(s + call));
            }
            assertEquals(0, fixture.provider.count("iterator"));
            assertTrue(pulls(fixture).isEmpty());

            // Any ordinarily invokable value is accepted, not only a literal Closure.
            assertSame(
                    ProtosBooleanValue.TRUE,
                    fixture.eval(s + "callable: { call: (x) => { log(x) } }\ns.each(callable) === s"));
            assertEquals(List.of("iterator", "hasNext", "next:0", "block", "hasNext"), pulls(fixture));
            assertNoLeak(fixture);
        }
    }

    @Test
    void blockErrorAndNonLocalExitStopPullingAndPropagateUnchanged() throws Exception {
        try (ProtosForeignValueFixture fixture = fixture(iterable("a", "b", "c"))) {
            String s = M + "s: m.seq\n";
            assertSame(
                    ProtosBooleanValue.TRUE,
                    fixture.eval(
                            s
                                    + "boom: Error()\n"
                                    + "Error.handle(() => {\n"
                                    + "    s.each((x) => { log(x)\n boom.signal() })\n"
                                    + "}, (caught) => caught === boom)"));
            assertEquals(List.of("iterator", "hasNext", "next:0", "block"), pulls(fixture));

            ProtosObjectValue failure =
                    fixture.failure(s + "s.each((x) => { log(x)\n Error().signal() })");
            assertSame(fixture.standardError("Error"), failure.parent().orElseThrow());

            fixture.provider.events.clear();
            assertEquals(String.valueOf("42"), com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerDecimalText(fixture.eval(
                                            s
                                                    + "f: () => {\n"
                                                    + "    s.each((x) => { log(x)\n ^42 })\n"
                                                    + "    0\n"
                                                    + "}\n"
                                                    + "f()")));
            assertEquals(List.of("iterator", "hasNext", "next:0", "block"), pulls(fixture));
            assertNoLeak(fixture);
        }
    }

    @Test
    void suspensionInsideTheBlockResumesTheSameVisitWithoutReplay() throws Exception {
        // Integer elements arrive as Protos Integers: the block computes with them.
        try (ProtosForeignValueFixture fixture =
                fixture(iterable(new Integral(BigInteger.ONE), new Integral(BigInteger.TWO)))) {
            assertEquals(String.valueOf("30"), com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerDecimalText(fixture.eval(
                                            M
                                                    + "s: m.seq\n"
                                                    + "total: 0\n"
                                                    + "s.each((x) => {\n"
                                                    + "    total = total + (() => x * 10).future().value()\n"
                                                    + "    log(x)\n"
                                                    + "})\n"
                                                    + "total")));
            assertEquals(
                    List.of("iterator", "hasNext", "next:0", "block", "hasNext", "next:1", "block", "hasNext"),
                    pulls(fixture));
            assertNoLeak(fixture);
        }
    }

    @Test
    void enteredIteratorFailuresAreFreshForeignErrors() throws Exception {
        for (String[] stage :
                List.of(
                        new String[] {"iterator", "iterator"},
                        new String[] {"hasNext", "iteratorHasNext"},
                        new String[] {"next", "iteratorNext"})) {
            Fake seq = iterable("a");
            seq.iteratorFailureStage = stage[0];
            seq.iteratorFailure = new TestForeignFailure("pull failed");
            try (ProtosForeignValueFixture fixture = fixture(seq)) {
                String source = M + "m.seq.each((x) => { log(x) })";
                ProtosObjectValue error = fixture.failure(source);
                ProtosObjectValue again = fixture.failure(source);
                assertNotSame(error, again);
                assertSame(fixture.standardError("ForeignError"), error.parent().orElseThrow());
                assertEquals(stage[1], text(error, "operation"));
                assertEquals("test-lang", text(error, "language"));
                assertEquals("pull failed", text(error, "message"));
                assertTrue(fixture.provider.executions.isEmpty(), stage[0]);
                assertNoLeak(fixture);
            }
        }
    }

    @Test
    void closedSessionFailsBeforeEntryAndNeverRebinds() throws Exception {
        try (ProtosForeignValueFixture fixture = fixture(iterable("a", "b"))) {
            Object saved = fixture.eval(M + "m.saved: m.seq");
            assertInstanceOf(ProtosRawForeignValue.class, saved);
            ProtosForeignProviderSessionBinding session =
                    fixture.facade("root").attachmentForRuntime().orElseThrow().session();
            int sessions = fixture.provider.sessions.size();
            session.closeForRuntime();

            assertOrdinary(
                    fixture,
                    fixture.failure("m: import(\"test:root\")\nm.saved.each((x) => { x })"));
            assertTrue(pulls(fixture).isEmpty());
            assertEquals(sessions, fixture.provider.sessions.size());
            assertSame(session, ((ProtosForeignHandle) ((ProtosRawForeignValue) saved).handleForRuntime()).session());
        }

        Fake seq = iterable("a", "b");
        try (ProtosForeignValueFixture fixture = fixture(seq)) {
            ProtosForeignProviderSessionBinding session =
                    fixture.facade("root").attachmentForRuntime().orElseThrow().session();
            int sessions = fixture.provider.sessions.size();
            // The session closes between the first pull and the next foreign entry.
            seq.afterNext = session::closeForRuntime;
            ProtosObjectValue error =
                    fixture.failure(
                            "m: import(\"test:root\")\ns: m.seq\nn: 0\ns.each((x) => { n = n + 1 })");
            assertOrdinary(fixture, error);
            assertEquals(List.of("iterator", "hasNext", "next:0"), pulls(fixture));
            assertEquals(sessions, fixture.provider.sessions.size());
            assertNoLeak(fixture);
        }
    }

    @Test
    void iterationIsLiveWithoutArrayOrMapSnapshotSemantics() throws Exception {
        Fake seq = iterable("a");
        try (ProtosForeignValueFixture fixture = fixture(seq)) {
            // An element the provider makes available after the first pull is still visited.
            seq.afterNext =
                    () -> {
                        if (seq.elements.size() == 1) {
                            seq.elements.add("late");
                        }
                    };
            fixture.eval(M + "m.seq.each((x) => { log(x) })");
            assertEquals(
                    List.of("a", "late"),
                    fixture.provider.executions.stream()
                            .map(arguments -> arguments.get(0).value())
                            .toList());
            assertNoLeak(fixture);
        }
    }

    @Test
    void projectedEachClosureIsNeitherActorTransferableNorParallel() throws Exception {
        try (ProtosForeignValueFixture fixture = fixture(iterable("a"))) {
            ProtosClosureValue each =
                    assertInstanceOf(
                            ProtosClosureValue.class, fixture.eval(M + "s: m.seq\ns.each"));
            assertTrue(ProtosForeignProjectedOperations.isProjectionClosure(each));
            ProtosSignalException failure =
                    assertThrows(
                            ProtosSignalException.class,
                            () -> ProtosActorValueTransfer.snapshotValue(each, fixture.activation()));
            assertSame(
                    fixture.standardError("NonTransferableValue"),
                    failure.error().parent().orElseThrow());
            assertEquals("NonParallel", parallelCopyFailure(each, fixture));
        }
    }

    @Test
    void pullIterationHasNoCallbackBridgeAndGrantsNoHostAuthority() throws Exception {
        for (String name : List.of("openIterator", "iteratorHasNext", "iteratorNext")) {
            boolean found = false;
            for (Method method : ProtosForeignValueAdapter.class.getMethods()) {
                if (!method.getName().equals(name)) {
                    continue;
                }
                found = true;
                for (Class<?> parameter : method.getParameterTypes()) {
                    for (Class<?> forbidden :
                            List.of(
                                    ProtosClosureValue.class,
                                    ProtosObjectValue.class,
                                    ProtosActivation.class,
                                    ProtosTask.class)) {
                        // Object-typed target/iterator parameters are guarded at runtime by the
                        // fixture's leak detector (assertNoLeak).
                        assertFalse(forbidden.isAssignableFrom(parameter), name);
                    }
                }
            }
            assertTrue(found, name);
        }
        String source =
                Files.readString(
                        Path.of(
                                "src/main/java/com/guillermomolina/protos/execution/ProtosForeignEachCall.java"));
        for (String forbidden :
                List.of(
                        "HostAccess",
                        "PolyglotAccess",
                        "IOAccess",
                        "allowAllAccess",
                        "InteropLibrary",
                        "ServiceLoader",
                        "openIterator(live, block",
                        "iteratorNext(live, block",
                        "iteratorHasNext(live, block")) {
            assertFalse(source.contains(forbidden), "ProtosForeignEachCall must not use " + forbidden);
        }
    }

    private static Object parallelCopy(Object value, ProtosForeignValueFixture fixture)
            throws Exception {
        Class<?> transfer =
                Class.forName("com.guillermomolina.protos.execution.ProtosParallelRuntime$Transfer");
        Method copy =
                transfer.getDeclaredMethod(
                        "copy", Object.class, ProtosActivation.class, IdentityHashMap.class);
        copy.setAccessible(true);
        return copy.invoke(null, value, fixture.activation(), new IdentityHashMap<Object, Object>());
    }

    private static String parallelCopyFailure(Object value, ProtosForeignValueFixture fixture) {
        InvocationTargetException failure =
                assertThrows(InvocationTargetException.class, () -> parallelCopy(value, fixture));
        return failure.getCause().getClass().getSimpleName();
    }

    private static String text(ProtosObjectValue error, String slot) {
        return ((ProtosStringValue) error.readLocalSlot(slot).orElseThrow()).value();
    }

    /** Ordinary missing-member failure: SlotNotFound, never ForeignError. */
    private static void assertMissing(ProtosForeignValueFixture fixture, ProtosObjectValue error) {
        assertSame(fixture.standardError("SlotNotFound"), error.parent().orElseThrow());
    }

    /** Ordinary pre-entry failure: a Core Error, never ForeignError. */
    private static void assertOrdinary(ProtosForeignValueFixture fixture, ProtosObjectValue error) {
        assertFalse(error.parent().orElseThrow() == fixture.standardError("ForeignError"));
        assertTrue(error.localSlotsSnapshot().isEmpty());
    }
}
