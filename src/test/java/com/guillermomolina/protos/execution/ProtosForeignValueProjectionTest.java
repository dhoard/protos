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

import com.guillermomolina.protos.runtime.ProtosTestIntegers;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.guillermomolina.protos.execution.ProtosForeignAdmissionDescriptor.Capability;
import com.guillermomolina.protos.execution.ProtosForeignValueFixture.Fake;
import com.guillermomolina.protos.execution.ProtosForeignValueFixture.Integral;
import com.guillermomolina.protos.execution.ProtosForeignValueFixture.TestForeignFailure;
import com.guillermomolina.protos.runtime.ProtosBooleanValue;
import com.guillermomolina.protos.runtime.ProtosIntegerValue;
import com.guillermomolina.protos.runtime.ProtosNullValue;
import com.guillermomolina.protos.runtime.ProtosObjectValue;
import com.guillermomolina.protos.runtime.ProtosRawForeignValue;
import com.guillermomolina.protos.runtime.ProtosStringValue;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** I082-D D188 member/call/index/iteration projection, ForeignError, and session lifetime. */
class ProtosForeignValueProjectionTest {
    private static final String M = "m: import(\"test:root\")\n";
    private static final Set<String> FOREIGN_ERROR_SLOTS =
            Set.of("language", "operation", "category", "foreignCategory", "message", "cause");

    private static ProtosForeignValueFixture fixture(Fake root) throws Exception {
        ProtosForeignValueFixture fixture = new ProtosForeignValueFixture();
        fixture.provider.modules.put("root", root);
        return fixture;
    }

    private static Fake adder(ProtosForeignValueFixture.TestProvider provider) {
        Fake fn = new Fake(false, Capability.EXECUTABLE);
        fn.body =
                arguments -> {
                    BigInteger sum = BigInteger.ZERO;
                    for (ProtosForeignArgument argument : arguments) {
                        if (ProtosForeignValueFixture.scalar(argument) instanceof BigInteger integer) {
                            sum = sum.add(integer);
                        }
                    }
                    return new Integral(sum);
                };
        return fn;
    }

    @Test
    void protosChainWinsThenFaithfulFallbackAdmitsAndInstitutionsCannotBeHijacked()
            throws Exception {
        Fake hostile = new Fake(false);
        for (String name : List.of("call", "at", "atPut", "each", "==", "hash", "ifNull")) {
            hostile.member(name, "foreign " + name);
        }
        hostile.member("child", new Fake(false));
        hostile.unfaithful.put("secret", "side effect");
        Fake root = new Fake(false).member("hostile", hostile).member("text", "foreign text");
        try (ProtosForeignValueFixture fixture = fixture(root)) {
            ProtosForeignValueFixture.TestProvider provider = fixture.provider;
            String h = M + "h: m.hostile\n";

            // Root Object supplies ifNull before any foreign fallback.
            assertInstanceOf(
                    com.guillermomolina.protos.runtime.ProtosClosureValue.class,
                    fixture.eval(h + "h.ifNull"));
            assertInstanceOf(ProtosRawForeignValue.class, fixture.eval(h + "h.child"));
            assertSame(ProtosBooleanValue.TRUE, fixture.eval(h + "h == h"));
            assertInstanceOf(ProtosIntegerValue.class, fixture.eval(h + "h.hash()"));
            for (String reserved : List.of("call", "at", "atPut", "each")) {
                assertMissing(fixture, fixture.failure(h + "h." + reserved));
            }
            assertMissing(fixture, fixture.failure(h + "h.secret"));
            for (String name : List.of("call", "at", "atPut", "each", "==", "hash", "ifNull", "secret")) {
                assertEquals(0, provider.count("read:" + name), name);
            }
            assertEquals(1, provider.count("read:child"));

            // Facade: its own slots win over the faithful target projection.
            assertEquals(
                    "foreign text",
                    ((ProtosStringValue) fixture.eval(M + "m.text")).value());
            assertEquals("mine", ((ProtosStringValue) fixture.eval(M + "m.text: \"mine\"\nm.text")).value());
        }
    }

    @Test
    void writesAreNeverRedirectedAndRawReferencesGetNoSlots() throws Exception {
        Fake plain = new Fake(false).member("x", "foreign x");
        try (ProtosForeignValueFixture fixture = fixture(new Fake(false).member("plain", plain))) {
            String p = M + "p: m.plain\n";
            assertOrdinary(fixture, fixture.failure(p + "p.x = 1"));
            assertOrdinary(fixture, fixture.failure(p + "p.y: 2"));
            assertEquals(Map.of("x", "foreign x"), plain.faithful);
            assertEquals(0, fixture.provider.count("read:y"));
        }
    }

    @Test
    void callIsProjectedOnlyForUnambiguousExecutablesAndMemberInvocationIsReadThenCall()
            throws Exception {
        Fake root = new Fake(false);
        try (ProtosForeignValueFixture fixture = fixture(root)) {
            ProtosForeignValueFixture.TestProvider provider = fixture.provider;
            root.member("fn", adder(provider))
                    .member("plain", new Fake(false))
                    .member("both", new Fake(false, Capability.EXECUTABLE, Capability.INSTANTIABLE))
                    .member("cls", new Fake(false, Capability.INSTANTIABLE))
                    .member("obj", new Fake(false).member("method", adder(provider)));

            assertEquals(
                    BigInteger.valueOf(6),
                    ProtosTestIntegers.exact(fixture.eval(M + "f: m.fn\nf(1, 2, 3, \"s\", 1.5, true, null)")));
            List<ProtosForeignArgument> received = provider.executions.get(0);
            assertEquals(
                    List.of(
                            ProtosForeignAdmissionDescriptor.Kind.INTEGER,
                            ProtosForeignAdmissionDescriptor.Kind.INTEGER,
                            ProtosForeignAdmissionDescriptor.Kind.INTEGER,
                            ProtosForeignAdmissionDescriptor.Kind.STRING,
                            ProtosForeignAdmissionDescriptor.Kind.BINARY64,
                            ProtosForeignAdmissionDescriptor.Kind.BOOLEAN,
                            ProtosForeignAdmissionDescriptor.Kind.NULL),
                    received.stream().map(ProtosForeignArgument::kind).toList());

            for (String target : List.of("plain", "both", "cls")) {
                assertOrdinary(fixture, fixture.failure(M + "x: m." + target + "\nx()"));
                assertMissing(fixture, fixture.failure(M + "x: m." + target + "\nx.call"));
            }
            assertEquals(1, provider.executions.size());

            provider.events.clear();
            assertEquals(
                    BigInteger.valueOf(5),
                    ProtosTestIntegers.exact(fixture.eval(M + "m.obj.method(2, 3)")));
            assertEquals(1, provider.count("read:method"));
            assertEquals(
                    BigInteger.valueOf(4),
                    ProtosTestIntegers.exact(fixture.eval(M + "m.fn(4)")));
            assertEquals(1, provider.count("read:fn"));
        }
    }

    @Test
    void indexedProjectionIsFaithfulOnlyWhenUnambiguousAndNeverArrayFamily() throws Exception {
        Fake array = new Fake(false, Capability.INDEXED_READ, Capability.INDEXED_WRITE);
        array.elements.addAll(List.of(new Integral(BigInteger.ONE), "two"));
        Fake both = new Fake(false, Capability.INDEXED_READ, Capability.HASH_ENTRIES);
        both.elements.add("x");
        try (ProtosForeignValueFixture fixture =
                fixture(new Fake(false).member("array", array).member("both", both))) {
            String a = M + "a: m.array\n";
            assertEquals(BigInteger.ONE, ProtosTestIntegers.exact(fixture.eval(a + "a[0]")));
            assertEquals("two", ((ProtosStringValue) fixture.eval(a + "a.at(1)")).value());
            assertEquals(BigInteger.valueOf(9), ProtosTestIntegers.exact(fixture.eval(a + "a[0] = 9")));
            assertEquals(BigInteger.valueOf(9), array.elements.get(0));
            assertMissing(fixture, fixture.failure(M + "b: m.both\nb[0]"));
            assertSame(ProtosObjectValue.rootObject(), fixture.eval(a + "a.parent()"));
            assertSame(ProtosBooleanValue.FALSE, fixture.eval(a + "a.parent() === Array"));
            assertMissing(fixture, fixture.failure(a + "a.size()"));
        }
    }

    @Test
    void onlyClosuresCrossAsD189CallbacksAndOrdinaryObjectsNeverCross() throws Exception {
        Fake root = new Fake(false);
        try (ProtosForeignValueFixture fixture = fixture(root)) {
            root.member("fn", adder(fixture.provider))
                    .member("other", new Fake(false))
                    .member("seq", new Fake(false, Capability.INDEXED_READ));
            // A Closure crosses only as an operation-scoped D189 callback (ProtosForeignCallbackTest).
            assertOrdinary(fixture, fixture.failure(M + "m.fn({})"));
            assertOrdinary(fixture, fixture.failure(M + "m.fn(Array(1))"));
            assertOrdinary(fixture, fixture.failure(M + "m.fn(m)"));
            assertTrue(fixture.provider.executions.isEmpty());
            // A raw reference of the same session crosses as its exact underlying target.
            fixture.eval(M + "m.fn(m.other)");
            assertEquals(
                    ProtosForeignAdmissionDescriptor.Kind.RAW,
                    fixture.provider.executions.get(0).get(0).kind());
            assertSame(root.faithful.get("other"), fixture.provider.executions.get(0).get(0).value());
            // Indexed shape never implies iteration: without ITERABLE, each stays an ordinary miss.
            assertMissing(fixture, fixture.failure(M + "m.seq.each"));
        }
    }

    @Test
    void enteredFailuresAreFreshSanitizedCycleSafeForeignErrors() throws Exception {
        TestForeignFailure first = new TestForeignFailure("safe message");
        TestForeignFailure second = new TestForeignFailure("cause message");
        first.foreignCause = second;
        second.foreignCause = first;
        Fake root = new Fake(false).member("failing", first);
        try (ProtosForeignValueFixture fixture = fixture(root)) {
            ProtosObjectValue error = fixture.failure(M + "m.failing");
            ProtosObjectValue again = fixture.failure(M + "m.failing");
            assertNotSame(error, again);
            assertSame(fixture.standardError("ForeignError"), error.parent().orElseThrow());
            assertSame(fixture.standardError("Error"), fixture.standardError("ForeignError").parent().orElseThrow());
            assertEquals(FOREIGN_ERROR_SLOTS, error.localSlotsSnapshot().keySet());
            assertEquals("test-lang", text(error, "language"));
            assertEquals("readMember", text(error, "operation"));
            assertEquals("test-category", text(error, "category"));
            assertEquals("TestForeignFailure", text(error, "foreignCategory"));
            assertEquals("safe message", text(error, "message"));

            ProtosObjectValue cause = (ProtosObjectValue) error.readLocalSlot("cause").orElseThrow();
            assertSame(fixture.standardError("ForeignError"), cause.parent().orElseThrow());
            assertEquals("cause message", text(cause, "message"));
            assertSame(ProtosNullValue.INSTANCE, cause.readLocalSlot("cause").orElseThrow());
            assertSafePayload(error);

            // The I082-C acquisition is an entered operation too.
            fixture.provider.modules.put("boom", new TestForeignFailure("no module"));
            ProtosObjectValue importFailure = fixture.failure("import(\"test:boom\")");
            assertEquals("import", text(importFailure, "operation"));
        }
    }

    private static final BigInteger TWO_POW_63 = BigInteger.ONE.shiftLeft(63);

    /** Echoes its first argument back as the provider's own source-classified Integer. */
    private static Fake echo() {
        Fake fn = new Fake(false, Capability.EXECUTABLE);
        fn.body = arguments ->
                new Integral((BigInteger) ProtosForeignValueFixture.scalar(arguments.get(0)));
        return fn;
    }

    @Test
    void exactIntegersCrossAsLongOrBigIntegerOnlyAndAdmitExactly() throws Exception {
        Fake root = new Fake(false);
        try (ProtosForeignValueFixture fixture = fixture(root)) {
            root.member("echo", echo());
            String[] spellings = {
                "0", "-9223372036854775808", "9223372036854775807",
                "9223372036854775808", "-9223372036854775809"};
            BigInteger[] values = {
                BigInteger.ZERO, BigInteger.valueOf(Long.MIN_VALUE),
                BigInteger.valueOf(Long.MAX_VALUE), TWO_POW_63,
                TWO_POW_63.negate().subtract(BigInteger.ONE)};
            for (int index = 0; index < spellings.length; index++) {
                Object admitted = fixture.eval(M + "m.echo(" + spellings[index] + ")");
                assertEquals(values[index], ProtosTestIntegers.exact(admitted), spellings[index]);
                Object outbound = fixture.provider.executions.get(index).get(0).value();
                // Outbound: signed-64 as a Long, a BigInteger only beyond that range.
                if (values[index].bitLength() < Long.SIZE) {
                    assertEquals(values[index].longValueExact(),
                            assertInstanceOf(Long.class, outbound), spellings[index]);
                    assertInstanceOf(ProtosIntegerValue.class, admitted, spellings[index]);
                } else {
                    assertEquals(values[index],
                            assertInstanceOf(BigInteger.class, outbound), spellings[index]);
                }
            }
            // A provider-returned large Integer is owned by the admitting Prelude.
            assertSame(ProtosBooleanValue.TRUE,
                    fixture.eval(M + "m.echo(9223372036854775808).parent() === BigInteger"));
            assertSame(ProtosBooleanValue.TRUE,
                    fixture.eval(M + "m.echo(9223372036854775808) - 1 === 9223372036854775807"));
        }
    }

    @Test
    void integerDescriptorsHoldOnlyNormalizedHostScalars() {
        assertInstanceOf(Long.class,
                ProtosForeignAdmissionDescriptor.integral(BigInteger.valueOf(Long.MIN_VALUE))
                        .scalar());
        assertInstanceOf(Long.class,
                ProtosForeignAdmissionDescriptor.integral(BigInteger.valueOf(Long.MAX_VALUE))
                        .scalar());
        assertEquals(TWO_POW_63, ProtosForeignAdmissionDescriptor.integral(TWO_POW_63).scalar());
        assertEquals(5L, ProtosForeignAdmissionDescriptor.integral(5).scalar());
        // A forged guest Integer or an unnormalized host scalar is never an Integer descriptor.
        for (Object forged : new Object[] {
                new ProtosIntegerValue(1L), BigInteger.ONE, 1, 1.0d, "1"}) {
            assertThrows(IllegalArgumentException.class,
                    () -> new ProtosForeignAdmissionDescriptor(
                            ProtosForeignAdmissionDescriptor.Kind.INTEGER,
                            forged,
                            null,
                            Set.of()),
                    String.valueOf(forged));
        }
        assertThrows(IllegalArgumentException.class,
                () -> ProtosForeignAdmissionDescriptor.integral(1.5d));
    }

    @Test
    void rawHandlesCallbacksAndClosedSessionsKeepTheirIntegerContract() throws Exception {
        Fake root = new Fake(false);
        try (ProtosForeignValueFixture fixture = fixture(root)) {
            Fake viaCallback = new Fake(false, Capability.EXECUTABLE);
            viaCallback.body = arguments -> {
                try {
                    ProtosForeignArgument result = arguments.get(0).callback()
                            .invoke(List.of(new Integral(TWO_POW_63)));
                    return new Integral(
                            (BigInteger) ProtosForeignValueFixture.scalar(result));
                } catch (Exception failure) {
                    throw new IllegalStateException(failure);
                }
            };
            root.member("echo", echo())
                    .member("fn", adder(fixture.provider))
                    .member("viaCallback", viaCallback)
                    .member("other", new Fake(false));
            // The callback receives the admitted large Integer and returns a large one.
            assertEquals(TWO_POW_63.add(BigInteger.ONE), ProtosTestIntegers.exact(
                    fixture.eval(M + "m.viaCallback((x) => x + 1)")));
            assertSame(ProtosBooleanValue.TRUE, fixture.eval(
                    M + "m.viaCallback((x) => (x.parent() === BigInteger).ifTrue() { x })"
                            + ".parent() === BigInteger"));
            // A RAW handle keeps its exact identity next to Integer arguments.
            fixture.eval(M + "m.fn(m.other, 9223372036854775808)");
            ProtosForeignArgument raw =
                    fixture.provider.executions.get(fixture.provider.executions.size() - 1).get(0);
            assertEquals(ProtosForeignAdmissionDescriptor.Kind.RAW, raw.kind());
            assertSame(root.faithful.get("other"), raw.value());

            ProtosRawForeignValue echo =
                    assertInstanceOf(ProtosRawForeignValue.class, fixture.eval(M + "m.saved: m.echo"));
            fixture.facade("root").attachmentForRuntime().orElseThrow().session().closeForRuntime();
            int executions = fixture.provider.executions.size();
            assertOrdinary(fixture, fixture.failure(M + "m.saved(9223372036854775808)"));
            assertEquals(executions, fixture.provider.executions.size());
            assertSame(echo, fixture.eval(M + "m.saved"));
        }
    }

    @Test
    void largeIntegersAreAdmittedByEachIndependentDomain() throws Exception {
        Fake leftRoot = new Fake(false);
        Fake rightRoot = new Fake(false);
        try (ProtosForeignValueFixture left = fixture(leftRoot);
                ProtosForeignValueFixture right = fixture(rightRoot)) {
            leftRoot.member("echo", echo());
            rightRoot.member("echo", echo());
            Object fromLeft = left.eval(M + "m.echo(9223372036854775808)");
            Object fromRight = right.eval(M + "m.echo(9223372036854775808)");
            assertNotSame(fromLeft, fromRight);
            assertSame(left.prelude.bigIntegerPrototype(),
                    ((ProtosObjectValue) fromLeft).parent().orElseThrow());
            assertSame(right.prelude.bigIntegerPrototype(),
                    ((ProtosObjectValue) fromRight).parent().orElseThrow());
            assertEquals(ProtosTestIntegers.exact(fromLeft), ProtosTestIntegers.exact(fromRight));
        }
    }

    @Test
    void closedSessionFailsBeforeEntryAndNeverRebinds() throws Exception {
        Fake root = new Fake(false);
        try (ProtosForeignValueFixture fixture = fixture(root)) {
            root.member("fn", adder(fixture.provider));
            ProtosRawForeignValue fn =
                    assertInstanceOf(ProtosRawForeignValue.class, fixture.eval(M + "m.saved: m.fn"));
            ProtosForeignModuleFacadeValue facade = fixture.facade("root");
            ProtosForeignProviderSessionBinding session =
                    facade.attachmentForRuntime().orElseThrow().session();
            int sessions = fixture.provider.sessions.size();
            session.closeForRuntime();

            long reads = fixture.provider.count("read:fn");
            assertOrdinary(fixture, fixture.failure(M + "m.fn"));
            assertEquals(reads, fixture.provider.count("read:fn"));
            assertOrdinary(fixture, fixture.failure(M + "m.saved(1)"));
            assertTrue(fixture.provider.executions.isEmpty());
            assertSame(session, ((ProtosForeignHandle) fn.handleForRuntime()).session());
            assertEquals(sessions, fixture.provider.sessions.size());
            assertSame(ProtosBooleanValue.TRUE, fixture.eval(M + "m.saved === m.saved"));
        }
    }

    @Test
    void facadeIsTheActorModuleInstanceAndProjectsItsTargetThroughTheSharedSubstrate()
            throws Exception {
        Fake seq = new Fake(false, Capability.INDEXED_READ);
        seq.elements.add("first");
        try (ProtosForeignValueFixture fixture = fixture(new Fake(false))) {
            fixture.provider.modules.put("seq", seq);
            assertSame(
                    ProtosBooleanValue.TRUE,
                    fixture.eval("import(\"test:seq\") === import(\"test:seq\")"));
            ProtosForeignModuleFacadeValue facade = fixture.facade("seq");
            assertSame(
                    facade,
                    fixture.root.actorModuleState()
                            .lookup(ProtosForeignModuleKey.encode("test", "seq"))
                            .orElseThrow()
                            .instance());
            assertEquals(
                    "first",
                    ((ProtosStringValue) fixture.eval("s: import(\"test:seq\")\ns[0]")).value());
            assertEquals(1, fixture.provider.count("at:0"));
        }
    }

    @Test
    void foreignValueSubstrateGrantsNoHostAuthorityAndNeverHandsProvidersAClosure() throws Exception {
        for (String file :
                List.of(
                        "execution/ProtosForeignAdmissionDescriptor.java",
                        "execution/ProtosForeignArgument.java",
                        "execution/ProtosForeignCallback.java",
                        "execution/ProtosForeignCallbackScope.java",
                        "execution/ProtosForeignEachCall.java",
                        "execution/ProtosForeignFailureDescription.java",
                        "execution/ProtosForeignHandle.java",
                        "execution/ProtosForeignOperation.java",
                        "execution/ProtosForeignProjectedOperations.java",
                        "execution/ProtosForeignValueAdapter.java",
                        "execution/ProtosForeignValueAdmission.java",
                        "execution/ProtosForeignModuleFacadeValue.java",
                        "runtime/ProtosForeignProjectedReceiver.java")) {
            String source =
                    Files.readString(Path.of("src/main/java/com/guillermomolina/protos", file));
            for (String forbidden :
                    List.of(
                            "HostAccess",
                            "PolyglotAccess",
                            "IOAccess",
                            "allowAllAccess",
                            "allowHostClassLookup",
                            "allowNativeAccess",
                            "allowCreateProcess",
                            "InteropLibrary",
                            "ServiceLoader",
                            "writeMember",
                            "invokeMember",
                            "instantiate")) {
                if (file.equals("execution/ProtosForeignValueAdapter.java")
                        && (forbidden.equals("writeMember") || forbidden.equals("instantiate"))) {
                    // LIB021-A: D193 std:interop explicit operations; never ordinary projection.
                    continue;
                }
                assertFalse(source.contains(forbidden), file + " must not use " + forbidden);
            }
        }
        // The raw value's only interop export is the bounded opaque display of represented values.
        String raw =
                Files.readString(
                        Path.of(
                                "src/main/java/com/guillermomolina/protos/runtime/ProtosRawForeignValue.java"));
        for (String forbidden : List.of("HostAccess", "PolyglotAccess", "readMember", "execute(")) {
            assertFalse(raw.contains(forbidden), "ProtosRawForeignValue must not use " + forbidden);
        }
        for (var method : ProtosForeignValueAdapter.class.getMethods()) {
            for (Class<?> parameter : method.getParameterTypes()) {
                assertFalse(
                        com.guillermomolina.protos.runtime.ProtosClosureValue.class
                                .isAssignableFrom(parameter),
                        method.getName());
            }
        }
    }

    private static String text(ProtosObjectValue error, String slot) {
        return ((ProtosStringValue) error.readLocalSlot(slot).orElseThrow()).value();
    }

    private static void assertSafePayload(ProtosObjectValue error) {
        for (Object value : error.localSlotsSnapshot().values()) {
            assertFalse(value instanceof Throwable);
            if (value instanceof ProtosStringValue string) {
                assertFalse(string.value().contains("SECRET"));
            } else if (value instanceof ProtosObjectValue cause) {
                assertSafePayload(cause);
            } else {
                assertSame(ProtosNullValue.INSTANCE, value);
            }
        }
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
