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
import com.guillermomolina.protos.runtime.ProtosClosureValue;
import com.guillermomolina.protos.runtime.ProtosLexicalCell;
import com.guillermomolina.protos.runtime.ProtosLexicalEnvironment;
import com.guillermomolina.protos.runtime.ProtosNullValue;
import com.guillermomolina.protos.runtime.ProtosNumericValueSupport;
import com.guillermomolina.protos.runtime.ProtosObjectValue;
import com.guillermomolina.protos.runtime.ProtosPrelude;
import com.guillermomolina.protos.runtime.control.ProtosReturnHome;
import com.guillermomolina.protos.runtime.modules.ProtosModuleKey;
import com.guillermomolina.protos.runtime.ProtosTask;
import com.guillermomolina.protos.semantic.ast.CanonicalClosure;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.frame.Frame;
import java.util.List;
import java.util.Objects;

final class ProtosFrameArguments {
    /* single B5 ordinary-call ABI:
     * [0] invoked Closure or published activation
     * [1] receiver or direct-call marker
     * [2] method home or explicit Task
     * [3] null, caller Prelude or caller Activation, by callee need, method Task provenance
     *     or the cold suspension seal (sealSuspendedCaller)
     * [4] return home
     * [5...] supplied positional arguments
     * No B5 argument array or wrapper is retained by another invocation.
     * Publication of this invocation replaces only its own slot 0.
     */
    private static final int CLOSURE_INDEX = 0;
    private static final int RECEIVER_INDEX = 1;
    private static final int METHOD_HOME_INDEX = 2;
    private static final int TASK_INDEX = 2;
    private static final int CALLER_INDEX = 3;
    private static final int RETURN_HOME_INDEX = 4;
    static final int HEADER_LENGTH = 5;
    static final int SUPPLIED_ARGUMENT_OFFSET = HEADER_LENGTH;

    /** Private kind marker; never a guest value, so never a method receiver. */
    private static final Object DIRECT_CLOSURE_CALL = new Object();

    private static final java.util.function.Function<Object[], ProtosActivation>
            COMPACT_CAPTURE_OWNER = ProtosFrameArguments::activation;

    private ProtosFrameArguments() {}


    static Object[] compactImmediateMethodCall(
            ProtosClosureValue closure,
            Object receiver,
            ProtosObjectValue methodHome,
            ProtosActivation caller,
            Object[] supplied) {
        Objects.requireNonNull(receiver, "receiver");
        Objects.requireNonNull(methodHome, "methodHome");
        Objects.requireNonNull(closure, "closure");
        Objects.requireNonNull(caller, "caller");
        Objects.requireNonNull(supplied, "supplied");
        return compactImmediateMethodCall(
                closure,
                receiver,
                methodHome,
                caller,
                ProtosClosureCallPreparation.invocationHomeForColdEntry(closure),
                supplied);
    }

    /**
     * PERF038-D: {@link #compactImmediateMethodCall} with the invocation
     * return home already obtained from {@code closure} by the caller
     * (exactly {@link ProtosClosureValue#invocationReturnHomeForRuntime}).
     *
     * <p>PERF038-E: the only caller is a guarded ordinary send whose
     * specialization already established every header value: the Closure and
     * method home are its cached selection (non-null by construction), the
     * receiver is its identity-guarded cached receiver, and the caller is an
     * activation the DSL type check or provenance guard produced. The header is
     * therefore written without repeating the null validation of the general
     * form.
     */
    static Object[] compactImmediateMethodCall(
            ProtosClosureValue closure,
            Object receiver,
            ProtosObjectValue methodHome,
            ProtosActivation caller,
            ProtosReturnHome returnHome,
            Object[] supplied) {
        bindOwnedReturnHome(closure, returnHome, caller.taskForRuntime());
        if (supplied.length == 0) {
            return new Object[] {closure, receiver, methodHome, caller, returnHome};
        }
        if (supplied.length == 1) {
            return new Object[] {
                closure, receiver, methodHome, caller, returnHome, supplied[0]
            };
        }
        return headerWithSupplied(closure, receiver, methodHome, caller, returnHome, supplied);
    }

    /** PERF038-H: guarded Send0, without supplied argument vector. */
    static Object[] compactImmediateMethodCallZero(
            ProtosClosureValue closure,
            Object receiver,
            ProtosObjectValue methodHome,
            ProtosActivation caller,
            ProtosReturnHome returnHome) {
        bindOwnedReturnHome(closure, returnHome, caller.taskForRuntime());
        return new Object[] {closure, receiver, methodHome, caller, returnHome};
    }

    /**
     * PERF038-H: guarded Send1, with the supplied value placed
     * directly into the final frame array.
     */
    static Object[] compactImmediateMethodCallOne(
            ProtosClosureValue closure,
            Object receiver,
            ProtosObjectValue methodHome,
            ProtosActivation caller,
            ProtosReturnHome returnHome,
            Object supplied0) {
        bindOwnedReturnHome(closure, returnHome, caller.taskForRuntime());
        return new Object[] {
            closure, receiver, methodHome, caller, returnHome, supplied0
        };
    }

    /**
     * PERF025 compact direct source-backed Closure call. {@code task} is the
     * exact owning Task of a Task-owned entry, or {@code null} for a guest call
     * whose callee inherits the caller's Task/dynamic-control state.
     */
    static Object[] compactDirectClosureCall(
            ProtosClosureValue closure,
            ProtosActivation caller,
            ProtosTask task,
            Object[] supplied) {
        Objects.requireNonNull(closure, "closure");
        Objects.requireNonNull(caller, "caller");
        Objects.requireNonNull(supplied, "supplied");
        ProtosReturnHome returnHome = ProtosClosureCallPreparation.invocationHomeForColdEntry(closure);
        bindOwnedReturnHome(
                closure, returnHome, task != null ? task : caller.taskForRuntime());
        if (supplied.length == 0) {
            return new Object[] {closure, DIRECT_CLOSURE_CALL, task, caller, returnHome};
        }
        if (supplied.length == 1) {
            return new Object[] {
                closure, DIRECT_CLOSURE_CALL, task, caller, returnHome, supplied[0]
            };
        }
        return headerWithSupplied(
                closure, DIRECT_CLOSURE_CALL, task, caller, returnHome, supplied);
    }

    /**
     * PERF038-H: admitted direct Call0 with an already-selected return home.
     * There is no explicit Task on an ordinary guest call.
     */
    static Object[] compactDirectClosureCallZeroPrepared(
            ProtosClosureValue closure,
            ProtosActivation caller,
            ProtosReturnHome returnHome) {
        bindOwnedReturnHome(closure, returnHome, caller.taskForRuntime());
        return new Object[] {closure, DIRECT_CLOSURE_CALL, null, caller, returnHome};
    }

    /**
     * PERF038-H: admitted direct Call1 with an already-selected home.
     * The supplied value is placed directly in the final frame.
     */
    static Object[] compactDirectClosureCallOnePrepared(
            ProtosClosureValue closure,
            ProtosActivation caller,
            ProtosReturnHome returnHome,
            Object supplied0) {
        bindOwnedReturnHome(closure, returnHome, caller.taskForRuntime());
        return new Object[] {
            closure, DIRECT_CLOSURE_CALL, null, caller, returnHome, supplied0
        };
    }

    /**
     * PERF038-H: one-argument direct Closure invocation.
     * No intermediate supplied-argument array is constructed.
     */
    static Object[] compactDirectClosureCallOne(
            ProtosClosureValue closure,
            ProtosActivation caller,
            ProtosTask task,
            Object supplied0) {
        Objects.requireNonNull(closure, "closure");
        Objects.requireNonNull(caller, "caller");
        ProtosReturnHome returnHome = ProtosClosureCallPreparation.invocationHomeForColdEntry(closure);
        bindOwnedReturnHome(
                closure, returnHome, task != null ? task : caller.taskForRuntime());
        return new Object[] {
            closure,
            DIRECT_CLOSURE_CALL,
            task,
            caller,
            returnHome,
            supplied0
        };
    }

    /*
     * PLAT070: a compact invocation whose Closure captured no home owns the materialized home in
     * its header; that home is bound to the invocation's known Task (the explicit Task of a
     * Task-owned entry, otherwise the exact caller's Task) before the callee body can create a
     * Closure capturing it. A captured home, a shared unobservable marker or an unknown Task
     * (Task-free invocation) is never bound. Ordinary-call cost: one field read and, for a fresh
     * home, one write; no activation, Context or caller chain is materialized.
     */
    static void bindOwnedReturnHome(
            ProtosClosureValue closure, ProtosReturnHome returnHome, ProtosTask task) {
        if (task != null && returnHome.isMaterialized()
                && closure.ownsInvocationReturnHomeForRuntime()) {
            returnHome.bindOwnerTaskForRuntime(task);
        }
    }

    /**
     * PLAT070: binds the home owned by the compact or published invocation in {@code arguments}
     * to {@code task} before a Task-owned entry runs it, without materializing an activation.
     */
    static void bindOwnedReturnHomeForTaskEntry(Object[] arguments, ProtosTask task) {
        Object first = arguments[CLOSURE_INDEX];
        if (first instanceof ProtosActivation published) {
            published.attachTask(task);
            return;
        }
        if (first instanceof ProtosClosureValue closure
                && arguments.length > RETURN_HOME_INDEX
                && arguments[RETURN_HOME_INDEX] instanceof ProtosReturnHome home) {
            ProtosTask entryTask = isFunctionCall(arguments)
                    ? inheritInvocationTask(arguments, task) : admittedCompactTask(arguments);
            if (!isFunctionCall(arguments) && task != null && entryTask != task) {
                throw new IllegalArgumentException("method Task entry requires its exact Task-owning caller");
            }
            bindOwnedReturnHome(closure, home, entryTask);
        }
    }

    /**
     * PERF038-H: direct Call0 frame after a guarded proof that this
     * invocation has no observable ReturnHome; the home slot holds the
     * identity-stable unobservable marker.
     */
    static Object[] compactDirectClosureCallZeroNoHome(
            ProtosClosureValue closure,
            ProtosActivation caller) {
        Objects.requireNonNull(closure, "closure");
        Objects.requireNonNull(caller, "caller");
        return new Object[] {
            closure, DIRECT_CLOSURE_CALL, null, caller, ProtosReturnHome.unobservable()
        };
    }

    /**
     * PERF038-H: complete direct Call1 ABI without allocating an
     * intermediate supplied-argument array.
     */
    static Object[] compactDirectClosureCallOneNoHome(
            ProtosClosureValue closure,
            ProtosActivation caller,
            Object supplied0) {
        Objects.requireNonNull(closure, "closure");
        Objects.requireNonNull(caller, "caller");
        return new Object[] {
            closure,
            DIRECT_CLOSURE_CALL,
            null,
            caller,
            ProtosReturnHome.unobservableAcceptingPrimitiveResultForRuntime(),
            supplied0
        };
    }

    /**
     * Validated B5+ Call1 construction. Slot 3 accepts exactly null, a
     * transported {@link ProtosPrelude}, or the exact {@link ProtosActivation}.
     * The source dispatcher transports the state requested by the effective
     * callee root: no need gives null, NEEDS_CALLER_PRELUDE gives a Prelude,
     * and NEEDS_EXACT_ACTIVATION gives the exact caller. No caller frame
     * arguments or wrapper are retained. Cold exact recovery requires an
     * active guest caller; a synthetic off-stack header cannot supply it.
     */
    static Object[] compactDirectClosureCallOneNoHome(
            ProtosClosureValue closure,
            Object callerReference,
            Object supplied0) {
        Objects.requireNonNull(closure, "closure");
        if (callerReference != null
                && !(callerReference instanceof ProtosActivation)
                && !(callerReference instanceof ProtosPrelude)) {
            throw new IllegalArgumentException(
                    "Invalid direct Closure-call caller payload");
        }
        return compactAdmittedTerminalClosureCallOneNoHome(
                closure, callerReference, supplied0);
    }


    /**
     * Trusted B5+ construction after direct-call admission. The caller payload
     * is already null, a Prelude, or an exact Activation, according to callee
     * needs. This skips validation but does not broaden the legal ABI. No
     * caller publication or copy occurs here; no ancestral argument array is
     * stored. The Object-overload retains validation for untrusted inputs.
     */
    static Object[] compactAdmittedTerminalClosureCallOneNoHome(
            ProtosClosureValue closure,
            Object callerReference,
            Object supplied0) {
        return new Object[] {
            closure,
            DIRECT_CLOSURE_CALL,
            null,
            callerReference,
            ProtosReturnHome.unobservableAcceptingPrimitiveResultForRuntime(),
            supplied0
        };
    }

    /**
     * B5 direct source-call header with an exact invocation ReturnHome.
     *
     * The caller is transported without publication. The invocation
     * home is selected once by the caller and retained by identity.
     */
    static Object[] compactDispatchedClosureCallOne(
            ProtosClosureValue closure,
            Object callerReference,
            ProtosReturnHome returnHome,
            Object supplied0) {
        return new Object[] {
            closure,
            DIRECT_CLOSURE_CALL,
            null,
            callerReference,
            returnHome,
            supplied0
        };
    }

    /*
     * (PArguments.create): the single B5 construction of every
     * call entered through ProtosFunctionCallDispatchers. A function call
     * writes the direct-call marker and no Task/methodHome; a selected method
     * writes its receiver and methodHome. The caller is transported as is
     * (exact or deferred) and the ReturnHome is the exact object selected by
     * the dispatcher: captured, freshly owned, or the unobservable marker.
     */
    static Object[] dispatchedCall0(
            ProtosClosureValue closure, Object receiverSlot, Object methodHomeSlot,
            Object callerReference, ProtosReturnHome returnHome) {
        return new Object[] {closure, receiverSlot, methodHomeSlot, callerReference, returnHome};
    }

    static Object[] dispatchedCall1(
            ProtosClosureValue closure, Object receiverSlot, Object methodHomeSlot,
            Object callerReference, ProtosReturnHome returnHome, Object a0) {
        return new Object[] {closure, receiverSlot, methodHomeSlot, callerReference, returnHome, a0};
    }

    static Object[] dispatchedCall2(
            ProtosClosureValue closure, Object receiverSlot, Object methodHomeSlot,
            Object callerReference, ProtosReturnHome returnHome, Object a0, Object a1) {
        return new Object[] {
            closure, receiverSlot, methodHomeSlot, callerReference, returnHome, a0, a1
        };
    }

    static Object[] dispatchedCall3(
            ProtosClosureValue closure, Object receiverSlot, Object methodHomeSlot,
            Object callerReference, ProtosReturnHome returnHome,
            Object a0, Object a1, Object a2) {
        return new Object[] {
            closure, receiverSlot, methodHomeSlot, callerReference, returnHome, a0, a1, a2
        };
    }

    static Object[] dispatchedCall4(
            ProtosClosureValue closure, Object receiverSlot, Object methodHomeSlot,
            Object callerReference, ProtosReturnHome returnHome,
            Object a0, Object a1, Object a2, Object a3) {
        return new Object[] {
            closure, receiverSlot, methodHomeSlot, callerReference, returnHome, a0, a1, a2, a3
        };
    }

    static Object[] dispatchedCallN(
            ProtosClosureValue closure, Object receiverSlot, Object methodHomeSlot,
            Object callerReference, ProtosReturnHome returnHome, Object[] supplied) {
        Object[] arguments = new Object[HEADER_LENGTH + supplied.length];
        arguments[CLOSURE_INDEX] = closure;
        arguments[RECEIVER_INDEX] = receiverSlot;
        arguments[METHOD_HOME_INDEX] = methodHomeSlot;
        arguments[CALLER_INDEX] = callerReference;
        arguments[RETURN_HOME_INDEX] = returnHome;
        System.arraycopy(supplied, 0, arguments, SUPPLIED_ARGUMENT_OFFSET, supplied.length);
        return arguments;
    }

    /** A B5 function-call array with the dispatcher-owned slots still empty. */
    static Object[] functionCallArguments(Object[] supplied) {
        return dispatchedCallN(null, DIRECT_CLOSURE_CALL, null, null, null, supplied);
    }

    /** A B5 method-call array with the dispatcher-owned slots still empty. */
    static Object[] methodCallArguments(
            Object receiver, ProtosObjectValue methodHome, Object[] supplied) {
        return dispatchedCallN(null, receiver, methodHome, null, null, supplied);
    }

    /**
     * a Task-owned function call (task entry): slot 2 carries
     * the exact owning Task.
     */
    static Object[] taskFunctionCallArguments(ProtosTask task, Object[] supplied) {
        return dispatchedCallN(null, DIRECT_CLOSURE_CALL, task, null, null, supplied);
    }

    /** The current receiver, without publishing the invocation's activation. */
    static Object currentReceiver(Object[] arguments) {
        if (hasPublishedActivation(arguments)) {
            return publishedActivation(arguments).receiver();
        }
        return isDirectClosureCall(arguments)
                ? compactClosure(arguments).capturedReceiver()
                : arguments[RECEIVER_INDEX];
    }

    /** The selected method home, without publishing the invocation. */
    static ProtosObjectValue compactMethodHome(Object[] arguments) {
        if (hasPublishedActivation(arguments)) {
            return publishedActivation(arguments).methodHomeOrNullForRuntime();
        }
        return isFunctionCall(arguments) ? compactClosure(arguments).methodHome().orElse(null)
                : (ProtosObjectValue) arguments[METHOD_HOME_INDEX];
    }

    /** Whether a B5 array built for the dispatcher is an unbound function call. */
    static boolean isFunctionCall(Object[] arguments) {
        return arguments[RECEIVER_INDEX] == DIRECT_CLOSURE_CALL;
    }

    /**
     * Carries Task provenance independently of caller publication. A function's
     * explicit Task wins; a method keeps its methodHome and transports its Task
     * through the exact caller instead. No caller chains are traversed.
     */
    static ProtosTask inheritInvocationTask(Object[] arguments, ProtosTask inherited) {
        if (isFunctionCall(arguments)) {
            Object explicit = arguments[TASK_INDEX];
            if (explicit instanceof ProtosTask task) {
                return task;
            }
            if (explicit != null) {
                throw new IllegalArgumentException("function Task slot has an invalid representation");
            }
            arguments[TASK_INDEX] = inherited;
        }
        return inherited;
    }

    /** Writes the receiver and methodHome of a selected method. */
    static void bindMethod(Object[] arguments, Object receiver, ProtosObjectValue methodHome) {
        arguments[RECEIVER_INDEX] = receiver;
        arguments[METHOD_HOME_INDEX] = methodHome;
    }

    /** Writes the dispatcher-owned header slots. */
    static void completeDispatchedHeader(
            Object[] arguments,
            ProtosClosureValue closure,
            Object callerReference,
            ProtosReturnHome returnHome) {
        arguments[CLOSURE_INDEX] = closure;
        arguments[CALLER_INDEX] = callerReference;
        arguments[RETURN_HOME_INDEX] = Objects.requireNonNull(returnHome, "returnHome");
    }

    /** The receiver slot value of an unbound function call. */
    static Object functionCallMarker() {
        return DIRECT_CLOSURE_CALL;
    }

    /**
     * The materialized ReturnHome this
     * activation owns, or {@code null} when its home is captured from an
     * outer activation, is the unobservable marker, or the arguments are not
     * a B5 Closure header. Read from the header, so the identity is exactly
     * the one the dispatcher wrote.
     */
    static ProtosReturnHome ownedReturnHomeOrNull(Object[] arguments) {
        if (arguments.length < HEADER_LENGTH) {
            return null;
        }
        Object owner = arguments[CLOSURE_INDEX];
        ProtosReturnHome home;
        if (owner instanceof ProtosActivation activation) {
            if (!activation.ownsReturnHome()) {
                return null;
            }
            home = activation.returnHomeOrNullForRuntime();
        } else if (owner instanceof ProtosClosureValue closure
                && arguments[RETURN_HOME_INDEX] instanceof ProtosReturnHome slot) {
            if (closure.capturedReturnHomeOrNullForRuntime() != null) {
                return null;
            }
            home = slot;
        } else {
            return null;
        }
        return home != null && home.isMaterialized() ? home : null;
    }

    /**
     * PERF038-I: trusted B5+ construction for the admitted direct tier of
     * InvokeMethodOneTail: an immediate method call (slots 1 and 2 hold the
     * receiver and the selected method home) whose caller slot carries the
     * demand-selected null, Prelude, or exact Activation. The guarded selection proved
     * a source Closure with an unobservable ReturnHome; the primitive-result
     * accepting marker lets the callee return a numeric carrier, which the
     * caller root's return epilogue projects. No validation, caller
     * publication or intermediate array occurs here.
     */
    static Object[] compactAdmittedTerminalMethodCallOneNoHome(
            ProtosClosureValue closure,
            Object receiver,
            ProtosObjectValue methodHome,
            Object callerReference,
            Object supplied0) {
        return new Object[] {
            closure,
            receiver,
            methodHome,
            callerReference,
            ProtosReturnHome.unobservableAcceptingPrimitiveResultForRuntime(),
            supplied0
        };
    }

    /**
     * Returns an already transported exact caller Activation unchanged.
     * Null and Prelude payloads contain insufficient information and are
     * rejected here, as are arrays and wrappers. Cold recovery is performed
     * by {@link #resolveCallerSlot} only while the guest caller is active;
     * it registers exact-activation demand for later calls.
     */
    static ProtosActivation resolveExactCaller(Object callerReference) {
        if (callerReference instanceof ProtosActivation activation) {
            return activation;
        }
        throw new IllegalStateException(
                "I115: exact caller activation was not transported");
    }



    static ProtosActivation resolveAdmittedCaller(Object callerReference) {
        return resolveExactCaller(callerReference);
    }





    /**
     * Resolves this invocation's legal B5[3] payload. An exact Activation
     * answers directly; null or Prelude requires cold active-stack recovery,
     * which records NEEDS_EXACT_ACTIVATION on the callee. The current frame's
     * arguments identify the invocation in that walk and are never retained
     * by another invocation.
     */
    private static ProtosActivation resolveCallerSlot(Object[] arguments) {
        return resolveCallerSlot(arguments, true);
    }

    private static ProtosActivation resolveCallerSlot(
            Object[] arguments, boolean registerCallerDemand) {
        Object callerReference = arguments[CALLER_INDEX];

        // A transported Prelude is only partial caller information.
        // Recover the exact activation on first demand, then let the
        // callee register NEEDS_EXACT_ACTIVATION for subsequent calls.
        if (callerReference == null || callerReference instanceof ProtosPrelude) {
            return ProtosReadCallerFrameNode.resolve(arguments, registerCallerDemand);
        }

        return resolveExactCaller(callerReference);
    }

    /**
     * cold suspension-boundary caller seal.
     *
     * <p>Called by the dispatcher only after the invocation described by
     * {@code calleeArguments} returned a suspended outcome, while the caller
     * frame is still live. The pending continuation retains this exact B5
     * array; once resumed, the original caller frame is no longer on the
     * active stack, so {@link ProtosReadCallerFrameNode} cannot answer it.
     * Sealing replaces an insufficient null or Prelude B5[3] with the exact
     * caller Activation established now.
     *
     * <p>A published B5[0] already carries its provenance, and an exact
     * B5[3] is kept, so repeated suspension is idempotent. B5[0], B5[1],
     * B5[2], B5[4] and the supplied arguments are never touched. The caller
     * comes only from the live caller frame arguments or the explicit
     * host/native caller; nothing is synthesized and the Task is never a
     * substitute. No caller demand is registered on either root: the
     * ordinary non-suspending path keeps its compact header unchanged.
     *
     * @param callerFrameArguments the live caller frame's arguments, or
     *     {@code null} for a frame-less host/native entry
     * @param explicitCaller the explicit caller of a host/native entry: an
     *     exact Activation, the live caller root's own header, or other
     *     partial information that is not used
     */
    @TruffleBoundary
    static void sealSuspendedCaller(
            Object[] calleeArguments,
            Object[] callerFrameArguments,
            Object explicitCaller) {
        if (calleeArguments == null
                || calleeArguments.length < HEADER_LENGTH
                || calleeArguments[CLOSURE_INDEX] instanceof ProtosActivation
                || calleeArguments[CALLER_INDEX] instanceof ProtosActivation
                || !isCompactCall(calleeArguments)) {
            return;
        }
        ProtosActivation caller = null;
        if (callerFrameArguments != null) {
            caller = liveCallerActivation(callerFrameArguments);
        } else if (explicitCaller instanceof ProtosActivation activation) {
            caller = activation;
        } else if (explicitCaller instanceof Object[] liveCallerHeader) {
            caller = liveCallerActivation(liveCallerHeader);
        }
        if (caller != null) {
            calleeArguments[CALLER_INDEX] = caller;
        }
    }

    /*
     * The exact Activation of a live caller frame: its published B5[0], or
     * the materialization of its compact header. A caller that was itself
     * resumed was sealed when it suspended, so any cold recovery here runs
     * only for a caller whose own caller is still active. Materialization
     * publishes only the caller's own slot 0 and registers no demand.
     */
    private static ProtosActivation liveCallerActivation(Object[] callerArguments) {
        if (callerArguments.length > CLOSURE_INDEX
                && callerArguments[CLOSURE_INDEX] instanceof ProtosActivation activation) {
            return activation;
        }
        if (!isCompactCall(callerArguments)) {
            return null;
        }
        return materializeCompactActivation(callerArguments, false);
    }







    /**
     * Admitted Send0 with a proven unobservable ReturnHome. The caller
     * payload is null, a Prelude, or an exact Activation, as selected by the
     * producer from callee demand. Construction does not publish a caller.
     */
    static Object[] compactImmediateMethodCallZeroNoHome(
            ProtosClosureValue closure,
            Object receiver,
            ProtosObjectValue methodHome,
            Object callerReference) {
        return new Object[] {
            closure,
            receiver,
            methodHome,
            callerReference,
            ProtosReturnHome.unobservableAcceptingPrimitiveResultForRuntime()
        };
    }

    /**
     * I102: the B5 frame arguments of an ordinary Closure entry whose exact
     * activation already exists (the generic, rich invocation path). The
     * activation is published in argument 0 from the start, so every reader
     * takes its published path and that activation remains the invocation's
     * single identity.
     *
     * <p>No caller, receiver or return home is synthesized. Slots 1 and 2
     * carry the activation's own receiver and method home when it has a method
     * home, otherwise the direct-call tag and no explicit Task (the activation
     * owns its Task state). The caller slot is {@code null}: the activation
     * already holds every provenance field, so no caller is ever consulted in
     * published state. The return-home slot is the activation's exact home,
     * and the supplied values follow at {@link #SUPPLIED_ARGUMENT_OFFSET}.
     *
     * <p>Published-state contract: the caller slot is read only behind an
     * "argument 0 is still the Closure" guard ({@link
     * #isUnmaterializedCompactCall} or the full {@link #hasCompactHeader}
     * validation), so {@code null} there is never taken for a caller nor
     * dereferenced; prelude, Task, receiver, method home and arguments of a
     * published invocation are read from its activation. Copying the supplied
     * values reads the activation's deferred value list (or its existing
     * guest Array) and never creates the guest Array or the Context.
     *
     * <p>preparation registers a published physical ReturnHome
     * owner with the root of {@code effectiveTarget} before the target may
     * execute, so a proven no-home root switches to owned-home completion.
     */
    static Object[] publishedInvocation(
            ProtosActivation activation,
            RootCallTarget effectiveTarget) {
        Objects.requireNonNull(activation, "activation");
        /*
         * Every Closure invocation activation receives its home from
         * ProtosClosureValue#invocationReturnHomeForRuntime (never null) or
         * from a validated compact header; only non-invocation activations
         * (module, bootstrap, top-level) have none, and they never enter
         * through this ABI.
         */
        ProtosReturnHome returnHome =
                activation.returnHome()
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "ordinary Closure entry requires the invocation's"
                                                        + " return home; this activation is not a"
                                                        + " Closure invocation"));
        ProtosBytecodeRootNode effectiveRoot =
                ProtosFunctionCallDispatchers.calleeRootOrNull(effectiveTarget);
        if (effectiveRoot != null) {
            effectiveRoot.admitPublishedPhysicalOwner(activation);
        }
        List<?> supplied = activation.suppliedArgumentsForRuntime();
        Object[] arguments = new Object[SUPPLIED_ARGUMENT_OFFSET + supplied.size()];
        arguments[CLOSURE_INDEX] = activation;
        ProtosObjectValue methodHome = activation.methodHome().orElse(null);
        if (methodHome != null) {
            arguments[RECEIVER_INDEX] = activation.receiver();
            arguments[METHOD_HOME_INDEX] = methodHome;
        } else {
            arguments[RECEIVER_INDEX] = DIRECT_CLOSURE_CALL;
        }
        arguments[RETURN_HOME_INDEX] = returnHome;
        for (int index = 0; index < supplied.size(); index++) {
            arguments[SUPPLIED_ARGUMENT_OFFSET + index] = supplied.get(index);
        }
        return arguments;
    }

    private static Object[] headerWithSupplied(
            ProtosClosureValue closure,
            Object kindOrReceiver,
            Object methodHomeOrTask,
            ProtosActivation caller,
            ProtosReturnHome returnHome,
            Object[] supplied) {
        Object[] arguments = new Object[SUPPLIED_ARGUMENT_OFFSET + supplied.length];
        arguments[CLOSURE_INDEX] = closure;
        arguments[RECEIVER_INDEX] = kindOrReceiver;
        arguments[METHOD_HOME_INDEX] = methodHomeOrTask;
        arguments[CALLER_INDEX] = caller;
        arguments[RETURN_HOME_INDEX] = returnHome;
        System.arraycopy(supplied, 0, arguments, SUPPLIED_ARGUMENT_OFFSET, supplied.length);
        return arguments;
    }

    static boolean hasActivation(Frame frame) {
        if (frame == null) {
            return false;
        }
        return hasActivation(frame.getArguments());
    }

    static boolean hasActivation(Object[] arguments) {
        return arguments.length > 0
                && (arguments[0] instanceof ProtosActivation
                        || isCompactCall(arguments));
    }

    static ProtosActivation activation(Frame frame) {
        if (frame == null) {
            throw new IllegalStateException(
                    "Execution node requires Protos frame arguments");
        }
        return activation(frame.getArguments());
    }

    /**
     * PERF038-C: true when argument 0 already holds the exact activation of
     * this invocation (a published B5 entry, or a compact call whose
     * activation was published by {@link #materializeCompactActivation}).
     * Exactly the first branch of {@link #activation(Object[])}, so a caller
     * that guards on it may read {@link #publishedActivation} without the
     * materialization path.
     */
    static boolean hasPublishedActivation(Object[] arguments) {
        return arguments.length > CLOSURE_INDEX
                && arguments[CLOSURE_INDEX] instanceof ProtosActivation;
    }

    /** The activation already published in argument 0; see {@link #hasPublishedActivation}. */
    static ProtosActivation publishedActivation(Object[] arguments) {
        return (ProtosActivation) arguments[CLOSURE_INDEX];
    }

    /**
     * Returns the exact activation of a target-argument array, materializing
     * and publishing it into argument 0 when it is still in compact form.
     */
    static ProtosActivation activation(Object[] arguments) {
        if (arguments.length > 0
                && arguments[0] instanceof ProtosActivation activation) {
            return activation;
        }
        return materializeCompactActivation(arguments, true);
    }

    /*
     * TEST009-E: materializing a compact call's activation is the rare path of
     * activation(Object[]) and is host-side object construction with no
     * partial-evaluation value; inlined, it was expanded into every operation
     * that may consult the current activation (several hundred nodes per site).
     */
    @TruffleBoundary
    private static ProtosActivation materializeCompactActivation(
            Object[] arguments, boolean registerCallerDemand) {
        if (!isCompactCall(arguments)) {
            throw new IllegalStateException(
                    "Execution node requires a Protos activation or compact source-call ABI");
        }

        ProtosClosureValue closure =
                (ProtosClosureValue) arguments[CLOSURE_INDEX];

        /*
         * Resolve the exact transported caller, or recover it on the active
         * guest stack. Publication changes only this invocation's slot 0.
         */
        ProtosActivation caller = resolveCallerSlot(arguments, registerCallerDemand);
        ProtosReturnHome returnHome = normalizedReturnHome(arguments[RETURN_HOME_INDEX]);
        /*
         * The supplied values stay backed by this frame-argument array: the
         * user-argument range is written once by its constructor and never
         * mutated, so the activation adopts it as a read-only view rather
         * than copying it.
         */
        List<?> supplied =
                ProtosActivation.frameBackedSuppliedArgumentsForRuntime(
                        arguments, SUPPLIED_ARGUMENT_OFFSET);

        ProtosActivation materialized;
        ProtosTask explicitTask = null;
        if (isDirectClosureCall(arguments)) {
            explicitTask = (ProtosTask) arguments[TASK_INDEX];
            materialized =
                    ProtosActivation.forDirectClosureInvocationWithReturnHomeForRuntime(
                            closure,
                            supplied,
                            caller.prelude().orElse(null),
                            caller.actorModuleState(),
                            caller.currentModuleKey().orElse(null),
                            caller.executionDomain(),
                            returnHome);
        } else {
            materialized =
                    ProtosActivation.forImmediateMethodInvocationWithReturnHomeForRuntime(
                            closure,
                            supplied,
                            arguments[RECEIVER_INDEX],
                            (ProtosObjectValue) arguments[METHOD_HOME_INDEX],
                            caller.prelude().orElse(null),
                            caller.actorModuleState(),
                            caller.currentModuleKey().orElse(null),
                            caller.executionDomain(),
                            returnHome);
        }

        if (explicitTask != null) {
            materialized.attachTask(explicitTask);
        } else if (caller.task().isPresent()) {
            materialized.attachTask(caller.task().orElseThrow());
        } else {
            materialized.inheritDynamicControlState(caller);
        }

        /*
         * Debugger/NodeLibrary observation may request the activation before
         * the semantic helper operation does. Publish the exact materialized
         * activation back into this frame so every observer and the helper
         * share one fresh guest execution-context identity.
         */
        arguments[CLOSURE_INDEX] = materialized;
        return materialized;
    }

    /**
     * Capture by a compact root. A creator capture publishes its exact
     * activation while caller provenance is still available, but leaves its
     * guest Context deferred. An inherited-only capture reuses the existing
     * lexical chain without publishing this root.
     *
     * <p>when this invocation's exact activation is already
     * published (for example because its frame was bound to a frame-backed
     * lexical view), that activation is reused as the one owner: the Closure
     * captures its lexical environment node, so it reaches the same live
     * physical bindings as the creator, and keeps its receiver, method home,
     * ReturnHome, Prelude and module key. No second owner is reconstructed.
     *
     * <p>PLAT066-C: when {@code capturesCreatorContext} is false the
     * lowering classified {@code definition} as not capturing its creator's
     * execution context, and the new Closure receives the creator's own
     * captured environment (the invoked Closure's, B5 argument 0) unchanged:
     * no deferred owner of this invocation is created, so nothing retains the
     * frame or its arguments. A recorded divergence from the unconditional
     * capture of {@code CALLABLES.md}, pending an owner decision.
     *
     * <p>: when the definition also cannot observe its
     * ReturnHome and needs no inherited environment, such a Closure is the
     * lightweight {@link ProtosClosureValue#experimentalNonCapturing}
     * representation; it retains only the creator's admitted Prelude, and
     * the creator's environment, receiver, methodHome and ReturnHome are not
     * consulted. A definition that
     * may observe its ReturnHome (for example {@code (value) => { ^value }})
     * keeps the construction with the creator's ReturnHome.
     */
    static ProtosClosureValue materializeCompactClosure(
            Object[] arguments,
            CanonicalClosure definition,
            ProtosClosureExecutionPlanCell executionPlanCell,
            boolean capturesCreatorContext,
            boolean requiresInheritedLexicalEnvironment,
            boolean mayObserveReturnHome) {
        if (!capturesCreatorContext && !requiresInheritedLexicalEnvironment && !mayObserveReturnHome) {
            return ProtosClosureValue.experimentalNonCapturing(
                    definition,
                    executionPlanCell.plan(),
                    definingModuleKeyForClosureCreation(arguments),
                    creatorPreludeForClosureCreation(arguments));
        }
        if (hasPublishedActivation(arguments)) {
            ProtosActivation owner = publishedActivation(arguments);
            return new ProtosClosureValue(
                    definition,
                    capturesCreatorContext
                            ? owner.lexicalEnvironmentForClosureCapture()
                            : owner.capturedLexicalEnvironmentForRuntime(),
                    owner.receiver(),
                    owner.methodHomeOrNullForRuntime(),
                    owner.returnHomeOrNullForRuntime(),
                    owner.preludeOrNullForRuntime(),
                    executionPlanCell.plan(),
                    owner.currentModuleKey().orElse(null));
        }
        ProtosClosureValue currentClosure = compactClosure(arguments);
        boolean direct = isDirectClosureCall(arguments);
        Object receiver = direct
                ? currentClosure.capturedReceiver()
                : arguments[RECEIVER_INDEX];
        ProtosObjectValue methodHome = direct
                ? currentClosure.methodHome().orElse(null)
                : (ProtosObjectValue) arguments[METHOD_HOME_INDEX];
        ProtosLexicalEnvironment environment = capturesCreatorContext
                ? ProtosLexicalEnvironment.deferredCompactOwner(
                        arguments,
                        COMPACT_CAPTURE_OWNER)
                : currentClosure.capturedLexicalEnvironmentForRuntime();
        return new ProtosClosureValue(
                definition,
                environment,
                receiver,
                methodHome,
                compactReturnHome(arguments),
                preludeOrNull(arguments),
                executionPlanCell.plan(),
                currentClosure.definingModuleKeyOrNullForRuntime());
    }

    /** The lexical module origin of a Closure created in this invocation. */
    static ProtosModuleKey definingModuleKeyForClosureCreation(
            Object[] arguments) {
        if (hasPublishedActivation(arguments)) {
            return publishedActivation(arguments)
                    .currentModuleKey().orElse(null);
        }
        return compactClosure(arguments)
                .definingModuleKeyOrNullForRuntime();
    }

    /**
     * PERF025-H1: true while {@code arguments} are still in compact
     * source-call form, i.e. no rich activation has been materialized and
     * published for this invocation yet. The invocation's execution context
     * is then necessarily unobserved and has no lexical-binding authority
     * installed, so the root's frame locals are the only store of its
     * frame-native bindings.
     *
     * <p>PERF038-B: this is the compact/materialized discriminator of an
     * already admitted Closure-root (or inline-callback carrier) argument
     * array, not an ABI validator. Every such array is a B5 array whose
     * argument 0 is either the invoked Closure or the published activation,
     * built by one of this class's constructors and validated by its
     * call carrier
     * before entry; the only later write of argument 0 is {@link
     * #materializeCompactActivation}, which replaces the Closure with the
     * published activation. Argument 0 alone therefore distinguishes the two
     * states, and the full header re-validation is not repeated at every
     * root-level frame operation. The materialization slow path still runs
     * the full validation.
     */
    static boolean isUnmaterializedCompactCall(Object[] arguments) {
        return arguments.length > CLOSURE_INDEX
                && arguments[CLOSURE_INDEX] instanceof ProtosClosureValue;
    }

    /**
     * PERF034-D: the compact/materialized discriminator of a scalar-local
     * lane. It is not an ABI validator: an arbitrary array whose argument 0
     * is a {@link ProtosClosureValue} (for example {@code {closure}}) yields
     * {@code true}.
     *
     * <p>Precondition: {@code arguments} are the B5 frame arguments of a
     * Closure root admitted by the PERF034-C scalar-local lowering. It is
     * re-read at every use so a materialization between two instructions is
     * observed. Since PERF038-B this is the same discriminator as {@link
     * #isUnmaterializedCompactCall}, under the same precondition.
     */
    static boolean isUnmaterializedCompactScalarLocalCall(Object[] arguments) {
        return isUnmaterializedCompactCall(arguments);
    }

    /**
     * PERF037-B: the captured lexical chain the activation of a
     * still-unmaterialized compact call has (or would have once
     * materialized): the invoked Closure's own, for both invocation kinds.
     * Precondition: {@link #isUnmaterializedCompactCall}.
     */
    static ProtosLexicalEnvironment compactCapturedLexicalEnvironment(Object[] arguments) {
        return ((ProtosClosureValue) arguments[CLOSURE_INDEX])
                .capturedLexicalEnvironmentForRuntime();
    }


    /**
     * The Prelude a new Closure retains from its creator: the admitted one
     * read from the creator's header, without publishing an activation. When
     * the creator inherits a Prelude its caller did not transport, the
     * non-publishing recovery runs behind a boundary.
     */
    static ProtosPrelude creatorPreludeForClosureCreation(Object[] arguments) {
        if (arguments.length >= HEADER_LENGTH) {
            Object first = arguments[CLOSURE_INDEX];
            if (first instanceof ProtosActivation activation) {
                return activation.preludeOrNullForRuntime();
            }
            if (first instanceof ProtosClosureValue closure) {
                ProtosPrelude own = closure.preludeOrNullForRuntime();
                if (own != null) {
                    return own;
                }
                ProtosPrelude transported = callerPreludeOrNull(arguments);
                if (transported != null) {
                    return transported;
                }
            }
        }
        return recoveredCreatorPrelude(arguments);
    }

    @TruffleBoundary
    private static ProtosPrelude recoveredCreatorPrelude(Object[] arguments) {
        return preludeOrNull(arguments);
    }

    static ProtosPrelude preludeOrNull(Object[] arguments) {
        if (hasPublishedActivation(arguments)) {
            return publishedActivation(arguments).preludeOrNullForRuntime();
        }
        ProtosPrelude own = compactClosure(arguments).preludeOrNullForRuntime();
        return own != null ? own : callerPreludeOrNull(arguments);
    }


    /**
     * Reads the running root's own admitted B5 header without publishing it.
     * A published activation or the Closure's own Prelude takes precedence;
     * otherwise slot 3 supplies a Prelude or an exact Activation's Prelude.
     * Null means no transported Prelude. The dispatcher registers inherited
     * Prelude demand when needed so later source calls transport it. This
     * accessor does not recover an exact caller from a synthetic header.
     */
    static ProtosPrelude admittedPreludeOrNull(Object[] arguments) {
        if (arguments.length >= HEADER_LENGTH) {
            Object first = arguments[CLOSURE_INDEX];
            if (first instanceof ProtosActivation activation) {
                return activation.preludeOrNullForRuntime();
            }
            if (first instanceof ProtosClosureValue closure) {
                ProtosPrelude own = closure.preludeOrNullForRuntime();
                if (own != null) {
                    return own;
                }
                ProtosPrelude transported = callerPreludeOrNull(arguments);
                if (transported == null
                        && arguments[CALLER_INDEX] == null
                        && CompilerDirectives.inInterpreter()) {
                    /*
                     * The caller does not transport the inherited Prelude yet:
                     * the root registers the demand so later calls transport it,
                     * and this once it is read from the calling frames.
                     */
                    return callingFramePreludeOrNull(arguments);
                }
                return transported;
            }
        }
        CompilerDirectives.transferToInterpreter();
        return preludeOrNull(arguments);
    }

    /**
     * The Prelude captured by the running compact invocation's own Closure,
     * or {@code null} when the arguments are not a B5 header, the invocation
     * already published its activation, or the Closure captured no Prelude.
     * A non-null answer is exactly what {@link #admittedPreludeOrNull} answers
     * for the same arguments; a null answer leaves the Prelude to that general
     * resolution, which keeps caller inheritance. Reads only the Closure's
     * final field: nothing is published, materialized or sent.
     */
    static ProtosPrelude capturedClosurePreludeOrNull(Object[] arguments) {
        if (arguments.length < HEADER_LENGTH) {
            return null;
        }
        Object first = arguments[CLOSURE_INDEX];
        if (first instanceof ProtosClosureValue closure) {
            return closure.preludeOrNullForRuntime();
        }
        return null;
    }

    /**
     * The Prelude of the nearest calling Protos frame that observes one, read
     * by walking the stack. Only the first invocation of a root whose caller
     * did not yet transport its inherited Prelude takes this path.
     */
    @TruffleBoundary
    private static ProtosPrelude callingFramePreludeOrNull(Object[] current) {
        return com.oracle.truffle.api.Truffle.getRuntime().iterateFrames(frameInstance -> {
            if (!(frameInstance.getCallTarget() instanceof com.oracle.truffle.api.RootCallTarget target)
                    || !(target.getRootNode() instanceof ProtosBytecodeRootNode)) {
                return null;
            }
            Object[] frameArguments = frameInstance
                    .getFrame(com.oracle.truffle.api.frame.FrameInstance.FrameAccess.READ_ONLY)
                    .getArguments();
            if (frameArguments == current || frameArguments.length <= CLOSURE_INDEX) {
                return null;
            }
            Object first = frameArguments[CLOSURE_INDEX];
            if (first instanceof ProtosActivation activation) {
                return activation.preludeOrNullForRuntime();
            }
            if (first instanceof ProtosClosureValue closure
                    && frameArguments.length >= HEADER_LENGTH) {
                ProtosPrelude own = closure.preludeOrNullForRuntime();
                return own != null ? own : callerPreludeOrNull(frameArguments);
            }
            return null;
        });
    }


    static boolean inheritsUntransportedCallerPrelude(Object[] arguments) {
        return arguments.length >= HEADER_LENGTH
                && arguments[CLOSURE_INDEX] instanceof ProtosClosureValue closure
                && closure.preludeOrNullForRuntime() == null
                && arguments[CALLER_INDEX] == null;
    }



    private static ProtosPrelude callerPreludeOrNull(Object[] arguments) {
        Object caller = arguments[CALLER_INDEX];
        if (caller instanceof ProtosPrelude prelude) {
            return prelude;
        }
        if (caller instanceof ProtosActivation activation) {
            return activation.preludeOrNullForRuntime();
        }
        return null;
    }


    /*
     * recovery of an inherited prelude that was not transported (the
     * first invocations before the need was registered, or a producer that
     * cannot consult its callee's needs). The general walker validates every
     * link it follows, rejects cycles of any length and publishes nothing.
     * Its @TruffleBoundary is the physical separation; it makes no explicit
     * transfer to the interpreter, so a producer that never transports does
     * not invalidate compiled code repeatedly.
     */





    /**
     * Supplied positional argument count of a B5 call, before or after
     * publication: the length never changes and the supplied values always
     * start at {@link #SUPPLIED_ARGUMENT_OFFSET}.
     */
    static int compactSuppliedArgumentCount(Object[] arguments) {
        return arguments.length - SUPPLIED_ARGUMENT_OFFSET;
    }

    /**
     * PERF038-E: {@link #compactSuppliedArgument} for an {@code index} already
     * proven below {@link #compactSuppliedArgumentCount} of the same array by
     * the caller's guard.
     */
    static Object compactSuppliedArgumentWithinCount(Object[] arguments, int index) {
        return arguments[SUPPLIED_ARGUMENT_OFFSET + index];
    }

    /**
     * PERF038-F: exactly {@code index < compactSuppliedArgumentCount(arguments)}
     * for a non-negative {@code index}.
     */
    static boolean compactHasSuppliedArgument(Object[] arguments, int index) {
        return arguments.length > SUPPLIED_ARGUMENT_OFFSET + index;
    }

    /**
     * PERF038-F: exactly {@code compactSuppliedArgumentCount(arguments) <=
     * maximum} for a non-negative {@code maximum}.
     */
    static boolean compactSuppliedArgumentCountAtMost(Object[] arguments, int maximum) {
        return arguments.length <= SUPPLIED_ARGUMENT_OFFSET + maximum;
    }

    /**
     * D203: supplied positional argument {@code index} of a B5 call, or
     * canonical null when the call supplied fewer arguments. The B5 header is
     * unchanged; only the supplied suffix length is consulted.
     */
    static Object compactSuppliedArgumentOrNull(Object[] arguments, int index) {
        return arguments.length > SUPPLIED_ARGUMENT_OFFSET + index
                ? arguments[SUPPLIED_ARGUMENT_OFFSET + index]
                : ProtosNullValue.INSTANCE;
    }

    /**
     * the number of supplied positional arguments of a B5 call, in
     * either state (publication never changes the supplied suffix).
     */
    static int suppliedArgumentCount(Object[] arguments) {
        return arguments.length - SUPPLIED_ARGUMENT_OFFSET;
    }

    /**
     * D237 / presence of a supplied formal parameter.
     *
     * Positional compact calls retain the B5 suffix-count test.
     * Published named calls use their association presence mask;
     * an explicitly supplied canonical null is still present.
     */
    static boolean hasSuppliedParameter(
            Object[] arguments, int index) {
        if (index < 0) {
            throw new IllegalArgumentException(
                    "negative formal parameter index");
        }

        if (hasPublishedActivation(arguments)) {
            return publishedActivation(arguments)
                    .hasSuppliedParameterForRuntime(index);
        }

        return suppliedArgumentCount(arguments) > index;
    }

    /**
     * the value of a rest parameter, read from the B5 supplied suffix:
     * the frozen Array of the supplied arguments after the first {@code
     * positionalParametersBeforeRest}, as guest values.
     */
    @TruffleBoundary
    static Object restArgumentArray(Object[] arguments, int positionalParametersBeforeRest) {
        int count = suppliedArgumentCount(arguments);
        java.util.ArrayList<Object> rest = new java.util.ArrayList<>();
        for (int index = positionalParametersBeforeRest; index < count; index++) {
            rest.add(ProtosNumericValueSupport.guestValue(
                    arguments[SUPPLIED_ARGUMENT_OFFSET + index]));
        }
        ProtosPrelude prelude = preludeOrNull(arguments);
        if (prelude == null) {
            throw new IllegalStateException(
                    "parameter binding requires an owning Core prelude");
        }
        return prelude.newFrozenArray(rest);
    }

    /** Supplied positional argument {@code index} of a B5 call. */
    static Object compactSuppliedArgument(Object[] arguments, int index) {
        if (index < 0) {
            /* Never expose a header slot as a guest argument. */
            throw new ArrayIndexOutOfBoundsException(index);
        }
        return arguments[SUPPLIED_ARGUMENT_OFFSET + index];
    }

    /** The callee Closure of a compact source call that is still unmaterialized. */
    /**
     * the captured cells of the invoked Closure, read once at root
     * entry from argument 0: the invoked Closure of a compact call, or the
     * published activation, which
     * refers to the same array. Called only by
     * roots whose scope declares free cells; each such root copies the cells
     * into its own Bytecode locals, so no later access reads this header.
     */
    static ProtosLexicalCell[] capturedCells(Object[] arguments) {
        Object invocation = arguments[CLOSURE_INDEX];
        if (invocation instanceof ProtosClosureValue closure) {
            return closure.capturedCellsForRuntime();
        }
        if (invocation instanceof ProtosActivation activation) {
            return activation.capturedCellsForRuntime();
        }
        throw CompilerDirectives.shouldNotReachHere(
                "I115 cell-capturing root entered without its Closure");
    }

    static ProtosClosureValue compactClosure(Object[] arguments) {
        requireCompactCall(arguments);
        return (ProtosClosureValue) arguments[CLOSURE_INDEX];
    }

    /**
     * PERF025 slice 3: the compact caller of a still-unmaterialized direct
     * Closure call (or, since PERF038-D, compact immediate method call) whose
     * callee would inherit every provenance field from it, or {@code null}.
     * {@link #activation(Object[])} materializes such a call with exactly this
     * caller's prelude (the Closure has none of its own, or the same one),
     * actor module state, current module key, execution domain and Task or
     * dynamic-control state (no explicit Task). A child invocation prepared
     * with this caller therefore materializes exactly as it would with the
     * not-yet-materialized callee activation as its caller.
     *
     * <p>A compact immediate method call never carries an explicit Task, and
     * its materialization ({@link
     * ProtosActivation#forImmediateMethodInvocationWithReturnHomeForRuntime})
     * takes the same caller-derived provenance as a direct Closure call's;
     * only its receiver and method home differ, and those are not provenance.
     */
    static ProtosActivation compactInheritedProvenanceCaller(Object[] arguments) {
        boolean inheritsAll =
                isDirectClosureCall(arguments)
                        ? explicitDirectTask(arguments) == null
                        : isCompactImmediateMethodCall(arguments);
        if (!inheritsAll) {
            return null;
        }
        return callerIfPreludeInherited(arguments);
    }

    /**
     * The exact caller when the activation of the invoked Closure has the
     * caller's prelude (the Closure has none of its own, or the same one),
     * else {@code null}. PERF038-E: reads the nullable prelude directly, so
     * no {@code Optional} is built. Only an exact Activation in slot 3
     * answers this relation; partial null/Prelude payloads return null
     * without attempting cold recovery.
     */
    private static ProtosActivation callerIfPreludeInherited(
            Object[] arguments) {
        Object caller = arguments[CALLER_INDEX];
        if (!(caller instanceof ProtosActivation activation)) {
            return null;
        }
        ProtosPrelude own =
                ((ProtosClosureValue) arguments[CLOSURE_INDEX])
                        .preludeOrNullForRuntime();
        return own == null || own == activation.preludeOrNullForRuntime()
                ? activation
                : null;
    }


    /**
     * PERF038-D: exactly {@link #compactInheritedProvenanceCaller} for the
     * still-unmaterialized frame arguments of an admitted Closure root (the
     * {@link #isUnmaterializedCompactCall} precondition), decided by the
     * fixed B5 slots instead of re-validating the ABI: the receiver slot is
     * the direct-call marker exactly for a direct Closure call, whose task
     * slot is then its explicit Task or {@code null}.
     */
    static ProtosActivation unmaterializedInheritedProvenanceCaller(Object[] arguments) {
        if (arguments[RECEIVER_INDEX] == DIRECT_CLOSURE_CALL
                && arguments[TASK_INDEX] != null) {
            return null;
        }
        return callerIfPreludeInherited(arguments);
    }

    /**
     * PERF038-F: true when the frame arguments of an admitted Closure root
     * are still unmaterialized and their callee inherits its Task or
     * dynamic-control state from the caller: an immediate method call, or a
     * direct Closure call without an explicit Task. Together with {@link
     * #unmaterializedFullHeaderOwnPrelude} {@code ==} the caller's prelude,
     * this is exactly the non-null case of {@link
     * #unmaterializedInheritedProvenanceCaller}, decided without re-deriving
     * the ABI.
     */
    static boolean isUnmaterializedInheritingFullHeader(Object[] arguments) {
        return isUnmaterializedCompactCall(arguments)
                && (arguments[RECEIVER_INDEX] != DIRECT_CLOSURE_CALL
                        || arguments[TASK_INDEX] == null);
    }

    /**
     * PERF038-F: the caller slot of a B5 header. Precondition: {@link
     * #isUnmaterializedInheritingFullHeader}; every such header was built
     * with a caller payload that is never rewritten. An exact Activation
     * answers directly; partial null/Prelude information requires cold
     * recovery from the active guest stack.
     */
    static ProtosActivation unmaterializedFullHeaderCaller(Object[] arguments) {
        Object caller = arguments[CALLER_INDEX];
        if (caller instanceof ProtosActivation activation) {
            return activation;
        }
        return resolveCallerSlot(arguments);
    }

    /**
     * PERF038-F: the invoked Closure's own (final) prelude, or {@code null}.
     * Precondition: {@link #isUnmaterializedInheritingFullHeader}.
     */
    static ProtosPrelude unmaterializedFullHeaderOwnPrelude(Object[] arguments) {
        return ((ProtosClosureValue) arguments[CLOSURE_INDEX]).preludeOrNullForRuntime();
    }

    /**
     * The invocation's exact return home, read from the fixed home slot. The
     * slot survives publication, so this is valid before and after it.
     */
    static ProtosReturnHome compactReturnHome(Object[] arguments) {
        return normalizedReturnHome(arguments[RETURN_HOME_INDEX]);
    }

    /* Only the primitive-result ABI marker is normalized. Missing home
     * information is never a certificate for a completed invocation. */
    private static ProtosReturnHome normalizedReturnHome(Object home) {
        return home == ProtosReturnHome.unobservableAcceptingPrimitiveResultForRuntime()
                ? ProtosReturnHome.unobservable()
                : (ProtosReturnHome) Objects.requireNonNull(home, "invocation returnHome");
    }

    /**
     * I091: whether the caller of this invocation accepts a primitive numeric
     * result. Only a fused direct source call or send writes the private
     * marker into the home slot; argument 0 publication keeps it. The length
     * test admits the frame arguments of non-Closure roots, whose entry
     * contracts are not B5.
     */
    static boolean acceptsPrimitiveResult(Object[] arguments) {
        return arguments.length > RETURN_HOME_INDEX
                && arguments[RETURN_HOME_INDEX]
                        == ProtosReturnHome.unobservableAcceptingPrimitiveResultForRuntime();
    }

    static boolean compactOwnsReturnHome(Object[] arguments) {
        requireCompactCall(arguments);
        return ((ProtosClosureValue) arguments[CLOSURE_INDEX])
                .returnHome()
                .isEmpty();
    }

    /**
     * The exact caller of this invocation. Slot 3 holds null, a Prelude, or
     * an exact Activation; only the latter answers directly. Cold recovery
     * for partial payloads requires an active guest stack and registers the
     * callee's exact-activation demand. No linked argument arrays are followed.
     */
    static ProtosActivation compactCaller(Object[] arguments) {
        return resolveCallerSlot(arguments);
    }

    /**
     * Reads the invocation's Task without materialization. A published
     * activation answers directly; otherwise an explicit Task in slot 2 wins,
     * then an exact Activation in slot 3 supplies its Task. A null or Prelude
     * caller payload supplies no Task. No ancestral frame arrays are retained
     * or traversed.
     */
    static ProtosTask compactTask(Object[] arguments) {
        if (hasPublishedActivation(arguments)) {
            return publishedActivation(arguments).taskForRuntime();
        }
        requireCompactCall(arguments);
        ProtosTask explicit = explicitDirectTask(arguments);
        if (explicit != null) {
            return explicit;
        }
        Object caller = arguments[CALLER_INDEX];
        return caller instanceof ProtosActivation activation
                ? activation.taskForRuntime()
                : null;
    }



    static ProtosTask admittedCompactTask(Object[] arguments) {
        if (arguments.length >= HEADER_LENGTH) {
            Object first = arguments[CLOSURE_INDEX];
            if (first instanceof ProtosActivation activation) {
                return activation.taskForRuntime();
            }
            if (first instanceof ProtosClosureValue) {
                if (arguments[RECEIVER_INDEX] == DIRECT_CLOSURE_CALL
                        && arguments[TASK_INDEX] instanceof ProtosTask explicit) {
                    return explicit;
                }
                Object caller = arguments[CALLER_INDEX];
                return caller instanceof ProtosActivation activation
                        ? activation.taskForRuntime()
                        : null;
            }
        }
        CompilerDirectives.transferToInterpreter();
        return compactTask(arguments);
    }


    /* Non-admitted headers use the general Task accessor. */


    /** The explicit owning Task of a direct Closure call, or {@code null}. */
    private static ProtosTask explicitDirectTask(Object[] arguments) {
        if (isDirectClosureCall(arguments)
                && arguments[TASK_INDEX] instanceof ProtosTask task) {
            return task;
        }
        return null;
    }

    private static boolean isCompactCall(Object[] arguments) {
        return isCompactImmediateMethodCall(arguments)
                || isDirectClosureCall(arguments);
    }

    private static boolean isCompactImmediateMethodCall(Object[] arguments) {
        return hasCompactHeader(arguments)
                && arguments[RECEIVER_INDEX] != null
                && arguments[RECEIVER_INDEX] != DIRECT_CLOSURE_CALL
                && arguments[METHOD_HOME_INDEX] instanceof ProtosObjectValue;
    }

    private static boolean isDirectClosureCall(Object[] arguments) {
        return hasCompactHeader(arguments)
                && arguments[RECEIVER_INDEX] == DIRECT_CLOSURE_CALL
                && (arguments[TASK_INDEX] == null
                        || arguments[TASK_INDEX] instanceof ProtosTask);
    }

    /**
     * Full validation of an unpublished B5 header (slow and carrier paths
     * only). The caller slot must be null, a Prelude, or an exact Activation.
     * Structural validation does not attempt cold stack recovery.
     */
    private static boolean hasCompactHeader(Object[] arguments) {
        return arguments != null
                && arguments.length >= HEADER_LENGTH
                && arguments[CLOSURE_INDEX] instanceof ProtosClosureValue
                && (arguments[CALLER_INDEX] == null
                        || arguments[CALLER_INDEX] instanceof ProtosPrelude
                        || arguments[CALLER_INDEX] instanceof ProtosActivation)
                && (arguments[RETURN_HOME_INDEX] == null
                        || arguments[RETURN_HOME_INDEX] instanceof ProtosReturnHome);
    }




    private static void requireCompactCall(Object[] arguments) {
        if (!isCompactCall(arguments)) {
            throw new IllegalStateException(
                    "Invalid compact ordinary source-call frame arguments");
        }
    }
}
