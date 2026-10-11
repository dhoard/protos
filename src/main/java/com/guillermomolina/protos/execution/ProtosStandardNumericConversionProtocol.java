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

import com.guillermomolina.protos.runtime.ProtosBinary64Rounding;
import com.guillermomolina.protos.runtime.ProtosClosureValue;
import com.guillermomolina.protos.runtime.ProtosCoreErrors;
import com.guillermomolina.protos.runtime.ProtosFloatValue;
import com.guillermomolina.protos.runtime.ProtosNumericValueSupport;
import com.guillermomolina.protos.runtime.ProtosObjectValue;
import com.guillermomolina.protos.runtime.ProtosPrelude;
import com.guillermomolina.protos.runtime.ProtosSignalException;
import java.util.Objects;

public final class ProtosStandardNumericConversionProtocol {
    private ProtosStandardNumericConversionProtocol() {}

    public static void install(
            ProtosObjectValue integerPrototype,
            ProtosObjectValue floatPrototype) {
        Objects.requireNonNull(integerPrototype, "integerPrototype");
        Objects.requireNonNull(floatPrototype, "floatPrototype");

        installIntegerFactory(integerPrototype);
        installFloatFactory(floatPrototype);
    }

    private static void installIntegerFactory(ProtosObjectValue integerPrototype) {
        installFactory(integerPrototype, FactoryKind.INTEGER);
    }

    private static void installFloatFactory(ProtosObjectValue floatPrototype) {
        installFactory(floatPrototype, FactoryKind.FLOAT);
    }

    enum FactoryKind {
        INTEGER,
        FLOAT
    }

    private static void installFactory(
            ProtosObjectValue prototype,
            FactoryKind factoryKind) {
        if (prototype.hasLocalSlot("call")) {
            throw new IllegalStateException(
                    "Core numeric prototype already defines a local call slot");
        }
        prototype.createLocalSlot(
                "call",
                ProtosClosureValue.nativeClosure(
                        (activation, supplied) -> {
                            if (activation.receiver() != prototype || supplied.size() != 1) {
                                throw new ProtosSignalException(
                                        ProtosCoreErrors.newError(activation));
                            }
                            Object converted = convert(
                                    factoryKind,
                                    supplied.get(0),
                                    activation.prelude().orElse(null));
                            if (converted == null) {
                                throw new ProtosSignalException(
                                        ProtosCoreErrors.newError(activation));
                            }
                            return converted;
                        }));
    }


    /*
     * The D196 factory conversion; null selects the guest Error. Package-private for tests. Only
     * an integral Float outside the signed-64 range consults prelude, which mints the result.
     * A canonical Fraction is never integral, so Integer conversion rejects it instead of
     * silently truncating (D197-C).
     */
    static Object convert(
            FactoryKind factoryKind, Object value, ProtosPrelude prelude) {
        return switch (factoryKind) {
            case INTEGER -> {
                if (ProtosNumericValueSupport.isCurrentInteger(value)) {
                    yield value;
                }
                if (value instanceof ProtosFloatValue floating) {
                    yield ProtosBinary64Rounding.integralBinary64(floating.value(), prelude);
                }
                yield null;
            }
            case FLOAT -> {
                if (value instanceof ProtosFloatValue floating) {
                    yield floating;
                }
                if (ProtosNumericValueSupport.isCurrentInteger(value)) {
                    yield new ProtosFloatValue(ProtosBinary64Rounding.roundExactInteger(value));
                }
                if (ProtosNumericValueSupport.isFraction(value)) {
                    // D197-C: the exact quotient rounded once, nearest with ties to even.
                    yield new ProtosFloatValue(
                            ProtosNumericValueSupport.roundFractionToBinary64(value));
                }
                yield null;
            }
        };
    }
}
