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

import com.oracle.truffle.api.Assumption;
import com.oracle.truffle.api.bytecode.BytecodeLocation;
import com.oracle.truffle.api.bytecode.BytecodeNode;
import com.oracle.truffle.api.bytecode.BytecodeRootNode;
import com.oracle.truffle.api.bytecode.ConstantOperand;
import com.oracle.truffle.api.bytecode.LocalAccessor;
import com.oracle.truffle.api.bytecode.LocalRangeAccessor;
import com.oracle.truffle.api.bytecode.MaterializedLocalAccessor;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.guillermomolina.protos.runtime.ProtosNumericValueSupport;
import com.guillermomolina.protos.runtime.ProtosActivation;
import com.guillermomolina.protos.runtime.ProtosLexicalEnvironment;
import com.guillermomolina.protos.runtime.ProtosLexicalFallback;
import com.guillermomolina.protos.runtime.ProtosArrayValue;
import com.guillermomolina.protos.runtime.ProtosBooleanValue;
import com.guillermomolina.protos.runtime.ProtosBytesValue;
import com.guillermomolina.protos.runtime.ProtosClosureValue;
import com.guillermomolina.protos.runtime.ProtosCoreErrors;
import com.guillermomolina.protos.runtime.ProtosDynamicControlState;
import com.guillermomolina.protos.runtime.ProtosEnvironmentValue;
import com.guillermomolina.protos.runtime.ProtosIdentity;
import com.guillermomolina.protos.runtime.ProtosIdentityMapValue;
import com.guillermomolina.protos.runtime.ProtosMapValue;
import com.guillermomolina.protos.runtime.ProtosNumericHashKey;
import com.guillermomolina.protos.runtime.ProtosIoOperation;
import com.guillermomolina.protos.runtime.ProtosNonLocalReturnException;
import com.guillermomolina.protos.runtime.ProtosNullValue;
import com.guillermomolina.protos.runtime.ProtosPrelude;
import com.guillermomolina.protos.runtime.ProtosReturnHome;
import com.guillermomolina.protos.runtime.ProtosSignalException;
import com.guillermomolina.protos.runtime.ProtosTask;
import com.guillermomolina.protos.runtime.ProtosNativeClosureBody;
import com.guillermomolina.protos.runtime.ProtosSuspensionCapableNativeClosureBody;
import com.guillermomolina.protos.runtime.ProtosObjectValue;
import com.guillermomolina.protos.runtime.ProtosSlotLookupResult;
import com.guillermomolina.protos.runtime.ProtosValueLookup;
import com.guillermomolina.protos.semantic.ast.CanonicalClosure;
import com.guillermomolina.protos.semantic.ast.CanonicalIntrinsic;
import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.bytecode.ContinuationResult;
import com.oracle.truffle.api.bytecode.ContinuationRootNode;
import com.oracle.truffle.api.bytecode.GenerateBytecode;
import com.oracle.truffle.api.bytecode.Operation;
import com.oracle.truffle.api.bytecode.Variadic;
import com.oracle.truffle.api.dsl.Bind;
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.Cached.Shared;
import com.oracle.truffle.api.dsl.NonIdempotent;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.exception.AbstractTruffleException;
import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.MaterializedFrame;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.ControlFlowException;
import com.oracle.truffle.api.nodes.DirectCallNode;
import com.oracle.truffle.api.nodes.IndirectCallNode;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.RootNode;
import com.oracle.truffle.api.profiles.InlinedBranchProfile;
import java.util.ArrayList;
import java.util.List;

/**
 * Internal Bytecode DSL root substrate selected by PLAT014.
 *
 * <p>This class is deliberately not a second Protos execution model. Under
 * PLAT042 Candidate B′ it is the untagged structured-dispatch/C-prime
 * interpreter: Task C-prime entry, structured prepared invocations (lowered by
 * {@link ProtosStructuredDispatchLowerer}) and the I/O C-prime roots. Ordinary
 * source roots are {@link ProtosSemanticBytecodeRootNode}s, whose source-surface
 * operations delegate to the single operation implementations owned here.
 * Source-only operations that no untagged root emits any longer may remain
 * declared here until a later cleanup.</p>
 */
@GenerateBytecode(
        languageClass = ProtosLanguage.class,
        enableYield = true,
        enableTagInstrumentation = true,
        enableRootTagging = false,
        enableRootBodyTagging = false,
        enableMaterializedLocalAccesses = true,
        enableTailCallHandlers = true,
        enableUncachedInterpreter = true,
        boxingEliminationTypes = {int.class, long.class, double.class},
        tagTreeNodeLibrary = ProtosBytecodeTagTreeNodeExports.class)
abstract class ProtosBytecodeRootNode extends RootNode implements BytecodeRootNode {

    protected ProtosBytecodeRootNode(
            ProtosLanguage language,
            FrameDescriptor frameDescriptor) {
        super(language, frameDescriptor);
    }

    @Override
    public Object interceptControlFlowException(
            ControlFlowException transfer,
            VirtualFrame frame,
            BytecodeNode bytecodeNode,
            int bytecodeIndex)
            throws Throwable {
        throw ProtosBytecodeControlTransferException.bridge(transfer);
    }

    /* PERF038-C: set once, when the first guest exception crosses this root. */
    @com.oracle.truffle.api.CompilerDirectives.CompilationFinal
    private boolean guestExceptionCrossed;

    @Override
    public AbstractTruffleException interceptTruffleException(
            AbstractTruffleException exception,
            VirtualFrame frame,
            BytecodeNode bytecodeNode,
            int bytecodeIndex) {
        /*
         * PERF038-C: a root no guest exception has crossed yet compiles this
         * interception as a deoptimization; the first crossing invalidates
         * that code once and every crossing then runs the unchanged work.
         */
        if (!guestExceptionCrossed) {
            com.oracle.truffle.api.CompilerDirectives.transferToInterpreterAndInvalidate();
            guestExceptionCrossed = true;
        }
        return interceptGuestException(exception, frame);
    }

    /**
     * Root exception interception shared by both generated Protos interpreters
     * (PLAT042 B′): the tagged semantic source root and this untagged
     * structured/C-prime root must select Error handlers identically.
     */
    static AbstractTruffleException interceptGuestException(
            AbstractTruffleException exception,
            VirtualFrame frame) {
        if (exception instanceof ProtosSignalException transfer) {
            selectGuestHandlerOnRootCrossing(transfer, frame == null ? null : frame.getArguments());
        }
        return exception;
    }

    /*
     * TEST009-E: the Error-path work of interceptGuestException, behind a host
     * boundary (it receives the frame-argument array, never the frame). Inlined,
     * the activation probing/materialization and handler selection were expanded
     * into the exception path of every compiled handler site.
     */
    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    private static void selectGuestHandlerOnRootCrossing(
            ProtosSignalException transfer,
            Object[] frameArguments) {
        /*
         * PERF025-H1: a semantic source root still in compact source-call
         * form materializes its exact activation here, on the Error path,
         * exactly as the former eager root prologue would have.
         */
        if (frameArguments != null && ProtosFrameArguments.hasActivation(frameArguments)) {
            ProtosActivation activation = ProtosFrameArguments.activation(frameArguments);
            /*
             * Selection is semantic authority and must happen before the
             * Bytecode EH table starts crossed TryFinally cleanup. Repeated
             * root crossings are idempotent because the exact transfer
             * remembers the one selected handler token.
             */
            ProtosCoreErrors.selectHandlerIfNeeded(activation, transfer);
        }
    }

    /**
     * Bytecode operation for unqualified lexical lookup.
     *
     * <p>The activation is loaded from frame argument 0 by the lowerer, preserving
     * the established Protos root calling convention.</p>
     */
    @Operation
    public static final class HasClosureArgument {
        @Specialization
        public static boolean perform(
                ProtosActivation activation,
                int positionalIndex) {
            List<?> arguments =
                    closureArguments(activation);
            return arguments.size() > positionalIndex;
        }
    }

    @Operation
    public static final class LoadClosureArgument {
        @Specialization
        public static Object perform(
                ProtosActivation activation,
                int positionalIndex) {
            List<?> arguments =
                    closureArguments(activation);
            if (positionalIndex >= arguments.size()) {
                throw closureArgumentCountError(activation);
            }
            return arguments.get(positionalIndex);
        }
    }

    @Operation
    public static final class BindClosureParameter {
        @Specialization
        public static void perform(
                ProtosActivation activation,
                String name,
                Object value) {
            createClosureParameterSlot(
                    activation,
                    name,
                    value);
        }
    }

    @Operation
    public static final class BindClosureRest {
        @Specialization
        public static void perform(
                ProtosActivation activation,
                String name,
                int positionalParametersBeforeRest) {
            createClosureParameterSlot(
                    activation,
                    name,
                    closureRestArray(activation, positionalParametersBeforeRest));
        }
    }

    private static Object closureRestArray(
            ProtosActivation activation,
            int positionalParametersBeforeRest) {
        List<?> supplied =
                closureArguments(activation);
        int restStart =
                Math.min(
                        positionalParametersBeforeRest,
                        supplied.size());
        ProtosPrelude prelude =
                activation.prelude()
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "parameter binding requires an owning Core prelude"));
        return prelude.newFrozenArray(
                supplied.subList(
                        restStart,
                        supplied.size()));
    }

    @Operation
    public static final class CheckClosureArgumentUpperBound {
        @Specialization
        public static void perform(
                ProtosActivation activation,
                int maximumPositionalArguments) {
            if (closureArguments(activation).size()
                    > maximumPositionalArguments) {
                throw closureArgumentCountError(activation);
            }
        }
    }

    private static List<?> closureArguments(
            ProtosActivation activation) {
        return activation.suppliedArgumentsForRuntime();
    }

    private static void createClosureParameterSlot(
            ProtosActivation activation,
            String name,
            Object value) {
        try {
            activation.createCurrentLocalSlotForRuntime(name, value);
        } catch (IllegalStateException invalidCreation) {
            throw new ProtosSignalException(
                    ProtosCoreErrors.newError(activation));
        }
    }

    /**
     * PERF025-H1: establishes a Closure parameter whose binding identity the
     * lowerer proved to be {@code ordinal} of {@code frameBackedLayout}, the
     * layout of a root that installs its persistent frame authority at entry.
     * While that root's authority is the activation's current one and the
     * context admits creation, the parameter is created at its known ordinal,
     * still rejecting a PRESENT binding exactly as a duplicate creation. Any
     * other state (a non-OPEN context, or another authority) takes the
     * unchanged named path of {@link BindClosureParameter}.
     */
    static void bindIndexedClosureParameter(
            ProtosFrameLexicalLayout frameBackedLayout,
            ProtosActivation activation,
            int ordinal,
            String name,
            Object value) {
        if (activation.currentAuthorityAdmittingLocalCreationForRuntime()
                        instanceof ProtosFrameLexicalBindingAuthority authority
                && authority.storesLayout(frameBackedLayout)) {
            try {
                authority.createFrameBackedBindingAt(ordinal, value);
            } catch (IllegalStateException invalidCreation) {
                throw new ProtosSignalException(
                        ProtosCoreErrors.newError(activation));
            }
            return;
        }
        createClosureParameterSlot(activation, name, value);
    }

    /**
     * PERF030: target-less creation of a statically proven current binding of
     * a root that installs its persistent frame authority, at the ordinal the
     * lowerer took from {@code frameBackedLayout}. Admitted exactly like
     * {@link #bindIndexedClosureParameter}, still rejecting a PRESENT binding
     * as a duplicate creation; any other state takes the unchanged named
     * {@link CreateCurrentLocalSlot} path.
     */
    static Object createIndexedCurrentLocalSlot(
            ProtosFrameLexicalLayout frameBackedLayout,
            ProtosActivation activation,
            int ordinal,
            String name,
            Object value) {
        if (activation.currentAuthorityAdmittingLocalCreationForRuntime()
                        instanceof ProtosFrameLexicalBindingAuthority authority
                && authority.storesLayout(frameBackedLayout)) {
            try {
                authority.createFrameBackedBindingAt(ordinal, value);
            } catch (IllegalStateException invalidCreation) {
                throw new ProtosSignalException(
                        ProtosCoreErrors.newError(activation));
            }
            return value;
        }
        return CreateCurrentLocalSlot.perform(activation, name, value);
    }

    /** PERF025-H1 rest counterpart of {@link #bindIndexedClosureParameter}. */
    static void bindIndexedClosureRest(
            ProtosFrameLexicalLayout frameBackedLayout,
            ProtosActivation activation,
            int ordinal,
            String name,
            int positionalParametersBeforeRest) {
        bindIndexedClosureParameter(
                frameBackedLayout,
                activation,
                ordinal,
                name,
                closureRestArray(activation, positionalParametersBeforeRest));
    }

    private static ProtosSignalException closureArgumentCountError(
            ProtosActivation activation) {
        return new ProtosSignalException(
                ProtosCoreErrors.newError(activation));
    }

    @Operation
    public static final class Lookup {
        @Specialization
        public static Object perform(ProtosActivation activation, String name) {
            return ProtosLexicalFallback.readByName(activation, name)
                    .orElseThrow(
                            () ->
                                    new ProtosSignalException(
                                            ProtosCoreErrors.newUnqualifiedLookupError(
                                                    activation)));
        }
    }

    /**
     * PLAT036 Candidate D, Slice 3: installs a frame-backed {@link
     * com.guillermomolina.protos.runtime.ProtosLexicalBindingAuthority} on the
     * current genuine execution context, exactly once, as the first operation
     * of every {@code ROOT}/{@code CLOSURE} Bytecode root that {@code
     * CanonicalToBytecodeLowerer} found at least one eligible current-scope
     * binding for. The frame is materialized before authority construction and
     * retained so a context that later escapes its own activation keeps observing
     * the same authoritative
     * values through this same authority instance.
     *
     * <p>No-op when {@code activation.context()} is not a genuine execution
     * context (never the case for a real ROOT/CLOSURE lowering unit, but
     * defensively harmless otherwise).
     */
    @Operation
    @ConstantOperand(
            type = LocalRangeAccessor.class,
            name = "frameBackedLocals")
    @ConstantOperand(
            type = ProtosFrameLexicalLayout.class,
            name = "frameBackedLayout")
    public static final class InstallFrameLexicalAuthority {
        @Specialization
        public static void perform(
                LocalRangeAccessor frameBackedLocals,
                ProtosFrameLexicalLayout frameBackedLayout,
                ProtosActivation activation,
                @Bind BytecodeNode bytecodeNode,
                @Bind("$bytecodeIndex") int bytecodeIndex,
                @Bind VirtualFrame frame) {
            MaterializedFrame materializedFrame = frame.materialize();
            activation.installFrameLexicalBindingAuthorityForRuntime(
                    new ProtosFrameLexicalBindingAuthority(
                            frameBackedLayout,
                            frameBackedLocals,
                            bytecodeNode.getBytecodeLocation(bytecodeIndex),
                            materializedFrame));
        }
    }

    /**
     * PERF025 frame-materialization slice: establishes a statically proven
     * current binding of a root lowered without {@link
     * InstallFrameLexicalAuthority} (see {@code
     * CanonicalToBytecodeLowerer#requiresPersistentFrameAuthority}).
     *
     * <p>While the activation's execution context is unobserved and has no
     * authority, the root's frame local is its only possible store: the
     * binding is created there directly, with the exact OPEN-context creation
     * rule (a PRESENT, i.e. non-cleared, local is a duplicate creation; an
     * unobserved context is necessarily OPEN). Allocating the local never made
     * it PRESENT; only this write does.
     *
     * <p>In any other state (a Context already materialized, or an authority
     * already installed), the root first installs, exactly once and while its
     * frame is live, the same materialized-frame authority {@link
     * InstallFrameLexicalAuthority} would have installed at entry, adopting
     * the bindings already established in its locals, and then uses the
     * unchanged authority creation path. No VirtualFrame is ever retained.
     *
     * <p>PERF030-I: every caller passes an {@code ordinal} that is an {@code
     * int} constant operand of its operation, because the {@link
     * LocalRangeAccessor} accesses require a partial-evaluation constant
     * index; it is never read from a runtime operand or an ordinal array.
     */
    static void createCurrentFrameBinding(
            LocalRangeAccessor frameBackedLocals,
            ProtosFrameLexicalLayout frameBackedLayout,
            ProtosActivation activation,
            int ordinal,
            String name,
            Object value,
            BytecodeNode bytecodeNode,
            int bytecodeIndex,
            VirtualFrame frame) {
        if (activation.hasUnobservedFrameNativeExecutionContextForRuntime()) {
            if (!frameBackedLocals.isCleared(bytecodeNode, frame, ordinal)) {
                throw new IllegalStateException("local slot already exists: " + name);
            }
            frameBackedLocals.setObject(bytecodeNode, frame, ordinal, value);
            return;
        }
        if (activation.hasGenuineExecutionContextForRuntime()) {
            installFrameLexicalAuthorityOnTransition(
                    frameBackedLocals,
                    frameBackedLayout,
                    activation,
                    bytecodeNode.getBytecodeLocation(bytecodeIndex),
                    frame.materialize());
        }
        // I091: only unobserved frame-native storage retains a primitive carrier.
        activation.createCurrentLocalSlotForRuntime(
                name, ProtosNumericValueSupport.guestValue(value));
    }

    /**
     * Installs the materialized-frame authority of a live root lowered
     * without {@link InstallFrameLexicalAuthority}, unless the authority
     * already installed is that root's own over this same frame. Callers pass
     * the frame of an activation that is still executing, and a location of
     * that activation's root (BUG018-C retained-frame reads translate it).
     */
    static void installFrameLexicalAuthorityOnTransition(
            LocalRangeAccessor frameBackedLocals,
            ProtosFrameLexicalLayout frameBackedLayout,
            ProtosActivation activation,
            BytecodeLocation location,
            MaterializedFrame materializedFrame) {
        if (activation.currentLexicalBindingAuthorityForRuntime()
                        instanceof ProtosFrameLexicalBindingAuthority installed
                && installed.isInstalledFor(location.getBytecodeNode(), materializedFrame)) {
            return;
        }
        ProtosFrameLexicalBindingAuthority authority =
                new ProtosFrameLexicalBindingAuthority(
                        frameBackedLayout,
                        frameBackedLocals,
                        location,
                        materializedFrame);
        authority.adoptPresentFrameBackedBindings();
        activation.installFrameLexicalBindingAuthorityForRuntime(authority);
    }

    /** Frame-native counterpart of {@link BindClosureParameter}. */
    @Operation
    @ConstantOperand(
            type = LocalRangeAccessor.class,
            name = "frameBackedLocals")
    @ConstantOperand(
            type = ProtosFrameLexicalLayout.class,
            name = "frameBackedLayout")
    @ConstantOperand(type = int.class, name = "ordinal")
    public static final class BindClosureFrameParameter {
        @Specialization
        public static void perform(
                LocalRangeAccessor frameBackedLocals,
                ProtosFrameLexicalLayout frameBackedLayout,
                int ordinal,
                ProtosActivation activation,
                String name,
                Object value,
                @Bind BytecodeNode bytecodeNode,
                @Bind("$bytecodeIndex") int bytecodeIndex,
                @Bind VirtualFrame frame) {
            try {
                createCurrentFrameBinding(
                        frameBackedLocals, frameBackedLayout, activation,
                        ordinal, name, value, bytecodeNode, bytecodeIndex, frame);
            } catch (IllegalStateException invalidCreation) {
                throw new ProtosSignalException(
                        ProtosCoreErrors.newError(activation));
            }
        }
    }

    /** Frame-native counterpart of {@link BindClosureRest}. */
    @Operation
    @ConstantOperand(
            type = LocalRangeAccessor.class,
            name = "frameBackedLocals")
    @ConstantOperand(
            type = ProtosFrameLexicalLayout.class,
            name = "frameBackedLayout")
    @ConstantOperand(type = int.class, name = "ordinal")
    public static final class BindClosureFrameRest {
        @Specialization
        public static void perform(
                LocalRangeAccessor frameBackedLocals,
                ProtosFrameLexicalLayout frameBackedLayout,
                int ordinal,
                ProtosActivation activation,
                String name,
                int positionalParametersBeforeRest,
                @Bind BytecodeNode bytecodeNode,
                @Bind("$bytecodeIndex") int bytecodeIndex,
                @Bind VirtualFrame frame) {
            Object rest = closureRestArray(activation, positionalParametersBeforeRest);
            try {
                createCurrentFrameBinding(
                        frameBackedLocals, frameBackedLayout, activation,
                        ordinal, name, rest, bytecodeNode, bytecodeIndex, frame);
            } catch (IllegalStateException invalidCreation) {
                throw new ProtosSignalException(
                        ProtosCoreErrors.newError(activation));
            }
        }
    }

    /** Frame-native counterpart of {@link CreateCurrentLocalSlot}. */
    @Operation
    @ConstantOperand(
            type = LocalRangeAccessor.class,
            name = "frameBackedLocals")
    @ConstantOperand(
            type = ProtosFrameLexicalLayout.class,
            name = "frameBackedLayout")
    @ConstantOperand(type = int.class, name = "ordinal")
    public static final class CreateCurrentFrameLocal {
        @Specialization
        public static Object perform(
                LocalRangeAccessor frameBackedLocals,
                ProtosFrameLexicalLayout frameBackedLayout,
                int ordinal,
                ProtosActivation activation,
                String name,
                Object value,
                @Bind BytecodeNode bytecodeNode,
                @Bind("$bytecodeIndex") int bytecodeIndex,
                @Bind VirtualFrame frame) {
            try {
                createCurrentFrameBinding(
                        frameBackedLocals, frameBackedLayout, activation,
                        ordinal, name, value, bytecodeNode, bytecodeIndex, frame);
            } catch (IllegalStateException invalidMutation) {
                throw new ProtosSignalException(
                        ProtosCoreErrors.newError(activation));
            }
            return value;
        }
    }

    /**
     * PLAT036 Candidate D, Slice 3: the direct/fast read of a {@code
     * Resolved} current-scope binding. Deliberately uses the same {@link
     * LocalAccessor} mechanism {@link ProtosFrameLexicalBindingAuthority}
     * itself uses to read/write this local, rather than the raw generated
     * {@code LoadLocal} bytecode instruction: a local ever written only
     * through the dynamic {@link LocalAccessor} API does not participate in
     * the frame-slot-kind speculation the DSL's own literal {@code
     * StoreLocal}/{@code LoadLocal} instruction pair relies on, so mixing the
     * two access mechanisms for the same local is not safe. Static resolution
     * proves binding identity, not permanent presence: D179 C0 allows a
     * PRESENT execution-context local to become ABSENT, so a cleared local
     * must resume the exact lexical/receiver fallback path.
     */
    @Operation
    @ConstantOperand(type = LocalAccessor.class, name = "accessor")
    @ConstantOperand(type = Assumption.class, name = "presenceContinuity")
    public static final class ReadFrameLocal {
        @Specialization
        public static Object perform(
                LocalAccessor accessor,
                Assumption presenceContinuity,
                ProtosActivation activation,
                String name,
                @Bind BytecodeNode bytecodeNode,
                @Bind VirtualFrame frame) {
            return ProtosNumericValueSupport.guestValue(
                    readRetainingCarrier(
                            accessor, presenceContinuity, activation, name, bytecodeNode, frame));
        }

        /**
         * I091: {@link #perform} without materializing a frame-retained
         * primitive carrier, for a lowered read whose consumer materializes
         * it (or is an operand of the guarded numeric chain).
         */
        static Object readRetainingCarrier(
                LocalAccessor accessor,
                Assumption presenceContinuity,
                ProtosActivation activation,
                String name,
                BytecodeNode bytecodeNode,
                VirtualFrame frame) {
            if (activation.hasGenuineExecutionContextForRuntime()
                    && (presenceContinuity.isValid()
                            || !accessor.isCleared(bytecodeNode, frame))) {
                return accessor.getObject(bytecodeNode, frame);
            }

            /*
             * Once continuity has been invalidated, D179 C0 remains exactly
             * presence-aware: a cleared genuine execution-context local is
             * semantically ABSENT and resumes lexical/receiver fallback.
             * Legacy/internal activations whose current lexical context is an
             * ordinary ProtosObjectValue use the same fallback.
             */
            return ProtosLexicalFallback.readByName(activation, name)
                    .orElseThrow(
                            () ->
                                    new ProtosSignalException(
                                            ProtosCoreErrors.newUnqualifiedLookupError(
                                                    activation)));
        }
    }

    /**
     * I068 Slice 5 direct captured read.
     *
     * <p>The statically proven owner is addressed by lexical depth and stable
     * frame-layout ordinal. Before taking that path, every semantically nearer
     * execution context is checked for PRESENT membership. This preserves
     * D179 C0 late creation/removal retargeting. Any topology/layout mismatch
     * falls back to the exact existing String-keyed lookup path.
     */
    @Operation
    @ConstantOperand(type = int.class, name = "frameOrdinal")
    public static final class ReadCapturedFrameLocal {
        @Specialization
        public static Object perform(
                int frameOrdinal,
                ProtosActivation activation,
                String name,
                int lexicalDepth) {
            if (lexicalDepth > 0
                    && !activation.currentContextHasLocalSlotForRuntime(name)) {
                Object value =
                        readCapturedFrameBindingOrNull(
                                activation.capturedLexicalEnvironmentForRuntime(),
                                name,
                                lexicalDepth,
                                frameOrdinal);
                if (value != null) {
                    return value;
                }
            }
            return lookupCapturedFallback(activation, name);
        }
    }

    /**
     * The statically proven direct-read primitive shared by {@link
     * ReadCapturedFrameLocal} and its PERF025 inline-callback counterpart,
     * once the current scope is known not to hold {@code name}: the owner
     * binding, or {@code null} when any nearer captured scope holds the name
     * PRESENT or the owner/layout does not match (the caller then takes the
     * exact fallback).
     */
    static Object readCapturedFrameBindingOrNull(
            ProtosLexicalEnvironment captured,
            String name,
            int lexicalDepth,
            int frameOrdinal) {
        return readOwnerFrameBindingOrNull(
                capturedOwnerWithoutNearerBinding(captured, name, lexicalDepth - 1),
                name,
                frameOrdinal);
    }

    /**
     * The owner half of {@link #readCapturedFrameBindingOrNull}: the binding
     * of an already selected {@code owner} (or {@code null} for none), or
     * {@code null} when its authority or layout does not match.
     */
    static Object readOwnerFrameBindingOrNull(
            ProtosLexicalEnvironment owner,
            String name,
            int frameOrdinal) {
        if (owner != null
                && owner.lexicalBindingAuthorityForRuntime()
                        instanceof ProtosFrameLexicalBindingAuthority authority
                && authority.hasFrameBackedBindingAt(name, frameOrdinal)) {
            return authority.readFrameBackedBindingAt(name, frameOrdinal);
        }
        return null;
    }

    /**
     * PERF013 Slice B1 / BUG018-C: owner-frame selection for the direct
     * captured read of a compile-time-proven owner {@link
     * com.oracle.truffle.api.bytecode.BytecodeLocal}. The accessor's identity
     * (owner root/local) is proven at lowering time by {@code
     * CanonicalToBytecodeLowerer} once owner and this Closure are lowered in
     * the same shared {@code BytecodeRootNodes} group (PERF013 Slice
     * A/A2/A3); only the owner's {@link MaterializedFrame} is selected
     * dynamically, through the same retained {@link
     * ProtosFrameLexicalBindingAuthority} instance {@link
     * ReadCapturedFrameLocal} already relies on. Every semantically nearer
     * execution-context presence check is preserved exactly as in {@link
     * ReadCapturedFrameLocal}, so D179 C0 late-creation/removal retargeting
     * is unaffected, and {@code isCleared} distinguishes {@code
     * PRESENT(null)} from {@code ABSENT} exactly as the runtime-authority path
     * does.
     *
     * <p>Returns the selected PRESENT owner frame, or {@code null} when the
     * generic captured lookup must run instead. The value itself is never read
     * here: the lowering loads it from the selected frame with the builtin
     * {@code LoadLocalMaterialized} of the same owner local, because {@link
     * MaterializedLocalAccessor#getObject} fails when the owner root's current
     * cached local-kind metadata, learned from another activation, disagrees
     * with the retained frame's physical tag (BUG018). Presence selection is
     * therefore independent of the value.
     */
    @Operation
    @ConstantOperand(type = MaterializedLocalAccessor.class)
    public static final class SelectCapturedMaterializedOwnerFrame {
        @Specialization
        public static MaterializedFrame perform(
                MaterializedLocalAccessor accessor,
                ProtosActivation activation,
                String name,
                int lexicalDepth,
                @Bind BytecodeNode bytecodeNode) {
            if (lexicalDepth > 0
                    && !activation.currentContextHasLocalSlotForRuntime(name)) {
                return capturedMaterializedOwnerFrameOrNull(
                        accessor,
                        activation.capturedLexicalEnvironmentForRuntime(),
                        name,
                        lexicalDepth,
                        bytecodeNode);
            }
            return null;
        }
    }

    /**
     * The {@link SelectCapturedMaterializedOwnerFrame} counterpart of {@link
     * #readCapturedFrameBindingOrNull}: the retained frame of the selected
     * owner when {@code accessor}'s local is PRESENT in it, otherwise {@code
     * null}.
     */
    static MaterializedFrame capturedMaterializedOwnerFrameOrNull(
            MaterializedLocalAccessor accessor,
            ProtosLexicalEnvironment captured,
            String name,
            int lexicalDepth,
            BytecodeNode bytecodeNode) {
        return ownerMaterializedFrameOrNull(
                accessor,
                capturedOwnerWithoutNearerBinding(captured, name, lexicalDepth - 1),
                bytecodeNode);
    }

    /**
     * The owner half of {@link #capturedMaterializedOwnerFrameOrNull}, for an
     * already selected {@code owner} (or {@code null} for none).
     */
    static MaterializedFrame ownerMaterializedFrameOrNull(
            MaterializedLocalAccessor accessor,
            ProtosLexicalEnvironment owner,
            BytecodeNode bytecodeNode) {
        if (owner == null
                || !(owner.lexicalBindingAuthorityForRuntime()
                        instanceof ProtosFrameLexicalBindingAuthority authority)) {
            return null;
        }
        MaterializedFrame ownerFrame =
                authority.retainedMaterializedFrameForCapturedAccess();
        if (ownerFrame == null || accessor.isCleared(bytecodeNode, ownerFrame)) {
            return null;
        }
        return ownerFrame;
    }

    /**
     * PERF025: selects the statically proven captured owner {@code ownerIndex}
     * scopes outward, or {@code null} when the captured chain is shorter or a
     * semantically nearer captured scope currently has the name PRESENT (D179
     * C0 late creation/removal retargeting). Membership is read through each
     * scope's single authority, so no deferred guest context is materialized.
     */
    private static ProtosLexicalEnvironment capturedOwnerWithoutNearerBinding(
            ProtosLexicalEnvironment captured,
            String name,
            int ownerIndex) {
        ProtosLexicalEnvironment scope = captured;
        for (int index = 0; scope != null && index < ownerIndex; index++) {
            if (scope.hasLocalSlotForRuntime(name)) {
                return null;
            }
            scope = scope.outer();
        }
        return scope;
    }

    /**
     * PERF037-C: a structural proof, retained by one specialized captured
     * read, that {@link #capturedOwnerWithoutNearerBinding} need not ask the
     * semantically nearer scopes for membership.
     *
     * <p>For every nearer scope position the proof records the frame layout
     * its authority stored when the read specialized. A scope whose current
     * authority stores that same layout cannot hold the read's name PRESENT:
     * the name is outside the layout (static resolution selected an outer
     * owner, and specialization re-checks it), and the layout's {@link
     * ProtosFrameLexicalLayout#noDynamicBindingOrNull} token, invalidated
     * before any dynamic-overflow binding is created through any authority
     * of that layout, rules out late creation (D179 C0). Removal can only
     * make a nearer scope ABSENT, which the proof already assumes.
     *
     * <p>The chain itself is never cached: it is the current invocation's
     * captured chain, each position's authority is re-read and checked
     * against the recorded layout, and the owner is the scope reached after
     * the nearer ones, so every invocation selects its own owner, deferred
     * or materialized (both answer through the same single authority).
     * The owner's PRESENT state and value are always read afterwards from
     * that owner, never from the proof. The first scope that does not match
     * permanently retires the proof for this read and resumes the exact
     * generic walk at that scope.
     */
    static final class CapturedNearerScopeAbsence {
        private static final ProtosFrameLexicalLayout[] NO_LAYOUTS =
                new ProtosFrameLexicalLayout[0];
        private static final Assumption[] NO_ASSUMPTIONS = new Assumption[0];

        @com.oracle.truffle.api.CompilerDirectives.CompilationFinal(dimensions = 1)
        private final ProtosFrameLexicalLayout[] nearerLayouts;

        @com.oracle.truffle.api.CompilerDirectives.CompilationFinal(dimensions = 1)
        private final Assumption[] noDynamicBinding;

        private final int ownerIndex;

        @com.oracle.truffle.api.CompilerDirectives.CompilationFinal
        private boolean retired;

        /*
         * PERF037-D: per-site representation profiles of the owner-frame
         * selection. Each records that its branch has been taken at least
         * once; an unseen branch is compiled as a deoptimization instead of
         * a merged path, so a site never pays for owner representations or
         * no-selection outcomes it has not executed. They profile control
         * flow only; the owner, its authority, frame and PRESENT state are
         * always re-read from the current invocation.
         */
        @com.oracle.truffle.api.CompilerDirectives.CompilationFinal
        private boolean seenMaterializedOwner;

        @com.oracle.truffle.api.CompilerDirectives.CompilationFinal
        private boolean seenDeferredOwner;

        @com.oracle.truffle.api.CompilerDirectives.CompilationFinal
        private boolean seenPublishedDeferredOwner;

        @com.oracle.truffle.api.CompilerDirectives.CompilationFinal
        private boolean seenNoSelection;

        private CapturedNearerScopeAbsence(
                ProtosFrameLexicalLayout[] nearerLayouts,
                Assumption[] noDynamicBinding,
                int ownerIndex,
                boolean retired) {
            this.nearerLayouts = nearerLayouts;
            this.noDynamicBinding = noDynamicBinding;
            this.ownerIndex = ownerIndex;
            this.retired = retired;
        }

        /** A proof that never holds; the read always takes the generic walk. */
        static CapturedNearerScopeAbsence unproven(int lexicalDepth) {
            return new CapturedNearerScopeAbsence(
                    NO_LAYOUTS, NO_ASSUMPTIONS, Math.max(lexicalDepth - 1, 0), true);
        }

        /**
         * PERF037-D: the uncached-interpreter instance; never proven and with
         * every branch already seen, so it is never mutated by execution.
         */
        static CapturedNearerScopeAbsence uncached(int lexicalDepth) {
            CapturedNearerScopeAbsence uncached = unproven(lexicalDepth);
            uncached.seenMaterializedOwner = true;
            uncached.seenDeferredOwner = true;
            uncached.seenPublishedDeferredOwner = true;
            uncached.seenNoSelection = true;
            return uncached;
        }

        /**
         * PERF037-D: exactly {@code ownerMaterializedFrameOrNull(accessor,
         * owner, bytecodeNode)} for an owner already selected by {@link
         * #ownerOrNull}, with every branch profiled at this site.
         */
        MaterializedFrame selectOwnerFrameOrNull(
                MaterializedLocalAccessor accessor,
                ProtosLexicalEnvironment owner,
                BytecodeNode bytecodeNode) {
            if (owner != null
                    && ownerAuthorityOrNull(owner)
                            instanceof ProtosFrameLexicalBindingAuthority authority) {
                MaterializedFrame ownerFrame =
                        authority.retainedMaterializedFrameForCapturedAccess();
                if (ownerFrame != null && !accessor.isCleared(bytecodeNode, ownerFrame)) {
                    return ownerFrame;
                }
            }
            profileNoSelection();
            return null;
        }

        /**
         * PERF037-D: records that this site selected no owner frame. Until
         * then the no-selection outcome is compiled as a deoptimization;
         * it changes no binding, owner or frame.
         */
        void profileNoSelection() {
            if (!seenNoSelection) {
                com.oracle.truffle.api.CompilerDirectives.transferToInterpreterAndInvalidate();
                seenNoSelection = true;
            }
        }

        /**
         * Exactly {@code owner.lexicalBindingAuthorityForRuntime()}, with the
         * owner's materialized/deferred representation profiled.
         */
        com.guillermomolina.protos.runtime.ProtosLexicalBindingAuthority
                ownerAuthorityOrNull(ProtosLexicalEnvironment owner) {
            ProtosObjectValue context = owner.materializedContextOrNullForRuntime();
            if (context != null) {
                if (!seenMaterializedOwner) {
                    com.oracle.truffle.api.CompilerDirectives
                            .transferToInterpreterAndInvalidate();
                    seenMaterializedOwner = true;
                }
            } else {
                if (!seenDeferredOwner) {
                    com.oracle.truffle.api.CompilerDirectives
                            .transferToInterpreterAndInvalidate();
                    seenDeferredOwner = true;
                }
                ProtosActivation deferredOwner = owner.deferredOwnerForRuntime();
                context = deferredOwner.materializedContextOrNullForRuntime();
                if (context == null) {
                    return deferredOwner.currentLexicalBindingAuthorityForRuntime();
                }
                if (!seenPublishedDeferredOwner) {
                    com.oracle.truffle.api.CompilerDirectives
                            .transferToInterpreterAndInvalidate();
                    seenPublishedDeferredOwner = true;
                }
            }
            return context
                            instanceof com.guillermomolina.protos.runtime
                                    .ProtosExecutionContextValue executionContext
                    ? executionContext.lexicalBindingAuthorityForRuntime()
                    : null;
        }

        /**
         * Specialization-time construction from the current captured chain.
         * Each token is obtained before the scope's membership is re-checked,
         * so a creation racing with construction either invalidates the
         * token or is observed by the check.
         */
        @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
        static CapturedNearerScopeAbsence create(
                ProtosLexicalEnvironment captured,
                String name,
                int lexicalDepth) {
            int ownerIndex = Math.max(lexicalDepth - 1, 0);
            if (ownerIndex == 0) {
                return new CapturedNearerScopeAbsence(NO_LAYOUTS, NO_ASSUMPTIONS, 0, false);
            }
            ProtosFrameLexicalLayout[] layouts = new ProtosFrameLexicalLayout[ownerIndex];
            Assumption[] tokens = new Assumption[ownerIndex];
            ProtosLexicalEnvironment scope = captured;
            for (int index = 0; index < ownerIndex; index++) {
                if (scope == null
                        || !(scope.lexicalBindingAuthorityForRuntime()
                                instanceof ProtosFrameLexicalBindingAuthority authority)) {
                    return unproven(lexicalDepth);
                }
                ProtosFrameLexicalLayout layout = authority.storedLayout();
                if (layout.offsetOf(name) != null) {
                    return unproven(lexicalDepth);
                }
                Assumption token = layout.noDynamicBindingOrNull();
                if (token == null || scope.hasLocalSlotForRuntime(name)) {
                    return unproven(lexicalDepth);
                }
                layouts[index] = layout;
                tokens[index] = token;
                scope = scope.outer();
            }
            return new CapturedNearerScopeAbsence(layouts, tokens, ownerIndex, false);
        }

        /**
         * Exactly {@code capturedOwnerWithoutNearerBinding(captured, name,
         * lexicalDepth - 1)}.
         */
        @ExplodeLoop
        ProtosLexicalEnvironment ownerOrNull(
                ProtosLexicalEnvironment captured,
                String name) {
            if (!retired) {
                ProtosLexicalEnvironment scope = captured;
                for (int index = 0; index < nearerLayouts.length; index++) {
                    if (scope == null) {
                        return null;
                    }
                    if (!(scope.lexicalBindingAuthorityForRuntime()
                                    instanceof ProtosFrameLexicalBindingAuthority authority)
                            || !authority.storesLayout(nearerLayouts[index])
                            || !noDynamicBinding[index].isValid()) {
                        com.oracle.truffle.api.CompilerDirectives
                                .transferToInterpreterAndInvalidate();
                        retired = true;
                        break;
                    }
                    scope = scope.outer();
                }
                if (!retired) {
                    return scope;
                }
            }
            return capturedOwnerWithoutNearerBinding(captured, name, ownerIndex);
        }
    }

    /**
     * PERF038-D: a monomorphic cache, retained by one specialized captured
     * materialized read, of {@link #ownerMaterializedFrameOrNull}'s owner
     * authority and retained frame.
     *
     * <p>The owner itself is never assumed: it is selected per invocation
     * (through {@link CapturedNearerScopeAbsence}) and compared by identity
     * with the recorded one. A scope's authority changes only when another
     * authority replaces it, which {@link
     * ProtosFrameLexicalBindingAuthority#retireInstallation} reports by
     * invalidating the recorded installation assumption; the scope's own
     * deferred-to-materialized transition keeps the same authority. While
     * the owner matches and the assumption holds, the recorded authority is
     * therefore the owner's current one, and its retained frame is final.
     * Presence (D179 C0 removal) is still read from that frame on every
     * execution. A different owner or a retired installation permanently
     * retires the cache for this read, which then takes the exact generic
     * selection.
     */
    static final class CapturedOwnerFrameCache {
        private static final Entry RETIRED = new Entry(null, null, null, null);

        /*
         * PERF037-D: presentContinuity is the owner layout's continuity token
         * for the read binding's ordinal, admitted only when the binding was
         * physically PRESENT in ownerFrame while the token was valid; null
         * when not admitted, and every hit then checks presence physically.
         */
        private record Entry(
                ProtosLexicalEnvironment owner,
                MaterializedFrame ownerFrame,
                Assumption installed,
                Assumption presentContinuity) {}

        /* One immutable entry, published in a single write; null until first use. */
        @com.oracle.truffle.api.CompilerDirectives.CompilationFinal
        private Entry entry;

        private CapturedOwnerFrameCache(Entry entry) {
            this.entry = entry;
        }

        static CapturedOwnerFrameCache create() {
            return new CapturedOwnerFrameCache(null);
        }

        /** A cache that never records; the read always takes the generic selection. */
        static CapturedOwnerFrameCache retired() {
            return new CapturedOwnerFrameCache(RETIRED);
        }

        /**
         * Exactly {@code ownerMaterializedFrameOrNull(accessor, owner,
         * bytecodeNode)}. PERF037-D: a miss or a retired cache takes the
         * selection profiled by this read's {@code profile}.
         */
        MaterializedFrame ownerFrameOrNull(
                MaterializedLocalAccessor accessor,
                ProtosLexicalEnvironment owner,
                String name,
                BytecodeNode bytecodeNode,
                CapturedNearerScopeAbsence profile) {
            Entry current = entry;
            if (current != RETIRED) {
                if (current != null
                        && owner == current.owner()
                        && current.installed().isValid()) {
                    MaterializedFrame ownerFrame = current.ownerFrame();
                    Assumption presentContinuity = current.presentContinuity();
                    if (presentContinuity != null && presentContinuity.isValid()) {
                        return ownerFrame;
                    }
                    if (accessor.isCleared(bytecodeNode, ownerFrame)) {
                        profile.profileNoSelection();
                        return null;
                    }
                    return ownerFrame;
                }
                com.oracle.truffle.api.CompilerDirectives.transferToInterpreterAndInvalidate();
                entry = current == null ? record(accessor, owner, name, bytecodeNode) : RETIRED;
            }
            return profile.selectOwnerFrameOrNull(accessor, owner, bytecodeNode);
        }

        @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
        private static Entry record(
                MaterializedLocalAccessor accessor,
                ProtosLexicalEnvironment owner,
                String name,
                BytecodeNode bytecodeNode) {
            if (owner == null
                    || !(owner.lexicalBindingAuthorityForRuntime()
                            instanceof ProtosFrameLexicalBindingAuthority authority)) {
                return RETIRED;
            }
            /*
             * The assumption is obtained before the authority is re-read, so
             * a replacement racing with recording either invalidates it or is
             * observed by the re-read.
             */
            Assumption installed = authority.installedAssumption();
            MaterializedFrame ownerFrame = authority.retainedMaterializedFrameForCapturedAccess();
            if (ownerFrame == null
                    || !installed.isValid()
                    || owner.lexicalBindingAuthorityForRuntime() != authority) {
                return RETIRED;
            }
            return new Entry(
                    owner,
                    ownerFrame,
                    installed,
                    admittedPresentContinuityOrNull(
                            accessor, authority, ownerFrame, name, bytecodeNode));
        }

        /**
         * PERF037-D: the continuity token of {@code name}'s ordinal in the
         * authority's frame-backed layout, admitted only when the binding is
         * physically PRESENT in {@code ownerFrame} and the token, obtained
         * before that check, is still valid after it. A valid token alone
         * does not prove presence: an ordinal never established is ABSENT
         * with its token intact. Removal invalidates the token before
         * clearing the local (PERF025-D179-A) and it is never renewed, so an
         * admitted valid token implies the binding is still PRESENT.
         */
        private static Assumption admittedPresentContinuityOrNull(
                MaterializedLocalAccessor accessor,
                ProtosFrameLexicalBindingAuthority authority,
                MaterializedFrame ownerFrame,
                String name,
                BytecodeNode bytecodeNode) {
            ProtosFrameLexicalLayout layout = authority.storedLayout();
            Integer ordinal = layout.offsetOf(name);
            if (ordinal == null) {
                return null;
            }
            Assumption presentContinuity = layout.presentContinuityAt(ordinal);
            if (!presentContinuity.isValid()
                    || accessor.isCleared(bytecodeNode, ownerFrame)
                    || !presentContinuity.isValid()) {
                return null;
            }
            return presentContinuity;
        }

        /** PERF037-D test access: the admitted continuity token, or {@code null}. */
        Assumption presentContinuityForTesting() {
            Entry current = entry;
            return current == null ? null : current.presentContinuity();
        }

        /** PERF037-D test access: true once the cache recorded an owner entry. */
        boolean isRecordedForTesting() {
            Entry current = entry;
            return current != null && current != RETIRED;
        }
    }

    /**
     * The generic captured lookup, also taken when {@link
     * SelectCapturedMaterializedOwnerFrame} selected no owner frame.
     */
    static Object lookupCapturedFallback(
            ProtosActivation activation,
            String name) {
        return ProtosLexicalFallback.readByName(activation, name)
                .orElseThrow(
                        () ->
                                new ProtosSignalException(
                                        ProtosCoreErrors.newUnqualifiedLookupError(
                                                activation)));
    }

    /**
     * I068 Slice 5 ephemeral write destination. It exists only inside one
     * executing activation; it is never stored in a semantic Closure value.
     *
     * <p>For a proven captured frame binding, {@code frameAuthority} and
     * {@code frameOwner} identify the exact outer destination selected before
     * RHS evaluation. The statically proven frame ordinal is deliberately not
     * retained here: PERF030-J supplies it again as the assignment operation's
     * constant operand, so the {@link LocalRangeAccessor} index never comes
     * from runtime destination state. For a PERF013 Slice B2 proven same-group
     * captured binding, {@code materializedOwnerFrame} identifies the exact
     * owner {@link MaterializedFrame} already selected before RHS evaluation,
     * to be revalidated and written through the assignment site's own {@link
     * MaterializedLocalAccessor} constant operand rather than re-resolved.
     * Otherwise {@code target} preserves the exact generic destination chosen
     * before RHS evaluation.
     */
    public static final class CapturedLexicalWriteTarget {
        private final ProtosObjectValue target;
        private final ProtosLexicalEnvironment frameOwner;
        private final ProtosFrameLexicalBindingAuthority frameAuthority;
        private final MaterializedFrame materializedOwnerFrame;

        private CapturedLexicalWriteTarget(
                ProtosObjectValue target,
                ProtosLexicalEnvironment frameOwner,
                ProtosFrameLexicalBindingAuthority frameAuthority,
                MaterializedFrame materializedOwnerFrame) {
            this.target = target;
            this.frameOwner = frameOwner;
            this.frameAuthority = frameAuthority;
            this.materializedOwnerFrame = materializedOwnerFrame;
        }

        static CapturedLexicalWriteTarget generic(
                ProtosObjectValue target) {
            return new CapturedLexicalWriteTarget(
                    java.util.Objects.requireNonNull(target, "target"),
                    null,
                    null,
                    null);
        }

        static CapturedLexicalWriteTarget frameBacked(
                ProtosLexicalEnvironment owner,
                ProtosFrameLexicalBindingAuthority authority) {
            return new CapturedLexicalWriteTarget(
                    null,
                    java.util.Objects.requireNonNull(owner, "owner"),
                    java.util.Objects.requireNonNull(authority, "authority"),
                    null);
        }

        static CapturedLexicalWriteTarget materialized(
                ProtosLexicalEnvironment owner,
                MaterializedFrame ownerFrame) {
            return new CapturedLexicalWriteTarget(
                    null,
                    java.util.Objects.requireNonNull(owner, "owner"),
                    null,
                    java.util.Objects.requireNonNull(ownerFrame, "ownerFrame"));
        }
    }

    @Operation
    @ConstantOperand(type = int.class, name = "frameOrdinal")
    public static final class ResolveCapturedWritableLexicalTarget {
        @Specialization
        public static CapturedLexicalWriteTarget perform(
                int frameOrdinal,
                ProtosActivation activation,
                String name,
                int lexicalDepth) {
            if (lexicalDepth > 0) {
                if (activation.currentContextHasLocalSlotForRuntime(name)) {
                    return CapturedLexicalWriteTarget.generic(
                            activation.context());
                }

                CapturedLexicalWriteTarget selected =
                        capturedFrameWriteTargetOrNull(
                                activation.capturedLexicalEnvironmentForRuntime(),
                                name,
                                lexicalDepth,
                                frameOrdinal);
                if (selected != null) {
                    return selected;
                }
            }

            ProtosObjectValue fallback =
                    ProtosLexicalFallback.writableContextByName(activation, name)
                            .orElseThrow(
                                    () ->
                                            new ProtosSignalException(
                                                    ProtosCoreErrors.newSlotNotFound(
                                                            activation)));
            return CapturedLexicalWriteTarget.generic(fallback);
        }
    }

    @Operation
    @ConstantOperand(type = int.class, name = "frameOrdinal")
    public static final class AssignCapturedFrameLocal {
        @Specialization
        public static Object perform(
                int frameOrdinal,
                ProtosActivation activation,
                CapturedLexicalWriteTarget destination,
                String name,
                Object value) {
            try {
                assignCapturedFrameDestination(destination, name, frameOrdinal, value);
            } catch (IllegalStateException invalidMutation) {
                throw new ProtosSignalException(
                        ProtosCoreErrors.newError(activation));
            }
            return value;
        }
    }

    /**
     * The statically proven destination-selection primitive shared by {@link
     * ResolveCapturedWritableLexicalTarget} and its PERF025 inline-callback
     * counterpart, once the current scope is known not to hold {@code name}:
     * the frame-backed owner destination, or {@code null} for the exact
     * generic selection.
     */
    static CapturedLexicalWriteTarget capturedFrameWriteTargetOrNull(
            ProtosLexicalEnvironment captured,
            String name,
            int lexicalDepth,
            int frameOrdinal) {
        ProtosLexicalEnvironment owner =
                capturedOwnerWithoutNearerBinding(captured, name, lexicalDepth - 1);
        if (owner != null
                && owner.lexicalBindingAuthorityForRuntime()
                        instanceof ProtosFrameLexicalBindingAuthority authority
                && authority.hasFrameBackedBindingAt(name, frameOrdinal)) {
            return CapturedLexicalWriteTarget.frameBacked(owner, authority);
        }
        return null;
    }

    /**
     * Writes exactly the destination selected before RHS evaluation, never
     * re-resolving it; an invalid mutation is reported as {@link
     * IllegalStateException} for the caller to turn into the guest Error.
     */
    static void assignCapturedFrameDestination(
            CapturedLexicalWriteTarget destination,
            String name,
            int frameOrdinal,
            Object value) {
        if (destination.frameAuthority != null) {
            /*
             * Match ProtosObjectValue.assignLocalSlot: CLOSED remains
             * writable, FROZEN does not. Presence/layout are checked
             * again at the actual mutation point.
             */
            if (destination.frameOwner.isFrozenForRuntime()) {
                throw new IllegalStateException("object is frozen");
            }
            destination.frameAuthority.assignFrameBackedBindingAt(
                    name,
                    frameOrdinal,
                    value);
        } else {
            // I091: object slots are guest-observable and never retain a carrier.
            destination.target.assignLocalSlot(
                    name, ProtosNumericValueSupport.guestValue(value));
        }
    }

    /**
     * PERF013 Slice B2 direct captured-write destination resolution using a
     * compile-time-proven owner {@link BytecodeLocal}, materialized via a
     * generated {@link MaterializedLocalAccessor}, mirroring {@link
     * SelectCapturedMaterializedOwnerFrame} on the write side. Every semantically
     * nearer execution-context presence check, and the static owner's own
     * {@code isCleared} presence check, are preserved exactly as in {@link
     * ResolveCapturedWritableLexicalTarget}, so D179 C0 late-creation/removal
     * retargeting is unaffected. The destination is fully selected here,
     * before RHS evaluation; {@link AssignCapturedMaterializedLocal} only
     * revalidates and writes the exact selection returned here, it never
     * re-resolves it.
     */
    @Operation
    @ConstantOperand(type = MaterializedLocalAccessor.class)
    public static final class ResolveCapturedMaterializedWritableLexicalTarget {
        @Specialization
        public static CapturedLexicalWriteTarget perform(
                MaterializedLocalAccessor accessor,
                ProtosActivation activation,
                String name,
                int lexicalDepth,
                @Bind BytecodeNode bytecodeNode) {
            if (lexicalDepth > 0) {
                if (activation.currentContextHasLocalSlotForRuntime(name)) {
                    return CapturedLexicalWriteTarget.generic(
                            activation.context());
                }

                CapturedLexicalWriteTarget selected =
                        capturedMaterializedWriteTargetOrNull(
                                accessor,
                                activation.capturedLexicalEnvironmentForRuntime(),
                                name,
                                lexicalDepth,
                                bytecodeNode);
                if (selected != null) {
                    return selected;
                }
            }

            ProtosObjectValue fallback =
                    ProtosLexicalFallback.writableContextByName(activation, name)
                            .orElseThrow(
                                    () ->
                                            new ProtosSignalException(
                                                    ProtosCoreErrors.newSlotNotFound(
                                                            activation)));
            return CapturedLexicalWriteTarget.generic(fallback);
        }
    }

    /**
     * PERF013 Slice B2 counterpart to {@link
     * ResolveCapturedMaterializedWritableLexicalTarget}: writes exactly the
     * destination already selected before RHS evaluation. For a materialized
     * destination, presence is revalidated (not re-resolved) through the same
     * constant {@link MaterializedLocalAccessor} used at resolution time; a
     * binding that has since been removed is a mutation error, never a
     * retarget. This never re-searches captured contexts.
     */
    @Operation
    @ConstantOperand(type = MaterializedLocalAccessor.class)
    public static final class AssignCapturedMaterializedLocal {
        @Specialization
        public static Object perform(
                MaterializedLocalAccessor accessor,
                ProtosActivation activation,
                CapturedLexicalWriteTarget destination,
                String name,
                Object value,
                @Bind BytecodeNode bytecodeNode) {
            try {
                assignCapturedMaterializedDestination(
                        accessor, destination, name, value, bytecodeNode);
            } catch (IllegalStateException invalidMutation) {
                throw new ProtosSignalException(
                        ProtosCoreErrors.newError(activation));
            }
            return value;
        }
    }

    /**
     * The {@link ResolveCapturedMaterializedWritableLexicalTarget} counterpart
     * of {@link #capturedFrameWriteTargetOrNull}.
     */
    static CapturedLexicalWriteTarget capturedMaterializedWriteTargetOrNull(
            MaterializedLocalAccessor accessor,
            ProtosLexicalEnvironment captured,
            String name,
            int lexicalDepth,
            BytecodeNode bytecodeNode) {
        ProtosLexicalEnvironment owner =
                capturedOwnerWithoutNearerBinding(captured, name, lexicalDepth - 1);
        if (owner != null
                && owner.lexicalBindingAuthorityForRuntime()
                        instanceof ProtosFrameLexicalBindingAuthority authority) {
            MaterializedFrame ownerFrame =
                    authority.retainedMaterializedFrameForCapturedAccess();
            if (ownerFrame != null && !accessor.isCleared(bytecodeNode, ownerFrame)) {
                return CapturedLexicalWriteTarget.materialized(owner, ownerFrame);
            }
        }
        return null;
    }

    /**
     * The {@link AssignCapturedMaterializedLocal} counterpart of {@link
     * #assignCapturedFrameDestination}.
     */
    static void assignCapturedMaterializedDestination(
            MaterializedLocalAccessor accessor,
            CapturedLexicalWriteTarget destination,
            String name,
            Object value,
            BytecodeNode bytecodeNode) {
        if (destination.materializedOwnerFrame != null) {
            /*
             * Match ProtosObjectValue.assignLocalSlot: CLOSED remains
             * writable, FROZEN does not. Presence is checked again at
             * the actual mutation point, against the exact selected
             * owner, never a different one.
             */
            if (destination.frameOwner.isFrozenForRuntime()) {
                throw new IllegalStateException("object is frozen");
            }
            if (accessor.isCleared(bytecodeNode, destination.materializedOwnerFrame)) {
                throw new IllegalStateException(
                        "captured frame-backed binding is absent or layout metadata mismatched: "
                                + name);
            }
            accessor.setObject(bytecodeNode, destination.materializedOwnerFrame, value);
        } else {
            destination.target.assignLocalSlot(
                    name, ProtosNumericValueSupport.guestValue(value));
        }
    }

    public static final class ResolvedLexicalWriteTarget {
        /**
         * PERF028-A selection of the statically proven current frame local,
         * recognized only by identity in {@link AssignCurrentFrameLocal}. It
         * is stateless because the destination is the assignment site's own
         * constant {@link LocalAccessor} in the executing frame, so the
         * ordinary PRESENT path allocates no destination object.
         */
        static final ResolvedLexicalWriteTarget STATIC_CURRENT_FRAME_LOCAL =
                new ResolvedLexicalWriteTarget(true, null);

        private final boolean currentContext;
        private final ProtosObjectValue object;

        private ResolvedLexicalWriteTarget(
                boolean currentContext,
                ProtosObjectValue object) {
            this.currentContext = currentContext;
            this.object = object;
        }

        static ResolvedLexicalWriteTarget currentContext() {
            return new ResolvedLexicalWriteTarget(true, null);
        }

        static ResolvedLexicalWriteTarget object(
                ProtosObjectValue object) {
            return new ResolvedLexicalWriteTarget(
                    false,
                    java.util.Objects.requireNonNull(object, "object"));
        }
    }

    @Operation
    public static final class ResolveWritableLexicalTarget {
        @Specialization
        public static ResolvedLexicalWriteTarget perform(
                ProtosActivation activation,
                String name) {
            ResolvedLexicalWriteTarget selected = selectByNameOrNull(activation, name);
            if (selected == null) {
                throw new ProtosSignalException(
                        ProtosCoreErrors.newSlotNotFound(activation));
            }
            return selected;
        }

        /**
         * TEST009-K: the residual String-keyed destination walk is a host boundary, like
         * {@link ProtosLexicalFallback#readByName}. Statically proven bindings select their
         * destination through the frame-backed operations and never reach it.
         */
        @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
        private static ResolvedLexicalWriteTarget selectByNameOrNull(
                ProtosActivation activation,
                String name) {
            if (activation.currentContextHasLocalSlotForRuntime(name)) {
                return ResolvedLexicalWriteTarget.currentContext();
            }

            for (ProtosLexicalEnvironment lexical =
                            activation.capturedLexicalEnvironmentForRuntime();
                    lexical != null;
                    lexical = lexical.outer()) {
                if (lexical.hasLocalSlotForRuntime(name)) {
                    return ResolvedLexicalWriteTarget.object(
                            lexical.context());
                }
            }

            if (activation.receiver()
                            instanceof ProtosObjectValue ordinaryReceiver
                    && ordinaryReceiver.hasLocalSlot(name)) {
                return ResolvedLexicalWriteTarget.object(
                        ordinaryReceiver);
            }

            return null;
        }
    }

    @Operation
    public static final class AssignResolvedLexicalTarget {
        @Specialization
        public static Object perform(
                ProtosActivation activation,
                ResolvedLexicalWriteTarget destination,
                String name,
                Object value) {
            try {
                if (destination.currentContext) {
                    activation.assignCurrentLocalSlotForRuntime(
                            name,
                            value);
                } else {
                    destination.object.assignLocalSlot(
                            name,
                            value);
                }
            } catch (IllegalStateException invalidMutation) {
                throw new ProtosSignalException(
                        ProtosCoreErrors.newError(activation));
            }
            return value;
        }
    }

    /**
     * PERF028-A destination selection for a bare assignment whose binding is
     * statically {@code Resolved} in the scope that owns the executing frame.
     * The assignment site's constant {@link LocalAccessor} identifies the
     * binding, so a PRESENT binding is selected without a String-keyed search
     * or a fresh destination object, using exactly the presence condition of
     * {@link ReadFrameLocal}. Static resolution does not prove presence (D179
     * C0): a cleared local, or a non-genuine current scope, runs the exact
     * generic {@link ResolveWritableLexicalTarget} selection now, before RHS
     * evaluation, and that selection is retained unchanged.
     */
    @Operation
    @ConstantOperand(type = LocalAccessor.class, name = "accessor")
    public static final class ResolveCurrentFrameLocalWriteTarget {
        @Specialization
        public static ResolvedLexicalWriteTarget perform(
                LocalAccessor accessor,
                ProtosActivation activation,
                String name,
                @Bind BytecodeNode bytecodeNode,
                @Bind VirtualFrame frame) {
            if (activation.hasGenuineExecutionContextForRuntime()
                    && !accessor.isCleared(bytecodeNode, frame)) {
                return ResolvedLexicalWriteTarget.STATIC_CURRENT_FRAME_LOCAL;
            }
            return ResolveWritableLexicalTarget.perform(activation, name);
        }
    }

    /**
     * PERF028-A counterpart to {@link ResolveCurrentFrameLocalWriteTarget}:
     * writes exactly the destination selected before RHS evaluation and never
     * re-resolves it. For the static current local, the mutation checks of
     * {@link ProtosObjectValue#assignLocalSlot} are applied to the same
     * current context and the same accessor: FROZEN is rejected (CLOSED stays
     * writable), and a binding cleared by the RHS is a mutation error rather
     * than a retarget, while one removed and recreated in the same context is
     * the same physical local and receives the write. The FROZEN check does
     * not materialize the guest Context. Execution contexts never track
     * lookup dependencies, so the direct write needs no invalidation. Any
     * other retained destination is written through the unchanged {@link
     * AssignResolvedLexicalTarget} path.
     */
    @Operation
    @ConstantOperand(type = LocalAccessor.class, name = "accessor")
    public static final class AssignCurrentFrameLocal {
        @Specialization
        public static Object perform(
                LocalAccessor accessor,
                ProtosActivation activation,
                ResolvedLexicalWriteTarget destination,
                String name,
                Object value,
                @Bind BytecodeNode bytecodeNode,
                @Bind VirtualFrame frame) {
            if (destination != ResolvedLexicalWriteTarget.STATIC_CURRENT_FRAME_LOCAL) {
                // I091: only direct frame storage may retain a primitive carrier.
                return AssignResolvedLexicalTarget.perform(
                        activation, destination, name,
                        ProtosNumericValueSupport.guestValue(value));
            }
            if (activation.currentContextIsFrozenForRuntime()
                    || accessor.isCleared(bytecodeNode, frame)) {
                throw new ProtosSignalException(
                        ProtosCoreErrors.newError(activation));
            }
            accessor.setObject(bytecodeNode, frame, value);
            return value;
        }
    }

    @Operation
    public static final class RequireObjectMutationTarget {
        @Specialization
        public static ProtosObjectValue perform(
                ProtosActivation activation,
                Object target) {
            // I091: a large Integer is a frozen object physically but an immutable Integer value.
            if (target instanceof ProtosObjectValue object
                    && !ProtosNumericValueSupport.isCurrentNumber(target)) {
                return object;
            }
            throw new ProtosSignalException(ProtosCoreErrors.newError(activation));
        }
    }

    @Operation
    public static final class CreateCurrentLocalSlot {
        @Specialization
        public static Object perform(
                ProtosActivation activation,
                String name,
                Object value) {
            try {
                activation.createCurrentLocalSlotForRuntime(name, value);
            } catch (IllegalStateException invalidMutation) {
                throw new ProtosSignalException(
                        ProtosCoreErrors.newError(activation));
            }
            return value;
        }
    }

    @Operation
    public static final class CreateLocalSlot {
        @Specialization
        public static Object perform(
                ProtosActivation activation,
                ProtosObjectValue target,
                String name,
                Object value) {
            try {
                target.createLocalSlot(name, value);
            } catch (IllegalStateException invalidMutation) {
                throw new ProtosSignalException(ProtosCoreErrors.newError(activation));
            }
            return value;
        }
    }

    @Operation
    public static final class MultipleCreateLocalSlots {
        @Specialization
        public static Object perform(
                ProtosActivation activation,
                String[] names,
                Object source) {
            int required = names.length;
            List<Object> observed =
                    observeMultipleCreatePrefix(activation, required, source);

            for (int index = 0; index < required; index++) {
                try {
                    activation.createCurrentLocalSlotForRuntime(
                            names[index],
                            observed.get(index));
                } catch (IllegalStateException invalidMutation) {
                    /*
                     * Deliberately no rollback: D143 applies ordinary ':'
                     * creation semantics left-to-right after extraction.
                     */
                    throw new ProtosSignalException(
                            ProtosCoreErrors.newError(activation));
                }
            }

            return source;
        }
    }

    static List<Object> observeMultipleCreatePrefix(
            ProtosActivation activation,
            int required,
            Object source) {
        List<Object> observed = multipleCreatePrefixOrNull(required, source);
        if (observed == null) {
            throw new ProtosSignalException(
                    ProtosCoreErrors.newError(activation));
        }
        return observed;
    }

    /**
     * The successful D143 prefix observation of {@link
     * #observeMultipleCreatePrefix}, or {@code null} when the source is not an
     * Array holding at least {@code required} elements (an Error case).
     */
    static List<Object> multipleCreatePrefixOrNull(int required, Object source) {
        if (!(source instanceof ProtosArrayValue array)
                || array.indexedSize() < required) {
            return null;
        }

        /*
         * D143 requires the complete fixed prefix to be shallow-observed
         * before the first target slot is created. Do not use at,
         * iteration, indexedSnapshot(), or any guest-visible protocol.
         */
        List<Object> observed = new ArrayList<>(required);
        for (int index = 0; index < required; index++) {
            observed.add(array.indexedAt(index));
        }
        return observed;
    }

    @Operation
    public static final class AssignLocalSlot {
        @Specialization
        public static Object perform(
                ProtosActivation activation,
                ProtosObjectValue target,
                String name,
                Object value) {
            try {
                target.assignLocalSlot(name, value);
            } catch (IllegalStateException invalidMutation) {
                throw new ProtosSignalException(ProtosCoreErrors.newError(activation));
            }
            return value;
        }
    }

    @Operation
    public static final class ReadMember {
        /**
         * PERF025-H3A shared inherited-member PIC. Distinct exact ordinary
         * receivers may share one selection when they have the same exact
         * direct parent and none owns the selector locally. The D013
         * dependency starts at the parent; local shadowing is guarded per
         * receiver and therefore does not invalidate unrelated siblings.
         */
        @Specialization(
                guards = {
                    "name.equals(cachedName)",
                    "cachedLookup != null",
                    "matchesSharedInheritedLookup(receiver, cachedName, cachedLookup)",
                    "cachedLookup.nonClosureContinuity() != null"
                },
                assumptions = {
                    "cachedLookup.stability()",
                    "cachedLookup.nonClosureContinuity()"
                },
                limit = "3")
        public static Object guardedSharedInheritedPlain(
                ProtosActivation activation,
                Object receiver,
                String name,
                @Cached("name") String cachedName,
                @Cached("createSharedInheritedLookup(receiver, name, activation)")
                        ProtosValueLookup.SharedInheritedSlotSelection cachedLookup) {
            return ProtosValueLookup.guardedPlainSlotValue(
                    cachedLookup.parentSelection());
        }

        /**
         * PERF025 ordinary exact-receiver member-read PIC. Selection is cached,
         * never the semantically observable extracted value. In particular a
         * selected Closure is rebound through materializeMemberRead on every
         * hit, so CALLABLES fresh receiver-bound extraction identity remains
         * exact.
         */
        @Specialization(
                guards = {
                    "name.equals(cachedName)",
                    "cachedLookup != null",
                    "matchesSharedInheritedLookup(receiver, cachedName, cachedLookup)"
                },
                assumptions = "cachedLookup.stability()",
                limit = "3")
        public static Object guardedSharedInherited(
                ProtosActivation activation,
                Object receiver,
                String name,
                @Cached("name") String cachedName,
                @Cached("createSharedInheritedLookup(receiver, name, activation)")
                        ProtosValueLookup.SharedInheritedSlotSelection cachedLookup,
                @Bind Node node,
                @Cached InlinedBranchProfile closureExtraction) {
            return ProtosValueLookup.materializeGuardedMemberRead(
                    receiver, cachedLookup, node, closureExtraction);
        }

        /**
         * PERF025 ordinary exact-receiver member-read PIC. Selection is cached,
         * never the semantically observable extracted value. In particular a
         * selected Closure is rebound through materializeMemberRead on every
         * hit, so CALLABLES fresh receiver-bound extraction identity remains
         * exact.
         */
        @Specialization(
                guards = {
                    "receiver == cachedReceiver",
                    "name.equals(cachedName)",
                    "cachedLookup != null",
                    "cachedLookup.nonClosureContinuity() != null"
                },
                assumptions = {
                    "cachedLookup.stability()",
                    "cachedLookup.nonClosureContinuity()"
                },
                limit = "3")
        public static Object guardedExactReceiverPlain(
                ProtosActivation activation,
                Object receiver,
                String name,
                @Cached("receiver") Object cachedReceiver,
                @Cached("name") String cachedName,
                @Cached("createGuardedLookup(receiver, name, activation)")
                        ProtosValueLookup.GuardedSlotSelection cachedLookup) {
            return ProtosValueLookup.guardedPlainSlotValue(cachedLookup);
        }

        @Specialization(
                guards = {
                    "receiver == cachedReceiver",
                    "name.equals(cachedName)",
                    "cachedLookup != null"
                },
                assumptions = "cachedLookup.stability()",
                limit = "3")
        public static Object guardedExactReceiver(
                ProtosActivation activation,
                Object receiver,
                String name,
                @Cached("receiver") Object cachedReceiver,
                @Cached("name") String cachedName,
                @Cached("createGuardedLookup(receiver, name, activation)")
                        ProtosValueLookup.GuardedSlotSelection cachedLookup,
                @Bind Node node,
                @Cached InlinedBranchProfile closureExtraction) {
            return ProtosValueLookup.materializeGuardedMemberRead(
                    receiver, cachedLookup, node, closureExtraction);
        }

        @Specialization(
                replaces = {
                    "guardedSharedInheritedPlain", "guardedSharedInherited",
                    "guardedExactReceiverPlain", "guardedExactReceiver"
                })
        public static Object perform(
                ProtosActivation activation,
                Object receiver,
                String name) {
            ProtosPrelude prelude = activation.prelude().orElse(null);
            try {
                return ProtosValueLookup.readMember(receiver, name, prelude)
                        .orElseThrow(
                                () ->
                                        new ProtosSignalException(
                                                ProtosCoreErrors.newSlotNotFound(activation)));
            } catch (UnsupportedOperationException unsupportedRepresentation) {
                throw new ProtosSignalException(ProtosCoreErrors.newError(activation));
            }
        }

        /**
         * The successful generic read of {@link #perform} through {@code
         * prelude}, or {@code null} when it would raise the absent-member or
         * unsupported-representation Error.
         */
        static Object readMemberOrNull(Object receiver, String name, ProtosPrelude prelude) {
            try {
                return ProtosValueLookup.readMember(receiver, name, prelude).orElse(null);
            } catch (UnsupportedOperationException unsupportedRepresentation) {
                return null;
            }
        }

        static ProtosValueLookup.SharedInheritedSlotSelection createSharedInheritedLookup(
                Object receiver,
                String name,
                ProtosActivation activation) {
            return createSharedInheritedLookupForPrelude(
                    receiver,
                    name,
                    activation.preludeOrNullForRuntime());
        }

        static ProtosValueLookup.SharedInheritedSlotSelection createSharedInheritedLookupForPrelude(
                Object receiver,
                String name,
                ProtosPrelude prelude) {
            return ProtosValueLookup.lookupGuardedSharedInheritedSlotSelection(receiver, name, prelude);
        }

        static boolean matchesSharedInheritedLookup(
                Object receiver,
                String name,
                ProtosValueLookup.SharedInheritedSlotSelection cachedLookup) {
            return ProtosValueLookup.matchesGuardedSharedInheritedSlotSelection(
                    receiver,
                    name,
                    cachedLookup);
        }

        static ProtosValueLookup.GuardedSlotSelection createGuardedLookup(
                Object receiver,
                String name,
                ProtosActivation activation) {
            return createGuardedLookupForPrelude(
                    receiver,
                    name,
                    activation.preludeOrNullForRuntime());
        }

        static ProtosValueLookup.GuardedSlotSelection createGuardedLookupForPrelude(
                Object receiver,
                String name,
                ProtosPrelude prelude) {
            try {
                return ProtosValueLookup.lookupGuardedSlotSelection(receiver, name, prelude);
            } catch (UnsupportedOperationException unsupportedRepresentation) {
                return null;
            }
        }
    }

    @Operation
    public static final class Identity {
        @Specialization
        public static Object perform(Object left, Object right) {
            return ProtosIdentity.identical(left, right)
                    ? ProtosBooleanValue.TRUE
                    : ProtosBooleanValue.FALSE;
        }
    }

    @Operation
    public static final class NotIdentity {
        @Specialization
        public static Object perform(Object left, Object right) {
            return ProtosIdentity.identical(left, right)
                    ? ProtosBooleanValue.FALSE
                    : ProtosBooleanValue.TRUE;
        }
    }

    @Operation
    public static final class ComplementEqualityResult {
        @Specialization
        public static Object perform(
                ProtosActivation activation,
                Object equalityResult) {
            Object validated =
                    ProtosStandardBooleanProtocol.requireBooleanResult(
                            equalityResult,
                            activation);
            return validated == ProtosBooleanValue.TRUE
                    ? ProtosBooleanValue.FALSE
                    : ProtosBooleanValue.TRUE;
        }
    }

    @Operation
    public static final class LoadIntrinsic {
        @Specialization
        public static Object perform(
                ProtosActivation activation,
                CanonicalIntrinsic.Kind kind) {
            return switch (kind) {
                case THIS -> activation.receiver();
                case CONTEXT -> activation.context();
            };
        }
    }

    @Operation
    public static final class MaterializeClosure {
        @Specialization
        public static ProtosClosureValue perform(
                ProtosActivation activation,
                CanonicalClosure definition,
                ProtosClosureExecutionPlanCell executionPlanCell) {
            return new ProtosClosureValue(
                    definition,
                    activation.lexicalEnvironmentForClosureCapture(),
                    activation.receiver(),
                    activation.methodHome().orElse(null),
                    activation.returnHome().orElse(null),
                    activation.prelude().orElse(null),
                    executionPlanCell.plan());
        }
    }

    /**
     * PLAT041 C′: allocates the Object under construction. The Object body is
     * then lowered inline in the enclosing root as a resumable region (see
     * {@code CanonicalToBytecodeLowerer#emitInlineObjectConstruction}); it is
     * not a physical root, nor a lexical execution context.
     */
    @Operation
    public static final class NewConstructedObject {
        @Specialization
        public static ProtosObjectValue perform(Object parent) {
            return new ProtosObjectValue(parent);
        }
    }

    /**
     * PLAT041 C′: the construction activation the inline Object body executes
     * with. {@link ProtosActivation#forObjectConstruction} remains the sole
     * authority for its receiver/context, capture chain, homes, and inherited
     * Task or dynamic-control identity.
     */
    @Operation
    public static final class NewObjectConstructionActivation {
        @Specialization
        public static ProtosActivation perform(
                ProtosActivation enclosing,
                ProtosObjectValue object) {
            return ProtosActivation.forObjectConstruction(object, enclosing);
        }
    }

    /**
     * Immutable reserved local-slot names of a composition item, carried as a
     * Bytecode constant operand of {@link ComposeLocalSlots}. A non-generic
     * carrier keeps the DSL-generated operand read a checked cast; a
     * parameterized {@code List<String>} operand forces an erased unchecked
     * cast in the generated interpreter.
     */
    public record ComposeReservedNames(java.util.List<String> names) {
        public ComposeReservedNames {
            names = java.util.List.copyOf(names);
        }
    }

    @Operation
    public static final class ComposeLocalSlots {
        @Specialization
        public static ProtosObjectValue perform(
                ProtosActivation activation,
                Object sourceValue,
                ComposeReservedNames reservedNames) {
            if (!(sourceValue instanceof ProtosObjectValue source)
                    || ProtosNumericValueSupport.isCurrentNumber(sourceValue)) {
                throw new ProtosSignalException(ProtosCoreErrors.newError(activation));
            }

            ProtosObjectValue target = activation.context();
            try {
                target.composeLocalSlotsFrom(source, reservedNames.names());
            } catch (IllegalStateException failure) {
                throw new ProtosSignalException(ProtosCoreErrors.newError(activation));
            }
            return target;
        }
    }

    @Operation
    public static final class RaiseNonLocalReturn {
        @Specialization
        public static Object perform(
                ProtosActivation activation,
                Object value) {
            ProtosReturnHome target =
                    activation.returnHome().orElse(null);
            if (target != null && !target.isMaterialized()) {
                throw new IllegalStateException(
                        "non-local return reached a return home proven unobservable");
            }
            if (target == null || !target.isActive()) {
                throw new ProtosSignalException(
                        ProtosCoreErrors.newInvalidReturn(activation));
            }
            throw new ProtosNonLocalReturnException(target, value);
        }
    }

    /**
     * Optional structured-control capability carried by a {@link NativeCall}.
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
        final boolean whileLoop;
        final ProtosStandardBooleanProtocol.StructuredCallbackKind booleanKind;
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
                boolean whileLoop,
                ProtosStandardBooleanProtocol.StructuredCallbackKind booleanKind,
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
            this.whileLoop = whileLoop;
            this.booleanKind = booleanKind;
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
                            + (whileLoop ? 1 : 0)
                            + (booleanKind != null ? 1 : 0)
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
                boolean whileLoop,
                ProtosStandardBooleanProtocol.StructuredCallbackKind booleanKind,
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
                            || whileLoop
                            || booleanKind != null
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
                    whileLoop,
                    booleanKind,
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
     * A prepared, about-to-execute Closure invocation.
     *
     * <p>I072 Phase D (PLAT040 Candidate F′): this is a small dispatch surface
     * shared by every call shape, not a universal physical carrier. Exactly one
     * of four leaf shapes implements it per call — {@link OrdinarySourceCall},
     * {@link NativeCall}, {@link ImmediateResultCall}, or {@link
     * ModuleInitializationCall} — and each leaf physically carries only the
     * state its own shape actually uses. An
     * ordinary source-backed call never allocates native-body, structured-
     * control-capability, or module-initialization state; those remain
     * exclusive to the two special shapes that actually need them. Interface
     * default methods below only supply the "this capability is absent"
     * answer for shapes that never own it; they add no per-instance state.
     */
    interface PreparedClosureCall {
        RootCallTarget bodyTarget();

        ProtosActivation activation();

        Object[] targetArguments();

        ProtosTask taskForRuntime();

        default boolean isNative() { return false; }

        default boolean isImmediate() { return false; }

        default Object enterImmediate() {
            throw new IllegalStateException("prepared call has no immediate result");
        }

        default Object enterNative() {
            throw new IllegalStateException("prepared Closure call is not native");
        }

        default boolean requiresStructuredDispatch() { return false; }

        default boolean isStructuredObjectCall() { return false; }
        default boolean isStructuredCaseOf() { return false; }
        default boolean isStructuredMapMatch() { return false; }
        default boolean isStructuredImportCall() { return false; }
        default boolean isStructuredEnsure() { return false; }
        default boolean isStructuredErrorHandler() { return false; }
        default boolean isStructuredWhile() { return false; }
        default boolean isStructuredBoolean() { return false; }
        default boolean isStructuredArrayEach() { return false; }
        default boolean isStructuredArrayMatch() { return false; }
        default boolean isStructuredBytesEach() { return false; }
        default boolean isStructuredEnvironmentEach() { return false; }
        default boolean isStructuredIdentityMapAtIfAbsent() { return false; }
        default boolean isStructuredIdentityMapEach() { return false; }
        default boolean isStructuredMapEach() { return false; }
        default boolean isStructuredMapReadLookup() { return false; }
        default boolean isStructuredMapAtPut() { return false; }
        default boolean isStructuredMapRemove() { return false; }
        default boolean isStructuredForeignEach() { return false; }

        /**
         * PLAT044 B′ (PERF026-C1) whole-pair admission of a selected standard
         * whileTrue; see {@link NativeCall#admitsInlineLiteralWhile}.
         */
        default boolean admitsInlineLiteralWhile(Object condition, Object body) {
            return false;
        }

        /**
         * PLAT044 B′ (PERF026-D1) whole-call admission of a selected standard
         * Array.each or Bytes.each; see {@link
         * NativeCall#admitsInlineLiteralIndexedEach}.
         */
        default boolean admitsInlineLiteralIndexedEach(Object callback) {
            return false;
        }

        /**
         * PLAT044 B′ (PERF026-D2) whole-call admission of a selected standard
         * Map.each or IdentityMap.each; see {@link
         * NativeCall#admitsInlineLiteralAssociationEach}.
         */
        default boolean admitsInlineLiteralAssociationEach(Object callback) {
            return false;
        }

        /**
         * PLAT044 B′ (PERF026-D3) whole-call admission of a selected standard
         * Environment.each; see {@link
         * NativeCall#admitsInlineLiteralEnvironmentEach}.
         */
        default boolean admitsInlineLiteralEnvironmentEach(Object callback) {
            return false;
        }

        default PreparedMapMatchCall prepareStructuredMapMatch() {
            throw new IllegalStateException(
                    "prepared Closure call has no structured Map.match capability");
        }

        default PreparedCaseOfCall prepareStructuredCaseOf() {
            throw new IllegalStateException(
                    "prepared Closure call has no structured Object.caseOf capability");
        }

        default PreparedStandardObjectCall prepareStructuredObjectCall() {
            throw new IllegalStateException(
                    "prepared Closure call has no PLAT032 Object.call capability");
        }

        default PreparedStandardImportCall prepareStructuredImportCall() {
            throw new IllegalStateException(
                    "prepared Closure call has no PLAT032 import capability");
        }

        default PreparedEnsureCall prepareStructuredEnsure() {
            throw new IllegalStateException(
                    "prepared Closure call has no structured ensure capability");
        }

        default PreparedWhileCall prepareStructuredWhile() {
            throw new IllegalStateException(
                    "prepared Closure call has no structured while capability");
        }

        default PreparedErrorHandlerCall prepareStructuredErrorHandler() {
            throw new IllegalStateException(
                    "prepared Closure call has no structured Error.handle capability");
        }

        default PreparedBooleanCall prepareStructuredBoolean() {
            throw new IllegalStateException(
                    "prepared Closure call has no structured Boolean callback capability");
        }

        default PreparedArrayEachCall prepareStructuredArrayEach() {
            throw new IllegalStateException(
                    "prepared Closure call has no structured Array.each capability");
        }

        default PreparedArrayMatchCall prepareStructuredArrayMatch() {
            throw new IllegalStateException(
                    "prepared Closure call has no structured Array.match capability");
        }

        default PreparedBytesEachCall prepareStructuredBytesEach() {
            throw new IllegalStateException(
                    "prepared Closure call has no structured Bytes.each capability");
        }

        default PreparedEnvironmentEachCall prepareStructuredEnvironmentEach() {
            throw new IllegalStateException(
                    "prepared Closure call has no structured Environment.each capability");
        }

        default PreparedIdentityMapAtIfAbsentCall prepareStructuredIdentityMapAtIfAbsent() {
            throw new IllegalStateException(
                    "prepared Closure call has no structured IdentityMap.atIfAbsent capability");
        }

        default PreparedIdentityMapEachCall prepareStructuredIdentityMapEach() {
            throw new IllegalStateException(
                    "prepared Closure call has no structured IdentityMap.each capability");
        }

        default PreparedMapEachCall prepareStructuredMapEach() {
            throw new IllegalStateException(
                    "prepared Closure call has no structured Map.each capability");
        }

        default PreparedMapReadLookupCall prepareStructuredMapReadLookup() {
            throw new IllegalStateException(
                    "prepared Closure call has no structured Map read-lookup capability");
        }

        default PreparedMapAtPutCall prepareStructuredMapAtPut() {
            throw new IllegalStateException(
                    "prepared Closure call has no structured Map.atPut capability");
        }

        default PreparedMapRemoveCall prepareStructuredMapRemove() {
            throw new IllegalStateException(
                    "prepared Closure call has no structured Map.remove capability");
        }

        default ProtosForeignEachCall prepareStructuredForeignEach() {
            throw new IllegalStateException(
                    "prepared Closure call has no structured foreign each capability");
        }

        Object handleControlTransfer(ControlFlowException transfer);

        void failIfModuleInitialization();

        RuntimeException mapRuntimeFailure(RuntimeException failure);

        void complete();

        Object finish(Object result);

        static PreparedClosureCall ordinary(RootCallTarget bodyTarget, ProtosActivation activation) {
            return new OrdinarySourceCall(bodyTarget, activation);
        }

        static PreparedClosureCall ordinaryCompact(
                RootCallTarget bodyTarget, Object[] compactTargetArguments) {
            return new OrdinarySourceCall(bodyTarget, compactTargetArguments);
        }

        /**
         * PERF038-D: {@link #ordinaryCompact} for frame arguments the caller
         * has just built with {@link ProtosFrameArguments#compactImmediateMethodCall}
         * from {@code returnHome} and {@code suppliedArgumentCount} supplied
         * values; {@code ownsReturnHome} is exactly {@link
         * ProtosFrameArguments#compactOwnsReturnHome} of that array.
         */
        static PreparedClosureCall ordinaryCompactSelected(
                RootCallTarget selectedEntryTarget,
                Object[] compactTargetArguments,
                ProtosReturnHome returnHome,
                boolean ownsReturnHome) {
            return new OrdinarySourceCall(
                    selectedEntryTarget,
                    compactTargetArguments,
                    returnHome,
                    ownsReturnHome);
        }

        static PreparedClosureCall ordinaryCompactPrepared(
                RootCallTarget bodyTarget,
                Object[] compactTargetArguments,
                ProtosReturnHome returnHome,
                boolean ownsReturnHome,
                int suppliedArgumentCount) {
            return new OrdinarySourceCall(
                    bodyTarget,
                    compactTargetArguments,
                    returnHome,
                    ownsReturnHome,
                    suppliedArgumentCount);
        }

        static PreparedClosureCall nativeCall(
                ProtosNativeClosureBody nativeBody,
                List<?> supplied,
                ProtosActivation activation,
                boolean structuredEnsure,
                boolean structuredErrorHandler,
                boolean structuredWhile,
                ProtosStandardBooleanProtocol.StructuredCallbackKind structuredBoolean,
                boolean structuredArrayEach,
                boolean structuredBytesEach,
                boolean structuredEnvironmentEach,
                boolean structuredIdentityMapEach,
                boolean structuredMapEach,
                ProtosStandardMapProtocol.StructuredReadLookupKind structuredMapReadLookup,
                boolean structuredMapAtPut,
                boolean structuredMapRemove,
                boolean directControlNative,
                boolean structuredObjectCall,
                ProtosModuleRuntime structuredImportRuntime) {
            return new NativeCall(
                    java.util.Objects.requireNonNull(nativeBody, "nativeBody"),
                    supplied,
                    activation,
                    StructuredCallCapabilities.of(
                            structuredEnsure,
                            structuredErrorHandler,
                            structuredWhile,
                            structuredBoolean,
                            structuredArrayEach,
                            structuredBytesEach,
                            structuredEnvironmentEach,
                            structuredIdentityMapEach,
                            structuredMapEach,
                            structuredMapReadLookup,
                            structuredMapAtPut,
                            structuredMapRemove,
                            directControlNative,
                            structuredObjectCall,
                            structuredImportRuntime));
        }

        static PreparedClosureCall immediateResult(Object result) {
            return new ImmediateResultCall(result);
        }

        static PreparedClosureCall moduleInitialization(
                ProtosModuleRuntime.PreparedModuleInitialization moduleInitialization) {
            return new ModuleInitializationCall(moduleInitialization);
        }
    }

    /**
     * Shared return-home ownership/completion behavior for the two prepared-call
     * shapes that actually own a {@link ProtosReturnHome}: {@link
     * OrdinarySourceCall} and {@link NativeCall}. {@link ModuleInitializationCall}
     * has no return-home concept and implements {@link PreparedClosureCall}
     * directly instead of extending this class.
     */
    private abstract static class ReturnHomeOwningCall implements PreparedClosureCall {
        private final ProtosReturnHome returnHome;
        /**
         * PERF025: an owned but non-materialized home has no lifecycle and can
         * never be a non-local-return target, so it is neither matched nor
         * completed.
         */
        private final boolean ownsMaterializedReturnHome;

        ReturnHomeOwningCall(ProtosReturnHome returnHome, boolean ownsReturnHome) {
            /*
             * PERF038-H: ordinary calls that cannot own a physical return
             * home have no return-home lifecycle or target to retain.
             * Captured homes still travel in the callee frame arguments,
             * where their provenance remains observable when required.
             */
            boolean hasLifecycle = ownsReturnHome && returnHome.isMaterialized();
            this.returnHome = hasLifecycle ? returnHome : null;
            this.ownsMaterializedReturnHome = hasLifecycle;
        }

        /**
         * PERF038-E: false when this call owns no materialized home, so
         * {@link #complete} and {@link #finish} have no effect beyond answering
         * the result and {@link #handleControlTransfer} rethrows every transfer.
         * Final for the carrier.
         */
        final boolean ownsMaterializedReturnHome() {
            return ownsMaterializedReturnHome;
        }

        @Override
        public Object handleControlTransfer(ControlFlowException transfer) {
            if (transfer instanceof ProtosNonLocalReturnException nonLocalReturn
                    && ownsMaterializedReturnHome
                    && returnHome.isActive()
                    && nonLocalReturn.target() == returnHome) {
                return nonLocalReturn.value();
            }
            throw transfer;
        }

        @Override
        public void failIfModuleInitialization() {
            // Neither shape extending this class owns module-initialization state.
        }

        @Override
        public RuntimeException mapRuntimeFailure(RuntimeException failure) {
            return failure;
        }

        @Override
        public void complete() {
            if (ownsMaterializedReturnHome && returnHome.isActive()) {
                returnHome.complete();
            }
        }

        @Override
        public Object finish(Object result) {
            complete();
            return result;
        }
    }

    /**
     * The lean, pay-only-when-used representation of an ordinary source-backed
     * Closure call. It carries exactly a selected target, the compact frame
     * arguments (or, for the pre-target rich-activation shape, the activation
     * itself), and the return-home/ownership state that non-local return
     * completion actually requires. It never carries native-body, structured-
     * control-capability, or module-initialization state.
     *
     * <p>PERF032-G6: every ordinary source call is constructed here, so this
     * is where the entered target is selected ({@link
     * ProtosSemanticBytecodeRootNode#selectSourceEntryTarget}): a call
     * supplying arguments to a Closure declaring no parameters enters that
     * Closure's arity-rejection root with the same frame arguments.
     */
    static final class OrdinarySourceCall extends ReturnHomeOwningCall {
        private final RootCallTarget bodyTarget;
        private final ProtosActivation activation;
        private final Object[] targetArguments;

        OrdinarySourceCall(RootCallTarget bodyTarget, ProtosActivation activation) {
            super(
                    java.util.Objects.requireNonNull(activation, "activation")
                            .returnHome()
                            .orElseThrow(
                                    () ->
                                            new IllegalStateException(
                                                    "Closure invocation requires a return home")),
                    activation.ownsReturnHome());
            this.bodyTarget =
                    ProtosSemanticBytecodeRootNode.selectSourceEntryTarget(
                            java.util.Objects.requireNonNull(bodyTarget, "bodyTarget"),
                            activation.suppliedArgumentCountForRuntime());
            this.activation = activation;
            this.targetArguments = new Object[] {activation};
        }

        OrdinarySourceCall(RootCallTarget bodyTarget, Object[] compactTargetArguments) {
            super(
                    ProtosFrameArguments.compactReturnHome(
                            java.util.Objects.requireNonNull(
                                    compactTargetArguments, "compactTargetArguments")),
                    ProtosFrameArguments.compactOwnsReturnHome(compactTargetArguments));
            this.bodyTarget =
                    ProtosSemanticBytecodeRootNode.selectSourceEntryTarget(
                            java.util.Objects.requireNonNull(bodyTarget, "bodyTarget"),
                            ProtosFrameArguments.compactSuppliedArgumentCount(
                                    compactTargetArguments));
            this.activation = null;
            this.targetArguments = compactTargetArguments;
        }

        /**
         * PERF038-H: the guarded PIC has already selected the exact
         * source entry target. No arity-based target selection remains
         * necessary inside this carrier constructor.
         */
        OrdinarySourceCall(
                RootCallTarget selectedEntryTarget,
                Object[] compactTargetArguments,
                ProtosReturnHome returnHome,
                boolean ownsReturnHome) {
            super(returnHome, ownsReturnHome);
            this.bodyTarget = selectedEntryTarget;
            this.activation = null;
            this.targetArguments = compactTargetArguments;
        }

        /** PERF038-D: see {@link PreparedClosureCall#ordinaryCompactPrepared}. */
        OrdinarySourceCall(
                RootCallTarget bodyTarget,
                Object[] compactTargetArguments,
                ProtosReturnHome returnHome,
                boolean ownsReturnHome,
                int suppliedArgumentCount) {
            super(returnHome, ownsReturnHome);
            /*
             * PERF038-H: a zero-argument call cannot require the
             * excess-argument rejection entry. Preserve the general
             * selection for calls carrying supplied arguments.
             */
            this.bodyTarget =
                    suppliedArgumentCount == 0
                            ? bodyTarget
                            : ProtosSemanticBytecodeRootNode.selectSourceEntryTarget(
                                    bodyTarget, suppliedArgumentCount);
            this.activation = null;
            this.targetArguments = compactTargetArguments;
        }

        /**
         * PERF038-E: the {@code FinishClosureCall} guard; declared here because
         * the DSL-generated node cannot see members of the private base class.
         */
        boolean hasReturnHomeLifecycle() {
            return ownsMaterializedReturnHome();
        }

        /**
         * PERF038-H: read the capability of the actual selected
         * source entry. Arity rejection and unknown targets
         * retain the general continuation protocol.
         */


        /** PERF038-H: capability of the selected source entry. */
        boolean provablyNonSuspending() {
            return bodyTarget.getRootNode()
                    instanceof ProtosSemanticBytecodeRootNode root
                    && root.provablyNonSuspendingBody();
        }

        @Override
        public RootCallTarget bodyTarget() { return bodyTarget; }

        @Override
        public ProtosActivation activation() {
            if (activation == null) {
                throw new IllegalStateException(
                        "compact source call has no pre-target rich activation");
            }
            return activation;
        }

        @Override
        public Object[] targetArguments() { return targetArguments; }

        /*
         * I101-B: a minimal source root may return a primitive carrier.
         * Do not expose it through an ordinary guest call. Fused callers
         * that explicitly accept primitive results retain that fast path.
         */
        @Override
        public Object finish(Object result) {
            // I101-B: project only this NORMAL local-root carrier at a guest boundary.
            if (bodyTarget.getRootNode() instanceof ProtosSemanticBytecodeRootNode root
                    && root.directScalarLocalCarrierBody()
                    && !ProtosFrameArguments.acceptsPrimitiveResult(targetArguments)) {
                result = ProtosNumericValueSupport.guestValue(result);
            }
            return super.finish(
                    ProtosFrameArguments.acceptsPrimitiveResult(targetArguments)
                            ? result
                            : ProtosNumericValueSupport.guestValue(result));
        }

        @Override
        public ProtosTask taskForRuntime() {
            if (activation != null) {
                return activation.task().orElse(null);
            }
            return ProtosFrameArguments.compactTask(targetArguments);
        }
    }

    /**
     * The special-call representation for a native Closure body, optionally
     * carrying exactly one mutually exclusive {@link StructuredCallCapabilities}.
     * Ordinary source calls never instantiate this class.
     */
    static final class NativeCall extends ReturnHomeOwningCall {
        private final ProtosNativeClosureBody nativeBody;
        private final List<?> supplied;
        private final ProtosActivation activation;
        private final StructuredCallCapabilities structured;

        NativeCall(
                ProtosNativeClosureBody nativeBody,
                List<?> supplied,
                ProtosActivation activation,
                StructuredCallCapabilities structured) {
            super(
                    java.util.Objects.requireNonNull(activation, "activation")
                            .returnHome()
                            .orElseThrow(
                                    () ->
                                            new IllegalStateException(
                                                    "Closure invocation requires a return home")),
                    activation.ownsReturnHome());
            this.nativeBody = java.util.Objects.requireNonNull(nativeBody, "nativeBody");
            this.supplied = List.copyOf(supplied);
            this.activation = activation;
            this.structured = structured;
        }

        @Override
        public RootCallTarget bodyTarget() { return null; }

        @Override
        public ProtosActivation activation() { return activation; }

        @Override
        public Object[] targetArguments() {
            throw new IllegalStateException("prepared call has no source target arguments");
        }

        @Override
        public ProtosTask taskForRuntime() { return activation.task().orElse(null); }

        @Override
        public boolean isNative() { return true; }

        @Override
        public boolean isStructuredObjectCall() { return structured != null && structured.objectCall; }

        @Override
        public boolean isStructuredCaseOf() {
            return ProtosStandardObjectProtocol.isStandardCaseOfImplementation(nativeBody);
        }

        @Override
        public boolean isStructuredMapMatch() {
            return ProtosStandardMapProtocol.isStandardMatchImplementation(nativeBody);
        }

        @Override
        public boolean isStructuredImportCall() {
            return structured != null && structured.importRuntime != null;
        }

        @Override
        public boolean isStructuredEnsure() { return structured != null && structured.ensure; }

        @Override
        public boolean isStructuredErrorHandler() {
            return structured != null && structured.errorHandler;
        }

        @Override
        public boolean isStructuredWhile() { return structured != null && structured.whileLoop; }

        /**
         * PLAT044 B′ (PERF026-C1): true only when ordinary selection prepared
         * the canonical standard whileTrue capability and its receiver and
         * sole supplied value are exactly the send site's staged {@code
         * condition} and {@code body} literal values, and neither literal
         * would fail the per-child B′ proof for a reason already known now:
         * an owned return home (no captured one) or a required Context-local
         * plan projection. A pure read of the already prepared call and the
         * literal values: no activation is prepared and nothing is validated,
         * so condition/body preparation timing is unchanged, and any other
         * shape keeps the structured-dispatch while. A per-child proof that
         * still fails later (for example a foreign-Context plan) keeps that
         * child's exact physical invocation.
         */
        @Override
        public boolean admitsInlineLiteralWhile(Object condition, Object body) {
            return isStructuredWhile()
                    && activation.receiver() == condition
                    && supplied.size() == 1
                    && supplied.get(0) == body
                    && isInlineLiteralCallbackValue(condition)
                    && isInlineLiteralCallbackValue(body);
        }

        /**
         * PLAT044 B′ (PERF026-D1): true only when ordinary selection prepared
         * the canonical standard Array.each or Bytes.each capability, its sole
         * supplied value is exactly the send site's staged one-parameter
         * {@code callback} literal value, and that literal would not fail the
         * per-element B′ proof for a reason already known now (as in {@link
         * #admitsInlineLiteralWhile}). A pure read: the receiver and callback
         * are not validated here and no snapshot or activation is created, so
         * {@code PreparedArrayEachCall}/{@code PreparedBytesEachCall} keep sole
         * authority over validation order, the snapshot and every per-element
         * activation.
         */
        @Override
        public boolean admitsInlineLiteralIndexedEach(Object callback) {
            return (isStructuredArrayEach() || isStructuredBytesEach())
                    && supplied.size() == 1
                    && supplied.get(0) == callback
                    && isInlineLiteralCallbackValue(callback);
        }

        /**
         * PLAT044 B′ (PERF026-D2): the {@link #admitsInlineLiteralIndexedEach}
         * proof for the canonical standard Map.each or IdentityMap.each
         * capability and the send site's staged two-parameter {@code
         * callback} literal value. Equally a pure read: {@code
         * PreparedMapEachCall}/{@code PreparedIdentityMapEachCall} keep sole
         * authority over receiver and callability validation, the association
         * snapshot and every per-entry activation, and callback arity is not
         * examined here.
         */
        @Override
        public boolean admitsInlineLiteralAssociationEach(Object callback) {
            return (isStructuredMapEach() || isStructuredIdentityMapEach())
                    && supplied.size() == 1
                    && supplied.get(0) == callback
                    && isInlineLiteralCallbackValue(callback);
        }

        /**
         * PLAT044 B′ (PERF026-D3): the {@link #admitsInlineLiteralIndexedEach}
         * proof for the canonical standard Environment.each capability and the
         * send site's staged two-parameter {@code callback} literal value.
         * Equally a pure read: no callability check, portable conversion,
         * ordering or activation happens here, so {@code
         * PreparedEnvironmentEachCall} keeps sole authority over receiver and
         * callability validation, the complete portable canonically ordered
         * snapshot established before callback #1 and every per-entry
         * activation, and callback arity is not examined here.
         *
         * <p>The structured capability may have been classified by the
         * standard each implementation alone (the generic preparation path),
         * which a copy of that Closure at another home shares; admission
         * therefore re-proves the canonical home from this call's own
         * receiver and method home, so a copied each keeps its ordinary path.
         */
        @Override
        public boolean admitsInlineLiteralEnvironmentEach(Object callback) {
            return isStructuredEnvironmentEach()
                    && activation.methodHome()
                            .map(home ->
                                    ProtosStandardEnvironmentProtocol.isCanonicalStandardEachHome(
                                            activation.receiver(), home))
                            .orElse(false)
                    && supplied.size() == 1
                    && supplied.get(0) == callback
                    && isInlineLiteralCallbackValue(callback);
        }

        private static boolean isInlineLiteralCallbackValue(Object value) {
            return value instanceof ProtosClosureValue closure
                    && closure.returnHome().isPresent()
                    && !closure.requiresContextLocalExecutionProjectionForRuntime();
        }

        @Override
        public boolean isStructuredBoolean() {
            return structured != null && structured.booleanKind != null;
        }

        @Override
        public boolean isStructuredArrayEach() { return structured != null && structured.arrayEach; }

        @Override
        public boolean isStructuredArrayMatch() {
            return ProtosStandardArrayProtocol.isStandardMatchImplementation(nativeBody);
        }

        @Override
        public boolean isStructuredBytesEach() { return structured != null && structured.bytesEach; }

        @Override
        public boolean isStructuredEnvironmentEach() {
            return structured != null && structured.environmentEach;
        }

        @Override
        public boolean isStructuredIdentityMapAtIfAbsent() {
            return ProtosStandardIdentityMapProtocol.isStandardAtIfAbsentImplementation(nativeBody);
        }

        @Override
        public boolean isStructuredIdentityMapEach() {
            return structured != null && structured.identityMapEach;
        }

        @Override
        public boolean isStructuredMapEach() { return structured != null && structured.mapEach; }

        @Override
        public boolean isStructuredMapReadLookup() {
            return structured != null && structured.mapReadLookup != null;
        }

        @Override
        public boolean isStructuredMapAtPut() { return structured != null && structured.mapAtPut; }

        @Override
        public boolean isStructuredMapRemove() { return structured != null && structured.mapRemove; }

        /**
         * D188 projected foreign {@code each}, recognized by its private projection body like
         * Array.match: the body is reachable only through the foreign projection, and the cursor
         * itself re-validates the receiver.
         */
        @Override
        public boolean isStructuredForeignEach() {
            return ProtosForeignProjectedOperations.isEachImplementation(nativeBody);
        }

        @Override
        public boolean requiresStructuredDispatch() {
            if (structured != null
                    && (structured.objectCall
                            || structured.importRuntime != null
                            || structured.ensure
                            || structured.errorHandler
                            || structured.whileLoop
                            || structured.booleanKind != null
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

        @Override
        public PreparedMapMatchCall prepareStructuredMapMatch() {
            if (!isStructuredMapMatch()) {
                throw new IllegalStateException(
                        "prepared Closure call has no structured Map.match capability");
            }
            return new PreparedMapMatchCall(
                    activation.receiver(),
                    supplied,
                    activation);
        }

        @Override
        public PreparedCaseOfCall prepareStructuredCaseOf() {
            if (!isStructuredCaseOf()) {
                throw new IllegalStateException(
                        "prepared Closure call has no structured Object.caseOf capability");
            }
            return new PreparedCaseOfCall(
                    activation.receiver(),
                    supplied,
                    activation);
        }

        @Override
        public PreparedStandardObjectCall prepareStructuredObjectCall() {
            if (structured == null || !structured.objectCall) {
                throw new IllegalStateException(
                        "prepared Closure call has no PLAT032 Object.call capability");
            }

            Object receiver = activation.receiver();
            if (receiver instanceof ProtosClosureValue targetClosure) {
                return PreparedStandardObjectCall.closure(
                        prepareDirectClosureCall(
                                targetClosure,
                                supplied,
                                activation));
            }
            if (receiver instanceof ProtosObjectValue prototype
                    && !ProtosNumericValueSupport.isCurrentNumber(receiver)) {
                ProtosObjectValue instance = new ProtosObjectValue(prototype);
                return PreparedStandardObjectCall.construction(
                        prepareSend(
                                instance,
                                "init",
                                activation,
                                supplied),
                        instance);
            }
            throw ProtosCoreErrors.signal(
                    activation,
                    ProtosCoreErrors.newError(activation));
        }

        @Override
        public PreparedStandardImportCall prepareStructuredImportCall() {
            if (structured == null || structured.importRuntime == null) {
                throw new IllegalStateException(
                        "prepared Closure call has no PLAT032 import capability");
            }
            return new PreparedStandardImportCall(
                    PreparedClosureCall.moduleInitialization(
                            structured.importRuntime.prepareBytecodeImport(
                                    supplied,
                                    activation)));
        }

        @Override
        public PreparedEnsureCall prepareStructuredEnsure() {
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
                    prepareDirectClosureCall(body, List.of(), activation),
                    prepareDirectClosureCall(cleanup, List.of(), activation));
        }

        @Override
        public PreparedWhileCall prepareStructuredWhile() {
            if (structured == null || !structured.whileLoop) {
                throw new IllegalStateException(
                        "prepared Closure call has no structured while capability");
            }
            Object receiver = activation.receiver();
            if (!(receiver instanceof ProtosClosureValue condition)
                    || supplied.size() != 1
                    || !(supplied.get(0) instanceof ProtosClosureValue body)) {
                throw ProtosCoreErrors.signal(
                        activation,
                        ProtosCoreErrors.newError(activation));
            }
            return new PreparedWhileCall(
                    condition,
                    body,
                    activation,
                    ProtosStandardObjectProtocol.returnsLastBodyResult(nativeBody));
        }

        @Override
        public PreparedErrorHandlerCall prepareStructuredErrorHandler() {
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
                        prepareDirectClosureCall(body, List.of(), activation));
            } catch (RuntimeException | Error preparationFailure) {
                state.leaveFrame(frame);
                throw preparationFailure;
            }
        }

        @Override
        public PreparedBooleanCall prepareStructuredBoolean() {
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

        @Override
        public PreparedArrayEachCall prepareStructuredArrayEach() {
            if (structured == null || !structured.arrayEach) {
                throw new IllegalStateException(
                        "prepared Closure call has no structured Array.each capability");
            }
            return new PreparedArrayEachCall(
                    activation.receiver(),
                    supplied,
                    activation);
        }

        @Override
        public PreparedArrayMatchCall prepareStructuredArrayMatch() {
            if (!isStructuredArrayMatch()) {
                throw new IllegalStateException(
                        "prepared Closure call has no structured Array.match capability");
            }
            return new PreparedArrayMatchCall(
                    activation.receiver(),
                    supplied,
                    activation);
        }

        @Override
        public PreparedBytesEachCall prepareStructuredBytesEach() {
            if (structured == null || !structured.bytesEach) {
                throw new IllegalStateException(
                        "prepared Closure call has no structured Bytes.each capability");
            }
            return new PreparedBytesEachCall(
                    activation.receiver(),
                    supplied,
                    activation);
        }

        @Override
        public PreparedEnvironmentEachCall prepareStructuredEnvironmentEach() {
            if (structured == null || !structured.environmentEach) {
                throw new IllegalStateException(
                        "prepared Closure call has no structured Environment.each capability");
            }
            return new PreparedEnvironmentEachCall(
                    activation.receiver(),
                    supplied,
                    activation);
        }

        @Override
        public ProtosForeignEachCall prepareStructuredForeignEach() {
            if (!isStructuredForeignEach()) {
                throw new IllegalStateException(
                        "prepared Closure call has no structured foreign each capability");
            }
            return new ProtosForeignEachCall(activation.receiver(), supplied, activation);
        }

        @Override
        public PreparedIdentityMapAtIfAbsentCall prepareStructuredIdentityMapAtIfAbsent() {
            if (!isStructuredIdentityMapAtIfAbsent()) {
                throw new IllegalStateException(
                        "prepared Closure call has no structured IdentityMap.atIfAbsent capability");
            }
            return new PreparedIdentityMapAtIfAbsentCall(
                    activation.receiver(),
                    supplied,
                    activation);
        }

        @Override
        public PreparedIdentityMapEachCall prepareStructuredIdentityMapEach() {
            if (structured == null || !structured.identityMapEach) {
                throw new IllegalStateException(
                        "prepared Closure call has no structured IdentityMap.each capability");
            }
            return new PreparedIdentityMapEachCall(
                    activation.receiver(),
                    supplied,
                    activation);
        }

        @Override
        public PreparedMapEachCall prepareStructuredMapEach() {
            if (structured == null || !structured.mapEach) {
                throw new IllegalStateException(
                        "prepared Closure call has no structured Map.each capability");
            }
            return new PreparedMapEachCall(
                    activation.receiver(),
                    supplied,
                    activation);
        }

        @Override
        public PreparedMapReadLookupCall prepareStructuredMapReadLookup() {
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

        @Override
        public PreparedMapAtPutCall prepareStructuredMapAtPut() {
            if (structured == null || !structured.mapAtPut) {
                throw new IllegalStateException(
                        "prepared Closure call has no structured Map.atPut capability");
            }
            return new PreparedMapAtPutCall(
                    activation.receiver(),
                    supplied,
                    activation);
        }

        @Override
        public PreparedMapRemoveCall prepareStructuredMapRemove() {
            if (structured == null || !structured.mapRemove) {
                throw new IllegalStateException(
                        "prepared Closure call has no structured Map.remove capability");
            }
            return new PreparedMapRemoveCall(
                    activation.receiver(),
                    supplied,
                    activation);
        }

        @Override
        public Object enterNative() {
            if (requiresStructuredDispatch()) {
                throw new IllegalStateException(
                        "structured control native must execute through Bytecode control operations");
            }
            if ((activation.task().isPresent()
                            || activation.deferredCPrimeOperationForRuntime().isPresent()
                            || activation.deferredCPrimeReleaseForRuntime().isPresent()
                            || ProtosHostEntrySuspension.suspendible(activation))
                    && nativeBody
                            instanceof ProtosSuspensionCapableNativeClosureBody
                                    suspensionCapable) {
                return suspensionCapable
                        .executeForBytecodeContinuation(
                                activation,
                                supplied);
            }
            return nativeBody.execute(
                    activation,
                    supplied);
        }
    }

    /**
     * PERF027: a result already produced by an exact guarded standard operation.
     * This shape deliberately owns no semantic method Activation, ReturnHome,
     * native body, supplied List or source target. Argument evaluation and D013
     * selection have already happened before it is created; only the physically
     * specialized implementation of that proven selection has executed.
     */
    static final class ImmediateResultCall implements PreparedClosureCall {
        private final Object result;

        ImmediateResultCall(Object result) {
            this.result = java.util.Objects.requireNonNull(result, "result");
        }

        @Override
        public RootCallTarget bodyTarget() { return null; }

        @Override
        public ProtosActivation activation() {
            throw new IllegalStateException(
                    "immediate guarded operation has no rich Activation");
        }

        @Override
        public Object[] targetArguments() {
            throw new IllegalStateException(
                    "immediate guarded operation has no source target arguments");
        }

        @Override
        public ProtosTask taskForRuntime() { return null; }

        @Override
        public boolean isImmediate() { return true; }

        @Override
        public Object enterImmediate() { return result; }

        @Override
        public Object handleControlTransfer(ControlFlowException transfer) {
            throw transfer;
        }

        @Override
        public void failIfModuleInitialization() {
            // No module lifecycle belongs to this shape.
        }

        @Override
        public RuntimeException mapRuntimeFailure(RuntimeException failure) {
            return failure;
        }

        @Override
        public void complete() {
            // No ReturnHome or lifecycle state exists.
        }

        @Override
        public Object finish(Object result) { return result; }
    }

    /**
     * The special-call representation for a standard-import module
     * initialization. It carries only the {@link
     * ProtosModuleRuntime.PreparedModuleInitialization} lifecycle handle;
     * ordinary source calls never instantiate this class and it has no
     * return-home concept of its own.
     */
    static final class ModuleInitializationCall implements PreparedClosureCall {
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

        @Override
        public RootCallTarget bodyTarget() { return bodyTarget; }

        @Override
        public ProtosActivation activation() { return activation; }

        @Override
        public Object[] targetArguments() {
            if (targetArguments == null) {
                throw new IllegalStateException(
                        "prepared call has no source target arguments");
            }
            return targetArguments;
        }

        @Override
        public ProtosTask taskForRuntime() { return activation.task().orElse(null); }

        @Override
        public boolean isImmediate() { return moduleInitialization.isImmediate(); }

        @Override
        public Object enterImmediate() {
            if (!isImmediate()) {
                throw new IllegalStateException("prepared call is not an immediate module hit");
            }
            return moduleInitialization.immediateResult();
        }

        @Override
        public Object handleControlTransfer(ControlFlowException transfer) {
            moduleInitialization.fail();
            throw transfer;
        }

        @Override
        public void failIfModuleInitialization() { moduleInitialization.fail(); }

        @Override
        public RuntimeException mapRuntimeFailure(RuntimeException failure) {
            return moduleInitialization.mapUnexpectedHostFailure(failure);
        }

        @Override
        public void complete() {
            // Module-initialization lifecycle is finalized exclusively through finish()/fail().
        }

        @Override
        public Object finish(Object result) { return moduleInitialization.finish(result); }
    }


    static final class PreparedCaseOfCall {
        private final Object subject;
        private final List<java.util.Map.Entry<Object, Object>> cases;
        private final ProtosActivation activation;
        private int index;
        private Object selectedAction;
        private List<Object> selectedArguments;

        PreparedCaseOfCall(
                Object subject,
                List<?> supplied,
                ProtosActivation activation) {
            this.subject = java.util.Objects.requireNonNull(subject, "subject");
            this.activation =
                    java.util.Objects.requireNonNull(
                            activation,
                            "activation");

            if (supplied.size() != 1
                    || !(supplied.get(0) instanceof ProtosMapValue caseMap)) {
                throw ProtosCoreErrors.signal(
                        activation,
                        ProtosCoreErrors.newError(activation));
            }

            this.cases =
                    List.copyOf(
                            caseMap.associationSnapshot());
        }

        boolean needsMatcher() {
            return selectedAction == null && index < cases.size();
        }

        PreparedClosureCall prepareMatcher() {
            if (!needsMatcher()) {
                throw new IllegalStateException(
                        "Object.caseOf matcher requested after selection or exhaustion");
            }

            return prepareSend(
                    cases.get(index).getKey(),
                    "match",
                    activation,
                    List.of(subject));
        }

        void acceptMatcher(Object outcome) {
            if (!needsMatcher()) {
                throw new IllegalStateException(
                        "Object.caseOf matcher outcome accepted after selection or exhaustion");
            }

            if (outcome == ProtosBooleanValue.FALSE) {
                index++;
                return;
            }

            if (outcome == ProtosBooleanValue.TRUE) {
                selectedAction = cases.get(index).getValue();
                selectedArguments = List.of();
                return;
            }

            if (outcome instanceof ProtosArrayValue captures) {
                List<Object> snapshot = captures.indexedSnapshot();

                if (snapshot.isEmpty()) {
                    throw ProtosCoreErrors.signal(
                            activation,
                            ProtosCoreErrors.newError(activation));
                }

                selectedAction = cases.get(index).getValue();
                selectedArguments = List.copyOf(snapshot);
                return;
            }

            throw ProtosCoreErrors.signal(
                    activation,
                    ProtosCoreErrors.newError(activation));
        }

        PreparedClosureCall prepareSelectedAction() {
            if (selectedAction == null) {
                throw ProtosCoreErrors.signal(
                        activation,
                        ProtosCoreErrors.newError(activation));
            }

            return prepareClosureCall(
                    selectedAction,
                    selectedArguments,
                    activation);
        }
    }

    @Operation
    public static final class IsStructuredCaseOfCall {
        @Specialization
        public static boolean ordinary(OrdinarySourceCall prepared) {
            return prepared.isStructuredCaseOf();
        }

        @Specialization
        public static boolean nativeCall(NativeCall prepared) {
            return prepared.isStructuredCaseOf();
        }

        @Specialization
        public static boolean immediate(ImmediateResultCall prepared) {
            return prepared.isStructuredCaseOf();
        }

        @Specialization
        public static boolean moduleInitialization(ModuleInitializationCall prepared) {
            return prepared.isStructuredCaseOf();
        }
    }

    @Operation
    public static final class PrepareStructuredCaseOfCall {
        @Specialization
        public static PreparedCaseOfCall ordinary(OrdinarySourceCall prepared) {
            return prepared.prepareStructuredCaseOf();
        }

        @Specialization
        public static PreparedCaseOfCall nativeCall(NativeCall prepared) {
            return prepared.prepareStructuredCaseOf();
        }

        @Specialization
        public static PreparedCaseOfCall immediate(ImmediateResultCall prepared) {
            return prepared.prepareStructuredCaseOf();
        }

        @Specialization
        public static PreparedCaseOfCall moduleInitialization(ModuleInitializationCall prepared) {
            return prepared.prepareStructuredCaseOf();
        }
    }

    @Operation
    public static final class StructuredCaseOfNeedsMatcher {
        @Specialization
        public static boolean perform(PreparedCaseOfCall prepared) {
            return prepared.needsMatcher();
        }
    }

    @Operation
    public static final class PrepareStructuredCaseOfMatcherCall {
        @Specialization
        public static PreparedClosureCall perform(
                PreparedCaseOfCall prepared) {
            return prepared.prepareMatcher();
        }
    }

    @Operation
    public static final class AcceptStructuredCaseOfMatcherOutcome {
        @Specialization
        public static void perform(
                PreparedCaseOfCall prepared,
                Object outcome) {
            prepared.acceptMatcher(outcome);
        }
    }

    @Operation
    public static final class PrepareStructuredCaseOfActionCall {
        @Specialization
        public static PreparedClosureCall perform(
                PreparedCaseOfCall prepared) {
            return prepared.prepareSelectedAction();
        }
    }

    static final class PreparedStandardObjectCall {
        private final PreparedClosureCall child;
        private final ProtosObjectValue constructedInstance;

        private PreparedStandardObjectCall(
                PreparedClosureCall child,
                ProtosObjectValue constructedInstance) {
            this.child = java.util.Objects.requireNonNull(child, "child");
            this.constructedInstance = constructedInstance;
        }

        static PreparedStandardObjectCall closure(
                PreparedClosureCall child) {
            return new PreparedStandardObjectCall(child, null);
        }

        static PreparedStandardObjectCall construction(
                PreparedClosureCall child,
                ProtosObjectValue constructedInstance) {
            return new PreparedStandardObjectCall(
                    child,
                    java.util.Objects.requireNonNull(
                            constructedInstance,
                            "constructedInstance"));
        }

        PreparedClosureCall child() {
            return child;
        }

        Object finish(Object childResult) {
            return constructedInstance == null
                    ? childResult
                    : constructedInstance;
        }
    }

    static final class PreparedStandardImportCall {
        private final PreparedClosureCall child;

        PreparedStandardImportCall(
                PreparedClosureCall child) {
            this.child = java.util.Objects.requireNonNull(child, "child");
        }

        PreparedClosureCall child() {
            return child;
        }

        Object finish(Object childResult) {
            return childResult;
        }
    }

    static final class PreparedEnsureCall {
        private final PreparedClosureCall body;
        private final PreparedClosureCall cleanup;

        PreparedEnsureCall(
                PreparedClosureCall body,
                PreparedClosureCall cleanup) {
            this.body = java.util.Objects.requireNonNull(body, "body");
            this.cleanup = java.util.Objects.requireNonNull(cleanup, "cleanup");
        }

        PreparedClosureCall body() { return body; }
        PreparedClosureCall cleanup() { return cleanup; }
    }

    /**
     * Validated standard pre-test loop shared by {@code whileTrue} and {@code
     * while} (D201). The loop algorithm is identical; {@link #result} applies
     * the selector's normal-result policy once the final condition completed
     * normally with canonical {@code false}.
     */
    static final class PreparedWhileCall {
        private final ProtosClosureValue condition;
        private final ProtosClosureValue body;
        private final ProtosActivation activation;
        private final boolean returnsLastBody;

        PreparedWhileCall(
                ProtosClosureValue condition,
                ProtosClosureValue body,
                ProtosActivation activation,
                boolean returnsLastBody) {
            this.condition = java.util.Objects.requireNonNull(condition, "condition");
            this.body = java.util.Objects.requireNonNull(body, "body");
            this.activation = java.util.Objects.requireNonNull(activation, "activation");
            this.returnsLastBody = returnsLastBody;
        }

        /**
         * Normal loop result. {@code lastBodyResult} is the exact result of the
         * last normally completed body activation, or canonical {@code null}
         * when none ran; it is returned unchanged (no copy, conversion, or
         * Future await/adoption) for {@code whileTrue}. {@code while} returns
         * its final canonical {@code false}.
         */
        Object result(Object lastBodyResult) {
            return returnsLastBody ? lastBodyResult : ProtosBooleanValue.FALSE;
        }

        /**
         * Value kept in the loop's body-result local after a normal body
         * activation: the exact result for {@code whileTrue}, canonical
         * {@code null} for {@code while}, so {@code while} never keeps a body
         * object reachable across its next (possibly suspending) condition.
         */
        Object retainedBodyResult(Object bodyResult) {
            return returnsLastBody ? bodyResult : ProtosNullValue.INSTANCE;
        }

        PreparedClosureCall prepareCondition() {
            return prepareDirectClosureCall(condition, List.of(), activation);
        }

        PreparedClosureCall prepareBody() {
            return prepareDirectClosureCall(body, List.of(), activation);
        }

        PreparedInlineLiteralCall prepareInlineCondition() {
            return prepareInlineLiteralCall(condition, List.of(), activation);
        }

        PreparedInlineLiteralCall prepareInlineBody() {
            return prepareInlineLiteralCall(body, List.of(), activation);
        }

        boolean conditionResult(Object result) {
            if (result == ProtosBooleanValue.TRUE) {
                return true;
            }
            if (result == ProtosBooleanValue.FALSE) {
                return false;
            }
            throw ProtosCoreErrors.signal(
                    activation,
                    ProtosCoreErrors.newError(activation));
        }

        /**
         * PLAT044 B′ (PERF026-C1) inline eligibility of the freshly prepared
         * condition invocation. The condition is exactly the staged literal
         * and the invocation has already performed authoritative ordinary
         * {@code call} selection, but has not materialized a rich activation
         * or physical prepared source call merely to prove inline execution.
         */
        boolean admitsInlineLiteralCondition(
                PreparedInlineLiteralCall child,
                Object literal,
                ProtosClosureExecutionPlanCell literalPlan) {
            return condition == literal
                    && child.admits(literalPlan);
        }

        /** As {@link #admitsInlineLiteralCondition}, for a fresh body invocation. */
        boolean admitsInlineLiteralBody(
                PreparedInlineLiteralCall child,
                Object literal,
                ProtosClosureExecutionPlanCell literalPlan) {
            return body == literal
                    && child.admits(literalPlan);
        }
    }

    /**
     * PERF025 / PLAT044 B′ pay-as-you-grow callback invocation.
     *
     * <p>For the exact canonical ordinary {@code Closure.call} selection of a
     * source-backed Closure, this carrier keeps only the effective Context-owned
     * target and the compact direct-Closure frame arguments. No
     * {@link PreparedClosureCall} and no rich {@link ProtosActivation} are
     * constructed on the admitted inline path. If PLAT044 admission later
     * fails, {@link #fallbackCall()} materializes the ordinary compact physical
     * source call without repeating lookup. A non-canonical selection keeps the
     * already prepared ordinary fallback, preserving override/alias semantics.
     */
    static final class PreparedInlineLiteralCall {
        private final RootCallTarget bodyTarget;
        private final Object[] compactTargetArguments;
        private final PreparedClosureCall fallback;
        private final boolean ownsReturnHome;

        /*
         * One-way: set when this invocation's frame-native bindings moved to
         * the durable authority installed on its activation, before that
         * activation reached any consumer (ProtosInlineCallbackFrameBindings).
         */
        private boolean frameBindingsTransferred;

        private PreparedInlineLiteralCall(
                RootCallTarget bodyTarget,
                Object[] compactTargetArguments,
                PreparedClosureCall fallback,
                boolean ownsReturnHome) {
            this.bodyTarget = bodyTarget;
            this.compactTargetArguments = compactTargetArguments;
            this.fallback = fallback;
            this.ownsReturnHome = ownsReturnHome;
        }

        static PreparedInlineLiteralCall direct(
                RootCallTarget bodyTarget,
                Object[] compactTargetArguments) {
            java.util.Objects.requireNonNull(bodyTarget, "bodyTarget");
            java.util.Objects.requireNonNull(
                    compactTargetArguments, "compactTargetArguments");
            return new PreparedInlineLiteralCall(
                    bodyTarget,
                    compactTargetArguments,
                    null,
                    ProtosFrameArguments.compactOwnsReturnHome(
                            compactTargetArguments));
        }

        static PreparedInlineLiteralCall fallback(PreparedClosureCall fallback) {
            return new PreparedInlineLiteralCall(
                    null,
                    null,
                    java.util.Objects.requireNonNull(fallback, "fallback"),
                    false);
        }

        boolean admits(ProtosClosureExecutionPlanCell literalPlan) {
            return fallback == null
                    && !ownsReturnHome
                    && bodyTarget
                            == literalPlan.plan().bytecodeActivationTargetForComposition();
        }

        boolean requiresStructuredDispatch() {
            return fallback != null && fallback.requiresStructuredDispatch();
        }

        PreparedClosureCall fallbackCall() {
            if (fallback != null) {
                return fallback;
            }
            return PreparedClosureCall.ordinaryCompact(
                    bodyTarget,
                    compactTargetArguments);
        }

        /**
         * The invocation's one fresh semantic activation, materialized from the
         * compact arguments by the first semantic or tooling observer and
         * published back into them, so every later observer of this same
         * invocation (guest operations, debugger, resumption after a yield)
         * receives exactly the same instance. A frame-native callback region
         * never hands this out directly: it goes through {@code
         * ProtosInlineCallbackFrameBindings}, which first makes the
         * invocation's bindings durable.
         */
        ProtosActivation activation() {
            if (compactTargetArguments == null) {
                throw new IllegalStateException(
                        "non-direct inline callback invocation has no compact activation");
            }
            return ProtosFrameArguments.activation(compactTargetArguments);
        }

        /*
         * PERF025 lazy inline callback activation. The admitted inline region
         * runs only a direct carrier, so compactTargetArguments is non-null on
         * every path below.
         */

        /** True once {@link #activation()} has been materialized. */
        boolean isActivationMaterialized() {
            return !ProtosFrameArguments.isUnmaterializedCompactCall(
                    compactTargetArguments);
        }

        /** Supplied positional argument count, without materializing. */
        int suppliedArgumentCount() {
            return ProtosFrameArguments.compactSuppliedArgumentCount(
                    compactTargetArguments);
        }

        /** Supplied positional argument {@code index}, without materializing. */
        Object suppliedArgument(int index) {
            return ProtosFrameArguments.compactSuppliedArgument(
                    compactTargetArguments, index);
        }

        /*
         * PERF025 slice 3 projections. Each answers exactly what the
         * invocation's activation would, without materializing it.
         */

        /**
         * The invocation's prelude: the materialized activation's, otherwise
         * the one {@link #activation()} would install.
         */
        ProtosPrelude prelude() {
            if (isActivationMaterialized()) {
                return activation().preludeOrNullForRuntime();
            }
            ProtosClosureValue closure =
                    ProtosFrameArguments.compactClosure(compactTargetArguments);
            return closure.prelude()
                    .orElse(ProtosFrameArguments.compactCaller(compactTargetArguments)
                            .preludeOrNullForRuntime());
        }

        /**
         * A caller that is provenance-equivalent to this invocation's
         * activation for preparing a child invocation or selecting through
         * this invocation's prelude: the activation itself once materialized,
         * otherwise the compact caller when the callee inherits every
         * provenance field from it ({@link
         * ProtosFrameArguments#compactInheritedProvenanceCaller}), else
         * {@code null}.
         */
        ProtosActivation provenanceCallerOrNull() {
            if (isActivationMaterialized()) {
                return activation();
            }
            return ProtosFrameArguments.compactInheritedProvenanceCaller(
                    compactTargetArguments);
        }

        /** The receiver of a direct Closure invocation: the captured receiver. */
        Object unmaterializedReceiver() {
            return ProtosFrameArguments.compactClosure(compactTargetArguments)
                    .capturedReceiver();
        }

        /** The captured lexical environment the activation would adopt. */
        ProtosLexicalEnvironment unmaterializedCapturedLexicalEnvironment() {
            return ProtosFrameArguments.compactClosure(compactTargetArguments)
                    .capturedLexicalEnvironmentForRuntime();
        }

        boolean frameBindingsTransferred() {
            return frameBindingsTransferred;
        }

        void markFrameBindingsTransferred() {
            frameBindingsTransferred = true;
        }
    }

    static final class PreparedErrorHandlerCall {
        private final ProtosDynamicControlState state;
        private final ProtosDynamicControlState.Frame frame;
        private final ProtosClosureValue handler;
        private final ProtosActivation activation;
        private final PreparedClosureCall body;
        private boolean left;

        PreparedErrorHandlerCall(
                ProtosDynamicControlState state,
                ProtosDynamicControlState.Frame frame,
                ProtosClosureValue handler,
                ProtosActivation activation,
                PreparedClosureCall body) {
            this.state = java.util.Objects.requireNonNull(state, "state");
            this.frame = java.util.Objects.requireNonNull(frame, "frame");
            this.handler = java.util.Objects.requireNonNull(handler, "handler");
            this.activation = java.util.Objects.requireNonNull(activation, "activation");
            this.body = java.util.Objects.requireNonNull(body, "body");
        }

        PreparedClosureCall body() { return body; }

        PreparedClosureCall selectHandler(AbstractTruffleException exception) {
            if (exception instanceof ProtosSignalException transfer
                    && transfer.selectedHandlerFrame().orElse(null) == frame) {
                leave();
                return prepareDirectClosureCall(
                        handler,
                        List.of(transfer.error()),
                        activation);
            }
            leave();
            throw exception;
        }

        void leave() {
            if (left) {
                return;
            }
            state.leaveFrame(frame);
            left = true;
        }
    }


    static final class PreparedBooleanCall {
        private final ProtosStandardBooleanProtocol.StructuredCallbackKind kind;
        private final Object receiver;
        private final List<?> supplied;
        private final ProtosActivation activation;

        PreparedBooleanCall(
                ProtosStandardBooleanProtocol.StructuredCallbackKind kind,
                Object receiver,
                List<?> supplied,
                ProtosActivation activation) {
            this.kind = java.util.Objects.requireNonNull(kind, "kind");
            this.receiver = java.util.Objects.requireNonNull(receiver, "receiver");
            // TEST009-AL: supplied is NativeCall's already-immutable List.copyOf result (the sole
            // construction site); re-copying it only re-enters List.copyOf's erased fallback in PE.
            this.supplied = java.util.Objects.requireNonNull(supplied, "supplied");
            this.activation = java.util.Objects.requireNonNull(activation, "activation");
            if (receiver != ProtosBooleanValue.TRUE
                    && receiver != ProtosBooleanValue.FALSE) {
                throw ProtosCoreErrors.signal(
                        activation,
                        ProtosCoreErrors.newError(activation));
            }
            if (this.supplied.size() != kind.arity()) {
                throw ProtosCoreErrors.signal(
                        activation,
                        ProtosCoreErrors.newError(activation));
            }
        }

        boolean hasCallback() {
            return switch (kind) {
                case IF_TRUE -> receiver == ProtosBooleanValue.TRUE;
                case IF_FALSE -> receiver == ProtosBooleanValue.FALSE;
                case IF_TRUE_IF_FALSE -> true;
                case AND -> receiver == ProtosBooleanValue.TRUE;
                case OR -> receiver == ProtosBooleanValue.FALSE;
                case IF -> receiver == ProtosBooleanValue.TRUE;
                case ELSE -> receiver == ProtosBooleanValue.FALSE;
            };
        }

        PreparedClosureCall prepareCallback() {
            if (!hasCallback()) {
                throw new IllegalStateException(
                        "short-circuited Boolean operation has no selected callback");
            }
            return prepareClosureCall(
                    supplied.get(selectedIndex()),
                    List.of(),
                    activation);
        }

        PreparedInlineLiteralCall prepareInlineCallback() {
            if (!hasCallback()) {
                throw new IllegalStateException(
                        "short-circuited Boolean operation has no selected callback");
            }
            return prepareInlineLiteralCall(
                    supplied.get(selectedIndex()),
                    List.of(),
                    activation);
        }

        /**
         * Supplied position of the selected callback; meaningful only when
         * {@link #hasCallback} holds. Shared by {@link #prepareCallback} and
         * {@link #admitsInlineLiteralCallback} so inline admission can never
         * disagree with ordinary selection.
         */
        private int selectedIndex() {
            return switch (kind) {
                case IF_TRUE, IF_FALSE, AND, OR, IF, ELSE -> 0;
                case IF_TRUE_IF_FALSE ->
                        receiver == ProtosBooleanValue.TRUE ? 0 : 1;
            };
        }

        Object immediateResult() {
            if (hasCallback()) {
                throw new IllegalStateException(
                        "selected Boolean callback has no immediate result");
            }
            return switch (kind) {
                case IF_TRUE, IF_FALSE -> ProtosNullValue.INSTANCE;
                case AND -> ProtosBooleanValue.FALSE;
                case OR -> ProtosBooleanValue.TRUE;
                case IF, ELSE -> receiver;
                case IF_TRUE_IF_FALSE ->
                        throw new IllegalStateException(
                                "ifTrueIfFalse always selects one callback");
            };
        }

        Object finishCallback(Object result) {
            return switch (kind) {
                case AND, OR ->
                        ProtosStandardBooleanProtocol.requireBooleanResult(
                                result,
                                activation);
                case IF_TRUE, IF_FALSE, IF_TRUE_IF_FALSE -> result;
                // D200: the callback's normal result is ignored; the receiver is returned.
                case IF, ELSE -> receiver;
            };
        }

        /**
         * PLAT044 B′ (PERF026-B1/B2/B3) inline eligibility of an already
         * prepared selected callback {@code child}.
         *
         * <p>True only when this standard capability selected a callback, the
         * selected supplied position ({@link #selectedIndex}) is exactly the
         * send site's candidate {@code position}, the value supplied there is
         * exactly {@code literal} (the immediate Closure literal value staged
         * by the send site), and {@code child} is that literal's exact
         * inline-equivalent invocation ({@link #isInlineLiteralInvocation}).
         * For IF_TRUE_IF_FALSE both callbacks are eagerly evaluated and
         * staged, but only the selected position can be admitted, so the
         * unselected literal is never entered. Any other shape (including a
         * different selected value or position) keeps the exact physical
         * callback invocation. The inline result still completes through
         * {@link #finishCallback}, so AND/OR Boolean-result validation is
         * unchanged.
         */
        boolean admitsInlineLiteralCallback(
                PreparedInlineLiteralCall child,
                Object literal,
                ProtosClosureExecutionPlanCell literalPlan,
                int position) {
            return hasCallback()
                    && selectedIndex() == position
                    && supplied.get(position) == literal
                    && child.admits(literalPlan);
        }
    }

    @Operation
    public static final class IsStructuredObjectCall {
        @Specialization
        public static boolean ordinary(OrdinarySourceCall prepared) {
            return prepared.isStructuredObjectCall();
        }

        @Specialization
        public static boolean nativeCall(NativeCall prepared) {
            return prepared.isStructuredObjectCall();
        }

        @Specialization
        public static boolean immediate(ImmediateResultCall prepared) {
            return prepared.isStructuredObjectCall();
        }

        @Specialization
        public static boolean moduleInitialization(ModuleInitializationCall prepared) {
            return prepared.isStructuredObjectCall();
        }
    }

    @Operation
    public static final class PrepareStructuredObjectCall {
        @Specialization
        public static PreparedStandardObjectCall ordinary(OrdinarySourceCall prepared) {
            return prepared.prepareStructuredObjectCall();
        }

        @Specialization
        public static PreparedStandardObjectCall nativeCall(NativeCall prepared) {
            return prepared.prepareStructuredObjectCall();
        }

        @Specialization
        public static PreparedStandardObjectCall immediate(ImmediateResultCall prepared) {
            return prepared.prepareStructuredObjectCall();
        }

        @Specialization
        public static PreparedStandardObjectCall moduleInitialization(
                ModuleInitializationCall prepared) {
            return prepared.prepareStructuredObjectCall();
        }
    }

    @Operation
    public static final class LoadStructuredObjectCallChild {
        @Specialization
        public static PreparedClosureCall perform(
                PreparedStandardObjectCall prepared) {
            return prepared.child();
        }
    }

    @Operation
    public static final class FinishStructuredObjectCall {
        @Specialization
        public static Object perform(
                PreparedStandardObjectCall prepared,
                Object childResult) {
            return prepared.finish(childResult);
        }
    }

    @Operation
    public static final class IsStructuredImportCall {
        @Specialization
        public static boolean ordinary(OrdinarySourceCall prepared) {
            return prepared.isStructuredImportCall();
        }

        @Specialization
        public static boolean nativeCall(NativeCall prepared) {
            return prepared.isStructuredImportCall();
        }

        @Specialization
        public static boolean immediate(ImmediateResultCall prepared) {
            return prepared.isStructuredImportCall();
        }

        @Specialization
        public static boolean moduleInitialization(ModuleInitializationCall prepared) {
            return prepared.isStructuredImportCall();
        }
    }

    @Operation
    public static final class PrepareStructuredImportCall {
        @Specialization
        public static PreparedStandardImportCall ordinary(OrdinarySourceCall prepared) {
            return prepared.prepareStructuredImportCall();
        }

        @Specialization
        public static PreparedStandardImportCall nativeCall(NativeCall prepared) {
            return prepared.prepareStructuredImportCall();
        }

        @Specialization
        public static PreparedStandardImportCall immediate(ImmediateResultCall prepared) {
            return prepared.prepareStructuredImportCall();
        }

        @Specialization
        public static PreparedStandardImportCall moduleInitialization(
                ModuleInitializationCall prepared) {
            return prepared.prepareStructuredImportCall();
        }
    }

    @Operation
    public static final class LoadStructuredImportCallChild {
        @Specialization
        public static PreparedClosureCall perform(
                PreparedStandardImportCall prepared) {
            return prepared.child();
        }
    }

    @Operation
    public static final class FinishStructuredImportCall {
        @Specialization
        public static Object perform(
                PreparedStandardImportCall prepared,
                Object childResult) {
            return prepared.finish(childResult);
        }
    }

    /**
     * TEST009-J: structured-dispatch selection and preparation specialize on
     * each concrete {@link PreparedClosureCall} representation, as the
     * terminal lifecycle and entry operations do, so partial evaluation binds
     * the capability query statically. Only {@link NativeCall} owns structured
     * capabilities; the other representations keep the interface's absent
     * answer (and its {@link IllegalStateException} for preparation). The same
     * shape applies to every {@code IsStructured*} and {@code PrepareStructured*}
     * operation whose operand is the prepared call itself.
     */
    @Operation
    public static final class RequiresStructuredDispatch {
        @Specialization
        public static boolean ordinary(OrdinarySourceCall prepared) {
            return prepared.requiresStructuredDispatch();
        }

        @Specialization
        public static boolean nativeCall(NativeCall prepared) {
            return prepared.requiresStructuredDispatch();
        }

        @Specialization
        public static boolean immediate(ImmediateResultCall prepared) {
            return prepared.requiresStructuredDispatch();
        }

        @Specialization
        public static boolean moduleInitialization(ModuleInitializationCall prepared) {
            return prepared.requiresStructuredDispatch();
        }
    }

    /**
     * TEST009-J: reached only after {@link RequiresStructuredDispatch}, which
     * only a {@link NativeCall} carrying a structured capability answers
     * {@code true}; both lowerers emit this operation solely inside that
     * guard, so it specializes on that concrete representation.
     */
    @Operation
    public static final class EnterNestedStructuredDispatch {
        @Specialization
        public static Object perform(
                NativeCall prepared,
                @Cached IndirectCallNode node) {
            RootCallTarget target =
                    ProtosTaskCPrimeEntryExecution
                            .planForEnteredContext()
                            .target();
            try {
                return node.call(
                        target,
                        prepared.activation(),
                        prepared);
            } catch (ProtosBytecodeControlTransferException bridged) {
                return prepared.handleControlTransfer(bridged.transfer());
            } catch (AbstractTruffleException transfer) {
                prepared.failIfModuleInitialization();
                throw transfer;
            } catch (RuntimeException failure) {
                throw prepared.mapRuntimeFailure(failure);
            }
        }
    }

    @Operation
    public static final class IsStructuredEnsureCall {
        @Specialization
        public static boolean ordinary(OrdinarySourceCall prepared) {
            return prepared.isStructuredEnsure();
        }

        @Specialization
        public static boolean nativeCall(NativeCall prepared) {
            return prepared.isStructuredEnsure();
        }

        @Specialization
        public static boolean immediate(ImmediateResultCall prepared) {
            return prepared.isStructuredEnsure();
        }

        @Specialization
        public static boolean moduleInitialization(ModuleInitializationCall prepared) {
            return prepared.isStructuredEnsure();
        }
    }

    @Operation
    public static final class PrepareStructuredEnsureCall {
        @Specialization
        public static PreparedEnsureCall ordinary(OrdinarySourceCall prepared) {
            return prepared.prepareStructuredEnsure();
        }

        @Specialization
        public static PreparedEnsureCall nativeCall(NativeCall prepared) {
            return prepared.prepareStructuredEnsure();
        }

        @Specialization
        public static PreparedEnsureCall immediate(ImmediateResultCall prepared) {
            return prepared.prepareStructuredEnsure();
        }

        @Specialization
        public static PreparedEnsureCall moduleInitialization(ModuleInitializationCall prepared) {
            return prepared.prepareStructuredEnsure();
        }
    }

    @Operation
    public static final class LoadStructuredEnsureBodyCall {
        @Specialization
        public static PreparedClosureCall perform(PreparedEnsureCall prepared) {
            return prepared.body();
        }
    }

    @Operation
    public static final class LoadStructuredEnsureCleanupCall {
        @Specialization
        public static PreparedClosureCall perform(PreparedEnsureCall prepared) {
            return prepared.cleanup();
        }
    }

    @Operation
    public static final class IsStructuredWhileCall {
        @Specialization
        public static boolean ordinary(OrdinarySourceCall prepared) {
            return prepared.isStructuredWhile();
        }

        @Specialization
        public static boolean nativeCall(NativeCall prepared) {
            return prepared.isStructuredWhile();
        }

        @Specialization
        public static boolean immediate(ImmediateResultCall prepared) {
            return prepared.isStructuredWhile();
        }

        @Specialization
        public static boolean moduleInitialization(ModuleInitializationCall prepared) {
            return prepared.isStructuredWhile();
        }
    }

    @Operation
    public static final class PrepareStructuredWhileCall {
        @Specialization
        public static PreparedWhileCall ordinary(OrdinarySourceCall prepared) {
            return prepared.prepareStructuredWhile();
        }

        @Specialization
        public static PreparedWhileCall nativeCall(NativeCall prepared) {
            return prepared.prepareStructuredWhile();
        }

        @Specialization
        public static PreparedWhileCall immediate(ImmediateResultCall prepared) {
            return prepared.prepareStructuredWhile();
        }

        @Specialization
        public static PreparedWhileCall moduleInitialization(ModuleInitializationCall prepared) {
            return prepared.prepareStructuredWhile();
        }
    }

    @Operation
    public static final class PrepareStructuredWhileConditionCall {
        @Specialization
        public static PreparedClosureCall perform(PreparedWhileCall prepared) {
            return prepared.prepareCondition();
        }
    }

    @Operation
    public static final class PrepareStructuredWhileBodyCall {
        @Specialization
        public static PreparedClosureCall perform(PreparedWhileCall prepared) {
            return prepared.prepareBody();
        }
    }

    @Operation
    public static final class StructuredWhileCondition {
        @Specialization
        public static boolean perform(PreparedWhileCall prepared, Object result) {
            return prepared.conditionResult(result);
        }
    }

    @Operation
    public static final class StructuredWhileRetainBodyResult {
        @Specialization
        public static Object perform(PreparedWhileCall prepared, Object bodyResult) {
            return prepared.retainedBodyResult(bodyResult);
        }
    }

    @Operation
    public static final class StructuredWhileResult {
        @Specialization
        public static Object perform(PreparedWhileCall prepared, Object lastBodyResult) {
            return prepared.result(lastBodyResult);
        }
    }

    /**
     * PLAT044 B′ common loop state of a standard each whose callback a send
     * site may sequence locally (PERF026-D1 indexed, PERF026-D2 association,
     * PERF026-D3 Environment): validated receiver and callback, one snapshot
     * established before the first callback, one fresh
     * callback invocation per snapshot position carrying exactly that
     * position's arguments, advance only after that invocation completes
     * normally, and the original receiver as result. It lets a send site
     * sequence any such loop without duplicating its inline callback region;
     * each implementation keeps its own validation and snapshot authority.
     */
    sealed interface PreparedLocalEachCall
            permits PreparedIndexedEachCall,
                    PreparedAssociationEachCall,
                    PreparedEnvironmentEachCall {
        boolean hasNext();

        PreparedClosureCall prepareCurrent();

        PreparedInlineLiteralCall prepareInlineCurrent();

        void advance();

        Object finish();

        /** The validated callback value supplied to this each invocation. */
        Object callback();

        /**
         * Inline eligibility of an already prepared per-position {@code
         * child}: the callback is exactly the staged {@code literal} and
         * {@code child} is its exact inline-equivalent invocation ({@link
         * #isInlineLiteralInvocation}), whose activation already carries the
         * snapshot arguments the inline region binds.
         */
        default boolean admitsInlineLiteralChild(
                PreparedInlineLiteralCall child,
                Object literal,
                ProtosClosureExecutionPlanCell literalPlan) {
            return callback() == literal
                    && child.admits(literalPlan);
        }
    }

    /**
     * PLAT044 B′ (PERF026-D1) standard Array.each and Bytes.each loops, whose
     * observable shapes coincide: an ascending indexed snapshot and one
     * one-argument callback invocation per snapshot element.
     */
    sealed interface PreparedIndexedEachCall extends PreparedLocalEachCall
            permits PreparedArrayEachCall, PreparedBytesEachCall {}

    /**
     * PLAT044 B′ (PERF026-D2) standard Map.each and IdentityMap.each loops,
     * whose observable shapes coincide: an insertion-ordered association
     * snapshot of representative keys and their values, and one
     * {@code block(key, value)} invocation per snapshot association. The two
     * receiver families stay distinct; only the loop view is shared.
     */
    sealed interface PreparedAssociationEachCall extends PreparedLocalEachCall
            permits PreparedMapEachCall, PreparedIdentityMapEachCall {}

    static final class PreparedArrayEachCall implements PreparedIndexedEachCall {
        private final ProtosArrayValue array;
        private final List<Object> snapshot;
        private final Object block;
        private final ProtosActivation activation;
        private int index;

        PreparedArrayEachCall(
                Object receiver,
                List<?> supplied,
                ProtosActivation activation) {
            this.activation = java.util.Objects.requireNonNull(activation, "activation");
            if (!(receiver instanceof ProtosArrayValue value)
                    || supplied.size() != 1) {
                throw ProtosCoreErrors.signal(
                        activation,
                        ProtosCoreErrors.newError(activation));
            }
            this.array = value;
            this.block = supplied.get(0);
            ProtosStandardArrayProtocol.requireInvokableForStructured(
                    block,
                    activation);
            this.snapshot = value.indexedSnapshot();
        }

        @Override
        public boolean hasNext() {
            return index < snapshot.size();
        }

        @Override
        public PreparedClosureCall prepareCurrent() {
            if (!hasNext()) {
                throw new IllegalStateException(
                        "Array.each callback requested after snapshot exhaustion");
            }
            return prepareClosureCall(
                    block,
                    List.of(snapshot.get(index)),
                    activation);
        }

        @Override
        public PreparedInlineLiteralCall prepareInlineCurrent() {
            if (!hasNext()) {
                throw new IllegalStateException(
                        "Array.each callback requested after snapshot exhaustion");
            }
            return prepareInlineLiteralCall(
                    block,
                    List.of(snapshot.get(index)),
                    activation);
        }

        @Override
        public void advance() {
            if (!hasNext()) {
                throw new IllegalStateException(
                        "Array.each cursor advanced after snapshot exhaustion");
            }
            index++;
        }

        @Override
        public Object finish() {
            if (hasNext()) {
                throw new IllegalStateException(
                        "Array.each finished before snapshot exhaustion");
            }
            return array;
        }

        @Override
        public Object callback() {
            return block;
        }
    }

    @Operation
    public static final class IsStructuredArrayEachCall {
        @Specialization
        public static boolean ordinary(OrdinarySourceCall prepared) {
            return prepared.isStructuredArrayEach();
        }

        @Specialization
        public static boolean nativeCall(NativeCall prepared) {
            return prepared.isStructuredArrayEach();
        }

        @Specialization
        public static boolean immediate(ImmediateResultCall prepared) {
            return prepared.isStructuredArrayEach();
        }

        @Specialization
        public static boolean moduleInitialization(ModuleInitializationCall prepared) {
            return prepared.isStructuredArrayEach();
        }
    }

    @Operation
    public static final class PrepareStructuredArrayEachCall {
        @Specialization
        public static PreparedArrayEachCall ordinary(OrdinarySourceCall prepared) {
            return prepared.prepareStructuredArrayEach();
        }

        @Specialization
        public static PreparedArrayEachCall nativeCall(NativeCall prepared) {
            return prepared.prepareStructuredArrayEach();
        }

        @Specialization
        public static PreparedArrayEachCall immediate(ImmediateResultCall prepared) {
            return prepared.prepareStructuredArrayEach();
        }

        @Specialization
        public static PreparedArrayEachCall moduleInitialization(
                ModuleInitializationCall prepared) {
            return prepared.prepareStructuredArrayEach();
        }
    }

    @Operation
    public static final class StructuredArrayEachHasNext {
        @Specialization
        public static boolean perform(PreparedArrayEachCall prepared) {
            return prepared.hasNext();
        }
    }

    @Operation
    public static final class PrepareStructuredArrayEachElementCall {
        @Specialization
        public static PreparedClosureCall perform(PreparedArrayEachCall prepared) {
            return prepared.prepareCurrent();
        }
    }

    @Operation
    public static final class AdvanceStructuredArrayEach {
        @Specialization
        public static void perform(PreparedArrayEachCall prepared) {
            prepared.advance();
        }
    }

    @Operation
    public static final class FinishStructuredArrayEach {
        @Specialization
        public static Object perform(PreparedArrayEachCall prepared) {
            return prepared.finish();
        }
    }


    static final class PreparedArrayMatchCall {
        private final ProtosActivation activation;
        private final List<Object> matcherSnapshot;
        private final List<Object> subjectSnapshot;
        private final List<Object> captures = new ArrayList<>();
        private int index;
        private boolean mismatch;

        PreparedArrayMatchCall(
                Object receiver,
                List<?> supplied,
                ProtosActivation activation) {
            this.activation =
                    java.util.Objects.requireNonNull(
                            activation,
                            "activation");

            if (!(receiver instanceof ProtosArrayValue matcher)
                    || supplied.size() != 1) {
                throw ProtosCoreErrors.signal(
                        activation,
                        ProtosCoreErrors.newError(activation));
            }

            Object subjectValue = supplied.get(0);
            if (!(subjectValue instanceof ProtosArrayValue subject)) {
                matcherSnapshot = List.of();
                subjectSnapshot = List.of();
                mismatch = true;
                return;
            }

            matcherSnapshot = matcher.indexedSnapshot();
            subjectSnapshot = subject.indexedSnapshot();

            if (matcherSnapshot.size() != subjectSnapshot.size()) {
                mismatch = true;
            }
        }

        boolean hasNext() {
            return !mismatch && index < matcherSnapshot.size();
        }

        PreparedClosureCall prepareCurrent() {
            if (!hasNext()) {
                throw new IllegalStateException(
                        "Array.match child requested after terminal outcome");
            }

            return prepareSend(
                    matcherSnapshot.get(index),
                    "match",
                    activation,
                    List.of(subjectSnapshot.get(index)));
        }

        void acceptCurrent(Object outcome) {
            if (!hasNext()) {
                throw new IllegalStateException(
                        "Array.match child outcome accepted after terminal outcome");
            }

            if (outcome == ProtosBooleanValue.FALSE) {
                mismatch = true;
                return;
            }

            if (outcome == ProtosBooleanValue.TRUE) {
                index++;
                return;
            }

            if (outcome instanceof ProtosArrayValue childCaptures) {
                List<Object> observedCaptures =
                        childCaptures.indexedSnapshot();

                if (observedCaptures.isEmpty()) {
                    throw ProtosCoreErrors.signal(
                            activation,
                            ProtosCoreErrors.newError(activation));
                }

                for (int captureIndex = 0;
                        captureIndex < observedCaptures.size();
                        captureIndex++) {
                    captures.add(observedCaptures.get(captureIndex));
                }
                index++;
                return;
            }

            throw ProtosCoreErrors.signal(
                    activation,
                    ProtosCoreErrors.newError(activation));
        }

        Object finish() {
            if (hasNext()) {
                throw new IllegalStateException(
                        "Array.match finished before snapshot exhaustion");
            }

            if (mismatch) {
                return ProtosBooleanValue.FALSE;
            }

            if (captures.isEmpty()) {
                return ProtosBooleanValue.TRUE;
            }

            return activation
                    .prelude()
                    .orElseThrow(
                            () ->
                                    new IllegalStateException(
                                            "standard Array.match requires an owning Core prelude"))
                    .newArray(captures);
        }
    }

    @Operation
    public static final class IsStructuredArrayMatchCall {
        @Specialization
        public static boolean ordinary(OrdinarySourceCall prepared) {
            return prepared.isStructuredArrayMatch();
        }

        @Specialization
        public static boolean nativeCall(NativeCall prepared) {
            return prepared.isStructuredArrayMatch();
        }

        @Specialization
        public static boolean immediate(ImmediateResultCall prepared) {
            return prepared.isStructuredArrayMatch();
        }

        @Specialization
        public static boolean moduleInitialization(ModuleInitializationCall prepared) {
            return prepared.isStructuredArrayMatch();
        }
    }

    @Operation
    public static final class PrepareStructuredArrayMatchCall {
        @Specialization
        public static PreparedArrayMatchCall ordinary(OrdinarySourceCall prepared) {
            return prepared.prepareStructuredArrayMatch();
        }

        @Specialization
        public static PreparedArrayMatchCall nativeCall(NativeCall prepared) {
            return prepared.prepareStructuredArrayMatch();
        }

        @Specialization
        public static PreparedArrayMatchCall immediate(ImmediateResultCall prepared) {
            return prepared.prepareStructuredArrayMatch();
        }

        @Specialization
        public static PreparedArrayMatchCall moduleInitialization(
                ModuleInitializationCall prepared) {
            return prepared.prepareStructuredArrayMatch();
        }
    }

    @Operation
    public static final class StructuredArrayMatchHasNext {
        @Specialization
        public static boolean perform(PreparedArrayMatchCall prepared) {
            return prepared.hasNext();
        }
    }

    @Operation
    public static final class PrepareStructuredArrayMatchElementCall {
        @Specialization
        public static PreparedClosureCall perform(
                PreparedArrayMatchCall prepared) {
            return prepared.prepareCurrent();
        }
    }

    @Operation
    public static final class AcceptStructuredArrayMatchOutcome {
        @Specialization
        public static void perform(
                PreparedArrayMatchCall prepared,
                Object outcome) {
            prepared.acceptCurrent(outcome);
        }
    }

    @Operation
    public static final class FinishStructuredArrayMatch {
        @Specialization
        public static Object perform(PreparedArrayMatchCall prepared) {
            return prepared.finish();
        }
    }

    static final class PreparedBytesEachCall implements PreparedIndexedEachCall {
        private final ProtosBytesValue bytes;
        private final List<Object> snapshot;
        private final Object block;
        private final ProtosActivation activation;
        private int index;

        PreparedBytesEachCall(
                Object receiver,
                List<?> supplied,
                ProtosActivation activation) {
            this.activation = java.util.Objects.requireNonNull(activation, "activation");
            if (!(receiver instanceof ProtosBytesValue value)
                    || supplied.size() != 1) {
                throw ProtosCoreErrors.signal(
                        activation,
                        ProtosCoreErrors.newError(activation));
            }
            this.bytes = value;
            this.block = supplied.get(0);
            ProtosStandardBytesProtocol.requireInvokableForStructured(
                    block,
                    activation);
            this.snapshot = value.indexedSnapshot();
        }

        @Override
        public boolean hasNext() {
            return index < snapshot.size();
        }

        @Override
        public PreparedClosureCall prepareCurrent() {
            if (!hasNext()) {
                throw new IllegalStateException(
                        "Bytes.each callback requested after snapshot exhaustion");
            }
            return prepareClosureCall(
                    block,
                    List.of(snapshot.get(index)),
                    activation);
        }

        @Override
        public PreparedInlineLiteralCall prepareInlineCurrent() {
            if (!hasNext()) {
                throw new IllegalStateException(
                        "Bytes.each callback requested after snapshot exhaustion");
            }
            return prepareInlineLiteralCall(
                    block,
                    List.of(snapshot.get(index)),
                    activation);
        }

        @Override
        public void advance() {
            if (!hasNext()) {
                throw new IllegalStateException(
                        "Bytes.each cursor advanced after snapshot exhaustion");
            }
            index++;
        }

        @Override
        public Object finish() {
            if (hasNext()) {
                throw new IllegalStateException(
                        "Bytes.each finished before snapshot exhaustion");
            }
            return bytes;
        }

        @Override
        public Object callback() {
            return block;
        }
    }

    @Operation
    public static final class IsStructuredBytesEachCall {
        @Specialization
        public static boolean ordinary(OrdinarySourceCall prepared) {
            return prepared.isStructuredBytesEach();
        }

        @Specialization
        public static boolean nativeCall(NativeCall prepared) {
            return prepared.isStructuredBytesEach();
        }

        @Specialization
        public static boolean immediate(ImmediateResultCall prepared) {
            return prepared.isStructuredBytesEach();
        }

        @Specialization
        public static boolean moduleInitialization(ModuleInitializationCall prepared) {
            return prepared.isStructuredBytesEach();
        }
    }

    @Operation
    public static final class PrepareStructuredBytesEachCall {
        @Specialization
        public static PreparedBytesEachCall ordinary(OrdinarySourceCall prepared) {
            return prepared.prepareStructuredBytesEach();
        }

        @Specialization
        public static PreparedBytesEachCall nativeCall(NativeCall prepared) {
            return prepared.prepareStructuredBytesEach();
        }

        @Specialization
        public static PreparedBytesEachCall immediate(ImmediateResultCall prepared) {
            return prepared.prepareStructuredBytesEach();
        }

        @Specialization
        public static PreparedBytesEachCall moduleInitialization(
                ModuleInitializationCall prepared) {
            return prepared.prepareStructuredBytesEach();
        }
    }

    @Operation
    public static final class StructuredBytesEachHasNext {
        @Specialization
        public static boolean perform(PreparedBytesEachCall prepared) {
            return prepared.hasNext();
        }
    }

    @Operation
    public static final class PrepareStructuredBytesEachElementCall {
        @Specialization
        public static PreparedClosureCall perform(PreparedBytesEachCall prepared) {
            return prepared.prepareCurrent();
        }
    }

    @Operation
    public static final class AdvanceStructuredBytesEach {
        @Specialization
        public static void perform(PreparedBytesEachCall prepared) {
            prepared.advance();
        }
    }

    @Operation
    public static final class FinishStructuredBytesEach {
        @Specialization
        public static Object perform(PreparedBytesEachCall prepared) {
            return prepared.finish();
        }
    }

    /**
     * Standard Environment.each loop state. It joins the PLAT044 B′
     * (PERF026-D3) local each view directly, not as an association each: its
     * snapshot is not a shallow association snapshot but the complete
     * portable (String, String) snapshot, converted, validated and
     * canonically ordered by the Environment protocol before any callback is
     * prepared.
     */
    static final class PreparedEnvironmentEachCall implements PreparedLocalEachCall {
        private final ProtosEnvironmentValue environment;
        private final List<ProtosEnvironmentValue.PortableEntry> snapshot;
        private final Object block;
        private final ProtosActivation activation;
        private int index;

        PreparedEnvironmentEachCall(
                Object receiver,
                List<?> supplied,
                ProtosActivation activation) {
            this.activation = java.util.Objects.requireNonNull(activation, "activation");
            if (!(receiver instanceof ProtosEnvironmentValue value)
                    || supplied.size() != 1) {
                throw ProtosCoreErrors.signal(
                        activation,
                        ProtosCoreErrors.newError(activation));
            }
            this.environment = value;
            this.block = supplied.get(0);
            ProtosStandardEnvironmentProtocol.requireInvokableForStructured(
                    block,
                    activation);
            /*
             * PLAT028 Environment.each must preserve the historical eager
             * portable-representation cutover. This call validates/converts
             * the complete environment before callback #1 can be prepared.
             */
            this.snapshot =
                    List.copyOf(
                            ProtosStandardEnvironmentProtocol.portableEntriesForStructured(
                                    value,
                                    activation));
        }

        @Override
        public boolean hasNext() {
            return index < snapshot.size();
        }

        @Override
        public PreparedClosureCall prepareCurrent() {
            if (!hasNext()) {
                throw new IllegalStateException(
                        "Environment.each callback requested after snapshot exhaustion");
            }
            ProtosEnvironmentValue.PortableEntry entry = snapshot.get(index);
            return prepareClosureCall(
                    block,
                    List.of(entry.name(), entry.value()),
                    activation);
        }

        @Override
        public PreparedInlineLiteralCall prepareInlineCurrent() {
            if (!hasNext()) {
                throw new IllegalStateException(
                        "Environment.each callback requested after snapshot exhaustion");
            }
            ProtosEnvironmentValue.PortableEntry entry = snapshot.get(index);
            return prepareInlineLiteralCall(
                    block,
                    List.of(entry.name(), entry.value()),
                    activation);
        }

        @Override
        public void advance() {
            if (!hasNext()) {
                throw new IllegalStateException(
                        "Environment.each cursor advanced after snapshot exhaustion");
            }
            index++;
        }

        @Override
        public Object finish() {
            if (hasNext()) {
                throw new IllegalStateException(
                        "Environment.each finished before snapshot exhaustion");
            }
            return environment;
        }

        @Override
        public Object callback() {
            return block;
        }
    }

    @Operation
    public static final class IsStructuredEnvironmentEachCall {
        @Specialization
        public static boolean ordinary(OrdinarySourceCall prepared) {
            return prepared.isStructuredEnvironmentEach();
        }

        @Specialization
        public static boolean nativeCall(NativeCall prepared) {
            return prepared.isStructuredEnvironmentEach();
        }

        @Specialization
        public static boolean immediate(ImmediateResultCall prepared) {
            return prepared.isStructuredEnvironmentEach();
        }

        @Specialization
        public static boolean moduleInitialization(ModuleInitializationCall prepared) {
            return prepared.isStructuredEnvironmentEach();
        }
    }

    @Operation
    public static final class PrepareStructuredEnvironmentEachCall {
        @Specialization
        public static PreparedEnvironmentEachCall ordinary(OrdinarySourceCall prepared) {
            return prepared.prepareStructuredEnvironmentEach();
        }

        @Specialization
        public static PreparedEnvironmentEachCall nativeCall(NativeCall prepared) {
            return prepared.prepareStructuredEnvironmentEach();
        }

        @Specialization
        public static PreparedEnvironmentEachCall immediate(ImmediateResultCall prepared) {
            return prepared.prepareStructuredEnvironmentEach();
        }

        @Specialization
        public static PreparedEnvironmentEachCall moduleInitialization(
                ModuleInitializationCall prepared) {
            return prepared.prepareStructuredEnvironmentEach();
        }
    }

    @Operation
    public static final class StructuredEnvironmentEachHasNext {
        @Specialization
        public static boolean perform(PreparedEnvironmentEachCall prepared) {
            return prepared.hasNext();
        }
    }

    @Operation
    public static final class PrepareStructuredEnvironmentEachEntryCall {
        @Specialization
        public static PreparedClosureCall perform(PreparedEnvironmentEachCall prepared) {
            return prepared.prepareCurrent();
        }
    }

    @Operation
    public static final class AdvanceStructuredEnvironmentEach {
        @Specialization
        public static void perform(PreparedEnvironmentEachCall prepared) {
            prepared.advance();
        }
    }

    @Operation
    public static final class FinishStructuredEnvironmentEach {
        @Specialization
        public static Object perform(PreparedEnvironmentEachCall prepared) {
            return prepared.finish();
        }
    }

    @Operation
    public static final class IsStructuredForeignEachCall {
        @Specialization
        public static boolean ordinary(OrdinarySourceCall prepared) {
            return prepared.isStructuredForeignEach();
        }

        @Specialization
        public static boolean nativeCall(NativeCall prepared) {
            return prepared.isStructuredForeignEach();
        }

        @Specialization
        public static boolean immediate(ImmediateResultCall prepared) {
            return prepared.isStructuredForeignEach();
        }

        @Specialization
        public static boolean moduleInitialization(ModuleInitializationCall prepared) {
            return prepared.isStructuredForeignEach();
        }
    }

    /**
     * D188 projected foreign {@code each}: the cursor validates the receiver, arity and block
     * callability before any foreign operation; the provider iterator is acquired lazily by the
     * first {@link StructuredForeignEachHasNext}.
     */
    @Operation
    public static final class PrepareStructuredForeignEachCall {
        @Specialization
        public static ProtosForeignEachCall ordinary(OrdinarySourceCall prepared) {
            return prepared.prepareStructuredForeignEach();
        }

        @Specialization
        public static ProtosForeignEachCall nativeCall(NativeCall prepared) {
            return prepared.prepareStructuredForeignEach();
        }

        @Specialization
        public static ProtosForeignEachCall immediate(ImmediateResultCall prepared) {
            return prepared.prepareStructuredForeignEach();
        }

        @Specialization
        public static ProtosForeignEachCall moduleInitialization(
                ModuleInitializationCall prepared) {
            return prepared.prepareStructuredForeignEach();
        }
    }

    @Operation
    public static final class StructuredForeignEachHasNext {
        @Specialization
        public static boolean perform(ProtosForeignEachCall prepared) {
            return prepared.hasNext();
        }
    }

    /** Pulls and admits one element, then prepares the ordinary invocation of the block. */
    @Operation
    public static final class PrepareStructuredForeignEachElementCall {
        @Specialization
        public static PreparedClosureCall perform(ProtosForeignEachCall prepared) {
            return prepareClosureCall(
                    prepared.callback(), List.of(prepared.current()), prepared.activation());
        }
    }

    @Operation
    public static final class AdvanceStructuredForeignEach {
        @Specialization
        public static void perform(ProtosForeignEachCall prepared) {
            prepared.advance();
        }
    }

    @Operation
    public static final class FinishStructuredForeignEach {
        @Specialization
        public static Object perform(ProtosForeignEachCall prepared) {
            return prepared.finish();
        }
    }


    static final class PreparedIdentityMapAtIfAbsentCall {
        private final ProtosIdentityMapValue.Entry match;
        private final Object fallback;
        private final ProtosActivation activation;
        private boolean fallbackPrepared;

        PreparedIdentityMapAtIfAbsentCall(
                Object receiver,
                List<?> supplied,
                ProtosActivation activation) {
            this.activation =
                    java.util.Objects.requireNonNull(
                            activation,
                            "activation");

            if (!(receiver instanceof ProtosIdentityMapValue map)
                    || supplied.size() != 2) {
                throw ProtosCoreErrors.signal(
                        activation,
                        ProtosCoreErrors.newError(activation));
            }

            Object key = supplied.get(0);
            this.fallback = supplied.get(1);

            this.match =
                    ProtosStandardIdentityMapProtocol.findForStructured(
                            map,
                            key);
        }

        boolean needsFallback() {
            return match == null;
        }

        PreparedClosureCall prepareFallback() {
            if (!needsFallback() || fallbackPrepared) {
                throw new IllegalStateException(
                        "IdentityMap.atIfAbsent fallback requested in an invalid state");
            }

            fallbackPrepared = true;

            return prepareClosureCall(
                    fallback,
                    List.of(),
                    activation);
        }

        Object finishFallback(Object result) {
            if (!needsFallback() || !fallbackPrepared) {
                throw new IllegalStateException(
                        "IdentityMap.atIfAbsent fallback result accepted in an invalid state");
            }
            return result;
        }

        Object finishPresent() {
            if (needsFallback()) {
                throw new IllegalStateException(
                        "IdentityMap.atIfAbsent present path has no matching entry");
            }
            if (fallbackPrepared) {
                throw new IllegalStateException(
                        "IdentityMap.atIfAbsent present path prepared its fallback");
            }
            return match.value();
        }
    }

    @Operation
    public static final class IsStructuredIdentityMapAtIfAbsentCall {
        @Specialization
        public static boolean ordinary(OrdinarySourceCall prepared) {
            return prepared.isStructuredIdentityMapAtIfAbsent();
        }

        @Specialization
        public static boolean nativeCall(NativeCall prepared) {
            return prepared.isStructuredIdentityMapAtIfAbsent();
        }

        @Specialization
        public static boolean immediate(ImmediateResultCall prepared) {
            return prepared.isStructuredIdentityMapAtIfAbsent();
        }

        @Specialization
        public static boolean moduleInitialization(ModuleInitializationCall prepared) {
            return prepared.isStructuredIdentityMapAtIfAbsent();
        }
    }

    @Operation
    public static final class PrepareStructuredIdentityMapAtIfAbsentCall {
        @Specialization
        public static PreparedIdentityMapAtIfAbsentCall ordinary(OrdinarySourceCall prepared) {
            return prepared.prepareStructuredIdentityMapAtIfAbsent();
        }

        @Specialization
        public static PreparedIdentityMapAtIfAbsentCall nativeCall(NativeCall prepared) {
            return prepared.prepareStructuredIdentityMapAtIfAbsent();
        }

        @Specialization
        public static PreparedIdentityMapAtIfAbsentCall immediate(ImmediateResultCall prepared) {
            return prepared.prepareStructuredIdentityMapAtIfAbsent();
        }

        @Specialization
        public static PreparedIdentityMapAtIfAbsentCall moduleInitialization(
                ModuleInitializationCall prepared) {
            return prepared.prepareStructuredIdentityMapAtIfAbsent();
        }
    }

    @Operation
    public static final class StructuredIdentityMapAtIfAbsentNeedsFallback {
        @Specialization
        public static boolean perform(
                PreparedIdentityMapAtIfAbsentCall prepared) {
            return prepared.needsFallback();
        }
    }

    @Operation
    public static final class PrepareStructuredIdentityMapAtIfAbsentFallbackCall {
        @Specialization
        public static PreparedClosureCall perform(
                PreparedIdentityMapAtIfAbsentCall prepared) {
            return prepared.prepareFallback();
        }
    }

    @Operation
    public static final class FinishStructuredIdentityMapAtIfAbsentFallback {
        @Specialization
        public static Object perform(
                PreparedIdentityMapAtIfAbsentCall prepared,
                Object result) {
            return prepared.finishFallback(result);
        }
    }

    @Operation
    public static final class FinishStructuredIdentityMapAtIfAbsentPresent {
        @Specialization
        public static Object perform(
                PreparedIdentityMapAtIfAbsentCall prepared) {
            return prepared.finishPresent();
        }
    }


    static final class PreparedIdentityMapEachCall implements PreparedAssociationEachCall {
        private final ProtosIdentityMapValue identityMap;
        private final List<java.util.Map.Entry<Object, Object>> snapshot;
        private final Object block;
        private final ProtosActivation activation;
        private int index;

        PreparedIdentityMapEachCall(
                Object receiver,
                List<?> supplied,
                ProtosActivation activation) {
            this.activation = java.util.Objects.requireNonNull(activation, "activation");
            if (!(receiver instanceof ProtosIdentityMapValue value)
                    || supplied.size() != 1) {
                throw ProtosCoreErrors.signal(
                        activation,
                        ProtosCoreErrors.newError(activation));
            }
            this.identityMap = value;
            this.block = supplied.get(0);
            ProtosStandardIdentityMapProtocol.requireInvokableForStructured(
                    block,
                    activation);
            this.snapshot = value.associationSnapshot();
        }

        @Override
        public boolean hasNext() {
            return index < snapshot.size();
        }

        @Override
        public PreparedClosureCall prepareCurrent() {
            if (!hasNext()) {
                throw new IllegalStateException(
                        "IdentityMap.each callback requested after snapshot exhaustion");
            }
            java.util.Map.Entry<Object, Object> entry = snapshot.get(index);
            return prepareClosureCall(
                    block,
                    List.of(entry.getKey(), entry.getValue()),
                    activation);
        }

        @Override
        public PreparedInlineLiteralCall prepareInlineCurrent() {
            if (!hasNext()) {
                throw new IllegalStateException(
                        "IdentityMap.each callback requested after snapshot exhaustion");
            }
            java.util.Map.Entry<Object, Object> entry = snapshot.get(index);
            return prepareInlineLiteralCall(
                    block,
                    List.of(entry.getKey(), entry.getValue()),
                    activation);
        }

        @Override
        public void advance() {
            if (!hasNext()) {
                throw new IllegalStateException(
                        "IdentityMap.each cursor advanced after snapshot exhaustion");
            }
            index++;
        }

        @Override
        public Object finish() {
            if (hasNext()) {
                throw new IllegalStateException(
                        "IdentityMap.each finished before snapshot exhaustion");
            }
            return identityMap;
        }

        @Override
        public Object callback() {
            return block;
        }
    }

    @Operation
    public static final class IsStructuredIdentityMapEachCall {
        @Specialization
        public static boolean ordinary(OrdinarySourceCall prepared) {
            return prepared.isStructuredIdentityMapEach();
        }

        @Specialization
        public static boolean nativeCall(NativeCall prepared) {
            return prepared.isStructuredIdentityMapEach();
        }

        @Specialization
        public static boolean immediate(ImmediateResultCall prepared) {
            return prepared.isStructuredIdentityMapEach();
        }

        @Specialization
        public static boolean moduleInitialization(ModuleInitializationCall prepared) {
            return prepared.isStructuredIdentityMapEach();
        }
    }

    @Operation
    public static final class PrepareStructuredIdentityMapEachCall {
        @Specialization
        public static PreparedIdentityMapEachCall ordinary(OrdinarySourceCall prepared) {
            return prepared.prepareStructuredIdentityMapEach();
        }

        @Specialization
        public static PreparedIdentityMapEachCall nativeCall(NativeCall prepared) {
            return prepared.prepareStructuredIdentityMapEach();
        }

        @Specialization
        public static PreparedIdentityMapEachCall immediate(ImmediateResultCall prepared) {
            return prepared.prepareStructuredIdentityMapEach();
        }

        @Specialization
        public static PreparedIdentityMapEachCall moduleInitialization(
                ModuleInitializationCall prepared) {
            return prepared.prepareStructuredIdentityMapEach();
        }
    }

    @Operation
    public static final class StructuredIdentityMapEachHasNext {
        @Specialization
        public static boolean perform(PreparedIdentityMapEachCall prepared) {
            return prepared.hasNext();
        }
    }

    @Operation
    public static final class PrepareStructuredIdentityMapEachEntryCall {
        @Specialization
        public static PreparedClosureCall perform(PreparedIdentityMapEachCall prepared) {
            return prepared.prepareCurrent();
        }
    }

    @Operation
    public static final class AdvanceStructuredIdentityMapEach {
        @Specialization
        public static void perform(PreparedIdentityMapEachCall prepared) {
            prepared.advance();
        }
    }

    @Operation
    public static final class FinishStructuredIdentityMapEach {
        @Specialization
        public static Object perform(PreparedIdentityMapEachCall prepared) {
            return prepared.finish();
        }
    }




    static final class PreparedMapMatchCall {
        private final ProtosActivation activation;
        private final ProtosMapValue subject;
        private final List<ProtosStandardMapProtocol.StableAssociation> matcherSnapshot;
        private final List<ProtosStandardMapProtocol.StableAssociation> subjectSnapshot;
        private final List<Object> childMatchers = new ArrayList<>();
        private final List<Object> selectedValues = new ArrayList<>();
        private final List<Object> captures = new ArrayList<>();

        private int requirementIndex;
        private int candidateIndex;
        private int childIndex;

        private ProtosNumericHashKey queryHash;
        private ProtosStandardMapProtocol.StableAssociation selectedAssociation;

        private boolean hashAccepted;
        private boolean comparisonEntered;
        private boolean mismatch;

        PreparedMapMatchCall(
                Object receiver,
                List<?> supplied,
                ProtosActivation activation) {
            this.activation =
                    java.util.Objects.requireNonNull(
                            activation,
                            "activation");

            if (!(receiver instanceof ProtosMapValue matcher)
                    || supplied.size() != 1) {
                throw ProtosCoreErrors.signal(
                        activation,
                        ProtosCoreErrors.newError(activation));
            }

            Object subjectValue = supplied.get(0);
            if (!(subjectValue instanceof ProtosMapValue subjectMap)) {
                subject = null;
                matcherSnapshot = List.of();
                subjectSnapshot = List.of();
                mismatch = true;
                return;
            }

            subject = subjectMap;
            matcherSnapshot =
                    ProtosStandardMapProtocol.stableSnapshot(matcher);
            subjectSnapshot =
                    ProtosStandardMapProtocol.stableSnapshot(subjectMap);
        }

        boolean hasRequirement() {
            return !mismatch
                    && requirementIndex < matcherSnapshot.size();
        }

        void enterComparison() {
            if (!hasRequirement()) {
                throw new IllegalStateException(
                        "Map.match comparison entered without a current requirement");
            }
            if (comparisonEntered) {
                throw new IllegalStateException(
                        "Map.match attempted to nest its own comparison scope");
            }
            subject.enterComparison();
            comparisonEntered = true;
        }

        void leaveComparison() {
            if (!comparisonEntered) {
                throw new IllegalStateException(
                        "Map.match comparison scope left without entry");
            }
            subject.leaveComparison();
            comparisonEntered = false;
        }

        PreparedClosureCall prepareHash() {
            if (!hasRequirement() || hashAccepted) {
                throw new IllegalStateException(
                        "Map.match hash requested in an invalid state");
            }

            return prepareSend(
                    matcherSnapshot.get(requirementIndex).key(),
                    "hash",
                    activation,
                    List.of());
        }

        void acceptHash(Object result) {
            if (comparisonEntered) {
                throw new IllegalStateException(
                        "Map.match hash accepted before comparison-scope exit");
            }
            if (!hasRequirement() || hashAccepted) {
                throw new IllegalStateException(
                        "Map.match hash accepted in an invalid state");
            }

            queryHash =
                    ProtosStandardMapProtocol.requireHashResultForStructured(
                            result,
                            activation);
            candidateIndex = 0;
            selectedAssociation = null;
            hashAccepted = true;
        }

        boolean needsEquality() {
            requireHashAccepted();

            if (selectedAssociation != null) {
                return false;
            }

            while (candidateIndex < subjectSnapshot.size()
                    && !subjectSnapshot.get(candidateIndex)
                            .recordedHash()
                            .equals(queryHash)) {
                candidateIndex++;
            }

            return candidateIndex < subjectSnapshot.size();
        }

        PreparedClosureCall prepareEquality() {
            if (!needsEquality()) {
                throw new IllegalStateException(
                        "Map.match equality requested without a candidate");
            }

            return prepareSend(
                    matcherSnapshot.get(requirementIndex).key(),
                    "==",
                    activation,
                    List.of(subjectSnapshot.get(candidateIndex).key()));
        }

        void acceptEquality(Object result) {
            if (comparisonEntered) {
                throw new IllegalStateException(
                        "Map.match equality accepted before comparison-scope exit");
            }
            if (!needsEquality()) {
                throw new IllegalStateException(
                        "Map.match equality accepted without a candidate");
            }

            boolean equal =
                    ProtosStandardMapProtocol.requireEqualityResultForStructured(
                            result,
                            activation);

            if (equal) {
                selectedAssociation =
                        subjectSnapshot.get(candidateIndex);
            } else {
                candidateIndex++;
            }
        }

        void finishRequirement() {
            requireHashAccepted();

            if (comparisonEntered) {
                throw new IllegalStateException(
                        "Map.match requirement finished with an active comparison scope");
            }
            if (needsEquality()) {
                throw new IllegalStateException(
                        "Map.match requirement finished before candidate exhaustion");
            }

            if (selectedAssociation == null) {
                mismatch = true;
                return;
            }

            ProtosStandardMapProtocol.StableAssociation requirement =
                    matcherSnapshot.get(requirementIndex);

            childMatchers.add(requirement.value());
            selectedValues.add(selectedAssociation.value());

            requirementIndex++;
            candidateIndex = 0;
            queryHash = null;
            selectedAssociation = null;
            hashAccepted = false;
        }

        boolean hasChildMatcher() {
            return !mismatch && childIndex < childMatchers.size();
        }

        PreparedClosureCall prepareChildMatcher() {
            if (!hasChildMatcher()) {
                throw new IllegalStateException(
                        "Map.match child matcher requested after terminal outcome");
            }

            return prepareSend(
                    childMatchers.get(childIndex),
                    "match",
                    activation,
                    List.of(selectedValues.get(childIndex)));
        }

        void acceptChildOutcome(Object outcome) {
            if (!hasChildMatcher()) {
                throw new IllegalStateException(
                        "Map.match child outcome accepted after terminal outcome");
            }

            if (outcome == ProtosBooleanValue.FALSE) {
                mismatch = true;
                return;
            }

            if (outcome == ProtosBooleanValue.TRUE) {
                childIndex++;
                return;
            }

            if (outcome instanceof ProtosArrayValue childCaptures) {
                List<Object> observedCaptures =
                        childCaptures.indexedSnapshot();

                if (observedCaptures.isEmpty()) {
                    throw ProtosCoreErrors.signal(
                            activation,
                            ProtosCoreErrors.newError(activation));
                }

                for (int captureIndex = 0;
                        captureIndex < observedCaptures.size();
                        captureIndex++) {
                    captures.add(observedCaptures.get(captureIndex));
                }
                childIndex++;
                return;
            }

            throw ProtosCoreErrors.signal(
                    activation,
                    ProtosCoreErrors.newError(activation));
        }

        Object finish() {
            if (comparisonEntered) {
                throw new IllegalStateException(
                        "Map.match finished with an active comparison scope");
            }
            if (hasRequirement()) {
                throw new IllegalStateException(
                        "Map.match finished before requirement resolution completed");
            }
            if (hasChildMatcher()) {
                throw new IllegalStateException(
                        "Map.match finished before child matcher exhaustion");
            }

            if (mismatch) {
                return ProtosBooleanValue.FALSE;
            }

            if (captures.isEmpty()) {
                return ProtosBooleanValue.TRUE;
            }

            return activation
                    .prelude()
                    .orElseThrow(
                            () ->
                                    new IllegalStateException(
                                            "standard Map.match requires an owning Core prelude"))
                    .newArray(captures);
        }

        private void requireHashAccepted() {
            if (!hashAccepted) {
                throw new IllegalStateException(
                        "Map.match requirement used before hash acceptance");
            }
        }
    }

    @Operation
    public static final class IsStructuredMapMatchCall {
        @Specialization
        public static boolean ordinary(OrdinarySourceCall prepared) {
            return prepared.isStructuredMapMatch();
        }

        @Specialization
        public static boolean nativeCall(NativeCall prepared) {
            return prepared.isStructuredMapMatch();
        }

        @Specialization
        public static boolean immediate(ImmediateResultCall prepared) {
            return prepared.isStructuredMapMatch();
        }

        @Specialization
        public static boolean moduleInitialization(ModuleInitializationCall prepared) {
            return prepared.isStructuredMapMatch();
        }
    }

    @Operation
    public static final class PrepareStructuredMapMatchCall {
        @Specialization
        public static PreparedMapMatchCall ordinary(OrdinarySourceCall prepared) {
            return prepared.prepareStructuredMapMatch();
        }

        @Specialization
        public static PreparedMapMatchCall nativeCall(NativeCall prepared) {
            return prepared.prepareStructuredMapMatch();
        }

        @Specialization
        public static PreparedMapMatchCall immediate(ImmediateResultCall prepared) {
            return prepared.prepareStructuredMapMatch();
        }

        @Specialization
        public static PreparedMapMatchCall moduleInitialization(ModuleInitializationCall prepared) {
            return prepared.prepareStructuredMapMatch();
        }
    }

    @Operation
    public static final class StructuredMapMatchHasRequirement {
        @Specialization
        public static boolean perform(PreparedMapMatchCall prepared) {
            return prepared.hasRequirement();
        }
    }

    @Operation
    public static final class EnterStructuredMapMatchComparison {
        @Specialization
        public static void perform(PreparedMapMatchCall prepared) {
            prepared.enterComparison();
        }
    }

    @Operation
    public static final class LeaveStructuredMapMatchComparison {
        @Specialization
        public static void perform(PreparedMapMatchCall prepared) {
            prepared.leaveComparison();
        }
    }

    @Operation
    public static final class PrepareStructuredMapMatchHashCall {
        @Specialization
        public static PreparedClosureCall perform(
                PreparedMapMatchCall prepared) {
            return prepared.prepareHash();
        }
    }

    @Operation
    public static final class AcceptStructuredMapMatchHashResult {
        @Specialization
        public static void perform(
                PreparedMapMatchCall prepared,
                Object result) {
            prepared.acceptHash(result);
        }
    }

    @Operation
    public static final class StructuredMapMatchNeedsEquality {
        @Specialization
        public static boolean perform(PreparedMapMatchCall prepared) {
            return prepared.needsEquality();
        }
    }

    @Operation
    public static final class PrepareStructuredMapMatchEqualityCall {
        @Specialization
        public static PreparedClosureCall perform(
                PreparedMapMatchCall prepared) {
            return prepared.prepareEquality();
        }
    }

    @Operation
    public static final class AcceptStructuredMapMatchEqualityResult {
        @Specialization
        public static void perform(
                PreparedMapMatchCall prepared,
                Object result) {
            prepared.acceptEquality(result);
        }
    }

    @Operation
    public static final class FinishStructuredMapMatchRequirement {
        @Specialization
        public static void perform(PreparedMapMatchCall prepared) {
            prepared.finishRequirement();
        }
    }

    @Operation
    public static final class StructuredMapMatchHasChildMatcher {
        @Specialization
        public static boolean perform(PreparedMapMatchCall prepared) {
            return prepared.hasChildMatcher();
        }
    }

    @Operation
    public static final class PrepareStructuredMapMatchChildCall {
        @Specialization
        public static PreparedClosureCall perform(
                PreparedMapMatchCall prepared) {
            return prepared.prepareChildMatcher();
        }
    }

    @Operation
    public static final class AcceptStructuredMapMatchChildOutcome {
        @Specialization
        public static void perform(
                PreparedMapMatchCall prepared,
                Object outcome) {
            prepared.acceptChildOutcome(outcome);
        }
    }

    @Operation
    public static final class FinishStructuredMapMatch {
        @Specialization
        public static Object perform(PreparedMapMatchCall prepared) {
            return prepared.finish();
        }
    }

    static final class PreparedMapReadLookupCall {
        private final ProtosStandardMapProtocol.StructuredReadLookupKind kind;
        private final ProtosMapValue map;
        private final Object key;
        private final Object fallback;
        private final ProtosActivation activation;
        private ProtosNumericHashKey queryHash;
        private List<ProtosMapValue.Entry> candidates;
        private int index;
        private ProtosMapValue.Entry match;
        private boolean hashAccepted;
        private boolean comparisonEntered;
        private boolean fallbackPrepared;

        PreparedMapReadLookupCall(
                ProtosStandardMapProtocol.StructuredReadLookupKind kind,
                Object receiver,
                List<?> supplied,
                ProtosActivation activation) {
            this.kind = java.util.Objects.requireNonNull(kind, "kind");
            this.activation = java.util.Objects.requireNonNull(activation, "activation");
            int expectedArity =
                    kind == ProtosStandardMapProtocol.StructuredReadLookupKind.AT_IF_ABSENT
                            ? 2
                            : 1;
            if (!(receiver instanceof ProtosMapValue value)
                    || supplied.size() != expectedArity) {
                throw ProtosCoreErrors.signal(
                        activation,
                        ProtosCoreErrors.newError(activation));
            }
            this.map = value;
            this.key = supplied.get(0);
            this.fallback =
                    kind == ProtosStandardMapProtocol.StructuredReadLookupKind.AT_IF_ABSENT
                            ? supplied.get(1)
                            : null;
        }

        void enterComparison() {
            if (comparisonEntered) {
                throw new IllegalStateException(
                        "Map read lookup attempted to nest its own comparison scope");
            }
            map.enterComparison();
            comparisonEntered = true;
        }

        void leaveComparison() {
            if (!comparisonEntered) {
                throw new IllegalStateException(
                        "Map read lookup comparison scope left without entry");
            }
            map.leaveComparison();
            comparisonEntered = false;
        }

        PreparedClosureCall prepareHash() {
            if (hashAccepted) {
                throw new IllegalStateException(
                        "Map read lookup hash callback prepared after hash acceptance");
            }
            return prepareSend(
                    key,
                    "hash",
                    activation,
                    List.of());
        }

        void acceptHash(Object result) {
            if (comparisonEntered) {
                throw new IllegalStateException(
                        "Map read lookup hash result accepted before comparison-scope exit");
            }
            if (hashAccepted) {
                throw new IllegalStateException(
                        "Map read lookup hash result accepted twice");
            }
            queryHash =
                    ProtosStandardMapProtocol.requireHashResultForStructured(
                            result,
                            activation);
            candidates = map.candidatesForRecordedHash(queryHash);
            hashAccepted = true;
        }

        boolean needsEquality() {
            requireHashAccepted();
            if (match != null) {
                return false;
            }
            return index < candidates.size();
        }

        PreparedClosureCall prepareEquality() {
            if (!needsEquality()) {
                throw new IllegalStateException(
                        "Map read lookup equality callback requested without a candidate");
            }
            return prepareSend(
                    key,
                    "==",
                    activation,
                    List.of(candidates.get(index).key()));
        }

        void acceptEquality(Object result) {
            if (comparisonEntered) {
                throw new IllegalStateException(
                        "Map read lookup equality result accepted before comparison-scope exit");
            }
            if (!needsEquality()) {
                throw new IllegalStateException(
                        "Map read lookup equality result accepted without a candidate");
            }
            boolean equal =
                    ProtosStandardMapProtocol.requireEqualityResultForStructured(
                            result,
                            activation);
            if (equal) {
                match = candidates.get(index);
            } else {
                index++;
            }
        }

        boolean needsFallback() {
            requireSearchComplete();
            return kind == ProtosStandardMapProtocol.StructuredReadLookupKind.AT_IF_ABSENT
                    && match == null;
        }

        PreparedClosureCall prepareFallback() {
            if (!needsFallback() || fallbackPrepared) {
                throw new IllegalStateException(
                        "Map.atIfAbsent fallback requested in an invalid state");
            }
            fallbackPrepared = true;
            return prepareClosureCall(
                    fallback,
                    List.of(),
                    activation);
        }

        Object finishFallback(Object result) {
            requireSearchComplete();
            if (kind != ProtosStandardMapProtocol.StructuredReadLookupKind.AT_IF_ABSENT
                    || match != null
                    || !fallbackPrepared) {
                throw new IllegalStateException(
                        "Map.atIfAbsent fallback result accepted in an invalid state");
            }
            return result;
        }

        Object finish() {
            requireSearchComplete();

            return switch (kind) {
                case AT -> {
                    if (match == null) {
                        throw ProtosCoreErrors.signal(
                                activation,
                                ProtosCoreErrors.newError(activation));
                    }
                    yield match.value();
                }
                case CONTAINS_KEY ->
                        match == null
                                ? ProtosBooleanValue.FALSE
                                : ProtosBooleanValue.TRUE;
                case AT_IF_ABSENT -> {
                    if (match == null) {
                        throw new IllegalStateException(
                                "Map.atIfAbsent absent path requires its fallback result");
                    }
                    yield match.value();
                }
            };
        }

        private void requireSearchComplete() {
            requireHashAccepted();
            if (comparisonEntered) {
                throw new IllegalStateException(
                        "Map read lookup finished with an active comparison scope");
            }
            if (needsEquality()) {
                throw new IllegalStateException(
                        "Map read lookup used before candidate exhaustion");
            }
        }

        private void requireHashAccepted() {
            if (!hashAccepted) {
                throw new IllegalStateException(
                        "Map read lookup used before hash acceptance");
            }
        }
    }

    @Operation
    public static final class IsStructuredMapReadLookupCall {
        @Specialization
        public static boolean ordinary(OrdinarySourceCall prepared) {
            return prepared.isStructuredMapReadLookup();
        }

        @Specialization
        public static boolean nativeCall(NativeCall prepared) {
            return prepared.isStructuredMapReadLookup();
        }

        @Specialization
        public static boolean immediate(ImmediateResultCall prepared) {
            return prepared.isStructuredMapReadLookup();
        }

        @Specialization
        public static boolean moduleInitialization(ModuleInitializationCall prepared) {
            return prepared.isStructuredMapReadLookup();
        }
    }

    @Operation
    public static final class PrepareStructuredMapReadLookupCall {
        @Specialization
        public static PreparedMapReadLookupCall ordinary(OrdinarySourceCall prepared) {
            return prepared.prepareStructuredMapReadLookup();
        }

        @Specialization
        public static PreparedMapReadLookupCall nativeCall(NativeCall prepared) {
            return prepared.prepareStructuredMapReadLookup();
        }

        @Specialization
        public static PreparedMapReadLookupCall immediate(ImmediateResultCall prepared) {
            return prepared.prepareStructuredMapReadLookup();
        }

        @Specialization
        public static PreparedMapReadLookupCall moduleInitialization(
                ModuleInitializationCall prepared) {
            return prepared.prepareStructuredMapReadLookup();
        }
    }

    @Operation
    public static final class EnterStructuredMapReadLookupComparison {
        @Specialization
        public static void perform(PreparedMapReadLookupCall prepared) {
            prepared.enterComparison();
        }
    }

    @Operation
    public static final class LeaveStructuredMapReadLookupComparison {
        @Specialization
        public static void perform(PreparedMapReadLookupCall prepared) {
            prepared.leaveComparison();
        }
    }

    @Operation
    public static final class PrepareStructuredMapReadLookupHashCall {
        @Specialization
        public static PreparedClosureCall perform(PreparedMapReadLookupCall prepared) {
            return prepared.prepareHash();
        }
    }

    @Operation
    public static final class AcceptStructuredMapReadLookupHashResult {
        @Specialization
        public static void perform(PreparedMapReadLookupCall prepared, Object result) {
            prepared.acceptHash(result);
        }
    }

    @Operation
    public static final class StructuredMapReadLookupNeedsEquality {
        @Specialization
        public static boolean perform(PreparedMapReadLookupCall prepared) {
            return prepared.needsEquality();
        }
    }

    @Operation
    public static final class PrepareStructuredMapReadLookupEqualityCall {
        @Specialization
        public static PreparedClosureCall perform(PreparedMapReadLookupCall prepared) {
            return prepared.prepareEquality();
        }
    }

    @Operation
    public static final class AcceptStructuredMapReadLookupEqualityResult {
        @Specialization
        public static void perform(PreparedMapReadLookupCall prepared, Object result) {
            prepared.acceptEquality(result);
        }
    }

    @Operation
    public static final class StructuredMapReadLookupNeedsFallback {
        @Specialization
        public static boolean perform(PreparedMapReadLookupCall prepared) {
            return prepared.needsFallback();
        }
    }

    @Operation
    public static final class PrepareStructuredMapReadLookupFallbackCall {
        @Specialization
        public static PreparedClosureCall perform(
                PreparedMapReadLookupCall prepared) {
            return prepared.prepareFallback();
        }
    }

    @Operation
    public static final class FinishStructuredMapReadLookupFallback {
        @Specialization
        public static Object perform(
                PreparedMapReadLookupCall prepared,
                Object result) {
            return prepared.finishFallback(result);
        }
    }

    @Operation
    public static final class FinishStructuredMapReadLookup {
        @Specialization
        public static Object perform(PreparedMapReadLookupCall prepared) {
            return prepared.finish();
        }
    }


    @Operation
    public static final class PrepareMapConstructionFactoryCall {
        @Specialization
        public static PreparedClosureCall perform(
                ProtosActivation activation,
                Object factory) {
            ProtosSlotLookupResult selected =
                    ProtosStandardMapProtocol
                            .selectFactoryCallForMapConstruction(
                                    factory,
                                    activation);

            return prepareImmediateMethodCall(
                    (ProtosClosureValue) selected.value(),
                    factory,
                    selected.home(),
                    List.of(),
                    activation);
        }
    }

    @Operation
    public static final class FinishMapConstructionFactoryCall {
        @Specialization
        public static ProtosMapValue perform(
                ProtosActivation activation,
                Object result) {
            return ProtosStandardMapProtocol
                    .requireMapConstructionResult(
                            result,
                            activation);
        }
    }

    static final class PreparedMapInitialDefinition {
        private final ProtosMapValue map;
        private final Object key;
        private final Object value;
        private final ProtosActivation activation;
        private ProtosNumericHashKey queryHash;
        private List<ProtosMapValue.Entry> candidates;
        private int index;
        private ProtosMapValue.Entry match;
        private boolean hashAccepted;
        private boolean comparisonEntered;

        PreparedMapInitialDefinition(
                Object receiver,
                Object key,
                Object value,
                ProtosActivation activation) {
            this.activation =
                    java.util.Objects.requireNonNull(
                            activation,
                            "activation");

            if (!(receiver instanceof ProtosMapValue mapValue)
                    || !mapValue.isOpen()
                    || mapValue.comparisonActive()) {
                throw ProtosCoreErrors.signal(
                        activation,
                        ProtosCoreErrors.newError(activation));
            }

            this.map = mapValue;
            this.key = java.util.Objects.requireNonNull(key, "key");
            this.value = java.util.Objects.requireNonNull(value, "value");
        }

        void enterComparison() {
            if (comparisonEntered) {
                throw new IllegalStateException(
                        "Map initial definition attempted to nest its own comparison scope");
            }
            map.enterComparison();
            comparisonEntered = true;
        }

        void leaveComparison() {
            if (!comparisonEntered) {
                throw new IllegalStateException(
                        "Map initial-definition comparison scope left without entry");
            }
            map.leaveComparison();
            comparisonEntered = false;
        }

        PreparedClosureCall prepareHash() {
            if (hashAccepted) {
                throw new IllegalStateException(
                        "Map initial-definition hash callback prepared after hash acceptance");
            }
            return prepareSend(
                    key,
                    "hash",
                    activation,
                    List.of());
        }

        void acceptHash(Object result) {
            if (comparisonEntered) {
                throw new IllegalStateException(
                        "Map initial-definition hash accepted before comparison-scope exit");
            }
            if (hashAccepted) {
                throw new IllegalStateException(
                        "Map initial-definition hash accepted twice");
            }

            queryHash =
                    ProtosStandardMapProtocol.requireHashResultForStructured(
                            result,
                            activation);
            candidates = map.candidatesForRecordedHash(queryHash);
            hashAccepted = true;
        }

        boolean needsEquality() {
            requireHashAccepted();

            if (match != null) {
                return false;
            }

            return index < candidates.size();
        }

        PreparedClosureCall prepareEquality() {
            if (!needsEquality()) {
                throw new IllegalStateException(
                        "Map initial-definition equality callback requested without a candidate");
            }

            return prepareSend(
                    key,
                    "==",
                    activation,
                    List.of(candidates.get(index).key()));
        }

        void acceptEquality(Object result) {
            if (comparisonEntered) {
                throw new IllegalStateException(
                        "Map initial-definition equality accepted before comparison-scope exit");
            }
            if (!needsEquality()) {
                throw new IllegalStateException(
                        "Map initial-definition equality accepted without a candidate");
            }

            boolean equal =
                    ProtosStandardMapProtocol.requireEqualityResultForStructured(
                            result,
                            activation);

            if (equal) {
                match = candidates.get(index);
            } else {
                index++;
            }
        }

        void finish() {
            requireHashAccepted();

            if (comparisonEntered) {
                throw new IllegalStateException(
                        "Map initial definition finished with an active comparison scope");
            }
            if (needsEquality()) {
                throw new IllegalStateException(
                        "Map initial definition finished before candidate exhaustion");
            }

            if (match != null
                    || !map.isOpen()
                    || map.comparisonActive()) {
                throw ProtosCoreErrors.signal(
                        activation,
                        ProtosCoreErrors.newError(activation));
            }

            map.append(key, queryHash, value);
        }

        private void requireHashAccepted() {
            if (!hashAccepted) {
                throw new IllegalStateException(
                        "Map initial definition used before hash acceptance");
            }
        }
    }

    @Operation
    public static final class PrepareMapInitialDefinition {
        @Specialization
        public static PreparedMapInitialDefinition perform(
                ProtosActivation activation,
                Object map,
                Object key,
                Object value) {
            return new PreparedMapInitialDefinition(
                    map,
                    key,
                    value,
                    activation);
        }
    }

    @Operation
    public static final class EnterMapInitialDefinitionComparison {
        @Specialization
        public static void perform(
                PreparedMapInitialDefinition prepared) {
            prepared.enterComparison();
        }
    }

    @Operation
    public static final class LeaveMapInitialDefinitionComparison {
        @Specialization
        public static void perform(
                PreparedMapInitialDefinition prepared) {
            prepared.leaveComparison();
        }
    }

    @Operation
    public static final class PrepareMapInitialDefinitionHashCall {
        @Specialization
        public static PreparedClosureCall perform(
                PreparedMapInitialDefinition prepared) {
            return prepared.prepareHash();
        }
    }

    @Operation
    public static final class AcceptMapInitialDefinitionHashResult {
        @Specialization
        public static void perform(
                PreparedMapInitialDefinition prepared,
                Object result) {
            prepared.acceptHash(result);
        }
    }

    @Operation
    public static final class MapInitialDefinitionNeedsEquality {
        @Specialization
        public static boolean perform(
                PreparedMapInitialDefinition prepared) {
            return prepared.needsEquality();
        }
    }

    @Operation
    public static final class PrepareMapInitialDefinitionEqualityCall {
        @Specialization
        public static PreparedClosureCall perform(
                PreparedMapInitialDefinition prepared) {
            return prepared.prepareEquality();
        }
    }

    @Operation
    public static final class AcceptMapInitialDefinitionEqualityResult {
        @Specialization
        public static void perform(
                PreparedMapInitialDefinition prepared,
                Object result) {
            prepared.acceptEquality(result);
        }
    }

    @Operation
    public static final class FinishMapInitialDefinition {
        @Specialization
        public static void perform(
                PreparedMapInitialDefinition prepared) {
            prepared.finish();
        }
    }

    static final class PreparedMapAtPutCall {
        private final ProtosMapValue map;
        private final Object key;
        private final Object newValue;
        private final ProtosActivation activation;
        private ProtosNumericHashKey queryHash;
        private List<ProtosMapValue.Entry> candidates;
        private int index;
        private ProtosMapValue.Entry match;
        private boolean hashAccepted;
        private boolean comparisonEntered;

        PreparedMapAtPutCall(
                Object receiver,
                List<?> supplied,
                ProtosActivation activation) {
            this.activation = java.util.Objects.requireNonNull(activation, "activation");
            if (!(receiver instanceof ProtosMapValue mapValue)
                    || supplied.size() != 2) {
                throw ProtosCoreErrors.signal(
                        activation,
                        ProtosCoreErrors.newError(activation));
            }
            this.map = mapValue;
            ProtosStandardMapProtocol.requireMutationEntryForStructured(map, activation);
            this.key = supplied.get(0);
            this.newValue = supplied.get(1);
        }

        void enterComparison() {
            if (comparisonEntered) {
                throw new IllegalStateException(
                        "Map.atPut attempted to nest its own comparison scope");
            }
            map.enterComparison();
            comparisonEntered = true;
        }

        void leaveComparison() {
            if (!comparisonEntered) {
                throw new IllegalStateException(
                        "Map.atPut comparison scope left without entry");
            }
            map.leaveComparison();
            comparisonEntered = false;
        }

        PreparedClosureCall prepareHash() {
            if (hashAccepted) {
                throw new IllegalStateException(
                        "Map.atPut hash callback prepared after hash acceptance");
            }
            return prepareSend(key, "hash", activation, List.of());
        }

        void acceptHash(Object result) {
            if (comparisonEntered) {
                throw new IllegalStateException(
                        "Map.atPut hash result accepted before comparison-scope exit");
            }
            if (hashAccepted) {
                throw new IllegalStateException(
                        "Map.atPut hash result accepted twice");
            }
            queryHash =
                    ProtosStandardMapProtocol.requireHashResultForStructured(
                            result,
                            activation);
            candidates = map.candidatesForRecordedHash(queryHash);
            hashAccepted = true;
        }

        boolean needsEquality() {
            requireHashAccepted();
            if (match != null) {
                return false;
            }
            return index < candidates.size();
        }

        PreparedClosureCall prepareEquality() {
            if (!needsEquality()) {
                throw new IllegalStateException(
                        "Map.atPut equality callback requested without a candidate");
            }
            return prepareSend(
                    key,
                    "==",
                    activation,
                    List.of(candidates.get(index).key()));
        }

        void acceptEquality(Object result) {
            if (comparisonEntered) {
                throw new IllegalStateException(
                        "Map.atPut equality result accepted before comparison-scope exit");
            }
            if (!needsEquality()) {
                throw new IllegalStateException(
                        "Map.atPut equality result accepted without a candidate");
            }
            boolean equal =
                    ProtosStandardMapProtocol.requireEqualityResultForStructured(
                            result,
                            activation);
            if (equal) {
                match = candidates.get(index);
            } else {
                index++;
            }
        }

        Object finish() {
            requireHashAccepted();
            if (comparisonEntered) {
                throw new IllegalStateException(
                        "Map.atPut finished with an active comparison scope");
            }
            if (needsEquality()) {
                throw new IllegalStateException(
                        "Map.atPut finished before candidate exhaustion");
            }
            if (match != null) {
                if (map.isFrozen()) {
                    throw ProtosCoreErrors.signal(
                            activation,
                            ProtosCoreErrors.newError(activation));
                }
                map.replaceValue(match, newValue);
                return newValue;
            }
            if (!map.isOpen()) {
                throw ProtosCoreErrors.signal(
                        activation,
                        ProtosCoreErrors.newError(activation));
            }
            map.append(key, queryHash, newValue);
            return newValue;
        }

        private void requireHashAccepted() {
            if (!hashAccepted) {
                throw new IllegalStateException(
                        "Map.atPut used before hash acceptance");
            }
        }
    }

    @Operation
    public static final class IsStructuredMapAtPutCall {
        @Specialization
        public static boolean ordinary(OrdinarySourceCall prepared) {
            return prepared.isStructuredMapAtPut();
        }

        @Specialization
        public static boolean nativeCall(NativeCall prepared) {
            return prepared.isStructuredMapAtPut();
        }

        @Specialization
        public static boolean immediate(ImmediateResultCall prepared) {
            return prepared.isStructuredMapAtPut();
        }

        @Specialization
        public static boolean moduleInitialization(ModuleInitializationCall prepared) {
            return prepared.isStructuredMapAtPut();
        }
    }

    @Operation
    public static final class PrepareStructuredMapAtPutCall {
        @Specialization
        public static PreparedMapAtPutCall ordinary(OrdinarySourceCall prepared) {
            return prepared.prepareStructuredMapAtPut();
        }

        @Specialization
        public static PreparedMapAtPutCall nativeCall(NativeCall prepared) {
            return prepared.prepareStructuredMapAtPut();
        }

        @Specialization
        public static PreparedMapAtPutCall immediate(ImmediateResultCall prepared) {
            return prepared.prepareStructuredMapAtPut();
        }

        @Specialization
        public static PreparedMapAtPutCall moduleInitialization(ModuleInitializationCall prepared) {
            return prepared.prepareStructuredMapAtPut();
        }
    }

    @Operation
    public static final class EnterStructuredMapAtPutComparison {
        @Specialization
        public static void perform(PreparedMapAtPutCall prepared) {
            prepared.enterComparison();
        }
    }

    @Operation
    public static final class LeaveStructuredMapAtPutComparison {
        @Specialization
        public static void perform(PreparedMapAtPutCall prepared) {
            prepared.leaveComparison();
        }
    }

    @Operation
    public static final class PrepareStructuredMapAtPutHashCall {
        @Specialization
        public static PreparedClosureCall perform(PreparedMapAtPutCall prepared) {
            return prepared.prepareHash();
        }
    }

    @Operation
    public static final class AcceptStructuredMapAtPutHashResult {
        @Specialization
        public static void perform(PreparedMapAtPutCall prepared, Object result) {
            prepared.acceptHash(result);
        }
    }

    @Operation
    public static final class StructuredMapAtPutNeedsEquality {
        @Specialization
        public static boolean perform(PreparedMapAtPutCall prepared) {
            return prepared.needsEquality();
        }
    }

    @Operation
    public static final class PrepareStructuredMapAtPutEqualityCall {
        @Specialization
        public static PreparedClosureCall perform(PreparedMapAtPutCall prepared) {
            return prepared.prepareEquality();
        }
    }

    @Operation
    public static final class AcceptStructuredMapAtPutEqualityResult {
        @Specialization
        public static void perform(PreparedMapAtPutCall prepared, Object result) {
            prepared.acceptEquality(result);
        }
    }

    @Operation
    public static final class FinishStructuredMapAtPut {
        @Specialization
        public static Object perform(PreparedMapAtPutCall prepared) {
            return prepared.finish();
        }
    }

    static final class PreparedMapRemoveCall {
        private final ProtosMapValue map;
        private final Object key;
        private final ProtosActivation activation;
        private ProtosNumericHashKey queryHash;
        private List<ProtosMapValue.Entry> candidates;
        private int index;
        private ProtosMapValue.Entry match;
        private boolean hashAccepted;
        private boolean comparisonEntered;

        PreparedMapRemoveCall(
                Object receiver,
                List<?> supplied,
                ProtosActivation activation) {
            this.activation = java.util.Objects.requireNonNull(activation, "activation");
            if (!(receiver instanceof ProtosMapValue mapValue)
                    || supplied.size() != 1) {
                throw ProtosCoreErrors.signal(
                        activation,
                        ProtosCoreErrors.newError(activation));
            }
            this.map = mapValue;
            ProtosStandardMapProtocol.requireMutationEntryForStructured(map, activation);
            if (!map.isOpen()) {
                throw ProtosCoreErrors.signal(
                        activation,
                        ProtosCoreErrors.newError(activation));
            }
            this.key = supplied.get(0);
        }

        void enterComparison() {
            if (comparisonEntered) {
                throw new IllegalStateException(
                        "Map.remove attempted to nest its own comparison scope");
            }
            map.enterComparison();
            comparisonEntered = true;
        }

        void leaveComparison() {
            if (!comparisonEntered) {
                throw new IllegalStateException(
                        "Map.remove comparison scope left without entry");
            }
            map.leaveComparison();
            comparisonEntered = false;
        }

        PreparedClosureCall prepareHash() {
            if (hashAccepted) {
                throw new IllegalStateException(
                        "Map.remove hash callback prepared after hash acceptance");
            }
            return prepareSend(key, "hash", activation, List.of());
        }

        void acceptHash(Object result) {
            if (comparisonEntered) {
                throw new IllegalStateException(
                        "Map.remove hash result accepted before comparison-scope exit");
            }
            if (hashAccepted) {
                throw new IllegalStateException(
                        "Map.remove hash result accepted twice");
            }
            queryHash =
                    ProtosStandardMapProtocol.requireHashResultForStructured(
                            result,
                            activation);
            candidates = map.candidatesForRecordedHash(queryHash);
            hashAccepted = true;
        }

        boolean needsEquality() {
            requireHashAccepted();
            if (match != null) {
                return false;
            }
            return index < candidates.size();
        }

        PreparedClosureCall prepareEquality() {
            if (!needsEquality()) {
                throw new IllegalStateException(
                        "Map.remove equality callback requested without a candidate");
            }
            return prepareSend(
                    key,
                    "==",
                    activation,
                    List.of(candidates.get(index).key()));
        }

        void acceptEquality(Object result) {
            if (comparisonEntered) {
                throw new IllegalStateException(
                        "Map.remove equality result accepted before comparison-scope exit");
            }
            if (!needsEquality()) {
                throw new IllegalStateException(
                        "Map.remove equality result accepted without a candidate");
            }
            boolean equal =
                    ProtosStandardMapProtocol.requireEqualityResultForStructured(
                            result,
                            activation);
            if (equal) {
                match = candidates.get(index);
            } else {
                index++;
            }
        }

        Object finish() {
            requireHashAccepted();
            if (comparisonEntered) {
                throw new IllegalStateException(
                        "Map.remove finished with an active comparison scope");
            }
            if (needsEquality()) {
                throw new IllegalStateException(
                        "Map.remove finished before candidate exhaustion");
            }
            if (match == null || !map.isOpen()) {
                throw ProtosCoreErrors.signal(
                        activation,
                        ProtosCoreErrors.newError(activation));
            }
            return map.remove(match);
        }

        private void requireHashAccepted() {
            if (!hashAccepted) {
                throw new IllegalStateException(
                        "Map.remove used before hash acceptance");
            }
        }
    }

    @Operation
    public static final class IsStructuredMapRemoveCall {
        @Specialization
        public static boolean ordinary(OrdinarySourceCall prepared) {
            return prepared.isStructuredMapRemove();
        }

        @Specialization
        public static boolean nativeCall(NativeCall prepared) {
            return prepared.isStructuredMapRemove();
        }

        @Specialization
        public static boolean immediate(ImmediateResultCall prepared) {
            return prepared.isStructuredMapRemove();
        }

        @Specialization
        public static boolean moduleInitialization(ModuleInitializationCall prepared) {
            return prepared.isStructuredMapRemove();
        }
    }

    @Operation
    public static final class PrepareStructuredMapRemoveCall {
        @Specialization
        public static PreparedMapRemoveCall ordinary(OrdinarySourceCall prepared) {
            return prepared.prepareStructuredMapRemove();
        }

        @Specialization
        public static PreparedMapRemoveCall nativeCall(NativeCall prepared) {
            return prepared.prepareStructuredMapRemove();
        }

        @Specialization
        public static PreparedMapRemoveCall immediate(ImmediateResultCall prepared) {
            return prepared.prepareStructuredMapRemove();
        }

        @Specialization
        public static PreparedMapRemoveCall moduleInitialization(
                ModuleInitializationCall prepared) {
            return prepared.prepareStructuredMapRemove();
        }
    }

    @Operation
    public static final class EnterStructuredMapRemoveComparison {
        @Specialization
        public static void perform(PreparedMapRemoveCall prepared) {
            prepared.enterComparison();
        }
    }

    @Operation
    public static final class LeaveStructuredMapRemoveComparison {
        @Specialization
        public static void perform(PreparedMapRemoveCall prepared) {
            prepared.leaveComparison();
        }
    }

    @Operation
    public static final class PrepareStructuredMapRemoveHashCall {
        @Specialization
        public static PreparedClosureCall perform(PreparedMapRemoveCall prepared) {
            return prepared.prepareHash();
        }
    }

    @Operation
    public static final class AcceptStructuredMapRemoveHashResult {
        @Specialization
        public static void perform(PreparedMapRemoveCall prepared, Object result) {
            prepared.acceptHash(result);
        }
    }

    @Operation
    public static final class StructuredMapRemoveNeedsEquality {
        @Specialization
        public static boolean perform(PreparedMapRemoveCall prepared) {
            return prepared.needsEquality();
        }
    }

    @Operation
    public static final class PrepareStructuredMapRemoveEqualityCall {
        @Specialization
        public static PreparedClosureCall perform(PreparedMapRemoveCall prepared) {
            return prepared.prepareEquality();
        }
    }

    @Operation
    public static final class AcceptStructuredMapRemoveEqualityResult {
        @Specialization
        public static void perform(PreparedMapRemoveCall prepared, Object result) {
            prepared.acceptEquality(result);
        }
    }

    @Operation
    public static final class FinishStructuredMapRemove {
        @Specialization
        public static Object perform(PreparedMapRemoveCall prepared) {
            return prepared.finish();
        }
    }

    static final class PreparedMapEachCall implements PreparedAssociationEachCall {
        private final ProtosMapValue map;
        private final List<java.util.Map.Entry<Object, Object>> snapshot;
        private final Object block;
        private final ProtosActivation activation;
        private int index;

        PreparedMapEachCall(
                Object receiver,
                List<?> supplied,
                ProtosActivation activation) {
            this.activation = java.util.Objects.requireNonNull(activation, "activation");
            if (!(receiver instanceof ProtosMapValue value)
                    || supplied.size() != 1) {
                throw ProtosCoreErrors.signal(
                        activation,
                        ProtosCoreErrors.newError(activation));
            }
            this.map = value;
            this.block = supplied.get(0);
            ProtosStandardMapProtocol.requireInvokableForStructured(
                    block,
                    activation);
            this.snapshot = List.copyOf(value.associationSnapshot());
        }

        @Override
        public boolean hasNext() {
            return index < snapshot.size();
        }

        @Override
        public PreparedClosureCall prepareCurrent() {
            if (!hasNext()) {
                throw new IllegalStateException(
                        "Map.each callback requested after snapshot exhaustion");
            }
            java.util.Map.Entry<Object, Object> entry = snapshot.get(index);
            return prepareClosureCall(
                    block,
                    List.of(entry.getKey(), entry.getValue()),
                    activation);
        }

        @Override
        public PreparedInlineLiteralCall prepareInlineCurrent() {
            if (!hasNext()) {
                throw new IllegalStateException(
                        "Map.each callback requested after snapshot exhaustion");
            }
            java.util.Map.Entry<Object, Object> entry = snapshot.get(index);
            return prepareInlineLiteralCall(
                    block,
                    List.of(entry.getKey(), entry.getValue()),
                    activation);
        }

        @Override
        public void advance() {
            if (!hasNext()) {
                throw new IllegalStateException(
                        "Map.each cursor advanced after snapshot exhaustion");
            }
            index++;
        }

        @Override
        public Object finish() {
            if (hasNext()) {
                throw new IllegalStateException(
                        "Map.each finished before snapshot exhaustion");
            }
            return map;
        }

        @Override
        public Object callback() {
            return block;
        }
    }

    @Operation
    public static final class IsStructuredMapEachCall {
        @Specialization
        public static boolean ordinary(OrdinarySourceCall prepared) {
            return prepared.isStructuredMapEach();
        }

        @Specialization
        public static boolean nativeCall(NativeCall prepared) {
            return prepared.isStructuredMapEach();
        }

        @Specialization
        public static boolean immediate(ImmediateResultCall prepared) {
            return prepared.isStructuredMapEach();
        }

        @Specialization
        public static boolean moduleInitialization(ModuleInitializationCall prepared) {
            return prepared.isStructuredMapEach();
        }
    }

    @Operation
    public static final class PrepareStructuredMapEachCall {
        @Specialization
        public static PreparedMapEachCall ordinary(OrdinarySourceCall prepared) {
            return prepared.prepareStructuredMapEach();
        }

        @Specialization
        public static PreparedMapEachCall nativeCall(NativeCall prepared) {
            return prepared.prepareStructuredMapEach();
        }

        @Specialization
        public static PreparedMapEachCall immediate(ImmediateResultCall prepared) {
            return prepared.prepareStructuredMapEach();
        }

        @Specialization
        public static PreparedMapEachCall moduleInitialization(ModuleInitializationCall prepared) {
            return prepared.prepareStructuredMapEach();
        }
    }

    @Operation
    public static final class StructuredMapEachHasNext {
        @Specialization
        public static boolean perform(PreparedMapEachCall prepared) {
            return prepared.hasNext();
        }
    }

    @Operation
    public static final class PrepareStructuredMapEachEntryCall {
        @Specialization
        public static PreparedClosureCall perform(PreparedMapEachCall prepared) {
            return prepared.prepareCurrent();
        }
    }

    @Operation
    public static final class AdvanceStructuredMapEach {
        @Specialization
        public static void perform(PreparedMapEachCall prepared) {
            prepared.advance();
        }
    }

    @Operation
    public static final class FinishStructuredMapEach {
        @Specialization
        public static Object perform(PreparedMapEachCall prepared) {
            return prepared.finish();
        }
    }

    @Operation
    public static final class IsStructuredBooleanCall {
        @Specialization
        public static boolean ordinary(OrdinarySourceCall prepared) {
            return prepared.isStructuredBoolean();
        }

        @Specialization
        public static boolean nativeCall(NativeCall prepared) {
            return prepared.isStructuredBoolean();
        }

        @Specialization
        public static boolean immediate(ImmediateResultCall prepared) {
            return prepared.isStructuredBoolean();
        }

        @Specialization
        public static boolean moduleInitialization(ModuleInitializationCall prepared) {
            return prepared.isStructuredBoolean();
        }
    }

    @Operation
    public static final class PrepareStructuredBooleanCall {
        @Specialization
        public static PreparedBooleanCall ordinary(OrdinarySourceCall prepared) {
            return prepared.prepareStructuredBoolean();
        }

        @Specialization
        public static PreparedBooleanCall nativeCall(NativeCall prepared) {
            return prepared.prepareStructuredBoolean();
        }

        @Specialization
        public static PreparedBooleanCall immediate(ImmediateResultCall prepared) {
            return prepared.prepareStructuredBoolean();
        }

        @Specialization
        public static PreparedBooleanCall moduleInitialization(ModuleInitializationCall prepared) {
            return prepared.prepareStructuredBoolean();
        }
    }

    @Operation
    public static final class StructuredBooleanHasCallback {
        @Specialization
        public static boolean perform(PreparedBooleanCall prepared) {
            return prepared.hasCallback();
        }
    }

    @Operation
    public static final class PrepareStructuredBooleanCallbackCall {
        @Specialization
        public static PreparedClosureCall perform(PreparedBooleanCall prepared) {
            return prepared.prepareCallback();
        }
    }

    @Operation
    public static final class StructuredBooleanImmediateResult {
        @Specialization
        public static Object perform(PreparedBooleanCall prepared) {
            return prepared.immediateResult();
        }
    }

    @Operation
    public static final class FinishStructuredBooleanCallback {
        @Specialization
        public static Object perform(PreparedBooleanCall prepared, Object result) {
            return prepared.finishCallback(result);
        }
    }

    @Operation
    public static final class RethrowTruffleException {
        @Specialization
        public static void perform(AbstractTruffleException exception) {
            throw exception;
        }
    }

    @Operation
    public static final class IsCancellationUnwindActive {
        @Specialization
        public static boolean perform(ProtosActivation activation) {
            ProtosTask task = activation.task().orElse(null);
            return task != null
                    && task.cancellationPhase()
                            == ProtosTask.CancellationPhase.UNWINDING;
        }
    }

    @Operation
    public static final class SupersedeCancellationUnwindIfActive {
        @Specialization
        public static void perform(
                ProtosActivation activation,
                boolean cancellationWasUnwindingBeforeCleanup) {
            if (!cancellationWasUnwindingBeforeCleanup) {
                return;
            }
            ProtosTask task = activation.task().orElse(null);
            if (task == null
                    || task.cancellationPhase()
                            != ProtosTask.CancellationPhase.UNWINDING) {
                return;
            }
            if (!task.supersedeCancellationUnwind()) {
                throw new IllegalStateException(
                        "escaping cleanup transfer could not supersede cancellation unwind");
            }
        }
    }

    @Operation
    public static final class IsStructuredErrorHandlerCall {
        @Specialization
        public static boolean ordinary(OrdinarySourceCall prepared) {
            return prepared.isStructuredErrorHandler();
        }

        @Specialization
        public static boolean nativeCall(NativeCall prepared) {
            return prepared.isStructuredErrorHandler();
        }

        @Specialization
        public static boolean immediate(ImmediateResultCall prepared) {
            return prepared.isStructuredErrorHandler();
        }

        @Specialization
        public static boolean moduleInitialization(ModuleInitializationCall prepared) {
            return prepared.isStructuredErrorHandler();
        }
    }

    @Operation
    public static final class PrepareStructuredErrorHandlerCall {
        @Specialization
        public static PreparedErrorHandlerCall ordinary(OrdinarySourceCall prepared) {
            return prepared.prepareStructuredErrorHandler();
        }

        @Specialization
        public static PreparedErrorHandlerCall nativeCall(NativeCall prepared) {
            return prepared.prepareStructuredErrorHandler();
        }

        @Specialization
        public static PreparedErrorHandlerCall immediate(ImmediateResultCall prepared) {
            return prepared.prepareStructuredErrorHandler();
        }

        @Specialization
        public static PreparedErrorHandlerCall moduleInitialization(
                ModuleInitializationCall prepared) {
            return prepared.prepareStructuredErrorHandler();
        }
    }

    @Operation
    public static final class LoadStructuredErrorHandlerBodyCall {
        @Specialization
        public static PreparedClosureCall perform(PreparedErrorHandlerCall prepared) {
            return prepared.body();
        }
    }

    @Operation
    public static final class PrepareSelectedStructuredErrorHandlerCall {
        @Specialization
        public static PreparedClosureCall perform(
                PreparedErrorHandlerCall prepared,
                AbstractTruffleException exception) {
            return prepared.selectHandler(exception);
        }
    }

    @Operation
    public static final class LeaveStructuredErrorHandlerFrame {
        @Specialization
        public static void perform(PreparedErrorHandlerCall prepared) {
            prepared.leave();
        }
    }

    /**
     * TEST009-H: the terminal lifecycle operations specialize on each concrete
     * {@link PreparedClosureCall} representation so partial evaluation binds the
     * lifecycle call statically instead of retaining an interface dispatch. The
     * behavior itself stays owned by each representation.
     */
    @Operation
    public static final class CompleteClosureCall {
        @Specialization
        public static void ordinary(OrdinarySourceCall prepared) {
            prepared.complete();
        }

        @Specialization
        public static void nativeCall(NativeCall prepared) {
            prepared.complete();
        }

        @Specialization
        public static void immediate(ImmediateResultCall prepared) {
            prepared.complete();
        }

        @Specialization
        public static void moduleInitialization(ModuleInitializationCall prepared) {
            prepared.complete();
        }
    }

    static final class PreparedArgumentVector {
        private final ArrayList<Object> values = new ArrayList<>();

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
            return values.size() == 1 ? values.get(0) : null;
        }

        List<Object> snapshot() {
            return List.copyOf(values);
        }
    }




    @Operation
    public static final class CreateSuppliedArgumentVector {
        @Specialization
        public static PreparedArgumentVector perform() {
            return new PreparedArgumentVector();
        }
    }

    @Operation
    public static final class AppendSuppliedArgument {
        @Specialization
        public static void perform(
                PreparedArgumentVector vector,
                Object value) {
            vector.append(value);
        }
    }

    @Operation
    public static final class AppendSpreadSuppliedArgument {
        @Specialization
        public static void perform(
                PreparedArgumentVector vector,
                Object value,
                ProtosActivation caller) {
            vector.appendSpread(value, caller);
        }
    }

    @Operation
    public static final class PrepareClosureCallVector {
        @Specialization
        public static PreparedClosureCall perform(
                Object receiver,
                ProtosActivation caller,
                PreparedArgumentVector supplied) {
            return prepareClosureCall(
                    receiver,
                    supplied.snapshot(),
                    caller);
        }
    }

    static PreparedClosureCall prepareTaskOwnedDirectClosureIfBytecode(
            ProtosClosureValue closure,
            List<?> supplied,
            ProtosActivation creator,
            ProtosTask task) {
        java.util.Objects.requireNonNull(closure, "closure");
        java.util.Objects.requireNonNull(supplied, "supplied");
        java.util.Objects.requireNonNull(creator, "creator");
        java.util.Objects.requireNonNull(task, "task");
        if (closure.nativeBody().isPresent()) {
            return null;
        }
        ProtosClosureExecutionPlan plan = taskOwnedBytecodePlan(closure);
        if (plan == null) {
            return null;
        }
        /*
         * PERF025 / PLAT040 F': the callee materializes its exact activation,
         * attached to this exact Task, after target entry; its guest Context
         * and supplied guest Array stay deferred.
         */
        return PreparedClosureCall.ordinaryCompact(
                plan.bytecodeActivationTargetForComposition(),
                ProtosFrameArguments.compactDirectClosureCall(
                        closure,
                        creator,
                        task,
                        supplied.toArray()));
    }


    static PreparedClosureCall prepareTaskOwnedDirectNativeForCPrime(
            ProtosClosureValue closure,
            List<?> supplied,
            ProtosActivation creator,
            ProtosTask task) {
        java.util.Objects.requireNonNull(closure, "closure");
        java.util.Objects.requireNonNull(supplied, "supplied");
        java.util.Objects.requireNonNull(creator, "creator");
        java.util.Objects.requireNonNull(task, "task");
        if (closure.nativeBody().isEmpty()) {
            throw new IllegalArgumentException(
                    "Task native C-prime entry requires a native Closure");
        }

        /*
         * Direct Closure execution has already selected the exact Closure
         * identity. Preserve implementation provenance for extracted/bound
         * standard callback/control Closures exactly as PLAT019/PLAT021/PLAT028
         * require; no selector/home inference is introduced here.
         */
        rejectComposedInvocationProjection(closure);
        ProtosActivation activation =
                ProtosActivation.forClosureInvocation(
                        closure,
                        supplied,
                        creator.prelude().orElse(null),
                        creator.actorModuleState(),
                        creator.currentModuleKey().orElse(null),
                        creator.executionDomain());
        activation.attachTask(task);
        return finishPreparingComposedCallByImplementation(
                closure,
                supplied,
                activation);
    }

    /**
     * PLAT054-3E2: Task-free counterpart of {@link #prepareTaskOwnedSelectedNativeForCPrime} for an
     * outermost standard-embedding host entry whose Closure has no compact target. {@code selected}
     * is the ordinary D013 {@code call} selection on {@code receiver}; only the exact canonical
     * selection executes the receiver Closure itself (PLAT017). The callee inherits the caller's
     * dynamic-control state exactly as a Task-free composed send does, and the returned call runs
     * through the shared C-prime entry root, so structured control and suspension behave as on the
     * compact path.
     */
    static PreparedClosureCall prepareHostEntrySelectedCall(
            Object receiver,
            ProtosSlotLookupResult selected,
            List<?> supplied,
            ProtosActivation caller) {
        java.util.Objects.requireNonNull(receiver, "receiver");
        java.util.Objects.requireNonNull(selected, "selected");
        java.util.Objects.requireNonNull(supplied, "supplied");
        java.util.Objects.requireNonNull(caller, "caller");
        if (caller.task().isPresent()) {
            throw new IllegalArgumentException("host entry provenance must not own a Task");
        }
        if (!(selected.value() instanceof ProtosClosureValue closure)) {
            throw new ProtosSignalException(ProtosCoreErrors.newError(caller));
        }
        ProtosActivation activation;
        ProtosClosureValue effective;
        if (receiver instanceof ProtosClosureValue targetClosure
                && ProtosStandardObjectProtocol.isCanonicalStandardCallSelection(
                        closure,
                        selected.home())) {
            effective = targetClosure;
            activation =
                    ProtosActivation.forClosureInvocation(
                            targetClosure,
                            supplied,
                            caller.prelude().orElse(null),
                            caller.actorModuleState(),
                            caller.currentModuleKey().orElse(null),
                            caller.executionDomain());
        } else {
            effective = closure;
            activation =
                    ProtosActivation.forImmediateMethodInvocation(
                            closure,
                            supplied,
                            receiver,
                            selected.home(),
                            caller.prelude().orElse(null),
                            caller.actorModuleState(),
                            caller.currentModuleKey().orElse(null),
                            caller.executionDomain());
        }
        rejectComposedInvocationProjection(effective);
        activation.inheritDynamicControlState(caller);
        return finishPreparingComposedCallByImplementation(effective, supplied, activation);
    }

    static PreparedClosureCall prepareTaskOwnedSelectedCallIfBytecode(
            Object receiver,
            ProtosSlotLookupResult selected,
            List<?> supplied,
            ProtosActivation creator,
            ProtosTask task) {
        java.util.Objects.requireNonNull(receiver, "receiver");
        java.util.Objects.requireNonNull(selected, "selected");
        java.util.Objects.requireNonNull(supplied, "supplied");
        java.util.Objects.requireNonNull(creator, "creator");
        java.util.Objects.requireNonNull(task, "task");

        if (!(selected.value() instanceof ProtosClosureValue closure)) {
            throw new ProtosSignalException(ProtosCoreErrors.newError(creator));
        }

        /*
         * Preserve PLAT017 exactly: ordinary D013 lookup has already selected
         * Object.call. Only the exact canonical selection may invoke the Closure
         * receiver intrinsically; aliases/overrides remain normal methods.
         */
        if (receiver instanceof ProtosClosureValue targetClosure
                && ProtosStandardObjectProtocol.isCanonicalStandardCallSelection(
                        closure,
                        selected.home())) {
            return prepareTaskOwnedDirectClosureIfBytecode(
                    targetClosure,
                    supplied,
                    creator,
                    task);
        }

        if (closure.nativeBody().isPresent()) {
            return null;
        }
        ProtosClosureExecutionPlan plan = taskOwnedBytecodePlan(closure);
        if (plan == null) {
            return null;
        }
        ProtosActivation activation =
                ProtosActivation.forImmediateMethodInvocation(
                        closure,
                        supplied,
                        receiver,
                        selected.home(),
                        creator.prelude().orElse(null),
                        creator.actorModuleState(),
                        creator.currentModuleKey().orElse(null),
                        creator.executionDomain());
        activation.attachTask(task);
        return PreparedClosureCall.ordinary(
                plan.bytecodeActivationTargetForComposition(),
                activation);
    }


    static PreparedClosureCall prepareTaskOwnedSelectedNativeForCPrime(
            Object receiver,
            ProtosSlotLookupResult selected,
            List<?> supplied,
            ProtosActivation creator,
            ProtosTask task) {
        java.util.Objects.requireNonNull(receiver, "receiver");
        java.util.Objects.requireNonNull(selected, "selected");
        java.util.Objects.requireNonNull(supplied, "supplied");
        java.util.Objects.requireNonNull(creator, "creator");
        java.util.Objects.requireNonNull(task, "task");

        if (!(selected.value() instanceof ProtosClosureValue closure)) {
            throw new ProtosSignalException(ProtosCoreErrors.newError(creator));
        }

        /*
         * PLAT017 remains post-lookup authority. Only the exact canonical
         * Object.call selection may elide that native bridge and execute the
         * Closure receiver itself.
         */
        if (receiver instanceof ProtosClosureValue targetClosure
                && ProtosStandardObjectProtocol.isCanonicalStandardCallSelection(
                        closure,
                        selected.home())) {
            return prepareTaskOwnedDirectNativeForCPrime(
                    targetClosure,
                    supplied,
                    creator,
                    task);
        }

        if (closure.nativeBody().isEmpty()) {
            throw new IllegalArgumentException(
                    "Task selected-native C-prime entry requires a native effective target");
        }

        /*
         * Preserve PLAT025 for the exact canonical import facility. The caller
         * remains the original semantic caller for resolution/error attribution;
         * the explicit Task is supplied separately so no shared creator
         * activation is mutated merely to host module initialization.
         */
        ProtosModuleRuntime standardImportRuntime =
                ProtosStandardImportProtocol.selectedRuntimeForBytecodeIntrinsic(
                        receiver,
                        closure,
                        selected.home(),
                        creator.prelude().orElse(null));
        if (standardImportRuntime != null) {
            return PreparedClosureCall.moduleInitialization(
                    standardImportRuntime.prepareBytecodeImportForTask(
                            supplied,
                            creator,
                            task));
        }

        rejectComposedInvocationProjection(closure);
        ProtosActivation activation =
                ProtosActivation.forImmediateMethodInvocation(
                        closure,
                        supplied,
                        receiver,
                        selected.home(),
                        creator.prelude().orElse(null),
                        creator.actorModuleState(),
                        creator.currentModuleKey().orElse(null),
                        creator.executionDomain());
        activation.attachTask(task);
        return finishPreparingComposedCallByImplementation(
                closure,
                supplied,
                activation);
    }

    private static ProtosClosureExecutionPlan taskOwnedBytecodePlan(
            ProtosClosureValue closure) {
        return taskOwnedBytecodePlan(
                closure,
                ProtosLanguageContext.currentIfEnteredForRuntime());
    }

    private static ProtosClosureExecutionPlan taskOwnedBytecodePlan(
            ProtosClosureValue closure,
            ProtosLanguageContext enteredContext) {
        ProtosClosureExecutionPlan template =
                closure.executionPlanForRuntimeInvocation();

        if (enteredContext != null) {
            boolean foreignContextPlan =
                    template.language().orElseThrow()
                            != enteredContext.languageForRuntime();
            if (closure.requiresContextLocalExecutionProjectionForRuntime()
                    || foreignContextPlan) {
                if (template.source().isEmpty()) {
                    return null;
                }
                return enteredContext.bytecodeExecutionPlanForEnteredClosure(
                        closure,
                        template);
            }
        } else if (closure.requiresContextLocalExecutionProjectionForRuntime()) {
            return null;
        }

        return template;
    }

    /**
     * PERF014: the stable executable-identity key and Context-owned activation
     * target admitted by direct Closure-call invocation ({@code operation()}).
     *
     * <p>Admission requires exactly the canonical {@code Object.call} selection
     * (see {@link ProtosStandardObjectProtocol#isCanonicalStandardCallSelection})
     * on a non-native, ordinary source-backed Closure receiver whose Context-
     * owned Bytecode target can be derived. Any override, shadowing, native
     * body, or projection failure misses here and remains on the exact generic
     * {@link #prepareClosureCall} path.
     */
    record GuardedDirectClosureCallTarget(
            ProtosClosureValue closure,
            RootCallTarget target,
            Assumption stability) {}

    /**
     * Resolves and classifies only while establishing a specialization for one
     * direct Closure-call site. Reuses {@link ProtosValueLookup#lookupGuarded}
     * exactly as {@link PrepareSendArguments#createGuardedSend} does for
     * ordinary sends, and reuses
     * {@link PrepareSendArguments#fastOrdinarySendTarget} for the
     * Context-owned target derivation (including foreign-Context projection),
     * so admission stays exactly as discriminating as the institutions I072-A
     * already established. The valid hit below never repeats this lookup or
     * the generic {@code finishPreparingComposedCallByImplementation}
     * structured-protocol classification.
     */
    static GuardedDirectClosureCallTarget createGuardedDirectClosureCall(
            Object receiver,
            ProtosActivation caller,
            ProtosLanguageContext enteredContext) {
        return createGuardedDirectClosureCallForPrelude(
                receiver, caller.preludeOrNullForRuntime(), enteredContext);
    }

    static GuardedDirectClosureCallTarget createGuardedDirectClosureCallForPrelude(
            Object receiver,
            ProtosPrelude prelude,
            ProtosLanguageContext enteredContext) {
        if (enteredContext == null || !(receiver instanceof ProtosClosureValue targetClosure)) {
            return null;
        }
        ProtosValueLookup.GuardedLookup lookup;
        try {
            lookup = ProtosValueLookup.lookupGuarded(
                    receiver, "call", prelude);
        } catch (UnsupportedOperationException unsupportedRepresentation) {
            return null;
        }
        if (lookup == null) {
            return null;
        }
        ProtosSlotLookupResult selected = lookup.selected();
        if (targetClosure.nativeBody().isPresent()
                || !ProtosStandardObjectProtocol.isCanonicalStandardCallSelection(
                        selected.value(), selected.home())) {
            lookup.stability().invalidate();
            return null;
        }
        RootCallTarget target =
                PrepareSendArguments.fastOrdinarySendTarget(targetClosure, enteredContext);
        if (target == null || !lookup.stability().isValid()) {
            lookup.stability().invalidate();
            return null;
        }
        return new GuardedDirectClosureCallTarget(targetClosure, target, lookup.stability());
    }

    private static final Object[] NO_SUPPLIED_ARGUMENTS = new Object[0];

    /**
     * Builds the ordinary prepared Closure invocation for an admitted direct
     * Closure-call hit (either cache tier below), without repeating
     * structured-protocol classification. PERF025 / PLAT040 F': the hit enters
     * the source target through the compact direct-Closure frame ABI; the
     * callee materializes exactly the activation the generic
     * {@link #prepareDirectClosureCall} would build (same captures, return
     * home and Task/dynamic-control inheritance), with its guest Context and
     * supplied guest Array deferred.
     */
    /**
     * PERF038-H: fixed Call1 path. The final frame is constructed
     * directly; zero and N arguments retain their existing paths.
     */
    static PreparedClosureCall finishDirectClosureCallOne(
            ProtosClosureValue closure,
            RootCallTarget target,
            Object supplied0,
            ProtosActivation caller) {
        ProtosReturnHome returnHome =
                closure.invocationReturnHomeForRuntime();
        RootCallTarget selectedEntry =
                ProtosSemanticBytecodeRootNode.selectSourceEntryTarget(
                        target, 1);
        return PreparedClosureCall.ordinaryCompactSelected(
                selectedEntry,
                ProtosFrameArguments.compactDirectClosureCallOnePrepared(
                        closure, caller, returnHome, supplied0),
                returnHome,
                closure.returnHome().isEmpty());
    }

    /**
     * PERF038-H: admitted direct Call0 needs no arity-rejection
     * selection and no compact-header revalidation.
     */
    static PreparedClosureCall finishDirectClosureCallZero(
            ProtosClosureValue closure,
            RootCallTarget target,
            ProtosActivation caller) {
        ProtosReturnHome returnHome =
                closure.invocationReturnHomeForRuntime();
        return PreparedClosureCall.ordinaryCompactSelected(
                target,
                ProtosFrameArguments.compactDirectClosureCallZeroPrepared(
                        closure, caller, returnHome),
                returnHome,
                closure.returnHome().isEmpty());
    }

    static PreparedClosureCall finishDirectClosureCall(
            ProtosClosureValue closure,
            RootCallTarget target,
            Object[] supplied,
            ProtosActivation caller) {
        return PreparedClosureCall.ordinaryCompact(
                target,
                ProtosFrameArguments.compactDirectClosureCall(
                        closure, caller, null, supplied));
    }

    /**
     * PERF014 second tier: admits a stable direct Closure-call target keyed by
     * the closure's stable {@link CanonicalClosure} definition identity rather
     * than by receiver instance identity, mirroring
     * {@link PrepareSendArguments#fastOrdinarySend}. A distinct
     * {@code ProtosClosureValue} materialization of the same definition (for
     * example a fresh closure rebuilt on every loop iteration, or every
     * recursive call) reuses the same Context-owned target without
     * re-deriving it, while the authoritative D013 {@code call} selection is
     * still performed fresh on every hit (not cached), so a local override on
     * one particular instance is observed exactly as before. This is the
     * institution the identity-keyed first tier
     * ({@link #createGuardedDirectClosureCall}) alone omitted, which left
     * receiver-identity churn paying repeated specialization-establishment
     * cost instead of converging.
     */
    static ProtosClosureValue directClosureCallSelectionOrNull(
            Object receiver, ProtosActivation caller) {
        return directClosureCallSelectionForPreludeOrNull(
                receiver, caller.preludeOrNullForRuntime());
    }

    static ProtosClosureValue directClosureCallSelectionForPreludeOrNull(
            Object receiver, ProtosPrelude prelude) {
        if (!(receiver instanceof ProtosClosureValue targetClosure)) {
            return null;
        }
        java.util.Optional<ProtosSlotLookupResult> selected;
        try {
            selected = ProtosValueLookup.lookup(
                    receiver, "call", prelude);
        } catch (UnsupportedOperationException unsupportedRepresentation) {
            return null;
        }
        if (selected.isEmpty() || targetClosure.nativeBody().isPresent()) {
            return null;
        }
        ProtosSlotLookupResult result = selected.orElseThrow();
        if (!ProtosStandardObjectProtocol.isCanonicalStandardCallSelection(
                result.value(), result.home())) {
            return null;
        }
        return targetClosure;
    }

    static CanonicalClosure directClosureCallDefinitionOrNull(ProtosClosureValue closure) {
        return closure == null ? null : closure.definition();
    }

    @Operation
    public static final class PrepareClosureCall {
        @Specialization(
                guards = {
                    "receiver == cachedReceiver",
                    "enteredContext != null",
                    "enteredContext == cachedContext",
                    "cachedGuarded != null"
                },
                assumptions = "cachedGuarded.stability()",
                limit = "3")
        public static PreparedClosureCall guardedDirect(
                Object receiver,
                ProtosActivation caller,
                @Bind("currentEnteredContext($node)")
                        ProtosLanguageContext enteredContext,
                @Cached("receiver") Object cachedReceiver,
                @Cached("enteredContext") ProtosLanguageContext cachedContext,
                @Cached("createGuardedDirectClosureCall(receiver, caller, enteredContext)")
                        GuardedDirectClosureCallTarget cachedGuarded) {
            return finishDirectClosureCallZero(
                    cachedGuarded.closure(), cachedGuarded.target(), caller);
        }

        @Specialization(
                guards = {
                    "closure != null",
                    "enteredContext != null",
                    "closureDefinition != null",
                    "closureDefinition == cachedClosureDefinition",
                    "enteredContext == cachedContext",
                    "cachedTarget != null"
                },
                limit = "3")
        public static PreparedClosureCall fastDirect(
                Object receiver,
                ProtosActivation caller,
                @Bind("directClosureCallSelectionOrNull(receiver, caller)")
                        ProtosClosureValue closure,
                @Bind("directClosureCallDefinitionOrNull(closure)")
                        CanonicalClosure closureDefinition,
                @Bind("currentEnteredContext($node)")
                        ProtosLanguageContext enteredContext,
                @Cached("closureDefinition") CanonicalClosure cachedClosureDefinition,
                @Cached("enteredContext") ProtosLanguageContext cachedContext,
                @Cached("fastOrdinarySendTarget(closure, enteredContext)")
                        RootCallTarget cachedTarget) {
            return finishDirectClosureCallZero(
                    closure, cachedTarget, caller);
        }

        @Specialization(replaces = {"guardedDirect", "fastDirect"})
        public static PreparedClosureCall perform(Object receiver, ProtosActivation caller) {
            return prepareClosureCall(receiver, List.of(), caller);
        }

        @NonIdempotent
        static ProtosLanguageContext currentEnteredContext(Node node) {
            return ProtosLanguageContext.current(node);
        }

        static GuardedDirectClosureCallTarget createGuardedDirectClosureCall(
                Object receiver, ProtosActivation caller, ProtosLanguageContext enteredContext) {
            return ProtosBytecodeRootNode.createGuardedDirectClosureCall(
                    receiver, caller, enteredContext);
        }

        static ProtosClosureValue directClosureCallSelectionOrNull(
                Object receiver, ProtosActivation caller) {
            return ProtosBytecodeRootNode.directClosureCallSelectionOrNull(receiver, caller);
        }

        static CanonicalClosure directClosureCallDefinitionOrNull(ProtosClosureValue closure) {
            return ProtosBytecodeRootNode.directClosureCallDefinitionOrNull(closure);
        }

        static RootCallTarget fastOrdinarySendTarget(
                ProtosClosureValue closure, ProtosLanguageContext enteredContext) {
            return PrepareSendArguments.fastOrdinarySendTarget(closure, enteredContext);
        }
    }

    @Operation
    public static final class PrepareClosureCallArguments {
        @Specialization(
                guards = {
                    "receiver == cachedReceiver",
                    "enteredContext != null",
                    "enteredContext == cachedContext",
                    "cachedGuarded != null"
                },
                assumptions = "cachedGuarded.stability()",
                limit = "3")
        public static PreparedClosureCall guardedDirect(
                Object receiver,
                ProtosActivation caller,
                @Variadic Object[] supplied,
                @Bind("currentEnteredContext($node)")
                        ProtosLanguageContext enteredContext,
                @Cached("receiver") Object cachedReceiver,
                @Cached("enteredContext") ProtosLanguageContext cachedContext,
                @Cached("createGuardedDirectClosureCall(receiver, caller, enteredContext)")
                        GuardedDirectClosureCallTarget cachedGuarded) {
            return finishDirectClosureCall(
                    cachedGuarded.closure(), cachedGuarded.target(), supplied, caller);
        }

        @Specialization(
                guards = {
                    "closure != null",
                    "enteredContext != null",
                    "closureDefinition != null",
                    "closureDefinition == cachedClosureDefinition",
                    "enteredContext == cachedContext",
                    "cachedTarget != null"
                },
                limit = "3")
        public static PreparedClosureCall fastDirect(
                Object receiver,
                ProtosActivation caller,
                @Variadic Object[] supplied,
                @Bind("directClosureCallSelectionOrNull(receiver, caller)")
                        ProtosClosureValue closure,
                @Bind("directClosureCallDefinitionOrNull(closure)")
                        CanonicalClosure closureDefinition,
                @Bind("currentEnteredContext($node)")
                        ProtosLanguageContext enteredContext,
                @Cached("closureDefinition") CanonicalClosure cachedClosureDefinition,
                @Cached("enteredContext") ProtosLanguageContext cachedContext,
                @Cached("fastOrdinarySendTarget(closure, enteredContext)")
                        RootCallTarget cachedTarget) {
            return finishDirectClosureCall(closure, cachedTarget, supplied, caller);
        }

        @Specialization(replaces = {"guardedDirect", "fastDirect"})
        public static PreparedClosureCall perform(
                Object receiver,
                ProtosActivation caller,
                @Variadic Object[] supplied) {
            return prepareClosureCall(
                    receiver,
                    List.of(supplied),
                    caller);
        }

        @NonIdempotent
        static ProtosLanguageContext currentEnteredContext(Node node) {
            return ProtosLanguageContext.current(node);
        }

        static GuardedDirectClosureCallTarget createGuardedDirectClosureCall(
                Object receiver, ProtosActivation caller, ProtosLanguageContext enteredContext) {
            return ProtosBytecodeRootNode.createGuardedDirectClosureCall(
                    receiver, caller, enteredContext);
        }

        static ProtosClosureValue directClosureCallSelectionOrNull(
                Object receiver, ProtosActivation caller) {
            return ProtosBytecodeRootNode.directClosureCallSelectionOrNull(receiver, caller);
        }

        static CanonicalClosure directClosureCallDefinitionOrNull(ProtosClosureValue closure) {
            return ProtosBytecodeRootNode.directClosureCallDefinitionOrNull(closure);
        }

        static RootCallTarget fastOrdinarySendTarget(
                ProtosClosureValue closure, ProtosLanguageContext enteredContext) {
            return PrepareSendArguments.fastOrdinarySendTarget(closure, enteredContext);
        }
    }

    /**
     * PERF038-H: generic direct Call1, including noncanonical call
     * selection, native bodies and user overrides.
     */
    static PreparedClosureCall prepareClosureCallOneFallback(
            Object receiver,
            ProtosActivation caller,
            Object supplied0) {
        return prepareClosureCall(
                receiver, List.of(supplied0), caller);
    }

    private static PreparedClosureCall prepareClosureCall(
            Object receiver,
            List<?> supplied,
            ProtosActivation caller) {
        return prepareSelectedClosureCall(
                receiver,
                supplied,
                caller,
                selectClosureCall(receiver, caller));
    }

    /**
     * PERF025 / PLAT044 B′ preparation seam for an inline-literal candidate.
     * Ordinary D013 {@code call} selection happens exactly once. The canonical
     * source-backed Closure selection keeps only its effective target and
     * compact frame arguments; every other selection is prepared through the
     * unchanged ordinary fallback immediately.
     */
    private static PreparedInlineLiteralCall prepareInlineLiteralCall(
            Object receiver,
            List<?> supplied,
            ProtosActivation caller) {
        ProtosSlotLookupResult selected = selectClosureCall(receiver, caller);
        ProtosClosureValue callBehavior =
                (ProtosClosureValue) selected.value();

        if (receiver instanceof ProtosClosureValue targetClosure
                && targetClosure.nativeBody().isEmpty()
                && ProtosStandardObjectProtocol
                        .isCanonicalStandardCallSelection(
                                callBehavior,
                                selected.home())) {
            ProtosClosureExecutionPlan plan =
                    taskOwnedBytecodePlan(targetClosure);
            if (plan != null) {
                return PreparedInlineLiteralCall.direct(
                        plan.bytecodeActivationTargetForComposition(),
                        ProtosFrameArguments.compactDirectClosureCall(
                                targetClosure,
                                caller,
                                null,
                                supplied.toArray()));
            }
        }

        return PreparedInlineLiteralCall.fallback(
                prepareSelectedClosureCall(
                        receiver,
                        supplied,
                        caller,
                        selected));
    }

    /**
     * I096-A (PLAT057-B): the same preparation seam for an ordinary
     * {@code receiver(...)} call whose staged receiver is the call site's own
     * Closure literal (I096-A) or the value read from a binding with a
     * candidate body (I096-B1). Selection is exactly {@link
     * #prepareInlineLiteralCall}:
     * one ordinary D013 {@code call} lookup, and any selection other than the
     * canonical standard {@code Object.call} of a source-backed Closure keeps
     * the ordinary prepared fallback.
     */
    static PreparedInlineLiteralCall prepareInlineClosureLiteralCall(
            Object receiver,
            ProtosActivation caller,
            Object[] supplied) {
        return prepareInlineLiteralCall(
                receiver, java.util.Arrays.asList(supplied), caller);
    }

    private static ProtosSlotLookupResult selectClosureCall(
            Object receiver,
            ProtosActivation caller) {
        ProtosPrelude prelude =
                caller.prelude()
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "polymorphic invocation requires an owning Core prelude"));
        ProtosSlotLookupResult selected;
        try {
            selected =
                    ProtosValueLookup.lookup(
                                    receiver,
                                    "call",
                                    prelude)
                            .orElseThrow(
                                    () ->
                                            new ProtosSignalException(
                                                    ProtosCoreErrors.newError(caller)));
        } catch (UnsupportedOperationException unsupportedRepresentation) {
            throw new ProtosSignalException(
                    ProtosCoreErrors.newError(caller));
        }

        if (!(selected.value() instanceof ProtosClosureValue)) {
            throw new ProtosSignalException(
                    ProtosCoreErrors.newError(caller));
        }
        return selected;
    }

    private static PreparedClosureCall prepareSelectedClosureCall(
            Object receiver,
            List<?> supplied,
            ProtosActivation caller,
            ProtosSlotLookupResult selected) {
        ProtosClosureValue callBehavior =
                (ProtosClosureValue) selected.value();

        /*
         * PLAT017-A: D013 lookup has already selected one ordinary `call`
         * behavior. Only the exact canonical root Object.call implementation
         * may elide its native bridge for a Closure target. A nearer override,
         * including an alias of the standard behavior at another home, remains
         * an ordinary method invocation.
         */
        if (receiver instanceof ProtosClosureValue targetClosure
                && ProtosStandardObjectProtocol
                        .isCanonicalStandardCallSelection(
                                callBehavior,
                                selected.home())) {
            return prepareStandardClosureCallIntrinsic(
                    targetClosure,
                    supplied,
                    caller);
        }

        return prepareImmediateMethodCall(
                callBehavior,
                receiver,
                selected.home(),
                supplied,
                caller);
    }

    private static PreparedClosureCall prepareStandardClosureCallIntrinsic(
            ProtosClosureValue targetClosure,
            List<?> supplied,
            ProtosActivation caller) {
        rejectComposedInvocationProjection(
                targetClosure);
        ProtosActivation activation =
                ProtosActivation.forClosureInvocation(
                        targetClosure,
                        supplied,
                        caller.prelude().orElse(null),
                        caller.actorModuleState(),
                        caller.currentModuleKey().orElse(null),
                        caller.executionDomain());
        attachTaskOrInheritDynamicControlState(
                activation,
                caller);
        return finishPreparingComposedCallByImplementation(
                targetClosure,
                supplied,
                activation);
    }

    @Operation
    public static final class PrepareDefaultClosureCallArguments {
        @Specialization(
                guards = {
                    "receiver == cachedReceiver",
                    "enteredContext != null",
                    "enteredContext == cachedContext",
                    "cachedGuarded != null"
                },
                assumptions = "cachedGuarded.stability()",
                limit = "3")
        public static PreparedClosureCall guardedDirect(
                Object receiver,
                ProtosActivation caller,
                @Variadic Object[] supplied,
                @Bind("currentEnteredContext($node)")
                        ProtosLanguageContext enteredContext,
                @Cached("receiver") Object cachedReceiver,
                @Cached("enteredContext") ProtosLanguageContext cachedContext,
                @Cached("createGuardedDirectClosureCall(receiver, caller, enteredContext)")
                        GuardedDirectClosureCallTarget cachedGuarded) {
            return finishDirectClosureCall(
                    cachedGuarded.closure(), cachedGuarded.target(), supplied, caller);
        }

        @Specialization(
                guards = {
                    "closure != null",
                    "enteredContext != null",
                    "closureDefinition != null",
                    "closureDefinition == cachedClosureDefinition",
                    "enteredContext == cachedContext",
                    "cachedTarget != null"
                },
                limit = "3")
        public static PreparedClosureCall fastDirect(
                Object receiver,
                ProtosActivation caller,
                @Variadic Object[] supplied,
                @Bind("directClosureCallSelectionOrNull(receiver, caller)")
                        ProtosClosureValue closure,
                @Bind("directClosureCallDefinitionOrNull(closure)")
                        CanonicalClosure closureDefinition,
                @Bind("currentEnteredContext($node)")
                        ProtosLanguageContext enteredContext,
                @Cached("closureDefinition") CanonicalClosure cachedClosureDefinition,
                @Cached("enteredContext") ProtosLanguageContext cachedContext,
                @Cached("fastOrdinarySendTarget(closure, enteredContext)")
                        RootCallTarget cachedTarget) {
            return finishDirectClosureCall(closure, cachedTarget, supplied, caller);
        }

        @Specialization(replaces = {"guardedDirect", "fastDirect"})
        public static PreparedClosureCall perform(
                Object receiver,
                ProtosActivation caller,
                @Variadic Object[] supplied) {
            return prepareClosureCall(
                    receiver,
                    List.of(supplied),
                    caller);
        }

        @NonIdempotent
        static ProtosLanguageContext currentEnteredContext(Node node) {
            return ProtosLanguageContext.current(node);
        }

        static GuardedDirectClosureCallTarget createGuardedDirectClosureCall(
                Object receiver, ProtosActivation caller, ProtosLanguageContext enteredContext) {
            return ProtosBytecodeRootNode.createGuardedDirectClosureCall(
                    receiver, caller, enteredContext);
        }

        static ProtosClosureValue directClosureCallSelectionOrNull(
                Object receiver, ProtosActivation caller) {
            return ProtosBytecodeRootNode.directClosureCallSelectionOrNull(receiver, caller);
        }

        static CanonicalClosure directClosureCallDefinitionOrNull(ProtosClosureValue closure) {
            return ProtosBytecodeRootNode.directClosureCallDefinitionOrNull(closure);
        }

        static RootCallTarget fastOrdinarySendTarget(
                ProtosClosureValue closure, ProtosLanguageContext enteredContext) {
            return PrepareSendArguments.fastOrdinarySendTarget(closure, enteredContext);
        }
    }

    /*
     * I072 Phase A guarded selection precedes the retained PERF010-A
     * prepared-target specialization. A stable ordinary receiver uses one
     * selector-specific assumption and never repeats lookup on a valid hit.
     * Unsupported chains and freshly rematerialized receivers can still use
     * the existing definition-based specialization described below.
     *
     * PERF010-A prepared-target specialization.
     *
     * <p>The generic ordinary-send path re-classifies every monomorphic hit
     * against the canonical standard-import native intrinsic and re-derives
     * the Context-owned Bytecode execution plan through
     * {@code sharedBytecodeExecutionPlans.computeIfAbsent(...)}. Both
     * operations are compiler-visible host machinery that a proven
     * non-native, source-backed ordinary Closure can never actually need
     * (see the retained PERF010-A causal evidence). {@code fastOrdinarySend}
     * re-runs authoritative D013 lookup on every hit, admits only an exact
     * cached selector/entered-Context match against the selected Closure's
     * stable executable identity, and reuses an effective Context-owned
     * activation target materialized once at cache-population time. Any
     * mismatch, native Closure, or unsupported case falls through to the
     * exact existing generic {@code perform} fallback.
     *
     * <p>The selected {@code ProtosClosureValue} and its {@code methodHome}
     * are fresh runtime objects on every invocation that re-executes the
     * Source producing them (for example a repeatedly re-materialized
     * Closure literal or a freshly constructed receiver): D013 selects the
     * same executable behavior every time, but the wrapper object and its
     * home differ by identity, so guarding on their identity previously
     * consumed a fresh cache entry per execution and, after {@code limit}
     * entries, permanently generalized to {@code perform} (see the retained
     * PERF010-A causal evidence). The cache key instead uses
     * {@link ProtosClosureValue#definition()}: the canonical, immutable
     * Closure definition that {@code MaterializeClosure} always rematerializes
     * from the exact same semantic AST node for one Closure literal, and
     * therefore stays identity-stable across fresh materializations of the
     * same executable behavior while remaining distinct whenever D013
     * genuinely selects a different Closure. The effective Context-owned
     * activation target depends only on that definition and the entered
     * Context (see {@link #fastOrdinarySendTarget}), never on the selected
     * Closure or methodHome instance, so this key remains exactly as
     * discriminating as the target it guards. The currently selected
     * {@code closure} and {@code methodHome} are still bound fresh on every
     * hit and flow into a fresh {@code ProtosActivation} exactly as before;
     * only the cache guard identity changes.
     */
    @Operation
    public static final class PrepareSendArguments {
        /**
         * PERF038-H: guarded source Send0.
         * PIC guards and selection stability were checked by the DSL.
         */
        static PreparedClosureCall guardedOrdinarySendZero(
                Object receiver,
                ProtosActivation caller,
                GuardedSendTarget cachedSend) {
            ProtosReturnHome returnHome = cachedSend.invocationHome();
            Object[] arguments =
                    ProtosFrameArguments.compactImmediateMethodCallZero(
                            cachedSend.closure(),
                            receiver,
                            cachedSend.methodHome(),
                            caller,
                            returnHome);
            return PreparedClosureCall.ordinaryCompactSelected(
                    cachedSend.entryTarget(0),
                    arguments,
                    returnHome,
                    cachedSend.ownsReturnHome());
        }

        /**
         * PERF038-H: guarded source Send1.
         */
        static PreparedClosureCall guardedOrdinarySendOne(
                Object receiver,
                ProtosActivation caller,
                Object supplied0,
                GuardedSendTarget cachedSend) {
            ProtosReturnHome returnHome = cachedSend.invocationHome();
            Object[] arguments =
                    ProtosFrameArguments.compactImmediateMethodCallOne(
                            cachedSend.closure(),
                            receiver,
                            cachedSend.methodHome(),
                            caller,
                            returnHome,
                            supplied0);
            return PreparedClosureCall.ordinaryCompactSelected(
                    cachedSend.entryTarget(1),
                    arguments,
                    returnHome,
                    cachedSend.ownsReturnHome());
        }

        /**
         * {@code sharedInvocationHome} (PERF038-D) is the return home every
         * invocation of {@code closure} uses when it is shared, i.e. the
         * Closure's captured home or the unobservable marker of a plan proven
         * return-home-unobservable; {@code null} when each invocation owns a
         * fresh home. The Closure's captured home is final and its execution
         * plan, once present, is never replaced, so a decision taken at
         * specialization stays exactly {@link
         * ProtosClosureValue#invocationReturnHomeForRuntime}'s for this Closure.
         *
         * <p>PERF038-E: {@code freshInvocationHome} is true when, with no
         * shared home, the Closure's plan was already present (and, being
         * never replaced, permanently not proven unobservable) at
         * specialization: each invocation then owns a fresh physical home
         * without re-reading the plan. {@code ownsReturnHome} is exactly
         * {@code closure.returnHome().isEmpty()}, final for the Closure.
         */
        public record GuardedSendTarget(
                ProtosClosureValue closure,
                ProtosObjectValue methodHome,
                RootCallTarget target,
                Assumption stability,
                ProtosReturnHome sharedInvocationHome,
                boolean freshInvocationHome,
                boolean ownsReturnHome,
                RootCallTarget suppliedEntryTarget) {

            /**
             * PERF038-H: source entry selection depends only on the
             * immutable target and whether arguments were supplied.
             * Resolve both possibilities when the PIC is established.
             */
            RootCallTarget entryTarget(int suppliedCount) {
                return suppliedCount == 0 ? target : suppliedEntryTarget;
            }

            /** Exactly {@code closure().invocationReturnHomeForRuntime()}. */
            ProtosReturnHome invocationHome() {
                if (sharedInvocationHome != null) {
                    return sharedInvocationHome;
                }
                if (freshInvocationHome) {
                    return new ProtosReturnHome();
                }
                return closure.invocationReturnHomeForRuntime();
            }
        }

        /**
         * I072 Phase E structured kinds admitted by {@link #guardedStructuredSend}.
         * Each member corresponds to exactly one canonical-selection helper;
         * the Boolean family additionally carries its
         * {@link ProtosStandardBooleanProtocol.StructuredCallbackKind}.
         */
        enum GuardedStructuredKind {
            ENSURE,
            ERROR_HANDLE,
            WHILE,
            BOOLEAN,
            ARRAY_EACH,
            BYTES_EACH,
            ENVIRONMENT_EACH,
            IDENTITY_MAP_EACH,
            MAP_EACH,
            MAP_READ_LOOKUP,
            MAP_AT_PUT,
            MAP_REMOVE,
            CASE_OF,
            ARRAY_MATCH,
            MAP_MATCH,
            IDENTITY_MAP_AT_IF_ABSENT
        }

        public record GuardedStructuredSend(
                ProtosClosureValue closure,
                ProtosObjectValue methodHome,
                GuardedStructuredKind kind,
                ProtosStandardBooleanProtocol.StructuredCallbackKind booleanKind,
                ProtosStandardMapProtocol.StructuredReadLookupKind mapReadLookupKind,
                Assumption stability) {}

        /**
         * PERF016: a guarded D013 selection for the semantic Integer
         * representation family. Unlike {@link #guardedOrdinarySend} the key is
         * the family, not one receiver object: {@code count - 1} yields a fresh
         * {@code ProtosIntegerValue} on every iteration and must still hit.
         * Only selection is cached; the actual current receiver and arguments
         * flow into the unchanged {@code prepareImmediateMethodCall} path, so
         * the selected native Closure executes exactly as on the generic path.
         * The prelude is part of the key because the represented parent is that
         * prelude's SmallInteger or BigInteger prototype; ProtosValueLookup admits
         * only selections identical for both concrete families, so one entry
         * serves every Integer receiver. The entered Context is retained for the
         * same isolation rule as the other guarded sends.
         */
        public record GuardedIntegerSend(
                ProtosClosureValue closure,
                ProtosObjectValue methodHome,
                ProtosStandardIntegerProtocol.CanonicalIntegerOperation operation,
                Assumption stability) {}

        @Specialization(
                guards = {
                    "isIntegerReceiver(receiver)",
                    "selector.equals(cachedSelector)",
                    "enteredContext != null",
                    "enteredContext == cachedContext",
                    "prelude == cachedPrelude",
                    "cachedInteger != null"
                },
                assumptions = "cachedInteger.stability()",
                limit = "3")
        public static PreparedClosureCall guardedIntegerSend(
                Object receiver,
                String selector,
                ProtosActivation caller,
                @Variadic Object[] supplied,
                @Bind("currentEnteredContext($node)")
                        ProtosLanguageContext enteredContext,
                @Bind("callerPrelude(caller)") ProtosPrelude prelude,
                @Cached("selector") String cachedSelector,
                @Cached("enteredContext") ProtosLanguageContext cachedContext,
                @Cached("prelude") ProtosPrelude cachedPrelude,
                @Cached("createGuardedIntegerSend(receiver, selector, prelude, enteredContext)")
                        GuardedIntegerSend cachedInteger) {
            PreparedClosureCall canonical =
                    canonicalIntegerResultOrNull(cachedInteger, receiver, supplied, prelude);
            if (canonical != null) {
                return canonical;
            }

            /*
             * Non-canonical guarded Integer selections, inherited Number
             * operations, wrong-domain operands and Error-producing cases keep
             * the exact PERF027-A path. That preserves the selected Closure,
             * methodHome and rich semantic invocation whenever it is actually
             * observable.
             */
            return prepareDeferredImmediateNativeMethodCall(
                    cachedInteger.closure(),
                    receiver,
                    cachedInteger.methodHome(),
                    List.of(supplied),
                    caller);
        }

        /**
         * The immediate result of a canonical guarded Integer operation, or
         * {@code null} for every case that must take the exact selected native
         * invocation (non-canonical selection, wrong-domain operand, Error).
         * Observes neither the caller activation nor its Context.
         */
        /**
         * PERF038-H: Send0 has no successful binary canonical Integer
         * operation. Preserve its native fallback and Error semantics.
         */
        static PreparedClosureCall guardedIntegerSendZero(
                Object receiver,
                Object caller,
                GuardedIntegerSend cachedInteger) {
            ProtosActivation exactCaller =
                    caller instanceof ProtosActivation activation
                            ? activation
                            : ProtosFrameArguments.activation((Object[]) caller);

            return prepareDeferredImmediateNativeMethodCall(
                    cachedInteger.closure(),
                    receiver,
                    cachedInteger.methodHome(),
                    List.of(),
                    exactCaller);
        }

        /**
         * PERF038-H: canonical Send1 needs neither an argument vector
         * nor the caller's activation. Materialize the caller only
         * when the native/Error fallback is actually required.
         */
        static PreparedClosureCall guardedIntegerSendOne(
                Object receiver,
                Object caller,
                Object supplied0,
                GuardedIntegerSend cachedInteger) {
            if (cachedInteger.operation() != null) {
                Object directResult =
                        ProtosStandardIntegerProtocol
                                .tryExecuteCanonicalOperationOne(
                                        cachedInteger.operation(),
                                        receiver,
                                        supplied0,
                                        // I091: owner of a result beyond the signed-64 range.
                                        caller instanceof ProtosActivation entered
                                                ? entered.preludeOrNullForRuntime()
                                                : ProtosFrameArguments.preludeOrNull(
                                                        (Object[]) caller));
                if (directResult != null) {
                    return PreparedClosureCall.immediateResult(directResult);
                }
            }

            ProtosActivation exactCaller =
                    caller instanceof ProtosActivation activation
                            ? activation
                            : ProtosFrameArguments.activation((Object[]) caller);

            return prepareDeferredImmediateNativeMethodCall(
                    cachedInteger.closure(),
                    receiver,
                    cachedInteger.methodHome(),
                    List.of(supplied0),
                    exactCaller);
        }

        static PreparedClosureCall canonicalIntegerResultOrNull(
                GuardedIntegerSend cachedInteger,
                Object receiver,
                Object[] supplied,
                ProtosPrelude prelude) {
            if (cachedInteger.operation() == null) {
                return null;
            }
            Object directResult =
                    ProtosStandardIntegerProtocol.tryExecuteCanonicalOperation(
                            cachedInteger.operation(),
                            receiver,
                            supplied,
                            // I091: owner of a result beyond the signed-64 range.
                            prelude);
            return directResult == null
                    ? null
                    : PreparedClosureCall.immediateResult(directResult);
        }

        /**
         * Resolves only while establishing a specialization. Admits a selected
         * native Closure only; a source-backed selection (for example a user
         * override) stays on the existing ordinary/generic paths.
         */
        static GuardedIntegerSend createGuardedIntegerSend(
                Object receiver,
                String selector,
                ProtosPrelude prelude) {
            return createGuardedIntegerSend(
                    receiver,
                    selector,
                    prelude,
                    ProtosLanguageContext.currentIfEnteredForRuntime());
        }

        static GuardedIntegerSend createGuardedIntegerSend(
                Object receiver,
                String selector,
                ProtosPrelude prelude,
                ProtosLanguageContext enteredContext) {
            if (enteredContext == null) {
                return null;
            }
            ProtosValueLookup.GuardedLookup lookup;
            try {
                lookup = ProtosValueLookup.lookupGuardedInteger(
                        receiver, selector, prelude);
            } catch (UnsupportedOperationException unsupportedRepresentation) {
                return null;
            }
            if (lookup == null) {
                return null;
            }
            ProtosSlotLookupResult selected = lookup.selected();
            if (!(selected.value() instanceof ProtosClosureValue closure)
                    || closure.nativeBody().isEmpty()
                    || !lookup.stability().isValid()) {
                lookup.stability().invalidate();
                return null;
            }
            ProtosStandardIntegerProtocol.CanonicalIntegerOperation operation =
                    ProtosStandardIntegerProtocol.canonicalOperationForSelection(
                            closure,
                            selected.home(),
                            selector,
                            prelude);
            return new GuardedIntegerSend(
                    closure,
                    selected.home(),
                    operation,
                    lookup.stability());
        }

        static boolean isIntegerReceiver(Object receiver) {
            return ProtosValueLookup.isInteger(receiver);
        }

        static ProtosPrelude callerPrelude(ProtosActivation caller) {
            return caller.preludeOrNullForRuntime();
        }

        @Specialization(
                guards = {
                    "receiver == cachedReceiver",
                    "selector.equals(cachedSelector)",
                    "enteredContext != null",
                    "enteredContext == cachedContext",
                    "cachedSend != null"
                },
                assumptions = "cachedSend.stability()",
                limit = "3")
        public static PreparedClosureCall guardedOrdinarySend(
                Object receiver,
                String selector,
                ProtosActivation caller,
                @Variadic Object[] supplied,
                @Bind("currentEnteredContext($node)")
                        ProtosLanguageContext enteredContext,
                @Cached("receiver") Object cachedReceiver,
                @Cached("selector") String cachedSelector,
                @Cached("enteredContext") ProtosLanguageContext cachedContext,
                @Cached("createGuardedSend(receiver, selector, caller, enteredContext)")
                        GuardedSendTarget cachedSend) {
            /*
             * PERF038-D: the selected Closure, method home, target and shared
             * return home are the specialization's constants; the frame
             * arguments are built right here, so the carrier takes their
             * header values directly instead of re-validating the array.
             */
            ProtosReturnHome returnHome = cachedSend.invocationHome();
            Object[] frameArguments =
                    ProtosFrameArguments.compactImmediateMethodCall(
                            cachedSend.closure(),
                            receiver,
                            cachedSend.methodHome(),
                            caller,
                            returnHome,
                            supplied);
            return PreparedClosureCall.ordinaryCompactSelected(
                    cachedSend.entryTarget(supplied.length),
                    frameArguments,
                    returnHome,
                    cachedSend.ownsReturnHome());
        }

        /**
         * Resolves and classifies only while establishing a specialization.
         * The entered Context owns the target; neither Closure definition
         * identity alone nor an unguarded method home authorizes this hit.
         */
        static GuardedSendTarget createGuardedSend(
                Object receiver,
                String selector,
                ProtosActivation caller,
                ProtosLanguageContext enteredContext) {
            return createGuardedSendForPrelude(
                    receiver, selector, caller.preludeOrNullForRuntime(), enteredContext);
        }

        /** PERF038-D: see {@link GuardedSendTarget#sharedInvocationHome}. */
        private static ProtosReturnHome sharedInvocationHomeOrNull(ProtosClosureValue closure) {
            if (closure.returnHome().isPresent()) {
                return closure.returnHome().orElseThrow();
            }
            ProtosReturnHome home = closure.invocationReturnHomeForRuntime();
            return home == ProtosReturnHome.unobservable() ? home : null;
        }

        static GuardedSendTarget createGuardedSendForPrelude(
                Object receiver,
                String selector,
                ProtosPrelude prelude,
                ProtosLanguageContext enteredContext) {
            if (enteredContext == null) {
                return null;
            }
            ProtosValueLookup.GuardedLookup lookup;
            try {
                lookup = ProtosValueLookup.lookupGuarded(
                        receiver, selector, prelude);
            } catch (UnsupportedOperationException unsupportedRepresentation) {
                return null;
            }
            if (lookup == null) {
                return null;
            }
            ProtosClosureValue closure =
                    ordinarySendClosureOrNull(lookup.selected());
            if (closure == null) {
                lookup.stability().invalidate();
                return null;
            }
            RootCallTarget target =
                    fastOrdinarySendTarget(closure, enteredContext);
            if (target == null || !lookup.stability().isValid()) {
                lookup.stability().invalidate();
                return null;
            }
            ProtosReturnHome sharedHome = sharedInvocationHomeOrNull(closure);
            return new GuardedSendTarget(
                    closure,
                    lookup.selected().home(),
                    target,
                    lookup.stability(),
                    sharedHome,
                    sharedHome == null && closure.executionPlan().isPresent(),
                    closure.returnHome().isEmpty(),
                    ProtosSemanticBytecodeRootNode.selectSourceEntryTarget(
                            target, 1));
        }

        /**
         * PERF038-H: Send0 with dynamically selected source Closure.
         * Preserve the current selected Closure/home, but avoid the
         * supplied-argument vector and compact-header validation.
         */
        static PreparedClosureCall fastOrdinarySendZero(
                ProtosClosureValue closure,
                Object receiver,
                ProtosObjectValue methodHome,
                ProtosActivation caller,
                RootCallTarget target) {
            ProtosReturnHome returnHome =
                    closure.invocationReturnHomeForRuntime();

            return PreparedClosureCall.ordinaryCompactSelected(
                    target,
                    ProtosFrameArguments.compactImmediateMethodCallZero(
                            closure, receiver, methodHome, caller, returnHome),
                    returnHome,
                    closure.returnHome().isEmpty());
        }

        /**
         * PERF038-H: Send1 with dynamically selected source Closure.
         * The exact supplied value enters the final frame directly.
         */
        static PreparedClosureCall fastOrdinarySendOne(
                ProtosClosureValue closure,
                Object receiver,
                ProtosObjectValue methodHome,
                ProtosActivation caller,
                RootCallTarget target,
                Object supplied0) {
            ProtosReturnHome returnHome =
                    closure.invocationReturnHomeForRuntime();

            RootCallTarget selectedEntry =
                    ProtosSemanticBytecodeRootNode.selectSourceEntryTarget(
                            target, 1);

            return PreparedClosureCall.ordinaryCompactSelected(
                    selectedEntry,
                    ProtosFrameArguments.compactImmediateMethodCallOne(
                            closure, receiver, methodHome, caller,
                            returnHome, supplied0),
                    returnHome,
                    closure.returnHome().isEmpty());
        }

        @Specialization(
                guards = {
                    "!isForeignReceiver(receiver)",
                    "closure != null",
                    "enteredContext != null",
                    "selector.equals(cachedSelector)",
                    "closureDefinition != null",
                    "closureDefinition == cachedClosureDefinition",
                    "enteredContext == cachedContext",
                    "cachedTarget != null"
                },
                limit = "3")
        public static PreparedClosureCall fastOrdinarySend(
                Object receiver,
                String selector,
                ProtosActivation caller,
                @Variadic Object[] supplied,
                @Bind("performOrdinarySendLookup(receiver, selector, caller)")
                        ProtosSlotLookupResult selected,
                @Bind("ordinarySendClosureOrNull(selected)")
                        ProtosClosureValue closure,
                @Bind("selected.home()") ProtosObjectValue methodHome,
                @Bind("ordinarySendClosureDefinitionOrNull(closure)")
                        CanonicalClosure closureDefinition,
                @Bind("currentEnteredContext($node)")
                        ProtosLanguageContext enteredContext,
                @Cached("selector") String cachedSelector,
                @Cached("closureDefinition") CanonicalClosure cachedClosureDefinition,
                @Cached("enteredContext") ProtosLanguageContext cachedContext,
                @Cached("fastOrdinarySendTarget(closure, enteredContext)")
                        RootCallTarget cachedTarget) {
            Object[] frameArguments =
                    ProtosFrameArguments.compactImmediateMethodCall(
                            closure,
                            receiver,
                            methodHome,
                            caller,
                            supplied);
            return PreparedClosureCall.ordinaryCompact(
                    cachedTarget,
                    frameArguments);
        }

        /*
         * I072 Phase E: a stable canonical structured native send (Object.ensure,
         * Error.handle, Object.whileTrue, Boolean callbacks) reuses the exact Phase A
         * lookupGuarded/Assumption contract that guardedOrdinarySend already
         * establishes for source-backed Closures. createGuardedStructuredSend
         * runs the authoritative D013 lookup and classifies the selected value
         * against the canonical-selection helpers (identity + home/provenance,
         * not native-body identity alone) exactly once, at specialization
         * establishment. The valid hit below never repeats that lookup or the
         * generic finishPreparingComposedCallByImplementation classifier scan;
         * it feeds the single cached kind directly into the unchanged
         * finishPreparingComposedCall/PreparedClosureCall.nativeCall path, so
         * ensure/handle/while/Boolean execution, suspension, cleanup and error
         * semantics remain byte-identical to the generic path. A selector whose
         * selection is not exactly one canonical standard implementation misses
         * here and falls through to the exact generic {@code perform} path,
         * which preserves override/alias/noncanonical-home behavior unchanged.
         */
        @Specialization(
                guards = {
                    "receiver == cachedReceiver",
                    "selector.equals(cachedSelector)",
                    "enteredContext != null",
                    "enteredContext == cachedContext",
                    "cachedStructured != null"
                },
                assumptions = "cachedStructured.stability()",
                limit = "3")
        public static PreparedClosureCall guardedStructuredSend(
                Object receiver,
                String selector,
                ProtosActivation caller,
                @Variadic Object[] supplied,
                @Bind("currentEnteredContext($node)")
                        ProtosLanguageContext enteredContext,
                @Cached("receiver") Object cachedReceiver,
                @Cached("selector") String cachedSelector,
                @Cached("enteredContext") ProtosLanguageContext cachedContext,
                @Cached("createGuardedStructuredSend(receiver, selector, caller)")
                        GuardedStructuredSend cachedStructured) {
            return guardedStructuredSendPrepared(
                    receiver, caller, List.of(supplied), cachedStructured);
        }

        /**
         * PERF038-H: Send0, preserving the selected structured kind.
         */
        static PreparedClosureCall guardedStructuredSendZero(
                Object receiver,
                ProtosActivation caller,
                GuardedStructuredSend cachedStructured) {
            return guardedStructuredSendPrepared(
                    receiver, caller, List.of(), cachedStructured);
        }

        /**
         * PERF038-H: Send1 without an intermediate Object[].
         */
        static PreparedClosureCall guardedStructuredSendOne(
                Object receiver,
                ProtosActivation caller,
                Object supplied0,
                GuardedStructuredSend cachedStructured) {
            return guardedStructuredSendPrepared(
                    receiver, caller, List.of(supplied0), cachedStructured);
        }

        /**
         * Exact existing structured invocation body, shared by the
         * variadic and fixed-arity entry points.
         */
        private static PreparedClosureCall guardedStructuredSendPrepared(
                Object receiver,
                ProtosActivation caller,
                List<?> suppliedList,
                GuardedStructuredSend cachedStructured) {
            rejectComposedInvocationProjection(cachedStructured.closure());
            ProtosActivation activation =
                    ProtosActivation.forImmediateMethodInvocation(
                            cachedStructured.closure(),
                            suppliedList,
                            receiver,
                            cachedStructured.methodHome(),
                            caller.prelude().orElse(null),
                            caller.actorModuleState(),
                            caller.currentModuleKey().orElse(null),
                            caller.executionDomain());
            attachTaskOrInheritDynamicControlState(activation, caller);
            return finishPreparingComposedCall(
                    cachedStructured.closure(),
                    suppliedList,
                    activation,
                    cachedStructured.kind() == GuardedStructuredKind.ENSURE,
                    cachedStructured.kind() == GuardedStructuredKind.ERROR_HANDLE,
                    cachedStructured.kind() == GuardedStructuredKind.WHILE,
                    cachedStructured.booleanKind(),
                    cachedStructured.kind() == GuardedStructuredKind.ARRAY_EACH,
                    cachedStructured.kind() == GuardedStructuredKind.BYTES_EACH,
                    cachedStructured.kind() == GuardedStructuredKind.ENVIRONMENT_EACH,
                    cachedStructured.kind() == GuardedStructuredKind.IDENTITY_MAP_EACH,
                    cachedStructured.kind() == GuardedStructuredKind.MAP_EACH,
                    cachedStructured.mapReadLookupKind(),
                    cachedStructured.kind() == GuardedStructuredKind.MAP_AT_PUT,
                    cachedStructured.kind() == GuardedStructuredKind.MAP_REMOVE,
                    false,
                    false,
                    null);
        }


        /**
         * Resolves and classifies only while establishing a specialization.
         * Reuses {@link ProtosValueLookup#lookupGuarded} exactly as
         * {@link #createGuardedSend} does, then admits only a selected native
         * Closure whose behavior/home satisfies one canonical-selection helper.
         * A copied/aliased/overridden selection (same native body at a
         * noncanonical home, or a different selected behavior entirely) misses
         * here and invalidates the assumption immediately, leaving the exact
         * generic classification as the sole authority for that selector.
         */
        static GuardedStructuredSend createGuardedStructuredSend(
                Object receiver,
                String selector,
                ProtosActivation caller) {
            boolean canonicalBoolean = ProtosValueLookup.isCanonicalBoolean(receiver);
            ProtosValueLookup.GuardedLookup lookup;
            try {
                lookup = canonicalBoolean
                        ? ProtosValueLookup.lookupGuardedCanonicalBoolean(
                                receiver, selector, caller.preludeOrNullForRuntime())
                        : ProtosValueLookup.lookupGuarded(
                                receiver, selector, caller.preludeOrNullForRuntime());
            } catch (UnsupportedOperationException unsupportedRepresentation) {
                return null;
            }
            if (lookup == null) {
                return null;
            }
            ProtosSlotLookupResult selected = lookup.selected();
            if (!(selected.value() instanceof ProtosClosureValue closure)
                    || closure.nativeBody().isEmpty()) {
                lookup.stability().invalidate();
                return null;
            }
            ProtosObjectValue home = selected.home();
            if (canonicalBoolean) {
                // PERF015: a represented Boolean is admitted only for the
                // canonical Boolean callbacks; every other selection stays generic.
                ProtosStandardBooleanProtocol.StructuredCallbackKind canonicalKind =
                        ProtosStandardBooleanProtocol
                                .structuredCallbackKindForCanonicalSelection(closure, home);
                if (canonicalKind == null || !lookup.stability().isValid()) {
                    lookup.stability().invalidate();
                    return null;
                }
                return new GuardedStructuredSend(
                        closure, home, GuardedStructuredKind.BOOLEAN, canonicalKind,
                        null, lookup.stability());
            }
            GuardedStructuredKind kind;
            ProtosStandardBooleanProtocol.StructuredCallbackKind booleanKind = null;
            ProtosStandardMapProtocol.StructuredReadLookupKind mapReadLookupKind = null;
            if (ProtosStandardObjectProtocol.isCanonicalStandardEnsureSelection(
                    closure, home)) {
                kind = GuardedStructuredKind.ENSURE;
            } else if (ProtosStandardErrorProtocol.isCanonicalStandardHandleSelection(
                    closure, home, caller)) {
                kind = GuardedStructuredKind.ERROR_HANDLE;
            } else if (ProtosStandardObjectProtocol.isCanonicalStandardPreTestLoopSelection(
                    closure, home)) {
                kind = GuardedStructuredKind.WHILE;
            } else if ((booleanKind =
                    ProtosStandardBooleanProtocol
                            .structuredCallbackKindForCanonicalSelection(
                                    closure, home))
                    != null) {
                kind = GuardedStructuredKind.BOOLEAN;
            } else if (ProtosStandardArrayProtocol.isCanonicalStandardEachSelection(
                    closure, home, caller)) {
                kind = GuardedStructuredKind.ARRAY_EACH;
            } else if (ProtosStandardBytesProtocol.isCanonicalStandardEachSelection(
                    closure, home, caller)) {
                kind = GuardedStructuredKind.BYTES_EACH;
            } else if (ProtosStandardEnvironmentProtocol.isCanonicalStandardEachSelection(
                    closure, receiver, home)) {
                kind = GuardedStructuredKind.ENVIRONMENT_EACH;
            } else if (ProtosStandardIdentityMapProtocol.isCanonicalStandardEachSelection(
                    closure, home, caller)) {
                kind = GuardedStructuredKind.IDENTITY_MAP_EACH;
            } else if (ProtosStandardMapProtocol.isCanonicalStandardEachSelection(
                    closure, home, caller)) {
                kind = GuardedStructuredKind.MAP_EACH;
            } else if ((mapReadLookupKind =
                    ProtosStandardMapProtocol
                            .structuredReadLookupKindForCanonicalSelection(
                                    closure, home, caller))
                    != null) {
                kind = GuardedStructuredKind.MAP_READ_LOOKUP;
            } else if (ProtosStandardMapProtocol.isCanonicalStandardAtPutSelection(
                    closure, home, caller)) {
                kind = GuardedStructuredKind.MAP_AT_PUT;
            } else if (ProtosStandardMapProtocol.isCanonicalStandardRemoveSelection(
                    closure, home, caller)) {
                kind = GuardedStructuredKind.MAP_REMOVE;
            } else if (ProtosStandardObjectProtocol.isCanonicalStandardCaseOfSelection(
                    closure, home)) {
                kind = GuardedStructuredKind.CASE_OF;
            } else if (ProtosStandardArrayProtocol.isCanonicalStandardMatchSelection(
                    closure, home, caller)) {
                kind = GuardedStructuredKind.ARRAY_MATCH;
            } else if (ProtosStandardMapProtocol.isCanonicalStandardMatchSelection(
                    closure, home, caller)) {
                kind = GuardedStructuredKind.MAP_MATCH;
            } else if (ProtosStandardIdentityMapProtocol.isCanonicalStandardAtIfAbsentSelection(
                    closure, home, caller)) {
                kind = GuardedStructuredKind.IDENTITY_MAP_AT_IF_ABSENT;
            } else {
                lookup.stability().invalidate();
                return null;
            }
            if (!lookup.stability().isValid()) {
                return null;
            }
            return new GuardedStructuredSend(
                    closure, home, kind, booleanKind, mapReadLookupKind, lookup.stability());
        }

        @Specialization(
                replaces = {
                    "guardedIntegerSend",
                    "guardedOrdinarySend",
                    "fastOrdinarySend",
                    "guardedStructuredSend"
                })
        public static PreparedClosureCall perform(
                Object receiver,
                String selector,
                ProtosActivation caller,
                @Variadic Object[] supplied) {
            return prepareSend(
                    receiver,
                    selector,
                    caller,
                    List.of(supplied));
        }

        /*
         * Bytecode DSL guard/@Bind expressions in this Operation are resolved
         * by the DSL processor's own expression parser, which only sees
         * members declared directly on this class (not private members of the
         * enclosing ProtosBytecodeRootNode, and not other top-level types by
         * simple name). These forwarders keep that resolution local while
         * delegating to the exact shared implementation, avoiding a duplicate
         * source of truth for lookup/error semantics.
         */

        static ProtosSlotLookupResult performOrdinarySendLookup(
                Object receiver,
                String selector,
                ProtosActivation caller) {
            return ProtosBytecodeRootNode.performOrdinarySendLookup(
                    receiver,
                    selector,
                    caller);
        }

        /**
         * D188: a foreign-member read is a foreign operation, so the speculative fast-path
         * selection must not perform it; foreign receivers always use the generic send path.
         */
        static boolean isForeignReceiver(Object receiver) {
            return receiver
                    instanceof com.guillermomolina.protos.runtime.ProtosForeignProjectedReceiver;
        }

        /**
         * Returns the non-native ordinary Closure selected by {@code selected},
         * or {@code null} when the selection is not an ordinary source-backed
         * Closure. Native Closures (including the canonical standard-import
         * intrinsic) always miss here and remain on the exact generic
         * {@code perform} path.
         */
        static ProtosClosureValue ordinarySendClosureOrNull(
                ProtosSlotLookupResult selected) {
            if (selected.value() instanceof ProtosClosureValue closure
                    && closure.nativeBody().isEmpty()) {
                return closure;
            }
            return null;
        }

        /**
         * Returns the stable executable-identity key for {@code closure}, or
         * {@code null} when there is no selected Closure. Same-definition
         * Closures always rematerialize this exact {@link CanonicalClosure}
         * instance (see the class-level PERF010-A note), so this key stays
         * stable across fresh {@code ProtosClosureValue}/{@code methodHome}
         * materializations of the same executable behavior.
         */
        static CanonicalClosure ordinarySendClosureDefinitionOrNull(
                ProtosClosureValue closure) {
            return closure == null ? null : closure.definition();
        }

        @NonIdempotent
        static ProtosLanguageContext currentEnteredContext(Node node) {
            return ProtosLanguageContext.current(node);
        }

        /**
         * Materializes the effective Context-owned Bytecode activation target
         * for one cached fast-hit specialization instance.
         *
         * <p>This runs once, at cache-population time, and may use the
         * existing Context-owned-plan machinery
         * ({@link ProtosBytecodeRootNode#taskOwnedBytecodePlan}); it is not
         * called again on the resulting hot hit. Returns {@code null} when no
         * safe target can be produced for the current entered Context, which
         * keeps the fast specialization from being instantiated for this call
         * and leaves the exact generic path as the fallback.
         */
        static RootCallTarget fastOrdinarySendTarget(
                ProtosClosureValue closure,
                ProtosLanguageContext enteredContext) {
            if (enteredContext == null) {
                return null;
            }
            ProtosClosureExecutionPlan plan =
                    ProtosBytecodeRootNode.taskOwnedBytecodePlan(
                            closure,
                            enteredContext);
            if (plan == null) {
                return null;
            }
            return plan.bytecodeActivationTargetForComposition();
        }
    }

    /**
     * I092: the selected true callback of a native canonical Boolean.ifTrue.
     *
     * <p>The callback is prepared exactly as the standard behavior prepares it
     * (ordinary D013 call selection on the supplied value, Error authority from
     * the caller's prelude), but with the send's own caller as invocation
     * caller. The standard ifTrue activation is not constructed: it is native,
     * creates no Closure, and only copies the caller's prelude, module, Actor
     * state, execution domain and Task/dynamic control, plus a fresh ReturnHome
     * nothing can target, so the callback cannot observe its absence.
     * An admitted immediate literal becomes the direct B-prime child; any other
     * value keeps the ordinary call protocol, including overrides and Errors.
     */
    static PreparedInlineLiteralCall nativeIfTrueCallback(
            Object caller,
            Object supplied,
            Object literalPlan,
            ProtosLanguageContext enteredContext) {
        if (literalPlan instanceof ProtosClosureExecutionPlanCell plan) {
            PreparedInlineLiteralCall inline =
                    compactCanonicalIfTrue(caller, supplied, plan, enteredContext);
            if (inline != null) {
                return inline;
            }
        }
        return prepareNativeIfTrueCallbackFallback(caller, supplied);
    }

    /**
     * Cold callback-invocation preparation when the compact B-prime
     * admission is not valid.
     *
     * <p>The selected callback still uses the complete ordinary call protocol.
     * Only its preparation is isolated; invocation, suspension, completion
     * and control transfer remain in the existing Bytecode DSL machinery.
     */
    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    static PreparedInlineLiteralCall prepareNativeIfTrueCallbackFallback(
            Object caller,
            Object supplied) {
        return prepareInlineLiteralCall(
                supplied,
                List.of(),
                ProtosSemanticBytecodeRootNode.PrepareSendArguments.exactCaller(caller));
    }

    /**
     * B-prime admission of an immediate literal true callback, or null.
     *
     * <p>A Closure delegating directly to the published frozen root Object
     * (D049) without a local call slot selects the canonical Object.call by
     * D013, so no call lookup is needed. Neither an outer NativeCall nor a rich
     * activation is constructed. The child's direct compact arguments borrow
     * provenance only when the compact caller is proven equivalent to its own
     * caller.
     */
    static PreparedInlineLiteralCall compactCanonicalIfTrue(
            Object caller,
            Object supplied,
            ProtosClosureExecutionPlanCell literalPlan,
            ProtosLanguageContext enteredContext) {
        if (!(supplied instanceof ProtosClosureValue closure)
                || closure.nativeBody().isPresent()
                || closure.returnHome().isEmpty()
                || closure.requiresContextLocalExecutionProjectionForRuntime()
                || closure.parent().orElse(null) != ProtosObjectValue.rootObject()
                || closure.hasLocalSlot("call")) {
            return null;
        }

        ProtosActivation provenance;
        if (caller instanceof ProtosActivation activation) {
            provenance = activation;
        } else if (caller instanceof Object[] compact
                && ProtosFrameArguments.isUnmaterializedCompactCall(compact)) {
            provenance =
                    ProtosFrameArguments.unmaterializedInheritedProvenanceCaller(
                            compact);
        } else {
            return null;
        }

        if (provenance == null) {
            return null;
        }

        /*
         * PERF041-E1: this cell is frozen before guest execution.
         * A Closure using exactly its template plan requires no
         * Context-owned projection when the entered language agrees.
         *
         * A distinct template or foreign language still takes the
         * authoritative taskOwnedBytecodePlan resolution below.
         */
        ProtosClosureExecutionPlan literalTemplate = literalPlan.plan();
        boolean localLanguage =
                enteredContext == null
                        || literalTemplate.language().orElseThrow()
                                == enteredContext.languageForRuntime();

        ProtosClosureExecutionPlan plan;
        if (localLanguage
                && closure.executionPlanForRuntimeInvocation()
                        == literalTemplate) {
            plan = literalTemplate;
        } else {
            plan = taskOwnedBytecodePlan(closure, enteredContext);
        }

        if (plan == null
                || (plan != literalTemplate
                        && plan.bytecodeActivationTargetForComposition()
                                != literalTemplate
                                        .bytecodeActivationTargetForComposition())) {
            return null;
        }

        /*
         * The preceding guards already proved:
         *  - the exact callback target;
         *  - a non-owning ReturnHome (closure.returnHome is present);
         *  - canonical Closure.call selection.
         *
         * PreparedInlineLiteralCall.direct therefore satisfies
         * admits(literalPlan) without a second admission check.
         */
        return PreparedInlineLiteralCall.direct(
                literalTemplate.bytecodeActivationTargetForComposition(),
                ProtosFrameArguments.compactDirectClosureCallZeroPrepared(
                        closure,
                        provenance,
                        closure.returnHome().orElseThrow()));
    }

    @Operation
    public static final class PrepareSendVector {
        @Specialization
        public static PreparedClosureCall perform(
                Object receiver,
                String selector,
                ProtosActivation caller,
                PreparedArgumentVector supplied) {
            return prepareSend(
                    receiver,
                    selector,
                    caller,
                    supplied.snapshot());
        }
    }

    /**
     * PERF038-H: generic Send0 without a supplied-argument array.
     */
    static PreparedClosureCall prepareSendZeroFallback(
            Object receiver,
            String selector,
            ProtosActivation caller) {
        return prepareSend(receiver, selector, caller, List.of());
    }

    /**
     * PERF038-H: generic Send1 without a supplied-argument array.
     */
    static PreparedClosureCall prepareSendOneFallback(
            Object receiver,
            String selector,
            ProtosActivation caller,
            Object supplied0) {
        return prepareSend(
                receiver, selector, caller, List.of(supplied0));
    }

    private static PreparedClosureCall prepareSend(
            Object receiver,
            String selector,
            ProtosActivation caller,
            List<?> supplied) {
        ProtosSlotLookupResult selected =
                performOrdinarySendLookup(receiver, selector, caller);
        if (ProtosForeignProjectedOperations.isForeignMemberSelection(selected)) {
            // D188: read then ordinary invocation of the read foreign member value.
            receiver = selected.value();
            selected = ProtosForeignProjectedOperations.invocationSelection(receiver, caller);
        }
        if (!(selected.value() instanceof ProtosClosureValue closure)) {
            throw new ProtosSignalException(
                    ProtosCoreErrors.newError(caller));
        }
        return prepareImmediateMethodCall(
                closure,
                receiver,
                selected.home(),
                supplied,
                caller);
    }

    /**
     * Performs the authoritative D013 ordinary-send lookup shared by the
     * generic {@link #prepareSend} path and the {@code PrepareSendArguments}
     * fast-hit specialization, preserving the exact existing lookup-failure
     * and unsupported-representation error semantics.
     */
    static ProtosSlotLookupResult performOrdinarySendLookup(
            Object receiver,
            String selector,
            ProtosActivation caller) {
        ProtosPrelude prelude =
                caller.preludeOrNullForRuntime();
        try {
            var selected =
                    ProtosValueLookup.lookup(
                            receiver,
                            selector,
                            prelude);
            if (selected.isEmpty()) {
                throw new ProtosSignalException(
                        ProtosCoreErrors.newSlotNotFound(caller));
            }
            return selected.orElseThrow();
        } catch (UnsupportedOperationException unsupportedRepresentation) {
            throw new ProtosSignalException(
                    ProtosCoreErrors.newError(caller));
        }
    }

    @Operation
    public static final class PrepareBufferedByteReaderTargetCall {
        @Specialization
        public static PreparedClosureCall perform(
                ProtosBufferedByteReaderCPrimeExecution.CallState state,
                ProtosActivation activation) {
            return prepareSend(
                    state.target(),
                    "read",
                    activation,
                    state.arguments());
        }
    }

    @Operation
    public static final class WrapBufferedByteReaderTargetInvocation {
        @Specialization
        public static Object perform(
                ProtosBufferedByteReaderCPrimeExecution.CallState state,
                Object result) {
            return state.invocationSucceeded(result);
        }
    }

    @Operation
    public static final class BufferedByteReaderTargetInvocationFailed {
        @Specialization
        public static Object perform(
                ProtosBufferedByteReaderCPrimeExecution.CallState state) {
            return state.invocationFailed();
        }
    }

    @Operation
    public static final class AwaitBufferedByteReaderTargetFuture {
        @Specialization
        public static Object perform(
                ProtosBufferedByteReaderCPrimeExecution.CallState state,
                Object invocation) {
            if (!(invocation
                    instanceof ProtosBufferedByteReaderCPrimeExecution.TargetInvocation
                            targetInvocation)) {
                throw new IllegalStateException(
                        "BufferedReader C-prime invocation produced an invalid carrier");
            }
            return state.awaitTargetFuture(targetInvocation);
        }
    }

    @Operation
    public static final class ResumeBufferedByteReaderTargetFutureWait {
        @Specialization
        public static Object perform(
                ProtosActivation activation,
                Object yielded,
                Object resumeValue) {
            if (!(yielded instanceof ProtosIoOperationSuspension suspension)) {
                throw new IllegalStateException(
                        "BufferedReader lower-Future wait yielded an invalid carrier");
            }
            ProtosIoOperation operation =
                    activation.deferredCPrimeOperationForRuntime()
                            .orElseThrow(
                                    () ->
                                            new IllegalStateException(
                                                    "BufferedReader lower-Future wait requires an operation-owned activation"));
            if (suspension.operation() != operation) {
                throw new IllegalStateException(
                        "BufferedReader lower-Future wait belongs to another operation");
            }
            if (resumeValue != ProtosNullValue.INSTANCE) {
                throw new IllegalStateException(
                        "BufferedReader lower-Future wait received unsupported resume transport");
            }
            return suspension.resume();
        }
    }

    @Operation
    public static final class BeginBufferedByteWriterFirstEffect {
        @Specialization
        public static boolean perform(
                ProtosBufferedByteWriterCPrimeExecution.CallState state) {
            return state.beginFirstEffectAttempt();
        }
    }

    @Operation
    public static final class IsBufferedByteWriterStepRequired {
        @Specialization
        public static boolean perform(Object value) {
            if (!(value instanceof Boolean required)) {
                throw new IllegalStateException(
                        "BufferedWriter C-prime step gate produced a non-Boolean value");
            }
            return required;
        }
    }

    @Operation
    public static final class PrepareBufferedByteWriterFirstCall {
        @Specialization
        public static PreparedClosureCall perform(
                ProtosBufferedByteWriterCPrimeExecution.CallState state,
                ProtosActivation activation) {
            return prepareSend(
                    state.target(),
                    state.firstSelector(),
                    activation,
                    state.firstArguments());
        }
    }

    @Operation
    public static final class PrepareBufferedByteWriterFlushCall {
        @Specialization
        public static PreparedClosureCall perform(
                ProtosBufferedByteWriterCPrimeExecution.CallState state,
                ProtosActivation activation) {
            return prepareSend(
                    state.target(),
                    "flush",
                    activation,
                    List.of());
        }
    }

    @Operation
    public static final class WrapBufferedByteWriterTargetInvocation {
        @Specialization
        public static Object perform(
                ProtosBufferedByteWriterCPrimeExecution.CallState state,
                Object result) {
            return state.invocationSucceeded(result);
        }
    }

    @Operation
    public static final class BufferedByteWriterTargetInvocationFailed {
        @Specialization
        public static Object perform(
                ProtosBufferedByteWriterCPrimeExecution.CallState state) {
            return state.invocationFailed();
        }
    }

    @Operation
    public static final class AwaitBufferedByteWriterTargetFuture {
        @Specialization
        public static Object perform(
                ProtosBufferedByteWriterCPrimeExecution.CallState state,
                Object invocation) {
            if (!(invocation
                    instanceof ProtosBufferedByteWriterCPrimeExecution.TargetInvocation
                            targetInvocation)) {
                throw new IllegalStateException(
                        "BufferedWriter C-prime invocation produced an invalid carrier");
            }
            return state.awaitTargetFuture(targetInvocation);
        }
    }

    @Operation
    public static final class ResumeBufferedByteWriterTargetFutureWait {
        @Specialization
        public static Object perform(
                ProtosActivation activation,
                Object yielded,
                Object resumeValue) {
            if (!(yielded instanceof ProtosIoOperationSuspension suspension)) {
                throw new IllegalStateException(
                        "BufferedWriter lower-Future wait yielded an invalid carrier");
            }
            ProtosIoOperation operation =
                    activation.deferredCPrimeOperationForRuntime()
                            .orElseThrow(
                                    () ->
                                            new IllegalStateException(
                                                    "BufferedWriter lower-Future wait requires an operation-owned activation"));
            if (suspension.operation() != operation) {
                throw new IllegalStateException(
                        "BufferedWriter lower-Future wait belongs to another operation");
            }
            if (resumeValue != ProtosNullValue.INSTANCE) {
                throw new IllegalStateException(
                        "BufferedWriter lower-Future wait received unsupported resume transport");
            }
            return suspension.resume();
        }
    }

    @Operation
    public static final class ApplyBufferedByteWriterFirstOutcome {
        @Specialization
        public static boolean perform(
                ProtosBufferedByteWriterCPrimeExecution.CallState state,
                Object outcome) {
            if (!(outcome
                    instanceof ProtosBufferedByteWriterCPrimeExecution.LowerOutcome
                            lowerOutcome)) {
                throw new IllegalStateException(
                        "BufferedWriter first lower wait produced an invalid carrier");
            }
            return state.applyFirstOutcome(lowerOutcome);
        }
    }

    @Operation
    public static final class ApplyBufferedByteWriterFollowupOutcome {
        @Specialization
        public static boolean perform(
                ProtosBufferedByteWriterCPrimeExecution.CallState state,
                Object outcome) {
            if (!(outcome
                    instanceof ProtosBufferedByteWriterCPrimeExecution.LowerOutcome
                            lowerOutcome)) {
                throw new IllegalStateException(
                        "BufferedWriter followup lower wait produced an invalid carrier");
            }
            return state.applyFollowupFlushOutcome(lowerOutcome);
        }
    }


    @Operation
    public static final class IoReleaseHasNextStep {
        @Specialization
        public static boolean perform(
                ProtosIoReleaseCPrimeExecution.Sequence sequence) {
            return sequence.hasNextStep();
        }
    }

    @Operation
    public static final class PrepareIoReleaseTargetCall {
        @Specialization
        public static PreparedClosureCall perform(
                ProtosIoReleaseCPrimeExecution.Sequence sequence,
                ProtosActivation activation) {
            return prepareSend(
                    sequence.target(),
                    sequence.selector(),
                    activation,
                    sequence.arguments());
        }
    }

    @Operation
    public static final class WrapIoReleaseTargetInvocation {
        @Specialization
        public static Object perform(
                ProtosIoReleaseCPrimeExecution.Sequence sequence,
                Object result) {
            return sequence.invocationSucceeded(result);
        }
    }

    @Operation
    public static final class IoReleaseTargetInvocationFailed {
        @Specialization
        public static Object perform(
                ProtosIoReleaseCPrimeExecution.Sequence sequence) {
            return sequence.invocationFailed();
        }
    }

    @Operation
    public static final class AwaitIoReleaseTargetFuture {
        @Specialization
        public static Object perform(
                ProtosIoReleaseCPrimeExecution.Sequence sequence,
                Object invocation) {
            if (!(invocation
                    instanceof ProtosIoReleaseCPrimeExecution.TargetInvocation
                            targetInvocation)) {
                throw new IllegalStateException(
                        "lifecycle release C-prime invocation produced an invalid carrier");
            }
            return sequence.awaitTargetFuture(targetInvocation);
        }
    }

    @Operation
    public static final class ResumeIoReleaseTargetFutureWait {
        @Specialization
        public static Object perform(
                ProtosActivation activation,
                Object yielded,
                Object resumeValue) {
            return ProtosStandardFutureProtocol.resumeIoReleaseFutureWaitForRuntime(
                    activation,
                    yielded,
                    resumeValue);
        }
    }

    @Operation
    public static final class ApplyIoReleaseTargetOutcome {
        @Specialization
        public static void perform(
                ProtosIoReleaseCPrimeExecution.Sequence sequence,
                Object outcome) {
            if (!(outcome
                    instanceof ProtosIoReleaseCPrimeExecution.LowerOutcome
                            lowerOutcome)) {
                throw new IllegalStateException(
                        "lifecycle release C-prime lower wait produced an invalid carrier");
            }
            sequence.applyOutcome(lowerOutcome);
        }
    }

    @Operation
    public static final class FinishIoReleaseSequence {
        @Specialization
        public static Object perform(
                ProtosIoReleaseCPrimeExecution.Sequence sequence) {
            return sequence.finish();
        }
    }

    @Operation
    public static final class PrepareTextWriterTargetCall {
        @Specialization
        public static PreparedClosureCall perform(
                ProtosTextWriterCPrimeExecution.CallState state,
                ProtosActivation activation) {
            return prepareSend(
                    state.target(),
                    state.selector(),
                    activation,
                    state.arguments());
        }
    }

    @Operation
    public static final class WrapTextWriterTargetInvocation {
        @Specialization
        public static Object perform(
                ProtosTextWriterCPrimeExecution.CallState state,
                Object result) {
            return state.invocationSucceeded(result);
        }
    }

    @Operation
    public static final class TextWriterTargetInvocationFailed {
        @Specialization
        public static Object perform(
                ProtosTextWriterCPrimeExecution.CallState state) {
            return state.invocationFailed();
        }
    }

    @Operation
    public static final class AwaitTextWriterTargetFuture {
        @Specialization
        public static Object perform(
                ProtosTextWriterCPrimeExecution.CallState state,
                Object invocation) {
            if (!(invocation
                    instanceof ProtosTextWriterCPrimeExecution.TargetInvocation targetInvocation)) {
                throw new IllegalStateException(
                        "TextWriter C-prime invocation produced an invalid carrier");
            }
            return state.awaitTargetFuture(targetInvocation);
        }
    }

    @Operation
    public static final class ResumeTextWriterTargetFutureWait {
        @Specialization
        public static Object perform(
                ProtosActivation activation,
                Object yielded,
                Object resumeValue) {
            if (!(yielded instanceof ProtosIoOperationSuspension suspension)) {
                throw new IllegalStateException(
                        "TextWriter lower-Future wait yielded an invalid carrier");
            }
            ProtosIoOperation operation =
                    activation.deferredCPrimeOperationForRuntime()
                            .orElseThrow(
                                    () ->
                                            new IllegalStateException(
                                                    "TextWriter lower-Future wait requires an operation-owned activation"));
            if (suspension.operation() != operation) {
                throw new IllegalStateException(
                        "TextWriter lower-Future wait belongs to another operation");
            }
            if (resumeValue != ProtosNullValue.INSTANCE) {
                throw new IllegalStateException(
                        "TextWriter lower-Future wait received unsupported resume transport");
            }
            return suspension.resume();
        }
    }

    @Operation
    public static final class PrepareTextReaderSourceCall {
        @Specialization
        public static PreparedClosureCall perform(
                ProtosTextReaderCPrimeExecution.CallState state,
                ProtosActivation activation) {
            return prepareSend(
                    state.source(),
                    "read",
                    activation,
                    state.arguments());
        }
    }

    @Operation
    public static final class WrapTextReaderSourceInvocation {
        @Specialization
        public static Object perform(
                ProtosTextReaderCPrimeExecution.CallState state,
                Object result) {
            return state.invocationSucceeded(result);
        }
    }

    @Operation
    public static final class TextReaderSourceInvocationFailed {
        @Specialization
        public static Object perform(
                ProtosTextReaderCPrimeExecution.CallState state,
                AbstractTruffleException failure) {
            return state.invocationFailed(failure);
        }
    }

    @Operation
    public static final class AwaitTextReaderSourceFuture {
        @Specialization
        public static Object perform(
                ProtosTextReaderCPrimeExecution.CallState state,
                Object invocation) {
            if (!(invocation
                    instanceof ProtosTextReaderCPrimeExecution.SourceInvocation sourceInvocation)) {
                throw new IllegalStateException(
                        "TextReader C-prime invocation produced an invalid carrier");
            }
            return state.awaitSourceFuture(sourceInvocation);
        }
    }

    @Operation
    public static final class ResumeTextReaderSourceFutureWait {
        @Specialization
        public static Object perform(
                ProtosActivation activation,
                Object yielded,
                Object resumeValue) {
            if (!(yielded instanceof ProtosIoOperationSuspension suspension)) {
                throw new IllegalStateException(
                        "TextReader lower-Future wait yielded an invalid carrier");
            }
            ProtosIoOperation operation =
                    activation.deferredCPrimeOperationForRuntime()
                            .orElseThrow(
                                    () ->
                                            new IllegalStateException(
                                                    "TextReader lower-Future wait requires an operation-owned activation"));
            if (suspension.operation() != operation) {
                throw new IllegalStateException(
                        "TextReader lower-Future wait belongs to another operation");
            }
            if (resumeValue != ProtosNullValue.INSTANCE) {
                throw new IllegalStateException(
                        "TextReader lower-Future wait received unsupported resume transport");
            }
            return suspension.resume();
        }
    }

    @Operation
    public static final class TextReaderInitialNeedInput {
        @Specialization
        public static Object perform() {
            return ProtosTextReaderCPrimeExecution.Advance.needInput();
        }
    }

    @Operation
    public static final class IsTextReaderNeedInput {
        @Specialization
        public static boolean perform(Object advance) {
            if (!(advance instanceof ProtosTextReaderCPrimeExecution.Advance state)) {
                throw new IllegalStateException(
                        "TextReader C-prime advance produced an invalid carrier");
            }
            return state.needsInput();
        }
    }

    @Operation
    public static final class ApplyTextReaderLowerOutcome {
        @Specialization
        public static Object perform(
                ProtosTextReaderCPrimeExecution.CallState state,
                Object outcome) {
            if (!(outcome instanceof ProtosTextReaderCPrimeExecution.LowerOutcome lowerOutcome)) {
                throw new IllegalStateException(
                        "TextReader C-prime lower wait produced an invalid carrier");
            }
            return state.applyLowerOutcome(lowerOutcome);
        }
    }

    @Operation
    public static final class PrepareSuperSendArguments {
        @Specialization
        public static PreparedClosureCall perform(
                String selector,
                ProtosActivation caller,
                @Variadic Object[] supplied) {
            return prepareSuperSend(selector, caller, List.of(supplied));
        }
    }

    @Operation
    public static final class PrepareSuperSendVector {
        @Specialization
        public static PreparedClosureCall perform(
                String selector,
                ProtosActivation caller,
                PreparedArgumentVector supplied) {
            return prepareSuperSend(selector, caller, supplied.snapshot());
        }
    }

    private static PreparedClosureCall prepareSuperSend(
            String selector,
            ProtosActivation caller,
            List<?> supplied) {
        ProtosObjectValue methodHome =
                caller.methodHome()
                        .orElseThrow(
                                () ->
                                        new ProtosSignalException(
                                                ProtosCoreErrors.newInvalidSuper(caller)));
        Object lookupOrigin =
                methodHome.parent()
                        .orElseThrow(
                                () ->
                                        new ProtosSignalException(
                                                ProtosCoreErrors.newSlotNotFound(caller)));

        ProtosPrelude prelude = caller.prelude().orElse(null);
        ProtosSlotLookupResult selected;
        try {
            selected =
                    ProtosValueLookup.lookup(lookupOrigin, selector, prelude)
                            .orElseThrow(
                                    () ->
                                            new ProtosSignalException(
                                                    ProtosCoreErrors.newSlotNotFound(caller)));
        } catch (UnsupportedOperationException unsupportedRepresentation) {
            throw new ProtosSignalException(ProtosCoreErrors.newError(caller));
        }
        if (!(selected.value() instanceof ProtosClosureValue closure)) {
            throw new ProtosSignalException(ProtosCoreErrors.newError(caller));
        }

        return prepareImmediateMethodCall(
                closure,
                caller.receiver(),
                selected.home(),
                supplied,
                caller);
    }

    private static PreparedClosureCall prepareImmediateMethodCall(
            ProtosClosureValue closure,
            Object receiver,
            ProtosObjectValue methodHome,
            List<?> supplied,
            ProtosActivation caller) {
        return prepareImmediateMethodCall(
                closure, receiver, methodHome, supplied, caller, false);
    }

    /**
     * PERF027-A: the guarded Integer native send prepares the same selected
     * method invocation through the PLAT040 deferred activation. The fresh
     * invocation return home is established here exactly as the eager factory
     * would; the guest execution Context and supplied guest Array materialize
     * only if the native body, an Error path, or another observer requests them.
     */
    static PreparedClosureCall prepareDeferredImmediateNativeMethodCall(
            ProtosClosureValue closure,
            Object receiver,
            ProtosObjectValue methodHome,
            List<?> supplied,
            ProtosActivation caller) {
        return prepareImmediateMethodCall(
                closure, receiver, methodHome, supplied, caller, true);
    }

    private static PreparedClosureCall prepareImmediateMethodCall(
            ProtosClosureValue closure,
            Object receiver,
            ProtosObjectValue methodHome,
            List<?> supplied,
            ProtosActivation caller,
            boolean deferGuestInvocationState) {
        ProtosModuleRuntime standardImportRuntime =
                ProtosStandardImportProtocol.selectedRuntimeForBytecodeIntrinsic(
                        receiver,
                        closure,
                        methodHome,
                        caller.prelude().orElse(null));
        if (standardImportRuntime != null) {
            return PreparedClosureCall.moduleInitialization(
                    standardImportRuntime.prepareBytecodeImport(
                            supplied,
                            caller));
        }

        rejectComposedInvocationProjection(closure);
        ProtosActivation activation = deferGuestInvocationState
                ? ProtosActivation.forImmediateMethodInvocationWithReturnHomeForRuntime(
                        closure, supplied, receiver, methodHome, caller.prelude().orElse(null),
                        caller.actorModuleState(), caller.currentModuleKey().orElse(null),
                        caller.executionDomain(),
                        closure.invocationReturnHomeForRuntime())
                : ProtosActivation.forImmediateMethodInvocation(
                        closure, supplied, receiver, methodHome, caller.prelude().orElse(null),
                        caller.actorModuleState(), caller.currentModuleKey().orElse(null),
                        caller.executionDomain());
        attachTaskOrInheritDynamicControlState(
                activation,
                caller);
        return finishPreparingComposedCallByImplementation(
                closure,
                supplied,
                activation);
    }

    private static PreparedClosureCall prepareDirectClosureCall(
            ProtosClosureValue closure,
            ProtosActivation caller) {
        return prepareDirectClosureCall(closure, List.of(), caller);
    }

    private static PreparedClosureCall prepareDirectClosureCall(
            ProtosClosureValue closure,
            List<?> supplied,
            ProtosActivation caller) {
        rejectComposedInvocationProjection(closure);
        ProtosActivation activation =
                ProtosActivation.forClosureInvocation(
                        closure,
                        supplied,
                        caller.prelude().orElse(null),
                        caller.actorModuleState(),
                        caller.currentModuleKey().orElse(null),
                        caller.executionDomain());
        attachTaskOrInheritDynamicControlState(
                activation,
                caller);
        return finishPreparingComposedCallByImplementation(
                closure,
                supplied,
                activation);
    }

    private static void attachTaskOrInheritDynamicControlState(
            ProtosActivation activation,
            ProtosActivation caller) {
        if (caller.task().isPresent()) {
            activation.attachTask(
                    caller.task().orElseThrow());
        } else {
            activation.inheritDynamicControlState(caller);
        }
    }

    private static void rejectComposedInvocationProjection(
            ProtosClosureValue closure) {
        if (closure.requiresContextLocalExecutionProjectionForRuntime()
                && ProtosLanguageContext.currentIfEnteredForRuntime() == null) {
            com.oracle.truffle.api.CompilerDirectives.transferToInterpreter();
            throw new UnsupportedOperationException(
                    "Context-local Closure projection requires an entered Protos Context");
        }
    }

    static PreparedClosureCall prepareSynchronousSourceClosureForRuntime(
            ProtosClosureValue closure,
            List<?> supplied,
            ProtosActivation activation) {
        java.util.Objects.requireNonNull(closure, "closure");
        java.util.Objects.requireNonNull(supplied, "supplied");
        java.util.Objects.requireNonNull(activation, "activation");

        if (closure.nativeBody().isPresent()) {
            throw new IllegalArgumentException(
                    "synchronous source Closure preparation requires a non-native Closure");
        }
        if (activation.task().isPresent()) {
            throw new IllegalArgumentException(
                    "synchronous source Closure preparation cannot own a Task");
        }
        if (!ProtosPolyglotExecutionContext.hasEnteredContextForRuntime()
                || ProtosLanguageContext.currentIfEnteredForRuntime() == null) {
            throw new IllegalStateException(
                    "synchronous source Closure preparation requires an entered host Context");
        }

        ProtosClosureExecutionPlan template =
                closure.executionPlanForRuntimeInvocation();
        if (template.source().isEmpty()) {
            throw new UnsupportedOperationException(
                    "synchronous Bytecode Closure preparation requires exact source");
        }

        return finishPreparingComposedCallByImplementation(
                closure,
                supplied,
                activation);
    }

    private static PreparedClosureCall finishPreparingComposedCallByImplementation(
            ProtosClosureValue closure,
            List<?> supplied,
            ProtosActivation activation) {
        return finishPreparingComposedCall(
                closure,
                supplied,
                activation,
                ProtosStandardObjectProtocol.isStandardEnsureImplementation(closure),
                ProtosStandardErrorProtocol.isStandardHandleImplementation(closure),
                ProtosStandardObjectProtocol.isStandardPreTestLoopImplementation(closure),
                ProtosStandardBooleanProtocol.structuredCallbackKindForImplementation(closure),
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
                ProtosStandardImportProtocol.runtimeForImplementation(closure));
    }

    private static PreparedClosureCall finishPreparingComposedCall(
            ProtosClosureValue closure,
            List<?> supplied,
            ProtosActivation activation,
            boolean structuredEnsure,
            boolean structuredErrorHandler,
            boolean structuredWhile,
            ProtosStandardBooleanProtocol.StructuredCallbackKind structuredBoolean,
            boolean structuredArrayEach,
            boolean structuredBytesEach,
            boolean structuredEnvironmentEach,
            boolean structuredIdentityMapEach,
            boolean structuredMapEach,
            ProtosStandardMapProtocol.StructuredReadLookupKind structuredMapReadLookup,
            boolean structuredMapAtPut,
            boolean structuredMapRemove,
            boolean directControlNative,
            boolean structuredObjectCall,
            ProtosModuleRuntime structuredImportRuntime) {
        if (closure.nativeBody().isPresent()) {
            ProtosNativeClosureBody nativeBody =
                    closure.nativeBody().orElseThrow();
            /*
             * PLAT019 keeps the ordinary non-suspending native path direct and
             * pay-only-when-used. Only bodies carrying the explicit suspension
             * capability enter executeForBytecodeContinuation(); ordinary native
             * bodies execute synchronously through PreparedClosureCall.enterNative().
             * PLAT021 structured-control provenance remains independently guarded.
             */
            return PreparedClosureCall.nativeCall(
                    nativeBody,
                    supplied,
                    activation,
                    structuredEnsure,
                    structuredErrorHandler,
                    structuredWhile,
                    structuredBoolean,
                    structuredArrayEach,
                    structuredBytesEach,
                    structuredEnvironmentEach,
                    structuredIdentityMapEach,
                    structuredMapEach,
                    structuredMapReadLookup,
                    structuredMapAtPut,
                    structuredMapRemove,
                    directControlNative,
                    structuredObjectCall,
                    structuredImportRuntime);
        }
        if (structuredEnsure
                || structuredErrorHandler
                || structuredWhile
                || structuredBoolean != null
                || structuredArrayEach
                || structuredBytesEach
                || structuredEnvironmentEach
                || structuredIdentityMapEach
                || structuredMapEach
                || structuredMapReadLookup != null
                || structuredMapAtPut
                || structuredMapRemove
                || directControlNative
                || structuredObjectCall
                || structuredImportRuntime != null) {
            throw new IllegalStateException(
                    "structured control capability requires the canonical native implementation");
        }
        ProtosClosureExecutionPlan plan = closure.executionPlanForRuntimeInvocation();
        ProtosLanguageContext enteredContext =
                ProtosLanguageContext.currentIfEnteredForRuntime();
        if (enteredContext != null) {
            boolean foreignContextPlan =
                    plan.language().orElseThrow()
                            != enteredContext.languageForRuntime();
            if (closure.requiresContextLocalExecutionProjectionForRuntime()
                    || foreignContextPlan) {
                if (plan.source().isEmpty()) {
                    throw new UnsupportedOperationException(
                            "Bytecode composition cannot project a source-less Closure plan");
                }
                plan =
                        enteredContext.bytecodeExecutionPlanForEnteredClosure(
                                closure,
                                plan);
            }
        }
        return PreparedClosureCall.ordinary(plan.bytecodeActivationTargetForComposition(), activation);
    }

    /**
     * Enters a prepared Closure call. TEST009-I: every specialization receives
     * one concrete final {@link PreparedClosureCall} representation, so the
     * entry, control-transfer, module-failure and runtime-failure calls bind
     * statically instead of dispatching through the interface. Each
     * representation keeps its own behavior: {@link OrdinarySourceCall} uses
     * the {@link ReturnHomeOwningCall} handling and {@link
     * ModuleInitializationCall} the module-initialization lifecycle.
     *
     * <p>The source representations each keep the previous direct-target
     * cache ({@code limit = 3}, replaced by the indirect fallback). Because the
     * DSL counts the limit per specialization, a site that sees both ordinary
     * and module-initialization source calls may hold up to three direct
     * targets of each kind, and one kind going megamorphic no longer drops the
     * other to the indirect path. This affects only call-node caching, never
     * target selection or observable behavior. Both indirect fallbacks share
     * one {@link IndirectCallNode}, as the single indirect fallback did.
     *
     * <p>TEST009-Q: native calls have one generic {@link NativeCall#enterNative}
     * entry; native body identity is not cached.
     */
    @Operation
    public static final class EnterClosureCall {
        @Specialization
        public static Object immediate(ImmediateResultCall prepared) {
            return prepared.enterImmediate();
        }

        @Specialization
        public static Object nativeCall(NativeCall prepared) {
            return prepared.enterNative();
        }

        /*
         * PERF038-H: no-home ordinary calls do not own a lifecycle, cannot
         * consume a non-local return and never remap runtime failures.
         * Their only required exception adaptation is unwrapping the
         * bytecode control-flow bridge.
         */
        @Specialization(
                guards = {
                    "!prepared.hasReturnHomeLifecycle()",
                    "prepared.bodyTarget() == cachedTarget"
                },
                limit = "3")
        public static Object ordinaryDirectNoHome(
                OrdinarySourceCall prepared,
                @Cached("prepared.bodyTarget()")
                        RootCallTarget cachedTarget,
                @Cached("create(cachedTarget)")
                        DirectCallNode node) {
            try {
                return node.call(prepared.targetArguments());
            } catch (ProtosBytecodeControlTransferException bridged) {
                throw bridged.transfer();
            }
        }

        @Specialization(
                guards = {
                    "prepared.hasReturnHomeLifecycle()",
                    "prepared.bodyTarget() == cachedTarget"
                },
                limit = "3")
        public static Object ordinaryDirect(
                OrdinarySourceCall prepared,
                @Cached("prepared.bodyTarget()")
                        RootCallTarget cachedTarget,
                @Cached("create(cachedTarget)")
                        DirectCallNode node) {
            try {
                return node.call(prepared.targetArguments());
            } catch (ProtosBytecodeControlTransferException bridged) {
                return prepared.handleControlTransfer(bridged.transfer());
            } catch (AbstractTruffleException transfer) {
                prepared.failIfModuleInitialization();
                throw transfer;
            } catch (RuntimeException failure) {
                throw prepared.mapRuntimeFailure(failure);
            }
        }

        @Specialization(replaces = {"ordinaryDirectNoHome", "ordinaryDirect"})
        public static Object ordinaryIndirect(
                OrdinarySourceCall prepared,
                @Shared("indirectCall") @Cached IndirectCallNode node) {
            try {
                return node.call(
                        prepared.bodyTarget(),
                        prepared.targetArguments());
            } catch (ProtosBytecodeControlTransferException bridged) {
                return prepared.handleControlTransfer(bridged.transfer());
            } catch (AbstractTruffleException transfer) {
                prepared.failIfModuleInitialization();
                throw transfer;
            } catch (RuntimeException failure) {
                throw prepared.mapRuntimeFailure(failure);
            }
        }

        @Specialization(guards = "prepared.isImmediate()")
        public static Object moduleImmediate(ModuleInitializationCall prepared) {
            return prepared.enterImmediate();
        }

        @Specialization(
                guards = {
                    "!prepared.isImmediate()",
                    "prepared.bodyTarget() == cachedTarget"
                },
                limit = "3")
        public static Object moduleDirect(
                ModuleInitializationCall prepared,
                @Cached("prepared.bodyTarget()")
                        RootCallTarget cachedTarget,
                @Cached("create(cachedTarget)")
                        DirectCallNode node) {
            try {
                return node.call(prepared.targetArguments());
            } catch (ProtosBytecodeControlTransferException bridged) {
                return prepared.handleControlTransfer(bridged.transfer());
            } catch (AbstractTruffleException transfer) {
                prepared.failIfModuleInitialization();
                throw transfer;
            } catch (RuntimeException failure) {
                throw prepared.mapRuntimeFailure(failure);
            }
        }

        @Specialization(replaces = "moduleDirect", guards = "!prepared.isImmediate()")
        public static Object moduleIndirect(
                ModuleInitializationCall prepared,
                @Shared("indirectCall") @Cached IndirectCallNode node) {
            try {
                return node.call(
                        prepared.bodyTarget(),
                        prepared.targetArguments());
            } catch (ProtosBytecodeControlTransferException bridged) {
                return prepared.handleControlTransfer(bridged.transfer());
            } catch (AbstractTruffleException transfer) {
                prepared.failIfModuleInitialization();
                throw transfer;
            } catch (RuntimeException failure) {
                throw prepared.mapRuntimeFailure(failure);
            }
        }
    }

    @Operation
    public static final class IsContinuation {
        @Specialization
        public static boolean perform(Object value) {
            /*
             * A native suspension is the leaf request. Yielding it from this
             * root materializes the real C-prime ContinuationResult; callers
             * then continue to see only ordinary nested ContinuationResults.
             */
            return value instanceof ContinuationResult
                    || value instanceof ProtosNativeSuspension
                    || value instanceof ProtosIoOperationSuspension
                    || value instanceof ProtosIoReleaseSuspension;
        }
    }

    @Operation
    public static final class ResumeContinuation {
        @Specialization
        public static Object nativeSuspension(
                PreparedClosureCall prepared,
                ProtosNativeSuspension suspension,
                Object resumeValue) {
            /*
             * Normal resume values are only C-prime transport through caller
             * roots; the native descriptor owns the state needed to finish the
             * logical operation. B4E reserves only the backend-private exact
             * cancellation transfer as an unwind injection marker. It reaches
             * the suspended native leaf without re-entering/re-observing the
             * detached Future waiter, then enters the existing B4A EH bridge.
             */
            if (resumeValue instanceof ProtosTaskCancellationException cancellation) {
                ProtosTask task =
                        prepared.taskForRuntime();
                if (task == null) {
                    throw new IllegalStateException(
                            "C-prime cancellation resume requires a task");
                }
                if (task.cancellationPhase()
                        != ProtosTask.CancellationPhase.UNWINDING) {
                    throw new IllegalStateException(
                            "C-prime cancellation resume requires UNWINDING phase");
                }
                throw cancellation;
            }
            if (resumeValue instanceof ProtosSignalException rejectedSuspension) {
                /*
                 * D189: the Task gate rejected this suspension across a live synchronous
                 * foreign operation and already removed the wait relationship. The fresh
                 * Error is signaled at the suspension point; the descriptor is never resumed.
                 */
                throw rejectedSuspension;
            }
            return suspension.resume();
        }

        @Specialization
        public static Object ioOperationSuspension(
                PreparedClosureCall prepared,
                ProtosIoOperationSuspension suspension,
                Object resumeValue) {
            if (prepared.activation().task().isPresent()) {
                throw new IllegalStateException(
                        "operation-owned suspension cannot resume through a Task activation");
            }
            if (prepared.activation().deferredCPrimeOperationForRuntime().orElse(null)
                    != suspension.operation()) {
                throw new IllegalStateException(
                        "operation-owned suspension resumed through another I/O operation");
            }
            if (resumeValue != ProtosNullValue.INSTANCE) {
                throw new IllegalStateException(
                        "operation-owned suspension received an unsupported resume transport");
            }
            return suspension.resume();
        }

        @Specialization(
                guards = "result.getContinuationRootNode() == cachedRoot",
                limit = "3")
        public static Object direct(
                PreparedClosureCall prepared,
                ContinuationResult result,
                Object resumeValue,
                @Cached("result.getContinuationRootNode()")
                        ContinuationRootNode cachedRoot,
                @Cached("create(cachedRoot.getCallTarget())")
                        DirectCallNode node) {
            try {
                return node.call(
                        result.getFrame(),
                        resumeValue);
            } catch (ProtosBytecodeControlTransferException bridged) {
                return prepared.handleControlTransfer(bridged.transfer());
            } catch (AbstractTruffleException transfer) {
                prepared.failIfModuleInitialization();
                throw transfer;
            } catch (RuntimeException failure) {
                throw prepared.mapRuntimeFailure(failure);
            }
        }

        @Specialization(replaces = "direct")
        public static Object indirect(
                PreparedClosureCall prepared,
                ContinuationResult result,
                Object resumeValue,
                @Cached IndirectCallNode node) {
            try {
                return node.call(
                        result.getContinuationCallTarget(),
                        result.getFrame(),
                        resumeValue);
            } catch (ProtosBytecodeControlTransferException bridged) {
                return prepared.handleControlTransfer(bridged.transfer());
            } catch (AbstractTruffleException transfer) {
                prepared.failIfModuleInitialization();
                throw transfer;
            } catch (RuntimeException failure) {
                throw prepared.mapRuntimeFailure(failure);
            }
        }
    }

    /** TEST009-H: see {@link CompleteClosureCall}. */
    @Operation
    public static final class FinishClosureCall {
        @Specialization
        public static Object ordinary(OrdinarySourceCall prepared, Object result) {
            return prepared.finish(result);
        }

        @Specialization
        public static Object nativeCall(NativeCall prepared, Object result) {
            return prepared.finish(result);
        }

        @Specialization
        public static Object immediate(ImmediateResultCall prepared, Object result) {
            return prepared.finish(result);
        }

        @Specialization
        public static Object moduleInitialization(
                ModuleInitializationCall prepared,
                Object result) {
            return prepared.finish(result);
        }
    }
}
