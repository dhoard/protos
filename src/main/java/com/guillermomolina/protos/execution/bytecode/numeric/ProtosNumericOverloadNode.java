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
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.GenerateInline;
import com.oracle.truffle.api.dsl.GenerateUncached;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.Node;

/**
 * Invokes the behavior selected for a binary operator selector when it is
 * not the standard numeric or String operator. Both operands were already
 * evaluated, once, by the enclosing operation; selection happens only now.
 *
 * <p>This is the single cold path of a non-standard operator. The complete
 * send selection of {@code GetMethod} chooses the behavior, and the shared
 * {@link ProtosFunctionCallDispatchers.CallNode} invokes it exactly as an
 * ordinary send: the receiver and the owning methodHome, the caller,
 * Prelude, ReturnHome, guest Errors, non-local control transfers and
 * suspensions are those of that selection and dispatcher. A suspended
 * result is returned unchanged to a non-terminal site, which composes it in
 * its own bytecode; a terminal site wraps it in the terminal transport
 * carrying its call location.
 */
@GenerateUncached
@GenerateInline(false)
abstract class ProtosNumericOverloadNode extends Node {

    abstract Object execute(
            VirtualFrame frame,
            boolean terminal,
            String selector,
            Object receiver,
            Object argument,
            ProtosBytecodeRootNode root,
            BytecodeNode bytecode,
            int bytecodeIndex);

    /* The complete send selection followed by the ordinary dispatcher. */
    @Specialization
    @InliningCutoff
    static Object doGeneric(
            VirtualFrame frame,
            boolean terminal,
            String selector,
            Object receiver,
            Object argument,
            ProtosBytecodeRootNode root,
            BytecodeNode bytecode,
            int bytecodeIndex,
            @Cached ProtosFunctionCallDispatchers.CallNode call) {
        Object method = ProtosExperimentalGetMethodNode.select(
                ProtosNumericValueSupport.guestValue(receiver),
                selector,
                root,
                frame.getArguments());
        Object outcome = call.execute(
                frame, terminal, method,
                ProtosFrameArguments.dispatchedCall1(
                        null, ProtosFrameArguments.functionCallMarker(),
                        null, null, null,
                        ProtosNumericValueSupport.guestValue(argument)),
                null, root);
        return ProtosTailCallContinuation.completeCall(
                terminal, outcome, bytecode, bytecodeIndex);
    }
}
