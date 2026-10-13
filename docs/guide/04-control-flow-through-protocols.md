# Control Flow Through Ordinary Protocols

> **Status:** Non-normative programming guide
>
> **Normative owners:** `spec/semantics/EXECUTION_AND_CONTROL.md`,
> `spec/semantics/VALUES_AND_COLLECTIONS.md`,
> `spec/semantics/CALLABLES.md`, and `spec/PROTOS_GRAMMAR.md`

The previous chapter showed that Protos has one executable value kind, the
Closure, and that method-style behavior is still ordinary Closure invocation.
Control flow follows the same design.

The central idea is:

> Core control flow is expressed through ordinary messages and lazy Closures,
> not through a separate callback model or special `while` execution syntax.

That is visible in conditionals, short-circuit Boolean operations, and loops.
The familiar control-flow role comes from the protocol being invoked; the
underlying operations remain ordinary lookup, ordinary arguments, and ordinary
Closure activation.

## Conditional execution is a Boolean protocol

A Boolean value can select a lazy callback with `ifTrue` or `ifFalse`:

```protos
ready.ifTrue() {
    start()
}

ready.ifFalse() {
    wait()
}
```

The trailing braced form creates a Closure. It does not run the Closure while
the call is being written or parsed. The standard Boolean protocol decides
whether that callback is invoked.

For the canonical Boolean values, the standard behavior is conceptually:

```text
true.ifTrue(block)   -> invoke block(); return its normal result
false.ifTrue(block)  -> null

true.ifFalse(block)  -> null
false.ifFalse(block) -> invoke block(); return its normal result
```

This gives conditional execution without introducing a second executable value
kind for "blocks" or a dedicated conditional callback mechanism.

The selected callback follows ordinary Closure invocation rules. The unselected
callback is not invoked.

D050 completes this standard Boolean protocol with a two-way selector:

```protos
result: ready.ifTrueIfFalse(
    () => { start() },
    () => { wait() }
)
```

For canonical Booleans:

```text
true.ifTrueIfFalse(yes, no)  -> invoke yes() exactly once; return its exact normal result
false.ifTrueIfFalse(yes, no) -> invoke no() exactly once; return its exact normal result
```

Both callback-producing argument expressions are evaluated by ordinary call
semantics before the Boolean message is invoked. After that evaluation, only the
selected callback is validated for callability and invoked; the unselected
callback is never invoked. The selected callback receives zero supplied
arguments, and its exact normal result is returned unchanged.

This gives an ordinary-protocol spelling for a complete two-branch conditional
without introducing `if` or `else` as Core v0.1 syntax.

## Result-producing versus receiver-preserving conditionals

D200 adds two more ordinary messages, `if(block)` and `else(block)`. They are
for running an *action*, not for computing a value:

```text
true.if(block)    -> invoke block(); return true
false.if(block)   -> false
true.else(block)  -> true
false.else(block) -> invoke block(); return false
```

The selected callback's normal result is ignored, whatever it is (`null`, a
Boolean, any object, or a Future, which is not awaited). The message always
returns the same canonical Boolean that received it. That makes chaining
natural:

```protos
ready.if() {
    start()
}.else() {
    wait()
}
```

Each `.if` / `.else` is an independent ordinary send with its own trailing
Closure; there is no syntactic pairing and no new grammar. The order may be
reversed (`ready.else(...).if(...)`), and `false.else(a).else(b)` runs both `a`
and `b` because both sends receive `false`.

So choose by what you need back:

- `ifTrue`, `ifFalse`, and `ifTrueIfFalse` **produce** the selected callback's
  result (or `null` when `ifTrue`/`ifFalse` select nothing);
- `if` and `else` **preserve** the Boolean, so the next send in a chain can
  still branch on it.

There is no `elseIf`. Multi-way action selection composes with the existing
lazy `or`, because each `if` returns whether its alternative was selected:

```protos
(n == 1).if() { actionA() }
    .or(() => { (n == 2).if() { actionB() } })
    .or(() => { (n == 3).if() { actionC() } })
    .else() { actionDefault() }
```

Once an alternative is selected, `or` does not evaluate the later conditions.
The same holds with `||`, which lowers to `or`.

## Boolean negation and two-way selection

Canonical Booleans also provide ordinary zero-argument `not()`:

```text
true.not()  -> false
false.not() -> true
```

The unary operator `!` is existing Core syntax whose mandatory lowering is an
ordinary `not()` message send:

```text
!condition  -> condition.not()
```

So these two forms have the same standard meaning:

```protos
ready.not()
!ready
```

The standard behavior remains strict: its receiver must be exactly canonical
`true` or canonical `false`. There is no truthiness conversion.

## `and` and `or` are lazy protocol operations too

Short-circuit Boolean composition uses the same pattern:

```protos
hasUser.and() {
    user.active
}

cached.or() {
    loadFromSource()
}
```

For the standard Boolean behavior:

```text
true.and(block)   -> invoke block(); require a Boolean result; return it
false.and(block)  -> false

true.or(block)    -> true
false.or(block)   -> invoke block(); require a Boolean result; return it
```

The callback is therefore evaluated only when its result is needed.

This is important when the callback has effects:

```protos
attempted: false

alreadyReady.or() {
    attempted = true
    probe()
}
```

If `alreadyReady` is canonical `true`, the callback is not invoked, so
`attempted` remains `false`.

Do not mentally translate these forms into an eager function call whose second
operand happened to be written later. The Closure is created as an ordinary
argument, but its body remains lazy until the selected protocol behavior invokes
it.

## A trailing Closure is still an ordinary argument

The convenient form:

```protos
condition.ifTrue() {
    work()
}
```

does not introduce special `ifTrue` grammar.

It is ordinary message/call syntax with a trailing Closure argument. Likewise:

```protos
condition.whileTrue() {
    work()
}
```

is an ordinary `whileTrue` message send whose body argument is the trailing
Closure.

That distinction matters because the normal language rules still apply:

- the receiver is evaluated normally;
- arguments are evaluated using the ordinary call rules;
- creating the trailing Closure does not execute its body;
- lookup decides which selector is invoked;
- invoking the selected callback uses the ordinary Closure activation model.

The selector spelling describes a standard protocol. It is not syntax-level
authority that bypasses lookup.

## `whileTrue` is a protocol on a condition Closure

A loop is written by making the changing condition itself a Closure:

```protos
i: 0

condition: () => i < 4

condition.whileTrue() {
    i = i + 1
}

i
```

The condition captures `i` by reference, so every activation observes its
current value.

For the standard inherited `Object.whileTrue` behavior, the observable cycle is:

```text
validate condition Closure and body Closure
        |
        v
invoke condition() with zero arguments
        |
        +-- false --> return null
        |
        +-- true  --> invoke body() with zero arguments
                         |
                         v
                  ignore normal body value
                         |
                         +------> condition() again
```

This is a **pre-test** loop: the condition runs before the first possible body
activation and before every later iteration.

If the first condition result is `false`, the body runs zero times and the
whole `whileTrue` operation returns canonical `null`.

## Why the receiver is a Closure

The receiver is not the Boolean result of one already-computed condition.
Instead, it is executable code that can recompute the condition:

```protos
keepGoing: () => {
    index < items.size()
}

keepGoing.whileTrue() {
    consume(items[index])
    index = index + 1
}
```

That choice connects loops directly to the Closure model from chapter 3:

- the condition can capture lexical state;
- the body can capture and modify the same state;
- each activation gets ordinary Closure call semantics;
- no hidden mutable loop-condition object is required.

Thinking of `whileTrue` as "repeatedly invoke this condition Closure" is much closer
to Protos semantics than thinking of it as a keyword that owns an embedded
expression.

## Standard `whileTrue` has a strict contract

The standard behavior is Closure-specific.

Before iteration begins, the selected standard `whileTrue` behavior requires:

1. the original receiver to be a semantic Closure;
2. exactly one argument;
3. that argument to be a semantic Closure.

The condition and body are then invoked with **zero supplied arguments** on
each reached activation.

That does not bypass normal parameter binding. If a callback declares
parameters with defaults, ordinary zero-argument Closure binding rules decide
whether that activation succeeds. Binding behavior inside an unreachable body
is not pre-executed merely because the body Closure was accepted as the loop
argument.

## There is no truthiness in the standard loop decision

Each normal condition result must be exactly canonical `true` or canonical
`false`.

These are not accepted as loop decisions merely because another language might
consider them truthy or falsy:

```protos
null
0
1
""
{}
```

A normal non-Boolean condition result signals the ordinary standard Error rather
than being coerced.

A `Future` returned as the condition result is also not implicitly awaited to
obtain a Boolean. It is simply not a valid normal condition result for the
standard loop protocol.

So code such as this should produce an actual Boolean:

```protos
condition: () => index < limit
```

rather than relying on a convention such as "non-null means continue".

## Body results do not become loop results

The normal value returned by each reached body activation is ignored:

```protos
i: 0

result: (() => i < 1).whileTrue() {
    i = i + 1
    99
}

result === null
```

The body can still produce effects, update captured state, call other code, or
signal control transfer. Its **normal value**, however, is not accumulated and
is not the result of `whileTrue`.

Normal loop completion always yields canonical `null`.

## Ordinary lookup still matters

`whileTrue`, `ifTrue`, `ifFalse`, `and`, and `or` are protocol selectors reached
through ordinary lookup. Their names are not reserved control-flow syntax that
automatically wins over object behavior.

For standard `whileTrue`, Closure values delegate to `Object`, where the standard
selector is installed. A nearer slot can shadow an inherited selector according
to the ordinary lookup rules.

This means two ideas must remain separate:

```text
selector name "whileTrue"
    ordinary message name

standard Object.whileTrue behavior
    the Closure-specific Core protocol described in this chapter
```

If lookup selects user-defined behavior under the same name, that behavior is
ordinary user-defined behavior. The standard Closure receiver/body contract
belongs to the standard behavior when that behavior is actually selected.

The same principle is why the grammar does not need to decide whether the
receiver of `ifTrue`, `and`, or another protocol selector is "really a Boolean".
Syntax identifies a message send; runtime lookup and the invoked behavior's
receiver contract determine what happens.

## Errors and non-local returns cross the loop normally

The loop does not create a second control-transfer universe.

If a reached condition or body signals an Error, that Error propagates through
`whileTrue` to the surrounding dynamic handling context.

Likewise, a valid non-local return from a reached callback continues toward its
captured return home:

```protos
findFirst: (limit) => {
    i: 0

    (() => i < limit).whileTrue() {
        (i == 3).ifTrue() {
            ^i
        }

        i = i + 1
    }

    null
}
```

When the `^i` is reached, control does not perform another body activation or
another condition check first. The non-local return keeps its ordinary meaning
from the Closure model.

Effects completed before the transfer remain completed; `whileTrue` does not roll
them back.

## Suspension composes without replaying completed effects

Condition and body Closures can participate in the normal task/suspension model.

When a reached callback suspends and later resumes, the loop continues from the
same semantic phase. Already completed callback effects are not supposed to be
duplicated merely because execution had to resume.

From a programmer's point of view, this means asynchronous suspension does not
require a different loop syntax or a second loop API. The same protocol
composes with the task runtime.

The exact task and Future ownership rules are covered by the concurrency
specification and by the later Programming Guide chapter dedicated to Futures
and structured concurrency.

## A body-returned Future is still just an ignored body value

One subtle boundary is worth knowing before that later chapter.

If a reached body normally returns a Future, `whileTrue` does **not** turn that
normal return into an implicit `await`:

```text
body() normally returns Future
        |
        v
whileTrue ignores that body value
        |
        v
next condition activation
```

The loop itself does not adopt, await, or cancel that Future merely because it
was the body's return value.

Any structured-lifetime relationship created by the task that produced the
Future remains governed by the ordinary task-ownership rules. Ignoring the body
value does not erase those independent rules.

This is different from a condition returning a Future: a Future is not a valid
canonical Boolean condition result, so the standard loop signals Error rather
than waiting for it.

## Validation and execution happen at different moments

It is useful to distinguish accepting the loop operation from reaching a
callback body.

For example, the standard operation validates that its body argument is a
Closure before the first condition activation. But code inside that Closure is
still lazy:

```protos
(() => false).whileTrue() {
    missingBinding
}
```

The body Closure is a valid body value, yet its body is unreachable because the
condition is immediately `false`. `missingBinding` is therefore not evaluated
just to preflight the loop.

By contrast, if the condition is `true`, the body activation is reached and its
ordinary binding/lookups can then fail normally.

The same principle applies to the condition Closure itself: its body executes
when the condition activation is reached, not when the Closure value is merely
created.

## Practical rules to remember

1. Read control-flow forms as ordinary messages whose lazy work is carried in
   Closures.
2. Remember that trailing Closure syntax supplies an ordinary call argument; it
   does not create a separate control construct.
3. Use `ifTrue` or `ifFalse` for one-branch conditional execution, and
   `ifTrueIfFalse` when exactly one of two lazy callbacks must be selected and
   its result is needed; use `if` / `else` for actions whose results are
   discarded and whose receiver Boolean should keep flowing through a chain.
4. Use `not()` or unary `!` for strict canonical-Boolean negation.
5. Use `and` and `or` for lazy Boolean short-circuiting, and return an actual
   Boolean from a selected callback.
6. Model a `whileTrue` condition as a zero-argument Closure that recomputes a
   canonical Boolean every time it is invoked.
7. Do not rely on truthiness or implicit Future awaiting for loop decisions.
8. Treat normal body values as discarded; normal loop completion is `null`.
9. Expect Error and valid non-local return to propagate through a reached
   callback normally.
10. Remember that selector names still participate in ordinary lookup and may be
   shadowed by nearer user-defined behavior.
11. Keep task/Future ownership separate from the loop's ignored body-result
    rule.

## Run the executable conformance examples

The current conformance corpus contains direct executable evidence for both
the D050 Boolean completion and the standard loop behavior discussed here.

Representative Boolean cases include:

- [`../../protos/tests/conformance/boolean/negation-and-receiver.protos`](../../protos/tests/conformance/boolean/negation-and-receiver.protos)
  demonstrates `!` through the mandatory `not()` lowering (`not operator true` case);
- [`../../protos/tests/conformance/boolean/if-true-if-false.protos`](../../protos/tests/conformance/boolean/if-true-if-false.protos)
  demonstrates true-branch selection and exact result propagation (`iftrueiffalse true selected`
  case), tolerance of an unselected non-invokable value (`iftrueiffalse unselected noninvokable`
  case), and ordinary eager evaluation of callback-producing argument expressions
  (`iftrueiffalse argument expressions eager` case);
- [`../../protos/tests/conformance/boolean/receiver-preserving-if-else.protos`](../../protos/tests/conformance/boolean/receiver-preserving-if-else.protos)
  demonstrates that `if` / `else` return the exact receiver and ignore the
  callback result, chaining in both orders, and multi-way selection through
  `or` and `||`.

Representative loop cases include:

- [`../../protos/tests/conformance/control/while-basic-and-validation.protos`](../../protos/tests/conformance/control/while-basic-and-validation.protos)
  demonstrates repeated pre-test iteration, zero iterations and canonical
  `null` completion, and that a Future condition result is not implicitly
  awaited;
- [`../../protos/tests/conformance/control/while-future-ownership.protos`](../../protos/tests/conformance/control/while-future-ownership.protos)
  demonstrates that normal body values are ignored;
- [`../../protos/tests/conformance/control/while-binding-and-transfer.protos`](../../protos/tests/conformance/control/while-binding-and-transfer.protos)
  demonstrates non-local return across a reached body;
- [`../../protos/tests/conformance/control/while-suspension-and-cancellation.protos`](../../protos/tests/conformance/control/while-suspension-and-cancellation.protos)
  demonstrates repeated condition/body suspension without duplicated completed
  effects.

These files are conformance evidence, not additional normative authority.

## Normative references

For exact behavior, consult:

- [`../../spec/semantics/EXECUTION_AND_CONTROL.md`](../../spec/semantics/EXECUTION_AND_CONTROL.md)
  for iteration order, strict loop decisions, normal completion, control
  transfer, suspension, and cancellation composition;
- [`../../spec/semantics/VALUES_AND_COLLECTIONS.md`](../../spec/semantics/VALUES_AND_COLLECTIONS.md)
  for canonical Booleans and the standard `not`, `ifTrue`, `ifFalse`,
  `ifTrueIfFalse`, `if`, `else`, `and`, and `or` protocols;
- [`../../spec/semantics/CALLABLES.md`](../../spec/semantics/CALLABLES.md) for
  Closure receiver domains, ordinary `Object.whileTrue` placement, lookup,
  extraction, shadowing, and activation;
- [`../../spec/PROTOS_GRAMMAR.md`](../../spec/PROTOS_GRAMMAR.md) for ordinary
  call/message syntax and trailing Closures;
- [`../../spec/concurrency/FUTURES_AND_TASKS.md`](../../spec/concurrency/FUTURES_AND_TASKS.md)
  for structured task ownership and Future semantics;
- [`03-closures-methods-and-receivers.md`](03-closures-methods-and-receivers.md)
  for the Closure, capture, receiver, and non-local-return model this chapter
  builds on.

Those documents define the language. This chapter explains how their rules fit
together while writing control flow in Protos.
