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

import com.guillermomolina.protos.runtime.ProtosActivation;
import com.guillermomolina.protos.runtime.ProtosBooleanValue;
import com.guillermomolina.protos.runtime.ProtosClosureValue;
import com.guillermomolina.protos.runtime.ProtosCoreErrors;
import com.guillermomolina.protos.runtime.ProtosNativeClosureBody;
import com.guillermomolina.protos.runtime.ProtosNullValue;
import com.guillermomolina.protos.runtime.ProtosObjectValue;
import com.guillermomolina.protos.runtime.ProtosSignalException;
import java.util.List;

public final class ProtosStandardBooleanProtocol {
    enum StructuredCallbackKind {
        IF_TRUE(1),
        IF_FALSE(1),
        IF_TRUE_IF_FALSE(2),
        AND(1),
        OR(1),
        IF(1),
        ELSE(1);

        private final int arity;

        StructuredCallbackKind(int arity) {
            this.arity = arity;
        }

        int arity() {
            return arity;
        }
    }

    private static final ProtosNativeClosureBody STANDARD_NOT_BODY =
            ProtosStandardBooleanProtocol::not;
    private static final ProtosNativeClosureBody STANDARD_IF_TRUE_BODY =
            ProtosStandardBooleanProtocol::ifTrue;
    private static final ProtosNativeClosureBody STANDARD_IF_FALSE_BODY =
            ProtosStandardBooleanProtocol::ifFalse;
    private static final ProtosNativeClosureBody STANDARD_IF_TRUE_IF_FALSE_BODY =
            ProtosStandardBooleanProtocol::ifTrueIfFalse;
    private static final ProtosNativeClosureBody STANDARD_AND_BODY =
            ProtosStandardBooleanProtocol::and;
    private static final ProtosNativeClosureBody STANDARD_OR_BODY =
            ProtosStandardBooleanProtocol::or;
    private static final ProtosNativeClosureBody STANDARD_IF_BODY =
            ProtosStandardBooleanProtocol::ifMessage;
    private static final ProtosNativeClosureBody STANDARD_ELSE_BODY =
            ProtosStandardBooleanProtocol::elseMessage;

    private static final ProtosClosureValue STANDARD_NOT =
            standardNative(STANDARD_NOT_BODY);
    private static final ProtosClosureValue STANDARD_IF_TRUE =
            standardNative(STANDARD_IF_TRUE_BODY);
    private static final ProtosClosureValue STANDARD_IF_FALSE =
            standardNative(STANDARD_IF_FALSE_BODY);
    private static final ProtosClosureValue STANDARD_IF_TRUE_IF_FALSE =
            standardNative(STANDARD_IF_TRUE_IF_FALSE_BODY);
    private static final ProtosClosureValue STANDARD_AND =
            standardNative(STANDARD_AND_BODY);
    private static final ProtosClosureValue STANDARD_OR =
            standardNative(STANDARD_OR_BODY);
    private static final ProtosClosureValue STANDARD_IF =
            standardNative(STANDARD_IF_BODY);
    private static final ProtosClosureValue STANDARD_ELSE =
            standardNative(STANDARD_ELSE_BODY);

    private ProtosStandardBooleanProtocol() {}

    /*
     * Keep the audited Core native-provider boundary at one lexical provider. The six
     * original Boolean selectors were already native before PLAT028, and D200 adds if/else
     * through the same helper; stable Closure/body identities let C-prime recognize the structured callback operations without expanding that boundary.
     */
    private static ProtosClosureValue standardNative(ProtosNativeClosureBody body) {
        return ProtosClosureValue.nativeClosure(body);
    }

    static StructuredCallbackKind structuredCallbackKindForImplementation(
            ProtosClosureValue closure) {
        ProtosNativeClosureBody body = closure.nativeBody().orElse(null);
        if (body == STANDARD_IF_TRUE_BODY) return StructuredCallbackKind.IF_TRUE;
        if (body == STANDARD_IF_FALSE_BODY) return StructuredCallbackKind.IF_FALSE;
        if (body == STANDARD_IF_TRUE_IF_FALSE_BODY) return StructuredCallbackKind.IF_TRUE_IF_FALSE;
        if (body == STANDARD_AND_BODY) return StructuredCallbackKind.AND;
        if (body == STANDARD_OR_BODY) return StructuredCallbackKind.OR;
        if (body == STANDARD_IF_BODY) return StructuredCallbackKind.IF;
        if (body == STANDARD_ELSE_BODY) return StructuredCallbackKind.ELSE;
        return null;
    }

    static StructuredCallbackKind structuredCallbackKindForCanonicalSelection(
            Object behavior,
            ProtosObjectValue home) {
        if (!home.isRootObject()) return null;
        if (behavior == STANDARD_IF_TRUE) return StructuredCallbackKind.IF_TRUE;
        if (behavior == STANDARD_IF_FALSE) return StructuredCallbackKind.IF_FALSE;
        if (behavior == STANDARD_IF_TRUE_IF_FALSE) return StructuredCallbackKind.IF_TRUE_IF_FALSE;
        if (behavior == STANDARD_AND) return StructuredCallbackKind.AND;
        if (behavior == STANDARD_OR) return StructuredCallbackKind.OR;
        if (behavior == STANDARD_IF) return StructuredCallbackKind.IF;
        if (behavior == STANDARD_ELSE) return StructuredCallbackKind.ELSE;
        return null;
    }

    public static void install() {
        ProtosObjectValue object = ProtosObjectValue.rootObject();
        install(object, "not", STANDARD_NOT);
        install(object, "ifTrue", STANDARD_IF_TRUE);
        install(object, "ifFalse", STANDARD_IF_FALSE);
        install(object, "ifTrueIfFalse", STANDARD_IF_TRUE_IF_FALSE);
        install(object, "and", STANDARD_AND);
        install(object, "or", STANDARD_OR);
        install(object, "if", STANDARD_IF);
        install(object, "else", STANDARD_ELSE);
    }

    private static void install(
            ProtosObjectValue object,
            String selector,
            ProtosClosureValue implementation) {
        if (!object.hasLocalSlot(selector)) {
            object.createLocalSlot(selector, implementation);
        }
    }

    private static Object not(ProtosActivation activation, List<?> supplied) {
        Object receiver = requireReceiverAndArity(activation, supplied, 0);
        return receiver == ProtosBooleanValue.TRUE
                ? ProtosBooleanValue.FALSE
                : ProtosBooleanValue.TRUE;
    }

    private static Object ifTrue(ProtosActivation activation, List<?> supplied) {
        Object receiver = requireReceiverAndArity(activation, supplied, 1);
        if (receiver == ProtosBooleanValue.FALSE) return ProtosNullValue.INSTANCE;
        return ProtosInvocation.invoke(supplied.get(0), List.of(), activation);
    }

    private static Object ifFalse(ProtosActivation activation, List<?> supplied) {
        Object receiver = requireReceiverAndArity(activation, supplied, 1);
        if (receiver == ProtosBooleanValue.TRUE) return ProtosNullValue.INSTANCE;
        return ProtosInvocation.invoke(supplied.get(0), List.of(), activation);
    }

    private static Object ifTrueIfFalse(ProtosActivation activation, List<?> supplied) {
        Object receiver = requireReceiverAndArity(activation, supplied, 2);
        Object selected =
                receiver == ProtosBooleanValue.TRUE ? supplied.get(0) : supplied.get(1);
        return ProtosInvocation.invoke(selected, List.of(), activation);
    }

    private static Object and(ProtosActivation activation, List<?> supplied) {
        Object receiver = requireReceiverAndArity(activation, supplied, 1);
        if (receiver == ProtosBooleanValue.FALSE) return ProtosBooleanValue.FALSE;
        return requireBooleanResult(
                ProtosInvocation.invoke(supplied.get(0), List.of(), activation),
                activation);
    }

    private static Object or(ProtosActivation activation, List<?> supplied) {
        Object receiver = requireReceiverAndArity(activation, supplied, 1);
        if (receiver == ProtosBooleanValue.TRUE) return ProtosBooleanValue.TRUE;
        return requireBooleanResult(
                ProtosInvocation.invoke(supplied.get(0), List.of(), activation),
                activation);
    }

    /*
     * D200: if/else return the original canonical receiver, never the callback result. Like
     * the other callback selectors they are StructuredCallbackKind members, because a Task
     * cannot invoke a callback synchronously from a native body (C-prime owns that call);
     * these bodies serve only non-Task ordinary invocation.
     */
    private static Object ifMessage(ProtosActivation activation, List<?> supplied) {
        Object receiver = requireReceiverAndArity(activation, supplied, 1);
        if (receiver == ProtosBooleanValue.TRUE) {
            ProtosInvocation.invoke(supplied.get(0), List.of(), activation);
        }
        return receiver;
    }

    private static Object elseMessage(ProtosActivation activation, List<?> supplied) {
        Object receiver = requireReceiverAndArity(activation, supplied, 1);
        if (receiver == ProtosBooleanValue.FALSE) {
            ProtosInvocation.invoke(supplied.get(0), List.of(), activation);
        }
        return receiver;
    }

    private static Object requireReceiverAndArity(
            ProtosActivation activation,
            List<?> supplied,
            int arity) {
        if (supplied.size() != arity) throw invalid(activation);
        Object receiver = activation.receiver();
        if (receiver != ProtosBooleanValue.TRUE && receiver != ProtosBooleanValue.FALSE) {
            throw invalid(activation);
        }
        return receiver;
    }

    static Object requireBooleanResult(Object result, ProtosActivation activation) {
        if (result != ProtosBooleanValue.TRUE && result != ProtosBooleanValue.FALSE) {
            throw invalid(activation);
        }
        return result;
    }

    private static ProtosSignalException invalid(ProtosActivation activation) {
        return new ProtosSignalException(ProtosCoreErrors.newError(activation));
    }
}