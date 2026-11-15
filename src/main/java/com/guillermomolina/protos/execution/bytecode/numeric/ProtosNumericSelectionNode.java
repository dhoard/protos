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

import com.guillermomolina.protos.runtime.ProtosClosureValue;
import com.guillermomolina.protos.runtime.ProtosNumericValueSupport;
import com.guillermomolina.protos.runtime.ProtosObjectValue;
import com.guillermomolina.protos.runtime.ProtosPrelude;
import com.guillermomolina.protos.runtime.ProtosStringValue;
import com.guillermomolina.protos.runtime.ProtosValueLookup;
import com.oracle.truffle.api.Assumption;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.dsl.Bind;
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.GenerateInline;
import com.oracle.truffle.api.dsl.GenerateUncached;
import com.oracle.truffle.api.dsl.NonIdempotent;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.Node;

/**
 * Answers whether the behavior actually selected for a numeric or String
 * operator selector on an evaluated receiver, under the caller's Prelude, is
 * exactly the standard behavior. Nothing is invoked.
 *
 * <p>The cached entries (at most three) are keyed by the selector, the
 * Prelude identity and the identity of the receiver's selection origin (its
 * delegation prototype, or the receiver itself when it is an object), and
 * guarded by the structural selection of the selector along that chain. A
 * structural change anywhere along the chain invalidates the selection and
 * misses the entry. A chain whose owners are all FROZEN cannot change its
 * selected behavior while the selection stays valid, so its classification
 * is cached; a mutable chain reads and classifies the current slot value on
 * every execution, so a reassignment that keeps every Shape is observed.
 *
 * <p>A {@code long} (SmallInteger) or {@code double} (Float) receiver has
 * typed entries that resolve the Prelude prototype only when the entry is
 * installed, through the Prelude's own local prototype binding, which must be
 * FROZEN. Such an entry is keyed by the selector and the Prelude identity.
 * When every owner along the operator chain is FROZEN as well, the
 * classification is proven at installation and returned without any
 * structural check. Otherwise the entry is guarded by the structural
 * selection of the operator along the prototype's chain and by the selected
 * location's final-value assumption: a reassignment of the selected slot that
 * keeps every Shape and any structural change along the chain invalidate the
 * entry, so its cached classification is never reused after the selected
 * behavior could have changed. When that assumption is no longer valid the
 * typed entry is not installed and the general entries apply.
 *
 * <p>A receiver that is neither a real Number nor a String, or a frame
 * without an admitted Prelude, is never standard.
 */
@GenerateUncached
@GenerateInline(false)
abstract class ProtosNumericSelectionNode extends Node {

    abstract boolean execute(VirtualFrame frame, String selector, Object receiver);

    static final String SMALL_INTEGER_BINDING = "SmallInteger";
    static final String FLOAT_BINDING = "Float";

    /*
     * A SmallInteger receiver of a compact invocation whose own Closure captured
     * its Prelude, read from the Closure's final field without the general
     * Prelude resolution. Once the invocation publishes its activation, or
     * when the Closure captured no Prelude, the bound Prelude is null and the
     * general entries resolve it, keeping caller inheritance. The frozen
     * classification is proven at installation for this Prelude identity.
     */
    @Specialization(
            excludeForUncached = true,
            guards = {
                "prelude != null",
                "selector == cachedSelector",
                "prelude == cachedPrelude",
                "frozen"
            },
            limit = "3")
    static boolean doCapturedFrozenLong(
            VirtualFrame frame,
            String selector,
            long receiver,
            @Bind("capturedPrelude(frame)") ProtosPrelude prelude,
            @Cached("selector") String cachedSelector,
            @Cached("prelude") ProtosPrelude cachedPrelude,
            @Cached("selectNumeric(frozenBoundPrototype(selectBinding(cachedPrelude, SMALL_INTEGER_BINDING)), cachedSelector)")
                    ProtosValueLookup.StructuralSelection selection,
            @Cached(value = "allOwnersFrozen(selection)", neverDefault = false) boolean frozen,
            @Cached(value = "frozenStandard(frozen, cachedSelector, receiver, selection, cachedPrelude)",
                    neverDefault = false) boolean standard) {
        return standard;
    }

    /*
     * A SmallInteger receiver of a compact invocation whose own Closure captured
     * its Prelude, with a mutable owner along the operator chain. The
     * selection is checked structurally on each execution and the selected
     * location's final-value assumption invalidates the entry when the slot
     * is rewritten in place; an already invalid assumption is not installed.
     */
    @Specialization(
            excludeForUncached = true,
            guards = {
                "prelude != null",
                "selector == cachedSelector",
                "prelude == cachedPrelude",
                "selection != null",
                "selection.isValid()"
            },
            assumptions = "selectionAssumption",
            limit = "3")
    static boolean doCapturedMutableLong(
            VirtualFrame frame,
            String selector,
            long receiver,
            @Bind("capturedPrelude(frame)") ProtosPrelude prelude,
            @Cached("selector") String cachedSelector,
            @Cached("prelude") ProtosPrelude cachedPrelude,
            @Cached("selectNumeric(frozenBoundPrototype(selectBinding(cachedPrelude, SMALL_INTEGER_BINDING)), cachedSelector)")
                    ProtosValueLookup.StructuralSelection selection,
            @Cached("finalAssumption(selection)") Assumption selectionAssumption,
            @Cached(value = "stableStandard(cachedSelector, receiver, selection, cachedPrelude)",
                    neverDefault = false) boolean standard) {
        return standard;
    }

    /*
     * A SmallInteger receiver under an observed Prelude whose local SmallInteger binding
     * and whose whole operator chain are FROZEN. Frozen objects never change
     * their slots, parents or Shapes, so the classification computed when the
     * entry is installed stays exact for this Prelude identity; only the
     * Prelude and the selector are checked on each execution.
     */
    @Specialization(
            excludeForUncached = true,
            guards = {
                "selector == cachedSelector",
                "prelude == cachedPrelude",
                "frozen"
            },
            limit = "3")
    static boolean doFrozenLong(
            VirtualFrame frame,
            String selector,
            long receiver,
            @Bind("prelude(frame)") ProtosPrelude prelude,
            @Cached("selector") String cachedSelector,
            @Cached("prelude") ProtosPrelude cachedPrelude,
            @Cached("selectNumeric(frozenBoundPrototype(selectBinding(cachedPrelude, SMALL_INTEGER_BINDING)), cachedSelector)")
                    ProtosValueLookup.StructuralSelection selection,
            @Cached(value = "allOwnersFrozen(selection)", neverDefault = false) boolean frozen,
            @Cached(value = "frozenStandard(frozen, cachedSelector, receiver, selection, cachedPrelude)",
                    neverDefault = false) boolean standard) {
        return standard;
    }

    /*
     * A SmallInteger receiver whose operator chain has a mutable owner. The
     * Prelude's SmallInteger binding is FROZEN, so the prototype it selects is
     * fixed for this Prelude identity; the operator selection is checked
     * structurally on each execution and the selected location's final-value
     * assumption invalidates the entry when the slot is rewritten in place.
     */
    @Specialization(
            excludeForUncached = true,
            guards = {
                "selector == cachedSelector",
                "prelude == cachedPrelude",
                "selection != null",
                "selection.isValid()"
            },
            assumptions = "selectionAssumption",
            limit = "3")
    static boolean doMutableLong(
            VirtualFrame frame,
            String selector,
            long receiver,
            @Bind("prelude(frame)") ProtosPrelude prelude,
            @Cached("selector") String cachedSelector,
            @Cached("prelude") ProtosPrelude cachedPrelude,
            @Cached("selectNumeric(frozenBoundPrototype(selectBinding(cachedPrelude, SMALL_INTEGER_BINDING)), cachedSelector)")
                    ProtosValueLookup.StructuralSelection selection,
            @Cached("finalAssumption(selection)") Assumption selectionAssumption,
            @Cached(value = "stableStandard(cachedSelector, receiver, selection, cachedPrelude)",
                    neverDefault = false) boolean standard) {
        return standard;
    }

    /*
     * A Float receiver of a compact invocation whose own Closure captured
     * its Prelude, read from the Closure's final field without the general
     * Prelude resolution. Once the invocation publishes its activation, or
     * when the Closure captured no Prelude, the bound Prelude is null and the
     * general entries resolve it, keeping caller inheritance. The frozen
     * classification is proven at installation for this Prelude identity.
     */
    @Specialization(
            excludeForUncached = true,
            guards = {
                "prelude != null",
                "selector == cachedSelector",
                "prelude == cachedPrelude",
                "frozen"
            },
            limit = "3")
    static boolean doCapturedFrozenDouble(
            VirtualFrame frame,
            String selector,
            double receiver,
            @Bind("capturedPrelude(frame)") ProtosPrelude prelude,
            @Cached("selector") String cachedSelector,
            @Cached("prelude") ProtosPrelude cachedPrelude,
            @Cached("selectNumeric(frozenBoundPrototype(selectBinding(cachedPrelude, FLOAT_BINDING)), cachedSelector)")
                    ProtosValueLookup.StructuralSelection selection,
            @Cached(value = "allOwnersFrozen(selection)", neverDefault = false) boolean frozen,
            @Cached(value = "frozenStandard(frozen, cachedSelector, receiver, selection, cachedPrelude)",
                    neverDefault = false) boolean standard) {
        return standard;
    }

    /*
     * A Float receiver of a compact invocation whose own Closure captured
     * its Prelude, with a mutable owner along the operator chain. The
     * selection is checked structurally on each execution and the selected
     * location's final-value assumption invalidates the entry when the slot
     * is rewritten in place; an already invalid assumption is not installed.
     */
    @Specialization(
            excludeForUncached = true,
            guards = {
                "prelude != null",
                "selector == cachedSelector",
                "prelude == cachedPrelude",
                "selection != null",
                "selection.isValid()"
            },
            assumptions = "selectionAssumption",
            limit = "3")
    static boolean doCapturedMutableDouble(
            VirtualFrame frame,
            String selector,
            double receiver,
            @Bind("capturedPrelude(frame)") ProtosPrelude prelude,
            @Cached("selector") String cachedSelector,
            @Cached("prelude") ProtosPrelude cachedPrelude,
            @Cached("selectNumeric(frozenBoundPrototype(selectBinding(cachedPrelude, FLOAT_BINDING)), cachedSelector)")
                    ProtosValueLookup.StructuralSelection selection,
            @Cached("finalAssumption(selection)") Assumption selectionAssumption,
            @Cached(value = "stableStandard(cachedSelector, receiver, selection, cachedPrelude)",
                    neverDefault = false) boolean standard) {
        return standard;
    }

    /*
     * A Float receiver under an observed Prelude whose local Float binding
     * and whose whole operator chain are FROZEN. Frozen objects never change
     * their slots, parents or Shapes, so the classification computed when the
     * entry is installed stays exact for this Prelude identity; only the
     * Prelude and the selector are checked on each execution.
     */
    @Specialization(
            excludeForUncached = true,
            guards = {
                "selector == cachedSelector",
                "prelude == cachedPrelude",
                "frozen"
            },
            limit = "3")
    static boolean doFrozenDouble(
            VirtualFrame frame,
            String selector,
            double receiver,
            @Bind("prelude(frame)") ProtosPrelude prelude,
            @Cached("selector") String cachedSelector,
            @Cached("prelude") ProtosPrelude cachedPrelude,
            @Cached("selectNumeric(frozenBoundPrototype(selectBinding(cachedPrelude, FLOAT_BINDING)), cachedSelector)")
                    ProtosValueLookup.StructuralSelection selection,
            @Cached(value = "allOwnersFrozen(selection)", neverDefault = false) boolean frozen,
            @Cached(value = "frozenStandard(frozen, cachedSelector, receiver, selection, cachedPrelude)",
                    neverDefault = false) boolean standard) {
        return standard;
    }

    /*
     * A Float receiver whose operator chain has a mutable owner. The
     * Prelude's Float binding is FROZEN, so the prototype it selects is
     * fixed for this Prelude identity; the operator selection is checked
     * structurally on each execution and the selected location's final-value
     * assumption invalidates the entry when the slot is rewritten in place.
     */
    @Specialization(
            excludeForUncached = true,
            guards = {
                "selector == cachedSelector",
                "prelude == cachedPrelude",
                "selection != null",
                "selection.isValid()"
            },
            assumptions = "selectionAssumption",
            limit = "3")
    static boolean doMutableDouble(
            VirtualFrame frame,
            String selector,
            double receiver,
            @Bind("prelude(frame)") ProtosPrelude prelude,
            @Cached("selector") String cachedSelector,
            @Cached("prelude") ProtosPrelude cachedPrelude,
            @Cached("selectNumeric(frozenBoundPrototype(selectBinding(cachedPrelude, FLOAT_BINDING)), cachedSelector)")
                    ProtosValueLookup.StructuralSelection selection,
            @Cached("finalAssumption(selection)") Assumption selectionAssumption,
            @Cached(value = "stableStandard(cachedSelector, receiver, selection, cachedPrelude)",
                    neverDefault = false) boolean standard) {
        return standard;
    }

    /*
     * A String or other admitted receiver of a compact invocation whose own
     * Closure captured its Prelude. The entry and its classification follow
     * exactly the general cached entry: a FROZEN chain answers its installed
     * classification, a mutable chain classifies the selected slot's current
     * value on every execution.
     */
    @Specialization(
            excludeForUncached = true,
            guards = {
                "prelude != null",
                "prototype != null",
                "selector == cachedSelector",
                "prelude == cachedPrelude",
                "prototype == cachedPrototype",
                "selection != null",
                "selection.isValid()"
            },
            limit = "3")
    static boolean doCapturedGeneric(
            VirtualFrame frame,
            String selector,
            Object receiver,
            @Bind("capturedPrelude(frame)") ProtosPrelude prelude,
            @Bind("prototype(receiver, prelude)") ProtosObjectValue prototype,
            @Cached("selector") String cachedSelector,
            @Cached("prelude") ProtosPrelude cachedPrelude,
            @Cached("prototype") ProtosObjectValue cachedPrototype,
            @Cached("selectNumeric(cachedPrototype, cachedSelector)")
                    ProtosValueLookup.StructuralSelection selection,
            @Cached(value = "allOwnersFrozen(selection)", neverDefault = false) boolean frozen,
            @Cached(value = "frozenStandard(frozen, cachedSelector, receiver, selection, cachedPrelude)",
                    neverDefault = false) boolean frozenStandard) {
        if (frozen) {
            return frozenStandard;
        }
        return standard(cachedSelector, receiver, selection.read(), selection.home(), cachedPrelude);
    }

    @Specialization(
            excludeForUncached = true,
            guards = {
                "prototype != null",
                "selector == cachedSelector",
                "prelude == cachedPrelude",
                "prototype == cachedPrototype",
                "selection != null",
                "selection.isValid()"
            },
            limit = "3")
    static boolean doCached(
            VirtualFrame frame,
            String selector,
            Object receiver,
            @Bind("prelude(frame)") ProtosPrelude prelude,
            @Bind("prototype(receiver, prelude)") ProtosObjectValue prototype,
            @Cached("selector") String cachedSelector,
            @Cached("prelude") ProtosPrelude cachedPrelude,
            @Cached("prototype") ProtosObjectValue cachedPrototype,
            @Cached("selectNumeric(cachedPrototype, cachedSelector)")
                    ProtosValueLookup.StructuralSelection selection,
            @Cached(value = "allOwnersFrozen(selection)", neverDefault = false) boolean frozen,
            @Cached(value = "frozenStandard(frozen, cachedSelector, receiver, selection, cachedPrelude)",
                    neverDefault = false) boolean frozenStandard) {
        if (frozen) {
            return frozenStandard;
        }
        return standard(cachedSelector, receiver, selection.read(), selection.home(), cachedPrelude);
    }

    /* Ordinary objects and absent Preludes never select a standard operator. */
    @Specialization(guards = "prototype(receiver, prelude) == null")
    static boolean doNotNumeric(
            VirtualFrame frame,
            String selector,
            Object receiver,
            @Bind("prelude(frame)") ProtosPrelude prelude) {
        return false;
    }

    /*
     * The generic selection, separate from the cached entries and replacing
     * none of them: a site that reaches it keeps its typed entries eligible.
     */
    @Specialization
    static boolean doUncached(VirtualFrame frame, String selector, Object receiver) {
        ProtosPrelude prelude = prelude(frame);
        if (prototype(receiver, prelude) == null) {
            return false;
        }
        return selectStandard(selector, receiver, prelude);
    }

    /* The caller's Prelude may differ between executions of one site. */
    @NonIdempotent
    static ProtosPrelude prelude(VirtualFrame frame) {
        return ProtosFrameArguments.admittedPreludeOrNull(frame.getArguments());
    }

    /*
     * The Prelude captured by the compact invocation's own Closure, or null.
     * Argument zero changes when the invocation publishes its activation.
     */
    @NonIdempotent
    static ProtosPrelude capturedPrelude(VirtualFrame frame) {
        return ProtosFrameArguments.capturedClosurePreludeOrNull(
                frame.getArguments());
    }

    /* The origin of the receiver's selection, or null when it is not a standard operand. */
    static ProtosObjectValue prototype(Object receiver, ProtosPrelude prelude) {
        if (prelude == null || !(receiver instanceof ProtosStringValue
                || ProtosNumericValueSupport.isCurrentRealNumber(receiver))) {
            return null;
        }
        if (receiver instanceof ProtosObjectValue object) {
            return object;
        }
        return (ProtosObjectValue) ProtosValueLookup.delegationParent(receiver, prelude)
                .orElse(null);
    }

    /* Specialization time only: the structural selection of the selector. */
    @TruffleBoundary
    static ProtosValueLookup.StructuralSelection selectNumeric(
            ProtosObjectValue prototype, String selector) {
        if (prototype == null) {
            return null;
        }
        return ProtosValueLookup.selectStructural(prototype, selector);
    }

    /*
     * Specialization time only: the structural selection of the Prelude's
     * own local binding {@code name}, or null when the Prelude is absent or
     * the binding is not local to its bindings object (an inherited binding
     * is not the standard prototype binding).
     */
    @TruffleBoundary
    static ProtosValueLookup.StructuralSelection selectBinding(
            ProtosPrelude prelude, String name) {
        if (prelude == null) {
            return null;
        }
        ProtosValueLookup.StructuralSelection binding =
                ProtosValueLookup.selectStructural(prelude.bindings(), name);
        if (binding == null || binding.home() != binding.origin()) {
            return null;
        }
        return binding;
    }

    /*
     * Specialization time only: the ordinary prototype selected by a binding
     * whose owners are all FROZEN, or null. A FROZEN bindings object never
     * rebinds the prototype, so no per-execution check of the binding is
     * needed while the Prelude identity matches.
     */
    static ProtosObjectValue frozenBoundPrototype(ProtosValueLookup.StructuralSelection binding) {
        if (binding == null || !binding.isValid() || !binding.allOwnersFrozen()) {
            return null;
        }
        return binding.read() instanceof ProtosObjectValue prototype ? prototype : null;
    }

    /*
     * Specialization time only: the final-value assumption of the selected
     * location, which may already be invalid; an invalid one leaves the
     * selection to the general entries.
     */
    static Assumption finalAssumption(ProtosValueLookup.StructuralSelection selection) {
        return selection == null ? Assumption.NEVER_VALID : selection.finalAssumption();
    }

    /*
     * Specialization time only: the standard classification of a selection
     * whose value is protected by {@link #finalAssumption}.
     */
    static boolean stableStandard(String selector, Object receiver,
            ProtosValueLookup.StructuralSelection selection, ProtosPrelude prelude) {
        return selection != null && selection.isValid()
                && standard(selector, receiver, selection.read(), selection.home(), prelude);
    }

    /* Specialization time only: whether the whole selected chain is FROZEN. */
    static boolean allOwnersFrozen(ProtosValueLookup.StructuralSelection selection) {
        return selection != null && selection.allOwnersFrozen();
    }

    /*
     * Specialization time only: the standard classification of a FROZEN
     * selection; never computed for a chain that may still change.
     */
    static boolean frozenStandard(boolean frozen, String selector, Object receiver,
            ProtosValueLookup.StructuralSelection selection, ProtosPrelude prelude) {
        return frozen && selection.isValid()
                && standard(selector, receiver, selection.read(), selection.home(), prelude);
    }

    /* Whether a selected behavior and its owner are the standard operator. */
    static boolean standard(String selector, Object receiver, Object behavior,
            ProtosObjectValue home, ProtosPrelude prelude) {
        if (receiver instanceof ProtosStringValue) {
            return "+".equals(selector)
                    && ProtosStandardStringProtocol.isStandardConcatenationBehavior(behavior);
        }
        if (!(behavior instanceof ProtosClosureValue closure)) {
            return false;
        }
        if (ProtosStandardNumberOrderingProtocol.canonicalRelationForSelection(
                closure, home, selector, prelude) != null) {
            return true;
        }
        if (ProtosNumericValueSupport.isCurrentInteger(receiver)) {
            return ProtosStandardIntegerProtocol.canonicalOperationForSelection(
                    closure, home, selector, prelude) != null;
        }
        return ProtosNumericValueSupport.isCurrentFloat(receiver)
                && ProtosStandardFloatProtocol.canonicalOperationForSelection(
                        closure, home, selector, prelude) != null;
    }

    /* The complete selection, observed without invoking anything. */
    @TruffleBoundary
    static boolean selectStandard(String selector, Object receiver, ProtosPrelude prelude) {
        return ProtosValueLookup.lookup(receiver, selector, prelude)
                .map(found -> standard(selector, receiver, found.value(), found.home(), prelude))
                .orElse(false);
    }
}
