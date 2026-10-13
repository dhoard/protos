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

import com.guillermomolina.protos.execution
        .ProtosBytecodeRootNode.PreparedInlineLiteralCall;
import com.oracle.truffle.api.HostCompilerDirectives.InliningCutoff;
import com.oracle.truffle.api.dsl.Fallback;
import com.oracle.truffle.api.dsl.GenerateInline;
import com.oracle.truffle.api.dsl.GenerateUncached;
import com.oracle.truffle.api.dsl.NeverDefault;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.Node;

/**
 * Specialized preparation for the selected canonical Boolean
 * callback. Ordinary callable selection remains authoritative
 * whenever compact B-prime admission is unavailable.
 */
@GenerateInline(false)
@GenerateUncached
abstract class ProtosNativeIfTrueCallbackNode extends Node {

    abstract PreparedInlineLiteralCall execute(
            Object caller,
            Object supplied,
            Object literalPlan,
            ProtosLanguageContext context);

    @NeverDefault
    public static ProtosNativeIfTrueCallbackNode create() {
        return ProtosNativeIfTrueCallbackNodeGen.create();
    }

    @Specialization
    static PreparedInlineLiteralCall withLiteralPlan(
            Object caller,
            Object supplied,
            ProtosClosureExecutionPlanCell literalPlan,
            ProtosLanguageContext context) {
        PreparedInlineLiteralCall direct =
                ProtosBytecodeRootNode.compactCanonicalIfTrue(
                        caller, supplied, literalPlan, context);

        return direct != null
                ? direct
                : ProtosBytecodeRootNode
                    .prepareNativeIfTrueCallbackFallback(
                            caller, supplied);
    }

    @Fallback
    @InliningCutoff
    static PreparedInlineLiteralCall ordinaryCallback(
            Object caller,
            Object supplied,
            Object literalPlan,
            ProtosLanguageContext context) {
        return ProtosBytecodeRootNode
                .prepareNativeIfTrueCallbackFallback(
                        caller, supplied);
    }
}
