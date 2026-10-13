# Protos Implementation Changelog

Current implementation-series changes are kept in this file.

Historical implementation changelogs:

- [0.2.x](changelog/CHANGELOG-0.2.md)
- [0.1.x](changelog/CHANGELOG-0.1.md)

## 0.3.349-SNAPSHOT

- `I096` (#884): broaden PLAT057-B lexical inlining of ordinary Closure
  calls, still entered only behind the run-time selection guard.
  - Exact positional arities up to `MAX_INLINE_CLOSURE_CALL_ARGUMENTS` (8)
    are inlined through the variadic carrier; C0/C1 keep their direct fast
    path, and wider calls keep the unchanged physical call.
  - Binding calls are recognized in every operand position evaluated by the
    same activation (send and call operands, create/assign values, member,
    identity, inequality, indexed-assign and return operands), with the
    receiver and arguments staged once in order.
  - Bodies creating nested Closures are admitted. They always take the
    named-activation path, so nested Closures capture the callee's own fresh
    activation by reference, including after escaping. Inside an inline
    body, a Closure whose root this parse already lowered reuses that root
    and plan, so the group never gains duplicate roots and reparses replay
    the same roots. Captured reads inside an inline body no longer claim a
    physical owner frame for the inlined callee scope.
  - A large Integer literal inside an inline callback region now resolves
    its Prelude through the inline carrier (`PrepareSendOne.callerPrelude`).
  - PERF026 B3/C1 RootTag expectations now count the additional I096 inline
    callee regions.

## 0.3.348-SNAPSHOT

- `I095` (#878): one shared NORMAL-mode lowering for the canonical Boolean
  controls `ifTrue`, `ifFalse`, `if` and `else` (I095-C).
  - A canonical literal receiver selects its branch statically. Any other
    receiver is evaluated once and classified by the I092 canonical Boolean
    classifier. A selected literal Closure whose whole activation passes the
    existing I095-B proof runs inline in the caller root, with its own
    semantic RootTag and source section. `if`/`else` discard only the
    callback's normal result and return the original receiver. A
    non-canonical receiver performs the ordinary send with the staged
    receiver, materializing the literal only there.
  - Unproven callbacks, non-literal callables, spread, and DEBUG keep the
    established B-prime/ordinary topology. `ifTrueIfFalse`, `and` and `or`
    are unchanged.
  - Standard root publication now validates the standard `ifFalse`, `if`
    and `else` behaviors alongside `ifTrue`.
  - Adopt the two proven `PROVEN_HANDLER_RETURN` sites of the new
    `IsCanonicalBooleanFalseClass` operation into the BCI PE topology
    baseline. Move the I092 branch-profile fixture to an unproven callback
    so it keeps exercising the I092 branches.

## 0.3.347-SNAPSHOT

- `I096` (#884): PLAT057-B lexical inlining of ordinary C0/C1 Closure calls
  (slices A and B1), entered only behind the run-time selection guard.
  - I096-A: a call whose receiver is an immediate Closure literal runs the
    literal's body inline in the caller root in NORMAL mode, after one
    ordinary `call` selection proves the canonical standard selection of the
    literal's own plan target with an unobservable ReturnHome; any other
    selection takes the prepared physical fallback without re-evaluating
    operands. DEBUG keeps the physical callee root.
  - I096-B1: a direct call through a binding whose single Closure-literal
    creation precedes it in the same Closure root reuses the same guarded
    region. The binding is read once and never treated as a constant; a
    reassigned, removed, shadowed or overridden value takes the fallback,
    and another instance of the same definition runs with its own captures,
    ReturnHome and fresh Context. Module roots emit no candidate regions.
  - Default/rest parameters, spread, C2+, bodies creating Closures, and
    non-direct sites keep the unchanged call topology.
  - Adopt the two proven `PROVEN_HANDLER_RETURN` I096-A operations into the
    BCI PE topology baseline; keep PERF026/PERF042 fixtures on their
    intended non-B1 shapes.

## 0.3.346-SNAPSHOT

- `I098` (#886): implement D201: `whileTrue` returns its last normal body
  result, and a new distinct standard `while` selector returns `false`.
  - `whileTrue` returns the exact result of the last normally completed body
    activation (canonical `null` for zero iterations), preserving identity and
    never awaiting or adopting a returned Future.
  - Install `Object.while`, which runs the same pre-test loop and returns the
    final canonical `false` condition result.
  - Share one loop algorithm across the direct, structured-dispatch and
    inline-literal paths; the selected native body decides the return
    policy, and `while` releases each body result before the next condition.
  - Update Object publication, native-boundary and BCI PE topology baselines,
    and add Protos-native and Java conformance coverage for both selectors.

## 0.3.345-SNAPSHOT

- `I097` (#885): implement the D200 receiver-preserving Boolean messages
  `if(block)` and `else(block)` on the standard Object root.
  - `true.if` / `false.else` invoke the selected callback once with zero
    arguments; every case returns the original canonical receiver and
    discards the callback's normal result without validation or awaiting.
  - Route both selectors through the existing structured Boolean callback
    dispatch (C-prime inside Tasks), with native bodies for synchronous
    invocation; `ifTrue`, `ifFalse`, `ifTrueIfFalse`, `and`, `or` and `not`
    are unchanged.
  - Extend Object publication, native-boundary and structured-send guards,
    and add Protos-native conformance coverage.

## 0.3.344-SNAPSHOT

- `PERF043` (#877): fold provable canonical Integer additions during
  Bytecode lowering without changing ordinary overridable message semantics.
  - Fold literal and nested constant Integer additions while retaining
    source-level CallTag observability.
  - Propagate proven straight-line scalar-local values and retain the
    materialized-activation fallback for debugger observation and mutation.
  - Preserve canonical numeric selection, nonconstant operands, exact
    arithmetic, overflow behavior and generic D013 dispatch.
  - Extend regression coverage for folded bytecode, dynamic arithmetic,
    observable scopes and debugger mutation.
  - Integrate with I095 canonical Boolean callback inlining.

## 0.3.343-SNAPSHOT

- `I095` (#878) / `PERF041` (#863): checkpoint experimental
  canonical Boolean `ifTrue` control lowering and Closure callback inlining.
  - Introduce compiler-side canonical Boolean control specialization,
    constant folding and direct emission of eligible literal callback bodies.
  - Preserve ordinary overridable message semantics, evaluation order,
    selected-only invocation and the established B-prime fallback.
  - Extend NORMAL-mode inline lowering to callback-owned scalar bindings
    and proven direct-parent lexical captures, including reads and writes.
  - Preserve lexical ownership, shadowing and D179 presence semantics;
    retain generic execution wherever the required proof is unavailable.
  - Preserve DEBUG behavior and project virtual inline callback bindings
    through Truffle debugger scopes without requiring a physical callback
    activation in the NORMAL path.
  - Extend regression coverage for semantic behavior, bytecode topology,
    captured bindings, debugger scopes and DAP integration.
  - Reconcile TEST009-C Bytecode API and TEST009-D generated-dispatch
    PE-safety baselines with the validated implementation.
  - Leave I095 open pending investigation of general Closure inlining,
    activation elimination and matched graph/performance evidence.

## 0.3.342-SNAPSHOT

- `PERF040` (#862): specialize canonical primitive numeric arithmetic without
  changing D013 message-selection semantics.
  - Split Integer `+`, `-`, and `*` into independent primitive signed-64 and
    overflow-generalized specializations, preserving exact large results and
    small arithmetic after overflow.
  - Separate Float `double/double` and `double/long` specializations for `+`,
    `-`, `*`, and `/`; retain the primitive Integer/Float path and IEEE 754
    binary64 division, NaN, infinity, and signed-zero semantics.
  - Preserve the exact canonical Closure, Prelude, Context and Assumption guards,
    primitive-carrier propagation, and generic D013 fallbacks for other values.
  - Extend Java regression coverage for overflow, polymorphic sites, mixed
    operand orders, Fraction/BigInteger conversion, and exceptional Float values.

## 0.3.341-SNAPSHOT

- `I091` (#869): strengthen PLAT056 numeric boundary conformance and
  representation-neutral structural behavior.
  - Add Java acceptance coverage for primitive Call1/Send0 propagation and
    deferred isolated-P graph population, including cycles, aliases,
    collection contents, identity hashes and mutation states.
  - Add ten Protos conformance cases for direct numeric call/send results,
    overflow, Float signed zero and generic fallback.
  - Include the new numeric conformance cases in Native Image validation.
  - Extend F2 static checks for direct Call1 and Send0, and reconcile the
    production numeric dependency census.
  - Reject inherited structural Object.close() and Object.freeze() on
    semantic Number values of every family, independently of Java
    representation; preserve ordinary-object behavior.
  - Record the structural Number contract in specification revision 0.1.456.
  - Preserve I090 numeric-family semantics and PERF040 measurement ownership.

## 0.3.340-SNAPSHOT

- `I091` (#869): advance PLAT056 primitive-first numeric execution,
  representation boundaries and isolated graph transfer.
  - Extend signed-64 Integer and binary64 Float carrier propagation through
    frame-native literals, direct Closure calls and selected sends, while
    retaining observable guest-value materialization and generic fallback.
  - Consolidate numeric admission and structural boundaries for BigInteger,
    Fraction and Complex in Object, Actor and Package Tool integration.
  - Normalize PLAT051 inert Integer payloads to signed-64 host scalars where
    possible; retain exact arbitrary-precision values when necessary.
  - Repair isolated P transfers of Array, Bytes, Map and IdentityMap graphs,
    including destination identity hashes, delegation, shared references,
    cyclic structures and deferred population before mutation-state publication.
  - Initialize the parallel executor and domain registry lazily so ordinary
    programs do not allocate unused P infrastructure.
  - Preserve D013 dispatch authority, numeric isolation and explicit host
    interoperability requirements. I091 remains open for final architecture
    review and revision-pinned closure evidence.

## 0.3.339-SNAPSHOT

- `I091` (#869): consolidate exact Integer boundaries and retire redundant BigInteger-based test infrastructure.
  - Update exact Integer representation and conversion paths across numeric rounding,
    detached/semantic transfer, foreign and polyglot adapters, and bytecode lowering.
  - Normalize guest Integer expectations to exact decimal text where appropriate,
    retaining Java BigInteger oracles for arbitrary precision and typed host interop.
  - Remove the obsolete `ProtosTestIntegers` fixture and add semantic-transfer
    payload coverage; simplify redundant test wrappers and bounded integer helpers.

## 0.3.338-SNAPSHOT

- `I090` (#868): I090-C1 introduces the D197-C canonical Core `Complex`
  numeric family with exact real and imaginary components.
  - Add `Complex.protos` as a concrete `Number` child and a Prelude binding;
    `ProtosStandardComplexProtocol` installs only the trusted, exact-owner
    `Complex.recognizes` method. No public constructor, literal, component
    selector, arithmetic, or ordering is added.
  - `ProtosComplexNumericSupport` mints frozen sealed values authenticated by
    a private family token, keeping Integer, Fraction, and Float components
    in their original semantic families. An exact Integer-zero imaginary
    component collapses to the real value; Float signed zero remains Complex.
  - Extend standard numeric equality, normal hashing, family-sensitive
    identity and identity hashing to Complex. Mathematical equality stays exact
    across real numeric families, a mathematically zero imaginary component
    hashes like the real value, and numeric ordering rejects Complex with a
    standard Protos Error.
  - Actor and isolated `P` transfers carry only inert component descriptors,
    preserve aliases and Float signed-zero bits, and rematerialize BigInteger,
    Fraction, and Complex with the destination Prelude. Ordinary primitive
    Integer/Float paths retain their existing representation.
  - Extend Core conformance, architecture audits and JUnit regression coverage,
    including real Map/IdentityMap operations and Actor/P transfer boundaries.

## 0.3.337-SNAPSHOT

- `I090` (#868): I090-B implements the D197-C `Fraction` family and exact
  rational arithmetic.
  - New Core source `Fraction.protos` declares `Fraction` under `Number`, with
    source-backed `+`, `-`, `*`, `/` and `negated`. `ProtosStandardFractionProtocol`
    installs the trusted `Fraction.recognizes` and the private
    `_coreFractionKernel` (`numerator`, `denominator`, `normalize`, `toFloat`).
    `Fraction` is a Prelude binding; there is no public constructor.
  - A Fraction is a frozen `ProtosSealedValue` of a private numeric family token
    whose state is its canonical Integer numerator and denominator.
    `ProtosNumericValueSupport` owns exact normalization (gcd, positive
    denominator, Integer demotion), recognition, identity and copying. An
    ordinary object delegating to `Fraction` is never numeric.
  - `Integer / Integer` returns the canonical exact quotient (an Integer or a
    Fraction) instead of Float (`EXACT_DIVIDE`). The compact Bytecode path keeps
    integral signed-64 quotients primitive and falls back otherwise, including
    `Long.MIN_VALUE / -1`. Integer with a Fraction argument computes the exact
    result natively, because a native method cannot invoke guest code inside a
    Task.
  - Float arithmetic and `Float(fraction)` round the whole exact quotient once.
    Equality, ordering and normal hash compare Fraction exactly against Integer
    and against a Float's exact binary64 rational. `===` and the identity hash
    are by canonical value and family.
  - Actor and P transfer carry a Fraction as an inert descriptor and
    rematerialize it with the destination Prelude's `Fraction`. Other sealed
    values remain non-transferable.
  - `std:toml` and its official-suite harness use `Float(a / b)` explicitly for
    decimal float values. Structured logging scalars stay Integer and Float.

## 0.3.336-SNAPSHOT

- `PERF032` (#831): explicit NORMAL and DEBUG execution modes replace the
  experimental `protos.perf032.headerlessLiteralOne` selector.
  - The mode is fixed for the life of a RuntimeHost and its Contexts.
    `ProtosPolyglotRuntimeHost.openDebug()` (and `protos debug`) selects DEBUG,
    every other host is NORMAL, and `isDebugMode()` reports it. A standard
    embedding selects DEBUG with the experimental `protos.Debug` Context option,
    which starts no DAP.
  - In NORMAL, the host entry of the proven parameterless literal-one Closure
    uses an empty argument array. The choice is fixed when the host executable is
    prepared and no longer consults the tag tree or any debugger state. DEBUG
    always uses the ordinary `[closure, caller]` header, so provenance exists from
    the first call.
  - A debugger scope request on a NORMAL headerless frame fails explicitly instead
    of reporting no scope. Creating a Truffle `DebuggerSession` is framework-owned
    and is not intercepted.

## 0.3.335-SNAPSHOT

- `I090` (#868): I090-A implements the D197-C public Integer hierarchy
  `Number → Integer → SmallInteger | BigInteger`.
  - New Core sources `SmallInteger.protos` and `BigInteger.protos` declare empty
    prototypes delegating to `Integer`. They are published as Prelude bindings,
    and bootstrap validates their exact parents. Common arithmetic, `call` and the
    canonical method home stay on `Integer`, so `SmallInteger(x)` and
    `BigInteger(x)` signal Error.
  - Signed-64 values delegate to `SmallInteger` and every other Integer delegates
    to `BigInteger`. Large values (including transfers between Preludes and IP bits
    beyond 64 bits from NIO) are minted with the owning Prelude's `BigInteger`.
    Normalization, primitive `long` carriers and the exact arithmetic kernels are
    unchanged.
  - `SmallInteger.recognizes` and `BigInteger.recognizes` are trusted native
    recognizers that use exact-owner receiver and arity checks, as
    `Integer.recognizes` does.
  - Guarded D013 Integer selection checks the receiver's exact concrete-family
    parent. It admits only selections that are identical for both families, so one
    cached guard still serves every Integer receiver while family-local slots such
    as `recognizes` stay on generic lookup.
  - Add `integer/family-boundaries` conformance coverage (signed-64 boundaries,
    promotion/demotion, conversion, identity and hash, lookalikes, and one call site
    alternating families), and update the existing Protos and JUnit expectations
    for the new parents.

## 0.3.334-SNAPSHOT

- `I093` (#873): I093-A moves IpAddress/IpEndpoint construction, equality and hash
  to Protos source and adds an exact private octet codec.
  - The Core `IpAddress` and `IpEndpoint` prototypes now define `call`, `==` and
    `hash` as source closures. Construction requires the exact canonical receiver,
    validates with exact Integer arithmetic, and freezes an ordinary two-slot
    instance. Equality and hash keep their contracts: an unrecognized receiver
    signals Error, an unrecognized argument compares `false`, and the hashes are
    exactly `bits * 31 + version` and `addressHash * 31 + port`. The closures reach
    the canonical prototypes through private bootstrap anchors that are not
    Prelude bindings.
  - `ProtosStandardIpAddressProtocol` and `ProtosStandardIpEndpointProtocol` keep
    only the native `recognizes` bridge, which inspects internal state without
    invoking guest behavior. `install` moves the source equality closure to `==`,
    as Core Integer does for `%`. The native Core closures drop from 138 to 132.
  - `std:network/IpAddresses` gains a private `_ipOctets` codec (4/16 network-order
    Integer octets, all 128 bits exact). `v4`, `v6` and `format` use it; the
    exported surface is still exactly `IpAddress`, `v4`, `v6`, `parse` and `format`.
  - Refresh the generated-bytecode BCI baseline (157 new proven sites, 92 stale
    entries removed; no risky or unknown transitions).
  - Add conformance coverage for range boundaries up to 2^128-1, lookalikes that
    must not be recognized and must trigger no callbacks, hand-built exact shapes,
    exact hashes and Map keys. Add library coverage for text round trips beyond
    signed 64 bits and fixed-width leading zeros, and JUnit coverage for
    source-backed selectors and private anchor placement.

## 0.3.333-SNAPSHOT

- `I092` (#870): keep the unobserved generic fallback of native canonical
  `Boolean.ifTrue` sites out of the compiled Boolean-only path.
  - Add a per-site `CanonicalBooleanMissProfile` to the four
    `TryCompactCanonicalBoolean*` operations: the first non-canonical receiver
    invalidates once and marks the site generic; later misses continue without
    further invalidation, and the uncached interpreter starts generic.
  - Isolate the ordinary `call` preparation used when compact B-prime admission
    fails behind a `@TruffleBoundary`; B-prime admission stays compilable.
  - Add focal coverage for a dynamic-receiver site alternating canonical and
    overriding receivers, and for single evaluation of an unselected producer.

## 0.3.332-SNAPSHOT

- `PERF042` (#871): reuse the fused direct Closure Call0/Call1 target across fresh
  instances of one definition.
  - Add a `definitionSource` tier to `TryDirectCallZero`/`TryDirectCallOne` after the
    receiver-identity PIC, keyed by Closure definition, prepared plan template,
    Context-projection requirement and entered Context, so the cached target is exactly the
    one the ordinary path derives for the current instance. The current Closure always
    enters the compact Call0/Call1 frame, and the ordinary `call` selection and
    unobservable ReturnHome are re-proved on every hit.
  - Derive a target only after the instance's `call` selection and ReturnHome are proved
    and its plan is already prepared; a deferred plan is never rematerialized and an
    overridden `call` never reaches plan or projection work.
  - Overridden, deferred or ReturnHome-observable instances miss through `rejectedSource`,
    and `generic` no longer replaces the cached tiers, so a transient rejection keeps the
    existing entries.
  - Add focal coverage for structural installation and persistence of the tier at real
    call sites, distinct plans of one definition, foreign-Context projection, overridden
    deferred Closures and transient rejections.

## 0.3.331-SNAPSHOT

- `I091` (#869): contain exact Integer handling at host boundaries and keep large positions,
  hashes and indices exact.
  - Route host-boundary Integer checks through `ProtosNumericValueSupport` range and exact
    conversion helpers instead of `ProtosIntegerValue` type tests; move
    `ProtosBinary64Rounding` into `runtime` and simplify numeric conversion, relations and
    literals.
  - `ProtosNumericHashKey` keeps the exact payload of a large Integer hash, so Map keys
    beyond the signed-64 range are never truncated to their low bits.
  - `ProtosFileFlow` requires its owning Core Prelude at construction and hands its
    backend only host-representable positions and sizes; Polyglot array indices outside
    the non-negative signed-64 domain are rejected before the foreign value is touched.
  - Extend focal coverage for large Map hashes, exact file positions and exact Polyglot
    arguments and indices.

## 0.3.330-SNAPSHOT

- `I092` (#870): execute canonical `Boolean.ifTrue` natively without lookup or
  intermediate activation.
  - Lower an ordinary `ifTrue` send natively only after the standard root `Object` is
    published frozen (D049); the canonical `true`/`false` selection is then fixed, so the
    native candidate is two identity tests in cached and uncached execution, with no
    lookup, `Assumption` or selection cache. Publication now validates the root's
    `ifTrue` and `call` behaviors.
  - Invoke the selected callback from the send's own caller instead of a synthetic
    standard `ifTrue` activation, which was unobservable; dynamic callbacks keep ordinary
    D013 `call` selection, overrides and Errors, admitted literals keep the single B-prime
    region, and `false` still evaluates its argument but neither invokes the callback
    nor validates its `call`.
  - Remove the native carrier, its completion `TryFinally` and the guarded selection
    record, share candidate emission across composed, default-parameter, spread and
    frame-native sites, and extend focal coverage.

## 0.3.329-SNAPSHOT

- `I091` (#869): complete PLAT056 Candidate C Integer representation and `BigInteger` containment.
  - `ProtosIntegerValue` now holds only a signed-64 `long`; exact Integers outside that
    range are `ProtosLargeIntegerValue`, a frozen ordinary object delegating to its minting
    Prelude's Integer prototype. Every exact result is normalized in both directions, large
    literals are minted per evaluating Prelude, and Actor/P/detached transfers
    rematerialize large values with the destination prototype. Observable Integer
    semantics are unchanged; a distinct D197 `BigInteger` family remains with I090.
  - Centralize exact Integer arithmetic, ordering, identity, hashing, decimal text and
    unsigned codecs in `ProtosNumericValueSupport`; reflective object primitives, mutation
    targets, composition and Actor bootstrap treat large Integers exactly as Integers.
  - Replace `BigInteger` range constants and round trips in fixed-width interop with exact
    bit-length checks and `long` storage; keep binary64 rounding significands, literal
    admission, Float projections, numeric relations, Regex payload validation, SemVer
    prerelease checks and outbound foreign Integers on primitive paths.
  - Reduce the I091 census from 161 to 121 `BigInteger` and from 203 to 90
    `ProtosIntegerValue` references and migrate the affected tests.

## 0.3.328-SNAPSHOT

- `I091` (#869): implement PLAT056 Candidate C primitive-first numeric execution.
  - Carry small Integer/Float values as primitive `long`/`double` through guarded
    arithmetic, ordering, Integer `/`, frame locals, captures, fused direct-send
    arguments and returns, materializing guest values only at observable boundaries;
    D013 overrides, assumptions and exact fallbacks are preserved.
  - Extend the semantic numeric boundary and move file, byte, text, network, IP,
    collection and tooling consumers off physical Integer dependencies; file backends
    take `long` positions while `File` positions remain exact.
  - Add the internal rich-numeric family identity capability for Candidate C guest
    objects; Core-family isolation transfer remains gated by PLAT051.
  - Reduce the I091 census to 161 `BigInteger` and 203 `ProtosIntegerValue` references
    and add focal carrier, return, boundary and feasibility coverage.

## 0.3.327-SNAPSHOT

- `I091` (#869): contain Java `BigInteger` dependencies behind exact numeric boundaries.
  - Replace arbitrary-precision index, size, octet, port, position, and count plumbing
    in Array, Bytes, byte/file/text I/O, filesystem and network backends, Actor/P
    transfers, and tooling with compact `int`/`long` or exact `ProtosIntegerValue`
    carriers; file and byte-flow positions remain exact Protos Integers.
  - Return identity hashes as `long` with representation-independent Integer hashing,
    and add an exact unsigned big-endian codec for IP address bits.
  - Add compact paths for mixed Float/Integer equality, ordering and normal hash,
    integral binary64 extraction, correctly rounded Integer division, Integer
    binary32/binary64 interop projection, and integer literal parsing.
  - Keep `BigInteger` only for genuine arbitrary-precision arithmetic and rounding,
    Truffle interop, the public foreign SPI, PLAT051 transfer payloads, and SemVer DTOs.
  - Reduce the I091 census from 450 to 165 references and extend focal coverage.

## 0.3.326-SNAPSHOT

- `I092` (#870): implement compact canonical Boolean control.
  - Defer rich activation materialization when creating eligible Closure literals.
  - Introduce guarded canonical `ifTrue` selection without mandatory generic outer NativeCall preparation.
  - Prepare admitted inline callbacks through compact state while preserving ordinary fallback semantics.
  - Preserve a single PLAT044 B-prime inline region and source instrumentation.
  - Extend Boolean and structural regression coverage, including deferred lexical-capture compatibility.

## 0.3.325-SNAPSHOT

- `I089-A` (#867): implement D196 B1 mixed Integer/Float arithmetic.
  - Promote the Integer operand to IEEE binary64 using the standard
    `Float(Integer)` roundTiesToEven conversion before `+`, `-`, `*`, or `/`,
    preserving original operand order and returning Float in both directions.
  - Preserve exact unbounded Integer/Integer arithmetic and the existing
    correctly rounded exact-quotient Integer/Integer `/`; keep `div`,
    `mod`, and `%` Integer-only and ordinary guarded dispatch intact.
  - Extend Java and Protos conformance tests for mixed operations,
    integer rounding boundaries, infinities, NaN, signed zero, and errors.

## 0.3.324-SNAPSHOT

- `PERF038-H` (#852): specialize fixed-arity source calls and sends.
  - Introduce zero- and one-argument direct call/send operations and
    straight-line source-call paths to reduce generic call preparation.
  - Preserve generic invocation, control-transfer, return-home and
    continuation handling for cases requiring those capabilities.
  - Extend call, argument, and primitive-protocol regression coverage.
  - Update the generated-bytecode BCI partial-evaluation baseline.

## 0.3.323-SNAPSHOT

- `PERF037-E` (#851): reduce ordinary member-read and terminal Sequence overhead
  while preserving object lookup, Closure binding, and control-transfer semantics.
  - Return eligible terminal literals, lookups, intrinsics, member reads,
    identity expressions, and Closure values directly, without a redundant
    `sequenceResult` store/load; register nested Closure roots before opening
    the return operation and preserve source sections and statement tags.
  - Discard unused Sequence-prefix results without intermediate result stores
    for direct expressions, and share composed-call scratch locals across the
    complete Sequence while retaining ordered evaluation and error propagation.
  - Encode member names as Bytecode DSL constant operands in ordinary,
    root-level, and inline-callback reads instead of loading the name as a
    runtime expression operand.
  - Admit a lazy, one-way non-Closure continuity assumption for selected
    physical `SlotCell` locations. An assignment of a Closure invalidates the
    assumption before publishing the value; subsequent data assignments do
    not renew it. The current value is always loaded from the authoritative
    slot location.
  - Specialize own and shared-inherited data-member reads in all four
    execution lanes. Keep generic reads for fresh receiver-bound Closure
    extraction, parent-chain changes, local shadowing, and unsupported cases.
  - Add regressions for direct returns, Sequence evaluation and source tags,
    constant member operands, independent slot cells, data-to-Closure
    transitions, fresh extracted Closures, and inherited shadowing.

## 0.3.322-SNAPSHOT

- `PERF038-F` (#852): residual primitive-method-call path.
  - The inherited-provenance guarded send is split by the observed prelude
    relation (the root Closure has no prelude of its own, or has its
    caller's), decided by fixed full-header slots and passing the caller
    slot directly; minimal headers keep the general provenance query, and
    a different prelude or an explicit Task still materializes.
  - Root-level argument presence, argument reads and the upper arity bound
    of a compact frame are decided by the frame-argument length alone,
    without the minimal-layout branch; arity Errors keep the unchanged
    activation path.
  - `IsContinuation` profiles the exact result class (limit 3), replacing
    the suspension type tests of an ordinary result with one class
    comparison; the generic form and every resumption path are unchanged.
    The generated-bytecode BCI baseline records the new instruction length.

## 0.3.321-SNAPSHOT

- `PERF037-D` (#851): owner-absence specialization and presence continuity
  in the captured owner-frame cache.
  - A cleared owner binding on a valid cache hit records the site's
    no-selection profile, shared with the profiled selection, so a never
    observed absence compiles as a deoptimization instead of a merged
    result.
  - When the cache records an owner whose binding is physically PRESENT,
    it admits the owner layout's existing present-continuity token for
    that ordinal, checked valid before and after the presence check. A
    hit then skips the physical presence check while the token is valid.
    Removal invalidates the token before clearing and it is never
    renewed, so any later hit checks presence physically; a binding
    absent at recording admits no token. The value is still read by
    `LoadLocalMaterialized`, and owner identity and the installed-authority
    assumption are still checked on every hit.

## 0.3.320-SNAPSHOT

- `PERF038-E` (#852): residual monomorphic call-graph infrastructure.
  - The guarded ordinary send specializes on its caller operand: a
    published activation, a compact caller whose callee inherits all of its
    provenance (passed without materialization, under a guard re-evaluated
    on every execution), or another compact caller, which is materialized
    exactly as before. A stable inheriting site compiles no
    materialization fallback.
  - Closure preludes are read through a nullable runtime accessor on
    provenance and frame-argument paths, so no `Optional` is built; the
    public `prelude()` API is unchanged.
  - The guarded send target records, at specialization, whether the
    Closure owns its return home and whether each invocation needs a fresh
    physical home, so preparation no longer re-reads the execution plan or
    the captured home; the guarded compact header is written without
    re-validating values the specialization already established.
  - `FinishClosureCall` has a separate form for ordinary calls without a
    materialized owned return home, which finishes without the lifecycle
    test; non-local returns and completion are unchanged for other calls.
  - The root-level compact argument read no longer repeats the layout test
    its guard already proved.

## 0.3.319-SNAPSHOT

- `PERF037-D` (#851): profiled captured owner-frame selection.
  - The root-level compact captured read profiles, per read site, the
    owner scope's representation (materialized, deferred, deferred with a
    published Context) and its no-selection outcome. A branch a site has
    never taken compiles as a deoptimization instead of a merged path;
    each profile invalidates at most once and is never cleared. The owner,
    its authority, retained frame and PRESENT state are still read from
    the current invocation, so captured-read semantics are unchanged.
  - A miss or retired PERF038-D owner-frame cache takes this profiled
    selection instead of the unprofiled generic one; the uncached
    interpreter uses a pre-seen profile and never invalidates.

## 0.3.318-SNAPSHOT

- `PERF038-D` (#852): primitive-method-call graph convergence, first unit.
  - A root-level send takes a lazy caller reference instead of the root's
    materialized activation. A guarded ordinary send from a still-compact
    root whose activation would inherit all of its provenance from its own
    caller (prelude, actor module state, module key, execution domain, Task
    or dynamic-control state) passes that caller to the callee header, so
    the root no longer materializes its activation merely to be a caller.
    Every other send specialization still receives the exact activation.
  - A root-level captured materialized read keeps a monomorphic cache of its
    owner's frame authority and retained frame, guarded by owner identity
    and a lazily created installation assumption that every authority
    replacement invalidates. Nearer-scope absence, owner presence and the
    value are still checked on every access (D179 C0, BUG018); a mismatch
    retires the cache and resumes the exact generic selection.
  - A guarded ordinary send decides at specialization whether its selected
    Closure's invocations share one return home (captured, or the
    unobservable marker of a return-home-unobservable plan), and builds its
    compact call carrier from the header values it has just written instead
    of re-validating the frame arguments.
  - Observable semantics are unchanged. The PE guard baselines record the
    new proven-safe accessor and dispatch sites.

## 0.3.317-SNAPSHOT

- `BUG021-A` (#859): Native Test Tool progress and discovery failures.
  - Logical Case discovery no longer lets a host exception or Error (for
    example a Native Image reflection or resource failure) escape into the
    Test Tool root task, which previously left that task non-terminal and
    made Process termination wait indefinitely. Every discovery failure is
    reported on Test Tool stderr as
    `Test tool discovery error: <corpus>:<source>: <cause>` and the Tool
    exits non-zero. Successful discovery is unchanged.
  - `dist/validate_native.py` runs the complete Native Test Tool under a
    supervised runner that streams its output live while retaining the
    bounded transcript, distinguishes exit, signal, timeout and transcript
    limit (`NATIVE_DIST_FULL_TEST_TOOL_TERMINATION`), and imposes no
    timeout on the admission run.

## 0.3.316-SNAPSHOT

- `PERF037-C` (#851): residual captured-lexical graph overhead.
  - Root-level captured reads take the binding name and lexical depth as
    constant operands, so the nearer-scope walk has a constant length.
  - A compact captured read retains a structural proof that its semantically
    nearer scopes do not hold the name: each nearer scope must still store
    the frame layout recorded at specialization, the name must be outside
    that layout, and the layout's lazily created one-way token must show
    that no dynamic binding was ever created through it. The stable path
    no longer asks each nearer scope for membership. Dynamic creation
    invalidates the token before the binding exists; a mismatch retires the
    proof and resumes the exact generic walk. Owner presence and the value
    are still read on every access (BUG018). Observable semantics are
    unchanged.
  - The LocalRange, operand, accessor and generated-bytecode BCI guard
    baselines are refreshed to the current PERF038-B/C and PERF037-C
    topology; every adopted site is classified proven or not PE-reachable.

## 0.3.315-SNAPSHOT

- `PERF038-C` (#852): residual compact method-call graph overhead.
  - Root-level current-activation, parameter, root-local and captured-read
    operations split their compact path and their published-activation path
    into separate re-evaluated specializations, so a site that only sees one
    frame state compiles no materialization, arity-Error or lexical-authority
    path for the other.
  - The compact captured-read miss materializes and looks up behind one
    boundary instead of two.
  - Both Bytecode roots compile guest-exception interception only after a
    guest exception has first crossed them; the first crossing deoptimizes
    once. Observable semantics are unchanged.

## 0.3.314-SNAPSHOT

- `PERF037-B` (#851): object-slot-read graph overhead.
  - Member reads and statically proven captured reads at Closure-root level
    no longer take the current activation as an operand: while the call is
    still compact they use the invoked Closure's captured chain and prelude
    directly, and only fallbacks and Errors materialize the exact activation.
  - The guarded member-read PIC caches the selected binding's stable
    location: a binding is promoted in place, inside the same single
    map-backed authority, to a cell the first time a PIC selects it, and a
    hit loads the current value without a String-keyed lookup or
    `Optional`. Assignment writes through the cell; removal and recreation
    invalidate the selection. Bindings no PIC selects never get a cell.
  - Fresh receiver-bound Closure extraction on a PIC hit stays behind a
    branch profile, so a data-only read site does not compile it.
    Observable semantics are unchanged.

## 0.3.313-SNAPSHOT

- `PERF038-B` (#852): compact method-call graph specialization.
  - Root-level frame operations tell a compact source call from a
    materialized one by frame argument 0 alone; the full compact-header
    validation stays at carrier construction and materialization.
  - Compact supplied-argument count and reads select the header layout by
    array length instead of re-deriving the minimal direct layout.
  - `BindClosureFrameParameter` is split into a guarded compact slot store and
    an authoritative fallback behind a boundary, so a binding site that only
    sees unobserved compact frames no longer compiles activation
    materialization or lexical-authority work. Observable semantics are
    unchanged.

## 0.3.312-SNAPSHOT

- `DIST015-FIX`: Native Image includes the ICU4J Unicode data.
  - `std:regex/Regex` reads ICU4J property data when it loads; the Native
    Image now embeds `com/ibm/icu/impl/data/icudata/*.icu` and
    `ICUConfig.properties`, so importing `std:regex` no longer fails in the
    Native distribution (the full Native Test Tool run hung in discovery).
- `I088-A` (#847): Java standard-library foreign provider namespace.
  - The I085-B external Java provider fixture now owns the `java:` scheme
    instead of `jdk:`: `import("java:math")` and `import("java:date")`.
  - `jdk:` is not an alias for it and stays free for a future,
    independently defined provider.
  - The fixture is renamed to `plugins/java` / `JavaProvider` (provider id
    `i085b-java`), with `java-math.protos` and `java-date.protos`. Module
    catalogue, conversions, errors, and authority are unchanged.

## 0.3.311-SNAPSHOT

- `PERF036-A`: pay-as-you-grow scalar lowering for local assignment.
  - A bare assignment whose destination is statically `Resolved` to an
    already-established binding of the current parameterless Closure root,
    and whose right-hand side is a literal or an established scalar read, is
    admitted to the PERF034 scalar-local lane.
  - While the call stays compact it writes the builtin local directly with
    `StoreLocal` and yields the exact right-hand-side value, without
    destination resolution, mutation-target temporaries, or invocation
    staging; an observed activation takes the unchanged authoritative
    resolved assignment.
  - Unadmitted assignments (explicit targets, captured, outer or dynamic
    destinations, effectful right-hand sides) keep their existing lowering.
    No observable semantics change.

## 0.3.310-SNAPSHOT

- `PERF032-G7` (#831): minimal direct Closure call headers.
  - A direct source-backed Closure call with zero supplied arguments passes
    only the frame arguments it needs instead of the five-slot compact
    header: `[closure, caller]` without an explicit Task and with the
    unobservable return-home marker, `[closure, caller, returnHome]` with a
    physical or captured home, `[closure, task, caller]` and
    `[closure, task, caller, returnHome]` for Task-owned entries.
  - The omitted marker is the identity-stable shared singleton and is
    reconstructed, never recomputed; physical homes and explicit Tasks keep
    their exact identities.
  - Lazy activation materialization, NodeLibrary scope observation, Task
    inheritance and PERF034-D scalar-local discrimination support the new
    layouts; calls with supplied arguments and immediate-method calls keep
    the full header. No observable semantics change.

## 0.3.309-SNAPSHOT

- `LM010-D` (#493): integrated LSP acceptance over the real JSON-RPC transport.
  - Add `ProtosLanguageServerIntegratedEditorTest`, which connects a standard
    LSP4J client to `ProtosLanguageServerStdio` over in-process pipes and
    exercises the advertised capabilities, Hover, Completion, and literal-Closure
    Signature Help (including `(`/`,` trigger contexts and UTF-16 positions)
    together with full-sync editing, document authority, diagnostics,
    definition, and the shutdown/exit lifecycle.
  - Test-only; no language-server behavior changes.

## 0.3.308-SNAPSHOT

- `PERF034-E` (#845): reduce intermediate state in the PERF034-C scalar-local
  lowering.
  - A scalar creation from a literal passes the literal directly as the
    operand of both the compact `StoreLocal` and the authoritative
    `CreateCurrentFrameLocal`, without a `createValue` temporary.
  - An admitted scalar root whose final expression reads one of its own
    bindings returns that read directly from each lane instead of through
    the `sequenceResult` local; the read keeps its own source section and
    shared Statement/Expression tag.
  - Scalar admission also accepts a creation whose value is a `Resolved`
    read of an already-established binding of the same root, so simple
    alias chains (`a: 1`, `b: a`) stay in the scalar lane.
  - Every creation and read, including an alias right-hand side, still
    re-checks `IsCompactLocalFrame` and keeps the D179-aware authoritative
    fallback; no observable semantics change.

## 0.3.307-SNAPSHOT

- `I087-2` (#841, PLAT055/#844): Java hosts supply Protos application
  modules to a standard Polyglot Context through the new public
  `ProtosEmbeddedModules.install(Context, Map<String, String>)`.
  - Each entry maps an exact `app:` specifier, which is also its canonical
    ModuleKey, to in-memory source characters; no relative lookup, alias,
    normalization, or filesystem/classpath search takes place, and `std:`
    and the `foreign:` key domain are unaffected.
  - The catalog is validated and defensively copied before publication,
    is immutable and Context-local, and may be installed once, after the
    Context is built and before the Process bootstrap begins; the
    installation is atomic with respect to that bootstrap. Invalid
    arguments and late, duplicate, closed-Context, or driver-owned-Context
    installation are host Java errors, and a driver Process cannot be
    bound to a Context that already has a catalog.
  - The embedded Process composes the catalog in front of the
    standard-library resolver, so `import()` and `Actor.spawn()` share the
    ordinary module runtime: Actor-local instances, cache-before-execute,
    cycles, and failed-initialization retry are unchanged, and modules are
    compiled lazily on first import. Host evaluations never acquire a
    catalog identity.
  - The catalog grants no Filesystem, Network, thread, or host authority.
    Without entries the previous resolver path is kept unchanged.
  - The portable embedding smoke runs the API from an external application
    using only the distributed JAR. Native Image embedding hosts are out of
    scope; the Native CLI has no Java embedding.
  - Focal tests, the integrated `make test` suite, and the portable
    embedding smoke passed.

## 0.3.306-SNAPSHOT

- `PERF034-D` (#845): select the PERF034-C scalar-local lane through a
  minimal compact/materialized discriminator instead of revalidating the
  whole compact source-call ABI header at every local creation and read.
  - `IsCompactLocalFrame` now tests only whether frame argument 0 is still
    the callee Closure or has become the published activation; the full
    ABI validation stays where compact calls are prepared.
  - The state is re-read at every use, so a materialization between two
    instructions still routes creation and reads through the authoritative
    D179-aware path with the same observed activation identity.
  - General ABI checks used by other operations are unchanged.
  - Focal tests and the integrated `make test` suite passed.

## 0.3.305-SNAPSHOT

- `LM012-C1` (#671, D195): add the static `protos lint [--output text|json]
  [--fail-on-warning] [<file>]` CLI/CI command.
  - Read one explicit file (symbolic links to regular files accepted) or
    stdin as strict UTF-8, parse it once through the canonical static
    analysis core, and run the D194 lint rules only on a successful parse;
    an invalid document reports only its parser error.
  - Report origin, rule code, severity, message, and zero-based UTF-16
    range in deterministic order as text or JSON v1 on stdout; usage,
    acquisition, and internal failures go to stderr with empty stdout.
  - Exit 0 for valid source, 1 for a parser error or warnings under
    `--fail-on-warning` (severity stays `warning`), 2 for usage errors,
    3 for unreadable or malformed input, and 70 for internal errors.
  - Dispatch lint on the calling thread without the guest carrier,
    Polyglot Context, Process, or any guest execution; never write files.
  - Extract the protocol-neutral `SourcePosition` mapping shared with the
    language server, preserving its existing positions.
  - Focal tests and the integrated `make test` suite passed.

## 0.3.304-SNAPSHOT

- `LM010-C` (#493): add static LSP signature help for calls whose callee
  is a literal Closure, through grouping parentheses only.
  - Advertise `signatureHelpProvider` with `(` and `,` triggers and
    publish one signature with source-text parameter labels, including
    defaults and `...` rest, without types or evaluated defaults.
  - Locate the innermost call argument list and active argument from
    real lexer tokens; nested structures, strings, and comments never
    contribute separators.
  - Validate incomplete argument lists through a transient local repair
    checked against the original tokens and the canonical parser; the
    repaired source is never retained.
  - Never attribute a signature to names, members, `super`, or computed
    callees, and publish nothing instead of a false parameter highlight
    for spreads or excess arguments.
  - Preserve canonical-source authority, open-snapshot freshness, and
    UTF-16 position handling.
  - Focal tests and the integrated `make test` suite passed.

## 0.3.303-SNAPSHOT

- `PERF034-C` (#845): introduce a statically admitted scalar-local
  lowering path for simple parameterless Closures.
  - Use native Bytecode `StoreLocal` and `LoadLocal` operations for
    eligible straight-line local bindings.
  - Preserve the existing authoritative frame-local execution path
    for non-compact activations and non-admitted programs.
  - Retain context-observation, duplicate-creation, and lexical
    binding semantics.
  - Add regression coverage for direct scalar execution,
    duplicate creation, and observed versus unobserved activations.
  - Focal tests and the integrated `make test` suite passed.
  - Compiled-graph and timing improvements remain to be measured.

## 0.3.302-SNAPSHOT

- `PERF035` (#846): separate guarded member-read slot-owner selection
  from the mutable value stored in that slot.
  - Preserve current-value reads and fresh receiver-bound Closure extraction.
  - Keep value-sensitive send/call lookup invalidation, while structural
    slot changes invalidate selection-sensitive assumptions.
  - Add guarded-lookup and executable primitive slot-write regressions.
  - Focal tests and the integrated `make test` suite passed on the
    implementation candidate; compiled Tier 2 stabilization remains
    pending verification in the independent benchmark environment.

## 0.3.301-SNAPSHOT

- `PERF034-B` (#845): isolate cold frame-local creation and
  lexical-read fallbacks behind Truffle boundaries.
  - Preserve compact frame-local creation and read paths.
  - Materialize frames before entering cold fallback helpers.
  - Preserve observed-context, duplicate-creation and D179 semantics.
  - Add regression coverage for compact repeated execution,
    immediate method calls and duplicate creation.
  - Integrated `make test` passed.
  - Structural and timing improvements remain to be measured.

## 0.3.300-SNAPSHOT

- `PERF032-G6` (#831): reconcile the PERF013 shared lexical-root grouping
  regression tests with G6's cold zero-parameter Closure arity-rejection
  roots. The tests now verify exact physical root counts and assert that
  every rejection root belongs to its Closure's original
  `BytecodeRootNodes` group.
  - Six obsolete root-count expectations updated across three test classes.
  - No runtime or language specification changes.
  - Focal tests and the integrated `make test` suite passed.

## 0.3.299-SNAPSHOT

- `PERF032-G6` (#831): a source Closure declaring no parameters no longer
  checks argument count inside its root. Each such definition gets a cold
  arity-rejection root in the same Bytecode group; ordinary source calls
  supplying arguments enter it and signal the unchanged guest argument-count
  Error without evaluating the body. Closures with parameters, defaults, rest,
  and inline callbacks are unchanged.

## 0.3.298-SNAPSHOT

- `I086` / `PLAT054-3E3` (#840, design #838, blocker B011) grants the default
  Filesystem in the standard Polyglot embedding, implementing normative
  specification 0.1.451 (bootstrap Alternative A). The specification is
  unchanged.
  - New `ProtosEmbeddedFilesystemCustody`: provisioned only when the Context
    effectively allows file I/O; the base is the Context working directory
    within the configured provider, and every `open`, `replace`, `remove`
    and `entries` operation goes through `TruffleFile`, so physical,
    restricted, read-only, custom and virtual providers keep their
    restrictions. An unsafe or unrepresentable base aborts the initial
    bootstrap before any guest source runs.
  - Open-or-create re-selects after a concurrent creator; `replace` requires
    an atomic move; writes past EOF fill an explicit zero gap. Unused grants
    hold only the base; open Files are released on Process termination and
    Context close.
  - `ProtosStandardFilesystemProtocol.installOperations` binds the standard
    operations to the RootActor after the bootstrap marker is created.
  - New `ProtosEmbeddedFilesystemTest`; `ProtosEmbeddedNetworkTest` now
    expects `filesystem` exactly when file access is allowed.

## 0.3.297-SNAPSHOT

- `PERF031-I` (#787) removes quadratic repeated-prefix Array growth from Test
  Tool Case discovery and the D185 Case listing. The specification and all
  observable Test Tool behavior are unchanged.
  - New Test Tool-internal `ArrayAccumulator` module (binary-carry,
    order-preserving accumulation over ordinary `Array(...)` construction).
  - `Main.protos` accumulates logical projections, Case metadata, Case
    references and each suite's progress keys through it, and materializes
    and freezes them at the existing points.
  - `CaseSelection.listing` accumulates its `protos.test.cases/v1` entries
    through it; the listing bytes are unchanged.
  - `ProtosTestToolCaseSelectionTest` adds multi-projection listing order
    and accumulator order/identity/independence coverage.
  - No speedup has been measured or is claimed.

## 0.3.296-SNAPSHOT

- `LM010-B` (#493) adds conservative static completion to the
  static-analysis core and the Protos language server. The specification is
  unchanged.
  - New `ProtosStaticCompletion`/`ProtosStaticCompletionResult` and
    `ProtosStaticAnalysisCore.completion(...)`. Candidates are offered only
    at a parser-proven `SurfaceName` read site: Closure parameters whose
    origin is proven by the D110 generation-1 facts at that site, then the
    reserved words `this`, `context`, `true`, `false`, and `null` when the
    real parser accepts that word there. Bare `super`, members, slots,
    captures, prelude, module, and workspace names are never offered.
  - The cursor context uses the real lexer and parser: nothing inside
    comments, String literals, or any token; a typed identifier prefix is
    the exact replacement range; an empty position is analyzed with one
    synthetic identifier that must parse as a read at the cursor.
    Unclassifiable positions are empty.
  - The D110 generation-1 walk is shared through one proof walker;
    Definition, References, and Hover results are unchanged.
  - The language server advertises `completionProvider` without resolve or
    trigger characters and always answers a complete list
    (`isIncomplete=false`), under the same canonical-source authority and
    snapshot-freshness checks as hover.
  - Lexical errors in edited source are now ordinary parse failures:
    `ProtosLexer.LexicalError` exposes its reported offset, and the
    static-analysis parse projects it as an exact empty-span Error
    diagnostic instead of propagating out of `didOpen`/`didChange`.

## 0.3.295-SNAPSHOT

- `LM012-B1` (#671, D194) adds the initial canonical correctness lint
  diagnostics to the static-analysis core and the Protos language server.
  The specification is unchanged.
  - `protos/unreachable-after-nonlocal-return` (Warning, enabled, no
    autofix) reports the suffix of a braced Closure body that follows a
    direct `^` in the same Sequence. Conditional, nested, or indirect
    Closure execution is never inferred.
  - `protos/always-different-fresh-object` (Warning, enabled, no autofix)
    reports `===`/`!==` whose exact right-hand operand, optionally inside
    parentheses, is an object literal; on normal completion the result is
    `false`/`true` respectively.
  - New `ProtosStaticLint` and `ProtosStaticLintDiagnostic` and
    `ProtosStaticAnalysisCore.lint(...)` analyze only successfully parsed
    snapshots. The language server publishes the findings with the rule ID
    as diagnostic code in the existing single per-version publication;
    parser errors replace them and stale snapshots publish nothing.

## 0.3.294-SNAPSHOT

- `I086` / `PLAT054-3E4` provisions the default Network in the standard
  Polyglot embedding (HOST-NET-1 and HOST-NET-2, specification `0.1.450`).
  The specification is unchanged; Filesystem (B011) stays out of scope.
  - The initial module receives the bootstrap-local `network` slot only when
    the Context effectively allows socket access (`Env.isSocketIOAllowed()`);
    otherwise the slot is absent. Guest thread-creation and file-access
    permissions are not consulted, and later host evaluations receive no
    implicit Network.
  - New `ProtosEmbeddedNetworkCustody` is the capability's authority target.
    It opens one NIO host (one host-only poller thread) on the first
    `connectTcp`/`listenTcp`, so a granted but unused Network creates no
    poller, selector, or socket. Process termination and Context
    finalization or disposal close it idempotently, which releases every
    connection, listener, and in-flight acquisition; no poller starts after
    close.
  - `ProtosNioNetworkHost` exposes its backend factory so the embedding can
    create the capability before any poller exists; `provision` behaves as
    before.
  - New `ProtosEmbeddedNetworkTest` (permission matrix, TCP echo and
    half-close, listen/accept through suspendible `Future.value()`, errors,
    cancellation, concurrency, close/cancelling close/fatal termination,
    Context isolation, pay-as-you-grow) and `ProtosEmbeddedNetworkCustodyTest`
    (lazy activation, initialization failure, first use racing close).

## 0.3.293-SNAPSHOT

- `LM010-A` adds static hover to the Protos language server. The
  specification is unchanged; completion and signature help remain deferred
  to LM010.
  - The server advertises `hoverProvider`. Hover answers plain text under the
    same canonical-source authority and snapshot-freshness checks as
    definition, and no hover for a closed, stale, or unparsable document.
  - A reference proven by D110 generation 1 to a Closure parameter shows a
    "Proven binding" section naming that parameter, plus its rest/default
    modifier.
  - Otherwise hover shows only labeled parser-derived "Syntax" facts:
    literal kinds, Closure parameter declarations with the Closure's
    parameter list, and the Closure head. Ranges are exact identifier or
    literal spans, mapped to LSP UTF-16 positions, including non-BMP text and
    CRLF line breaks.
  - New `ProtosStaticHoverTest` and `ProtosLanguageServerHoverTest`;
    `ProtosLanguageServerFoundationTest` now expects the hover capability.

## 0.3.292-SNAPSHOT

- `PERF031-G` removes repeated Package-flavored Core bootstraps from the
  Package Tool execution-plan and Package Run Driver Java test fixtures. The
  specification and observable Protos behavior are unchanged.
  - `ProtosPackageToolProtosTestSupport.executeExternalCaptureFixture`
    reuses one lazily prepared Package-flavored Core Prelude; every invalid
    identity, Git/edge, and capture vector still opens its own hosted
    Process, fresh activation, project-tree backend, and per-root captured
    custodies.
  - `ProtosPackageRunDriverTestSupport.digest` reuses one lazily prepared
    Package-flavored Core Prelude; every template digest still captures its
    real tree and evaluates the bundled `ContentIdentity.digest` in its own
    hosted Process.

## 0.3.291-SNAPSHOT

- `I086` `PLAT054-3E2` implements HOST-FUT-1 (`FUTURES_AND_TASKS.md` §29,
  Suspendible host-initiated RootActor entries, spec 0.1.450) for the standard
  Polyglot embedding: a Closure executed from Java through `Value.execute()`
  may call `value()` on a pending Future and suspend, and `execute()` still
  returns only the final result or propagates only the final failure.
  - An outermost host entry suspends through the existing C-prime machinery:
    the pending observation registers one ordinary Future observer atomically
    with the pending check and yields the same native suspension leaf a Task
    uses, so the exact guest continuation is resumed with no replay and no
    second evaluator. Closures without a compact target (native bodies,
    non-canonical `call` selections) run through the shared C-prime entry root
    with the same behavior.
  - While suspended, the entry thread keeps the RootActor entry (concurrent
    entries are still rejected), dispatches the RootActor's own runnable work,
    and otherwise parks in a Truffle-interruptible region. Process termination
    or Context close ends the wait without resuming guest code, even when
    termination itself terminalizes the observed Future, and releases the
    observer. The observed Future is never cancelled by its observer.
  - A foreign callback during a host entry, including a nested same-thread
    entry, keeps the no-suspension rule: an actual suspension signals a fresh
    `Error`. An unhandled Error after resumption stays fatal to the RootActor.
  - PAY AS YOU GROW: a call that does not suspend creates no Task, RootTask,
    waiter, continuation, or scheduler; the fixed cost is one domain extent
    swap per entry and one result type check.
  - `ProtosHostEntryFutureSuspensionTest` covers resolved, pending, failed, and
    cancelled Futures, no-replay effects, races, `ensure`, non-local returns,
    nested and structured calls, the native generic path, foreign callbacks,
    exclusivity, Context close, Process termination, and Context independence.

## 0.3.290-SNAPSHOT

- `PERF031-F` removes repeated work from Test Tool progress grouping. The
  specification and observable Protos behavior are unchanged, and no speedup
  is claimed without a comparable measurement.
  - `RepositorySuite.progressGroupAssignment` answers a leaf's progress group
    names and each retained Case's group index in one pass: each casePath is
    matched against the conformance descriptors once, and local `Map`s replace
    repeated linear name searches. `progressGroupNames` delegates to it.
    Group order, first-appearance subgroup order, and fail-closed rejection of
    ambiguous descriptors and invalid selectors are preserved.
  - Main collects each suite's retained progress keys during discovery and
    calls the assignment once per suite, instead of refiltering every
    retained Case for each suite and resolving each Case's group again.
  - TOOL011/TOOL004C structural assertions follow the new call; TOOL012 checks
    that the assignment matches `progressGroupNames` and `progressGroupIndex`
    over the complete corpus.
  - Residual: Arrays are still grown by spread expansion, which is quadratic
    in a suite's retained Case count; ordinary `Array` has no append.

## 0.3.289-SNAPSHOT

- `I086` / `PLAT054-3D` completes Actor threading and Context close for the
  standard Polyglot embedding. The specification is unchanged; Filesystem and
  Network grants stay absent pending a design decision.
  - Actor carriers of an embedded Process now run on the new
    `ProtosEmbeddedCarrierPool` instead of a `ThreadPoolExecutor`. A carrier is
    registered only after it has started, in the same critical section that
    checks for close, so no carrier can start after the close has joined the
    carriers. Close joins the real threads, never the pool's bookkeeping.
  - An idle carrier waits in a Truffle-interruptible blocked region, so
    `Context.close(true)` and other thread-local actions reach it; previously an
    idle carrier could block `close(true)` forever. An interrupt alone is not
    treated as close.
  - A cancelled or exiting close no longer waits for the Process to become
    terminal, and `disposeContext` covers closes that skip finalization.
  - In a Context that forbids thread creation, `Actor.spawn` still returns its
    `ActorRef`; the incarnation terminates after the creation cutover, and a
    later `request` fails as an ordinary, non-uncertain Future.
  - Failures escaping a carrier are not caught. If accepted work remains, a
    replacement carrier is started; a fatal JVM failure is an exceptional
    runtime limit, not a guaranteed recovery.
  - New `ProtosEmbeddedCarrierPoolTest` (deterministic start/close, refusal,
    replacement, interrupt, and self-close races) and
    `ProtosEmbeddingLifecycleTest` (thread policy, cancellation, safepoint
    actions, retained values after close, independent Contexts).
  - Known limitation: in the embedding, `Actor.spawn` can name only `std:`
    modules (I087). A pending `Future.value()` inside a host-invoked Closure
    fails with an internal error rather than suspending; it is recorded as an
    open I086 residual.

## 0.3.288-SNAPSHOT

- `PERF031-C` reduces caller-side Core bootstraps in
  `ProtosPackageTestLogicalCaseExecutionFacilityTest` from eleven to one. The
  specification and observable Protos behavior are unchanged.
  - A class-local lazy holder prepares the immutable Package-flavored resolver
    configuration and its caller Core Prelude exactly once.
  - Every test still creates its own module activation, runtime host,
    submission queue, and logical Case execution facility; the attempt
    bridge's own Prelude, per-Case Processes, and physical Case authority are
    unchanged.

## 0.3.287-SNAPSHOT

- `PERF031-B` reduces redundant Core bootstraps in five Java test classes. The
  specification and observable Protos behavior are unchanged.
  - `ProtosTestToolSuiteGraphTest`,
    `ProtosTestToolResourceCatalogSchemaTest`,
    `ProtosTestToolResourceRequirementsSchemaTest`, and
    `ProtosTestToolSequentialRunnerTest` bootstrap their class-invariant
    Prelude once, lazily, through a class-local holder, following
    `ProtosTestToolManifestPlanTest`.
  - `ProtosExternalPackagePlanningPreflightTest` shares one lazily prepared
    Core Prelude only in its borrowed-custody assertion helper; package
    verification, planning, custody, and Process lifetimes are unchanged.
  - Every fixture and evaluation still uses a fresh module activation with its
    own Actor module state, so no mutable module instance is shared between
    tests.

## 0.3.286-SNAPSHOT

- `I086` / `PLAT054-3C` packages Core in the Protos JAR, so a standard
  Polyglot embedding (`Context.newBuilder("protos").build()`) runs with no
  `protos.CoreRoot` option and no language home. The specification is
  unchanged.
  - The build copies `protos/lib` into the JAR as the Truffle internal resource
    `ProtosCoreResource`, with a file list and a SHA-256 content hash. Truffle
    unpacks it once per content hash into its versioned resource cache, never
    per Context or per call.
  - Core resolution keeps the order `protos.CoreRoot` > language home >
    packaged Core. An invalid override still fails without fallback; a
    language home that has no `protos/lib/core` is not a Core origin. Module
    identities (`std:` `ModuleKey`s), the Actor-local module cache, and lazy
    bootstrap are unchanged.
  - Reading the packaged Core grants the guest no Filesystem or Network
    authority, and its unpacked location is never exposed to the guest.
  - `ProtosPackagedCoreEmbeddingTest` checks the option-free path and asserts
    that Core never comes from the checkout. `dist/smoke_polyglot_embedding.sh`,
    now part of `dist/validate_portable.sh`, runs the public example against
    the distributed `lib/protos.jar` alone from outside the checkout, and checks
    override, language-home precedence, and invalid-override failure.

## 0.3.285-SNAPSHOT

- `I085-B` checks the `I085-A` external foreign provider SPI end to end. Protos
  programs run on an extracted portable distribution, as separate processes,
  and load plugin JARs kept outside it through `--foreign-provider-path`. Only
  tests and the test wiring change; the specification and the runtime are
  unchanged.
  - Three external test plugins are compiled only against the distribution's
    `lib/protos.jar`. `inventado:demo` uses opaque Java handles. `oslib:libc`
    calls the real C `strlen` through the JDK 25 Foreign Function and Memory
    API on Linux x86_64, in process under the host-selected trusted
    authority; it is not isolated. `jdk:math` and `jdk:date` call the JDK
    `Math.abs(long)`, `Math.max(long, long)`, and `LocalDate`, with each
    overload chosen explicitly in the plugin.
  - The assertions live in the `.protos` programs under
    `protos/tests/foreign-provider/`. They cover results, module-cache
    identity, two providers loaded together, and Errors for incompatible
    arguments and invalid dates. They also check that an unprovided scheme
    signals an Error, and that a program which leaves it unhandled exits with
    failure.
  - Pay-as-you-grow checks run from separate processes. Without
    `--foreign-provider-path` nothing is discovered, even with the plugin
    JARs on `CLASSPATH`. Configured providers that a program never imports
    open no session. The installed distribution is never modified. On any
    platform other than Linux x86_64 with JDK 25, the FFM check reports
    `UNSUPPORTED`, never a pass.
  - `make test-protos` now packages once and then runs two phases,
    `test-protos-foreign` (`dist/test_external_foreign_providers.py`) and
    `test-protos-suite` (the previous `bin/protos test`). A failing phase
    never prevents the other from running, and the target fails if either
    phase fails. The foreign phase prints Test Tool-style progress lines.
    These programs are not in the ordinary conformance manifest.

## 0.3.284-SNAPSHOT

- `I086` / `PLAT054-3` slice B lets the standard Polyglot embedding accept Java
  scalar arguments, so `add.execute(2, 3)` now returns `5`. The specification
  is unchanged by this slice.
  - Java `Byte`, `Short`, `Integer`, and `Long` arguments become ordinary
    Protos Integers of exactly the same value, and Java `String` arguments
    become ordinary Protos Strings. A String with an unpaired surrogate is
    rejected.
  - Every other host value is rejected before any guest code runs, and the
    rejection is not fatal. This includes floating-point numbers, booleans,
    characters, `BigInteger`, collections, callbacks, and arbitrary objects.
  - Protos-valued arguments pass through unchanged, and argument order is
    preserved. Defaults, rest parameters, and arity Errors follow ordinary
    binding. The argument array is copied only when an argument needs
    conversion, and no Task is created.
  - The `ProtosStandaloneHostedSession` adapter still accepts only zero
    arguments.

## 0.3.283-SNAPSHOT

- `I086` / `PLAT054-3` slice A implements the standard Polyglot embedding
  contract of specification `0.1.449`: `Context.newBuilder("protos").build()`,
  `context.eval(source)`, and `context.getBindings("protos").getMember(name)
  .execute(...)` now work without `ProtosStandaloneHostedSession` or any Java
  bootstrap.
  - One lazy Protos Process per Context: building the Context or querying its
    bindings creates nothing; the first valid evaluation bootstraps Core, the
    Process, and its RootActor; a parse error precedes bootstrap. Core comes
    from the new `protos.CoreRoot` option (an invalid override fails
    explicitly) or else the language home. Arguments, environment, and standard
    streams come from the embedding `TruffleLanguage.Env`; no default
    Filesystem or Network is granted.
  - Each evaluation is a direct RootActor entry: the first is the initial
    module (the only one with bootstrap slots), later ones get distinct
    standalone `moduleContext`s; `import()` uses the ordinary Actor-local cache.
  - The binding scope is a stable, host-read-only view of the own local slots of
    the last normally completed entry, empty after Process termination. Closure
    reads are fresh receiver-bound extractions presented through the PERF033
    compact executable (no Task); native Closures use ordinary invocation, and
    Protos-valued arguments are accepted.
  - An Error escaping an outermost host entry fails the RootActor and
    terminates the Process, which is never recreated; concurrent RootActor
    entry is rejected without a universal call gate; `Context.close()`
    terminates the Process and joins its Actor carriers.
  - Driver-owned Contexts are unchanged and still expose no language top scope.
    Host-value argument conversion, a packaged Core resource, and
    Filesystem/Network grant mapping remain for later slices.

## 0.3.282-SNAPSHOT

- `I085-A` adds a public, execution-mechanism-independent SPI for external
  foreign providers, so a new foreign language or native library can be used
  from Protos by installing an external plugin, without changing or
  rebuilding Protos. The specification is unchanged by this slice.
  - Public SPI package `com.guillermomolina.protos.spi.foreign`:
    `ProtosForeignProviderPlugin` (identity, scheme, canonicalization,
    session, value operations), `ProtosForeignPluginSession` (opaque
    `Object` module handles), `ProtosForeignPluginEnvironment` (read-only
    host options only), and `ProtosForeignValueOperations` with
    `ProtosForeignValueClass`, `ProtosForeignArgumentValue`, and
    `ProtosForeignFailureInfo`. The SPI does not depend on Truffle or
    Polyglot; the optional `spi.foreign.polyglot` package offers a reusable
    `Value`-based implementation for Truffle-backed providers.
  - Plugins are bridged into the existing I082 substrate (canonical
    `ModuleKey`, Actor-local facades, cache and retry, D188 admission and
    identity, sessions and generations, failures, Actor isolation, closure);
    D189 callbacks do not cross the public SPI.
  - Discovery uses `ServiceLoader` only over explicitly configured provider
    paths, through a host-owned class loader; the global class path is never
    scanned. The provider registry is fixed when the RuntimeHost is built
    (PLAT053); duplicate or invalid identities and schemes, missing paths,
    and paths without providers are rejected.
  - Embedders use `ProtosPolyglotRuntimeHost.openWithForeignProviders` with
    `ProtosForeignProviderConfiguration.trustedInProcess(paths)`: loading an
    external JAR is an explicit host trust decision (PLAT052); `open()` is
    unchanged and configures no provider.
  - The CLI accepts `--foreign-provider-path <paths>` before a file, `-e`, or
    REPL execution.
  - Pay as you grow: without configured paths there is no discovery, class
    loader, session, or foreign initialization; configured but unused
    providers open no session.
  - No foreign-language dependency was added. Dynamic plugin loading is
    JVM-only; Native Image does not offer it.

## 0.3.281-SNAPSHOT

- `LIB021-A` adds `std:interop`, the first public explicit
  foreign-interoperability Standard Library module, implementing the D193
  operation contract reconciled by I084. The specification is unchanged by
  this slice.
  - `std:interop` is an ordinary Protos Standard Library module
    (`protos/lib/interop.protos`) whose public surface is exactly:
    - `invoke(target, ...arguments)`;
    - `instantiate(target, ...arguments)`;
    - `readMember(target, name)`;
    - `writeMember(target, name, value)`.
    Its runtime-only `_interopFacility` is private and removed from the
    published module surface.
  - Operations:
    - `invoke` performs explicit foreign execution without ordinary Protos
      call lookup;
    - `instantiate` performs explicit construction and stays distinct from
      `invoke`, including for targets that support both;
    - `readMember` performs an explicit underlying foreign-member read and can
      reach names protected from ordinary projection;
    - `writeMember` performs explicit foreign-member mutation and, after
      success, returns the exact original Protos value with no readback.
  - All four operations reuse the existing D188 admission, projection, and
    failure substrate. Closure arguments reuse D189 synchronous
    operation-scoped callback semantics. Exact provider/session/generation
    lifetime is preserved: closed sessions and generations are rejected, and
    entered foreign failures surface as fresh `ForeignError`s.
  - Importing `std:interop` creates no provider, session, Context, discovery,
    acquisition, or extra authority.
  - Unchanged: ordinary member lookup/write/invocation, callability,
    `at`/`atPut`, projected `each`, identity/equality/hash, Actor/P transfer,
    and provider topology.
  - The restricted host-Java provider only widens its classification so
    explicit member reads can reach already-catalogued members; there is no
    discovery and no public reflection.
  - D193 continues to defer `invokeMember`, public capability predicates,
    explicit indexed/hash operations, iterator/cursor APIs, explicit
    conversion, metaobject/type/language/source/display metadata,
    provider/language discovery, foreign acquisition, and retained or
    asynchronous callbacks.
  - Substrate: `ProtosForeignInteropFacility`,
    `ProtosForeignAdmissionDescriptor`, `ProtosForeignHandle`,
    `ProtosForeignProjectedOperations`, `ProtosForeignValueAdapter`,
    `ProtosHostJavaProvider`, and `ProtosCoreBootstrap`.
  - New `ProtosForeignInteropTest`, with bounded extensions of the existing
    foreign fixtures/tests, covers the exact four-operation surface, the
    hidden `_interopFacility`, zero-use import, `invoke`/`instantiate`
    disambiguation, protected-name and fidelity-bypassing `readMember`,
    `writeMember` exact-value result without readback, raw-reference and
    facade targets, pre-entry ordinary failures, closed session/generation
    rejection, entered fresh `ForeignError`, and D189 synchronous callbacks
    and expiry.

## 0.3.280-SNAPSHOT

- `I082-G` adds the first production foreign provider: a restricted host-Java
  provider for the `java:` import scheme. The specification, D188, D189,
  PLAT052, and PLAT053 are unchanged. With this slice the I082 foreign interop
  runtime/import foundation is complete.
  - New public `ProtosHostJavaCatalogue` is the embedder's immutable list of
    already-provisioned Java classes and the exact members that may cross the
    boundary:
    - at most one public constructor per class;
    - at most one public static method and one public instance method per
      name, declared by the admitted class itself, so no overloads and no
      inherited members such as `getClass`;
    - parameter and result types limited to lossless D188 scalars and other
      admitted classes.
    Being on the classpath never makes a class importable.
  - New `ProtosPolyglotRuntimeHost.openWithHostJava(catalogue)` installs the
    provider when the host is created. `open()` still configures no foreign
    provider and no Java authority. There is no dynamic or guest provider
    registration, no dependency acquisition, and no Espresso, guest JVM, or
    extra Polyglot Context.
  - The provider profile is `RESTRICTED_IN_PROCESS`; the catalogue is its
    confinement and no authority is provisioned to it.
    - Looking up an import target is a plain catalogue lookup, so a class that
      is not admitted is never loaded, initialized, or probed, and no
      compartment or session is opened for it.
    - Compartments and sessions stay lazy and hold no application state.
    - Reflection is used only to invoke members the embedder preselected.
  - Imported classes are ordinary Actor-local foreign module facades:
    - a facade with an exposed constructor publishes it as its own `call`
      slot, so `G(args)` constructs;
    - `G.staticMethod(args)` and `object.method(args)` are the ordinary
      foreign member read followed by ordinary invocation of an executable
      bound to the exact receiver.
  - Values and failures follow D188:
    - Java booleans, null/void, Strings, integral primitives/wrappers,
      `BigInteger`, and binary floating values convert losslessly;
    - every other Java object is a raw reference identified by host
      reference identity, never Java `equals`/`hashCode`;
    - arguments convert only losslessly for the exact parameter type and
      otherwise fail before the Java member runs;
    - Closure arguments are rejected before entry;
    - a thrown Java exception becomes a fresh `ForeignError` exposing only the
      exception class name.
  - Generic substrate: `ProtosForeignModuleProvider.publishesFacadeCall()`
    (default `false`) lets a provider deliberately publish its facade's
    projected `call` (D188 "Callability and construction"). Facades of other
    providers are unchanged.
  - New `ProtosHostJavaProviderTest` proves the real provider end to end over
    inert fixture classes: import and Actor-local identity, static calls,
    construction, instance calls, scalar admission, lossy-argument and
    overload rejection, raw identity, sanitized failures, unadmitted classes
    and unexposed members failing closed, absence of authority, session
    lifetime and no rebinding, no Actor/P transfer, and zero-use laziness of
    both the configured and the default host.

## 0.3.279-SNAPSHOT

- `I082-F` makes the PLAT052 foreign provider execution profiles real
  enforcement states. The specification, D188, D189, and PLAT053 are
  unchanged, and there are no observable Protos semantic changes.
  - New internal `ProtosForeignProviderAdmission` is the single admission
    owner. It decides from the immutable host-supplied descriptor alone,
    using type tests and running no provider code. It rejects:
    - `UNAVAILABLE`;
    - `RESTRICTED_IN_PROCESS` without a
      `ProtosForeignProviderEnforcement.InProcessRestriction`;
    - `STRONGLY_ISOLATED` without a
      `ProtosForeignProviderEnforcement.StrongIsolation`, including when an
      in-process restriction is supplied instead;
    - a trusted provider that carries a mechanism; and
    - an ambiguous mechanism.
  - Admission runs before import canonicalization, before the Actor-local
    facade is cached, before session acquisition, and before compartment
    opening. D188/D189 operations are reachable only through session
    bindings acquired after admission. A rejected provider has zero
    provider-side effect.
  - Restricted and isolated compartments are opened only through their
    mechanism. Only host-trusted providers are opened directly from their
    factory. Profiles and mechanisms are fixed in the RuntimeHost registry;
    imports, dependencies, guest code, and foreign code cannot select or
    upgrade them.
  - Provisioned foreign authority remains none. No provider discovery, host
    access, Context construction, or production provider is added. The test
    conformance providers run under an inert zero-authority restriction, and
    the new `ProtosForeignProviderEnforcementTest` adds the negative proof.

## 0.3.278-SNAPSHOT

- `I082-E` adds the D189 synchronous foreign callback bridge with
  dynamic-extent lifetime checks. The specification and the observable
  semantics it already defines are unchanged.
  - A Closure argument of a projected foreign `call`, `at`, or `atPut` now
    crosses as an operation-scoped `ProtosForeignCallback` capability. That
    capability denotes the exact Closure and is invoked through ordinary
    Protos invocation. Every other non-scalar, non-raw argument still fails
    before the operation is entered.
  - Callback arguments are admitted through the existing D188 admission. A
    normal result returns to the provider only as a lossless scalar or as a
    raw reference of the same session. Any other result is rejected toward
    the provider and is never exported.
  - Inside a Task, the callback continues the current Task as a nested
    synchronous C-prime call. It creates no Task, Future, Actor turn, or
    structured scope, and adds no cancellation checkpoint. Sequential,
    recursive, and nested foreign-call re-entry are supported.
  - `ProtosTask.beginSuspensionCapture` is the single commit gate. A
    suspension that would commit while a callback is running is rejected:
    the registered waiter is removed, and a fresh `Error` is signaled at the
    suspension point.
  - Each operation's callback scope is live only while the operation runs
    and expires on every exit path. On expiry it drops its Closure,
    activation, and session references. Invocations are rejected before
    guest entry when the scope has expired, when they arrive on a different
    thread (foreign-created or concurrent), or when the session is closed,
    the Task is not running, or the Actor has terminated.
  - A Protos Error or control transfer that leaves a callback travels in a
    private carrier bound to its operation. Only that exact carrier, leaving
    the same operation, resumes the original outcome; anything else becomes
    a fresh `ForeignError`. When the Error leaves the callback, any handler
    it selected outside the callback is released, so a replacement
    `ForeignError` can still be handled there.
  - Only the plain-Java test provider is used. No production provider,
    `std:interop` API, or authority expansion is added.

## 0.3.277-SNAPSHOT

- `PERF033-A` adds a canonical pay-as-you-grow Polyglot executable for
  prepared hosted-session Closures. There is no specification or observable
  Protos semantic change, and no performance claim is made yet.
  - `ProtosStandaloneHostedSession.PreparedTopLevel.executable()` returns an
    `org.graalvm.polyglot.Value`, created once at preparation and bound to the
    session's live Process Context. `Value.execute()` enters through the
    framework host-to-guest boundary and runs ordinary direct activation of
    the exact prepared Closure. It creates no RootTask or `ProtosTask`, does
    not register an Actor task, and performs no Protos-owned Context entry.
  - `ProtosHostExecutableClosure` is the internal interop adapter that
    exposes this. It is not a Protos value and has no Closure identity of its
    own. It retains the Context-owned Bytecode target and enters the PERF025
    compact direct-Closure frame ABI through a cached `DirectCallNode`, so
    the literal workload materializes no rich callee activation. Only
    zero-argument execution is supported; other arities fail through interop
    arity.
  - The per-entry `ENTERED_CONTEXT` thread-local is replaced by a binding of
    each host wrapper to its `ProtosLanguageContext`, made once when the
    wrapper opens. Standard-stream routing, physical Source admission, Core
    bootstrap, and P rematerialization therefore behave the same under
    framework-owned entry.
  - `PreparedTopLevel.invoke()` is unchanged and keeps its
    `ProtosExecutionOutcome` contract. The executable does not take the
    session gate, so callers must not run it concurrently. After session
    close it is rejected and does not keep the Process or Context alive.

## 0.3.276-SNAPSHOT

- `I082-D2` completes the generic D188 runtime with projected foreign pull
  iteration, `foreign.each(block)` (`VALUES_AND_COLLECTIONS.md` "Indexed
  access, foreign hash containers, and iteration"). There is no specification
  change. On normal completion, `each` returns the receiver, matching every
  standard `each` (owner decision; the D188 text does not yet state it).
  - A provider declares the new `ITERABLE` capability; iterability is never
    inferred from array/hash shape or from a foreign member named `each`, which
    stays reserved.
  - `ProtosForeignValueAdapter` gains `openIterator`, `iteratorHasNext` and
    `iteratorNext`. These receive only the session and provider-private values,
    never a Protos callback. Protos owns the loop.
  - `ProtosForeignEachCall` validates the receiver, exact arity and the
    ordinary callability of the block (any invokable value, not only a Closure)
    before acquiring the iterator. It then pulls one element at a time, admits
    it through `ProtosForeignValueAdmission`, and invokes the block through
    ordinary invocation. Nothing is snapshotted.
  - Each provider call is an entered foreign operation (`iterator`,
    `iteratorHasNext`, `iteratorNext`). Failures inside the provider become a
    fresh `ForeignError`. A closed session fails with an ordinary Error before
    entry and never rebinds. An Error or non-local exit from the block stops
    pulling and propagates unchanged.
  - Inside a Task, the structured C-prime dispatcher recognizes the projected
    `each` body and drives the same cursor with scoped callback invocation, so
    callback suspension resumes the same visit without replay, with no new
    Task, Future, or Actor turn.
  - Raw references and attached module facades project `each` identically.
    The projected `each` Closure stays Actor-nontransferable and P-nonparallel.
  - D189 callback bridging, concrete providers, `std:interop`, and host
    authority remain out of scope.

## 0.3.275-SNAPSHOT

- `I082-D1` adds the single provider-neutral D188 foreign-value substrate
  (`VALUES_AND_COLLECTIONS.md` "Foreign Values") and realizes the already
  normative `ForeignError` Core category. There is no specification change.
  Foreign iteration (`each`) is not projected yet; it follows in `I082-D2`.
  No concrete provider, callback bridge (D189), `std:interop` API, or host
  authority is introduced.
  - `ForeignError` (parent `Error`) is added to the Core Error taxonomy,
    the Prelude, `ProtosCoreErrors.StandardError`, bootstrap validation, and
    the test Runner's standard-error mapping.
  - Providers supply one `ProtosForeignValueAdapter` with explicit source
    classification. Booleans, true null, valid Unicode text,
    source-classified integrals, and exact binary64 values convert to their
    Protos families. Everything else, including ambiguous null-like,
    decimal, custom numeric, invalid text, and array/map-shaped values, stays
    a raw foreign reference (`ProtosRawForeignValue`).
  - Raw references are bound to their exact session generation and never
    rebind. They are identity-bearing: provider-stable identity unifies
    distinct wrappers within one session, otherwise each admission is its
    own identity. `==`/`hash` are the default identity rules, and `Map` and
    `IdentityMap` keep their own key rules. Raw references have no local
    slots, so creation and assignment on them fail ordinarily.
  - Member lookup on a raw reference uses projected `call`/`at`/`atPut`
    only when faithful and unambiguous. It never inherits `Object.call`.
    Otherwise it uses the root Object chain, then a provider-asserted
    faithful member fallback whose result is admitted. Institution names are
    never satisfied by foreign members. A foreign module facade keeps its
    ordinary chain first and then projects its target through the same
    substrate. `foreign.member(args)` is the read followed by ordinary
    invocation of the read value.
  - Pre-entry failures (missing projection, closed session, non-exportable
    argument such as a Closure, object, or Array) are ordinary Errors.
    Failures after entry, including foreign module target acquisition, are
    fresh `ForeignError`s with exactly the `language`, `operation`,
    `category`, `foreignCategory`, `message`, and `cause` slots, built from
    provider-sanitized data with cycle-safe cause projection.
  - Raw references, foreign module facades, and projected Closures are
    rejected by Actor transfer (`NonTransferableValue`) and P transfer
    (`NonParallelValue`). Converted scalars transfer normally. The three
    projected native providers are registered as an audited non-Core
    native boundary.

## 0.3.274-SNAPSHOT

- `I082-C` routes explicit foreign imports to a registered provider and
  realizes foreign module instances as Actor-local Protos facades under the
  ordinary module lifecycle (D188, PLAT053, `MODULES.md` "Foreign module
  instances"). There is no specification change. Source-backed import
  behavior is unchanged, and no concrete provider, member projection,
  callback, or foreign authority is introduced.
  - A provider descriptor may own one `ProtosForeignImportRoute`: an exact
    import scheme plus a provider-neutral `ProtosForeignModuleProvider`
    (`canonicalTarget(exactTarget)` without a session,
    `acquireTarget(session, canonicalTarget)` inside the Actor's session).
    Routes are fixed with the RuntimeHost registry; duplicate schemes are
    rejected, and the source schemes `std`, `self`, `dep`, and `tool-shared`
    cannot be routed. Only a `scheme:target` specifier whose scheme is
    registered with the importing Process's RuntimeHost is foreign; every
    other specifier, including unknown schemes, keeps the source resolver's
    authority and never invokes a provider.
  - Foreign canonical identity is `ProtosForeignModuleKey`
    (`foreign:v1:<scheme>:<target>`, base64url fields) built only from the
    route scheme and the provider-defined canonical target. A source
    resolver result inside that domain is rejected, so foreign and source
    keys cannot collide.
  - On a cache miss, `ProtosModuleRuntime` caches a fresh
    `ProtosForeignModuleFacadeValue` as `INITIALIZING` before obtaining the
    Actor's I082-B session and acquiring the target. Reentrant and cyclic
    imports see the same partial facade. Success attaches the target
    privately and marks the same record `READY`. Failure removes the exact
    record and surfaces as an ordinary Core Error; a retry creates a fresh
    facade, and an escaped failed facade stays unattached and unchanged.
    Foreign initialization completes during Bytecode import preparation, so
    the existing immediate prepared-import result carries it without a
    child root.

## 0.3.273-SNAPSHOT

- `I082-B` adds the PLAT053 Process-owned lazy foreign provider compartment
  and Actor-isolated session lifecycle. There is no specification change and
  no guest-visible change; import resolution is unchanged and no foreign
  authority, Polyglot Context, or guest language is introduced.
  - `ProtosForeignProviderProcessLifecycle`, owned by each
    `ProtosPolyglotProcessContext`, opens at most one compartment per
    Process/provider on first use and at most one session per
    Actor/provider on first use; repeated use returns the same live session.
    Unknown providers and `UNAVAILABLE` providers fail without invoking a
    factory. Provider code runs outside the Process-local publication lock;
    racing first acquisitions wait for the single constructor, and failed
    compartment or session construction is not cached.
  - `ProtosForeignProviderCompartment.openSession()` creates a provider-side
    `ProtosForeignProviderSession`; the runtime-owned
    `ProtosForeignProviderSessionBinding` records the Actor association,
    serves as the session generation, and fails closed once closed.
  - `ProtosProcessExecutionHost.actorTerminatedForRuntime(actor)` (default
    no-op) is notified when a hosted Actor actually reaches `TERMINATED`,
    before it leaves its Process; the Polyglot Process Context closes that
    Actor's sessions. Process terminal notification rejects further admission,
    closes remaining sessions, then closes every compartment exactly once
    before releasing the Process Context. Cleanup failures never stop the
    remaining cleanup and surface through
    `awaitTerminalDispositionForRuntime()` as host/runtime failure.
  - A RuntimeHost, Process, or Actor that never acquires a provider invokes
    no factory and creates no compartment or session.

## 0.3.272-SNAPSHOT

- `I082-A` adds the provider-neutral foreign provider registry foundation
  ratified by PLAT053. There is no specification change and no guest-visible
  change; import resolution and the Protos Engine/Process Context lifecycle
  are unchanged.
  - `ProtosPolyglotRuntimeHost` owns one immutable host-supplied
    `ProtosForeignProviderRegistry`, fixed at construction; `open()` and
    `openDebug()` supply the empty registry. There is no discovery, global
    singleton, or mutation.
  - Internal model: `ProtosForeignProviderId`, the four PLAT052/PLAT053
    profiles in `ProtosForeignProviderExecutionProfile`
    (`RESTRICTED_IN_PROCESS`, `TRUSTED_IN_PROCESS`, `STRONGLY_ISOLATED`,
    `UNAVAILABLE`), `ProtosForeignProviderDescriptor`,
    `ProtosForeignProviderFactory`, and a logical
    `ProtosForeignProviderCompartment` that is explicitly not a Polyglot
    Context. Duplicate provider identities are rejected deterministically.
  - No provider factory is invoked and no compartment, session, foreign
    Context, or foreign runtime is created; the normal execution path is
    unchanged.

## 0.3.271-SNAPSHOT

- `LIB015-E1` adds `std:logging/ColoredFormatter`, a human-readable formatter
  that colors only the LEVEL token of the `std:logging/TextFormatter` line.
  There is no specification change and no Java, runtime, or bootstrap change;
  `Level`, `LogEvent`, `Logger`, `TextFormatter`, `TextSink`, `MemorySink`,
  `JsonFormatter`, and the `std:text` modules are unchanged.
  - `ColoredFormatter(mode, autoEnabled)` resolves
    `std:text/ColorMode.resolve(mode, autoEnabled)` once at construction,
    propagating its Error for invalid arguments, and answers a fresh frozen
    formatter whose only slot is `format`.
  - `format(event)` calls `TextFormatter.format(event)` exactly once, which
    stays the only owner of the human line grammar and signals its Errors
    unchanged. With styling disabled the result is exactly that line; with
    styling enabled only the LEVEL token is wrapped by `std:text/ANSI`
    foreground sequences, so removing them yields exactly that line.
  - The palette is fixed: TRACE blue, DEBUG cyan, INFO default (no escape
    sequence), WARN yellow, ERROR red. No environment, terminal, or Process
    is consulted, and composition reuses `TextSink(formatter, writer)`.

## 0.3.270-SNAPSHOT

- `TEST009-AL` removes the redundant `List.copyOf` re-copy in the prepared
  structured-Boolean call. Its only construction site passes the `NativeCall`
  argument list, which is already an immutable `List.copyOf` result, so the
  re-copy returned the same instance but forced partial evaluation through the
  erased `Collection.isEmpty()`/`toArray()` fallback. On the focal strict SYNC
  reproducer this removes exactly that family (performance warnings 52 -> 48,
  compilation failures unchanged at 2); the strict gate remains red. There is
  no semantic, specification, or public API change.

## 0.3.269-SNAPSHOT

- `LIB020-C` adds `std:text/ColorMode`, the pure color-mode policy ratified by
  D190/D191. There is no specification change and no Java, runtime, or
  bootstrap change; no consumer (logging, Test Tool, Package Tool, CLI) is
  integrated in this slice.
  - `ColorMode.AUTO`, `ColorMode.ALWAYS` and `ColorMode.NEVER` are exactly the
    canonical Strings `"AUTO"`, `"ALWAYS"` and `"NEVER"`; no alias or other
    letter case is a mode.
  - `ColorMode.recognizes(value)` answers `true` only for the three modes, by
    String identity, never signals an Error and never invokes candidate
    behavior.
  - `ColorMode.resolve(mode, autoEnabled)` answers `autoEnabled` for `AUTO`,
    `true` for `ALWAYS` and `false` for `NEVER`. Both arguments are always
    validated: `mode` must be a canonical mode and `autoEnabled` exactly
    `true` or `false`, otherwise an Error is signalled.
  - No environment, Process, standard stream, or terminal is consulted; the
    resolved Boolean is passed by the caller to `ANSI.render`, whose API is
    unchanged.

## 0.3.268-SNAPSHOT

- `LIB020-B` adds `std:text/ANSI` with exactly one public operation,
  `ANSI.render(styledText, stylingEnabled)`, the pure deterministic renderer
  ratified by D190/D191. There is no specification change; `ColorMode`,
  environment/TTY detection and `TextWriter` integration are not part of this
  slice.
  - `styledText` must be recognized by `StyledText.recognizes` and
    `stylingEnabled` must be exactly `true` or `false`; anything else signals
    an Error without conversion or invoking candidate behavior.
  - With `false` the result is the run text concatenated in order; no escape
    sequence is generated.
  - With `true` the eight basic foregrounds render as `ESC[30m`..`ESC[37m`.
    Default text generates nothing; leaving a color for default, for a
    different color (reset then new foreground), or at the end emits
    `ESC[0m`. Equal adjacent colors emit nothing; empty text renders as `""`.
  - Run text, including existing escape and control characters, passes
    through unchanged; the renderer neither parses nor sanitizes it.
  - Internal runtime support: the bootstrap provisions `std:text/ANSI` with
    the same `ProtosSealedFamilyFacility` instances as `std:text/Style` and
    `std:text/StyledText`; the module captures and removes them, so no new
    family, native closure, or public state is introduced.

## 0.3.267-SNAPSHOT

- `LIB020-A` adds the `std:text/Style` and `std:text/StyledText` public
  semantic-value surface ratified by D190/D191. There is no specification
  change; ANSI rendering, `ColorMode`, terminal detection and `TextWriter`
  integration are not part of this slice.
  - `Style(foreground)` answers a fresh frozen style for exactly one of
    `"default"`, `"black"`, `"red"`, `"green"`, `"yellow"`, `"blue"`,
    `"magenta"`, `"cyan"` or `"white"`; any other argument signals an Error
    without conversion, case folding or `null` defaulting. Styles have
    semantic `==` with a coherent `hash` and ordinary `===` identity. Their
    state is opaque.
  - `StyledText(text)`, `StyledText(text, style)` and
    `StyledText.concat(...fragments)` (Strings or styled texts) answer fresh
    frozen styled texts that logically hold an opaque flat sequence of runs.
    `concat` always answers a styled text; empty text keeps no style. Styled
    texts keep ordinary identity equality and hash only.
  - `Style.recognizes` and `StyledText.recognizes` are safe exact-family
    recognition: no candidate behavior (`parent`, `slotNames`, `hasSlot`,
    `slotValue`, `==`, `hash`, ...) is ever invoked, and lookalike, malformed
    and unfrozen forgeries are rejected.
  - New internal runtime support: `ProtosSealedValue`, an ordinary frozen
    object minted only by a sealing family that keeps family-private state
    outside Protos-visible slots, and `ProtosSealedFamilyFacility`, one
    frozen per-module facility (`seal`, `recognizes`, `state`) provisioned as
    a standard initial member of each module and removed from its surface.
    The standard module member table now uses `Map.ofEntries`.
  - Sealed values are not semantically portable: Actor transfer signals
    `NonTransferableValue` and isolated-parallel transfer `NonParallelValue`
    instead of copying them into ordinary objects that would lose their
    family. No PLAT051 transfer family is registered.
  - Coverage: `protos/tests/conformance/library/text/style.protos` and
    `styled-text.protos` (including hostile candidates), and
    `ProtosSealedFamilyFacilityTest` (run content, module surface, both
    transfer boundaries and an ordinary-object control). The audited native
    boundary inventory registers the new facility as a non-core provider.

## 0.3.266-SNAPSHOT

- `TEST009-AJ` (guillermomolina/protos#795) keeps the real, reachable
  missing-binding failure of `ProtosPrelude.standardErrorPrototype(String)`
  out of Truffle partial evaluation. There is no specification or semantic
  change.
  - The `Optional.orElseThrow()` read is split into an explicit empty check:
    `CompilerDirectives.transferToInterpreter()` now precedes the throw of the
    same `NoSuchElementException("No value present")`. The dynamic name
    lookup, lazy validation timing, ordinary-object and Error-hierarchy
    validations are unchanged; compiled code is not invalidated, and no
    `@TruffleBoundary` is added.
  - A focal test pins the lazy missing standard-Error failure class and
    message.
  - Re-diagnosing `protos-root:088d2ae81075aba8` (`Manifest.protos`): the
    14 `standardErrorPrototype` `NoSuchElementException` occurrences are gone
    (aggregate 46 to 32, `Throwable.fillInStackTrace()` 76 to 62), the
    `attachTaskOrInheritDynamicControlState` (20) and
    `finishPreparingComposedCall` (12) subtrees are unchanged, and the target
    now compiles instead of failing with `CodeTooLarge`.

## 0.3.265-SNAPSHOT

- `TEST009-AI` (guillermomolina/protos#795) keeps the real, reachable
  missing-`Array` failure of `ProtosPrelude.arrayPrototype()` out of Truffle
  partial evaluation. There is no specification or semantic change.
  - The `Optional.orElseThrow()` read is split into an explicit empty check:
    `CompilerDirectives.transferToInterpreter()` now precedes the throw of the
    same `NoSuchElementException("No value present")`. The exception class,
    message and lazy validation timing are unchanged; compiled code is not
    invalidated, and no `@TruffleBoundary` is added.
  - A focal test pins the lazy missing-`Array` failure class and message.
  - Re-diagnosing `protos-root:088d2ae81075aba8` (`Manifest.protos`) still
    fails with `CodeTooLarge`, but the 20-frame `arrayPrototype`
    `NoSuchElementException` subtree is gone: `NoSuchElementException`
    size in the method expansion tree drops from 4884 to 3404 and
    `Throwable.fillInStackTrace()` size from 6864 to 5434. The other
    `NoSuchElementException` subtrees are unchanged.

## 0.3.264-SNAPSHOT

- `PLAT051-B` opts `std:regex/Regex` Pattern and Match into PLAT051 two-stage
  semantic transfer, so both cross Actor and isolated-P boundaries as
  portable semantic data (D187). There is no specification change and no
  observable Regex semantic change.
  - One trusted family, `ProtosRegexSemanticTransferFamily`, owned by
    `std:regex/Regex` and registered once by Core bootstrap, serves both kinds.
    Pattern payload is its source and canonical flags; the destination
    recompiles with its own Regex module. Match payload is a snapshot of its
    capture count, per-group participation, text and scalar bounds, and
    capture names; the destination rebuilds it without matching again. No
    subject, compiled program or source Closure crosses.
  - `Regex.protos` mints its Patterns and Matches through a private bootstrap
    facility captured only by that module and removed from its surface; the
    public members and behavior of Pattern and Match are unchanged. Match
    construction now depends only on capture metadata, not on the compiled
    program.
  - A minimal generic seam lets an owning standard module install, while it
    initializes, a destination-local guest factory in its own Actor-local
    module record; family values may carry family-private state readable only
    by their family. Ordinary transfer pays no Regex-specific work.

## 0.3.263-SNAPSHOT

- `TEST009-AF` (guillermomolina/protos#795) keeps the real, reachable
  Context-local Closure projection failure of
  `ProtosBytecodeRootNode.rejectComposedInvocationProjection(...)` out of
  Truffle partial evaluation. There is no specification or semantic change.
  - `CompilerDirectives.transferToInterpreter()` now precedes the existing
    `UnsupportedOperationException` throw. The exception class, message,
    failure condition and timing are unchanged; compiled code is not
    invalidated, and no `@TruffleBoundary` is added.
  - A focal test pins the unentered composed-call rejection class and message.
  - Re-diagnosing `protos-root:088d2ae81075aba8` (`Manifest.protos`) still
    fails with `CodeTooLarge`, but the 20-frame
    `rejectComposedInvocationProjection` `UnsupportedOperationException`
    subtree is gone and `Throwable.fillInStackTrace()` size in the method
    expansion tree drops from 8294 to 6864. The `NoSuchElementException`
    subtree is unchanged.

## 0.3.262-SNAPSHOT

- `PLAT051-A2` splits Standard Library semantic-value transfer into a source
  stage and a destination stage (Candidate C with explicit two-stage
  transfer). There is no specification or observable semantic change.
  - The source stage runs synchronously inside the existing Actor/P snapshot.
    It validates the exact bootstrap-authorized family, extracts the inert
    payload, checks it with the new `acceptsPayload` and produces an internal,
    non-guest `ProtosSemanticTransferRecord`. `NonTransferableValue` and
    `NonParallelValue` timing is unchanged.
  - The destination stage materializes each record once per destination graph,
    inside the destination domain and before guest code observes it. This
    happens in Actor turns (send, request and Group), Actor spawn bootstrap,
    request replies (through a targeted completion in the requester domain),
    the P worker's root Task and the P caller's producer Task.
  - `ProtosSemanticTransferFamily.materialize` receives a
    `ProtosSemanticTransferDestination`. It loads the family's exact owning
    std module in the destination Actor-local module state and invokes
    destination guest code, so guest-implemented families are supported.
  - Only graphs that actually contain records pay the destination copy.
    Ordinary transfers pay an O(1) check and no second pass, scan or import.

## 0.3.261-SNAPSHOT

- `TEST009-AD` (guillermomolina/protos#795) keeps the real, reachable
  unsupported-representation failure of `ProtosValueLookup.delegationParent(...)`
  out of Truffle partial evaluation. There is no specification or semantic
  change.
  - `CompilerDirectives.transferToInterpreter()` now precedes the existing
    `UnsupportedOperationException` throw. The exception class, message,
    construction site and failure timing are unchanged; compiled code is not
    invalidated, and no `@TruffleBoundary` or stackless exception is used.
  - Re-diagnosing `protos-root:088d2ae81075aba8` (`Manifest.protos`) still
    fails with `CodeTooLarge`, but `Throwable.fillInStackTrace()` size in the
    method expansion tree drops from 9867 to 8294, and the 22-frame
    `delegationParent` `UnsupportedOperationException` subtree is gone. The
    `rejectComposedInvocationProjection` exception subtree is unchanged.

## 0.3.260-SNAPSHOT

- `LIB015-D1` (guillermomolina/protos#432) adds timestamped log events and an
  explicit time source to `std:logging`, implementing the ratified LIB015-D0
  decision (E1). There is no specification change; no `std:datetime/Clock`,
  ambient clock, or host time is introduced.
  - `LogEvent` has one canonical family of exactly five slots: `level`,
    `message`, `fields`, `error`, and `timestamp`, which is always present
    and holds a `std:datetime/Instant` or `null`. `LogEvent(level, message,
    fields, error)` remains valid with `timestamp` `null`; an optional fifth
    argument supplies the instant. The four-slot shape is no longer
    recognized, and the private recognition facility validates the
    timestamp without invoking candidate behavior.
  - `Logger(minimumLevel, sink, timeSource = null)` accepts an optional
    zero-argument callable answering an `Instant`. It is invoked exactly
    once per enabled call, after field merging and before event creation,
    and never for disabled calls or `with` derivations, which share the same
    source. A signalling source or a non-`Instant` result signals an Error
    to the caller and emits nothing; sink containment still covers only
    `sink.emit`.
  - `TextFormatter` prefixes timestamped lines with
    `std:datetime/ISO8601.formatInstant` and a space, signalling an Error
    for instants outside that format's civil range. `JsonFormatter` emits
    `"timestamp"` first as the exact signed Integer of nanoseconds, omitting
    it for `null`. Output for events without a timestamp is byte-for-byte
    unchanged.

## 0.3.259-SNAPSHOT

- `TEST009-AC` (guillermomolina/protos#795) removes the logically impossible
  `NoSuchElementException` construction path that the checked local-slot
  `Optional` unwrap in `ProtosValueLookup.lookup()` contributed to Truffle
  partial evaluation. There is no specification or semantic change.
  - Inside the `isPresent()` branch the same immutable `Optional` is consumed
    with `orElse(null)`, which returns its non-null value without a throwing
    path. The unsupported-representation `UnsupportedOperationException` in
    `delegationParent(...)` is unchanged.
  - Re-diagnosing `protos-root:088d2ae81075aba8` (`Manifest.protos`) still
    fails with `CodeTooLarge`, but `Throwable.fillInStackTrace()` self size in
    the method expansion tree drops from 11440 to 9867, the local-unwrap
    `NoSuchElementException` subtree is gone, and the `delegationParent`
    `UnsupportedOperationException` subtree is preserved.
  - A focal test covers the generic own-slot hit value and home, the miss, and
    the unsupported-representation exception class and message.

## 0.3.258-SNAPSHOT

- `PLAT051-A` (guillermomolina/protos#813) adds the generic privileged
  Standard Library semantic-value transfer mechanism ratified by PLAT051.
  There is no specification or observable semantic change, and no Standard
  Library family opts in yet.
  - A trusted `ProtosSemanticTransferFamily` descriptor pairs a source-local
    extractor producing an inert, acyclic `ProtosSemanticTransferPayload`
    (host scalars only) with a destination-local reconstructor. Only values
    minted by a family (`ProtosSemanticTransferValue`) carry it; guest code
    cannot forge one through names, slots, shape, tags or delegation.
  - Families are authorized by exact descriptor identity per owning `std:`
    module key, fixed by Core bootstrap at Prelude construction; there is no
    global mutable registry.
  - Actor and P transfer rebuild authorized values through their existing
    identity memos, preserving aliases, distinct identities and surrounding
    cycles, and fail closed as `NonTransferableValue` / `NonParallel` on
    unauthorized, unfrozen, malformed or invalid reconstruction. Closure,
    capability and execution-value rules are unchanged.
  - Ordinary transfer pays one additional `instanceof` per object, with no
    allocation, lookup, import or source execution; ordinary object layout
    is unchanged.

## 0.3.257-SNAPSHOT

- `LIB015-C1` (guillermomolina/protos#432) adds JSON log formatting to
  `std:logging` over `std:json`, implementing the ratified LIB015-C0
  contract. There is no specification change; timestamps, color, and a
  dedicated JSON sink remain unimplemented.
  - `std:logging/JsonFormatter.format(event)` answers one compact JSON
    object, `{"level":...,"message":...,"fields":{...}}`, plus
    `"error":true` exactly when an Error is attached, with no terminator.
    The members keep that order; `fields` is always present (`{}` when
    empty) and nests every field, so names such as `level` or `error` never
    collide with top-level members. The attached Error is never inspected.
  - Event data is projected to `std:json` nodes and encoded by
    `JSON.encode`, which alone owns string escaping and number spelling; no
    private serializer is added. Arrays keep their order and Maps are
    ordered by Unicode scalar sequence at every depth. Integers are exact
    JSON numbers without range limits; finite non-zero Floats use the same
    shortest round-trip decimal as `TextFormatter`; `0.0` and `-0.0` both
    render as `0`; `NaN`, `Infinity`, and `-Infinity` signal an Error before
    any text is produced.
  - Composition reuses `TextSink(JsonFormatter, writer)`, which writes one
    JSON value per line; a Logger contains formatting Errors as before.
  - The private shortest-decimal runtime facility is now also provisioned
    to `std:logging/JsonFormatter`; `TextFormatter` output is unchanged.
  - The `library/logging` corpus gains `json-formatter`.

## 0.3.256-SNAPSHOT

- `TOOL012` (guillermomolina/protos#814) bounds the Test Tool
  `conformance/standard-library/toml-official` progress group to at most 100
  Logical Cases. This is a presentation-only change: there is no
  specification, Case identity, selection, scheduling, isolation,
  classification, or aggregate-count change.
  - `RepositorySuite` remains the single progress-grouping authority. The
    toml-official descriptor is now subdivided: each retained Case presents
    under `conformance/standard-library/toml-official/<directory>`, where
    `<directory>` is its D153 selector without the final segment (the upstream
    toml-test direction and directory, for example `invalid/array`,
    `encoder/spec-1.1.0`, or `valid` for root-level Cases). The current
    corpus yields 39 subgroups of at most 76 Cases.
  - Subgroups appear in order of their first retained Case in plan order, so
    focal runs show only subgroups that own a selected Case, and failures are
    attributed to the owning subgroup. Malformed selectors and group names
    absent from the presented set fail closed.
  - `Main` records each retained Case's casePath and selector after
    selection and passes them to `RepositorySuite.progressGroupNames` and
    `progressGroupIndex`.
  - Focal tests cover the 100-Case bound, complete and unique coverage, and
    determinism over the real corpus, preservation of the `--directory` Case
    set, focal `--file` and exact `--case` presentation, and fail-closed
    selectors; the TOOL011 and TOOL004-C guards follow the new signatures.

## 0.3.255-SNAPSHOT

- `TEST009-Y` (guillermomolina/protos#795) removes the host defensive
  exception expansion that `ProtosPrelude.errorPrototype()` contributed to
  Truffle partial evaluation. There is no specification or semantic change.
  - `ProtosPrelude` retains the exact `Error` identity its constructor already
    reads and validates from the frozen prelude bindings, and
    `errorPrototype()` returns it instead of re-reading the slot through
    `Optional.orElseThrow()`. Only `Error` is retained; every other prelude
    binding keeps its lazy validation.
  - Re-diagnosing `protos-root:088d2ae81075aba8` (`Manifest.protos`) still
    fails with `CodeTooLarge`, but `Throwable.fillInStackTrace()` self size in
    the method expansion tree drops from 17303 to 11440, and
    `errorPrototype()` no longer owns any of it.
  - Focal tests cover retention of the validated identity, the unchanged
    construction failures, and the absence of eager requirements on other
    bindings.

## 0.3.254-SNAPSHOT

- `LIB014-3` (guillermomolina/protos#431) completes the D187 `std:regex/Regex`
  baseline public API with `Pattern.replaceFirst(text, replacement)`,
  `Pattern.replaceAll(text, replacement)`, and `Pattern.split(text)`. There is
  no specification change.
  - Replacement Strings accept only `$$`, `${0}`, `${N}` (decimal without
    leading zeros), and `${name}`. The template is parsed and every reference
    validated against the Pattern before any matching, so a malformed `$` or a
    reference to a missing group signals an Error even when the subject has no
    match. A group that did not participate expands to the empty String.
  - `replaceFirst` replaces the match `search` selects; `replaceAll` and
    `split` consume the same non-overlapping progression as `eachMatch` and
    `findAll`. The copy boundary is kept separate from the search position,
    so the scalar skipped after an empty match is kept: `""` replaced by `"-"`
    in `"ab"` answers `"-a-b-"`, and `""` splits `"ab"` into
    `"", "a", "b", ""`.
  - `split` omits delimiters and their captures and keeps leading, interior,
    and trailing empty fields. Output is built in one UTF-8 buffer per call
    using Unicode scalar offsets, without normalization.
  - The `library/regex` corpus gains `replacement` and `split`. Actor/Process
    transfer of Patterns and Matches remains gated by PLAT051.

## 0.3.253-SNAPSHOT

- `LIB015-B1` (guillermomolina/protos#432) adds plain-text log formatting and
  explicit text and memory sinks to `std:logging`, implementing the ratified
  LIB015-B0 contract. There is no specification change; JSON output,
  timestamps, color, and filtering or fan-out sinks remain unimplemented.
  - Events now delegate directly to `std:logging/LogEvent`, and the new
    `LogEvent.recognizes(value)` accepts exactly frozen events whose four slots
    hold valid state. A private runtime facility reads that state, including
    the attached-Error delegation chain, without invoking any behavior of the
    candidate, so lookalikes and objects that override `parent`, `slotNames`,
    `each`, `==`, or `hash` are rejected safely.
  - `std:logging/TextFormatter.format(event)` renders one line,
    `LEVEL "message" {fields} error=true`, with no terminator. Strings escape
    quotes, backslashes, LF, CR, and TAB, and render C0/C1 controls, DEL,
    U+2028, and U+2029 as `\u{HEX}`, so output stays on one physical line
    and never carries terminal control sequences. Map keys are ordered by
    Unicode scalar sequence at every depth. Floats render as their shortest
    round-trip decimal using the ECMAScript layout, plus `NaN`, `Infinity`,
    `-Infinity`, and `-0.0`. An attached Error renders only `error=true`.
  - `std:logging/TextSink(formatter, writer)` borrows an explicit TextWriter:
    `emit` calls `writeLine` and waits for its Future, so a slow writer
    suspends the caller and a failed write signals from `emit` (a Logger
    contains it). The sink never flushes or closes the writer.
  - `std:logging/MemorySink()` retains exact recognized events in emission
    order; `events()` answers a fresh frozen snapshot.
  - Fixes LIB015-A event construction and tests that called the nonexistent
    Array `add`; nested Arrays in event fields now snapshot correctly.
  - The `library/logging` corpus gains `recognition`, `text-formatter`, and
    `sinks`.

## 0.3.252-SNAPSHOT

- `LIB014-2` (guillermomolina/protos#431) adds `std:regex/Regex` matching under
  the D187 restricted portable regex contract. There is no specification
  change, and replacement and splitting remain unimplemented (LIB014-3).
  - Patterns gain `fullMatch(text)`, `search(text)`, `searchFrom(text,
    offset)`, `eachMatch(text, block)`, and `findAll(text)`. Offsets are
    Unicode scalar indexes; a non-String subject or an offset outside
    `0..text.size()` signals an Error; no match answers null.
  - Matching is leftmost-first: the earliest start wins, then ordered
    alternatives and greedy or lazy quantifier priority. Group 0 is the whole
    match, a group outside the selected path is null, and a repeated group
    reports its last participation. An optional iteration that consumes no
    input is selected under the ordinary priority and ends the repetition.
  - The engine is a Protos-owned prioritized Pike VM over the private
    compiled node table: no backtracking, no host regex engine, and no
    expansion of counted repetition. One search costs at most pattern size
    times input length. All execution state is local to each operation, so
    one Pattern can be shared by concurrent Tasks.
  - `^`/`$` follow the `m` line boundaries with CRLF as one sequence, and
    `\b`/`\B` use the D187 Unicode word set. `eachMatch`/`findAll` advance
    one scalar after an empty match, so an empty match at the end is reported
    once.
  - Known limitation: Patterns and Matches hold Closures and are therefore
    not transferable between Actors under the current runtime rules.
  - The `library/regex` corpus gains `matching`, `captures`, `assertions`,
    `repetition`, `traversal`, `complexity`, and `sharing`.

## 0.3.251-SNAPSHOT

- `LIB013-D` (guillermomolina/protos#430) adds explicit temporal text profiles:
  `std:datetime/ISO8601` and `std:datetime/RFC3339`. There is no
  specification change, and the datetime value modules are unchanged.
  - `ISO8601` is a deliberately bounded canonical LIB013 profile inspired by
    ISO 8601 extended representations, not full ISO 8601 acceptance. It parses
    and formats `Date`, `Time`, `LocalDateTime`, `OffsetDateTime`, and
    `Instant`. Years are `YYYY` (`0000`..`9999`) or `-YYYY` (`-0001`..`-9999`).
    Fractions have one to nine digits, and excess precision is rejected, never
    rounded. There are no leap seconds and no hour `24`. Offsets are `Z`,
    `±HH:MM`, or `±HH:MM:SS` within ±18:00, and `-00:00` is rejected.
    Formatting is canonical and is not normalized to UTC. `formatInstant`
    writes UTC `Z` text and signals an Error outside `Date`'s supported years.
  - `RFC3339` is a bounded adapter for `OffsetDateTime` only. It requires
    four-digit non-negative years, uppercase `T`/`Z`, and minute-resolution
    `±HH:MM` offsets. It rejects `-00:00` (unknown local offset), leap-second
    `:60`, and ten or more fraction digits. Formatting signals an Error for
    negative years and for offsets that are not whole minutes.
  - Neither profile has time-zone, time-zone database, clock, or locale
    authority. The `library/datetime` corpus gains `iso8601.protos` and
    `rfc3339.protos`.

## 0.3.250-SNAPSHOT

- `BUG019` (guillermomolina/protos#811) makes Native distributions report
  their exact packaged Protos version through `protos --version`. The Native
  launcher takes `implementation_version` from the distribution `SOURCE.txt`
  and passes it to the Native payload, overriding any caller-provided value.
  JVM distributions continue to use their JAR manifest identity, and genuine
  development executions still fall back to `Protos development`. Native
  distribution admission now requires exact version output. The fix applies
  to distributions built from this code onward; already-published artifacts
  are unchanged. There is no specification change.

## 0.3.249-SNAPSHOT

- `BUG020` (guillermomolina/protos#812) keeps the Test Tool running when one
  logical Case's execution fails without producing a Case completion. There is
  no specification change.
  - Such a Case is now recorded as a D155 Tool error for that Case and reported
    as `INFRA <case>`. The remaining Cases still run, and the invocation ends
    with exit code 3. It no longer aborts with an unattributed
    `Test tool error`. Cancellation still propagates unchanged.
  - Progress `FAIL`/`INFRA` lines name the exact logical Case
    (`<corpus>:<source>::<selector>`) instead of only its source file.
  - After the run, every `FAIL`/`INFRA` Case is listed again before the final
    totals, so failures stay identifiable in parallel (`--jobs`) output.
  - The 0.3.248 `Test case error:` line is removed. Passing Cases still
    produce no per-Case output, and scheduling is unchanged.

## 0.3.248-SNAPSHOT

- `BUG020` (guillermomolina/protos#812) makes the Test Tool name the failing
  logical Case when its execution fails without producing a Case completion.
  Before the existing `Test tool error` diagnostic, stderr now shows
  `Test case error: <corpus>:<source>::<selector>`. The Error, exit status,
  progress output, and scheduling are unchanged. There is no specification
  change.

## 0.3.247-SNAPSHOT

- `LIB015-A` (guillermomolina/protos#432) adds the structured logging core:
  `std:logging/Level`, `std:logging/LogEvent`, and `std:logging/Logger`. There
  is no specification change.
  - The levels are `TRACE`, `DEBUG`, `INFO`, `WARN`, and `ERROR`. Each one is
    its canonical String value, ordered only through `Level.compare`. There is
    no `FATAL` level.
  - `LogEvent(level, message, fields, error)` is a frozen event. Its fields
    are a deep snapshot (copied and frozen) of structured ordinary data:
    `null`, Booleans, Strings, Integers, Floats, Arrays, and normal Maps with
    String keys. Other values and cyclic data signal `Error` and are never
    converted to Strings. The attached Error is kept by identity. An event
    carries no timestamp yet.
  - `Logger(minimumLevel, sink)` is an explicit frozen logger with
    `isEnabled`, `log`, `trace`, `debug`, `info`, `warn`, `error`, and
    `with(fields)`. A derived logger's context overrides its base context,
    and call-site fields override both. A disabled call validates only its
    level, then returns without copying data, creating an event, or invoking
    the sink.
  - The only output authority is the explicitly supplied sink, which receives
    each event through `emit(event)`. An ordinary Error signalled by `emit` is
    contained and does not reach the caller. There is no global logger,
    registry, ambient stream, clock, or background Task.

## 0.3.246-SNAPSHOT

- `LIB013-C` (guillermomolina/protos#430) adds the instant and fixed-offset
  domain: `std:datetime/Offset`, `std:datetime/Instant`, and
  `std:datetime/OffsetDateTime`. There is no specification change.
  - `Offset(seconds)` is a fixed displacement in whole seconds from `-64800`
    through `64800`, not a time-zone identity. It has `compare`.
  - `Instant(nanoseconds)` is an exact signed Integer count of uniform
    nanoseconds from `1970-01-01T00:00:00Z`, with no artificial range and no
    leap seconds. It has `compare`, `addDuration`, `subtractDuration`, and
    `durationBetween`, which answers a `Duration`.
  - `OffsetDateTime(dateTime, offset)` pairs a `LocalDateTime` with an
    `Offset`. Its equality is structural, and it has no `compare`.
    `sameInstant` compares instants explicitly. `toInstant`, `fromInstant`, and
    `withOffsetSameInstant` convert with exact proleptic Gregorian Integer
    arithmetic and floor normalization before the epoch. A local date outside
    `-9999..9999` signals `Error`.
  - Every datetime family (`Date`, `Time`, `LocalDateTime`, `Duration`,
    `Period`, and the new families) now uses the D048 canonical
    factory/prototype discipline. A value's immediate parent is its module, and
    the new `recognizes(value)` checks that parent, the exact local slot set,
    and a valid state. Equality and every operation use it, so
    `Duration(5) != Instant(5)` and objects with matching slots under another
    parent are rejected. Recognition cannot observe frozenness because Core has
    no frozenness predicate; constructors always freeze. The A/B data contracts
    and API are unchanged. Values remain non-transferable across Actors.
  - No time zones, clocks, parsing, formatting, or symbolic operators are added.
  - `Date.protos`, `Time.protos`, and `LocalDateTime.protos` regain the missing
    last line of their license notice.
  - Tests: `instants.protos`, `conversions.protos`, and `families.protos` are
    added to the `library/datetime` corpus.

## 0.3.245-SNAPSHOT

- `LIB014-1` (guillermomolina/protos#431) adds `std:regex/Regex` compilation and
  escaping under the D187 restricted portable regex contract. There is no
  specification change.
  - `Regex.compile(source)` and `Regex.compileWithFlags(source, flags)` parse a
    Protos-owned dialect over Unicode scalars: literals, `\t \n \r \f`,
    `\u{HEX}`, escaped ASCII punctuation, `.`, classes with ranges, nesting,
    negation, `&&` intersection and `--` subtraction, `\d \s \w` and their
    negations, `\p{...}`/`\P{...}`, ordered alternation, numbered,
    non-capturing and named groups, greedy and lazy `? * + {n} {n,} {n,m}`, and
    `^ $ \A \z \b \B`. Flags `i`, `m`, `s`, and `x` may each appear once in
    any order. Backreferences, look-around, atomic groups, possessive
    quantifiers, recursion, conditionals, inline flags, `\G`, `\K`, `\X`,
    POSIX bracket classes, and malformed input signal an ordinary Error.
  - Unicode properties accept General_Category, Script, and Script_Extensions
    values plus the UTS #18 Level 1 binary properties, `Any`, `ASCII`, and
    `Assigned`, resolved from guarded Unicode 17.0.0 data through a private
    frozen facility provisioned only to the module. Case-insensitive classes
    are closed under simple case folding.
  - A Pattern is a fresh frozen value exposing `source`, canonical `flags`,
    `captureCount`, and a frozen `captureNames` Map; groups are numbered in
    opening-parenthesis order and named groups are also numbered. The compiled
    representation is a private postfix node table that never expands counted
    repetition and is built without host recursion. Matching operations are
    not yet provided.
  - `Regex.escape(text)` produces literal regex source valid under every flag;
    `Regex.escapeReplacement(text)` doubles every `$`.
  - The `protos/library/regex` suite-native corpus covers accepted and
    rejected syntax, flags, the Pattern value model, bounded compilation, and
    escaping; the native-boundary guard records the new facility.

## 0.3.244-SNAPSHOT

- `TEST009-W` (guillermomolina/protos#795) fixes CLI session teardown when
  Process termination leaves cooperative cancellation work queued in the RootActor
  execution domain.
  - `Session.terminate()` now enters the owning Polyglot Process Context, requests
    Process termination, and drains the RootActor execution domain before waiting
    for semantic Process terminality.
  - Process terminality and deferred Polyglot Context disposition still complete
    before the shared RuntimeHost is closed.
  - A regression covers a suspended Task whose cancellation must be drained by
    session termination itself rather than by an external caller.
  - The CLI routing architecture guard retains the required teardown ordering.
  - There is no specification change.

## 0.3.243-SNAPSHOT

- `LIB013-B` (guillermomolina/protos#430) adds temporal amounts and calendar
  arithmetic: `std:datetime/Duration` and `std:datetime/Period`. There is no
  specification change.
  - `Duration(nanoseconds)` is a fixed elapsed amount with one signed Integer
    `nanoseconds` slot holding the exact total amount. It has no calendar, zone,
    or clock meaning. The module provides `compare`, `add`, `subtract`, and
    `negate`. No Float, unit factories, parsing, or formatting are added.
  - `Period(years, months, days)` is a calendar-relative amount whose signed
    Integer components are structural and never normalized, so
    `Period(1, 0, 0) != Period(0, 12, 0)`. It has no natural order and no
    `compare`. `Period.negate` negates each component.
  - `Period.addToDate`, `Period.subtractFromDate`,
    `Period.addToLocalDateTime`, and `Period.subtractFromLocalDateTime` apply
    a period in this order: combine the year and month displacement, clamp the
    day once to the end of the resulting month, then add the days.
    Subtraction applies the negated period. Local date-times keep their `time`
    object unchanged. A final date outside `-9999..9999` signals `Error`.
  - `Date + Duration`, symbolic operators, Instant, Offset, zones, clocks,
    parsing, and formatting are not added. Values are frozen, use semantic `==`
    and a coherent `hash` as in LIB013-A, and are non-transferable across
    Actors under the same current limitation.
  - Tests: `amounts.protos` and `arithmetic.protos` are added to the
    `library/datetime` corpus.

## 0.3.242-SNAPSHOT

- `LIB013-A` (guillermomolina/protos#430) adds the pure civil temporal kernel
  `std:datetime/Date`, `std:datetime/Time`, and `std:datetime/LocalDateTime`.
  There is no specification change.
  - `Date(year, month, day)` uses the proleptic Gregorian calendar with
    astronomical year numbering (year `0` and negative years exist). Supported
    years are `-9999` through `9999`. `Time(hour, minute, second, nanosecond)`
    has nanosecond precision and rejects `second == 60`.
    `LocalDateTime(date, time)` composes exactly one Date and one Time, with no
    offset, zone, or clock state.
  - Invalid or out-of-range fields signal `Error`. Nothing is clamped,
    wrapped, or normalized.
  - Values are frozen and use the same approach as SemVer: semantic `==`
    through `alias("equals", "==")`, with a coherent `hash` so equal values work
    as Map keys. `===` stays object identity. Each module provides
    `compare(left, right)`, which answers `-1`, `0`, or `1` in natural order and
    `0` exactly for `==` values. Values with local Closure slots remain
    non-transferable across Actors under the current pass-by-value rules.
  - No clocks, zones, parsing, formatting, or arithmetic are added.
  - Tests: new `library/datetime` corpus with `construction.protos`,
    `equality.protos`, and `ordering.protos`, registered in the test tool and
    CLI.

## 0.3.241-SNAPSHOT

- `LIB012-C` (guillermomolina/protos#429) converges the Package Tool on
  `std:semver/SemVer`. There is no specification change.
  - `self:ReleaseVersion` is now a thin policy adapter. It parses through
    `SemVer.parse`, rejects build metadata, and compares through
    `SemVer.comparePrecedence`. The duplicate Package Tool SemVer parser and
    precedence comparator are removed. The Package record shape and
    DependencyConstraint policy are unchanged.
  - Tests: new `package-tool/version/release-version-std-semver.protos`.

## 0.3.240-SNAPSHOT

- `LIB012-B` (guillermomolina/protos#429) completes the `std:semver/SemVer`
  version kernel. There is no specification change.
  - `SemVer.format(version)` answers the canonical
    `MAJOR.MINOR.PATCH[-PRERELEASE][+BUILD]` text from the structured value,
    in time linear in the output length. `format(parse(text)) == text` holds for every
    accepted `text`, including unbounded Integers and build identifiers such
    as `001`.
  - `SemVer.comparePrecedence(a, b)` answers `-1`, `0`, or `1` under
    Semantic Versioning 2.0.0 precedence. Core components and numeric prerelease
    identifiers compare as unbounded Integers, numeric identifiers rank below
    alphanumeric ones, alphanumeric ones compare by ASCII octet order, and a
    longer prerelease list ranks above its prefix. Build metadata is ignored.
  - Versions answered by `parse` now define complete semantic `==`, which
    includes build metadata, and a coherent `hash`. This uses the ordinary
    `alias("equals", "==")` mechanism, so versions carry local `equals`, `hash`,
    and `==` slots. `===` stays object identity. `==` against a non-version
    answers `false`. `format` and `comparePrecedence` signal `Error` for values
    without the `parse` representation.
  - Tests: new `semver/precedence.protos`, `semver/format.protos`, and
    `semver/equality.protos` suite-native sources in the
    `protos/corpus/library/semver` plan.

## 0.3.239-SNAPSHOT

- `I079` (guillermomolina/protos#806) moves bundled-tool TOML parsing onto
  `std:toml/TOML` and retires the private `tool-shared:Toml10` engine. This
  follows D087 as amended after AUD017: there is now exactly one TOML
  implementation. There is no specification change.
  - `TOML.parseDialect(text, version)`, with `version` set to `"1.0"` or
    `"1.1"`, runs the single Standard Library parser under the selected TOML
    version. Any other `version` signals `Error`. `TOML.parse(text)` keeps
    its TOML 1.1 meaning. Under TOML 1.0 the parser additionally rejects the
    `\e` and `\xHH` escapes, inline tables that span lines, contain
    comments or end with a trailing comma, and time values without seconds.
  - The Package Tool `ManifestSchemaV1` and the Test Tool D077
    `ResourceRequirements` and `ResourceCatalog` schemas now parse through
    `TOML.parseDialect(text, "1.0")`. Their persisted schemas stay pinned to
    TOML 1.0, and schema validation and diagnostics are unchanged. The
    signed-64-bit Integer limit of the old engine is not carried over.
    Manifest schema v1 accepts only `manifest-version = 1`, so it still
    rejects every other Integer.
  - Removed `protos/tools/shared/Toml10/`, the Package Tool
    `self:TomlSyntax` and `self:TomlDocument` adapters, and their exact
    module overlays in `ProtosCli`. The Package Tool still reaches
    `std:toml/TOML` only through bundled-tool Standard Library resolution,
    never through project package resolution. The generic `tool-shared:`
    resolver namespace remains but is now unused.
  - Tests: the parser-level tests of the retired engine now live in
    `library/toml/parser-toml10.protos`, written against the public API in
    TOML 1.0 mode. New tests in `library/toml/parser-dialects.protos`
    compare TOML 1.0 and 1.1 on the same input. The Package manifest
    schema corpus and the D077 schema tests are kept.

## 0.3.238-SNAPSHOT

- `LIB012-A` (guillermomolina/protos#429) adds the Standard Library module
  `std:semver/SemVer` with a strict Semantic Versioning 2.0.0 parser,
  following the ratified LIB012-0 design. There is no specification change.
  - `SemVer.parse(text)` accepts exactly
    `MAJOR.MINOR.PATCH[-PRERELEASE][+BUILD]` and answers a fresh object with
    `major`, `minor` and `patch` slots, a `prerelease` Array and a `build`
    Array. Core components and numeric prerelease identifiers are unbounded
    Integers. Alphanumeric prerelease identifiers and every build identifier
    keep their exact ASCII spelling as Strings, so `+001` stays `"001"`.
    Build metadata is preserved.
  - Malformed input signals `Error`, following the existing Standard Library
    parsers. That includes missing components, a `v` prefix, leading zeros
    in core components or numeric prerelease identifiers, empty identifiers,
    whitespace, non-ASCII characters and non-String input. There is no
    loose, coercing or normalizing mode.
  - Parsing is a single linear pass with no length or numeric limits.
  - Precedence, canonical formatting and structural equality are left to
    LIB012-B. Ranges and requirements are deferred. The Package Tool's
    `ReleaseVersion` policy is unchanged.
  - The test tool registers a `library/semver` corpus for the new tests.

## 0.3.237-SNAPSHOT

- `TEST009-V2` (guillermomolina/protos#795) corrects the TEST009 diagnostic
  boundary: `make diagnose-truffle-root` stops at the BGV capture. There is
  no semantic, specification, compiler-policy or strict-gate change.
  - The diagnostic no longer tells the caller to open the BGV in IGV. The
    retained BGV is the handoff artifact for downstream Graal graph
    analysis, and BGV interpretation is outside the Protos tool contract.
  - A `TARGET_COMPILATION_*` result now also requires every reported BGV to
    exist and be non-empty (`EMPTY_BGV`/`MISSING_BGV` otherwise fail closed).
    The output ends with `TRUFFLE_ROOT_CAPTURE_READY=YES|NO`. When it is
    `YES`, it also prints `TRUFFLE_ROOT_CAPTURE_BOUNDARY=BGV` and one
    `TRUFFLE_ROOT_BGV=<path>` per dump. `report.json` records
    `capture_ready`.
  - Harness tests pin the handoff, the empty/missing BGV rejection and the
    absence of IGV, Docker, extra process launches and BGV parsing. The
    Makefile help and the optimization investigation reference describe the
    same boundary.

## 0.3.236-SNAPSHOT

- `TEST009-V` (guillermomolina/protos#795) replaces the TEST009-T global
  compilerability causal correlator with the standard targeted Truffle/Graal
  single-root diagnostic. There is no semantic, specification,
  compiler-policy or strict-gate change.
  - The TEST009-U evidence showed that the global model was wrong for the
    APIs it consumed. Expansion trees are per compilation, expansion
    statistics are run aggregates, and the runtime listener exposes neither
    as structured per-compilation data. `ProtosCompilerabilityTrace`,
    `ProtosCompilerabilityRootIdentity`, `ProtosCompilerabilityRecorder`,
    `ProtosCompilerabilityListenerBridge`, `ProtosCompilerabilityJson`, their
    test, `tools/truffle_compilerability_causal.py` and the
    `protos.compilerability.causalTrace` property are removed. The earlier
    TEST009-T entries remain historical.
  - `ProtosDiagnosticRootIdentity`: only while the private
    `-Dprotos.diagnostic.stableRootIdentity=true` property is set, each
    semantic root records its exact span at lowering, prints one catalog
    line, and names its call target
    `protos-root:<digest>[KIND|uri|start+length]`. The digest covers only
    kind, source URI and span, with no object hash or bytecode. A root
    without a recorded span is `protos-root:unavailable[...]` (fail closed).
    Without the property, root naming is the Truffle default.
  - `tools/truffle_root_diagnostic.py` with `make truffle-root-catalog`
    and `make diagnose-truffle-root`. The catalog lists the selectors lowered
    by one Case without compiling. The diagnose step compiles only the
    selected root (`engine.CompileOnly`, synchronous,
    `CompilationFailureAction=Print`) with one expansion view
    (`TraceMethodExpansion` or `TraceNodeExpansion`, never statistics) and
    `-Djdk.graal.Dump=Truffle:1`, into a fresh directory under
    `target/truffle-compilation/root-diagnostic/`. Results are
    `TARGET_COMPILATION_SUCCEEDED`, `..._FAILED_CODE_TOO_LARGE` and
    `..._FAILED_OTHER` (valid evidence), or `TARGET_NOT_FOUND`,
    `TARGET_SELECTOR_AMBIGUOUS` and `TOOL_ACQUISITION_FAILED`.
  - `make diagnose-truffle-compilation` is now per-Case triage only (report
    `/v4`). It enables no expansion traces, no expansion statistics and no
    causal fields. The strict SYNC/BACKGROUND gate is unchanged.
  - Real acceptance: `protos-root:67ee73cdc49e3775` (the `items` body of
    `frozenTuple` in `protos/tools/test/Manifest.protos`, compiled during the
    `closure-and-argument-surface.protos::closure form equivalence` Case)
    reported `TARGET_COMPILATION_SUCCEEDED`. The run produced two fresh BGV
    files. Both opened in IGV, showed the selected root and contained
    `After TruffleTier`.
  - Known limitations: a root with generated continuations is reported as
    ambiguous with its continuation names. Roots lowered from a Test Tool
    Case's own source were not observed to compile under the Test Tool, so
    the acceptance root came from the Tool's own sources.
  - Focused gates, the real BGV/IGV acceptance and the integrated full suite
    (`make test`) passed before this metadata was finalized.

## 0.3.235-SNAPSHOT

- `TOOL011-B` (guillermomolina/protos#803) replaces the positional Test Tool
  progress phases with repository progress groups owned by
  `RepositorySuite`. There is no specification, SuiteId, CorpusId,
  ExecutionRequirementId, CaseRef, selection, `--list-cases`, scheduling,
  result-classification or exit-code change; only progress presentation
  changes.
  - `Main.protos` no longer carries the `phaseNames` label array; the
    `[main]` group is gone. Grouping is a presentation projection over the
    final retained Logical Cases, computed after `CaseSelection` completes
    and never on the `--list-cases` path. Groups without a retained Case are
    omitted, and failures are attributed to the Case's own group.
  - Ordinary repository leaves present under their SuiteId without the
    `protos/` prefix (for example `library/uri`, `package-tool/version`),
    replacing the historical labels (`uri`, `package-tool-version`, ...).
  - `protos/conformance` is presented as ten ordered groups classified by
    the directory of `Manifest.casePath`: `values`, `object-model`,
    `control-concurrency`, `io`, `standard-library/toml-official`,
    `standard-library/toml`, `standard-library/collections`,
    `standard-library/data-text-test`, `language-surface` and
    `regression-maturity`, all under `conformance/`. A path matching no
    group or more than one fails closed; there is no catch-all group.
  - New `ProtosTestToolTool011ProgressGroupingTest`; existing progress-label
    and `Main.protos` source-anchor assertions updated accordingly.

## 0.3.234-SNAPSHOT

- `LIB010-E2-D` is the final corrective closure for `AUD005`
  (guillermomolina/protos#451) and `LIB010` (guillermomolina/protos#418).
  There is no public API, TOML semantic, specification, D104, D109, D087 or
  TOOL001/TOOL002 change.
  - F4 is explicitly deferred with no behavior change. `TOML.array` and
    `TOML.table` keep their current immediate-envelope checks and now carry
    authored documentation. That documentation does not present constructor
    success as proof that an arbitrarily constructed tree is recursively
    valid. `TOML.encode` keeps full encodability validation. A future
    decision may strengthen the constructors; this closure promises no
    compatibility for invalid constructed trees.
  - Standard Library documentation coverage has no missing public modules
    or symbols. `ProtosStandardLibraryDocumentationExtractorTest` now
    requires that for the current repository.
  - F5: the historical LIB010-D V9 changed-path gate piped the path list
    into `python3 -` while the program also arrived on stdin through a
    heredoc. It was therefore not a valid proof. Retrospective review found
    the affected interval benign. The current reusable gate
    (`scripts/validation_impact.py`) reads the delta directly from Git
    `--base`/`--head`. `scripts/test_validation_impact.py` adds a regression
    proving that stdin has no effect on how the delta is obtained.
  - F1, F2, F3 and F6 were already resolved by earlier slices.
  - The focal gates and the integrated full suite (`make test`) passed
    before this metadata was finalized. AUD005 and LIB010 are ready for
    final GitHub and durable closure after publication.

## 0.3.233-SNAPSHOT

- `AUD007-B2` (guillermomolina/protos#452) closes the parallel validation
  launcher isolation defects. No specification change and no product runtime
  change; the GITHUB003 optimistic fail-closed publication model is unchanged.
  - F1: `scripts/publication_validation.py` gives every validation a private,
    uniquely named writable Maven local repository and reads the shared one
    only as a read-only `maven.repo.local.tail`. Cleanup is bounded, the
    validation runs in its own process group, and dead-owner residue is
    recovered. `scripts/maven_local_repository_harness.py` retains the
    two-JVM proof that a shared writable repository has no multi-process
    synchronization, and that the policy leaves it unwritten.
  - F2: the real-DAP tests bind `127.0.0.1:0` and connect to the endpoint the
    instrument publishes through `ProtosGraalDapReadinessAdapter`. They share
    `ProtosDapTestSupport` instead of reserving, releasing and rebinding a port
    number. `ProtosAud007DapPortOwnershipTest` retains the deterministic
    TOCTOU proof and a source guard, and it runs in the serial Java lane.
  - F3: publication validation fails closed on untracked, or ignored but
    Maven-read (`.mvn/`), input that the selected validation can observe, and
    names the offending paths. `scripts/validation_impact.py` exposes the
    shared observability taxonomy. Paths it classifies as unobservable, such
    as `docs/`, and gitignored scratch are preserved. Dot-directory paths no
    longer alias a neutral root.

## 0.3.232-SNAPSHOT

- `LIB010-E2-C` (guillermomolina/protos#418, AUD005 F3 / #451) retains the
  official toml-test v2.2.0 TOML 1.1 corpus (commit
  `ce08da1ddb075d1c7596d663c7fcba9a2ae02c5c`, selector
  `tests/files-toml-1.1.0`) as conformance evidence for `std:toml/TOML`. No
  specification change and no public API change; D104, D109, D087 and F4 are
  unchanged.
  - The 895 selected upstream files are retained byte-exact with their MIT
    `LICENSE`, `PROVENANCE.toml`, `CASES.tsv` and `SHA256SUMS` under
    `protos/tests/conformance/library/toml/official/upstream/v2.2.0/`.
  - `tools/toml_test_projection.py` deterministically generates 27
    suite-native shards (214 valid, 214 encoder and 456 invalid logical Tests,
    884 in total) and `--check` verifies pin, inventory, hashes and
    regeneration offline. The 11 invalid fixtures whose bytes are not strict
    UTF-8 are recorded as `NOT_APPLICABLE_INPUT_DOMAIN` for
    `TOML.parse(String)`.
  - The suite-local `Harness.protos` mirrors the official comparator
    semantics; Protos-owned TOML tests keep the stronger guarantees.
  - Fixes two `TOML.parse` defects exposed by the corpus: an empty array or
    empty inline table followed by other content was rejected, and a dotted
    key inside an inline table could extend a table given as an inline-table
    value. Added Protos-owned regressions for both.

## 0.3.231-SNAPSHOT

- `AUD006-B2` (guillermomolina/protos#453) removes the recursive
  `canonicalizeCommand` from `std:cli/CommandLine.command`, so canonicalization
  execution-stack depth no longer grows with command-tree depth. No
  specification change, no public API change, no canonical CommandSpec shape
  change and no public depth limit.
  - Traversal now uses explicit linked heap frames in Protos (`enterCommand` /
    `exitCommand` plus an iterative loop). Each child is canonicalized
    completely before the parent's duplicate-name check, registration and
    storage, preserving validation and failure ordering.
  - `visiting` remains an active-path identity set: self and indirect ancestor
    cycles are rejected, completed shared descriptors are accepted, and every
    occurrence yields a fresh canonical result (no memoization).
  - Added retained regressions in `ProtosCommandLineSpecModuleTest`: a
    4096-level single-child chain (test scale only; it overflowed the stack on
    the previous recursive implementation), an indirect ancestor cycle, and a
    shared descriptor reused across two branches.
  - `parseScope` recursion is unchanged and remains for AUD006-B3.

## 0.3.230-SNAPSHOT

- `LIB010-E2-A` (AUD005, guillermomolina/protos#451) makes the
  `std:toml/TOML` encoder's temporal fraction text linear in the emitted
  length, closing audit finding F6. No specification change, no public API
  change and no change to encoded spelling.
  - `fractionText` now writes `.`, the zero padding and the coefficient
    digits into one octet buffer and decodes it once, instead of repeatedly
    prepending `"0"` to an immutable String.
  - Added a suite-native round-trip test for a 400-digit zero-padded fraction
    and a structural guard in `ProtosTomlClosureConformanceTest` against the
    quadratic prepend pattern.
  - AUD005, LIB010-E2 and LIB010 remain open (F3, F4 and F5 outstanding).

## 0.3.229-SNAPSHOT

- `TEST009-T` adds a diagnostic-only causal compilerability acquisition to
  `make diagnose-truffle-compilation`, so the residual `CodeTooLarge` failures
  can be localized per compilation before any repair. No specification change,
  no semantic change, no compiler-policy change and no dependency change.
  - New private JVM property `protos.compilerability.causalTrace=true`, passed
    only by the diagnose mode (absent from the strict SYNC/BACKGROUND gate and
    ordinary execution). When absent, no listener is registered and nothing is
    printed.
  - When enabled, a reflective `OptimizedTruffleRuntimeListener` proxy is
    installed once per JVM (`truffle-runtime` stays runtime-scope) and prints
    one `protos.compilerability.causal/v1` JSON event per lifecycle callback:
    start, Truffle tier, Graal tier, success or failure; installation failure
    is reported machine-readably.
  - Compilations get a durable root key from stable metadata only:
    continuations normalize to their source root plus resume bytecode index;
    semantic roots use a source span recorded by the lowerer only while the
    trace is enabled; untagged roots use a normalized instruction digest.
  - `tools/truffle_compilerability_causal.py` joins the events with the
    attributed method/node expansion traces and engine trace lines; the
    diagnose report becomes `protos.truffle-compilation.diagnose/v3` with
    per-shard `causal_compilations`. Ambiguous or incomplete evidence makes the
    acquisition INCOMPLETE.
  - Added focal JUnit and harness self-tests.

## 0.3.228-SNAPSHOT

- `AUD006-A4` (guillermomolina/protos#453) makes `std:cli/CommandLine.parse`
  Array accumulation linear, restoring the D115 time bound for the option,
  raw-positional and positional builders. No specification change, no public
  API change and no observable CommandLine semantic change.
  - Replaced the balanced-chunk builder (Theta(N log N) through repeated
    `Array(...left, ...chunk)` copies) with the private D101-style
    `ProtosCommandLineArrayConstructionFacility`: N amortized O(1) appends to
    an invocation-local host buffer and one O(N) materialization of a single
    ordinary standard Array; no zero-copy ownership transfer.
  - The frozen stateless builder factory is a standard initial member of the
    exact `std:cli/CommandLine` module only; `parse` captures it lexically and
    module initialization removes the bootstrap slot, so the published module
    surface remains `option`, `positional`, `command`, `parse`, `renderHelp`.
  - The facility is classified as an audited non-Core native-Closure provider;
    added focal facility evidence tests.

## 0.3.227-SNAPSHOT

- `DOC008-B` (guillermomolina/protos#560) completes the safe D067 Standard
  Library source documentation. No runtime or language semantic change and no
  specification change; only `//!`/`///` documentation comments were added.
  - Added module `//!` documentation to the 18 previously undocumented `std:`
    modules and `///` documentation to 99 top-level slot owners, so 23 of 23
    modules and 120 of 122 top-level symbols now carry authored documentation.
  - `std:toml/TOML::array` and `std:toml/TOML::table` deliberately remain
    without authored documentation pending the open semantic question routed
    to AUD005 (guillermomolina/protos#451).
  - `std:io/BufferedReader` and `std:io/BufferedWriter` receive module
    documentation only; their runtime-owned factories have no source slot
    owner.

## 0.3.226-SNAPSHOT

- `TEST002-A4` (child of TEST002, guillermomolina/protos#538) reconciles the
  legacy JSON parser Java/JUnit semantic ownership into suite-native Protos
  Test Tool coverage. No semantic change and no specification change.
  - Removed `ProtosJsonParserModuleTest`; its deep-nesting and
    chunk-boundary materialization contracts are now owned by
    `library/json/final-deep-stress.protos` (full 2048-level structural
    walk) and the new `final array materialization chunk boundary` Test in
    `library/json/final-large-materialization.protos` (indices 0, 1, 2, 31,
    32 and 63, plus guest-observable open state of parsed nodes and decimal
    payloads). Parsed Array frozen state remains owned by
    `library/json/parser-structural-errors.protos`.
  - The explicit `ProtosJsonParserStress` harness now carries its own copy of
    the former shared helpers; its stress behavior is unchanged.

## 0.3.225-SNAPSHOT

- `TEST002-A3` (child of TEST002, guillermomolina/protos#538) migrates the
  legacy Java/JUnit semantic ownership of the public `std:toml/TOML` module
  to suite-native Protos Test Tool coverage. No semantic change and no
  specification change.
  - New `protos/tests/conformance/library/toml/` cohort (data model,
    data-model errors, parser positive/errors, encoder positive/errors,
    round-trip), registered as `suite-native` in the conformance manifest.
  - Removed `ProtosTomlParserModuleTest` and `ProtosTomlEncoderModuleTest`;
    `ProtosTomlClosureConformanceTest` retains only the source-level D087 and
    host-runtime boundary check, and `ProtosTomlDataModelModuleTest` retains
    only the exact export-surface and Actor-transfer identity checks.

## 0.3.224-SNAPSHOT

- `TEST009-Q` (child of TEST009, guillermomolina/protos#795) fully reverts
  the TEST009-M M5 global exact-native-body PIC, as decided by TEST009-P.
  No semantic change and no specification change.
  - `EnterClosureCall.nativeDirect` (exact `ProtosNativeClosureBody`
    identity guard, `limit = "3"`, replaced by the generic entry) is removed
    from both `ProtosBytecodeRootNode` and `ProtosSemanticBytecodeRootNode`;
    each again has one generic `nativeCall(NativeCall)` specialization, and
    the Semantic Bytecode entry still delegates to the Bytecode owner.
  - `NativeCall.nativeBody()` and `NativeCall.enterNativeBody(body)` existed
    only for the PIC and are removed; the ordinary versus
    suspension-capable rule (structured-dispatch rejection; Task, deferred
    C-prime operation and deferred C-prime release admission;
    `ProtosSuspensionCapableNativeClosureBody` continuation entry) again has
    its single owner in `NativeCall.enterNative()`, unchanged.
  - Retained: TEST009-M M1-M4 and the TEST009-O encoding, C-prime plan
    cache-miss and physical-close host boundaries.
  - `ProtosI072PhaseDPreparedCallSeparationTest` now expects exactly one
    `NativeCall` entry specialization with parameters `[NativeCall]`.
  - Post-Q Truffle diagnostic (`closure-call-and-return.protos::plain
    closure call`, one shard worker) was acquired; its analysis belongs to
    the next TEST009 slice. TEST009 remains open.

## 0.3.223-SNAPSHOT

- `I070` (guillermomolina/protos#712) completes the remaining warning
  reconciliation: `I070-B` (#714) test sources and `I070-C` (#715)
  generated/annotation-processing output. Under `-Xlint:all`, the clean
  `mvn clean package -DskipTests` baseline (89 javac warnings: 0 main,
  62 test, 24 generated, 3 processing) now has 0 actionable Protos-owned
  warnings; handwritten main remains at 0. No semantic change and no
  specification change.
  - Tests: 33 deliberately unreferenced lifetime-only try-with-resources
    resources carry method-level `@SuppressWarnings("try")`; five fixtures
    whose `close()` declared `throws Exception` but can only throw
    `IOException` declare `throws IOException`; the actor `CarrierPool`
    fixture keeps propagating `InterruptedException` from awaiting carrier
    termination under a narrow `"try"` suppression; two redundant
    `@SafeVarargs` on reifiable `Future<?>` varargs are removed.
  - Generated: the `ComposeLocalSlots` Bytecode operand is the immutable
    non-generic `ProtosBytecodeRootNode.ComposeReservedNames` carrier instead
    of `List<String>`, eliminating the 8 DSL-generated unchecked casts.
  - Processing: test compilation runs with `<proc>none</proc>`; test sources
    declare no Truffle DSL elements and the processor generated nothing for
    them.
  - Retained, not Protos-owned (18): 16 `java.lang.ThreadDeath`
    deprecation-for-removal warnings emitted by the Truffle Bytecode DSL
    generator's exception-handling template (`resolveThrowable`,
    `handleException`) for every `@GenerateBytecode` root; 2 javac
    "No processor claimed any of these annotations" warnings for main
    compilation, inherent to javac's `processing` lint over runtime and
    nested DSL annotations. No global lint category is disabled.

## 0.3.222-SNAPSHOT

- `I070-A` (child of I070, guillermomolina/protos#713) eliminates every
  compilation warning attributable to handwritten `src/main/java` sources
  under `-Xlint:all` and the Truffle DSL processor (179 before, 0 after).
  No semantic change and no specification change.
  - Serialization: stateless exceptions that are serializable only by
    inheritance declare `serialVersionUID = 1L`; exceptions carrying runtime
    state (`ParseError`, `ProtosNonLocalReturnException`,
    `ProtosSignalException`, `ProtosBytecodeControlTransferException`) carry a
    class-scoped `@SuppressWarnings("serial")`. Their Java serializability is
    accidental and no Java-serialization contract is introduced.
  - Context helpers: the Bytecode-root `currentEnteredContext(Node)` helpers
    and `EnterNestedStructuredDispatch.structuredDispatchTarget()` are
    `@NonIdempotent`, matching the Truffle classification of
    `ContextReference.get`; `EnterNestedStructuredDispatch.direct` declares
    `excludeForUncached = true` and its never-null target cache
    `neverDefault = true`, making the existing DSL behavior explicit.
  - Binds: redundant `@Bind("$bytecodeNode")` / `@Bind("$frame")` expressions
    diagnosed by the DSL use the canonical type-inferred `@Bind`; parameters
    are retained.
  - PE guards: the LocalRangeAccessor-operand, LocalAccessor and Bytecode API
    guards accept a bare `@Bind BytecodeNode` parameter as the same structural
    proof as `@Bind("$bytecodeNode")`, with self-tests; proof strength is
    unchanged.

## 0.3.221-SNAPSHOT

- `TEST009-O` (child of TEST009, guillermomolina/protos#795) bounds the three
  shared host-heavy leaf families that became visible to partial evaluation
  after the TEST009-M exact native-body specialization, without reverting it.
  No semantic change and no specification change.
  - The TEST009-M M5 global native-body PIC (`EnterClosureCall.nativeDirect`
    with the `nativeCall` generic fallback) is retained unchanged;
    `NativeCall.enterNativeBody` remains the only ordinary versus
    suspension-capable selection authority.
  - Encoding host leaves bounded: the portable codec transformations
    (`PortableEncoder.encode`, `PortableDecoder.preview`) and the one-shot
    result assembly in `ProtosEncodingValue` are `@TruffleBoundary`.
    Receiver/arity/argument validation, octet range checks, `EncodingError`
    translation and Bytes/String materialization in
    `ProtosStandardEncodingProtocol` stay visible to partial evaluation.
  - C-prime lazy plan construction bounded on cache miss: the six
    Context-local C-prime plan caches in `ProtosLanguageContext` keep a
    visible volatile fast path and move only the synchronized
    double-checked `createPlan` publication into one `@TruffleBoundary`
    helper per family. Plans remain lazy, Context-local and reused within a
    Context; `ProtosPolyglotExecutionContextTest` pins that.
  - Physical resource close host seam bounded: new
    `ProtosHostResourceClose.closePhysically` is the shared host choke point
    used by the four NIO File `Resource.close(CloseCompletion)`
    implementations. Close admission, idempotence, closed state, callback
    order and `RuntimeException` to `I_O_ERROR` translation in
    `ProtosFileFlow` are unchanged; `ProtosHostResourceCloseTest` pins the
    helper contract.
  - Static PE guard baselines reconciled with their own generated
    candidates after TEST009-M, BUG018-C and I056 renamed or rerouted
    already-safe sites (local-range reachability and operands,
    local-accessor, bytecode-api, generated-bytecode BCI). Every adopted
    entry is `BOUNDARY_CUT`, proven, or `PROVEN_HANDLER_RETURN`; all guard
    risk counters remain zero and no guard threshold changed.
  - The strict Truffle compilation gate still fails on pre-existing
    TEST009 send/call-preparation `VIRTUAL_RUNTIME_CALL` warnings that do
    not traverse the O seams. The 21 residual code-installation-too-large
    failures are deferred to later TEST009 work.

## 0.3.220-SNAPSHOT

- `I070-A1` (guillermomolina/protos#713, parent #712) removes three stale,
  unattached documentation comments in `ProtosTask` that described retired
  legacy-evaluator replay and C-prime routing members and produced
  `dangling-doc-comments` lint warnings. The Javadoc of
  `dynamicControlState()` is preserved. No executable code, observable
  semantics, or specification text changed.

## 0.3.219-SNAPSHOT

- `I054` (guillermomolina/protos#654) publication metadata reconciliation. The
  substantive I054 change was already published in
  `9bed87430f99f3df8c2c3112f5a51b429a6e3176` without its required version bump
  and changelog entry; this release completes that metadata forward-only and
  does not re-implement it. That change retires the test-only
  `org.graalvm.polyglot:lsp` dependency for the generic GraalVM dynamic LSP,
  removes the I026-G1/G2 executable evidence (`ProtosI026GLspTransportTest`,
  `ProtosI026GLspCapabilityTest`) and the serialized-test wiring left dead in
  the `Makefile`. DAP/debugger support (`org.graalvm.polyglot:dap`,
  `protos debug`), the static Protos language server (`ProtosLanguageServer`,
  `org.eclipse.lsp4j`, static analysis core/session) and Truffle
  instrumentation (`Source`/`SourceSection`, `StatementTag`/`CallTag`) are
  preserved. Language semantics and the specification are unchanged; adopting
  the GraalVM dynamic LSP may be reconsidered later only through a new design
  decision.

## 0.3.218-SNAPSHOT

- `I056` (guillermomolina/protos#658) implements the AUD009-G1 cleanup: the
  obsolete `ProtosClosureExecutionPlan.isBytecodeBackendForRuntime()`
  discriminator is removed. Its three constant-true checks in
  `ProtosBytecodeRootNode` (inline literal call preparation, fast ordinary send
  target, and the C-prime composed-invocation guard) are dropped while the
  surrounding null and Context-projection handling is unchanged. Migration-era
  test assertions on the discriminator are removed or replaced by plan-presence
  checks. The generic Closure execution-plan boundary and the Bytecode plan
  implementation are retained; observable semantics are unchanged.

## 0.3.217-SNAPSHOT

- `I060-B` (guillermomolina/protos#663) implements D167 Candidate D: the
  buffered byte wrapper factories move from Core/Prelude to `std:io`.
  `protos/lib/core/BufferedReader.protos` and `BufferedWriter.protos` and their
  Prelude bindings are removed. Bare `BufferedReader`/`BufferedWriter` now
  signal `SlotNotFound`. New minimal `protos/lib/io/BufferedReader.protos` and
  `BufferedWriter.protos` do not redeclare the factories. Core bootstrap creates
  them through new `ProtosStandardBufferedByteIoProtocol.createReaderFactory`/
  `createWriterFactory` (reusing the unchanged installers), freezes them with
  the standard graph and registers them in the general standard-module-member
  seam. Module instances stay Actor-local while the factory identity is shared,
  and each construction still creates a fresh wrapper bound to the caller's
  execution domain. `ProtosPrelude.isStandardModuleMemberForRuntime` is a new
  exact-identity predicate over every registered member. It replaces
  `isIpFamilyPrototypeForRuntime` in Actor, P and detached transfer, so
  registered members stay exact shared anchors without any import, module
  initialization or source execution. The buffered state machines and the D117
  and PLAT031 contracts are unchanged. New
  `ProtosStandardBufferedByteIoPlacementTest` guards placement, identity, fresh
  wrappers and transfer without imports; existing buffered tests obtain the
  factory through the module-member seam.

## 0.3.216-SNAPSHOT

- `TEST009-M` (guillermomolina/protos#795) batches five compilerability
  repairs that cut post-K partial-evaluation expansion. `ProtosTextReader.scanLine`
  now appends line text through a `@TruffleBoundary` helper, so JDK
  `StringBuilder` growth and its bounds/format paths stay out of PE while line
  framing, byte accounting and decoder selection remain visible. A new
  package-private `ProtosLexicalBindingAuthorityCalls` provides bounded choke
  points for the String-keyed `ProtosLexicalBindingAuthority` residual
  (contains/read/snapshot/append/put/remove and the one-time binding handoff).
  `ProtosObjectValue` (which still answers the shared empty authority inline)
  and the deferred-authority paths of `ProtosActivation` route through them;
  frame-native ordinal paths and Context materialization timing are unchanged.
  `ProtosValueLookup.delegationParent` isolates only the generic
  `ProtosRepresentedValue.representedDelegationParent` call behind a boundary.
  `EnterClosureCall` gains a `nativeDirect` specialization that caches up to
  three exact `ProtosNativeClosureBody` identities, replaced by the unchanged
  generic `NativeCall.enterNative()` entry; both apply the single
  ordinary-versus-suspension-capable rule now owned by
  `NativeCall.enterNativeBody`, and structured calls still reject native entry.
  The generated Bytecode BCI PE baseline adopts the 93 PROVEN topology entries
  left stale by the TEST009-J structured-dispatch operations (no risk or unknown
  transitions). No semantic or specification change.

## 0.3.215-SNAPSHOT

- `BUG018-C` (guillermomolina/protos#801) makes retained-frame lexical reads
  safe across uncached-to-cached tier transitions. A frame retained by one
  activation could hold a PRESENT local while another activation of the same
  Bytecode root moved it to a cached node whose local-kind metadata for that
  local was still unset. `MaterializedLocalAccessor`/`LocalRangeAccessor`
  `getObject` then failed with `FrameSlotTypeException`. Proven
  captured-materialized reads, including the inline-callback form, now select
  the PRESENT owner frame without reading its value
  (`Select[Inline]CapturedMaterializedOwnerFrame`). They then load the value
  with the builtin `LoadLocalMaterialized` of the owner local, or take the
  unchanged generic captured lookup (`Read[Inline]CapturedFallback`).
  `ProtosFrameLexicalBindingAuthority` now retains a `BytecodeLocation` of its
  root. It reads PRESENT values behind a `@TruffleBoundary` through
  `BytecodeNode.getLocalValue`, at the index translated by
  `BytecodeLocation.update()` and at the binding's public local offset. A root
  lowering records these offsets in `ProtosFrameLexicalLayout` and validates
  them on parser replay. Presence checks, writes, D179 retargeting,
  `PRESENT(null)` and inline-callback laziness are unchanged. The
  local-range PE guard baselines follow the renamed signatures and the
  retired `getObject` sinks. Two deterministic tier-transition regressions are
  added. No specification change.

## 0.3.214-SNAPSHOT

- `I064-A` (guillermomolina/protos#667) implements D171 Candidate B: the
  public `Filesystem.captureTree` selector is removed from the standard
  Filesystem protocol (slot, `Operation.CAPTURE_TREE`, `Backend.captureTree`,
  the `ProtosFilesystemTreeObservationFlow` capture/custody-transfer machinery
  and the NIO source/captured-backend capture overrides, including captured
  subtree minting). `Filesystem.entries` is unchanged. PLAT012 package custody
  is preserved: `ProtosCapturedFilesystemCustody.captureSelectedRoot` still
  uses the secure recursive no-follow engine via `captureRootForHostCustody`
  and produces immutable captured backing. Read-only captured Filesystem
  views, same-custody verification and later use, run-owned lifetime and
  Actor-domain rematerialization are all unchanged. Captured views no longer
  expose a capture selector. ContentIdentity vectors and the F2E3B
  execution-plan cases now run Java-hosted over real host-captured custody
  instead of a guest `captureTree`. Their trees and digests are unchanged.
  Public-captureTree-only tests are retired, and absence checks are added.
  Specification revision 0.1.443.

## 0.3.213-SNAPSHOT

- `TEST009-K` (guillermomolina/protos#795) groups four compilerability
  repairs that cut partial-evaluation expansion debt: the standard
  `TextReader` queue handoff is now an iterative, non-reentrant `pump()` loop
  (finishing a request only releases the active slot), removing the
  recursive `finishQueueRequest -> pump -> advanceUntilInputOrTerminal`
  graph behind the earlier `TooDeepInlining` bailout; the residual
  String-keyed bare-write destination walks
  (`ProtosLexicalFallback.writableContextByName` and the generic
  `ResolveWritableLexicalTarget` selection) are host boundaries like
  `readByName`; `ProtosLanguageContext.currentIfEnteredForRuntime()` keeps
  the Polyglot `Context.getCurrent` probe out of PE; and the Context-local
  Bytecode plan cache hit lookup is a host boundary. Frame-native lexical
  paths and Node-owned `ContextReference` acquisition stay inline. A new
  `TextReader` regression test covers ordered, non-recursive handoff of many
  queued requests across a permanent `LineTooLong` failure. The final
  sharded diagnosis (`closure-call-and-return.protos`, 3 Cases, 2732.1 s,
  semantic corpus PASS, 0 PE-constant failures) shows all four expansion
  families absent. No observable semantic or specification change. Global
  compilerability remains red on different, later debt outside TEST009-K:
  about 23 `code is too large` bailouts per Case, one compiler
  `OutOfMemoryError`, and a new JDK-side `TooDeepInlining` reached from
  `ProtosTextReader.scanLine` through the `StringBuilder` bounds-message
  path. The full sharded diagnosis is not repeated as part of this close.

## 0.3.212-SNAPSHOT

- `I066-B` (guillermomolina/protos#669) applies D172 placement to the
  canonical IP families. The public Prelude no longer binds `IpAddress` or
  `IpEndpoint`; the same FROZEN canonical families are now exposed as
  `std:network/IpAddresses.IpAddress` and `std:network/IpEndpoints.IpEndpoint`.
  `ProtosPrelude` retains both families runtime-only and gains a general,
  immutable `ModuleKey` -> standard initial members seam, applied by every
  canonical module-creation path before cache insertion and source execution;
  module lifecycle, Actor-local caching and identity are unchanged. Actor, P
  and detached-snapshot transfer keep the families as exact standard anchors
  without importing modules, and Network/TCP validation and endpoint
  materialization read the runtime-retained families. D048 construction,
  recognition, equality and hashing are unchanged. Guest code must now import
  the families explicitly from `std:network`.

## 0.3.211-SNAPSHOT

- `BUG016-B` (guillermomolina/protos#797) shards the manual TEST009 Truffle
  compilation diagnostics by logical Case. `tools/truffle_compilation_gate.py
  diagnose` first obtains the authoritative CasePlan from the real Test Tool
  (`protos test ... --list-cases`, `protos.test.cases/v1`, parsed
  fail-closed), then runs each opaque CaseRef in its own packaged diagnostic
  JVM (`protos test ... --case <ref>`) with unchanged diagnostic options, at
  most `--shard-workers` JVMs at a time (Makefile
  `TRUFFLE_COMPILATION_SHARD_WORKERS`, default 1). Each shard keeps its own
  log and per-JVM timeout; results are aggregated fail-closed in CasePlan
  order with exact-once coverage, into the new
  `protos.truffle-compilation.diagnose/v2` report. `make
  diagnose-truffle-compilation` now takes its Test Tool arguments from
  `TRUFFLE_COMPILATION_DIAGNOSE_TEST_ARGS` (empty by default, no `--jobs`);
  the strict `check` gate is unchanged. This does not fix BUG016: a real run
  showed each diagnostic JVM using about one core for most of its lifetime,
  so the dominant cost is per-JVM and sharding multiplies it rather than
  removing it. BUG016 remains open.

## 0.3.210-SNAPSHOT

- `BUG017` (guillermomolina/protos#800) settles the P Completion after an
  unexpected host execution failure. Previously a host exception escaping
  isolated P execution on a carrier left the Completion without an outcome,
  so the producer Task stayed suspended, the result Future stayed pending,
  and a waiting root could hang forever. The carrier execution boundary now
  contains ordinary host `RuntimeException`s as a generic standard `Error`
  occurrence (no host Throwable, class or message reaches the guest and no
  new Error category is introduced); JVM `Error`s still fail the Future and
  are then rethrown to the carrier. Completion terminalization remains
  exactly-once and never overrides a winning cancellation. This repairs the
  liveness defect only; the underlying `FrameSlotTypeException` remains
  tracked separately as `BUG018` (guillermomolina/protos#801).

## 0.3.209-SNAPSHOT

- `I065` (guillermomolina/protos#668; D173 Candidate A) threads the
  policy-neutral `ProtosWorkspacePackageApplicationExecution.NetworkGrant`
  through standalone application hosting. `ProtosStandaloneHostedSession.open`
  and `ProtosStandaloneHostedExecution.executeFile` gain explicit-grant
  overloads; the existing overloads remain Network-less (`NONE`). A new
  host-aware `ProtosStandaloneHostedExecution.bootstrapProcess` overload
  provisions a granted Network from the exact application Prelude on the
  RuntimeHost that then hosts the Process; standalone sessions, the CLI
  session and the debug session now open their RuntimeHost before
  bootstrapping the Process, closing it if bootstrap fails. `NONE` never
  initializes the host Network plane. The CLI's internal session seams can
  carry a grant, but `-e`, direct-file, REPL, `protos debug` and bundled Tool
  sessions all select `NONE`; no CLI syntax, manifest key or other
  user-facing Network policy is added.

## 0.3.208-SNAPSHOT

- `I065` (guillermomolina/protos#668; D173 Candidate A) threads the
  policy-neutral `ProtosWorkspacePackageApplicationExecution.NetworkGrant`
  through `ProtosPackageRunDriver`. The existing
  `execute(request, provider)` route remains Network-less (`NONE`); the new
  `execute(request, provider, networkGrant)` lets an owning host explicitly
  select `HOST_NETWORK`. With no external requirements the grant is forwarded
  to the generation-1 `ProtosWorkspaceRunDriver` route without consulting the
  materialization provider or creating any capture, V2 plan or resource
  scope. With external requirements, requirement derivation, verification,
  planning and resource-scope reconciliation stay Network-less; only the
  application Process receives the grant, provisioned from its exact mixed
  application Prelude on the same RuntimeHost that hosts it. The CLI and its
  syntax are unchanged and receive no Network.

## 0.3.207-SNAPSHOT

- `TOOL009-G2` (parent TOOL009-G/#798; D185/#799 Candidate C′) exposes
  logical Case discovery and exact selection in the Test Tool.
  `protos test --list-cases` runs the normal TestPlan, `--file`/`--directory`
  source-scope and D153 discovery pipeline, prints a
  `protos.test.cases/v1` JSON document (`ref` plus presentation-only
  `display` per Case, in authoritative CasePlan order) on stdout, and stops
  before progress, scheduling, fresh Case Processes or Test body invocation.
  The repeatable `--case CASE_REF` option restricts execution (or listing) to
  existing discovered CasePlan entries by exact match only, intersected with
  any active source scope and kept in CasePlan order regardless of argument
  order; malformed, unsupported-version, repeated, unknown/stale and
  out-of-scope refs fail before scheduling. The frozen CaseRef V1 spelling is
  `v1.` followed by lowercase hex of the UTF-8 std JSON encoding of
  `[sourceAssociation, selector]`, so the complete logical SourceIdentity
  (including a project-tree CaseAuthority descriptor) and the D153 selector
  are its only inputs. Refs are never decoded; ExecutionRequirement,
  CaseAuthority, resource, jobs and scheduler authorities are unchanged.

## 0.3.206-SNAPSHOT

- `I065` (#668; D173 Candidate A) threads an explicit, policy-neutral Network
  grant selection through workspace application execution
  (`ProtosWorkspaceRunDriver` → `ProtosWorkspacePackageApplicationExecution`
  → `ProtosStandaloneProcessBootstrap`). With
  `NetworkGrant.HOST_NETWORK` the capability is provisioned from the exact
  application Prelude on the same live RuntimeHost that hosts the Process and
  is bound as the initial `moduleContext` local `network`. The default
  workspace route, the external-package route, Package Tool preflight and the
  CLI remain Network-less; no CLI syntax, manifest policy, ambient accessor or
  Standard Library TCP facade is added.

## 0.3.205-SNAPSHOT

- `TEST009-J` (parent TEST009/#795; trigger PERF030/#784) specializes the
  prepared structured-dispatch selection and preparation operations, in both
  generated Bytecode interpreters, by concrete prepared-call representation
  (`OrdinarySourceCall`, `NativeCall`, `ImmediateResultCall`,
  `ModuleInitializationCall`): `RequiresStructuredDispatch`, every
  `IsStructured*` and `PrepareStructured*` operation whose operand is the
  prepared call, and the semantic interpreter's `AdmitsInlineLiteral*`
  inline-literal admissions. This removes the generic `PreparedClosureCall`
  interface dispatch that kept partial evaluation from retaining the concrete
  type. `NativeCall` keeps the real structured capabilities; the other
  representations keep their absent answer and their `IllegalStateException`
  on preparation. `EnterNestedStructuredDispatch` now accepts only
  `NativeCall`, which both lowerers guarantee by emitting it solely after
  `RequiresStructuredDispatch`. `ResumeContinuation` is unchanged. A
  structural regression guard pins the absence of generic prepared-call
  receivers in both interpreters. No Protos semantic or specification change.

## 0.3.204-SNAPSHOT

- `TEST008` follow-up separates the developer validation entry points:
  `make check` now owns compilerability / partial-evaluation bailout checks
  only, including the strict Truffle compilation gate, and no longer runs
  `make test`. `make test` remains the functional validation authority over
  the Java and Protos suites. Slow-test setup and classification stay
  diagnostic telemetry: their regression, environment-comparability or guard
  verdicts do not own `make test`'s exit status, while the real Java and
  Protos test phases remain fail-closed. A repository self-test pins this
  Makefile contract against regression. No Protos semantic or specification
  change.

## 0.3.203-SNAPSHOT

- `TEST009-I` (parent TEST009/#795; trigger PERF030/#784) specializes the
  prepared Closure entry operation `EnterClosureCall`, in both generated
  Bytecode interpreters, by concrete prepared-call representation
  (`ImmediateResultCall`, `NativeCall`, `OrdinarySourceCall`,
  `ModuleInitializationCall`), removing the generic `PreparedClosureCall`
  interface dispatch at closure entry that kept partial evaluation from
  retaining the concrete type. Immediate, native, ordinary-source and
  module-initialization (immediate hit and source execution) entry keep their
  behavior, as do direct-call caching with indirect fallback, ReturnHome
  control transfer and the module-initialization failure lifecycle. Because
  ordinary and module source calls are now separate specializations, the
  direct-call cache limit of three targets applies to each independently.
  Direct Java callers enter through the representation their contract
  guarantees. No Protos semantic or specification change.

## 0.3.202-SNAPSHOT

- `TEST009-H` (parent TEST009/#795; trigger PERF030/#784) specializes the
  prepared Closure terminal lifecycle operations `CompleteClosureCall` and
  `FinishClosureCall`, in both generated Bytecode interpreters, by concrete
  prepared-call representation (`OrdinarySourceCall`, `NativeCall`,
  `ImmediateResultCall`, `ModuleInitializationCall`) so Truffle partial
  evaluation no longer retains the generic `PreparedClosureCall` interface
  call at `finish`/`complete`. Lifecycle behavior stays owned by each
  representation; direct Java callers invoke the representation's `finish`.
  The generated dispatch BCI topology baseline is updated for the resulting
  proven-constant instruction-length drift. No Protos semantic or
  specification change.

## 0.3.201-SNAPSHOT

- `TEST009-E` (parent TEST009/#795; trigger PERF030/#784) moves cold host-side
  work out of partial evaluation behind `@TruffleBoundary`, after real
  synchronous Truffle compilation of the Test Tool corpus showed it expanded
  into every compiled operation (`TooDeepInlining` through JDK reflection and
  HotSpot "code is too large" installation failures): module import
  preparation and specifier resolution (`ProtosModuleRuntime`), compact-call
  activation materialization (`ProtosFrameArguments`, the already-materialized
  fast path stays inline), the residual String-keyed lexical read
  (`ProtosLexicalFallback.readByName`), arbitrary-precision Integer arithmetic
  (`ProtosIntegerValue`, the `long` fast paths stay inline), Error handler
  selection on root crossing (`ProtosCoreErrors`, `ProtosBytecodeRootNode`),
  and dynamic-control-state inheritance (`ProtosActivation`). Evaluation
  order, Error precedence, activation identity and handler selection are
  unchanged. No Protos semantic or specification change.

## 0.3.200-SNAPSHOT

- `LM011-D1` (issue #670; D183, PLAT050 Candidate F, PLAT024) exposes the
  canonical formatter over standard LSP `textDocument/formatting`; the server
  now advertises `documentFormattingProvider`. A request formats the current
  open immutable document snapshot, including unsaved edits, through the same
  `ProtosWholeDocumentFormatter` / TOOL010 authority as `protos format`.
  `FormattingOptions` (tab size, spaces, newline preferences) never change the
  canonical style. Success yields zero edits for already-canonical source or
  exactly one full-document `TextEdit`. Invalid or incomplete source, a
  snapshot superseded while formatting, and an unopened URI all yield no
  edits; internal formatter/bootstrap failures still fail the LSP request.
  There is no disk fallback and no user-module execution. The formatter
  runtime host is request-scoped, never persistent. The `PROTOS_HOME`
  toolchain-root lookup is shared between the CLI and the LSP. Range and
  on-type formatting are not advertised and there is no TypeScript formatter.
  No Protos semantic or specification change.

## 0.3.199-SNAPSHOT

- `LM011-C` (issue #670; D184/#796 Candidate B) exposes the public canonical
  formatter command `protos format [<file>]`. With no operand it reads one
  whole UTF-8 document from stdin; with one operand it reads exactly that
  regular file (symlinks to regular files are accepted for reading) without
  executing it or resolving imports. More operands are a usage error.
  Formatting goes only through the existing editor-neutral
  `ProtosWholeDocumentFormatter` / TOOL010 authority. On success stdout holds
  exactly the canonical source and stderr is empty (exit 0). Invalid or
  incomplete source fails closed: the exact original source goes to stdout and
  a `protos format:` diagnostic goes to stderr (exit 1). Missing, unreadable,
  non-regular or malformed-UTF-8 input leaves stdout empty (exit 1). Usage
  errors exit 2, and genuine formatter/bootstrap failures stay internal errors
  (exit 70). The command never modifies files and has no multi-file, directory,
  workspace or package discovery. Check, write, range, on-type formatting and
  style configuration remain deferred. No Protos semantic or specification
  change.

## 0.3.198-SNAPSHOT

- `TEST009-C` (TEST009/#795; PERF030/#784) adds the static Bytecode API
  PE-argument guard (`make check-bytecode-api-pe`, part of `make check`). It
  checks the arguments that `BytecodeNode` local-table operations
  (`getLocalValues`/`getLocalNames`/`getLocalInfos`/`setLocalValues`/
  `copyLocalValues`), `BytecodeNode.get(Node)`,
  `BytecodeRootNodes.update(BytecodeConfig)` and `BytecodeLocation.get`
  require to be partial-evaluation constants. Sinks are found by receiver
  type and arity and followed through helpers to Bytecode DSL operations,
  Truffle DSL specializations, library exports and externally invoked node
  overrides. A boundary cut is accepted as the only safe outcome for runtime
  values, and an unproven or unresolved PE-reachable argument fails. The
  NodeLibrary tag-tree exports read the local table at a runtime tag-tree
  bytecode index, so the two tooling-only helpers that do this are now
  `@TruffleBoundary` and receive the materialized frame. Debugger-visible
  scopes, bindings and local metadata are unchanged.

## 0.3.197-SNAPSHOT

- `LM011-B2` / `TOOL010` (issue #670; D183/#791; PLAT050/#792) adds
  the first canonical whole-document Protos formatter authority. The exact
  bundled formatter under `protos/tools/formatter/` owns D183's fixed
  structural formatting policy in Protos, including 4-space structural and
  continuation indentation, canonical token/delimiter spacing, deterministic
  soft-100 wrapping, brace and sequence layout, Array/Map/Closure and trailing
  Closure forms, and canonical ordinary blank-line/final-LF handling. The B1
  source-layout bridge supplies the required structural, separator, comment and
  exact raw-spelling facts without introducing a CST, second parser/grammar or
  host-side formatter policy. Comments preserve exact raw text, ordering,
  attachment and grammar-significant boundaries; String, numeric and Unicode
  spellings remain exact, including locked triple-double lexical payload
  newlines and horizontal whitespace. The editor-neutral
  `ProtosWholeDocumentFormatter` fails closed for invalid or incomplete source,
  returning the exact original source unchanged without starting TOOL010, while
  genuine bundled-formatter failures remain host failures rather than being
  misclassified as invalid source. Focal correctness gates cover successful
  reparse, deterministic output, exact idempotence, D183 preservation
  invariants and non-executing canonical semantic-AST equivalence; the
  maintainer-reported full suite is 1328 passed, 0 failed. Public CLI spelling,
  LSP `textDocument/formatting`, VS Code integration, range/check/on-type
  formatting, recovery/partial formatting and configurable style remain
  deferred to later LM011 slices. No observable Protos language semantic or
  specification change.

## 0.3.196-SNAPSHOT

- `TEST009-A` (issue #795) adds the static
  `tools/java_local_range_operand_pe_guard.py` (with focal self-tests and an
  exact topology baseline). It proves, per indexed `LocalRangeAccessor`
  operation, that the accessor receiver and the `BytecodeNode` argument are
  partial-evaluation constants (a `LocalRangeAccessor` `@ConstantOperand` and
  a `@Bind("$bytecodeNode")` of the Bytecode DSL operation, propagated
  losslessly through helpers) on every PE-reachable path. It complements the
  PERF030 index guard, whose target is now `make check-local-range-index-pe`
  (`make test-local-range-pe-guard` remains an alias). The new guard runs
  as `make check-local-range-operands-pe`, and both deterministic guards
  (about 2 s each) now run in `make check`. The guard found five
  PE-reachable sinks whose accessor and node came from the per-invocation
  `ProtosFrameLexicalBindingAuthority` (a field and the declaring root's
  current node); its captured-read/assign and indexed-creation seams are now
  `@TruffleBoundary`. No observable language semantic or specification change.

## 0.3.195-SNAPSHOT

- `CLI008-D` (issue #312) reconciles terminal Error presentation across the
  REPL, `-e`, direct-file, workspace and bundled Tool surfaces so an available
  `ProtosDiagnosticTrace` is never silently dropped. The bundled Tool launcher
  now presents a FAILED outcome directly with its existing
  `<Tool> tool error: ` prefix followed by the occurrence's guest frames,
  instead of rebuilding a trace-less `ProtosSignalException`; the `-e` and
  direct-file `ProtosSignalException` fallbacks render an attached terminal
  trace like the REPL already did. All surfaces share one summary-plus-trace
  presenter. Summary prefixes, program stdout, exit-code policies, COMPLETED
  Test Tool classification and CANCELLED/host runtime-error handling are
  unchanged; no trace is recaptured in the CLI. No observable language
  semantic, specification, print, serialization, PLAT049 capture, Test Tool or
  Package Tool change.

## 0.3.194-SNAPSHOT

- `BUG015` (issue #794) fixes a ready-before-suspend lost wakeup in
  `ProtosParallelRuntime.ownedFuture()`. When the P outcome became ready after
  the producer Task's pending check but before `suspend()`, `suspend()` returned
  `false` and left the Task `RUNNING`, yet the continuation returned anyway; the
  Task was then neither running, suspended, runnable nor terminal, and
  `dispatchUntilTerminal()` could wait forever (observed as an intermittent
  `array-parallel-sort.protos` stall under `--jobs 8`). The producer now returns
  only on a true suspension, consumes the ready outcome while still `RUNNING`,
  and returns without consuming it when cancellation won and the Task was
  re-enqueued, matching the existing `Future.then()` contract. Deterministic
  Java regressions cover both the ready and the cancellation-wins paths through
  a package-private test seam. No observable language semantic or
  specification change.

## 0.3.193-SNAPSHOT

- `PERF030-N` (issue #784, `F3_GENERIC_NAME_TO_RANGE_INDEX`) cuts the remaining
  three PE-reachable runtime-name-derived `LocalRangeAccessor` sinks at the
  generic frame-backed lexical membership/read boundary. `containsBinding(String)`
  and `readBinding(String)` are now `@TruffleBoundary` slow paths for genuinely
  dynamic names, while statically selected lexical execution continues to use
  the existing indexed/ordinal seams. The final LocalRange PE inventory remains
  36 sinks with 13 PE-reachable proven-constant sites, 19 boundary cuts,
  4 non-PE-reachable sites, 0 PE-reachable risks and 0 unknown reachability.
  `PRESENT(null)` versus `ABSENT`, D179 late-nearer retargeting, capture by
  reference, current-`BytecodeNode` coherence, dynamic overflow behavior,
  deferred Context laziness and debugger/reflection projection are preserved.
  No observable language semantic, specification or benchmark change.

## 0.3.192-SNAPSHOT

- `CLI008-C1` (issue #416, PLAT049 Candidate C) adds an occurrence-carried,
  bounded guest diagnostic trace for terminal Errors. When an Error
  occurrence fails a Task (or escapes a REPL unit), its Truffle guest stack
  is projected once, before the throwable is discarded, into an inert
  `ProtosDiagnosticTrace` of at most 64 semantic guest frames (innermost
  kept, `truncated` flag). Only `ProtosSemanticBytecodeRootNode` frames are
  projected; continuation roots normalize to their semantic source root,
  structured-dispatch/C-prime, host and scheduler frames are excluded, and
  frames live at the capture point (the Task's host) are cut off. PLAT044 B′
  inline callbacks contribute exactly one frame each through their nested
  `RootTag` (source information plus `RootTag` are materialized lazily only
  for a root failing inside a live inline region). The trace is stored on
  the failing Task at its first failure commit (surviving child drain), is
  never forwarded to the associated Future, and travels as optional metadata
  on FAILED `ProtosExecutionOutcome` without changing `error()` identity.
  The CLI (`-e`, file run, workspace `run`, REPL) prints `  at
  <source>:line:column` lines after the existing `Error:` summary on stderr.
  Success paths do no new diagnostic bookkeeping; the Error value is never
  mutated. No observable language semantic or specification change.

## 0.3.191-SNAPSHOT

- `PERF030-M` (issue #784, `F4_PE_VISIBLE_LIFECYCLE_RANGE_SCANS`) moves the
  10 PE-visible lifecycle `LocalRangeAccessor` scans in
  `ProtosFrameLexicalBindingAuthority` and
  `ProtosInlineCallbackFrameBindings` behind dedicated `@TruffleBoundary`
  helpers. General establishment-order materialization, adoption of already
  PRESENT frame-backed bindings, and authority handoff now perform their full
  range scans outside partial evaluation while preserving the existing cheap
  guards and current-`BytecodeNode` resolution where applicable. Inline
  durable activation keeps the `frameBindingsTransferred()` fast guard outside
  the boundary and materializes the frame only for the first transfer so the
  slow helper receives a `MaterializedFrame`. No `@ExplodeLoop` is introduced.
  The final LocalRange PE inventory remains 36 sinks: 13 PE-reachable proven
  constant, 3 PE-reachable risks, 16 boundary cuts, 4 non-PE-reachable and 0
  unknown; only `F3_GENERIC_NAME_TO_RANGE_INDEX` remains PE-reachable risk.
  Establishment order, compact/general transition, `PRESENT(null)` versus
  `ABSENT`, remove/recreate order, authority handoff, inline activation
  laziness and first-observer transfer, and debugger/tooling observation are
  preserved. No observable language semantic, specification or benchmark
  change.

## 0.3.190-SNAPSHOT

- `LM011-B1` (issue #670, D183 Candidate B, PLAT050 Candidate F) adds the
  on-demand editor-neutral source-layout and source-preservation foundation for
  the future canonical Protos formatter. Exact immutable source remains the raw
  spelling authority while tooling-only demand can retain horizontal
  whitespace, line comments and block comments without changing the ordinary
  token stream; logical newlines remain parser tokens and block-comment
  internal newlines remain non-parser newlines. The canonical parser can
  additionally expose source-only sequence separator kind, bare versus
  parenthesized single-parameter Closure form and trailing-Closure origin.
  `ProtosSourceLayoutView` groups the exact snapshot, canonical
  `TokenOccurrence`s and existing Surface AST with immutable trivia/source
  facts, source-position-independent structural paths and deterministic
  `OWN_LINE`, `END_OF_LINE` and `EMBEDDED_BETWEEN_TOKENS` comment
  attachments. Its D183 preservation projection retains raw token/comment
  spellings, structural/grouping/Array/Map forms, separator kinds, Closure
  forms and trailing-Closure relationships without using source offsets as
  structural identity. The ordinary lexer/parser/static-analysis path retains
  no formatter-only metadata and allocates no sequence-separator `SourceSpan`
  when source facts are not requested. No formatter transformation, CLI/LSP
  integration, style configuration, CST/lossless syntax layer, language
  semantic change or specification change is introduced.

## 0.3.189-SNAPSHOT

- `PERF030-L` (issue #784) removes the PE-visible frame-local rescan from
  `ProtosFrameLexicalBindingAuthority.isEmpty()`. Empty-state queries now use
  the authority's existing establishment metadata in O(1): general mode reads
  `establishmentOrder`, while compact mode reads `lastCompactFrameOrdinal`;
  the defensive dynamic-overflow check is retained. Focused regression
  coverage verifies non-empty, empty and recreate transitions in both compact
  and general modes, including remove/recreate ordering. The LocalRange PE
  guard removes exactly the F5 `isEmpty()` sink from both baselines, leaving
  36 sinks: 13 PE-reachable proven constant, 13 PE-reachable risks, 6 boundary
  cuts, 4 non-PE-reachable and 0 unknown. Observable language semantics,
  specification behavior and benchmarks remain unchanged.

## 0.3.188-SNAPSHOT

- `PERF030-K` (issue #784) removes the structurally unreachable inline
  captured-access range probe from `ProtosInlineCallbackFrameBindings`.
  Binding analysis and lowering already restrict these direct operations to
  `CapturedResolved` owners in established outer lexical scopes, so
  `admitsCapturedAccess` now depends only on an unmaterialized activation and
  positive lexical depth instead of resolving the runtime name back into the
  callback's current frame layout. Focused binding-analysis coverage makes the
  ownership invariant explicit: an outer established name is
  `CapturedResolved`, while a declaration owned by the current scope is
  `Candidate` before its creation and `Resolved` afterwards. The LocalRange PE
  guard removes exactly the obsolete F6 sink from both baselines, leaving 37
  sinks: 13 PE-reachable proven constant, 14 PE-reachable risks, 6 boundary
  cuts, 4 non-PE-reachable and 0 unknown. Capture by reference, D179 late
  nearer-binding behavior, inline activation laziness, pre-RHS captured-write
  destination selection, selected-owner mutation behavior, and observable
  language semantics remain unchanged. No specification or benchmark change.

## 0.3.187-SNAPSHOT

- `TOOL001-F2E5` (issue #93, PLAT048 B′) completes the current bounded
  external immutable-package run closure by adding an implementation-private,
  read-only exact local materialization backend and routing public
  `protos run <entry> [args...]` through `ProtosPackageRunDriver`. The backend
  derives one deterministic opaque private path from the complete typed exact
  identity (registry/Git kind, PackageId, exact version/revision and
  ContentIdentity), performs no directory enumeration, alternative lookup,
  fetch, network access, lock mutation, package-store writes, repair or
  garbage collection, and fails closed when the exact already-present root is
  missing. The selected root still passes unchanged through the existing F2E2
  capture+ContentIdentity verification, F2E3 planning and F2E4
  detach/reconciliation before application execution. Workspace-only public
  runs remain on the generation-1 path and do not consult the materialization
  provider. The physical materialization root/key layout is implementation
  private and introduces no CLI option, environment variable, public
  configuration or canonical package-store contract. Focused coverage verifies
  complete-identity separation, fail-closed missing materializations,
  workspace-only zero lookup and a public mixed run that imports and executes
  source from an exact verified external package. No language or specification
  semantic change.

## 0.3.186-SNAPSHOT

- `PERF030-J` (captured-owner constant ordinal pipeline, issue #784) carries
  the statically proven frame-layout ordinal for captured frame-native reads,
  captured writable-destination resolution, and post-RHS captured assignment
  as an `int` `@ConstantOperand` on both ordinary and inline Bytecode
  operations. `CapturedLexicalWriteTarget` now retains the exact owner and
  authority selected before RHS evaluation without retaining the ordinal, so
  assignment still mutates exactly that preselected destination and never
  re-resolves after RHS effects while the `LocalRangeAccessor` index comes
  from operation-constant structure. The static PE reachability guard
  reclassifies the three F2 sinks (`hasFrameBackedBindingAt`,
  `readFrameBackedBindingAt`, and `assignFrameBackedBindingAt`) from
  `PE_REACHABLE_RISK` to `PE_REACHABLE_PROVEN_CONSTANT`, leaving 13 proven
  constant sinks, 15 PE-reachable risks, 6 boundary cuts, and 4 non-PE-
  reachable sinks. Captured-by-reference behavior, late-nearer retargeting at
  resolution time, selected-owner removal failure, CLOSED/FROZEN mutation
  rules, and inline activation laziness are unchanged. No semantic or
  specification change.

## 0.3.185-SNAPSHOT

- `TOOL001-F2E5` (issue #93, PLAT048 B′) adds the CLI-neutral
  `ProtosPackageRunDriver`, composing the exact external requirements preflight
  with a host-supplied `ProtosExactPackageMaterializationProvider` (complete
  exact identity -> one already-present selected root). When there are no
  external requirements the run is the unchanged generation-1
  `ProtosWorkspaceRunDriver` route and the provider is never consulted.
  Otherwise each requirement, in lock order, gets exactly one provider lookup
  and one F2E2 capture+verification, then F2E3 planning, F2E4 detach and
  resource-scope reconciliation, and one mixed V2 application Process. The
  driver owns every verified custody until reconciliation succeeds (provider
  miss, verification, planning, detach or reconciliation failure closes them
  all, with no application Process started); afterwards the scope owns them
  and is closed only after the application Process has TERMINATED.
  `VerifiedExternalPackage.fromIdentity` builds planning inputs from exact
  identities, and the application bootstrap of
  `ProtosWorkspacePackageApplicationExecution` is shared through a small
  `executeEntry` helper with unchanged workspace behavior. No default
  materialization backend, CLI, specification or semantic change.

## 0.3.184-SNAPSHOT

- `PERF030-I` (lowerer-known establishment ordinals, issue #784) makes every
  frame-layout ordinal the lowerer already knows reach its
  `LocalRangeAccessor` access as an `int` `@ConstantOperand` instead of a
  dynamic `emitLoadConstant` operand. This covers the frame-native parameter,
  rest and current creation operations of both Bytecode roots, the
  persistent-authority indexed parameter and rest operations, and the
  inline-callback parameter and creation operations. Frame-native multiple
  creation no longer indexes an ordinal array at run time: it is lowered to
  one observation of the complete fixed source prefix
  (`ObserveMultipleCreatePrefix` / `ObserveInlineMultipleCreatePrefix`)
  followed by one scalar constant-ordinal creation per name, in source order,
  with no rollback. The array-based `MultipleCreateFrameLocals`,
  `MultipleCreateInlineFrameLocals` and inline `multipleCreate` are removed.
  The static LocalRangeAccessor PE guard now classifies the six affected sinks
  as `PE_REACHABLE_PROVEN_CONSTANT` (10 proven, 18 remaining risks); the
  reachability baseline is updated accordingly. No semantic or specification
  change.

## 0.3.183-SNAPSHOT

- `TOOL001-F2E5` (exact external requirements preflight, PLAT048 B′) adds the
  bundled Package Tool operation `ExecutionPlan.exactExternalRequirements`. It
  validates the current lock against the current ResolutionRoot (header, root,
  workspace membership and workspace-declared dependencies) and returns one
  `{ref, content}` per locked registry/Git node, before any external package
  is captured. Exports and external-declared dependencies stay with the later
  F2E3 planning over the same verified captures. The current-lock, external-node
  index and workspace-dependency checks move into shared helpers used by
  `buildV2FromVerifiedCaptures` as well; the V2 planner's behavior is unchanged.
  The host-side `ProtosExactExternalRequirementsPreflight` runs that operation in
  a fresh Package Tool Process over a read-only confined project Filesystem. It
  detaches the result into a list of `ProtosExactExternalPackageIdentity` values,
  using the exact V2 ref/content shape checks, and rejects duplicates only by
  complete exact identity. The process is terminated before the call returns,
  and the result holds no Path, locator, Filesystem, custody or guest value.
  No materialization provider, public-run wiring, CLI or specification change.

## 0.3.182-SNAPSHOT

- `TOOL001-F2E4` (mixed V2 resolver and lazy external source loading) adds
  `ProtosPackageExecutionPlanV2ModuleResolver`. It routes `self:`, `dep:` and
  `std:` over one detached mixed workspace/registry/Git PackageExecutionPlanV2
  graph using immutable exact-NodeRef, exact-external-identity and
  `declaring NodeRef + alias -> target NodeRef` indexes, so distinct versions,
  revisions, registry/Git kinds and same-content identities never collapse.
  Importers are identified only from canonical workspace or external
  ModuleKeys; `dep:` always goes through the exact edge and target exports.
  Workspace sources keep the unchanged generation-1 physical rules. External
  sources are read lazily per load from the borrowed
  `ProtosExternalPackageResourceScope`, decoded as strict UTF-8 and returned
  without a physical path; no source/store Path is reopened and nothing is
  cached. The resolver never closes the scope; loads fail after its owner
  closes it. No public-run wiring, CLI or specification change.

## 0.3.181-SNAPSHOT

- `TOOL001-F2E4` (verified-custody resource scope) implements the PLAT012
  authority/lifetime layer for external packages. `CapturedBackend` gains a
  host-only `readRegularResource` projection, and the captured-tree backend
  implements it over its existing tree, blobs and leases. It reads exact
  regular resources by a package-relative `ProtosPackageResourceName` without
  Activation, guest Filesystem, Process, Actor, Context or any source/backing
  Path. Absolute, `.`/`..`, empty and NUL names are rejected, and links,
  other entries, directories and missing entries fail closed. Each read
  returns a detached copy and uses its own channel, so independent reads run
  concurrently. `ProtosCapturedFilesystemCustody.readResource` fails after
  close. `ProtosExternalPackageResourceScope.reconcile` reconciles the detached
  PackageExecutionPlanV2 external identities exactly 1:1 with verified
  custodies (via the new `VerifiedExternalPackage.identity()`), keyed by the
  full exact identity. Missing, extra, duplicate or mismatched identities and
  reused custodies fail closed with no partial scope. A successful scope owns
  its custodies and closes each exactly once, idempotently. Planning remains a
  borrower. No resolver, source decoding, cache or public-run wiring is added,
  and no observable semantics change.

## 0.3.180-SNAPSHOT

- `TOOL001-F2E4` (first slice) adds the host side of D053/PLAT012 external
  package identity. `ProtosPackageExecutionPlanV2Adapter` defensively detaches
  the ordinary-Protos PackageExecutionPlanV2 into the immutable host
  `ProtosPackageExecutionPlanV2`. The detach requires exact shapes and typed
  workspace/registry/Git NodeRefs, carries ContentIdentity only on external
  packages and location only on workspace packages, and keeps nodes unique by
  exact NodeRef, so several exact versions or revisions of one PackageId may
  coexist. It also checks root and dependency closure and rejects duplicate
  `(declaring, alias)` pairs. `ProtosExactExternalPackageIdentity` represents
  `(kind, PackageId, exact version or revision, ContentIdentity)`.
  `ProtosExternalPackageModuleKey` is a strict canonical `ProtosModuleKey`
  codec whose domain is disjoint from workspace keys. It encodes that identity
  plus the internal logical module and excludes aliases, exports, locators and
  physical provenance. Generation-1 detach is unchanged. No resolver, custody
  index, source loading or public-run wiring is added, and no observable
  semantics change.

## 0.3.179-SNAPSHOT

- `PERF030` establishes a statically proven target-less creation of a root
  that installs its persistent frame authority through the new
  `CreateCurrentIndexedLocalSlot` operation, whose layout and frame ordinal are
  Bytecode DSL constant operands taken at lowering time. While that root's
  authority admits creation and stores the expected layout, the binding is
  created at its known ordinal (still rejecting a PRESENT binding as a
  duplicate creation); any other state takes the unchanged named
  `CreateCurrentLocalSlot` path. The ordinary path no longer resolves the frame
  ordinal from the runtime binding name before its frame-local accesses, which
  partial evaluation could not reduce to a constant. No observable semantics
  change.

## 0.3.178-SNAPSHOT

- `I058` implements D160 (Candidate B): the parallel Array algorithms move from
  Core to the Standard Library. `std:collections/Array` adds the module
  functions `parallelMap`, `parallelFilter`, `parallelFindIndex`,
  `parallelReduce`, and `parallelSort`, each taking
  `(array, callback, ...arguments)` and returning a Future. They are ordinary
  Protos source composed only from `Closure.parallel`, Futures, `Future.all`,
  and `ensure`: a synchronous P snapshot of elements, callback, and arguments
  at call time; one isolated P child per callback invocation; results in
  source order; lowest-index failure selection; outcome validation
  (`InvalidPredicateResult`, `InvalidComparatorResult`,
  `InvalidComparatorOrder`) in the caller in ascending index order; and
  cancellation of unfinished children when the result Future is cancelled.
  `parallelReduce` combines adjacent pairs round by round, and `parallelSort`
  reproduces the stable sequential `sort` with each comparator invocation in
  its own P domain.
- The Core Array prototype no longer has `parallelMap`, `parallelFilter`,
  `parallelFindIndex`, `parallelReduce`, or `parallelSort`, and no
  compatibility alias exists; calling them on an Array now signals
  `SlotNotFound`. `ProtosParallelRuntime` drops `installArrayParallel`, the
  native indexed/reduce/sort algorithms, their staging/transfer helpers, and the
  Completion forwarding/abandon/cancel-action machinery, leaving only
  `Closure.parallel`. The native-boundary audit shrinks accordingly.
- The isolated-parallel example and the canonical `parallel-array-map`
  benchmark use `Arrays.parallelMap(...)` and the other module functions.

## 0.3.177-SNAPSHOT

- `PERF030` makes the statically known `CreateCurrentFrameLocal` frame-local
  ordinal a Bytecode DSL constant operand, as PERF029 did for
  `BindClosureFrameParameter`. Lowering no longer emits that ordinal as a
  runtime stack operand, so every `LocalRangeAccessor` access it drives sees a
  partial-evaluation constant index. This addresses the permanent
  partial-evaluation constant-index bailout identified as `8231|Pi`. The
  compact-call direct creation path and the activation fallback (including
  duplicate creation Errors, OPEN/frozen/conflict rules, and the returned
  value) are unchanged. No semantic change.

## 0.3.176-SNAPSHOT

- `I057` implements D161: removes `ByteRegion` and writable parallel-range
  reservations. Standard `Bytes` no longer installs `parallelRange`, so sending
  it follows ordinary missing-selector lookup (`SlotNotFound`). The
  `ProtosByteRegionValue` runtime family is deleted. `ProtosBytesValue` loses
  all reservation state and checks, and `Bytes.at`/`atPut`/`add`/`removeAt`
  have no reservation-dependent failure path. `ProtosParallelRuntime` drops
  region creation, reservation, the commit-on-publication and release-on-cancel
  hooks, and `ProtosFutureValue.resolveWithCommit`. Ordinary
  `Closure.parallel`, Array parallel operations, P snapshot/result transfer, and
  Future cancellation are unchanged. The Core Error prototypes
  `ParallelRegionOverlap`, `ParallelRegionInUse`, and `ParallelRegionOutsideP`
  are removed from the taxonomy, the prelude, and the Test Tool error-kind
  mapping. The ByteRegion-specific Actor/detached transfer exclusions and the
  diagnostic projection are also removed. The retired reservation unit test is
  deleted, and a conformance test now guards the removed surface. Implements
  specification `0.1.439`.

## 0.3.175-SNAPSHOT

- `PERF025` repairs the slice 3 inline callback consumer evidence. A
  non-canonical guarded Integer send inside a frame-native inline callback (for
  example `n < 3`) now prepares its native invocation with the
  provenance-equivalent caller, as the ordinary-send and direct-Closure-call
  consumers already do, instead of materializing the callback activation. The
  focal test now warms each scenario past the Bytecode DSL uncached-interpreter
  threshold before observing the specialized cached tier, and reads bindings
  from the durable authority once they have been transferred. No observable
  semantics change.

## 0.3.174-SNAPSHOT

- `I063` follow-up: removes stale append wording from the
  `ProtosStandardFilesystemProtocol` Javadoc (File capability descriptors match
  the captured read/write authority; captured-tree backends reject
  write/create/truncate opens). No behavior change.

## 0.3.173-SNAPSHOT

- `I063` implements D170 by removing the standard File append institution.
  `Filesystem.open` no longer accepts an `append` option: it is now an ordinary
  unknown option and fails with `InvalidIOArgument` before backend authority is
  exercised. `ProtosFilesystemOpenOptions` drops its write-placement state, and
  `ProtosFileFlow` drops `AppendWritableResource`, `AppendCompletion`, the
  append-only write path, the append capability flag and the cross-alias
  append-placement contract. `ProtosStandardFileProtocol` now has a single
  `create` factory. Every writable File writes at its own logical position.
  `ByteSeekable` (`position`, `seek`, `seekBy`, `seekToEnd`), `ByteSized`,
  `Truncatable`, `Syncable`, truncate-on-open and the generic
  commitment/cancellation/lifecycle machinery are unchanged, and backends still
  expose only the capabilities they can honestly support. The Standard Library
  `Files` helper no longer passes `append: false`.

## 0.3.172-SNAPSHOT

- `PERF029` removes the two permanent Truffle partial-evaluation bailouts
  reached by an ordinary source-backed Closure call. The frame-native
  `BindClosureFrameParameter` operation now receives its local ordinal as a
  Bytecode DSL constant operand, so every `LocalRangeAccessor` access it
  performs uses a partial-evaluation constant index. The name-keyed
  `ProtosFrameLexicalBindingAuthority.putBinding`, whose frame ordinal is
  resolved from a runtime name (for example while an authority handoff
  migrates existing bindings), is now a Truffle boundary instead of being
  partially evaluated. Observable Protos semantics are unchanged.

## 0.3.171-SNAPSHOT

- `I065` lets a fresh application Process executed on a caller-supplied
  `ProtosPolyglotRuntimeHost` carry an explicit, already-selected Network grant
  into the existing D047 bootstrap authority model: the grant binds the initial
  `moduleContext` local `network` slot, and without a grant the slot remains
  absent. The temporary-host convenience overload rejects a request carrying a
  grant before any guest execution, so a Network is never used by a Process
  hosted outside the RuntimeHost that provisioned it. Captured tool Processes
  remain Network-less. No CLI, workspace or session selection syntax is added.

## 0.3.170-SNAPSHOT

- `I078` records the D180 Candidate B rename of the standard Closure pre-test
  loop selector from `while` to `whileTrue`, as already represented by the
  current product state. `condition.whileTrue(body)` is the only standard
  pre-test loop selector: the standard `while` selector is removed with no
  compatibility alias, and no standard inverse `whileFalse` selector is
  introduced. The rename preserves every non-name D044 semantic (receiver and
  argument validation, exact canonical Boolean condition results, pre-test
  order, `null` result, Error/non-local-return/suspension/cancellation
  propagation, and no implicit Future adoption). Ordinary lookup, shadowing,
  extraction and override of the `Object.whileTrue` slot are unchanged, and
  user-defined selectors named `while` remain ordinary members. No dedicated
  loop syntax, truthiness or selector intrinsic is introduced. Repository-owned
  standard-loop call sites, the normative specification, runtime protocol
  publication, conformance and Java tests, and documentation were migrated
  coherently.

## 0.3.169-SNAPSHOT

- `PERF025` specializes the residual consumers of a frame-native PLAT044 B′
  inline callback so that their successful path no longer materializes the
  callback's semantic activation. New carrier-operand operations take the
  invocation's `PreparedInlineLiteralCall` for statically resolved captured
  reads, PERF028-A captured write-target selection and assignment, canonical
  guarded Integer sends, guarded and fast ordinary source sends, direct
  source-Closure calls, `this`, member reads (both PIC tiers) and fixed-prefix
  multiple creation. Selection uses the invocation's prelude; a child
  invocation is prepared with the callback's compact caller only when the
  callback would inherit every provenance field from it, otherwise the
  callback activation is materialized. Nearer-binding (D179), absent-binding,
  non-canonical, structured, generic and Error paths still materialize the
  activation through the existing durable transfer and run the unchanged
  operations. `context`, debugger scopes and non-local return remain
  observers. No observable semantics change.

## 0.3.168-SNAPSHOT

- `PERF025` lazily materializes the semantic activation of PLAT044 B′ inline
  literal callbacks. The inline region now keeps the invocation's
  `PreparedInlineLiteralCall` carrier instead of an eagerly built
  `ProtosActivation`; the callback's one fresh activation is materialized
  from the carrier's compact frame arguments at most once, by the first
  semantic or tooling observer, and every later observer of the same
  invocation, including resumption after a yield, receives that same
  instance. For callbacks whose bindings are frame-native, parameter binding,
  arity checks, current-local creation, resolved reads and PERF028-A resolved
  writes operate on the callback's block locals through new carrier-operand
  operations and never materialize the activation on their ordinary path. The
  activation of such a callback is only ever handed out through the new
  `MaterializeInlineCallbackActivation` operation (and its tooling
  counterpart), which, before returning it, moves the PRESENT block-local
  bindings in layout order into a fresh durable map-backed authority installed
  on the activation and relinquishes the block locals; no Context can
  therefore observe, or keep aliasing, the block locals that the next
  invocation reuses. The debugger projects that durable activation instead of
  a suspension-local snapshot. D179 presence semantics, destination selection
  before RHS evaluation, FROZEN/mutation Errors, arity Errors, non-local
  return, suspension, B′ admission and the context-observing fallback path are
  unchanged. New PERF025 focal coverage freezes lazy entry for zero-, one- and
  two-parameter callbacks, at-most-once and per-invocation identity, durable
  transfer before the first observer returns, non-aliasing after region reuse,
  on-demand debugger materialization, suspension and non-local return.

## 0.3.167-SNAPSHOT

- `PERF025` replaces eager O(n) `IdentityMap` snapshot copying with a
  generation-backed copy-on-write representation while preserving the existing
  shallow logical snapshot contract and exact semantic-identity lookup.
  `keyedSnapshot()` now publishes and reuses a read-only insertion-order view
  of the current generation, while `associationSnapshot()` projects the same
  generation without allocating one host `Map.Entry` pair per association.
  Once either snapshot is published, the first later append, replacement or
  removal detaches the live map to a fresh generation with fresh entry objects
  and rebuilt recorded-identity-hash buckets; previously published generations
  therefore retain their exact representative key references, mapped value
  references, membership and order. Mutations before publication and subsequent
  mutations before another publication remain in-place, so never-snapshotted
  maps pay no copy-on-write cost. Standard `IdentityMap.each` retains the stable
  generation view directly instead of immediately copying it again. Existing
  exact recorded-hash bucket lookup, semantic `===` collision resolution,
  remove/reinsert ordering, open/closed/frozen behavior, Actor transfer and
  isolated-P transfer remain unchanged. New PERF025 focal coverage freezes
  generation reuse, detach-on-first-mutation, historical snapshot stability,
  collision-bucket preservation, read-only exposure and P-isolation after
  capture. Existing affected `each`, physical-index, Actor-transfer and
  collection-state regressions plus the full test suite remain green. This is
  an internal physical-representation optimization only: no observable Protos
  semantic, specification or platform decision changes, and no performance
  magnitude is claimed.

## 0.3.166-SNAPSHOT

- `PERF025` replaces eager O(n) Array snapshot reference copying with a
  generation-backed copy-on-write representation while preserving the existing
  shallow logical snapshot contract. `indexedSnapshot()` now publishes and
  reuses a read-only view of the current indexed generation without copying the
  complete element-reference sequence; the first later indexed replacement
  detaches the live Array to a fresh generation before writing, so previously
  established snapshots retain their exact captured references and order.
  Replacement before snapshot publication remains in-place, repeated snapshots
  of an unchanged generation reuse the same view, constructor input remains
  defensively owned, and open/closed/frozen mutation behavior plus validation
  ordering remain unchanged. Existing Array iteration, matching, Actor transfer,
  isolated-P transfer and interop consumers continue through the same snapshot
  API. New PERF025 focal coverage freezes generation reuse, detach-on-first-write,
  old-snapshot stability, read-only snapshot exposure and state-boundary
  behavior. This is an internal physical-representation optimization only: no
  observable Protos semantic, specification or platform decision changes, and
  no performance magnitude is claimed.

## 0.3.165-SNAPSHOT

- `PERF025-G2` reduces fixed physical RootTask/Task bookkeeping cost without
  changing observable Task, Future, Actor, cancellation or structured-concurrency
  semantics. The Actor execution domain now tracks live Tasks in an
  identity-backed set rather than a `LinkedHashSet`, preserving exact live-Task
  enumeration for Actor termination while avoiding per-entry linked-set nodes
  and any incidental iteration-order dependency. `ProtosTask` structured-parent
  provenance is now immutable after construction, and internal terminal
  bookkeeping reads that parent directly instead of reacquiring the child Task
  monitor and allocating an `Optional`. Terminal publication also no longer
  reacquires the Task monitor solely to revalidate the exact terminal state
  already established by its private callers. The existing
  `beginDirectDispatch()` plus `runContinuation()` first-execution path is
  deliberately retained so pre-start cancellation and Actor-termination races
  keep the established boundary. Focused Task/domain, terminal-lifecycle,
  Actor-termination and Future regressions remain green. This is an internal
  runtime bookkeeping optimization only: no observable Protos semantic,
  specification or platform decision changes, and no performance magnitude is
  claimed.

## 0.3.164-SNAPSHOT

- `PERF025` makes hot guest Bytecode Context acquisition node-aware in both
  generated Protos interpreters. Direct Closure-call and send specializations
  now bind the current `ProtosLanguageContext` through the current Truffle
  `Node` and `ContextReference.get(node)` instead of routing those hot
  specializations through the host-level Polyglot current-Context probe plus a
  node-less Context reference. The already-proven entered Context is also
  propagated into guarded Integer specialization establishment and
  Context-owned Bytecode plan derivation instead of being rediscovered from
  ambient runtime state inside those helpers. The existing
  `enteredContext == cachedContext` guards, Context-local executable projection,
  PLAT001 ownership, ordinary fallback paths and host/boundary callers without
  an adopted guest node remain unchanged. Focused direct-Closure, compact-call,
  guarded-Integer and structured-send regressions plus Actor, isolated-P and
  nested-CallTarget multi-Context routing regressions remain green. This is an
  internal hot-path lookup/propagation optimization only: no observable Protos
  semantic, specification or Context-policy change is introduced, and no
  performance magnitude is claimed.

## 0.3.163-SNAPSHOT

- `PERF025` preserves already-established Unicode-scalar-sequence proof and
  derived scalar cardinality across Core String operations instead of
  repeatedly forgetting and reconstructing that information. Arbitrary host
  `String` ingress still validates the complete UTF-16 representation and
  rejects unpaired surrogates, but now derives the exact semantic scalar count
  in that same traversal and stores it immutably in `ProtosStringValue`.
  Standard `String.size` reads the cached count directly; standard `String.at`
  creates its already-proven one-scalar result without revalidation; standard
  `String +` and variadic `String.concat` compose validity and scalar counts
  from already-valid semantic String operands without rescanning the complete
  derived text. Actor transfer, isolated-P transfer, detached execution and
  Test Tool discovery rematerialization likewise preserve the proof metadata
  while retaining fresh wrappers where their existing isolation/copy policy
  requires one. String identity/equality/hash, interop, strict String domains,
  exact scalar-sequence semantics and the prohibition on implicit Unicode
  normalization remain unchanged. New PERF025 focal regressions freeze the
  cached-count and proof-preserving productive paths. This is an internal
  representation/runtime-cost optimization only: no Protos specification or
  platform decision changes, and no performance magnitude is claimed.

## 0.3.162-SNAPSHOT

- `PERF025` makes `Bytes` and recursive `ByteRegion` reservation coordination
  pay only while isolated-parallel byte regions are actually live. Ordinary
  indexed size/read/write/add/remove and snapshot operations no longer acquire a
  Java monitor merely because `parallelRange` exists, and fresh values no
  longer allocate an empty reservation list. The first non-empty reservation
  lazily publishes an immutable reservation snapshot; zero-length ranges remain
  allocation-free, disjoint reservations coexist, overlap checks retain the
  existing half-open interval semantics, and releasing or committing the last
  reservation returns the value to the storage-only representation. Snapshot
  reads take the coordinated slow path only while reservation state is active,
  preserving publication visibility and the existing `Bytes.each`,
  Actor-transfer and nested-P snapshot behavior. Successful commit, cancellation
  and failure continue to use the existing Future commitment boundary; parent
  reserved-index access, structural-mutation rejection, readable `size`, P
  ownership and all public errors/protocols are unchanged.
  `ProtosBytesReservationPayAsYouGrowTest` freezes lazy reservation lifetime,
  zero-length behavior, disjoint/overlap handling, commit publication and the
  absence of monitor modifiers on the ordinary fast path, while existing
  indexed-interop, P execution/context routing, Actor-transfer and PERF006/
  PERF026 `Bytes.each` regressions remain green. This is an internal
  representation/synchronization optimization only: no observable Protos
  semantic or specification change, and no performance magnitude is claimed.

## 0.3.161-SNAPSHOT

- `PERF025` replaces whole-collection keyed search in `Map` and `IdentityMap`
  with physical exact-hash indexing while preserving the existing logical
  insertion-order sequence and all observable collection semantics. Each live
  association remains present exactly once in insertion order and is also
  registered in one bucket keyed by its exact recorded `BigInteger` hash;
  bucket candidate order is insertion order, replacement changes only the
  value, removal updates both authorities and drops an empty bucket, and
  reinsertion appends normally. Ordinary `Map` lookup now computes the query
  hash once and examines only entries whose recorded hash is exactly equal
  before applying the existing directed `queryKey == storedKey` comparison.
  Structured Map read lookup, initial definition, `atPut` and `remove` select
  that same exact bucket after the guest hash result is accepted instead of
  copying `keyedSnapshot()` or scanning unrelated recorded hashes. Absent
  `atPut` continues to store the already-computed query hash, retaining the
  existing single-hash I035 behavior and representative key, recorded hash and
  insertion position on replacement. `IdentityMap` analogously indexes by
  exact recorded identity hash and still resolves collisions exclusively with
  `ProtosIdentity.identical`; it does not introduce Java reference identity or
  `IdentityHashMap`, and absent `atPut` reuses its single computed identity
  hash. The existing Map comparison-scope, suspension/cancellation/error and
  open/closed/frozen machinery is unchanged. Logical snapshots used by
  `Map.each`, `IdentityMap.each`, `Map.match`, transfer/copy/render paths and
  Array/Bytes are deliberately unchanged. New
  `ProtosPerf025MapPhysicalIndexTest` freezes bucket ordering,
  remove/reinsert behavior, semantic IdentityMap identity and the structural
  absence of productive whole-Map search snapshots; the existing PLAT028,
  I049, F-prime construction, Map.match and PERF026-D2 regressions cover
  callback direction, exact recorded-hash filtering, representative-key
  retention, single-hash behavior, suspension/unwind, state boundaries and
  snapshot stability. This is a physical representation/indexing
  optimization only: no observable Protos semantic or specification change,
  and no performance magnitude is claimed.

## 0.3.160-SNAPSHOT

- `PERF025` makes ordinary object/member and frame-backed lexical storage pay
  only for the generality actually used. Empty ordinary objects and freshly
  rebound Closures now share a storage-free lexical-binding authority and
  promote to map-backed storage only on first local-slot creation; the
  map-backed authority allocates its `LinkedHashMap` lazily and uses that one
  ordered map as both value store and establishment-order authority, releasing
  the backing map again when emptied. Execution Context materialization can now
  attach its definitive deferred/frame lexical authority directly instead of
  constructing and then replacing a provisional map-backed authority.
  Frame-backed lexical state likewise keeps `dynamicOverflow` and explicit
  `establishmentOrder` absent until dynamic bindings or non-monotonic
  establishment history require them; Closure frame layouts place parameters
  before body declarations so the normal parameter-then-local path remains in
  compact ordinal order without changing lexical identity or PRESENT/ABSENT
  semantics. `bindMethod` skips local-slot copying when the stored Closure has
  no local bindings while still producing one fresh receiver-bound Closure
  identity per member extraction. Ordinary `ReadMember` in both generated
  Bytecode interpreters now has a three-entry exact-receiver/constant-selector
  PIC backed by the existing selector-specific D013 lookup `Assumption`;
  successful hits cache only `ProtosSlotLookupResult`, never the extracted
  value, so Closure reads still rebind freshly on every access, selector
  mutation invalidates exactly as before, unsupported representations and
  megamorphic sites retain the unchanged generic fallback, and scalar member
  materialization no longer crosses an unnecessary `TruffleBoundary`.
  `ProtosPerf025ObjectModelPayAsYouGrowTest`, expanded frame-materialization
  coverage and expanded lazy-capture/member-read coverage freeze storage
  promotion, remove/recreate order, compact-to-general frame transitions,
  guarded invalidation, fresh Closure extraction and bounded-PIC fallback.
  Existing lexical-layout, H1 indexed-parameter, guarded-lookup, debugger,
  capture, Context and D179 regressions remain green. This slice deliberately
  does not introduce Graal `DynamicObject`/`Shape` or any observable object
  model change, and makes no performance-magnitude claim.

## 0.3.159-SNAPSHOT

- `PERF025` makes PLAT044 B′ immediate literal standard-control callbacks pay
  for a rich Closure invocation only when it is actually required. Boolean,
  `whileTrue` and local `each` callback preparation now performs authoritative
  ordinary D013 `call` selection once and retains a lean
  `PreparedInlineLiteralCall` carrying the selected source target plus compact
  invocation arguments. Inline admission is decided from that carrier before
  any rich `ProtosActivation`, guest Context/argument Array or physical
  `PreparedClosureCall` is materialized; an admitted callback materializes its
  one fresh semantic Closure activation only when entering the parser-inlined
  resumable region, while a missed admission constructs the exact ordinary
  prepared call lazily from the already-selected target without repeating
  lookup. Canonical `call` overrides, alias-home selection and invalidation,
  captured lexical state and ReturnHome provenance, Task/dynamic control,
  Error/non-local-return/suspension behavior, debugger scope projection and
  ordinary structured/physical fallback remain unchanged. The callback body
  remains parser-inlined in its caller semantic Bytecode root under PLAT044;
  callback-specific lexical/frame-native lowering is deliberately unchanged
  for the later slice. New
  `ProtosPerf025InlineCallbackPreparationTest` freezes the preparation boundary
  for Boolean, `whileTrue` and `Array.each`, while the existing PERF026 B1/B2/B3,
  C1 and D1/D2/D3 regressions continue to cover observable semantics and
  tooling. No performance magnitude is claimed.

## 0.3.158-SNAPSHOT

- `PERF027` removes the mandatory `BigInteger` physical representation from
  ordinary small semantic Integers while preserving the single exact unbounded
  `Integer` family. `ProtosIntegerValue` now canonicalizes values that fit the
  full signed `long` range to an internal machine-word representation and keeps
  arbitrary-precision `BigInteger` storage only when the mathematical value is
  outside that range. Canonical `+`, binary `-`, `*`, `div` and `mod` operations
  execute directly on two small operands and transparently promote on overflow;
  arbitrary-precision results canonicalize back to the small representation
  whenever they again fit in signed `long`. The `Long.MIN_VALUE / -1` boundary
  promotes exactly instead of overflowing. Integer/Integer equality, ordering
  and semantic identity compare the canonical internal representation without
  requiring `BigInteger` materialization on the small/small path. Integral
  interop width checks/projections likewise use the machine-word representation
  directly where possible. Exact Integer `/` to binary64 deliberately retains
  the existing arbitrary-precision rounding authority, and Integer/Float
  cross-family semantics, hashes, lookup/delegation, recognition, guarded
  selection/fallback, Actor transfer, debugger/tooling behavior and Native
  Image architecture remain unchanged. No SmallInteger/BigInteger family or
  primitive `long` Bytecode carrier becomes guest-visible. New
  `ProtosIntegerValueRepresentationTest` covers signed-long canonicalization,
  overflow promotion, down-normalization, signed quotient/remainder behavior
  and representation-independent identity/comparison; existing arithmetic,
  interop, numeric equality/ordering, guarded-Integer and Actor-transfer tests
  cover the affected integration surface. This is a structural representation
  optimization only: no observable Protos semantic or specification change and
  no performance magnitude is claimed.

## 0.3.157-SNAPSHOT

- `PERF025` removes the residual full native-Closure invocation scaffold from
  successful guarded canonical local Integer operations. PERF016/D013 guarded
  lookup remains the semantic selection authority; after that selection, the
  runtime additionally proves the exact standard implementation by the
  installed Closure identity, its private Integer-operation provenance, the
  exact `Integer` prototype `methodHome`, selector and existing lookup
  `Assumption`. Proven `+`, binary `-`, `*`, `/`, `div` and `mod` operations
  with valid Integer operands execute through the same canonical arithmetic
  authority used by their native Closure bodies and return through a minimal
  `ImmediateResultCall`, without constructing a rich method `ProtosActivation`,
  `ProtosReturnHome`, guest argument Array or `PreparedClosureCall.NativeCall`,
  and without calling `nativeBody.execute`. Wrong-domain arguments,
  division/remainder by zero, inherited Number operations such as ordering,
  non-canonical selections and all unsupported cases retain the exact
  PERF027-A deferred native path, preserving selected Closure/methodHome,
  Error/control state and generic fallback. `ProtosIntegerValue(BigInteger)`
  representation and exact arithmetic remain unchanged. The Integer protocol
  native-provider boundary contracts from four lexical `nativeClosure`
  construction sites to two because the six arithmetic selectors now share one
  audited installer/body implementation; the native semantic surface itself is
  not expanded. `ProtosStandardIntegerArithmeticTest`,
  `ProtosPerf027AGuardedIntegerDeferredActivationTest`,
  `ProtosGuardedLookupTest` and
  `ProtosCoreNativeBoundaryArchitectureTest` cover canonical provenance,
  exact large-Integer results, immediate successful execution, deferred Error
  and inherited-operation fallback, Context isolation and the audited native
  boundary. No observable Protos semantic or specification change; no
  performance magnitude is claimed.

## 0.3.156-SNAPSHOT

- `PERF025-H1` establishes statically proven Closure parameters of a root that
  installs its persistent frame authority at their known layout ordinal. The
  lowerer emits the new `BindClosureIndexedParameter`/`BindClosureIndexedRest`
  operations, carrying the root's `ProtosFrameLexicalLayout` and the
  parameter's ordinal, for every parameter whose binding identity is owned by
  that root's own scope; `ProtosFrameLexicalBindingAuthority.createFrameBackedBindingAt`
  then checks presence and writes the frame local directly at that ordinal,
  recording establishment order exactly as before, instead of re-resolving the
  name through `containsBinding`/`putBinding`. The indexed path applies only
  while the activation's current authority stores that exact layout and the
  execution context admits creation
  (`ProtosActivation.currentAuthorityAdmittingLocalCreationForRuntime`: an
  unmaterialized, or an OPEN materialized, execution context still using that
  authority); a CLOSED or FROZEN context, another authority, or an unproven
  parameter takes the unchanged named `BindClosureParameter`/`BindClosureRest`
  path. Static identity never implies presence: a PRESENT binding, including
  PRESENT(null), is still a duplicate-creation Error, and a binding removed
  before its formal bind (D179 C0) is re-established at the same ordinal.
  Defaults, rest Array creation, argument transport and the frame-native
  `BindClosureFrameParameter` path are unchanged; no observable semantic
  change. New `ProtosPerf025H1IndexedParameterEstablishmentTest`.

## 0.3.155-SNAPSHOT

- `PERF025` virtualizes statically unobservable Closure return homes. A new
  `CanonicalReturnHomeAnalysis` proves, once per source Closure execution plan
  (never per invocation), whether the Closure or any lexical descendant -
  parameter defaults, nested Closures at any depth, and inline Object bodies -
  contains `^`. An owning invocation of a source-backed Closure whose plan
  proves its Smalltalk-style return home unobservable runs under the shared
  non-materialized `ProtosReturnHome.unobservable()` marker instead of a fresh
  `ProtosReturnHome`, and skips the home's `isActive`/`complete` lifecycle and
  non-local-return matching. All owning sites (compact frame ABI, rich
  activation factories, deferred immediate-method preparation) select the home
  through `ProtosClosureValue.invocationReturnHomeForRuntime()`. Home
  ownership and captured-home provenance are unchanged, so PLAT044 inline
  literal-callback admission is preserved; native bodies and plans not yet
  prepared keep a fresh physical home. Direct, default, descendant, transitive
  and Object-body non-local return, escaped and foreign-Task `InvalidReturn`,
  `ensure` precedence and suspension lifetime of physical homes are preserved;
  no observable semantic change. New `ProtosPerf025VirtualReturnHomeTest`;
  return-home lifecycle assertions over proven-unobservable plans in
  `ProtosClosureInvokerTest`, `ProtosPerf006B2BClosureContinuationCompositionTest`,
  `ProtosPerf006B2D1OrdinarySendCompositionTest`,
  `ProtosPerf006B2D3BSelectedStandardObjectCallIntrinsicTest`,
  `ProtosPerf006B2D3AOrdinaryObjectCallProtocolTest`,
  `ProtosPerf010APreparedTargetSpecializationTest`,
  `ProtosPerf025DirectSourceClosureCompactInvocationTest` and
  `ProtosPerf025H1LazyRootActivationTest` follow the non-materialized home.

## 0.3.154-SNAPSHOT

- `PERF025` completes compact callee execution on top of `PERF025-H1`: a
  compact source root keeps its compact invocation state authoritative through
  ordinary body-level local creation and assignment, not only through
  parameter binding and reads. `CreateCurrentFrameLocal` establishes an ABSENT
  frame-native local directly while the frame is still compact, and new
  root-level `ResolveRootFrameLocalWriteTarget`/`AssignRootFrameLocal`
  operations select and write a PRESENT statically `Resolved` current local
  through its constant `LocalAccessor` without materializing the activation;
  duplicate creation, ABSENT (D179 C0) targets, FROZEN contexts and every
  other destination take the unchanged activation path and Errors. When a
  compact invocation is materialized, the activation adopts a read-only view
  of the frame-argument supplied range instead of copying it with
  `List.copyOf`, and `ProtosFrameArguments.compactTask` reports the published
  activation's exact Task after materialization. BUG013 (no retained
  `VirtualFrame`), D179 presence, parameter/default/rest order, Context
  identity, Error-handler selection, ReturnHome and Task/control provenance
  are preserved. New `ProtosPerf025CompactCalleeExecutionTest`;
  `ProtosPerf025FrameMaterializationSliceTest` and
  `ProtosPerf028AResolvedCurrentLexicalWriteTest` accept the root-level write
  operations. No observable Protos semantics change; the specification is
  unchanged.

## 0.3.153-SNAPSHOT

- `PERF025-H1` (#758) removes the unconditional `ProtosActivation`
  materialization from compact semantic source roots. The root prologue
  (`PublishFrameActivation`) is gone; the current activation is loaded through
  a lazy `CurrentActivation` operation that materializes the exact activation
  from the compact frame ABI only when an operation, an Error path, root
  exception interception or tooling needs it, at most once, published into
  frame argument 0. At root level, supplied-argument presence, loads and the
  arity upper bound (`HasFrameClosureArgument`, `LoadFrameClosureArgument`,
  `CheckFrameClosureArgumentUpperBound`), frame-native parameter binding
  (`BindClosureFrameParameter`) and PRESENT current-frame reads
  (`ReadRootFrameLocal`) operate directly from compact frame arguments and
  frame locals, so ordinary zero/simple-argument and default-argument calls
  run without a rich activation. The rich activation remains an exact lazy
  projection with unchanged Context, Task, receiver, method-home, capture and
  return-home provenance. ReturnHome representation, frame materialization and
  Closure-capture behavior are intentionally unchanged. New
  `ProtosPerf025H1LazyRootActivationTest`; affected PERF025/I068 tests updated
  to the new structural shape. No observable Protos semantics change; the
  specification is unchanged.

## 0.3.152-SNAPSHOT

- `PERF025` makes Closure-literal lexical capture lazy. A materialized
  Closure no longer captures an eagerly built `List` of guest execution
  Contexts: `MaterializeClosure` now captures the creating activation's
  `ProtosLexicalEnvironment` through
  `ProtosActivation.lexicalEnvironmentForClosureCapture()`, without calling
  `context()` or copying the outer chain. Each activation creates at most one
  environment node, shared by reference by every Closure it creates and by
  the activations derived from them; a deferred node answers membership,
  reads and frame-backed authority through the owning activation's single
  lexical store (PLAT036/I068, PERF013 `MaterializedLocalAccessor` paths
  unchanged) and materializes the guest Context only through the activation's
  own `context()`, so every observer (the `context` intrinsic, reflection,
  escape, the debugger, generic writes) sees one identity. Captured
  read/write operations and `ProtosLexicalFallback` walk the chain without
  materializing; D179 C0 nearer-presence retargeting, object-body capture
  boundary, `bindMethod`, parallel projection, receiver/`methodHome`/return
  home and NLR are unchanged. List-based `capturedLexicalContexts()` /
  `lexicalContextsForClosureCapture()` remain as cold materializing
  projections. New `ProtosPerf025LazyLexicalCaptureTest`. No semantic or
  specification change.

## 0.3.151-SNAPSHOT

- `PERF025` frame-materialization slice: ordinary ROOT/CLOSURE frames are no
  longer materialized merely because they declare a local or parameter.
  `CanonicalToBytecodeLowerer` now emits `InstallFrameLexicalAuthority` only
  when a conservative lowering predicate (`requiresPersistentFrameAuthority`)
  cannot prove that a Closure root's bindings stay confined to its live frame:
  top-level/module roots, nested Closures (including inline literal callbacks
  and Closure defaults), Object construction, the `context` intrinsic,
  `compose`, any non-`Resolved` (`Candidate`/`Dynamic`) access to a name the
  root declares, and any unrecognized form keep the persistent authority.
  In admitted roots, statically proven current bindings (supplied, defaulted
  and rest parameters, target-less `:` and D143 multiple creation) are
  established directly in their frame locals by the new
  `BindClosureFrameParameter`, `BindClosureFrameRest`,
  `CreateCurrentFrameLocal` and `MultipleCreateFrameLocals` operations, with
  the exact duplicate-creation error and presence-by-not-cleared rule; reads
  and PERF028-A writes keep their existing `LocalAccessor` fast paths. If an
  admitted root's activation is already observed (materialized Context or an
  installed authority), its first establishment installs, in-frame and while
  the frame is live, the same materialized-frame authority, adopting the
  bindings already present. Tooling scope projection of an unobserved
  admitted root installs that same authority from the live queried frame
  before projecting, so `ProtosDebuggerScope` is unchanged.
  Persistent/materialized frame authority is retained for every case that
  requires general or escaping context semantics; BUG013 lifetime safety is
  intact (no raw `VirtualFrame` is ever retained). No observable Protos
  semantic change and no specification change. New
  `ProtosPerf025FrameMaterializationSliceTest`.

## 0.3.150-SNAPSHOT

- `PERF025` completes the PLAT040 F′ convergence for direct source-backed
  Closure invocation. Stable direct Closure-call hits (`finishDirectClosureCall`,
  under the unchanged PERF014 selection and `DirectCallNode` target) and
  Task-owned direct source Closure entry (`prepareTaskOwnedDirectClosureIfBytecode`,
  the path taken by `PreparedTopLevel.invoke()`) no longer build a pre-target
  `ProtosActivation` through `forClosureInvocation`. They enter the source
  target through a new compact direct-Closure frame ABI kind in
  `ProtosFrameArguments` (closure, caller provenance, explicit Task or null,
  invocation return home, supplied values), and the root prologue materializes
  the exact activation through the new
  `ProtosActivation.forDirectClosureInvocationWithReturnHomeForRuntime` with
  the guest execution Context and supplied guest Array deferred. Captured
  receiver, `methodHome`, lexical contexts, return-home ownership, Task
  attachment and dynamic-control inheritance are unchanged. Native,
  structured-control, generic-fallback and synchronous host Closure paths keep
  their existing shape. ReturnHome allocation, BUG013 frame materialization
  and `PublishFrameActivation` are unchanged. New
  `ProtosPerf025DirectSourceClosureCompactInvocationTest` covers the compact
  shape, deferred state, rest parameters, Task-owned entry, distinct
  invocations, capture by reference, `this`, non-local return and InvalidReturn.
  No observable Protos semantics change; the specification is unchanged.

## 0.3.149-SNAPSHOT

- `PERF028-A` (#780) specializes bare lexical assignments statically
  `Resolved` in the genuine scope that owns the executing frame. They now
  lower to `ResolveCurrentFrameLocalWriteTarget`/`AssignCurrentFrameLocal`,
  which identify the binding through a constant `LocalAccessor` instead of
  the String-keyed `ResolveWritableLexicalTarget`/`AssignResolvedLexicalTarget`
  pair, in both body and parameter-default lowering. A PRESENT binding is
  selected without name search or a fresh destination object; a binding
  cleared under D179 C0 runs the unchanged generic selection before RHS
  evaluation, and the selected destination is written without re-resolution
  (FROZEN rejected, CLOSED writable, RHS removal is a mutation Error). The
  FROZEN check uses a new non-materializing
  `ProtosActivation.currentContextIsFrozenForRuntime()`. Candidate, Dynamic,
  explicit member and captured writes keep their existing paths. New
  `ProtosPerf028AResolvedCurrentLexicalWriteTest` covers the structural path,
  fallback selection, no retargeting, remove/recreate, CLOSED/FROZEN,
  `PRESENT(null)`, control transfer, suspension/resume with the
  uncached-to-cached transition, and default assignments. No observable
  Protos semantics change; the specification is unchanged.

## 0.3.148-SNAPSHOT

- `PERF027-A` (#779) isolates the Integer invocation tax. The PERF016 guarded
  Integer native send keeps its exact cached selection (native Closure,
  method home, stability) but now prepares the selected method invocation
  through the existing PLAT040 deferred activation
  (`forImmediateMethodInvocationWithReturnHomeForRuntime`) instead of the
  eager immediate-method factory. The fresh invocation return home is still
  established before entry; the guest execution Context and supplied guest
  Array materialize only when observed (for example by an Error path,
  reflection or a debugger). Every other immediate-method caller keeps the
  eager path. Integer representation (`ProtosIntegerValue(BigInteger)`) and
  arithmetic are unchanged. New
  `ProtosPerf027AGuardedIntegerDeferredActivationTest` covers selection and
  home identity, deferred state after success, return-home completion,
  on-demand materialization, exact large and overflow-sensitive results,
  comparison results, div/mod zero and wrong-domain Errors, and separate
  Contexts. No observable Protos semantics change; the specification is
  unchanged.

## 0.3.147-SNAPSHOT

- `PERF025-G1` (#758) makes Task structured-child bookkeeping pay-only-when-used.
  `ProtosTask.children` is now materialized on the first `addChild`; a never
  materialized set behaves as the empty set, and child snapshots of an empty
  set return `Set.of()` without allocation. The per-Task anonymous child-drain
  `WaitDependency` is replaced by one shared stateless identity sentinel,
  compared only against each Task's own wait/resume dependency. New
  `ProtosPerf025G1LazyTaskChildrenTest` covers childless completion without
  materialization, first-child ownership and removal, completion, failure and
  cancellation child drain. No observable Protos semantics change; the
  specification is unchanged.

## 0.3.146-SNAPSHOT

- `PERF025-F1` (#758) implements PLAT046 Candidate B: direct-caller hosted
  session execution. `ProtosStandaloneHostedSession` (and therefore
  `ProtosStandaloneHostedExecution.executeFile`) now runs open,
  `invokeTopLevel`, `prepareTopLevel`, `PreparedTopLevel.invoke` and close on
  the calling thread, still through the Process execution host, Process
  Polyglot Context entry and a fresh RootTask. A session-local
  `ReentrantLock` gate admits one guest operation at a time; close publishes
  its cutover before taking the gate, so an already-started operation
  finishes and every later operation fails with `IllegalStateException`,
  then performs the unchanged teardown sequence. `ProtosGuestCarrier` and
  `ProtosGuestCarrierTest` are retired; the shared
  `GUEST_CALL_STACK_SIZE_BYTES` budget remains for the CLI and Test Tool
  carriers. New `ProtosStandaloneHostedSessionGateTest` covers caller-thread
  execution, multi-caller serialization and call-versus-close exclusion. No
  observable Protos semantics change; the specification is unchanged.

## 0.3.145-SNAPSHOT

- `PERF025-E2` (#758) cleans up the guest-carrier transport.
  `ProtosGuestCarrier.call()` now queues one private `CarrierCall` request
  (operation, waiting thread, result, failure, volatile completion) directly
  as the carrier `Runnable` and completes it with `LockSupport.park`/`unpark`,
  replacing the per-call one-element state arrays, the wrapping lambda and the
  per-call monitor `wait()`/`notifyAll()`. The dedicated carrier thread, its
  16 MiB stack budget, the `LinkedBlockingQueue`, carrier serialization, STOP
  ordering, close rejection, failure propagation and caller interrupt-status
  restoration are unchanged. New `ProtosGuestCarrierTest` covers the transport.
  No observable Protos semantics change; the specification is unchanged.

## 0.3.144-SNAPSHOT

- `BUG014` restores the ordinary shaded checkout JAR under the `native`
  Maven profile. The profile no longer sets `protos.shade.skip=true`;
  instead `native-maven-plugin` receives the compiled application classes
  directly through
  `<classesDirectory>${project.build.outputDirectory}</classesDirectory>`
  plus the resolved dependency graph, so Native Image still never consumes
  an uber-JAR that duplicates those dependencies. The PLAT045 interpreter-only
  fallback runtime selection is unchanged.
  `ProtosPerf006C1OptimizingRuntimeClosureTest` now asserts the
  classes-directory input and the absence of the shade skip. No observable
  Protos semantics change; the specification is unchanged.

## 0.3.143-SNAPSHOT

- `PERF025-C2D` (#758) reduces the shared explicit JVM guest-carrier stack
  budget (`ProtosStandaloneHostedExecution.GUEST_CALL_STACK_SIZE_BYTES`)
  from 64 MiB to 16 MiB after the post-B′ PERF025-C2C evidence gate showed
  the artificial per-level root multipliers removed. The dedicated guest
  carrier, Test Tool per-Case carrier use, serialization/placement
  behavior, the unchanged 10,000-deep
  `deep-recursive-closure-call-stack-capacity` regression workload, and all
  observable Protos semantics are retained; the specification is unchanged.
  16 MiB is a conservative fixed budget recorded as part of the reference
  runtime's run identity, not a claimed minimum. The benchmark README now
  records the 16 MiB carrier stack.

## 0.3.142-SNAPSHOT

- `PERF026-D3` (#769) adds the standard `Environment.each` consumer of the
  PLAT044 (#766) Candidate B′ inline literal callback mechanism, without
  changing observable Protos semantics or the specification. It reuses the
  PERF026-D2 two-parameter literal candidate (exactly two ordinary required
  positional parameters, no default, not rest, no nested Closure) unchanged;
  only runtime capability distinguishes the families. After ordinary lookup
  and selection prepare the canonical standard `Environment.each` with
  exactly that staged literal, and it neither owns its return home nor
  requires a Context-local projection, the loop is sequenced in the semantic
  source root instead of the structured-dispatch helper root. Admission
  prepares and validates nothing and does not examine callback arity:
  receiver validation, callback callability validation before portable
  validation, the complete portable (String, String) conversion and
  validation with canonical name ordering before callback #1, the fresh
  per-entry activation carrying the exact portable name and value Strings,
  advance only after normal callback completion, the ignored callback
  result and the original receiver result all stay owned by the existing
  prepared Environment each call, which joins the common local each view
  directly rather than as an association each. Each entry child that passes
  the shared B′ exact-invocation proof runs inline with its own RootTag and
  projected debugger scope, binding `name` and `value` through the ordinary
  Closure parameter binding from that prepared activation; a child that
  fails it keeps its exact physical invocation. The two-parameter send-site
  admission and preparation operations now cover Map, IdentityMap and
  Environment, so the inline callback region is not duplicated. Because the
  generic preparation path classifies the structured Environment.each
  capability by implementation identity alone, the Environment admission
  additionally re-proves the canonical home from the call's own receiver and
  method home, so the standard `each` copied to another home keeps its
  ordinary path. Every other
  callback shape and every other `each` keep the existing paths. Focused
  topology, tooling, fallback and semantic tests are added; no performance
  claim is made.

## 0.3.141-SNAPSHOT

- `PERF026-D2` (#769) adds the standard `Map.each` and `IdentityMap.each`
  consumers of the PLAT044 (#766) Candidate B′ inline literal callback
  mechanism, without changing observable Protos semantics or the
  specification. B′ is extended only to an immediate Closure literal with
  exactly two ordinary required positional parameters (no default, not rest)
  and no nested Closure, staged as the sole argument by a candidate check kept
  separate from the one-parameter indexed each candidate and the
  zero-parameter Boolean/while candidates. After ordinary lookup and selection
  prepare the canonical standard `Map.each` or `IdentityMap.each` with exactly
  that staged literal, and it neither owns its return home nor requires a
  Context-local projection, the loop is sequenced in the semantic source root
  instead of the structured-dispatch helper root. Admission prepares nothing
  and does not examine callback arity: receiver validation, callback
  callability validation, the single shallow insertion-ordered association
  snapshot, the fresh per-association activation carrying the exact
  representative key and value, advance only after normal callback
  completion, the ignored callback result and the original receiver result
  all stay owned by the existing prepared Map/IdentityMap each calls; no
  hash, equality or identity re-search is added. Each association child that
  passes the shared B′ exact-invocation proof runs inline with its own
  RootTag and projected debugger scope, binding `key` and `value` through the
  ordinary Closure parameter binding from that prepared activation; a child
  that fails it keeps its exact physical invocation. The D1 local loop is
  generalized over a common prepared each view instead of being duplicated.
  Every other callback shape and every other `each` keep the existing paths.
  Focused topology, tooling, fallback and semantic tests are added; no
  performance claim is made.

## 0.3.140-SNAPSHOT

- `PERF026-D1` (#769) adds the standard `Array.each` and `Bytes.each`
  consumers of the PLAT044 (#766) Candidate B′ inline literal callback
  mechanism, without changing observable Protos semantics or the
  specification. B′ is extended only to the smallest parameterized shape: an
  immediate Closure literal with exactly one ordinary required positional
  parameter (no default, not rest) and no nested Closure, staged as the sole
  argument by a candidate check kept separate from the zero-parameter
  Boolean/while candidates. After ordinary lookup and selection prepare the
  canonical standard `Array.each` or `Bytes.each` with exactly that staged
  literal, and it neither owns its return home nor requires a Context-local
  projection, the loop is sequenced in the semantic source root instead of the
  structured-dispatch helper root. Admission prepares nothing: receiver
  validation, callback callability validation, the single shallow ascending
  indexed snapshot, the fresh per-element activation carrying the exact
  snapshot element or semantic Integer octet, advance only after normal
  callback completion, the ignored callback result and the original receiver
  result all stay owned by the existing prepared Array/Bytes each calls. Each
  element child that passes the shared B′ exact-invocation proof runs inline
  with its own RootTag and projected debugger scope, binding its formal
  through the ordinary Closure parameter binding from that prepared
  activation; a child that fails it keeps its exact physical invocation. Every
  other callback shape and every other `each` keep the existing paths.
  Focused topology, tooling, fallback and semantic tests are added; no
  performance claim is made.

## 0.3.139-SNAPSHOT

- `PERF026-C1` (#768) adds the first standard `whileTrue` consumer of the
  PLAT044 (#766) Candidate B′ inline literal callback mechanism, without
  changing observable Protos semantics or the specification. When the
  condition receiver and the sole body argument are both immediate
  zero-parameter Closure literals without nested Closures, both are staged
  in their ordinary evaluation order. After ordinary lookup and selection
  prepare the canonical standard `whileTrue` with exactly those two values,
  and neither literal owns its return home or requires a Context-local
  projection, the loop is sequenced in the semantic source root instead of
  the structured-dispatch helper root. Admission prepares no activation, so
  receiver/body validation, the fresh per-iteration condition activation,
  the body activation prepared only after a canonical `true`, the strict
  Boolean condition authority, the ignored body result and the canonical
  `null` result are all unchanged. Each fresh child that passes the shared
  B′ exact-invocation proof runs inline with its own RootTag and projected
  debugger scope; a child that fails it keeps its exact physical invocation.
  Every other while keeps the existing structured-dispatch path. Focused
  topology, tooling and semantic tests are added; no performance claim is
  made.

## 0.3.138-SNAPSHOT

- `PERF026-B3` (#767) completes the standard Boolean PLAT044 (#766) Candidate
  B′ family by adding `IF_TRUE_IF_FALSE` two-candidate selected-branch
  handling, without changing observable Protos semantics or the specification.
  Both callback arguments are still evaluated eagerly, exactly once and in
  source order; each eligible immediate literal position is staged as a
  positional candidate. After ordinary lookup, `PreparedBooleanCall`
  selection and selected-callback preparation, the selected callback runs
  inline only when its position and exact value match a staged literal and
  every existing B1 shape check holds. Selection and admission share one
  selected-position authority, so the unselected callback is never entered
  and any other shape keeps the exact physical callback invocation. The
  selected result completes through the existing Boolean callback finish. The
  guest carrier and its 64 MiB stack size are unchanged. The superseded
  B1/B2 `ifTrueIfFalse` physical-boundary tests are replaced by focused B3
  topology, tooling and semantic tests; no performance claim is made.

## 0.3.137-SNAPSHOT

- `BUG013-F` implements the ratified PLAT045 (#772) Candidate B contract
  ("Native Image supported, guest JIT unavailable"). The `native` Maven
  profile now builds with the supported fallback Truffle runtime
  (`-Dtruffle.UseFallbackRuntime=true`): the executable remains a real
  GraalVM Native Image AOT host, but guest execution is interpreter-only and
  Native guest JIT is unavailable while upstream `oracle/graal#14579` blocks
  Bytecode DSL Tier-2 compilation in Native Image. The JVM path, Surefire and
  `bin/protos` keep the optimizing runtime and guest JIT unchanged; observable
  Protos semantics and the specification do not change. The maintained Native
  regression (`build/native/test-native.sh`) no longer claims forced Tier-2
  success: it keeps the CLI, guest, Test Tool and DAP gates and instead
  validates interpreter-only execution, failing closed on a missing fallback
  runtime, any guest compilation, opt failure, `FrameWithoutBoxing` or
  compilation failure. The interpreter-only warning is not suppressed.
  Restoring Native guest JIT remains gated on an upstream fix and Native
  Tier-2 revalidation. No release is implied.

## 0.3.136-SNAPSHOT

- `PERF026-B2` (#767) extends the PLAT044 (#766) Candidate B′ single-literal
  inline callback path published by `PERF026-B1` from standard `IF_TRUE` to
  standard `IF_FALSE`, `AND` and `OR`, without changing observable Protos
  semantics or the specification. Eligibility is still decided after ordinary
  lookup and the existing `PreparedBooleanCall` classification, only when the
  one supplied callback is actually selected and every existing B1 shape check
  holds; unselected callbacks are never entered. The inline callback result
  still completes through the standard Boolean callback finish, so `and`/`or`
  keep the exact Boolean-result validation and Error. `IF_TRUE_IF_FALSE`
  keeps its physical callback path (PERF026-B3), as do all B1 fallbacks. The
  guest carrier and its 64 MiB stack size are unchanged. Focused topology,
  tooling and semantic tests are added; no performance claim is made.

## 0.3.135-SNAPSHOT

- `PERF026-B1` (#767) implements the first PLAT044 (#766) Candidate B′ slice,
  without changing observable Protos semantics or the specification. After
  ordinary lookup and the existing `PreparedBooleanCall` classification, an
  eligible standard `IF_TRUE` immediate literal callback (a zero-parameter
  Closure literal with no nested Closure, whose prepared invocation is its own
  activation root with a rich activation and a captured return home) no longer
  enters a distinct callback RootCallTarget: its body runs inline in the
  containing semantic source root. The literal is still evaluated exactly once
  as an ordinary argument, and the fresh semantic callback activation is
  preserved and selected as the current activation for the whole inline
  region, so `context`, lexical capture by reference, non-local return, Error
  propagation and suspension behave exactly as before. The inline region
  carries a custom RootTag, and debugger scopes inside it project the callback
  activation; as approved by PLAT044, the callback no longer appears as a
  separate debugger or Truffle stack frame. Dynamic callbacks, non-Closure
  invokables, custom same-name selectors, top-level literals owning their
  return home, every other non-eligible shape, and `IF_FALSE`,
  `IF_TRUE_IF_FALSE`, `AND` and `OR` keep the exact physical callback path.
  The guest carrier and its 64 MiB stack size are unchanged. Focused topology,
  tooling and semantic tests and Boolean conformance cases are added.

## 0.3.134-SNAPSHOT

- `PERF025-C2B` implements the PLAT043 (#763) standard Boolean ownership
  exception to PLAT042, without changing observable Protos semantics or the
  specification. A prepared standard Boolean call (`ifTrue`, `ifFalse`,
  `ifTrueIfFalse`, `and`, `or`), classified from the already-selected
  behavior after ordinary lookup, is now sequenced inside the tagged semantic
  source root instead of entering the untagged structured/C-prime root, so a
  reached Boolean callback no longer adds a helper CallTarget per level. The
  semantic root gains operations that delegate to the existing
  `PreparedBooleanCall` state machine and prepared-call completion; the outer
  call is completed exactly once through a Bytecode `TryFinally`. Every other
  structured family (`while`, `each`, `ensure`, `Error.handle`, matching,
  Map/collection control, import, I/O C-prime) still enters the untagged
  helper root. The guest carrier and its fixed stack size are unchanged.
  Topology evidence and a Boolean callback dispatch/control conformance test
  are added.

## 0.3.133-SNAPSHOT

- `PERF025-C` (#758) completes the PLAT042 Candidate B′ semantic/structured
  interpreter cutover (C1c), without changing observable Protos semantics or
  the specification. `ProtosSemanticBytecodeRootNode` is now the real tagged
  semantic source interpreter (automatic RootTag, no RootBodyTag, same tier,
  yield, materialized-local, tail-call and boxing configuration as the
  untagged interpreter): `CanonicalToBytecodeLowerer` lowers top-level,
  module and Closure roots directly into it, so the universal semantic
  wrapper root is removed and an ordinary source Closure call enters exactly
  one CallTarget. Its source-surface operations delegate to the single
  operation implementations in `ProtosBytecodeRootNode`, and root
  Error-handler selection is shared by both interpreters. Each semantic root
  first publishes a compact guarded-send activation into frame argument 0,
  preserving one activation identity per call. `ProtosBytecodeRootNode` is
  now the untagged structured-dispatch/C-prime interpreter; the structured
  dispatcher moved unchanged into `ProtosStructuredDispatchLowerer`, and a
  structured prepared invocation enters it once per invocation through a
  direct call to the Context's stable Task C-prime entry target (indirect
  fallback), composing its continuation without replay. Lexically nested
  Closure roots share the semantic interpreter's `BytecodeRootNodes`
  generation, preserving the PERF013 materialized-local fast path. Native
  generated-structure policy and the native forced-JIT workload cover both
  interpreters. A bounded JVM comparison against `595d547b` measured faster
  ordinary (-46%), structured-boundary (-36%) and retained closure-call
  (-43%) workloads. The BUG008 64 MiB dedicated guest carrier is unchanged:
  stack evidence shows the retained 10,000-deep recursive drivers, which use
  a structured `ifTrue` at every level, still need a large fixed stack (32
  MiB in the interpreter), so carrier retirement remains pending.

## 0.3.132-SNAPSHOT

- `PERF025-C1` (#758) partial implementation checkpoint (C1a+C1b), without
  changing observable Protos semantics or the specification.
  `CanonicalToBytecodeLowerer` now loads the current `ProtosActivation`
  through one compile-time lowering seam instead of emitting frame-argument
  loads directly. An Object construction body is now lowered inline as a
  resumable region of its enclosing Bytecode root, executing with its
  construction activation (still created by
  `ProtosActivation.forObjectConstruction`) held in Bytecode locals; the
  Object-body helper root and its call/continuation machinery
  (`ProtosObjectBodyTargetCell` and the Prepare/Enter/Resume/Finish Object
  construction operations) are removed. Object bodies remain non-lexical
  execution regions, and Closures declared inside them still share their
  lexical owner's `BytecodeRootNodes` generation, preserving the PERF013
  same-generation Closure/owner relationship. Updates the PERF013 grouping
  tests to the inline topology and adds Object-body suspension, nesting,
  capture, Error, ensure and non-local-return coverage.

## 0.3.131-SNAPSHOT

- `PERF025-B` (#758): add a prepared reusable top-level callable to the
  supported JVM embedding session without changing observable Protos
  semantics. `ProtosStandaloneHostedSession.prepareTopLevel(name)` resolves and
  validates the entry module's source-backed top-level Closure once on the
  guest carrier and returns a `PreparedTopLevel` handle whose `invoke()`
  repeatedly runs exactly that captured Closure in the same live Process,
  Polyglot Context and carrier, through the existing execution-host boundary
  and `ProtosRootTaskExecution.executeClosure(...)` (so it inherits the
  PERF025-A direct first dispatch), with no per-invocation slot lookup, source
  parsing, or Process/Context creation. `invokeTopLevel(name)` keeps its
  dynamic per-call slot read and therefore still observes guest reassignment;
  the prepared handle intentionally does not. Preparation of a missing or
  non-source-backed slot fails like `invokeTopLevel`, and a prepared handle is
  rejected once its session is closed. Adds external-consumer embedding tests
  for repeated prepared calls, shared module state, the reassignment
  distinction, preparation rejection, post-close rejection and `FAILED`
  outcome mapping.

## 0.3.130-SNAPSHOT

- `PERF025-A` (#758): direct-dispatch the first segment of a fresh RootTask
  without changing observable Protos semantics. `ProtosRootTaskExecution` now
  starts its root Task through the new
  `ProtosActorExecutionDomain.runFreshRootTaskDirectly(...)`, which registers
  the Task exactly as `createTask(...)` does and runs its first segment via
  `beginDirectDispatch()` instead of an initial runnable-queue
  enqueue/dequeue and scheduler wakeup. A Task created on an already
  TERMINATING Actor still records cancellation before the first segment, so
  it is cancelled at the first-execution boundary without running ordinary
  code. Suspension, resumption and re-runnable re-entry continue through the
  ordinary Actor queue, and terminal mapping is unchanged. Adds focused domain
  regressions for direct start, cancellation, suspend/resume, TERMINATING and
  TERMINATED Actors.

## 0.3.129-SNAPSHOT

- `PERF022` (#752): reduce pathological ordinary Java test-suite runtime
  without changing observable Protos semantics or weakening required
  correctness coverage. Benchmark-scale standalone-embedding inputs are
  replaced by proportional recursion/factorial cases while PERF021 remains
  the owner of benchmark-scale workloads; the reusable-session regression
  keeps repeated invocation coverage with a proportional repeat count.
  Test Tool source loading now crosses its 16-read batch boundary using
  deterministic short reads and a split UTF-8 scalar instead of a
  megabyte-scale payload. Redundant full public Test Tool file-selection and
  workspace-run executions are replaced by existing focal coverage or direct
  outcome/host-failure translation checks, including explicit cancelled
  workspace outcome coverage. Filesystem-library, Package execution-plan and
  workspace-preflight tests amortize immutable Core/RuntimeHost setup while
  retaining fresh activations, Processes, Filesystems and resources where
  isolation is part of the contract. Removes the duplicate
  `ProtosWorkspaceRunDriverTest`; its preflight, Tool/Application isolation,
  dependency execution, public driver path and metadata non-mutation
  invariants remain owned by their focused integration tests.

## 0.3.128-SNAPSHOT

- `I077` (#751, PERF021 #748): expose a reusable standalone JVM embedding
  session. The new `ProtosStandaloneHostedSession` opens one standalone source
  file with the I076 standalone direct-file sequence and keeps the semantic
  Process, its Process-scoped Polyglot Context (and therefore Engine and JIT
  state) and the runtime host alive, so that `invokeTopLevel(name)` can invoke
  an already-defined top-level Closure such as `run` repeatedly in that same
  Process without reparsing source text. All guest operations, including open
  and close, run on one long-lived dedicated carrier with the BUG008 stack
  budget. `close()` requests Process termination, awaits terminal Process
  completion and Context disposition, then closes the runtime host and module
  resolver, and is idempotent. `ProtosStandaloneHostedExecution.executeFile`
  is now implemented on the session and remains supported; it additionally
  awaits terminal Process completion before closing. `ProtosRootTaskExecution`
  gains `executeClosure` sharing its terminal-state mapping. No observable
  Protos semantics or CLI behavior change and no benchmark policy is added.
  Adds session embedding and lifecycle tests.

## 0.3.127-SNAPSHOT

- `I076`: expose reusable standalone hosted execution for JVM embedding. The
  new `ProtosStandaloneHostedExecution.executeFile(coreRoot, sourceFile, ...)`
  executes one standalone source file through the same Core bootstrap,
  standalone Process bootstrap, Process-scoped Polyglot Context binding and
  canonical initial-module execution as the CLI, on a dedicated guest carrier
  thread with the BUG008 stack budget, and returns the terminal
  `ProtosExecutionOutcome` (the terminal expression's guest value when
  completed). Every Process, Context and runtime-host resource it opens is
  released before it returns. The CLI now consumes the same shared bootstrap,
  binding, host Environment/stream provisioning and direct-file execution
  instead of private copies. Embedded execution installs no CLI conveniences
  such as `print`. No observable Protos semantics, raw `Context.eval`
  behavior or CLI behavior change. Adds embedding tests for recursive
  Fibonacci and factorial terminal values and updates the CLI Polyglot routing
  architecture test to follow the moved code.

## 0.3.126-SNAPSHOT

- `BUG013` (#749, DIST009 #743): repair the Truffle frame lifetime used by the
  frame-backed lexical authority. `InstallFrameLexicalAuthority` now
  materializes its bound `VirtualFrame` at the explicit escape boundary before
  constructing `ProtosFrameLexicalBindingAuthority`, and that authority retains
  only a final `MaterializedFrame`. Context observation is no longer the first
  materialization point, and the captured-materialized-local seam reuses the
  already escape-safe retained frame directly. The I075-D invariant remains
  intact: the authority retains the stable declaring `BytecodeRootNode` and
  resolves the current `BytecodeNode` for local access. The repair preserves
  boxing elimination, the uncached interpreter, yield/materialized-local
  support, and observable Protos semantics. A focused regression verifies that
  the deferred authority already retains a `MaterializedFrame` before
  `ProtosActivation.context()` makes the guest execution context observable.

## 0.3.125-SNAPSHOT

- `I075-D` (#746, PERF011 #693): enable Bytecode DSL boxing elimination with
  `boxingEliminationTypes = {int.class}` on `ProtosBytecodeRootNode`, an
  implementation-internal `int` carrier only; no guest primitive
  representation is introduced. Prerequisite repair:
  `ProtosFrameLexicalBindingAuthority` no longer retains the installation-time
  `BytecodeNode`. It retains the stable declaring `BytecodeRootNode` and
  resolves `getBytecodeNode()` for every frame-backed `LocalRangeAccessor`
  operation, so writes after an uncached-to-cached transition update the
  current node's local metadata coherently. Adds a regression test that
  installs the authority while uncached, transitions across a continuation
  resume, then creates, removes, re-creates and reads the binding. Presence
  remains frame-clear based (D179 C0) and no observable Protos semantics
  change.

## 0.3.124-SNAPSHOT

- `I074` (#745): enable the Truffle Bytecode DSL uncached interpreter by
  setting `enableUncachedInterpreter = true` on `ProtosBytecodeRootNode`, so
  cold roots start in the uncached tier and transition to the cached
  interpreter when they warm up. The annotation processor accepted every
  existing operation unchanged: no operation was adapted and no `forceCached`
  is used. This is internal interpreter machinery only: it changes no
  observable Protos semantics, and no performance claim is made.

## 0.3.123-SNAPSHOT

- `I073` (#744): enable Bytecode DSL bytecode-handler tail-call compilation by
  setting `enableTailCallHandlers = true` on `ProtosBytecodeRootNode`. This is
  internal interpreter machinery only: it introduces no guest tail-call
  semantics, changes no observable Protos semantics, and makes no performance
  improvement claim. Publication metadata for commit `f206eddf`, which omitted
  the version bump and this entry.

## 0.3.122-SNAPSHOT

- `I062` (#665, D169): simplify Core `Path` to relative/downward components.
  `ProtosPathValue` is now one immutable ordered `List<String>` of normal
  components plus its delegation prototype; the rooted flag, the `Parent`
  component kind, and `Component`/`Normal` wrappers are removed. Core `Path`
  installs only `relative`, `child`, `==`, and `hash`; `Path.rooted()` and
  `path.parentComponent()` no longer exist. Structural equality and hash depend
  only on the ordered component Strings. Actor and P transfer copy the
  components with the destination Path prototype. The read-only, confined,
  read-only-tree, and captured-tree Filesystem backends drop their obsolete
  rooted/Parent branches while keeping every confinement, direct-child,
  empty-Path, portable-name, and no-follow rule unchanged. No file-URL to Path
  bridge is implemented. Maintained Protos examples, conformance tests, Java
  tests, the architecture audit counts, and guide 11 are reconciled.

## 0.3.121-SNAPSHOT

- `BUG012` (#742): repair Native Image DAP stack-frame materialization by
  initializing both the explicit external-receiver `NodeLibrary` export owner
  and its generated registration owner at image build time. Keeping
  `ProtosBytecodeTagTreeNodeExports` hosted-initialized preserves the DIST006-C1
  / I069 guest runtime-compilation parsing requirement, while also initializing
  `ProtosBytecodeTagTreeNodeExportsGen` prevents its generated
  `LibraryExport.register(...)` class initializer from registering the same
  receiver again on the first runtime debugger `stackTrace`. The Native
  regression gate now exercises a real DAP breakpoint, suspended thread,
  stack trace, continuation, and clean process termination while retaining the
  existing forced Tier-2 guest-compilation checks. No Protos language semantics
  or Standard Library behavior changes.

## 0.3.120-SNAPSHOT

- `I061` (#664, D168): replace `ProcessArguments` with frozen ordinary Array
  bootstrap snapshots. `process.args()` now returns an ordinary Core `Array` of
  the stable bootstrap argument Strings, frozen before exposure and built from
  the calling Actor's own `Array` prototype on each acquisition; the Process
  runtime retains only the immutable ordered String content. Removed
  `ProtosProcessArgumentsValue`, `ProtosStandardProcessArgumentsProtocol`, and
  every ProcessArguments-specific Actor transfer, P transfer, detached-execution,
  diagnostic, interop, and Bytecode/C-prime path (the structured
  `ProcessArguments.each` operations, capability flag, guarded kind, and
  lowering block). Argument content, order, String-only elements, representability
  failure, and host-mutation isolation are unchanged. Canonical container identity
  across `process.args()` and `process.environment()` acquisitions is no longer
  a guarantee and no test asserts it; the Environment family and its native-name,
  representability, `contains`, and `get` behavior are unchanged. Tests, the
  native-boundary architecture audit, and the process guide were reconciled.

## 0.3.119-SNAPSHOT

- `PERF016` (#727): admit the semantic Integer representation family to a
  guarded selection path. `ProtosValueLookup.lookupGuardedInteger` runs the
  same lookup loop as `lookupGuarded`, exempting only the Integer receiver's
  own represented step (its delegation parent is the prelude's Integer
  prototype, taken from the frozen prelude bindings); every other represented
  value remains on generic lookup. `PrepareSendArguments.guardedIntegerSend`
  caches the selected native Closure, its exact method home (Integer prototype
  for `-`, Number prototype for `>`) and the stability Assumption, keyed by
  selector, entered Context and prelude rather than receiver identity, so
  fresh Integer values hit. The actual receiver and arguments still flow into
  the unchanged `prepareImmediateMethodCall` invocation path; no Integer
  operation is implemented at the send site. Non-native selections and any
  failed guard fall back to the existing exact paths. No observable Protos,
  Standard Library, or public behavior changes; no timing claim is made.

## 0.3.118-SNAPSHOT

- `PERF015` (#726): admit canonical `true`/`false` to the guarded structured
  selection path. `ProtosValueLookup.lookupGuardedCanonicalBoolean` runs the
  same lookup loop as `lookupGuarded`, exempting only the canonical Boolean
  receiver's own represented step (its delegation parent is fixed by
  representation to the root Object); `lookupGuarded` and every other
  represented value remain on generic lookup. `createGuardedStructuredSend`
  uses it for canonical Boolean receivers and admits only selections classified
  by the existing exact canonical Boolean behavior/home helper (`ifTrue`,
  `ifFalse`, `ifTrueIfFalse`, `and`, `or`), reusing the I072 Phase E structured
  Boolean machinery, so a valid hit no longer repeats generic represented-value
  selection. Any other selection falls back to the exact generic path. No
  observable Protos, Standard Library, or public behavior changes; no timing
  claim is made.

## 0.3.117-SNAPSHOT

- `PERF018` (#731): remove the per-logical-Case Core bootstrap and its
  temporary Polyglot Engine/Context from suite-native Test Tool execution.
  `ProtosTestLogicalCaseAttemptBridge` now bootstraps one Core Prelude lazily
  and reuses it for every Case attempt; the per-Case direct-file module
  resolver is bound to each fresh Process's own Polyglot Context and reached
  through the new internal `ProtosContextBoundModuleResolver`. Every attempt
  still creates a fresh semantic Process and Process Context, rematerializes the
  suite declaration, revalidates the discovery signature, resolves the selected
  Test inside that Process, and keeps private stdout/stderr. No observable
  Protos, Standard Library, or Test Tool behavior changes. A regression test
  proves one shared Prelude across Cases whose same-named local modules differ.

## 0.3.116-SNAPSHOT

- `DIST005-D6` (#549): add an explicit, fail-closed publication primitive for
  an already-prepared and already-verified `JVM_PLUS_NATIVE` prerelease.
  `dist/publish_release.py` consumes a separate exact publication authorization
  bound to candidate SHA, public version/tag, release-manifest SHA-256, and
  prerelease/non-draft GitHub Release mode. Before any public mutation it
  revalidates detached clean candidate identity and release-only lineage,
  canonical repository identity, release manifest, notes, archive/checksum
  bytes, the independent multi-asset metadata verifier, local and remote tag
  state, and any existing GitHub Release/assets. Publication creates or safely
  reuses only the exact lightweight `vV` tag, exact `Protos V` prerelease body,
  and the five current downloadable assets: Native ZIP/checksum, portable JVM
  ZIP/checksum, and `RELEASE_MANIFEST.txt`; release notes remain the body rather
  than an asset. Exact partial publication is resumable without tag force,
  asset clobber, deletion, replacement, or automatic rollback, while conflicting
  tags, metadata, bodies, digests, or unexpected assets fail closed. Post-
  publication verification re-reads the remote tag, GitHub Release metadata,
  complete asset set, downloaded asset digests, and authorized manifest identity
  before reporting success. The publication path remains separate from
  `dist/prepare_release.py`; implementation tests use fixtures and local Git
  only and perform no real release publication.

## 0.3.115-SNAPSHOT

- `PERF019` (#736): remove the two independent caller-Actor serialization
  sources that limited Test Tool replacement admission under bounded parallel
  execution. Future completion now supports an exact runtime-only targeted
  continuation handoff: when terminalization wakes the one waiting lane Task,
  that exact queued Task can continue immediately while unrelated Actor-local
  work retains its existing FIFO order and cancellation still defeats the
  handoff. Logical Case execution no longer carries source text through the
  caller Actor; after the source association has been authorized, each host
  execution carrier performs the required fresh UTF-8 read immediately before
  D153 fresh-Process declaration rematerialization, preserving per-Case source
  freshness without reusing discovery source. The Process-snapshot route uses
  the same host-carrier source model. A scheduling regression fixture now waits
  for its host wrapper's physical completion before asserting physical
  completion order, avoiding a race exposed by the faster Future handoff
  without weakening its `[1, 0, 2]` ordering requirement. The canonical
  `--jobs 16` Test Tool run completes all 1263 logical Cases with 16 lanes and
  complete 1247/1247 replacement-pair coverage in 70.84 s at 1096% process CPU;
  mean completion-to-replacement delay falls to 104.643 ms and the selected
  caller-serialization intervals to 60.792 ms. The full repository validation
  passes.

## 0.3.114-SNAPSHOT

- `DIST005-D5` (#549): compose the already-proven DIST001/DIST005 release
  primitives into one deterministic, fail-closed `dist/prepare_release.py`
  candidate-preparation entry point. After an explicit selection record exists,
  the orchestrator materializes or safely resumes one detached release-only
  candidate, builds and completely admits the selected Linux x86_64 Native
  artifact, builds and completely admits the portable JVM compatibility
  fallback, then prepares and independently verifies their common
  `JVM_PLUS_NATIVE` release envelope. Preparation remains strictly separate
  from publication: it creates no Git tag, GitHub Release, or public asset.
  Candidate materialization retains the historical `origin/main` lineage guard
  by default while allowing this orchestrated pre-publication proof to use an
  explicitly selected commit already on local `main`, so implementation can be
  proven before it is pushed. Portable distribution Test Tool admission now
  uses bounded `--jobs` parallelism, aligned with the repository
  `PROTOS_TEST_JOBS=8` default and overridable by that environment variable,
  instead of silently falling back to the Test Tool's serial default. Generated
  release-envelope state is safely replaceable only under the candidate
  `target/` tree, making preparation retryable without weakening candidate
  identity or publication guards.

## 0.3.113-SNAPSHOT

- `DIST005-D4` (#549): add fail-closed clean-candidate machinery for the
  owner-approved Native public artifact under the selected Oracle Linux 10 /
  Linux x86_64 / glibc 2.39 / `-march=compatibility` policy. Native
  public-prerelease builds now reuse the existing DIST001 release identity and
  baseline lineage model while retaining the distinct development-only Native
  path. The Native archive records empirical ELF evidence derived from the exact
  executable bytes, including GLIBC symbol versions and observed maximum,
  interpreter, `DT_NEEDED`, resolved dynamic-library closure, build-host
  provenance, configured Native Image `-march`, and post-link GNU ISA evidence.
  Archive admission independently re-inspects the extracted Native executable
  and requires the recorded evidence to match. The multi-asset
  `JVM_PLUS_NATIVE` envelope now fails closed on missing, malformed, excessive,
  or internally inconsistent GLIBC evidence, unresolved library closure,
  incompatible build ISA policy, or mixed candidate identity. The observed
  post-link ISA evidence is retained as evidence rather than being rewritten as
  the build policy. Final D4 closure still requires the exact clean detached
  `0.3.113` candidate, complete Native admission, portable JVM fallback built
  from the same candidate identity, and real multi-asset envelope verification;
  this changelog entry does not authorize release publication.

## 0.3.112-SNAPSHOT

- `DIST005-D3` (#549): apply the selected public Native platform, CPU ISA, and
  libc ABI metadata policy to the approved `JVM_PLUS_NATIVE` release model.
  The public Native artifact is Linux x86_64, dynamically linked against glibc,
  built on the existing Oracle Linux 10 Native Image toolchain generation, and
  declares `libc_abi_min=2.39`. Native Image compilation now explicitly uses
  `-march=compatibility` so the public artifact does not inherit the platform's
  stronger default x86-64 ISA assumption. Native `RUNTIME.txt`, artifact keys,
  platform identity, release manifests, and release notes expose the selected
  ABI and CPU policy, while the multi-asset envelope fails closed on missing or
  mismatched OS, architecture, libc family, libc ABI floor, linkage, or CPU ISA
  identity. The portable JVM compatibility/fallback artifact and the existing
  release identity model remain unchanged. This slice records and enforces the
  selected policy; clean-candidate proof of the produced binary's actual ABI
  and platform properties remains for the subsequent DIST005-D4 validation
  slice.

## 0.3.111-SNAPSHOT

- `DIST005-D1` (#549): add the multi-asset release-envelope foundation for the
  approved `JVM_PLUS_NATIVE` distribution model. A single exact release
  candidate can now describe and independently verify a recommended Native
  artifact together with the portable JVM compatibility/fallback artifact,
  while preserving the existing single-JVM release-envelope workflow.
  Per-artifact identity covers stable artifact key, kind, role, platform,
  runtime, source/candidate identity, archive checksum, and license/notice
  identity. Multi-asset verification fails closed on mixed candidates,
  duplicate identities, ambiguous recommended roles, missing platform/runtime
  metadata, unknown artifact kinds, checksum mismatches, and incomplete
  license/notice identity, with deterministic artifact ordering. This slice
  does not select a public Native CPU ISA or platform support matrix, build a
  public Native release artifact, or change Protos language semantics.

## 0.3.110-SNAPSHOT

- `DIST005-B` (#549): implement and prove a development Native Image
  self-contained distribution for the current Linux x86_64/glibc target. The
  extracted archive carries the Native Protos executable plus the required
  Protos library, tool, test, license, runtime/dependency identity, provenance,
  and checksum material, and the launcher selects the bundled Native payload
  without probing for Java while preserving the existing JVM execution paths
  when no Native payload is present. Native Image construction now uses a
  recreated thin application JAR rather than feeding a previously shaded JAR
  back into the Native classpath, making repeated Native builds compositionally
  stable. Closed-world reachability retains the LSP4J protocol surface
  structurally, while the Native build keeps Truffle runtime compilation
  enabled. Distribution admission exercises version/help/eval, unrelated-CWD
  source execution, workspace run, Package Tool, focal and complete Test Tool
  execution, real PTY REPL, real LSP stdio, real DAP TCP, archive integrity,
  absence of external Java/Maven, and forced guest compilation of both generated
  Bytecode roots to Truffle Tier 2. No Protos language, specification, Standard
  Library, or public tool semantics change.
## 0.3.109-SNAPSHOT

- `BUG011` (#738): make Test Tool PERF017 JFR admission instrumentation
  capability-optional so the Test Tool runs correctly in GraalVM Native Image,
  where Flight Recorder is not available. PERF017 previously initialized its
  JFR `EventType` eagerly; Native Image therefore raised
  `InternalError: Flight Recorder is not supported on this VM` as soon as the
  Test Tool reported its first lifecycle event. The subsequent Session teardown
  then attempted to close the RuntimeHost while Process Contexts from the
  interrupted execution were still active, masking the primary failure with
  `Polyglot runtime host cannot close while Process Contexts are active`.
  PERF017 now reports disabled when JFR is unavailable while retaining the
  existing JFR event behavior on supported JVMs; the RuntimeHost close
  invariant remains unchanged. The Native Image regression gate now includes
  the canonical `uri/parse.protos` Test Tool smoke and requires its 4/4 result,
  zero exit status, and absence of the Process-Context teardown diagnostic.

## 0.3.108-SNAPSHOT

- `DIST006-C1`: restore Native Image compatibility on the canonical GraalVM /
  Truffle 25.4.4.1.1 baseline without weakening runtime compilation. The
  Bytecode DSL `NodeLibrary` bridge keeps frame/activation extraction
  PE-visible while moving debugger-scope construction and debugger-only member
  projection behind narrow `TruffleBoundary` edges. This prevents the
  tooling-only projection from pulling broad Java collection, debugger,
  logging, reflection, I/O, and SubstrateVM paths into guest runtime
  compilation. Native Image 25.4 builds successfully, both generated Bytecode
  DSL roots still compile at Truffle Tier 2, and the retained native regression
  gate reports `OPT_FAILED=0`, `FRAME_WITHOUT_BOXING_FAILURES=0`, and
  `COMPILATION_FAILURES=0`.
- `DIST006-C1` also binds the Native Image container to the canonical 25.4
  toolchain authority and extends the repository toolchain verifier so
  all-surface verification detects stale Native Image channel/JDK bindings
  while development-scope verification remains unaffected.

## 0.3.107-SNAPSHOT

- `PERF017` (#729): add Test Tool-private JFR instrumentation for the
  completion-to-replacement admission path without changing scheduling,
  concurrency, result ordering, lifecycle, cancellation, Process/Context
  ownership, or public Test Tool output. The internal
  `protos.TestToolPerf017Admission` event timestamps host Case completion,
  caller-domain completion-task entry, Future terminalization, lifecycle
  terminal/start observation, submission entry/return, and replacement-carrier
  entry. Stable diagnostic `operationId` values correlate one host operation,
  while weakly held `laneId` values correlate the existing pull-lane Task
  across lifecycle and execution seams. The ordinary logical-Case facility
  and Process-snapshot facility emit the same schema. This slice is
  diagnostic-only: it establishes measurement points for quantitative
  attribution of lost `--jobs` scaling and does not claim a scheduling cause
  or implement a performance fix.

## 0.3.106-SNAPSHOT

- `TOOL009-F-I1`: consolidate the F-I1 slice of the conformance test corpus
  (`boolean`, `float`, `integer`, `number`, `numeric-conversion`,
  `numeric-equality`, `equality`, `string`, `object`, `object-structural`,
  `path`, `call`, `core-surface`, `matching`, `network`, `surface-sugar`) from
  282 one-Test-per-file suite-native sources down to 58 sources grouped by
  semantic/behavioral cohesion (shared corpus, `ExecutionRequirement`,
  namespace, bootstrap, and execution authority), per the ratified
  `TOOL009` source-granularity policy. Every existing `Test(...)` is preserved
  verbatim as an independent entry in its consolidated source's `tests`
  Array: 282 Logical Cases before and after, with exact selector/name and
  body preservation confirmed by full-corpus reconciliation. `manifest.tsv`
  is updated to the 58 consolidated paths. `network/network-prototype.protos`
  is retained unchanged as the sole reused single-case source for its family.
  Two live-path regression fixtures
  (`protos/tests/tooling/tool002-d2-manifest-plan.protos`,
  `protos/tests/tooling/tool002-e1b-package-toml-filesystem.protos`) and
  `ProtosTestToolManifestPlanTest.java` are updated from the retired
  `integer/add-small.protos` first-manifest-row identity to the new first row,
  `integer/arithmetic-and-unary.protos`. The suite-native source-granularity
  policy (semantic/behavioral cohesion as the grouping unit; one-Test-per-file
  and minimum file count are not repository conventions) is now documented in
  `protos/AGENTS.md`.

- Fix a syntax defect introduced by the automated `TOOL009-F-I1`/`TOOL009-F-I2`
  conformance-corpus consolidation: consecutive `Test(...)` entries inside a
  consolidated source's `tests: [ ... ]` Array were separated only by a
  logical `NEWLINE`, with no comma. Per `spec/PROTOS_GRAMMAR.md` §12.3/§17, an
  Array construction expression desugars to an ordinary call
  (`[a, b] -> Array(a, b)`), and a `NEWLINE` is never an argument separator; a
  comma is required between every two elements. Every affected source failed
  to parse (`bin/protos <file>` raised `Syntax error: Expected ']' but found
  IDENTIFIER`), and under the Test Tool the same parse failure surfaced only
  as an opaque `Test tool error: Object {}`, because Discovery detaches a
  failed declaration execution into a generic `Error` with no local slots.
  Insert the missing comma after every `Test(...)` entry's closing `})` that
  is immediately followed by another `Test(...)` entry, across the 53
  affected sources under `protos/tests/conformance/{bytes,collections,
  control,encoding,future,reflection,text-reader,text-writer}/` (229 sites);
  no test body, selector/name, or `tests` Array element count changes. No
  production or Standard Library source is affected, so no implementation
  version increment applies to this change.

## 0.3.105-SNAPSHOT

- `PERF014` (#725) follow-up: the direct Closure-call guarded specialization
  gains a second, definition-keyed cache tier on `PrepareClosureCall`,
  `PrepareClosureCallArguments`, and `PrepareDefaultClosureCallArguments`,
  mirroring `PrepareSendArguments.fastOrdinarySend`'s existing institution for
  ordinary sends. The first tier alone keyed admission on exact receiver
  instance identity (`receiver == cachedReceiver`, `limit = 3`), which paid
  full specialization-establishment cost (a fresh guarded D013 lookup plus a
  new `Assumption`) for every distinct `ProtosClosureValue` materialization of
  the same Closure definition, converging to the generic fallback only after
  three misses. The new `fastDirect` specialization instead keys the cached
  Context-owned target on `ProtosClosureValue.definition()` — the immutable
  `CanonicalClosure` identity shared by every materialization of one Closure
  literal — while still performing the authoritative D013 `call` selection
  fresh on every hit via `directClosureCallSelectionOrNull`, so a local
  override on one particular instance is observed exactly as before. A
  controlled baseline-vs-worktree `--jobs 8`/`--jobs 16` comparison confirmed
  receiver-identity churn as the material cause of a Protos test-suite
  concurrency-adjacent slowdown observed after the first tier alone, and
  confirmed this second tier resolves it without any observable Protos
  semantic change.

## 0.3.104-SNAPSHOT

- `BUG010` (#728): the Test Tool's normal D120 progress is now observable
  from terminal logical Case events while a `protos test` invocation is
  still running, instead of only after every logical Case has already
  completed.

  `protos/tools/test/Main.protos` previously created every suite's
  `Progress` state and drove `Progress.observer(...)` only after
  `LogicalCaseRunner.run(...).value()` had already awaited the complete
  logical run, then grouped and replayed the already-finished completions
  through `Progress.observer(...)` in a single post-run pass. Even the
  initial `[phase] 0/N` line could not reach the reporting backend until the
  whole invocation had finished.

  `protos/tools/test/LogicalCaseRunner.protos` gains a new optional,
  backward-compatible trailing parameter on `run(...)`,
  `completionObserver = null`, called `(index, completion)` at the exact
  point an individual logical Case already crosses the existing terminal
  barrier — the narrow, presentation-neutral counterpart of the D120
  `terminalObserver` seam already established on the D108 path in
  `Runner.protos`. `Main.protos` now creates every suite's `Progress` state
  and `Progress.observer(...)` before scheduling begins, and wires a new
  `logicalCompletionObserver` through this seam to classify and forward
  each completion to its suite's observer immediately, independently of
  when the remaining Cases complete. The final `logicalResults` projection,
  exit-code classification, `--jobs` bounded admission, D174/D176
  lifecycle/watchdog behavior, and guest stdout/stderr privacy are
  unchanged; stable logical TestPlan result order is preserved exactly as
  before.

## 0.3.103-SNAPSHOT

- `PERF014` (#725): direct Closure-call invocation (`operation()`,
  `operation(args)`, and default-argument call positions) gains a guarded
  fast-hit specialization on `PrepareClosureCall`, `PrepareClosureCallArguments`,
  and `PrepareDefaultClosureCallArguments`, converging with the I072-A
  guarded-selection institutions `PrepareSendArguments` already established
  for ordinary sends instead of introducing a parallel mechanism.

  A new shared helper, `createGuardedDirectClosureCall`, reuses
  `ProtosValueLookup.lookupGuarded("call")` to obtain the authoritative D013
  selection under a selector-specific `Assumption`, admits the fast hit only
  when the selection is exactly the canonical `Object.call` implementation
  (`ProtosStandardObjectProtocol.isCanonicalStandardCallSelection`) on a
  non-native, ordinary source-backed Closure receiver, and reuses
  `PrepareSendArguments.fastOrdinarySendTarget` for the Context-owned
  Bytecode activation target (including foreign-Context projection). A valid
  hit builds the ordinary `ProtosActivation.forClosureInvocation` shape and
  the existing shared `EnterClosureCall.direct` `DirectCallNode` exactly as
  before, without repeating the D013 lookup or the
  `finishPreparingComposedCallByImplementation` structured-protocol
  classification against the ~14 native Object/Boolean/Array/Map/etc.
  callback shapes on every call. Any override, shadowing, native body,
  non-canonical selection, or projection failure misses and falls through to
  the exact, unmodified generic `prepareClosureCall` path, so call
  shadowing, replacement, and removal/recreation invalidation remain exactly
  as observable as before.

  This slice is structural/product-correctness only: it does not change the
  causal timing conclusion from the PERF010-B/PERF013 checkpoint and does not
  touch `protos-benchmarks`. A separate controlled-timing checkpoint measures
  whether the closure-call/guest-call increment actually falls by a
  multiplicative factor.

## 0.3.102-SNAPSHOT

- `PERF013` Slice B2 (#724): a proven `CapturedResolved` lexical write whose
  owner root shares the current lowering group (Slice A/A2/A3, Slice B1) now
  compiles to new `ResolveCapturedMaterializedWritableLexicalTarget`/
  `AssignCapturedMaterializedLocal` Bytecode operations instead of the
  runtime-authority `ResolveCapturedWritableLexicalTarget`/
  `AssignCapturedFrameLocal` path, mirroring Slice B1 on the write side. Both
  operations share the same constant `MaterializedLocalAccessor` operand,
  built from the owner's own `BytecodeLocal`, so the accessor's identity is
  proven and partial-evaluation constant at both resolution and assignment
  time; only the owner's retained `MaterializedFrame` is looked up
  dynamically.

  The destination is still fully selected before the RHS is evaluated, and
  `AssignCapturedMaterializedLocal` only revalidates (via `isCleared`) and
  writes the exact selection `ResolveCapturedMaterializedWritableLexicalTarget`
  already returned; it never re-resolves or retargets the destination after
  the RHS runs, so late nearer-binding creation/removal/recreation during RHS
  evaluation keeps the exact existing D179 C0 observable semantics.
  `CapturedLexicalWriteTarget` gained a `materializedOwnerFrame` variant
  alongside its existing generic/frame-backed ones. `CLOSED` destinations
  remain writable and `FROZEN` destinations are rejected exactly as before.

  `CanonicalToBytecodeLowerer.emitBodyAssign` and `emitDefaultAssign` both
  reuse the existing `capturedOwnerBytecodeLocal`/`frameLocalsByScope`
  infrastructure from Slice B1 to select the new fast path only when the
  owner's `BytecodeLocal` is actually available in the current shared
  `BytecodeRootNodes` group; otherwise they fall back to the unchanged
  runtime-authority path, still necessary for an isolated Closure
  rebuild/rematerialization that does not include its lexical owner in the
  same group (PERF013 Slice C).

  Slice B1 captured reads (`ReadCapturedMaterializedLocal`) are unchanged.
  This slice changes no observable Protos semantics.

## 0.3.101-SNAPSHOT

- `PERF013` Slice B1 (#724): a proven `CapturedResolved` lexical read whose
  owner root shares the current lowering group (Slice A/A2/A3) now compiles
  to a new `ReadCapturedMaterializedLocal` Bytecode operation instead of the
  runtime-authority `ReadCapturedFrameLocal` path. The operation's constant
  operand is a Truffle Bytecode DSL-generated `MaterializedLocalAccessor`,
  built from the owner's own `BytecodeLocal` (never a hand-rolled
  `rootIndex`), so the accessor's identity is proven and partial-evaluation
  constant at lowering time; only the owner's retained `MaterializedFrame`
  (already held by `ProtosFrameLexicalBindingAuthority`, exposed through a
  new `retainedMaterializedFrameForCapturedAccess` seam) is looked up
  dynamically. `ProtosBytecodeRootNode` now enables
  `enableMaterializedLocalAccesses`.

  `CanonicalToBytecodeLowerer` gained a backend-private
  `frameLocalsByScope` registry mapping each genuine lexical scope to the
  `BytecodeLocal`s created for it, populated by `emitRootBody` before that
  root's own body (and therefore any nested Closure/object-body root) is
  lowered. `emitLookup` now uses this registry, together with the
  already-present (previously unused) `capturedOwnerMatchesCurrentRoot`
  structural check, to select the new fast path only when the owner's
  `BytecodeLocal` is actually available in the current shared
  `BytecodeRootNodes` group; otherwise it falls back to the unchanged
  `ReadCapturedFrameLocal` path, which remains necessary for an isolated
  Closure rebuild/rematerialization that does not include its lexical owner
  in the same group (PERF013 Slice C).

  The exact D179 C0 presence/fallback algorithm (current-context check,
  nearer-context presence guard, `isCleared`-based `PRESENT(null) != ABSENT`
  distinction, owner-removal/recreate observation) is preserved unchanged;
  only the read's physical local-identity/frame-access mechanism changes.
  Captured writes are untouched (`ResolveCapturedWritableLexicalTarget`/
  `AssignCapturedFrameLocal`); that migration is PERF013 Slice B2.

  Existing instruction-identity assertions in
  `ProtosI068Slice5CapturedMaterializedLexicalLoweringTest` and
  `ProtosI068Slice7ActivationLexicalDecompositionTest` are updated for the
  same-group proven captures whose selected instruction changed; the
  isolated-rebuild/Candidate/Dynamic assertions that must keep observing the
  old runtime-authority path are unchanged.

## 0.3.100-SNAPSHOT

- `PERF013` Slice A3 (#724) closes the last remaining independent
  `BytecodeRootNodes` group: an object-construction helper root (the body of
  an object literal, e.g. `{ x: 1 }` or `obj: { method: () => x }`) now
  lowers into the exact same shared `BytecodeRootNodes<ProtosBytecodeRootNode>`
  group as the lexical lowering unit that contains the object literal,
  instead of opening an independent lowerer/`create()` call. A Closure
  declared inside that object body continues to nest into the same group via
  the existing Slice A/A2 `bytecodeClosurePlan`/`lowerNestedClosureRoot`
  mechanism, so owner root, object-construction helper root, and any
  arbitrarily deep nested Closures inside the object body now all belong to
  one physical group.

  `CanonicalToBytecodeLowerer`'s `CanonicalObject` validation cases
  (`validateSupportedDefaultExpression` and `validateSupportedExpression`) no
  longer eagerly build the object's helper root; they only register
  `CanonicalCompose` reserved-slot-name metadata and perform a
  structural-support walk of the object body, mirroring the structural-only
  treatment already used for `CanonicalClosure`. Real emission
  (`emitBodyObjectLiteral`/`emitDefaultObjectLiteral`) resolves a new
  backend-private, freeze-once `ProtosObjectBodyTargetCell` — the
  object-construction analogue of `ProtosClosureExecutionPlanCell` — before
  opening `PrepareObjectConstruction`, deferring the helper root's real
  `RootCallTarget` until the owning group's `create()` call returns. The
  now-dead independent-construction path (`bytecodeObjectBodyTarget`,
  `bytecodeObjectBodyTargets`, `lowerObjectBodyRoot`) is removed.

  Sharing this physical root group does not change object-construction
  semantics: an object-construction body remains lowered with
  `genuineExecutionContextRoot=false`, so it still never receives a
  frame-backed lexical-binding authority (`ProtosFrameLexicalLayout`/
  `ProtosFrameLexicalBindingAuthority`), and it is still not a lexical
  capture scope. A Closure created during object construction continues to
  capture only the enclosing genuine lexical execution context(s)
  (`ProtosActivation.forObjectConstruction`/
  `lexicalContextsForClosureCapture` are unchanged), never the constructed
  object itself, while the constructed object remains the Closure's
  receiver. Object slots (e.g. `x` in `{ x: 1 }`) remain receiver state, not
  lexical locals. `PrepareObjectConstruction`/`EnterObjectConstruction`/
  `ResumeObjectConstruction`/`FinishObjectConstruction`'s suspension protocol
  is unchanged apart from `PrepareObjectConstruction` now consuming a
  `ProtosObjectBodyTargetCell` instead of a plain constant `RootCallTarget`.

  This slice does not adopt `MaterializedLocalAccessor` (PERF013 Slice B) and
  does not change the captured-local read/write mechanism
  (`ReadCapturedFrameLocal`/`AssignCapturedFrameLocal`), which remains
  exactly as before.

## 0.3.99-SNAPSHOT

- `PERF013` Slice A2 (#724) closes the one exception Slice A retained: a
  parameter default-value Closure literal (for example `g` in
  `f: (x, g = () => x) => g`) now lowers into the exact same shared
  `BytecodeRootNodes<ProtosBytecodeRootNode>` group as its lexical owner and
  any ordinary body Closure, instead of opening an independent
  lowerer/`create()` call. `CanonicalToBytecodeLowerer.validateSupportedDefaultExpression`
  now performs structural-support validation only for a default-value
  Closure — mirroring the structural-only check `validateSupportedExpression`
  already used for an ordinary body Closure — rather than eagerly
  constructing its execution plan/root before the owner root's builder is
  even open. Real emission (`emitClosureParameterBindings` ->
  `emitExpression`) already ran, and continues to run, while that builder is
  open, so it now reaches the same `bytecodeClosurePlan`/
  `lowerNestedClosureRoot` path an ordinary nested Closure uses, recursively,
  to arbitrary default-expression nesting depth. The now-unreachable
  independent-construction machinery
  (`independentBytecodeClosurePlan`, `ProtosClosureExecutionPlanCell.independent`,
  `isFromIndependentGroup`) is removed.

  This closes the last known lexical-root grouping exception ahead of PERF013
  Slice B's `MaterializedLocalAccessor` adoption. No observable Protos
  semantics change: capture-by-reference, sequential parameter
  establishment, default-expression evaluation only when the argument is
  absent, earlier-parameter visibility in later defaults, `D179` C0,
  `I071`, the object-construction-body boundary, and `RootTag`/tooling
  architecture are all unaffected. The captured-local read/write mechanism
  itself (`ReadCapturedFrameLocal`/`AssignCapturedFrameLocal`) remains
  unchanged and is still PERF013 Slice B's responsibility; this slice does
  not adopt `MaterializedLocalAccessor`.

## 0.3.98-SNAPSHOT

- `PERF013` Slice A (#724) restructures `CanonicalToBytecodeLowerer` so a
  lexical owner root and every Closure it lexically contains now lower into
  one shared `BytecodeRootNodes<ProtosBytecodeRootNode>` group: a Closure
  reached while an enclosing root's own `beginRoot()`/`endRoot()` pair is
  still open nests its own `beginRoot()`/`endRoot()` pair inside that same
  open builder/`create()` invocation (recursively, to arbitrary lexical
  depth), instead of opening an independent lowerer/`create()` call the way
  every Closure did before this slice. This is the prerequisite topology
  PERF010-B/PERF012 identified for PERF013 Slice B's `MaterializedLocalAccessor`
  adoption, which requires the reading child root and its lexical owner's
  root to belong to the same group.

  Because `RootNode.getCallTarget()` refuses until the whole group's
  `create()` call returns, a nested Closure's real `ProtosClosureExecutionPlan`
  cannot be constructed at the exact point its `MaterializeClosure` bytecode
  is emitted. `ProtosClosureExecutionPlanCell` is a new backend-private,
  immutable-once-frozen indirection emitted as that bytecode's constant
  instead: the lowerer freezes it with the real plan once the owning group's
  `create()` call returns. It is never guest-visible and is always frozen
  before any guest code in the affected lowering unit can execute, so it does
  not introduce a second, mutable execution-plan authority. A Closure used as
  a parameter default value keeps its previous independent construction path
  (validated/lowered before its owner's own `create()` call is even open, so
  no shared builder exists to nest it in) unchanged.

  `ProtosFrameLexicalLayout` remains precomputed per root exactly as PERF012
  left it; object-construction bodies remain non-lexical-owner roots with
  their own independent `BytecodeRootNodes` group, unchanged; `RootTag`/
  tooling architecture, capture-by-reference, `D179` C0, `I071`, and every
  other observable Closure/capture semantic are unaffected — this slice is a
  lowering-topology change only. The captured-local read/write mechanism
  itself (`ReadCapturedFrameLocal`/`AssignCapturedFrameLocal`) is unchanged
  and remains PERF013 Slice B's responsibility.

## 0.3.97-SNAPSHOT

- `PERF012` (#723) removes the JDK `LinkedHashMap`/`ArrayList` construction
  that previously ran inside `ProtosFrameLexicalBindingAuthority`'s
  constructor on every installation of a genuine `ROOT`/`CLOSURE` execution
  context's frame-backed lexical authority. That construction rebuilt an
  already-static name/ordinal layout — the same declaration-order names
  `CanonicalToBytecodeLowerer` already carried as a lowering-time constant —
  from scratch on every invocation of the owning root, and PERF010-B Step 0
  identified it as the cause of a "too deep inlining" partial-evaluation
  bailout on Bytecode roots whose frame-backed layout was large enough to
  expand through JDK `HashMap`/`Class` generic-signature machinery.

  The name/ordinal layout is now `ProtosFrameLexicalLayout`, an immutable,
  backend-private descriptor built exactly once per root during
  `CanonicalToBytecodeLowerer` lowering and installed as a Bytecode
  `ConstantOperand` in place of the previous raw `String[]`. Every
  per-invocation `ProtosFrameLexicalBindingAuthority` instance now holds a
  reference to that one shared layout instead of rebuilding its own copy;
  only the authority's runtime frame/dynamic-overflow state remains
  per-invocation. The layout carries no binding values or presence state of
  its own, so this remains a single frame-backed authority per genuine
  execution context, not a second store.

  `PLAT036` Candidate D's frame-backed-single-authority shape, `D179` C0
  clear/recreate, `I071` frame-native cleared-presence semantics,
  `PRESENT(null) != ABSENT`, dynamic overflow, capture-by-reference,
  establishment/name order, and debugger/reflection projection are
  unaffected: the same authority object, the same `LocalRangeAccessor`
  physical storage, and the same presence derivation are used exactly as
  before. No observable Protos semantics change.

## 0.3.96-SNAPSHOT

- `I072` Phase E (slice 2) extends structured-control convergence to the
  remaining collection-callback families admitted by the existing structured
  capability vector: `Array.each`, `Bytes.each`, `ProcessArguments.each`,
  `Environment.each`, `IdentityMap.each`, `Map.each`, the `Map` read-lookup
  family (`at`/`containsKey`/`atIfAbsent`), `Map.atPut`, and `Map.remove`. The
  `guardedStructuredSend` specialization added in slice 1 now classifies a
  stable canonically-selected send against all thirteen structured kinds using
  the existing canonical-selection helpers (behavior identity plus home/
  provenance, or receiver identity for `ProcessArguments`/`Environment`), and
  feeds the single cached kind into the unchanged `finishPreparingComposedCall`
  path; collection-callback order, snapshot timing, mutation visibility,
  suspension/resume and error propagation are unaffected.

  The slice also converges the four remaining structured paths that were still
  classified only from native-body identity at native-body execution time:
  `Object.caseOf`, `Array.match`, `Map.match`, and `IdentityMap.atIfAbsent`.
  These four are outside the `StructuredCallCapabilities` vector — `NativeCall`
  recognizes them from `nativeBody` identity independent of that vector — so a
  canonically-selected ordinary send to one of them previously still paid the
  full generic D013 lookup and classifier scan on every hit even though the
  other thirteen kinds had already converged. New canonical-selection helpers
  (`ProtosStandardObjectProtocol.isCanonicalStandardCaseOfSelection`,
  `ProtosStandardArrayProtocol.isCanonicalStandardMatchSelection`,
  `ProtosStandardMapProtocol.isCanonicalStandardMatchSelection`, and the
  existing `ProtosStandardIdentityMapProtocol.isCanonicalStandardAtIfAbsentSelection`)
  let `guardedStructuredSend` recognize a stable canonical selection of these
  four and route it through the same guarded fast path; the resulting
  `NativeCall` is unchanged, since its `isStructuredCaseOf`/`isStructuredMapMatch`/
  `isStructuredArrayMatch`/`isStructuredIdentityMapAtIfAbsent` accessors already
  recognize the operation from `nativeBody` regardless of how the call was
  prepared. `Object.call`, standard import, and the generic
  `finishPreparingComposedCallByImplementation` classifier remain unchanged
  and still required by the cache-miss/megamorphic, direct-Closure-invocation,
  and Object.call/import paths, which do not carry D013 selection provenance
  for the guarded specialization to reuse. This completes the structured
  families identified for `I072` Phase E; no dead compatibility machinery was
  found to remove. No observable Protos semantics change.

## 0.3.95-SNAPSHOT

- `I072` Phase E (slice 1) begins structured-control convergence: a stable,
  canonically-selected send to `Object.ensure`, `Error.handle`, `Object.while`,
  or a Boolean callback selector (`ifTrue`/`ifFalse`/`ifTrue:ifFalse:`/`and`/
  `or`) no longer repeats the general D013 lookup and the generic
  `finishPreparingComposedCallByImplementation` classifier scan on its valid
  hit. A new `guardedStructuredSend` specialization on `PrepareSendArguments`
  reuses the exact Phase A `ProtosValueLookup.lookupGuarded`/`Assumption`
  contract, classifies the selected value exactly once against the existing
  canonical-selection helpers (behavior identity plus home/provenance, never
  native-body identity or selector spelling alone), and feeds the cached kind
  directly into the unchanged `finishPreparingComposedCall`/
  `PreparedClosureCall.nativeCall` path, so execution, suspension, cleanup and
  error semantics are byte-identical to the generic path. Any override, alias
  at a noncanonical home, or invalidated selection falls straight through to
  the exact existing generic path, unchanged. The remaining structured
  families (`Array`/`Bytes`/`ProcessArguments`/`Environment`/`IdentityMap`/
  `Map` callbacks, `Map` read/`atPut`/`remove`, `Object.caseOf`/`match`) and
  the corresponding dead-compatibility-machinery review remain open follow-up
  slices of `I072` Phase E; `StructuredCallCapabilities` and the generic
  classifier cascade are unchanged and still required by those paths. No
  observable Protos semantics change.

## 0.3.94-SNAPSHOT

- `I072` Phase D completes the prepared-call/control-mode separation left open
  by the D1 slice. The internal `PreparedClosureCall` invocation carrier is no
  longer one universal class; it is a small dispatch interface implemented by
  three mutually exclusive leaf representations — a lean `OrdinarySourceCall`
  (rich-activation and compact frame-argument shapes), `NativeCall` (native
  body plus the D1 `StructuredCallCapabilities` holder), and
  `ModuleInitializationCall` (standard-import module lifecycle). An ordinary
  source-backed, nonsuspending call now physically carries only a selected
  target, its compact frame arguments (or pre-target activation), and the
  return-home/ownership state non-local return completion requires; it never
  allocates native-body, structured-control-capability, or
  module-initialization fields (PLAT040 Candidate F′: pay only for what is
  used). Return-home ownership/completion and non-local-return handling are
  shared between the ordinary and native shapes through a small internal base
  class instead of being duplicated. Task-owned dispatch, C-prime suspension
  and continuation resume, guarded selector-specific stability (Phase A), the
  compact frame-argument ABI (Phase B), and conditional guest-Context/Array
  materialization (Phase C) are unaffected; no observable Protos semantics
  change. Structured-control convergence remains Phase E, not started here.

## 0.3.93-SNAPSHOT

- `I072` Phase D (slice 1) removes the physical structured-control capability
  vector from the ordinary/native/module-initialization forms of the internal
  `PreparedClosureCall` invocation carrier. The sixteen mutually-exclusive
  `structuredEnsure`/`structuredWhile`/`structuredBoolean`/collection-callback/
  `structuredObjectCall`/`structuredImportRuntime`/etc. fields are now grouped
  behind a single nullable `StructuredCallCapabilities` holder that is only
  allocated when a call genuinely owns one of those capabilities (PLAT040
  Candidate F′: optional semantic capability must not force unrelated ordinary
  calls to carry its full physical machinery). Ordinary source-backed calls,
  compact frame-argument calls, plain native calls, and module-initialization
  calls now pass `null` and pay no allocation or field cost for structured
  control. Every mutual-exclusion check, dispatch guard, and error message is
  preserved exactly; no observable Protos semantics change. Full type-level
  separation of the ordinary call path from the shared carrier class remains
  open for a later Phase D slice.

## 0.3.92-SNAPSHOT

- `I072` Phase C removes unconditional guest execution-context materialization
  from ordinary optimized source-backed calls while preserving the Phase A
  selector-specific lookup guard and the Phase B compact frame-argument ABI.
  The current root now operates primarily against the single frame-backed
  lexical authority; a genuine guest `Context` object is materialized only when
  guest semantics observe, capture, escape, reflect, or otherwise require it.
- Supplied positional arguments remain an internal compact vector through
  ordinary parameter/default binding instead of being wrapped eagerly in a
  frozen guest `Array`. Guest Array materialization remains available on demand,
  and rest binding still creates the semantically required fresh frozen Array.
- Current-local create/read/assign paths, unqualified writable-target
  resolution, captured-lexical nearer-binding checks, and repeated-root lexical
  authority handoff now avoid forcing a guest Context while preserving
  `PRESENT(null) != ABSENT`, D179 remove/recreate behavior, capture by reference,
  late nearer creation/retargeting, receiver fallback, evaluation ordering, and
  one authoritative lexical store.
- Frame materialization is deferred until the execution context becomes
  guest-observable. Closure capture and debugger/reflection projection still
  force the required escape-safe materialization, while ordinary execution does
  not. Focused Java regressions, the execution-context conformance corpus,
  structural falsification of the Phase A/B/C call path, and the integrated
  `make test` gate pass with no observable Protos semantic change.

## 0.3.91-SNAPSHOT

- `I072` Phase B implements the PLAT040 compact ordinary-call frame-argument ABI
  for admitted source-backed guarded sends. The Phase A selector-specific
  stability guard, selected Closure, exact `methodHome`, and Context-owned
  `RootCallTarget` remain authoritative; valid guarded hits still avoid general
  D013 lookup/delegation traversal and generic selected-value classification.
- Ordinary source-backed target entry no longer requires a preconstructed rich
  `ProtosActivation`. The call crosses the Truffle boundary with compact frame
  arguments carrying the selected Closure, receiver, exact `methodHome`, caller
  provenance, return-home token, and supplied positional values. Captured lexical
  state remains owned by the Closure, and the existing rich activation,
  execution-context, and frozen guest argument Array are materialized only after
  target entry by the semantic Bytecode adapter where current semantics require
  them.
- Existing generic/native fallbacks and prepared control/suspension machinery
  remain intact. Receiver/`this`, method-home, positional/default/rest/spread
  argument behavior, fresh activation/execution-context identity, capture by
  reference, non-local return, Error/dynamic control, Task ownership,
  suspension/resume without replay, debugger/reflection projection, and
  multi-Context execution remain unchanged. Generated Bytecode DSL structure and
  the complete Maven `verify` gate validate the Phase B boundary with no
  observable Protos semantic change.

## 0.3.90-SNAPSHOT

- `I072` Phase A implements the PLAT040 guarded selected-send substrate for
  ordinary stable receiver chains. D013 selection is established once under a
  selector-specific Truffle `Assumption`; the valid monomorphic hit reuses the
  selected Closure, exact `methodHome`, and Context-owned `RootCallTarget`
  without repeating general lookup, delegation traversal, or selected-value
  classification.
- Slot replacement, removal, and nearer same-selector additions invalidate the
  exact dependent lookup assumptions, while unrelated selector/object mutation
  does not. Unsupported represented/subclass/context-backed chains retain the
  authoritative generic path. The existing activation, execution-context,
  argument, suspension/control, and generic fallback representation is preserved,
  with no observable Protos semantic change.

## 0.3.89-SNAPSHOT

- `I069` implements the PLAT038/PLAT039 Native Image runtime architecture as a
  first-class Maven `native` build path while retaining the Stage1 JVM path.
  The native profile uses GraalVM Build Tools to produce the `protos` executable
  and generates its Native Image class-initialization arguments as a dedicated
  build step rather than coupling native packaging to ordinary JVM builds.
- Native Image initialization is now deterministic and deliberately narrower
  than package-wide `--initialize-at-build-time`. The generated policy includes
  the language provider and generated Truffle library exports, both generated
  Bytecode DSL root structures, every generated helper and semantic operation
  node, and the narrowly required hosted constants
  `ProtosTextReaderCPrimeExecution.Advance` and
  `ProtosCoreErrors.StandardError`. The validated policy contains 311 unique
  build-time initialized classes.
- The PLAT039 PE-visible guest kernel was audited across bytecode execution,
  closure invocation, argument/default/rest binding, frame-backed lexical
  access, object/member lookup, module execution-plan caching, continuation and
  suspension handling, Future/Task/Actor state, parallel execution, Core value
  operations, standard protocols, debugger/interop projections, and guest
  callbacks. Guest-runtime paths that previously exposed opaque iterator,
  collection-view, stream, Optional, or structurally unsuitable access shapes
  were replaced with PE-visible indexed or explicit structural operations where
  required.
- Lexical and object-state helpers now provide explicit ordered projections for
  PE-sensitive traversal while preserving a single authoritative binding store,
  stable frame-backed local identity, `PRESENT(null) != ABSENT`, capture by
  reference, current/captured fallback semantics, and the PLAT036/I068
  frame-backed execution-context architecture. No second lexical authority or
  observable Protos semantic change is introduced.
- Native host/cold edges are isolated with narrow Truffle boundaries where the
  operation is genuinely outside the guest PE kernel, including selected host
  filesystem/network/runtime and cold materialization paths. Ordinary guest
  send/call/lookup, continuation decisions, guest callbacks, runtime state, and
  the main Bytecode DSL execution loops remain PE-visible; no broad
  enter/resume boundary is introduced.
- Standard Object `caseOf`, IdentityMap `at:ifAbsent:`, Map matching, and Array
  matching fast paths now use explicit marker `ProtosNativeClosureBody`
  implementations instead of identity-sensitive lambda singleton bodies. This
  preserves the existing protocol behavior while keeping those native closures
  structurally safe for Native Image hosted/runtime compilation.
- Runtime compilation support was validated on the produced Native Image rather
  than accepting an interpreter-only executable. The final image reports 2,132
  runtime-compiled methods, and forced compilation completes successfully for
  both `ProtosBytecodeRootNodeGen` and
  `ProtosSemanticBytecodeRootNodeGen` at Truffle Tier 2 with `OPT_FAILED=0` and
  no `FrameWithoutBoxing` materialization failure.
- Native `--version`, `--help`, and basic guest evaluation all pass, followed by
  the complete Maven `verify` gate. The investigation-only Native Image graph,
  blocklist, frontier, policy, and PE-audit Python programs used during I069 are
  not retained as product tooling; the durable native build inputs are
  `build/native/Dockerfile` and `build/native/generate-init-args.sh`.
- Native Image developer tooling is now isolated under `build/native/`.
  `make -C build/native build` builds the Stage1 Native Image executable, while
  `make -C build/native test` rebuilds it and runs the retained Native regression
  suite. The regression suite validates `--version`, `--help`, basic guest
  execution, and forced Truffle guest compilation for both generated Bytecode
  DSL roots at Tier 2, requiring `OPT_FAILED=0` and guarding against recurrence
  of the `FrameWithoutBoxing` materialization failure observed during I069.


## 0.3.88-SNAPSHOT

- `I071` / D179 Candidate C0 with implementation Candidate E restores
  execution-context `PRESENT -> ABSENT` removal on top of the PLAT036/I068
  frame-backed lexical architecture. `ProtosExecutionContextValue` again uses
  the ordinary open/closed/frozen structural rules instead of the superseded
  C3 monotonic-membership rejection.
- Statically admitted bindings keep their existing Bytecode DSL frame-local
  identity and ordinal. Removal uses the existing frame-local cleared state as
  semantic absence; later legal re-creation reuses the same frame-backed
  binding rather than introducing a sentinel, bitmap, dynamic-map migration,
  or second binding authority.
- `ReadFrameLocal` is now presence-aware: a cleared current local takes the
  exact lexical/receiver fallback path instead of reading an absent frame
  local. Captured frame-backed reads retain their existing presence/topology
  guards.
- Conformance coverage now exercises current-local removal revealing an outer
  lexical binding, receiver fallback after removal, captured/escaped removal,
  remove/recreate, PRESENT-null versus ABSENT, CLOSED/FROZEN rejection, and
  ordinary-object removal. Focused Java coverage additionally proves stable
  frame layout across clear/recreate, debugger/reflection hiding of cleared
  bindings, and that a captured assignment destination fixed before RHS
  evaluation is not retargeted if the RHS removes that destination.
- No PLAT036/I068 architecture is reopened, no broad Truffle boundary is added,
  and the change remains compatible with PLAT039's PE-visible guest-kernel
  boundary.

## 0.3.87-SNAPSHOT

- `I068` / PLAT036 Candidate D, Slice 7 ("activation lexical
  decomposition/fallback cleanup"): exact name-based lexical fallback is no
  longer an intrinsic responsibility of `ProtosActivation`.
  `ProtosLexicalFallback` now owns the residual bare-name read and
  bare-assignment destination-resolution paths that remain necessary when
  canonical binding analysis cannot prove a direct frame-backed binding or
  runtime presence/topology requires fallback. Statically proven current
  `Resolved` reads continue to lower through `ReadFrameLocal`, and proven
  `CapturedResolved` reads/writes continue through the captured frame-backed
  operations without regressing through generic name lookup. Candidate and
  Dynamic reads, compatibility paths for non-genuine execution contexts,
  captured late-presence/retargeting fallback, non-statically-proven bare
  assignment destination selection, debugger semantic reads, and receiver
  fallback retain their exact prior semantics. Bare assignment still selects
  its destination before RHS evaluation and never follows receiver
  delegation for writes. `capturedLexicalContexts` and
  `lexicalContextsForClosureCapture` remain on `ProtosActivation` as required
  activation/capture topology rather than obsolete lookup machinery.
  Existing activation-inspection tests now target the explicit lexical
  fallback boundary. No second lexical-binding authority, lazy execution
  context materialization, or observable language-semantic change is
  introduced. This completes the planned I068 Slice 1-7 implementation
  sequence for PLAT036 Candidate D.

## 0.3.86-SNAPSHOT

- `I068` / PLAT036 Candidate D, Slice 6 ("debugger/reflection projection"):
  debugger scope and Core reflection are now covered against the frame-backed
  lexical-state architecture established by Slices 2-5. The projection remains
  semantic rather than backend-native: PRESENT frame-backed bindings and
  dynamic-overflow bindings are visible, statically allocated but not yet
  established bindings remain hidden, and Bytecode DSL implementation
  temporaries are not exposed. Debugger reads continue to route through
  `ProtosActivation` lookup precedence, including live captured frame-backed
  bindings after outer mutation, while Core `slotNames` / `slotValue` observe
  the same single lexical-binding authority. The existing read-only tooling
  baseline, receiver fallback, object-body lexical boundary, and
  `NO_DUAL_BINDING_AUTHORITY` invariant remain unchanged. No production-code
  change was required; the slice closes the missing focused evidence over real
  frame-backed execution. Slice 7 retains the
  `ProtosActivation` lexical-decomposition/fallback cleanup.

## 0.3.85-SNAPSHOT

- `I068` / PLAT036 Candidate D, Slice 5 ("captured/materialized lexical
  lowering"): statically proven captured lexical bindings now retain their
  canonical owner/depth proof through nested Closure plan construction and
  lower eligible reads through a dedicated frame-native
  `ReadCapturedFrameLocal` operation against the same materialized
  frame-backed lexical authority already projected by the escaped first-class
  execution context. Captured writes resolve and retain their exact lexical
  destination before RHS evaluation, then update that same authoritative
  frame-backed binding through `ResolveCapturedWritableLexicalTarget` /
  `AssignCapturedFrameLocal`; no captured value copy or second authoritative
  store is introduced. Capture remains by reference after the outer activation
  returns, later outer mutation remains visible, lexical depth greater than one
  is preserved, and `PRESENT(null)` remains distinct from `ABSENT`.
  D179 candidate C3 late `ABSENT -> PRESENT` creation remains observable:
  dynamically-created nearer bindings retarget subsequent captured reads and
  writes, while a write whose destination was already resolved before its RHS
  continues to mutate that previously selected destination. Presence-ambiguous
  `Candidate` references and truly `Dynamic` references preserve the existing
  generic/receiver fallback, and Object-construction bodies remain outside the
  genuine lexical execution-context chain. Context-local/source reparsing now
  remaps previously proven captured sites onto fresh canonical AST identities
  using portable source-position/name metadata plus the stable owner local
  layout, without storing Truffle `Frame`, `BytecodeNode`, or other
  Context-owned execution objects in semantic `ProtosClosureValue` state.
  `DUAL_AUTHORITATIVE_COPIES=NO`, `LAZY_CONTEXT_MATERIALIZATION=NO`, and
  observable Protos semantics are unchanged. Debugger/reflection projection
  cleanup remains allocated to Slice 6.

## 0.3.84-SNAPSHOT

- `I068` / PLAT036 Candidate D, Slice 4 ("sequential/default parameter
  lowering"): Closure parameters now participate in the genuine current
  execution-context root's stable Truffle Bytecode DSL `BytecodeLocal` layout.
  Physical local allocation still does not establish semantic presence:
  parameter locals begin cleared and become PRESENT only when the existing
  left-to-right parameter-binding operation successfully creates that binding
  through the single frame-backed lexical authority. Supplied arguments still
  suppress defaults, omitted defaults execute exactly once in the invocation
  activation, earlier parameters are available to later defaults, and current
  or later parameters remain semantically absent during an earlier/default-own
  lookup and therefore retain ordinary fallback behavior. Rest parameters
  continue to receive a fresh frozen Array containing exactly the unconsumed
  supplied suffix, and `PRESENT(null) != ABSENT` remains preserved. Parameter
  reads that are statically `Resolved` in the current activation can now use
  the same direct frame-local read path established by Slice 3, while
  `Candidate`/`Dynamic` lookups remain on the exact generic path. Escaped
  execution contexts continue to project the same authoritative parameter
  values after activation return. Captured/materialized outer lexical lowering
  remains allocated to Slice 5; no second authoritative binding store and no
  lazy execution-context materialization are introduced.

## 0.3.83-SNAPSHOT

- `I068` / PLAT036 Candidate D, Slice 3 ("definitely-current local lowering"):
  the first runtime lexical-authority cutover is now active for statically
  `Resolved` bindings owned by the genuine current execution-context scope
  (`ROOT` / `CLOSURE`). Those bindings receive stable Truffle Bytecode DSL
  `BytecodeLocal` storage and eligible bare reads lower directly through the
  generated local-accessor path instead of
  `Lookup -> ProtosActivation.lookup(String)`. The same frame-backed storage
  remains observable through the first-class `ProtosExecutionContextValue`
  authority seam, including after the context escapes its activation; no
  duplicate map-backed authoritative value copy is maintained for a migrated
  binding. Semantic presence remains independent from physical local
  allocation (`STATIC_LOCAL_EXISTS != SEMANTIC_BINDING_PRESENT`,
  `PRESENT(null) != ABSENT`), and the existing OPEN/CLOSED/FROZEN mutation
  rules plus D179 C3 monotonic membership are preserved. `Candidate` and
  `Dynamic` references retain the exact existing presence/topology and
  receiver/member fallback path, object-construction (`OBJECT_BODY`) slots
  remain ordinary object state, and legacy/internal activations whose current
  lexical context is an ordinary `ProtosObjectValue` retain the historical
  String-keyed lookup path. Closure parameters/default establishment remain
  for Slice 4 and captured/materialized outer lexical access remains for
  Slice 5; lazy execution-context materialization and performance attribution
  remain out of scope. `RUNTIME_AUTHORITY_CUTOVER=CURRENT_RESOLVED_ONLY`.

## 0.3.82-SNAPSHOT

- `I068` / PLAT036 Candidate D, Slice 2 ("frame/context single-authority
  seam"): `ProtosObjectValue`'s local-slot storage is now reached through an
  explicit, backend-private `ProtosLexicalBindingAuthority` seam installed
  once at construction, instead of every operation (`hasLocalSlot`,
  `readLocalSlot`, `localSlotsSnapshot`, `withoutLocalSlot`, `aliasLocalSlot`,
  `composeLocalSlotsFrom`, `lookupSlot`, `readSlot`, `createLocalSlot`,
  `assignLocalSlot`, `removeLocalSlot`) touching a private map field directly.
  The default `ProtosMapBackedLexicalBindingAuthority` is the same ordinary
  `LinkedHashMap` storage used before this slice, installed for both ordinary
  objects and, via a new authority-attachment constructor, for
  `ProtosExecutionContextValue`. No second authoritative binding-value copy
  exists anywhere: each object holds exactly one authority instance backed by
  exactly one map. This establishes the seam a later slice can use to install
  a Truffle Bytecode DSL frame-backed authority for statically admitted
  lexical bindings without changing any caller of these operations. D179
  candidate C3 monotonic-removal rejection, `PRESENT(null) != ABSENT`,
  close/freeze behavior, and delegated lookup through an execution context are
  all preserved exactly. `RUNTIME_AUTHORITY_CUTOVER=NO`.

## 0.3.81-SNAPSHOT

- `I068` / PLAT036 Candidate D, Slice 1 ("canonical binding identity and
  presence metadata"): the Bytecode DSL lowering backend now carries
  statically proven lexical binding identity from canonical analysis into
  lowering, preparing for later frame-backed lexical authority without
  changing any observable behavior. Five new backend-private classes under
  `com.guillermomolina.protos.execution`
  (`CanonicalLexicalScope`, `CanonicalBindingIdentity`,
  `CanonicalBindingResolution`, `CanonicalBindingAnalyzer`,
  `CanonicalBindingAnalysis`) discover, for a canonical subtree, the owning
  lexical scope (module root, Closure activation, or Object-construction
  body) of every bare local creation and closure parameter, and classify
  every bare lexical read/write reference as `Resolved` (guaranteed PRESENT
  in the current scope at this exact program point), `Candidate` (a
  statically known owning scope and lexical depth, without a presence
  guarantee, because a nearer scope may still legally create the same name
  later per D179 candidate C3 monotonic membership), or `Dynamic` (no scope
  in the static chain declares the name anywhere, preserving today's exact
  receiver/member-fallback path unchanged). `CanonicalToBytecodeLowerer`
  computes and caches this analysis for each lowering unit as a side effect
  of `lowerRoot`; it is not yet read by any codegen path, so
  `ProtosActivation`'s existing exact String-keyed lookup/write operations
  remain the sole runtime lexical authority. `RUNTIME_AUTHORITY_CUTOVER=NO`.

## 0.3.80-SNAPSHOT

- `I067` / D179 candidate C3 ("monotonic context membership"): a genuine
  execution context (the object bound to a Closure invocation's fresh
  activation, or to a module's `moduleContext`) now rejects `removeSlot(name)`
  whenever `name` currently identifies one of its local slots, regardless of
  whether the execution context is open, closed, or frozen. The new
  `ProtosExecutionContextValue` runtime family (a `ProtosObjectValue`
  subclass) overrides `removeLocalSlot` to reject removal of a present local
  slot and otherwise defer to the ordinary contract; `ProtosPrelude
  .newExecutionContext()` is the sole factory for this family and now returns
  it. The rejection reuses the existing generic structural-mutation `Error`
  already raised by `ProtosStandardObjectProtocol.removeSlot` for every other
  removal failure; no new Error family or public selector was introduced.
  `Object.removeSlot(name)` on ordinary objects, including the object under
  construction that temporarily serves as an object-literal body's
  slot-creation context, is unaffected. Late absent-to-present local
  creation while open, present-to-present value mutation while writable,
  closure capture-by-reference, context escape, `close()`, `freeze()`, and
  present-`null`-distinct-from-absent semantics are all unaffected.

## 0.3.79-SNAPSHOT

- `PERF010-A`: replace the `fastOrdinarySend` cache guard's `closure`/
  `methodHome` object-identity check with a `ProtosClosureValue.definition()`
  identity check in `ProtosBytecodeRootNode.PrepareSendArguments`. The
  selected Closure and its `methodHome` are legitimately fresh runtime
  objects on every invocation that re-executes the Source producing them
  (a re-materialized Closure literal, a freshly constructed receiver), even
  when D013 keeps selecting the same executable behavior; guarding on their
  object identity consumed a fresh cache entry per execution and, after the
  cache limit, permanently generalized the call site to the generic
  `perform` fallback (the PERF010-A stable-specialization-identity-churn
  root cause). The effective Context-owned Bytecode activation target
  depends only on the selected Closure's immutable `CanonicalClosure`
  definition and the entered `ProtosLanguageContext`, never on the selected
  Closure or `methodHome` instance, so keying the cache on that definition
  instead keeps the fast hit stable across fresh materializations of the
  same executable behavior while remaining exactly as discriminating
  whenever D013 genuinely selects a different Closure. The currently
  selected Closure and `methodHome` are still bound fresh on every hit and
  flow into a fresh `ProtosActivation` exactly as before; only the cache
  guard identity changes. D013 re-lookup per invocation, mutation
  visibility/shadowing/delegation, and every other preserved PERF010-A
  invariant are unaffected.

## 0.3.78-SNAPSHOT

- `PERF010-A`: add a prepared Context-owned target specialization for
  `ProtosBytecodeRootNode.PrepareSendArguments`, the exact hot caller
  identified by the retained PERF010-A causal evidence as compiler-stranded
  behind generic ordinary-send preparation. A new `fastOrdinarySend`
  specialization re-runs authoritative D013 lookup on every hit and, only
  when the selector, selected Closure identity, `methodHome` identity, and
  entered `ProtosLanguageContext` identity all match a cached hit, reuses an
  effective Context-owned Bytecode activation target materialized once at
  cache-population time; it builds a fresh activation/invocation on every
  call as before. This keeps the fast hit from calling
  `ProtosStandardImportProtocol.selectedRuntimeForBytecodeIntrinsic`/
  `prepareBytecodeImport` and `ProtosLanguageContext.
  bytecodeExecutionPlanForDefinition`'s `sharedBytecodeExecutionPlans.
  computeIfAbsent(...)`, both of which the retained evidence traces directly
  into the caller helper's permanent compiler bailout, for a proven
  non-native, source-backed ordinary Closure that can never reach either
  branch. Any native Closure, standard-import selection, Closure/home/
  selector/Context mismatch, or unsupported representation falls through to
  the exact existing generic `perform` path, which is unchanged in
  behavior (only its lookup logic is now shared via the extracted
  `performOrdinarySendLookup` helper). This is a bounded implementation
  experiment: it does not itself establish PERF010-A's dominant cause or
  attributable fraction, and no production optimization is selected by this
  change alone.

## 0.3.77-SNAPSHOT

- `TOOL009-B`: final legacy Test Tool migration cleanup. Repository production
  execution is now logical-only end to end:
  - `RepositorySuite` execution no longer partitions a corpus's TestPlan into
    a legacy Runner plan and suite-native logical Cases: `Main.protos`
    instead validates, per Case, that every registered production Case is
    `suite-native` and fails closed otherwise. `Runner.runD108WithResources`
    is no longer called from repository production, `completedRuns`/
    `primaryRun` reconciliation across D108 runs is gone, and the final
    `TestRunOutcome` is derived solely from the current Logical Case result
    projection (`LogicalCaseResult.exitCode`) rather than from a legacy/
    logical outcome merge. `Runner.runD108WithResources` and the rest of the
    D108/D114/D116 infrastructure-outcome machinery remain fully intact and
    independently authoritative for their own callers.
  - `LogicalCaseMigration.protos` (the mixed-ownership partition/merge
    bridge: `splitPlan`, `legacyPlan`, `suiteNativeSpecs`,
    `isSuiteNativeSpec`, `neutralLegacyOutcome`, `mergeOutcome`) is removed.
    Its two still-authoritative helpers, `sourceAssociation` and
    `logicalCaseExecutorAsync`, are re-homed to a new small current-owner
    module, `protos/tools/test/LogicalCaseDispatch.protos`, unchanged in
    behavior.
  - The legacy repository lifecycle/display adapter is removed:
    `Main.protos` no longer builds a `legacyCaseDisplayReference` or a
    `legacyLifecycleObserver`; the invocation-wide D174 lifecycle tracker now
    has exactly one observer, the logical one.
  - The whole-source project-tree `CaseAuthority` execution path is removed:
    `ProtosTestCaseAuthorityExecutionScope`,
    `ProtosTestCaseAuthorityExecutionFacility`,
    `ProtosTestCaseAuthorityAttemptBridge` and
    `ProtosTestCaseAuthorityAttemptCompletion` are deleted, along with the
    five `caseAuthorityExecutionAsync` project-tree corpus bootstrap slots
    and `ProtosTestCorpusRegistry`'s dedicated project-tree binding helper
    (a project-tree corpus binding is now the same shape as a case-outcomes
    binding: `filesystem`/`planLoader`/`caseNamespace`). The two still-live
    D133/D134 primitives this path owned, project-tree CaseAuthority
    descriptor validation and trusted-root confinement resolution, are
    re-homed as `ProtosTestLogicalCaseAttemptBridge.fixtureIdentity` and
    `ProtosTestLogicalCaseAttemptBridge.resolveAuthorityRoot`, the current
    authoritative owner of D133/D134 physical project-tree authority for
    suite-native Logical Case execution.
    `ProtosTestToolAsyncExecutionScope.installWithCaseAuthorities` is renamed
    to `installWithProjectTreeAuthorities`, reflecting that it no longer
    installs any `CaseAuthority` execution scope.
  - Repository-suite-native execution's current route is otherwise
    unchanged: `CorpusBinding`/`planLoader` → suite-native `TestPlan`
    validation → fail-closed native-plan validation → source associations →
    Logical Case discovery → `CasePlan` → `ExecutionRequirementId` →
    `logicalCaseExecutionAsync` → `LogicalCaseRunner` → `LogicalCaseResult` →
    logical-only final `TestRunOutcome` → `Progress.finishInvocation`. The
    Package project-tree suite-native route
    (`protos/test/package` → Package `logicalCaseExecutionAsync` →
    `sourceAssociation` authority descriptor → trusted `casesRoot` →
    physical project-tree authority → `projectTreeFilesystem` → selected
    `Test.call()`) is unaffected and has no dependency on the removed
    whole-source `CaseAuthority` execution facility.
  - Retained unchanged and independently authoritative: D108/D114/D116
    outcome/fail-stop machinery and the legacy expectation engine; Manifest
    `true`/`error` compatibility for `packageTomlCaseSpec`/
    `caseOutcomeSpec`/`projectTreeCaseSpec`; the D125/D077/D108
    `executionAsync`/`executionInspectAsync`/`resourceExecutionAsync`/
    `resourceExecutionInspectAsync` APIs; Process Snapshot; and the ordinary/
    Actor/Group/Package/Package-project-tree Logical Case execution
    facilities.

## 0.3.76-SNAPSHOT

- `TOOL009`: migrate the complete 54-case Package Tool project-tree corpus
  (`protos/tests/package-tool/content-identity` (12),
  `protos/tests/package-tool/resolution-input-lock` (2),
  `protos/tests/package-tool/resolution-root` (8),
  `protos/tests/package-tool/execution-plan` (28),
  `protos/tests/package-tool/project-projection` (4)) from the legacy
  project-tree `CaseAuthority` execution path to suite-native Logical Case
  execution (Publication 2 of the Package non-TOML migration), completing the
  Package non-TOML migration alongside Publication 1's 157 case-outcomes
  cases.
  - Adds the previously missing project-tree-aware Package Logical Case
    authority adapter: `ProtosTestLogicalCaseAttemptBridge` optionally
    provisions a fresh read-only physical project-tree authority (reusing
    `ProtosTestCaseAuthorityAttemptBridge`'s trusted confinement mechanics)
    and installs it under the `projectTreeFilesystem` slot the declaration's
    module scope closes over, before Discovery/selection run and the
    selected Test's `call()` executes — all inside the same fresh Process.
    `ProtosTestLogicalCaseExecutionFacility` gains a `Map<corpusId, casesRoot>`
    parameter (empty for the ordinary/Actor/Group installations, populated
    for the Package-flavored installation) and decodes an optional third
    `sourceAssociation` element carrying the D133 project-tree CaseAuthority
    descriptor. This stays the ordinary four-argument suite-native Logical
    Case protocol; the descriptor travels inside the single
    `sourceAssociation` argument. `ProtosTestLogicalCaseDiscoveryFacility`,
    the separate authority-free discovery boundary that runs earlier in the
    same pipeline, is extended the same way: it now accepts an optional
    third `sourceAssociation` element and preserves it opaquely (without
    interpreting it) into every discovered CasePlan entry's own
    `sourceAssociation`, so the descriptor a project-tree spec supplies at
    discovery time is still present when that Case later reaches execution.
  - `LogicalCaseMigration.sourceAssociation` now appends the CaseAuthority
    descriptor as a third element when a suite-native spec carries one, so
    distinct project identities that reuse the same fixture source path
    remain distinct logical records; `splitPlan` now accepts a well-formed
    project-tree/case/case descriptor alongside `suite-native` instead of
    rejecting it; `logicalCaseExecutorAsync` forwards a 2- or 3-element
    association unchanged.
  - `Manifest.protos`'s `projectTreeCaseSpec` now also accepts the
    `suite-native` outcome marker (normalized to `expectation =
    "suite-native"`, `expected = "-"`), alongside the already-supported
    `true`/`error` markers, while still attaching the D133 CaseAuthority
    descriptor.
  - `Main.protos`'s suite-native discovery bookkeeping is keyed by
    `(sourcePath, fixtureIdentity)` rather than `sourcePath` alone, so
    project-tree fixture scripts reused across distinct project identities
    (for example `execution-plan`'s shared `f2e3b-build-v2-error.protos`)
    are discovered and re-read independently per project identity instead of
    colliding.
  - The 40 distinct project-tree fixture scripts are rewritten from the
    legacy whole-source boolean/error convention into the `std:test/Test`
    suite-native convention (`tests: [Test("<name>", () => { ... })]`),
    preserving each fixture's original assertions verbatim: a `true`
    expectation's trailing completion expression is wrapped in
    `Assertions.require(...)`; an `error` expectation's whole body is
    wrapped in `Assertions.signals(Error, () => { ... })`. The five
    `manifest.tsv` files are updated to the `suite-native` outcome marker.
  - `ProtosTestCaseAuthorityAttemptBridge.resolveAuthorityRoot` and
    `ProtosTestCaseAuthorityExecutionFacility.fixtureIdentity` are widened
    from `private` to package-private so the new adapter reuses the exact
    same confinement/descriptor-validation logic rather than duplicating it.
    TOOL009-B legacy cleanup (removing the now-unused legacy CaseAuthority
    scope/facilities once no CaseSpec references them) remains out of scope
    for this publication.

## 0.3.75-SNAPSHOT

- `TOOL009`: migrate the complete 157-case-outcomes Package non-TOML corpus
  (`protos/tests/package-tool/version` (74), `protos/tests/package-tool/lock`
  (71), `protos/tests/package-tool/resolution-input` (12)) from the legacy
  Test Tool execution path to suite-native Logical Case execution
  (Publication 1 of the Package non-TOML migration).
  - `protos/tools/test/Manifest.protos`'s generic two-column
    `caseOutcomeSpec`/`loadCaseOutcomes` loader now also accepts the
    established `suite-native` outcome marker (normalized to
    `expectation = "suite-native"`, `expected = "-"`), alongside the
    already-supported `true`/`error` markers. The loader remains generic
    and Package-Tool-name-free; the change is the same normalization
    `packageTomlCaseSpec` already performs for the Package TOML corpus.
  - The Package-flavored suite-native logical Case fallback resolver
    (`packageLogicalCaseFallbackResolver` in `ProtosCli`) is extended with
    seven finite exact overlays for the real
    `self:ReleaseVersion`/`self:DependencyConstraint`/
    `self:FreshVersionSelection`/`self:RetainedVersionSelection`/
    `self:LockSyntax`/`self:LockDocument`/`self:ResolutionInput` modules
    under `protos/tools/package/`, following the same finite exact-overlay
    strategy already published for the Package TOML module graph. Their
    shared `std:collections/Array` and `std:crypto/SHA256` dependencies
    already resolve through the standard library resolver and need no
    overlay. Generic `ProtosBundledToolModuleResolver` closure guards and
    ambient Package-directory search remain unchanged.
  - `ProtosPackageTestLogicalCaseExecutionFacilityTest` gains matching
    resolver overlay coverage plus three new focal tests proving real
    execution through the new graph: `RetainedVersionSelection ->
    FreshVersionSelection -> DependencyConstraint -> ReleaseVersion`,
    `LockDocument -> LockSyntax -> ReleaseVersion`, and `ResolutionInput ->
    LockSyntax / ReleaseVersion` plus the required `std:collections/Array`
    and `std:crypto/SHA256` standard library dependencies.
  - All 157 `.protos` fixtures (74 `version`, 71 `lock`, 12
    `resolution-input`) now declare `std:test/Test` cases using
    `std:test/Assertions.require(...)` (former `true` expectation) or
    `std:test/Assertions.signals(Error, ...)` (former `error`
    expectation), preserving the exact behavior each fixture tests inside
    the selected Test body. Each fixture's `self:` imports are declared
    inside the selected Test's closure body rather than at module top
    level, for the same discovery/execution resolver-boundary reason
    already recorded for the `0.3.74-SNAPSHOT` Package TOML migration.
  - The three corpora's `manifest.tsv` files now record `suite-native` for
    all 157 entries (`CASE_OUTCOMES_SUITE_NATIVE=157`,
    `CASE_OUTCOMES_LEGACY=0`), with row count, row order, source path, and
    case namespace preserved.
  - The 54 `project-tree` Package Tool cases
    (`content-identity`/`resolution-input-lock`/`resolution-root`/
    `execution-plan`/`project-projection`) intentionally remain on the
    legacy Runner/CaseAuthority path (`PROJECT_TREE_LEGACY=54`); they
    require a project-tree-aware Package Logical Case authority adapter
    (Publication 2) and are out of scope for this change.
    `LogicalCaseMigration`/`splitPlan`/`legacyPlan`/`suiteNativeSpecs`/
    `Runner.runD108WithResources` and the other legacy mixed-ownership
    infrastructure remain production-live and unremoved; TOOL009-B legacy
    cleanup remains blocked until Publication 2 also completes.

## 0.3.74-SNAPSHOT

- `TOOL009`: migrate the complete 102-case Package Tool TOML corpus under
  `protos/tests/package-tool/toml-syntax` from the legacy Test Tool
  execution path to suite-native Logical Case execution, using the
  Package-flavored resolver coverage published in `0.3.73-SNAPSHOT`.
  - All 102 `.protos` fixtures (27 `TomlSyntax`, 31 `TomlDocument`, 44
    `ManifestSchemaV1`) now declare `std:test/Test` cases using
    `std:test/Assertions.require(...)` (former `true` expectation) or
    `std:test/Assertions.signals(Error, ...)` (former `error`
    expectation), preserving the exact behavior each fixture tests
    inside the selected Test body.
  - Each fixture's `self:TomlSyntax` / `self:TomlDocument` /
    `self:ManifestSchemaV1` import is declared inside the selected
    Test's closure body rather than at module top level. The Test Tool
    CLI's suite-native discovery boundary
    (`ProtosTestLogicalCaseDiscoveryFacility`) is flavor-agnostic and
    installed once with the ordinary, non-overlaid Test Tool fallback
    resolver; it fully evaluates a suite's module source to read its
    `tests` declaration, before any flavor-specific (Package/Actor/
    Group) execution resolver is ever consulted. A module-top-level
    `self:` import is therefore unresolvable at discovery time for this
    corpus, even though the already-published Package-flavored
    execution resolver overlay resolves it correctly once a selected
    Test is actually invoked. Group/Actor's suite-native corpora never
    surfaced this discovery/execution resolver-boundary seam because
    their flavor-specific dependency (`workers`) is referenced only
    from inside a Test body at runtime, never as a module-top-level
    import. Moving the import inside the Test closure (syntactically
    ordinary, since `import(specifier)` is an ordinary call expression
    per `spec/PROTOS_GRAMMAR.md`, not top-level-only syntax) defers its
    resolution to execution time, matching the pattern Group/Actor
    already rely on; this is a fixture-authoring adjustment, not a
    resolver or discovery-facility change, and
    `ProtosTestLogicalCaseDiscoveryFacility`'s generic wiring is
    unchanged.
  - `manifest.tsv` now records `suite-native` for all 102 entries
    (`LEGACY_TRUE_METADATA_REMAINING=0`,
    `LEGACY_ERROR_METADATA_REMAINING=0`).
  - `protos/tools/test/Manifest.protos`'s `packageTomlCaseSpec` (the
    two-column Package TOML manifest parser) now recognizes
    `suite-native` as a third valid `expectation`/`expected` pair
    (`"suite-native"`/`"-"`), alongside its existing `true`/`error`
    handling, so `Manifest.caseExpectation(...) == "suite-native"`
    routes these cases through the already-published
    `LogicalCaseMigration` suite-native dispatch. The `true`/`error`
    handling is unchanged.
  - Two pre-existing legacy exact-execution infrastructure fixtures
    (`protos/tests/tooling/tool002-e2a1-package-resolved-execution.protos`
    and
    `protos/tests/tooling/tool002-e2a2b-package-failed-fixture.protos`)
    directly executed raw source read from
    `key-bare-dotted.protos`/`invalid-key-error.protos` in the corpus,
    which no longer evaluate to a bare boolean/signal now that those
    files are suite-native Test declarations. Both fixtures now execute
    a dedicated inline source string reproducing the exact former
    corpus behavior, decoupling this legacy-plumbing infrastructure
    check from the now-migrated corpus content; the corresponding Java
    tests (`ProtosTestToolPackageExecutionEnvironmentTest`,
    `ProtosTestToolPackageFailedExecutionTest`) are unchanged.
  - `protos/tests/tooling/tool002-e1b-package-toml-filesystem.protos`
    (which asserts on the real corpus's first manifest row) now expects
    `caseExpectation == "suite-native"` / `caseExpected == "-"` instead
    of `"boolean"`/`"true"`, matching the migrated
    `key-bare-dotted.protos` entry.
  - This migration does not modify Java production code, does not
    remove legacy Test Tool infrastructure
    (`LogicalCaseMigration`/`splitPlan`/`legacyPlan`/`mergeOutcome`, the
    D108 Runner route, or the `packageExecutionAsync` family), and does
    not change `D108`/`D152`/`D153`/`D178`/Case Authority semantics.
    Global legacy-infrastructure liveness reconciliation remains
    separate follow-up work (`TOOL009-B` / #685).

## 0.3.73-SNAPSHOT

- `TOOL009`: complete the Package-flavored suite-native logical Case
  resolver coverage required by the real Package Tool TOML module graph.
  This is a bounded prerequisite for migrating the Package Tool TOML
  corpus; the 102 TOML manifest fixtures under
  `protos/tests/package-tool/toml-syntax` are not migrated by this change.
  - The previously published `packageLogicalCaseFallbackResolver`
    (`0.3.72-SNAPSHOT`) overlaid only `package-runtime-names`, a
    dedicated proof-of-bootstrap module. It did not resolve the actual
    modules the TOML corpus imports: `self:TomlSyntax`,
    `self:TomlDocument`, and `self:ManifestSchemaV1`. A corpus-loaded
    suite is materialized through `ProtosDirectFileModuleResolver` and
    therefore carries a `direct-file:` `ProtosModuleKey`, which never
    satisfies `ProtosBundledToolModuleResolver`'s `self:`/`tool-shared:`
    importer-closure guards, so every one of those imports failed.
  - `ProtosCli`'s `packageLogicalCaseFallbackResolver` now also overlays
    the finite Package TOML module graph as exact specifiers, each with
    an explicit canonical `ProtosModuleKey` and exact source path:
    `self:TomlSyntax`, `self:TomlDocument`, `self:ManifestSchemaV1`
    (the three Package-owned modules, keyed under a `tool001-package:`
    namespace consistent with `package-runtime-names`), and
    `tool-shared:Toml10/TomlSyntax` /
    `tool-shared:Toml10/TomlDocument` (the shared Toml10 modules those
    modules depend on, keyed with the same `bundled-tool-shared:`
    canonical identity `ProtosBundledToolModuleResolver` would itself
    assign for the same specifier, so the shared modules keep one single
    canonical identity regardless of which resolution path reaches
    them). `ProtosExactModuleOverlayResolver` matches on the literal
    import specifier before any importer-closure check runs, so this
    closes the gap without relaxing
    `ProtosBundledToolModuleResolver`'s generic `self:`/`tool-shared:`
    closure guards for any other consumer. `packagePrelude` and the
    legacy `packageExecutionAsync` facility are unchanged.
  - Strengthened `ProtosPackageTestLogicalCaseExecutionFacilityTest` with
    focal coverage that resolves and executes the real
    `protos/tools/package/TomlSyntax.protos`,
    `protos/tools/package/TomlDocument.protos`, and
    `protos/tools/package/ManifestSchemaV1.protos` modules (not copies
    embedded in the test) from a direct-file suite, proving: `self:
    TomlSyntax` resolves its `tool-shared:Toml10/TomlSyntax` dependency;
    `self:TomlDocument` resolves its `tool-shared:Toml10/TomlDocument`
    dependency, which itself resolves `tool-shared:Toml10/TomlSyntax`;
    and `self:ManifestSchemaV1`'s `self:TomlDocument` import, which is
    lazy (declared inside the `parseBase` callable body rather than at
    module top level), actually resolves when the selected Test body
    calls `Schema.parseBase(...)`. Each case asserts the real parsed
    TOML structure, not merely that a module loaded.
  Implementation version becomes `0.3.73-SNAPSHOT`.

## 0.3.72-SNAPSHOT

- `TOOL009` (GitHub #692 infrastructure): add the Package-flavored
  suite-native logical Case execution route so `protos/test/package` can
  reuse the same `ProtosTestLogicalCaseExecutionFacility` /
  `ProtosTestLogicalCaseAttemptBridge` machinery the ordinary/Actor/Group
  routes already use, without touching Package Tool's own bootstrap. This
  is plumbing only: the Package Tool corpus (102 TOML manifest fixtures
  under `protos/tests/package-tool/toml-syntax`) is not migrated by this
  change and remains entirely legacy-shaped.
  - `ProtosCli` now builds a `packageLogicalCaseFallbackResolver`: the
    same ordinary bundled Test Tool fallback resolver the ordinary/
    Actor/Group routes already use as their base (the suite-native
    bridge's own selection machinery resolves `self:Discovery` against
    that root regardless of flavor), overlaid with one host-selected
    exact reference to Package Tool's own `RuntimeNames.protos` module
    (specifier `package-runtime-names`) so a selected Test body can prove
    it resolved the Package-flavored bootstrap rather than the plain
    unoverlaid ordinary resolver. `packagePrelude` and the legacy
    `packageExecutionAsync` facility, which resolve against
    `packageToolRoot` directly, are unchanged.
  - `ProtosTestToolAsyncExecutionScope` installs a fourth
    `ProtosTestLogicalCaseExecutionFacility` instance under the new
    `packageLogicalCaseExecutionAsync` bootstrap slot
    (`PACKAGE_LOGICAL_CASE_EXECUTION_BOOTSTRAP_SLOT`), distinct from the
    ordinary, Actor- and Group-flavored slots so all four routes coexist;
    each selected Test Case still receives a fresh Process/Prelude per
    attempt.
  - `ProtosTestExecutionRequirementRegistry` now publishes
    `logicalCaseExecutionAsync` on the `protos/test/package` D125 binding,
    reusing the newly installed facility rather than provisioning a
    parallel one. The legacy `packageExecutionAsync` /
    `packageExecutionInspectAsync` / `packageResourceExecutionAsync` /
    `packageResourceExecutionInspectAsync` bindings are unchanged and
    remain fully functional (TOOL009-B legacy removal is out of scope).
  - Added `ProtosPackageTestLogicalCaseExecutionFacilityTest`, dedicated
    inline-fixture focal coverage demonstrating the Package Tool
    `RuntimeNames` overlay resolves inside a selected Test body, selected
    `Test.call()` authority (module declaration completing does not make
    a failing selected Test pass), exactly-once execution, correct
    multi-Test selector resolution, a fresh Process per Case with no
    state leaking between Cases, and rejection (without executing the
    body) on selector and signature mismatch.
  - Updated `ProtosTestToolExecutionRequirementRegistryTest` and
    `ProtosTestToolH2B3PublicIntegrationTest` for the new
    `packageLogicalCaseFallbackResolver` parameter and the
    `protos/test/package` binding now carrying
    `logicalCaseExecutionAsync`.
  Implementation version becomes `0.3.72-SNAPSHOT`.

## 0.3.71-SNAPSHOT

- `TOOL009-B` (GitHub #685): remove the unused
  `LogicalCaseMigration.selectSuiteNativeSpecs` migration helper and its
  migration-only characterization coverage in
  `protos/tests/tooling/tool009-logical-case-migration.protos`. The global
  reconciliation for TOOL009-B established that this helper had zero
  production consumers while every other `LogicalCaseMigration` slot
  (`splitPlan`, `legacyPlan`, `suiteNativeSpecs`, `sourceAssociation`,
  `logicalCaseExecutorAsync`, `mergeOutcome`, `neutralLegacyOutcome`) remains
  required by the hybrid `Main.protos` execution path while Package Tool
  legacy corpora still generate legacy `CaseSpec`s. Drop the now-unused
  `FileSelection` import this helper alone required, and correct the stale
  module-header claim that "M3/M4 must remove this module": the module
  remains production infrastructure and is not scheduled for automatic
  removal. This does not remove or simplify the legacy execution path, the
  D108 Runner, the expectation engine, or any host execution facility.
  Implementation version becomes `0.3.71-SNAPSHOT`.

## 0.3.70-SNAPSHOT

- TOOL009-C Slice 4 (#686): add a Group-flavored suite-native logical
  Case execution route and migrate all 10
  `protos/tests/conformance/group` fixtures to it, reusing the same
  `ProtosTestLogicalCaseExecutionFacility` /
  `ProtosTestLogicalCaseAttemptBridge` machinery the Actor route (Slice
  3) already established rather than a parallel implementation.
  - `ProtosCli` now builds a `groupLogicalCaseFallbackResolver`
    (ordinary bundled Test Tool fallback resolver overlaid with the
    Group-specific `tool002-group:workers` module, exactly as
    `groupPrelude` already resolves it) and passes it to
    `ProtosTestToolAsyncExecutionScope.installWithCaseAuthorities`.
  - `ProtosTestToolAsyncExecutionScope` installs a second
    `ProtosTestLogicalCaseExecutionFacility` instance under the new
    `groupLogicalCaseExecutionAsync` bootstrap slot
    (`GROUP_LOGICAL_CASE_EXECUTION_BOOTSTRAP_SLOT`), distinct from the
    ordinary and Actor-flavored slots so all three routes coexist; each
    selected Test Case still receives a fresh Process/Prelude per
    attempt.
  - `ProtosTestExecutionRequirementRegistry` now publishes
    `logicalCaseExecutionAsync` on the `protos/test/group` D125
    binding, reusing the newly installed facility rather than
    provisioning a parallel one. The legacy `groupExecutionAsync` /
    `groupExecutionInspectAsync` / `groupResourceExecutionAsync` /
    `groupResourceExecutionInspectAsync` bindings are unchanged and
    remain fully functional (TOOL009-B legacy removal is out of scope).
  - Added `ProtosGroupTestLogicalCaseExecutionFacilityTest`, dedicated
    inline-fixture focal coverage demonstrating the Group "workers"
    module overlay resolves inside a selected Test body, selected
    `Test.call()` authority, exactly-once execution, a fresh
    Process/Group per Case with no state leaking between Cases,
    intra-Case Group state across multiple requests within one Case,
    and rejection (without executing the body) on selector and
    signature mismatch.
  - Migrated all 10 `protos/tests/conformance/group` fixtures from the
    legacy manifest-driven expectation model (`boolean`/
    `future-integer`/`future-integer-one-of`/`future-boolean`/`error`
    kinds) to suite-native `Test(...)` declarations, so
    `group/manifest.tsv` now records all 10 entries as `suite-native`.
    Original fixture bodies, license headers, and observable semantics
    (member readiness, eligible-member selection, argument
    snapshotting, GroupRef identity/transfer, and routing after member
    termination) are unchanged: the 2 former `boolean`-kind cases wrap
    their body in a `tool009LegacyCase` closure asserted with
    `Assertions.require(tool009LegacyCase() === <expected>)`; the 3
    former `future-integer`-kind cases follow the same pattern with
    `Assertions.require(tool009LegacyCase().value() == N)`; the 1
    former `future-boolean`-kind case follows the same pattern with
    `Assertions.require(tool009LegacyCase().value() === true)`; the 1
    former `future-integer-one-of`-kind case
    (`request-selects-one-eligible-member.protos`) preserves its exact
    `1,2` acceptable-result set with
    `Assertions.require((value == 1) || (value == 2))`; the 3 former
    `error`-kind cases wrap their body in
    `Assertions.signals(Error, ...)`. Each fixture remains an
    independent physical `.protos` source with exactly one `Test`; no
    fixtures were merged or regrouped. Process, Actor, D152, D153,
    D178, and TOOL009-D are unmodified; legacy Group execution
    infrastructure (`groupExecutionAsync` / `groupExecutionInspectAsync`
    / `groupResourceExecutionAsync` / `groupResourceExecutionInspectAsync`)
    is not removed (TOOL009-B, out of scope).
    - `ProtosExactExecutionFacilityTest` exercised the legacy
      whole-source inspection route directly against
      `request-selects-one-eligible-member.protos`'s raw file content;
      since that file's module-level completion is no longer a bare
      Future after migration, the test now holds the original fixture
      body as an inline `GROUP_REQUEST_CASE_SOURCE` Java string
      constant instead of reading it from disk, decoupling it from the
      corpus file's new suite-native purpose while preserving its
      original assertions unchanged. No production/`src/main` or
      `protos/lib` source was modified by this decoupling.

## 0.3.69-SNAPSHOT

- TOOL009-C Slice 3 (#686): migrate all 11
  `protos/tests/conformance/actor` fixtures from the legacy
  manifest-driven expectation model (`boolean`/`future-integer`/
  `future-boolean`/`error` kinds) to suite-native `Test(...)`
  declarations, so `actor/manifest.tsv` now records all 11 entries as
  `suite-native` and each fixture routes through
  `protos/test/actor`'s `actorLogicalCaseExecutionAsync` (the Slice 3
  infrastructure facility, itself unmodified) instead of the legacy
  whole-source completion route. Original fixture bodies, license
  headers, and observable semantics are unchanged: the 1 former
  `boolean:true`-kind case and the 4 former `future-boolean:true`-kind
  cases wrap their body in a `tool009LegacyCase` closure asserted with
  `Assertions.require(tool009LegacyCase() === true)` (the `future-*`
  cases additionally observe the returned Future with `.value()` before
  the assertion, per the Future-observation authority the migration is
  required to preserve); the 4 former `future-integer`-kind cases follow
  the same pattern with `Assertions.require(tool009LegacyCase().value()
  == N)`; the 2 former `error`-kind cases wrap their body in
  `Assertions.signals(Error, ...)`. Each fixture remains an independent
  physical `.protos` source with exactly one `Test`; no fixtures were
  merged or regrouped. Process, Group, D152, D153, D178, and TOOL009-D
  are unmodified; legacy Actor execution infrastructure
  (`actorExecutionAsync` / `actorExecutionInspectAsync` /
  `actorResourceExecutionAsync` / `actorResourceExecutionInspectAsync`)
  is not removed (TOOL009-B, out of scope).
  - `ProtosExactExecutionFacilityTest` exercised the legacy whole-source
    inspection route directly against `spawn-request-echo.protos`'s and
    `ip-data-transfer.protos`'s raw file content; since those files'
    module-level completion is no longer a bare Future after migration,
    the test now holds the original fixture bodies as inline
    `ACTOR_REQUEST_CASE_SOURCE` / `ACTOR_CHAIN_CASE_SOURCE` Java string
    constants instead of reading them from disk, decoupling it from the
    corpus files' new suite-native purpose while preserving its original
    assertions unchanged. No production/`src/main` or `protos/lib`
    source was modified.

## 0.3.68-SNAPSHOT

- TOOL009-C Slice 3 infrastructure (#686): add the suite-native Actor
  Logical Case execution facility that Slice 3's actual migration of the
  11 `protos/tests/conformance/actor` fixtures will depend on.
  `protos/test/actor` now additionally exposes `logicalCaseExecutionAsync`
  in `ProtosTestExecutionRequirementRegistry`, backed by a second
  installation of the existing `ProtosTestLogicalCaseExecutionFacility` /
  `ProtosTestLogicalCaseAttemptBridge` machinery under its own
  `actorLogicalCaseExecutionAsync` bootstrap slot
  (`ProtosTestToolAsyncExecutionScope`). No new Case authority or bridge
  class was introduced: the Actor route reuses the ordinary D152/D153
  logical Case protocol unchanged, parameterized with an Actor-flavored
  fallback resolver that overlays the same `workers` module the legacy
  `actorExecutionAsync` facility resolves through `actorPrelude`, so
  `Actor.spawn("workers", ...)` inside a selected Test body resolves the
  Actor-flavored blueprint set instead of falling back to the ordinary
  Test Tool resolver. Each Case still gets a fresh Process/Prelude/Actor
  graph per attempt (unchanged bridge behavior), giving Case isolation and
  intra-Case Actor state persistence for free. The 11 corpus fixtures and
  `manifest.tsv` are untouched; legacy `actorExecutionAsync` /
  `actorExecutionInspectAsync` / `actorResourceExecutionAsync` /
  `actorResourceExecutionInspectAsync` routes are unmodified. D152, D153,
  D178, CaseAuthority, TOOL009-D, and Group are unmodified.
  - Added `ProtosActorTestLogicalCaseExecutionFacilityTest` (focal
    infrastructure tests: workers overlay resolution + selected-Test
    execution, intra-Case Actor state persistence across requests,
    Case-to-Case Actor isolation, selector mismatch rejection, signature
    mismatch rejection — all against dedicated inline fixtures, not the
    corpus).
  - Extended `ProtosTestToolExecutionRequirementRegistryTest` to assert
    the new `protos/test/actor` binding shape and updated its existing
    assertions that previously encoded "Actor has no suite-native route
    yet".
  - `ProtosTestToolAsyncExecutionScope.installWithCaseAuthorities` and
    `ProtosCli` gained one new `ProtosModuleResolver` parameter/argument
    for the Actor-flavored fallback resolver; other call sites
    (`ProtosTestToolH2B3PublicIntegrationTest`) were updated accordingly.

## 0.3.67-SNAPSHOT

- TOOL009-C Slice 2 (#686): migrate all 15
  `protos/tests/conformance/process` fixtures from the legacy
  manifest-driven expectation model (`error`/`boolean` kinds) to
  suite-native `Test(...)` declarations, so `manifest.tsv` now records all
  15 entries as `suite-native` and each fixture routes through
  `protos/test/process-snapshot`'s `logicalCaseExecutionAsync` (the D178
  selected-`Test.call()` authority) instead of the legacy whole-source
  completion route. Original fixture bodies, license headers, and
  observable semantics are unchanged: the 8 former `error`-kind cases wrap
  their body in `Assertions.signals(Error, ...)`, and the 7 former
  `boolean:true`-kind cases wrap their body in a `tool009LegacyCase`
  closure asserted with `Assertions.require(tool009LegacyCase() === true)`.
  Actor, Group, D152, D153, D178, and TOOL009-D are unmodified; legacy
  execution infrastructure is not removed (TOOL009-B, out of scope).
  - `ProtosProcessSnapshotExecutionTest` and
    `ProtosAsyncProcessSnapshotExecutionFacilityTest` exercised the legacy
    whole-source execution route directly against
    `snapshot-identity.protos`'s raw file content; since that file's
    module-level completion is no longer a boolean after migration, both
    tests now hold the original fixture body as an inline
    `SNAPSHOT_IDENTITY_SOURCE` Java string constant instead of reading it
    from disk, decoupling them from the corpus file's new suite-native
    purpose while preserving their original assertions unchanged. No
    production/`src/main` or `protos/lib` source was modified.

## 0.3.66-SNAPSHOT

- D178: implement the ratified `A_TEST_BODY_AUTHORITY` Process-snapshot Logical
  Case authority model in isolation (docs/project/decisions/tooling/D178_
  PROCESS_SNAPSHOT_LOGICAL_CASE_AUTHORITY_MODEL.md,
  guillermomolina/protos-project-docs@5cbcee32). `logicalCaseExecutionAsync`
  for `protos/test/process-snapshot` no longer treats whole-source-module
  completion as Case authority: it now declares `source`'s top-level `tests`
  (discovery-observational — constructing a named Test never invokes its
  body), resolves exactly one selected Test through the same
  `Discovery.resolveSelectedTest` authority the ordinary suite-native lane
  uses, and invokes only that selected Test's `call()` exactly once; that
  invocation's completion is the Case authority. A signature or selector
  mismatch is classified `rematerialization-error` and rejects before the
  selected Test body ever runs, mirroring `ProtosTestLogicalCaseAttemptBridge`'s
  phase model. The D135 Process-snapshot bootstrap (fresh
  `ProtosProcessRuntime` -> `establishArgumentsForRuntime`/
  `establishEnvironmentForRuntime`) is reused unchanged and still produces a
  fresh Process per Logical Case; `process.args()`/`process.environment()`
  semantics, D152, D153, `CaseAuthority`, and TOOL009-D are unmodified. Actor,
  Group, and the Process conformance corpus/manifest are untouched, and the 15
  `protos/tests/conformance/process` fixtures are not migrated in this slice.
  - `ProtosProcessSnapshotExecution` gains `executeCase(source, prelude,
    expectedSignature, selector)` alongside the unchanged `execute(source,
    prelude)` (still used by `ProtosAsyncProcessSnapshotExecutionFacility`'s
    unrelated whole-source `processSnapshotExecutionAsync` route). Both share
    one extracted bootstrap helper, so the D135 Process/arguments/Environment
    materialization is not duplicated. `executeCase` runs `source` once to
    materialize its `tests` declaration in the bootstrap activation, then
    evaluates the same `Discovery.resolveSelectedTest` selection script
    `ProtosTestLogicalCaseAttemptBridge` uses (sharing its actor module
    state/execution domain so the selected Test closure still resolves
    `process`/`otherProcess`/`emptyProcess` lexically), and finally invokes
    `selected()` as one separate root execution.
  - `ProtosProcessSnapshotLogicalCaseExecutionFacility` no longer validates
    the degenerate "signature names exactly one Case equal to the selector"
    shape; it now validates only argument/entry types (mirroring
    `ProtosTestLogicalCaseExecutionFacility`) and defers real signature/
    selector verification to `executeCase`'s Discovery-based resolution, so a
    Process-snapshot source may now declare more than one named Test.
  - Rewrites `ProtosProcessSnapshotLogicalCaseExecutionFacilityTest` to
    demonstrate the selected-Test authority end to end: the selected Test body
    actually executes exactly once, a failing selected Test body fails the
    Case, selecting one of two declared Tests runs only that one, a signature
    mismatch and a selector mismatch both reject before the selected body
    runs (each distinguished from a body failure by the `rematerialization-
    error` vs `case-execution` phase), and `process.args()`/
    `process.environment()` still read the real D135 bootstrap snapshots
    with a fresh Process per independent Logical Case.

## 0.3.65-SNAPSHOT

- TOOL009-E (#688): add a suite-native Logical Case execution route for the
  `protos/test/process-snapshot` Test Tool lane, so a Logical Case can reach
  `protos/test/process-snapshot` through the same discovery-driven
  `logicalCaseExecutionAsync` protocol already wired for `protos/test/ordinary`
  (TOOL009-D/#687). The D135 Process-snapshot bootstrap
  (`ProtosProcessSnapshotExecution.execute` -> fresh `ProtosProcessRuntime` ->
  `establishArgumentsForRuntime`/`establishEnvironmentForRuntime`) is reused
  unchanged; it is not duplicated or reimplemented. `CaseAuthority`, D152, and
  D153 are unmodified, and Process/Actor/Group corpus migration
  (`protos/tests/conformance/process/manifest.tsv`) remains out of scope.
  - Adds `ProtosProcessSnapshotLogicalCaseExecutionFacility` (Java), a thin
    protocol adapter that translates the 4-argument discovery-driven Logical
    Case call (`sourceAssociation, source, signature, selector`) into the
    existing single-source Process-snapshot execution mechanism. Because the
    Process-snapshot corpus has no Suite/Discovery module structure — each
    source unit is exactly one Case — the adapter validates the degenerate
    one-Case protocol shape (the signature names exactly one Case and the
    selector selects it) and fails closed synchronously on a mismatch,
    without performing a host-side rematerialization search. Execution itself
    is delegated to `ProtosProcessSnapshotExecution.execute(...)`, so every
    accepted Case rematerializes its own fresh Process; two Logical Cases
    never share Process state.
  - `ProtosTestToolAsyncExecutionScope.installWithCaseAuthorities` installs
    the new facility alongside the existing
    `ProtosAsyncProcessSnapshotExecutionFacility` and closes it with the rest
    of the scope.
  - `ProtosTestExecutionRequirementRegistry` extends `addExecutionOnlyBinding`
    with the same optional-route pattern TOOL009-D introduced for the
    7-argument `addBinding` overload: when the new facility's bootstrap slot
    is installed, the `protos/test/process-snapshot` binding additionally
    exposes `logicalCaseExecutionAsync`, reusing that installed slot rather
    than provisioning a parallel one. The binding remains otherwise
    unchanged, and `protos/test/actor`/`protos/test/group`/`protos/test/package`
    are untouched.
  - Adds `ProtosProcessSnapshotLogicalCaseExecutionFacilityTest` demonstrating
    that a `logicalCaseExecutionAsync` call actually reaches
    `ProtosProcessSnapshotExecution` (not merely that the slot exists),
    including successful completion driven by `snapshot-identity.protos`
    (so `process.args()`/`process.environment()` still read from the D135
    bootstrap snapshots), synchronous fail-closed rejection of a
    signature/selector mismatch, and two independent Logical Case invocations
    each observing their own rematerialized Process.
  - Extends `ProtosTestToolExecutionRequirementRegistryTest
    .ordinaryBindingCarriesSuiteNativeLogicalCaseExecutionRouteWhenInstalled`
    to assert the `protos/test/process-snapshot` binding now carries the
    exact installed `ProtosProcessSnapshotLogicalCaseExecutionFacility`
    bootstrap object under `logicalCaseExecutionAsync`.

## 0.3.64-SNAPSHOT

- TOOL009-D (#687): transport the D125 `ExecutionRequirementId` selected by a
  logical Case's corpus into suite-native `LogicalCaseRunner` execution,
  without weakening the D152/D153 inert-`CasePlan` invariant. Process, Actor,
  and Group corpus migration remain out of scope (TOOL009-C/#686 Slices 2-4).
  - `protos/tools/test/LogicalCaseMigration.protos` adds
    `logicalCaseExecutorAsync(corpusExecutionRequirements,
    testExecutionRequirementBindings)`, which returns the
    `LogicalCaseRunner.run` `executorAsync` callback. At each Case's
    execution boundary, the callback resolves the corpus's authoritative
    `ExecutionRequirementId` (never carried on the guest-visible
    `LogicalCasePlan`/`CasePlan` entry) against the existing D125
    `testExecutionRequirementBindings` registry and dispatches to that
    requirement's `logicalCaseExecutionAsync` binding, failing closed for a
    requirement or corpus with no suite-native execution route.
  - `protos/tools/test/Main.protos` records each selected suite's
    `ExecutionRequirementId` by corpus id while building the flat
    suite-native Case plan, and passes
    `LogicalCaseMigration.logicalCaseExecutorAsync(...)` to
    `LogicalCaseRunner.run` in place of the single generic
    `logicalCaseExecutionAsync` executor.
  - `ProtosTestExecutionRequirementRegistry` (Java) exposes the
    already-installed `ProtosTestLogicalCaseExecutionFacility` bootstrap slot
    as an additional optional `logicalCaseExecutionAsync` member of the
    `protos/test/ordinary` binding, reusing that existing facility rather
    than installing a parallel one; the binding is otherwise unchanged and
    the other requirement bindings (`actor`, `group`, `package`,
    `process-snapshot`) are untouched.
  - Extends `protos/tests/tooling/tool009-logical-case-migration.protos`
    (run by the existing `ProtosTestToolLogicalCaseMigrationTest`) with
    direct coverage of the new dispatcher: correct per-corpus requirement
    routing, and fail-closed behavior for a requirement binding with no
    suite-native execution route and for an unrecorded corpus. Adds
    `ProtosTestToolExecutionRequirementRegistryTest
    .ordinaryBindingCarriesSuiteNativeLogicalCaseExecutionRouteWhenInstalled`
    verifying the `protos/test/ordinary` binding carries the exact installed
    facility object and that no other requirement binding gains the route.
  - Updates the literal `Main.protos`-content assertions in
    `ProtosTestToolI8D5CPublicCutoverTest` and
    `ProtosTestToolTool004CProgressPresentationTest` to check for
    `LogicalCaseMigration.logicalCaseExecutorAsync(` in place of the removed
    bare `logicalCaseExecutionAsync` reference.
  - Process, Actor, and Group corpora remain on the incumbent legacy
    execution path; TOOL009-C/#686 Slices 2-4 still own migrating them to
    suite-native, and TOOL009-B/#685 still owns retiring legacy execution
    infrastructure once that migration makes it unnecessary. No
    `CaseAuthority` concept was introduced for Process/Actor/Group and no new
    `ExecutionRequirementId` was added.
  - `make test-java` and the integrated `make test` (Java + native Protos,
    after `make clean`) both pass.

## 0.3.63-SNAPSHOT

- TOOL009-C (#686, Slice 1): migrate the seven library-conformance
  `RepositoryCorpusPlans` corpora (`uri`, `csv`, `cli`, `math/integer`,
  `crypto/sha256`, `network/ip-addresses`, `network/ip-endpoints`; 78 case
  sources) from legacy Test Tool interpretation (`boolean`, `error`,
  `future-boolean` expectations) to the suite-native authoring model: each
  source now imports `std:test/Test` and `std:test/Assertions`, exposes a
  finite frozen `tests` Array of named `Test` declarations, and keeps prior
  legacy case logic intact inside the Test body. `error`-expectation cases use
  `Assertions.signals(Error, body)`; `future-boolean`-expectation cases
  observe their `Future` explicitly with `value()` inside the Test body
  before asserting, rather than returning the `Future` for runner
  interpretation.
  - `protos/tools/test/RepositoryCorpusPlans.protos` keeps the exact same
    explicit corpus membership, path spelling, and case order for all seven
    corpora; every entry's expectation column now reads `suite-native`. The
    manifest itself is preserved as the explicit membership authority, not
    removed.
  - `ProtosTestToolRepositoryCorpusPlansTest.java` remains the golden
    membership/order JUnit test: `ordinaryLibraryPlansHaveExactExplicitMembership`
    is unchanged, and the renamed
    `csvFutureAndShaCasesRemainOrderedAndAreSuiteNative` now asserts both case
    path identity and the `suite-native` expectation at the previously
    special csv future-boolean and sha256 error indices.
  - Process, Actor, and Group corpora are out of scope for this slice and are
    unchanged; TOOL009-C tracks their migration as separate remaining slices
    under #686, which stays open.
  - `make test-java` and the integrated `make test` (Java + native Protos)
    both pass.

## 0.3.62-SNAPSHOT

- TOOL009: reconcile the JUnit/harness surface with the now-complete
  suite-native corpus migration (`protos/tests/conformance/manifest.tsv` has
  zero non-`suite-native` entries). No runtime, parser, or Standard Library
  behavior changed; this slice is test-harness and test-fixture only.
  - Remove `ProtosFutureObservationFixtureShapeTest`: it asserted the old
    bare-object shape (`future`/`error`/`observe`) of
    `future/failed-value-resignals-recorded-error.protos`, which is now owned
    and re-asserted by that corpus source's own suite-native `Test` body —
    duplicated incumbent ownership over an already-migrated manifest source.
  - Decouple the still-authoritative TOOL002 Test Tool mechanism harnesses
    (`ProtosTestToolFutureResolvedMechanismTest` (F1),
    `ProtosTestToolFutureTerminalMechanismTest` (F2),
    `ProtosTestToolFutureStoredInspectionFixtureSuiteTest` (F3C1B/F3C2),
    `ProtosTestToolFutureObservationPolicyFixtureSuiteTest` (F3E),
    `ProtosTestToolFutureFreshInspectionFixtureSuiteTest` (F3D)) from the
    conformance corpus, whose sample sources they used to borrow by raw text
    before the corpus fixtures changed shape under them. Each harness keeps
    its exact existing mechanism (real filesystem-backed reading,
    `executionInspect` live inspection, fresh-child-process boundary); only
    the sample sources moved to new tooling-owned fixtures under
    `protos/tests/tooling/` (`tool002-future-shape-stored.protos`,
    `tool002-future-shape-fresh.protos`, and five standalone F1/F2 samples),
    reproducing exactly the structural properties (`future`, `error`,
    `observe`, parent identities, dispatch counts) those harnesses require.
  - Update the `tool002-d2-manifest-plan.protos` tooling fixture's first-row
    expectation from the pre-migration `"integer"`/`"2"` to the current
    `"suite-native"`/`"-"`, matching the real corpus manifest without
    restoring any legacy manifest metadata.
  - Adapt `ProtosCoreBootstrapTest#objectMatchConformanceUsesDynamicEqualityExactlyOnceAndReturnsExactResult`
    and `#guestCannotCreateAStandardRootSlotAfterPublication` to inline,
    CORE-only-compatible Protos sources (no `std:test` import), preserving
    the exact CORE-only bootstrap boundary and the exact semantic property
    each test proves, instead of depending on now-suite-native corpus
    fixtures that require a full Standard Library bootstrap.
  - Focal JUnit set and the full `make test-java` lane pass; `make test`
    (integrated Java + native Protos suite) passes.

## 0.3.61-SNAPSHOT

- Implement ratified `D174` and `D176` as `TOOL009-A` (GitHub #684): expose
  bounded in-flight progress observability for the Test Tool so a hung Case can
  be identified during a directory-scoped run. The invocation-wide `D174`
  lifecycle tracker in `Progress.protos` now assigns an invocation-local token
  per Case and forwards presentation-neutral `CaseStarted`/`CaseTerminal` facts,
  plus a caller-rendered display reference, to separate reporting machinery.
  `Main.protos` attaches that machinery and supplies one display renderer per
  execution path, so both the incumbent and the suite-native scheduler report
  through a single tracker. A new Test-Tool-private host facility
  (`ProtosTestToolStalledCaseDiagnosticFacility` and
  `ProtosTestToolStalledCaseReporter`) owns the `D176` policy: after 30 seconds
  with no `CaseTerminal` while work remains in flight, one bounded snapshot of
  at most 8 Case references plus a collapsed `+N more` is written to Test Tool
  stderr, re-arming only after later terminal progress and disarming when
  nothing remains in flight. The diagnostic is advisory only — it never fails,
  cancels, times out, classifies, retries or reschedules a Case, and it changes
  no result or exit code. Normal Tool output remains the compact `D120`
  aggregate progress with no mandatory per-Case terminal write, and Protos guest
  code gains no Clock or Timer capability. Add focused regressions for the
  lifecycle boundary, in-flight tracking, the bounded snapshot, re-arming,
  quiescence under continuing terminal progress, and concurrent observation.
  No timeout policy, retry policy, public event/JSON protocol, JUnit reporting,
  telemetry, or terminal UI framework is introduced. The new facility installs
  one bootstrap-local native Closure, so the `I018` guard records it in the
  audited non-Core native-provider inventory alongside the existing Test Tool
  acquisition and file-selection facilities; the Core native boundary is
  unchanged.

## 0.3.60-SNAPSHOT

- Implement ratified `D159` as `I055` (GitHub #656): remove the public
  `Future.detach()` operation and enforce the strict complete-lifetime
  structured-ownership invariant. The standard Future protocol no longer
  installs a `detach` selector, `ProtosFutureValue` loses its detached state
  and `detach()` operation, and `ProtosTask.detachFromParent()` is removed
  without touching the retained parent/child terminalization, child-drain,
  cancellation-unwind, or Actor-termination machinery. Cancellation, cleanup,
  loop, and cross-Task return fixtures retain their task-local pending
  dependencies without detachment. Add regressions for ordinary missing-selector
  behavior and complete-lifetime child ownership. Normative reconciliation
  updates `FUTURES_AND_TASKS.md`, `EXECUTION_AND_CONTROL.md`, `ACTORS.md`,
  `PARALLEL_EXECUTION.md`, `IO_CORE.md`, `ABSTRACT_RUNTIME.md`, and the guide
  chapters, advancing the specification to `0.1.432`. No public replacement
  background-work API, native-boundary expansion, or license-term change is
  introduced.
- Fix a source-wait cancellation defect exposed by I055 validation:
  `Future.then()` now honors cancellation when its private source wait resumes,
  before waiting again or propagating the source outcome. This prevents an
  endlessly runnable continuation while the source is pending and preserves
  cancellation precedence when the source becomes terminal before resumption.
  The source remains unchanged, and a running non-suspending transform retains
  the ordinary cooperative-cancellation behavior.

## 0.3.59-SNAPSHOT

- Fix `BUG008` — the canonical recursive benchmark corpus (for example
  `protos/benchmarks/micro/closure-call.protos` at its checked-in 10,000-deep
  `repeat` recursion) overflowed the host call stack with
  `java.lang.StackOverflowError` well short of its documented iteration count.
  Root cause: a Protos-level Closure call composes through two nested Bytecode
  `CallTarget` invocations (the ratified `PLAT026` truthful semantic-root shell
  wrapping the untagged execution helper), so host stack consumption per guest
  recursion level is higher than a single-`CallTarget` interpreter would need,
  and no part of the implementation provisioned a call stack sized for that
  cost. This is a host-resource-budget gap, not a `PLAT026` semantic regression:
  the existing two-root architecture and its RootTag truthfulness rationale are
  unchanged. `ProtosCli.run` now dispatches every CLI command (file/REPL/`-e`
  evaluation, `run`, `debug`, `package`, `test`) on a dedicated carrier thread
  with a fixed 64 MiB stack instead of depending on whatever stack size the
  calling thread happened to have, matching `protos/benchmarks/README.md`'s
  existing allowance for a benchmark runtime to "provision a larger call stack
  when required" as long as that runtime option is fixed and recorded; the
  README now records the exact provisioned value. All seven `micro/` workloads,
  both `runtime/` dispatch workloads and the affected `collections/` workloads
  execute successfully at their checked-in iteration counts with unchanged
  documented results. Adds a dedicated regression
  (`regression/deep-recursive-closure-call-stack-capacity.protos`) exercising
  the same 10,000-deep recursive self-call shape as the benchmark corpus;
  writing that regression as ordinary suite-native Test Tool corpus content
  exposed a second, independently reachable instance of the identical gap:
  `ProtosTestToolAsyncExecutionScope$PlatformThreadPerTaskSubmission` gives
  each Test Tool Case its own isolated Process on a fresh
  `protos-test-exact-N` platform Thread that can likewise drive arbitrarily
  deep guest recursion, and that carrier previously kept the JVM's unpatched
  default stack size. Left unfixed, the same 10,000-deep recursion running
  inside that isolated per-Case Process did not merely crash — the resulting
  `StackOverflowError` during Actor/Task unwinding left the Process's
  lifecycle unable to reach `TERMINATED`, so `bin/protos test` hung
  indefinitely instead of failing the Case. `PlatformThreadPerTaskSubmission`
  now provisions the same fixed 64 MiB budget for its carriers. Diagnosing
  and hardening that unwind-time hang-on-`StackOverflowError` path itself
  (independent of stack provisioning) is not in scope here and is not
  addressed by this fix.
  No Protos specification, observable language semantics, or `PLAT026`
  architecture change.
  Implementation version becomes `0.3.59-SNAPSHOT`.

## 0.3.58-SNAPSHOT

- Fix `BUG009` — a self-cancelled Task's suspended `.ensure()` cleanup that
  superseded the cancellation via a non-local return (`^`) whose captured
  return home belonged to a different, currently-suspended Task escaped
  uncaught instead of being recognized, permanently stalling that Task and
  deadlocking anything awaiting its Future. Under ratified `D177`,
  `ProtosBytecodeTaskExecution`'s Task drivers (`runSegment`,
  `runPreparedSegment`) now recognize a non-local return whose captured home
  is unreachable from the executing Task and fail that Task with
  `InvalidReturn`, matching the already-specified completed-home case in
  `spec/semantics/CALLABLES.md` §14. Adds a dedicated regression
  (`regression/escaped-closure-cross-task-invalid-return.protos`) and
  corrects the TOOL009 suite-native migration of the three previously-blocked
  `control/` conformance tests so their `.ensure()`-cleanup closures keep
  their original same-Task return home instead of unintentionally capturing
  the enclosing Test body's.
  Implementation version becomes `0.3.58-SNAPSHOT`.

## 0.3.57-SNAPSHOT

- Advance `TOOL009 — Local-first logical Case Test Tool integration` (GitHub
  #600) with composable invocation-local source selection. Make `--file FILE`
  repeatable, add repeatable recursive `--directory DIR` selection, and project
  both selector families over already-materialized authoritative TestPlans
  without filesystem test discovery. Require every explicit selector to match
  independently, union overlapping selections without duplicate Case execution,
  preserve canonical TestPlan order, retain invocation-CWD relative and absolute
  locator support behind host authority, and keep physical paths out of Case
  identity. Extend the host selection capability, Test Tool option/orchestration
  wiring, focal regressions, and end-to-end coverage accordingly.
  Implementation version becomes `0.3.57-SNAPSHOT`.

## 0.3.56-SNAPSHOT

- Implement `I052 — Remove Core fixed-width integer families and preserve
  interop capability` (GitHub #628) under ratified D156 Candidate D′ and
  specification revision `0.1.430`. Remove the eight former Core
  `UInt8`/`Int8` through `UInt64`/`Int64` source prototypes, prelude bindings,
  conversion/arithmetic/equality/order/hash participation, runtime transfer and
  indexed/I/O widening paths, and the obsolete Test Tool `fixed-integer`
  expectation policy. Core numeric semantics now expose only `Number`,
  unbounded exact `Integer`, and binary64 `Float`. Preserve reusable
  width/range-aware host numeric projection behind the internal
  `ProtosFixedIntegerInteropValue` carrier without granting it guest Core
  prototype, identity, equality, arithmetic, hashing, transfer, or public FFI
  semantics. Reconcile Standard Library domain tests, Core architecture guards,
  current public documentation, and exploratory numeric-boundary notes with the
  ratified model while retaining the historical TOOL002-D3B1 record as retired.
  Implementation version becomes `0.3.56-SNAPSHOT`.

## 0.3.55-SNAPSHOT

- Advance `TOOL009 — Local-first logical Case Test Tool integration` (GitHub
  #600) through the first production D152-M2 ownership cutovers under ratified
  D151, D152, D153, D155 and the LIB018 suite-native authoring model. Wire the
  temporary mixed-ownership Test Tool orchestrator so selected `suite-native`
  manifest entries are discovered before scheduling, projected into one flat
  logical Case plan, executed through fresh semantic Processes with the global
  `--jobs` capacity, and removed from incumbent TOOL002 ownership to prevent
  double execution while unmigrated entries continue through the legacy path.
  Migrate all 13 `library/test/` production sources and all 12 `library/text/`
  production sources to finite frozen `tests` Arrays of named `std:test/Test`
  values, preserving D151 exact `--file` selection and D155 terminal outcome
  precedence across mixed ownership. Correct the migrated malformed-UTF8
  conformance case to construct Bytes through the public UTF8 Encoding surface
  and assert the intended `EncodingError`, eliminating a historical generic
  `error` false positive that could succeed because direct `Bytes()` is no
  longer a public Core binding. Admit both migrated library domains as
  suite-native during the temporary M2 bridge. TOOL009 remains open: additional
  production domains, legacy-owner exhaustion, M3 cleanup and the M4
  migration-scar audit remain pending. Implementation version becomes
  `0.3.55-SNAPSHOT`.

## 0.3.54-SNAPSHOT

- Implement `I053 — Implement scalar Core String indexing and preserve grapheme
  capability` (GitHub #629) under ratified D157 Candidate D and specification
  revision `0.1.429`. Change Core `String.size` and `String.at` / bracket read
  from Unicode-17 extended-grapheme units to exact Unicode scalar values,
  including supplementary-plane scalars as one position and decomposed or
  multi-scalar emoji sequences as multiple positions. Preserve zero-based
  semantic-Integer indexing, existing Error behavior, String immutability,
  exact-scalar identity/no-normalization, concatenation, Encoding boundaries,
  and strict String-family receiver checks. Retain Unicode-17 default
  extended-grapheme segmentation behind an internal reusable boundary for the
  separately placed Standard Library capability without choosing its deferred
  public API. Enforce the semantic String invariant by rejecting unpaired
  UTF-16 surrogates at `ProtosStringValue` construction, migrate Core
  conformance coverage to scalar expectations, and retain focused Java evidence
  for preserved grapheme segmentation. Implementation version becomes
  `0.3.54-SNAPSHOT`.

## 0.3.53-SNAPSHOT

- Advance `TOOL009 — Local-first logical Case Test Tool integration` (GitHub
  #600) under ratified D151, D152, D153 and D155. Replace physical source-path
  arguments at the suite-native discovery and logical Case execution guest
  boundaries with canonical `[CorpusId, relativePath]` source associations,
  resolving exact physical locators only inside the existing D151 host authority
  boundary. Reuse the same invocation-local corpus source roots for discovery
  and execution, preserving inert logical identity across planning and fresh
  Process rematerialization. Add the temporary D152-M2 mixed-ownership migration
  scaffold with explicit legacy/suite-native partitioning, D151 source
  selection, and D155 outcome precedence (`3 > 1 > 0`) in preparation for the
  first production Case migration. No production test source changes ownership
  in this checkpoint. Implementation version becomes `0.3.53-SNAPSHOT`.

## 0.3.52-SNAPSHOT

- Implement `I051 — Remove public Object.identityHash selector` (GitHub #621)
  under ratified D154 Candidate A and specification revision `0.1.428`. Remove
  the standard guest-visible `Object.identityHash()` selector while preserving
  primitive non-overridable semantic identity hashing, `===` / `!==`,
  `IdentityMap` identity semantics, default `Object.hash()`, execution-scoped
  identity-hash stability/collision rules, and ActorRef/GroupRef identity.
  Ordinary user-defined slots named `identityHash` remain ordinary behavior and
  have no semantic identity-hash authority. Add focused guest and Java coverage
  proving the standard selector is absent and IdentityMap does not dispatch
  user-defined `identityHash` callbacks. Implementation version becomes
  `0.3.52-SNAPSHOT`.

## 0.3.51-SNAPSHOT

- Advance `TOOL009 — Local-first logical Case Test Tool integration` (GitHub
  #600) under ratified D152, D153, LIB018-0 and D155. Add authority-free
  suite-native discovery of the explicit `tests` declaration, inert ordered
  logical Case plans, signature-checked fresh-Process Case rematerialization,
  case-private execution observations, a global work-conserving logical Case
  scheduler, and terminal-state Case result classification. Wire the discovery
  and execution facilities into the Test Tool bootstrap, allow D155
  infrastructure-aborted outcomes without manufacturing historical D108
  evidence, and reuse D151 corpus/source authority for exact logical-source
  resolution without exposing physical paths to guest code. Add focused Protos
  and Java coverage for discovery, planning, scheduling, execution,
  rematerialization, result policy, bootstrap integration, and source-authority
  resolution. This remains an intermediate migration checkpoint: incumbent
  production test ownership is unchanged and no repository test source has yet
  been cut over to suite-native ownership. Implementation version becomes
  `0.3.51-SNAPSHOT`.

## 0.3.50-SNAPSHOT

- Begin `LIB018 — Expand Protos testing support beyond the initial Assertions
  surface` (GitHub #592) under the ratified LIB018-0 Candidate C-prime
  authoring model. Add `std:test/Test` as the canonical ordinary Test-value
  constructor: `Test(name, body)` requires a non-empty semantic String name and
  returns one fresh frozen ordinary object exposing local `name` and zero-argument
  `call` slots. Test invocation returns the exact body result and preserves exact
  Error/control propagation, while body callability remains deferred until
  invocation. Add no nominal Test runtime type, Suite abstraction, ambient
  registration mechanism, fixture lifecycle, parameterization framework, or
  Test Tool discovery/scheduling change. Add focused Protos conformance for the
  Test value surface, freshness/frozen behavior, result/Error propagation,
  invalid names, and deferred body validation. Implementation version becomes
  `0.3.50-SNAPSHOT`.

## 0.3.49-SNAPSHOT

- Implement `I049 — Implement Map.atIfAbsent expected-absence lookup` (GitHub
  #598) under ratified D142 Candidate C and specification revision `0.1.421`.
  Add standard `Map.atIfAbsent(key, fallback)` and
  `IdentityMap.atIfAbsent(key, fallback)` with exactly one logical key search,
  exact present-value return including `null` and `false`, and path-sensitive
  ordinary zero-argument fallback invocation only on absence. Preserve normal
  Map `hash` and directed `queryKey == storedKey` observability, IdentityMap
  identity lookup without guest key callbacks, fallback suspension/resumption
  without search replay, no second search after fallback, no implicit mutation,
  and eligibility on closed/frozen maps. Keep existing `at` and `containsKey`
  behavior unchanged, and add focused Protos conformance plus Bytecode
  DSL/C-prime regression coverage. Implementation version becomes
  `0.3.49-SNAPSHOT`.

## 0.3.48-SNAPSHOT

- Implement `I050 — Implement D143 multiple-slot creation` (GitHub #599)
  under ratified D143 and specification revision `0.1.422`. Add dedicated
  `(a, b): source` multiple-slot creation with one RHS evaluation, strict
  receiver-owned standard Array indexed-state validation, complete short-source
  preflight, ascending shallow observation of exactly the first N indexed
  references before mutation, ignored extra elements, and ordinary bare-slot
  creation left-to-right with deliberate no-rollback behavior. Return the exact
  RHS object on success, preserve closure and object-body creation contexts,
  reserve every target name against object composition, bypass user-visible
  `at`/iteration/deconstruction protocols, and reject non-Array, inherited-only
  Array lookalike, and short sources before creating any target slot. Add
  focused parser/canonicalization coverage, Protos conformance coverage, and
  Java runtime-mechanics regressions for preflight and partial effects.
  Implementation version becomes `0.3.48-SNAPSHOT`.

## 0.3.47-SNAPSHOT

- Implement `TOOL008 — Test Tool exact file-backed focal selection` (GitHub
  #591) under ratified D151 Candidate B-prime. Add exact separate-token
  `protos test --file FILE` selection over already-authoritative CaseSpecs,
  resolving relative locators from the invocation working directory and
  accepting absolute locators without turning physical paths into Test Tool
  identity. Preserve authoritative TestPlan ordering and CaseSpec metadata,
  reject arbitrary or zero-match source files, support multiple CaseSpecs for
  one source, and continue through the existing D108 scheduler, resource,
  result-classification and reporting paths. Implementation version becomes
  `0.3.47-SNAPSHOT`.

## 0.3.46-SNAPSHOT

- Implement `I048 — Implement Object null-aware control protocol` (GitHub #589)
  under ratified D144 Candidate B-prime and specification revision `0.1.427`.
  Add source-backed ordinary `Object.ifNull(block)` and
  `Object.ifNotNull(block)` control messages using exact canonical-null
  identity, with no truthiness and no null identity conferred by delegation.
  Preserve eager argument-expression evaluation, branch-sensitive callback
  validation, ordinary polymorphic callback invocation, exact receiver/result
  identity, unchanged Future results with no implicit await/adoption, and
  ordinary Error/non-local-control propagation. Keep failed lookup and D142 Map
  absence semantics unchanged, retain ordinary custom overrides, document the
  public selectors in distributable Core source, and add focused conformance
  plus source-backed/native-boundary guards. Implementation version becomes
  `0.3.46-SNAPSHOT`.

## 0.3.45-SNAPSHOT

- Complete `I045-B — Add exact standard-owner recognizes predicates` (GitHub
  #584) under ratified D147 Candidate B-prime and specification revision
  `0.1.426`. Add exact canonical-owner `recognizes(value)` predicates for
  `String`, ordinary unbounded `Integer`, `Float`, and standard-state `Array`.
  Preserve mismatch-as-false behavior, strict owner/arity errors, fixed-width
  Integer exclusion, Array recognition independent of immediate delegation
  parent, and the no-candidate-dispatch/no-coercion contract. Migrate matching
  Standard Library and bundled Tool family-validation probes to the new
  predicates while preserving caller-specific failure behavior, and add focused
  semantic and native-boundary coverage. Implementation version becomes
  `0.3.45-SNAPSHOT`.

## 0.3.44-SNAPSHOT

- Implement `LIB017 — Integer ranges and progression iteration` (GitHub #570)
  under the owner-approved G-prime decision. Add
  `std:collections/Range` with `each(start, stop, block)` and
  `reverseEach(start, stop, block)` over the same half-open `[start, stop)`
  domain of ordinary unbounded Integer values. Preserve empty equal/reversed
  bounds, ordinary polymorphic callback invocation, ignored callback results,
  canonical `null` normal completion, and ordinary Error/non-local-control
  propagation. Reject Float, fixed-width Integer-family, and delegated
  Integer-lookalike bounds; keep host-width limits, proportional
  materialization, first-class Range values, arbitrary step, generic
  Iterable/Iterator integration, slicing, and range syntax out of this slice.
  Add focused conformance coverage for traversal order, callback behavior,
  strict bound domains, single bound evaluation, unbounded endpoints, and
  control propagation. Implementation version becomes `0.3.44-SNAPSHOT`.

## 0.3.43-SNAPSHOT

- Implement `I047 — Implement strict String.concat semantics` (GitHub #587)
  under ratified D141 Candidate M1 and specification revision `0.1.424`. Add
  ordinary variadic `String.concat` with strict semantic-String receiver and
  argument domains, zero-argument semantic identity, exact scalar-sequence
  concatenation, and no normalization or implicit textual conversion. Preserve
  ordinary eager argument/spread evaluation before method-body validation,
  reject invalid inputs without user callbacks or partial String results, keep
  standard `+` unchanged, and keep `+` and `concat` as independent selectors.
  Add focused conformance coverage and update the audited Java-native String
  boundary. Implementation version becomes `0.3.43-SNAPSHOT`.

## 0.3.42-SNAPSHOT

- Complete `I044 — Remove custom symbolic binary operators` (GitHub #583)
  under ratified D149 Candidate A. Remove arbitrary custom symbolic binary
  source syntax, the `CUSTOM_OPERATOR` token/category, the custom-binary parser
  path, custom precedence/mixing behavior, and the dedicated conformance and
  canonicalization coverage. Preserve the fixed standard symbolic surface and
  precedence ladder, D148-derived `!=`, ordinary named one-argument messages,
  and `Object.alias`. Unsupported maximal symbolic spellings are lexical errors
  and are not split into shorter standard operators; forms such as `!!x`,
  `^^x`, `--x`, `-!x`, and `!-x` therefore remain invalid. Implementation
  version becomes `0.3.42-SNAPSHOT`.

## 0.3.41-SNAPSHOT

- Continue `I046-B — Add standard prelude fail callable` (GitHub #585) with the
  bounded equivalent-source migration approved by D146. Remove 46 redundant
  local zero-argument `fail` wrappers across 29 Standard Library and bundled
  Tool source files where the wrapper was exactly `Error().signal()` and had no
  additional state, alternate Error binding, reassignment, or value-observable
  behavior. Those call sites now resolve the ordinary frozen-prelude `fail`
  callable. Preserve stateful/custom local `fail` helpers, direct
  `Error().signal()` sites, `error.signal()` identity semantics, and
  `std:test/Assertions`. Implementation version becomes `0.3.41-SNAPSHOT`.

## 0.3.40-SNAPSHOT

- Begin executable `I046-B — Add standard prelude fail callable` (GitHub #585)
  under ratified D146 Candidate B-prime and specification revision `0.1.423`.
  Add `fail` as an ordinary source-backed zero-argument Closure in the frozen
  standard prelude. Each reached invocation creates and signals one fresh
  generic Error with canonical standard `Error` parentage, while extraction,
  caller-local `Error` shadowing, and ordinary local `fail` shadowing preserve
  the ratified semantics. Add focused conformance for fresh identity/parentage,
  canonical provenance under shadowing, and wrong arity. Bounded production
  call-site migration remains subsequent I046 work. Implementation version
  becomes `0.3.40-SNAPSHOT`.

## 0.3.39-SNAPSHOT

- Complete `PERF009-C` (GitHub #559) by removing the fixed wall-clock
  validation budget introduced during the post-quarantine validation work.
  Restore GitHub CI to the canonical
  `make test JAVA_TEST_JOBS=4 PROTOS_TEST_JOBS=4` correctness path and remove
  the `test-budget` target and timeout guard. Test execution has no fixed
  duration acceptance threshold; proportional routine workloads remain governed
  by `AGENTS.md`, while future measured performance regressions are investigated
  when evidence shows a regression. Implementation version becomes
  `0.3.39-SNAPSHOT`.

## 0.3.38-SNAPSHOT

- Fix `BUG007 — PERF006 canonical coverage still expects removed args
  intrinsic` (GitHub #586). Remove the stale PERF006 B6A1 bytecode coverage
  that still expected bare `args` to expose the caller-supplied argument vector
  after I042/D145 removed `args` from the intrinsic model. Preserve all other
  PERF006 B6A1 canonical/bytecode coverage and keep bare `args` as an ordinary
  identifier. Implementation version becomes `0.3.38-SNAPSHOT`.

## 0.3.37-SNAPSHOT

- Implement `I042 — Remove Closure args intrinsic` under ratified D145
  Candidate A. Remove bare `args` from the reserved/intrinsic lexer, parser,
  surface/canonical and bytecode execution path; `args` is now an ordinary
  identifier and may be used for parameters, rest parameters, local slots and
  ordinary lookup. Preserve internal caller-supplied argument vectors required
  for binding, preserve required/default/rest/spread behavior, and leave
  `process.args()` unchanged. Replace obsolete ambient-`args` conformance with
  ordinary-identifier/rest-forwarding coverage and reconcile specification
  revision `0.1.420`. Implementation version becomes `0.3.37-SNAPSHOT`.

## 0.3.36-SNAPSHOT

- Implement `I043 — Implement derived inequality semantics` (GitHub #582) under
  ratified D148 Candidate A-prime. Keep the `!=` source operator while lowering
  it to exactly one ordinary `==` invocation followed by strict canonical
  Boolean validation and inversion. Remove the independent standard
  `Object.!=` protocol and its source-backed Core bootstrap behavior, so a
  structural slot named `!=` no longer changes source inequality. Preserve
  `===` / `!==`, Map/hash equality semantics, left-to-right exactly-once
  evaluation, Errors/control, and suspension behavior. Add focused Java and
  Protos conformance coverage; the Protos suite remains green at 1301/1301.
  Reconcile the normative specification at revision `0.1.419`. Implementation
  version becomes `0.3.36-SNAPSHOT`.

## 0.3.35-SNAPSHOT

- Complete the implementation slice for `PERF009-C — Post-quarantine
  full-suite budget and validation-routing retirement` (GitHub #559). Normalize
  routine test workloads whose cost came from repeated guest bootstrap/execution
  rather than the correctness property being proved, while preserving every
  retained invalid-input and semantic case. Establish the owner-approved
  180-second complete-repository validation budget for
  `make test JAVA_TEST_JOBS=4 PROTOS_TEST_JOBS=4`, add a fail-visible retained
  budget guard used by CI, and retire the PERF007 `FULL:NON_TOOL` temporary Tool
  quarantine so shared, unknown, unmapped, empty, and top-level closure deltas
  again require complete Maven validation. Retain the independently justified
  `TOOL_LOCAL:PACKAGE` and `TOOL_LOCAL:TEST` intermediate-publication routing.
  Implementation version becomes `0.3.35-SNAPSHOT`.

## 0.3.34-SNAPSHOT

- Reconcile the Standard Library documentation extractor with the D138
  source-local documentation layer for `TOOL007` (GitHub #556). Remove the
  extractor's duplicate `//!`/`///` parsing and association logic and delegate
  authored documentation ownership and validation to
  `ProtosSourceDocumentation`. Preserve D064/D067 publication semantics:
  Standard Library artifacts continue to expose the top-level module surface,
  while valid nested D138 documentation is accepted without creating additional
  published symbols. Implementation version becomes `0.3.34-SNAPSHOT`.

## 0.3.33-SNAPSHOT

- Implement `TOOL007-A3 — named slot source-documentation association`
  (GitHub #556) under ratified D138 Candidate A′. Associate contiguous
  line-leading `///` documentation blocks with exact source-local named
  `SurfaceSlotCreation` owners, including nested and member-target creations.
  Preserve distinct same-name occurrences, allow parenthesized grouping as a
  transparent source boundary, and fail closed across blank lines, ordinary
  comments, unrelated constructs, scope boundaries, non-owner forms, and
  inline documentation markers. Standard Library documentation extractor
  reconciliation remains deferred. Implementation version becomes
  `0.3.33-SNAPSHOT`.

## 0.3.32-SNAPSHOT

- Implement the initial `LIB016 — Protos testing support library and Test Tool
  boundary` public standard-library surface under the ratified LIB016-0 design.
  Add `std:test/Assertions` with the ordinary `AssertionFailure` Error prototype,
  `require(condition)`, and `signals(errorPrototype, body)`. Preserve the Core
  Error model and Test Tool boundary: assertion failures are fresh ordinary
  Error occurrences, `require` accepts only Boolean conditions, `signals`
  delegates matching and propagation to ordinary `Error.handle`, and no TOOL002,
  Core, parser, compiler, or runtime semantics change. Add focused conformance
  coverage for the public surface, success/failure behavior, fresh assertion
  failures, non-Boolean misuse, exact matching Error identity, missing expected
  errors, and unchanged propagation of nonmatching Errors. Protos conformance
  validation passes 1299/1299 cases. Implementation version becomes
  `0.3.32-SNAPSHOT`.

## 0.3.31-SNAPSHOT

- Implement `TOOL007-A2 — module source-documentation association`
  (GitHub #556) under ratified D138 Candidate A′. Add tooling-only extraction
  of one contiguous line-leading `//!` documentation block from the module
  preamble, preserving ordinary comment behavior for Protos execution. Reject
  late module documentation, multiple module documentation blocks, and inline
  documentation markers. Slot-level `///` association remains deferred to
  TOOL007-A3. Implementation version becomes `0.3.31-SNAPSHOT`.

## 0.3.30-SNAPSHOT

- Implement `TOOL007-A1 — source-local documentation owner inventory`
  (GitHub #556) under ratified D138 Candidate A′. Add the minimal
  source-documentation ownership projection over the parser-authoritative
  Surface AST, identifying the module source unit plus explicit named
  `SurfaceSlotCreation` occurrences in source order, including nested and
  member-target creations. Preserve occurrence-local identity so repeated
  names remain distinct, while parameters and assignments remain outside the
  independent documentation-owner model. Documentation comment association
  (`//!` and `///`) remains deferred to subsequent TOOL007-A slices.
  Implementation version becomes `0.3.30-SNAPSHOT`.

## 0.3.29-SNAPSHOT

- Complete `PERF009-B — Quarantined Java test normalization and reintegration`
  (GitHub #546) by removing the remaining Java slow-test quarantine while
  preserving the semantic and integration properties owned by the retained
  tests. Normalize external-package planning failure coverage to construct and
  retain only the borrowed custody actually exercised by that failure path, and
  reuse one Core bootstrap when checking multiple borrowed custodies. Reuse one
  real package execution plan across the independent malformed-generation,
  shape, location, and runtime-alias rejection checks instead of rebuilding the
  same expensive fixture for every corruption. Split JSON parser validation
  into proportional ordinary correctness coverage and an explicit
  `test-java-stress` surface that retains the original 2048-level nesting and
  2048-element implementation-shape guards. Pin Maven Surefire 3.5.6 after
  demonstrating that inherited 3.2.5 misattributes JUnit testcases between XML
  suites under class-parallel execution while 3.5.6 reports 1853 parallel
  testcases with zero suite/class mismatches. Tune the local Java-test default
  from eight to six jobs from measured throughput/latency evidence; CI retains
  its explicit four-job setting. Remove the PERF009 Java quarantine completely.
  The explicit Java stress validation passes 2/2 tests and the full ordinary
  validation passes, including 1292/1292 Protos tests. No Protos-visible
  language, specification, package-format, or Standard Library semantics
  change. Implementation version becomes `0.3.29-SNAPSHOT`.

## 0.3.28-SNAPSHOT

- Implement `CLI009 — Direct-file local module resolution` (GitHub #555) under ratified D137
  Candidate B′. Add an official direct-file resolver domain for explicit `./`
  and `../` module specifiers, resolve them relative to the importing local
  source inside the selected entry-directory tree, preserve exact component
  spelling and fail-closed no-follow confinement, and keep CWD lookup,
  implicit extensions, index/search-path probing, absolute-file semantics and
  general Filesystem authority absent. Give direct and debug file entries a
  canonical `ModuleKey` before execution and route them through the existing
  cache-before-execute initial-module lifecycle so `main -> helper -> main`
  aliases the same partially initialized entry instance. Preserve `std:`
  composition and leave package `self:`/`dep:` plus locationless `-e`/REPL
  resolution unchanged. Add focused resolver, CLI cycle/confinement, debugger
  parity and routing coverage and document the direct-file namespace. Core
  language and package semantics remain unchanged. Implementation version
  becomes `0.3.28-SNAPSHOT`.

## 0.3.27-SNAPSHOT

- Advance `AUD012 — Direct Java guest-execution path audit` (GitHub #541) under
  ratified PLAT035 by completing retirement of the legacy executable-AST
  backend. Remove `CanonicalToTruffleLowerer` and the
  `ProtosExpressionNode`/`ProtosRootNode` execution tree, collapse Closure
  execution planning onto the Truffle Bytecode DSL backend, and require
  source-backed execution crossing Polyglot Context ownership boundaries to be
  rematerialized through the entered `ProtosLanguageContext`. Migrate retained
  execution tests to the Bytecode-backed boundaries where appropriate and
  remove tests whose only subject was the retired AST backend. Strengthen the
  fail-closed legacy-execution guard with an explicit retired-backend symbol
  family and record the PLAT035 execution/test-placement rule in `AGENTS.md`.
  Final source/test rescan finds no references to the retired backend classes.
  After reconciliation with current `main`, the affected execution focal set
  passes 33/33 tests; the Java lane passes 1849/1849, the separately executed
  DAP/LSP set passes 3/3, packaging succeeds, and the Protos Test Tool suites
  pass 1292/1292. No Protos-visible language, specification, or Standard
  Library semantics change. Implementation version becomes `0.3.27-SNAPSHOT`.

## 0.3.26-SNAPSHOT

- Advance `I041-D — D131 matching conformance reconciliation` (GitHub #550).
  Reconcile the retained matching evidence around the ratified protocol-first
  model and make the ordinary `Array.match`, `Map.match`, and `Object.caseOf`
  implementations safe on the production Task/C-prime execution path. Route
  nested matcher, case action, Map key `hash`, and query-side `==` calls through
  structured Bytecode dispatch instead of synchronous guest invocation inside a
  Task. Preserve D131 ordering and observation rules: Array matcher/subject
  snapshots remain shallow and left-to-right; Map matcher and subject
  associations remain stable snapshots, all required associations are resolved
  through the normal Map hash/equality law before the first mapped-value matcher,
  and case selection remains insertion ordered with first-success commitment.
  Add direct `Object.match` authority coverage, additional `caseOf` ordering,
  uniqueness, and lazy-validation coverage, fresh-Process regressions for all
  three structured paths, and seven protocol-first matching cases in the primary
  Protos conformance manifest. The matching focal set passes 36/36 tests, the
  authoritative Java lane passes 1861/1861, and the Protos Test Tool suites pass
  1278/1278 with the primary manifest at 865/865. Specification remains at
  revision `0.1.417`; implementation version becomes `0.3.26-SNAPSHOT`.

## 0.3.25-SNAPSHOT

- Advance `PERF009-B — Quarantined Java test normalization and reintegration`
  (GitHub #546) by reintegrating the former
  `ProtosPackageToolProtosTest` quarantine into the ordinary Java lane while
  preserving or strengthening its semantic and integration coverage. Move the
  six frozen positive ContentIdentity digest and verification vectors into the
  canonical Test Tool project-tree corpus, and move filesystem-backed fresh/stale resolution-input
  lock checks into a dedicated project-tree corpus. Preserve the TextReader
  multi-chunk regression with valid TOML larger than the 8192-byte read-ahead
  boundary without retaining hundreds of parser-scale exports. Split the
  remaining 26 Java host/runtime tests by their existing responsibilities into
  Manifest, metadata persistence/lock, filesystem security, and ContentIdentity
  integration classes with shared harness support. Across five repeated
  comparable runs, the maximum observed class times are 1.975 s, 1.941 s,
  0.066 s, and 2.216 s respectively, with maximum individual test time
  1.347 s; all satisfy the PERF009 `< 5 s` class and `< 2 s` per-test budgets.
  Retain the pure-Protos SHA-256 implementation while replacing recursive
  per-bit ternary operations with semantically equivalent 2-bit lookup-table
  processing. Remove the former Package Tool class from the slow-test
  quarantine, leaving four quarantined Java classes. No Protos-visible
  language, specification, package-format, or Standard Library semantics
  change. Implementation version becomes `0.3.25-SNAPSHOT`.

## 0.3.24-SNAPSHOT

- Advance `I041-C — remove the superseded dedicated matching implementation`
  (GitHub #550). Remove the pre-D131 `subject match { ... }` language mechanism
  completely after the ordinary protocol-first replacement became available.
  Delete the dedicated surface and canonical Match ASTs, match coverage model,
  parser grammar, canonicalization/lowering paths, Truffle nodes, Bytecode
  lowering and Match-specific Bytecode operations, together with their obsolete
  Java execution/analysis tests and retained conformance corpus. Remove the 38
  retired matching cases from the primary Test Tool manifest. Preserve
  `Object.match`, `Array.match`, `Map.match`, `Any.match`, and `Capture.match` as
  ordinary D131 protocol behavior; `match`, `case`, `when`, `exact`, and
  `captures` remain ordinary identifiers. Retain an explicit parser regression
  proving that the removed dedicated syntax is rejected while ordinary
  `pattern.match(subject)` remains valid. Specification remains at revision
  `0.1.417`; implementation version becomes `0.3.24-SNAPSHOT`.

## 0.3.23-SNAPSHOT

- Advance `AUD012 — Direct Java guest-execution path audit` (GitHub #541)
  under ratified PLAT035 by removing another production and retained-Java
  consumer block from the legacy executable-AST backend. Route Process
  snapshot execution, canonical initial-module unhosted execution, and the
  ModuleRuntime unhosted fallback through initialized Polyglot Contexts and
  the canonical public-parse Bytecode path while preserving activation,
  module-cache, signal, and Process-hosting behavior. Migrate TOML parser
  module execution and Process-context parallel-routing coverage away from
  direct `ProtosSourceCompiler.compile(...)`, and move the Test Tool Manifest
  compile-only check to `Context.parse(...)` so it remains non-executing while
  exercising the canonical frontend/Bytecode path. Update A4B3 architecture
  evidence accordingly and add direct unhosted Bytecode coverage for initial
  modules and ModuleRuntime. Deliberately retain legacy-backend-specific
  compiler/lowering tests and `ProtosSourceFileLoader`, whose Core-bootstrap
  ownership requires a separate retirement cut. The combined affected focal
  set passes 40/40 tests. No Protos-visible language, specification, or
  Standard Library semantics change. Implementation version becomes
  `0.3.23-SNAPSHOT`.

## 0.3.22-SNAPSHOT

- Advance `AUD012 — Direct Java guest-execution path audit` (GitHub #541) under
  ratified PLAT035 by migrating the remaining straightforward retained-Java
  semantic, representation, runtime, and conformance harness block away from
  direct legacy-AST `ProtosSourceCompiler.compile(...).call(...)` and
  `ProtosSourceFileLoader` execution routes onto the canonical Bytecode-backed
  `ProtosTestExecutionSupport` boundary. Cover TOML data-model and closure
  conformance, IP address/endpoint modules, retained structural matching
  representation and error cases, Array lifecycle/callback behavior, Core
  bootstrap and Context source behavior, the exact execution facility, and
  filesystem language, maturity, and tree-surface conformance. Remove stale
  `ProtosSourceFileLoader` imports from already-migrated Future observation and
  integrated filesystem-tree harnesses. The final Block 6 rescan leaves direct
  compiler uses only where they are compiler/backend, explicit Bytecode,
  compile-only, architecture, PERF006, Context-routing, or legacy-backend
  retirement evidence; those remain for the subsequent PLAT035 retirement
  work. The combined affected Java set passes 58/58 focal tests. No
  Protos-visible language, specification, Standard Library, or production
  runtime semantics change. Implementation version becomes
  `0.3.22-SNAPSHOT`.

## 0.3.21-SNAPSHOT

- Advance `I041-B4 — structural Map.match publication` (GitHub #550).
  Publish the ratified D131 open/subset structural matching protocol on ordinary
  normal standard Map values. Require the matcher receiver to own normal
  standard Map keyed-entry state and return canonical `false` for an ineligible
  subject or a missing required association. Establish stable shallow
  observations of matcher and subject associations before any requirement
  search or nested value matching; preserve representative key references,
  recorded hashes, mapped values, and insertion order for the attempt. Resolve
  every matcher requirement against the observed subject associations using the
  existing normal Map query-side `hash` / `==` law before invoking the first
  mapped-value child matcher. Ignore unrelated subject associations, then invoke
  resolved child matchers exactly once in matcher insertion order through
  ordinary `childMatcher.match(selectedMappedValue)` dispatch. Canonical
  `false` fails immediately, canonical `true` contributes no captures, and each
  non-empty standard Array result contributes its shallowly observed outer
  elements to the positional capture sequence without flattening captured Array
  values themselves. Invalid reached matcher outcomes signal ordinary Error.
  Return canonical `true` when no captures are produced, otherwise return one
  new standard Array in child/capture order. Extend focal coverage for open
  subset behavior, eligibility, complete pre-resolution, matcher order, stable
  observation, normal Map key law, hash count, capture composition, capture
  carrier stability, and invalid outcomes. Specification remains at revision
  `0.1.417`; implementation version becomes `0.3.21-SNAPSHOT`.

## 0.3.20-SNAPSHOT

- Advance `AUD012 — Direct Java guest-execution path audit` (GitHub #541) under
  ratified PLAT035 by migrating another retained-Java runtime and representation
  block from direct `ProtosSourceCompiler.compile(...).call(...)` legacy-AST
  execution to the canonical Bytecode-backed
  `ProtosTestExecutionSupport.evaluate(...)` boundary. Migrate IdentityMap
  represented-value/bootstrap-binding coverage and the ModuleRuntime resolver,
  namespace, canonical-key cache, Actor-local cache, retry/eviction, and
  host-failure translation harnesses. In `ProtosPolymorphicInvocationTest`,
  migrate the represented Object.call parent-materialization and call-spread
  representation cases while deliberately retaining the dedicated nested-call
  compiler/lowering test on `ProtosSourceCompiler`. No Protos-visible language,
  specification, Standard Library, or production runtime semantics change.
  Implementation version becomes `0.3.20-SNAPSHOT`.

## 0.3.19-SNAPSHOT

- Advance `PERF009-B — Quarantined Java test normalization and reintegration`
  (GitHub #546) by reintegrating `ProtosTomlEncoderModuleTest` into the ordinary
  Java lane. Share one Core bootstrap across the semantic encoder class and use
  the canonical `ProtosTestExecutionSupport` execution boundary. Separate
  ordinary nested encode/parse semantic round-trip coverage from the
  input-depth recursion regression into
  `ProtosTomlEncoderDeepContainerRegressionTest`. Historical pre-LIB010-C2
  recursive encoder evidence establishes Array last-pass/first-fail depths
  `74`/`75` and selected guard `139`, and inline
  Table last-pass/first-fail depths `77`/`78` with selected
  guard `142`; the selected guards reproduce `StackOverflowError`
  against the prohibited historical implementation while the current iterative
  encoder passes. Five fresh-JVM repetitions measure
  `ProtosTomlEncoderModuleTest` at at most 3.498 s/class and
  0.656 s/test and the deep-container regression class at at most
  1.792 s/class and 1.036 s/test, satisfying the `< 5 s`
  class quarantine threshold and `< 2.0 s` hard per-test budget with equivalent
  or stronger regression ownership. Remove `ProtosTomlEncoderModuleTest` from
  `JAVA_SLOW_TEST_EXCLUDES`. No Protos-visible language, specification,
  Standard Library, or production runtime behavior changes. Implementation
  version becomes `0.3.19-SNAPSHOT`.

## 0.3.18-SNAPSHOT

- Advance `I041-B3 — structural Array.match publication` (GitHub #550).
  Publish the ratified D131 structural matching protocol on ordinary standard
  Array values. Require the matcher receiver to own standard Array indexed state;
  return canonical `false` for an ineligible subject or an exact-length mismatch;
  and take one shallow snapshot of both matcher and subject Arrays before any
  child matcher is invoked. Invoke reached children exactly once from left to
  right through ordinary `matcherElement.match(subjectElement)` dispatch.
  Canonical `false` fails immediately, canonical `true` contributes no captures,
  and each non-empty standard Array result contributes its shallowly observed
  outer elements to the positional capture sequence without flattening captured
  Array values themselves. Invalid reached matcher outcomes signal ordinary
  Error. Return canonical `true` when no captures are produced, otherwise return
  one new standard Array containing the captures in child/capture order. Extend
  the audited native Array representation boundary for the required state
  snapshot and add focal coverage for eligibility, exact length, evaluation
  order, fail-fast behavior, capture composition, matcher/subject snapshot
  stability, child-capture carrier stability, invalid outcomes, and inherited
  behavior on an ineligible receiver. Specification remains at revision
  `0.1.417`; implementation version becomes `0.3.18-SNAPSHOT`.

## 0.3.17-SNAPSHOT

- Advance `AUD012 — Direct Java guest-execution path audit` (GitHub #541) under
  ratified PLAT035 by migrating retained-Java representation and lifecycle
  harnesses from direct `ProtosSourceCompiler.compile(...).call(...)`
  legacy-AST execution to the canonical Bytecode-backed
  `ProtosTestExecutionSupport.evaluate(...)` boundary. Migrate Map lifecycle,
  Path representation, Array factory materialization, and JSON actor-transfer
  coverage while preserving Java ownership of represented-value state,
  prototype/lookup identity, close/freeze semantics, Actor graph-transfer
  assertions, and host-side runtime inspection. No Protos-visible language,
  specification, Standard Library, or production runtime semantics change.
  Implementation version becomes `0.3.17-SNAPSHOT`.

## 0.3.16-SNAPSHOT

- Advance `I041-B2 — ordinary Object.caseOf publication` (GitHub #550).
  Publish the ratified D131 `caseOf` behavior as an ordinary `Object` message.
  Require an eligible normal standard Map as the case carrier, establish one
  shallow insertion-ordered association snapshot before matcher execution, and
  attempt each observed matcher exactly once through ordinary
  `matcher.match(subject)` dispatch. Canonical `false` continues, canonical
  `true` invokes the selected callable with zero arguments, and a non-empty
  standard Array spreads positional captures into the selected callable.
  Invalid reached matcher outcomes, an invalid selected callable, an ineligible
  case carrier, and no successful case signal ordinary Error. Preserve selected
  callable results unchanged, including `null`, and perform no eager validation
  of unreached matchers or callables. Extend the audited native Object boundary
  only for this representation-owned operation and add focal evidence covering
  case order, capture spreading, null preservation, lazy validation,
  case-carrier snapshot mutation, invalid outcomes, selected-callable
  validation, non-Map carrier, and no-match behavior. Specification remains at
  revision `0.1.417`; implementation version becomes `0.3.16-SNAPSHOT`.

## 0.3.15-SNAPSHOT

- Advance `AUD012 — Direct Java guest-execution path audit` (GitHub #541) under
  ratified PLAT035 by migrating the retained-Java Collections execution block
  from direct `ProtosSourceCompiler.compile(...).call(...)` legacy-AST execution
  to the canonical Bytecode-backed `ProtosTestExecutionSupport.evaluate(...)`
  boundary. Migrate Set algebra and mutation coverage, Array algorithm and
  reduce/sort callback-ordering coverage, and Standard Library resolver
  import/caching/error coverage while preserving Java ownership, actor-local
  Activations, native callbacks, assertions, and semantic coverage. No
  Protos-visible semantics, specification, Standard Library behavior, or
  production runtime behavior changes. Implementation version becomes
  `0.3.15-SNAPSHOT`.

## 0.3.14-SNAPSHOT

- Fix checkout/developer execution after implementation-version changes. Repair
  the PERF009 Java slow-test exclusion list after TOML parser reintegration and
  make `bin/protos` select the exact artifact identified by Maven build metadata
  instead of choosing among stale `target/protos-*.jar` files by lexicographic
  filename order. This prevents an older implementation jar from executing
  against newer Core sources when multiple build artifacts remain in `target/`.
  No Protos-visible language, specification, Standard Library, or production
  runtime semantics change. Implementation version becomes
  `0.3.14-SNAPSHOT`.

## 0.3.13-SNAPSHOT

- Advance `PERF009-B — Quarantined Java test normalization and reintegration`
  (GitHub #546) by reintegrating the TOML parser coverage into the ordinary
  Java lane. Replace the quarantined monolithic `ProtosTomlParserStressTest`
  with eight bounded, responsibility-specific regression classes covering
  nested values, flat documents, paths, lexical keys, string/trivia scanning,
  integers, float/temporal tokens, and arrays of tables. Remove
  `ProtosTomlParserStressTest` from `JAVA_SLOW_TEST_EXCLUDES` and delete the
  superseded stress class. Repeated cold-JVM validation satisfies the existing
  `< 2.0 s` hard per-test budget and `< 5 s` class threshold for all eight
  final classes; the Java lane passes and the full test suite passes with zero
  failures. No Protos-visible semantics, specification, Standard Library
  behavior, or production runtime behavior changes. Implementation version
  becomes `0.3.13-SNAPSHOT`.

## 0.3.12-SNAPSHOT

- Advance `AUD012 — Direct Java guest-execution path audit` (GitHub #541) under
  ratified PLAT035 by migrating a second retained-Java semantic-harness block
  from direct `ProtosSourceCompiler.compile(...).call(...)` legacy-AST execution
  to the canonical Bytecode-backed `ProtosTestExecutionSupport.evaluate(...)`
  boundary. Migrate InvalidSuper execution coverage and the CommandLine module,
  specification-model, and result-model harnesses while preserving Java
  ownership, existing Activations, assertions, error signaling, and semantic
  coverage. No Protos-visible semantics, specification, Standard Library
  behavior, or production runtime behavior changes. Implementation version
  becomes `0.3.12-SNAPSHOT`.

## 0.3.11-SNAPSHOT

- Advance `I041-B1 — standard matcher helper publication` (GitHub #550).
  Publish the ratified D131 `Any` and `Capture` helper matchers as ordinary
  frozen standard-prelude objects. Existing Core topology authority makes both
  direct children of `Object`; no `Matcher`, `Pattern`, hidden intermediate
  prototype, second matcher authority, or new matching capability is
  introduced. `Any.match(subject)` returns canonical `true` and
  `Capture.match(subject)` returns a one-element standard Array containing the
  exact subject reference. Add bootstrap/publication evidence for prelude
  visibility, direct-`Object` parentage, freezing, and exact helper behavior.
  Specification remains at revision `0.1.417`; implementation version becomes
  `0.3.11-SNAPSHOT`.

## 0.3.10-SNAPSHOT

- Advance `AUD012 — Direct Java guest-execution path audit` (GitHub #541) under
  ratified PLAT035 by migrating the first bounded retained-Java semantic-harness
  block from direct `ProtosSourceCompiler.compile(...).call(...)` legacy-AST
  execution to the canonical Bytecode-backed
  `ProtosTestExecutionSupport.evaluate(...)` boundary. Migrate the URI, CSV,
  JSON parser stress, Integer math, and Collections Set harnesses while
  preserving Java ownership, existing Activations, Actor-local module
  caching/isolation, assertions, and stress coverage. No Protos-visible
  semantics, specification, Standard Library behavior, or production runtime
  behavior changes. Implementation version becomes `0.3.10-SNAPSHOT`.

## 0.3.9-SNAPSHOT

- Restore `TOOL006 — public Test Tool resource-requirements wiring` (GitHub
  #473). Public `Main.protos` now applies the existing D094/D091
  `resource-requirements.toml` join to complete manifest-backed corpus plans
  before progress counting and D108 scheduling. True sidecar absence preserves
  the resource-free path; valid requirements reach the existing
  binding/reservation/provider machinery. Reconcile the historical I8C guard
  that kept `ResourceRequirements` out of public Main and strengthen I8D5C
  public-cutover evidence for join ordering. TOOL005 non-manifest loaders remain
  outside this bounded restoration. No Protos language/specification, resource
  schema, catalog, provider, reservation, scheduling, reporting or CLI semantics
  change. Implementation version becomes `0.3.9-SNAPSHOT`.

## 0.3.8-SNAPSHOT

- Publish `I040-C — D136 F-prime Map-construction cutover`. Reconcile `%{...}`
  with the owner-approved D136 initial-association model across the normative
  specification, direct AST execution, C-prime continuation execution,
  conformance corpus, and programmer guide. Construction now requires canonical
  standard Map factory behavior selected directly or by inheritance, creates a
  fresh standard Map with the actual factory receiver as parent, defines each
  initial association without `atPut` dispatch, and signals `Error` for a
  duplicate/equal initial key under the normal Map hash/directed-equality query
  law. Later indexed assignment remains ordinary `atPut`. Specification
  revision becomes `0.1.415`; implementation version becomes
  `0.3.8-SNAPSHOT`.

## 0.3.7-SNAPSHOT

- Advance `PERF009-B — Quarantined Java test normalization and reintegration` (GitHub #546) by reintegrating `ProtosTestToolManifestPlanTest` into the ordinary Java lane. Bound the retained Manifest workloads to the minimum scales that preserve their regression properties, replace incidental host-NIO cost in Manifest-policy cases with deterministic read-only test storage, and separate the D133 project-tree responsibility into `ProtosTestToolProjectTreePlanTest` so independently schedulable correctness responsibilities remain independently bounded. Repeated evidence keeps `ProtosTestToolManifestPlanTest` at 3.158–3.344 s per class with a 0.686 s maximum testcase and `ProtosTestToolProjectTreePlanTest` at 1.825–1.878 s with a 0.857 s maximum testcase, satisfying the existing `< 5 s` class quarantine threshold and `< 2.0 s` hard ordinary-test budget. Remove `ProtosTestToolManifestPlanTest` from `JAVA_SLOW_TEST_EXCLUDES`; the class-parallel Java lane passes 1829 tests with zero failures or errors. No Protos-visible semantics, specification, Standard Library behavior, or production runtime behavior changes. Implementation version becomes `0.3.7-SNAPSHOT`.

## 0.3.6-SNAPSHOT

- Publish `I040-B — F-prime C-prime Map-construction foundation`. Add the continuation-capable internal C-prime machinery required to construct through the canonical standard Map factory and define initial associations with normal Map hash and directed-equality semantics without `atPut` dispatch. Duplicate/equal initial keys are rejected rather than replaced, and the original representative association is preserved. This remains an internal foundation slice: production `%{...}` execution is not yet cut over to F-prime semantics. Specification unchanged. Implementation version becomes `0.3.6-SNAPSHOT`.

## 0.3.5-SNAPSHOT

- Publish `I040-A — F-prime Map construction foundation` for D136 reconciliation. Give the canonical standard `Map.call` implementation stable internal provenance so `%{...}` construction can later admit the standard factory selected directly or through inheritance while rejecting copied or nearer arbitrary `call` behavior. Add an internal create-only initial-association operation that reuses normal Map `hash` and directed `==` query semantics, rejects an equal existing initial key instead of replacing it, preserves the first representative key and recorded hash, and bypasses `atPut`. This slice establishes internal runtime machinery only: production `%{...}` execution remains on the previous E-prime path until the coordinated AST/C-prime cutover. Specification unchanged. Implementation version becomes `0.3.5-SNAPSHOT`.

## 0.3.4-SNAPSHOT

- Correct `PERF009-A — Current full-suite baseline and critical-path attribution` (GitHub #531) by routing `ProtosTomlParserStressTest` through the canonical `ProtosTestExecutionSupport.evaluate(...)` execution bridge instead of invoking `ProtosSourceCompiler` directly. This removes the legacy direct-harness execution distortion from the retained TOML stress measurements while preserving the existing test inputs and assertions, allowing PERF009 attribution to measure the ordinary Protos execution path. No Protos-visible semantics, specification, Standard Library behavior, or production runtime path changes. Implementation version becomes `0.3.4-SNAPSHOT`.

## 0.3.3-SNAPSHOT

- Implement `I040 — Map populated construction syntax implementation` (GitHub #543) for ratified D136, including its approved newline/semicolon layout amendment. Add `%{ ... }` as a primary Map construction expression: resolve ordinary shadow-sensitive `Map`, invoke it exactly once with zero arguments before entry evaluation, then evaluate each key and value in the enclosing activation and perform a fresh ordinary `atPut` dispatch before continuing to the next entry. Construction uses newline or `;` entry separators, rejects comma separation and trailing `;`, preserves fail-fast/non-transactional effects and ordinary control propagation, composes with postfix operations, and introduces no construction activation, guest-visible temporary binding, Association value, IdentityMap sugar, spread/merge form, or matching change. Add C-prime and direct-AST backend support, parser/canonical/static-tooling regression coverage, and Protos/Test-Tool conformance for standard construction, evaluated keys, duplicates, postfix use, outer `this`, factory-before-entry order, sequential insertion, and per-entry `atPut` redispatch. Specification revision becomes `0.1.414`; implementation version becomes `0.3.3-SNAPSHOT`.

## 0.3.2-SNAPSHOT

- Implement `I039 — Array construction syntax implementation` (GitHub #539) for ratified D130. Add `[a, b]` as surface-only construction syntax that lowers to the ordinary shadow-sensitive call `Array(a, b)`, reuses existing argument spread and left-to-right evaluation semantics, composes with ordinary postfix indexing and nesting, and leaves matching Array-pattern syntax separate. Add parser/tooling regression coverage plus TOOL002 conformance for empty/nested construction, spread/evaluation order, postfix indexing, and ordinary `Array` shadowing. Specification revision becomes `0.1.413`; implementation version becomes `0.3.2-SNAPSHOT`.

## 0.3.1-SNAPSHOT

- Reconcile `TEST001-I — Superseded test infrastructure cleanup and final closure` (GitHub #467) by removing the abandoned TEST001-C/D/E/F/G semantic-test ownership registry/guard and its publication-validation coupling. Replace the registry-backed `AGENTS.md` rule with a simpler test-placement contract: prefer Protos/TOOL002 for faithfully expressible Protos behavior, retain Java/JUnit for distinct host/runtime/bootstrap evidence, and leave legacy pre-policy Java-to-Protos migration to TEST002/#538. Existing useful Protos conformance cases are retained. Governance/test-infrastructure cleanup only; no specification, runtime semantics, or implementation-version change.

- Establish `GITHUB020 — Formal work closure-evidence and durable-publication contract` (GitHub #536). Require every formal Issue closure to carry a compact evidence-backed closure summary and an explicit durable-record decision, while keeping publication to `protos-project-docs` conditional on whether durable project knowledge would otherwise be lost or an owning work-item contract explicitly requires it. Required durable publication remains fail-closed with exact revision identities; `Protos Development` remains a derived scheduling dashboard rather than evidence authority. Governance only; no specification, executable behavior, runtime architecture, or implementation-version change.

## 0.3.0

- Ratify `D133 — Test Tool per-case CaseAuthority scheduling boundary` (GitHub #519) as Candidate B′. Keep CaseSpec logical/inert, materialize a physical case-scoped CaseAuthority only at scheduling admission, preserve D108 aggregation/progress/cancellation semantics, and keep project-tree authority separate from generic D077/D098 resources. Governance/tooling decision only; no specification or version change.

- Ratify `D132 — Plain two-column Test Tool manifest semantics` (GitHub #506) as Candidate B′. Keep `Manifest.load(...)` strict for the existing three-column schema and add a separate generic semantic loader for explicit two-column outcome manifests, with explicit case namespace and stable logical CaseId independent from physical paths. Governance/tooling decision only; no specification or version change.

- Publish Protos 0.3.0 as a GitHub pre-release, the first public milestone of the optimized Truffle-runtime era following the PERF006 runtime-integrity closure. The release identifies the immutable validated candidate `e98fcc85855c8cefdb0b205804f81a0357aba51c` from development baseline `705d8238af0407104b15ba1d46e628fd2d243581` (`0.3.0-SNAPSHOT`) at Core specification revision `0.1.412`, tagged `v0.3.0`, with portable archive `protos-0.3.0-posix-jvm.zip` (SHA-256 `2cb9dea7091e391b0bb93d6533367914fe580d6fc05669da0be7e1f4a66c5fe7`).

- Ship the DIST002-validated optimizing runtime contract for distribution: GraalVM Community Edition 25.3.4.1 for JDK 25.0.4.1, Truffle 25.3.4.1, and HotSpotTruffleRuntime as the expected optimizing runtime; the portable POSIX/JVM bundle does not include the JDK, and the Java 21 bytecode target is not a generic Java 21+ support claim.

- Include the DIST003-D1 Test Tool stdout-completion correction in the published artifact: the bundled Test Tool executes its complete bundled corpus end to end (1089 passing cases at this candidate) and emits the exact public bootstrap and argument stdout markers verified by the distribution smoke gate.

- Retain the published pre-release limitations: Core v0.1 remains a draft specification; automatic repository CI stays intentionally suspended while TOOL005 (#468) completes the official corpus-execution path and GITHUB017 (#492) owns reactivation (the eight CI toolchain bindings remain `VALID_KNOWN_LIMITATION`, not active-CI PASS); Test Tool hard-timeout, OS-worker recovery and full corpus-path ownership remain deferred; and the bundled Package Tool external immutable-package execution (TOOL001-F2E) remains incomplete and fail-closed. Ordinary development continues on the existing `0.3.0-SNAPSHOT` line; this entry records only the published pre-release evidence and changes no repository development version.
