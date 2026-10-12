/*
 * THE LICENSED WORK IS PROVIDED UNDER THE TERMS OF THE ADAPTIVE PUBLIC LICENSE
 * ("LICENSE") AS FIRST COMPLETED BY: Guillermo Adrián Molina. ANY USE, PUBLIC
 * DISPLAY, PUBLIC PERFORMANCE, REPRODUCTION OR DISTRIBUTION OF, OR PREPARATION
 * OF DERIVATIVE WORKS BASED ON, THE LICENSED WORK CONSTITUTES RECIPIENT'S
 * ACCEPTANCE OF THIS LICENSE AND ITS TERMS.
 */
package com.guillermomolina.protos.execution;

import static org.junit.jupiter.api.Assertions.*;

import com.guillermomolina.protos.runtime.*;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * I091-F4: definitive destination shells, deferred population, identity
 * bookkeeping and failure isolation in the actual P transfer stages.
 */
final class ProtosI091ParallelTransferAcceptanceTest {
    private static ProtosPrelude core() throws Exception {
        return new ProtosCoreBootstrap().bootstrap(
                Path.of("protos", "lib", "core"));
    }

    @Test
    void arrayParentChildCyclesAliasesAndMutationStateSurviveCapture()
            throws Exception {
        ProtosPrelude prelude = core();
        ProtosObjectValue parent =
                new ProtosObjectValue(ProtosObjectValue.rootObject());
        ProtosArrayValue array =
                new ProtosArrayValue(parent, List.of(ProtosNullValue.INSTANCE));
        ProtosObjectValue shared =
                new ProtosObjectValue(ProtosObjectValue.rootObject());

        array.indexedPut(0, array);
        array.createLocalSlot("shared", shared);
        shared.createLocalSlot("array", array);
        parent.createLocalSlot("child", array);
        array.freeze();
        parent.close();

        boolean[] records = new boolean[1];
        List<Object> copied = ProtosParallelRuntime.captureValuesForTesting(
                List.of(array, array, parent, shared),
                prelude.newModuleActivation(), records);

        assertFalse(records[0]);
        ProtosArrayValue result =
                assertInstanceOf(ProtosArrayValue.class, copied.get(0));
        ProtosObjectValue copiedParent =
                assertInstanceOf(ProtosObjectValue.class, copied.get(2));
        ProtosObjectValue copiedShared =
                assertInstanceOf(ProtosObjectValue.class, copied.get(3));

        assertNotSame(array, result);
        assertSame(result, copied.get(1));
        assertSame(result, result.indexedAt(0));
        assertSame(copiedParent, result.parent().orElseThrow());
        assertSame(result, copiedParent.readLocalSlot("child").orElseThrow());
        assertSame(copiedShared, result.readLocalSlot("shared").orElseThrow());
        assertSame(result, copiedShared.readLocalSlot("array").orElseThrow());
        assertTrue(result.isFrozen());
        assertTrue(copiedParent.isClosed());
        assertTrue(array.isFrozen());
        assertTrue(parent.isClosed());
    }

    @Test
    void pathDescriptorForcesDestinationPopulationOfBytesAndCyclicSlots()
            throws Exception {
        ProtosPrelude sourcePrelude = core();
        ProtosPrelude destinationPrelude = core();

        ProtosObjectValue holder =
                new ProtosObjectValue(ProtosObjectValue.rootObject());
        ProtosBytesValue bytes =
                new ProtosBytesValue(ProtosObjectValue.rootObject());
        bytes.indexedAdd(new ProtosIntegerValue(200L));
        bytes.indexedAdd(new ProtosIntegerValue(255L));
        bytes.createLocalSlot("owner", holder);
        holder.createLocalSlot("bytes", bytes);
        holder.createLocalSlot(
                "path",
                new ProtosPathValue(
                        sourcePrelude.pathPrototype(), List.of("a", "b")));
        bytes.freeze();

        boolean[] records = new boolean[1];
        List<Object> detached = ProtosParallelRuntime.captureValuesForTesting(
                List.of(holder, bytes),
                sourcePrelude.newModuleActivation(), records);

        assertTrue(records[0]);
        ProtosObjectValue detachedHolder =
                assertInstanceOf(ProtosObjectValue.class, detached.get(0));
        assertInstanceOf(
                ProtosPathComponents.class,
                detachedHolder.readLocalSlot("path").orElseThrow());

        List<Object> delivered =
                ProtosParallelRuntime.materializeValuesForTesting(
                        detached, destinationPrelude.newModuleActivation());

        ProtosObjectValue resultHolder =
                assertInstanceOf(ProtosObjectValue.class, delivered.get(0));
        ProtosBytesValue resultBytes =
                assertInstanceOf(ProtosBytesValue.class, delivered.get(1));
        ProtosPathValue resultPath =
                assertInstanceOf(
                        ProtosPathValue.class,
                        resultHolder.readLocalSlot("path").orElseThrow());

        assertNotSame(holder, resultHolder);
        assertNotSame(bytes, resultBytes);
        assertSame(
                resultBytes,
                resultHolder.readLocalSlot("bytes").orElseThrow());
        assertSame(
                resultHolder,
                resultBytes.readLocalSlot("owner").orElseThrow());
        assertTrue(resultBytes.isFrozen());
        assertEquals(
                "200",
                ProtosNumericValueSupport.integerDecimalText(
                        resultBytes.indexedAt(0)));
        assertEquals(
                "255",
                ProtosNumericValueSupport.integerDecimalText(
                        resultBytes.indexedAt(1)));
        assertSame(
                destinationPrelude.pathPrototype(),
                resultPath.representedDelegationParent(destinationPrelude));
        assertEquals(List.of("a", "b"), resultPath.components());
    }

    @Test
    void mapAndIdentityMapRebuildIdentityHashesWithoutLosingCycles()
            throws Exception {
        ProtosPrelude prelude = core();
        ProtosMapValue map = prelude.newMap();
        ProtosIdentityMapValue identityMap =
                new ProtosIdentityMapValue(prelude.identityMapPrototype());
        ProtosObjectValue key =
                new ProtosObjectValue(ProtosObjectValue.rootObject());
        ProtosStringValue stringKey = new ProtosStringValue("text");
        ProtosNumericHashKey stringHash =
                ProtosNumericHashKey.ofLong("text".hashCode());

        map.append(
                key, ProtosNumericHashKey.fromIdentity(key), identityMap);
        map.append(stringKey, stringHash, map);
        identityMap.append(
                key, ProtosNumericHashKey.fromIdentity(key), map);
        map.createLocalSlot("self", map);
        identityMap.createLocalSlot("map", map);
        key.createLocalSlot("owner", identityMap);
        map.close();

        boolean[] records = new boolean[1];
        List<Object> copied = ProtosParallelRuntime.captureValuesForTesting(
                List.of(map, identityMap, key),
                prelude.newModuleActivation(), records);

        assertFalse(records[0]);
        ProtosMapValue copiedMap =
                assertInstanceOf(ProtosMapValue.class, copied.get(0));
        ProtosIdentityMapValue copiedIdentity =
                assertInstanceOf(ProtosIdentityMapValue.class, copied.get(1));
        ProtosObjectValue copiedKey =
                assertInstanceOf(ProtosObjectValue.class, copied.get(2));

        assertNotSame(map, copiedMap);
        assertNotSame(identityMap, copiedIdentity);
        assertNotSame(key, copiedKey);
        assertTrue(copiedMap.isClosed());
        assertSame(
                copiedMap, copiedMap.readLocalSlot("self").orElseThrow());
        assertSame(
                copiedMap,
                copiedIdentity.readLocalSlot("map").orElseThrow());
        assertSame(
                copiedIdentity,
                copiedKey.readLocalSlot("owner").orElseThrow());

        List<ProtosMapValue.Entry> entries = copiedMap.keyedSnapshot();
        assertEquals(2, entries.size());
        assertSame(copiedKey, entries.get(0).key());
        assertSame(copiedIdentity, entries.get(0).value());
        assertEquals(
                ProtosNumericHashKey.fromIdentity(copiedKey),
                entries.get(0).recordedHash());
        assertSame(copiedMap, entries.get(1).value());
        assertEquals(stringHash, entries.get(1).recordedHash());

        ProtosIdentityMapValue.Entry identityEntry =
                copiedIdentity.keyedSnapshot().get(0);
        assertSame(copiedKey, identityEntry.key());
        assertSame(copiedMap, identityEntry.value());
        assertEquals(
                ProtosNumericHashKey.fromIdentity(copiedKey),
                identityEntry.recordedIdentityHash());
    }

    @Test
    void failedDeferredPopulationDoesNotMutateSourceOrPoisonNextTransfer()
            throws Exception {
        ProtosPrelude prelude = core();
        ProtosObjectValue source =
                new ProtosObjectValue(ProtosObjectValue.rootObject());
        Object forbidden = new Object();
        source.createLocalSlot("self", source);
        source.createLocalSlot("forbidden", forbidden);

        ProtosArrayValue array =
                new ProtosArrayValue(
                        ProtosObjectValue.rootObject(), List.of(source));
        source.createLocalSlot("array", array);

        assertThrows(
                RuntimeException.class,
                () -> ProtosParallelRuntime.captureValuesForTesting(
                        List.of(array), prelude.newModuleActivation(),
                        new boolean[1]));

        assertSame(source, source.readLocalSlot("self").orElseThrow());
        assertSame(forbidden, source.readLocalSlot("forbidden").orElseThrow());
        assertSame(array, source.readLocalSlot("array").orElseThrow());
        assertSame(source, array.indexedAt(0));

        source.removeLocalSlot("forbidden");
        boolean[] records = new boolean[1];
        ProtosArrayValue recovered =
                assertInstanceOf(
                        ProtosArrayValue.class,
                        ProtosParallelRuntime.captureValuesForTesting(
                                List.of(array), prelude.newModuleActivation(),
                                records).get(0));
        ProtosObjectValue recoveredSource =
                assertInstanceOf(ProtosObjectValue.class, recovered.indexedAt(0));

        assertFalse(records[0]);
        assertNotSame(array, recovered);
        assertNotSame(source, recoveredSource);
        assertSame(
                recoveredSource,
                recoveredSource.readLocalSlot("self").orElseThrow());
        assertSame(
                recovered,
                recoveredSource.readLocalSlot("array").orElseThrow());
        assertFalse(source.hasLocalSlot("forbidden"));
    }
}
