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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.guillermomolina.protos.runtime.ProtosActivation;
import com.guillermomolina.protos.runtime.ProtosClosureValue;
import com.guillermomolina.protos.runtime.ProtosBooleanValue;

import com.guillermomolina.protos.runtime.ProtosNullValue;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import com.oracle.truffle.api.bytecode.Instruction;
import org.junit.jupiter.api.Test;

/** I092 canonical Boolean direct path and ordinary fallback regressions. */
final class ProtosI092CompactBooleanTest {
    private static final Path CORE = Path.of("protos", "lib", "core");

    @Test
    void trueRunsSelectedInlineLiteral() throws Exception {
        assertInteger(42, evaluate("""
                run: () => {
                    true.ifTrue(() => { 42 })
                }
                run()
                """));
    }

    @Test
    void constantBooleanControlHasDirectNormalBranch()
            throws Exception {
        for (String booleanLiteral : List.of("true", "false")) {
            ProtosActivation module =
                    new ProtosCoreBootstrap()
                            .bootstrap(CORE)
                            .newModuleActivation();

            ProtosClosureValue run = assertInstanceOf(
                    ProtosClosureValue.class,
                    ProtosTestExecutionSupport.evaluate(
                            "i095-constant-control.protos",
                            "run: () => { "
                                    + booleanLiteral
                                    + ".ifTrue(() => { 41 }) }"
                                    + System.lineSeparator()
                                    + "run",
                            module));

            var root = run.executionPlan().orElseThrow()
                    .bytecodeActivationRootForTesting();
            root.getRootNodes().ensureComplete();

            List<String> names = root.getBytecodeNode()
                    .getInstructionsAsList()
                    .stream()
                    .map(Instruction::getName)
                    .toList();

            int mode = position(names, "IsNormalExecutionMode");
            int generic = position(names, "PrepareSendOne");

            assertTrue(mode >= 0, names.toString());
            assertTrue(generic > mode, names.toString());

            // The DEBUG alternative retains the established
            // canonical/generic protocol and its callback.
            assertTrue(
                    position(names, "IsCompactCanonicalBooleanMiss")
                            > mode,
                    names.toString());

            Object value = ProtosTestExecutionSupport.evaluate(
                    "i095-constant-control-run.protos",
                    "run()",
                    module);

            if ("true".equals(booleanLiteral)) {
                assertInteger(41, value);
            } else {
                assertSame(ProtosNullValue.INSTANCE, value);
            }
        }
    }

    @Test
    void normalConstantTrueFoldsPureLiteralSequence()
            throws Exception {
        ProtosActivation module =
                new ProtosCoreBootstrap()
                        .bootstrap(CORE)
                        .newModuleActivation();

        ProtosClosureValue run = assertInstanceOf(
                ProtosClosureValue.class,
                ProtosTestExecutionSupport.evaluate(
                        "i095-pure-sequence.protos",
                        """
                        run: () => {
                            true.ifTrue(() => {
                                3
                                false
                                41
                            })
                        }
                        run
                        """,
                        module));

        var root = run.executionPlan().orElseThrow()
                .bytecodeActivationRootForTesting();
        root.getRootNodes().ensureComplete();

        var names = root.getBytecodeNode()
                .getInstructionsAsList()
                .stream()
                .map(Instruction::getName)
                .toList();

        assertTrue(
                position(names, "IsNormalExecutionMode") >= 0,
                names.toString());

        assertInteger(
                41,
                ProtosTestExecutionSupport.evaluate(
                        "i095-pure-sequence-run.protos",
                        "run()",
                        module));
    }

    @Test
    void normalConstantFalseDiscardsUnselectedParameterizedClosure()
            throws Exception {
        ProtosActivation module =
                new ProtosCoreBootstrap()
                        .bootstrap(CORE)
                        .newModuleActivation();

        ProtosClosureValue run = assertInstanceOf(
                ProtosClosureValue.class,
                ProtosTestExecutionSupport.evaluate(
                        "i095-unselected-closure.protos",
                        """
                        run: () => {
                            false.ifTrue((ignored) => {
                                ^99
                            })
                        }
                        run
                        """,
                        module));

        var root = run.executionPlan().orElseThrow()
                .bytecodeActivationRootForTesting();
        root.getRootNodes().ensureComplete();

        var names = root.getBytecodeNode()
                .getInstructionsAsList()
                .stream()
                .map(Instruction::getName)
                .toList();

        assertTrue(
                position(names, "IsNormalExecutionMode") >= 0,
                names.toString());

        assertSame(
                ProtosNullValue.INSTANCE,
                ProtosTestExecutionSupport.evaluate(
                        "i095-unselected-closure-run.protos",
                        "run()",
                        module));
    }

    @Test
    void normalConstantTrueInlinesPrimitiveIdentityTree()
            throws Exception {
        ProtosActivation module =
                new ProtosCoreBootstrap()
                        .bootstrap(CORE)
                        .newModuleActivation();

        ProtosClosureValue run = assertInstanceOf(
                ProtosClosureValue.class,
                ProtosTestExecutionSupport.evaluate(
                        "i095-identity-tree.protos",
                        """
                        run: () => {
                            true.ifTrue(() => {
                                true === false
                                (true === false) !== true
                            })
                        }
                        run
                        """,
                        module));

        var root = run.executionPlan().orElseThrow()
                .bytecodeActivationRootForTesting();
        root.getRootNodes().ensureComplete();

        var names = root.getBytecodeNode()
                .getInstructionsAsList()
                .stream()
                .map(Instruction::getName)
                .toList();

        assertTrue(
                position(names, "IsNormalExecutionMode") >= 0,
                names.toString());

        assertTrue(
                position(names, "Identity") >= 0,
                names.toString());

        assertTrue(
                position(names, "NotIdentity") >= 0,
                names.toString());

        assertSame(
                ProtosBooleanValue.TRUE,
                ProtosTestExecutionSupport.evaluate(
                        "i095-identity-tree-run.protos",
                        "run()",
                        module));
    }

    @Test
    void ordinaryArithmeticInsideIfTrueUsesWholeBlockInline()
            throws Exception {
        ProtosActivation module =
                new ProtosCoreBootstrap()
                        .bootstrap(CORE)
                        .newModuleActivation();

        ProtosClosureValue run = assertInstanceOf(
                ProtosClosureValue.class,
                ProtosTestExecutionSupport.evaluate(
                        "i095-ordinary-arithmetic.protos",
                        """
                        run: () => {
                            true.ifTrue(() => { 1 + 2 })
                        }
                        run
                        """,
                        module));

        var root = run.executionPlan().orElseThrow()
                .bytecodeActivationRootForTesting();
        root.getRootNodes().ensureComplete();

        var names = root.getBytecodeNode()
                .getInstructionsAsList()
                .stream()
                .map(Instruction::getName)
                .toList();

        assertTrue(
                position(names, "IsNormalExecutionMode") >= 0,
                names.toString());

        assertInteger(
                3,
                ProtosTestExecutionSupport.evaluate(
                        "i095-ordinary-arithmetic-run.protos",
                        "run()",
                        module));
    }

    @Test
    void normalTrueInlinesWholeOrdinaryArithmeticSequence()
            throws Exception {
        ProtosActivation module =
                new ProtosCoreBootstrap()
                        .bootstrap(CORE)
                        .newModuleActivation();

        ProtosClosureValue run = assertInstanceOf(
                ProtosClosureValue.class,
                ProtosTestExecutionSupport.evaluate(
                        "i095-whole-arithmetic.protos",
                        """
                        run: () => {
                            true.ifTrue(() => {
                                1 + 2
                                3 * 4
                            })
                        }
                        run
                        """,
                        module));

        var root = run.executionPlan().orElseThrow()
                .bytecodeActivationRootForTesting();
        root.getRootNodes().ensureComplete();

        var names = root.getBytecodeNode()
                .getInstructionsAsList()
                .stream()
                .map(Instruction::getName)
                .toList();

        assertTrue(
                position(names, "IsNormalExecutionMode") >= 0,
                names.toString());

        assertTrue(
                position(names, "TryPrimitiveNumericSendOne") >= 0,
                names.toString());

        assertInteger(
                12,
                ProtosTestExecutionSupport.evaluate(
                        "i095-whole-arithmetic-run.protos",
                        "run()",
                        module));
    }

    @Test
    void capturedBindingUsesDirectParentInlineRead()
            throws Exception {
        ProtosActivation module =
                new ProtosCoreBootstrap()
                        .bootstrap(CORE)
                        .newModuleActivation();

        ProtosClosureValue run = assertInstanceOf(
                ProtosClosureValue.class,
                ProtosTestExecutionSupport.evaluate(
                        "i095-captured-fallback.protos",
                        """
                        run: () => {
                            value: 8
                            true.ifTrue(() => {
                                value + 1
                            })
                        }
                        run
                        """,
                        module));

        var root = run.executionPlan().orElseThrow()
                .bytecodeActivationRootForTesting();
        root.getRootNodes().ensureComplete();

        var names = root.getBytecodeNode()
                .getInstructionsAsList()
                .stream()
                .map(Instruction::getName)
                .toList();

        assertTrue(
                position(names, "IsNormalExecutionMode") >= 0,
                names.toString());

        assertInteger(
                9,
                ProtosTestExecutionSupport.evaluate(
                        "i095-captured-fallback-run.protos",
                        "run()",
                        module));
    }

    @Test
    void normalTrueInlinesReadOnlyParentCapture() throws Exception {
        ProtosActivation module =
                new ProtosCoreBootstrap()
                        .bootstrap(CORE)
                        .newModuleActivation();

        ProtosClosureValue run = assertInstanceOf(
                ProtosClosureValue.class,
                ProtosTestExecutionSupport.evaluate(
                        "i095-b4-read-capture.protos",
                        """
                        run: () => {
                            outer: 41
                            true.ifTrue(() => {
                                outer + 1
                            })
                        }
                        run
                        """,
                        module));

        var root = run.executionPlan().orElseThrow()
                .bytecodeActivationRootForTesting();
        root.getRootNodes().ensureComplete();

        var names = root.getBytecodeNode()
                .getInstructionsAsList()
                .stream()
                .map(Instruction::getName)
                .toList();

        assertTrue(
                position(names, "IsNormalExecutionMode") >= 0,
                names.toString());

        assertTrue(
                position(names, "ReadRootFrameLocal") >= 0,
                names.toString());

        for (int index = 0; index < 3; index++) {
            assertInteger(
                    42,
                    ProtosTestExecutionSupport.evaluate(
                            "i095-b4-read-capture-run.protos",
                            "run()",
                            module));
        }
    }

    @Test
    void callbackOwnedBindingCannotAliasParentFrame()
            throws Exception {
        ProtosActivation module =
                new ProtosCoreBootstrap()
                        .bootstrap(CORE)
                        .newModuleActivation();

        ProtosClosureValue run = assertInstanceOf(
                ProtosClosureValue.class,
                ProtosTestExecutionSupport.evaluate(
                        "i095-b4-owned-binding.protos",
                        """
                        run: () => {
                            outer: 41
                            true.ifTrue(() => {
                                outer: 7
                                outer + 1
                            })
                        }
                        run
                        """,
                        module));

        var root = run.executionPlan().orElseThrow()
                .bytecodeActivationRootForTesting();
        root.getRootNodes().ensureComplete();

        var names = root.getBytecodeNode()
                .getInstructionsAsList()
                .stream()
                .map(Instruction::getName)
                .toList();

        assertTrue(
                position(names, "IsNormalExecutionMode") >= 0,
                names.toString());

        assertInteger(
                8,
                ProtosTestExecutionSupport.evaluate(
                        "i095-b4-owned-binding-run.protos",
                        "run()",
                        module));
    }

    @Test
    void normalTrueInlinesCapturedParentWrite() throws Exception {
        ProtosActivation module =
                new ProtosCoreBootstrap()
                        .bootstrap(CORE)
                        .newModuleActivation();

        ProtosClosureValue run = assertInstanceOf(
                ProtosClosureValue.class,
                ProtosTestExecutionSupport.evaluate(
                        "i095-b5-captured-write.protos",
                        """
                        run: () => {
                            count: 40
                            true.ifTrue(() => {
                                count = count + 2
                                count
                            })
                        }
                        run
                        """,
                        module));

        var root = run.executionPlan().orElseThrow()
                .bytecodeActivationRootForTesting();
        root.getRootNodes().ensureComplete();

        var names = root.getBytecodeNode()
                .getInstructionsAsList()
                .stream()
                .map(Instruction::getName)
                .toList();

        assertTrue(
                position(names, "IsNormalExecutionMode") >= 0,
                names.toString());

        assertTrue(
                position(names, "ResolveRootFrameLocalWriteTarget") >= 0,
                names.toString());

        assertTrue(
                position(names, "AssignRootFrameLocal") >= 0,
                names.toString());

        for (int index = 0; index < 3; index++) {
            assertInteger(
                    42,
                    ProtosTestExecutionSupport.evaluate(
                            "i095-b5-captured-write-run.protos",
                            "run()",
                            module));
        }
    }

    @Test
    void callbackOwnedWriteStillUsesItsOwnActivation()
            throws Exception {
        ProtosActivation module =
                new ProtosCoreBootstrap()
                        .bootstrap(CORE)
                        .newModuleActivation();

        ProtosClosureValue run = assertInstanceOf(
                ProtosClosureValue.class,
                ProtosTestExecutionSupport.evaluate(
                        "i095-b5-owned-write.protos",
                        """
                        run: () => {
                            count: 40
                            true.ifTrue(() => {
                                count: 2
                                count = count + 1
                                count
                            })
                        }
                        run
                        """,
                        module));

        var root = run.executionPlan().orElseThrow()
                .bytecodeActivationRootForTesting();
        root.getRootNodes().ensureComplete();

        var names = root.getBytecodeNode()
                .getInstructionsAsList()
                .stream()
                .map(Instruction::getName)
                .toList();

        assertTrue(
                position(names, "IsNormalExecutionMode") < 0,
                names.toString());

        assertInteger(
                3,
                ProtosTestExecutionSupport.evaluate(
                        "i095-b5-owned-write-run.protos",
                        "run()",
                        module));
    }

    @Test
    void i095B6aOwnScalarScopeDoesNotAliasParent() throws Exception {
        ProtosActivation module = new ProtosCoreBootstrap()
                .bootstrap(CORE).newModuleActivation();
        ProtosClosureValue run = assertInstanceOf(
                ProtosClosureValue.class,
                ProtosTestExecutionSupport.evaluate(
                        "i095-b6a-own-scope.protos",
                        """
                        run: () => {
                            value: 9
                            inside: true.ifTrue(() => {
                                value: 41
                                value = 42
                                value
                            })
                            inside + value
                        }
                        run
                        """, module));
        var root = run.executionPlan().orElseThrow()
                .bytecodeActivationRootForTesting();
        root.getRootNodes().ensureComplete();
        var names = root.getBytecodeNode().getInstructionsAsList()
                .stream().map(Instruction::getName).toList();
        assertTrue(position(names, "IsNormalExecutionMode") >= 0, names.toString());
        for (int i = 0; i < 3; i++) {
            assertInteger(51, ProtosTestExecutionSupport.evaluate(
                    "i095-b6a-own-scope-call.protos", "run()", module));
        }
        System.out.println("I095_B6A_OWN_SCOPE=PASS");
    }

    @Test
    void i095B6aIndependentCallbackBindings() throws Exception {
        ProtosActivation module = new ProtosCoreBootstrap()
                .bootstrap(CORE).newModuleActivation();
        ProtosClosureValue run = assertInstanceOf(
                ProtosClosureValue.class,
                ProtosTestExecutionSupport.evaluate(
                        "i095-b6a-two-callbacks.protos",
                        """
                        run: () => {
                            a: true.ifTrue(() => {
                                item: 1
                                item = 2
                                item
                            })
                            b: true.ifTrue(() => {
                                item: 40
                                item = 41
                                item
                            })
                            a + b
                        }
                        run
                        """, module));
        var root = run.executionPlan().orElseThrow()
                .bytecodeActivationRootForTesting();
        root.getRootNodes().ensureComplete();
        var names = root.getBytecodeNode().getInstructionsAsList()
                .stream().map(Instruction::getName).toList();
        assertTrue(position(names, "IsNormalExecutionMode") >= 0, names.toString());
        for (int i = 0; i < 3; i++) {
            assertInteger(43, ProtosTestExecutionSupport.evaluate(
                    "i095-b6a-two-callbacks-call.protos", "run()", module));
        }
        System.out.println("I095_B6A_MULTIPLE_CALLBACK_SCOPES=PASS");
    }

    @Test
    void i095B6aUnprovenPresenceKeepsBPrime() throws Exception {
        for (String body : List.of(
                "item\nitem: 7\nitem",
                "item: 1\nitem: 2\nitem")) {
            ProtosActivation module = new ProtosCoreBootstrap()
                    .bootstrap(CORE).newModuleActivation();
            ProtosClosureValue run = assertInstanceOf(
                    ProtosClosureValue.class,
                    ProtosTestExecutionSupport.evaluate(
                            "i095-b6a-fallback.protos",
                            "run: () => { true.ifTrue(() => { " + body + " }) }\nrun",
                            module));
            var root = run.executionPlan().orElseThrow()
                    .bytecodeActivationRootForTesting();
            root.getRootNodes().ensureComplete();
            var names = root.getBytecodeNode().getInstructionsAsList()
                    .stream().map(Instruction::getName).toList();
            assertTrue(position(names, "IsNormalExecutionMode") < 0, names.toString());
        }
        System.out.println("I095_B6A_UNPROVEN_PRESENCE=B_PRIME");
    }

    @Test
    void i095B6bOwnLocalsAndParentCaptureWrite() throws Exception {
        ProtosActivation module = new ProtosCoreBootstrap()
                .bootstrap(CORE).newModuleActivation();
        ProtosClosureValue run = assertInstanceOf(
                ProtosClosureValue.class,
                ProtosTestExecutionSupport.evaluate(
                        "i095-b6b-mixed-write.protos",
                        """
                        run: () => {
                            outer: 40
                            shadow: 11
                            inside: true.ifTrue(() => {
                                shadow: 1
                                outer = outer + shadow
                                shadow = 2
                                outer + shadow
                            })
                            inside + outer + shadow
                        }
                        run
                        """, module));
        var root = run.executionPlan().orElseThrow()
                .bytecodeActivationRootForTesting();
        root.getRootNodes().ensureComplete();
        var names = root.getBytecodeNode().getInstructionsAsList()
                .stream().map(Instruction::getName).toList();
        assertTrue(position(names, "IsNormalExecutionMode") >= 0, names.toString());
        assertTrue(position(names, "ReadRootFrameLocal") >= 0, names.toString());
        assertTrue(position(names, "ResolveRootFrameLocalWriteTarget") >= 0, names.toString());
        assertTrue(position(names, "AssignRootFrameLocal") >= 0, names.toString());
        for (int i = 0; i < 3; i++) {
            assertInteger(95, ProtosTestExecutionSupport.evaluate(
                    "i095-b6b-mixed-write-call.protos", "run()", module));
        }
        System.out.println("I095_B6B_MIXED_CAPTURE_WRITE=PASS");
    }

    @Test
    void i095B6bOwnLocalsAndParentRead() throws Exception {
        ProtosActivation module = new ProtosCoreBootstrap()
                .bootstrap(CORE).newModuleActivation();
        ProtosClosureValue run = assertInstanceOf(
                ProtosClosureValue.class,
                ProtosTestExecutionSupport.evaluate(
                        "i095-b6b-mixed-read.protos",
                        """
                        run: () => {
                            outer: 41
                            true.ifTrue(() => {
                                inner: 1
                                outer + inner
                            })
                        }
                        run
                        """, module));
        var root = run.executionPlan().orElseThrow()
                .bytecodeActivationRootForTesting();
        root.getRootNodes().ensureComplete();
        var names = root.getBytecodeNode().getInstructionsAsList()
                .stream().map(Instruction::getName).toList();
        assertTrue(position(names, "IsNormalExecutionMode") >= 0, names.toString());
        assertTrue(position(names, "ReadRootFrameLocal") >= 0, names.toString());
        for (int i = 0; i < 3; i++) {
            assertInteger(42, ProtosTestExecutionSupport.evaluate(
                    "i095-b6b-mixed-read-call.protos", "run()", module));
        }
        System.out.println("I095_B6B_MIXED_CAPTURE_READ=PASS");
    }

    @Test
    void i095B6bCandidateCaptureKeepsBPrime() throws Exception {
        ProtosActivation module = new ProtosCoreBootstrap()
                .bootstrap(CORE).newModuleActivation();
        ProtosClosureValue run = assertInstanceOf(
                ProtosClosureValue.class,
                ProtosTestExecutionSupport.evaluate(
                        "i095-b6b-candidate-fallback.protos",
                        """
                        run: () => {
                            outer: 41
                            true.ifTrue(() => {
                                inner: 1
                                missing + inner
                            })
                        }
                        run
                        """, module));
        var root = run.executionPlan().orElseThrow()
                .bytecodeActivationRootForTesting();
        root.getRootNodes().ensureComplete();
        var names = root.getBytecodeNode().getInstructionsAsList()
                .stream().map(Instruction::getName).toList();
        assertTrue(position(names, "IsNormalExecutionMode") < 0, names.toString());
        System.out.println("I095_B6B_DYNAMIC_FALLBACK=B_PRIME");
    }

    @Test
    void falseDoesNotInvokeBodyAndReturnsNull() throws Exception {
        assertSame(ProtosNullValue.INSTANCE, evaluate("""
                run: () => {
                    false.ifTrue(() => { ^99 })
                }
                run()
                """));
    }

    @Test
    void unselectedProducingExpressionIsEvaluatedOnce() throws Exception {
        assertInteger(1, evaluate("""
                run: () => {
                    count: 0
                    producer: () => {
                        count = count + 1
                        () => { 99 }
                    }
                    false.ifTrue(producer())
                    count
                }
                run()
                """));
    }

    @Test
    void ordinaryOverrideStaysOrdinary() throws Exception {
        assertInteger(77, evaluate("""
                run: () => {
                    custom: {
                        ifTrue: (callback) => { 77 }
                    }
                    custom.ifTrue(() => { 2 })
                }
                run()
                """));
    }

    @Test
    void captureAndNonLocalReturnArePreserved() throws Exception {
        assertInteger(42, evaluate("""
                run: () => {
                    count: 41
                    true.ifTrue(() => { count + 1 })
                }
                run()
                """));

        assertInteger(42, evaluate("""
                run: () => {
                    true.ifTrue(() => { ^41 })
                    99
                }
                run() + 1
                """));
    }


    @Test
    void canonicalBooleanHasIndependentNativeBytecodeBranch() throws Exception {
        ProtosActivation module =
                new ProtosCoreBootstrap().bootstrap(CORE).newModuleActivation();
        ProtosClosureValue run = assertInstanceOf(
                ProtosClosureValue.class,
                ProtosTestExecutionSupport.evaluate(
                        "i092-native.protos",
                        """
                        run: () => { true.ifTrue(() => { 41 }) }
                        run
                        """,
                        module));

        var root = run.executionPlan().orElseThrow()
                .bytecodeActivationRootForTesting();
        root.getRootNodes().ensureComplete();
        List<String> names = root.getBytecodeNode()
                .getInstructionsAsList().stream()
                .map(Instruction::getName).toList();

        int selection = position(names, "TryCompactCanonicalBooleanOne");
        int miss = position(names, "IsCompactCanonicalBooleanMiss");
        int fallback = position(names, "PrepareSendOne");
        int generic = position(names, "PrepareStructuredBooleanCall");
        int nativeBranch = position(names, "IsCompactCanonicalBooleanNoCallback");

        assertTrue(selection < 0 && miss >= 0, names.toString());
        assertTrue(
                position(names, "PrepareCanonicalIfTrueCallback") >= 0,
                names.toString());
        assertTrue(fallback > miss && generic > fallback, names.toString());
        assertTrue(nativeBranch > generic, names.toString());
        assertFalse(Arrays.stream(ProtosBytecodeRootNode.class.getDeclaredClasses())
                .anyMatch(type -> type.getSimpleName()
                        .equals("CompactCanonicalBooleanCall")));
    }

    private static int position(List<String> names, String operation) {
        for (int i = 0; i < names.size(); i++) {
            if (names.get(i).contains(operation)) {
                return i;
            }
        }
        return -1;
    }

    @Test
    void dynamicCallbackKeepsCanonicalControl() throws Exception {
        assertInteger(42, evaluate("""
                run: () => {
                    callback: () => { 42 }
                    true.ifTrue(callback)
                }
                run()
                """));

        assertSame(ProtosNullValue.INSTANCE, evaluate("""
                run: () => {
                    callback: () => { ^99 }
                    false.ifTrue(callback)
                }
                run()
                """));
    }

    @Test
    void dynamicCallbackEmitsNativeControl() throws Exception {
        var module = new ProtosCoreBootstrap()
                .bootstrap(CORE).newModuleActivation();
        var run = assertInstanceOf(
                ProtosClosureValue.class,
                ProtosTestExecutionSupport.evaluate(
                        "i092-dynamic-control.protos",
                        """
                        run: (callback) => { true.ifTrue(callback) }
                        run
                        """,
                        module));
        var root = run.executionPlan().orElseThrow()
                .bytecodeActivationRootForTesting();
        root.getRootNodes().ensureComplete();
        var names = root.getBytecodeNode().getInstructionsAsList()
                .stream().map(Instruction::getName).toList();

        assertTrue(position(names, "TryCompactCanonicalBooleanOne") < 0);
        assertTrue(position(names, "PrepareCanonicalIfTrueCallback") >= 0);
        assertTrue(position(names, "IsCompactCanonicalBooleanNoCallback") >= 0);
        assertTrue(position(names, "LoadInlineLiteralFallbackCall") >= 0, names.toString());
        assertNoSyntheticIfTrueActivation();
    }

    /** The native path has no carrier for a standard ifTrue activation. */
    private static void assertNoSyntheticIfTrueActivation() {
        for (Class<?> root : List.of(
                ProtosBytecodeRootNode.class, ProtosSemanticBytecodeRootNode.class)) {
            assertFalse(Arrays.stream(root.getDeclaredClasses())
                    .map(Class::getSimpleName)
                    .anyMatch(name -> name.equals("PreparedNativeIfTrueCallback")
                            || name.equals("LoadNativeIfTrueCallback")
                            || name.equals("CompleteNativeIfTrueCallback")
                            || name.equals("GuardedCompactIfTrue")),
                    root.getName());
        }
    }

    @Test
    void nativeCallbackKeepsOrdinaryCallSelection() throws Exception {
        assertInteger(8, evaluate("""
                run: (callback) => { true.ifTrue(callback) }
                run({ call: () => { 8 } })
                """));
        assertInteger(95, evaluate("""
                run: (callback) => { true.ifTrue(callback) }
                Error.handle(
                    () => { run(17) },
                    (caught) => { 95 })
                """));
        assertSame(ProtosNullValue.INSTANCE, evaluate("""
                run: (callback) => { false.ifTrue(callback) }
                run(17)
                """));
    }


    @Test
    void defaultParameterIfTrueBehavior() throws Exception {
        assertInteger(41, evaluate("""
                run: (answer = true.ifTrue(() => { 41 })) => { answer }
                run()
                """));
        assertSame(ProtosNullValue.INSTANCE, evaluate("""
                run: (answer = false.ifTrue(() => { ^99 })) => { answer }
                run()
                """));
    }


    @Test
    void defaultParameterEmitsNativeIfTrue() throws Exception {
        var module = new ProtosCoreBootstrap()
                .bootstrap(CORE).newModuleActivation();
        var run = assertInstanceOf(
                ProtosClosureValue.class,
                ProtosTestExecutionSupport.evaluate(
                        "i092-default.protos",
                        """
                        run: (answer = true.ifTrue(() => { 41 })) => { answer }
                        run
                        """,
                        module));
        var root = run.executionPlan().orElseThrow()
                .bytecodeActivationRootForTesting();
        root.getRootNodes().ensureComplete();
        var names = root.getBytecodeNode().getInstructionsAsList()
                .stream().map(Instruction::getName).toList();
        assertTrue(position(names, "TryCompactCanonicalBooleanOne") < 0);
        assertTrue(position(names, "PrepareCanonicalIfTrueCallback") >= 0);
        assertTrue(position(names, "IsCompactCanonicalBooleanNoCallback") >= 0);
        // Default-parameter sites have no B-prime region: ordinary callback invocation.
        int nativeCheck = position(names, "IsCompactCanonicalBooleanNoCallback");
        assertTrue(names.subList(nativeCheck, names.size()).stream()
                .anyMatch(name -> name.contains("LoadInlineLiteralFallbackCall")),
                names.toString());
        assertNoSyntheticIfTrueActivation();
    }


    @Test
    void spreadIfTruePreservesControlAndArity() throws Exception {
        assertInteger(42, evaluate("""
                run: () => {
                    callbacks: [() => { 42 }]
                    true.ifTrue(...callbacks)
                }
                run()
                """));
        assertSame(ProtosNullValue.INSTANCE, evaluate("""
                run: () => {
                    callbacks: [17]
                    false.ifTrue(...callbacks)
                }
                run()
                """));
        assertInteger(93, evaluate("""
                Error.handle(
                    () => { true.ifTrue(...[]) },
                    (caught) => { 93 })
                """));
    }


    @Test
    void spreadIfTrueEmitsNativeControl() throws Exception {
        var module = new ProtosCoreBootstrap()
                .bootstrap(CORE).newModuleActivation();
        var run = assertInstanceOf(
                ProtosClosureValue.class,
                ProtosTestExecutionSupport.evaluate(
                        "i092-spread.protos",
                        """
                        run: (callbacks) => { true.ifTrue(...callbacks) }
                        run
                        """,
                        module));
        var root = run.executionPlan().orElseThrow()
                .bytecodeActivationRootForTesting();
        root.getRootNodes().ensureComplete();
        var names = root.getBytecodeNode().getInstructionsAsList()
                .stream().map(Instruction::getName).toList();

        assertTrue(position(names, "TryCompactCanonicalBooleanVector") >= 0);
        assertTrue(
                position(names, "IsCanonicalIfTrueTrue")
                        > position(names, "TryCompactCanonicalBooleanVector"));
        assertTrue(
                position(names, "PrepareCanonicalIfTrueVectorCallback")
                        > position(names, "IsCanonicalIfTrueTrue"));
        assertTrue(position(names, "IsCompactCanonicalBooleanMiss") >= 0);
        assertTrue(position(names, "IsCompactCanonicalBooleanNoCallback") >= 0);
    }


    @Test
    void inlineFrameNativeIfTrueBehavior() throws Exception {
        assertInteger(42, evaluate("""
                run: (callback) => {
                    true.ifTrue(() => { true.ifTrue(callback) })
                }
                run(() => { 42 })
                """));
        assertSame(ProtosNullValue.INSTANCE, evaluate("""
                run: (callback) => {
                    true.ifTrue(() => { false.ifTrue(callback) })
                }
                run(17)
                """));
        assertInteger(42, evaluate("""
                run: (callbacks) => {
                    true.ifTrue(() => { true.ifTrue(...callbacks) })
                }
                run([() => { 42 }])
                """));
    }


    @Test
    void inlineFrameNativeEmitsNativeControl() throws Exception {
        var module = new ProtosCoreBootstrap()
                .bootstrap(CORE).newModuleActivation();
        var run = assertInstanceOf(
                ProtosClosureValue.class,
                ProtosTestExecutionSupport.evaluate(
                        "i092-inline-frame.protos",
                        """
                        run: (callback, callbacks) => {
                            true.ifTrue(() => {
                                true.ifTrue(callback)
                                true.ifTrue(...callbacks)
                            })
                        }
                        run
                        """,
                        module));
        var root = run.executionPlan().orElseThrow()
                .bytecodeActivationRootForTesting();
        root.getRootNodes().ensureComplete();
        var names = root.getBytecodeNode().getInstructionsAsList()
                .stream().map(Instruction::getName).toList();

        assertTrue(position(names, "TryCompactCanonicalBooleanInlineOne") >= 0);
        assertTrue(position(names, "TryCompactCanonicalBooleanInlineVector") >= 0);
        assertTrue(
                position(names, "PrepareCanonicalIfTrueInlineCallback")
                        > position(names, "TryCompactCanonicalBooleanInlineOne"));
        assertTrue(
                position(names, "PrepareCanonicalIfTrueInlineVectorCallback")
                        > position(names, "TryCompactCanonicalBooleanInlineVector"));
        assertTrue(position(names, "IsCompactCanonicalBooleanNoCallback") >= 0);
    }


    @Test
    void canonicalAndOverrideExecuteOppositePhysicalBranches() throws Exception {
        var module = new ProtosCoreBootstrap()
                .bootstrap(CORE).newModuleActivation();
        var run = assertInstanceOf(
                ProtosClosureValue.class,
                ProtosTestExecutionSupport.evaluate(
                        "i092-branch-definition.protos",
                        """
                        run: (receiver) => {
                            receiver.ifTrue(() => { 41 })
                        }
                        run
                        """,
                        module));

        var root = run.executionPlan().orElseThrow()
                .bytecodeActivationRootForTesting();
        root.getRootNodes().ensureComplete();
        // Count both paths from the first invocation, not after the uncached threshold.
        root.getBytecodeNode().setUncachedThreshold(0);

        assertInteger(41, ProtosTestExecutionSupport.evaluate(
                "i092-branch-true.protos", "run(true)", module));
        assertSame(ProtosNullValue.INSTANCE,
                ProtosTestExecutionSupport.evaluate(
                        "i092-branch-false.protos", "run(false)", module));
        assertInteger(99, ProtosTestExecutionSupport.evaluate(
                "i092-branch-override.protos",
                """
                custom: { ifTrue: (callback) => { 99 } }
                run(custom)
                """,
                module));

        var instructions = root.getBytecodeNode().getInstructionsAsList();
        var names = instructions.stream().map(Instruction::getName).toList();
        assertEquals(
                1L,
                names.stream()
                        .filter(name -> name.contains(
                                "IsCompactCanonicalBooleanMiss"))
                        .count(),
                "Canonical/generic classification must occur once");

        int candidate = position(names, "TryCompactCanonicalBooleanOne");
        int primitiveBranch = position(names, "IsCanonicalIfTrueTrue");
        int nativePreparation = position(names, "PrepareCanonicalIfTrueCallback");
        assertTrue(
                candidate >= 0
                        && primitiveBranch > candidate
                        && nativePreparation > primitiveBranch,
                () -> "Primitive classification must precede callback preparation: "
                        + names);
        int miss = position(names, "IsCompactCanonicalBooleanMiss");
        int generic = position(names, "PrepareSendOne");
        int nativeCheck = position(names, "IsCompactCanonicalBooleanNoCallback");

        assertTrue(candidate >= 0 && miss > candidate
                        && generic > miss && nativeCheck > generic,
                () -> "Native/generic bytecode ordering: " + names);

        var branch = instructions.subList(miss + 1, generic).stream()
                .filter(instruction ->
                        instruction.getName().startsWith("branch.false"))
                .findFirst().orElseThrow();

        int target = branch.getArguments().stream()
                .filter(argument -> argument.getKind()
                        == Instruction.Argument.Kind.BYTECODE_INDEX)
                .findFirst().orElseThrow().asBytecodeIndex();

        assertTrue(target > instructions.get(generic).getBytecodeIndex(),
                "Canonical hit must skip generic send preparation");
        assertTrue(target <= instructions.get(nativeCheck).getBytecodeIndex(),
                "Canonical hit must enter the native Boolean branch");

        var profile = branch.getArguments().stream()
                .filter(argument -> argument.getKind()
                        == Instruction.Argument.Kind.BRANCH_PROFILE)
                .findFirst().orElseThrow().asBranchProfile();

        assertTrue(profile.falseCount() > 0,
                () -> "Canonical hit profile: " + profile);
        assertTrue(profile.trueCount() > 0,
                () -> "Generic fallback profile: " + profile);
    }


    @Test
    void dynamicReceiverSiteSurvivesCanonicalAndOverrideTransitions() throws Exception {
        var module = new ProtosCoreBootstrap()
                .bootstrap(CORE).newModuleActivation();
        assertInstanceOf(
                ProtosClosureValue.class,
                ProtosTestExecutionSupport.evaluate(
                        "i092-dynamic-definition.protos",
                        """
                        run: (receiver) => {
                            receiver.ifTrue(() => { 41 })
                        }
                        run
                        """,
                        module));

        assertInteger(41, ProtosTestExecutionSupport.evaluate(
                "i092-dynamic-true.protos", "run(true)", module));
        assertSame(ProtosNullValue.INSTANCE, ProtosTestExecutionSupport.evaluate(
                "i092-dynamic-false.protos", "run(false)", module));
        for (int i = 0; i < 8; i++) {
            assertInteger(41, ProtosTestExecutionSupport.evaluate(
                    "i092-dynamic-alt-true.protos", "run(true)", module));
            assertSame(ProtosNullValue.INSTANCE, ProtosTestExecutionSupport.evaluate(
                    "i092-dynamic-alt-false.protos", "run(false)", module));
        }
        assertInteger(99, ProtosTestExecutionSupport.evaluate(
                "i092-dynamic-override.protos",
                """
                custom: { ifTrue: (callback) => { 99 } }
                run(custom)
                """,
                module));
        assertInteger(41, ProtosTestExecutionSupport.evaluate(
                "i092-dynamic-true-after.protos", "run(true)", module));
        assertSame(ProtosNullValue.INSTANCE, ProtosTestExecutionSupport.evaluate(
                "i092-dynamic-false-after.protos", "run(false)", module));
    }

    @Test
    void dynamicReceiverUnselectedProducerIsEvaluatedOnce() throws Exception {
        assertInteger(32199, evaluate("""
                trace: () => {
                    count: 0
                    invoked: 0
                    producer: () => {
                        count = count + 1
                        () => {
                            invoked = invoked + 1
                            99
                        }
                    }
                    run: (receiver) => {
                        receiver.ifTrue(producer())
                    }
                    custom: { ifTrue: (callback) => { callback() } }
                    first: run(true)
                    second: run(custom)
                    third: run(false)
                    nullMark: (third === null).ifTrueIfFalse(() => { 1 }, () => { 0 })
                    count * 10000 + invoked * 1000 + first + second + nullMark
                }
                trace()
                """));
    }


    @Test
    void frameNativeSpreadErrorAndCallerProvenance() throws Exception {
        assertInteger(73, evaluate("""
                run: () => {
                    true.ifTrue(() => {
                        false.ifTrue(...17)
                    })
                }
                Error.handle(
                    () => { run() },
                    (caught) => { 73 })
                """));

        assertInteger(74, evaluate("""
                Error.handle(
                    () => { false.ifTrue(...17) },
                    (caught) => { 74 })
                """));

        assertInteger(44, evaluate("""
                run: (callback) => {
                    true.ifTrue(() => {
                        local: 43
                        true.ifTrue(callback)
                        local + 1
                    })
                }
                run(() => { 91 })
                """));
    }

    private static Object evaluate(String source) throws Exception {
        ProtosExecutionOutcome outcome =
                ProtosTestExecutionSupport.execute(
                        "i092-compact-boolean.protos",
                        source,
                        new ProtosCoreBootstrap()
                                .bootstrap(CORE)
                                .newModuleActivation());

        assertEquals(
                ProtosExecutionOutcome.State.COMPLETED,
                outcome.state(),
                () -> "Outcome: " + outcome.state()
                        + " error=" + outcome.error());
        return outcome.value();
    }

    private static void assertInteger(long expected, Object value) {
        assertEquals(Long.toString(expected), com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerDecimalText(value));
    }
}
