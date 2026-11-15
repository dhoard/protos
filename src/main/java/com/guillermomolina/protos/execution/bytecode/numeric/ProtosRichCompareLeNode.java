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
import com.oracle.truffle.api.HostCompilerDirectives.InliningCutoff;
import com.oracle.truffle.api.bytecode.BytecodeNode;
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
 * The ordering comparison {@code <=}. Primitive specializations execute
 * only while the standard {@code <=} is the behavior selected for the
 * original receiver under the caller's Prelude. The generic specialization
 * re-checks the selection: a standard selection uses the existing ordering
 * kernel; any other selection invokes the selected implementation through
 * {@link ProtosNumericOverloadNode}. Its result, possibly a suspended
 * invocation, is composed by the enclosing bytecode, which then requires a
 * canonical Boolean.
 */
@GenerateUncached
@GenerateInline(false)
@OperationProxy.Proxyable(allowUncached = true, storeBytecodeIndex = false)
abstract class ProtosRichCompareLeNode extends Node {
    static final String SELECTOR = "<=";

    abstract Object execute(VirtualFrame frame, Object left, Object right);

    @Specialization(guards = "selection.execute(frame, SELECTOR, left)")
    static boolean doLL(VirtualFrame frame, long left, long right,
            @Shared("selection") @Cached ProtosNumericSelectionNode selection) {
        return left <= right;
    }

    @Specialization(guards = "selection.execute(frame, SELECTOR, left)")
    static boolean doDD(VirtualFrame frame, double left, double right,
            @Shared("selection") @Cached ProtosNumericSelectionNode selection) {
        return left <= right;
    }

    @Specialization
    @StoreBytecodeIndex
    @InliningCutoff
    static Object doGeneric(
            VirtualFrame frame, Object left, Object right,
            @Bind("$node") Node node,
            @Bind("rootOf($node)") ProtosBytecodeRootNode root,
            @Shared("selection") @Cached ProtosNumericSelectionNode selection,
            @Cached ProtosNumericOverloadNode overload) {
        if (selection.execute(frame, SELECTOR, left)) {
            return ProtosNumericOperationSupport.compare(
                    root, frame, left, right, Relation.LESS_EQUAL);
        }
        BytecodeNode bytecode = BytecodeNode.get(node);
        return overload.execute(frame, false, SELECTOR, left, right,
                root, bytecode, bytecode.getBytecodeIndex(frame));
    }

    static ProtosBytecodeRootNode rootOf(Node node) {
        return (ProtosBytecodeRootNode) BytecodeNode.get(node).getBytecodeRootNode();
    }
}
