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

/**
 * The Smalltalk-style return home an owning invocation establishes.
 *
 * <p>PERF025: a home that is statically proven unobservable (no non-local
 * return can ever target it; see {@code CanonicalReturnHomeAnalysis}) is
 * represented by the shared {@link #unobservable()} marker instead of a
 * fresh instance. The marker still carries home provenance (a Closure created
 * under it shares its owner's home rather than owning one), but it has no
 * lifecycle: owners never complete it, and a non-local return can never
 * target it. It is never a guest value, so its identity is not observable.
 */
public final class ProtosReturnHome {
    private static final ProtosReturnHome UNOBSERVABLE = new ProtosReturnHome(false);
    /*
     * I091: frame-ABI-only variant of UNOBSERVABLE written by a fused direct
     * source call or send whose caller accepts a primitive numeric result. The frame
     * argument accessors normalize it to UNOBSERVABLE, so no activation,
     * non-local return or guest observer ever sees it.
     */
    private static final ProtosReturnHome UNOBSERVABLE_PRIMITIVE_RESULT = new ProtosReturnHome(false);

    private boolean active;

    public ProtosReturnHome() {
        this(true);
    }

    private ProtosReturnHome(boolean active) {
        this.active = active;
    }

    /** The shared non-materialized representation of a proven-unobservable home. */
    public static ProtosReturnHome unobservable() {
        return UNOBSERVABLE;
    }

    /** I091 frame-ABI marker; see {@link #unobservable()} for its semantics. */
    public static ProtosReturnHome unobservableAcceptingPrimitiveResultForRuntime() {
        return UNOBSERVABLE_PRIMITIVE_RESULT;
    }

    /** False only for the {@link #unobservable()} marker, which has no lifecycle. */
    public boolean isMaterialized() {
        return this != UNOBSERVABLE && this != UNOBSERVABLE_PRIMITIVE_RESULT;
    }

    public boolean isActive() {
        return active;
    }

    public void complete() {
        if (!active) {
            throw new IllegalStateException("return home is already completed");
        }
        active = false;
    }
}
