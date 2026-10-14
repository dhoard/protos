/*
 * THE LICENSED WORK IS PROVIDED UNDER THE TERMS OF THE ADAPTIVE PUBLIC LICENSE
 * ("LICENSE") AS FIRST COMPLETED BY: Guillermo Adrián Molina. ANY USE, PUBLIC
 * DISPLAY, PUBLIC PERFORMANCE, REPRODUCTION OR DISTRIBUTION OF, OR PREPARATION OF
 * DERIVATIVE WORKS BASED ON, THE LICENSED WORK CONSTITUTES RECIPIENT'S ACCEPTANCE
 * OF THIS LICENSE AND ITS TERMS, WHETHER OR NOT SUCH RECIPIENT READS THE TERMS
 * OF THE LICENSE. "LICENSED WORK" AND "RECIPIENT" ARE DEFINED IN THE LICENSE.
 * A COPY OF THE LICENSE IS LOCATED IN THE TEXT FILE ENTITLED "LICENSE.TXT"
 * ACCOMPANYING THE CONTENTS OF THIS FILE. IF A COPY OF THE LICENSE DOES NOT
 * ACCOMPANY THE CONTENTS OF THIS FILE, A COPY OF THE LICENSE MAY ALSO BE
 * OBTAINED AT THE FOLLOWING WEB SITE:
 * https://github.com/guillermomolina/protos
 *
 * Software distributed under the License is distributed on an "AS IS" basis,
 * WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
 * for the specific language governing rights and limitations under the License.
 */
package com.guillermomolina.protos.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.guillermomolina.protos.runtime.ProtosActivation;
import com.guillermomolina.protos.runtime.ProtosClosureValue;
import com.guillermomolina.protos.runtime.ProtosIntegerValue;
import com.guillermomolina.protos.runtime.ProtosNullValue;
import com.guillermomolina.protos.runtime.ProtosNumericValueSupport;
import com.guillermomolina.protos.runtime.ProtosPrelude;
import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.TruffleLanguage.LanguageReference;
import com.oracle.truffle.api.bytecode.BytecodeConfig;
import com.oracle.truffle.api.bytecode.BytecodeLocal;
import com.oracle.truffle.api.bytecode.BytecodeRootNodes;
import com.oracle.truffle.api.bytecode.Instruction;
import com.oracle.truffle.api.source.Source;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;

/**
 * I101-B: minimal, handwritten Bytecode DSL reference for one local bound to 1,
 * with or without a subsequent read. The reference uses precisely one root,
 * one BytecodeLocal, one StoreLocal, and a Return/LoadLocal. It retains source
 * and precise expression source sections, without copying any Protos lowering
 * machinery, instrumentation tags, guards, fallbacks or frame authorities.
 *
 * <p>The production Closure and its arity-rejection root are retained for an
 * independent full-signature comparison. Bytecode equality is observed and
 * reported, <em>not</em> asserted: Protos has additional dynamic semantics, so
 * equivalence to the minimal DSL reference is an investigation question.
 *
 * <p>This is a pre-Graal lowering diagnostic, not a Tier 1/2 graph comparison.
 */
final class ProtosI101BLocalBindingBuilderOracleTest {
    private static final Path CORE = Path.of("protos", "lib", "core");
    private static final LanguageReference<ProtosLanguage> LANGUAGE_REF =
            LanguageReference.create(ProtosLanguage.class);

    private static final String CREATE_ONLY = "() => { x: 1 }";
    private static final String CREATE_AND_READ = "() => { x: 1; x }";

    // I101_B_MINIMAL_BUILDER_COMPARISON: observational, not equality-gated.
    @Test
    void localCreationComparedWithMinimalBuilder() throws Exception {
        compareWithMinimalBuilder(CREATE_ONLY, "CREATE_ONLY",
                ProtosI101BLocalBindingBuilderOracleTest::handwrittenCreateOnlyBuilder);
    }

    @Test
    void localCreationAndReadComparedWithMinimalBuilder() throws Exception {
        compareWithMinimalBuilder(CREATE_AND_READ, "CREATE_AND_READ",
                ProtosI101BLocalBindingBuilderOracleTest::handwrittenCreateAndReadBuilder);
    }

    private static void compareWithMinimalBuilder(
            String program, String caseName,
            Supplier<ProtosSemanticBytecodeRootNode> independentBuilder)
            throws Exception {
        try (Context context = Context.newBuilder(ProtosLanguage.ID).build()) {
            context.initialize(ProtosLanguage.ID);
            context.enter();
            try {
                assertEquals(ProtosExecutionMode.NORMAL,
                        ProtosLanguageContext.current().executionMode());
                ProtosPrelude prelude = new ProtosCoreBootstrap().bootstrap(CORE);

                // Observe the real Protos lowering and its real source-Closure topology.
                ProtosSemanticBytecodeRootNode module = parse(program, caseName);
                List<ProtosSemanticBytecodeRootNode> actualRoots = roots(module);
                assertEquals(3, actualRoots.size(),
                        "production module, Closure activation, arity rejection");
                assertSame(module, actualRoots.get(0));
                ProtosSemanticBytecodeRootNode actual = actualRoots.get(1);
                assertSame(actualRoots.get(2).getCallTarget(),
                        actual.arityRejectionTarget());

                // Independent reference: there must be *no* Protos arity root,
                // layout, activation, guards, tags or fallback inside this builder.
                ProtosSemanticBytecodeRootNode minimal = independentBuilder.get();
                assertEquals(1, roots(minimal).size(),
                        "minimal Bytecode DSL reference has exactly one root");

                List<String> expected = completeSignature(minimal);
                List<String> actualSignature = completeSignature(actual);
                assertTrue(expected.stream().anyMatch(s -> s.startsWith("store.local")),
                        () -> "minimal builder has no local store: " + expected);
                assertTrue(expected.stream().anyMatch(s -> s.startsWith("load.local")),
                        () -> "minimal builder has no local read: " + expected);
                assertTrue(expected.stream().noneMatch(s ->
                                s.contains("IsCompactLocalFrame")
                                        || s.contains("CreateCurrentFrameLocal")
                                        || s.contains("ReadRootFrameLocal")),
                        () -> "minimal builder acquired Protos-specific operations: " + expected);

                System.out.println("I101_B_" + caseName + "_MINIMAL_BUILDER=" + expected);
                System.out.println("I101_B_" + caseName + "_PROTOS_LOWERER=" + actualSignature);
                System.out.println("I101_B_" + caseName + "_MINIMAL_INSTRUCTIONS="
                        + expected.size());
                System.out.println("I101_B_" + caseName + "_PROTOS_INSTRUCTIONS="
                        + actualSignature.size());
                System.out.println("I101_B_" + caseName + "_BYTECODE_EQUAL="
                        + expected.equals(actualSignature));
                assertEquals(expected, actualSignature,
                        "Protos must exactly reproduce the handwritten "
                                + "local-binding Builder");

                // Prove the observable guest Integer through the real source
                // call, including I091 completion at the caller boundary.
                ProtosClosureValue closure = assertInstanceOf(
                        ProtosClosureValue.class,
                        module.getCallTarget().call(prelude.newModuleActivation()));
                ProtosClosureExecutionPlan plan = closure.executionPlan().orElseThrow();
                assertSame(actual, plan.bytecodeActivationRootForTesting());
                assertSame(actual.getCallTarget(),
                        plan.bytecodeActivationTargetForComposition());

                ProtosActivation invocationModule = prelude.newModuleActivation();
                invocationModule.context().createLocalSlot("candidate", closure);
                Object result = parse("candidate()", caseName + "-invoke")
                        .getCallTarget()
                        .call(invocationModule);
                assertEquals("1", ProtosNumericValueSupport.integerDecimalText(result),
                        "Protos local creation and final lookup must both return 1");
                System.out.println("I101_B_" + caseName + "_RESULT=1");
                System.out.println("I101_B_" + caseName + "=PASS");
            } finally {
                context.leave();
            }
        }
    }

    private static ProtosSemanticBytecodeRootNode parse(String program, String name) {
        Source source = Source.newBuilder(
                        ProtosLanguage.ID, program, "i101-b-" + name + ".protos")
                .build();
        RootCallTarget callTarget = assertInstanceOf(
                RootCallTarget.class, ProtosLanguageContext.current().parsePublic(source));
        return assertInstanceOf(
                ProtosSemanticBytecodeRootNode.class, callTarget.getRootNode());
    }

    /**
     * Minimal Bytecode DSL reference for { x: 1 }.
     * The StoreLocal initializes x; Return/LoadLocal yields its value.
     * Source and expression spans come directly from this case's own source.
     */
    private static ProtosSemanticBytecodeRootNode handwrittenCreateOnlyBuilder() {
        String code = CREATE_ONLY;
        Source source = Source.newBuilder(
                        ProtosLanguage.ID, code, "i101-b-create-only-oracle.protos")
                .build();
        int bodyStart = code.indexOf('{');
        int bodyLength = code.lastIndexOf('}') - bodyStart + 1;
        int creationStart = code.indexOf("x: 1");

        BytecodeRootNodes<ProtosSemanticBytecodeRootNode> nodes =
                ProtosSemanticBytecodeRootNodeGen.create(
                        LANGUAGE_REF.get(null),
                        BytecodeConfig.DEFAULT,
                        builder -> {
                            builder.beginSource(source);
                            builder.beginSourceSection(bodyStart, bodyLength);
                            builder.beginRoot();

                            BytecodeLocal x = builder.createLocal("x", null);

                            builder.beginSourceSection(creationStart, "x: 1".length());
                            builder.beginStoreLocal(x);
                            builder.emitLoadConstant(1L);
                            builder.endStoreLocal();
                            builder.beginReturn();
                            builder.emitLoadLocal(x);
                            builder.endReturn();
                            builder.endSourceSection();

                            builder.endRoot();
                            builder.endSourceSection();
                            builder.endSource();
                        });
        return nodes.getNode(0);
    }

    /**
     * Minimal Bytecode DSL reference for { x: 1; x }.
     * Creation and terminal read have distinct source sections.
     * No Protos guards, fallbacks, activation, layout, or auxiliary roots.
     */
    private static ProtosSemanticBytecodeRootNode handwrittenCreateAndReadBuilder() {
        String code = CREATE_AND_READ;
        Source source = Source.newBuilder(
                        ProtosLanguage.ID, code, "i101-b-create-and-read-oracle.protos")
                .build();
        int bodyStart = code.indexOf('{');
        int bodyLength = code.lastIndexOf('}') - bodyStart + 1;
        int creationStart = code.indexOf("x: 1");
        int readStart = code.lastIndexOf('x');

        BytecodeRootNodes<ProtosSemanticBytecodeRootNode> nodes =
                ProtosSemanticBytecodeRootNodeGen.create(
                        LANGUAGE_REF.get(null),
                        BytecodeConfig.DEFAULT,
                        builder -> {
                            builder.beginSource(source);
                            builder.beginSourceSection(bodyStart, bodyLength);
                            builder.beginRoot();

                            BytecodeLocal x = builder.createLocal("x", null);

                            builder.beginSourceSection(creationStart, "x: 1".length());
                            builder.beginStoreLocal(x);
                            builder.emitLoadConstant(1L);
                            builder.endStoreLocal();
                            builder.endSourceSection();

                            builder.beginSourceSection(readStart, 1);
                            builder.beginReturn();
                            builder.emitLoadLocal(x);
                            builder.endReturn();
                            builder.endSourceSection();

                            builder.endRoot();
                            builder.endSourceSection();
                            builder.endSource();
                        });
        return nodes.getNode(0);
    }

    private static List<ProtosSemanticBytecodeRootNode> roots(
            ProtosSemanticBytecodeRootNode root) {
        return root.getRootNodes().getNodes().stream()
                .map(ProtosSemanticBytecodeRootNode.class::cast)
                .toList();
    }

    private static List<String> completeSignature(ProtosSemanticBytecodeRootNode root) {
        root.getRootNodes().ensureComplete();
        List<String> entries = new ArrayList<>();
        for (Instruction instruction : root.getBytecodeNode().getInstructionsAsList()) {
            StringBuilder value = new StringBuilder(instruction.getName());
            for (Instruction.Argument argument : instruction.getArguments()) {
                value.append(' ')
                        .append(argument.getName())
                        .append(':')
                        .append(argument.getKind())
                        .append('=')
                        .append(operand(argument));
            }
            entries.add(value.toString());
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
            case TAG_NODE -> argument.asTagNode() == null
                    ? "no-tag-node"
                    : argument.asTagNode().getTags().stream()
                            .map(Class::getSimpleName).sorted().toList().toString();
            case NODE_PROFILE, BRANCH_PROFILE -> "profile";
        };
    }

    /** Treat identity-only runtime metadata as metadata, never hide guest values. */
    private static String constant(Object constant) {
        if (constant == null) {
            return "host-null";
        }
        if (constant instanceof ProtosIntegerValue integer) {
            return "ProtosInteger(" + integer.longValue() + ")";
        }
        if (constant instanceof ProtosFrameLexicalLayout layout) {
            List<String> namesAndOffsets = new ArrayList<>();
            for (int index = 0; index < layout.length(); index++) {
                namesAndOffsets.add(layout.nameAt(index) + ":" + layout.localOffsetAt(index));
            }
            return "FrameLayout(" + namesAndOffsets + ")";
        }
        if (constant instanceof Double value) {
            return "Double(bits=" + Long.toHexString(Double.doubleToRawLongBits(value)) + ")";
        }
        if (constant instanceof Float value) {
            return "Float(bits=" + Integer.toHexString(Float.floatToRawIntBits(value)) + ")";
        }
        if (constant instanceof Number || constant instanceof Boolean
                || constant instanceof Character || constant instanceof String) {
            return constant.getClass().getSimpleName() + "(" + constant + ")";
        }
        if (constant == ProtosNullValue.INSTANCE) {
            return "Protos(null)";
        }
        if (constant instanceof Class<?> type) {
            return "Class(" + type.getName() + ")";
        }
        // Branch and continuity assumptions / implementation-owned holders
        // have run-specific identities; the type must still match.
        return "Metadata(" + constant.getClass().getName() + ")";
    }
}
