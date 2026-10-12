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
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.guillermomolina.protos.runtime.ProtosActivation;
import com.guillermomolina.protos.runtime.ProtosBooleanValue;
import com.guillermomolina.protos.runtime.ProtosClosureValue;
import com.guillermomolina.protos.runtime.ProtosCoreErrors;
import com.guillermomolina.protos.runtime.ProtosNullValue;
import com.guillermomolina.protos.runtime.ProtosFloatValue;
import com.guillermomolina.protos.runtime.ProtosIntegerValue;
import com.guillermomolina.protos.runtime.ProtosReturnHome;
import com.guillermomolina.protos.runtime.ProtosPrelude;
import com.guillermomolina.protos.runtime.ProtosSignalException;
import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.bytecode.Instruction;
import com.oracle.truffle.api.bytecode.TagTree;
import com.oracle.truffle.api.instrumentation.StandardTags;
import com.oracle.truffle.api.nodes.IndirectCallNode;
import com.oracle.truffle.api.source.Source;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;

/**
 * PERF037-E regression coverage for directly returning eligible terminal
 * Sequence expressions without storing and reloading sequenceResult.
 */
final class ProtosPerf037EDirectTerminalReturnTest {
    private static final Path CORE = Path.of("protos", "lib", "core");

    @Test
    void singleTerminalValuesNeedNoSequenceResult() throws Exception {
        withCore(module -> {
            evaluate("holder: { value: 23 }\nmarker: holder.value", module);

            for (String body : List.of(
                    "() => { 19 }",
                    "() => { marker }",
                    "() => { holder.value }",
                    "() => { marker === holder.value }",
                    "() => { marker !== holder }",
                    "() => { (holder.value === marker) !== false }")) {
                ProtosClosureValue candidate = closure(body, module);
                assertFalse(
                        localNames(candidate).contains("sequenceResult"),
                        () -> "unexpected terminal result local: " + body
                                + " " + localNames(candidate));
            }

            assertEquals("19", integerValue(call(closure("() => { 19 }", module), module)));
            assertEquals("23", integerValue(call(closure("() => { marker }", module), module)));
            assertEquals("23", integerValue(call(closure("() => { holder.value }", module), module)));

            assertSame(
                    ProtosBooleanValue.TRUE,
                    call(closure("() => { marker === holder.value }", module), module));
            assertSame(
                    ProtosBooleanValue.TRUE,
                    call(closure("() => { marker !== holder }", module), module));
            assertSame(
                    ProtosBooleanValue.TRUE,
                    call(
                            closure(
                                    "() => { (holder.value === marker) !== false }",
                                    module),
                            module));
        });
    }

    @Test
    void terminalFloatReturnsPrimitiveOnlyToAcceptingCaller() throws Exception {
        withCore(module -> {
            ProtosClosureValue run = closure("() => { 1.5 }", module);
            ProtosSemanticBytecodeRootNode root =
                    run.executionPlan()
                            .orElseThrow()
                            .bytecodeActivationRootForTesting();

            root.getRootNodes().ensureComplete();
            assertTrue(
                    root.getBytecodeNode().getInstructionsAsList().stream()
                            .anyMatch(instruction ->
                                    instruction.getName()
                                            .contains("MaterializeReturnCarrier")));

            assertFalse(localNames(run).contains("sequenceResult"));
            assertEquals(
                    1.5d,
                    assertInstanceOf(ProtosFloatValue.class, call(run, module)).value());

            ProtosClosureValue accepting =
                    closure("(ignored) => { 1.5 }", module);

            Object[] arguments =
                    ProtosFrameArguments.compactDirectClosureCallOnePrepared(
                            accepting,
                            module,
                            ProtosReturnHome
                                    .unobservableAcceptingPrimitiveResultForRuntime(),
                            new ProtosIntegerValue(7L));

            assertTrue(ProtosFrameArguments.acceptsPrimitiveResult(arguments));

            Object result =
                    accepting.executionPlan()
                            .orElseThrow()
                            .bytecodeActivationRootForTesting()
                            .getCallTarget()
                            .call(arguments);

            assertEquals(
                    1.5d,
                    assertInstanceOf(Double.class, result).doubleValue());
        });
    }

    @Test
    void earlierStatementsExecuteOnceInSourceOrder() throws Exception {
        withCore(module -> {
            evaluate("counter: 0\nholder: { value: 23 }", module);

            ProtosClosureValue run =
                    closure(
                            "() => {\n"
                                    + "  counter = counter + 1\n"
                                    + "  holder.value\n"
                                    + "}",
                            module);

            assertEquals("23", integerValue(call(run, module)));
            assertEquals("1", integerValue(evaluate("counter", module)));

            assertEquals("23", integerValue(call(run, module)));
            assertEquals("2", integerValue(evaluate("counter", module)));
        });
    }

    @Test
    void missingFinalLookupPreservesSlotNotFound() throws Exception {
        withCore(module -> {
            ProtosClosureValue run =
                    closure("() => { absentPerf037E }", module);
            ProtosSignalException signal =
                    assertThrows(
                            ProtosSignalException.class,
                            () -> call(run, module));

            assertSame(
                    ProtosCoreErrors.prototype(
                            module,
                            ProtosCoreErrors.StandardError.SLOT_NOT_FOUND),
                    signal.error().parent().orElse(null));
        });
    }

    @Test
    void emptyBodyReturnsCanonicalNull() throws Exception {
        withCore(module -> {
            ProtosClosureValue empty = closure("() => {}", module);
            assertSame(ProtosNullValue.INSTANCE, call(empty, module));
            assertFalse(localNames(empty).contains("sequenceResult"));
        });
    }

    @Test
    void nonLocalReturnPreservesHomeTransfer() throws Exception {
        withCore(module -> {
            Object result =
                    evaluate(
                            "outer: () => {\n"
                                    + "  inner: (value) => { ^ value }\n"
                                    + "  inner(7)\n"
                                    + "  99\n"
                                    + "}\n"
                                    + "outer()",
                            module);

            assertEquals("7", integerValue(result));
        });
    }

    @Test
    void composedTerminalCallRetainsOriginalLowering() throws Exception {
        withCore(module -> {
            ProtosClosureValue composed =
                    closure(
                            "() => {\n"
                                    + "  nested: () => 31\n"
                                    + "  nested()\n"
                                    + "}",
                            module);

            assertTrue(
                    localNames(composed).contains("sequenceResult"),
                    "composed terminal calls retain the original result local");
            assertEquals("31", integerValue(call(composed, module)));
        });
    }

    @Test
    void directTerminalMemberKeepsStatementAndExpressionTags()
            throws Exception {
        withCore(module -> {
            evaluate("holder: { value: 23 }", module);

            ProtosClosureValue run =
                    closure(
                            "() => {\n"
                                    + "  holder.value\n"
                                    + "}",
                            module);

            ProtosSemanticBytecodeRootNode root =
                    run.executionPlan()
                            .orElseThrow()
                            .bytecodeActivationRootForTesting();

            root.getRootNodes().ensureComplete();
            TagTree tree = root.getBytecodeNode().getTagTree();

            List<TagTree> statements =
                    collectTags(tree, StandardTags.StatementTag.class);
            List<TagTree> expressions =
                    collectTags(tree, StandardTags.ExpressionTag.class);

            assertEquals(
                    List.of("holder.value"),
                    statements.stream()
                            .map(tag ->
                                    tag.getSourceSection()
                                            .getCharacters()
                                            .toString())
                            .toList());
            assertEquals(statements.size(), expressions.size());
            for (int index = 0; index < statements.size(); index++) {
                assertSame(
                        statements.get(index),
                        expressions.get(index));
            }

            assertFalse(localNames(run).contains("sequenceResult"));
            assertEquals("23", integerValue(call(run, module)));
        });
    }

    @Test
    void rootMemberNamesAreConstantOperands() throws Exception {
        withCore(module -> {
            evaluate(
                    "holder: { value: 23\n other: 41 }",
                    module);

            ProtosClosureValue run =
                    closure(
                            "() => {\n"
                                    + " holder.value\n"
                                    + " holder.other\n"
                                    + "}",
                            module);

            List<Instruction> reads =
                    run.executionPlan()
                            .orElseThrow()
                            .bytecodeActivationRootForTesting()
                            .getBytecodeNode()
                            .getInstructionsAsList()
                            .stream()
                            .filter(instruction ->
                                    instruction.getName()
                                            .contains("ReadMemberAtRoot"))
                            .toList();

            assertEquals(
                    List.of("value", "other"),
                    reads.stream()
                            .map(instruction ->
                                    instruction.getArguments()
                                            .stream()
                                            .filter(argument ->
                                                    argument.getName()
                                                            .equals("name"))
                                            .findFirst()
                                            .orElseThrow()
                                            .asConstant())
                            .toList());

            assertFalse(localNames(run).contains("sequenceResult"));
            assertEquals("41", integerValue(call(run, module)));
        });
    }

    @Test
    void discardedEarlierLookupStillPropagatesError()
            throws Exception {
        withCore(module -> {
            ProtosClosureValue run =
                    closure(
                            "() => {\n"
                                    + " missingPerf037EPrefix\n"
                                    + " 47\n"
                                    + "}",
                            module);

            assertFalse(localNames(run).contains("sequenceResult"));

            ProtosSignalException signal =
                    assertThrows(
                            ProtosSignalException.class,
                            () -> call(run, module));

            assertSame(
                    ProtosCoreErrors.prototype(
                            module,
                            ProtosCoreErrors.StandardError.SLOT_NOT_FOUND),
                    signal.error().parent().orElse(null));
        });
    }

    @Test
    void constantMemberNamePreservesUpdatesAndFreshExtraction()
            throws Exception {
        withCore(module -> {
            evaluate(
                    "holder: { value: 23\n method: () => 3 }",
                    module);

            ProtosClosureValue valueReader =
                    closure("() => { holder.value }", module);

            assertEquals("23", integerValue(call(valueReader, module)));

            evaluate("holder.value = 55", module);

            assertEquals("55", integerValue(call(valueReader, module)));

            ProtosClosureValue methodReader =
                    closure("() => { holder.method }", module);

            Object first = call(methodReader, module);
            Object second = call(methodReader, module);

            assertInstanceOf(ProtosClosureValue.class, first);
            assertInstanceOf(ProtosClosureValue.class, second);
            assertNotSame(first, second);
        });
    }

    @Test
    void terminalClosureReturnsDirectlyWithoutSequenceResult()
            throws Exception {
        withCore(module -> {
            ProtosClosureValue supplier =
                    closure("() => { () => 37 }", module);

            assertFalse(
                    localNames(supplier).contains("sequenceResult"));

            ProtosClosureValue returned =
                    assertInstanceOf(
                            ProtosClosureValue.class,
                            call(supplier, module));

            assertEquals("37", integerValue(call(returned, module)));
        });
    }

    @Test
    void mixedPrefixOnlyStagesExpressionsThatNeedIt()
            throws Exception {
        withCore(module -> {
            evaluate(
                    "holder: { value: 23 }\n"
                            + "counter: 0",
                    module);

            ProtosClosureValue run =
                    closure(
                            "() => {\n"
                                    + "  holder.value\n"
                                    + "  counter = counter + 1\n"
                                    + "  holder.value\n"
                                    + "}",
                            module);

            assertEquals("23", integerValue(call(run, module)));
            assertEquals("1", integerValue(evaluate("counter", module)));

            assertEquals("23", integerValue(call(run, module)));
            assertEquals("2", integerValue(evaluate("counter", module)));
        });
    }

    @Test
    void discardedClosurePrefixNeedsNoSequenceResult()
            throws Exception {
        withCore(module -> {
            ProtosClosureValue run =
                    closure(
                            "() => {\n"
                                    + "  () => 29\n"
                                    + "  41\n"
                                    + "}",
                            module);

            assertFalse(localNames(run).contains("sequenceResult"));
            assertEquals("41", integerValue(call(run, module)));
        });
    }

    @Test
    void discardedScalarReadKeepsCorrectValueAndOrder()
            throws Exception {
        withCore(module -> {
            ProtosClosureValue run =
                    closure(
                            "() => {\n"
                                    + "  local: 9\n"
                                    + "  local\n"
                                    + "  local\n"
                                    + "}",
                            module);

            assertEquals("9", integerValue(call(run, module)));
        });
    }

    @Test
    void composedTerminalPreservesEffectsOfDiscardedPrefix()
            throws Exception {
        withCore(module -> {
            evaluate(
                    "counter: 0\n"
                            + "holder: { value: 23 }",
                    module);

            ProtosClosureValue run =
                    closure(
                            "() => {\n"
                                    + "  holder.value\n"
                                    + "  counter = counter + 1\n"
                                    + "  holder.value\n"
                                    + "  nested: () => counter\n"
                                    + "  nested()\n"
                                    + "}",
                            module);

            assertTrue(
                    localNames(run).contains("sequenceResult"),
                    "composed final calls retain final-result storage");

            assertEquals("1", integerValue(call(run, module)));
            assertEquals("2", integerValue(call(run, module)));
            assertEquals("2", integerValue(evaluate("counter", module)));
        });
    }

    @Test
    void discardedMemberReadStillRaisesItsExactError()
            throws Exception {
        withCore(module -> {
            evaluate("holder: { value: 23 }", module);

            ProtosClosureValue run =
                    closure(
                            "() => {\n"
                                    + "  holder.absentPerf037E\n"
                                    + "  99\n"
                                    + "}",
                            module);

            ProtosSignalException signal =
                    assertThrows(
                            ProtosSignalException.class,
                            () -> call(run, module));

            assertSame(
                    ProtosCoreErrors.prototype(
                            module,
                            ProtosCoreErrors.StandardError.SLOT_NOT_FOUND),
                    signal.error().parent().orElse(null));
        });
    }

    @Test
    void multipleComposedPrefixCallsKeepExecutionOrder()
            throws Exception {
        withCore(module -> {
            evaluate(
                    "counter: 0\n"
                            + "step: () => {\n"
                            + "  counter = counter + 1\n"
                            + "  counter\n"
                            + "}",
                    module);

            ProtosClosureValue run =
                    closure(
                            "() => {\n"
                                    + "  step()\n"
                                    + "  step()\n"
                                    + "  counter\n"
                                    + "}",
                            module);

            assertEquals("2", integerValue(call(run, module)));
            assertEquals("4", integerValue(call(run, module)));
        });
    }

    @Test
    void composedPrefixAndTerminalShareExecutionState()
            throws Exception {
        withCore(module -> {
            evaluate(
                    "counter: 0\n"
                            + "step: () => {\n"
                            + "  counter = counter + 1\n"
                            + "  counter\n"
                            + "}",
                    module);

            ProtosClosureValue run =
                    closure(
                            "() => {\n"
                                    + "  step()\n"
                                    + "  step()\n"
                                    + "  step()\n"
                                    + "}",
                            module);

            assertTrue(localNames(run).contains("sequenceResult"));

            assertEquals("3", integerValue(call(run, module)));
            assertEquals("6", integerValue(call(run, module)));
        });
    }

    @Test
    void rootPlainMemberDeoptimizesBeforeClosureExtraction()
            throws Exception {
        withCore(module -> {
            evaluate(
                    "holder: { value: 17 }",
                    module);

            ProtosClosureValue reader =
                    closure(
                            "() => { holder.value }",
                            module);

            for (int index = 0; index < 32; index++) {
                assertEquals("17", integerValue(call(reader, module)));
            }

            evaluate(
                    "holder.value = () => 81",
                    module);

            ProtosClosureValue first =
                    assertInstanceOf(
                            ProtosClosureValue.class,
                            call(reader, module));
            ProtosClosureValue second =
                    assertInstanceOf(
                            ProtosClosureValue.class,
                            call(reader, module));

            assertNotSame(
                    first,
                    second,
                    "Closure extraction must remain fresh");

            assertEquals("81", integerValue(call(first, module)));

            evaluate(
                    "holder.value = 19",
                    module);

            assertEquals("19", integerValue(call(reader, module)));
        });
    }

    @FunctionalInterface
    private interface CoreTest {
        void run(ProtosActivation module) throws Exception;
    }

    private static void withCore(CoreTest test) throws Exception {
        try (Context context =
                Context.newBuilder(ProtosLanguage.ID).build()) {
            context.initialize(ProtosLanguage.ID);
            context.enter();
            try {
                ProtosPrelude prelude =
                        new ProtosCoreBootstrap().bootstrap(CORE);
                test.run(prelude.newModuleActivation());
            } finally {
                context.leave();
            }
        }
    }

    private static Object evaluate(
            String characters,
            ProtosActivation activation) {
        Source source =
                Source.newBuilder(
                                ProtosLanguage.ID,
                                characters,
                                "perf037e-terminal-return.protos")
                        .mimeType(ProtosLanguage.MIME_TYPE)
                        .build();
        CallTarget target =
                ProtosLanguageContext.current().parsePublic(source);
        return target.call(activation);
    }

    private static ProtosClosureValue closure(
            String characters,
            ProtosActivation activation) {
        return assertInstanceOf(
                ProtosClosureValue.class,
                evaluate(characters, activation));
    }

    private static Object call(
            ProtosClosureValue closure,
            ProtosActivation caller) {
        ProtosLanguageContext entered =
                ProtosLanguageContext.currentIfEnteredForRuntime();

        ProtosClosureValue selected =
                ProtosBytecodeRootNode
                        .directClosureCallSelectionOrNull(
                                closure, caller);
        assertSame(closure, selected);

        RootCallTarget target =
                ProtosBytecodeRootNode.PrepareSendArguments
                        .fastOrdinarySendTarget(closure, entered);

        ProtosBytecodeRootNode.PreparedClosureCall prepared =
                ProtosBytecodeRootNode.PrepareClosureCallArguments
                        .fastDirect(
                                closure,
                                caller,
                                new Object[0],
                                selected,
                                selected.definition(),
                                entered,
                                selected.definition(),
                                entered,
                                target);

        Object result =
                ProtosBytecodeRootNode.EnterClosureCall
                        .ordinaryIndirect(
                                assertInstanceOf(
                                        ProtosBytecodeRootNode
                                                .OrdinarySourceCall.class,
                                        prepared),
                                IndirectCallNode.create());

        return prepared.finish(result);
    }

    private static String integerValue(Object value) {
        return com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerDecimalText(value);
    }

    private static List<Object> localNames(
            ProtosClosureValue closure) {
        return closure.executionPlan()
                .orElseThrow()
                .bytecodeActivationRootForTesting()
                .getBytecodeNode()
                .getLocals()
                .stream()
                .map(local -> local.getName())
                .toList();
    }

    private static List<TagTree> collectTags(
            TagTree tree,
            Class<? extends com.oracle.truffle.api.instrumentation.Tag> tag) {
        List<TagTree> result = new ArrayList<>();
        if (tree == null) {
            return result;
        }
        if (tree.hasTag(tag)) {
            result.add(tree);
        }
        for (TagTree child : tree.getTreeChildren()) {
            result.addAll(collectTags(child, tag));
        }
        return result;
    }
}
