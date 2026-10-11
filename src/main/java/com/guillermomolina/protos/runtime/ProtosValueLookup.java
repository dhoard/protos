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

import com.oracle.truffle.api.Assumption;
import com.oracle.truffle.api.CompilerAsserts;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.profiles.InlinedBranchProfile;
import java.util.Objects;
import java.util.Optional;

/**
 * Ordinary slot lookup across runtime representations whose semantic delegation
 * parent is supplied by the source-backed Core prelude.
 */
public final class ProtosValueLookup {
    private ProtosValueLookup() {}

    /**
     * A D013 selection protected by one dependency shared by every visited
     * ordinary object, including nearer objects where the selector is absent.
     * Checking stability never traverses the delegation chain.
     */
    public record GuardedLookup(
            ProtosSlotLookupResult selected,
            Assumption stability) {}

    /**
     * PERF025-H3A shared inherited selection. The exact direct parent is the
     * shareable semantic anchor; the guarded lookup starts at that parent, so
     * sibling receivers are not registered as dependencies. Each actual
     * receiver separately proves local absence of the selector on every hit.
     */
    public record SharedInheritedLookup(
            Object exactParent,
            GuardedLookup parentLookup) {
        public SharedInheritedLookup {
            Objects.requireNonNull(exactParent, "exactParent");
            Objects.requireNonNull(parentLookup, "parentLookup");
        }

        public ProtosSlotLookupResult selected() {
            return parentLookup.selected();
        }

        public Assumption stability() {
            return parentLookup.stability();
        }
    }


    /**
     * A guarded choice of slot owner. Its value is deliberately not cached.
     * The same owner remains usable across successful local-slot assignments.
     *
     * <p>PERF037-B: {@code location} is the selected binding's stable
     * physical location in {@code home}'s single authority, so a valid hit
     * loads the current value without a nominal lookup. Assignments write
     * through it; every mutation that could detach it (removal, recreation)
     * invalidates {@code stability} first.
     */
    public record GuardedSlotSelection(
            ProtosObjectValue home,
            ProtosMapBackedLexicalBindingAuthority.SlotCell location,
            Assumption stability,
            Assumption nonClosureContinuity) {
        public GuardedSlotSelection {
            Objects.requireNonNull(home, "home");
            Objects.requireNonNull(location, "location");
            Objects.requireNonNull(stability, "stability");
        }
    }

    /**
     * An inherited selection shared by receivers with an identical parent.
     * Each receiver still proves that it does not shadow the selected name.
     */
    public record SharedInheritedSlotSelection(
            Object exactParent,
            GuardedSlotSelection parentSelection) {
        public SharedInheritedSlotSelection {
            Objects.requireNonNull(exactParent, "exactParent");
            Objects.requireNonNull(parentSelection, "parentSelection");
        }

        public Assumption stability() {
            return parentSelection.stability();
        }

        public Assumption nonClosureContinuity() {
            return parentSelection.nonClosureContinuity();
        }
    }

    public static GuardedSlotSelection lookupGuardedSlotSelection(
            Object receiver,
            String name,
            ProtosPrelude prelude) {
        CompilerAsserts.neverPartOfCompilation();
        Assumption stability =
                Truffle.getRuntime().createAssumption("Protos slot selection");
        Optional<ProtosSlotLookupResult> selected =
                lookup(receiver, name, prelude, stability, false, true);
        if (selected.isEmpty() || !stability.isValid()) {
            stability.invalidate();
            return null;
        }
        ProtosObjectValue home = selected.orElseThrow().home();
        ProtosMapBackedLexicalBindingAuthority.SlotCell location =
                home.slotCellForGuardedRead(name);
        if (location == null) {
            stability.invalidate();
            return null;
        }
        return new GuardedSlotSelection(
                home,
                location,
                stability,
                location.nonClosureContinuityForGuardedRead());
    }

    public static SharedInheritedSlotSelection lookupGuardedSharedInheritedSlotSelection(
            Object receiver,
            String name,
            ProtosPrelude prelude) {
        CompilerAsserts.neverPartOfCompilation();
        Objects.requireNonNull(name, "name");
        if (receiver == null || receiver.getClass() != ProtosObjectValue.class) {
            return null;
        }

        ProtosObjectValue ordinary = (ProtosObjectValue) receiver;
        if (ordinary.hasLocalSlot(name)) {
            return null;
        }

        Object parent = ordinary.directParentForGuardedLookup();
        if (parent == null) {
            return null;
        }

        try {
            GuardedSlotSelection selected =
                    lookupGuardedSlotSelection(parent, name, prelude);
            return selected == null
                    ? null
                    : new SharedInheritedSlotSelection(parent, selected);
        } catch (UnsupportedOperationException unsupportedRepresentation) {
            return null;
        }
    }

    public static boolean matchesGuardedSharedInheritedSlotSelection(
            Object receiver,
            String name,
            SharedInheritedSlotSelection cached) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(cached, "cached");
        if (receiver == null || receiver.getClass() != ProtosObjectValue.class) {
            return false;
        }
        ProtosObjectValue ordinary = (ProtosObjectValue) receiver;
        return ordinary.directParentForGuardedLookup() == cached.exactParent()
                && !ordinary.hasLocalSlot(name);
    }

    /**
     * Establishes a cache entry using the same lookup implementation as the
     * generic path. This is specialization-time work, never valid-hit work.
     *
     * <p>Attribute-specific assumption invalidation follows the established
     * Truffle runtime pattern used by GraalPy's MRO attribute caches.
     * Protos parents are immutable; selector mutations are the dependencies
     * for admitted ordinary chains. Represented values and subclass storage
     * remain unsupported here and use generic lookup.
     *
     * @return a protected selection, or null when absent or unsupported
     */
    public static GuardedLookup lookupGuarded(
            Object receiver,
            String name,
            ProtosPrelude prelude) {
        CompilerAsserts.neverPartOfCompilation();
        Assumption stability =
                Truffle.getRuntime().createAssumption("Protos selected slot");
        Optional<ProtosSlotLookupResult> selected =
                lookup(receiver, name, prelude, stability);
        if (selected.isEmpty() || !stability.isValid()) {
            stability.invalidate();
            return null;
        }
        return new GuardedLookup(selected.orElseThrow(), stability);
    }

    /**
     * Establishes a shared inherited-member selection for an exact ordinary
     * receiver. The receiver itself must not own {@code name}; lookup and D013
     * dependency registration begin at its exact direct parent.
     *
     * <p>This deliberately shares only inherited selection. Own-slot reads stay
     * on the exact-receiver PIC because ordinary slot storage is still
     * String-keyed LinkedHashMap storage rather than a shared ordinal layout.
     *
     * @return a shared inherited selection, or null when the shape is not
     *         safely admitted
     */
    public static SharedInheritedLookup lookupGuardedSharedInherited(
            Object receiver,
            String name,
            ProtosPrelude prelude) {
        CompilerAsserts.neverPartOfCompilation();
        Objects.requireNonNull(name, "name");

        if (receiver == null || receiver.getClass() != ProtosObjectValue.class) {
            return null;
        }

        ProtosObjectValue ordinary = (ProtosObjectValue) receiver;
        if (ordinary.hasLocalSlot(name)) {
            return null;
        }

        Object parent = ordinary.directParentForGuardedLookup();
        if (parent == null) {
            return null;
        }

        try {
            GuardedLookup parentLookup =
                    lookupGuarded(parent, name, prelude);
            if (parentLookup == null) {
                return null;
            }
            return new SharedInheritedLookup(parent, parentLookup);
        } catch (UnsupportedOperationException unsupportedRepresentation) {
            return null;
        }
    }

    /**
     * Valid-hit guard for {@link #lookupGuardedSharedInherited}. The exact
     * parent identity is immutable; local selector absence is intentionally
     * checked per receiver so one sibling's shadowing does not invalidate the
     * shared parent-chain selection for every other sibling.
     */
    public static boolean matchesGuardedSharedInherited(
            Object receiver,
            String name,
            SharedInheritedLookup cachedLookup) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(cachedLookup, "cachedLookup");

        if (receiver == null || receiver.getClass() != ProtosObjectValue.class) {
            return false;
        }

        ProtosObjectValue ordinary = (ProtosObjectValue) receiver;
        return ordinary.directParentForGuardedLookup()
                        == cachedLookup.exactParent()
                && !ordinary.hasLocalSlot(name);
    }

    /**
     * Returns whether {@code receiver} is one of the two canonical Boolean
     * identities, the only represented values admitted by
     * {@link #lookupGuardedCanonicalBoolean}.
     */
    public static boolean isCanonicalBoolean(Object receiver) {
        return receiver == ProtosBooleanValue.TRUE || receiver == ProtosBooleanValue.FALSE;
    }

    /**
     * Guarded D013 selection for a canonical {@code true}/{@code false}
     * receiver. Canonical Booleans are represented values with no local slots
     * whose delegation parent is fixed by their representation to the root
     * Object, so the only mutable selection dependencies are the ordinary
     * objects visited from that parent. This runs the same lookup loop as
     * {@link #lookupGuarded}; only the receiver's own represented step is
     * exempt from invalidation. Any other receiver, including any other
     * represented value, is unsupported here and generic lookup remains
     * authoritative.
     *
     * @return a protected selection, or null when absent or unsupported
     */
    public static GuardedLookup lookupGuardedCanonicalBoolean(
            Object receiver,
            String name,
            ProtosPrelude prelude) {
        CompilerAsserts.neverPartOfCompilation();
        if (!isCanonicalBoolean(receiver)
                || delegationParent(receiver, prelude).orElse(null)
                        != ProtosObjectValue.rootObject()) {
            return null;
        }
        Assumption stability =
                Truffle.getRuntime().createAssumption("Protos selected Boolean slot");
        Optional<ProtosSlotLookupResult> selected =
                lookup(receiver, name, prelude, stability, true);
        if (selected.isEmpty() || !stability.isValid()) {
            stability.invalidate();
            return null;
        }
        return new GuardedLookup(selected.orElseThrow(), stability);
    }

    /**
     * Returns whether {@code receiver} belongs to the semantic Integer
     * representation family, the only represented values admitted by
     * {@link #lookupGuardedInteger}. Membership is by representation, not by
     * value or identity: every {@link ProtosIntegerValue} is an immutable
     * carrier with no local slots whose delegation parent is fixed by its
     * representation contract to the owning prelude's SmallInteger prototype
     * (D197-C), which delegates to the common Integer prototype.
     */
    public static boolean isInteger(Object receiver) {
        /*
         * I091: a large Integer is a FROZEN slotless ordinary object whose parent is its minting
         * Prelude's BigInteger prototype. It is never observed outside that Prelude (every
         * cross-domain path rematerializes it), so its own lookup step is as fixed as the
         * represented step of a signed-64 Integer.
         */
        return ProtosNumericValueSupport.isCurrentInteger(receiver);
    }

    /**
     * Returns whether {@code value} is a Protos runtime value: an ordinary
     * object or a represented value. Host values that merely cross an interop
     * boundary are not Protos values and are never admitted by this test.
     */
    public static boolean isProtosValue(Object value) {
        return value instanceof ProtosObjectValue
                || value instanceof ProtosRepresentedValue;
    }

    /**
     * Guarded D013 selection for a semantic Integer receiver. The receiver's
     * immediate parent must be exactly the prelude's prototype of its concrete
     * D197-C family (SmallInteger for a signed-64 value, BigInteger otherwise);
     * the lookup then continues ordinarily through Integer, Number and the root
     * Object. This runs the same lookup loop as {@link #lookupGuarded}; only the
     * receiver's own represented step is exempt from invalidation.
     *
     * <p>Callers share one selection across every Integer receiver evaluated
     * against the same prelude, whatever its concrete family. That is sound
     * only when the selection does not depend on the family: both concrete
     * prototypes must be frozen and neither may define {@code name} locally,
     * so the lookup from either family reaches the same slot with the same
     * home. A family-local slot (for example {@code recognizes}) is therefore
     * unsupported here and generic lookup remains authoritative, as it does for
     * any other receiver, including any other represented value.
     *
     * @return a protected selection, or null when absent or unsupported
     */
    /** Guarded D013 Float selection, symmetric with Integer lookup. */
    public static GuardedLookup lookupGuardedFloat(
            Object receiver, String name, ProtosPrelude prelude) {
        CompilerAsserts.neverPartOfCompilation();
        if (!ProtosNumericValueSupport.isCurrentFloat(receiver)
                || prelude == null
                || delegationParent(receiver, prelude).orElse(null)
                        != prelude.floatPrototype()) {
            return null;
        }
        Assumption stability =
                Truffle.getRuntime().createAssumption("Protos selected Float slot");
        Optional<ProtosSlotLookupResult> selected =
                lookup(receiver, name, prelude, stability, true);
        if (selected.isEmpty() || !stability.isValid()) {
            stability.invalidate();
            return null;
        }
        return new GuardedLookup(selected.orElseThrow(), stability);
    }

    public static GuardedLookup lookupGuardedInteger(
            Object receiver,
            String name,
            ProtosPrelude prelude) {
        CompilerAsserts.neverPartOfCompilation();
        if (!isInteger(receiver) || prelude == null) {
            return null;
        }
        ProtosObjectValue smallIntegerPrototype = prelude.smallIntegerPrototype();
        ProtosObjectValue bigIntegerPrototype = prelude.bigIntegerPrototype();
        ProtosObjectValue expectedParent =
                ProtosNumericValueSupport.isLargeInteger(receiver)
                        ? bigIntegerPrototype
                        : smallIntegerPrototype;
        if (delegationParent(receiver, prelude).orElse(null) != expectedParent
                || !isFamilyIndependent(smallIntegerPrototype, name)
                || !isFamilyIndependent(bigIntegerPrototype, name)) {
            return null;
        }
        Assumption stability =
                Truffle.getRuntime().createAssumption("Protos selected Integer slot");
        Optional<ProtosSlotLookupResult> selected =
                lookup(receiver, name, prelude, stability, true);
        if (selected.isEmpty() || !stability.isValid()) {
            stability.invalidate();
            return null;
        }
        return new GuardedLookup(selected.orElseThrow(), stability);
    }

    private static boolean isFamilyIndependent(
            ProtosObjectValue concreteIntegerPrototype, String name) {
        return concreteIntegerPrototype.isFrozen()
                && !concreteIntegerPrototype.hasLocalSlot(name);
    }

    public static Optional<ProtosSlotLookupResult> lookup(
            Object receiver,
            String name,
            ProtosPrelude prelude) {
        return lookup(receiver, name, prelude, null);
    }

    private static Optional<ProtosSlotLookupResult> lookup(
            Object receiver,
            String name,
            ProtosPrelude prelude,
            Assumption stability) {
        return lookup(receiver, name, prelude, stability, false);
    }

    private static Optional<ProtosSlotLookupResult> lookup(
            Object receiver,
            String name,
            ProtosPrelude prelude,
            Assumption stability,
            // Set only by a family-specific guarded entry point that has already
            // proven the receiver's own represented step is fixed.
            boolean admitRepresentedReceiverStep) {
        return lookup(
                receiver, name, prelude, stability,
                admitRepresentedReceiverStep, false);
    }

    private static Optional<ProtosSlotLookupResult> lookup(
            Object receiver,
            String name,
            ProtosPrelude prelude,
            Assumption stability,
            boolean admitRepresentedReceiverStep,
            boolean selectionOnly) {
        Objects.requireNonNull(receiver, "receiver");
        Objects.requireNonNull(name, "name");

        if (receiver instanceof ProtosForeignProjectedReceiver foreign) {
            if (stability != null) {
                // D188 projections and foreign-member fallback are never D013 selections:
                // guarded callers must stay on the generic path and must not enter foreign code.
                stability.invalidate();
                return Optional.empty();
            }
            return lookupForeign(foreign, name, prelude);
        }
        return lookupOrdinaryChain(
                receiver, name, prelude, stability,
                admitRepresentedReceiverStep, selectionOnly);
    }

    /**
     * Ordinary slot lookup through the immutable delegation chain starting at {@code origin},
     * without the D188 receiver projection. Only foreign projections use this entry point, to
     * consult the ordinary Protos-facing chain of a foreign receiver before any foreign fallback.
     */
    public static Optional<ProtosSlotLookupResult> lookupOrdinaryChain(
            Object origin,
            String name,
            ProtosPrelude prelude) {
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(name, "name");
        return lookupOrdinaryChain(origin, name, prelude, null, false, false);
    }

    @TruffleBoundary
    private static Optional<ProtosSlotLookupResult> lookupForeign(
            ProtosForeignProjectedReceiver foreign,
            String name,
            ProtosPrelude prelude) {
        return foreign.lookupForeignMemberForRuntime(name, prelude);
    }

    private static Optional<ProtosSlotLookupResult> lookupOrdinaryChain(
            Object receiver,
            String name,
            ProtosPrelude prelude,
            Assumption stability,
            boolean admitRepresentedReceiverStep,
            boolean selectionOnly) {
        Object current = receiver;
        while (true) {
            if (current instanceof ProtosObjectValue ordinary) {
                if (stability != null
                        && !(admitRepresentedReceiverStep
                                && current == receiver
                                && ProtosNumericValueSupport.isLargeInteger(current))
                        && !(selectionOnly
                                ? ordinary.trackSlotSelectionDependency(name, stability)
                                : ordinary.trackLookupDependency(name, stability))) {
                    stability.invalidate();
                }
                Optional<Object> local = ordinary.readLocalSlot(name);
                if (local.isPresent()) {
                    // TEST009-AC: local is present here, so orElse(null) yields its non-null
                    // value without a PE-visible NoSuchElementException path.
                    return Optional.of(new ProtosSlotLookupResult(local.orElse(null), ordinary));
                }
            } else if (stability != null
                    && !(admitRepresentedReceiverStep && current == receiver)) {
                stability.invalidate();
            }

            Optional<Object> parent = delegationParent(current, prelude);
            if (parent.isEmpty()) {
                return Optional.empty();
            }
            current = parent.orElseThrow();
        }
    }

    /**
     * Returns the semantic immediate delegation parent used by ordinary lookup.
     *
     * <p>The unique root Object has no parent. Represented semantic values delegate through the
     * parent supplied by their representation contract. The prelude may be {@code null} for a
     * representation whose contract does not require a Core prototype.
     */
    public static Optional<Object> delegationParent(
            Object receiver,
            ProtosPrelude prelude) {
        Objects.requireNonNull(receiver, "receiver");

        if (receiver instanceof ProtosObjectValue ordinary) {
            return ordinary.parent();
        }
        if (receiver instanceof ProtosRepresentedValue represented) {
            return Optional.of(
                    Objects.requireNonNull(
                            representedDelegationParent(represented, prelude),
                            "represented delegation parent"));
        }
        CompilerDirectives.transferToInterpreter();
        throw new UnsupportedOperationException(
                "Standard delegation parent is not implemented for runtime value representation "
                        + receiver.getClass().getName());
    }

    /*
     * TEST009-M: the represented families do not share one parent rule (root
     * Object, a Prelude prototype, or an instance-retained prototype), so the
     * megamorphic interface call is kept out of partial evaluation.
     */
    @TruffleBoundary
    private static Object representedDelegationParent(
            ProtosRepresentedValue represented,
            ProtosPrelude prelude) {
        return represented.representedDelegationParent(prelude);
    }

    /**
     * Performs one ordinary member read after lookup, including method-Closure
     * binding to the original receiver and the selected slot home.
     */
    public static Optional<Object> readMember(
            Object receiver,
            String name,
            ProtosPrelude prelude) {
        Optional<ProtosSlotLookupResult> result = lookup(receiver, name, prelude);
        if (result.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(materializeMemberRead(receiver, result.orElseThrow()));
    }


    /**
     * Reads the current value at a selection-protected home. The Assumption
     * guards the name resolution, so this neither traverses delegation nor
     * looks the name up again: the value is loaded from the selection's
     * stable location. Closure extraction remains fresh and binds the
     * original receiver; {@code closureExtraction} keeps that branch out of
     * compiled code until a selected slot actually holds a Closure.
     */
    /**
     * PERF037: fast path for a selected ordinary data slot.
     *
     * Callers must hold both the selection stability assumption and
     * its non-Closure continuity assumption as Truffle specialization
     * assumptions. The stored value is never cached; assignments
     * remain visible through the selected physical location.
     *
     * A data-to-Closure assignment invalidates the latter assumption
     * before publishing the new value, forcing specialization
     * transfer to the general fresh-extraction path.
     */
    public static Object guardedPlainSlotValue(
            GuardedSlotSelection selection) {
        return selection.location().value();
    }

    public static Object materializeGuardedMemberRead(
            Object receiver,
            GuardedSlotSelection selection,
            Node node,
            InlinedBranchProfile closureExtraction) {
        Assumption nonClosure = selection.nonClosureContinuity();
        if (nonClosure != null && nonClosure.isValid()) {
            /*
             * The selected cell is still the authoritative value source.
             * Only the value's non-Closure representation is protected;
             * writes of other data values remain observable immediately.
             */
            return selection.location().value();
        }

        Object value = selection.location().value();
        if (value instanceof ProtosClosureValue closure) {
            closureExtraction.enter(node);
            return closure.bindMethod(receiver, selection.home());
        }
        return value;
    }

    public static Object materializeGuardedMemberRead(
            Object receiver,
            SharedInheritedSlotSelection selection,
            Node node,
            InlinedBranchProfile closureExtraction) {
        return materializeGuardedMemberRead(
                receiver, selection.parentSelection(), node, closureExtraction);
    }

    public static Object materializeMemberRead(
            Object receiver,
            ProtosSlotLookupResult result) {
        Object value = result.value();
        if (value instanceof ProtosClosureValue closure) {
            return closure.bindMethod(receiver, result.home());
        }
        return value;
    }
}
