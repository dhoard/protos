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

import com.guillermomolina.protos.execution.ProtosBytecodeRootNode.CapturedLexicalWriteTarget;
import com.guillermomolina.protos.execution.ProtosBytecodeRootNode.CapturedNearerScopeAbsence;
import com.guillermomolina.protos.execution.ProtosBytecodeRootNode.CapturedOwnerFrameCache;
import com.guillermomolina.protos.execution.ProtosBytecodeRootNode.GuardedDirectClosureCallTarget;
import com.guillermomolina.protos.execution.ProtosBytecodeRootNode.ImmediateResultCall;
import com.guillermomolina.protos.execution.ProtosBytecodeRootNode.ModuleInitializationCall;
import com.guillermomolina.protos.execution.ProtosBytecodeRootNode.NativeCall;
import com.guillermomolina.protos.execution.ProtosBytecodeRootNode.OrdinarySourceCall;
import com.guillermomolina.protos.execution.ProtosBytecodeRootNode.PrepareSendArguments.GuardedIntegerSend;
import com.guillermomolina.protos.execution.ProtosBytecodeRootNode.PrepareSendArguments.GuardedSendTarget;
import com.guillermomolina.protos.execution.ProtosBytecodeRootNode.PrepareSendArguments.GuardedStructuredSend;
import com.guillermomolina.protos.execution.ProtosBytecodeRootNode.PreparedArgumentVector;
import com.guillermomolina.protos.execution.ProtosBytecodeRootNode.PreparedBooleanCall;
import com.guillermomolina.protos.execution.ProtosBytecodeRootNode.PreparedClosureCall;
import com.guillermomolina.protos.execution.ProtosBytecodeRootNode.PreparedInlineLiteralCall;
import com.guillermomolina.protos.execution.ProtosBytecodeRootNode.PreparedIndexedEachCall;
import com.guillermomolina.protos.execution.ProtosBytecodeRootNode.PreparedLocalEachCall;
import com.guillermomolina.protos.execution.ProtosBytecodeRootNode.PreparedMapInitialDefinition;
import com.guillermomolina.protos.execution.ProtosBytecodeRootNode.PreparedWhileCall;
import com.guillermomolina.protos.execution.ProtosBytecodeRootNode.ResolvedLexicalWriteTarget;
import com.guillermomolina.protos.runtime.ProtosActivation;
import com.guillermomolina.protos.runtime.ProtosBooleanValue;
import com.guillermomolina.protos.runtime.ProtosClosureValue;
import com.guillermomolina.protos.runtime.ProtosMapValue;
import com.guillermomolina.protos.runtime.ProtosNumericValueSupport;
import com.guillermomolina.protos.runtime.ProtosObjectValue;
import com.guillermomolina.protos.runtime.ProtosNullValue;
import com.guillermomolina.protos.runtime.ProtosPrelude;
import com.guillermomolina.protos.runtime.ProtosIntegerValue;
import com.guillermomolina.protos.runtime.ProtosFloatValue;
import com.guillermomolina.protos.runtime.ProtosReturnHome;
import com.guillermomolina.protos.runtime.ProtosSlotLookupResult;
import com.guillermomolina.protos.runtime.ProtosValueLookup;
import com.guillermomolina.protos.semantic.ast.CanonicalClosure;
import com.guillermomolina.protos.semantic.ast.CanonicalIntrinsic;
import com.oracle.truffle.api.Assumption;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.bytecode.BytecodeLocation;
import com.oracle.truffle.api.bytecode.BytecodeNode;
import com.oracle.truffle.api.bytecode.BytecodeRootNode;
import com.oracle.truffle.api.bytecode.ConstantOperand;
import com.oracle.truffle.api.bytecode.ContinuationResult;
import com.oracle.truffle.api.bytecode.ContinuationRootNode;
import com.oracle.truffle.api.bytecode.GenerateBytecode;
import com.oracle.truffle.api.bytecode.LocalAccessor;
import com.oracle.truffle.api.bytecode.LocalRangeAccessor;
import com.oracle.truffle.api.bytecode.MaterializedLocalAccessor;
import com.oracle.truffle.api.bytecode.Operation;
import com.oracle.truffle.api.bytecode.OperationProxy;
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

/**
 * PLAT042 Candidate B′ tagged semantic source interpreter.
 *
 * <p>Every root of this generated interpreter is a guest-semantic top-level,
 * module or Closure activation produced by {@link CanonicalToBytecodeLowerer};
 * its automatic RootTag is therefore truthful. Object bodies are lowered inline
 * and never become roots. Lexically nested Closure roots are created in the same
 * {@code BytecodeRootNodes} parse as their owner, so PERF013 materialized
 * captured accesses use a {@link MaterializedLocalAccessor} of this interpreter
 * only against an owner frame of this interpreter.
 *
 * <p>Structured prepared invocations other than the PLAT043 standard Boolean
 * family are not executed here. {@link
 * EnterNestedStructuredDispatch} enters the untagged {@link
 * ProtosBytecodeRootNode} structured/C-prime root once per such invocation and
 * this root composes the returned C-prime continuation (PLAT014).
 *
 * <p>The operations below are the source-surface subset of the canonical
 * operation set. Each delegates to the single implementation owned by {@link
 * ProtosBytecodeRootNode}; only the operation declarations (operands,
 * specialization guards and caches) are repeated, because one generated Bytecode
 * DSL interpreter cannot declare operations of another and {@code
 * OperationProxy} requires Node-based proxyable classes.
 */
@GenerateBytecode(
        languageClass = ProtosLanguage.class,
        enableYield = true,
        enableTagInstrumentation = true,
        enableRootTagging = true,
        enableRootBodyTagging = false,
        enableMaterializedLocalAccesses = true,
        enableTailCallHandlers = true,
        enableUncachedInterpreter = true,
        boxingEliminationTypes = {int.class, long.class, double.class},
        tagTreeNodeLibrary = ProtosBytecodeTagTreeNodeExports.class)
@OperationProxy(value = ProtosCanonicalBooleanOneNode.class, name = "TryCompactCanonicalBooleanOne")
abstract class ProtosSemanticBytecodeRootNode extends RootNode implements BytecodeRootNode {
    protected ProtosSemanticBytecodeRootNode(
            ProtosLanguage language,
            FrameDescriptor frameDescriptor) {
        super(language, frameDescriptor);
    }

    /*
     * PERF025 frame-materialization slice: the frame-binding range and layout
     * of a Closure root lowered without InstallFrameLexicalAuthority, or null.
     * Read only by tooling scope projection, which receives the live frame.
     */
    private LocalRangeAccessor frameNativeBindingLocals;
    private ProtosFrameLexicalLayout frameNativeBindingLayout;

    /*
     * TEST009-V: the exact root span, recorded by the lowerer only while the diagnostic stable
     * root identity is enabled, so a compiled root has a cross-run name without materializing
     * (reparsing) its lazy source information. Never read by guest execution.
     */
    private ProtosDiagnosticRootIdentity.Span diagnosticRootSpan;

    final void recordDiagnosticRootSpan(ProtosDiagnosticRootIdentity.Span span) {
        this.diagnosticRootSpan = span;
    }

    final ProtosDiagnosticRootIdentity.Span diagnosticRootSpan() {
        return diagnosticRootSpan;
    }

    /**
     * The default Truffle text unless {@link ProtosDiagnosticRootIdentity#PROPERTY} is set; then
     * the stable diagnostic target name that {@code engine.CompileOnly} can select across runs.
     */
    @Override
    public String toString() {
        if (ProtosDiagnosticRootIdentity.ENABLED) {
            return ProtosDiagnosticRootIdentity.targetName(diagnosticRootSpan);
        }
        return super.toString();
    }

    /*
     * PERF032-G6: for the root of a source Closure declaring no parameters, the target of its
     * arity-rejection root, else null. Such a root's own body carries no argument-count check: the
     * only binding step of an empty signature is the excess-argument check (CALLABLES.md,
     * "Normative parameter-binding algorithm"), which precedes every observable effect, so a call
     * supplying arguments is entered at this target instead (see selectSourceEntryTarget). The
     * rejection root is lowered once per definition in the same BytecodeRootNodes group, with the
     * same source section, and only materializes the call's exact activation and signals the
     * unchanged argument-count Error. Attached once, after the group's create() returns.
     */
    @CompilationFinal private RootCallTarget arityRejectionTarget;

    /* PERF038-C: set once, when the first guest exception crosses this root. */
    @CompilationFinal private boolean guestExceptionCrossed;

    final void attachArityRejectionTarget(RootCallTarget target) {
        if (arityRejectionTarget != null) {
            throw new IllegalStateException("arity-rejection target already attached");
        }
        this.arityRejectionTarget = java.util.Objects.requireNonNull(target, "target");
    }

    final RootCallTarget arityRejectionTarget() {
        return arityRejectionTarget;
    }

    /**
     * PERF032-G6 single selection point of the target an ordinary source Closure call enters:
     * {@code bodyTarget} itself, except that a call supplying arguments to a Closure that declares
     * no parameters enters that Closure's arity-rejection root. {@code suppliedArgumentCount} is
     * read from the compact frame arguments or the existing rich activation; nothing is
     * materialized to make this choice.
     */
    static RootCallTarget selectSourceEntryTarget(
            RootCallTarget bodyTarget, int suppliedArgumentCount) {
        if (suppliedArgumentCount != 0
                && bodyTarget.getRootNode() instanceof ProtosSemanticBytecodeRootNode root
                && root.arityRejectionTarget != null) {
            return root.arityRejectionTarget;
        }
        return bodyTarget;
    }

    /*
     * PERF038-H: conservative source-root execution capability.
     * Unknown and dynamically selected operations remain general.
     */
    private boolean provablyNonSuspendingBody;

    final void recordProvablyNonSuspendingBody(boolean proven) {
        this.provablyNonSuspendingBody = proven;
    }

    final boolean provablyNonSuspendingBody() {
        return provablyNonSuspendingBody;
    }

    /*
     * I101-B: a NORMAL scalar-local root returns its private primitive Long
     * carrier without bytecode materialization. Only the caller completion
     * of this root may project that carrier into the observable guest domain.
     * It remains a private carrier for explicitly accepting fused call sites.
     */
    @CompilationFinal private boolean directScalarLocalCarrierBody;

    final void recordDirectScalarLocalCarrierBody(boolean proven) {
        directScalarLocalCarrierBody = proven;
    }

    final boolean directScalarLocalCarrierBody() {
        return directScalarLocalCarrierBody;
    }

    /*
     * PERF032: the body never consults its activation, so a NORMAL-mode host entry may omit the
     * frame header. Not a general source-call ABI contract; the execution mode decides its use.
     */
    private boolean headerlessEntryBody;

    final void recordHeaderlessEntryBody(boolean proven) {
        this.headerlessEntryBody = proven;
    }

    final boolean headerlessEntryBody() {
        return headerlessEntryBody;
    }

    final void recordFrameNativeBindings(
            LocalRangeAccessor frameBackedLocals,
            ProtosFrameLexicalLayout frameBackedLayout) {
        this.frameNativeBindingLocals = frameBackedLocals;
        this.frameNativeBindingLayout = frameBackedLayout;
    }

    /**
     * Installs, for tooling scope projection, the materialized-frame authority
     * of this root over {@code frame} on {@code activation}, when this root was
     * lowered without a persistent frame authority. {@code frame} must be the
     * live frame of an executing activation of this root, and {@code location}
     * a location of this root; only the frame's materialized form is retained.
     */
    final void installFrameNativeAuthorityForTooling(
            ProtosActivation activation,
            BytecodeLocation location,
            com.oracle.truffle.api.frame.Frame frame) {
        if (frameNativeBindingLocals == null) {
            return;
        }
        ProtosBytecodeRootNode.installFrameLexicalAuthorityOnTransition(
                frameNativeBindingLocals,
                frameNativeBindingLayout,
                activation,
                location,
                frame.materialize());
    }

    @Override
    public Object interceptControlFlowException(
            ControlFlowException transfer,
            VirtualFrame frame,
            BytecodeNode bytecodeNode,
            int bytecodeIndex)
            throws Throwable {
        throw ProtosBytecodeControlTransferException.bridge(transfer, bytecodeNode, bytecodeIndex);
    }

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
            CompilerDirectives.transferToInterpreterAndInvalidate();
            guestExceptionCrossed = true;
        }
        /*
         * CLI008-C1: Protos exceptions carry no location node, so the guest stack trace has no
         * bytecode index for the frame they are raised in. Record the first semantic crossing on
         * the occurrence itself; this runs only while an exception is already unwinding.
         */
        ProtosDiagnosticTraceCapture.recordOrigin(exception, frame, bytecodeNode, bytecodeIndex);
        return ProtosBytecodeRootNode.interceptGuestException(exception, frame);
    }

    /**
     * PERF025-H1 sole operand seam for "the current activation" of a semantic source root (see
     * {@code CanonicalToBytecodeLowerer#emitCurrentActivation}). A guarded ordinary send, a stable
     * direct Closure call, or a Task-owned direct source Closure entry enters the root with a
     * compact source-call ABI and no rich activation. The root has no activation prologue: the
     * exact activation is materialized only when an operation that actually needs it executes,
     * at most once, and is published into frame argument 0 (see {@link
     * ProtosFrameArguments#activation}), so the body, root exception interception and debugger
     * scopes all observe one activation identity. A frame that already carries an activation
     * returns it unchanged.
     */
    @Operation
    public static final class CurrentActivation {
        /*
         * PERF038-C: the already-published activation and the first
         * materialization are separate specializations, so a site that has
         * only ever seen one state compiles no path for the other. The guard
         * is re-evaluated on every execution; both specializations return
         * exactly what ProtosFrameArguments.activation returns.
         */
        @Specialization(guards = "hasPublishedActivation(frame)")
        public static ProtosActivation published(@Bind VirtualFrame frame) {
            return ProtosFrameArguments.publishedActivation(frame.getArguments());
        }

        @Specialization(guards = "!hasPublishedActivation(frame)")
        public static ProtosActivation materialize(@Bind VirtualFrame frame) {
            return ProtosFrameArguments.activation(frame);
        }

        @NonIdempotent
        static boolean hasPublishedActivation(VirtualFrame frame) {
            return ProtosFrameArguments.hasPublishedActivation(frame.getArguments());
        }
    }

    /**
     * PERF038-D root-level caller operand of a send preparation (see {@code
     * CanonicalToBytecodeLowerer#emitSendCallerOperand}): the published
     * activation, or, while the root is still in compact source-call form,
     * its frame-argument array itself. It never materializes; {@link
     * PrepareSendArguments} resolves it through {@link
     * PrepareSendArguments#exactCaller} wherever the exact activation is
     * needed, and through {@link
     * PrepareSendArguments#inheritedProvenanceCallerOrNull} where a
     * provenance-equivalent caller suffices.
     */
    @Operation
    public static final class CurrentCallerReference {
        @Specialization(guards = "hasPublishedActivation(frame)")
        public static Object published(@Bind VirtualFrame frame) {
            return ProtosFrameArguments.publishedActivation(frame.getArguments());
        }

        @Specialization(guards = "!hasPublishedActivation(frame)")
        public static Object compact(@Bind VirtualFrame frame) {
            return frame.getArguments();
        }

        @NonIdempotent
        static boolean hasPublishedActivation(VirtualFrame frame) {
            return ProtosFrameArguments.hasPublishedActivation(frame.getArguments());
        }
    }

    // ---- Closure parameter binding -------------------------------------------------

    @Operation
    public static final class HasClosureArgument {
        @Specialization
        public static boolean perform(ProtosActivation activation, int positionalIndex) {
            return ProtosBytecodeRootNode.HasClosureArgument.perform(activation, positionalIndex);
        }
    }

    @Operation
    public static final class LoadClosureArgument {
        @Specialization
        public static Object perform(ProtosActivation activation, int positionalIndex) {
            return ProtosBytecodeRootNode.LoadClosureArgument.perform(activation, positionalIndex);
        }
    }

    /*
     * PERF025-H1 root-level parameter operations. The lowerer emits these
     * instead of the activation-operand forms whenever the current activation
     * is the root's own (never inside an inline Object body or inline callback
     * region). While the frame is still in compact source-call form, the
     * supplied positional values are read directly from the frame arguments;
     * an arity Error, like every other path, materializes the exact activation
     * first and takes the unchanged activation implementation.
     *
     * PERF038-C: each operation's compact path and its activation path are
     * separate specializations (the PERF038-B BindClosureFrameParameter
     * shape). A site that has only ever run in compact frames within its
     * arity compiles the frame-argument read alone; the materialization, the
     * activation implementation and the arity Error are reached only through
     * the respecializing fallback. The guards are re-evaluated on every
     * execution and are exact complements of the former in-line conditions.
     */

    @Operation
    public static final class HasFrameClosureArgument {
        @Specialization(guards = "isCompact(frame)")
        public static boolean compact(int positionalIndex, @Bind VirtualFrame frame) {
            /* PERF038-F: the supplied-count comparison without the layout branch. */
            return ProtosFrameArguments.compactHasSuppliedArgument(
                    frame.getArguments(), positionalIndex);
        }

        @Specialization(guards = "!isCompact(frame)")
        public static boolean materialized(int positionalIndex, @Bind VirtualFrame frame) {
            return ProtosBytecodeRootNode.HasClosureArgument.perform(
                    ProtosFrameArguments.activation(frame.getArguments()), positionalIndex);
        }

        @NonIdempotent
        static boolean isCompact(VirtualFrame frame) {
            return ProtosFrameArguments.isUnmaterializedCompactCall(frame.getArguments());
        }
    }

    @Operation
    public static final class LoadFrameClosureArgument {
        @Specialization(guards = "isCompactSupplied(positionalIndex, frame)")
        public static Object compact(int positionalIndex, @Bind VirtualFrame frame) {
            /* PERF038-E: the guard already proved the index within the supplied count. */
            return ProtosFrameArguments.compactSuppliedArgumentWithinCount(
                    frame.getArguments(), positionalIndex);
        }

        @Specialization(guards = "!isCompactSupplied(positionalIndex, frame)")
        public static Object materialized(int positionalIndex, @Bind VirtualFrame frame) {
            return ProtosBytecodeRootNode.LoadClosureArgument.perform(
                    ProtosFrameArguments.activation(frame.getArguments()), positionalIndex);
        }

        @NonIdempotent
        static boolean isCompactSupplied(int positionalIndex, VirtualFrame frame) {
            Object[] arguments = frame.getArguments();
            return ProtosFrameArguments.isUnmaterializedCompactCall(arguments)
                    && ProtosFrameArguments.compactHasSuppliedArgument(
                            arguments, positionalIndex);
        }
    }

    @Operation
    public static final class CheckFrameClosureArgumentUpperBound {
        @Specialization(guards = "isCompactWithin(maximumPositionalArguments, frame)")
        public static void compact(
                int maximumPositionalArguments,
                @Bind VirtualFrame frame) {
            // The compact supplied count is already within the bound.
        }

        @Specialization(guards = "!isCompactWithin(maximumPositionalArguments, frame)")
        public static void materialized(
                int maximumPositionalArguments,
                @Bind VirtualFrame frame) {
            ProtosBytecodeRootNode.CheckClosureArgumentUpperBound.perform(
                    ProtosFrameArguments.activation(frame.getArguments()),
                    maximumPositionalArguments);
        }

        @NonIdempotent
        static boolean isCompactWithin(int maximumPositionalArguments, VirtualFrame frame) {
            Object[] arguments = frame.getArguments();
            return ProtosFrameArguments.isUnmaterializedCompactCall(arguments)
                    && ProtosFrameArguments.compactSuppliedArgumentCountAtMost(
                            arguments, maximumPositionalArguments);
        }
    }

    @Operation
    public static final class BindClosureParameter {
        @Specialization
        public static void perform(ProtosActivation activation, String name, Object value) {
            ProtosBytecodeRootNode.BindClosureParameter.perform(activation, name, value);
        }
    }

    @Operation
    public static final class BindClosureRest {
        @Specialization
        public static void perform(
                ProtosActivation activation,
                String name,
                int positionalParametersBeforeRest) {
            ProtosBytecodeRootNode.BindClosureRest.perform(
                    activation, name, positionalParametersBeforeRest);
        }
    }

    /**
     * PERF025-H1: binds a statically proven parameter of a root that installs
     * its persistent frame authority, at the ordinal the lowerer took from
     * {@code frameBackedLayout} instead of re-resolving {@code name}, which
     * only the unchanged named fallback uses.
     *
     * <p>PERF030-I: {@code ordinal} is a constant operand because the
     * frame-local accesses of {@link
     * ProtosFrameLexicalBindingAuthority#createFrameBackedBindingAt} require a
     * partial-evaluation constant index.
     */
    @Operation
    @ConstantOperand(
            type = ProtosFrameLexicalLayout.class,
            name = "frameBackedLayout")
    @ConstantOperand(type = int.class, name = "ordinal")
    public static final class BindClosureIndexedParameter {
        @Specialization
        public static void perform(
                ProtosFrameLexicalLayout frameBackedLayout,
                int ordinal,
                ProtosActivation activation,
                String name,
                Object value) {
            ProtosBytecodeRootNode.bindIndexedClosureParameter(
                    frameBackedLayout, activation, ordinal, name, value);
        }
    }

    /** PERF025-H1 rest counterpart of {@link BindClosureIndexedParameter}. */
    @Operation
    @ConstantOperand(
            type = ProtosFrameLexicalLayout.class,
            name = "frameBackedLayout")
    @ConstantOperand(type = int.class, name = "ordinal")
    public static final class BindClosureIndexedRest {
        @Specialization
        public static void perform(
                ProtosFrameLexicalLayout frameBackedLayout,
                int ordinal,
                ProtosActivation activation,
                String name,
                int positionalParametersBeforeRest) {
            ProtosBytecodeRootNode.bindIndexedClosureRest(
                    frameBackedLayout, activation, ordinal, name, positionalParametersBeforeRest);
        }
    }

    @Operation
    public static final class CheckClosureArgumentUpperBound {
        @Specialization
        public static void perform(ProtosActivation activation, int maximumPositionalArguments) {
            ProtosBytecodeRootNode.CheckClosureArgumentUpperBound.perform(
                    activation, maximumPositionalArguments);
        }
    }

    // ---- Lexical lookup and frame-backed bindings ----------------------------------

    @Operation
    public static final class Lookup {
        @Specialization
        public static Object perform(ProtosActivation activation, String name) {
            return ProtosBytecodeRootNode.Lookup.perform(activation, name);
        }
    }

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
            ProtosBytecodeRootNode.InstallFrameLexicalAuthority.perform(
                    frameBackedLocals, frameBackedLayout, activation, bytecodeNode, bytecodeIndex, frame);
        }
    }

    @Operation
    @ConstantOperand(
            type = LocalRangeAccessor.class,
            name = "frameBackedLocals")
    @ConstantOperand(
            type = ProtosFrameLexicalLayout.class,
            name = "frameBackedLayout")
    @ConstantOperand(type = int.class, name = "ordinal")
    public static final class BindClosureFrameParameter {
        /**
         * PERF025-H1: a frame still in compact source-call form has no
         * materialized activation, hence an unobserved execution context
         * without an authority; the frame local is then the binding's only
         * store, exactly as {@code createCurrentFrameBinding} treats an
         * unobserved context. Any other state, including a duplicate
         * creation's Error, takes the unchanged activation path.
         *
         * <p>PERF029: {@code ordinal} is a constant operand, not a stack
         * operand, because every {@link LocalRangeAccessor} local operation
         * requires its index to be a partial-evaluation constant.
         *
         * <p>PERF038-B: the compact direct store and the authoritative
         * activation path are separate specializations. A site that has only
         * ever bound into compact unobserved frames compiles the guard and
         * the slot store alone; the activation, lexical-authority and
         * duplicate-creation machinery is reached only through the
         * respecializing fallback, and even when that fallback is active it
         * stays behind a boundary (the PERF034-B {@code CreateCurrentFrameLocal}
         * shape). The guard is re-evaluated on every execution, so a frame
         * materialized or an ordinal already established since the last
         * execution always takes the fallback.
         */
        @Specialization(guards = "isCompactUnbound(frameBackedLocals, ordinal, bytecodeNode, frame)")
        public static void compact(
                LocalRangeAccessor frameBackedLocals,
                ProtosFrameLexicalLayout frameBackedLayout,
                int ordinal,
                String name,
                Object value,
                @Bind BytecodeNode bytecodeNode,
                @Bind VirtualFrame frame) {
            frameBackedLocals.setObject(bytecodeNode, frame, ordinal, value);
        }

        @Specialization(guards = "!isCompactUnbound(frameBackedLocals, ordinal, bytecodeNode, frame)")
        public static void materialized(
                LocalRangeAccessor frameBackedLocals,
                ProtosFrameLexicalLayout frameBackedLayout,
                int ordinal,
                String name,
                Object value,
                @Bind BytecodeNode bytecodeNode,
                @Bind("$bytecodeIndex") int bytecodeIndex,
                @Bind VirtualFrame frame) {
            slowBind(
                    frameBackedLocals, frameBackedLayout, ordinal,
                    frame.getArguments(), name, value, bytecodeNode, bytecodeIndex,
                    frame.materialize());
        }

        @NonIdempotent
        static boolean isCompactUnbound(
                LocalRangeAccessor frameBackedLocals,
                int ordinal,
                BytecodeNode bytecodeNode,
                VirtualFrame frame) {
            return ProtosFrameArguments.isUnmaterializedCompactCall(frame.getArguments())
                    && frameBackedLocals.isCleared(bytecodeNode, frame, ordinal);
        }

        /**
         * The unchanged authoritative binding, including duplicate-creation
         * Errors and activation materialization. The frame is materialized
         * before entering the boundary so no VirtualFrame escapes.
         */
        @TruffleBoundary
        private static void slowBind(
                LocalRangeAccessor frameBackedLocals,
                ProtosFrameLexicalLayout frameBackedLayout,
                int ordinal,
                Object[] arguments,
                String name,
                Object value,
                BytecodeNode bytecodeNode,
                int bytecodeIndex,
                MaterializedFrame frame) {
            ProtosBytecodeRootNode.BindClosureFrameParameter.perform(
                    frameBackedLocals, frameBackedLayout, ordinal,
                    ProtosFrameArguments.activation(arguments), name, value,
                    bytecodeNode, bytecodeIndex, frame);
        }
    }

    /*
     * PERF025 lazy inline callback activation. The B-prime inline-callback
     * counterparts of the root-level frame-native operations: frame argument
     * zero belongs to the enclosing physical root, so each takes the
     * callback invocation's PreparedInlineLiteralCall carrier plus the
     * callback's block-local range and layout. The callback activation is
     * materialized only when an operation really needs it, and only through
     * MaterializeInlineCallbackActivation's durable transfer
     * (ProtosInlineCallbackFrameBindings).
     */

    @Operation
    @ConstantOperand(
            type = LocalRangeAccessor.class,
            name = "frameBackedLocals")
    @ConstantOperand(
            type = ProtosFrameLexicalLayout.class,
            name = "frameBackedLayout")
    public static final class MaterializeInlineCallbackActivation {
        @Specialization
        public static ProtosActivation perform(
                LocalRangeAccessor frameBackedLocals,
                ProtosFrameLexicalLayout frameBackedLayout,
                PreparedInlineLiteralCall child,
                @Bind BytecodeNode bytecodeNode,
                @Bind VirtualFrame frame) {
            return ProtosInlineCallbackFrameBindings.durableActivation(
                    child, frameBackedLocals, frameBackedLayout, bytecodeNode, frame);
        }
    }

    @Operation
    @ConstantOperand(
            type = LocalRangeAccessor.class,
            name = "frameBackedLocals")
    @ConstantOperand(
            type = ProtosFrameLexicalLayout.class,
            name = "frameBackedLayout")
    public static final class LoadInlineClosureArgument {
        @Specialization
        public static Object perform(
                LocalRangeAccessor frameBackedLocals,
                ProtosFrameLexicalLayout frameBackedLayout,
                PreparedInlineLiteralCall child,
                int positionalIndex,
                @Bind BytecodeNode bytecodeNode,
                @Bind VirtualFrame frame) {
            return ProtosInlineCallbackFrameBindings.loadArgument(
                    child, frameBackedLocals, frameBackedLayout,
                    positionalIndex, bytecodeNode, frame);
        }
    }

    @Operation
    @ConstantOperand(
            type = LocalRangeAccessor.class,
            name = "frameBackedLocals")
    @ConstantOperand(
            type = ProtosFrameLexicalLayout.class,
            name = "frameBackedLayout")
    public static final class CheckInlineClosureArgumentUpperBound {
        @Specialization
        public static void perform(
                LocalRangeAccessor frameBackedLocals,
                ProtosFrameLexicalLayout frameBackedLayout,
                PreparedInlineLiteralCall child,
                int maximumPositionalArguments,
                @Bind BytecodeNode bytecodeNode,
                @Bind VirtualFrame frame) {
            ProtosInlineCallbackFrameBindings.checkArgumentUpperBound(
                    child, frameBackedLocals, frameBackedLayout,
                    maximumPositionalArguments, bytecodeNode, frame);
        }
    }

    @Operation
    @ConstantOperand(
            type = LocalRangeAccessor.class,
            name = "frameBackedLocals")
    @ConstantOperand(
            type = ProtosFrameLexicalLayout.class,
            name = "frameBackedLayout")
    @ConstantOperand(type = int.class, name = "ordinal")
    public static final class BindInlineClosureFrameParameter {
        @Specialization
        public static void perform(
                LocalRangeAccessor frameBackedLocals,
                ProtosFrameLexicalLayout frameBackedLayout,
                int ordinal,
                PreparedInlineLiteralCall child,
                String name,
                Object value,
                @Bind BytecodeNode bytecodeNode,
                @Bind VirtualFrame frame) {
            ProtosInlineCallbackFrameBindings.create(
                    child,
                    frameBackedLocals,
                    frameBackedLayout,
                    ordinal,
                    name,
                    value,
                    bytecodeNode,
                    frame);
        }
    }

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
            ProtosBytecodeRootNode.BindClosureFrameRest.perform(
                    frameBackedLocals, frameBackedLayout, ordinal,
                    activation, name, positionalParametersBeforeRest,
                    bytecodeNode, bytecodeIndex, frame);
        }
    }

    /**
     * PERF034-C: a bytecode-local scalar lane is valid only while the
     * current source-call activation remains compact and unobserved.
     * A materialized activation continues through the authoritative
     * frame-local creation/read operations.
     */
    @Operation
    public static final class IsCompactLocalFrame {
        @Specialization
        public static boolean perform(@Bind VirtualFrame frame) {
            return ProtosFrameArguments.isUnmaterializedCompactScalarLocalCall(
                    frame.getArguments());
        }
    }

    @Operation
    @ConstantOperand(
            type = LocalRangeAccessor.class,
            name = "frameBackedLocals")
    @ConstantOperand(
            type = ProtosFrameLexicalLayout.class,
            name = "frameBackedLayout")
    @ConstantOperand(type = int.class, name = "ordinal")
    public static final class CreateCurrentFrameLocal {
        /**
         * PERF025 compact callee execution: the body-level counterpart of
         * {@link BindClosureFrameParameter}. While the frame is still in
         * compact source-call form the context is unobserved and has no
         * authority, so an ABSENT local is established directly in the frame;
         * a PRESENT one (duplicate creation) or any other state takes the
         * unchanged activation path, including its exact creation Error.
         *
         * <p>PERF030: {@code ordinal} is a constant operand for the same
         * reason as in {@link BindClosureFrameParameter}.
         */
        @Specialization
        public static Object perform(
                LocalRangeAccessor frameBackedLocals,
                ProtosFrameLexicalLayout frameBackedLayout,
                int ordinal,
                String name,
                Object value,
                @Bind BytecodeNode bytecodeNode,
                @Bind("$bytecodeIndex") int bytecodeIndex,
                @Bind VirtualFrame frame) {
            Object[] arguments = frame.getArguments();
            if (ProtosFrameArguments.isUnmaterializedCompactCall(arguments)
                    && frameBackedLocals.isCleared(bytecodeNode, frame, ordinal)) {
                frameBackedLocals.setObject(bytecodeNode, frame, ordinal, value);
                return value;
            }
            return slowCreate(
                    frameBackedLocals, frameBackedLayout, ordinal,
                    arguments, name, value, bytecodeNode, bytecodeIndex,
                    frame.materialize());
        }

        /**
         * PERF034-B: keep rich-activation transitions and duplicate-creation
         * errors outside the compiled ordinary compact-local path.
         *
         * The frame is materialized before entering the boundary. This
         * preserves the existing authoritative creation implementation
         * without allowing a VirtualFrame to escape compilation.
         */
        @TruffleBoundary
        private static Object slowCreate(
                LocalRangeAccessor frameBackedLocals,
                ProtosFrameLexicalLayout frameBackedLayout,
                int ordinal,
                Object[] arguments,
                String name,
                Object value,
                BytecodeNode bytecodeNode,
                int bytecodeIndex,
                MaterializedFrame frame) {
            return ProtosBytecodeRootNode.CreateCurrentFrameLocal.perform(
                    frameBackedLocals, frameBackedLayout, ordinal,
                    ProtosFrameArguments.activation(arguments), name, value,
                    bytecodeNode, bytecodeIndex, frame);
        }
    }

    @Operation
    @ConstantOperand(
            type = LocalRangeAccessor.class,
            name = "frameBackedLocals")
    @ConstantOperand(
            type = ProtosFrameLexicalLayout.class,
            name = "frameBackedLayout")
    @ConstantOperand(type = int.class, name = "ordinal")
    public static final class CreateInlineCurrentFrameLocal {
        @Specialization
        public static Object perform(
                LocalRangeAccessor frameBackedLocals,
                ProtosFrameLexicalLayout frameBackedLayout,
                int ordinal,
                PreparedInlineLiteralCall child,
                String name,
                Object value,
                @Bind BytecodeNode bytecodeNode,
                @Bind VirtualFrame frame) {
            ProtosInlineCallbackFrameBindings.create(
                    child,
                    frameBackedLocals,
                    frameBackedLayout,
                    ordinal,
                    name,
                    value,
                    bytecodeNode,
                    frame);
            return value;
        }
    }

    /*
     * PERF030-I frame-native multiple creation. The lowerer splits it into one
     * prefix observation, which observes the complete fixed source prefix
     * before any binding is created, followed by one scalar
     * CreateCurrentFrameLocal or CreateInlineCurrentFrameLocal per name, in
     * source order, each owning its ordinal as a constant operand. A failing
     * creation leaves the earlier ones in place (no rollback), exactly as
     * MultipleCreateLocalSlots. No ordinal array is ever indexed at run time.
     */

    /** The observed prefix of a frame-native root multiple creation. */
    @Operation
    public static final class ObserveMultipleCreatePrefix {
        @Specialization
        public static Object[] perform(ProtosActivation activation, int required, Object source) {
            return ProtosBytecodeRootNode.observeMultipleCreatePrefix(activation, required, source)
                    .toArray();
        }
    }

    /**
     * The observed prefix of an inline-callback multiple creation; the
     * callback activation is materialized only for the Error of an invalid or
     * insufficient source.
     */
    @Operation
    @ConstantOperand(
            type = LocalRangeAccessor.class,
            name = "frameBackedLocals")
    @ConstantOperand(
            type = ProtosFrameLexicalLayout.class,
            name = "frameBackedLayout")
    public static final class ObserveInlineMultipleCreatePrefix {
        @Specialization
        public static Object[] perform(
                LocalRangeAccessor frameBackedLocals,
                ProtosFrameLexicalLayout frameBackedLayout,
                PreparedInlineLiteralCall child,
                int required,
                Object source,
                @Bind BytecodeNode bytecodeNode,
                @Bind VirtualFrame frame) {
            return ProtosInlineCallbackFrameBindings.observeMultipleCreatePrefix(
                    child, frameBackedLocals, frameBackedLayout,
                    required, source, bytecodeNode, frame);
        }
    }

    /** Element {@code index} of an observed multiple-creation prefix. */
    @Operation
    public static final class ObservedMultipleCreateValue {
        @Specialization
        public static Object perform(Object[] observed, int index) {
            return observed[index];
        }
    }

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
            return ProtosBytecodeRootNode.ReadFrameLocal.perform(
                    accessor,
                    presenceContinuity,
                    activation,
                    name,
                    bytecodeNode,
                    frame);
        }
    }

    /**
     * PERF025-H1 root-level form of {@link ReadFrameLocal}, emitted when the
     * current activation is the root's own. A frame still in compact
     * source-call form denotes a genuine, unobserved execution context, so a
     * PRESENT local is read directly; an ABSENT one (D179 C0) materializes the
     * exact activation and resumes the unchanged fallback.
     */
    @Operation
    @ConstantOperand(type = LocalAccessor.class, name = "accessor")
    @ConstantOperand(type = Assumption.class, name = "presenceContinuity")
    public static final class ReadRootFrameLocal {
        /*
         * The lowerer emitted this operation only for a current Resolved
         * binding. While the call is still compact its guest Context has
         * never become observable, so no D179 structural removal could have
         * made that established binding ABSENT.
         *
         * PERF038-C: the compact read and the presence-aware fallback are
         * separate specializations, so a site that has only read compact
         * frames compiles no fallback call; the guard is re-evaluated on
         * every execution.
         */
        @Specialization(guards = "isCompact(frame)")
        public static Object compact(
                LocalAccessor accessor,
                Assumption presenceContinuity,
                String name,
                @Bind BytecodeNode bytecodeNode,
                @Bind VirtualFrame frame) {
            /*
             * I091: may yield a frame-retained primitive carrier. The lowerer
             * materializes it for every consumer outside the guarded numeric
             * chain.
             */
            return accessor.getObject(bytecodeNode, frame);
        }

        @Specialization(guards = "!isCompact(frame)")
        public static Object materialized(
                LocalAccessor accessor,
                Assumption presenceContinuity,
                String name,
                @Bind BytecodeNode bytecodeNode,
                @Bind VirtualFrame frame) {
            return slowRead(
                    accessor, presenceContinuity, frame.getArguments(),
                    name, bytecodeNode, frame.materialize());
        }

        @NonIdempotent
        static boolean isCompact(VirtualFrame frame) {
            return ProtosFrameArguments.isUnmaterializedCompactCall(frame.getArguments());
        }

        /**
         * PERF034-B: observed contexts retain the authoritative D179
         * presence-aware fallback, but do not expand that machinery into
         * the compiled unobserved compact-local read.
         */
        @TruffleBoundary
        private static Object slowRead(
                LocalAccessor accessor,
                Assumption presenceContinuity,
                Object[] arguments,
                String name,
                BytecodeNode bytecodeNode,
                MaterializedFrame frame) {
            // I091: the lowered consumer materializes a retained carrier.
            return ProtosBytecodeRootNode.ReadFrameLocal.readRetainingCarrier(
                    accessor,
                    presenceContinuity,
                    ProtosFrameArguments.activation(arguments),
                    name,
                    bytecodeNode,
                    frame);
        }
    }

    /** Inline-callback form of {@link ReadFrameLocal}. */
    @Operation
    @ConstantOperand(type = LocalAccessor.class, name = "accessor")
    @ConstantOperand(type = Assumption.class, name = "presenceContinuity")
    @ConstantOperand(
            type = LocalRangeAccessor.class,
            name = "frameBackedLocals")
    @ConstantOperand(
            type = ProtosFrameLexicalLayout.class,
            name = "frameBackedLayout")
    public static final class ReadInlineFrameLocal {
        @Specialization
        public static Object perform(
                LocalAccessor accessor,
                Assumption presenceContinuity,
                LocalRangeAccessor frameBackedLocals,
                ProtosFrameLexicalLayout frameBackedLayout,
                PreparedInlineLiteralCall child,
                String name,
                @Bind BytecodeNode bytecodeNode,
                @Bind VirtualFrame frame) {
            return ProtosInlineCallbackFrameBindings.read(
                    child,
                    accessor,
                    presenceContinuity,
                    frameBackedLocals,
                    frameBackedLayout,
                    name,
                    bytecodeNode,
                    frame);
        }
    }

    /*
     * PERF037-B root-level captured reads. The lowerer emits these instead of
     * the activation-operand forms whenever the current activation is the
     * root's own. While the frame is still in compact source-call form the
     * invocation's guest Context has never become observable, so it cannot
     * hold a binding the static analysis did not declare (D179 C0 late
     * creation needs that Context), and the captured lexical chain is the
     * invoked Closure's own: the nearer-scope presence checks and the owner
     * selection then run without materializing the activation. A
     * materialized frame, and every fallback, take the unchanged
     * activation path.
     */

    /*
     * PERF037-C: the name and lexical depth are constant operands, so the
     * nearer-scope walk has a partial-evaluation-constant length. The compact
     * and materialized-activation forms are separate specializations, so a
     * root whose invocations stay compact never compiles the activation path.
     * The compact form retains a CapturedNearerScopeAbsence proof instead of
     * asking each nearer scope for membership; the uncached interpreter uses
     * an unproven one and walks exactly as before.
     */

    @Operation
    @ConstantOperand(type = int.class, name = "frameOrdinal")
    @ConstantOperand(type = String.class, name = "name")
    @ConstantOperand(type = int.class, name = "lexicalDepth")
    public static final class ReadCapturedFrameLocalAtRoot {
        /*
         * PERF038-C: compact and materialized frames are separate
         * specializations (guard re-evaluated on every execution), and the
         * compact miss materializes and looks up behind one boundary instead
         * of two, so a compact-only site compiles no activation path.
         */
        @Specialization(guards = "isCompact(frame)")
        public static Object compact(
                int frameOrdinal,
                String name,
                int lexicalDepth,
                @Bind VirtualFrame frame,
                @Cached(
                                value = "createNearerScopeAbsence(frame, name, lexicalDepth)",
                                uncached = "unprovenNearerScopeAbsence(lexicalDepth)")
                        CapturedNearerScopeAbsence nearerAbsence) {
            Object[] arguments = frame.getArguments();
            if (lexicalDepth > 0) {
                Object value =
                        ProtosBytecodeRootNode.readOwnerFrameBindingOrNull(
                                nearerAbsence.ownerOrNull(
                                        ProtosFrameArguments.compactCapturedLexicalEnvironment(
                                                arguments),
                                        name),
                                name,
                                frameOrdinal);
                if (value != null) {
                    return value;
                }
            }
            return lookupCapturedFallbackAtRoot(arguments, name);
        }

        @Specialization(guards = "!isCompact(frame)")
        public static Object materialized(
                int frameOrdinal,
                String name,
                int lexicalDepth,
                @Bind VirtualFrame frame) {
            return ProtosBytecodeRootNode.ReadCapturedFrameLocal.perform(
                    frameOrdinal,
                    ProtosFrameArguments.activation(frame.getArguments()),
                    name,
                    lexicalDepth);
        }

        @NonIdempotent
        static boolean isCompact(VirtualFrame frame) {
            return ProtosFrameArguments.isUnmaterializedCompactCall(frame.getArguments());
        }

        static CapturedNearerScopeAbsence createNearerScopeAbsence(
                VirtualFrame frame, String name, int lexicalDepth) {
            return CapturedNearerScopeAbsence.create(
                    ProtosFrameArguments.compactCapturedLexicalEnvironment(frame.getArguments()),
                    name,
                    lexicalDepth);
        }

        static CapturedNearerScopeAbsence unprovenNearerScopeAbsence(int lexicalDepth) {
            return CapturedNearerScopeAbsence.unproven(lexicalDepth);
        }
    }

    @Operation
    @ConstantOperand(type = MaterializedLocalAccessor.class)
    @ConstantOperand(type = String.class, name = "name")
    @ConstantOperand(type = int.class, name = "lexicalDepth")
    public static final class SelectCapturedMaterializedOwnerFrameAtRoot {
        /* PERF038-C: compact and materialized frames specialize separately. */
        @Specialization(guards = "isCompact(frame)")
        public static MaterializedFrame compact(
                MaterializedLocalAccessor accessor,
                String name,
                int lexicalDepth,
                @Bind BytecodeNode bytecodeNode,
                @Bind VirtualFrame frame,
                @Cached(
                                value = "createNearerScopeAbsence(frame, name, lexicalDepth)",
                                uncached = "unprovenNearerScopeAbsence(lexicalDepth)")
                        CapturedNearerScopeAbsence nearerAbsence,
                @Cached(
                                value = "createOwnerFrameCache()",
                                uncached = "retiredOwnerFrameCache()")
                        CapturedOwnerFrameCache ownerFrameCache) {
            if (lexicalDepth > 0) {
                return ownerFrameCache.ownerFrameOrNull(
                        accessor,
                        nearerAbsence.ownerOrNull(
                                ProtosFrameArguments.compactCapturedLexicalEnvironment(
                                        frame.getArguments()),
                                name),
                        name,
                        bytecodeNode,
                        nearerAbsence);
            }
            return null;
        }

        @Specialization(guards = "!isCompact(frame)")
        public static MaterializedFrame materialized(
                MaterializedLocalAccessor accessor,
                String name,
                int lexicalDepth,
                @Bind BytecodeNode bytecodeNode,
                @Bind VirtualFrame frame) {
            return ProtosBytecodeRootNode.SelectCapturedMaterializedOwnerFrame.perform(
                    accessor,
                    ProtosFrameArguments.activation(frame.getArguments()),
                    name,
                    lexicalDepth,
                    bytecodeNode);
        }

        @NonIdempotent
        static boolean isCompact(VirtualFrame frame) {
            return ProtosFrameArguments.isUnmaterializedCompactCall(frame.getArguments());
        }

        static CapturedNearerScopeAbsence createNearerScopeAbsence(
                VirtualFrame frame, String name, int lexicalDepth) {
            return CapturedNearerScopeAbsence.create(
                    ProtosFrameArguments.compactCapturedLexicalEnvironment(frame.getArguments()),
                    name,
                    lexicalDepth);
        }

        static CapturedNearerScopeAbsence unprovenNearerScopeAbsence(int lexicalDepth) {
            return CapturedNearerScopeAbsence.uncached(lexicalDepth);
        }

        static CapturedOwnerFrameCache createOwnerFrameCache() {
            return CapturedOwnerFrameCache.create();
        }

        static CapturedOwnerFrameCache retiredOwnerFrameCache() {
            return CapturedOwnerFrameCache.retired();
        }
    }

    @Operation
    @ConstantOperand(type = String.class, name = "name")
    public static final class ReadCapturedFallbackAtRoot {
        @Specialization
        public static Object perform(String name, @Bind VirtualFrame frame) {
            return lookupCapturedFallbackAtRoot(frame.getArguments(), name);
        }
    }

    /**
     * PERF038-C: the generic captured lookup of a root-level read, including
     * the exact activation materialization and publication it may require,
     * as one host call instead of a materialization call followed by a
     * lookup call. Receives the frame-argument array, never the frame.
     */
    @TruffleBoundary
    static Object lookupCapturedFallbackAtRoot(Object[] arguments, String name) {
        return ProtosBytecodeRootNode.lookupCapturedFallback(
                ProtosFrameArguments.activation(arguments), name);
    }

    @Operation
    @ConstantOperand(type = int.class, name = "frameOrdinal")
    public static final class ReadCapturedFrameLocal {
        @Specialization
        public static Object perform(
                int frameOrdinal,
                ProtosActivation activation,
                String name,
                int lexicalDepth) {
            return ProtosBytecodeRootNode.ReadCapturedFrameLocal.perform(
                    frameOrdinal, activation, name, lexicalDepth);
        }
    }

    /*
     * BUG018-C: a proven captured-materialized read is lowered as owner-frame
     * selection, IsCapturedOwnerFrameSelected, then either the builtin
     * LoadLocalMaterialized of the owner local from the selected frame or the
     * generic captured lookup. Selection never inspects the value.
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
            return ProtosBytecodeRootNode.SelectCapturedMaterializedOwnerFrame.perform(
                    accessor, activation, name, lexicalDepth, bytecodeNode);
        }
    }

    /** True when owner-frame selection produced a frame (host {@code null} means none). */
    @Operation
    public static final class IsCapturedOwnerFrameSelected {
        @Specialization
        public static boolean perform(Object selectedOwnerFrame) {
            return selectedOwnerFrame != null;
        }
    }

    @Operation
    public static final class ReadCapturedFallback {
        @Specialization
        public static Object perform(ProtosActivation activation, String name) {
            return ProtosBytecodeRootNode.lookupCapturedFallback(activation, name);
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
            return ProtosBytecodeRootNode.ResolveCapturedWritableLexicalTarget.perform(
                    frameOrdinal, activation, name, lexicalDepth);
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
            return ProtosBytecodeRootNode.AssignCapturedFrameLocal.perform(
                    frameOrdinal, activation, destination, name, value);
        }
    }

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
            return ProtosBytecodeRootNode.ResolveCapturedMaterializedWritableLexicalTarget.perform(
                    accessor, activation, name, lexicalDepth, bytecodeNode);
        }
    }

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
            return ProtosBytecodeRootNode.AssignCapturedMaterializedLocal.perform(
                    accessor, activation, destination, name, value, bytecodeNode);
        }
    }

    /*
     * PERF025 slice 3: inline-callback consumers whose successful path
     * observes neither the callback activation nor its Context. Each takes
     * the PreparedInlineLiteralCall carrier; the activation is materialized
     * (through ProtosInlineCallbackFrameBindings.durableActivation) only for
     * an observer, Error or fallback path, which then runs the unchanged
     * activation operation.
     */

    @Operation
    @ConstantOperand(
            type = LocalRangeAccessor.class,
            name = "frameBackedLocals")
    @ConstantOperand(
            type = ProtosFrameLexicalLayout.class,
            name = "frameBackedLayout")
    @ConstantOperand(type = int.class, name = "frameOrdinal")
    public static final class ReadInlineCapturedFrameLocal {
        @Specialization
        public static Object perform(
                LocalRangeAccessor frameBackedLocals,
                ProtosFrameLexicalLayout frameBackedLayout,
                int frameOrdinal,
                PreparedInlineLiteralCall child,
                String name,
                int lexicalDepth,
                @Bind BytecodeNode bytecodeNode,
                @Bind VirtualFrame frame) {
            return ProtosInlineCallbackFrameBindings.readCaptured(
                    child, frameBackedLocals, frameBackedLayout,
                    name, lexicalDepth, frameOrdinal, bytecodeNode, frame);
        }
    }

    @Operation
    @ConstantOperand(type = MaterializedLocalAccessor.class)
    @ConstantOperand(
            type = LocalRangeAccessor.class,
            name = "frameBackedLocals")
    @ConstantOperand(
            type = ProtosFrameLexicalLayout.class,
            name = "frameBackedLayout")
    public static final class SelectInlineCapturedMaterializedOwnerFrame {
        @Specialization
        public static MaterializedFrame perform(
                MaterializedLocalAccessor accessor,
                LocalRangeAccessor frameBackedLocals,
                ProtosFrameLexicalLayout frameBackedLayout,
                PreparedInlineLiteralCall child,
                String name,
                int lexicalDepth,
                @Bind BytecodeNode bytecodeNode,
                @Bind VirtualFrame frame) {
            return ProtosInlineCallbackFrameBindings.selectCapturedMaterializedOwnerFrame(
                    accessor, child, frameBackedLocals, frameBackedLayout,
                    name, lexicalDepth, bytecodeNode, frame);
        }
    }

    @Operation
    @ConstantOperand(
            type = LocalRangeAccessor.class,
            name = "frameBackedLocals")
    @ConstantOperand(
            type = ProtosFrameLexicalLayout.class,
            name = "frameBackedLayout")
    public static final class ReadInlineCapturedFallback {
        @Specialization
        public static Object perform(
                LocalRangeAccessor frameBackedLocals,
                ProtosFrameLexicalLayout frameBackedLayout,
                PreparedInlineLiteralCall child,
                String name,
                @Bind BytecodeNode bytecodeNode,
                @Bind VirtualFrame frame) {
            return ProtosInlineCallbackFrameBindings.readCapturedFallback(
                    child, frameBackedLocals, frameBackedLayout,
                    name, bytecodeNode, frame);
        }
    }

    @Operation
    @ConstantOperand(
            type = LocalRangeAccessor.class,
            name = "frameBackedLocals")
    @ConstantOperand(
            type = ProtosFrameLexicalLayout.class,
            name = "frameBackedLayout")
    @ConstantOperand(type = int.class, name = "frameOrdinal")
    public static final class ResolveInlineCapturedWritableLexicalTarget {
        @Specialization
        public static CapturedLexicalWriteTarget perform(
                LocalRangeAccessor frameBackedLocals,
                ProtosFrameLexicalLayout frameBackedLayout,
                int frameOrdinal,
                PreparedInlineLiteralCall child,
                String name,
                int lexicalDepth,
                @Bind BytecodeNode bytecodeNode,
                @Bind VirtualFrame frame) {
            return ProtosInlineCallbackFrameBindings.resolveCapturedWriteTarget(
                    child, frameBackedLocals, frameBackedLayout,
                    name, lexicalDepth, frameOrdinal, bytecodeNode, frame);
        }
    }

    @Operation
    @ConstantOperand(
            type = LocalRangeAccessor.class,
            name = "frameBackedLocals")
    @ConstantOperand(
            type = ProtosFrameLexicalLayout.class,
            name = "frameBackedLayout")
    @ConstantOperand(type = int.class, name = "frameOrdinal")
    public static final class AssignInlineCapturedFrameLocal {
        @Specialization
        public static Object perform(
                LocalRangeAccessor frameBackedLocals,
                ProtosFrameLexicalLayout frameBackedLayout,
                int frameOrdinal,
                PreparedInlineLiteralCall child,
                CapturedLexicalWriteTarget destination,
                String name,
                Object value,
                @Bind BytecodeNode bytecodeNode,
                @Bind VirtualFrame frame) {
            return ProtosInlineCallbackFrameBindings.assignCaptured(
                    child, frameBackedLocals, frameBackedLayout,
                    frameOrdinal, destination, name, value, bytecodeNode, frame);
        }
    }

    @Operation
    @ConstantOperand(type = MaterializedLocalAccessor.class)
    @ConstantOperand(
            type = LocalRangeAccessor.class,
            name = "frameBackedLocals")
    @ConstantOperand(
            type = ProtosFrameLexicalLayout.class,
            name = "frameBackedLayout")
    public static final class ResolveInlineCapturedMaterializedWritableLexicalTarget {
        @Specialization
        public static CapturedLexicalWriteTarget perform(
                MaterializedLocalAccessor accessor,
                LocalRangeAccessor frameBackedLocals,
                ProtosFrameLexicalLayout frameBackedLayout,
                PreparedInlineLiteralCall child,
                String name,
                int lexicalDepth,
                @Bind BytecodeNode bytecodeNode,
                @Bind VirtualFrame frame) {
            return ProtosInlineCallbackFrameBindings.resolveCapturedMaterializedWriteTarget(
                    accessor, child, frameBackedLocals, frameBackedLayout,
                    name, lexicalDepth, bytecodeNode, frame);
        }
    }

    @Operation
    @ConstantOperand(type = MaterializedLocalAccessor.class)
    @ConstantOperand(
            type = LocalRangeAccessor.class,
            name = "frameBackedLocals")
    @ConstantOperand(
            type = ProtosFrameLexicalLayout.class,
            name = "frameBackedLayout")
    public static final class AssignInlineCapturedMaterializedLocal {
        @Specialization
        public static Object perform(
                MaterializedLocalAccessor accessor,
                LocalRangeAccessor frameBackedLocals,
                ProtosFrameLexicalLayout frameBackedLayout,
                PreparedInlineLiteralCall child,
                CapturedLexicalWriteTarget destination,
                String name,
                Object value,
                @Bind BytecodeNode bytecodeNode,
                @Bind VirtualFrame frame) {
            return ProtosInlineCallbackFrameBindings.assignCapturedMaterialized(
                    accessor, child, frameBackedLocals, frameBackedLayout,
                    destination, name, value, bytecodeNode, frame);
        }
    }

    /** Inline-callback {@code THIS}: the receiver a direct Closure call binds. */
    @Operation
    @ConstantOperand(
            type = LocalRangeAccessor.class,
            name = "frameBackedLocals")
    @ConstantOperand(
            type = ProtosFrameLexicalLayout.class,
            name = "frameBackedLayout")
    public static final class LoadInlineCallbackReceiver {
        @Specialization
        public static Object perform(
                LocalRangeAccessor frameBackedLocals,
                ProtosFrameLexicalLayout frameBackedLayout,
                PreparedInlineLiteralCall child,
                @Bind BytecodeNode bytecodeNode,
                @Bind VirtualFrame frame) {
            return ProtosInlineCallbackFrameBindings.receiver(
                    child, frameBackedLocals, frameBackedLayout, bytecodeNode, frame);
        }
    }

    /**
     * Inline-callback {@link ReadMember}: the same PIC tiers, selected through
     * the invocation's prelude. Selection and receiver-bound extraction never
     * observe the activation; an absent member or unsupported representation
     * raises its exact Error from the materialized activation.
     */
    @Operation
    @ConstantOperand(
            type = LocalRangeAccessor.class,
            name = "frameBackedLocals")
    @ConstantOperand(
            type = ProtosFrameLexicalLayout.class,
            name = "frameBackedLayout")
    @ConstantOperand(type = String.class, name = "name")
    public static final class ReadInlineMember {
        @Specialization(
                guards = {
                    "cachedLookup != null",
                    "matchesSharedInheritedLookup(receiver, name, cachedLookup)",
                    "cachedLookup.nonClosureContinuity() != null"
                },
                assumptions = {
                    "cachedLookup.stability()",
                    "cachedLookup.nonClosureContinuity()"
                },
                limit = "3")
        public static Object guardedSharedInheritedPlain(
                LocalRangeAccessor frameBackedLocals,
                ProtosFrameLexicalLayout frameBackedLayout,
                String name,
                PreparedInlineLiteralCall child,
                Object receiver,
                @Bind("child.prelude()") ProtosPrelude prelude,
                @Cached("createSharedInheritedLookupForPrelude(receiver, name, prelude)")
                        ProtosValueLookup.SharedInheritedSlotSelection cachedLookup) {
            return ProtosValueLookup.guardedPlainSlotValue(
                    cachedLookup.parentSelection());
        }

        @Specialization(
                guards = {
                    "cachedLookup != null",
                    "matchesSharedInheritedLookup(receiver, name, cachedLookup)"
                },
                assumptions = "cachedLookup.stability()",
                limit = "3")
        public static Object guardedSharedInherited(
                LocalRangeAccessor frameBackedLocals,
                ProtosFrameLexicalLayout frameBackedLayout,
                String name,
                PreparedInlineLiteralCall child,
                Object receiver,
                @Bind("child.prelude()") ProtosPrelude prelude,
                @Cached("createSharedInheritedLookupForPrelude(receiver, name, prelude)")
                        ProtosValueLookup.SharedInheritedSlotSelection cachedLookup,
                @Bind Node node,
                @Cached InlinedBranchProfile closureExtraction) {
            return ProtosValueLookup.materializeGuardedMemberRead(
                    receiver, cachedLookup, node, closureExtraction);
        }

        @Specialization(
                guards = {
                    "receiver == cachedReceiver",
                    "cachedLookup != null",
                    "cachedLookup.nonClosureContinuity() != null"
                },
                assumptions = {
                    "cachedLookup.stability()",
                    "cachedLookup.nonClosureContinuity()"
                },
                limit = "3")
        public static Object guardedExactReceiverPlain(
                LocalRangeAccessor frameBackedLocals,
                ProtosFrameLexicalLayout frameBackedLayout,
                String name,
                PreparedInlineLiteralCall child,
                Object receiver,
                @Bind("child.prelude()") ProtosPrelude prelude,
                @Cached("receiver") Object cachedReceiver,
                @Cached("createGuardedLookupForPrelude(receiver, name, prelude)")
                        ProtosValueLookup.GuardedSlotSelection cachedLookup) {
            return ProtosValueLookup.guardedPlainSlotValue(cachedLookup);
        }

        @Specialization(
                guards = {
                    "receiver == cachedReceiver",
                    "cachedLookup != null"
                },
                assumptions = "cachedLookup.stability()",
                limit = "3")
        public static Object guardedExactReceiver(
                LocalRangeAccessor frameBackedLocals,
                ProtosFrameLexicalLayout frameBackedLayout,
                String name,
                PreparedInlineLiteralCall child,
                Object receiver,
                @Bind("child.prelude()") ProtosPrelude prelude,
                @Cached("receiver") Object cachedReceiver,
                @Cached("createGuardedLookupForPrelude(receiver, name, prelude)")
                        ProtosValueLookup.GuardedSlotSelection cachedLookup,
                @Bind Node node,
                @Cached InlinedBranchProfile closureExtraction) {
            return ProtosValueLookup.materializeGuardedMemberRead(
                    receiver, cachedLookup, node, closureExtraction);
        }

        @Specialization(replaces = {"guardedSharedInheritedPlain", "guardedSharedInherited", "guardedExactReceiverPlain", "guardedExactReceiver"})
        public static Object perform(
                LocalRangeAccessor frameBackedLocals,
                ProtosFrameLexicalLayout frameBackedLayout,
                String name,
                PreparedInlineLiteralCall child,
                Object receiver,
                @Bind BytecodeNode bytecodeNode,
                @Bind VirtualFrame frame) {
            Object value =
                    ProtosBytecodeRootNode.ReadMember.readMemberOrNull(
                            receiver, name, child.prelude());
            if (value != null) {
                return value;
            }
            return ProtosBytecodeRootNode.ReadMember.perform(
                    ProtosInlineCallbackFrameBindings.durableActivation(
                            child, frameBackedLocals, frameBackedLayout, bytecodeNode, frame),
                    receiver,
                    name);
        }

        static ProtosValueLookup.SharedInheritedSlotSelection createSharedInheritedLookupForPrelude(
                Object receiver, String name, ProtosPrelude prelude) {
            return ProtosBytecodeRootNode.ReadMember.createSharedInheritedLookupForPrelude(
                    receiver, name, prelude);
        }

        static boolean matchesSharedInheritedLookup(
                Object receiver,
                String name,
                ProtosValueLookup.SharedInheritedSlotSelection cachedLookup) {
            return ProtosBytecodeRootNode.ReadMember.matchesSharedInheritedLookup(
                    receiver, name, cachedLookup);
        }

        static ProtosValueLookup.GuardedSlotSelection createGuardedLookupForPrelude(
                Object receiver, String name, ProtosPrelude prelude) {
            return ProtosBytecodeRootNode.ReadMember.createGuardedLookupForPrelude(
                    receiver, name, prelude);
        }
    }

    @Operation
    public static final class ResolveWritableLexicalTarget {
        @Specialization
        public static ResolvedLexicalWriteTarget perform(ProtosActivation activation, String name) {
            return ProtosBytecodeRootNode.ResolveWritableLexicalTarget.perform(activation, name);
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
            return ProtosBytecodeRootNode.AssignResolvedLexicalTarget.perform(
                    activation, destination, name, value);
        }
    }

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
            return ProtosBytecodeRootNode.ResolveCurrentFrameLocalWriteTarget.perform(
                    accessor, activation, name, bytecodeNode, frame);
        }
    }

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
            return ProtosBytecodeRootNode.AssignCurrentFrameLocal.perform(
                    accessor, activation, destination, name, value, bytecodeNode, frame);
        }
    }

    /**
     * PERF025 compact callee execution: root-level form of {@link
     * ResolveCurrentFrameLocalWriteTarget}, emitted when the current
     * activation is the root's own. A frame still in compact source-call form
     * denotes a genuine, unobserved (hence OPEN, non-FROZEN) execution
     * context, so a PRESENT local is selected directly; an ABSENT one (D179
     * C0) materializes the exact activation and runs the unchanged selection.
     */
    @Operation
    @ConstantOperand(type = LocalAccessor.class, name = "accessor")
    public static final class ResolveRootFrameLocalWriteTarget {
        @Specialization
        public static ResolvedLexicalWriteTarget perform(
                LocalAccessor accessor,
                String name,
                @Bind BytecodeNode bytecodeNode,
                @Bind VirtualFrame frame) {
            Object[] arguments = frame.getArguments();
            if (ProtosFrameArguments.isUnmaterializedCompactCall(arguments)
                    && !accessor.isCleared(bytecodeNode, frame)) {
                return ResolvedLexicalWriteTarget.STATIC_CURRENT_FRAME_LOCAL;
            }
            return ProtosBytecodeRootNode.ResolveCurrentFrameLocalWriteTarget.perform(
                    accessor, ProtosFrameArguments.activation(arguments), name, bytecodeNode, frame);
        }
    }

    /**
     * PERF025 compact callee execution: root-level form of {@link
     * AssignCurrentFrameLocal}. The retained static destination of a still
     * compact (never FROZEN) context is written directly when PRESENT; a local
     * cleared by the RHS, and every other destination, takes the unchanged
     * activation path and its exact mutation Error.
     */
    @Operation
    @ConstantOperand(type = LocalAccessor.class, name = "accessor")
    public static final class AssignRootFrameLocal {
        @Specialization
        public static Object perform(
                LocalAccessor accessor,
                ResolvedLexicalWriteTarget destination,
                String name,
                Object value,
                @Bind BytecodeNode bytecodeNode,
                @Bind VirtualFrame frame) {
            Object[] arguments = frame.getArguments();
            if (destination == ResolvedLexicalWriteTarget.STATIC_CURRENT_FRAME_LOCAL
                    && ProtosFrameArguments.isUnmaterializedCompactCall(arguments)
                    && !accessor.isCleared(bytecodeNode, frame)) {
                accessor.setObject(bytecodeNode, frame, value);
                return value;
            }
            return ProtosBytecodeRootNode.AssignCurrentFrameLocal.perform(
                    accessor, ProtosFrameArguments.activation(arguments),
                    destination, name, value, bytecodeNode, frame);
        }
    }

    /** Inline-callback form of {@link ResolveCurrentFrameLocalWriteTarget}. */
    @Operation
    @ConstantOperand(type = LocalAccessor.class, name = "accessor")
    @ConstantOperand(
            type = LocalRangeAccessor.class,
            name = "frameBackedLocals")
    @ConstantOperand(
            type = ProtosFrameLexicalLayout.class,
            name = "frameBackedLayout")
    public static final class ResolveInlineFrameLocalWriteTarget {
        @Specialization
        public static ResolvedLexicalWriteTarget perform(
                LocalAccessor accessor,
                LocalRangeAccessor frameBackedLocals,
                ProtosFrameLexicalLayout frameBackedLayout,
                PreparedInlineLiteralCall child,
                String name,
                @Bind BytecodeNode bytecodeNode,
                @Bind VirtualFrame frame) {
            return ProtosInlineCallbackFrameBindings.resolveWriteTarget(
                    child, accessor, frameBackedLocals, frameBackedLayout,
                    name, bytecodeNode, frame);
        }
    }

    /** Inline-callback form of {@link AssignCurrentFrameLocal}. */
    @Operation
    @ConstantOperand(type = LocalAccessor.class, name = "accessor")
    @ConstantOperand(
            type = LocalRangeAccessor.class,
            name = "frameBackedLocals")
    @ConstantOperand(
            type = ProtosFrameLexicalLayout.class,
            name = "frameBackedLayout")
    public static final class AssignInlineFrameLocal {
        @Specialization
        public static Object perform(
                LocalAccessor accessor,
                LocalRangeAccessor frameBackedLocals,
                ProtosFrameLexicalLayout frameBackedLayout,
                PreparedInlineLiteralCall child,
                ResolvedLexicalWriteTarget destination,
                String name,
                Object value,
                @Bind BytecodeNode bytecodeNode,
                @Bind VirtualFrame frame) {
            return ProtosInlineCallbackFrameBindings.assign(
                    child, accessor, frameBackedLocals, frameBackedLayout,
                    destination, name, value, bytecodeNode, frame);
        }
    }

    // ---- Slot creation/assignment, members, identity, intrinsics -------------------

    @Operation
    public static final class RequireObjectMutationTarget {
        @Specialization
        public static ProtosObjectValue perform(ProtosActivation activation, Object target) {
            return ProtosBytecodeRootNode.RequireObjectMutationTarget.perform(activation, target);
        }
    }

    @Operation
    public static final class CreateCurrentLocalSlot {
        @Specialization
        public static Object perform(ProtosActivation activation, String name, Object value) {
            return ProtosBytecodeRootNode.CreateCurrentLocalSlot.perform(activation, name, value);
        }
    }

    /**
     * PERF030: {@link CreateCurrentLocalSlot} for a statically proven current
     * binding of a root that installs its persistent frame authority. The
     * {@code ordinal} is a constant operand because the frame-local accesses
     * of {@link ProtosFrameLexicalBindingAuthority#createFrameBackedBindingAt}
     * require a partial-evaluation constant index; only the unchanged named
     * fallback resolves {@code name}.
     */
    @Operation
    @ConstantOperand(
            type = ProtosFrameLexicalLayout.class,
            name = "frameBackedLayout")
    @ConstantOperand(type = int.class, name = "ordinal")
    public static final class CreateCurrentIndexedLocalSlot {
        @Specialization
        public static Object perform(
                ProtosFrameLexicalLayout frameBackedLayout,
                int ordinal,
                ProtosActivation activation,
                String name,
                Object value) {
            return ProtosBytecodeRootNode.createIndexedCurrentLocalSlot(
                    frameBackedLayout, activation, ordinal, name, value);
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
            return ProtosBytecodeRootNode.CreateLocalSlot.perform(activation, target, name, value);
        }
    }

    @Operation
    public static final class MultipleCreateLocalSlots {
        @Specialization
        public static Object perform(ProtosActivation activation, String[] names, Object source) {
            return ProtosBytecodeRootNode.MultipleCreateLocalSlots.perform(activation, names, source);
        }
    }

    @Operation
    public static final class AssignLocalSlot {
        @Specialization
        public static Object perform(
                ProtosActivation activation,
                ProtosObjectValue target,
                String name,
                Object value) {
            return ProtosBytecodeRootNode.AssignLocalSlot.perform(activation, target, name, value);
        }
    }

    /**
     * Consumes an evaluated expression whose value is not the Sequence
     * result. The child still executes, including errors and effects.
     */
    @Operation
    public static final class DiscardValue {
        @Specialization
        public static void perform(Object ignored) {
        }
    }

    /**
     * PERF037-B root-level {@link ReadMember}: the lowerer emits it whenever
     * the current activation is the root's own (frame argument 0). The PIC
     * tiers are those of {@link ReadMember}, but neither selection nor a
     * successful read observes the activation: selection takes the prelude
     * the invocation's activation has or would have, and a valid hit loads
     * the selected binding's stable location. Only an absent member or an
     * unsupported representation materializes the exact activation, from
     * which its Error is raised.
     */
    @Operation
    @ConstantOperand(type = String.class, name = "name")
    public static final class ReadMemberAtRoot {
        @Specialization(
                guards = {
                    "cachedLookup != null",
                    "matchesSharedInheritedLookup(receiver, name, cachedLookup)",
                    "cachedLookup.nonClosureContinuity() != null"
                },
                assumptions = {
                    "cachedLookup.stability()",
                    "cachedLookup.nonClosureContinuity()"
                },
                limit = "3")
        public static Object guardedSharedInheritedPlain(
                String name,
                Object receiver,
                @Bind VirtualFrame frame,
                @Cached("createSharedInheritedLookup(receiver, name, frame.getArguments())")
                        ProtosValueLookup.SharedInheritedSlotSelection cachedLookup) {
            return ProtosValueLookup.guardedPlainSlotValue(
                    cachedLookup.parentSelection());
        }

        /**
         * PERF037: no method-binding branch in the common data-member
         * path. Truffle invalidates this specialization before a selected
         * slot can transition from data to Closure.
         */
        @Specialization(
                guards = {
                    "cachedLookup != null",
                    "matchesSharedInheritedLookup(receiver, name, cachedLookup)"
                },
                assumptions = "cachedLookup.stability()",
                limit = "3")
        public static Object guardedSharedInherited(
                String name,
                Object receiver,
                @Bind VirtualFrame frame,
                @Cached("createSharedInheritedLookup(receiver, name, frame.getArguments())")
                        ProtosValueLookup.SharedInheritedSlotSelection cachedLookup,
                @Bind Node node,
                @Cached InlinedBranchProfile closureExtraction) {
            return ProtosValueLookup.materializeGuardedMemberRead(
                    receiver, cachedLookup, node, closureExtraction);
        }

        /**
         * PERF037: no method-binding branch in the common data-member
         * path. Truffle invalidates this specialization before a selected
         * slot can transition from data to Closure.
         */
        @Specialization(
                guards = {
                    "receiver == cachedReceiver",
                    "cachedLookup != null",
                    "cachedLookup.nonClosureContinuity() != null"
                },
                assumptions = {
                    "cachedLookup.stability()",
                    "cachedLookup.nonClosureContinuity()"
                },
                limit = "3")
        public static Object guardedExactReceiverPlain(
                String name,
                Object receiver,
                @Bind VirtualFrame frame,
                @Cached("receiver") Object cachedReceiver,
                @Cached("createGuardedLookup(receiver, name, frame.getArguments())")
                        ProtosValueLookup.GuardedSlotSelection cachedLookup) {
            return ProtosValueLookup.guardedPlainSlotValue(cachedLookup);
        }

        @Specialization(
                guards = {
                    "receiver == cachedReceiver",
                    "cachedLookup != null"
                },
                assumptions = "cachedLookup.stability()",
                limit = "3")
        public static Object guardedExactReceiver(
                String name,
                Object receiver,
                @Bind VirtualFrame frame,
                @Cached("receiver") Object cachedReceiver,
                @Cached("createGuardedLookup(receiver, name, frame.getArguments())")
                        ProtosValueLookup.GuardedSlotSelection cachedLookup,
                @Bind Node node,
                @Cached InlinedBranchProfile closureExtraction) {
            return ProtosValueLookup.materializeGuardedMemberRead(
                    receiver, cachedLookup, node, closureExtraction);
        }

        @Specialization(replaces = {"guardedSharedInheritedPlain", "guardedSharedInherited", "guardedExactReceiverPlain", "guardedExactReceiver"})
        public static Object perform(
                String name,
                Object receiver,
                @Bind VirtualFrame frame) {
            Object[] arguments = frame.getArguments();
            Object value =
                    ProtosBytecodeRootNode.ReadMember.readMemberOrNull(
                            receiver, name, ProtosFrameArguments.preludeOrNull(arguments));
            if (value != null) {
                return value;
            }
            return ProtosBytecodeRootNode.ReadMember.perform(
                    ProtosFrameArguments.activation(arguments), receiver, name);
        }

        static ProtosValueLookup.SharedInheritedSlotSelection createSharedInheritedLookup(
                Object receiver, String name, Object[] arguments) {
            return ProtosBytecodeRootNode.ReadMember.createSharedInheritedLookupForPrelude(
                    receiver, name, ProtosFrameArguments.preludeOrNull(arguments));
        }

        static boolean matchesSharedInheritedLookup(
                Object receiver,
                String name,
                ProtosValueLookup.SharedInheritedSlotSelection cachedLookup) {
            return ProtosBytecodeRootNode.ReadMember.matchesSharedInheritedLookup(
                    receiver, name, cachedLookup);
        }

        static ProtosValueLookup.GuardedSlotSelection createGuardedLookup(
                Object receiver, String name, Object[] arguments) {
            return ProtosBytecodeRootNode.ReadMember.createGuardedLookupForPrelude(
                    receiver, name, ProtosFrameArguments.preludeOrNull(arguments));
        }
    }

    @Operation
    @ConstantOperand(type = String.class, name = "name")
    public static final class ReadMember {
        @Specialization(
                guards = {
                    "cachedLookup != null",
                    "matchesSharedInheritedLookup(receiver, name, cachedLookup)",
                    "cachedLookup.nonClosureContinuity() != null"
                },
                assumptions = {
                    "cachedLookup.stability()",
                    "cachedLookup.nonClosureContinuity()"
                },
                limit = "3")
        public static Object guardedSharedInheritedPlain(
                String name,
                ProtosActivation activation,
                Object receiver,
                @Cached("createSharedInheritedLookup(receiver, name, activation)")
                        ProtosValueLookup.SharedInheritedSlotSelection cachedLookup) {
            return ProtosValueLookup.guardedPlainSlotValue(
                    cachedLookup.parentSelection());
        }

        @Specialization(
                guards = {
                    "cachedLookup != null",
                    "matchesSharedInheritedLookup(receiver, name, cachedLookup)"
                },
                assumptions = "cachedLookup.stability()",
                limit = "3")
        public static Object guardedSharedInherited(
                String name,
                ProtosActivation activation,
                Object receiver,
                @Cached("createSharedInheritedLookup(receiver, name, activation)")
                        ProtosValueLookup.SharedInheritedSlotSelection cachedLookup,
                @Bind Node node,
                @Cached InlinedBranchProfile closureExtraction) {
            return ProtosValueLookup.materializeGuardedMemberRead(
                    receiver, cachedLookup, node, closureExtraction);
        }

        @Specialization(
                guards = {
                    "receiver == cachedReceiver",
                    "cachedLookup != null",
                    "cachedLookup.nonClosureContinuity() != null"
                },
                assumptions = {
                    "cachedLookup.stability()",
                    "cachedLookup.nonClosureContinuity()"
                },
                limit = "3")
        public static Object guardedExactReceiverPlain(
                String name,
                ProtosActivation activation,
                Object receiver,
                @Cached("receiver") Object cachedReceiver,
                @Cached("createGuardedLookup(receiver, name, activation)")
                        ProtosValueLookup.GuardedSlotSelection cachedLookup) {
            return ProtosValueLookup.guardedPlainSlotValue(cachedLookup);
        }

        @Specialization(
                guards = {
                    "receiver == cachedReceiver",
                    "cachedLookup != null"
                },
                assumptions = "cachedLookup.stability()",
                limit = "3")
        public static Object guardedExactReceiver(
                String name,
                ProtosActivation activation,
                Object receiver,
                @Cached("receiver") Object cachedReceiver,
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
                String name,
                ProtosActivation activation,
                Object receiver) {
            return ProtosBytecodeRootNode.ReadMember.perform(
                    activation,
                    receiver,
                    name);
        }

        static ProtosValueLookup.SharedInheritedSlotSelection createSharedInheritedLookup(
                Object receiver,
                String name,
                ProtosActivation activation) {
            return ProtosBytecodeRootNode.ReadMember.createSharedInheritedLookup(
                    receiver,
                    name,
                    activation);
        }

        static boolean matchesSharedInheritedLookup(
                Object receiver,
                String name,
                ProtosValueLookup.SharedInheritedSlotSelection cachedLookup) {
            return ProtosBytecodeRootNode.ReadMember.matchesSharedInheritedLookup(
                    receiver,
                    name,
                    cachedLookup);
        }

        static ProtosValueLookup.GuardedSlotSelection createGuardedLookup(
                Object receiver,
                String name,
                ProtosActivation activation) {
            return ProtosBytecodeRootNode.ReadMember.createGuardedLookup(
                    receiver,
                    name,
                    activation);
        }
    }

    @Operation
    public static final class Identity {
        @Specialization
        public static Object perform(Object left, Object right) {
            return ProtosBytecodeRootNode.Identity.perform(left, right);
        }
    }

    @Operation
    public static final class NotIdentity {
        @Specialization
        public static Object perform(Object left, Object right) {
            return ProtosBytecodeRootNode.NotIdentity.perform(left, right);
        }
    }

    @Operation
    public static final class ComplementEqualityResult {
        @Specialization
        public static Object perform(ProtosActivation activation, Object equalityResult) {
            return ProtosBytecodeRootNode.ComplementEqualityResult.perform(activation, equalityResult);
        }
    }

    @Operation
    public static final class LoadIntrinsic {
        @Specialization
        public static Object perform(ProtosActivation activation, CanonicalIntrinsic.Kind kind) {
            return ProtosBytecodeRootNode.LoadIntrinsic.perform(activation, kind);
        }
    }

    @Operation
    public static final class RaiseNonLocalReturn {
        @Specialization
        public static Object perform(ProtosActivation activation, Object value) {
            return ProtosBytecodeRootNode.RaiseNonLocalReturn.perform(activation, value);
        }
    }

    // ---- Number literals ------------------------------------------------------------

    /**
     * I091 / PLAT056 Candidate C: an Integer literal outside the signed-64 range. Its value is an
     * ordinary guest object owned by the executing Prelude, so it is minted on evaluation with
     * that Prelude's BigInteger prototype rather than embedded as a constant shared by every
     * execution domain that runs this code. Signed-64 literals remain primitive constants.
     *
     * <p>{@code value} is the literal's lowering-time exact descriptor, never a guest value. Each
     * evaluation mints a fresh Integer, so no domain shares the object of another. A caller
     * without a Prelude cannot own the value; that is an internal failure, never a guest null.
     */
    @Operation
    public static final class LargeIntegerLiteral {
        @Specialization
        @TruffleBoundary
        public static Object perform(Object caller, java.math.BigInteger value) {
            ProtosPrelude prelude = PrepareSendOne.callerPrelude(caller);
            if (prelude == null) {
                throw new IllegalStateException(
                        "a large Integer literal requires the executing Core prelude");
            }
            return ProtosNumericValueSupport.integerFromHost(value, prelude);
        }
    }

    // ---- Closure literals and inline Object construction ---------------------------

    @Operation
    public static final class MaterializeClosure {
        @Specialization
        public static ProtosClosureValue perform(
                ProtosActivation activation,
                CanonicalClosure definition,
                ProtosClosureExecutionPlanCell executionPlanCell) {
            return ProtosBytecodeRootNode.MaterializeClosure.perform(
                    activation, definition, executionPlanCell);
        }
    }

    @Operation
    public static final class MaterializeCurrentClosure {
        @Specialization
        public static ProtosClosureValue rich(
                ProtosActivation activation,
                CanonicalClosure definition,
                ProtosClosureExecutionPlanCell executionPlanCell) {
            return ProtosBytecodeRootNode.MaterializeClosure.perform(
                    activation, definition, executionPlanCell);
        }

        @Specialization
        public static ProtosClosureValue compact(
                Object[] caller,
                CanonicalClosure definition,
                ProtosClosureExecutionPlanCell executionPlanCell) {
            return ProtosFrameArguments.materializeCompactClosure(
                    caller, definition, executionPlanCell);
        }
    }

    @Operation
    public static final class NewConstructedObject {
        @Specialization
        public static ProtosObjectValue perform(Object parent) {
            return ProtosBytecodeRootNode.NewConstructedObject.perform(parent);
        }
    }

    @Operation
    public static final class NewObjectConstructionActivation {
        @Specialization
        public static ProtosActivation perform(ProtosActivation enclosing, ProtosObjectValue object) {
            return ProtosBytecodeRootNode.NewObjectConstructionActivation.perform(enclosing, object);
        }
    }

    @Operation
    public static final class ComposeLocalSlots {
        @Specialization
        public static ProtosObjectValue perform(
                ProtosActivation activation,
                Object sourceValue,
                ProtosBytecodeRootNode.ComposeReservedNames reservedNames) {
            return ProtosBytecodeRootNode.ComposeLocalSlots.perform(
                    activation, sourceValue, reservedNames);
        }
    }

    // ---- Map construction ----------------------------------------------------------

    @Operation
    public static final class PrepareMapConstructionFactoryCall {
        @Specialization
        public static PreparedClosureCall perform(ProtosActivation activation, Object factory) {
            return ProtosBytecodeRootNode.PrepareMapConstructionFactoryCall.perform(activation, factory);
        }
    }

    @Operation
    public static final class FinishMapConstructionFactoryCall {
        @Specialization
        public static ProtosMapValue perform(ProtosActivation activation, Object result) {
            return ProtosBytecodeRootNode.FinishMapConstructionFactoryCall.perform(activation, result);
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
            return ProtosBytecodeRootNode.PrepareMapInitialDefinition.perform(
                    activation, map, key, value);
        }
    }

    @Operation
    public static final class EnterMapInitialDefinitionComparison {
        @Specialization
        public static void perform(PreparedMapInitialDefinition prepared) {
            ProtosBytecodeRootNode.EnterMapInitialDefinitionComparison.perform(prepared);
        }
    }

    @Operation
    public static final class LeaveMapInitialDefinitionComparison {
        @Specialization
        public static void perform(PreparedMapInitialDefinition prepared) {
            ProtosBytecodeRootNode.LeaveMapInitialDefinitionComparison.perform(prepared);
        }
    }

    @Operation
    public static final class PrepareMapInitialDefinitionHashCall {
        @Specialization
        public static PreparedClosureCall perform(PreparedMapInitialDefinition prepared) {
            return ProtosBytecodeRootNode.PrepareMapInitialDefinitionHashCall.perform(prepared);
        }
    }

    @Operation
    public static final class AcceptMapInitialDefinitionHashResult {
        @Specialization
        public static void perform(PreparedMapInitialDefinition prepared, Object result) {
            ProtosBytecodeRootNode.AcceptMapInitialDefinitionHashResult.perform(prepared, result);
        }
    }

    @Operation
    public static final class MapInitialDefinitionNeedsEquality {
        @Specialization
        public static boolean perform(PreparedMapInitialDefinition prepared) {
            return ProtosBytecodeRootNode.MapInitialDefinitionNeedsEquality.perform(prepared);
        }
    }

    @Operation
    public static final class PrepareMapInitialDefinitionEqualityCall {
        @Specialization
        public static PreparedClosureCall perform(PreparedMapInitialDefinition prepared) {
            return ProtosBytecodeRootNode.PrepareMapInitialDefinitionEqualityCall.perform(prepared);
        }
    }

    @Operation
    public static final class AcceptMapInitialDefinitionEqualityResult {
        @Specialization
        public static void perform(PreparedMapInitialDefinition prepared, Object result) {
            ProtosBytecodeRootNode.AcceptMapInitialDefinitionEqualityResult.perform(prepared, result);
        }
    }

    @Operation
    public static final class FinishMapInitialDefinition {
        @Specialization
        public static void perform(PreparedMapInitialDefinition prepared) {
            ProtosBytecodeRootNode.FinishMapInitialDefinition.perform(prepared);
        }
    }

    // ---- Supplied argument vectors -------------------------------------------------

    @Operation
    public static final class CreateSuppliedArgumentVector {
        @Specialization
        public static PreparedArgumentVector perform() {
            return ProtosBytecodeRootNode.CreateSuppliedArgumentVector.perform();
        }
    }

    @Operation
    public static final class AppendSuppliedArgument {
        @Specialization
        public static void perform(PreparedArgumentVector vector, Object value) {
            ProtosBytecodeRootNode.AppendSuppliedArgument.perform(vector, value);
        }
    }

    @Operation
    public static final class AppendSpreadSuppliedArgument {
        @Specialization
        public static void perform(
                PreparedArgumentVector vector,
                Object value,
                Object caller) {
            if (!vector.appendSpreadArrayOrFalse(value)) {
                ProtosActivation exactCaller =
                        PrepareSendArguments.exactCaller(caller);
                throw new com.guillermomolina.protos.runtime.ProtosSignalException(
                        com.guillermomolina.protos.runtime.ProtosCoreErrors
                                .newError(exactCaller));
            }
        }
    }


    @Operation
    @ConstantOperand(type = LocalRangeAccessor.class, name = "frameBackedLocals")
    @ConstantOperand(type = ProtosFrameLexicalLayout.class, name = "frameBackedLayout")
    public static final class AppendInlineSpreadSuppliedArgument {
        @Specialization
        public static void perform(
                LocalRangeAccessor frameBackedLocals,
                ProtosFrameLexicalLayout frameBackedLayout,
                PreparedArgumentVector vector,
                Object value,
                PreparedInlineLiteralCall child,
                @Bind BytecodeNode bytecodeNode,
                @Bind VirtualFrame frame) {
            if (!vector.appendSpreadArrayOrFalse(value)) {
                ProtosActivation exactCaller =
                        ProtosInlineCallbackFrameBindings.durableActivation(
                                child, frameBackedLocals, frameBackedLayout,
                                bytecodeNode, frame);
                throw new com.guillermomolina.protos.runtime.ProtosSignalException(
                        com.guillermomolina.protos.runtime.ProtosCoreErrors
                                .newError(exactCaller));
            }
        }
    }

    // ---- Prepared call/send selection ----------------------------------------------

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
            return ProtosBytecodeRootNode.PrepareClosureCall.guardedDirect(
                    receiver, caller, enteredContext, cachedReceiver, cachedContext, cachedGuarded);
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
            return ProtosBytecodeRootNode.PrepareClosureCall.fastDirect(
                    receiver, caller, closure, closureDefinition, enteredContext,
                    cachedClosureDefinition, cachedContext, cachedTarget);
        }

        @Specialization(replaces = {"guardedDirect", "fastDirect"})
        public static PreparedClosureCall perform(Object receiver, ProtosActivation caller) {
            return ProtosBytecodeRootNode.PrepareClosureCall.perform(receiver, caller);
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
            return ProtosBytecodeRootNode.PrepareSendArguments.fastOrdinarySendTarget(
                    closure, enteredContext);
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
            return ProtosBytecodeRootNode.PrepareClosureCallArguments.guardedDirect(
                    receiver, caller, supplied, enteredContext, cachedReceiver, cachedContext,
                    cachedGuarded);
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
            return ProtosBytecodeRootNode.PrepareClosureCallArguments.fastDirect(
                    receiver, caller, supplied, closure, closureDefinition, enteredContext,
                    cachedClosureDefinition, cachedContext, cachedTarget);
        }

        @Specialization(replaces = {"guardedDirect", "fastDirect"})
        public static PreparedClosureCall perform(
                Object receiver,
                ProtosActivation caller,
                @Variadic Object[] supplied) {
            return ProtosBytecodeRootNode.PrepareClosureCallArguments.perform(
                    receiver, caller, supplied);
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
            return ProtosBytecodeRootNode.PrepareSendArguments.fastOrdinarySendTarget(
                    closure, enteredContext);
        }
    }


    /* PERF038-H: dedicated single-argument Closure call. */
    @Operation
    public static final class PrepareClosureCallOne {
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
                Object supplied0,
                @Bind("currentEnteredContext($node)")
                        ProtosLanguageContext enteredContext,
                @Cached("receiver") Object cachedReceiver,
                @Cached("enteredContext") ProtosLanguageContext cachedContext,
                @Cached("createGuardedDirectClosureCall(receiver, caller, enteredContext)")
                        GuardedDirectClosureCallTarget cachedGuarded) {

            return ProtosBytecodeRootNode.finishDirectClosureCallOne(
                    cachedGuarded.closure(),
                    cachedGuarded.target(),
                    supplied0,
                    caller);
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
                Object supplied0,
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

            return ProtosBytecodeRootNode.finishDirectClosureCallOne(
                    closure,
                    cachedTarget,
                    supplied0,
                    caller);
        }

        @Specialization(replaces = {"guardedDirect", "fastDirect"})
        public static PreparedClosureCall perform(
                Object receiver,
                ProtosActivation caller,
                Object supplied0) {
            return ProtosBytecodeRootNode.prepareClosureCallOneFallback(
                    receiver, caller, supplied0);

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
            return ProtosBytecodeRootNode.PrepareSendArguments.fastOrdinarySendTarget(
                    closure, enteredContext);
        }
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
            return ProtosBytecodeRootNode.PrepareDefaultClosureCallArguments.guardedDirect(
                    receiver, caller, supplied, enteredContext, cachedReceiver, cachedContext,
                    cachedGuarded);
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
            return ProtosBytecodeRootNode.PrepareDefaultClosureCallArguments.fastDirect(
                    receiver, caller, supplied, closure, closureDefinition, enteredContext,
                    cachedClosureDefinition, cachedContext, cachedTarget);
        }

        @Specialization(replaces = {"guardedDirect", "fastDirect"})
        public static PreparedClosureCall perform(
                Object receiver,
                ProtosActivation caller,
                @Variadic Object[] supplied) {
            return ProtosBytecodeRootNode.PrepareDefaultClosureCallArguments.perform(
                    receiver, caller, supplied);
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
            return ProtosBytecodeRootNode.PrepareSendArguments.fastOrdinarySendTarget(
                    closure, enteredContext);
        }
    }

    /**
     * PERF025 slice 3 inline-callback {@link PrepareSendArguments}: the same
     * specialization tiers, selected through the invocation's prelude. A
     * canonical guarded Integer hit observes no activation; an ordinary
     * source-backed hit prepares its compact child with a provenance-equivalent
     * caller ({@link ProtosInlineCallbackFrameBindings#invocationCaller}).
     * Every other path (non-canonical Integer selection, structured send,
     * generic send, any Error) runs the unchanged activation path.
     */
    @Operation
    @ConstantOperand(
            type = LocalRangeAccessor.class,
            name = "frameBackedLocals")
    @ConstantOperand(
            type = ProtosFrameLexicalLayout.class,
            name = "frameBackedLayout")
    public static final class PrepareInlineSendArguments {
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
                LocalRangeAccessor frameBackedLocals,
                ProtosFrameLexicalLayout frameBackedLayout,
                Object receiver,
                String selector,
                PreparedInlineLiteralCall child,
                @Variadic Object[] supplied,
                @Bind BytecodeNode bytecodeNode,
                @Bind VirtualFrame frame,
                @Bind("currentEnteredContext($node)")
                        ProtosLanguageContext enteredContext,
                @Bind("child.prelude()") ProtosPrelude prelude,
                @Cached("selector") String cachedSelector,
                @Cached("enteredContext") ProtosLanguageContext cachedContext,
                @Cached("prelude") ProtosPrelude cachedPrelude,
                @Cached("createGuardedIntegerSend(receiver, selector, prelude, enteredContext)")
                        GuardedIntegerSend cachedInteger) {
            PreparedClosureCall canonical =
                    ProtosBytecodeRootNode.PrepareSendArguments.canonicalIntegerResultOrNull(
                            cachedInteger, receiver, supplied, prelude);
            if (canonical != null) {
                return canonical;
            }
            return ProtosBytecodeRootNode.PrepareSendArguments.guardedIntegerSend(
                    receiver, selector,
                    ProtosInlineCallbackFrameBindings.invocationCaller(
                            child, frameBackedLocals, frameBackedLayout, bytecodeNode, frame),
                    supplied, enteredContext, prelude,
                    cachedSelector, cachedContext, cachedPrelude, cachedInteger);
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
                LocalRangeAccessor frameBackedLocals,
                ProtosFrameLexicalLayout frameBackedLayout,
                Object receiver,
                String selector,
                PreparedInlineLiteralCall child,
                @Variadic Object[] supplied,
                @Bind BytecodeNode bytecodeNode,
                @Bind VirtualFrame frame,
                @Bind("currentEnteredContext($node)")
                        ProtosLanguageContext enteredContext,
                @Bind("child.prelude()") ProtosPrelude prelude,
                @Cached("receiver") Object cachedReceiver,
                @Cached("selector") String cachedSelector,
                @Cached("enteredContext") ProtosLanguageContext cachedContext,
                @Cached("createGuardedSendForPrelude(receiver, selector, prelude, enteredContext)")
                        GuardedSendTarget cachedSend) {
            return ProtosBytecodeRootNode.PrepareSendArguments.guardedOrdinarySend(
                    receiver, selector,
                    ProtosInlineCallbackFrameBindings.invocationCaller(
                            child, frameBackedLocals, frameBackedLayout, bytecodeNode, frame),
                    supplied, enteredContext,
                    cachedReceiver, cachedSelector, cachedContext, cachedSend);
        }

        /*
         * Unlike PrepareSendArguments.fastOrdinarySend, a failing selection
         * binds null and misses here: its exact Error is raised by the
         * generic path from the materialized activation.
         */
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
                LocalRangeAccessor frameBackedLocals,
                ProtosFrameLexicalLayout frameBackedLayout,
                Object receiver,
                String selector,
                PreparedInlineLiteralCall child,
                @Variadic Object[] supplied,
                @Bind BytecodeNode bytecodeNode,
                @Bind VirtualFrame frame,
                @Bind("ordinarySendSelectionOrNull(receiver, selector, child)")
                        ProtosSlotLookupResult selected,
                @Bind("ordinarySendClosureOrNull(selected)")
                        ProtosClosureValue closure,
                @Bind("ordinarySendClosureDefinitionOrNull(closure)")
                        CanonicalClosure closureDefinition,
                @Bind("currentEnteredContext($node)")
                        ProtosLanguageContext enteredContext,
                @Cached("selector") String cachedSelector,
                @Cached("closureDefinition") CanonicalClosure cachedClosureDefinition,
                @Cached("enteredContext") ProtosLanguageContext cachedContext,
                @Cached("fastOrdinarySendTarget(closure, enteredContext)")
                        RootCallTarget cachedTarget) {
            return ProtosBytecodeRootNode.PrepareSendArguments.fastOrdinarySend(
                    receiver, selector,
                    ProtosInlineCallbackFrameBindings.invocationCaller(
                            child, frameBackedLocals, frameBackedLayout, bytecodeNode, frame),
                    supplied, selected, closure, selected.home(),
                    closureDefinition, enteredContext, cachedSelector, cachedClosureDefinition,
                    cachedContext, cachedTarget);
        }

        /*
         * Structured sends keep the materialized activation as their caller;
         * only their D013 classification, which reads the caller's prelude,
         * uses a provenance-equivalent caller.
         */
        @Specialization(
                guards = {
                    "receiver == cachedReceiver",
                    "selector.equals(cachedSelector)",
                    "enteredContext != null",
                    "enteredContext == cachedContext",
                    "lookupCaller != null",
                    "cachedStructured != null"
                },
                assumptions = "cachedStructured.stability()",
                limit = "3")
        public static PreparedClosureCall guardedStructuredSend(
                LocalRangeAccessor frameBackedLocals,
                ProtosFrameLexicalLayout frameBackedLayout,
                Object receiver,
                String selector,
                PreparedInlineLiteralCall child,
                @Variadic Object[] supplied,
                @Bind BytecodeNode bytecodeNode,
                @Bind VirtualFrame frame,
                @Bind("currentEnteredContext($node)")
                        ProtosLanguageContext enteredContext,
                @Bind("child.provenanceCallerOrNull()") ProtosActivation lookupCaller,
                @Cached("receiver") Object cachedReceiver,
                @Cached("selector") String cachedSelector,
                @Cached("enteredContext") ProtosLanguageContext cachedContext,
                @Cached("createGuardedStructuredSend(receiver, selector, lookupCaller)")
                        GuardedStructuredSend cachedStructured) {
            return ProtosBytecodeRootNode.PrepareSendArguments.guardedStructuredSend(
                    receiver, selector,
                    ProtosInlineCallbackFrameBindings.durableActivation(
                            child, frameBackedLocals, frameBackedLayout, bytecodeNode, frame),
                    supplied, enteredContext,
                    cachedReceiver, cachedSelector, cachedContext, cachedStructured);
        }

        @Specialization(
                replaces = {
                    "guardedIntegerSend",
                    "guardedOrdinarySend",
                    "fastOrdinarySend",
                    "guardedStructuredSend"
                })
        public static PreparedClosureCall perform(
                LocalRangeAccessor frameBackedLocals,
                ProtosFrameLexicalLayout frameBackedLayout,
                Object receiver,
                String selector,
                PreparedInlineLiteralCall child,
                @Variadic Object[] supplied,
                @Bind BytecodeNode bytecodeNode,
                @Bind VirtualFrame frame) {
            return ProtosBytecodeRootNode.PrepareSendArguments.perform(
                    receiver, selector,
                    ProtosInlineCallbackFrameBindings.durableActivation(
                            child, frameBackedLocals, frameBackedLayout, bytecodeNode, frame),
                    supplied);
        }

        static GuardedIntegerSend createGuardedIntegerSend(
                Object receiver,
                String selector,
                ProtosPrelude prelude,
                ProtosLanguageContext enteredContext) {
            return ProtosBytecodeRootNode.PrepareSendArguments.createGuardedIntegerSend(
                    receiver, selector, prelude, enteredContext);
        }

        static boolean isIntegerReceiver(Object receiver) {
            return ProtosBytecodeRootNode.PrepareSendArguments.isIntegerReceiver(receiver);
        }

        static GuardedSendTarget createGuardedSendForPrelude(
                Object receiver,
                String selector,
                ProtosPrelude prelude,
                ProtosLanguageContext enteredContext) {
            return ProtosBytecodeRootNode.PrepareSendArguments.createGuardedSendForPrelude(
                    receiver, selector, prelude, enteredContext);
        }

        static GuardedStructuredSend createGuardedStructuredSend(
                Object receiver, String selector, ProtosActivation caller) {
            return ProtosBytecodeRootNode.PrepareSendArguments.createGuardedStructuredSend(
                    receiver, selector, caller);
        }

        static ProtosSlotLookupResult ordinarySendSelectionOrNull(
                Object receiver, String selector, PreparedInlineLiteralCall child) {
            try {
                return ProtosValueLookup.lookup(receiver, selector, child.prelude())
                        .orElse(null);
            } catch (UnsupportedOperationException unsupportedRepresentation) {
                return null;
            }
        }

        static boolean isForeignReceiver(Object receiver) {
            return ProtosBytecodeRootNode.PrepareSendArguments.isForeignReceiver(receiver);
        }

        static ProtosClosureValue ordinarySendClosureOrNull(ProtosSlotLookupResult selected) {
            return selected == null
                    ? null
                    : ProtosBytecodeRootNode.PrepareSendArguments.ordinarySendClosureOrNull(
                            selected);
        }

        static CanonicalClosure ordinarySendClosureDefinitionOrNull(ProtosClosureValue closure) {
            return ProtosBytecodeRootNode.PrepareSendArguments.ordinarySendClosureDefinitionOrNull(
                    closure);
        }

        @NonIdempotent
        static ProtosLanguageContext currentEnteredContext(Node node) {
            return ProtosLanguageContext.current(node);
        }

        static RootCallTarget fastOrdinarySendTarget(
                ProtosClosureValue closure, ProtosLanguageContext enteredContext) {
            return ProtosBytecodeRootNode.PrepareSendArguments.fastOrdinarySendTarget(
                    closure, enteredContext);
        }
    }

    /**
     * PERF025 slice 3 inline-callback {@link PrepareClosureCall} and {@link
     * PrepareClosureCallArguments}: the canonical direct source-Closure tiers
     * select through the invocation's prelude and prepare the compact child
     * with a provenance-equivalent caller; every other selection runs the
     * unchanged activation path.
     */

    /* PERF038-H: fixed-arity inline sends. */
    @Operation
    @ConstantOperand(
            type = LocalRangeAccessor.class,
            name = "frameBackedLocals")
    @ConstantOperand(
            type = ProtosFrameLexicalLayout.class,
            name = "frameBackedLayout")
    public static final class PrepareInlineSendZero {
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
                LocalRangeAccessor frameBackedLocals,
                ProtosFrameLexicalLayout frameBackedLayout,
                Object receiver,
                String selector,
                PreparedInlineLiteralCall child,

                @Bind BytecodeNode bytecodeNode,
                @Bind VirtualFrame frame,
                @Bind("currentEnteredContext($node)")
                        ProtosLanguageContext enteredContext,
                @Bind("child.prelude()") ProtosPrelude prelude,
                @Cached("selector") String cachedSelector,
                @Cached("enteredContext") ProtosLanguageContext cachedContext,
                @Cached("prelude") ProtosPrelude cachedPrelude,
                @Cached("createGuardedIntegerSend(receiver, selector, prelude, enteredContext)")
                        GuardedIntegerSend cachedInteger) {
            return ProtosBytecodeRootNode.PrepareSendArguments
                    .guardedIntegerSendZero(
                            receiver,
                            ProtosInlineCallbackFrameBindings.invocationCaller(
                    child, frameBackedLocals, frameBackedLayout,
                    bytecodeNode, frame),
                            cachedInteger);
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
                LocalRangeAccessor frameBackedLocals,
                ProtosFrameLexicalLayout frameBackedLayout,
                Object receiver,
                String selector,
                PreparedInlineLiteralCall child,

                @Bind BytecodeNode bytecodeNode,
                @Bind VirtualFrame frame,
                @Bind("currentEnteredContext($node)")
                        ProtosLanguageContext enteredContext,
                @Bind("child.prelude()") ProtosPrelude prelude,
                @Cached("receiver") Object cachedReceiver,
                @Cached("selector") String cachedSelector,
                @Cached("enteredContext") ProtosLanguageContext cachedContext,
                @Cached("createGuardedSendForPrelude(receiver, selector, prelude, enteredContext)")
                        GuardedSendTarget cachedSend) {
            return ProtosBytecodeRootNode.PrepareSendArguments
                    .guardedOrdinarySendZero(
                            receiver,
                            ProtosInlineCallbackFrameBindings.invocationCaller(
                    child, frameBackedLocals, frameBackedLayout,
                    bytecodeNode, frame),
                            cachedSend);
        }

        /*
         * Unlike PrepareSendArguments.fastOrdinarySend, a failing selection
         * binds null and misses here: its exact Error is raised by the
         * generic path from the materialized activation.
         */
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
                LocalRangeAccessor frameBackedLocals,
                ProtosFrameLexicalLayout frameBackedLayout,
                Object receiver,
                String selector,
                PreparedInlineLiteralCall child,

                @Bind BytecodeNode bytecodeNode,
                @Bind VirtualFrame frame,
                @Bind("ordinarySendSelectionOrNull(receiver, selector, child)")
                        ProtosSlotLookupResult selected,
                @Bind("ordinarySendClosureOrNull(selected)")
                        ProtosClosureValue closure,
                @Bind("ordinarySendClosureDefinitionOrNull(closure)")
                        CanonicalClosure closureDefinition,
                @Bind("currentEnteredContext($node)")
                        ProtosLanguageContext enteredContext,
                @Cached("selector") String cachedSelector,
                @Cached("closureDefinition") CanonicalClosure cachedClosureDefinition,
                @Cached("enteredContext") ProtosLanguageContext cachedContext,
                @Cached("fastOrdinarySendTarget(closure, enteredContext)")
                        RootCallTarget cachedTarget) {
            return ProtosBytecodeRootNode.PrepareSendArguments
                    .fastOrdinarySendZero(
                            closure,
                            receiver,
                            selected.home(),
                            ProtosInlineCallbackFrameBindings.invocationCaller(
                    child, frameBackedLocals, frameBackedLayout,
                    bytecodeNode, frame),
                            cachedTarget);
        }

        /*
         * Structured sends keep the materialized activation as their caller;
         * only their D013 classification, which reads the caller's prelude,
         * uses a provenance-equivalent caller.
         */
        @Specialization(
                guards = {
                    "receiver == cachedReceiver",
                    "selector.equals(cachedSelector)",
                    "enteredContext != null",
                    "enteredContext == cachedContext",
                    "lookupCaller != null",
                    "cachedStructured != null"
                },
                assumptions = "cachedStructured.stability()",
                limit = "3")
        public static PreparedClosureCall guardedStructuredSend(
                LocalRangeAccessor frameBackedLocals,
                ProtosFrameLexicalLayout frameBackedLayout,
                Object receiver,
                String selector,
                PreparedInlineLiteralCall child,

                @Bind BytecodeNode bytecodeNode,
                @Bind VirtualFrame frame,
                @Bind("currentEnteredContext($node)")
                        ProtosLanguageContext enteredContext,
                @Bind("child.provenanceCallerOrNull()") ProtosActivation lookupCaller,
                @Cached("receiver") Object cachedReceiver,
                @Cached("selector") String cachedSelector,
                @Cached("enteredContext") ProtosLanguageContext cachedContext,
                @Cached("createGuardedStructuredSend(receiver, selector, lookupCaller)")
                        GuardedStructuredSend cachedStructured) {
            return ProtosBytecodeRootNode.PrepareSendArguments
                    .guardedStructuredSendZero(
                            receiver,
                            ProtosInlineCallbackFrameBindings.durableActivation(
                    child, frameBackedLocals, frameBackedLayout,
                    bytecodeNode, frame),
                            cachedStructured);
        }

        @Specialization(
                replaces = {
                    "guardedIntegerSend",
                    "guardedOrdinarySend",
                    "fastOrdinarySend",
                    "guardedStructuredSend"
                })
        public static PreparedClosureCall perform(
                LocalRangeAccessor frameBackedLocals,
                ProtosFrameLexicalLayout frameBackedLayout,
                Object receiver,
                String selector,
                PreparedInlineLiteralCall child,

                @Bind BytecodeNode bytecodeNode,
                @Bind VirtualFrame frame) {
            return ProtosBytecodeRootNode.prepareSendZeroFallback(
                    receiver,
                    selector,
                    ProtosInlineCallbackFrameBindings.durableActivation(
                            child, frameBackedLocals, frameBackedLayout,
                            bytecodeNode, frame));

        }

        static GuardedIntegerSend createGuardedIntegerSend(
                Object receiver,
                String selector,
                ProtosPrelude prelude,
                ProtosLanguageContext enteredContext) {
            return ProtosBytecodeRootNode.PrepareSendArguments.createGuardedIntegerSend(
                    receiver, selector, prelude, enteredContext);
        }

        static boolean isIntegerReceiver(Object receiver) {
            return ProtosBytecodeRootNode.PrepareSendArguments.isIntegerReceiver(receiver);
        }

        static GuardedSendTarget createGuardedSendForPrelude(
                Object receiver,
                String selector,
                ProtosPrelude prelude,
                ProtosLanguageContext enteredContext) {
            return ProtosBytecodeRootNode.PrepareSendArguments.createGuardedSendForPrelude(
                    receiver, selector, prelude, enteredContext);
        }

        static GuardedStructuredSend createGuardedStructuredSend(
                Object receiver, String selector, ProtosActivation caller) {
            return ProtosBytecodeRootNode.PrepareSendArguments.createGuardedStructuredSend(
                    receiver, selector, caller);
        }

        static ProtosSlotLookupResult ordinarySendSelectionOrNull(
                Object receiver, String selector, PreparedInlineLiteralCall child) {
            try {
                return ProtosValueLookup.lookup(receiver, selector, child.prelude())
                        .orElse(null);
            } catch (UnsupportedOperationException unsupportedRepresentation) {
                return null;
            }
        }

        static boolean isForeignReceiver(Object receiver) {
            return ProtosBytecodeRootNode.PrepareSendArguments.isForeignReceiver(receiver);
        }

        static ProtosClosureValue ordinarySendClosureOrNull(ProtosSlotLookupResult selected) {
            return selected == null
                    ? null
                    : ProtosBytecodeRootNode.PrepareSendArguments.ordinarySendClosureOrNull(
                            selected);
        }

        static CanonicalClosure ordinarySendClosureDefinitionOrNull(ProtosClosureValue closure) {
            return ProtosBytecodeRootNode.PrepareSendArguments.ordinarySendClosureDefinitionOrNull(
                    closure);
        }

        @NonIdempotent
        static ProtosLanguageContext currentEnteredContext(Node node) {
            return ProtosLanguageContext.current(node);
        }

        static RootCallTarget fastOrdinarySendTarget(
                ProtosClosureValue closure, ProtosLanguageContext enteredContext) {
            return ProtosBytecodeRootNode.PrepareSendArguments.fastOrdinarySendTarget(
                    closure, enteredContext);
        }
    }

    /**
     * PERF025 slice 3 inline-callback {@link PrepareClosureCall} and {@link
     * PrepareClosureCallArguments}: the canonical direct source-Closure tiers
     * select through the invocation's prelude and prepare the compact child
     * with a provenance-equivalent caller; every other selection runs the
     * unchanged activation path.
     */

    @Operation
    @ConstantOperand(
            type = LocalRangeAccessor.class,
            name = "frameBackedLocals")
    @ConstantOperand(
            type = ProtosFrameLexicalLayout.class,
            name = "frameBackedLayout")
    public static final class PrepareInlineSendOne {
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
                LocalRangeAccessor frameBackedLocals,
                ProtosFrameLexicalLayout frameBackedLayout,
                Object receiver,
                String selector,
                PreparedInlineLiteralCall child,
                Object supplied0,
                @Bind BytecodeNode bytecodeNode,
                @Bind VirtualFrame frame,
                @Bind("currentEnteredContext($node)")
                        ProtosLanguageContext enteredContext,
                @Bind("child.prelude()") ProtosPrelude prelude,
                @Cached("selector") String cachedSelector,
                @Cached("enteredContext") ProtosLanguageContext cachedContext,
                @Cached("prelude") ProtosPrelude cachedPrelude,
                @Cached("createGuardedIntegerSend(receiver, selector, prelude, enteredContext)")
                        GuardedIntegerSend cachedInteger) {
            if (cachedInteger.operation() != null) {
                Object direct =
                        ProtosStandardIntegerProtocol.tryExecuteCanonicalOperationOne(
                                cachedInteger.operation(), receiver, supplied0, prelude);
                if (direct != null) {
                    return PreparedClosureCall.immediateResult(direct);
                }
            }

            return ProtosBytecodeRootNode.PrepareSendArguments
                    .guardedIntegerSendOne(
                            receiver,
                            ProtosInlineCallbackFrameBindings.invocationCaller(
                    child, frameBackedLocals, frameBackedLayout,
                    bytecodeNode, frame), supplied0,
                            cachedInteger);
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
                LocalRangeAccessor frameBackedLocals,
                ProtosFrameLexicalLayout frameBackedLayout,
                Object receiver,
                String selector,
                PreparedInlineLiteralCall child,
                Object supplied0,
                @Bind BytecodeNode bytecodeNode,
                @Bind VirtualFrame frame,
                @Bind("currentEnteredContext($node)")
                        ProtosLanguageContext enteredContext,
                @Bind("child.prelude()") ProtosPrelude prelude,
                @Cached("receiver") Object cachedReceiver,
                @Cached("selector") String cachedSelector,
                @Cached("enteredContext") ProtosLanguageContext cachedContext,
                @Cached("createGuardedSendForPrelude(receiver, selector, prelude, enteredContext)")
                        GuardedSendTarget cachedSend) {
            return ProtosBytecodeRootNode.PrepareSendArguments
                    .guardedOrdinarySendOne(
                            receiver,
                            ProtosInlineCallbackFrameBindings.invocationCaller(
                    child, frameBackedLocals, frameBackedLayout,
                    bytecodeNode, frame), supplied0,
                            cachedSend);
        }

        /*
         * Unlike PrepareSendArguments.fastOrdinarySend, a failing selection
         * binds null and misses here: its exact Error is raised by the
         * generic path from the materialized activation.
         */
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
                LocalRangeAccessor frameBackedLocals,
                ProtosFrameLexicalLayout frameBackedLayout,
                Object receiver,
                String selector,
                PreparedInlineLiteralCall child,
                Object supplied0,
                @Bind BytecodeNode bytecodeNode,
                @Bind VirtualFrame frame,
                @Bind("ordinarySendSelectionOrNull(receiver, selector, child)")
                        ProtosSlotLookupResult selected,
                @Bind("ordinarySendClosureOrNull(selected)")
                        ProtosClosureValue closure,
                @Bind("ordinarySendClosureDefinitionOrNull(closure)")
                        CanonicalClosure closureDefinition,
                @Bind("currentEnteredContext($node)")
                        ProtosLanguageContext enteredContext,
                @Cached("selector") String cachedSelector,
                @Cached("closureDefinition") CanonicalClosure cachedClosureDefinition,
                @Cached("enteredContext") ProtosLanguageContext cachedContext,
                @Cached("fastOrdinarySendTarget(closure, enteredContext)")
                        RootCallTarget cachedTarget) {
            return ProtosBytecodeRootNode.PrepareSendArguments
                    .fastOrdinarySendOne(
                            closure,
                            receiver,
                            selected.home(),
                            ProtosInlineCallbackFrameBindings.invocationCaller(
                    child, frameBackedLocals, frameBackedLayout,
                    bytecodeNode, frame),
                            cachedTarget, supplied0);
        }

        /*
         * Structured sends keep the materialized activation as their caller;
         * only their D013 classification, which reads the caller's prelude,
         * uses a provenance-equivalent caller.
         */
        @Specialization(
                guards = {
                    "receiver == cachedReceiver",
                    "selector.equals(cachedSelector)",
                    "enteredContext != null",
                    "enteredContext == cachedContext",
                    "lookupCaller != null",
                    "cachedStructured != null"
                },
                assumptions = "cachedStructured.stability()",
                limit = "3")
        public static PreparedClosureCall guardedStructuredSend(
                LocalRangeAccessor frameBackedLocals,
                ProtosFrameLexicalLayout frameBackedLayout,
                Object receiver,
                String selector,
                PreparedInlineLiteralCall child,
                Object supplied0,
                @Bind BytecodeNode bytecodeNode,
                @Bind VirtualFrame frame,
                @Bind("currentEnteredContext($node)")
                        ProtosLanguageContext enteredContext,
                @Bind("child.provenanceCallerOrNull()") ProtosActivation lookupCaller,
                @Cached("receiver") Object cachedReceiver,
                @Cached("selector") String cachedSelector,
                @Cached("enteredContext") ProtosLanguageContext cachedContext,
                @Cached("createGuardedStructuredSend(receiver, selector, lookupCaller)")
                        GuardedStructuredSend cachedStructured) {
            return ProtosBytecodeRootNode.PrepareSendArguments
                    .guardedStructuredSendOne(
                            receiver,
                            ProtosInlineCallbackFrameBindings.durableActivation(
                    child, frameBackedLocals, frameBackedLayout,
                    bytecodeNode, frame), supplied0,
                            cachedStructured);
        }

        @Specialization(
                replaces = {
                    "guardedIntegerSend",
                    "guardedOrdinarySend",
                    "fastOrdinarySend",
                    "guardedStructuredSend"
                })
        public static PreparedClosureCall perform(
                LocalRangeAccessor frameBackedLocals,
                ProtosFrameLexicalLayout frameBackedLayout,
                Object receiver,
                String selector,
                PreparedInlineLiteralCall child,
                Object supplied0,
                @Bind BytecodeNode bytecodeNode,
                @Bind VirtualFrame frame) {
            return ProtosBytecodeRootNode.prepareSendOneFallback(
                    receiver,
                    selector,
                    ProtosInlineCallbackFrameBindings.durableActivation(
                            child, frameBackedLocals, frameBackedLayout,
                            bytecodeNode, frame),
                    supplied0);

        }

        static GuardedIntegerSend createGuardedIntegerSend(
                Object receiver,
                String selector,
                ProtosPrelude prelude,
                ProtosLanguageContext enteredContext) {
            return ProtosBytecodeRootNode.PrepareSendArguments.createGuardedIntegerSend(
                    receiver, selector, prelude, enteredContext);
        }

        static boolean isIntegerReceiver(Object receiver) {
            return ProtosBytecodeRootNode.PrepareSendArguments.isIntegerReceiver(receiver);
        }

        static GuardedSendTarget createGuardedSendForPrelude(
                Object receiver,
                String selector,
                ProtosPrelude prelude,
                ProtosLanguageContext enteredContext) {
            return ProtosBytecodeRootNode.PrepareSendArguments.createGuardedSendForPrelude(
                    receiver, selector, prelude, enteredContext);
        }

        static GuardedStructuredSend createGuardedStructuredSend(
                Object receiver, String selector, ProtosActivation caller) {
            return ProtosBytecodeRootNode.PrepareSendArguments.createGuardedStructuredSend(
                    receiver, selector, caller);
        }

        static ProtosSlotLookupResult ordinarySendSelectionOrNull(
                Object receiver, String selector, PreparedInlineLiteralCall child) {
            try {
                return ProtosValueLookup.lookup(receiver, selector, child.prelude())
                        .orElse(null);
            } catch (UnsupportedOperationException unsupportedRepresentation) {
                return null;
            }
        }

        static boolean isForeignReceiver(Object receiver) {
            return ProtosBytecodeRootNode.PrepareSendArguments.isForeignReceiver(receiver);
        }

        static ProtosClosureValue ordinarySendClosureOrNull(ProtosSlotLookupResult selected) {
            return selected == null
                    ? null
                    : ProtosBytecodeRootNode.PrepareSendArguments.ordinarySendClosureOrNull(
                            selected);
        }

        static CanonicalClosure ordinarySendClosureDefinitionOrNull(ProtosClosureValue closure) {
            return ProtosBytecodeRootNode.PrepareSendArguments.ordinarySendClosureDefinitionOrNull(
                    closure);
        }

        @NonIdempotent
        static ProtosLanguageContext currentEnteredContext(Node node) {
            return ProtosLanguageContext.current(node);
        }

        static RootCallTarget fastOrdinarySendTarget(
                ProtosClosureValue closure, ProtosLanguageContext enteredContext) {
            return ProtosBytecodeRootNode.PrepareSendArguments.fastOrdinarySendTarget(
                    closure, enteredContext);
        }
    }

    /**
     * PERF025 slice 3 inline-callback {@link PrepareClosureCall} and {@link
     * PrepareClosureCallArguments}: the canonical direct source-Closure tiers
     * select through the invocation's prelude and prepare the compact child
     * with a provenance-equivalent caller; every other selection runs the
     * unchanged activation path.
     */
    @Operation
    @ConstantOperand(
            type = LocalRangeAccessor.class,
            name = "frameBackedLocals")
    @ConstantOperand(
            type = ProtosFrameLexicalLayout.class,
            name = "frameBackedLayout")
    public static final class PrepareInlineClosureCall {
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
                LocalRangeAccessor frameBackedLocals,
                ProtosFrameLexicalLayout frameBackedLayout,
                Object receiver,
                PreparedInlineLiteralCall child,
                @Bind BytecodeNode bytecodeNode,
                @Bind VirtualFrame frame,
                @Bind("currentEnteredContext($node)")
                        ProtosLanguageContext enteredContext,
                @Bind("child.prelude()") ProtosPrelude prelude,
                @Cached("receiver") Object cachedReceiver,
                @Cached("enteredContext") ProtosLanguageContext cachedContext,
                @Cached("createGuardedDirectClosureCall(receiver, prelude, enteredContext)")
                        GuardedDirectClosureCallTarget cachedGuarded) {
            return ProtosBytecodeRootNode.PrepareClosureCall.guardedDirect(
                    receiver,
                    ProtosInlineCallbackFrameBindings.invocationCaller(
                            child, frameBackedLocals, frameBackedLayout, bytecodeNode, frame),
                    enteredContext, cachedReceiver, cachedContext, cachedGuarded);
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
                LocalRangeAccessor frameBackedLocals,
                ProtosFrameLexicalLayout frameBackedLayout,
                Object receiver,
                PreparedInlineLiteralCall child,
                @Bind BytecodeNode bytecodeNode,
                @Bind VirtualFrame frame,
                @Bind("directClosureCallSelectionOrNull(receiver, child)")
                        ProtosClosureValue closure,
                @Bind("directClosureCallDefinitionOrNull(closure)")
                        CanonicalClosure closureDefinition,
                @Bind("currentEnteredContext($node)")
                        ProtosLanguageContext enteredContext,
                @Cached("closureDefinition") CanonicalClosure cachedClosureDefinition,
                @Cached("enteredContext") ProtosLanguageContext cachedContext,
                @Cached("fastOrdinarySendTarget(closure, enteredContext)")
                        RootCallTarget cachedTarget) {
            return ProtosBytecodeRootNode.PrepareClosureCall.fastDirect(
                    receiver,
                    ProtosInlineCallbackFrameBindings.invocationCaller(
                            child, frameBackedLocals, frameBackedLayout, bytecodeNode, frame),
                    closure, closureDefinition, enteredContext,
                    cachedClosureDefinition, cachedContext, cachedTarget);
        }

        @Specialization(replaces = {"guardedDirect", "fastDirect"})
        public static PreparedClosureCall perform(
                LocalRangeAccessor frameBackedLocals,
                ProtosFrameLexicalLayout frameBackedLayout,
                Object receiver,
                PreparedInlineLiteralCall child,
                @Bind BytecodeNode bytecodeNode,
                @Bind VirtualFrame frame) {
            return ProtosBytecodeRootNode.PrepareClosureCall.perform(
                    receiver,
                    ProtosInlineCallbackFrameBindings.durableActivation(
                            child, frameBackedLocals, frameBackedLayout, bytecodeNode, frame));
        }

        @NonIdempotent
        static ProtosLanguageContext currentEnteredContext(Node node) {
            return ProtosLanguageContext.current(node);
        }

        static GuardedDirectClosureCallTarget createGuardedDirectClosureCall(
                Object receiver, ProtosPrelude prelude, ProtosLanguageContext enteredContext) {
            return ProtosBytecodeRootNode.createGuardedDirectClosureCallForPrelude(
                    receiver, prelude, enteredContext);
        }

        static ProtosClosureValue directClosureCallSelectionOrNull(
                Object receiver, PreparedInlineLiteralCall child) {
            return ProtosBytecodeRootNode.directClosureCallSelectionForPreludeOrNull(
                    receiver, child.prelude());
        }

        static CanonicalClosure directClosureCallDefinitionOrNull(ProtosClosureValue closure) {
            return ProtosBytecodeRootNode.directClosureCallDefinitionOrNull(closure);
        }

        static RootCallTarget fastOrdinarySendTarget(
                ProtosClosureValue closure, ProtosLanguageContext enteredContext) {
            return ProtosBytecodeRootNode.PrepareSendArguments.fastOrdinarySendTarget(
                    closure, enteredContext);
        }
    }

    @Operation
    @ConstantOperand(
            type = LocalRangeAccessor.class,
            name = "frameBackedLocals")
    @ConstantOperand(
            type = ProtosFrameLexicalLayout.class,
            name = "frameBackedLayout")
    public static final class PrepareInlineClosureCallArguments {
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
                LocalRangeAccessor frameBackedLocals,
                ProtosFrameLexicalLayout frameBackedLayout,
                Object receiver,
                PreparedInlineLiteralCall child,
                @Variadic Object[] supplied,
                @Bind BytecodeNode bytecodeNode,
                @Bind VirtualFrame frame,
                @Bind("currentEnteredContext($node)")
                        ProtosLanguageContext enteredContext,
                @Bind("child.prelude()") ProtosPrelude prelude,
                @Cached("receiver") Object cachedReceiver,
                @Cached("enteredContext") ProtosLanguageContext cachedContext,
                @Cached("createGuardedDirectClosureCall(receiver, prelude, enteredContext)")
                        GuardedDirectClosureCallTarget cachedGuarded) {
            return ProtosBytecodeRootNode.PrepareClosureCallArguments.guardedDirect(
                    receiver,
                    ProtosInlineCallbackFrameBindings.invocationCaller(
                            child, frameBackedLocals, frameBackedLayout, bytecodeNode, frame),
                    supplied, enteredContext, cachedReceiver, cachedContext, cachedGuarded);
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
                LocalRangeAccessor frameBackedLocals,
                ProtosFrameLexicalLayout frameBackedLayout,
                Object receiver,
                PreparedInlineLiteralCall child,
                @Variadic Object[] supplied,
                @Bind BytecodeNode bytecodeNode,
                @Bind VirtualFrame frame,
                @Bind("directClosureCallSelectionOrNull(receiver, child)")
                        ProtosClosureValue closure,
                @Bind("directClosureCallDefinitionOrNull(closure)")
                        CanonicalClosure closureDefinition,
                @Bind("currentEnteredContext($node)")
                        ProtosLanguageContext enteredContext,
                @Cached("closureDefinition") CanonicalClosure cachedClosureDefinition,
                @Cached("enteredContext") ProtosLanguageContext cachedContext,
                @Cached("fastOrdinarySendTarget(closure, enteredContext)")
                        RootCallTarget cachedTarget) {
            return ProtosBytecodeRootNode.PrepareClosureCallArguments.fastDirect(
                    receiver,
                    ProtosInlineCallbackFrameBindings.invocationCaller(
                            child, frameBackedLocals, frameBackedLayout, bytecodeNode, frame),
                    supplied, closure, closureDefinition, enteredContext,
                    cachedClosureDefinition, cachedContext, cachedTarget);
        }

        @Specialization(replaces = {"guardedDirect", "fastDirect"})
        public static PreparedClosureCall perform(
                LocalRangeAccessor frameBackedLocals,
                ProtosFrameLexicalLayout frameBackedLayout,
                Object receiver,
                PreparedInlineLiteralCall child,
                @Variadic Object[] supplied,
                @Bind BytecodeNode bytecodeNode,
                @Bind VirtualFrame frame) {
            return ProtosBytecodeRootNode.PrepareClosureCallArguments.perform(
                    receiver,
                    ProtosInlineCallbackFrameBindings.durableActivation(
                            child, frameBackedLocals, frameBackedLayout, bytecodeNode, frame),
                    supplied);
        }

        @NonIdempotent
        static ProtosLanguageContext currentEnteredContext(Node node) {
            return ProtosLanguageContext.current(node);
        }

        static GuardedDirectClosureCallTarget createGuardedDirectClosureCall(
                Object receiver, ProtosPrelude prelude, ProtosLanguageContext enteredContext) {
            return ProtosBytecodeRootNode.createGuardedDirectClosureCallForPrelude(
                    receiver, prelude, enteredContext);
        }

        static ProtosClosureValue directClosureCallSelectionOrNull(
                Object receiver, PreparedInlineLiteralCall child) {
            return ProtosBytecodeRootNode.directClosureCallSelectionForPreludeOrNull(
                    receiver, child.prelude());
        }

        static CanonicalClosure directClosureCallDefinitionOrNull(ProtosClosureValue closure) {
            return ProtosBytecodeRootNode.directClosureCallDefinitionOrNull(closure);
        }

        static RootCallTarget fastOrdinarySendTarget(
                ProtosClosureValue closure, ProtosLanguageContext enteredContext) {
            return ProtosBytecodeRootNode.PrepareSendArguments.fastOrdinarySendTarget(
                    closure, enteredContext);
        }
    }


    /* PERF038-H: dedicated single-argument Closure call. */
    @Operation
    @ConstantOperand(
            type = LocalRangeAccessor.class,
            name = "frameBackedLocals")
    @ConstantOperand(
            type = ProtosFrameLexicalLayout.class,
            name = "frameBackedLayout")
    public static final class PrepareInlineClosureCallOne {
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
                LocalRangeAccessor frameBackedLocals,
                ProtosFrameLexicalLayout frameBackedLayout,
                Object receiver,
                PreparedInlineLiteralCall child,
                Object supplied0,
                @Bind BytecodeNode bytecodeNode,
                @Bind VirtualFrame frame,
                @Bind("currentEnteredContext($node)")
                        ProtosLanguageContext enteredContext,
                @Bind("child.prelude()") ProtosPrelude prelude,
                @Cached("receiver") Object cachedReceiver,
                @Cached("enteredContext") ProtosLanguageContext cachedContext,
                @Cached("createGuardedDirectClosureCall(receiver, prelude, enteredContext)")
                        GuardedDirectClosureCallTarget cachedGuarded) {

            return ProtosBytecodeRootNode.finishDirectClosureCallOne(
                    cachedGuarded.closure(),
                    cachedGuarded.target(),
                    supplied0,
                    ProtosInlineCallbackFrameBindings.invocationCaller(
                            child, frameBackedLocals,
                            frameBackedLayout, bytecodeNode, frame));
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
                LocalRangeAccessor frameBackedLocals,
                ProtosFrameLexicalLayout frameBackedLayout,
                Object receiver,
                PreparedInlineLiteralCall child,
                Object supplied0,
                @Bind BytecodeNode bytecodeNode,
                @Bind VirtualFrame frame,
                @Bind("directClosureCallSelectionOrNull(receiver, child)")
                        ProtosClosureValue closure,
                @Bind("directClosureCallDefinitionOrNull(closure)")
                        CanonicalClosure closureDefinition,
                @Bind("currentEnteredContext($node)")
                        ProtosLanguageContext enteredContext,
                @Cached("closureDefinition") CanonicalClosure cachedClosureDefinition,
                @Cached("enteredContext") ProtosLanguageContext cachedContext,
                @Cached("fastOrdinarySendTarget(closure, enteredContext)")
                        RootCallTarget cachedTarget) {

            return ProtosBytecodeRootNode.finishDirectClosureCallOne(
                    closure,
                    cachedTarget,
                    supplied0,
                    ProtosInlineCallbackFrameBindings.invocationCaller(
                            child, frameBackedLocals,
                            frameBackedLayout, bytecodeNode, frame));
        }

        @Specialization(replaces = {"guardedDirect", "fastDirect"})
        public static PreparedClosureCall perform(
                LocalRangeAccessor frameBackedLocals,
                ProtosFrameLexicalLayout frameBackedLayout,
                Object receiver,
                PreparedInlineLiteralCall child,
                Object supplied0,
                @Bind BytecodeNode bytecodeNode,
                @Bind VirtualFrame frame) {
            return ProtosBytecodeRootNode.prepareClosureCallOneFallback(
                    receiver,
                    ProtosInlineCallbackFrameBindings.durableActivation(
                            child, frameBackedLocals, frameBackedLayout,
                            bytecodeNode, frame),
                    supplied0);

        }

        @NonIdempotent
        static ProtosLanguageContext currentEnteredContext(Node node) {
            return ProtosLanguageContext.current(node);
        }

        static GuardedDirectClosureCallTarget createGuardedDirectClosureCall(
                Object receiver, ProtosPrelude prelude, ProtosLanguageContext enteredContext) {
            return ProtosBytecodeRootNode.createGuardedDirectClosureCallForPrelude(
                    receiver, prelude, enteredContext);
        }

        static ProtosClosureValue directClosureCallSelectionOrNull(
                Object receiver, PreparedInlineLiteralCall child) {
            return ProtosBytecodeRootNode.directClosureCallSelectionForPreludeOrNull(
                    receiver, child.prelude());
        }

        static CanonicalClosure directClosureCallDefinitionOrNull(ProtosClosureValue closure) {
            return ProtosBytecodeRootNode.directClosureCallDefinitionOrNull(closure);
        }

        static RootCallTarget fastOrdinarySendTarget(
                ProtosClosureValue closure, ProtosLanguageContext enteredContext) {
            return ProtosBytecodeRootNode.PrepareSendArguments.fastOrdinarySendTarget(
                    closure, enteredContext);
        }
    }

    @Operation
    public static final class PrepareClosureCallVector {
        @Specialization
        public static PreparedClosureCall perform(
                Object receiver,
                ProtosActivation caller,
                PreparedArgumentVector supplied) {
            return ProtosBytecodeRootNode.PrepareClosureCallVector.perform(receiver, caller, supplied);
        }
    }

    @Operation
    public static final class PrepareSendArguments {
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
                Object caller,
                @Variadic Object[] supplied,
                @Bind("currentEnteredContext($node)")
                        ProtosLanguageContext enteredContext,
                @Bind("callerPrelude(caller)") ProtosPrelude prelude,
                @Cached("selector") String cachedSelector,
                @Cached("enteredContext") ProtosLanguageContext cachedContext,
                @Cached("prelude") ProtosPrelude cachedPrelude,
                @Cached("createGuardedIntegerSend(receiver, selector, prelude, enteredContext)")
                        GuardedIntegerSend cachedInteger) {
            return ProtosBytecodeRootNode.PrepareSendArguments.guardedIntegerSend(
                    receiver, selector, exactCaller(caller), supplied, enteredContext, prelude,
                    cachedSelector, cachedContext, cachedPrelude, cachedInteger);
        }

        /*
         * PERF038-E: the guarded ordinary send is specialized on the state of
         * its caller operand (see CurrentCallerReference), so each compiled
         * form carries only the provenance path it has observed:
         *
         *   guardedOrdinarySend           a published caller activation;
         *   guardedOrdinarySendInherited  a compact caller whose callee
         *                                 inherits all provenance from its own
         *                                 caller, passed in its place without
         *                                 materialization;
         *   guardedOrdinarySendMaterializing  any other compact caller, which
         *                                 is materialized and published
         *                                 exactly as CurrentActivation would.
         *
         * The inheritance condition is a guard evaluated on every execution,
         * so a compact caller that stops satisfying it (a different root
         * Closure, prelude or explicit Task) leaves the inherited form and
         * respecializes; no materialization path is compiled into a site
         * that has never needed one. Every form shares the selection
         * Assumption, receiver identity and entered-Context guards and runs
         * the same preparation.
         */
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
            return ProtosBytecodeRootNode.PrepareSendArguments.guardedOrdinarySend(
                    receiver, selector, caller, supplied, enteredContext,
                    cachedReceiver, cachedSelector, cachedContext, cachedSend);
        }

        /*
         * PERF038-F: the inherited form is split by the provenance relation
         * the site has observed, each decided by fixed full-header slots
         * instead of re-deriving the ABI and merging the alternatives:
         *
         *   guardedOrdinarySendInheritedOwnPreludeAbsent  the root Closure has
         *                                 no prelude of its own;
         *   guardedOrdinarySendInheritedSamePrelude  the root Closure's prelude
         *                                 is its caller's;
         *   guardedOrdinarySendInherited  a minimal direct header, decided by
         *                                 the general provenance query.
         *
         * A Closure with a different prelude or an explicit Task satisfies
         * none of the inherited guards and takes the materializing form, as
         * before. The guards are re-evaluated on every execution.
         */
        @Specialization(
                guards = {
                    "isInheritingFullHeader(caller)",
                    "ownPreludeAbsent(caller)",
                    "receiver == cachedReceiver",
                    "selector.equals(cachedSelector)",
                    "enteredContext != null",
                    "enteredContext == cachedContext",
                    "cachedSend != null"
                },
                assumptions = "cachedSend.stability()",
                limit = "3")
        public static PreparedClosureCall guardedOrdinarySendInheritedOwnPreludeAbsent(
                Object receiver,
                String selector,
                Object[] caller,
                @Variadic Object[] supplied,
                @Bind("currentEnteredContext($node)")
                        ProtosLanguageContext enteredContext,
                @Cached("receiver") Object cachedReceiver,
                @Cached("selector") String cachedSelector,
                @Cached("enteredContext") ProtosLanguageContext cachedContext,
                @Cached("createGuardedSend(receiver, selector, fullHeaderCaller(caller), enteredContext)")
                        GuardedSendTarget cachedSend) {
            return ProtosBytecodeRootNode.PrepareSendArguments.guardedOrdinarySend(
                    receiver, selector, ProtosFrameArguments.unmaterializedFullHeaderCaller(caller),
                    supplied, enteredContext, cachedReceiver, cachedSelector, cachedContext,
                    cachedSend);
        }

        @Specialization(
                guards = {
                    "isInheritingFullHeader(caller)",
                    "ownPreludeIsCallers(caller)",
                    "receiver == cachedReceiver",
                    "selector.equals(cachedSelector)",
                    "enteredContext != null",
                    "enteredContext == cachedContext",
                    "cachedSend != null"
                },
                assumptions = "cachedSend.stability()",
                limit = "3")
        public static PreparedClosureCall guardedOrdinarySendInheritedSamePrelude(
                Object receiver,
                String selector,
                Object[] caller,
                @Variadic Object[] supplied,
                @Bind("currentEnteredContext($node)")
                        ProtosLanguageContext enteredContext,
                @Cached("receiver") Object cachedReceiver,
                @Cached("selector") String cachedSelector,
                @Cached("enteredContext") ProtosLanguageContext cachedContext,
                @Cached("createGuardedSend(receiver, selector, fullHeaderCaller(caller), enteredContext)")
                        GuardedSendTarget cachedSend) {
            return ProtosBytecodeRootNode.PrepareSendArguments.guardedOrdinarySend(
                    receiver, selector, ProtosFrameArguments.unmaterializedFullHeaderCaller(caller),
                    supplied, enteredContext, cachedReceiver, cachedSelector, cachedContext,
                    cachedSend);
        }

        @Specialization(
                guards = {
                    "inherited != null",
                    "!isInheritingFullHeader(caller)",
                    "receiver == cachedReceiver",
                    "selector.equals(cachedSelector)",
                    "enteredContext != null",
                    "enteredContext == cachedContext",
                    "cachedSend != null"
                },
                assumptions = "cachedSend.stability()",
                limit = "3")
        public static PreparedClosureCall guardedOrdinarySendInherited(
                Object receiver,
                String selector,
                Object[] caller,
                @Variadic Object[] supplied,
                @Bind("inheritedProvenanceCallerOrNull(caller)") ProtosActivation inherited,
                @Bind("currentEnteredContext($node)")
                        ProtosLanguageContext enteredContext,
                @Cached("receiver") Object cachedReceiver,
                @Cached("selector") String cachedSelector,
                @Cached("enteredContext") ProtosLanguageContext cachedContext,
                @Cached("createGuardedSend(receiver, selector, inherited, enteredContext)")
                        GuardedSendTarget cachedSend) {
            return ProtosBytecodeRootNode.PrepareSendArguments.guardedOrdinarySend(
                    receiver, selector, inherited, supplied, enteredContext,
                    cachedReceiver, cachedSelector, cachedContext, cachedSend);
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
        public static PreparedClosureCall guardedOrdinarySendMaterializing(
                Object receiver,
                String selector,
                Object[] caller,
                @Variadic Object[] supplied,
                @Bind("currentEnteredContext($node)")
                        ProtosLanguageContext enteredContext,
                @Cached("receiver") Object cachedReceiver,
                @Cached("selector") String cachedSelector,
                @Cached("enteredContext") ProtosLanguageContext cachedContext,
                @Cached("createGuardedSend(receiver, selector, exactCaller(caller), enteredContext)")
                        GuardedSendTarget cachedSend) {
            return ProtosBytecodeRootNode.PrepareSendArguments.guardedOrdinarySend(
                    receiver, selector, exactCaller(caller), supplied, enteredContext,
                    cachedReceiver, cachedSelector, cachedContext, cachedSend);
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
                Object caller,
                @Variadic Object[] supplied,
                @Bind("performOrdinarySendLookup(receiver, selector, exactCaller(caller))")
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
            return ProtosBytecodeRootNode.PrepareSendArguments.fastOrdinarySend(
                    receiver, selector, exactCaller(caller), supplied, selected, closure, methodHome,
                    closureDefinition, enteredContext, cachedSelector, cachedClosureDefinition,
                    cachedContext, cachedTarget);
        }

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
                Object caller,
                @Variadic Object[] supplied,
                @Bind("currentEnteredContext($node)")
                        ProtosLanguageContext enteredContext,
                @Cached("receiver") Object cachedReceiver,
                @Cached("selector") String cachedSelector,
                @Cached("enteredContext") ProtosLanguageContext cachedContext,
                @Cached("createGuardedStructuredSend(receiver, selector, exactCaller(caller))")
                        GuardedStructuredSend cachedStructured) {
            return ProtosBytecodeRootNode.PrepareSendArguments.guardedStructuredSend(
                    receiver, selector, exactCaller(caller), supplied, enteredContext,
                    cachedReceiver, cachedSelector, cachedContext, cachedStructured);
        }

        @Specialization(
                replaces = {
                    "guardedIntegerSend",
                    "guardedOrdinarySend",
                    "guardedOrdinarySendInheritedOwnPreludeAbsent",
                    "guardedOrdinarySendInheritedSamePrelude",
                    "guardedOrdinarySendInherited",
                    "guardedOrdinarySendMaterializing",
                    "fastOrdinarySend",
                    "guardedStructuredSend"
                })
        public static PreparedClosureCall perform(
                Object receiver,
                String selector,
                Object caller,
                @Variadic Object[] supplied) {
            return ProtosBytecodeRootNode.PrepareSendArguments.perform(
                    receiver, selector, exactCaller(caller), supplied);
        }

        static GuardedIntegerSend createGuardedIntegerSend(
                Object receiver, String selector, ProtosPrelude prelude) {
            return ProtosBytecodeRootNode.PrepareSendArguments.createGuardedIntegerSend(
                    receiver, selector, prelude);
        }

        static GuardedIntegerSend createGuardedIntegerSend(
                Object receiver,
                String selector,
                ProtosPrelude prelude,
                ProtosLanguageContext enteredContext) {
            return ProtosBytecodeRootNode.PrepareSendArguments.createGuardedIntegerSend(
                    receiver, selector, prelude, enteredContext);
        }

        static boolean isIntegerReceiver(Object receiver) {
            return ProtosBytecodeRootNode.PrepareSendArguments.isIntegerReceiver(receiver);
        }

        /**
         * PERF038-D: the prelude of the caller a {@link CurrentCallerReference}
         * denotes, without materializing it ({@link
         * ProtosFrameArguments#preludeOrNull} answers exactly what the
         * materialized activation would).
         */
        static ProtosPrelude callerPrelude(Object caller) {
            if (caller instanceof ProtosActivation activation) {
                return ProtosBytecodeRootNode.PrepareSendArguments.callerPrelude(activation);
            }
            return ProtosFrameArguments.preludeOrNull((Object[]) caller);
        }

        /**
         * PERF038-D: the exact caller activation a caller operand denotes,
         * materializing and publishing a compact root activation exactly as
         * {@link CurrentActivation} would have when the operand was evaluated.
         * Materialization is not guest-observable by itself: the activation
         * identity is the one every later observer of the root also sees.
         */
        static ProtosActivation exactCaller(Object caller) {
            if (caller instanceof ProtosActivation activation) {
                return activation;
            }
            return ProtosFrameArguments.activation((Object[]) caller);
        }

        /**
         * PERF038-D/E: a caller that is provenance-equivalent to the exact one
         * for the callee header of a guarded ordinary send, whose callee uses
         * its caller only for the prelude, actor module state, current module
         * key, execution domain and Task or dynamic-control state it would
         * inherit (see {@link ProtosFrameArguments#compactInheritedProvenanceCaller}):
         * the own caller of a still-compact root that inherits all of them,
         * else {@code null}. It never materializes. The frame-argument array
         * may be published between executions, so this is re-evaluated on
         * every execution.
         */
        @NonIdempotent
        static ProtosActivation inheritedProvenanceCallerOrNull(Object[] caller) {
            if (!ProtosFrameArguments.isUnmaterializedCompactCall(caller)) {
                return null;
            }
            return ProtosFrameArguments.unmaterializedInheritedProvenanceCaller(caller);
        }

        /**
         * PERF038-F: {@link ProtosFrameArguments#isUnmaterializedInheritingFullHeader}
         * of a compact caller operand, re-evaluated on every execution because
         * the root may be published between executions.
         */
        @NonIdempotent
        static boolean isInheritingFullHeader(Object[] caller) {
            return ProtosFrameArguments.isUnmaterializedInheritingFullHeader(caller);
        }

        /** Precondition: {@link #isInheritingFullHeader}. */
        static boolean ownPreludeAbsent(Object[] caller) {
            return ProtosFrameArguments.unmaterializedFullHeaderOwnPrelude(caller) == null;
        }

        /** Precondition: {@link #isInheritingFullHeader}. */
        static boolean ownPreludeIsCallers(Object[] caller) {
            return ProtosFrameArguments.unmaterializedFullHeaderOwnPrelude(caller)
                    == ProtosFrameArguments.unmaterializedFullHeaderCaller(caller)
                            .preludeOrNullForRuntime();
        }

        /** Precondition: {@link #isInheritingFullHeader}. */
        static ProtosActivation fullHeaderCaller(Object[] caller) {
            return ProtosFrameArguments.unmaterializedFullHeaderCaller(caller);
        }

        static GuardedSendTarget createGuardedSend(
                Object receiver,
                String selector,
                ProtosActivation caller,
                ProtosLanguageContext enteredContext) {
            return ProtosBytecodeRootNode.PrepareSendArguments.createGuardedSend(
                    receiver, selector, caller, enteredContext);
        }

        static GuardedStructuredSend createGuardedStructuredSend(
                Object receiver, String selector, ProtosActivation caller) {
            return ProtosBytecodeRootNode.PrepareSendArguments.createGuardedStructuredSend(
                    receiver, selector, caller);
        }

        static ProtosSlotLookupResult performOrdinarySendLookup(
                Object receiver, String selector, ProtosActivation caller) {
            return ProtosBytecodeRootNode.PrepareSendArguments.performOrdinarySendLookup(
                    receiver, selector, caller);
        }

        static boolean isForeignReceiver(Object receiver) {
            return ProtosBytecodeRootNode.PrepareSendArguments.isForeignReceiver(receiver);
        }

        static ProtosClosureValue ordinarySendClosureOrNull(ProtosSlotLookupResult selected) {
            return ProtosBytecodeRootNode.PrepareSendArguments.ordinarySendClosureOrNull(selected);
        }

        static CanonicalClosure ordinarySendClosureDefinitionOrNull(ProtosClosureValue closure) {
            return ProtosBytecodeRootNode.PrepareSendArguments.ordinarySendClosureDefinitionOrNull(
                    closure);
        }

        @NonIdempotent
        static ProtosLanguageContext currentEnteredContext(Node node) {
            return ProtosBytecodeRootNode.PrepareSendArguments.currentEnteredContext(node);
        }

        static RootCallTarget fastOrdinarySendTarget(
                ProtosClosureValue closure, ProtosLanguageContext enteredContext) {
            return ProtosBytecodeRootNode.PrepareSendArguments.fastOrdinarySendTarget(
                    closure, enteredContext);
        }
    }


    /* PERF038-H: fixed operand count, with the complete existing PIC policy. */
    @Operation
    public static final class PrepareSendZero {
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
                Object caller,

                @Bind("currentEnteredContext($node)")
                        ProtosLanguageContext enteredContext,
                @Bind("callerPrelude(caller)") ProtosPrelude prelude,
                @Cached("selector") String cachedSelector,
                @Cached("enteredContext") ProtosLanguageContext cachedContext,
                @Cached("prelude") ProtosPrelude cachedPrelude,
                @Cached("createGuardedIntegerSend(receiver, selector, prelude, enteredContext)")
                        GuardedIntegerSend cachedInteger) {
            return ProtosBytecodeRootNode.PrepareSendArguments.guardedIntegerSendZero(
                    receiver, caller, cachedInteger);
        }

        /*
         * PERF038-E: the guarded ordinary send is specialized on the state of
         * its caller operand (see CurrentCallerReference), so each compiled
         * form carries only the provenance path it has observed:
         *
         *   guardedOrdinarySend           a published caller activation;
         *   guardedOrdinarySendInherited  a compact caller whose callee
         *                                 inherits all provenance from its own
         *                                 caller, passed in its place without
         *                                 materialization;
         *   guardedOrdinarySendMaterializing  any other compact caller, which
         *                                 is materialized and published
         *                                 exactly as CurrentActivation would.
         *
         * The inheritance condition is a guard evaluated on every execution,
         * so a compact caller that stops satisfying it (a different root
         * Closure, prelude or explicit Task) leaves the inherited form and
         * respecializes; no materialization path is compiled into a site
         * that has never needed one. Every form shares the selection
         * Assumption, receiver identity and entered-Context guards and runs
         * the same preparation.
         */
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

                @Bind("currentEnteredContext($node)")
                        ProtosLanguageContext enteredContext,
                @Cached("receiver") Object cachedReceiver,
                @Cached("selector") String cachedSelector,
                @Cached("enteredContext") ProtosLanguageContext cachedContext,
                @Cached("createGuardedSend(receiver, selector, caller, enteredContext)")
                        GuardedSendTarget cachedSend) {
            return ProtosBytecodeRootNode.PrepareSendArguments.guardedOrdinarySendZero(
                    receiver, caller, cachedSend);
        }

        /*
         * PERF038-F: the inherited form is split by the provenance relation
         * the site has observed, each decided by fixed full-header slots
         * instead of re-deriving the ABI and merging the alternatives:
         *
         *   guardedOrdinarySendInheritedOwnPreludeAbsent  the root Closure has
         *                                 no prelude of its own;
         *   guardedOrdinarySendInheritedSamePrelude  the root Closure's prelude
         *                                 is its caller's;
         *   guardedOrdinarySendInherited  a minimal direct header, decided by
         *                                 the general provenance query.
         *
         * A Closure with a different prelude or an explicit Task satisfies
         * none of the inherited guards and takes the materializing form, as
         * before. The guards are re-evaluated on every execution.
         */
        @Specialization(
                guards = {
                    "isInheritingFullHeader(caller)",
                    "ownPreludeAbsent(caller)",
                    "receiver == cachedReceiver",
                    "selector.equals(cachedSelector)",
                    "enteredContext != null",
                    "enteredContext == cachedContext",
                    "cachedSend != null"
                },
                assumptions = "cachedSend.stability()",
                limit = "3")
        public static PreparedClosureCall guardedOrdinarySendInheritedOwnPreludeAbsent(
                Object receiver,
                String selector,
                Object[] caller,

                @Bind("currentEnteredContext($node)")
                        ProtosLanguageContext enteredContext,
                @Cached("receiver") Object cachedReceiver,
                @Cached("selector") String cachedSelector,
                @Cached("enteredContext") ProtosLanguageContext cachedContext,
                @Cached("createGuardedSend(receiver, selector, fullHeaderCaller(caller), enteredContext)")
                        GuardedSendTarget cachedSend) {
            return ProtosBytecodeRootNode.PrepareSendArguments.guardedOrdinarySendZero(
                    receiver, ProtosFrameArguments.unmaterializedFullHeaderCaller(caller), cachedSend);
        }

        @Specialization(
                guards = {
                    "isInheritingFullHeader(caller)",
                    "ownPreludeIsCallers(caller)",
                    "receiver == cachedReceiver",
                    "selector.equals(cachedSelector)",
                    "enteredContext != null",
                    "enteredContext == cachedContext",
                    "cachedSend != null"
                },
                assumptions = "cachedSend.stability()",
                limit = "3")
        public static PreparedClosureCall guardedOrdinarySendInheritedSamePrelude(
                Object receiver,
                String selector,
                Object[] caller,

                @Bind("currentEnteredContext($node)")
                        ProtosLanguageContext enteredContext,
                @Cached("receiver") Object cachedReceiver,
                @Cached("selector") String cachedSelector,
                @Cached("enteredContext") ProtosLanguageContext cachedContext,
                @Cached("createGuardedSend(receiver, selector, fullHeaderCaller(caller), enteredContext)")
                        GuardedSendTarget cachedSend) {
            return ProtosBytecodeRootNode.PrepareSendArguments.guardedOrdinarySendZero(
                    receiver, ProtosFrameArguments.unmaterializedFullHeaderCaller(caller), cachedSend);
        }

        @Specialization(
                guards = {
                    "inherited != null",
                    "!isInheritingFullHeader(caller)",
                    "receiver == cachedReceiver",
                    "selector.equals(cachedSelector)",
                    "enteredContext != null",
                    "enteredContext == cachedContext",
                    "cachedSend != null"
                },
                assumptions = "cachedSend.stability()",
                limit = "3")
        public static PreparedClosureCall guardedOrdinarySendInherited(
                Object receiver,
                String selector,
                Object[] caller,

                @Bind("inheritedProvenanceCallerOrNull(caller)") ProtosActivation inherited,
                @Bind("currentEnteredContext($node)")
                        ProtosLanguageContext enteredContext,
                @Cached("receiver") Object cachedReceiver,
                @Cached("selector") String cachedSelector,
                @Cached("enteredContext") ProtosLanguageContext cachedContext,
                @Cached("createGuardedSend(receiver, selector, inherited, enteredContext)")
                        GuardedSendTarget cachedSend) {
            return ProtosBytecodeRootNode.PrepareSendArguments.guardedOrdinarySendZero(
                    receiver, inherited, cachedSend);
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
        public static PreparedClosureCall guardedOrdinarySendMaterializing(
                Object receiver,
                String selector,
                Object[] caller,

                @Bind("currentEnteredContext($node)")
                        ProtosLanguageContext enteredContext,
                @Cached("receiver") Object cachedReceiver,
                @Cached("selector") String cachedSelector,
                @Cached("enteredContext") ProtosLanguageContext cachedContext,
                @Cached("createGuardedSend(receiver, selector, exactCaller(caller), enteredContext)")
                        GuardedSendTarget cachedSend) {
            return ProtosBytecodeRootNode.PrepareSendArguments.guardedOrdinarySendZero(
                    receiver, exactCaller(caller), cachedSend);
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
                Object caller,

                @Bind("performOrdinarySendLookup(receiver, selector, exactCaller(caller))")
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
            return ProtosBytecodeRootNode.PrepareSendArguments.fastOrdinarySendZero(
                    closure,
                    receiver,
                    methodHome,
                    exactCaller(caller),
                    cachedTarget);
        }

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
                Object caller,

                @Bind("currentEnteredContext($node)")
                        ProtosLanguageContext enteredContext,
                @Cached("receiver") Object cachedReceiver,
                @Cached("selector") String cachedSelector,
                @Cached("enteredContext") ProtosLanguageContext cachedContext,
                @Cached("createGuardedStructuredSend(receiver, selector, exactCaller(caller))")
                        GuardedStructuredSend cachedStructured) {
            return ProtosBytecodeRootNode.PrepareSendArguments.guardedStructuredSendZero(
                    receiver, exactCaller(caller), cachedStructured);
        }

        @Specialization(
                replaces = {
                    "guardedIntegerSend",
                    "guardedOrdinarySend",
                    "guardedOrdinarySendInheritedOwnPreludeAbsent",
                    "guardedOrdinarySendInheritedSamePrelude",
                    "guardedOrdinarySendInherited",
                    "guardedOrdinarySendMaterializing",
                    "fastOrdinarySend",
                    "guardedStructuredSend"
                })
        public static PreparedClosureCall perform(
                Object receiver,
                String selector,
                Object caller) {
            return ProtosBytecodeRootNode.prepareSendZeroFallback(
                    receiver, selector, exactCaller(caller));

        }

        static GuardedIntegerSend createGuardedIntegerSend(
                Object receiver, String selector, ProtosPrelude prelude) {
            return ProtosBytecodeRootNode.PrepareSendArguments.createGuardedIntegerSend(
                    receiver, selector, prelude);
        }

        static GuardedIntegerSend createGuardedIntegerSend(
                Object receiver,
                String selector,
                ProtosPrelude prelude,
                ProtosLanguageContext enteredContext) {
            return ProtosBytecodeRootNode.PrepareSendArguments.createGuardedIntegerSend(
                    receiver, selector, prelude, enteredContext);
        }

        static boolean isIntegerReceiver(Object receiver) {
            return ProtosBytecodeRootNode.PrepareSendArguments.isIntegerReceiver(receiver);
        }

        /**
         * PERF038-D: the prelude of the caller a {@link CurrentCallerReference}
         * denotes, without materializing it ({@link
         * ProtosFrameArguments#preludeOrNull} answers exactly what the
         * materialized activation would).
         */
        static ProtosPrelude callerPrelude(Object caller) {
            if (caller instanceof ProtosActivation activation) {
                return ProtosBytecodeRootNode.PrepareSendArguments.callerPrelude(activation);
            }
            return ProtosFrameArguments.preludeOrNull((Object[]) caller);
        }

        /**
         * PERF038-D: the exact caller activation a caller operand denotes,
         * materializing and publishing a compact root activation exactly as
         * {@link CurrentActivation} would have when the operand was evaluated.
         * Materialization is not guest-observable by itself: the activation
         * identity is the one every later observer of the root also sees.
         */
        static ProtosActivation exactCaller(Object caller) {
            if (caller instanceof ProtosActivation activation) {
                return activation;
            }
            return ProtosFrameArguments.activation((Object[]) caller);
        }

        /**
         * PERF038-D/E: a caller that is provenance-equivalent to the exact one
         * for the callee header of a guarded ordinary send, whose callee uses
         * its caller only for the prelude, actor module state, current module
         * key, execution domain and Task or dynamic-control state it would
         * inherit (see {@link ProtosFrameArguments#compactInheritedProvenanceCaller}):
         * the own caller of a still-compact root that inherits all of them,
         * else {@code null}. It never materializes. The frame-argument array
         * may be published between executions, so this is re-evaluated on
         * every execution.
         */
        @NonIdempotent
        static ProtosActivation inheritedProvenanceCallerOrNull(Object[] caller) {
            if (!ProtosFrameArguments.isUnmaterializedCompactCall(caller)) {
                return null;
            }
            return ProtosFrameArguments.unmaterializedInheritedProvenanceCaller(caller);
        }

        /**
         * PERF038-F: {@link ProtosFrameArguments#isUnmaterializedInheritingFullHeader}
         * of a compact caller operand, re-evaluated on every execution because
         * the root may be published between executions.
         */
        @NonIdempotent
        static boolean isInheritingFullHeader(Object[] caller) {
            return ProtosFrameArguments.isUnmaterializedInheritingFullHeader(caller);
        }

        /** Precondition: {@link #isInheritingFullHeader}. */
        static boolean ownPreludeAbsent(Object[] caller) {
            return ProtosFrameArguments.unmaterializedFullHeaderOwnPrelude(caller) == null;
        }

        /** Precondition: {@link #isInheritingFullHeader}. */
        static boolean ownPreludeIsCallers(Object[] caller) {
            return ProtosFrameArguments.unmaterializedFullHeaderOwnPrelude(caller)
                    == ProtosFrameArguments.unmaterializedFullHeaderCaller(caller)
                            .preludeOrNullForRuntime();
        }

        /** Precondition: {@link #isInheritingFullHeader}. */
        static ProtosActivation fullHeaderCaller(Object[] caller) {
            return ProtosFrameArguments.unmaterializedFullHeaderCaller(caller);
        }

        static GuardedSendTarget createGuardedSend(
                Object receiver,
                String selector,
                ProtosActivation caller,
                ProtosLanguageContext enteredContext) {
            return ProtosBytecodeRootNode.PrepareSendArguments.createGuardedSend(
                    receiver, selector, caller, enteredContext);
        }

        static GuardedStructuredSend createGuardedStructuredSend(
                Object receiver, String selector, ProtosActivation caller) {
            return ProtosBytecodeRootNode.PrepareSendArguments.createGuardedStructuredSend(
                    receiver, selector, caller);
        }

        static ProtosSlotLookupResult performOrdinarySendLookup(
                Object receiver, String selector, ProtosActivation caller) {
            return ProtosBytecodeRootNode.PrepareSendArguments.performOrdinarySendLookup(
                    receiver, selector, caller);
        }

        static boolean isForeignReceiver(Object receiver) {
            return ProtosBytecodeRootNode.PrepareSendArguments.isForeignReceiver(receiver);
        }

        static ProtosClosureValue ordinarySendClosureOrNull(ProtosSlotLookupResult selected) {
            return ProtosBytecodeRootNode.PrepareSendArguments.ordinarySendClosureOrNull(selected);
        }

        static CanonicalClosure ordinarySendClosureDefinitionOrNull(ProtosClosureValue closure) {
            return ProtosBytecodeRootNode.PrepareSendArguments.ordinarySendClosureDefinitionOrNull(
                    closure);
        }

        @NonIdempotent
        static ProtosLanguageContext currentEnteredContext(Node node) {
            return ProtosBytecodeRootNode.PrepareSendArguments.currentEnteredContext(node);
        }

        static RootCallTarget fastOrdinarySendTarget(
                ProtosClosureValue closure, ProtosLanguageContext enteredContext) {
            return ProtosBytecodeRootNode.PrepareSendArguments.fastOrdinarySendTarget(
                    closure, enteredContext);
        }
    }


    @Operation
    public static final class PrepareSendOne {
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
                Object caller,
                Object supplied0,
                @Bind("currentEnteredContext($node)")
                        ProtosLanguageContext enteredContext,
                @Bind("callerPrelude(caller)") ProtosPrelude prelude,
                @Cached("selector") String cachedSelector,
                @Cached("enteredContext") ProtosLanguageContext cachedContext,
                @Cached("prelude") ProtosPrelude cachedPrelude,
                @Cached("createGuardedIntegerSend(receiver, selector, prelude, enteredContext)")
                        GuardedIntegerSend cachedInteger) {
            return ProtosBytecodeRootNode.PrepareSendArguments.guardedIntegerSendOne(
                    receiver, caller, supplied0, cachedInteger);
        }

        /*
         * PERF038-E: the guarded ordinary send is specialized on the state of
         * its caller operand (see CurrentCallerReference), so each compiled
         * form carries only the provenance path it has observed:
         *
         *   guardedOrdinarySend           a published caller activation;
         *   guardedOrdinarySendInherited  a compact caller whose callee
         *                                 inherits all provenance from its own
         *                                 caller, passed in its place without
         *                                 materialization;
         *   guardedOrdinarySendMaterializing  any other compact caller, which
         *                                 is materialized and published
         *                                 exactly as CurrentActivation would.
         *
         * The inheritance condition is a guard evaluated on every execution,
         * so a compact caller that stops satisfying it (a different root
         * Closure, prelude or explicit Task) leaves the inherited form and
         * respecializes; no materialization path is compiled into a site
         * that has never needed one. Every form shares the selection
         * Assumption, receiver identity and entered-Context guards and runs
         * the same preparation.
         */
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
                Object supplied0,
                @Bind("currentEnteredContext($node)")
                        ProtosLanguageContext enteredContext,
                @Cached("receiver") Object cachedReceiver,
                @Cached("selector") String cachedSelector,
                @Cached("enteredContext") ProtosLanguageContext cachedContext,
                @Cached("createGuardedSend(receiver, selector, caller, enteredContext)")
                        GuardedSendTarget cachedSend) {
            return ProtosBytecodeRootNode.PrepareSendArguments.guardedOrdinarySendOne(
                    receiver, caller, supplied0, cachedSend);
        }

        /*
         * PERF038-F: the inherited form is split by the provenance relation
         * the site has observed, each decided by fixed full-header slots
         * instead of re-deriving the ABI and merging the alternatives:
         *
         *   guardedOrdinarySendInheritedOwnPreludeAbsent  the root Closure has
         *                                 no prelude of its own;
         *   guardedOrdinarySendInheritedSamePrelude  the root Closure's prelude
         *                                 is its caller's;
         *   guardedOrdinarySendInherited  a minimal direct header, decided by
         *                                 the general provenance query.
         *
         * A Closure with a different prelude or an explicit Task satisfies
         * none of the inherited guards and takes the materializing form, as
         * before. The guards are re-evaluated on every execution.
         */
        @Specialization(
                guards = {
                    "isInheritingFullHeader(caller)",
                    "ownPreludeAbsent(caller)",
                    "receiver == cachedReceiver",
                    "selector.equals(cachedSelector)",
                    "enteredContext != null",
                    "enteredContext == cachedContext",
                    "cachedSend != null"
                },
                assumptions = "cachedSend.stability()",
                limit = "3")
        public static PreparedClosureCall guardedOrdinarySendInheritedOwnPreludeAbsent(
                Object receiver,
                String selector,
                Object[] caller,
                Object supplied0,
                @Bind("currentEnteredContext($node)")
                        ProtosLanguageContext enteredContext,
                @Cached("receiver") Object cachedReceiver,
                @Cached("selector") String cachedSelector,
                @Cached("enteredContext") ProtosLanguageContext cachedContext,
                @Cached("createGuardedSend(receiver, selector, fullHeaderCaller(caller), enteredContext)")
                        GuardedSendTarget cachedSend) {
            return ProtosBytecodeRootNode.PrepareSendArguments.guardedOrdinarySendOne(
                    receiver, ProtosFrameArguments.unmaterializedFullHeaderCaller(caller), supplied0, cachedSend);
        }

        @Specialization(
                guards = {
                    "isInheritingFullHeader(caller)",
                    "ownPreludeIsCallers(caller)",
                    "receiver == cachedReceiver",
                    "selector.equals(cachedSelector)",
                    "enteredContext != null",
                    "enteredContext == cachedContext",
                    "cachedSend != null"
                },
                assumptions = "cachedSend.stability()",
                limit = "3")
        public static PreparedClosureCall guardedOrdinarySendInheritedSamePrelude(
                Object receiver,
                String selector,
                Object[] caller,
                Object supplied0,
                @Bind("currentEnteredContext($node)")
                        ProtosLanguageContext enteredContext,
                @Cached("receiver") Object cachedReceiver,
                @Cached("selector") String cachedSelector,
                @Cached("enteredContext") ProtosLanguageContext cachedContext,
                @Cached("createGuardedSend(receiver, selector, fullHeaderCaller(caller), enteredContext)")
                        GuardedSendTarget cachedSend) {
            return ProtosBytecodeRootNode.PrepareSendArguments.guardedOrdinarySendOne(
                    receiver, ProtosFrameArguments.unmaterializedFullHeaderCaller(caller), supplied0, cachedSend);
        }

        @Specialization(
                guards = {
                    "inherited != null",
                    "!isInheritingFullHeader(caller)",
                    "receiver == cachedReceiver",
                    "selector.equals(cachedSelector)",
                    "enteredContext != null",
                    "enteredContext == cachedContext",
                    "cachedSend != null"
                },
                assumptions = "cachedSend.stability()",
                limit = "3")
        public static PreparedClosureCall guardedOrdinarySendInherited(
                Object receiver,
                String selector,
                Object[] caller,
                Object supplied0,
                @Bind("inheritedProvenanceCallerOrNull(caller)") ProtosActivation inherited,
                @Bind("currentEnteredContext($node)")
                        ProtosLanguageContext enteredContext,
                @Cached("receiver") Object cachedReceiver,
                @Cached("selector") String cachedSelector,
                @Cached("enteredContext") ProtosLanguageContext cachedContext,
                @Cached("createGuardedSend(receiver, selector, inherited, enteredContext)")
                        GuardedSendTarget cachedSend) {
            return ProtosBytecodeRootNode.PrepareSendArguments.guardedOrdinarySendOne(
                    receiver, inherited, supplied0, cachedSend);
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
        public static PreparedClosureCall guardedOrdinarySendMaterializing(
                Object receiver,
                String selector,
                Object[] caller,
                Object supplied0,
                @Bind("currentEnteredContext($node)")
                        ProtosLanguageContext enteredContext,
                @Cached("receiver") Object cachedReceiver,
                @Cached("selector") String cachedSelector,
                @Cached("enteredContext") ProtosLanguageContext cachedContext,
                @Cached("createGuardedSend(receiver, selector, exactCaller(caller), enteredContext)")
                        GuardedSendTarget cachedSend) {
            return ProtosBytecodeRootNode.PrepareSendArguments.guardedOrdinarySendOne(
                    receiver, exactCaller(caller), supplied0, cachedSend);
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
                Object caller,
                Object supplied0,
                @Bind("performOrdinarySendLookup(receiver, selector, exactCaller(caller))")
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
            return ProtosBytecodeRootNode.PrepareSendArguments.fastOrdinarySendOne(
                    closure,
                    receiver,
                    methodHome,
                    exactCaller(caller),
                    cachedTarget,
                    supplied0);
        }

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
                Object caller,
                Object supplied0,
                @Bind("currentEnteredContext($node)")
                        ProtosLanguageContext enteredContext,
                @Cached("receiver") Object cachedReceiver,
                @Cached("selector") String cachedSelector,
                @Cached("enteredContext") ProtosLanguageContext cachedContext,
                @Cached("createGuardedStructuredSend(receiver, selector, exactCaller(caller))")
                        GuardedStructuredSend cachedStructured) {
            return ProtosBytecodeRootNode.PrepareSendArguments.guardedStructuredSendOne(
                    receiver, exactCaller(caller), supplied0, cachedStructured);
        }

        @Specialization(
                replaces = {
                    "guardedIntegerSend",
                    "guardedOrdinarySend",
                    "guardedOrdinarySendInheritedOwnPreludeAbsent",
                    "guardedOrdinarySendInheritedSamePrelude",
                    "guardedOrdinarySendInherited",
                    "guardedOrdinarySendMaterializing",
                    "fastOrdinarySend",
                    "guardedStructuredSend"
                })
        public static PreparedClosureCall perform(
                Object receiver,
                String selector,
                Object caller,
                Object supplied0) {
            return ProtosBytecodeRootNode.prepareSendOneFallback(
                    receiver, selector, exactCaller(caller), supplied0);

        }

        static GuardedIntegerSend createGuardedIntegerSend(
                Object receiver, String selector, ProtosPrelude prelude) {
            return ProtosBytecodeRootNode.PrepareSendArguments.createGuardedIntegerSend(
                    receiver, selector, prelude);
        }

        static GuardedIntegerSend createGuardedIntegerSend(
                Object receiver,
                String selector,
                ProtosPrelude prelude,
                ProtosLanguageContext enteredContext) {
            return ProtosBytecodeRootNode.PrepareSendArguments.createGuardedIntegerSend(
                    receiver, selector, prelude, enteredContext);
        }

        static boolean isIntegerReceiver(Object receiver) {
            return ProtosBytecodeRootNode.PrepareSendArguments.isIntegerReceiver(receiver);
        }

        /**
         * PERF038-D: the prelude of the caller a {@link CurrentCallerReference}
         * denotes, without materializing it ({@link
         * ProtosFrameArguments#preludeOrNull} answers exactly what the
         * materialized activation would).
         */
        static ProtosPrelude callerPrelude(Object caller) {
            if (caller instanceof ProtosActivation activation) {
                return ProtosBytecodeRootNode.PrepareSendArguments.callerPrelude(activation);
            }
            if (caller instanceof PreparedInlineLiteralCall inline) {
                return inline.prelude();
            }
            return ProtosFrameArguments.preludeOrNull((Object[]) caller);
        }

        /**
         * PERF038-D: the exact caller activation a caller operand denotes,
         * materializing and publishing a compact root activation exactly as
         * {@link CurrentActivation} would have when the operand was evaluated.
         * Materialization is not guest-observable by itself: the activation
         * identity is the one every later observer of the root also sees.
         */
        static ProtosActivation exactCaller(Object caller) {
            if (caller instanceof ProtosActivation activation) {
                return activation;
            }
            return ProtosFrameArguments.activation((Object[]) caller);
        }

        /**
         * PERF038-D/E: a caller that is provenance-equivalent to the exact one
         * for the callee header of a guarded ordinary send, whose callee uses
         * its caller only for the prelude, actor module state, current module
         * key, execution domain and Task or dynamic-control state it would
         * inherit (see {@link ProtosFrameArguments#compactInheritedProvenanceCaller}):
         * the own caller of a still-compact root that inherits all of them,
         * else {@code null}. It never materializes. The frame-argument array
         * may be published between executions, so this is re-evaluated on
         * every execution.
         */
        @NonIdempotent
        static ProtosActivation inheritedProvenanceCallerOrNull(Object[] caller) {
            if (!ProtosFrameArguments.isUnmaterializedCompactCall(caller)) {
                return null;
            }
            return ProtosFrameArguments.unmaterializedInheritedProvenanceCaller(caller);
        }

        /**
         * PERF038-F: {@link ProtosFrameArguments#isUnmaterializedInheritingFullHeader}
         * of a compact caller operand, re-evaluated on every execution because
         * the root may be published between executions.
         */
        @NonIdempotent
        static boolean isInheritingFullHeader(Object[] caller) {
            return ProtosFrameArguments.isUnmaterializedInheritingFullHeader(caller);
        }

        /** Precondition: {@link #isInheritingFullHeader}. */
        static boolean ownPreludeAbsent(Object[] caller) {
            return ProtosFrameArguments.unmaterializedFullHeaderOwnPrelude(caller) == null;
        }

        /** Precondition: {@link #isInheritingFullHeader}. */
        static boolean ownPreludeIsCallers(Object[] caller) {
            return ProtosFrameArguments.unmaterializedFullHeaderOwnPrelude(caller)
                    == ProtosFrameArguments.unmaterializedFullHeaderCaller(caller)
                            .preludeOrNullForRuntime();
        }

        /** Precondition: {@link #isInheritingFullHeader}. */
        static ProtosActivation fullHeaderCaller(Object[] caller) {
            return ProtosFrameArguments.unmaterializedFullHeaderCaller(caller);
        }

        static GuardedSendTarget createGuardedSend(
                Object receiver,
                String selector,
                ProtosActivation caller,
                ProtosLanguageContext enteredContext) {
            return ProtosBytecodeRootNode.PrepareSendArguments.createGuardedSend(
                    receiver, selector, caller, enteredContext);
        }

        static GuardedStructuredSend createGuardedStructuredSend(
                Object receiver, String selector, ProtosActivation caller) {
            return ProtosBytecodeRootNode.PrepareSendArguments.createGuardedStructuredSend(
                    receiver, selector, caller);
        }

        static ProtosSlotLookupResult performOrdinarySendLookup(
                Object receiver, String selector, ProtosActivation caller) {
            return ProtosBytecodeRootNode.PrepareSendArguments.performOrdinarySendLookup(
                    receiver, selector, caller);
        }

        static boolean isForeignReceiver(Object receiver) {
            return ProtosBytecodeRootNode.PrepareSendArguments.isForeignReceiver(receiver);
        }

        static ProtosClosureValue ordinarySendClosureOrNull(ProtosSlotLookupResult selected) {
            return ProtosBytecodeRootNode.PrepareSendArguments.ordinarySendClosureOrNull(selected);
        }

        static CanonicalClosure ordinarySendClosureDefinitionOrNull(ProtosClosureValue closure) {
            return ProtosBytecodeRootNode.PrepareSendArguments.ordinarySendClosureDefinitionOrNull(
                    closure);
        }

        @NonIdempotent
        static ProtosLanguageContext currentEnteredContext(Node node) {
            return ProtosBytecodeRootNode.PrepareSendArguments.currentEnteredContext(node);
        }

        static RootCallTarget fastOrdinarySendTarget(
                ProtosClosureValue closure, ProtosLanguageContext enteredContext) {
            return ProtosBytecodeRootNode.PrepareSendArguments.fastOrdinarySendTarget(
                    closure, enteredContext);
        }
    }

    @Operation
    public static final class PrepareSendVector {
        @Specialization
        public static PreparedClosureCall perform(
                Object receiver,
                String selector,
                ProtosActivation caller,
                PreparedArgumentVector supplied) {
            return ProtosBytecodeRootNode.PrepareSendVector.perform(receiver, selector, caller, supplied);
        }
    }

    @Operation
    public static final class PrepareSuperSendArguments {
        @Specialization
        public static PreparedClosureCall perform(
                String selector,
                ProtosActivation caller,
                @Variadic Object[] supplied) {
            return ProtosBytecodeRootNode.PrepareSuperSendArguments.perform(selector, caller, supplied);
        }
    }

    @Operation
    public static final class PrepareSuperSendVector {
        @Specialization
        public static PreparedClosureCall perform(
                String selector,
                ProtosActivation caller,
                PreparedArgumentVector supplied) {
            return ProtosBytecodeRootNode.PrepareSuperSendVector.perform(selector, caller, supplied);
        }
    }

    // ---- Prepared invocation and C-prime composition -------------------------------

    @Operation
    public static final class RequiresStructuredDispatch {
        @Specialization
        public static boolean ordinary(OrdinarySourceCall prepared) {
            return ProtosBytecodeRootNode.RequiresStructuredDispatch.ordinary(prepared);
        }

        @Specialization
        public static boolean nativeCall(NativeCall prepared) {
            return ProtosBytecodeRootNode.RequiresStructuredDispatch.nativeCall(prepared);
        }

        @Specialization
        public static boolean immediate(ImmediateResultCall prepared) {
            return ProtosBytecodeRootNode.RequiresStructuredDispatch.immediate(prepared);
        }

        @Specialization
        public static boolean moduleInitialization(ModuleInitializationCall prepared) {
            return ProtosBytecodeRootNode.RequiresStructuredDispatch.moduleInitialization(prepared);
        }
    }

    // ---- PLAT043 standard Boolean orchestration ------------------------------------
    //
    // The prepared standard Boolean capability (IF_TRUE, IF_FALSE,
    // IF_TRUE_IF_FALSE, AND, OR) is sequenced locally in this root instead of
    // entering the untagged structured root. The capability is read from the
    // already-selected prepared call, never from the selector spelling; the
    // finite state machine stays owned by ProtosBytecodeRootNode.PreparedBooleanCall.

    /**
     * Records whether an ordinary non-Boolean send was actually observed
     * at this Boolean-control site.
     *
     * <p>Until the first miss, the generic continuation is reached only after
     * deoptimization, so it does not belong to the compiled Boolean-only
     * execution path.
     */
    static final class CanonicalBooleanMissProfile {
        @CompilationFinal private boolean genericObserved;

        private CanonicalBooleanMissProfile(boolean genericObserved) {
            this.genericObserved = genericObserved;
        }

        static CanonicalBooleanMissProfile create() {
            return new CanonicalBooleanMissProfile(false);
        }

        static CanonicalBooleanMissProfile uncached() {
            return new CanonicalBooleanMissProfile(true);
        }

        void observeMiss() {
            if (!genericObserved) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                genericObserved = true;
            }
        }
    }

    /**
     * I092: native canonical Boolean.ifTrue, before generic send preparation.
     *
     * <p>The lowerer emits these candidates only for an ordinary {@code ifTrue}
     * send compiled after the standard root Object was published FROZEN (D049).
     * Canonical true/false own no slots and delegate to that root by
     * representation, and publication validates its ifTrue as the standard
     * behavior, so D013 selection for those two receivers is fixed for the life
     * of the site: no lookup, Assumption or cache is needed, and the cached and
     * uncached interpreters execute the same two identity tests.
     *
     * <p>Result: null for any other receiver (ordinary dispatch), ProtosNullValue
     * for false, otherwise the selected callback as a PreparedInlineLiteralCall
     * invoked from the send's own caller. No standard ifTrue activation exists.
     */

    /**
     * PERF041-D1: the primitive classification decides only whether
     * canonical Boolean.ifTrue selects its callback.
     */
    @Operation
    public static final class IsCanonicalIfTrueTrue {
        @Specialization
        public static boolean perform(int classification) {
            return classification
                    == ProtosCanonicalBooleanOneNode.CLASS_TRUE;
        }
    }

    /** I095-C: the canonical FALSE classification of a dynamic control receiver. */
    @Operation
    public static final class IsCanonicalBooleanFalseClass {
        @Specialization
        public static boolean perform(int classification) {
            return classification
                    == ProtosCanonicalBooleanOneNode.CLASS_FALSE;
        }
    }

    /**
     * I095-D: the and/or policy for the normal result of a selected callback
     * that ran inline. A canonical true/false passes unchanged; any other
     * value, including null and a Future, signals the same standard Error as
     * {@link ProtosStandardBooleanProtocol#requireBooleanResult}. The exact
     * caller is resolved only on that failure path.
     */
    @Operation
    public static final class RequireCanonicalBooleanControlResult {
        @Specialization
        public static Object perform(Object result, Object caller) {
            if (result == ProtosBooleanValue.TRUE
                    || result == ProtosBooleanValue.FALSE) {
                return result;
            }
            return ProtosStandardBooleanProtocol.requireBooleanResult(
                    result, PrepareSendArguments.exactCaller(caller));
        }
    }

    /**
     * Result when no native callback is selected.
     * MISS remains the Java null sentinel for ordinary dispatch.
     */
    @Operation
    public static final class CanonicalIfTrueNonTrueResult {
        @Specialization
        public static Object perform(int classification) {
            return classification
                    == ProtosCanonicalBooleanOneNode.CLASS_FALSE
                            ? ProtosNullValue.INSTANCE
                            : null;
        }
    }

    /**
     * Prepare the selected callback only on canonical TRUE.
     * The generic callable protocol remains the existing fallback.
     */
    @Operation
    public static final class PrepareCanonicalIfTrueCallback {
        @Specialization
        public static PreparedInlineLiteralCall perform(
                Object caller,
                Object supplied,
                Object literalPlan,
                @Bind("currentEnteredContext($node)")
                    ProtosLanguageContext context,
                @Cached ProtosNativeIfTrueCallbackNode callback) {
            return callback.execute(
                    caller, supplied, literalPlan, context);
        }

        @NonIdempotent
        static ProtosLanguageContext currentEnteredContext(Node node) {
            return ProtosLanguageContext.current(node);
        }
    }

    /** Spread: exact arity is part of canonical classification. */

    /*
     * I101-A (PLAT058-D): there is no run-time execution-mode operation. The
     * immutable profile of the owning Context selects the physical lowering
     * when the root is built (CanonicalToBytecodeLowerer).
     */

    @Operation
    public static final class TryCompactCanonicalBooleanVector {

        static CanonicalBooleanMissProfile createMissProfile() {
            return CanonicalBooleanMissProfile.create();
        }

        static CanonicalBooleanMissProfile uncachedMissProfile() {
            return CanonicalBooleanMissProfile.uncached();
        }

        static boolean canonicalFalse(Object receiver, PreparedArgumentVector vector) {
            return vector.soleValueOrNull() != null && receiver == ProtosBooleanValue.FALSE;
        }

        static boolean canonicalTrue(Object receiver, PreparedArgumentVector vector) {
            return vector.soleValueOrNull() != null && receiver == ProtosBooleanValue.TRUE;
        }

        @Specialization(guards = "canonicalFalse(receiver, vector)")
        public static int falseBranch(Object receiver, PreparedArgumentVector vector) {
            return ProtosCanonicalBooleanOneNode.CLASS_FALSE;
        }

        @Specialization(guards = "canonicalTrue(receiver, vector)")
        public static int trueBranch(Object receiver, PreparedArgumentVector vector) {
            return ProtosCanonicalBooleanOneNode.CLASS_TRUE;
        }

        @Specialization(guards = {
                "!canonicalFalse(receiver, vector)",
                "!canonicalTrue(receiver, vector)"
        })
        public static int genericMiss(
                Object receiver, PreparedArgumentVector vector,
                @Cached(
                    value = "createMissProfile()",
                    uncached = "uncachedMissProfile()")
                    CanonicalBooleanMissProfile missProfile) {
            missProfile.observeMiss();
            return ProtosCanonicalBooleanOneNode.CLASS_MISS;
        }
    }

    /** Frame-native One: classification does not observe the caller. */

    @Operation
    public static final class TryCompactCanonicalBooleanInlineOne {

        static CanonicalBooleanMissProfile createMissProfile() {
            return CanonicalBooleanMissProfile.create();
        }

        static CanonicalBooleanMissProfile uncachedMissProfile() {
            return CanonicalBooleanMissProfile.uncached();
        }

        static boolean canonicalFalse(Object receiver) {
            return receiver == ProtosBooleanValue.FALSE;
        }

        static boolean canonicalTrue(Object receiver) {
            return receiver == ProtosBooleanValue.TRUE;
        }

        @Specialization(guards = "canonicalFalse(receiver)")
        public static int falseBranch(Object receiver) {
            return ProtosCanonicalBooleanOneNode.CLASS_FALSE;
        }

        @Specialization(guards = "canonicalTrue(receiver)")
        public static int trueBranch(Object receiver) {
            return ProtosCanonicalBooleanOneNode.CLASS_TRUE;
        }

        @Specialization(guards = {
                "!canonicalFalse(receiver)",
                "!canonicalTrue(receiver)"
        })
        public static int genericMiss(
                Object receiver,
                @Cached(
                    value = "createMissProfile()",
                    uncached = "uncachedMissProfile()")
                    CanonicalBooleanMissProfile missProfile) {
            missProfile.observeMiss();
            return ProtosCanonicalBooleanOneNode.CLASS_MISS;
        }
    }

    /** Frame-native Vector: caller observation is deferred. */

    @Operation
    public static final class TryCompactCanonicalBooleanInlineVector {

        static CanonicalBooleanMissProfile createMissProfile() {
            return CanonicalBooleanMissProfile.create();
        }

        static CanonicalBooleanMissProfile uncachedMissProfile() {
            return CanonicalBooleanMissProfile.uncached();
        }

        static boolean canonicalFalse(Object receiver, PreparedArgumentVector vector) {
            return vector.soleValueOrNull() != null && receiver == ProtosBooleanValue.FALSE;
        }

        static boolean canonicalTrue(Object receiver, PreparedArgumentVector vector) {
            return vector.soleValueOrNull() != null && receiver == ProtosBooleanValue.TRUE;
        }

        @Specialization(guards = "canonicalFalse(receiver, vector)")
        public static int falseBranch(Object receiver, PreparedArgumentVector vector) {
            return ProtosCanonicalBooleanOneNode.CLASS_FALSE;
        }

        @Specialization(guards = "canonicalTrue(receiver, vector)")
        public static int trueBranch(Object receiver, PreparedArgumentVector vector) {
            return ProtosCanonicalBooleanOneNode.CLASS_TRUE;
        }

        @Specialization(guards = {
                "!canonicalFalse(receiver, vector)",
                "!canonicalTrue(receiver, vector)"
        })
        public static int genericMiss(
                Object receiver, PreparedArgumentVector vector,
                @Cached(
                    value = "createMissProfile()",
                    uncached = "uncachedMissProfile()")
                    CanonicalBooleanMissProfile missProfile) {
            missProfile.observeMiss();
            return ProtosCanonicalBooleanOneNode.CLASS_MISS;
        }
    }

    /**
     * Selected spread callback. The vector has already passed the
     * one-argument classification guard.
     */
    @Operation
    public static final class PrepareCanonicalIfTrueVectorCallback {
        @Specialization
        public static PreparedInlineLiteralCall perform(
                Object caller,
                PreparedArgumentVector vector,
                @Cached ProtosNativeIfTrueCallbackNode callback) {
            return callback.execute(
                    caller, vector.soleValueOrNull(), null, null);
        }
    }

    /**
     * The inline callback's exact caller is observed only when TRUE
     * selects its child. FALSE and MISS never enter this operation.
     */
    @Operation
    @ConstantOperand(
            type = LocalRangeAccessor.class,
            name = "frameBackedLocals")
    @ConstantOperand(
            type = ProtosFrameLexicalLayout.class,
            name = "frameBackedLayout")
    public static final class PrepareCanonicalIfTrueInlineCallback {
        @Specialization
        public static PreparedInlineLiteralCall perform(
                LocalRangeAccessor frameBackedLocals,
                ProtosFrameLexicalLayout frameBackedLayout,
                PreparedInlineLiteralCall child,
                Object supplied,
                Object literalPlan,
                @Bind BytecodeNode bytecodeNode,
                @Bind VirtualFrame frame,
                @Bind("currentEnteredContext($node)")
                    ProtosLanguageContext context,
                @Cached ProtosNativeIfTrueCallbackNode callback) {

            ProtosActivation caller =
                    ProtosInlineCallbackFrameBindings.invocationCaller(
                            child, frameBackedLocals,
                            frameBackedLayout, bytecodeNode, frame);

            return callback.execute(
                    caller, supplied, literalPlan, context);
        }

        @NonIdempotent
        static ProtosLanguageContext currentEnteredContext(Node node) {
            return ProtosLanguageContext.current(node);
        }
    }

    /**
     * Inline spread: preserve the original vector callback protocol,
     * including its null entered-context fallback.
     */
    @Operation
    @ConstantOperand(
            type = LocalRangeAccessor.class,
            name = "frameBackedLocals")
    @ConstantOperand(
            type = ProtosFrameLexicalLayout.class,
            name = "frameBackedLayout")
    public static final class PrepareCanonicalIfTrueInlineVectorCallback {
        @Specialization
        public static PreparedInlineLiteralCall perform(
                LocalRangeAccessor frameBackedLocals,
                ProtosFrameLexicalLayout frameBackedLayout,
                PreparedInlineLiteralCall child,
                PreparedArgumentVector vector,
                @Bind BytecodeNode bytecodeNode,
                @Bind VirtualFrame frame,
                @Cached ProtosNativeIfTrueCallbackNode callback) {

            ProtosActivation caller =
                    ProtosInlineCallbackFrameBindings.invocationCaller(
                            child, frameBackedLocals,
                            frameBackedLayout, bytecodeNode, frame);

            return callback.execute(
                    caller, vector.soleValueOrNull(), null, null);
        }
    }

    @Operation
    public static final class IsCompactCanonicalBooleanMiss {
        @Specialization
        public static boolean perform(Object value) {
            return value == null;
        }
    }

    @Operation
    public static final class IsCompactCanonicalBooleanNoCallback {
        @Specialization
        public static boolean perform(Object value) {
            return value == ProtosNullValue.INSTANCE;
        }
    }

    @Operation
    public static final class IsNativeIfTrueInlineCallback {
        @Specialization
        public static boolean perform(Object value) {
            return value instanceof PreparedInlineLiteralCall;
        }
    }

    /**
     * I092: completes the outer call of an ifTrue literal site exactly once,
     * after its callback, on every exit. ProtosNullValue means no outer call:
     * the native path (which owns no ifTrue activation or ReturnHome), a
     * non-Boolean generic selection (completed by its own dispatch) or an exit
     * before any outer call was recorded.
     */
    @Operation
    public static final class CompleteIfTrueSiteOuter {
        @Specialization
        public static void none(ProtosNullValue outer) {
            // No outer call owned by this site.
        }

        @Specialization(guards = "!prepared.hasReturnHomeLifecycle()")
        public static void ordinaryWithoutHomeLifecycle(
                OrdinarySourceCall prepared) {
            // No owned materialized ReturnHome to complete.
        }

        @Specialization(guards = "prepared.hasReturnHomeLifecycle()")
        public static void ordinary(OrdinarySourceCall prepared) {
            ProtosBytecodeRootNode.CompleteClosureCall.ordinary(prepared);
        }

        @Specialization
        public static void nativeCall(NativeCall prepared) {
            ProtosBytecodeRootNode.CompleteClosureCall.nativeCall(prepared);
        }

        @Specialization
        public static void immediate(ImmediateResultCall prepared) {
            ProtosBytecodeRootNode.CompleteClosureCall.immediate(prepared);
        }

        @Specialization
        public static void moduleInitialization(ModuleInitializationCall prepared) {
            ProtosBytecodeRootNode.CompleteClosureCall.moduleInitialization(prepared);
        }
    }

    /** I092: B-prime admission of the native path's prepared callback. */
    @Operation
    public static final class AdmitsNativeIfTrueInline {
        @Specialization
        public static boolean perform(
                PreparedInlineLiteralCall child,
                ProtosClosureExecutionPlanCell literalPlan) {
            return child.admits(literalPlan);
        }
    }

    @Operation
    public static final class IsStructuredBooleanCall {
        @Specialization
        public static boolean ordinary(OrdinarySourceCall prepared) {
            return ProtosBytecodeRootNode.IsStructuredBooleanCall.ordinary(prepared);
        }

        @Specialization
        public static boolean nativeCall(NativeCall prepared) {
            return ProtosBytecodeRootNode.IsStructuredBooleanCall.nativeCall(prepared);
        }

        @Specialization
        public static boolean immediate(ImmediateResultCall prepared) {
            return ProtosBytecodeRootNode.IsStructuredBooleanCall.immediate(prepared);
        }

        @Specialization
        public static boolean moduleInitialization(ModuleInitializationCall prepared) {
            return ProtosBytecodeRootNode.IsStructuredBooleanCall.moduleInitialization(prepared);
        }

    }

    @Operation
    public static final class PrepareStructuredBooleanCall {
        @Specialization
        public static PreparedBooleanCall ordinary(OrdinarySourceCall prepared) {
            return ProtosBytecodeRootNode.PrepareStructuredBooleanCall.ordinary(prepared);
        }

        @Specialization
        public static PreparedBooleanCall nativeCall(NativeCall prepared) {
            return ProtosBytecodeRootNode.PrepareStructuredBooleanCall.nativeCall(prepared);
        }

        @Specialization
        public static PreparedBooleanCall immediate(ImmediateResultCall prepared) {
            return ProtosBytecodeRootNode.PrepareStructuredBooleanCall.immediate(prepared);
        }

        @Specialization
        public static PreparedBooleanCall moduleInitialization(ModuleInitializationCall prepared) {
            return ProtosBytecodeRootNode.PrepareStructuredBooleanCall
                    .moduleInitialization(prepared);
        }

    }

    @Operation
    public static final class StructuredBooleanHasCallback {
        @Specialization
        public static boolean perform(PreparedBooleanCall prepared) {
            return ProtosBytecodeRootNode.StructuredBooleanHasCallback.perform(prepared);
        }
    }

    @Operation
    public static final class PrepareStructuredBooleanCallbackCall {
        @Specialization
        public static PreparedClosureCall perform(PreparedBooleanCall prepared) {
            return ProtosBytecodeRootNode.PrepareStructuredBooleanCallbackCall.perform(prepared);
        }
    }

    @Operation
    public static final class PrepareInlineStructuredBooleanCallbackCall {
        @Specialization
        public static PreparedInlineLiteralCall perform(PreparedBooleanCall prepared) {
            return prepared.prepareInlineCallback();
        }
    }

    @Operation
    public static final class StructuredBooleanImmediateResult {
        @Specialization
        public static Object perform(PreparedBooleanCall prepared) {
            return ProtosBytecodeRootNode.StructuredBooleanImmediateResult.perform(prepared);
        }
    }

    @Operation
    public static final class FinishStructuredBooleanCallback {
        @Specialization
        public static Object perform(PreparedBooleanCall prepared, Object result) {
            return ProtosBytecodeRootNode.FinishStructuredBooleanCallback.perform(prepared, result);
        }
    }

    // ---- PLAT044 B′ inline literal callback (PERF026-B1) ----------------------------
    //
    // After ordinary selection and PLAT043 preparation, an eligible immediate
    // literal callback runs its body inline in this root under the prepared
    // child's own fresh activation (see CanonicalToBytecodeLowerer
    // #emitInlineLiteralCallback). Eligibility is owned by PreparedBooleanCall.

    @Operation
    public static final class AdmitsInlineLiteralCallback {
        @Specialization
        public static boolean perform(
                PreparedBooleanCall prepared,
                PreparedInlineLiteralCall child,
                Object literal,
                ProtosClosureExecutionPlanCell literalPlan,
                int position) {
            return prepared.admitsInlineLiteralCallback(child, literal, literalPlan, position);
        }
    }

    // ---- PLAT057-B inline ordinary Closure-literal call (I096-A) ------------------
    //
    // An ordinary positional call (exact arity up to
    // CanonicalToBytecodeLowerer#MAX_INLINE_CLOSURE_CALL_ARGUMENTS) whose
    // receiver is the site's own staged Closure literal (I096-A), or a
    // binding with a candidate literal body (I096-B1),
    // is prepared through the PLAT044 B′ carrier seam (one ordinary
    // call selection) and admitted only when the carrier's effective body
    // target is that literal's own plan target with an unobservable
    // ReturnHome (see CanonicalToBytecodeLowerer#emitInlineClosureLiteralCall).

    @Operation
    public static final class PrepareInlineClosureLiteralCall {
        @Specialization
        public static PreparedInlineLiteralCall perform(
                Object receiver,
                ProtosActivation caller,
                @Variadic Object[] supplied) {
            return ProtosBytecodeRootNode.prepareInlineClosureLiteralCall(
                    receiver, caller, supplied);
        }
    }

    @Operation
    public static final class AdmitsInlineClosureLiteralCall {
        @Specialization
        public static boolean perform(
                PreparedInlineLiteralCall child,
                ProtosClosureExecutionPlanCell literalPlan) {
            return child.admits(literalPlan);
        }
    }

    // ---- PLAT044 B′ inline literal whileTrue (PERF026-C1) ---------------------------
    //
    // A selected standard whileTrue whose receiver and body are exactly the
    // send site's staged literals is sequenced locally in this root; every
    // other while keeps the structured-dispatch root. The loop state machine
    // and Boolean condition authority stay owned by
    // ProtosBytecodeRootNode.PreparedWhileCall.

    @Operation
    public static final class AdmitsInlineLiteralWhile {
        @Specialization
        public static boolean ordinary(
                OrdinarySourceCall prepared,
                Object condition,
                Object body) {
            return prepared.admitsInlineLiteralWhile(condition, body);
        }

        @Specialization
        public static boolean nativeCall(
                NativeCall prepared,
                Object condition,
                Object body) {
            return prepared.admitsInlineLiteralWhile(condition, body);
        }

        @Specialization
        public static boolean immediate(
                ImmediateResultCall prepared,
                Object condition,
                Object body) {
            return prepared.admitsInlineLiteralWhile(condition, body);
        }

        @Specialization
        public static boolean moduleInitialization(
                ModuleInitializationCall prepared,
                Object condition,
                Object body) {
            return prepared.admitsInlineLiteralWhile(condition, body);
        }
    }

    @Operation
    public static final class PrepareStructuredWhileCall {
        @Specialization
        public static PreparedWhileCall ordinary(OrdinarySourceCall prepared) {
            return ProtosBytecodeRootNode.PrepareStructuredWhileCall.ordinary(prepared);
        }

        @Specialization
        public static PreparedWhileCall nativeCall(NativeCall prepared) {
            return ProtosBytecodeRootNode.PrepareStructuredWhileCall.nativeCall(prepared);
        }

        @Specialization
        public static PreparedWhileCall immediate(ImmediateResultCall prepared) {
            return ProtosBytecodeRootNode.PrepareStructuredWhileCall.immediate(prepared);
        }

        @Specialization
        public static PreparedWhileCall moduleInitialization(ModuleInitializationCall prepared) {
            return ProtosBytecodeRootNode.PrepareStructuredWhileCall.moduleInitialization(prepared);
        }
    }

    @Operation
    public static final class PrepareStructuredWhileConditionCall {
        @Specialization
        public static PreparedClosureCall perform(PreparedWhileCall prepared) {
            return ProtosBytecodeRootNode.PrepareStructuredWhileConditionCall.perform(prepared);
        }
    }

    @Operation
    public static final class PrepareStructuredWhileBodyCall {
        @Specialization
        public static PreparedClosureCall perform(PreparedWhileCall prepared) {
            return ProtosBytecodeRootNode.PrepareStructuredWhileBodyCall.perform(prepared);
        }
    }

    @Operation
    public static final class PrepareInlineStructuredWhileConditionCall {
        @Specialization
        public static PreparedInlineLiteralCall perform(PreparedWhileCall prepared) {
            return prepared.prepareInlineCondition();
        }
    }

    @Operation
    public static final class PrepareInlineStructuredWhileBodyCall {
        @Specialization
        public static PreparedInlineLiteralCall perform(PreparedWhileCall prepared) {
            return prepared.prepareInlineBody();
        }
    }

    @Operation
    public static final class StructuredWhileCondition {
        @Specialization
        public static boolean perform(PreparedWhileCall prepared, Object result) {
            return ProtosBytecodeRootNode.StructuredWhileCondition.perform(prepared, result);
        }
    }

    @Operation
    public static final class StructuredWhileRetainBodyResult {
        @Specialization
        public static Object perform(PreparedWhileCall prepared, Object bodyResult) {
            return ProtosBytecodeRootNode.StructuredWhileRetainBodyResult.perform(
                    prepared, bodyResult);
        }
    }

    @Operation
    public static final class StructuredWhileResult {
        @Specialization
        public static Object perform(PreparedWhileCall prepared, Object lastBodyResult) {
            return ProtosBytecodeRootNode.StructuredWhileResult.perform(prepared, lastBodyResult);
        }
    }

    @Operation
    public static final class AdmitsInlineLiteralWhileCondition {
        @Specialization
        public static boolean perform(
                PreparedWhileCall prepared,
                PreparedInlineLiteralCall child,
                Object literal,
                ProtosClosureExecutionPlanCell literalPlan) {
            return prepared.admitsInlineLiteralCondition(child, literal, literalPlan);
        }
    }

    @Operation
    public static final class AdmitsInlineLiteralWhileBody {
        @Specialization
        public static boolean perform(
                PreparedWhileCall prepared,
                PreparedInlineLiteralCall child,
                Object literal,
                ProtosClosureExecutionPlanCell literalPlan) {
            return prepared.admitsInlineLiteralBody(child, literal, literalPlan);
        }
    }

    // ---- PLAT044 B′ inline literal standard each (PERF026-D1/D2/D3) ------------------
    //
    // A selected standard Array.each or Bytes.each whose sole argument is
    // exactly the send site's staged one-parameter literal, or a selected
    // standard Map.each, IdentityMap.each or Environment.each whose sole
    // argument is exactly the staged two-parameter literal, is sequenced
    // locally in this root; every other each keeps the structured-dispatch
    // root. Validation, the snapshot, the cursor and every per-position
    // activation stay owned by the prepared each calls of
    // ProtosBytecodeRootNode.

    @Operation
    public static final class AdmitsInlineLiteralIndexedEach {
        @Specialization
        public static boolean ordinary(OrdinarySourceCall prepared, Object callback) {
            return prepared.admitsInlineLiteralIndexedEach(callback);
        }

        @Specialization
        public static boolean nativeCall(NativeCall prepared, Object callback) {
            return prepared.admitsInlineLiteralIndexedEach(callback);
        }

        @Specialization
        public static boolean immediate(ImmediateResultCall prepared, Object callback) {
            return prepared.admitsInlineLiteralIndexedEach(callback);
        }

        @Specialization
        public static boolean moduleInitialization(
                ModuleInitializationCall prepared,
                Object callback) {
            return prepared.admitsInlineLiteralIndexedEach(callback);
        }
    }

    /**
     * Reached only after {@link AdmitsInlineLiteralIndexedEach}, so exactly
     * one of the two standard capabilities was selected; the ordinary
     * structured preparation of that capability runs unchanged.
     */
    @Operation
    public static final class PrepareStructuredIndexedEachCall {
        @Specialization
        public static PreparedIndexedEachCall ordinary(OrdinarySourceCall prepared) {
            return prepared.isStructuredArrayEach()
                    ? ProtosBytecodeRootNode.PrepareStructuredArrayEachCall.ordinary(prepared)
                    : ProtosBytecodeRootNode.PrepareStructuredBytesEachCall.ordinary(prepared);
        }

        @Specialization
        public static PreparedIndexedEachCall nativeCall(NativeCall prepared) {
            return prepared.isStructuredArrayEach()
                    ? ProtosBytecodeRootNode.PrepareStructuredArrayEachCall.nativeCall(prepared)
                    : ProtosBytecodeRootNode.PrepareStructuredBytesEachCall.nativeCall(prepared);
        }

        @Specialization
        public static PreparedIndexedEachCall immediate(ImmediateResultCall prepared) {
            return prepared.isStructuredArrayEach()
                    ? ProtosBytecodeRootNode.PrepareStructuredArrayEachCall.immediate(prepared)
                    : ProtosBytecodeRootNode.PrepareStructuredBytesEachCall.immediate(prepared);
        }

        @Specialization
        public static PreparedIndexedEachCall moduleInitialization(
                ModuleInitializationCall prepared) {
            return prepared.isStructuredArrayEach()
                    ? ProtosBytecodeRootNode.PrepareStructuredArrayEachCall
                            .moduleInitialization(prepared)
                    : ProtosBytecodeRootNode.PrepareStructuredBytesEachCall
                            .moduleInitialization(prepared);
        }
    }

    @Operation
    public static final class AdmitsInlineLiteralTwoParameterEach {
        @Specialization
        public static boolean ordinary(OrdinarySourceCall prepared, Object callback) {
            return prepared.admitsInlineLiteralAssociationEach(callback)
                    || prepared.admitsInlineLiteralEnvironmentEach(callback);
        }

        @Specialization
        public static boolean nativeCall(NativeCall prepared, Object callback) {
            return prepared.admitsInlineLiteralAssociationEach(callback)
                    || prepared.admitsInlineLiteralEnvironmentEach(callback);
        }

        @Specialization
        public static boolean immediate(ImmediateResultCall prepared, Object callback) {
            return prepared.admitsInlineLiteralAssociationEach(callback)
                    || prepared.admitsInlineLiteralEnvironmentEach(callback);
        }

        @Specialization
        public static boolean moduleInitialization(
                ModuleInitializationCall prepared,
                Object callback) {
            return prepared.admitsInlineLiteralAssociationEach(callback)
                    || prepared.admitsInlineLiteralEnvironmentEach(callback);
        }
    }

    /**
     * Reached only after {@link AdmitsInlineLiteralTwoParameterEach}, so
     * exactly one of the three standard capabilities was selected; the
     * ordinary structured preparation of that capability runs unchanged. For
     * Environment.each that preparation still validates callability, then
     * converts, validates and canonically orders the complete portable
     * snapshot before any callback child exists.
     */
    @Operation
    public static final class PrepareStructuredTwoParameterEachCall {
        @Specialization
        public static PreparedLocalEachCall ordinary(OrdinarySourceCall prepared) {
            if (prepared.isStructuredEnvironmentEach()) {
                return ProtosBytecodeRootNode.PrepareStructuredEnvironmentEachCall
                        .ordinary(prepared);
            }
            return prepared.isStructuredMapEach()
                    ? ProtosBytecodeRootNode.PrepareStructuredMapEachCall.ordinary(prepared)
                    : ProtosBytecodeRootNode.PrepareStructuredIdentityMapEachCall
                            .ordinary(prepared);
        }

        @Specialization
        public static PreparedLocalEachCall nativeCall(NativeCall prepared) {
            if (prepared.isStructuredEnvironmentEach()) {
                return ProtosBytecodeRootNode.PrepareStructuredEnvironmentEachCall
                        .nativeCall(prepared);
            }
            return prepared.isStructuredMapEach()
                    ? ProtosBytecodeRootNode.PrepareStructuredMapEachCall.nativeCall(prepared)
                    : ProtosBytecodeRootNode.PrepareStructuredIdentityMapEachCall
                            .nativeCall(prepared);
        }

        @Specialization
        public static PreparedLocalEachCall immediate(ImmediateResultCall prepared) {
            if (prepared.isStructuredEnvironmentEach()) {
                return ProtosBytecodeRootNode.PrepareStructuredEnvironmentEachCall
                        .immediate(prepared);
            }
            return prepared.isStructuredMapEach()
                    ? ProtosBytecodeRootNode.PrepareStructuredMapEachCall.immediate(prepared)
                    : ProtosBytecodeRootNode.PrepareStructuredIdentityMapEachCall
                            .immediate(prepared);
        }

        @Specialization
        public static PreparedLocalEachCall moduleInitialization(
                ModuleInitializationCall prepared) {
            if (prepared.isStructuredEnvironmentEach()) {
                return ProtosBytecodeRootNode.PrepareStructuredEnvironmentEachCall
                        .moduleInitialization(prepared);
            }
            return prepared.isStructuredMapEach()
                    ? ProtosBytecodeRootNode.PrepareStructuredMapEachCall
                            .moduleInitialization(prepared)
                    : ProtosBytecodeRootNode.PrepareStructuredIdentityMapEachCall
                            .moduleInitialization(prepared);
        }
    }

    @Operation
    public static final class StructuredLocalEachHasNext {
        @Specialization
        public static boolean perform(PreparedLocalEachCall prepared) {
            return prepared.hasNext();
        }
    }

    @Operation
    public static final class PrepareStructuredLocalEachChildCall {
        @Specialization
        public static PreparedClosureCall perform(PreparedLocalEachCall prepared) {
            return prepared.prepareCurrent();
        }
    }

    @Operation
    public static final class PrepareInlineStructuredLocalEachChildCall {
        @Specialization
        public static PreparedInlineLiteralCall perform(PreparedLocalEachCall prepared) {
            return prepared.prepareInlineCurrent();
        }
    }

    @Operation
    public static final class AdvanceStructuredLocalEach {
        @Specialization
        public static void perform(PreparedLocalEachCall prepared) {
            prepared.advance();
        }
    }

    @Operation
    public static final class FinishStructuredLocalEach {
        @Specialization
        public static Object perform(PreparedLocalEachCall prepared) {
            return prepared.finish();
        }
    }

    @Operation
    public static final class AdmitsInlineLiteralLocalEachChild {
        @Specialization
        public static boolean perform(
                PreparedLocalEachCall prepared,
                PreparedInlineLiteralCall child,
                Object literal,
                ProtosClosureExecutionPlanCell literalPlan) {
            return prepared.admitsInlineLiteralChild(child, literal, literalPlan);
        }
    }

    /**
     * The callback's fresh semantic activation, materialized from the region's
     * carrier by the first operation that needs it and shared, by identity,
     * with every later operation and tooling query of the same invocation.
     */
    @Operation
    public static final class LoadInlineCallbackActivation {
        @Specialization
        public static ProtosActivation perform(PreparedInlineLiteralCall child) {
            return child.activation();
        }
    }

    @Operation
    public static final class LoadInlineLiteralFallbackCall {
        @Specialization
        public static PreparedClosureCall perform(PreparedInlineLiteralCall child) {
            return child.fallbackCall();
        }
    }

    @Operation
    public static final class CompleteClosureCall {
        /*
         * PERF038-H: completion has no observable effect when this call
         * owns no materialized ReturnHome. Avoid entering the general
         * completion machinery on the pay-as-you-grow path.
         */
        @Specialization(guards = "!prepared.hasReturnHomeLifecycle()")
        public static void ordinaryWithoutHomeLifecycle(
                OrdinarySourceCall prepared) {
            // No owned materialized ReturnHome to complete.
        }

        @Specialization(guards = "prepared.hasReturnHomeLifecycle()")
        public static void ordinary(OrdinarySourceCall prepared) {
            ProtosBytecodeRootNode.CompleteClosureCall.ordinary(prepared);
        }


        @Specialization
        public static void nativeCall(NativeCall prepared) {
            ProtosBytecodeRootNode.CompleteClosureCall.nativeCall(prepared);
        }

        @Specialization
        public static void immediate(ImmediateResultCall prepared) {
            ProtosBytecodeRootNode.CompleteClosureCall.immediate(prepared);
        }

        @Specialization
        public static void moduleInitialization(ModuleInitializationCall prepared) {
            ProtosBytecodeRootNode.CompleteClosureCall.moduleInitialization(prepared);
        }
    }

    /**
     * PLAT042 B′ structured-dispatch boundary: one helper CallTarget per
     * structured invocation. The Task C-prime entry plan is cached per entered
     * Context, so its target is stable for a Context; a call site seen by
     * several Contexts falls back to an indirect call. Control-transfer,
     * module-initialization and runtime-failure mapping is the same as for an
     * ordinary prepared call. TEST009-J: emitted only inside
     * {@link RequiresStructuredDispatch}, so the operand is always a
     * structured {@link NativeCall}.
     */
    @Operation
    public static final class EnterNestedStructuredDispatch {
        @Specialization(guards = "structuredDispatchTarget() == cachedTarget", excludeForUncached = true)
        public static Object direct(
                NativeCall prepared,
                @Cached(value = "structuredDispatchTarget()", neverDefault = true) RootCallTarget cachedTarget,
                @Cached("create(cachedTarget)") DirectCallNode node) {
            try {
                return node.call(prepared.activation(), prepared);
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
                NativeCall prepared,
                @Cached IndirectCallNode node) {
            return ProtosBytecodeRootNode.EnterNestedStructuredDispatch.perform(prepared, node);
        }

        @NonIdempotent
        static RootCallTarget structuredDispatchTarget() {
            return ProtosTaskCPrimeEntryExecution.planForEnteredContext().target();
        }
    }

    /**
     * PERF038-H: straight-line execution is permitted only for the
     * selected source entry, not by selector spelling or receiver type.
     *
     * The proof excludes suspension and an owned materialized
     * ReturnHome. Unknown and native calls preserve the full path.
     */
    @Operation
    public static final class AdmitsStraightLineSourceCall {
        @Specialization
        public static boolean perform(OrdinarySourceCall prepared) {
            return prepared.provablyNonSuspending()
                    && !prepared.hasReturnHomeLifecycle();
        }

        @Specialization
        public static boolean nativeCall(NativeCall prepared) {
            return false;
        }

        @Specialization
        public static boolean immediate(ImmediateResultCall prepared) {
            return false;
        }

        @Specialization
        public static boolean moduleInitialization(
                ModuleInitializationCall prepared) {
            return false;
        }
    }

    /**
     * PERF038-H: direct execution of an admitted straight-line source
     * call. The enclosing invocation's existing TryFinally remains
     * authoritative for Error, cancellation and cleanup paths.
     *
     * These entry points reuse the original EnterClosureCall exception
     * bridge. No second invocation or semantic dispatch is introduced.
     */
    @Operation
    public static final class EnterStraightLineSourceCall {
        @Specialization(
                guards = "prepared.bodyTarget() == cachedTarget",
                limit = "3")
        public static Object ordinaryDirect(
                OrdinarySourceCall prepared,
                @Cached("prepared.bodyTarget()")
                        RootCallTarget cachedTarget,
                @Cached("create(cachedTarget)")
                        DirectCallNode node) {
            return ProtosNumericValueSupport.guestValue(
                    ProtosBytecodeRootNode.EnterClosureCall.ordinaryDirect(
                            prepared, cachedTarget, node));
        }

        @Specialization(replaces = "ordinaryDirect")
        public static Object ordinaryIndirect(
                OrdinarySourceCall prepared,
                @Cached IndirectCallNode node) {
            return ProtosNumericValueSupport.guestValue(
                    ProtosBytecodeRootNode.EnterClosureCall.ordinaryIndirect(
                            prepared, node));
        }
    }

    /**
     * PERF038-H: source-backed Send1 fast entry.
     *
     * Both argument evaluation and the D013 member-selection guards
     * remain authoritative. The selected Closure must have a
     * statically proven non-suspending source body, an entry matching
     * the supplied arity, and no physical ReturnHome lifecycle.
     *
     * Every other selection returns MISS without invoking the guest.
     * The lowerer then runs the original prepared-call path using
     * the already evaluated operands.
     */
    /**
     * PERF038-H: fused zero-argument ordinary source-method send.
     *
     * Lookup identity, selected method home, entered Context,
     * prelude and invalidation are guarded exactly as in Send1.
     * The selected source root must be proven non-suspending and
     * must require no physical ReturnHome completion.
     */
    @Operation
    public static final class TryDirectSendZero {
        @Specialization(
                guards = {
                    "!isForeignReceiver(receiver)",
                    "receiver == cachedReceiver",
                    "selector.equals(cachedSelector)",
                    "enteredContext != null",
                    "enteredContext == cachedContext",
                    "prelude == cachedPrelude",
                    "cachedSend != null",
                    "admitted(cachedSend)"
                },
                assumptions = "cachedSend.stability()",
                limit = "3")
        public static Object guardedSource(
                Object receiver,
                String selector,
                Object caller,
                @Bind("currentEnteredContext($node)")
                        ProtosLanguageContext enteredContext,
                @Bind("callerPrelude(caller)")
                        ProtosPrelude prelude,
                @Cached("receiver") Object cachedReceiver,
                @Cached("selector") String cachedSelector,
                @Cached("enteredContext")
                        ProtosLanguageContext cachedContext,
                @Cached("prelude") ProtosPrelude cachedPrelude,
                @Cached("createGuardedSendForPrelude(receiver, selector, prelude, enteredContext)")
                        GuardedSendTarget cachedSend,
                @Cached("create(cachedSend.target())")
                        DirectCallNode node) {
            ProtosActivation exactCaller =
                    PrepareSendArguments.exactCaller(caller);

            Object[] frameArguments =
                    ProtosFrameArguments
                            .compactImmediateMethodCallZeroNoHome(
                                    cachedSend.closure(),
                                    receiver,
                                    cachedSend.methodHome(),
                                    exactCaller);

            try {
                return ProtosNumericValueSupport.guestValue(
                        node.call(frameArguments));
            } catch (ProtosBytecodeControlTransferException bridged) {
                /*
                 * The admission excludes ownership of a materialized
                 * ReturnHome, so no transfer can be consumed here.
                 */
                throw bridged.transfer();
            }
        }

        @Specialization(replaces = "guardedSource")
        public static Object generic(
                Object receiver,
                String selector,
                Object caller) {
            return TryDirectSendOne.MISS;
        }

        static boolean admitted(GuardedSendTarget send) {
            return TryDirectSendOne.admittedForArity(send, 0);
        }

        static GuardedSendTarget createGuardedSendForPrelude(
                Object receiver,
                String selector,
                ProtosPrelude prelude,
                ProtosLanguageContext enteredContext) {
            return TryDirectSendOne.createGuardedSendForPrelude(
                    receiver, selector, prelude, enteredContext);
        }

        static ProtosPrelude callerPrelude(Object caller) {
            return TryDirectSendOne.callerPrelude(caller);
        }

        static boolean isForeignReceiver(Object receiver) {
            return TryDirectSendOne.isForeignReceiver(receiver);
        }

        @NonIdempotent
        static ProtosLanguageContext currentEnteredContext(Node node) {
            return ProtosLanguageContext.current(node);
        }
    }


    /** I091-C2: an internal Long must never be observed as a guest Number. */
    @Operation
    public static final class MaterializePrimitiveNumericCarrier {
        @Specialization
        public static Object perform(Object value, boolean carrierPermitted) {
            return carrierPermitted
                    ? ProtosNumericValueSupport.guestValue(value)
                    : value;
        }
    }

    /**
     * I091: the implicit final value of a source root. A primitive numeric
     * carrier is returned as is only to a fused direct source send that
     * accepts it; every other caller receives the guest value.
     */
    @Operation
    public static final class MaterializeReturnCarrier {
        @Specialization
        public static Object perform(Object value, @Bind VirtualFrame frame) {
            if (ProtosNumericValueSupport.isPrimitiveCarrier(value)
                    && ProtosFrameArguments.acceptsPrimitiveResult(frame.getArguments())) {
                return value;
            }
            return ProtosNumericValueSupport.guestValue(value);
        }
    }

    @Operation
    public static final class IsPrimitiveNumericSendMiss {
        @Specialization
        public static boolean perform(Object value) {
            return value == TryPrimitiveNumericSendOne.MISS;
        }
    }

    /**
     * Exact canonical Integer dispatch, selected using the same protected D013
     * lookup as PrepareSendOne. The exemplar is valid for all represented
     * Integer values sharing a Prelude: their immutable represented receiver
     * step has no own slots. Never dispatch a guest send on a Java Long.
     */
    @Operation
    public static final class TryPrimitiveNumericSendOne {
        private static final Object MISS = new Object();

        public record FoldedIntegerArithmetic(
                ProtosStandardIntegerProtocol.CanonicalIntegerOperation operation,
                long left, long right, long result) {}

        @Specialization(
                guards = {
                    "smallInteger(receiver, receiverCarrier)",
                    "smallInteger(argument, argumentCarrier)",
                    "selector.equals(cachedSelector)",
                    "enteredContext != null",
                    "enteredContext == cachedContext",
                    "prelude == cachedPrelude",
                    "cachedInteger != null",
                    "mixedArithmetic(cachedInteger.operation())"
                },
                assumptions = "cachedInteger.stability()",
                limit = "3")
        public static Object canonical(
                Object receiver,
                String selector,
                Object caller,
                Object argument,
                boolean receiverCarrier,
                boolean argumentCarrier, Object foldedIntegerAddition,
                Object staticFoldResult,
                @Bind("currentEnteredContext($node)")
                        ProtosLanguageContext enteredContext,
                @Bind("callerPrelude(caller)") ProtosPrelude prelude,
                @Cached("selector") String cachedSelector,
                @Cached("enteredContext") ProtosLanguageContext cachedContext,
                @Cached("prelude") ProtosPrelude cachedPrelude,
                @Cached("canonicalGuard(selector, prelude, enteredContext)")
                        GuardedIntegerSend cachedInteger,
                @Cached(inline = false)
                    ProtosPrimitiveNumericSendFastPathsBase.IntegerArithmeticNode arithmetic) {
            long left = smallValue(receiver);
            long right = smallValue(argument);

            /*
             * The existing canonical lookup, Context, Prelude and Assumption
             * guards have already admitted this specialization. Check the
             * actual operands as well: guest locals are mutable.
             */
            if (foldedIntegerAddition instanceof FoldedIntegerArithmetic fold
                    && cachedInteger.operation() == fold.operation()
                    && left == fold.left()
                    && right == fold.right()
                    && staticFoldResult instanceof Long constant
                    && constant.longValue() == fold.result()) {
                return constant;
            }

            try {
                return switch (cachedInteger.operation()) {
                    case ADD, SUBTRACT, MULTIPLY ->
                            arithmetic.execute(cachedInteger.operation(), left, right, prelude);
                    // D197-C: Integer / Integer is the canonical exact quotient. Only an
                    // integral signed-64 quotient stays primitive; a zero divisor, the
                    // Long.MIN_VALUE / -1 promotion and every Fraction belong to the
                    // selected native fallback.
                    case EXACT_DIVIDE ->
                            right != 0L
                                            && (left != Long.MIN_VALUE || right != -1L)
                                            && left % right == 0L
                                    ? (Object) (left / right)
                                    : MISS;
                    default -> throw new AssertionError("non-arithmetic canonical operation");
                };
            } catch (ArithmeticException overflow) {
                // Exact BigInteger arithmetic belongs to the selected native fallback.
                return MISS;
            }
        }

        @Specialization(
                guards = {
                    "smallInteger(receiver, receiverCarrier)",
                    "smallFloat(argument, argumentCarrier)",
                    "selector.equals(cachedSelector)",
                    "enteredContext != null",
                    "enteredContext == cachedContext",
                    "prelude == cachedPrelude",
                    "cachedInteger != null",
                    "mixedArithmetic(cachedInteger.operation())"
                },
                assumptions = "cachedInteger.stability()",
                limit = "3")
        public static Object canonicalMixedInteger(
                Object receiver, String selector, Object caller, Object argument,
                boolean receiverCarrier, boolean argumentCarrier, Object foldedIntegerAddition,
                Object staticFoldResult,
                @Bind("currentEnteredContext($node)")
                        ProtosLanguageContext enteredContext,
                @Bind("callerPrelude(caller)") ProtosPrelude prelude,
                @Cached("selector") String cachedSelector,
                @Cached("enteredContext") ProtosLanguageContext cachedContext,
                @Cached("prelude") ProtosPrelude cachedPrelude,
                @Cached("canonicalGuard(selector, prelude, enteredContext)")
                        GuardedIntegerSend cachedInteger) {
            double left = (double) smallValue(receiver);
            double right = floatValue(argument);
            return switch (cachedInteger.operation()) {
                case ADD -> left + right;
                case SUBTRACT -> left - right;
                case MULTIPLY -> left * right;
                case EXACT_DIVIDE -> left / right;
                default -> throw new AssertionError(
                        "non-mixed canonical Integer operation");
            };
        }

        record GuardedFloatSend(
                ProtosStandardFloatProtocol.CanonicalFloatOperation operation,
                Assumption stability) {}

        @Specialization(
                guards = {
                    "smallFloat(receiver, receiverCarrier)",
                    "smallFloat(argument, argumentCarrier)",
                    "selector.equals(cachedSelector)",
                    "enteredContext != null",
                    "enteredContext == cachedContext",
                    "prelude == cachedPrelude",
                    "cachedFloat != null"
                },
                assumptions = "cachedFloat.stability()",
                limit = "3")
        public static double canonicalFloatDD(
                Object receiver, String selector, Object caller, Object argument,
                boolean receiverCarrier, boolean argumentCarrier, Object foldedIntegerAddition,
                Object staticFoldResult,
                @Bind("currentEnteredContext($node)")
                        ProtosLanguageContext enteredContext,
                @Bind("callerPrelude(caller)") ProtosPrelude prelude,
                @Cached("selector") String cachedSelector,
                @Cached("enteredContext") ProtosLanguageContext cachedContext,
                @Cached("prelude") ProtosPrelude cachedPrelude,
                @Cached("canonicalFloatGuard(selector, prelude, enteredContext)")
                        GuardedFloatSend cachedFloat) {
            double left = floatValue(receiver);
            double right = floatValue(argument);
            return switch (cachedFloat.operation()) {
                case ADD -> left + right;
                case SUBTRACT -> left - right;
                case MULTIPLY -> left * right;
                case DIVIDE -> left / right;
            };
        }

        @Specialization(
                guards = {
                    "smallFloat(receiver, receiverCarrier)",
                    "smallInteger(argument, argumentCarrier)",
                    "selector.equals(cachedSelector)",
                    "enteredContext != null",
                    "enteredContext == cachedContext",
                    "prelude == cachedPrelude",
                    "cachedFloat != null"
                },
                assumptions = "cachedFloat.stability()",
                limit = "3")
        public static double canonicalFloatDL(
                Object receiver, String selector, Object caller, Object argument,
                boolean receiverCarrier, boolean argumentCarrier, Object foldedIntegerAddition,
                Object staticFoldResult,
                @Bind("currentEnteredContext($node)")
                        ProtosLanguageContext enteredContext,
                @Bind("callerPrelude(caller)") ProtosPrelude prelude,
                @Cached("selector") String cachedSelector,
                @Cached("enteredContext") ProtosLanguageContext cachedContext,
                @Cached("prelude") ProtosPrelude cachedPrelude,
                @Cached("canonicalFloatGuard(selector, prelude, enteredContext)")
                        GuardedFloatSend cachedFloat) {
            double left = floatValue(receiver);
            double right = (double) smallValue(argument);
            return switch (cachedFloat.operation()) {
                case ADD -> left + right;
                case SUBTRACT -> left - right;
                case MULTIPLY -> left * right;
                case DIVIDE -> left / right;
            };
        }

        record GuardedOrderingSend(
                ProtosStandardNumberOrderingProtocol.Relation relation,
                Assumption stability) {}

        /*
         * Standard Number ordering selected through an Integer receiver.
         * Separate from the Float receiver family: each family's own
         * prototype chain may override the inherited relation (D013).
         */
        @Specialization(
                guards = {
                    "smallInteger(receiver, receiverCarrier)",
                    "smallNumeric(argument, argumentCarrier)",
                    "selector.equals(cachedSelector)",
                    "enteredContext != null",
                    "enteredContext == cachedContext",
                    "prelude == cachedPrelude",
                    "cachedOrdering != null"
                },
                assumptions = "cachedOrdering.stability()",
                limit = "3")
        public static Object canonicalIntegerOrdering(
                Object receiver, String selector, Object caller, Object argument,
                boolean receiverCarrier, boolean argumentCarrier, Object foldedIntegerAddition,
                Object staticFoldResult,
                @Bind("currentEnteredContext($node)")
                        ProtosLanguageContext enteredContext,
                @Bind("callerPrelude(caller)") ProtosPrelude prelude,
                @Cached("selector") String cachedSelector,
                @Cached("enteredContext") ProtosLanguageContext cachedContext,
                @Cached("prelude") ProtosPrelude cachedPrelude,
                @Cached("integerOrderingGuard(selector, prelude, enteredContext)")
                        GuardedOrderingSend cachedOrdering) {
            long left = smallValue(receiver);
            ProtosStandardNumberOrderingProtocol.Comparison comparison =
                    smallFloat(argument, argumentCarrier)
                            ? ProtosCurrentNumericRelations.reverse(
                                    ProtosCurrentNumericRelations.compareFloatToLong(
                                            floatValue(argument), left))
                            : ProtosCurrentNumericRelations.compareLongs(
                                    left, smallValue(argument));
            return ProtosBooleanValue.of(cachedOrdering.relation().holds(comparison));
        }

        @Specialization(
                guards = {
                    "smallFloat(receiver, receiverCarrier)",
                    "smallNumeric(argument, argumentCarrier)",
                    "selector.equals(cachedSelector)",
                    "enteredContext != null",
                    "enteredContext == cachedContext",
                    "prelude == cachedPrelude",
                    "cachedOrdering != null"
                },
                assumptions = "cachedOrdering.stability()",
                limit = "3")
        public static Object canonicalFloatOrdering(
                Object receiver, String selector, Object caller, Object argument,
                boolean receiverCarrier, boolean argumentCarrier, Object foldedIntegerAddition,
                Object staticFoldResult,
                @Bind("currentEnteredContext($node)")
                        ProtosLanguageContext enteredContext,
                @Bind("callerPrelude(caller)") ProtosPrelude prelude,
                @Cached("selector") String cachedSelector,
                @Cached("enteredContext") ProtosLanguageContext cachedContext,
                @Cached("prelude") ProtosPrelude cachedPrelude,
                @Cached("floatOrderingGuard(selector, prelude, enteredContext)")
                        GuardedOrderingSend cachedOrdering) {
            double left = floatValue(receiver);
            ProtosStandardNumberOrderingProtocol.Comparison comparison =
                    smallFloat(argument, argumentCarrier)
                            ? ProtosCurrentNumericRelations.compareFloats(
                                    left, floatValue(argument))
                            : ProtosCurrentNumericRelations.compareFloatToLong(
                                    left, smallValue(argument));
            return ProtosBooleanValue.of(cachedOrdering.relation().holds(comparison));
        }

        @Specialization(
                replaces = {"canonical", "canonicalMixedInteger", "canonicalFloatDD", "canonicalFloatDL",
                    "canonicalIntegerOrdering", "canonicalFloatOrdering"})
        public static Object generic(
                Object receiver, String selector, Object caller, Object argument,
                boolean receiverCarrier, boolean argumentCarrier, Object foldedIntegerAddition,
                Object staticFoldResult) {
            return MISS;
        }

        static boolean smallInteger(Object value, boolean carrierPermitted) {
            return (carrierPermitted && value instanceof Long)
                    || value instanceof ProtosIntegerValue;
        }

        static long smallValue(Object value) {
            if (value instanceof Long carrier) {
                return carrier.longValue();
            }
            return ((ProtosIntegerValue) value).longValue();
        }

        static boolean arithmetic(
                ProtosStandardIntegerProtocol.CanonicalIntegerOperation operation) {
            return operation == ProtosStandardIntegerProtocol.CanonicalIntegerOperation.ADD
                    || operation == ProtosStandardIntegerProtocol.CanonicalIntegerOperation.SUBTRACT
                    || operation == ProtosStandardIntegerProtocol.CanonicalIntegerOperation.MULTIPLY;
        }

        static boolean smallFloat(Object value, boolean carrierPermitted) {
            return (carrierPermitted && value instanceof Double)
                    || value instanceof ProtosFloatValue;
        }

        static boolean smallNumeric(Object value, boolean carrierPermitted) {
            return smallInteger(value, carrierPermitted)
                    || smallFloat(value, carrierPermitted);
        }

        static double floatValue(Object value) {
            if (value instanceof Double carrier) {
                return carrier.doubleValue();
            }
            return ((ProtosFloatValue) value).value();
        }

        static boolean mixedArithmetic(
                ProtosStandardIntegerProtocol.CanonicalIntegerOperation operation) {
            return arithmetic(operation)
                    || operation ==
                            ProtosStandardIntegerProtocol.CanonicalIntegerOperation.EXACT_DIVIDE;
        }

        static GuardedFloatSend canonicalFloatGuard(
                String selector,
                ProtosPrelude prelude,
                ProtosLanguageContext enteredContext) {
            if (enteredContext == null || prelude == null) {
                return null;
            }
            ProtosValueLookup.GuardedLookup lookup =
                    ProtosValueLookup.lookupGuardedFloat(
                            new ProtosFloatValue(0.0), selector, prelude);
            if (lookup == null) {
                return null;
            }
            ProtosSlotLookupResult selected = lookup.selected();
            if (!(selected.value() instanceof ProtosClosureValue closure)
                    || !lookup.stability().isValid()) {
                lookup.stability().invalidate();
                return null;
            }
            ProtosStandardFloatProtocol.CanonicalFloatOperation operation =
                    ProtosStandardFloatProtocol.canonicalOperationForSelection(
                            closure, selected.home(), selector, prelude);
            if (operation == null) {
                lookup.stability().invalidate();
                return null;
            }
            return new GuardedFloatSend(operation, lookup.stability());
        }

        static GuardedOrderingSend integerOrderingGuard(
                String selector,
                ProtosPrelude prelude,
                ProtosLanguageContext enteredContext) {
            if (enteredContext == null || prelude == null) {
                return null;
            }
            return orderingGuard(
                    ProtosValueLookup.lookupGuardedInteger(
                            new ProtosIntegerValue(0L), selector, prelude),
                    selector, prelude);
        }

        static GuardedOrderingSend floatOrderingGuard(
                String selector,
                ProtosPrelude prelude,
                ProtosLanguageContext enteredContext) {
            if (enteredContext == null || prelude == null) {
                return null;
            }
            return orderingGuard(
                    ProtosValueLookup.lookupGuardedFloat(
                            new ProtosFloatValue(0.0), selector, prelude),
                    selector, prelude);
        }

        private static GuardedOrderingSend orderingGuard(
                ProtosValueLookup.GuardedLookup lookup,
                String selector,
                ProtosPrelude prelude) {
            if (lookup == null) {
                return null;
            }
            ProtosSlotLookupResult selected = lookup.selected();
            if (!(selected.value() instanceof ProtosClosureValue closure)
                    || !lookup.stability().isValid()) {
                lookup.stability().invalidate();
                return null;
            }
            ProtosStandardNumberOrderingProtocol.Relation relation =
                    ProtosStandardNumberOrderingProtocol.canonicalRelationForSelection(
                            closure, selected.home(), selector, prelude);
            if (relation == null) {
                lookup.stability().invalidate();
                return null;
            }
            return new GuardedOrderingSend(relation, lookup.stability());
        }

        static GuardedIntegerSend canonicalGuard(
                String selector,
                ProtosPrelude prelude,
                ProtosLanguageContext enteredContext) {
            return ProtosBytecodeRootNode.PrepareSendArguments.createGuardedIntegerSend(
                    new ProtosIntegerValue(0L), selector, prelude, enteredContext);
        }

        static ProtosPrelude callerPrelude(Object caller) {
            return PrepareSendOne.callerPrelude(caller);
        }

        @NonIdempotent
        static ProtosLanguageContext currentEnteredContext(Node node) {
            return ProtosLanguageContext.current(node);
        }
    }

    @Operation
    public static final class TryDirectSendOne {
        private static final Object MISS = new Object();

        @Specialization(
                guards = {
                    "!isForeignReceiver(receiver)",
                    "receiver == cachedReceiver",
                    "selector.equals(cachedSelector)",
                    "enteredContext != null",
                    "enteredContext == cachedContext",
                    "prelude == cachedPrelude",
                    "cachedSend != null",
                    "admitted(cachedSend)"
                },
                assumptions = "cachedSend.stability()",
                limit = "3")
        public static Object guardedSource(
                Object receiver,
                String selector,
                Object caller,
                Object supplied0,
                @Bind("currentEnteredContext($node)")
                        ProtosLanguageContext enteredContext,
                @Bind("callerPrelude(caller)")
                        ProtosPrelude prelude,
                @Cached("receiver") Object cachedReceiver,
                @Cached("selector") String cachedSelector,
                @Cached("enteredContext")
                        ProtosLanguageContext cachedContext,
                @Cached("prelude") ProtosPrelude cachedPrelude,
                @Cached("createGuardedSendForPrelude(receiver, selector, prelude, enteredContext)")
                        GuardedSendTarget cachedSend,
                @Cached("create(cachedSend.target())")
                        DirectCallNode node) {
            ProtosActivation exactCaller =
                    PrepareSendArguments.exactCaller(caller);

            Object[] frameArguments =
                    ProtosFrameArguments.compactImmediateMethodCallOne(
                            cachedSend.closure(),
                            receiver,
                            cachedSend.methodHome(),
                            exactCaller,
                            /*
                             * I091: the admitted home is unobservable; this
                             * variant also lets the callee return a primitive
                             * numeric carrier, which the lowered call site
                             * consumes or materializes.
                             */
                            ProtosReturnHome.unobservableAcceptingPrimitiveResultForRuntime(),
                            supplied0);

            try {
                return node.call(frameArguments);
            } catch (ProtosBytecodeControlTransferException bridged) {
                /*
                 * Exactly the no-home OrdinarySourceCall bridge:
                 * there is no owned materialized home able to
                 * consume this control transfer.
                 */
                throw bridged.transfer();
            }
        }

        @Specialization(replaces = "guardedSource")
        public static Object generic(
                Object receiver,
                String selector,
                Object caller,
                Object supplied0) {
            return MISS;
        }

        static boolean admitted(GuardedSendTarget send) {
            return admittedForArity(send, 1);
        }

        static boolean admittedForArity(
                GuardedSendTarget send, int suppliedArgumentCount) {
            if (send == null
                    || send.freshInvocationHome()
                    || send.sharedInvocationHome()
                            != ProtosReturnHome.unobservable()) {
                return false;
            }

            RootCallTarget target = send.target();
            if (target == null
                    || !(target.getRootNode()
                            instanceof ProtosSemanticBytecodeRootNode root)
                    || !root.provablyNonSuspendingBody()) {
                return false;
            }

            /*
             * Admission must agree with the exact entry selected for
             * this supplied arity, including the rejection root.
             */
            return ProtosSemanticBytecodeRootNode.selectSourceEntryTarget(
                    target, suppliedArgumentCount) == target;
        }

        static GuardedSendTarget createGuardedSendForPrelude(
                Object receiver,
                String selector,
                ProtosPrelude prelude,
                ProtosLanguageContext enteredContext) {
            return ProtosBytecodeRootNode.PrepareSendArguments
                    .createGuardedSendForPrelude(
                            receiver, selector, prelude, enteredContext);
        }

        static ProtosPrelude callerPrelude(Object caller) {
            return PrepareSendArguments.callerPrelude(caller);
        }

        static boolean isForeignReceiver(Object receiver) {
            return ProtosBytecodeRootNode.PrepareSendArguments
                    .isForeignReceiver(receiver);
        }

        @NonIdempotent
        static ProtosLanguageContext currentEnteredContext(Node node) {
            return ProtosLanguageContext.current(node);
        }
    }

    /**
     * A failed fused attempt delegates to the original prepared
     * invocation. The marker cannot collide with a Protos result.
     */
    /**
     * PERF038-H: direct Call0 of a proven straight-line source
     * Closure, without constructing an OrdinarySourceCall carrier.
     */
    @Operation
    public static final class TryDirectCallZero {
        @Specialization(
                excludeForUncached = true,
                guards = {
                    "receiver == cachedReceiver",
                    "enteredContext != null",
                    "enteredContext == cachedContext",
                    "prelude == cachedPrelude",
                    "cachedGuarded != null",
                    "admitted(cachedGuarded, 0)"
                },
                assumptions = "cachedGuarded.stability()",
                limit = "3")
        public static Object guardedSource(
                Object receiver,
                Object caller,
                @Bind("currentEnteredContext($node)")
                        ProtosLanguageContext enteredContext,
                @Bind("callerPrelude(caller)")
                        ProtosPrelude prelude,
                @Cached("receiver") Object cachedReceiver,
                @Cached("enteredContext")
                        ProtosLanguageContext cachedContext,
                @Cached("prelude") ProtosPrelude cachedPrelude,
                @Cached("createGuardedDirectClosureCallForPrelude(receiver, prelude, enteredContext)")
                        GuardedDirectClosureCallTarget cachedGuarded,
                @Cached("create(cachedGuarded.target())")
                        DirectCallNode node) {
            ProtosActivation exactCaller =
                    PrepareSendArguments.exactCaller(caller);

            Object[] frameArguments =
                    ProtosFrameArguments
                            .compactDirectClosureCallZeroNoHome(
                                    cachedGuarded.closure(),
                                    exactCaller);

            try {
                return ProtosNumericValueSupport.guestValue(
                        node.call(frameArguments));
            } catch (ProtosBytecodeControlTransferException bridged) {
                throw bridged.transfer();
            }
        }

        /**
         * PERF042: second tier keyed by the stable {@link CanonicalClosure}
         * definition, the receiver's prepared plan template, its
         * Context-projection requirement and the entered Context: exactly the
         * inputs from which {@code fastOrdinarySendTarget} derives the
         * Context-owned target, so the cached target is the one the ordinary
         * path would select for the current instance. Only those keys, the
         * target and target-level admission are cached; the current Closure
         * is always the one entered. Per-instance facts (the ordinary
         * {@code call} selection and the unobservable ReturnHome) are
         * re-proved on every hit, and a rejected instance answers the miss
         * marker, so neither fresh instances nor one overridden instance
         * degrade the site.
         */
        @Specialization(
                excludeForUncached = true,
                guards = {
                    "closureDefinition != null",
                    "closureDefinition == cachedClosureDefinition",
                    "sourcePlan != null",
                    "sourcePlan == cachedSourcePlan",
                    "enteredContext != null",
                    "enteredContext == cachedContext",
                    "projectionRequired == cachedProjectionRequired",
                    "cachedTarget != null",
                    "cachedAdmitted"
                },
                limit = "3")
        public static Object definitionSource(
                Object receiver,
                Object caller,
                @Bind("sourceDefinitionOrNull(receiver)")
                        CanonicalClosure closureDefinition,
                @Bind("currentEnteredContext($node)")
                        ProtosLanguageContext enteredContext,
                @Bind("sourcePlanOrNull(receiver)")
                        ProtosClosureExecutionPlan sourcePlan,
                @Bind("requiresContextProjection(receiver)")
                        boolean projectionRequired,
                @Cached("closureDefinition")
                        CanonicalClosure cachedClosureDefinition,
                @Cached("enteredContext")
                        ProtosLanguageContext cachedContext,
                @Cached("sourcePlan")
                        ProtosClosureExecutionPlan cachedSourcePlan,
                @Cached("projectionRequired")
                        boolean cachedProjectionRequired,
                @Cached("definitionTarget(receiver, caller, enteredContext)")
                        RootCallTarget cachedTarget,
                @Cached("admittedTarget(cachedTarget, 0)")
                        boolean cachedAdmitted,
                @Cached("createDirectCallNodeOrNull(cachedTarget)")
                        DirectCallNode node) {
            ProtosClosureValue closure =
                    admittedInstanceOrNull(receiver, caller);
            if (closure == null) {
                return TryDirectSendOne.MISS;
            }
            ProtosActivation exactCaller =
                    PrepareSendArguments.exactCaller(caller);

            Object[] frameArguments =
                    ProtosFrameArguments
                            .compactDirectClosureCallZeroNoHome(
                                    closure,
                                    exactCaller);

            try {
                return ProtosNumericValueSupport.guestValue(
                        node.call(frameArguments));
            } catch (ProtosBytecodeControlTransferException bridged) {
                throw bridged.transfer();
            }
        }

        /**
         * PERF042: a source Closure that currently cannot take the fused path
         * (overridden {@code call}, observable ReturnHome, or a plan not yet
         * prepared) misses without activating {@link #generic}, so the
         * cached tiers keep admitting later instances.
         */
        @Specialization(
                guards = "temporarilyRejectedInstance(receiver, caller)")
        public static Object rejectedSource(Object receiver, Object caller) {
            return TryDirectSendOne.MISS;
        }

        @Specialization
        public static Object generic(Object receiver, Object caller) {
            return TryDirectSendOne.MISS;
        }

        static CanonicalClosure sourceDefinitionOrNull(Object receiver) {
            return receiver instanceof ProtosClosureValue closure
                            && closure.nativeBody().isEmpty()
                    ? closure.definition()
                    : null;
        }

        static ProtosClosureExecutionPlan sourcePlanOrNull(
                Object receiver) {
            return receiver instanceof ProtosClosureValue closure
                            && closure.nativeBody().isEmpty()
                    ? closure.executionPlan().orElse(null)
                    : null;
        }

        static boolean requiresContextProjection(Object receiver) {
            return receiver instanceof ProtosClosureValue closure
                    && closure.requiresContextLocalExecutionProjectionForRuntime();
        }

        static DirectCallNode createDirectCallNodeOrNull(
                RootCallTarget target) {
            return target == null ? null : DirectCallNode.create(target);
        }

        @NonIdempotent
        static boolean temporarilyRejectedInstance(
                Object receiver, Object caller) {
            return sourceDefinitionOrNull(receiver) != null
                    && admittedInstanceOrNull(receiver, caller) == null;
        }

        /**
         * Runs only while populating a definition-tier entry. The instance's
         * ordinary {@code call} selection and ReturnHome are proved, and its
         * plan must already be prepared, before any target is derived: a
         * deferred plan is never rematerialized here and an overridden
         * {@code call} never reaches plan or projection work.
         */
        static RootCallTarget definitionTarget(
                Object receiver,
                Object caller,
                ProtosLanguageContext enteredContext) {
            if (enteredContext == null
                    || !(receiver instanceof ProtosClosureValue closure)
                    || closure.executionPlan().isEmpty()
                    || admittedInstanceOrNull(receiver, caller) != closure) {
                return null;
            }
            return ProtosBytecodeRootNode.PrepareSendArguments
                    .fastOrdinarySendTarget(closure, enteredContext);
        }

        /**
         * Target-level half of {@link #admitted}: depends only on the
         * cached target, so it is proved once per cache entry.
         */
        static boolean admittedTarget(
                RootCallTarget target, int suppliedArgumentCount) {
            return target != null
                    && target.getRootNode()
                            instanceof ProtosSemanticBytecodeRootNode root
                    && root.provablyNonSuspendingBody()
                    && ProtosSemanticBytecodeRootNode
                                    .selectSourceEntryTarget(
                                            target, suppliedArgumentCount)
                            == target;
        }

        /**
         * Instance-level half of {@link #admitted}, re-proved on every
         * definition-tier hit: the receiver's current ordinary {@code call}
         * selection is the canonical standard one, and this exact Closure
         * proves the unobservable ReturnHome marker.
         */
        static ProtosClosureValue admittedInstanceOrNull(
                Object receiver, Object caller) {
            ProtosClosureValue closure =
                    ProtosBytecodeRootNode
                            .directClosureCallSelectionForPreludeOrNull(
                                    receiver, callerPrelude(caller));
            if (closure == null
                    || closure.invocationReturnHomeForRuntime()
                            != ProtosReturnHome.unobservable()) {
                return null;
            }
            return closure;
        }

        static boolean admitted(
                GuardedDirectClosureCallTarget guarded,
                int suppliedArgumentCount) {
            if (guarded == null
                    || !(guarded.target().getRootNode()
                            instanceof ProtosSemanticBytecodeRootNode root)
                    || !root.provablyNonSuspendingBody()
                    || ProtosSemanticBytecodeRootNode
                                    .selectSourceEntryTarget(
                                            guarded.target(),
                                            suppliedArgumentCount)
                            != guarded.target()) {
                return false;
            }

            /*
             * No physical ReturnHome is discarded. The exact Closure
             * must independently prove the unobservable marker.
             */
            return guarded.closure()
                            .invocationReturnHomeForRuntime()
                    == ProtosReturnHome.unobservable();
        }

        static GuardedDirectClosureCallTarget
                createGuardedDirectClosureCallForPrelude(
                        Object receiver,
                        ProtosPrelude prelude,
                        ProtosLanguageContext enteredContext) {
            return ProtosBytecodeRootNode
                    .createGuardedDirectClosureCallForPrelude(
                            receiver, prelude, enteredContext);
        }

        static ProtosPrelude callerPrelude(Object caller) {
            return PrepareSendArguments.callerPrelude(caller);
        }

        @NonIdempotent
        static ProtosLanguageContext currentEnteredContext(Node node) {
            return ProtosLanguageContext.current(node);
        }
    }

    /**
     * PERF038-H: scalar direct Call1 with the same canonical
     * selection and execution-capability proof as Call0.
     */
    @Operation
    public static final class TryDirectCallOne {
        @Specialization(
                excludeForUncached = true,
                guards = {
                    "receiver == cachedReceiver",
                    "enteredContext != null",
                    "enteredContext == cachedContext",
                    "prelude == cachedPrelude",
                    "cachedGuarded != null",
                    "admitted(cachedGuarded)"
                },
                assumptions = "cachedGuarded.stability()",
                limit = "3")
        public static Object guardedSource(
                Object receiver,
                Object caller,
                Object supplied0,
                @Bind("currentEnteredContext($node)")
                        ProtosLanguageContext enteredContext,
                @Bind("callerPrelude(caller)")
                        ProtosPrelude prelude,
                @Cached("receiver") Object cachedReceiver,
                @Cached("enteredContext")
                        ProtosLanguageContext cachedContext,
                @Cached("prelude") ProtosPrelude cachedPrelude,
                @Cached("createGuardedDirectClosureCallForPrelude(receiver, prelude, enteredContext)")
                        GuardedDirectClosureCallTarget cachedGuarded,
                @Cached("create(cachedGuarded.target())")
                        DirectCallNode node) {
            ProtosActivation exactCaller =
                    PrepareSendArguments.exactCaller(caller);

            Object[] frameArguments =
                    ProtosFrameArguments
                            .compactDirectClosureCallOneNoHome(
                                    cachedGuarded.closure(),
                                    exactCaller,
                                    supplied0);

            try {
                return ProtosNumericValueSupport.guestValue(
                        node.call(frameArguments));
            } catch (ProtosBytecodeControlTransferException bridged) {
                throw bridged.transfer();
            }
        }

        /** PERF042: the Call1 counterpart of {@link TryDirectCallZero#definitionSource}. */
        @Specialization(
                excludeForUncached = true,
                guards = {
                    "closureDefinition != null",
                    "closureDefinition == cachedClosureDefinition",
                    "sourcePlan != null",
                    "sourcePlan == cachedSourcePlan",
                    "enteredContext != null",
                    "enteredContext == cachedContext",
                    "projectionRequired == cachedProjectionRequired",
                    "cachedTarget != null",
                    "cachedAdmitted"
                },
                limit = "3")
        public static Object definitionSource(
                Object receiver,
                Object caller,
                Object supplied0,
                @Bind("sourceDefinitionOrNull(receiver)")
                        CanonicalClosure closureDefinition,
                @Bind("currentEnteredContext($node)")
                        ProtosLanguageContext enteredContext,
                @Bind("sourcePlanOrNull(receiver)")
                        ProtosClosureExecutionPlan sourcePlan,
                @Bind("requiresContextProjection(receiver)")
                        boolean projectionRequired,
                @Cached("closureDefinition")
                        CanonicalClosure cachedClosureDefinition,
                @Cached("enteredContext")
                        ProtosLanguageContext cachedContext,
                @Cached("sourcePlan")
                        ProtosClosureExecutionPlan cachedSourcePlan,
                @Cached("projectionRequired")
                        boolean cachedProjectionRequired,
                @Cached("definitionTarget(receiver, caller, enteredContext)")
                        RootCallTarget cachedTarget,
                @Cached("admittedTarget(cachedTarget, 1)")
                        boolean cachedAdmitted,
                @Cached("createDirectCallNodeOrNull(cachedTarget)")
                        DirectCallNode node) {
            ProtosClosureValue closure =
                    TryDirectCallZero.admittedInstanceOrNull(receiver, caller);
            if (closure == null) {
                return TryDirectSendOne.MISS;
            }
            ProtosActivation exactCaller =
                    PrepareSendArguments.exactCaller(caller);

            Object[] frameArguments =
                    ProtosFrameArguments
                            .compactDirectClosureCallOneNoHome(
                                    closure,
                                    exactCaller,
                                    supplied0);

            try {
                return ProtosNumericValueSupport.guestValue(
                        node.call(frameArguments));
            } catch (ProtosBytecodeControlTransferException bridged) {
                throw bridged.transfer();
            }
        }

        /** PERF042: see {@link TryDirectCallZero#rejectedSource}. */
        @Specialization(
                guards = "temporarilyRejectedInstance(receiver, caller)")
        public static Object rejectedSource(Object receiver, Object caller, Object supplied0) {
            return TryDirectSendOne.MISS;
        }

        @Specialization
        public static Object generic(
                Object receiver,
                Object caller,
                Object supplied0) {
            return TryDirectSendOne.MISS;
        }

        static CanonicalClosure sourceDefinitionOrNull(Object receiver) {
            return TryDirectCallZero.sourceDefinitionOrNull(receiver);
        }

        static ProtosClosureExecutionPlan sourcePlanOrNull(
                Object receiver) {
            return TryDirectCallZero.sourcePlanOrNull(receiver);
        }

        static boolean requiresContextProjection(Object receiver) {
            return TryDirectCallZero.requiresContextProjection(receiver);
        }

        static DirectCallNode createDirectCallNodeOrNull(
                RootCallTarget target) {
            return TryDirectCallZero.createDirectCallNodeOrNull(target);
        }

        @NonIdempotent
        static boolean temporarilyRejectedInstance(
                Object receiver, Object caller) {
            return TryDirectCallZero.temporarilyRejectedInstance(
                    receiver, caller);
        }

        static RootCallTarget definitionTarget(
                Object receiver,
                Object caller,
                ProtosLanguageContext enteredContext) {
            return TryDirectCallZero.definitionTarget(
                    receiver, caller, enteredContext);
        }

        static boolean admittedTarget(
                RootCallTarget target, int suppliedArgumentCount) {
            return TryDirectCallZero.admittedTarget(
                    target, suppliedArgumentCount);
        }

        static boolean admitted(
                GuardedDirectClosureCallTarget guarded) {
            return TryDirectCallZero.admitted(guarded, 1);
        }

        static GuardedDirectClosureCallTarget
                createGuardedDirectClosureCallForPrelude(
                        Object receiver,
                        ProtosPrelude prelude,
                        ProtosLanguageContext enteredContext) {
            return TryDirectCallZero
                    .createGuardedDirectClosureCallForPrelude(
                            receiver, prelude, enteredContext);
        }

        static ProtosPrelude callerPrelude(Object caller) {
            return TryDirectCallZero.callerPrelude(caller);
        }

        @NonIdempotent
        static ProtosLanguageContext currentEnteredContext(Node node) {
            return ProtosLanguageContext.current(node);
        }
    }

    @Operation
    public static final class IsDirectSendMiss {
        @Specialization
        public static boolean perform(Object result) {
            return result == TryDirectSendOne.MISS;
        }
    }

    @Operation
    public static final class EnterClosureCall {
        @Specialization
        public static Object immediate(ImmediateResultCall prepared) {
            return ProtosBytecodeRootNode.EnterClosureCall.immediate(prepared);
        }

        @Specialization
        public static Object nativeCall(NativeCall prepared) {
            return ProtosBytecodeRootNode.EnterClosureCall.nativeCall(prepared);
        }

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
            return ProtosBytecodeRootNode.EnterClosureCall.ordinaryDirectNoHome(
                    prepared, cachedTarget, node);
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
            return ProtosBytecodeRootNode.EnterClosureCall.ordinaryDirect(
                    prepared, cachedTarget, node);
        }

        @Specialization(replaces = {"ordinaryDirectNoHome", "ordinaryDirect"})
        public static Object ordinaryIndirect(
                OrdinarySourceCall prepared,
                @Shared("indirectCall") @Cached IndirectCallNode node) {
            return ProtosBytecodeRootNode.EnterClosureCall.ordinaryIndirect(prepared, node);
        }

        @Specialization(guards = "prepared.isImmediate()")
        public static Object moduleImmediate(ModuleInitializationCall prepared) {
            return ProtosBytecodeRootNode.EnterClosureCall.moduleImmediate(prepared);
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
            return ProtosBytecodeRootNode.EnterClosureCall.moduleDirect(
                    prepared, cachedTarget, node);
        }

        @Specialization(replaces = "moduleDirect", guards = "!prepared.isImmediate()")
        public static Object moduleIndirect(
                ModuleInitializationCall prepared,
                @Shared("indirectCall") @Cached IndirectCallNode node) {
            return ProtosBytecodeRootNode.EnterClosureCall.moduleIndirect(prepared, node);
        }
    }

    @Operation
    public static final class IsContinuation {
        /*
         * PERF038-F: an ordinary call site observes a small, stable set of
         * result classes, none of them a continuation. Profiling the exact
         * result class replaces the four suspension type tests of every
         * ordinary result with one class comparison; the answer is decided
         * once per class from the same type tests, so continuation results
         * keep their resumption path unchanged. A site seeing more classes
         * than the limit (and the uncached interpreter) takes the generic
         * form.
         */
        @Specialization(
                guards = {"value != null", "value.getClass() == cachedClass"},
                limit = "3")
        public static boolean classProfiled(
                Object value,
                @Cached("value.getClass()") Class<?> cachedClass,
                @Cached("isContinuationClass(cachedClass)") boolean continuation) {
            return continuation;
        }

        @Specialization(replaces = "classProfiled")
        public static boolean perform(Object value) {
            return ProtosBytecodeRootNode.IsContinuation.perform(value);
        }

        /** Exactly {@link ProtosBytecodeRootNode.IsContinuation#perform} for every instance of {@code type}. */
        static boolean isContinuationClass(Class<?> type) {
            return ContinuationResult.class.isAssignableFrom(type)
                    || ProtosNativeSuspension.class.isAssignableFrom(type)
                    || ProtosIoOperationSuspension.class.isAssignableFrom(type)
                    || ProtosIoReleaseSuspension.class.isAssignableFrom(type);
        }
    }

    /**
     * PERF038-H: suppress continuation classification only for a
     * source entry statically proven incapable of suspending.
     *
     * Every native, structured, dynamically uncertain or
     * polymorphic call retains the generic classification.
     */
    /**
     * PERF038-H: continuation composition specialized by the
     * concrete prepared-call representation (TEST009-J).
     *
     * A source entry proven incapable of suspension answers
     * false directly. Other ordinary source entries retain the
     * result-class PIC of PERF038-F.
     *
     * Native, immediate and module entries preserve the
     * complete continuation predicate.
     */
    @Operation
    public static final class IsContinuationForCall {
        @Specialization(guards = "prepared.provablyNonSuspending()")
        public static boolean provenNonSuspending(
                OrdinarySourceCall prepared,
                Object value) {
            return false;
        }

        @Specialization(
                guards = {
                    "!prepared.provablyNonSuspending()",
                    "value != null",
                    "value.getClass() == cachedClass"
                },
                limit = "3")
        public static boolean ordinaryClassProfiled(
                OrdinarySourceCall prepared,
                Object value,
                @Cached("value.getClass()")
                        Class<?> cachedClass,
                @Cached("isContinuationClass(cachedClass)")
                        boolean continuation) {
            return continuation;
        }

        @Specialization(
                replaces = {"provenNonSuspending", "ordinaryClassProfiled"})
        public static boolean ordinaryGeneric(
                OrdinarySourceCall prepared,
                Object value) {
            return IsContinuation.perform(value);
        }

        @Specialization
        public static boolean nativeCall(
                NativeCall prepared,
                Object value) {
            return IsContinuation.perform(value);
        }

        @Specialization
        public static boolean immediate(
                ImmediateResultCall prepared,
                Object value) {
            return IsContinuation.perform(value);
        }

        @Specialization
        public static boolean moduleInitialization(
                ModuleInitializationCall prepared,
                Object value) {
            return IsContinuation.perform(value);
        }

        static boolean isContinuationClass(Class<?> type) {
            return IsContinuation.isContinuationClass(type);
        }
    }

    @Operation
    public static final class ResumeContinuation {
        @Specialization
        public static Object nativeSuspension(
                PreparedClosureCall prepared,
                ProtosNativeSuspension suspension,
                Object resumeValue) {
            return ProtosBytecodeRootNode.ResumeContinuation.nativeSuspension(
                    prepared, suspension, resumeValue);
        }

        @Specialization
        public static Object ioOperationSuspension(
                PreparedClosureCall prepared,
                ProtosIoOperationSuspension suspension,
                Object resumeValue) {
            return ProtosBytecodeRootNode.ResumeContinuation.ioOperationSuspension(
                    prepared, suspension, resumeValue);
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
            return ProtosBytecodeRootNode.ResumeContinuation.direct(
                    prepared, result, resumeValue, cachedRoot, node);
        }

        @Specialization(replaces = "direct")
        public static Object indirect(
                PreparedClosureCall prepared,
                ContinuationResult result,
                Object resumeValue,
                @Cached IndirectCallNode node) {
            return ProtosBytecodeRootNode.ResumeContinuation.indirect(
                    prepared, result, resumeValue, node);
        }
    }

    @Operation
    public static final class FinishClosureCall {
        /*
         * PERF038-E: an ordinary source call that owns no materialized return
         * home (an unobservable owned home, or a captured home owned by an
         * outer invocation) has no completion effect, so a site that has only
         * finished such calls compiles neither the lifecycle test nor the
         * completion. The guard reads a final carrier field on every
         * execution; the lifecycle form keeps the unchanged completion.
         */
        @Specialization(guards = "!prepared.hasReturnHomeLifecycle()")
        public static Object ordinaryWithoutHomeLifecycle(
                OrdinarySourceCall prepared, Object result) {
            return result;
        }

        @Specialization(guards = "prepared.hasReturnHomeLifecycle()")
        public static Object ordinary(OrdinarySourceCall prepared, Object result) {
            return ProtosBytecodeRootNode.FinishClosureCall.ordinary(prepared, result);
        }

        @Specialization
        public static Object nativeCall(NativeCall prepared, Object result) {
            return ProtosBytecodeRootNode.FinishClosureCall.nativeCall(prepared, result);
        }

        @Specialization
        public static Object immediate(ImmediateResultCall prepared, Object result) {
            return ProtosBytecodeRootNode.FinishClosureCall.immediate(prepared, result);
        }

        @Specialization
        public static Object moduleInitialization(
                ModuleInitializationCall prepared,
                Object result) {
            return ProtosBytecodeRootNode.FinishClosureCall.moduleInitialization(
                    prepared, result);
        }
    }
}
