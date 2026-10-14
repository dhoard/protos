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

import com.guillermomolina.protos.runtime.ProtosClosureValue;
import com.guillermomolina.protos.runtime.ProtosNullValue;
import com.guillermomolina.protos.runtime.ProtosNumericValueSupport;
import com.guillermomolina.protos.runtime.ProtosPrelude;
import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.TruffleLanguage.LanguageReference;
import com.oracle.truffle.api.bytecode.BytecodeConfig;
import com.oracle.truffle.api.bytecode.BytecodeRootNodes;
import com.oracle.truffle.api.bytecode.Instruction;
import com.oracle.truffle.api.instrumentation.StandardTags;
import com.oracle.truffle.api.source.Source;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;

/**
 * I101-B: the smallest ordinary Protos Closure body.
 *
 * No Boolean dispatch, arithmetic, generic constant propagation, or other
 * optimization families are tested here.
 *
 * This test validates emitted Bytecode DSL instructions, not Graal machine
 * code. Every physical instruction is retained in its diagnostic output.
 */
final class ProtosI101BLiteralOneTest {

    private static final Path CORE = Path.of("protos", "lib", "core");
    private static final String PROGRAM = "() => { 1 }";

    private static final LanguageReference<ProtosLanguage> LANGUAGE =
            LanguageReference.create(ProtosLanguage.class);

    @Test
    void literalOneMatchesIndependentMinimalBuilder() throws Exception {

        try (Context context = Context.newBuilder(ProtosLanguage.ID).build()) {
            context.initialize(ProtosLanguage.ID);
            context.enter();

            try {
                ProtosPrelude prelude =
                        new ProtosCoreBootstrap().bootstrap(CORE);

                assertEquals(
                        ProtosExecutionMode.NORMAL,
                        ProtosLanguageContext.current().executionMode());

                ProtosSemanticBytecodeRootNode oracle =
                        handwrittenLiteralOne();

                Source source = Source.newBuilder(
                        ProtosLanguage.ID,
                        PROGRAM,
                        "i101-b-literal-one.protos").build();

                RootCallTarget target = assertInstanceOf(
                        RootCallTarget.class,
                        ProtosLanguageContext.current().parsePublic(source));

                ProtosSemanticBytecodeRootNode module =
                        assertInstanceOf(
                                ProtosSemanticBytecodeRootNode.class,
                                target.getRootNode());

                ProtosClosureValue closure = assertInstanceOf(
                        ProtosClosureValue.class,
                        module.getCallTarget().call(
                                prelude.newModuleActivation()));

                ProtosSemanticBytecodeRootNode actual =
                        closure.executionPlan()
                                .orElseThrow()
                                .bytecodeActivationRootForTesting();

                List<ProtosSemanticBytecodeRootNode> roots =
                        module.getRootNodes().getNodes().stream()
                                .map(ProtosSemanticBytecodeRootNode.class::cast)
                                .toList();

                // Module, ordinary Closure and separate arity rejection.
                assertEquals(3, roots.size());
                assertSame(roots.get(1), actual);
                assertSame(
                        roots.get(2).getCallTarget(),
                        actual.arityRejectionTarget());

                printInstructions(
                        "I101_B_LITERAL1_BEFORE_COMPLETE",
                        signature(actual));

                List<String> expected = completeSignature(oracle);
                List<String> emitted = completeSignature(actual);

                printInstructions("I101_B_LITERAL1_ORACLE", expected);
                printInstructions("I101_B_LITERAL1_ACTUAL", emitted);

                assertEquals(
                        expected,
                        emitted,
                        "The complete executable root differs from "
                                + "the independent minimal Builder oracle");

                assertTrue(
                        expected.stream().anyMatch(
                                line -> line.contains("Long(1)")),
                        "The oracle must contain the literal-one carrier");

                assertTrue(actual.provablyNonSuspendingBody());
                assertTrue(actual.headerlessEntryBody());

                // No generic execution machinery in the literal root.
                // Instrumentation instructions are printed, not filtered.
                for (String entry : emitted) {
                    String name = entry.split(" ", 2)[0];

                    for (String forbidden : List.of(
                            "IsNormalExecutionMode",
                            "PrepareSend",
                            "PrepareClosureCall",
                            "TryDirectCall",
                            "Capture",
                            "Dispatch",
                            "InstallFrameLexicalAuthority",
                            "CheckFrame",
                            "Throw",
                            "Exception",
                            "load.local",
                            "store.local",
                            "branch")) {
                        assertFalse(
                                name.contains(forbidden),
                                () -> "Unexpected operation: " + entry);
                    }
                }

                // Semantic correctness is independent of the Builder oracle.
                var invocation = prelude.newModuleActivation();
                invocation.context().createLocalSlot("candidate", closure);

                Source callSource = Source.newBuilder(
                        ProtosLanguage.ID,
                        "candidate()",
                        "i101-b-literal-one-call.protos").build();

                Object result = ProtosLanguageContext.current()
                        .parsePublic(callSource)
                        .call(invocation);

                assertEquals(
                        "1",
                        ProtosNumericValueSupport.integerDecimalText(result));

                System.out.println("I101_B_LITERAL1_ORACLE=PASS");
                System.out.println("I101_B_LITERAL1_TOPOLOGY=PASS");
                System.out.println("I101_B_LITERAL1_SEMANTICS=PASS");

            } finally {
                context.leave();
            }
        }
    }

    /**
     * Independent handwritten Builder oracle.
     *
     * The separate arity-rejection root belongs to the group but cannot
     * introduce checks into the zero-argument executable activation root.
     */
    /**
     * Independent absolute minimum: a root returning one constant.
     *
     * Source metadata only; no tags, blocks, argument checks,
     * numeric materialization, dispatch or fallback.
     *
     * This is the target for physical-emission analysis, not
     * a copy of the current Protos lowering.
     */
    private static ProtosSemanticBytecodeRootNode handwrittenLiteralOne() {

        Source source = Source.newBuilder(
                ProtosLanguage.ID,
                PROGRAM,
                "i101-b-manual-oracle.protos").build();

        int bodyStart = PROGRAM.indexOf('{');
        int bodyLength = PROGRAM.lastIndexOf('}') + 1 - bodyStart;

        BytecodeRootNodes<ProtosSemanticBytecodeRootNode> nodes =
                ProtosSemanticBytecodeRootNodeGen.create(
                        LANGUAGE.get(null),
                        BytecodeConfig.DEFAULT,
                        builder -> {
                            builder.beginSource(source);
                            builder.beginSourceSection(
                                    bodyStart, bodyLength);

                            builder.beginRoot();

                            builder.beginReturn();
                            builder.emitLoadConstant(1L);
                            builder.endReturn();

                            builder.endRoot();

                            builder.endSourceSection();
                            builder.endSource();
                        });

        return nodes.getNode(0);
    }

    private static List<String> completeSignature(
            ProtosSemanticBytecodeRootNode root) {
        root.getRootNodes().ensureComplete();
        return signature(root);
    }

    /**
     * Full instruction stream, including every argument kind, tags,
     * profiles and physical bytecode indices. Nothing is filtered out.
     */
    private static List<String> signature(
            ProtosSemanticBytecodeRootNode root) {

        List<String> lines = new ArrayList<>();

        for (Instruction instruction :
                root.getBytecodeNode().getInstructionsAsList()) {

            StringBuilder line =
                    new StringBuilder(instruction.getName());

            for (Instruction.Argument argument :
                    instruction.getArguments()) {
                line.append(' ')
                        .append(argument.getName())
                        .append(':')
                        .append(argument.getKind())
                        .append('=')
                        .append(operand(argument));
            }

            lines.add(line.toString());
        }

        return lines;
    }

    private static String operand(Instruction.Argument argument) {

        return switch (argument.getKind()) {
            case CONSTANT -> constant(argument.asConstant());
            case BYTECODE_INDEX ->
                    Integer.toString(argument.asBytecodeIndex());
            case INTEGER ->
                    Integer.toString(argument.asInteger());
            case LOCAL_OFFSET ->
                    Integer.toString(argument.asLocalOffset());
            case LOCAL_INDEX ->
                    Integer.toString(argument.asLocalIndex());
            case TAG_NODE ->
                    argument.asTagNode() == null
                            ? "no-tag-node"
                            : argument.asTagNode().getTags().stream()
                                    .map(Class::getSimpleName)
                                    .sorted()
                                    .toList().toString();
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
            return "Double(bits="
                    + Long.toHexString(
                            Double.doubleToRawLongBits(number))
                    + ")";
        }
        if (value instanceof Long
                || value instanceof Integer
                || value instanceof String
                || value instanceof Boolean) {
            return value.getClass().getSimpleName()
                    + "(" + value + ")";
        }

        return "Metadata(" + value.getClass().getName() + ")";
    }

    private static void printInstructions(
            String prefix, List<String> instructions) {

        System.out.println(prefix + "_COUNT=" + instructions.size());

        for (int index = 0; index < instructions.size(); index++) {
            System.out.println(
                    prefix + "[" + index + "]=" + instructions.get(index));
        }
    }
}
