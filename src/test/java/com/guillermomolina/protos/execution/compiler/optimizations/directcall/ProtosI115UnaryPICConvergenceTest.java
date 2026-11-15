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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.guillermomolina.protos.runtime.ProtosActivation;
import com.guillermomolina.protos.runtime.ProtosClosureValue;
import com.guillermomolina.protos.runtime.ProtosPrelude;
import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.bytecode.Instruction;
import com.oracle.truffle.api.source.Source;
import java.nio.file.Path;
import java.util.List;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Engine;
import org.junit.jupiter.api.Test;

/**
 * I115: the unary Closure-call PIC keyed by Closure identity, its explicit
 * megamorphic transition, the single non-capturing Closure materialization
 * and the experimental local Call1 root. Guest results prove that fresh
 * Closures stay distinct, that saturation keeps the generic D013 behavior
 * and that ReturnHome, projection and non-terminal calls keep their rules.
 * Nothing here measures time or depends on compilation.
 */
final class ProtosI115UnaryPICConvergenceTest {
    private static final Path CORE = Path.of("protos", "lib", "core");
    private static final int PAST_CACHE_LIMITS = 12;

    private static final String FOCAL = """
            run: () => {
                identity: (value) => { value }
                identity(1)
            }
            """;

    @Test
    void freshLocalClosureUsesUnaryCallAndReturnsValue() throws Exception {
        withCore(module -> {
            evaluate(FOCAL, "i115-pic-focal-define.protos", module);
            ProtosBytecodeRootNode root = runRoot(module);
            RootCallTarget target = root.getCallTarget();
            for (int i = 0; i < PAST_CACHE_LIMITS; i++) {
                assertInteger(1, evaluate("run()\n", "i115-pic-focal.protos", module));
            }
            // I115: the shared lowering; the local Closure is a native LOCAL.
            assertSharedUnaryCallRoot(runRoot(module), 1);
            assertTrue(localNames(root).contains("identity"), () -> localNames(root).toString());
            assertSame(root, runRoot(module), "one stable root");
            assertSame(target, runRoot(module).getCallTarget(), "the target is reused");
        });
    }

    @Test
    void freshClosureInstancesRemainDistinct() throws Exception {
        withCore(module -> {
            evaluate("""
                    make: () => { (value) => { value } }
                    callOne: (f, value) => { f(value) }
                    """, "i115-pic-distinct-define.protos", module);
            ProtosClosureValue first = closure("make()\n", module);
            ProtosClosureValue second = closure("make()\n", module);
            ProtosClosureValue third = closure("make()\n", module);
            assertNotSame(first, second);
            assertNotSame(second, third);
            assertNotSame(first, third);
            assertSame(first.definition(), second.definition());
            assertSame(
                    first.preparedExecutionPlanOrNullForRuntime(),
                    second.preparedExecutionPlanOrNullForRuntime());
            assertSame(
                    first.preparedExecutionPlanOrNullForRuntime(),
                    third.preparedExecutionPlanOrNullForRuntime());
            assertSame(
                    Boolean.FALSE,
                    evaluate(
                            "a: make()\nb: make()\na === b\n",
                            "i115-pic-distinct-identity.protos",
                            module));
            for (int i = 0; i < PAST_CACHE_LIMITS; i++) {
                assertInteger(3, evaluate("callOne(make(), 3)\n", "i115-pic-distinct-call.protos", module));
            }
            // Calling them through the PIC neither interns nor reuses them.
            assertNotSame(closure("make()\n", module), closure("make()\n", module));
        });
    }

    /**
     * Fresh Closures of two definitions alternate at one unary call site far
     * past the identity PIC limit. Only results are checked: the definition
     * of the called Closure decides them, never a previously cached object.
     * The internal cache state is verified separately with DSL captures.
     */
    @Test
    void freshFunctionObjectsShareCachedCodeRoot() throws Exception {
        withCore(module -> {
            evaluate("""
                    makeA: () => { (v) => { v } }
                    makeB: () => { (v) => { v + 1 } }
                    callOne: (f, v) => { f(v) }
                    """, "i115-pic-root-define.protos", module);
            ProtosClosureValue a1 = closure("makeA()\n", module);
            ProtosClosureValue a2 = closure("makeA()\n", module);
            ProtosClosureValue b1 = closure("makeB()\n", module);
            ProtosClosureValue b2 = closure("makeB()\n", module);
            assertNotSame(a1, a2);
            assertNotSame(b1, b2);
            assertSame(a1.definition(), a2.definition());
            assertSame(b1.definition(), b2.definition());
            assertNotSame(a1.definition(), b1.definition());
            assertSame(
                    a1.preparedExecutionPlanOrNullForRuntime(),
                    a2.preparedExecutionPlanOrNullForRuntime());
            assertSame(
                    b1.preparedExecutionPlanOrNullForRuntime(),
                    b2.preparedExecutionPlanOrNullForRuntime());

            for (int i = 0; i < 4 * PAST_CACHE_LIMITS; i++) {
                assertInteger(i, evaluate(
                        "callOne(makeA(), " + i + ")\n",
                        "i115-pic-root-a.protos",
                        module));
                assertInteger(i + 1, evaluate(
                        "callOne(makeB(), " + i + ")\n",
                        "i115-pic-root-b.protos",
                        module));
            }
            // After saturation, each definition still decides its result.
            assertInteger(10, evaluate("callOne(makeA(), 10)\n", "i115-pic-root-a-after.protos", module));
            assertInteger(11, evaluate("callOne(makeB(), 10)\n", "i115-pic-root-b-after.protos", module));
        });
    }

    /**
     * A single unary call site sees fresh Closures with distinct lexical
     * captures, but one shared execution target per source definition.
     * Saturating the identity PIC must never retain a previous Closure's
     * captures when the secondary target PIC invokes the next one.
     */
    @Test
    void secondaryTargetPicPreservesCurrentClosureAndDistinctTargets() throws Exception {
        withCore(module -> {
            evaluate("""
                    makeA: (offset) => { (value) => { value + offset } }
                    makeB: (offset) => { (value) => { offset - value } }
                    callOne: (f, value) => { f(value) }
                    """, "i115-pic-target-define.protos", module);

            ProtosClosureValue a1 = closure("makeA(2)\n", module);
            ProtosClosureValue a2 = closure("makeA(9)\n", module);
            ProtosClosureValue b1 = closure("makeB(100)\n", module);
            assertNotSame(a1, a2);
            assertNotSame(a1, b1);
            assertSame(
                    a1.preparedExecutionPlanOrNullForRuntime(),
                    a2.preparedExecutionPlanOrNullForRuntime());
            assertNotSame(
                    a1.preparedExecutionPlanOrNullForRuntime(),
                    b1.preparedExecutionPlanOrNullForRuntime());
            assertSame(
                    a1.preparedExecutionPlanOrNullForRuntime()
                            .bytecodeActivationTargetForComposition(),
                    a2.preparedExecutionPlanOrNullForRuntime()
                            .bytecodeActivationTargetForComposition());

            for (int i = 0; i < 4 * PAST_CACHE_LIMITS; i++) {
                assertInteger(i + 7, evaluate(
                        "callOne(makeA(" + i + "), 7)\n",
                        "i115-pic-target-a.protos", module));
                assertInteger(93 + i, evaluate(
                        "callOne(makeB(" + (100 + i) + "), 7)\n",
                        "i115-pic-target-b.protos", module));
            }
        });
    }

    /*
     * Source Closures take the direct function entry only while ordinary
     * `call` lookup would select the canonical Object.call; a nearer local
     * `call` slot is selected instead (CALLABLES.md, D013). The former
     * I115 divergence is closed by owner decision.
     */
    @Test
    void megamorphicUnaryCallDispatchesSourceFunctions() throws Exception {
        withCore(module -> {
            evaluate("""
                    make: () => { (value) => { value } }
                    callOne: (f, value) => { f(value) }
                    overridden: make()
                    overridden.call: (value) => { 100 + value }
                    """, "i115-pic-mega-define.protos", module);
            assertInteger(5, evaluate("callOne(make(), 5)\n", "i115-pic-mega-before.protos", module));
            // Distinct canonical instances only, until past the PIC limit.
            for (int i = 0; i < PAST_CACHE_LIMITS; i++) {
                assertInteger(i, evaluate("callOne(make(), " + i + ")\n", "i115-pic-mega-saturate.protos", module));
            }
            // D013: the nearer local call slot is selected by ordinary lookup.
            ProtosClosureValue overridden = closure("overridden\n", module);
            assertTrue(overridden.hasLocalCallSlotForRuntime(), "the override is installed");
            assertInteger(105, evaluate("callOne(overridden, 5)\n", "i115-pic-mega-after-override.protos", module));
            assertSame(
                    closure("make()\n", module).preparedExecutionPlanOrNullForRuntime(),
                    overridden.preparedExecutionPlanOrNullForRuntime(),
                    "the override keeps the source function's execution plan");
            assertInteger(5, evaluate("callOne(make(), 5)\n", "i115-pic-mega-after.protos", module));
        });
    }

    /*
     * Source Closures take the direct function entry only while ordinary
     * `call` lookup would select the canonical Object.call; a nearer local
     * `call` slot is selected instead (CALLABLES.md, D013). The former
     * I115 divergence is closed by owner decision.
     *
     * The argument is evaluated before dispatch (arm runs exactly once per
     * call and installs the override), so the installed override is the
     * selected `call` behavior of that very invocation.
     */
    @Test
    void unaryCallEvaluatesArgumentBeforeSourceFunctionDispatch() throws Exception {
        withCore(module -> {
            int[] armed = new int[1];
            module.context().createLocalSlot("armProbe", ProtosClosureValue.nativeClosure(
                    (activation, supplied) -> {
                        armed[0]++;
                        return com.guillermomolina.protos.runtime.ProtosNullValue.INSTANCE;
                    }));
            evaluate("""
                    make: () => { (value) => { value } }
                    late: make()
                    plain: () => { 7 }
                    arm: () => {
                        armProbe()
                        late.call: (value) => { 100 + value }
                        7
                    }
                    callWith: (f, g) => { f(g()) }
                    """, "i115-pic-argument-define.protos", module);
            // Warm the direct entry for the very instance that is later armed.
            for (int i = 0; i < PAST_CACHE_LIMITS; i++) {
                assertInteger(7, evaluate("callWith(late, plain)\n", "i115-pic-argument-warm.protos", module));
            }
            for (int round = 0; round < 2; round++) {
                int before = armed[0];
                assertInteger(107, evaluate("callWith(late, arm)\n", "i115-pic-argument-arm.protos", module));
                assertEquals(before + 1, armed[0], "the argument expression ran exactly once");
                assertTrue(closure("late\n", module).hasLocalCallSlotForRuntime(),
                        "argument evaluation installed the override");
                evaluate("late.removeSlot(\"call\")\n", "i115-pic-argument-remove.protos", module);
                assertFalse(closure("late\n", module).hasLocalCallSlotForRuntime());
                assertInteger(7, evaluate("callWith(late, plain)\n", "i115-pic-argument-plain.protos", module));
            }
        });
    }

    /**
     * Each evaluation calls a fresh {@code f} whose {@code ^} targets the
     * observable ReturnHome captured from its enclosing invocation, so the
     * call sites run uncached first and then past the identity PIC limit.
     * The {@code f} invocation must not capture or complete that home; the
     * enclosing invocation that owns it must capture the return by identity,
     * terminal and non-terminal.
     */
    @Test
    void observableReturnHomeWorksAcrossDirectAndMegamorphicDispatch() throws Exception {
        withCore(module -> {
            evaluate("""
                    earlyTerminal: (v) => {
                        f: (x) => { ^ x + 1 }
                        f(v)
                    }

                    earlyInner: (v) => {
                        f: (x) => { ^ x + 1 }
                        f(v)
                        0
                    }
                    """, "i115-pic-observable-home-define.protos", module);
            for (int i = 0; i < PAST_CACHE_LIMITS; i++) {
                assertInteger(5, evaluate(
                        "earlyTerminal(4)\n", "i115-pic-observable-home-terminal.protos", module));
                assertInteger(5, evaluate(
                        "earlyInner(4)\n", "i115-pic-observable-home-inner.protos", module));
            }
        });
    }

    /*
     * Source Closures take the direct function entry only while ordinary
     * `call` lookup would select the canonical Object.call; a nearer local
     * `call` slot is selected instead (CALLABLES.md, D013). The former
     * I115 divergence is closed by owner decision.
     */
    @Test
    void projectionAndReturnHomeRemainValidWithSourceFunctionDispatch() throws Exception {
        withCore(module -> {
            evaluate("""
                    earlyTerminal: (v) => {
                        f: (x) => { ^ x + 1 }
                        f(v)
                    }
                    earlyInner: (v) => {
                        f: (x) => { ^ x + 1 }
                        f(v)
                        0
                    }
                    nonTerminal: () => {
                        identity: (value) => { value }
                        identity(1) + 1
                    }
                    make: () => { (value) => { value } }
                    callOne: (f, value) => { f(value) }
                    projected: make()
                    overridden: make()
                    overridden.call: (value) => { 100 + value }
                    """, "i115-pic-fallback-define.protos", module);
            for (int i = 0; i < PAST_CACHE_LIMITS; i++) {
                // Observable ReturnHome, terminal and non-terminal.
                assertInteger(5, evaluate("earlyTerminal(4)\n", "i115-pic-home-terminal.protos", module));
                assertInteger(5, evaluate("earlyInner(4)\n", "i115-pic-home-inner.protos", module));
                // Non-terminal unary call.
                assertInteger(2, evaluate("nonTerminal()\n", "i115-pic-non-terminal.protos", module));
            }
            // Warm the unary call site with projected; no override has run there.
            for (int i = 0; i < PAST_CACHE_LIMITS; i++) {
                assertInteger(7, evaluate("callOne(projected, 7)\n", "i115-pic-projected-warm.protos", module));
            }

            // A Closure that starts requiring a Context-local projection
            // invalidates its entry instead of running the old target.
            closure("projected\n", module).requireContextLocalExecutionProjectionForRuntime();
            for (int i = 0; i < PAST_CACHE_LIMITS; i++) {
                assertInteger(7, evaluate("callOne(projected, 7)\n", "i115-pic-projected.protos", module));
            }

            // D013: a local call override is selected by ordinary lookup.
            assertInteger(107, evaluate("callOne(overridden, 7)\n", "i115-pic-generic.protos", module));

            // I115: every root shares the one lowering: one CallUnaryMethod per
            // unary call site and no separate callee-context path.
            assertSharedUnaryCallRoot(rootOf("earlyTerminal\n", module), 1);
            assertSharedUnaryCallRoot(rootOf("earlyInner\n", module), 1);
            // `identity(1) + 1`: one unary call site; the fused NumberAdd
            // dispatches a redefined `+` itself, without a separate guard or send.
            ProtosBytecodeRootNode nonTerminalRoot = rootOf("nonTerminal\n", module);
            assertSharedUnaryCallRoot(nonTerminalRoot, 1);
            List<String> nonTerminalNames = nonTerminalRoot.getBytecodeNode()
                    .getInstructionsAsList().stream().map(Instruction::getName).toList();
            assertTrue(nonTerminalNames.stream()
                            .noneMatch(name -> isInstruction(name, "c.IsStandardNumericSelection")),
                    nonTerminalNames::toString);
            assertTrue(nonTerminalNames.stream()
                            .anyMatch(name -> isInstruction(name, "c.NumberAdd")),
                    nonTerminalNames::toString);
            assertTrue(nonTerminalNames.stream()
                            .anyMatch(name -> isInstruction(name, "c.NumberAdd")),
                    nonTerminalNames::toString);
            assertSharedUnaryCallRoot(rootOf("callOne\n", module), 1);
            assertTrue(localNames(rootOf("earlyTerminal\n", module)).contains("f"));
            assertTrue(localNames(rootOf("nonTerminal\n", module)).contains("identity"));
        });
    }

    /**
     * A Closure created in one Polyglot Context is invoked through the unary
     * call site of another, both with separate Engines and with a shared
     * Engine. The receiving Context runs its own projection: the template's
     * CallTarget of the originating Context is never reused.
     */
    @Test
    void foreignContextClosureIsCalledThroughTheEnteredContextProjection() throws Exception {
        try (Harness origin = new Harness(null);
                Harness receiver = new Harness(null)) {
            assertForeignUnaryCall(origin, receiver);
        }
        try (Engine engine = Engine.create();
                Harness origin = new Harness(engine);
                Harness receiver = new Harness(engine)) {
            assertForeignUnaryCall(origin, receiver);
        }
    }

    @Test
    void unaryCallQuickensByClosureIdentityThenBecomesMegamorphic()
            throws Exception {
        withCore(module -> {
            evaluate(FOCAL, "i115-identity-quickening-define.protos", module);
            ProtosBytecodeRootNode root = runRoot(module);

            int specializedSnapshots = 0;
            int genericSnapshots = 0;

            for (int i = 1; i <= 101; i++) {
                assertInteger(
                        1,
                        evaluate(
                                "run()\n",
                                "i115-identity-quickening-call.protos",
                                module));

                {
                    List<String> unaryNames =
                            root.getBytecodeNode()
                                    .getInstructionsAsList()
                                    .stream()
                                    .map(Instruction::getName)
                                    .filter(name -> isInstruction(
                                            name, "c.CallUnaryMethod"))
                                    .toList();

                    assertEquals(1, unaryNames.size(), unaryNames.toString());

                    String expected = i <= 4
                            ? "c.CallUnaryMethod$CallCachedClosure"
                            : "c.CallUnaryMethod";

                    assertEquals(
                            expected,
                            unaryNames.get(0),
                            "Unexpected unary quickening state after call " + i);

                    if (i <= 4) {
                        specializedSnapshots++;
                    } else {
                        genericSnapshots++;
                    }
                }
            }

            assertEquals(4, specializedSnapshots);
            assertEquals(97, genericSnapshots);
        });
    }

    @Test
    void unaryCallBytecodeShapeRemainsDirect() throws Exception {
        withCore(module -> {
            evaluate(FOCAL, "i115-pic-shape-define.protos", module);
            assertInteger(1, evaluate("run()\n", "i115-pic-shape.protos", module));
            ProtosBytecodeRootNode root = runRoot(module);
            List<String> names =
                    root.getBytecodeNode().getInstructionsAsList().stream()
                            .map(Instruction::getName)
                            .toList();

            // Real instruction names; a quickened form adds a '$' suffix.
            List<String> forbidden = List.of(
                    "c.CreateCurrentFrameLocal",
                    "c.ReadRootFrameLocal",
                    "c.MaterializePrimitiveNumericCarrier",
                    "c.GetMethod",
                    "c.CallBinaryMethod",
                    "c.CallNilaryMethod",
                    "c.CallVarargsMethod");
            for (String name : names) {
                for (String operation : forbidden) {
                    assertFalse(isInstruction(name, operation), names.toString());
                }
            }

            // The focal operations appear in order; structural and
            // instrumentation instructions may lie between them.
            List<String> expected = List.of(
                    "c.MaterializeCurrentClosure",
                    "store.local",
                    "load.local",
                    "c.CallUnaryMethod",
                    "return");
            int next = 0;
            for (String name : names) {
                if (next < expected.size() && isInstruction(name, expected.get(next))) {
                    next++;
                }
            }
            assertEquals(expected.size(), next, names.toString());
            assertEquals(
                    1,
                    names.stream().filter(name -> isInstruction(name, "c.CallUnaryMethod")).count(),
                    names.toString());
        });
    }

    /* Retired experimental callee-context / compact-root operations. */
    private static final List<String> RETIRED = List.of(
            "c.InvokeClosureOneTail",
            "c.InvokeExperimentalClosureOneTail",
            "c.InvokeStaticClosureOneTail",
            "c.ExperimentalLongArgument0",
            "c.CopyArguments",
            "c.CreateCurrentFrameLocal",
            "c.ReadRootFrameLocal",
            "c.InstallFrameLexicalAuthority");

    /*
     * I115 (replaces the retired callee-context classification): a root of
     * the single Bytecode DSL lowering with exactly {@code calls} unary call
     * sites, each the shared CallUnaryMethod, and none of the retired
     * experimental callee-context operations.
     */
    private static void assertSharedUnaryCallRoot(ProtosBytecodeRootNode root, int calls) {
        List<String> names = root.getBytecodeNode().getInstructionsAsList().stream()
                .map(Instruction::getName)
                .toList();
        assertEquals(calls,
                names.stream().filter(name -> isInstruction(name, "c.CallUnaryMethod")).count(),
                names.toString());
        assertTrue(names.stream().anyMatch(name -> isInstruction(name, "c.EnterRoot")),
                names.toString());
        for (String retired : RETIRED) {
            assertTrue(names.stream().noneMatch(name -> isInstruction(name, retired)),
                    () -> retired + ": " + names);
        }
    }

    private static List<Object> localNames(ProtosBytecodeRootNode root) {
        return root.getBytecodeNode().getLocals().stream()
                .map(local -> local.getName())
                .toList();
    }

    private static boolean isInstruction(String name, String operation) {
        return name.equals(operation) || name.startsWith(operation + "$");
    }

    private static ProtosBytecodeRootNode runRoot(ProtosActivation module) {
        return rootOf("run\n", module);
    }

    private static ProtosBytecodeRootNode rootOf(String characters, ProtosActivation module) {
        ProtosBytecodeRootNode root =
                closure(characters, module)
                        .executionPlan()
                        .orElseThrow()
                        .bytecodeActivationRootForTesting();
        root.getRootNodes().ensureComplete();
        return root;
    }

    private static void assertForeignUnaryCall(Harness origin, Harness receiver)
            throws Exception {
        ProtosClosureValue foreign = origin.call(() -> closure(
                "(value) => { value + 1 }\n", origin.module()));
        ProtosClosureExecutionPlan template = foreign.preparedExecutionPlanOrNullForRuntime();
        assertNotNull(template);
        RootCallTarget foreignTarget = template.bytecodeActivationTargetForComposition();

        receiver.run(() -> {
            ProtosActivation module = receiver.module();
            ProtosClosureValue holder = closure(
                    "holder: () => { 0 }\nholder\n", module);
            holder.createLocalSlot("foreign", foreign);
            evaluate(
                    "callOne: (f, value) => { f(value) }\n",
                    "i115-pic-foreign-define.protos",
                    module);
            for (int i = 0; i < PAST_CACHE_LIMITS; i++) {
                assertInteger(8, evaluate(
                        "callOne(holder.foreign, 7)\n",
                        "i115-pic-foreign-call.protos",
                        module));
            }

            ProtosLanguageContext context = ProtosLanguageContext.current();
            ProtosClosureExecutionPlan projected =
                    context.bytecodeExecutionPlanForDefinition(foreign.definition(), template);
            assertNotNull(projected);
            assertNotSame(template, projected);
            assertSame(context.languageForRuntime(), projected.language().orElseThrow());
            assertNotSame(foreignTarget, projected.bytecodeActivationTargetForComposition());
            ProtosBytecodeRootNode callOne = rootOf("callOne\n", module);
            assertSharedUnaryCallRoot(callOne, 1);
            ProtosBytecodeRootNode projectedRoot = projected.bytecodeActivationRootForTesting();
            assertNotSame(foreignTarget.getRootNode(), projectedRoot,
                    "the projection owns its own root in the entered Context");
            assertTrue(projectedRoot.getBytecodeNode().getInstructionsAsList().stream()
                            .map(Instruction::getName)
                            .anyMatch(name -> isInstruction(name, "c.NumberAdd")),
                    "the projected body is the same single lowering");
        });

        // The originating Context still runs its own template.
        assertSame(template, foreign.preparedExecutionPlanOrNullForRuntime());
        assertSame(foreignTarget, template.bytecodeActivationTargetForComposition());
        origin.run(() -> assertInteger(8, evaluate(
                "callOne: (f, value) => { f(value) }\nlocal: (value) => { value + 1 }\ncallOne(local, 7)\n",
                "i115-pic-foreign-origin.protos",
                origin.module())));
    }

    @FunctionalInterface
    private interface Action {
        void run() throws Exception;
    }

    @FunctionalInterface
    private interface Producer<T> {
        T get() throws Exception;
    }

    /** One raw Polyglot Context with a published Core, entered only while an action runs. */
    private static final class Harness implements AutoCloseable {
        private final Context context;
        private ProtosActivation module;

        /** A Context of {@code engine}, or of its own Engine when {@code engine} is null. */
        Harness(Engine engine) {
            Context.Builder builder = Context.newBuilder(ProtosLanguage.ID);
            if (engine != null) {
                builder.engine(engine);
            }
            this.context = builder.build();
            context.initialize(ProtosLanguage.ID);
        }

        void run(Action action) throws Exception {
            call(() -> {
                action.run();
                return null;
            });
        }

        <T> T call(Producer<T> action) throws Exception {
            context.enter();
            try {
                if (module == null) {
                    module = new ProtosCoreBootstrap().bootstrap(CORE).newModuleActivation();
                }
                return action.get();
            } finally {
                context.leave();
            }
        }

        /** Must run entered; one module activation per Context. */
        ProtosActivation module() {
            return module;
        }

        @Override
        public void close() {
            context.close();
        }
    }

    private interface CoreTest {
        void run(ProtosActivation module) throws Exception;
    }

    private static void withCore(CoreTest test) throws Exception {
        try (Context context = Context.newBuilder(ProtosLanguage.ID).build()) {
            context.initialize(ProtosLanguage.ID);
            context.enter();
            try {
                ProtosPrelude prelude = new ProtosCoreBootstrap().bootstrap(CORE);
                test.run(prelude.newModuleActivation());
            } finally {
                context.leave();
            }
        }
    }

    private static Object evaluate(String characters, String name, ProtosActivation activation) {
        Source source =
                Source.newBuilder(ProtosLanguage.ID, characters, name)
                        .mimeType(ProtosLanguage.MIME_TYPE)
                        .build();
        CallTarget target = ProtosLanguageContext.current().parsePublic(source);
        return target.call(activation);
    }

    private static ProtosClosureValue closure(String characters, ProtosActivation activation) {
        return assertInstanceOf(
                ProtosClosureValue.class,
                evaluate(characters, "i115-pic-closure.protos", activation));
    }

    private static void assertInteger(long expected, Object actual) {
        assertEquals(
                Long.toString(expected),
                com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerDecimalText(actual));
    }
}
