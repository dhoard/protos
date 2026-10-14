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
import com.oracle.truffle.api.bytecode.TagTree;
import com.oracle.truffle.api.instrumentation.StandardTags;
import com.oracle.truffle.api.nodes.RootNode;
import com.oracle.truffle.api.source.Source;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;

/**
 * PERF025-C1c (PLAT042 Candidate B′) focal topology evidence.
 *
 * <p>Ordinary source roots are tagged semantic Bytecode roots: an ordinary
 * source Closure call adds exactly one CallTarget to the stack. A structured
 * prepared invocation adds exactly one untagged structured-dispatch CallTarget,
 * and that helper root carries no RootTag. PLAT043 (PERF025-C2B) narrows the
 * structured case: standard Boolean control is sequenced in the source root and
 * adds no helper CallTarget.
 */
final class ProtosPerf025C1cSemanticSourceTopologyTest {
    private static final Path CORE = Path.of("protos", "lib", "core");

    private static final LanguageReference<ProtosLanguage> LANGUAGE_REF =
            LanguageReference.create(ProtosLanguage.class);

    @Test
    void ordinarySourceClosureCallUsesOneSemanticCallTarget() throws Exception {
        try (Context context = Context.newBuilder(ProtosLanguage.ID).build()) {
            context.initialize(ProtosLanguage.ID);
            context.enter();
            try {
                List<RootNode> stack = new ArrayList<>();
                ProtosActivation module = moduleWithProbe(stack);
                ProtosSemanticBytecodeRootNode root =
                        lowerRoot(
                                """
                                inner: () => { probe() }
                                outer: () => { inner() }
                                outer()
                                """);

                assertSame(ProtosNullValue.INSTANCE, root.getCallTarget().call(module));

                assertEquals(3, stack.size(), () -> "stack=" + stack);
                for (RootNode frameRoot : stack) {
                    assertInstanceOf(ProtosSemanticBytecodeRootNode.class, frameRoot);
                }
                assertSame(root, stack.get(2));

                root.getRootNodes().ensureComplete();
                assertEquals(1, rootTags(root.getBytecodeNode()));
            } finally {
                context.leave();
            }
        }
        System.out.println("PERF025_C1C_ORDINARY_SOURCE_CLOSURE_CALLTARGET_COUNT=1");
        System.out.println("PERF025_C1C_SEMANTIC_AUTOMATIC_ROOT_TAG=YES");
    }

    @Test
    void closureActivationRootIsItsOwnCompositionTarget() throws Exception {
        try (Context context = Context.newBuilder(ProtosLanguage.ID).build()) {
            context.initialize(ProtosLanguage.ID);
            context.enter();
            try {
                ProtosActivation module = moduleWithProbe(new ArrayList<>());
                ProtosSemanticBytecodeRootNode root = lowerRoot("() => 1");
                ProtosClosureValue closure =
                        assertInstanceOf(
                                ProtosClosureValue.class,
                                root.getCallTarget().call(module));
                ProtosClosureExecutionPlan plan = closure.executionPlan().orElseThrow();
                ProtosSemanticBytecodeRootNode activationRoot =
                        plan.bytecodeActivationRootForTesting();

                assertSame(
                        activationRoot,
                        plan.bytecodeActivationTargetForComposition().getRootNode(),
                        "no universal semantic wrapper may sit in front of a Closure root");
                assertSame(root.getRootNodes(), activationRoot.getRootNodes());

                activationRoot.getRootNodes().ensureComplete();
                assertEquals(1, rootTags(activationRoot.getBytecodeNode()));
            } finally {
                context.leave();
            }
        }
        System.out.println("PERF025_C1C_UNIVERSAL_SEMANTIC_WRAPPER=REMOVED");
    }

    /**
     * PLAT043 (PERF025-C2B): every prepared standard Boolean kind is sequenced
     * in the semantic source root, so the reached callback root sits directly
     * on the source root with no untagged helper root in between.
     */
    @Test
    void standardBooleanControlEntersNoStructuredRoot() throws Exception {
        List<String> sources =
                List.of(
                        "true.ifTrue(() => { probe() })",
                        "false.ifFalse(() => { probe() })",
                        "false.ifTrueIfFalse(() => 1, () => { probe() })",
                        """
                        true.and(() => {
                            probe()
                            true
                        })
                        """,
                        """
                        false.or(() => {
                            probe()
                            true
                        })
                        """);
        try (Context context = Context.newBuilder(ProtosLanguage.ID).build()) {
            context.initialize(ProtosLanguage.ID);
            context.enter();
            try {
                for (String characters : sources) {
                    List<RootNode> stack = new ArrayList<>();
                    ProtosActivation module = moduleWithProbe(stack);
                    ProtosSemanticBytecodeRootNode root = lowerRoot(characters);

                    root.getCallTarget().call(module);

                    assertEquals(2, stack.size(), () -> characters + " stack=" + stack);
                    for (RootNode frameRoot : stack) {
                        assertInstanceOf(
                                ProtosSemanticBytecodeRootNode.class,
                                frameRoot,
                                () -> characters + " stack=" + stack);
                    }
                    assertSame(root, stack.get(1));

                    ProtosSemanticBytecodeRootNode callback =
                            (ProtosSemanticBytecodeRootNode) stack.get(0);
                    callback.getRootNodes().ensureComplete();
                    assertEquals(1, rootTags(callback.getBytecodeNode()));
                }
            } finally {
                context.leave();
            }
        }
        System.out.println("PERF025_C2B_BOOLEAN_HELPER_CALLTARGET_REMOVED=YES");
    }

    /**
     * PLAT042 remains authoritative for every other structured family: a
     * reached {@code Closure.whileTrue} condition runs while exactly one untagged
     * structured-dispatch root is live between it and the source root.
     */
    @Test
    void nonBooleanStructuredInvocationEntersOneUntaggedStructuredRoot() throws Exception {
        try (Context context = Context.newBuilder(ProtosLanguage.ID).build()) {
            context.initialize(ProtosLanguage.ID);
            context.enter();
            try {
                List<RootNode> stack = new ArrayList<>();
                ProtosActivation module = moduleWithProbe(stack);
                ProtosSemanticBytecodeRootNode root =
                        lowerRoot(
                                """
                                (() => {
                                    probe()
                                    false
                                }).whileTrue(() => 1)
                                """);

                root.getCallTarget().call(module);

                assertEquals(3, stack.size(), () -> "stack=" + stack);
                assertInstanceOf(ProtosSemanticBytecodeRootNode.class, stack.get(0));
                ProtosBytecodeRootNode structured =
                        assertInstanceOf(ProtosBytecodeRootNode.class, stack.get(1));
                assertSame(root, stack.get(2));

                RootCallTarget structuredTarget =
                        ProtosTaskCPrimeEntryExecution.planForEnteredContext().target();
                assertSame(structuredTarget.getRootNode(), structured);

                structured.getRootNodes().ensureComplete();
                assertEquals(0, rootTags(structured.getBytecodeNode()));
            } finally {
                context.leave();
            }
        }
        System.out.println("PERF025_C1C_STRUCTURED_ONLY_HELPER_BOUNDARY=YES");
        System.out.println("PERF025_C1C_STRUCTURED_HELPER_ROOT_TAG=NO");
    }

    @Test
    void guardedMethodSendEntersSemanticRootWithOneActivation() throws Exception {
        ProtosActivation module =
                new ProtosCoreBootstrap().bootstrap(CORE).newModuleActivation();
        ProtosExecutionOutcome outcome =
                ProtosTestExecutionSupport.execute(
                        "perf025-c1c-compact-send.protos",
                        """
                        receiver: {
                            identity: (value) => { value }
                            fail: () => { Error().signal() }
                        }
                        sum: 0
                        count: 0
                        (() => count < 20).whileTrue(() => {
                            sum = sum + receiver.identity(2)
                            count = count + 1
                        })
                        sum + Error.handle(() => receiver.fail(), (caught) => 2)
                        """,
                        module);
        assertEquals(
                ProtosExecutionOutcome.State.COMPLETED,
                outcome.state(),
                () -> "outcome=" + outcome.state() + ", error=" + outcome.error());
        assertEquals(Long.toString(42), com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerDecimalText(outcome.value()));
        System.out.println("PERF025_C1C_COMPACT_METHOD_SEND_ACTIVATION=PASS");
    }

    private static ProtosActivation moduleWithProbe(List<RootNode> stack) throws Exception {
        ProtosActivation module =
                new ProtosCoreBootstrap().bootstrap(CORE).newModuleActivation();
        module.context().createLocalSlot(
                "probe",
                ProtosClosureValue.nativeClosure(
                        (activation, supplied) -> {
                            stack.clear();
                            Truffle.getRuntime()
                                    .iterateFrames(
                                            frame -> {
                                                stack.add(
                                                        ((RootCallTarget) frame.getCallTarget())
                                                                .getRootNode());
                                                return null;
                                            });
                            return ProtosNullValue.INSTANCE;
                        }));
        return module;
    }

    private static ProtosSemanticBytecodeRootNode lowerRoot(String characters) {
        Source source =
                Source.newBuilder(
                                ProtosLanguage.ID,
                                characters,
                                "perf025-c1c-topology.protos")
                        .build();
        CanonicalSequence sequence =
                (CanonicalSequence)
                        new Canonicalizer()
                                .canonicalize(new ProtosParser(characters).parseProgram());
        return new CanonicalToBytecodeLowerer(LANGUAGE_REF.get(null), source, ProtosExecutionMode.NORMAL)
                .lowerRoot(sequence);
    }

    private static int rootTags(BytecodeNode bytecodeNode) {
        return countTags(bytecodeNode.getTagTree(), StandardTags.RootTag.class);
    }

    private static int countTags(
            TagTree tree,
            Class<? extends com.oracle.truffle.api.instrumentation.Tag> tag) {
        if (tree == null) {
            return 0;
        }
        int count = tree.hasTag(tag) ? 1 : 0;
        for (TagTree child : tree.getTreeChildren()) {
            count += countTags(child, tag);
        }
        return count;
    }
}
