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
import com.oracle.truffle.api.bytecode.BytecodeNode;
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.Cached.Shared;
import com.oracle.truffle.api.dsl.GenerateCached;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.Node;

/**
 * Primitive long and double addition. Every specialization executes only
 * while the behavior selected for {@code +} on the original
 * receiver under the caller's Prelude is the standard operator; any other
 * selection falls to the generic specialization of the proxied subclass.
 * Signed-64 overflow promotes exactly.
 */
@GenerateCached(false)
abstract class ProtosNumberAddFastPathsBase extends Node {

    static final String SELECTOR = "+";

    abstract Object execute(VirtualFrame frame, boolean terminal, Object left, Object right);

    @Specialization(guards = "selection.execute(frame, SELECTOR, left)",
            rewriteOn = ArithmeticException.class)
    static long doLL(VirtualFrame frame, boolean terminal, long left, long right,
            @Shared("selection") @Cached ProtosNumericSelectionNode selection) {
        return Math.addExact(left, right);
    }

    @Specialization(guards = "selection.execute(frame, SELECTOR, left)")
    static double doDD(VirtualFrame frame, boolean terminal, double left, double right,
            @Shared("selection") @Cached ProtosNumericSelectionNode selection) {
        return left + right;
    }

    @Specialization(guards = "selection.execute(frame, SELECTOR, left)")
    static double doDL(VirtualFrame frame, boolean terminal, double left, long right,
            @Shared("selection") @Cached ProtosNumericSelectionNode selection) {
        return left + right;
    }

    @Specialization(guards = "selection.execute(frame, SELECTOR, left)")
    static double doLD(VirtualFrame frame, boolean terminal, long left, double right,
            @Shared("selection") @Cached ProtosNumericSelectionNode selection) {
        return left + right;
    }

    /*
     * The long sum, or on signed-64 overflow the exact large Integer of the
     * caller's Prelude.
     */
    static Object addOrPromote(VirtualFrame frame, Node node, long left, long right) {
        long sum = left + right;
        if (((left ^ sum) & (right ^ sum)) >= 0L) {
            return sum;
        }
        return ProtosNumberFactory.add(ProtosNumberFactory.prelude(node, frame), left, right);
    }

    /**
     * The admitted standard operand domains not covered by a primitive
     * specialization: two semantic Integers of any representation use the
     * exact factory of the caller's Prelude; other domains use the existing
     * standard numeric kernels. Only a standard selection reaches here.
     */
    static Object genericAdd(ProtosBytecodeRootNode root, VirtualFrame frame,
            Node node, Object left, Object right) {
        Object receiver = ProtosNumericValueSupport.guestValue(left);
        Object argument = ProtosNumericValueSupport.guestValue(right);
        if (ProtosNumericValueSupport.isCurrentInteger(receiver)
                && ProtosNumericValueSupport.isCurrentInteger(argument)) {
            return ProtosNumberFactory.add(ProtosNumberFactory.prelude(node, frame), receiver, argument);
        }
        return ProtosNumericOperationSupport.arithmetic(root, frame, left, SELECTOR, right);
    }

    /*
     * The Protos root executing this operation. In a proxied node the
     * default root bind is typed Node, so it is resolved from the enclosing
     * BytecodeNode.
     */
    static ProtosBytecodeRootNode rootOf(Node node) {
        return (ProtosBytecodeRootNode) BytecodeNode.get(node).getBytecodeRootNode();
    }
}
