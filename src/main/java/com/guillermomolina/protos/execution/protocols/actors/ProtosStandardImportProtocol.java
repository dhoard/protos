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
import com.guillermomolina.protos.runtime.ProtosActorExecutionDomain;
import com.guillermomolina.protos.runtime.ProtosActorModuleState;
import com.guillermomolina.protos.runtime.ProtosClosureValue;
import com.guillermomolina.protos.runtime.ProtosCoreErrors;
import com.guillermomolina.protos.runtime.callables.ProtosNativeClosureBody;
import com.guillermomolina.protos.runtime.ProtosNullValue;
import com.guillermomolina.protos.runtime.ProtosObjectValue;
import com.guillermomolina.protos.runtime.ProtosPrelude;
import com.guillermomolina.protos.runtime.ProtosSignalException;
import com.guillermomolina.protos.runtime.ProtosTask;
import com.guillermomolina.protos.runtime.modules.ProtosModuleKey;
import java.util.Objects;

final class ProtosStandardImportProtocol {
    private ProtosStandardImportProtocol() {}

    /**
     * The Process module runtime behind {@code prelude}'s standard import facility, or {@code
     * null} when that Prelude has no Core-installed import facility. The source-backed facility
     * is recognized only through the private per-Prelude binding its own bootstrap established;
     * guest captures are never inspected.
     */
    static ProtosModuleRuntime runtimeForPrelude(ProtosPrelude prelude) {
        Object standardBinding = prelude.bindings().readLocalSlot("import").orElse(null);
        if (!(standardBinding instanceof ProtosObjectValue standardFacility)
                || !(standardFacility.readLocalSlot("call").orElse(null)
                        instanceof ProtosClosureValue behavior)) {
            return null;
        }
        return prelude.moduleRuntimeForRuntime() instanceof ProtosModuleRuntime bound
                        && isSourceBackedClosure(behavior)
                ? bound
                : null;
    }

    /**
     * Validates and freezes the source-created Core import facility. It must delegate directly to
     * Object, still be open, and declare exactly one slot, {@code call}, holding the source-backed
     * Closure of {@code protos/lib/core/import.protos}. That {@code call} is kept as is; the
     * private bridge it uses is a bootstrap-local lexical anchor installed by the caller, never a
     * slot of the facility.
     */
    static ProtosObjectValue installImportFacility(ProtosObjectValue facility) {
        Objects.requireNonNull(facility, "facility");
        if (facility.parent().orElse(null) != ProtosObjectValue.rootObject()) {
            throw new IllegalArgumentException(
                    "Core import facility must delegate directly to Object");
        }
        if (!facility.isOpen()) {
            throw new IllegalArgumentException("source-created Core import facility must begin open");
        }
        java.util.Map<String, Object> slots = facility.localSlotsSnapshot();
        if (slots.size() != 1 || !isSourceBackedClosure(slots.get("call"))) {
            throw new IllegalArgumentException(
                    "source-created Core import facility must declare exactly a source-backed call");
        }
        return facility.freeze();
    }

    private static boolean isSourceBackedClosure(Object value) {
        return value instanceof ProtosClosureValue closure
                && closure.definition() != null
                && closure.executionPlan().isPresent()
                && closure.nativeBody().isEmpty();
    }

    // ------------------------------------------------------------------------------------------
    // Private primitives behind the source-backed Core import policy.
    //
    // The guest policy in import.protos decides validation order, cache hit versus miss, and
    // when to start a new initialization. These primitives only perform host work the guest
    // cannot: canonical resolution, the authority-checked Actor-local cache observation, record
    // reservation, and the hand-off of the reserved attempt to the existing structured import
    // protocol. They keep no module state of their own: every operation reads the Actor-local
    // module state of the current activation, and tokens are bound to that state.
    // ------------------------------------------------------------------------------------------

    /** Bootstrap-local lexical name of the private import bridge; never a Prelude binding. */
    static final String IMPORT_BRIDGE_SLOT = "_coreImportBridge";

    /** Opaque, slotless, frozen guest token for one resolved canonical module identity. */
    private static final class ResolvedModuleToken extends ProtosObjectValue {
        private final ProtosModuleRuntime runtime;
        private final ProtosModuleKey key;
        private final ProtosActorModuleState actorState;
        private final ProtosActorExecutionDomain domain;

        ResolvedModuleToken(
                ProtosModuleRuntime runtime, ProtosModuleKey key, ProtosActivation owner) {
            super(ProtosObjectValue.rootObject());
            this.runtime = runtime;
            this.key = key;
            this.actorState = owner.actorModuleState();
            this.domain = owner.executionDomain();
            freeze();
        }
    }

    /**
     * Opaque, slotless, frozen guest token for exactly one requested initialization attempt. It
     * holds only the identities needed to verify the attempt (runtime, canonical key, Actor
     * module state, execution domain and Task) and touches no module record: the INITIALIZING
     * record is created only when the structured {@code execute} claims it, so an attempt
     * abandoned before {@code execute} leaves nothing to clean up. It can be claimed once.
     */
    private static final class PendingInitializationToken extends ProtosObjectValue {
        private final ProtosModuleRuntime runtime;
        private final ProtosModuleKey key;
        private final ProtosActorModuleState actorState;
        private final ProtosActorExecutionDomain domain;
        private final ProtosTask task;
        private boolean claimed;

        PendingInitializationToken(
                ProtosModuleRuntime runtime, ProtosModuleKey key, ProtosActivation owner) {
            super(ProtosObjectValue.rootObject());
            this.runtime = runtime;
            this.key = Objects.requireNonNull(key, "key");
            this.actorState = owner.actorModuleState();
            this.domain = owner.executionDomain();
            this.task = owner.task().orElse(null);
            freeze();
        }

        /** Answers {@code true} exactly once. */
        synchronized boolean claim() {
            if (claimed) {
                return false;
            }
            claimed = true;
            return true;
        }
    }

    /**
     * The private {@code execute} implementation. It is recognized by exact body identity when
     * selected; the structured import protocol, not this body, runs the module child. Reaching
     * {@link #execute} directly means no structured protocol is available, and it is refused.
     */
    private static final class ImportExecuteBody implements ProtosNativeClosureBody {
        private final ProtosModuleRuntime runtime;
        private ProtosObjectValue bridge;

        ImportExecuteBody(ProtosModuleRuntime runtime) {
            this.runtime = runtime;
        }

        @Override
        public Object execute(ProtosActivation activation, java.util.List<?> supplied) {
            throw new ProtosSignalException(ProtosCoreErrors.newError(activation));
        }
    }

    /** Creates the frozen private import bridge for {@code runtime}. */
    static ProtosObjectValue createImportBridge(ProtosModuleRuntime runtime) {
        Objects.requireNonNull(runtime, "runtime");
        ProtosObjectValue bridge = new ProtosObjectValue(ProtosObjectValue.rootObject());
        ImportExecuteBody executeBody = new ImportExecuteBody(runtime);
        executeBody.bridge = bridge;
        bridge.createLocalSlot(
                "resolve",
                ProtosClosureValue.nativeClosure(
                        (activation, supplied) -> {
                            requireBridgeCall(bridge, activation, supplied);
                            return new ResolvedModuleToken(
                                    runtime,
                                    runtime.resolveModuleKey(supplied.get(0), activation),
                                    activation);
                        }));
        bridge.createLocalSlot(
                "lookup",
                ProtosClosureValue.nativeClosure(
                        (activation, supplied) -> {
                            requireBridgeCall(bridge, activation, supplied);
                            ResolvedModuleToken token =
                                    requireResolvedToken(runtime, supplied.get(0), activation);
                            ProtosActorModuleState.ModuleRecord record =
                                    runtime.authorizedRecordOrNull(token.key, activation);
                            return record == null ? ProtosNullValue.INSTANCE : record.instance();
                        }));
        bridge.createLocalSlot(
                "begin",
                ProtosClosureValue.nativeClosure(
                        (activation, supplied) -> {
                            requireBridgeCall(bridge, activation, supplied);
                            ResolvedModuleToken token =
                                    requireResolvedToken(runtime, supplied.get(0), activation);
                            // Precondition only: authority and a cache miss. No record is
                            // reserved and no initializer runs until `execute` claims the token.
                            if (runtime.authorizedRecordOrNull(token.key, activation) != null) {
                                throw new ProtosSignalException(
                                        ProtosCoreErrors.newError(activation));
                            }
                            return new PendingInitializationToken(runtime, token.key, activation);
                        }));
        bridge.createLocalSlot("execute", ProtosClosureValue.nativeClosure(executeBody));
        return bridge.freeze();
    }

    private static void requireBridgeCall(
            ProtosObjectValue bridge, ProtosActivation activation, java.util.List<?> supplied) {
        if (activation.receiver() != bridge || supplied.size() != 1) {
            throw new ProtosSignalException(ProtosCoreErrors.newError(activation));
        }
    }

    private static ResolvedModuleToken requireResolvedToken(
            ProtosModuleRuntime runtime, Object candidate, ProtosActivation activation) {
        if (!(candidate instanceof ResolvedModuleToken token)
                || token.runtime != runtime
                || token.actorState != activation.actorModuleState()
                || token.domain != activation.executionDomain()) {
            throw new ProtosSignalException(ProtosCoreErrors.newError(activation));
        }
        return token;
    }

    /**
     * The runtime behind the private import {@code execute} implementation, or {@code null} for
     * any other Closure. Classification is by exact body identity only.
     */
    static ProtosModuleRuntime structuredImportRuntimeForImplementation(
            ProtosClosureValue behavior) {
        return behavior.nativeBody().orElse(null) instanceof ImportExecuteBody executeBody
                ? executeBody.runtime
                : null;
    }

    static boolean isImportExecuteImplementation(ProtosNativeClosureBody body) {
        return body instanceof ImportExecuteBody;
    }

    /**
     * Claims the attempt requested by a private {@code execute} call and prepares its
     * initialization. The receiver must be the bridge that owns the selected body and the token
     * must have been issued by the same runtime in the calling Actor and Task and not claimed
     * before. A rejected claim leaves every module record untouched.
     */
    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    static ProtosModuleRuntime.PreparedModuleInitialization claimImportAttempt(
            ProtosNativeClosureBody selected,
            ProtosActivation activation,
            java.util.List<?> supplied) {
        if (!(selected instanceof ImportExecuteBody executeBody)
                || activation.receiver() != executeBody.bridge
                || supplied.size() != 1
                || !(supplied.get(0) instanceof PendingInitializationToken token)
                || token.runtime != executeBody.runtime
                || token.actorState != activation.actorModuleState()
                || token.domain != activation.executionDomain()
                || token.task != activation.task().orElse(null)) {
            throw new ProtosSignalException(ProtosCoreErrors.newError(activation));
        }
        if (!token.claim()) {
            throw new ProtosSignalException(ProtosCoreErrors.newError(activation));
        }
        // The claim consumed the token even if the key was cached meanwhile; a retry needs a
        // fresh `begin` decision by the guest policy.
        if (token.runtime.authorizedRecordOrNull(token.key, activation) != null) {
            throw new ProtosSignalException(ProtosCoreErrors.newError(activation));
        }
        // Inserts the INITIALIZING record before the initializer or FOREIGN acquisition runs;
        // from here completion and exact eviction belong to the structured import protocol.
        return token.runtime.prepareDecidedCacheMiss(token.key, activation);
    }
}
