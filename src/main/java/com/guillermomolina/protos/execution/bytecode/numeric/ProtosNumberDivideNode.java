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

import com.guillermomolina.protos.runtime.ProtosNumericValueSupport;
import com.oracle.truffle.api.HostCompilerDirectives.InliningCutoff;
import com.oracle.truffle.api.bytecode.BytecodeNode;
import com.oracle.truffle.api.bytecode.ConstantOperand;
import com.oracle.truffle.api.bytecode.OperationProxy;
import com.oracle.truffle.api.bytecode.StoreBytecodeIndex;
import com.oracle.truffle.api.dsl.Bind;
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.Cached.Shared;
import com.oracle.truffle.api.dsl.GenerateInline;
import com.oracle.truffle.api.dsl.GenerateUncached;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.Node;

/**
 * The exact division operation {@code /}. Primitive specializations execute
 * only while the standard {@code /} is the behavior selected for the
 * original receiver under the caller's Prelude. The generic specialization
 * re-checks the selection: a standard selection completes with the existing
 * numeric kernels; any other selection invokes the selected implementation
 * through {@link ProtosNumericOverloadNode}, whose result (including a
 * suspended invocation) is the exact result of this operation.
 */
@GenerateUncached
@GenerateInline(false)
@ConstantOperand(type = boolean.class, name = "terminal")
@OperationProxy.Proxyable(allowUncached = true, storeBytecodeIndex = false)
abstract class ProtosNumberDivideNode extends Node {
    static final String SELECTOR = "/";

    abstract Object execute(VirtualFrame frame, boolean terminal, Object left, Object right);

    @Specialization(guards = "selection.execute(frame, SELECTOR, left)")
    static Object doLL(VirtualFrame frame, boolean terminal, long left, long right,
            @Bind Node node,
            @Bind("rootOf($node)") ProtosBytecodeRootNode root,
            @Shared("selection") @Cached ProtosNumericSelectionNode selection) {
        if (right != 0L && (left != Long.MIN_VALUE || right != -1L)
                && left % right == 0L) {
            return left / right;
        }
        // No truncation: Fraction, MIN/-1 and zero take the exact factory path.
        return divideIntegers(root, frame, node, left, right);
    }

    @Specialization(guards = "selection.execute(frame, SELECTOR, left)")
    static double doDD(VirtualFrame frame, boolean terminal, double left, double right,
            @Shared("selection") @Cached ProtosNumericSelectionNode selection) {
        return left / right;
    }

    @Specialization(guards = "selection.execute(frame, SELECTOR, left)")
    static double doDL(VirtualFrame frame, boolean terminal, double left, long right,
            @Shared("selection") @Cached ProtosNumericSelectionNode selection) {
        return left / right;
    }

    @Specialization(guards = "selection.execute(frame, SELECTOR, left)")
    static double doLD(VirtualFrame frame, boolean terminal, long left, double right,
            @Shared("selection") @Cached ProtosNumericSelectionNode selection) {
        return left / right;
    }

    @Specialization
    @StoreBytecodeIndex
    @InliningCutoff
    static Object doGeneric(VirtualFrame frame, boolean terminal, Object left, Object right,
            @Bind("$node") Node node,
            @Bind("rootOf($node)") ProtosBytecodeRootNode root,
            @Shared("selection") @Cached ProtosNumericSelectionNode selection,
            @Cached ProtosNumericOverloadNode overload) {
        if (!selection.execute(frame, SELECTOR, left)) {
            BytecodeNode bytecode = BytecodeNode.get(node);
            return overload.execute(frame, terminal, SELECTOR, left, right,
                    root, bytecode, bytecode.getBytecodeIndex(frame));
        }
        Object receiver = ProtosNumericValueSupport.guestValue(left);
        Object argument = ProtosNumericValueSupport.guestValue(right);
        if (ProtosNumericValueSupport.isCurrentInteger(receiver)
                && ProtosNumericValueSupport.isCurrentInteger(argument)) {
            return divideIntegers(root, frame, node, receiver, argument);
        }
        if (isFloatOperand(receiver) && isFloatOperand(argument)
                && (ProtosNumericValueSupport.isCurrentFloat(receiver)
                        || ProtosNumericValueSupport.isCurrentFloat(argument))) {
            return floatValue(receiver) / floatValue(argument);
        }
        return ProtosNumericOperationSupport.arithmetic(root, frame, left, SELECTOR, right);
    }

    @InliningCutoff
    static Object divideIntegers(ProtosBytecodeRootNode root, VirtualFrame frame,
            Node node, Object left, Object right) {
        try {
            return ProtosNumberFactory.divide(ProtosNumberFactory.prelude(node, frame), left, right);
        } catch (ArithmeticException zeroDivisor) {
            throw ProtosNumericOperationSupport.unsupported(root, frame);
        }
    }

    private static boolean isFloatOperand(Object value) {
        return ProtosNumericValueSupport.isCurrentFloat(value)
                || ProtosNumericValueSupport.isIntegerInLongRange(value);
    }

    private static double floatValue(Object value) {
        return ProtosNumericValueSupport.isCurrentFloat(value)
                ? ProtosNumericValueSupport.currentFloatValue(value)
                : (double) ProtosNumericValueSupport.exactLong(value);
    }

    static ProtosBytecodeRootNode rootOf(Node node) {
        return (ProtosBytecodeRootNode) BytecodeNode.get(node).getBytecodeRootNode();
    }
}
