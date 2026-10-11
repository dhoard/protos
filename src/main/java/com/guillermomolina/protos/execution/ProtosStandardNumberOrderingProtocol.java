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

import com.guillermomolina.protos.runtime.ProtosActivation;
import com.guillermomolina.protos.runtime.ProtosBooleanValue;
import com.guillermomolina.protos.runtime.ProtosClosureValue;
import com.guillermomolina.protos.runtime.ProtosCoreErrors;
import com.guillermomolina.protos.runtime.ProtosNativeClosureBody;
import com.guillermomolina.protos.runtime.ProtosNumericValueSupport;
import com.guillermomolina.protos.runtime.ProtosObjectValue;
import com.guillermomolina.protos.runtime.ProtosPrelude;
import com.guillermomolina.protos.runtime.ProtosSignalException;
import java.util.List;
import java.util.Objects;

/** Standard exact cross-family Number ordering. */
public final class ProtosStandardNumberOrderingProtocol {
    private ProtosStandardNumberOrderingProtocol() {}

    public static void install(ProtosObjectValue numberPrototype) {
        Objects.requireNonNull(numberPrototype, "numberPrototype");
        install(numberPrototype, "<", Relation.LESS);
        install(numberPrototype, "<=", Relation.LESS_EQUAL);
        install(numberPrototype, ">", Relation.GREATER);
        install(numberPrototype, ">=", Relation.GREATER_EQUAL);
    }

    private static void install(
            ProtosObjectValue numberPrototype,
            String selector,
            Relation relation) {
        if (numberPrototype.hasLocalSlot(selector)) {
            throw new IllegalStateException(
                    "Core Number already defines a local " + selector + " slot");
        }
        StandardOrderingBody body =
                new StandardOrderingBody(numberPrototype, selector, relation);
        ProtosClosureValue closure = ProtosClosureValue.nativeClosure(body);
        body.bindOwner(closure);
        numberPrototype.createLocalSlot(selector, closure);
    }

    /**
     * I091: one private body per relation and bootstrapped Number prototype.
     * Retaining the owning Closure lets a guarded primitive comparison prove
     * that ordinary D013 selection reached exactly this standard method.
     */
    private static final class StandardOrderingBody implements ProtosNativeClosureBody {
        private final ProtosObjectValue home;
        private final String selector;
        private final Relation relation;
        private ProtosClosureValue owner;

        StandardOrderingBody(
                ProtosObjectValue home, String selector, Relation relation) {
            this.home = Objects.requireNonNull(home, "home");
            this.selector = Objects.requireNonNull(selector, "selector");
            this.relation = Objects.requireNonNull(relation, "relation");
        }

        void bindOwner(ProtosClosureValue owner) {
            if (this.owner != null) {
                throw new IllegalStateException(
                        "standard Number ordering body already owns a Closure");
            }
            this.owner = Objects.requireNonNull(owner, "owner");
        }

        boolean isCanonicalSelection(
                ProtosClosureValue behavior,
                ProtosObjectValue selectedHome,
                String requestedSelector,
                ProtosPrelude prelude) {
            return owner != null
                    && behavior == owner
                    && selectedHome == home
                    && prelude != null
                    && selectedHome == prelude.numberPrototype()
                    && selector.equals(requestedSelector);
        }

        @Override
        public Object execute(ProtosActivation activation, List<?> supplied) {
            if (supplied.size() != 1
                    // Real values only: no Complex order is defined (I090-C1).
                    || !ProtosNumericValueSupport.isCurrentRealNumber(activation.receiver())
                    || !ProtosNumericValueSupport.isCurrentRealNumber(supplied.get(0))) {
                throw new ProtosSignalException(
                        ProtosCoreErrors.newError(activation));
            }
            return ProtosBooleanValue.of(
                    relation.holds(compare(activation.receiver(), supplied.get(0))));
        }
    }

    /** Null unless ordinary selection reached the exact standard relation. */
    static Relation canonicalRelationForSelection(
            ProtosClosureValue behavior,
            ProtosObjectValue home,
            String selector,
            ProtosPrelude prelude) {
        Objects.requireNonNull(behavior, "behavior");
        Objects.requireNonNull(home, "home");
        Objects.requireNonNull(selector, "selector");
        ProtosNativeClosureBody body = behavior.nativeBody().orElse(null);
        if (!(body instanceof StandardOrderingBody standard)
                || !standard.isCanonicalSelection(behavior, home, selector, prelude)) {
            return null;
        }
        return standard.relation;
    }

    static Comparison compare(Object left, Object right) {
        return ProtosCurrentNumericRelations.compare(left, right);
    }

    enum Comparison { LESS, EQUAL, GREATER, UNORDERED }
    enum Relation {
        LESS, LESS_EQUAL, GREATER, GREATER_EQUAL;

        /* An unordered (NaN) comparison satisfies no relation. */
        boolean holds(Comparison comparison) {
            return switch (this) {
                case LESS -> comparison == Comparison.LESS;
                case LESS_EQUAL ->
                        comparison == Comparison.LESS || comparison == Comparison.EQUAL;
                case GREATER -> comparison == Comparison.GREATER;
                case GREATER_EQUAL ->
                        comparison == Comparison.GREATER || comparison == Comparison.EQUAL;
            };
        }
    }
}
