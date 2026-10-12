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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.guillermomolina.protos.runtime.ProtosActivation;
import com.guillermomolina.protos.runtime.ProtosArrayValue;
import com.guillermomolina.protos.runtime.ProtosClosureValue;
import com.guillermomolina.protos.runtime.ProtosDiagnosticTrace;
import com.guillermomolina.protos.runtime.ProtosIntegerValue;
import com.guillermomolina.protos.runtime.ProtosObjectValue;
import com.guillermomolina.protos.runtime.ProtosPrelude;
import com.guillermomolina.protos.runtime.ProtosSignalException;
import com.guillermomolina.protos.runtime.ProtosTask;
import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.bytecode.Instruction;
import com.oracle.truffle.api.nodes.IndirectCallNode;
import com.oracle.truffle.api.source.Source;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Value;
import org.junit.jupiter.api.Test;

/**
 * PERF032-G6: a source Closure declaring no parameters executes a normal root without any
 * argument-count check, while every ordinary call that supplies arguments enters the Closure's
 * arity-rejection root, which signals the unchanged guest argument-count Error ({@code
 * CALLABLES.md}, Normative parameter-binding algorithm) without evaluating the body.
 */
final class ProtosPerf032G6ZeroParameterGuestRootTest {
    private static final Path CORE = Path.of("protos", "lib", "core");
    private static final String UPPER_BOUND_CHECK = "CheckFrameClosureArgumentUpperBound";

    @Test
    void normalRootHasNoArityCheckAndIsTheDirectSemanticTarget() throws Exception {
        withCore(module -> {
            ProtosClosureValue one = parsedClosure("() => { 1 }", "g6-root.protos", module);
            ProtosSemanticBytecodeRootNode root = planRoot(one);

            assertNone(instructionNames(root), UPPER_BOUND_CHECK);
            assertSame(root, target(one).getRootNode(), "no universal wrapper root");

            RootCallTarget rejection = root.arityRejectionTarget();
            assertNotNull(rejection);
            assertNotSame(target(one), rejection);
            ProtosSemanticBytecodeRootNode rejectionRoot =
                    assertInstanceOf(ProtosSemanticBytecodeRootNode.class, rejection.getRootNode());
            assertContains(instructionNames(rejectionRoot), UPPER_BOUND_CHECK);
            assertNull(rejectionRoot.arityRejectionTarget(), "the rejection root is terminal");
        });
        System.out.println("PERF032_G6_NORMAL_ROOT_WITHOUT_ARITY_CHECK=PASS");
        System.out.println("PERF032_G6_DIRECT_SEMANTIC_TARGET=PASS");
    }

    @Test
    void validCallsStayCompactOnTheNormalTarget() throws Exception {
        withCore(module -> {
            ProtosClosureValue one = parsedClosure("() => { 1 }", "g6-valid.protos", module);
            for (int index = 0; index < 4; index++) {
                ProtosBytecodeRootNode.PreparedClosureCall prepared = fastDirect(one, module);
                assertSame(target(one), prepared.bodyTarget());
                assertEquals("1", integerValue(enter(prepared)));
                assertSame(one, prepared.targetArguments()[0], "no activation was materialized");
                assertNull(prepared.taskForRuntime(), "no Task was initialized");
            }
            assertTrue(module.task().isEmpty());
        });
        System.out.println("PERF032_G6_VALID_CALL_COMPACT=PASS");
    }

    @Test
    void suppliedArgumentsEnterTheRejectionRootAndMaterializeOneActivation() throws Exception {
        withCore(module -> {
            ProtosClosureValue one = parsedClosure("() => { 1 }", "g6-reject.protos", module);
            RootCallTarget rejection = planRoot(one).arityRejectionTarget();

            ProtosIntegerValue supplied = integer(5);
            ProtosBytecodeRootNode.PreparedClosureCall single = fastDirect(one, module, supplied);
            assertSame(rejection, single.bodyTarget());
            assertThrows(ProtosSignalException.class, () -> enter(single));
            ProtosActivation activation =
                    assertInstanceOf(ProtosActivation.class, single.targetArguments()[0]);
            assertSame(activation, ProtosFrameArguments.activation(single.targetArguments()));
            assertEquals(List.of(supplied), activation.suppliedArgumentsForRuntime());

            ProtosBytecodeRootNode.PreparedClosureCall several =
                    fastDirect(one, module, integer(1), integer(2), integer(3));
            assertSame(rejection, several.bodyTarget());
            assertThrows(ProtosSignalException.class, () -> enter(several));
        });
        System.out.println("PERF032_G6_SINGLE_ARGUMENT_REJECTED=PASS");
        System.out.println("PERF032_G6_SEVERAL_ARGUMENTS_REJECTED=PASS");
        System.out.println("PERF032_G6_ONE_ERROR_ACTIVATION=PASS");
    }

    @Test
    void richActivationAndPlanExecutionKeepTheContract() throws Exception {
        withCore(module -> {
            ProtosClosureValue one = parsedClosure("() => { 1 }", "g6-rich.protos", module);
            ProtosClosureExecutionPlan plan = one.executionPlan().orElseThrow();

            assertEquals("1", integerValue(plan.executeBytecodeActivationForTesting(richActivation(one, module))));
            assertThrows(
                    ProtosSignalException.class,
                    () -> plan.executeBytecodeActivationForTesting(
                            richActivation(one, module, integer(1))));

            ProtosActivation rich = richActivation(one, module, integer(2));
            ProtosBytecodeRootNode.PreparedClosureCall prepared =
                    ProtosBytecodeRootNode.PreparedClosureCall.ordinary(target(one), rich);
            assertSame(planRoot(one).arityRejectionTarget(), prepared.bodyTarget());
            assertThrows(ProtosSignalException.class, () -> enter(prepared));
            assertSame(rich, prepared.targetArguments()[0], "no second activation");
        });
        System.out.println("PERF032_G6_RICH_ACTIVATION_REJECTED=PASS");
        System.out.println("PERF032_G6_PLAN_EXECUTION_REJECTED=PASS");
    }

    @Test
    void guestCallsAndSendsSignalTheSameHandleableErrorWithoutBodyEffects() throws Exception {
        ProtosExecutionOutcome outcome =
                execute(
                        "g6-guest.protos",
                        """
                        n: 0
                        bump: () => { n = n + 1 }
                        bumpOne: (x) => { n = n + 1 }
                        o: Object {
                            m: () => { n = n + 1 }
                        }
                        direct: Error.handle(() => { bump(1) }, (caught) => { caught })
                        many: Error.handle(() => { bump(1, 2) }, (caught) => { caught })
                        sent: Error.handle(() => { o.m(1) }, (caught) => { caught })
                        reference: Error.handle(() => { bumpOne(1, 2) }, (caught) => { caught })
                        valid: [bump(), o.m()]
                        [n, direct, many, sent, reference, valid]
                        """);
        assertEquals(ProtosExecutionOutcome.State.COMPLETED, outcome.state(), outcome::toString);
        List<Object> results = elements(outcome.value());

        assertEquals("2", integerValue(results.get(0)), "only the valid calls ran");
        ProtosObjectValue reference = assertInstanceOf(ProtosObjectValue.class, results.get(4));
        for (int index = 1; index <= 3; index++) {
            ProtosObjectValue caught = assertInstanceOf(ProtosObjectValue.class, results.get(index));
            assertSame(
                    reference.parent().orElseThrow(),
                    caught.parent().orElseThrow(),
                    "the same argument-count Error kind");
        }
        assertEquals(
                List.of("1", "2"),
                elements(results.get(5)).stream()
                        .map(ProtosPerf032G6ZeroParameterGuestRootTest::integerValue)
                        .toList());
        System.out.println("PERF032_G6_GUEST_DIRECT_CALL_CONTRACT=PASS");
        System.out.println("PERF032_G6_METHOD_SEND_CONTRACT=PASS");
        System.out.println("PERF032_G6_HANDLER_CAPTURE=PASS");
        System.out.println("PERF032_G6_NO_BODY_EFFECTS=PASS");
    }

    @Test
    void unhandledRejectionKeepsTheSourceFrameWithoutAFictitiousOne() throws Exception {
        ProtosDiagnosticTrace zero =
                failedTrace(
                        "g6-trace-zero.protos",
                        """
                        a: () => {
                            1
                        }
                        a(1)
                        """);
        ProtosDiagnosticTrace one =
                failedTrace(
                        "g6-trace-one.protos",
                        """
                        a: (x) => {
                            1
                        }
                        a(1, 2)
                        """);
        assertEquals(frames(one), frames(zero), "the in-body check produced the same frames");
        System.out.println("PERF032_G6_TRACE_SOURCE_FRAME=PASS");
    }

    @Test
    void rejectionRootSharesTheSourceSectionAcrossReparse() throws Exception {
        withCore(module -> {
            ProtosClosureValue one = parsedClosure("() => { 1 }", "g6-source.protos", module);
            ProtosSemanticBytecodeRootNode root = planRoot(one);
            RootCallTarget rejection = root.arityRejectionTarget();

            // Materializing source information reparses the whole group.
            root.getRootNodes().ensureSourceInformation();
            assertNotNull(root.getSourceSection());
            assertEquals(root.getSourceSection(), rejection.getRootNode().getSourceSection());
            assertSame(root, planRoot(one), "the normal root identity survives the reparse");
            assertSame(rejection, root.arityRejectionTarget());
            assertNone(instructionNames(root), UPPER_BOUND_CHECK);
            assertEquals("1", integerValue(enter(fastDirect(one, module))));
        });
        System.out.println("PERF032_G6_SOURCE_SECTION_AND_REPARSE=PASS");
    }

    @Test
    void taskOwnedEntryKeepsItsStateAndFailsWithTheError() throws Exception {
        withCore(module -> {
            ProtosClosureValue one = parsedClosure("() => { 1 }", "g6-task.protos", module);

            ProtosTask valid = runTaskOwned(one, module, List.of(), new AtomicReference<>());
            assertEquals(ProtosTask.State.COMPLETED, valid.state());
            assertEquals("1", integerValue(valid.result().orElseThrow()));

            AtomicReference<ProtosBytecodeRootNode.PreparedClosureCall> rejected =
                    new AtomicReference<>();
            ProtosTask failed = runTaskOwned(one, module, List.of(integer(1)), rejected);
            assertEquals(ProtosTask.State.FAILED, failed.state());
            assertTrue(failed.failure().isPresent());
            assertSame(planRoot(one).arityRejectionTarget(), rejected.get().bodyTarget());
            ProtosActivation activation =
                    ProtosFrameArguments.activation(rejected.get().targetArguments());
            assertSame(failed, activation.task().orElseThrow(), "the exact owning Task");
            assertTrue(module.task().isEmpty());
        });
        System.out.println("PERF032_G6_TASK_OWNED_CONTRACT=PASS");
    }

    @Test
    void closuresWithParametersKeepTheirInBodyAlgorithm() throws Exception {
        withCore(module -> {
            for (String characters :
                    List.of("(x) => { x }", "(x, y) => { y }", "(x = 1) => { x }")) {
                ProtosClosureValue closure = parsedClosure(characters, "g6-params.protos", module);
                ProtosSemanticBytecodeRootNode root = planRoot(closure);
                assertContains(instructionNames(root), UPPER_BOUND_CHECK);
                assertNull(root.arityRejectionTarget(), characters);
            }
            ProtosClosureValue rest = parsedClosure("(...r) => { r }", "g6-rest.protos", module);
            assertNone(instructionNames(planRoot(rest)), UPPER_BOUND_CHECK);
            assertNull(planRoot(rest).arityRejectionTarget());
        });

        ProtosExecutionOutcome outcome =
                execute(
                        "g6-defaults.protos",
                        """
                        d: (a = 7) => { a }
                        r: (a = 1, ...rest) => { [a, rest] }
                        excess: Error.handle(() => { d(1, 2) }, (caught) => { 0 })
                        [d(), d(3), r(), r(10, 20), excess]
                        """);
        assertEquals(ProtosExecutionOutcome.State.COMPLETED, outcome.state(), outcome::toString);
        List<Object> results = elements(outcome.value());
        assertEquals("7", integerValue(results.get(0)));
        assertEquals("3", integerValue(results.get(1)));
        assertEquals("1", integerValue(elements(results.get(2)).get(0)));
        assertTrue(elements(elements(results.get(2)).get(1)).isEmpty());
        assertEquals("10", integerValue(elements(results.get(3)).get(0)));
        assertEquals("20", integerValue(elements(elements(results.get(3)).get(1)).get(0)));
        assertEquals("0", integerValue(results.get(4)));
        System.out.println("PERF032_G6_PARAMETER_CLOSURES_UNCHANGED=PASS");
        System.out.println("PERF032_G6_DEFAULTS_AND_REST_UNCHANGED=PASS");
    }

    @Test
    void standardEmbeddingExecutePreservesTheGuestError() {
        try (Context context =
                Context.newBuilder(ProtosLanguage.ID)
                        .option("protos.CoreRoot", CORE.toAbsolutePath().toString())
                        .build()) {
            context.eval(
                    ProtosLanguage.ID,
                    """
                    run: () => {
                        1
                    }
                    0
                    """);
            Value run = context.getBindings(ProtosLanguage.ID).getMember("run");
            for (int index = 0; index < 3; index++) {
                assertEquals(1, run.execute().asInt());
            }
            PolyglotException arity = assertThrows(PolyglotException.class, () -> run.execute(1));
            assertTrue(arity.isGuestException());
            assertFalse(
                    languageContext(context).embeddedProcessOrNull().isLive(),
                    "an unhandled arity Error is fatal");
        }
        System.out.println("PERF032_G6_STANDARD_EMBEDDING_GUEST_ERROR=PASS");
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

    private static ProtosLanguageContext languageContext(Context context) {
        context.enter();
        try {
            return ProtosLanguageContext.current();
        } finally {
            context.leave();
        }
    }

    private static ProtosExecutionOutcome execute(String name, String source) throws Exception {
        return ProtosTestExecutionSupport.execute(
                name, source, new ProtosCoreBootstrap().bootstrap(CORE).newModuleActivation());
    }

    private static ProtosDiagnosticTrace failedTrace(String name, String source) throws Exception {
        ProtosExecutionOutcome outcome = execute(name, source);
        assertEquals(ProtosExecutionOutcome.State.FAILED, outcome.state(), outcome::toString);
        return outcome.failureDiagnosticTrace().orElseThrow();
    }

    private static List<String> frames(ProtosDiagnosticTrace trace) {
        return trace.frames().stream()
                .map(frame -> frame.line() + ":" + frame.column() + ":" + frame.label())
                .toList();
    }

    private static ProtosTask runTaskOwned(
            ProtosClosureValue closure,
            ProtosActivation module,
            List<?> supplied,
            AtomicReference<ProtosBytecodeRootNode.PreparedClosureCall> preparedRef) {
        ProtosTask task =
                module.executionDomain()
                        .createTask(
                                null,
                                current -> {
                                    ProtosBytecodeRootNode.PreparedClosureCall prepared =
                                            ProtosBytecodeRootNode
                                                    .prepareTaskOwnedDirectClosureIfBytecode(
                                                            closure, supplied, module, current);
                                    preparedRef.set(prepared);
                                    ProtosBytecodeTaskExecution.executePreparedClosure(
                                            current, prepared);
                                });
        assertTrue(module.executionDomain().dispatchOne());
        return task;
    }

    private static ProtosActivation richActivation(
            ProtosClosureValue closure, ProtosActivation module, Object... supplied) {
        ProtosActivation activation =
                ProtosActivation.forClosureInvocation(
                        closure,
                        List.of(supplied),
                        module.prelude().orElseThrow(),
                        module.actorModuleState(),
                        module.currentModuleKey().orElse(null),
                        module.executionDomain());
        activation.inheritDynamicControlState(module);
        return activation;
    }

    private static ProtosSemanticBytecodeRootNode planRoot(ProtosClosureValue closure) {
        return closure.executionPlan().orElseThrow().bytecodeActivationRootForTesting();
    }

    private static RootCallTarget target(ProtosClosureValue closure) {
        return ProtosBytecodeRootNode.PrepareSendArguments.fastOrdinarySendTarget(
                closure, ProtosLanguageContext.currentIfEnteredForRuntime());
    }

    private static ProtosBytecodeRootNode.PreparedClosureCall fastDirect(
            ProtosClosureValue closure, ProtosActivation caller, Object... supplied) {
        ProtosLanguageContext entered = ProtosLanguageContext.currentIfEnteredForRuntime();
        ProtosClosureValue selected =
                ProtosBytecodeRootNode.directClosureCallSelectionOrNull(closure, caller);
        assertSame(closure, selected, "canonical direct Closure-call selection");
        return ProtosBytecodeRootNode.PrepareClosureCallArguments.fastDirect(
                closure,
                caller,
                supplied,
                selected,
                selected.definition(),
                entered,
                selected.definition(),
                entered,
                target(closure));
    }

    private static Object enter(ProtosBytecodeRootNode.PreparedClosureCall prepared) {
        Object entered =
                ProtosBytecodeRootNode.EnterClosureCall.ordinaryIndirect(
                        assertInstanceOf(ProtosBytecodeRootNode.OrdinarySourceCall.class, prepared),
                        IndirectCallNode.create());
        return prepared.finish(entered);
    }

    private static Object evaluate(String characters, String name, ProtosActivation activation) {
        Source source =
                Source.newBuilder(ProtosLanguage.ID, characters, name)
                        .mimeType(ProtosLanguage.MIME_TYPE)
                        .build();
        CallTarget target = ProtosLanguageContext.current().parsePublic(source);
        return target.call(activation);
    }

    private static ProtosClosureValue parsedClosure(
            String characters, String name, ProtosActivation activation) {
        return assertInstanceOf(
                ProtosClosureValue.class, evaluate(characters, name, activation));
    }

    private static List<String> instructionNames(ProtosSemanticBytecodeRootNode root) {
        return root.getBytecodeNode().getInstructionsAsList().stream()
                .map(Instruction::getName)
                .toList();
    }

    private static void assertContains(List<String> names, String operation) {
        assertTrue(
                names.stream().anyMatch(name -> name.contains(operation)),
                () -> "expected " + operation + " in: " + names);
    }

    private static void assertNone(List<String> names, String operation) {
        assertTrue(
                names.stream().noneMatch(name -> name.contains(operation)),
                () -> "unexpected " + operation + " in: " + names);
    }

    private static List<Object> elements(Object array) {
        return assertInstanceOf(ProtosArrayValue.class, array).indexedSnapshot();
    }

    private static ProtosIntegerValue integer(long value) {
        return new ProtosIntegerValue(value);
    }

    private static String integerValue(Object value) {
        return com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerDecimalText(value);
    }
}
