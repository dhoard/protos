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
import com.guillermomolina.protos.runtime.ProtosValueLookup;
import com.oracle.truffle.api.bytecode.OperationProxy;
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.GenerateInline;
import com.oracle.truffle.api.dsl.GenerateUncached;
import com.oracle.truffle.api.dsl.NeverDefault;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.Node;

/**
 * Canonical Boolean.ifTrue dispatch.
 *
 * The canonical true and false receivers have separate DSL
 * specializations. Other receivers preserve ordinary dispatch.
 */
@GenerateInline(false)
@GenerateUncached
@OperationProxy.Proxyable(
        allowUncached = true,
        storeBytecodeIndex = false)
abstract class ProtosCanonicalBooleanOneNode extends Node {
    static final int CLASS_MISS = 0;
    static final int CLASS_FALSE = 1;
    static final int CLASS_TRUE = 2;

    abstract int execute(Object receiver);

    @NeverDefault
    public static ProtosCanonicalBooleanOneNode create() {
        return ProtosCanonicalBooleanOneNodeGen.create();
    }

    @Specialization(guards = "isCanonicalFalse(receiver)")
    static int canonicalFalse(Object receiver) {
        return CLASS_FALSE;
    }

    @Specialization(guards = "isCanonicalTrue(receiver)")
    static int canonicalTrue(Object receiver) {
        return CLASS_TRUE;
    }

    @Specialization(guards = "!isCanonicalBoolean(receiver)")
    static int ordinaryMiss(
            Object receiver,
            @Cached(
                value = "createMissProfile()",
                uncached = "uncachedMissProfile()")
                ProtosSemanticBytecodeRootNode
                    .CanonicalBooleanMissProfile missProfile) {
        missProfile.observeMiss();
        return CLASS_MISS;
    }

    static ProtosSemanticBytecodeRootNode
            .CanonicalBooleanMissProfile createMissProfile() {
        return ProtosSemanticBytecodeRootNode
                .CanonicalBooleanMissProfile.create();
    }

    static ProtosSemanticBytecodeRootNode
            .CanonicalBooleanMissProfile uncachedMissProfile() {
        return ProtosSemanticBytecodeRootNode
                .CanonicalBooleanMissProfile.uncached();
    }

    static boolean isCanonicalFalse(Object receiver) {
        return receiver == ProtosBooleanValue.FALSE;
    }

    static boolean isCanonicalTrue(Object receiver) {
        return receiver == ProtosBooleanValue.TRUE;
    }

    static boolean isCanonicalBoolean(Object receiver) {
        return ProtosValueLookup.isCanonicalBoolean(receiver);
    }

}
