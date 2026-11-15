# Protos Implementation Changelog

Current implementation-series changes are kept in this file.

Historical implementation changelogs:

- [0.3.x](changelog/CHANGELOG-0.3.md)
- [0.2.x](changelog/CHANGELOG-0.2.md)
- [0.1.x](changelog/CHANGELOG-0.1.md)

## 0.4.127-SNAPSHOT

- **I115 — Fused numeric operations with internal selection and overloaded dispatch.**
  - Lowering: every numeric operator send (`+`, `-`, `*`, `/`, `div`, `mod`, `<`, `<=`, `>`, `>=`) is one fused operation. The separate `IsStandardNumericSelection` guard and the `GetMethod`/`CallUnaryMethod` fallback are no longer emitted. The operation selects the behavior once both operands are evaluated (D237), runs the standard behavior internally, and otherwise invokes the selected method. Its result is composed with the shared continuation loop, so a resumption never re-evaluates the operands or repeats the invocation. An ordering result must be a canonical Boolean. A root's final numeric send with direct operands may use the terminal transport.
  - Numeric nodes: `NumberAdd`, `NumberSubtract`, `NumberMultiply`, `NumberDivide` and `RichCompareLt/Le/Gt/Ge` keep their `long`/`double` primitive specializations. Their generic specialization classifies the selection through the new `ProtosNumericSelectionNode` and dispatches a nonstandard selection through the new `ProtosNumericOverloadNode`. Their `doGeneric` binds its node explicitly (`@Bind("$node")`).
  - Selection: `ProtosNumericSelectionNode` caches per selector, Prelude identity and selection origin. `long` and `double` receivers have typed entries that resolve the Prelude's FROZEN `SmallInteger`/`Float` binding when the entry is installed. A fully FROZEN chain caches its classification. A mutable chain is guarded by the structural selection and by the selected location's final-value assumption. When the running compact invocation's own Closure captured its Prelude, it is read from the Closure (`ProtosFrameArguments.capturedClosurePreludeOrNull`) without the general Prelude resolution; a published activation or a Closure without a captured Prelude falls back to the general entries, keeping caller inheritance.
  - Observable change for tooling: with an attached debugger, a numeric operator site no longer carries a `CallTag`, since no separate send is emitted for it.
  - Gates: `tools/java_bytecode_api_pe_baseline.json` records the eight proven `BytecodeNode.get` sites of the fused `doGeneric` specializations. `tools/java_generated_bytecode_bci_pe_baseline.json` follows the larger encoding of the numeric instructions.
  - Tests: new `ProtosI115NumericOverloadContinuationTest`, `ProtosI115DirectAdditionStructureExperimentTest` and `ProtosI115Tier1RunGraphTest` (also reports `I115_TIER1_PRELUDE_FIELD_LOADS`), plus `tools/i115_tier1_run_graph.sh`. `ProtosPerf040E1NumericOperationReconciliationTest` and `ProtosI115StringAdditionTest` add Prelude alternation across Closures and operators, retroactive-freezing checks, and exact counts of invocations of a redefined `+`. The structural lowering tests now expect fused numeric sites with no separate guard, fallback send or `CallTag`.

## 0.4.126-SNAPSHOT

- **I167 — Protos-first module import promoted to production (PLAT075).**
  - Core: `import.call` is now the source-backed Closure of `protos/lib/core/import.protos` in every Prelude. It admits exact semantic Strings via `String.recognizes`, then resolves, performs an authorized Actor-local cache lookup, and on a miss calls `begin` and the structured `execute` of a private bootstrap-local bridge. The bridge is never a Prelude binding or a slot of `import`; `import` stays a frozen Core object with only `call`.
  - Bootstrap: `ProtosCoreBootstrap` creates the Process `ProtosModuleRuntime` before loading `import.protos`, installs the private bridge, validates and freezes the source-created facility without replacing `call`, and binds the runtime once to the Prelude's private association (`ProtosPrelude.bindModuleRuntimeForRuntime`). The experimental bootstrap seam and the `sourceBackedImportPolicy` mode were removed.
  - Import protocol: removed the native `StandardImportBody` and its recognition helpers (`runtimeForImplementation`, `selectedRuntimeForBytecodeIntrinsic`). The bridge keeps opaque single-use tokens with runtime, Actor, execution-domain and Task checks; `begin` inserts no record, and the claimed `execute` inserts INITIALIZING immediately before source initialization or FOREIGN acquisition. The only import specialization is exact post-selection recognition of the private `execute` implementation, which runs through the existing C-prime module-initialization child.
  - Module runtime: removed the obsolete native-policy entry points `prepareBytecodeImport`, `prepareBytecodeImportForTask`, `prepareBytecodeImportWithTask` and `importModule`. Shared preparation, authorization-before-cache, MODULE/SOURCE frames, FOREIGN acquisition, internal host loading and exact-record eviction are unchanged.
  - Tests: `ProtosPlat075ProtosFirstImportFeasibilityTest` now exercises the production bootstrap; `ProtosModuleRuntimeTest` and `ProtosCoreNativeBoundaryArchitectureTest` require a source-backed `import.call` (the import protocol now provides the four private bridge Closures); `module-source-separation.protos` adds cases for non-String specifiers, String impostors, local `import` shadowing and repeated canonical identity.

## 0.4.125-SNAPSHOT

- **I115 — Task-presence profiling on direct Closure calls and verified recompilation in the semantic performance gate.**
  - Calls: `prepareInvocationTask` profiles whether an inherited Task is present with an `InlinedConditionProfile`, threaded through `invokeUnobservableHome`, `invokeCapturedHome` and the direct/indirect invoke nodes of `ProtosFunctionCallDispatchers`, `ProtosCallUnaryMethodNode` and `ProtosExperimentalCallBinaryMethodNode`. Task inheritance and caller provenance are unchanged.
  - Gate: `tools/i115_semantic_performance_gate.py` `parse_trace` now accepts a recompilation of a root at the requested tier only when the trace proves the same physical CallTarget was invalidated (`opt deopt … |Invalidated true|`) between the two compilations, with no intervening same-tier compilation. The latest compilation is measured; replaced compilations are kept as `invalidated_compilations` provenance. A recompilation on another CallTarget (`RECOMPILATION_TARGET_AMBIGUOUS`), without verified invalidation (`RECOMPILATION_UNVERIFIED`), or an invalidated latest compilation (`LATEST_COMPILATION_INVALIDATED`) fails the gate.
  - Tests: `tools/tests/test_i115_local_gate.py` adds coverage for verified, cross-target, unverified, tier-interleaved and invalidated-latest recompilations; the baseline-mapping and probe-build gate tests follow the revision-keyed baseline tier counts.

## 0.4.124-SNAPSHOT

- **PERF040 — Residual lexical lookup retirement and standard numeric selection caching.**
  - Lexical lookup: removed `ProtosLexicalFallback`, the second String-keyed implementation of bare-name read and write-target selection. `ProtosLexicalSelectionNode` (Bytecode DSL) is the only runtime implementation; the debugger scope dropped its unused import. No behavior change.
  - Lowering: `I115InlineStoragePlan` renamed `CanonicalInlineStoragePlan` (nominal only; capture analysis, materialization and `BytecodeLocal`/`CELL` selection unchanged).
  - Numeric selection: `ProtosValueLookup.StructuralSelection` gains `allOwnersFrozen()`, and its `isValid()` Shape guard is `@NonIdempotent`. The `cached` specialization of `IsStandardNumericSelection` caches the standard classification only when every object of the selected chain is FROZEN; a chain that is not fully FROZEN still reads and classifies the selected behavior on every execution. Prelude and prototype identity and Shape validity still guard the cache, and the Prelude read is `@NonIdempotent`. `NumberAdd`, operand evaluation order and the generic route are unchanged.
  - Tests: `ProtosExecutionContextTest` moved and rewritten as `ProtosLexicalSelectionNodeTest`, exercising the generated selection node for reads, write targets assigned through `assignResolvedLexicalTarget`, and `SlotNotFound` Errors. The tests that used the removed helper now use the selection node or direct binding reads. `ProtosPerf040E1NumericOperationReconciliationTest` adds coverage for repeated standard `+`, mutable-prototype value and structural changes, Prelude isolation, overflow continuity, alternating numeric domains, and propagation of nonstandard selections and Errors.
  - Performance: the effect on the `primitive-integer-add` benchmark is not yet established by published benchmark results.

## 0.4.123-SNAPSHOT

- **I115 — Task failure containment, P capture isolation, package reorganization and source cleanup.**
  - Tasks: an unexpected Java `RuntimeException`/`Error` raised while a Task resumes no longer strands it. The Task is terminalized fail-closed: it is detached from its wait/capture dependencies, structured children are cancelled and drained before the parent publishes `FAILED`, the owning Actor is failed through its existing fatal-failure authority, and the original Throwable is rethrown to the host caller with any cleanup failures attached as suppressed exceptions. A pending Future of such a Task is cancelled instead of receiving a fabricated guest Error.
  - Parallel (P) projections no longer share the source Actor's captured lexical cells. A projection keeps the captured-cell layout but every cell is a fresh discarded placeholder: proven-present reads signal `SlotNotFound`, checked reads continue lexical lookup in the new P root, and a direct store to a discarded cell signals the uninitialized-cell error. This closes the previously recorded I115 divergence in `ProtosClosureValue`.
  - Lowering: a lexical lookup or assignment whose access is `SELECTED` (a Context may hold a nearer dynamic binding) is no longer treated as a transported free cell, so captured assignment and Array construction observe a dynamic Context shadow. Root-level Closure definitions are created by the new `CreateDiscardedModuleBinding` operation. Terminal nilary and two-argument calls use `CallNilaryMethod` and `CallBinaryMethod`. The constant long-array lowering shortcut was removed; Array literals take the ordinary path.
  - Truffle boundaries: native builtin bodies and suspension-capable native bodies execute behind `@TruffleBoundary(transferToInterpreterOnException = false)`; the root lookup of `ProtosNumberFactory` is a cold boundary.
  - Package reorganization (no behavior change): global access nodes and trace hooks moved to `execution.bytecode.activation.globals`, Closure execution plans and call requests to `execution.bytecode.activation.plans`, and Task/C-prime/I/O execution helpers to `execution.bytecode.activation.tasks`. Boolean and numeric optimization tests moved to `optimizations.booleans` and `optimizations.numeric`; represented-value tests to `runtime.values`.
  - Source cleanup: comments and Javadoc in main and test sources no longer reference other Truffle languages (GraalPy, TruffleRuby, SimpleLanguage, GraalJS) or Python-specific terms; they describe the Protos mechanism directly. The `ProtosCallUnaryMethodNode` specialization `callPFunction` is renamed `callCachedClosure`, and generated-name references in tests and baselines follow it.
  - Diagnostics: temporary Native termination diagnostic printed by `ProtosProcessRuntime` when `I115_NATIVE_TERM_DIAG=1` (Process, Actor and Task lifecycle snapshot, re-polled every second); default behavior unchanged.
  - Native image: `scripts/native-generate-init-args.sh` initializes the generated function-call dispatcher nodes, `ProtosLexicalSelectionNode` and the new layout/mode types at build time; `scripts/native-test.sh` adds caller-recovery smoke cases (nested call, object in Closure, CLI module import).
  - Tests: added `ProtosI115UnexpectedTaskEscapeTest` and P captured-cell isolation coverage in `ProtosParallelExecutionTest`; added conformance cases for dynamic Array and captured-assignment Context shadows; updated lowering, census and PE baselines (`tools/java_*_pe_*baseline.json`) to the new emitted operations.

## 0.4.122-SNAPSHOT

- **I115-V141 — historical replay.**
  - Restore each selection ordering after operand evaluation and finalize indexed protocol emission/names. Compose nonterminal indexed consumers through Yield/ResumeDispatchedContinuation.
  - Intended semantic baseline: message syntax and selection/evaluation rules, signed-64 `long`/`Long` Integer carriers, live lexical capture and ordinary callable behavior. Internal specialized operations do not authorize new guest semantics.
  - **Historical semantic limitations and divergences:** Final historical state still requires independent functional/performance acceptance across dynamic selection, indexed protocols and suspension. D013, D179 C0, D197-C, dynamic selection, lexical captures, ReturnHome, suspension, cancellation, Futures, Task/C-prime and Actor transfer are not independently certified or declared closed by this replay. Historical code is preserved except for the explicitly authorized whitespace correction described below.
  - Tests added or modified (not executed): `ProtosI115EachSelectionOrderingTest.java`, `ProtosPerf026D1IndexedEachInlineCallbackTest.java`, `ProtosPerf026D2AssociationEachInlineCallbackTest.java`. No Protos tests, builds, Maven commands or benchmarks were run during this replay.
  - Historical file transitions: 1 added, 3 modified, 0 deleted (rename detection disabled for exact path accounting). All 4 historical path transitions preserve original modes and deletion state. Blob and parent identity checks allow only the explicitly authorized one-byte whitespace correction introduced in I115-V91: `tools/i115_gate_cases/primitive-if-true.protos:20`, original blob `b29428dbbee0f06243f105627c0a7ba472abea5a`, corrected blob `06420027434dabfae0edb537abfca37225e710aa`. All other payload blobs match exactly. Root `pom.xml` and `CHANGELOG.md` receive version/record metadata edits.
  - Original implementation: `4266299e2e5bc16489830a6d3beefabaed6b7f63`.

## 0.4.121-SNAPSHOT

- **I115-V140 — historical replay.**
  - Restore residual compiler semantics and reconcile Batch D regressions, including native arithmetic operand consumption, lazy builtin activation, instrumentation and root lexical layout.
  - Intended semantic baseline: message syntax and selection/evaluation rules, signed-64 `long`/`Long` Integer carriers, live lexical capture and ordinary callable behavior. Internal specialized operations do not authorize new guest semantics.
  - **Historical semantic limitations and divergences:** The original reconciliation claim is preserved without asserting compilation or regression success in this replay. D013, D179 C0, D197-C, dynamic selection, lexical captures, ReturnHome, suspension, cancellation, Futures, Task/C-prime and Actor transfer are not independently certified or declared closed by this replay. Historical code is preserved except for the explicitly authorized whitespace correction described below.
  - Tests added or modified (not executed): `ProtosDispatchTestSupport.java`, `ProtosI115StringAdditionTest.java`, `ProtosPerf006B6BFinalConformanceArchitectureTest.java`, `ProtosPerf025H1LazyRootActivationTest.java`, `ProtosPerf027AGuardedIntegerDeferredActivationTest.java`, `ProtosPerf032G7MinimalDirectHeaderTest.java`, `ProtosPerf006B6A2MutatingCanonicalCoverageTest.java`, `ProtosPerf025InlineCallbackPreparationTest.java`, `ProtosPerf025CompactCalleeExecutionTest.java`, `ProtosI092CompactBooleanTest.java`, `ProtosI101BArithmeticBuilderOracleTest.java`, `ProtosI107ContinuationEmissionTest.java`, `ProtosI115H7MethodCallConvergenceTest.java`, `ProtosI115NCompactBooleanTest.java`, `ProtosI115QCompactLiteralReturnTest.java`, `ProtosPerf040E1NumericOperationReconciliationTest.java`, `ProtosI115UnaryPICConvergenceTest.java`. No Protos tests, builds, Maven commands or benchmarks were run during this replay.
  - Historical file transitions: 7 added, 32 modified, 7 deleted (rename detection disabled for exact path accounting). All 46 historical path transitions preserve original modes and deletion state. Blob and parent identity checks allow only the explicitly authorized one-byte whitespace correction introduced in I115-V91: `tools/i115_gate_cases/primitive-if-true.protos:20`, original blob `b29428dbbee0f06243f105627c0a7ba472abea5a`, corrected blob `06420027434dabfae0edb537abfca37225e710aa`. All other payload blobs match exactly. Root `pom.xml` and `CHANGELOG.md` receive version/record metadata edits.
  - Original implementation: `ed42cd50cb8be355e626c44612eb8e01174bcf33`.

## 0.4.120-SNAPSHOT

- **I115-V139 — historical replay.**
  - Restore Batch C lexical tooling, terminal continuation handling, semantic traces and DAP support using physical-root tags, root-scoped locals and stored bytecode locations.
  - Intended semantic baseline: message syntax and selection/evaluation rules, signed-64 `long`/`Long` Integer carriers, live lexical capture and ordinary callable behavior. Internal specialized operations do not authorize new guest semantics.
  - **Historical semantic limitations and divergences:** Runtime tooling state remains transient; debugger, trace and continuation suites were not executed by this replay. D013, D179 C0, D197-C, dynamic selection, lexical captures, ReturnHome, suspension, cancellation, Futures, Task/C-prime and Actor transfer are not independently certified or declared closed by this replay. Historical code is preserved except for the explicitly authorized whitespace correction described below.
  - Tests added or modified (not executed): `ProtosReplLexicalObservationTest.java`, `ProtosPerf006B5BSourceInstrumentationLocationTest.java`, `ProtosI026EDebuggerIntegrationTest.java`. No Protos tests, builds, Maven commands or benchmarks were run during this replay.
  - Historical file transitions: 0 added, 23 modified, 0 deleted (rename detection disabled for exact path accounting). All 23 historical path transitions preserve original modes and deletion state. Blob and parent identity checks allow only the explicitly authorized one-byte whitespace correction introduced in I115-V91: `tools/i115_gate_cases/primitive-if-true.protos:20`, original blob `b29428dbbee0f06243f105627c0a7ba472abea5a`, corrected blob `06420027434dabfae0edb537abfca37225e710aa`. All other payload blobs match exactly. Root `pom.xml` and `CHANGELOG.md` receive version/record metadata edits.
  - Original implementation: `2b331c34b93f2dd5d320970e7ecf2f5d254f05d7`.

## 0.4.119-SNAPSHOT

- **I115-V138 — historical replay.**
  - Restore per-invocation Core numeric selection, exact arithmetic and host scalar interoperability. Keep primitive non-overflow arithmetic off the cold numeric factory path.
  - Intended semantic baseline: message syntax and selection/evaluation rules, signed-64 `long`/`Long` Integer carriers, live lexical capture and ordinary callable behavior. Internal specialized operations do not authorize new guest semantics.
  - **Historical semantic limitations and divergences:** The historical repair does not independently certify D197-C delegation, all alternative Cores or overflow/interop edges. D013, D179 C0, D197-C, dynamic selection, lexical captures, ReturnHome, suspension, cancellation, Futures, Task/C-prime and Actor transfer are not independently certified or declared closed by this replay. Historical code is preserved except for the explicitly authorized whitespace correction described below.
  - Tests added or modified (not executed): `ProtosI123CpuKernelCandidateSupport.java`, `ProtosPerf025MapPhysicalIndexTest.java`, `ProtosPerf006B1BytecodeLiteralSequenceTest.java`, `ProtosPerf040E1NumericOperationReconciliationTest.java`, `ProtosForeignValueProjectionTest.java`, `ProtosHostJavaProviderTest.java`, `ProtosStandardPolyglotEmbeddingTest.java`, `ProtosI123CpuKernelEligibilityTest.java`. No Protos tests, builds, Maven commands or benchmarks were run during this replay.
  - Historical file transitions: 1 added, 21 modified, 0 deleted (rename detection disabled for exact path accounting). All 22 historical path transitions preserve original modes and deletion state. Blob and parent identity checks allow only the explicitly authorized one-byte whitespace correction introduced in I115-V91: `tools/i115_gate_cases/primitive-if-true.protos:20`, original blob `b29428dbbee0f06243f105627c0a7ba472abea5a`, corrected blob `06420027434dabfae0edb537abfca37225e710aa`. All other payload blobs match exactly. Root `pom.xml` and `CHANGELOG.md` receive version/record metadata edits.
  - Original implementation: `d4ef51bccbf4a9c363b8870310af7d72a0265415`.

## 0.4.118-SNAPSHOT

- **I115-V137 — historical replay.**
  - Preserve C-prime release transfers across mandatory finalizers and compose suspendible conditional callbacks. Return conditional callback results unchanged, including ordinary Futures.
  - Intended semantic baseline: message syntax and selection/evaluation rules, signed-64 `long`/`Long` Integer carriers, live lexical capture and ordinary callable behavior. Internal specialized operations do not authorize new guest semantics.
  - **Historical semantic limitations and divergences:** Release ordering, transfer cleanup and terminal suspension still require independent functional acceptance. D013, D179 C0, D197-C, dynamic selection, lexical captures, ReturnHome, suspension, cancellation, Futures, Task/C-prime and Actor transfer are not independently certified or declared closed by this replay. Historical code is preserved except for the explicitly authorized whitespace correction described below.
  - Tests added or modified (not executed): `ProtosPlat070IoCPrimeTransferAccountingTest.java`, `ProtosPerf006Plat028BooleanCallbackTest.java`, `ProtosTextReaderLineProtocolTest.java`, `ProtosStandardBufferedByteIoProtocolTest.java`. No Protos tests, builds, Maven commands or benchmarks were run during this replay.
  - Historical file transitions: 0 added, 8 modified, 0 deleted (rename detection disabled for exact path accounting). All 8 historical path transitions preserve original modes and deletion state. Blob and parent identity checks allow only the explicitly authorized one-byte whitespace correction introduced in I115-V91: `tools/i115_gate_cases/primitive-if-true.protos:20`, original blob `b29428dbbee0f06243f105627c0a7ba472abea5a`, corrected blob `06420027434dabfae0edb537abfca37225e710aa`. All other payload blobs match exactly. Root `pom.xml` and `CHANGELOG.md` receive version/record metadata edits.
  - Original implementation: `b9704fc5c3d22ec1866175c7cfa7f18fac8ec39d`.

## 0.4.117-SNAPSHOT

- **I115-V136 — historical replay.**
  - Restore shape-backed object state and guarded attribute/indexed operations. Stage receivers and operands once in source order; preserve explicit atPut results and indexed-assignment RHS values.
  - Intended semantic baseline: message syntax and selection/evaluation rules, signed-64 `long`/`Long` Integer carriers, live lexical capture and ordinary callable behavior. Internal specialized operations do not authorize new guest semantics.
  - **Historical semantic limitations and divergences:** Shape guards must prove mutation admission; the historical fix does not establish all custom indexed protocol cases. D013, D179 C0, D197-C, dynamic selection, lexical captures, ReturnHome, suspension, cancellation, Futures, Task/C-prime and Actor transfer are not independently certified or declared closed by this replay. Historical code is preserved except for the explicitly authorized whitespace correction described below.
  - Tests added or modified (not executed): `ProtosI115IndexedOperationTest.java`, `ProtosPerf010APreparedTargetSpecializationTest.java`, `ProtosPrimitiveSlotWriteRegressionTest.java`, `ProtosI091PrimitiveCarrierPropagationTest.java`, `ProtosPerf042IdentityBuilderOracleTest.java`, `ProtosGuardedLookupTest.java`, `ProtosPerf025ObjectModelPayAsYouGrowTest.java`, `ProtosRepresentedValueLookupTest.java`, `ProtosObjectValueTest.java`. No Protos tests, builds, Maven commands or benchmarks were run during this replay.
  - Historical file transitions: 0 added, 12 modified, 0 deleted (rename detection disabled for exact path accounting). All 12 historical path transitions preserve original modes and deletion state. Blob and parent identity checks allow only the explicitly authorized one-byte whitespace correction introduced in I115-V91: `tools/i115_gate_cases/primitive-if-true.protos:20`, original blob `b29428dbbee0f06243f105627c0a7ba472abea5a`, corrected blob `06420027434dabfae0edb537abfca37225e710aa`. All other payload blobs match exactly. Root `pom.xml` and `CHANGELOG.md` receive version/record metadata edits.
  - Original implementation: `3333f13285347ff3ac757c387136f40721971dd8`.

## 0.4.116-SNAPSHOT

- **I115-V135 — historical replay.**
  - Use Java Boolean guest values throughout affected runtime/tests and preserve embedding host-entry suspension and RootActor admission while retaining direct session calls.
  - Intended semantic baseline: message syntax and selection/evaluation rules, signed-64 `long`/`Long` Integer carriers, live lexical capture and ordinary callable behavior. Internal specialized operations do not authorize new guest semantics.
  - **Historical semantic limitations and divergences:** The original explicitly reports five known regression failures pending; this broad representation migration is preserved without fresh runtime acceptance. D013, D179 C0, D197-C, dynamic selection, lexical captures, ReturnHome, suspension, cancellation, Futures, Task/C-prime and Actor transfer are not independently certified or declared closed by this replay. Historical code is preserved except for the explicitly authorized whitespace correction described below.
  - Tests added or modified (not executed): `ProtosDiagnosticInspectorTest.java`, `ProtosTestToolH2B3PublicIntegrationTest.java`, `ProtosTestToolI8D5AHostRegistryBootstrapTest.java`, `ProtosTestToolI8D5CPublicCutoverTest.java`, `ProtosFilesystemLanguageConformanceTest.java`, `ProtosFilesystemLibraryConformanceTest.java`, `ProtosFilesystemMaturityConformanceTest.java`, `ProtosFilesystemTreeIntegratedConformanceTest.java`, `ProtosFilesystemTreeSurfaceConformanceTest.java`, `ProtosResourceLifetimeMaturityConformanceTest.java`, `ProtosSystemResourceEndToEndConformanceTest.java`, `ProtosI109ADynamicLocalEachTest.java`, `ProtosReplLexicalObservationTest.java`, `ProtosPerf025H1IndexedParameterEstablishmentTest.java`, `ProtosPerf027AGuardedIntegerDeferredActivationTest.java`, `CanonicalCompositionExecutionTest.java`, `CanonicalExplicitMemberMutationExecutionTest.java`, `CanonicalLookupExecutionTest.java`, `CanonicalMapConstructionExecutionTest.java`, `CanonicalMemberReadExecutionTest.java`, `CanonicalObjectExecutionTest.java`, `ProtosPerf006B1BytecodeLiteralSequenceTest.java`, `ProtosPerf006B6A1ReadOnlyCanonicalCoverageTest.java`, `ProtosPerf006B6A3CObjectComposeCanonicalCoverageTest.java`, `ProtosPerf006Plat028BooleanCallbackTest.java`, `ProtosPerf006Plat028IdentityMapEachCallbackTest.java`, `ProtosPerf006Plat028MapAtPutCallbackTest.java`, `ProtosPerf006Plat028MapReadLookupCallbackTest.java`, `ProtosPerf006Plat028MapRemoveCallbackTest.java`, `ProtosPerf025CallbackConsumerSpecializationTest.java`, `ProtosPerf026B1BooleanInlineCallbackTest.java`, `ProtosPerf026B2BooleanInlineCallbackTest.java`, `ProtosPerf026B3BooleanInlineCallbackTest.java`, `ProtosPerf026C1WhileInlineCallbackTest.java`, `ProtosPerf026D1IndexedEachInlineCallbackTest.java`, `ProtosPerf026D2AssociationEachInlineCallbackTest.java`, `ProtosPerf026D3EnvironmentEachInlineCallbackTest.java`, `ProtosSemanticTransferMaterializationTest.java`, `ProtosI143NamedObjectCallBridgeTest.java`, `ProtosI143NamedSendSelectionTest.java`, `ProtosI143NamedSourceExecutionTest.java`, `ProtosI143NamedSourceSemanticsTest.java`, `ProtosI143SelectedSourcePreparationTest.java`, `ProtosD212StandardLoopPolaritiesTest.java`, `ProtosHostEntryFutureSuspensionTest.java`, `ProtosNonLocalReturnTest.java`, `ProtosPerf006B4BNonLocalReturnTest.java`, `ProtosPerf006B4FStructuredWhileTest.java`, `ProtosPerf037EDirectTerminalReturnTest.java`, `ProtosI072PhaseEStructuredSendConvergenceTest.java`, `ProtosI145AConstructProtocolTest.java`, `ProtosPerf025CompactCalleeExecutionTest.java`, `ProtosPerf038BCompactMethodCallTest.java`, `ProtosPerf038CCompactCallSpecializationTest.java`, `ProtosPerf038ITerminalMethodSendTest.java`, `CanonicalBareSlotMutationExecutionTest.java`, `ProtosI115PublishedLexicalContextTest.java`, `ProtosI091PrimitiveCarrierRootsTest.java`, `ProtosI092CompactBooleanTest.java`, `ProtosI095BooleanProtocolInlineTest.java`, `ProtosI101CComparisonBuilderOracleTest.java`, `ProtosI101CIndependentBuilderReferenceTest.java`, `ProtosI101ProfileSpecializedLoweringTest.java`, `ProtosI107ContinuationEmissionTest.java`, `ProtosI112TailContinuationTest.java`, `ProtosI115PCompactBooleanWhileTest.java`, `ProtosPerf040E1NumericOperationReconciliationTest.java`, `ProtosI115StructuredControlSmokeTest.java`, `ProtosI115UnaryPICConvergenceTest.java`, `ProtosHmacSha256FacilityTest.java`, `ProtosExternalForeignProviderTest.java`, `ProtosForeignCallbackTest.java`, `ProtosForeignInteropTest.java`, `ProtosForeignPullEachTest.java`, `ProtosForeignValueAdmissionTest.java`, `ProtosForeignValueProjectionTest.java`, `ProtosHostJavaProviderTest.java`, `ProtosHmacSha256IntegrationTest.java`, `ProtosCapturedFilesystemCustodyTest.java`, `ProtosFilesystemIntegratedConformanceTest.java`, `ProtosLinuxConfinedFilesystemTestSupport.java`, `ProtosNioReadOnlyTreeFilesystemBackendTest.java`, `ProtosAPlusExecutionProjectionTest.java`, `ProtosCommandLineResultModelTest.java`, `ProtosExactExecutionFacilityTest.java`, `ProtosHttpClientIntegrationTest.java`, `ProtosHttpServerIntegrationTest.java`, `ProtosHttpUrlClientIntegrationTest.java`, `ProtosI026FDapBehaviorTest.java`, `ProtosA4B3ModuleProcessHostingTest.java`, `ProtosCommandLineSpecModuleTest.java`, `ProtosCoreBootstrapTest.java`, `ProtosNetworkConnectAcquisitionTest.java`, `ProtosNetworkingIpEndpointsModuleTest.java`, `ProtosNioNetworkBackendConnectTest.java`, `ProtosResolverAuthorityTest.java`, `ProtosTcpConnectionEndpointObservationTest.java`, `ProtosTcpConnectionIntegratedConformanceTest.java`, `ProtosTcpListenerAcceptTest.java`, `ProtosTcpListenerIntegratedConformanceTest.java`, `ProtosPackageTestLogicalCaseExecutionFacilityTest.java`, `ProtosAsyncProcessSnapshotExecutionFacilityTest.java`, `ProtosI158ActorBootstrapSuspensionTest.java`, `ProtosParallelExecutionTest.java`, `ProtosProcessIntegratedConformanceTest.java`, `ProtosProcessSnapshotExecutionTest.java`, `ProtosProcessSnapshotLogicalCaseExecutionFacilityTest.java`, `ProtosRootActorServiceBootstrapIntegrationTest.java`, `ProtosCaseOfProtocolTest.java`, `ProtosTlsClientIntegrationTest.java`, `ProtosCanonicalInitialModuleExecutionTest.java`, `ProtosModuleRuntimeTest.java`, `ProtosPackageToolContentIdentityTest.java`, `ProtosPackageToolFilesystemSecurityTest.java`, `ProtosPackageToolManifestTest.java`, `ProtosPackageToolMetadataPersistenceTest.java`, `ProtosPackageToolProtosTestSupport.java`, `ProtosStandardEnvironmentProtocolTest.java`, `ProtosStandardFilesystemProtocolTest.java`, `ProtosStandardFilesystemTreeMaterializationTest.java`, `ProtosStandardIpFamilyPlacementTest.java`, `ProtosStandardPathProtocolTest.java`, `ProtosTomlParserPathRegressionTest.java`, `ProtosDerivedInequalityBackendTest.java`, `ProtosInspectFacilityTest.java`, `ProtosRegexSemanticTransferTest.java`, `ProtosRuntimeMetadataFacilityTest.java`, `ProtosComplexSemanticsTest.java`, `ProtosMathModuleTest.java`, `ProtosStandardIntegerArithmeticTest.java`, `ProtosStandardNumberEqualityTest.java`, `ProtosStandardNumberOrderingProtocolTest.java`, `ProtosTomlParserFloatTemporalRegressionTest.java`, `ProtosTomlParserIntegerRegressionTest.java`, `ProtosArrayMatchProtocolTest.java`, `ProtosCollectionsArrayAlgorithmsModuleTest.java`, `ProtosCollectionsArrayReduceSortModuleTest.java`, `ProtosCollectionsSetAlgebraModuleTest.java`, `ProtosCollectionsSetMutationModuleTest.java`, `ProtosIdentityMapConformanceTest.java`, `ProtosMapConstructionFPrimeBytecodeFoundationTest.java`, `ProtosMapConstructionFPrimeProtocolTest.java`, `ProtosMapMatchProtocolTest.java`, `ProtosMatcherHelpersBootstrapTest.java`, `ProtosObjectMatchProtocolTest.java`, `ProtosStandardArrayFactoryTest.java`, `ProtosStandardLinearAlgebraProtocolTest.java`, `ProtosStandardMapProtocolTest.java`, `ProtosTomlEncoderDeepContainerRegressionTest.java`, `ProtosTomlParserArrayOfTablesRegressionTest.java`, `ProtosTomlParserFlatDocumentRegressionTest.java`, `ProtosTomlParserLexicalKeyRegressionTest.java`, `ProtosTomlParserLexicalStringTriviaRegressionTest.java`, `ProtosTomlParserNestedValueRegressionTest.java`, `ProtosTestToolDiscoveryImportedModuleTest.java`, `ProtosTestToolDiscoverySignatureTest.java`, `ProtosTestToolFileSelectionPlanTest.java`, `ProtosTestToolSourceLoaderTest.java`, `ProtosTestToolCaseSelectionTest.java`, `ProtosTestToolFutureResolvedMechanismTest.java`, `ProtosTestToolFutureTerminalMechanismTest.java`, `ProtosTestToolI8D5BTestRunOutcomeProjectionTest.java`, `ProtosTestToolLib011OptionsAdoptionTest.java`, `ProtosTestToolLogicalCaseMigrationTest.java`, `ProtosTestToolLogicalCasePlanTest.java`, `ProtosTestToolLogicalCaseResultTest.java`, `ProtosTestToolManifestPlanTest.java`, `ProtosTestToolPackageExecutionEnvironmentTest.java`, `ProtosTestToolPackageFailedExecutionTest.java`, `ProtosTestToolSequentialRunnerTest.java`, `ProtosTestToolClosureFreshExpectationsTest.java`, `ProtosTestToolErrorParentExpectationsTest.java`, `ProtosTestToolExactBinary64Test.java`, `ProtosTestToolFloatBitsExpectationsTest.java`, `ProtosTestToolFloatBitsParserTest.java`, `ProtosTestToolFloatNanExpectationsTest.java`, `ProtosTestToolFutureFreshInspectionFixtureSuiteTest.java`, `ProtosTestToolFutureObservationPolicyFixtureSuiteTest.java`, `ProtosTestToolFutureStoredExpectationRecognitionTest.java`, `ProtosTestToolFutureStoredInspectionFixtureSuiteTest.java`, `ProtosTestToolSimpleExpectationsTest.java`, `ProtosTestToolTool004BProgressBoundaryTest.java`, `ProtosTestToolTool004CProgressPresentationTest.java`, `ProtosTestToolTool009ALifecycleReporterTest.java`, `ProtosTestToolH2B1BoundedSimpleSchedulingTest.java`, `ProtosTestToolH2B2BoundedDCaseSchedulingTest.java`, `ProtosTestToolI8CResourceRoundSchedulingTest.java`, `ProtosTestToolI8D4C1ResourcefulExecutionFacilityTest.java`, `ProtosTestToolI8D4C2ResourcefulInspectionFacilityTest.java`, `ProtosTestToolI8D4C3RunnerRoundDrainTest.java`, `ProtosTestToolResourceCatalogSchemaTest.java`, `ProtosTestToolResourceRequirementsDiscoveryTest.java`, `ProtosTestToolResourceRequirementsSchemaTest.java`, `ProtosTestToolResourceReservationKernelTest.java`, `ProtosTestLogicalCaseDiscoveryFacilityTest.java`, `ProtosActorValueTransferTest.java`, `ProtosFilesystemOpenFlowTest.java`, `ProtosGuardedLookupTest.java`, `ProtosRepresentedValueLookupTest.java`, `ProtosExecutionContextTest.java`, `ProtosActorSendOperationTest.java`, `ProtosNumericValueSupportTest.java`, `ProtosClockTransferTest.java`, `ProtosArrayInteropTest.java`, `ProtosArrayValueTest.java`, `ProtosObjectValueTest.java`, `ProtosSimpleScalarInteropDisplayTest.java`, `ProtosSimpleScalarInteropTest.java`. No Protos tests, builds, Maven commands or benchmarks were run during this replay.
  - Historical file transitions: 0 added, 252 modified, 1 deleted (rename detection disabled for exact path accounting). All 253 historical path transitions preserve original modes and deletion state. Blob and parent identity checks allow only the explicitly authorized one-byte whitespace correction introduced in I115-V91: `tools/i115_gate_cases/primitive-if-true.protos:20`, original blob `b29428dbbee0f06243f105627c0a7ba472abea5a`, corrected blob `06420027434dabfae0edb537abfca37225e710aa`. All other payload blobs match exactly. Root `pom.xml` and `CHANGELOG.md` receive version/record metadata edits.
  - Original implementation: `da221450c955bff99375a3d98b420e1a5cee8ede`.

## 0.4.115-SNAPSHOT

- **I115-V134 — historical replay.**
  - Allow Future.value to suspend Task-free Actor initialization, fail suspended module records on abrupt composition exit, recover inherited Prelude demand from caller frames and bind numeric construction at Actor bootstrap.
  - Intended semantic baseline: message syntax and selection/evaluation rules, signed-64 `long`/`Long` Integer carriers, live lexical capture and ordinary callable behavior. Internal specialized operations do not authorize new guest semantics.
  - **Historical semantic limitations and divergences:** The original explicitly leaves hostFut1 guest translation and indexed access pending. D013, D179 C0, D197-C, dynamic selection, lexical captures, ReturnHome, suspension, cancellation, Futures, Task/C-prime and Actor transfer are not independently certified or declared closed by this replay. Historical code is preserved except for the explicitly authorized whitespace correction described below.
  - Tests added or modified (not executed): `ProtosDetachedExecutionValueCrossPreludeTest.java`, `ProtosCoreBootstrapTest.java`, `ProtosNetworkingIpEndpointsModuleTest.java`, `ProtosI158ActorBootstrapSuspensionTest.java`, `ProtosProcessStandardStreamBindingTest.java`. No Protos tests, builds, Maven commands or benchmarks were run during this replay.
  - Historical file transitions: 0 added, 11 modified, 0 deleted (rename detection disabled for exact path accounting). All 11 historical path transitions preserve original modes and deletion state. Blob and parent identity checks allow only the explicitly authorized one-byte whitespace correction introduced in I115-V91: `tools/i115_gate_cases/primitive-if-true.protos:20`, original blob `b29428dbbee0f06243f105627c0a7ba472abea5a`, corrected blob `06420027434dabfae0edb537abfca37225e710aa`. All other payload blobs match exactly. Root `pom.xml` and `CHANGELOG.md` receive version/record metadata edits.
  - Original implementation: `bd5d6504d421c40a187b632b9e17dc7a4603c3a7`.

## 0.4.114-SNAPSHOT

- **I115-V133 — historical replay.**
  - Translate numeric tests to long Integer and double Float values, reconcile constant-folding oracles and bind the harness numeric factory at entry.
  - Intended semantic baseline: message syntax and selection/evaluation rules, signed-64 `long`/`Long` Integer carriers, live lexical capture and ordinary callable behavior. Internal specialized operations do not authorize new guest semantics.
  - **Historical semantic limitations and divergences:** The original explicitly leaves alternative-Core add selection and builtin activation pending. D013, D179 C0, D197-C, dynamic selection, lexical captures, ReturnHome, suspension, cancellation, Futures, Task/C-prime and Actor transfer are not independently certified or declared closed by this replay. Historical code is preserved except for the explicitly authorized whitespace correction described below.
  - Tests added or modified (not executed): `ProtosI101BArithmeticBuilderOracleTest.java`, `ProtosI101CComparisonBuilderOracleTest.java`, `ProtosI115Long4H7ArithmeticTest.java`, `ProtosI115OConstantIntegerReturnTest.java`, `ProtosPerf040E1NumericOperationReconciliationTest.java`, `ProtosTestExecutionSupport.java`, `ProtosCurrentNumericRelationsTest.java`, `ProtosComplexArithmeticTest.java`, `ProtosFractionSemanticsTest.java`, `ProtosMathModuleTest.java`, `ProtosStandardIntegerArithmeticTest.java`, `ProtosI091IdentityRepresentationTest.java`, `ProtosNumericValueSupportTest.java`. No Protos tests, builds, Maven commands or benchmarks were run during this replay.
  - Historical file transitions: 0 added, 13 modified, 0 deleted (rename detection disabled for exact path accounting). All 13 historical path transitions preserve original modes and deletion state. Blob and parent identity checks allow only the explicitly authorized one-byte whitespace correction introduced in I115-V91: `tools/i115_gate_cases/primitive-if-true.protos:20`, original blob `b29428dbbee0f06243f105627c0a7ba472abea5a`, corrected blob `06420027434dabfae0edb537abfca37225e710aa`. All other payload blobs match exactly. Root `pom.xml` and `CHANGELOG.md` receive version/record metadata edits.
  - Original implementation: `6c555f72431581b214acf34105a1d2f9c97c0152`.

## 0.4.113-SNAPSHOT

- **I115-V132 — historical replay.**
  - Send the original operator selector for unspecialized numeric operands, bind the numeric factory at Core bootstrap and translate folded-literal/internal-control tests.
  - Intended semantic baseline: message syntax and selection/evaluation rules, signed-64 `long`/`Long` Integer carriers, live lexical capture and ordinary callable behavior. Internal specialized operations do not authorize new guest semantics.
  - **Historical semantic limitations and divergences:** The original explicitly reports numeric group failures pending. D013, D179 C0, D197-C, dynamic selection, lexical captures, ReturnHome, suspension, cancellation, Futures, Task/C-prime and Actor transfer are not independently certified or declared closed by this replay. Historical code is preserved except for the explicitly authorized whitespace correction described below.
  - Tests added or modified (not executed): `ProtosPerf037EDirectTerminalReturnTest.java`, `ProtosPerf025CompactCalleeExecutionTest.java`, `ProtosI115H7MethodCallConvergenceTest.java`, `ProtosI115QCompactLiteralReturnTest.java`. No Protos tests, builds, Maven commands or benchmarks were run during this replay.
  - Historical file transitions: 0 added, 15 modified, 0 deleted (rename detection disabled for exact path accounting). All 15 historical path transitions preserve original modes and deletion state. Blob and parent identity checks allow only the explicitly authorized one-byte whitespace correction introduced in I115-V91: `tools/i115_gate_cases/primitive-if-true.protos:20`, original blob `b29428dbbee0f06243f105627c0a7ba472abea5a`, corrected blob `06420027434dabfae0edb537abfca37225e710aa`. All other payload blobs match exactly. Root `pom.xml` and `CHANGELOG.md` receive version/record metadata edits.
  - Original implementation: `ccec650c00685d7ee347c989f336206bac0f753d`.

## 0.4.112-SNAPSHOT

- **I115-V131 — historical replay.**
  - Execute the already-selected Closure body directly on structured Object.call, preventing a second lookup of a local call override.
  - Intended semantic baseline: message syntax and selection/evaluation rules, signed-64 `long`/`Long` Integer carriers, live lexical capture and ordinary callable behavior. Internal specialized operations do not authorize new guest semantics.
  - **Historical semantic limitations and divergences:** The original explicitly leaves numeric, object and size failures pending. D013, D179 C0, D197-C, dynamic selection, lexical captures, ReturnHome, suspension, cancellation, Futures, Task/C-prime and Actor transfer are not independently certified or declared closed by this replay. Historical code is preserved except for the explicitly authorized whitespace correction described below.
  - Tests added or modified (not executed): `ProtosPerf006B2D3BSelectedStandardObjectCallIntrinsicTest.java`. No Protos tests, builds, Maven commands or benchmarks were run during this replay.
  - Historical file transitions: 0 added, 5 modified, 0 deleted (rename detection disabled for exact path accounting). All 5 historical path transitions preserve original modes and deletion state. Blob and parent identity checks allow only the explicitly authorized one-byte whitespace correction introduced in I115-V91: `tools/i115_gate_cases/primitive-if-true.protos:20`, original blob `b29428dbbee0f06243f105627c0a7ba472abea5a`, corrected blob `06420027434dabfae0edb537abfca37225e710aa`. All other payload blobs match exactly. Root `pom.xml` and `CHANGELOG.md` receive version/record metadata edits.
  - Original implementation: `da062ea4d1ff38252e00d74664c58bb4d6cf61b7`.

## 0.4.111-SNAPSHOT

- **I115-V130 — historical replay.**
  - Decide static inline-control admission using the planned physical-root entry context, publish non-suspension proofs for statically selected conditionals and translate I107 topology assertions.
  - Intended semantic baseline: message syntax and selection/evaluation rules, signed-64 `long`/`Long` Integer carriers, live lexical capture and ordinary callable behavior. Internal specialized operations do not authorize new guest semantics.
  - **Historical semantic limitations and divergences:** The original explicitly leaves numeric, object and size failures pending; static proofs are not revalidated functionally. D013, D179 C0, D197-C, dynamic selection, lexical captures, ReturnHome, suspension, cancellation, Futures, Task/C-prime and Actor transfer are not independently certified or declared closed by this replay. Historical code is preserved except for the explicitly authorized whitespace correction described below.
  - Tests added or modified (not executed): `ProtosI107ContinuationEmissionTest.java`. No Protos tests, builds, Maven commands or benchmarks were run during this replay.
  - Historical file transitions: 0 added, 2 modified, 0 deleted (rename detection disabled for exact path accounting). All 2 historical path transitions preserve original modes and deletion state. Blob and parent identity checks allow only the explicitly authorized one-byte whitespace correction introduced in I115-V91: `tools/i115_gate_cases/primitive-if-true.protos:20`, original blob `b29428dbbee0f06243f105627c0a7ba472abea5a`, corrected blob `06420027434dabfae0edb537abfca37225e710aa`. All other payload blobs match exactly. Root `pom.xml` and `CHANGELOG.md` receive version/record metadata edits.
  - Original implementation: `ffce6d13dc18e933c40de58058fe2fb5a1a813f7`.

## 0.4.110-SNAPSHOT

- **I115-V129 — historical replay.**
  - Preserve inherited methodHome on cached selection, perform D013 guards before preparing a Closure target and mark changing guards NonIdempotent. Translate dispatch tests to current operations.
  - Intended semantic baseline: message syntax and selection/evaluation rules, signed-64 `long`/`Long` Integer carriers, live lexical capture and ordinary callable behavior. Internal specialized operations do not authorize new guest semantics.
  - **Historical semantic limitations and divergences:** The original explicitly leaves I107 oracles pending. D013, D179 C0, D197-C, dynamic selection, lexical captures, ReturnHome, suspension, cancellation, Futures, Task/C-prime and Actor transfer are not independently certified or declared closed by this replay. Historical code is preserved except for the explicitly authorized whitespace correction described below.
  - Tests added or modified (not executed): `ProtosI143NamedActivationPresenceTest.java`, `ProtosPerf006B6A3BClosureLiteralCanonicalCoverageTest.java`, `ProtosPerf025DirectSourceClosureCompactInvocationTest.java`, `ProtosPerf025CompactCalleeExecutionTest.java`, `ProtosPerf038EResidualCallGraphTest.java`, `ProtosPerf038FResidualPrimitiveCallTest.java`, `ProtosPerf038ITerminalMethodSendTest.java`, `ProtosI096InlineClosureLiteralCallTest.java`, `ProtosI106ComposableCompilationFactsTest.java`, `ProtosI107ContinuationEmissionTest.java`, `ProtosI108BCallNDispatchTest.java`, `ProtosI112TailContinuationTest.java`, `ProtosI112TerminalCallBudgetTest.java`, `ProtosI115H7MethodCallConvergenceTest.java`, `ProtosI115HStaticClosureCallTest.java`, `ProtosI115RFactorialCaptureTest.java`, `ProtosPerf042DefinitionKeyedDirectCallTest.java`, `ProtosI115BDeferredCallerTest.java`. No Protos tests, builds, Maven commands or benchmarks were run during this replay.
  - Historical file transitions: 0 added, 26 modified, 0 deleted (rename detection disabled for exact path accounting). All 26 historical path transitions preserve original modes and deletion state. Blob and parent identity checks allow only the explicitly authorized one-byte whitespace correction introduced in I115-V91: `tools/i115_gate_cases/primitive-if-true.protos:20`, original blob `b29428dbbee0f06243f105627c0a7ba472abea5a`, corrected blob `06420027434dabfae0edb537abfca37225e710aa`. All other payload blobs match exactly. Root `pom.xml` and `CHANGELOG.md` receive version/record metadata edits.
  - Original implementation: `dbb9566313beb5cf4428d24fe191bb67575969c2`.

## 0.4.109-SNAPSHOT

- **I115-V128 — historical replay.**
  - Move send and binary-plus behavior selection after formation of the argument packet under D237. Translate H7 tests to grouped binding and current binary-send operations.
  - Intended semantic baseline: message syntax and selection/evaluation rules, signed-64 `long`/`Long` Integer carriers, live lexical capture and ordinary callable behavior. Internal specialized operations do not authorize new guest semantics.
  - **Historical semantic limitations and divergences:** The original explicitly reports invocation failures pending; selection ordering is preserved as historical intent. D013, D179 C0, D197-C, dynamic selection, lexical captures, ReturnHome, suspension, cancellation, Futures, Task/C-prime and Actor transfer are not independently certified or declared closed by this replay. Historical code is preserved except for the explicitly authorized whitespace correction described below.
  - Tests added or modified (not executed): `ProtosI115H7MethodCallConvergenceTest.java`. No Protos tests, builds, Maven commands or benchmarks were run during this replay.
  - Historical file transitions: 0 added, 2 modified, 0 deleted (rename detection disabled for exact path accounting). All 2 historical path transitions preserve original modes and deletion state. Blob and parent identity checks allow only the explicitly authorized one-byte whitespace correction introduced in I115-V91: `tools/i115_gate_cases/primitive-if-true.protos:20`, original blob `b29428dbbee0f06243f105627c0a7ba472abea5a`, corrected blob `06420027434dabfae0edb537abfca37225e710aa`. All other payload blobs match exactly. Root `pom.xml` and `CHANGELOG.md` receive version/record metadata edits.
  - Original implementation: `3e9dd420af875d6fd3172b4956bedc772737f754`.

## 0.4.108-SNAPSHOT

- **I115-V127 — historical replay.**
  - Treat transported free cells as shared binding references rather than capture of the creator Context; translate capture tests to guest identity and shared mutation observations.
  - Intended semantic baseline: message syntax and selection/evaluation rules, signed-64 `long`/`Long` Integer carriers, live lexical capture and ordinary callable behavior. Internal specialized operations do not authorize new guest semantics.
  - **Historical semantic limitations and divergences:** The original explicitly reports invocation failures pending; optimized capture representation is not a new semantic rule. D013, D179 C0, D197-C, dynamic selection, lexical captures, ReturnHome, suspension, cancellation, Futures, Task/C-prime and Actor transfer are not independently certified or declared closed by this replay. Historical code is preserved except for the explicitly authorized whitespace correction described below.
  - Tests added or modified (not executed): `ProtosI115LazyCapturedFrameTest.java`, `ProtosPerf025LazyLexicalCaptureTest.java`, `ProtosPerf037CCapturedLexicalReadTest.java`, `ProtosI115ARuntimeAdmissionTest.java`. No Protos tests, builds, Maven commands or benchmarks were run during this replay.
  - Historical file transitions: 0 added, 5 modified, 0 deleted (rename detection disabled for exact path accounting). All 5 historical path transitions preserve original modes and deletion state. Blob and parent identity checks allow only the explicitly authorized one-byte whitespace correction introduced in I115-V91: `tools/i115_gate_cases/primitive-if-true.protos:20`, original blob `b29428dbbee0f06243f105627c0a7ba472abea5a`, corrected blob `06420027434dabfae0edb537abfca37225e710aa`. All other payload blobs match exactly. Root `pom.xml` and `CHANGELOG.md` receive version/record metadata edits.
  - Original implementation: `4d021d0084d1fcc858a7abc9656c6e98f7748045`.

## 0.4.107-SNAPSHOT

- **I115-V126 — historical replay.**
  - Dispatch an alias of standard Object.call directly, make its Closure branch terminal and remove advance caller-Prelude declarations.
  - Intended semantic baseline: message syntax and selection/evaluation rules, signed-64 `long`/`Long` Integer carriers, live lexical capture and ordinary callable behavior. Internal specialized operations do not authorize new guest semantics.
  - **Historical semantic limitations and divergences:** A terminal selected Closure body must not reselect its local call slot. The original still reports invocation failures pending. D013, D179 C0, D197-C, dynamic selection, lexical captures, ReturnHome, suspension, cancellation, Futures, Task/C-prime and Actor transfer are not independently certified or declared closed by this replay. Historical code is preserved except for the explicitly authorized whitespace correction described below.
  - Tests added or modified (not executed): `ProtosI115StringAdditionTest.java`, `ProtosI115BDeferredCallerTest.java`. No Protos tests, builds, Maven commands or benchmarks were run during this replay.
  - Historical file transitions: 0 added, 6 modified, 0 deleted (rename detection disabled for exact path accounting). All 6 historical path transitions preserve original modes and deletion state. Blob and parent identity checks allow only the explicitly authorized one-byte whitespace correction introduced in I115-V91: `tools/i115_gate_cases/primitive-if-true.protos:20`, original blob `b29428dbbee0f06243f105627c0a7ba472abea5a`, corrected blob `06420027434dabfae0edb537abfca37225e710aa`. All other payload blobs match exactly. Root `pom.xml` and `CHANGELOG.md` receive version/record metadata edits.
  - Original implementation: `2f74c372ae6db45cbfd80a6fdd87307ea0894ba8`.

## 0.4.106-SNAPSHOT

- **I115-V125 — historical replay.**
  - Retain the creator admitted Prelude in non-capturing Closures so host entry owns its Core, without retaining the creator environment, receiver, methodHome or ReturnHome.
  - Intended semantic baseline: message syntax and selection/evaluation rules, signed-64 `long`/`Long` Integer carriers, live lexical capture and ordinary callable behavior. Internal specialized operations do not authorize new guest semantics.
  - **Historical semantic limitations and divergences:** The original explicitly reports invocation failures pending; Core ownership is not newly validated. D013, D179 C0, D197-C, dynamic selection, lexical captures, ReturnHome, suspension, cancellation, Futures, Task/C-prime and Actor transfer are not independently certified or declared closed by this replay. Historical code is preserved except for the explicitly authorized whitespace correction described below.
  - Tests added or modified (not executed): `ProtosPerf025CompactCalleeExecutionTest.java`, `ProtosI115BDeferredCallerTest.java`. No Protos tests, builds, Maven commands or benchmarks were run during this replay.
  - Historical file transitions: 0 added, 5 modified, 0 deleted (rename detection disabled for exact path accounting). All 5 historical path transitions preserve original modes and deletion state. Blob and parent identity checks allow only the explicitly authorized one-byte whitespace correction introduced in I115-V91: `tools/i115_gate_cases/primitive-if-true.protos:20`, original blob `b29428dbbee0f06243f105627c0a7ba472abea5a`, corrected blob `06420027434dabfae0edb537abfca37225e710aa`. All other payload blobs match exactly. Root `pom.xml` and `CHANGELOG.md` receive version/record metadata edits.
  - Original implementation: `6c286ea2a0fc7380974c204c83c38f252366a397`.

## 0.4.105-SNAPSHOT

- **I115-V124 — historical replay.**
  - Implement the historical D013 call-slot reconciliation and select call behavior using the admitted Prelude, demanding a caller activation only for Error construction. Translate dispatch tests to the current ABI.
  - Intended semantic baseline: message syntax and selection/evaluation rules, signed-64 `long`/`Long` Integer carriers, live lexical capture and ordinary callable behavior. Internal specialized operations do not authorize new guest semantics.
  - **Historical semantic limitations and divergences:** The original claims D013 reconciliation but still reports invocation failures pending; this replay does not certify closure. D013, D179 C0, D197-C, dynamic selection, lexical captures, ReturnHome, suspension, cancellation, Futures, Task/C-prime and Actor transfer are not independently certified or declared closed by this replay. Historical code is preserved except for the explicitly authorized whitespace correction described below.
  - Tests added or modified (not executed): `ProtosI143NamedArgumentStagingTest.java`, `ProtosPerf006B2D1OrdinarySendCompositionTest.java`, `ProtosPerf006B2D3AOrdinaryObjectCallProtocolTest.java`, `ProtosPerf006B2D3BSelectedStandardObjectCallIntrinsicTest.java`, `ProtosPerf006B2D4AComposedCallTargetTest.java`, `ProtosPerf006B2D4BComposedSendReceiverTest.java`, `ProtosI115BDeferredCallerTest.java`, `ProtosI115UnaryPICConvergenceTest.java`. No Protos tests, builds, Maven commands or benchmarks were run during this replay.
  - Historical file transitions: 0 added, 9 modified, 0 deleted (rename detection disabled for exact path accounting). All 9 historical path transitions preserve original modes and deletion state. Blob and parent identity checks allow only the explicitly authorized one-byte whitespace correction introduced in I115-V91: `tools/i115_gate_cases/primitive-if-true.protos:20`, original blob `b29428dbbee0f06243f105627c0a7ba472abea5a`, corrected blob `06420027434dabfae0edb537abfca37225e710aa`. All other payload blobs match exactly. Root `pom.xml` and `CHANGELOG.md` receive version/record metadata edits.
  - Original implementation: `6bbc31881158bc5ef2f04be60407fd6f9fc77258`.

## 0.4.104-SNAPSHOT

- **I115-V123 — historical replay.**
  - Remove the retired control-flow interceptor and translate Closure-call tests to the current ABI, preserving distinct CallTargets for ordinary binding calls.
  - Intended semantic baseline: message syntax and selection/evaluation rules, signed-64 `long`/`Long` Integer carriers, live lexical capture and ordinary callable behavior. Internal specialized operations do not authorize new guest semantics.
  - **Historical semantic limitations and divergences:** The original explicitly reports invocation failures pending. D013, D179 C0, D197-C, dynamic selection, lexical captures, ReturnHome, suspension, cancellation, Futures, Task/C-prime and Actor transfer are not independently certified or declared closed by this replay. Historical code is preserved except for the explicitly authorized whitespace correction described below.
  - Tests added or modified (not executed): `ProtosPerf026B3BooleanInlineCallbackTest.java`, `ProtosPerf026C1WhileInlineCallbackTest.java`, `ProtosPerf006B2AClosureActivationTest.java`, `ProtosPerf006B2BClosureContinuationCompositionTest.java`, `ProtosPerf006B3CSuspensionCapableNativeLeafTest.java`. No Protos tests, builds, Maven commands or benchmarks were run during this replay.
  - Historical file transitions: 0 added, 6 modified, 0 deleted (rename detection disabled for exact path accounting). All 6 historical path transitions preserve original modes and deletion state. Blob and parent identity checks allow only the explicitly authorized one-byte whitespace correction introduced in I115-V91: `tools/i115_gate_cases/primitive-if-true.protos:20`, original blob `b29428dbbee0f06243f105627c0a7ba472abea5a`, corrected blob `06420027434dabfae0edb537abfca37225e710aa`. All other payload blobs match exactly. Root `pom.xml` and `CHANGELOG.md` receive version/record metadata edits.
  - Original implementation: `6205b344468272b1828ad341b2b5d1f2580a5208`.

## 0.4.103-SNAPSHOT

- **I115-V122 — historical replay.**
  - Translate inline-context and lexical-write tests to lazy context publication, physical-root callback execution and selected-binding guest observations.
  - Intended semantic baseline: message syntax and selection/evaluation rules, signed-64 `long`/`Long` Integer carriers, live lexical capture and ordinary callable behavior. Internal specialized operations do not authorize new guest semantics.
  - **Historical semantic limitations and divergences:** These are translated assertions; no result is asserted for the unexecuted tests. D013, D179 C0, D197-C, dynamic selection, lexical captures, ReturnHome, suspension, cancellation, Futures, Task/C-prime and Actor transfer are not independently certified or declared closed by this replay. Historical code is preserved except for the explicitly authorized whitespace correction described below.
  - Tests added or modified (not executed): `ProtosPerf025InlineCallbackPreparationTest.java`, `ProtosPerf028AResolvedCurrentLexicalWriteTest.java`. No Protos tests, builds, Maven commands or benchmarks were run during this replay.
  - Historical file transitions: 0 added, 2 modified, 0 deleted (rename detection disabled for exact path accounting). All 2 historical path transitions preserve original modes and deletion state. Blob and parent identity checks allow only the explicitly authorized one-byte whitespace correction introduced in I115-V91: `tools/i115_gate_cases/primitive-if-true.protos:20`, original blob `b29428dbbee0f06243f105627c0a7ba472abea5a`, corrected blob `06420027434dabfae0edb537abfca37225e710aa`. All other payload blobs match exactly. Root `pom.xml` and `CHANGELOG.md` receive version/record metadata edits.
  - Original implementation: `0f53fbfaadef914b2297a69779b39d767e911e97`.

## 0.4.102-SNAPSHOT

- **I115-V121 — historical replay.**
  - Restore lazy Closure binding and callback inlining. From the first default parameter onward, use checked creation because defaults may observe or mutate the invocation Context; retain grouped stores for eligible parameter lists.
  - Intended semantic baseline: message syntax and selection/evaluation rules, signed-64 `long`/`Long` Integer carriers, live lexical capture and ordinary callable behavior. Internal specialized operations do not authorize new guest semantics.
  - **Historical semantic limitations and divergences:** The original explicitly reports regression failures pending. D013, D179 C0, D197-C, dynamic selection, lexical captures, ReturnHome, suspension, cancellation, Futures, Task/C-prime and Actor transfer are not independently certified or declared closed by this replay. Historical code is preserved except for the explicitly authorized whitespace correction described below.
  - Tests added or modified (not executed): `ProtosPerf006B2D5CDefaultSpreadTest.java`, `ProtosPerf025H1IndexedParameterEstablishmentTest.java`, `ProtosPerf025CallbackConsumerSpecializationTest.java`, `ProtosPerf025LazyInlineCallbackActivationTest.java`, `ProtosBug018CapturedMaterializedReadAfterOwnerTierTransitionTest.java`, `ProtosI115LazyCapturedFrameTest.java`, `ProtosPerf013SliceB2MaterializedCapturedWriteTest.java`, `ProtosPerf025FrameMaterializationSliceTest.java`, `ProtosPerf025LazyLexicalCaptureTest.java`, `ProtosPerf037CCapturedLexicalReadTest.java`, `ProtosPerf006B2C3B2SimpleDefaultBindingTest.java`, `ProtosPerf006B2C3B3CallSendDefaultBindingTest.java`, `ProtosPerf006B5ABytecodeDebuggerScopeTest.java`. No Protos tests, builds, Maven commands or benchmarks were run during this replay.
  - Historical file transitions: 0 added, 21 modified, 0 deleted (rename detection disabled for exact path accounting). All 21 historical path transitions preserve original modes and deletion state. Blob and parent identity checks allow only the explicitly authorized one-byte whitespace correction introduced in I115-V91: `tools/i115_gate_cases/primitive-if-true.protos:20`, original blob `b29428dbbee0f06243f105627c0a7ba472abea5a`, corrected blob `06420027434dabfae0edb537abfca37225e710aa`. All other payload blobs match exactly. Root `pom.xml` and `CHANGELOG.md` receive version/record metadata edits.
  - Original implementation: `28ba26844e3fa5ed29b25680770f103d80f98926`.

## 0.4.101-SNAPSHOT

- **I115-V120 — historical replay.**
  - Re-evaluate mutable lexical presence/selection guards on every execution and translate capture tests to D179 and PLAT066-C guest guarantees.
  - Intended semantic baseline: message syntax and selection/evaluation rules, signed-64 `long`/`Long` Integer carriers, live lexical capture and ordinary callable behavior. Internal specialized operations do not authorize new guest semantics.
  - **Historical semantic limitations and divergences:** Representation-level capture changes do not waive live binding visibility, mutation or context identity semantics. D013, D179 C0, D197-C, dynamic selection, lexical captures, ReturnHome, suspension, cancellation, Futures, Task/C-prime and Actor transfer are not independently certified or declared closed by this replay. Historical code is preserved except for the explicitly authorized whitespace correction described below.
  - Tests added or modified (not executed): `CanonicalClosureMaterializationTest.java`, `ProtosI068Slice5CapturedMaterializedLexicalLoweringTest.java`, `ProtosI115LazyCapturedFrameTest.java`, `ProtosPerf025LazyLexicalCaptureTest.java`, `ProtosPerf037CCapturedLexicalReadTest.java`. No Protos tests, builds, Maven commands or benchmarks were run during this replay.
  - Historical file transitions: 0 added, 7 modified, 0 deleted (rename detection disabled for exact path accounting). All 7 historical path transitions preserve original modes and deletion state. Blob and parent identity checks allow only the explicitly authorized one-byte whitespace correction introduced in I115-V91: `tools/i115_gate_cases/primitive-if-true.protos:20`, original blob `b29428dbbee0f06243f105627c0a7ba472abea5a`, corrected blob `06420027434dabfae0edb537abfca37225e710aa`. All other payload blobs match exactly. Root `pom.xml` and `CHANGELOG.md` receive version/record metadata edits.
  - Original implementation: `9f2008aaf277b6d88d70f9f74d0c882ed12d31cb`.

## 0.4.100-SNAPSHOT

- **I115-V119 — historical replay.**
  - Support context-observing inline callbacks with lazy genuine per-invocation activations. Detach present bindings into durable own storage before frame locals are reused.
  - Intended semantic baseline: message syntax and selection/evaluation rules, signed-64 `long`/`Long` Integer carriers, live lexical capture and ordinary callable behavior. Internal specialized operations do not authorize new guest semantics.
  - **Historical semantic limitations and divergences:** Activation lifetime and context identity are historical implementation claims, not certified runtime outcomes. D013, D179 C0, D197-C, dynamic selection, lexical captures, ReturnHome, suspension, cancellation, Futures, Task/C-prime and Actor transfer are not independently certified or declared closed by this replay. Historical code is preserved except for the explicitly authorized whitespace correction described below.
  - Tests added or modified (not executed): `ProtosArrayEachNativeIterationTest.java`, `ProtosPerf026D1IndexedEachInlineCallbackTest.java`. No Protos tests, builds, Maven commands or benchmarks were run during this replay.
  - Historical file transitions: 0 added, 6 modified, 0 deleted (rename detection disabled for exact path accounting). All 6 historical path transitions preserve original modes and deletion state. Blob and parent identity checks allow only the explicitly authorized one-byte whitespace correction introduced in I115-V91: `tools/i115_gate_cases/primitive-if-true.protos:20`, original blob `b29428dbbee0f06243f105627c0a7ba472abea5a`, corrected blob `06420027434dabfae0edb537abfca37225e710aa`. All other payload blobs match exactly. Root `pom.xml` and `CHANGELOG.md` receive version/record metadata edits.
  - Original implementation: `44b9fb13fc082b5a46851204f8ee06fef191db6b`.

## 0.4.99-SNAPSHOT

- **I115-V118 — historical replay.**
  - Run standard Map, IdentityMap, Environment and Bytes each through local inline loops, with standard-selection admission and association snapshots at iteration start.
  - Intended semantic baseline: message syntax and selection/evaluation rules, signed-64 `long`/`Long` Integer carriers, live lexical capture and ordinary callable behavior. Internal specialized operations do not authorize new guest semantics.
  - **Historical semantic limitations and divergences:** Snapshot ordering, callback effects and custom dispatch remain subject to later functional validation. D013, D179 C0, D197-C, dynamic selection, lexical captures, ReturnHome, suspension, cancellation, Futures, Task/C-prime and Actor transfer are not independently certified or declared closed by this replay. Historical code is preserved except for the explicitly authorized whitespace correction described below.
  - Tests added or modified (not executed): `ProtosPerf026D1IndexedEachInlineCallbackTest.java`, `ProtosPerf026D2AssociationEachInlineCallbackTest.java`, `ProtosPerf026D3EnvironmentEachInlineCallbackTest.java`. No Protos tests, builds, Maven commands or benchmarks were run during this replay.
  - Historical file transitions: 0 added, 6 modified, 0 deleted (rename detection disabled for exact path accounting). All 6 historical path transitions preserve original modes and deletion state. Blob and parent identity checks allow only the explicitly authorized one-byte whitespace correction introduced in I115-V91: `tools/i115_gate_cases/primitive-if-true.protos:20`, original blob `b29428dbbee0f06243f105627c0a7ba472abea5a`, corrected blob `06420027434dabfae0edb537abfca37225e710aa`. All other payload blobs match exactly. Root `pom.xml` and `CHANGELOG.md` receive version/record metadata edits.
  - Original implementation: `90ff7024afeb77a8d1affdae58a1f01ae0c49bb0`.

## 0.4.98-SNAPSHOT

- **I115-V117 — historical replay.**
  - Inline callbacks with non-local returns into their creating home root and reject direct writes to frozen receivers by routing them through ordinary mutation validation.
  - Intended semantic baseline: message syntax and selection/evaluation rules, signed-64 `long`/`Long` Integer carriers, live lexical capture and ordinary callable behavior. Internal specialized operations do not authorize new guest semantics.
  - **Historical semantic limitations and divergences:** The original repairs frozen-write admission and return-home lowering; this is not independent proof of every non-local-return or mutation path. D013, D179 C0, D197-C, dynamic selection, lexical captures, ReturnHome, suspension, cancellation, Futures, Task/C-prime and Actor transfer are not independently certified or declared closed by this replay. Historical code is preserved except for the explicitly authorized whitespace correction described below.
  - Tests added or modified (not executed): `ProtosPerf026C1WhileInlineCallbackTest.java`, `ProtosPerf026D1IndexedEachInlineCallbackTest.java`, `ProtosPerf025VirtualReturnHomeTest.java`, `ProtosI095BooleanProtocolInlineTest.java`. No Protos tests, builds, Maven commands or benchmarks were run during this replay.
  - Historical file transitions: 0 added, 6 modified, 0 deleted (rename detection disabled for exact path accounting). All 6 historical path transitions preserve original modes and deletion state. Blob and parent identity checks allow only the explicitly authorized one-byte whitespace correction introduced in I115-V91: `tools/i115_gate_cases/primitive-if-true.protos:20`, original blob `b29428dbbee0f06243f105627c0a7ba472abea5a`, corrected blob `06420027434dabfae0edb537abfca37225e710aa`. All other payload blobs match exactly. Root `pom.xml` and `CHANGELOG.md` receive version/record metadata edits.
  - Original implementation: `471e479d33c72e7abb71f978b1e3f92dd1c01788`.

## 0.4.97-SNAPSHOT

- **I115-V116 — historical replay.**
  - Recover standard Closure loop behavior with actual receiver selection, complete operand evaluation followed by Closure-domain validation, and structured native fallback.
  - Intended semantic baseline: message syntax and selection/evaluation rules, signed-64 `long`/`Long` Integer carriers, live lexical capture and ordinary callable behavior. Internal specialized operations do not authorize new guest semantics.
  - **Historical semantic limitations and divergences:** Invokable non-Closure operands remain invalid for standard loops; child-call outcome and suspension behavior are not executed in this replay. D013, D179 C0, D197-C, dynamic selection, lexical captures, ReturnHome, suspension, cancellation, Futures, Task/C-prime and Actor transfer are not independently certified or declared closed by this replay. Historical code is preserved except for the explicitly authorized whitespace correction described below.
  - Tests added or modified (not executed): `ProtosPerf026C1WhileInlineCallbackTest.java`, `ProtosI115NCompactBooleanTest.java`, `ProtosI115PCompactBooleanWhileTest.java`. No Protos tests, builds, Maven commands or benchmarks were run during this replay.
  - Historical file transitions: 0 added, 11 modified, 0 deleted (rename detection disabled for exact path accounting). All 11 historical path transitions preserve original modes and deletion state. Blob and parent identity checks allow only the explicitly authorized one-byte whitespace correction introduced in I115-V91: `tools/i115_gate_cases/primitive-if-true.protos:20`, original blob `b29428dbbee0f06243f105627c0a7ba472abea5a`, corrected blob `06420027434dabfae0edb537abfca37225e710aa`. All other payload blobs match exactly. Root `pom.xml` and `CHANGELOG.md` receive version/record metadata edits.
  - Original implementation: `fe0736936dfe9859d7b5e4f7bc170226255c219e`.

## 0.4.96-SNAPSHOT

- **I115-V115 — historical replay.**
  - Keep standard and/or right operands local, materialize live-frame callbacks for custom dispatch and attach inline-region tags to the callback literal source.
  - Intended semantic baseline: message syntax and selection/evaluation rules, signed-64 `long`/`Long` Integer carriers, live lexical capture and ordinary callable behavior. Internal specialized operations do not authorize new guest semantics.
  - **Historical semantic limitations and divergences:** Short-circuit semantics, custom selection and source instrumentation remain separate acceptance surfaces. D013, D179 C0, D197-C, dynamic selection, lexical captures, ReturnHome, suspension, cancellation, Futures, Task/C-prime and Actor transfer are not independently certified or declared closed by this replay. Historical code is preserved except for the explicitly authorized whitespace correction described below.
  - Tests added or modified (not executed): `ProtosI095BooleanProtocolInlineTest.java`. No Protos tests, builds, Maven commands or benchmarks were run during this replay.
  - Historical file transitions: 0 added, 2 modified, 0 deleted (rename detection disabled for exact path accounting). All 2 historical path transitions preserve original modes and deletion state. Blob and parent identity checks allow only the explicitly authorized one-byte whitespace correction introduced in I115-V91: `tools/i115_gate_cases/primitive-if-true.protos:20`, original blob `b29428dbbee0f06243f105627c0a7ba472abea5a`, corrected blob `06420027434dabfae0edb537abfca37225e710aa`. All other payload blobs match exactly. Root `pom.xml` and `CHANGELOG.md` receive version/record metadata edits.
  - Original implementation: `88beb746ca879a7c035d4a8cfb75414a076e50d5`.

## 0.4.95-SNAPSHOT

- **I115-V114 — historical replay.**
  - Materialize conditional callbacks over a live frame-backed view only for non-Boolean ordinary dispatch, so custom behavior observes the same local storage as inline execution.
  - Intended semantic baseline: message syntax and selection/evaluation rules, signed-64 `long`/`Long` Integer carriers, live lexical capture and ordinary callable behavior. Internal specialized operations do not authorize new guest semantics.
  - **Historical semantic limitations and divergences:** The custom branch creates genuine Closures; lifetime and lexical-write behavior require later runtime validation. D013, D179 C0, D197-C, dynamic selection, lexical captures, ReturnHome, suspension, cancellation, Futures, Task/C-prime and Actor transfer are not independently certified or declared closed by this replay. Historical code is preserved except for the explicitly authorized whitespace correction described below.
  - Tests added or modified (not executed): `ProtosI115NCompactBooleanTest.java`, `ProtosI115StructuredControlSmokeTest.java`. No Protos tests, builds, Maven commands or benchmarks were run during this replay.
  - Historical file transitions: 1 added, 5 modified, 0 deleted (rename detection disabled for exact path accounting). All 6 historical path transitions preserve original modes and deletion state. Blob and parent identity checks allow only the explicitly authorized one-byte whitespace correction introduced in I115-V91: `tools/i115_gate_cases/primitive-if-true.protos:20`, original blob `b29428dbbee0f06243f105627c0a7ba472abea5a`, corrected blob `06420027434dabfae0edb537abfca37225e710aa`. All other payload blobs match exactly. Root `pom.xml` and `CHANGELOG.md` receive version/record metadata edits.
  - Original implementation: `443dd0f853fd1d370115ce45c2d0312b921f0258`.

## 0.4.94-SNAPSHOT

- **I115-V113 — historical replay.**
  - Admit nested callbacks owned by one physical root without persistent frame authority, accounting for eliminated lexical hops and materialized nested free cells.
  - Intended semantic baseline: message syntax and selection/evaluation rules, signed-64 `long`/`Long` Integer carriers, live lexical capture and ordinary callable behavior. Internal specialized operations do not authorize new guest semantics.
  - **Historical semantic limitations and divergences:** Physical-root admission must preserve genuine lexical sharing; no nested-capture regression suite was executed. D013, D179 C0, D197-C, dynamic selection, lexical captures, ReturnHome, suspension, cancellation, Futures, Task/C-prime and Actor transfer are not independently certified or declared closed by this replay. Historical code is preserved except for the explicitly authorized whitespace correction described below.
  - No test files are added or modified by this historical commit. No Protos tests, builds, Maven commands or benchmarks were run during this replay.
  - Historical file transitions: 0 added, 3 modified, 0 deleted (rename detection disabled for exact path accounting). All 3 historical path transitions preserve original modes and deletion state. Blob and parent identity checks allow only the explicitly authorized one-byte whitespace correction introduced in I115-V91: `tools/i115_gate_cases/primitive-if-true.protos:20`, original blob `b29428dbbee0f06243f105627c0a7ba472abea5a`, corrected blob `06420027434dabfae0edb537abfca37225e710aa`. All other payload blobs match exactly. Root `pom.xml` and `CHANGELOG.md` receive version/record metadata edits.
  - Original implementation: `be3ba3eca10e859ea522f87d18c7db6189765738`.

## 0.4.93-SNAPSHOT

- **I115-V112 — historical replay.**
  - Restore ordinary-dispatch standard conditional methods for spread/named calls, extraction and reflective sends. Keep canonical Boolean sites internal and inline conditional storage local.
  - Intended semantic baseline: message syntax and selection/evaluation rules, signed-64 `long`/`Long` Integer carriers, live lexical capture and ordinary callable behavior. Internal specialized operations do not authorize new guest semantics.
  - **Historical semantic limitations and divergences:** Callback invocation count and exact result propagation remain intended semantics rather than newly executed evidence. D013, D179 C0, D197-C, dynamic selection, lexical captures, ReturnHome, suspension, cancellation, Futures, Task/C-prime and Actor transfer are not independently certified or declared closed by this replay. Historical code is preserved except for the explicitly authorized whitespace correction described below.
  - Tests added or modified (not executed): `ProtosI115NCompactBooleanTest.java`, `ProtosI115StructuredControlSmokeTest.java`. No Protos tests, builds, Maven commands or benchmarks were run during this replay.
  - Historical file transitions: 0 added, 5 modified, 0 deleted (rename detection disabled for exact path accounting). All 5 historical path transitions preserve original modes and deletion state. Blob and parent identity checks allow only the explicitly authorized one-byte whitespace correction introduced in I115-V91: `tools/i115_gate_cases/primitive-if-true.protos:20`, original blob `b29428dbbee0f06243f105627c0a7ba472abea5a`, corrected blob `06420027434dabfae0edb537abfca37225e710aa`. All other payload blobs match exactly. Root `pom.xml` and `CHANGELOG.md` receive version/record metadata edits.
  - Original implementation: `2404b28020e6ff8c510f960a5979aef6ad500bd1`.

## 0.4.92-SNAPSHOT

- **I115-V111 — historical replay.**
  - Select standard or custom conditional behavior and inline proven callbacks with their RootTag. Admit live Dynamic reads by name while retaining genuine Closures for unproven writes, parameters and resolutions.
  - Intended semantic baseline: message syntax and selection/evaluation rules, signed-64 `long`/`Long` Integer carriers, live lexical capture and ordinary callable behavior. Internal specialized operations do not authorize new guest semantics.
  - **Historical semantic limitations and divergences:** Custom conditional branches may materialize callbacks; semantic and instrumentation equivalence are not inferred from the inline plan. D013, D179 C0, D197-C, dynamic selection, lexical captures, ReturnHome, suspension, cancellation, Futures, Task/C-prime and Actor transfer are not independently certified or declared closed by this replay. Historical code is preserved except for the explicitly authorized whitespace correction described below.
  - Tests added or modified (not executed): `ProtosPerf026B1BooleanInlineCallbackTest.java`, `ProtosPerf026B2BooleanInlineCallbackTest.java`, `ProtosPerf026B3BooleanInlineCallbackTest.java`, `ProtosPerf026D1IndexedEachInlineCallbackTest.java`, `ProtosI095BooleanControlInlineTest.java`, `ProtosI095BooleanProtocolInlineTest.java`, `ProtosI115NCompactBooleanTest.java`, `ProtosI115StructuredControlSmokeTest.java`. No Protos tests, builds, Maven commands or benchmarks were run during this replay.
  - Historical file transitions: 0 added, 10 modified, 0 deleted (rename detection disabled for exact path accounting). All 10 historical path transitions preserve original modes and deletion state. Blob and parent identity checks allow only the explicitly authorized one-byte whitespace correction introduced in I115-V91: `tools/i115_gate_cases/primitive-if-true.protos:20`, original blob `b29428dbbee0f06243f105627c0a7ba472abea5a`, corrected blob `06420027434dabfae0edb537abfca37225e710aa`. All other payload blobs match exactly. Root `pom.xml` and `CHANGELOG.md` receive version/record metadata edits.
  - Original implementation: `9212aa82556c8395eb17fd476d6b55a38d585c75`.

## 0.4.91-SNAPSHOT

- **I115-V110 — historical replay.**
  - Migrate control, call and lexical execution details to the current bytecode path. Overlay live physical-parent LOCAL/CELL bindings when tooling requests an inline callback scope and add diagnostic Error views.
  - Intended semantic baseline: message syntax and selection/evaluation rules, signed-64 `long`/`Long` Integer carriers, live lexical capture and ordinary callable behavior. Internal specialized operations do not authorize new guest semantics.
  - **Historical semantic limitations and divergences:** Diagnostic projections preserve the original transfer path; debugger and inline lexical visibility are not functionally validated here. D013, D179 C0, D197-C, dynamic selection, lexical captures, ReturnHome, suspension, cancellation, Futures, Task/C-prime and Actor transfer are not independently certified or declared closed by this replay. Historical code is preserved except for the explicitly authorized whitespace correction described below.
  - Tests added or modified (not executed): `ProtosPerf026B1BooleanInlineCallbackTest.java`, `ProtosPerf026B2BooleanInlineCallbackTest.java`, `ProtosPerf026B3BooleanInlineCallbackTest.java`. No Protos tests, builds, Maven commands or benchmarks were run during this replay.
  - Historical file transitions: 0 added, 5 modified, 0 deleted (rename detection disabled for exact path accounting). All 5 historical path transitions preserve original modes and deletion state. Blob and parent identity checks allow only the explicitly authorized one-byte whitespace correction introduced in I115-V91: `tools/i115_gate_cases/primitive-if-true.protos:20`, original blob `b29428dbbee0f06243f105627c0a7ba472abea5a`, corrected blob `06420027434dabfae0edb537abfca37225e710aa`. All other payload blobs match exactly. Root `pom.xml` and `CHANGELOG.md` receive version/record metadata edits.
  - Original implementation: `5ac2f849a1a410a25d380399e28083956019d88a`.

## 0.4.90-SNAPSHOT

- **I115-V109 — historical replay.**
  - Implement native Array.each iteration with literal callback inlining guarded by actual standard each selection and canonical Object.call; use ordinary dispatch on other selections.
  - Intended semantic baseline: message syntax and selection/evaluation rules, signed-64 `long`/`Long` Integer carriers, live lexical capture and ordinary callable behavior. Internal specialized operations do not authorize new guest semantics.
  - **Historical semantic limitations and divergences:** Selection invalidation, callback callability and iteration behavior still require independent acceptance. D013, D179 C0, D197-C, dynamic selection, lexical captures, ReturnHome, suspension, cancellation, Futures, Task/C-prime and Actor transfer are not independently certified or declared closed by this replay. Historical code is preserved except for the explicitly authorized whitespace correction described below.
  - Tests added or modified (not executed): `ProtosArrayEachNativeIterationTest.java`, `ProtosPerf025InlineCallbackPreparationTest.java`, `ProtosPerf026D1IndexedEachInlineCallbackTest.java`. No Protos tests, builds, Maven commands or benchmarks were run during this replay.
  - Historical file transitions: 1 added, 6 modified, 0 deleted (rename detection disabled for exact path accounting). All 7 historical path transitions preserve original modes and deletion state. Blob and parent identity checks allow only the explicitly authorized one-byte whitespace correction introduced in I115-V91: `tools/i115_gate_cases/primitive-if-true.protos:20`, original blob `b29428dbbee0f06243f105627c0a7ba472abea5a`, corrected blob `06420027434dabfae0edb537abfca37225e710aa`. All other payload blobs match exactly. Root `pom.xml` and `CHANGELOG.md` receive version/record metadata edits.
  - Original implementation: `a768bbdc940723520937867efb0196fee6d8ee92`.

## 0.4.89-SNAPSHOT

- **I115-V108 — historical replay.**
  - Restore debugger lexical scopes and defining-source module visibility for imported constant Closures; reconcile native-control tests with physical Closure roots.
  - Intended semantic baseline: message syntax and selection/evaluation rules, signed-64 `long`/`Long` Integer carriers, live lexical capture and ordinary callable behavior. Internal specialized operations do not authorize new guest semantics.
  - **Historical semantic limitations and divergences:** Tooling scope and native-control regressions are incorporated without executing debugger or guest tests. D013, D179 C0, D197-C, dynamic selection, lexical captures, ReturnHome, suspension, cancellation, Futures, Task/C-prime and Actor transfer are not independently certified or declared closed by this replay. Historical code is preserved except for the explicitly authorized whitespace correction described below.
  - Tests added or modified (not executed): `ProtosPerf032ExecutionModeTest.java`, `ProtosPerf025InlineCallbackPreparationTest.java`. No Protos tests, builds, Maven commands or benchmarks were run during this replay.
  - Historical file transitions: 0 added, 6 modified, 0 deleted (rename detection disabled for exact path accounting). All 6 historical path transitions preserve original modes and deletion state. Blob and parent identity checks allow only the explicitly authorized one-byte whitespace correction introduced in I115-V91: `tools/i115_gate_cases/primitive-if-true.protos:20`, original blob `b29428dbbee0f06243f105627c0a7ba472abea5a`, corrected blob `06420027434dabfae0edb537abfca37225e710aa`. All other payload blobs match exactly. Root `pom.xml` and `CHANGELOG.md` receive version/record metadata edits.
  - Original implementation: `172398cd1a4203051e2c474715a92d1a8112b87a`.

## 0.4.88-SNAPSHOT

- **I115-V107 — historical replay.**
  - Reconcile B5 ReturnHome assertions for zero-argument host entry and checkpoint the debugger lexical-scope investigation.
  - Intended semantic baseline: message syntax and selection/evaluation rules, signed-64 `long`/`Long` Integer carriers, live lexical capture and ordinary callable behavior. Internal specialized operations do not authorize new guest semantics.
  - **Historical semantic limitations and divergences:** Debugger scope behavior remains under investigation in this historical checkpoint. D013, D179 C0, D197-C, dynamic selection, lexical captures, ReturnHome, suspension, cancellation, Futures, Task/C-prime and Actor transfer are not independently certified or declared closed by this replay. Historical code is preserved except for the explicitly authorized whitespace correction described below.
  - Tests added or modified (not executed): `ProtosPerf032ExecutionModeTest.java`. No Protos tests, builds, Maven commands or benchmarks were run during this replay.
  - Historical file transitions: 0 added, 1 modified, 0 deleted (rename detection disabled for exact path accounting). All 1 historical path transitions preserve original modes and deletion state. Blob and parent identity checks allow only the explicitly authorized one-byte whitespace correction introduced in I115-V91: `tools/i115_gate_cases/primitive-if-true.protos:20`, original blob `b29428dbbee0f06243f105627c0a7ba472abea5a`, corrected blob `06420027434dabfae0edb537abfca37225e710aa`. All other payload blobs match exactly. Root `pom.xml` and `CHANGELOG.md` receive version/record metadata edits.
  - Original implementation: `c982dc862cdbdb0a2396bdffb6aafacf69b38c73`.

## 0.4.87-SNAPSHOT

- **I115-V106 — historical replay.**
  - Translate Closure-call regression expectations to arity-selected bytecode calls, callee-owned parameter binding and distinct physical Closure roots/CallTargets.
  - Intended semantic baseline: message syntax and selection/evaluation rules, signed-64 `long`/`Long` Integer carriers, live lexical capture and ordinary callable behavior. Internal specialized operations do not authorize new guest semantics.
  - **Historical semantic limitations and divergences:** Root cloning may change physical identity; translated structural assertions do not replace guest semantic acceptance. D013, D179 C0, D197-C, dynamic selection, lexical captures, ReturnHome, suspension, cancellation, Futures, Task/C-prime and Actor transfer are not independently certified or declared closed by this replay. Historical code is preserved except for the explicitly authorized whitespace correction described below.
  - Tests added or modified (not executed): `ProtosI096InlineClosureLiteralCallTest.java`. No Protos tests, builds, Maven commands or benchmarks were run during this replay.
  - Historical file transitions: 0 added, 1 modified, 0 deleted (rename detection disabled for exact path accounting). All 1 historical path transitions preserve original modes and deletion state. Blob and parent identity checks allow only the explicitly authorized one-byte whitespace correction introduced in I115-V91: `tools/i115_gate_cases/primitive-if-true.protos:20`, original blob `b29428dbbee0f06243f105627c0a7ba472abea5a`, corrected blob `06420027434dabfae0edb537abfca37225e710aa`. All other payload blobs match exactly. Root `pom.xml` and `CHANGELOG.md` receive version/record metadata edits.
  - Original implementation: `1994f148af0ef000b5ce2ca7f6ba46660c4b9dd1`.

## 0.4.86-SNAPSHOT

- **I115-V105 — historical replay.**
  - Restore callable selection across all arities. Use specialized source dispatch only while selected call behavior is canonical; already-selected bound methods must not perform another callable lookup.
  - Intended semantic baseline: message syntax and selection/evaluation rules, signed-64 `long`/`Long` Integer carriers, live lexical capture and ordinary callable behavior. Internal specialized operations do not authorize new guest semantics.
  - **Historical semantic limitations and divergences:** All-arity selection fixes are historical implementation intent; no call regression suite was run. D013, D179 C0, D197-C, dynamic selection, lexical captures, ReturnHome, suspension, cancellation, Futures, Task/C-prime and Actor transfer are not independently certified or declared closed by this replay. Historical code is preserved except for the explicitly authorized whitespace correction described below.
  - Tests added or modified (not executed): `ProtosI115LexicalStorageConvergenceTest.java`, `ProtosI096InlineClosureLiteralCallTest.java`, `ProtosI115ARuntimeAdmissionTest.java`. No Protos tests, builds, Maven commands or benchmarks were run during this replay.
  - Historical file transitions: 0 added, 11 modified, 0 deleted (rename detection disabled for exact path accounting). All 11 historical path transitions preserve original modes and deletion state. Blob and parent identity checks allow only the explicitly authorized one-byte whitespace correction introduced in I115-V91: `tools/i115_gate_cases/primitive-if-true.protos:20`, original blob `b29428dbbee0f06243f105627c0a7ba472abea5a`, corrected blob `06420027434dabfae0edb537abfca37225e710aa`. All other payload blobs match exactly. Root `pom.xml` and `CHANGELOG.md` receive version/record metadata edits.
  - Original implementation: `bdb023ab018bad3e5bffd7de07ce6b95e74a4cdf`.

## 0.4.85-SNAPSHOT

- **I115-V104 — historical replay.**
  - Reconcile Protos semantics with the Bytecode DSL implementation across compiler, runtime and regression sources; retain the historical specialized execution architecture.
  - Intended semantic baseline: message syntax and selection/evaluation rules, signed-64 `long`/`Long` Integer carriers, live lexical capture and ordinary callable behavior. Internal specialized operations do not authorize new guest semantics.
  - **Historical semantic limitations and divergences:** This broad semantic reconciliation checkpoint does not independently discharge the remaining dynamic selection, numeric, control and invocation acceptance obligations. D013, D179 C0, D197-C, dynamic selection, lexical captures, ReturnHome, suspension, cancellation, Futures, Task/C-prime and Actor transfer are not independently certified or declared closed by this replay. Historical code is preserved except for the explicitly authorized whitespace correction described below.
  - Tests added or modified (not executed): `indexed-protocol-selection.protos`, `grammar-and-lexical-surface.protos`, `manifest.tsv`, `ProtosI115BooleanOperatorTest.java`, `ProtosI115BytesDirectOperationTest.java`, `ProtosI115ContextualActivationTest.java`, `ProtosI115IndexedOperationTest.java`, `ProtosI115StringAdditionTest.java`, `CanonicalBindingAnalyzerTest.java`, `ProtosI068Slice7ActivationLexicalDecompositionTest.java`, `ProtosI115LexicalStorageConvergenceTest.java`, `ProtosI115PublishedLexicalContextTest.java`, `ProtosStandardStringProtocolTest.java`, `ProtosLexicalBindingAuthoritySeamTest.java`, `ProtosExecutionContextValueTest.java`. No Protos tests, builds, Maven commands or benchmarks were run during this replay.
  - Historical file transitions: 9 added, 31 modified, 0 deleted (rename detection disabled for exact path accounting). All 40 historical path transitions preserve original modes and deletion state. Blob and parent identity checks allow only the explicitly authorized one-byte whitespace correction introduced in I115-V91: `tools/i115_gate_cases/primitive-if-true.protos:20`, original blob `b29428dbbee0f06243f105627c0a7ba472abea5a`, corrected blob `06420027434dabfae0edb537abfca37225e710aa`. All other payload blobs match exactly. Root `pom.xml` and `CHANGELOG.md` receive version/record metadata edits.
  - Original implementation: `14149b2dd4683bcedeb06a7875a430b6945a1042`.

## 0.4.84-SNAPSHOT

- **I115-V103 — historical replay.**
  - Resume ordinary lexical/receiver lookup when a captured module binding is absent, including standard SlotNotFound behavior after lookup exhaustion.
  - Intended semantic baseline: message syntax and selection/evaluation rules, signed-64 `long`/`Long` Integer carriers, live lexical capture and ordinary callable behavior. Internal specialized operations do not authorize new guest semantics.
  - **Historical semantic limitations and divergences:** The original explicitly retains direct reads of established module bindings without checking nearer dynamic lexical shadowing. D013, D179 C0, D197-C, dynamic selection, lexical captures, ReturnHome, suspension, cancellation, Futures, Task/C-prime and Actor transfer are not independently certified or declared closed by this replay. Historical code is preserved except for the explicitly authorized whitespace correction described below.
  - Tests added or modified (not executed): `ProtosI115LexicalStorageConvergenceTest.java`. No Protos tests, builds, Maven commands or benchmarks were run during this replay.
  - Historical file transitions: 0 added, 2 modified, 0 deleted (rename detection disabled for exact path accounting). All 2 historical path transitions preserve original modes and deletion state. Blob and parent identity checks allow only the explicitly authorized one-byte whitespace correction introduced in I115-V91: `tools/i115_gate_cases/primitive-if-true.protos:20`, original blob `b29428dbbee0f06243f105627c0a7ba472abea5a`, corrected blob `06420027434dabfae0edb537abfca37225e710aa`. All other payload blobs match exactly. Root `pom.xml` and `CHANGELOG.md` receive version/record metadata edits.
  - Original implementation: `9b8d84f9ec5f6fd8a1e933565c32804aaa4847f0`.

## 0.4.83-SNAPSHOT

- **I115-V102 — historical replay.**
  - Restore ordinary lexical fallback for LOCAL Candidate reads before binding establishment while keeping established local reads optimized.
  - Intended semantic baseline: message syntax and selection/evaluation rules, signed-64 `long`/`Long` Integer carriers, live lexical capture and ordinary callable behavior. Internal specialized operations do not authorize new guest semantics.
  - **Historical semantic limitations and divergences:** Assignment destination selection remains separate; other dynamic binding-presence and nearer-scope cases are not certified by this change. D013, D179 C0, D197-C, dynamic selection, lexical captures, ReturnHome, suspension, cancellation, Futures, Task/C-prime and Actor transfer are not independently certified or declared closed by this replay. Historical code is preserved except for the explicitly authorized whitespace correction described below.
  - Tests added or modified (not executed): `ProtosI068Slice7ActivationLexicalDecompositionTest.java`. No Protos tests, builds, Maven commands or benchmarks were run during this replay.
  - Historical file transitions: 0 added, 2 modified, 0 deleted (rename detection disabled for exact path accounting). All 2 historical path transitions preserve original modes and deletion state. Blob and parent identity checks allow only the explicitly authorized one-byte whitespace correction introduced in I115-V91: `tools/i115_gate_cases/primitive-if-true.protos:20`, original blob `b29428dbbee0f06243f105627c0a7ba472abea5a`, corrected blob `06420027434dabfae0edb537abfca37225e710aa`. All other payload blobs match exactly. Root `pom.xml` and `CHANGELOG.md` receive version/record metadata edits.
  - Original implementation: `a9b56ef7066b555942ccfe8f8babd71f2f854f2d`.

## 0.4.82-SNAPSHOT

- **I115-V101 — historical replay.**
  - Rebaseline the stored GraalVM 25.5 structural gate at the historical checkpoint.
  - Intended semantic baseline: message syntax and selection/evaluation rules, signed-64 `long`/`Long` Integer carriers, live lexical capture and ordinary callable behavior. Internal specialized operations do not authorize new guest semantics.
  - **Historical semantic limitations and divergences:** Changed baseline data records the original checkpoint, not a newly executed gate or an independently accepted performance result. D013, D179 C0, D197-C, dynamic selection, lexical captures, ReturnHome, suspension, cancellation, Futures, Task/C-prime and Actor transfer are not independently certified or declared closed by this replay. Historical code is preserved except for the explicitly authorized whitespace correction described below.
  - No test files are added or modified by this historical commit. No Protos tests, builds, Maven commands or benchmarks were run during this replay.
  - Historical file transitions: 1 added, 2 modified, 0 deleted (rename detection disabled for exact path accounting). All 3 historical path transitions preserve original modes and deletion state. Blob and parent identity checks allow only the explicitly authorized one-byte whitespace correction introduced in I115-V91: `tools/i115_gate_cases/primitive-if-true.protos:20`, original blob `b29428dbbee0f06243f105627c0a7ba472abea5a`, corrected blob `06420027434dabfae0edb537abfca37225e710aa`. All other payload blobs match exactly. Root `pom.xml` and `CHANGELOG.md` receive version/record metadata edits.
  - Original implementation: `d3f5e4f39a15e1470967fa15f3689ba76da43f38`.

## 0.4.81-SNAPSHOT

- **I115-V100 — historical replay.**
  - Remove ReturnHome checks from proven-unobservable hot paths. Monotonically invalidate the no-home assumption when a published entry carries an owned physical home.
  - Intended semantic baseline: message syntax and selection/evaluation rules, signed-64 `long`/`Long` Integer carriers, live lexical capture and ordinary callable behavior. Internal specialized operations do not authorize new guest semantics.
  - **Historical semantic limitations and divergences:** The optimization depends on no-home proofs and cold admission; this replay does not measure speed or establish proof completeness. D013, D179 C0, D197-C, dynamic selection, lexical captures, ReturnHome, suspension, cancellation, Futures, Task/C-prime and Actor transfer are not independently certified or declared closed by this replay. Historical code is preserved except for the explicitly authorized whitespace correction described below.
  - Tests added or modified (not executed): `ProtosDispatchTestSupport.java`, `ProtosPerf025H1LazyRootActivationTest.java`, `ProtosPerf032G7MinimalDirectHeaderTest.java`, `ProtosPerf025VirtualReturnHomeTest.java`, `ProtosPerf006B2D4AComposedCallTargetTest.java`, `ProtosI115H7MethodCallConvergenceTest.java`, `ProtosI115BDeferredCallerTest.java`. No Protos tests, builds, Maven commands or benchmarks were run during this replay.
  - Historical file transitions: 0 added, 11 modified, 0 deleted (rename detection disabled for exact path accounting). All 11 historical path transitions preserve original modes and deletion state. Blob and parent identity checks allow only the explicitly authorized one-byte whitespace correction introduced in I115-V91: `tools/i115_gate_cases/primitive-if-true.protos:20`, original blob `b29428dbbee0f06243f105627c0a7ba472abea5a`, corrected blob `06420027434dabfae0edb537abfca37225e710aa`. All other payload blobs match exactly. Root `pom.xml` and `CHANGELOG.md` receive version/record metadata edits.
  - Original implementation: `3fdf1e1144457526c98593565dceafcd95ecdb54`.

## 0.4.80-SNAPSHOT

- **I115-V99 — historical replay.**
  - Bypass redundant generic CallNode classification for prepared zero-argument host entry while retaining entered Context, caller provenance and B5 layout. Add host-entry arity regression coverage.
  - Intended semantic baseline: message syntax and selection/evaluation rules, signed-64 `long`/`Long` Integer carriers, live lexical capture and ordinary callable behavior. Internal specialized operations do not authorize new guest semantics.
  - **Historical semantic limitations and divergences:** The direct host path is preserved without claiming host-boundary or suspension acceptance. D013, D179 C0, D197-C, dynamic selection, lexical captures, ReturnHome, suspension, cancellation, Futures, Task/C-prime and Actor transfer are not independently certified or declared closed by this replay. Historical code is preserved except for the explicitly authorized whitespace correction described below.
  - Tests added or modified (not executed): `ProtosPerf033CanonicalCallableInteropTest.java`. No Protos tests, builds, Maven commands or benchmarks were run during this replay.
  - Historical file transitions: 0 added, 2 modified, 0 deleted (rename detection disabled for exact path accounting). All 2 historical path transitions preserve original modes and deletion state. Blob and parent identity checks allow only the explicitly authorized one-byte whitespace correction introduced in I115-V91: `tools/i115_gate_cases/primitive-if-true.protos:20`, original blob `b29428dbbee0f06243f105627c0a7ba472abea5a`, corrected blob `06420027434dabfae0edb537abfca37225e710aa`. All other payload blobs match exactly. Root `pom.xml` and `CHANGELOG.md` receive version/record metadata edits.
  - Original implementation: `c914562306dc50786ca6805c8de8e3d614e16575`.

## 0.4.79-SNAPSHOT

- **I115-V98 — historical replay.**
  - Apply ReturnHome mode specialization to unary and SEND1 identity PICs.
  - Intended semantic baseline: message syntax and selection/evaluation rules, signed-64 `long`/`Long` Integer carriers, live lexical capture and ordinary callable behavior. Internal specialized operations do not authorize new guest semantics.
  - **Historical semantic limitations and divergences:** The added fixed-arity paths inherit the historical ownership assumptions; no invocation regression suite was executed. D013, D179 C0, D197-C, dynamic selection, lexical captures, ReturnHome, suspension, cancellation, Futures, Task/C-prime and Actor transfer are not independently certified or declared closed by this replay. Historical code is preserved except for the explicitly authorized whitespace correction described below.
  - No test files are added or modified by this historical commit. No Protos tests, builds, Maven commands or benchmarks were run during this replay.
  - Historical file transitions: 0 added, 3 modified, 0 deleted (rename detection disabled for exact path accounting). All 3 historical path transitions preserve original modes and deletion state. Blob and parent identity checks allow only the explicitly authorized one-byte whitespace correction introduced in I115-V91: `tools/i115_gate_cases/primitive-if-true.protos:20`, original blob `b29428dbbee0f06243f105627c0a7ba472abea5a`, corrected blob `06420027434dabfae0edb537abfca37225e710aa`. All other payload blobs match exactly. Root `pom.xml` and `CHANGELOG.md` receive version/record metadata edits.
  - Original implementation: `fb3db48c379fd2b9221614cb3fdba5ffd7ad8004`.

## 0.4.78-SNAPSHOT

- **I115-V97 — historical replay.**
  - Specialize ReturnHome handling per exact Closure-identity PIC entry into stable invocation modes; retain per-call selection when Closures merely share a target.
  - Intended semantic baseline: message syntax and selection/evaluation rules, signed-64 `long`/`Long` Integer carriers, live lexical capture and ordinary callable behavior. Internal specialized operations do not authorize new guest semantics.
  - **Historical semantic limitations and divergences:** PIC-specific ownership proofs require later functional acceptance; target sharing alone must not freeze a captured home. D013, D179 C0, D197-C, dynamic selection, lexical captures, ReturnHome, suspension, cancellation, Futures, Task/C-prime and Actor transfer are not independently certified or declared closed by this replay. Historical code is preserved except for the explicitly authorized whitespace correction described below.
  - Tests added or modified (not executed): `ProtosNonLocalReturnTest.java`, `ProtosPerf025VirtualReturnHomeTest.java`, `ProtosI115H6HostEntryConvergenceTest.java`, `ProtosI115ARuntimeAdmissionTest.java`, `ProtosI115BDeferredCallerTest.java`. No Protos tests, builds, Maven commands or benchmarks were run during this replay.
  - Historical file transitions: 0 added, 9 modified, 0 deleted (rename detection disabled for exact path accounting). All 9 historical path transitions preserve original modes and deletion state. Blob and parent identity checks allow only the explicitly authorized one-byte whitespace correction introduced in I115-V91: `tools/i115_gate_cases/primitive-if-true.protos:20`, original blob `b29428dbbee0f06243f105627c0a7ba472abea5a`, corrected blob `06420027434dabfae0edb537abfca37225e710aa`. All other payload blobs match exactly. Root `pom.xml` and `CHANGELOG.md` receive version/record metadata edits.
  - Original implementation: `5dddba09e8bf9708830cf68facb7cb0fe159e9f3`.

## 0.4.77-SNAPSHOT

- **I115-V96 — historical replay.**
  - Restore an omitted ReturnHome ownership/suspension call-dispatch operation in the shared dispatcher.
  - Intended semantic baseline: message syntax and selection/evaluation rules, signed-64 `long`/`Long` Integer carriers, live lexical capture and ordinary callable behavior. Internal specialized operations do not authorize new guest semantics.
  - **Historical semantic limitations and divergences:** This one-line historical repair is preserved without treating it as proof that all call arities and suspension paths are correct. D013, D179 C0, D197-C, dynamic selection, lexical captures, ReturnHome, suspension, cancellation, Futures, Task/C-prime and Actor transfer are not independently certified or declared closed by this replay. Historical code is preserved except for the explicitly authorized whitespace correction described below.
  - No test files are added or modified by this historical commit. No Protos tests, builds, Maven commands or benchmarks were run during this replay.
  - Historical file transitions: 0 added, 1 modified, 0 deleted (rename detection disabled for exact path accounting). All 1 historical path transitions preserve original modes and deletion state. Blob and parent identity checks allow only the explicitly authorized one-byte whitespace correction introduced in I115-V91: `tools/i115_gate_cases/primitive-if-true.protos:20`, original blob `b29428dbbee0f06243f105627c0a7ba472abea5a`, corrected blob `06420027434dabfae0edb537abfca37225e710aa`. All other payload blobs match exactly. Root `pom.xml` and `CHANGELOG.md` receive version/record metadata edits.
  - Original implementation: `2c93c2508715ed5552ef1c0f7723a9be1cd1c482`.

## 0.4.76-SNAPSHOT

- **I115-V95 — historical replay.**
  - Align object storage, call selection and lexical tests. Guard canonical root call selection with both Shape and an independently invalidated selector-stability Assumption.
  - Intended semantic baseline: message syntax and selection/evaluation rules, signed-64 `long`/`Long` Integer carriers, live lexical capture and ordinary callable behavior. Internal specialized operations do not authorize new guest semantics.
  - **Historical semantic limitations and divergences:** A same-Shape selector rewrite must invalidate selection; creator-context retention and lexical representation remain separate acceptance concerns. D013, D179 C0, D197-C, dynamic selection, lexical captures, ReturnHome, suspension, cancellation, Futures, Task/C-prime and Actor transfer are not independently certified or declared closed by this replay. Historical code is preserved except for the explicitly authorized whitespace correction described below.
  - Tests added or modified (not executed): `ProtosPerf025VirtualReturnHomeTest.java`, `ProtosI115ARuntimeAdmissionTest.java`, `ProtosI115BDeferredCallerTest.java`, `ProtosPerf025ObjectModelPayAsYouGrowTest.java`. No Protos tests, builds, Maven commands or benchmarks were run during this replay.
  - Historical file transitions: 0 added, 8 modified, 0 deleted (rename detection disabled for exact path accounting). All 8 historical path transitions preserve original modes and deletion state. Blob and parent identity checks allow only the explicitly authorized one-byte whitespace correction introduced in I115-V91: `tools/i115_gate_cases/primitive-if-true.protos:20`, original blob `b29428dbbee0f06243f105627c0a7ba472abea5a`, corrected blob `06420027434dabfae0edb537abfca37225e710aa`. All other payload blobs match exactly. Root `pom.xml` and `CHANGELOG.md` receive version/record metadata edits.
  - Original implementation: `bcd84b7f38d46671dd8621395c68f6d53655004c`.

## 0.4.75-SNAPSHOT

- **I115-V94 — historical replay.**
  - Close owned ReturnHomes during PLAT070 continuation unwind. Use bytecode TryFinally for configured unwind exceptions and consume exact-home returns at the owning invocation boundary, including resumption.
  - Intended semantic baseline: message syntax and selection/evaluation rules, signed-64 `long`/`Long` Integer carriers, live lexical capture and ordinary callable behavior. Internal specialized operations do not authorize new guest semantics.
  - **Historical semantic limitations and divergences:** Continuation unwind coverage is incorporated as source evidence; the replay executes no control-transfer tests. D013, D179 C0, D197-C, dynamic selection, lexical captures, ReturnHome, suspension, cancellation, Futures, Task/C-prime and Actor transfer are not independently certified or declared closed by this replay. Historical code is preserved except for the explicitly authorized whitespace correction described below.
  - Tests added or modified (not executed): `closure-call-and-return.protos`, `ensure-control-transfer.protos`, `nonlocal-and-escaped-return.protos`, `ProtosDispatchTestSupport.java`, `ProtosPerf006B4CStructuredEnsureTest.java`, `ProtosI143NamedObjectCallBridgeTest.java`, `ProtosI143SelectedNamedBindingTest.java`, `ProtosNonLocalReturnTest.java`, `ProtosPerf006B4ECancellationUnwindTest.java`, `ProtosPerf025VirtualReturnHomeTest.java`, `ProtosI145AConstructProtocolTest.java`, `ProtosPerf025CompactCalleeExecutionTest.java`, `ProtosI115ARuntimeAdmissionTest.java`, `ProtosI115BDeferredCallerTest.java`. No Protos tests, builds, Maven commands or benchmarks were run during this replay.
  - Historical file transitions: 0 added, 27 modified, 0 deleted (rename detection disabled for exact path accounting). All 27 historical path transitions preserve original modes and deletion state. Blob and parent identity checks allow only the explicitly authorized one-byte whitespace correction introduced in I115-V91: `tools/i115_gate_cases/primitive-if-true.protos:20`, original blob `b29428dbbee0f06243f105627c0a7ba472abea5a`, corrected blob `06420027434dabfae0edb537abfca37225e710aa`. All other payload blobs match exactly. Root `pom.xml` and `CHANGELOG.md` receive version/record metadata edits.
  - Original implementation: `19294381b56f5be226276c540240150629b1a587`.

## 0.4.74-SNAPSHOT

- **I115-V93 — historical replay.**
  - Converge Closure dispatch and B5 activation transport. Preserve positional/named argument information until cold name-aware selection freezes the packet; adjust owned ReturnHome lifetime on normal, suspended and abrupt outcomes.
  - Intended semantic baseline: message syntax and selection/evaluation rules, signed-64 `long`/`Long` Integer carriers, live lexical capture and ordinary callable behavior. Internal specialized operations do not authorize new guest semantics.
  - **Historical semantic limitations and divergences:** Broad invocation transport changes do not independently establish full D013, ReturnHome or suspension conformance. D013, D179 C0, D197-C, dynamic selection, lexical captures, ReturnHome, suspension, cancellation, Futures, Task/C-prime and Actor transfer are not independently certified or declared closed by this replay. Historical code is preserved except for the explicitly authorized whitespace correction described below.
  - Tests added or modified (not executed): `named-arguments.protos`, `ProtosDispatchTestSupport.java`, `ProtosI109ASharedLocalEachLoweringTest.java`, `ProtosInstructionNames.java`, `ProtosPerf006B4ATransferSubstrateTest.java`, `ProtosPerf006B4CStructuredEnsureTest.java`, `ProtosI143NamedActivationPresenceTest.java`, `ProtosI143NamedObjectCallBridgeTest.java`, `ProtosI143NamedSendSelectionTest.java`, `ProtosI143SelectedNamedBindingTest.java`, `ProtosI143SelectedSourcePreparationTest.java`, `ProtosNonLocalReturnTest.java`, `ProtosPerf006B4ECancellationUnwindTest.java`, `ProtosPerf006B4FStructuredWhileTest.java`, `ProtosI072PhaseEStructuredSendConvergenceTest.java`, `ProtosI145AConstructProtocolTest.java`, `ProtosPerf025CompactCalleeExecutionTest.java`, `ProtosI091NumericStructuralAdmissionTest.java`, `ProtosI107NonSuspensionFactsTest.java`, `ProtosI108BCallNDispatchTest.java`, `ProtosI115H7MethodCallConvergenceTest.java`, `ProtosI115HStaticClosureCallTest.java`, `ProtosI115ARuntimeAdmissionTest.java`, `ProtosI115BDeferredCallerTest.java`, `ProtosTcpAcceptorActorDelegationTest.java`, `ProtosActorBootstrapTest.java`, `ProtosI158ActorBootstrapSuspensionTest.java`, `ProtosSemanticTransferFamilyTest.java`, `ProtosGuardedLookupTest.java`, `ProtosPerf025ObjectModelPayAsYouGrowTest.java`, `ProtosActivationLexicalNamesTest.java`, `ProtosLongRepresentationTest.java`, `ProtosByteIoReadDeliveryTest.java`, `ProtosFileFlowReadDeliveryTest.java`, `ProtosNetworkCapabilityTransferTest.java`, `ProtosTcpAcceptorTest.java`, `ProtosTcpListenerFoundationTest.java`. No Protos tests, builds, Maven commands or benchmarks were run during this replay.
  - Historical file transitions: 0 added, 49 modified, 0 deleted (rename detection disabled for exact path accounting). All 49 historical path transitions preserve original modes and deletion state. Blob and parent identity checks allow only the explicitly authorized one-byte whitespace correction introduced in I115-V91: `tools/i115_gate_cases/primitive-if-true.protos:20`, original blob `b29428dbbee0f06243f105627c0a7ba472abea5a`, corrected blob `06420027434dabfae0edb537abfca37225e710aa`. All other payload blobs match exactly. Root `pom.xml` and `CHANGELOG.md` receive version/record metadata edits.
  - Original implementation: `38dfb9cca33a7bf6743d7ee6b272ae5475b10963`.

## 0.4.73-SNAPSHOT

- **I115-V92 — historical replay.**
  - Reconcile dispatch after the main rebase, routing Actor bootstrap module/binding phases and module initialization through shared call operations.
  - Intended semantic baseline: message syntax and selection/evaluation rules, signed-64 `long`/`Long` Integer carriers, live lexical capture and ordinary callable behavior. Internal specialized operations do not authorize new guest semantics.
  - **Historical semantic limitations and divergences:** Bootstrap suspension and module READY publication remain historical behavior requiring later functional validation. D013, D179 C0, D197-C, dynamic selection, lexical captures, ReturnHome, suspension, cancellation, Futures, Task/C-prime and Actor transfer are not independently certified or declared closed by this replay. Historical code is preserved except for the explicitly authorized whitespace correction described below.
  - No test files are added or modified by this historical commit. No Protos tests, builds, Maven commands or benchmarks were run during this replay.
  - Historical file transitions: 0 added, 5 modified, 0 deleted (rename detection disabled for exact path accounting). All 5 historical path transitions preserve original modes and deletion state. Blob and parent identity checks allow only the explicitly authorized one-byte whitespace correction introduced in I115-V91: `tools/i115_gate_cases/primitive-if-true.protos:20`, original blob `b29428dbbee0f06243f105627c0a7ba472abea5a`, corrected blob `06420027434dabfae0edb537abfca37225e710aa`. All other payload blobs match exactly. Root `pom.xml` and `CHANGELOG.md` receive version/record metadata edits.
  - Original implementation: `a386898f52a19828fd06e6960092198ece440277`.

## 0.4.72-SNAPSHOT

- **I115-V91 — historical replay.**
  - Add a self-contained semantic/performance gate, case sources and a 41-root structural baseline using explicit i115 case URIs.
  - Intended semantic baseline: message syntax and selection/evaluation rules, signed-64 `long`/`Long` Integer carriers, live lexical capture and ordinary callable behavior. Internal specialized operations do not authorize new guest semantics.
  - **Historical semantic limitations and divergences:** The gate and stored baseline are historical artifacts; this replay does not execute them or establish performance acceptance. D013, D179 C0, D197-C, dynamic selection, lexical captures, ReturnHome, suspension, cancellation, Futures, Task/C-prime and Actor transfer are not independently certified or declared closed by this replay. Historical code is preserved except for the explicitly authorized whitespace correction described below.
  - Tests added or modified (not executed): `test_i115_baseline_mapping_gate.py`, `test_i115_local_gate.py`, `test_i115_probe_build_gate.py`, `test_i115_runtime_plane_gate.py`, `test_i115_semantic_performance_gate.py`. No Protos tests, builds, Maven commands or benchmarks were run during this replay.
  - Historical file transitions: 28 added, 1 modified, 0 deleted (rename detection disabled for exact path accounting). All 29 historical path transitions preserve original modes and deletion state. Blob and parent identity checks allow only the explicitly authorized one-byte whitespace correction introduced in I115-V91: `tools/i115_gate_cases/primitive-if-true.protos:20`, original blob `b29428dbbee0f06243f105627c0a7ba472abea5a`, corrected blob `06420027434dabfae0edb537abfca37225e710aa`. All other payload blobs match exactly. Root `pom.xml` and `CHANGELOG.md` receive version/record metadata edits.
  - Original implementation: `2cfc0df89ef008c9f25c10a99df681e8548514b9`.

## 0.4.71-SNAPSHOT

- **I115-V90 — historical replay.**
  - Reconcile the rebased runtime interfaces and remove obsolete activation/frame machinery across 18 files. Adapt abrupt-control transport and affected runtime/test callers to the current bytecode activation representation.
  - Intended semantic baseline: message syntax and selection/evaluation rules, signed-64 `long`/`Long` Integer carriers, live lexical capture and ordinary callable behavior. Internal specialized operations do not authorize new guest semantics.
  - **Historical semantic limitations and divergences:** The original explicitly leaves lexical-name observation temporarily unavailable through the new activation representation. Its compilation-restoration intent is not a compilation result from this replay. D013, D179 C0, D197-C, dynamic selection, lexical captures, ReturnHome, suspension, cancellation, Futures, Task/C-prime and Actor transfer are not independently certified or declared closed by this replay. Historical code is preserved without repairs.
  - No test files are added or modified by this historical commit. No Protos tests, builds, Maven commands or benchmarks were run during this replay.
  - Historical file transitions: 0 added, 18 modified, 0 deleted (rename detection disabled for exact path accounting). All 18 original paths retain their exact Git blobs/modes or deletion state; reconstructed parent blobs/modes also match the original parent. Only root `pom.xml` and `CHANGELOG.md` receive replay metadata edits.
  - Original implementation: `b5c995bfb117b1c5c72ae82b3d69833c546e4819`.

## 0.4.70-SNAPSHOT

- **I115-V89 — historical replay.**
  - Set the uncached threshold to zero for semantic activation roots after lowering, entering the cached Bytecode DSL tier on first invocation.
  - Intended semantic baseline: message syntax and selection/evaluation rules, signed-64 `long`/`Long` Integer carriers, live lexical capture and ordinary callable behavior. Internal specialized operations do not authorize new guest semantics.
  - **Historical semantic limitations and divergences:** No benchmark or Graal graph was executed to establish a performance improvement; semantic limitations from earlier checkpoints remain. D013, D179 C0, D197-C, dynamic selection, lexical captures, ReturnHome, suspension, cancellation, Futures, Task/C-prime and Actor transfer are not independently certified or declared closed by this replay. Historical code is preserved without repairs.
  - No test files are added or modified by this historical commit. No Protos tests, builds, Maven commands or benchmarks were run during this replay.
  - Historical file transitions: 0 added, 1 modified, 0 deleted (rename detection disabled for exact path accounting). All 1 original paths retain their exact Git blobs/modes or deletion state; reconstructed parent blobs/modes also match the original parent. Only root `pom.xml` and `CHANGELOG.md` receive replay metadata edits.
  - Original implementation: `7d314edbfbd3d68a75283518e2c88fcddb4117c9`.

## 0.4.69-SNAPSHOT

- **I115-V88 — historical replay.**
  - Cache the frozen Prelude Array binding at Prelude construction and use the admitted Prelude for constant Array construction. Preserve the original cold missing-binding and wrong-binding checks.
  - Intended semantic baseline: message syntax and selection/evaluation rules, signed-64 `long`/`Long` Integer carriers, live lexical capture and ordinary callable behavior. Internal specialized operations do not authorize new guest semantics.
  - **Historical semantic limitations and divergences:** Caching the admitted Prelude is not independent acceptance of alternative-Core construction or indexed protocol behavior. D013, D179 C0, D197-C, dynamic selection, lexical captures, ReturnHome, suspension, cancellation, Futures, Task/C-prime and Actor transfer are not independently certified or declared closed by this replay. Historical code is preserved without repairs.
  - No test files are added or modified by this historical commit. No Protos tests, builds, Maven commands or benchmarks were run during this replay.
  - Historical file transitions: 0 added, 2 modified, 0 deleted (rename detection disabled for exact path accounting). All 2 original paths retain their exact Git blobs/modes or deletion state; reconstructed parent blobs/modes also match the original parent. Only root `pom.xml` and `CHANGELOG.md` receive replay metadata edits.
  - Original implementation: `3ddda1757dbb014084ad53be89f68cbbbee3961b`.

## 0.4.68-SNAPSHOT

- **I115-V87 — historical replay.**
  - Add BinarySubscript, SetItem, MakeConstantLongArray and GetLen operations with primitive long Array storage. Copy constant arrays per evaluation, streamline stack/result handling and resolve inline control block bindings in the surrounding function scope.
  - Intended semantic baseline: message syntax and selection/evaluation rules, signed-64 `long`/`Long` Integer carriers, live lexical capture and ordinary callable behavior. Internal specialized operations do not authorize new guest semantics.
  - **Historical semantic limitations and divergences:** The original explicitly omits ordinary at/atPut protocol dispatch for non-Array receivers; contextual callback scope and lexical observation remain divergent. D013, D179 C0, D197-C, dynamic selection, lexical captures, ReturnHome, suspension, cancellation, Futures, Task/C-prime and Actor transfer are not independently certified or declared closed by this replay. Historical code is preserved without repairs.
  - No test files are added or modified by this historical commit. No Protos tests, builds, Maven commands or benchmarks were run during this replay.
  - Historical file transitions: 0 added, 9 modified, 0 deleted (rename detection disabled for exact path accounting). All 9 original paths retain their exact Git blobs/modes or deletion state; reconstructed parent blobs/modes also match the original parent. Only root `pom.xml` and `CHANGELOG.md` receive replay metadata edits.
  - Original implementation: `e5bfd57bc2b789e2690e86aea306c6fbf2d74ea0`.

## 0.4.67-SNAPSHOT

- **I115-V86 — historical replay.**
  - Cache hasProperty(cachedShape, name) as propertyPresent in the SetAttribute direct specialization and use that cached Boolean as its guard.
  - Intended semantic baseline: message syntax and selection/evaluation rules, signed-64 `long`/`Long` Integer carriers, live lexical capture and ordinary callable behavior. Internal specialized operations do not authorize new guest semantics.
  - **Historical semantic limitations and divergences:** The inherited direct-write frozen-state divergence remains; caching property presence does not resolve it. D013, D179 C0, D197-C, dynamic selection, lexical captures, ReturnHome, suspension, cancellation, Futures, Task/C-prime and Actor transfer are not independently certified or declared closed by this replay. Historical code is preserved without repairs.
  - No test files are added or modified by this historical commit. No Protos tests, builds, Maven commands or benchmarks were run during this replay.
  - Historical file transitions: 0 added, 1 modified, 0 deleted (rename detection disabled for exact path accounting). All 1 original paths retain their exact Git blobs/modes or deletion state; reconstructed parent blobs/modes also match the original parent. Only root `pom.xml` and `CHANGELOG.md` receive replay metadata edits.
  - Original implementation: `5cbb8395ff056ecb14ab7a1a5be2049821a4d9c6`.

## 0.4.66-SNAPSHOT

- **I115-V85 — historical replay.**
  - Move ordinary object properties onto the receiver DynamicObject shape. Use shape-cached PropertyGetter reads and PutNode writes for own properties, retain generic inherited-member fallback and extract Closure bindings freshly.
  - Intended semantic baseline: message syntax and selection/evaluation rules, signed-64 `long`/`Long` Integer carriers, live lexical capture and ordinary callable behavior. Internal specialized operations do not authorize new guest semantics.
  - **Historical semantic limitations and divergences:** The direct write path explicitly bypasses frozen-state admission until reconciliation; shape migration is not proof of object-model compliance. D013, D179 C0, D197-C, dynamic selection, lexical captures, ReturnHome, suspension, cancellation, Futures, Task/C-prime and Actor transfer are not independently certified or declared closed by this replay. Historical code is preserved without repairs.
  - No test files are added or modified by this historical commit. No Protos tests, builds, Maven commands or benchmarks were run during this replay.
  - Historical file transitions: 0 added, 10 modified, 0 deleted (rename detection disabled for exact path accounting). All 10 original paths retain their exact Git blobs/modes or deletion state; reconstructed parent blobs/modes also match the original parent. Only root `pom.xml` and `CHANGELOG.md` receive replay metadata edits.
  - Original implementation: `1b8e735878cea95f2106e4490803d9a47476b69d`.

## 0.4.65-SNAPSHOT

- **I115-V84 — historical replay.**
  - Return the exact outcome from experimental zero-argument host entry instead of routing it through general host Integer normalization, preserving the raw Long carrier.
  - Intended semantic baseline: message syntax and selection/evaluation rules, signed-64 `long`/`Long` Integer carriers, live lexical capture and ordinary callable behavior. Internal specialized operations do not authorize new guest semantics.
  - **Historical semantic limitations and divergences:** This changes the historical host return boundary; host interoperability and numeric normalization are not independently validated. D013, D179 C0, D197-C, dynamic selection, lexical captures, ReturnHome, suspension, cancellation, Futures, Task/C-prime and Actor transfer are not independently certified or declared closed by this replay. Historical code is preserved without repairs.
  - No test files are added or modified by this historical commit. No Protos tests, builds, Maven commands or benchmarks were run during this replay.
  - Historical file transitions: 0 added, 1 modified, 0 deleted (rename detection disabled for exact path accounting). All 1 original paths retain their exact Git blobs/modes or deletion state; reconstructed parent blobs/modes also match the original parent. Only root `pom.xml` and `CHANGELOG.md` receive replay metadata edits.
  - Original implementation: `c9a792fdab360baf3df400f9840ea488f1ba48f9`.

## 0.4.64-SNAPSHOT

- **I115-V83 — historical replay.**
  - Allow boolean/long/double boxing elimination and carry specialized relational results as primitive booleans into control decisions; generic comparisons retain their guest Boolean results.
  - Intended semantic baseline: message syntax and selection/evaluation rules, signed-64 `long`/`Long` Integer carriers, live lexical capture and ordinary callable behavior. Internal specialized operations do not authorize new guest semantics.
  - **Historical semantic limitations and divergences:** The original identifies the primitive Boolean as an experimental internal carrier; guest identity, projection and numeric dispatch remain separate acceptance concerns. D013, D179 C0, D197-C, dynamic selection, lexical captures, ReturnHome, suspension, cancellation, Futures, Task/C-prime and Actor transfer are not independently certified or declared closed by this replay. Historical code is preserved without repairs.
  - No test files are added or modified by this historical commit. No Protos tests, builds, Maven commands or benchmarks were run during this replay.
  - Historical file transitions: 0 added, 6 modified, 0 deleted (rename detection disabled for exact path accounting). All 6 original paths retain their exact Git blobs/modes or deletion state; reconstructed parent blobs/modes also match the original parent. Only root `pom.xml` and `CHANGELOG.md` receive replay metadata edits.
  - Original implementation: `88cc861113a85f5ee760f96341bad831cc011899`.

## 0.4.63-SNAPSHOT

- **I115-V82 — historical replay.**
  - Add cold caller recovery through a Truffle stack walk, register the effective callee caller need and reuse demand-driven B5 transport on later calls. Reduce eager prologue state and simplify host dispatch.
  - Intended semantic baseline: message syntax and selection/evaluation rules, signed-64 `long`/`Long` Integer carriers, live lexical capture and ordinary callable behavior. Internal specialized operations do not authorize new guest semantics.
  - **Historical semantic limitations and divergences:** Caller recovery and lean host entry do not prove equivalence across suspension, cancellation, Futures or Actor transfer. D013, D179 C0, D197-C, dynamic selection, lexical captures, ReturnHome, suspension, cancellation, Futures, Task/C-prime and Actor transfer are not independently certified or declared closed by this replay. Historical code is preserved without repairs.
  - No test files are added or modified by this historical commit. No Protos tests, builds, Maven commands or benchmarks were run during this replay.
  - Historical file transitions: 1 added, 5 modified, 0 deleted (rename detection disabled for exact path accounting). All 6 original paths retain their exact Git blobs/modes or deletion state; reconstructed parent blobs/modes also match the original parent. Only root `pom.xml` and `CHANGELOG.md` receive replay metadata edits.
  - Original implementation: `c7ea10ee1d838b9c58f401e60480bbebf6d7f552`.

## 0.4.62-SNAPSHOT

- **I115-V81 — historical replay.**
  - Streamline primitive and structured control lowering: eliminate statically empty controls, inline selected literal callbacks, emit effects-only statements and allocate discard sinks lazily. Keep CallTag on dynamic dispatch rather than synthetic eliminated calls.
  - Intended semantic baseline: message syntax and selection/evaluation rules, signed-64 `long`/`Long` Integer carriers, live lexical capture and ordinary callable behavior. Internal specialized operations do not authorize new guest semantics.
  - **Historical semantic limitations and divergences:** Static control admission still needs semantic acceptance for dynamic selection, callback activation identity and instrumentation equivalence. D013, D179 C0, D197-C, dynamic selection, lexical captures, ReturnHome, suspension, cancellation, Futures, Task/C-prime and Actor transfer are not independently certified or declared closed by this replay. Historical code is preserved without repairs.
  - No test files are added or modified by this historical commit. No Protos tests, builds, Maven commands or benchmarks were run during this replay.
  - Historical file transitions: 0 added, 1 modified, 0 deleted (rename detection disabled for exact path accounting). All 1 original paths retain their exact Git blobs/modes or deletion state; reconstructed parent blobs/modes also match the original parent. Only root `pom.xml` and `CHANGELOG.md` receive replay metadata edits.
  - Original implementation: `1776846b99289cda0b5fac6f2d6a1bc49ca0c122`.

## 0.4.61-SNAPSHOT

- **I115-V80 — historical replay.**
  - Replace root-wide cached-entry markers with an auxiliary per-invocation frame token and volatile readiness publication. Add a bounded elapsed-time escape during compiler preparation for non-returning or continually throwing roots.
  - Intended semantic baseline: message syntax and selection/evaluation rules, signed-64 `long`/`Long` Integer carriers, live lexical capture and ordinary callable behavior. Internal specialized operations do not authorize new guest semantics.
  - **Historical semantic limitations and divergences:** The compilation-deferral policy remains a historical optimization; no latency or compilation improvement is established by this replay. D013, D179 C0, D197-C, dynamic selection, lexical captures, ReturnHome, suspension, cancellation, Futures, Task/C-prime and Actor transfer are not independently certified or declared closed by this replay. Historical code is preserved without repairs.
  - No test files are added or modified by this historical commit. No Protos tests, builds, Maven commands or benchmarks were run during this replay.
  - Historical file transitions: 0 added, 1 modified, 0 deleted (rename detection disabled for exact path accounting). All 1 original paths retain their exact Git blobs/modes or deletion state; reconstructed parent blobs/modes also match the original parent. Only root `pom.xml` and `CHANGELOG.md` receive replay metadata edits.
  - Original implementation: `775f55ec2ba16f0ee0245c43108a5ed31f8592fa`.

## 0.4.60-SNAPSHOT

- **I115-V79 — historical replay.**
  - Refine the compilation-readiness diagnostic to require an interpreted cached entry followed by a return, rather than accepting a return after an UNCACHED-to-CACHED transition.
  - Intended semantic baseline: message syntax and selection/evaluation rules, signed-64 `long`/`Long` Integer carriers, live lexical capture and ordinary callable behavior. Internal specialized operations do not authorize new guest semantics.
  - **Historical semantic limitations and divergences:** The original root-wide readiness marker is explicitly not per-invocation or safe as a concurrent production protocol. D013, D179 C0, D197-C, dynamic selection, lexical captures, ReturnHome, suspension, cancellation, Futures, Task/C-prime and Actor transfer are not independently certified or declared closed by this replay. Historical code is preserved without repairs.
  - No test files are added or modified by this historical commit. No Protos tests, builds, Maven commands or benchmarks were run during this replay.
  - Historical file transitions: 0 added, 1 modified, 0 deleted (rename detection disabled for exact path accounting). All 1 original paths retain their exact Git blobs/modes or deletion state; reconstructed parent blobs/modes also match the original parent. Only root `pom.xml` and `CHANGELOG.md` receive replay metadata edits.
  - Original implementation: `759fae7d8b9984d020f245826aa88822d3ca794b`.

## 0.4.59-SNAPSHOT

- **I115-V78 — historical replay.**
  - Add a reversible cold Tier-1 diagnostic that delays semantic-root compilation until the first interpreted normal return, without changing JIT thresholds or generated interpreters.
  - Intended semantic baseline: message syntax and selection/evaluation rules, signed-64 `long`/`Long` Integer carriers, live lexical capture and ordinary callable behavior. Internal specialized operations do not authorize new guest semantics.
  - **Historical semantic limitations and divergences:** The original marks this as a diagnostic, not a production fix: a root that never returns cannot become compilable under this probe. D013, D179 C0, D197-C, dynamic selection, lexical captures, ReturnHome, suspension, cancellation, Futures, Task/C-prime and Actor transfer are not independently certified or declared closed by this replay. Historical code is preserved without repairs.
  - No test files are added or modified by this historical commit. No Protos tests, builds, Maven commands or benchmarks were run during this replay.
  - Historical file transitions: 0 added, 1 modified, 0 deleted (rename detection disabled for exact path accounting). All 1 original paths retain their exact Git blobs/modes or deletion state; reconstructed parent blobs/modes also match the original parent. Only root `pom.xml` and `CHANGELOG.md` receive replay metadata edits.
  - Original implementation: `2cf2e603bc6512bab4fb22b4bf5b6bab3dc72110`.

## 0.4.58-SNAPSHOT

- **I115-V77 — historical replay.**
  - Replace recursive retention of caller B5 arrays with demand-driven null/Prelude/Activation caller transport. Invalidate incoming PICs when callee caller needs grow; publish only the current invocation header.
  - Intended semantic baseline: message syntax and selection/evaluation rules, signed-64 `long`/`Long` Integer carriers, live lexical capture and ordinary callable behavior. Internal specialized operations do not authorize new guest semantics.
  - **Historical semantic limitations and divergences:** Demand-driven caller state does not independently establish context identity, ReturnHome, Task ownership or suspension equivalence. D013, D179 C0, D197-C, dynamic selection, lexical captures, ReturnHome, suspension, cancellation, Futures, Task/C-prime and Actor transfer are not independently certified or declared closed by this replay. Historical code is preserved without repairs.
  - No test files are added or modified by this historical commit. No Protos tests, builds, Maven commands or benchmarks were run during this replay.
  - Historical file transitions: 0 added, 11 modified, 1 deleted (rename detection disabled for exact path accounting). All 12 original paths retain their exact Git blobs/modes or deletion state; reconstructed parent blobs/modes also match the original parent. Only root `pom.xml` and `CHANGELOG.md` receive replay metadata edits.
  - Original implementation: `2362d6889261a39b34d140bb8fb4f2be91499a0a`.

## 0.4.57-SNAPSHOT

- **I115-V76 — historical replay.**
  - Converge CALL1/SEND1 dispatch on Closure-identity PICs guarded by code-stability assumptions. Transport receiver separately, share fixed-arity dispatch and remove legacy invocation preparation overhead.
  - Intended semantic baseline: message syntax and selection/evaluation rules, signed-64 `long`/`Long` Integer carriers, live lexical capture and ordinary callable behavior. Internal specialized operations do not authorize new guest semantics.
  - **Historical semantic limitations and divergences:** The original explicitly loses exact inherited methodHome on the direct selection path and omits historical ReturnHome preparation, terminal-suspension translation and early numeric projection. D013, D179 C0, D197-C, dynamic selection, lexical captures, ReturnHome, suspension, cancellation, Futures, Task/C-prime and Actor transfer are not independently certified or declared closed by this replay. Historical code is preserved without repairs.
  - No test files are added or modified by this historical commit. No Protos tests, builds, Maven commands or benchmarks were run during this replay.
  - Historical file transitions: 0 added, 5 modified, 0 deleted (rename detection disabled for exact path accounting). All 5 original paths retain their exact Git blobs/modes or deletion state; reconstructed parent blobs/modes also match the original parent. Only root `pom.xml` and `CHANGELOG.md` receive replay metadata edits.
  - Original implementation: `65ac24a85e031289babd083eb6e44b2ae1b9a45b`.

## 0.4.56-SNAPSHOT

- **I115-V75 — historical replay.**
  - Add CopySuppliedArguments for an entire contiguous range of LOCAL positional parameters without defaults/rest bindings. Add experimental direct unary operand and call emission using the existing invocation transport.
  - Intended semantic baseline: message syntax and selection/evaluation rules, signed-64 `long`/`Long` Integer carriers, live lexical capture and ordinary callable behavior. Internal specialized operations do not authorize new guest semantics.
  - **Historical semantic limitations and divergences:** The direct unary path deliberately does not compose a continuation returned by the callee; the original calls this a temporary invocation-semantic divergence. D013, D179 C0, D197-C, dynamic selection, lexical captures, ReturnHome, suspension, cancellation, Futures, Task/C-prime and Actor transfer are not independently certified or declared closed by this replay. Historical code is preserved without repairs.
  - No test files are added or modified by this historical commit. No Protos tests, builds, Maven commands or benchmarks were run during this replay.
  - Historical file transitions: 0 added, 2 modified, 0 deleted (rename detection disabled for exact path accounting). All 2 original paths retain their exact Git blobs/modes or deletion state; reconstructed parent blobs/modes also match the original parent. Only root `pom.xml` and `CHANGELOG.md` receive replay metadata edits.
  - Original implementation: `48d03199e749e4477c9827fec15538464e3be37e`.

## 0.4.55-SNAPSHOT

- **I115-V74 — historical replay.**
  - Introduce direct GetAttribute, SetAttribute and CreateAttribute Bytecode DSL operations. Keep physical attribute writes void and preserve consumed expression results in the lowerer; separate explicit attributes from lexical write targets.
  - Intended semantic baseline: message syntax and selection/evaluation rules, signed-64 `long`/`Long` Integer carriers, live lexical capture and ordinary callable behavior. Internal specialized operations do not authorize new guest semantics.
  - **Historical semantic limitations and divergences:** The original SetAttribute fast path explicitly bypasses frozen-state admission and retains internal numeric carriers pending semantic reconciliation. D013, D179 C0, D197-C, dynamic selection, lexical captures, ReturnHome, suspension, cancellation, Futures, Task/C-prime and Actor transfer are not independently certified or declared closed by this replay. Historical code is preserved without repairs.
  - No test files are added or modified by this historical commit. No Protos tests, builds, Maven commands or benchmarks were run during this replay.
  - Historical file transitions: 0 added, 5 modified, 0 deleted (rename detection disabled for exact path accounting). All 5 original paths retain their exact Git blobs/modes or deletion state; reconstructed parent blobs/modes also match the original parent. Only root `pom.xml` and `CHANGELOG.md` receive replay metadata edits.
  - Original implementation: `29d9802de950f9e58831f9db6e155259998c0503`.

## 0.4.54-SNAPSHOT

- **I115-V73 — historical replay.**
  - Unify lexical binding creation/write emission by storage class and avoid preserving unused results. Keep NAME/MEMBER creation on the named-slot path; write LOCAL/CELL bindings directly. Compute continuation-scratch needs from the expression subtree.
  - Intended semantic baseline: message syntax and selection/evaluation rules, signed-64 `long`/`Long` Integer carriers, live lexical capture and ordinary callable behavior. Internal specialized operations do not authorize new guest semantics.
  - **Historical semantic limitations and divergences:** Synchronous classification and omitted continuation scratch are historical implementation choices, not proof of suspension or cancellation correctness. D013, D179 C0, D197-C, dynamic selection, lexical captures, ReturnHome, suspension, cancellation, Futures, Task/C-prime and Actor transfer are not independently certified or declared closed by this replay. Historical code is preserved without repairs.
  - No test files are added or modified by this historical commit. No Protos tests, builds, Maven commands or benchmarks were run during this replay.
  - Historical file transitions: 0 added, 1 modified, 0 deleted (rename detection disabled for exact path accounting). All 1 original paths retain their exact Git blobs/modes or deletion state; reconstructed parent blobs/modes also match the original parent. Only root `pom.xml` and `CHANGELOG.md` receive replay metadata edits.
  - Original implementation: `56d823d13891919f0d961adc94e9ceaf664a8d5a`.

## 0.4.53-SNAPSHOT

- **I115-V72 — historical replay.**
  - Lower numeric operands directly and plan physical inline storage before the prologue. Promote captures owned only by inline regions from CELL storage to root LOCAL storage; avoid result stores for discarded expressions and synchronous numeric operations.
  - Intended semantic baseline: message syntax and selection/evaluation rules, signed-64 `long`/`Long` Integer carriers, live lexical capture and ordinary callable behavior. Internal specialized operations do not authorize new guest semantics.
  - **Historical semantic limitations and divergences:** The original explicitly records that promoted inline captures appear as plain locals and do not provide the old shared activation-cell introspection. D013, D179 C0, D197-C, dynamic selection, lexical captures, ReturnHome, suspension, cancellation, Futures, Task/C-prime and Actor transfer are not independently certified or declared closed by this replay. Historical code is preserved without repairs.
  - No test files are added or modified by this historical commit. No Protos tests, builds, Maven commands or benchmarks were run during this replay.
  - Historical file transitions: 2 added, 10 modified, 0 deleted (rename detection disabled for exact path accounting). All 12 original paths retain their exact Git blobs/modes or deletion state; reconstructed parent blobs/modes also match the original parent. Only root `pom.xml` and `CHANGELOG.md` receive replay metadata edits.
  - Original implementation: `39da87599a72cad1d75a45e09f833a3ddd820088`.

## 0.4.52-SNAPSHOT

- **I115-V71 — historical replay.**
  - Unify conditional and loop lowering through structured Bytecode DSL operations and CanonicalControlCondition. Encode selector arity, polarity and operand roles in compile-time rows; validate canonical Boolean conditions without truthiness or automatic Future adoption.
  - Intended semantic baseline: message syntax and selection/evaluation rules, signed-64 `long`/`Long` Integer carriers, live lexical capture and ordinary callable behavior. Internal specialized operations do not authorize new guest semantics.
  - **Historical semantic limitations and divergences:** These internal control paths bypass ordinary selector sends; custom selection and suspendible callback equivalence remain unaccepted. D013, D179 C0, D197-C, dynamic selection, lexical captures, ReturnHome, suspension, cancellation, Futures, Task/C-prime and Actor transfer are not independently certified or declared closed by this replay. Historical code is preserved without repairs.
  - Tests added or modified (not executed): `ProtosI115StructuredControlSmokeTest.java`. No Protos tests, builds, Maven commands or benchmarks were run during this replay.
  - Historical file transitions: 1 added, 8 modified, 0 deleted (rename detection disabled for exact path accounting). All 9 original paths retain their exact Git blobs/modes or deletion state; reconstructed parent blobs/modes also match the original parent. Only root `pom.xml` and `CHANGELOG.md` receive replay metadata edits.
  - Original implementation: `2e6ab63f097caa479e679842e670282ff869da79`.

## 0.4.51-SNAPSHOT

- **I115-V70 — historical replay.**
  - Remove superseded Boolean/while execution infrastructure and narrow the remaining Boolean control table to native not/and/or behaviors. Consolidate root-analysis facts and the unified control emitter.
  - Intended semantic baseline: message syntax and selection/evaluation rules, signed-64 `long`/`Long` Integer carriers, live lexical capture and ordinary callable behavior. Internal specialized operations do not authorize new guest semantics.
  - **Historical semantic limitations and divergences:** Infrastructure removal does not restore dynamically overridden conditional or loop methods. D013, D179 C0, D197-C, dynamic selection, lexical captures, ReturnHome, suspension, cancellation, Futures, Task/C-prime and Actor transfer are not independently certified or declared closed by this replay. Historical code is preserved without repairs.
  - No test files are added or modified by this historical commit. No Protos tests, builds, Maven commands or benchmarks were run during this replay.
  - Historical file transitions: 0 added, 7 modified, 0 deleted (rename detection disabled for exact path accounting). All 7 original paths retain their exact Git blobs/modes or deletion state; reconstructed parent blobs/modes also match the original parent. Only root `pom.xml` and `CHANGELOG.md` receive replay metadata edits.
  - Original implementation: `530c73a8cb24cd8cd14fce88b9bf78b8aa01ac4d`.

## 0.4.50-SNAPSHOT

- **I115-V69 — historical replay.**
  - Migrate 26 test/helper files to the unified Bytecode DSL lowering: native parameter locals/cells, shared return epilogue, module binding reads and current instruction topology. Retain guest semantic assertions separately from retired implementation-layout assertions.
  - Intended semantic baseline: message syntax and selection/evaluation rules, signed-64 `long`/`Long` Integer carriers, live lexical capture and ordinary callable behavior. Internal specialized operations do not authorize new guest semantics.
  - **Historical semantic limitations and divergences:** The migrated parameter tests explicitly retain the known D179 divergence: reflected Closure contexts expose NAME bindings while LOCAL/CELL removal and recreation remain incomplete. D013, D179 C0, D197-C, dynamic selection, lexical captures, ReturnHome, suspension, cancellation, Futures, Task/C-prime and Actor transfer are not independently certified or declared closed by this replay. Historical code is preserved without repairs.
  - Tests added or modified (not executed): `ProtosInstructionNames.java`, `ProtosPerf025H1IndexedParameterEstablishmentTest.java`, `ProtosPerf025CallbackConsumerSpecializationTest.java`, `ProtosBug013FrameLexicalAuthorityMaterializationTest.java`, `ProtosI068Slice5CapturedMaterializedLexicalLoweringTest.java`, `ProtosPerf025FrameMaterializationSliceTest.java`, `ProtosPerf037CCapturedLexicalReadTest.java`, `ProtosPerf025CompactCalleeExecutionTest.java`, `ProtosBug018FrameLexicalAuthorityReadAfterOwnerTierTransitionTest.java`, `ProtosI075DLexicalAuthorityCurrentBytecodeNodeTest.java`, `ProtosPerf012FrameLexicalLayoutTest.java`, `ProtosPerf025D179LexicalMembershipStabilityReadTest.java`, `ProtosPerf028AResolvedCurrentLexicalWriteTest.java`, `ProtosPrimitiveSlotWriteRegressionTest.java`, `ProtosI101BLocalBindingBuilderOracleTest.java`, `ProtosI101CIndependentBuilderReferenceTest.java`, `ProtosI101ProfileSpecializedLoweringTest.java`, `ProtosI112TailContinuationTest.java`, `ProtosI115H7MethodCallConvergenceTest.java`, `ProtosI115HStaticClosureCallTest.java`, `ProtosI115PCompactBooleanWhileTest.java`, `ProtosI115RFactorialCaptureTest.java`, `ProtosPerf040E1NumericOperationReconciliationTest.java`, `ProtosI115BDeferredCallerTest.java`, `ProtosI115UnaryPICConvergenceTest.java`, `ProtosI068Slice6DebuggerReflectionProjectionTest.java`. No Protos tests, builds, Maven commands or benchmarks were run during this replay.
  - Historical file transitions: 0 added, 26 modified, 0 deleted (rename detection disabled for exact path accounting). All 26 original paths retain their exact Git blobs/modes or deletion state; reconstructed parent blobs/modes also match the original parent. Only root `pom.xml` and `CHANGELOG.md` receive replay metadata edits.
  - Original implementation: `47b78f3c6be248c68c42a7661cd52302944c3c86`.

## 0.4.49-SNAPSHOT

- **I115 — retire compact-root lowering; use a single GraalPy-shaped Bytecode DSL path (historical).**
  - Remove the separate experimental compact-root emitter and its repeated
    control and numeric lowering. `CanonicalToBytecodeLowerer` now routes
    ordinary and previously compact source roots through one Bytecode DSL
    lowering strategy, using the existing internal structured `ifTrue`,
    `ifFalse`, `ifTrueIfFalse`, `whileTrue` and unified Number/RichCompare
    operations. The historical source changes are replayed unchanged; this
    version does not independently prove the correct guest semantics.
  - Retire the experimental static/minimal unary frame argument layouts in
    `ProtosFrameArguments` and compact-only root/result classifications.
    Consolidate closure parameter access, source-root execution and host
    result transport around the remaining B5 invocation contract; retain
    signed-64 `long`/`Long` Integer and direct `Double` Float from V66.
  - Rework `ReadGlobal` for own module roots and captured-module reads with
    cached module-context/Closure identities and physical lexical depth.
    Add `ProtosExperimentalWriteGlobalNode` (`SelectWriteGlobal`): select the
    module NAME destination before evaluating the RHS, hold its fixed
    `ProtosModuleBindingLocation`, check runtime presence and signal
    `SlotNotFound` if absent. The write then uses the already-selected
    location rather than resolving a different name after RHS side effects.
  - Delete twelve obsolete numeric/compact-only classes, including
    `ProtosNumericCoreAdmission`, `ProtosPrimitiveNumericSendFastPathsBase`,
    `ProtosNumericAddNode`, `ProtosNumericDivideNode`,
    `ProtosNumericLessNode`, `ProtosNumericGreaterNode`,
    `ProtosExperimentalLessNode` and
    `ProtosExperimentalNumberInPlaceAddNode`. Retain the shared
    `NumberDivide` and `RichCompare` nodes from V65. One new source file is
    introduced, thirteen others are modified and twelve are deleted.
  - **Historical semantic limitations and divergences:** V67's D179 C0
    lexical classification is not fully correct for dynamic `Candidate`
    presence, `LOCAL`/`CELL` removeSlot and recreation, or nearer-scope
    shadowing; this refactor does not restore those behaviors. `ReadGlobal`
    and `SelectWriteGlobal` use static module ownership and do not recover
    arbitrary dynamic lookup retargeting. Closure captures, parallel Actor
    projection, context identity and non-local ReturnHome transfers still
    require separate semantic acceptance. Internal Boolean controls can
    bypass overridden `ifTrue`/`whileTrue` methods, while D013 `Object.call`
    override behavior remains independently unresolved. Suspension,
    cancellation, terminal Futures, Task/C-prime ownership, D197-C
    BigInteger prototype delegation, dynamic numeric dispatch, and the
    `Double` migration remain separately unverified. The removal of legacy
    operations does not itself prove runtime correctness or a performance
    improvement. No tests, Maven build, Graal compiler graphs or benchmarks
    were run after this version bump in the historical replay.
  - Original implementation: `100e03e479ddf33d14b5d0d02dee492a0c4419ea`.
    All 26 original source blobs and Git modes are preserved byte-for-byte;
    only root `pom.xml` and `CHANGELOG.md` receive versioned metadata edits.

## 0.4.48-SNAPSHOT

- **I115 — GraalPy-style NAME/LOCAL/CELL lexical storage convergence (historical).**
  - Replace the earlier frame-authority/storage-selection infrastructure
    with one compile-time storage classification in `CanonicalLexicalScope`:
    `NAME` for module ROOT declarations in the module context, `MEMBER` for
    object-body members, `LOCAL` for uncaptured Closure bindings held directly
    by Bytecode DSL locals, and `CELL` for Closure bindings captured through
    nested Closure boundaries. `CanonicalBindingAnalyzer` records free cells
    in deterministic order and distinguishes `Resolved`, `CapturedResolved`,
    `Candidate` and `Dynamic` identities without selecting storage at runtime.
  - Add `ProtosLexicalCell`, a shared-by-reference guest-value cell with Java
    `null` exclusively marking an uninitialized binding; a guest null value
    remains distinct. `ProtosClosureValue` and `ProtosActivation` carry arrays
    of captured cells, and B5 invocation arguments expose those cells to the
    callee. Repeated captures share the same actual cell rather than copying
    a frame slot, context or a captured value. Module `NAME` reads use
    `ProtosModuleBindingLocation` with a specialized `DynamicObject` getter;
    removal/recreation of the module slot can be observed by its NAME path.
  - Retire `ProtosCapturedLexicalAccess`,
    `ProtosFrameLexicalBindingAuthority` and `ProtosFrameLexicalLayout`.
    Rework root operations, name lookup, lexical capture and assignments,
    parameter binding and source lowering around their single preselected
    storage. Preserve the explicit `MEMBER` path and dynamic NAME lookup.
    Add `ProtosI115LexicalStorageConvergenceTest` and update the canonical
    binding analyzer tests; these historical tests are not executed here.
  - **Historical semantic limitations and recorded divergences:** D179 C0
    presence and shadowing are NOT fully preserved by this change. A
    statically classified `LOCAL` or `CELL` does not track `removeSlot` and
    recreation through an observed Context, and a `Candidate` read before
    its binding point can raise an Error instead of finding an outer name.
    Classified captured cells do not dynamically retarget to late nearer
    bindings. Parallel Closure projection shares cells rather than providing
    isolated copies, leaving Actor confinement and cross-Actor transfer
    semantics independently unverified. The new bytecode storage model is
    not proof of full lexical semantics, stable context identity or all
    return/closure cases. D013 `Object.call` overrides, Boolean control
    selector overrides (`ifTrue`/`whileTrue`), `ReturnHome`, non-local
    control transfer, suspension, cancellation, terminal Futures,
    Task/C-prime ownership, D197-C BigInteger prototype delegation and
    numeric behavior remain separate acceptance concerns. Integer is a
    semantic `long`/`Long` value (not a guest `int` lane); Float now uses
    direct `Double`, as in V66. No tests, Maven build, compiler graphs or
    benchmarks have been run in this versioned replay after the version
    increment; no performance gain or full conformance is claimed.
  - Original implementation: `3b59add7397d75038922f6498c2028ddedae9ee6`.
    Sixteen modified files, three new files and three deletions are replayed
    as exact historical Git blobs and modes. Only root `pom.xml` and
    `CHANGELOG.md` receive versioned metadata edits.

## 0.4.47-SNAPSHOT

- **I115 — GraalPy-style direct `double` as the semantic Float representation (historical).**
  - Normalize ordinary Float values to a direct Java `Double`: `ProtosNumericValueSupport.floating`
    now returns `Double.valueOf`, `isCurrentFloat` recognizes both `Double`
    and the legacy `ProtosFloatValue`, and `guestValue` no longer boxes a
    `Double` into a representation object. A direct `Double` is an authentic
    Protos Float in every invocation lane, rather than a temporary carrier.
    Protos Integer remains signed-64 `long`/`Long`, not a guest `int` lane.
  - Make numeric family identity and hash agree across direct `Double` and
    legacy boxed Float; distinguish negative zero from positive zero by raw
    IEEE-754 bits and normalize NaN identity. Copying/transferring a direct
    immutable Float requires no representation allocation. `ProtosValueLookup`
    admits `Double` and delegates method lookup to the observing Prelude's
    Float prototype, preserving the normal method lookup home.
  - Update numeric comparison and equality, fast-path family checks, the
    standard Float/Integer/Fraction conversion protocols, mixed arithmetic,
    exact-to-binary64 rounding, Complex arithmetic and transfer descriptors,
    `ProtosSquareRoot`, and packed `ProtosMatrixValue` read/write/unpack to
    recognize the direct Float representation. Foreign binary64 admissions
    and actor value transfer also treat `Double` as a guest number, not an
    impermissible host carrier.
  - Add `ProtosI115DoubleSemanticTest` for direct Double family membership,
    legacy-object identity/hash equivalence, negative-zero/NaN bits and
    allocation-free copying. Reconcile existing numeric conversion, sqrt,
    Complex, matrix, foreign, value-lookup and actor-transfer test expectations.
    All original tests are preserved byte-for-byte and are not executed during
    this versioned replay.
  - **Historical semantic limitations:** this is a representation migration,
    not a complete semantic acceptance of Float arithmetic, equality,
    signed zero, NaN, infinities, overflow, precision, mixed Integer/Fraction
    and BigInteger operations, conversion, prototype delegation, foreign
    boundaries or actor transfer. The legacy `ProtosFloatValue` remains
    recognized; callers outside this commit may still rely on boxed forms.
    D197-C Context-owned BigInteger Prelude and dynamic numeric delegation,
    D013 `Object.call` overrides, D179 C0 lexical binding, unconditional
    `ifTrue` / `whileTrue` internal lowering, `ReturnHome`, non-local control
    transfers, suspension, cancellation, terminal Futures and Task/C-prime
    ownership remain independently unverified. No tests, Maven builds, Graal
    compiler graphs or benchmarks are run after this version increment. No performance improvement or full semantic conformance is claimed.
  - Original implementation: `b67281510e29c65ed9671167053f39ba2644bb46`.
    Nineteen original modified files and one new test are reproduced as
    exact Git blobs with their modes. Only root `pom.xml` and `CHANGELOG.md`
    receive versioned metadata edits.

## 0.4.46-SNAPSHOT

- **I115 — unified GraalPy-style numeric DSL operators and result projection (historical).**
  - Register five new Bytecode DSL operations: `NumberDivide` and
    `RichCompareLt`, `RichCompareLe`, `RichCompareGt`, `RichCompareGe`.
    The new nodes specialize `long`/`Long` and supported floating numeric
    operands, with a cold composable generic send for other guest values.
    Integer `/` returns a signed-64 quotient directly only for exact,
    nonzero divisions without `Long.MIN_VALUE / -1` overflow. Nonintegral
    results, zero and overflow defer to guest division semantics rather
    than silently truncating a result.
  - Route both ordinary and admitted compact numeric lowering through the
    same DSL `NumberAdd`, `NumberSubtract`, `NumberMultiply`, `NumberDivide`
    and `RichCompare` operations where selected. Evaluate receiver and
    argument once, in source order. Compose any suspended generic result
    through `emitDispatchedContinuationComposition` before exposing the
    completed value, including the canonical Boolean branch condition.
  - Add `ProtosFunctionCallDispatchers.sendOneComposable`: the cold
    generic operator selects a guest method through `GetMethod` and the
    shared call dispatcher without prematurely rejecting a suspended
    continuation. Move completed primitive carrier projection to the
    call-site contract with `projectForCaller`; direct callee epilogues
    return raw outcomes, while an ordinary observable host call boundary
    materializes guest Float and other necessary guest representations.
    `ProtosClosureInvoker` establishes an entered Truffle Context for
    synchronous Java-to-guest calls only when one is not already entered.
  - Reconcile historical PERF040 numeric-operation structure, I115-B
    deferred-caller carrier expectations, and complex-number semantics
    after adopting `Long` as the physical SmallInteger representation.
    Those original tests are preserved byte-for-byte, not executed here.
  - **Historical semantic limitations:** this commit does not eliminate
    all legacy compact numeric fallback operations. The compatibility
    MISS/dispatch route remains for some arithmetic selectors, pending
    further convergence. Generic division, comparison, custom operator
    selection, dynamic prototype overrides, mixed types and exact
    Fraction/BigInteger edge cases require independent conformance.
    Suspension, cancellation, terminal continuations, Task/C-prime,
    lexical captures, `ReturnHome`, non-local returns, D179 C0 and the
    D013 `Object.call` divergence remain separate open concerns. The
    Context-owned BigInteger Prelude / D197-C delegation and the earlier
    unconditional `ifTrue` / `whileTrue` internal lowering also remain
    unverified. Guest integers are signed-64 `long` / `Long` (no guest
    `int` lane). No tests, Maven builds, Graal BGV traces or benchmarks
    were executed after the version bump. No performance or full semantic
    conformance claim is made for this historical replay.
  - Original implementation: `937addb1f06d51a232b7e4270db7bf94cda2dd11`.
    Seven modified and five newly added source/test blobs and their Git
    file modes are preserved; only root pom.xml and CHANGELOG.md differ.

## 0.4.45-SNAPSHOT

- **I115 — experimental lazy promotion of live-frame lexical authority (historical).**
  - Introduce `RootFacts.admitsLazyFrameAuthority` alongside
    `requiresPersistentFrameAuthority` in `CanonicalCompilationFacts`. For
    qualifying ordinary Closure roots, start with frame-native locals even
    if persistent lexical authority may later be needed. The admission proof
    requires a matching lexical scope/body and fails closed for module roots,
    parameter defaults, `Candidate` or `Dynamic` own-name lookup, foreign
    ownership, Object construction, `compose` and other unproven demands.
  - Add the `EnsureLiveFrameLexicalAuthority` Bytecode DSL operation and the
    `ProtosFrameLocalBindings.ensureLiveFrameLexicalAuthority` transition.
    The lowerer inserts it **before** creating a creator-capturing Closure or
    observing the `context` intrinsic, rather than at root entry. Noncapturing
    Closures and ordinary frame-local reads and writes need no promotion.
    Roots that fail static admission retain the existing
    `InstallFrameLexicalAuthority` entry prologue.
  - Recognize an already-installed authority by its root and frame layout
    before calling `frame.materialize()`. Promotion preserves the executing
    activation and uses that root's live frame; a second capture/observation
    does not install another authority, and no `VirtualFrame` is retained.
    `ProtosFrameLexicalBindingAuthority.isDeclaredBy` supports this proof.
    The lowerer saves/restores its per-root lazy-state during nested lowering.
  - Add `ProtosI115LazyCapturedFrameTest` and extend the BUG013 and PERF025
    suites. The historical tests cover capture-before-creation ordering,
    escaped mutable captures, sibling state, context identity, dynamic
    presence and removal/recreation, assignment effects, non-local returns,
    completed ReturnHome errors and single-authority installation.
    These tests were **added but not executed** during this versioned replay.
  - **Historical semantic limitations:** this is an experimental admission
    and publication policy, not proof of all capture and frame lifetime paths.
    Dynamic `Candidate` presence, shadowing, D179 C0 lexical ownership,
    `ReturnHome` observability, non-local return, suspension and cancellation,
    Task/C-prime transfers and module/foreign contexts require independent
    conformance. The existing D013 / `Object.call` divergence and unconditional
    `ifTrue` / `whileTrue` internal lowering may still bypass user-overridden
    methods; the Context-owned BigInteger factory and D197-C prototype
    delegation remain separate concerns. Guest Integer is signed-64
    `long` / `Long`, not a guest `int` tier. No runtime tests, Maven build,
    BGV captures or benchmarks were executed after this historical version
    bump, and no performance or full-conformance claim is made.
  - Original implementation: `94d56ec200f1486d86c2d1f8f00b643eab12c4e3`.
    The seven modified source/test blobs and one new test blob retain the
    original content and Git modes; only Maven version and changelog differ.

## 0.4.44-SNAPSHOT

- **I115 — unified Closure inlining and recursive captured-control callbacks (historical).**
  - Use `i115AdmitsInlineClosure` as the common static admission policy for
    immediately invoked zero-argument Closure literals and structured Boolean
    callbacks, including `ifTrue` and `whileTrue`. Refuse syntactic inlining
    when lexical capture, `ReturnHome` or persistent frame authority cannot
    be proved; dynamic/nonliteral closures still use dispatched callable
    execution. Consolidate execution through `emitI115ClosureInvocation`
    and the renamed `emitI115InlineClosureBody`, keeping a fresh Closure
    for rejected callbacks instead of silently applying a second compact policy.
  - Extend `CanonicalBindingAnalysis` with owner-and-depth proofs for
    inlined module-owned `CapturedResolved` global reads and for
    `CanonicalBindingResolution.Candidate` reads. Candidate is **not**
    an assertion of runtime presence: preserve named, presence-aware lookup
    and shadowing at the physical parent. Reads and writes have intentionally
    different admission policies; captured assignments continue to require
    a separate direct-parent proof. Emit `ReadGlobal` with the corrected
    lexical depth after the callback activation is eliminated.
  - Add optional `protos.i115.traceInlineAdmission` diagnostics for lexical
    admission and capture proofs. Add
    `ProtosI115FactorialControlInliningTest`, a structural oracle requiring
    the exact `factorial(20)` value `2432902008176640000` and three physical
    Bytecode DSL roots (module, factorial and run) rather than a fourth
    physical root for the `ifTrue` callback. The historical test is present
    but **was not executed** during this versioned replay.
  - **Historical semantic limitations:** admitting Candidate reads requires
    independent acceptance of runtime presence, shadowing, D179 C0 lexical
    ownership and published binding behavior; a name/depth proof is not a
    general PRESENT proof. Inlined recursive captures, assignments, closure
    identity and non-local control transfer still require conformance.
    The earlier unconditional `ifTrue` / `ifFalse` / `ifTrueIfFalse` /
    `whileTrue` lowering may bypass user-defined selector overrides and
    ordinary dynamic message selection. D013 / `Object.call` divergence,
    `ReturnHome` observability, suspension, cancellation, Task/C-prime
    lifetime and Context-owned BigInteger / D197-C numeric delegation also
    remain unverified. Signed-64 guest Integer uses `long` / `Long` and no
    guest `int` tier. No runtime tests, Maven build, compiler graph captures
    or benchmarks were run after this version bump; this historical commit
    does not establish full semantic conformance or a performance gain.
  - Original implementation: `ab20a890f74a6d117f3b653055d107456fe880aa`.
    Both modified production blobs and the newly added test blob and Git modes
    are reproduced exactly, with only version metadata and changelog changed.

## 0.4.43-SNAPSHOT

- **I115 — unified Bytecode DSL Boolean branches and `whileTrue` lowering (historical).**
  - Replace separate compact and general Boolean-control emitters with the
    single `emitI115UnifiedControl` in `CanonicalToBytecodeLowerer`. Lower
    `ifTrue`, `ifFalse`, `ifTrueIfFalse` and `whileTrue` as internal
    `IfThen` / `IfThenElse` / `While` Bytecode DSL control rather than an
    ordinary selector send or Boolean method activation.
  - Consolidate ordinary and compact callback selection. Stage eligible
    non-inline operands left to right, then select the callback using
    `ExperimentalCanonicalBooleanCondition`; reuse the existing I095
    lexical/activation proof for normal-root inline literal callbacks.
    Retain the generic Closure call dispatcher with continuation transport
    for callbacks that cannot be inlined. Compact roots continue to use
    their admitted literal callback bodies without allocating a Closure.
  - Add `emitI115ProvenInlineCallback` to preserve isolated callback lexical
    bindings, source statement tags and nested invocation lowering; restore
    previous callback-local compiler state in `finally`. The same lowering
    owns both conditional branches and loop bodies; remove the older
    `emitExperimentalCompactWhile`, `emitExperimentalCompactBooleanControl`,
    `emitExperimentalCompactBooleanDispatch` and associated branch emitters.
  - **Historical semantic limitations:** the unified internal selection
    bypasses user-defined `ifTrue` / `ifFalse` / `ifTrueIfFalse` / `whileTrue`
    method overrides and normal selection for noncanonical receivers.
    `ExperimentalCanonicalBooleanCondition` may reject an ordinary guest
    object instead of dispatching its selector. Requiring fixed positional
    callbacks without spread is not a general send-equivalence proof.
    Eager/staged callback operand evaluation, the timing of captures, result
    semantics, lexical publication, non-local returns and loop iteration
    ordering require separate acceptance. The I095 admission proof does not
    certify all callback shapes or all `D179 C0` lexical cases.
  - Existing `D013` / `Object.call` dispatch divergence remains distinct.
    `ReturnHome` observability, suspension, cancellation, Task/C-prime
    ownership, `D197-C` Context-owned BigInteger numeric delegation and
    signed-64 `long`/`Long` carrier correctness remain unverified by this
    commit. There is no guest `int` numeric tier. No runtime tests, Maven
    build, Graal compiler graphs or benchmarks were executed after the
    version bump during this historical replay; no performance improvement
    or complete semantic conformance is claimed.
  - Original implementation: `75c3e289e083553ee0a9fd5e13d6443b49ce8738`.
    The sole original source blob and its Git mode are reproduced exactly.

## 0.4.42-SNAPSHOT

- **I115 — Bytecode DSL `ifTrue` control and primitive root-frame reads (historical).**
  - Lower a unary `ifTrue` send via `emitI115IfTrueScalar` into the
    Bytecode DSL `IfThenElse` control structure, rather than entering
    `GetMethod(ifTrue)` or a Boolean method activation. Stage the receiver
    and callback-producing expression once, in that order; use
    `ExperimentalCanonicalBooleanCondition` to select the true branch.
    The true branch invokes the callback through the shared dispatcher and
    continuation transport, while the false branch produces guest `null`
    after evaluating, but not invoking, the callback.
  - Change the experimental compact Boolean dispatch to use its structured
    control path without the previous `IsCanonicalBooleanValue` fallback
    and ordinary method-send MISS branch. Dynamic comparisons yielding
    canonical Booleans are covered by the added factorial and `ifTrue`
    source tests. Their historical addition does not mean they have run
    during the versioned replay.
  - Add a constant `localOffset` to `ReadRootFrameLocal`; use
    `BytecodeNode.getLocalValue(bytecodeIndex, frame, localOffset)` in
    compact and published-present specializations instead of
    `LocalAccessor.getObject`. Adjust the lowerer and the D179 / I101
    independent-builder test sites to supply their frame-layout offsets.
  - **Historical semantic limitations:** unconditional internal lowering
    of `ifTrue` can bypass a user-defined method override or a noncanonical
    receiver's ordinary message selection. It does not establish full
    Boolean-control behavior for all receiver shapes, inherited lookup or
    error signaling. This is separate from the existing D013 / `Object.call`
    divergence. Direct frame reads still require D179 C0 lexical binding
    acceptance and correct materialization after publication. `ReturnHome`
    proof, suspension, cancellation, Task/C-prime ownership, non-local
    control transfer, and Context-owned BigInteger / D197-C prototype
    delegation remain unverified. Guest Integer uses `long` / `Long`, with
    no guest `int` tier. No tests, Maven build, Graal compiler graphs or
    benchmarks were executed after the version bump in this historical
    replay, and no performance or full-conformance claim is made.
  - Original implementation: `64ebc59bb8b8a8e754dd144f4cf4231b09b8462d`.
    All six original source/test blobs and Git modes are reproduced intact.

## 0.4.41-SNAPSHOT

- **I115 — cached non-capturing Closure materialization (historical).**
  - Add the `nonCapturingCached` specialization to the Bytecode DSL
    `MaterializeCurrentClosure` operation. It is admitted only when a
    literal neither captures its creator Context nor may observe its
    invocation `ReturnHome`; the source execution-plan cell is the PIC key.
  - Cache the frozen `ProtosClosureExecutionPlan` from the identity of its
    `ProtosClosureExecutionPlanCell` with `@Cached`, limit `3`. Reuse that
    plan without reading the plan cell on every hit, but still construct a
    **fresh** `ProtosClosureValue.experimentalNonCapturing` per invocation.
    Neither the Closure nor lexical/caller state is retained by the cache.
  - Preserve `nonCapturing` as the general specialization using
    `replaces = "nonCapturingCached"` after saturation; capturing and
    ReturnHome-observing Closure paths are unchanged by this commit.
  - **Historical semantic limitations:** this PIC does not establish that
    the full guest semantics have recovered. D013 / `Object.call` still
    distinguishes implicit direct source-Closure calls from an explicit
    instance-local `call` override. Correct `ReturnHome` proof and
    publication, suspension and cancellation, Task/C-prime ownership,
    non-local control transfer and D179 C0 lexical binding remain subject
    to independent acceptance. Context-owned BigInteger promotion and
    D197-C numeric-prototype delegation likewise remain unresolved.
    Guest Integer stays `long` / `Long` (signed 64 bit), not a Java `int`
    guest lane. No runtime tests, build, compiler graphs, or benchmarks
    were executed during this historical replay; no performance claim.
  - Original implementation: `2354f61720c9a2bd8931cfb66c3b3539a99c6f15`.
    Its sole source blob and Git mode are preserved exactly.

## 0.4.40-SNAPSHOT

- **I115 — opt-in Tier 2 compiler graph witness for run() (historical).**
  - Add `ProtosI115Tier2RunGraphTest`, an opt-in compiler regression
    that compiles the concrete `run()` root for a unary Closure identity
    call and selects its own fresh BGV graph at the `After TruffleTier`
    phase using Graal's graphio parser. It checks the selected root's
    optimized CallTarget, cached Bytecode DSL tier, and natural Tier 2
    compilation after 2,001 (`2001`) invocations, rather than consulting a prebuilt
    graph summary.
  - The executable assertions require `LoadFieldNode=0` and
    `IsNullNode=0` on that selected compiled graph. The test also reports
    `I115_RUN_TIER2_TOTAL_NODES`, but does **not** assert total nodes < 50,
    notwithstanding the original commit's 'under 50 nodes' title. Do not
    misrepresent the title as a measured acceptance criterion.
  - Add `tools/i115_tier2_run_graph.sh` to run the witness with the GraalVM
    JDK compiler module, synchronous Truffle compilation, stable root
    identity and a fresh dump directory. The test is normally skipped
    unless `protos.i115.graphCapture=true` is provided. Invoking this
    historical script creates files under `target/truffle-compilation/`;
    this replay does not run it or generate such artifacts.
  - **Historical semantic limitations:** a Tier 2 graph node census does
    not establish guest correctness, dynamic `Object.call` / D013 dispatch,
    ReturnHome, suspension, cancellation, Task/C-prime ownership, non-local
    control transfers, D179 C0 lexical behavior, or D197-C numeric-prototype
    delegation of the Context-owned BigInteger factory. The signed-64
    Integer representation remains `long` / `Long`; no Java int guest
    integer lane is introduced here. No runtime tests, builds, compiler
    captures, graph measurements, or benchmarks were executed during this
    versioned replay; neither <50 total nodes nor the zero-node assertions
    are claimed as observed results here.
  - Original implementation: `cfb7ab8d266df7127965c9a3bb3aecea667099dc`.
    Both original additions are preserved byte-for-byte, including
    executable content and test assertions.

## 0.4.39-SNAPSHOT

- **I115 — proof-driven null ReturnHome for direct Closure invocation (historical).**
  - Introduce the compilation-final `returnHomeProvenUnobservable` fact on
    `ProtosBytecodeRootNode`. The lowerer records the result of
    `!compilationFacts.mayObserveReturnHome(activationDefinition)` for each
    source-Closure activation root at construction, rather than inspecting
    the Closure execution plan on each cached direct call.
  - `ProtosFunctionCallDispatchers` passes a `null` ReturnHome in the B5
    header only when it has a resolved callee root with the recorded
    unobservability proof. Other calls continue to obtain the invocation's
    normal semantic ReturnHome from the Closure. A fast no-home entry can
    therefore avoid allocating the unobservable marker on initial entry.
  - Teach `ProtosFrameArguments` to recognize a null ReturnHome in compact
    and deferred B5 headers, and to materialize `ProtosReturnHome.unobservable()`
    only if the slow semantic path actually requires a home object.
  - **Historical semantic limitations:** `null` is an internal B5 carrier,
    not a guest-facing value. The proof relies on the lowerer's analysis
    of the Closure's lexical subtree, including defaults and nested Closures;
    this historical replay does not demonstrate its completeness under
    dynamic mutation, non-local return, or unusual control transfers.
    D013 / `Object.call` still lets implicit source-function invocation
    bypass instance-local `call` overrides. suspension, cancellation,
    Task/C-prime ownership, D179 C0 lexical behavior, and ReturnHome
    propagation after resumption require independent acceptance. Context-
    owned BigInteger creation, D197-C numeric-prototype delegation, and
    the long/Long-only Integer lane are separate concerns; no correctness,
    performance or allocation benefit has been measured in this replay.
    No build, tests, compiler graphs or benchmarks were run after bumping
    the version.
  - Original implementation: `339fd0102d12b419e3d5e97ab4e5ece881d91751`.
    All four original production blobs are reproduced without modification.

## 0.4.38-SNAPSHOT

- **I115 — secondary RootCallTarget PIC after unary Closure-identity saturation (historical).**
  - Re-enable a secondary `callFunctionCachedCt` specialization in
    `ProtosFunctionCallDispatchers.FunctionCachedCallNode`: once the
    unary Closure-identity cache saturates, cache the prepared executable
    `RootCallTarget` and its `DirectCallNode`, not the Closure instance or
    its captured lexical environment. Different Closures sharing the
    same source definition may therefore enter the same physical root,
    while the current Closure and its actual captures travel in the B5
    arguments at every call.
  - Bound the secondary target PIC to three entries, checking the
    entered Context, the already-prepared plan, the plan's language,
    the need for Context-local projection, and the selected effective
    call target. Deferred plans and foreign/projection-required plans
    remain on the general resolver; there is no preparation in a guard.
    The `@Megamorphic` fallback still handles other target shapes.
  - Add a regression witness that repeatedly invokes fresh Closure
    instances with different captured offsets and different source
    targets beyond cache limits. The test was added in the historical
    implementation, but was not executed in this versioned replay.
  - **Historical semantic limitations:** this additional direct-call PIC
    does not repair D013 / `Object.call`: an implicit source-Closure
    invocation may bypass an instance-local `call` override, whereas an
    explicit `object.call(...)` send still selects the override.
    `ReturnHome`, suspension, cancellation, Task/C-prime ownership,
    non-local control transfers and D179 C0 lexical behavior require
    independent acceptance. The earlier Context-owned BigInteger
    factory and D197-C numeric-prototype delegation remain separate
    concerns. Protos uses `long`/`Long` for signed-64 Integer values;
    nothing in this replay establishes compiled performance or full
    semantics. No tests, build or benchmarks were run after the bump.
  - Original implementation: `175ff17945d91e63e5e0a7b95e6eacb6ad06eae1`.
    Both source/test blobs are reproduced exactly, without alterations.

## 0.4.37-SNAPSHOT

- **I115 — GraalPy-style unary Closure PIC quickening (historical).**
  - Restore an identity-keyed, weakly cached source-Closure `CallUnaryMethod`
    direct PIC: an installed `callPFunction` entry matches the concrete
    Closure, not just its shared execution plan, and uses the prepared
    `cachedPlan` and stable `RootCallTarget` for the direct call.
  - Raise the experimental unary PIC limit to four entries. Once four
    distinct Closure identities have been seen, the `@Megamorphic`
    specialization `callSourceMegamorphic` takes over through the common
    `ProtosFunctionCallDispatchers.CallNode` and the B5 call layout.
    Retain single-Context and prepared-plan restrictions on the direct hit;
    source-function fallback is restored rather than disabled.
  - Reconcile the deferred-caller test with identity-cache saturation:
    the underlying target is checked independently, not inferred from a
    remaining direct cache entry. Add a 101-call structural witness for
    quickening (`c.CallUnaryMethod$CallPFunction` on the first four calls)
    and the generic operation after saturation. The witness was added
    historically, but has not been executed during this versioned replay.
  - **Historical semantic limitations:** the preceding D013 / `Object.call`
    behavior still lets direct source-function calls bypass a local `call`
    override, even though an explicit `object.call(...)` send selects it.
    Identity-PIC quickening alone does not establish `ReturnHome`,
    suspension or cancellation correctness, Task/C-prime ownership,
    non-local control transfer, D179 C0 lexical semantics, or dynamic
    numeric-prototype delegation. The Context-owned BigInteger Prelude of
    V55 remains a separate D197-C concern. `long`/`Long` is the intended
    64-bit numeric lane; no new arithmetic or delegation acceptance is
    claimed here. No runtime tests, graph evidence, benchmarks, or
    compilation were executed for this historical replay.
  - Original implementation: `b5263d39cf277c1fb2ebfd1458aed6d62461b784`.
    The three source/test blobs are reproduced without modification.

## 0.4.36-SNAPSHOT

- **I115 — GraalPy-style arithmetic and Context-owned BigInteger promotion (historical).**
  - Replace the guest signed-64 `ProtosIntegerValue` wrapper with Java `Long`
    as the canonical semantic Integer in-range, retaining
    `ProtosLargeIntegerValue` for values outside signed 64 bits. Remove
    `ProtosIntegerValue.java`; normalize integer reads, interop,
    numeric relations, slot storage and tests to the new representation.
  - Introduce direct `NumberSubtract` and `NumberMultiply` fast paths,
    beside `NumberAdd` and the general `ProtosNumericAddNode`,
    `ProtosNumericSubtractNode` and `ProtosNumericMultiplyNode`.
    Signed-64 arithmetic uses primitive `long` / `Math.*Exact`; overflow
    promotes exactly to a BigInteger-backed guest value, without int
    narrowing or a generic message send on the numeric hit.
  - Introduce `ProtosNumberFactory`: a GraalPy PFactory-style numeric
    constructor owned by `ProtosLanguageContext`. Its prototype Prelude
    is bound on initial root entry, rather than recovered from caller
    frames at every overflowing arithmetic site. Non-numeric fallback
    retains dynamic selector dispatch.
  - Add LONG-4/LONG-5 arithmetic witnesses and rename
    `ProtosIntegerValueRepresentationTest.java` to
    `ProtosLongRepresentationTest.java`; update the wider test corpus
    for the new `Long` representation. No historical runtime tests or
    performance measurements are claimed for this replay.
  - **Historical source delta:** GitHub reports 221 file entries:
    212 modified, 7 added, 1 deleted and 1 renamed. With Git
    `--no-renames` the rename is one addition and one deletion:
    222 changes = 212 modified, 8 added and 2 deleted.
    All historical source/test blobs, including the removed class and
    renamed test, are retained without alteration.
  - **Historical semantic limitations:** the Context-owned numeric
    factory pins an initial Prelude; a different caller Prelude or
    an overridden numeric prototype may therefore not redirect
    large-Integer construction, so D197-C / dynamic number protocol
    semantics remain open. The prior source-function `Object.call`
    override discrepancy (D013) also remains. Signed-64 `long` is the
    intended numeric carrier, but project-wide conformance of
    `ReturnHome`, Task/C-prime, suspension, cancellation, non-local
    control transfers, D179 C0 lexical selection and optimized
    internal message execution has not been established. Replacing
    the guest wrapper with `Long` also needs independent interop,
    identity and delegation acceptance. No benchmarks, graph evidence
    or compilation/tests were executed for this historical replay.
  - Original implementation: `bdca637a534ca9a1e071e9670461fddf61ae6690`.

## 0.4.35-SNAPSHOT

- **I115 — GraalPy-shaped unary call lowering and single-Context direct dispatch (historical).**
  - `CanonicalToBytecodeLowerer.emitCall` emits a terminal one-argument
    `CallUnaryMethod` directly from its evaluated callable and argument,
    removing intermediate BytecodeLocal staging for this admitted case.
  - `ProtosCallUnaryMethodNode.callPFunction` adds a three-entry, weak
    Closure-identity PIC with `@ForceQuickening` and a `DirectCallNode`.
    Its fast admission requires one Context, a prepared source execution
    plan in the caller root's language, stable code and Context-projection
    assumptions. Deferred, multi-context and projection-required cases
    retain the Context-aware `FunctionCachedCallNode` fallback.
  - `ProtosLanguage` records the single-Context language-instance fact and
    invalidates this condition when `initializeMultipleContexts` runs.
    In `ProtosFunctionCallDispatchers`, a proven non-suspending callee
    returns its direct result without terminal continuation wrapping;
    unproven callees still use the existing completion path.
  - Source and test delta: 4 modified files, 0 added, 0 deleted; exact
    original source/test blobs are preserved without further edits.
  - **Historical semantic limitations:** the previous source-function
    selection behavior still bypasses instance-local `Object.call`
    overrides for direct source function calls, a known D013 dynamic-send
    divergence. The direct path also depends on sound single-Context,
    code/projection-stability and non-suspension proofs; continuation,
    Task/C-prime, ReturnHome, cancellation, structured cleanup and non-local
    transfers require independent conformance. D179 C0 lexical behavior
    remains unproven; `long`-only integer and internal-specialized message
    execution remain requirements, not certified project-wide here.
    No runtime tests, compilation graphs or performance measurements are
    asserted for this reconstructed historical version.
  - Original implementation: `88c175a941c58f17cf3096626d5fdb79bdd7aea5`.

## 0.4.34-SNAPSHOT

- **I115 — experimental GraalPy-shaped source-function root and Context lookup.**
  - Resolve an already prepared source Closure's bytecode activation root directly
    when its execution plan belongs to the entered Context and needs no
    Context-local projection. Preserve the general target lookup for deferred
    plans and Context-local projections, rather than assuming all Closures
    share a physical target or lifetime.
  - The shared `ProtosFunctionCallDispatchers` resolves its entered
    `ProtosLanguageContext` using `current(node)` instead of the host-side
    `currentIfEnteredForRuntime()` lookup.
  - Update source-call admission tests and deferred-caller tests to pin the
    historical selection of a source function despite an instance-local
    `call` override (including overrides created during argument evaluation).
    A separate explicit `late.call(7)` still selects that local method.
  - Original source delta: 3 modified files, 0 added, 0 deleted. All source
    and test blobs are reproduced verbatim from the historical commit.
  - **Historical semantic limitations:** the source-function direct-dispatch
    rule bypasses an instance-local `Object.call` override on `callOne(late, 7)`.
    This is a known D013/dynamic-message-selection semantic divergence, not
    evidence that Protos' ordinary message semantics were recovered. Deferred
    caller, ReturnHome, Task/C-prime, suspension/resumption, cancellation,
    structured cleanup, non-local transfer and D179 C0 require independent
    conformance. `long`-only integer representation and internal specialized
    message execution remain project-wide requirements, not certified here.
    No runtime tests, Truffle graphs, timing or performance acceptance were
    run for this reconstructed versioned historical commit.
  - Original implementation: `b3730cd8abbfdcb1674c650019a3ab4c572472a0`.

## 0.4.33-SNAPSHOT

- **I116 — experimental unification of Bytecode DSL semantic execution and on-demand Truffle tooling**
  in the chronological I115 source branch. This is the original implementation
  state, not a certification of semantic or performance equivalence.
  - Replace the separate prepared-call, guarded-send and callback entry
    mechanisms with `ProtosFunctionCallDispatchers`, `ProtosCallRequest`,
    `ProtosBoundMethod` and fixed-arity `CallNilaryMethod`, `CallUnaryMethod`,
    `CallTernaryMethod`, `CallQuaternaryMethod` and `CallVarargsMethod` nodes.
    Add Context-owned builtin call roots and dispatch source/native Closures
    through the shared B5 argument protocol, including selected method homes.
  - Route structured Boolean/control, collection and Map protocols, Task-owned
    execution, host-entry continuation and I/O C-prime through shared call
    requests and dispatcher-owned invocation handling. The revised continuation
    transport intends to resume a suspended callee without repeating its
    original call; cancellation and ReturnHome ownership require independent
    behavioral conformance checks.
  - Reconcile Truffle tag-tree and debugger scope access with on-demand
    activation materialization. Remove historical inline callback binding
    preparation/tooling, guarded send helpers and other superseded code.
    The removal of inline-specific scope projection is a tooling-behavior
    change requiring explicit debugger and instrumentation verification.
  - Original source delta: 87 files (9 added, 71 modified, 7 deleted).
    The entire implementation, test and source file tree delta is copied
    byte-for-byte from the original commit. The historical tests were not
    run while constructing this versioned replay; no tests, Tier 1/Tier 2
    graphs or timing results are claimed for this reconstructed version.
  - **Historical semantic limitations:** unification alone does not prove
    `Object.call` override selection, frozen-state or lexical authority,
    exactly-once ReturnHome completion, Task cancellation, C-prime ownership,
    suspension/resumption, structured cleanup, non-local transfers or D179 C0.
    The signed-64 `long` numeric contract and specialized internal message
    execution still require independent regression gates across all paths;
    compatibility of on-demand Truffle tooling remains unverified here.
  - Original implementation: `0c1df650522717a390089eaeac111135935a7862`.

## 0.4.32-SNAPSHOT

- **I115 — experimental convergence of the unary Closure PIC and local `run` root**
  with GraalPy-style fixed-arity dispatch. This entry records the original
  implementation state; replaying its bytes is not semantic acceptance.
  - Rework the `CallUnaryMethod` direct-call PIC around weakly held concrete
    Closure identity (limit three), with guarded canonical `Object.call`
    selection and root-call, code, and Context-projection assumptions.
    On saturation the generic tier handles the megamorphic transition.
  - Specialize `MaterializeCurrentClosure` for noncapturing Closures whose
    `ReturnHome` is unobservable, without binding or publishing a creator
    activation. Select the experimental local `Call1`/`run` result transport
    from compilation facts rather than an invocation name or a separate PIC.
  - Add `ProtosI115UnaryPICConvergenceTest` for fresh Closure identity,
    override/restore behavior, cache saturation, capture/ReturnHome cases,
    and local unary call-root structure. Historical tests are preserved,
    not executed by this versioned replay; no Tier 1/Tier 2 graph or timing
    measurements are claimed here.
  - **Historical semantic limitations:** the lightweight noncapturing
    Closure may omit creator metadata, and the admitted compact path can
    bypass caller/Task/ReturnHome lifecycle and Context admission. Dynamic
    `Object.call` overrides, suspension and non-local control transfer
    require ordinary fallback and have not been proven equivalent on all
    paths. The prior experimental H7 numeric-protocol and Boolean-control
    divergences and D179 C0 remain open. The signed-64 `long` contract is
    retained in the source replay, but full semantic and performance gates
    are not established by this commit.
  - Original implementation: `4c69f201c560abed905b0a38065b6df6dda1c754`.
    All four original source/test blobs are preserved byte-for-byte.

## 0.4.31-SNAPSHOT

- **I115 — experimental convergence of H7 signed-64 numeric fast paths and
  direct Closure calls with GraalPy.** This is a historical implementation
  checkpoint; the following code and tests are preserved as originally
  committed, not rerun or independently accepted by this replay.
  - Replace 32-bit `int`/`Integer` compact numeric paths with primitive
    signed-64 `long`/`Long` literals, arithmetic and comparisons. The H7
    `NumberAdd` fast path uses checked `long` addition; signed-64 overflow
    promotes to the exact large guest Integer using the effective Prelude.
    A host-supplied Java `Integer` is normalized at guest boundaries and
    is no longer an admitted 32-bit internal numeric carrier.
  - Extend the H11 reduced unary call ABI to transport the effective Prelude
    when a callee registers `NEEDS_CALLER_PRELUDE`, including cold recovery
    on initial numeric overflow. Keep deferred caller transport where it
    is needed, and allow raw `Long` results to cross the admitted compact
    root and Polyglot boundary without premature guest materialization.
  - Update method-call, static/recursive Closure, compact-lowering and
    factorial test expectations. Original tests cover signed-64 literals,
    recursive arithmetic, exact overflow, lazy Prelude propagation and
    host return representation; none are executed as part of this replay.
  - **Historical semantic limitations:** some direct H7 method-call paths
    no longer require a global proof of non-suspension, so exceptional
    continuations and non-terminal suspension may diverge. Experimental
    arithmetic does not always perform the ordinary Prelude numeric-protocol
    admission or dynamic `+`/comparison selection. `Object.call`, Boolean
    control, caller authority, `ReturnHome`, Task lifecycle and D179 C0
    retain historical open divergences. This local `long` migration does
    not establish full language-wide semantic or performance acceptance,
    and no Tier 1/Tier 2 graph evidence is claimed by the replay.
  - Original implementation: `4ac5d115f7c00294be1a8a0133b988eab80708b9`.
    All fourteen original source/test blobs are preserved byte-for-byte.

## 0.4.30-SNAPSHOT

- **I115-C6 — reconcile Bytecode DSL call and Boolean-control oracles with
  the GraalPy-shaped physical lowering.** This is a historical migration
  checkpoint, not a completed semantic or performance acceptance.
  - Add `ProtosCallUnaryMethodNode` with a specialized unary Closure call
    and its generic fallback; converge fixed-arity terminal lowering on
    `CallUnaryMethod` / `CallNilaryMethod`, and retain `CallVarargsMethod`
    for spread and variable arity. Move shared Closure admission helpers
    into `ProtosClosureCallPreparation`; retire the obsolete composed
    `InvokeClosureArguments` operation. The new unary node is the one added
    original source file in this commit.
  - For host Closures with observable `ReturnHome`, add an ordinary-entry
    fallback with RootActor ownership, prepared call completion and
    continuation handling. The narrow no-home direct/indirect route remains
    experimental for Closures with unobservable homes.
  - Reconcile builder, profiling, continuation, CallN/spread, tail-call,
    PERF042 and deferred-caller test oracles with the new physical DSL.
    Update the nested Boolean-control oracle to expect inline conditional
    branches and the H7 `NumberAdd` operation, while still recording
    dynamic-receiver fallback cases. No tests are executed in this replay.
  - **Historical semantic limitations:** retaining the experimental
    compact paths does not prove dynamic `Object.call` selection,
    `ReturnHome`, caller authority, suspension, cancellation or
    non-local control-transfer equivalence; D179 C0 also remains open.
    GraalPy-shaped H7 arithmetic/Boolean paths still use Java `int`
    carriers beyond the H10 `long` attribute lane, in conflict with the
    Protos long-only design. Structural oracle changes are not Tier 1/
    Tier 2 graph evidence or a correctness/performance acceptance.
  - Original implementation: `bbba82478bc48721b75d01b587b5cdbf397f90ac`.
    All thirteen original source/test blobs (one addition) are preserved
    byte-for-byte, without editing the code or historical commits.

## 0.4.29-SNAPSHOT

- **I115-H11 — experimental convergence of Closure and method calls on a compact unary ABI.**
  - Replace the H7 method-call B5-like experimental header with two selected
    unary argument layouts: `[supplied0]` for callees that do not require a
    lexical owner; `[selectedClosure, supplied0]` for roots using `ReadGlobal`.
    A compilation-final callee-root fact selects the layout after lowering.
    `CopyArguments` and `ReadGlobal` recognize these layouts alongside B5.
  - Apply the same reduced unary call protocol to admitted direct Closure
    sends and experimental `CallBinaryMethod`, including indirect tiers where
    admitted. Preserve the selected home only in the generic method lookup
    tier; the cached `GetMethod` path reads a Shape-backed selected value
    through a cached `DynamicObject.GetNode` without copying the slot value.
  - Extend H7 convergence and deferred-caller test coverage for layouts with
    and without captured lexical owner, generic home selection and reduced
    caller publication. These original tests are not run on this replay.
  - **Historical semantic limitations:** the optimized unary ABI omits the
    receiver, method home, caller, `Task` and `ReturnHome` transport, and
    non-local control transfers are not fully translated. Direct admission
    does not establish equivalence to dynamic `Object.call` selection or
    general suspension/continuation semantics. H7 still contains Java `int`
    carriers outside the H10 attribute `long` lane, so Protos' long-only
    goal is not yet met; D179 C0 and other existing experimental divergences
    remain unresolved. No complete correctness or performance acceptance is
    claimed for this historical point.
  - Original implementation: `b429ee096421fc55f326cfff9b986d0155b9d4a8`.
    All ten original source/test blobs are preserved byte-for-byte.

## 0.4.28-SNAPSHOT

- **I115-H10 — experimental 64-bit Shape-backed attribute operations.**
  - Replace `ExperimentalSetAttribute.directInt` with a signed-64 `directLong`
    specialization and use a `PropertyGetter.getLong` fast path in
    `ExperimentalGetAttribute`, with a guest-value Object fallback.
    Compact numeric assignment emits a `Long` constant for eligible
    attribute stores rather than narrowing the value to `int`.
  - Keep one authoritative Shape-backed `DynamicObject` store: normalize
    Protos guest Integer values and residual Java `Integer` carriers into
    Java `Long` on storage, then return guest-facing `ProtosIntegerValue`
    representations from standard binding reads, snapshots and removals.
    Read admission no longer mutates storage simply to convert to `int`.
  - Extend tests for signed-64 storage, large positive and negative values,
    type generalization, removal/recreation and the typed/object fallback.
    A generalized Shape may continue using an Object location after recreate.
  - **Historical semantic limitations:** this H10 attribute-storage lane
    uses `long` (signed 64-bit), but other I115-H7 experimental operations
    still use Java `int` and raw `Integer` carriers, so Protos' global
    long-only design is not yet achieved. The H8/H9 direct write retains
    the deliberate bypass of frozen-state mutation checks; other known
    divergences in `Object.call`, `ReturnHome`, suspension and D179 C0
    remain unresolved. No complete semantic or performance acceptance
    is implied, and no behavioral tests run on this historical replay.
  - Original implementation: `f8dce47fbf371e5033c3fccd178cfb592c8867ac`.
    All six source/test blobs are copied without modification.

## 0.4.27-SNAPSHOT

- **I115-H9 — experimental Shape-backed object attribute access.**
  Replace the ordinary object's prior map/cell value storage with one
  authoritative `DynamicObject` whose `Shape` holds the local slot values.
  The `LinkedHashMap` continues to index names and optional PIC `SlotCell`
  handles but no longer duplicates guest values.
  - Introduce `ExperimentalGetAttribute`, with a cached `PropertyGetter`
    and a primitive `int` specialization followed by an Object fallback.
    The lowering of admitted compact member reads now uses this operation
    instead of `ReadMemberAtRoot`; call-site guards and assumptions govern
    selected locations and non-Closure continuity.
  - Specialize `ExperimentalSetAttribute` for primitive `int` writes and
    Object writes using `DynamicObject.PutNode`. Both use the same selected
    Shape-backed storage as ordinary lookup and writes; invalidation of
    value-lookup dependencies remains the selected-slot responsibility.
  - The experimental readable-slot admission may normalize an existing
    signed-32 guest `ProtosIntegerValue` into a raw Java `Integer` in the
    authoritative store. Extend the compact slot read/write tests and
    pay-as-you-grow storage tests for the new physical representation.
  - **Historical semantic limitations:** the optimized write retains H8's
    deliberate bypass of frozen-state checks and guest value normalization.
    Primitive `int` (`Integer`) carriers remain in storage and at compact
    boundaries, in conflict with Protos' long-only Integer design. Caching
    Shape-backed locations is experimental and does not establish general
    equivalence with dynamic message selection, mutable object semantics or
    lexical rebinding under D179 C0. Previous divergences involving
    `Object.call`, `ReturnHome` and suspension remain unresolved.
    This historical replay does not claim compilation, behavioral tests,
    numerical conformance or a measured performance improvement.
  - Original implementation: `24ad02ed3a21f946e7222be64c144e4700e64cdd`.
    All seven original source/test blobs remain unchanged in this replay.

## 0.4.26-SNAPSHOT

- **I115-H8 — experimental GraalPy-shaped compact object-slot writes.**
  Introduce `ExperimentalSetAttribute` in the Bytecode DSL for a compact
  explicit assignment to an existing member of a module-captured object.
  The receiver is evaluated before the right-hand side, and the write
  executes as one specialized bytecode operation, not a prepared send.
  - At specialization installation, cache the identity of an exact
    `ProtosObjectValue` and its stable physical `SlotCell` through
    `ProtosValueLookup.experimentalWritableSelection`. A valid Truffle
    assumption protects the selected location, while slot-value changes
    invalidate value-lookup dependencies. The slow tier uses ordinary
    `assignLocalSlot` and emits a guest error for an invalid mutation.
  - Expand compact-root admission and emission for explicit member stores
    and selected member reads. Reuse `ReadGlobal`, `ReadMemberAtRoot`, and
    the R5 raw-Integer return convention on the experimental path.
  - Extend `ProtosPrimitiveSlotWriteRegressionTest` to assert the physical
    `ExperimentalSetAttribute` shape and that the stored value may remain
    an internal Java `Integer` carrier. These are historical source tests;
    none were executed in this versioned replay.
  - **Historical semantic limitations:** the optimized write intentionally
    bypasses frozen-state checks and guest numeric normalization, allowing
    mutation that ordinary Protos would reject and permitting raw `int`
    (`Integer`) storage rather than the guest long-only Integer contract.
    It is NOT a normative implementation of `slotAtPut` or ordinary slot
    mutation. Prior experimental divergences in dynamic `Object.call`
    selection, `ReturnHome`, suspension, and D179 C0 lexical authority
    remain unproven or unresolved. No successful compilation, complete
    semantic test gate, or performance improvement is claimed.
  - Original implementation: `ba680dd2c93c9994f81164dc4b395279a455a1d6`.
    All five original source/test blobs remain unchanged in this replay.

## 0.4.25-SNAPSHOT

- **I115-H7-R5 — retain raw Integer carriers across compact returns and Polyglot host entry.**
  Preserve an H7 compact result as a Java `Integer` through both the bytecode
  root epilogue and the source-backed Polyglot host boundary, instead of
  allocating a guest `ProtosIntegerValue` at either location.
  - Add the compilation-final root flag `experimentalH7RawIntegerReturn`
    when compact lowering admits an H7 invocation. `EpilogForReturn` preserves
    raw `Integer` results for that root; other result types and generic roots
    retain their preceding projection behavior.
  - Use the same root flag in direct and indirect `ProtosHostExecutableClosure`
    entry. A flagged root returns an `Integer` unchanged; a non-flagged root
    still converts its `Integer` to a guest numeric value at the host edge.
  - Extend the original tests to inspect the **actual Java return type** at
    both boundaries and check that wider numbers and ordinary roots do not
    accidentally use this special case. No tests were run on this historical
    versioned replay; this is source-level reconstruction, not validation.
  - **Historical semantic limitations:** R5 deliberately exposes a Java `int`
    carrier (`Integer`) across the host boundary, conflicting with Protos's
    long-only `Integer` design. This historical exception does not establish
    preservation of guest Integer identity, dynamic `Object.call` selection,
    `ReturnHome` lifecycle, suspension or all caller/Prelude transport.
    Existing H7 numeric protocol deviations, D179 C0 lexical retargeting,
    and R4's version-dependent physical Object-frame indexing remain open.
    No compilation, comprehensive test acceptance, or performance gain is
    asserted for this replay.
  - Original implementation: `52e3f221bde99841c2a395a42af269bb8d98ca4c`.
    The four original source/test files retain their exact Git blobs.

## 0.4.24-SNAPSHOT

- **I115-H7-R4 — validated direct Object-frame ReadGlobal fast path.**
  Specialize the experimental H7 module-global read for compact Closure
  invocations using a PIC of up to three Closure identities, instead of
  repeatedly traversing the captured module's lexical chain on every read.
  - Resolve the module and its frame-backed physical `Object` location at
    PIC installation behind `TruffleBoundary`. Require that the selected
    binding, bytecode root, frame offset and physical Object value agree
    with the public Bytecode local-value API before admitting the fast path.
  - Use a hardcoded `USER_LOCALS_START_INDEX = 2` in the frame indexing:
    this is an internal generated layout assumption for Truffle 25.4.4.1.1,
    **not a portable Truffle API guarantee**. Revalidate on upgrades.
  - On the hot path, read `MaterializedFrame.getObject(physicalIndex)`
    directly, without `ProtosNumericValueSupport.guestValue` or a lexical
    lookup. Read the current slot value rather than caching a prior value,
    so an Object rebinding remains visible. A primitive frame-tag change
    invalidates the optimized site and recovers via a cold generic read.
  - Published invocations, non-Object physical slots, and locations without
    a valid admission use `readGeneric`. Extend the H7 tests with Object
    rebinding, layout/guard and primitive-tag transition probes.
  - **Historical limitations:** the cached frame mapping does not recheck
    binding presence or lexical layout on every read; deletion or late
    retargeting may violate D179 C0. This step does not resolve H7's `int`
    carriers versus Protos's `long`-only contract, `Object.call` dispatch,
    Caller/Prelude transport, nonterminal suspension or `ReturnHome`.
    The original source and tests are experimental; no new performance or
    comprehensive semantic acceptance is claimed for this replay.
  - Original implementation: `bb8fab40beacb1514bc9713bbf7642e2d985767b`.
    No compilation or behavioral tests were run on this historical replay;
    three original files retain their Git blobs unchanged.

## 0.4.23-SNAPSHOT

- **I115-H7-R3 — converge primitive-method-call bytecode with GraalPy 25.4.4.1.1.**
  Continue the experimental H7 `primitive-method-call` workload using the
  GraalPy-shaped `ReadGlobal`, `GetMethod`, `CallBinaryMethod`, `CopyArguments`
  and return epilogue, without changing the normative specification.
  - Introduce `ExperimentalGlobalReadLocation`: cache the exact physical
    frame-backed module-binding location at specialization installation;
    guard the selected BytecodeNode with `isCurrent()` and read its current
    value directly. The fast path does **not** recheck binding presence or
    layout; deletion and late lexical retargeting are not guaranteed (D179 C0).
  - Make `GetMethod` return the selected physical slot **value**, rather than
    a `Method` descriptor; retain identity and slot-stability guards but
    remove caller-Prelude selection and its cache guard from this hot path.
    Thus some inherited-Prelude needs are no longer registered there.
  - Lower the receiver once with `BindStackValue`, reload for `GetMethod`
    and `CallBinaryMethod`, and evaluate the explicit argument once, after
    method selection. `CallBinaryMethod` consumes `(callable, receiver,
    argument)`, with generic fallback retained for non-admitted selections.
    Direct compact method calls omit `methodHome` and normally carry no
    caller reference unless a callee root has registered a caller need.
  - Simplify the admitted `CopyArguments` and return paths: the unary
    parameter is copied directly from its B5 slot, without a per-call arity
    or publication fallback; experimental roots return primitive results
    without a caller-side carrier projection test. An internal `int` carrier
    may escape. H7's `int` paths conflict with Protos's `long`-only design.
  - Update H7 structural tests and B5 deferred-caller expectations. The
    original experiment document cites **R2** After TruffleTier 417/329,
    Final IR 866/714 (Tier 1/Tier 2) and five Tier 2 invokes. R3 has no new graph capture,
    invocation-count or timing measurement. These R2
    numbers are historical observations, not measurements of this replay.
  - **Historical limitations:** dynamic message overrides, Caller/Prelude
    transport, nonterminal suspension, `ReturnHome`, binding presence and
    D179 C0 lexical semantics are not established as equivalent. This is
    an experimental checkpoint, not an accepted semantic/performance gate.
  - Original implementation: `94e321dbb94ff8d6f65e380cd80a71bbdff3048f`.
    No compilation or behavioral tests were run on this historical replay;
    the ten original source, test and experiment files retain their Git blobs.

## 0.4.22-SNAPSHOT

- **I115-H7-R2 — preserve deferred B5 callers across direct sends.**
  A guarded, synchronous source `TryDirectSendZero` can retain the caller's
  unmaterialized B5 frame when the caller inherits its Prelude, instead of
  eagerly publishing a `ProtosActivation` merely for method dispatch. Roots
  owning their own Prelude retain the existing exact-caller route.
  - Query the selected callee root's `NEEDS_CALLER_PRELUDE` contract and
    transport the inherited prelude through `InvokeMethodOneTail.callerArgument`
    only when requested; otherwise keep the caller reference deferred. The
    callee may register that need on its first untransported read.
  - Change `compactImmediateMethodCallZeroNoHome` to accept a general B5
    caller reference, not only an already materialized activation.
  - Make the generic `InvokeClosureOneTail.exactCaller` request
    `NEEDS_EXACT_ACTIVATION` even if its incoming caller is already published;
    distinguish that conservative fallback from the direct Send0 route.
  - Restrict experimental `CallBinaryMethod` direct/indirect target admission
    to non-native, non-suspending source Closures with **unobservable**
    invocation `ReturnHome`; an observable captured home stays on ordinary
    send dispatch. This prevents silently discarding a materialized home.
  - Remove temporary `PROTOS_I115_TRACE_CALLER_NEEDS` probes. Extend H7
    method-call and deferred-caller structural tests: the A/B/C/D chain now
    records inherited-prelude needs on B, C and D, not A, with convergence
    after transport is established.
  - **Historical limits:** this is a targeted correction within the unfinished
    H7 experiment, not a complete semantic or performance gate. H7's `int`
    carriers remain contrary to Protos's `long`-only integer design; generic
    nonterminal suspension, dynamic message overrides and lexical authority
    are not proven equivalent. Earlier D179 C0 lexical-retargeting and H6
    host-entry / RootActor divergences are not resolved by this commit.
  - Original implementation: `a38f551b39a7681ec05c4154142f16488278a264`.
    Replayed without compilation or behavioral tests; all six original code
    and test files are required to retain their historical Git blob IDs.

## 0.4.21-SNAPSHOT

- **I115-H7 Unfinished** — experimental GraalPy-shaped physical convergence for a
  `receiver.identity(i)` method-call workload inside a literal `whileTrue`
  (reference result `499500`). This is a historical *unfinished* checkpoint,
  not a validated implementation or a normative specification change.
  - Add experimental Bytecode DSL operations `GetMethod`, `CallBinaryMethod`,
    `NumberAdd`, `NumberInPlaceAdd`, `Lt` and `CopyArguments`; introduce guarded
    method selection, small direct/indirect Closure call caches, and generic
    send fallbacks. Existing source-level message syntax remains unchanged.
  - Lower admitted literal `whileTrue` expressions as an internal `While`
    without creating condition/body Closure objects. Overrides of `whileTrue`
    and observable callback identity, capture and lifetime are not respected.
  - Replace H5's frozen `ReadGlobal` specialization with a module binding
    read that can observe later module mutations; late nearer lexical
    retargeting required by D179 C0 is still not fully preserved.
  - Specialize arithmetic and local storage on `int`, `long`, and `double`,
    with selected exact-overflow promotion and generic fallbacks. **Historical
    nonconformance:** `int` guest carriers contradict Protos's long-only
    Integer architecture. Primitive numeric fast paths bypass the Prelude
    protocol / overridden operators, and some mixed `int`/`long` pairs fall
    through to generic dispatch. This is not an endorsed long-only design.
  - The generic dispatch paths may publish a caller activation without
    promoting its frame-local bindings to lexical authority; nonterminal
    suspension has no correct continuation in the experimental compact root.
    Dynamic method selection, caller authority, cancellation and ReturnHome
    are not established as equivalent by the new optimization.
  - Add the non-normative experiment report and H7 regression tests. The
    original H7 note explicitly says that instruction censuses, Tier 1/Tier 2
    graphs and timings were pending human execution. A temporary
    `PROTOS_I115_TRACE_CALLER_NEEDS` diagnostic remains in the original code.
  - Reconstructed byte-for-byte (for original files) from commit
    `b51a9a5f82271dc0c6b41897a0afc25e3105e3cc`. No behavioral tests or
    compilation were performed during this versioned historical replay.

## 0.4.20-SNAPSHOT

- `I115-H6` (experimental collapse of source-backed Java/Polyglot host entry):
  enter the guest with exactly one compact no-home B5 call when a source-backed
  executable receives zero or one supplied argument. The installed call target
  is invoked directly or indirectly; the target-null generic path remains.
  - Remove host-entry processing from this admitted route: no Context
    revalidation, Process or RootActor admission, host-entry extent or Task,
    ReturnHome completion, suspension/continuation driver, bridged
    control-transfer translation, failure mapping, or guest-result projection.
    Raw guest-call results cross the Polyglot boundary without conversion.
  - Add focused H6 host-entry regression tests and revise interop and future-
    suspension oracles to reflect the narrower experimental entry contract.
    These original test changes are evidence of historical intent, not proof
    that ordinary host entry remains semantically equivalent.
  - **Historical semantic limitations:** bypassing RootActor and Context
    checks can violate normal concurrency and lifecycle guarantees; omitting
    suspension, ReturnHome, and control-transfer handling can change observable
    program behavior. Existing H5 stale global-value caching and H3 wrapping
    long arithmetic remain, as does the D179 C0 late-retargeting divergence.
    This is not a normative change or a complete correctness/performance gate.
  - Reconstructed from original I115 commit
    `f8e51ff6b76fbbe011c77dfdcc43f9f7ac26afb8`.
    No behavioral tests were run on this versioned historical replay.

## 0.4.19-SNAPSHOT

- `I115-H5` (experimental factorial hot-path reductions): retain the physical
  factorial root while removing experimental unary dispatcher/context helpers
  and restoring one direct `InvokeExperimentalClosureOneTail` Bytecode DSL
  operation for statically admitted recursive Integer callees.
  - The optimized Call1 resolves the target once when installing its direct
    specialization and invokes it with a reduced static unary header, without
    transporting the caller, Prelude, Task or ReturnHome. Non-admitted callees
    continue through the ordinary Call1 path.
  - Simplify module `ReadGlobal` to an unguarded specialization-installation
    value cache; an uncached interpreter still resolves each execution.
    Remove primitive return-carrier projection for experimental callee roots.
  - Update factorial and structural oracles, removing three experimental
    call-dispatch/context source files no longer used by this iteration.
  - **Historical semantic divergences:** the cached global value does not
    observe later rebinding, module/Context changes or authority mutations;
    the direct static invocation does not recheck `Object.call`, receiver
    identity or Context, and omits suspension and bridged control-transfer
    handling. The existing H3 wrapping `long` arithmetic and D179 C0 late
    lexical-retargeting divergence remain. This is not normative conformance.
  - Reconstructed from original I115 commit
    `d319618e17e7c36a9f1713784ac0564a284d2f36`. No behavioral tests
    were run on this versioned historical replay.

## 0.4.18-SNAPSHOT

- `I115-H4-B` (converge experimental unary Closure dispatch on B5):
  move the experimental direct Call1 path back to the existing B5+ frame
  header and its caller transport, instead of the separate seven-slot ABI7.
  - Stage the Closure and caller in the B5+ header, with dynamic per-receiver
    admission and the ordinary D013 terminal Call1 fallback for misses. The
    revised path accounts for target stability, ReturnHome and suspension
    handling without certifying general semantic equivalence.
  - Remove the unused `ProtosExperimentalCalleeContext` and its private
    per-invocation state. Use B5 as the callee frame contract and eliminate
    the ABI7-specific lowering admission and header implementation.
  - Revisit the experimental module `ReadGlobal` prelude cache: inspect the
    module on every read, and avoid caching an outer scope that could be
    shadowed by a nearer scope. Refresh structural factorial tests.
  - **Historical limitations:** the separate H3 static recursive unary lane
    retains the wrapping `long` overflow divergence; D179 C0 late lexical
    retargeting remains unproved for module-owned `ReadGlobal`. The altered
    bytecode tagging and generator configuration remain experimental. This
    historical replay performs no compilation, tests or acceptance gates.
  - Reconstructed from original I115 commit
    `883c77744936fa8e2fe65fb6272987f13745d315`.

## 0.4.17-SNAPSHOT

- `WIP I115-H4-B` (experimental GraalPy-style unary-call infrastructure):
  port an experimental `CallUnaryMethodNode`-like direct call path with
  `ProtosExperimentalCallUnaryNode`, dispatch helpers, caller/callee contexts,
  and a separate seven-slot GraalPy-inspired frame-argument ABI (ABI7).
  - Admit the direct path only for a statically certified ABI7-compatible
    recursive Integer callee; use the ordinary B5 terminal Call1 for
    non-admitted callees. Introduce a per-Closure code-stability Assumption
    and specialized module `ReadGlobal` support.
  - Experiment with automatic RootTag/RootBodyTag tagging, Bytecode DSL
    serialization configuration, frame bytecode-index storage and an optional
    JVM-controlled call-trace instrumentation; these are not validated as
    behaviorally equivalent to the preceding instrumentation configuration.
  - Extend factorial and incompatible-callee tests; the original commit is
    explicitly WIP and does not establish successful compilation, complete
    regression coverage or a passing semantic/performance acceptance gate.
  - **Historical semantic divergences:** the specialized route does not
    preserve all dynamic `Object.call` selection, caller/Task/Prelude/
    `ReturnHome` transport, suspension, bridged control transfers, null-result
    validation or B5 interoperability. The experimental `ReadGlobal` behavior
    still diverges from D179 C0 late lexical retargeting; H3's wrapping `long`
    arithmetic and disabled exact-overflow test also remain. These are
    historical characteristics, not normative changes or an acceptance.
  - Reconstructed from original I115 commit
    `0b75afa903a33c3fba0acca1c7b9300531aaa2f4`.
    No behavioral tests have been run on this versioned historical replay.

## 0.4.16-SNAPSHOT

- `I115-H4-A` (experimental GraalPy-style direct terminal Call1 for factorial
  `run()`): add `InvokeExperimentalClosureOneTail`, with a cached
  Closure-identity PIC and `DirectCallNode` for a structurally admitted
  terminal call to a certified H3 recursive Integer Closure. Non-admitted
  invocations retain the general `InvokeClosureOneTail` path.
  - Record the certified recursive module binding during lowering and
    restrict the new operation to the corresponding terminal unary call
    of a parameterless Closure root; preserve other ordinary call sites.
  - Update the historical factorial structural expectations: three
    physical roots and the six-instruction `run()` including one
    `InvokeExperimentalClosureOneTail`. These are source-test oracles,
    not measurements made during this versioned replay.
  - **Historical semantic divergence:** the direct PIC ignores dynamic
    `Object.call` replacement and cross-Context projection, reuses a
    cached target without a code-stability invalidation equivalent,
    transports no caller, Task, Prelude or ReturnHome, and does not
    check ReturnHome observability or potential suspension. It also
    bypasses bridged control-transfer translation and null-result
    checking and returns directly to the calling root's epilogue.
  - H3's wrapping signed-64 `long` overflow and the earlier D179 C0
    late-lexical-retargeting divergence remain unresolved. This is an
    intermediate experimental optimization, not a complete semantic,
    suspension, Context-isolation or performance acceptance gate.
  - Reconstructed from original I115 commit
    `aac8ee5fc091491c4b04447ae4c9dc271fdc2e7a`.
    No behavioral tests were run on this versioned historical replay.

## 0.4.15-SNAPSHOT

- `I115-H3` (experimental factorial bytecode hot path): specialize the
  previously admitted compact recursive factorial lane, without widening
  its correctness or method-selection guarantees.
  - Bind the recursive unary Integer parameter directly to its Bytecode DSL
    local using `ExperimentalLongArgument0` and `StoreLocal`; move the
    experimental static-unary argument to the usual B5 supplied-argument
    offset. The private frame representation still is not equivalent to
    an ordinary B5+ invocation.
  - Make the admitted `ExperimentalIntegerGreaterBool` long-only and perform
    direct wrapping signed-64 subtraction and multiplication without the
    earlier exact overflow promotion or generic Integer fallback.
  - Initialize the static direct-call target once in the first entered
    Context, without a per-invocation Context guard. A call from a different
    Context may retain the original target, and the direct path no longer
    translates a bridged control transfer.
  - Update the historical factorial FULL DSL expectations: 3 physical roots;
    module 17, run 6 and factorial 19 instructions, total 42. The original
    overflow-to-large-Integer regression test is explicitly `@Disabled` in
    this historical commit. These are source-test expectations, not results
    measured by this versioned replay.
  - **Historical semantic divergence:** signed-64 overflow wrapping conflicts
    with exact Protos Integer semantics; the experimental recursive call also
    bypasses dynamic `Object.call` selection, caller/Task/ReturnHome context,
    and some cross-Context/control-transfer guarantees. Earlier D179 C0
    module-binding lexical-retargeting divergence is not resolved. No
    semantic, suspension, or performance acceptance gate is established.
  - Reconstructed from original I115 commit
    `29d4f0e6e2089e901c164d886d31d83645ff4545`.
    No behavioral tests were run on this versioned historical replay.

## 0.4.14-SNAPSHOT

- `I115-H2` (PLAT066-C / experimental recursive factorial): restore the
  narrow compact recursive factorial lowering on top of the H1-FIX ordinary
  dispatch and frame-lexical-authority fallbacks.
  - Recognize the selected unary Integer recursive Closure and reintroduce
    the experimental `InvokeStaticClosureOneTail` execution lane with the
    reduced static unary argument layout; other roots keep the H1 ordinary
    B5+ invocation and selection paths.
  - Re-enable compact signed-64 arithmetic/comparison operations and exact
    overflow promotion in this admitted lane; leave numeric protocol lookup
    and dynamic selection to ordinary paths outside its strict admission.
  - Restore structural assertions for `factorial(20)` and its historical
    Bytecode DSL census: three roots, module 17, factorial 18, run 6,
    and 41 instructions in total. These are test expectations from the
    original I115 commit, not measurements on this versioned replay.
  - **Historical semantic limitation:** the reduced two-slot static unary
    ABI is not an equivalent replacement for B5+; the experimental recursive
    lane deliberately does not preserve every dynamic `Object.call` override,
    module-binding retargeting, or D179 C0 late lexical-selection guarantee.
    This snapshot does not establish full Protos semantic conformance or a
    general optimization acceptance gate.
  - Reconstructed from original I115 commit
    `a49cab249116bd59680ad50b3ec9ea192726dbda`.
    No behavioral tests were run on this versioned historical replay.

## 0.4.13-SNAPSHOT

- `I115-H1-FIX` (restore ordinary dispatch and frame lexical authority in compact lowering):
  retire the experimental reduced static-unary argument layout and the
  `InvokeStaticClosureOneTail` bypass for locally bound Closures. Preserve
  ordinary B5+ call arguments and `InvokeClosureOneTail`, allowing the
  standard `Object.call` selection and its override/fallback behavior.
  - Remove the static-local-Closure-call canonical admission and associated
    experimental recursive/static-call paths, while keeping the admitted
    compact lowering paths that remain eligible after the correction.
  - Use shared guarded primitive-numeric operations with ordinary dispatch
    on a numeric miss rather than the dedicated experimental Integer path;
    retain exact numeric services, overflow handling and resumable fallback.
  - Restore frame lexical-authority promotion for compact roots with proven
    declaration order, and reconcile parameter/frame-local admission.
  - Update structural and behavior tests for ordinary terminal call selection,
    numeric fallback and compact frame authority.
  - **Historical limitation:** these repairs address specific invalid
    experimental assumptions but do not establish full Protos conformance.
    The remaining compact and module-global specializations, including the
    prior `ReadGlobal` versus D179 C0 issue, need independent acceptance.
  - Reconstructed from original I115 commit
    `1c8c57f0eb75534fa004ec37d8ab6799d2b05dca`.
    No behavioral tests were run on this versioned historical replay.

## 0.4.12-SNAPSHOT

- `I115` (post-rebase Boolean control selector reconciliation): restrict
  experimental compact Boolean-control admission to `ifTrue`, `ifFalse`
  and `ifTrueIfFalse`; the `if` and `else` selector forms no longer enter
  this specialized branch.
  - Remove the shortcut that classified receiver-selected control results
    unconditionally as BOOLEAN. Preserve the existing branch-result join
    classification instead, avoiding an unjustified compact type proof.
  - **Historical limitation:** this is a narrowly scoped correction to the
    experimental I115 compact lowerer. It does not establish a complete
    override, method-selection, suspension or semantic-conformance gate.
  - Reconstructed from original I115 commit `8b839b40e22087187be18922d5e13becd05b7c6a`.
    No behavioral tests were run on this versioned historical replay.

## 0.4.11-SNAPSHOT

- `I115-R6` (experimental GraalPy-style module-global reads): introduce the
  `ReadGlobal` Bytecode DSL operation for lexically resolved ROOT/module
  bindings; read the live module authority directly, without materializing
  the caller or emitting captured-owner selection and conditional fallback.
  - Keep module presence, mutation and `PRESENT(null)` observable; only an
    ABSENT module binding consults outer scopes such as the Prelude through
    a cold fallback. Do not fall back to the receiver.
  - Reject inline-callback admission for module-owned reads and leave
    other captured reads on their existing owner-selection paths.
  - Extend capture, structural and factorial tests. The original I115-R6
    report records a reduction of factorial `run()` FULL DSL instructions
    from 17 (R5) to 6 (R6); this is historical evidence, not a measurement
    made on this versioned replay.
  - **Historical semantic divergence:** the statically resolved module read
    ignores a nearer binding created later, contrary to D179 C0's late
    lexical-retargeting behavior. The added design note is non-normative,
    grants no approval for changing `spec/`, and does not establish complete
    Protos semantic equivalence or a final performance gate.
  - Reconstructed from original I115 commit
    `48db4247d1d63e31a75fe908fe731b13536a0009`. No behavioral tests were
    run on this versioned historical replay.

## 0.4.10-SNAPSHOT

- `I115-R4` (experimental recursive factorial / Tier 1 graph evidence):
  extend compact Bytecode DSL lowering to selected unary Integer Closure roots
  and statically recognized recursive self-calls, retaining the signed-64
  `long` carrier model and using the existing static unary call path.
  - Register recursive module Closure bindings before nested root lowering
    and publish pending execution-plan cells early enough for recursive
    references to use the existing `InvokeStaticClosureOneTail` operation.
  - Admit certain module-level sequences consisting of named Closure
    creations to the frame-native authority path, and collapse immediate
    enclosing-scope captured reads into `ReadCapturedFrameLocalAtRoot`.
  - Add a deterministic `factorial(20)` correctness and Bytecode DSL census
    fixture, optional Tier 1 compiler-graph capture, and update captured-read
    structural oracles to recognize the revised lowering shape.
  - **Historical semantic limitation:** the experimental static recursive
    admission intentionally does not preserve every dynamic Object.call
    selection, module-binding retargeting, or dynamic presence guarantee.
    Physical instruction counts and opt-in compiler graphs are evidence,
    not a complete semantic or performance acceptance gate.
  - Reconstructed from original I115 commit
    `ce5d7af4a73fa630db7f526bb456b8115707385f`.
    No behavioral tests have been run on the versioned historical replay.

## 0.4.9-SNAPSHOT

- `I115` (host-to-guest Call0/Call1 fast entry): streamline the admitted
  non-suspending Polyglot `Value.execute()` path and extend static terminal
  `Call1` non-suspension evidence to a narrowly proved local-Closure pattern.
  - Refine effective-root non-suspension for a static terminal one-argument
    Closure call only when the callee body and supplied argument satisfy
    structural restrictions; retain UNKNOWN for unproved calls.
  - Cache host-entry eligibility for Closures with unobservable invocation
    `ReturnHome`, use direct compact Call0/Call1 B5 frame preparation and
    admit eligible host arguments without an extra supplied-argument array.
    The frame itself is still allocated, and fallback preserves normal
    conversion, call handling and suspension machinery.
  - Expand host-entry/Future tests for the static proof, argument provenance,
    fast and fallback routes, and correctness of eligible calls.
  - **Historical semantic limitation:** this optimization continues the
    temporary non-atomic RootActor admission bypass introduced in 0.4.8.
    Non-suspension alone does not establish concurrent-entry, reentrancy or
    termination equivalence, nor does the narrow static proof establish
    complete language conformance.
  - Reconstructed from original I115 commit
    `a0372478c1bad7faa22fbb6871324f451f0f3972`. No behavioral tests
    were run on this versioned historical replay.

## 0.4.8-SNAPSHOT

- `I115` (host entry / Call0): introduce a fast host-to-guest execution path for
  extracted source-backed Closures in standard Polyglot embedding when their
  effective root is proven non-suspending. Cache the proof at adapter creation
  and invoke the prepared guest call through its direct target.
  - For eligible `Value.execute()` calls, avoid conventional per-call RootActor
    admission and host-entry extent management; preserve argument conversion,
    owning-Context checks, B5 call preparation and return projection. Keep the
    ordinary admission/suspension path for unproven or ineligible cases.
  - Reject an unexpected pending continuation as a violated non-suspension
    precondition; retain fatal-error handling on the fast route. Extend
    host-entry/Future and canonical-callable interop test coverage.
  - **Historical semantic limitation:** the fast route deliberately omits
    exclusive RootActor admission. Its non-atomic owner/liveness check allows
    concurrency, reentrancy and termination races; non-suspension is not an
    equivalence proof for Actor isolation. The new design note
    `docs/design/I115_HOST_ENTRY_TEMPORARY_DEVIATION.md` is expressly
    non-normative. This snapshot does not establish full semantic conformance.
  - Reconstructed from original I115 commit
    `1510fdb7ccc8384da89eb2b534c046e3ab867df1`. No behavioral tests
    have been run on this versioned replay.

## 0.4.7-SNAPSHOT

- `I115-R` (GraalPy-inspired caller handling / PERF038-I / PLAT066-C):
  introduce `ProtosCallerFrameReference` for admitted terminal calls, preserving
  the exact caller's B5 header and optionally transporting its effective Prelude
  without eagerly publishing a `ProtosActivation`.
  - Record per-root caller needs (`NEEDS_EXACT_ACTIVATION` and
    `NEEDS_CALLER_PRELUDE`) with monotonic, invalidatable assumptions so
    subsequent calls prepare only requested information; retain validating
    general accessors for unadmitted and malformed representations.
  - Extend the terminal-call specialization to eligible one-argument method
    sends through `InvokeMethodOneTail`, preserving selection and fallbacks;
    refine compact-root non-suspension and captured lexical access paths.
  - Use exact Integer services on compact arithmetic overflow and results
    outside signed-64 range; this repairs numerical overflow handling but
    does not establish general user-defined method-selection equivalence.
  - Include terminal-call, deferred-caller, compact-frame and compiler-graph
    tests, plus the non-normative GraalPy caller-optimization report.
  - **Historical limitation:** this intermediate implementation retains
    experimental compact and caller paths. Its design report does not grant
    normative approval to all proposed relaxations; a complete semantic,
    suspension and performance gate is not asserted for this replay.
  - Reconstructed from original I115 commit
    `d9b803ca4299d4195964e5bcbd56e43d929b858a`.
    No behavioral tests have been run on the versioned replay.

## 0.4.6-SNAPSHOT

- `I115-Q` (PLAT066-C / PERF039) extends the experimental compact Bytecode DSL lowering for literal Integer returns and constant Boolean control.
  - Return an eligible signed-64 Integer terminal literal as a prepared guest `ProtosIntegerValue` constant, without compact return-carrier projection; keep the prior representation for unadmitted expressions and other terminal families.
  - Admit `whileTrue` conditions whose complete body has a proven Boolean final value, not only an Integer comparison (`I115-P`). Preserve the condition's preceding expressions on every iteration and use a direct primitive Boolean for `true`/`false` literals in structured control.
  - Add I115-P Boolean-while and I115-Q literal-return regression/structural tests, plus an opt-in compiler-graph capture oracle. Physical instruction censuses and opt-in captures are measurements, not proof of a general optimization or semantics gate.
  - **Historical semantic limitation:** this is an experimental compact subset inherited from I115-M/N. It does not establish full protocol-override behavior, arbitrary method selection, suspension, capture/ReturnHome equivalence, or complete Protos semantic conformance.
  - Reconstructed from original I115 commit `cfde3264318a7fab8b9df1a31f448c626210e553`. No behavioral tests have been run on this versioned replay.

## 0.4.5-SNAPSHOT

- `I115-O` (PLAT066-C / PERF041): optimize certified constant Integer
  returns in experimental compact Bytecode DSL roots.
  - Admit a returned root-local binding only when its terminal use is a
    lookup and all other stores to it are eligible literal statements
    (signed-64 Integer, `null`, `true` or `false`), with at least one
    Integer store and no other read of that binding.
  - Store prepared guest Integer constants directly at eligible write sites
    and skip the compact return-carrier projection for the proven return.
    Retain the previous lowering and return handling for unadmitted roots.
  - Add constant-provenance, conditional-assignment, and physical Bytecode
    census coverage, including the PERF041 variable-`ifTrue` case.
  - **Historical semantic limitation:** this was an experimental
    specialization layered on the I115-M/N compact path, not proof of
    general method-selection, override, suspension, or numerical-equivalence
    guarantees. Full language conformance was not established at this step.
  - Reconstructed from original I115 commit
    `9d41d119967a493e7236f7a0e3e76aeff2499fb9`;
    no behavioral tests were run on this versioned replay.

## 0.4.4-SNAPSHOT

- `I115-M/N` (PLAT066-C / PERF039 / PERF041): generalize the experimental
  compact Bytecode DSL lowering path for admitted root-local Integer operations,
  Boolean control and simple `whileTrue` loops.
  - Use specialized Integer operations and local Bytecode storage within the
    admitted compact roots, avoiding prepared message sends and persistent
    frame-backed lexical authority on those narrowly proven paths.
  - Lower admitted standard Boolean selectors (`ifTrue`, `ifFalse`,
    `ifTrueIfFalse`, `if`, `else`) to structured `IfThen`/`IfThenElse`
    operations. Require canonical Boolean conditions, never truthiness.
  - Add I115-M/N root and Boolean-control regression/structural tests and a
    PERF039 primitive-while census with opt-in real compiler-graph capture.
  - **Historical semantic limitation:** the experimental Integer operations
    do not generally preserve operator method selection, custom numeric
    methods or arbitrary-precision overflow promotion. The special Boolean
    lane is likewise not evidence of equivalence for arbitrary receiver
    overrides. This intermediate snapshot does not claim full Protos semantic
    conformance.
  - Reconstructed from original I115 commit `8de92642fa98eaa060fa39132fc97dfb472cc141`. No behavioral
    tests have been run on this versioned replay.

- `PERF041` (test-only, historical commit `e835fc1a5d068fd83da9413e53159d94d02f4c19`):
  add observational physical Bytecode DSL censuses for the primitive
  `true.ifTrue` and variable `value.ifTrue` workloads. Enumerate all
  source-group roots and instructions without filtering auxiliary roots,
  fallback paths or instrumentation. Verify the guest result but pin no
  static instruction-count budget. No behavioral tests were run during
  this historical replay; the Maven version remains `0.4.4-SNAPSHOT`.

## 0.4.3-SNAPSHOT

- `I115-H` (PLAT066-C): introduce an experimental static invocation path for a
  provably local Closure whose only use is a terminal one-argument call.
  - Use canonical identity and body-shape proofs to emit `InvokeStaticClosureOneTail`
    and a reduced unary call argument layout (`[marker, supplied0]`) instead of
    the normal Closure call preparation for the narrowly admitted case.
  - Add a lightweight non-capturing Closure representation when the body cannot
    observe its creator's `ReturnHome`; keep ordinary creation and fallback
    for cases outside the structural proof.
  - Add independent-builder and static-call structural/runtime tests that
    describe the admitted paths and their limitations.
  - **Historical semantic limitation:** this path was explicitly experimental
    and did not establish normative equivalence for dynamic D013 selection,
    Closure identity, creator context, caller/Task ownership or `ReturnHome`.
    It is not evidence of complete Protos semantic conformance.
  - Reconstructed from original I115 commit
    `7af12ea1c0c163da8de4bb745c6a245770914ba0`; no behavioral tests
    have been run on this versioned replay.

## 0.4.2-SNAPSHOT

- `I115-G` (PLAT066-C): classify whether each created Closure actually
  captures its creator's execution context. For a non-capturing Closure,
  reuse the creator's outer lexical environment instead of retaining its
  invocation's frame/activation; keep the capturing path for Closures that
  read, write or otherwise observe creator bindings.
  - Store eligible ordinary Closure bindings in plain Bytecode DSL locals
    instead of installing persistent lexical frame authority. Preserve the
    distinct storage path for genuinely captured bindings.
  - Extend lexical capture, terminal-call budget, independent-builder and
    PERF042 identity-root structural tests for the compact local layout.
  - **Historical semantic limitation:** at this point the optimization was
    explicitly recorded as diverging from the unconditional creator-context
    capture required by `spec/semantics/CALLABLES.md`, pending a design/semantic
    reconciliation. This entry does not claim full conformance or acceptance.
  - Historical reconstruction of original commit
    `015f76f946f2857c25f278142d475f2daf61e496` from I115. No new
    behavioral tests have been run on this versioned replay.

## 0.4.1-SNAPSHOT

- `PERF042` (PLAT066-C / I115): lower eligible terminal calls of a lexically
  bound one-argument Closure through the physical `InvokeClosureOneTail`
  operation instead of copying the callee's body into the caller during
  Bytecode DSL lowering. The callee retains its separate CallTarget and
  GraalVM decides whether to inline between targets.
  - Keep other I096 inline candidates on their existing paths, including
    immediate Closure literals and C0/C2/N calls. Preserve dynamic D013
    call selection and fallback; this lowering is not a non-suspension proof.
  - Update physical-root structural tests, terminal-call budgets and the
    opt-in compiler graph capture oracle for the new lexical C1 topology.
  - Historical reconstruction of `c00e6e8e2c33` from the I115 development
    branch. This is an intermediate development snapshot, not a claim that
    the full Protos semantic conformance suite passed at this revision.

## 0.4.0-SNAPSHOT

- Start the 0.4.x implementation development series and archive the complete 0.3.x implementation history. This transition changes only version and changelog metadata; it does not change Protos runtime behavior or the language specification.
