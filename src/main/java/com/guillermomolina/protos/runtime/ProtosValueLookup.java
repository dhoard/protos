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

import com.guillermomolina.protos.runtime.lookup.ProtosSlotLookupResult;
import com.oracle.truffle.api.Assumption;
import com.oracle.truffle.api.CompilerAsserts;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.dsl.NonIdempotent;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.api.object.PropertyGetter;
import com.oracle.truffle.api.object.Property;
import com.oracle.truffle.api.object.Shape;
import java.util.ArrayList;
import java.util.Objects;
import java.util.Optional;

/**
 * Ordinary slot lookup across runtime representations whose semantic delegation
 * parent is supplied by the source-backed Core prelude, plus the structural
 * ({@code Shape}-based) property selection used by specialized attribute
 * reads.
 */
public final class ProtosValueLookup {
    private ProtosValueLookup() {}

    /**
     * A structural selection of an ordinary property along a delegation
     * chain, examining each object's own properties along the Protos
     * prototype delegation chain.
     *
     * <p>It records the ordinary objects examined from the lookup origin up to
     * and including the real slot owner ({@link #home()}), the {@code Shape}
     * observed for each of them, and a {@link PropertyGetter} for the
     * selected property in the owner's {@code Shape}. It never records the
     * property value: every read loads the owner's current physical location.
     *
     * <p>Delegation parents are immutable, so the selection stays exact while
     * every examined object still has its observed {@code Shape}: a nearer
     * shadowing property, a removal, or any other structural change of an
     * examined object is a {@code Shape} transition that makes
     * {@link #isValid()} fail, forcing respecialization or the generic path.
     *
     * <p>It also exposes the final-value {@link Assumption} of the selected
     * property's storage location ({@link #finalAssumption()}), obtained when
     * the selection was made. Object storage invalidates it when that
     * location is rewritten in place, so while it is valid and
     * {@link #isValid()} holds, the selected value has not changed. It never
     * replaces {@link #isValid()}.
     */
    public static final class StructuralSelection {
        @CompilationFinal(dimensions = 1)
        private final ProtosObjectValue[] chain;
        @CompilationFinal(dimensions = 1)
        private final Shape[] shapes;
        private final PropertyGetter getter;
        private final Assumption finalAssumption;

        private StructuralSelection(
                ProtosObjectValue[] chain,
                Shape[] shapes,
                PropertyGetter getter,
                Assumption finalAssumption) {
            this.chain = chain;
            this.shapes = shapes;
            this.getter = getter;
            this.finalAssumption = finalAssumption;
        }

        /**
         * The final-value assumption of the selected property's location, as
         * obtained when the selection was made. It may already be invalid
         * when the location had been rewritten before; a valid one stays
         * valid only until the location is rewritten in place.
         */
        public Assumption finalAssumption() {
            return finalAssumption;
        }

        /** The object the lookup started at. */
        public ProtosObjectValue origin() {
            return chain[0];
        }

        /** The real owner of the selected slot. */
        public ProtosObjectValue home() {
            return chain[chain.length - 1];
        }

        /**
         * Whether every examined object, identified by its exact identity
         * (never by Shape alone), still has its observed Shape. Two chains
         * with equal Shapes but different objects never share a selection,
         * so {@link #home()} is always the real owner.
         */
        @NonIdempotent
        @ExplodeLoop
        public boolean isValid() {
            for (int index = 0; index < chain.length; index++) {
                if (!shapes[index].check(chain[index])) {
                    return false;
                }
            }
            return true;
        }

        /** The current value of the selected property. Requires {@link #isValid()}. */
        public Object read() {
            return getter.get(home());
        }

        /**
         * Whether every examined object of the captured chain is FROZEN. Such
         * objects can neither change their slots nor their parents, so while
         * {@link #isValid()} holds the selected value never changes. Inspects
         * only the chain observed when the selection was made: the proof rests
         * on the captured Shapes, each of which must carry the FROZEN state
         * and still describe its object. A FROZEN Shape is final, so a chain
         * proven here keeps its selection for good. Specialization-time only.
         */
        @ExplodeLoop
        public boolean allOwnersFrozen() {
            for (int index = 0; index < chain.length; index++) {
                if (!ProtosObjectValue.isFrozenShape(shapes[index])
                        || !shapes[index].check(chain[index])) {
                    return false;
                }
            }
            return true;
        }
    }

    /**
     * Whether {@code receiver} is an exact ordinary object, the only
     * representation admitted by structural attribute specializations.
     */
    public static boolean isOrdinaryObject(Object receiver) {
        return receiver != null && receiver.getClass() == ProtosObjectValue.class;
    }

    /**
     * The
     * getter of {@code name} in {@code shape}, or {@code null} when absent.
     * Obtaining the location's final assumption lets compiled reads of a
     * constant receiver fold while the location is never rewritten; the value
     * itself is never cached. Specialization-time only.
     */
    public static PropertyGetter propertyGetterWithFinalAssumption(Shape shape, String name) {
        CompilerAsserts.neverPartOfCompilation();
        PropertyGetter getter = shape.makePropertyGetter(name);
        if (getter != null) {
            Property property = shape.getProperty(name);
            if (property != null) {
                property.getLocation().getFinalAssumption();
            }
        }
        return getter;
    }

    /** Whether {@code shape} describes a local property {@code name}. */
    public static boolean hasProperty(Shape shape, String name) {
        return shape.getProperty(name) != null;
    }

    /**
     * Selects {@code name} structurally starting at {@code origin}, or returns
     * {@code null} when it is absent or some examined object does not store
     * its slots in its own {@code Shape}. Specialization-time only.
     */
    @TruffleBoundary
    public static StructuralSelection selectStructural(Object origin, String name) {
        Objects.requireNonNull(name, "name");
        ArrayList<ProtosObjectValue> chain = new ArrayList<>();
        ArrayList<Shape> shapes = new ArrayList<>();
        Object current = origin;
        while (current instanceof ProtosObjectValue object
                && object.usesObjectStorageForRuntime()
                && !ProtosNumericValueSupport.isCurrentNumber(object)) {
            Shape shape = object.getShape();
            chain.add(object);
            shapes.add(shape);
            if (hasProperty(shape, name)) {
                PropertyGetter getter = shape.makePropertyGetter(name);
                Property property = shape.getProperty(name);
                if (getter == null || property == null) {
                    return null;
                }
                return new StructuralSelection(
                        chain.toArray(ProtosObjectValue[]::new),
                        shapes.toArray(Shape[]::new),
                        getter,
                        property.getLocation().getFinalAssumption());
            }
            current = object.directParentForGuardedLookup();
        }
        return null;
    }

    /**
     * The structural selection of an inherited {@code name} for an exact
     * ordinary receiver that does not own it, starting at the receiver's
     * direct parent so it can be shared by every receiver with that parent.
     * Specialization-time only.
     */
    @TruffleBoundary
    public static StructuralSelection selectInheritedStructural(Object receiver, String name) {
        if (!isOrdinaryObject(receiver)) {
            return null;
        }
        ProtosObjectValue ordinary = (ProtosObjectValue) receiver;
        if (hasProperty(ordinary.getShape(), name)) {
            return null;
        }
        return selectStructural(ordinary.directParentForGuardedLookup(), name);
    }

    /**
     * Whether {@code receiver}'s immutable direct parent is the origin of the
     * shared inherited {@code selection}.
     */
    public static boolean hasSelectionParent(
            ProtosObjectValue receiver, StructuralSelection selection) {
        return receiver.directParentForGuardedLookup() == selection.origin();
    }

    /**
     * Fresh member extraction of a structurally read value: a Closure is bound
     * to the original receiver and the real slot owner on every read; any
     * other value is returned as stored.
     */
    public static Object extractMember(Object receiver, ProtosObjectValue home, Object value) {
        if (value instanceof ProtosClosureValue closure) {
            return closure.bindMethod(receiver, home);
        }
        return value;
    }

    /**
     * Returns whether {@code value} is a Protos runtime value: an ordinary
     * object or a represented value. Host values that merely cross an interop
     * boundary are not Protos values and are never admitted by this test.
     */
    public static boolean isProtosValue(Object value) {
        return value instanceof Long
                || value instanceof Double
                || value instanceof Boolean
                || value instanceof ProtosObjectValue
                || value instanceof ProtosRepresentedValue;
    }

    public static Optional<ProtosSlotLookupResult> lookup(
            Object receiver,
            String name,
            ProtosPrelude prelude) {
        Objects.requireNonNull(receiver, "receiver");
        Objects.requireNonNull(name, "name");

        if (receiver instanceof ProtosForeignProjectedReceiver foreign) {
            return lookupForeign(foreign, name, prelude);
        }
        return lookupOrdinaryChain(receiver, name, prelude);
    }

    /**
     * Ordinary slot lookup through the immutable delegation chain starting at {@code origin},
     * without the D188 receiver projection. Foreign projections use this entry point to
     * consult the ordinary Protos-facing chain of a foreign receiver before any foreign fallback.
     */
    public static Optional<ProtosSlotLookupResult> lookupOrdinaryChain(
            Object origin,
            String name,
            ProtosPrelude prelude) {
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(name, "name");
        Object current = origin;
        while (true) {
            if (current instanceof ProtosObjectValue ordinary) {
                Optional<Object> local = ordinary.readLocalSlot(name);
                if (local.isPresent()) {
                    // TEST009-AC: local is present here, so orElse(null) yields its non-null
                    // value without a PE-visible NoSuchElementException path.
                    return Optional.of(new ProtosSlotLookupResult(local.orElse(null), ordinary));
                }
            }

            Optional<Object> parent = delegationParent(current, prelude);
            if (parent.isEmpty()) {
                return Optional.empty();
            }
            current = parent.orElseThrow();
        }
    }

    @TruffleBoundary
    private static Optional<ProtosSlotLookupResult> lookupForeign(
            ProtosForeignProjectedReceiver foreign,
            String name,
            ProtosPrelude prelude) {
        return foreign.lookupForeignMemberForRuntime(name, prelude);
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
        if (receiver instanceof Long) {
            return Optional.of(
                    ProtosRepresentedValue.requirePrelude(prelude, "SmallInteger")
                            .smallIntegerPrototype());
        }
        // A Double receiver is a Float; regular Float lookup
        // delegates through the observing Prelude's Float prototype.
        if (receiver instanceof Double) {
            return Optional.of(Objects.requireNonNull(prelude,
                    "Float lookup requires a Core prelude").floatPrototype());
        }
        // A Boolean is a Java boolean and delegates directly to the root Object.
        if (receiver instanceof Boolean) {
            return Optional.of(ProtosObjectValue.rootObject());
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

    /** Generic semantic member extraction of a completed lookup. */
    public static Object materializeMemberRead(
            Object receiver,
            ProtosSlotLookupResult result) {
        return extractMember(receiver, result.home(), result.value());
    }
}
