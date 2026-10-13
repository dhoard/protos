# Protos Execution and Control v0.1

Language version: 0.1
Status: Draft
Last updated: 2026-09-18

This document is the primary normative owner of execution contexts, lookup/control foundations, intrinsic execution references, evaluation order, iteration/loop control, and related execution semantics.

The material below is migrated without intended semantic change from `../PROTOS_LANGUAGE_SPEC.md`. Legacy section titles and numbering are retained so existing references remain understandable.

## 4. Execution Context

Every execution has a `context`.

`context` is an object. Parameters, temporary bindings, and local slots belong to this object.

```js
greet: (name) => {
    message: "Hello " + name
    print(message)
}
```

Conceptually:

```text
context
├── name
└── message
```

There is no separate semantic category called a local variable. Local variables are slots of an execution context.

Execution contexts are ordinary Protos objects. Their standard prototype is `Context`, provided by the standard prelude:

```text
activationContext
        ↓
Context
        ↓
Object
```

`Context` is not a reserved word, and it is distinct from the reserved intrinsic pseudo-identifier `context`, which denotes the current execution context. Behavior provided by `Context` is inherited through ordinary Protos delegation; there is no separate runtime object category for execution contexts and no special lookup mechanism associated with `Context`.

### Lexical Parent Relation

Every execution context that participates in lexical lookup has an immediate **lexical parent context**, or no lexical parent when it is the lexical root. This semantic relation is distinct from ordinary object delegation and is not the `Context -> Object` delegation chain.

For Core v0.1:

- a Closure invocation's fresh activation context has the Closure's captured lexical context as its immediate lexical parent;
- an object-construction context uses the genuine lexical context of the enclosing execution as its lexical parent while the object body executes, but the object under construction is not thereby captured as a lexical parent by methods created in that body;
- a module's `moduleContext` has the frozen standard prelude context as its immediate lexical parent while the module body executes;
- the frozen standard prelude context has no lexical parent.

Following lexical parents therefore visits execution-context objects, but it never follows any visited context object's ordinary delegation parent. In particular, `Context`, `Object`, or behavior inherited through them does not become an unqualified lexical binding merely because execution contexts delegate through `Context`.

Implementations may represent lexical-parent associations in activation metadata, environment records, links between context objects, or another form. That representation is not observable; the lexical traversal defined in §6 is.

### Execution-Context Local-Slot Structure

A genuine execution context — the object bound to a Closure invocation's fresh
activation, or to a module's `moduleContext`, as established above — follows
the ordinary structural `Object` contract owned by `OBJECT_MODEL.md` §22:

1. While the execution context is open, a local slot that is currently absent
   may become present through ordinary bare creation or explicit member
   creation.
2. An existing local slot's value may change while that slot remains writable.
3. While the execution context is open, `removeSlot(name)` may remove a
   PRESENT local slot and returns the exact removed value under the ordinary
   `Object` rule. The slot is then ABSENT.
4. `close()` and `freeze()` retain the ordinary structural restrictions defined
   for objects: a closed or frozen execution context rejects structural
   removal, and a frozen execution context also rejects value mutation.

Removing a local slot changes semantic presence, not the lexical topology of
the activation. A subsequent bare read therefore performs the ordinary lookup
defined in §6 from the state that exists after the removal: an outer lexical
binding with the same name may become visible, and after lexical exhaustion the
ordinary receiver fallback may become visible.

Closure capture and context escape remain by reference (`CALLABLES.md`).
Consequently, removal through any captured or escaped reference to the same
execution context is observed by later lexical reads. A later legal creation
of the same name while the context is open may make that binding PRESENT again.

A PRESENT slot whose value is `null` remains distinct from an ABSENT slot.
These rules introduce no lexical-versus-dynamic slot-provenance category and
do not alter the evaluation-order rule for bare assignment: the assignment
destination is selected before evaluation of the right-hand side and is not
re-resolved after right-hand-side effects.

### Object Construction Is Not a Lexical Capture Scope

An object body executes with the object being constructed as its current slot-creation context, but the object itself does **not** become a lexical environment captured by method closures declared in that body.

This distinction is fundamental. Object slots are receiver state, not lexical variables. A method inherited through delegation must therefore resolve bare state names against its dynamic receiver after genuine lexical contexts have been searched.

```js
animal: {
    name: "animal"

    speak: () => {
        print(name)
    }
}

dog: animal {
    name: "Rex"
}

dog.speak()
```

The bare `name` in `speak` resolves to `dog.name`, so the call prints `"Rex"`. The local `name` slot of `animal` is a distinct slot and remains reachable through ordinary delegation when the receiver does not provide a nearer slot.

A method may still capture genuine enclosing lexical contexts, such as module bindings or locals of an enclosing closure. Those lexical bindings have priority over receiver lookup.

Conceptually, bare-name lookup inside a method is therefore:

```text
current activation context (local slots only)
        ↓ lexical parent
genuine captured lexical contexts (local slots only)
        ↓ lexical parent
standard prelude when present in that lexical chain (local slots only)
        ↓ after lexical exhaustion
this
        ↓ ordinary delegation
parent of this
        ↓
...
```

The object in whose body the method closure was created is not inserted into the lexical portion of that chain merely because it owns the method slot.
## 5. `this`

`this` represents the current receiver.

During:

```js
dog.speak()
```

if `speak` is invoked as a method:

```js
this === dog
```

This remains true even when `speak` is found through delegation.

Given:

```text
rex → dog → animal
```

executing:

```js
rex.speak()
```

keeps:

```js
this === rex
```

`this` is an intrinsic pseudo-identifier supplied by the current execution state. It is not an ordinary bare identifier and does not execute the unqualified-name algorithm in §6. The same distinction applies to the intrinsic pseudo-identifier `context`. The spelling `args` is an ordinary bare identifier and therefore does execute the unqualified-name algorithm in §6. `super` is governed separately by §8 and is not a bare-name lookup.
## 6. Unqualified Lookup

An ordinary bare identifier expression such as:

```js
name
```

performs unqualified contextual lookup. This section is the primary normative owner of that lookup algorithm.

The algorithm is exactly:

1. Start with the current execution context as the current lexical context.
2. Inspect **only the local slots** of that lexical context for `name`. Do not consult that context object's ordinary delegation parent.
3. If a local slot named `name` exists, return its exact current value and stop.
4. Otherwise move to the immediate lexical parent defined in §4 and repeat steps 2-4 until the lexical chain is exhausted. The frozen standard prelude participates when, and only when, it is on that lexical chain.
5. If the lexical chain is exhausted and the current execution has an ordinary receiver `this`, perform an ordinary member read for `name` starting at `this`. This receiver phase searches `this` and then its ordinary delegation parents under `OBJECT_MODEL.md`; when the selected value is a method Closure, the binding rules in `CALLABLES.md` preserve the original receiver.
6. If no lexical local slot exists and the receiver phase is absent or also finds no slot, signal a fresh standard `SlotNotFound` failure under the Error-object construction and identity rules owned by `ERRORS.md`. Failed lookup never implicitly produces `null`.

The order above is fixed. In particular:

- ordinary delegation of an execution context never participates in the lexical phase, so a slot available only through `context -> Context -> Object` does not satisfy a bare read;
- the entire lexical chain is searched before receiver fallback, so any lexical binding — including a prelude binding when the prelude is on that chain — shadows a same-named receiver slot;
- receiver fallback is ordinary member lookup and may therefore find a slot inherited through the receiver's delegation chain;
- no Process-wide, Actor-wide, module-cache-wide, host-global, or other implicit global namespace is searched;
- an explicit member read such as `context.name` or `object.name` is a different operation: it follows the ordinary member-lookup rules of `OBJECT_MODEL.md`, including ordinary delegation.

Closure capture does not snapshot these slot values. Closures capture their genuine lexical execution contexts by reference under `CALLABLES.md`, so later mutation of an existing captured slot, and later creation of a slot in a still-captured context where creation is otherwise valid, is observed by subsequent bare lookup through that same context.

At module top level, the current lexical context is the module's `moduleContext` and its lexical parent is the frozen standard prelude. Module execution has no additional implicit global receiver namespace. Consequently the module-top-level bare-name path is exactly: local module binding, then local prelude binding, then absence. Module-specific ownership, freezing, isolation, and import behavior remain owned by `MODULES.md`.
## 7. Unqualified Assignment and Creation

Bare read, bare assignment, and bare creation are distinct operations and do not share one generic lookup walk.

For bare assignment:

```js
x = value
```

the assignment destination is selected before the right-hand side is evaluated. Destination selection proceeds as follows:

1. Starting at the current execution context, inspect only local slots for `x`; if absent, continue through immediate lexical parents, again inspecting local slots only.
2. The first lexical context with a local slot named `x` is the selected destination. Selection does not skip that binding based on whether the later mutation attempt will succeed; it does not continue to an outer lexical context or to `this`.
3. If the complete lexical chain contains no local slot named `x` and the current execution has an ordinary receiver `this`, inspect **only `this`'s own local slots**. If `this` has a local `x`, that slot is the selected destination.
4. Do not follow ordinary delegation from any lexical context or from `this` while selecting an assignment destination. In particular, an inherited receiver slot is readable through the §6 receiver fallback but is not a destination for bare `=`.
5. If no destination exists, signal a fresh standard `SlotNotFound` failure under the Error-object construction and identity rules owned by `ERRORS.md`. Bare assignment never creates a slot.

This search is for the nearest existing local binding, not for the nearest writable binding. A local binding that exists but is frozen, closed against the requested mutation, or otherwise non-modifiable wins destination selection; lookup does not skip it in search of a farther binding.

Once a bare-assignment destination has been selected, that exact local slot
remains the destination for the rest of that assignment expression. Only after
successful destination selection is the right-hand side evaluated. If destination
selection finds no eligible existing binding, a fresh standard `SlotNotFound` is
signaled before right-hand-side evaluation begins. If right-hand-side evaluation
performs a control transfer, no assignment write is attempted.

After the right-hand side completes normally, assignment attempts to write the
exact object produced by that evaluation into the previously selected local slot.
The destination is **not re-resolved** after right-hand-side effects, even if those
effects create, remove, shadow, freeze, close, or otherwise alter same-named slots
elsewhere in the lexical/receiver search space. Ordinary object-state mutation
validation applies to the already-selected destination at this write attempt; a
failure there does not cause lookup to continue to another binding, and effects
already produced by right-hand-side evaluation are not rolled back.

Bare creation:

```js
x: value
```

performs no lookup. After evaluating `value`, it attempts ordinary slot creation named `x` **only on the current execution context**. Normal local creation/open/frozen/conflict rules apply. A successful bare creation may therefore shadow an outer lexical binding, a prelude binding, a receiver slot, or a receiver-delegated slot without modifying any of them.

Inside a function, bare creation is conceptually equivalent to creating the slot directly on the current `context` object:

```js
context.x: value
```

To explicitly create or assign receiver state, source uses an explicit member operation such as:

```js
this.x: value
this.x = value
```

Those explicit member operations are governed by `OBJECT_MODEL.md`; they are not bare-name lookup and do not alter the lexical-parent relation.

### D143 multiple bare slot creation from a standard Array prefix

The grammar form:

```protos
(a, b): source
```

is one slot-creation expression with two or more bare target names. It does not create a tuple, pattern, hidden Closure activation, or separate binding namespace.

For `N` target names, evaluation is exactly:

1. Evaluate `source` exactly once. If that evaluation performs an Error or other control transfer, no target slot is created.
2. Require the resulting object to own standard Array indexed state under `VALUES_AND_COLLECTIONS.md`. Merely inheriting or copying Array behavior is insufficient. Open, closed, and frozen eligible Arrays are equivalent for this read-only operation. An ineligible source signals an `Error` before any target slot is created.
3. Require the current Array length to be at least `N`. A shorter Array signals an `Error` before any target slot is created. Extra elements are ignored and no remainder Array is created.
4. Establish one shallow observation of exactly the first `N` indexed element references in ascending logical index order `0` through `N - 1`. This observation occurs before any target slot creation. It reads standard Array indexed state directly and performs no user-visible `at` send, iteration, matcher invocation, conversion, equality/hash operation, callback, or arbitrary-object deconstruction protocol. The observed element objects are not cloned or frozen.
5. Create the target names strictly left-to-right in the current slot-creation context, each with the corresponding already-observed element reference, using the ordinary bare `:` local-creation/open/frozen/conflict rules applicable at that source position.
6. If every target creation completes normally, the result of the entire expression is the exact original `source` result object.

There is no duplicate-name preflight, target reservation, multi-slot transaction, or rollback. A duplicate name therefore follows ordinary left-to-right creation: an earlier occurrence may create its slot and a later occurrence may then fail because that local slot already exists. Likewise, if any later target creation fails for an ordinary slot-state or conflict reason, earlier successful creations remain. Effects already produced by evaluating `source` are not rolled back.

The same current slot-creation destination used by ordinary bare `:` at that source position is used here. Thus activation-local, module-context, and object-body creation retain their existing distinctions; D143 introduces no new scope or receiver rule.

D143 adds only multiple **creation**. There is no corresponding multiple-assignment form, and this rule adds no rest/remainder binding, nested/Map/object destructuring, wildcard/default/alias/OR/guard institution, matcher binding, or generic positional-deconstruction protocol.

## 8. `super`

`super` is not another receiver and is not a first-class value. It is special lookup syntax.

It means:

> Continue lookup after the object where the currently executing method was found, while preserving the original receiver.

Only a super message send is valid in the core language, for example `super.speak()` or `super.move(x, y)`. The message name following `super.` is a contextual member name and may be a reserved-word spelling, so `super.true()`, `super.this()`, and `super.super()` are valid super message sends whose message names are respectively `true`, `this`, and `super`; this does not make `super` a first-class value. Expressions such as `x: super`, `foo(super)`, bare `super`, or method extraction such as `f: super.speak` are invalid.

Conceptually, `super.message(args...)` is syntactic sugar for a context-aware send operation using `context`: the receiver remains `context.receiver`, while lookup starts at `parent(context.methodHome)`. `super` therefore does not need to exist as a runtime object.

Given:

```text
rex → dog → animal → Object
```

if a method defined in `dog` executes:

```js
super.speak()
```

lookup begins at `animal`, while `this === rex` remains true.

Conceptually:

```text
receiver     = this
lookupOrigin = parent(methodHome)
```

The availability of `methodHome` is a dynamic invocation property, not a
syntactic category of the Closure that contains the super send. `CALLABLES.md`
defines one executable value kind, Closure, and method behavior as an invocation
role. A syntactically valid super message send is therefore not rejected merely
because the Closure was created in a source position that had no method role.

For `super.message(arguments...)`, first form the complete caller-supplied
positional argument vector under the ordinary call argument, spread, and
trailing-Closure rules in `CALLABLES.md`. Argument items are evaluated exactly
once from left to right. If that phase signals an Error or performs another
control transfer, super dispatch does not begin and the super send produces no
additional failure.

After the argument vector completes normally, super dispatch proceeds exactly as
follows:

1. If the current execution has no `methodHome`, signal one fresh standard
   `InvalidSuper` failure under the construction and identity rules in
   `ERRORS.md`. No slot lookup is attempted.
2. Otherwise preserve the current receiver and determine the lookup origin as
   the immediate delegation parent of `methodHome`. If `methodHome` has no
   delegation parent, the super lookup search space is empty and the send
   signals one fresh standard `SlotNotFound` failure. Under `OBJECT_MODEL.md`,
   this no-parent case can occur only for the unique root `Object`.
3. Starting at that lookup origin, perform the ordinary delegating lookup for the
   message name while preserving the original receiver. If no matching slot is
   found, signal one fresh standard `SlotNotFound` failure. If a slot is selected,
   its invocation follows the ordinary Closure/method rules in `CALLABLES.md`.

`InvalidSuper` therefore identifies absence of the invocation metadata required
to define a super lookup origin; it is not a failed selector lookup. Conversely,
once `methodHome` exists, failure to find anything after that home is
`SlotNotFound`, including the empty search after root `Object`.

Effects already produced while evaluating earlier argument items are not rolled
back if the subsequent super dispatch fails. This rule creates no static
`Method` value kind, no first-class `super` object, and no fallback receiver or
lookup origin.

## 8.1 Evaluation Order

The language evaluates strict subexpressions from left to right. The receiver or assignment target is evaluated before arguments or the right-hand side, and arguments are evaluated left to right. Parent expressions are evaluated before object bodies. Standard binary operators evaluate their left operand before their right operand.

```js
getObject().x = makeValue()
```

evaluates `getObject()` first, then `makeValue()`, then performs the assignment. Lazy operations such as `&&` and `||` are exceptions because their right-hand expression is evaluated only when required by their lazy semantics.

### Normal result of slot creation and assignment expressions

The following four slot-write forms are expressions:

```js
x: value
object.x: value
x = value
object.x = value
```

Their target-selection, local-slot, delegation, open/closed/frozen, conflict, and
write-validity rules remain those defined by §7 for bare forms and by
`OBJECT_MODEL.md` for explicit member forms.

After all subexpressions required by the applicable evaluation-order rule have
been evaluated, the slot creation or assignment is attempted using the exact
object produced by the right-hand-side evaluation. If and only if that slot
operation completes normally, the result of the whole slot-write expression is
that **same exact object**.

The result is therefore not `null`, not the target/receiver, and not a value
obtained by reading the slot again after the write. No implicit conversion,
copying, canonicalization, wrapping, or second lookup is performed merely to
produce the expression result. In particular, when the right-hand side produces
an identity-bearing object, successful slot creation or assignment preserves
that object identity both in the written binding and as the expression result.

If evaluation of a required target or right-hand-side subexpression performs a
control transfer, the write is not completed and the slot-write expression has
no normal result. If the right-hand side completes normally but the subsequent
slot creation or assignment signals an `Error` or otherwise transfers control,
the expression likewise has no normal result; effects already produced by
earlier evaluation are not rolled back by this rule.

This rule applies only to slot creation and slot assignment. It does **not**
apply to indexed assignment such as `object[index] = value`, which is the
ordinary indexing-protocol operation defined by its own normative owner and is
not a `:`/`=` slot write.

## 8.2 Sequence Evaluation

The semantic `Sequence(expressions)` produced from an `expression-sequence` evaluates its expressions strictly from left to right in the current execution context.

If the Sequence contains one or more expressions and completes normally, its result is the exact value produced by its final expression. If the Sequence contains zero expressions and completes normally, its result is the canonical `null` value.

The `null` result is only the result of **normal completion** of the empty Sequence. Sequence evaluation does not convert a non-local return, Error signaling/unwind, cooperative cancellation unwind, or any other control transfer into `null`; when such a transfer leaves the Sequence, that Sequence produces no normal result.

This rule applies to the semantic Sequence corresponding to the grammar's `expression-sequence`, including a source module/program body and a braced Closure body. It does not redefine `object-body-sequence` or the result of object construction; object-body construction semantics remain owned independently by `OBJECT_MODEL.md`.

Implementations need not allocate a runtime Sequence object and may erase, inline, constant-fold, or otherwise optimize an empty Sequence, provided the observable normal result remains canonical `null` and all surrounding control-flow, cleanup, task/Future, and construction semantics remain unchanged.

## 17. Iteration and Loops

No primitive `for` construct is required.

```js
users.each((user) => {
    print(user.name)
})

users.map((user) => {
    user.name
})

1.to(10).each((i) => {
    print(i)
})
```

### Standard Closure `whileTrue` and `while` operations

Core v0.1 exposes pre-test looping through two ordinary messages:

```text
condition.whileTrue(body)
condition.while(body)
```

Both standard behaviors require the original receiver `condition` to be a
semantic Closure, require exactly one argument, and require `body` to be a
semantic Closure. `CALLABLES.md` owns the ordinary `Object.whileTrue` and
`Object.while` slot placement, Closure-family receiver domain, extraction,
shadowing, and invocation-role consequences. `../PROTOS_GRAMMAR.md` owns only the
ordinary message and trailing-Closure syntax; neither selector introduces a
dedicated grammar production or reserved word.

`whileTrue` and `while` are the standard pre-test loop selectors. They execute
the identical validation and loop algorithm below and differ only in their
normal result. They are not aliases: each is a distinct standard behavior.
Core v0.1 defines no standard inverse `whileFalse` selector; that name remains an
ordinary member name that programs may define under the ordinary slot rules
without acquiring standard loop behavior.

The common trailing-Closure spelling is therefore ordinary syntax for the same
one-argument message:

```js
(() => i < 10).whileTrue() {
    i = i + 1
}
```

Receiver and argument expressions are evaluated completely by the ordinary call
rules before the standard behavior begins. After that evaluation completes, the
standard behavior validates, in order, that the original receiver is a semantic
Closure, that exactly one argument was supplied, and that the resulting `body`
value is a semantic Closure. If any validation fails, neither Closure is invoked.

This validation is Closure-domain validation only. It does not preflight either
Closure's declared parameter arity, evaluate defaults, or execute user code.
Each actual loop callback activation below supplies zero positional arguments;
ordinary Closure parameter binding therefore reports any incompatible declared
arity only when that particular activation is reached. In particular, a body
whose parameter binding would fail is not activated when the first condition
result is `false`, even though the body value itself was already validated as a
Closure before the first condition activation.

After successful validation either operation repeats this exact pre-test algorithm:

1. Activate the semantic `condition` Closure itself with zero caller-supplied
   positional arguments, using its ordinary Closure activation, capture,
   receiver/`methodHome`, parameter-binding, return-home, Error, and explicit
   suspension semantics. This activation executes the validated Closure itself;
   the standard loop operation does not perform a new polymorphic `call` lookup
   on the Closure object in place of that Closure activation.
2. If the condition activation completes normally with exact canonical `false`,
   terminate the loop normally without activating `body` for that test.
3. If it completes normally with exact canonical `true`, activate the semantic
   `body` Closure itself with zero caller-supplied positional arguments under the
   same ordinary Closure rules. If that activation completes normally, its exact
   normal result becomes the operation's most recent body result, replacing any
   earlier one, and the next iteration begins by activating `condition` again.
4. If the condition activation completes normally with any value other than
   exact canonical `true` or exact canonical `false`, signal one fresh standard
   `Error` failure occurrence under `ERRORS.md`. The body is not activated for
   that test. There is no truthiness, coercion, implicit invocation, Boolean
   delegation test, implicit awaiting, or Future adoption.

The loop terminates normally only through step 2. Its normal result is then:

- for `whileTrue`, the exact normal result of the most recent normally completed
  body activation, or canonical `null` when no body activation ran (the
  zero-iteration case);
- for `while`, the final condition result, which is necessarily exact canonical
  `false`, whether zero, one, or many body activations ran.

The `whileTrue` result is returned as is: an object, Boolean, primitive value, or
Future keeps its exact identity and is not converted, copied, wrapped, awaited,
or adopted. Earlier body results are not accumulated or otherwise observable
through the loop. The terminal condition activation runs after the last body
activation and may observe or mutate the object that `whileTrue` will return;
that object's identity is still the result. If the terminal condition activation,
or any other condition or body activation, does not complete normally, the
invocation has no normal result and the most recent body result is not returned.

A normal Future value has no loop-specific meaning. If `condition` normally
returns a Future, that object is a non-Boolean condition result and the standard
invalid-result `Error` above is signaled; the loop does not await, adopt,
flatten, or cancel it. If `body` normally returns a Future, the loop does not
implicitly observe, await, adopt, flatten, or cancel it; under `whileTrue` that
Future is the most recent body result like any other value and may therefore be
the invocation's normal result. When such a result in turn becomes the outcome of
an enclosing asynchronous computation, the ordinary Future-resolution adoption
rules of `../concurrency/FUTURES_AND_TASKS.md` apply to it there, not inside the
loop. Existing structured-ownership rules for work created while either Closure
activation executes remain owned by `../concurrency/FUTURES_AND_TASKS.md`. Those
synchronous callback activations do not establish new structured execution scopes
of their own merely by being invoked by a standard loop.

### Control transfer, suspension, and cancellation

Neither `whileTrue` nor `while` introduces a handler, cleanup scope, return home,
task, Future, scheduler boundary, cancellation mask, or hidden
suspension/checkpoint of its own.

If condition or body execution signals an Error, performs a valid non-local
return, encounters `InvalidReturn`, begins cooperative cancellation unwind, or
otherwise leaves by a non-normal control transfer, that transfer propagates
unchanged through the loop invocation. No later condition/body activation is
started by that invocation, and effects already completed are not rolled back.

If condition or body explicitly suspends through an operation whose existing
contract permits suspension, suspension is not loop completion and does not
restart the logical iteration. Resumption continues at the same semantic point.
A conforming implementation must not duplicate a condition activation, body
activation, or already-completed callback effect merely because suspension,
carrier change, interpreter replay, compilation, deoptimization, or equivalent
implementation machinery occurred.

Cooperative cancellation is observed only at the ordinary cancellation
boundaries reached by the executing code. The standard loop adds no polling or
preemption point simply because another iteration begins. Once cancellation is
honored, ordinary unwind/`ensure`/structured-child rules apply; the loop neither
shields nor re-delivers that cancellation.

These rules define observable loop semantics, not a required implementation
shape. Implementations may inline, specialize, compile, or otherwise eliminate
explicit loop protocol machinery when ordinary lookup/reflection/shadowing,
validation timing, exact Boolean tests, callback activation count/order, normal
result, control transfer, suspension/replay, cancellation, and Future behavior
remain identical.

A future source form such as `while (...) { ... }` would require a separate
normative grammar decision. It is not Core v0.1 syntax and cannot alter the
ordinary `condition.whileTrue(body)` and `condition.while(body)` protocols defined
here.

## Resource Cleanup and `ensure`

This section is the primary normative owner of the standard Closure
`ensure(cleanup)` behavior and deterministic control-flow cleanup/unwind
semantics. Error signaling and handler selection during cleanup compose with
`ERRORS.md`. `CALLABLES.md` owns the ordinary `Object` slot placement,
Closure-family receiver domain, extraction, and invocation-role consequences.
`../PROTOS_GRAMMAR.md` owns only the ordinary message syntax; `ensure` introduces
no dedicated grammar production or lowering.

Core v0.1 defines no deterministic object destructor.

Resource release is explicit protocol behavior, for example:

```js
file.close()
socket.close()
```

### Standard Closure cleanup operation

Core exposes unwind-safe cleanup through the ordinary message:

```js
body.ensure(cleanup)
```

The standard behavior requires the original receiver `body` to be a semantic
Closure, requires exactly one argument, and requires `cleanup` to be a semantic
Closure.

Receiver and argument expressions are evaluated by the ordinary invocation
rules before the standard behavior begins. After that evaluation completes, the
standard behavior validates its receiver domain, arity, and cleanup Closure
before invoking `body` or establishing a protected extent. If validation fails,
`body` and `cleanup` are not invoked and no partially installed cleanup scope is
observable.

After successful validation, one protected dynamic extent is established and
`body` is invoked with zero arguments.

The protected extent belongs to the executing dynamic flow. Merely creating a
distinct asynchronous child task does not copy the `ensure` frame into that
child's dynamic control state. Existing structured-ownership rules in
`../concurrency/FUTURES_AND_TASKS.md` still apply at the current task-scoped
structured execution boundary. The protected body's ordinary synchronous
activation does not become a separate structured scope merely because `ensure`
invoked it: a task-backed Future created there may remain pending after the body
invocation returns. That child work can still delay terminal completion of
the enclosing owning asynchronous computation, because the child's ownership
edge remains attached to that computation's structured scope.

The live `ensure` installation is task-local dynamic control state. It is not
copied or transferred merely because protected code creates or returns a
Future, begins isolated parallel execution, transfers values/capabilities to
another Actor or Process/Node, or because a separate/later Actor incarnation is
created. Those execution domains own their own control/lifetime state; only code
actually executing inside this installation participates in its protected extent.

### Suspension is not scope exit

Suspending the protected execution does not trigger cleanup.

In particular, `Future.value()` suspension, scheduler/carrier changes, host-stack
unwinding used only to suspend, interpreter replay, compilation, deoptimization,
or equivalent implementation machinery do not semantically leave the protected
extent.

Resumption continues inside the same protected extent. A single `ensure`
installation therefore executes its cleanup exactly once on semantic scope exit,
not once per physical suspension/replay segment.

The same rule applies while cleanup itself is running: cleanup may explicitly
suspend and later resume without re-invoking the protected body or repeating
already-completed cleanup effects.

### Normal completion and exact result

If `body` completes normally with exact value `result`, cleanup runs before the
`ensure` invocation completes.

If cleanup also completes normally, the result of `ensure` is the exact
`result` produced by `body`. The cleanup Closure's normal result is ignored.

No copying, conversion, canonicalization, re-reading, Future adoption, or Future
flattening is performed merely to produce the `ensure` result.

Consequently, if `body` normally returns a Future as an ordinary value, cleanup
runs when that body invocation reaches normal completion under the existing
structured-ownership rules; `ensure` does not keep the scope open merely until
that returned Future later becomes terminal.

Likewise, if cleanup merely returns a Future as an ordinary value, `ensure` does
not implicitly observe or adopt that Future. Cleanup that must wait for an
asynchronous operation performs that wait explicitly, for example by invoking
`value()` while cleanup is running.

### Scope exits that trigger cleanup

Once the protected body has begun, cleanup runs exactly once when execution
semantically leaves its protected extent by any Core v0.1 exit mode:

- normal completion;
- non-local return with `^`;
- Error unwind;
- cooperative cancellation unwind.

Nested protected extents unwind structurally from innermost to outermost.
Nested `ensure` cleanup therefore executes in LIFO scope-exit order without
introducing a programmer-visible cleanup-stack value or global registry.

### Pending transfer and cleanup precedence

The completion or control transfer that caused scope exit remains pending while
cleanup runs.

If cleanup completes normally, that pending completion or transfer continues
unchanged. Normal cleanup therefore does not transform a normal result,
non-local return, Error unwind, or cancellation unwind into another outcome.

Cleanup is otherwise ordinary Protos execution. If cleanup initiates a later
control transfer that leaves cleanup, that later transfer supersedes the pending
completion or transfer that caused cleanup to run.

This includes an ordinary non-local return initiated by cleanup. For example, if
one non-local return is pending and cleanup successfully initiates another valid
non-local return, the cleanup's later return is the active transfer and the
earlier return does not resume afterward.

The ordinary non-local-return validity rules remain unchanged. If cleanup uses
`^` with an inactive return home, the resulting `InvalidReturn` is an Error
raised during cleanup and follows the Error-precedence rule below.

No special cleanup-return value, resumable pending transfer, dual-transfer
state, suppressed-return record, or composite control-transfer object is exposed
by Core.

### Error precedence during cleanup

Cleanup-triggered Error precedence and handler-search consequences are owned by
`ERRORS.md`.

If an Error transfer initiated by cleanup escapes the cleanup Closure, that Error
becomes the active transfer and supersedes any pending normal completion,
non-local return, Error unwind, or cancellation unwind. Once that escaping Error
has superseded the earlier transfer, the superseded transfer does not become
active again if the cleanup Error is subsequently handled outside cleanup.

An Error signaled and completely handled inside cleanup does not escape the
cleanup Closure and therefore does not supersede the pending transfer. If cleanup
then completes normally, the previously pending completion or transfer continues
unchanged.

Core does not implicitly combine the old and new failures as a composite Error,
suppressed-error list, cause chain, or wrapper. Libraries may build explicit
reporting conventions with ordinary objects and handlers.

When an Error handler was selected before unwind crossed this `ensure` scope,
the selected handler is already inactive while cleanup runs under the ordering
owned by `ERRORS.md`. That selected frame is a one-shot unwind destination: normal
crossed cleanup preserves the route to it, while a later transfer that escapes
cleanup supersedes the original transfer and abandons that selected destination.
The replacement transfer cannot re-select the consumed handler merely because it
also matches; it uses the still-active handler context defined by `ERRORS.md`.

### Cancellation-safe cleanup

Cleanup is part of the unwind that triggered it, not fresh ordinary execution
subject to re-delivery of that same control transfer.

Once a pending cancellation request has been honored and cancellation unwind has
begun, that already-honored request is not observed again at suspension
boundaries reached while running `ensure` cleanup for that unwind. Cleanup may
therefore perform ordinary asynchronous operations and suspend while releasing
resources.

This shielding is only from the cancellation request already being delivered by
the current unwind. It is not a general cancellation-masking facility and does
not turn ordinary Errors, independently observed Future outcomes, or unrelated
control transfers into successful cleanup. An implementation may represent this
with masking, an unwind phase, continuation metadata, or other machinery, but
the distinction is not otherwise observable.

For Actor termination, D121 makes one lifecycle-specific consequence of this
shielded cleanup explicit. If the currently executing `ensure` cleanup is already
authorized termination cleanup for that Actor, an explicit resource lifecycle
`close()` invoked by that exact cleanup flow may make its first close commitment
even though the Actor termination cutover has already occurred. The authorization
follows ordinary nested calls and suspension/resumption of the same cleanup
continuation. It does **not** transfer merely because the cleanup creates a new
Task, Future producer, or mailbox turn. The admitted close
then follows the ordinary lifecycle contract; any guest-requiring release is
termination cleanup under D112/D121 and PLAT030.

Under the idempotent request contract owned by
`../concurrency/FUTURES_AND_TASKS.md`, repeated `Future.cancel()` calls while the
same Future remains pending do not create a distinct or stronger cancellation
request. Repeating `cancel()` therefore does not pierce the shielding of the
already-honored request while cleanup for that unwind is running.

If cleanup completes normally during cancellation unwind, cancellation
continues afterward. A task's Future reaches terminal cancelled state only after
all applicable cleanup and the structured-cancellation requirements owned by
`../concurrency/FUTURES_AND_TASKS.md` have completed.

If cleanup instead initiates another control transfer, the general later-transfer
rules above apply. In particular, a cleanup Error makes the task fail with that
Error rather than complete as cancelled.

Core v0.1 Error signaling is non-resumable as owned by `ERRORS.md`. Every Error
transfer that reaches this cleanup rule has abandoned the signaling continuation;
a handler cannot keep the protected computation active by returning, resuming,
retrying, or supplying a value to the signal point. A future recovery facility,
if standardized, must be a distinct control mechanism with its own cleanup
contract rather than an alternate interpretation of Core `Error.signal()`. A
future continuation/effect contract must explicitly define capture, resumption,
abandonment and any multi-shot duplication relative to cleanup; D043 itself defines
no continuation cloning or duplication semantics.

The exactly-once guarantee above applies to semantic exits while the owning
execution remains capable of running Protos cleanup code. Fail-stop loss or forced
termination of the execution environment that prevents further Protos execution is
not a semantic unwind and carries no D043 guarantee that cleanup will run. Durable
or distributed recovery/compensation requires its own explicit protocol.

Higher-level resource protocols such as `use`, `withOpen`, or similar APIs may
be implemented on top of this guarantee using ordinary messages and closures.

Garbage-collector finalization is not a resource-management guarantee and must
not be relied upon for deterministic release of external resources.
