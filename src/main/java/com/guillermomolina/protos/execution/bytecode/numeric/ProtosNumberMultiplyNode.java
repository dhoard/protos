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
 * The multiplication operation with exact overflow promotion, executed
 * directly only while the standard {@code *} is selected.
 *
 * <p>The generic specialization never replaces the primitive ones. It
 * re-checks the selection: a standard selection completes with the existing
 * numeric kernels; any other selection invokes the selected implementation
 * through {@link ProtosNumericOverloadNode}, whose result (including a
 * suspended invocation) is the exact result of this operation. The
 * {@code terminal} constant states whether the site is the terminal
 * invocation of its root.
 */
@GenerateUncached
@GenerateInline(false)
@ConstantOperand(type = boolean.class, name = "terminal")
@OperationProxy.Proxyable(allowUncached = true, storeBytecodeIndex = false)
abstract class ProtosNumberMultiplyNode extends ProtosNumberMultiplyFastPathsBase {

    @Specialization(guards = "selection.execute(frame, SELECTOR, left)", replaces = "doLL")
    static Object doLLOvf(VirtualFrame frame, boolean terminal, long left, long right,
            @Bind Node node,
            @Shared("selection") @Cached ProtosNumericSelectionNode selection) {
        return multiplyOrPromote(frame, node, left, right);
    }

    @Specialization
    @StoreBytecodeIndex
    @InliningCutoff
    static Object doGeneric(
            VirtualFrame frame,
            boolean terminal,
            Object left,
            Object right,
            @Bind("$node") Node node,
            @Bind("rootOf($node)") ProtosBytecodeRootNode root,
            @Shared("selection") @Cached ProtosNumericSelectionNode selection,
            @Cached ProtosNumericOverloadNode overload) {
        if (selection.execute(frame, SELECTOR, left)) {
            return genericMultiply(root, frame, node, left, right);
        }
        BytecodeNode bytecode = BytecodeNode.get(node);
        return overload.execute(frame, terminal, SELECTOR, left, right,
                root, bytecode, bytecode.getBytecodeIndex(frame));
    }
}
