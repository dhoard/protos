# Protos Futures and Tasks v0.1

Language version: 0.1
Status: Draft
Last updated: 2026-09-04

This document is the primary normative owner of Core Future and task semantics
that have been migrated out of `../PROTOS_LANGUAGE_SPEC.md` and
`docs/design/CONCURRENCY_DESIGN.md`.

During modularization, legacy section numbers are retained where useful so
existing citations remain understandable. Compatibility headings left in the
former monolithic documents are references only and do not define duplicate
normative authority.

## 26. Futures

`Future` is the standard prototype object for Core Future values and delegates
directly to `Object`. Every Future object produced by a Core Future-producing
operation has `Future` as its immediate delegation parent unless that operation
explicitly specifies a different standard parent relation. Future state,
ownership, producer kind, cancellation, or distribution never inserts another
Protos-visible ancestor.

There are no `async` functions and no `await` keyword.

An ordinary function may simply return a Future.

Returning a Future from an ordinary synchronous invocation does not itself create,
close, transfer, re-parent, or wait a structured-concurrency ownership
scope. Structured ownership is task-scoped as defined in §24: synchronous Closure
and method activations executing within one asynchronous computation remain in
that computation's current structured execution scope. A pending Future can
therefore cross an ordinary synchronous return immediately while its task-backed
producer, when any, remains owned by the same surrounding structured execution
scope.

### Future result identity

Unless the normative contract of a Core-standard operation expressly says that
its Future result is an already-existing Future object, every successfully
dispatched invocation that produces a Future as its operation result produces a
fresh standard Future identity belonging to that invocation. Futures produced
by distinct such invocations are therefore distinct under `===` and `!==`, have
their own identity as observed by `identityHashOf` and `IdentityMap`, and do not
become the same Future merely because they have the same producer, arguments,
logical operation, immediately known outcome, or eventual terminal outcome.

This rule applies equally when the produced Future can be terminal immediately.
An implementation must not reuse a cached resolved, failed, or cancelled Future
as the result identity of distinct invocations, and it must not share one pending
result Future between distinct invocations. Consequently, cancellation,
continuation attachment, waiting, or other Future-local observation of one such
result cannot act on another invocation merely through accidental Future
aliasing.

Fresh Future identity is distinct from the identity of the eventual outcome.
Resolution and adoption retain the value/Error identity rules specified below;
creating distinct result Futures does not by itself require copying, cloning, or
freshening the values or Errors that those Futures eventually observe.

The rule does not apply when an operation's normative contract expressly returns
a pre-existing Future, including a Future receiver or another Future supplied or
obtained under that contract. Such an operation preserves the identity required
by its own result rule rather than manufacturing a replacement Future merely
because the result happens to be a Future.

Freshness is semantic, not an allocation prescription. Implementations may use
scalar replacement, allocation elision, virtualization, pooling, shared immutable
backing, canonical terminal-state representation, cached completion metadata, or
other internal sharing provided all Protos-observable Future identities and
Future-local effects remain exactly as specified.

## 27. Asynchronous Execution

A closure may request asynchronous execution:

```js
work: () => {
    calculate()
}

resultFuture: work.future()
```

`future()` is a visible message whose fundamental implementation reaches a runtime primitive.

### Ordinary ownership of `future`

The standard `future` selector is the ordinary local Closure-valued
`Object.future` slot specified by `../semantics/CALLABLES.md`. Ordinary lookup,
reflection, extraction, shadowing/override behavior, and the semantic-Closure
receiver-domain check are owned there. In particular, merely finding the
inherited selector does not make a non-Closure receiver a Closure; if the
standard behavior is selected for such a receiver, its invocation fails under
that receiver-domain contract before this section creates or schedules work.

This section owns the behavior after that standard receiver-domain validation:
a valid Closure receiver requests the asynchronous execution described below and
returns the corresponding Future. Reaching scheduler/runtime primitives is
implementation machinery and does not replace the ordinary `Object.future` slot
or its observable lookup behavior.

Conceptually:

```text
closure
   ↓
create pending Future
   ↓
create/schedule closure execution
   ↓
return Future immediately
```

The same closure may execute synchronously:

```js
result: work()
```

or asynchronously:

```js
future: work.future()
```

Asynchrony belongs to **the execution of a closure**, not to its definition.

### Cancellation of asynchronous execution

Cancellation of Future-producing asynchronous work is a concurrency-domain
facility. The normative semantics of `Future.cancel()`, cooperative cancellation,
portable observation boundaries, cancellation-runnable suspended/pre-start work,
resume precedence, cleanup, and producer-specific commitment boundaries are owned
by `FUTURES_AND_TASKS.md` §23 and the structured-unwind rules of §24.

The language-level fact that `closure.future()` requests asynchronous execution
through an ordinary message is unchanged. No additional cancellation syntax,
asynchronous exception mechanism, or hidden implementation-selected checkpoint is
introduced by this language specification.

## 28. Future State, Resolution, Failure, and Adoption

This section is the primary normative owner of the Core v0.1 Future
state/resolution model.

A Future has exactly one of four states:

```text
pending
resolved(value)
failed(error)
cancelled
```

`pending` is the only non-terminal state. `resolved`, `failed`, and `cancelled`
are terminal. The first terminal transition is stable: later producer,
completion, cancellation, or adoption bookkeeping cannot rewrite the Future's
terminal outcome.

Normal completion with an ordinary non-Future value resolves the Future with
that value. Unhandled asynchronous failure fails the Future with the applicable
`Error`; there is no separate promise-rejection mechanism.

### Domain-local failure preserves Error identity

When a Future becomes failed with an `Error` already belonging to the same
Protos value/isolation domain as that Future, the Future records that exact Error
object. Failure recording does not clone, wrap, snapshot, reconstruct, or
otherwise substitute it.

Consequently, later observations that re-signal the stored failure use that same
domain-local Error object. This identity rule does not preserve producer control
state, dynamic handlers, activation frames, continuations, return homes, or
resumption authority.

Boundary-specific transformation happens before failure is recorded in the
receiving Future. In particular, P first transfers/reconstructs an Error into the
caller domain and then fails the caller Future with that transferred object.
Actor-fatal Errors do not implicitly cross Actor boundaries.

### Future resolution adopts Future outcomes

When Future resolution is given another Future instead of an ordinary value, the
destination adopts the source Future's eventual terminal outcome rather than
resolving to a nested Future.

While the adopted source is pending, the destination remains `pending`.
Adoption bookkeeping is not a fifth Future state and is not a language-visible
slot or object category.

Adoption mirrors the source terminal outcome exactly:

```text
source resolved(value) -> destination resolved(value)
source failed(error)    -> destination failed(error)
source cancelled        -> destination cancelled
```

Resolution uses the same resolved value object. Failure uses the same Error
object when both Futures are already in the same value/isolation domain, subject
to the boundary rule above. Propagating an adopted terminal outcome does not
invoke ordinary Protos transformation or handler code merely to perform that
propagation.

Adoption transfers only eventual outcome. It does not transfer Future identity,
task identity, or structured ownership. It creates no upstream
cancellation: cancelling an adopting destination never requests cancellation of
the adopted source and never changes that source's ownership.

Adoption is a cancellation-aware pending operation under the cancellation rules
owned by `FUTURES_AND_TASKS.md` §23. If destination cancellation and
adopted-source completion race, the first terminal transition of the destination
wins; later bookkeeping has no effect.

Future-adoption cycles are invalid. Direct self-adoption and any transitive
pending adoption that would make the destination reachable from the source's
adoption chain fail the destination with a fresh standard
`FutureResolutionCycle` failure occurrence. `FutureResolutionCycle` delegates
directly to `Error`; creation and identity of the Error instance are owned by
`../semantics/ERRORS.md`. A cycle must not remain implementation-dependently
pending.

Implementations may represent adoption with callbacks, dependency nodes, waiter
lists, graph edges, or another mechanism. Such bookkeeping must not execute
ordinary Protos code inline merely because the source Future becomes terminal.

## 29. Obtaining a Future's Value

This section is the primary normative owner of the Core v0.1
`Future.value()` observation semantics.

```text
future.value()
```

observes the Future's stable state defined in §28.

For a resolved Future, `value()` returns the resolved value immediately.

For a failed Future, `value()` signals the exact stored domain-local `Error`
object in the consumer's then-current dynamic handler context. This is a new
non-resumable signaling event at the consumer observation point. It is not a
continuation of the producer's original signaling operation and carries no
producer stack frame, handler frame, continuation token, return home, or other
resumption authority.

If a consumer handler handles that re-signaled Error, the handler result belongs
only to the consumer-side handling boundary. It cannot resume, retry, or inject a
value into the producer computation that originally failed. Repeated observations
of the same failed Future are repeated consumer-side signaling events and
re-signal the same stored Error object, subject to the value-domain boundary
rules of §28.

### Cancelled Future observation

`Cancelled` is a standard Error prototype and standard-prelude binding delegating
directly to `Error`.

A cancelled Future stores only the `cancelled` terminal state; cancellation does
not store a `Cancelled` Error object in the Future.

Each call to `value()` that observes a cancelled Future creates a **fresh standard
failure occurrence** whose Error instance delegates directly to `Cancelled`, then
signals that fresh instance in the consumer's then-current dynamic handler
context under `../semantics/ERRORS.md`. The `Cancelled` prototype itself is never
implicitly signaled by cancellation observation.

Consequently, two distinct observations of the same cancelled Future receive
distinct Error identities, while each individual handler receives the exact
fresh Error object created for that observation. Each observation is a new
non-resumable signaling event; it does not resume or recreate the cancelled
producer computation.

This cancellation-observation Error is distinct from the Future's `cancelled`
terminal state: the state is stored on the Future, while the fresh `Cancelled`
instance exists only as the Error outcome of that particular `value()`
observation unless ordinary program references keep it reachable.

### Pending Future observation and lost-wakeup exclusion

For a pending Future, `value()` explicitly suspends the current execution until
that Future becomes terminal or the waiting task observes its own cancellation.
Suspension does not require blocking an operating-system thread.

The decision to suspend and registration of the waiting continuation are
semantically atomic with respect to the Future's first terminal transition.
There is no interval in which `value()` has committed to waiting because it saw
`pending` while the Future can become terminal without either observing that
waiter or causing the attempted suspension to observe the terminal state.

Equivalently, every conforming implementation must ensure one of these outcomes:

```text
Future terminal transition happens first
    -> the consumer does not remain suspended on that Future

waiter registration happens first
    -> that terminal transition makes the registered consumer eligible to resume
```

When a Future makes its first terminal transition, every still-live continuation
registered as waiting on that Future becomes eligible to resume. One waiter must
not be skipped merely because another waiter is also registered. Terminalization
must clear or make inert the pending waiter registrations so a terminal Future
does not retain suspended continuations indefinitely.

A waiter becoming eligible does not imply immediate execution and does not create
a global ordering among independent runnable work. Implementations may use locks,
CAS, generation counters, register-then-recheck, callbacks, waiter nodes, queues,
condition variables, or another mechanism. Spurious internal wake-ups are
permitted only when semantically invisible; lost terminal notifications and
duplicate semantic resumptions are not.

### Interaction with waiting-task cancellation

Calling `value()` is an explicit suspension point, so a task-backed consumer is
subject to the cooperative cancellation semantics owned by
`FUTURES_AND_TASKS.md` §23.

If cancellation of the waiting task is already pending at the suspension
boundary, cancellation is honored instead of installing a live waiter. If the
task is already suspended and cancellation is requested later, the task becomes
cancellation-runnable without waiting for the observed Future to complete.

Before a suspended task resumes ordinary Protos code or receives a successful
`value()` result, the ordinary resume cancellation boundary applies. Therefore,
if the observed Future becomes terminal and the waiting task also has cancellation
pending at that resume boundary, cancellation of the waiting task wins for that
consumer. The observed Future retains its own terminal outcome unchanged.

Cancelling one waiting task never calls `cancel()` on the observed Future and
does not affect other waiters on that Future.

A non-task-backed execution context that is otherwise permitted to suspend may
also call `value()`. It is not manufactured into a hidden task-backed Future
merely to wait; it waits under its enclosing lifecycle and has no task
cancellation flag to consult.

### Suspendible host-initiated RootActor entries

A host-initiated entry is a synchronous invocation of Protos code by the
embedding host from outside any Protos execution, such as invoking an extracted
Protos Closure through the standard Polyglot execute operation of a Context
whose Process is live (`../io/PROCESS_IO.md`, Standard Polyglot embedding
bootstrap and authority). It is not a callback invoked by a synchronous foreign
operation within the dynamic extent of Protos code; that case remains governed
exclusively by "Synchronous foreign callbacks and the current Task" below.

Such an entry executes in the RootActor as a synchronous RootActor entry
(`ACTORS.md` §24C) and is a non-task-backed execution context permitted to
suspend at explicit suspension points, including `value()` on a pending Future.
The following rules apply:

1. From the host's perspective the entry remains synchronous: it returns only
   the entry's final result, or propagates only its final failure, after the
   guest computation has completed or terminated.
2. A suspension preserves the guest continuation and resumes it at the
   suspension point. Resumption never re-evaluates expressions that completed
   before the suspension point and never duplicates their effects.
3. The absence of a task does not by itself prevent an explicit suspension.
   The pending-observation, lost-wakeup, and eligibility rules of this section
   apply unchanged.
4. Permission to suspend imposes no cost on entries that do not suspend. An
   entry is not required to allocate a task, root task, scheduler, waiter,
   rich activation, or suspension machinery merely because it could suspend.
5. A suspended entry waits under the lifecycle of its Process and of the
   embedding Context. It has no task cancellation flag. Process termination,
   including termination caused by closing the Context, ends the wait: the
   suspended continuation is never resumed and no guest code executes after
   the terminal boundary (`../io/PROCESS_IO.md` §28). The host observes the
   entry as terminated rather than as a successful result.
6. Suspension of the entry neither cancels nor otherwise affects the observed
   Future, whose terminal outcome remains governed by §28; other waiters are
   unaffected.
7. While the entry is suspended, the RootActor's Actor-local exclusivity is
   preserved: no other Protos code executes concurrently in the RootActor's
   mutable domain. Whether a further host entry may run in the RootActor while
   an earlier one is suspended follows the unsafe-concurrent-entry rule of
   `../io/PROCESS_IO.md`; this section grants no additional interleaving.
   Other Actors and isolated parallel work continue to make independent
   progress as their own rules permit, including work that terminalizes the
   observed Future.
8. An Error that escapes the entry's outermost dynamic handler boundary,
   including one signaled by a resumed observation, follows the fatal RootActor
   rule of `ACTORS.md` §24C and §32.

This section standardizes no host thread, carrier, lock, waiter structure,
task representation, or continuation mechanism; an implementation may block
the calling host thread, park a continuation, or use any other mechanism that
preserves these observations.

## 30. Future Composition

Core v0.1 standardizes the ordinary Future transformation operation:

```text
future.then(transform)
    -> Future
```

`then` is an ordinary message on a Future value and introduces no new syntax or
executable value kind. Its normative continuation-task ownership, execution
domain, scheduling, source-outcome propagation, flattening, cancellation, and
ordering semantics are owned by
`FUTURES_AND_TASKS.md` under `Future then() continuations`.

Automatic flattening uses the Future resolution/adoption semantics owned by
this specification in §28. The concurrency model owns the continuation task's
execution, ownership, cancellation, and scheduling consequences.
The `transform` value is not Closure-only. It must be ordinarily invokable under
the callability-inspection protocol owned by `../semantics/CALLABLES.md`; no
hidden callback protocol or Closure-identity shortcut is introduced.

### Waiting for multiple Futures

Core v0.1 standardizes the ordinary Future coordination operation:

```text
Future.all(futures...)
    -> Future
```

`Future.all` is an ordinary message on the standard Future prototype and
introduces no new syntax, Task kind, or wait-set object. Its normative argument
validation, aggregate identity/kind, deterministic terminal selection, result
ordering, source ownership, cancellation, failure propagation, and empty-input
semantics are owned by `FUTURES_AND_TASKS.md` §24E.

General argument-evaluation, Array, Error-object identity, and ordinary message
rules from this language specification continue to apply where referenced by
that owning contract.

### No generic Future race/select in Core

Core v0.1 defines no standard `Future.race(...)`, `Future.select(...)`, or
equivalent first-completion combinator.

Independent Future terminalizations do not acquire a portable global total order
merely so a race API can pick a winner. Core therefore does not expose scheduler,
callback, carrier, I/O-backend, polling, or host completion timing as a semantic
selection rule.

`Future.all(...)` is the standard deterministic multi-Future coordination
operation. A future wait-any/select facility must define an independent semantic
priority or event-ordering contract rather than deriving meaning from
implementation-selected completion timing.

### Non-local return and Actor boundaries

A non-local return (`^`) never targets an activation in another Actor.

Actor communication transfers values, not execution contexts, continuations, or
return-home authority. Destination Actor code can unwind only through return
homes belonging to its own execution domain. `send()` and `request()` do not
become hidden control-transfer channels merely because destination code executes
`^`.

Actor replacement does not preserve or recreate return homes from the terminated
incarnation.

## 31. Structured Concurrency

Structured ownership of Future-producing child work is a concurrency-domain
facility. Its normative ownership, normal-exit waiting, error/cancellation
unwind, cleanup, Actor-local lifetime, and non-task-backed Future
semantics are owned by `FUTURES_AND_TASKS.md` §24.

The cooperative non-preemption rule for ordinary Actor-local
`closure.future()` work is owned by `ACTORS.md` §24D.

The exact scheduler representation remains implementation machinery.

# Concurrency-domain Future and task contracts

The following contracts were previously embedded in the mixed concurrency design
ledger. Their CLOSED status and observable semantics are preserved; this document
is now their primary normative location.

### Future `then()` continuations

This section is the primary normative owner of the Core v0.1
`Future.then(transform) -> Future` concurrency-domain semantics.

After ordinary receiver and argument-expression evaluation completes,
`future.then(transform)` validates the exact resulting `transform` value as
ordinarily invokable using the read-only callability-inspection protocol owned by
`../semantics/CALLABLES.md`. Validation does not invoke `transform`.

If that validation fails, the call immediately signals the ordinary
non-invokable-argument Error for this API. No continuation task or destination
Future is created, registered, scheduled, or otherwise made observable. This
validation is eager and independent of the source Future's state: a pending,
resolved, failed, or cancelled source does not defer, suppress, or replace it.

Only after successful validation does the call create a distinct continuation
task and a destination Future. The continuation is asynchronous child work
created in the current task-scoped structured execution scope, and therefore
belongs to that scope under the ordinary structured-concurrency rule. An
ordinary synchronous activation that merely
calls `then()` does not become a new structured owner solely because that call
occurred inside it.

Completion of the source Future only makes the continuation runnable. It does not
execute `transform` inline, reentrantly, or inside the task or Actor turn that
completes the source Future. This remains true when the source Future was already
terminal when `then()` was called.

The continuation executes as ordinary task work in the Actor/execution domain of
the activation that created it. For a resolved source, it invokes `transform`
through ordinary polymorphic parenthesized invocation with the source value as
the sole argument. That later invocation performs a fresh ordinary `call` lookup
under `CALLABLES.md`; eager validation does not pin or cache the Closure selected
during inspection, so intervening ordinary mutation or shadowing remains
observable under the general invocation rules.

A failed source fails the destination with the same error without invoking
`transform`; a cancelled source cancels the destination without invoking
`transform`. A Future returned by `transform` is flattened through the Future
resolution/adoption semantics owned by `FUTURES_AND_TASKS.md` §28.

That generic adoption contract owns outcome mirroring, cycle failure,
destination/source identity separation, first-terminal-transition stability, and
the absence of upstream cancellation or ownership transfer during adoption.

Cancellation is downstream-only for this continuation edge:
cancelling the destination requests cancellation of the continuation but does not
cancel the source Future. It does not change the ownership or lifetime of the
source Future.

The first execution of every newly created asynchronous task is a portable
cancellation-observation boundary before any ordinary Protos code in that task
executes. A cancellation request for a not-yet-started task must make that task
eligible to reach this boundary even when the task was waiting for a semantic
prerequisite that has not become ready. Cancellation does not satisfy, complete,
or otherwise modify that prerequisite.

Therefore, if the destination of `then()` is cancelled while its continuation is
waiting for a still-pending source Future, the continuation becomes
cancellation-runnable without waiting for the source. The request is honored
before inspecting the source outcome or invoking `transform`; the destination
becomes cancelled and `transform` is not invoked. The source Future remains
unchanged.

The same rule applies when the source had already completed but the continuation
had not yet begun its first turn. This prevents scheduler or source-completion
timing from deciding whether a never-started continuation nevertheless performs
ordinary Protos side effects.

Once a task has begun ordinary Protos execution, no additional cancellation
boundary is implied merely by method calls, closure invocation, allocations, loop
back-edges, or other non-suspending execution. The existing explicit-suspension,
resume, and cancellation-aware-operation boundaries remain unchanged.

No additional total ordering is introduced between independent continuations.

## 23. Cancellation

**CLOSED --- REVISED**

This section is the primary normative owner of Core v0.1 cooperative
cancellation semantics, including `Future.cancel()` and portable cancellation
observation/wake-up boundaries unless a more specific domain contract explicitly
specializes an operation's commitment/effect behavior.

Cancellation is cooperative.

Protos does not arbitrarily interrupt Actor code in the middle of an
instruction by injecting asynchronous exceptions.

Cancellation requests are observed at portable cancellation boundaries,
not at implementation-selected runtime safepoints.

Every explicit suspension point is a mandatory cancellation observation boundary.
A pending request is observed before the task suspends or, if suspension has
already occurred, before that task resumes ordinary Protos execution. An operation
whose normative contract is cancellation-aware may additionally observe a request
while its underlying work is pending, subject to that operation's commitment and
effect rules.

A cancellation request made while a task is already suspended must make that
task eligible to resume for cancellation without waiting for the condition that
originally suspended it to complete. Otherwise structured cancellation could
wait forever for a child suspended on a Future, timer, I/O operation, or other
condition that never becomes ready.

Likewise, cancellation of a task that has been created but has not yet begun its
first ordinary Protos instruction must not wait indefinitely for an unmet
pre-start scheduling prerequisite. The task becomes eligible to reach its
mandatory first-execution cancellation boundary without that prerequisite being
made true. Making the task cancellation-runnable is idempotent and does not
authorize duplicate execution, preemption of currently executing ordinary code,
or satisfaction/cancellation of an unrelated prerequisite or upstream Future.

This cancellation wake-up resumes only the waiting task's control flow. It does
not by itself cancel, fail, complete, or otherwise modify the Future or other
condition being awaited, and it does not request cancellation of an upstream
producer. Multiple tasks may wait on the same Future without cancellation of one
waiter affecting the others or the Future itself.

The existing resume boundary remains authoritative. If a task was suspended and
its cancellation request is pending when it is selected to resume into Protos,
cancellation is honored before the suspended operation can return a successful
result or execute further ordinary Protos code. Thus, if awaited completion made
the task runnable but cancellation became pending before that resume boundary,
the consumer observes cancellation; the awaited Future retains its own terminal
outcome unchanged. If the task already crossed the resume boundary and completed
the suspension operation before cancellation was requested, that completed
observation is not retroactively rewritten.

This is a prompt cancellation guarantee for the suspended consumer, not upstream
cancellation propagation. Producer-specific cancellation remains explicit
through that producer's own Future or capability contract.

Ordinary non-suspending execution does not acquire hidden cancellation points from
method calls, allocations, loop back-edges, interpreter/JIT polls, garbage
collection, host calls, or similar runtime machinery. CPU-bound Protos code that
does not explicitly suspend or enter a cancellation-aware operation may therefore
complete normally after cancellation was requested.

An implementation may use additional internal polling or carrier interruption only
when it cannot change the Protos-observable point at which cancellation takes
effect.

Cancelling a Future requests cancellation of its work.

`Future.cancel()` is an idempotent request operation and returns the same Future
object on every call. If the Future is already terminal, `cancel()` is a no-op
with respect to Future state and outcome and still returns that Future. For a
pending Future, returning normally means only that the cancellation request was
recorded; it does not assert that cancellation has already taken effect or that
the eventual terminal state must be `cancelled`.

Cancelling a SendOperation attempts to prevent further delivery while
that remains safe.

Before concrete-Actor acceptance, cancellation may remove or stop the
operation where the runtime can still do so safely.

If the destination has already accepted the message, cancellation cannot
unsend it.

If the runtime cannot determine whether acceptance occurred,
cancellation may end in an uncertain delivery state rather than assuming
that the message was cancelled before delivery. For `request()`, whose public result
is an ordinary Future and therefore has no uncertain terminal state, this outcome
is represented by failure with the standard `RequestOutcomeUncertain` error.

Principle:

> Cancellation never reverses effects that have already occurred.

## 24. Structured Concurrency

**CLOSED --- REVISED**

This section is the primary normative owner of Core v0.1 structured ownership
semantics for Future-producing child work, including structured lifetime,
cancellation unwind, and cleanup.

The existing structured-concurrency semantics for Futures remain.

Structured ownership is scoped to asynchronous task execution, not to every
ordinary synchronous invocation frame. Each task-backed asynchronous computation
has one structured execution scope. Ordinary Closure calls, method calls,
`whileTrue`/`while` condition/body activations, `ensure` body/cleanup activations, and other
synchronous nested invocations performed by that computation execute inside the
same current structured execution scope unless they themselves start a distinct
asynchronous computation. Core exposes no public Task or scope object merely to
represent this relationship.

Task-backed asynchronous child work created while a structured execution scope is
current is owned by that scope. Starting a
distinct child task creates a new structured execution scope for work that child
itself later creates, so the ownership relation remains recursively structured
without making synchronous call-stack depth part of the concurrency tree.

An ordinary synchronous invocation can therefore return while task-backed work it
created remains pending. Returning the Future itself, storing it, wrapping it in
another ordinary value, or otherwise letting it escape that invocation does not
transfer, re-parent, duplicate, or remove its ownership edge. No escape
analysis or result-shape inspection participates in ownership. The edge remains
attached to the same surrounding structured execution scope until the child is
terminal.

Structured ownership bounds child lifetime but does not implicitly observe child
results. When the owning asynchronous computation itself reaches otherwise normal
terminal completion, its structured execution scope waits for every
child to become terminal. A child's failed or cancelled terminal state does not by
itself fail or cancel that normally completing owner. Failure or cancellation
becomes observable to owner code only through the ordinary Future observation
operations, such as `value()`.

This deliberately avoids hidden "unobserved failure" or "failure consumed" state:
whether an owner completes normally cannot depend on whether some previous read of
a child Future happened to observe its failure. The Future remains an ordinary
eventual-result object whose terminal outcome is stable and may be observed more
than once.

Cancellation unwind includes the task's applicable `ensure` cleanup.
Once a cancellation request has been honored and has begun that unwind, the same
request is not re-delivered at suspension boundaries reached by cleanup for that
unwind. This permits resource cleanup to suspend without being immediately
cancelled by the cancellation it is already handling. If cleanup fails, that
failure replaces the cancellation transfer and the task fails; if cleanup
completes, cancellation continues and the Future becomes cancelled only after
cleanup is complete. This rule does not create a general user-visible
cancellation-mask facility.

Core exposes no `Future.detach()` operation. No Core operation removes,
transfers, or re-parents a task-backed child's structured-ownership edge while
the child remains nonterminal.

A non-task-backed Future, including one produced directly by an I/O facility,
has no structured task ownership edge; its lifetime and cancellation follow its
producer's own contract.

An Actor-local task may outlive the synchronous activation that created it,
but it cannot outlive the Actor
incarnation whose mutable state and serial execution domain it uses.

When termination of an Actor incarnation begins while its hosting runtime remains
able to execute Protos cleanup, every pending Actor-local task belonging to that
incarnation receives a cooperative cancellation request.

Termination also records a cancellation request on every still-pending
non-task-backed Future representing an asynchronous operation initiated by that
Actor incarnation. This includes, when applicable, I/O-operation Futures and
communication-operation Futures such as `request()`. Actor termination is
therefore a cancellation-request boundary for outstanding Actor-originated
asynchronous operations; it is not permission for such operations to continue
or disappear according to implementation accident.

This Actor-lifecycle request does not invent stronger cancellation than the
producer already provides. An operation that can still satisfy its ordinary
cancellation contract may become cancelled. An operation that has already crossed
its commitment/acceptance boundary keeps the effects and outcome constraints of
its own protocol; Actor termination cannot roll those effects back, convert them
into pre-commit cancellation, or transparently retry them.

The incarnation stops accepting or dispatching new ordinary message work. Task
execution that occurs thereafter is limited to reaching the existing portable
cancellation boundaries and performing the cancellation unwind and applicable
`ensure` cleanup required to terminate those tasks.

A task is never silently re-parented to the RootActor, Process, a
replacement Actor, or another runtime scope merely so it can continue running
after its Actor terminates. Its Future follows the ordinary cancellation-unwind
rule: successful cleanup permits the Future to become `cancelled`; a cleanup
failure makes that Future `failed` with the cleanup error. Replacement creates a
fresh Actor execution domain and inherits none of these tasks.

Actor termination waits for the required cancellation unwind of Actor-local
tasks, but does not generally wait for every non-task-backed producer Future to
become terminal. Once the cancellation request has been recorded, any residual
producer/backend work that cannot yet terminate safely remains under
runtime/producer custody and may proceed only as allowed by that operation's
existing commitment and cancellation rules. No continuation of the terminated
Actor is resumed merely to observe such a later outcome.

Termination cleanup may itself suspend and therefore has no bounded-time liveness
guarantee. Catastrophic loss of the Process, runtime, or underlying execution
capacity may make further Protos cleanup impossible; this does not authorize the
task to continue in another Actor or execution domain. The portable guarantee
here governs semantic Actor termination while the runtime remains capable of
executing the required cleanup.

When an explicit isolated parallel operation creates work whose result
is represented by a Future, that work participates in the same
structured ownership, waiting, failure, and cooperative-cancellation
model unless an API explicitly provides different ownership semantics.
Its isolated execution does not turn it into an Actor and does not give
it Actor identity, a mailbox, independent supervision, or independent
lifecycle semantics.

Actor creation does not automatically establish the same ownership
relationship.

Structured concurrency therefore governs Future-producing child work,
including isolated parallel computations, but not Actor lifetime in
general.

## 24E. Waiting for Multiple Futures with `Future.all(...)`

**CLOSED**

This section is the primary normative owner of the Core v0.1
`Future.all(futures...) -> Future` concurrency-domain semantics.

Core v0.1 standardizes the ordinary Future-protocol operation:

```text
Future.all(futures...)
    -> Future
```

`Future` here denotes the standard Future prototype object; `all` is an ordinary
message on that object and introduces no new syntax, Task kind, wait-set object,
or scheduler identity.

After ordinary argument evaluation left-to-right, every supplied argument must
be a Future value in the current execution domain. Validation is synchronous in
ascending argument-index order. The first non-Future argument signals an `Error`
and no aggregate Future is created.

The returned aggregate is a fresh non-task-backed Future representing only this
multi-Future observation. It does not own, re-parent, cancel, or otherwise
change any source Future or its producer.

For zero arguments:

```text
Future.all()
```

returns an already-resolved Future whose value is a fresh empty standard Array.

For one or more source Futures, let their argument positions be `0 .. n-1`.
The aggregate observes each source's stable terminal outcome and applies one
deterministic ascending-index frontier.

For each source position, the logical outcomes are:

```text
resolved(value)
failed(error)
cancelled
```

A resolved source contributes its resolved value at the same index of the final
result Array.

The aggregate may resolve successfully only when every source Future is resolved.
Its value is then one fresh standard Array:

```text
[result0, result1, ..., resultN]
```

with exactly the source-argument order, independent of source completion order.

Failure/cancellation selection is also argument-order deterministic. The
aggregate becomes terminal at the lowest source index whose outcome is not
`resolved`, but only after every lower index is known to be `resolved`.

Therefore:

- a failed source at index `i` fails the aggregate with that same Error only when
  every source `0 .. i-1` is resolved;
- a cancelled source at index `i` cancels the aggregate only when every source
  `0 .. i-1` is resolved;
- a later failure/cancellation cannot overtake an unresolved lower index;
- if a lower source later fails/cancels, that lower outcome wins;
- physical completion order, callback scheduling, carrier choice, or registration
  order cannot select the aggregate terminal outcome.

This ordered frontier intentionally differs from completion-race semantics.
`Future.all(...)` is the deterministic wait-for-all combinator; first-completion
selection remains a separate `select`/`race` design topic.

Cancelling the aggregate Future abandons only the aggregate observation. It does
not request cancellation of any source Future, does not alter any source terminal
state, and does not propagate upstream cancellation. Once aggregate cancellation
wins its ordinary first-terminal transition, later source completions are ignored
by that aggregate.

Repeated source Future identity is allowed. Each argument position is one logical
observation slot. If the same Future appears more than once and resolves, its same
resolved value occupies each corresponding result position; no source is
duplicated or re-executed.

The aggregate itself has no structured task-ownership edge.

A pending aggregate must not retain arbitrary completed-source implementation
state beyond what is necessary to produce the specified result or deterministic
frontier decision. Once the aggregate becomes terminal or its cancellation is
honored, source registrations must be removed or made inert so long-lived source
Futures cannot retain dead aggregate observation state without bound.

The implementation may realize the operation with callbacks, compact waiter
registrations, bitmaps, counters, continuation records, polling already-terminal
state, or another mechanism. Such choices are non-observable provided the
ordered result, terminal-outcome selection, cancellation isolation, and
registration-lifetime rules above are preserved.

## 24F. No Generic Future Race/Select in Core

**CLOSED**

Core v0.1 does not standardize a generic `Future.race(...)`,
`Future.select(...)`, wait-any object, or another Future combinator whose result
is chosen by whichever independent source Future happens to become terminal
"first".

This is a semantic boundary, not an implementation omission.

Independent Future producers may become terminal because of Actor scheduling,
P scheduling, I/O completion, communication, cancellation, host events, or other
concurrent causes. Core deliberately does not define one global portable clock or
total event order across such independent terminal transitions.

Therefore a generic first-completion API would have to choose one of two
undesirable semantics:

- expose implementation observation order, callback order, carrier timing,
  kernel completion order, or scheduler timing as a language-visible winner; or
- impose an unrelated fixed priority such as argument index and call that
  priority a "race", even when another source actually became terminal earlier.

Core v0.1 does neither.

In particular, implementations must not add a standard-looking race/select
operation whose winner may differ solely because of thread scheduling, callback
registration order, polling cadence, work stealing, I/O backend choice, or other
non-semantic machinery.

`Future.all(...)` remains the standardized deterministic multi-Future
coordination primitive. It preserves argument order and deterministic
failure/cancellation selection without exposing completion timing.

Libraries and future standard facilities may define selection when they have an
independent semantic ordering rule. Examples include an explicit caller-supplied
priority, a protocol-defined message/order position, a timer/clock contract, or
another domain-specific event ordering. Such a facility must specify:

- what events are eligible;
- the exact selection/priority rule;
- behavior when several eligible outcomes are already available;
- behavior for semantically unordered concurrent events;
- result/failure/cancellation representation;
- whether losing operations continue, are merely unobserved, or receive explicit
  cancellation requests;
- registration/removal lifetime and resource bounds.

A future facility may use ordinary Futures internally, but Future terminalization
alone does not manufacture a portable total order between independent producers.

This closure preserves scheduler independence: concurrency may affect when an
answer becomes available, but generic Core Future coordination does not expose
implementation-selected timing as which answer is observed.
## 24G. Future Ownership and Actor Lifecycle Matrix

**CLOSED**

The interaction between Future ownership and Actor lifecycle is fully determined
in Core v0.1 by the existing structured-concurrency and Actor-termination rules.
It is not an implementation-selectable policy.

The normative cases are:

```text
Actor-local task-backed Future
    -> producing task belongs to that Actor execution domain
    -> Actor termination requests cooperative cancellation
    -> Actor waits for required task cancellation unwind/ensure cleanup
    -> task cannot continue as ordinary Protos work after Actor termination

Actor-originated non-task-backed Future
    -> no structured task ownership edge exists
    -> Actor termination records a cancellation request on the pending operation
    -> producer-specific commitment/cancellation semantics remain authoritative
    -> Actor generally does not wait for backend work that must safely continue
    -> no later continuation may resume ordinary code in the terminated Actor

P-result Future created from Actor-local code
    -> structured ownership follows the creating activation
    -> P execution remains isolated from Actor mutable state
    -> Actor termination requests cancellation through the existing structured
       ownership/lifecycle rules
    -> P work does not gain an independent Actor-like lifetime

pure observation Future with no Actor-originated producer ownership
    -> follows the explicit contract of that observation facility
    -> merely holding the Future does not extend Actor lifetime
```

A Future object surviving as an ordinary value after its originating Actor has
terminated does not keep that Actor incarnation alive, resurrect it, or authorize
continuation execution inside its former mutable domain. The Future may still
carry a stable terminal outcome or, for producer/backend work whose contract
allows it, may later become terminal under runtime/producer custody.

Likewise, Actor replacement does not inherit pending tasks, continuation state,
Future producer ownership, or Actor-local mutable execution from the terminated
incarnation. A replacement is a fresh Actor domain.

Core defines no hidden "orphan Future" policy that may choose among continuing,
cancelling, re-parenting, abandoning, or migrating Actor-local work according to
runtime convenience. Each case above follows its already-defined ownership and
producer contract.

This section is a consolidation of existing normative rules, not a new lifetime
mechanism. It closes the former open ledger item `Future ownership interaction
with Actor lifecycle`.

# Actor-turn integration migrated from legacy §5

### Dynamic error handlers and task suspension

Dynamic error-handler frames are local to the task whose execution installed
them. They are not Actor-global mutable state.

If a task suspends at an explicit suspension point while a protected handler
scope remains active, that handler scope is retained as part of the suspended
task's continuation. When the same task resumes, the scope is active again until
normal completion or unwind leaves it. Other runnable work executed by the Actor
during the suspension does not see or inherit the suspended task's handlers.

Creating a distinct asynchronous Future/task does not inherit or copy the
creator's active dynamic handler frames. This includes a Future continuation
created by `then()`: the continuation is a distinct task and handles only errors
under handlers that are dynamically installed in that continuation's own
execution. If an asynchronous task fails without handling its error, the error is
recorded by its Future and is re-signaled only when a consumer observes that
failure under the ordinary Future rules.

This rule prevents handler installation from becoming hidden Actor-wide state,
prevents unrelated task failures from being intercepted by another task's
temporary scope, and avoids retaining a creator's dynamic stack for the lifetime
of asynchronously spawned work.

### Synchronous foreign callbacks and the current Task

A Protos callback invoked by a synchronous foreign operation within its dynamic
extent (`../semantics/VALUES_AND_COLLECTIONS.md`, "Synchronous foreign
callbacks") runs in the current Task and in the same structured execution scope
as the originating foreign call, like any other synchronous nested activation.
Callback invocation creates no Task, Future, structured-concurrency scope, or
cancellation checkpoint. Work created by the callback is owned exactly as if it
were created by that activation without the intervening foreign frames.

The callback may run ordinary Protos code, including operations that may
suspend, as long as no actual suspension of the current Task occurs across the
foreign dynamic extent. For example, `value()` on an already-terminal Future
does not suspend and completes normally. An operation inside the callback that
would actually suspend the current Task is rejected before that suspension
commits: it signals a fresh standard failure occurrence of category `Error`
(`../semantics/ERRORS.md`) at that point, and no waiter is installed. The
implementation never captures or retains the foreign/host/native stack, never
creates a hidden child Task to preserve it, and introduces no second
continuation model.

Cancellation is unchanged by callback entry. A cancellation request already
pending for the Task remains pending and is observed only at the ordinary
portable cancellation boundaries of §23 that the executing code actually
reaches; a callback that reaches none may complete before observing it. When
an operation that would suspend is reached with cancellation already pending,
the ordinary cancellation-first rule (§29, "Interaction with waiting-task
cancellation") applies and cancellation is honored instead of suspending.
Nothing in this rule introduces preemption or standardizes cancellation of the
foreign operation itself.

This prohibition applies whenever Protos execution is nested inside the dynamic
extent of a synchronous foreign operation, including a callback reached during
a host-initiated entry. It does not apply to the outermost host-initiated entry
itself, which has no enclosing Protos execution and no foreign frames between it
and the host; that entry is governed by §29, "Suspendible host-initiated
RootActor entries". Being host-initiated does not exempt a nested callback from
this rule.

### Failed Future observation does not transfer producer control state

Future failure transport carries an Error outcome, not the producer's dynamic
control stack.
