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

import com.guillermomolina.protos.runtime.ProtosIntegerValue;
import com.guillermomolina.protos.runtime.ProtosNumericValueSupport;
import com.guillermomolina.protos.runtime.ProtosPrelude;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.dsl.GenerateInline;
import com.oracle.truffle.api.dsl.GenerateUncached;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.Node;

/**
 * PERF040-A: arithmetic is distinct from canonical guest-message admission.
 *
 * An admitted SmallInteger '+' can execute using a primitive long. The first
 * overflow generalizes only the arithmetic node; subsequent nonoverflows still
 * use long arithmetic, and genuine overflow enters exact Prelude-owned Integer
 * normalization. No BigInteger is created on the successful long path.
 *
 * This mirrors GraalPy's doLL/doLLOvf split, not Python's method-resolution
 * semantics: D013 admission is deliberately implemented by the outer proxy.
 */
abstract class ProtosPrimitiveNumericSendFastPathsBase extends Node {

    /**
     * PERF040-B: GraalPy doLL -> doLLOvf specialization pairs, independent
     * for each canonical Integer arithmetic operation. Unlike Python's
     * distinct bytecode operations, Protos has a D013-selected message:
     * this single child retains one cached arithmetic node per send site.
     * The already-guarded selector/Closure capability selects the pair,
     * never Java type or operator spelling alone.
     */
    @GenerateInline(false)
    @GenerateUncached
    static abstract class IntegerArithmeticNode extends Node {
        abstract Object execute(
                ProtosStandardIntegerProtocol.CanonicalIntegerOperation operation,
                long left, long right, ProtosPrelude prelude);

        @Specialization(guards = "isAdd(operation)", rewriteOn = ArithmeticException.class)
        static long doLLAdd(
                ProtosStandardIntegerProtocol.CanonicalIntegerOperation operation,
                long left, long right, ProtosPrelude prelude) {
            return Math.addExact(left, right);
        }

        @Specialization(guards = "isAdd(operation)", replaces = "doLLAdd")
        static Object doLLOvfAdd(
                ProtosStandardIntegerProtocol.CanonicalIntegerOperation operation,
                long left, long right, ProtosPrelude prelude) {
            long result = left + right;
            if (((left ^ result) & (right ^ result)) < 0) {
                return addLarge(left, right, prelude);
            }
            return result;
        }

        @Specialization(guards = "isSubtract(operation)", rewriteOn = ArithmeticException.class)
        static long doLLSubtract(
                ProtosStandardIntegerProtocol.CanonicalIntegerOperation operation,
                long left, long right, ProtosPrelude prelude) {
            return Math.subtractExact(left, right);
        }

        @Specialization(guards = "isSubtract(operation)", replaces = "doLLSubtract")
        static Object doLLOvfSubtract(
                ProtosStandardIntegerProtocol.CanonicalIntegerOperation operation,
                long left, long right, ProtosPrelude prelude) {
            long result = left - right;
            if (((left ^ right) & (left ^ result)) < 0) {
                return subtractLarge(left, right, prelude);
            }
            return result;
        }

        @Specialization(guards = "isMultiply(operation)", rewriteOn = ArithmeticException.class)
        static long doLLMultiply(
                ProtosStandardIntegerProtocol.CanonicalIntegerOperation operation,
                long left, long right, ProtosPrelude prelude) {
            return Math.multiplyExact(left, right);
        }

        @Specialization(guards = "isMultiply(operation)", replaces = "doLLMultiply")
        static Object doLLOvfMultiply(
                ProtosStandardIntegerProtocol.CanonicalIntegerOperation operation,
                long left, long right, ProtosPrelude prelude) {
            long result = left * right;
            long ax = Math.abs(left);
            long ay = Math.abs(right);
            // Same signed-64 overflow predicate as GraalPy's doLongWithOverflow.
            if (((ax | ay) >>> 31) != 0) {
                if ((right != 0 && result / right != left)
                        || (left == Long.MIN_VALUE && right == -1L)) {
                    return multiplyLarge(left, right, prelude);
                }
            }
            return result;
        }

        static boolean isAdd(ProtosStandardIntegerProtocol.CanonicalIntegerOperation operation) {
            return operation == ProtosStandardIntegerProtocol.CanonicalIntegerOperation.ADD;
        }

        static boolean isSubtract(ProtosStandardIntegerProtocol.CanonicalIntegerOperation operation) {
            return operation == ProtosStandardIntegerProtocol.CanonicalIntegerOperation.SUBTRACT;
        }

        static boolean isMultiply(ProtosStandardIntegerProtocol.CanonicalIntegerOperation operation) {
            return operation == ProtosStandardIntegerProtocol.CanonicalIntegerOperation.MULTIPLY;
        }

    }

    @TruffleBoundary
    static Object addLarge(long left, long right, ProtosPrelude prelude) {
        return ProtosNumericValueSupport.addIntegers(
                new ProtosIntegerValue(left), new ProtosIntegerValue(right), prelude);
    }

    @TruffleBoundary
    static Object subtractLarge(long left, long right, ProtosPrelude prelude) {
        return ProtosNumericValueSupport.subtractIntegers(
                new ProtosIntegerValue(left), new ProtosIntegerValue(right), prelude);
    }

    @TruffleBoundary
    static Object multiplyLarge(long left, long right, ProtosPrelude prelude) {
        return ProtosNumericValueSupport.multiplyIntegers(
                new ProtosIntegerValue(left), new ProtosIntegerValue(right), prelude);
    }

    static boolean smallInteger(Object value, boolean carrierPermitted) {
        return (carrierPermitted && value instanceof Long)
                || value instanceof ProtosIntegerValue;
    }

    static long smallValue(Object value) {
        if (value instanceof Long carrier) {
            return carrier.longValue();
        }
        return ((ProtosIntegerValue) value).longValue();
    }
}
