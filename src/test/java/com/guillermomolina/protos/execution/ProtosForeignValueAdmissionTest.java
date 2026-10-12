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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.guillermomolina.protos.execution.ProtosForeignValueFixture.Alias;
import com.guillermomolina.protos.execution.ProtosForeignValueFixture.Ambiguous;
import com.guillermomolina.protos.execution.ProtosForeignValueFixture.Binary64;
import com.guillermomolina.protos.execution.ProtosForeignValueFixture.Decimal;
import com.guillermomolina.protos.execution.ProtosForeignValueFixture.Fake;
import com.guillermomolina.protos.execution.ProtosForeignValueFixture.Integral;
import com.guillermomolina.protos.execution.ProtosForeignValueFixture.Marker;
import com.guillermomolina.protos.runtime.ProtosActorValueTransfer;
import com.guillermomolina.protos.runtime.ProtosBooleanValue;
import com.guillermomolina.protos.runtime.ProtosFloatValue;
import com.guillermomolina.protos.runtime.ProtosIdentity;
import com.guillermomolina.protos.runtime.ProtosNullValue;
import com.guillermomolina.protos.runtime.ProtosObjectValue;
import com.guillermomolina.protos.runtime.ProtosRawForeignValue;
import com.guillermomolina.protos.runtime.ProtosSignalException;
import com.guillermomolina.protos.runtime.ProtosStringValue;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/** I082-D D188 primitive admission, raw identity, equality/hash, and Actor/P boundaries. */
class ProtosForeignValueAdmissionTest {
    private static final String M = "m: import(\"test:root\")\n";

    private static ProtosForeignValueFixture fixture(Fake root) throws Exception {
        ProtosForeignValueFixture fixture = new ProtosForeignValueFixture();
        fixture.provider.modules.put("root", root);
        return fixture;
    }

    @Test
    void sourceClassifiedLosslessScalarsConvertAndEverythingElseStaysRaw() throws Exception {
        BigInteger big = BigInteger.TWO.pow(100);
        Fake root =
                new Fake(false)
                        .member("bool", Boolean.TRUE)
                        .member("nil", Marker.NULL)
                        .member("undef", Marker.UNDEFINED)
                        .member("text", "héllo 😀")
                        .member("badText", "\uD800x")
                        .member("big", new Integral(big))
                        .member("dbl", new Binary64(0.1))
                        .member("dec", new Decimal(BigDecimal.ONE))
                        .member("amb", new Ambiguous(5))
                        .member("list", List.of(1, 2))
                        .member("map", Map.of("k", 1));
        try (ProtosForeignValueFixture fixture = fixture(root)) {
            assertSame(ProtosBooleanValue.TRUE, fixture.eval(M + "m.bool"));
            assertSame(ProtosNullValue.INSTANCE, fixture.eval(M + "m.nil"));
            assertEquals(
                    "héllo 😀",
                    assertInstanceOf(ProtosStringValue.class, fixture.eval(M + "m.text")).value());
            assertEquals(String.valueOf(String.valueOf(big)), com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerDecimalText(fixture.eval(M + "m.big")));
            assertEquals(
                    0.1,
                    assertInstanceOf(ProtosFloatValue.class, fixture.eval(M + "m.dbl")).value());
            for (String raw : List.of("undef", "badText", "dec", "amb", "list", "map")) {
                assertInstanceOf(ProtosRawForeignValue.class, fixture.eval(M + "m." + raw), raw);
            }
            // A converted value is an ordinary member of its family.
            assertSame(fixture.prelude.bigIntegerPrototype(), fixture.eval(M + "m.big.parent()"));
            assertSame(ProtosBooleanValue.TRUE, fixture.eval(M + "m.text == \"héllo 😀\""));
        }
    }

    @Test
    void stableForeignIdentityUnifiesWrappersAndUnstableAdmissionsStayDistinct() throws Exception {
        Fake stable = new Fake(true);
        Fake unstable = new Fake(false);
        Fake root =
                new Fake(false)
                        .member("stable", (Supplier<Object>) () -> new Alias(stable))
                        .member("unstable", (Supplier<Object>) () -> new Alias(unstable));
        try (ProtosForeignValueFixture fixture = fixture(root)) {
            assertSame(ProtosBooleanValue.TRUE, fixture.eval(M + "m.stable === m.stable"));
            assertSame(ProtosBooleanValue.FALSE, fixture.eval(M + "m.unstable === m.unstable"));
            assertSame(ProtosBooleanValue.TRUE, fixture.eval(M + "u: m.unstable\nv: u\nu === v"));
            assertSame(ProtosBooleanValue.TRUE, fixture.eval(M + "u: m.unstable\nu == u"));
            assertSame(ProtosBooleanValue.FALSE, fixture.eval(M + "m.unstable == m.unstable"));
            assertSame(ProtosBooleanValue.TRUE, fixture.eval(M + "m.stable == m.stable"));
            assertSame(
                    ProtosBooleanValue.TRUE,
                    fixture.eval(M + "m.stable.hash() == m.stable.hash()"));
            assertSame(
                    ProtosBooleanValue.TRUE,
                    fixture.eval(M + "u: m.unstable\nu.hash() == u.hash()"));

            Object first = fixture.eval(M + "m.stable");
            Object second = fixture.eval(M + "m.stable");
            assertNotSame(first, second);
            assertEquals(true, ProtosIdentity.identical(first, second));
            assertEquals(ProtosIdentity.identityHash(first), ProtosIdentity.identityHash(second));
        }
    }

    @Test
    void mapAndIdentityMapKeepTheirOwnKeyRulesForRawReferences() throws Exception {
        Fake stable = new Fake(true);
        Fake unstable = new Fake(false);
        Fake root =
                new Fake(false)
                        .member("stable", (Supplier<Object>) () -> new Alias(stable))
                        .member("unstable", (Supplier<Object>) () -> new Alias(unstable));
        try (ProtosForeignValueFixture fixture = fixture(root)) {
            // Host equals/hashCode of the doubles are total; none of it may be imported.
            assertEquals(String.valueOf("2"), com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerDecimalText(fixture.eval(
                                            M
                                                    + "mp: Map()\nmp[m.unstable] = 1\n"
                                                    + "mp[m.unstable] = 2\nmp.size()")));
            assertSame(
                    ProtosBooleanValue.TRUE,
                    fixture.eval(M + "mp: Map()\nmp[m.stable] = 1\nmp.containsKey(m.stable)"));
            assertSame(
                    ProtosBooleanValue.TRUE,
                    fixture.eval(
                            M + "im: IdentityMap()\nim[m.stable] = 1\nim.containsKey(m.stable)"));
            assertSame(
                    ProtosBooleanValue.FALSE,
                    fixture.eval(
                            M
                                    + "im: IdentityMap()\nim[m.unstable] = 1\n"
                                    + "im.containsKey(m.unstable)"));
        }
    }

    @Test
    void rawReferencesAndAttachedFacadesHaveNoActorOrPTransferButScalarsDo() throws Exception {
        Fake root =
                new Fake(false)
                        .member("plain", new Fake(false))
                        .member("fn", new Fake(false, ProtosForeignAdmissionDescriptor.Capability.EXECUTABLE))
                        .member("big", new Integral(BigInteger.TEN));
        try (ProtosForeignValueFixture fixture = fixture(root)) {
            Object raw = fixture.eval(M + "m.plain");
            Object facade = fixture.eval(M + "m");
            Object projectedCall = fixture.eval(M + "f: m.fn\nf.call");
            Object scalar = fixture.eval(M + "m.big");
            ProtosObjectValue holder = new ProtosObjectValue(ProtosObjectValue.rootObject());
            holder.createLocalSlot("inner", raw);

            for (Object value : List.of(raw, facade, holder)) {
                ProtosSignalException failure =
                        assertThrows(
                                ProtosSignalException.class,
                                () ->
                                        ProtosActorValueTransfer.snapshotValue(
                                                value, fixture.activation()));
                assertSame(
                        fixture.standardError("NonTransferableValue"),
                        failure.error().parent().orElseThrow());
            }
            assertEquals(String.valueOf("10"), com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerDecimalText(ProtosActorValueTransfer.snapshotValue(
                                            scalar, fixture.activation())));

            for (Object value : List.of(raw, facade, projectedCall, holder)) {
                assertEquals("NonParallel", parallelCopyFailure(value, fixture));
            }
            assertEquals(String.valueOf("10"), com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerDecimalText(parallelCopy(scalar, fixture)));
            // The facade still owns its foreign state after the rejected transfers.
            assertSame(
                    root,
                    ((ProtosForeignModuleFacadeValue) facade)
                            .attachmentForRuntime()
                            .orElseThrow()
                            .target());
        }
    }

    @Test
    void hostIntegralDescriptorsNormalizeToLongOrBeyondRangeBigInteger() {
        BigInteger beyond = BigInteger.ONE.shiftLeft(63);
        Object[][] cases = {
            {Byte.valueOf((byte) -128), -128L},
            {Short.valueOf((short) 32767), 32767L},
            {Integer.valueOf(Integer.MIN_VALUE), (long) Integer.MIN_VALUE},
            {Long.valueOf(Long.MAX_VALUE), Long.MAX_VALUE},
            {BigInteger.valueOf(Long.MIN_VALUE), Long.MIN_VALUE},
            {BigInteger.valueOf(42L), 42L},
            {beyond, beyond},
            {beyond.negate().subtract(BigInteger.ONE), beyond.negate().subtract(BigInteger.ONE)},
        };
        for (Object[] host : cases) {
            ProtosForeignAdmissionDescriptor descriptor =
                    ProtosForeignAdmissionDescriptor.integral((Number) host[0]);
            assertSame(ProtosForeignAdmissionDescriptor.Kind.INTEGER, descriptor.kind());
            assertEquals(host[1], descriptor.scalar(), String.valueOf(host[0]));
            assertEquals(host[1].getClass(), descriptor.scalar().getClass());
        }
        // A beyond-range host BigInteger is carried as-is: no host -> guest -> host round trip.
        assertSame(beyond, ProtosForeignAdmissionDescriptor.integral(beyond).scalar());

        // Only normalized scalars are valid INTEGER descriptors.
        for (Object invalid : new Object[] {
                BigInteger.ONE, Integer.valueOf(1), 1.0d, "1", null,
                new com.guillermomolina.protos.runtime.ProtosIntegerValue(1L)}) {
            assertThrows(IllegalArgumentException.class,
                    () -> new ProtosForeignAdmissionDescriptor(
                            ProtosForeignAdmissionDescriptor.Kind.INTEGER, invalid, null,
                            java.util.Set.of()),
                    String.valueOf(invalid));
        }
        for (Number notIntegral : new Number[] {1.0d, 1.0f, BigDecimal.ONE}) {
            assertThrows(IllegalArgumentException.class,
                    () -> ProtosForeignAdmissionDescriptor.integral(notIntegral));
        }
        // An opaque value never carries a scalar that could become an Integer.
        assertThrows(IllegalArgumentException.class,
                () -> new ProtosForeignAdmissionDescriptor(
                        ProtosForeignAdmissionDescriptor.Kind.RAW, 1L, null, java.util.Set.of()));
        assertEquals(null, ProtosForeignAdmissionDescriptor.opaque().scalar());
    }

    @Test
    void admittedIntegersMintInTheAdmittingDomainAndRematerializeAcrossActorAndP()
            throws Exception {
        BigInteger huge = BigInteger.ONE.shiftLeft(64).negate();
        Fake root =
                new Fake(false)
                        .member("smallBig", new Integral(BigInteger.valueOf(-7L)))
                        .member("edge", new Integral(BigInteger.valueOf(Long.MIN_VALUE)))
                        .member("huge", new Integral(huge));
        try (ProtosForeignValueFixture fixture = fixture(root)) {
            assertEquals(-7L, assertInstanceOf(
                    com.guillermomolina.protos.runtime.ProtosIntegerValue.class,
                    fixture.eval(M + "m.smallBig")).longValue());
            assertEquals(Long.MIN_VALUE, assertInstanceOf(
                    com.guillermomolina.protos.runtime.ProtosIntegerValue.class,
                    fixture.eval(M + "m.edge")).longValue());
            Object large = fixture.eval(M + "m.huge");
            assertInstanceOf(com.guillermomolina.protos.runtime.ProtosLargeIntegerValue.class, large);
            assertSame(fixture.prelude.bigIntegerPrototype(),
                    ((ProtosObjectValue) large).parent().orElseThrow());
            assertEquals(String.valueOf(String.valueOf(huge)), com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerDecimalText(large));

            Object actorCopy = ProtosActorValueTransfer.snapshotValue(large, fixture.activation());
            assertNotSame(large, actorCopy);
            assertEquals(String.valueOf(String.valueOf(huge)), com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerDecimalText(actorCopy));
            assertSame(Boolean.TRUE, ProtosIdentity.identical(large, actorCopy));
            Object parallel = parallelCopy(large, fixture);
            assertNotSame(large, parallel);
            assertEquals(String.valueOf(String.valueOf(huge)), com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerDecimalText(parallel));
        }
    }

    private static Object parallelCopy(Object value, ProtosForeignValueFixture fixture)
            throws Exception {
        Class<?> transfer =
                Class.forName("com.guillermomolina.protos.execution.ProtosParallelRuntime$Transfer");
        Method copy =
                transfer.getDeclaredMethod(
                        "copy",
                        Object.class,
                        com.guillermomolina.protos.runtime.ProtosActivation.class,
                        IdentityHashMap.class);
        copy.setAccessible(true);
        return copy.invoke(null, value, fixture.activation(), new IdentityHashMap<Object, Object>());
    }

    private static String parallelCopyFailure(Object value, ProtosForeignValueFixture fixture) {
        InvocationTargetException failure =
                assertThrows(InvocationTargetException.class, () -> parallelCopy(value, fixture));
        return failure.getCause().getClass().getSimpleName();
    }
}
