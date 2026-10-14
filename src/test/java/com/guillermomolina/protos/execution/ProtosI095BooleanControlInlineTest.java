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
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.guillermomolina.protos.parser.ProtosParser;
import com.guillermomolina.protos.runtime.ProtosActivation;
import com.guillermomolina.protos.runtime.ProtosClosureValue;
import com.guillermomolina.protos.runtime.ProtosNullValue;
import com.guillermomolina.protos.semantic.Canonicalizer;
import com.guillermomolina.protos.semantic.ast.CanonicalSequence;
import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.TruffleLanguage.LanguageReference;
import com.oracle.truffle.api.bytecode.BytecodeNode;
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
 * I095-C focal evidence: ordinary {@code ifTrue}, {@code ifFalse}, {@code if}
 * and {@code else} sends share one NORMAL lowering. A canonical literal
 * receiver selects its branch statically; any other receiver is evaluated
 * once and classified at run time, a non-canonical receiver keeping the
 * ordinary send. A selected literal callback whose whole activation is proven
 * unobservable runs inline in the caller's Bytecode root. DEBUG mode and
 * unproven callbacks keep the established topology.
 *
 * <p>I101-A: each program is lowered under the immutable profile of the
 * entered Context, so a NORMAL root holds only the admitted lowering and a
 * DEBUG root only the ordinary one. Inline admission is observed through
 * {@code run}'s bytecode; physical callees through the guest CallTarget stack
 * seen by a native {@code probe} (depth 2 is {@code run} over the module
 * root). No test mutates the frozen global root.
 */
final class ProtosI095BooleanControlInlineTest {
    private static final Path CORE = Path.of("protos", "lib", "core");

    private static final LanguageReference<ProtosLanguage> LANGUAGE_REF =
            LanguageReference.create(ProtosLanguage.class);

    private static final int INLINE_DEPTH = 2;

    /** The eight rows of the standard table, constant and dynamic receivers. */
    @Test
    void allEightStandardRowsForConstantAndDynamicReceivers() throws Exception {
        // Digits: selected result 7, unselected null 0, true 1, false 2.
        String code = """
                code: (value) => {
                    (value === null).ifTrueIfFalse(() => { 0 }, () => {
                        (value === true).ifTrueIfFalse(() => { 1 }, () => {
                            (value === false).ifTrueIfFalse(() => { 2 }, () => { value })
                        })
                    })
                }
                """;
        String constant = code + """
                run: () => {
                    a: code(true.ifTrue(() => { 7 }))
                    b: code(false.ifTrue(() => { 9 }))
                    c: code(true.ifFalse(() => { 9 }))
                    d: code(false.ifFalse(() => { 7 }))
                    e: code(true.if(() => { 9 }))
                    f: code(false.if(() => { 9 }))
                    g: code(true.else(() => { 9 }))
                    h: code(false.else(() => { 9 }))
                    ((((((a * 10 + b) * 10 + c) * 10 + d) * 10 + e) * 10 + f) * 10 + g) * 10 + h
                }
                run()
                """;
        assertParity(constant, 70071212);

        String dynamic = code + """
                row: (t, f) => {
                    a: code(t.ifTrue(() => { 7 }))
                    b: code(f.ifTrue(() => { 9 }))
                    c: code(t.ifFalse(() => { 9 }))
                    d: code(f.ifFalse(() => { 7 }))
                    e: code(t.if(() => { 9 }))
                    g: code(f.if(() => { 9 }))
                    h: code(t.else(() => { 9 }))
                    i: code(f.else(() => { 9 }))
                    ((((((a * 10 + b) * 10 + c) * 10 + d) * 10 + e) * 10 + g) * 10 + h) * 10 + i
                }
                row(true, false) * 10 + row(true, false)
                """;
        assertParity(dynamic, 700712120L + 70071212L);
        System.out.println("I095_C_EIGHT_ROWS=PASS");
    }

    /**
     * Large Integer literals (minted with the executing Prelude) inside an
     * admitted callback body and inside a Closure called from one (the
     * TOOL009 conformance-case shape).
     */
    @Test
    void largeIntegerLiteralsInsideAdmittedBodies() throws Exception {
        String called = """
                run: () => {
                    big: () => {
                        999999999999999999999999 * 999999999999999999999999
                    }
                    (big() == 999999999999999999999998000000000000000000000001).ifTrue(() => { 7 })
                }
                run()
                """;
        assertParity(called, 7);
        assertExecutedOutcome(called, 7);

        String dynamic = """
                run: (b) => {
                    b.ifTrue(() => {
                        (999999999999999999999999.div(3) == 333333333333333333333333).ifTrue(() => { 7 })
                    })
                }
                run(true)
                """;
        assertParity(dynamic, 7);
        assertExecutedOutcome(dynamic, 7);
    }

    /** One site alternates true/false and then sees a non-canonical receiver. */
    @Test
    void dynamicSiteAlternatesAndMissesToOrdinaryDispatch() throws Exception {
        String source = """
                run: (b) => {
                    probe()
                    b.if(() => {
                        inner: 5
                        inner + 1
                    })
                }
                custom: {
                    if: (callback) => {
                        probe()
                        callback() + 30
                    }
                }
                code: (value) => {
                    (value === true).ifTrueIfFalse(() => { 1 }, () => {
                        (value === false).ifTrueIfFalse(() => { 2 }, () => { value })
                    })
                }
                code(run(true)) * 1000 + code(run(false)) * 100 + code(run(true)) * 10 + 0 * code(run(false)) + run(custom)
                """;
        Execution normal = execute(false, source);
        assertInteger(1210 + 36, normal.value());
        List<String> names = instructionNames(normal.rootOfFirstProbe());
        int classify = position(names, "TryCompactCanonicalBooleanOne");
        int falseClass = position(names, "IsCanonicalBooleanFalseClass");
        // I101-A: the classifier is the admitted site's semantic guard; no mode test precedes it.
        assertTrue(classify >= 0, () -> "no canonical classification: " + names);
        assertTrue(falseClass > classify,
                () -> "falseClass " + falseClass + " classify " + classify);
        // MISS keeps an ordinary prepared send in the NORMAL lowering.
        int prepare = falseClass + 1
                + position(names.subList(falseClass + 1, names.size()), "PrepareSendOne");
        assertTrue(prepare > falseClass,
                () -> "prepare " + prepare + " falseClass " + falseClass);
        // The custom method is a physical callee above run.
        assertTrue(
                normal.probe().depths().stream().anyMatch(depth -> depth > INLINE_DEPTH),
                normal.probe()::toString);
        assertParity(source, 1246);
        System.out.println("I095_C_DYNAMIC_ALTERNATION_AND_MISS=PASS");
    }

    /** An admitted callback runs in run's root with its own semantic RootTag. */
    @Test
    void admittedCallbackRunsInTheCallerRootWithItsOwnRootTag() throws Exception {
        String source = """
                run: (b) => {
                    p: probe
                    outer: 40
                    b.ifFalse(() => {
                        inner: 2
                        p()
                        outer + inner
                    })
                }
                run(false)
                """;
        Execution normal = execute(false, source);
        assertInteger(42, normal.value());
        assertEquals(List.of(INLINE_DEPTH), normal.probe().depths(), normal.probe()::toString);
        ProtosSemanticBytecodeRootNode runRoot = normal.rootOfFirstProbe();
        // Materializes the tag tree read below.
        runRoot.getRootNodes().ensureComplete();
        assertTrue(rootTags(runRoot.getBytecodeNode()) >= 2,
                "run's RootTag plus the inline callback's semantic RootTag");
        assertTrue(
                rootTagSources(runRoot.getBytecodeNode().getTagTree()).stream()
                        .anyMatch(text -> text.contains("inner: 2")
                                && !text.contains("outer: 40")),
                () -> rootTagSources(runRoot.getBytecodeNode().getTagTree()).toString());
        assertParity(source, 42);
        System.out.println("I095_C_INLINE_ROOT_TAG_AND_SOURCE=PASS");
    }

    /**
     * NORMAL and DEBUG agree on the result and on exactly one callback
     * execution, each under its own profile-specific lowering.
     */
    @Test
    void normalAndDebugAgreeOnResultAndSingleCallbackExecution() throws Exception {
        String source = """
                run: (b) => {
                    p: probe
                    value: 9
                    r: b.if(() => {
                        value: 41
                        value = 42
                        p()
                        value
                    })
                    (r === b).ifTrueIfFalse(() => { value }, () => { -1 })
                }
                run(true)
                """;
        Execution normal = execute(false, source);
        Execution debug = execute(true, source);
        assertInteger(9, normal.value());
        assertInteger(9, debug.value());
        assertEquals(1, normal.probe().calls.get());
        assertEquals(1, debug.probe().calls.get());
        System.out.println("I095_C_NORMAL_DEBUG_PARITY=PASS");
    }

    /** Receiver and argument are evaluated once, in order, on every path. */
    @Test
    void receiverAndArgumentAreEvaluatedExactlyOnce() throws Exception {
        String source = """
                trace: () => {
                    order: 0
                    hits: 0
                    index: 0
                    next: () => {
                        index = index + 1
                        order = order * 10 + 1
                        (index === 2).ifTrueIfFalse(() => { false }, () => { true })
                    }
                    a: next().ifTrue(() => {
                        hits = hits + 1
                        order = order * 10 + 2
                        5
                    })
                    b: next().else(() => {
                        hits = hits + 1
                        order = order * 10 + 3
                        5
                    })
                    c: next().ifFalse(() => {
                        hits = hits + 100
                        9
                    })
                    nullMark: (c === null).ifTrueIfFalse(() => { 1 }, () => { 0 })
                    falseMark: (b === false).ifTrueIfFalse(() => { 1 }, () => { 0 })
                    order * 1000 + hits * 100 + a * 10 + nullMark + falseMark
                }
                trace()
                """;
        assertParity(source, 12131L * 1000 + 2 * 100 + 50 + 2);

        String producer = """
                trace: () => {
                    count: 0
                    producer: () => {
                        count = count + 1
                        () => { 1 }
                    }
                    run: (receiver) => {
                        receiver.if(producer())
                    }
                    custom: { if: (callback) => { callback() + 10 } }
                    run(true)
                    run(false)
                    r: run(custom)
                    count * 100 + r
                }
                trace()
                """;
        assertParity(producer, 311);
        System.out.println("I095_C_EVALUATED_ONCE=PASS");
    }

    /** An unselected literal is neither validated nor invoked. */
    @Test
    void unselectedInvalidLiteralIsNotInvoked() throws Exception {
        String source = """
                run: (t, f) => {
                    a: t.ifFalse((x, y) => { x + y })
                    b: f.ifTrue((x, y) => { x + y })
                    c: t.else((x) => { x })
                    d: f.if((x) => { x })
                    e: true.ifFalse((x, y) => { x + y })
                    g: false.if((x) => { x })
                    ok: (a === null) && (b === null) && (c === true) && (d === false) && (e === null) && (g === false)
                    ok.ifTrueIfFalse(() => { 1 }, () => { 0 })
                }
                run(true, false)
                """;
        assertParity(source, 1);
        System.out.println("I095_C_UNSELECTED_NOT_VALIDATED=PASS");
    }

    /** A selected non-Closure callable uses ordinary zero-argument invocation. */
    @Test
    void selectedNonClosureCallableAndSelectorOverrides() throws Exception {
        String source = """
                run: (t, f) => {
                    callable: { call: () => { 8 } }
                    a: t.ifTrue(callable)
                    b: f.ifFalse(callable)
                    c: t.if(callable)
                    custom: {
                        ifTrue: (callback) => { 100 }
                        ifFalse: (callback) => { 200 }
                        if: (callback) => { 300 }
                        else: (callback) => { 400 }
                    }
                    d: custom.ifTrue(() => { 1 })
                    e: custom.ifFalse(() => { 1 })
                    g: custom.if(() => { 1 })
                    h: custom.else(() => { 1 })
                    ok: (c === true).ifTrueIfFalse(() => { 1 }, () => { 0 })
                    a + b + ok + d + e + g + h
                }
                run(true, false)
                """;
        assertParity(source, 8 + 8 + 1 + 1000);

        String callOverride = """
                run: (b) => {
                    cb: () => { 1 }
                    cb.call: () => { 50 }
                    x: b.ifTrue(cb)
                    callable: { call: () => { 1 } }
                    callable.removeSlot("call")
                    callable.call: () => { 7 }
                    y: b.ifTrue(callable)
                    x + y
                }
                run(true)
                """;
        assertParity(callOverride, 57);
        System.out.println("I095_C_CALLABLE_AND_OVERRIDES=PASS");
    }

    /** if/else discard the callback's normal result, whatever its value. */
    @Test
    void ifAndElseDiscardNumberObjectBooleanAndFuture() throws Exception {
        String source = """
                run: (t, f) => {
                    a: t.if(() => { 5 })
                    b: t.if(() => { { marker: 1 } })
                    c: f.else(() => { true })
                    d: f.else(() => { (() => { 41 }).future() })
                    e: true.if(() => { false })
                    g: false.else(() => { (() => { 41 }).future() })
                    future: t.ifTrue(() => { (() => { 41 }).future() })
                    ok: (a === true) && (b === true) && (c === false) && (d === false) && (e === true) && (g === false)
                    ok.ifTrueIfFalse(() => { future.value() + 1 }, () => { 0 })
                }
                run(true, false)
                """;
        assertExecutedOutcome(source, 42);
        System.out.println("I095_C_IF_ELSE_DISCARD=PASS");
    }

    /** Chains are two independent sends; repeated else sends are not merged. */
    @Test
    void chainsAndRepeatedElseAreIndependentSends() throws Exception {
        String source = """
                run: (b) => {
                    trace: 0
                    r: b.if(() => { trace = trace * 10 + 1 }).else(() => { trace = trace * 10 + 2 })
                    s: b.else(() => { trace = trace * 10 + 3 }).else(() => { trace = trace * 10 + 4 })
                    same: (r === b) && (s === b)
                    same.ifTrueIfFalse(() => { trace }, () => { -1 })
                }
                run(true) * 1000 + run(false)
                """;
        assertParity(source, 1 * 1000 + 234);

        String constant = """
                run: () => {
                    trace: 0
                    false.else(() => { trace = trace * 10 + 1 }).else(() => { trace = trace * 10 + 2 })
                    true.if(() => { trace = trace * 10 + 3 }).else(() => { trace = trace * 10 + 4 })
                    trace
                }
                run()
                """;
        assertParity(constant, 123);
        System.out.println("I095_C_CHAINS_UNMERGED=PASS");
    }

    /** Captures by reference, own bindings, shadowing and D179. */
    @Test
    void bindingsCapturesShadowingAndRecreation() throws Exception {
        String source = """
                run: (b) => {
                    outer: 40
                    shadow: 11
                    inside: b.ifFalse(() => {
                        shadow: 1
                        outer = outer + shadow
                        shadow = 2
                        outer + shadow
                    })
                    inside + outer + shadow
                }
                run(false)
                """;
        assertParity(source, 95);

        String recreation = """
                run: (b) => {
                    x: 1
                    a: b.ifTrue(() => {
                        inner: 10
                        x + inner
                    })
                    context.removeSlot("x")
                    context.x: 5
                    c: b.ifTrue(() => {
                        inner: 10
                        x + inner
                    })
                    a * 100 + c
                }
                run(true)
                """;
        assertParity(recreation, 1115);

        String fresh = """
                run: (b) => {
                    total: 0
                    i: 0
                    (() => i < 3).whileTrue(() => {
                        b.if(() => {
                            own: i
                            own = own + 1
                            total = total + own
                        })
                        i = i + 1
                    })
                    total
                }
                run(true)
                """;
        assertParity(fresh, 6);
        System.out.println("I095_C_BINDINGS_PRESERVED=PASS");
    }

    /** Error, non-local return, ensure and suspension cross the control. */
    @Test
    void abruptExitsEnsureAndSuspension() throws Exception {
        String error = """
                cleaned: 0
                run: (b) => {
                    (() => {
                        b.else(() => {
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
                    b.if(() => { ^41 })
                    0
                }
                run(true) + run(false) + 1
                """;
        assertParity(nonLocal, 42);

        String suspension = """
                run: (b) => {
                    pending: (() => { 41 }).future()
                    seen: 0
                    r: b.if(() => {
                        inner: pending.value()
                        seen = inner + 1
                    })
                    (r === b).ifTrueIfFalse(() => { seen }, () => { -1 })
                }
                run(true)
                """;
        assertExecutedOutcome(suspension, 42);

        String task = """
                run: (b) => {
                    task: (() => {
                        pending: (() => { 20 }).future()
                        b.ifFalse(() => {
                            inner: pending.value()
                            inner + 22
                        })
                    }).future()
                    task.value()
                }
                run(false)
                """;
        assertExecutedOutcome(task, 42);
        System.out.println("I095_C_ABRUPT_AND_SUSPENSION=PASS");
    }

    /** An unproven callback keeps the established topology. */
    @Test
    void unprovenCallbackKeepsTheEstablishedTopology() throws Exception {
        String source = """
                run: (b) => {
                    probe()
                    b.else(() => {
                        inner: 1
                        missing + inner
                    })
                }
                missing: 41
                run(false)
                """;
        Execution normal = execute(false, source);
        List<String> names = instructionNames(normal.rootOfFirstProbe());
        // The site is not admitted: no canonical classification, only the ordinary send.
        assertTrue(position(names, "TryCompactCanonicalBooleanOne") < 0, names::toString);
        assertTrue(position(names, "PrepareSend") >= 0, names::toString);
        System.out.println("I095_C_UNPROVEN_FALLBACK=PASS");
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

    /** NORMAL and DEBUG produce the same result for the same program. */
    private static void assertParity(String source, long expected) throws Exception {
        assertInteger(expected, execute(false, source).value());
        assertInteger(expected, execute(true, source).value());
    }

    /**
     * Pending Futures need the C-prime continuation entry, which the direct
     * CallTarget harness above does not provide.
     */
    private static void assertExecutedOutcome(String source, long expected) throws Exception {
        ProtosExecutionOutcome outcome =
                ProtosTestExecutionSupport.execute(
                        "i095-c-execution.protos",
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
                Source.newBuilder(ProtosLanguage.ID, characters, "i095-c.protos").build();
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
        return instructionNames(root.getBytecodeNode());
    }

    private static List<String> instructionNames(BytecodeNode bytecodeNode) {
        return bytecodeNode.getInstructionsAsList().stream()
                .map(Instruction::getName)
                .toList();
    }

    private static int position(List<String> names, String operation) {
        for (int i = 0; i < names.size(); i++) {
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

    private static int rootTags(BytecodeNode bytecodeNode) {
        return countRootTags(bytecodeNode.getTagTree());
    }

    private static int countRootTags(TagTree tree) {
        if (tree == null) {
            return 0;
        }
        int count = tree.hasTag(StandardTags.RootTag.class) ? 1 : 0;
        for (TagTree child : tree.getTreeChildren()) {
            count += countRootTags(child);
        }
        return count;
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
