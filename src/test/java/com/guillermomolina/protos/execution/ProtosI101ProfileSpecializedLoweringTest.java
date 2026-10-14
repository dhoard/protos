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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.guillermomolina.protos.runtime.ProtosActivation;
import com.guillermomolina.protos.runtime.ProtosBooleanValue;
import com.guillermomolina.protos.runtime.ProtosClosureValue;
import com.guillermomolina.protos.runtime.ProtosNullValue;
import com.guillermomolina.protos.runtime.ProtosNumericValueSupport;
import com.guillermomolina.protos.runtime.ProtosPrelude;
import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.TruffleLanguage.LanguageReference;
import com.oracle.truffle.api.bytecode.BytecodeConfig;
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
 * I101-A (PLAT058-D) focal evidence: the immutable NORMAL/DEBUG profile of the owning Context
 * selects the physical lowering of every executable it owns, and NORMAL emits the proven minimal
 * representation of the primary constant Boolean-control witness.
 *
 * <p>The expected NORMAL activation root of {@code () => { true.ifTrue() { 1 } }} is a handwritten
 * {@link ProtosSemanticBytecodeRootNodeGen.Builder} program authored from the language root
 * contract (a parameterless Closure root with its nested PERF032-G6 arity-rejection root and one
 * terminal statement returning the literal's Long carrier). It never consults the parser, the
 * canonicalizer, the lowerer or any lowered root. Real roots always come from the production
 * {@code ParsingRequest} path of an entered Context, so the profile is the Context's own.
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
    private static final String WITNESS = "() => { true.ifTrue() { 1 } }";

    @TempDir
    Path directory;

    /** DIRECT_LITERAL == FOLDED_BOOLEAN == HANDWRITTEN_ORACLE for complete activation roots. */
    @Test
    void foldedWitnessMatchesDirectLiteralAndHandwrittenOracle() throws Exception {
        try (Harness normal = new Harness(ProtosExecutionMode.NORMAL, null)) {
            normal.run(() -> {
                ProtosSemanticBytecodeRootNode oracle = handwrittenLiteralOneActivationRoot();
                ProtosSemanticBytecodeRootNode direct = activationRoot(compile(DIRECT, "direct"));
                ProtosSemanticBytecodeRootNode folded =
                        activationRoot(compile(WITNESS, "witness"));

                List<String> expected = completeSignature(oracle);
                List<String> directSignature = completeSignature(direct);
                List<String> foldedSignature = completeSignature(folded);
                System.out.println("I101_A_ORACLE_SIGNATURE=" + expected);
                System.out.println("I101_A_DIRECT_SIGNATURE=" + directSignature);
                System.out.println("I101_A_FOLDED_SIGNATURE=" + foldedSignature);

                assertEquals(expected, directSignature, "direct literal root");
                assertEquals(expected, foldedSignature, "folded Boolean-control root");
                assertTrue(
                        expected.stream().anyMatch(entry -> entry.contains("=Long(1)")),
                        expected::toString);
                assertNoForbiddenNormalOperation(foldedSignature);
                /*
                 * Capabilities are proved over the effective body (the fold's proven result),
                 * not inferred from equal instructions: the witness gets those of the literal.
                 */
                assertTrue(direct.provablyNonSuspendingBody());
                assertTrue(direct.headerlessEntryBody());
                assertTrue(folded.provablyNonSuspendingBody());
                assertTrue(folded.headerlessEntryBody());
                // An unfolded site (its callback body is a send) keeps neither capability.
                ProtosSemanticBytecodeRootNode unfolded =
                        activationRoot(compile("() => { true.ifTrue() { 1 + 0 } }", "unfolded"));
                assertTrue(!unfolded.provablyNonSuspendingBody());
                assertTrue(!unfolded.headerlessEntryBody());
                System.out.println("I101_A_THREE_WAY_SIGNATURE=PASS");
            });
        }
    }

    /** Root count/order, arity rejection and CallTargets of the NORMAL groups. */
    @Test
    void normalGroupTopologyHasNoCallbackRoot() throws Exception {
        try (Harness normal = new Harness(ProtosExecutionMode.NORMAL, null)) {
            normal.run(() -> {
                for (String characters : List.of(DIRECT, WITNESS)) {
                    ProtosSemanticBytecodeRootNode module =
                            compile(characters, "topology-" + characters.length());
                    List<ProtosSemanticBytecodeRootNode> roots = groupRoots(module);
                    /*
                     * Roots are numbered when opened: the module root, the Closure root it
                     * creates, and that parameterless Closure's arity-rejection root nested
                     * first in it. The folded callback is never lowered, so it has no root.
                     */
                    assertEquals(3, roots.size(), characters);
                    assertSame(module, roots.get(0));
                    assertArityRejection(roots.get(1), roots.get(2));

                    ProtosClosureValue closure = assertInstanceOf(
                            ProtosClosureValue.class,
                            module.getCallTarget().call(normal.moduleActivation()));
                    ProtosClosureExecutionPlan plan = closure.executionPlan().orElseThrow();
                    assertSame(roots.get(1), plan.bytecodeActivationRootForTesting());
                    assertSame(
                            roots.get(1).getCallTarget(),
                            plan.bytecodeActivationTargetForComposition());
                    assertEquals(ProtosExecutionMode.NORMAL, plan.bytecodeProfileForTesting());
                    assertInteger(1, callClosure(normal, closure));
                }
                System.out.println("I101_A_NORMAL_TOPOLOGY=PASS");
            });
        }
    }

    /** DEBUG keeps the ordinary send, the physical callback root and its source/RootTag. */
    @Test
    void debugWitnessKeepsTheOrdinarySendAndPhysicalCallback() throws Exception {
        try (Harness debug = new Harness(ProtosExecutionMode.DEBUG, null)) {
            debug.run(() -> {
                ProtosSemanticBytecodeRootNode module = compile(WITNESS, "debug-witness");
                List<ProtosSemanticBytecodeRootNode> roots = groupRoots(module);
                // Module, Closure, its arity root, the callback root, the callback's arity root.
                assertEquals(5, roots.size());
                assertArityRejection(roots.get(1), roots.get(2));
                assertArityRejection(roots.get(3), roots.get(4));

                // Unfolded in DEBUG: the ordinary send keeps no literal-body capability.
                assertTrue(!roots.get(1).provablyNonSuspendingBody());
                assertTrue(!roots.get(1).headerlessEntryBody());
                List<String> activation = completeSignature(roots.get(1));
                assertNotEquals(
                        completeSignature(handwrittenLiteralOneActivationRoot()), activation);
                assertTrue(activation.stream().anyMatch(entry -> entry.contains("PrepareSendOne")),
                        activation::toString);
                assertTrue(
                        activation.stream()
                                .anyMatch(entry -> entry.contains("IsCompactCanonicalBooleanMiss")),
                        activation::toString);

                ProtosSemanticBytecodeRootNode callback = roots.get(3);
                assertNotNull(callback.getSourceSection());
                // A Closure root spans its body sequence, as the direct Closure root does.
                assertEquals("1", callback.getSourceSection().getCharacters().toString());
                assertTrue(hasRootTag(callback.getBytecodeNode().getTagTree()),
                        "the callback keeps its own RootTag");

                ProtosClosureValue closure = assertInstanceOf(
                        ProtosClosureValue.class,
                        module.getCallTarget().call(debug.moduleActivation()));
                assertEquals(
                        ProtosExecutionMode.DEBUG,
                        closure.executionPlan().orElseThrow().bytecodeProfileForTesting());
                assertInteger(1, callClosure(debug, closure));
                System.out.println("I101_A_DEBUG_PHYSICAL_CALLBACK=PASS");
            });
        }
    }

    /**
     * Reparse ({@code ensureComplete}) keeps the group, its root and CallTarget identities, its
     * arity targets and every semantic instruction, and only adds tag instrumentation.
     */
    @Test
    void reparseKeepsTopologyIdentitiesAndSemanticInstructions() throws Exception {
        for (ProtosExecutionMode mode : ProtosExecutionMode.values()) {
            try (Harness harness = new Harness(mode, null)) {
                harness.run(() -> {
                    ProtosSemanticBytecodeRootNode module =
                            compile(WITNESS, "reparse-" + mode);
                    BytecodeRootNodes<?> group = module.getRootNodes();
                    List<ProtosSemanticBytecodeRootNode> roots = groupRoots(module);
                    List<RootCallTarget> targets =
                            roots.stream().map(RootNode::getCallTarget).toList();
                    List<RootCallTarget> arityTargets = roots.stream()
                            .map(ProtosSemanticBytecodeRootNode::arityRejectionTarget)
                            .toList();
                    List<List<String>> semantic =
                            roots.stream().map(root -> semanticSignature(root)).toList();

                    group.ensureComplete();
                    List<List<String>> complete =
                            roots.stream().map(root -> signature(root)).toList();
                    for (ProtosSemanticBytecodeRootNode root : roots) {
                        assertNotNull(root.getSourceSection(), mode.toString());
                    }
                    group.ensureComplete();

                    assertSame(group, module.getRootNodes());
                    assertEquals(roots, groupRoots(module), mode.toString());
                    for (int index = 0; index < roots.size(); index++) {
                        ProtosSemanticBytecodeRootNode root =
                                (ProtosSemanticBytecodeRootNode) group.getNode(index);
                        assertSame(roots.get(index), root);
                        assertSame(targets.get(index), root.getCallTarget());
                        assertSame(arityTargets.get(index), root.arityRejectionTarget());
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

    /** NORMAL and DEBUG Contexts of one shared Engine each lower under their own profile. */
    @Test
    void mixedContextsOfOneEngineKeepTheirOwnProfiles() throws Exception {
        try (Engine engine = Engine.create();
                Harness normal = new Harness(ProtosExecutionMode.NORMAL, engine);
                Harness debug = new Harness(ProtosExecutionMode.DEBUG, engine)) {
            for (int round = 0; round < 2; round++) {
                String name = "mixed-" + round;
                List<String> normalSignature = normal.call(() -> {
                    List<String> expected =
                            completeSignature(handwrittenLiteralOneActivationRoot());
                    List<String> actual = completeSignature(activationRoot(compile(WITNESS, name)));
                    assertEquals(expected, actual);
                    return actual;
                });
                List<String> debugSignature = debug.call(() ->
                        completeSignature(activationRoot(compile(WITNESS, name))));
                assertNotEquals(normalSignature, debugSignature);
                assertTrue(
                        debugSignature.stream().anyMatch(entry -> entry.contains("PrepareSendOne")),
                        debugSignature::toString);
            }
        }
        System.out.println("I101_A_MIXED_CONTEXTS=PASS");
    }

    /**
     * A Closure projected into another Context is lowered under the destination profile, in both
     * directions; the source template keeps its own executable.
     */
    @Test
    void closureProjectionUsesTheDestinationContextProfile() throws Exception {
        try (Harness normal = new Harness(ProtosExecutionMode.NORMAL, null);
                Harness debug = new Harness(ProtosExecutionMode.DEBUG, null)) {
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
                assertEquals(ProtosExecutionMode.DEBUG, projected.bytecodeProfileForTesting());
                assertSame(projected, destination.bytecodeExecutionPlanForDefinition(
                        normalClosure.definition(), normalTemplate));
                List<String> signature =
                        completeSignature(projected.bytecodeActivationRootForTesting());
                assertTrue(signature.stream().anyMatch(entry -> entry.contains("PrepareSendOne")),
                        signature::toString);
            });
            normal.run(() -> {
                ProtosLanguageContext destination = ProtosLanguageContext.current();
                ProtosClosureExecutionPlan projected =
                        destination.bytecodeExecutionPlanForDefinition(
                                debugClosure.definition(), debugTemplate);
                assertEquals(ProtosExecutionMode.NORMAL, projected.bytecodeProfileForTesting());
                assertEquals(
                        completeSignature(handwrittenLiteralOneActivationRoot()),
                        completeSignature(projected.bytecodeActivationRootForTesting()));
            });
            assertEquals(ProtosExecutionMode.NORMAL, normalTemplate.bytecodeProfileForTesting());
            assertEquals(ProtosExecutionMode.DEBUG, debugTemplate.bytecodeProfileForTesting());
        }
        System.out.println("I101_A_PROJECTION_DESTINATION_PROFILE=PASS");
    }

    /** An imported module is compiled under its owning Context's profile. */
    @Test
    void importedModuleUsesTheOwningContextProfile() throws Exception {
        List<String> oracle;
        try (Harness normal = new Harness(ProtosExecutionMode.NORMAL, null)) {
            oracle = normal.call(() -> completeSignature(handwrittenLiteralOneActivationRoot()));
        }
        for (ProtosExecutionMode mode : ProtosExecutionMode.values()) {
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
                    ProtosStandaloneHostedSession.openForTesting(CORE, main, mode)) {
                assertEquals(ProtosExecutionOutcome.State.COMPLETED,
                        session.initialOutcome().state(), mode.toString());
                ProtosStandaloneHostedSession.PreparedTopLevel run =
                        session.prepareTopLevel("run");
                ProtosClosureExecutionPlan plan =
                        run.closureForTesting().executionPlan().orElseThrow();
                assertEquals(mode, plan.bytecodeProfileForTesting());
                List<String> signature =
                        completeSignature(plan.bytecodeActivationRootForTesting());
                if (mode == ProtosExecutionMode.NORMAL) {
                    assertEquals(oracle, signature);
                } else {
                    assertTrue(
                            signature.stream().anyMatch(entry -> entry.contains("PrepareSendOne")),
                            signature::toString);
                }
                for (int iteration = 0; iteration < 3; iteration++) {
                    assertEquals(1, run.executable().execute().asInt(), mode.toString());
                }
            }
        }
        System.out.println("I101_A_IMPORTED_MODULE_PROFILE=PASS");
    }

    /**
     * A NORMAL root omits the roots of the Boolean callbacks it inlines or never selects, so an
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
        for (ProtosExecutionMode mode : ProtosExecutionMode.values()) {
            try (Harness harness = new Harness(mode, null)) {
                harness.run(() -> {
                    ProtosSemanticBytecodeRootNode module =
                            compile(program, "omitted-callbacks-" + mode);
                    List<String> run = completeSignature(groupRoots(module).get(1));
                    long inlineSites = run.stream()
                            .filter(entry -> entry.startsWith("c.PrepareInlineClosureLiteralCall"))
                            .count();
                    assertEquals(mode == ProtosExecutionMode.NORMAL ? 2L : 0L, inlineSites,
                            () -> mode + " " + run);
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
     * Fold boundary: only an admitted literal receiver whose selected EXACT callback body is one
     * pure literal folds. Every other shape keeps ordinary semantics under both profiles.
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
                // Integer, a receiver/canonical-Boolean result policy and a wrong arity.
                new Case("() => { false.ifTrue() { 1 } }", null, ProtosNullValue.INSTANCE),
                new Case("() => { true.ifTrue() { 1 + 2 } }", "() => { 3 }", 3L),
                new Case("() => {\n    true.ifTrue() { ^1 }\n    2\n}", "() => { 1 }", 1L),
                new Case("() => { true.ifTrue() { 99999999999999999999 } }",
                        "() => { 99999999999999999999 }",
                        new BigInteger("99999999999999999999")),
                new Case("() => { true.if() { 1 } }", "() => { true }", ProtosBooleanValue.TRUE),
                new Case("() => { true.and() { true } }", "() => { true }",
                        ProtosBooleanValue.TRUE),
                new Case("() => { Error.handle(() => { true.ifTrue() }, (caught) => { 7 }) }",
                        null, 7L));
        List<Integer> foldedCases = List.of(0, 1, 2, 3);
        for (ProtosExecutionMode mode : ProtosExecutionMode.values()) {
            try (Harness harness = new Harness(mode, null)) {
                harness.run(() -> {
                    for (int index = 0; index < cases.size(); index++) {
                        Case test = cases.get(index);
                        String name = "boundary-" + index;
                        ProtosSemanticBytecodeRootNode module = compile(test.closure(), name);
                        List<String> actual = completeSignature(activationRoot(module));
                        if (test.folded() != null) {
                            List<String> folded = completeSignature(activationRoot(
                                    compile(test.folded(), name + "-folded")));
                            boolean expectFold = mode == ProtosExecutionMode.NORMAL
                                    && foldedCases.contains(index);
                            assertEquals(expectFold, folded.equals(actual),
                                    mode + " " + test.closure() + "\n" + actual);
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
     * Handwritten expectation of the NORMAL activation root: a parameterless Closure root whose
     * PERF032-G6 arity-rejection root is nested first, followed by a direct return
     * of the Long carrier of {@code 1}. Source metadata and the arity root remain;
     * ordinary guest-value materialization is handled by the call boundary.
     */
    private static ProtosSemanticBytecodeRootNode handwrittenLiteralOneActivationRoot() {
        String characters = DIRECT;
        Source source = Source.newBuilder(ProtosLanguage.ID, characters, "i101-a-oracle.protos")
                .build();
        int bodyStart = characters.indexOf('{');
        int bodyLength = characters.lastIndexOf('}') + 1 - bodyStart;
        BytecodeRootNodes<ProtosSemanticBytecodeRootNode> nodes =
                ProtosSemanticBytecodeRootNodeGen.create(
                        LANGUAGE_REF.get(null),
                        BytecodeConfig.DEFAULT,
                        builder -> {
                            builder.beginSource(source);
                            builder.beginSourceSection(bodyStart, bodyLength);
                            builder.beginRoot();

                            builder.beginSourceSection(bodyStart, bodyLength);
                            builder.beginRoot();
                            builder.beginCheckFrameClosureArgumentUpperBound();
                            builder.emitLoadConstant(0);
                            builder.endCheckFrameClosureArgumentUpperBound();
                            builder.beginReturn();
                            builder.emitLoadConstant(ProtosNullValue.INSTANCE);
                            builder.endReturn();
                            builder.endRoot();
                            builder.endSourceSection();

                            builder.beginReturn();
                            builder.emitLoadConstant(1L);
                            builder.endReturn();

                            builder.endRoot();
                            builder.endSourceSection();
                            builder.endSource();
                        });
        return nodes.getNode(0);
    }

    /** The production ParsingRequest path of the entered Context. */
    private static ProtosSemanticBytecodeRootNode compile(String characters, String name) {
        Source source = Source.newBuilder(ProtosLanguage.ID, characters, "i101-a-" + name + ".protos")
                .build();
        RootCallTarget target = assertInstanceOf(
                RootCallTarget.class, ProtosLanguageContext.current().parsePublic(source));
        return assertInstanceOf(ProtosSemanticBytecodeRootNode.class, target.getRootNode());
    }

    /** The Closure root created by a module whose only statement is a Closure literal. */
    private static ProtosSemanticBytecodeRootNode activationRoot(
            ProtosSemanticBytecodeRootNode module) {
        return groupRoots(module).get(1);
    }

    /** The roots of {@code root}'s group in group order. */
    private static List<ProtosSemanticBytecodeRootNode> groupRoots(
            ProtosSemanticBytecodeRootNode root) {
        return root.getRootNodes().getNodes().stream()
                .map(ProtosSemanticBytecodeRootNode.class::cast)
                .toList();
    }

    private static void assertArityRejection(
            ProtosSemanticBytecodeRootNode closureRoot,
            ProtosSemanticBytecodeRootNode rejectionRoot) {
        assertSame(rejectionRoot.getCallTarget(), closureRoot.arityRejectionTarget());
        assertTrue(
                signature(rejectionRoot).stream()
                        .anyMatch(entry -> entry.contains("CheckFrameClosureArgumentUpperBound")),
                () -> signature(rejectionRoot).toString());
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

    private static List<String> completeSignature(ProtosSemanticBytecodeRootNode root) {
        root.getRootNodes().ensureComplete();
        return signature(root);
    }

    /** Every instruction with every argument; nothing is filtered. */
    private static List<String> signature(ProtosSemanticBytecodeRootNode root) {
        List<String> entries = new ArrayList<>();
        for (Instruction instruction : root.getBytecodeNode().getInstructionsAsList()) {
            StringBuilder entry = new StringBuilder(instruction.getName());
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
     * Reparse comparison only: the instructions other than tag and trace instrumentation, whose
     * insertion is the expected metadata expansion and shifts bytecode indices.
     */
    private static List<String> semanticSignature(ProtosSemanticBytecodeRootNode root) {
        List<String> entries = new ArrayList<>();
        for (Instruction instruction : root.getBytecodeNode().getInstructionsAsList()) {
            if (instruction.getName().startsWith("tag.")
                    || instruction.getName().startsWith("trace.")) {
                continue;
            }
            StringBuilder entry = new StringBuilder(instruction.getName());
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
                assertTrue(entry.equals("load.null") || entry.equals("return"),
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
        if (value == ProtosBooleanValue.TRUE || value == ProtosBooleanValue.FALSE) {
            return "Protos(" + (value == ProtosBooleanValue.TRUE) + ")";
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
        private final ProtosExecutionMode mode;
        private final Context context;
        private ProtosPrelude prelude;

        Harness(ProtosExecutionMode mode, Engine engine) {
            this.mode = mode;
            Context.Builder builder = Context.newBuilder(ProtosLanguage.ID);
            if (engine != null) {
                builder.engine(engine);
            }
            if (mode == ProtosExecutionMode.DEBUG) {
                builder.allowExperimentalOptions(true).option("protos.Debug", "true");
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
                assertEquals(mode, ProtosLanguageContext.current().executionMode());
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
            context.close();
        }
    }
}
