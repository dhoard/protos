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

package com.guillermomolina.protos.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * PERF025 focal evidence for generation-backed shallow IdentityMap snapshots.
 *
 * <p>Snapshot publication must not copy the insertion-order Entry sequence or
 * materialize one association pair per entry. Once either snapshot view is
 * published, the first later mutation detaches the live IdentityMap to fresh
 * Entry objects and fresh exact-hash buckets, preserving every established
 * snapshot without imposing copy-on-write cost on never-snapshotted maps.
 */
final class ProtosPerf025IdentityMapSnapshotGenerationTest {
    private static final Path IDENTITY_MAP_SOURCE =
            Path.of(
                    "src/main/java/com/guillermomolina/protos/runtime/"
                            + "ProtosIdentityMapValue.java");
    private static final Path BYTECODE_ROOT =
            Path.of(
                    "src/main/java/com/guillermomolina/protos/execution/"
                            + "ProtosBytecodeRootNode.java");

    @Test
    void unpublishedMutationStaysInPlaceAndSnapshotsReuseViews()
            throws Exception {
        ProtosObjectValue parent = ProtosObjectValue.rootObject();
        ProtosIdentityMapValue map =
                new ProtosIdentityMapValue(parent);

        Object key = new ProtosObjectValue(parent);
        Object firstValue = new ProtosObjectValue(parent);
        Object replacement = new ProtosObjectValue(parent);
        ProtosNumericHashKey hash = ProtosNumericHashKey.fromIdentity(key);

        map.append(key, hash, firstValue);

        Object initialGeneration = generation(map);

        List<ProtosIdentityMapValue.Entry> candidates =
                map.candidatesForRecordedIdentityHash(hash);

        map.replaceValue(candidates.get(0), replacement);

        assertSame(
                initialGeneration,
                generation(map),
                "ordinary replacement before publication must not detach");
        assertSame(
                candidates,
                map.candidatesForRecordedIdentityHash(hash),
                "unpublished exact-hash bucket view remains live");
        assertSame(replacement, candidates.get(0).value());

        List<ProtosIdentityMapValue.Entry> keyed =
                map.keyedSnapshot();
        List<Map.Entry<Object, Object>> associations =
                map.associationSnapshot();

        assertSame(initialGeneration, generation(map));

        assertSame(
                keyed,
                map.keyedSnapshot(),
                "repeated keyed snapshots reuse the generation view");
        assertSame(
                associations,
                map.associationSnapshot(),
                "repeated association snapshots reuse the generation view");

        assertSame(
                keyed.get(0),
                associations.get(0),
                "association traversal must not materialize pair objects");

        assertThrows(
                UnsupportedOperationException.class,
                keyed::clear);
        assertThrows(
                UnsupportedOperationException.class,
                () -> associations.get(0).setValue(firstValue));
    }

    @Test
    void successivePublishedGenerationsKeepExactHistoricalValues()
            throws Exception {
        ProtosObjectValue parent = ProtosObjectValue.rootObject();
        ProtosIdentityMapValue map =
                new ProtosIdentityMapValue(parent);

        Object key = new ProtosObjectValue(parent);
        Object first = new ProtosObjectValue(parent);
        Object second = new ProtosObjectValue(parent);
        Object third = new ProtosObjectValue(parent);
        Object fourth = new ProtosObjectValue(parent);
        ProtosNumericHashKey hash = ProtosNumericHashKey.fromIdentity(key);

        map.append(key, hash, first);

        List<Map.Entry<Object, Object>> firstSnapshot =
                map.associationSnapshot();
        Object firstGeneration = generation(map);

        map.replaceValue(
                map.candidatesForRecordedIdentityHash(hash).get(0),
                second);

        Object secondGeneration = generation(map);

        assertNotSame(firstGeneration, secondGeneration);
        assertSame(first, firstSnapshot.get(0).getValue());
        assertSame(
                second,
                map.candidatesForRecordedIdentityHash(hash)
                        .get(0)
                        .value());

        List<Map.Entry<Object, Object>> secondSnapshot =
                map.associationSnapshot();

        map.replaceValue(
                map.candidatesForRecordedIdentityHash(hash).get(0),
                third);

        Object thirdGeneration = generation(map);

        assertNotSame(secondGeneration, thirdGeneration);
        assertSame(first, firstSnapshot.get(0).getValue());
        assertSame(second, secondSnapshot.get(0).getValue());
        assertSame(
                third,
                map.candidatesForRecordedIdentityHash(hash)
                        .get(0)
                        .value());

        map.replaceValue(
                map.candidatesForRecordedIdentityHash(hash).get(0),
                fourth);

        assertSame(
                thirdGeneration,
                generation(map),
                "after detach, ordinary replacement stays cheap until "
                        + "another snapshot is published");
        assertSame(first, firstSnapshot.get(0).getValue());
        assertSame(second, secondSnapshot.get(0).getValue());
        assertSame(
                fourth,
                map.candidatesForRecordedIdentityHash(hash)
                        .get(0)
                        .value());
    }

    @Test
    void appendRemoveAndReinsertPreserveOldOrderAndCollisionBuckets()
            throws Exception {
        ProtosObjectValue parent = ProtosObjectValue.rootObject();
        ProtosIdentityMapValue map =
                new ProtosIdentityMapValue(parent);

        Object firstKey = new ProtosObjectValue(parent);
        Object secondKey = new ProtosObjectValue(parent);
        Object firstValue = new ProtosObjectValue(parent);
        Object secondValue = new ProtosObjectValue(parent);
        Object reinsertedValue = new ProtosObjectValue(parent);

        ProtosNumericHashKey collisionHash = ProtosNumericHashKey.ofLong(17);

        map.append(firstKey, collisionHash, firstValue);

        List<ProtosIdentityMapValue.Entry> oneEntrySnapshot =
                map.keyedSnapshot();
        Object firstGeneration = generation(map);

        map.append(secondKey, collisionHash, secondValue);

        Object secondGeneration = generation(map);

        assertNotSame(firstGeneration, secondGeneration);
        assertEquals(1, oneEntrySnapshot.size());
        assertSame(firstKey, oneEntrySnapshot.get(0).key());

        List<ProtosIdentityMapValue.Entry> twoEntrySnapshot =
                map.keyedSnapshot();

        List<ProtosIdentityMapValue.Entry> publishedCandidates =
                map.candidatesForRecordedIdentityHash(collisionHash);

        assertEquals(2, publishedCandidates.size());
        assertSame(firstKey, publishedCandidates.get(0).key());
        assertSame(secondKey, publishedCandidates.get(1).key());

        ProtosIdentityMapValue.Entry removed =
                publishedCandidates.get(0);

        assertSame(firstValue, map.remove(removed));

        Object thirdGeneration = generation(map);

        assertNotSame(secondGeneration, thirdGeneration);

        assertEquals(2, twoEntrySnapshot.size());
        assertSame(firstKey, twoEntrySnapshot.get(0).key());
        assertSame(secondKey, twoEntrySnapshot.get(1).key());

        assertEquals(
                2,
                publishedCandidates.size(),
                "old published collision bucket remains immutable");

        List<ProtosIdentityMapValue.Entry> currentCandidates =
                map.candidatesForRecordedIdentityHash(collisionHash);

        assertEquals(1, currentCandidates.size());
        assertSame(secondKey, currentCandidates.get(0).key());

        map.append(
                firstKey,
                collisionHash,
                reinsertedValue);

        assertSame(
                thirdGeneration,
                generation(map),
                "after detach, append stays in-place until publication");
        assertSame(
                currentCandidates,
                map.candidatesForRecordedIdentityHash(collisionHash));

        assertEquals(2, currentCandidates.size());
        assertSame(secondKey, currentCandidates.get(0).key());
        assertSame(firstKey, currentCandidates.get(1).key());

        List<ProtosIdentityMapValue.Entry> currentOrder =
                map.keyedSnapshot();

        assertEquals(2, currentOrder.size());
        assertSame(secondKey, currentOrder.get(0).key());
        assertSame(firstKey, currentOrder.get(1).key());
        assertSame(reinsertedValue, currentOrder.get(1).value());

        assertEquals(1, oneEntrySnapshot.size());
        assertEquals(2, twoEntrySnapshot.size());
        assertSame(firstValue, twoEntrySnapshot.get(0).value());
    }

    @Test
    void sourceKeepsSnapshotsLazyAndStructuredEachRetainsView()
            throws Exception {
        String identitySource =
                Files.readString(IDENTITY_MAP_SOURCE);
        String bytecodeSource =
                Files.readString(BYTECODE_ROOT);

        assertTrue(
                identitySource.contains(
                        "Collections.unmodifiableList(entries)"));
        assertTrue(
                identitySource.contains(
                        "new AssociationSnapshotView(entries)"));

        assertFalse(
                identitySource.contains(
                        "return List.copyOf(entries);"),
                "keyedSnapshot must not restore eager O(n) copying");

        assertFalse(
                identitySource.contains(
                        "snapshot.add(Map.entry("),
                "associationSnapshot must not allocate one pair per entry");

        int identityMapEachStart =
                bytecodeSource.indexOf(
                        "static final class PreparedIdentityMapEachCall");
        int identityMapEachEnd =
                bytecodeSource.indexOf(
                        "public static final class IsStructuredIdentityMapEachCall",
                        identityMapEachStart);

        assertTrue(
                identityMapEachStart >= 0,
                "PreparedIdentityMapEachCall source owner must remain present");
        assertTrue(
                identityMapEachEnd > identityMapEachStart,
                "PreparedIdentityMapEachCall source boundary must remain present");

        String identityMapEachSource =
                bytecodeSource.substring(
                        identityMapEachStart,
                        identityMapEachEnd);

        assertFalse(
                identityMapEachSource.contains(
                        "List.copyOf(value.associationSnapshot())"),
                "structured IdentityMap.each must retain the stable "
                        + "generation view directly");

        assertTrue(
                identityMapEachSource.contains(
                        "this.snapshot = value.associationSnapshot();"));
    }

    private static Object generation(
            ProtosIdentityMapValue map)
            throws Exception {
        Field field =
                ProtosIdentityMapValue.class.getDeclaredField(
                        "generation");
        field.setAccessible(true);
        return field.get(map);
    }
}
