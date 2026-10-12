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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * PERF025 focal evidence for generation-backed shallow Array snapshots.
 *
 * <p>Snapshot publication must not copy the complete indexed sequence.
 * A generation that has been exposed as a snapshot is immutable from that
 * point onward; the first later replacement detaches the live Array to a new
 * generation, leaving every established snapshot stable.
 */
final class ProtosPerf025ArraySnapshotGenerationTest {
    private static final Path ARRAY_SOURCE =
            Path.of(
                    "src/main/java/com/guillermomolina/protos/runtime/"
                            + "ProtosArrayValue.java");

    @Test
    void unpublishedArrayReplacesInPlaceAndRepeatedSnapshotsReuseGeneration()
            throws Exception {
        Object first = new Object();
        Object replacement = new Object();

        ProtosArrayValue array =
                new ProtosArrayValue(
                        ProtosObjectValue.rootObject(),
                        List.of(first));

        Object initialGeneration = indexedGeneration(array);

        assertSame(
                replacement,
                array.indexedPut(0, replacement));
        assertSame(
                initialGeneration,
                indexedGeneration(array),
                "ordinary replacement before snapshot publication must not copy storage");

        List<Object> firstSnapshot = array.indexedSnapshot();

        assertSame(replacement, firstSnapshot.get(0));
        assertSame(initialGeneration, indexedGeneration(array));
        assertSame(
                firstSnapshot,
                array.indexedSnapshot(),
                "repeated snapshots without mutation must reuse the published generation view");
    }

    @Test
    void firstReplacementAfterPublicationDetachesAndPreservesEveryOldSnapshot()
            throws Exception {
        Object first = new Object();
        Object second = new Object();
        Object third = new Object();

        ProtosArrayValue array =
                new ProtosArrayValue(
                        ProtosObjectValue.rootObject(),
                        List.of(first));

        List<Object> firstSnapshot = array.indexedSnapshot();
        Object firstGeneration = indexedGeneration(array);

        assertSame(second, array.indexedPut(0, second));

        Object secondGeneration = indexedGeneration(array);
        assertNotSame(
                firstGeneration,
                secondGeneration,
                "first write after publication must detach the live Array");
        assertSame(first, firstSnapshot.get(0));
        assertSame(second, array.indexedAt(0));

        List<Object> secondSnapshot = array.indexedSnapshot();
        assertSame(second, secondSnapshot.get(0));
        assertSame(secondSnapshot, array.indexedSnapshot());

        assertSame(third, array.indexedPut(0, third));

        Object thirdGeneration = indexedGeneration(array);
        assertNotSame(
                secondGeneration,
                thirdGeneration,
                "each published generation must detach before a later write");
        assertSame(first, firstSnapshot.get(0));
        assertSame(second, secondSnapshot.get(0));
        assertSame(third, array.indexedSnapshot().get(0));
    }

    @Test
    void snapshotViewIsReadOnlyAndStateBoundariesKeepExistingOrdering()
            throws Exception {
        Object first = new Object();
        Object replacement = new Object();

        ProtosArrayValue closed =
                new ProtosArrayValue(
                        ProtosObjectValue.rootObject(),
                        List.of(first));
        List<Object> closedSnapshot = closed.indexedSnapshot();

        assertThrows(
                UnsupportedOperationException.class,
                () -> closedSnapshot.set(0, replacement));

        closed.close();
        assertSame(
                replacement,
                closed.indexedPut(0, replacement));
        assertSame(first, closedSnapshot.get(0));
        assertSame(replacement, closed.indexedAt(0));

        ProtosArrayValue frozen =
                new ProtosArrayValue(
                        ProtosObjectValue.rootObject(),
                        List.of(first));
        List<Object> frozenSnapshot = frozen.indexedSnapshot();
        Object frozenGeneration = indexedGeneration(frozen);
        frozen.freeze();

        assertThrows(
                IllegalStateException.class,
                () ->
                        frozen.indexedPut(
                                99,
                                replacement));

        assertSame(
                frozenGeneration,
                indexedGeneration(frozen),
                "a rejected frozen write must not detach storage");
        assertSame(frozenSnapshot, frozen.indexedSnapshot());
        assertSame(first, frozenSnapshot.get(0));
    }

    @Test
    void constructorIsolationAndSourceFreezeCowInsteadOfEagerSnapshotCopy()
            throws Exception {
        Object first = new Object();
        Object externalReplacement = new Object();

        ArrayList<Object> supplied = new ArrayList<>();
        supplied.add(first);

        ProtosArrayValue array =
                new ProtosArrayValue(
                        ProtosObjectValue.rootObject(),
                        supplied);

        supplied.set(0, externalReplacement);

        assertSame(first, array.indexedAt(0));
        assertSame(first, array.indexedSnapshot().get(0));

        String source = Files.readString(ARRAY_SOURCE);

        assertTrue(source.contains("Collections.unmodifiableList(elements)"));
        assertTrue(source.contains("new ArrayList<>(elements)"));
        assertFalse(
                source.contains("return List.copyOf(elements);"),
                "indexedSnapshot must not return to an eager O(n) element-reference copy");
    }

    private static Object indexedGeneration(ProtosArrayValue array)
            throws Exception {
        Field field =
                ProtosArrayValue.class.getDeclaredField("indexedGeneration");
        field.setAccessible(true);
        return field.get(array);
    }
}
