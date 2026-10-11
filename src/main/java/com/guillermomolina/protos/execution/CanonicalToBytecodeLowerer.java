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
import com.guillermomolina.protos.runtime.ProtosNullValue;
import com.guillermomolina.protos.runtime.ProtosObjectValue;
import com.guillermomolina.protos.runtime.ProtosNumberLiteral;
import com.guillermomolina.protos.runtime.ProtosNumericValueSupport;
import com.guillermomolina.protos.runtime.ProtosStringValue;
import com.guillermomolina.protos.semantic.ast.CanonicalAssign;
import com.guillermomolina.protos.semantic.ast.CanonicalCall;
import com.guillermomolina.protos.semantic.ast.CanonicalClosure;
import com.guillermomolina.protos.semantic.ast.CanonicalCompose;
import com.guillermomolina.protos.semantic.ast.CanonicalCreate;
import com.guillermomolina.protos.semantic.ast.CanonicalMultipleCreate;
import com.guillermomolina.protos.semantic.ast.CanonicalDerivedInequality;
import com.guillermomolina.protos.semantic.ast.CanonicalExpression;
import com.guillermomolina.protos.semantic.ast.CanonicalIdentity;
import com.guillermomolina.protos.semantic.ast.CanonicalIndexedAssign;
import com.guillermomolina.protos.semantic.ast.CanonicalIntrinsic;
import com.guillermomolina.protos.semantic.ast.CanonicalLiteral;
import com.guillermomolina.protos.semantic.ast.CanonicalLookup;
import com.guillermomolina.protos.semantic.ast.CanonicalMapConstruction;
import com.guillermomolina.protos.semantic.ast.CanonicalMember;
import com.guillermomolina.protos.semantic.ast.CanonicalNotIdentity;
import com.guillermomolina.protos.semantic.ast.CanonicalObject;
import com.guillermomolina.protos.semantic.ast.CanonicalParameter;
import com.guillermomolina.protos.semantic.ast.CanonicalReturn;
import com.guillermomolina.protos.semantic.ast.CanonicalSequence;
import com.guillermomolina.protos.semantic.ast.CanonicalSend;
import com.guillermomolina.protos.semantic.ast.CanonicalSpread;
import com.guillermomolina.protos.semantic.ast.CanonicalSuperSend;
import com.guillermomolina.protos.source.SourceSpan;
import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.bytecode.BytecodeConfig;
import com.oracle.truffle.api.bytecode.BytecodeLocal;
import com.oracle.truffle.api.bytecode.BytecodeRootNodes;
import com.oracle.truffle.api.bytecode.LocalRangeAccessor;
import com.oracle.truffle.api.instrumentation.StandardTags;
import com.oracle.truffle.api.source.Source;
import java.math.BigInteger;
import java.util.Objects;

/**
 * Parallel canonical-to-Bytecode lowering seam for the PERF006-B migration.
 *
 * <p>PERF006-B2D5C completes B2 invocation composition by supporting
 * caller-supplied spread in call/send default expressions as well as Closure
 * bodies. Body and default paths share the same backend-private supplied-vector
 * representation and the same immediate shallow Array snapshot rule at each
 * spread item's exact left-to-right position. Non-spread body/default fast paths
 * remain unchanged.
 * PERF006-B6A1 extends the same C-prime lowering with read-only member,
 * identity and execution-intrinsic expressions. PERF006-B6A2 adds canonical
 * slot creation/assignment and indexed assignment while preserving the AST
 * validation/evaluation order and composing ordinary atPut dispatch through
 * the same continuation backend. PERF006-B6A3A adds canonical super sends,
 * preserving physical method-home lookup, dynamic receiver identity, spread
 * argument semantics and C-prime suspension composition. PERF006-B6A3B adds
 * Closure literal materialization with exact lexical/receiver/method-home/
 * return-home/prelude capture and a pre-lowered Bytecode execution-plan
 * template per canonical Closure position. PERF006-B6A3C adds Object
 * literals and contextual composition using a pre-lowered child Bytecode
 * root per Object position plus a non-Closure construction carrier, so
 * construction activation/lexical-capture semantics and suspension are
 * preserved without manufacturing ReturnHome ownership.</p>
 *
 * <p>PLAT042 Candidate B′: every top-level, module and Closure activation root
 * produced here is a real tagged {@link ProtosSemanticBytecodeRootNode} (its
 * automatic RootTag is the guest semantic root identity) and lexically nested
 * Closure roots share that interpreter's {@code BytecodeRootNodes} group.
 * Structured prepared invocations are not lowered here; they enter the untagged
 * {@link ProtosStructuredDispatchLowerer} root once per invocation.</p>
 */
final class CanonicalToBytecodeLowerer {
    /**
     * Name of the Bytecode local holding the {@link
     * ProtosBytecodeRootNode.PreparedInlineLiteralCall} of a PLAT044 B′ inline
     * literal callback region: the carrier of that invocation's one fresh
     * semantic activation, materialized lazily by its first semantic or
     * tooling observer. Tooling reads it back by name ({@link
     * ProtosBytecodeTagTreeNodeExports}).
     */
    static final String INLINE_CALLBACK_CALL_LOCAL = "inlineCallbackCall";

    /**
     * Physical Bytecode-local prefix for statically admitted bindings owned by
     * a PLAT044 B-prime inline callback. Guest binding identity remains the
     * CanonicalLexicalScope/name identity; this name is implementation-only.
     */
    static final String INLINE_CALLBACK_BINDING_LOCAL_PREFIX =
            "$inlineCallbackBinding:";

    /**
     * A send site's PLAT044 B′ inline candidate: the literal's definition, its
     * supplied argument position, the argument local holding its materialized
     * value, and its own plan cell.
     */
    private record InlineLiteralCallback(
            CanonicalClosure definition,
            int position,
            BytecodeLocal literal,
            ProtosClosureExecutionPlanCell literalPlan) {}

    /**
     * A send site's PLAT044 B′ (PERF026-C1) inline whileTrue candidate pair:
     * the receiver literal staged as the condition and the sole supplied
     * literal staged as the body. The condition's {@code position} is unused.
     */
    private record InlineLiteralWhile(
            InlineLiteralCallback condition,
            InlineLiteralCallback body) {}

    /**
     * A send site's PLAT044 B′ inline standard each candidate: the sole
     * supplied literal and whether it is the two-parameter {@code
     * block(a, b)} shape (Map.each/IdentityMap.each, PERF026-D2;
     * Environment.each, PERF026-D3) rather than the PERF026-D1 one-parameter
     * indexed shape (Array.each/Bytes.each). The shape only selects which
     * standard capabilities may admit it.
     */
    private record InlineLiteralEach(
            InlineLiteralCallback callback,
            boolean twoParameter) {}

    private final ProtosLanguage language;
    private final Source source;

    /**
     * I068 Slice 5 backend-private whole-tree lexical analysis supplied by an
     * enclosing lowering unit. When present, nested Closure activation roots
     * reuse this analysis so captured binding owners/depths are not lost by
     * re-analyzing the Closure in isolation.
     */
    private final CanonicalBindingAnalysis inheritedBindingAnalysis;

    private final java.util.IdentityHashMap<CanonicalClosure, ProtosClosureExecutionPlanCell>
            bytecodeClosurePlans = new java.util.IdentityHashMap<>();

    /**
     * PERF013 Slice A: Closure roots nested (via {@link #lowerNestedClosureRoot})
     * in the group currently being built by the top-level {@link #lowerRoot}
     * call in progress. Their real {@link ProtosClosureExecutionPlan} cannot be
     * constructed until that group's {@code create()} call returns ({@link
     * com.oracle.truffle.api.nodes.RootNode#getCallTarget()} refuses while
     * parsing is still in progress), so construction is deferred and this list
     * is drained/frozen right after {@code create()} returns. Save/restored
     * around each {@link #lowerRoot} call so a reentrant {@link #lowerRoot}
     * never sees or pollutes this group's pending list.
     */
    private java.util.List<PendingGroupClosure> pendingGroupClosures;

    private record PendingGroupClosure(
            CanonicalClosure definition,
            CanonicalBindingAnalysis bindingAnalysis,
            ProtosSemanticBytecodeRootNode root,
            ProtosClosureExecutionPlanCell cell) {}

    /**
     * PERF032-G6: the arity-rejection roots lowered in the group currently
     * being built by the top-level {@link #lowerRoot} call, paired with the
     * Closure root each belongs to. As with {@link #pendingGroupClosures},
     * their targets are attached only once that group's {@code create()} has
     * returned; saved and restored around each {@link #lowerRoot} call.
     */
    private java.util.List<PendingArityRejection> pendingArityRejections;

    private record PendingArityRejection(
            ProtosSemanticBytecodeRootNode root,
            ProtosSemanticBytecodeRootNode rejectionRoot) {}

    private final java.util.IdentityHashMap<CanonicalCompose, java.util.List<String>>
            bytecodeComposeReservedNames = new java.util.IdentityHashMap<>();
    private final java.util.IdentityHashMap<CanonicalClosure, CanonicalBindingAnalysis>
            bindingAnalysisByClosure = new java.util.IdentityHashMap<>();
    /*
     * I091: exact host descriptors of the number literals of this lowering, parsed once per
     * literal node however many lowering decisions consult it.
     */
    private final java.util.IdentityHashMap<CanonicalLiteral, Object> numberLiteralDescriptors =
            new java.util.IdentityHashMap<>();
    private CanonicalBindingAnalysis moduleBindingAnalysis;

    /**
     * PLAT036 Candidate D, Slice 3 fast-path context for whichever genuine
     * {@code ROOT}/{@code CLOSURE} Bytecode root is currently being lowered
     * (null/empty while lowering an inline object-construction body, which is
     * never eligible). Saved and restored around each {@link #lowerRootInto}
     * call and each inline object body ({@link
     * #emitInlineObjectConstruction}) so lowering either inside an enclosing
     * root's own body never corrupts the enclosing root's context.
     */
    private CanonicalBindingAnalysis currentRootAnalysis;
    private CanonicalLexicalScope currentRootTopScope;
    private java.util.Map<String, BytecodeLocal> currentRootFrameLocals =
            java.util.Map.of();
    private ProtosFrameLexicalLayout currentRootFrameLayout;

    /**
     * PERF025 frame-materialization slice: the frame-binding range and layout
     * of the genuine root currently being lowered when {@link
     * #requiresPersistentFrameAuthority} proved it needs no persistent frame
     * authority, else {@code null}. While non-null, that root's own statically
     * proven current bindings are established through the frame-native
     * operations instead of the named-slot authority path. Saved and restored
     * with the other per-root fast-path context.
     */
    private BytecodeLocal[] currentRootFrameNativeLocals;
    private ProtosFrameLexicalLayout currentRootFrameNativeLayout;

    /**
     * PERF025-H1: the frame-binding layout of the genuine root currently being
     * lowered when it installs its persistent frame authority at entry, else
     * {@code null}. While non-null, that root's statically proven parameters
     * are established at their layout ordinal ({@link
     * #indexedParameterOrdinal}). Saved and restored with the other per-root
     * fast-path context.
     */
    private ProtosFrameLexicalLayout currentRootIndexedParameterLayout;

    /*
     * PERF034-C: direct BytecodeLocal storage is admitted only for a
     * statically complete, effect-free, straight-line local body.
     * This is per-root lowering state, not runtime/global state.
     */
    private java.util.Set<String> currentRootScalarLocals =
            java.util.Set.of();

    /**
     * PLAT041 C′ lowering-time source of the current {@link
     * com.guillermomolina.protos.runtime.ProtosActivation} consumed by {@link
     * #emitCurrentActivation}: {@code null} selects the root's frame argument
     * 0; inside an inline object-construction body it is that body's own
     * construction-activation local. Reset to {@code null} for every new root
     * ({@link #lowerRootInto}), so an enclosing object body's selection never
     * leaks into a Closure root nested inside it, and saved/restored around
     * each inline object body, so nested object bodies select their own.
     */
    private BytecodeLocal currentActivationLocal;

    /**
     * PERF025 lazy inline callback activation. True while {@link
     * #currentActivationLocal} holds the {@link
     * ProtosBytecodeRootNode.PreparedInlineLiteralCall} carrier of a PLAT044
     * B-prime inline callback region rather than an activation; {@link
     * #emitCurrentActivation} then materializes the callback activation
     * through that carrier only where an operation needs it.
     */
    private boolean currentActivationLocalIsInlineCallbackCall;

    /**
     * PERF025 inline-callback lexical slice. True only while an admitted
     * PLAT044 B-prime callback uses its statically proven bindings directly
     * from block-local frame storage, through the carrier-operand inline
     * operations that never materialize the callback activation on their
     * ordinary path.
     */
    private boolean currentInlineCallbackFrameNative;

    /**
     * PERF013 Slice B1 backend-private registry of the frame-backed {@link
     * BytecodeLocal}s created for every genuine lexical scope this lowerer has
     * lowered so far, keyed by the exact {@link CanonicalLexicalScope} object
     * the whole-tree binding analysis assigned it. A captured read or write
     * (Slice B2) whose owner scope is proven ({@link
     * #capturedOwnerMatchesCurrentRoot}) to be part of the current shared
     * {@code BytecodeRootNodes} group looks its owner's stable {@link
     * BytecodeLocal} up here (see {@link #capturedOwnerBytecodeLocal}) instead
     * of resolving it dynamically from the runtime frame-lexical-binding
     * authority. Populated by {@link
     * #emitRootBody} for every genuine execution-context root, before that
     * root's own body (and therefore any nested Closure root that might
     * capture one of its bindings) is lowered, so the owner's current
     * entry is always fresh by the time a nested captured read needs it —
     * including on a {@code BytecodeRootNodes} reparse replay, since the owner
     * root in the group is always replayed before its nested children and
     * simply overwrites its own entry with the current parse's {@link
     * BytecodeLocal} objects.
     */
    private final java.util.IdentityHashMap<CanonicalLexicalScope, java.util.Map<String, BytecodeLocal>>
            frameLocalsByScope = new java.util.IdentityHashMap<>();

    /*
     * PERF025-D179-A: BytecodeRootNodes may invoke its retained parser again
     * to materialize source/instrumentation metadata. BytecodeLocal objects
     * are parse-local and are intentionally refreshed in frameLocalsByScope,
     * but lexical membership speculation must retain one exact identity for
     * the lifetime of the logical lowered root. CanonicalLexicalScope identity
     * is stable across those parser replays because the binding analysis is
     * retained by this lowerer.
     *
     * Reusing the whole immutable layout also guarantees that every reparse
     * carries the same root/name Assumption constants. Otherwise an escaped
     * authority created before a reparse could invalidate an obsolete token
     * while newly generated ReadFrameLocal instructions trusted a fresh one.
     */
    private final java.util.IdentityHashMap<CanonicalLexicalScope, ProtosFrameLexicalLayout>
            frameLayoutsByScope = new java.util.IdentityHashMap<>();

    CanonicalToBytecodeLowerer(ProtosLanguage language, Source source) {
        this(language, source, null);
    }

    CanonicalToBytecodeLowerer(
            ProtosLanguage language,
            Source source,
            CanonicalBindingAnalysis inheritedBindingAnalysis) {
        this.language = Objects.requireNonNull(language, "language");
        this.source = Objects.requireNonNull(source, "source");
        this.inheritedBindingAnalysis = inheritedBindingAnalysis;
    }

    /**
     * PLAT036 Candidate D, Slice 1 preparatory metadata: computes and caches
     * statically proven lexical binding identity/presence-candidate metadata
     * for the unit currently being lowered, so it is available at lowering
     * time. Not yet consumed by codegen ({@code RUNTIME_AUTHORITY_CUTOVER=NO});
     * {@link #emitLookup}, {@link #emitBodyAssign} and {@link #emitBodyCreate}
     * continue to emit the exact existing String-keyed runtime operations.
     */
    private CanonicalBindingAnalysis bindingAnalysisFor(
            CanonicalSequence sequence, CanonicalClosure activationDefinition) {
        if (activationDefinition != null) {
            if (inheritedBindingAnalysis != null
                    && inheritedBindingAnalysis.scopeOf(activationDefinition).isPresent()) {
                return inheritedBindingAnalysis;
            }
            return bindingAnalysisByClosure.computeIfAbsent(
                    activationDefinition, CanonicalBindingAnalyzer::analyzeClosure);
        }

        /*
         * Inline object bodies reached while lowering a Closure with
         * inherited whole-tree analysis keep that same analysis available for
         * Closure literals nested inside the object. The object body itself
         * remains non-authoritative and never takes the direct-local path.
         */
        if (inheritedBindingAnalysis != null) {
            return inheritedBindingAnalysis;
        }

        if (moduleBindingAnalysis == null) {
            moduleBindingAnalysis = CanonicalBindingAnalyzer.analyzeModule(sequence);
        }
        return moduleBindingAnalysis;
    }

    private CanonicalBindingAnalysis bindingAnalysisForNestedClosure(
            CanonicalClosure definition) {
        if (currentRootAnalysis != null
                && currentRootAnalysis.scopeOf(definition).isPresent()) {
            return currentRootAnalysis;
        }
        if (inheritedBindingAnalysis != null
                && inheritedBindingAnalysis.scopeOf(definition).isPresent()) {
            return inheritedBindingAnalysis;
        }
        if (moduleBindingAnalysis != null
                && moduleBindingAnalysis.scopeOf(definition).isPresent()) {
            return moduleBindingAnalysis;
        }
        return CanonicalBindingAnalyzer.analyzeClosure(definition);
    }

    private CanonicalLexicalScope rootScopeFor(
            CanonicalBindingAnalysis analysis,
            CanonicalClosure activationDefinition) {
        if (activationDefinition == null) {
            return analysis.topScope();
        }
        return analysis.scopeOf(activationDefinition)
                .orElseGet(analysis::topScope);
    }

    /**
     * PERF013 Slice A/A2: construction path for a Closure reached while
     * emitting an already-open enclosing root's own body — including a
     * parameter default-value Closure, which (since Slice A2) is reached the
     * same way: real emission (via {@code emitClosureParameterBindings})
     * always happens while the owner root's own builder is already open.
     * Nests the Closure's root in the same {@code create()} invocation as
     * that enclosing root (see {@link #lowerNestedClosureRoot}) instead of
     * opening an independent lowerer/{@code create()} call, so owner and
     * child end up in one shared {@code BytecodeRootNodes} group.
     *
     * <p>A cache hit in {@code bytecodeClosurePlans} means this exact group's
     * lambda is being replayed (a {@code BytecodeRootNodes} reparse, e.g. to
     * materialize source/tag information): {@code beginRoot()}/{@code
     * endRoot()} must still be replayed for this Closure so every root in the
     * group keeps the same index it had during the original parse, but the
     * plan already frozen then remains valid (the Bytecode DSL patches the
     * same root identity in place on reparse) and must not be rebuilt.
     */
    private ProtosClosureExecutionPlanCell bytecodeClosurePlan(
            ProtosSemanticBytecodeRootNodeGen.Builder builder,
            CanonicalClosure definition) {
        ProtosClosureExecutionPlanCell existing = bytecodeClosurePlans.get(definition);

        ProtosSemanticBytecodeRootNode nestedRoot =
                lowerNestedClosureRoot(builder, definition);

        if (existing != null) {
            return existing;
        }

        ProtosClosureExecutionPlanCell cell = ProtosClosureExecutionPlanCell.pendingGroup();
        bytecodeClosurePlans.put(definition, cell);
        /*
         * lowerNestedClosureRoot's own currentRootAnalysis save/restore has
         * already unwound by this point, so this recomputes the exact same
         * whole-tree analysis it resolved and used while lowering nestedRoot
         * (bindingAnalysisForNestedClosure is a cheap, idempotent lookup, not
         * a re-analysis) rather than re-deriving one in isolation.
         */
        pendingGroupClosures.add(
                new PendingGroupClosure(
                        definition,
                        bindingAnalysisForNestedClosure(definition),
                        nestedRoot,
                        cell));
        return cell;
    }


    /**
     * Preparation-time metadata registration only. Every {@link
     * CanonicalCompose} directly inside {@code object}'s body must know its
     * enclosing object's reserved local-slot names before {@code
     * composeReservedNames} is consulted during structural validation of the
     * compose itself. This registration is idempotent.
     */
    private void registerObjectBodyReservedNames(CanonicalObject object) {
        java.util.List<String> reservedNames =
                java.util.List.copyOf(object.reservedLocalSlotNames());
        for (CanonicalExpression expression : object.body().expressions()) {
            if (expression instanceof CanonicalCompose compose) {
                java.util.List<String> previous =
                        bytecodeComposeReservedNames.put(compose, reservedNames);
                if (previous != null && !previous.equals(reservedNames)) {
                    throw new IllegalStateException(
                            "canonical composition item belongs to multiple object bodies");
                }
            }
        }
    }

    private java.util.List<String> composeReservedNames(
            CanonicalCompose compose) {
        java.util.List<String> reservedNames = bytecodeComposeReservedNames.get(compose);
        if (reservedNames == null) {
            throw new AssertionError(
                    "contextual composition item was not registered by its object body");
        }
        return reservedNames;
    }

    CallTarget lower(CanonicalSequence sequence) {
        return lowerRoot(sequence).getCallTarget();
    }

    ProtosSemanticBytecodeRootNode lowerRoot(CanonicalSequence sequence) {
        return lowerRoot(sequence, null);
    }

    ProtosSemanticBytecodeRootNode lowerClosureActivationRoot(
            CanonicalClosure definition) {
        Objects.requireNonNull(definition, "definition");
        validateSupportedDefaults(definition);
        return lowerRoot(definition.body(), definition);
    }

    private ProtosSemanticBytecodeRootNode lowerRoot(
            CanonicalSequence sequence,
            CanonicalClosure activationDefinition) {
        Objects.requireNonNull(sequence, "sequence");
        /*
         * PERF013 Slice A: this top-level entry owns the one Source wrapper for
         * the whole lexical root group. Every Closure lexically reached while
         * lowering this root's own body nests its own beginRoot()/endRoot()
         * pair inside this same open builder (see emitExpression's
         * CanonicalClosure case and lowerNestedClosureRoot below) instead of
         * opening an independent create() call, so owner and child end up in
         * one shared BytecodeRootNodes<ProtosSemanticBytecodeRootNode> group.
         *
         * A nested Closure's real ProtosClosureExecutionPlan cannot be built
         * while this create() call is still in progress (getCallTarget()
         * refuses until parsing completes), so construction is deferred via
         * pendingGroupClosures and drained right after create() returns, once
         * every root in the group is real and getCallTarget()-safe. Saved and
         * restored here so a reentrant lowerRoot/create() call never sees or
         * pollutes this group's pending list.
         *
         * PLAT041 C′: an object-construction body is lowered inline in the
         * enclosing root (emitInlineObjectConstruction) and contributes no
         * root to the group.
         */
        java.util.List<PendingGroupClosure> savedPendingGroupClosures =
                pendingGroupClosures;
        java.util.List<PendingArityRejection> savedPendingArityRejections =
                pendingArityRejections;
        pendingGroupClosures = new java.util.ArrayList<>();
        pendingArityRejections = new java.util.ArrayList<>();
        try {
            BytecodeRootNodes<ProtosSemanticBytecodeRootNode> roots =
                    ProtosSemanticBytecodeRootNodeGen.create(
                            language,
                            BytecodeConfig.DEFAULT,
                            builder -> {
                                /*
                                 * Root-inside-Source is the Bytecode DSL shape
                                 * that gives every root in the group a
                                 * reliable exact source section once lazy
                                 * source information is materialized.
                                 */
                                builder.beginSource(source);
                                lowerRootInto(
                                        builder,
                                        sequence,
                                        activationDefinition);
                                builder.endSource();
                            });

            /*
             * getCallTarget() may notify instrumentation, which can replay
             * this group's parser; detach the list first so such a replay
             * never records into it (see emitRootBody).
             */
            java.util.List<PendingArityRejection> arityRejections =
                    pendingArityRejections;
            pendingArityRejections = null;
            for (PendingArityRejection pending : arityRejections) {
                pending.root()
                        .attachArityRejectionTarget(
                                pending.rejectionRoot().getCallTarget());
            }

            for (PendingGroupClosure pending : pendingGroupClosures) {
                pending.cell()
                        .freeze(
                                ProtosClosureExecutionPlan.bytecode(
                                        pending.definition(),
                                        language,
                                        source,
                                        pending.bindingAnalysis(),
                                        pending.root()));
            }

            return roots.getNode(0);
        } finally {
            pendingGroupClosures = savedPendingGroupClosures;
            pendingArityRejections = savedPendingArityRejections;
        }
    }

    /**
     * PERF013 Slice A: lowers one root — top-level, or a Closure lexically
     * nested inside an already-open enclosing root's own {@code beginRoot()}/
     * {@code endRoot()} pair — into {@code builder}. The caller owns the
     * enclosing {@code beginSource}/{@code endSource} pair.
     *
     * <p>I068 Slice 5: establishes whole-tree binding metadata before
     * recursive validation constructs nested Closure/Object plans. Otherwise
     * those plans would conservatively re-analyze nested Closures in
     * isolation and lose their captured owner/depth metadata.
     */
    private ProtosSemanticBytecodeRootNode lowerRootInto(
            ProtosSemanticBytecodeRootNodeGen.Builder builder,
            CanonicalSequence sequence,
            CanonicalClosure activationDefinition) {
        CanonicalBindingAnalysis analysisForThisRoot =
                bindingAnalysisFor(sequence, activationDefinition);
        CanonicalLexicalScope scopeForThisRoot =
                rootScopeFor(analysisForThisRoot, activationDefinition);
        return lowerRootInto(
                builder,
                sequence,
                activationDefinition,
                analysisForThisRoot,
                scopeForThisRoot);
    }

    private ProtosSemanticBytecodeRootNode lowerRootInto(
            ProtosSemanticBytecodeRootNodeGen.Builder builder,
            CanonicalSequence sequence,
            CanonicalClosure activationDefinition,
            CanonicalBindingAnalysis analysisForThisRoot,
            CanonicalLexicalScope scopeForThisRoot) {
        validateSupported(sequence);
        validateSpan(sequence.span());

        /*
         * PLAT036 Slice 3: save/restore around this (possibly reentrant, e.g.
         * a nested Closure lowered while lowering its enclosing root's own
         * body) lowering pass's fast-path context. This both protects
         * same-invocation reentrancy and re-establishes state correctly on
         * every future BytecodeRootNodes reparse invocation of the retained
         * parser, since the whole nest replays together.
         *
         * PLAT041 C′: every root starts again from its own frame argument 0
         * as the current activation, even when nested inside an inline object
         * body of its enclosing root.
         */
        CanonicalBindingAnalysis savedAnalysis = currentRootAnalysis;
        CanonicalLexicalScope savedTopScope = currentRootTopScope;
        java.util.Map<String, BytecodeLocal> savedFrameLocals =
                currentRootFrameLocals;
        BytecodeLocal savedActivationLocal = currentActivationLocal;
        boolean savedActivationLocalIsInlineCallbackCall =
                currentActivationLocalIsInlineCallbackCall;
        boolean savedInlineCallbackFrameNative =
                currentInlineCallbackFrameNative;
        ProtosFrameLexicalLayout savedFrameLayout = currentRootFrameLayout;
        BytecodeLocal[] savedFrameNativeLocals = currentRootFrameNativeLocals;
        ProtosFrameLexicalLayout savedFrameNativeLayout = currentRootFrameNativeLayout;
        ProtosFrameLexicalLayout savedIndexedParameterLayout = currentRootIndexedParameterLayout;
        java.util.Set<String> savedScalarLocals = currentRootScalarLocals;
        try {
            currentRootAnalysis = analysisForThisRoot;
            currentRootTopScope = scopeForThisRoot;
            currentRootFrameLocals = java.util.Map.of();
            currentRootFrameLayout = null;
            currentActivationLocal = null;
            currentActivationLocalIsInlineCallbackCall = false;
            currentInlineCallbackFrameNative = false;
            currentRootFrameNativeLocals = null;
            currentRootFrameNativeLayout = null;
            currentRootIndexedParameterLayout = null;
            currentRootScalarLocals = java.util.Set.of();
            return emitRootBody(
                    builder,
                    sequence,
                    activationDefinition,
                    scopeForThisRoot);
        } finally {
            currentRootAnalysis = savedAnalysis;
            currentRootTopScope = savedTopScope;
            currentRootFrameLocals = savedFrameLocals;
            currentRootFrameLayout = savedFrameLayout;
            currentActivationLocal = savedActivationLocal;
            currentActivationLocalIsInlineCallbackCall =
                    savedActivationLocalIsInlineCallbackCall;
            currentInlineCallbackFrameNative =
                    savedInlineCallbackFrameNative;
            currentRootFrameNativeLocals = savedFrameNativeLocals;
            currentRootFrameNativeLayout = savedFrameNativeLayout;
            currentRootIndexedParameterLayout = savedIndexedParameterLayout;
            currentRootScalarLocals = savedScalarLocals;
        }
    }

    /**
     * PERF013 Slice A/A2: lowers a Closure lexically reached while an
     * enclosing root's own {@code beginRoot()}/{@code endRoot()} pair is
     * still open, nesting this Closure's root in the exact same {@code
     * create()} invocation (and therefore the same {@code BytecodeRootNodes}
     * group) as that enclosing root instead of opening an independent
     * lowerer/{@code create()} call. Since Slice A2 this also covers a
     * parameter default-value Closure: real emission
     * ({@code emitClosureParameterBindings} -> {@code emitExpression} ->
     * {@link #bytecodeClosurePlan}) always reaches this method while the
     * owner root's own builder is already open.
     *
     * <p>Resolves binding analysis via {@link #bindingAnalysisForNestedClosure}:
     * the enclosing root's own whole-tree analysis takes priority so captured
     * owner/depth metadata is not lost by re-analyzing this Closure in
     * isolation.
     */
    private ProtosSemanticBytecodeRootNode lowerNestedClosureRoot(
            ProtosSemanticBytecodeRootNodeGen.Builder builder,
            CanonicalClosure definition) {
        validateSupportedDefaults(definition);
        CanonicalBindingAnalysis analysisForThisRoot =
                bindingAnalysisForNestedClosure(definition);
        CanonicalLexicalScope scopeForThisRoot =
                rootScopeFor(analysisForThisRoot, definition);
        return lowerRootInto(
                builder,
                definition.body(),
                definition,
                analysisForThisRoot,
                scopeForThisRoot);
    }

    private ProtosSemanticBytecodeRootNode emitRootBody(
            ProtosSemanticBytecodeRootNodeGen.Builder builder,
            CanonicalSequence sequence,
            CanonicalClosure activationDefinition,
            CanonicalLexicalScope scopeForThisRoot) {
        SourceSpan rootSpan = sequence.span();

        builder.beginSourceSection(
                rootSpan.startOffset(),
                rootSpan.length());
        builder.beginRoot();

        /*
         * PLAT042 B′ / PERF025-H1: this root is entered directly (no
         * wrapper) and has no activation prologue. A compact source-call
         * frame stays compact until an operation that needs the rich
         * activation loads it through emitCurrentActivation.
         */

        /*
         * PERF032-G6: a Closure declaring no parameters has no argument
         * check in this root (see emitClosureParameterBindings); its
         * arity-rejection root is nested here, once per lowering, so every
         * reparse replays it at the same group index.
         */
        ProtosSemanticBytecodeRootNode arityRejectionRoot =
                hasArityRejectionRoot(activationDefinition)
                        ? emitArityRejectionRoot(builder, rootSpan)
                        : null;

        /*
         * PLAT036 Candidate D, I068 Slice 4: every statically
         * declared binding owned by this genuine execution-context
         * root, including Closure parameters, receives one stable
         * BytecodeLocal. Allocating that physical local does not
         * establish semantic presence: it stays cleared until the
         * binding point (the frame-backed authority, or the PERF025
         * frame-native establishment operation) writes it.
         */
        java.util.Map<String, BytecodeLocal> frameLocals =
                new java.util.LinkedHashMap<>();
        for (String name : scopeForThisRoot.declaredNames()) {
            BytecodeLocal local = builder.createLocal(name, null);
            frameLocals.put(name, local);
        }
        currentRootFrameLocals = java.util.Map.copyOf(frameLocals);
        /*
         * PERF013 Slice B1: register this root's own frame locals under its
         * own scope identity before its body (and therefore any nested
         * Closure root that might capture one of these bindings) is lowered,
         * so a captured read reached while lowering this root's own body
         * already sees a fresh entry.
         */
        frameLocalsByScope.put(scopeForThisRoot, currentRootFrameLocals);
        BytecodeLocal[] frameLocalRange = null;
        ProtosFrameLexicalLayout frameLocalLayout = null;
        if (!frameLocals.isEmpty()) {
            frameLocalRange =
                    frameLocals.values().toArray(BytecodeLocal[]::new);
            String[] frameLocalNames =
                    frameLocals.keySet().toArray(String[]::new);
            frameLocalLayout =
                    frameLexicalLayoutForScope(
                            scopeForThisRoot,
                            frameLocalNames,
                            frameLocalRange);
            currentRootFrameLayout = frameLocalLayout;
            /*
             * PERF025 frame-materialization slice: declaring a binding no
             * longer implies a persistent materialized frame. Only a root
             * that may need its bindings outside its live frame installs the
             * escape-safe (BUG013) materialized-frame authority at entry.
             */
            if (requiresPersistentFrameAuthority(
                    sequence,
                    activationDefinition,
                    currentRootAnalysis,
                    scopeForThisRoot)) {
                builder.beginInstallFrameLexicalAuthority(
                        frameLocalRange,
                        frameLocalLayout);
                emitCurrentActivation(builder);
                builder.endInstallFrameLexicalAuthority();
                currentRootIndexedParameterLayout = frameLocalLayout;
            } else {
                currentRootFrameNativeLocals = frameLocalRange;
                currentRootFrameNativeLayout = frameLocalLayout;
            }
        }

        currentRootScalarLocals =
                currentRootFrameNativeLayout == null
                        ? java.util.Set.of()
                        : scalarLocalNamesForRoot(
                                sequence,
                                activationDefinition,
                                currentRootAnalysis,
                                scopeForThisRoot);

        BytecodeLocal defaultValue = null;
        BytecodeLocal defaultPreparedCall = null;
        BytecodeLocal defaultChildResult = null;
        BytecodeLocal defaultResumeValue = null;
        if (activationDefinition != null
                && hasComposedDefault(activationDefinition)) {
            defaultValue = builder.createLocal("defaultValue", null);
            defaultPreparedCall = builder.createLocal("defaultPreparedClosureCall", null);
            defaultChildResult = builder.createLocal("defaultChildResult", null);
            defaultResumeValue = builder.createLocal("defaultResumeValue", null);
        }

        if (activationDefinition != null) {
            emitClosureParameterBindings(
                    builder,
                    activationDefinition,
                    defaultValue,
                    defaultPreparedCall,
                    defaultChildResult,
                    defaultResumeValue);
        }

        if (sequence.expressions().isEmpty()) {
            builder.beginReturn();
            builder.emitLoadConstant(ProtosNullValue.INSTANCE);
            builder.endReturn();
        } else {
            java.util.List<CanonicalExpression> expressions =
                    sequence.expressions();
            CanonicalExpression last =
                    expressions.get(expressions.size() - 1);

            boolean scalarTerminal =
                    last instanceof CanonicalLookup lookup
                            && isScalarLocalRead(lookup);
            boolean directTerminal =
                    !requiresComposedInvocation(last)
                            && isDirectTerminalValue(last);

            boolean closureTerminal =
                    last instanceof CanonicalClosure;

            if (scalarTerminal || directTerminal || closureTerminal) {
                /*
                 * PERF037-E: previous statements retain their established
                 * lowering. Only the terminal value bypasses sequenceResult.
                 * A single direct expression requires no result local.
                 */
                if (expressions.size() > 1) {
                    java.util.List<CanonicalExpression> prefix =
                            expressions.subList(0, expressions.size() - 1);

                    emitDiscardedPrefix(builder, prefix);
                }

                /*
                 * A nested Closure root must be registered in the shared
                 * BytecodeRootNodes group before the Return operation opens.
                 * The Closure value itself is still created at runtime.
                 */
                beginStatement(builder, last);
                ProtosClosureExecutionPlanCell terminalClosurePlan =
                        closureTerminal
                                ? bytecodeClosurePlan(
                                        builder, (CanonicalClosure) last)
                                : null;
                if (scalarTerminal) {
                    // Preserve the PERF034-E compact/D179-aware selection.
                    emitScalarLocalRead(
                            builder, (CanonicalLookup) last, null);
                } else if (closureTerminal) {
                    builder.beginReturn();
                    beginClosureCapture(builder);
                    builder.emitLoadConstant((CanonicalClosure) last);
                    builder.emitLoadConstant(terminalClosurePlan);
                    endClosureCapture(builder);
                    builder.endReturn();
                } else if (last instanceof CanonicalLookup terminalRead) {
                    // I091: a retained carrier may return to an accepting caller.
                    builder.beginReturn();
                    builder.beginMaterializeReturnCarrier();
                    emitLookup(builder, terminalRead, true);
                    builder.endMaterializeReturnCarrier();
                    builder.endReturn();
                } else {
                    builder.beginReturn();
                    emitExpression(builder, last);
                    builder.endReturn();
                }
                endStatement(builder);
            } else {
                /*
                 * PERF037-E: even when the final expression requires
                 * composed lowering, earlier Sequence values are not
                 * observable. Evaluate them in order and discard their
                 * results; only the final value needs result storage.
                 */
                ComposedSequenceScratch sharedScratch =
                        createComposedSequenceScratch(builder, expressions);

                emitDiscardedPrefix(
                        builder,
                        expressions.subList(0, expressions.size() - 1),
                        sharedScratch);

                BytecodeLocal result =
                        builder.createLocal("sequenceResult", null);

                beginStatement(builder, last);

                boolean carrierTerminal =
                        sharedScratch != null
                                && last instanceof CanonicalSend terminalSend
                                && primitiveResultSend(terminalSend);
                if (carrierTerminal) {
                    // I091: the final numeric result may return as a carrier.
                    emitPrimitiveNumericOperandToLocal(
                            builder,
                            last,
                            result,
                            sharedScratch.preparedCall(),
                            sharedScratch.childResult(),
                            sharedScratch.resumeValue());
                } else if (requiresComposedInvocation(last)) {
                    emitBodyExpressionToLocal(
                            builder,
                            last,
                            result,
                            sharedScratch == null
                                    ? null : sharedScratch.preparedCall(),
                            sharedScratch == null
                                    ? null : sharedScratch.childResult(),
                            sharedScratch == null
                                    ? null : sharedScratch.resumeValue());
                } else if (last instanceof CanonicalLookup lookup
                        && isScalarLocalRead(lookup)) {
                    emitScalarLocalReadToResult(builder, lookup, result);
                } else {
                    builder.beginStoreLocal(result);
                    emitExpression(builder, last);
                    builder.endStoreLocal();
                }

                endStatement(builder);

                builder.beginReturn();
                if (carrierTerminal) {
                    builder.beginMaterializeReturnCarrier();
                    builder.emitLoadLocal(result);
                    builder.endMaterializeReturnCarrier();
                } else {
                    builder.emitLoadLocal(result);
                }
                builder.endReturn();
            }
        }

        ProtosSemanticBytecodeRootNode result = builder.endRoot();
        builder.endSourceSection();
        result.recordProvablyNonSuspendingBody(
                provesNonSuspendingBody(
                        activationDefinition, currentRootAnalysis));
        // PERF032: prove the exact parameterless literal-one body eligible for
        // the NORMAL-mode headerless entry. Never infer this capability merely
        // from non-suspension.
        result.recordHeaderlessEntryBody(
                activationDefinition != null
                        && activationDefinition.parameters().isEmpty()
                        && sequence.expressions().size() == 1
                        && sequence.expressions().get(0)
                                instanceof CanonicalLiteral literal
                        && literal.kind() == CanonicalLiteral.Kind.NUMBER
                        && "1".equals(literal.value()));
        /*
         * A reparse replays the group onto the same root identities, whose
         * rejection targets are attached by the create() that first built
         * them; a replay outside that create() records nothing.
         */
        if (arityRejectionRoot != null
                && pendingArityRejections != null
                && result.arityRejectionTarget() == null) {
            pendingArityRejections.add(
                    new PendingArityRejection(result, arityRejectionRoot));
        }
        if (ProtosDiagnosticRootIdentity.ENABLED) {
            ProtosDiagnosticRootIdentity.record(
                    result,
                    new ProtosDiagnosticRootIdentity.Span(
                            source,
                            rootSpan.startOffset(),
                            rootSpan.length(),
                            activationDefinition == null
                                    ? ProtosDiagnosticRootIdentity.TOP_LEVEL
                                    : ProtosDiagnosticRootIdentity.CLOSURE));
        }
        if (currentRootFrameNativeLayout != null) {
            result.recordFrameNativeBindings(
                    LocalRangeAccessor.constantOf(currentRootFrameNativeLocals),
                    currentRootFrameNativeLayout);
        }
        return result;
    }

    /**
     * PERF032-G6: a genuine source Closure root whose signature is empty.
     * Under the normative parameter-binding algorithm ({@code CALLABLES.md})
     * its only binding step is the final excess-argument check, which no
     * parameter, default, or body effect can precede; that check is therefore
     * taken by the call's entry-target selection ({@link
     * ProtosSemanticBytecodeRootNode#selectSourceEntryTarget}) instead of by
     * the root's own body. Closures with any parameter keep the in-body
     * algorithm unchanged.
     */
    /**
     * PERF038-H: proof of non-suspending source execution for the
     * simplest Closure bodies.
     *
     * Admitted operations:
     * - binding supplied positional arguments without defaults/rest;
     * - loading a parameter resolved by this exact lexical analysis;
     * - evaluating literals.
     *
     * Calls, sends, dynamic or captured lookups, mutations, returns,
     * nested Closures and all unknown forms fail closed.
     *
     * A canonical binding identity is compared by Java identity:
     * coincidentally equal names never establish this proof.
     */
    private static boolean provesNonSuspendingBody(
            CanonicalClosure definition,
            CanonicalBindingAnalysis analysis) {
        if (definition == null || analysis == null) {
            return false;
        }

        for (CanonicalParameter parameter : definition.parameters()) {
            if (parameter.rest()
                    || parameter.defaultValue().isPresent()
                    || analysis.identityOf(parameter).isEmpty()) {
                return false;
            }
        }

        for (CanonicalExpression expression :
                definition.body().expressions()) {
            if (expression instanceof CanonicalLiteral) {
                continue;
            }

            if (expression instanceof CanonicalLookup lookup) {
                CanonicalBindingResolution resolution =
                        analysis.resolutionOf(lookup).orElse(null);

                if (!(resolution
                        instanceof CanonicalBindingResolution.Resolved resolved)) {
                    return false;
                }

                boolean localParameter = false;

                for (CanonicalParameter parameter :
                        definition.parameters()) {
                    if (analysis.identityOf(parameter).orElse(null)
                            == resolved.identity()) {
                        localParameter = true;
                        break;
                    }
                }

                if (localParameter) {
                    continue;
                }
            }

            return false;
        }

        return true;
    }

    private static boolean hasArityRejectionRoot(CanonicalClosure activationDefinition) {
        return activationDefinition != null
                && activationDefinition.parameters().isEmpty();
    }

    /**
     * PERF032-G6: lowers the arity-rejection root of a Closure declaring no
     * parameters, nested in that Closure's own open root so it belongs to the
     * same {@code BytecodeRootNodes} group with the same source section. It
     * is entered only by a call supplying arguments: the unchanged {@code
     * CheckFrameClosureArgumentUpperBound} materializes that call's exact
     * activation and signals the argument-count Error, and the ordinary root
     * exception interception selects guest handlers and records the
     * diagnostic origin. It never evaluates the Closure body; the trailing
     * return only keeps the root well formed.
     */
    private static ProtosSemanticBytecodeRootNode emitArityRejectionRoot(
            ProtosSemanticBytecodeRootNodeGen.Builder builder,
            SourceSpan rootSpan) {
        builder.beginSourceSection(
                rootSpan.startOffset(),
                rootSpan.length());
        builder.beginRoot();
        builder.beginCheckFrameClosureArgumentUpperBound();
        builder.emitLoadConstant(0);
        builder.endCheckFrameClosureArgumentUpperBound();
        builder.beginReturn();
        builder.emitLoadConstant(ProtosNullValue.INSTANCE);
        builder.endReturn();
        ProtosSemanticBytecodeRootNode rejectionRoot = builder.endRoot();
        builder.endSourceSection();
        return rejectionRoot;
    }

    /**
     * PERF025 frame-materialization slice: conservative lowering decision on
     * whether this genuine root must install its persistent, escape-safe
     * materialized-frame authority at entry, or may keep its statically proven
     * current bindings in its ordinary frame locals for the lifetime of its
     * live frame.
     *
     * <p>Only a Closure root can avoid the authority: a top-level/module root
     * may share its activation with later roots (REPL/module persistence and
     * the I072-C authority handoff), so it always installs it. A Closure root
     * avoids it only when nothing in its own parameters or body can make its
     * current execution context observable or capturable, or reach a current
     * binding other than through its static identity:
     * <ul>
     *   <li>no nested Closure (including inline literal callbacks and Closure
     *       defaults), which captures the current context by reference;</li>
     *   <li>no Object construction, whose construction activation captures it;</li>
     *   <li>no {@code context} intrinsic and no {@code compose}, which observe
     *       or populate it as a guest Context;</li>
     *   <li>every bare read or write of a name this root declares is {@code
     *       Resolved} to this root's own scope (never {@code Candidate} or
     *       {@code Dynamic}), and every target-less creation is proven to
     *       establish a binding of this root's own scope.</li>
     * </ul>
     * Any unrecognized form keeps the authority. A root that is admitted but
     * whose activation is nonetheless observed at run time takes the
     * in-frame transition of {@link
     * ProtosBytecodeRootNode#createCurrentFrameBinding}.
     */
    /**
     * PERF034-C: only a parameterless source Closure with an effect-free
     * straight-line body may use builtin StoreLocal/LoadLocal for its own
     * bindings. Each creation must establish a fresh, statically owned
     * name exactly once; reads must select an already-established name.
     *
     * No calls, context observation, closures, objects, mutation,
     * duplicate creation, candidate lookup or dynamic lookup are admitted.
     * All other programs retain the existing authoritative lowering.
     */
    private static java.util.Set<String> scalarLocalNamesForRoot(
            CanonicalSequence body,
            CanonicalClosure definition,
            CanonicalBindingAnalysis analysis,
            CanonicalLexicalScope scope) {
        if (definition == null
                || !definition.parameters().isEmpty()
                || analysis == null
                || scope == null) {
            return java.util.Set.of();
        }

        java.util.LinkedHashSet<String> established =
                new java.util.LinkedHashSet<>();

        for (CanonicalExpression expression : body.expressions()) {
            if (expression instanceof CanonicalCreate create) {
                if (create.target().isPresent()
                        || !(create.value() instanceof CanonicalLiteral
                                || (create.value() instanceof CanonicalLookup read
                                        && readsEstablishedScalar(
                                                read,
                                                analysis,
                                                scope,
                                                established)))
                        || analysis.identityOf(create)
                                .map(identity ->
                                        identity.owner() != scope
                                                || !identity.name().equals(
                                                        create.name()))
                                .orElse(true)
                        || !established.add(create.name())) {
                    return java.util.Set.of();
                }
                continue;
            }

            if (expression instanceof CanonicalLookup lookup) {
                if (!readsEstablishedScalar(
                        lookup, analysis, scope, established)) {
                    return java.util.Set.of();
                }
                continue;
            }

            /*
             * PERF036-A: a bare assignment whose destination is Resolved to
             * an already-established binding of this scope, and whose RHS is
             * a literal or an established scalar read. Such an RHS cannot
             * observe, remove, freeze or close the destination.
             */
            if (expression instanceof CanonicalAssign assign) {
                if (assign.target().isPresent()
                        || !(assign.value() instanceof CanonicalLiteral
                                || (assign.value() instanceof CanonicalLookup read
                                        && readsEstablishedScalar(
                                                read,
                                                analysis,
                                                scope,
                                                established)))
                        || !assignsEstablishedScalar(
                                assign, analysis, scope, established)) {
                    return java.util.Set.of();
                }
                continue;
            }

            if (!(expression instanceof CanonicalLiteral)) {
                return java.util.Set.of();
            }
        }

        return java.util.Set.copyOf(established);
    }

    /**
     * PERF034-C/E: {@code lookup} is a {@code Resolved} read of a binding
     * owned by {@code scope} (its lexical identity, not its spelling) that an
     * earlier creation of the same straight-line body already established.
     * Such a read is effect-free and cannot reach a capture or a dynamic
     * binding.
     */
    private static boolean readsEstablishedScalar(
            CanonicalLookup lookup,
            CanonicalBindingAnalysis analysis,
            CanonicalLexicalScope scope,
            java.util.Set<String> established) {
        return analysis.resolutionOf(lookup)
                .map(resolution ->
                        resolution
                                instanceof CanonicalBindingResolution.Resolved resolved
                                && resolved.identity().owner() == scope
                                && resolved.identity().name().equals(lookup.name())
                                && established.contains(
                                        resolved.identity().name()))
                .orElse(false);
    }

    /**
     * PERF036-A: the assignment counterpart of {@link
     * #readsEstablishedScalar}: {@code assign}'s destination is {@code
     * Resolved} to a binding of {@code scope} that an earlier creation of the
     * same straight-line body already established.
     */
    private static boolean assignsEstablishedScalar(
            CanonicalAssign assign,
            CanonicalBindingAnalysis analysis,
            CanonicalLexicalScope scope,
            java.util.Set<String> established) {
        return analysis.resolutionOf(assign)
                .map(resolution ->
                        resolution
                                instanceof CanonicalBindingResolution.Resolved resolved
                                && resolved.identity().owner() == scope
                                && resolved.identity().name().equals(assign.name())
                                && established.contains(
                                        resolved.identity().name()))
                .orElse(false);
    }

    private static boolean requiresPersistentFrameAuthority(
            CanonicalSequence body,
            CanonicalClosure activationDefinition,
            CanonicalBindingAnalysis analysis,
            CanonicalLexicalScope scope) {
        if (activationDefinition == null || analysis == null || scope == null) {
            return true;
        }
        for (CanonicalParameter parameter : activationDefinition.parameters()) {
            if (analysis.identityOf(parameter)
                            .map(identity -> identity.owner() != scope)
                            .orElse(true)
                    || (parameter.defaultValue().isPresent()
                            && demandsPersistentFrame(
                                    parameter.defaultValue().orElseThrow(), analysis, scope))) {
                return true;
            }
        }
        return demandsPersistentFrame(body, analysis, scope);
    }

    private static boolean demandsPersistentFrame(
            CanonicalExpression expression,
            CanonicalBindingAnalysis analysis,
            CanonicalLexicalScope scope) {
        if (expression instanceof CanonicalLiteral) {
            return false;
        }
        if (expression instanceof CanonicalClosure
                || expression instanceof CanonicalObject
                || expression instanceof CanonicalCompose) {
            return true;
        }
        if (expression instanceof CanonicalIntrinsic intrinsic) {
            return intrinsic.kind() == CanonicalIntrinsic.Kind.CONTEXT;
        }
        if (expression instanceof CanonicalLookup lookup) {
            return scope.declaresName(lookup.name())
                    && !resolvedInScope(analysis.resolutionOf(lookup), scope);
        }
        if (expression instanceof CanonicalAssign assign) {
            if (assign.target().isPresent()) {
                return demandsPersistentFrame(assign.target().orElseThrow(), analysis, scope)
                        || demandsPersistentFrame(assign.value(), analysis, scope);
            }
            return (scope.declaresName(assign.name())
                            && !resolvedInScope(analysis.resolutionOf(assign), scope))
                    || demandsPersistentFrame(assign.value(), analysis, scope);
        }
        if (expression instanceof CanonicalCreate create) {
            if (create.target().isPresent()) {
                return demandsPersistentFrame(create.target().orElseThrow(), analysis, scope)
                        || demandsPersistentFrame(create.value(), analysis, scope);
            }
            return analysis.identityOf(create)
                            .map(identity -> identity.owner() != scope)
                            .orElse(true)
                    || demandsPersistentFrame(create.value(), analysis, scope);
        }
        if (expression instanceof CanonicalMultipleCreate create) {
            return analysis.identitiesOf(create)
                            .map(identities -> identities.size() != create.names().size()
                                    || identities.stream()
                                            .anyMatch(identity -> identity.owner() != scope))
                            .orElse(true)
                    || demandsPersistentFrame(create.value(), analysis, scope);
        }
        if (expression instanceof CanonicalSequence sequence) {
            return anyDemandsPersistentFrame(sequence.expressions(), analysis, scope);
        }
        if (expression instanceof CanonicalCall call) {
            return demandsPersistentFrame(call.receiver(), analysis, scope)
                    || anyDemandsPersistentFrame(call.arguments(), analysis, scope);
        }
        if (expression instanceof CanonicalSend send) {
            return demandsPersistentFrame(send.receiver(), analysis, scope)
                    || anyDemandsPersistentFrame(send.arguments(), analysis, scope);
        }
        if (expression instanceof CanonicalSuperSend superSend) {
            return anyDemandsPersistentFrame(superSend.arguments(), analysis, scope);
        }
        if (expression instanceof CanonicalMember member) {
            return demandsPersistentFrame(member.receiver(), analysis, scope);
        }
        if (expression instanceof CanonicalIdentity identity) {
            return demandsPersistentFrame(identity.left(), analysis, scope)
                    || demandsPersistentFrame(identity.right(), analysis, scope);
        }
        if (expression instanceof CanonicalNotIdentity identity) {
            return demandsPersistentFrame(identity.left(), analysis, scope)
                    || demandsPersistentFrame(identity.right(), analysis, scope);
        }
        if (expression instanceof CanonicalDerivedInequality inequality) {
            return demandsPersistentFrame(inequality.left(), analysis, scope)
                    || demandsPersistentFrame(inequality.right(), analysis, scope);
        }
        if (expression instanceof CanonicalIndexedAssign indexed) {
            return demandsPersistentFrame(indexed.receiver(), analysis, scope)
                    || demandsPersistentFrame(indexed.index(), analysis, scope)
                    || demandsPersistentFrame(indexed.value(), analysis, scope);
        }
        if (expression instanceof CanonicalMapConstruction map) {
            if (demandsPersistentFrame(map.factory(), analysis, scope)) {
                return true;
            }
            for (CanonicalMapConstruction.Entry entry : map.entries()) {
                if (demandsPersistentFrame(entry.key(), analysis, scope)
                        || demandsPersistentFrame(entry.value(), analysis, scope)) {
                    return true;
                }
            }
            return false;
        }
        if (expression instanceof CanonicalReturn returnExpression) {
            return demandsPersistentFrame(returnExpression.value(), analysis, scope);
        }
        if (expression instanceof CanonicalSpread spread) {
            return demandsPersistentFrame(spread.expression(), analysis, scope);
        }
        return true;
    }

    private static boolean anyDemandsPersistentFrame(
            java.util.List<CanonicalExpression> expressions,
            CanonicalBindingAnalysis analysis,
            CanonicalLexicalScope scope) {
        for (CanonicalExpression expression : expressions) {
            if (demandsPersistentFrame(expression, analysis, scope)) {
                return true;
            }
        }
        return false;
    }

    private static boolean resolvedInScope(
            java.util.Optional<CanonicalBindingResolution> resolution,
            CanonicalLexicalScope scope) {
        return resolution.isPresent()
                && resolution.orElseThrow() instanceof CanonicalBindingResolution.Resolved resolved
                && resolved.identity().owner() == scope;
    }

    /**
     * PERF025-D179-A: returns the one frame layout owned by {@code scope} for
     * the complete lifetime of this lowerer's retained Bytecode parser.
     *
     * <p>The first parse creates it. Later BytecodeRootNodes reparses validate
     * that declaration order is unchanged and reuse the exact object, so its
     * root/name one-way membership assumptions cannot be silently renewed.
     * BUG018-C: a root lowering ({@code rootFrameLocals} non-null) also binds
     * the public offsets of those root-scoped locals, the same array that forms
     * the root's LocalRangeAccessor, and validates them on replay. An inline
     * callback lowering of the same scope passes null: its block-scoped locals
     * have context-dependent offsets and never back a frame authority.
     */
    private ProtosFrameLexicalLayout frameLexicalLayoutForScope(
            CanonicalLexicalScope scope,
            String[] frameLocalNames,
            BytecodeLocal[] rootFrameLocals) {
        ProtosFrameLexicalLayout layout = frameLayoutsByScope.get(scope);
        if (layout != null) {
            layout.requireSameNames(frameLocalNames);
        } else {
            layout = ProtosFrameLexicalLayout.of(frameLocalNames);
            frameLayoutsByScope.put(scope, layout);
        }
        if (rootFrameLocals != null) {
            layout.bindRootLocalOffsets(
                    ProtosFrameLexicalLayout.localOffsetsOf(rootFrameLocals));
        }
        return layout;
    }

    /**
     * The frame-layout ordinal through which a statically proven current
     * binding named {@code name} is established frame-natively, or {@code -1}
     * when no eligible frame-native layout is active. Besides a genuine root,
     * PERF025 admits the explicit semantic Activation of a PLAT044 B-prime
     * inline callback. Inline Object bodies remain excluded.
     */
    private int frameNativeOrdinal(String name) {
        if (currentRootFrameNativeLayout == null
                || (currentActivationLocal != null
                        && !currentInlineCallbackFrameNative)
                || !currentRootFrameLocals.containsKey(name)) {
            return -1;
        }
        Integer ordinal = currentRootFrameNativeLayout.offsetOf(name);
        return ordinal == null ? -1 : ordinal;
    }

    /**
     * PERF025-H1: the layout ordinal at which {@code parameter} of a root that
     * installs its persistent frame authority is established, or {@code -1}
     * when its static identity is not proven: the root has no such authority,
     * the current activation is not the root's own, or the binding analysis
     * gives the parameter no identity owned by this root's own scope and
     * backed by one of its frame locals. The ordinal is taken from the very
     * layout instance the indexed operation carries, so it denotes exactly
     * that parameter's binding; presence is still checked at run time.
     */
    private int indexedParameterOrdinal(CanonicalParameter parameter) {
        String name = parameter.name();
        if (currentRootIndexedParameterLayout == null
                || currentActivationLocal != null
                || currentRootAnalysis == null
                || !currentRootFrameLocals.containsKey(name)
                || currentRootAnalysis.identityOf(parameter)
                        .map(identity -> identity.owner() != currentRootTopScope
                                || !identity.name().equals(name))
                        .orElse(true)) {
            return -1;
        }
        Integer ordinal = currentRootIndexedParameterLayout.offsetOf(name);
        return ordinal == null ? -1 : ordinal;
    }

    /**
     * PERF030: the layout ordinal at which the target-less {@code create} of a
     * root that installs its persistent frame authority is established, or
     * {@code -1} when its static identity is not proven, under exactly the
     * conditions of {@link #indexedParameterOrdinal}. Presence is still
     * checked at run time.
     */
    private int indexedCreateOrdinal(CanonicalCreate create) {
        String name = create.name();
        if (currentRootIndexedParameterLayout == null
                || currentActivationLocal != null
                || currentRootAnalysis == null
                || !currentRootFrameLocals.containsKey(name)
                || currentRootAnalysis.identityOf(create)
                        .map(identity -> identity.owner() != currentRootTopScope
                                || !identity.name().equals(name))
                        .orElse(true)) {
            return -1;
        }
        Integer ordinal = currentRootIndexedParameterLayout.offsetOf(name);
        return ordinal == null ? -1 : ordinal;
    }

    /**
     * Emits each expression of {@code sequence} as one source statement
     * (StatementTag + ExpressionTag over its exact span), storing each value
     * into {@code result}. Shared by root bodies and inline object-construction
     * bodies so both keep identical statement/expression tag membership.
     */
    /**
     * PERF034-C: the compact lane reads the builtin local directly.
     * An observed activation instead uses the existing D179-aware
     * ReadRootFrameLocal path at the same source position.
     */
    private void emitScalarLocalReadToResult(
            ProtosSemanticBytecodeRootNodeGen.Builder builder,
            CanonicalLookup lookup,
            BytecodeLocal result) {
        emitScalarLocalRead(builder, lookup, result);
    }

    /**
     * PERF034-C/E: whether {@code lookup} reads an admitted scalar local of
     * the root being lowered (outside any inline activation region).
     */
    private boolean isScalarLocalRead(CanonicalLookup lookup) {
        return currentActivationLocal == null
                && currentRootScalarLocals.contains(lookup.name());
    }

    /**
     * PERF034-C/E: one admitted scalar read with its own compact check.
     * Stores the value into {@code result}, or, when {@code result} is
     * {@code null}, returns it directly from the root (PERF034-E final
     * read). Each read re-checks {@code IsCompactLocalFrame}, so a D179
     * materialization observed earlier is never bypassed.
     */
    private void emitScalarLocalRead(
            ProtosSemanticBytecodeRootNodeGen.Builder builder,
            CanonicalLookup lookup,
            BytecodeLocal result) {
        BytecodeLocal local =
                currentRootFrameLocals.get(lookup.name());

        if (local == null) {
            throw new AssertionError(
                    "admitted scalar local is missing: " + lookup.name());
        }

        builder.beginIfThenElse();
        builder.emitIsCompactLocalFrame();

        builder.beginBlock();
        beginScalarReadSink(builder, result);
        builder.emitLoadLocal(local);
        endScalarReadSink(builder, result);
        builder.endBlock();

        builder.beginBlock();
        beginScalarReadSink(builder, result);
        emitLookup(builder, lookup);
        endScalarReadSink(builder, result);
        builder.endBlock();

        builder.endIfThenElse();
    }

    private static void beginScalarReadSink(
            ProtosSemanticBytecodeRootNodeGen.Builder builder,
            BytecodeLocal result) {
        if (result == null) {
            builder.beginReturn();
        } else {
            builder.beginStoreLocal(result);
        }
    }

    private static void endScalarReadSink(
            ProtosSemanticBytecodeRootNodeGen.Builder builder,
            BytecodeLocal result) {
        if (result == null) {
            builder.endReturn();
        } else {
            builder.endStoreLocal();
        }
    }

    /**
     * Opens one source statement over {@code expression}'s exact span:
     * StatementTag + ExpressionTag around a block.
     */
    private static void beginStatement(
            ProtosSemanticBytecodeRootNodeGen.Builder builder,
            CanonicalExpression expression) {
        SourceSpan span = expression.span();
        builder.beginSourceSection(
                span.startOffset(),
                span.length());
        builder.beginTag(
                StandardTags.StatementTag.class,
                StandardTags.ExpressionTag.class);
        builder.beginBlock();
    }

    private static void endStatement(
            ProtosSemanticBytecodeRootNodeGen.Builder builder) {
        builder.endBlock();
        builder.endTag(
                StandardTags.StatementTag.class,
                StandardTags.ExpressionTag.class);
        builder.endSourceSection();
    }

    private void emitStatementsToLocal(
            ProtosSemanticBytecodeRootNodeGen.Builder builder,
            CanonicalSequence sequence,
            BytecodeLocal result) {
        emitStatementsToLocal(builder, sequence.expressions(), result);
    }

    private void emitStatementsToLocal(
            ProtosSemanticBytecodeRootNodeGen.Builder builder,
            java.util.List<CanonicalExpression> expressions,
            BytecodeLocal result) {
        // PERF036-A: an admitted scalar assignment needs no invocation staging.
        boolean hasComposedInvocation =
                expressions.stream()
                        .anyMatch(expression ->
                                requiresComposedInvocation(expression)
                                        && !(expression instanceof CanonicalAssign assign
                                                && isScalarLocalAssign(assign)));
        BytecodeLocal preparedCall =
                hasComposedInvocation
                        ? builder.createLocal(
                                "preparedClosureCall",
                                null)
                        : null;
        BytecodeLocal childResult =
                hasComposedInvocation
                        ? builder.createLocal(
                                "childResult",
                                null)
                        : null;
        BytecodeLocal resumeValue =
                hasComposedInvocation
                        ? builder.createLocal(
                                "resumeValue",
                                null)
                        : null;

        for (CanonicalExpression expression : expressions) {
            beginStatement(builder, expression);

            if (requiresComposedInvocation(expression)) {
                emitBodyExpressionToLocal(
                        builder,
                        expression,
                        result,
                        preparedCall,
                        childResult,
                        resumeValue);
            } else if (expression instanceof CanonicalLookup lookup
                    && isScalarLocalRead(lookup)) {
                emitScalarLocalReadToResult(builder, lookup, result);
            } else {
                builder.beginStoreLocal(result);
                emitExpression(builder, expression);
                builder.endStoreLocal();
            }

            endStatement(builder);
        }
    }

    private void validateSupportedDefaults(
            CanonicalClosure definition) {
        for (CanonicalParameter parameter : definition.parameters()) {
            if (parameter.defaultValue().isEmpty()) {
                continue;
            }
            validateSupportedDefaultExpression(parameter.defaultValue().orElseThrow());
        }
    }

    private void validateSupportedDefaultExpression(
            CanonicalExpression expression) {
        validateSpan(expression.span());
        if (expression instanceof CanonicalLiteral
                || expression instanceof CanonicalLookup
                || expression instanceof CanonicalIntrinsic) {
            return;
        }
        if (expression instanceof CanonicalClosure closure) {
            /*
             * PERF013 Slice A2: structural-support validation only, mirroring
             * validateSupportedExpression's CanonicalClosure case. This must
             * NOT construct the Closure's execution plan/root here: real
             * emission (emitClosureParameterBindings -> emitExpression) always
             * happens later, while the owner root's own builder is already
             * open, so the default-value Closure's root is nested in that
             * same shared BytecodeRootNodes group (bytecodeClosurePlan /
             * lowerNestedClosureRoot) instead of opening an independent
             * create() call.
             */
            validateSupportedDefaults(closure);
            validateSpan(closure.body().span());
            validateSupported(closure.body());
            return;
        }
        if (expression instanceof CanonicalObject object) {
            /*
             * Structural-support validation only: the body is emitted inline
             * later (emitInlineObjectConstruction), while the owner root's
             * own builder is open.
             */
            object.parent().ifPresent(this::validateSupportedDefaultExpression);
            registerObjectBodyReservedNames(object);
            validateSupported(object.body());
            return;
        }
        if (expression instanceof CanonicalMapConstruction map) {
            validateSupportedDefaultExpression(map.factory());
            for (CanonicalMapConstruction.Entry entry : map.entries()) {
                validateSupportedDefaultExpression(entry.key());
                validateSupportedDefaultExpression(entry.value());
            }
            return;
        }
        if (expression instanceof CanonicalMember member) {
            validateSupportedDefaultExpression(member.receiver());
            return;
        }
        if (expression instanceof CanonicalIdentity identity) {
            validateSupportedDefaultExpression(identity.left());
            validateSupportedDefaultExpression(identity.right());
            return;
        }
        if (expression instanceof CanonicalNotIdentity identity) {
            validateSupportedDefaultExpression(identity.left());
            validateSupportedDefaultExpression(identity.right());
            return;
        }
        if (expression instanceof CanonicalDerivedInequality inequality) {
            validateSupportedDefaultExpression(inequality.left());
            validateSupportedDefaultExpression(inequality.right());
            return;
        }
        if (expression instanceof CanonicalCreate create) {
            create.target().ifPresent(this::validateSupportedDefaultExpression);
            validateSupportedDefaultExpression(create.value());
            return;
        }
        if (expression instanceof CanonicalMultipleCreate create) {
            validateSupportedDefaultExpression(create.value());
            return;
        }
        if (expression instanceof CanonicalAssign assign) {
            assign.target().ifPresent(this::validateSupportedDefaultExpression);
            validateSupportedDefaultExpression(assign.value());
            return;
        }
        if (expression instanceof CanonicalIndexedAssign indexedAssign) {
            validateSupportedDefaultExpression(indexedAssign.receiver());
            validateSupportedDefaultExpression(indexedAssign.index());
            validateSupportedDefaultExpression(indexedAssign.value());
            return;
        }
        if (expression instanceof CanonicalCall call) {
            validateSupportedDefaultExpression(call.receiver());
            for (CanonicalExpression argument : call.arguments()) {
                if (argument instanceof CanonicalSpread spread) {
                    validateSupportedDefaultExpression(
                            spread.expression());
                } else {
                    validateSupportedDefaultExpression(argument);
                }
            }
            return;
        }
        if (expression instanceof CanonicalSend send) {
            validateSupportedDefaultExpression(send.receiver());
            for (CanonicalExpression argument : send.arguments()) {
                if (argument instanceof CanonicalSpread spread) {
                    validateSupportedDefaultExpression(
                            spread.expression());
                } else {
                    validateSupportedDefaultExpression(argument);
                }
            }
            return;
        }
        if (expression instanceof CanonicalSuperSend send) {
            for (CanonicalExpression argument : send.arguments()) {
                if (argument instanceof CanonicalSpread spread) {
                    validateSupportedDefaultExpression(spread.expression());
                } else {
                    validateSupportedDefaultExpression(argument);
                }
            }
            return;
        }
        if (expression instanceof CanonicalReturn returnExpression) {
            validateSupportedDefaultExpression(returnExpression.value());
            return;
        }
        throw new UnsupportedOperationException(
                "PERF006-B2C3B3 default expression is not migrated: "
                        + expression.getClass().getSimpleName());
    }

    private static boolean hasComposedDefault(CanonicalClosure definition) {
        return definition.parameters().stream()
                .flatMap(parameter -> parameter.defaultValue().stream())
                .anyMatch(CanonicalToBytecodeLowerer::requiresComposedInvocation);
    }

    /**
     * Sole lowering authority for loading the currently executing
     * {@link com.guillermomolina.protos.runtime.ProtosActivation}. A root's
     * own activation is frame argument 0, or is materialized there on demand
     * from a compact source-call ABI by the {@code CurrentActivation}
     * operation (PERF025-H1); inside an inline object-construction body
     * (PLAT041 C′) the current
     * activation is instead that body's construction activation, held in
     * {@link #currentActivationLocal}. Inside a PLAT044 B′ inline callback
     * region that local holds the invocation's carrier instead, and the
     * callback activation is materialized from it on demand, at most once
     * (PERF025 lazy inline callback activation); in a frame-native region
     * that materialization first moves the callback's block-local bindings
     * to a durable authority on the activation. Operations that need "the
     * current activation" must obtain it through this method rather than
     * emitting the argument load directly. This is a compile-time choice only.
     */
    private void emitCurrentActivation(
            ProtosSemanticBytecodeRootNodeGen.Builder builder) {
        if (currentActivationLocal == null) {
            builder.emitCurrentActivation();
        } else if (currentInlineCallbackFrameNative) {
            builder.beginMaterializeInlineCallbackActivation(
                    currentRootFrameNativeLocals,
                    currentRootFrameNativeLayout);
            builder.emitLoadLocal(currentActivationLocal);
            builder.endMaterializeInlineCallbackActivation();
        } else if (currentActivationLocalIsInlineCallbackCall) {
            builder.beginLoadInlineCallbackActivation();
            builder.emitLoadLocal(currentActivationLocal);
            builder.endLoadInlineCallbackActivation();
        } else {
            builder.emitLoadLocal(currentActivationLocal);
        }
    }

    /**
     * Loads the {@link ProtosBytecodeRootNode.PreparedInlineLiteralCall}
     * carrier operand of an inline frame-native operation; only valid while
     * {@link #currentInlineCallbackFrameNative}.
     */
    private void emitCurrentInlineCallbackCall(
            ProtosSemanticBytecodeRootNodeGen.Builder builder) {
        if (!currentInlineCallbackFrameNative
                || !currentActivationLocalIsInlineCallbackCall) {
            throw new AssertionError("no frame-native inline callback region is active");
        }
        builder.emitLoadLocal(currentActivationLocal);
    }

    /*
     * PERF025 slice 3. Inside a frame-native inline callback region, the
     * consumers below take the carrier-operand operation, which runs its
     * successful path without the callback activation and otherwise
     * materializes it exactly as emitCurrentActivation would; everywhere else
     * they take the unchanged activation operation. Each begin/end pair is
     * emitted within one region, so the selection is the same for both.
     */

    private void beginPrepareSend(
            ProtosSemanticBytecodeRootNodeGen.Builder builder, int arity) {
        if (currentInlineCallbackFrameNative) {
            if (arity == 0) {
                builder.beginPrepareInlineSendZero(
                        currentRootFrameNativeLocals,
                        currentRootFrameNativeLayout);
            } else if (arity == 1) {
                builder.beginPrepareInlineSendOne(
                        currentRootFrameNativeLocals,
                        currentRootFrameNativeLayout);
            } else {
                builder.beginPrepareInlineSendArguments(
                        currentRootFrameNativeLocals,
                        currentRootFrameNativeLayout);
            }
        } else if (arity == 0) {
            builder.beginPrepareSendZero();
        } else if (arity == 1) {
            builder.beginPrepareSendOne();
        } else {
            builder.beginPrepareSendArguments();
        }
    }

    private void endPrepareSend(
            ProtosSemanticBytecodeRootNodeGen.Builder builder, int arity) {
        if (currentInlineCallbackFrameNative) {
            if (arity == 0) {
                builder.endPrepareInlineSendZero();
            } else if (arity == 1) {
                builder.endPrepareInlineSendOne();
            } else {
                builder.endPrepareInlineSendArguments();
            }
        } else if (arity == 0) {
            builder.endPrepareSendZero();
        } else if (arity == 1) {
            builder.endPrepareSendOne();
        } else {
            builder.endPrepareSendArguments();
        }
    }

    /** The caller operand of a send/Closure-call preparation opened above. */
    private void emitInvocationCallerOperand(
            ProtosSemanticBytecodeRootNodeGen.Builder builder) {
        if (currentInlineCallbackFrameNative) {
            emitCurrentInlineCallbackCall(builder);
        } else {
            emitCurrentActivation(builder);
        }
    }

    /**
     * PERF038-D: the caller operand of a send preparation opened by {@link
     * #beginPrepareSend}. At root level it is the lazy caller reference,
     * which materializes the root's activation only for the specializations
     * that need its exact identity; elsewhere it is {@link
     * #emitInvocationCallerOperand}.
     */
    private void emitSendCallerOperand(
            ProtosSemanticBytecodeRootNodeGen.Builder builder) {
        if (!currentInlineCallbackFrameNative && currentActivationLocal == null) {
            builder.emitCurrentCallerReference();
        } else {
            emitInvocationCallerOperand(builder);
        }
    }

    /**
     * Opens a member read and emits its activation (or carrier) operand. A
     * root-level read (PERF037-B) takes no activation operand: it reaches
     * the root's frame arguments itself, only when it must.
     */
    private void beginMemberRead(
            ProtosSemanticBytecodeRootNodeGen.Builder builder,
            String name) {
        if (currentInlineCallbackFrameNative) {
            builder.beginReadInlineMember(
                    currentRootFrameNativeLocals,
                    currentRootFrameNativeLayout,
                    name);
            emitCurrentInlineCallbackCall(builder);
        } else if (currentActivationLocal == null) {
            builder.beginReadMemberAtRoot(name);
        } else {
            builder.beginReadMember(name);
            emitCurrentActivation(builder);
        }
    }

    private void endMemberRead(ProtosSemanticBytecodeRootNodeGen.Builder builder) {
        if (currentInlineCallbackFrameNative) {
            builder.endReadInlineMember();
        } else if (currentActivationLocal == null) {
            builder.endReadMemberAtRoot();
        } else {
            builder.endReadMember();
        }
    }

    /**
     * Emits the PERF028-A destination selection of a statically resolved
     * captured assignment, before its RHS is evaluated.
     */
    private void emitResolveCapturedWriteTarget(
            ProtosSemanticBytecodeRootNodeGen.Builder builder,
            CanonicalAssign assign,
            CanonicalBindingResolution.CapturedResolved captured,
            BytecodeLocal capturedOwnerLocal) {
        if (capturedOwnerLocal != null) {
            if (currentInlineCallbackFrameNative) {
                builder.beginResolveInlineCapturedMaterializedWritableLexicalTarget(
                        capturedOwnerLocal,
                        currentRootFrameNativeLocals,
                        currentRootFrameNativeLayout);
                emitCurrentInlineCallbackCall(builder);
            } else {
                builder.beginResolveCapturedMaterializedWritableLexicalTarget(
                        capturedOwnerLocal);
                emitCurrentActivation(builder);
            }
            builder.emitLoadConstant(assign.name());
            builder.emitLoadConstant(captured.lexicalDepth());
            if (currentInlineCallbackFrameNative) {
                builder.endResolveInlineCapturedMaterializedWritableLexicalTarget();
            } else {
                builder.endResolveCapturedMaterializedWritableLexicalTarget();
            }
            return;
        }
        int frameOrdinal = frameBackedOrdinal(captured.identity());
        if (currentInlineCallbackFrameNative) {
            builder.beginResolveInlineCapturedWritableLexicalTarget(
                    currentRootFrameNativeLocals,
                    currentRootFrameNativeLayout,
                    frameOrdinal);
            emitCurrentInlineCallbackCall(builder);
        } else {
            builder.beginResolveCapturedWritableLexicalTarget(frameOrdinal);
            emitCurrentActivation(builder);
        }
        builder.emitLoadConstant(assign.name());
        builder.emitLoadConstant(captured.lexicalDepth());
        if (currentInlineCallbackFrameNative) {
            builder.endResolveInlineCapturedWritableLexicalTarget();
        } else {
            builder.endResolveCapturedWritableLexicalTarget();
        }
    }

    /**
     * Emits the write of a statically resolved captured assignment to the
     * destination retained in {@code mutationTarget}.
     */
    private void emitAssignCaptured(
            ProtosSemanticBytecodeRootNodeGen.Builder builder,
            CanonicalAssign assign,
            CanonicalBindingResolution.CapturedResolved captured,
            BytecodeLocal capturedOwnerLocal,
            BytecodeLocal mutationTarget,
            BytecodeLocal value) {
        if (capturedOwnerLocal != null) {
            if (currentInlineCallbackFrameNative) {
                builder.beginAssignInlineCapturedMaterializedLocal(
                        capturedOwnerLocal,
                        currentRootFrameNativeLocals,
                        currentRootFrameNativeLayout);
                emitCurrentInlineCallbackCall(builder);
            } else {
                builder.beginAssignCapturedMaterializedLocal(capturedOwnerLocal);
                emitCurrentActivation(builder);
            }
        } else if (currentInlineCallbackFrameNative) {
            builder.beginAssignInlineCapturedFrameLocal(
                    currentRootFrameNativeLocals,
                    currentRootFrameNativeLayout,
                    frameBackedOrdinal(captured.identity()));
            emitCurrentInlineCallbackCall(builder);
        } else {
            builder.beginAssignCapturedFrameLocal(
                    frameBackedOrdinal(captured.identity()));
            emitCurrentActivation(builder);
        }
        builder.emitLoadLocal(mutationTarget);
        builder.emitLoadConstant(assign.name());
        builder.emitLoadLocal(value);
        if (capturedOwnerLocal != null) {
            if (currentInlineCallbackFrameNative) {
                builder.endAssignInlineCapturedMaterializedLocal();
            } else {
                builder.endAssignCapturedMaterializedLocal();
            }
        } else if (currentInlineCallbackFrameNative) {
            builder.endAssignInlineCapturedFrameLocal();
        } else {
            builder.endAssignCapturedFrameLocal();
        }
    }

    private void emitClosureParameterBindings(
            ProtosSemanticBytecodeRootNodeGen.Builder builder,
            CanonicalClosure definition,
            BytecodeLocal defaultValue,
            BytecodeLocal defaultPreparedCall,
            BytecodeLocal defaultChildResult,
            BytecodeLocal defaultResumeValue) {
        int positionalIndex = 0;
        boolean hasRest = false;

        for (CanonicalParameter parameter : definition.parameters()) {
            if (parameter.rest()) {
                int restOrdinal = frameNativeOrdinal(parameter.name());
                int indexedRestOrdinal = indexedParameterOrdinal(parameter);
                if (restOrdinal >= 0) {
                    builder.beginBindClosureFrameRest(
                            currentRootFrameNativeLocals,
                            currentRootFrameNativeLayout,
                            restOrdinal);
                    emitCurrentActivation(builder);
                    builder.emitLoadConstant(parameter.name());
                    builder.emitLoadConstant(positionalIndex);
                    builder.endBindClosureFrameRest();
                } else if (indexedRestOrdinal >= 0) {
                    builder.beginBindClosureIndexedRest(
                            currentRootIndexedParameterLayout,
                            indexedRestOrdinal);
                    emitCurrentActivation(builder);
                    builder.emitLoadConstant(parameter.name());
                    builder.emitLoadConstant(positionalIndex);
                    builder.endBindClosureIndexedRest();
                } else {
                    builder.beginBindClosureRest();
                    emitCurrentActivation(builder);
                    builder.emitLoadConstant(parameter.name());
                    builder.emitLoadConstant(positionalIndex);
                    builder.endBindClosureRest();
                }
                hasRest = true;
                continue;
            }

            if (parameter.defaultValue().isPresent()) {
                CanonicalExpression defaultExpression =
                        parameter.defaultValue().orElseThrow();

                builder.beginIfThenElse();

                if (currentActivationLocal == null) {
                    builder.beginHasFrameClosureArgument();
                    builder.emitLoadConstant(positionalIndex);
                    builder.endHasFrameClosureArgument();
                } else {
                    builder.beginHasClosureArgument();
                    emitCurrentActivation(builder);
                    builder.emitLoadConstant(positionalIndex);
                    builder.endHasClosureArgument();
                }

                builder.beginBlock();
                emitBindSuppliedClosureParameter(
                        builder,
                        parameter,
                        positionalIndex);
                builder.endBlock();

                builder.beginBlock();
                if (defaultExpression instanceof CanonicalCall defaultCall) {
                    emitComposedDefaultCall(
                            builder,
                            defaultCall,
                            defaultValue,
                            defaultPreparedCall,
                            defaultChildResult,
                            defaultResumeValue);
                    emitBindDefaultLocal(builder, parameter, defaultValue);
                } else if (defaultExpression instanceof CanonicalSend defaultSend) {
                    emitComposedDefaultSend(
                            builder,
                            defaultSend,
                            defaultValue,
                            defaultPreparedCall,
                            defaultChildResult,
                            defaultResumeValue);
                    emitBindDefaultLocal(builder, parameter, defaultValue);
                } else if (defaultExpression instanceof CanonicalReturn
                        || requiresComposedInvocation(defaultExpression)) {
                    emitDefaultExpressionToLocal(
                            builder,
                            defaultExpression,
                            defaultValue,
                            defaultPreparedCall,
                            defaultChildResult,
                            defaultResumeValue);
                    emitBindDefaultLocal(builder, parameter, defaultValue);
                } else {
                    ParameterBindingForm form =
                            beginBindClosureParameter(builder, parameter);
                    builder.beginSourceSection(
                            defaultExpression.span().startOffset(),
                            defaultExpression.span().length());
                    emitExpression(builder, defaultExpression);
                    builder.endSourceSection();
                    endBindClosureParameter(builder, form);
                }
                builder.endBlock();

                builder.endIfThenElse();
            } else {
                emitBindSuppliedClosureParameter(
                        builder,
                        parameter,
                        positionalIndex);
            }

            positionalIndex++;
        }

        /*
         * PERF032-G6: a genuine root with an empty signature leaves this
         * check to its arity-rejection root (see emitArityRejectionRoot).
         */
        boolean checkedBeforeEntry =
                currentActivationLocal == null
                        && hasArityRejectionRoot(definition);
        if (!hasRest && !checkedBeforeEntry) {
            if (currentActivationLocal == null) {
                builder.beginCheckFrameClosureArgumentUpperBound();
                builder.emitLoadConstant(positionalIndex);
                builder.endCheckFrameClosureArgumentUpperBound();
            } else if (currentInlineCallbackFrameNative) {
                builder.beginCheckInlineClosureArgumentUpperBound(
                        currentRootFrameNativeLocals,
                        currentRootFrameNativeLayout);
                emitCurrentInlineCallbackCall(builder);
                builder.emitLoadConstant(positionalIndex);
                builder.endCheckInlineClosureArgumentUpperBound();
            } else {
                builder.beginCheckClosureArgumentUpperBound();
                emitCurrentActivation(builder);
                builder.emitLoadConstant(positionalIndex);
                builder.endCheckClosureArgumentUpperBound();
            }
        }
    }

    private void emitBindDefaultLocal(
            ProtosSemanticBytecodeRootNodeGen.Builder builder,
            CanonicalParameter parameter,
            BytecodeLocal defaultValue) {
        if (defaultValue == null) {
            throw new AssertionError("composed default value local was not allocated");
        }
        ParameterBindingForm form = beginBindClosureParameter(builder, parameter);
        builder.emitLoadLocal(defaultValue);
        endBindClosureParameter(builder, form);
    }

    /** The binding operation opened for one Closure parameter. */
    private enum ParameterBindingForm {
        FRAME_NATIVE,
        INLINE_FRAME_NATIVE,
        INDEXED,
        NAMED
    }

    /**
     * Opens the binding operation of one Closure parameter: the PERF025
     * frame-native {@code BindClosureFrameParameter} for a parameter of a
     * root lowered without a persistent frame authority; the PERF025-H1
     * {@code BindClosureIndexedParameter} for a statically proven parameter
     * of a root that installs one; otherwise the unchanged named {@code
     * BindClosureParameter}. Each then takes the value operand. The
     * frame-native form (only ever opened for the root's own activation)
     * takes no activation operand, so binding a parameter of a compact
     * source call does not materialize one (PERF025-H1).
     */
    private ParameterBindingForm beginBindClosureParameter(
            ProtosSemanticBytecodeRootNodeGen.Builder builder,
            CanonicalParameter parameter) {
        String name = parameter.name();
        int ordinal = frameNativeOrdinal(name);
        if (ordinal >= 0) {
            if (currentInlineCallbackFrameNative) {
                builder.beginBindInlineClosureFrameParameter(
                        currentRootFrameNativeLocals,
                        currentRootFrameNativeLayout,
                        ordinal);
                emitCurrentInlineCallbackCall(builder);
                builder.emitLoadConstant(name);
                return ParameterBindingForm.INLINE_FRAME_NATIVE;
            }
            builder.beginBindClosureFrameParameter(
                    currentRootFrameNativeLocals,
                    currentRootFrameNativeLayout,
                    ordinal);
            builder.emitLoadConstant(name);
            return ParameterBindingForm.FRAME_NATIVE;
        }
        ordinal = indexedParameterOrdinal(parameter);
        if (ordinal >= 0) {
            builder.beginBindClosureIndexedParameter(
                    currentRootIndexedParameterLayout,
                    ordinal);
            emitCurrentActivation(builder);
            builder.emitLoadConstant(name);
            return ParameterBindingForm.INDEXED;
        }
        builder.beginBindClosureParameter();
        emitCurrentActivation(builder);
        builder.emitLoadConstant(name);
        return ParameterBindingForm.NAMED;
    }

    private static void endBindClosureParameter(
            ProtosSemanticBytecodeRootNodeGen.Builder builder,
            ParameterBindingForm form) {
        switch (form) {
            case FRAME_NATIVE -> builder.endBindClosureFrameParameter();
            case INLINE_FRAME_NATIVE ->
                    builder.endBindInlineClosureFrameParameter();
            case INDEXED -> builder.endBindClosureIndexedParameter();
            case NAMED -> builder.endBindClosureParameter();
        }
    }

    /**
     * Emits the target-less creation of {@code name} from {@code value}: the
     * PERF025 frame-native {@code CreateCurrentFrameLocal} for a binding of a
     * root lowered without a persistent frame authority, otherwise the
     * PERF030 {@code CreateCurrentIndexedLocalSlot} for a statically proven
     * binding of a root that installs one, otherwise the unchanged {@code
     * CreateCurrentLocalSlot}.
     */
    private void emitCreateCurrentBinding(
            ProtosSemanticBytecodeRootNodeGen.Builder builder,
            CanonicalCreate create,
            BytecodeLocal value) {
        String name = create.name();
        int ordinal = frameNativeOrdinal(name);
        if (ordinal >= 0) {
            if (currentInlineCallbackFrameNative) {
                emitCreateInlineCurrentFrameLocal(builder, ordinal, name);
                builder.emitLoadLocal(value);
                builder.endCreateInlineCurrentFrameLocal();
                return;
            }
            emitCreateCurrentFrameLocal(builder, ordinal, name);
            builder.emitLoadLocal(value);
            builder.endCreateCurrentFrameLocal();
            return;
        }
        ordinal = indexedCreateOrdinal(create);
        if (ordinal >= 0) {
            builder.beginCreateCurrentIndexedLocalSlot(
                    currentRootIndexedParameterLayout,
                    ordinal);
            emitCurrentActivation(builder);
            builder.emitLoadConstant(name);
            builder.emitLoadLocal(value);
            builder.endCreateCurrentIndexedLocalSlot();
            return;
        }
        builder.beginCreateCurrentLocalSlot();
        emitCurrentActivation(builder);
        builder.emitLoadConstant(name);
        builder.emitLoadLocal(value);
        builder.endCreateCurrentLocalSlot();
    }

    /**
     * PERF034-C/E: one admitted scalar creation with its own compact check.
     * The compact lane stores {@code emitValue}'s operand into the builtin
     * local and yields that binding's value; an observed activation instead
     * takes the authoritative {@code CreateCurrentFrameLocal} with the same
     * operand. {@code emitValue} must be effect-free (a constant or an
     * already-evaluated local).
     */
    private void emitScalarCreate(
            ProtosSemanticBytecodeRootNodeGen.Builder builder,
            CanonicalCreate create,
            BytecodeLocal result,
            Runnable emitValue) {
        String name = create.name();
        BytecodeLocal local = currentRootFrameLocals.get(name);
        int ordinal = frameNativeOrdinal(name);
        if (local == null || ordinal < 0) {
            throw new AssertionError(
                    "admitted scalar creation is missing: " + name);
        }

        builder.beginIfThenElse();
        builder.emitIsCompactLocalFrame();

        builder.beginBlock();
        builder.beginStoreLocal(local);
        emitValue.run();
        builder.endStoreLocal();
        builder.beginStoreLocal(result);
        builder.emitLoadLocal(local);
        builder.endStoreLocal();
        builder.endBlock();

        builder.beginBlock();
        builder.beginStoreLocal(result);
        emitCreateCurrentFrameLocal(builder, ordinal, name);
        emitValue.run();
        builder.endCreateCurrentFrameLocal();
        builder.endStoreLocal();
        builder.endBlock();

        builder.endIfThenElse();
    }

    /**
     * Opens a frame-native {@code CreateInlineCurrentFrameLocal} of {@code
     * name} at {@code ordinal}, a constant operand, leaving only its value
     * operand to emit.
     */
    private void emitCreateInlineCurrentFrameLocal(
            ProtosSemanticBytecodeRootNodeGen.Builder builder,
            int ordinal,
            String name) {
        builder.beginCreateInlineCurrentFrameLocal(
                currentRootFrameNativeLocals,
                currentRootFrameNativeLayout,
                ordinal);
        emitCurrentInlineCallbackCall(builder);
        builder.emitLoadConstant(name);
    }

    /**
     * Opens a frame-native {@code CreateCurrentFrameLocal} of {@code name} at
     * {@code ordinal}, a constant operand, leaving only its value operand to
     * emit.
     */
    private void emitCreateCurrentFrameLocal(
            ProtosSemanticBytecodeRootNodeGen.Builder builder,
            int ordinal,
            String name) {
        if (currentActivationLocal != null) {
            throw new AssertionError(
                    "frame-native creation inside an unsupported inline "
                            + "activation region: "
                            + name);
        }
        builder.beginCreateCurrentFrameLocal(
                currentRootFrameNativeLocals,
                currentRootFrameNativeLayout,
                ordinal);
        builder.emitLoadConstant(name);
    }

    /**
     * Emits a multiple creation from {@code source}. When every name is a
     * frame-native binding (PERF025), PERF030-I lowers it to one observation
     * of the complete fixed prefix, retained in a local, followed by one
     * scalar frame-native creation per name in source order, each owning its
     * ordinal as a constant operand; a failing creation leaves the earlier
     * ones in place, and the whole expression yields {@code source}.
     * Otherwise the unchanged {@code MultipleCreateLocalSlots}.
     */
    private void emitMultipleCreateCurrentBindings(
            ProtosSemanticBytecodeRootNodeGen.Builder builder,
            java.util.List<String> names,
            BytecodeLocal source) {
        int[] ordinals = new int[names.size()];
        boolean frameNative = true;
        for (int index = 0; index < ordinals.length && frameNative; index++) {
            ordinals[index] = frameNativeOrdinal(names.get(index));
            frameNative = ordinals[index] >= 0;
        }
        if (!frameNative) {
            builder.beginMultipleCreateLocalSlots();
            emitCurrentActivation(builder);
            builder.emitLoadConstant(multipleCreateNamesConstant(names));
            builder.emitLoadLocal(source);
            builder.endMultipleCreateLocalSlots();
            return;
        }
        boolean inline = currentInlineCallbackFrameNative;
        builder.beginBlock();
        BytecodeLocal observed = builder.createLocal("multipleCreateObserved", null);
        builder.beginStoreLocal(observed);
        if (inline) {
            builder.beginObserveInlineMultipleCreatePrefix(
                    currentRootFrameNativeLocals,
                    currentRootFrameNativeLayout);
            emitCurrentInlineCallbackCall(builder);
        } else {
            builder.beginObserveMultipleCreatePrefix();
            emitCurrentActivation(builder);
        }
        builder.emitLoadConstant(ordinals.length);
        builder.emitLoadLocal(source);
        if (inline) {
            builder.endObserveInlineMultipleCreatePrefix();
        } else {
            builder.endObserveMultipleCreatePrefix();
        }
        builder.endStoreLocal();
        for (int index = 0; index < ordinals.length; index++) {
            if (inline) {
                emitCreateInlineCurrentFrameLocal(builder, ordinals[index], names.get(index));
            } else {
                emitCreateCurrentFrameLocal(builder, ordinals[index], names.get(index));
            }
            builder.beginObservedMultipleCreateValue();
            builder.emitLoadLocal(observed);
            builder.emitLoadConstant(index);
            builder.endObservedMultipleCreateValue();
            if (inline) {
                builder.endCreateInlineCurrentFrameLocal();
            } else {
                builder.endCreateCurrentFrameLocal();
            }
        }
        builder.emitLoadLocal(source);
        builder.endBlock();
    }

    private static boolean hasComposedArgument(
            java.util.List<CanonicalExpression> arguments) {
        return arguments.stream()
                .map(CanonicalToBytecodeLowerer::suppliedArgumentExpression)
                .anyMatch(CanonicalToBytecodeLowerer::requiresComposedInvocation);
    }

    /**
     * PERF037-E: value expressions that can be emitted inside Return without
     * invoking the composed-expression machinery. Keep Closure materialization
     * on its established path because it may lower nested roots first.
     */
    private static boolean isDirectTerminalValue(
            CanonicalExpression expression) {
        if (expression instanceof CanonicalLiteral
                || expression instanceof CanonicalLookup
                || expression instanceof CanonicalIntrinsic) {
            return true;
        }
        if (expression instanceof CanonicalMember member) {
            return isDirectTerminalValue(member.receiver());
        }
        if (expression instanceof CanonicalIdentity identity) {
            return isDirectTerminalValue(identity.left())
                    && isDirectTerminalValue(identity.right());
        }
        if (expression instanceof CanonicalNotIdentity identity) {
            return isDirectTerminalValue(identity.left())
                    && isDirectTerminalValue(identity.right());
        }
        return false;
    }

    /**
     * A simple prefix can consume each evaluated result directly. Preserve
     * the established scalar lane and composed-control lowering otherwise.
     */
    private record ComposedSequenceScratch(
            BytecodeLocal preparedCall,
            BytecodeLocal childResult,
            BytecodeLocal resumeValue) {}

    private ComposedSequenceScratch createComposedSequenceScratch(
            ProtosSemanticBytecodeRootNodeGen.Builder builder,
            java.util.List<CanonicalExpression> expressions) {
        boolean needed =
                expressions.stream()
                        .anyMatch(expression ->
                                requiresComposedInvocation(expression)
                                        && !(expression instanceof CanonicalAssign assign
                                                && isScalarLocalAssign(assign)));
        if (!needed) {
            return null;
        }
        return new ComposedSequenceScratch(
                builder.createLocal("preparedClosureCall", null),
                builder.createLocal("childResult", null),
                builder.createLocal("resumeValue", null));
    }

    private void emitDiscardedPrefix(
            ProtosSemanticBytecodeRootNodeGen.Builder builder,
            java.util.List<CanonicalExpression> expressions) {
        emitDiscardedPrefix(
                builder,
                expressions,
                createComposedSequenceScratch(builder, expressions));
    }

    private void emitDiscardedPrefix(
            ProtosSemanticBytecodeRootNodeGen.Builder builder,
            java.util.List<CanonicalExpression> expressions,
            ComposedSequenceScratch scratch) {
        BytecodeLocal preparedCall =
                scratch == null ? null : scratch.preparedCall();
        BytecodeLocal childResult =
                scratch == null ? null : scratch.childResult();
        BytecodeLocal resumeValue =
                scratch == null ? null : scratch.resumeValue();
        BytecodeLocal stagedResult = null;

        for (CanonicalExpression expression : expressions) {
            boolean directDiscard =
                    isDirectTerminalValue(expression)
                            && !requiresComposedInvocation(expression)
                            && !(expression instanceof CanonicalLookup lookup
                                    && isScalarLocalRead(lookup));

            if (directDiscard) {
                beginStatement(builder, expression);
                builder.beginDiscardValue();
                emitExpression(builder, expression);
                builder.endDiscardValue();
                endStatement(builder);
            } else if (expression instanceof CanonicalLookup lookup
                    && isScalarLocalRead(lookup)) {
                beginStatement(builder, expression);
                emitDiscardedScalarLocalRead(builder, lookup);
                endStatement(builder);
            } else if (expression instanceof CanonicalClosure closure) {
                /*
                 * Register the nested root before opening DiscardValue.
                 * Materialization still executes at the original statement.
                 */
                beginStatement(builder, expression);
                ProtosClosureExecutionPlanCell plan =
                        bytecodeClosurePlan(builder, closure);
                builder.beginDiscardValue();
                beginClosureCapture(builder);
                builder.emitLoadConstant(closure);
                builder.emitLoadConstant(plan);
                endClosureCapture(builder);
                builder.endDiscardValue();
                endStatement(builder);
            } else {
                /*
                 * A composed/control expression keeps its existing lowering.
                 * Allocate scratch storage only if one actually occurs.
                 * Direct expressions on either side still avoid result stores.
                 */
                if (stagedResult == null) {
                    stagedResult =
                            builder.createLocal("sequenceResult", null);
                }
                beginStatement(builder, expression);
                if (requiresComposedInvocation(expression)) {
                    emitBodyExpressionToLocal(
                            builder,
                            expression,
                            stagedResult,
                            preparedCall,
                            childResult,
                            resumeValue);
                } else {
                    builder.beginStoreLocal(stagedResult);
                    emitExpression(builder, expression);
                    builder.endStoreLocal();
                }
                endStatement(builder);
            }
        }
    }

    private void emitDiscardedScalarLocalRead(
            ProtosSemanticBytecodeRootNodeGen.Builder builder,
            CanonicalLookup lookup) {
        BytecodeLocal local =
                currentRootFrameLocals.get(lookup.name());
        if (local == null) {
            throw new AssertionError(
                    "admitted scalar local is missing: " + lookup.name());
        }

        builder.beginIfThenElse();
        builder.emitIsCompactLocalFrame();

        builder.beginBlock();
        builder.beginDiscardValue();
        builder.emitLoadLocal(local);
        builder.endDiscardValue();
        builder.endBlock();

        builder.beginBlock();
        builder.beginDiscardValue();
        emitLookup(builder, lookup);
        builder.endDiscardValue();
        builder.endBlock();

        builder.endIfThenElse();
    }

    private static boolean requiresComposedInvocation(
            CanonicalExpression expression) {
        if (expression instanceof CanonicalCall
                || expression instanceof CanonicalSend
                || expression instanceof CanonicalDerivedInequality
                || expression instanceof CanonicalSuperSend
                || expression instanceof CanonicalReturn
                || expression instanceof CanonicalObject
                || expression instanceof CanonicalMapConstruction
                || expression instanceof CanonicalCompose) {
            return true;
        }
        if (expression instanceof CanonicalMember member) {
            return requiresComposedInvocation(member.receiver());
        }
        if (expression instanceof CanonicalIdentity identity) {
            return requiresComposedInvocation(identity.left())
                    || requiresComposedInvocation(identity.right());
        }
        if (expression instanceof CanonicalNotIdentity identity) {
            return requiresComposedInvocation(identity.left())
                    || requiresComposedInvocation(identity.right());
        }
        if (expression instanceof CanonicalCreate
                || expression instanceof CanonicalMultipleCreate
                || expression instanceof CanonicalAssign
                || expression instanceof CanonicalIndexedAssign) {
            return true;
        }
        return false;
    }

    private static boolean hasSpreadArgument(
            java.util.List<CanonicalExpression> arguments) {
        return arguments.stream()
                .anyMatch(CanonicalSpread.class::isInstance);
    }

    private static CanonicalExpression suppliedArgumentExpression(
            CanonicalExpression argument) {
        if (argument instanceof CanonicalSpread spread) {
            return spread.expression();
        }
        return argument;
    }

    private void emitBodySpreadArgumentVector(
            ProtosSemanticBytecodeRootNodeGen.Builder builder,
            java.util.List<CanonicalExpression> arguments,
            BytecodeLocal suppliedVector,
            BytecodeLocal preparedCall,
            BytecodeLocal childResult,
            BytecodeLocal resumeValue) {
        builder.beginStoreLocal(suppliedVector);
        builder.emitCreateSuppliedArgumentVector();
        builder.endStoreLocal();

        for (CanonicalExpression argument : arguments) {
            CanonicalExpression valueExpression =
                    suppliedArgumentExpression(argument);
            BytecodeLocal value =
                    builder.createLocal(
                            argument instanceof CanonicalSpread
                                    ? "spreadArgumentValue"
                                    : "callArgumentValue",
                            null);
            emitBodyExpressionToLocal(
                    builder,
                    valueExpression,
                    value,
                    preparedCall,
                    childResult,
                    resumeValue);

            if (argument instanceof CanonicalSpread) {
                if (currentInlineCallbackFrameNative) {
                    builder.beginAppendInlineSpreadSuppliedArgument(
                            currentRootFrameNativeLocals,
                            currentRootFrameNativeLayout);
                } else {
                    builder.beginAppendSpreadSuppliedArgument();
                }
                builder.emitLoadLocal(suppliedVector);
                builder.emitLoadLocal(value);
                emitSendCallerOperand(builder);
                if (currentInlineCallbackFrameNative) {
                    builder.endAppendInlineSpreadSuppliedArgument();
                } else {
                    builder.endAppendSpreadSuppliedArgument();
                }
            } else {
                builder.beginAppendSuppliedArgument();
                builder.emitLoadLocal(suppliedVector);
                builder.emitLoadLocal(value);
                builder.endAppendSuppliedArgument();
            }
        }
    }

    private void emitDefaultSpreadArgumentVector(
            ProtosSemanticBytecodeRootNodeGen.Builder builder,
            java.util.List<CanonicalExpression> arguments,
            BytecodeLocal suppliedVector,
            BytecodeLocal preparedCall,
            BytecodeLocal childResult,
            BytecodeLocal resumeValue) {
        builder.beginStoreLocal(suppliedVector);
        builder.emitCreateSuppliedArgumentVector();
        builder.endStoreLocal();

        for (CanonicalExpression argument : arguments) {
            CanonicalExpression valueExpression =
                    suppliedArgumentExpression(argument);
            BytecodeLocal value =
                    builder.createLocal(
                            argument instanceof CanonicalSpread
                                    ? "defaultSpreadArgumentValue"
                                    : "defaultArgumentValue",
                            null);
            emitDefaultExpressionToLocal(
                    builder,
                    valueExpression,
                    value,
                    preparedCall,
                    childResult,
                    resumeValue);

            if (argument instanceof CanonicalSpread) {
                if (currentInlineCallbackFrameNative) {
                    builder.beginAppendInlineSpreadSuppliedArgument(
                            currentRootFrameNativeLocals,
                            currentRootFrameNativeLayout);
                } else {
                    builder.beginAppendSpreadSuppliedArgument();
                }
                builder.emitLoadLocal(suppliedVector);
                builder.emitLoadLocal(value);
                emitSendCallerOperand(builder);
                if (currentInlineCallbackFrameNative) {
                    builder.endAppendInlineSpreadSuppliedArgument();
                } else {
                    builder.endAppendSpreadSuppliedArgument();
                }
            } else {
                builder.beginAppendSuppliedArgument();
                builder.emitLoadLocal(suppliedVector);
                builder.emitLoadLocal(value);
                builder.endAppendSuppliedArgument();
            }
        }
    }

    private void emitBodyExpressionToLocal(
            ProtosSemanticBytecodeRootNodeGen.Builder builder,
            CanonicalExpression expression,
            BytecodeLocal target,
            BytecodeLocal preparedCall,
            BytecodeLocal childResult,
            BytecodeLocal resumeValue) {
        if (expression instanceof CanonicalCall call) {
            builder.beginSourceSection(
                    call.span().startOffset(),
                    call.span().length());
            emitComposedCall(
                    builder,
                    call,
                    target,
                    preparedCall,
                    childResult,
                    resumeValue);
            builder.endSourceSection();
            return;
        }
        if (expression instanceof CanonicalSend send) {
            builder.beginSourceSection(
                    send.span().startOffset(),
                    send.span().length());
            emitComposedSend(
                    builder,
                    send,
                    target,
                    preparedCall,
                    childResult,
                    resumeValue);
            builder.endSourceSection();
            return;
        }
        if (expression instanceof CanonicalDerivedInequality inequality) {
            builder.beginSourceSection(
                    inequality.span().startOffset(),
                    inequality.span().length());
            emitBodyDerivedInequality(
                    builder,
                    inequality,
                    target,
                    preparedCall,
                    childResult,
                    resumeValue);
            builder.endSourceSection();
            return;
        }
        if (expression instanceof CanonicalSuperSend send) {
            builder.beginSourceSection(
                    send.span().startOffset(),
                    send.span().length());
            emitComposedSuperSend(
                    builder,
                    send,
                    target,
                    preparedCall,
                    childResult,
                    resumeValue);
            builder.endSourceSection();
            return;
        }
        if (expression instanceof CanonicalMapConstruction map) {
            emitBodyMapConstruction(
                    builder,
                    map,
                    target,
                    preparedCall,
                    childResult,
                    resumeValue);
            return;
        }
        if (expression instanceof CanonicalObject object) {
            emitBodyObjectLiteral(
                    builder,
                    object,
                    target,
                    preparedCall,
                    childResult,
                    resumeValue);
            return;
        }
        if (expression instanceof CanonicalCompose compose) {
            emitBodyCompose(
                    builder,
                    compose,
                    target,
                    preparedCall,
                    childResult,
                    resumeValue);
            return;
        }
        if (expression instanceof CanonicalReturn returnExpression) {
            emitBodyExpressionToLocal(
                    builder,
                    returnExpression.value(),
                    target,
                    preparedCall,
                    childResult,
                    resumeValue);
            builder.beginStoreLocal(target);
            builder.beginRaiseNonLocalReturn();
            emitCurrentActivation(builder);
            builder.emitLoadLocal(target);
            builder.endRaiseNonLocalReturn();
            builder.endStoreLocal();
            return;
        }
        if (expression instanceof CanonicalMember member) {
            BytecodeLocal receiverValue = builder.createLocal("memberReceiver", null);
            emitBodyExpressionToLocal(
                    builder, member.receiver(), receiverValue, preparedCall, childResult, resumeValue);
            builder.beginStoreLocal(target);
            beginMemberRead(builder, member.name());
            builder.emitLoadLocal(receiverValue);
            endMemberRead(builder);
            builder.endStoreLocal();
            return;
        }
        if (expression instanceof CanonicalIdentity identity) {
            BytecodeLocal leftValue = builder.createLocal("identityLeft", null);
            BytecodeLocal rightValue = builder.createLocal("identityRight", null);
            emitBodyExpressionToLocal(
                    builder, identity.left(), leftValue, preparedCall, childResult, resumeValue);
            emitBodyExpressionToLocal(
                    builder, identity.right(), rightValue, preparedCall, childResult, resumeValue);
            builder.beginStoreLocal(target);
            builder.beginIdentity();
            builder.emitLoadLocal(leftValue);
            builder.emitLoadLocal(rightValue);
            builder.endIdentity();
            builder.endStoreLocal();
            return;
        }
        if (expression instanceof CanonicalNotIdentity identity) {
            BytecodeLocal leftValue = builder.createLocal("notIdentityLeft", null);
            BytecodeLocal rightValue = builder.createLocal("notIdentityRight", null);
            emitBodyExpressionToLocal(
                    builder, identity.left(), leftValue, preparedCall, childResult, resumeValue);
            emitBodyExpressionToLocal(
                    builder, identity.right(), rightValue, preparedCall, childResult, resumeValue);
            builder.beginStoreLocal(target);
            builder.beginNotIdentity();
            builder.emitLoadLocal(leftValue);
            builder.emitLoadLocal(rightValue);
            builder.endNotIdentity();
            builder.endStoreLocal();
            return;
        }
        if (expression instanceof CanonicalCreate create) {
            emitBodyCreate(
                    builder, create, target, preparedCall, childResult, resumeValue);
            return;
        }
        if (expression instanceof CanonicalMultipleCreate create) {
            emitBodyMultipleCreate(
                    builder, create, target, preparedCall, childResult, resumeValue);
            return;
        }
        if (expression instanceof CanonicalAssign assign) {
            emitBodyAssign(
                    builder, assign, target, preparedCall, childResult, resumeValue);
            return;
        }
        if (expression instanceof CanonicalIndexedAssign indexedAssign) {
            emitBodyIndexedAssign(
                    builder, indexedAssign, target, preparedCall, childResult, resumeValue);
            return;
        }
        if (expression instanceof CanonicalLookup lookup
                && isScalarLocalRead(lookup)) {
            // PERF034-E: an admitted scalar RHS re-checks compactness itself.
            emitScalarLocalReadToResult(builder, lookup, target);
            return;
        }
        builder.beginStoreLocal(target);
        emitExpression(builder, expression);
        builder.endStoreLocal();
    }

    private void emitDefaultExpressionToLocal(
            ProtosSemanticBytecodeRootNodeGen.Builder builder,
            CanonicalExpression expression,
            BytecodeLocal target,
            BytecodeLocal preparedCall,
            BytecodeLocal childResult,
            BytecodeLocal resumeValue) {
        if (expression instanceof CanonicalCall call) {
            emitComposedDefaultCall(
                    builder,
                    call,
                    target,
                    preparedCall,
                    childResult,
                    resumeValue);
            return;
        }
        if (expression instanceof CanonicalSend send) {
            emitComposedDefaultSend(
                    builder,
                    send,
                    target,
                    preparedCall,
                    childResult,
                    resumeValue);
            return;
        }
        if (expression instanceof CanonicalDerivedInequality inequality) {
            emitDefaultDerivedInequality(
                    builder,
                    inequality,
                    target,
                    preparedCall,
                    childResult,
                    resumeValue);
            return;
        }
        if (expression instanceof CanonicalSuperSend send) {
            emitComposedDefaultSuperSend(
                    builder,
                    send,
                    target,
                    preparedCall,
                    childResult,
                    resumeValue);
            return;
        }
        if (expression instanceof CanonicalMapConstruction map) {
            emitDefaultMapConstruction(
                    builder,
                    map,
                    target,
                    preparedCall,
                    childResult,
                    resumeValue);
            return;
        }
        if (expression instanceof CanonicalObject object) {
            emitDefaultObjectLiteral(
                    builder,
                    object,
                    target,
                    preparedCall,
                    childResult,
                    resumeValue);
            return;
        }
        if (expression instanceof CanonicalReturn returnExpression) {
            emitDefaultExpressionToLocal(
                    builder,
                    returnExpression.value(),
                    target,
                    preparedCall,
                    childResult,
                    resumeValue);
            builder.beginStoreLocal(target);
            builder.beginRaiseNonLocalReturn();
            emitCurrentActivation(builder);
            builder.emitLoadLocal(target);
            builder.endRaiseNonLocalReturn();
            builder.endStoreLocal();
            return;
        }
        if (expression instanceof CanonicalMember member) {
            BytecodeLocal receiverValue = builder.createLocal("defaultMemberReceiver", null);
            emitDefaultExpressionToLocal(
                    builder, member.receiver(), receiverValue, preparedCall, childResult, resumeValue);
            builder.beginStoreLocal(target);
            beginMemberRead(builder, member.name());
            builder.emitLoadLocal(receiverValue);
            endMemberRead(builder);
            builder.endStoreLocal();
            return;
        }
        if (expression instanceof CanonicalIdentity identity) {
            BytecodeLocal leftValue = builder.createLocal("defaultIdentityLeft", null);
            BytecodeLocal rightValue = builder.createLocal("defaultIdentityRight", null);
            emitDefaultExpressionToLocal(
                    builder, identity.left(), leftValue, preparedCall, childResult, resumeValue);
            emitDefaultExpressionToLocal(
                    builder, identity.right(), rightValue, preparedCall, childResult, resumeValue);
            builder.beginStoreLocal(target);
            builder.beginIdentity();
            builder.emitLoadLocal(leftValue);
            builder.emitLoadLocal(rightValue);
            builder.endIdentity();
            builder.endStoreLocal();
            return;
        }
        if (expression instanceof CanonicalNotIdentity identity) {
            BytecodeLocal leftValue = builder.createLocal("defaultNotIdentityLeft", null);
            BytecodeLocal rightValue = builder.createLocal("defaultNotIdentityRight", null);
            emitDefaultExpressionToLocal(
                    builder, identity.left(), leftValue, preparedCall, childResult, resumeValue);
            emitDefaultExpressionToLocal(
                    builder, identity.right(), rightValue, preparedCall, childResult, resumeValue);
            builder.beginStoreLocal(target);
            builder.beginNotIdentity();
            builder.emitLoadLocal(leftValue);
            builder.emitLoadLocal(rightValue);
            builder.endNotIdentity();
            builder.endStoreLocal();
            return;
        }
        if (expression instanceof CanonicalCreate create) {
            emitDefaultCreate(
                    builder, create, target, preparedCall, childResult, resumeValue);
            return;
        }
        if (expression instanceof CanonicalMultipleCreate create) {
            emitDefaultMultipleCreate(
                    builder, create, target, preparedCall, childResult, resumeValue);
            return;
        }
        if (expression instanceof CanonicalAssign assign) {
            emitDefaultAssign(
                    builder, assign, target, preparedCall, childResult, resumeValue);
            return;
        }
        if (expression instanceof CanonicalIndexedAssign indexedAssign) {
            emitDefaultIndexedAssign(
                    builder, indexedAssign, target, preparedCall, childResult, resumeValue);
            return;
        }
        builder.beginStoreLocal(target);
        emitExpression(builder, expression);
        builder.endStoreLocal();
    }


    private void emitBodyDerivedInequality(
            ProtosSemanticBytecodeRootNodeGen.Builder builder,
            CanonicalDerivedInequality inequality,
            BytecodeLocal target,
            BytecodeLocal preparedCall,
            BytecodeLocal childResult,
            BytecodeLocal resumeValue) {
        BytecodeLocal equalityResult =
                builder.createLocal("derivedInequalityEqualityResult", null);

        CanonicalSend equalitySend =
                new CanonicalSend(
                        inequality.left(),
                        "==",
                        java.util.List.of(inequality.right()),
                        inequality.span());

        emitComposedSend(
                builder,
                equalitySend,
                equalityResult,
                preparedCall,
                childResult,
                resumeValue);

        builder.beginStoreLocal(target);
        builder.beginComplementEqualityResult();
        emitCurrentActivation(builder);
        builder.emitLoadLocal(equalityResult);
        builder.endComplementEqualityResult();
        builder.endStoreLocal();
    }

    private void emitDefaultDerivedInequality(
            ProtosSemanticBytecodeRootNodeGen.Builder builder,
            CanonicalDerivedInequality inequality,
            BytecodeLocal target,
            BytecodeLocal preparedCall,
            BytecodeLocal childResult,
            BytecodeLocal resumeValue) {
        BytecodeLocal equalityResult =
                builder.createLocal(
                        "defaultDerivedInequalityEqualityResult",
                        null);

        CanonicalSend equalitySend =
                new CanonicalSend(
                        inequality.left(),
                        "==",
                        java.util.List.of(inequality.right()),
                        inequality.span());

        emitComposedDefaultSend(
                builder,
                equalitySend,
                equalityResult,
                preparedCall,
                childResult,
                resumeValue);

        builder.beginStoreLocal(target);
        builder.beginComplementEqualityResult();
        emitCurrentActivation(builder);
        builder.emitLoadLocal(equalityResult);
        builder.endComplementEqualityResult();
        builder.endStoreLocal();
    }

    private void emitBodyMapConstruction(
            ProtosSemanticBytecodeRootNodeGen.Builder builder,
            CanonicalMapConstruction map,
            BytecodeLocal result,
            BytecodeLocal preparedCall,
            BytecodeLocal childResult,
            BytecodeLocal resumeValue) {
        emitMapConstruction(
                builder,
                map,
                result,
                preparedCall,
                childResult,
                resumeValue,
                false);
    }

    private void emitDefaultMapConstruction(
            ProtosSemanticBytecodeRootNodeGen.Builder builder,
            CanonicalMapConstruction map,
            BytecodeLocal result,
            BytecodeLocal preparedCall,
            BytecodeLocal childResult,
            BytecodeLocal resumeValue) {
        emitMapConstruction(
                builder,
                map,
                result,
                preparedCall,
                childResult,
                resumeValue,
                true);
    }

    private void emitMapConstruction(
            ProtosSemanticBytecodeRootNodeGen.Builder builder,
            CanonicalMapConstruction map,
            BytecodeLocal result,
            BytecodeLocal preparedCall,
            BytecodeLocal childResult,
            BytecodeLocal resumeValue,
            boolean defaultContext) {
        requireDefaultScratch(
                result,
                preparedCall,
                childResult,
                resumeValue);

        BytecodeLocal factoryValue =
                builder.createLocal(
                        defaultContext
                                ? "defaultMapConstructionFactory"
                                : "mapConstructionFactory",
                        null);
        BytecodeLocal mapValue =
                builder.createLocal(
                        defaultContext
                                ? "defaultMapConstructionValue"
                                : "mapConstructionValue",
                        null);
        BytecodeLocal factoryResult =
                builder.createLocal(
                        defaultContext
                                ? "defaultMapConstructionFactoryResult"
                                : "mapConstructionFactoryResult",
                        null);

        if (defaultContext) {
            emitDefaultExpressionToLocal(
                    builder,
                    map.factory(),
                    factoryValue,
                    preparedCall,
                    childResult,
                    resumeValue);
        } else {
            emitBodyExpressionToLocal(
                    builder,
                    map.factory(),
                    factoryValue,
                    preparedCall,
                    childResult,
                    resumeValue);
        }

        builder.beginStoreLocal(preparedCall);
        builder.beginPrepareMapConstructionFactoryCall();
        emitCurrentActivation(builder);
        builder.emitLoadLocal(factoryValue);
        builder.endPrepareMapConstructionFactoryCall();
        builder.endStoreLocal();

        emitPreparedInvocation(
                builder,
                factoryResult,
                preparedCall,
                childResult,
                resumeValue);

        builder.beginStoreLocal(mapValue);
        builder.beginFinishMapConstructionFactoryCall();
        emitCurrentActivation(builder);
        builder.emitLoadLocal(factoryResult);
        builder.endFinishMapConstructionFactoryCall();
        builder.endStoreLocal();

        for (CanonicalMapConstruction.Entry entry : map.entries()) {
            BytecodeLocal key =
                    builder.createLocal(
                            defaultContext
                                    ? "defaultMapConstructionKey"
                                    : "mapConstructionKey",
                            null);
            BytecodeLocal value =
                    builder.createLocal(
                            defaultContext
                                    ? "defaultMapConstructionEntryValue"
                                    : "mapConstructionEntryValue",
                            null);
            BytecodeLocal initialDefinition =
                    builder.createLocal(
                            defaultContext
                                    ? "defaultMapInitialDefinition"
                                    : "mapInitialDefinition",
                            null);
            BytecodeLocal callbackResult =
                    builder.createLocal(
                            defaultContext
                                    ? "defaultMapInitialDefinitionCallbackResult"
                                    : "mapInitialDefinitionCallbackResult",
                            null);

            if (defaultContext) {
                emitDefaultExpressionToLocal(
                        builder,
                        entry.key(),
                        key,
                        preparedCall,
                        childResult,
                        resumeValue);
                emitDefaultExpressionToLocal(
                        builder,
                        entry.value(),
                        value,
                        preparedCall,
                        childResult,
                        resumeValue);
            } else {
                emitBodyExpressionToLocal(
                        builder,
                        entry.key(),
                        key,
                        preparedCall,
                        childResult,
                        resumeValue);
                emitBodyExpressionToLocal(
                        builder,
                        entry.value(),
                        value,
                        preparedCall,
                        childResult,
                        resumeValue);
            }

            builder.beginStoreLocal(initialDefinition);
            builder.beginPrepareMapInitialDefinition();
            emitCurrentActivation(builder);
            builder.emitLoadLocal(mapValue);
            builder.emitLoadLocal(key);
            builder.emitLoadLocal(value);
            builder.endPrepareMapInitialDefinition();
            builder.endStoreLocal();

            builder.beginEnterMapInitialDefinitionComparison();
            builder.emitLoadLocal(initialDefinition);
            builder.endEnterMapInitialDefinitionComparison();

            builder.beginTryFinally(
                    () -> {
                        builder.beginLeaveMapInitialDefinitionComparison();
                        builder.emitLoadLocal(initialDefinition);
                        builder.endLeaveMapInitialDefinitionComparison();
                    });

            builder.beginBlock();

            builder.beginStoreLocal(preparedCall);
            builder.beginPrepareMapInitialDefinitionHashCall();
            builder.emitLoadLocal(initialDefinition);
            builder.endPrepareMapInitialDefinitionHashCall();
            builder.endStoreLocal();

            emitPreparedInvocation(
                    builder,
                    callbackResult,
                    preparedCall,
                    childResult,
                    resumeValue);

            builder.endBlock();
            builder.endTryFinally();

            builder.beginAcceptMapInitialDefinitionHashResult();
            builder.emitLoadLocal(initialDefinition);
            builder.emitLoadLocal(callbackResult);
            builder.endAcceptMapInitialDefinitionHashResult();

            builder.beginWhile();

            builder.beginMapInitialDefinitionNeedsEquality();
            builder.emitLoadLocal(initialDefinition);
            builder.endMapInitialDefinitionNeedsEquality();

            builder.beginBlock();

            builder.beginEnterMapInitialDefinitionComparison();
            builder.emitLoadLocal(initialDefinition);
            builder.endEnterMapInitialDefinitionComparison();

            builder.beginTryFinally(
                    () -> {
                        builder.beginLeaveMapInitialDefinitionComparison();
                        builder.emitLoadLocal(initialDefinition);
                        builder.endLeaveMapInitialDefinitionComparison();
                    });

            builder.beginBlock();

            builder.beginStoreLocal(preparedCall);
            builder.beginPrepareMapInitialDefinitionEqualityCall();
            builder.emitLoadLocal(initialDefinition);
            builder.endPrepareMapInitialDefinitionEqualityCall();
            builder.endStoreLocal();

            emitPreparedInvocation(
                    builder,
                    callbackResult,
                    preparedCall,
                    childResult,
                    resumeValue);

            builder.endBlock();
            builder.endTryFinally();

            builder.beginAcceptMapInitialDefinitionEqualityResult();
            builder.emitLoadLocal(initialDefinition);
            builder.emitLoadLocal(callbackResult);
            builder.endAcceptMapInitialDefinitionEqualityResult();

            builder.endBlock();
            builder.endWhile();

            builder.beginFinishMapInitialDefinition();
            builder.emitLoadLocal(initialDefinition);
            builder.endFinishMapInitialDefinition();
        }

        builder.beginStoreLocal(result);
        builder.emitLoadLocal(mapValue);
        builder.endStoreLocal();
    }

    private void emitBodySequenceToLocal(
            ProtosSemanticBytecodeRootNodeGen.Builder builder,
            CanonicalSequence sequence,
            BytecodeLocal result,
            BytecodeLocal preparedCall,
            BytecodeLocal childResult,
            BytecodeLocal resumeValue) {
        if (sequence.expressions().isEmpty()) {
            builder.beginStoreLocal(result);
            builder.emitLoadConstant(ProtosNullValue.INSTANCE);
            builder.endStoreLocal();
            return;
        }

        for (CanonicalExpression expression : sequence.expressions()) {
            SourceSpan span = expression.span();
            builder.beginSourceSection(span.startOffset(), span.length());
            builder.beginTag(
                    StandardTags.StatementTag.class,
                    StandardTags.ExpressionTag.class);
            builder.beginBlock();
            emitBodyExpressionToLocal(
                    builder,
                    expression,
                    result,
                    preparedCall,
                    childResult,
                    resumeValue);
            builder.endBlock();
            builder.endTag(
                    StandardTags.StatementTag.class,
                    StandardTags.ExpressionTag.class);
            builder.endSourceSection();
        }
    }

    private static void emitLocalNoop(
            ProtosSemanticBytecodeRootNodeGen.Builder builder,
            BytecodeLocal local) {
        builder.beginStoreLocal(local);
        builder.emitLoadLocal(local);
        builder.endStoreLocal();
    }

    private void emitBodyObjectLiteral(
            ProtosSemanticBytecodeRootNodeGen.Builder builder,
            CanonicalObject object,
            BytecodeLocal result,
            BytecodeLocal preparedCall,
            BytecodeLocal childResult,
            BytecodeLocal resumeValue) {
        requireDefaultScratch(result, preparedCall, childResult, resumeValue);
        BytecodeLocal parent = builder.createLocal("objectParent", null);

        if (object.parent().isPresent()) {
            emitBodyExpressionToLocal(
                    builder,
                    object.parent().orElseThrow(),
                    parent,
                    preparedCall,
                    childResult,
                    resumeValue);
        } else {
            builder.beginStoreLocal(parent);
            builder.emitLoadConstant(ProtosObjectValue.rootObject());
            builder.endStoreLocal();
        }

        emitInlineObjectConstruction(builder, object, parent, result);
    }

    private void emitDefaultObjectLiteral(
            ProtosSemanticBytecodeRootNodeGen.Builder builder,
            CanonicalObject object,
            BytecodeLocal result,
            BytecodeLocal preparedCall,
            BytecodeLocal childResult,
            BytecodeLocal resumeValue) {
        requireDefaultScratch(result, preparedCall, childResult, resumeValue);
        BytecodeLocal parent = builder.createLocal("defaultObjectParent", null);

        if (object.parent().isPresent()) {
            emitDefaultExpressionToLocal(
                    builder,
                    object.parent().orElseThrow(),
                    parent,
                    preparedCall,
                    childResult,
                    resumeValue);
        } else {
            builder.beginStoreLocal(parent);
            builder.emitLoadConstant(ProtosObjectValue.rootObject());
            builder.endStoreLocal();
        }

        emitInlineObjectConstruction(builder, object, parent, result);
    }

    private void emitBodyCompose(
            ProtosSemanticBytecodeRootNodeGen.Builder builder,
            CanonicalCompose compose,
            BytecodeLocal result,
            BytecodeLocal preparedCall,
            BytecodeLocal childResult,
            BytecodeLocal resumeValue) {
        BytecodeLocal sourceValue = builder.createLocal("compositionSource", null);
        emitBodyExpressionToLocal(
                builder,
                compose.object(),
                sourceValue,
                preparedCall,
                childResult,
                resumeValue);
        builder.beginStoreLocal(result);
        builder.beginComposeLocalSlots();
        emitCurrentActivation(builder);
        builder.emitLoadLocal(sourceValue);
        builder.emitLoadConstant(
                new ProtosBytecodeRootNode.ComposeReservedNames(composeReservedNames(compose)));
        builder.endComposeLocalSlots();
        builder.endStoreLocal();
    }

    /**
     * PLAT041 C′ inline object construction. The object body is an ordinary
     * resumable region of the enclosing root, not a physical root: the
     * constructed object and its construction activation live in Bytecode
     * locals, so any suspension inside the body is captured by the enclosing
     * root's own continuation, and Error/ensure/non-local-return transfers
     * propagate through the enclosing root exactly as from any other
     * operation (the construction activation inherits the enclosing Task or
     * dynamic-control authority and homes; see {@link
     * com.guillermomolina.protos.runtime.ProtosActivation#forObjectConstruction}).
     *
     * <p>The body is not a lexical execution context: it receives no frame
     * lexical authority and never takes the direct-local fast path, and its
     * binding-analysis selection is exactly that of a non-genuine root, so
     * Closures nested inside it resolve and capture exactly as before.
     */
    private void emitInlineObjectConstruction(
            ProtosSemanticBytecodeRootNodeGen.Builder builder,
            CanonicalObject object,
            BytecodeLocal parent,
            BytecodeLocal result) {
        BytecodeLocal constructed = builder.createLocal("constructedObject", null);
        BytecodeLocal constructionActivation =
                builder.createLocal("constructionActivation", null);

        builder.beginStoreLocal(constructed);
        builder.beginNewConstructedObject();
        builder.emitLoadLocal(parent);
        builder.endNewConstructedObject();
        builder.endStoreLocal();

        builder.beginStoreLocal(constructionActivation);
        builder.beginNewObjectConstructionActivation();
        emitCurrentActivation(builder);
        builder.emitLoadLocal(constructed);
        builder.endNewObjectConstructionActivation();
        builder.endStoreLocal();

        validateSpan(object.body().span());
        /*
         * Mirrors exactly the binding-analysis selection the former object
         * body root performed (it may lazily establish the module analysis
         * that Closure roots nested in object bodies resolve against).
         */
        bindingAnalysisFor(object.body(), null);

        if (!object.body().expressions().isEmpty()) {
            CanonicalBindingAnalysis savedAnalysis = currentRootAnalysis;
            CanonicalLexicalScope savedTopScope = currentRootTopScope;
            java.util.Map<String, BytecodeLocal> savedFrameLocals =
                    currentRootFrameLocals;
            BytecodeLocal savedActivationLocal = currentActivationLocal;
            boolean savedActivationLocalIsInlineCallbackCall =
                    currentActivationLocalIsInlineCallbackCall;
            boolean savedInlineCallbackFrameNative =
                    currentInlineCallbackFrameNative;
            try {
                currentRootAnalysis = null;
                currentRootTopScope = null;
                currentRootFrameLocals = java.util.Map.of();
                currentActivationLocal = constructionActivation;
                currentActivationLocalIsInlineCallbackCall = false;
                currentInlineCallbackFrameNative = false;
                BytecodeLocal bodyResult =
                        builder.createLocal("objectBodyResult", null);
                emitStatementsToLocal(builder, object.body(), bodyResult);
            } finally {
                currentRootAnalysis = savedAnalysis;
                currentRootTopScope = savedTopScope;
                currentRootFrameLocals = savedFrameLocals;
                currentActivationLocal = savedActivationLocal;
                currentActivationLocalIsInlineCallbackCall =
                        savedActivationLocalIsInlineCallbackCall;
                currentInlineCallbackFrameNative =
                        savedInlineCallbackFrameNative;
            }
        }

        builder.beginStoreLocal(result);
        builder.emitLoadLocal(constructed);
        builder.endStoreLocal();
    }

    private void emitBodyCreate(
            ProtosSemanticBytecodeRootNodeGen.Builder builder,
            CanonicalCreate create,
            BytecodeLocal result,
            BytecodeLocal preparedCall,
            BytecodeLocal childResult,
            BytecodeLocal resumeValue) {
        boolean scalarCreate =
                create.target().isEmpty()
                        && currentActivationLocal == null
                        && currentRootScalarLocals.contains(create.name());
        if (scalarCreate
                && create.value() instanceof CanonicalLiteral literal) {
            // PERF034-E: a literal is its own operand; no createValue local.
            emitScalarCreate(
                    builder,
                    create,
                    result,
                    () -> emitLiteral(builder, literal));
            return;
        }
        BytecodeLocal value = builder.createLocal("createValue", null);
        if (create.target().isEmpty()) {
            /*
             * I091: a frame-native creation may retain a primitive carrier
             * from the guarded numeric chain; its own value is a guest value.
             */
            boolean carrierCreate =
                    !scalarCreate
                            && frameNativeOrdinal(create.name()) >= 0
                            && primitiveNumericCarrierValue(create.value());
            if (carrierCreate) {
                emitPrimitiveNumericOperandToLocal(
                        builder, create.value(), value, preparedCall, childResult, resumeValue);
                builder.beginStoreLocal(result);
                builder.beginMaterializePrimitiveNumericCarrier();
                emitCreateCurrentBinding(builder, create, value);
                builder.emitLoadConstant(true);
                builder.endMaterializePrimitiveNumericCarrier();
                builder.endStoreLocal();
                return;
            }
            emitBodyExpressionToLocal(
                    builder, create.value(), value, preparedCall, childResult, resumeValue);

            if (scalarCreate) {
                emitScalarCreate(
                        builder,
                        create,
                        result,
                        () -> builder.emitLoadLocal(value));
                return;
            }

            builder.beginStoreLocal(result);
            emitCreateCurrentBinding(builder, create, value);
            builder.endStoreLocal();
            return;
        }

        BytecodeLocal rawTarget = builder.createLocal("createRawTarget", null);
        BytecodeLocal mutationTarget = builder.createLocal("createMutationTarget", null);
        emitBodyExpressionToLocal(
                builder,
                create.target().orElseThrow(),
                rawTarget,
                preparedCall,
                childResult,
                resumeValue);
        builder.beginStoreLocal(mutationTarget);
        builder.beginRequireObjectMutationTarget();
        emitCurrentActivation(builder);
        builder.emitLoadLocal(rawTarget);
        builder.endRequireObjectMutationTarget();
        builder.endStoreLocal();
        emitBodyExpressionToLocal(
                builder, create.value(), value, preparedCall, childResult, resumeValue);
        builder.beginStoreLocal(result);
        builder.beginCreateLocalSlot();
        emitCurrentActivation(builder);
        builder.emitLoadLocal(mutationTarget);
        builder.emitLoadConstant(create.name());
        builder.emitLoadLocal(value);
        builder.endCreateLocalSlot();
        builder.endStoreLocal();
    }

    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    private static String[] multipleCreateNamesConstant(java.util.List<String> names) {
        Objects.requireNonNull(names, "names");
        String[] result = new String[names.size()];
        for (int index = 0; index < result.length; index++) {
            result[index] = Objects.requireNonNull(names.get(index), "names[" + index + "]");
        }
        return result;
    }

    private void emitBodyMultipleCreate(
            ProtosSemanticBytecodeRootNodeGen.Builder builder,
            CanonicalMultipleCreate create,
            BytecodeLocal result,
            BytecodeLocal preparedCall,
            BytecodeLocal childResult,
            BytecodeLocal resumeValue) {
        BytecodeLocal source = builder.createLocal("multipleCreateSource", null);

        emitBodyExpressionToLocal(
                builder,
                create.value(),
                source,
                preparedCall,
                childResult,
                resumeValue);

        builder.beginStoreLocal(result);
        emitMultipleCreateCurrentBindings(builder, create.names(), source);
        builder.endStoreLocal();
    }

    private void emitBodyAssign(
            ProtosSemanticBytecodeRootNodeGen.Builder builder,
            CanonicalAssign assign,
            BytecodeLocal result,
            BytecodeLocal preparedCall,
            BytecodeLocal childResult,
            BytecodeLocal resumeValue) {
        if (isScalarLocalAssign(assign)) {
            emitScalarAssign(
                    builder, assign, result, preparedCall, childResult, resumeValue);
            return;
        }
        emitResolvedBodyAssign(
                builder, assign, result, preparedCall, childResult, resumeValue);
    }

    /**
     * PERF036-A: whether {@code assign} is an admitted scalar assignment of
     * the root being lowered (outside any inline activation region). Within
     * an admitted root every bare assignment was proven by {@link
     * #scalarLocalNamesForRoot}.
     */
    private boolean isScalarLocalAssign(CanonicalAssign assign) {
        return assign.target().isEmpty()
                && currentActivationLocal == null
                && currentRootScalarLocals.contains(assign.name());
    }

    /**
     * PERF036-A: one admitted scalar assignment with its own compact check.
     * The compact lane's context is unobserved (hence OPEN), its destination
     * is PRESENT, and its RHS is effect-free, so writing the builtin local
     * directly is indistinguishable from selecting the destination first;
     * the assignment yields the exact RHS value. An observed activation
     * instead takes the unchanged authoritative assignment.
     */
    private void emitScalarAssign(
            ProtosSemanticBytecodeRootNodeGen.Builder builder,
            CanonicalAssign assign,
            BytecodeLocal result,
            BytecodeLocal preparedCall,
            BytecodeLocal childResult,
            BytecodeLocal resumeValue) {
        BytecodeLocal local = currentRootFrameLocals.get(assign.name());
        if (local == null) {
            throw new AssertionError(
                    "admitted scalar assignment is missing: " + assign.name());
        }

        builder.beginIfThenElse();
        builder.emitIsCompactLocalFrame();

        builder.beginBlock();
        builder.beginStoreLocal(local);
        if (assign.value() instanceof CanonicalLiteral literal) {
            emitLiteral(builder, literal);
        } else if (assign.value() instanceof CanonicalLookup read
                && isScalarLocalRead(read)) {
            builder.emitLoadLocal(currentRootFrameLocals.get(read.name()));
        } else {
            throw new AssertionError(
                    "admitted scalar assignment has an unproven value: "
                            + assign.value().getClass().getSimpleName());
        }
        builder.endStoreLocal();
        builder.beginStoreLocal(result);
        builder.emitLoadLocal(local);
        builder.endStoreLocal();
        builder.endBlock();

        builder.beginBlock();
        emitResolvedBodyAssign(
                builder, assign, result, preparedCall, childResult, resumeValue);
        builder.endBlock();

        builder.endIfThenElse();
    }

    private void emitResolvedBodyAssign(
            ProtosSemanticBytecodeRootNodeGen.Builder builder,
            CanonicalAssign assign,
            BytecodeLocal result,
            BytecodeLocal preparedCall,
            BytecodeLocal childResult,
            BytecodeLocal resumeValue) {
        BytecodeLocal value = builder.createLocal("assignValue", null);
        BytecodeLocal mutationTarget = builder.createLocal("assignMutationTarget", null);
        java.util.Optional<CanonicalBindingResolution.CapturedResolved> capturedResolution =
                capturedResolvedAssignment(assign);
        BytecodeLocal capturedOwnerLocal =
                capturedResolution.map(this::capturedOwnerBytecodeLocal).orElse(null);
        BytecodeLocal currentFrameLocal = currentResolvedAssignmentBytecodeLocal(assign);

        if (assign.target().isPresent()) {
            BytecodeLocal rawTarget = builder.createLocal("assignRawTarget", null);
            emitBodyExpressionToLocal(
                    builder,
                    assign.target().orElseThrow(),
                    rawTarget,
                    preparedCall,
                    childResult,
                    resumeValue);
            builder.beginStoreLocal(mutationTarget);
            builder.beginRequireObjectMutationTarget();
            emitCurrentActivation(builder);
            builder.emitLoadLocal(rawTarget);
            builder.endRequireObjectMutationTarget();
            builder.endStoreLocal();
        } else if (capturedResolution.isPresent()) {
            CanonicalBindingResolution.CapturedResolved captured =
                    capturedResolution.orElseThrow();
            /*
             * Critical ordering invariant: resolve and retain the exact
             * destination before the RHS is evaluated.
             */
            builder.beginStoreLocal(mutationTarget);
            emitResolveCapturedWriteTarget(builder, assign, captured, capturedOwnerLocal);
            builder.endStoreLocal();
        } else if (currentFrameLocal != null) {
            /* PERF028-A: select (and retain) the destination before RHS evaluation. */
            builder.beginStoreLocal(mutationTarget);
            if (currentActivationLocal == null) {
                builder.beginResolveRootFrameLocalWriteTarget(currentFrameLocal);
                builder.emitLoadConstant(assign.name());
                builder.endResolveRootFrameLocalWriteTarget();
            } else if (currentInlineCallbackFrameNative) {
                builder.beginResolveInlineFrameLocalWriteTarget(
                        currentFrameLocal,
                        currentRootFrameNativeLocals,
                        currentRootFrameNativeLayout);
                emitCurrentInlineCallbackCall(builder);
                builder.emitLoadConstant(assign.name());
                builder.endResolveInlineFrameLocalWriteTarget();
            } else {
                builder.beginResolveCurrentFrameLocalWriteTarget(currentFrameLocal);
                emitCurrentActivation(builder);
                builder.emitLoadConstant(assign.name());
                builder.endResolveCurrentFrameLocalWriteTarget();
            }
            builder.endStoreLocal();
        } else {
            /* AST authority resolves the writable lexical destination before RHS evaluation. */
            builder.beginStoreLocal(mutationTarget);
            builder.beginResolveWritableLexicalTarget();
            emitCurrentActivation(builder);
            builder.emitLoadConstant(assign.name());
            builder.endResolveWritableLexicalTarget();
            builder.endStoreLocal();
        }

        /*
         * I091: a bare lexical assignment may retain a primitive carrier from
         * the guarded numeric chain. Only direct frame storage keeps it (every
         * other destination materializes); the assignment's own value is
         * always a guest value.
         */
        boolean carrierAssignment =
                assign.target().isEmpty()
                        && (capturedResolution.isPresent() || currentFrameLocal != null)
                        && primitiveNumericCarrierValue(assign.value());
        if (carrierAssignment) {
            emitPrimitiveNumericOperandToLocal(
                    builder, assign.value(), value, preparedCall, childResult, resumeValue);
        } else {
            emitBodyExpressionToLocal(
                    builder, assign.value(), value, preparedCall, childResult, resumeValue);
        }
        builder.beginStoreLocal(result);
        if (carrierAssignment) {
            builder.beginMaterializePrimitiveNumericCarrier();
        }
        if (capturedResolution.isPresent()) {
            emitAssignCaptured(
                    builder,
                    assign,
                    capturedResolution.orElseThrow(),
                    capturedOwnerLocal,
                    mutationTarget,
                    value);
        } else if (assign.target().isPresent()) {
            builder.beginAssignLocalSlot();
            emitCurrentActivation(builder);
            builder.emitLoadLocal(mutationTarget);
            builder.emitLoadConstant(assign.name());
            builder.emitLoadLocal(value);
            builder.endAssignLocalSlot();
        } else if (currentFrameLocal != null) {
            if (currentActivationLocal == null) {
                builder.beginAssignRootFrameLocal(currentFrameLocal);
                builder.emitLoadLocal(mutationTarget);
                builder.emitLoadConstant(assign.name());
                builder.emitLoadLocal(value);
                builder.endAssignRootFrameLocal();
            } else if (currentInlineCallbackFrameNative) {
                builder.beginAssignInlineFrameLocal(
                        currentFrameLocal,
                        currentRootFrameNativeLocals,
                        currentRootFrameNativeLayout);
                emitCurrentInlineCallbackCall(builder);
                builder.emitLoadLocal(mutationTarget);
                builder.emitLoadConstant(assign.name());
                builder.emitLoadLocal(value);
                builder.endAssignInlineFrameLocal();
            } else {
                builder.beginAssignCurrentFrameLocal(currentFrameLocal);
                emitCurrentActivation(builder);
                builder.emitLoadLocal(mutationTarget);
                builder.emitLoadConstant(assign.name());
                builder.emitLoadLocal(value);
                builder.endAssignCurrentFrameLocal();
            }
        } else {
            builder.beginAssignResolvedLexicalTarget();
            emitCurrentActivation(builder);
            builder.emitLoadLocal(mutationTarget);
            builder.emitLoadConstant(assign.name());
            builder.emitLoadLocal(value);
            builder.endAssignResolvedLexicalTarget();
        }
        if (carrierAssignment) {
            builder.emitLoadConstant(true);
            builder.endMaterializePrimitiveNumericCarrier();
        }
        builder.endStoreLocal();
    }

    private void emitBodyIndexedAssign(
            ProtosSemanticBytecodeRootNodeGen.Builder builder,
            CanonicalIndexedAssign indexedAssign,
            BytecodeLocal result,
            BytecodeLocal preparedCall,
            BytecodeLocal childResult,
            BytecodeLocal resumeValue) {
        BytecodeLocal receiver = builder.createLocal("indexedAssignReceiver", null);
        BytecodeLocal index = builder.createLocal("indexedAssignIndex", null);
        BytecodeLocal value = builder.createLocal("indexedAssignValue", null);
        BytecodeLocal dispatchResult = builder.createLocal("indexedAssignDispatchResult", null);

        emitBodyExpressionToLocal(
                builder,
                indexedAssign.receiver(),
                receiver,
                preparedCall,
                childResult,
                resumeValue);
        emitBodyExpressionToLocal(
                builder,
                indexedAssign.index(),
                index,
                preparedCall,
                childResult,
                resumeValue);
        emitBodyExpressionToLocal(
                builder,
                indexedAssign.value(),
                value,
                preparedCall,
                childResult,
                resumeValue);

        builder.beginStoreLocal(preparedCall);
        beginPrepareSend(builder, 2);
        builder.emitLoadLocal(receiver);
        builder.emitLoadConstant("atPut");
        emitSendCallerOperand(builder);
        builder.emitLoadLocal(index);
        builder.emitLoadLocal(value);
        endPrepareSend(builder, 2);
        builder.endStoreLocal();
        emitPreparedInvocation(
                builder,
                dispatchResult,
                preparedCall,
                childResult,
                resumeValue);

        /* Indexed assignment returns the exact RHS, never the atPut result. */
        builder.beginStoreLocal(result);
        builder.emitLoadLocal(value);
        builder.endStoreLocal();
    }

    private void emitDefaultCreate(
            ProtosSemanticBytecodeRootNodeGen.Builder builder,
            CanonicalCreate create,
            BytecodeLocal result,
            BytecodeLocal preparedCall,
            BytecodeLocal childResult,
            BytecodeLocal resumeValue) {
        BytecodeLocal value = builder.createLocal("defaultCreateValue", null);
        if (create.target().isEmpty()) {
            emitDefaultExpressionToLocal(
                    builder, create.value(), value, preparedCall, childResult, resumeValue);
            builder.beginStoreLocal(result);
            emitCreateCurrentBinding(builder, create, value);
            builder.endStoreLocal();
            return;
        }

        BytecodeLocal rawTarget = builder.createLocal("defaultCreateRawTarget", null);
        BytecodeLocal mutationTarget = builder.createLocal("defaultCreateMutationTarget", null);
        emitDefaultExpressionToLocal(
                builder,
                create.target().orElseThrow(),
                rawTarget,
                preparedCall,
                childResult,
                resumeValue);
        builder.beginStoreLocal(mutationTarget);
        builder.beginRequireObjectMutationTarget();
        emitCurrentActivation(builder);
        builder.emitLoadLocal(rawTarget);
        builder.endRequireObjectMutationTarget();
        builder.endStoreLocal();
        emitDefaultExpressionToLocal(
                builder, create.value(), value, preparedCall, childResult, resumeValue);
        builder.beginStoreLocal(result);
        builder.beginCreateLocalSlot();
        emitCurrentActivation(builder);
        builder.emitLoadLocal(mutationTarget);
        builder.emitLoadConstant(create.name());
        builder.emitLoadLocal(value);
        builder.endCreateLocalSlot();
        builder.endStoreLocal();
    }

    private void emitDefaultMultipleCreate(
            ProtosSemanticBytecodeRootNodeGen.Builder builder,
            CanonicalMultipleCreate create,
            BytecodeLocal result,
            BytecodeLocal preparedCall,
            BytecodeLocal childResult,
            BytecodeLocal resumeValue) {
        BytecodeLocal source =
                builder.createLocal("defaultMultipleCreateSource", null);

        emitDefaultExpressionToLocal(
                builder,
                create.value(),
                source,
                preparedCall,
                childResult,
                resumeValue);

        builder.beginStoreLocal(result);
        emitMultipleCreateCurrentBindings(builder, create.names(), source);
        builder.endStoreLocal();
    }

    private void emitDefaultAssign(
            ProtosSemanticBytecodeRootNodeGen.Builder builder,
            CanonicalAssign assign,
            BytecodeLocal result,
            BytecodeLocal preparedCall,
            BytecodeLocal childResult,
            BytecodeLocal resumeValue) {
        BytecodeLocal value = builder.createLocal("defaultAssignValue", null);
        BytecodeLocal mutationTarget = builder.createLocal("defaultAssignMutationTarget", null);
        java.util.Optional<CanonicalBindingResolution.CapturedResolved> capturedResolution =
                capturedResolvedAssignment(assign);
        BytecodeLocal capturedOwnerLocal =
                capturedResolution.map(this::capturedOwnerBytecodeLocal).orElse(null);
        BytecodeLocal currentFrameLocal = currentResolvedAssignmentBytecodeLocal(assign);

        if (assign.target().isPresent()) {
            BytecodeLocal rawTarget = builder.createLocal("defaultAssignRawTarget", null);
            emitDefaultExpressionToLocal(
                    builder,
                    assign.target().orElseThrow(),
                    rawTarget,
                    preparedCall,
                    childResult,
                    resumeValue);
            builder.beginStoreLocal(mutationTarget);
            builder.beginRequireObjectMutationTarget();
            emitCurrentActivation(builder);
            builder.emitLoadLocal(rawTarget);
            builder.endRequireObjectMutationTarget();
            builder.endStoreLocal();
        } else if (capturedResolution.isPresent()) {
            CanonicalBindingResolution.CapturedResolved captured =
                    capturedResolution.orElseThrow();
            builder.beginStoreLocal(mutationTarget);
            emitResolveCapturedWriteTarget(builder, assign, captured, capturedOwnerLocal);
            builder.endStoreLocal();
        } else if (currentFrameLocal != null) {
            builder.beginStoreLocal(mutationTarget);
            if (currentActivationLocal == null) {
                builder.beginResolveRootFrameLocalWriteTarget(currentFrameLocal);
                builder.emitLoadConstant(assign.name());
                builder.endResolveRootFrameLocalWriteTarget();
            } else if (currentInlineCallbackFrameNative) {
                builder.beginResolveInlineFrameLocalWriteTarget(
                        currentFrameLocal,
                        currentRootFrameNativeLocals,
                        currentRootFrameNativeLayout);
                emitCurrentInlineCallbackCall(builder);
                builder.emitLoadConstant(assign.name());
                builder.endResolveInlineFrameLocalWriteTarget();
            } else {
                builder.beginResolveCurrentFrameLocalWriteTarget(currentFrameLocal);
                emitCurrentActivation(builder);
                builder.emitLoadConstant(assign.name());
                builder.endResolveCurrentFrameLocalWriteTarget();
            }
            builder.endStoreLocal();
        } else {
            builder.beginStoreLocal(mutationTarget);
            builder.beginResolveWritableLexicalTarget();
            emitCurrentActivation(builder);
            builder.emitLoadConstant(assign.name());
            builder.endResolveWritableLexicalTarget();
            builder.endStoreLocal();
        }

        emitDefaultExpressionToLocal(
                builder, assign.value(), value, preparedCall, childResult, resumeValue);
        builder.beginStoreLocal(result);
        if (capturedResolution.isPresent()) {
            emitAssignCaptured(
                    builder,
                    assign,
                    capturedResolution.orElseThrow(),
                    capturedOwnerLocal,
                    mutationTarget,
                    value);
        } else if (assign.target().isPresent()) {
            builder.beginAssignLocalSlot();
            emitCurrentActivation(builder);
            builder.emitLoadLocal(mutationTarget);
            builder.emitLoadConstant(assign.name());
            builder.emitLoadLocal(value);
            builder.endAssignLocalSlot();
        } else if (currentFrameLocal != null) {
            if (currentActivationLocal == null) {
                builder.beginAssignRootFrameLocal(currentFrameLocal);
                builder.emitLoadLocal(mutationTarget);
                builder.emitLoadConstant(assign.name());
                builder.emitLoadLocal(value);
                builder.endAssignRootFrameLocal();
            } else if (currentInlineCallbackFrameNative) {
                builder.beginAssignInlineFrameLocal(
                        currentFrameLocal,
                        currentRootFrameNativeLocals,
                        currentRootFrameNativeLayout);
                emitCurrentInlineCallbackCall(builder);
                builder.emitLoadLocal(mutationTarget);
                builder.emitLoadConstant(assign.name());
                builder.emitLoadLocal(value);
                builder.endAssignInlineFrameLocal();
            } else {
                builder.beginAssignCurrentFrameLocal(currentFrameLocal);
                emitCurrentActivation(builder);
                builder.emitLoadLocal(mutationTarget);
                builder.emitLoadConstant(assign.name());
                builder.emitLoadLocal(value);
                builder.endAssignCurrentFrameLocal();
            }
        } else {
            builder.beginAssignResolvedLexicalTarget();
            emitCurrentActivation(builder);
            builder.emitLoadLocal(mutationTarget);
            builder.emitLoadConstant(assign.name());
            builder.emitLoadLocal(value);
            builder.endAssignResolvedLexicalTarget();
        }
        builder.endStoreLocal();
    }

    private void emitDefaultIndexedAssign(
            ProtosSemanticBytecodeRootNodeGen.Builder builder,
            CanonicalIndexedAssign indexedAssign,
            BytecodeLocal result,
            BytecodeLocal preparedCall,
            BytecodeLocal childResult,
            BytecodeLocal resumeValue) {
        BytecodeLocal receiver = builder.createLocal("defaultIndexedAssignReceiver", null);
        BytecodeLocal index = builder.createLocal("defaultIndexedAssignIndex", null);
        BytecodeLocal value = builder.createLocal("defaultIndexedAssignValue", null);
        BytecodeLocal dispatchResult = builder.createLocal("defaultIndexedAssignDispatchResult", null);

        emitDefaultExpressionToLocal(
                builder,
                indexedAssign.receiver(),
                receiver,
                preparedCall,
                childResult,
                resumeValue);
        emitDefaultExpressionToLocal(
                builder,
                indexedAssign.index(),
                index,
                preparedCall,
                childResult,
                resumeValue);
        emitDefaultExpressionToLocal(
                builder,
                indexedAssign.value(),
                value,
                preparedCall,
                childResult,
                resumeValue);

        builder.beginStoreLocal(preparedCall);
        beginPrepareSend(builder, 2);
        builder.emitLoadLocal(receiver);
        builder.emitLoadConstant("atPut");
        emitSendCallerOperand(builder);
        builder.emitLoadLocal(index);
        builder.emitLoadLocal(value);
        endPrepareSend(builder, 2);
        builder.endStoreLocal();
        emitPreparedInvocation(
                builder,
                dispatchResult,
                preparedCall,
                childResult,
                resumeValue);

        builder.beginStoreLocal(result);
        builder.emitLoadLocal(value);
        builder.endStoreLocal();
    }

    private void emitComposedSuperSend(
            ProtosSemanticBytecodeRootNodeGen.Builder builder,
            CanonicalSuperSend send,
            BytecodeLocal result,
            BytecodeLocal preparedCall,
            BytecodeLocal childResult,
            BytecodeLocal resumeValue) {
        requireDefaultScratch(result, preparedCall, childResult, resumeValue);
        boolean stageArguments = hasComposedArgument(send.arguments());
        boolean spreadArguments = hasSpreadArgument(send.arguments());
        boolean stageInputs = stageArguments || spreadArguments;
        BytecodeLocal suppliedVector = null;
        java.util.List<BytecodeLocal> argumentValues = java.util.List.of();

        builder.beginTag(StandardTags.CallTag.class);
        builder.beginBlock();

        if (stageInputs) {
            if (spreadArguments) {
                suppliedVector = builder.createLocal("superSuppliedArgumentVector", null);
                emitBodySpreadArgumentVector(
                        builder,
                        send.arguments(),
                        suppliedVector,
                        preparedCall,
                        childResult,
                        resumeValue);
            } else {
                argumentValues = new java.util.ArrayList<>(send.arguments().size());
                for (CanonicalExpression argument : send.arguments()) {
                    BytecodeLocal argumentValue = builder.createLocal("superArgument", null);
                    emitBodyExpressionToLocal(
                            builder,
                            argument,
                            argumentValue,
                            preparedCall,
                            childResult,
                            resumeValue);
                    argumentValues.add(argumentValue);
                }
            }
        }

        builder.beginStoreLocal(preparedCall);
        if (spreadArguments) {
            builder.beginPrepareSuperSendVector();
            builder.emitLoadConstant(send.message());
            emitCurrentActivation(builder);
            builder.emitLoadLocal(suppliedVector);
            builder.endPrepareSuperSendVector();
        } else {
            builder.beginPrepareSuperSendArguments();
            builder.emitLoadConstant(send.message());
            emitCurrentActivation(builder);
            if (stageInputs) {
                for (BytecodeLocal argumentValue : argumentValues) {
                    builder.emitLoadLocal(argumentValue);
                }
            } else {
                for (CanonicalExpression argument : send.arguments()) {
                    emitExpression(builder, argument);
                }
            }
            builder.endPrepareSuperSendArguments();
        }
        builder.endStoreLocal();

        emitPreparedInvocation(
                builder,
                result,
                preparedCall,
                childResult,
                resumeValue);
        builder.endBlock();
        builder.endTag(StandardTags.CallTag.class);
    }

    private void emitComposedDefaultSuperSend(
            ProtosSemanticBytecodeRootNodeGen.Builder builder,
            CanonicalSuperSend send,
            BytecodeLocal result,
            BytecodeLocal preparedCall,
            BytecodeLocal childResult,
            BytecodeLocal resumeValue) {
        requireDefaultScratch(result, preparedCall, childResult, resumeValue);
        boolean stageArguments = hasComposedArgument(send.arguments());
        boolean spreadArguments = hasSpreadArgument(send.arguments());
        boolean stageInputs = stageArguments || spreadArguments;
        BytecodeLocal suppliedVector = null;
        java.util.List<BytecodeLocal> argumentValues = java.util.List.of();

        builder.beginSourceSection(
                send.span().startOffset(),
                send.span().length());
        builder.beginTag(StandardTags.CallTag.class);
        builder.beginBlock();

        if (stageInputs) {
            if (spreadArguments) {
                suppliedVector = builder.createLocal("defaultSuperSuppliedArgumentVector", null);
                emitDefaultSpreadArgumentVector(
                        builder,
                        send.arguments(),
                        suppliedVector,
                        preparedCall,
                        childResult,
                        resumeValue);
            } else {
                argumentValues = new java.util.ArrayList<>(send.arguments().size());
                for (CanonicalExpression argument : send.arguments()) {
                    BytecodeLocal argumentValue = builder.createLocal("defaultSuperArgument", null);
                    emitDefaultExpressionToLocal(
                            builder,
                            argument,
                            argumentValue,
                            preparedCall,
                            childResult,
                            resumeValue);
                    argumentValues.add(argumentValue);
                }
            }
        }

        builder.beginStoreLocal(preparedCall);
        if (spreadArguments) {
            builder.beginPrepareSuperSendVector();
            builder.emitLoadConstant(send.message());
            emitCurrentActivation(builder);
            builder.emitLoadLocal(suppliedVector);
            builder.endPrepareSuperSendVector();
        } else {
            builder.beginPrepareSuperSendArguments();
            builder.emitLoadConstant(send.message());
            emitCurrentActivation(builder);
            if (stageInputs) {
                for (BytecodeLocal argumentValue : argumentValues) {
                    builder.emitLoadLocal(argumentValue);
                }
            } else {
                for (CanonicalExpression argument : send.arguments()) {
                    emitExpression(builder, argument);
                }
            }
            builder.endPrepareSuperSendArguments();
        }
        builder.endStoreLocal();

        emitPreparedInvocation(
                builder,
                result,
                preparedCall,
                childResult,
                resumeValue);
        builder.endBlock();
        builder.endTag(StandardTags.CallTag.class);
        builder.endSourceSection();
    }

    private void emitComposedDefaultCall(
            ProtosSemanticBytecodeRootNodeGen.Builder builder,
            CanonicalCall call,
            BytecodeLocal result,
            BytecodeLocal preparedCall,
            BytecodeLocal childResult,
            BytecodeLocal resumeValue) {
        requireDefaultScratch(
                result,
                preparedCall,
                childResult,
                resumeValue);
        CanonicalExpression receiver = call.receiver();
        boolean stageReceiver = requiresComposedInvocation(receiver);
        boolean stageArguments =
                hasComposedArgument(call.arguments());
        boolean spreadArguments =
                hasSpreadArgument(call.arguments());
        boolean stageInputs =
                stageReceiver
                        || stageArguments
                        || spreadArguments;
        BytecodeLocal receiverValue = null;
        BytecodeLocal suppliedVector = null;
        java.util.List<BytecodeLocal> argumentValues =
                java.util.List.of();

        builder.beginSourceSection(
                call.span().startOffset(),
                call.span().length());
        builder.beginTag(StandardTags.CallTag.class);
        builder.beginBlock();

        if (stageInputs) {
            receiverValue =
                    builder.createLocal(
                            "defaultCallReceiver",
                            null);
            if (stageReceiver) {
                emitDefaultExpressionToLocal(
                        builder,
                        receiver,
                        receiverValue,
                        preparedCall,
                        childResult,
                        resumeValue);
            } else {
                builder.beginStoreLocal(receiverValue);
                emitExpression(builder, receiver);
                builder.endStoreLocal();
            }

            if (spreadArguments) {
                suppliedVector =
                        builder.createLocal(
                                "defaultSuppliedArgumentVector",
                                null);
                emitDefaultSpreadArgumentVector(
                        builder,
                        call.arguments(),
                        suppliedVector,
                        preparedCall,
                        childResult,
                        resumeValue);
            } else {
                argumentValues =
                        new java.util.ArrayList<>(
                                call.arguments().size());
                for (CanonicalExpression argument :
                        call.arguments()) {
                    BytecodeLocal argumentValue =
                            builder.createLocal(
                                    "defaultCallArgument",
                                    null);
                    emitDefaultExpressionToLocal(
                            builder,
                            argument,
                            argumentValue,
                            preparedCall,
                            childResult,
                            resumeValue);
                    argumentValues.add(argumentValue);
                }
            }
        }

        builder.beginStoreLocal(preparedCall);
        if (spreadArguments) {
            builder.beginPrepareClosureCallVector();
            builder.emitLoadLocal(receiverValue);
            emitCurrentActivation(builder);
            builder.emitLoadLocal(suppliedVector);
            builder.endPrepareClosureCallVector();
        } else {
            builder.beginPrepareDefaultClosureCallArguments();
            if (stageInputs) {
                builder.emitLoadLocal(receiverValue);
            } else {
                emitExpression(builder, receiver);
            }
            emitCurrentActivation(builder);
            if (stageInputs) {
                for (BytecodeLocal argumentValue :
                        argumentValues) {
                    builder.emitLoadLocal(argumentValue);
                }
            } else {
                for (CanonicalExpression argument :
                        call.arguments()) {
                    emitExpression(builder, argument);
                }
            }
            builder.endPrepareDefaultClosureCallArguments();
        }
        builder.endStoreLocal();
        emitComposedPreparedDefaultInvocation(
                builder,
                result,
                preparedCall,
                childResult,
                resumeValue);
        builder.endBlock();
        builder.endTag(StandardTags.CallTag.class);
        builder.endSourceSection();
    }

    private void emitComposedDefaultSend(
            ProtosSemanticBytecodeRootNodeGen.Builder builder,
            CanonicalSend send,
            BytecodeLocal result,
            BytecodeLocal preparedCall,
            BytecodeLocal childResult,
            BytecodeLocal resumeValue) {
        requireDefaultScratch(
                result,
                preparedCall,
                childResult,
                resumeValue);
        CanonicalExpression receiver = send.receiver();
        boolean stageReceiver = requiresComposedInvocation(receiver);
        boolean stageArguments =
                hasComposedArgument(send.arguments());
        boolean spreadArguments =
                hasSpreadArgument(send.arguments());
        boolean tryNativeIfTrue = admitsNativeIfTrue(send, spreadArguments);
        boolean stageInputs =
                stageReceiver || stageArguments || spreadArguments
                        || tryNativeIfTrue;
        BytecodeLocal receiverValue = null;
        BytecodeLocal suppliedVector = null;
        java.util.List<BytecodeLocal> argumentValues =
                java.util.List.of();

        builder.beginSourceSection(
                send.span().startOffset(),
                send.span().length());
        builder.beginTag(StandardTags.CallTag.class);
        builder.beginBlock();

        if (stageInputs) {
            receiverValue =
                    builder.createLocal(
                            "defaultSendReceiver",
                            null);
            if (stageReceiver) {
                emitDefaultExpressionToLocal(
                        builder,
                        receiver,
                        receiverValue,
                        preparedCall,
                        childResult,
                        resumeValue);
            } else {
                builder.beginStoreLocal(receiverValue);
                emitExpression(builder, receiver);
                builder.endStoreLocal();
            }

            if (spreadArguments) {
                suppliedVector =
                        builder.createLocal(
                                "defaultSuppliedArgumentVector",
                                null);
                emitDefaultSpreadArgumentVector(
                        builder,
                        send.arguments(),
                        suppliedVector,
                        preparedCall,
                        childResult,
                        resumeValue);
            } else {
                argumentValues =
                        new java.util.ArrayList<>(
                                send.arguments().size());
                for (CanonicalExpression argument :
                        send.arguments()) {
                    BytecodeLocal argumentValue =
                            builder.createLocal(
                                    "defaultSendArgument",
                                    null);
                    emitDefaultExpressionToLocal(
                            builder,
                            argument,
                            argumentValue,
                            preparedCall,
                            childResult,
                            resumeValue);
                    argumentValues.add(argumentValue);
                }
            }
        }

        BytecodeLocal nativeIfTrue = null;
        if (tryNativeIfTrue) {
            nativeIfTrue = builder.createLocal("defaultNativeIfTrue", null);
            builder.beginStoreLocal(nativeIfTrue);
            emitNativeIfTrueCandidate(
                    builder, receiverValue, spreadArguments, suppliedVector,
                    spreadArguments ? null : argumentValues.get(0), null);
            builder.endStoreLocal();

            builder.beginIfThenElse();
            builder.beginIsCompactCanonicalBooleanMiss();
            builder.emitLoadLocal(nativeIfTrue);
            builder.endIsCompactCanonicalBooleanMiss();
            builder.beginBlock();
        }

        builder.beginStoreLocal(preparedCall);
        if (spreadArguments) {
            builder.beginPrepareSendVector();
            builder.emitLoadLocal(receiverValue);
            builder.emitLoadConstant(send.message());
            emitCurrentActivation(builder);
            builder.emitLoadLocal(suppliedVector);
            builder.endPrepareSendVector();
        } else {
            beginPrepareSend(builder, send.arguments().size());
            if (stageInputs) {
                builder.emitLoadLocal(receiverValue);
            } else {
                emitExpression(builder, receiver);
            }
            builder.emitLoadConstant(send.message());
            emitSendCallerOperand(builder);
            if (stageInputs) {
                for (BytecodeLocal argumentValue :
                        argumentValues) {
                    builder.emitLoadLocal(argumentValue);
                }
            } else {
                for (CanonicalExpression argument :
                        send.arguments()) {
                    emitExpression(builder, argument);
                }
            }
            endPrepareSend(builder, send.arguments().size());
        }
        builder.endStoreLocal();
        emitComposedPreparedDefaultInvocation(
                builder,
                result,
                preparedCall,
                childResult,
                resumeValue);
        if (tryNativeIfTrue) {
            builder.endBlock();
            builder.beginBlock();
            emitCanonicalIfTrueNative(
                    builder, nativeIfTrue, result,
                    childResult, resumeValue);
            builder.endBlock();
            builder.endIfThenElse();
        }
        builder.endBlock();
        builder.endTag(StandardTags.CallTag.class);
        builder.endSourceSection();
    }

    private void emitComposedPreparedDefaultInvocation(
            ProtosSemanticBytecodeRootNodeGen.Builder builder,
            BytecodeLocal result,
            BytecodeLocal preparedCall,
            BytecodeLocal childResult,
            BytecodeLocal resumeValue) {
        emitPreparedInvocation(
                builder,
                result,
                preparedCall,
                childResult,
                resumeValue);
    }

    /**
     * PLAT042 Candidate B′ source-root prepared invocation.
     *
     * <p>A structured prepared call (while, each, ensure, Error.handle,
     * match/case, import, structured collection callbacks, ...) enters the
     * untagged structured-dispatch root exactly once for this invocation and
     * composes its C-prime continuation through this semantic root; the
     * structured root then performs every callback call itself. Any other
     * prepared call is invoked locally, exactly as the final ordinary branch of
     * the structured dispatcher does.
     *
     * <p>PLAT043 narrows the structured case: a prepared standard Boolean call
     * (IF_TRUE, IF_FALSE, IF_TRUE_IF_FALSE, AND, OR) is sequenced in this root
     * (see {@link #emitLocalBooleanInvocation}), so a reached callback is
     * entered directly from this root without an intermediate helper root.
     */
    private void emitPreparedInvocation(
            ProtosSemanticBytecodeRootNodeGen.Builder builder,
            BytecodeLocal result,
            BytecodeLocal preparedCall,
            BytecodeLocal childResult,
            BytecodeLocal resumeValue) {
        emitPreparedInvocation(
                builder,
                result,
                preparedCall,
                childResult,
                resumeValue,
                java.util.List.of(),
                null,
                null);
    }

    /**
     * {@code inlineCallbacks} are the send site's PLAT044 B′ candidate
     * literals (see {@link #inlineLiteralCallbackCandidates}); they only add
     * guarded inline alternatives to the selected Boolean callback invocation
     * and never change selection.
     *
     * <p>{@code inlineWhile}, when non-null, is the send site's PERF026-C1
     * whileTrue literal pair ({@link #isInlineLiteralWhileCandidate}): a
     * prepared call that admits exactly that pair is sequenced by {@link
     * #emitLocalInlineLiteralWhile}; every other prepared call, including any
     * other standard while, keeps the dispatch below unchanged.
     *
     * <p>{@code inlineEach}, when non-null, is the send site's PERF026-D1
     * one-parameter ({@link #isInlineLiteralIndexedEachCandidate}) or
     * PERF026-D2/D3 two-parameter ({@link
     * #isInlineLiteralTwoParameterEachCandidate}) literal argument: a prepared
     * standard Array.each or Bytes.each, respectively Map.each,
     * IdentityMap.each or Environment.each, that admits exactly that literal is sequenced by
     * {@link #emitLocalInlineLiteralEach}; every other prepared call keeps the
     * dispatch below unchanged.
     */
    private void emitPreparedInvocation(
            ProtosSemanticBytecodeRootNodeGen.Builder builder,
            BytecodeLocal result,
            BytecodeLocal preparedCall,
            BytecodeLocal childResult,
            BytecodeLocal resumeValue,
            java.util.List<InlineLiteralCallback> inlineCallbacks,
            InlineLiteralWhile inlineWhile,
            InlineLiteralEach inlineEach) {
        requireDefaultScratch(
                result,
                preparedCall,
                childResult,
                resumeValue);

        if (inlineWhile != null) {
            builder.beginIfThenElse();
            builder.beginAdmitsInlineLiteralWhile();
            builder.emitLoadLocal(preparedCall);
            builder.emitLoadLocal(inlineWhile.condition().literal());
            builder.emitLoadLocal(inlineWhile.body().literal());
            builder.endAdmitsInlineLiteralWhile();

            emitLocalInlineLiteralWhile(
                    builder,
                    result,
                    preparedCall,
                    childResult,
                    resumeValue,
                    inlineWhile);

            builder.beginBlock();
        }

        if (inlineEach != null) {
            builder.beginIfThenElse();
            if (inlineEach.twoParameter()) {
                builder.beginAdmitsInlineLiteralTwoParameterEach();
            } else {
                builder.beginAdmitsInlineLiteralIndexedEach();
            }
            builder.emitLoadLocal(preparedCall);
            builder.emitLoadLocal(inlineEach.callback().literal());
            if (inlineEach.twoParameter()) {
                builder.endAdmitsInlineLiteralTwoParameterEach();
            } else {
                builder.endAdmitsInlineLiteralIndexedEach();
            }

            emitLocalInlineLiteralEach(
                    builder,
                    result,
                    preparedCall,
                    childResult,
                    resumeValue,
                    inlineEach);

            builder.beginBlock();
        }

        builder.beginIfThenElse();

        builder.beginIsStructuredBooleanCall();
        builder.emitLoadLocal(preparedCall);
        builder.endIsStructuredBooleanCall();

        emitLocalBooleanInvocation(
                builder,
                result,
                preparedCall,
                childResult,
                resumeValue,
                inlineCallbacks);

        builder.beginBlock();
        emitDispatchedPreparedInvocation(
                builder,
                result,
                preparedCall,
                childResult,
                resumeValue);
        builder.endBlock();

        builder.endIfThenElse();

        if (inlineEach != null) {
            builder.endBlock();
            builder.endIfThenElse();
        }

        if (inlineWhile != null) {
            builder.endBlock();
            builder.endIfThenElse();
        }
    }

    /**
     * PLAT044 B′ (PERF026-C1) local standard whileTrue over an admitted
     * literal pair.
     *
     * <p>Mirrors the while branch of {@code ProtosStructuredDispatchLowerer}
     * against the same {@code PreparedWhileCall} state machine, so the outer
     * prepared call is completed exactly once by the enclosing TryFinally,
     * the receiver/body are validated before the first condition activation,
     * each condition and (only after a canonical {@code true}) each body
     * activation is prepared fresh at the same point, the condition result
     * keeps its strict canonical Boolean authority, the body result is
     * ignored and normal completion is canonical {@code null}. The loop phase
     * lives in this root's Bytecode control state, so suspension resumes at
     * the exact loop PC through this root's continuation (PLAT014/PLAT021).
     *
     * <p>Each fresh child that {@code PreparedWhileCall} admits for its
     * staged literal runs inline ({@link #emitInlineLiteralCallback}); a
     * child that is not admitted keeps the helper's exact scoped invocation
     * ({@link #emitLocalInlineLiteralChild}).
     */
    private void emitLocalInlineLiteralWhile(
            ProtosSemanticBytecodeRootNodeGen.Builder builder,
            BytecodeLocal result,
            BytecodeLocal preparedCall,
            BytecodeLocal childResult,
            BytecodeLocal resumeValue,
            InlineLiteralWhile inlineWhile) {
        builder.beginBlock();
        BytecodeLocal structuredWhile =
                builder.createLocal("structuredWhile", null);
        BytecodeLocal structuredWhileChild =
                builder.createLocal("structuredWhileChild", null);
        BytecodeLocal structuredWhileConditionResult =
                builder.createLocal("structuredWhileConditionResult", null);

        builder.beginTryFinally(
                () -> {
                    builder.beginCompleteClosureCall();
                    builder.emitLoadLocal(preparedCall);
                    builder.endCompleteClosureCall();
                });
        builder.beginBlock();

        builder.beginStoreLocal(structuredWhile);
        builder.beginPrepareStructuredWhileCall();
        builder.emitLoadLocal(preparedCall);
        builder.endPrepareStructuredWhileCall();
        builder.endStoreLocal();

        builder.beginWhile();

        builder.beginBlock();
        builder.beginStoreLocal(structuredWhileChild);
        builder.beginPrepareInlineStructuredWhileConditionCall();
        builder.emitLoadLocal(structuredWhile);
        builder.endPrepareInlineStructuredWhileConditionCall();
        builder.endStoreLocal();
        emitLocalInlineLiteralChild(
                builder,
                structuredWhileConditionResult,
                structuredWhileChild,
                childResult,
                resumeValue,
                inlineWhile.condition().definition(),
                () -> {
                    builder.beginAdmitsInlineLiteralWhileCondition();
                    emitInlineLiteralChildAdmissionOperands(
                            builder,
                            structuredWhile,
                            structuredWhileChild,
                            inlineWhile.condition());
                    builder.endAdmitsInlineLiteralWhileCondition();
                });
        builder.beginStructuredWhileCondition();
        builder.emitLoadLocal(structuredWhile);
        builder.emitLoadLocal(structuredWhileConditionResult);
        builder.endStructuredWhileCondition();
        builder.endBlock();

        builder.beginBlock();
        builder.beginStoreLocal(structuredWhileChild);
        builder.beginPrepareInlineStructuredWhileBodyCall();
        builder.emitLoadLocal(structuredWhile);
        builder.endPrepareInlineStructuredWhileBodyCall();
        builder.endStoreLocal();
        emitLocalInlineLiteralChild(
                builder,
                childResult,
                structuredWhileChild,
                childResult,
                resumeValue,
                inlineWhile.body().definition(),
                () -> {
                    builder.beginAdmitsInlineLiteralWhileBody();
                    emitInlineLiteralChildAdmissionOperands(
                            builder,
                            structuredWhile,
                            structuredWhileChild,
                            inlineWhile.body());
                    builder.endAdmitsInlineLiteralWhileBody();
                });
        builder.endBlock();

        builder.endWhile();

        builder.beginStoreLocal(result);
        builder.emitLoadConstant(ProtosNullValue.INSTANCE);
        builder.endStoreLocal();

        builder.endBlock();
        builder.endTryFinally();
        builder.endBlock();
    }

    /**
     * PLAT044 B′ local standard each over an admitted literal callback:
     * Array.each or Bytes.each with a one-parameter literal (PERF026-D1), or
     * Map.each or IdentityMap.each (PERF026-D2) or Environment.each
     * (PERF026-D3) with a two-parameter literal.
     *
     * <p>Mirrors the corresponding each branches of {@code
     * ProtosStructuredDispatchLowerer} against the same prepared loop state
     * ({@code PreparedArrayEachCall}/{@code PreparedBytesEachCall} or {@code
     * PreparedMapEachCall}/{@code PreparedIdentityMapEachCall} or {@code
     * PreparedEnvironmentEachCall}, viewed as {@code PreparedLocalEachCall}),
     * so the outer prepared call is completed exactly once by the enclosing
     * TryFinally, the receiver and callback callability are validated before
     * the single snapshot is established (for Environment.each, the complete
     * portable, canonically ordered String snapshot), each child is prepared
     * fresh with exactly its snapshot arguments (the element or octet, the
     * representative key and value, or the portable name and value) as
     * supplied arguments, the cursor advances only after that child
     * completes normally, an Error or non-local return unwinds without a
     * later visit, and normal completion returns the original receiver. The
     * loop phase lives in this root's Bytecode control state, so suspension
     * resumes the same child through this root's continuation
     * (PLAT014/PLAT021) without replaying completed visits.
     *
     * <p>An admitted child runs inline ({@link #emitInlineLiteralCallback}),
     * whose ordinary parameter binding reads every formal's value from the
     * child's own fresh activation; a child that is not admitted keeps its
     * exact physical invocation ({@link #emitLocalInlineLiteralChild}).
     */
    private void emitLocalInlineLiteralEach(
            ProtosSemanticBytecodeRootNodeGen.Builder builder,
            BytecodeLocal result,
            BytecodeLocal preparedCall,
            BytecodeLocal childResult,
            BytecodeLocal resumeValue,
            InlineLiteralEach inlineEach) {
        InlineLiteralCallback callback = inlineEach.callback();
        builder.beginBlock();
        BytecodeLocal structuredEach =
                builder.createLocal("structuredLocalEach", null);
        BytecodeLocal structuredEachChild =
                builder.createLocal("structuredLocalEachChild", null);

        builder.beginTryFinally(
                () -> {
                    builder.beginCompleteClosureCall();
                    builder.emitLoadLocal(preparedCall);
                    builder.endCompleteClosureCall();
                });
        builder.beginBlock();

        builder.beginStoreLocal(structuredEach);
        if (inlineEach.twoParameter()) {
            builder.beginPrepareStructuredTwoParameterEachCall();
            builder.emitLoadLocal(preparedCall);
            builder.endPrepareStructuredTwoParameterEachCall();
        } else {
            builder.beginPrepareStructuredIndexedEachCall();
            builder.emitLoadLocal(preparedCall);
            builder.endPrepareStructuredIndexedEachCall();
        }
        builder.endStoreLocal();

        builder.beginWhile();
        builder.beginStructuredLocalEachHasNext();
        builder.emitLoadLocal(structuredEach);
        builder.endStructuredLocalEachHasNext();

        builder.beginBlock();
        builder.beginStoreLocal(structuredEachChild);
        builder.beginPrepareInlineStructuredLocalEachChildCall();
        builder.emitLoadLocal(structuredEach);
        builder.endPrepareInlineStructuredLocalEachChildCall();
        builder.endStoreLocal();
        emitLocalInlineLiteralChild(
                builder,
                childResult,
                structuredEachChild,
                childResult,
                resumeValue,
                callback.definition(),
                () -> {
                    builder.beginAdmitsInlineLiteralLocalEachChild();
                    emitInlineLiteralChildAdmissionOperands(
                            builder,
                            structuredEach,
                            structuredEachChild,
                            callback);
                    builder.endAdmitsInlineLiteralLocalEachChild();
                });
        builder.beginAdvanceStructuredLocalEach();
        builder.emitLoadLocal(structuredEach);
        builder.endAdvanceStructuredLocalEach();
        builder.endBlock();

        builder.endWhile();

        builder.beginStoreLocal(result);
        builder.beginFinishStructuredLocalEach();
        builder.emitLoadLocal(structuredEach);
        builder.endFinishStructuredLocalEach();
        builder.endStoreLocal();

        builder.endBlock();
        builder.endTryFinally();
        builder.endBlock();
    }

    /**
     * One fresh callback child of a locally sequenced loop (a while condition
     * or body, or a standard each element, association or entry).
     *
     * <p>Preparation has already performed authoritative ordinary
     * {@code call} selection, but an eligible canonical source callback is
     * still represented only by its compact invocation carrier. PLAT044
     * admission therefore happens before rich activation or physical
     * PreparedClosureCall construction. Only an admission miss materializes
     * the exact physical fallback.
     */
    private void emitLocalInlineLiteralChild(
            ProtosSemanticBytecodeRootNodeGen.Builder builder,
            BytecodeLocal result,
            BytecodeLocal child,
            BytecodeLocal childResult,
            BytecodeLocal resumeValue,
            CanonicalClosure definition,
            Runnable emitAdmission) {
        builder.beginIfThenElse();
        emitAdmission.run();

        emitInlineLiteralCallback(
                builder,
                definition,
                child,
                result);

        builder.beginBlock();
        emitInlineLiteralFallbackInvocation(
                builder,
                result,
                child,
                childResult,
                resumeValue);
        builder.endBlock();

        builder.endIfThenElse();
    }

    /**
     * Materializes a PLAT044 carrier's physical fallback only after inline
     * admission has failed. A structured fallback retains the existing
     * structured-dispatch helper boundary; an ordinary fallback retains the
     * existing scoped completion discipline.
     */
    private static void emitInlineLiteralFallbackInvocation(
            ProtosSemanticBytecodeRootNodeGen.Builder builder,
            BytecodeLocal result,
            BytecodeLocal child,
            BytecodeLocal childResult,
            BytecodeLocal resumeValue) {
        builder.beginBlock();

        BytecodeLocal fallback =
                builder.createLocal("inlineCallbackFallback", null);

        builder.beginStoreLocal(fallback);
        builder.beginLoadInlineLiteralFallbackCall();
        builder.emitLoadLocal(child);
        builder.endLoadInlineLiteralFallbackCall();
        builder.endStoreLocal();

        builder.beginIfThenElse();

        builder.beginRequiresStructuredDispatch();
        builder.emitLoadLocal(fallback);
        builder.endRequiresStructuredDispatch();

        builder.beginBlock();
        emitNestedStructuredInvocation(
                builder,
                result,
                fallback,
                childResult,
                resumeValue);
        builder.endBlock();

        builder.beginBlock();
        builder.beginTryFinally(
                () -> {
                    builder.beginCompleteClosureCall();
                    builder.emitLoadLocal(fallback);
                    builder.endCompleteClosureCall();
                });

        builder.beginBlock();
        emitOrdinaryPreparedInvocation(
                builder,
                result,
                fallback,
                childResult,
                resumeValue);
        builder.endBlock();

        builder.endTryFinally();
        builder.endBlock();

        builder.endIfThenElse();
        builder.endBlock();
    }

    /** Operands of a per-child admission: loop state, child, staged literal and plan. */
    private static void emitInlineLiteralChildAdmissionOperands(
            ProtosSemanticBytecodeRootNodeGen.Builder builder,
            BytecodeLocal loop,
            BytecodeLocal child,
            InlineLiteralCallback literal) {
        builder.emitLoadLocal(loop);
        builder.emitLoadLocal(child);
        builder.emitLoadLocal(literal.literal());
        builder.emitLoadConstant(literal.literalPlan());
    }

    /**
     * Invokes a prepared call that is not sequenced in this root: a structured
     * call enters the untagged structured root (PLAT042), any other call is
     * invoked locally.
     */
    private static void emitDispatchedPreparedInvocation(
            ProtosSemanticBytecodeRootNodeGen.Builder builder,
            BytecodeLocal result,
            BytecodeLocal preparedCall,
            BytecodeLocal childResult,
            BytecodeLocal resumeValue) {
        builder.beginIfThenElse();

        builder.beginRequiresStructuredDispatch();
        builder.emitLoadLocal(preparedCall);
        builder.endRequiresStructuredDispatch();

        builder.beginBlock();
        emitNestedStructuredInvocation(builder, result, preparedCall, childResult, resumeValue);
        builder.endBlock();

        builder.beginBlock();
        emitOrdinaryPreparedInvocation(builder, result, preparedCall, childResult, resumeValue);
        builder.endBlock();

        builder.endIfThenElse();
    }

    private static void emitNestedStructuredInvocation(
            ProtosSemanticBytecodeRootNodeGen.Builder builder,
            BytecodeLocal result,
            BytecodeLocal preparedCall,
            BytecodeLocal childResult,
            BytecodeLocal resumeValue) {
        builder.beginStoreLocal(childResult);
        builder.beginEnterNestedStructuredDispatch();
        builder.emitLoadLocal(preparedCall);
        builder.endEnterNestedStructuredDispatch();
        builder.endStoreLocal();
        emitContinuationComposition(
                builder,
                preparedCall,
                childResult,
                resumeValue);
        builder.beginStoreLocal(result);
        builder.emitLoadLocal(childResult);
        builder.endStoreLocal();
    }

    private static void emitOrdinaryPreparedInvocation(
            ProtosSemanticBytecodeRootNodeGen.Builder builder,
            BytecodeLocal result,
            BytecodeLocal preparedCall,
            BytecodeLocal childResult,
            BytecodeLocal resumeValue) {
        builder.beginIfThenElse();

        builder.beginAdmitsStraightLineSourceCall();
        builder.emitLoadLocal(preparedCall);
        builder.endAdmitsStraightLineSourceCall();

        builder.beginBlock();
        builder.beginStoreLocal(result);
        builder.beginEnterStraightLineSourceCall();
        builder.emitLoadLocal(preparedCall);
        builder.endEnterStraightLineSourceCall();
        builder.endStoreLocal();
        builder.endBlock();

        builder.beginBlock();
        builder.beginStoreLocal(childResult);
        builder.beginEnterClosureCall();
        builder.emitLoadLocal(preparedCall);
        builder.endEnterClosureCall();
        builder.endStoreLocal();
        emitContinuationComposition(
                builder,
                preparedCall,
                childResult,
                resumeValue);
        builder.beginStoreLocal(result);
        builder.beginFinishClosureCall();
        builder.emitLoadLocal(preparedCall);
        builder.emitLoadLocal(childResult);
        builder.endFinishClosureCall();
        builder.endStoreLocal();
        builder.endBlock();

        builder.endIfThenElse();
    }

    /**
     * PLAT043 standard Boolean orchestration in the semantic source root.
     *
     * <p>Mirrors the Boolean branch of {@code ProtosStructuredDispatchLowerer}
     * against the same {@code PreparedBooleanCall} state machine: the outer
     * prepared call is completed exactly once by the enclosing TryFinally on
     * normal, Error, control-transfer and cancellation exits, an unselected
     * callback is neither validated nor invoked, and the selected callback is
     * prepared through ordinary polymorphic Closure-call preparation. The
     * callback child keeps the helper's scoped shape: a child that itself
     * requires structured dispatch enters the untagged root, and any other
     * child is completed by its own TryFinally. C-prime continuations of the
     * child are composed through this root (PLAT014); no new continuation
     * kind, Task, handler or return home is introduced.
     *
     * <p>PLAT044 B′ (PERF026-B1/B3): for each {@code inlineCallbacks}
     * candidate, a non-structured selected child that {@code
     * PreparedBooleanCall} admits for that candidate's exact position,
     * literal value and plan runs that candidate's body inline ({@link
     * #emitInlineLiteralCallback}) instead of entering the callback's
     * RootCallTarget; every other child keeps the exact ordinary invocation.
     * Admission follows the prepared selection, so at most one candidate can
     * match. The child TryFinally completes either form.
     */
    private void emitLocalBooleanInvocation(
            ProtosSemanticBytecodeRootNodeGen.Builder builder,
            BytecodeLocal result,
            BytecodeLocal preparedCall,
            BytecodeLocal childResult,
            BytecodeLocal resumeValue,
            java.util.List<InlineLiteralCallback> inlineCallbacks) {
        builder.beginBlock();
        BytecodeLocal structuredBoolean =
                builder.createLocal("structuredBoolean", null);
        BytecodeLocal structuredBooleanChild =
                builder.createLocal("structuredBooleanChild", null);

        builder.beginTryFinally(
                () -> {
                    builder.beginCompleteClosureCall();
                    builder.emitLoadLocal(preparedCall);
                    builder.endCompleteClosureCall();
                });
        builder.beginBlock();

        builder.beginStoreLocal(structuredBoolean);
        builder.beginPrepareStructuredBooleanCall();
        builder.emitLoadLocal(preparedCall);
        builder.endPrepareStructuredBooleanCall();
        builder.endStoreLocal();

        builder.beginIfThenElse();
        builder.beginStructuredBooleanHasCallback();
        builder.emitLoadLocal(structuredBoolean);
        builder.endStructuredBooleanHasCallback();

        builder.beginBlock();
        builder.beginStoreLocal(structuredBooleanChild);
        builder.beginPrepareInlineStructuredBooleanCallbackCall();
        builder.emitLoadLocal(structuredBoolean);
        builder.endPrepareInlineStructuredBooleanCallbackCall();
        builder.endStoreLocal();

        for (InlineLiteralCallback inlineCallback : inlineCallbacks) {
            builder.beginIfThenElse();
            builder.beginAdmitsInlineLiteralCallback();
            builder.emitLoadLocal(structuredBoolean);
            builder.emitLoadLocal(structuredBooleanChild);
            builder.emitLoadLocal(inlineCallback.literal());
            builder.emitLoadConstant(inlineCallback.literalPlan());
            builder.emitLoadConstant(inlineCallback.position());
            builder.endAdmitsInlineLiteralCallback();

            emitInlineLiteralCallback(
                    builder,
                    inlineCallback.definition(),
                    structuredBooleanChild,
                    childResult);

            builder.beginBlock();
        }

        emitInlineLiteralFallbackInvocation(
                builder,
                childResult,
                structuredBooleanChild,
                childResult,
                resumeValue);

        for (int i = 0; i < inlineCallbacks.size(); i++) {
            builder.endBlock();
            builder.endIfThenElse();
        }

        builder.beginStoreLocal(result);
        builder.beginFinishStructuredBooleanCallback();
        builder.emitLoadLocal(structuredBoolean);
        builder.emitLoadLocal(childResult);
        builder.endFinishStructuredBooleanCallback();
        builder.endStoreLocal();
        builder.endBlock();

        builder.beginBlock();
        builder.beginStoreLocal(result);
        builder.beginStructuredBooleanImmediateResult();
        builder.emitLoadLocal(structuredBoolean);
        builder.endStructuredBooleanImmediateResult();
        builder.endStoreLocal();
        builder.endBlock();

        builder.endIfThenElse();
        builder.endBlock();
        builder.endTryFinally();
        builder.endBlock();
    }

    /**
     * PLAT044 B′ inline callback region for an admitted literal callback.
     *
     * <p>The region keeps the invocation's {@link
     * ProtosBytecodeRootNode.PreparedInlineLiteralCall} in {@link
     * #INLINE_CALLBACK_CALL_LOCAL}; entering the region materializes no
     * activation. The callback's fresh semantic activation is materialized
     * from that carrier, exactly once, by the first operation or tooling
     * query that needs it ({@link #emitCurrentActivation}), and every later
     * observer of the invocation shares it. In a frame-native region the
     * block-local bindings are made durable on the activation before it
     * reaches that first consumer.
     *
     * <p>When whole-tree binding analysis proves that the callback does not
     * require a persistent frame authority, its current-scope parameters and
     * locals use block-local frame storage in the enclosing physical root,
     * through carrier-operand operations ({@link
     * ProtosInlineCallbackFrameBindings}). Reads use the exact {@link
     * ProtosFrameLexicalLayout#presentContinuityAt} Assumption owned by the
     * callback's canonical lexical scope, including across Bytecode parser
     * reparses.
     *
     * <p>A callback requiring observable/escaping current-context authority
     * keeps the named runtime-authority path, whose first operation
     * materializes the activation. No authority over ephemeral block locals is
     * retained, B-prime admission is unchanged, and no callback
     * RootCallTarget/FrameInstance is fabricated.
     */
    private void emitInlineLiteralCallback(
            ProtosSemanticBytecodeRootNodeGen.Builder builder,
            CanonicalClosure definition,
            BytecodeLocal child,
            BytecodeLocal result) {
        CanonicalBindingAnalysis callbackAnalysis =
                bindingAnalysisForNestedClosure(definition);
        CanonicalLexicalScope callbackScope =
                rootScopeFor(callbackAnalysis, definition);
        boolean frameNativeCallback =
                !requiresPersistentFrameAuthority(
                        definition.body(),
                        definition,
                        callbackAnalysis,
                        callbackScope);

        builder.beginBlock();
        BytecodeLocal callbackCall =
                builder.createLocal(INLINE_CALLBACK_CALL_LOCAL, null);
        BytecodeLocal bodyResult =
                builder.createLocal("inlineCallbackResult", null);

        java.util.Map<String, BytecodeLocal> callbackFrameLocals =
                java.util.Map.of();
        BytecodeLocal[] callbackFrameLocalRange = null;
        ProtosFrameLexicalLayout callbackFrameLocalLayout = null;

        /*
         * A frame-native callback always has a (possibly empty) range and
         * layout: its activation is only ever materialized through them.
         */
        if (frameNativeCallback) {
            java.util.LinkedHashMap<String, BytecodeLocal> frameLocals =
                    new java.util.LinkedHashMap<>();
            for (String name : callbackScope.declaredNames()) {
                frameLocals.put(
                        name,
                        builder.createLocal(
                                INLINE_CALLBACK_BINDING_LOCAL_PREFIX + name,
                                null));
            }

            callbackFrameLocals = java.util.Map.copyOf(frameLocals);
            callbackFrameLocalRange =
                    frameLocals.values().toArray(BytecodeLocal[]::new);
            callbackFrameLocalLayout =
                    frameLexicalLayoutForScope(
                            callbackScope,
                            frameLocals.keySet().toArray(String[]::new),
                            null);
        }

        builder.beginStoreLocal(callbackCall);
        builder.emitLoadLocal(child);
        builder.endStoreLocal();

        SourceSpan bodySpan = definition.body().span();
        builder.beginSourceSection(bodySpan.startOffset(), bodySpan.length());
        builder.beginTag(StandardTags.RootTag.class);
        builder.beginBlock();

        CanonicalBindingAnalysis savedAnalysis = currentRootAnalysis;
        CanonicalLexicalScope savedTopScope = currentRootTopScope;
        java.util.Map<String, BytecodeLocal> savedFrameLocals =
                currentRootFrameLocals;
        ProtosFrameLexicalLayout savedFrameLayout =
                currentRootFrameLayout;
        BytecodeLocal savedActivationLocal = currentActivationLocal;
        boolean savedActivationLocalIsInlineCallbackCall =
                currentActivationLocalIsInlineCallbackCall;
        boolean savedInlineCallbackFrameNative =
                currentInlineCallbackFrameNative;
        BytecodeLocal[] savedFrameNativeLocals =
                currentRootFrameNativeLocals;
        ProtosFrameLexicalLayout savedFrameNativeLayout =
                currentRootFrameNativeLayout;
        ProtosFrameLexicalLayout savedIndexedParameterLayout =
                currentRootIndexedParameterLayout;

        try {
            if (frameNativeCallback) {
                currentRootAnalysis = callbackAnalysis;
                currentRootTopScope = callbackScope;
                currentRootFrameLocals = callbackFrameLocals;
                currentRootFrameLayout = callbackFrameLocalLayout;
                currentActivationLocal = callbackCall;
                currentActivationLocalIsInlineCallbackCall = true;
                currentInlineCallbackFrameNative = true;
                currentRootFrameNativeLocals = callbackFrameLocalRange;
                currentRootFrameNativeLayout = callbackFrameLocalLayout;
                currentRootIndexedParameterLayout = null;
            } else {
                currentRootAnalysis = null;
                currentRootTopScope = null;
                currentRootFrameLocals = java.util.Map.of();
                currentRootFrameLayout = null;
                currentActivationLocal = callbackCall;
                currentActivationLocalIsInlineCallbackCall = true;
                currentInlineCallbackFrameNative = false;
                currentRootFrameNativeLocals = null;
                currentRootFrameNativeLayout = null;
                currentRootIndexedParameterLayout = null;
            }

            emitClosureParameterBindings(
                    builder,
                    definition,
                    null,
                    null,
                    null,
                    null);

            if (definition.body().expressions().isEmpty()) {
                builder.beginStoreLocal(bodyResult);
                builder.emitLoadConstant(ProtosNullValue.INSTANCE);
                builder.endStoreLocal();
            } else {
                emitStatementsToLocal(
                        builder,
                        definition.body(),
                        bodyResult);
            }
        } finally {
            currentRootAnalysis = savedAnalysis;
            currentRootTopScope = savedTopScope;
            currentRootFrameLocals = savedFrameLocals;
            currentRootFrameLayout = savedFrameLayout;
            currentActivationLocal = savedActivationLocal;
            currentActivationLocalIsInlineCallbackCall =
                    savedActivationLocalIsInlineCallbackCall;
            currentInlineCallbackFrameNative =
                    savedInlineCallbackFrameNative;
            currentRootFrameNativeLocals = savedFrameNativeLocals;
            currentRootFrameNativeLayout = savedFrameNativeLayout;
            currentRootIndexedParameterLayout =
                    savedIndexedParameterLayout;
        }

        builder.endBlock();
        builder.endTag(StandardTags.RootTag.class);
        builder.endSourceSection();

        builder.beginStoreLocal(result);
        builder.emitLoadLocal(bodyResult);
        builder.endStoreLocal();
        builder.endBlock();
    }

    /**
     * Re-yields every nested C-prime continuation of {@code childResult}
     * through this root and resumes it with the value this root is resumed
     * with, until the child completes (PLAT014; no replay, no new
     * continuation kind).
     */
    private static void emitContinuationComposition(
            ProtosSemanticBytecodeRootNodeGen.Builder builder,
            BytecodeLocal preparedCall,
            BytecodeLocal childResult,
            BytecodeLocal resumeValue) {
        builder.beginWhile();
        builder.beginIsContinuationForCall();
        builder.emitLoadLocal(preparedCall);
        builder.emitLoadLocal(childResult);
        builder.endIsContinuationForCall();

        builder.beginBlock();
        builder.beginStoreLocal(resumeValue);
        builder.beginYield();
        builder.emitLoadLocal(childResult);
        builder.endYield();
        builder.endStoreLocal();

        builder.beginStoreLocal(childResult);
        builder.beginResumeContinuation();
        builder.emitLoadLocal(preparedCall);
        builder.emitLoadLocal(childResult);
        builder.emitLoadLocal(resumeValue);
        builder.endResumeContinuation();
        builder.endStoreLocal();
        builder.endBlock();

        builder.endWhile();
    }

    static void requireDefaultScratch(
            BytecodeLocal result,
            BytecodeLocal preparedCall,
            BytecodeLocal childResult,
            BytecodeLocal resumeValue) {
        if (result == null || preparedCall == null || childResult == null || resumeValue == null) {
            throw new AssertionError("Bytecode composed-default scratch locals were not allocated");
        }
    }

    private void emitBindSuppliedClosureParameter(
            ProtosSemanticBytecodeRootNodeGen.Builder builder,
            CanonicalParameter parameter,
            int positionalIndex) {
        ParameterBindingForm form = beginBindClosureParameter(builder, parameter);
        if (currentActivationLocal == null) {
            /*
             * I091: a direct source caller may supply a primitive carrier.
             * Only frame-native parameter storage retains it.
             */
            boolean materialize = form != ParameterBindingForm.FRAME_NATIVE;
            if (materialize) {
                builder.beginMaterializePrimitiveNumericCarrier();
            }
            builder.beginLoadFrameClosureArgument();
            builder.emitLoadConstant(positionalIndex);
            builder.endLoadFrameClosureArgument();
            if (materialize) {
                builder.emitLoadConstant(true);
                builder.endMaterializePrimitiveNumericCarrier();
            }
        } else if (currentInlineCallbackFrameNative) {
            builder.beginLoadInlineClosureArgument(
                    currentRootFrameNativeLocals,
                    currentRootFrameNativeLayout);
            emitCurrentInlineCallbackCall(builder);
            builder.emitLoadConstant(positionalIndex);
            builder.endLoadInlineClosureArgument();
        } else {
            builder.beginLoadClosureArgument();
            emitCurrentActivation(builder);
            builder.emitLoadConstant(positionalIndex);
            builder.endLoadClosureArgument();
        }
        endBindClosureParameter(builder, form);
    }

    private void validateSupported(CanonicalSequence sequence) {
        for (CanonicalExpression expression : sequence.expressions()) {
            validateSupportedExpression(expression);
        }
    }

    private void validateSupportedExpression(
            CanonicalExpression expression) {
        validateSpan(expression.span());
        if (expression instanceof CanonicalLiteral
                || expression instanceof CanonicalLookup
                || expression instanceof CanonicalIntrinsic) {
            return;
        }
        if (expression instanceof CanonicalClosure closure) {
            /*
             * PERF013 Slice A: structural-support validation only. This must
             * NOT construct the Closure's execution plan/root here: that
             * happens later, at real emission time in emitExpression, so the
             * Closure's root can be nested inside whichever enclosing root's
             * builder is actually open then (shared BytecodeRootNodes
             * grouping). Mirrors exactly the checks full construction would
             * have performed (validateSupportedDefaults + a supported-shape
             * walk of the body), so an unsupported nested construct is still
             * reported before any builder is touched.
             */
            validateSupportedDefaults(closure);
            validateSpan(closure.body().span());
            validateSupported(closure.body());
            return;
        }
        if (expression instanceof CanonicalObject object) {
            /*
             * Structural-support validation only: the body is emitted inline
             * later (emitInlineObjectConstruction), while the enclosing
             * root's own builder is open.
             */
            object.parent().ifPresent(this::validateSupportedExpression);
            registerObjectBodyReservedNames(object);
            validateSupported(object.body());
            return;
        }
        if (expression instanceof CanonicalMapConstruction map) {
            validateSupportedExpression(map.factory());
            for (CanonicalMapConstruction.Entry entry : map.entries()) {
                validateSupportedExpression(entry.key());
                validateSupportedExpression(entry.value());
            }
            return;
        }
        if (expression instanceof CanonicalCompose compose) {
            if (!bytecodeComposeReservedNames.containsKey(compose)) {
                throw new UnsupportedOperationException(
                        "CanonicalCompose is valid only in its registered Object body");
            }
            validateSupportedExpression(compose.object());
            return;
        }
        if (expression instanceof CanonicalMember member) {
            validateSupportedExpression(member.receiver());
            return;
        }
        if (expression instanceof CanonicalIdentity identity) {
            validateSupportedExpression(identity.left());
            validateSupportedExpression(identity.right());
            return;
        }
        if (expression instanceof CanonicalNotIdentity identity) {
            validateSupportedExpression(identity.left());
            validateSupportedExpression(identity.right());
            return;
        }
        if (expression instanceof CanonicalDerivedInequality inequality) {
            validateSupportedExpression(inequality.left());
            validateSupportedExpression(inequality.right());
            return;
        }
        if (expression instanceof CanonicalCreate create) {
            create.target().ifPresent(this::validateSupportedExpression);
            validateSupportedExpression(create.value());
            return;
        }
        if (expression instanceof CanonicalMultipleCreate create) {
            validateSupportedExpression(create.value());
            return;
        }
        if (expression instanceof CanonicalAssign assign) {
            assign.target().ifPresent(this::validateSupportedExpression);
            validateSupportedExpression(assign.value());
            return;
        }
        if (expression instanceof CanonicalIndexedAssign indexedAssign) {
            validateSupportedExpression(indexedAssign.receiver());
            validateSupportedExpression(indexedAssign.index());
            validateSupportedExpression(indexedAssign.value());
            return;
        }
        if (expression instanceof CanonicalCall call) {
            validateSupportedExpression(call.receiver());
            for (CanonicalExpression argument : call.arguments()) {
                if (argument instanceof CanonicalSpread spread) {
                    validateSupportedExpression(spread.expression());
                } else {
                    validateSupportedExpression(argument);
                }
            }
            return;
        }
        if (expression instanceof CanonicalSend send) {
            validateSupportedExpression(send.receiver());
            for (CanonicalExpression argument : send.arguments()) {
                if (argument instanceof CanonicalSpread spread) {
                    validateSupportedExpression(spread.expression());
                } else {
                    validateSupportedExpression(argument);
                }
            }
            return;
        }
        if (expression instanceof CanonicalSuperSend send) {
            for (CanonicalExpression argument : send.arguments()) {
                if (argument instanceof CanonicalSpread spread) {
                    validateSupportedExpression(spread.expression());
                } else {
                    validateSupportedExpression(argument);
                }
            }
            return;
        }
        if (expression instanceof CanonicalReturn returnExpression) {
            validateSupportedExpression(returnExpression.value());
            return;
        }
        throw new UnsupportedOperationException(
                "PERF006-B2B Bytecode lowerer does not yet support "
                        + expression.getClass().getSimpleName());
    }

    private void validateSpan(SourceSpan span) {
        Objects.requireNonNull(span, "span");
        if (span.endOffset() > source.getLength()) {
            throw new IllegalArgumentException(
                    "source span "
                            + span
                            + " exceeds owning Truffle Source length "
                            + source.getLength()
                            + " for "
                            + source.getName());
        }
    }

    private boolean useCompactClosureCapture() {
        return currentActivationLocal == null && currentRootFrameLocals.isEmpty();
    }

    private void beginClosureCapture(
            ProtosSemanticBytecodeRootNodeGen.Builder builder) {
        if (useCompactClosureCapture()) {
            builder.beginMaterializeCurrentClosure();
            builder.emitCurrentCallerReference();
        } else {
            builder.beginMaterializeClosure();
            emitCurrentActivation(builder);
        }
    }

    private void endClosureCapture(
            ProtosSemanticBytecodeRootNodeGen.Builder builder) {
        if (useCompactClosureCapture()) {
            builder.endMaterializeCurrentClosure();
        } else {
            builder.endMaterializeClosure();
        }
    }

    private void emitExpression(
            ProtosSemanticBytecodeRootNodeGen.Builder builder,
            CanonicalExpression expression) {
        if (expression instanceof CanonicalLiteral literal) {
            emitLiteral(builder, literal);
            return;
        }
        if (expression instanceof CanonicalClosure closure) {
            /*
             * PERF013 Slice A/A2: resolve/lower the Closure's own root
             * (nesting its beginRoot()/endRoot() in this same open builder,
             * or reusing the cell already registered on a BytecodeRootNodes
             * reparse) before opening the MaterializeClosure operation,
             * rather than nesting root construction inside that operation's
             * own argument evaluation.
             */
            ProtosClosureExecutionPlanCell cell = bytecodeClosurePlan(builder, closure);
            beginClosureCapture(builder);
            builder.emitLoadConstant(closure);
            builder.emitLoadConstant(cell);
            endClosureCapture(builder);
            return;
        }
        if (expression instanceof CanonicalLookup lookup) {
            emitLookup(builder, lookup);
            return;
        }
        if (expression instanceof CanonicalIntrinsic intrinsic) {
            if (intrinsic.kind() == CanonicalIntrinsic.Kind.THIS
                    && currentInlineCallbackFrameNative) {
                // CONTEXT is a real observer and keeps LoadIntrinsic.
                builder.beginLoadInlineCallbackReceiver(
                        currentRootFrameNativeLocals,
                        currentRootFrameNativeLayout);
                emitCurrentInlineCallbackCall(builder);
                builder.endLoadInlineCallbackReceiver();
                return;
            }
            builder.beginLoadIntrinsic();
            emitCurrentActivation(builder);
            builder.emitLoadConstant(intrinsic.kind());
            builder.endLoadIntrinsic();
            return;
        }
        if (expression instanceof CanonicalMember member) {
            beginMemberRead(builder, member.name());
            emitExpression(builder, member.receiver());
            endMemberRead(builder);
            return;
        }
        if (expression instanceof CanonicalIdentity identity) {
            builder.beginIdentity();
            emitExpression(builder, identity.left());
            emitExpression(builder, identity.right());
            builder.endIdentity();
            return;
        }
        if (expression instanceof CanonicalNotIdentity identity) {
            builder.beginNotIdentity();
            emitExpression(builder, identity.left());
            emitExpression(builder, identity.right());
            builder.endNotIdentity();
            return;
        }
        throw new AssertionError(
                "validated value-shaped Bytecode expression became unsupported: "
                        + expression.getClass().getSimpleName());
    }

    /**
     * PLAT036 Candidate D, Slice 3 key direct/fast path: a reference that is
     * statically {@link CanonicalBindingResolution.Resolved} in the exact
     * genuine execution-context scope currently being lowered compiles
     * straight to a {@code ReadFrameLocal} of its own stable {@link
     * com.oracle.truffle.api.bytecode.LocalAccessor}, bypassing the generic {@code Lookup} operation and exact name-based
     * lexical fallback entirely. This uses the
     * same accessor-based mechanism the installed frame-backed authority uses
     * to write this local (see {@code ProtosFrameLexicalBindingAuthority}),
     * deliberately never the raw generated {@code LoadLocal} instruction: a
     * local written only through the dynamic accessor API does not
     * participate in the frame-slot-kind speculation the DSL's own literal
     * {@code StoreLocal}/{@code LoadLocal} pair relies on. A
     * {@code Resolved} classification proves that the binding was established
     * before this exact program point; PERF025-D179-A combines that proof with
     * a root/name-scoped one-way Assumption so the ordinary path need not read
     * cleared state until the first actual D179 removal invalidates it.
     * {@code Candidate} and {@code Dynamic} resolutions, and any
     * {@code Resolved} binding owned by a different scope (an {@code
     * OBJECT_BODY} or a different root entirely), always fall through to the
     * unchanged generic path.
     */
    private void emitLookup(
            ProtosSemanticBytecodeRootNodeGen.Builder builder,
            CanonicalLookup lookup) {
        emitLookup(builder, lookup, false);
    }

    /*
     * I091: carrierOperand is true only for an operand of the guarded
     * primitive numeric chain, which may then receive a frame-retained
     * primitive carrier. Every other read yields a guest value.
     */
    private void emitLookup(
            ProtosSemanticBytecodeRootNodeGen.Builder builder,
            CanonicalLookup lookup,
            boolean carrierOperand) {
        if (currentRootAnalysis != null) {
            java.util.Optional<CanonicalBindingResolution> resolution =
                    currentRootAnalysis.resolutionOf(lookup);
            if (resolution.isPresent()
                    && resolution.orElseThrow() instanceof CanonicalBindingResolution.Resolved resolved
                    && resolved.identity().owner() == currentRootTopScope) {
                String resolvedName = resolved.identity().name();
                BytecodeLocal local =
                        currentRootFrameLocals.get(resolvedName);
                Integer ordinal =
                        currentRootFrameLayout == null
                                ? null
                                : currentRootFrameLayout.offsetOf(resolvedName);
                if (local != null
                        && ordinal != null
                        && currentActivationLocal == null) {
                    if (!carrierOperand) {
                        builder.beginMaterializePrimitiveNumericCarrier();
                    }
                    builder.beginReadRootFrameLocal(
                            local,
                            currentRootFrameLayout.presentContinuityAt(ordinal));
                    builder.emitLoadConstant(resolvedName);
                    builder.endReadRootFrameLocal();
                    if (!carrierOperand) {
                        builder.emitLoadConstant(true);
                        builder.endMaterializePrimitiveNumericCarrier();
                    }
                    return;
                }
                if (local != null
                        && ordinal != null
                        && currentInlineCallbackFrameNative) {
                    builder.beginReadInlineFrameLocal(
                            local,
                            currentRootFrameLayout.presentContinuityAt(ordinal),
                            currentRootFrameNativeLocals,
                            currentRootFrameNativeLayout);
                    emitCurrentInlineCallbackCall(builder);
                    builder.emitLoadConstant(resolvedName);
                    builder.endReadInlineFrameLocal();
                    return;
                }
                if (local != null && ordinal != null) {
                    builder.beginReadFrameLocal(
                            local,
                            currentRootFrameLayout.presentContinuityAt(ordinal));
                    emitCurrentActivation(builder);
                    builder.emitLoadConstant(resolvedName);
                    builder.endReadFrameLocal();
                    return;
                }
            }

            if (resolution.isPresent()
                    && resolution.orElseThrow()
                            instanceof CanonicalBindingResolution.CapturedResolved captured) {
                BytecodeLocal ownerLocal = capturedOwnerBytecodeLocal(captured);
                if (ownerLocal != null && currentInlineCallbackFrameNative) {
                    emitGuestCapturedMaterializedRead(
                            builder, captured, ownerLocal, true, carrierOperand);
                    return;
                }
                if (currentInlineCallbackFrameNative) {
                    builder.beginReadInlineCapturedFrameLocal(
                            currentRootFrameNativeLocals,
                            currentRootFrameNativeLayout,
                            frameBackedOrdinal(captured.identity()));
                    emitCurrentInlineCallbackCall(builder);
                    builder.emitLoadConstant(captured.identity().name());
                    builder.emitLoadConstant(captured.lexicalDepth());
                    builder.endReadInlineCapturedFrameLocal();
                    return;
                }
                if (ownerLocal != null) {
                    emitGuestCapturedMaterializedRead(
                            builder, captured, ownerLocal, false, carrierOperand);
                    return;
                }

                if (currentActivationLocal == null) {
                    builder.emitReadCapturedFrameLocalAtRoot(
                            frameBackedOrdinal(captured.identity()),
                            captured.identity().name(),
                            captured.lexicalDepth());
                    return;
                }
                builder.beginReadCapturedFrameLocal(
                        frameBackedOrdinal(captured.identity()));
                emitCurrentActivation(builder);
                builder.emitLoadConstant(captured.identity().name());
                builder.emitLoadConstant(captured.lexicalDepth());
                builder.endReadCapturedFrameLocal();
                return;
            }
        }
        builder.beginLookup();
        emitCurrentActivation(builder);
        builder.emitLoadConstant(lookup.name());
        builder.endLookup();
    }

    /*
     * I091: the builtin materialized load reads the owner frame directly, so
     * a retained primitive carrier is materialized unless the read is an
     * operand of the guarded primitive numeric chain.
     */
    private void emitGuestCapturedMaterializedRead(
            ProtosSemanticBytecodeRootNodeGen.Builder builder,
            CanonicalBindingResolution.CapturedResolved captured,
            BytecodeLocal ownerLocal,
            boolean inlineCallback,
            boolean carrierOperand) {
        if (carrierOperand) {
            emitCapturedMaterializedRead(builder, captured, ownerLocal, inlineCallback);
            return;
        }
        builder.beginMaterializePrimitiveNumericCarrier();
        emitCapturedMaterializedRead(builder, captured, ownerLocal, inlineCallback);
        builder.emitLoadConstant(true);
        builder.endMaterializePrimitiveNumericCarrier();
    }

    /**
     * PERF013 Slice B1 / BUG018-C: the direct read of a proven captured
     * binding whose owner {@link BytecodeLocal} is known. The owner frame is
     * selected dynamically (nearer bindings, owner authority, PRESENT check)
     * without reading the value; a selected frame is then read with the
     * builtin {@code LoadLocalMaterialized} of {@code ownerLocal}, which
     * tolerates the owner root's cached local-kind metadata disagreeing with
     * a frame retained from another activation. No selected frame takes the
     * generic captured lookup, so presence never depends on the value and
     * {@code PRESENT(null)} stays distinct from {@code ABSENT}. The inline
     * form selects without materializing the callback activation whenever
     * the existing direct path admits it.
     */
    private void emitCapturedMaterializedRead(
            ProtosSemanticBytecodeRootNodeGen.Builder builder,
            CanonicalBindingResolution.CapturedResolved captured,
            BytecodeLocal ownerLocal,
            boolean inlineCallback) {
        String name = captured.identity().name();
        builder.beginBlock();
        BytecodeLocal selectedOwnerFrame =
                builder.createLocal("capturedOwnerFrame", null);

        builder.beginStoreLocal(selectedOwnerFrame);
        if (inlineCallback) {
            builder.beginSelectInlineCapturedMaterializedOwnerFrame(
                    ownerLocal,
                    currentRootFrameNativeLocals,
                    currentRootFrameNativeLayout);
            emitCurrentInlineCallbackCall(builder);
            builder.emitLoadConstant(name);
            builder.emitLoadConstant(captured.lexicalDepth());
            builder.endSelectInlineCapturedMaterializedOwnerFrame();
        } else if (currentActivationLocal == null) {
            builder.emitSelectCapturedMaterializedOwnerFrameAtRoot(
                    ownerLocal, name, captured.lexicalDepth());
        } else {
            builder.beginSelectCapturedMaterializedOwnerFrame(ownerLocal);
            emitCurrentActivation(builder);
            builder.emitLoadConstant(name);
            builder.emitLoadConstant(captured.lexicalDepth());
            builder.endSelectCapturedMaterializedOwnerFrame();
        }
        builder.endStoreLocal();

        builder.beginConditional();
        builder.beginIsCapturedOwnerFrameSelected();
        builder.emitLoadLocal(selectedOwnerFrame);
        builder.endIsCapturedOwnerFrameSelected();

        builder.beginLoadLocalMaterialized(ownerLocal);
        builder.emitLoadLocal(selectedOwnerFrame);
        builder.endLoadLocalMaterialized();

        if (inlineCallback) {
            builder.beginReadInlineCapturedFallback(
                    currentRootFrameNativeLocals,
                    currentRootFrameNativeLayout);
            emitCurrentInlineCallbackCall(builder);
            builder.emitLoadConstant(name);
            builder.endReadInlineCapturedFallback();
        } else if (currentActivationLocal == null) {
            builder.emitReadCapturedFallbackAtRoot(name);
        } else {
            builder.beginReadCapturedFallback();
            emitCurrentActivation(builder);
            builder.emitLoadConstant(name);
            builder.endReadCapturedFallback();
        }
        builder.endConditional();
        builder.endBlock();
    }

    /**
     * PERF013 Slice B1 (reads) / Slice B2 (writes): the owner {@link
     * BytecodeLocal} for a proven captured access, when and only when {@code
     * captured}'s owner scope is both structurally reachable from the exact
     * root currently being lowered ({@link #capturedOwnerMatchesCurrentRoot})
     * and already registered in {@link #frameLocalsByScope} — meaning owner
     * and this Closure share the same physical {@code BytecodeRootNodes}
     * group. Returns {@code null} (never partial/best-effort metadata)
     * whenever either condition fails, so the caller falls back to the exact
     * existing {@code ReadCapturedFrameLocal}/{@code
     * ResolveCapturedWritableLexicalTarget} runtime-authority path unchanged.
     * This is expected, not an error, for an owner root reached through an
     * isolated Closure rebuild/rematerialization that does not include its
     * lexical owner in the same lowering group (PERF013 Slice C).
     */
    private BytecodeLocal capturedOwnerBytecodeLocal(
            CanonicalBindingResolution.CapturedResolved captured) {
        if (!capturedOwnerMatchesCurrentRoot(captured)) {
            return null;
        }
        java.util.Map<String, BytecodeLocal> ownerFrameLocals =
                frameLocalsByScope.get(captured.identity().owner());
        if (ownerFrameLocals == null) {
            return null;
        }
        return ownerFrameLocals.get(captured.identity().name());
    }

    private java.util.Optional<CanonicalBindingResolution.CapturedResolved>
            capturedResolvedAssignment(CanonicalAssign assign) {
        if (assign.target().isPresent() || currentRootAnalysis == null) {
            return java.util.Optional.empty();
        }

        java.util.Optional<CanonicalBindingResolution> resolution =
                currentRootAnalysis.resolutionOf(assign);
        if (resolution.isPresent()
                && resolution.orElseThrow()
                        instanceof CanonicalBindingResolution.CapturedResolved captured) {
            return java.util.Optional.of(captured);
        }
        return java.util.Optional.empty();
    }

    /**
     * PERF028-A: the current root's own {@link BytecodeLocal} for a bare
     * assignment statically {@code Resolved} in the exact genuine scope being
     * lowered, mirroring the {@code ReadFrameLocal} admission in {@link
     * #emitLookup}; {@code null} keeps the unchanged generic write path.
     */
    private BytecodeLocal currentResolvedAssignmentBytecodeLocal(CanonicalAssign assign) {
        if (assign.target().isPresent() || currentRootAnalysis == null) {
            return null;
        }

        java.util.Optional<CanonicalBindingResolution> resolution =
                currentRootAnalysis.resolutionOf(assign);
        if (resolution.isPresent()
                && resolution.orElseThrow() instanceof CanonicalBindingResolution.Resolved resolved
                && resolved.identity().owner() == currentRootTopScope) {
            return currentRootFrameLocals.get(resolved.identity().name());
        }
        return null;
    }

    private boolean capturedOwnerMatchesCurrentRoot(
            CanonicalBindingResolution.CapturedResolved captured) {
        if (currentRootTopScope == null) {
            return false;
        }

        CanonicalLexicalScope scope = currentRootTopScope;
        for (int depth = 0; depth < captured.lexicalDepth(); depth++) {
            scope = scope.outwardScope();
            if (scope == null) {
                return false;
            }
        }
        return scope == captured.identity().owner();
    }

    private static int frameBackedOrdinal(
            CanonicalBindingIdentity identity) {
        int ordinal = 0;
        for (String declaredName : identity.owner().declaredNames()) {
            if (declaredName.equals(identity.name())) {
                return ordinal;
            }
            ordinal++;
        }
        throw new AssertionError(
                "binding identity owner no longer declares its own name: "
                        + identity.name());
    }

    /*
     * Numeric carriers are private to the guarded arithmetic expression chain.
     * Ordering consumes carriers and always yields a guest Boolean.
     */
    private static boolean primitiveNumericSendCandidate(CanonicalSend send) {
        return send.arguments().size() == 1
                && primitiveNumericSelector(send.message())
                && !hasSpreadArgument(send.arguments());
    }

    /*
     * I091: a send whose fused direct source call may deliver a callee's
     * primitive result to a carrier-permitting consumer.
     */
    private static boolean primitiveResultSend(CanonicalSend send) {
        return primitiveNumericSendCandidate(send)
                || (send.arguments().size() == 1 && !hasSpreadArgument(send.arguments()));
    }

    private static boolean primitiveNumericSelector(String selector) {
        return switch (selector) {
            case "+", "-", "*", "/", "<", "<=", ">", ">=" -> true;
            default -> false;
        };
    }

    /*
     * I091: an argument worth staging as a carrier for an admitted direct
     * source call (whose frame-native parameter binding retains it).
     */
    private static boolean primitiveNumericCarrierArgument(CanonicalExpression argument) {
        return argument instanceof CanonicalLookup
                || (argument instanceof CanonicalSend send && primitiveResultSend(send));
    }

    /* I091: a binding value the guarded chain or a raw binding read may yield as a carrier. */
    private boolean primitiveNumericCarrierValue(CanonicalExpression value) {
        if (value instanceof CanonicalLookup lookup) {
            return !isScalarLocalRead(lookup);
        }
        return value instanceof CanonicalSend send && primitiveResultSend(send);
    }

    /*
     * Whether the staged operand may hold a primitive carrier. A binding read
     * may: frame storage retains carriers written by the chain (I091).
     */
    private boolean primitiveNumericCarrierSource(CanonicalExpression expression) {
        if (expression instanceof CanonicalLookup) {
            return true;
        }
        if (expression instanceof CanonicalLiteral literal) {
            return primitiveNumberLiteralOrNull(literal) != null;
        }
        return expression instanceof CanonicalSend send && primitiveResultSend(send);
    }

    private void emitPrimitiveNumericOperandToLocal(
            ProtosSemanticBytecodeRootNodeGen.Builder builder,
            CanonicalExpression expression,
            BytecodeLocal target,
            BytecodeLocal preparedCall,
            BytecodeLocal childResult,
            BytecodeLocal resumeValue) {
        if (expression instanceof CanonicalLiteral literal) {
            // The staged operand is the literal's own carrier: a Long or Double descriptor.
            Object carrier = primitiveNumberLiteralOrNull(literal);
            if (carrier != null) {
                builder.beginStoreLocal(target);
                builder.emitLoadConstant(carrier);
                builder.endStoreLocal();
                return;
            }
        }
        if (expression instanceof CanonicalLookup lookup
                && !isScalarLocalRead(lookup)) {
            builder.beginStoreLocal(target);
            emitLookup(builder, lookup, true);
            builder.endStoreLocal();
            return;
        }
        if (expression instanceof CanonicalSend nested && primitiveResultSend(nested)) {
            builder.beginSourceSection(
                    nested.span().startOffset(), nested.span().length());
            emitComposedSend(builder, nested, target, preparedCall,
                    childResult, resumeValue, true);
            builder.endSourceSection();
            return;
        }
        emitBodyExpressionToLocal(builder, expression, target,
                preparedCall, childResult, resumeValue);
    }

    /**
     * I092 / D049: an ordinary ifTrue send with one supplied position is lowered
     * natively only once the standard root Object is published. FROZEN is
     * irreversible and publication validates its ifTrue and call behaviors, so
     * the canonical-Boolean selection proven here holds for every execution of
     * the site. Sources lowered during unpublished bootstrap stay generic.
     */
    private static boolean admitsNativeIfTrue(
            CanonicalSend send, boolean spreadArguments) {
        return "ifTrue".equals(send.message())
                && (spreadArguments || send.arguments().size() == 1)
                && ProtosObjectValue.rootObject().isFrozen();
    }

    /** Emits the native ifTrue candidate; see TryCompactCanonicalBooleanOne. */
    private void emitNativeIfTrueCandidate(
            ProtosSemanticBytecodeRootNodeGen.Builder builder,
            BytecodeLocal receiverValue,
            boolean spreadArguments,
            BytecodeLocal suppliedVector,
            BytecodeLocal argument,
            ProtosClosureExecutionPlanCell literalPlan) {
        if (spreadArguments) {
            if (currentInlineCallbackFrameNative) {
                builder.beginTryCompactCanonicalBooleanInlineVector(
                        currentRootFrameNativeLocals, currentRootFrameNativeLayout);
            } else {
                builder.beginTryCompactCanonicalBooleanVector();
            }
            builder.emitLoadLocal(receiverValue);
            emitSendCallerOperand(builder);
            builder.emitLoadLocal(suppliedVector);
            if (currentInlineCallbackFrameNative) {
                builder.endTryCompactCanonicalBooleanInlineVector();
            } else {
                builder.endTryCompactCanonicalBooleanVector();
            }
            return;
        }
        if (currentInlineCallbackFrameNative) {
            builder.beginTryCompactCanonicalBooleanInlineOne(
                    currentRootFrameNativeLocals, currentRootFrameNativeLayout);
        } else {
            builder.beginTryCompactCanonicalBooleanOne();
        }
        builder.emitLoadLocal(receiverValue);
        emitSendCallerOperand(builder);
        builder.emitLoadLocal(argument);
        builder.emitLoadConstant(
                literalPlan == null ? ProtosNullValue.INSTANCE : literalPlan);
        if (currentInlineCallbackFrameNative) {
            builder.endTryCompactCanonicalBooleanInlineOne();
        } else {
            builder.endTryCompactCanonicalBooleanOne();
        }
    }

    /** Native hit of an ifTrue site without an inline literal candidate. */
    private void emitCanonicalIfTrueNative(
            ProtosSemanticBytecodeRootNodeGen.Builder builder,
            BytecodeLocal candidate,
            BytecodeLocal result,
            BytecodeLocal childResult,
            BytecodeLocal resumeValue) {
        builder.beginIfThenElse();
        builder.beginIsCompactCanonicalBooleanNoCallback();
        builder.emitLoadLocal(candidate);
        builder.endIsCompactCanonicalBooleanNoCallback();

        builder.beginBlock();
        builder.beginStoreLocal(result);
        builder.emitLoadConstant(ProtosNullValue.INSTANCE);
        builder.endStoreLocal();
        builder.endBlock();

        // Selected callback, invoked from the send's caller: no ifTrue activation to complete.
        builder.beginBlock();
        emitInlineLiteralFallbackInvocation(
                builder, result, candidate, childResult, resumeValue);
        builder.endBlock();
        builder.endIfThenElse();
    }

    private static void emitStoreConstant(
            ProtosSemanticBytecodeRootNodeGen.Builder builder,
            BytecodeLocal target,
            Object value) {
        builder.beginStoreLocal(target);
        builder.emitLoadConstant(value);
        builder.endStoreLocal();
    }

    private static void emitCopyLocal(
            ProtosSemanticBytecodeRootNodeGen.Builder builder,
            BytecodeLocal target,
            BytecodeLocal source) {
        builder.beginStoreLocal(target);
        builder.emitLoadLocal(source);
        builder.endStoreLocal();
    }

    /**
     * I092 / PLAT044 B-prime: an ifTrue site whose sole argument is an
     * immediate literal callback. The native hit and the generic selection
     * converge on one inline region, so the site has one callback RootTag,
     * source span and debugger scope.
     *
     * <p>Argument staging, the native attempt and generic PrepareSend stay
     * outside the TryFinally. Inside it, the outer call is recorded before
     * anything can exit (the generic structured Boolean call); the native
     * path owns no ifTrue activation and records none. The
     * finally completes it once, after the callback, on normal, Error,
     * non-local-return and cancellation exits. A non-Boolean generic selection
     * keeps the unchanged prepared-invocation dispatch and its own completion.
     * The generic result still passes FinishStructuredBooleanCallback.
     */
    private void emitCanonicalIfTrueLiteralSite(
            ProtosSemanticBytecodeRootNodeGen.Builder builder,
            CanonicalClosure definition,
            BytecodeLocal compact,
            BytecodeLocal receiverValue,
            String selector,
            BytecodeLocal literal,
            BytecodeLocal result,
            BytecodeLocal preparedCall,
            BytecodeLocal childResult,
            BytecodeLocal resumeValue) {
        ProtosClosureExecutionPlanCell literalPlan =
                bytecodeClosurePlans.get(definition);
        BytecodeLocal outer = builder.createLocal("ifTrueSiteOuter", null);
        BytecodeLocal inlineChild =
                builder.createLocal("ifTrueSiteInlineChild", null);
        BytecodeLocal structuredBoolean =
                builder.createLocal("ifTrueSiteBoolean", null);
        BytecodeLocal child = builder.createLocal("ifTrueSiteChild", null);

        builder.beginBlock();
        emitStoreConstant(builder, outer, ProtosNullValue.INSTANCE);
        emitStoreConstant(builder, inlineChild, ProtosNullValue.INSTANCE);

        // Generic selection: same lifecycle position as the ordinary send.
        builder.beginIfThenElse();
        builder.beginIsCompactCanonicalBooleanMiss();
        builder.emitLoadLocal(compact);
        builder.endIsCompactCanonicalBooleanMiss();
        builder.beginBlock();
        builder.beginStoreLocal(preparedCall);
        beginPrepareSend(builder, 1);
        builder.emitLoadLocal(receiverValue);
        builder.emitLoadConstant(selector);
        emitSendCallerOperand(builder);
        builder.emitLoadLocal(literal);
        endPrepareSend(builder, 1);
        builder.endStoreLocal();
        builder.endBlock();
        builder.beginBlock();
        builder.endBlock();
        builder.endIfThenElse();

        builder.beginTryFinally(
                () -> {
                    builder.beginCompleteIfTrueSiteOuter();
                    builder.emitLoadLocal(outer);
                    builder.endCompleteIfTrueSiteOuter();
                });
        builder.beginBlock();

        builder.beginIfThenElse();
        builder.beginIsCompactCanonicalBooleanMiss();
        builder.emitLoadLocal(compact);
        builder.endIsCompactCanonicalBooleanMiss();

        // Generic: emitLocalBooleanInvocation, with admission deferred.
        builder.beginBlock();
        builder.beginIfThenElse();
        builder.beginIsStructuredBooleanCall();
        builder.emitLoadLocal(preparedCall);
        builder.endIsStructuredBooleanCall();

        builder.beginBlock();
        emitCopyLocal(builder, outer, preparedCall);
        builder.beginStoreLocal(structuredBoolean);
        builder.beginPrepareStructuredBooleanCall();
        builder.emitLoadLocal(preparedCall);
        builder.endPrepareStructuredBooleanCall();
        builder.endStoreLocal();

        builder.beginIfThenElse();
        builder.beginStructuredBooleanHasCallback();
        builder.emitLoadLocal(structuredBoolean);
        builder.endStructuredBooleanHasCallback();

        builder.beginBlock();
        builder.beginStoreLocal(child);
        builder.beginPrepareInlineStructuredBooleanCallbackCall();
        builder.emitLoadLocal(structuredBoolean);
        builder.endPrepareInlineStructuredBooleanCallbackCall();
        builder.endStoreLocal();

        builder.beginIfThenElse();
        builder.beginAdmitsInlineLiteralCallback();
        builder.emitLoadLocal(structuredBoolean);
        builder.emitLoadLocal(child);
        builder.emitLoadLocal(literal);
        builder.emitLoadConstant(literalPlan);
        builder.emitLoadConstant(0);
        builder.endAdmitsInlineLiteralCallback();

        builder.beginBlock();
        emitCopyLocal(builder, inlineChild, child);
        builder.endBlock();

        builder.beginBlock();
        emitInlineLiteralFallbackInvocation(
                builder, childResult, child, childResult, resumeValue);
        builder.beginStoreLocal(result);
        builder.beginFinishStructuredBooleanCallback();
        builder.emitLoadLocal(structuredBoolean);
        builder.emitLoadLocal(childResult);
        builder.endFinishStructuredBooleanCallback();
        builder.endStoreLocal();
        builder.endBlock();
        builder.endIfThenElse();
        builder.endBlock();

        builder.beginBlock();
        builder.beginStoreLocal(result);
        builder.beginStructuredBooleanImmediateResult();
        builder.emitLoadLocal(structuredBoolean);
        builder.endStructuredBooleanImmediateResult();
        builder.endStoreLocal();
        builder.endBlock();
        builder.endIfThenElse();
        builder.endBlock();

        builder.beginBlock();
        emitPreparedInvocation(
                builder, result, preparedCall, childResult, resumeValue);
        builder.endBlock();
        builder.endIfThenElse();
        builder.endBlock();

        // Native: no PreparedBooleanCall, PrepareSend or generic dispatch.
        builder.beginBlock();
        builder.beginIfThenElse();
        builder.beginIsCompactCanonicalBooleanNoCallback();
        builder.emitLoadLocal(compact);
        builder.endIsCompactCanonicalBooleanNoCallback();

        builder.beginBlock();
        emitStoreConstant(builder, result, ProtosNullValue.INSTANCE);
        builder.endBlock();

        // The selected callback; outer stays null, no ifTrue activation exists.
        builder.beginBlock();
        builder.beginIfThenElse();
        builder.beginAdmitsNativeIfTrueInline();
        builder.emitLoadLocal(compact);
        builder.emitLoadConstant(literalPlan);
        builder.endAdmitsNativeIfTrueInline();

        builder.beginBlock();
        emitCopyLocal(builder, inlineChild, compact);
        builder.endBlock();

        builder.beginBlock();
        emitInlineLiteralFallbackInvocation(
                builder, result, compact, childResult, resumeValue);
        builder.endBlock();
        builder.endIfThenElse();
        builder.endBlock();
        builder.endIfThenElse();
        builder.endBlock();
        builder.endIfThenElse();

        // The site's only inline callback region.
        builder.beginIfThenElse();
        builder.beginIsNativeIfTrueInlineCallback();
        builder.emitLoadLocal(inlineChild);
        builder.endIsNativeIfTrueInlineCallback();

        builder.beginBlock();
        emitInlineLiteralCallback(builder, definition, inlineChild, childResult);
        builder.beginIfThenElse();
        builder.beginIsCompactCanonicalBooleanMiss();
        builder.emitLoadLocal(compact);
        builder.endIsCompactCanonicalBooleanMiss();
        builder.beginBlock();
        builder.beginStoreLocal(result);
        builder.beginFinishStructuredBooleanCallback();
        builder.emitLoadLocal(structuredBoolean);
        builder.emitLoadLocal(childResult);
        builder.endFinishStructuredBooleanCallback();
        builder.endStoreLocal();
        builder.endBlock();
        builder.beginBlock();
        emitCopyLocal(builder, result, childResult);
        builder.endBlock();
        builder.endIfThenElse();
        builder.endBlock();

        builder.beginBlock();
        builder.endBlock();
        builder.endIfThenElse();

        builder.endBlock();
        builder.endTryFinally();
        builder.endBlock();
    }

    private void emitComposedSend(
            ProtosSemanticBytecodeRootNodeGen.Builder builder,
            CanonicalSend send,
            BytecodeLocal result,
            BytecodeLocal preparedCall,
            BytecodeLocal childResult,
            BytecodeLocal resumeValue) {
        emitComposedSend(builder, send, result, preparedCall,
                childResult, resumeValue, false);
    }

    private void emitComposedSend(
            ProtosSemanticBytecodeRootNodeGen.Builder builder,
            CanonicalSend send,
            BytecodeLocal result,
            BytecodeLocal preparedCall,
            BytecodeLocal childResult,
            BytecodeLocal resumeValue,
            boolean primitiveCarrierResult) {
        if (result == null
                || preparedCall == null
                || childResult == null
                || resumeValue == null) {
            throw new AssertionError(
                    "Bytecode send result/scratch locals were not allocated");
        }

        CanonicalExpression receiver = send.receiver();
        boolean tryPrimitiveNumericSend = primitiveNumericSendCandidate(send);
        boolean stageReceiver = requiresComposedInvocation(receiver);
        boolean stageArguments =
                hasComposedArgument(send.arguments());
        boolean spreadArguments =
                hasSpreadArgument(send.arguments());
        java.util.List<Integer> inlineCallbackPositions =
                spreadArguments
                        ? java.util.List.of()
                        : inlineLiteralCallbackCandidates(send.arguments());
        boolean tryCompactCanonicalIfTrue =
                admitsNativeIfTrue(send, spreadArguments);
        boolean inlineIndexedEachCandidate =
                !spreadArguments
                        && isInlineLiteralIndexedEachCandidate(send.arguments());
        boolean inlineTwoParameterEachCandidate =
                !spreadArguments
                        && isInlineLiteralTwoParameterEachCandidate(send.arguments());
        /*
         * PLAT044 B′: candidate literals are staged in ordinary argument
         * locals (still evaluated exactly once, in order) so the prepared
         * Boolean call can compare its selected callback with them.
         */
        /*
         * PERF038-H: attempt a fused Send1 only when the call site
         * can retain its operands for the unchanged fallback.
         * Admission of the actual selected method is runtime-guarded.
         */
        boolean tryDirectSendZero =
                !spreadArguments
                        && send.arguments().isEmpty()
                        && !currentInlineCallbackFrameNative
                        && inlineCallbackPositions.isEmpty()
                        && !inlineIndexedEachCandidate
                        && !inlineTwoParameterEachCandidate;

        boolean tryDirectSendOne =
                !spreadArguments
                        && send.arguments().size() == 1
                        && !currentInlineCallbackFrameNative
                        && inlineCallbackPositions.isEmpty()
                        && !inlineIndexedEachCandidate
                        && !inlineTwoParameterEachCandidate;

        boolean stageInputs =
                stageReceiver
                        || stageArguments
                        || spreadArguments
                        || !inlineCallbackPositions.isEmpty()
                        || inlineIndexedEachCandidate
                        || inlineTwoParameterEachCandidate
                        || tryDirectSendZero
                        || tryDirectSendOne
                        || tryPrimitiveNumericSend
                        || tryCompactCanonicalIfTrue;
        BytecodeLocal receiverValue = null;
        BytecodeLocal suppliedVector = null;
        boolean carrierDirectArgument = false;
        java.util.List<BytecodeLocal> argumentValues =
                java.util.List.of();

        builder.beginTag(StandardTags.CallTag.class);
        builder.beginBlock();

        if (stageInputs) {
            receiverValue =
                    builder.createLocal(
                            "sendReceiver",
                            null);
            if (tryPrimitiveNumericSend) {
                emitPrimitiveNumericOperandToLocal(
                        builder, receiver, receiverValue,
                        preparedCall, childResult, resumeValue);
            } else if (stageReceiver) {
                emitBodyExpressionToLocal(
                        builder,
                        receiver,
                        receiverValue,
                        preparedCall,
                        childResult,
                        resumeValue);
            } else {
                builder.beginStoreLocal(receiverValue);
                emitExpression(builder, receiver);
                builder.endStoreLocal();
            }

            if (spreadArguments) {
                suppliedVector =
                        builder.createLocal(
                                "suppliedArgumentVector",
                                null);
                emitBodySpreadArgumentVector(
                        builder,
                        send.arguments(),
                        suppliedVector,
                        preparedCall,
                        childResult,
                        resumeValue);
            } else {
                argumentValues =
                        new java.util.ArrayList<>(
                                send.arguments().size());
                for (CanonicalExpression argument :
                        send.arguments()) {
                    BytecodeLocal argumentValue =
                            builder.createLocal(
                                    "sendArgument",
                                    null);
                    if (tryPrimitiveNumericSend) {
                        emitPrimitiveNumericOperandToLocal(
                                builder, argument, argumentValue,
                                preparedCall, childResult, resumeValue);
                    } else if (tryDirectSendOne
                            && primitiveNumericCarrierArgument(argument)) {
                        /*
                         * I091: a fused direct source call may pass a
                         * primitive carrier; the miss path materializes it.
                         */
                        carrierDirectArgument = true;
                        emitPrimitiveNumericOperandToLocal(
                                builder, argument, argumentValue,
                                preparedCall, childResult, resumeValue);
                    } else {
                        emitBodyExpressionToLocal(
                                builder, argument, argumentValue,
                                preparedCall, childResult, resumeValue);
                    }
                    argumentValues.add(argumentValue);
                }
            }
        }

        BytecodeLocal primitiveNumericAttempt = null;
        if (tryPrimitiveNumericSend) {
            primitiveNumericAttempt = builder.createLocal("primitiveNumericAttempt", null);
            builder.beginStoreLocal(primitiveNumericAttempt);
            builder.beginTryPrimitiveNumericSendOne();
            builder.emitLoadLocal(receiverValue);
            builder.emitLoadConstant(send.message());
            emitSendCallerOperand(builder);
            builder.emitLoadLocal(argumentValues.get(0));
            builder.emitLoadConstant(primitiveNumericCarrierSource(receiver));
            builder.emitLoadConstant(
                    primitiveNumericCarrierSource(send.arguments().get(0)));
            builder.endTryPrimitiveNumericSendOne();
            builder.endStoreLocal();

            builder.beginIfThenElse();
            builder.beginIsPrimitiveNumericSendMiss();
            builder.emitLoadLocal(primitiveNumericAttempt);
            builder.endIsPrimitiveNumericSendMiss();
            builder.beginBlock();
            // Any internal carriers must become guest values before normal D013.
            builder.beginStoreLocal(receiverValue);
            builder.beginMaterializePrimitiveNumericCarrier();
            builder.emitLoadLocal(receiverValue);
            builder.emitLoadConstant(primitiveNumericCarrierSource(receiver));
            builder.endMaterializePrimitiveNumericCarrier();
            builder.endStoreLocal();
            BytecodeLocal stagedArgument = argumentValues.get(0);
            builder.beginStoreLocal(stagedArgument);
            builder.beginMaterializePrimitiveNumericCarrier();
            builder.emitLoadLocal(stagedArgument);
            builder.emitLoadConstant(
                    primitiveNumericCarrierSource(send.arguments().get(0)));
            builder.endMaterializePrimitiveNumericCarrier();
            builder.endStoreLocal();
        }

        /*
         * I092: receiver and callback-producing expression have already
         * been evaluated once, in source order. Selection comes afterward.
         * The entire original pipeline remains on the miss branch.
         */
        BytecodeLocal compactBooleanResult = null;
        CanonicalClosure compactBooleanDefinition = null;

        if (tryCompactCanonicalIfTrue) {
            compactBooleanDefinition =
                    inlineCallbackPositions.size() == 1
                                    && inlineCallbackPositions.get(0) == 0
                                    && !isInlineLiteralWhileCandidate(send)
                            ? (CanonicalClosure) send.arguments().get(0)
                            : null;
            compactBooleanResult =
                    builder.createLocal("compactCanonicalBoolean", null);

            builder.beginStoreLocal(compactBooleanResult);
            emitNativeIfTrueCandidate(
                    builder, receiverValue, spreadArguments, suppliedVector,
                    spreadArguments ? null : argumentValues.get(0),
                    compactBooleanDefinition == null
                            ? null
                            : bytecodeClosurePlans.get(compactBooleanDefinition));
            builder.endStoreLocal();

            if (compactBooleanDefinition != null) {
                emitCanonicalIfTrueLiteralSite(
                        builder,
                        compactBooleanDefinition,
                        compactBooleanResult,
                        receiverValue,
                        send.message(),
                        argumentValues.get(0),
                        result,
                        preparedCall,
                        childResult,
                        resumeValue);
                builder.endBlock();
                builder.endTag(StandardTags.CallTag.class);
                return;
            }

            builder.beginIfThenElse();
            builder.beginIsCompactCanonicalBooleanMiss();
            builder.emitLoadLocal(compactBooleanResult);
            builder.endIsCompactCanonicalBooleanMiss();
            builder.beginBlock();
        }

        BytecodeLocal directSendResult = null;
        if (tryDirectSendZero || tryDirectSendOne) {
            directSendResult =
                    builder.createLocal("directSendOneResult", null);

            builder.beginStoreLocal(directSendResult);
            if (tryDirectSendZero) {
                builder.beginTryDirectSendZero();
            } else {
                builder.beginTryDirectSendOne();
            }

            builder.emitLoadLocal(receiverValue);
            builder.emitLoadConstant(send.message());
            emitSendCallerOperand(builder);

            if (tryDirectSendZero) {
                builder.endTryDirectSendZero();
            } else {
                builder.emitLoadLocal(argumentValues.get(0));
                builder.endTryDirectSendOne();
            }
            builder.endStoreLocal();

            builder.beginIfThenElse();
            builder.beginIsDirectSendMiss();
            builder.emitLoadLocal(directSendResult);
            builder.endIsDirectSendMiss();

            /*
             * Miss: the original prepared-call protocol, including
             * native/structured calls, suspension and Error paths.
             */
            builder.beginBlock();
            if (carrierDirectArgument) {
                BytecodeLocal stagedArgument = argumentValues.get(0);
                builder.beginStoreLocal(stagedArgument);
                builder.beginMaterializePrimitiveNumericCarrier();
                builder.emitLoadLocal(stagedArgument);
                builder.emitLoadConstant(true);
                builder.endMaterializePrimitiveNumericCarrier();
                builder.endStoreLocal();
            }
        }

        builder.beginStoreLocal(preparedCall);
        if (spreadArguments) {
            builder.beginPrepareSendVector();
            builder.emitLoadLocal(receiverValue);
            builder.emitLoadConstant(send.message());
            emitCurrentActivation(builder);
            builder.emitLoadLocal(suppliedVector);
            builder.endPrepareSendVector();
        } else {
            beginPrepareSend(builder, send.arguments().size());
            if (stageInputs) {
                builder.emitLoadLocal(receiverValue);
            } else {
                emitExpression(builder, receiver);
            }
            builder.emitLoadConstant(send.message());
            emitSendCallerOperand(builder);
            if (stageInputs) {
                for (BytecodeLocal argumentValue :
                        argumentValues) {
                    builder.emitLoadLocal(argumentValue);
                }
            } else {
                for (CanonicalExpression argument :
                        send.arguments()) {
                    emitExpression(builder, argument);
                }
            }
            endPrepareSend(builder, send.arguments().size());
        }
        builder.endStoreLocal();

        java.util.List<InlineLiteralCallback> inlineCallbacks =
                new java.util.ArrayList<>(inlineCallbackPositions.size());
        for (int position : inlineCallbackPositions) {
            CanonicalClosure candidate =
                    (CanonicalClosure) send.arguments().get(position);
            inlineCallbacks.add(
                    new InlineLiteralCallback(
                            candidate,
                            position,
                            argumentValues.get(position),
                            bytecodeClosurePlans.get(candidate)));
        }

        /*
         * PERF026-C1: a while pair candidate always has its body at supplied
         * position 0 among the Boolean candidates, so both literals are
         * already staged; the receiver literal is staged in receiverValue.
         */
        InlineLiteralWhile inlineWhile = null;
        if (!spreadArguments && isInlineLiteralWhileCandidate(send)) {
            CanonicalClosure condition = (CanonicalClosure) receiver;
            inlineWhile =
                    new InlineLiteralWhile(
                            new InlineLiteralCallback(
                                    condition,
                                    -1,
                                    receiverValue,
                                    bytecodeClosurePlans.get(condition)),
                            inlineCallbacks.get(0));
            /*
             * A Closure-literal receiver can never be admitted by a selected
             * standard Boolean capability (PreparedBooleanCall rejects a
             * non-Boolean receiver before preparing any callback), so no
             * unreachable Boolean inline region is emitted for this site.
             */
            inlineCallbacks = java.util.List.of();
        }

        /*
         * PERF026-D1/D2/D3: disjoint from each other (one versus two
         * parameters) and from the Boolean and while candidates, which
         * require zero-parameter literals.
         */
        InlineLiteralEach inlineEach = null;
        if (inlineIndexedEachCandidate || inlineTwoParameterEachCandidate) {
            CanonicalClosure callback =
                    (CanonicalClosure) send.arguments().get(0);
            inlineEach =
                    new InlineLiteralEach(
                            new InlineLiteralCallback(
                                    callback,
                                    0,
                                    argumentValues.get(0),
                                    bytecodeClosurePlans.get(callback)),
                            inlineTwoParameterEachCandidate);
        }

        emitPreparedInvocation(
                builder,
                result,
                preparedCall,
                childResult,
                resumeValue,
                inlineCallbacks,
                inlineWhile,
                inlineEach);

        if (tryDirectSendZero || tryDirectSendOne) {
            builder.endBlock();

            /*
             * Fused hit: no PreparedClosureCall, continuation scratch
             * or ReturnHome completion was required.
             */
            builder.beginBlock();
            builder.beginStoreLocal(result);
            /*
             * I091: an admitted callee may return a primitive carrier; only
             * a carrier-permitting consumer receives it unmaterialized.
             */
            if (!primitiveCarrierResult) {
                builder.beginMaterializePrimitiveNumericCarrier();
            }
            builder.emitLoadLocal(directSendResult);
            if (!primitiveCarrierResult) {
                builder.emitLoadConstant(true);
                builder.endMaterializePrimitiveNumericCarrier();
            }
            builder.endStoreLocal();
            builder.endBlock();

            builder.endIfThenElse();
        }

        if (tryCompactCanonicalIfTrue) {
            builder.endBlock();
            builder.beginBlock();
            emitCanonicalIfTrueNative(
                    builder,
                    compactBooleanResult,
                    result,
                    childResult,
                    resumeValue);
            builder.endBlock();
            builder.endIfThenElse();
        }

        if (tryPrimitiveNumericSend) {
            builder.endBlock();
            builder.beginBlock();
            builder.beginStoreLocal(result);
            if (primitiveCarrierResult) {
                builder.emitLoadLocal(primitiveNumericAttempt);
            } else {
                builder.beginMaterializePrimitiveNumericCarrier();
                builder.emitLoadLocal(primitiveNumericAttempt);
                builder.emitLoadConstant(true);
                builder.endMaterializePrimitiveNumericCarrier();
            }
            builder.endStoreLocal();
            builder.endBlock();
            builder.endIfThenElse();
        }

        builder.endBlock();
        builder.endTag(StandardTags.CallTag.class);
    }

    /**
     * PLAT044 B′ (PERF026-B1/B3) compile-time candidates: the supplied
     * positions, of a send with one or two non-spread arguments (the arities
     * of the standard Boolean callback capabilities), that hold an immediate
     * zero-parameter Closure literal whose body creates no nested Closure.
     * The literal's own root and plan cell exist (it is materialized as the
     * argument), so the body can also be lowered inline. Nested Closures are
     * excluded because capturing a callback-owned binding would need lexical
     * machinery B1 does not establish. Nothing here consults the selector:
     * whether, and for which position, the inline path is taken is decided at
     * run time, after ordinary selection, by {@link
     * ProtosBytecodeRootNode.PreparedBooleanCall#admitsInlineLiteralCallback}.
     */
    private static java.util.List<Integer> inlineLiteralCallbackCandidates(
            java.util.List<CanonicalExpression> arguments) {
        if (arguments.size() > 2) {
            return java.util.List.of();
        }
        java.util.List<Integer> positions = new java.util.ArrayList<>(2);
        for (int position = 0; position < arguments.size(); position++) {
            if (isInlineLiteralCallbackCandidate(arguments.get(position))) {
                positions.add(position);
            }
        }
        return positions;
    }

    /**
     * PLAT044 B′ (PERF026-C1) compile-time whileTrue pair candidate: the
     * receiver and the sole supplied argument are both inline literal
     * callback candidates ({@link #isInlineLiteralCallbackCandidate}). As for
     * Boolean candidates the selector is never consulted; the local loop is
     * taken only after ordinary selection prepared the canonical standard
     * whileTrue with exactly these two staged values ({@link
     * ProtosBytecodeRootNode.PreparedClosureCall#admitsInlineLiteralWhile}).
     */
    private static boolean isInlineLiteralWhileCandidate(CanonicalSend send) {
        return send.arguments().size() == 1
                && isInlineLiteralCallbackCandidate(send.receiver())
                && isInlineLiteralCallbackCandidate(send.arguments().get(0));
    }

    /**
     * PLAT044 B′ (PERF026-D1) compile-time indexed each candidate: the sole
     * supplied argument is an immediate Closure literal with exactly one
     * ordinary required positional parameter (no default, not rest) whose
     * body creates no nested Closure. This is deliberately separate from
     * {@link #isInlineLiteralCallbackCandidate}, so no Boolean or while site
     * gains a parameterized inline region. The selector is never consulted:
     * the local loop is taken only after ordinary selection prepared the
     * canonical standard Array.each or Bytes.each with exactly this staged
     * value ({@link
     * ProtosBytecodeRootNode.PreparedClosureCall#admitsInlineLiteralIndexedEach}),
     * and the formal is then bound by the ordinary parameter binding from the
     * prepared per-element activation.
     */
    private static boolean isInlineLiteralIndexedEachCandidate(
            java.util.List<CanonicalExpression> arguments) {
        return isInlineLiteralEachCandidate(arguments, 1);
    }

    /**
     * PLAT044 B′ (PERF026-D2/D3) compile-time two-parameter each candidate:
     * as {@link #isInlineLiteralIndexedEachCandidate}, but the literal has
     * exactly two ordinary required positional parameters (neither with a
     * default nor rest), matching the two arguments of a standard
     * {@code block(key, value)} or {@code block(name, value)} invocation. It
     * is disjoint from the one-parameter indexed candidate and from the
     * zero-parameter Boolean and while candidates. The source shape is only a
     * candidate: the local loop is taken only after ordinary selection
     * prepared the canonical standard Map.each, IdentityMap.each or
     * Environment.each with exactly this staged value ({@link
     * ProtosBytecodeRootNode.PreparedClosureCall#admitsInlineLiteralAssociationEach},
     * {@link
     * ProtosBytecodeRootNode.PreparedClosureCall#admitsInlineLiteralEnvironmentEach}),
     * and both formals are then bound by the ordinary parameter binding from
     * the prepared per-position activation. Any other shape keeps the
     * ordinary path, including its ordinary callback arity behavior.
     */
    private static boolean isInlineLiteralTwoParameterEachCandidate(
            java.util.List<CanonicalExpression> arguments) {
        return isInlineLiteralEachCandidate(arguments, 2);
    }

    private static boolean isInlineLiteralEachCandidate(
            java.util.List<CanonicalExpression> arguments,
            int parameterCount) {
        return arguments.size() == 1
                && arguments.get(0) instanceof CanonicalClosure closure
                && closure.parameters().size() == parameterCount
                && closure.parameters().stream()
                        .allMatch(parameter -> !parameter.rest()
                                && parameter.defaultValue().isEmpty())
                && !containsClosure(closure.body());
    }

    private static boolean isInlineLiteralCallbackCandidate(
            CanonicalExpression expression) {
        return expression instanceof CanonicalClosure closure
                && closure.parameters().isEmpty()
                && !containsClosure(closure.body());
    }

    private static boolean containsClosure(CanonicalExpression expression) {
        return switch (expression) {
            case CanonicalClosure closure -> true;
            case CanonicalSequence sequence ->
                    sequence.expressions().stream()
                            .anyMatch(CanonicalToBytecodeLowerer::containsClosure);
            case CanonicalAssign assign ->
                    assign.target().map(CanonicalToBytecodeLowerer::containsClosure).orElse(false)
                            || containsClosure(assign.value());
            case CanonicalCreate create ->
                    create.target().map(CanonicalToBytecodeLowerer::containsClosure).orElse(false)
                            || containsClosure(create.value());
            case CanonicalMultipleCreate create -> containsClosure(create.value());
            case CanonicalCall call ->
                    containsClosure(call.receiver()) || anyContainsClosure(call.arguments());
            case CanonicalSend send ->
                    containsClosure(send.receiver()) || anyContainsClosure(send.arguments());
            case CanonicalSuperSend send -> anyContainsClosure(send.arguments());
            case CanonicalCompose compose -> containsClosure(compose.object());
            case CanonicalDerivedInequality inequality ->
                    containsClosure(inequality.left()) || containsClosure(inequality.right());
            case CanonicalIdentity identity ->
                    containsClosure(identity.left()) || containsClosure(identity.right());
            case CanonicalNotIdentity identity ->
                    containsClosure(identity.left()) || containsClosure(identity.right());
            case CanonicalIndexedAssign assign ->
                    containsClosure(assign.receiver())
                            || containsClosure(assign.index())
                            || containsClosure(assign.value());
            case CanonicalMapConstruction map ->
                    containsClosure(map.factory())
                            || map.entries().stream()
                                    .anyMatch(
                                            entry ->
                                                    containsClosure(entry.key())
                                                            || containsClosure(entry.value()));
            case CanonicalMember member -> containsClosure(member.receiver());
            case CanonicalObject object ->
                    object.parent().map(CanonicalToBytecodeLowerer::containsClosure).orElse(false)
                            || containsClosure(object.body());
            case CanonicalReturn returnExpression -> containsClosure(returnExpression.value());
            case CanonicalSpread spread -> containsClosure(spread.expression());
            case CanonicalIntrinsic intrinsic -> false;
            case CanonicalLiteral literal -> false;
            case CanonicalLookup lookup -> false;
        };
    }

    private static boolean anyContainsClosure(
            java.util.List<CanonicalExpression> expressions) {
        return expressions.stream().anyMatch(CanonicalToBytecodeLowerer::containsClosure);
    }

    private void emitComposedCall(
            ProtosSemanticBytecodeRootNodeGen.Builder builder,
            CanonicalCall call,
            BytecodeLocal result,
            BytecodeLocal preparedCall,
            BytecodeLocal childResult,
            BytecodeLocal resumeValue) {
        if (result == null
                || preparedCall == null
                || childResult == null
                || resumeValue == null) {
            throw new AssertionError(
                    "Bytecode call result/scratch locals were not allocated");
        }

        CanonicalExpression receiver = call.receiver();
        boolean stageReceiver = requiresComposedInvocation(receiver);
        boolean stageArguments = hasComposedArgument(call.arguments());
        boolean spreadArguments = hasSpreadArgument(call.arguments());
        boolean tryDirectCall =
                !spreadArguments
                        && call.arguments().size() <= 1
                        && !currentInlineCallbackFrameNative;

        boolean stageInputs =
                stageReceiver
                        || stageArguments
                        || spreadArguments
                        || tryDirectCall;
        BytecodeLocal receiverValue = null;
        BytecodeLocal suppliedVector = null;
        java.util.List<BytecodeLocal> argumentValues =
                java.util.List.of();

        builder.beginTag(StandardTags.CallTag.class);
        builder.beginBlock();

        if (stageInputs) {
            receiverValue =
                    builder.createLocal(
                            "callReceiver",
                            null);
            if (stageReceiver) {
                emitBodyExpressionToLocal(
                        builder,
                        receiver,
                        receiverValue,
                        preparedCall,
                        childResult,
                        resumeValue);
            } else {
                builder.beginStoreLocal(receiverValue);
                emitExpression(builder, receiver);
                builder.endStoreLocal();
            }

            if (spreadArguments) {
                suppliedVector =
                        builder.createLocal(
                                "suppliedArgumentVector",
                                null);
                emitBodySpreadArgumentVector(
                        builder,
                        call.arguments(),
                        suppliedVector,
                        preparedCall,
                        childResult,
                        resumeValue);
            } else {
                argumentValues =
                        new java.util.ArrayList<>(
                                call.arguments().size());
                for (CanonicalExpression argument :
                        call.arguments()) {
                    BytecodeLocal argumentValue =
                            builder.createLocal(
                                    "callArgument",
                                    null);
                    emitBodyExpressionToLocal(
                            builder,
                            argument,
                            argumentValue,
                            preparedCall,
                            childResult,
                            resumeValue);
                    argumentValues.add(argumentValue);
                }
            }
        }

        BytecodeLocal directCallResult = null;

        if (tryDirectCall) {
            directCallResult =
                    builder.createLocal("directCallResult", null);

            builder.beginStoreLocal(directCallResult);
            if (call.arguments().isEmpty()) {
                builder.beginTryDirectCallZero();
            } else {
                builder.beginTryDirectCallOne();
            }

            builder.emitLoadLocal(receiverValue);
            emitInvocationCallerOperand(builder);

            if (!call.arguments().isEmpty()) {
                builder.emitLoadLocal(argumentValues.get(0));
            }

            if (call.arguments().isEmpty()) {
                builder.endTryDirectCallZero();
            } else {
                builder.endTryDirectCallOne();
            }
            builder.endStoreLocal();

            builder.beginIfThenElse();
            builder.beginIsDirectSendMiss();
            builder.emitLoadLocal(directCallResult);
            builder.endIsDirectSendMiss();

            builder.beginBlock();
        }

        builder.beginStoreLocal(preparedCall);
        if (spreadArguments) {
            builder.beginPrepareClosureCallVector();
            builder.emitLoadLocal(receiverValue);
            emitCurrentActivation(builder);
            builder.emitLoadLocal(suppliedVector);
            builder.endPrepareClosureCallVector();
        } else if (call.arguments().isEmpty() && currentInlineCallbackFrameNative) {
            builder.beginPrepareInlineClosureCall(
                    currentRootFrameNativeLocals,
                    currentRootFrameNativeLayout);
            if (stageInputs) {
                builder.emitLoadLocal(receiverValue);
            } else {
                emitExpression(builder, receiver);
            }
            emitCurrentInlineCallbackCall(builder);
            builder.endPrepareInlineClosureCall();
        } else if (call.arguments().isEmpty()) {
            builder.beginPrepareClosureCall();
            if (stageInputs) {
                builder.emitLoadLocal(receiverValue);
            } else {
                emitExpression(builder, receiver);
            }
            emitCurrentActivation(builder);
            builder.endPrepareClosureCall();
        } else {
            if (currentInlineCallbackFrameNative) {
                if (call.arguments().size() == 1) {
                    builder.beginPrepareInlineClosureCallOne(
                            currentRootFrameNativeLocals,
                            currentRootFrameNativeLayout);
                } else {
                    builder.beginPrepareInlineClosureCallArguments(
                            currentRootFrameNativeLocals,
                            currentRootFrameNativeLayout);
                }
            } else if (call.arguments().size() == 1) {
                builder.beginPrepareClosureCallOne();
            } else {
                builder.beginPrepareClosureCallArguments();
            }
            if (stageInputs) {
                builder.emitLoadLocal(receiverValue);
            } else {
                emitExpression(builder, receiver);
            }
            emitInvocationCallerOperand(builder);
            if (stageInputs) {
                for (BytecodeLocal argumentValue :
                        argumentValues) {
                    builder.emitLoadLocal(argumentValue);
                }
            } else {
                for (CanonicalExpression argument :
                        call.arguments()) {
                    emitExpression(builder, argument);
                }
            }
            if (currentInlineCallbackFrameNative) {
                if (call.arguments().size() == 1) {
                    builder.endPrepareInlineClosureCallOne();
                } else {
                    builder.endPrepareInlineClosureCallArguments();
                }
            } else if (call.arguments().size() == 1) {
                builder.endPrepareClosureCallOne();
            } else {
                builder.endPrepareClosureCallArguments();
            }
        }
        builder.endStoreLocal();


        emitPreparedInvocation(
                builder,
                result,
                preparedCall,
                childResult,
                resumeValue);

        if (tryDirectCall) {
            builder.endBlock();

            builder.beginBlock();
            builder.beginStoreLocal(result);
            builder.emitLoadLocal(directCallResult);
            builder.endStoreLocal();
            builder.endBlock();

            builder.endIfThenElse();
        }

        builder.endBlock();
        builder.endTag(StandardTags.CallTag.class);
    }

    /*
     * I091: a literal is a guest constant unless it is an Integer outside the signed-64 range,
     * which is an ordinary guest object minted with the executing Prelude on each evaluation.
     * The constant of a signed-64 Integer or Float literal is immutable, carries no prototype,
     * and compares by value, so one constant serves every execution domain.
     */
    private void emitLiteral(
            ProtosSemanticBytecodeRootNodeGen.Builder builder, CanonicalLiteral literal) {
        if (literal.kind() == CanonicalLiteral.Kind.NUMBER
                && numberLiteralDescriptor(literal) instanceof BigInteger large) {
            builder.beginLargeIntegerLiteral();
            emitSendCallerOperand(builder);
            builder.emitLoadConstant(large);
            builder.endLargeIntegerLiteral();
            return;
        }
        builder.emitLoadConstant(guestLiteralConstant(literal));
    }

    /*
     * I091: the Long or Double descriptor of a number literal that may be staged as a primitive
     * carrier, or null for every other literal (including a large Integer literal).
     */
    private Object primitiveNumberLiteralOrNull(CanonicalLiteral literal) {
        if (literal.kind() != CanonicalLiteral.Kind.NUMBER) {
            return null;
        }
        Object descriptor = numberLiteralDescriptor(literal);
        return descriptor instanceof Long || descriptor instanceof Double ? descriptor : null;
    }

    private Object numberLiteralDescriptor(CanonicalLiteral literal) {
        return numberLiteralDescriptors.computeIfAbsent(
                literal, number -> ProtosNumberLiteral.parse(number.value()));
    }

    /* The guest constant of a literal other than a large Integer literal. */
    private Object guestLiteralConstant(CanonicalLiteral literal) {
        return switch (literal.kind()) {
            case NUMBER -> numberLiteralDescriptor(literal) instanceof Long small
                    ? ProtosNumericValueSupport.integer(small)
                    : ProtosNumericValueSupport.floating((Double) numberLiteralDescriptor(literal));
            case STRING -> new ProtosStringValue(literal.value());
            case TRUE -> ProtosBooleanValue.TRUE;
            case FALSE -> ProtosBooleanValue.FALSE;
            case NULL -> ProtosNullValue.INSTANCE;
        };
    }
}
