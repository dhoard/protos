# Protos Values and Collections v0.1

Language version: 0.1
Status: Draft
Last updated: 2026-09-18

This document is the primary normative owner of Core immutable value families, equality/identity, indexed access, and standard collection/value protocols; callable and general control-flow semantics are owned by their dedicated modules.

The material below is migrated without intended semantic change from `../PROTOS_LANGUAGE_SPEC.md`. Legacy section titles and numbering are retained so existing references remain understandable.

## 15. `null`

Exactly one object represents absence:

```js
null
```

There is no `undefined`.

```js
x: null
```

means the slot `x` exists and contains `null`.

A failed lookup signals an error. It does not evaluate to `null`.

`null` is a singleton object and may respond to messages like any other object.

### Standard null-aware Object control

The standard `Object` protocol provides the ordinary one-argument messages:

```text
ifNull(block)
ifNotNull(block)
```

These selectors are standard local behavior on canonical `Object` and are
reached through ordinary message lookup and dispatch. A custom object may define
or override either selector through the ordinary slot rules.

The standard behavior distinguishes canonical `null` solely by exact object
identity of the original receiver. There is no truthiness rule. Delegating to
canonical `null`, or otherwise inheriting behavior through it, does not make a
distinct object null.

Both operations accept exactly one supplied positional argument. Receiver and
argument expressions are evaluated under the ordinary eager left-to-right call
rules before invocation. The resulting callback object is callability-validated
only on a branch that actually invokes it.

For standard `ifNull(block)` behavior:

```text
receiver is canonical null
    -> invoke block() exactly once
    -> return its exact normal result

receiver is not canonical null
    -> do not callability-validate or invoke block
    -> return the exact receiver
```

For standard `ifNotNull(block)` behavior:

```text
receiver is canonical null
    -> do not callability-validate or invoke block
    -> return canonical null

receiver is not canonical null
    -> invoke block(receiver) exactly once
    -> return its exact normal result
```

The argument supplied to a reached `ifNotNull` callback is the exact original
receiver. Callback invocation uses the ordinary polymorphic invocation protocol;
the callback need not be a Closure.

A reached callback's normal result is returned unchanged, including canonical
`null`, Boolean values, ordinary objects, and Future values. These operations
perform no implicit await, Future adoption, wrapping, conversion, or hidden
suspension. Errors, non-local returns, and other ordinary control effects from a
reached callback propagate according to the existing invocation and control
rules.

An unreachable callback is neither callability-validated nor invoked, but the
argument expression that produced it has already been evaluated normally before
dispatch. A non-invokable callback therefore signals only when its branch is
actually reached.

These operations do not change lookup or collection absence semantics. A failed
member lookup still signals the ordinary `Error`; it does not become `null`.
Map absence remains distinct from a present entry whose stored value is canonical
`null`.

Core v0.1 adds no optional-navigation syntax, null-coalescing syntax,
`Optional`/`Maybe` value family, `undefined` value, truthiness rule, or
failed-lookup-to-null conversion as part of this protocol.

## 16. Booleans

Core v0.1 has exactly two semantic Boolean values: the canonical singleton
objects `true` and `false`. No other object becomes a Boolean by delegation,
copying, composition, freezing, or implementation representation.

The standard Boolean protocol consists of the ordinary messages:

```text
not()
ifTrue(block)
ifFalse(block)
ifTrueIfFalse(trueBlock, falseBlock)
and(block)
or(block)
```

The standard arities are exact: `not` accepts zero arguments; `ifTrue`,
`ifFalse`, `and`, and `or` accept exactly one argument; and
`ifTrueIfFalse` accepts exactly two arguments. Any other supplied positional
argument count signals an `Error` under the ordinary invocation boundary.

These selectors use ordinary message lookup and dispatch. The standard behavior
defined in this section is Boolean-family behavior: when ordinary lookup selects
that standard behavior, the original receiver must be exactly canonical `true`
or canonical `false`. An incompatible original receiver signals an `Error`
under the standard semantic-family receiver-domain rule in `OBJECT_MODEL.md`.
The term Boolean family in this specification names this semantic family. Core
v0.1 defines **no standard prelude binding, object, or prototype named
`Boolean`**. `Boolean` remains an ordinary identifier name: a program or library
may create or shadow a binding with that name through the ordinary slot and
lookup rules, but any object obtained from such a binding does not thereby
define Boolean membership.

Consequently, when ordinary bare-name lookup finds no program- or
library-provided binding named `Boolean`, evaluating `Boolean` signals the
ordinary missing-lookup `Error`. Expressions such as `Boolean.parent()` and
`Boolean.slotNames()` therefore have no standard Core v0.1 receiver to inspect;
in that situation evaluation fails while resolving `Boolean`, before either
member lookup or invocation occurs.

Delegating to canonical `true` or canonical `false`, or to an object that
supplies standard Boolean protocol behavior, does not make the original receiver
one of the two canonical Boolean values. Any standard protocol check that
requires a Boolean accepts exactly canonical `true` or canonical `false`.

A custom object may nevertheless define or override any of these selectors with
its own ordinary behavior; doing so does not introduce truthiness and does not
make that object a semantic Boolean.

`block`, `trueBlock`, and `falseBlock` denote already-evaluated argument
objects. Receiver and argument expressions are evaluated before the message
invocation according to the ordinary left-to-right call rules in `CALLABLES.md`.
For `ifTrueIfFalse`, both callback-producing argument expressions are therefore
evaluated, in order, before Boolean behavior selects a callback. Evaluating a
Closure literal creates the Closure object but does not execute its body.

Standard Boolean behavior requires only the callback selected by the receiver
value to be invokable through the ordinary polymorphic invocation protocol; it
need not be a Closure. The selected callback is invoked exactly once with zero
positional arguments. Callability validation does not pre-validate callback
declared arity: any arity or other invocation failure from that actual
zero-argument call propagates normally.

Callability is validated **only on a path that will invoke a callback**. An
unselected callback is neither callability-validated nor invoked. This
path-sensitivity concerns the already-evaluated callback object; it does not
suppress ordinary evaluation of the argument expression that produced it.

The exact standard results are:

```text
true.not()           -> false
false.not()          -> true

true.ifTrue(block)   -> invoke block(); return its exact normal result
false.ifTrue(block)  -> null

true.ifFalse(block)  -> null
false.ifFalse(block) -> invoke block(); return its exact normal result

true.ifTrueIfFalse(trueBlock, falseBlock)
                     -> invoke trueBlock(); return its exact normal result
false.ifTrueIfFalse(trueBlock, falseBlock)
                     -> invoke falseBlock(); return its exact normal result

true.and(block)      -> invoke block(); require Boolean result; return it
false.and(block)     -> false

true.or(block)       -> true
false.or(block)      -> invoke block(); require Boolean result; return it
```

The `null`, `true`, and `false` results above are the canonical language values.
`not` performs no callback invocation and returns the opposite canonical Boolean.
For `ifTrue`, `ifFalse`, and `ifTrueIfFalse`, a selected callback's normal result
is returned unchanged. It may therefore be `null`, either Boolean, an arbitrary
ordinary object, or a `Future`; the Boolean method performs no conversion,
awaiting, Future adoption, or result validation on that value.

For standard `and` and `or`, the result of an invoked callback must be exactly
canonical `true` or canonical `false`. Any other normal result, including
`null`, an arbitrary ordinary object, or a `Future`, signals an `Error` after
the callback has completed. No truthiness conversion, coercion, implicit
invocation, implicit awaiting, or Future adoption is performed. When no more
specific standard Error prototype is mandated by `ERRORS.md`, portability
requires only that the failure be an `Error`; implementations must not rely on
an implementation-specific visible subtype.

If a selected callback signals an Error, performs a non-local return, is
cancelled, or otherwise leaves by a non-normal control transfer, that transfer
propagates exactly as it would from an ordinary callback invocation. Standard
Boolean behavior does not catch it, wrap it, convert it to a Boolean or `null`,
or invoke the callback again. Effects completed before such a transfer or before
an invalid `and`/`or` result is detected are not rolled back.

The Boolean protocol itself introduces no hidden suspension point, task, lock,
or scheduling boundary. A selected callback may of course reach an explicit
suspension point according to its own ordinary semantics; an unselected callback
cannot suspend because it is not invoked.

The mandatory source lowering and precedence of `!`, `&&`, and `||` are owned by
`../PROTOS_GRAMMAR.md`. Unary `!value` lowers to the ordinary zero-argument
message `value.not()`. The `&&` / `||` lowering supplies a zero-argument Closure
around the right operand and then performs ordinary `and` / `or` dispatch.
Therefore standard canonical-Boolean receivers obtain the strict negation and
short-circuit behavior defined above, while a custom receiver may override
`not`, `and`, or `or` through ordinary lookup and, for `and` / `or`, may observe
the generated RHS Closure. Implementations may specialize the canonical Boolean
cases only when observable behavior remains identical to the ordinary protocol.

Possible future `if`/`else` syntax may be defined as sugar, but it does not
create a second Boolean or truthiness semantics.

## 21. Equality and Identity

`===` represents **semantic identity** and is not customizable. Its result must not depend on allocation, interning, boxing, tagged values, or any other implementation strategy.

For ordinary identity-bearing objects, identity means that both expressions denote the same individual object:

```js
a: { x: 1 }
b: { x: 1 }
c: a

a === b  // false
a === c  // true
```

Individual object identity is semantic, not physical representation identity. If an
applicable normative domain rule preserves the same identity-bearing object across
a transfer, reconstruction, migration, or rematerialization boundary, distinct
physical wrappers, proxies, handles, addresses, or cache entries for that same
semantic object must still compare `===`. Conversely, a domain rule that creates a
new semantic object creates a distinct identity even if physical storage is shared.
This does not add a value-identity family: the object remains identity-bearing, and
the domain rule determines whether the same individual object or a new one crosses
that boundary.

Some Core values have **value identity**: their semantic value determines
identity rather than a particular allocation. The Core v0.1 value-identity set
is closed and consists exactly of:

- numeric values in the `Number` family;
- `String` values;
- the canonical Boolean values `true` and `false`;
- the canonical `null` value.

No implementation, host platform, optimization, standard-library extension, or
ordinary Protos program may add another Core v0.1 value-identity category.
Future language versions may extend this set only by an explicit normative
language change.

```js
1 === 1                    // true
"hello" === "hello"        // true
("hel" + "lo") === "hello" // true
true === true              // true
null === null              // true
```

Being immutable, closed, frozen, interned, canonicalized, structurally equal,
or backed by the same host representation does not by itself grant value
identity. Every object outside the closed set above has individual object
identity.

In particular, ordinary objects, closed or frozen ordinary objects, Closures,
Arrays, Maps, Futures, errors, execution contexts, module instances, and
standard prototype objects remain identity-bearing objects unless a normative
rule explicitly places the value itself in one of the closed value-identity
families above.

Delegation does not transfer value identity. An ordinary object whose parent is
a Number, String, Boolean, or `null` value remains an ordinary identity-bearing
object, as already required by the delegation rules.

`===` is non-overridable, so user code cannot opt an object into value identity
by defining equality behavior. `==` remains the customizable protocol for
semantic equality.


`Number` objects are immutable value objects.

`String` objects are immutable value objects. An operation on a String never changes that String in place; an operation that produces different text produces another String value. Implementations may freely share or intern String storage because such sharing cannot change observable identity semantics.

The standard prelude `String` object delegates directly to `Object`. Every
semantic String value has `String` as its immediate delegation parent. This
delegation supplies standard String behavior through ordinary lookup; it does
not make an ordinary object that merely delegates to `String` a semantic String
value.

### Exact standard-owner semantic-domain recognition

Core publishes the ordinary selector `recognizes(value)` as standard local
behavior on exactly these canonical standard owners:

```text
String
Integer
SmallInteger
BigInteger
Fraction
Float
Array
```

Each of these seven standard behaviors requires its exact canonical standard
owner as receiver and accepts exactly one supplied argument. The candidate
argument may be any Protos value. A matching candidate returns canonical `true`;
a non-matching candidate returns canonical `false`.

Recognition inspects only the owning Core semantic-family or standard-state
truth defined below. It performs no message lookup or dispatch on the candidate,
does not send `parent()` to the candidate, does not invoke candidate equality or
hash behavior, invokes no callback, performs no conversion or coercion, and does
not allocate a converted replacement value. Delegation does not confer
recognition, and user-defined behavior cannot opt an object into one of these
Core domains.

An invalid receiver for one of these standard behaviors, or supplying any arity
other than exactly one argument, follows the ordinary Error rules for invalid
standard-behavior receivers and arity. Candidate mismatch itself is not an
Error; it is canonical `false`.

The seven recognized domains are exactly:

- `String.recognizes(value)` is `true` iff `value` is a semantic String value.
  An ordinary object that merely delegates to `String` or to a String value is
  not recognized, and `String.recognizes(String)` is `false`.
- `Integer.recognizes(value)` is `true` iff `value` is a semantic Integer value,
  that is, a member of either concrete Integer family defined under **Numeric
  Model**. Machine-word, boxed, or equivalent physical representation choices
  are unobservable, and `Integer.recognizes(Integer)` is `false`.
- `SmallInteger.recognizes(value)` is `true` iff `value` is a semantic Integer
  value in the signed-64 range. `SmallInteger.recognizes(SmallInteger)` and
  `SmallInteger.recognizes(Integer)` are `false`.
- `BigInteger.recognizes(value)` is `true` iff `value` is a semantic Integer
  value outside the signed-64 range. `BigInteger.recognizes(BigInteger)` and
  `BigInteger.recognizes(Integer)` are `false`.
- `Fraction.recognizes(value)` is `true` iff `value` is a semantic Fraction
  value as defined under **Numeric Model**. An ordinary object that merely
  delegates to `Fraction`, including a frozen one, is not recognized; Integers
  and Floats are not Fractions; and `Fraction.recognizes(Fraction)` is `false`.
- `Float.recognizes(value)` is `true` iff `value` is a semantic Float value.
  Recognition performs no numeric promotion or conversion; in particular an
  ordinary Integer is not a Float, and `Float.recognizes(Float)` is `false`.
- `Array.recognizes(value)` is `true` iff `value` owns standard Array indexed
  state. Immediate delegation parent identity is irrelevant. An ordinary object
  that merely delegates to `Array` is not recognized, while a standard Array
  produced by a descendant Array factory remains recognized even though its
  immediate parent is that descendant factory. Open, closed, and frozen
  standard Arrays are all recognized. `Array.recognizes(Array)` is `false`.

No additional Core owner acquires `recognizes` by symmetry. In particular this
contract adds no recognizer to `Number`, `Map`, `IdentityMap`, or `Bytes`, and
introduces no generic semantic-membership query or first-class family descriptor.

These predicates are intended for boundaries whose contract already requires
one of the exact Core domains above. Ordinary protocol-oriented programming
continues to rely on the protocol an object supports rather than on recognition
as a general substitute for polymorphism.

`true`, `false`, and `null` are canonical singleton values.

Ordinary mutable objects, closures, arrays and other identity-bearing objects retain individual object identity even when their contents happen to be equal. The exact collection model is specified separately.

`==` represents semantic equality and may be customized through object behavior.

The equality protocol has a strict result contract:

```text
==  -> true | false | error
!=  -> true | false | error
```

An implementation of `==` must return one of the canonical Boolean objects `true` or `false`, or signal an error. Returning any other object is an invalid equality result.

The same Boolean-result contract applies to the standard comparison operators:

```text
<   <=   >   >=
```

They return canonical `true` or `false`, or signal an error. The language defines no truthiness conversion for interpreting arbitrary comparison results. For built-in immutable value objects, `==` and `===` may naturally produce the same result, but they remain different operations: `==` is behavioral and customizable, while `===` is a non-overridable identity primitive.

Identity is never defined by comparing hash codes. A runtime may derive or cache hashes from identity where appropriate, but hash collisions cannot make distinct identity-bearing objects identical.
## Indexed Access Syntax

Bracket indexing is syntactic sugar over ordinary message sends. Indexing is not a privileged runtime operation and is not restricted to arrays.

Slot/member access and indexed access are distinct mechanisms. Member syntax accesses the object's slot/delegation model: `object.name` performs ordinary slot lookup. Indexed syntax invokes the indexing protocol: `object[key]` lowers to `object.at(key)`. Indexed access is **not** dynamic slot access. In particular, `object["foo"]` is not defined to be equivalent to `object.foo`; the two expressions may return completely different values. An object does not automatically become indexable merely because it has slots — `object[key]` works only according to the `at` protocol implemented or inherited by that object.

Indexed read:

```js
receiver[index]
```

lowers conceptually to:

```js
receiver.at(index)
```

Indexed write:

```js
receiver[index] = value
```

lowers conceptually to:

```js
receiver.atPut(index, value)
```

The `=` in indexed assignment does **not** mean "modify an already-existing indexed entry". The syntax itself imposes no universal existence requirement on the key or index. Whether `atPut` creates a new indexed entry, replaces an existing one, extends a collection, requires an existing or in-range index, rejects the operation, or implements some other domain-specific behavior is defined by the receiver's `atPut` protocol. For example, a `Map` may define `map[key] = value` to create the key/value entry when the key is absent and replace its value when the key is already present, while an `Array` may require the index to be within a permitted range. User-defined objects may implement their own `at` / `atPut` behavior; the indexing syntax does not impose `Map` semantics on every indexable object.

Any object may support bracket syntax by implementing the corresponding messages.

Conversely, an indexable object remains an ordinary Protos object and may have ordinary slots, methods, delegation, and openness/frozen state in addition to indexed contents. Indexed contents are not automatically object slots, and a slot is not automatically indexed content. For example:

```js
map.description: "users"
map["description"] = user
```

may coexist and refer to entirely different things: the first operation concerns a slot of `map`, the second concerns `map`'s indexing protocol. Likewise, adding a slot named `foo` does not imply that `object["foo"]` will return that slot; only the receiver's `at` implementation determines the result. Indexability is protocol-based behavior, not a special object kind.

Indexed slot creation is forbidden. `:` is specifically the slot-creation operator and cannot be applied to an indexed target:

```js
object[index]: value     // syntax error
object["foo"]: value     // syntax error
object.foo[index]: value // syntax error
```

There is no indexed equivalent of slot creation in Core v0.1 and no `atCreate`-style protocol. Indexed mutation is expressed solely through `atPut`. `:` operates on the slot model while `[]` operates on the indexing protocol, and the two mechanisms remain distinct. There is no automatic relationship between String-valued indexes and slot names: `object["foo"]: value` must not be interpreted as `object.foo: value`.

The bracket forms do not bypass normal message lookup, mutability rules, or error signaling. The meaning of an index, accepted index types, bounds behavior, and storage semantics are defined by the receiver's protocol.

Evaluation order is left-to-right. For:

```js
getReceiver()[getIndex()] = makeValue()
```

the runtime evaluates `getReceiver()`, then `getIndex()`, then `makeValue()`, and finally performs the `atPut` message send.

The selector names `at` and `atPut` are part of the Core v0.1 indexed-access protocol.

The slot openness rules that govern `:` — for example, creating a slot on an object that does not permit slot creation fails according to the existing semantics — do not automatically apply to `atPut`. `atPut` is an ordinary protocol operation; its behavior is defined by the receiver/protocol and any existing mutability or frozen rules. This revision does not redesign `open`, `closed`, or `frozen` semantics.

These are valid:

```js
foo: value
object.foo: value
object[index].foo: value

foo = value
object.foo = value
object[index] = value
object[index].foo = value
object.foo[index] = value
```

These are syntax errors:

```js
object[index]: value
object["foo"]: value
object.foo[index]: value
```

`object.foo` and `object["foo"]` are not equivalent unless the object's own `at` implementation deliberately makes them behave that way. Reflection facilities, if they provide dynamic slot access or creation, remain separate from `[]`.
## Map Construction and Initial Association Definition

The `%{...}` syntax defined by `../PROTOS_GRAMMAR.md` constructs initial
association state for a standard Map. It is not syntactic sugar for repeated
post-construction indexed assignment and does not dispatch `atPut` while
establishing those initial associations.

Construction first resolves the ordinary identifier `Map` in the enclosing
activation. It then performs ordinary `call` lookup on the resulting object.

That selected behavior is eligible for `%{...}` construction only when it is
the canonical standard Map factory behavior selected from the canonical
standard Map home, either directly or through ordinary inheritance/delegation.
An object that delegates to the canonical Map and inherits that factory behavior
is therefore eligible. A nearer arbitrary `call` override is not eligible.
Copying or locally defining equivalent-looking factory behavior does not make
that nearer behavior canonical.

Ineligible construction signals `Error` before executing the selected custom
`call` behavior and before evaluating any source entry.

For an eligible receiver, the canonical standard Map factory is invoked exactly
once with zero arguments. It produces a fresh open standard normal Map whose
delegation parent is the actual factory invocation receiver. Consequently an
ordinary prototype that delegates to the canonical Map and inherits its standard
factory behavior composes with `%{...}` construction while retaining itself as
the parent of the new Map.

Each source entry is processed strictly in source order:

1. evaluate the key expression exactly once in the enclosing activation;
2. evaluate the value expression exactly once in the enclosing activation;
3. only after both evaluations complete normally, define one initial
   association in the constructed Map.

Initial association definition uses the normal standard Map query-selection law
but does not perform `atPut` dispatch.

For each initial association, the exact evaluated key is the query key. Its
ordinary standard-Map `hash` result is computed exactly once. Existing initial
associations whose recorded hash differs are skipped without equality
comparison. Equal-hash candidates are considered in their existing
source/insertion order, and each comparison is the directed ordinary equality
query:

```text
queryKey == storedRepresentativeKey
```

The first canonical `true` result denotes an already-defined initial key and
construction signals `Error`. The existing representative key and value remain
unchanged. A canonical `false` result continues to the next equal-hash
candidate. Invalid hash or equality results, Errors, suspension, cancellation,
non-local return, or other control transfer propagate according to their
ordinary semantics.

If no existing initial association matches, the constructed Map records the
exact query key as the representative key, together with the hash already
computed for that query and the evaluated value.

Therefore duplicate/equal source keys are a construction-time conflict rather
than replacement:

```protos
%{
    "name": "first"
    "name": "second"
}
```

signals `Error`; it does not produce a Map whose `"name"` value is `"second"`.

The value expression is evaluated before the hash/equality search for that
entry. Effects already completed by factory lookup/invocation, earlier entries,
the current key expression, or the current value expression are not rolled back
when later hashing, equality, duplicate detection, or another transfer aborts
construction.

Construction performs no compile-time duplicate-key rejection. Equality remains
behavioral and may depend on runtime values, so duplicate detection is the
runtime standard Map query law defined above.

After construction, ordinary indexed assignment is unchanged:

```protos
map[key] = value
```

continues to lower to ordinary `atPut(key, value)` dispatch and may replace an
already-matching standard Map entry according to the existing Map mutation
contract. Construction-time initial definition and later indexed mutation are
therefore intentionally distinct operations.

No Association object is materialized or exposed by construction. Key/value
pairing is source structure consumed by the construction operation, not a new
Core value family or public protocol.

`IdentityMap` remains a distinct explicit factory whose identity-key law is
unchanged. D136 does not make `%{...}` select `IdentityMap`, infer a keyed
collection type from context, add generic `%Factory{...}` syntax, or change Map
matching semantics.

## Standard Array Indexed Semantics

### Standard Array construction through ordinary invocation

The standard prelude `Array` object delegates directly to `Object`.

The standard prelude `Array` object specializes the ordinary polymorphic
invocation protocol as an Array factory.

A call:

```js
Array()
Array(a)
Array(a, b, c)
```

creates a fresh **open standard Array** whose receiver-owned indexed elements
are exactly the supplied positional argument objects, in order.

There is no argument-type overload. In particular:

```js
Array(3)
```

creates a one-element Array whose element at index `0` is the exact supplied
Integer object `3`. It does **not** mean "create an Array of length 3".
Core v0.1 defines no implicit length constructor, fill constructor, or
numeric-special constructor behavior.

The factory is shallow: argument objects are neither cloned nor frozen. The new
Array contains the exact supplied object references.

Each successful invocation creates a fresh Array identity, including
zero-argument invocation. Two empty Arrays created by separate calls are not
identical under `===`.

The created Array's delegation parent is the object whose standard Array-factory
invocation behavior was selected as the invocation receiver. Therefore the
ordinary prototype mechanism composes with Array construction:

```js
MyArray: Array {
    label: "custom"
}

values: MyArray(10, 20)
```

`values` owns standard Array indexed state, while its delegation parent is
`MyArray`. `MyArray` itself does not acquire indexed state merely by delegating
to `Array`.

This rule does not weaken the standard Array receiver-domain invariant.
Inheriting or copying ordinary indexed operations such as `at`, `atPut`,
`size`, or `each` still does not confer indexed state on their receiver.
The Array-factory invocation behavior is different: it creates a **new** object
with standard Array state and does not mutate or reclassify the invocation
receiver.

The standard Array factory does not send `init` to the created Array and does
not use `Object`'s default child-plus-`init` construction path. This is an
ordinary specialization of the existing polymorphic invocation protocol, not a
second construction syntax or callable category. A prototype that wants
different construction behavior may specialize its ordinary invocation
behavior in the same way any other object may.

Argument expressions and call spread are evaluated before invocation under the
existing left-to-right call rules. Once invocation begins, standard Array
construction performs no user callback, conversion, equality, hashing,
iteration, or hidden suspension.

The Array construction source syntax defined by `../PROTOS_GRAMMAR.md` is not a
second construction mechanism. It mandatorily lowers to the corresponding
ordinary call:

```text
[]       -> Array()
[a, b]   -> Array(a, b)
```

Consequently the standard factory semantics in this section apply only when
ordinary lookup selects the standard `Array` factory, or an object inheriting
that ordinary factory behavior. Shadowing `Array` affects bracket construction
exactly as it affects an explicit `Array(...)` call; a non-invokable selected
value fails by the ordinary invocation rules.

A standard `Array` is an identity-bearing object with receiver-owned indexed
element state. Its indexed contents are distinct from its ordinary local slots,
in the same sense that standard Map entries are distinct from ordinary slots.

At any observation point, an Array's indexed state is a finite dense sequence of
element references with logical indices:

```text
0, 1, 2, ... length - 1
```

Core v0.1 defines no holes and no negative-from-end indexing for standard
Arrays.

The standard indexed read:

```js
array.at(index)
```

requires `index` to be a semantic `Integer`. Any Integer family is accepted
according to its mathematical Integer value; no Float-to-Integer conversion,
String parsing, truncation, wrapping, or host-sized coercion is performed. The
Integer must satisfy:

```text
0 <= index < current Array length
```

Otherwise the operation signals an `Error`. A successful `at(index)` returns the
exact element object stored at that position.

The standard indexed update:

```js
array.atPut(index, value)
```

has the same Integer and bounds requirement and replaces exactly the existing
element at that index. Standard `Array.atPut` does not append, grow the Array,
create a hole, shift elements, or otherwise change the Array's indexed length.
A successful call returns the exact `value` object supplied to it.

Consequently bracket syntax has the same standard Array behavior:

```js
array[index]          // standard array.at(index)
array[index] = value  // standard array.atPut(index, value)
```

while retaining the existing syntax-level rule that indexed assignment itself
evaluates to `value`.

Standard Array indexed behavior operates only on an original receiver that owns
standard Array indexed state. Delegation, copying, aliasing, composition, or
otherwise obtaining a standard Array method does not confer Array element
storage on an ordinary object and does not redirect the operation to an
ancestor's Array state. An incompatible receiver signals an `Error` before
performing Array-indexed work.

Array element replacement follows the ordinary object state boundary:

```text
open Array
    -> existing elements may be replaced

closed Array
    -> existing elements may still be replaced

frozen Array
    -> element replacement is prohibited
```

Closing or freezing remains shallow and does not close or freeze element
objects. For `atPut`, a receiver already frozen when the standard method begins
signals an `Error` before index validation or element mutation. A closed Array
still validates the index and may replace the existing element.

Read-only `at` remains available on open, closed, and frozen Arrays.

This section defines the standard semantics of Array indexed state and the
already-existing `at` / `atPut` protocol. The construction syntax owned by
`../PROTOS_GRAMMAR.md` adds no second Array literal/runtime category, constructor
API, insertion/removal selectors, slicing, negative indexing, automatic growth,
or second collection hierarchy. Such facilities require their own explicit
contracts if standardized later.

Standard Array `==` and `hash` remain governed by the existing Core default:
without an explicit user override, Arrays use semantic object identity and
`identityHashOf`; element contents are not traversed merely for equality or
hashing.

### Standard Array size

The standard `Array.size` operation returns a semantic `Integer` equal to the
receiver's current indexed element count.

For an Array whose indexed positions are:

```text
0, 1, 2, ... length - 1
```

the result is exactly the mathematical Integer `length`. Because this
Core-standard result is specified simply as an `Integer`, the Numeric Model's
general standard-result rule makes it an ordinary unbounded Integer. Host-size
overflow, wrapping, saturation, or truncation must not alter the result.

`size` is a read-only observation. It does not invoke element behavior, does not
traverse or copy the elements merely to establish the semantic result, and does
not mutate the Array. It is available for open, closed, and frozen Arrays.

The existing standard Array receiver-domain rule applies: an object that merely
delegates to an Array or obtains the standard `size` behavior does not thereby
own Array indexed state.

### Standard Array iteration

The standard iteration selector for `Array` is:

```js
array.each(block)
```

`block` must be invokable through the ordinary polymorphic invocation protocol. It need not be a Closure: any value that an ordinary parenthesized call can invoke is accepted. Standard `Array.each` validates this callability after ordinary receiver and argument evaluation and standard Array receiver validation, but before establishing the iteration snapshot or invoking callback behavior. The standard operation then invokes `block` through ordinary polymorphic invocation once with one argument for each Array element.

At the start of `each`, after ordinary receiver and argument evaluation and
after standard Array receiver validation, the operation establishes a shallow
logical snapshot of the receiver's current indexed element sequence. Snapshot
order is ascending Array index order:

```text
0, 1, 2, ... snapshotLength - 1
```

Each snapshot element is the exact object stored at that Array position at the
snapshot point. `each` then invokes `block` once for every snapshot element, in
that order, passing only that element object as the callback argument.

If every callback returns normally, `each` returns the original Array receiver.

The snapshot is shallow. It does not clone, freeze, or otherwise isolate element
objects. Mutating a mutable element through any ordinary reference remains
ordinary mutation of that element and is visible normally.

Replacing an Array element after the snapshot has been established does not
change the value visited for that snapshot position. Likewise, any future
standard operation that changes Array length cannot retroactively add or remove
visits from an already-established `each` snapshot unless that future operation
is explicitly specified to do so.

The callback may replace elements of the same Array through `atPut` whenever
that mutation is otherwise permitted by the Array's open/closed/frozen state.
Such replacement does not alter the current iteration snapshot. Mutations of
other objects likewise remain ordinary effects.

If a callback reaches an explicit suspension point, the Actor may execute other
runnable Actor-local work. Other work may read or mutate the Array according to
the ordinary Array and Actor rules; `each` introduces no Array-wide lock,
mutation prohibition, or hidden scheduling dependency. When the callback
resumes, the current `each` invocation continues with its already-established
snapshot.

If a callback signals an error or exits the `each` invocation through an
ordinary non-local control transfer, no later snapshot element is visited.
Effects already completed by earlier callbacks are not rolled back.

The standard Array receiver-domain rule applies: inheriting, copying, composing,
aliasing, or otherwise obtaining the standard `each` behavior does not confer
Array indexed state on an incompatible receiver.

An implementation need not allocate an eager copied Array merely to implement
the snapshot. Persistent element storage, versioned views, copy-on-write state,
or another representation is valid when the observable shallow-snapshot
behavior is identical. Arrays that are never iterated pay no semantic cost for
iteration snapshots.
## No Core Array parallel operations

The standard Array prototype defines no parallel algorithm selectors
(`parallelMap`, `parallelFilter`, `parallelFindIndex`, `parallelReduce`,
`parallelSort`, `parallelEach`, or similar). Parallel Array algorithms are
Standard Library facilities of `std:collections/Array` built on the isolated
parallel substrate; their Core placement boundary is owned by
`../concurrency/PARALLEL_EXECUTION.md` §71.6 and §71.6E.

# Actor-suspension integration for Map comparison scopes

### Map comparison scopes and Actor-local suspension

The language-level Map comparison restriction composes with Actor reentrancy at
explicit suspension points.

A task that suspends inside a standard normal-`Map` key equality comparison
releases the Actor execution segment in the ordinary way, so other runnable
Actor-local work may execute. It does **not** release the Map-specific
keyed-entry mutation restriction associated with that in-progress comparison.

Consequently, during the suspension:

```text
other task reads same Map
    -> permitted

other task mutates unrelated Map
    -> permitted

other task mutates same Map keyed-entry state
    -> Error before mutation
```

This is intentionally narrower than Actor-wide exclusion and intentionally
different from task-local dynamic error handlers. The comparison restriction
protects one mutable Map's in-progress keyed search and is therefore visible to
other tasks that attempt to mutate that same Map. Error-handler frames instead
control which task catches an error and remain private to their task.

No task waits for a comparison scope as part of this rule. The Actor remains
free to schedule unrelated work, and conflicting Map mutation fails rather than
introducing hidden blocking or lock acquisition.

If the comparison's suspended task is later cancelled, ordinary cancellation
resumption and unwind release the comparison scope. If it remains suspended
indefinitely, the same Map may remain mutation-restricted indefinitely; this
does not prevent unrelated Actor-local work or operations on unrelated Maps.

## Conditional Protocol and Truthiness

The complete standard Boolean protocol, including canonical receiver-domain,
callback-selection/validation timing, exact results, strict `and`/`or` Boolean
result validation, Error/control propagation, Future behavior, suspension
behavior, and the absence of language-wide truthiness, is owned by §16
**Booleans** above.

This section records only the general dispatch consequence: the selectors
`ifTrue`, `ifFalse`, `and`, and `or` are ordinary messages. Any object may define
or override them with its own behavior. Such customization does not make the
object a semantic Boolean and does not establish a language-wide truthiness
conversion. Values such as `0`, `""`, `null`, arrays, and arbitrary objects are
therefore neither inherently truthy nor inherently falsy; absent a matching
custom or inherited message, ordinary lookup fails in the usual way.

The syntax and mandatory lazy lowering of `&&` and `||` are owned exclusively by
`../PROTOS_GRAMMAR.md`.

## Numeric Equality Across Families

Numeric semantic equality compares mathematical numeric value across numeric families.

Examples:

```js
1 == 1.0               // true
```

This does **not** imply conversion of either operand into the other's numeric family. Equality must not introduce rounding merely to perform a comparison.

For numeric values, cross-family equality is symmetric:

```text
a == b  iff  b == a
```

when both operations complete normally.

Implementations must compare exactly enough to avoid false equality caused by lossy conversion. For example, an arbitrary-precision Integer must not be rounded to Float merely to compare it with a Float.

Semantic identity remains stricter:

```js
1 === 1.0               // false
```

For numeric values, `===` includes the semantic numeric family in identity. Equal mathematical value across distinct numeric families does not imply identity.

This yields the general distinction:

```text
==   compares numeric value
===  compares numeric value plus semantic numeric family
```

Special floating-point cases such as NaN and signed zero are specified separately.


### Numeric hash coherence

Number-family values provide standard `hash` behavior specialized for numeric
semantic equality rather than inheriting `Object`'s identity-based default hash.

For all Core numeric values `a` and `b`:

```text
if a == b:
    a.hash == b.hash
```

This guarantee applies across numeric families. In particular:

```text
1.hash == 1.0.hash
0.0.hash == (-0.0).hash
```

whenever the corresponding numeric `==` comparison is true.

The semantic hash input for a finite Number is its exact mathematical numeric
value, not its semantic numeric family, storage width, signedness, boxing,
machine representation, or source spelling. Therefore an Integer and a Float
that compare equal numerically must enter the same normal-`Map` hash class even
though they are not semantically identical under `===`.

Float signed zero has one normal numeric hash class because `0.0 == -0.0` is
true, despite the existing identity distinction between the two zeros.

Core Float NaN values have one standard normal-hash class within an execution.
This is not an equality claim: NaN remains unequal under `==`, including to
itself. The canonical NaN hash requirement only prevents IEEE payload, signaling
state, boxing, or host representation from becoming observable through the
standard hash protocol.

The exact Integer returned by standard numeric `hash` is intentionally not fixed
across separate executions. An implementation may salt or randomize numeric
hashing per execution, and unequal numeric values may collide. Within one
execution, however, the result for an immutable numeric value is stable and the
cross-family equality implication above is mandatory.

Standard numeric `hash` must not be implemented as `identityHashOf(this)`,
because semantic numeric identity distinguishes some values that numeric `==`
intentionally equates. `IdentityMap` remains unaffected and continues to use
`identityHashOf` together with `===`.

## Float Special Values and Identity

`NaN` is a special semantic value of the `Float` family, not a language-level singleton object analogous to `null`.

Different IEEE 754 NaN bit patterns, payloads, and NaN sign bits do not create distinct Core language-level semantic values. Core v0.1 has one semantic NaN value in the `Float` family. An implementation may use any convenient NaN representation internally, and Core code cannot observe or depend on an internal NaN payload or sign bit.

Consequently:

```js
a: someNaNProducingOperation()
b: someOtherNaNProducingOperation()

a == b    // false
a === b   // true
```

Numeric equality follows IEEE-style NaN behavior: a NaN is not numerically equal to any value, including another NaN.

Numeric semantic identity treats all NaN values of the same semantic Float family as the same semantic special value, independent of runtime payload, sign bit, allocation, boxing, or host representation.

`NaN` need not be a reserved literal or a global singleton binding. Standard-library protocol may expose an ordinary way to obtain it, for example:

```js
Float.nan
```

Similarly, infinities are special Float values rather than new language literals. A standard library may expose ordinary protocol such as:

```js
Float.infinity
Float.negativeInfinity
```

`null` remains fundamentally different:

```text
null
    canonical singleton language object

NaN
    special semantic value of Float
    potentially many runtime representations
```

## Float Signed Zero Semantics

IEEE-style signed zero is semantically observable in the `Float` family.

Numeric equality ignores the distinction:

```js
0.0 == -0.0    // true
```

Numeric semantic identity preserves it:

```js
0.0 === -0.0   // false
```

The sign of zero is therefore part of Float semantic identity even though it does not affect numeric equality.

This distinction matters because signed zero can influence later floating-point behavior, for example the sign of infinity produced by reciprocal-style operations:

```js
1.0 / 0.0     // +Infinity
1.0 / -0.0    // -Infinity
```

The general rule is:

```text
==   compares numeric value and ignores the signed-zero distinction
===  preserves the signed-zero distinction within Float
```

This rule is semantic and must not depend on boxing, allocation, host references, or implementation-specific representation.

### Exact call-spread semantics

Core v0.1 call spread:

```js
f(...values)
```

accepts only an original receiver that owns standard `Array` indexed state.
Delegation, copying, composition, or user-defined `at` / `size` / `each`
behavior does not make an otherwise incompatible object spreadable.

The spread operand expression is evaluated exactly once at its ordinary
left-to-right argument position. After successful standard Array receiver
validation, the spread operation captures a shallow logical snapshot of the
Array's current indexed element references in ascending index order:

```text
0, 1, 2, ... size - 1
```

Those captured element objects are appended, in that order, to the outgoing
positional argument vector.

The snapshot is established at the point where the spread argument itself is
evaluated. Later argument expressions may mutate the source Array when ordinary
state rules permit it, but those later mutations do not alter the argument
objects already contributed by that spread.

For example, in:

```js
f(...values, mutate(values))
```

the spread captures `values` before `mutate(values)` is evaluated, because
argument evaluation remains strictly left-to-right.

Spread capture is shallow. It does not clone, freeze, or otherwise transform
the element objects. If an element is a mutable object, the outgoing argument
vector contains that same object reference.

Call spread performs no user-message iteration. In particular, it does not
invoke `each`, `at`, `size`, an iterator method, conversion behavior, or any
other user-defined protocol while extracting standard Array elements. A
non-Array operand signals an `Error` after the operand expression has been
evaluated but before any later argument expression is evaluated.

An empty standard Array contributes zero positional arguments.

The source Array may be open, closed, or frozen; spread is read-only. No Array
mutation, lock, suspension point, iterator object, or hidden callback is
introduced merely by expansion.

This Core rule intentionally does not standardize a general iterable/spreadable
protocol. A future generic iteration protocol may generalize call spread only
through an explicit normative revision defining traversal order, failure,
effects, suspension, mutation visibility, and interaction with existing
collection protocols.

### Rest argument collections are frozen Arrays

A rest parameter is bound to a **fresh frozen standard `Array`** containing
exactly the remaining caller-supplied positional argument objects, in order.
Distinct rest bindings created by different invocations are distinct objects,
including empty rest Arrays.

Because rest bindings are standard Arrays, their read behavior follows the
standard Array contracts for `at`, `size`, and `each`. Because they are frozen,
standard `atPut`, ordinary slot mutation, slot creation/removal, and any other
mutation prohibited by frozen-object semantics fail normally.

Freezing is shallow: mutable argument objects are not frozen merely because a
reference to them occurs in a rest Array. Parameter bindings and rest Arrays
therefore refer to the same supplied argument objects; no deep-copy or alias
isolation is introduced.

The complete caller-supplied positional vector may still exist as
implementation-internal activation/binding state, but Core exposes no ambient
guest collection for that complete vector. This uses the existing Array and
frozen-object mechanisms for rest bindings rather than defining a second
privileged argument-collection object model.

## Numeric Model

Core v0.1 defines one portable numeric model. Numeric family is semantic state, not an implementation representation, and no arithmetic result may depend on host integer width, boxing, tagging, or storage strategy.

### Standard numeric families and prototypes

The Core v0.1 numeric semantic families are exactly:

```text
Number                         common numeric prototype; not a concrete value family
├── Integer                    common exact mathematical-integer domain; not a concrete value family
│   ├── SmallInteger           concrete family: Integers in the signed-64 range
│   └── BigInteger             concrete family: Integers outside the signed-64 range
├── Fraction                   concrete family: exact non-integral rationals
└── Float                      IEEE 754-2019 binary64 family
```

`Number`, `Integer`, `SmallInteger`, `BigInteger`, `Fraction`, and `Float` are
the standard prototype objects corresponding to that hierarchy and are
published as Prelude bindings of those names. `Integer`, `Fraction`, and
`Float` delegate immediately to `Number`; `SmallInteger` and `BigInteger`
delegate immediately to `Integer`. In this specification an **exact real** value
is a semantic Integer or a semantic Fraction.
Numeric-family membership is not conferred by delegation: an ordinary object
that delegates to one of these prototype objects is still an ordinary object
unless a separate normative factory or operation creates a numeric semantic
value.

The signed-64 range is the closed mathematical interval
`[-9223372036854775808, 9223372036854775807]`, that is `[-2^63, 2^63 - 1]`.
Every semantic Integer value belongs to exactly one concrete Integer family,
determined by its exact mathematical value alone (**canonical
normalization**): a value inside the signed-64 range is a `SmallInteger`, and
every other value is a `BigInteger`. The immediate delegation parent of a
semantic Integer value is the `SmallInteger` or `BigInteger` prototype of its
concrete family, so `42.parent() === SmallInteger` and
`9223372036854775808.parent() === BigInteger`. Behavior common to all Integers,
including the standard Integer arithmetic, recognition, and conversion
behaviors, is owned by `Integer` and reached through ordinary delegation;
the concrete-family prototypes add only their own `recognizes` behavior.

A semantic Fraction value is an immutable exact rational number in
**canonical form** `n/d`: its numerator `n` and denominator `d` are semantic
Integers (of either concrete family), `d > 1`, and `gcd(|n|, d) = 1`. The
canonical form is unique for each rational value, so no Fraction has a zero or
negative denominator, and no Fraction denotes zero or any other mathematical
integer. **Exact rational normalization** of an exact rational result is: a
zero divisor signals an `Error`; an integral result is the canonically
normalized Integer of that value (a `SmallInteger` or `BigInteger`); every other
result is the Fraction in canonical form. Every standard operation specified
below to return an exact rational result applies this normalization, so for
example `6 / 3` is the `SmallInteger` `2`, `2 / 4` and `1 / 2` are the same
Fraction value, and `0 / 7` is the `SmallInteger` `0`. The immediate delegation
parent of a semantic Fraction value is the `Fraction` prototype, so
`(1 / 2).parent() === Fraction`; standard Fraction arithmetic and negation are
reached through ordinary delegation, and the standard `Number` behaviors apply.

Fraction values arise only from standard exact arithmetic. This revision
defines no Fraction literal and no Fraction construction or conversion factory:
`Fraction` has no standard `call` behavior, and an ordinary object delegating to
`Fraction` never becomes a Fraction. A Fraction exposes no standard selector for
its numerator or denominator in this revision.

D197-C also establishes `Complex` as a future concrete numeric family under
`Number`. It is not part of this revision: no prototype, binding, literal, or
operation for it is defined here, and no standard operation in this revision
produces it.

Core v0.1 defines no standard width-specific numeric semantic family, prototype, or
prelude binding. Width- or range-specific representations may exist inside an
implementation or at an interop, FFI, or binary boundary, but they are not Core
numeric semantic values merely because they preserve a machine width. This
revision does not define a guest-visible width-specific value model or a public FFI
surface for such representations.

Core v0.1 also defines no additional standard numeric prototypes named `Int` or
`UInt`. Such names, if present in a library or program, are ordinary bindings
and do not denote a portable Core numeric family unless a later normative
revision explicitly standardizes them.

In this specification, `Integer` means a value of the common unbounded
Integer domain, that is, a value of either concrete Integer family. Whenever a
Core-standard operation's normal result or Future resolution is specified as an
`Integer`, that result is an unbounded semantic Integer whose concrete family is
selected by canonical normalization of its exact value. Input domains that
require `Integer` likewise accept exactly that common domain, regardless of
concrete family, unless their normative owner explicitly states a different
protocol.

`Integer` denotes mathematical integers with unbounded precision. Ordinary
Integer arithmetic therefore has no semantic integer overflow. A conforming
implementation must grow representation as required; allocation failure or a
runtime resource limit is not permission to wrap, saturate, truncate, or expose
the host's integer width as a numeric result.

### Concrete Integer family versus internal representation

The concrete Integer families are observable semantics; their physical
representation is not.

Observable: `SmallInteger` and `BigInteger` are distinct public families. Which
one a value belongs to depends only on its exact mathematical value and the
signed-64 boundary above. `parent()` and the `recognizes` behaviors observe it.
An arithmetic, conversion, or other standard result is normalized to its
concrete family: a result that leaves the signed-64 range is a `BigInteger`,
and a result that returns into it is a `SmallInteger`. There is no wraparound,
saturation, or implicit promotion to `Float` at the boundary.

Not observable: tagged integers, primitive machine words, boxed host integers,
arbitrary-precision host payloads, limb arrays, deferred materialization,
compilation strategy, and similar categories are implementation
representations only. They are not part of the portable contract, and none of
them by itself changes the concrete family of a value.

An implementation may change the physical representation of an Integer at any
time, including across arithmetic, constant folding, Actor transfer,
specialization, deoptimization, or garbage collection, provided the change is
unobservable. In particular, internal representation must not change:

- the value's concrete family or standard prototype/delegation behavior;
- ordinary lookup or dispatch;
- `==`, `!=`, `===`, `!==`, `hash`, or `identityHashOf`;
- reflection or family/prototype observations provided elsewhere by Core;
- standard transfer/copy behavior between Actors or `P` workers;
- any standard external encoding/decoding semantics.

Core v0.1 introduces no serialization format for an internal integer
representation. Any standard transfer or encoding rule that preserves a numeric
value operates on its semantic family and semantic value, never on a physical
storage category; a transferred Integer delegates to the concrete-family
prototype of its destination, and a transferred Fraction is the same canonical
rational value delegating to the `Fraction` prototype of its destination.

Integer-only bit-manipulation or explicit wrapping protocols are not added by
this section. Libraries may define such ordinary messages, but portable Core
v0.1 code cannot infer an unstated wrapping, widening, or host-word behavior from
the standard arithmetic rules below.

### Numeric literals

- A leading sign is never part of a numeric literal.
- Decimal integer literals use digits `0` through `9`.
- Leading zeroes are allowed and have no radix significance; for example, `007` is decimal `7`.
- Hexadecimal integer literals use `0x` or `0X`.
- Binary integer literals use `0b` or `0B`.
- Octal integer literals use `0o` or `0O`.
- `_` may be used as a visual separator between digits; it cannot appear at the beginning or end of a digit sequence and cannot appear consecutively.
- Radix-prefixed literals produce ordinary `Integer` values.
- Decimal literals containing a decimal point or exponent produce `Float` values.
- A `.` belongs to a decimal numeric literal only when it is immediately followed by a decimal digit: `1.0` is a `Float` literal; `1.` and `.5` are not numeric literals as complete source sequences. The lexer tokenizes `1.` as `INTEGER("1")` followed by a `.` token and `.5` as a `.` token followed by `INTEGER("5")`, so for example `1.to(10)` tokenizes as `INTEGER("1")` `.` `IDENTIFIER("to")` `(` `INTEGER("10")` `)`. This does not make either complete sequence necessarily a lexical error; whether the resulting token sequence is syntactically valid is the parser's responsibility.
- Decimal exponents use `e` or `E`, optionally followed by `+` or `-`, and require at least one exponent digit.
- Hexadecimal, binary, and octal `Float` literals are not supported in Core v0.1.
- A `.` immediately following a complete radix-prefixed Integer literal is a structural `.` token when it is not immediately followed by a decimal digit; for example, `0b10.foo` tokenizes as `INTEGER("0b10")` `.` `IDENTIFIER("foo")` and `0xFF.toString()` tokenizes as `INTEGER("0xFF")` `.` `IDENTIFIER("toString")` `(` `)`. When the `.` is immediately followed by a decimal digit, the source sequence is an attempted unsupported radix Float literal and is a lexical error; for example, `0b10.5`, `0o17.25`, and `0x1.8` are lexical errors rather than being split into `INTEGER` `.` `INTEGER` tokens.
- Numeric type suffixes such as `L`, `f`, or `d` are not supported.
- `NaN` and `Infinity` are not special numeric literal syntax.
- Once a source sequence has begun as a numeric literal, if its immediately adjacent continuation makes that numeric form malformed or creates an invalid numeric/identifier boundary, the lexer reports a lexical error. It must not split the malformed sequence into otherwise valid tokens in order to recover it.
- A radix prefix (`0x`, `0X`, `0b`, `0B`, `0o`, `0O`) must be followed by at least one valid digit for that radix. Once a radix prefix has been recognized, an invalid digit or identifier-like continuation does not cause the lexer to fall back to an `INTEGER("0")` token plus another token; for example, `0x`, `0xG`, `0b2`, and `0o8` are lexical errors.
- A `.` immediately following a complete radix-prefixed Integer literal is a structural `.` token unless it is immediately followed by a decimal digit; `0b10.5` is an attempted unsupported radix Float literal and is a lexical error, not `INTEGER("0b10")` `.` `INTEGER("5")`.
- Once `e` or `E` has begun the exponent part of a decimal numeric literal, the exponent must be complete; `2e`, `2e+`, and `2e-` are lexical errors.
- Invalid underscore placement inside or immediately adjacent to a numeric literal is a lexical error; for example, `1__2`, `1_`, and `0x_FF`.
- An identifier cannot begin immediately after a numeric literal without a lexical boundary; `123abc` is a lexical error, not `INTEGER("123")` followed by `IDENTIFIER("abc")`.
- Valid token boundaries remain valid and are not affected by this rule: punctuation, whitespace, structural delimiters, and operators may terminate a numeric token according to the existing lexical grammar. The decimal-point vs. member-access dot rules above are unchanged.

For example:

```js
255
007
0xFF
0b11111111
0o377
1_000
1.5
2e3
1.5e-3
```

denote the values above. Literal radix is syntactic only; every integer literal
denotes an ordinary unbounded `Integer` whose concrete family is selected by
canonical normalization of its value, never by its radix or spelled width.

### Explicit standard numeric conversion

The standard prototype objects `Integer` and `Float` specialize ordinary
polymorphic invocation as one-argument numeric conversion factories. Argument
evaluation and invocation otherwise follow `CALLABLES.md`. Each standard numeric
conversion requires exactly one positional argument and requires that argument
to be a semantic `Number`; wrong arity or a non-Number argument signals an
`Error`.

No standard numeric conversion sends a user conversion callback, performs
String parsing, invokes equality/hash behavior, or introduces a hidden
suspension point.

`Integer(value)` returns the same mathematical Integer when `value` is already
an Integer. When `value` is a Float, conversion succeeds only if the Float is
finite and its exact binary64 value is mathematically integral; the result is
that exact unbounded Integer, canonically normalized to its concrete family. NaN, either infinity, and a finite non-integral
Float signal an `Error`. A Fraction is never mathematically integral, so
`Integer(fraction)` signals an `Error`. `Integer` conversion never rounds or
truncates.

`Float(value)` returns the same semantic Float value when `value` is already a
Float, preserving signed zero, infinity, and the Core NaN semantic value. For
an Integer value, it returns the unique IEEE 754-2019 binary64 value obtained by
converting that exact mathematical integer using `roundTiesToEven`. Loss of
integer precision in this explicit conversion is therefore permitted and
normative, not an `Error`. A result beyond the finite binary64 range is the
correspondingly signed infinity. Integer zero converts to positive `0.0`.

For a Fraction value `n/d`, `Float(value)` returns the unique binary64 value
obtained by rounding the **exact rational quotient** `n/d` once using
`roundTiesToEven`; the numerator and denominator are never rounded separately.
Precision loss, overflow to the correspondingly signed infinity, subnormal
rounding, and rounding of a nonzero quotient to `+0.0` or `-0.0` according to
its sign are part of that result and do not signal an `Error`. Consequently a
Fraction whose components both lie beyond the binary64 range but whose quotient
is close to one converts to a finite Float near `1.0`, never to NaN.

`SmallInteger` and `BigInteger` are not conversion factories. The standard
`Integer` conversion behavior is reachable from them by ordinary lookup, but it
requires its exact canonical `Integer` owner as receiver, so invoking
`SmallInteger(value)` or `BigInteger(value)` signals an `Error`.

Outside the standard mixed exact-real/Float arithmetic rule below, these
conversion factories introduce no implicit numeric coercion. Standard mixed
arithmetic is defined using the existing `Float(Integer)` and `Float(Fraction)`
conversions, but that does not make `Float(x)` an implicit conversion for other
protocols.

### Standard arithmetic compatibility matrix

Standard numeric arithmetic uses the **original receiver and argument semantic
families** to determine the standard operation. For binary `+`, `-`, and `*`,
the complete Core v0.1 matrix is:

In this matrix and in the division rules below, `Integer` denotes the common
Integer domain: either concrete Integer family selects the same row, and an
exact `Integer` result is canonically normalized to its concrete family.

| receiver family | argument family | result |
| --- | --- | --- |
| `Integer` | `Integer` | `Integer`, exact |
| `Integer` | `Fraction` | exact real, exact rational rule below |
| `Fraction` | `Integer` | exact real, exact rational rule below |
| `Fraction` | `Fraction` | exact real, exact rational rule below |
| `Integer` or `Fraction` | `Float` | `Float`, operand-first binary64 rule below |
| `Float` | `Integer` or `Fraction` | `Float`, operand-first binary64 rule below |
| `Float` | `Float` | `Float`, binary64 rule below |

**Exact rational rule.** When both operands of standard binary `+`, `-`, `*`,
or `/` are exact real values and at least one is a Fraction, each Integer
operand participates as the rational with denominator one, the result is the
exact mathematical rational

```text
(a/b) + (c/d) = (ad + bc)/(bd)
(a/b) - (c/d) = (ad - bc)/(bd)
(a/b) * (c/d) = (ac)/(bd)
(a/b) / (c/d) = (ad)/(bc)
```

and that result is subject to exact rational normalization under **Standard
numeric families and prototypes**. No intermediate value is rounded, and the
result may be an Integer: for example `(1 / 2) + (1 / 2)` is the `SmallInteger`
`1`. Division by an exact zero signals an `Error`.

For standard binary `+`, `-`, `*`, and `/` with one exact real operand and one
semantic Float operand, convert the exact real operand to binary64 using exactly
the standard `Float(Integer)` or `Float(Fraction)` rule above, including
`roundTiesToEven` of a Fraction's whole exact quotient, signed infinity for
out-of-range magnitude, and positive zero for Integer zero. Then
apply the selected IEEE 754-2019 binary64 operation to the resulting Float
operands **in their original receiver/argument order**, with the normal binary64
operation rounding. The standard result is a semantic Float in both operand
orders. Conversion happens **before** arithmetic: implementations must not
perform exact mixed arithmetic followed by a single final rounding.

Consequently, rounding an Integer operand can discard information before the
operation; for example, mathematical `(2^53 + 1) + (-2^53.0)` produces `+0.0`.
An Integer whose magnitude converts to infinity participates as that infinity:
mathematical `2^1024 * 0.0` produces NaN, rather than exact zero; mathematical
`0.5 / 2^1024` produces `+0.0`. Mixed division by Float zero follows IEEE
binary64, not Integer/Integer division-by-zero Error behavior.

The result family is Float for either mixed operand order, but subtraction and
division preserve left/right order. Examples:

```js
1 + 2                 // Integer(3)
1 + 2.0               // Float(3.0)
2.0 + 1               // Float(3.0)
1 - 2.0               // Float(-1.0)
2.0 - 1               // Float(1.0)
```

This is a rule for the standard numeric operations, not an unconditional syntax
intrinsic. Ordinary overridable message dispatch, custom methods, lookup,
receiver-domain checking, evaluation order, and guarded-send fallback retain
their existing semantics.

The same-family rule also governs standard unary negation:

- Integer negation returns the exact Integer;
- Fraction negation returns the exact canonical Fraction of opposite sign;
- Float negation is the binary64 sign-negation operation described below.

The grammar-owned prefix `-` lowering inherits exactly that standard behavior.

### Division, integer quotient, and remainder

Standard `/` applies the same operand-first binary64 rule above to every
operand pair of one exact real value and one Float. The result is Float, with
original operand order preserved.

For the remaining operand pairs:

- `Float / Float` returns the IEEE binary64 result described below.
- `Integer / Integer` with nonzero divisor returns the exact mathematical
  rational quotient under exact rational normalization: an integral quotient is
  the canonically normalized Integer, including `BigInteger` promotion such as
  `-2^63 / -1`, and every other quotient is the canonical Fraction. It never
  returns a Float.
- every other pair of exact real operands follows the exact rational rule
  above.
- division by an exact zero (Integer zero) signals an `Error`.

A program that requires the binary64 approximation of an exact quotient
requests it explicitly with `Float(a / b)`, which rounds the whole exact
quotient once under the `Float(Fraction)` rule.

Core v0.1 standardizes integer quotient/remainder selectors `div` and `mod`, and
the grammar operator `%` has the same standard numeric result as `mod` for
Integer receivers.

For `div`, `mod`, and `%`:

- both operands must be `Integer` values; a Fraction or Float operand signals
  an `Error`;
- a zero divisor signals an `Error`;
- the mathematical quotient for `div` is truncated toward zero;
- `mod` and `%` return `a - (a div b) * b`, so a nonzero remainder has the sign
  of the dividend and its magnitude is less than the divisor magnitude; and
- the result is an exact `Integer`.

`Float` has no standard `div`, `mod`, or `%` numeric behavior in Core v0.1.

### Float format, precision, and arithmetic

`Float` has one fixed Core v0.1 semantic format. The Float semantic value set is
exactly IEEE 754-2019 `binary64` (double precision), with the language-level NaN
and signed-zero model specified in this document. The choice is part of Protos
semantics and is not implementation-defined.

Every finite Float has the precision and exponent range of binary64; positive
and negative zero, positive and negative infinity, and subnormal values are
required. Implementations must support gradual underflow and must not flush
subnormal operands or results to zero.

The standard Float behaviors corresponding to IEEE basic binary arithmetic
(`+`, `-`, `*`, and `/`) and unary negation operate as binary64. Each primitive
operation produces the result required by IEEE 754-2019 for those operands using
`roundTiesToEven` when rounding is required. The result of each such operation
is a Float value before any later Protos operation observes or consumes it.

An implementation may use wider registers, fused instructions, constant
folding, vector instructions, JIT specialization, or another internal strategy
only when the observable result is the same as the required sequence of
binary64 operations. Extra intermediate precision and contraction of separate
operations into a fused operation must not change a Protos result.

Core v0.1 exposes no mutable floating-point rounding mode and no ambient
floating-point status flags. Host thread-local floating-point environment state
must not change Protos results.

For IEEE basic Float arithmetic, overflow, underflow, division by zero, and
invalid floating-point arithmetic produce the corresponding binary64 infinity,
signed zero/subnormal, or NaN result rather than signaling a Protos Error merely
because the IEEE condition occurred.

### Numeric equality, identity, ordering, and hashing

The earlier **Numeric Equality Across Families**, **Numeric hash coherence**,
**Float Special Values and Identity**, and **Float Signed Zero Semantics**
sections remain the authoritative equality/identity/hash rules. The
mixed-arithmetic Float conversion rule does not apply to equality, ordering,
identity, or hashing.

For any two numeric values, standard `==` compares exact mathematical numeric
value across families as already specified. The comparison never converts an
Integer or Fraction to Float or otherwise rounds an operand. A finite Float
participates by its exact binary64 mathematical value, which is a dyadic
rational. Therefore, for example:

```js
1 == 1.0                         // true
1 === 1.0                        // false
9007199254740993 == 9007199254740992.0 // false
(1 / 2) == 0.5                   // true
(1 / 10) == 0.1                  // false: binary64 0.1 is not exactly 1/10
(1 / 2) === 0.5                  // false
(2 / 4) === (1 / 2)              // true
```

`===` remains non-overridable semantic identity and includes numeric semantic
family, with the previously specified Float signed-zero and NaN special cases.
Two Integers are identical exactly when they denote the same mathematical
value. Because the `SmallInteger` and `BigInteger` ranges are disjoint, equal
Integers always share their concrete family, so the concrete family never
splits an Integer value into distinct identities. Two Fractions are identical
exactly when they denote the same rational value; because the canonical form is
unique, separately produced equal Fractions are identical. A Fraction is never
identical to an Integer or Float. Physical representation is never a semantic
family and cannot change `===`.

The standard numeric ordering operators `<`, `<=`, `>`, and `>=` accept any two
semantic Number values, including distinct numeric families. They compare
without promotion or coercion:

- finite values are ordered by exact mathematical value, treating each finite
  Float as its exact binary64 value and each Fraction as its exact rational
  value;
- negative infinity is below every finite value;
- positive infinity is above every finite value;
- `-0.0`, `+0.0`, and exact-integer zero are equal for ordering;
- if either operand is Float NaN, every one of `<`, `<=`, `>`, and `>=` returns
  canonical `false`.

Consequently `1 < 1.5` is `true` and `(1 / 3) > 0.3333333333333333` is
`true`. Integer, Fraction, and Float ordering is cross-family by exact
mathematical value; it does not use mixed arithmetic's binary64 promotion.

The existing numeric hash coherence rule is unchanged and mandatory across all
these families: whenever numeric `a == b` is true, standard `a.hash` and
`b.hash` are the same mathematical Integer hash value for that execution.
Numeric family (Integer, Fraction, or Float) and internal representation must
not split equal numeric values into different normal-Map hash classes; for
example `(1 / 2).hash == 0.5.hash`.
`IdentityMap` continues to use `===` and `identityHashOf`, so distinct semantic
numeric families remain distinct identity keys even when ordinary numeric `==`
is true.

Standard numeric ordering with a non-Number argument signals an `Error`.
Standard numeric equality with a non-Number argument returns canonical `false`;
this is the Number-family specialization of ordinary semantic equality and does
not coerce the other object.

### Errors, effects, transfer, and optimization boundary

Every numeric failure explicitly specified above signals an `Error` according to
`ERRORS.md`. Core v0.1 introduces no more-specific mandatory numeric Error
prototype. Implementations must not expose a host arithmetic exception or
implementation-specific visible Error subtype as a portability requirement.

Receiver and argument expressions are evaluated before standard numeric
behavior begins under the ordinary call rules. Numeric validation, arithmetic,
comparison, hashing, and standard conversion execute no user callback and
introduce no hidden suspension point. If numeric validation or arithmetic
fails, earlier expression-evaluation effects remain completed and no numeric
result is produced.

Number values are immutable value objects. Actor transfer, `P` transfer, constant
folding, JIT specialization, unboxing, arbitrary-precision specialization, and
other optimizations may copy, share, inline, or change physical representation
only when semantic family, exact Integer value, exact binary64 Float value and
special-value distinctions, exact canonical Fraction value, result/error
behavior, equality, ordering, hashing,
identity, dispatch, and observable evaluation order remain exactly as specified.

Endianness is not a property of a numeric value. It belongs to binary encoding
and decoding. The same semantic numeric value may be represented as bytes using
ordinary encoding protocols such as `BigEndian` and `LittleEndian`; such
protocols must not expose an implementation-private physical Integer
representation.

For example:

```js
value.toBytes(BigEndian)
```

or equivalent buffer-oriented protocols. This follows the general rule that
semantic values are distinct from their external binary representation.


## String Indexing, Mutability, and Encoded Representations

`String` is an immutable Unicode text value whose exact semantic value is an
ordered sequence of Unicode scalar values.

Core String indexing and size operate on Unicode scalar values rather than
bytes, encoding code units, UTF-16 code units, or extended grapheme clusters:

```js
text.size
text[0]
```

conceptually correspond to scalar-count and scalar-at-index operations.

Default extended-grapheme segmentation is not a Core String indexing rule. The
Standard Library retains a separate default extended-grapheme segmentation
capability using the Unicode 17.0.0 default rules preserved by the language
decision. This Core section does not standardize the final public
module/prototype/view topology or selector spelling of that capability.

Core v0.1 does not standardize separate `String.graphemes()` or
`String.codePoints()` convenience protocols. Scalar indexing is already the
standard Core code-point access model; richer text views or segmentation
operations remain separate library capabilities.

Encoding conversion is separate from String indexing and is owned by
`../io/TEXT_IO.md`.

Because `String` is immutable, an operation that produces different text
produces a new `String` value. Core v0.1 does not standardize `uppercase()` or
`replace(...)` here; those require separate contracts.

Here `otherString` denotes another semantic String value. The standard
String-family `+` operation does not accept an arbitrary non-String object.

Efficient incremental text construction may be provided by separate mutable
library objects or buffer-oriented abstractions. Core v0.1 does not standardize
a `StringBuilder` binding, prototype, constructor, or protocol.

`Bytes` is a mutable raw byte sequence by default. Indexed access therefore
naturally follows the existing protocol:

```js
bytes[i]          // bytes.at(i)
bytes[i] = value  // bytes.atPut(i, value)
```

Encoded textual representations such as UTF-8 data, UTF-16 data, C strings,
memory-backed strings, or similar objects may be first-class values with their
own protocols.

Their mutability is not globally fixed by Core. An encoded representation may
expose `atPut` if mutation is meaningful and supported, or omit it if the
representation is immutable or read-only.

Therefore mutability is expressed behaviorally through supported messages
rather than through a universal collection mutability flag.

Examples of possible first-class representations include:

```text
UTF8EncodedString
UTF16EncodedString
CString
MappedText
```

These names are illustrative; Core v0.1 does not require this exact library
taxonomy.


### Exact standard String indexing semantics

For a String whose exact semantic value is the Unicode-scalar sequence `S`, the
standard `size` result is:

```text
String.size -> semantic Integer equal to length(S)
```

The result is an exact semantic `Integer`; no host code-unit count, encoded byte
length, grapheme count, host-sized representation, overflow, saturation, or
wrapping may replace that mathematical result.

The standard indexed read:

```js
text.at(index)
text[index]
```

requires `index` to be a semantic `Integer` and uses its mathematical Integer
value. No Float-to-Integer conversion, String parsing, truncation, wrapping,
modulo reduction, or host-sized coercion is performed.

The index must satisfy:

```text
0 <= index < length(S)
```

Otherwise the operation signals an `Error`.

A successful read returns a `String` whose semantic scalar sequence contains
exactly the single scalar `S[index]`. The returned String is not normalized,
case-folded, rewritten, encoded, or decoded.

Consequently:

```text
"😀".size()                 == 1
"😀"[0]                     == "😀"

"e\u{301}".size()          == 2
"e\u{301}"[0]              == "e"
"e\u{301}"[1]              == "\u{301}"

"👨‍👩‍👧‍👦".size()         == 7
```

The host representation must not leak into this model. In particular, a
supplementary-plane scalar remains one String position even when an
implementation stores it as a UTF-16 surrogate pair.

Standard String indexing behavior applies only to an original receiver that is
a semantic String value. Delegating to a String value or String prototype does
not confer String semantic membership. An incompatible receiver that invokes
the standard behavior signals an `Error` under the existing semantic-family
receiver rule.

`String` is immutable and Core defines no standard indexed mutation behavior
that changes a String in place. Bracket assignment does not acquire a hidden
String mutation primitive merely because bracket-read syntax is supported.
User-defined objects remain free to define their own ordinary `atPut` protocol.

This section defines the standard Core String indexing/size semantics. It does
not standardize normalization, locale-sensitive segmentation, collation,
text-editing protocols, or the final public API topology of the separate
Standard Library grapheme capability.



### Standard String concatenation with `+`

The standard String-family behavior for binary `+` concatenates two semantic
String values.

```js
"hel" + "lo"     // "hello"
```

The original receiver must be a semantic String value, and the right operand
must also be a semantic String value. No Number, Boolean, `null`, arbitrary
object, prototype, or other value is implicitly converted to String.

The result is the String whose Unicode-scalar sequence is exactly the receiver's
scalar sequence followed by the right operand's scalar sequence. Concatenation
performs no Unicode normalization, grapheme segmentation, locale transformation,
encoding, or decoding.

String values are immutable, so concatenation mutates neither operand. The
result obeys ordinary String value identity and equality. Consequently, when
the concatenated scalar sequence equals an existing String value's scalar
sequence, `===` observes the same String value semantics:

```js
("hel" + "lo") === "hello"   // true
```

The standard operation performs no user callback, textual coercion, `hash`,
`==`, Encoding operation, or hidden suspension.

This is a standard String-family specialization of the ordinary `+` message.
Ordinary lookup and overriding rules remain unchanged. Merely delegating to a
String value or String-family prototype does not make an incompatible receiver
a semantic String.

### Standard String aggregate concatenation with `concat`

The standard `String.concat` behavior is the ordinary variadic aggregate form
of strict String composition. It is a standard local selector on canonical
`String` and is reached by ordinary lookup. Selecting that standard behavior
does not confer String-family membership on the original receiver.

```protos
"a".concat()
"a".concat("b")
"a".concat("b", "c", "d")
"a".concat(...parts)
```

The original receiver must be a semantic String value. After the ordinary call
rules have produced the complete caller-supplied positional vector, every
supplied value must also be a semantic String value. Delegation to canonical
`String`, possession of similarly named behavior, or another structural
relationship does not satisfy either requirement. No Number, Boolean, `null`,
Bytes value, ordinary object, prototype, or other non-String value is implicitly
converted.

Ordinary call evaluation is unchanged. The call receiver/target is evaluated
first, then explicit argument and spread items are evaluated strictly
left-to-right, including ordinary standard spread extraction, before Closure
activation or parameter binding. Therefore all visible argument/spread effects
that complete normally have already occurred before the selected standard
`concat` behavior validates its receiver and supplied values. A failure during
receiver/argument evaluation or spread extraction prevents `concat` invocation
under the existing call rules.

Once standard `concat` begins, it validates the original receiver and supplied
values without invoking user behavior. Validation of supplied values follows
their positional order. A validation failure signals `Error`, mutates no input,
and exposes no partial String result.

On success, the result is the String whose exact Unicode-scalar sequence is the
receiver's scalar sequence followed, in positional order, by each supplied
String's scalar sequence. The operation performs no Unicode normalization,
grapheme segmentation, locale transformation, encoding or decoding, textual
conversion, equality/hash dispatch, callback, mutation, or hidden suspension.

Zero supplied values are valid. `s.concat()` therefore has exactly the scalar
sequence of `s` and is semantically identical to `s` under existing String value
identity. This does not require reuse of the same host object or physical String
storage.

Standard `concat` is **not** defined as repeated dispatch of `+`. Existing
standard binary `+` is unchanged, and `+` and `concat` are independent ordinary
selectors: defining, shadowing, or overriding one does not implicitly redefine
the other. When standard behavior is selected and `b` is a semantic String,
`a.concat(b)` and standard `a + b` denote the same String semantic value; this
is result equivalence only, not a lowering, callback, or dispatch-equivalence
rule.

Implementations may construct the result using flat allocation, pre-sizing,
ropes, slices, interning, storage sharing, or another representation strategy
provided every observable rule above is preserved. Core `concat` does not
standardize a mutable String builder, fragment tree, stream, or intermediate
representation.

### Exact String semantic value and identity

A Core `String` semantic value is exactly a finite sequence of Unicode scalar
values, in order. String semantic identity compares that sequence exactly.

Therefore two String values are semantically identical exactly when they contain
the same number of Unicode scalar values and the scalar value at every position
is the same:

```text
stringIdentity(a, b)
    = exactUnicodeScalarSequence(a) == exactUnicodeScalarSequence(b)
```

No Unicode normalization is implicit in String construction, semantic identity,
ordinary default `==`, or ordinary default `hash`. Canonically equivalent but
differently encoded scalar sequences are distinct String semantic values unless
a program explicitly normalizes them.

For example, a String containing U+00E9 LATIN SMALL LETTER E WITH ACUTE is not
semantically identical to a String containing U+0065 LATIN SMALL LETTER E
followed by U+0301 COMBINING ACUTE ACCENT:

```text
"é" !== "e\u{301}"
```

when the first source spelling denotes the single precomposed scalar U+00E9.
Their standard `==` results are likewise false under the ordinary unspecialized
String equality default, and their standard hashes are not required to be equal.

Case folding, locale-sensitive comparison, canonical-equivalence comparison,
compatibility-equivalence comparison, collation, grapheme-cluster processing,
and Unicode normalization are higher-level text policies. They require explicit
protocols or library operations and do not alter Core String identity.

The rule is independent of internal representation. An implementation may store
Strings as UTF-8, UTF-16, UTF-32, ropes, slices, interned objects, or another
representation, but encoding units, surrogate pairs used internally, storage
sharing, and normalization choices must not change the observable scalar
sequence or semantic identity.

This exact-sequence rule also preserves retained source newline distinctions:
a retained `LF`, `CR`, and `CRLF` denote respectively U+000A, U+000D, and the
two-scalar sequence U+000D U+000A, as already required by the String-literal
rules.

## Text, Bytes, and Character Encodings

This section owns semantic `String` and `Bytes` value behavior. The standard
Encoding-object conversion contract is owned by `../io/TEXT_IO.md`; this section
only states the value-domain separation needed by String/Bytes semantics.


Core v0.1 separates abstract text from its external binary representation.

`String` denotes Unicode text as a semantic value. A `String` is not semantically UTF-8, UTF-16, Latin-1, or any other particular byte encoding, even if an implementation chooses one of those representations internally.

`Bytes` denotes a raw sequence of bytes. Byte values carry no implicit text interpretation.

Character encodings are represented independently through encoding objects or protocols, conceptually including values such as:

```text
UTF8
UTF16LE
UTF16BE
Latin1
```

Conversion between text and bytes is explicit. The normative one-shot dispatch,
argument domains, encoding catalogue, strict/replacement behavior, BOM rules,
and conversion failures are owned by `../io/TEXT_IO.md`. In particular, the
standard receiver is the `Encoding` object (`encoding.encode(text)` and
`encoding.decode(bytes)`); this document does not define a reciprocal
`String.encode(encoding)` or `Bytes.decode(encoding)` Core protocol.

The availability of standard Encoding objects and I/O facilities remains governed by the I/O specifications; this value-domain section does not make them required Core-prelude bindings.

This follows the same general principle used for numeric endianness:

```text
semantic value ≠ external binary representation
```

Therefore:

```text
String ≠ UTF-8 bytes
Integer ≠ little-endian bytes
```

An implementation may use any internal String representation provided observable language semantics remain unchanged.

## String Literal Semantics

Core v0.1 defines String literals as ordinary `String` values.

- The three supported String source forms — single-quoted (`'...'`), double-quoted (`"..."`), and triple-double-quoted (`"""..."""`) — are formally defined by the lexical grammar in `PROTOS_GRAMMAR.md`.
- Single-quoted and double-quoted forms are equivalent String literals.
- Protos has no separate character literal or character type. `'a'` and `"a"` both denote a `String` containing the single-character text `a`.
- Single-quoted, double-quoted, and triple-double-quoted String literals share the same escape rules.
- The backslash escape is `\\`.
- The supported escape sequences are exactly: `\\`, `\'`, `\"`, `\n`, `\r`, `\t`, `\b`, `\f`, and `\u{HEX}`.
- `\u{HEX}` requires 1 to 6 hexadecimal digits and must denote a valid Unicode scalar value.
- Invalid or incomplete escape sequences are lexical errors.
- Octal escapes and `\xNN` escapes are not supported.
- Triple-double-quoted strings are multiline String literals, not raw strings.
- A triple-double-quoted String starts with exactly three consecutive unescaped double-quote characters (`"""`). When three consecutive double quotes occur at the current lexical position outside a String, triple-double opening recognition takes priority over an ordinary double-quoted String opener.
- Inside a triple-double-quoted String, the first three consecutive unescaped double-quote characters form the closing delimiter, which consumes exactly those three quotes; one or two consecutive unescaped quotes that do not begin a closing delimiter are ordinary content, quotes remaining after a closing delimiter are lexed normally from that position, and an escaped double quote (`\"`) is content and does not participate in a closing delimiter. Core v0.1 defines no implicit concatenation of adjacent String literals.
- Triple-single-quoted strings are not supported.
- String interpolation is not part of Core v0.1.
- `${...}` has no special meaning inside a String and is treated as literal text.
- Reaching the end of source before the required closing delimiter of any supported String literal is a lexical error. This applies to single-quoted (`'...'`), double-quoted (`"..."`), and triple-double-quoted (`"""..."""`) forms, and an unterminated literal never produces a partial String token or a String value.

**Newline Handling in String Literals:**

A logical source newline is one `LF` (U+000A), one `CR` (U+000D), or one `CRLF` (U+000D U+000A) sequence, as defined in Separators, Line Breaks, and Comments.

- Single-quoted and double-quoted String literals are single-line literals.
- A logical source newline (`LF`, `CR`, or `CRLF`) is not permitted inside a single-quoted or double-quoted String literal.
- Encountering a logical source newline before the matching closing quote is a lexical error.
- Newline characters may be represented in single-quoted and double-quoted literals using the `\n` and `\r` escape sequences; these escapes denote String content and are distinct from raw source newlines.
- Triple-double-quoted String literals are multiline String literals and permit logical source newlines as part of the literal content.
- Each logical source newline inside a triple-double-quoted literal counts as one logical newline for structural processing: opening/closing delimiter placement, content-line splitting, and indentation normalization.
- Retained source newlines in a triple-double-quoted literal preserve their original source code points in the resulting String: `LF` remains U+000A, `CR` remains U+000D, and `CRLF` remains U+000D U+000A. There is no implicit newline normalization of String content.
- Opening/trailing newline removal removes the complete logical newline sequence, so a removable `CRLF` is removed as one logical newline.
- In a triple-double-quoted literal, the logical source newline immediately following the opening `"""`, when present, is not part of the resulting String.
- When the closing `"""` is preceded on its source line only by indentation whitespace (possibly none) after a logical source newline, that closing newline and its indentation-only trailing line are not part of the resulting String. A multiline String whose content begins or ends on the same line as a delimiter receives no implicit leading or trailing newline removal.

**Multiline Indentation Normalization:**

- The Core v0.1 multiline indentation normalization rule applies to triple-double-quoted String literals. Indentation is normalized as exact source characters: `SPACE` (U+0020) and `CHARACTER TABULATION` (U+0009, TAB) are distinct code points, they are never considered equivalent for indentation purposes, and Core v0.1 defines no semantic tab width. Normalization is never computed from visual columns or editor tab stops, and never from a minimum-indent rule, a common-visual-column rule, or the longest common whitespace prefix among the content lines.
- The closing delimiter alone establishes the structural indentation prefix. When the closing `"""` terminates an indentation-only trailing line (the case excluded above), the structural indentation prefix is exactly the sequence of `SPACE` and `TAB` characters on that source line immediately preceding the closing delimiter; the prefix may be empty, which is the case when the closing delimiter begins its line. When content flows into the closing delimiter on its source line rather than ending at an indentation-only trailing line, no structural indentation prefix exists and no indentation normalization is performed.
- Indentation normalization applies only where a structural indentation prefix exists. Where no structural indentation prefix exists, no indentation or other whitespace is removed from any content line; this includes whitespace-only content lines, whose `SPACE` and `TAB` characters are ordinary String content and are preserved verbatim. Blank-line whitespace stripping is part of multiline indentation normalization and never applies unconditionally. No structural indentation prefix ⇒ no indentation normalization.
- Where the closing delimiter establishes a structural indentation prefix, the remaining content is split into content lines at each retained logical source newline, and every non-blank content line must begin with exactly that prefix, compared as exact source characters. The prefix is removed exactly once from the beginning of each non-blank content line; the remainder of the line, including any further leading `SPACE` or `TAB` characters, is preserved.
- A structural indentation prefix may contain both `SPACE` and `TAB` characters, and mixed `SPACE`/`TAB` indentation is legal when each content line begins with exactly the same prefix. A `TAB` never equals any number of `SPACE` characters regardless of how an editor displays it. For example, a closing delimiter preceded by `TAB` `SPACE` `SPACE` requires each non-blank content line to begin with exactly `TAB` `SPACE` `SPACE`; a line beginning with `SPACE` `SPACE` `SPACE` `SPACE` does not satisfy that prefix.
- Where the closing delimiter establishes a structural indentation prefix, a non-blank content line that does not begin with the exact prefix — because it has fewer prefix characters, uses `SPACE` where the prefix requires `TAB`, uses `TAB` where the prefix requires `SPACE`, or otherwise differs from it — makes the triple-double-quoted String invalid. Consistent with the existing String-literal lexical-error model, this is a lexical error: the literal produces no String token and no String value, and no recovery behavior is defined.
- Where a structural indentation prefix exists, a blank content line — a content line containing no characters other than `SPACE` and `TAB` (possibly none) — is exempt from the prefix requirement and need not contain the complete structural indentation prefix. All `SPACE` and `TAB` characters on such a blank content line are removed as incidental indentation, so a source blank line contributes an empty logical line rather than whitespace caused solely by source indentation. No intentional whitespace is removed from a non-blank content line beyond the single structural prefix.
- Indentation matching and stripping operate on the raw source characters at the beginning of each content line, before escape sequences are interpreted. An escape sequence that denotes a `TAB` or any other character is not a source `SPACE` or `TAB` and never satisfies the structural indentation prefix. The Core v0.1 escape rules themselves are unchanged by this rule.

Example literals:

```js
"hello"
'hello'
"""
    hello
    world
    """
"${notInterpolated}"
"line\nfeed"
"\u{1F600}"
```

The first multiline example above evaluates to:

```text
hello
world
```

Another example:

```js
"""
    hello
        world
    """
```

evaluates to:

```text
hello
    world
```


Blank-line whitespace stripping occurs only where the closing `"""` establishes a structural indentation prefix. In the following literal the closing delimiter establishes a four-`SPACE` structural prefix, and the intermediate source line between `one` and `two` is whitespace-only and therefore a blank content line:

```js
"""
    one

    two
    """
```

The two non-blank content lines each lose the four-`SPACE` prefix exactly once. The whitespace-only intermediate line is exempt from prefix matching, and all of its `SPACE` characters are removed as incidental indentation, so it contributes an empty logical line. The resulting String is conceptually:

```text
one

two
```

equivalently:

```text
"one\n\ntwo"
```

When content flows into the closing delimiter on its source line, no structural indentation prefix exists and no indentation normalization is performed. In the following literal the intermediate source line contains exactly seven `SPACE` characters and is a whitespace-only line; because no structural prefix exists it is preserved verbatim:

```js
"""one

two"""
```

The resulting String is conceptually:

```text
"one\n       \ntwo"
```


### Standard Bytes indexed semantics

### Standard Bytes size

### Complete standard Bytes sequence semantics

Standard `Bytes` is dynamically resizable through the explicit standard
operations `add(value)` and `removeAt(index)`. This does not change the existing
`atPut(index, value)` contract: `atPut` replaces one existing octet and never
changes sequence length.

#### Standard Bytes-producing results

Unless an operation's normative contract explicitly says that it returns an
existing object, every successful Core-standard operation whose normal result
is specified as a `Bytes` object produces a fresh **open** standard Bytes
identity. This rule applies independently to every successful invocation,
including an invocation whose resulting byte sequence is empty. The result has
the standardized `Bytes` factory object as its delegation parent, exactly as a
direct invocation of that factory does, and owns its ordinary local slots and
mutable byte-sequence state.

No such result is identical to a Bytes object returned by another invocation,
to a source or argument Bytes object, to the producing receiver, or to an
implementation-controlled buffer. Consequently, mutation of the result through
ordinary slot operations, `atPut`, `add`, or `removeAt` cannot change another
result, a source/argument object, the producer, or any later operation's result;
mutation of those objects cannot change the returned result. Primitive identity,
`identityHashOf`, `IdentityMap`, default `==`/`hash`, and open/closed/frozen
observations follow from these distinct ordinary Bytes identities and states.

This is a semantic identity and mutable-state guarantee, not a physical-copy
requirement. Immutable backing, copy-on-write, slicing, persistent storage,
zero-copy, lazy materialization, scalar replacement, and other physical sharing
or virtualization remain permitted when no Protos observation can reveal shared
mutable state or collapsed identity.

An operation that fails, is cancelled before successful result commitment, or
otherwise has no normal Bytes result exposes no partially constructed Bytes
result merely because an implementation had begun producing or buffering its
octets. Actor and isolated-P boundaries do not preserve a source-domain result
object's identity: if this Bytes later crosses such a boundary, the applicable
value-transfer rules independently determine the destination-domain logical
copy or reconstruction.

#### Standard empty Bytes construction

Where the standardized `Bytes` factory object is available, its ordinary
polymorphic invocation behavior accepts exactly zero positional arguments:

```js
Bytes()
```

and creates a fresh **open**, empty standard Bytes object with receiver-owned
byte-sequence state, consistently with the general standard Bytes-producing
result rule above.

Core v0.1 does not require `Bytes` to be a binding of the Core prelude; that
availability boundary remains owned by the I/O model and standard-library
environment. This rule defines the semantics of the standardized factory when
it is exposed; it does not introduce a new mandatory prelude binding.

Each successful `Bytes()` invocation creates a fresh identity. A non-empty
argument vector fails with the ordinary argument-count error after ordinary
left-to-right argument evaluation and before the new Bytes object is created.
The standard factory sends no `init` message.

If a prototype inherits the standard Bytes-factory invocation behavior, the
fresh Bytes object's delegation parent is the actual invocation receiver. The
prototype itself does not acquire byte-sequence state merely by inheriting the
factory.

#### Standard `Bytes.add`

```js
bytes.add(value)
```

requires `value` to be an exact semantic `Integer` in the inclusive range
`0..255`. No Float, numeric coercion, masking, wrapping, truncation, or
implementation-native byte conversion is permitted.

The original receiver must own standard Bytes state and must be **open**.
A closed or frozen receiver signals an `Error` before mutation.

On success, `add` appends the exact supplied semantic Integer value after the
current last octet, increases `size` by exactly one, preserves all existing
octets and their relative order, and returns the exact supplied `value` object.

Validation completes before sequence mutation. A failing `add` changes neither
length nor contents.

#### Standard `Bytes.removeAt`

```js
bytes.removeAt(index)
```

requires an exact semantic `Integer` index in the current range
`0 .. bytes.size - 1`. No coercion, truncation, wrapping, or negative indexing
is defined.

The original receiver must own standard Bytes state and must be **open**.
A closed or frozen receiver signals an `Error` before indexed removal.

On success, `removeAt` removes exactly the octet currently at `index`, shifts
every later octet left by one position while preserving order, decreases `size`
by exactly one, and returns the exact semantic Integer octet value that was
removed.

The index is validated against the sequence state applicable to this operation
before mutation. A failing `removeAt` changes neither length nor contents.

#### Standard `Bytes.each`

```js
bytes.each(block)
```

requires `block` to be invokable through the ordinary polymorphic invocation
protocol. It need not be a Closure.

After ordinary receiver and argument evaluation, standard `Bytes.each` first
validates the original receiver as standard Bytes, then validates `block`
callability, and only then captures a shallow logical snapshot of the current
octets in ascending index order.

Each snapshot octet is supplied to one ordinary invocation:

```text
block(octet)
```

The octet argument is the exact semantic Integer value stored at snapshot time.
Because byte values are semantic Integer values, no host byte/signed-byte object
is exposed.

The iteration snapshot is fixed for the invocation. Later `atPut`, `add`, or
`removeAt` operations performed by callbacks or by other Actor-local work at
explicit suspension points do not change which snapshot octets this invocation
will visit or their order. Such mutations remain governed by the receiver's
ordinary open/closed/frozen rules.

If every callback completes normally, `each` returns the receiver Bytes object.
A callback error or non-local control effect stops further callbacks and
propagates normally; callbacks already completed and independently permitted
mutations are not rolled back.

`Bytes.each` introduces no hidden Map-style mutation guard, lock, transaction,
or suspension point. Snapshot representation is implementation-private provided
the observable ascending-index snapshot semantics are preserved.

#### State consequences

Standard Bytes state therefore has these mutation permissions:

```text
open:
    atPut     allowed for an existing index
    add       allowed
    removeAt  allowed

closed:
    atPut     allowed for an existing index
    add       ERROR
    removeAt  ERROR

frozen:
    atPut     ERROR
    add       ERROR
    removeAt  ERROR
```

`size`, `at`, and `each` remain read-only observations available for open,
closed, and frozen Bytes.



The standard `Bytes.size` operation returns a semantic `Integer` equal to the
receiver's current number of octets.

For standard Bytes whose valid indexed positions are:

```text
0, 1, 2, ... byteLength - 1
```

`bytes.size` returns exactly the mathematical Integer `byteLength`.

Because this Core-standard result is specified simply as an `Integer`, it is an ordinary unbounded Integer under the Numeric Model's general standard-result rule.
An implementation must not expose host index width, native buffer-size limits,
overflow, wrapping, saturation, or truncation through `Bytes.size`.

`size` is a read-only observation. It does not read or decode octet contents,
does not invoke user behavior, and does not mutate the Bytes object. It is
available for open, closed, and frozen Bytes.

The existing standard Bytes receiver-domain rule applies. Merely delegating to
a Bytes object, copying a `size` behavior, or otherwise obtaining that behavior
does not confer receiver-owned byte-sequence state.

`Bytes.size` counts octets only. It does not report Unicode scalar values,
grapheme clusters, encoded characters, storage capacity, reserved capacity,
host buffer length, or any other representation-dependent quantity.



A standard `Bytes` object is an identity-bearing mutable object with
receiver-owned byte-sequence state. Its byte contents are distinct from its
ordinary local slots.

At any observation point, standard Bytes state is a finite dense sequence of
octets with logical indices:

```text
0, 1, 2, ... byteLength - 1
```

Each stored octet has the mathematical value range `0 .. 255`. Bytes carry no
implicit text, character, signed-integer, Unicode, or host-native interpretation.

The standard indexed read:

```js
bytes.at(index)
```

requires `index` to be a semantic `Integer`. Any Integer family is accepted by
its mathematical Integer value. No Float-to-Integer conversion, String parsing,
truncation, wrapping, modulo reduction, or host-sized coercion is performed.
The index must satisfy:

```text
0 <= index < current byteLength
```

Otherwise the operation signals an `Error`.

A successful read returns a semantic `Integer` whose mathematical value is the
stored octet value in `0 .. 255`. Observable correctness is the exact
mathematical Integer value, independent of host storage representation.

The standard indexed update:

```js
bytes.atPut(index, value)
```

requires the same valid semantic Integer index and additionally requires
`value` to be a semantic `Integer` with mathematical value in `0 .. 255`.
Invalid value objects and out-of-range Integers signal an `Error`; the standard
operation never truncates, masks, wraps, takes modulo 256, parses text, or
coerces a Float.

A successful `atPut` replaces exactly the existing octet at that position,
changes no other position, leaves the byte-sequence length unchanged, and
returns the exact `value` object supplied to the invocation.

Consequently:

```js
bytes[index]          // standard bytes.at(index)
bytes[index] = value  // standard bytes.atPut(index, value)
```

use those same contracts, while indexed assignment itself retains the general
language rule that the assignment expression evaluates to the assigned value.

Standard Bytes indexed behavior applies only to an original receiver that owns
standard Bytes byte-sequence state. Delegation, copying, aliasing, composition,
or otherwise obtaining a standard Bytes method does not confer byte storage on
an ordinary object and does not redirect access to an ancestor's Bytes state.
An incompatible receiver signals an `Error` before byte-indexed work.

Byte replacement follows the ordinary object-state boundary:

```text
open Bytes
    -> existing octets may be replaced

closed Bytes
    -> existing octets may still be replaced

frozen Bytes
    -> octet replacement is prohibited
```

Closing or freezing is shallow. For standard `atPut`, a receiver already frozen
when the standard method begins signals an `Error` before index/value
validation or byte mutation. A closed Bytes object still validates the index
and value and may replace an existing octet. Read-only `at` remains available
on open, closed, and frozen Bytes.

This rule defines only the already-existing standard indexed protocol. It does
not introduce byte literals, resizing, append, insert/remove, slicing, numeric
endianness, text decoding, or a second binary-buffer hierarchy. Such facilities
require explicit protocols if standardized separately.

Standard Bytes `==` and `hash` remain governed by the existing Core default for
identity-bearing objects. Two distinct Bytes objects do not become equal merely
because their current octets are equal, and ordinary standard hashing does not
traverse byte contents.

## Maps, Hashing, and Key Equality

`Map` is an ordinary keyed collection whose indexed syntax uses the existing `at` / `atPut` protocol:

```js
map[key]
map[key] = value
```

A normal `Map` uses semantic equality and hashing:

```text
key equality  -> ==
key hash      -> hash
```

### Standard Map construction through ordinary invocation

The standard prelude `Map` and `IdentityMap` objects each delegate directly to
`Object`. `IdentityMap` does not delegate to `Map`; their shared collection
surface does not create an implicit collection hierarchy.


### Standard Map size

Standard `Map.size` and `IdentityMap.size` return the exact semantic `Integer`
number of associations currently stored in the receiver's keyed-entry state.

An empty newly constructed Map therefore has size `0`. Inserting a previously
absent key increases size by exactly one. Replacing the mapped value of an
already-matching key leaves size unchanged. Successfully removing one stored
association decreases size by exactly one.

For normal `Map`, size counts stored associations, not distinct current
`==`-equivalence classes. Therefore if mutable-key behavior has caused two
stored representative keys to become currently equal while both entries remain
stored, both associations count toward `size`.

For `IdentityMap`, size likewise counts stored associations. Identity-hash
collisions do not merge entries and do not affect the count.

The result is a mathematical semantic `Integer`. An implementation must not
expose host container width, bucket count, load factor, capacity, tombstones,
sparse representation, or overflow/truncation through the result.

`size` is a read-only observation. It performs no key `hash`, key `==`,
`identityHashOf`, `===` comparison, callback, iteration snapshot, insertion,
removal, or mapped-value access. It is available for open, closed, and frozen
Maps.

The existing standard keyed receiver-domain rule applies. Merely delegating to
`Map` or `IdentityMap`, or copying/inheriting a `size` behavior onto an object
without the corresponding receiver-owned keyed state, does not make that object
a valid standard Map-size receiver.



The standard prelude objects `Map` and `IdentityMap` specialize the ordinary
polymorphic invocation protocol as empty-map factories.

The standard calls:

```js
Map()
IdentityMap()
```

each create a fresh **open** standard keyed object with no entries.

`Map()` creates ordinary Map keyed state, whose standard key matching uses the
existing `hash` plus `==` rules. `IdentityMap()` creates IdentityMap keyed state,
whose standard key matching uses the existing primitive identity-hash plus
`===` rules.

Both standard factories accept exactly zero positional arguments. A non-empty
argument vector signals the ordinary argument-count error after all call
arguments have already been evaluated under the existing left-to-right call
rules. No Map object is created by that failing invocation, and argument effects
are not rolled back.

Core v0.1 deliberately defines no constructor form that consumes alternating
key/value arguments, an Array of pairs, another Map, an iterator, `each`, or any
other implicit entry source. Programs populate a newly created Map explicitly
through ordinary keyed operations such as:

```js
m: Map()
m.atPut(key, value)
```

This avoids introducing a hidden iterable/pair protocol or constructor-specific
duplicate-key rule.

Each successful invocation creates a fresh Map identity, including two
successive empty-map calls. The new keyed state is empty and its insertion order
therefore contains zero entries.

The created object's delegation parent is the object whose standard Map-factory
or IdentityMap-factory invocation behavior was selected as the invocation
receiver. Consequently ordinary prototype specialization composes with Map
construction:

```js
MyMap: Map {
    label: "custom"
}

m: MyMap()
```

`m` owns fresh ordinary Map keyed state and delegates to `MyMap`; `MyMap` itself
does not acquire keyed state merely by delegating to `Map`. The analogous rule
applies to prototypes inheriting the `IdentityMap` factory behavior.

Factory inheritance never changes map kind. Behavior inherited from `Map`
creates ordinary Map keyed state; behavior inherited from `IdentityMap` creates
IdentityMap keyed state.

This rule does not weaken the standard keyed receiver-domain invariant.
Inheriting or copying `at`, `atPut`, `containsKey`, `atIfAbsent`, `remove`,
`each`, or other ordinary keyed behavior still does not confer keyed state on
the receiver. The
factory instead creates a distinct new object that owns that state.

The standard Map factories do not send `init` to the created object and perform
no key hashing, equality comparison, identity hashing, callback, iteration,
entry insertion, or hidden suspension after invocation begins.

Map relies on the language-wide Boolean-result contract of `==`: equality returns canonical `true` or `false`, or signals an error. Map introduces no separate truthiness or Map-specific interpretation rule.

The required contract is:

```text
a == b  =>  a.hash == b.hash
```

### Hash Result Contract

The language-level `hash` protocol returns a semantic `Integer` value.

A `Map` operation that consumes a key's `hash` result must validate that result
before using it. Any semantic `Integer` value is valid; the protocol does not
depend on one particular Integer representation or implementation layout. A
Float, String, Boolean, `null`, ordinary identity-bearing object, or an object
that merely delegates to an Integer value is not an Integer hash result.

No implicit conversion, truncation, masking, modulo reduction, host-word-size
coercion, or Float-to-Integer conversion is part of the language protocol. An
implementation may reduce or mix a valid Integer internally for its own table
layout only if that reduction is unobservable and preserves the specified
`Map` matching semantics.

If a `Map` key's `hash` behavior returns a non-Integer value, the Map operation
signals an error before performing its own Map mutation. Effects already
performed while evaluating the user-defined `hash` behavior are ordinary
effects and are not rolled back.

For correctly behaving hashable values, repeated `hash` observations during one
execution must be stable whenever the state relevant to `==`/`hash` has not
changed, and:

```text
a == b  =>  a.hash == b.hash
```

The equality in the hash contract compares the mathematical Integer hash values;
internal Integer representation choices cannot distinguish equal semantic
Integer hash results.

`identityHash` likewise produces a semantic `Integer`. It is the hash companion
to semantic identity (`===`): if `a === b`, their `identityHash` values must be
the same during that execution. The converse is not required; identity-hash
collisions are permitted.

For an identity-bearing object, `identityHashOf` remains stable for that
object's lifetime within the current Protos execution. For Core value-identity
categories, semantically identical values receive equal identity hashes
independently of boxing, allocation, interning, representation, Actor placement,
worker placement, operating-system process, or machine placement within that
same Protos execution.

The observable standard identity-hash domain is the Protos execution, not a host
process. Separate Protos executions need not choose the same identity hashes.
Within one Protos execution, host placement alone must not change the
`identityHashOf` result of a semantic value whose identity is preserved.

This does not require a global mutable identity-hash registry or a global lock.
For value-identity categories an implementation may derive identity hashes from
semantic identity plus immutable execution-scoped configuration. For
identity-bearing objects it may allocate or cache identity hashes locally where
the object lives, provided the object's required semantic identity and lifetime
rules are preserved. An Actor pass-by-value copy that is a new identity-bearing
object is not required to retain the source object's identity hash merely because
its copied state is equal.

Persistent, distributed, cryptographic, or interoperable hashing requires a
separate explicit algorithm/protocol. Ordinary `hash` and primitive
`identityHashOf` do not define a persistent object identifier or externally
stable fingerprint.

### Identity-hash dispatch boundary

Semantic identity hashing is non-overridable in the same sense as semantic
identity itself.

Core defines a primitive semantic operation, written conceptually as:

```text
identityHashOf(value)
```

`identityHashOf` is not a Protos message lookup and ordinary program code cannot
replace, shadow, intercept, or override it. It returns the semantic `Integer`
identity hash governed by the `identityHash` contract above.

Core v0.1 exposes no standard ordinary `identityHash()` message and no
guest-visible numeric identity-hash accessor. `identityHashOf(value)` is the
primitive semantic operation used by Core identity-sensitive machinery; it is
not a standard guest-visible message or callable.

The name `identityHash` remains an ordinary slot name when program code defines
it explicitly. Such user-defined behavior has no semantic identity-hash
authority. In particular, `IdentityMap` uses `identityHashOf(key)` together with
primitive `===`; it does not send an `identityHash` message to a key. A
user-defined `key.identityHash()` therefore cannot change whether the key is
found in an `IdentityMap`, create or remove identity-key collisions at the
language level, or violate the invariant that semantically identical values have
the same semantic identity hash.

Likewise, implementation optimizations may compute or cache semantic identity
hashes internally, but may not route `IdentityMap` behavior through user-defined
message dispatch merely because program code defines a slot named
`identityHash`.

This distinction does not create a second observable notion of identity:
`identityHashOf` is the hash companion of the already non-overridable `===`
relation and remains internal to the semantic/runtime machinery that requires
its numeric result.

Stable `hash`/`==` behavior remains the correctness contract for keys whose
programmer intends ordinary associative-map behavior. Core nevertheless defines
the result of violating that contract; it does not make the `Map` implementation
strategy observable.

A `Map` entry retains the hash recorded when that entry was first inserted.
Subsequent mutation of the stored key, mutation of state consulted by its
`hash`/`==` behavior, or any other change does not recompute that recorded hash,
move the entry, replace its representative key, or cause automatic reindexing.

### Standard Map receiver domain

Standard `Map` and `IdentityMap` keyed behavior operates on keyed-entry state
owned by the original receiver. Ordinary delegation can make a standard Map
method visible to another object, but delegation alone does not create, copy,
borrow, or redirect keyed-entry state.

A standard keyed behavior whose contract requires normal-Map state is applicable
only when the original receiver owns standard normal-`Map` keyed-entry state.
Likewise, a standard behavior whose contract requires `IdentityMap` state is
applicable only when the original receiver owns standard `IdentityMap`
keyed-entry state, unless that behavior is explicitly specified as generic over
both standard Map kinds.

This applies to the standard keyed protocols defined by Core, including `at`,
`atPut`, `containsKey`, `atIfAbsent`, `remove`, `each`, and any other standard
behavior whose normative semantics inspect or mutate the receiver's keyed-entry
state.

For an incompatible receiver, invocation signals an `Error` after ordinary
receiver/argument evaluation and ordinary message lookup have selected the
behavior, but before the behavior performs keyed-state work. In particular, the
failing invocation performs no key `hash`, no key `==`, no `identityHashOf`
operation for key search, no iteration-snapshot capture, and no keyed-entry
mutation.

Failure does not resume lookup at a more distant slot with the same name.
Lookup remains ordinary delegation lookup; receiver-domain validation belongs
to the selected standard behavior's contract.

Consequently, if an ordinary object delegates to a Map object, Map prototype, or
another object exposing standard Map methods, that object does not thereby
become a Map and does not gain hidden associative storage:

```text
ordinaryChild -> someMapOrMapPrototype -> ...

ordinaryChild.at(key)
    -> Error if the selected standard behavior requires Map keyed-entry state
       that ordinaryChild does not own
```

Copying, aliasing, composing, or otherwise reusing a standard Map method does not
confer Map keyed-entry state either. User-defined behavior remains ordinary
Protos behavior and may intentionally implement a wider receiver contract.

This rule does not introduce a second delegation relation or a class/type
hierarchy. It makes explicit the receiver-owned semantic state already required
by standard keyed collections and prevents implementations from borrowing an
ancestor's entries, allocating hidden storage on first inherited use, or making
delegation itself grant collection membership.

### Map key-state visibility during search

Map lookup fixes the candidate sequence and the lookup hash information, but it
does not snapshot the mutable state of key objects.

During one normal `Map` lookup:

- the candidate sequence is fixed for that lookup;
- a stored key's recorded hash is not recomputed merely because user code
  mutates that key;
- the query key's single `hash` result is the hash used by that lookup;
- mutations performed by equality code remain ordinary visible mutations;
- later candidate comparisons observe the current state of the relevant objects;
- the lookup does not restore, clone, freeze, or otherwise snapshot key objects;
- mutation does not restart or reorder the lookup and does not silently trigger
  another query-key hash.

This distinguishes fixed search-control state from live object state. An
implementation must not copy mutable key objects merely to make lookup easier
to implement.

For identity lookup, no user equality callback is performed. The same fixed
candidate-order and no-key-snapshot rules nevertheless apply.

Implementations may use hash tables, ordered arrays, trees, or other internal
representations, provided the specified candidate order, hash behavior, and
visibility of intervening mutations are preserved.

Every later key search continues to use the deterministic matching algorithm
defined below: compute the query key's current hash once, consider only entries
whose recorded hash equals that query hash, and compare those candidates in
insertion order using the query key's current `==` behavior. Therefore a changed
stored key may cease to be findable by itself, may later coexist with another
key that currently compares equal, or may become findable by a different query.
Those outcomes follow from the specified recorded hashes and current protocol
results rather than from hash-table layout.

If several stored entries currently compare equal to a query and have the same
recorded hash as that query, the earliest such entry in insertion order is the
one found. Entries with different recorded hashes are not equality candidates
for that search even if their current `==` behavior would report equality.

The invariant

```text
a == b  =>  a.hash == b.hash
```

therefore remains a programmer-facing contract required for conventional map
semantics, not a license for implementations to choose arbitrary behavior when
it is violated. The same is true of the recommendation that a stored key's
relevant equality/hash behavior remain stable.

Core does not prohibit mutable objects from being keys, does not implicitly
freeze keys, and does not require hidden mutation tracking. Implementations may
diagnose unstable or inconsistent keys in optional debugging facilities only if
doing so does not replace the normative Core behavior of ordinary execution.

Protocol violations cannot cause host-language memory unsafety or corruption of
the Protos runtime. They also do not authorize implementation-dependent
exceptions, aborts, nontermination, silent reindexing, or other behavior that
would differ from the deterministic `Map` search/update rules.


### Default equality and hash behavior

`Object` provides the default ordinary `==` and `hash` behavior for receivers
that do not provide more specific behavior through ordinary delegation.

The default `==` behavior is semantic identity:

```text
defaultObjectEquals(a, b) = (a === b)
```

Therefore two distinct ordinary identity-bearing objects compare unequal by
default even if they currently contain the same slots, while two references to
the same ordinary object compare equal. No structural slot comparison,
delegation-chain comparison, prototype comparison, serialization comparison, or
host-representation comparison is implied.

The default `hash` behavior returns the receiver's semantic identity hash:

```text
defaultObjectHash(a) = identityHashOf(a)
```

Consequently the inherited defaults satisfy the normal Map contract:

```text
a == b  =>  a.hash == b.hash
```

without requiring per-object user code.

Both are ordinary messages exposed through the object model. A more specific
object or prototype may override `==` and/or `hash` through ordinary slots. If
custom `==` behavior makes two non-identical values equal, the programmer or
library defining that behavior is responsible for providing coherent `hash`
behavior as already required by the Map contract.

Overriding ordinary `==` does not change `===`. Overriding ordinary `hash` does
not change `identityHashOf` or `IdentityMap`.

The default does not make `==` globally symmetric, transitive, or reflexive for
all user-defined behavior. Those properties follow only where the specific
equality protocol in use guarantees them. The default inherited behavior itself
has the corresponding properties because it delegates to semantic identity.


### Inequality semantics

`!=` is retained source syntax but is not an independently customizable
inequality message protocol.

For arbitrary expressions `a` and `b`, the language requires:

```text
a != b  =  not (a == b)
```

with this exact semantic boundary:

1. evaluate `a` exactly once;
2. evaluate `b` exactly once after `a`;
3. invoke the receiver's ordinary customizable `==` behavior exactly once;
4. require the `==` result to be canonical `true` or canonical `false`, or
   propagate its Error/non-normal control result according to the existing
   equality contract;
5. return the opposite canonical Boolean.

No truthiness conversion is applied. A non-Boolean normal return from custom
`==` signals the existing equality-result error before `!=` returns. The
derived operation does not invoke `not()` on an arbitrary equality result.

The `!=` source operator performs no ordinary lookup or send for a selector
named `!=`. `Object` therefore provides no standard `!=` protocol slot, and a
local or delegated slot structurally named `!=` does not affect source
inequality.

`==` remains the sole ordinary customization point for Core semantic equality.
This does not require custom `==` to be globally symmetric, transitive, or
reflexive where the existing equality model does not require those properties;
it requires only that source `!=` be the logical complement of the selected
valid `==` result.

`!==` remains different: it is the non-overridable logical complement of
semantic identity `===`.

```text
a !== b  =  not (a === b)
```

`!==` performs no `!=`, `==`, `not`, or other user-overridable message dispatch.
It returns canonical `true` exactly when `a === b` is false, and canonical
`false` exactly when `a === b` is true.

Therefore overriding `==` cannot change `===` or `!==`, and ordinary equality
customization cannot change identity-sensitive mechanisms such as `IdentityMap`.

### Default equality and hashing when Core defines no specialization

The ordinary `Object` equality/hash behavior is the default for every Core
object for which no normative rule explicitly defines more specific standard
`==` or `hash` behavior. This default is not limited to identity-bearing object
kinds.

Therefore, absent an explicit normative specialization:

```text
receiver == other
    -> receiver === other

receiver.hash()
    -> identityHashOf(receiver)
```

For identity-bearing objects, this means ordinary object identity and an
identity-derived hash. For Core value-identity objects, the same inherited
default operates on their semantic identity: semantically identical String
values, the canonical Boolean values, and `null` therefore use `===` for the
standard equality result and `identityHashOf` for the standard hash unless a
more specific normative rule is explicitly defined.

A built-in family, standard prototype, container, buffer, executable object,
runtime coordination object, context, module, singleton, or other Core object
does not acquire structural, content-derived, case-folded, locale-sensitive,
state-derived, or otherwise specialized equality/hashing merely because an
implementation or host language commonly provides such behavior.

A normative section may deliberately define specialized standard equality or
hashing where semantics require it. Number is explicitly specialized: numeric
`==` is numeric equality rather than semantic identity, and standard numeric
`hash` is correspondingly required to preserve numeric-equality coherence.
Other explicit specializations, if added normatively, likewise take precedence
over this default.

The existing standard Map/IdentityMap equality/hash rule is a documented
collection-specific consequence of this general default, not a separate
identity relation.

User-defined ordinary `==` and `hash` overrides remain unaffected. Libraries may
provide structural, recursive, content-based, locale-sensitive, or
domain-specific equality and hashing explicitly; those policies are not inferred
by Core.

### Standard Map equality and hashing

`Map` and `IdentityMap` are identity-bearing mutable objects. The standard Map
prototypes do not introduce structural collection equality or structural
collection hashing.

Unless a program deliberately overrides the ordinary protocols, both standard
Map kinds use the ordinary `Object` defaults:

```text
map1 == map2
    -> map1 === map2

map.hash()
    -> identityHashOf(map)
```

Consequently two distinct Maps remain unequal under standard `==` even when
they currently contain the same associations in the same insertion order, and
even when their keys and values compare equal. The same rule applies to two
distinct `IdentityMap` objects.

Standard Map equality and hashing therefore do not:

- enumerate or compare entries;
- invoke key `hash` or `==` protocols;
- invoke equality or hashing on stored values;
- depend on insertion order, current capacity, physical table layout, or
  recorded entry hashes;
- recurse through Maps stored as keys or values;
- change merely because a Map is closed or frozen.

This keeps the standard `hash` of a Map stable for that Map's semantic identity
during the current execution even while the Map's contents mutate. A standard
Map may therefore itself be used as a normal `Map` key without its own content
mutation silently changing its default equality/hash class.

The word "identity" in `IdentityMap` describes how that collection matches its
keys. It does not grant `IdentityMap` a second object-identity relation and does
not cause two distinct IdentityMaps with identical entries to compare equal.

`==` and `hash` remain ordinary customizable messages. A program may define
structural or domain-specific Map comparison and hashing deliberately, but such
overrides are then governed by the existing general contracts: custom equality
must return the required Boolean result where applicable, and keys intended for
ordinary associative behavior must satisfy equality/hash coherence and the
existing stability rules. Such customization does not affect `===`,
`identityHashOf`, or `IdentityMap` key matching.

Core intentionally does not provide an implicit deep Map equality institution.
A library that wants structural, recursive, order-sensitive, order-insensitive,
cycle-aware, or application-specific collection comparison must expose that
policy explicitly rather than making ordinary mutable Map identity depend on
entry traversal.

### Standard `Map.atPut` result

For the standard `Map` and `IdentityMap` indexed-update protocols,
`atPut(key, value)` returns the exact `value` object supplied to that invocation
after the update succeeds.

This result is independent of whether the operation inserted a new entry or
updated an existing entry. It does not return the previous mapped value and does
not use `null`, a hidden sentinel, or another absence marker to distinguish
insertion from replacement.

Consequently an explicit ordinary message send:

```js
result: map.atPut(key, value)
```

has:

```js
result === value
```

after successful completion.

Bracket assignment remains governed by the existing indexed-assignment rule and
also evaluates to the assigned value:

```js
map[key] = value
```

The syntax-level result does not depend on the `atPut` return value, so
user-defined indexing protocols remain free to define a different direct
`atPut` result unless their own normative protocol says otherwise. This section
fixes only the standard `Map` and `IdentityMap` protocol results.

If key search, hashing, equality, receiver validation, or the mutation itself
signals an error, `atPut` has no normal return. Existing rules for effects and
Map mutation before failure remain unchanged.

### Standard Map missing-key semantics

For the standard `Map` and `IdentityMap` protocols, indexed lookup through
`at(key)` requires a matching entry. If the key search completes normally and no
entry matches, `at(key)` signals an `Error`; it does not return `null`, `false`,
a hidden sentinel, or another ordinary value to represent absence.

This rule is necessary because every Protos object, including `null`, is a valid
stored Map value. A mapping whose value is `null` is present and is observably
different from an absent mapping.

`containsKey(key)` is the non-failing presence query. If its key search completes
normally, it returns canonical `true` exactly when a matching entry exists and
canonical `false` otherwise. It does not retrieve or interpret the mapped value,
so a key mapped to `null`, `false`, or any other value is still present.

Conceptually:

```text
map.at(key)
    matching entry -> entry.value
    no match       -> Error

map.containsKey(key)
    matching entry -> true
    no match       -> false
```

Hashing, equality, and identity-key matching remain exactly those already
specified for the receiver's Map kind. If a required `hash`, `==`, identity-hash
operation, or other key-search step signals, that error propagates; the
missing-key rule applies only after a search completes normally with no match.

Core introduces no special absence value and does not reserve any ordinary
object as an out-of-band Map result. Optional-result carriers, hidden sentinels,
and ambient receiver-wide default state are not implied by the standard missing-
key rules.

### Standard Map expected-absence fallback

The standard non-mutating expected-absence selector for `Map` and `IdentityMap`
is:

```js
map.atIfAbsent(key, fallback)
```

Receiver, `key`, and the argument expression that produces `fallback` are
evaluated under the ordinary left-to-right, exactly-once invocation rules before
the selected standard behavior begins. Evaluating a Closure literal therefore
creates the Closure object but does not execute its body.

After standard receiver-domain validation, `atIfAbsent` performs exactly one
logical key search using the receiver's existing key-search law:

- normal `Map` uses the existing query hash and directed
  `queryKey == storedKey` semantics;
- `IdentityMap` uses the existing semantic identity-hash and `===` semantics.

If a matching association exists, `atIfAbsent` returns the exact stored value
object. This includes `null`, `false`, and every other valid stored value. On
this present path, `fallback` is neither callability-validated nor invoked.

If key search completes normally with no matching association, `fallback` must
be invokable through the ordinary polymorphic invocation protocol. It is invoked
exactly once with zero positional arguments, and its exact normal result is
returned unchanged. Callability validation is therefore path-sensitive; it does
not suppress the earlier ordinary evaluation of the argument expression that
produced the fallback object. No fallback arity is accepted implicitly: an
arity or other invocation failure from the actual zero-argument call propagates
normally.

Search and fallback effects, suspension, errors, and ordinary non-local control
transfer remain governed by their existing semantics. `atIfAbsent` performs no
keyed-entry mutation itself and introduces no rollback. The fallback begins only
after the missing-key search has completed; once invoked, it is ordinary Protos
behavior and may mutate the same Map, another Map, the key, or other objects when
those mutations are otherwise permitted by the existing state and reentrancy
rules.

`atIfAbsent` does not search the key again after fallback execution. If the
fallback inserts, removes, closes, freezes, or otherwise affects the receiver
after absence was established, the outer operation does not reinterpret that
change; it returns the fallback's exact result if the fallback returns normally.
The fallback receives neither the key nor the Map receiver as an implicit
argument; code that needs them may capture them through ordinary closure
semantics.

No atomicity, key reservation, transaction, rollback, memoization, or
compute-once guarantee is part of `atIfAbsent`. The selector does not define a
mutating get-or-insert operation, an eager default-value selector, a presence-
result carrier, `Optional`/`Maybe`, truthiness, a hidden absence sentinel, or new
syntax. Such facilities require their own explicit contracts if standardized
later.

The one-search rule is observable semantics rather than a physical storage
requirement. A conforming implementation may use any Map representation or
indexing strategy that preserves the receiver's existing deterministic key law
without beginning a second logical lookup for the same `atIfAbsent` invocation.

### Standard Map interaction with `close()` and `freeze()`

`Map` and `IdentityMap` are ordinary objects and participate in the existing
open/closed/frozen object-state model. Their keyed-entry state is receiver-owned
mutable state even though indexed entries are not object slots.

For the standard Map protocols:

```text
open Map
    may insert entries
    may remove entries
    may replace values of existing entries

closed Map
    may not insert entries
    may not remove entries
    may replace values of existing entries

frozen Map
    may not insert entries
    may not remove entries
    may not replace values of existing entries
```

The same rules apply to `IdentityMap`.

Closing or freezing a Map is shallow. It changes mutation permissions on that
Map's own keyed-entry state and ordinary local slots; it does not close or freeze
stored keys or values and does not change their identity, equality, or hash
behavior.

Read-only Map operations, including `at(key)`, `containsKey(key)`, and
`atIfAbsent(key, fallback)`, remain available on closed and frozen Maps and use
the same deterministic key-search semantics. Any mutation attempted by an
invoked fallback remains subject to the receiver's then-current ordinary state
rules.

For `atPut(key, value)`, state validation is ordered as follows after ordinary
receiver/argument evaluation:

- if the Map is frozen, the operation signals an `Error` before invoking the
  key's `hash`, `==`, or any other key-search callback, because no successful
  keyed-entry mutation is permitted;
- if the Map is open, ordinary key search runs and the operation may either
  replace a matched entry's value or append a new entry;
- if the Map is closed, ordinary key search runs because replacing an existing
  entry is still permitted. A match may have its value replaced; a no-match
  result signals an `Error` before appending a new entry.

For a standard operation whose purpose is removal of a keyed entry, a closed or
frozen Map signals an `Error` before beginning key search because that operation
cannot perform a permitted keyed-entry mutation in either state. An open Map
uses the ordinary key-search and removal rules.

These state checks do not roll back receiver/argument-evaluation effects that
occurred before the standard Map method begins. Where key search is permitted,
its ordinary `hash`/`==` effects and errors remain governed by the existing Map
rules.

This is a standard collection contract, not a change to indexed syntax.
User-defined `atPut` or other indexed protocols remain ordinary behavior and are
not automatically constrained by Map-specific keyed-state rules merely because
they use bracket syntax.

### Deterministic `Map` key matching

Because `==` and `hash` are ordinary Protos protocol operations, the direction
and observable order in which a `Map` uses them are part of `Map` semantics.
Implementations must not let hash-table layout, bucket order, probing strategy,
or another storage detail choose which user-defined equality operations occur.

For every standard `Map` operation that searches for an argument key `queryKey`,
the portable behavior is as if the map performs the following search:

```text
queryHash = queryKey.hash

for each stored entry in insertion order:
    if entry.recordedHash == queryHash:
        if queryKey == entry.key:
            match that entry and stop

no entry matches
```

`queryKey` is therefore the receiver of `==`; the stored key is its argument.
Core does not silently reverse the comparison, invoke both directions, or
symmetrize a user-defined `==`. The existing Boolean-result contract applies to
every comparison.

When a new entry is created, the hash value obtained from that insertion key is
recorded conceptually with the entry. An implementation may represent this
metadata differently, but a correctly behaving key observes semantics
equivalent to the search above. This does not weaken the existing requirement
that the equality/hash behavior relevant to a key remain stable while it is
stored.

If evaluating the query's `hash` or one of the required `==` comparisons signals
an error, the `Map` operation signals that error. A mutating key operation does
not perform its own structural/value mutation before the key search completes
successfully; side effects already performed by user `hash` or `==` behavior are
not rolled back.

For insertion/update through `map[key] = value`, if the search finds an existing
entry, only that entry's value is replaced. The originally stored key object,
its recorded hash, and its insertion position are retained. Supplying a
different identity-bearing object that compares equal therefore does not replace
the representative key visible during iteration.

If no entry matches, a new entry containing the supplied key and value is added
at the end of insertion order and stores the query hash obtained for that
operation.

The same matching rule applies to standard operations such as direct lookup,
`containsKey`, `atIfAbsent`, removal by key, and any later standard `Map`
protocol that is defined in terms of finding a key. A library operation that
deliberately wants
different matching semantics must expose a distinct protocol rather than rely
on implementation-specific `Map` internals.

`IdentityMap` is unchanged. Its matching operation is based on `===` and
`identityHashOf`, not this `Map` `==` protocol.

This rule intentionally does not turn general user-defined `==` into an
equivalence relation. If user code defines asymmetric equality, `Map` remains
deterministic: only `queryKey == storedKey` is relevant. Numeric equality keeps
its separately specified symmetry guarantee.

Likewise, `Map` does not add an identity shortcut before `==`. Values such as
Float NaN therefore retain their ordinary `==` semantics when used as normal
`Map` keys. Code that requires identity-keyed behavior uses `IdentityMap`.
The ordinary `hash` operation is not required to be stable across separate
Protos executions. Standard built-in hash behavior may use per-execution
randomization or salting for security, but host placement is not a semantic hash
boundary: moving otherwise equivalent execution across operating-system
processes, threads, workers, or machines within the same Protos execution must
not by itself change a standard built-in value's observable `hash` result.

For standard immutable value families whose hash is defined from semantic value,
the mapping from the specified semantic hash key to the observable Integer hash
is therefore coherent for the duration of one Protos execution. Separate Protos
executions need not choose the same mapping.

This requirement does not impose a global mutable hash table or a global lock.
An implementation may derive the mapping from immutable execution-scoped
configuration and may additionally use per-Map, per-Actor, per-worker, or
per-process mixing for physical table layout when that mixing is not observable
through the language-level `hash` result or logical Map matching semantics.

Persistent or interoperable hashing must use a separate explicit protocol or
algorithm.

`IdentityMap` follows the same insertion-order rule unless a more specialized collection explicitly documents otherwise.



### Standard Map iteration

The standard iteration selector for `Map` and `IdentityMap` is:

```js
map.each(block)
```

`block` must be invokable through the ordinary polymorphic invocation protocol.
It need not be a Closure: any value accepted by an ordinary parenthesized call
is a valid standard Map iteration callback.

After ordinary receiver and argument evaluation, standard `Map.each` /
`IdentityMap.each` first validates the receiver under the existing standard Map
receiver-domain rule, then validates `block` callability, and only then
establishes the iteration snapshot. A non-invokable `block` therefore signals an
`Error` before snapshot establishment and before any callback invocation.

Callability validation does not validate callback arity in advance. Each
snapshot association is subsequently supplied through one ordinary polymorphic
invocation with exactly two positional arguments:

```text
block(key, value)
```

Any arity or invocation error from that actual call propagates normally and
stops iteration under the existing `each` error/unwind rule.

At the start of the standard `each` invocation, after ordinary receiver and
argument evaluation, the operation establishes a shallow logical snapshot of
the receiver's current associations in insertion order. Each snapshot element
contains exactly the representative key object stored by the Map and the exact
value object associated with that entry at the snapshot point.

`each` then invokes `block` once for every snapshot element, in snapshot
insertion order, with two ordinary arguments:

```text
block(key, value)
```

If every callback returns normally, `each` returns the receiver Map object.

The snapshot is an iteration semantic boundary, not a physical representation
requirement. While the callbacks execute, code may mutate the same Map whenever
those mutations are otherwise permitted by the existing open/closed/frozen,
hash/equality, and reentrancy rules. Such later Map mutations do not alter the
current iteration snapshot:

- entries inserted after the snapshot are not visited by that invocation;
- entries removed after the snapshot are still visited if their snapshot
  position has not yet been visited;
- replacing a Map entry's value after the snapshot does not change the value
  argument stored in the current snapshot;
- removing and later reinserting a semantically equal key does not create a
  second visit in the current snapshot;
- nested `each` calls establish their own independent snapshots.

The snapshot is shallow. It preserves the key and value object references that
were stored at the snapshot point; it does not clone, freeze, or otherwise
isolate those objects. Mutating a mutable key or value object through some
ordinary reference remains mutation of that object and is observable normally.
Only later changes to the Map's association set or replacement of an entry's
mapped value are excluded from the already-established snapshot.

This rule also applies when a callback reaches an explicit suspension point.
Another Actor-local task may mutate the Map while the iterating task is
suspended, subject to ordinary Actor/task semantics, without acquiring a hidden
iteration lock and without changing the suspended iteration's established
snapshot. Standard Map iteration therefore introduces no Map-wide lock,
mutation prohibition, or scheduling dependency.

If a callback signals an error or otherwise exits the `each` invocation by an
ordinary non-local control transfer, no later snapshot element is visited.
Effects already completed by earlier callbacks are not rolled back.

`IdentityMap.each` has exactly the same snapshot and result semantics. Its
snapshot contains the representative key objects and values stored by the
IdentityMap; no identity re-search is performed merely to iterate.

An implementation need not allocate an eager copied Array or pair objects.
Persistent entry structures, versioned cursors, copy-on-write state, or any
other representation are valid when they produce the same shallow-snapshot
behavior. The cost of preserving this semantics is incurred only when iteration
requires it; Core does not mandate snapshot copies for Maps that are never
iterated.

### Deterministic `IdentityMap` key semantics

`IdentityMap` uses semantic identity rather than ordinary equality. Its logical
key search is:

```text
queryIdentityHash = identityHashOf(queryKey)

for each stored entry in insertion order:
    if entry.recordedIdentityHash == queryIdentityHash:
        if queryKey === entry.key:
            match that entry and stop

no entry matches
```

Both `identityHashOf` and `===` in this algorithm are the primitive,
non-overridable semantic operations already defined for identity-sensitive
machinery. No `identityHash`, `hash`, `==`, or other user-overridable message is
sent while finding an `IdentityMap` key.

Each newly inserted entry logically records the semantic identity hash obtained
for its key. Because semantic identity hashes are stable for the lifetime
required by the identity-hash contract, an implementation may recompute,
cache, reduce, or store this information differently when the difference is not
observable; the algorithm above defines matching behavior, not physical table
layout.

For indexed insertion or update, if a matching entry already exists, only that
entry's value is replaced. Its representative key and insertion position are
retained. If no entry matches, a new entry containing the supplied key and value
is appended at the end of insertion order.

Removal by key removes the matching entry. A later insertion of the same
semantic key after that removal is a new insertion and therefore appears at the
end of insertion order.

The same identity-key search rule applies to direct lookup, `containsKey`,
`atIfAbsent`, removal by key, indexed insertion/update, and any later standard
`IdentityMap` operation defined in terms of finding a key.

Identity-hash collisions do not make distinct semantic identities match.
Conversely, two values that are semantically identical under `===` denote the
same `IdentityMap` key even if an implementation represents them using different
allocations, boxes, or immediate-value encodings.

This rule does not require a particular hash-table representation or a physical
linear scan. An implementation may use any indexing strategy that produces the
same observable matching, update, removal, and insertion-order behavior.

### Standard keyed removal

The standard keyed-removal selector for `Map` and `IdentityMap` is:

```js
map.remove(key)
```

It removes exactly the entry selected by the receiver's existing deterministic
key-search semantics and returns that entry's previously stored value.

If key search completes normally and no entry matches, `remove(key)` signals an
`Error`. It does not return `null`, `false`, a hidden sentinel, or another
ordinary value to represent absence. A mapping whose stored value is `null`
therefore remains distinguishable from an absent mapping.

Conceptually:

```text
map.remove(key)
    matching entry -> remove entry, return previous stored value
    no match       -> Error
```

The same result contract applies to `IdentityMap.remove(key)`, using primitive
identity-key matching.

`remove(key)` is a mutating standard Map operation and therefore follows the
existing open/closed/frozen rules and state-revalidation rules:

- an already closed or frozen Map signals before key search;
- on an open normal Map, permitted key search may execute user-defined `hash`
  and `==` behavior;
- if such behavior closes or freezes the Map before the actual removal, the
  operation signals before removing the matched entry;
- successful removal returns the exact stored value object that belonged to the
  removed entry.

Effects already performed by key-search callbacks are not rolled back if the
outer removal later fails.

`containsKey(key)` remains the non-failing way to ask whether a key is present.
Libraries that want remove-if-present, default-return, or conditional-removal
behavior may expose distinct ordinary protocols rather than overloading the
standard `remove(key)` absence result.

### Map state transitions during key search

The object-state checks above are semantic checks at the point where the
corresponding keyed-entry mutation is about to occur; an earlier successful
check does not grant permission that survives later user code.

This matters for normal `Map`, because key search can execute user-defined
`hash` and `==` behavior. The existing outermost-hash rule permits ordinary
effects before candidate traversal, and comparison callbacks may perform
ordinary operations that do not themselves mutate the Map's keyed-entry state.
Those effects may include calling `close()` or `freeze()` on the Map.

Therefore a standard mutating Map operation must revalidate the receiver state
after every user-code phase that can precede its own keyed-entry mutation:

- `atPut(key, value)` still rejects a Map that is already frozen before key
  search, preserving the existing fail-before-callback rule;
- after a successful matching search, immediately before replacing the matched
  entry's value, `atPut` checks the then-current Map state again. Replacement is
  permitted only while the Map is open or closed; if a key callback froze the
  Map, the operation signals an `Error` before replacing the value;
- on the no-match path, the existing insertion check occurs after key search and
  therefore uses the then-current state. A callback that closes or freezes the
  Map prevents insertion;
- a keyed-entry removal checks removal permission before key search as already
  specified and, if a matching entry is found, checks again immediately before
  removing it. A callback that closes or freezes the Map therefore prevents the
  removal.

State changes already completed by user behavior are ordinary effects and are
not rolled back merely because the outer Map operation subsequently fails.
Likewise, key-search effects other than the denied keyed-entry mutation remain
completed.

`IdentityMap` key search executes no user-defined callback, so the same
point-of-mutation rule normally observes the same state as its initial check.
Implementations may elide a redundant recheck when they can prove that no
semantic operation between the checks can change the receiver state.

No lock, transaction, snapshot, or reservation of future mutation permission is
introduced. The rule simply applies the receiver's actual state at the semantic
mutation point.

### Reentrant mutation during `Map` key comparison

A normal `Map` search may execute user-defined `==` behavior while it is
examining candidate entries. That callback is ordinary Protos code, but it must
not make the candidate sequence of the same in-progress search depend on the
implementation's table iterator.

While a `Map` is executing a `queryKey == storedKey` comparison on behalf of a
search of that same Map, any operation that would mutate that Map's entry set,
entry values, recorded hashes, or insertion-order state signals an `Error`
before performing the Map mutation.

This restriction is scoped to the particular Map and only to the dynamic extent
of its key-comparison callback. It does not block:

- mutation of unrelated Maps;
- mutation of the query or stored key objects themselves;
- mutation of objects stored as Map values;
- read-only operations on the same Map;
- ordinary non-Map slot mutation that does not alter the Map's keyed-entry
  state.

If the attempted reentrant Map mutation signals an error that user code handles,
the comparison may continue and return a Boolean in the ordinary way. If the
error escapes the comparison, the outer Map operation fails. No Map-entry
mutation attempted while the restriction was active has occurred.

The query key's `hash` call happens before that search enters any of its own
candidate-comparison scopes. In the ordinary outermost case, effects performed
by `hash`, including mutations of the target Map, complete according to ordinary
semantics before the search examines its candidate entries. Consequently the
candidate search observes the Map state that exists after the query hash has
returned.

This does not suspend an already-active comparison restriction. If an outer
comparison scope for that same Map is already active — for example because a
key's `==` implementation performs a nested read-only lookup on the same Map —
the nested lookup's `hash` call executes while that outer scope remains active.
Any attempt by that `hash` behavior to mutate the same Map therefore signals the
ordinary reentrant-mutation `Error` before mutation. A `hash` call is exempt only
from a comparison scope that would otherwise be created by its own search; it
does not escape comparison scopes established by enclosing operations.

The Map's own requested mutation, if any, still occurs only after key search
succeeds as specified elsewhere.

Mutation of key objects during `hash` or `==` remains governed by the existing
mutable-key rules: the query hash is computed once, stored entries retain their
recorded hashes, and later equality comparisons use then-current key behavior.

The implementation may enforce the comparison restriction with an operation
stack, reentrancy flag, iterator discipline, or any other mechanism. No
Actor-wide lock, global lock, snapshot copy of the Map, or permanent per-entry
metadata is required by Core semantics.

### Map comparison restriction across suspension

A normal `Map` candidate equality comparison is ordinary Protos code and may
reach an explicit suspension point. Suspension does not end, mask, or transfer
the same-Map comparison restriction.

If a `queryKey == storedKey` comparison suspends while executing on behalf of a
search of a particular Map, that Map remains under the existing keyed-entry
mutation restriction until the comparison invocation completes normally or
leaves through ordinary unwind. Other runnable Actor-local work may execute
while the comparison is suspended, but any such work that attempts to mutate
that same Map's keyed-entry state signals the same reentrant-mutation `Error`
before mutation.

The restriction therefore follows the lifetime of the in-progress comparison,
not merely the currently executing Actor turn or task segment. It remains
Map-specific and does not prevent:

- other Actor-local tasks from running;
- read-only operations on that Map;
- mutation of unrelated Maps;
- mutation of key objects or objects stored as values;
- ordinary non-Map slot mutation that does not alter that Map's keyed-entry
  state.

A read-only same-Map search started by another Actor-local task while the
comparison is suspended executes under the already-active restriction. Its own
query-key `hash` call therefore cannot mutate that Map merely because the new
search has not yet entered one of its own candidate comparisons.

The restriction is not a blocking lock. A conflicting keyed-entry mutation
fails according to the existing reentrant-mutation rule; it does not wait for
the suspended comparison to resume. No global Actor or execution-wide exclusion
is introduced.

When the suspended comparison later resumes, the same restriction is still
active. Normal return, error unwind, non-local return, or cancellation unwind
that leaves the comparison releases that comparison's contribution to the
restriction exactly once. An implementation must not leave the Map permanently
restricted after the comparison has ceased to exist.

This rule deliberately avoids snapshotting the Map's keyed-entry state across a
suspending equality callback and avoids letting unrelated Actor-local
interleaving silently change the candidate associations of an already-active
comparison. A key equality implementation that suspends for an unbounded time
can consequently keep that particular Map mutation-restricted for the same
unbounded time; this is an observable consequence of choosing to suspend inside
a protocol whose dynamic extent protects that Map, not permission for the
runtime to block unrelated Actor work.

## Foreign Values

This section is the primary normative owner of the semantics of values that a
foreign provider admits into Protos: values originating in Java, Python,
JavaScript, Ruby, another guest language, or another host runtime API. Foreign
module identity and lifecycle are owned by `MODULES.md`; the `ForeignError`
category is recorded in the `ERRORS.md` taxonomy and its use is owned here;
Actor and P transfer remain owned by `../concurrency/ACTORS.md` and
`../concurrency/PARALLEL_EXECUTION.md`.

This section is implementation-independent. No runtime interoperability API,
host wrapper class, host exception type, or provider mechanism is a Protos
language institution by virtue of being used to realize it. Core v0.1 defines
no syntax for foreign values, and this section does not standardize a provider
registry or a specifier scheme. The public operation contract of the explicit
interoperability module `std:interop` is owned by "Relation to explicit
interoperability" below.

### Ordinary Protos semantics first

A foreign value does not import the semantics of its source language or of a
particular runtime API into Protos. A foreign capability participates in
ordinary Protos syntax and protocols only where a faithful projection onto an
already existing Protos institution exists, as defined by the rules below.

Behavior that is ambiguous, incompatible with ordinary Protos semantics,
side-effecting in a way that is not equivalent to the ordinary operation,
provider-specific, metadata-oriented, or otherwise not faithfully projectable
receives no automatic ordinary-syntax meaning. It is reachable only through a
deliberate provider facade/protocol or through the explicit interoperability
module (`std:interop`, see below).

Terms used in this section:

```text
admission
    entry of a foreign value into Protos, producing either a converted Protos
    scalar or a raw foreign reference

raw foreign reference
    an admitted foreign value that is not converted to an existing Protos
    value family

provider facade
    an ordinary Protos object deliberately created by a provider to present a
    foreign capability through documented Protos-facing behavior
```

### Primitive admission

A foreign value is automatically converted to an existing Protos value only
when its source semantic category is known and the conversion is lossless:

```text
foreign Boolean                                   -> Protos Boolean
true foreign absence/null                         -> Protos null
valid Unicode scalar sequence                     -> Protos String
source-classified integral value                  -> unbounded Protos Integer
source-classified binary floating value exactly
representable as binary64                         -> Protos Float
```

The following are not collapsed automatically and remain raw foreign references
unless a future explicit conversion converts them:

- ambiguous null-like values, including JavaScript `undefined`;
- decimal, rational, or other custom numeric families;
- numeric values whose conversion would be lossy;
- text that is not a valid Unicode scalar sequence;
- foreign arrays/lists merely because of their shape;
- foreign maps/dictionaries/hashes merely because of their shape.

A physical representability check alone does not establish the source semantic
family. For example, a foreign value whose bits happen to fit an Integer or a
Float is not thereby source-classified as integral or binary floating.

A converted value is an ordinary value of its Protos family and has exactly that
family's identity, equality, hashing, and transfer semantics.

### Raw foreign-reference identity

A raw foreign reference is an identity-bearing value under §21. It does not form
a new value-identity family. `===` remains the authoritative identity relation:
neither host reference identity nor physical runtime-wrapper identity is a
Protos identity relation.

- When the provider/runtime has a stable foreign identity for the underlying
  object, distinct physical representations of the same foreign object denote
  the same Protos foreign-reference identity.
- When no stable foreign identity is available, independent admissions produce
  distinct Protos identities, while an alias of an already-admitted raw foreign
  reference preserves that reference's identity.
- When a Protos value crosses into a foreign runtime and later returns in a form
  the provider recognizes as that same Protos value, it is the original Protos
  value with its original identity.

No universal or global wrapper cache is required; physical representation is an
implementation choice provided these observations hold.

A provider facade is an ordinary identity-bearing Protos object. There is no
general rule that a facade is `===` to its underlying foreign target, and no
universal facade canonicalization. The identity of a foreign module facade is
defined by `MODULES.md`; other facades have ordinary object identity unless a
future normative rule says otherwise.

### Equality, hashing, Map, and IdentityMap

For a raw foreign reference, ordinary `==` is the default Object equality of
"Default equality and hash behavior" (that is, `===`), and ordinary `hash` is
the semantic identity hash (`identityHashOf`), coherent with that `==`. Source
language or provider equality and hashing — for example Java `equals` /
`hashCode`, Python `__eq__` / `__hash__`, JavaScript `==` / `===`, Ruby `==`,
or a foreign hash table's key equality — are not imported.

A provider facade may define ordinary `==` and `hash` like any other Protos
object, subject to the ordinary coherence obligation.

Standard `Map` keeps exactly its `==` / `hash` key rule and standard
`IdentityMap` keeps exactly its `===` / `identityHashOf` key rule; raw foreign
references used as keys therefore behave as identity-keyed values in both. A
foreign hash container is not a standard `Map`, and its keying rules do not
redefine those of standard `Map`.

### Member read

An ordinary member read `foreign.name` on a raw foreign reference proceeds
conceptually as:

1. the Protos-facing semantic protocol/slot projection of the receiver;
2. after a miss there, a faithful foreign-member fallback, if available;
3. otherwise the ordinary Protos missing-member failure.

Names that denote Protos institutions — including `call`, `at`, `atPut`,
`each`, `==`, and `hash` — are never satisfied by a same-spelling foreign member
through step 2. The foreign member remains reachable only through explicit
interoperability.

The Protos-facing projection of a raw foreign reference supplies the default
`==` and `hash` behavior above. It does not supply the default construction of
the standard `Object.call` (`CALLABLES.md`); a `call` member exists only when
explicitly projected under "Callability and construction" below.

Automatic foreign-member fallback exists only when the foreign read is faithful
to ordinary Protos member-read semantics. A foreign read that has material side
effects incompatible with an ordinary read, that cannot preserve receiver
binding faithfully, or that otherwise changes ordinary Protos meaning receives
no generic automatic projection; a provider facade/protocol or explicit
interoperability must be used.

### Member write

`foreign.member = value` keeps its ordinary meaning (`OBJECT_MODEL.md` §3): it
modifies a valid existing writable local Protos slot. Writes do not delegate and
are not redefined as hidden foreign-member writes. A raw foreign reference does
not acquire writable local Protos slots because the foreign runtime permits
writing a member, and slot modification or creation on it fails with the
ordinary failure for that operation. A provider facade, being an ordinary Protos
object, may have ordinary local slots. Foreign-member mutation belongs to
explicit interoperability or a deliberate provider facade/protocol.

### Member invocation

`foreign.member(args...)` is defined as the member read above followed by
ordinary Protos invocation of the resulting admitted/projected value. There is
no second, hidden "invoke foreign member" semantics. An implementation may use a
combined invoke-member primitive only when it is observably equivalent to read
and bind followed by ordinary invocation, including evaluation order, receiver
binding, result admission, failure projection, and side effects.

### Callability and construction

Foreign executability does not create a second callable predicate.
`foreign(args...)` remains the ordinary `call` protocol of `CALLABLES.md`:
lookup of `call`, a selected value that must be a Closure, and ordinary Closure
activation. A foreign executable capability may be projected as a `call` member
whose value is an adapter Closure that performs the foreign execution when
activated. A non-executable raw foreign reference has no hidden callability:
lookup of `call` fails with the ordinary missing-member failure.

Foreign instantiability creates no syntax, does not make a raw foreign reference
callable, and does not redefine `call`. The baseline route to instantiation is
the explicit `std:interop` operation `instantiate`. A provider type/class/module
facade may deliberately publish a `call` Closure whose documented Protos
semantics is construction. When a foreign target is both executable and
instantiable, the generic layer does not choose between them; a provider facade
or explicit interoperability disambiguates.

### Indexed access, foreign hash containers, and iteration

Bracket syntax keeps exactly its lowering to `at` / `atPut` ("Indexed Access
Syntax"). A foreign indexed/array-like value may expose faithful Protos-facing
`at` and `atPut`. That does not make it a standard `Array` or give it Array
family membership, identity, copying, `each` snapshot, or Actor/P transfer
semantics. When a foreign target presents both array-element and hash-entry
capabilities, the generic layer does not choose the meaning of `at`; a provider
facade or explicit interoperability resolves it.

A provider may project foreign hash-entry access as custom `at` / `atPut` when
that is an explicit, unambiguous provider semantics. Keying remains that of the
foreign container, which is not a standard `Map`.

Where ordinary foreign iteration is projected, it is `foreign.each(block)`,
whose Protos-facing implementation repeatedly pulls the next foreign iterator
element, admits it, and invokes `block(element)` through ordinary Protos
invocation. The baseline mechanism does not hand the Protos callback to the
foreign runtime. A custom foreign `each` does not acquire the shallow-snapshot
semantics of standard `Array.each` or `Map.each`.

When the projected foreign iteration is exhausted normally and every reached
`block(element)` invocation completes normally, `foreign.each(block)` returns
the exact original Protos-facing receiver of that `each` invocation: for a raw
foreign reference, that same raw foreign reference; for a foreign module
facade, that same Actor-local Protos facade (`MODULES.md`). The result is never
the underlying foreign target, and the results of `block(element)` invocations
do not select it. This result rule confers no Array or Map family membership
and no Array or Map snapshot semantics.

### Foreign failures

`ForeignError` (parent `Error`, see `ERRORS.md`) is the single initial standard
category for foreign failures. Core defines no per-language Error hierarchy.

Every failure produced by a foreign operation after that operation has actually
been entered is a fresh `ForeignError` standard failure occurrence. A failure
that occurs before any foreign operation is entered is the existing ordinary
Protos failure; for example, a foreign target that lacks a required
Protos-facing `call` produces the ordinary lookup/invocation failure, not a
`ForeignError` merely because the receiver is foreign.

A `ForeignError` occurrence exposes exactly these visible payload slots, each
holding only safe Protos values:

```text
language         String naming the foreign language/provider
operation        String naming the attempted foreign operation
category         String classifying the failure, or null
foreignCategory  String naming the source-language failure kind, or null
message          String, or null
cause            Error, or null
```

This revision does not standardize the vocabularies of these Strings. The
`cause` projection must be cycle-safe: projecting a cyclic foreign cause chain
terminates and never exposes a non-Error value.

Host exception objects, foreign exception handles, foreign runtime objects, host
authority handles, foreign/native or mixed-language stack objects, suppressed
exception objects, and runtime implementation metadata are not part of the
public structure of a `ForeignError`. Implementation-private diagnostics remain
governed by `ERRORS.md`.

### Synchronous foreign callbacks

This subsection is the primary owner of the baseline foreign-to-Protos callback
contract: a Protos callable value handed to a synchronous foreign operation and
invoked by that operation within its own dynamic extent. Actor-side execution of
such a callback is owned by `../concurrency/ACTORS.md` §24J; Task ownership,
suspension, and cancellation by `../concurrency/FUTURES_AND_TASKS.md`
("Synchronous foreign callbacks and the current Task"). Core v0.1 defines no
syntax and no public API for passing a callback; a provider or a future explicit
interoperability facility supplies the passing mechanism under these rules.

**Callback value.** The foreign-facing callback capability denotes the actual
Protos semantic value that was passed. For a Closure, each foreign invocation is
ordinary Closure activation (`CALLABLES.md`) of that exact Closure, with its
lexical environment, receiver, `methodHome`, parameter/argument binding, return
home, non-local return, and ordinary Error/control semantics. A host adapter or
wrapper that an implementation uses to present the callback to the foreign
runtime does not replace the Closure's identity, is not a distinct Closure, and
has no semantic authority of its own.

**Arguments.** Values supplied by the foreign runtime as callback arguments are
admitted exactly by the rules of this section (primitive admission and raw
foreign-reference identity) and then bound as the ordinary positional arguments
of the activation. There is no separate callback-specific conversion.

**Result projection.** When the activation completes normally, its result is
projected back to the foreign runtime only losslessly:

- a Protos scalar that the provider can represent without changing its value;
- a raw foreign reference that returns faithfully to its originating provider
  when that provider can preserve the same foreign target.

No other result receives automatic projection. In particular an arbitrary
identity-bearing Protos object, including an Object, Array, Map, Closure,
Future, ActorRef, or other capability, is not automatically exported as a
retained foreign handle; that requires a future explicit export contract. When
a result cannot be projected, the callback does not complete successfully
toward the foreign runtime; what the foreign operation then does is foreign
behavior, and any failure it consequently returns to Protos follows "Foreign
failures". The concrete foreign representation of a projected result is an
implementation choice.

**Errors and control.** A Protos Error signaled and not handled inside the
activation, or any other Protos control transfer leaving it, crosses the
callback boundary into the foreign operation. If that same outcome comes back
through the same foreign operation recognizably unchanged, Protos continues to
propagate exactly that outcome: an Error `e` is re-signaled as exactly `e`
(`ERRORS.md`, "Standard failure occurrence objects and identity"), and no
`ForeignError` is created merely because `e` traversed foreign code. If the
foreign operation instead produces a different failure, substitutes, transforms,
or wraps the outcome so that it is a foreign-produced failure, or fails before
or after the callback, the failure returned to Protos is a fresh `ForeignError`
under "Foreign failures". No host exception, foreign exception handle, raw host
stack, or mixed-language stack becomes visible. Non-local return keeps the
Closure's ordinary semantics (`CALLABLES.md` §13 and §14): `^value` whose return
home is still active is that ordinary control transfer, and a return home that
is no longer active produces the ordinary `InvalidReturn`.

**Reentrancy.** A callback may itself call synchronous foreign operations that
invoke further callbacks, callbacks may be invoked sequentially any number of
times, and ordinary recursion through callbacks is permitted, all within the
same logical Protos execution. Concurrent re-entry into that logical execution
is not permitted (`../concurrency/ACTORS.md` §24J).

**Lifetime.** A callback capability is semantically live only during the dynamic
extent of the foreign operation that received it: it becomes live when that
operation is entered and expires when the operation leaves by any means,
including normal return, failure, a Protos Error, non-local control,
cancellation unwind, or termination. Preparing the capability before entry gives
it no independent lifetime, and physically retaining a host wrapper does not
extend it. An expired callback cannot keep alive or reactivate a Process, Actor,
Task, provider, foreign execution context, Closure execution authority, or
return home. The mechanism used to track expiry is an implementation choice.

**Rejected invocations.** A foreign attempt to invoke a callback is rejected
before any Protos code is entered when the capability has expired, when its
originating foreign operation has returned, when its owning Task has completed,
when its Actor no longer permits ordinary work, when its Process has terminated
or closed, when its provider or foreign execution context has closed, or under
the thread rule of `../concurrency/ACTORS.md` §24J. A rejected invocation never
creates a Task and never resurrects an Actor, Process, provider, or foreign
execution context. The rejection is reported to the foreign caller, not as a
Protos Error: if it occurs inside another entered foreign operation that then
fails back to Protos, that failure is a `ForeignError` under "Foreign failures";
if no Protos execution awaits the result, no Protos Error channel exists for it.

**Deferred.** This baseline defines no retained or asynchronous callback
facility: no retained callback handle, foreign-thread ingress queue, listener,
timer, or event-loop registration, asynchronous completion callback, Task
creation for late callbacks, callback result Future, persistent rooting,
revocation or asynchronous cancellation API, Process/provider lifetime
extension, or callback-after-return behavior. A future explicit facility may
define those without changing this synchronous contract.

### Relation to explicit interoperability

Import-based foreign access and the explicit interoperability module
`std:interop` share exactly one substrate for foreign-value admission, foreign
identity, primitive conversion, and failure projection. `std:interop` is not a
one-to-one mirror of any runtime API and defines no universal source-language
semantic-equality operation. Its public surface is exactly these four
operations:

```text
invoke(target, ...arguments)
instantiate(target, ...arguments)
readMember(target, name)
writeMember(target, name, value)
```

Each operation acts only on a foreign value the program already possesses — a
raw foreign reference or a provider facade presenting a foreign target — and
operates on its underlying foreign target. None of them changes any ordinary
Protos institution of this section: ordinary lookup, member read, member write,
member invocation, callability, `at` / `atPut`, projected `each`, and raw
foreign identity, equality, and hashing remain exactly as defined above.

**Outbound values and results.** Arguments of `invoke` and `instantiate` and the
`value` of `writeMember` cross into the foreign operation only under the
lossless outbound projection of this section ("Synchronous foreign callbacks",
"Result projection"); a Protos callable passed where the provider accepts a
callback is governed exactly by "Synchronous foreign callbacks". A value that
cannot be so projected is rejected before the foreign operation is entered. The
normal result of `invoke`, `instantiate`, and `readMember` is admitted under
"Primitive admission" and "Raw foreign-reference identity".

**`invoke`.** `invoke(target, ...arguments)` is explicit foreign execution of the
underlying foreign target. It performs no ordinary Protos `call` lookup, does
not redefine the ordinary `call` protocol (`CALLABLES.md`), and does not make a
raw foreign reference ordinarily callable.

**`instantiate`.** `instantiate(target, ...arguments)` is explicit foreign
instantiation/construction of the underlying foreign target. It is semantically
distinct from `invoke`: when a foreign target supports both execution and
instantiation, the operation named by the program selects the meaning and Protos
does not choose between them. Foreign instantiability does not make a raw
foreign reference ordinarily callable, and ordinary `call` is unchanged.

**`readMember`.** `readMember(target, name)` explicitly requests the foreign
member `name` of the underlying foreign target. `name` must be a Protos
`String`. For this explicit operation only, ordinary Protos slot and projection
precedence ("Member read") is bypassed, so a foreign member whose name coincides
with a protected Protos institution — including `call`, `at`, `atPut`, `each`,
`==`, and `hash` — is reachable: `readMember(target, "call")` requests the
foreign member `call`, while ordinary `target.call` continues to denote the
Protos institution. `readMember` does not change the meaning of `target.name`.

**`writeMember`.** `writeMember(target, name, value)` is the explicit operation
that mutates the foreign member `name` of the underlying foreign target. `name`
must be a Protos `String`. Ordinary assignment `target.name = value` never
becomes foreign-member mutation ("Member write"). When the foreign write
completes normally, `writeMember` returns the exact Protos value passed as
`value`: it performs no readback, returns no provider completion token, does not
readmit or reconvert `value`, and returns neither `null` nor `target` unless
that is literally the value passed.

**Failures.** The entered/not-entered boundary of "Foreign failures" applies
unchanged. Each of the following, when detected before the foreign operation is
entered, is the existing ordinary Protos failure and never a `ForeignError`: a
target that is not a possessed foreign value or facade; invalid arity; a `name`
that is not a `String`; an explicit operation known to be unsupported by the
target; an outbound argument or `value` that cannot be projected; a closed
provider session or generation; and an unavailable required provider
capability. A failure after the foreign operation has actually been entered is a
fresh `ForeignError` under "Foreign failures". A Protos Error or control outcome
that traverses the same foreign operation unchanged under "Synchronous foreign
callbacks" continues as that exact outcome. No provider- or language-specific
public Error category is introduced.

**Authority and lifetime.** `std:interop` is possession-based. Importing it
creates no provider compartment, provider session, or Polyglot Context, performs
no provider or language discovery and no dependency acquisition, and confers no
authority. It publishes and selects no provider, execution profile, trusted
mode, host, filesystem, network, process, or native access, classloading,
reflection, cross-language evaluation, provider registration or hot-loading, or
dependency acquisition, and it is not a service locator. Each operation uses
only the provider, session, generation, and authority already associated with
the possessed target; the exact provider/session/generation remains the lifetime
authority, and a closed generation is never rebound to a later one. No
operation performs Actor or P transfer, proxying, rematerialization, or provider
reopening ("Actor and P boundaries").

**Not defined.** This revision defines no other `std:interop` operation and in
particular no foreign-ness or capability predicate (such as `isForeign`,
`isExecutable`, `isInstantiable`, `supports`, `hasMembers`,
`hasArrayElements`, `hasHashEntries`, or `hasIterator`); no explicit member
invocation; no explicit array-element or hash-entry access; no iterator or
cursor object or explicit iterator protocol; no explicit scalar or raw
conversion; no metaobject, type, language, source, or display metadata; no
provider or language discovery; no foreign class, type, or module acquisition or
dependency acquisition; no provider registration or hot-loading; no
session/generation/lifetime inspection; and no retained or asynchronous
callback. Runtime-private capabilities remain private. In particular, an
explicit member invocation is not universally equivalent to
`invoke(readMember(target, name), ...arguments)`, because a foreign member may
be invocable without being readable; such an operation, if added, is a distinct
additive operation. Ordinary `foreign.member(args...)` remains as defined in
"Member invocation".

### Actor and P boundaries

Foreign values and resources have no automatic Actor transfer contract and no
automatic P transfer contract. The rules of `../concurrency/ACTORS.md` §16,
§24K, and §24L and of `../concurrency/PARALLEL_EXECUTION.md` §71 apply
unchanged; an Actor-local foreign module facade does not make the underlying
foreign state or resource transferable. Converted scalars transfer as ordinary
values of their Protos family.
