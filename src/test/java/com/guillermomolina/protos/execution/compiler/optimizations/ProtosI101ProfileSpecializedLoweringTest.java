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
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.oracle.truffle.api.debug.DebuggerSession;
import com.oracle.truffle.api.debug.Debugger;
import com.guillermomolina.protos.runtime.ProtosActivation;
import com.guillermomolina.protos.runtime.ProtosClosureValue;
import com.guillermomolina.protos.runtime.ProtosNullValue;
import com.guillermomolina.protos.runtime.ProtosNumericValueSupport;
import com.guillermomolina.protos.runtime.ProtosPrelude;
import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.TruffleLanguage.LanguageReference;
import com.oracle.truffle.api.bytecode.BytecodeConfig;
import com.oracle.truffle.api.bytecode.BytecodeLocal;
import com.oracle.truffle.api.bytecode.BytecodeRootNodes;
import com.oracle.truffle.api.bytecode.Instruction;
import com.oracle.truffle.api.bytecode.TagTree;
import com.oracle.truffle.api.bytecode.TagTreeNode;
import com.oracle.truffle.api.instrumentation.StandardTags;
import com.oracle.truffle.api.nodes.RootNode;
import com.oracle.truffle.api.source.Source;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Engine;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * I101-A focal evidence, PLAT067-B: every Context lowers every executable it owns through the one
 * proof-optimized path, which emits the proven minimal representation of the primary constant
 * Boolean-control witness. A Truffle debugger attached before lowering observes exactly the same
 * roots: it never selects another lowering and never fabricates an eliminated callback root.
 *
 * <p>The expected activation root of {@code () => { true.ifTrue() { 1 } }} is a handwritten
 * {@link ProtosBytecodeRootNodeGen.Builder} program authored from the language root
 * contract (a parameterless Closure root with one terminal statement returning the literal's
 * Long carrier; D203/I105 lowers no arity-rejection root). It never consults the parser, the
 * canonicalizer, the lowerer or any lowered root. Real roots always come from the production
 * {@code ParsingRequest} path of an entered Context.
 *
 * <p>Instruction signatures carry every instruction name, every argument kind, every constant
 * with its exact host type (Long is distinct from Integer and Double, Double compares by raw
 * bits), every local and bytecode index, and the tags of every tag node. Nothing is removed before
 * the oracle comparison. Profiles and caches carry no stable value and are compared by kind.
 *
 * <p>No test mutates the frozen global root. A source lowered before standard root publication
 * cannot be produced deterministically in this shared JVM (the first Core bootstrap publishes the
 * root for the whole run), so the publication snapshot is evidenced by reparse stability of
 * roots lowered after publication.
 */
final class ProtosI101ProfileSpecializedLoweringTest {
    private static final Path CORE = Path.of("protos", "lib", "core");

    private static final LanguageReference<ProtosLanguage> LANGUAGE_REF =
            LanguageReference.create(ProtosLanguage.class);

    private static final String DIRECT = "() => { 1 }";
    private static final String UNARY = "(value) => { value }";
    private static final String WITNESS = "() => { true.ifTrue() { 1 } }";

    @TempDir
    Path directory;

    /**
     * DIRECT_LITERAL == HANDWRITTEN_ORACLE for complete activation roots. The Boolean-control
     * witness is no longer a literal-only root: I115-H7 lowers it through the experimental
     * Boolean-control topology, which is verified explicitly rather than equated with the
     * literal oracle.
     */
    @Test
    void foldedWitnessMatchesDirectLiteralAndHandwrittenOracle() throws Exception {
        for (boolean debugger : new boolean[] {false, true}) {
          try (Harness normal = new Harness(debugger, null)) {
            normal.run(() -> {
                ProtosBytecodeRootNode oracle = handwrittenLiteralOneActivationRoot();
                ProtosBytecodeRootNode direct = activationRoot(compile(DIRECT, "direct"));
                ProtosBytecodeRootNode witnessModule = compile(WITNESS, "witness");
                ProtosBytecodeRootNode folded = activationRoot(witnessModule);

                List<String> expected = completeSignature(oracle);
                List<String> directSignature = completeSignature(direct);
                List<String> foldedSignature = completeSignature(folded);
                System.out.println("I101_A_ORACLE_SIGNATURE=" + expected);
                System.out.println("I101_A_DIRECT_SIGNATURE=" + directSignature);
                System.out.println("I101_A_FOLDED_SIGNATURE=" + foldedSignature);

                assertEquals(expected, directSignature, "direct literal root");
                assertTrue(
                        expected.stream().anyMatch(entry -> entry.contains("constant:CONSTANT=Long(1)")),
                        expected::toString);
                assertExperimentalBooleanControlTopology(
                        folded, foldedSignature, "witness " + label(debugger));
                /*
                 * Capabilities are proved over the effective body (the fold's proven result),
                 * not inferred from equal instructions: the witness gets those of the literal.
                 */
                assertTrue(direct.provablyNonSuspendingBody());
                assertTrue(folded.provablyNonSuspendingBody());
                // An unfolded site (its callback body is a send) does not keep the capability.
                ProtosBytecodeRootNode unfolded =
                        activationRoot(compile("() => { true.ifTrue() { 1 + 0 } }", "unfolded"));
                assertTrue(!unfolded.provablyNonSuspendingBody());
                ProtosClosureValue closure = assertInstanceOf(
                        ProtosClosureValue.class,
                        witnessModule.getCallTarget().call(normal.moduleActivation()));
                assertSame(folded, closure.executionPlan().orElseThrow()
                        .bytecodeActivationRootForTesting());
                assertInteger(1, callClosure(normal, closure));
                System.out.println("I101_A_THREE_WAY_SIGNATURE=PASS " + label(debugger));
            });
          }
        }
    }

    /**
     * I104-A: independent Builder oracles for the previous and fused unary
     * entries. Neither oracle invokes the production lowering machinery.
     */
    @Test
    void unaryNormalEntryMatchesIndependentBuilderOracle()
            throws Exception {
        try (Harness normal = new Harness(false, null)) {
            normal.run(() -> {
                ProtosBytecodeRootNode module =
                        compile(UNARY, "i104-unary");
                ProtosBytecodeRootNode production =
                        activationRoot(module);

                List<String> expected =
                        completeSignature(handwrittenUnaryIdentityRoot());
                List<String> actual =
                        completeSignature(production);

                System.out.println("I104_A_INSTRUCTIONS=" + actual);
                System.out.println("I104_A_COUNT=" + actual.size());

                assertEquals(
                        expected,
                        actual,
                        "the production root must equal the independent Builder");
                // I115: the one parameter lowering, not a special unary entry.
                assertSingleParameterLowering(expected, 1, 0);
                assertSingleParameterLowering(actual, 1, 0);
                assertEquals(2, groupRoots(module).size());
                assertSame(production, groupRoots(module).get(1));

                var target = production.getCallTarget();
                var group = production.getRootNodes();
                group.ensureComplete();

                assertSame(production, groupRoots(module).get(1));
                assertSame(target, production.getCallTarget());
            });
        }

        // PLAT067-B: with a debugger attached the same fused entry is emitted.
        try (Harness debug = new Harness(true, null)) {
            debug.run(() -> {
                ProtosBytecodeRootNode production =
                        activationRoot(compile(UNARY, "i104-debug"));
                assertEquals(
                        completeSignature(handwrittenUnaryIdentityRoot()),
                        completeSignature(production),
                        "with a debugger the production root must equal the independent Builder");
            });
        }
    }

    /**
     * Root count/order and CallTargets: {@code () => { 1 }} and
     * {@code () => { true.ifTrue() { 1 } }} lower to the same minimum two-root topology and the
     * same handwritten Builder oracle, without and with a debugger attached before lowering.
     */
    @Test
    void groupTopologyHasNoCallbackRootWithAndWithoutDebugger() throws Exception {
        for (boolean debugger : new boolean[] {false, true}) {
          try (Harness normal = new Harness(debugger, null)) {
            normal.run(() -> {
                List<String> oracle = completeSignature(handwrittenLiteralOneActivationRoot());
                for (String characters : List.of(DIRECT, WITNESS)) {
                    boolean literal = characters.equals(DIRECT);
                    ProtosBytecodeRootNode module =
                            compile(characters, "topology-" + characters.length());
                    List<ProtosBytecodeRootNode> roots = groupRoots(module);
                    /*
                     * Roots are numbered when opened: the module root and the Closure root it
                     * creates. The folded callback is never lowered, so it has no root.
                     */
                    assertEquals(2, roots.size(), characters);
                    assertSame(module, roots.get(0));
                    /*
                     * PLAT067-B: under BytecodeConfig.DEFAULT the passive statement tags
                     * materialize nothing; the physical program equals the oracle's.
                     */
                    List<String> physical = signature(roots.get(1));
                    if (literal) {
                        assertEquals(signature(handwrittenLiteralOneActivationRoot()), physical,
                                label(debugger) + " DEFAULT " + characters);
                    }
                    assertTrue(physical.stream().noneMatch(entry -> entry.startsWith("tag.")),
                            physical::toString);
                    List<String> complete = completeSignature(roots.get(1));
                    if (literal) {
                        assertEquals(oracle, complete, label(debugger) + " " + characters);
                    } else {
                        assertExperimentalBooleanControlTopology(
                                roots.get(1), complete, label(debugger) + " " + characters);
                    }

                    ProtosClosureValue closure = assertInstanceOf(
                            ProtosClosureValue.class,
                            module.getCallTarget().call(normal.moduleActivation()));
                    ProtosClosureExecutionPlan plan = closure.executionPlan().orElseThrow();
                    assertSame(roots.get(1), plan.bytecodeActivationRootForTesting());
                    assertSame(
                            roots.get(1).getCallTarget(),
                            plan.bytecodeActivationTargetForComposition());
                    assertInteger(1, callClosure(normal, closure));
                }
                System.out.println("I101_A_TOPOLOGY=PASS " + label(debugger));
            });
          }
        }
    }

    /**
     * PLAT067-B: with a debugger attached, the folded witness keeps no ordinary send and no
     * callback root; complete instrumentation materializes the activation root's own source and
     * RootTag without fabricating the eliminated callback.
     */
    @Test
    void attachedDebuggerWitnessFabricatesNoCallbackRoot() throws Exception {
        try (Harness debug = new Harness(true, null)) {
            debug.run(() -> {
                ProtosBytecodeRootNode module = compile(WITNESS, "debug-witness");
                List<ProtosBytecodeRootNode> roots = groupRoots(module);
                assertEquals(2, roots.size(), "module and Closure roots only");

                ProtosBytecodeRootNode activationRoot = roots.get(1);
                assertTrue(activationRoot.provablyNonSuspendingBody());
                List<String> activation = completeSignature(activationRoot);
                assertExperimentalBooleanControlTopology(
                        activationRoot, activation, "debugger witness");
                assertEquals(2, groupRoots(module).size(), "instrumentation adds no root");
                assertNotNull(activationRoot.getSourceSection());
                assertTrue(hasRootTag(activationRoot.getBytecodeNode().getTagTree()));

                ProtosClosureValue closure = assertInstanceOf(
                        ProtosClosureValue.class,
                        module.getCallTarget().call(debug.moduleActivation()));
                assertSame(activationRoot,
                        closure.executionPlan().orElseThrow().bytecodeActivationRootForTesting());
                assertInteger(1, callClosure(debug, closure));
                System.out.println("I101_A_DEBUGGER_NO_FABRICATED_CALLBACK=PASS");
            });
        }
    }

    /**
     * Reparse ({@code ensureComplete}) keeps the group, its root and CallTarget identities and
     * every semantic instruction, and only adds tag instrumentation.
     */
    @Test
    void reparseKeepsTopologyIdentitiesAndSemanticInstructions() throws Exception {
        for (boolean debugger : new boolean[] {false, true}) {
            String mode = label(debugger);
            try (Harness harness = new Harness(debugger, null)) {
                harness.run(() -> {
                    ProtosBytecodeRootNode module =
                            compile(WITNESS, "reparse-" + mode);
                    BytecodeRootNodes<?> group = module.getRootNodes();
                    List<ProtosBytecodeRootNode> roots = groupRoots(module);
                    List<RootCallTarget> targets =
                            roots.stream().map(RootNode::getCallTarget).toList();
                    List<List<String>> semantic =
                            roots.stream().map(root -> semanticSignature(root)).toList();

                    group.ensureComplete();
                    List<List<String>> complete =
                            roots.stream().map(root -> signature(root)).toList();
                    for (ProtosBytecodeRootNode root : roots) {
                        assertNotNull(root.getSourceSection(), mode.toString());
                    }
                    group.ensureComplete();

                    assertSame(group, module.getRootNodes());
                    assertEquals(roots, groupRoots(module), mode.toString());
                    for (int index = 0; index < roots.size(); index++) {
                        ProtosBytecodeRootNode root =
                                (ProtosBytecodeRootNode) group.getNode(index);
                        assertSame(roots.get(index), root);
                        assertSame(targets.get(index), root.getCallTarget());
                        assertOnlyInstrumentationAdded(
                                semantic.get(index), semanticSignature(root),
                                mode + " root " + index);
                        assertEquals(complete.get(index), signature(root),
                                mode + " root " + index);
                    }

                    ProtosClosureValue closure = assertInstanceOf(
                            ProtosClosureValue.class,
                            module.getCallTarget().call(harness.moduleActivation()));
                    assertSame(roots.get(1),
                            closure.executionPlan().orElseThrow()
                                    .bytecodeActivationRootForTesting());
                    assertInteger(1, callClosure(harness, closure));
                    assertInteger(1, callClosure(harness, closure));
                });
            }
        }
        System.out.println("I101_A_REPARSE_STABLE=PASS");
    }

    /**
     * Two Contexts of one shared Engine, with a debugger attached to that Engine, each lower the
     * witness to the same oracle: there is no per-Context profile.
     */
    @Test
    void contextsOfOneDebuggedEngineShareTheSingleLowering() throws Exception {
        try (Engine engine = Engine.create();
                DebuggerSession attached = Debugger.find(engine).startSession(event -> {});
                Harness first = new Harness(false, engine);
                Harness second = new Harness(false, engine)) {
            for (int round = 0; round < 2; round++) {
                String name = "mixed-" + round;
                List<String> firstSignature = first.call(() -> {
                    ProtosBytecodeRootNode module = compile(WITNESS, name);
                    List<String> actual = completeSignature(activationRoot(module));
                    assertExperimentalBooleanControlTopology(
                            activationRoot(module), actual, "first " + name);
                    return actual;
                });
                List<String> secondSignature = second.call(() -> {
                    ProtosBytecodeRootNode module = compile(WITNESS, name);
                    List<String> actual = completeSignature(activationRoot(module));
                    assertExperimentalBooleanControlTopology(
                            activationRoot(module), actual, "second " + name);
                    return actual;
                });
                assertEquals(firstSignature, secondSignature);
            }
        }
        System.out.println("I101_A_SHARED_ENGINE_CONTEXTS=PASS");
    }

    /**
     * A Closure projected into another Context is lowered for the destination Context, in both
     * directions, through the same single lowering; the source template keeps its own executable.
     */
    @Test
    void closureProjectionUsesTheDestinationContext() throws Exception {
        try (Harness normal = new Harness(false, null);
                Harness debug = new Harness(true, null)) {
            ProtosClosureValue normalClosure = normal.call(() -> assertInstanceOf(
                    ProtosClosureValue.class,
                    compile(WITNESS, "projection-normal").getCallTarget()
                            .call(normal.moduleActivation())));
            ProtosClosureValue debugClosure = debug.call(() -> assertInstanceOf(
                    ProtosClosureValue.class,
                    compile(WITNESS, "projection-debug").getCallTarget()
                            .call(debug.moduleActivation())));
            ProtosClosureExecutionPlan normalTemplate = normalClosure.executionPlan().orElseThrow();
            ProtosClosureExecutionPlan debugTemplate = debugClosure.executionPlan().orElseThrow();

            debug.run(() -> {
                ProtosLanguageContext destination = ProtosLanguageContext.current();
                ProtosClosureExecutionPlan projected =
                        destination.bytecodeExecutionPlanForDefinition(
                                normalClosure.definition(), normalTemplate);
                assertSame(destination.languageForRuntime(), projected.language().orElseThrow());
                assertSame(projected, destination.bytecodeExecutionPlanForDefinition(
                        normalClosure.definition(), normalTemplate));
                ProtosBytecodeRootNode projectedRoot = projected.bytecodeActivationRootForTesting();
                assertExperimentalBooleanControlTopology(
                        projectedRoot, completeSignature(projectedRoot), "projected into debug",
                        PROJECTED_GROUP_ROOTS);
            });
            normal.run(() -> {
                ProtosLanguageContext destination = ProtosLanguageContext.current();
                ProtosClosureExecutionPlan projected =
                        destination.bytecodeExecutionPlanForDefinition(
                                debugClosure.definition(), debugTemplate);
                assertSame(destination.languageForRuntime(), projected.language().orElseThrow());
                ProtosBytecodeRootNode projectedRoot = projected.bytecodeActivationRootForTesting();
                assertExperimentalBooleanControlTopology(
                        projectedRoot, completeSignature(projectedRoot), "projected into normal",
                        PROJECTED_GROUP_ROOTS);
            });
            assertNotSame(normalTemplate.language().orElseThrow(),
                    debugTemplate.language().orElseThrow());
        }
        System.out.println("I101_A_PROJECTION_DESTINATION_CONTEXT=PASS");
    }

    /** An imported module is compiled by the one lowering, with and without a debugger. */
    @Test
    void importedModuleUsesTheSingleLowering() throws Exception {
        List<String> previous = null;
        for (boolean debugger : new boolean[] {false, true}) {
            String mode = label(debugger);
            Path root = Files.createDirectories(directory.resolve(mode.toString()));
            Files.writeString(
                    root.resolve("helper.protos"),
                    "run: " + WITNESS + "\n",
                    StandardCharsets.UTF_8);
            Path main = root.resolve("main.protos");
            Files.writeString(
                    main,
                    """
                    Helper: import("./helper.protos")
                    run: Helper.run
                    0
                    """,
                    StandardCharsets.UTF_8);
            try (ProtosStandaloneHostedSession session =
                    ProtosStandaloneHostedSession.open(CORE, main);
                    DebuggerSession attached = attach(session, debugger)) {
                assertEquals(ProtosExecutionOutcome.State.COMPLETED,
                        session.initialOutcome().state(), mode.toString());
                ProtosStandaloneHostedSession.PreparedTopLevel run =
                        session.prepareTopLevel("run");
                ProtosClosureExecutionPlan plan =
                        run.closureForTesting().executionPlan().orElseThrow();
                ProtosBytecodeRootNode imported = plan.bytecodeActivationRootForTesting();
                RootCallTarget importedTarget = imported.getCallTarget();
                List<String> signature = completeSignature(imported);
                assertExperimentalBooleanControlTopology(imported, signature, mode.toString());
                // Debugger attachment selects no different lowering.
                if (previous != null) {
                    assertEquals(previous, signature, mode);
                }
                previous = signature;
                for (int iteration = 0; iteration < 3; iteration++) {
                    assertEquals(1, run.executable().execute().asInt(), mode.toString());
                }
                assertSame(importedTarget, imported.getCallTarget(), mode.toString());
            }
        }
        System.out.println("I101_A_IMPORTED_MODULE_SINGLE_LOWERING=PASS");
    }

    /**
     * A root omits the roots of the Boolean callbacks it inlines or never selects, so an
     * I096 inline copy of that root lowers them at its own site, where the callee's root is
     * already closed. Their captures of the callee's bindings must take the runtime-authority
     * path; both I096 sites keep their inline region and every capture keeps its by-reference
     * semantics.
     */
    @Test
    void inlineCopyLowersOmittedCapturingCallbacksAtItsSite() throws Exception {
        String program = """
                run: () => {
                    f: () => {
                        count: 0
                        value: true.ifTrueIfFalse(() => {
                            count = count + 1
                            count
                        }, () => {
                            count = 100
                            0
                        })
                        (value * 10) + count
                    }
                    f() * 1000 + (() => {
                        count: 0
                        value: false.ifTrueIfFalse(() => {
                            count = 100
                            0
                        }, () => {
                            count = count + 2
                            count
                        })
                        (value * 10) + count
                    })()
                }
                run()
                """;
        for (boolean debugger : new boolean[] {false, true}) {
            String mode = label(debugger);
            try (Harness harness = new Harness(debugger, null)) {
                harness.run(() -> {
                    ProtosBytecodeRootNode module =
                            compile(program, "omitted-callbacks-" + mode);
                    List<String> run = completeSignature(groupRoots(module).get(1));
                    for (String operation : List.of(
                            "c.CallNilaryMethod",
                            "c.MaterializeCurrentClosure")) {
                        assertEquals(
                                2L,
                                run.stream()
                                        .filter(entry -> entry.startsWith(operation))
                                        .count(),
                                () -> mode + " two " + operation + ": " + run);
                    }
                    assertEquals(
                            0L,
                            run.stream()
                                    .filter(entry -> entry.startsWith(
                                            "c.PrepareInlineClosureLiteralCall"))
                                    .count(),
                            () -> mode + " retired inline preparation: " + run);
                    for (int iteration = 0; iteration < 3; iteration++) {
                        assertInteger(11022,
                                module.getCallTarget().call(harness.moduleActivation()));
                    }
                });
            }
        }
        System.out.println("I101_A_INLINE_COPY_OMITTED_CALLBACKS=PASS");
    }

    /**
     * Fold boundary: every case must produce its observable result, with or without a debugger.
     * Separately, the physical outcome of each site is identified. The previous eager Boolean
     * fold (a literal-only root equal to the folded source's root) and the I115-H7 Boolean
     * control path (a physical condition and conditional branch with inline callback bodies)
     * are distinct optimization contracts: a branching root is never declared equivalent to a
     * literal-only root. A case expected to fold must emit one of these two physical outcomes;
     * a case not expected to fold must not emit a literal-only root equal to its folded form.
     */
    @Test
    void foldBoundaryKeepsOrdinarySemantics() throws Exception {
        record Case(String closure, String folded, Object expected) {}
        List<Case> cases = List.of(
                // Proven folds of the same proof family.
                new Case("() => { false.ifFalse() { 1 } }", "() => { 1 }", 1L),
                new Case("() => { true.ifTrueIfFalse(() => { 1 }, () => { 2 }) }",
                        "() => { 1 }", 1L),
                new Case("() => { false.ifTrueIfFalse(() => { 1 }, () => { 2 }) }",
                        "() => { 2 }", 2L),
                new Case("() => { true.ifTrue() { null } }", "() => { null }",
                        ProtosNullValue.INSTANCE),
                // Not folded: an unselected callback, a send, a non-local return, a large
                // Integer, the canonical-Boolean result policy (and/or) and a wrong arity.
                new Case("() => { false.ifTrue() { 1 } }", null, ProtosNullValue.INSTANCE),
                new Case("() => { true.ifTrue() { 1 + 2 } }", "() => { 3 }", 3L),
                new Case("() => {\n    true.ifTrue() { ^1 }\n    2\n}", "() => { 1 }", 1L),
                new Case("() => { true.ifTrue() { 99999999999999999999 } }",
                        "() => { 99999999999999999999 }",
                        new BigInteger("99999999999999999999")),
                new Case("() => { false.or() { true } }", "() => { true }",
                        Boolean.TRUE),
                new Case("() => { true.and() { true } }", "() => { true }",
                        Boolean.TRUE),
                new Case("() => { Error.handle(() => { true.ifTrue() }, (caught) => { 7 }) }",
                        null, 7L));
        List<Integer> foldedCases = List.of(0, 1, 2, 3);
        for (boolean debugger : new boolean[] {false, true}) {
            String mode = label(debugger);
            try (Harness harness = new Harness(debugger, null)) {
                harness.run(() -> {
                    for (int index = 0; index < cases.size(); index++) {
                        Case test = cases.get(index);
                        String name = "boundary-" + index;
                        ProtosBytecodeRootNode module = compile(test.closure(), name);
                        List<String> actual = completeSignature(activationRoot(module));
                        if (test.folded() != null) {
                            List<String> folded = completeSignature(activationRoot(
                                    compile(test.folded(), name + "-folded")));
                            boolean expectFold = foldedCases.contains(index);
                            boolean literalFold = folded.equals(actual);
                            if (literalFold) {
                                // Strict literal-oracle equality: a literal-only root.
                                assertTrue(expectFold,
                                        mode + " unexpected fold " + test.closure() + "\n" + actual);
                                assertNoForbiddenNormalOperation(actual);
                            } else if (expectFold) {
                                // I115-H7: only the unguarded literal Boolean-control
                                // form may replace the literal-only oracle, and it
                                // must carry the selected literal value in its branch.
                                boolean guarded = assertExperimentalBooleanControlTopology(
                                        activationRoot(module), actual,
                                        mode + " " + test.closure());
                                assertTrue(!guarded,
                                        mode + " guarded control is not a fold "
                                                + test.closure() + "\n" + actual);
                                List<String> literals = test.expected() instanceof Long value
                                        ? List.of(
                                                "load.constant constant:CONSTANT=Integer(" + value + ")",
                                                "load.constant constant:CONSTANT=Long(" + value + ")")
                                        : List.of("load.constant constant:CONSTANT=Protos(null)");
                                assertTrue(actual.stream().anyMatch(literals::contains),
                                        mode + " " + literals + " missing in " + actual);
                            }
                        }
                        ProtosClosureValue closure = assertInstanceOf(
                                ProtosClosureValue.class,
                                module.getCallTarget().call(harness.moduleActivation()));
                        assertGuestValue(test.expected(), callClosure(harness, closure),
                                mode + " " + test.closure());
                    }
                });
            }
        }
        System.out.println("I101_A_FOLD_BOUNDARY=PASS");
    }

    /**
     * I101-C/D203 acceptance with and without a debugger. This is a semantic and root-topology
     * integration test, not a claim of a new physically minimal encoding.
     */
    @Test
    void i101CD203BindingsRetainRootTopologyWithAndWithoutDebugger() throws Exception {
        record Case(String definition, String arguments, Object expected) {}

        List<Case> cases = List.of(
                new Case(
                        "(first, second: first, third) => { second }",
                        "9", 9L),
                new Case(
                        "(first, second: 7, third) => { second }",
                        "9, null", ProtosNullValue.INSTANCE),
                new Case(
                        "(first: 7, second, third: second) => { second }",
                        "", ProtosNullValue.INSTANCE),
                new Case(
                        "(first, second: 7, third) => { third }",
                        "9, 8, 42, 100", 42L),
                new Case(
                        "(first: 7, ...tail) => { tail.size() }",
                        "9, 8, 42", 2L));

        for (boolean debugger : new boolean[] {false, true}) {
            String mode = label(debugger);
            try (Harness harness = new Harness(debugger, null)) {
                harness.run(() -> {
                    for (int index = 0; index < cases.size(); index++) {
                        Case example = cases.get(index);
                        String label = "d203-" + mode + "-" + index;

                        ProtosBytecodeRootNode module =
                                compile(example.definition(), label);

                        BytecodeRootNodes<?> group = module.getRootNodes();
                        List<ProtosBytecodeRootNode> roots =
                                groupRoots(module);

                        assertEquals(2, roots.size(),
                                "D203 must not create an arity-rejection root");

                        ProtosBytecodeRootNode body = roots.get(1);
                        RootCallTarget target = body.getCallTarget();

                        group.ensureComplete();
                        List<String> complete = signature(body);
                        group.ensureComplete();

                        assertSame(group, module.getRootNodes());
                        assertEquals(roots, groupRoots(module));
                        assertSame(body, group.getNode(1));
                        assertSame(target, body.getCallTarget());
                        assertEquals(complete, signature(body));

                        assertNotNull(body.getSourceSection());
                        assertTrue(hasRootTag(
                                body.getBytecodeNode().getTagTree()));

                        ProtosClosureValue closure = assertInstanceOf(
                                ProtosClosureValue.class,
                                module.getCallTarget().call(
                                        harness.moduleActivation()));

                        ProtosClosureExecutionPlan plan =
                                closure.executionPlan().orElseThrow();

                        assertSame(target,
                                plan.bytecodeActivationTargetForComposition());

                        ProtosActivation caller = harness.moduleActivation();
                        caller.context().createLocalSlot("candidate", closure);

                        ProtosBytecodeRootNode invocation =
                                compile(
                                        "candidate(" + example.arguments() + ")",
                                        label + "-call");

                        for (int repetition = 0; repetition < 2; repetition++) {
                            assertGuestValue(
                                    example.expected(),
                                    invocation.getCallTarget().call(caller),
                                    mode + " " + example.definition());
                        }
                    }
                });
            }
        }
    }

    /**
     * I101-C integration of lexical authority and structured control with
     * root identity, reparse and real hosted execution, with and without a debugger.
     */
    @Test
    void i101CCrossFamilyControlAndCaptureAcceptance() throws Exception {
        record Case(String name, String program, int expected) {}

        List<Case> cases = List.of(
                new Case("late-capture", """
                        run: () => {
                            outer: () => {
                                value: 40
                                middle: () => {
                                    reader: () => value
                                    first: reader()
                                    value: 2
                                    first + reader()
                                }
                                middle()
                            }
                            outer()
                        }
                        0
                        """, 42),
                new Case("nonlocal-ensure", """
                        cleaned: 0
                        run: () => {
                            (() => {
                                ^42
                            }).ensure(() => {
                                cleaned = 7
                            })
                            0
                        }
                        verifyCleanup: () => { cleaned }
                        0
                        """, 42),
                new Case("error-identity", """
                        run: () => {
                            original: Error()
                            cleaned: false
                            result: original.handle(() => {
                                (() => original.signal()).ensure(() => {
                                    cleaned = true
                                })
                            }, (caught) => {
                                (caught === original) && cleaned
                            })
                            result.ifTrueIfFalse(
                                () => { 1 },
                                () => { 0 })
                        }
                        0
                        """, 1),
                new Case("suspendible-ensure", """
                        run: () => {
                            work: () => {
                                count: 0
                                dependency: (() => 7).future()
                                (() => {
                                    count = count + 1
                                    dependency.value()
                                    count = count + 10
                                }).ensure(() => {
                                    count = count + 100
                                })
                                count
                            }
                            work.future().value()
                        }
                        0
                        """, 111));

        for (boolean debugger : new boolean[] {false, true}) {
            String mode = label(debugger);
            for (Case example : cases) {
                Path file = directory.resolve(
                        "i101-c-" + mode + "-" + example.name() + ".protos");

                Files.writeString(
                        file, example.program(), StandardCharsets.UTF_8);

                try (ProtosStandaloneHostedSession session =
                    ProtosStandaloneHostedSession.open(CORE, file);
                    DebuggerSession attached = attach(session, debugger)) {

                    assertEquals(
                            ProtosExecutionOutcome.State.COMPLETED,
                            session.initialOutcome().state());

                    ProtosStandaloneHostedSession.PreparedTopLevel run =
                            session.prepareTopLevel("run");

                    ProtosClosureExecutionPlan plan =
                            run.closureForTesting()
                                    .executionPlan().orElseThrow();


                    ProtosBytecodeRootNode root =
                            plan.bytecodeActivationRootForTesting();

                    BytecodeRootNodes<?> group = root.getRootNodes();
                    List<ProtosBytecodeRootNode> roots =
                            groupRoots(root);
                    RootCallTarget target = root.getCallTarget();

                    group.ensureComplete();

                    assertSame(group, root.getRootNodes());
                    assertEquals(roots, groupRoots(root));
                    assertSame(target, root.getCallTarget());
                    assertNotNull(root.getSourceSection());
                    assertTrue(hasRootTag(
                            root.getBytecodeNode().getTagTree()));


                    for (int repetition = 0; repetition < 2; repetition++) {
                        if ("suspendible-ensure".equals(example.name())) {
                            ProtosExecutionOutcome outcome = run.invoke();
                            assertEquals(
                                    ProtosExecutionOutcome.State.COMPLETED,
                                    outcome.state(),
                                    mode + " " + example.name());
                            assertEquals(
                                    String.valueOf(example.expected()),
                                    ProtosNumericValueSupport.integerDecimalText(
                                            outcome.value()),
                                    mode + " " + example.name());
                        } else {
                            assertEquals(
                                    example.expected(),
                                    run.executable().execute().asInt(),
                                    mode + " " + example.name());
                        }
                    }

                    if (example.name().equals("nonlocal-ensure")) {
                        assertEquals(
                                7,
                                session.prepareTopLevel("verifyCleanup")
                                        .executable().execute().asInt(),
                                mode + " NLR cleanup");
                    }
                }
            }
        }
    }


    /**
     * I101-C: guarded specializations must retain their actual MISS path.
     * I095 has a specialized physical Boolean-control selection with a
     * fallback and I096 an inline Closure-call guard; PLAT067-B: both are
     * emitted identically with a debugger attached. Reparse may materialize
     * instrumentation but must not change the semantic instruction stream,
     * root identities, CallTargets or the selected guest behavior.
     *
     * This test checks the complete typed instruction signatures for reparse
     * stability. It does not claim a minimum-Builder equality for a guarded
     * root: the MISS path is semantically necessary.
     */
    @Test
    void i101CGuardedFallbackPreservesPhysicalPathsAndReparse()
            throws Exception {
        record Family(
                String name,
                String source,
                String guard,
                String fallback,
                long expected) {}

        List<Family> families = List.of(
                new Family(
                        "boolean",
                        """
                        select: (receiver) => {
                            receiver.ifTrue(() => { 1 })
                        }
                        run: () => {
                            custom: {
                                ifTrue: (callback) => { 7 }
                            }
                            select(true) * 10 + select(custom)
                        }
                        0
                        """,
                        "IsCanonicalBooleanOperand",
                        "GetMethod",
                        17),
                new Family(
                        "closure-call",
                        """
                        run: () => {
                            f: (value) => { value }
                            first: f(2)
                            f.call: (value) => { value + 10 }
                            second: f(3)
                            first * 10 + second
                        }
                        0
                        """,
                        "CallUnaryMethod",
                        "CallUnaryMethod",
                        33));

        for (boolean debugger : new boolean[] {false, true}) {
            String mode = label(debugger);
            for (Family family : families) {
                Path folder = Files.createDirectories(
                        directory.resolve(
                                "i101-guard-" + mode + "-" + family.name()));
                Path file = folder.resolve("fixture.protos");
                Files.writeString(
                        file, family.source(), StandardCharsets.UTF_8);

                try (ProtosStandaloneHostedSession session =
                    ProtosStandaloneHostedSession.open(CORE, file);
                    DebuggerSession attached = attach(session, debugger)) {
                    assertEquals(
                            ProtosExecutionOutcome.State.COMPLETED,
                            session.initialOutcome().state(),
                            mode + " " + family.name());

                    ProtosStandaloneHostedSession.PreparedTopLevel run =
                            session.prepareTopLevel("run");

                    ProtosClosureExecutionPlan plan =
                            run.closureForTesting()
                                    .executionPlan().orElseThrow();

                    ProtosBytecodeRootNode activation =
                            plan.bytecodeActivationRootForTesting();
                    BytecodeRootNodes<?> group = activation.getRootNodes();
                    List<ProtosBytecodeRootNode> roots =
                            groupRoots(activation);

                    List<RootCallTarget> targets = roots.stream()
                            .map(RootNode::getCallTarget)
                            .toList();

                    List<List<String>> before = roots.stream()
                            .map(ProtosI101ProfileSpecializedLoweringTest
                                    ::stableReparseSemanticSignature)
                            .toList();

                    group.ensureComplete();

                    List<List<String>> complete = roots.stream()
                            .map(ProtosI101ProfileSpecializedLoweringTest
                                    ::signature)
                            .toList();

                    group.ensureComplete();

                    assertSame(group, activation.getRootNodes());
                    assertEquals(roots, groupRoots(activation));

                    for (int index = 0; index < roots.size(); index++) {
                        ProtosBytecodeRootNode root =
                                roots.get(index);

                        assertSame(
                                root, group.getNode(index),
                                mode + " " + family.name());
                        assertSame(
                                targets.get(index), root.getCallTarget(),
                                mode + " " + family.name());

                        assertReparsePrefixAndCleanup(
                                before.get(index),
                                stableReparseSemanticSignature(root),
                                mode + " " + family.name()
                                        + " root " + index);

                        assertEquals(
                                complete.get(index),
                                signature(root),
                                mode + " " + family.name()
                                        + " root " + index);

                        assertNotNull(
                                root.getSourceSection(),
                                mode + " " + family.name());
                    }

                    List<ProtosBytecodeRootNode> guarded =
                            family.name().equals("closure-call")
                                    ? List.of(activation)
                                    : roots.stream()
                                            .filter(root -> signature(root).stream()
                                                    .anyMatch(instruction ->
                                                            instruction.contains(
                                                                    family.guard())))
                                            .toList();

                    {
                        assertEquals(
                                1, guarded.size(),
                                "one guarded site: " + mode + " "
                                        + family.name());

                        List<String> instructions =
                                signature(guarded.get(0));

                        int guardIndex = -1;
                        int fallbackIndex = -1;

                        for (int index = 0;
                                index < instructions.size();
                                index++) {
                            String instruction =
                                    instructions.get(index);

                            if (instruction.contains(family.guard())
                                    && guardIndex < 0) {
                                guardIndex = index;
                            }

                            if (instruction.contains(family.fallback())
                                    && fallbackIndex < 0
                                    && (!family.name().equals("closure-call")
                                            || index > guardIndex)) {
                                fallbackIndex = index;
                            }
                        }

                        assertTrue(
                                instructions.stream().noneMatch(
                                        instruction -> instruction.contains(
                                                "IsNormalExecutionMode")),
                                "no runtime mode branch exists");
                        assertTrue(
                                guardIndex >= 0,
                                "missing guard: " + instructions);
                        assertTrue(
                                fallbackIndex > guardIndex,
                                "missing or misplaced fallback/second call: "
                                        + instructions);
                        if (family.name().equals("closure-call")) {
                            for (String retired : List.of(
                                    "TryDirectCall",
                                    "PrepareClosureCall",
                                    "EnterStraightLineSourceCall",
                                    "BindClosureFrameParameter")) {
                                assertTrue(
                                        instructions.stream().noneMatch(
                                                entry -> entry.contains(retired)),
                                        () -> "retired Closure dispatch "
                                                + retired + ": " + instructions);
                            }
                            long unaryOperations = instructions.stream()
                                    .filter(entry -> entry.startsWith(
                                            "c.CallUnaryMethod"))
                                    .count();
                            assertTrue(
                                    unaryOperations >= 2L,
                                    "two source calls must remain executable: "
                                            + instructions);
                            assertSame(
                                    activation,
                                    guarded.get(0),
                                    "both source calls must belong to run");
                        }
                    }

                    for (int repetition = 0; repetition < 2;
                            repetition++) {
                        assertEquals(
                                family.expected(),
                                run.executable().execute().asLong(),
                                mode + " " + family.name());
                    }
                }
            }
        }
    }

    /**
     * I101-C: a cancellation request reaching a real suspension must unwind
     * through ensure. This test explicitly checks that the pending
     * cancellation is observed at the cooperative {@code dependency.value()}
     * boundary, that code after {@code dependency.value()} never runs
     * ({@code afterAwait} stays 0), that ensure runs exactly once
     * ({@code cleaned} is 1), that the handler receives the identical
     * cleanup Error ({@code caught === cleanupError}), that the cleanup
     * Error takes precedence over the original cancellation transfer, and
     * that the result is the same with and without a debugger.
     *
     * The operation is entered through a cooperative RootTask only because
     * it contains a real suspension; ordinary synchronous calls elsewhere
     * remain task-free.
     */
    @Test
    void i101CCancellationEnsureAndTaskRetainDebuggerParity()
            throws Exception {
        String source = """
                run: () => {
                    cleaned: 0
                    afterAwait: 0
                    cleanupError: Error()

                    producer: (() => {
                        dependency: Future.all().then(
                            (values) => { 7 })

                        Error.handle(
                            () => {
                                (() => {
                                    producer.cancel()
                                    dependency.value()
                                    afterAwait = 1
                                    41
                                }).ensure(() => {
                                    cleaned = cleaned + true.ifTrue(() => { 1 })
                                    cleanupError.signal()
                                })
                            },
                            (caught) => {
                                (caught === cleanupError).ifTrueIfFalse(
                                    () => { 42 },
                                    () => { 90 }
                                )
                            }
                        )
                    }).future()

                    value: producer.value()
                    cleaned * 100 + value + afterAwait * 1000
                }
                0
                """;

        for (boolean debugger : new boolean[] {false, true}) {
            String mode = label(debugger);
            Path folder = Files.createDirectories(
                    directory.resolve("i101-cancellation-" + mode));
            Path file = folder.resolve("fixture.protos");
            Files.writeString(file, source, StandardCharsets.UTF_8);

            try (ProtosStandaloneHostedSession session =
                    ProtosStandaloneHostedSession.open(CORE, file);
                    DebuggerSession attached = attach(session, debugger)) {
                assertEquals(
                        ProtosExecutionOutcome.State.COMPLETED,
                        session.initialOutcome().state(),
                        mode.toString());

                ProtosStandaloneHostedSession.PreparedTopLevel run =
                        session.prepareTopLevel("run");

                ProtosClosureExecutionPlan plan =
                        run.closureForTesting()
                                .executionPlan().orElseThrow();


                for (int repetition = 0; repetition < 2; repetition++) {
                    ProtosExecutionOutcome result = run.invoke();

                    assertEquals(
                            ProtosExecutionOutcome.State.COMPLETED,
                            result.state(),
                            mode + " cancellation / ensure");
                    assertInteger(142, result.value());
                }
            }
        }
    }


    /**
     * Reparse may append the root's unreachable local-cleanup epilogue.
     * The epilogue may be empty, contain only {@code return}, or be the
     * complete cleanup epilogue in this exact order: zero or more
     * {@code clear.local} of the I111-A explicit RootTag Block's own locals
     * (its unreachable exit), {@code load.null}, zero or more
     * {@code clear.local} of the root-scoped locals, at most one I112
     * {@code c.EpilogForReturn}, {@code return}. Each local offset is cleared
     * at most once. The pre-existing semantic instruction prefix must remain
     * identical. Bytecode indices are normalized by semanticSignature.
     *
     * <p>I115: a root ending with an explicit terminal Return has no
     * fallthrough path; its only appended epilogue is exactly one
     * {@code c.EpilogForReturn} followed by {@code return}.
     */
    /**
     * I115: the one observed Bytecode DSL reparse shape that is not an append. Before reparse
     * the root ends {@code load.local, c.EpilogForReturn, return, return}: the reachable return
     * and its epilogue, then the unreachable fallthrough return. Instrumentation inserts exactly
     * one {@code c.EpilogForReturn} before that unreachable return. Every other instruction,
     * including the reachable return and its epilogue, must be identical; nothing else may be
     * inserted or appended.
     */
    private static boolean isUnreachableReturnEpilogueInsertion(
            List<String> before, List<String> after) {
        int size = before.size();
        if (size < 4 || after.size() != size + 1) {
            return false;
        }
        String epilogue = before.get(size - 3);
        // The returned value is a local, or a terminal numeric operation
        // that leaves its result directly on the stack.
        String returned = before.get(size - 4);
        boolean shape = (returned.startsWith("load.local ")
                        || returned.startsWith("c.Number"))
                && epilogue.startsWith("c.EpilogForReturn")
                && before.get(size - 2).equals("return")
                && before.get(size - 1).equals("return");
        return shape
                && after.subList(0, size - 1).equals(before.subList(0, size - 1))
                && after.get(size - 1).equals(epilogue)
                && after.get(size).equals("return");
    }

    private static void assertReparsePrefixAndCleanup(
            List<String> before, List<String> after, String message) {
        assertTrue(
                after.size() >= before.size(),
                () -> message + ": instructions disappeared");

        if (isUnreachableReturnEpilogueInsertion(before, after)) {
            return;
        }
        assertEquals(
                before,
                after.subList(0, before.size()),
                message + ": semantic prefix changed");

        List<String> epilogue = after.subList(before.size(), after.size());
        if (epilogue.isEmpty() || epilogue.equals(List.of("return"))) {
            return;
        }
        if (epilogue.size() == 2
                && epilogue.get(0).startsWith("c.EpilogForReturn")
                && epilogue.get(1).equals("return")) {
            return;
        }
        java.util.Set<String> cleared = new java.util.HashSet<>();
        int position = 0;
        // I111-A: unreachable exit cleanup of the explicit RootTag Block.
        while (position < epilogue.size()
                && epilogue.get(position).startsWith("clear.local ")) {
            String instruction = epilogue.get(position++);
            assertTrue(
                    cleared.add(instruction),
                    () -> message + ": local cleared twice in " + epilogue);
        }
        assertTrue(
                position < epilogue.size(),
                () -> message + ": incomplete reparse epilogue " + epilogue);
        assertEquals(
                "load.null", epilogue.get(position++),
                message + ": reparse epilogue must reach load.null " + epilogue);
        // The implicit root return clears the root-scoped locals.
        while (position < epilogue.size()
                && epilogue.get(position).startsWith("clear.local ")) {
            String instruction = epilogue.get(position++);
            assertTrue(
                    cleared.add(instruction),
                    () -> message + ": local cleared twice in " + epilogue);
        }
        // I112: every root return runs the EpilogForReturn epilogue.
        if (position < epilogue.size()
                && epilogue.get(position).startsWith("c.EpilogForReturn")) {
            position++;
        }
        assertEquals(
                List.of("return"),
                epilogue.subList(position, epilogue.size()),
                message + ": reparse epilogue must end with exactly one return " + epilogue);
    }


    /**
     * I101-C/D203: defaults, rest parameters and spread arguments are
     * intentionally outside the admitted I096 direct-binding inline shapes.
     * They must keep ordinary argument binding and invocation semantics,
     * with and without a debugger.
     */
    @Test
    void i101CD203ExcludedCallsKeepOrdinaryTopology()
            throws Exception {
        String program = """
                run: () => {
                    withDefault: (a: 2) => { a + 3 }
                    withRest: (...items) => { items.size() }
                    withSpread: (a) => { a }
                    values: [9]

                    first: withDefault()
                    second: withRest(1, 2)
                    third: withSpread(...values)

                    first * 100 + second * 10 + third
                }
                0
                """;

        for (boolean debugger : new boolean[] {false, true}) {
            String mode = label(debugger);
            Path folder = Files.createDirectories(
                    directory.resolve("i101-d203-negative-" + mode));
            Path file = folder.resolve("fixture.protos");
            Files.writeString(file, program, StandardCharsets.UTF_8);

            try (ProtosStandaloneHostedSession session =
                    ProtosStandaloneHostedSession.open(CORE, file);
                    DebuggerSession attached = attach(session, debugger)) {
                assertEquals(
                        ProtosExecutionOutcome.State.COMPLETED,
                        session.initialOutcome().state(),
                        mode.toString());

                ProtosStandaloneHostedSession.PreparedTopLevel run =
                        session.prepareTopLevel("run");

                ProtosClosureExecutionPlan plan =
                        run.closureForTesting()
                                .executionPlan().orElseThrow();


                ProtosBytecodeRootNode root =
                        plan.bytecodeActivationRootForTesting();

                List<String> instructions = completeSignature(root);

                assertTrue(
                        instructions.stream().noneMatch(
                                instruction -> instruction.contains(
                                        "AdmitsInlineClosureLiteralCall")),
                        mode + " must not inline D203-excluded calls");

                // I108 preserves ordinary physical invocation through
                // semantic operations. D203 exclusions must not become
                // inline-literal calls.
                List<String> operationNames = instructions.stream()
                        .map(instruction -> instruction.split(" ", 2)[0])
                        .toList();

                long callZero = operationNames.stream()
                        .filter(name -> name.startsWith("c.CallNilaryMethod"))
                        .count();
                long callTwo = operationNames.stream()
                        .filter(name -> name.startsWith("c.CallBinaryMethod"))
                        .count();
                long callSpread = operationNames.stream()
                        .filter(name -> name.startsWith("c.CallVarargsMethod"))
                        .count();

                assertEquals(1L, callZero,
                        mode + ": expected one ordinary Call0");
                assertEquals(1L, callTwo,
                        mode + ": rest invocation must use physical Call2");
                assertEquals(1L, callSpread,
                        mode + ": spread invocation must retain physical varargs");

                assertTrue(
                        instructions.stream().noneMatch(
                                instruction -> instruction.contains(
                                        "IsNormalExecutionMode")),
                        mode + " must not contain runtime profile dispatch");

                for (int repetition = 0; repetition < 2; repetition++) {
                    assertEquals(
                            529L,
                            run.executable().execute().asLong(),
                            mode + " D203 ordinary calls");
                }
            }
        }
    }

    /**
     * I104-A standalone physical oracle for {@code (value) => { value }},
     * rewritten for the single I115 lowering: the parameter is bound by
     * {@code LoadSuppliedArgument(0)} stored into its own native local, and
     * the terminal statement returns it with {@code LoadLocal} inside its
     * tagged Block. D203/I105: no excess-argument check follows the binding.
     */
    private static ProtosBytecodeRootNode handwrittenUnaryIdentityRoot() {
        return handwrittenParameterReadRoot(
                UNARY, "i104-unary-oracle.protos", new String[] {"value"}, 0,
                UNARY.lastIndexOf("value"), "value".length());
    }

    /**
     * The one parameter contract of every required positional parameter:
     * {@code LoadSuppliedArgument(index)} then {@code StoreLocal} into that
     * parameter's own local, in declaration order; the terminal read of
     * parameter {@code read} is a {@code LoadLocal} returned inside the
     * statement's tagged Block. Independent of the production lowerer.
     */
    private static ProtosBytecodeRootNode handwrittenParameterReadRoot(
            String characters, String sourceName, String[] names, int read,
            int readStart, int readLength) {
        Source source = Source.newBuilder(ProtosLanguage.ID, characters, sourceName).build();
        int bodyStart = characters.indexOf('{');
        int bodyLength = characters.lastIndexOf('}') + 1 - bodyStart;

        BytecodeRootNodes<ProtosBytecodeRootNode> nodes =
                ProtosBytecodeRootNodeGen.create(
                        LANGUAGE_REF.get(null),
                        BytecodeConfig.DEFAULT,
                        builder -> {
                            builder.beginSource(source);
                            builder.beginSourceSection(bodyStart, bodyLength);
                            builder.beginRoot();

                            BytecodeLocal[] locals = new BytecodeLocal[names.length];
                            for (int index = 0; index < names.length; index++) {
                                locals[index] = builder.createLocal(names[index], null);
                            }
                            builder.beginBlock();

                            builder.emitCopySuppliedArguments(locals);

                            builder.beginReturn();
                            builder.beginSourceSection(readStart, readLength);
                            builder.beginTag(
                                    StandardTags.StatementTag.class,
                                    StandardTags.ExpressionTag.class);
                            builder.beginBlock();
                            builder.emitLoadLocal(locals[read]);
                            builder.endBlock();
                            builder.endTag(
                                    StandardTags.StatementTag.class,
                                    StandardTags.ExpressionTag.class);
                            builder.endSourceSection();
                            builder.endReturn();

                            builder.endBlock();
                            builder.endRoot();
                            builder.endSourceSection();
                            builder.endSource();
                        });
        return nodes.getNode(0);
    }

    /* Retired parameter and frame-authority operations; none may reappear. */
    private static final List<String> RETIRED_PARAMETER_OPERATIONS =
            List.of(
                    "CopyArguments",
                    "BindClosureFrameParameter",
                    "BindRequiredFrameParameter",
                    "BindUnaryFrameParameter",
                    "LoadFrameClosureArgument",
                    "ReadRootFrameLocal",
                    "InstallFrameLexicalAuthority");

    /**
     * The positive structure of the one parameter lowering: exactly one
     * {@code LoadSuppliedArgument} per parameter, at positions 0..n-1 in
     * order, each immediately stored into a native local, followed by a
     * native read; and no retired parameter operation.
     */
    private static void assertSingleParameterLowering(
            List<String> signature, int parameters, int readOffset) {
        long groupedCopies = signature.stream()
                .filter(entry -> entry.startsWith("c.CopySuppliedArguments"))
                .count();
        assertEquals(1L, groupedCopies,
                "exactly one grouped parameter copy: " + signature);
        assertTrue(signature.stream()
                        .noneMatch(entry -> entry.startsWith("c.LoadSuppliedArgument")),
                "individual parameter loads must not replace the grouped copy: " + signature);
        assertTrue(parameters > 0 && readOffset >= 0 && readOffset < parameters,
                "invalid independent parameter witness");
        assertEquals(
                1L,
                signature.stream()
                        .filter(entry -> entry.startsWith("load.local "))
                        .count(),
                "identity/read witness must load exactly one local: " + signature);
        assertTrue(
                signature.stream().anyMatch(entry ->
                        entry.startsWith(
                                "load.local local_offset:LOCAL_OFFSET=" + readOffset)),
                () -> "the returned parameter must be read from its native local: "
                        + signature);
        for (String retired : RETIRED_PARAMETER_OPERATIONS) {
            assertTrue(signature.stream().noneMatch(entry -> entry.contains(retired)),
                    () -> retired + " in " + signature);
        }
    }


    /** I104-B: production lowering against an independently written Builder. */
    @Test
    void i104RequiredPrefixMatchesIndependentBuilderOracle() throws Exception {
        final String source = "(a, b, c) => { b }";

        try (Harness normal = new Harness(false, null)) {
            normal.run(() -> {
                ProtosBytecodeRootNode module =
                        compile(source, "i104-b-normal");
                ProtosBytecodeRootNode actual =
                        activationRoot(module);

                List<String> beforeReparse = semanticSignature(actual);
                var group = module.getRootNodes();
                var roots = groupRoots(module);
                var target = actual.getCallTarget();

                List<String> handwritten =
                        completeSignature(handwrittenI104BRoot());
                List<String> compiled = completeSignature(actual);

                System.out.println(
                        "I104_B_INSTRUCTION_COUNT=" + compiled.size());
                System.out.println(
                        "I104_B_SIGNATURE=" + compiled);

                assertEquals(
                        handwritten, compiled,
                        "the required-prefix root must match independent Builder");
                // I115: three required parameters, one contract each.
                assertSingleParameterLowering(handwritten, 3, 1);
                assertSingleParameterLowering(compiled, 3, 1);
                assertEquals(2, roots.size());
                assertSame(module, roots.get(0));
                assertSame(actual, roots.get(1));
                assertSame(target, actual.getCallTarget());

                group.ensureComplete();
                assertSame(actual, group.getNode(1));
                assertSame(target, actual.getCallTarget());
                assertEquals(compiled, completeSignature(actual));
                List<String> afterReparse = semanticSignature(actual);
                assertTrue(
                        afterReparse.size() >= beforeReparse.size(),
                        "I104-B reparse lost original instructions");
                assertEquals(
                        beforeReparse,
                        afterReparse.subList(0, beforeReparse.size()),
                        "I104-B reparse changed the original instruction prefix");
                // I115: the root ends with an explicit terminal Return, so
                // there is no fallthrough path and no unreachable load.null or
                // local cleanup. Reparse appends only the root return
                // epilogue: EpilogForReturn, then return.
                assertEquals(
                        List.of(
                                "c.EpilogForReturn INTEGER=0 BYTECODE_INDEX=bci INTEGER=1 NODE_PROFILE=profile",
                                "return"),
                        afterReparse.subList(
                                beforeReparse.size(), afterReparse.size()),
                        "I104-B reparse must append only the explicit-return epilogue");

                System.out.println("I104_B_BUILDER_ORACLE=PASS");
                System.out.println("I104_B_REPARSE=PASS");
            });
        }

        // PLAT067-B: with a debugger attached the same required-prefix entry is emitted.
        try (Harness debug = new Harness(true, null)) {
            debug.run(() -> {
                var root = activationRoot(
                        compile(source, "i104-b-debug"));
                List<String> signature = completeSignature(root);
                assertEquals(
                        completeSignature(handwrittenI104BRoot()),
                        signature,
                        "with a debugger the production root must equal the independent Builder");
                assertSingleParameterLowering(signature, 3, 1);
                System.out.println("I104_B_DEBUGGER_SAME_ENTRY=PASS");
            });
        }
    }

    /**
     * I104-B oracle for {@code (a, b, c) => { b }}: the same one parameter
     * contract as every other required positional parameter.
     */
    private static ProtosBytecodeRootNode handwrittenI104BRoot() {
        String characters = "(a, b, c) => { b }";
        return handwrittenParameterReadRoot(
                characters, "i104-b-builder.protos", new String[] {"a", "b", "c"}, 1,
                characters.lastIndexOf('b'), 1);
    }

    /**
     * Handwritten expectation of the activation root: a parameterless Closure root with a
     * direct return of the Long carrier of {@code 1}. Ordinary guest-value materialization is
     * handled by the call boundary; D203/I105 nests no arity-rejection root.
     */
    private static ProtosBytecodeRootNode handwrittenLiteralOneActivationRoot() {
        String characters = DIRECT;
        Source source = Source.newBuilder(ProtosLanguage.ID, characters, "i101-a-oracle.protos")
                .build();
        int bodyStart = characters.indexOf('{');
        int bodyLength = characters.lastIndexOf('}') + 1 - bodyStart;
        String statement = "1";
        int statementStart = characters.indexOf(statement);
        BytecodeRootNodes<ProtosBytecodeRootNode> nodes =
                ProtosBytecodeRootNodeGen.create(
                        LANGUAGE_REF.get(null),
                        BytecodeConfig.DEFAULT,
                        builder -> {
                            builder.beginSource(source);
                            builder.beginSourceSection(bodyStart, bodyLength);
                            builder.beginRoot();
                            builder.beginBlock();
                            builder.beginSourceSection(statementStart, statement.length());
                            builder.beginTag(StandardTags.StatementTag.class, StandardTags.ExpressionTag.class);
                            builder.beginBlock();
                            builder.beginReturn();
                            builder.emitLoadConstant(ProtosNumericValueSupport.integer(1L));
                            builder.endReturn();
                            builder.endBlock();
                            builder.endTag(StandardTags.StatementTag.class, StandardTags.ExpressionTag.class);
                            builder.endSourceSection();

                            builder.endBlock();
                            builder.endRoot();
                            builder.endSourceSection();
                            builder.endSource();
                        });
        return nodes.getNode(0);
    }

    /** The production ParsingRequest path of the entered Context. */
    private static ProtosBytecodeRootNode compile(String characters, String name) {
        Source source = Source.newBuilder(ProtosLanguage.ID, characters, "i101-a-" + name + ".protos")
                .build();
        RootCallTarget target = assertInstanceOf(
                RootCallTarget.class, ProtosLanguageContext.current().parsePublic(source));
        return assertInstanceOf(ProtosBytecodeRootNode.class, target.getRootNode());
    }

    /** The Closure root created by a module whose only statement is a Closure literal. */
    private static ProtosBytecodeRootNode activationRoot(
            ProtosBytecodeRootNode module) {
        return groupRoots(module).get(1);
    }

    /** The roots of {@code root}'s group in group order. */
    private static List<ProtosBytecodeRootNode> groupRoots(
            ProtosBytecodeRootNode root) {
        return root.getRootNodes().getNodes().stream()
                .map(ProtosBytecodeRootNode.class::cast)
                .toList();
    }

    /**
     * I115-H7: the experimental Boolean-control topology of a {@code literal.ifTrue() {...}}
     * activation root. This is a distinct optimization contract from the previous eager
     * Boolean fold: the root keeps a physical condition and conditional branch whose selected
     * callback body is lowered inline, so it is verified on its own terms and never equated
     * with the literal-only oracle. {@code signature} is the root's complete signature.
     *
     * <p>A proven literal control may be entirely folded into one result constant
     * without a condition or branch. Otherwise, an unguarded condition has no MISS
     * path, while a guarded receiver must preserve ordinary selection on its MISS
     * path. In every form, no separate callback root is permitted.
     *
     * @return whether the condition is the guarded form
     */
    private static boolean assertExperimentalBooleanControlTopology(
            ProtosBytecodeRootNode activation, List<String> signature, String message) {
        return assertExperimentalBooleanControlTopology(
                activation, signature, message, MODULE_AND_CLOSURE_GROUP_ROOTS);
    }

    /** A module-compiled witness: the module root, then the Closure root. */
    private static final int MODULE_AND_CLOSURE_GROUP_ROOTS = 2;

    /**
     * A Closure projected into another Context is lowered from its definition alone: its
     * group holds exactly its own root.
     */
    private static final int PROJECTED_GROUP_ROOTS = 1;

    private static boolean assertExperimentalBooleanControlTopology(
            ProtosBytecodeRootNode activation,
            List<String> signature,
            String message,
            int expectedGroupRoots) {
        int condition = -1;
        int branch = -1;
        boolean guarded = false;
        for (int index = 0; index < signature.size(); index++) {
            String entry = signature.get(index);
            String name = entry.split(" ", 2)[0];
            if (condition < 0
                    && ((name.equals("load.constant") && entry.contains("=Boolean("))
                            || name.equals("c.ExperimentalCanonicalBooleanCondition"))) {
                condition = index;
                guarded = !name.equals("load.constant");
            }
            if (condition >= 0 && branch < 0 && name.startsWith("branch.false")) {
                branch = index;
            }
        }
        if (condition < 0) {
            long selectedLiterals = signature.stream()
                    .filter(entry -> entry.startsWith("load.constant ")
                            && (entry.contains("=Long(")
                                    || entry.contains("=Protos(null)")))
                    .count();
            assertEquals(1L, selectedLiterals,
                    message + ": folded Boolean control must retain one selected literal: "
                            + signature);
            assertTrue(activation.provablyNonSuspendingBody(),
                    message + ": a folded literal body must not suspend");
            for (String entry : signature) {
                String name = entry.split(" ", 2)[0];
                assertTrue(
                        !name.startsWith("branch")
                                && !name.equals("yield")
                                && (!name.startsWith("c.")
                                        || name.equals("c.EnterRoot")
                                        || name.equals("c.EpilogForReturn")),
                        message + ": folded Boolean control retains execution machinery: "
                                + signature);
            }
        } else {
            assertTrue(branch > condition,
                    () -> message + ": no conditional branch after the condition in "
                            + signature);
        }
        // The selected callback body runs inline: no callback Closure and no callback call.
        for (String forbidden : List.of(
                "Capture",
                "PrepareClosureCall",
                "TryDirectCall",
                "PrepareInlineClosureLiteralCall",
                "CallNilaryMethod")) {
            assertTrue(
                    signature.stream().noneMatch(
                            entry -> entry.split(" ", 2)[0].contains(forbidden)),
                    () -> message + ": " + forbidden + " in " + signature);
        }
        List<String> sends = List.of(
                "PrepareSend", "GetMethod", "CallUnaryMethod", "CallBinaryMethod",
                "CallVarargsMethod");
        java.util.function.Predicate<String> isSend = entry -> {
            String name = entry.split(" ", 2)[0];
            return sends.stream().anyMatch(name::contains);
        };
        if (guarded) {
            // The guarded MISS path keeps the ordinary send after the guard.
            int guard = condition;
            assertTrue(
                    signature.subList(guard + 1, signature.size()).stream().anyMatch(isSend),
                    () -> message + ": guarded control without its MISS send " + signature);
        } else {
            // An unguarded literal condition has no MISS path.
            assertTrue(
                    signature.stream().noneMatch(isSend),
                    () -> message + ": send in an unguarded control " + signature);
        }
        // Exactly the expected roots, with this activation root last; no callback root.
        List<ProtosBytecodeRootNode> roots = groupRoots(activation);
        assertEquals(expectedGroupRoots, roots.size(),
                () -> message + ": root topology " + roots);
        assertSame(activation, roots.get(expectedGroupRoots - 1), message);
        assertNotNull(activation.getSourceSection(), message);
        TagTree tree = activation.getBytecodeNode().getTagTree();
        assertTrue(hasTag(tree, StandardTags.RootTag.class), message + ": RootTag");
        assertTrue(hasTag(tree, StandardTags.RootBodyTag.class), message + ": RootBodyTag");
        assertTrue(hasTag(tree, StandardTags.StatementTag.class), message + ": StatementTag");

        // Reparse keeps the root, its CallTarget and the complete program.
        RootCallTarget target = activation.getCallTarget();
        activation.getRootNodes().ensureComplete();
        assertEquals(roots, groupRoots(activation), message);
        assertSame(target, activation.getCallTarget(), message);
        assertEquals(signature, signature(activation), message + ": reparse stability");
        return guarded;
    }

    private static boolean hasTag(TagTree tree, Class<? extends com.oracle.truffle.api.instrumentation.Tag> tag) {
        if (tree == null) {
            return false;
        }
        if (tree.hasTag(tag)) {
            return true;
        }
        for (TagTree child : tree.getTreeChildren()) {
            if (hasTag(child, tag)) {
                return true;
            }
        }
        return false;
    }

    private static void assertNoForbiddenNormalOperation(List<String> signature) {
        List<String> forbidden = List.of(
                "IsNormalExecutionMode",
                "CanonicalBoolean",
                "IsCanonical",
                "PrepareSend",
                "Capture",
                "PrepareClosureCall",
                "TryDirectCall",
                "PrepareInlineClosureLiteralCall",
                "AdmitsInline",
                "store.local",
                "load.local",
                "branch");
        for (String entry : signature) {
            String name = entry.split(" ", 2)[0];
            for (String operation : forbidden) {
                assertTrue(!name.contains(operation), () -> operation + " in " + signature);
            }
        }
    }

    private static List<String> completeSignature(ProtosBytecodeRootNode root) {
        root.getRootNodes().ensureComplete();
        return signature(root);
    }

    /** Every instruction with every argument; nothing is filtered. */
    private static List<String> signature(ProtosBytecodeRootNode root) {
        List<String> entries = new ArrayList<>();
        for (Instruction instruction : root.getBytecodeNode().getInstructionsAsList()) {
            StringBuilder entry = new StringBuilder(ProtosInstructionNames.of(instruction));
            for (Instruction.Argument argument : instruction.getArguments()) {
                entry.append(' ')
                        .append(argument.getName())
                        .append(':')
                        .append(argument.getKind())
                        .append('=')
                        .append(operand(argument));
            }
            entries.add(entry.toString());
        }
        return entries;
    }


    /**
     * Exact semantic reparse signature independent of Truffle quickening.
     * All physical operations and semantic operands remain represented.
     * Only specialization state and quickening suffixes are omitted.
     */
    private static List<String> stableReparseSemanticSignature(
            ProtosBytecodeRootNode root) {
        List<String> entries = new ArrayList<>();
        for (Instruction instruction : root.getBytecodeNode().getInstructionsAsList()) {
            String name = ProtosInstructionNames.of(instruction);
            if (name.startsWith("tag.") || name.startsWith("trace.")) {
                continue;
            }
            int quickening = name.indexOf('$');
            StringBuilder entry = new StringBuilder(
                    quickening >= 0 ? name.substring(0, quickening) : name);
            for (Instruction.Argument argument : instruction.getArguments()) {
                if (argument.getName().startsWith("state_")) {
                    continue;
                }
                entry.append(' ')
                        .append(argument.getName())
                        .append(':')
                        .append(argument.getKind())
                        .append('=')
                        .append(argument.getKind()
                                == Instruction.Argument.Kind.BYTECODE_INDEX
                                        ? "bci" : operand(argument));
            }
            entries.add(entry.toString());
        }
        return entries;
    }

    /**
     * Reparse comparison only: the instructions other than tag and trace instrumentation, whose
     * insertion is the expected metadata expansion and shifts bytecode indices.
     */
    private static List<String> semanticSignature(ProtosBytecodeRootNode root) {
        List<String> entries = new ArrayList<>();
        for (Instruction instruction : root.getBytecodeNode().getInstructionsAsList()) {
            if (instruction.getName().startsWith("tag.")
                    || instruction.getName().startsWith("trace.")) {
                continue;
            }
            StringBuilder entry = new StringBuilder(ProtosInstructionNames.of(instruction));
            for (Instruction.Argument argument : instruction.getArguments()) {
                entry.append(' ').append(argument.getKind()).append('=').append(
                        argument.getKind() == Instruction.Argument.Kind.BYTECODE_INDEX
                                ? "bci"
                                : operand(argument));
            }
            entries.add(entry.toString());
        }
        return entries;
    }

    /**
     * Every semantic instruction before reparse is still present, in order and with the same
     * operands; the only additions are the instrumented root epilogue that tag materialization
     * appends after the final return.
     */
    private static void assertOnlyInstrumentationAdded(
            List<String> before, List<String> after, String message) {
        int matched = 0;
        for (String entry : after) {
            if (matched < before.size() && entry.equals(before.get(matched))) {
                matched++;
            } else {
                // I112: every root return runs the EpilogForReturn epilogue.
                assertTrue(entry.equals("load.null")
                                || entry.equals("return")
                                || entry.startsWith("c.EpilogForReturn"),
                        () -> message + ": unexpected " + entry + " in " + after);
            }
        }
        assertEquals(before.size(), matched, () -> message + ": " + before + " vs " + after);
    }

    private static String operand(Instruction.Argument argument) {
        return switch (argument.getKind()) {
            case CONSTANT -> constant(argument.asConstant());
            case BYTECODE_INDEX -> Integer.toString(argument.asBytecodeIndex());
            case INTEGER -> Integer.toString(argument.asInteger());
            case LOCAL_OFFSET -> Integer.toString(argument.asLocalOffset());
            case LOCAL_INDEX -> Integer.toString(argument.asLocalIndex());
            case TAG_NODE -> tags(argument.asTagNode());
            case NODE_PROFILE, BRANCH_PROFILE -> "profile";
        };
    }

    private static String constant(Object value) {
        if (value == null) {
            return "host-null";
        }
        if (value instanceof Double number) {
            return "Double(bits=" + Long.toHexString(Double.doubleToRawLongBits(number)) + ")";
        }
        if (value instanceof Float number) {
            return "Float(bits=" + Integer.toHexString(Float.floatToRawIntBits(number)) + ")";
        }
        if (value instanceof Long
                || value instanceof Integer
                || value instanceof Short
                || value instanceof Byte
                || value instanceof Boolean
                || value instanceof Character
                || value instanceof String
                || value instanceof BigInteger) {
            return value.getClass().getSimpleName() + "(" + value + ")";
        }
        if (value == ProtosNullValue.INSTANCE) {
            return "Protos(null)";
        }
        if (value == Boolean.TRUE || value == Boolean.FALSE) {
            return "Protos(" + (value == Boolean.TRUE) + ")";
        }
        if (value instanceof Class<?> type) {
            return "Class(" + type.getName() + ")";
        }
        // Internal metadata (plans, layouts, definitions) has no stable value across roots.
        return "Metadata(" + value.getClass().getName() + ")";
    }

    private static String tags(TagTreeNode node) {
        if (node == null) {
            return "no-tag-node";
        }
        return node.getTags().stream().map(Class::getSimpleName).sorted().toList().toString();
    }

    private static boolean hasRootTag(TagTree tree) {
        if (tree == null) {
            return false;
        }
        if (tree.hasTag(StandardTags.RootTag.class)) {
            return true;
        }
        for (TagTree child : tree.getTreeChildren()) {
            if (hasRootTag(child)) {
                return true;
            }
        }
        return false;
    }

    /** Calls a Closure value through an ordinary guest call in a fresh module activation. */
    private static Object callClosure(Harness harness, ProtosClosureValue closure) throws Exception {
        ProtosActivation module = harness.moduleActivation();
        module.context().createLocalSlot("candidate", closure);
        return compile("candidate()", "call-" + System.identityHashCode(closure))
                .getCallTarget()
                .call(module);
    }

    private static void assertInteger(long expected, Object value) {
        assertEquals(
                Long.toString(expected), ProtosNumericValueSupport.integerDecimalText(value));
    }

    private static void assertGuestValue(Object expected, Object actual, String message) {
        if (expected instanceof Long || expected instanceof BigInteger) {
            assertEquals(
                    expected.toString(), ProtosNumericValueSupport.integerDecimalText(actual),
                    message);
        } else {
            assertSame(expected, actual, message);
        }
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
        private final DebuggerSession debugger;
        private ProtosPrelude prelude;

        /**
         * A Context of {@code engine} (or of its own Engine when null). With {@code debugger}, a
         * Truffle debugger session is attached to the Engine before any lowering (PLAT067-B: it
         * observes, it never selects another lowering).
         */
        Harness(boolean debugger, Engine engine) {
            Context.Builder builder = Context.newBuilder(ProtosLanguage.ID);
            if (engine != null) {
                builder.engine(engine);
            }
            this.context = builder.build();
            this.debugger =
                    debugger
                            ? Debugger.find(context.getEngine()).startSession(event -> {})
                            : null;
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
                if (prelude == null) {
                    // Publishes the standard root before any witness is lowered.
                    prelude = new ProtosCoreBootstrap().bootstrap(CORE);
                }
                return action.get();
            } finally {
                context.leave();
            }
        }

        ProtosActivation moduleActivation() {
            return prelude.newModuleActivation();
        }

        @Override
        public void close() {
            if (debugger != null) {
                debugger.close();
            }
            context.close();
        }
    }

    private static String label(boolean debugger) {
        return debugger ? "debugger" : "plain";
    }

    /** PLAT067-B: a Truffle debugger session on {@code session}'s Engine, or none. */
    private static DebuggerSession attach(ProtosStandaloneHostedSession session, boolean debugger) {
        return debugger
                ? Debugger.find(session.runtimeHostForTesting().engineForTesting())
                        .startSession(event -> {})
                : null;
    }
}
