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

import com.guillermomolina.protos.execution.ProtosStandardNumberOrderingProtocol.Relation;
import com.guillermomolina.protos.runtime.ProtosActivation;
import com.guillermomolina.protos.runtime.ProtosCoreErrors;
import com.guillermomolina.protos.runtime.ProtosNumericValueSupport;
import com.guillermomolina.protos.runtime.ProtosSignalException;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.frame.VirtualFrame;

/**
 * Domain-complete execution shared by the numeric operations once they have
 * verified that the standard operator is selected; these helpers never send
 * an operator message.
 */
final class ProtosNumericOperationSupport {
    private ProtosNumericOperationSupport() {}

    /**
     * The result of {@code relation} for any two operands, cold: the canonical
     * Boolean of two real Numbers. An invalid standard operand domain signals
     * the ordinary guest Error using the authentic caller.
     */
    static Object compare(ProtosBytecodeRootNode root, VirtualFrame frame,
            Object left, Object right, Relation relation) {
        Object result = compareCold(
                ProtosNumericValueSupport.guestValue(left),
                ProtosNumericValueSupport.guestValue(right),
                relation);
        if (result == null) {
            throw unsupported(root, frame);
        }
        return result;
    }

    /** Execute the admitted standard arithmetic operation without a method activation. */
    static Object arithmetic(ProtosBytecodeRootNode root, VirtualFrame frame,
            Object left, String selector, Object right) {
        var prelude = ProtosFrameArguments.admittedPreludeOrNull(frame.getArguments());
        Object result = null;
        try {
            if (ProtosNumericValueSupport.isCurrentInteger(left)) {
                var operation = switch (selector) {
                    case "+" -> ProtosStandardIntegerProtocol.CanonicalIntegerOperation.ADD;
                    case "-" -> ProtosStandardIntegerProtocol.CanonicalIntegerOperation.SUBTRACT;
                    case "*" -> ProtosStandardIntegerProtocol.CanonicalIntegerOperation.MULTIPLY;
                    case "/" -> ProtosStandardIntegerProtocol.CanonicalIntegerOperation.EXACT_DIVIDE;
                    case "div" -> ProtosStandardIntegerProtocol.CanonicalIntegerOperation.QUOTIENT;
                    case "mod" -> ProtosStandardIntegerProtocol.CanonicalIntegerOperation.REMAINDER;
                    default -> throw new IllegalArgumentException("not an arithmetic selector");
                };
                result = ProtosStandardIntegerProtocol.tryExecuteCanonicalOperationOne(
                        operation, left, right, prelude);
            } else if (ProtosNumericValueSupport.isCurrentFloat(left)) {
                result = ProtosStandardFloatProtocol.tryExecuteCanonicalOperationOne(
                        ProtosStandardFloatProtocol.CanonicalFloatOperation.forSelector(selector),
                        left, right, prelude);
            }
        } catch (ArithmeticException invalidArithmetic) {
            throw unsupported(root, frame);
        }
        if (result == null) throw unsupported(root, frame);
        return result;
    }

    @TruffleBoundary
    private static Object compareCold(Object left, Object right, Relation relation) {
        if (!ProtosNumericValueSupport.isCurrentRealNumber(left)
                || !ProtosNumericValueSupport.isCurrentRealNumber(right)) {
            return null;
        }
        return Boolean.valueOf(
                relation.holds(ProtosStandardNumberOrderingProtocol.compare(left, right)));
    }

    /** Signals the guest Error of an unsupported numeric operand pair. */
    static ProtosSignalException unsupported(ProtosBytecodeRootNode root, VirtualFrame frame) {
        root.requestCallerNeed(
                ProtosBytecodeRootNode.NEEDS_EXACT_ACTIVATION);
        return unsupported(root, ProtosFrameArguments.activation(frame));
    }

    @TruffleBoundary
    static ProtosSignalException unsupported(ProtosBytecodeRootNode root, Object caller) {
        ProtosActivation activation = ProtosFunctionCallDispatchers.exactCaller(root, caller);
        throw ProtosCoreErrors.signal(activation, ProtosCoreErrors.newError(activation));
    }
}
