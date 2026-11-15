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

import com.guillermomolina.protos.runtime.ProtosNullValue;
import com.guillermomolina.protos.runtime.ProtosObjectValue;
import com.guillermomolina.protos.runtime.numeric.ProtosNumberLiteral;
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
import com.guillermomolina.protos.semantic.ast.CanonicalNamedArgument;
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
import com.oracle.truffle.api.bytecode.LocalAccessor;
import com.oracle.truffle.api.bytecode.StackValue;
import com.oracle.truffle.api.instrumentation.StandardTags;
import com.oracle.truffle.api.source.Source;
import com.oracle.truffle.api.strings.TruffleString;
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
 * produced here is a real tagged {@link ProtosBytecodeRootNode}. Automatic
 * physical root/body tags expose its execution, while the semantic activation
 * marker distinguishes it from auxiliary roots of the same interpreter, and
 * lexically nested Closure roots share that interpreter's {@code
 * BytecodeRootNodes} group. Structured prepared invocations are not lowered
 * here; they enter the auxiliary {@link ProtosStructuredDispatchLowerer} root
 * once per invocation.</p>
 */
final class CanonicalToBytecodeLowerer {
    /**
     * Name of the Bytecode local holding the {@link
     * Object} carrier of a retired PLAT044 B′ inline
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
     * I096 (PLAT057-B) expansion budget: the largest exact positional arity
     * whose ordinary Closure call site may receive a lexically inlined body.
     * A wider call keeps the unchanged physical call, so a single site never
     * grows the caller root by an unbounded staging and parameter prologue.
     */
    static final int MAX_INLINE_CLOSURE_CALL_ARGUMENTS = 8;

    private final ProtosLanguage language;
    private final Source source;

    /**
     * I101-A: whether the standard root {@code Object} was already published
     * (FROZEN) when this lowerer was created. Publication is irreversible and
     * validates the standard Boolean and {@code call} behaviors, so a
     * {@code true} snapshot holds for every execution of every site lowered
     * here; a {@code false} snapshot keeps generic dispatch. The snapshot is
     * taken once, before any group is created, so a {@code BytecodeRootNodes}
     * reparse after a later publication replays exactly the decisions of the
     * original parse instead of acquiring a different executable path.
     */
    private final boolean standardObjectPublished;

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
            ProtosBytecodeRootNode root,
            ProtosClosureExecutionPlanCell cell) {}

    private final java.util.IdentityHashMap<CanonicalCompose, java.util.List<String>>
            bytecodeComposeReservedNames = new java.util.IdentityHashMap<>();
    /*
     * I106 (PLAT061-B): canonical proofs of this compilation unit, shared by
     * every parse of its retained Bytecode parser and never by another
     * lowerer or Context.
     */
    private final CanonicalCompilationFacts compilationFacts =
            new CanonicalCompilationFacts();
    /*
     * I107: generic prepared invocations (each carrying the continuation
     * composition) emitted so far into the genuine root being lowered. A root
     * planned as non-suspending emits none; an emitted one invalidates the
     * plan before publication instead of publishing an unsound capability.
     * Saved and restored per root.
     */
    private boolean currentRootEmittedContinuationPath;
    /*
     * I112 / PLAT062-T (D204): a terminal Closure invocation emitted into the
     * genuine root being lowered. It emits no Yield, but the invoked call may
     * still suspend and the root then returns its terminal transport, so the
     * root is never published as non-suspending. Independent of the
     * continuation-path flag; saved and restored per root.
     */
    private boolean currentRootEmittedTerminalInvocation;
    /*
     * an emitted '+' site of the physical root being lowered (its
     * inline Closure bodies included) whose IsStandardNumericSelection may need
     * the observing Prelude to select the standard String behavior, and
     * whose demand is not yet discharged by a caller-need registration. A
     * nested capturing root propagates its undischarged demand to its
     * creator, whose Prelude it captures. Saved and restored per root.
     *
     * A dynamically dispatched invocation (noteDynamicInvocation) is the
     * same demand: its callee may be a non-capturing Closure that owns no
     * Prelude and declares NEEDS_CALLER_PRELUDE, which this root can only
     * forward if its own caller transported one.
     */
    private boolean currentRootMayRequirePrelude;
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
    /*
     * the Bytecode DSL locals of the genuine CLOSURE root being lowered,
     * keyed by declared name: the value of each LOCAL binding, or the cell
     * reference of each CELL binding. Empty for a module ROOT, whose
     * bindings live in its module context (NAME storage).
     */
    private java.util.Map<String, BytecodeLocal> currentRootFrameLocals =
            java.util.Map.of();

    /*
     * the cell references reachable from the root being lowered, keyed
     * by binding identity: the root's own CELL bindings and the free cells
     * its Closure carries, each copied once at entry into a root-scoped
     * Bytecode DSL local. Saved and restored with the other per-root state.
     */
    private java.util.Map<CanonicalBindingIdentity, BytecodeLocal> currentRootCellLocals =
            java.util.Map.of();

    /*
     * the root's own lexical CELL identities that InlineStoragePlan
     * represents physically as LOCAL, because only inline Closures of this
     * root capture them. Saved and restored with the other per-root state.
     */
    /*
     * The context publication state of the inline callback being emitted
     * when it observes its context: the per-invocation activation cache, the
     * layout of its region locals and its Closure for the activation.
     */
    private BytecodeLocal currentInlineContextCache;
    /* The cache allocated by the inline body wrapper for the body it opens next. */
    private BytecodeLocal pendingInlineContextCache;
    private ProtosBytecodeRootNode.LexicalFrameLayout currentInlineContextLayout;
    private ProtosSelectedCallbackPlan currentInlineContextCallback;

    private java.util.Set<CanonicalBindingIdentity> currentRootPromotedCells =
            java.util.Set.of();

    /* the inline decisions of this lowerer, as consulted at emission. */
    private final CanonicalInlineStoragePlan.InlinePolicy inlineStoragePolicy = inlinePolicyUnder(null);

    /**
     * The inline decisions under the admission {@code context} of the site
     * being decided, or under the live emission context when it is
     * {@code null}.
     */
    private CanonicalInlineStoragePlan.InlinePolicy inlinePolicyUnder(ControlSiteContext context) {
        return new CanonicalInlineStoragePlan.InlinePolicy() {
                @Override
                public boolean admitsInline(CanonicalClosure closure) {
                    return context == null
                            ? admitsInlineControlClosure(closure)
                            : admitsInlineControlClosure(closure, context);
                }

                @Override
                public boolean inlineCallbackPosition(CanonicalSend send, int position) {
                    ControlSelector control = ControlSelector.of(send.message(), send.arguments());
                    if (control != null) {
                        return control.callbackPosition(position)
                                && (send.receiver() instanceof CanonicalClosure
                                        || canonicalBooleanLiteral(send.receiver()) != null
                                        || admitsSelectedCallbacks(send.arguments()));
                    }
                    /*
                     * The right operand of and/or runs inline for a canonical
                     * receiver. It is an inline position only when any other
                     * receiver can receive it over the live frame; otherwise
                     * that receiver gets a genuine Closure with cells.
                     */
                    return position == 1
                            && !send.contextual()
                            && nativeBooleanControl(send.message(), send.arguments()) != null
                            && admitsSelectedCallbacks(send.arguments());
                }
            };
    }

    /*
     * PERF034-C: direct BytecodeLocal storage is admitted only for a
     * statically complete, effect-free, straight-line local body.
     * This is per-root lowering state, not runtime/global state.
     */
    private java.util.Set<String> currentRootScalarLocals =
            java.util.Set.of();

    /*
     *: the parse configuration selected only when the
     * internal trace probe is enabled at JVM start; it adds the experimental
     * Call1 instrumentations, which are otherwise absent from the bytecode.
     */
    private static final BytecodeConfig EXPERIMENTAL_TRACE_CONFIG =
            ProtosBytecodeRootNodeGen.newConfigBuilder()
                    .addInstrumentation(ProtosBytecodeRootNode.InstrumentCall.class)
                    .addInstrumentation(ProtosBytecodeRootNode.InstrumentCallReturn.class)
                    .build();

    /*
     * I096-B1 (PLAT057-B) per-root lowering state, recomputed from the
     * canonical AST on every parse of the root (including Bytecode reparses)
     * and saved/restored around nested roots. The first maps each admitted
     * direct call site of the genuine root sequence to the candidate body of
     * the binding its receiver resolves to; it is never a proof of the
     * called value. The second records the Closure literals whose
     * materialization this parse of the root has already emitted, so a
     * candidate is used only when its creation precedes the site in this very
     * parse and every reparse emits the same instructions.
     */
    private java.util.Map<CanonicalCall, CanonicalClosure> currentRootBindingCallCandidates =
            java.util.Map.of();
    private java.util.Set<CanonicalClosure> currentRootMaterializedClosures =
            java.util.Set.of();

    /*
     * I096 (PLAT057-B) nested-Closure admission. The first is true while an
     * admitted candidate body is lowered inline in its caller root (reset for
     * every nested root); the second holds the Closure definitions whose own
     * root this parse of the group has already lowered, and is reset at the
     * start of every parse, including Bytecode reparses. Together they let a
     * Closure literal reached again inside an inline body reuse the root and
     * plan its callee's own root already lowered in this parse, so the group
     * never gains a duplicate root and every reparse replays the same roots
     * in the same order (see bytecodeClosurePlan).
     */
    private boolean currentInlineClosureCallBody;
    private java.util.Set<CanonicalClosure> closureRootsLoweredThisParse =
            java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());

    /*
     * I095-B4: lowering-time marker for a physically inline
     * zero-parameter callback whose reads were proven to belong
     * to this root. It is saved/restored around the inline body.
     */
    private boolean currentNormalDirectParentCaptureReads;


    /*
     * I095-B6a: independent, lexically scoped Bytecode locals belonging to
     * one inline callback. Only the existing PERF034/036 straight-line
     * scalar proof permits this lane. No callback invocation/activation is
     * created, and none of these locals can alias a root binding by name.
     */
    // Physical storage is in the parent root; lexical ownership remains the callback.
    // Tooling uses this prefix to project a virtual callback scope on demand.
    /* root-level Closure creation never materializes its creator (see beginClosureCapture). */
    private static final boolean COMPACT_ROOT_CLOSURE_CAPTURE = true;

    /* the name prefix of the root local holding one free cell. */
    static final String FREE_CELL_LOCAL_PREFIX = "i115FreeCell:";
    static final String NORMAL_INLINE_CALLBACK_BINDING_LOCAL_PREFIX =
            "i095CallbackScalar:";
    // Preorder identities of semantic inline RootTags within the current physical root.
    // Stored as BytecodeLocal info and replayed with the tags during instrumentation reparses.
    private int nextInlineCallbackRegion;
    private int currentInlineCallbackRegion;

    private java.util.Map<String, BytecodeLocal> currentNormalCallbackScalarLocals =
            java.util.Map.of();
    private CanonicalBindingAnalysis currentNormalCallbackScalarAnalysis;
    private CanonicalLexicalScope currentNormalCallbackScalarScope;

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
     * PLAT067-B: one proof-optimized lowering for every Context. Whether the
     * host enables a debugger never changes what is emitted; Truffle
     * materializes source, tag and instrumentation information on demand.
     */
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
        this.standardObjectPublished = ProtosObjectValue.rootObject().isFrozen();
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
            return compilationFacts.isolatedClosureAnalysis(activationDefinition);
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
        return compilationFacts.isolatedClosureAnalysis(definition);
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
     * I106 (PLAT061-B): resolves the analysis and scope of one root and
     * proves its canonical facts before that root's builder section opens.
     * Physical facts (layout, carriers, BytecodeLocals) stay with emission.
     */
    private CanonicalCompilationFacts.RootFacts prepareRootFacts(
            CanonicalSequence sequence,
            CanonicalClosure activationDefinition,
            CanonicalBindingAnalysis analysis) {
        return compilationFacts.rootFacts(
                sequence,
                activationDefinition,
                analysis,
                rootScopeFor(analysis, activationDefinition));
    }

    /**
     * I107 (PLAT061-B) immutable emission plan of one root, prepared before
     * its builder section opens ({@code create()} for a group root, {@code
     * beginRoot()} for a nested one). It holds only canonical decisions:
     * the root facts, the identity of the effective root and the proofs of the canonical body and of the
     * effective root under this lowerer's publication snapshot. Every parse of the group consumes the same decisions.
     */
    record RootEmissionPlan(
            CanonicalCompilationFacts.RootFacts rootFacts,
            CanonicalCompilationFacts.EffectiveRoot effectiveRoot,
            CanonicalNonSuspensionAnalysis.Result canonicalBody,
            CanonicalNonSuspensionAnalysis.Result plannedEffectiveRoot) {}

    /**
     * The control-admission state of one emission point; at a root's entry
     * it is fully determined by the root's analysis and scope (a CLOSURE
     * root's locals are exactly its declared names, a module ROOT has none;
     * see {@link #emitRootBody}).
     */
    private record ControlSiteContext(
            CanonicalBindingAnalysis analysis,
            CanonicalLexicalScope topScope,
            java.util.Set<String> frameNames,
            boolean hasFrameLayout,
            boolean inlineCallbackFrameNative,
            boolean activationLocalPresent) {

        static ControlSiteContext rootEntry(
                CanonicalBindingAnalysis analysis, CanonicalLexicalScope scope) {
            java.util.Set<String> names =
                    scope.kind() == CanonicalLexicalScope.Kind.CLOSURE
                            ? scope.declaredNames()
                            : java.util.Set.of();
            return new ControlSiteContext(
                    analysis, scope, names, !names.isEmpty(), false, false);
        }
    }

    /*
     * The bindings an inline callback may reach in its physical parent: the
     * parent's plain locals and the cells it owns. A cell is reached through
     * the same cell local by the parent and by the inline body, so captures
     * by reference are preserved. This is the same name set rootEntry gives
     * the analysis that selects inline controls, so selection and emission
     * agree on every callback.
     */
    private ControlSiteContext currentControlSiteContext() {
        java.util.Set<String> names =
                new java.util.LinkedHashSet<>(currentRootFrameLocals.keySet());
        for (CanonicalBindingIdentity identity : currentRootCellLocals.keySet()) {
            if (identity.owner() == currentRootTopScope) {
                names.add(identity.name());
            }
        }
        return new ControlSiteContext(
                currentRootAnalysis,
                currentRootTopScope,
                java.util.Collections.unmodifiableSet(names),
                !names.isEmpty(),
                false,
                currentActivationLocal != null);
    }

    private RootEmissionPlan prepareRootPlan(
            CanonicalSequence sequence,
            CanonicalClosure activationDefinition,
            CanonicalBindingAnalysis analysis) {
        CanonicalCompilationFacts.RootFacts rootFacts =
                prepareRootFacts(sequence, activationDefinition, analysis);
        CanonicalLexicalScope scope = rootFacts.scope();
        /*
         * Inline control regions, however deeply nested, are emitted in this
         * root's frame, so their static admission is decided under this root's
         * entry context, exactly as emission decides it.
         */
        ControlSiteContext rootContext = ControlSiteContext.rootEntry(analysis, scope);
        CanonicalNonSuspensionAnalysis.SendSelection selection =
                (send, ownerAnalysis, ownerScope) ->
                        selectedControlSend(
                                send,
                                ControlSiteContext.rootEntry(ownerAnalysis, ownerScope),
                                rootContext);
        CanonicalNonSuspensionAnalysis.Result canonicalBody =
                compilationFacts.canonicalBodyNonSuspension(
                        sequence, activationDefinition, analysis, scope);
        Object canonicalRoot = activationDefinition == null ? sequence : activationDefinition;
        CanonicalCompilationFacts.EffectiveRoot effectiveRoot =
                compilationFacts.effectiveRoot(canonicalRoot, analysis, scope);
        CanonicalNonSuspensionAnalysis.Result planned =
                compilationFacts.newAnalysis(analysis, scope, activationDefinition, selection)
                        .proveEffectiveRoot(
                                effectiveRoot,
                                activationDefinition,
                                sequence.expressions(),
                                activationDefinition != null
                                        && compilationFacts.mayObserveReturnHome(
                                                activationDefinition),
                                "planned effective root");
        compilationFacts.publishNonSuspension(planned);
        return new RootEmissionPlan(
                rootFacts, effectiveRoot, canonicalBody, planned);
    }

    /**
     * I107 selection producer: an admitted Boolean-control send whose receiver
     * is a canonical Boolean literal is statically selected by the frozen
     * standard root ({@link #normalBooleanControlSite}); it evaluates its
     * non-Closure arguments and runs exactly its INLINE callback. A dynamic
     * receiver can take the ordinary send on MISS and stays unselected.
     */
    private CanonicalNonSuspensionAnalysis.SelectedSend selectedControlSend(
            CanonicalSend send, ControlSiteContext context, ControlSiteContext rootContext) {
        if (!(send.receiver() instanceof CanonicalLiteral receiver)) {
            return null;
        }
        /*
         * A conditional control whose literal receiver decides it statically
         * is emitted without a send ({@link #emitControl}): it evaluates
         * no operand besides literal Closures and runs only the selected
         * callback inline.
         */
        StaticControlSelection staticControl = standardObjectPublished
                ? classifyStaticControlSelection(
                        ControlSelector.of(send.message(), send.arguments()),
                        receiver, send.arguments(), rootContext)
                : null;
        if (staticControl != null && !staticControl.control().loop()) {
            java.util.List<CanonicalClosure> selectedBodies =
                    staticControl.eliminable()
                            ? java.util.List.of()
                            : java.util.List.of((CanonicalClosure) send.arguments()
                                    .get(staticControl.selectedPosition() - 1));
            return new CanonicalNonSuspensionAnalysis.SelectedSend(
                    java.util.List.of(),
                    selectedBodies,
                    java.util.EnumSet.of(
                            CanonicalNonSuspensionAnalysis.Precondition.STANDARD_OBJECT_PUBLISHED));
        }
        NormalBooleanControlSite site = normalBooleanControlSite(send, context);
        if (site == null) {
            return null;
        }
        boolean value = receiver.kind() == CanonicalLiteral.Kind.TRUE;
        java.util.List<CanonicalExpression> evaluated = new java.util.ArrayList<>();
        for (CanonicalExpression argument : send.arguments()) {
            if (!(argument instanceof CanonicalClosure)) {
                evaluated.add(argument);
            }
        }
        java.util.List<CanonicalClosure> bodies =
                site.path(value) == NormalBooleanControlPath.INLINE
                        ? java.util.List.of((CanonicalClosure)
                                send.arguments().get(site.control().selectedIndex(value)))
                        : java.util.List.of();
        return new CanonicalNonSuspensionAnalysis.SelectedSend(
                java.util.List.copyOf(evaluated),
                bodies,
                java.util.EnumSet.of(
                        CanonicalNonSuspensionAnalysis.Precondition.STANDARD_OBJECT_PUBLISHED));
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
     *
     * <p>I096 (PLAT057-B): inside an inline candidate body, a definition
     * whose root this parse already lowered (inside the callee's own root,
     * which always precedes the inline site) is not lowered again; the
     * inline copy materializes its instances with that same root and plan,
     * exactly as a physical invocation of the callee would. The choice
     * depends only on the order of this parse, so a reparse makes it
     * identically. Outside inline bodies nothing changes.
     *
     * <p>I101-A: a callee root omits the roots of the Boolean
     * callbacks it inlines or never selects, so its inline copy, which
     * keeps the ordinary send, lowers such a callback here for the first
     * time.
     */
    private ProtosClosureExecutionPlanCell bytecodeClosurePlan(
            ProtosBytecodeRootNodeGen.Builder builder,
            CanonicalClosure definition) {
        ProtosClosureExecutionPlanCell existing = bytecodeClosurePlans.get(definition);
        boolean loweredInThisParse = !closureRootsLoweredThisParse.add(definition);
        if (currentInlineClosureCallBody && loweredInThisParse && existing != null) {
            currentRootMaterializedClosures.add(definition);
            return existing;
        }

        // publish the pending cell before emitting its root.
        // Freezing the cell still happens after create() completes.
        ProtosClosureExecutionPlanCell cell = existing;
        if (cell == null) {
            cell = ProtosClosureExecutionPlanCell.pendingGroup();
            bytecodeClosurePlans.put(definition, cell);
        }

        ProtosBytecodeRootNode nestedRoot =
                lowerNestedClosureRoot(builder, definition);
        // I096-B1: after the nested root restored this root's state.
        currentRootMaterializedClosures.add(definition);

        if (existing != null) {
            return existing;
        }
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

    ProtosBytecodeRootNode lowerRoot(CanonicalSequence sequence) {
        return lowerRoot(sequence, null);
    }

    ProtosBytecodeRootNode lowerClosureActivationRoot(
            CanonicalClosure definition) {
        Objects.requireNonNull(definition, "definition");
        validateSupportedDefaults(definition);
        return lowerRoot(definition.body(), definition);
    }

    private ProtosBytecodeRootNode lowerRoot(
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
         * one shared BytecodeRootNodes<ProtosBytecodeRootNode> group.
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
        RootEmissionPlan rootPlan =
                prepareRootPlan(
                        sequence,
                        activationDefinition,
                        bindingAnalysisFor(sequence, activationDefinition));

        java.util.List<PendingGroupClosure> savedPendingGroupClosures =
                pendingGroupClosures;
        pendingGroupClosures = new java.util.ArrayList<>();
        try {
            BytecodeRootNodes<ProtosBytecodeRootNode> roots =
                    ProtosBytecodeRootNodeGen.create(
                            language,
                            ProtosExperimentalTraceHooks.ENABLED
                                    ? EXPERIMENTAL_TRACE_CONFIG
                                    : BytecodeConfig.DEFAULT,
                            builder -> {
                                java.util.Set<CanonicalClosure> savedLoweredThisParse =
                                        closureRootsLoweredThisParse;
                                closureRootsLoweredThisParse =
                                        java.util.Collections.newSetFromMap(
                                                new java.util.IdentityHashMap<>());
                                try {
                                    /*
                                     * Root-inside-Source is the Bytecode DSL
                                     * shape that gives every root in the group
                                     * a reliable exact source section once lazy
                                     * source information is materialized.
                                     */
                                    builder.beginSource(source);
                                    lowerRootInto(
                                            builder,
                                            sequence,
                                            activationDefinition,
                                            rootPlan);
                                    builder.endSource();
                                } finally {
                                    closureRootsLoweredThisParse = savedLoweredThisParse;
                                }
                            });

            // Immediate cached-tier admission.
            // Specialize source-root locals from the first invocation.
            for (ProtosBytecodeRootNode root : roots.getNodes()) {
                if (root.isSemanticActivationRoot()) {
                    root.getBytecodeNode().setUncachedThreshold(0);
                }
            }

            for (PendingGroupClosure pending : pendingGroupClosures) {
                pending.cell()
                        .freeze(
                                ProtosClosureExecutionPlan.bytecode(
                                        pending.definition(),
                                        language,
                                        source,
                                        pending.bindingAnalysis(),
                                        pending.root(),
                                        compilationFacts.mayObserveReturnHome(
                                                pending.definition())));
            }

            return roots.getNode(0);
        } finally {
            pendingGroupClosures = savedPendingGroupClosures;
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
     *
     * <p>I106/I107: {@code rootPlan} was prepared by the caller before this
     * root's builder section opened and is not proven again here.
     */
    private ProtosBytecodeRootNode lowerRootInto(
            ProtosBytecodeRootNodeGen.Builder builder,
            CanonicalSequence sequence,
            CanonicalClosure activationDefinition,
            RootEmissionPlan rootPlan) {
        CanonicalCompilationFacts.RootFacts rootFacts = rootPlan.rootFacts();
        CanonicalBindingAnalysis analysisForThisRoot = rootFacts.bindingAnalysis();
        CanonicalLexicalScope scopeForThisRoot = rootFacts.scope();
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
        java.util.Map<CanonicalBindingIdentity, BytecodeLocal> savedCellLocals =
                currentRootCellLocals;
        java.util.Set<CanonicalBindingIdentity> savedPromotedCells =
                currentRootPromotedCells;
        java.util.Set<String> savedScalarLocals = currentRootScalarLocals;
        java.util.Map<CanonicalCall, CanonicalClosure> savedBindingCallCandidates =
                currentRootBindingCallCandidates;
        java.util.Set<CanonicalClosure> savedMaterializedClosures =
                currentRootMaterializedClosures;
        int savedNextInlineCallbackRegion = nextInlineCallbackRegion;
        int savedInlineCallbackRegion = currentInlineCallbackRegion;
        boolean savedInlineClosureCallBody = currentInlineClosureCallBody;
        boolean savedContinuationPath = currentRootEmittedContinuationPath;
        boolean savedTerminalInvocation = currentRootEmittedTerminalInvocation;
        boolean savedMayRequirePrelude = currentRootMayRequirePrelude;
        /*
         * exactly the MaterializeCurrentClosure flags its creator
         * emits (with the creator's analysis, still current here). When all
         * three are false the Closure is the lightweight non-capturing
         * representation, which owns no Prelude: its root reads only a
         * Prelude its caller transports. A creator with an explicit
         * activation (inline object region) emits MaterializeClosure, which
         * always retains the creator's Prelude.
         */
        boolean mayLackOwnPrelude = activationDefinition != null
                && savedActivationLocal == null
                && !CanonicalCompilationFacts.capturesCreatorContext(
                        activationDefinition, savedAnalysis)
                && !CanonicalCompilationFacts.requiresInheritedLexicalEnvironment(
                        activationDefinition, savedAnalysis)
                && !compilationFacts.mayObserveReturnHome(activationDefinition);
        boolean rootMayRequirePrelude = false;
        try {
            nextInlineCallbackRegion = 0;
            currentInlineCallbackRegion = 0;
            currentInlineClosureCallBody = false;
            currentRootEmittedContinuationPath = false;
            currentRootEmittedTerminalInvocation = false;
            currentRootMayRequirePrelude = false;
            /*
             * I096-B1: a Closure created by a module root owns a fresh
             * ReturnHome, which the carrier never admits, so a module root
             * would only grow by regions that always fall back.
             */
            currentRootBindingCallCandidates =
                    activationDefinition == null
                            ? java.util.Map.of()
                            : compilationFacts.bindingClosureCallCandidates(
                                    sequence,
                                    activationDefinition,
                                    analysisForThisRoot,
                                    scopeForThisRoot);
            currentRootMaterializedClosures =
                    java.util.Collections.newSetFromMap(
                            new java.util.IdentityHashMap<>());
            currentRootAnalysis = analysisForThisRoot;
            currentRootTopScope = scopeForThisRoot;
            currentRootFrameLocals = java.util.Map.of();
            currentRootCellLocals = java.util.Map.of();
            currentRootPromotedCells = java.util.Set.of();
            currentActivationLocal = null;
            currentRootScalarLocals = java.util.Set.of();
            ProtosBytecodeRootNode root = emitRootBody(
                    builder,
                    sequence,
                    activationDefinition,
                    rootPlan);
            rootMayRequirePrelude = currentRootMayRequirePrelude;
            if (rootMayRequirePrelude && mayLackOwnPrelude) {
                /*
                 * A non-capturing Closure retains its creator's Prelude, so
                 * its root needs no caller transport declared in advance; the
                 * rare creator without an admitted Prelude is covered by the
                 * runtime caller-need registration. The creator does not
                 * inherit this root's Prelude requirement.
                 */
                rootMayRequirePrelude = false;
            }
            return root;
        } finally {
            nextInlineCallbackRegion = savedNextInlineCallbackRegion;
            currentInlineCallbackRegion = savedInlineCallbackRegion;
            currentRootMayRequirePrelude =
                    savedMayRequirePrelude || rootMayRequirePrelude;
            currentRootEmittedContinuationPath = savedContinuationPath;
            currentRootEmittedTerminalInvocation = savedTerminalInvocation;
            currentRootAnalysis = savedAnalysis;
            currentRootTopScope = savedTopScope;
            currentRootFrameLocals = savedFrameLocals;
            currentRootCellLocals = savedCellLocals;
            currentRootPromotedCells = savedPromotedCells;
            currentActivationLocal = savedActivationLocal;
            currentRootScalarLocals = savedScalarLocals;
            currentRootBindingCallCandidates = savedBindingCallCandidates;
            currentRootMaterializedClosures = savedMaterializedClosures;
            currentInlineClosureCallBody = savedInlineClosureCallBody;
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
    private ProtosBytecodeRootNode lowerNestedClosureRoot(
            ProtosBytecodeRootNodeGen.Builder builder,
            CanonicalClosure definition) {
        validateSupportedDefaults(definition);
        RootEmissionPlan rootPlan =
                prepareRootPlan(
                        definition.body(),
                        definition,
                        bindingAnalysisForNestedClosure(definition));
        return lowerRootInto(
                builder,
                definition.body(),
                definition,
                rootPlan);
    }

    private ProtosBytecodeRootNode emitRootBody(
            ProtosBytecodeRootNodeGen.Builder builder,
            CanonicalSequence sequence,
            CanonicalClosure activationDefinition,
            RootEmissionPlan rootPlan) {
        CanonicalCompilationFacts.RootFacts rootFacts = rootPlan.rootFacts();
        CanonicalLexicalScope scopeForThisRoot = rootFacts.scope();
        SourceSpan rootSpan = sequence.span();

        builder.beginSourceSection(
                rootSpan.startOffset(),
                rootSpan.length());
        builder.beginRoot();

        /*
         * the storage of every binding of this root is fixed here,
         * before its body is emitted, from the binding analysis. A module
         * ROOT allocates no local for its declarations: they are NAME
         * bindings created in its module context. A CLOSURE root allocates
         * exactly one root-scoped Bytecode DSL local per declared binding,
         * including parameters (the value of a LOCAL binding or the cell
         * reference of a CELL binding), plus one local per free cell its
         * Closure carries. Allocating a local does not establish the binding.
         *
         * I111-A: these locals are created before the root Block so they
         * stay root-scoped and are live at every location of this root,
         * including the EnterRoot entry location.
         */
        java.util.Map<String, BytecodeLocal> frameLocals =
                new java.util.LinkedHashMap<>();
        java.util.Map<CanonicalBindingIdentity, BytecodeLocal> cellLocals =
                new java.util.LinkedHashMap<>();
        if (scopeForThisRoot.kind() == CanonicalLexicalScope.Kind.CLOSURE) {
            /*
             * allocate positional parameter locals first, in
             * declaration order. LocalRangeAccessor requires a contiguous
             * allocation range. Other declared locals follow that range.
             */
            if (activationDefinition != null) {
                for (CanonicalParameter parameter : activationDefinition.parameters()) {
                    String name = parameter.name();
                    if (scopeForThisRoot.declaredNames().contains(name)
                            && !frameLocals.containsKey(name)) {
                        frameLocals.put(name, builder.createLocal(name, null));
                    }
                }
            }
            for (String name : scopeForThisRoot.declaredNames()) {
                if (!frameLocals.containsKey(name)) {
                    frameLocals.put(name, builder.createLocal(name, null));
                }
            }
            /*
             * the physical storage plan is fixed before the prologue,
             * from this root's analysis and the same inline policy the
             * emission sites use. A promoted identity keeps its ordinary
             * local and gets no cell.
             */
            currentRootFrameLocals = java.util.Collections.unmodifiableMap(frameLocals);
            currentRootPromotedCells = CanonicalInlineStoragePlan.promotedCells(
                    sequence, activationDefinition, scopeForThisRoot,
                    currentRootAnalysis, inlineStoragePolicy);
            for (String name : scopeForThisRoot.declaredNames()) {
                CanonicalBindingIdentity identity = scopeForThisRoot.identity(name);
                if (scopeForThisRoot.needsCell(name)
                        && !currentRootPromotedCells.contains(identity)) {
                    cellLocals.put(identity, frameLocals.get(name));
                }
            }
            for (CanonicalBindingIdentity free : scopeForThisRoot.freeCells()) {
                cellLocals.put(
                        free,
                        builder.createLocal(FREE_CELL_LOCAL_PREFIX + free.name(), null));
            }
        }
        currentRootFrameLocals = java.util.Collections.unmodifiableMap(frameLocals);
        currentRootCellLocals = java.util.Collections.unmodifiableMap(cellLocals);

        /*
         * This physical root is a genuine semantic source activation.
         * Its physical RootTag/RootBodyTag are emitted automatically by the
         * Bytecode DSL; inline callbacks add their own semantic RootTags.
         */
        builder.beginBlock();

        /*
         * PLAT042 B′ / PERF025-H1: this root is entered directly (no
         * wrapper) and has no activation prologue. A compact source-call
         * frame stays compact until an operation that needs the rich
         * activation loads it through emitCurrentActivation.
         */
        emitCellPrologue(builder, scopeForThisRoot);

        currentRootScalarLocals =
                currentRootFrameLocals.isEmpty()
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
            /*
             * every Closure root binds its parameters through the one
             * parameter lowering: each supplied argument is stored directly
             * into its classified LOCAL or CELL storage.
             */
            emitClosureParameterBindings(
                    builder,
                    activationDefinition,
                    defaultValue,
                    defaultPreparedCall,
                    defaultChildResult,
                    defaultResumeValue);
        }

        boolean loweringRefinementAccepted = true;
        CanonicalExpression minimalTerminal =
                sequence.expressions().size() == 1
                        ? sequence.expressions().get(0)
                        : null;

        Object constant = null;
        if (activationDefinition != null
                && activationDefinition.parameters().isEmpty()
                && scopeForThisRoot.declaredNames().isEmpty()
                && minimalTerminal != null) {
            constant = i101Constant(
                    minimalTerminal, java.util.Map.of(), scopeForThisRoot);
        }

        if (constant instanceof Long
                || constant instanceof Boolean) {
            /*
             * PLAT067-B: the constant return is the surviving executable
             * location of the source statement, so it carries that
             * statement's passive StatementTag + ExpressionTag (its exact
             * span), as the ordinary
             * literal-carrier terminal does. No operation is added and
             * BytecodeConfig.DEFAULT materializes no tag instruction.
             */
            beginStatement(builder, sequence.expressions().get(0));
            builder.beginReturn();
            builder.emitLoadConstant(constant);
            builder.endReturn();
            endStatement(builder);
            loweringRefinementAccepted = acceptsLoweringRefinement(
                    publishLoweringRefinement(rootPlan, "I101 minimal constant body"));
        } else if (sequence.expressions().isEmpty()) {
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
            boolean numericTerminal =
                    last instanceof CanonicalSend numericSend
                            && primitiveNumericSendCandidate(numericSend)
                            && terminalNumericOperand(numericSend.receiver())
                            && terminalNumericOperand(numericSend.arguments().get(0));
            boolean emptyControlTerminal = isI115EliminableControl(last);

            if (numericTerminal || emptyControlTerminal) {
                if (expressions.size() > 1) {
                    emitDiscardedPrefix(
                            builder, expressions.subList(0, expressions.size() - 1));
                }
                builder.beginReturn();
                beginStatement(builder, last);
                if (emptyControlTerminal) {
                    builder.emitLoadConstant(ProtosNullValue.INSTANCE);
                } else if (admitsTerminalNumericSend((CanonicalSend) last)) {
                    emitTerminalNumericOperation(builder, (CanonicalSend) last);
                } else {
                    emitDirectNumericSendValue(
                            builder, (CanonicalSend) last, null, null, null, true);
                }
                endStatement(builder);
                builder.endReturn();
            } else if (scalarTerminal || directTerminal || closureTerminal) {
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
                /*
                 * I112: a terminal read returns from the root as its last
                 * operation, so the Builder appends no implicit fallthrough
                 * return (load.null, epilogue, return) after the statement.
                 */
                boolean returnEnclosesStatement =
                        !scalarTerminal
                                && !closureTerminal
                                && last instanceof CanonicalLookup;
                if (returnEnclosesStatement) {
                    builder.beginReturn();
                }
                beginStatement(builder, last);
                ProtosClosureExecutionPlanCell terminalClosurePlan =
                        closureTerminal
                                ? bytecodeClosurePlan(
                                        builder, (CanonicalClosure) last)
                                : null;
                if (scalarTerminal) {
                    emitScalarLocalRead(
                            builder, (CanonicalLookup) last, null);
                } else if (closureTerminal) {
                    builder.beginReturn();
                    emitClosureCapture(
                            builder, (CanonicalClosure) last, terminalClosurePlan);
                    builder.endReturn();
                } else if (last instanceof CanonicalLiteral literal
                        && primitiveNumberLiteralOrNull(literal) != null) {
                    // numeric literals return directly.
                    builder.beginReturn();
                    builder.emitLoadConstant(primitiveNumberLiteralOrNull(literal));
                    builder.endReturn();
                } else if (last instanceof CanonicalLookup terminalRead) {
                    // a terminal binding read returns directly.
                    emitLookup(builder, terminalRead, true);
                } else {
                    builder.beginReturn();
                    emitExpression(builder, last);
                    builder.endReturn();
                }
                endStatement(builder);
                if (returnEnclosesStatement) {
                    builder.endReturn();
                }
            } else if (admitsTerminalClosureCreate(last)) {
                // a final Closure creation returns the created Closure directly.
                if (expressions.size() > 1) {
                    emitDiscardedPrefix(
                            builder,
                            expressions.subList(0, expressions.size() - 1));
                }
                emitTerminalClosureCreate(builder, (CanonicalCreate) last);
            } else if (admitsTerminalClosureCall(last)) {
                // Call0 and Call1 return directly.
                /*
                 * I112 / PLAT062-T: the final Closure call is the root's
                 * terminal invocation. No result or scratch local, no
                 * prepared-call dispatch and no Yield is emitted here: the
                 * terminal operation owns selection, its fallback and the
                 * transport of a suspended invocation.
                 */
                if (expressions.size() > 1) {
                    emitDiscardedPrefix(
                            builder,
                            expressions.subList(0, expressions.size() - 1));
                }
                // Return is the root's last operation: no implicit fallthrough.
                builder.beginReturn();
                beginStatement(builder, last);
                emitCall(builder, last);
                endStatement(builder);
                builder.endReturn();
            } else if (admitsTerminalMethodSend(last)) {
                /*
                 * PERF038-I: the final immediate Send1 is the root's
                 * terminal invocation, exactly as a terminal Call1. No
                 * sequenceResult, prepared-call or miss local.
                 * GetMethod selects the raw callable when cached, and
                 * CallBinaryMethod transports the receiver separately.
                 * The terminal result remains a raw numeric carrier.
                 */
                if (expressions.size() > 1) {
                    emitDiscardedPrefix(
                            builder,
                            expressions.subList(0, expressions.size() - 1));
                }
                // Return is the root's last operation: no implicit fallthrough.
                builder.beginReturn();
                beginStatement(builder, last);
                emitCall(builder, last);
                endStatement(builder);
                builder.endReturn();
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
                        (last instanceof CanonicalSend terminalSend
                                        && primitiveResultSend(terminalSend))
                                || (last instanceof CanonicalCall terminalCall
                                        && primitiveResultCall(terminalCall));
                if (carrierTerminal) {
                    // I091: the final numeric result may return as a carrier.
                    // A synchronous numeric send with direct operands needs
                    // no continuation scratch.
                    emitPrimitiveNumericOperandToLocal(
                            builder,
                            last,
                            result,
                            sharedScratch == null
                                    ? null : sharedScratch.preparedCall(),
                            sharedScratch == null
                                    ? null : sharedScratch.childResult(),
                            sharedScratch == null
                                    ? null : sharedScratch.resumeValue());
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
                builder.emitLoadLocal(result);
                builder.endReturn();
            }
        }
        builder.endBlock();
        ProtosBytecodeRootNode result = builder.endRoot();
        result.markSemanticActivationRoot();
        if (activationDefinition != null) {
            // A rest parameter constructs a Core Array during parameter binding.
            // Declare its caller Prelude dependency before the first invocation.
            for (CanonicalParameter parameter : activationDefinition.parameters()) {
                if (parameter.rest()) {
                    result.requestCallerNeed(
                            ProtosBytecodeRootNode.NEEDS_CALLER_PRELUDE);
                    break;
                }
            }
            result.recordReturnHomeProvenUnobservable(
                    !compilationFacts.mayObserveReturnHome(activationDefinition));
        }
        builder.endSourceSection();
        /*
         * I107: the root publishes the EFFECTIVE_ROOT proof consumed from the
         * facts: the planner's proof of entry plus body, possibly refined above by
         * this lowerer's own transformations. Publication cannot authorize
         * anything already emitted: a root that nevertheless emitted a generic
         * prepared invocation invalidates the unconsumed proof first.
         */
        CanonicalCompilationFacts.EffectiveRoot effectiveRoot = rootPlan.effectiveRoot();
        boolean emittedGenericInvocation = currentRootEmittedContinuationPath;
        /*
         * I112 / D204: a terminal invocation has no Yield in this root, yet
         * it may delegate a real continuation through its transport. Its
         * terminality is a property of the emission point, never a proof of
         * non-suspension.
         */
        boolean emittedTerminalInvocation = currentRootEmittedTerminalInvocation;
        if (emittedGenericInvocation || emittedTerminalInvocation) {
            // A consumed result is kept (REJECTED_CONSUMED is not an error).
            compilationFacts.invalidateNonSuspension(
                    CanonicalNonSuspensionAnalysis.Subject.EFFECTIVE_ROOT,
                    effectiveRoot,
                    rootFacts.bindingAnalysis(),
                    scopeForThisRoot,
                    emittedGenericInvocation
                            ? "generic invocation emitted"
                            : "terminal invocation emitted");
        }
        /*
         * The physical evidence of this parse always wins: a root that
         * emitted a generic invocation is never published as non-suspending,
         * whatever an earlier parse consumed.
         */
        result.recordProvablyNonSuspendingBody(
                !emittedGenericInvocation
                        && !emittedTerminalInvocation
                        && compilationFacts.consumeNonSuspension(
                                        CanonicalNonSuspensionAnalysis.Subject.EFFECTIVE_ROOT,
                                        effectiveRoot,
                                        rootFacts.bindingAnalysis(),
                                        scopeForThisRoot)
                                .proven()
                        && (loweringRefinementAccepted
                                || rootPlan.plannedEffectiveRoot().proven()));
        recordLexicalFrameLayout(result, scopeForThisRoot);
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
        return result;
    }

    /*
     * the single physical layout of a Closure root's LOCAL/CELL
     * bindings, shared by tooling scope projection and the lazy frame-backed
     * guest Context view. It records each binding's physical storage (a
     * capture promoted by the inline storage plan is a LOCAL of this root,
     * never a second stored value). A module root records none: its bindings
     * are NAME bindings of its module Context.
     */
    /* The physical layout of an inline callback region's own LOCAL bindings. */
    private static ProtosBytecodeRootNode.LexicalFrameLayout inlineRegionLayout(
            java.util.Map<String, BytecodeLocal> locals) {
        int count = locals.size();
        String[] names = new String[count];
        int[] offsets = new int[count];
        boolean[] cells = new boolean[count];
        LocalAccessor[] accessors = new LocalAccessor[count];
        int index = 0;
        for (java.util.Map.Entry<String, BytecodeLocal> entry : locals.entrySet()) {
            names[index] = entry.getKey();
            offsets[index] = entry.getValue().getLocalOffset();
            accessors[index] = LocalAccessor.constantOf(entry.getValue());
            index++;
        }
        return new ProtosBytecodeRootNode.LexicalFrameLayout(names, offsets, cells, accessors);
    }

    private void recordLexicalFrameLayout(
            ProtosBytecodeRootNode root,
            CanonicalLexicalScope scope) {
        if (scope.kind() != CanonicalLexicalScope.Kind.CLOSURE) {
            return;
        }
        int count = currentRootFrameLocals.size();
        String[] names = new String[count];
        int[] offsets = new int[count];
        boolean[] cells = new boolean[count];
        LocalAccessor[] accessors = new LocalAccessor[count];
        int index = 0;
        for (java.util.Map.Entry<String, BytecodeLocal> entry : currentRootFrameLocals.entrySet()) {
            names[index] = entry.getKey();
            offsets[index] = entry.getValue().getLocalOffset();
            accessors[index] = LocalAccessor.constantOf(entry.getValue());
            cells[index] = scope.needsCell(entry.getKey())
                    && !currentRootPromotedCells.contains(scope.identity(entry.getKey()));
            index++;
        }
        root.recordLexicalFrameLayout(
                new ProtosBytecodeRootNode.LexicalFrameLayout(names, offsets, cells, accessors));
    }

    /**
     * I101-C1: one typed, bounded compile-time evaluator for arithmetic,
     * numeric comparison, semantic equality and identity.
     */
    private Object i101Constant(
            CanonicalExpression expression,
            java.util.Map<String, Object> known,
            CanonicalLexicalScope scope) {
        if (expression instanceof CanonicalLiteral literal) {
            return switch (literal.kind()) {
                case NUMBER -> numberLiteralDescriptor(literal);
                case TRUE -> Boolean.TRUE;
                case FALSE -> Boolean.FALSE;
                case NULL -> ProtosNullValue.INSTANCE;
                default -> null;
            };
        }

        if (expression instanceof CanonicalLookup lookup) {
            if (!known.containsKey(lookup.name())
                    || currentRootAnalysis == null
                    || scope == null
                    || currentRootAnalysis.resolutionOf(lookup)
                            .map(resolution -> !(resolution instanceof
                                    CanonicalBindingResolution.Resolved resolved)
                                    || resolved.identity().owner() != scope
                                    || !resolved.identity().name()
                                            .equals(lookup.name()))
                            .orElse(true)) {
                return null;
            }
            return known.get(lookup.name());
        }

        if (expression instanceof CanonicalIdentity identity) {
            Object left = i101Constant(identity.left(), known, scope);
            Object right = i101Constant(identity.right(), known, scope);
            return left == null || right == null ? null
                    : Boolean.valueOf(i101Identity(left, right));
        }

        if (expression instanceof CanonicalNotIdentity identity) {
            Object left = i101Constant(identity.left(), known, scope);
            Object right = i101Constant(identity.right(), known, scope);
            return left == null || right == null ? null
                    : Boolean.valueOf(!i101Identity(left, right));
        }

        if (expression instanceof CanonicalDerivedInequality inequality) {
            Object left = i101Constant(inequality.left(), known, scope);
            Object right = i101Constant(inequality.right(), known, scope);
            Object equal = i101Equality(left, right);
            return equal instanceof Boolean booleanValue
                    ? Boolean.valueOf(!booleanValue.booleanValue()) : null;
        }

        if (!(expression instanceof CanonicalSend send)
                || send.arguments().size() != 1) {
            return null;
        }

        if (java.util.Set.of("+", "-", "*", "/").contains(send.message())) {
            return i101IntegerArithmetic(send, known, scope);
        }

        Object left = i101Constant(send.receiver(), known, scope);
        Object right = i101Constant(send.arguments().get(0), known, scope);

        if ("==".equals(send.message())) {
            return i101Equality(left, right);
        }

        if (!i101Numeric(left) || !i101Numeric(right)) {
            return null;
        }

        var relation = i101Compare(left, right);
        Boolean value = switch (send.message()) {
            case "<" -> relation ==
                    ProtosStandardNumberOrderingProtocol.Comparison.LESS;
            case "<=" -> relation ==
                    ProtosStandardNumberOrderingProtocol.Comparison.LESS
                    || relation ==
                    ProtosStandardNumberOrderingProtocol.Comparison.EQUAL;
            case ">" -> relation ==
                    ProtosStandardNumberOrderingProtocol.Comparison.GREATER;
            case ">=" -> relation ==
                    ProtosStandardNumberOrderingProtocol.Comparison.GREATER
                    || relation ==
                    ProtosStandardNumberOrderingProtocol.Comparison.EQUAL;
            default -> {
                yield null;
            }
        };
        return value == null ? null : Boolean.valueOf(value);
    }

    /*
     * I101: the exact signed-64 result of a bounded Integer arithmetic
     * expression over Integer literals and proven lexical Integer constants,
     * or null when an operand is not such a constant, an operation
     * overflows, or a division is not exact. It only proves a constant
     * result; it never selects an executable operation.
     */
    private Long i101IntegerArithmetic(
            CanonicalExpression expression,
            java.util.Map<String, Object> known,
            CanonicalLexicalScope scope) {
        if (!(expression instanceof CanonicalSend send)) {
            return i101Constant(expression, known, scope) instanceof Long integer
                    ? integer
                    : null;
        }
        if (send.arguments().size() != 1) {
            return null;
        }
        Long left = i101IntegerArithmetic(send.receiver(), known, scope);
        Long right = left == null
                ? null
                : i101IntegerArithmetic(send.arguments().get(0), known, scope);
        if (right == null) {
            return null;
        }
        try {
            switch (send.message()) {
                case "+":
                    return Math.addExact(left, right);
                case "-":
                    return Math.subtractExact(left, right);
                case "*":
                    return Math.multiplyExact(left, right);
                case "/":
                    if (right == 0L
                            || (left == Long.MIN_VALUE && right == -1L)
                            || left % right != 0L) {
                        return null;
                    }
                    return left / right;
                default:
                    return null;
            }
        } catch (ArithmeticException overflow) {
            return null;
        }
    }

    private static boolean i101Numeric(Object value) {
        return value instanceof Long
                || value instanceof Double
                || value instanceof BigInteger;
    }

    private static BigInteger i101Integer(Object value) {
        return value instanceof Long integer
                ? BigInteger.valueOf(integer) : (BigInteger) value;
    }

    private static ProtosStandardNumberOrderingProtocol.Comparison
            i101Compare(Object left, Object right) {
        if (left instanceof Double floating) {
            if (Double.isNaN(floating)) {
                return ProtosStandardNumberOrderingProtocol
                        .Comparison.UNORDERED;
            }
            if (right instanceof Double other) {
                return ProtosCurrentNumericRelations.compareFloats(
                        floating, other);
            }
            if (Double.isInfinite(floating)) {
                return floating > 0.0
                        ? ProtosStandardNumberOrderingProtocol
                                .Comparison.GREATER
                        : ProtosStandardNumberOrderingProtocol
                                .Comparison.LESS;
            }
            return i101Sign(new java.math.BigDecimal(floating)
                    .compareTo(new java.math.BigDecimal(i101Integer(right))));
        }

        if (right instanceof Double) {
            return ProtosCurrentNumericRelations.reverse(
                    i101Compare(right, left));
        }
        return i101Sign(i101Integer(left).compareTo(i101Integer(right)));
    }

    private static ProtosStandardNumberOrderingProtocol.Comparison
            i101Sign(int sign) {
        if (sign < 0) {
            return ProtosStandardNumberOrderingProtocol.Comparison.LESS;
        }
        if (sign > 0) {
            return ProtosStandardNumberOrderingProtocol.Comparison.GREATER;
        }
        return ProtosStandardNumberOrderingProtocol.Comparison.EQUAL;
    }

    private static Object i101Equality(Object left, Object right) {
        if (left == null || right == null) {
            return null;
        }
        if (i101Numeric(left) && i101Numeric(right)) {
            return Boolean.valueOf(i101Compare(left, right)
                    == ProtosStandardNumberOrderingProtocol
                            .Comparison.EQUAL);
        }
        if (left instanceof Boolean
                && right instanceof Boolean) {
            return Boolean.valueOf(left == right);
        }
        if (left == ProtosNullValue.INSTANCE
                || right == ProtosNullValue.INSTANCE) {
            return Boolean.valueOf(left == right);
        }
        return null;
    }

    private static boolean i101Identity(Object left, Object right) {
        if (i101Numeric(left) && i101Numeric(right)) {
            if (left instanceof Double floating) {
                if (!(right instanceof Double other)) {
                    return false;
                }
                return Double.isNaN(floating) && Double.isNaN(other)
                        || Double.doubleToRawLongBits(floating)
                                == Double.doubleToRawLongBits(other);
            }
            return !(right instanceof Double)
                    && i101Integer(left).equals(i101Integer(right));
        }
        return left == right;
    }

    /**
     * PERF032-G6: a genuine source Closure root whose signature is empty.
     * Under the normative parameter-binding algorithm ({@code CALLABLES.md})
     * its only binding step is the final excess-argument check, which no
     * parameter, default, or body effect can precede; that check is therefore
     * taken by the call's entry-target selection ({@link
     * ProtosBytecodeRootNode#selectSourceEntryTarget}) instead of by
     * the root's own body. Closures with any parameter keep the in-body
     * algorithm unchanged.
     */
    /**
     * I107: publishes a proof this lowerer's own transformation established
     * for the effective root it is emitting (a refinement of the planner's
     * UNKNOWN). Valid only for this root's effective identity, analysis and
     * scope.
     */
    private CanonicalCompilationFacts.Publication publishLoweringRefinement(
            RootEmissionPlan rootPlan, String provenance) {
        CanonicalCompilationFacts.RootFacts rootFacts = rootPlan.rootFacts();
        return compilationFacts.publishNonSuspension(
                new CanonicalNonSuspensionAnalysis.Result(
                        CanonicalNonSuspensionAnalysis.Proof.PROVEN_NO_SUSPENSION,
                        CanonicalNonSuspensionAnalysis.Subject.EFFECTIVE_ROOT,
                        rootPlan.effectiveRoot(),
                        rootFacts.bindingAnalysis(),
                        rootFacts.scope(),
                        java.util.EnumSet.noneOf(
                                CanonicalNonSuspensionAnalysis.Precondition.class),
                        provenance));
    }

    private static boolean acceptsLoweringRefinement(
            CanonicalCompilationFacts.Publication publication) {
        return switch (publication) {
            case RECORDED, REFINED, UNCHANGED -> true;
            case REJECTED_INCOMPATIBLE, REJECTED_CONSUMED,
                    REJECTED_INVALIDATED, INVALIDATED -> false;
        };
    }


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

        java.util.List<CanonicalExpression> expressions = body.expressions();
        for (int index = 0; index < expressions.size(); index++) {
            CanonicalExpression expression = expressions.get(index);
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

            /*
             * PERF043-A: a terminal send cannot invalidate an earlier local
             * read. Admit only a single, effect-free operand evaluation on
             * each side. The selected method remains entirely dynamic.
             */
            if (index == expressions.size() - 1
                    && expression instanceof CanonicalSend send
                    && java.util.Set.of("+", "-", "*", "/")
                            .contains(send.message())
                    && send.arguments().size() == 1
                    && scalarTerminalOperand(
                            send.receiver(), analysis, scope, established)
                    && scalarTerminalOperand(
                            send.arguments().get(0), analysis, scope, established)) {
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
    private static boolean scalarTerminalOperand(
            CanonicalExpression expression,
            CanonicalBindingAnalysis analysis,
            CanonicalLexicalScope scope,
            java.util.Set<String> established) {
        return expression instanceof CanonicalLiteral
                || (expression instanceof CanonicalLookup lookup
                        && readsEstablishedScalar(
                                lookup, analysis, scope, established));
    }

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


    /** PERF034-C / an admitted scalar read stored into {@code result}. */
    private void emitScalarLocalReadToResult(
            ProtosBytecodeRootNodeGen.Builder builder,
            CanonicalLookup lookup,
            BytecodeLocal result) {
        emitScalarLocalRead(builder, lookup, result);
    }

    /**
     * PERF034-C/E: whether {@code lookup} reads an admitted scalar local of
     * the root being lowered (outside any inline activation region).
     */
    private boolean isScalarLocalRead(CanonicalLookup lookup) {
        return currentNormalCallbackScalarLocals.isEmpty()
                && currentActivationLocal == null
                && currentRootScalarLocals.contains(lookup.name());
    }

    /**
     * load.local: a statically resolved current-root scalar
     * reads its Bytecode DSL local directly, with no compact/published switch.
     *
     * <p>an admitted scalar root contains no {@code context}
     * intrinsic and no Closure literal, so its Context is never observed and
     * nothing can remove, recreate or shadow the binding: the direct read is
     * exactly the semantic read.
     */
    private void emitScalarLocalRead(
            ProtosBytecodeRootNodeGen.Builder builder,
            CanonicalLookup lookup,
            BytecodeLocal result) {
        BytecodeLocal local = currentRootFrameLocals.get(lookup.name());
        if (local == null) {
            throw new AssertionError(
                    "admitted scalar local is missing: " + lookup.name());
        }
        beginScalarReadSink(builder, result);
        builder.emitLoadLocal(local);
        endScalarReadSink(builder, result);
    }

    private static void beginScalarReadSink(
            ProtosBytecodeRootNodeGen.Builder builder,
            BytecodeLocal result) {
        if (result == null) {
            builder.beginReturn();
        } else {
            builder.beginStoreLocal(result);
        }
    }

    private static void endScalarReadSink(
            ProtosBytecodeRootNodeGen.Builder builder,
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
            ProtosBytecodeRootNodeGen.Builder builder,
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
            ProtosBytecodeRootNodeGen.Builder builder) {
        builder.endBlock();
        builder.endTag(
                StandardTags.StatementTag.class,
                StandardTags.ExpressionTag.class);
        builder.endSourceSection();
    }

    private void emitStatementsToLocal(
            ProtosBytecodeRootNodeGen.Builder builder,
            CanonicalSequence sequence,
            BytecodeLocal result) {
        emitStatementsToLocal(builder, sequence.expressions(), result);
    }

    private void emitStatementsToLocal(
            ProtosBytecodeRootNodeGen.Builder builder,
            java.util.List<CanonicalExpression> expressions,
            BytecodeLocal result) {
        boolean hasComposedInvocation =
                expressions.stream()
                        .anyMatch(this::requiresContinuationScratch);
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

        // only the last expression supplies the consumer's value; the
        // earlier ones are statements and never write the result local.
        DiscardSink sink = new DiscardSink();
        for (int index = 0; index < expressions.size(); index++) {
            CanonicalExpression expression = expressions.get(index);
            beginStatement(builder, expression);

            if (index < expressions.size() - 1) {
                emitExpressionStatement(
                        builder, expression, sink, preparedCall, childResult, resumeValue);
            } else if (requiresComposedInvocation(expression)) {
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
        if (expression instanceof CanonicalNamedArgument named) {
            validateSupportedDefaultExpression(named.expression());
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

    private boolean hasComposedDefault(CanonicalClosure definition) {
        return definition.parameters().stream()
                .flatMap(parameter -> parameter.defaultValue().stream())
                .anyMatch(this::requiresContinuationScratch);
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
            ProtosBytecodeRootNodeGen.Builder builder) {
        if (currentActivationLocal == null) {
            builder.emitCurrentActivation(false);
        } else {
            builder.emitLoadLocal(currentActivationLocal);
        }
    }

    /*
     * PERF025 slice 3. Inside a frame-native inline callback region, the
     * consumers below take the carrier-operand operation, which runs its
     * successful path without the callback activation and otherwise
     * materializes it exactly as emitCurrentActivation would; everywhere else
     * they take the unchanged activation operation. Each begin/end pair is
     * emitted within one region, so the selection is the same for both.
     */

    /** The caller operand of a send/Closure-call preparation opened above. */
    private void emitInvocationCallerOperand(
            ProtosBytecodeRootNodeGen.Builder builder) {
        {
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
            ProtosBytecodeRootNodeGen.Builder builder) {
        if (currentActivationLocal == null) {
            builder.emitCurrentActivation(true);
        } else {
            emitInvocationCallerOperand(builder);
        }
    }

    /**
     * binds every parameter of {@code definition}, left to right,
     * directly into its classified storage. Each supplied value is read from
     * the B5 call ABI, whose supplied positions keep their offsets after
     * publication, so no activation is materialized to bind a parameter.
     *
     * <p>D203: parameter {@code k} reads supplied position {@code k}; an
     * unsupplied position evaluates its default, if any, after every earlier
     * parameter is bound, or binds canonical null. Excess supplied arguments
     * are ignored without rest, so no upper-bound check is emitted. A rest
     * parameter binds the frozen Array of the remaining supplied values.
     *
     * <p>A default may observe and mutate this invocation's Context before
     * its own parameter or a later parameter is established. From the first
     * default onward, binding therefore uses ordinary checked creation in
     * the same LOCAL or CELL storage. Parameter lists without defaults keep
     * their direct stores and grouped argument copy.
     */
    private void emitClosureParameterBindings(
            ProtosBytecodeRootNodeGen.Builder builder,
            CanonicalClosure definition,
            BytecodeLocal defaultValue,
            BytecodeLocal defaultPreparedCall,
            BytecodeLocal defaultChildResult,
            BytecodeLocal defaultResumeValue) {
        /*
         * experimental grouped-copy admission: only an entire
         * positional parameter list owned by this Closure root, with
         * physical LOCAL storage and no default or rest binding, can use
         * the contiguous local range.
         */
        boolean groupedCopy =
                !definition.parameters().isEmpty()
                        && currentActivationLocal == null
                        && currentRootAnalysis != null;

        java.util.Set<String> parameterNames = new java.util.HashSet<>();

        if (groupedCopy) {
            for (CanonicalParameter parameter : definition.parameters()) {
                if (parameter.rest()
                        || parameter.defaultValue().isPresent()
                        || !parameterNames.add(parameter.name())) {
                    groupedCopy = false;
                    break;
                }

                CanonicalBindingIdentity identity =
                        currentRootAnalysis.identityOf(parameter).orElse(null);

                if (identity == null
                        || identity.owner() != currentRootTopScope
                        || physicalStorageOf(identity)
                                != CanonicalLexicalScope.Storage.LOCAL
                        || !currentRootFrameLocals.containsKey(identity.name())) {
                    groupedCopy = false;
                    break;
                }
            }
        }

        if (groupedCopy) {
            BytecodeLocal[] parameterLocals =
                    new BytecodeLocal[definition.parameters().size()];

            for (int index = 0; index < parameterLocals.length; index++) {
                parameterLocals[index] =
                        parameterLocal(definition.parameters().get(index));
            }

            builder.emitCopySuppliedArguments(parameterLocals);
            return;
        }

        int positionalIndex = 0;
        boolean checkCreation = false;
        for (CanonicalParameter parameter : definition.parameters()) {
            checkCreation |= parameter.defaultValue().isPresent();
            if (parameter.rest()) {
                beginStoreParameter(builder, parameter, checkCreation);
                builder.emitLoadRestArguments(positionalIndex);
                endStoreParameter(builder, parameter, checkCreation);
                continue;
            }

            if (parameter.defaultValue().isPresent()) {
                CanonicalExpression defaultExpression =
                        parameter.defaultValue().orElseThrow();

                builder.beginIfThenElse();
                builder.emitHasSuppliedArgument(positionalIndex);

                builder.beginBlock();
                emitBindSuppliedClosureParameter(
                        builder,
                        parameter,
                        positionalIndex,
                        checkCreation);
                builder.endBlock();

                builder.beginBlock();
                if (!requiresContinuationScratch(defaultExpression)
                        || i115DirectUnaryCall(defaultExpression)
                        || (defaultExpression instanceof CanonicalSend numericDefault
                                && isDirectNumericOperand(numericDefault))) {
                    beginStoreParameter(builder, parameter, checkCreation);
                    emitExpressionValue(
                            builder, defaultExpression,
                            defaultPreparedCall, defaultChildResult,
                            defaultResumeValue, false);
                    endStoreParameter(builder, parameter, checkCreation);
                } else if (defaultExpression instanceof CanonicalCall defaultCall) {
                    emitComposedDefaultCall(
                            builder,
                            defaultCall,
                            defaultValue,
                            defaultPreparedCall,
                            defaultChildResult,
                            defaultResumeValue);
                    emitBindDefaultLocal(builder, parameter, defaultValue, checkCreation);
                } else if (defaultExpression instanceof CanonicalSend defaultSend) {
                    emitComposedDefaultSend(
                            builder,
                            defaultSend,
                            defaultValue,
                            defaultPreparedCall,
                            defaultChildResult,
                            defaultResumeValue);
                    emitBindDefaultLocal(builder, parameter, defaultValue, checkCreation);
                } else if (defaultExpression instanceof CanonicalReturn
                        || requiresComposedInvocation(defaultExpression)) {
                    emitDefaultExpressionToLocal(
                            builder,
                            defaultExpression,
                            defaultValue,
                            defaultPreparedCall,
                            defaultChildResult,
                            defaultResumeValue);
                    emitBindDefaultLocal(builder, parameter, defaultValue, checkCreation);
                } else {
                    beginStoreParameter(builder, parameter, checkCreation);
                    builder.beginSourceSection(
                            defaultExpression.span().startOffset(),
                            defaultExpression.span().length());
                    emitExpression(builder, defaultExpression);
                    builder.endSourceSection();
                    endStoreParameter(builder, parameter, checkCreation);
                }
                builder.endBlock();

                builder.endIfThenElse();
            } else {
                emitBindSuppliedClosureParameter(
                        builder,
                        parameter,
                        positionalIndex,
                        checkCreation);
            }

            positionalIndex++;
        }
    }

    private void emitBindDefaultLocal(
            ProtosBytecodeRootNodeGen.Builder builder,
            CanonicalParameter parameter,
            BytecodeLocal defaultValue,
            boolean checkCreation) {
        if (defaultValue == null) {
            throw new AssertionError("composed default value local was not allocated");
        }
        beginStoreParameter(builder, parameter, checkCreation);
        builder.emitLoadLocal(defaultValue);
        endStoreParameter(builder, parameter, checkCreation);
    }

    /** Establishes a supplied parameter, or canonical null for an absent position. */
    private void emitBindSuppliedClosureParameter(
            ProtosBytecodeRootNodeGen.Builder builder,
            CanonicalParameter parameter,
            int positionalIndex,
            boolean checkCreation) {
        beginStoreParameter(builder, parameter, checkCreation);
        builder.emitLoadSuppliedArgument(positionalIndex);
        endStoreParameter(builder, parameter, checkCreation);
    }

    /** Opens parameter establishment in its classified LOCAL or CELL storage. */
    private void beginStoreParameter(
            ProtosBytecodeRootNodeGen.Builder builder,
            CanonicalParameter parameter,
            boolean checkCreation) {
        BytecodeLocal cell = parameterCellLocalOrNull(parameter);
        if (checkCreation) {
            builder.beginDiscardValue();
            if (cell != null) {
                builder.beginCreateLexicalCell(parameter.name());
                builder.emitLoadLocal(cell);
            } else {
                builder.beginCreateLexicalLocal(parameter.name(), parameterLocal(parameter));
            }
            return;
        }
        if (cell != null) {
            builder.beginStoreCell();
            builder.emitLoadLocal(cell);
            return;
        }
        builder.beginStoreLocal(parameterLocal(parameter));
    }

    private void endStoreParameter(
            ProtosBytecodeRootNodeGen.Builder builder,
            CanonicalParameter parameter,
            boolean checkCreation) {
        if (checkCreation) {
            if (parameterCellLocalOrNull(parameter) != null) {
                builder.endCreateLexicalCell();
            } else {
                builder.endCreateLexicalLocal();
            }
            builder.endDiscardValue();
            return;
        }
        if (parameterCellLocalOrNull(parameter) != null) {
            builder.endStoreCell();
        } else {
            builder.endStoreLocal();
        }
    }

    private BytecodeLocal parameterCellLocalOrNull(CanonicalParameter parameter) {
        return physicalStorageOf(parameterIdentity(parameter))
                        == CanonicalLexicalScope.Storage.CELL
                ? currentRootCellLocals.get(parameterIdentity(parameter))
                : null;
    }

    private BytecodeLocal parameterLocal(CanonicalParameter parameter) {
        BytecodeLocal local = currentRootFrameLocals.get(parameterIdentity(parameter).name());
        if (local == null) {
            throw new AssertionError("I115 parameter has no local: " + parameter.name());
        }
        return local;
    }

    private CanonicalBindingIdentity parameterIdentity(CanonicalParameter parameter) {
        CanonicalBindingIdentity identity =
                currentRootAnalysis == null
                        ? null
                        : currentRootAnalysis.identityOf(parameter).orElse(null);
        if (identity == null
                || identity.owner() != currentRootTopScope
                || currentActivationLocal != null) {
            throw new AssertionError("I115 unclassified parameter: " + parameter.name());
        }
        return identity;
    }

    /*
     * the root-scoped locals of the cells visible to a CLOSURE root are
     * established once at entry: a fresh cell for each own CELL binding
     * (uninitialized until its binding point) and the Closure's captured
     * cell for each free cell, at its constant captured-cell index.
     */
    private void emitCellPrologue(
            ProtosBytecodeRootNodeGen.Builder builder,
            CanonicalLexicalScope scope) {
        if (scope.kind() != CanonicalLexicalScope.Kind.CLOSURE) {
            return;
        }
        for (String name : scope.cellNamesInDeclarationOrder()) {
            if (currentRootPromotedCells.contains(scope.identity(name))) {
                // an inline-only capture is a plain local; no cell.
                continue;
            }
            builder.beginStoreLocal(currentRootCellLocals.get(scope.identity(name)));
            builder.emitCreateCell();
            builder.endStoreLocal();
        }
        java.util.List<CanonicalBindingIdentity> free = scope.freeCells();
        for (int index = 0; index < free.size(); index++) {
            builder.beginStoreLocal(currentRootCellLocals.get(free.get(index)));
            builder.emitLoadCapturedCell(index);
            builder.endStoreLocal();
        }
    }

    /**
     * the static binding identity of a classified site, or {@code null}
     * for a {@code Dynamic} (or unanalyzed) site, which is a name operation.
     */
    private static CanonicalBindingIdentity staticIdentityOrNull(
            CanonicalBindingResolution resolution) {
        return resolution == null ? null : resolution.staticIdentityOrNull();
    }

    /**
     * the cell local of {@code identity} when its storage is CELL, else
     * {@code null}. A cell binding is always reachable from the root being
     * lowered (its owner, or a root carrying it as a free cell); anything
     * else is a lowering defect, never a reason to choose another storage.
     */
    private BytecodeLocal cellLocalOrNull(CanonicalBindingIdentity identity) {
        if (physicalStorageOf(identity) != CanonicalLexicalScope.Storage.CELL) {
            return null;
        }
        BytecodeLocal local = currentRootCellLocals.get(identity);
        if (local == null) {
            throw new AssertionError("I115 cell is not reachable from this root: " + identity.name());
        }
        return local;
    }

    /**
     * the Bytecode DSL local of a LOCAL binding owned by the CLOSURE
     * root being lowered, else {@code null} (NAME or MEMBER storage). A LOCAL
     * binding is only ever accessed from its own root; another owner is a
     * lowering defect.
     */
    private BytecodeLocal ownLocalOrNull(CanonicalBindingIdentity identity) {
        if (physicalStorageOf(identity) != CanonicalLexicalScope.Storage.LOCAL) {
            return null;
        }
        BytecodeLocal local =
                identity.owner() == currentRootTopScope
                        ? currentRootFrameLocals.get(identity.name())
                        : null;
        if (local == null) {
            throw new AssertionError("I115 local is not owned by this root: " + identity.name());
        }
        return local;
    }

    /**
     * the physical storage of {@code identity} in the root being
     * lowered: its lexical storage, except that a CELL identity promoted by
     * {@link InlineStoragePlan} is a LOCAL of its owner root. A promoted
     * identity is only ever accessed from that root's inline region, so a
     * not-yet-established access executes in place, before its binding
     * point, exactly like a LOCAL one.
     */
    private CanonicalLexicalScope.Storage physicalStorageOf(CanonicalBindingIdentity identity) {
        if (currentRootPromotedCells.contains(identity)) {
            return CanonicalLexicalScope.Storage.LOCAL;
        }
        return CanonicalBindingAnalysis.storageOf(identity);
    }

    /**
     * Emits the target-less creation of {@code create} from the evaluated
     * {@code value} for a module (NAME) or object-body (MEMBER) binding,
     * through the {@code CreateCurrentLocalSlot} name operation. LOCAL and
     * CELL creations never reach it: they are lexical binding writes. The
     * created value is delivered to {@code result}, or discarded when {@code
     * result} is {@code null}.
     */
    private void emitCreateCurrentBinding(
            ProtosBytecodeRootNodeGen.Builder builder,
            CanonicalCreate create,
            BytecodeLocal value,
            BytecodeLocal result) {
        beginMutationResult(builder, result);
        builder.beginCreateCurrentLocalSlot();
        emitCurrentActivation(builder);
        builder.emitLoadConstant(create.name());
        builder.emitLoadLocal(value);
        builder.endCreateCurrentLocalSlot();
        endMutationResult(builder, result);
    }

    /**
     * Emits a multiple creation from {@code source}. When the names are
     * LOCAL/CELL bindings of the root being lowered, the complete fixed
     * prefix is observed once, before any binding is created, and each value
     * is then stored, in source order, directly into its own classified
     * storage; a failing observation creates nothing. When {@code
     * presenceChecked} (), each creation applies the ordinary
     * {@code :} rules (a PRESENT binding or a CLOSED/FROZEN Context signals
     * its Error) left to right, without rollback. The whole expression
     * yields {@code source} into {@code result}, unless that is {@code null}
     * (no consumer). NAME and MEMBER bindings keep the {@code
     * CreateCurrentLocalSlot} name operation over all names.
     */
    private void emitMultipleCreateCurrentBindings(
            ProtosBytecodeRootNodeGen.Builder builder,
            java.util.List<String> names,
            java.util.List<CanonicalBindingIdentity> identities,
            boolean presenceChecked,
            BytecodeLocal source,
            BytecodeLocal result) {
        java.util.List<BindingStorage> storages = new java.util.ArrayList<>();
        if (identities != null) {
            for (CanonicalBindingIdentity identity : identities) {
                storages.add(bindingStorageOrNull(identity));
            }
        }
        boolean direct = !storages.isEmpty() && !storages.contains(null);
        if (!direct) {
            beginMutationResult(builder, result);
            builder.beginCreateCurrentLocalSlot();
            emitCurrentActivation(builder);
            builder.emitLoadConstant(multipleCreateNamesConstant(names));
            builder.emitLoadLocal(source);
            builder.endCreateCurrentLocalSlot();
            endMutationResult(builder, result);
            return;
        }
        BytecodeLocal observed = builder.createLocal("multipleCreateObserved", null);
        builder.beginStoreLocal(observed);
        builder.beginObserveMultipleCreatePrefix();
        builder.emitLoadConstant(identities.size());
        builder.emitLoadLocal(source);
        builder.endObserveMultipleCreatePrefix();
        builder.endStoreLocal();
        for (int index = 0; index < storages.size(); index++) {
            int elementIndex = index;
            Runnable element = () -> {
                builder.beginObservedMultipleCreateValue();
                builder.emitLoadLocal(observed);
                builder.emitLoadConstant(elementIndex);
                builder.endObservedMultipleCreateValue();
            };
            if (presenceChecked) {
                // ordinary ':' rules per name, left to right, no rollback.
                builder.beginDiscardValue();
                emitPresenceCheckedCreateOperation(
                        builder, names.get(index), storages.get(index), element);
                builder.endDiscardValue();
            } else {
                emitBindingWrite(
                        builder,
                        storages.get(index),
                        BindingValue.synchronous(element),
                        null);
            }
        }
        if (result != null) {
            builder.beginStoreLocal(result);
            builder.emitLoadLocal(source);
            builder.endStoreLocal();
        }
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
            ProtosBytecodeRootNodeGen.Builder builder,
            java.util.List<CanonicalExpression> expressions) {
        boolean needed =
                expressions.stream()
                        .anyMatch(this::requiresContinuationScratch);
        if (!needed) {
            return null;
        }
        return new ComposedSequenceScratch(
                builder.createLocal("preparedClosureCall", null),
                builder.createLocal("childResult", null),
                builder.createLocal("resumeValue", null));
    }

    private void emitDiscardedPrefix(
            ProtosBytecodeRootNodeGen.Builder builder,
            java.util.List<CanonicalExpression> expressions) {
        emitDiscardedPrefix(
                builder,
                expressions,
                createComposedSequenceScratch(builder, expressions));
    }

    private void emitDiscardedPrefix(
            ProtosBytecodeRootNodeGen.Builder builder,
            java.util.List<CanonicalExpression> expressions,
            ComposedSequenceScratch scratch) {
        BytecodeLocal preparedCall =
                scratch == null ? null : scratch.preparedCall();
        BytecodeLocal childResult =
                scratch == null ? null : scratch.childResult();
        BytecodeLocal resumeValue =
                scratch == null ? null : scratch.resumeValue();
        DiscardSink sink = null;

        for (CanonicalExpression expression : expressions) {
            if (isI115EliminableControl(expression)) {
                continue;
            }

            // A discarded module binding whose RHS is a Closure has
            // one source-visible effect: create the named binding.
            // The fused node preserves normal ':' admission and error
            // semantics without staging the created Closure or
            // materializing the activation as a bytecode operand.
            if (expression instanceof CanonicalCreate create
                    && create.target().isEmpty()
                    && create.value() instanceof CanonicalClosure closure
                    && currentRootTopScope != null
                    && currentRootTopScope.kind()
                            == CanonicalLexicalScope.Kind.ROOT
                    && currentActivationLocal == null
                    && !currentInlineClosureCallBody
                    && currentNormalCallbackScalarLocals.isEmpty()) {
                beginStatement(builder, create);
                ProtosClosureExecutionPlanCell plan =
                        bytecodeClosurePlan(builder, closure);
                builder.beginCreateDiscardedModuleBinding(create.name());
                emitClosureCapture(builder, closure, plan);
                builder.endCreateDiscardedModuleBinding();
                endStatement(builder);
                continue;
            }

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
                emitClosureCapture(builder, closure, plan);
                builder.endDiscardValue();
                endStatement(builder);
            } else {
                /*
                 * every other expression is a statement: direct
                 * numeric operations and simple values are discarded on
                 * the stack, and a composed invocation writes at most the
                 * lazily created discard sink, never a staged result.
                 */
                beginStatement(builder, expression);
                if (sink == null && classifyStaticControlSelection(expression) == null) {
                    sink = new DiscardSink();
                }
                emitExpressionStatement(
                        builder, expression, sink, preparedCall, childResult, resumeValue);
                endStatement(builder);
            }
        }
    }

    private void emitDiscardedScalarLocalRead(
            ProtosBytecodeRootNodeGen.Builder builder,
            CanonicalLookup lookup) {
        BytecodeLocal local =
                currentRootFrameLocals.get(lookup.name());
        if (local == null) {
            throw new AssertionError(
                    "admitted scalar local is missing: " + lookup.name());
        }
        builder.beginDiscardValue();
        builder.emitLoadLocal(local);
        builder.endDiscardValue();
    }

    /**
     * whether {@code last}, the final expression of the genuine root
     * being lowered, is a target-less creation whose value is a Closure
     * literal. Its value cannot suspend, so the root returns the created
     * Closure directly instead of staging it in a sequence result.
     */
    private boolean admitsTerminalClosureCreate(CanonicalExpression last) {
        return last instanceof CanonicalCreate create
                && create.target().isEmpty()
                && create.value() instanceof CanonicalClosure
                && currentActivationLocal == null
                && currentNormalCallbackScalarLocals.isEmpty()
                && !currentRootScalarLocals.contains(create.name())
                && !requiresPresenceCheckedCreation(create);
    }

    /**
     * one admitted {@link #admitsTerminalClosureCreate} statement. The
     * nested root and its plan cell are registered before the Return opens;
     * the Closure is created at the statement and stored once into the
     * binding's classified storage, which then yields the returned value:
     * {@code StoreCell}/{@code StoreLocal} for a CELL/LOCAL binding, and the
     * {@code CreateCurrentLocalSlot} name operation for a module (NAME) or
     * dynamic binding, exactly as {@link #emitCreateCurrentBinding}.
     */
    private void emitTerminalClosureCreate(
            ProtosBytecodeRootNodeGen.Builder builder,
            CanonicalCreate create) {
        CanonicalClosure closure = (CanonicalClosure) create.value();
        beginStatement(builder, create);
        ProtosClosureExecutionPlanCell plan = bytecodeClosurePlan(builder, closure);
        CanonicalBindingIdentity identity =
                currentRootAnalysis == null
                        ? null
                        : currentRootAnalysis.identityOf(create).orElse(null);
        BindingStorage storage = identity == null ? null : bindingStorageOrNull(identity);
        BytecodeLocal cell = storage == null ? null : storage.cell();
        BytecodeLocal local = storage == null ? null : storage.local();
        builder.beginReturn();
        if (cell != null) {
            builder.beginBlock();
            builder.beginStoreCell();
            builder.emitLoadLocal(cell);
            emitClosureCapture(builder, closure, plan);
            builder.endStoreCell();
            builder.beginLoadCell();
            builder.emitLoadLocal(cell);
            builder.endLoadCell();
            builder.endBlock();
        } else if (local != null) {
            builder.beginBlock();
            builder.beginStoreLocal(local);
            emitClosureCapture(builder, closure, plan);
            builder.endStoreLocal();
            builder.emitLoadLocal(local);
            builder.endBlock();
        } else {
            builder.beginCreateCurrentLocalSlot();
            emitCurrentActivation(builder);
            builder.emitLoadConstant(create.name());
            emitClosureCapture(builder, closure, plan);
            builder.endCreateCurrentLocalSlot();
        }
        builder.endReturn();
        endStatement(builder);
    }

    private void emitClosureCapture(
            ProtosBytecodeRootNodeGen.Builder builder,
            CanonicalClosure closure,
            ProtosClosureExecutionPlanCell plan) {
        boolean bindsFrame = requiresLexicalViewBeforeCapture(closure);
        if (bindsFrame) {
            /*
             * the first point where this root's frame must
             * survive. The block's value is the created Closure.
             */
            builder.beginBlock();
            builder.emitEnsureCurrentClosureLexicalView();
        }
        java.util.List<CanonicalBindingIdentity> cells = beginClosureCapture(builder, closure);
        builder.emitLoadConstant(closure);
        builder.emitLoadConstant(plan);
        endClosureCapture(builder, cells);
        if (bindsFrame) {
            builder.endBlock();
        }
    }

    /*
     * a Closure root whose bindings a nested site may need to
     * select by name binds its frame to the frame-backed view before creating
     * a Closure that captures it, so the captured lexical scope reaches the
     * live frame bindings even after this activation returns.
     */
    private boolean requiresLexicalViewBeforeCapture(CanonicalClosure closure) {
        return currentRootTopScope != null
                && currentRootTopScope.kind() == CanonicalLexicalScope.Kind.CLOSURE
                && currentRootTopScope.requiresLexicalView()
                && CanonicalCompilationFacts.capturesCreatorContext(closure, currentRootAnalysis);
    }

    /*
     * an inline object construction whose body may select a
     * binding of this Closure root's frame by name binds the frame before
     * creating the construction activation. Only bodies the analysis recorded
     * pay for it; every other object literal keeps the compact frame.
     */
    private boolean requiresLexicalViewBeforeConstruction(CanonicalObject object) {
        return currentRootTopScope != null
                && currentRootTopScope.kind() == CanonicalLexicalScope.Kind.CLOSURE
                && currentRootTopScope.requiresLexicalView()
                && currentRootAnalysis != null
                && currentRootAnalysis.scopeOf(object)
                        .map(CanonicalLexicalScope::requiresEnclosingFrameView)
                        .orElse(false);
    }

    /**
     * I112 / PLAT062-T: whether {@code last}, the final expression of the
     * genuine root being lowered, is a terminal Closure invocation: a
     * {@link CanonicalCall} after which the root only returns its value,
     * whose receiver and positional arguments are direct values that can be
     * evaluated once, left to right, as operands of the terminal operation.
     * Terminality is a property of this emission point, never a proof that
     * the invocation cannot suspend.
     *
     * <p>Not admitted, and therefore lowered unchanged: spread arguments,
     * composed or control operands, and every inline callback or activation
     * region, whose enclosing work must still resume physically.
     *
     * <p>PERF042 / PLAT066-C: PLAT057-B permitted proven inlining during
     * lowering, and an I096 candidate other than a lexical one-argument
     * binding call (an immediate Closure literal, a C0 or C2/N binding call)
     * keeps that inline lowering. For an ordinary terminal C1 call whose
     * receiver is the lexical binding of a known Closure, the approved
     * direction is to keep the callee's physical CallTarget: the existence of
     * an I096 candidate does not justify expanding that call in the caller,
     * so it is admitted here and Graal decides inlining between CallTargets.
     * This compact admission is not a proof that the call cannot suspend, and
     * the terminal operation keeps the dynamic D013 selection and its
     * fallbacks. The exception covers only this scope; it does not presume a
     * general retirement of PLAT057-B.
     */
    private boolean admitsTerminalClosureCall(CanonicalExpression last) {
        if (!(last instanceof CanonicalCall call)
                || currentActivationLocal != null
                || currentInlineClosureCallBody
                || !currentNormalCallbackScalarLocals.isEmpty()
                || hasVectorArgument(call.arguments())
                || !isTerminalCallOperand(call.receiver())) {
            return false;
        }
        boolean lexicalC1BindingCandidate =
                call.arguments().size() == 1
                        && call.receiver() instanceof CanonicalLookup
                        && currentRootBindingCallCandidates.containsKey(call);
        // Decided before the prefix materializes its literals: every other
        // I096 candidate keeps its inline lowering.
        if (!lexicalC1BindingCandidate
                && currentRootBindingCallCandidates.containsKey(call)) {
            return false;
        }
        for (CanonicalExpression argument : call.arguments()) {
            if (!isTerminalCallOperand(argument)) {
                return false;
            }
        }
        return true;
    }

    /**
     * PERF038-I: an immediate method Send1 that is the final expression of a
     * genuine source root, with both operands direct terminal values (so
     * nothing composed remains after the invocation). Primitive numeric
     * operator sends keep their established Integer lowering; every other
     * selector is admitted regardless of its name.
     */
    private boolean admitsTerminalMethodSend(CanonicalExpression last) {
        if (!(last instanceof CanonicalSend send)
                || currentActivationLocal != null
                || currentInlineClosureCallBody
                || !currentNormalCallbackScalarLocals.isEmpty()
                || send.arguments().size() != 1
                || hasVectorArgument(send.arguments())
                || primitiveNumericSendCandidate(send)
                || indexedProtocolSend(send)
                || ControlSelector.of(send.message(), send.arguments()) != null
                || nativeBooleanControl(
                        send.message(), send.arguments()) != null) {
            return false;
        }
        return isTerminalCallOperand(send.receiver())
                && isTerminalCallOperand(send.arguments().get(0));
    }


    /**
     * A terminal call or send whose operands are direct terminal
     * values: an arity-selected terminal call through the shared
     * dispatcher. Immediate SEND1 evaluates the receiver once and
     * transports it separately through CallBinaryMethod. A suspended outcome
     * retains its caller location in the terminal operation's transport.
     */
    private void emitCall(
            ProtosBytecodeRootNodeGen.Builder builder,
            CanonicalExpression expression) {

        currentRootEmittedTerminalInvocation = true;
        noteDynamicInvocation();

        SourceSpan span = expression instanceof CanonicalCall call
                ? call.span()
                : ((CanonicalSend) expression).span();

        builder.beginSourceSection(span.startOffset(), span.length());

        if (expression instanceof CanonicalSend send) {
            emitI115DirectUnaryOperation(
                    builder, InvocationShape.SEND,
                    send.receiver(), send.message(), send.arguments().get(0),
                    true, true);
            builder.endSourceSection();
            return;
        }

        builder.beginTag(StandardTags.CallTag.class);

        // RootNodeCompiler.emitCall: emit unary operands directly.
        // No intermediate BytecodeLocal stores or reloads are required.
        if (expression instanceof CanonicalCall call
                && call.arguments().size() == 1) {
            builder.beginCallUnaryMethod(true);
            emitTerminalCallOperand(builder, call.receiver());
            emitTerminalCallOneArgument(builder, call.arguments().get(0));
            builder.endCallUnaryMethod();
            builder.endTag(StandardTags.CallTag.class);
            builder.endSourceSection();
            return;
        }

        // The terminal Call0 consumes its receiver directly.
        // The terminal operation owns suspension transport.
        if (expression instanceof CanonicalCall call
                && call.arguments().isEmpty()) {
            builder.beginCallNilaryMethod(true);
            emitTerminalCallOperand(builder, call.receiver());
            builder.endCallNilaryMethod();
            builder.endTag(StandardTags.CallTag.class);
            builder.endSourceSection();
            return;
        }

        // Fixed-arity terminal Call2: receiver, first argument and second
        // argument are direct operands evaluated once in source order.
        // The terminal operation owns dynamic selection and suspension.
        if (expression instanceof CanonicalCall call
                && call.arguments().size() == 2) {
            builder.beginCallBinaryMethod(true, false);
            emitTerminalCallOperand(builder, call.receiver());
            emitTerminalCallOneArgument(builder, call.arguments().get(0));
            emitTerminalCallOneArgument(builder, call.arguments().get(1));
            builder.endCallBinaryMethod();
            builder.endTag(StandardTags.CallTag.class);
            builder.endSourceSection();
            return;
        }

        builder.beginBlock();

        BytecodeLocal callable = builder.createLocal("terminalCallable", null);
        java.util.List<CanonicalExpression> arguments;
        BytecodeLocal terminalSendReceiver = null;
        String terminalSendSelector = null;
        if (expression instanceof CanonicalCall call) {
            arguments = call.arguments();
            builder.beginStoreLocal(callable);
            emitTerminalCallOperand(builder, call.receiver());
            builder.endStoreLocal();
        } else if (expression instanceof CanonicalSend send) {
            arguments = send.arguments();
            BytecodeLocal receiver = builder.createLocal("terminalReceiver", null);
            builder.beginStoreLocal(receiver);
            emitTerminalCallOperand(builder, send.receiver());
            builder.endStoreLocal();
            terminalSendReceiver = receiver;
            terminalSendSelector = send.message();
        } else {
            throw new AssertionError(
                    "Unsupported fixed-arity call: " + expression.getClass());
        }
        java.util.List<BytecodeLocal> argumentValues = new java.util.ArrayList<>(arguments.size());
        for (CanonicalExpression argument : arguments) {
            BytecodeLocal argumentValue = builder.createLocal("terminalArgument", null);
            builder.beginStoreLocal(argumentValue);
            emitTerminalCallOneArgument(builder, argument);
            builder.endStoreLocal();
            argumentValues.add(argumentValue);
        }
        // D237: a send selects its behavior once the argument packet is formed.
        if (terminalSendReceiver != null) {
            emitGetMethod(builder, terminalSendSelector, terminalSendReceiver, callable);
        }
        // The block's value: the terminal call result or transport.
        emitCallOperation(builder, true, callable, argumentValues, null);

        builder.endBlock();
        builder.endTag(StandardTags.CallTag.class);
        builder.endSourceSection();
    }


    private static boolean isTerminalCallOperand(CanonicalExpression expression) {
        return isDirectTerminalValue(expression)
                && !requiresComposedInvocation(expression);
    }

    /** The single Call1 argument; a number literal stays a primitive carrier. */
    private void emitTerminalCallOneArgument(
            ProtosBytecodeRootNodeGen.Builder builder,
            CanonicalExpression argument) {
        Object carrier =
                argument instanceof CanonicalLiteral literal
                        ? primitiveNumberLiteralOrNull(literal)
                        : null;
        if (carrier != null) {
            builder.emitLoadConstant(carrier);
        } else {
            emitTerminalCallOperand(builder, argument);
        }
    }

    private void emitTerminalCallOperand(
            ProtosBytecodeRootNodeGen.Builder builder,
            CanonicalExpression operand) {
        if (operand instanceof CanonicalLookup lookup) {
            emitLookup(builder, lookup, true);
        } else {
            emitExpression(builder, operand);
        }
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


    /*
     * DIRECT-UNARY EMISSION.
     *
     * This is an emission category, not a proof of non-suspension.
     * The operands and invocation are emitted directly with Bytecode DSL.
     * Nonterminal consumers compose a callee's continuation through the
     * existing Yield/ResumeDispatchedContinuation protocol.
     */

    private boolean i115DirectLookup(CanonicalLookup lookup) {
        if (currentNormalCallbackScalarLocals.containsKey(lookup.name())) {
            CanonicalBindingResolution resolution =
                    currentNormalCallbackScalarAnalysis == null
                            ? null
                            : currentNormalCallbackScalarAnalysis.resolutionOf(lookup)
                                    .orElse(null);
            return resolution instanceof CanonicalBindingResolution.Resolved resolved
                    && resolved.identity().owner() == currentNormalCallbackScalarScope;
        }

        if (currentRootAnalysis == null) {
            return false;
        }

        CanonicalBindingResolution resolution =
                currentRootAnalysis.resolutionOf(lookup).orElse(null);
        CanonicalBindingIdentity identity = staticIdentityOrNull(resolution);
        if (identity == null) {
            return false;
        }

        CanonicalLexicalScope.Storage storage = physicalStorageOf(identity);
        if (storage == CanonicalLexicalScope.Storage.LOCAL) {
            return identity.owner() == currentRootTopScope
                    && currentRootFrameLocals.containsKey(identity.name());
        }
        if (storage == CanonicalLexicalScope.Storage.CELL) {
            return currentRootCellLocals.containsKey(identity);
        }
        return isStaticModuleBinding(resolution, identity);
    }

    private boolean i115DirectUnaryOperand(CanonicalExpression expression) {
        if (expression instanceof CanonicalLiteral literal) {
            return primitiveNumberLiteralOrNull(literal) != null;
        }
        if (expression instanceof CanonicalLookup lookup) {
            return i115DirectLookup(lookup);
        }
        if (expression instanceof CanonicalMember member) {
            return i115DirectUnaryOperand(member.receiver());
        }
        if (expression instanceof CanonicalSend send) {
            /*
             * at and size are never stack-only operands. Their
             * selected behavior may be program-defined and may suspend, so
             * they are staged by emitIndexedSend, which composes the
             * dispatched path with Yield/ResumeDispatchedContinuation.
             */
            return false;
        }
        return false;
    }

    private static boolean i115StructuredSelector(String selector) {
        return switch (selector) {
            case "ensure", "caseOf", "import", "match", "each",
                    "eachStep", "atPut", "atIfAbsent", "remove",
                    "open", "listenTcp", "spawn" -> true;
            default -> false;
        };
    }

    private boolean i115AdmitsDirectUnaryInvocation(
            InvocationShape shape,
            CanonicalExpression receiver,
            String selector,
            java.util.List<CanonicalExpression> arguments) {
        if (arguments.size() != 1 || hasVectorArgument(arguments)
                || !i115DirectUnaryOperand(arguments.get(0))) {
            return false;
        }

        if (shape == InvocationShape.CALL) {
            return (receiver instanceof CanonicalLookup
                            || receiver instanceof CanonicalMember)
                    && i115DirectUnaryOperand(receiver);
        }

        if (shape == InvocationShape.SEND) {
            return selector != null
                    && !primitiveNumericSelector(selector)
                    && !"at".equals(selector)
                    && !i115StructuredSelector(selector)
                    && ControlSelector.of(selector, arguments) == null
                    && i115DirectUnaryOperand(receiver);
        }
        return false;
    }

    /** Direct indexed operations are admitted only after evaluating all operands. */
    private static boolean indexedProtocolSend(CanonicalSend send) {
        return (("at".equals(send.message()) && send.arguments().size() == 1)
                        || ("atPut".equals(send.message()) && send.arguments().size() == 2))
                && !send.contextual()
                && !hasVectorArgument(send.arguments());
    }

    // GetLen, or the selected size behavior.
    private static boolean isIndexedSizeSend(CanonicalSend send) {
        return "size".equals(send.message())
                && !send.contextual()
                && send.arguments().isEmpty();
    }

    /**
     * Stage the receiver and arguments exactly once in source order, then
     * select either the standard indexed operation or its dynamic fallback.
     */
    private void emitIndexedSend(
            ProtosBytecodeRootNodeGen.Builder builder,
            CanonicalSend send,
            BytecodeLocal result,
            BytecodeLocal scratch,
            BytecodeLocal childResult,
            BytecodeLocal resumeValue,
            OperandStager stager,
            boolean primitiveCarrierResult) {
        if (scratch == null || childResult == null || resumeValue == null) {
            throw new AssertionError(
                    "Indexed send scratch locals were not allocated");
        }
        builder.beginSourceSection(
                send.span().startOffset(), send.span().length());
        builder.beginBlock();
        BytecodeLocal receiver =
                builder.createLocal("indexedReceiver", null);
        stageOperand(
                builder, send.receiver(), receiver,
                scratch, childResult, resumeValue, stager);
        java.util.List<BytecodeLocal> operands =
                new java.util.ArrayList<>(send.arguments().size());
        for (CanonicalExpression argument : send.arguments()) {
            BytecodeLocal operand =
                    builder.createLocal("indexedOperand", null);
            stageOperand(
                    builder, argument, operand,
                    scratch, childResult, resumeValue, stager);
            operands.add(operand);
        }
        emitIndexedProtocolOfLocals(
                builder, send.message(), receiver, operands, result,
                childResult, resumeValue, primitiveCarrierResult);
        builder.endBlock();
        builder.endSourceSection();
    }

    /**
     * (BinarySubscript / SetItem / GetLen over staged
     * operands). {@code IsStandardIndexedSelection} observes the behavior
     * actually selected for {@code selector} on the receiver:
     * <ul>
     *   <li>the standard Array/Bytes behavior: the direct
     *       {@code BinarySubscript}, {@code SetItem} or {@code GetLen},
     *       with no method invocation;
     *   <li>anything else (Map, IdentityMap, program-defined or overriding
     *       behavior, or a missing selector): {@code GetMethod} on the
     *       staged receiver and the arity-selected {@code Call*Method},
     *       composed with {@code Yield}/{@code ResumeDispatchedContinuation},
     *       under its {@link StandardTags.CallTag}.
     * </ul>
     * An explicit {@code atPut} send retains the selected method's result.
     * Indexed assignment passes no result local and yields its own RHS.
     */
    private void emitIndexedProtocolOfLocals(
            ProtosBytecodeRootNodeGen.Builder builder,
            String selector,
            BytecodeLocal receiver,
            java.util.List<BytecodeLocal> operands,
            BytecodeLocal result,
            BytecodeLocal childResult,
            BytecodeLocal resumeValue,
            boolean primitiveCarrierResult) {
        BytecodeLocal selectedResult = result;

        builder.beginIfThenElse();

        builder.beginIsStandardIndexedSelection(selector);
        builder.emitLoadLocal(receiver);
        builder.endIsStandardIndexedSelection();

        builder.beginBlock();
        switch (selector) {
            case "atPut" -> {
                builder.beginSetItem();
                builder.emitLoadLocal(operands.get(1));
                builder.emitLoadLocal(receiver);
                builder.emitLoadLocal(operands.get(0));
                builder.endSetItem();
                if (selectedResult != null) {
                    builder.beginStoreLocal(selectedResult);
                    builder.emitLoadLocal(operands.get(1));
                    builder.endStoreLocal();
                }
            }
            case "at" -> {
                beginMutationResult(builder, selectedResult);
                builder.beginBinarySubscript();
                builder.emitLoadLocal(receiver);
                builder.emitLoadLocal(operands.get(0));
                builder.endBinarySubscript();
                endMutationResult(builder, selectedResult);
            }
            case "size" -> {
                beginMutationResult(builder, selectedResult);
                builder.beginGetLen();
                builder.emitLoadLocal(receiver);
                builder.endGetLen();
                endMutationResult(builder, selectedResult);
            }
            default -> throw new AssertionError(
                    "Not an indexed selector: " + selector);
        }
        builder.endBlock();

        builder.beginTag(StandardTags.CallTag.class);
        builder.beginBlock();
        BytecodeLocal callable =
                builder.createLocal("indexedMethod", null);
        emitGetMethod(builder, selector, receiver, callable);
        emitDispatchedCall(
                builder, callable, operands, null, childResult,
                selectedResult, resumeValue, primitiveCarrierResult);
        builder.endBlock();
        builder.endTag(StandardTags.CallTag.class);

        builder.endIfThenElse();
    }

    private boolean i115DirectUnaryCall(CanonicalExpression expression) {
        if (expression instanceof CanonicalCall call) {
            return i115AdmitsDirectUnaryInvocation(
                    InvocationShape.CALL, call.receiver(),
                    null, call.arguments());
        }
        if (expression instanceof CanonicalSend send) {
            return i115AdmitsDirectUnaryInvocation(
                    InvocationShape.SEND, send.receiver(),
                    send.message(), send.arguments());
        }
        return false;
    }

    private void emitI115DirectUnaryOperand(
            ProtosBytecodeRootNodeGen.Builder builder,
            CanonicalExpression expression,
            boolean carrier) {
        if (expression instanceof CanonicalLiteral literal) {
            if (carrier) {
                builder.emitLoadConstant(primitiveNumberLiteralOrNull(literal));
            } else {
                emitLiteral(builder, literal);
            }
            return;
        }
        if (expression instanceof CanonicalLookup lookup) {
            emitLookup(builder, lookup, carrier);
            return;
        }
        if (expression instanceof CanonicalMember member) {
            builder.beginGetAttribute(attributeName(member.name()));
            emitI115DirectUnaryOperand(builder, member.receiver(), false);
            builder.endGetAttribute();
            return;
        }
        if (expression instanceof CanonicalSend send
                && primitiveNumericSendCandidate(send)) {
            builder.beginSourceSection(
                    send.span().startOffset(), send.span().length());
            emitDirectNumericOperation(
                    builder, send, null, null, null);
            builder.endSourceSection();
            return;
        }
        throw new AssertionError(
                "Unadmitted I115 direct unary operand: "
                        + expression.getClass().getSimpleName());
    }

    private void emitI115DirectUnaryOperation(
            ProtosBytecodeRootNodeGen.Builder builder,
            InvocationShape shape,
            CanonicalExpression receiver,
            String selector,
            CanonicalExpression argument,
            boolean primitiveCarrierResult,
            boolean terminal) {
        noteDynamicInvocation();
        if (!primitiveCarrierResult) {
            builder.beginMaterializePrimitiveNumericCarrier();
        }

        builder.beginTag(StandardTags.CallTag.class);

        if (shape == InvocationShape.CALL) {
            builder.beginCallUnaryMethod(terminal);
            emitI115DirectUnaryOperand(builder, receiver, false);
            emitI115DirectUnaryOperand(builder, argument, true);
            builder.endCallUnaryMethod();
        } else if (shape == InvocationShape.SEND) {
            builder.beginBlock();

            builder.beginBindStackValue();
            if (terminal) {
                emitTerminalCallOperand(builder, receiver);
            } else {
                emitI115DirectUnaryOperand(builder, receiver, false);
            }
            StackValue receiverValue = builder.endBindStackValue();

            builder.beginBindStackValue();
            if (terminal) {
                emitTerminalCallOneArgument(builder, argument);
            } else {
                emitI115DirectUnaryOperand(builder, argument, true);
            }
            StackValue argumentValue = builder.endBindStackValue();

            // D237: receiver, then argument, then selection and the call.
            builder.beginCallBinaryMethod(terminal, true);

            builder.beginGetMethod(
                    TruffleString.fromJavaStringUncached(
                            selector, TruffleString.Encoding.UTF_16),
                    true);
            builder.emitLoadStackValue(receiverValue);
            builder.endGetMethod();

            builder.emitLoadStackValue(receiverValue);
            builder.emitLoadStackValue(argumentValue);

            builder.endCallBinaryMethod();
            builder.endBlock();
        } else {
            throw new AssertionError(
                    "Not an ordinary direct unary invocation");
        }

        builder.endTag(StandardTags.CallTag.class);

        if (!primitiveCarrierResult) {
            builder.emitLoadConstant(true);
            builder.endMaterializePrimitiveNumericCarrier();
        }
    }

    /**
     * A specialized Call1/Send1 is not proof of synchronous completion.
     * Keep the specialized direct call operation and compose its
     * transport through the existing Bytecode DSL Yield protocol before
     * exposing the result to an enclosing expression.
     *
     * The Runnable emits Bytecode DSL at compilation time. It is not a
     * guest callback or an alternative runtime invocation mechanism.
     */
    private void emitComposedDirectUnaryValue(
            ProtosBytecodeRootNodeGen.Builder builder,
            Runnable invocation,
            BytecodeLocal childResult,
            BytecodeLocal resumeValue,
            boolean primitiveCarrierResult) {
        if (childResult == null || resumeValue == null) {
            throw new AssertionError(
                    "Direct unary invocation requires continuation locals");
        }

        builder.beginBlock();

        builder.beginStoreLocal(childResult);
        invocation.run();
        builder.endStoreLocal();

        emitDispatchedContinuationComposition(
                builder, childResult, resumeValue);

        if (primitiveCarrierResult) {
            builder.emitLoadLocal(childResult);
        } else {
            builder.beginMaterializePrimitiveNumericCarrier();
            builder.emitLoadLocal(childResult);
            builder.emitLoadConstant(true);
            builder.endMaterializePrimitiveNumericCarrier();
        }

        builder.endBlock();
    }

    private void emitI115DirectUnaryValue(
            ProtosBytecodeRootNodeGen.Builder builder,
            CanonicalExpression expression,
            boolean primitiveCarrierResult) {
        if (!i115DirectUnaryCall(expression)) {
            throw new AssertionError("Unadmitted direct unary call");
        }

        builder.beginSourceSection(
                expression.span().startOffset(),
                expression.span().length());

        if (expression instanceof CanonicalCall call) {
            emitI115DirectUnaryOperation(
                    builder, InvocationShape.CALL,
                    call.receiver(), null, call.arguments().get(0),
                    primitiveCarrierResult, false);
        } else {
            CanonicalSend send = (CanonicalSend) expression;
            emitI115DirectUnaryOperation(
                    builder, InvocationShape.SEND,
                    send.receiver(), send.message(), send.arguments().get(0),
                    primitiveCarrierResult, false);
        }

        builder.endSourceSection();
    }

    /**
     * whether emitting {@code expression} can need the continuation
     * scratch locals ({@code preparedClosureCall}, {@code childResult},
     * {@code resumeValue}) of a suspendable invocation, or must otherwise be
     * treated as able to suspend. Unlike {@link #requiresComposedInvocation},
     * which selects how an expression is emitted, this static emission
     * decision answers only whether a real resumption can occur anywhere in
     * its subtree.
     *
     * <p>The switch is exhaustive over the sealed canonical expression
     * family. A node is scratch-free only when it is itself synchronous and
     * every executable subexpression (targets, values, receivers, operands)
     * is scratch-free; a numeric operator send is never scratch-free, since a
     * non-standard selected operator may suspend. Every node
     * whose synchronous execution is not proven here (calls, dispatched
     * sends, super sends, derived inequalities, indexed assignments,
     * compositions, objects, map constructions, returns, sequences and
     * spreads) keeps the scratch.
     */
    private boolean requiresContinuationScratch(CanonicalExpression expression) {
        return switch (expression) {
            case CanonicalLiteral ignored -> false;
            case CanonicalLookup ignored -> false;
            case CanonicalIntrinsic ignored -> false;
            // Materialization only; the body runs in the Closure's own root.
            case CanonicalClosure ignored -> false;
            case CanonicalSend send -> {
                if (send.contextual()) {
                    // A contextual callback may require an ordinary Closure
                    // activation and therefore the existing Yield protocol.
                    // The admitted inline path emits no such invocation.
                    yield true;
                }
                // at/size may dispatch a suspending selected behavior.
                if (indexedProtocolSend(send) || isIndexedSizeSend(send)) {
                    yield true;
                }
                StaticControlSelection staticControl = classifyStaticControlSelection(send);
                if (staticControl != null) {
                    if (staticControl.eliminable()) {
                        yield false;
                    }
                    CanonicalClosure callback = (CanonicalClosure)
                            send.arguments().get(staticControl.selectedPosition() - 1);
                    yield callback.body().expressions().stream()
                            .anyMatch(this::requiresContinuationScratch);
                }
                // A numeric operator executes the selected behavior, and a
                // selection other than the standard operator may suspend.
                yield true;
            }
            case CanonicalCreate create ->
                    create.target().map(this::requiresContinuationScratch)
                                    .orElse(false)
                            || requiresContinuationScratch(create.value());
            case CanonicalAssign assign ->
                    assign.target().map(this::requiresContinuationScratch)
                                    .orElse(false)
                            || requiresContinuationScratch(assign.value());
            case CanonicalMultipleCreate create -> requiresContinuationScratch(create.value());
            case CanonicalMember member -> requiresContinuationScratch(member.receiver());
            case CanonicalIdentity identity ->
                    requiresContinuationScratch(identity.left())
                            || requiresContinuationScratch(identity.right());
            case CanonicalNotIdentity identity ->
                    requiresContinuationScratch(identity.left())
                            || requiresContinuationScratch(identity.right());
            case CanonicalCall ignored -> true;
            case CanonicalSuperSend ignored -> true;
            case CanonicalDerivedInequality ignored -> true;
            // a non-standard receiver dispatches atPut, which may suspend.
            case CanonicalIndexedAssign ignored -> true;
            case CanonicalCompose ignored -> true;
            case CanonicalObject ignored -> true;
            case CanonicalMapConstruction ignored -> true;
            case CanonicalReturn ignored -> true;
            case CanonicalSequence ignored -> true;
            case CanonicalSpread ignored -> true;
            case CanonicalNamedArgument named ->
                    requiresContinuationScratch(named.expression());
        };
    }

    private static boolean hasSpreadArgument(
            java.util.List<CanonicalExpression> arguments) {
        return arguments.stream()
                .anyMatch(CanonicalSpread.class::isInstance);
    }

    /** I143: labels explicitly supplied at a source call site. */
    private static boolean hasNamedArgument(
            java.util.List<CanonicalExpression> arguments) {
        return arguments.stream()
                .anyMatch(CanonicalNamedArgument.class::isInstance);
    }

    /** Either form requires the source-ordered argument vector. */
    private static boolean hasVectorArgument(
            java.util.List<CanonicalExpression> arguments) {
        return hasSpreadArgument(arguments)
                || hasNamedArgument(arguments);
    }

    private static CanonicalExpression suppliedArgumentExpression(
            CanonicalExpression argument) {
        if (argument instanceof CanonicalSpread spread) {
            return spread.expression();
        }
        if (argument instanceof CanonicalNamedArgument named) {
            return named.expression();
        }
        return argument;
    }

    private void emitBodySpreadArgumentVector(
            ProtosBytecodeRootNodeGen.Builder builder,
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

            if (argument instanceof CanonicalNamedArgument named) {
                builder.beginAppendNamedSuppliedArgument(named.name());
                builder.emitLoadLocal(suppliedVector);
                builder.emitLoadLocal(value);
                builder.endAppendNamedSuppliedArgument();
            } else if (argument instanceof CanonicalSpread) {
                {
                    builder.beginAppendSpreadSuppliedArgument();
                }
                builder.emitLoadLocal(suppliedVector);
                builder.emitLoadLocal(value);
                emitSendCallerOperand(builder);
                {
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
            ProtosBytecodeRootNodeGen.Builder builder,
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

            if (argument instanceof CanonicalNamedArgument named) {
                builder.beginAppendNamedSuppliedArgument(named.name());
                builder.emitLoadLocal(suppliedVector);
                builder.emitLoadLocal(value);
                builder.endAppendNamedSuppliedArgument();
            } else if (argument instanceof CanonicalSpread) {
                {
                    builder.beginAppendSpreadSuppliedArgument();
                }
                builder.emitLoadLocal(suppliedVector);
                builder.emitLoadLocal(value);
                emitSendCallerOperand(builder);
                {
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


    /**
     * directly emit a synchronous member receiver when its
     * expression is supported by ordinary value lowering.
     *
     * No continuation-local is necessary for these receivers.
     * Expressions requiring composition retain their existing staged
     * continuation path.
     */
    private boolean isDirectAttributeReadReceiver(
            CanonicalExpression receiver) {
        if (isDirectTerminalValue(receiver)) {
            return true;
        }
        return receiver instanceof CanonicalSend send
                && primitiveNumericSendCandidate(send)
                && !requiresContinuationScratch(send)
                && !containsClosure(send);
    }

    private void emitDirectAttributeReadReceiver(
            ProtosBytecodeRootNodeGen.Builder builder,
            CanonicalExpression receiver,
            BytecodeLocal preparedCall,
            BytecodeLocal childResult,
            BytecodeLocal resumeValue) {
        if (receiver instanceof CanonicalSend send) {
            emitDirectNumericSendValue(
                    builder, send, preparedCall, childResult, resumeValue,
                    false);
        } else {
            emitExpression(builder, receiver);
        }
    }

    private void emitBodyExpressionToLocal(
            ProtosBytecodeRootNodeGen.Builder builder,
            CanonicalExpression expression,
            BytecodeLocal target,
            BytecodeLocal preparedCall,
            BytecodeLocal childResult,
            BytecodeLocal resumeValue) {
        if (isI115EliminableControl(expression)) {
            builder.beginStoreLocal(target);
            builder.emitLoadConstant(ProtosNullValue.INSTANCE);
            builder.endStoreLocal();
            return;
        }
        if (i115DirectUnaryCall(expression)) {
            builder.beginStoreLocal(target);
            emitComposedDirectUnaryValue(
                    builder,
                    () -> emitI115DirectUnaryValue(builder, expression, true),
                    childResult, resumeValue, false);
            builder.endStoreLocal();
            return;
        }
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
            if (isDirectAttributeReadReceiver(member.receiver())) {
                builder.beginStoreLocal(target);
                builder.beginGetAttribute(attributeName(member.name()));
                emitDirectAttributeReadReceiver(
                        builder, member.receiver(),
                        preparedCall, childResult, resumeValue);
                builder.endGetAttribute();
                builder.endStoreLocal();
                return;
            }

            BytecodeLocal receiverValue =
                    builder.createLocal("memberReceiver", null);
            emitBodyExpressionToLocal(
                    builder, member.receiver(), receiverValue,
                    preparedCall, childResult, resumeValue);

            builder.beginStoreLocal(target);
            builder.beginGetAttribute(attributeName(member.name()));
            builder.emitLoadLocal(receiverValue);
            builder.endGetAttribute();
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
            builder.beginIdentity(false);
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
            builder.beginIdentity(true);
            builder.emitLoadLocal(leftValue);
            builder.emitLoadLocal(rightValue);
            builder.endIdentity();
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
            ProtosBytecodeRootNodeGen.Builder builder,
            CanonicalExpression expression,
            BytecodeLocal target,
            BytecodeLocal preparedCall,
            BytecodeLocal childResult,
            BytecodeLocal resumeValue) {
        if (i115DirectUnaryCall(expression)
                || (expression instanceof CanonicalSend send
                        && isDirectNumericOperand(send))) {
            builder.beginStoreLocal(target);
            emitExpressionValue(
                    builder, expression, preparedCall,
                    childResult, resumeValue, false);
            builder.endStoreLocal();
            return;
        }
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
            if (isDirectAttributeReadReceiver(member.receiver())) {
                builder.beginStoreLocal(target);
                builder.beginGetAttribute(attributeName(member.name()));
                emitDirectAttributeReadReceiver(
                        builder, member.receiver(),
                        preparedCall, childResult, resumeValue);
                builder.endGetAttribute();
                builder.endStoreLocal();
                return;
            }

            BytecodeLocal receiverValue =
                    builder.createLocal("defaultMemberReceiver", null);
            emitDefaultExpressionToLocal(
                    builder, member.receiver(), receiverValue,
                    preparedCall, childResult, resumeValue);

            builder.beginStoreLocal(target);
            builder.beginGetAttribute(attributeName(member.name()));
            builder.emitLoadLocal(receiverValue);
            builder.endGetAttribute();
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
            builder.beginIdentity(false);
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
            builder.beginIdentity(true);
            builder.emitLoadLocal(leftValue);
            builder.emitLoadLocal(rightValue);
            builder.endIdentity();
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
            ProtosBytecodeRootNodeGen.Builder builder,
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
            ProtosBytecodeRootNodeGen.Builder builder,
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
            ProtosBytecodeRootNodeGen.Builder builder,
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
            ProtosBytecodeRootNodeGen.Builder builder,
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
            ProtosBytecodeRootNodeGen.Builder builder,
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
        builder.beginMapConstructionFactoryCall(false);
        emitCurrentActivation(builder);
        builder.emitLoadLocal(factoryValue);
        builder.endMapConstructionFactoryCall();
        builder.endStoreLocal();

        emitRequestInvocation(
                builder,
                factoryResult,
                preparedCall,
                childResult,
                resumeValue);

        builder.beginStoreLocal(mapValue);
        builder.beginMapConstructionFactoryCall(true);
        emitCurrentActivation(builder);
        builder.emitLoadLocal(factoryResult);
        builder.endMapConstructionFactoryCall();
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

            builder.beginStructuredAdvance(ProtosStructuredPhase.ENTER_COMPARISON);
            builder.emitLoadLocal(initialDefinition);
            builder.endStructuredAdvance();

            builder.beginTryFinally(
                    () -> {
                        builder.beginStructuredAdvance(ProtosStructuredPhase.LEAVE_COMPARISON);
                        builder.emitLoadLocal(initialDefinition);
                        builder.endStructuredAdvance();
                    });

            builder.beginBlock();

            builder.beginStoreLocal(preparedCall);
            builder.beginStructuredPrepareChildCall(ProtosStructuredPhase.HASH);
            builder.emitLoadLocal(initialDefinition);
            builder.endStructuredPrepareChildCall();
            builder.endStoreLocal();

            emitRequestInvocation(
                    builder,
                    callbackResult,
                    preparedCall,
                    childResult,
                    resumeValue);

            builder.endBlock();
            builder.endTryFinally();

            builder.beginStructuredAcceptChildOutcome(ProtosStructuredPhase.HASH);
            builder.emitLoadLocal(initialDefinition);
            builder.emitLoadLocal(callbackResult);
            builder.endStructuredAcceptChildOutcome();

            builder.beginWhile();

            builder.beginStructuredHasNext(ProtosStructuredPhase.EQUALITY);
            builder.emitLoadLocal(initialDefinition);
            builder.endStructuredHasNext();

            builder.beginBlock();

            builder.beginStructuredAdvance(ProtosStructuredPhase.ENTER_COMPARISON);
            builder.emitLoadLocal(initialDefinition);
            builder.endStructuredAdvance();

            builder.beginTryFinally(
                    () -> {
                        builder.beginStructuredAdvance(ProtosStructuredPhase.LEAVE_COMPARISON);
                        builder.emitLoadLocal(initialDefinition);
                        builder.endStructuredAdvance();
                    });

            builder.beginBlock();

            builder.beginStoreLocal(preparedCall);
            builder.beginStructuredPrepareChildCall(ProtosStructuredPhase.EQUALITY);
            builder.emitLoadLocal(initialDefinition);
            builder.endStructuredPrepareChildCall();
            builder.endStoreLocal();

            emitRequestInvocation(
                    builder,
                    callbackResult,
                    preparedCall,
                    childResult,
                    resumeValue);

            builder.endBlock();
            builder.endTryFinally();

            builder.beginStructuredAcceptChildOutcome(ProtosStructuredPhase.EQUALITY);
            builder.emitLoadLocal(initialDefinition);
            builder.emitLoadLocal(callbackResult);
            builder.endStructuredAcceptChildOutcome();

            builder.endBlock();
            builder.endWhile();

            builder.beginStructuredAdvance(ProtosStructuredPhase.STEP);
            builder.emitLoadLocal(initialDefinition);
            builder.endStructuredAdvance();
        }

        builder.beginStoreLocal(result);
        builder.emitLoadLocal(mapValue);
        builder.endStoreLocal();
    }

    private void emitBodyObjectLiteral(
            ProtosBytecodeRootNodeGen.Builder builder,
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
            ProtosBytecodeRootNodeGen.Builder builder,
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
            ProtosBytecodeRootNodeGen.Builder builder,
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
     * <p>The body is not a lexical capture scope. it keeps the
     * enclosing root's analysis and storage, so a reference to an enclosing
     * Closure binding uses that binding's own LOCAL or CELL storage, Closures
     * nested in the body capture cells from the enclosing root, and the
     * body's own bindings are MEMBER bindings created through its
     * construction activation.
     */
    private void emitInlineObjectConstruction(
            ProtosBytecodeRootNodeGen.Builder builder,
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

        if (requiresLexicalViewBeforeConstruction(object)) {
            /*
             * a site of this body may select a binding of the
             * enclosing Closure frame by name (e.g. `name: name` while the
             * member is ABSENT), so the frame is bound to its frame-backed
             * view before the construction activation captures the
             * enclosing lexical environment.
             */
            builder.emitEnsureCurrentClosureLexicalView();
        }

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
            BytecodeLocal savedActivationLocal = currentActivationLocal;
            try {
                currentActivationLocal = constructionActivation;
                BytecodeLocal bodyResult =
                        builder.createLocal("objectBodyResult", null);
                emitStatementsToLocal(builder, object.body(), bodyResult);
            } finally {
                currentActivationLocal = savedActivationLocal;
            }
        }

        builder.beginStoreLocal(result);
        builder.emitLoadLocal(constructed);
        builder.endStoreLocal();
    }

    /**
     * one creation. A target-less creation of a binding with LOCAL or
     * CELL physical storage (an admitted scalar binding, an inline callback
     * binding, an ordinary lexical binding or a cell promoted to LOCAL by
     * {@link InlineStoragePlan}) is a single direct store of its RHS
     * ({@code StoreVisitor}). A NAME/MEMBER or explicitly targeted
     * creation keeps its destination selection and evaluation order. With
     * {@code result == null} the creation has no consumer: no value is
     * produced, materialized, stored or reloaded beyond the mutation itself.
     *
     * <p>a LOCAL/CELL creation whose binding may already be
     * PRESENT, or whose Context may be CLOSED/FROZEN, applies the ordinary
     * {@code :} rules through {@code CreateLexicalLocal}/{@code
     * CreateLexicalCell}; every other one is the direct store.
     */

    /**
     * a directly emitible physical attribute operand.
     *
     * Nested Closure roots must not be registered inside an already
     * opened attribute operation. Unsupported direct shapes are staged
     * using the existing body/default continuation lowering.
     */
    private boolean directAttributeOperand(
            CanonicalExpression expression) {
        if (expression instanceof CanonicalLiteral
                || expression instanceof CanonicalLookup
                || expression instanceof CanonicalIntrinsic
                || expression instanceof CanonicalClosure) {
            return true;
        }
        if (expression instanceof CanonicalMember member) {
            CanonicalExpression receiver = member.receiver();
            return !(receiver instanceof CanonicalClosure)
                    && directAttributeMemberReceiver(receiver);
        }
        if (expression instanceof CanonicalSend send) {
            return primitiveNumericSendCandidate(send)
                    && !requiresContinuationScratch(send);
        }
        return false;
    }

    private static boolean directAttributeMemberReceiver(
            CanonicalExpression expression) {
        if (expression instanceof CanonicalLiteral
                || expression instanceof CanonicalLookup
                || expression instanceof CanonicalIntrinsic) {
            return true;
        }
        if (expression instanceof CanonicalMember member) {
            return directAttributeMemberReceiver(member.receiver());
        }
        return false;
    }

    /**
     * Build an operand producer before opening SetAttribute/CreateAttribute.
     * Closure plans are registered here, outside the physical operation.
     */
    private BindingValue attributeOperandValue(
            ProtosBytecodeRootNodeGen.Builder builder,
            CanonicalExpression expression,
            boolean defaultExpression,
            BytecodeLocal preparedCall,
            BytecodeLocal childResult,
            BytecodeLocal resumeValue) {

        if (expression instanceof CanonicalClosure closure) {
            ProtosClosureExecutionPlanCell plan =
                    bytecodeClosurePlan(builder, closure);
            return BindingValue.synchronous(
                    () -> emitClosureCapture(builder, closure, plan));
        }

        if (directAttributeOperand(expression)
                && !requiresContinuationScratch(expression)) {
            return BindingValue.synchronous(() -> {
                if (expression instanceof CanonicalSend) {
                    emitExpressionValue(
                            builder, expression, preparedCall,
                            childResult, resumeValue, false);
                } else {
                    emitExpression(builder, expression);
                }
            });
        }

        return new BindingValue(
                null,
                target -> {
                    if (defaultExpression) {
                        emitDefaultExpressionToLocal(
                                builder, expression, target,
                                preparedCall, childResult, resumeValue);
                    } else {
                        emitBodyExpressionToLocal(
                                builder, expression, target,
                                preparedCall, childResult, resumeValue);
                    }
                },
                true,
                false);
    }

    /**
     * Evaluate the receiver before the RHS, retaining it across RHS effects or
     * suspension. Physical writes consume value then receiver and return void;
     * a consumed expression returns its exact RHS from the result local.
     */
    private void emitAttributeWrite(
            ProtosBytecodeRootNodeGen.Builder builder,
            String name,
            CanonicalExpression receiver,
            CanonicalExpression rhs,
            boolean creation,
            boolean defaultExpression,
            BytecodeLocal result,
            BytecodeLocal preparedCall,
            BytecodeLocal childResult,
            BytecodeLocal resumeValue) {

        BindingValue rhsValue = attributeOperandValue(
                builder, rhs, defaultExpression,
                preparedCall, childResult, resumeValue);

        BindingValue receiverValue = attributeOperandValue(
                builder, receiver, defaultExpression,
                preparedCall, childResult, resumeValue);

        boolean stageRhs =
                result != null
                        || rhsValue.suspendable()
                        || receiverValue.suspendable();

        BytecodeLocal rhsLocal = null;
        BytecodeLocal receiverLocal = builder.createLocal("attributeReceiver", null);
        emitBindingValueToLocal(builder, receiverValue, receiverLocal);

        if (stageRhs) {
            rhsLocal = result != null
                    ? result
                    : builder.createLocal("attributeRhs", null);
            emitBindingValueToLocal(builder, rhsValue, rhsLocal);
        }

        if (creation) {
            builder.beginCreateAttribute(attributeName(name));
        } else {
            builder.beginSetAttribute(attributeName(name));
        }

        if (stageRhs) {
            builder.emitLoadLocal(rhsLocal);
        } else {
            rhsValue.direct().run();
        }

        builder.emitLoadLocal(receiverLocal);

        if (creation) {
            builder.endCreateAttribute();
        } else {
            builder.endSetAttribute();
        }
    }

    private void emitBodyCreate(
            ProtosBytecodeRootNodeGen.Builder builder,
            CanonicalCreate create,
            BytecodeLocal result,
            BytecodeLocal preparedCall,
            BytecodeLocal childResult,
            BytecodeLocal resumeValue) {
        if (create.target().isPresent()) {
            emitAttributeWrite(
                    builder, create.name(), create.target().orElseThrow(),
                    create.value(), true, false, result,
                    preparedCall, childResult, resumeValue);
            return;
        }
        if (!currentNormalCallbackScalarLocals.isEmpty()) {
            BytecodeLocal own = currentNormalCallbackScalarLocals.get(create.name());
            CanonicalBindingIdentity identity =
                    currentNormalCallbackScalarAnalysis.identityOf(create).orElse(null);
            if (create.target().isPresent()
                    || own == null
                    || identity == null
                    || identity.owner() != currentNormalCallbackScalarScope) {
                throw new AssertionError(
                        "I095-B6a unproven callback creation: " + create.name());
            }
            emitBindingWrite(
                    builder, BindingStorage.ofLocal(own),
                    expressionBindingValue(
                            builder, create.value(), false,
                            preparedCall, childResult, resumeValue),
                    result);
            return;
        }
        if (create.target().isEmpty()) {
            if (currentActivationLocal == null
                    && currentRootScalarLocals.contains(create.name())) {
                // PERF034-C/E: an admitted scalar root contains no Closure
                // literal, so its bindings are always plain LOCALs.
                BytecodeLocal local = currentRootFrameLocals.get(create.name());
                if (local == null) {
                    throw new AssertionError(
                            "missing scalar creation local: " + create.name());
                }
                BindingValue value = expressionBindingValue(
                        builder, create.value(), false,
                        preparedCall, childResult, resumeValue);
                if (value.suspendable()) {
                    throw new AssertionError("scalar creation must be synchronous");
                }
                emitBindingWrite(
                        builder, BindingStorage.ofLocal(local),
                        BindingValue.synchronous(() -> emitPresenceCheckedCreateOperation(
                                builder, create.name(), BindingStorage.ofLocal(local),
                                value.direct())),
                        result);
                return;
            }
            CanonicalBindingIdentity identity =
                    currentRootAnalysis == null
                            ? null
                            : currentRootAnalysis.identityOf(create).orElse(null);
            if (identity != null
                    && requiresPresenceCheckedCreation(create)
                    && emitPresenceCheckedCreate(
                            builder, identity, result,
                            target -> emitBodyExpressionToLocal(
                                    builder, create.value(), target,
                                    preparedCall, childResult, resumeValue))) {
                return;
            }
            // I091: a LOCAL creation outside an inline activation region may
            // retain a primitive carrier; a cell holds guest values only.
            if (identity != null
                    && emitStoreLexicalBinding(
                            builder, identity, create.value(),
                            currentActivationLocal == null, result,
                            preparedCall, childResult, resumeValue)) {
                return;
            }
            BytecodeLocal value = builder.createLocal("createValue", null);
            emitBodyExpressionToLocal(
                    builder, create.value(), value, preparedCall, childResult, resumeValue);
            emitCreateCurrentBinding(builder, create, value, result);
            return;
        }

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
            ProtosBytecodeRootNodeGen.Builder builder,
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

        CanonicalBindingAnalysis analysis = currentNormalCallbackScalarAnalysis != null
                ? currentNormalCallbackScalarAnalysis : currentRootAnalysis;
        emitMultipleCreateCurrentBindings(
                builder,
                create.names(),
                analysis == null ? null : analysis.identitiesOf(create).orElse(null),
                analysis != null && analysis.requiresPresenceCheckedCreation(create),
                source,
                result);
    }

    private void emitBodyAssign(
            ProtosBytecodeRootNodeGen.Builder builder,
            CanonicalAssign assign,
            BytecodeLocal result,
            BytecodeLocal preparedCall,
            BytecodeLocal childResult,
            BytecodeLocal resumeValue) {
        if (assign.target().isPresent()) {
            emitAttributeWrite(
                    builder, assign.name(), assign.target().orElseThrow(),
                    assign.value(), false, false, result,
                    preparedCall, childResult, resumeValue);
            return;
        }
        if (!currentNormalCallbackScalarLocals.isEmpty()
                && currentNormalCallbackScalarLocals.containsKey(assign.name())) {
            BytecodeLocal own = currentNormalCallbackScalarLocals.get(assign.name());
            CanonicalBindingResolution resolution =
                    currentNormalCallbackScalarAnalysis.resolutionOf(assign)
                            .orElse(null);
            if (assign.target().isPresent()
                    || own == null
                    || !(resolution instanceof CanonicalBindingResolution.Resolved resolved)
                    || resolved.identity().owner() != currentNormalCallbackScalarScope) {
                throw new AssertionError(
                        "I095-B6a unproven callback assignment: " + assign.name());
            }
            emitBindingWrite(
                    builder, BindingStorage.ofLocal(own),
                    expressionBindingValue(
                            builder, assign.value(), false,
                            preparedCall, childResult, resumeValue),
                    result);
            return;
        }
        if (isScalarLocalAssign(assign)) {
            emitScalarAssign(builder, assign, result);
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
        return currentNormalCallbackScalarLocals.isEmpty()
                && assign.target().isEmpty()
                && currentActivationLocal == null
                && currentRootScalarLocals.contains(assign.name());
    }

    /**
     * store.local: write an admitted scalar binding directly.
     *
     * <p>an admitted scalar root never observes its Context, so
     * the binding cannot be removed, recreated or shadowed: the direct write
     * is exactly the semantic write.
     */
    private void emitScalarAssign(
            ProtosBytecodeRootNodeGen.Builder builder,
            CanonicalAssign assign,
            BytecodeLocal result) {
        BytecodeLocal local = currentRootFrameLocals.get(assign.name());
        if (local == null) {
            throw new AssertionError(
                    "admitted scalar assignment is missing: " + assign.name());
        }
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
        if (result != null) {
            builder.beginStoreLocal(result);
            builder.emitLoadLocal(local);
            builder.endStoreLocal();
        }
    }

    /**
     * Emits a bare or member assignment outside the admitted scalar lane.
     *
     * <p>the destination is always selected before the RHS
     * evaluates and is never selected again afterwards. A {@code DIRECT}
     * LOCAL or CELL destination (provably the PRESENT nearest binding) is
     * written directly ({@code store.local}/{@code store.cell}). A
     * {@code PRESENCE_CHECKED} LOCAL or CELL destination selects its own
     * storage while PRESENT, or the destination chosen by live lexical
     * selection, before the RHS. A module binding uses {@code
     * SelectWriteGlobal}/{@code WriteGlobal}. Every other destination is
     * selected by live lexical selection ({@code AccessNamedLexical}) and
     * assigned by {@code MutateLocalSlot}. A member assignment keeps the
     * existing member operations.
     */
    private void emitResolvedBodyAssign(
            ProtosBytecodeRootNodeGen.Builder builder,
            CanonicalAssign assign,
            BytecodeLocal result,
            BytecodeLocal preparedCall,
            BytecodeLocal childResult,
            BytecodeLocal resumeValue) {
        CanonicalBindingIdentity identity = assignmentIdentityOrNull(assign);
        CanonicalBindingAnalysis.LexicalAccess access = assignmentAccess(assign, identity);
        if (identity != null
                && access != CanonicalBindingAnalysis.LexicalAccess.DIRECT
                && emitSelectedLexicalAssign(
                        builder, assign, identity, access, result,
                        target -> emitBodyExpressionToLocal(
                                builder, assign.value(), target,
                                preparedCall, childResult, resumeValue))) {
            return;
        }
        if (identity != null
                && access == CanonicalBindingAnalysis.LexicalAccess.DIRECT
                && emitStoreLexicalBinding(
                        builder, identity, assign.value(), true, result,
                        preparedCall, childResult, resumeValue)) {
            return;
        }
        BytecodeLocal value = builder.createLocal("assignValue", null);
        if (identity != null && access != CanonicalBindingAnalysis.LexicalAccess.SELECTED) {
            CanonicalBindingResolution resolution =
                    currentRootAnalysis.resolutionOf(assign).orElse(null);
            if (isStaticModuleBinding(resolution, identity)) {
                BytecodeLocal destination = emitSelectModuleWriteTarget(
                        builder, identity, physicalModuleDepth(resolution));
                emitBodyExpressionToLocal(
                        builder, assign.value(), value, preparedCall, childResult, resumeValue);
                emitModuleAssign(builder, destination, value, result);
                return;
            }
        }

        BytecodeLocal mutationTarget = builder.createLocal("assignMutationTarget", null);
        emitResolveNamedWriteTarget(builder, assign, mutationTarget);
        emitBodyExpressionToLocal(
                builder, assign.value(), value, preparedCall, childResult, resumeValue);
        emitNamedOrMemberAssign(builder, assign, mutationTarget, value, result);
    }

    /** the run-time access of a bare assignment's destination. */
    private CanonicalBindingAnalysis.LexicalAccess assignmentAccess(
            CanonicalAssign assign, CanonicalBindingIdentity identity) {
        return identity == null
                ? CanonicalBindingAnalysis.LexicalAccess.SELECTED
                : currentRootAnalysis.accessOf(assign);
    }

    /**
     * a bare assignment to a LOCAL or CELL binding whose
     * membership must be validated, or whose nearest binding must be selected
     * live. The destination is selected into a fresh local before {@code
     * rhsToLocal} evaluates the RHS, and the assignment operation writes
     * through that exact destination: the binding's own storage when it was
     * PRESENT, otherwise the destination live selection chose. A CELL site
     * whose nearest binding is not proven ({@code SELECTED}) returns {@code
     * false} and keeps the generic named path. NAME and MEMBER storage also
     * return {@code false}.
     */
    private boolean emitSelectedLexicalAssign(
            ProtosBytecodeRootNodeGen.Builder builder,
            CanonicalAssign assign,
            CanonicalBindingIdentity identity,
            CanonicalBindingAnalysis.LexicalAccess access,
            BytecodeLocal result,
            java.util.function.Consumer<BytecodeLocal> rhsToLocal) {
        BytecodeLocal cell = cellLocalOrNull(identity);
        if (cell != null) {
            if (access == CanonicalBindingAnalysis.LexicalAccess.SELECTED) {
                return false;
            }
            BytecodeLocal destination = builder.createLocal("assignCellTarget", null);
            builder.beginStoreLocal(destination);
            builder.beginSelectLexicalCellWrite(assign.name());
            builder.emitLoadLocal(cell);
            builder.endSelectLexicalCellWrite();
            builder.endStoreLocal();
            BytecodeLocal value = builder.createLocal("assignValue", null);
            rhsToLocal.accept(value);
            beginMutationResult(builder, result);
            builder.beginAssignLexicalCell(assign.name());
            builder.emitLoadLocal(destination);
            builder.emitLoadLocal(value);
            builder.endAssignLexicalCell();
            endMutationResult(builder, result);
            return true;
        }
        BytecodeLocal local = ownLocalOrNull(identity);
        if (local == null) {
            return false;
        }
        BytecodeLocal destination = builder.createLocal("assignLocalTarget", null);
        builder.beginStoreLocal(destination);
        builder.emitSelectLexicalLocalWrite(assign.name(), local);
        builder.endStoreLocal();
        BytecodeLocal value = builder.createLocal("assignValue", null);
        rhsToLocal.accept(value);
        beginMutationResult(builder, result);
        builder.beginAssignLexicalLocal(assign.name(), local);
        builder.emitLoadLocal(destination);
        builder.emitLoadLocal(value);
        builder.endAssignLexicalLocal();
        endMutationResult(builder, result);
        return true;
    }

    /**
     * a target-less creation of a LOCAL or CELL binding that may
     * find its binding PRESENT or its Context CLOSED/FROZEN ({@link
     * CanonicalBindingAnalysis#requiresPresenceCheckedCreation}). The RHS
     * completes first, then the creation applies the ordinary {@code :}
     * rules and establishes the value in the binding's one physical storage.
     * Returns {@code false} for NAME and MEMBER storage.
     */
    private boolean emitPresenceCheckedCreate(
            ProtosBytecodeRootNodeGen.Builder builder,
            CanonicalBindingIdentity identity,
            BytecodeLocal result,
            java.util.function.Consumer<BytecodeLocal> rhsToLocal) {
        BindingStorage storage = bindingStorageOrNull(identity);
        if (storage == null) {
            return false;
        }
        BytecodeLocal value = builder.createLocal("createValue", null);
        rhsToLocal.accept(value);
        beginMutationResult(builder, result);
        emitPresenceCheckedCreateOperation(
                builder, identity.name(), storage, () -> builder.emitLoadLocal(value));
        endMutationResult(builder, result);
        return true;
    }

    /* The checked creation operation of one binding; it yields the created value. */
    private static void emitPresenceCheckedCreateOperation(
            ProtosBytecodeRootNodeGen.Builder builder,
            String name,
            BindingStorage storage,
            Runnable value) {
        if (storage.cell() != null) {
            builder.beginCreateLexicalCell(name);
            builder.emitLoadLocal(storage.cell());
            value.run();
            builder.endCreateLexicalCell();
        } else {
            builder.beginCreateLexicalLocal(name, storage.local());
            value.run();
            builder.endCreateLexicalLocal();
        }
    }

    /* whether a target-less creation must apply the ':' presence rules. */
    private boolean requiresPresenceCheckedCreation(CanonicalCreate create) {
        return currentRootAnalysis != null
                && currentRootAnalysis.requiresPresenceCheckedCreation(create);
    }

    /** the static identity of a bare assignment, or {@code null}. */
    private CanonicalBindingIdentity assignmentIdentityOrNull(CanonicalAssign assign) {
        if (assign.target().isPresent() || currentRootAnalysis == null) {
            return null;
        }
        return staticIdentityOrNull(currentRootAnalysis.resolutionOf(assign).orElse(null));
    }

    /**
     * the physical storage of a lexical binding write: exactly one of
     * {@code cell} (a CELL binding's cell reference) and {@code local} (a
     * LOCAL binding's frame local) is non-null.
     */
    private record BindingStorage(BytecodeLocal cell, BytecodeLocal local) {
        static BindingStorage ofLocal(BytecodeLocal local) {
            return new BindingStorage(null, Objects.requireNonNull(local, "local"));
        }
    }

    /**
     * the single selection of the physical storage of {@code identity}
     * (through {@link #physicalStorageOf}, so a cell promoted by {@link
     * InlineStoragePlan} is a LOCAL), or {@code null} for NAME and MEMBER
     * storage.
     */
    private BindingStorage bindingStorageOrNull(CanonicalBindingIdentity identity) {
        if (identity != null && identity.owner() == currentNormalCallbackScalarScope) {
            BytecodeLocal local = currentNormalCallbackScalarLocals.get(identity.name());
            if (local == null) {
                throw new AssertionError("missing inline binding local: " + identity.name());
            }
            return BindingStorage.ofLocal(local);
        }
        BytecodeLocal cell = cellLocalOrNull(identity);
        if (cell != null) {
            return new BindingStorage(cell, null);
        }
        BytecodeLocal local = ownLocalOrNull(identity);
        return local == null ? null : BindingStorage.ofLocal(local);
    }

    /**
     * the RHS of a binding write. {@code direct} emits exactly one
     * value onto the operand stack and is used only when no resumption can
     * occur; {@code staged} evaluates the RHS to completion into a given
     * local, owning the continuation protocol when the RHS may suspend
     * ({@code suspendable}). {@code carrier}: the value may be a primitive
     * {@code long}/{@code double} carrier (LOCAL storage only).
     */
    private record BindingValue(
            Runnable direct,
            java.util.function.Consumer<BytecodeLocal> staged,
            boolean suspendable,
            boolean carrier) {
        /** An already evaluated, effect-free value producer. */
        static BindingValue synchronous(Runnable direct) {
            return new BindingValue(direct, null, false, false);
        }
    }

    /**
     * the RHS {@code value} of a creation or assignment. A Closure
     * literal's nested root and plan are registered now, before any
     * consuming store opens; only its materialization is deferred to the
     * producer. Whether the RHS may suspend is decided statically by {@link
     * #requiresContinuationScratch}.
     */
    private BindingValue expressionBindingValue(
            ProtosBytecodeRootNodeGen.Builder builder,
            CanonicalExpression value,
            boolean carrier,
            BytecodeLocal preparedCall,
            BytecodeLocal childResult,
            BytecodeLocal resumeValue) {
        if (value instanceof CanonicalClosure closure) {
            ProtosClosureExecutionPlanCell plan = bytecodeClosurePlan(builder, closure);
            return BindingValue.synchronous(() -> emitClosureCapture(builder, closure, plan));
        }
        if (requiresContinuationScratch(value)) {
            java.util.function.Consumer<BytecodeLocal> staged = carrier
                    ? target -> emitPrimitiveNumericOperandToLocal(
                            builder, value, target, preparedCall, childResult, resumeValue)
                    : target -> emitBodyExpressionToLocal(
                            builder, value, target, preparedCall, childResult, resumeValue);
            return new BindingValue(null, staged, true, carrier);
        }
        Runnable direct = carrier
                ? () -> emitPrimitiveNumericOperandValue(
                        builder, value, preparedCall, childResult, resumeValue)
                : () -> emitExpressionValue(
                        builder, value, preparedCall, childResult, resumeValue, false);
        return new BindingValue(direct, null, false, carrier);
    }

    /**
     * ({@code StoreVisitor}): the direct write of a classified
     * lexical binding by a creation or an assignment of RHS {@code value}.
     * Returns {@code false}, emitting nothing, for NAME and MEMBER storage.
     * A LOCAL binding may keep a carrier when {@code carrierPermitted}; a
     * CELL always receives a guest value.
     */
    private boolean emitStoreLexicalBinding(
            ProtosBytecodeRootNodeGen.Builder builder,
            CanonicalBindingIdentity identity,
            CanonicalExpression value,
            boolean carrierPermitted,
            BytecodeLocal result,
            BytecodeLocal preparedCall,
            BytecodeLocal childResult,
            BytecodeLocal resumeValue) {
        BindingStorage storage = bindingStorageOrNull(identity);
        if (storage == null) {
            return false;
        }
        boolean carrier =
                carrierPermitted
                        && storage.local() != null
                        && primitiveNumericCarrierValue(value);
        emitBindingWrite(
                builder, storage,
                expressionBindingValue(
                        builder, value, carrier, preparedCall, childResult, resumeValue),
                result);
        return true;
    }

    /**
     * the single implementation of a lexical binding write.
     * {@code result == null} means the value has no consumer, so nothing
     * beyond the RHS and the store is emitted.
     *
     * <p>LOCAL: a synchronous RHS is the direct operand of {@code
     * StoreLocal}; a suspendable one completes straight into the binding's
     * own local, with no intermediate temporary (the local is invisible to
     * any capture). A consumed value is read back once, materialized only
     * when it may be a carrier.
     *
     * <p>CELL: a synchronous RHS with no consumer is the direct operand of
     * {@code StoreCell}. Otherwise the RHS completes first, into the
     * consumer's {@code result} or, when discarded, into the one local its
     * continuation needs, and only then is {@code StoreCell} opened, so no
     * cell reference is pending on the operand stack across a suspension.
     */
    private static void emitBindingWrite(
            ProtosBytecodeRootNodeGen.Builder builder,
            BindingStorage storage,
            BindingValue value,
            BytecodeLocal result) {
        BytecodeLocal cell = storage.cell();
        if (cell != null) {
            if (!value.suspendable() && result == null) {
                builder.beginStoreCell();
                builder.emitLoadLocal(cell);
                value.direct().run();
                builder.endStoreCell();
                return;
            }
            BytecodeLocal completed =
                    result != null ? result : builder.createLocal("cellStoreValue", null);
            emitBindingValueToLocal(builder, value, completed);
            builder.beginStoreCell();
            builder.emitLoadLocal(cell);
            builder.emitLoadLocal(completed);
            builder.endStoreCell();
            return;
        }
        BytecodeLocal local = storage.local();
        emitBindingValueToLocal(builder, value, local);
        if (result == null) {
            return;
        }
        builder.beginStoreLocal(result);
        builder.emitLoadLocal(local);
        builder.endStoreLocal();
    }

    private static void emitBindingValueToLocal(
            ProtosBytecodeRootNodeGen.Builder builder,
            BindingValue value,
            BytecodeLocal target) {
        if (value.suspendable()) {
            value.staged().accept(target);
            return;
        }
        builder.beginStoreLocal(target);
        value.direct().run();
        builder.endStoreLocal();
    }


    /**
     * the fixed destination of a bare assignment statically resolved
     * to a module (ROOT) NAME binding at physical depth {@code moduleDepth}
     * ({@code 0}: the running module root's own context), selected before
     * the RHS evaluates (WriteGlobal). It is stored in a fresh
     * local consumed by {@link #emitModuleAssign}.
     */
    private BytecodeLocal emitSelectModuleWriteTarget(
            ProtosBytecodeRootNodeGen.Builder builder,
            CanonicalBindingIdentity identity,
            int moduleDepth) {
        BytecodeLocal destination = builder.createLocal("assignModuleTarget", null);
        builder.beginStoreLocal(destination);
        builder.emitSelectWriteGlobal(
                TruffleString.fromJavaStringUncached(
                        identity.name(), TruffleString.Encoding.UTF_16),
                moduleDepth);
        builder.endStoreLocal();
        return destination;
    }

    /* assigns the evaluated RHS through a selected module destination. */
    private static void emitModuleAssign(
            ProtosBytecodeRootNodeGen.Builder builder,
            BytecodeLocal destination,
            BytecodeLocal value,
            BytecodeLocal result) {
        beginMutationResult(builder, result);
        builder.beginWriteGlobal();
        builder.emitLoadLocal(destination);
        builder.emitLoadLocal(value);
        builder.endWriteGlobal();
        endMutationResult(builder, result);
    }

    /* The NAME destination is selected before the RHS evaluates. */
    private void emitResolveNamedWriteTarget(
            ProtosBytecodeRootNodeGen.Builder builder,
            CanonicalAssign assign,
            BytecodeLocal mutationTarget) {
        builder.beginStoreLocal(mutationTarget);
        builder.beginAccessNamedLexical(ProtosNamedLexicalAccess.RESOLVE_WRITE);
        emitCurrentActivation(builder);
        builder.emitLoadConstant(assign.name());
        builder.endAccessNamedLexical();
        builder.endStoreLocal();
    }

    private void emitNamedOrMemberAssign(
            ProtosBytecodeRootNodeGen.Builder builder,
            CanonicalAssign assign,
            BytecodeLocal mutationTarget,
            BytecodeLocal value,
            BytecodeLocal result) {
        beginMutationResult(builder, result);
        builder.beginMutateLocalSlot(ProtosSlotMutation.ASSIGN_RESOLVED);
        emitCurrentActivation(builder);
        builder.emitLoadLocal(mutationTarget);
        builder.emitLoadConstant(assign.name());
        builder.emitLoadLocal(value);
        builder.endMutateLocalSlot();
        endMutationResult(builder, result);
    }

    /**
     * opens the consumer of a mutation operation's value: {@code
     * result} when the value is consumed, or {@code DiscardValue} when the
     * mutation has no consumer ({@code result == null}).
     */
    private static void beginMutationResult(
            ProtosBytecodeRootNodeGen.Builder builder,
            BytecodeLocal result) {
        if (result == null) {
            builder.beginDiscardValue();
        } else {
            builder.beginStoreLocal(result);
        }
    }

    private static void endMutationResult(
            ProtosBytecodeRootNodeGen.Builder builder,
            BytecodeLocal result) {
        if (result == null) {
            builder.endDiscardValue();
        } else {
            builder.endStoreLocal();
        }
    }

    /**
     * Evaluate receiver, index and RHS once into continuation locals, then select
     * atPut. Standard indexed behavior executes internally; other selections use
     * the shared dispatcher. Indexed-assignment syntax returns the prepared RHS
     * regardless of the atPut result (VALUES_AND_COLLECTIONS, Indexed Access).
     */
    private void emitIndexedWrite(
            ProtosBytecodeRootNodeGen.Builder builder,
            CanonicalIndexedAssign assign,
            BytecodeLocal result,
            BytecodeLocal preparedCall,
            BytecodeLocal childResult,
            BytecodeLocal resumeValue,
            OperandStager stager) {
        if (preparedCall == null || childResult == null || resumeValue == null) {
            throw new AssertionError(
                    "Indexed assignment scratch locals were not allocated");
        }
        builder.beginSourceSection(
                assign.span().startOffset(), assign.span().length());
        builder.beginBlock();

        BytecodeLocal receiver =
                builder.createLocal("indexedAssignReceiver", null);
        BytecodeLocal index =
                builder.createLocal("indexedAssignIndex", null);
        BytecodeLocal value =
                builder.createLocal("indexedAssignValue", null);

        stageOperand(
                builder, assign.receiver(), receiver,
                preparedCall, childResult, resumeValue, stager);
        stageOperand(
                builder, assign.index(), index,
                preparedCall, childResult, resumeValue, stager);
        stageOperand(
                builder, assign.value(), value,
                preparedCall, childResult, resumeValue, stager);

        emitIndexedProtocolOfLocals(
                builder, "atPut", receiver, java.util.List.of(index, value),
                null, childResult, resumeValue, false);

        if (result != null) {
            builder.beginStoreLocal(result);
            builder.emitLoadLocal(value);
            builder.endStoreLocal();
        }

        builder.endBlock();
        builder.endSourceSection();
    }

    private void emitBodyIndexedAssign(
            ProtosBytecodeRootNodeGen.Builder builder,
            CanonicalIndexedAssign indexedAssign,
            BytecodeLocal result,
            BytecodeLocal preparedCall,
            BytecodeLocal childResult,
            BytecodeLocal resumeValue) {
        emitIndexedWrite(
                builder, indexedAssign, result,
                preparedCall, childResult, resumeValue,
                this::emitBodyExpressionToLocal);
    }

    private void emitDefaultCreate(
            ProtosBytecodeRootNodeGen.Builder builder,
            CanonicalCreate create,
            BytecodeLocal result,
            BytecodeLocal preparedCall,
            BytecodeLocal childResult,
            BytecodeLocal resumeValue) {
        if (create.target().isPresent()) {
            emitAttributeWrite(
                    builder, create.name(), create.target().orElseThrow(),
                    create.value(), true, true, result,
                    preparedCall, childResult, resumeValue);
            return;
        }
        if (create.target().isEmpty()) {
            CanonicalBindingIdentity identity =
                    currentRootAnalysis == null
                            ? null
                            : currentRootAnalysis.identityOf(create).orElse(null);
            if (identity != null
                    && requiresPresenceCheckedCreation(create)
                    && emitPresenceCheckedCreate(
                            builder, identity, result,
                            target -> emitDefaultExpressionToLocal(
                                    builder, create.value(), target,
                                    preparedCall, childResult, resumeValue))) {
                return;
            }
            BindingStorage storage =
                    identity == null ? null : bindingStorageOrNull(identity);
            if (storage != null) {
                emitBindingWrite(
                        builder, storage,
                        defaultBindingValue(
                                builder, create.value(), preparedCall, childResult, resumeValue),
                        result);
                return;
            }
        }
        BytecodeLocal value = builder.createLocal("defaultCreateValue", null);
        if (create.target().isEmpty()) {
            emitDefaultExpressionToLocal(
                    builder, create.value(), value, preparedCall, childResult, resumeValue);
            emitCreateCurrentBinding(builder, create, value, result);
            return;
        }

    }

    /**
     * a parameter default RHS as a binding value. It keeps the
     * default-expression lowering, which completes into a local, so it is
     * staged: it completes straight into the LOCAL binding, or into the
     * consumer's result or the cell-store local before {@code StoreCell}.
     */
    private BindingValue defaultBindingValue(
            ProtosBytecodeRootNodeGen.Builder builder,
            CanonicalExpression value,
            BytecodeLocal preparedCall,
            BytecodeLocal childResult,
            BytecodeLocal resumeValue) {
        return new BindingValue(
                null,
                target -> emitDefaultExpressionToLocal(
                        builder, value, target, preparedCall, childResult, resumeValue),
                true,
                false);
    }

    private void emitDefaultMultipleCreate(
            ProtosBytecodeRootNodeGen.Builder builder,
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

        emitMultipleCreateCurrentBindings(
                builder,
                create.names(),
                currentRootAnalysis == null
                        ? null
                        : currentRootAnalysis.identitiesOf(create).orElse(null),
                currentRootAnalysis != null
                        && currentRootAnalysis.requiresPresenceCheckedCreation(create),
                source,
                result);
    }

    /*
     * the parameter default-expression counterpart of
     * emitResolvedBodyAssign, with the same destination selection before the
     * RHS ().
     */
    private void emitDefaultAssign(
            ProtosBytecodeRootNodeGen.Builder builder,
            CanonicalAssign assign,
            BytecodeLocal result,
            BytecodeLocal preparedCall,
            BytecodeLocal childResult,
            BytecodeLocal resumeValue) {
        if (assign.target().isPresent()) {
            emitAttributeWrite(
                    builder, assign.name(), assign.target().orElseThrow(),
                    assign.value(), false, true, result,
                    preparedCall, childResult, resumeValue);
            return;
        }
        CanonicalBindingIdentity identity = assignmentIdentityOrNull(assign);
        CanonicalBindingAnalysis.LexicalAccess access = assignmentAccess(assign, identity);
        if (identity != null
                && access != CanonicalBindingAnalysis.LexicalAccess.DIRECT
                && emitSelectedLexicalAssign(
                        builder, assign, identity, access, result,
                        target -> emitDefaultExpressionToLocal(
                                builder, assign.value(), target,
                                preparedCall, childResult, resumeValue))) {
            return;
        }
        BindingStorage storage =
                identity == null || access != CanonicalBindingAnalysis.LexicalAccess.DIRECT
                        ? null
                        : bindingStorageOrNull(identity);
        if (storage != null) {
            emitBindingWrite(
                    builder, storage,
                    defaultBindingValue(
                            builder, assign.value(), preparedCall, childResult, resumeValue),
                    result);
            return;
        }
        BytecodeLocal value = builder.createLocal("defaultAssignValue", null);
        if (identity != null && access != CanonicalBindingAnalysis.LexicalAccess.SELECTED) {
            CanonicalBindingResolution resolution =
                    currentRootAnalysis.resolutionOf(assign).orElse(null);
            if (isStaticModuleBinding(resolution, identity)) {
                BytecodeLocal destination = emitSelectModuleWriteTarget(
                        builder, identity, physicalModuleDepth(resolution));
                emitDefaultExpressionToLocal(
                        builder, assign.value(), value, preparedCall, childResult, resumeValue);
                emitModuleAssign(builder, destination, value, result);
                return;
            }
        }

        BytecodeLocal mutationTarget = builder.createLocal("defaultAssignMutationTarget", null);
        emitResolveNamedWriteTarget(builder, assign, mutationTarget);
        emitDefaultExpressionToLocal(
                builder, assign.value(), value, preparedCall, childResult, resumeValue);
        emitNamedOrMemberAssign(builder, assign, mutationTarget, value, result);
    }

    private void emitDefaultIndexedAssign(
            ProtosBytecodeRootNodeGen.Builder builder,
            CanonicalIndexedAssign indexedAssign,
            BytecodeLocal result,
            BytecodeLocal preparedCall,
            BytecodeLocal childResult,
            BytecodeLocal resumeValue) {
        emitIndexedWrite(
                builder, indexedAssign, result,
                preparedCall, childResult, resumeValue,
                this::emitDefaultExpressionToLocal);
    }

    private void emitComposedSuperSend(
            ProtosBytecodeRootNodeGen.Builder builder,
            CanonicalSuperSend send,
            BytecodeLocal result,
            BytecodeLocal scratch,
            BytecodeLocal childResult,
            BytecodeLocal resumeValue) {
        emitDispatchedInvocation(
                builder, InvocationShape.SUPER_SEND, null, send.message(), send.arguments(),
                result, scratch, childResult, resumeValue,
                this::emitBodyExpressionToLocal, this::emitBodySpreadArgumentVector, false);
    }


    private void emitComposedDefaultSuperSend(
            ProtosBytecodeRootNodeGen.Builder builder,
            CanonicalSuperSend send,
            BytecodeLocal result,
            BytecodeLocal scratch,
            BytecodeLocal childResult,
            BytecodeLocal resumeValue) {
        builder.beginSourceSection(send.span().startOffset(), send.span().length());
        emitDispatchedInvocation(
                builder, InvocationShape.SUPER_SEND, null, send.message(), send.arguments(),
                result, scratch, childResult, resumeValue,
                this::emitDefaultExpressionToLocal, this::emitDefaultSpreadArgumentVector, false);
        builder.endSourceSection();
    }


    private void emitComposedDefaultCall(
            ProtosBytecodeRootNodeGen.Builder builder,
            CanonicalCall call,
            BytecodeLocal result,
            BytecodeLocal scratch,
            BytecodeLocal childResult,
            BytecodeLocal resumeValue) {
        builder.beginSourceSection(call.span().startOffset(), call.span().length());
        emitDispatchedInvocation(
                builder, InvocationShape.CALL, call.receiver(), null, call.arguments(),
                result, scratch, childResult, resumeValue,
                this::emitDefaultExpressionToLocal, this::emitDefaultSpreadArgumentVector, false);
        builder.endSourceSection();
    }


    private void emitComposedDefaultSend(
            ProtosBytecodeRootNodeGen.Builder builder,
            CanonicalSend send,
            BytecodeLocal result,
            BytecodeLocal scratch,
            BytecodeLocal childResult,
            BytecodeLocal resumeValue) {
        if (send.contextual() && !hasNamedArgument(send.arguments())) {
            emitContextualControl(
                    builder, send, result,
                    scratch, childResult, resumeValue, false);
            return;
        }
        if (indexedProtocolSend(send) || isIndexedSizeSend(send)) {
            emitIndexedSend(
                    builder, send, result, scratch, childResult, resumeValue,
                    this::emitDefaultExpressionToLocal, false);
            return;
        }

        builder.beginSourceSection(send.span().startOffset(), send.span().length());
        emitDispatchedInvocation(
                builder, InvocationShape.SEND, send.receiver(), send.message(), send.arguments(),
                result, scratch, childResult, resumeValue,
                this::emitDefaultExpressionToLocal, this::emitDefaultSpreadArgumentVector, false);
        builder.endSourceSection();
    }


    /** how an invocation operand is staged in the current lowering context. */
    @FunctionalInterface
    private interface OperandStager {
        void stage(
                ProtosBytecodeRootNodeGen.Builder builder,
                CanonicalExpression expression,
                BytecodeLocal target,
                BytecodeLocal scratch,
                BytecodeLocal childResult,
                BytecodeLocal resumeValue);
    }

    /** how a spread argument vector is staged in the current lowering context. */
    @FunctionalInterface
    private interface SpreadStager {
        void stage(
                ProtosBytecodeRootNodeGen.Builder builder,
                java.util.List<CanonicalExpression> arguments,
                BytecodeLocal vector,
                BytecodeLocal scratch,
                BytecodeLocal childResult,
                BytecodeLocal resumeValue);
    }

    /** the three invocation shapes of the canonical AST. */
    private enum InvocationShape { CALL, SEND, SUPER_SEND }

    /**
     * (call lowering): the single lowering of every ordinary
     * call, send and super send.
     *
     * <ol>
     *   <li>CALL: evaluate the callee expression once.
     *   <li>SEND: evaluate the receiver once and retain it.
     *   <li>SUPER_SEND: retain the current lexical methodHome.
     *   <li>Evaluate all arguments left to right, constructing the complete
     *       positional or named argument packet.
     *   <li>Only after argument evaluation, SEND uses {@code GetMethod} to
     *       select a bound method on the retained receiver, and SUPER_SEND
     *       uses {@code GetSuperMethod} to select from the parent of methodHome.
     *       CALL uses its previously evaluated callee.
     *   <li>{@code Call*Method} by arity (0..4, otherwise or with spread
     *       {@code CallVarargsMethod}) enters the shared dispatcher.
     *   <li>A pending result is composed: {@code IsResultKind(CONTINUATION)
     *       -> Yield -> ResumeDispatchedContinuation}.
     * </ol>
     * Dynamic dispatch keeps its {@link StandardTags.CallTag}; statically
     * eliminated or selected controls do not create a synthetic call tag.
     */
    private void emitDispatchedInvocation(
            ProtosBytecodeRootNodeGen.Builder builder,
            InvocationShape shape,
            CanonicalExpression receiver,
            String selector,
            java.util.List<CanonicalExpression> arguments,
            BytecodeLocal result,
            BytecodeLocal scratch,
            BytecodeLocal childResult,
            BytecodeLocal resumeValue,
            OperandStager stager,
            SpreadStager spreadStager,
            boolean primitiveCarrierResult) {
        if (i115AdmitsDirectUnaryInvocation(shape, receiver, selector, arguments)) {
            if (result == null) {
                builder.beginDiscardValue();
            } else {
                builder.beginStoreLocal(result);
            }

            emitComposedDirectUnaryValue(
                    builder,
                    () -> emitI115DirectUnaryOperation(
                            builder, shape, receiver, selector,
                            arguments.get(0), true, false),
                    childResult, resumeValue, primitiveCarrierResult);

            if (result == null) {
                builder.endDiscardValue();
            } else {
                builder.endStoreLocal();
            }
            return;
        }

        boolean namedInvocation = hasNamedArgument(arguments);
        ControlSelector control =
                shape == InvocationShape.SEND && !namedInvocation
                        ? ControlSelector.of(selector, arguments)
                        : null;
        StaticControlSelection staticControl =
                classifyStaticControlSelection(control, receiver, arguments);
        if (staticControl != null) {
            CanonicalSend discarded = discardedControlStatement;
            boolean matchesDiscarded =
                    discarded != null
                            && discarded.receiver() == receiver
                            && discarded.arguments() == arguments;
            if (matchesDiscarded) {
                discardedControlStatement = null;
            }
            boolean valueUsed = result != null && !matchesDiscarded;
            emitControl(
                    builder, control, receiver, arguments, valueUsed,
                    result, scratch, childResult, resumeValue,
                    stager, primitiveCarrierResult);
            return;
        }

        if (result == null || scratch == null || childResult == null || resumeValue == null) {
            throw new AssertionError("Bytecode invocation result/scratch locals were not allocated");
        }
        if (shape == InvocationShape.CALL
                && arguments.isEmpty()
                && admitsInlineControlClosure(receiver)) {
            builder.beginTag(StandardTags.CallTag.class);
            builder.beginBlock();
            emitInlineControlClosureBody(
                    builder, (CanonicalClosure) receiver, result,
                    scratch, childResult, resumeValue);
            builder.endBlock();
            builder.endTag(StandardTags.CallTag.class);
            return;
        }
        if (control != null) {
            CanonicalSend discarded = discardedControlStatement;
            discardedControlStatement = null;
            boolean valueUsed = discarded == null
                    || discarded.receiver() != receiver
                    || discarded.arguments() != arguments;
            emitControl(
                    builder, control, receiver, arguments, valueUsed,
                    result, scratch, childResult, resumeValue,
                    stager, primitiveCarrierResult);
            return;
        }
        NormalBooleanControl booleanControl =
                shape == InvocationShape.SEND && !namedInvocation
                        ? nativeBooleanControl(selector, arguments)
                        : null;
        if (booleanControl != null) {
            emitBooleanControl(
                    builder, booleanControl, receiver, selector, arguments,
                    result, scratch, childResult, resumeValue, stager);
            return;
        }

        builder.beginTag(StandardTags.CallTag.class);
        builder.beginBlock();

        BytecodeLocal callable = builder.createLocal("invocationCallable", null);
        BytecodeLocal receiverValue = null;

        switch (shape) {
            case CALL ->
                    stageOperand(
                            builder, receiver, callable,
                            scratch, childResult, resumeValue, stager);

            case SEND -> {
                receiverValue = builder.createLocal("invocationReceiver", null);
                stageOperand(
                        builder, receiver, receiverValue,
                        scratch, childResult, resumeValue, stager);

            }

            case SUPER_SEND -> {
                // Selected after the argument packet (D237), below.
            }
        }

        BytecodeLocal suppliedVector = null;
        java.util.List<BytecodeLocal> argumentValues = new java.util.ArrayList<>(arguments.size());
        if (hasVectorArgument(arguments)) {
            suppliedVector = builder.createLocal("suppliedArgumentVector", null);
            spreadStager.stage(builder, arguments, suppliedVector, scratch, childResult, resumeValue);
        } else {
            for (CanonicalExpression argument : arguments) {
                BytecodeLocal argumentValue = builder.createLocal("invocationArgument", null);
                stageOperand(builder, argument, argumentValue, scratch, childResult, resumeValue, stager);
                argumentValues.add(argumentValue);
            }
        }

        if (namedInvocation) {
            // D237: selected implementation must reflect every effect
            // of argument evaluation before name association begins.
            if (shape == InvocationShape.SEND) {
                emitGetMethod(builder, selector, receiverValue, callable);
            } else if (shape == InvocationShape.SUPER_SEND) {
                builder.beginStoreLocal(callable);
                builder.emitGetSuperMethod(selector);
                builder.endStoreLocal();
            }

            emitDispatchedNamedCall(
                    builder,
                    callable,
                    suppliedVector,
                    childResult,
                    result,
                    resumeValue,
                    primitiveCarrierResult);
        } else {
            // D237: the behavior is selected once the argument packet is formed.
            if (shape == InvocationShape.SEND) {
                emitGetMethod(builder, selector, receiverValue, callable);
            } else if (shape == InvocationShape.SUPER_SEND) {
                builder.beginStoreLocal(callable);
                builder.emitGetSuperMethod(selector);
                builder.endStoreLocal();
            }
            emitDispatchedCall(
                    builder, callable, argumentValues, suppliedVector,
                    childResult, result, resumeValue,
                    primitiveCarrierResult);
        }

        builder.endBlock();
        builder.endTag(StandardTags.CallTag.class);
    }


    /**
     * the standard control selectors are Bytecode DSL control flow,
     * not method sends. One compile-time row per selector fixes its arity,
     * its phase, the polarity of the primitive decision and, for loops, the
     * operand positions of the condition and of the body (0 is the receiver,
     * 1 the sole argument). The roles are never inferred from Closure
     * identity, parameters or results.
     *
     * <p>A conditional's decision is {@code receiver == polarity}: ifFalse
     * selects its callback on canonical false without a {@code not()} send.
     * A loop's decision is "continue": whileTrue and untilFalse continue on
     * true, whileFalse and untilTrue on false.
     */
    private enum ControlSelector {
        IF_TRUE("ifTrue", 1, Phase.CONDITIONAL, true, -1, -1),
        IF_FALSE("ifFalse", 1, Phase.CONDITIONAL, false, -1, -1),
        IF_TRUE_IF_FALSE("ifTrueIfFalse", 2, Phase.CONDITIONAL, true, -1, -1),
        WHILE_TRUE("whileTrue", 1, Phase.PRE_TEST, true, 0, 1),
        WHILE_FALSE("whileFalse", 1, Phase.PRE_TEST, false, 0, 1),
        UNTIL_TRUE("untilTrue", 1, Phase.POST_TEST, false, 1, 0),
        UNTIL_FALSE("untilFalse", 1, Phase.POST_TEST, true, 1, 0);

        private enum Phase { CONDITIONAL, PRE_TEST, POST_TEST }

        private final String selector;
        private final int arity;
        private final Phase phase;
        private final boolean polarity;
        private final int conditionPosition;
        private final int bodyPosition;

        ControlSelector(
                String selector,
                int arity,
                Phase phase,
                boolean polarity,
                int conditionPosition,
                int bodyPosition) {
            this.selector = selector;
            this.arity = arity;
            this.phase = phase;
            this.polarity = polarity;
            this.conditionPosition = conditionPosition;
            this.bodyPosition = bodyPosition;
        }

        /** The row of a structurally recognized control send, or {@code null}. */
        static ControlSelector of(
                String selector, java.util.List<CanonicalExpression> arguments) {
            if (selector == null || hasVectorArgument(arguments)) {
                return null;
            }
            for (ControlSelector control : values()) {
                if (control.selector.equals(selector)
                        && control.arity == arguments.size()) {
                    return control;
                }
            }
            return null;
        }

        boolean loop() {
            return phase != Phase.CONDITIONAL;
        }

        /** Whether operand {@code position} is a callback the control invokes. */
        boolean callbackPosition(int position) {
            return loop() || position > 0;
        }
    }

    /** A statically decidable control; zero means that no callback executes. */
    private record StaticControlSelection(ControlSelector control, int selectedPosition) {
        boolean eliminable() {
            return selectedPosition == 0;
        }
    }

    private static Boolean literalClosureBoolean(CanonicalExpression expression) {
        if (!(expression instanceof CanonicalClosure closure)
                || !closure.parameters().isEmpty()
                || closure.body().expressions().size() != 1) {
            return null;
        }
        return canonicalBooleanLiteral(closure.body().expressions().get(0));
    }

    private StaticControlSelection classifyStaticControlSelection(
            ControlSelector control,
            CanonicalExpression receiver,
            java.util.List<CanonicalExpression> arguments) {
        return classifyStaticControlSelection(
                control, receiver, arguments, currentControlSiteContext());
    }

    /** The static classification of a control site under the given admission context. */
    private StaticControlSelection classifyStaticControlSelection(
            ControlSelector control,
            CanonicalExpression receiver,
            java.util.List<CanonicalExpression> arguments,
            ControlSiteContext context) {
        if (control == null) {
            return null;
        }

        if (control.phase == ControlSelector.Phase.PRE_TEST) {
            Boolean condition = literalClosureBoolean(receiver);
            if (condition != null && condition != control.polarity
                    && arguments.get(0) instanceof CanonicalClosure) {
                return new StaticControlSelection(control, 0);
            }
            return null;
        }

        if (control.phase != ControlSelector.Phase.CONDITIONAL) {
            return null;
        }

        Boolean condition = canonicalBooleanLiteral(receiver);
        if (condition == null) {
            return null;
        }

        int selected = control.arity == 2
                ? (condition ? 1 : 2)
                : (condition == control.polarity ? 1 : 0);

        if (selected == 0) {
            return arguments.get(0) instanceof CanonicalClosure
                    ? new StaticControlSelection(control, 0) : null;
        }

        // Unselected arguments must also be literals: otherwise their
        // evaluation could have effects before the selected callback.
        for (CanonicalExpression argument : arguments) {
            if (!(argument instanceof CanonicalClosure)) {
                return null;
            }
        }

        return admitsInlineControlClosure(arguments.get(selected - 1), context)
                ? new StaticControlSelection(control, selected) : null;
    }

    private StaticControlSelection classifyStaticControlSelection(CanonicalExpression expression) {
        if (!(expression instanceof CanonicalSend send)) {
            return null;
        }
        return classifyStaticControlSelection(
                ControlSelector.of(send.message(), send.arguments()),
                send.receiver(), send.arguments());
    }

    private boolean isI115EliminableControl(CanonicalExpression expression) {
        StaticControlSelection control = classifyStaticControlSelection(expression);
        return control != null && control.eliminable();
    }

    /**
     * the control send whose value its enclosing sequence discards, or
     * {@code null}. Set only around the emission of that statement and
     * consumed (by operand identity) on entry of {@link #emitControl},
     * so a nested control expression never inherits it.
     */
    private CanonicalSend discardedControlStatement;

    /**
     * Emits one composed invocation whose value is discarded into the
     * continuation destination {@code target}, marking a control send as
     * discarded so it does not store its result.
     */
    private void emitDiscardedStatementToLocal(
            ProtosBytecodeRootNodeGen.Builder builder,
            CanonicalExpression expression,
            BytecodeLocal target,
            BytecodeLocal scratch,
            BytecodeLocal childResult,
            BytecodeLocal resumeValue) {
        if (isI115EliminableControl(expression)) {
            return;
        }
        CanonicalSend saved = discardedControlStatement;
        discardedControlStatement =
                expression instanceof CanonicalSend send ? send : null;
        try {
            emitBodyExpressionToLocal(
                    builder, expression, target, scratch, childResult, resumeValue);
        } finally {
            discardedControlStatement = saved;
        }
    }

    /**
     * the storage a discarded composed invocation still needs to
     * complete (its continuation protocol writes a result). Created lazily,
     * only when such an invocation occurs, and never the consumer's result.
     * Mutations (creations and assignments) never use it: their emitters
     * accept a {@code null} result.
     */
    private static final class DiscardSink {
        private BytecodeLocal local;

        BytecodeLocal get(ProtosBytecodeRootNodeGen.Builder builder) {
            if (local == null) {
                local = builder.createLocal("discardedResult", null);
            }
            return local;
        }
    }

    /**
     * emits {@code expression} leaving exactly one value on the
     * Bytecode DSL stack. Direct numeric operations and simple expressions
     * produce it directly; an invocation requiring composition completes
     * through its continuation storage, followed by a single load.
     */
    private void emitExpressionValue(
            ProtosBytecodeRootNodeGen.Builder builder,
            CanonicalExpression expression,
            BytecodeLocal scratch,
            BytecodeLocal childResult,
            BytecodeLocal resumeValue,
            boolean primitiveCarrierResult) {
        if (isI115EliminableControl(expression)) {
            builder.emitLoadConstant(ProtosNullValue.INSTANCE);
            return;
        }
        if (i115DirectUnaryCall(expression)) {
            emitComposedDirectUnaryValue(
                    builder,
                    () -> emitI115DirectUnaryValue(builder, expression, true),
                    childResult, resumeValue, primitiveCarrierResult);
            return;
        }
        if (expression instanceof CanonicalSend send
                && primitiveNumericSendCandidate(send)) {
            builder.beginSourceSection(send.span().startOffset(), send.span().length());
            emitDirectNumericSendValue(
                    builder, send, scratch, childResult, resumeValue, primitiveCarrierResult);
            builder.endSourceSection();
            return;
        }
        if (expression instanceof CanonicalLookup lookup && isScalarLocalRead(lookup)) {
            builder.emitLoadLocal(currentRootFrameLocals.get(lookup.name()));
            return;
        }
        if (!requiresComposedInvocation(expression)) {
            emitExpression(builder, expression);
            return;
        }
        BytecodeLocal value = builder.createLocal("expressionValue", null);
        builder.beginBlock();
        emitBodyExpressionToLocal(builder, expression, value, scratch, childResult, resumeValue);
        builder.emitLoadLocal(value);
        builder.endBlock();
    }

    /**
     * emit an expression for its effects only. Static controls can
     * disappear entirely or execute the selected inline callback directly.
     * Direct numeric values are discarded; mutations keep no result;
     * remaining composed invocations use a lazily created discard sink.
     */
    private void emitExpressionStatement(
            ProtosBytecodeRootNodeGen.Builder builder,
            CanonicalExpression expression,
            DiscardSink sink,
            BytecodeLocal scratch,
            BytecodeLocal childResult,
            BytecodeLocal resumeValue) {
        if (expression instanceof CanonicalSend contextual
                && contextual.contextual()) {
            emitContextualControl(
                    builder, contextual, null,
                    scratch, childResult, resumeValue, false);
            return;
        }

        StaticControlSelection staticControl = classifyStaticControlSelection(expression);
        if (staticControl != null) {
            if (!staticControl.eliminable()) {
                CanonicalSend send = (CanonicalSend) expression;
                emitControl(
                        builder, staticControl.control(),
                        send.receiver(), send.arguments(),
                        false, null, scratch, childResult, resumeValue,
                        this::emitBodyExpressionToLocal, false);
            }
            return;
        }
        if (i115DirectUnaryCall(expression)) {
            builder.beginDiscardValue();
            emitComposedDirectUnaryValue(
                    builder,
                    () -> emitI115DirectUnaryValue(builder, expression, true),
                    childResult, resumeValue, true);
            builder.endDiscardValue();
            return;
        }
        if (expression instanceof CanonicalSend send
                && primitiveNumericSendCandidate(send)) {
            builder.beginDiscardValue();
            emitDirectNumericOperation(builder, send, scratch, childResult, resumeValue);
            builder.endDiscardValue();
            return;
        }
        if (expression instanceof CanonicalLookup lookup && isScalarLocalRead(lookup)) {
            emitDiscardedScalarLocalRead(builder, lookup);
            return;
        }
        if (expression instanceof CanonicalClosure closure) {
            // Register the nested root before opening DiscardValue.
            ProtosClosureExecutionPlanCell plan = bytecodeClosurePlan(builder, closure);
            builder.beginDiscardValue();
            emitClosureCapture(builder, closure, plan);
            builder.endDiscardValue();
            return;
        }
        // a discarded mutation has no consumer; its emitter receives a
        // null result and writes only the binding or slot it mutates.
        if (expression instanceof CanonicalCreate create) {
            emitBodyCreate(builder, create, null, scratch, childResult, resumeValue);
            return;
        }
        if (expression instanceof CanonicalMultipleCreate create) {
            emitBodyMultipleCreate(builder, create, null, scratch, childResult, resumeValue);
            return;
        }
        if (expression instanceof CanonicalAssign assign) {
            emitBodyAssign(builder, assign, null, scratch, childResult, resumeValue);
            return;
        }
        if (expression instanceof CanonicalIndexedAssign indexedAssign) {
            emitBodyIndexedAssign(
                    builder, indexedAssign, null, scratch, childResult, resumeValue);
            return;
        }
        if (!requiresComposedInvocation(expression)) {
            builder.beginDiscardValue();
            emitExpression(builder, expression);
            builder.endDiscardValue();
            return;
        }
        emitDiscardedStatementToLocal(
                builder, expression, sink.get(builder), scratch, childResult, resumeValue);
    }

    /** how an inline Closure body delivers its last expression. */
    private enum InlineBodyResult { STACK, STORE, DISCARD }

    /**
     * an immediately invoked Closure literal (a control callback or an
     * ordinary Call0) whose activation I095 proved unobservable is emitted
     * in the caller, with its own bindings in plain locals of this root.
     * Nested invocations and statement tags reuse the ordinary body
     * expression lowerer.
     */
    private void emitInlineControlClosureBody(
            ProtosBytecodeRootNodeGen.Builder builder,
            CanonicalClosure closure,
            BytecodeLocal result,
            BytecodeLocal scratch,
            BytecodeLocal childResult,
            BytecodeLocal resumeValue) {
        emitInlineControlClosureBody(builder, closure, InlineBodyResult.STORE, result,
                scratch, childResult, resumeValue, false);
    }

    /**
     * The inline body in one of three forms. {@code STACK} leaves the
     * last expression's value on the stack (a condition or a Conditional
     * branch); {@code STORE} stores it once into {@code result};
     * {@code DISCARD} discards it. Earlier expressions are statements.
     */
    private void emitInlineControlClosureBody(
            ProtosBytecodeRootNodeGen.Builder builder,
            CanonicalClosure closure,
            InlineBodyResult mode,
            BytecodeLocal result,
            BytecodeLocal scratch,
            BytecodeLocal childResult,
            BytecodeLocal resumeValue,
            boolean primitiveCarrierResult) {
        emitInlineControlClosureBody(builder, closure, mode, result,
                scratch, childResult, resumeValue, primitiveCarrierResult,
                java.util.Map.of());
    }

    /**
     * The inline body whose parameters are already bound: {@code
     * boundParameters} maps each parameter name to the local its caller
     * stored the supplied value into before this body. Those locals are
     * PRESENT on entry and are not reset; every other own binding is.
     */
    private void emitInlineControlClosureBody(
            ProtosBytecodeRootNodeGen.Builder builder,
            CanonicalClosure closure,
            InlineBodyResult mode,
            BytecodeLocal result,
            BytecodeLocal scratch,
            BytecodeLocal childResult,
            BytecodeLocal resumeValue,
            boolean primitiveCarrierResult,
            java.util.Map<String, BytecodeLocal> boundParameters) {
        /*
         * The inline body keeps the RootTag of the Closure activation it
         * replaces, so instruments and the debugger still see that guest
         * root, and project its scope, inside the physical parent. The tag
         * covers exactly the source of that Closure literal.
         */
        builder.beginSourceSection(closure.span().startOffset(), closure.span().length());
        builder.beginTag(StandardTags.RootTag.class);
        int enclosingRegion = currentInlineCallbackRegion;
        currentInlineCallbackRegion = ++nextInlineCallbackRegion;
        builder.beginBlock();
        CanonicalLexicalScope ownScope =
                compilationFacts.isolatedClosureAnalysis(closure).scopeOf(closure).orElse(null);
        if (ownScope != null && ownScope.observesContext()) {
            /*
             * The activation an invocation materializes for its context must
             * stop viewing the region locals when the invocation ends, by
             * any exit, before a later invocation reuses them.
             */
            BytecodeLocal cache = builder.createLocal("inlineCallbackActivation", null);
            BytecodeLocal value = mode == InlineBodyResult.STACK
                    ? builder.createLocal("inlineCallbackValue", null)
                    : null;
            builder.beginTryFinally(() -> builder.emitDetachInlineCallbackActivation(cache));
            builder.beginBlock();
            pendingInlineContextCache = cache;
            emitInlineClosureBodyContent(builder, closure,
                    value != null ? InlineBodyResult.STORE : mode,
                    value != null ? value : result,
                    scratch, childResult, resumeValue, primitiveCarrierResult,
                    boundParameters);
            builder.endBlock();
            builder.endTryFinally();
            if (value != null) {
                builder.emitLoadLocal(value);
            }
        } else {
            emitInlineClosureBodyContent(builder, closure, mode, result,
                    scratch, childResult, resumeValue, primitiveCarrierResult,
                    boundParameters);
        }
        builder.endBlock();
        builder.endTag(StandardTags.RootTag.class);
        currentInlineCallbackRegion = enclosingRegion;
        builder.endSourceSection();
    }

    private void emitInlineClosureBodyContent(
            ProtosBytecodeRootNodeGen.Builder builder,
            CanonicalClosure closure,
            InlineBodyResult mode,
            BytecodeLocal result,
            BytecodeLocal scratch,
            BytecodeLocal childResult,
            BytecodeLocal resumeValue,
            boolean primitiveCarrierResult,
            java.util.Map<String, BytecodeLocal> boundParameters) {
        // Reuse I095's lexical proof and existing callback-local handlers.
        // An inline callback's bindings are *not* bindings of its parent root.
        CanonicalBindingAnalysis analysis =
                compilationFacts.isolatedClosureAnalysis(closure);
        CanonicalLexicalScope scope =
                analysis.scopeOf(closure).orElseThrow();

        java.util.Map<String, BytecodeLocal> savedLocals =
                currentNormalCallbackScalarLocals;
        CanonicalBindingAnalysis savedAnalysis =
                currentNormalCallbackScalarAnalysis;
        CanonicalLexicalScope savedScope =
                currentNormalCallbackScalarScope;
        boolean savedDirectParent = currentNormalDirectParentCaptureReads;
        boolean savedInlineBody = currentInlineClosureCallBody;
        BytecodeLocal savedContextCache = currentInlineContextCache;
        ProtosBytecodeRootNode.LexicalFrameLayout savedContextLayout = currentInlineContextLayout;
        ProtosSelectedCallbackPlan savedContextCallback = currentInlineContextCallback;

        // Stable ordering matters for Bytecode DSL reparses. The I095
        // emitBodyCreate/emitBodyAssign/emitLookup handlers recognize these
        // locals by their isolated lexical binding identity.
        java.util.Map<String, BytecodeLocal> locals = new java.util.LinkedHashMap<>();
        java.util.List<String> names =
                new java.util.ArrayList<>(scope.declaredNames());
        java.util.Collections.sort(names);
        for (String name : names) {
            BytecodeLocal bound = boundParameters.get(name);
            locals.put(name, bound != null ? bound : builder.createLocal(
                    NORMAL_INLINE_CALLBACK_BINDING_LOCAL_PREFIX + name, currentInlineCallbackRegion));
        }
        /*
         * Root-scoped physical locals are reused, but every inline callback invocation starts
         * with its own bindings ABSENT. The operation runs inside the selected
         * callback path, including once per iteration of an admitted loop.
         * A value-producing body opens its Block first, so these resets are
         * statements of that Block and its last expression is its value.
         */
        if (mode == InlineBodyResult.STACK) {
            builder.beginBlock();
        }
        for (String name : names) {
            if (!boundParameters.containsKey(name)) {
                builder.emitResetInlineCallbackLocal(locals.get(name));
            }
        }
        /*
         * A callback that observes its context gets, per inline invocation,
         * a cache for the activation its first observation creates; the
         * activation's bindings are the live view of these region locals.
         */
        if (scope.observesContext()) {
            BytecodeLocal cache = pendingInlineContextCache;
            pendingInlineContextCache = null;
            builder.emitResetInlineCallbackLocal(cache);
            currentInlineContextCache = cache;
            currentInlineContextLayout = inlineRegionLayout(locals);
            currentInlineContextCallback = new ProtosSelectedCallbackPlan(closure, language, source);
        } else {
            currentInlineContextCache = null;
            currentInlineContextLayout = null;
            currentInlineContextCallback = null;
        }
        currentNormalCallbackScalarLocals = java.util.Map.copyOf(locals);
        currentNormalCallbackScalarAnalysis = analysis;
        currentNormalCallbackScalarScope = scope;
        currentNormalDirectParentCaptureReads = true;
        currentInlineClosureCallBody = true;
        try {
            java.util.List<CanonicalExpression> expressions =
                    closure.body().expressions();
            if (expressions.isEmpty()) {
                switch (mode) {
                    case STACK -> {
                        builder.emitLoadConstant(ProtosNullValue.INSTANCE);
                        builder.endBlock();
                    }
                    case STORE -> {
                        builder.beginStoreLocal(result);
                        builder.emitLoadConstant(ProtosNullValue.INSTANCE);
                        builder.endStoreLocal();
                    }
                    case DISCARD -> { }
                }
                return;
            }
            DiscardSink sink = null;
            for (int index = 0; index < expressions.size(); index++) {
                CanonicalExpression expression = expressions.get(index);
                boolean last = index == expressions.size() - 1;
                if ((!last || mode == InlineBodyResult.DISCARD)
                        && isI115EliminableControl(expression)) {
                    continue;
                }
                beginStatement(builder, expression);
                if (!last || mode == InlineBodyResult.DISCARD) {
                    if (sink == null && classifyStaticControlSelection(expression) == null) {
                        sink = new DiscardSink();
                    }
                    emitExpressionStatement(
                            builder, expression, sink, scratch, childResult, resumeValue);
                } else if (mode == InlineBodyResult.STACK) {
                    emitExpressionValue(builder, expression,
                            scratch, childResult, resumeValue, primitiveCarrierResult);
                } else {
                    emitBodyExpressionToLocal(
                            builder, expression, result,
                            scratch, childResult, resumeValue);
                }
                endStatement(builder);
            }
            if (mode == InlineBodyResult.STACK) {
                builder.endBlock();
            }
        } finally {
            currentNormalCallbackScalarLocals = savedLocals;
            currentNormalCallbackScalarAnalysis = savedAnalysis;
            currentNormalCallbackScalarScope = savedScope;
            currentInlineClosureCallBody = savedInlineBody;
            currentInlineContextCache = savedContextCache;
            currentInlineContextLayout = savedContextLayout;
            currentInlineContextCallback = savedContextCallback;
            currentNormalDirectParentCaptureReads = savedDirectParent;
        }
    }

    /**
     * one static admission policy for an immediately invoked Closure
     * literal, whether invoked as a control callback or as an ordinary Call0.
     * Nonliteral/dynamic callables are never statically inlined here; they use
     * the ordinary callable dispatcher. In particular, a failed lexical or
     * ReturnHome proof does not authorize a syntactic inline.
     */
    private boolean admitsInlineControlClosure(CanonicalExpression callable) {
        return admitsInlineControlClosure(callable, currentControlSiteContext());
    }

    private boolean admitsInlineControlClosure(
            CanonicalExpression callable, ControlSiteContext context) {
        return callable instanceof CanonicalClosure closure
                && admitsNormalInlineControlBody(closure, context);
    }

    /**
     * lower the seven control selectors. Static literal conditions
     * with a selected inline callback execute it directly; known-empty
     * controls generate only canonical null when consumed. The remaining
     * controls stage dynamic operands in source order and use structured
     * control flow, including {@code CanonicalControlCondition} where needed.
     * The selector itself is never sent.
     */
    /*
     * contextual syntax is the corresponding standard Closure-control
     * send. Reuse the same native Bytecode DSL control lowering and the same
     * proof of whether each callback activation can be inlined.
     *
     * A callback whose activation is observable remains a genuine Closure
     * invocation through Call*Method and Yield/ResumeDispatchedContinuation.
     * Canonical control itself never becomes a guest message send.
     */
    private void emitContextualControl(
            ProtosBytecodeRootNodeGen.Builder builder,
            CanonicalSend send,
            BytecodeLocal result,
            BytecodeLocal scratch,
            BytecodeLocal childResult,
            BytecodeLocal resumeValue,
            boolean primitiveCarrierResult) {
        if (!send.contextual()) {
            throw new AssertionError("Not contextual control");
        }

        ControlSelector control =
                ControlSelector.of(send.message(), send.arguments());
        if (control == null) {
            throw new AssertionError(
                    "Unsupported contextual control: " + send.message());
        }

        CanonicalSend discarded = discardedControlStatement;
        discardedControlStatement = null;
        boolean valueUsed = result != null && discarded != send;

        emitControl(
                builder,
                control,
                send.receiver(),
                send.arguments(),
                valueUsed,
                result,
                scratch,
                childResult,
                resumeValue,
                this::emitBodyExpressionToLocal,
                primitiveCarrierResult);
    }

    private void emitControl(
            ProtosBytecodeRootNodeGen.Builder builder,
            ControlSelector control,
            CanonicalExpression receiver,
            java.util.List<CanonicalExpression> arguments,
            boolean valueUsed,
            BytecodeLocal result,
            BytecodeLocal scratch,
            BytecodeLocal childResult,
            BytecodeLocal resumeValue,
            OperandStager stager,
            boolean primitiveCarrierResult) {
        StaticControlSelection staticControl =
                classifyStaticControlSelection(control, receiver, arguments);
        if (staticControl != null) {
            if (staticControl.eliminable()) {
                if (valueUsed) {
                    builder.beginStoreLocal(result);
                    builder.emitLoadConstant(ProtosNullValue.INSTANCE);
                    builder.endStoreLocal();
                }
                return;
            }

            java.util.List<CanonicalExpression> directOperands =
                    new java.util.ArrayList<>(arguments.size() + 1);
            directOperands.add(receiver);
            directOperands.addAll(arguments);

            java.util.List<BytecodeLocal> noLocals =
                    new java.util.ArrayList<>(
                            java.util.Collections.nCopies(directOperands.size(), null));
            java.util.List<Boolean> inline =
                    new java.util.ArrayList<>(
                            java.util.Collections.nCopies(directOperands.size(), false));
            inline.set(staticControl.selectedPosition(), true);

            // Preserve the actual invocation site around the selected semantic callback,
            // even when selection and physical dispatch have been eliminated.
            builder.beginTag(StandardTags.CallTag.class);
            builder.beginBlock();
            emitConditionalControl(
                    builder, control,
                    new ControlOperands(
                            directOperands, noLocals, inline,
                            scratch, childResult, resumeValue),
                    staticControl, valueUsed, result, primitiveCarrierResult);
            builder.endBlock();
            builder.endTag(StandardTags.CallTag.class);
            return;
        }

        /*
         * A conditional whose receiver may be any object selects natively only
         * for canonical true or false; any other receiver performs the
         * ordinary send, whose method receives genuine callbacks. Those are
         * created by MaterializeSelectedCallback over the live frame, so the
         * inline callbacks keep their captures in plain locals.
         */
        boolean selectsCustomBehavior =
                !control.loop()
                        && canonicalBooleanLiteral(receiver) == null;

        builder.beginTag(StandardTags.CallTag.class);
        builder.beginBlock();

        BytecodeLocal conditionalReceiver = null;
        if (selectsCustomBehavior) {
            conditionalReceiver = builder.createLocal("conditionalReceiver", null);
            stageOperand(builder, receiver, conditionalReceiver,
                    scratch, childResult, resumeValue, stager);
            builder.beginIfThenElse();
            builder.beginIsCanonicalBooleanOperand();
            builder.emitLoadLocal(conditionalReceiver);
            builder.endIsCanonicalBooleanOperand();
            builder.beginBlock();
        }

        java.util.List<CanonicalExpression> operands = new java.util.ArrayList<>(arguments.size() + 1);
        operands.add(receiver);
        operands.addAll(arguments);
        java.util.List<BytecodeLocal> staged = new java.util.ArrayList<>(operands.size());
        java.util.List<Boolean> inline = new java.util.ArrayList<>(operands.size());
        /*
         * when every callback of a conditional runs inline, nothing is
         * evaluated after the receiver, so a direct numeric receiver feeds
         * CanonicalControlCondition straight from the stack.
         */
        boolean allCallbacksInline = true;
        for (int position = 1; position < operands.size(); position++) {
            allCallbacksInline &= admitsInlineControlClosure(operands.get(position));
        }
        for (int position = 0; position < operands.size(); position++) {
            if (selectsCustomBehavior && position == 0) {
                inline.add(false);
                staged.add(conditionalReceiver);
                continue;
            }
            CanonicalExpression operand = operands.get(position);
            boolean callback = control.callbackPosition(position);
            boolean admitted = callback && admitsInlineControlClosure(operand)
                    && (receiver instanceof CanonicalClosure
                            || canonicalBooleanLiteral(receiver) != null
                            || admitsSelectedCallbacks(arguments));
            inline.add(admitted);
            boolean directDecision = !callback
                    && (canonicalBooleanLiteral(operand) != null
                            || (allCallbacksInline
                                    && operand instanceof CanonicalSend send
                                    && primitiveNumericSendCandidate(send)));
            if (admitted || directDecision) {
                staged.add(null);
            } else {
                BytecodeLocal local = builder.createLocal("controlOperand", null);
                stageOperand(builder, operand, local,
                        scratch, childResult, resumeValue, stager);
                staged.add(local);
            }
        }
        ControlOperands staging = new ControlOperands(
                operands, staged, inline, scratch, childResult, resumeValue);
        if (control.loop()) {
            emitValidatedLoop(builder, control, staging, arguments, valueUsed, result,
                    scratch, childResult, resumeValue, stager, primitiveCarrierResult);
        } else {
            emitConditionalControl(
                    builder, control, staging, null,
                    valueUsed, result, primitiveCarrierResult);
        }

        if (selectsCustomBehavior) {
            builder.endBlock();
            emitSelectedConditionalSend(
                    builder, control.selector, conditionalReceiver, arguments,
                    valueUsed ? result : null,
                    scratch, childResult, resumeValue, stager, primitiveCarrierResult);
            builder.endIfThenElse();
        }

        builder.endBlock();
        builder.endTag(StandardTags.CallTag.class);
    }

    /*
     * EXECUTION_AND_CONTROL: a standard loop validates, after every operand
     * is evaluated and before activating either Closure, that the receiver
     * and the argument are semantic Closures. A Closure literal is one by
     * construction and is not tested. A receiver that is not a literal may
     * also be any other object, or a Closure with a nearer slot of the
     * selector: ordinary dispatch then selects that method, which receives
     * the evaluated argument, a callback literal being created over the live
     * frame. When such a callback cannot be created that way, the receiver
     * is validated like the argument instead.
     */
    private void emitValidatedLoop(
            ProtosBytecodeRootNodeGen.Builder builder,
            ControlSelector control,
            ControlOperands staging,
            java.util.List<CanonicalExpression> arguments,
            boolean valueUsed,
            BytecodeLocal result,
            BytecodeLocal scratch,
            BytecodeLocal childResult,
            BytecodeLocal resumeValue,
            OperandStager stager,
            boolean primitiveCarrierResult) {
        BytecodeLocal receiver = staging.staged().get(0);
        BytecodeLocal argument = staging.staged().get(1);
        boolean receiverDynamic = !(staging.operands().get(0) instanceof CanonicalClosure);
        boolean argumentDynamic = !(staging.operands().get(1) instanceof CanonicalClosure);
        boolean selectsCustomBehavior = receiverDynamic;

        if (selectsCustomBehavior) {
            builder.beginIfThenElse();
            builder.beginIsStandardLoopReceiver(control.selector);
            builder.emitLoadLocal(receiver);
            builder.endIsStandardLoopReceiver();
        }
        builder.beginBlock();
        if (receiverDynamic && !selectsCustomBehavior) {
            emitRequireLoopClosure(builder, receiver);
        }
        if (argumentDynamic) {
            emitRequireLoopClosure(builder, argument);
        }
        emitLoop(builder, control, staging, valueUsed, result, primitiveCarrierResult);
        builder.endBlock();
        if (selectsCustomBehavior) {
            emitSelectedConditionalSend(
                    builder, control.selector, receiver, arguments,
                    java.util.Arrays.asList(argument),
                    valueUsed ? result : null,
                    scratch, childResult, resumeValue, stager, primitiveCarrierResult);
            builder.endIfThenElse();
        }
    }

    private void emitRequireLoopClosure(
            ProtosBytecodeRootNodeGen.Builder builder, BytecodeLocal operand) {
        builder.beginRequireLoopClosure();
        builder.emitLoadLocal(operand);
        builder.endRequireLoopClosure();
    }

    /*
     * Whether a conditional site can pass genuine callbacks to a method that
     * ordinary dispatch selects: every literal callback must reach its free
     * names by name from this root (its own bindings through the frame-backed
     * view, or module and unresolved names). Otherwise the site keeps only the
     * standard Boolean selection.
     */
    private boolean admitsSelectedCallbacks(java.util.List<CanonicalExpression> arguments) {
        if (currentRootTopScope == null || currentRootAnalysis == null) {
            return false;
        }
        for (CanonicalExpression argument : arguments) {
            if (argument instanceof CanonicalClosure closure
                    && !compilationFacts.isolatedClosureAnalysis(closure)
                            .freeNamesSelectableFrom(currentRootAnalysis, currentRootTopScope)) {
                return false;
            }
        }
        return true;
    }

    /*
     * The ordinary send of a conditional selector to a receiver that is not a
     * canonical Boolean. Arguments are evaluated again in source order, as
     * this branch is the only one that runs; a callback literal becomes a
     * genuine Closure created over the live frame.
     */
    private void emitSelectedConditionalSend(
            ProtosBytecodeRootNodeGen.Builder builder,
            String selector,
            BytecodeLocal receiver,
            java.util.List<CanonicalExpression> arguments,
            BytecodeLocal result,
            BytecodeLocal scratch,
            BytecodeLocal childResult,
            BytecodeLocal resumeValue,
            OperandStager stager,
            boolean primitiveCarrierResult) {
        emitSelectedConditionalSend(builder, selector, receiver, arguments, null, result,
                scratch, childResult, resumeValue, stager, primitiveCarrierResult);
    }

    /*
     * As above; an argument whose value is already in {@code evaluated} (same
     * index, non-null) is passed as is instead of being evaluated again.
     */
    private void emitSelectedConditionalSend(
            ProtosBytecodeRootNodeGen.Builder builder,
            String selector,
            BytecodeLocal receiver,
            java.util.List<CanonicalExpression> arguments,
            java.util.List<BytecodeLocal> evaluated,
            BytecodeLocal result,
            BytecodeLocal scratch,
            BytecodeLocal childResult,
            BytecodeLocal resumeValue,
            OperandStager stager,
            boolean primitiveCarrierResult) {
        builder.beginBlock();
        BytecodeLocal method = builder.createLocal("conditionalMethod", null);
        java.util.List<BytecodeLocal> argumentValues = new java.util.ArrayList<>(arguments.size());
        for (int index = 0; index < arguments.size(); index++) {
            CanonicalExpression argument = arguments.get(index);
            BytecodeLocal known = evaluated == null ? null : evaluated.get(index);
            if (known != null) {
                argumentValues.add(known);
                continue;
            }
            BytecodeLocal value = builder.createLocal("conditionalArgument", null);
            if (argument instanceof CanonicalClosure closure
                    && admitsSelectedCallbacks(arguments)) {
                builder.beginStoreLocal(value);
                builder.beginMaterializeSelectedCallback();
                emitCurrentActivation(builder);
                builder.emitLoadConstant(new ProtosSelectedCallbackPlan(closure, language, source));
                builder.endMaterializeSelectedCallback();
                builder.endStoreLocal();
            } else {
                stageOperand(builder, argument, value, scratch, childResult, resumeValue, stager);
            }
            argumentValues.add(value);
        }
        // D237: selection after the argument packet.
        emitGetMethod(builder, selector, receiver, method);
        emitDispatchedCall(builder, method, argumentValues, null,
                childResult, result, resumeValue, primitiveCarrierResult);
        builder.endBlock();
    }

    /** the evaluated operands of one control site and the shared invocation scratch. */
    private record ControlOperands(
            java.util.List<CanonicalExpression> operands,
            java.util.List<BytecodeLocal> staged,
            java.util.List<Boolean> inline,
            BytecodeLocal scratch,
            BytecodeLocal childResult,
            BytecodeLocal resumeValue) {}

    /**
     * ifTrue, ifFalse, ifTrueIfFalse. Static admitted controls directly
     * execute their selected callback without a condition or unused branch.
     * Dynamic consumed controls use {@code Conditional}; discarded ones use
     * {@code IfThen} or {@code IfThenElse}.
     */
    private void emitConditionalControl(
            ProtosBytecodeRootNodeGen.Builder builder,
            ControlSelector control,
            ControlOperands staging,
            StaticControlSelection staticControl,
            boolean valueUsed,
            BytecodeLocal result,
            boolean primitiveCarrierResult) {
        if (staticControl != null) {
            if (staticControl.eliminable()) {
                if (valueUsed) {
                    builder.beginStoreLocal(result);
                    builder.emitLoadConstant(ProtosNullValue.INSTANCE);
                    builder.endStoreLocal();
                }
            } else if (valueUsed) {
                emitControlCallback(
                        builder, staging, staticControl.selectedPosition(),
                        result, primitiveCarrierResult);
            } else {
                emitControlCallbackStatement(
                        builder, staging, staticControl.selectedPosition());
            }
            return;
        }

        boolean twoCallbacks = control.arity == 2;
        if (valueUsed) {
            builder.beginStoreLocal(result);
            builder.beginConditional();
            emitConditionalDecision(builder, control, staging);
            emitControlCallbackValue(builder, staging, 1, primitiveCarrierResult);
            if (twoCallbacks) {
                emitControlCallbackValue(builder, staging, 2, primitiveCarrierResult);
            } else {
                builder.emitLoadConstant(ProtosNullValue.INSTANCE);
            }
            builder.endConditional();
            builder.endStoreLocal();
        } else if (twoCallbacks) {
            builder.beginIfThenElse();
            emitConditionalDecision(builder, control, staging);
            emitControlCallbackStatement(builder, staging, 1);
            emitControlCallbackStatement(builder, staging, 2);
            builder.endIfThenElse();
        } else {
            builder.beginIfThen();
            emitConditionalDecision(builder, control, staging);
            emitControlCallbackStatement(builder, staging, 1);
            builder.endIfThen();
        }
    }

    private void emitConditionalDecision(
            ProtosBytecodeRootNodeGen.Builder builder,
            ControlSelector control,
            ControlOperands staging) {
        BytecodeLocal receiver = staging.staged().get(0);
        CanonicalExpression operand = staging.operands().get(0);
        Boolean literal = receiver == null ? canonicalBooleanLiteral(operand) : null;
        if (literal != null) {
            builder.emitLoadConstant(literal == control.polarity);
        } else if (receiver == null) {
            builder.beginCanonicalControlCondition(control.polarity);
            emitExpressionValue(builder, operand, staging.scratch(),
                    staging.childResult(), staging.resumeValue(), false);
            builder.endCanonicalControlCondition();
        } else {
            builder.beginCanonicalControlCondition(control.polarity);
            builder.emitLoadLocal(receiver);
            builder.endCanonicalControlCondition();
        }
    }

    /**
     * whileTrue, whileFalse (pre-test) and untilTrue, untilFalse
     * (post-test) as one Bytecode DSL {@code While}. Literal inline
     * conditions feed the loop's continue Boolean directly; dynamic ones
     * use {@code CanonicalControlCondition}. Pre-test: the While body invokes the
     * body callback. Post-test: the While condition is a block running the
     * body, then the condition, so the body precedes every condition
     * including the first; the While body is empty.
     *
     * <p>A consumed loop value is held in {@code result} alone: canonical
     * null before the loop, overwritten only when a body invocation
     * completes normally, and returned unchanged after the loop. A
     * discarded loop neither initializes nor writes it.
     */
    private void emitLoop(
            ProtosBytecodeRootNodeGen.Builder builder,
            ControlSelector control,
            ControlOperands staging,
            boolean valueUsed,
            BytecodeLocal result,
            boolean primitiveCarrierResult) {
        if (valueUsed) {
            builder.beginStoreLocal(result);
            builder.emitLoadConstant(ProtosNullValue.INSTANCE);
            builder.endStoreLocal();
        }
        boolean postTest = control.phase == ControlSelector.Phase.POST_TEST;
        builder.beginWhile();
        builder.beginBlock();
        if (postTest) {
            emitLoopBody(builder, staging, control.bodyPosition,
                    valueUsed ? result : null, primitiveCarrierResult);
        }
        Boolean knownCondition = staging.inline().get(control.conditionPosition)
                ? literalClosureBoolean(
                        staging.operands().get(control.conditionPosition))
                : null;

        if (knownCondition != null) {
            builder.emitLoadConstant(knownCondition == control.polarity);
        } else {
            builder.beginCanonicalControlCondition(control.polarity);
            emitControlCallbackValue(
                    builder, staging, control.conditionPosition, false);
            builder.endCanonicalControlCondition();
        }
        builder.endBlock();
        builder.beginBlock();
        if (!postTest) {
            emitLoopBody(builder, staging, control.bodyPosition,
                    valueUsed ? result : null, primitiveCarrierResult);
        }
        builder.endBlock();
        builder.endWhile();
    }

    /* a loop body stores its value only for a consumed loop. */
    private void emitLoopBody(
            ProtosBytecodeRootNodeGen.Builder builder,
            ControlOperands staging,
            int position,
            BytecodeLocal result,
            boolean primitiveCarrierResult) {
        if (result == null) {
            emitControlCallbackStatement(builder, staging, position);
        } else {
            emitControlCallback(builder, staging, position, result, primitiveCarrierResult);
        }
    }

    /**
     * a selected callback whose value feeds its consumer directly on
     * the stack. An inline callback has no intermediate store; an ordinary
     * callback keeps only the storage its invocation needs.
     */
    private void emitControlCallbackValue(
            ProtosBytecodeRootNodeGen.Builder builder,
            ControlOperands staging,
            int position,
            boolean primitiveCarrierResult) {
        if (staging.inline().get(position)) {
            emitInlineControlClosureBody(
                    builder, (CanonicalClosure) staging.operands().get(position),
                    InlineBodyResult.STACK, null,
                    staging.scratch(), staging.childResult(), staging.resumeValue(),
                    primitiveCarrierResult);
            return;
        }
        BytecodeLocal value = builder.createLocal("callbackValue", null);
        builder.beginBlock();
        emitDispatchedCall(builder, staging.staged().get(position), java.util.List.of(), null,
                staging.childResult(), value, staging.resumeValue(), primitiveCarrierResult);
        builder.emitLoadLocal(value);
        builder.endBlock();
    }

    /** a selected callback whose result is discarded; nothing is stored. */
    private void emitControlCallbackStatement(
            ProtosBytecodeRootNodeGen.Builder builder,
            ControlOperands staging,
            int position) {
        builder.beginBlock();
        if (staging.inline().get(position)) {
            emitInlineControlClosureBody(
                    builder, (CanonicalClosure) staging.operands().get(position),
                    InlineBodyResult.DISCARD, null,
                    staging.scratch(), staging.childResult(), staging.resumeValue(), false);
        } else {
            emitDispatchedCall(builder, staging.staged().get(position), java.util.List.of(), null,
                    staging.childResult(), null, staging.resumeValue(), false);
        }
        builder.endBlock();
    }

    /**
     * the one execution of a selected control callback, storing its
     * exact normal result once in {@code target}. An admitted literal runs
     * inline (no Closure, activation or ReturnHome is created); any other
     * callback is an ordinary nilary Closure call through the shared
     * dispatcher and its continuation composition.
     */
    private void emitControlCallback(
            ProtosBytecodeRootNodeGen.Builder builder,
            ControlOperands staging,
            int position,
            BytecodeLocal target,
            boolean primitiveCarrierResult) {
        if (staging.inline().get(position)) {
            emitInlineControlClosureBody(
                    builder, (CanonicalClosure) staging.operands().get(position),
                    InlineBodyResult.STORE, target,
                    staging.scratch(), staging.childResult(), staging.resumeValue(),
                    primitiveCarrierResult);
        } else {
            emitDispatchedCall(builder, staging.staged().get(position), java.util.List.of(), null,
                    staging.childResult(), target, staging.resumeValue(), primitiveCarrierResult);
        }
    }

    /**
     * the native Boolean selector row of an ordinary send of
     * {@code not()}, {@code and(rhs)} or {@code or(rhs)}, or {@code null}.
     * Admitted only once the standard object (and thus the frozen standard
     * Boolean behavior) is published, with the exact standard arity and no
     * spread or named argument. Admission is independent of whether the RHS
     * is an inline-admissible Closure literal: {@link #emitBooleanControl}
     * chooses the RHS execution strategy. Any other site keeps the ordinary
     * GetMethod/Call*Method lowering unchanged.
     */
    private NormalBooleanControl nativeBooleanControl(
            String selector, java.util.List<CanonicalExpression> arguments) {
        NormalBooleanControl control =
                selector == null ? null : NormalBooleanControl.of(selector);
        if (control == null
                || !standardObjectPublished
                || arguments.size() != control.arity
                || hasVectorArgument(arguments)
                || hasNamedArgument(arguments)) {
            return null;
        }
        return control;
    }

    /**
     * native short-circuit {@code not}/{@code and}/{@code or}.
     *
     * <p>The receiver is evaluated once into a local. When it is exactly a
     * canonical Boolean (or the primitive Boolean carrier of a native
     * comparison, never materialized for this test) the standard behavior
     * of VALUES_AND_COLLECTIONS §16 runs as Bytecode DSL control flow:
     * {@code CanonicalControlCondition} selects, the unselected RHS body is
     * not executed, a selected RHS runs inline exactly once and its result
     * is checked by {@code RequireCanonicalBooleanResult}. The stored result
     * is always the canonical guest {@code true}/{@code false}. No message
     * is sent on this path.
     *
     * <p>A RHS that is not an inline-admissible Closure literal (a nonliteral
     * argument, or a literal whose activation may observe its lexical
     * Context, ReturnHome or suspension) is evaluated on the native path
     * exactly as an ordinary argument, after the receiver and regardless of
     * the selection, so Closure creation and capture happen as in the
     * ordinary lowering; only a selected RHS is then invoked through the
     * ordinary zero-argument {@code Call*Method} with continuation
     * composition, and its result is strictly Boolean-checked.
     *
     * <p>Any other receiver takes the ordinary path on the same staged
     * receiver: {@code GetMethod(selector)}, the RHS Closure literal is
     * materialized as a genuine Closure argument, and {@code Call*Method}
     * dispatches, so redefinitions and RHS Closure observation are
     * preserved. The RHS Closure is never an inline callback position for
     * {@link InlineStoragePlan}, so captured cells stay cells for that
     * materialization.
     */
    private void emitBooleanControl(
            ProtosBytecodeRootNodeGen.Builder builder,
            NormalBooleanControl control,
            CanonicalExpression receiver,
            String selector,
            java.util.List<CanonicalExpression> arguments,
            BytecodeLocal result,
            BytecodeLocal scratch,
            BytecodeLocal childResult,
            BytecodeLocal resumeValue,
            OperandStager stager) {
        builder.beginTag(StandardTags.CallTag.class);
        builder.beginBlock();

        BytecodeLocal receiverValue = builder.createLocal("booleanReceiver", null);
        stageOperand(builder, receiver, receiverValue,
                scratch, childResult, resumeValue, stager);

        builder.beginIfThenElse();

        builder.beginIsCanonicalBooleanOperand();
        builder.emitLoadLocal(receiverValue);
        builder.endIsCanonicalBooleanOperand();

        if (control != NormalBooleanControl.NOT
                && (!admitsInlineControlClosure(arguments.get(0))
                        || !admitsSelectedCallbacks(arguments))) {
            emitMaterializedBooleanRhs(
                    builder, control, receiverValue, arguments.get(0),
                    result, scratch, childResult, resumeValue, stager);
        } else {
            emitInlineBooleanControl(
                    builder, control, receiverValue, arguments,
                    result, scratch, childResult, resumeValue);
        }

        // Any other receiver: ordinary selection on the staged receiver.
        if (admitsSelectedCallbacks(arguments)) {
            emitSelectedConditionalSend(
                    builder, selector, receiverValue, arguments, result,
                    scratch, childResult, resumeValue, stager, false);
            builder.endIfThenElse();
            builder.endBlock();
            builder.endTag(StandardTags.CallTag.class);
            return;
        }
        builder.beginBlock();
        BytecodeLocal callable = builder.createLocal("invocationCallable", null);
        java.util.List<BytecodeLocal> argumentValues =
                new java.util.ArrayList<>(arguments.size());
        for (CanonicalExpression argument : arguments) {
            BytecodeLocal argumentValue = builder.createLocal("invocationArgument", null);
            stageOperand(builder, argument, argumentValue,
                    scratch, childResult, resumeValue, stager);
            argumentValues.add(argumentValue);
        }
        // D237: selection after the argument packet.
        emitGetMethod(builder, selector, receiverValue, callable);
        emitDispatchedCall(builder, callable, argumentValues, null,
                childResult, result, resumeValue, false);
        builder.endBlock();

        builder.endIfThenElse();

        builder.endBlock();
        builder.endTag(StandardTags.CallTag.class);
    }

    /**
     * the native branch of {@code not} and of an inline-admissible
     * and/or RHS: one {@code Conditional} whose selected RHS runs inline.
     */
    private void emitInlineBooleanControl(
            ProtosBytecodeRootNodeGen.Builder builder,
            NormalBooleanControl control,
            BytecodeLocal receiverValue,
            java.util.List<CanonicalExpression> arguments,
            BytecodeLocal result,
            BytecodeLocal scratch,
            BytecodeLocal childResult,
            BytecodeLocal resumeValue) {
        builder.beginStoreLocal(result);
        builder.beginConditional();
        builder.beginCanonicalControlCondition(true);
        builder.emitLoadLocal(receiverValue);
        builder.endCanonicalControlCondition();
        switch (control) {
            case NOT -> {
                builder.emitLoadConstant(Boolean.FALSE);
                builder.emitLoadConstant(Boolean.TRUE);
            }
            case AND -> {
                emitBooleanRhsValue(builder, (CanonicalClosure) arguments.get(0),
                        scratch, childResult, resumeValue);
                builder.emitLoadConstant(Boolean.FALSE);
            }
            case OR -> {
                builder.emitLoadConstant(Boolean.TRUE);
                emitBooleanRhsValue(builder, (CanonicalClosure) arguments.get(0),
                        scratch, childResult, resumeValue);
            }
        }
        builder.endConditional();
        builder.endStoreLocal();
    }

    /**
     * the native branch of and/or whose RHS is not inline-admissible.
     * The RHS argument is staged exactly once (materializing a literal
     * Closure with its captures), the canonical receiver selects natively,
     * and only a selected RHS is invoked by the zero-argument
     * {@code Call*Method}, whose suspension/non-local return use the
     * ordinary continuation composition. Its result must be exactly a
     * canonical Boolean; an unselected RHS yields the receiver's constant.
     */
    private void emitMaterializedBooleanRhs(
            ProtosBytecodeRootNodeGen.Builder builder,
            NormalBooleanControl control,
            BytecodeLocal receiverValue,
            CanonicalExpression rhs,
            BytecodeLocal result,
            BytecodeLocal scratch,
            BytecodeLocal childResult,
            BytecodeLocal resumeValue,
            OperandStager stager) {
        builder.beginBlock();
        BytecodeLocal rhsValue = builder.createLocal("booleanRhs", null);
        stageOperand(builder, rhs, rhsValue,
                scratch, childResult, resumeValue, stager);

        builder.beginIfThenElse();
        builder.beginCanonicalControlCondition(control == NormalBooleanControl.AND);
        builder.emitLoadLocal(receiverValue);
        builder.endCanonicalControlCondition();

        // Selected: and on true, or on false.
        builder.beginBlock();
        emitDispatchedCall(builder, rhsValue, java.util.List.of(), null,
                childResult, result, resumeValue, false);
        builder.beginStoreLocal(result);
        builder.beginRequireCanonicalBooleanResult();
        builder.emitLoadLocal(result);
        builder.endRequireCanonicalBooleanResult();
        builder.endStoreLocal();
        builder.endBlock();

        // Unselected: the receiver itself, as a canonical constant.
        builder.beginStoreLocal(result);
        builder.emitLoadConstant(control == NormalBooleanControl.AND
                ? Boolean.FALSE : Boolean.TRUE);
        builder.endStoreLocal();

        builder.endIfThenElse();
        builder.endBlock();
    }

    /** a selected standard and/or RHS, inline once, strictly Boolean-checked. */
    private void emitBooleanRhsValue(
            ProtosBytecodeRootNodeGen.Builder builder,
            CanonicalClosure rhs,
            BytecodeLocal scratch,
            BytecodeLocal childResult,
            BytecodeLocal resumeValue) {
        builder.beginRequireCanonicalBooleanResult();
        builder.beginBlock();
        emitInlineControlClosureBody(
                builder, rhs, InlineBodyResult.STACK, null,
                scratch, childResult, resumeValue, false);
        builder.endBlock();
        builder.endRequireCanonicalBooleanResult();
    }

    /** The value of a canonical {@code true}/{@code false} literal, or {@code null}. */
    private static Boolean canonicalBooleanLiteral(CanonicalExpression expression) {
        if (expression instanceof CanonicalLiteral literal) {
            return switch (literal.kind()) {
                case TRUE -> Boolean.TRUE;
                case FALSE -> Boolean.FALSE;
                default -> null;
            };
        }
        return null;
    }

    private void stageOperand(
            ProtosBytecodeRootNodeGen.Builder builder,
            CanonicalExpression expression,
            BytecodeLocal target,
            BytecodeLocal scratch,
            BytecodeLocal childResult,
            BytecodeLocal resumeValue,
            OperandStager stager) {
        if (requiresComposedInvocation(expression)) {
            stager.stage(builder, expression, target, scratch, childResult, resumeValue);
        } else {
            builder.beginStoreLocal(target);
            emitExpression(builder, expression);
            builder.endStoreLocal();
        }
    }

    /** {@code GetMethod(selector)} on the staged receiver, stored in {@code callable}. */
    private static void emitGetMethod(
            ProtosBytecodeRootNodeGen.Builder builder,
            String selector,
            BytecodeLocal receiver,
            BytecodeLocal callable) {
        builder.beginStoreLocal(callable);
        builder.beginGetMethod(
                TruffleString.fromJavaStringUncached(
                        selector, TruffleString.Encoding.UTF_16),
                false);
        builder.emitLoadLocal(receiver);
        builder.endGetMethod();
        builder.endStoreLocal();
    }

    /**
     * D237 / one cold, name-bearing invocation.
     *
     * The selected callable and the complete P,K packet are passed to
     * the shared named dispatcher. It associates labels against the
     * actually selected implementation. No named metadata enters the
     * positional Call*Method operations or their B5 headers.
     */
    private void emitDispatchedNamedCall(
            ProtosBytecodeRootNodeGen.Builder builder,
            BytecodeLocal callable,
            BytecodeLocal suppliedVector,
            BytecodeLocal outcome,
            BytecodeLocal result,
            BytecodeLocal resumeValue,
            boolean primitiveCarrierResult) {
        if (suppliedVector == null) {
            throw new AssertionError(
                    "named invocation requires an evaluated argument vector");
        }

        noteDynamicInvocation();
        builder.beginStoreLocal(outcome);
        builder.beginCallNamedMethod();
        builder.emitLoadLocal(callable);
        builder.emitLoadLocal(suppliedVector);
        builder.endCallNamedMethod();
        builder.endStoreLocal();

        emitDispatchedContinuationComposition(
                builder, outcome, resumeValue);

        if (result == null) {
            return;
        }

        builder.beginStoreLocal(result);
        if (primitiveCarrierResult) {
            builder.emitLoadLocal(outcome);
        } else {
            builder.beginMaterializePrimitiveNumericCarrier();
            builder.emitLoadLocal(outcome);
            builder.emitLoadConstant(true);
            builder.endMaterializePrimitiveNumericCarrier();
        }
        builder.endStoreLocal();
    }

    /**
     * The {@code Call*Method} of the staged callable and arguments, its
     * continuation composition and the result store. Shared by every
     * invocation shape so all arities reach the same dispatcher.
     */
    private void emitDispatchedCall(
            ProtosBytecodeRootNodeGen.Builder builder,
            BytecodeLocal callable,
            java.util.List<BytecodeLocal> argumentValues,
            BytecodeLocal suppliedVector,
            BytecodeLocal outcome,
            BytecodeLocal result,
            BytecodeLocal resumeValue,
            boolean primitiveCarrierResult) {
        noteDynamicInvocation();
        builder.beginStoreLocal(outcome);
        emitCallOperation(builder, false, callable, argumentValues, suppliedVector);
        builder.endStoreLocal();

        emitDispatchedContinuationComposition(builder, outcome, resumeValue);

        if (result == null) {
            // a discarded invocation result is not copied anywhere.
            return;
        }
        builder.beginStoreLocal(result);
        if (primitiveCarrierResult) {
            builder.emitLoadLocal(outcome);
        } else {
            builder.beginMaterializePrimitiveNumericCarrier();
            builder.emitLoadLocal(outcome);
            builder.emitLoadConstant(true);
            builder.endMaterializePrimitiveNumericCarrier();
        }
        builder.endStoreLocal();
    }

    /** The arity-selected {@code Call*Method} operation over staged locals. */
    private static void emitCallOperation(
            ProtosBytecodeRootNodeGen.Builder builder,
            boolean terminal,
            BytecodeLocal callable,
            java.util.List<BytecodeLocal> argumentValues,
            BytecodeLocal suppliedVector) {
        int arity = suppliedVector != null ? -1 : argumentValues.size();
        switch (arity) {
            case 0 -> builder.beginCallNilaryMethod(terminal);
            case 1 -> builder.beginCallUnaryMethod(terminal);
            case 2 -> builder.beginCallBinaryMethod(terminal, false);
            case 3 -> builder.beginCallTernaryMethod(terminal);
            case 4 -> builder.beginCallQuaternaryMethod(terminal);
            default -> builder.beginCallVarargsMethod(terminal);
        }
        builder.emitLoadLocal(callable);
        if (arity >= 0 && arity <= 4) {
            for (BytecodeLocal argumentValue : argumentValues) {
                builder.emitLoadLocal(argumentValue);
            }
        } else if (suppliedVector != null) {
            builder.beginSnapshotClosureCallArguments();
            builder.emitLoadLocal(suppliedVector);
            builder.endSnapshotClosureCallArguments();
        } else {
            builder.beginCollectClosureCallArguments();
            for (BytecodeLocal argumentValue : argumentValues) {
                builder.emitLoadLocal(argumentValue);
            }
            builder.endCollectClosureCallArguments();
        }
        switch (arity) {
            case 0 -> builder.endCallNilaryMethod();
            case 1 -> builder.endCallUnaryMethod();
            case 2 -> builder.endCallBinaryMethod();
            case 3 -> builder.endCallTernaryMethod();
            case 4 -> builder.endCallQuaternaryMethod();
            default -> builder.endCallVarargsMethod();
        }
    }

    /**
     * this physical root (its inline Closure bodies included) emits a
     * dispatched invocation whose callee is selected at run time. Its
     * prepareCallerForCallee can forward only the Prelude this root itself
     * observes, so a root that may lack its own Prelude declares
     * NEEDS_CALLER_PRELUDE before publication (lowerRoot), exactly like a
     * '+' site. Never an exact Activation: a Prelude is sufficient.
     */
    private void noteDynamicInvocation() {
        currentRootMayRequirePrelude = true;
    }

    /** a send of already staged locals: GetMethod, then the dispatched call. */
    private void emitDispatchedSendOfLocals(
            ProtosBytecodeRootNodeGen.Builder builder,
            String selector,
            BytecodeLocal receiver,
            java.util.List<BytecodeLocal> argumentValues,
            BytecodeLocal result,
            BytecodeLocal childResult,
            BytecodeLocal resumeValue) {
        BytecodeLocal callable = builder.createLocal("sendMethod", null);
        emitGetMethod(builder, selector, receiver, callable);
        emitDispatchedCall(
                builder, callable, argumentValues, null, childResult, result, resumeValue, false);
    }

    /**
     * a {@link ProtosCallRequest} produced by a protocol operation
     * (Map construction factory, Map hashing/equality), made through the
     * shared dispatcher and composed like any call.
     */
    private void emitRequestInvocation(
            ProtosBytecodeRootNodeGen.Builder builder,
            BytecodeLocal result,
            BytecodeLocal request,
            BytecodeLocal childResult,
            BytecodeLocal resumeValue) {
        builder.beginTag(StandardTags.CallTag.class);
        builder.beginBlock();
        builder.beginStoreLocal(childResult);
        builder.beginCallRequest(false);
        builder.emitLoadLocal(request);
        builder.endCallRequest();
        builder.endStoreLocal();
        emitDispatchedContinuationComposition(builder, childResult, resumeValue);
        builder.beginStoreLocal(result);
        builder.beginMaterializePrimitiveNumericCarrier();
        builder.emitLoadLocal(childResult);
        builder.emitLoadConstant(true);
        builder.endMaterializePrimitiveNumericCarrier();
        builder.endStoreLocal();
        builder.endBlock();
        builder.endTag(StandardTags.CallTag.class);
    }

    /**
     * Composes a pending result from a direct source-function dispatch.
     * The callee frames own their ReturnHomes.
     */
    private void emitDispatchedContinuationComposition(
            ProtosBytecodeRootNodeGen.Builder builder,
            BytecodeLocal childResult,
            BytecodeLocal resumeValue) {

        currentRootEmittedContinuationPath = true;

        builder.beginWhile();

        builder.beginIsResultKind(ProtosResultKind.CONTINUATION);
        builder.emitLoadLocal(childResult);
        builder.endIsResultKind();

        builder.beginBlock();

        builder.beginStoreLocal(resumeValue);
        builder.beginYield();
        builder.emitLoadLocal(childResult);
        builder.endYield();
        builder.endStoreLocal();

        builder.beginStoreLocal(childResult);
        builder.beginResumeDispatchedContinuation();
        builder.emitLoadLocal(childResult);
        builder.emitLoadLocal(resumeValue);
        builder.endResumeDispatchedContinuation();
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
        if (expression instanceof CanonicalNamedArgument named) {
            validateSupportedExpression(named.expression());
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

    /*
     * I112: at root level the creating activation is the root's own frame
     * argument 0, bound by MaterializeCurrentClosure itself rather than
     * loaded as an operand. a root's LOCAL and CELL bindings are never
     * part of its context, so a still-compact root always captures without
     * materializing its activation. Inline object regions keep their
     * explicit activation operand.
     *
     * PLAT066-C: the second constant operand carries the capture
     * classification of this exact Closure under the root's analysis; a
     * Closure that does not capture its creator is created from the
     * creator's own environment, without free cell bindings.
     *
     * A Closure whose scope
     * carries free cells is wrapped by CaptureClosureCells, which installs
     * the creator's cell references, in the Closure scope's constant order,
     * before the Closure escapes. Returns those cells for endClosureCapture.
     */
    private java.util.List<CanonicalBindingIdentity> beginClosureCapture(
            ProtosBytecodeRootNodeGen.Builder builder,
            CanonicalClosure closure) {
        java.util.List<CanonicalBindingIdentity> cells =
                currentRootAnalysis == null
                        ? java.util.List.of()
                        : currentRootAnalysis.scopeOf(closure)
                                .map(CanonicalBindingAnalysis::freeCellsOf)
                                .orElse(java.util.List.of());
        if (!cells.isEmpty()) {
            builder.beginCaptureClosureCells();
        }
        if (currentActivationLocal == null) {
            /*
             * invariant of the compactCapture operand: it answers whether
             * capturing the creator may keep its guest Context deferred.
             * Under the storage model a captured context never needs
             * a copy of the creator's frame: CELL bindings are transported
             * explicitly by CaptureClosureCells, NAME bindings already live
             * in the creator's context object, and a creator whose LOCAL/CELL
             * bindings may be selected by name has already bound its frame to
             * a frame-backed view (, emitClosureCapture). A captured
             * owner is resolved while its frame is live; its guest Context
             * remains deferred, so the answer is statically true
             * for every root-level creation; only an inline object region,
             * which has an explicit activation, uses MaterializeClosure. The
             * operation's other modes are thereby unreachable from lowering.
             *
             * both the ReturnHome and transitive inherited-environment
             * facts gate the lightweight representation.
             */
            builder.beginMaterializeCurrentClosure(
                    COMPACT_ROOT_CLOSURE_CAPTURE,
                    CanonicalCompilationFacts.capturesCreatorContext(
                            closure, currentRootAnalysis),
                    CanonicalCompilationFacts.requiresInheritedLexicalEnvironment(
                            closure, currentRootAnalysis),
                    compilationFacts.mayObserveReturnHome(closure));
        } else {
            builder.beginMaterializeClosure();
            emitCurrentActivation(builder);
        }
        return cells;
    }

    private void endClosureCapture(
            ProtosBytecodeRootNodeGen.Builder builder,
            java.util.List<CanonicalBindingIdentity> cells) {
        if (currentActivationLocal == null) {
            builder.endMaterializeCurrentClosure();
        } else {
            builder.endMaterializeClosure();
        }
        if (!cells.isEmpty()) {
            for (CanonicalBindingIdentity cell : cells) {
                if (currentRootPromotedCells.contains(cell)) {
                    throw new AssertionError(
                            "I115 promoted inline capture reached a materialized Closure: "
                                    + cell.name());
                }
                BytecodeLocal local = currentRootCellLocals.get(cell);
                if (local == null) {
                    throw new AssertionError(
                            "I115 captured cell is not reachable from its creator: "
                                    + cell.name());
                }
                builder.emitLoadLocal(local);
            }
            builder.endCaptureClosureCells();
        }
    }

    private void emitExpression(
            ProtosBytecodeRootNodeGen.Builder builder,
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
            emitClosureCapture(builder, closure, cell);
            return;
        }
        if (expression instanceof CanonicalLookup lookup) {
            emitLookup(builder, lookup);
            return;
        }
        if (expression instanceof CanonicalIntrinsic intrinsic) {
            if (intrinsic.kind() == CanonicalIntrinsic.Kind.THIS
                    && currentActivationLocal == null) {
                builder.emitLoadCurrentReceiver();
                return;
            }
            if (intrinsic.kind() == CanonicalIntrinsic.Kind.CONTEXT
                    && currentInlineContextCache != null) {
                builder.emitPublishInlineCallbackContext(
                        currentInlineContextCache,
                        currentInlineContextLayout,
                        currentInlineContextCallback);
                return;
            }
            if (intrinsic.kind() == CanonicalIntrinsic.Kind.CONTEXT
                    && publishesOwnClosureContext()) {
                /*
                 * the observed Context of a Closure activation is
                 * the live view of its frame, bound once at this point.
                 */
                builder.emitPublishCurrentClosureContext();
                return;
            }
            builder.beginLoadIntrinsic();
            emitCurrentActivation(builder);
            builder.emitLoadConstant(intrinsic.kind());
            builder.endLoadIntrinsic();
            return;
        }
        if (expression instanceof CanonicalMember member) {
            if (member.receiver() instanceof CanonicalClosure closure) {
                ProtosClosureExecutionPlanCell plan =
                        bytecodeClosurePlan(builder, closure);
                builder.beginGetAttribute(attributeName(member.name()));
                emitClosureCapture(builder, closure, plan);
                builder.endGetAttribute();
                return;
            }

            if (containsClosure(member.receiver())) {
                BytecodeLocal completedReceiver =
                        builder.createLocal("memberClosureReceiver", null);
                builder.beginStoreLocal(completedReceiver);
                emitExpression(builder, member.receiver());
                builder.endStoreLocal();

                builder.beginGetAttribute(attributeName(member.name()));
                builder.emitLoadLocal(completedReceiver);
                builder.endGetAttribute();
                return;
            }

            builder.beginGetAttribute(attributeName(member.name()));
            emitExpression(builder, member.receiver());
            builder.endGetAttribute();
            return;
        }
        if (expression instanceof CanonicalIdentity identity) {
            builder.beginIdentity(false);
            emitExpression(builder, identity.left());
            emitExpression(builder, identity.right());
            builder.endIdentity();
            return;
        }
        if (expression instanceof CanonicalNotIdentity identity) {
            builder.beginIdentity(true);
            emitExpression(builder, identity.left());
            emitExpression(builder, identity.right());
            builder.endIdentity();
            return;
        }
        throw new AssertionError(
                "validated value-shaped Bytecode expression became unsupported: "
                        + expression.getClass().getSimpleName());
    }

    private void emitLookup(
            ProtosBytecodeRootNodeGen.Builder builder,
            CanonicalLookup lookup) {
        emitLookup(builder, lookup, false);
    }

    /**
     * emits exactly one access for a bare read, selected at
     * lowering time from the site's static resolution and its run-time access
     * ({@link CanonicalBindingAnalysis.LexicalAccess}):
     * <ul>
     *   <li>{@code DIRECT}: {@code LoadLocal} for a LOCAL binding of this
     *       root, {@code LoadCell} for a CELL binding (owned or carried as a
     *       free cell), {@code ReadGlobal} for a module binding;</li>
     *   <li>{@code PRESENCE_CHECKED}: {@code LoadLexicalLocal} or {@code
     *       LoadLexicalCell}, the same physical read while the binding is
     *       PRESENT and live lexical selection when it is ABSENT, or {@code
     *       ReadGlobal}, which validates module membership itself;</li>
     *   <li>{@code SELECTED} (and every {@code Dynamic} name): one {@code
     *       AccessNamedLexical} live lexical selection. An owned LOCAL keeps
     *       its presence-aware physical read: the only scopes between the site
     *       and its own root are inline regions of that same frame.</li>
     * </ul>
     * A declaration of the same name elsewhere never selects a storage by
     * itself; the selected storage is always the static owner's.
     *
     * <p>I091: carrierOperand is true only for an operand of the guarded
     * primitive numeric chain, which may then receive a local-retained
     * primitive carrier. Every other read yields a guest value.
     */
    private void emitLookup(
            ProtosBytecodeRootNodeGen.Builder builder,
            CanonicalLookup lookup,
            boolean carrierOperand) {
        if (!currentNormalCallbackScalarLocals.isEmpty()
                && currentNormalCallbackScalarLocals.containsKey(lookup.name())) {
            CanonicalBindingResolution resolution =
                    currentNormalCallbackScalarAnalysis.resolutionOf(lookup)
                            .orElse(null);
            BytecodeLocal own = currentNormalCallbackScalarLocals.get(lookup.name());
            if (own == null
                    || !(resolution instanceof CanonicalBindingResolution.Resolved resolved)
                    || resolved.identity().owner() != currentNormalCallbackScalarScope) {
                throw new AssertionError(
                        "I095-B6a unproven callback lookup: " + lookup.name());
            }
            builder.emitLoadLocal(own);
            return;
        }

        CanonicalBindingResolution resolution =
                currentRootAnalysis == null
                        ? null
                        : currentRootAnalysis.resolutionOf(lookup).orElse(null);
        CanonicalBindingIdentity identity = staticIdentityOrNull(resolution);
        if (identity != null) {
            CanonicalBindingAnalysis.LexicalAccess access = currentRootAnalysis.accessOf(lookup);
            BytecodeLocal cell = cellLocalOrNull(identity);
            if (cell != null) {
                if (access == CanonicalBindingAnalysis.LexicalAccess.DIRECT) {
                    // load.deref of a provably PRESENT binding.
                    builder.beginLoadCell();
                    builder.emitLoadLocal(cell);
                    builder.endLoadCell();
                    return;
                }
                if (access == CanonicalBindingAnalysis.LexicalAccess.PRESENCE_CHECKED) {
                    builder.beginLoadLexicalCell(lookup.name());
                    builder.emitLoadLocal(cell);
                    builder.endLoadLexicalCell();
                    return;
                }
            } else {
                BytecodeLocal local = ownLocalOrNull(identity);
                if (local != null) {
                    if (access == CanonicalBindingAnalysis.LexicalAccess.DIRECT) {
                        // LoadLocal.
                        // Long and Double already use their guest representation.
                        builder.emitLoadLocal(local);
                    } else {
                        builder.emitLoadLexicalLocal(lookup.name(), local);
                    }
                    return;
                }
                if (access != CanonicalBindingAnalysis.LexicalAccess.SELECTED
                        && isStaticModuleBinding(resolution, identity)) {
                    /*
                     * a module binding reads its module context
                     * (ReadGlobal) at its static physical depth: 0 in
                     * the module root itself, else the captured module of a
                     * Closure root. An absent binding continues with live
                     * lexical selection.
                     */
                    builder.emitReadGlobal(
                            TruffleString.fromJavaStringUncached(
                                    identity.name(), TruffleString.Encoding.UTF_16),
                            physicalModuleDepth(resolution),
                            resolution instanceof CanonicalBindingResolution.Candidate);
                    return;
                }
            }
        }
        builder.beginAccessNamedLexical(ProtosNamedLexicalAccess.LOOKUP);
        emitCurrentActivation(builder);
        builder.emitLoadConstant(lookup.name());
        builder.endAccessNamedLexical();
    }

    /*
     * whether a context intrinsic here observes the running
     * Closure root's own activation (not a module root, an inline object
     * body's construction activation, or an inline callback region).
     */
    private boolean publishesOwnClosureContext() {
        return currentRootTopScope != null
                && currentRootTopScope.kind() == CanonicalLexicalScope.Kind.CLOSURE
                && currentActivationLocal == null
                && !currentInlineClosureCallBody
                && currentNormalCallbackScalarLocals.isEmpty();
    }

    /**
     * whether a classified site is statically a module (ROOT) NAME
     * binding accessed with the module operations ({@code ReadGlobal},
     * {@code SelectWriteGlobal}/{@code WriteGlobal}). Inside an inline
     * object body (an explicit activation local) the access keeps the
     * object-construction name operation, whose lookup also observes the
     * object under construction.
     */
    private boolean isStaticModuleBinding(
            CanonicalBindingResolution resolution,
            CanonicalBindingIdentity identity) {
        return identity.owner().kind() == CanonicalLexicalScope.Kind.ROOT
                && currentActivationLocal == null
                && (resolution instanceof CanonicalBindingResolution.Resolved
                        || resolution instanceof CanonicalBindingResolution.CapturedResolved
                        || resolution instanceof CanonicalBindingResolution.Candidate);
    }

    /**
     * the physical depth, from the running Bytecode root, of the module
     * context owning a site accepted by {@link #isStaticModuleBinding}:
     * {@code 0} when the running root is the module root itself, else the
     * number of Closure roots crossed. Inside an inline callback the
     * physical root is one scope nearer than the lexical one.
     */
    private int physicalModuleDepth(CanonicalBindingResolution resolution) {
        int lexicalDepth;
        if (resolution instanceof CanonicalBindingResolution.CapturedResolved captured) {
            lexicalDepth = captured.lexicalDepth();
        } else if (resolution instanceof CanonicalBindingResolution.Candidate candidate) {
            lexicalDepth = candidate.lexicalDepth();
        } else {
            lexicalDepth = 0;
        }
        int physicalDepth =
                currentNormalDirectParentCaptureReads ? lexicalDepth - 1 : lexicalDepth;
        if (physicalDepth < 0) {
            throw new AssertionError("I115 module binding above its physical root");
        }
        return physicalDepth;
    }

    /*
     * Numeric carriers are private to the direct arithmetic expression chain.
     * Ordering consumes carriers and always yields a guest Boolean.
     */
    /*
     * A one-argument numeric selector, including '+', lowered as a direct
     * numeric operation whose operands stay on the operand stack.
     */
    private static boolean primitiveNumericSendCandidate(CanonicalSend send) {
        return send.arguments().size() == 1
                && primitiveNumericSelector(send.message())
                && !hasVectorArgument(send.arguments());
    }

    /*
     * I091: a send whose fused direct source call may deliver a callee's
     * primitive result to a carrier-permitting consumer.
     */
    private static boolean primitiveResultSend(CanonicalSend send) {
        if (hasNamedArgument(send.arguments())) {
            return false;
        }
        // I091-F2: zero-argument sends may return primitive carriers
        // through the admitted direct Send0 frame. Generic dispatch
        // remains materialized, and Send1 retains its existing rules.
        return send.arguments().isEmpty()
                || primitiveNumericSendCandidate(send)
                || (send.arguments().size() == 1
                        && !hasSpreadArgument(send.arguments()));
    }

    /*
     * I091-F2: a direct Call1 has the full compact header required
     * for the private primitive-result marker. A generic or rejected
     * call still returns an observable guest value.
     */
    private static boolean primitiveResultCall(CanonicalCall call) {
        return call.arguments().size() == 1
                && !hasVectorArgument(call.arguments());
    }

    private static boolean isOrderingSelector(String selector) {
        return "<".equals(selector) || "<=".equals(selector)
                || ">".equals(selector) || ">=".equals(selector);
    }

    private static boolean primitiveNumericSelector(String selector) {
        return switch (selector) {
            case "+", "-", "*", "/", "div", "mod", "<", "<=", ">", ">=" -> true;
            default -> false;
        };
    }

    /* I091: a binding value the guarded chain or a raw binding read may yield as a carrier. */
    private boolean primitiveNumericCarrierValue(CanonicalExpression value) {
        if (value instanceof CanonicalLiteral literal) {
            return primitiveNumberLiteralOrNull(literal) != null;
        }
        if (value instanceof CanonicalLookup lookup) {
            return !isScalarLocalRead(lookup);
        }
        return (value instanceof CanonicalSend send && primitiveResultSend(send))
                || (value instanceof CanonicalCall call && primitiveResultCall(call));
    }

    /**
     * stores one primitive-numeric operand into {@code target}. The
     * value itself is produced by {@link #emitPrimitiveNumericOperandValue};
     * this adapter is used only where the value must survive between two
     * operations, a suspension, or two independent uses.
     */
    private void emitPrimitiveNumericOperandToLocal(
            ProtosBytecodeRootNodeGen.Builder builder,
            CanonicalExpression expression,
            BytecodeLocal target,
            BytecodeLocal preparedCall,
            BytecodeLocal childResult,
            BytecodeLocal resumeValue) {
        if (isDirectNumericOperand(expression)) {
            builder.beginStoreLocal(target);
            emitPrimitiveNumericOperandValue(
                    builder, expression, preparedCall, childResult, resumeValue);
            builder.endStoreLocal();
            return;
        }
        emitComposedNumericOperandToLocal(
                builder, expression, target, preparedCall, childResult, resumeValue);
    }

    /* whether an operand is emitted straight onto the operand stack. */
    private boolean isDirectNumericOperand(CanonicalExpression expression) {
        return i115DirectUnaryOperand(expression)
                || i115DirectUnaryCall(expression);
    }

    /**
     * (operand emission): emits exactly one value of a primitive
     * numeric operand directly onto the Bytecode DSL stack, with no
     * destination local. Literals load their carrier, lexical reads load
     * their binding, a nested numeric operator opens its own operation, and
     * only an expression requiring real composition goes through the
     * existing continuation storage followed by a single load.
     */
    private void emitPrimitiveNumericOperandValue(
            ProtosBytecodeRootNodeGen.Builder builder,
            CanonicalExpression expression,
            BytecodeLocal preparedCall,
            BytecodeLocal childResult,
            BytecodeLocal resumeValue) {
        if (i115DirectUnaryCall(expression)) {
            emitComposedDirectUnaryValue(
                    builder,
                    () -> emitI115DirectUnaryValue(builder, expression, true),
                    childResult, resumeValue, true);
            return;
        }
        if (expression instanceof CanonicalMember member
                && i115DirectUnaryOperand(member)) {
            emitI115DirectUnaryOperand(builder, member, true);
            return;
        }
        if (expression instanceof CanonicalLiteral literal) {
            Object carrier = primitiveNumberLiteralOrNull(literal);
            if (carrier != null) {
                builder.emitLoadConstant(carrier);
            } else {
                // String and other literals load their guest constant directly.
                emitLiteral(builder, literal);
            }
            return;
        }
        if (expression instanceof CanonicalLookup lookup) {
            if (isScalarLocalRead(lookup)) {
                BytecodeLocal local = currentRootFrameLocals.get(lookup.name());
                if (local == null) {
                    throw new AssertionError(
                            "admitted scalar local is missing: " + lookup.name());
                }
                builder.emitLoadLocal(local);
            } else {
                emitLookup(builder, lookup, true);
            }
            return;
        }
        if (expression instanceof CanonicalSend nested
                && primitiveNumericSendCandidate(nested)) {
            builder.beginSourceSection(
                    nested.span().startOffset(), nested.span().length());
            emitDirectNumericOperation(
                    builder, nested, preparedCall, childResult, resumeValue);
            builder.endSourceSection();
            return;
        }
        BytecodeLocal operand = builder.createLocal("numericOperand", null);
        builder.beginBlock();
        emitComposedNumericOperandToLocal(
                builder, expression, operand, preparedCall, childResult, resumeValue);
        builder.emitLoadLocal(operand);
        builder.endBlock();
    }

    /* an operand that really needs composition (a call that may suspend). */
    private void emitComposedNumericOperandToLocal(
            ProtosBytecodeRootNodeGen.Builder builder,
            CanonicalExpression expression,
            BytecodeLocal target,
            BytecodeLocal preparedCall,
            BytecodeLocal childResult,
            BytecodeLocal resumeValue) {
        if (i115DirectUnaryCall(expression)) {
            builder.beginStoreLocal(target);
            emitComposedDirectUnaryValue(
                    builder,
                    () -> emitI115DirectUnaryValue(builder, expression, true),
                    childResult, resumeValue, true);
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
        if (expression instanceof CanonicalCall nested
                && primitiveResultCall(nested)) {
            builder.beginSourceSection(
                    nested.span().startOffset(), nested.span().length());
            emitComposedCall(
                    builder, nested, target, preparedCall,
                    childResult, resumeValue, true);
            builder.endSourceSection();
            return;
        }
        emitBodyExpressionToLocal(builder, expression, target,
                preparedCall, childResult, resumeValue);
    }

    /**
     * Leave the completed result of one non-terminal numeric send on the
     * operand stack. The receiver and the argument are evaluated once onto
     * the stack and consumed by the numeric operation, which verifies the
     * selected behavior itself: a standard selection executes internally, any
     * other selection invokes the selected implementation. Its result may be
     * a suspended invocation, so it is composed with the shared continuation
     * loop before the enclosing expression consumes it; a resumption
     * continues the selected invocation and never re-evaluates the operands
     * or the operation. An ordering result must then be a canonical Boolean.
     *
     * <p>The site reuses the caller's continuation locals when it has them
     * and otherwise creates its own.
     */
    private void emitDirectNumericOperation(
            ProtosBytecodeRootNodeGen.Builder builder,
            CanonicalSend send,
            BytecodeLocal preparedCall,
            BytecodeLocal childResult,
            BytecodeLocal resumeValue) {
        noteDynamicInvocation();
        BytecodeLocal numericResult = childResult != null
                ? childResult
                : builder.createLocal("numericResult", null);
        BytecodeLocal numericResume = resumeValue != null
                ? resumeValue
                : builder.createLocal("numericResume", null);

        builder.beginBlock();

        builder.beginStoreLocal(numericResult);
        beginNumericOperation(builder, send.message(), false);
        emitPrimitiveNumericOperandValue(
                builder, send.receiver(), preparedCall, childResult, resumeValue);
        emitPrimitiveNumericOperandValue(
                builder, send.arguments().get(0), preparedCall, childResult, resumeValue);
        endNumericOperation(builder, send.message());
        builder.endStoreLocal();

        emitDispatchedContinuationComposition(builder, numericResult, numericResume);

        if (isOrderingSelector(send.message())) {
            builder.beginRequireCanonicalBooleanResult();
            builder.emitLoadLocal(numericResult);
            builder.endRequireCanonicalBooleanResult();
        } else {
            builder.emitLoadLocal(numericResult);
        }

        builder.endBlock();
    }

    /**
     * The terminal arithmetic send of a root: no guest work follows it, so a
     * suspended selected implementation leaves the root in the terminal
     * transport of the operation, which carries this call location, instead
     * of being composed here.
     */
    private void emitTerminalNumericOperation(
            ProtosBytecodeRootNodeGen.Builder builder,
            CanonicalSend send) {
        currentRootEmittedTerminalInvocation = true;
        noteDynamicInvocation();
        beginNumericOperation(builder, send.message(), true);
        emitPrimitiveNumericOperandValue(
                builder, send.receiver(), null, null, null);
        emitPrimitiveNumericOperandValue(
                builder, send.arguments().get(0), null, null, null);
        endNumericOperation(builder, send.message());
    }

    /*
     * Whether an operand of a root's final numeric send is a value emitted
     * directly onto the operand stack that can never suspend: a Long, Double
     * or String literal, a lookup admitted by the binding analysis, or an
     * admitted member read. Calls and every subexpression that must be
     * composed before delivering its value are rejected.
     */
    private boolean terminalNumericOperand(CanonicalExpression expression) {
        if (expression instanceof CanonicalLiteral literal) {
            return literal.kind() == CanonicalLiteral.Kind.STRING
                    || primitiveNumberLiteralOrNull(literal) != null;
        }
        if (expression instanceof CanonicalLookup lookup) {
            return i115DirectLookup(lookup);
        }
        return expression instanceof CanonicalMember member
                && i115DirectUnaryOperand(member);
    }

    /*
     * Whether the final numeric send of a root may use the terminal
     * transport: an arithmetic selector (an ordering result is checked after
     * completion) in a genuine source root body with direct operands.
     */
    private boolean admitsTerminalNumericSend(CanonicalSend send) {
        return !isOrderingSelector(send.message())
                && currentActivationLocal == null
                && !currentInlineClosureCallBody
                && currentNormalCallbackScalarLocals.isEmpty();
    }

    private static void beginNumericOperation(
            ProtosBytecodeRootNodeGen.Builder builder, String selector, boolean terminal) {
        switch (selector) {
            case "+" -> builder.beginNumberAdd(terminal);
            case "-" -> builder.beginNumberSubtract(terminal);
            case "*" -> builder.beginNumberMultiply(terminal);
            case "/" -> builder.beginNumberDivide(terminal);
            case "div" -> builder.beginNumberQuotient(terminal);
            case "mod" -> builder.beginNumberRemainder(terminal);
            case "<" -> builder.beginRichCompareLt();
            case "<=" -> builder.beginRichCompareLe();
            case ">" -> builder.beginRichCompareGt();
            case ">=" -> builder.beginRichCompareGe();
            default -> throw new AssertionError("not a numeric selector: " + selector);
        }
    }

    private static void endNumericOperation(
            ProtosBytecodeRootNodeGen.Builder builder, String selector) {
        switch (selector) {
            case "+" -> builder.endNumberAdd();
            case "-" -> builder.endNumberSubtract();
            case "*" -> builder.endNumberMultiply();
            case "/" -> builder.endNumberDivide();
            case "div" -> builder.endNumberQuotient();
            case "mod" -> builder.endNumberRemainder();
            case "<" -> builder.endRichCompareLt();
            case "<=" -> builder.endRichCompareLe();
            case ">" -> builder.endRichCompareGt();
            case ">=" -> builder.endRichCompareGe();
            default -> throw new AssertionError("not a numeric selector: " + selector);
        }
    }

    private void emitComposedSend(
            ProtosBytecodeRootNodeGen.Builder builder,
            CanonicalSend send,
            BytecodeLocal result,
            BytecodeLocal scratch,
            BytecodeLocal childResult,
            BytecodeLocal resumeValue) {
        emitComposedSend(builder, send, result, scratch, childResult, resumeValue, false);
    }

    private void emitComposedSend(
            ProtosBytecodeRootNodeGen.Builder builder,
            CanonicalSend send,
            BytecodeLocal result,
            BytecodeLocal scratch,
            BytecodeLocal childResult,
            BytecodeLocal resumeValue,
            boolean primitiveCarrierResult) {
        emitComposedSendUnfolded(
                builder, send, result, scratch, childResult, resumeValue, primitiveCarrierResult);
    }

    /*
     * Boolean-control selection table of the native standard selectors not,
     * and and or. Each row declares, for each canonical receiver, the
     * supplied position of the selected callback (-1: none) and the result
     * when no callback is selected. The rows restate the standard behaviors
     * of ProtosStandardBooleanProtocol (also sequenced by
     * PreparedBooleanCall); frozen root publication validates that exactly
     * those behaviors are selected for canonical receivers.
     */
    private enum NormalBooleanControl {
        NOT(0, -1, -1, Unselected.NEGATION),
        AND(1, 0, -1, Unselected.RECEIVER),
        OR(1, -1, 0, Unselected.RECEIVER);

        /** Result of a canonical receiver that selects no callback. */
        private enum Unselected { RECEIVER, NEGATION }

        private final int arity;
        private final int selectedOnTrue;
        private final int selectedOnFalse;
        private final Unselected unselected;

        NormalBooleanControl(
                int arity,
                int selectedOnTrue,
                int selectedOnFalse,
                Unselected unselected) {
            this.arity = arity;
            this.selectedOnTrue = selectedOnTrue;
            this.selectedOnFalse = selectedOnFalse;
            this.unselected = unselected;
        }

        static NormalBooleanControl of(String message) {
            return switch (message) {
                case "not" -> NOT;
                case "and" -> AND;
                case "or" -> OR;
                default -> null;
            };
        }

        /** Supplied position of the selected callback, or -1. */
        int selectedIndex(boolean receiver) {
            return receiver ? selectedOnTrue : selectedOnFalse;
        }

        Object unselectedResult(boolean receiver) {
            return switch (unselected) {
                case RECEIVER -> canonicalBoolean(receiver);
                case NEGATION -> canonicalBoolean(!receiver);
            };
        }

        static Object canonicalBoolean(boolean value) {
            return value ? Boolean.TRUE : Boolean.FALSE;
        }
    }

    /**
     * I095-D: how one canonical receiver completes an admitted site:
     * an immediate result, the selected literal callback inline in this
     * root, or the ordinary send with the already evaluated operands.
     */
    private enum NormalBooleanControlPath { IMMEDIATE, INLINE, ORDINARY }

    /**
     * I095-D: an admitted site. {@code inlineCallbacks} holds, per supplied
     * position, whether that argument is a literal Closure that some
     * reachable canonical receiver selects and whose whole activation is
     * proven unobservable ({@link #admitsNormalInlineControlBody}).
     */
    private record NormalBooleanControlSite(
            NormalBooleanControl control,
            java.util.List<Boolean> inlineCallbacks) {

        NormalBooleanControlPath path(boolean receiver) {
            int selected = control.selectedIndex(receiver);
            if (selected < 0) {
                return NormalBooleanControlPath.IMMEDIATE;
            }
            return inlineCallbacks.get(selected)
                    ? NormalBooleanControlPath.INLINE
                    : NormalBooleanControlPath.ORDINARY;
        }
    }

    /*
     * I095-C/D: a canonical receiver that does not select a callback needs
     * no proof about that argument, which may be any expression: it is
     * still evaluated, but neither validated nor invoked. A selected
     * argument runs inline only when it is a literal Closure carrying the
     * I095-B proof of the whole callback activation. A literal receiver is admitted
     * when its own path is not the ordinary send. A dynamic receiver,
     * classified at run time, is admitted when some canonical path runs a
     * callback inline or no path invokes one (not); otherwise the existing
     * prepared/B-prime lowering is kept unchanged. Spread arguments and
     * arities other than the standard one keep ordinary dispatch.
     *
     * I101-A: admission is a pure function of the canonical site and this
     * lowerer's publication snapshot, so every reparse of a group makes the
     * same decision.
     */
    /**
     * I107: the selection row of an admissible site, or {@code null}; the
     * precondition of admission ({@link #normalBooleanControlSite}). Only
     * the native standard selectors not, and and or have rows; the * control selectors ({@link ControlSelector}) are never classified here.
     */
    private NormalBooleanControl normalBooleanControlRow(
            CanonicalSend send, ControlSiteContext context) {
        NormalBooleanControl control =
                NormalBooleanControl.of(send.message());
        if (control == null
                || !standardObjectPublished
                || context.inlineCallbackFrameNative()
                || context.activationLocalPresent()
                || send.arguments().size() != control.arity
                || hasVectorArgument(send.arguments())) {
            return null;
        }
        return control;
    }

    private NormalBooleanControlSite normalBooleanControlSite(
            CanonicalSend send, ControlSiteContext context) {
        NormalBooleanControl control = normalBooleanControlRow(send, context);
        if (control == null) {
            return null;
        }

        Boolean literalReceiver = null;
        if (send.receiver() instanceof CanonicalLiteral receiver) {
            if (receiver.kind() == CanonicalLiteral.Kind.TRUE) {
                literalReceiver = Boolean.TRUE;
            } else if (receiver.kind() == CanonicalLiteral.Kind.FALSE) {
                literalReceiver = Boolean.FALSE;
            } else {
                return null;
            }
        }

        java.util.List<Boolean> inlineCallbacks =
                new java.util.ArrayList<>(control.arity);
        for (int position = 0; position < control.arity; position++) {
            boolean selectable = literalReceiver != null
                    ? control.selectedIndex(literalReceiver) == position
                    : control.selectedIndex(true) == position
                            || control.selectedIndex(false) == position;
            inlineCallbacks.add(
                    selectable
                            && send.arguments().get(position)
                                    instanceof CanonicalClosure closure
                            && admitsNormalInlineControlBody(closure, context));
        }
        NormalBooleanControlSite site = new NormalBooleanControlSite(
                control, java.util.List.copyOf(inlineCallbacks));

        if (literalReceiver != null) {
            return site.path(literalReceiver)
                            == NormalBooleanControlPath.ORDINARY
                    ? null
                    : site;
        }
        NormalBooleanControlPath onTrue = site.path(true);
        NormalBooleanControlPath onFalse = site.path(false);
        boolean inlinePath = onTrue == NormalBooleanControlPath.INLINE
                || onFalse == NormalBooleanControlPath.INLINE;
        boolean noCallback = onTrue == NormalBooleanControlPath.IMMEDIATE
                && onFalse == NormalBooleanControlPath.IMMEDIATE;
        return inlinePath || noCallback ? site : null;
    }

    /*
     * A callback created by a Closure activation captures that activation's
     * return home, so a return in the callback targets the very home a
     * return written in the creating root targets. Inline, the callback's
     * return is lowered exactly as one written in that root and reaches the
     * same home through the root's own activation, as a block inlined into
     * its home method returns locally. A callback created outside any
     * Closure activation (by a module) owns its own return home; one that
     * may observe it keeps its genuine activation.
     */
    private boolean ownsItsReturnHome(CanonicalClosure closure, ControlSiteContext context) {
        return compilationFacts.mayObserveReturnHome(closure)
                && (context.topScope() == null
                        || context.topScope().kind() != CanonicalLexicalScope.Kind.CLOSURE);
    }

    /*
     * I095-B1: admission belongs to the entire callback activation,
     * not to individual expression categories. A selected literal
     * Closure is admitted when its own lexical scope, return home and
     * context authority are unobservable; its statements are then
     * lowered through the general Bytecode DSL machinery. The argument
     * must be a literal Closure, so omitting its physical
     * materialization cannot suppress an argument expression's effects.
     *
     * A failed proof retains the established B-prime path.
     */
    private boolean admitsNormalInlineControlBody(
            CanonicalClosure closure, ControlSiteContext context) {
        if (!closure.parameters().isEmpty()
                || ownsItsReturnHome(closure, context)) {
            return false;
        }

        /*
         * an inline callback keeps its own bindings in plain locals of
         * the physical root. A callback owning a cell binding (captured by a
         * Closure nested in it) stays a genuine Closure, whose root creates
         * that cell.
         */
        CanonicalLexicalScope wholeTreeScope =
                context.analysis() == null
                        ? null
                        : context.analysis().scopeOf(closure).orElse(null);
        if (wholeTreeScope != null
                && !wholeTreeScope.cellNamesInDeclarationOrder().isEmpty()) {
            return false;
        }

        // Deliberately isolated: the own-binding and direct-parent-capture
        // proofs below compare it against the current root's analysis.
        CanonicalBindingAnalysis analysis =
                compilationFacts.isolatedClosureAnalysis(closure);
        CanonicalLexicalScope scope =
                analysis.scopeOf(closure).orElseThrow();

        java.util.Set<String> scalarNames = scalarLocalNamesForRoot(
                closure.body(), closure, analysis, scope);
        boolean ownScalarBindings =
                !scope.declaredNames().isEmpty()
                        && scalarNames.equals(scope.declaredNames());
        boolean noOwnBindingsAndProvenCaptures =
                scope.declaredNames().isEmpty()
                        && analysis.admitsDirectParentCaptures(
                                context.analysis(),
                                context.topScope(),
                                context.frameNames(),
                                context.hasFrameLayout());

        boolean mixedOwnBindingsAndParentCaptures =
                !scope.declaredNames().isEmpty()
                        && admitsNormalMixedCallbackOwnCreations(
                                closure.body(), analysis, scope)
                        && analysis.admitsOwnBindingsAndDirectParentCaptures(
                                context.analysis(),
                                scope,
                                context.topScope(),
                                context.frameNames(),
                                context.hasFrameLayout());

        boolean requiresPersistentAuthority =
                CanonicalCompilationFacts.inlineBodyRequiresPersistentFrameAuthority(
                        closure, analysis, scope, inlinePolicyUnder(context));
        boolean admitted = (ownScalarBindings
                        || noOwnBindingsAndProvenCaptures
                        || mixedOwnBindingsAndParentCaptures)
                && !requiresPersistentAuthority;
        return admitted;
    }

    /**
     * I095-B6b: prove that every own binding is created once, at top level,
     * before any admitted own read or write. The isolated binding analysis
     * supplies presence proof for reads/writes; the exact create-site count
     * excludes hidden creations inside an operand. Direct owner creation and
     * assignment deliberately remain scalar-value-shaped in this slice.
     * All other statements can use the existing general body lowering.
     */
    private static boolean admitsNormalMixedCallbackOwnCreations(
            CanonicalSequence body,
            CanonicalBindingAnalysis analysis,
            CanonicalLexicalScope scope) {
        return admitsNormalMixedCallbackOwnCreations(
                body, analysis, scope, java.util.Set.of());
    }

    /**
     * The same proof for a callback whose {@code boundParameters} are
     * already PRESENT on entry: they count as established before the first
     * statement and are never created by the body.
     */
    private static boolean admitsNormalMixedCallbackOwnCreations(
            CanonicalSequence body,
            CanonicalBindingAnalysis analysis,
            CanonicalLexicalScope scope,
            java.util.Set<String> boundParameters) {
        java.util.LinkedHashSet<String> established =
                new java.util.LinkedHashSet<>(boundParameters);
        int createSites = 0;
        for (CanonicalExpression expression : body.expressions()) {
            if (expression instanceof CanonicalCreate create) {
                if (create.target().isPresent()
                        || !admittedInlineCreationValue(create.value())
                        || analysis.identityOf(create)
                                .map(identity -> identity.owner() != scope
                                        || !identity.name().equals(create.name()))
                                .orElse(true)
                        || !established.add(create.name())) {
                    return false;
                }
                createSites++;
                continue;
            }
            if (expression instanceof CanonicalMultipleCreate create) {
                java.util.List<CanonicalBindingIdentity> identities =
                        analysis.identitiesOf(create).orElse(null);
                if (scope.observesContext()
                        || identities == null
                        || identities.size() != create.names().size()
                        || !admittedInlineCreationValue(create.value())) {
                    return false;
                }
                for (int index = 0; index < identities.size(); index++) {
                    CanonicalBindingIdentity identity = identities.get(index);
                    if (identity.owner() != scope
                            || !identity.name().equals(create.names().get(index))
                            || !established.add(identity.name())) {
                        return false;
                    }
                }
                continue;
            }
            if (expression instanceof CanonicalAssign assign) {
                if (assign.target().isPresent()
                        || containsMutationInMixedInlineOperand(assign.value())) {
                    return false;
                }
                if (scope.declaresName(assign.name())
                        && !(assign.value() instanceof CanonicalLiteral
                                || (assign.value() instanceof CanonicalLookup read
                                        && readsEstablishedScalar(
                                                read, analysis, scope, established)))) {
                    return false;
                }
                continue;
            }
            if (containsMutationInMixedInlineOperand(expression)) {
                return false;
            }
        }
        return !established.isEmpty()
                && established.equals(scope.declaredNames())
                && analysis.lexicalCreateSiteCount() == createSites;
    }

    /** Values using existing local, identity and numeric operations without nested bindings. */
    private static boolean admittedInlineCreationValue(CanonicalExpression expression) {
        return switch (expression) {
            case CanonicalLiteral ignored -> true;
            case CanonicalLookup ignored -> true;
            case CanonicalIntrinsic intrinsic -> intrinsic.kind() == CanonicalIntrinsic.Kind.THIS;
            case CanonicalIdentity identity -> admittedInlineCreationValue(identity.left())
                    && admittedInlineCreationValue(identity.right());
            case CanonicalNotIdentity identity -> admittedInlineCreationValue(identity.left())
                    && admittedInlineCreationValue(identity.right());
            case CanonicalSend send -> !send.contextual()
                    && send.arguments().size() == 1
                    && java.util.Set.of("+", "-", "*", "/", "<", "<=", ">", ">=")
                            .contains(send.message())
                    && admittedInlineCreationValue(send.receiver())
                    && admittedInlineCreationValue(send.arguments().get(0));
            default -> false;
        };
    }

    /** Reject nested binding mutations; ordinary side-effectful sends stay allowed. */
    private static boolean containsMutationInMixedInlineOperand(
            CanonicalExpression expression) {
        return switch (expression) {
            case CanonicalCreate ignored -> true;
            case CanonicalMultipleCreate ignored -> true;
            case CanonicalAssign ignored -> true;
            case CanonicalIndexedAssign ignored -> true;
            case CanonicalClosure ignored -> true;
            case CanonicalObject ignored -> true;
            case CanonicalCompose ignored -> true;
            case CanonicalSequence sequence ->
                    sequence.expressions().stream()
                            .anyMatch(CanonicalToBytecodeLowerer::containsMutationInMixedInlineOperand);
            case CanonicalCall call ->
                    containsMutationInMixedInlineOperand(call.receiver())
                            || call.arguments().stream().anyMatch(
                                    CanonicalToBytecodeLowerer::containsMutationInMixedInlineOperand);
            case CanonicalSend send ->
                    containsMutationInMixedInlineOperand(send.receiver())
                            || send.arguments().stream().anyMatch(
                                    CanonicalToBytecodeLowerer::containsMutationInMixedInlineOperand);
            case CanonicalSuperSend send ->
                    send.arguments().stream().anyMatch(
                            CanonicalToBytecodeLowerer::containsMutationInMixedInlineOperand);
            case CanonicalMember member ->
                    containsMutationInMixedInlineOperand(member.receiver());
            case CanonicalIdentity identity ->
                    containsMutationInMixedInlineOperand(identity.left())
                            || containsMutationInMixedInlineOperand(identity.right());
            case CanonicalNotIdentity identity ->
                    containsMutationInMixedInlineOperand(identity.left())
                            || containsMutationInMixedInlineOperand(identity.right());
            case CanonicalDerivedInequality inequality ->
                    containsMutationInMixedInlineOperand(inequality.left())
                            || containsMutationInMixedInlineOperand(inequality.right());
            case CanonicalMapConstruction map ->
                    containsMutationInMixedInlineOperand(map.factory())
                            || map.entries().stream().anyMatch(entry ->
                                    containsMutationInMixedInlineOperand(entry.key())
                                            || containsMutationInMixedInlineOperand(entry.value()));
            case CanonicalReturn result ->
                    containsMutationInMixedInlineOperand(result.value());
            case CanonicalSpread spread ->
                    containsMutationInMixedInlineOperand(spread.expression());
            case CanonicalNamedArgument ignored -> true;
            case CanonicalIntrinsic ignored -> false;
            case CanonicalLiteral ignored -> false;
            case CanonicalLookup ignored -> false;
        };
    }

    /**
     * Store the result of one direct numeric send.
     */
    private void emitNumericSend(
            ProtosBytecodeRootNodeGen.Builder builder,
            CanonicalSend send,
            BytecodeLocal result,
            BytecodeLocal scratch,
            BytecodeLocal childResult,
            BytecodeLocal resumeValue,
            boolean primitiveCarrierResult) {
        builder.beginStoreLocal(result);
        emitDirectNumericSendValue(
                builder, send, scratch, childResult, resumeValue, primitiveCarrierResult);
        builder.endStoreLocal();
    }

    /**
     * the value of a direct numeric send left on the stack, with the
     * carrier materialized only when the consumer requires a guest value.
     */
    private void emitDirectNumericSendValue(
            ProtosBytecodeRootNodeGen.Builder builder,
            CanonicalSend send,
            BytecodeLocal scratch,
            BytecodeLocal childResult,
            BytecodeLocal resumeValue,
            boolean primitiveCarrierResult) {
        emitDirectNumericOperation(
                builder, send, scratch, childResult, resumeValue);
    }

    /**
     * Store one direct arithmetic result without changing its semantic representation.
     */
    private void emitArithmeticSend(
            ProtosBytecodeRootNodeGen.Builder builder,
            CanonicalSend send,
            BytecodeLocal result,
            BytecodeLocal scratch,
            BytecodeLocal childResult,
            BytecodeLocal resumeValue) {
        builder.beginStoreLocal(result);
        emitDirectNumericOperation(builder, send, scratch, childResult, resumeValue);
        builder.endStoreLocal();
    }

    /**
     * Lower a send through the current specialized operations and shared callable dispatcher.
     */
    private void emitComposedSendUnfolded(
            ProtosBytecodeRootNodeGen.Builder builder,
            CanonicalSend send,
            BytecodeLocal result,
            BytecodeLocal scratch,
            BytecodeLocal childResult,
            BytecodeLocal resumeValue,
            boolean primitiveCarrierResult) {
        if (send.contextual() && !hasNamedArgument(send.arguments())) {
            emitContextualControl(
                    builder, send, result,
                    scratch, childResult, resumeValue,
                    primitiveCarrierResult);
            return;
        }
        if (indexedProtocolSend(send) || isIndexedSizeSend(send)) {
            emitIndexedSend(
                    builder, send, result, scratch, childResult, resumeValue,
                    this::emitBodyExpressionToLocal, primitiveCarrierResult);
            return;
        }

        if (primitiveNumericSendCandidate(send)) {
            switch (send.message()) {
                case "+", "-", "*" -> emitArithmeticSend(
                        builder, send, result, scratch, childResult, resumeValue);
                default -> emitNumericSend(
                        builder, send, result, scratch, childResult, resumeValue,
                        primitiveCarrierResult);
            }
            return;
        }
        CanonicalClosure eachCallback = inlineArrayEachCallback(send);
        if (eachCallback != null) {
            emitArrayEachSend(
                    builder, send, eachCallback, result, scratch, childResult, resumeValue);
            return;
        }
        CanonicalClosure associationCallback = inlineAssociationEachCallback(send);
        if (associationCallback != null) {
            emitAssociationEachSend(
                    builder, send, associationCallback, result, scratch, childResult, resumeValue);
            return;
        }
        emitDispatchedInvocation(
                builder, InvocationShape.SEND, send.receiver(), send.message(), send.arguments(),
                result, scratch, childResult, resumeValue,
                this::emitBodyExpressionToLocal, this::emitBodySpreadArgumentVector,
                primitiveCarrierResult);
        if (isOrderingSelector(send.message()) && result != null) {
            builder.beginStoreLocal(result);
            builder.beginRequireCanonicalBooleanResult();
            builder.emitLoadLocal(result);
            builder.endRequireCanonicalBooleanResult();
            builder.endStoreLocal();
        }
    }

    /**
     * The literal Closure argument of an {@code each} send whose callback
     * activation may be eliminated on the standard Array path, or {@code
     * null}. Only the shape of the site is decided here; whether the
     * standard behaviors are actually selected is observed at run time.
     */
    /*
     * The literal two-parameter callback of an {@code each} send that the
     * standard Map, IdentityMap or Environment iteration may run inline, or
     * {@code null}; the selection itself is observed at run time.
     */
    private CanonicalClosure inlineAssociationEachCallback(CanonicalSend send) {
        if (!"each".equals(send.message())
                || send.contextual()
                || send.arguments().size() != 1
                || hasVectorArgument(send.arguments())
                || hasNamedArgument(send.arguments())
                || !(send.arguments().get(0) instanceof CanonicalClosure closure)) {
            return null;
        }
        return admitsEachInlineCallback(closure, currentControlSiteContext(), 2)
                ? closure : null;
    }

    private CanonicalClosure inlineArrayEachCallback(CanonicalSend send) {
        if (!"each".equals(send.message())
                || send.contextual()
                || send.arguments().size() != 1
                || hasVectorArgument(send.arguments())
                || hasNamedArgument(send.arguments())
                || !(send.arguments().get(0) instanceof CanonicalClosure closure)) {
            return null;
        }
        return admitsArrayEachInlineCallback(closure, currentControlSiteContext())
                ? closure : null;
    }

    /**
     * Whether the activation of a literal {@code each} callback is
     * unobservable, so its body may run in this physical root once per
     * element. The single ordinary parameter and every own creation must be
     * LOCAL bindings of the callback scope proven by the binding analysis;
     * every other reference must be a proven capture of this root (or a
     * module read). A CELL binding, a nested Closure or other escape, an
     * observable Context, an observable ReturnHome, or any persistent frame
     * authority keeps the genuine Closure invocation.
     */
    private boolean admitsArrayEachInlineCallback(
            CanonicalClosure closure, ControlSiteContext context) {
        return admitsEachInlineCallback(closure, context, 1);
    }

    /*
     * The admission of an inline {@code each} callback with {@code arity}
     * ordinary parameters, each a LOCAL binding of the callback scope that
     * the iteration stores directly before every visit.
     */
    private boolean admitsEachInlineCallback(
            CanonicalClosure closure, ControlSiteContext context, int arity) {
        if (closure.parameters().size() != arity
                || ownsItsReturnHome(closure, context)) {
            return false;
        }
        for (CanonicalParameter parameter : closure.parameters()) {
            if (parameter.rest() || parameter.defaultValue().isPresent()) {
                return false;
            }
        }
        CanonicalLexicalScope wholeTreeScope =
                context.analysis() == null
                        ? null
                        : context.analysis().scopeOf(closure).orElse(null);
        if (wholeTreeScope != null
                && !wholeTreeScope.cellNamesInDeclarationOrder().isEmpty()) {
            return false;
        }

        CanonicalBindingAnalysis analysis =
                compilationFacts.isolatedClosureAnalysis(closure);
        CanonicalLexicalScope scope = analysis.scopeOf(closure).orElse(null);
        if (scope == null) {
            return false;
        }
        java.util.Set<String> parameterNames = new java.util.LinkedHashSet<>();
        for (CanonicalParameter parameter : closure.parameters()) {
            CanonicalBindingIdentity identity =
                    analysis.identityOf(parameter).orElse(null);
            if (identity == null
                    || identity.owner() != scope
                    || CanonicalBindingAnalysis.storageOf(identity)
                            != CanonicalLexicalScope.Storage.LOCAL) {
                return false;
            }
            parameterNames.add(identity.name());
        }
        return admitsNormalMixedCallbackOwnCreations(
                        closure.body(), analysis, scope, parameterNames)
                && analysis.admitsParameterBindingsAndDirectParentCaptures(
                        context.analysis(),
                        scope,
                        context.topScope(),
                        context.frameNames(),
                        context.hasFrameLayout())
                && !CanonicalCompilationFacts.inlineBodyRequiresPersistentFrameAuthority(
                        closure, analysis, scope, inlinePolicyUnder(context));
    }

    /**
     * An admitted {@code each} send of a literal callback.
     *
     * <ol>
     *   <li>The receiver is evaluated exactly once into a local.
     *   <li>{@code IsStandardArrayEachSelection} observes, invoking nothing,
     *       whether {@code each} selects the standard Array behavior and the
     *       root {@code call} selection is canonical. Otherwise the literal
     *       argument is materialized before GetMethod selects {@code each},
     *       preserving the ordinary send's argument-first selection order.
     *   <li>Standard: evaluating the literal argument has no observable
     *       effect and its activation is proven unobservable, so no Closure
     *       is created. {@code GetArrayEachSnapshot} establishes the single
     *       snapshot; a While loop advances it with {@code ArrayEachNext},
     *       which stores each element into the parameter local, and runs the
     *       callback body inline with its other bindings reset to ABSENT.
     *       The result is the original receiver.
     *   <li>Otherwise: the literal argument is evaluated exactly once
     *       (materializing the genuine Closure) and the selected behavior is
     *       invoked by {@code CallUnaryMethod}, composed with
     *       Yield/ResumeDispatchedContinuation.
     * </ol>
     */
    private void emitArrayEachSend(
            ProtosBytecodeRootNodeGen.Builder builder,
            CanonicalSend send,
            CanonicalClosure callback,
            BytecodeLocal result,
            BytecodeLocal scratch,
            BytecodeLocal childResult,
            BytecodeLocal resumeValue) {
        if (result == null || scratch == null || childResult == null || resumeValue == null) {
            throw new AssertionError("each send result/scratch locals were not allocated");
        }
        OperandStager stager = this::emitBodyExpressionToLocal;
        // The generic selection observes the Prelude, like GetMethod.
        currentRootMayRequirePrelude = true;

        builder.beginTag(StandardTags.CallTag.class);
        builder.beginBlock();

        BytecodeLocal receiver = builder.createLocal("eachReceiver", null);
        stageOperand(builder, send.receiver(), receiver,
                scratch, childResult, resumeValue, stager);

        BytecodeLocal standard = builder.createLocal("eachStandard", null);
        builder.beginStoreLocal(standard);
        builder.beginIsStandardArrayEachSelection();
        builder.emitLoadLocal(receiver);
        builder.endIsStandardArrayEachSelection();
        builder.endStoreLocal();

        builder.beginIfThenElse();
        builder.emitLoadLocal(standard);

        builder.beginBlock();
        BytecodeLocal snapshot = builder.createLocal("eachSnapshot", null);
        builder.beginStoreLocal(snapshot);
        builder.beginGetArrayEachSnapshot();
        builder.emitLoadLocal(receiver);
        builder.endGetArrayEachSnapshot();
        builder.endStoreLocal();

        String parameter = callback.parameters().get(0).name();
        BytecodeLocal element = builder.createLocal(
                NORMAL_INLINE_CALLBACK_BINDING_LOCAL_PREFIX + parameter, nextInlineCallbackRegion + 1);
        builder.beginWhile();
        builder.beginArrayEachNext(element);
        builder.emitLoadLocal(snapshot);
        builder.endArrayEachNext();
        emitInlineControlClosureBody(
                builder, callback, InlineBodyResult.DISCARD, null,
                scratch, childResult, resumeValue, false,
                java.util.Map.of(parameter, element));
        builder.endWhile();

        builder.beginStoreLocal(result);
        builder.emitLoadLocal(receiver);
        builder.endStoreLocal();
        builder.endBlock();

        builder.beginBlock();
        BytecodeLocal argument = builder.createLocal("invocationArgument", null);
        stageOperand(builder, callback, argument,
                scratch, childResult, resumeValue, stager);

        // D237: form the argument packet before selecting the ordinary method.
        BytecodeLocal callable = builder.createLocal("eachMethod", null);
        emitGetMethod(builder, "each", receiver, callable);
        emitDispatchedCall(
                builder, callable, java.util.List.of(argument), null,
                childResult, result, resumeValue, false);
        builder.endBlock();

        builder.endIfThenElse();

        builder.endBlock();
        builder.endTag(StandardTags.CallTag.class);
    }

    /*
     * An admitted {@code each} send of a literal two-parameter callback on a
     * Map, IdentityMap or Environment: the receiver once, the standard
     * selection observed without invoking anything, then one association
     * snapshot whose keys and values the iteration stores into the callback's
     * parameter locals before running its body inline; the result is the
     * receiver. Any other selection invokes the selected method with the
     * genuine Closure.
     */
    private void emitAssociationEachSend(
            ProtosBytecodeRootNodeGen.Builder builder,
            CanonicalSend send,
            CanonicalClosure callback,
            BytecodeLocal result,
            BytecodeLocal scratch,
            BytecodeLocal childResult,
            BytecodeLocal resumeValue) {
        if (result == null || scratch == null || childResult == null || resumeValue == null) {
            throw new AssertionError("each send result/scratch locals were not allocated");
        }
        OperandStager stager = this::emitBodyExpressionToLocal;
        // The generic selection observes the Prelude, like GetMethod.
        currentRootMayRequirePrelude = true;

        builder.beginTag(StandardTags.CallTag.class);
        builder.beginBlock();

        BytecodeLocal receiver = builder.createLocal("eachReceiver", null);
        stageOperand(builder, send.receiver(), receiver,
                scratch, childResult, resumeValue, stager);

        BytecodeLocal standard = builder.createLocal("eachStandard", null);
        builder.beginStoreLocal(standard);
        builder.beginIsStandardAssociationEachSelection();
        builder.emitLoadLocal(receiver);
        builder.endIsStandardAssociationEachSelection();
        builder.endStoreLocal();

        builder.beginIfThenElse();
        builder.emitLoadLocal(standard);

        builder.beginBlock();
        BytecodeLocal snapshot = builder.createLocal("eachSnapshot", null);
        builder.beginStoreLocal(snapshot);
        builder.beginGetAssociationEachSnapshot();
        builder.emitLoadLocal(receiver);
        builder.endGetAssociationEachSnapshot();
        builder.endStoreLocal();

        String keyName = callback.parameters().get(0).name();
        String valueName = callback.parameters().get(1).name();
        BytecodeLocal key = builder.createLocal(
                NORMAL_INLINE_CALLBACK_BINDING_LOCAL_PREFIX + keyName, nextInlineCallbackRegion + 1);
        BytecodeLocal value = builder.createLocal(
                NORMAL_INLINE_CALLBACK_BINDING_LOCAL_PREFIX + valueName, nextInlineCallbackRegion + 1);
        builder.beginWhile();
        builder.beginAssociationEachNext(key, value);
        builder.emitLoadLocal(snapshot);
        builder.endAssociationEachNext();
        emitInlineControlClosureBody(
                builder, callback, InlineBodyResult.DISCARD, null,
                scratch, childResult, resumeValue, false,
                java.util.Map.of(keyName, key, valueName, value));
        builder.endWhile();

        builder.beginStoreLocal(result);
        builder.emitLoadLocal(receiver);
        builder.endStoreLocal();
        builder.endBlock();

        builder.beginBlock();
        BytecodeLocal argument = builder.createLocal("invocationArgument", null);
        stageOperand(builder, callback, argument,
                scratch, childResult, resumeValue, stager);

        // D237: form the argument packet before selecting the ordinary method.
        BytecodeLocal callable = builder.createLocal("eachMethod", null);
        emitGetMethod(builder, "each", receiver, callable);
        emitDispatchedCall(
                builder, callable, java.util.List.of(argument), null,
                childResult, result, resumeValue, false);
        builder.endBlock();

        builder.endIfThenElse();

        builder.endBlock();
        builder.endTag(StandardTags.CallTag.class);
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
                    containsClosure(send.receiver())
                            || anyContainsClosure(send.arguments());
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
            case CanonicalNamedArgument named ->
                    containsClosure(named.expression());
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
            ProtosBytecodeRootNodeGen.Builder builder,
            CanonicalCall call,
            BytecodeLocal result,
            BytecodeLocal scratch,
            BytecodeLocal childResult,
            BytecodeLocal resumeValue) {
        emitComposedCall(builder, call, result, scratch, childResult, resumeValue, false);
    }

    private void emitComposedCall(
            ProtosBytecodeRootNodeGen.Builder builder,
            CanonicalCall call,
            BytecodeLocal result,
            BytecodeLocal scratch,
            BytecodeLocal childResult,
            BytecodeLocal resumeValue,
            boolean primitiveCarrierResult) {
        emitDispatchedInvocation(
                builder, InvocationShape.CALL, call.receiver(), null, call.arguments(),
                result, scratch, childResult, resumeValue,
                this::emitBodyExpressionToLocal, this::emitBodySpreadArgumentVector,
                primitiveCarrierResult);
    }


    /*
     * I091: a literal is a guest constant unless it is an Integer outside the signed-64 range,
     * which is an ordinary guest object minted with the executing Prelude on each evaluation.
     * The constant of a signed-64 Integer or Float literal is immutable, carries no prototype,
     * and compares by value, so one constant serves every execution domain.
     */
    private void emitLiteral(
            ProtosBytecodeRootNodeGen.Builder builder, CanonicalLiteral literal) {
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
            case TRUE -> Boolean.TRUE;
            case FALSE -> Boolean.FALSE;
            case NULL -> ProtosNullValue.INSTANCE;
        };
    }

    /*
     * attribute names reach DynamicObject property nodes, whose caches
     * compare keys by identity; canonicalize them once, at bytecode build
     * time, never per access.
     */
    private static String attributeName(String name) {
        return name.intern();
    }
}
