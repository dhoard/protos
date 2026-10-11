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

import com.guillermomolina.protos.runtime.ProtosBooleanValue;
import com.guillermomolina.protos.runtime.ProtosClosureValue;
import com.guillermomolina.protos.runtime.ProtosComplexNumericSupport;
import com.guillermomolina.protos.runtime.ProtosCoreErrors;
import com.guillermomolina.protos.runtime.ProtosObjectValue;
import com.guillermomolina.protos.runtime.ProtosSignalException;
import java.util.Objects;

/**
 * Core bootstrap bridge for the Complex prototype (D197-C, I090-C1).
 *
 * <p>It installs only the exact trusted {@code recognizes}: membership is decided by the private
 * family authentication of {@link ProtosComplexNumericSupport}, never by sending a message to the
 * candidate, so an ordinary object delegating to {@code Complex} or carrying the same slots is
 * never recognized. No constructor, component selector, arithmetic or ordering is installed.
 */
public final class ProtosStandardComplexProtocol {
    private ProtosStandardComplexProtocol() {}

    public static void install(ProtosObjectValue prototype, ProtosObjectValue numberPrototype) {
        Objects.requireNonNull(prototype, "prototype");
        Objects.requireNonNull(numberPrototype, "numberPrototype");
        if (prototype.parent().orElse(null) != numberPrototype) {
            throw new IllegalStateException(
                    "standard Complex prototype must delegate directly to Number");
        }
        if (!prototype.isOpen() || !prototype.localSlotsSnapshot().isEmpty()) {
            throw new IllegalStateException(
                    "standard Complex prototype must be an empty open source object");
        }
        prototype.createLocalSlot(
                "recognizes",
                ProtosClosureValue.nativeClosure(
                        (activation, supplied) -> {
                            if (activation.receiver() != prototype || supplied.size() != 1) {
                                throw new ProtosSignalException(
                                        ProtosCoreErrors.newError(activation));
                            }
                            return ProtosBooleanValue.of(
                                    ProtosComplexNumericSupport.isComplex(supplied.get(0)));
                        }));
    }
}
