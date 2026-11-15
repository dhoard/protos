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

import com.guillermomolina.protos.execution.ProtosStructuredControlCalls.PreparedBooleanCall;
import com.guillermomolina.protos.execution.ProtosStructuredControlCalls.PreparedCaseOfCall;
import com.guillermomolina.protos.execution.ProtosStructuredControlCalls.PreparedEnsureCall;
import com.guillermomolina.protos.execution.ProtosStructuredControlCalls.PreparedErrorHandlerCall;
import com.guillermomolina.protos.execution.ProtosStructuredControlCalls.PreparedLoopCall;
import com.guillermomolina.protos.execution.ProtosStructuredControlCalls.PreparedStandardImportCall;
import com.guillermomolina.protos.execution.ProtosStructuredControlCalls.PreparedStandardObjectCall;
import com.guillermomolina.protos.execution.ProtosStructuredEachCalls.PreparedArrayEachCall;
import com.guillermomolina.protos.execution.ProtosStructuredEachCalls.PreparedArrayMatchCall;
import com.guillermomolina.protos.execution.ProtosStructuredEachCalls.PreparedBytesEachCall;
import com.guillermomolina.protos.execution.ProtosStructuredEachCalls.PreparedEnvironmentEachCall;
import com.guillermomolina.protos.execution.ProtosStructuredEachCalls.PreparedIdentityMapAtIfAbsentCall;
import com.guillermomolina.protos.execution.ProtosStructuredEachCalls.PreparedIdentityMapEachCall;
import com.guillermomolina.protos.execution.ProtosStructuredEachCalls.PreparedMapEachCall;
import com.guillermomolina.protos.execution.ProtosStructuredMapQueryCalls.PreparedMapMatchCall;
import com.guillermomolina.protos.execution.ProtosStructuredMapQueryCalls.PreparedMapReadLookupCall;
import com.guillermomolina.protos.execution.ProtosStructuredMapUpdateCalls.PreparedMapAtPutCall;
import com.guillermomolina.protos.execution.ProtosStructuredMapUpdateCalls.PreparedMapRemoveCall;
import com.guillermomolina.protos.runtime.ProtosActivation;
import com.guillermomolina.protos.runtime.ProtosArrayValue;
import com.guillermomolina.protos.runtime.ProtosClosureValue;
import com.guillermomolina.protos.runtime.ProtosCoreErrors;
import com.guillermomolina.protos.runtime.ProtosDynamicControlState;
import com.guillermomolina.protos.runtime.ProtosNumericValueSupport;
import com.guillermomolina.protos.runtime.ProtosObjectValue;
import com.guillermomolina.protos.runtime.ProtosSignalException;
import com.guillermomolina.protos.runtime.callables.ProtosNativeClosureBody;
import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.nodes.ControlFlowException;
import java.util.ArrayList;
import java.util.List;

/**
 * I115: the state carried into the structured-protocol root by a structured
 * standard native ({@link StructuredNativeInvocation}), the module
 * initialization handle of a standard import, and the supplied-argument
 * vector of a spread call. None of these implements a call protocol: every
 * Closure the structured protocols invoke goes through {@link
 * ProtosFunctionCallDispatchers} as a {@link ProtosCallRequest}.
 */
final class ProtosPreparedCalls {
    private ProtosPreparedCalls() {}

    /**
     * Optional structured-control capability carried by a {@link StructuredNativeInvocation}.
     *
     * <p>Ordinary source-backed and plain-native calls never allocate this holder
     * (PLAT040 Candidate F′: optional semantic capability must not force unrelated
     * ordinary calls to carry its full physical machinery). It exists only when a
     * call actually owns one of the mutually exclusive structured-control,
     * collection-callback, or import capabilities below.
     */
    private static final class StructuredCallCapabilities {
        final boolean ensure;
        final boolean errorHandler;
        final ProtosStandardBooleanProtocol.StructuredCallbackKind booleanKind;
        final ProtosStandardObjectProtocol.StandardLoop loop;
        final boolean arrayEach;
        final boolean bytesEach;
        final boolean environmentEach;
        final boolean identityMapEach;
        final boolean mapEach;
        final ProtosStandardMapProtocol.StructuredReadLookupKind mapReadLookup;
        final boolean mapAtPut;
        final boolean mapRemove;
        final boolean directControlNative;
        final boolean objectCall;
        final ProtosModuleRuntime importRuntime;

        private StructuredCallCapabilities(
                boolean ensure,
                boolean errorHandler,
                ProtosStandardBooleanProtocol.StructuredCallbackKind booleanKind,
                ProtosStandardObjectProtocol.StandardLoop loop,
                boolean arrayEach,
                boolean bytesEach,
                boolean environmentEach,
                boolean identityMapEach,
                boolean mapEach,
                ProtosStandardMapProtocol.StructuredReadLookupKind mapReadLookup,
                boolean mapAtPut,
                boolean mapRemove,
                boolean directControlNative,
                boolean objectCall,
                ProtosModuleRuntime importRuntime) {
            this.ensure = ensure;
            this.errorHandler = errorHandler;
            this.booleanKind = booleanKind;
            this.loop = loop;
            this.arrayEach = arrayEach;
            this.bytesEach = bytesEach;
            this.environmentEach = environmentEach;
            this.identityMapEach = identityMapEach;
            this.mapEach = mapEach;
            this.mapReadLookup = mapReadLookup;
            this.mapAtPut = mapAtPut;
            this.mapRemove = mapRemove;
            this.directControlNative = directControlNative;
            this.objectCall = objectCall;
            this.importRuntime = importRuntime;
            int controlCapabilities =
                    (ensure ? 1 : 0)
                            + (errorHandler ? 1 : 0)
                            + (booleanKind != null ? 1 : 0)
                            + (loop != null ? 1 : 0)
                            + (arrayEach ? 1 : 0)
                            + (bytesEach ? 1 : 0)
                            + (environmentEach ? 1 : 0)
                            + (identityMapEach ? 1 : 0)
                            + (mapEach ? 1 : 0)
                            + (mapReadLookup != null ? 1 : 0)
                            + (mapAtPut ? 1 : 0)
                            + (mapRemove ? 1 : 0)
                            + (directControlNative ? 1 : 0)
                            + (objectCall ? 1 : 0)
                            + (importRuntime != null ? 1 : 0);
            if (controlCapabilities > 1) {
                throw new IllegalArgumentException(
                        "prepared Closure call cannot own multiple structured-control capabilities");
            }
        }

        /** Returns {@code null} when every capability is at its default/unset value. */
        static StructuredCallCapabilities of(
                boolean ensure,
                boolean errorHandler,
                ProtosStandardBooleanProtocol.StructuredCallbackKind booleanKind,
                ProtosStandardObjectProtocol.StandardLoop loop,
                boolean arrayEach,
                boolean bytesEach,
                boolean environmentEach,
                boolean identityMapEach,
                boolean mapEach,
                ProtosStandardMapProtocol.StructuredReadLookupKind mapReadLookup,
                boolean mapAtPut,
                boolean mapRemove,
                boolean directControlNative,
                boolean objectCall,
                ProtosModuleRuntime importRuntime) {
            boolean any =
                    ensure
                            || errorHandler
                            || booleanKind != null
                            || loop != null
                            || arrayEach
                            || bytesEach
                            || environmentEach
                            || identityMapEach
                            || mapEach
                            || mapReadLookup != null
                            || mapAtPut
                            || mapRemove
                            || directControlNative
                            || objectCall
                            || importRuntime != null;
            if (!any) {
                return null;
            }
            return new StructuredCallCapabilities(
                    ensure,
                    errorHandler,
                    booleanKind,
                    loop,
                    arrayEach,
                    bytesEach,
                    environmentEach,
                    identityMapEach,
                    mapEach,
                    mapReadLookup,
                    mapAtPut,
                    mapRemove,
                    directControlNative,
                    objectCall,
                    importRuntime);
        }
    }

    /**
     * The special-call representation for a native Closure body, optionally
     * carrying exactly one mutually exclusive {@link StructuredCallCapabilities}.
     * Ordinary source calls never instantiate this class.
     */
    static final class StructuredNativeInvocation {
        private final ProtosNativeClosureBody nativeBody;
        private final List<?> supplied;
        private final ProtosActivation activation;
        private final StructuredCallCapabilities structured;

        private StructuredNativeInvocation(
                ProtosNativeClosureBody nativeBody,
                List<?> supplied,
                ProtosActivation activation,
                StructuredCallCapabilities structured) {
            this.nativeBody = java.util.Objects.requireNonNull(nativeBody, "nativeBody");
            this.supplied = List.copyOf(supplied);
            this.activation = java.util.Objects.requireNonNull(activation, "activation");
            this.structured = structured;
        }

        /**
         * The structured state of a native body, or {@code null} when the
         * body is an ordinary builtin. Classification is by the identity of
         * the standard implementation, exactly as before I115.
         */
        static StructuredNativeInvocation forBodyOrNull(
                ProtosClosureValue closure,
                List<?> supplied,
                ProtosActivation activation) {
            ProtosNativeClosureBody nativeBody = closure.nativeBody().orElseThrow();
            StructuredNativeInvocation invocation =
                    new StructuredNativeInvocation(
                            nativeBody,
                            supplied,
                            activation,
                            StructuredCallCapabilities.of(
                                    ProtosStandardObjectProtocol.isStandardEnsureImplementation(closure),
                                    ProtosStandardErrorProtocol.isStandardHandleImplementation(closure),
                                    ProtosStandardBooleanProtocol.structuredCallbackKindForImplementation(closure),
                                    ProtosStandardObjectProtocol.standardLoopForImplementation(closure),
                                    ProtosStandardArrayProtocol.isStandardEachImplementation(closure),
                                    ProtosStandardBytesProtocol.isStandardEachImplementation(closure),
                                    ProtosStandardEnvironmentProtocol.isStandardEachImplementation(closure),
                                    ProtosStandardIdentityMapProtocol.isStandardEachImplementation(closure),
                                    ProtosStandardMapProtocol.isStandardEachImplementation(closure),
                                    ProtosStandardMapProtocol.structuredReadLookupKindForImplementation(closure),
                                    ProtosStandardMapProtocol.isStandardAtPutImplementation(closure),
                                    ProtosStandardMapProtocol.isStandardRemoveImplementation(closure),
                                    ProtosStandardErrorProtocol.isStandardSignalImplementation(closure),
                                    ProtosStandardObjectProtocol.isStandardCallImplementation(closure),
                                    ProtosStandardImportProtocol.structuredImportRuntimeForImplementation(closure)));
            return invocation.requiresStructuredDispatch() ? invocation : null;
        }

        ProtosActivation activation() { return activation; }

        boolean isStructuredObjectCall() { return structured != null && structured.objectCall; }

        boolean isStructuredCaseOf() {
            return ProtosStandardObjectProtocol.isStandardCaseOfImplementation(nativeBody);
        }

        boolean isStructuredMapMatch() {
            return ProtosStandardMapProtocol.isStandardMatchImplementation(nativeBody);
        }

        boolean isStructuredImportCall() {
            return structured != null && structured.importRuntime != null;
        }

        boolean isStructuredEnsure() { return structured != null && structured.ensure; }

        boolean isStructuredErrorHandler() {
            return structured != null && structured.errorHandler;
        }

        boolean isStructuredBoolean() {
            return structured != null && structured.booleanKind != null;
        }

        boolean isStructuredLoop() { return structured != null && structured.loop != null; }

        PreparedLoopCall prepareStructuredLoop() {
            if (!isStructuredLoop()) {
                throw new IllegalStateException(
                        "prepared Closure call has no structured loop capability");
            }
            return new PreparedLoopCall(
                    structured.loop.postTest(),
                    structured.loop.exitValue(),
                    activation.receiver(),
                    supplied,
                    activation);
        }

        boolean isStructuredArrayEach() { return structured != null && structured.arrayEach; }

        boolean isStructuredArrayMatch() {
            return ProtosStandardArrayProtocol.isStandardMatchImplementation(nativeBody);
        }

        boolean isStructuredBytesEach() { return structured != null && structured.bytesEach; }

        boolean isStructuredEnvironmentEach() {
            return structured != null && structured.environmentEach;
        }

        boolean isStructuredIdentityMapAtIfAbsent() {
            return ProtosStandardIdentityMapProtocol.isStandardAtIfAbsentImplementation(nativeBody);
        }

        boolean isStructuredIdentityMapEach() {
            return structured != null && structured.identityMapEach;
        }

        boolean isStructuredMapEach() { return structured != null && structured.mapEach; }

        boolean isStructuredMapReadLookup() {
            return structured != null && structured.mapReadLookup != null;
        }

        boolean isStructuredMapAtPut() { return structured != null && structured.mapAtPut; }

        boolean isStructuredMapRemove() { return structured != null && structured.mapRemove; }

        /**
         * D188 projected foreign {@code each}, recognized by its private projection body like
         * Array.match: the body is reachable only through the foreign projection, and the cursor
         * itself re-validates the receiver.
         */
        boolean isStructuredForeignEach() {
            return ProtosForeignProjectedOperations.isEachImplementation(nativeBody);
        }

        boolean requiresStructuredDispatch() {
            if (structured != null
                    && (structured.objectCall
                            || structured.importRuntime != null
                            || structured.ensure
                            || structured.errorHandler
                            || structured.booleanKind != null
                            || structured.loop != null
                            || structured.arrayEach
                            || structured.bytesEach
                            || structured.environmentEach
                            || structured.identityMapEach
                            || structured.mapEach
                            || structured.mapReadLookup != null
                            || structured.mapAtPut
                            || structured.mapRemove)) {
                return true;
            }
            return isStructuredCaseOf()
                    || isStructuredMapMatch()
                    || isStructuredArrayMatch()
                    || isStructuredIdentityMapAtIfAbsent()
                    || isStructuredForeignEach();
        }

        PreparedMapMatchCall prepareStructuredMapMatch() {
            if (!isStructuredMapMatch()) {
                throw new IllegalStateException(
                        "prepared Closure call has no structured Map.match capability");
            }
            return new PreparedMapMatchCall(
                    activation.receiver(),
                    supplied,
                    activation);
        }

        PreparedCaseOfCall prepareStructuredCaseOf() {
            if (!isStructuredCaseOf()) {
                throw new IllegalStateException(
                        "prepared Closure call has no structured Object.caseOf capability");
            }
            return new PreparedCaseOfCall(
                    activation.receiver(),
                    supplied,
                    activation);
        }

        PreparedStandardObjectCall prepareStructuredObjectCall() {
            if (structured == null || !structured.objectCall) {
                throw new IllegalStateException(
                        "prepared Closure call has no PLAT032 Object.call capability");
            }

            Object receiver = activation.receiver();
            if (receiver instanceof ProtosClosureValue targetClosure) {
                // The Closure branch is terminal: it executes the Closure's own
                // body and never looks up `call` again (CALLABLES.md).
                return PreparedStandardObjectCall.forward(
                        ProtosCallRequest.selectedBody(
                                targetClosure,
                                supplied,
                                activation));
            }
            if (receiver instanceof ProtosObjectValue
                    && !ProtosNumericValueSupport.isCurrentNumber(receiver)) {
                return PreparedStandardObjectCall.forward(
                        ProtosCallRequest.send(
                                receiver,
                                "construct",
                                activation,
                                supplied));
            }
            throw ProtosCoreErrors.signal(
                    activation,
                    ProtosCoreErrors.newError(activation));
        }

        PreparedStandardImportCall prepareStructuredImportCall() {
            if (structured == null || structured.importRuntime == null) {
                throw new IllegalStateException(
                        "prepared Closure call has no PLAT032 import capability");
            }
            // The private bridge `execute` hands over the attempt that the source-backed Core
            // import policy already decided; it never resolves or decides on its own.
            ProtosModuleRuntime.PreparedModuleInitialization initialization =
                    ProtosStandardImportProtocol.claimImportAttempt(
                            nativeBody, activation, supplied);
            return new PreparedStandardImportCall(new ModuleInitializationCall(initialization));
        }

        PreparedEnsureCall prepareStructuredEnsure() {
            if (structured == null || !structured.ensure) {
                throw new IllegalStateException(
                        "prepared Closure call has no structured ensure capability");
            }
            Object receiver = activation.receiver();
            if (!(receiver instanceof ProtosClosureValue body)
                    || supplied.size() != 1
                    || !(supplied.get(0) instanceof ProtosClosureValue cleanup)) {
                throw ProtosCoreErrors.signal(
                        activation,
                        ProtosCoreErrors.newError(activation));
            }
            return new PreparedEnsureCall(
                    ProtosCallRequest.call(body, List.of(), activation),
                    ProtosCallRequest.call(cleanup, List.of(), activation));
        }

        PreparedErrorHandlerCall prepareStructuredErrorHandler() {
            if (structured == null || !structured.errorHandler) {
                throw new IllegalStateException(
                        "prepared Closure call has no structured Error.handle capability");
            }
            Object receiver = activation.receiver();
            if (!(receiver instanceof ProtosObjectValue matchPrototype)
                    || !ProtosCoreErrors.isError(activation, matchPrototype)
                    || supplied.size() != 2
                    || !(supplied.get(0) instanceof ProtosClosureValue body)
                    || !(supplied.get(1) instanceof ProtosClosureValue handler)) {
                throw ProtosCoreErrors.signal(
                        activation,
                        ProtosCoreErrors.newError(activation));
            }

            ProtosDynamicControlState state = activation.dynamicControlState();
            ProtosDynamicControlState.Frame frame =
                    state.enterHandlerFrame(activation, matchPrototype);
            try {
                return new PreparedErrorHandlerCall(
                        state,
                        frame,
                        handler,
                        activation,
                        ProtosCallRequest.call(body, List.of(), activation));
            } catch (RuntimeException | Error preparationFailure) {
                state.leaveFrame(frame);
                throw preparationFailure;
            }
        }

        PreparedBooleanCall prepareStructuredBoolean() {
            if (structured == null || structured.booleanKind == null) {
                throw new IllegalStateException(
                        "prepared Closure call has no structured Boolean callback capability");
            }
            return new PreparedBooleanCall(
                    structured.booleanKind,
                    activation.receiver(),
                    supplied,
                    activation);
        }

        PreparedArrayEachCall prepareStructuredArrayEach() {
            if (structured == null || !structured.arrayEach) {
                throw new IllegalStateException(
                        "prepared Closure call has no structured Array.each capability");
            }
            return new PreparedArrayEachCall(
                    activation.receiver(),
                    supplied,
                    activation);
        }

        PreparedArrayMatchCall prepareStructuredArrayMatch() {
            if (!isStructuredArrayMatch()) {
                throw new IllegalStateException(
                        "prepared Closure call has no structured Array.match capability");
            }
            return new PreparedArrayMatchCall(
                    activation.receiver(),
                    supplied,
                    activation);
        }

        PreparedBytesEachCall prepareStructuredBytesEach() {
            if (structured == null || !structured.bytesEach) {
                throw new IllegalStateException(
                        "prepared Closure call has no structured Bytes.each capability");
            }
            return new PreparedBytesEachCall(
                    activation.receiver(),
                    supplied,
                    activation);
        }

        PreparedEnvironmentEachCall prepareStructuredEnvironmentEach() {
            if (structured == null || !structured.environmentEach) {
                throw new IllegalStateException(
                        "prepared Closure call has no structured Environment.each capability");
            }
            return new PreparedEnvironmentEachCall(
                    activation.receiver(),
                    supplied,
                    activation);
        }

        ProtosForeignEachCall prepareStructuredForeignEach() {
            if (!isStructuredForeignEach()) {
                throw new IllegalStateException(
                        "prepared Closure call has no structured foreign each capability");
            }
            return new ProtosForeignEachCall(activation.receiver(), supplied, activation);
        }

        PreparedIdentityMapAtIfAbsentCall prepareStructuredIdentityMapAtIfAbsent() {
            if (!isStructuredIdentityMapAtIfAbsent()) {
                throw new IllegalStateException(
                        "prepared Closure call has no structured IdentityMap.atIfAbsent capability");
            }
            return new PreparedIdentityMapAtIfAbsentCall(
                    activation.receiver(),
                    supplied,
                    activation);
        }

        PreparedIdentityMapEachCall prepareStructuredIdentityMapEach() {
            if (structured == null || !structured.identityMapEach) {
                throw new IllegalStateException(
                        "prepared Closure call has no structured IdentityMap.each capability");
            }
            return new PreparedIdentityMapEachCall(
                    activation.receiver(),
                    supplied,
                    activation);
        }

        PreparedMapEachCall prepareStructuredMapEach() {
            if (structured == null || !structured.mapEach) {
                throw new IllegalStateException(
                        "prepared Closure call has no structured Map.each capability");
            }
            return new PreparedMapEachCall(
                    activation.receiver(),
                    supplied,
                    activation);
        }

        PreparedMapReadLookupCall prepareStructuredMapReadLookup() {
            if (structured == null || structured.mapReadLookup == null) {
                throw new IllegalStateException(
                        "prepared Closure call has no structured Map read-lookup capability");
            }
            return new PreparedMapReadLookupCall(
                    structured.mapReadLookup,
                    activation.receiver(),
                    supplied,
                    activation);
        }

        PreparedMapAtPutCall prepareStructuredMapAtPut() {
            if (structured == null || !structured.mapAtPut) {
                throw new IllegalStateException(
                        "prepared Closure call has no structured Map.atPut capability");
            }
            return new PreparedMapAtPutCall(
                    activation.receiver(),
                    supplied,
                    activation);
        }

        PreparedMapRemoveCall prepareStructuredMapRemove() {
            if (structured == null || !structured.mapRemove) {
                throw new IllegalStateException(
                        "prepared Closure call has no structured Map.remove capability");
            }
            return new PreparedMapRemoveCall(
                    activation.receiver(),
                    supplied,
                    activation);
        }
    }

    /**
     * The state of a standard-import module initialization: the {@link
     * ProtosModuleRuntime.PreparedModuleInitialization} lifecycle handle and
     * the module body target. It is module state, not a Closure call: the
     * structured import protocol runs the module body and applies {@code
     * finish}/{@code fail} through it.
     */
    static final class ModuleInitializationCall {
        private final ProtosModuleRuntime.PreparedModuleInitialization moduleInitialization;
        private final RootCallTarget bodyTarget;
        private final ProtosActivation activation;
        private final Object[] targetArguments;

        ModuleInitializationCall(
                ProtosModuleRuntime.PreparedModuleInitialization moduleInitialization) {
            this.moduleInitialization =
                    java.util.Objects.requireNonNull(
                            moduleInitialization,
                            "moduleInitialization");
            this.bodyTarget = moduleInitialization.bodyTarget();
            this.activation = moduleInitialization.activation();
            this.targetArguments =
                    bodyTarget == null
                            ? null
                            : new Object[] {this.activation};
        }

        RootCallTarget bodyTarget() { return bodyTarget; }

        ProtosActivation activation() { return activation; }

        Object[] targetArguments() {
            if (targetArguments == null) {
                throw new IllegalStateException(
                        "prepared call has no source target arguments");
            }
            return targetArguments;
        }

        boolean isImmediate() { return moduleInitialization.isImmediate(); }

        Object enterImmediate() {
            if (!isImmediate()) {
                throw new IllegalStateException("prepared call is not an immediate module hit");
            }
            return moduleInitialization.immediateResult();
        }

        Object handleControlTransfer(ControlFlowException transfer) {
            moduleInitialization.fail();
            throw transfer;
        }

        void failIfModuleInitialization() { moduleInitialization.fail(); }

        RuntimeException mapRuntimeFailure(RuntimeException failure) {
            return moduleInitialization.mapUnexpectedHostFailure(failure);
        }

        Object finish(Object result) { return moduleInitialization.finish(result); }

        /**
         * Runs the module body once (or answers an immediate module hit).
         * A pending result is returned for the caller to compose; {@link
         * #finish} is applied by the structured import protocol after it
         * completes. An abrupt exit fails the initialization.
         */
        Object run(com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
            if (isImmediate()) {
                return enterImmediate();
            }
            try {
                return callNode.call(bodyTarget, targetArguments());
            } catch (ControlFlowException transfer) {
                return handleControlTransfer(transfer);
            } catch (com.oracle.truffle.api.exception.AbstractTruffleException failure) {
                failIfModuleInitialization();
                throw failure;
            } catch (RuntimeException failure) {
                throw mapRuntimeFailure(failure);
            }
        }
    }

    /**
     * D237 / I115: immutable evaluated positional/named packet.
     *
     * Constructed only for a name-bearing call. Evaluation and spread
     * expansion have already completed in original source order.
     * Neither parameter association nor selection occurs here.
     */
    static record NamedArgumentPacket(
            List<Object> positional,
            List<ProtosNamedArgumentAssociation.NamedValue> named) {

        NamedArgumentPacket {
            positional = List.copyOf(
                    java.util.Objects.requireNonNull(
                            positional, "positional"));
            named = List.copyOf(
                    java.util.Objects.requireNonNull(
                            named, "named"));

            if (named.isEmpty()) {
                throw new IllegalArgumentException(
                        "named packet requires at least one label");
            }
        }
    }

    static final class PreparedArgumentVector {
        private final ArrayList<Object> values = new ArrayList<>();

        // I143: positional calls allocate no named-argument metadata.
        private ArrayList<ProtosNamedArgumentAssociation.NamedValue> namedValues;

        void appendNamed(String name, Object value) {
            ProtosNamedArgumentAssociation.NamedValue item =
                    new ProtosNamedArgumentAssociation.NamedValue(name, value);
            if (namedValues == null) {
                namedValues = new ArrayList<>();
            }
            namedValues.add(item);
        }

        boolean hasNamedArguments() {
            return namedValues != null && !namedValues.isEmpty();
        }

        List<ProtosNamedArgumentAssociation.NamedValue> namedSnapshot() {
            return namedValues == null ? List.of() : List.copyOf(namedValues);
        }

        NamedArgumentPacket namedPacket() {
            return new NamedArgumentPacket(
                    positionalSnapshot(),
                    namedSnapshot());
        }

        List<Object> positionalSnapshot() {
            return List.copyOf(values);
        }

        private void requirePositionalOnly() {
            if (hasNamedArguments()) {
                throw new IllegalStateException(
                        "I143 named arguments require name-aware call preparation");
            }
        }

        void append(Object value) {
            values.add(
                    java.util.Objects.requireNonNull(
                            value,
                            "supplied argument"));
        }

        void appendSpread(Object value, ProtosActivation caller) {
            if (!appendSpreadArrayOrFalse(value)) {
                throw new ProtosSignalException(ProtosCoreErrors.newError(caller));
            }
        }

        /**
         * Expands a valid Array without requiring caller activation.
         * Invalid spread is reported by the caller with its exact authority.
         */
        boolean appendSpreadArrayOrFalse(Object value) {
            if (!(value instanceof ProtosArrayValue array)) {
                return false;
            }

            // Snapshot at this argument's own evaluation position.
            List<Object> spread = array.indexedSnapshot();
            for (int index = 0; index < spread.size(); index++) {
                values.add(spread.get(index));
            }
            return true;
        }

        Object soleValueOrNull() {
            requirePositionalOnly();
            return values.size() == 1 ? values.get(0) : null;
        }

        List<Object> snapshot() {
            requirePositionalOnly();
            return positionalSnapshot();
        }
    }
}
