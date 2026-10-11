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
import java.lang.reflect.Modifier;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * PLAT051-A/A2: generic privileged Standard Library semantic-value transfer through Actor and P,
 * exercised with test-only fixture families that no guest code can reach.
 *
 * <p>A2 splits transfer into a synchronous source stage (validate and extract into an internal
 * record) and a destination stage (materialize inside the destination domain). These tests drive
 * each stage directly; {@code ProtosSemanticTransferMaterializationTest} covers the hosted Actor/P
 * boundaries with a guest-implemented family.
 */
final class ProtosSemanticTransferFamilyTest {
    private static final Path CORE = Path.of("protos", "lib", "core");
    private static final Path MAIN = Path.of("src/main/java/com/guillermomolina/protos");

    private static final FixtureFamily PORTABLE =
            new FixtureFamily("std:plat051-fixture/Portable", Mode.PORTABLE);
    private static final FixtureFamily NULL_PAYLOAD =
            new FixtureFamily("std:plat051-fixture/NullPayload", Mode.NULL_PAYLOAD);
    private static final FixtureFamily MALFORMED_PAYLOAD =
            new FixtureFamily("std:plat051-fixture/MalformedPayload", Mode.MALFORMED_PAYLOAD);
    private static final FixtureFamily CLOSURE_PAYLOAD =
            new FixtureFamily("std:plat051-fixture/ClosurePayload", Mode.CLOSURE_PAYLOAD);
    private static final FixtureFamily THROWING =
            new FixtureFamily("std:plat051-fixture/Throwing", Mode.THROWING);
    private static final FixtureFamily OPEN_RESULT =
            new FixtureFamily("std:plat051-fixture/OpenResult", Mode.OPEN_RESULT);
    private static final FixtureFamily FOREIGN_RESULT =
            new FixtureFamily("std:plat051-fixture/ForeignResult", Mode.FOREIGN_RESULT);
    /** Same owner key as PORTABLE but never authorized by any bootstrap. */
    private static final FixtureFamily IMPOSTOR =
            new FixtureFamily("std:plat051-fixture/Portable", Mode.PORTABLE);

    private static final NumericCandidateFamily NUMERIC_CANDIDATE =
            new NumericCandidateFamily();

    private static ProtosPrelude prelude;

    @BeforeAll
    static void bootstrap() throws Exception {
        prelude =
                ProtosCoreBootstrap.withSemanticTransferFamiliesForTesting(
                                List.of(
                                        PORTABLE,
                                        NULL_PAYLOAD,
                                        MALFORMED_PAYLOAD,
                                        CLOSURE_PAYLOAD,
                                        THROWING,
                                        OPEN_RESULT,
                                        FOREIGN_RESULT,
                                        NUMERIC_CANDIDATE))
                        .bootstrap(CORE);
    }

    @Test
    void actorSourceStageExtractsSynchronouslyIntoInternalRecordsWithoutMaterializing() {
        ProtosSemanticTransferValue shared = PORTABLE.mint("a+b");
        ProtosSemanticTransferValue twin = PORTABLE.mint("a+b");
        ProtosObjectValue graph = surroundingGraph(shared, twin);
        int extracted = PORTABLE.extracted;
        int materialized = PORTABLE.materialized;

        List<Object> snapshot =
                ProtosActorValueTransfer.snapshotArguments(
                        List.of(shared, graph), prelude.newModuleActivation());

        // One extraction per distinct source identity, completed before snapshotArguments returns.
        assertEquals(extracted + 2, PORTABLE.extracted);
        assertEquals(materialized, PORTABLE.materialized, "the source stage must not materialize");
        assertTrue(ProtosActorValueTransfer.requiresMaterialization(snapshot));
        for (Object element : snapshot) {
            assertFalse(element instanceof ProtosObjectValue, "no guest value or OPEN shell in transit");
        }
        Object single = ProtosActorValueTransfer.snapshotValue(shared, prelude.newModuleActivation());
        assertFalse(single instanceof ProtosObjectValue);
        assertEquals(materialized, PORTABLE.materialized);
    }

    @Test
    void actorDestinationStageMaterializesOncePerRecordWithAliasesDistinctIdentitiesAndCycles() {
        ProtosSemanticTransferValue shared = PORTABLE.mint("a+b");
        ProtosSemanticTransferValue twin = PORTABLE.mint("a+b");
        ProtosObjectValue graph = surroundingGraph(shared, twin);
        List<Object> snapshot =
                ProtosActorValueTransfer.snapshotArguments(
                        List.of(shared, graph), prelude.newModuleActivation());
        ProtosActivation destination = prelude.newModuleActivation();
        int materialized = PORTABLE.materialized;

        List<?> delivered = ProtosActorValueTransfer.materializeArguments(snapshot, destination);

        assertEquals(materialized + 2, PORTABLE.materialized);
        assertSame(destination.executionDomain(), PORTABLE.lastDestinationDomain);
        assertRematerializedGraph(shared, twin, graph, delivered.get(0), delivered.get(1));
    }

    @Test
    void oneRecordMaterializesIndependentlyPerRecipient() {
        ProtosSemanticTransferValue shared = PORTABLE.mint("a+b");
        ProtosSemanticTransferValue twin = PORTABLE.mint("a+b");
        ProtosObjectValue graph = surroundingGraph(shared, twin);
        List<Object> snapshot =
                ProtosActorValueTransfer.snapshotArguments(
                        List.of(shared, graph), prelude.newModuleActivation());
        int extracted = PORTABLE.extracted;

        List<?> first =
                ProtosActorValueTransfer.materializeArguments(snapshot, prelude.newModuleActivation());
        List<?> second =
                ProtosActorValueTransfer.materializeArguments(snapshot, prelude.newModuleActivation());

        assertEquals(extracted, PORTABLE.extracted, "the inert record is reused, never re-extracted");
        assertRematerializedGraph(shared, twin, graph, first.get(0), first.get(1));
        assertRematerializedGraph(shared, twin, graph, second.get(0), second.get(1));
        assertNotSame(first.get(0), second.get(0));
        assertNotSame(first.get(1), second.get(1));
    }

    @Test
    void pSourceAndDestinationStagesAreSeparateWithAliasesDistinctIdentitiesAndCycles() {
        ProtosSemanticTransferValue shared = PORTABLE.mint("a+b");
        ProtosSemanticTransferValue twin = PORTABLE.mint("a+b");
        ProtosObjectValue graph = surroundingGraph(shared, twin);
        boolean[] records = new boolean[1];
        int materialized = PORTABLE.materialized;

        List<Object> detached =
                ProtosParallelRuntime.captureValuesForTesting(
                        List.of(shared, graph), prelude.newModuleActivation(), records);

        assertTrue(records[0]);
        assertFalse(detached.get(0) instanceof ProtosObjectValue);
        assertEquals(materialized, PORTABLE.materialized, "the P caller stage must not materialize");

        ProtosActivation worker = prelude.newModuleActivation();
        List<Object> delivered = ProtosParallelRuntime.materializeValuesForTesting(detached, worker);

        assertEquals(materialized + 2, PORTABLE.materialized);
        assertSame(worker.executionDomain(), PORTABLE.lastDestinationDomain);
        assertRematerializedGraph(shared, twin, graph, delivered.get(0), delivered.get(1));
    }

    @Test
    void semanticMapKeyKeepsAConsistentRecordedHashAfterMaterialization() {
        ProtosSemanticTransferValue key = PORTABLE.mint("k");
        ProtosMapValue map = prelude.newMap();
        map.append(key, ProtosNumericHashKey.fromIdentity(key), new ProtosIntegerValue(1L));

        List<?> delivered =
                ProtosActorValueTransfer.materializeArguments(
                        ProtosActorValueTransfer.snapshotArguments(
                                List.of(map), prelude.newModuleActivation()),
                        prelude.newModuleActivation());

        ProtosMapValue.Entry entry =
                assertInstanceOf(ProtosMapValue.class, delivered.get(0)).keyedSnapshot().get(0);
        ProtosSemanticTransferValue copiedKey =
                assertInstanceOf(ProtosSemanticTransferValue.class, entry.key());
        assertNotSame(key, copiedKey);
        assertEquals(ProtosNumericHashKey.fromIdentity(copiedKey), entry.recordedHash());
    }

    /*
     * Root-parented graph only: materialization into a distinct Prelude shares only that
     * Prelude's standard objects, so a source-Prelude Map/Array prototype is not portable there.
     */
    @Test
    void largeIntegersInADetachedSnapshotRematerializeInTheDestinationPrelude() throws Exception {
        ProtosPrelude destinationPrelude =
                ProtosCoreBootstrap.withSemanticTransferFamiliesForTesting(List.of(PORTABLE))
                        .bootstrap(CORE);
        BigInteger huge = BigInteger.ONE.shiftLeft(1000).add(BigInteger.ONE);
        Object shared = ProtosTestIntegers.integer(huge, prelude);
        Object twin = ProtosTestIntegers.integer(huge, prelude);
        Object negative = ProtosTestIntegers.integer(BigInteger.ONE.shiftLeft(64).negate(), prelude);
        ProtosObjectValue graph = surroundingGraph(PORTABLE.mint("a+b"), PORTABLE.mint("a+b"));
        graph.createLocalSlot("large", shared);
        graph.createLocalSlot("largeAgain", shared);
        graph.createLocalSlot("largeTwin", twin);
        graph.createLocalSlot("negative", negative);

        List<Object> snapshot =
                ProtosActorValueTransfer.snapshotArguments(
                        List.of(graph, shared), prelude.newModuleActivation());
        assertTrue(ProtosActorValueTransfer.requiresMaterialization(snapshot));
        List<?> delivered =
                ProtosActorValueTransfer.materializeArguments(
                        snapshot, destinationPrelude.newModuleActivation());

        ProtosObjectValue copiedGraph = assertInstanceOf(ProtosObjectValue.class, delivered.get(0));
        Object copiedLarge = copiedGraph.readLocalSlot("large").orElseThrow();
        Object copiedTwin = copiedGraph.readLocalSlot("largeTwin").orElseThrow();
        Object copiedNegative = copiedGraph.readLocalSlot("negative").orElseThrow();
        for (Object copied : List.of(copiedLarge, copiedTwin, copiedNegative)) {
            ProtosObjectValue large = assertInstanceOf(ProtosObjectValue.class, copied);
            assertTrue(ProtosNumericValueSupport.isLargeInteger(large));
            assertTrue(large.isFrozen());
            assertSame(destinationPrelude.integerPrototype(), large.parent().orElseThrow());
        }
        assertEquals(huge, ProtosTestIntegers.exact(copiedLarge));
        assertEquals(BigInteger.ONE.shiftLeft(64).negate(), ProtosTestIntegers.exact(copiedNegative));
        assertSame(copiedLarge, delivered.get(1));
        assertSame(copiedLarge, copiedGraph.readLocalSlot("largeAgain").orElseThrow());
        assertNotSame(copiedLarge, copiedTwin);
        assertTrue(ProtosIdentity.identical(copiedLarge, copiedTwin));
        assertEquals(ProtosIdentity.identityHash(shared), ProtosIdentity.identityHash(copiedTwin));
    }

    @Test
    void ordinaryObjectCannotForgeFamilyThroughNamesSlotsShapeOrDelegation() {
        ProtosSemanticTransferValue genuine = PORTABLE.mint("x");
        ProtosObjectValue forged = new ProtosObjectValue(ProtosObjectValue.rootObject());
        forged.createLocalSlot("text", new ProtosStringValue("x"));
        forged.createLocalSlot("ownerModule", new ProtosStringValue("std:plat051-fixture/Portable"));
        forged.createLocalSlot("family", new ProtosStringValue("ProtosSemanticTransferFamily"));
        forged.freeze();
        ProtosObjectValue child = new ProtosObjectValue(genuine);
        child.freeze();
        int extracted = PORTABLE.extracted;

        List<Object> actorForged =
                ProtosActorValueTransfer.snapshotArguments(List.of(forged), prelude.newModuleActivation());
        boolean[] records = new boolean[1];
        List<Object> pForged =
                ProtosParallelRuntime.captureValuesForTesting(
                        List.of(forged), prelude.newModuleActivation(), records);
        assertFalse(ProtosActorValueTransfer.requiresMaterialization(actorForged));
        assertFalse(records[0]);
        assertFalse(actorForged.get(0) instanceof ProtosSemanticTransferValue);
        assertFalse(pForged.get(0) instanceof ProtosSemanticTransferValue);
        assertEquals(extracted, PORTABLE.extracted);

        // Delegating to a genuine value confers nothing: the child stays ordinary while its
        // parent edge reaches a materialized destination value of the genuine family.
        ProtosObjectValue actorChild =
                assertInstanceOf(
                        ProtosObjectValue.class,
                        ProtosActorValueTransfer.materializeValue(
                                ProtosActorValueTransfer.snapshotValue(child, prelude.newModuleActivation()),
                                prelude.newModuleActivation()));
        assertFalse(actorChild instanceof ProtosSemanticTransferValue);
        assertNotSame(genuine, actorChild.parent().orElseThrow());
        assertSame(
                PORTABLE,
                assertInstanceOf(ProtosSemanticTransferValue.class, actorChild.parent().orElseThrow())
                        .family());
    }

    @Test
    void unauthorizedDescriptorAndMissingAuthorityFailSynchronouslyBeforeExtraction() throws Exception {
        ProtosSemanticTransferValue impostor = IMPOSTOR.mint("x");
        assertNonTransferable(impostor, prelude);
        assertNonParallel(impostor, prelude);
        assertEquals(0, IMPOSTOR.extracted);

        ProtosPrelude withoutFamilies = new ProtosCoreBootstrap().bootstrap(CORE);
        ProtosSemanticTransferValue genuine = PORTABLE.mint("x");
        int extracted = PORTABLE.extracted;
        assertNonTransferable(genuine, withoutFamilies);
        assertNonParallel(genuine, withoutFamilies);
        assertEquals(extracted, PORTABLE.extracted);
    }

    @Test
    void malformedSourceValuesFailSynchronouslyInTheSourceStage() {
        for (FixtureFamily family : List.of(NULL_PAYLOAD, MALFORMED_PAYLOAD, CLOSURE_PAYLOAD, THROWING)) {
            ProtosSemanticTransferValue value = family.mint("x");
            assertNonTransferable(value, prelude);
            assertNonParallel(value, prelude);
            assertEquals(0, family.materialized, family.ownerModule().canonicalId());
        }
        ProtosSemanticTransferValue open = PORTABLE.mintOpen("x");
        assertNonTransferable(open, prelude);
        assertNonParallel(open, prelude);

        assertThrows(
                IllegalArgumentException.class,
                () -> ProtosSemanticTransferPayload.of(new ProtosStringValue("protos value")));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        ProtosSemanticTransferPayload.of(
                                ProtosClosureValue.nativeClosure((a, args) -> ProtosNullValue.INSTANCE)));
        assertThrows(
                IllegalArgumentException.class,
                () -> new FixtureFamily("user:plat051-fixture/NotStandard", Mode.PORTABLE));
    }

    @Test
    void destinationFailureOfAValidatedRecordIsAnInternalInconsistencyNotAGuestError() {
        for (FixtureFamily family : List.of(OPEN_RESULT, FOREIGN_RESULT)) {
            ProtosSemanticTransferValue value = family.mint("x");
            List<Object> actorSnapshot =
                    ProtosActorValueTransfer.snapshotArguments(List.of(value), prelude.newModuleActivation());
            boolean[] records = new boolean[1];
            List<Object> pSnapshot =
                    ProtosParallelRuntime.captureValuesForTesting(
                            List.of(value), prelude.newModuleActivation(), records);

            IllegalStateException actorFailure =
                    assertThrows(
                            IllegalStateException.class,
                            () ->
                                    ProtosActorValueTransfer.materializeArguments(
                                            actorSnapshot, prelude.newModuleActivation()));
            IllegalStateException pFailure =
                    assertThrows(
                            IllegalStateException.class,
                            () ->
                                    ProtosParallelRuntime.materializeValuesForTesting(
                                            pSnapshot, prelude.newModuleActivation()));
            assertTrue(actorFailure.getMessage().contains("internal inconsistency"));
            assertTrue(pFailure.getMessage().contains("internal inconsistency"));
        }
    }

    @Test
    void closureExecutionAndResourceRulesAreUnchanged() {
        ProtosActivation activation = prelude.newModuleActivation();
        ProtosClosureValue closure = ProtosClosureValue.nativeClosure((a, args) -> ProtosNullValue.INSTANCE);
        ProtosObjectValue holder = new ProtosObjectValue(ProtosObjectValue.rootObject());
        holder.createLocalSlot("callback", closure);

        assertNonTransferable(closure, prelude);
        assertNonTransferable(holder, prelude);
        assertNonTransferable(activation, prelude);
        assertNonTransferable(prelude.newExecutionContext(), prelude);
        assertNonParallel(prelude.newExecutionContext(), prelude);

        // P keeps its existing projection rule for Closures; it does not become rematerialization.
        boolean[] records = new boolean[1];
        Object projected =
                ProtosParallelRuntime.captureValuesForTesting(List.of(closure), activation, records).get(0);
        assertInstanceOf(ProtosClosureValue.class, projected);
        assertNotSame(closure, projected);
        assertFalse(records[0]);
    }

    @Test
    void ordinaryGraphTransferNeitherConsultsFamiliesNorImportsNorPaysMaterialization() {
        ProtosObjectValue node = new ProtosObjectValue(ProtosObjectValue.rootObject());
        ProtosArrayValue array = prelude.newArray(List.of(node, node, new ProtosIntegerValue(2L)));
        node.createLocalSlot("self", node);
        node.createLocalSlot("items", array);
        ProtosActivation activation = prelude.newModuleActivation();
        int extracted = PORTABLE.extracted;
        int materialized = PORTABLE.materialized;

        List<Object> actorSnapshot = ProtosActorValueTransfer.snapshotArguments(List.of(node), activation);
        Object actorValue = ProtosActorValueTransfer.snapshotValue(node, activation);
        boolean[] records = new boolean[1];
        List<Object> pSnapshot =
                ProtosParallelRuntime.captureValuesForTesting(List.of(node), activation, records);

        // The destination stage of an ordinary snapshot is the identity: no second pass.
        assertFalse(ProtosActorValueTransfer.requiresMaterialization(actorSnapshot));
        assertSame(
                actorSnapshot,
                ProtosActorValueTransfer.materializeArguments(actorSnapshot, prelude.newModuleActivation()));
        assertSame(
                actorValue,
                ProtosActorValueTransfer.materializeValue(actorValue, prelude.newModuleActivation()));
        assertFalse(records[0]);

        for (Object copied : List.of(actorSnapshot.get(0), actorValue, pSnapshot.get(0))) {
            ProtosObjectValue copy = assertInstanceOf(ProtosObjectValue.class, copied);
            assertNotSame(node, copy);
            assertEquals(ProtosObjectValue.class, copy.getClass());
            assertSame(copy, copy.readLocalSlot("self").orElseThrow());
            ProtosArrayValue items =
                    assertInstanceOf(ProtosArrayValue.class, copy.readLocalSlot("items").orElseThrow());
            assertSame(copy, items.indexedSnapshot().get(0));
            assertSame(copy, items.indexedSnapshot().get(1));
        }
        assertEquals(extracted, PORTABLE.extracted);
        assertEquals(materialized, PORTABLE.materialized);
        assertTrue(
                activation.actorModuleState().lookup(PORTABLE.ownerModule()).isEmpty(),
                "recognizing values must not import or initialize any module");
    }

    @Test
    void pPathCapturesAsInertDescriptorsAndMaterializesWithTheDestinationPrelude()
            throws Exception {
        ProtosPrelude destinationPrelude = new ProtosCoreBootstrap().bootstrap(CORE);
        ProtosPathValue shared = new ProtosPathValue(prelude.pathPrototype(), List.of("a", "b"));
        ProtosPathValue twin = new ProtosPathValue(prelude.pathPrototype(), List.of("a", "b"));
        ProtosArrayValue array = prelude.newArray(List.of(shared, twin));
        ProtosMapValue map = prelude.newMap();
        ProtosNumericHashKey hash = ProtosNumericHashKey.fromIdentity(shared);
        map.append(shared, hash, twin);
        boolean[] records = new boolean[1];

        List<Object> detached =
                ProtosParallelRuntime.captureValuesForTesting(
                        List.of(shared, shared, twin, array, map),
                        prelude.newModuleActivation(),
                        records);

        assertTrue(records[0], "a Path forces the destination materialization stage");
        ProtosPathComponents descriptor =
                assertInstanceOf(ProtosPathComponents.class, detached.get(0));
        assertSame(descriptor, detached.get(1), "the memo keeps one descriptor per source Path");
        assertNotSame(descriptor, detached.get(2));
        assertEquals(List.of("a", "b"), descriptor.components());
        assertThrows(UnsupportedOperationException.class, () -> descriptor.components().add("c"));
        ProtosArrayValue detachedArray = assertInstanceOf(ProtosArrayValue.class, detached.get(3));
        assertSame(descriptor, detachedArray.indexedAt(0));

        List<Object> delivered =
                ProtosParallelRuntime.materializeValuesForTesting(
                        detached, destinationPrelude.newModuleActivation());

        ProtosPathValue copied = assertInstanceOf(ProtosPathValue.class, delivered.get(0));
        ProtosPathValue copiedTwin = assertInstanceOf(ProtosPathValue.class, delivered.get(2));
        assertNotSame(shared, copied);
        assertSame(copied, delivered.get(1));
        assertNotSame(copied, copiedTwin);
        assertEquals(List.of("a", "b"), copiedTwin.components());
        assertSame(
                destinationPrelude.pathPrototype(),
                copied.representedDelegationParent(destinationPrelude));
        ProtosArrayValue copiedArray = assertInstanceOf(ProtosArrayValue.class, delivered.get(3));
        assertSame(copied, copiedArray.indexedAt(0));
        assertSame(copiedTwin, copiedArray.indexedAt(1));
        ProtosMapValue.Entry entry =
                assertInstanceOf(ProtosMapValue.class, delivered.get(4)).keyedSnapshot().get(0);
        assertSame(copied, entry.key());
        assertSame(copiedTwin, entry.value());
        assertEquals(hash, entry.recordedHash());
    }

    @Test
    void pTransferWithoutPathOrRecordsKeepsTheSingleStage() {
        ProtosArrayValue array =
                prelude.newArray(List.of(new ProtosStringValue("a"), new ProtosIntegerValue(1L)));
        boolean[] records = new boolean[1];

        List<Object> detached =
                ProtosParallelRuntime.captureValuesForTesting(
                        List.of(array), prelude.newModuleActivation(), records);

        assertFalse(records[0]);
        assertInstanceOf(ProtosArrayValue.class, detached.get(0));
    }

    @Test
    void transferRecordIsAnInertInternalNodeWithoutGuestSurface() {
        Class<ProtosSemanticTransferRecord> record = ProtosSemanticTransferRecord.class;
        assertFalse(ProtosObjectValue.class.isAssignableFrom(record), "a record is not a guest value");
        assertFalse(com.oracle.truffle.api.interop.TruffleObject.class.isAssignableFrom(record));
        assertTrue(Modifier.isFinal(record.getModifiers()));
        assertEquals(0, record.getConstructors().length, "records cannot be forged");
        assertEquals(0, ProtosSemanticTransferDestination.class.getConstructors().length);
    }

    @Test
    void genericMechanismHasNoRegexBranchAndUsesNoDynamicHostResolution() throws Exception {
        List<String> generic =
                List.of(
                        "runtime/ProtosSemanticTransferFamily.java",
                        "runtime/ProtosSemanticTransferValue.java",
                        "runtime/ProtosSemanticTransferPayload.java",
                        "runtime/ProtosSemanticTransferRecord.java",
                        "runtime/ProtosSemanticTransferDestination.java",
                        "runtime/ProtosActorValueTransfer.java",
                        "runtime/ProtosPrelude.java",
                        "execution/ProtosParallelRuntime.java");
        for (String relative : generic) {
            String source = Files.readString(MAIN.resolve(relative));
            assertFalse(source.toLowerCase(Locale.ROOT).contains("regex"), relative);
        }
        for (String relative : generic.subList(0, 6)) {
            String source = Files.readString(MAIN.resolve(relative));
            for (String forbidden :
                    List.of(
                            "java.lang.reflect",
                            "Class.forName",
                            "ServiceLoader",
                            "Serializable",
                            "readResolve",
                            "MethodHandle",
                            "ConcurrentHashMap")) {
                assertFalse(source.contains(forbidden), relative + " uses " + forbidden);
            }
        }
    }

    /** Graph: slots shared/again alias one value, twin is distinct with an equal payload, self is a cycle. */
    private static ProtosObjectValue surroundingGraph(
            ProtosSemanticTransferValue shared, ProtosSemanticTransferValue twin) {
        ProtosObjectValue graph = new ProtosObjectValue(ProtosObjectValue.rootObject());
        graph.createLocalSlot("shared", shared);
        graph.createLocalSlot("again", shared);
        graph.createLocalSlot("twin", twin);
        graph.createLocalSlot("self", graph);
        return graph;
    }

    private static void assertRematerializedGraph(
            ProtosSemanticTransferValue shared,
            ProtosSemanticTransferValue twin,
            ProtosObjectValue graph,
            Object copiedShared,
            Object copiedGraph) {
        ProtosObjectValue destination = assertInstanceOf(ProtosObjectValue.class, copiedGraph);
        ProtosSemanticTransferValue rebuilt =
                assertInstanceOf(ProtosSemanticTransferValue.class, copiedShared);
        Object again = destination.readLocalSlot("again").orElseThrow();
        Object rebuiltTwin = destination.readLocalSlot("twin").orElseThrow();

        assertNotSame(graph, destination);
        assertSame(destination, destination.readLocalSlot("self").orElseThrow());
        assertSame(rebuilt, destination.readLocalSlot("shared").orElseThrow());
        assertSame(rebuilt, again);
        assertNotSame(shared, rebuilt);
        assertNotSame(twin, rebuiltTwin);
        assertNotSame(rebuilt, rebuiltTwin);
        assertSame(PORTABLE, rebuilt.family());
        assertTrue(rebuilt.isFrozen());
        assertEquals("a+b", ((ProtosStringValue) rebuilt.readLocalSlot("text").orElseThrow()).value());
        // The callable surface was built by the destination stage, not copied.
        assertNotSame(
                shared.readLocalSlot("describe").orElseThrow(),
                rebuilt.readLocalSlot("describe").orElseThrow());
    }

    private static void assertNonTransferable(Object value, ProtosPrelude owner) {
        ProtosSignalException failure =
                assertThrows(
                        ProtosSignalException.class,
                        () -> ProtosActorValueTransfer.snapshotValue(value, owner.newModuleActivation()));
        assertSame(
                owner.bindings().readLocalSlot("NonTransferableValue").orElseThrow(),
                failure.error().parent().orElseThrow());
    }

    private static void assertNonParallel(Object value, ProtosPrelude owner) {
        RuntimeException failure =
                assertThrows(
                        RuntimeException.class,
                        () ->
                                ProtosParallelRuntime.captureValuesForTesting(
                                        List.of(value), owner.newModuleActivation(), new boolean[1]));
        assertEquals("NonParallel", failure.getClass().getSimpleName());
    }


    @Test
    void numericCandidatePreservesPrivateExactStateAndSignedZeroThroughActorAndP() {
        NumericCandidateState state =
                new NumericCandidateState(
                        BigInteger.ONE.shiftLeft(130).add(BigInteger.valueOf(7)),
                        BigInteger.valueOf(3),
                        -0.0d);

        ProtosSemanticTransferValue original =
                NUMERIC_CANDIDATE.mint(state, prelude);

        assertTrue(original.isFrozen());
        assertTrue(ProtosValueLookup.isProtosValue(original));
        assertSame(
                prelude.numberPrototype(),
                ProtosValueLookup.delegationParent(original, prelude)
                        .orElseThrow());
        assertFalse(original.hasLocalSlot("numerator"));
        assertFalse(original.hasLocalSlot("denominator"));
        assertFalse(original.hasLocalSlot("imaginaryZero"));

        List<Object> actorSnapshot =
                ProtosActorValueTransfer.snapshotArguments(
                        List.of(original), prelude.newModuleActivation());

        assertTrue(
                ProtosActorValueTransfer.requiresMaterialization(actorSnapshot));

        ProtosSemanticTransferValue actorCopy =
                assertInstanceOf(
                        ProtosSemanticTransferValue.class,
                        ProtosActorValueTransfer.materializeArguments(
                                actorSnapshot,
                                prelude.newModuleActivation()).get(0));

        boolean[] hasRecords = new boolean[1];
        List<Object> detached =
                ProtosParallelRuntime.captureValuesForTesting(
                        List.of(original),
                        prelude.newModuleActivation(),
                        hasRecords);

        assertTrue(hasRecords[0]);

        ProtosSemanticTransferValue parallelCopy =
                assertInstanceOf(
                        ProtosSemanticTransferValue.class,
                        ProtosParallelRuntime.materializeValuesForTesting(
                                detached,
                                prelude.newModuleActivation()).get(0));

        for (ProtosSemanticTransferValue copy :
                List.of(actorCopy, parallelCopy)) {
            assertNotSame(original, copy);
            assertTrue(copy.isFrozen());
            assertSame(NUMERIC_CANDIDATE, copy.family());
            assertSame(
                    prelude.numberPrototype(),
                    ProtosValueLookup.delegationParent(copy, prelude)
                            .orElseThrow());

            NumericCandidateState content =
                    NUMERIC_CANDIDATE.content(copy);

            assertEquals(state.numerator(), content.numerator());
            assertEquals(state.denominator(), content.denominator());
            assertEquals(
                    Double.doubleToRawLongBits(state.imaginaryZero()),
                    Double.doubleToRawLongBits(content.imaginaryZero()));
        }

        assertNotSame(actorCopy, parallelCopy);
    }

    @Test
    void numericCandidateExposesTheExistingValueIdentityIntegrationGap() {
        NumericCandidateState state =
                new NumericCandidateState(
                        BigInteger.ONE.shiftLeft(130),
                        BigInteger.valueOf(3),
                        -0.0d);

        ProtosSemanticTransferValue first =
                NUMERIC_CANDIDATE.mint(state, prelude);
        ProtosSemanticTransferValue second =
                NUMERIC_CANDIDATE.mint(state, prelude);

        assertNotSame(first, second);
        assertEquals(
                NUMERIC_CANDIDATE.content(first),
                NUMERIC_CANDIDATE.content(second));

        // This is an expected existing limitation, not Candidate C acceptance:
        // the standard identity machinery still treats rich objects by reference.
        assertFalse(ProtosIdentity.identical(first, second));
        assertFalse(ProtosValueLookup.isInteger(first));
        assertThrows(
                IllegalArgumentException.class,
                () -> ProtosStandardNumberEqualityProtocol.numericEquals(
                        first, second));

        // Hash currently takes the generic object-identity path. Do not
        // assume two distinct identity hashes must differ: collisions exist.
        assertEquals(
                Integer.toUnsignedLong(System.identityHashCode(first)),
                ProtosIdentity.identityHash(first));
    }

    private record NumericCandidateState(
            BigInteger numerator,
            BigInteger denominator,
            double imaginaryZero) {
    }

    /**
     * Test-only numeric feasibility fixture. It is intentionally not a
     * guest-visible D197 numeric family and defines no new public selectors.
     */
    private static final class NumericCandidateFamily
            extends ProtosSemanticTransferFamily {

        NumericCandidateFamily() {
            super(new ProtosModuleKey("std:i091-feasibility/Numeric"));
        }

        ProtosSemanticTransferValue mint(
                NumericCandidateState state, ProtosPrelude owner) {
            ProtosSemanticTransferValue value =
                    newValue(owner.numberPrototype(), state);
            value.freeze();
            return value;
        }

        NumericCandidateState content(ProtosSemanticTransferValue value) {
            return (NumericCandidateState) familyState(value);
        }

        @Override
        protected ProtosSemanticTransferPayload extract(
                ProtosSemanticTransferValue value) {
            NumericCandidateState content = content(value);
            return ProtosSemanticTransferPayload.of(
                    content.numerator(),
                    content.denominator(),
                    content.imaginaryZero());
        }

        @Override
        protected boolean acceptsPayload(
                ProtosSemanticTransferPayload payload) {
            return payload.size() == 3
                    && payload.get(0) instanceof BigInteger
                    && payload.get(1) instanceof BigInteger denominator
                    && denominator.signum() > 0
                    && payload.get(2) instanceof Double;
        }

        @Override
        protected ProtosSemanticTransferValue materialize(
                ProtosSemanticTransferPayload payload,
                ProtosSemanticTransferDestination destination) {
            NumericCandidateState state =
                    new NumericCandidateState(
                            (BigInteger) payload.get(0),
                            (BigInteger) payload.get(1),
                            (Double) payload.get(2));
            return mint(state, destination.prelude());
        }
    }

    private enum Mode {
        PORTABLE,
        NULL_PAYLOAD,
        MALFORMED_PAYLOAD,
        CLOSURE_PAYLOAD,
        THROWING,
        OPEN_RESULT,
        FOREIGN_RESULT
    }

    /**
     * Test-only infrastructure family: payload is one String; values carry a native Closure slot.
     * It proves only the generic mechanism; guest-implemented materialization is covered by the
     * hosted test.
     */
    private static final class FixtureFamily extends ProtosSemanticTransferFamily {
        private final Mode mode;
        int extracted;
        int materialized;
        ProtosActorExecutionDomain lastDestinationDomain;

        FixtureFamily(String ownerModule, Mode mode) {
            super(new ProtosModuleKey(ownerModule));
            this.mode = mode;
        }

        ProtosSemanticTransferValue mint(String text) {
            ProtosSemanticTransferValue value = mintOpen(text);
            value.freeze();
            return value;
        }

        ProtosSemanticTransferValue mintOpen(String text) {
            ProtosSemanticTransferValue value = newValue(ProtosObjectValue.rootObject());
            value.createLocalSlot("text", new ProtosStringValue(text));
            value.createLocalSlot(
                    "describe",
                    ProtosClosureValue.nativeClosure((a, args) -> new ProtosStringValue(text)));
            return value;
        }

        @Override
        protected ProtosSemanticTransferPayload extract(ProtosSemanticTransferValue value) {
            extracted++;
            Object text = value.readLocalSlot("text").orElse(null);
            return switch (mode) {
                case NULL_PAYLOAD -> null;
                case MALFORMED_PAYLOAD -> ProtosSemanticTransferPayload.of(BigInteger.ONE);
                case CLOSURE_PAYLOAD -> ProtosSemanticTransferPayload.of(value.readLocalSlot("describe").orElseThrow());
                case THROWING -> throw new IllegalStateException("extraction failure");
                default -> ProtosSemanticTransferPayload.of(((ProtosStringValue) text).value());
            };
        }

        @Override
        protected boolean acceptsPayload(ProtosSemanticTransferPayload payload) {
            return payload.size() == 1 && payload.get(0) instanceof String;
        }

        @Override
        protected ProtosSemanticTransferValue materialize(
                ProtosSemanticTransferPayload payload, ProtosSemanticTransferDestination destination) {
            materialized++;
            lastDestinationDomain = destination.executionDomain();
            String text = (String) payload.get(0);
            return switch (mode) {
                case OPEN_RESULT -> mintOpen(text);
                case FOREIGN_RESULT -> IMPOSTOR.mint(text);
                default -> mint(text);
            };
        }
    }
}
