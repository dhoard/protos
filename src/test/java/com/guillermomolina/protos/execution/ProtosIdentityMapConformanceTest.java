/* APL-1.0 licensed work; see LICENSE.TXT. */
package com.guillermomolina.protos.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.guillermomolina.protos.runtime.ProtosActivation;
import com.guillermomolina.protos.runtime.ProtosActorValueTransfer;
import com.guillermomolina.protos.runtime.ProtosBooleanValue;
import com.guillermomolina.protos.runtime.ProtosFloatValue;
import com.guillermomolina.protos.runtime.ProtosIdentity;
import com.guillermomolina.protos.runtime.ProtosIdentityMapValue;
import com.guillermomolina.protos.runtime.ProtosIntegerValue;
import com.guillermomolina.protos.runtime.ProtosNumericHashKey;
import com.guillermomolina.protos.runtime.ProtosPrelude;
import com.guillermomolina.protos.runtime.ProtosStringValue;

import java.io.IOException;
import java.math.BigInteger;
import java.util.List;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ProtosIdentityMapConformanceTest {
    // Deliberately Java-side: this checks implementation representation and the
    // exact bootstrap binding, not ordinary source-visible IdentityMap behavior.
    @Test
    void factoryMaterializesRepresentedIdentityMapAndPreludeBinding() throws IOException {
        ProtosPrelude prelude = core();
        Object value = exec(prelude, "IdentityMap()");

        assertInstanceOf(ProtosIdentityMapValue.class, value);
        assertSame(
                prelude.identityMapPrototype(),
                prelude.bindings().readLocalSlot("IdentityMap").orElseThrow());
    }

    // Deliberately Java-side: ProtosIdentity is the primitive representation
    // helper consumed by IdentityMap; testing its raw NaN/signed-zero
    // behavior is an implementation contract rather than a message-level test.
    @Test
    void identityHashesAreCoherent() {
        var nan1 = new ProtosFloatValue(Double.longBitsToDouble(0x7ff8000000000001L));
        var nan2 = new ProtosFloatValue(Double.longBitsToDouble(0x7ff8000000000002L));
        assertTrue(ProtosIdentity.identical(nan1, nan2));
        assertEquals(ProtosIdentity.identityHash(nan1), ProtosIdentity.identityHash(nan2));

        assertFalse(
                ProtosIdentity.identical(
                        new ProtosFloatValue(0.0), new ProtosFloatValue(-0.0)));
    }

    // I091: numeric IdentityMap keys are found by value identity, never by Java wrapper identity.
    @Test
    void largeIntegerKeysAreFoundByValueAndDistinctMagnitudesStayApart() throws IOException {
        ProtosPrelude prelude = core();
        ProtosActivation a = prelude.newModuleActivation();
        ProtosIdentityMapValue map = new ProtosIdentityMapValue(prelude.identityMapPrototype());
        BigInteger huge = BigInteger.ONE.shiftLeft(1000).add(BigInteger.ONE);
        Object stored = com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerFromHost(huge, prelude);
        Object equal = com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerFromHost(new BigInteger(huge.toString()), prelude);

        put(map, stored, "huge", a);
        put(map, equal, "replaced", a);

        assertNotSame(stored, equal);
        assertEquals(1, map.keyedSnapshot().size());
        assertSame(stored, map.keyedSnapshot().get(0).key());
        assertEquals("replaced", at(map, com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerFromHost(huge, prelude), a));
        assertEquals(
                ProtosNumericHashKey.fromIdentity(equal),
                map.keyedSnapshot().get(0).recordedIdentityHash());
        assertSame(
                ProtosBooleanValue.FALSE,
                ProtosInvocation.invokeMessage(
                        map,
                        "containsKey",
                        List.of(com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerFromHost(huge.add(BigInteger.ONE), prelude)),
                        a));
        assertSame(
                ProtosBooleanValue.FALSE,
                ProtosInvocation.invokeMessage(
                        map, "containsKey", List.of(com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerFromHost(huge.negate(), prelude)), a));
    }

    @Test
    void identityHashCollisionsBetweenLargeIntegersAreResolvedByIdentity() throws IOException {
        ProtosPrelude prelude = core();
        ProtosActivation a = prelude.newModuleActivation();
        ProtosIdentityMapValue map = new ProtosIdentityMapValue(prelude.identityMapPrototype());
        // Magnitudes [1, 0, 31] and [1, 1, 0] share the exact hash 31 * (31 + 0) + 31 = 992.
        BigInteger first = BigInteger.ONE.shiftLeft(64).add(BigInteger.valueOf(31));
        BigInteger second = BigInteger.ONE.shiftLeft(64).add(BigInteger.ONE.shiftLeft(32));
        Object firstKey = com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerFromHost(first, prelude);
        Object secondKey = com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerFromHost(second, prelude);
        assertEquals(first.hashCode(), second.hashCode());
        assertEquals(ProtosIdentity.identityHash(firstKey), ProtosIdentity.identityHash(secondKey));

        put(map, firstKey, "first", a);
        put(map, secondKey, "second", a);

        assertEquals(2, map.keyedSnapshot().size());
        assertEquals("first", at(map, com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerFromHost(first, prelude), a));
        assertEquals("second", at(map, com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerFromHost(second, prelude), a));
    }

    @Test
    void floatKeysKeepNanSignedZeroAndIntegerFamilyDistinctions() throws IOException {
        ProtosPrelude prelude = core();
        ProtosActivation a = prelude.newModuleActivation();
        ProtosIdentityMapValue map = new ProtosIdentityMapValue(prelude.identityMapPrototype());

        put(map, new ProtosFloatValue(Double.longBitsToDouble(0x7ff8000000000001L)), "nan", a);
        put(map, new ProtosFloatValue(0.0d), "positiveZero", a);
        put(map, new ProtosFloatValue(-0.0d), "negativeZero", a);
        put(map, new ProtosIntegerValue(1), "integer", a);
        put(map, new ProtosFloatValue(1.0d), "float", a);
        put(map, com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerFromHost(BigInteger.ONE.shiftLeft(64), prelude), "large", a);
        put(map, new ProtosFloatValue(0x1p64), "largeFloat", a);

        assertEquals(7, map.keyedSnapshot().size());
        assertEquals(
                "nan",
                at(map, new ProtosFloatValue(Double.longBitsToDouble(0x7ff8000000000123L)), a));
        assertEquals("positiveZero", at(map, new ProtosFloatValue(0.0d), a));
        assertEquals("negativeZero", at(map, new ProtosFloatValue(-0.0d), a));
        assertEquals("integer", at(map, new ProtosIntegerValue(1), a));
        assertEquals("float", at(map, new ProtosFloatValue(1.0d), a));
        assertEquals(
                "large", at(map, com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerFromHost(BigInteger.ONE.shiftLeft(64), prelude), a));
        assertEquals("largeFloat", at(map, new ProtosFloatValue(0x1p64), a));
    }

    @Test
    void transferredLargeIntegerKeysKeepCoherentRecordedIdentityHashes() throws IOException {
        ProtosPrelude prelude = core();
        ProtosActivation a = prelude.newModuleActivation();
        ProtosIdentityMapValue map = new ProtosIdentityMapValue(prelude.identityMapPrototype());
        BigInteger negative = BigInteger.ONE.shiftLeft(64).negate();
        put(map, com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerFromHost(negative, prelude), "negative", a);
        put(map, new ProtosFloatValue(Double.NaN), "nan", a);

        ProtosIdentityMapValue copied =
                assertInstanceOf(
                        ProtosIdentityMapValue.class,
                        ProtosActorValueTransfer.snapshotValue(map, a));

        for (ProtosIdentityMapValue.Entry entry : copied.keyedSnapshot()) {
            assertEquals(
                    ProtosNumericHashKey.fromIdentity(entry.key()), entry.recordedIdentityHash());
        }
        assertEquals("negative", at(copied, com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerFromHost(negative, prelude), a));
        assertEquals(
                "nan",
                at(copied, new ProtosFloatValue(Double.longBitsToDouble(0x7ff8000000000077L)), a));
    }

    private static void put(
            ProtosIdentityMapValue map, Object key, String value, ProtosActivation a) {
        ProtosInvocation.invokeMessage(map, "atPut", List.of(key, new ProtosStringValue(value)), a);
    }

    private static String at(ProtosIdentityMapValue map, Object key, ProtosActivation a) {
        return assertInstanceOf(
                        ProtosStringValue.class,
                        ProtosInvocation.invokeMessage(map, "at", List.of(key), a))
                .value();
    }

    private static Object exec(ProtosPrelude prelude, String source) {
        return ProtosTestExecutionSupport.evaluate(
                source,
                prelude.newModuleActivation());
    }

    private static ProtosPrelude core() throws IOException {
        return new ProtosCoreBootstrap().bootstrap(Path.of("protos", "lib", "core"));
    }
}
