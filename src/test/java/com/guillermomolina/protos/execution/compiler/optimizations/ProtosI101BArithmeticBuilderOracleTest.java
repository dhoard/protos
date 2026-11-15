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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import com.oracle.truffle.api.instrumentation.StandardTags;
import com.oracle.truffle.api.source.Source;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;

/**
 * I101-B arithmetic oracle candidates, independently handwritten in Bytecode DSL.
 *
 * <p>All three witnesses have statically known operands. The proposed *minimal*
 * executable root has folded sums (3L, 2L, 3L), retaining the explicitly
 * requested local stores and final local lookup for the two multi-statement
 * cases. These are strict, initially red target tests, not a claim that the
 * production lowerer currently has this proof or may unconditionally remove
 * dynamically selected guest sends.
 *
 * <p>Unlike a variable-plus-constant specialized node, this oracle
 * deliberately requires constant propagation across these exact immutable
 * literal initializer shapes. Protos's dynamic '+' dispatch remains a
 * semantic gate for any production implementation of this optimization.
 */
final class ProtosI101BArithmeticBuilderOracleTest {
    private static final Path CORE = Path.of("protos", "lib", "core");
    private static final LanguageReference<ProtosLanguage> LANGUAGE =
            LanguageReference.create(ProtosLanguage.class);

    private static final String LITERAL_ADD = "() => { 1 + 2 }";
    private static final String LOCAL_AND_LITERAL = "() => { x: 1; y: x + 1; y }";
    private static final String TWO_LOCALS = "() => { x: 1; y: 2; z: x + y; z }";

    @Test
    void literalAdditionMatchesFoldedThreeOracle() throws Exception {
        compare(LITERAL_ADD, "LITERAL_ADD", 3L,
                ProtosI101BArithmeticBuilderOracleTest::handwrittenLiteralAdd);
    }

    /*
     * I115: a '+' over locals is not propagated as a constant; it is one
     * fused NumberAdd that classifies its standard selection (D237) and
     * performs the ordinary send itself otherwise. The folded handwritten program stays the physical
     * lower bound of that site.
     */
    @Test
    void localPlusLiteralMatchesMinimalFoldedTwoOracle() throws Exception {
        compareGuarded(LOCAL_AND_LITERAL, "LOCAL_PLUS_LITERAL", 2L,
                ProtosI101BArithmeticBuilderOracleTest::handwrittenLocalPlusLiteral);
    }

    @Test
    void localPlusLocalMatchesMinimalFoldedThreeOracle() throws Exception {
        compareGuarded(TWO_LOCALS, "LOCAL_PLUS_LOCAL", 3L,
                ProtosI101BArithmeticBuilderOracleTest::handwrittenLocalPlusLocal);
    }

    private static void compareGuarded(
            String program, String caseName, long resultValue,
            Supplier<ProtosBytecodeRootNode> manual) throws Exception {
        try (Context context = Context.newBuilder(ProtosLanguage.ID).build()) {
            context.initialize(ProtosLanguage.ID);
            context.enter();
            try {
                ProtosPrelude prelude = new ProtosCoreBootstrap().bootstrap(CORE);
                ProtosBytecodeRootNode module = parse(program, caseName);
                List<ProtosBytecodeRootNode> realRoots = roots(module);
                assertEquals(2, realRoots.size(), "module and ordinary Closure root");
                ProtosBytecodeRootNode actual = realRoots.get(1);
                List<String> emitted = completeSignature(actual);
                List<String> lowerBound = completeSignature(manual.get());

                assertEquals(1, emitted.stream()
                                .filter(entry -> entry.split(" ", 2)[0].equals("c.NumberAdd"))
                                .count(),
                        () -> "c.NumberAdd in " + emitted);
                for (String operation : List.of(
                        "c.IsStandardNumericSelection", "c.GetMethod", "c.CallUnaryMethod")) {
                    assertEquals(0, emitted.stream()
                                    .filter(entry -> entry.split(" ", 2)[0].equals(operation))
                                    .count(),
                            () -> operation + " is not emitted beside the fused + in " + emitted);
                }
                assertTrue(lowerBound.size() < emitted.size(), "physical lower bound");

                ProtosClosureValue closure = assertInstanceOf(
                        ProtosClosureValue.class,
                        module.getCallTarget().call(prelude.newModuleActivation()));
                ProtosActivation invocation = prelude.newModuleActivation();
                invocation.context().createLocalSlot("candidate", closure);
                Object value = parse("candidate()", caseName + "-invoke")
                        .getCallTarget().call(invocation);
                assertEquals(Long.toString(resultValue),
                        ProtosNumericValueSupport.integerDecimalText(value));
            } finally {
                context.leave();
            }
        }
    }

    @Test
    void i101ConstantLongArithmeticMatchesLiteralBytecodeExactly()
            throws Exception {
        try (Context context = Context.newBuilder(ProtosLanguage.ID).build()) {
            context.initialize(ProtosLanguage.ID);
            context.enter();
            try {
                new ProtosCoreBootstrap().bootstrap(CORE);

                ProtosBytecodeRootNode literal =
                        roots(parse("() => { 2 }", "constant-two")).get(1);
                List<String> expected = completeSignature(literal);

                for (String expression : List.of(
                        "1 + 1",
                        "(1 + 1) + 0",
                        "1 + (1 + 0)",
                        "4 - 2",
                        "1 * 2",
                        "4 / 2")) {
                    ProtosBytecodeRootNode folded =
                            roots(parse(
                                    "() => { " + expression + " }",
                                    "constant-fold")).get(1);

                    List<String> actual = completeSignature(folded);

                    assertEquals(expected, actual,
                            "Constant operation must have precisely the "
                                    + "literal root's bytecode: " + expression);
                    assertTrue(actual.stream().noneMatch(
                                    instruction ->
                                            instruction.contains("TryPrimitiveNumeric")
                                                    || instruction.contains("PrepareSendOne")
                                                    || instruction.contains("TryDirectSendOne")),
                            "Constant operation unexpectedly emitted dispatch: "
                                    + expression);
                }
                System.out.println("I101_EXACT_LONG_LITERAL_EQUIVALENCE=PASS");
            } finally {
                context.leave();
            }
        }
    }

    private static void compare(
            String program, String caseName, long resultValue,
            Supplier<ProtosBytecodeRootNode> manual) throws Exception {
        try (Context context = Context.newBuilder(ProtosLanguage.ID).build()) {
            context.initialize(ProtosLanguage.ID);
            context.enter();
            try {
                ProtosPrelude prelude = new ProtosCoreBootstrap().bootstrap(CORE);

                ProtosBytecodeRootNode module = parse(program, caseName);
                List<ProtosBytecodeRootNode> realRoots = roots(module);
                // D203/I105: no arity-rejection root accompanies the Closure root.
                assertEquals(2, realRoots.size(),
                        "module and ordinary Closure root");
                assertSame(module, realRoots.get(0));
                ProtosBytecodeRootNode actual = realRoots.get(1);

                ProtosBytecodeRootNode oracle = manual.get();
                assertEquals(1, roots(oracle).size(),
                        "An independent builder has one executable root");

                List<String> expected = completeSignature(oracle);
                List<String> emitted = completeSignature(actual);
                System.out.println("I101_B_" + caseName + "_ORACLE_COUNT=" + expected.size());
                System.out.println("I101_B_" + caseName + "_PROTOS_COUNT=" + emitted.size());
                System.out.println("I101_B_" + caseName + "_ORACLE=" + expected);
                System.out.println("I101_B_" + caseName + "_ACTUAL=" + emitted);
                System.out.println("I101_B_" + caseName + "_BYTECODE_EQUAL="
                        + expected.equals(emitted));

                // Assert real guest semantics even if the strict oracle differs.
                ProtosClosureValue closure = assertInstanceOf(
                        ProtosClosureValue.class,
                        module.getCallTarget().call(prelude.newModuleActivation()));
                assertSame(actual,
                        closure.executionPlan().orElseThrow().bytecodeActivationRootForTesting());
                ProtosActivation invocation = prelude.newModuleActivation();
                invocation.context().createLocalSlot("candidate", closure);
                Object value = parse("candidate()", caseName + "-invoke")
                        .getCallTarget().call(invocation);
                assertEquals(Long.toString(resultValue),
                        ProtosNumericValueSupport.integerDecimalText(value),
                        "The actual guest program must evaluate correctly");
                System.out.println("I101_B_" + caseName + "_SEMANTICS=PASS");

                // The independent builder is a physical lower bound, not a
                // semantically valid replacement for overridable arithmetic.
                // A production send must preserve guarded canonical selection
                // and the ordinary fallback.
                assertEquals(expected, emitted,
                        "Protos must exactly reproduce the handwritten "
                                + "arithmetic Builder");
                assertTrue(expected.stream().anyMatch(s -> s.contains(
                                "Long(" + resultValue + ")")),
                        "Folded Long carrier missing from independent oracle");
                System.out.println("I101_B_" + caseName + "_ORACLE=PASS");
            } finally {
                context.leave();
            }
        }
    }

    private static ProtosBytecodeRootNode handwrittenLiteralAdd() {
        String code = LITERAL_ADD;
        Source source = source(code, "literal-add-oracle");
        int bodyStart = code.indexOf('{');
        int bodyLength = code.lastIndexOf('}') + 1 - bodyStart;
        String statement = "1 + 2";
        int statementStart = code.indexOf(statement);
        BytecodeRootNodes<ProtosBytecodeRootNode> nodes =
                ProtosBytecodeRootNodeGen.create(
                        LANGUAGE.get(null), BytecodeConfig.DEFAULT,
                        builder -> {
                            builder.beginSource(source);
                            builder.beginSourceSection(bodyStart, bodyLength);
                            builder.beginRoot();
                            builder.beginBlock();
                            builder.beginSourceSection(statementStart, statement.length());
                            builder.beginTag(StandardTags.StatementTag.class, StandardTags.ExpressionTag.class);
                            builder.beginBlock();
                            builder.beginReturn();
                            builder.emitLoadConstant(3L);
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

    private static ProtosBytecodeRootNode handwrittenLocalPlusLiteral() {
        String code = LOCAL_AND_LITERAL;
        Source source = source(code, "local-plus-literal-oracle");
        int bodyStart = code.indexOf('{');
        int bodyLength = code.lastIndexOf('}') + 1 - bodyStart;
        int xStart = code.indexOf("x: 1");
        int yStart = code.indexOf("y: x + 1");
        int terminalStart = code.lastIndexOf('y');
        BytecodeRootNodes<ProtosBytecodeRootNode> nodes =
                ProtosBytecodeRootNodeGen.create(
                        LANGUAGE.get(null), BytecodeConfig.DEFAULT,
                        builder -> {
                            builder.beginSource(source);
                            builder.beginSourceSection(bodyStart, bodyLength);
                            builder.beginRoot();

                            BytecodeLocal x = builder.createLocal("x", null);
                            BytecodeLocal y = builder.createLocal("y", null);
                            builder.beginBlock();

                            builder.beginSourceSection(xStart, "x: 1".length());
                            builder.beginTag(StandardTags.StatementTag.class, StandardTags.ExpressionTag.class);
                            builder.beginBlock();
                            builder.beginStoreLocal(x);
                            builder.emitLoadConstant(1L);
                            builder.endStoreLocal();
                            builder.endBlock();
                            builder.endTag(StandardTags.StatementTag.class, StandardTags.ExpressionTag.class);
                            builder.endSourceSection();

                            builder.beginSourceSection(yStart, "y: x + 1".length());
                            builder.beginTag(StandardTags.StatementTag.class, StandardTags.ExpressionTag.class);
                            builder.beginBlock();
                            builder.beginStoreLocal(y);
                            builder.emitLoadConstant(2L);
                            builder.endStoreLocal();
                            builder.endBlock();
                            builder.endTag(StandardTags.StatementTag.class, StandardTags.ExpressionTag.class);
                            builder.endSourceSection();

                            builder.beginSourceSection(terminalStart, 1);
                            builder.beginTag(StandardTags.StatementTag.class, StandardTags.ExpressionTag.class);
                            builder.beginBlock();
                            builder.beginReturn();
                            builder.emitLoadLocal(y);
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

    private static ProtosBytecodeRootNode handwrittenLocalPlusLocal() {
        String code = TWO_LOCALS;
        Source source = source(code, "local-plus-local-oracle");
        int bodyStart = code.indexOf('{');
        int bodyLength = code.lastIndexOf('}') + 1 - bodyStart;
        int xStart = code.indexOf("x: 1");
        int yStart = code.indexOf("y: 2");
        int zStart = code.indexOf("z: x + y");
        int terminalStart = code.lastIndexOf('z');
        BytecodeRootNodes<ProtosBytecodeRootNode> nodes =
                ProtosBytecodeRootNodeGen.create(
                        LANGUAGE.get(null), BytecodeConfig.DEFAULT,
                        builder -> {
                            builder.beginSource(source);
                            builder.beginSourceSection(bodyStart, bodyLength);
                            builder.beginRoot();

                            BytecodeLocal x = builder.createLocal("x", null);
                            BytecodeLocal y = builder.createLocal("y", null);
                            BytecodeLocal z = builder.createLocal("z", null);
                            builder.beginBlock();

                            builder.beginSourceSection(xStart, "x: 1".length());
                            builder.beginTag(StandardTags.StatementTag.class, StandardTags.ExpressionTag.class);
                            builder.beginBlock();
                            builder.beginStoreLocal(x);
                            builder.emitLoadConstant(1L);
                            builder.endStoreLocal();
                            builder.endBlock();
                            builder.endTag(StandardTags.StatementTag.class, StandardTags.ExpressionTag.class);
                            builder.endSourceSection();

                            builder.beginSourceSection(yStart, "y: 2".length());
                            builder.beginTag(StandardTags.StatementTag.class, StandardTags.ExpressionTag.class);
                            builder.beginBlock();
                            builder.beginStoreLocal(y);
                            builder.emitLoadConstant(2L);
                            builder.endStoreLocal();
                            builder.endBlock();
                            builder.endTag(StandardTags.StatementTag.class, StandardTags.ExpressionTag.class);
                            builder.endSourceSection();

                            builder.beginSourceSection(zStart, "z: x + y".length());
                            builder.beginTag(StandardTags.StatementTag.class, StandardTags.ExpressionTag.class);
                            builder.beginBlock();
                            builder.beginStoreLocal(z);
                            builder.emitLoadConstant(3L);
                            builder.endStoreLocal();
                            builder.endBlock();
                            builder.endTag(StandardTags.StatementTag.class, StandardTags.ExpressionTag.class);
                            builder.endSourceSection();

                            builder.beginSourceSection(terminalStart, 1);
                            builder.beginTag(StandardTags.StatementTag.class, StandardTags.ExpressionTag.class);
                            builder.beginBlock();
                            builder.beginReturn();
                            builder.emitLoadLocal(z);
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

    private static Source source(String code, String label) {
        return Source.newBuilder(ProtosLanguage.ID, code,
                "i101-b-" + label + ".protos").build();
    }

    private static ProtosBytecodeRootNode parse(String code, String label) {
        RootCallTarget callTarget = assertInstanceOf(RootCallTarget.class,
                ProtosLanguageContext.current().parsePublic(source(code, label)));
        return assertInstanceOf(ProtosBytecodeRootNode.class,
                callTarget.getRootNode());
    }

    private static List<ProtosBytecodeRootNode> roots(
            ProtosBytecodeRootNode root) {
        return root.getRootNodes().getNodes().stream()
                .map(ProtosBytecodeRootNode.class::cast).toList();
    }

    private static List<String> completeSignature(ProtosBytecodeRootNode root) {
        root.getRootNodes().ensureComplete();
        List<String> result = new ArrayList<>();
        for (Instruction instruction : root.getBytecodeNode().getInstructionsAsList()) {
            StringBuilder s = new StringBuilder(instruction.getName());
            for (Instruction.Argument arg : instruction.getArguments()) {
                s.append(' ').append(arg.getName()).append(':').append(arg.getKind())
                        .append('=').append(operand(arg));
            }
            result.add(s.toString());
        }
        return result;
    }

    private static String operand(Instruction.Argument argument) {
        return switch (argument.getKind()) {
            case CONSTANT -> constant(argument.asConstant());
            case BYTECODE_INDEX -> Integer.toString(argument.asBytecodeIndex());
            case INTEGER -> Integer.toString(argument.asInteger());
            case LOCAL_OFFSET -> Integer.toString(argument.asLocalOffset());
            case LOCAL_INDEX -> Integer.toString(argument.asLocalIndex());
            case TAG_NODE -> argument.asTagNode() == null ? "no-tag-node"
                    : argument.asTagNode().getTags().stream()
                            .map(Class::getSimpleName).sorted().toList().toString();
            case NODE_PROFILE, BRANCH_PROFILE -> "profile";
        };
    }

    private static String constant(Object value) {
        if (value == null) {
            return "host-null";
        }
        if (value == ProtosNullValue.INSTANCE) {
            return "Protos(null)";
        }
        if (value instanceof Double number) {
            return "Double(bits=" + Long.toHexString(Double.doubleToRawLongBits(number)) + ")";
        }
        if (value instanceof Float number) {
            return "Float(bits=" + Integer.toHexString(Float.floatToRawIntBits(number)) + ")";
        }
        if (value instanceof Number || value instanceof Boolean
                || value instanceof Character || value instanceof String) {
            return value.getClass().getSimpleName() + "(" + value + ")";
        }
        if (value instanceof Class<?> type) {
            return "Class(" + type.getName() + ")";
        }
        return "Metadata(" + value.getClass().getName() + ")";
    }
}
