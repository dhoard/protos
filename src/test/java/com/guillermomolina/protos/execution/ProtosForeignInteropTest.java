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
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.guillermomolina.protos.execution.ProtosForeignAdmissionDescriptor.Capability;
import com.guillermomolina.protos.execution.ProtosForeignValueFixture.Fake;
import com.guillermomolina.protos.execution.ProtosForeignValueFixture.Integral;
import com.guillermomolina.protos.execution.ProtosForeignValueFixture.TestForeignFailure;
import com.guillermomolina.protos.runtime.ProtosBooleanValue;
import com.guillermomolina.protos.runtime.ProtosIntegerValue;
import com.guillermomolina.protos.runtime.ProtosObjectValue;
import com.guillermomolina.protos.runtime.ProtosRawForeignValue;
import com.guillermomolina.protos.runtime.ProtosStringValue;
import com.guillermomolina.protos.spi.foreign.ProtosForeignArgumentValue;
import com.guillermomolina.protos.spi.foreign.ProtosForeignValueClass;
import com.guillermomolina.protos.spi.foreign.polyglot.ProtosForeignScalarFamily;
import com.guillermomolina.protos.spi.foreign.polyglot.ProtosPolyglotValueOperations;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Value;
import org.graalvm.polyglot.proxy.ProxyArray;
import org.graalvm.polyglot.proxy.ProxyExecutable;
import org.junit.jupiter.api.Test;

/** LIB021-A {@code std:interop} explicit operations over the shared D188/D189 substrate. */
class ProtosForeignInteropTest {
    private static final String M = "I: import(\"std:interop\")\nm: import(\"test:root\")\n";
    private static final Set<String> OPERATIONS =
            Set.of("invoke", "instantiate", "readMember", "writeMember");

    private static ProtosForeignValueFixture fixture(Fake root) throws Exception {
        ProtosForeignValueFixture fixture = new ProtosForeignValueFixture();
        fixture.provider.modules.put("root", root);
        return fixture;
    }

    @Test
    void moduleSurfaceIsExactAndImportAloneTouchesNoProvider() throws Exception {
        try (ProtosForeignValueFixture fixture = fixture(new Fake(false))) {
            int sessions = fixture.provider.sessions.size();
            ProtosObjectValue module =
                    assertInstanceOf(
                            ProtosObjectValue.class, fixture.eval("import(\"std:interop\")"));
            assertEquals(OPERATIONS, module.localSlotsSnapshot().keySet());
            assertSame(
                    ProtosBooleanValue.FALSE,
                    fixture.eval("import(\"std:interop\").hasSlot(\"_interopFacility\")"));
            assertEquals(sessions, fixture.provider.sessions.size());
            assertTrue(fixture.provider.events.isEmpty());
            assertTrue(fixture.provider.executions.isEmpty());

            ProtosObjectValue facility =
                    ProtosStandardModuleMemberTestSupport.member(
                            fixture.prelude,
                            ProtosForeignInteropFacility.MODULE_KEY.canonicalId(),
                            ProtosForeignInteropFacility.BOOTSTRAP_SLOT);
            assertEquals(OPERATIONS, facility.localSlotsSnapshot().keySet());
            assertTrue(facility.isFrozen());
        }
    }

    @Test
    void invokeAndInstantiateAreExplicitlyDistinctAndOrdinaryCallIsUnchanged() throws Exception {
        Fake exec = new Fake(false, Capability.EXECUTABLE);
        exec.body = arguments -> "executed";
        Fake cls = new Fake(false, Capability.INSTANTIABLE);
        cls.constructor = arguments -> "constructed";
        Fake both = new Fake(false, Capability.EXECUTABLE, Capability.INSTANTIABLE);
        both.body = arguments -> "executed";
        both.constructor = arguments -> "constructed";
        Fake root = new Fake(false).member("exec", exec).member("cls", cls).member("both", both);
        try (ProtosForeignValueFixture fixture = fixture(root)) {
            ProtosForeignValueFixture.TestProvider provider = fixture.provider;

            assertEquals("executed", text(fixture.eval(M + "I.invoke(m.exec, 1, \"s\")")));
            assertEquals(
                    List.of(
                            ProtosForeignAdmissionDescriptor.Kind.INTEGER,
                            ProtosForeignAdmissionDescriptor.Kind.STRING),
                    provider.executions.get(0).stream().map(ProtosForeignArgument::kind).toList());
            assertEquals("constructed", text(fixture.eval(M + "I.instantiate(m.cls, 2)")));
            assertEquals(
                    BigInteger.TWO,
                    ProtosForeignValueFixture.scalar(provider.instantiations.get(0).get(0)));

            // Both capabilities: the operation named by the program selects the meaning.
            assertEquals("executed", text(fixture.eval(M + "I.invoke(m.both)")));
            assertEquals("constructed", text(fixture.eval(M + "I.instantiate(m.both)")));
            assertEquals(2, provider.executions.size());
            assertEquals(2, provider.instantiations.size());

            // The other family is known unsupported before entry.
            assertOrdinary(fixture, fixture.failure(M + "I.invoke(m.cls)"));
            assertOrdinary(fixture, fixture.failure(M + "I.instantiate(m.exec)"));

            // Ordinary call projection is untouched: only the unambiguous executable is callable.
            assertEquals("executed", text(fixture.eval(M + "f: m.exec\nf()")));
            for (String target : List.of("both", "cls")) {
                assertOrdinary(fixture, fixture.failure(M + "f: m." + target + "\nf()"));
                assertMissing(fixture, fixture.failure(M + "f: m." + target + "\nf.call"));
            }
            assertEquals(3, provider.executions.size());
            assertEquals(2, provider.instantiations.size());
        }
    }

    @Test
    void readMemberBypassesOrdinaryPrecedenceAndFidelityOnlyForTheExplicitRead()
            throws Exception {
        Fake hostile = new Fake(false, Capability.MEMBER_READ);
        List<String> institutions = List.of("call", "at", "atPut", "each", "==", "hash");
        for (String name : institutions) {
            hostile.member(name, "foreign " + name);
        }
        hostile.member("child", new Fake(false));
        hostile.unfaithful.put("secret", new Integral(BigInteger.valueOf(7)));
        Fake root = new Fake(false).member("hostile", hostile);
        try (ProtosForeignValueFixture fixture = fixture(root)) {
            ProtosForeignValueFixture.TestProvider provider = fixture.provider;
            String h = M + "h: m.hostile\n";

            for (String name : institutions) {
                assertEquals(
                        "foreign " + name,
                        text(fixture.eval(h + "I.readMember(h, \"" + name + "\")")),
                        name);
            }
            // Ordinary lookup still denotes the Protos institutions and reads nothing foreign.
            for (String name : List.of("call", "at", "atPut", "each")) {
                assertMissing(fixture, fixture.failure(h + "h." + name));
            }
            assertSame(ProtosBooleanValue.TRUE, fixture.eval(h + "h == h"));
            assertInstanceOf(ProtosIntegerValue.class, fixture.eval(h + "h.hash()"));
            for (String name : institutions) {
                assertEquals(1, provider.count("read:" + name), name);
            }

            // Not faithful for ordinary reads, yet reachable explicitly; results are D188-admitted.
            assertMissing(fixture, fixture.failure(h + "h.secret"));
            assertEquals(0, provider.count("read:secret"));
            assertEquals(String.valueOf("7"), com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerDecimalText(fixture.eval(h + "I.readMember(h, \"secret\")")));
            assertInstanceOf(
                    ProtosRawForeignValue.class, fixture.eval(h + "I.readMember(h, \"child\")"));
        }
    }

    @Test
    void writeMemberMutatesTheForeignMemberAndAnswersTheExactValueWithoutReadback()
            throws Exception {
        Fake target = new Fake(false, Capability.MEMBER_WRITE).member("x", "old");
        Fake stable = new Fake(true);
        Fake root = new Fake(false).member("target", target).member("stable", stable);
        try (ProtosForeignValueFixture fixture = fixture(root)) {
            ProtosForeignValueFixture.TestProvider provider = fixture.provider;
            String t = M + "t: m.target\n";

            // An identity-bearing raw reference comes back as that exact Protos value.
            assertSame(
                    ProtosBooleanValue.TRUE,
                    fixture.eval(t + "s: m.stable\nI.writeMember(t, \"x\", s) === s"));
            ProtosForeignArgument written = (ProtosForeignArgument) target.faithful.get("x");
            assertEquals(ProtosForeignAdmissionDescriptor.Kind.RAW, written.kind());
            assertSame(stable, written.value());
            assertSame(
                    ProtosBooleanValue.TRUE,
                    fixture.eval(t + "v: \"text\"\nI.writeMember(t, \"y\", v) === v"));
            assertEquals("text", ((ProtosForeignArgument) target.faithful.get("y")).value());
            assertEquals(0, provider.count("read:x"));
            assertEquals(0, provider.count("read:y"));

            // Ordinary assignment is never redirected to the foreign member.
            assertOrdinary(fixture, fixture.failure(t + "t.x = 1"));
            assertEquals(1, provider.count("write:x"));
            assertSame(written, target.faithful.get("x"));
        }
    }

    @Test
    void everyOperationActsOnTheUnderlyingTargetOfRawReferencesAndFacades() throws Exception {
        Fake all =
                new Fake(
                        false,
                        Capability.EXECUTABLE,
                        Capability.INSTANTIABLE,
                        Capability.MEMBER_READ,
                        Capability.MEMBER_WRITE);
        all.body = arguments -> "executed";
        all.constructor = arguments -> "constructed";
        all.member("call", "foreign call");
        try (ProtosForeignValueFixture fixture = fixture(new Fake(false).member("all", all))) {
            fixture.provider.modules.put("all", all);
            ProtosForeignModuleFacadeValue facade = fixture.facade("all");
            for (String input : List.of("m.all", "import(\"test:all\")")) {
                String a = M + "a: " + input + "\n";
                assertEquals("executed", text(fixture.eval(a + "I.invoke(a)")), input);
                assertEquals("constructed", text(fixture.eval(a + "I.instantiate(a)")), input);
                assertEquals(
                        "foreign call", text(fixture.eval(a + "I.readMember(a, \"call\")")), input);
                assertEquals(String.valueOf("3"), com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerDecimalText(fixture.eval(a + "I.writeMember(a, \"z\", 3)")), input);
            }
            assertEquals(2, fixture.provider.count("write:z"));
            // The facade keeps its Protos-facing identity and gains no slot from the write.
            assertSame(facade, fixture.facade("all"));
            assertSame(
                    ProtosBooleanValue.TRUE,
                    fixture.eval(
                            M + "f: import(\"test:all\")\nI.invoke(f)\nf === import(\"test:all\")"));
            assertTrue(facade.readLocalSlot("z").isEmpty());
        }
    }

    @Test
    void preEntryFailuresAreOrdinaryErrorsAndNeverEnterTheProvider() throws Exception {
        Fake all =
                new Fake(
                        false,
                        Capability.EXECUTABLE,
                        Capability.INSTANTIABLE,
                        Capability.MEMBER_READ,
                        Capability.MEMBER_WRITE);
        all.member("x", "v");
        Fake none = new Fake(false).member("x", "v");
        Fake root = new Fake(false).member("all", all).member("none", none);
        try (ProtosForeignValueFixture fixture = fixture(root)) {
            ProtosForeignValueFixture.TestProvider provider = fixture.provider;
            String a = M + "a: m.all\nn: m.none\n";
            List<String> rejected =
                    List.of(
                            // Not a possessed foreign value.
                            "I.invoke(1)",
                            "I.instantiate(Object {})",
                            "I.readMember(\"text\", \"x\")",
                            "I.writeMember(null, \"x\", 1)",
                            // Invalid arity.
                            "I.invoke()",
                            "I.instantiate()",
                            "I.readMember(a)",
                            "I.readMember(a, \"x\", 1)",
                            "I.writeMember(a, \"x\")",
                            "I.writeMember(a, \"x\", 1, 2)",
                            // A name that is not a String.
                            "I.readMember(a, 1)",
                            "I.readMember(a, null)",
                            "I.writeMember(a, 1, 2)",
                            // Operation family not declared by the target.
                            "I.invoke(n)",
                            "I.instantiate(n)",
                            "I.readMember(n, \"x\")",
                            "I.writeMember(n, \"x\", 1)",
                            // An outbound value that cannot be projected.
                            "I.invoke(a, Object {})",
                            "I.instantiate(a, [1])",
                            "I.writeMember(a, \"x\", Object {})");
            for (String source : rejected) {
                assertNotForeign(fixture, fixture.failure(a + source), source);
            }
            assertTrue(provider.executions.isEmpty());
            assertTrue(provider.instantiations.isEmpty());
            assertEquals(0, provider.count("read:x"));
            assertEquals(0, provider.count("write:x"));
        }
    }

    @Test
    void closedSessionFailsBeforeEntryAndNeverRebinds() throws Exception {
        Fake all =
                new Fake(
                        false,
                        Capability.EXECUTABLE,
                        Capability.INSTANTIABLE,
                        Capability.MEMBER_READ,
                        Capability.MEMBER_WRITE);
        all.member("x", "v");
        Fake root = new Fake(false, Capability.MEMBER_READ).member("all", all);
        try (ProtosForeignValueFixture fixture = fixture(root)) {
            ProtosForeignValueFixture.TestProvider provider = fixture.provider;
            ProtosRawForeignValue saved =
                    assertInstanceOf(
                            ProtosRawForeignValue.class, fixture.eval(M + "m.saved: m.all"));
            ProtosForeignProviderSessionBinding session =
                    fixture.facade("root").attachmentForRuntime().orElseThrow().session();
            int sessions = provider.sessions.size();
            session.closeForRuntime();

            for (String source :
                    List.of(
                            "I.invoke(m.saved)",
                            "I.instantiate(m.saved)",
                            "I.readMember(m.saved, \"x\")",
                            "I.writeMember(m.saved, \"x\", 1)",
                            "I.readMember(m, \"all\")")) {
                assertOrdinary(fixture, fixture.failure(M + source));
            }
            assertTrue(provider.executions.isEmpty());
            assertTrue(provider.instantiations.isEmpty());
            assertEquals(0, provider.count("read:x"));
            assertEquals(1, provider.count("read:all"));
            assertEquals(0, provider.count("write:x"));
            assertSame(session, ((ProtosForeignHandle) saved.handleForRuntime()).session());
            assertEquals(sessions, provider.sessions.size());
        }
    }

    @Test
    void enteredFailuresAreFreshForeignErrorsForEveryOperation() throws Exception {
        Fake all =
                new Fake(
                        false,
                        Capability.EXECUTABLE,
                        Capability.INSTANTIABLE,
                        Capability.MEMBER_READ,
                        Capability.MEMBER_WRITE);
        TestForeignFailure failure = new TestForeignFailure("boom");
        all.body =
                arguments -> {
                    throw failure;
                };
        all.constructor =
                arguments -> {
                    throw failure;
                };
        all.member("bad", failure);
        try (ProtosForeignValueFixture fixture = fixture(new Fake(false).member("all", all))) {
            String a = M + "a: m.all\n";
            List<List<String>> cases =
                    List.of(
                            List.of("execute", "I.invoke(a, 1)"),
                            List.of("instantiate", "I.instantiate(a, 1)"),
                            List.of("readMember", "I.readMember(a, \"bad\")"),
                            List.of("writeMember", "I.writeMember(a, \"bad\", 1)"));
            for (List<String> entry : cases) {
                ProtosObjectValue first = fixture.failure(a + entry.get(1));
                ProtosObjectValue second = fixture.failure(a + entry.get(1));
                assertSame(fixture.standardError("ForeignError"), first.parent().orElseThrow());
                assertNotSame(first, second);
                assertEquals(entry.get(0), slot(first, "operation"), entry.get(1));
                assertEquals(ProtosForeignValueFixture.LANGUAGE, slot(first, "language"));
                assertEquals("boom", slot(first, "message"));
            }
        }
    }

    @Test
    void closureArgumentsCrossOnlyThroughTheD189CallbackBridge() throws Exception {
        Fake all =
                new Fake(
                        false,
                        Capability.EXECUTABLE,
                        Capability.INSTANTIABLE,
                        Capability.MEMBER_WRITE);
        AtomicReference<Object> seen = new AtomicReference<>();
        all.body =
                arguments -> {
                    ProtosForeignArgument callback = arguments.get(0);
                    assertTrue(callback.isCallback());
                    seen.set(callback.callback().callableOrNullForRuntime());
                    ProtosForeignArgument result =
                            invoke(callback, new Integral(BigInteger.TWO));
                    return new Integral((BigInteger) ProtosForeignValueFixture.scalar(result));
                };
        all.constructor = all.body;
        try (ProtosForeignValueFixture fixture = fixture(new Fake(false).member("all", all))) {
            String a = M + "a: m.all\nn: 10\nc: (x) => {\n    n = n + x\n    n\n}\nm.keep: c\n";
            assertSame(
                    ProtosBooleanValue.TRUE,
                    fixture.eval(a + "(I.invoke(a, c) == 12).and() { I.instantiate(a, c) == 14 }"));
            // The capability denoted the exact Closure; nothing was copied or wrapped.
            assertSame(fixture.facade("root").readLocalSlot("keep").orElseThrow(), seen.get());

            // A Protos Error leaving the callback round-trips as that exact Error.
            all.body = arguments -> invoke(arguments.get(0));
            assertSame(
                    ProtosBooleanValue.TRUE,
                    fixture.eval(
                            M
                                    + "a: m.all\nboom: Error()\n"
                                    + "Error.handle(() => I.invoke(a, () => boom.signal()),"
                                    + " (caught) => caught === boom)"));

            // A callback the provider retains through writeMember expires with that operation.
            assertSame(
                    ProtosBooleanValue.TRUE,
                    fixture.eval(M + "a: m.all\nc: () => 1\nI.writeMember(a, \"cb\", c) === c"));
            ProtosForeignArgument retained = (ProtosForeignArgument) all.faithful.get("cb");
            assertTrue(retained.isCallback());
            assertThrows(ProtosForeignCallback.Rejection.class, () -> invoke(retained));
            assertNull(retained.callback().callableOrNullForRuntime());
            assertTrue(fixture.provider.leaks.isEmpty());
        }
    }

    private static final BigInteger TWO_POW_63 = BigInteger.ONE.shiftLeft(63);

    @Test
    void foreignIndicesReachTheProviderExactlyAndAreNeverTruncated() throws Exception {
        Fake array = new Fake(false, Capability.INDEXED_READ);
        array.elements.add("zero");
        Fake root = new Fake(false).member("array", array);
        try (ProtosForeignValueFixture fixture = fixture(root)) {
            ProtosForeignValueFixture.TestProvider provider = fixture.provider;
            assertEquals("zero", text(fixture.eval(M + "m.array[0]")));
            for (String index : List.of(
                    "9223372036854775807", "9223372036854775808", "0 - 1",
                    "18446744073709551616")) {
                // The fake container has one element; the index it receives is the exact one.
                fixture.failure(M + "m.array[" + index + "]");
            }
            assertEquals(1, provider.count("at:9223372036854775807"));
            assertEquals(1, provider.count("at:9223372036854775808"));
            assertEquals(1, provider.count("at:-1"));
            assertEquals(1, provider.count("at:18446744073709551616"));
            assertEquals(1, provider.count("at:0"));
        }
    }

    @Test
    void polyglotOperationsClassifyIntegralsBySourceFamilyOnly() {
        BigInteger huge = BigInteger.ONE.shiftLeft(100).negate();
        ProtosPolyglotValueOperations integral =
                new ProtosPolyglotValueOperations("test", value -> ProtosForeignScalarFamily.INTEGRAL);
        ProtosForeignValueClass large = integral.classify(null, Value.asValue(huge));
        assertEquals(ProtosForeignValueClass.Kind.INTEGER, large.kind());
        assertEquals(huge, large.scalar());
        assertEquals(Long.MIN_VALUE,
                integral.classify(null, Value.asValue(Long.MIN_VALUE)).scalar());
        assertInstanceOf(Long.class,
                integral.classify(null, Value.asValue(7L)).scalar());
        assertTrue(integral.supportsLongIntegerArguments());
        // A declared integral family must actually be integral; a Float is never converted.
        assertThrows(IllegalStateException.class,
                () -> integral.classify(null, Value.asValue(1.5d)));

        ProtosPolyglotValueOperations unspecified = new ProtosPolyglotValueOperations(
                "test", value -> ProtosForeignScalarFamily.UNSPECIFIED);
        assertEquals(ProtosForeignValueClass.Kind.HANDLE,
                unspecified.classify(null, Value.asValue(7L)).kind());
        assertEquals(ProtosForeignValueClass.Kind.HANDLE,
                unspecified.classify(null, Value.asValue(huge)).kind());

        ProtosPolyglotValueOperations floating = new ProtosPolyglotValueOperations(
                "test", value -> ProtosForeignScalarFamily.BINARY_FLOATING);
        assertEquals(ProtosForeignValueClass.Kind.BINARY64,
                floating.classify(null, Value.asValue(7L)).kind());
    }

    @Test
    void polyglotIndicesAreValidatedExactlyBeforeTheForeignArrayIsTouched() {
        List<Long> requested = new ArrayList<>();
        try (Context context = Context.create()) {
            Value array = context.asValue(new ProxyArray() {
                @Override
                public Object get(long index) {
                    requested.add(index);
                    return "element";
                }

                @Override
                public void set(long index, Value value) {
                    requested.add(index);
                }

                @Override
                public long getSize() {
                    return Long.MAX_VALUE;
                }
            });
            ProtosPolyglotValueOperations operations =
                    new ProtosPolyglotValueOperations("test", value -> ProtosForeignScalarFamily.INTEGRAL);
            operations.readElement(null, array, integerArgument(BigInteger.ZERO));
            operations.readElement(null, array, integerArgument(BigInteger.valueOf(Long.MAX_VALUE)));
            operations.readElement(null, array,
                    new ProtosForeignArgumentValue(
                            ProtosForeignValueClass.Kind.INTEGER, 0L));
            assertEquals(List.of(0L, Long.MAX_VALUE, 0L), requested);

            for (BigInteger invalid : List.of(
                    TWO_POW_63, BigInteger.ONE.negate(), BigInteger.valueOf(Long.MIN_VALUE),
                    BigInteger.ONE.shiftLeft(64))) {
                assertThrows(IllegalArgumentException.class,
                        () -> operations.readElement(null, array, integerArgument(invalid)),
                        invalid.toString());
                assertThrows(IllegalArgumentException.class,
                        () -> operations.writeElement(
                                null, array, integerArgument(invalid), integerArgument(BigInteger.ONE)),
                        invalid.toString());
            }
            assertThrows(IllegalArgumentException.class,
                    () -> operations.readElement(null, array, new ProtosForeignArgumentValue(
                            ProtosForeignValueClass.Kind.BINARY64, 0.0d)));
            assertThrows(IllegalArgumentException.class,
                    () -> operations.readElement(null, array,
                            new ProtosForeignArgumentValue(
                                    ProtosForeignValueClass.Kind.INTEGER, -1L)));
            assertEquals(List.of(0L, Long.MAX_VALUE, 0L), requested);
        }
    }

    @Test
    void polyglotArgumentsStayExactAtEveryMagnitude() {
        List<Value> received = new ArrayList<>();
        try (Context context = Context.create()) {
            Value executable = context.asValue((ProxyExecutable) arguments -> {
                received.addAll(List.of(arguments));
                return null;
            });
            ProtosPolyglotValueOperations operations =
                    new ProtosPolyglotValueOperations("test", value -> ProtosForeignScalarFamily.INTEGRAL);
            BigInteger huge = BigInteger.ONE.shiftLeft(80);
            operations.execute(null, executable, List.of(
                    integerArgument(BigInteger.valueOf(Long.MAX_VALUE)),
                    integerArgument(TWO_POW_63),
                    integerArgument(huge.negate()),
                    new ProtosForeignArgumentValue(
                            ProtosForeignValueClass.Kind.INTEGER, 7L)));
            assertTrue(received.get(0).fitsInLong());
            assertEquals(Long.MAX_VALUE, received.get(0).asLong());
            assertTrue(!received.get(1).fitsInLong() && received.get(1).fitsInBigInteger());
            assertEquals(TWO_POW_63, received.get(1).asBigInteger());
            assertEquals(huge.negate(), received.get(2).asBigInteger());
            assertEquals(7L, received.get(3).asLong());
        }
    }

    private static ProtosForeignArgumentValue integerArgument(BigInteger value) {
        return new ProtosForeignArgumentValue(ProtosForeignValueClass.Kind.INTEGER, value);
    }

    /** Provider-side callback invocation; provider failures and carriers propagate unchanged. */
    private static ProtosForeignArgument invoke(ProtosForeignArgument callback, Object... values) {
        try {
            return callback.callback().invoke(List.of(values));
        } catch (RuntimeException unchanged) {
            throw unchanged;
        } catch (Exception checked) {
            throw new IllegalStateException(checked);
        }
    }

    private static String text(Object value) {
        return ((ProtosStringValue) value).value();
    }

    private static String slot(ProtosObjectValue error, String name) {
        return text(error.readLocalSlot(name).orElseThrow());
    }

    /** Ordinary missing-member failure: SlotNotFound, never ForeignError. */
    private static void assertMissing(ProtosForeignValueFixture fixture, ProtosObjectValue error) {
        assertSame(fixture.standardError("SlotNotFound"), error.parent().orElseThrow());
    }

    /** Ordinary pre-entry failure of the foreign substrate: a Core Error, never ForeignError. */
    private static void assertOrdinary(ProtosForeignValueFixture fixture, ProtosObjectValue error) {
        assertNotForeign(fixture, error, "");
        assertTrue(error.localSlotsSnapshot().isEmpty());
    }

    /** Any ordinary Protos failure, including closure arity mismatch; never ForeignError. */
    private static void assertNotForeign(
            ProtosForeignValueFixture fixture, ProtosObjectValue error, String source) {
        assertNotSame(
                fixture.standardError("ForeignError"), error.parent().orElseThrow(), source);
    }
}
