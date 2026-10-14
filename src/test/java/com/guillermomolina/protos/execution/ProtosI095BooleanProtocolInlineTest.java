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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.guillermomolina.protos.parser.ProtosParser;
import com.guillermomolina.protos.runtime.ProtosActivation;
import com.guillermomolina.protos.runtime.ProtosBooleanValue;
import com.guillermomolina.protos.runtime.ProtosClosureValue;
import com.guillermomolina.protos.runtime.ProtosNullValue;
import com.guillermomolina.protos.semantic.Canonicalizer;
import com.guillermomolina.protos.semantic.ast.CanonicalSequence;
import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.TruffleLanguage.LanguageReference;
import com.oracle.truffle.api.bytecode.Instruction;
import com.oracle.truffle.api.bytecode.TagTree;
import com.oracle.truffle.api.instrumentation.StandardTags;
import com.oracle.truffle.api.nodes.RootNode;
import com.oracle.truffle.api.source.Source;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;

/**
 * I095-D focal evidence: the shared NORMAL Boolean-control lowering covers all
 * eight standard selectors. {@code ifTrueIfFalse} proves and inlines each
 * literal callback independently, {@code and}/{@code or} inline the selected
 * body and then require a canonical Boolean result, {@code not} needs no
 * callback, and a canonical literal receiver that selects no callback admits
 * any argument expression, which is still evaluated but neither validated nor
 * invoked.
 *
 * <p>Topology is read from the bytecode of the root that called the native
 * {@code probe} first, lowered under the entered Context's immutable profile
 * (I101-A): after the canonical classification, the
 * {@code IsCanonicalIfTrueTrue} and {@code IsCanonicalBooleanFalseClass}
 * branches hold the TRUE and FALSE paths and the next prepared send is MISS.
 * An aggressive inline path contains no prepared send, no prepared Boolean
 * call and no B-prime admission. Physical callees are seen through the probe's
 * CallTarget stack (depth 2 is a callee of the module root). No test mutates
 * the frozen global root.
 */
final class ProtosI095BooleanProtocolInlineTest {
    private static final Path CORE = Path.of("protos", "lib", "core");

    private static final LanguageReference<ProtosLanguage> LANGUAGE_REF =
            LanguageReference.create(ProtosLanguage.class);

    private static final int INLINE_DEPTH = 2;

    private static final String CODE = """
            code: (value) => {
                (value === null).ifTrueIfFalse(() => { 0 }, () => {
                    (value === true).ifTrueIfFalse(() => { 1 }, () => {
                        (value === false).ifTrueIfFalse(() => { 2 }, () => { value })
                    })
                })
            }
            """;

    /** not, and, or and ifTrueIfFalse rows, literal and dynamic receivers, operators. */
    @Test
    void remainingStandardRowsAndOperators() throws Exception {
        // Digits: true 1, false 2, selected results 7 and 8.
        String constant = CODE + """
                run: () => {
                    a: code(true.not())
                    b: code(!false)
                    c: code(true && false)
                    d: code(false && 9)
                    e: code(true || 9)
                    g: code(false || true)
                    h: code(true.ifTrueIfFalse(() => { 7 }, () => { 8 }))
                    i: code(false.ifTrueIfFalse(() => { 7 }, () => { 8 }))
                    ((((((a * 10 + b) * 10 + c) * 10 + d) * 10 + e) * 10 + g) * 10 + h) * 10 + i
                }
                run()
                """;
        assertParity(constant, 21221178);

        String dynamic = CODE + """
                run: (t, f) => {
                    a: code(t.not())
                    b: code(!f)
                    c: code(t.and(() => { f }))
                    d: code(f.and(() => { 9 }))
                    e: code(t.or(() => { 9 }))
                    g: code(f.or(() => { t }))
                    h: code(t.ifTrueIfFalse(() => { 7 }, () => { 8 }))
                    i: code(f.ifTrueIfFalse(() => { 7 }, () => { 8 }))
                    ((((((a * 10 + b) * 10 + c) * 10 + d) * 10 + e) * 10 + g) * 10 + h) * 10 + i
                }
                run(true, false) * 1000 + run(true, false) % 1000
                """;
        assertParity(dynamic, 21221178L * 1000 + 178);
        System.out.println("I095_D_STANDARD_ROWS=PASS");
    }

    /** Both literal callbacks of ifTrueIfFalse run in the caller's root. */
    @Test
    void ifTrueIfFalseInlinesBothBodiesWithoutPreparation() throws Exception {
        String source = """
                run: (b) => {
                    p: probe
                    p()
                    b.ifTrueIfFalse(() => {
                        inner: 1
                        p()
                        inner
                    }, () => {
                        inner: 2
                        p()
                        inner
                    })
                }
                run(true) * 10 + run(false)
                """;
        Execution normal = execute(false, source);
        assertInteger(12, normal.value());
        assertEquals(List.of(INLINE_DEPTH), normal.probe().depths(), normal.probe()::toString);
        ProtosSemanticBytecodeRootNode runRoot = normal.rootOfFirstProbe();
        Regions regions = regions(instructionNames(runRoot));
        assertAggressive(regions.onTrue());
        assertAggressive(regions.onFalse());
        List<String> sources = rootTagSources(runRoot.getBytecodeNode().getTagTree());
        assertTrue(sources.stream().anyMatch(text -> text.contains("inner: 1")
                        && !text.contains("inner: 2")), sources::toString);
        assertTrue(sources.stream().anyMatch(text -> text.contains("inner: 2")
                        && !text.contains("inner: 1")), sources::toString);
        assertParity(source, 12);

        String miss = source.replace(
                "run(true) * 10 + run(false)",
                """
                custom: { ifTrueIfFalse: (t, f) => { t() * 100 + f() } }
                run(true) * 1000 + run(false) * 100 + run(custom)
                """);
        assertParity(miss, 1000 + 200 + 102);
        System.out.println("I095_D_IF_TRUE_IF_FALSE_TWO_BODIES=PASS");
    }

    /** Each callback is proven independently; an unproven one keeps its root. */
    @Test
    void ifTrueIfFalsePartialAdmissionFallsBackPerBranch() throws Exception {
        String inlineTrue = """
                run: (b) => {
                    p: probe
                    p()
                    b.ifTrueIfFalse(() => {
                        inner: 1
                        p()
                        inner
                    }, () => {
                        nested: () => { 2 }
                        p()
                        nested()
                    })
                }
                run(true) * 10 + run(false)
                """;
        Execution forward = execute(false, inlineTrue);
        assertInteger(12, forward.value());
        assertEquals(List.of(INLINE_DEPTH, INLINE_DEPTH, INLINE_DEPTH, INLINE_DEPTH + 1),
                forward.probe().stacks.stream().map(List::size).toList(),
                forward.probe()::toString);
        Regions forwardRegions = regions(instructionNames(forward.rootOfFirstProbe()));
        assertAggressive(forwardRegions.onTrue());
        assertEquals(null, forwardRegions.onFalse(), "FALSE takes the shared ordinary send");
        assertParity(inlineTrue, 12);

        String inlineFalse = """
                run: (b) => {
                    p: probe
                    p()
                    b.ifTrueIfFalse(() => {
                        nested: () => { 1 }
                        p()
                        nested()
                    }, () => {
                        inner: 2
                        p()
                        inner
                    })
                }
                run(true) * 10 + run(false)
                """;
        Execution reversed = execute(false, inlineFalse);
        assertInteger(12, reversed.value());
        assertEquals(List.of(INLINE_DEPTH, INLINE_DEPTH + 1, INLINE_DEPTH, INLINE_DEPTH),
                reversed.probe().stacks.stream().map(List::size).toList(),
                reversed.probe()::toString);
        Regions regions = regions(instructionNames(reversed.rootOfFirstProbe()));
        assertEquals(null, regions.onTrue(), "TRUE takes the shared ordinary send");
        assertAggressive(regions.onFalse());
        assertParity(inlineFalse, 12);

        String dynamicArguments = """
                run: (b) => {
                    callable: { call: () => { 8 } }
                    first: b.ifTrueIfFalse(callable, () => { 2 })
                    second: b.ifTrueIfFalse(() => { 3 }, callable)
                    first * 100 + second * 10
                }
                pick: (b) => { b.ifTrueIfFalse(() => { 4 }, 5) }
                pick(true) * 1000000 + run(true) * 1000 + run(false)
                """;
        assertParity(dynamicArguments, 4830280);
        System.out.println("I095_D_IF_TRUE_IF_FALSE_PARTIAL_ADMISSION=PASS");
    }

    /** Receiver then both arguments, once and in order, before any callback. */
    @Test
    void argumentsAreEvaluatedOnceInOrderBeforeSelection() throws Exception {
        String source = """
                log: 0
                note: (n) => { log = log * 10 + n }
                rec: (x) => {
                    note(1)
                    x
                }
                arg: (n) => {
                    note(n)
                    () => {
                        note(9)
                        n
                    }
                }
                run: (v, mark) => {
                    probe()
                    rec(v).ifTrueIfFalse(arg(2), () => {
                        mark(5)
                        5
                    })
                }
                custom: { ifTrueIfFalse: (a, b) => { a() + b() } }
                a: run(true, note)
                first: log
                log = 0
                b: run(false, note)
                second: log
                log = 0
                c: run(custom, note)
                third: log
                ((first * 1000 + second) * 10000 + third) * 1000 + a * 100 + b * 10 + c
                """;
        Execution normal = execute(false, source);
        assertTrue(position(instructionNames(normal.rootOfFirstProbe()),
                        "TryCompactCanonicalBooleanOne", 0) >= 0,
                "the site is admitted");
        assertParity(source, 1291251295257L);

        String failing = """
                run: (v) => {
                    log: 0
                    fail: () => {
                        log = log * 10 + 3
                        Error().signal()
                    }
                    r: Error.handle(() => {
                        v.ifTrueIfFalse(() => {
                            log = log * 10 + 7
                            0
                        }, fail())
                    }, (caught) => { 1 })
                    log * 10 + r
                }
                run(true) * 100 + run(false)
                """;
        assertParity(failing, 31 * 100 + 31);
        System.out.println("I095_D_ARGUMENTS_ONCE_IN_ORDER=PASS");
    }

    /** A literal receiver that selects nothing still evaluates any argument. */
    @Test
    void unselectedNonLiteralArgumentsAreEvaluatedButNotInvoked() throws Exception {
        String source = """
                run: () => {
                    probe()
                    count: 0
                    produce: (value) => {
                        count = count + 1
                        value
                    }
                    a: false.ifTrue(produce(1))
                    b: true.ifFalse(produce({ marker: 1 }))
                    c: false.if(produce(3))
                    d: true.else(produce(4))
                    e: false.and(produce(5))
                    g: true.or(produce(6))
                    h: true.ifTrueIfFalse(() => { count }, produce(7))
                    ok: (a === null) && (b === null) && (c === false) && (d === true) && (e === false) && (g === true)
                    ok.ifTrueIfFalse(() => { h * 10 + count }, () => { -1 })
                }
                run()
                """;
        assertParity(source, 7 * 10 + 7);

        String structure = """
                run: (callback) => {
                    probe()
                    false.ifTrue(callback)
                }
                (run(5) === null).ifTrueIfFalse(() => { 1 }, () => { 0 })
                """;
        Execution normal = execute(false, structure);
        assertInteger(1, normal.value());
        // I101-A: the literal receiver selects nothing statically, so the
        // admitted NORMAL site keeps neither a classification nor a send.
        List<String> structureNames = instructionNames(normal.rootOfFirstProbe());
        assertTrue(position(structureNames, "PrepareSend", 0) < 0
                        && position(structureNames, "TryCompactCanonicalBooleanOne", 0) < 0,
                () -> "an unselected binding argument is admitted: " + structureNames);

        String error = """
                run: () => {
                    false.ifTrue(Error().signal())
                    0
                }
                Error.handle(() => { run() }, (caught) => { 42 })
                """;
        assertParity(error, 42);

        String alternating = """
                run: (receiver, callback) => {
                    receiver.ifTrue(callback)
                }
                custom: { ifTrue: (callback) => { callback() + 30 } }
                dynamic: () => { 3 }
                a: run(true, dynamic)
                b: run(false, 5)
                c: run(custom, dynamic)
                (b === null).ifTrueIfFalse(() => { a * 100 + c }, () => { -1 })
                """;
        assertParity(alternating, 333);
        System.out.println("I095_D_UNSELECTED_ARGUMENTS=PASS");
    }

    /** and/or run the selected body inline and validate its result strictly. */
    @Test
    void andOrInlineSelectedBodyAndRequireCanonicalBoolean() throws Exception {
        String andSource = """
                run: (b) => {
                    p: probe
                    p()
                    b.and(() => {
                        inner: 1
                        p()
                        inner === 1
                    })
                }
                (run(true) === true) && (run(false) === false)
                """;
        Execution and = execute(false, andSource);
        assertEquals(ProtosBooleanValue.TRUE, and.value());
        assertEquals(List.of(INLINE_DEPTH), and.probe().depths(), and.probe()::toString);
        Regions andRegions = regions(instructionNames(and.rootOfFirstProbe()));
        assertAggressive(andRegions.onTrue());
        assertTrue(andRegions.onTrue().stream()
                        .anyMatch(name -> name.contains("RequireCanonicalBooleanControlResult")),
                andRegions.onTrue()::toString);
        assertFalse(andRegions.onFalse().stream()
                        .anyMatch(name -> name.contains("RequireCanonicalBooleanControlResult")),
                andRegions.onFalse()::toString);

        String orSource = """
                run: (b) => {
                    p: probe
                    p()
                    b.or(() => {
                        inner: 2
                        p()
                        inner === 1
                    })
                }
                (run(true) === true) && (run(false) === false)
                """;
        Execution or = execute(false, orSource);
        assertEquals(ProtosBooleanValue.TRUE, or.value());
        Regions orRegions = regions(instructionNames(or.rootOfFirstProbe()));
        assertAggressive(orRegions.onFalse());
        assertTrue(orRegions.onFalse().stream()
                        .anyMatch(name -> name.contains("RequireCanonicalBooleanControlResult")),
                orRegions.onFalse()::toString);

        // Every non-Boolean normal result fails after the callback ran once.
        String invalid = """
                hits: 0
                mark: () => { hits = hits + 1 }
                andRun: (b, v, m) => {
                    b.and(() => {
                        m()
                        v
                    })
                }
                orRun: (b, v, m) => {
                    b.or(() => {
                        m()
                        v
                    })
                }
                failed: 0
                check: (block) => {
                    Error.handle(block, (caught) => { failed = failed + 1 })
                }
                check(() => { andRun(true, null, mark) })
                check(() => { andRun(true, 5, mark) })
                check(() => { andRun(true, { marker: 1 }, mark) })
                check(() => { orRun(false, null, mark) })
                check(() => { orRun(false, 5, mark) })
                check(() => { orRun(false, { marker: 1 }, mark) })
                skipped: (andRun(false, null, mark) === false) && (orRun(true, 5, mark) === true)
                valid: (andRun(true, false, mark) === false) && (orRun(false, true, mark) === true)
                (skipped && valid).ifTrueIfFalse(() => { hits * 10 + failed }, () => { -1 })
                """;
        assertParity(invalid, 86);

        String future = """
                seen: 0
                mark: () => { seen = seen + 1 }
                andFuture: (b, m) => {
                    b.and(() => {
                        m()
                        (() => { 41 }).future()
                    })
                }
                r: Error.handle(() => { andFuture(true, mark) }, (caught) => { 40 })
                r + seen + seen
                """;
        assertExecutedOutcome(future, 42);
        System.out.println("I095_D_AND_OR_INLINE_STRICT_BOOLEAN=PASS");
    }

    /** && and || keep their ordinary sends with a lazily evaluated right operand. */
    @Test
    void operatorsShortCircuitAndKeepOrdinaryDispatch() throws Exception {
        String source = """
                run: (t, f) => {
                    count: 0
                    effect: (value) => {
                        count = count + 1
                        value
                    }
                    a: f && effect(true)
                    b: t || effect(false)
                    c: f || effect(true)
                    d: t && effect(false)
                    e: true.and(() => { true })
                    g: false.or(() => { false })
                    ok: (a === false) && (b === true) && (c === true) && (d === false) && (e === true) && (g === false)
                    ok.ifTrueIfFalse(() => { count }, () => { -1 })
                }
                run(true, false)
                """;
        assertParity(source, 2);

        String custom = """
                observed: null
                custom: {
                    and: (block) => {
                        observed = block
                        block() + 1
                    }
                    or: (block) => { block() * 2 }
                }
                a: custom && 41
                b: custom || 21
                callable: (observed === null).ifTrueIfFalse(() => { 0 }, () => { observed() })
                a * 1000 + b * 10 + callable - 41
                """;
        assertParity(custom, 42 * 1000 + 42 * 10);
        System.out.println("I095_D_OPERATORS_SHORT_CIRCUIT=PASS");
    }

    /** not and ! need no physical standard activation for canonical receivers. */
    @Test
    void notSelectsCanonicalResultAndKeepsOrdinaryMiss() throws Exception {
        for (String expression : List.of("b.not()", "!b")) {
            String source = """
                    run: (b) => {
                        probe()
                        %s
                    }
                    custom: { not: () => { 7 } }
                    t: (run(false) === true) && (run(true) === false)
                    t.ifTrueIfFalse(() => { run(custom) }, () => { -1 })
                    """.formatted(expression);
            Execution normal = execute(false, source);
            assertInteger(7, normal.value());
            List<String> names = instructionNames(normal.rootOfFirstProbe());
            Regions regions = regions(names);
            assertAggressive(regions.onTrue());
            assertAggressive(regions.onFalse());
            int falseClass = position(names, "IsCanonicalBooleanFalseClass", 0);
            assertTrue(position(names, "PrepareSendZero", falseClass) > falseClass,
                    names::toString);
            assertParity(source, 7);
        }

        String arity = """
                a: Error.handle(() => { true.not(1) }, (caught) => { 10 })
                b: Error.handle(() => { false.not(...17) }, (caught) => { 20 })
                a + b
                """;
        assertParity(arity, 30);
        System.out.println("I095_D_NOT_CANONICAL_AND_OVERRIDE=PASS");
    }

    /** Selector and call overrides, selected results of every family. */
    @Test
    void overridesAndSelectedResultFamilies() throws Exception {
        String source = """
                run: (b) => {
                    callable: { call: () => { 8 } }
                    callable.removeSlot("call")
                    callable.call: () => { 9 }
                    x: b.ifTrueIfFalse(callable, () => { 2 })
                    custom: { ifTrueIfFalse: (t, f) => { 100 } }
                    y: custom.ifTrueIfFalse(() => { 1 }, () => { 2 })
                    n: b.ifTrueIfFalse(() => { null }, () => { { marker: 3 } })
                    m: (n === null).ifTrueIfFalse(() => { 0 }, () => { n.marker })
                    k: b.ifTrueIfFalse(() => { false }, () => { true })
                    kk: k.ifTrueIfFalse(() => { 1 }, () => { 0 })
                    x * 1000 + y + m * 10 + kk
                }
                run(true) * 10000 + run(false)
                """;
        assertParity(source, 9100L * 10000 + 2100 + 30 + 1);

        String future = """
                run: (b) => {
                    f: b.ifTrueIfFalse(() => { (() => { 41 }).future() }, () => { null })
                    f.value() + 1
                }
                run(true)
                """;
        assertExecutedOutcome(future, 42);
        System.out.println("I095_D_OVERRIDES_AND_RESULTS=PASS");
    }

    /** Captures, shadowing and recreation, independently in both bodies. */
    @Test
    void bindingsAreIndependentInBothBodies() throws Exception {
        String source = """
                run: (b) => {
                    outer: 40
                    shadow: 11
                    r: b.ifTrueIfFalse(() => {
                        shadow: 1
                        outer = outer + shadow
                        outer + shadow
                    }, () => {
                        shadow: 2
                        outer = outer - shadow
                        outer + shadow
                    })
                    r + outer + shadow
                }
                run(true) * 1000 + run(false)
                """;
        assertParity(source, 94L * 1000 + 89);

        String recreation = """
                run: (b) => {
                    x: 1
                    a: b.ifTrueIfFalse(() => { x + 10 }, () => { x + 20 })
                    context.removeSlot("x")
                    context.x: 5
                    c: b.ifTrueIfFalse(() => { x + 10 }, () => { x + 20 })
                    a * 100 + c
                }
                run(true) * 10000 + run(false)
                """;
        assertParity(recreation, 1115L * 10000 + 2125);

        String loop = """
                run: (b) => {
                    total: 0
                    i: 0
                    (() => i < 3).whileTrue(() => {
                        b.ifTrueIfFalse(() => {
                            own: i
                            own = own + 1
                            total = total + own
                        }, () => {
                            own: i
                            total = total + own * 10
                        })
                        i = i + 1
                    })
                    total
                }
                run(true) * 1000 + run(false)
                """;
        assertParity(loop, 6 * 1000 + 30);
        System.out.println("I095_D_BINDINGS_PRESERVED=PASS");
    }

    /** Error, non-local return, ensure and suspension cross and/or/ifTrueIfFalse. */
    @Test
    void abruptExitsEnsureAndSuspension() throws Exception {
        String error = """
                cleaned: 0
                run: (b) => {
                    (() => {
                        b.ifTrueIfFalse(() => { 1 }, () => {
                            inner: 1
                            Error().signal()
                            inner
                        })
                    }).ensure(() => { cleaned = cleaned + 1 })
                    99
                }
                r: Error.handle(() => { run(false) }, (caught) => { 40 })
                r + cleaned + cleaned
                """;
        assertParity(error, 42);

        String nonLocal = """
                run: (b) => {
                    b.or(() => { ^41 })
                    b.ifTrueIfFalse(() => { ^1 }, () => { 0 })
                    0
                }
                run(false) + run(true)
                """;
        assertParity(nonLocal, 42);

        String suspension = """
                run: (b) => {
                    pending: (() => { 41 }).future()
                    r: b.and(() => {
                        inner: pending.value()
                        inner === 41
                    })
                    s: b.ifTrueIfFalse(() => {
                        inner: pending.value()
                        inner + 1
                    }, () => { 0 })
                    r.ifTrueIfFalse(() => { s }, () => { -1 })
                }
                run(true)
                """;
        assertExecutedOutcome(suspension, 42);
        System.out.println("I095_D_ABRUPT_AND_SUSPENSION=PASS");
    }

    /** NORMAL and DEBUG agree and each selected callback runs exactly once. */
    @Test
    void normalAndDebugAgreeOnSingleCallbackExecution() throws Exception {
        String source = """
                run: (b) => {
                    p: probe
                    value: 9
                    r: b.ifTrueIfFalse(() => {
                        p()
                        value
                    }, () => {
                        p()
                        0
                    })
                    s: b.and(() => {
                        p()
                        true
                    })
                    s.ifTrueIfFalse(() => { r }, () => { -1 })
                }
                run(true)
                """;
        Execution normal = execute(false, source);
        Execution debug = execute(true, source);
        assertInteger(9, normal.value());
        assertInteger(9, debug.value());
        assertEquals(2, normal.probe().calls.get());
        assertEquals(2, debug.probe().calls.get());
        System.out.println("I095_D_NORMAL_DEBUG_PARITY=PASS");
    }

    /**
     * Staged operands are emitted once for every canonical path and MISS,
     * so a chain of admitted sites whose receivers are admitted sites lowers
     * in linear size (per-path copies would double at every level).
     */
    @Test
    void longOperatorChainLowersLinearly() throws Exception {
        String chain = String.join(" && ", java.util.Collections.nCopies(24, "(b === b)"));
        String source = """
                run: (b) => { %s }
                (run(true) === true).ifTrueIfFalse(() => { 1 }, () => { 0 })
                """.formatted(chain);
        assertParity(source, 1);
        System.out.println("I095_D_LINEAR_CHAIN=PASS");
    }

    /** ifNull and ifNotNull keep their standard results through ifTrueIfFalse. */
    @Test
    void ifNullAndIfNotNullKeepTheirSemantics() throws Exception {
        String source = """
                five: 5
                a: null.ifNull(() => { 1 })
                b: five.ifNull(() => { 1 })
                c: null.ifNotNull((v) => { v })
                d: five.ifNotNull((v) => { v + 1 })
                ok: (b === five) && (c === null)
                ok.ifTrueIfFalse(() => { a * 10 + d }, () => { -1 })
                """;
        assertParity(source, 16);
        System.out.println("I095_D_IF_NULL_REGRESSION=PASS");
    }

    /**
     * TRUE and FALSE paths completed by the first admitted dynamic Boolean
     * control; null when that canonical receiver has no own path and takes
     * the shared ordinary send (MISS and ORDINARY), whose prepared send
     * follows the last classification test.
     */
    private record Regions(List<String> onTrue, List<String> onFalse) {}

    private static Regions regions(List<String> names) {
        int classify = position(names, "TryCompactCanonicalBooleanOne", 0);
        int trueClass = position(names, "IsCanonicalIfTrueTrue", classify);
        int falseClass = position(names, "IsCanonicalBooleanFalseClass", classify);
        int last = Math.max(trueClass, falseClass);
        int shared = position(names, "PrepareSend", last);
        assertTrue(classify >= 0 && last > classify
                        && (trueClass < 0 || falseClass < 0 || falseClass > trueClass)
                        && shared > last,
                names::toString);
        return new Regions(
                trueClass < 0 ? null
                        : names.subList(trueClass + 1, falseClass > trueClass ? falseClass : shared),
                falseClass < 0 ? null : names.subList(falseClass + 1, shared));
    }

    /**
     * No prepared send of the control and no B-prime admission. Calls made by
     * the inline body itself keep the generic prepared-call dispatch, which
     * includes its structured-call check, so that check is not evidence.
     */
    private static void assertAggressive(List<String> region) {
        assertFalse(region.stream().anyMatch(name -> name.contains("PrepareSend")
                        || name.contains("AdmitsInlineLiteralCallback")),
                region::toString);
    }

    private record Execution(Object value, Probe probe) {
        /** The guest root that called probe first. */
        ProtosSemanticBytecodeRootNode rootOfFirstProbe() {
            return assertInstanceOf(
                    ProtosSemanticBytecodeRootNode.class,
                    probe.stacks.get(0).get(0));
        }
    }

    private static Execution execute(boolean debug, String source) throws Exception {
        Context.Builder builder = Context.newBuilder(ProtosLanguage.ID);
        if (debug) {
            builder.allowExperimentalOptions(true).option("protos.Debug", "true");
        }
        try (Context context = builder.build()) {
            context.initialize(ProtosLanguage.ID);
            context.enter();
            try {
                Probe probe = new Probe();
                Object value = lowerRoot(source).getCallTarget().call(probe.module());
                return new Execution(value, probe);
            } finally {
                context.leave();
            }
        }
    }

    private static void assertParity(String source, long expected) throws Exception {
        assertInteger(expected, execute(false, source).value());
        assertInteger(expected, execute(true, source).value());
    }

    /** Pending Futures need the C-prime continuation entry of the hosted boundary. */
    private static void assertExecutedOutcome(String source, long expected) throws Exception {
        ProtosExecutionOutcome outcome =
                ProtosTestExecutionSupport.execute(
                        "i095-d-execution.protos",
                        source,
                        new ProtosCoreBootstrap().bootstrap(CORE).newModuleActivation());
        assertEquals(
                ProtosExecutionOutcome.State.COMPLETED,
                outcome.state(),
                () -> "Outcome: " + outcome.state() + " error=" + outcome.error());
        assertInteger(expected, outcome.value());
    }

    /** A native {@code probe} recording the guest CallTarget stack of every call. */
    private static final class Probe {
        final List<List<RootNode>> stacks = new ArrayList<>();
        final AtomicInteger calls = new AtomicInteger();

        List<Integer> depths() {
            return stacks.stream().map(List::size).distinct().toList();
        }

        ProtosActivation module() throws Exception {
            ProtosActivation module =
                    new ProtosCoreBootstrap().bootstrap(CORE).newModuleActivation();
            module.context().createLocalSlot(
                    "probe",
                    ProtosClosureValue.nativeClosure(
                            (activation, supplied) -> {
                                calls.incrementAndGet();
                                List<RootNode> stack = new ArrayList<>();
                                Truffle.getRuntime()
                                        .iterateFrames(
                                                frame -> {
                                                    stack.add(
                                                            ((RootCallTarget) frame.getCallTarget())
                                                                    .getRootNode());
                                                    return null;
                                                });
                                stacks.add(stack);
                                return ProtosNullValue.INSTANCE;
                            }));
            return module;
        }

        @Override
        public String toString() {
            return "stacks=" + stacks;
        }
    }

    private static ProtosSemanticBytecodeRootNode lowerRoot(String characters) {
        Source source =
                Source.newBuilder(ProtosLanguage.ID, characters, "i095-d.protos").build();
        CanonicalSequence sequence =
                (CanonicalSequence)
                        new Canonicalizer()
                                .canonicalize(new ProtosParser(characters).parseProgram());
        // I101-A: lowered under the immutable profile of the entered Context.
        return new CanonicalToBytecodeLowerer(
                        LANGUAGE_REF.get(null),
                        source,
                        ProtosLanguageContext.current().executionMode())
                .lowerRoot(sequence);
    }

    private static List<String> instructionNames(ProtosSemanticBytecodeRootNode root) {
        root.getRootNodes().ensureComplete();
        return root.getBytecodeNode().getInstructionsAsList().stream()
                .map(Instruction::getName)
                .toList();
    }

    private static int position(List<String> names, String operation, int from) {
        for (int i = Math.max(from, 0); i < names.size(); i++) {
            if (names.get(i).contains(operation)) {
                return i;
            }
        }
        return -1;
    }

    private static void assertInteger(long expected, Object value) {
        assertEquals(
                Long.toString(expected),
                com.guillermomolina.protos.runtime.ProtosNumericValueSupport
                        .integerDecimalText(value));
    }

    private static List<String> rootTagSources(TagTree tree) {
        List<String> sources = new ArrayList<>();
        if (tree == null) {
            return sources;
        }
        if (tree.hasTag(StandardTags.RootTag.class) && tree.getSourceSection() != null) {
            sources.add(tree.getSourceSection().getCharacters().toString());
        }
        for (TagTree child : tree.getTreeChildren()) {
            sources.addAll(rootTagSources(child));
        }
        return sources;
    }
}
