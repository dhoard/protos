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
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import com.guillermomolina.protos.execution.ProtosCoreBootstrap;
import com.oracle.truffle.api.profiles.InlinedBranchProfile;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

final class ProtosGuardedLookupTest {
    private static ProtosObjectValue object() {
        return new ProtosObjectValue(ProtosObjectValue.rootObject());
    }

    private static Object read(
            Object receiver, ProtosValueLookup.GuardedSlotSelection selection) {
        return ProtosValueLookup.materializeGuardedMemberRead(
                receiver, selection, null, InlinedBranchProfile.getUncached());
    }

    private static Object read(
            Object receiver, ProtosValueLookup.SharedInheritedSlotSelection selection) {
        return ProtosValueLookup.materializeGuardedMemberRead(
                receiver, selection, null, InlinedBranchProfile.getUncached());
    }

    private static ProtosValueLookup.GuardedLookup selected(ProtosObjectValue receiver) {
        var result = ProtosValueLookup.lookupGuarded(receiver, "pick", null);
        assertNotNull(result);
        assertTrue(result.stability().isValid());
        var generic = ProtosValueLookup.lookup(receiver, "pick", null).orElseThrow();
        assertSame(generic.value(), result.selected().value());
        assertSame(generic.home(), result.selected().home());
        return result;
    }

    @Test
    void replacementInvalidatesEveryDependentReceiver() {
        var parent = object();
        var first = new ProtosObjectValue(parent);
        var second = new ProtosObjectValue(parent);
        Object oldValue = new Object();
        Object newValue = new Object();
        parent.createLocalSlot("pick", oldValue);
        var firstLookup = selected(first);
        var secondLookup = selected(second);

        parent.assignLocalSlot("pick", newValue);

        assertFalse(firstLookup.stability().isValid());
        assertFalse(secondLookup.stability().isValid());
        assertSame(oldValue, firstLookup.selected().value());
        assertSame(newValue, selected(first).selected().value());
    }

    @Test
    void sharedInheritedLookupTracksParentChainButNotSiblingShadows() {
        var parent = object();
        Object inherited = new Object();
        Object replacement = new Object();
        parent.createLocalSlot("pick", inherited);

        var first = new ProtosObjectValue(parent);
        var second = new ProtosObjectValue(parent);
        var third = new ProtosObjectValue(parent);

        var shared =
                ProtosValueLookup.lookupGuardedSharedInherited(
                        first,
                        "pick",
                        null);
        assertNotNull(shared);
        assertSame(parent, shared.exactParent());
        assertSame(inherited, shared.selected().value());
        assertSame(parent, shared.selected().home());
        assertTrue(shared.stability().isValid());

        assertTrue(
                ProtosValueLookup.matchesGuardedSharedInherited(
                        second,
                        "pick",
                        shared));
        assertTrue(
                ProtosValueLookup.matchesGuardedSharedInherited(
                        third,
                        "pick",
                        shared));

        second.createLocalSlot("pick", new Object());
        assertFalse(
                ProtosValueLookup.matchesGuardedSharedInherited(
                        second,
                        "pick",
                        shared));
        assertTrue(
                shared.stability().isValid(),
                "a sibling-local shadow is deliberately not a parent-chain dependency");

        second.removeLocalSlot("pick");
        assertTrue(
                ProtosValueLookup.matchesGuardedSharedInherited(
                        second,
                        "pick",
                        shared));
        assertTrue(shared.stability().isValid());

        parent.assignLocalSlot("pick", replacement);
        assertFalse(shared.stability().isValid());

        var refreshed =
                ProtosValueLookup.lookupGuardedSharedInherited(
                        third,
                        "pick",
                        null);
        assertNotNull(refreshed);
        assertSame(replacement, refreshed.selected().value());
        assertSame(parent, refreshed.selected().home());
    }

    @Test
    void sharedInheritedLookupInvalidatesForLateIntermediateShadow() {
        var ancestor = object();
        Object inherited = new Object();
        Object nearer = new Object();
        ancestor.createLocalSlot("pick", inherited);

        var parent = new ProtosObjectValue(ancestor);
        var first = new ProtosObjectValue(parent);
        var second = new ProtosObjectValue(parent);

        var shared =
                ProtosValueLookup.lookupGuardedSharedInherited(
                        first,
                        "pick",
                        null);
        assertNotNull(shared);
        assertSame(parent, shared.exactParent());
        assertSame(ancestor, shared.selected().home());
        assertSame(inherited, shared.selected().value());

        parent.createLocalSlot("pick", nearer);
        assertFalse(shared.stability().isValid());

        var refreshed =
                ProtosValueLookup.lookupGuardedSharedInherited(
                        second,
                        "pick",
                        null);
        assertNotNull(refreshed);
        assertSame(parent, refreshed.selected().home());
        assertSame(nearer, refreshed.selected().value());
    }

    @Test
    void sharedInheritedLookupRejectsOwnSlotsAndSubclassChains() {
        var parent = object();
        parent.createLocalSlot("pick", new Object());

        var own = new ProtosObjectValue(parent);
        own.createLocalSlot("pick", new Object());
        assertNull(
                ProtosValueLookup.lookupGuardedSharedInherited(
                        own,
                        "pick",
                        null));

        var context =
                new ProtosExecutionContextValue(
                        ProtosObjectValue.rootObject());
        context.createLocalSlot("pick", new Object());
        assertNull(
                ProtosValueLookup.lookupGuardedSharedInherited(
                        context,
                        "pick",
                        null));

        var childOfContext = new ProtosObjectValue(context);
        assertNull(
                ProtosValueLookup.lookupGuardedSharedInherited(
                        childOfContext,
                        "pick",
                        null));
    }

    @Test
    void removalRejectsStaleHomeAndRevealsInheritedSelection() {
        var parent = object();
        var receiver = new ProtosObjectValue(parent);
        Object inherited = new Object();
        parent.createLocalSlot("pick", inherited);
        receiver.createLocalSlot("pick", new Object());
        var before = selected(receiver);
        assertSame(receiver, before.selected().home());

        receiver.removeLocalSlot("pick");

        assertFalse(before.stability().isValid());
        var after = selected(receiver);
        assertSame(parent, after.selected().home());
        assertSame(inherited, after.selected().value());
        parent.removeLocalSlot("pick");
        assertFalse(after.stability().isValid());
        assertNull(ProtosValueLookup.lookupGuarded(receiver, "pick", null));
    }

    @Test
    void nearerAdditionInvalidatesEvenWhenClosureIdentityIsUnchanged() {
        var parent = object();
        var middle = new ProtosObjectValue(parent);
        var receiver = new ProtosObjectValue(middle);
        var closure = ProtosClosureValue.nativeClosure(
                (activation, supplied) -> ProtosNullValue.INSTANCE);
        parent.createLocalSlot("pick", closure);
        var before = selected(receiver);

        middle.createLocalSlot("pick", closure);

        assertFalse(before.stability().isValid());
        var after = selected(receiver);
        assertSame(closure, after.selected().value());
        assertSame(middle, after.selected().home());
    }

    @Test
    void unrelatedNamesAndObjectsDoNotInvalidate() {
        var parent = object();
        var receiver = new ProtosObjectValue(parent);
        parent.createLocalSlot("pick", new Object());
        var lookup = selected(receiver);

        receiver.createLocalSlot("other", new Object());
        receiver.assignLocalSlot("other", new Object());
        receiver.removeLocalSlot("other");
        parent.createLocalSlot("other", new Object());
        object().createLocalSlot("pick", new Object());

        assertTrue(lookup.stability().isValid());
    }

    @Test
    void successfulCompositionInvalidatesOnlyContributedSelectors() {
        var parent = object();
        var receiver = new ProtosObjectValue(parent);
        var source = object();
        parent.createLocalSlot("pick", new Object());
        source.createLocalSlot("pick", new Object());
        var before = selected(receiver);

        receiver.composeLocalSlotsFrom(source, Set.of("pick"));
        assertTrue(before.stability().isValid());

        receiver.composeLocalSlotsFrom(source, Set.of());
        assertFalse(before.stability().isValid());
        assertSame(receiver, selected(receiver).selected().home());
    }

    @Test
    void failedCompositionAndFrozenMutationPreserveSelection() {
        var receiver = object();
        var source = object();
        receiver.createLocalSlot("pick", new Object());
        source.createLocalSlot("pick", new Object());
        var lookup = selected(receiver);

        assertThrows(IllegalStateException.class,
                () -> receiver.composeLocalSlotsFrom(source, Set.of()));
        assertTrue(lookup.stability().isValid());
        receiver.close();
        assertThrows(IllegalStateException.class,
                () -> receiver.removeLocalSlot("pick"));
        receiver.freeze();
        assertThrows(IllegalStateException.class,
                () -> receiver.assignLocalSlot("pick", new Object()));
        assertTrue(lookup.stability().isValid());
        assertTrue(selected(receiver).stability().isValid());
    }

    @Test
    void contextStorageRemainsOnGenericLookup() {
        var context = new ProtosExecutionContextValue(ProtosObjectValue.rootObject());
        Object value = new Object();
        context.createLocalSlot("pick", value);
        assertNull(ProtosValueLookup.lookupGuarded(context, "pick", null));
        assertSame(value,
                ProtosValueLookup.lookup(context, "pick", null).orElseThrow().value());

        var child = new ProtosObjectValue(context);
        assertNull(ProtosValueLookup.lookupGuarded(child, "pick", null));
    }

    @Test
    void representedParentChangesRemainVisibleThroughGenericFallback() {
        var first = object();
        var second = object();
        first.createLocalSlot("pick", new Object());
        second.createLocalSlot("pick", new Object());
        var represented = new MutableRepresentedValue(first);
        var receiver = new ProtosObjectValue(represented);

        assertNull(ProtosValueLookup.lookupGuarded(receiver, "pick", null));
        assertSame(first,
                ProtosValueLookup.lookup(receiver, "pick", null).orElseThrow().home());
        represented.parent = second;
        assertNull(ProtosValueLookup.lookupGuarded(receiver, "pick", null));
        assertSame(second,
                ProtosValueLookup.lookup(receiver, "pick", null).orElseThrow().home());
    }

    @Test
    void canonicalBooleanGuardedSelectionMatchesGenericLookup() {
        var root = ProtosObjectValue.rootObject();
        for (Object receiver : new Object[] {ProtosBooleanValue.TRUE, ProtosBooleanValue.FALSE}) {
            assertNull(ProtosValueLookup.lookupGuardedCanonicalBoolean(
                    receiver, "perf015Absent", null));
            // The ordinary guarded entry point stays generic for represented values.
            assertNull(ProtosValueLookup.lookupGuarded(receiver, "ifTrue", null));

            // Present only once Core bootstrap has installed the standard protocol.
            if (root.hasLocalSlot("ifTrue")) {
                var guarded = ProtosValueLookup.lookupGuardedCanonicalBoolean(
                        receiver, "ifTrue", null);
                assertNotNull(guarded);
                assertTrue(guarded.stability().isValid());
                var generic = ProtosValueLookup.lookup(receiver, "ifTrue", null).orElseThrow();
                assertSame(generic.value(), guarded.selected().value());
                assertSame(generic.home(), guarded.selected().home());
                assertSame(root, guarded.selected().home());
            }
        }
    }

    /**
     * Root Object is frozen by Core bootstrap, so this mutation evidence is
     * only reachable while it is still open (JVM-order dependent).
     */
    @Test
    void canonicalBooleanGuardedSelectionInvalidatesOnRootObjectMutation() {
        var root = ProtosObjectValue.rootObject();
        assumeFalse(root.isFrozen(), "root Object already frozen by Core bootstrap");
        for (Object receiver : new Object[] {ProtosBooleanValue.TRUE, ProtosBooleanValue.FALSE}) {
            Object first = new Object();
            Object second = new Object();
            root.createLocalSlot("perf015Probe", first);
            try {
                var guarded = ProtosValueLookup.lookupGuardedCanonicalBoolean(
                        receiver, "perf015Probe", null);
                assertNotNull(guarded);
                assertTrue(guarded.stability().isValid());
                assertSame(first, guarded.selected().value());
                root.assignLocalSlot("perf015Probe", second);
                assertFalse(guarded.stability().isValid());
            } finally {
                root.removeLocalSlot("perf015Probe");
            }
        }
    }

    @Test
    void canonicalBooleanGuardedSelectionRejectsOtherReceivers() {
        var represented = new MutableRepresentedValue(ProtosObjectValue.rootObject());
        assertNull(ProtosValueLookup.lookupGuardedCanonicalBoolean(
                represented, "perf015Probe", null));
        assertNull(ProtosValueLookup.lookupGuardedCanonicalBoolean(
                object(), "perf015Probe", null));
        assertFalse(ProtosValueLookup.isCanonicalBoolean(represented));
        assertTrue(ProtosValueLookup.isCanonicalBoolean(ProtosBooleanValue.TRUE));
        assertTrue(ProtosValueLookup.isCanonicalBoolean(ProtosBooleanValue.FALSE));
    }


    @Test
    void slotSelectionSurvivesRepeatedValueReplacement() {
        var holder = object();
        Object initial = new Object();
        holder.createLocalSlot("pick", initial);

        var selection =
                ProtosValueLookup.lookupGuardedSlotSelection(holder, "pick", null);
        var valueSensitive = selected(holder);
        assertNotNull(selection);
        assertSame(holder, selection.home());

        for (int index = 0; index < 200; index++) {
            Object replacement = new Object();
            holder.assignLocalSlot("pick", replacement);
            assertTrue(selection.stability().isValid());
            assertSame(
                    replacement,
                    read(holder, selection));
        }

        assertFalse(valueSensitive.stability().isValid());
        assertSame(initial, valueSensitive.selected().value());
    }

    /*
     * PERF037-B: a selection's stable location is the binding's single
     * authoritative store. It must stay invisible on every read surface,
     * keep insertion order, follow assignment, and never survive removal.
     */
    @Test
    void stableSlotLocationIsTheSingleInvisibleAuthority() {
        var holder = object();
        holder.createLocalSlot("a", "A");
        holder.createLocalSlot("pick", "first");
        holder.createLocalSlot("c", "C");

        var selection =
                ProtosValueLookup.lookupGuardedSlotSelection(holder, "pick", null);
        assertNotNull(selection);
        assertSame(
                selection.location(),
                ProtosValueLookup.lookupGuardedSlotSelection(holder, "pick", null).location());
        assertEquals(List.of("a", "pick", "c"), List.copyOf(holder.localSlotsSnapshot().keySet()));
        assertEquals("first", holder.localSlotsSnapshot().get("pick"));
        assertEquals("first", holder.readLocalSlot("pick").orElseThrow());

        holder.assignLocalSlot("pick", "second");
        assertTrue(selection.stability().isValid());
        assertEquals("second", read(holder, selection));
        var names = new java.util.ArrayList<String>();
        var values = new java.util.ArrayList<Object>();
        holder.appendLocalBindingsTo(names, values);
        assertEquals(List.of("a", "pick", "c"), names);
        assertEquals(List.of("A", "second", "C"), values);

        var copy = holder.withoutLocalSlot("a");
        assertEquals("second", copy.readLocalSlot("pick").orElseThrow());
        copy.assignLocalSlot("pick", "copy");
        assertEquals("second", read(holder, selection));

        assertEquals("second", holder.removeLocalSlot("pick"));
        assertFalse(selection.stability().isValid());
        holder.createLocalSlot("pick", "recreated");
        assertEquals(List.of("a", "c", "pick"), List.copyOf(holder.localSlotsSnapshot().keySet()));
        var recreated =
                ProtosValueLookup.lookupGuardedSlotSelection(holder, "pick", null);
        assertNotNull(recreated);
        assertFalse(recreated.location() == selection.location());
        assertEquals("recreated", read(holder, recreated));
    }

    @Test
    void stableSlotLocationFollowsDataAndClosureValues() {
        var receiver = object();
        var closure = ProtosClosureValue.nativeClosure(
                (activation, supplied) -> ProtosNullValue.INSTANCE);
        receiver.createLocalSlot("pick", ProtosNullValue.INSTANCE);
        var selection =
                ProtosValueLookup.lookupGuardedSlotSelection(receiver, "pick", null);
        assertNotNull(selection);
        assertNotNull(selection.nonClosureContinuity());
        assertTrue(selection.nonClosureContinuity().isValid());
        assertSame(ProtosNullValue.INSTANCE, read(receiver, selection));

        receiver.assignLocalSlot("pick", closure);
        assertFalse(selection.nonClosureContinuity().isValid());
        var first = (ProtosClosureValue) read(receiver, selection);
        var second = (ProtosClosureValue) read(receiver, selection);
        assertFalse(first == second);
        assertFalse(first == closure);
        assertSame(receiver, first.capturedReceiver());

        Object data = new Object();
        receiver.assignLocalSlot("pick", data);
        assertTrue(selection.stability().isValid());
        assertFalse(selection.nonClosureContinuity().isValid());
        assertSame(data, read(receiver, selection));
    }

    @Test
    void nonClosureContinuityInvalidatesOnlyItsOwnCell() {
        var first = object();
        var second = object();

        Object originalFirst = new Object();
        Object originalSecond = new Object();

        first.createLocalSlot("pick", originalFirst);
        second.createLocalSlot("pick", originalSecond);

        var firstSelection =
                ProtosValueLookup.lookupGuardedSlotSelection(
                        first, "pick", null);
        var secondSelection =
                ProtosValueLookup.lookupGuardedSlotSelection(
                        second, "pick", null);

        assertNotNull(firstSelection);
        assertNotNull(secondSelection);
        assertNotNull(firstSelection.nonClosureContinuity());
        assertNotNull(secondSelection.nonClosureContinuity());

        for (int index = 0; index < 20; index++) {
            Object replacement = new Object();
            first.assignLocalSlot("pick", replacement);
            assertSame(replacement, read(first, firstSelection));
            assertTrue(firstSelection.nonClosureContinuity().isValid());
            assertTrue(secondSelection.nonClosureContinuity().isValid());
        }

        ProtosClosureValue closure =
                ProtosClosureValue.nativeClosure(
                        (activation, supplied) -> ProtosNullValue.INSTANCE);

        first.assignLocalSlot("pick", closure);

        assertFalse(firstSelection.nonClosureContinuity().isValid());
        assertTrue(secondSelection.nonClosureContinuity().isValid());
        assertTrue(firstSelection.stability().isValid());

        ProtosClosureValue bound =
                assertInstanceOf(
                        ProtosClosureValue.class,
                        read(first, firstSelection));
        assertSame(first, bound.capturedReceiver());

        Object restored = new Object();
        first.assignLocalSlot("pick", restored);

        assertSame(restored, read(first, firstSelection));
        assertFalse(firstSelection.nonClosureContinuity().isValid());
        assertSame(originalSecond, read(second, secondSelection));
    }

    @Test
    void emptyObjectsNeverAllocateSlotStorageForGuardedReads() {
        var empty = object();
        assertNull(ProtosValueLookup.lookupGuardedSlotSelection(empty, "pick", null));
        assertFalse(empty.hasLocalBindingsForRuntime());
        assertTrue(empty.localSlotsSnapshot().isEmpty());
    }

    @Test
    void inheritedSelectionKeepsItsHomeAndIsolatesSiblings() {
        var ancestor = object();
        var parent = new ProtosObjectValue(ancestor);
        var first = new ProtosObjectValue(parent);
        var second = new ProtosObjectValue(parent);
        ancestor.createLocalSlot("pick", new Object());

        var selection =
                ProtosValueLookup.lookupGuardedSharedInheritedSlotSelection(
                        first, "pick", null);
        assertNotNull(selection);
        assertSame(ancestor, selection.parentSelection().home());
        assertTrue(
                ProtosValueLookup.matchesGuardedSharedInheritedSlotSelection(
                        second, "pick", selection));

        Object updated = new Object();
        ancestor.assignLocalSlot("pick", updated);
        assertTrue(selection.stability().isValid());
        assertSame(
                updated,
                read(second, selection));

        second.createLocalSlot("pick", new Object());
        assertTrue(selection.stability().isValid());
        assertFalse(
                ProtosValueLookup.matchesGuardedSharedInheritedSlotSelection(
                        second, "pick", selection));
        assertTrue(
                ProtosValueLookup.matchesGuardedSharedInheritedSlotSelection(
                        first, "pick", selection));

        second.removeLocalSlot("pick");
        assertTrue(
                ProtosValueLookup.matchesGuardedSharedInheritedSlotSelection(
                        second, "pick", selection));

        Object nearer = new Object();
        parent.createLocalSlot("pick", nearer);
        assertFalse(selection.stability().isValid());

        var refreshed =
                ProtosValueLookup.lookupGuardedSharedInheritedSlotSelection(
                        first, "pick", null);
        assertNotNull(refreshed);
        assertSame(parent, refreshed.parentSelection().home());
        assertSame(
                nearer,
                read(first, refreshed));
    }

    @Test
    void localRemovalAndCompositionInvalidateSelection() {
        var parent = object();
        var receiver = new ProtosObjectValue(parent);
        parent.createLocalSlot("pick", new Object());
        receiver.createLocalSlot("pick", new Object());

        var local = ProtosValueLookup.lookupGuardedSlotSelection(
                receiver, "pick", null);
        assertNotNull(local);
        receiver.removeLocalSlot("pick");
        assertFalse(local.stability().isValid());

        var inherited = ProtosValueLookup.lookupGuardedSlotSelection(
                receiver, "pick", null);
        assertNotNull(inherited);
        assertSame(parent, inherited.home());

        var source = object();
        source.createLocalSlot("pick", new Object());
        receiver.composeLocalSlotsFrom(source, Set.of());
        assertFalse(inherited.stability().isValid());

        var composed = ProtosValueLookup.lookupGuardedSlotSelection(
                receiver, "pick", null);
        assertNotNull(composed);
        assertSame(receiver, composed.home());
    }

    @Test
    void sharedInheritedPlainContinuityHandlesClosureAndShadowing() {
        var parent = object();
        var first = new ProtosObjectValue(parent);
        var second = new ProtosObjectValue(parent);

        Object originalValue = new Object();
        parent.createLocalSlot("pick", originalValue);

        var selection =
                ProtosValueLookup.lookupGuardedSharedInheritedSlotSelection(
                        first, "pick", null);
        assertNotNull(selection);

        var continuity = selection.nonClosureContinuity();
        assertNotNull(continuity);
        assertTrue(continuity.isValid());

        Object replacement = new Object();
        parent.assignLocalSlot("pick", replacement);

        assertTrue(selection.stability().isValid());
        assertTrue(continuity.isValid());
        assertSame(replacement, read(first, selection));
        assertSame(replacement, read(second, selection));

        ProtosClosureValue closure =
                ProtosClosureValue.nativeClosure(
                        (activation, supplied) -> ProtosNullValue.INSTANCE);

        parent.assignLocalSlot("pick", closure);

        assertFalse(continuity.isValid());
        assertTrue(selection.stability().isValid());

        ProtosClosureValue extractedA =
                assertInstanceOf(
                        ProtosClosureValue.class,
                        read(first, selection));
        ProtosClosureValue extractedB =
                assertInstanceOf(
                        ProtosClosureValue.class,
                        read(first, selection));

        assertFalse(extractedA == extractedB);
        assertSame(first, extractedA.capturedReceiver());
        assertSame(parent, extractedA.methodHome().orElseThrow());

        second.createLocalSlot("pick", new Object());

        assertFalse(
                ProtosValueLookup.matchesGuardedSharedInheritedSlotSelection(
                        second, "pick", selection));

        assertTrue(
                ProtosValueLookup.matchesGuardedSharedInheritedSlotSelection(
                        first, "pick", selection));

        Object restored = new Object();
        parent.assignLocalSlot("pick", restored);

        assertSame(restored, read(first, selection));
        assertFalse(continuity.isValid());
    }

    @Test
    void selectedClosureIsReadFreshAndBoundToOriginalReceiver() {
        var parent = object();
        var receiver = new ProtosObjectValue(parent);
        var first = ProtosClosureValue.nativeClosure(
                (activation, supplied) -> ProtosNullValue.INSTANCE);
        var replacement = ProtosClosureValue.nativeClosure(
                (activation, supplied) -> ProtosBooleanValue.TRUE);
        parent.createLocalSlot("pick", first);

        var selection = ProtosValueLookup.lookupGuardedSlotSelection(
                receiver, "pick", null);
        assertNotNull(selection);
        assertSame(parent, selection.home());
        assertNull(
                selection.nonClosureContinuity(),
                "an initially Closure-valued slot cannot admit the plain lane");

        var extractedA = (ProtosClosureValue)
                read(receiver, selection);
        var extractedB = (ProtosClosureValue)
                read(receiver, selection);
        assertFalse(extractedA == extractedB);
        assertSame(receiver, extractedA.capturedReceiver());
        assertSame(parent, extractedA.methodHome().orElseThrow());

        parent.assignLocalSlot("pick", replacement);
        assertTrue(selection.stability().isValid());

        var extractedC = (ProtosClosureValue)
                read(receiver, selection);
        assertSame(receiver, extractedC.capturedReceiver());
        assertSame(parent, extractedC.methodHome().orElseThrow());
        assertSame(
                replacement.nativeBody().orElseThrow(),
                extractedC.nativeBody().orElseThrow());
        assertFalse(
                extractedA.nativeBody().orElseThrow()
                        == extractedC.nativeBody().orElseThrow());
    }

    @Test
    void closedAssignmentPreservesSelectionAndFrozenAssignmentFails() {
        var receiver = object();
        receiver.createLocalSlot("pick", new Object());
        var selection =
                ProtosValueLookup.lookupGuardedSlotSelection(receiver, "pick", null);
        assertNotNull(selection);

        receiver.close();
        Object updated = new Object();
        receiver.assignLocalSlot("pick", updated);
        assertTrue(selection.stability().isValid());
        assertSame(
                updated,
                read(receiver, selection));

        receiver.freeze();
        assertThrows(IllegalStateException.class,
                () -> receiver.assignLocalSlot("pick", new Object()));
        assertTrue(selection.stability().isValid());

        var context =
                new ProtosExecutionContextValue(ProtosObjectValue.rootObject());
        context.createLocalSlot("pick", new Object());
        assertNull(ProtosValueLookup.lookupGuardedSlotSelection(
                context, "pick", null));
    }

    private static ProtosPrelude corePrelude() throws IOException {
        return new ProtosCoreBootstrap().bootstrap(Path.of("protos", "lib", "core"));
    }

    private static ProtosIntegerValue integer(long value) {
        return new ProtosIntegerValue(value);
    }

    /** PERF016: admission is by representation family, never by value or identity. */
    @Test
    void integerFamilyAdmissionIsByRepresentationNotIdentity() throws IOException {
        var prelude = corePrelude();
        var first = integer(3);
        var second = integer(3);
        var huge = ProtosTestIntegers.integer(BigInteger.TWO.pow(200), prelude);
        for (Object receiver : new Object[] {first, second, huge}) {
            assertTrue(ProtosValueLookup.isInteger(receiver));
            assertNotNull(ProtosValueLookup.lookupGuardedInteger(receiver, "-", prelude));
        }
        assertFalse(ProtosValueLookup.isInteger(ProtosBooleanValue.TRUE));
        assertFalse(ProtosValueLookup.isInteger(new ProtosFloatValue(1.5d)));
        assertFalse(ProtosValueLookup.isInteger(
                new MutableRepresentedValue(prelude.integerPrototype())));
        assertFalse(ProtosValueLookup.isInteger(object()));
    }

    @Test
    void integerMinusSelectsIntegerPrototypeAndMatchesGenericLookup() throws IOException {
        var prelude = corePrelude();
        var guarded = ProtosValueLookup.lookupGuardedInteger(integer(10), "-", prelude);
        assertNotNull(guarded);
        assertTrue(guarded.stability().isValid());
        assertSame(prelude.integerPrototype(), guarded.selected().home());
        assertTrue(guarded.selected().value() instanceof ProtosClosureValue closure
                && closure.nativeBody().isPresent());

        // The selection made from one Integer is exactly the generic selection
        // for every other Integer, so it is reusable across fresh receivers.
        for (long value : new long[] {10, 9, 0, -7}) {
            var generic = ProtosValueLookup.lookup(integer(value), "-", prelude).orElseThrow();
            assertSame(generic.value(), guarded.selected().value());
            assertSame(generic.home(), guarded.selected().home());
        }
        assertTrue(guarded.stability().isValid());
    }

    @Test
    void integerGreaterSelectsNumberPrototypeAndMatchesGenericLookup() throws IOException {
        var prelude = corePrelude();
        var guarded = ProtosValueLookup.lookupGuardedInteger(integer(10), ">", prelude);
        assertNotNull(guarded);
        assertTrue(guarded.stability().isValid());
        assertSame(prelude.numberPrototype(), guarded.selected().home());
        assertTrue(guarded.selected().value() instanceof ProtosClosureValue closure
                && closure.nativeBody().isPresent());

        for (long value : new long[] {10, 9, 0, -7}) {
            var generic = ProtosValueLookup.lookup(integer(value), ">", prelude).orElseThrow();
            assertSame(generic.value(), guarded.selected().value());
            assertSame(generic.home(), guarded.selected().home());
        }
    }

    /**
     * D197-C: both concrete Integer families reach the inherited operations
     * with the canonical home (Integer for arithmetic, Number for ordering),
     * and a selection made from either family equals the generic selection for
     * the other, so one guarded selection may serve every Integer receiver.
     */
    @Test
    void concreteIntegerFamiliesShareInheritedGuardedSelections() throws IOException {
        var prelude = corePrelude();
        Object small = integer(10);
        Object big = ProtosTestIntegers.integer(BigInteger.TWO.pow(70), prelude);
        assertSame(prelude.smallIntegerPrototype(),
                ProtosValueLookup.delegationParent(small, prelude).orElseThrow());
        assertSame(prelude.bigIntegerPrototype(),
                ProtosValueLookup.delegationParent(big, prelude).orElseThrow());
        for (String selector : new String[] {"-", ">"}) {
            ProtosObjectValue expectedHome =
                    selector.equals("-") ? prelude.integerPrototype() : prelude.numberPrototype();
            var fromSmall = ProtosValueLookup.lookupGuardedInteger(small, selector, prelude);
            var fromBig = ProtosValueLookup.lookupGuardedInteger(big, selector, prelude);
            assertNotNull(fromSmall);
            assertNotNull(fromBig);
            assertSame(expectedHome, fromSmall.selected().home());
            assertSame(expectedHome, fromBig.selected().home());
            assertSame(fromSmall.selected().value(), fromBig.selected().value());
            for (Object receiver : new Object[] {small, big}) {
                var generic = ProtosValueLookup.lookup(receiver, selector, prelude).orElseThrow();
                assertSame(generic.value(), fromSmall.selected().value());
                assertSame(generic.home(), fromSmall.selected().home());
            }
        }
        // A family-local slot differs per family, so it is never shared by a guard.
        assertNull(ProtosValueLookup.lookupGuardedInteger(small, "recognizes", prelude));
        assertNull(ProtosValueLookup.lookupGuardedInteger(big, "recognizes", prelude));
    }

    @Test
    void integerGuardedSelectionRejectsAnotherPreludesLargeInteger() throws IOException {
        var prelude = corePrelude();
        var other = corePrelude();
        Object foreignBig = ProtosTestIntegers.integer(BigInteger.TWO.pow(70), other);
        assertNull(ProtosValueLookup.lookupGuardedInteger(foreignBig, "-", prelude));
        assertNotNull(ProtosValueLookup.lookupGuardedInteger(foreignBig, "-", other));
        // A large value whose parent is not a concrete-family prototype is rejected too.
        assertNull(ProtosValueLookup.lookupGuardedInteger(
                ProtosTestIntegers.integer(BigInteger.TWO.pow(70)), "-", prelude));
    }

    /**
     * The published standard graph is frozen by Core bootstrap, so selection
     * dependencies on Integer/Number/root Object cannot be mutated here; the
     * ordinary-chain invalidation contract itself is covered by the
     * lookupGuarded tests above, which share the same lookup loop.
     */
    @Test
    void integerGuardedSelectionDependsOnFrozenStandardGraph() throws IOException {
        var prelude = corePrelude();
        assertTrue(prelude.integerPrototype().isFrozen());
        assertTrue(prelude.smallIntegerPrototype().isFrozen());
        assertTrue(prelude.bigIntegerPrototype().isFrozen());
        assertTrue(prelude.numberPrototype().isFrozen());
        assertThrows(IllegalStateException.class,
                () -> prelude.numberPrototype().createLocalSlot(">", new Object()));
    }

    @Test
    void integerGuardedSelectionFallsBackForUnsupportedReceiversAndSelectors()
            throws IOException {
        var prelude = corePrelude();
        assertNull(ProtosValueLookup.lookupGuardedInteger(integer(1), "perf016Absent", prelude));
        assertNull(ProtosValueLookup.lookupGuardedInteger(integer(1), "-", null));
        assertNull(ProtosValueLookup.lookupGuardedInteger(new ProtosFloatValue(1.5d), "-", prelude));
        assertNull(ProtosValueLookup.lookupGuardedInteger(ProtosBooleanValue.TRUE, "-", prelude));
        assertNull(ProtosValueLookup.lookupGuardedInteger(object(), "-", prelude));
        assertNull(ProtosValueLookup.lookupGuardedInteger(
                new MutableRepresentedValue(prelude.integerPrototype()), "-", prelude));
        // The ordinary entry point and the Boolean entry point stay generic for Integer.
        assertNull(ProtosValueLookup.lookupGuarded(integer(1), "-", prelude));
        assertNull(ProtosValueLookup.lookupGuardedCanonicalBoolean(integer(1), "-", prelude));
    }

    private static final class MutableRepresentedValue implements ProtosRepresentedValue {
        private Object parent;

        MutableRepresentedValue(Object parent) {
            this.parent = parent;
        }

        @Override
        public Object representedDelegationParent(ProtosPrelude prelude) {
            return parent;
        }
    }
}
