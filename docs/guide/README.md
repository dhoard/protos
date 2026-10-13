<p align="center">
  <img src="../assets/branding/protos-logo.png" alt="Protos" width="280">
</p>

# Protos Programming Guide

This directory contains the non-normative programming guide for Protos.

The guide answers a different question from the normative specification:

> Given the Protos semantics, how should a programmer think about them and use
> them effectively?

The specification under [`../../spec/`](../../spec/) remains authoritative for
observable syntax and semantics. If this guide and the specification disagree,
the specification wins.

## Start here

If you want to run Protos before reading the language guide, start with
[Try Protos](00-try-protos.md). It covers the Protos 0.3.312 prerelease: the
recommended self-contained Native release on its supported target, the Protos
Dev Container, and the portable JVM compatibility fallback, with checksum
verification and Native limitations.

## How to use the learning material

Four complementary resources are maintained:

- [Try Protos](00-try-protos.md) gets a runnable Protos environment ready;
- this guide explains concepts and mental models;
- [`../../protos/tutorials/`](../../protos/tutorials/) contains small,
  progressive executable programs;
- [`../../protos/examples/`](../../protos/examples/) is a task-oriented
  cookbook for answering "how do I do X in Protos?"

Where practical, guide chapters point to executable tutorial programs rather
than duplicating large source examples that can drift independently.

## Source style reference

[`SOURCE_STYLE.md`](SOURCE_STYLE.md) records the non-normative project convention
for choosing between idiomatic syntactic sugar and an equivalent canonical or
expanded protocol form. Ordinary hand-written source normally uses the idiomatic
surface; the explicit form remains appropriate when the underlying mechanism,
bootstrap boundary, dispatch behavior, or semantic equivalence is the point of
the code.

## Tracked documentation work

The Programming Guide is part of
[`DOC001 — Protos Programming Documentation`](https://github.com/guillermomolina/protos-project-docs/blob/main/docs/project/work/DOC001/DOC001_PROGRAMMING_DOCUMENTATION.md).

DOC001 tracks this guide as one documentation initiative with independently
auditable slices. A blocker on one chapter does not automatically block unrelated
documentation areas whose semantics and implementation are already closed.

The getting-started path is tracked separately by
[`DOC006 — Try Protos and Getting Started guide`](https://github.com/guillermomolina/protos/issues/524).
DOC006 owns the runnable onboarding path and keeps it separate from the broader
language-teaching progression under DOC001.

## Current chapters

0. [Try Protos](00-try-protos.md)
1. [Bindings, contexts, and object state](01-bindings-contexts-and-state.md)
2. [Objects, delegation, and composition](02-objects-delegation-and-composition.md)
3. [Closures, methods, and receivers](03-closures-methods-and-receivers.md)
4. [Control flow through ordinary protocols](04-control-flow-through-protocols.md)
5. [Values, identity, equality, and collections](05-values-identity-equality-and-collections.md)
6. [Modules and imports](06-modules-and-imports.md)
7. [Errors, handlers, `ensure`, and resource lifetime](07-errors-handlers-ensure-and-resource-lifetime.md)
8. [Futures and structured concurrency](08-futures-and-structured-concurrency.md)
9. [Isolated parallel execution](09-isolated-parallel-execution.md)
10. [Actors, ActorRefs, and Actor Groups](10-actors-actorrefs-and-groups.md)
11. [Process, I/O, Filesystems, and Authority](11-process-io-filesystems-and-authority.md)
    — including what each launcher grants and Filesystem/Network examples
12. [Protocol-first matching and case selection](12-matching-expressions.md)

## Toolchain guides

- [Bundled Tools](tools/README.md) — what an official bundled Tool is, which
  Tools currently ship, and where Tool policy stops and language/runtime
  behavior begins.
- [The `protos` command line](tools/cli.md) — program execution, `format`,
  `lint`, `debug`, `run`, exit codes, and Native/JVM differences.
- [Language server](tools/language-server.md) — LSP capabilities, editor
  configuration, project binding, and what is not provided.
- [Package Tool](tools/package-tool.md) — `protos package manifest` and the
  current boundary of package operations.
- [Test Tool](tools/test-tool.md) — current `protos test` corpus and
  expectation model, isolated case execution, deterministic reporting,
  bounded `--jobs` parallelism, and Case selection/listing.

## Standard Library guides

- [Standard Library overview](library/README.md) — importing `std:` modules
  and Native/JVM behavior.
- [Regular expressions](library/regex.md) — `std:regex/Regex`.
- [Dates, times, and durations](library/datetime.md) — `std:datetime/*`.
- [Structured logging](library/logging.md) — `std:logging/*`.
- [Text styling and encodings](library/text.md) — `std:text/*`.
- [Semantic versions](library/semver.md) — `std:semver/SemVer`.
- [Explicit foreign operations](library/interop.md) — `std:interop`.

## Java integration guides

- [Embedding Protos in a Java application](embedding/java.md) — standard
  Polyglot `Context`, evaluation, bindings, Closure invocation, Core location,
  authority, and lifecycle on the portable JVM runtime.
- [Supplying application modules from Java](embedding/application-modules.md)
  — the `app:` catalog installed with `ProtosEmbeddedModules.install`.
- [Foreign interoperability](interop/README.md) — map of resolvers, foreign
  providers, and what is available at the current revision.
- [Writing and registering an external provider](interop/custom-provider.md)
  — the public `spi.foreign` contract and `--foreign-provider-path`.
- [The `java:` provider fixture](interop/java-provider.md) — `java:math` and
  `java:date` through the I085-B test fixture.

## Current guide state

The control-flow dependency that originally blocked chapter 04 is closed.

D044 / specification revision `0.1.381` defines the complete standard Closure
`while` protocol, D045 / specification revision `0.1.382` clarifies task-scoped
structured ownership for returned task-backed Futures, and
[`I023 — Standard while protocol`](https://github.com/guillermomolina/protos-project-docs/blob/main/docs/project/registries/IMPLEMENTATION_STATUS.md#i023--standard-while-protocol)
has published the reference implementation plus full conformance closure.
[`B007`](https://github.com/guillermomolina/protos-project-docs/blob/main/docs/project/registries/IMPLEMENTATION_BLOCKERS.md#b007--standard-while-protocol-semantics)
is CLOSED. D180 later renamed the standard Closure loop selector from `while`
to `whileTrue` without a compatibility alias; all other D044 loop semantics are
unchanged. D201 later made `whileTrue` return its last normal body result
(canonical `null` for zero iterations) and added the distinct standard `while`
selector, which runs the same loop and returns its final canonical `false`.

`DOC001-E` is CLOSED with
[chapter 04](04-control-flow-through-protocols.md), which explains the published
control-flow behavior as ordinary protocols without redefining the language.

`DOC001-F` is CLOSED with
[chapter 05](05-values-identity-equality-and-collections.md), which explains the
current value-identity/equality model, indexing, Core collection mechanisms, and
their Standard Library extensions.

`DOC001-G` is CLOSED with
[chapter 06](06-modules-and-imports.md), which explains module contexts,
ordinary `import(...)`, canonical ModuleKeys, Actor-local module instances,
cache-before-execute cycles, failure/retry semantics, and the resolver-policy
boundary.

`DOC001-H` is CLOSED with
[chapter 07](07-errors-handlers-ensure-and-resource-lifetime.md), which explains
Error objects, non-resumable signaling, dynamic handlers, unwind-safe `ensure`,
cleanup precedence, and deterministic explicit resource release.

`DOC001-I` is CLOSED with
[chapter 08](08-futures-and-structured-concurrency.md), which explains Future
identity/state and observation, adoption, `then`, deterministic `Future.all`,
cooperative cancellation, and task-scoped structured ownership.

`DOC001-J` is CLOSED with
[chapter 09](09-isolated-parallel-execution.md), which explains explicit P
isolation, Closure projection, argument snapshots, deterministic Array parallel
operations, cooperative cancellation/structured ownership, and the absence of
shared writable partitioning.

`DOC001-K` is CLOSED with
[chapter 10](10-actors-actorrefs-and-groups.md), which explains Actor isolation,
ActorRef incarnation identity, explicit snapshot/capability transfer,
send/request/backpressure and acceptance boundaries, lifecycle monitoring,
Actor Group identity, GroupRef routing, and Group/Process lifetime separation.

`DOC001-L` is CLOSED with
[chapter 11](11-process-io-filesystems-and-authority.md), which explains
bootstrap-local Process authority, args/environment snapshots, independently
optional byte streams and explicit Encoding, TextReader/TextWriter ownership,
I/O Future/commitment/lifecycle rules, structural Path values, confined
Filesystem/File capabilities, namespace mutation, and the current D046/I024
specified-versus-runnable boundary.

The language-focused DOC001 guide sequence is complete through DOC001-L. The
former DOC001-M broad toolchain gate has been reconciled out of DOC001: bundled
Tools and detailed Test Tool documentation are owned by DOC005, while
comprehensive Package Tool documentation should be tracked independently when
TOOL001 is stable. DOC001-N therefore owns only the final navigation and
consistency closure for the completed Programming Guide. Closing DOC001 does not
claim that TOOL001 or DOC005 are complete.

`DOC004` remains CLOSED with
[chapter 12](12-matching-expressions.md), now reconciled by I041-E to the D131
protocol-first model: ordinary `pattern.match(subject)` authority, standard
`Any` and `Capture`, direct structural `Array.match` and `Map.match`, ordinary
`value.caseOf(cases)`, positional captures, and explicit documentation that the
superseded I038 dedicated pattern-language surface is no longer part of Core
v0.1.

## Related ongoing documentation

DOC005 owns the maintained bundled-Tool and Test Tool documentation under
[Toolchain guides](tools/README.md) and the
[Test Tool guide](tools/test-tool.md). Its remaining work may continue as the
published Test Tool surface evolves without reopening or blocking DOC001.

Comprehensive Package Tool user documentation is likewise outside DOC001's final
Programming Guide closure. It should be tracked as independently meaningful
documentation work when TOOL001 has a stable published surface to describe.

These documentation tracks explain published behavior only. They do not define
planned language or Tool behavior, and their independent lifecycle does not
change the completed DOC001 language-guide scope.
