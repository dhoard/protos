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
import com.guillermomolina.protos.runtime.ProtosClosureValue;
import com.guillermomolina.protos.runtime.ProtosCoreErrors;
import com.guillermomolina.protos.runtime.ProtosModuleKey;
import com.guillermomolina.protos.runtime.ProtosObjectValue;
import com.guillermomolina.protos.runtime.ProtosPrelude;
import com.guillermomolina.protos.runtime.ProtosSignalException;
import com.guillermomolina.protos.runtime.ProtosSquareRoot;
import java.util.List;

/**
 * Private runtime facility of {@code std:math/Math} (D198, I090-C).
 *
 * <p>The public {@code sqrt} is an ordinary Closure of {@code lib/math/Math.protos}. Following the
 * D101/D187 pattern, this frozen facility is provisioned as a standard initial member of exactly
 * that module, which captures it lexically and removes the bootstrap slot during initialization,
 * so it is never module surface or a Prelude binding.
 *
 * <ul>
 *   <li>{@code sqrt(value)} answers {@link ProtosSquareRoot#sqrt} of a semantic Number, with
 *       results owned by the caller's Prelude; any other receiver, arity or argument signals an
 *       Error. No guest message is sent to the argument.
 * </ul>
 */
public final class ProtosMathFacility {
    public static final ProtosModuleKey MODULE_KEY = new ProtosModuleKey("std:math/Math");
    public static final String BOOTSTRAP_SLOT = "_mathSqrtFacility";

    private ProtosMathFacility() {}

    public static ProtosObjectValue createFacility() {
        ProtosObjectValue facility = new ProtosObjectValue(ProtosObjectValue.rootObject());
        facility.createLocalSlot(
                "sqrt",
                ProtosClosureValue.nativeClosure(
                        (activation, supplied) -> sqrt(facility, activation, supplied)));
        return facility.freeze();
    }

    private static Object sqrt(
            ProtosObjectValue facility, ProtosActivation activation, List<?> supplied) {
        if (activation.receiver() != facility || supplied.size() != 1) {
            throw invalid(activation);
        }
        ProtosPrelude prelude =
                activation.prelude()
                        .orElseThrow(() -> new IllegalStateException(
                                "std:math/Math sqrt requires the caller's Prelude"));
        Object root = ProtosSquareRoot.sqrt(supplied.get(0), prelude);
        if (root == null) {
            throw invalid(activation);
        }
        return root;
    }

    private static ProtosSignalException invalid(ProtosActivation activation) {
        return new ProtosSignalException(ProtosCoreErrors.newError(activation));
    }
}
