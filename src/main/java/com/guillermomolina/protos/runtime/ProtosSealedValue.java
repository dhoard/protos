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

import java.util.List;
import java.util.Objects;

/**
 * Internal representation of an ordinary Protos object minted by a trusted sealing family
 * (LIB020-A).
 *
 * <p>It is not a new Protos value category: slots, delegation, mutation state, identity and every
 * other observable behavior are those of an ordinary frozen object. The only differences are the
 * family token, which lets the minting family recognize its own values without invoking any
 * behavior of a candidate, and a family-private state that is neither a slot nor otherwise
 * reachable from guest code. Guest code cannot mint these values: an ordinary object with the same
 * slots, parent or shape is never a member of the family.
 *
 * <p>Sealed values are not semantically portable: isolation transfer and isolated-parallel
 * transfer reject them instead of copying them into ordinary objects that would silently lose
 * their family. The single exception is the Core Fraction family (D197-C, I090), whose values the
 * explicit numeric-copy boundary of {@link ProtosNumericValueSupport} rematerializes by value.
 */
public final class ProtosSealedValue extends ProtosObjectValue {
    private final Object family;
    private final Object state;

    private ProtosSealedValue(Object parent, Object family, Object state) {
        super(parent);
        this.family = Objects.requireNonNull(family, "family");
        this.state = Objects.requireNonNull(state, "state");
    }

    /**
     * Mints a FROZEN value of {@code family} with the parent and local slots of {@code template}
     * and the given family-private {@code state}.
     */
    public static ProtosSealedValue seal(Object family, ProtosObjectValue template, Object state) {
        ProtosSealedValue value =
                new ProtosSealedValue(template.parent().orElseThrow(), family, state);
        value.composeLocalSlotsFrom(template, List.of());
        value.freeze();
        return value;
    }

    /**
     * Mints a FROZEN value of {@code family} delegating to {@code parent}, with no local slots.
     * Reserved for the Core numeric boundary, which owns the family token it passes.
     */
    static ProtosSealedValue mint(Object family, ProtosObjectValue parent, Object state) {
        ProtosSealedValue value =
                new ProtosSealedValue(Objects.requireNonNull(parent, "parent"), family, state);
        value.freeze();
        return value;
    }

    /** Answers whether this frozen value was minted by exactly {@code family}. */
    public boolean belongsTo(Object family) {
        return this.family == family && isFrozen();
    }

    /** Family-private state; only the minting family may read it. */
    public Object stateFor(Object family) {
        if (!belongsTo(family)) {
            throw new IllegalArgumentException("sealed value does not belong to this family");
        }
        return state;
    }
}
