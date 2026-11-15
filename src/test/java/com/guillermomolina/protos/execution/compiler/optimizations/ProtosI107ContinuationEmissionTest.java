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
import com.oracle.truffle.api.bytecode.TagTreeNode;
import com.oracle.truffle.api.instrumentation.StandardTags;
import com.oracle.truffle.api.source.Source;
import java.math.BigInteger;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;

/**
 * I107 (PLAT061-B, D204-A) physical evidence: the lowerer consumes its
 * pre-builder emission plan, so a nested constant Boolean-control site whose
 * selection is complete under the published standard root folds to its proven
 * literal and loses the ordinary send, its continuation composition ({@code
 * IsContinuation}, {@code Yield}, {@code ResumeDispatchedContinuation}) and its
 * callback roots; the published root capability is the consumed EFFECTIVE_ROOT
 * proof. Every dynamic selection keeps its generic fallback.
 *
 * <p>Signatures carry every instruction with every argument; nothing is
 * filtered before a strict comparison. The oracle is a handwritten {@link
 * ProtosBytecodeRootNodeGen.Builder} program that never consults the
 * parser, canonicalizer, lowerer or the analysis under test.
 */
final class ProtosI107ContinuationEmissionTest {
    private static final Path CORE = Path.of("protos", "lib", "core");
    private static final LanguageReference<ProtosLanguage> LANGUAGE_REF =
            LanguageReference.create(ProtosLanguage.class);

    private static final String NESTED =
            "() => { true.ifTrue() { false.ifFalse() { true.ifTrueIfFalse(() => { 7 }, () => { x() }) } } }";
    /** Same nesting whose innermost value is a send: no fold is proven. */
    private static final String NESTED_UNPROVEN =
            "() => { true.ifTrue() { false.ifFalse() { 7 + 0 } } }";
    /** Exact Bytecode instruction names of the continuation composition. */
    private static final List<String> CONTINUATION =
            List.of("c.IsContinuation", "yield", "c.ResumeDispatchedContinuation");

    @Test
    void nestedConstantControlFoldsToTheIndependentOracle() throws Exception {
        try (Harness normal = new Harness(false)) {
            normal.run(() -> {
                ProtosBytecodeRootNode module = compile(NESTED, "nested");
                ProtosBytecodeRootNode activation = activationRoot(module);
                List<String> expected = completeSignature(handwrittenInlineRegionsRoot(NESTED, 7L, 3));
                List<String> actual = completeSignature(activation);
                System.out.println("I107_ORACLE_SIGNATURE=" + expected);
                System.out.println("I107_NESTED_SIGNATURE=" + actual);

                assertEquals(expected, actual, "nested fold must equal the Builder oracle");
                assertNoContinuation(actual);
                assertTrue(actual.stream().anyMatch(entry -> entry.contains("=Long(7)")));
                // The group: module and Closure only; no callback root was lowered.
                assertEquals(2, groupRoots(module).size());
                assertSame(activation, groupRoots(module).get(1));
                assertTrue(activation.provablyNonSuspendingBody());
                assertEquals(bodyText(NESTED),
                        activation.getSourceSection().getCharacters().toString());

                // Second generation: a full reparse keeps the identical topology.
                RootCallTarget target = activation.getCallTarget();
                module.getRootNodes().ensureComplete();
                assertEquals(actual, signature(activation));
                assertSame(target, activation.getCallTarget());
                ProtosBytecodeRootNode equivalent =
                        activationRoot(compile(NESTED, "again"));
                assertEquals(expected, completeSignature(equivalent));
                assertTrue(activation.provablyNonSuspendingBody());
                assertTrue(equivalent.provablyNonSuspendingBody());

                assertInteger(7, callClosure(normal, closureOf(normal, module)));
            });
        }
    }

    /**
     * The same shape whose innermost value is a send is not folded. I115: both nested
     * literal-Boolean controls are statically selected inline regions with no callback root, and the innermost {@code 7 + 0} is the {@code NumberAdd} arithmetic operation,
     * which owns its fast path and its ordinary-dispatch fallback, instead of an explicit
     * PrepareSend plus continuation composition. The absence of a root-level Yield is not a
     * non-suspension proof.
     */
    @Test
    void unprovenNestingUsesInlineNumericTopology() throws Exception {
        try (Harness normal = new Harness(false)) {
            normal.run(() -> {
                ProtosBytecodeRootNode module = compile(NESTED_UNPROVEN, "unproven");
                ProtosBytecodeRootNode activation = activationRoot(module);
                List<String> actual = completeSignature(activation);
                List<String> folded = completeSignature(activationRoot(compile(NESTED, "folded")));
                int add = -1;
                for (int index = 0; index < actual.size(); index++) {
                    if (actual.get(index).split(" ", 2)[0].equals("c.NumberAdd")) {
                        add = index;
                    }
                }
                assertTrue(add >= 0, () -> "NumberAdd missing in " + actual);
                // Both nested literal controls are statically selected: no Boolean
                // literal and no conditional branch remains for them, only their inline
                // regions (RootTag), both entered before the arithmetic.
                assertFalse(actual.stream().anyMatch(entry -> entry.startsWith("load.constant ")
                                && (entry.contains("=Boolean(") || entry.contains("=Protos(true)")
                                        || entry.contains("=Protos(false)"))),
                        actual::toString);
                int regions = 0;
                for (int index = 0; index < add; index++) {
                    if (actual.get(index).equals("tag.enter tag:TAG_NODE=[RootTag]")) {
                        regions++;
                    }
                }
                // The root's own RootTag plus one region per nested control.
                assertEquals(3, regions, actual::toString);
                // The fused '+' selects its behavior after its operands (D237) and
                // performs any ordinary send itself, followed by the continuation
                // composition; no separate selection guard is emitted.
                assertTrue(actual.stream()
                        .noneMatch(entry -> entry.startsWith("c.IsStandardNumericSelection ")),
                        actual::toString);
                assertHasContinuation(actual);
                // Both operands of the arithmetic dispatch are staged before it.
                assertTrue(add >= 2, actual::toString);
                // I091: the scalar operand is a primitive carrier (Integer or Long).
                assertTrue(actual.stream().anyMatch(entry -> entry.contains("=Integer(7)")
                                || entry.contains("=Long(7)")),
                        actual::toString);
                assertTrue(folded.size() < actual.size(), folded + " vs " + actual);
                assertEquals(2, groupRoots(module).size());
                assertSame(module, groupRoots(module).get(0));
                assertSame(activationRoot(module), groupRoots(module).get(1));
                assertFalse(activation.provablyNonSuspendingBody());

                RootCallTarget target = activation.getCallTarget();
                module.getRootNodes().ensureComplete();
                assertEquals(actual, signature(activation));
                assertSame(target, activation.getCallTarget());
                assertFalse(activation.provablyNonSuspendingBody());
                assertInteger(7, callClosure(normal, closureOf(normal, module)));
            });
        }
    }

    /**
     * PLAT067-B: with a debugger attached the same nested shape still folds to the independent
     * oracle: no callback root, no continuation, and no fabricated ordinary send.
     */
    @Test
    void attachedDebuggerKeepsTheFoldedOracleTopology() throws Exception {
        try (Harness debug = new Harness(true)) {
            debug.run(() -> {
                ProtosBytecodeRootNode module = compile(NESTED, "debug");
                ProtosBytecodeRootNode activation = activationRoot(module);
                List<String> actual = completeSignature(activation);
                assertEquals(completeSignature(handwrittenInlineRegionsRoot(NESTED, 7L, 3)), actual);
                assertNoContinuation(actual);
                assertEquals(2, groupRoots(module).size());
                assertTrue(activation.provablyNonSuspendingBody());
                assertInteger(7, callClosure(debug, closureOf(debug, module)));
            });
        }
    }

    /** Effective-root proofs that the previous literal/parameter predicate rejected. */
    @Test
    void generalSafeBodiesPublishTheEffectiveRootCapability() throws Exception {
        StringBuilder sequence = new StringBuilder("() => {\n");
        for (int index = 0; index < 200; index++) {
            sequence.append("v").append(index).append(": ")
                    .append(index == 0 ? "1" : "v" + (index - 1)).append('\n');
        }
        sequence.append("v199\n}");
        record Case(String source, Object expected) {}
        List<Case> cases = List.of(
                new Case("() => { a: 1\n b: a\n b }", 1L),
                new Case(sequence.toString(), 1L),
                new Case("() => { true.ifTrue() { 1 }\n false.ifFalse() { 2 }\n 3 }", 3L),
                new Case("(value: 7) => { value }", 7L),
                new Case("(a, b) => { a === b }", null));
        try (Harness normal = new Harness(false)) {
            normal.run(() -> {
                for (Case test : cases) {
                    ProtosBytecodeRootNode module = compile(test.source(), "safe");
                    ProtosBytecodeRootNode activation = activationRoot(module);
                    List<String> instructions = completeSignature(activation);
                    assertNoContinuation(instructions);
                    assertTrue(activation.provablyNonSuspendingBody(), test.source());
                    if (test.expected() != null) {
                        assertInteger((Long) test.expected(),
                                callClosure(normal, closureOf(normal, module)));
                    }
                }
            });
        }
    }

    @Test
    void unknownSubjectsKeepTheCapabilityOff() throws Exception {
        List<String> sources = List.of(
                "(f) => { f.value() }",
                "(value: compute()) => { 1 }",
                "(first: 7, ...tail) => { first }",
                "() => { escape: () => { ^1 }\n escape()\n 0 }",
                "(f) => { f() }",
                "(b) => { b.ifTrue() { 1 } }",
                "() => { self: () => { self() }\n 1 }",
                "() => { Error.handle(() => { true.ifTrue() }, (caught) => { 7 }) }");
        try (Harness normal = new Harness(false)) {
            normal.run(() -> {
                for (String source : sources) {
                    ProtosBytecodeRootNode activation =
                            activationRoot(compile(source, "unknown"));
                    completeSignature(activation);
                    if (!source.contains("self")) {
                        assertFalse(activation.provablyNonSuspendingBody(), source);
                    }
                }
            });
        }
    }

    /** Guarded sites keep the complete fallback even when the expected body is trivial. */
    @Test
    void dynamicSelectionsKeepTheirPhysicalFallback() throws Exception {
        List<String> sources = List.of(
                // Ordinary call of a parameter followed by guest work: D013
                // dynamic selection. (A final call of a parameter is an I112
                // terminal invocation; see terminalCallWithoutYieldIsNotANonSuspensionProof.)
                "(f) => { f()\n 1 }",
                // Binding candidate: the guard may miss after replacement/override.
                "() => { f: () => { 1 }\n f() }",
                "() => { f: () => { 1 }\n f = () => { 2 }\n f() }",
                // Terminal method calls are tested separately below.
                // Spread argument.
                "(f, items) => { f(...items) }");
        try (Harness normal = new Harness(false)) {
            normal.run(() -> {
                for (String source : sources) {
                    List<String> instructions =
                            completeSignature(activationRoot(compile(source, "fallback")));
                    assertHasContinuation(instructions);
                }

                // I115: a final ifTrue on a dynamic receiver is internal control, not a
                // terminal send. A canonical Boolean is decided by CanonicalControlCondition
                // and its callback is invoked (CallNilaryMethod); any other receiver takes the
                // guarded ordinary send (GetMethod + CallUnaryMethod). Both invocations keep
                // the continuation composition.
                ProtosBytecodeRootNode terminalModule =
                        compile("(b, g) => { b.ifTrue(g) }", "terminal-method");
                List<String> terminalMethod =
                        completeSignature(activationRoot(terminalModule));
                for (String operation : List.of(
                        "c.CanonicalControlCondition",
                        "c.CallNilaryMethod",
                        "c.GetMethod",
                        "c.CallUnaryMethod")) {
                    assertTrue(
                            terminalMethod.stream().anyMatch(
                                    entry -> entry.split(" ", 2)[0].equals(operation)),
                            () -> operation + " missing in " + terminalMethod);
                }
                assertHasContinuation(terminalMethod);
                ProtosClosureValue terminalClosure = closureOf(normal, terminalModule);
                assertInteger(5, callClosureWith(normal, terminalClosure, "true, () => { 5 }"));
                assertSame(ProtosNullValue.INSTANCE,
                        callClosureWith(normal, terminalClosure, "false, () => { 5 }"));
                assertInteger(99, callClosureWith(normal, terminalClosure,
                        "{ ifTrue: (callback) => { 99 } }, () => { 5 }"));

                // I115: a literal Closure receiver is statically selected under the
                // frozen standard root: an inline region without fallback or continuation.
                ProtosBytecodeRootNode literalCall =
                        compile("() => { (() => { 1 })() }", "literal-call");
                List<String> literalRegion = completeSignature(activationRoot(literalCall));
                assertNoContinuation(literalRegion);
                assertTrue(literalRegion.stream().anyMatch(
                                entry -> entry.equals("tag.enter tag:TAG_NODE=[RootTag]")),
                        literalRegion::toString);
                assertInteger(1, callClosure(normal, closureOf(normal, literalCall)));

                ProtosBytecodeRootNode replaced =
                        compile("() => { f: () => { 1 }\n f = () => { 2 }\n f() }", "replace");
                assertInteger(2, callClosure(normal, closureOf(normal, replaced)));
            });
        }
    }

    /**
     * I115: composed Call1 uses CallUnaryMethod(false). A pending result
     * returned directly by a source CallTarget is composed through
     * ResumeDispatchedContinuation; the physical prepared-call continuation
     * remains for the operations that still return a PreparedClosureCall.
     */
    @Test
    void composedUnaryCallUsesPhysicalContinuation() throws Exception {
        try (Harness normal = new Harness(false)) {
            normal.run(() -> {
                ProtosBytecodeRootNode module =
                        compile("(f) => { f(1)\n 9 }", "composed-unary");

                List<String> instructions =
                        completeSignature(activationRoot(module));

                assertHasContinuation(instructions);

                assertTrue(
                        instructions.stream().anyMatch(
                                entry -> entry.split(" ", 2)[0]
                                        .equals("c.CallUnaryMethod")),
                        () -> "CallUnaryMethod missing in " + instructions);

                assertTrue(
                        instructions.stream().anyMatch(
                                entry -> entry.split(" ", 2)[0]
                                        .equals("c.ResumeDispatchedContinuation")),
                        () -> "ResumeDispatchedContinuation missing in " + instructions);

                assertFalse(
                        instructions.stream().anyMatch(
                                entry -> entry.split(" ", 2)[0]
                                        .equals("c.InvokeClosureOne")),
                        () -> "legacy InvokeClosureOne in " + instructions);

                assertInteger(
                        9,
                        callClosureWith(
                                normal,
                                closureOf(normal, module),
                                "(value) => { value + 4 }"));
            });
        }
    }

    /**
     * I112 / PLAT062-T: a terminal Closure invocation emits no Yield in its
     * root, because no guest work follows it, yet it is not a proof of
     * non-suspension: the invoked call may still suspend and the root then
     * returns its terminal transport. A non-terminal unknown call keeps the
     * physical C-prime protocol.
     */
    @Test
    void terminalCallWithoutYieldIsNotANonSuspensionProof() throws Exception {
        record Case(String source, String operation, String argument, long expected) {}
        List<Case> terminal = List.of(
                new Case("(f) => { f() }", "c.CallNilaryMethod",
                        "() => { 5 }", 5),
                new Case("(f) => { f(1) }", "c.CallUnaryMethod",
                        "(value) => { value + 4 }", 5),
                // Two arguments: the callable plus two operands (CallBinaryMethod).
                new Case("(f) => { f(2, 3) }", "c.CallBinaryMethod",
                        "(a, b) => { a + b }", 5));
        try (Harness normal = new Harness(false)) {
            normal.run(() -> {
                for (Case test : terminal) {
                    ProtosBytecodeRootNode module = compile(test.source(), "terminal");
                    ProtosBytecodeRootNode activation = activationRoot(module);
                    List<String> instructions = completeSignature(activation);
                    assertTrue(
                            instructions.stream().anyMatch(
                                    entry -> entry.split(" ", 2)[0].split("\\$", 2)[0].equals(test.operation())),
                            () -> test.operation() + " missing in " + instructions);
                    assertNoContinuation(instructions);
                    for (String absent : List.of(
                            "c.IsPreparedClosureInvocation",
                            "c.EnterClosureCall",
                            "c.FinishClosureCall")) {
                        assertFalse(
                                instructions.stream().anyMatch(
                                        entry -> entry.split(" ", 2)[0].equals(absent)),
                                () -> absent + " in " + instructions);
                    }
                    // Terminal without an outer Yield is not non-suspending.
                    assertFalse(activation.provablyNonSuspendingBody(), test.source());

                    module.getRootNodes().ensureComplete();
                    assertEquals(instructions, signature(activation));
                    assertFalse(activation.provablyNonSuspendingBody(), test.source());

                    assertInteger(
                            test.expected(),
                            callClosureWith(
                                    normal, closureOf(normal, module), test.argument()));
                }

                ProtosBytecodeRootNode nonTerminal =
                        activationRoot(compile("(f) => { f()\n 1 }", "non-terminal"));
                List<String> composed = completeSignature(nonTerminal);
                assertHasContinuation(composed);
                assertFalse(composed.stream().anyMatch(entry -> entry.contains("Tail")),
                        composed::toString);
                assertFalse(nonTerminal.provablyNonSuspendingBody());
            });
        }
    }

    @Test
    void inlineFallbackContinuationIsAccountedFor() throws Exception {
        String source = """
                () => {
                    b: true
                    b.ifTrue() { 1 }
                }
                """;
        try (Harness normal = new Harness(false)) {
            normal.run(() -> {
                ProtosBytecodeRootNode module =
                        compile(source, "inline-fallback");
                ProtosBytecodeRootNode activation = activationRoot(module);
                List<String> actual = completeSignature(activation);

                // I115-H7: a dispatching Boolean control. The staged receiver
                // is tested by CanonicalControlCondition and a
                // conditional branch selects the inline body (HIT); any other
                // value takes the ordinary send (MISS). The structural check
                // alone does not prove the fallback: the HIT and MISS
                // behaviors are exercised separately below.
                int condition = -1;
                int branch = -1;
                for (int index = 0; index < actual.size(); index++) {
                    String name = actual.get(index).split(" ", 2)[0];
                    if (condition < 0
                            && name.equals("c.CanonicalControlCondition")) {
                        condition = index;
                    }
                    if (condition >= 0 && branch < 0 && name.startsWith("branch.false")) {
                        branch = index;
                    }
                }
                assertTrue(condition >= 0, actual::toString);
                assertTrue(branch > condition, actual::toString);
                // I115: the receiver is a local, not a literal, so the site is not
                // statically selected; its guarded ordinary-send fallback keeps the
                // physical continuation composition, and that physical evidence keeps
                // the root unproven.
                assertHasContinuation(actual);
                assertFalse(activation.provablyNonSuspendingBody());

                // Compared before execution: running the root quickens its instructions.
                module.getRootNodes().ensureComplete();
                assertEquals(actual, signature(activation));
                assertFalse(activation.provablyNonSuspendingBody());
                assertInteger(1, callClosure(normal, closureOf(normal, module)));

                ProtosBytecodeRootNode equivalent =
                        activationRoot(compile(source, "inline-fallback-reparse"));
                assertEquals(actual, completeSignature(equivalent));
                assertFalse(equivalent.provablyNonSuspendingBody());

                // Admitted path versus failed guard: a dynamic canonical
                // Boolean runs the inline body, while a non-Boolean receiver
                // with its own ifTrue receives the ordinary send.
                ProtosBytecodeRootNode dynamicModule =
                        compile("(b) => { b.ifTrue() { 1 } }", "dynamic-boolean");
                ProtosBytecodeRootNode dynamicActivation = activationRoot(dynamicModule);
                completeSignature(dynamicActivation);
                // A dynamic receiver may select an overridden ifTrue: no guarantee.
                assertFalse(dynamicActivation.provablyNonSuspendingBody());
                ProtosClosureValue dynamic = closureOf(normal, dynamicModule);
                assertInteger(1, callClosureWith(normal, dynamic, "true"));
                assertSame(
                        ProtosNullValue.INSTANCE,
                        callClosureWith(normal, dynamic, "false"));
                assertInteger(99, callClosureWith(
                        normal, dynamic, "{ ifTrue: (callback) => { 99 } }"));

                ProtosBytecodeRootNode safe =
                        activationRoot(compile("() => { 1 }", "independent-root"));
                completeSignature(safe);
                assertTrue(safe.provablyNonSuspendingBody());
            });
        }
    }

    /** Each Context owns its lowerer and facts; nothing is shared across Contexts. */
    @Test
    void contextsDoNotShareRootsOrFacts() throws Exception {
        List<String> first = new ArrayList<>();
        try (Harness one = new Harness(false)) {
            one.run(() -> first.addAll(completeSignature(activationRoot(compile(NESTED, "one")))));
        }
        try (Harness two = new Harness(false)) {
            two.run(() -> {
                ProtosBytecodeRootNode activation = activationRoot(compile(NESTED, "two"));
                assertEquals(first, completeSignature(activation));
                assertTrue(activation.provablyNonSuspendingBody());
            });
        }
    }

    /**
     * Handwritten Closure root whose single statement holds {@code depth} nested inline regions
     * (each a statically selected control callback: RootTag plus its statement's
     * StatementTag + ExpressionTag); the innermost stores one Long literal carrier into a
     * plain local, which the root then returns. The source section is that of {@code
     * characters}' outermost body.
     */
    private static ProtosBytecodeRootNode handwrittenInlineRegionsRoot(
            String characters, long value, int depth) {
        Source source = Source.newBuilder(ProtosLanguage.ID, characters, "i107-oracle.protos")
                .build();
        int bodyStart = characters.indexOf('{');
        int bodyLength = characters.lastIndexOf('}') + 1 - bodyStart;
        String statement = bodyText(characters);
        int statementStart = characters.indexOf(statement);
        BytecodeRootNodes<ProtosBytecodeRootNode> nodes =
                ProtosBytecodeRootNodeGen.create(
                        LANGUAGE_REF.get(null),
                        BytecodeConfig.DEFAULT,
                        builder -> {
                            builder.beginSource(source);
                            builder.beginSourceSection(bodyStart, bodyLength);
                            builder.beginRoot();
                            BytecodeLocal result = builder.createLocal();
                            builder.beginBlock();
                            builder.beginSourceSection(statementStart, statement.length());
                            builder.beginTag(StandardTags.StatementTag.class, StandardTags.ExpressionTag.class);
                            for (int level = 0; level < depth; level++) {
                                builder.beginTag(StandardTags.CallTag.class);
                                builder.beginTag(StandardTags.RootTag.class);
                                builder.beginTag(StandardTags.StatementTag.class, StandardTags.ExpressionTag.class);
                            }
                            builder.beginStoreLocal(result);
                            builder.emitLoadConstant(value);
                            builder.endStoreLocal();
                            for (int level = 0; level < depth; level++) {
                                builder.endTag(StandardTags.StatementTag.class, StandardTags.ExpressionTag.class);
                                builder.endTag(StandardTags.RootTag.class);
                                builder.endTag(StandardTags.CallTag.class);
                            }
                            builder.endTag(StandardTags.StatementTag.class, StandardTags.ExpressionTag.class);
                            builder.endSourceSection();
                            builder.beginReturn();
                            builder.emitLoadLocal(result);
                            builder.endReturn();
                            builder.endBlock();
                            builder.endRoot();
                            builder.endSourceSection();
                            builder.endSource();
                        });
        return nodes.getNode(0);
    }

    /** The Closure body's statements, which the root's source section covers. */
    private static String bodyText(String characters) {
        return characters.substring(characters.indexOf('{') + 1, characters.lastIndexOf('}'))
                .trim();
    }

    private static void assertNoContinuation(List<String> signature) {
        for (String entry : signature) {
            String name = entry.split(" ", 2)[0].split("\\$", 2)[0];
            for (String operation : CONTINUATION) {
                assertFalse(name.equals(operation), () -> operation + " in " + signature);
            }
            assertFalse(name.contains("PrepareSend"), () -> "PrepareSend in " + signature);
        }
    }

    private static void assertHasContinuation(List<String> signature) {
        for (String operation : CONTINUATION) {
            assertTrue(
                    signature.stream().anyMatch(
                            entry -> entry.split(" ", 2)[0].split("\\$", 2)[0].equals(operation)),
                    () -> operation + " missing in " + signature);
        }
    }

    private static ProtosBytecodeRootNode compile(String characters, String name) {
        Source source = Source.newBuilder(
                        ProtosLanguage.ID,
                        characters,
                        "i107-" + name + "-" + System.nanoTime() + ".protos")
                .build();
        RootCallTarget target = assertInstanceOf(
                RootCallTarget.class, ProtosLanguageContext.current().parsePublic(source));
        return assertInstanceOf(ProtosBytecodeRootNode.class, target.getRootNode());
    }

    private static ProtosBytecodeRootNode activationRoot(
            ProtosBytecodeRootNode module) {
        return groupRoots(module).get(1);
    }

    private static List<ProtosBytecodeRootNode> groupRoots(
            ProtosBytecodeRootNode root) {
        return root.getRootNodes().getNodes().stream()
                .map(ProtosBytecodeRootNode.class::cast)
                .toList();
    }

    private static List<String> completeSignature(ProtosBytecodeRootNode root) {
        root.getRootNodes().ensureComplete();
        return signature(root);
    }

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
        if (value instanceof Long
                || value instanceof Integer
                || value instanceof Boolean
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
        return "Metadata(" + value.getClass().getName() + ")";
    }

    private static String tags(TagTreeNode node) {
        if (node == null) {
            return "no-tag-node";
        }
        return node.getTags().stream().map(Class::getSimpleName).sorted().toList().toString();
    }

    private static ProtosClosureValue closureOf(
            Harness harness, ProtosBytecodeRootNode module) {
        return assertInstanceOf(
                ProtosClosureValue.class,
                module.getCallTarget().call(harness.moduleActivation()));
    }

    private static Object callClosure(Harness harness, ProtosClosureValue closure) {
        ProtosActivation module = harness.moduleActivation();
        module.context().createLocalSlot("candidate", closure);
        return compile("candidate()", "call").getCallTarget().call(module);
    }

    private static Object callClosureWith(
            Harness harness, ProtosClosureValue closure, String argument) {
        ProtosActivation module = harness.moduleActivation();
        module.context().createLocalSlot("candidate", closure);
        return compile("candidate(" + argument + ")", "call-with").getCallTarget().call(module);
    }

    private static void assertInteger(long expected, Object value) {
        assertEquals(Long.toString(expected), ProtosNumericValueSupport.integerDecimalText(value));
    }

    @FunctionalInterface
    private interface Action {
        void run() throws Exception;
    }

    /** One raw Polyglot Context with a published Core, entered only while an action runs. */
    private static final class Harness implements AutoCloseable {
        private final Context context;
        private final DebuggerSession debugger;
        private ProtosPrelude prelude;

        /**
         * With {@code debugger}, a Truffle debugger session is attached to the Engine before any
         * lowering (PLAT067-B: it observes, it never selects another lowering).
         */
        Harness(boolean debugger) {
            this.context = Context.newBuilder(ProtosLanguage.ID).build();
            this.debugger =
                    debugger
                            ? Debugger.find(context.getEngine()).startSession(event -> {})
                            : null;
            context.initialize(ProtosLanguage.ID);
        }

        void run(Action action) throws Exception {
            context.enter();
            try {
                if (prelude == null) {
                    prelude = new ProtosCoreBootstrap().bootstrap(CORE);
                }
                action.run();
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
}
