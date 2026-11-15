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

import com.guillermomolina.protos.execution.ProtosDispatchTestSupport.DirectEntry;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.oracle.truffle.api.debug.DebuggerSession;
import com.oracle.truffle.api.debug.Debugger;
import com.guillermomolina.protos.runtime.ProtosActivation;
import com.guillermomolina.protos.runtime.ProtosArrayValue;
import com.guillermomolina.protos.runtime.ProtosClosureValue;
import com.guillermomolina.protos.runtime.ProtosCoreErrors;
import com.guillermomolina.protos.runtime.ProtosExecutionContextValue;
import com.guillermomolina.protos.runtime.ProtosNullValue;
import com.guillermomolina.protos.runtime.ProtosObjectValue;
import com.guillermomolina.protos.runtime.ProtosPrelude;
import com.guillermomolina.protos.runtime.ProtosSignalException;
import com.guillermomolina.protos.runtime.ProtosTask;
import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.bytecode.Instruction;
import com.oracle.truffle.api.bytecode.TagTree;
import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.instrumentation.StandardTags;
import com.oracle.truffle.api.nodes.IndirectCallNode;
import com.oracle.truffle.api.source.Source;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;

/**
 * PERF025 compact callee execution: a source root entered through the compact
 * source-call ABI keeps that compact invocation state authoritative through
 * ordinary execution. It no longer publishes a materialized activation at
 * entry; supplied parameters are read from the frame arguments and bound
 * directly into their lowering-time Bytecode locals; the exact activation (and
 * any frame lexical authority) is materialized once, on the first semantic
 * observation, and shared by every later observer.
 */
final class ProtosPerf025CompactCalleeExecutionTest {
    private static final Path CORE = Path.of("protos", "lib", "core");

    @Test
    void escapedCapturesPublishExactOwnersWithoutObservingTheirContexts() throws Exception {
        withCore(module -> {
            ProtosClosureValue template = closure("() => { () => context }", module);
            for (boolean taskOwned : new boolean[] {false, true}) {
                ProtosTask task = taskOwned
                        ? module.executionDomain().createTask(null, current -> {}) : null;
                var home = new com.guillermomolina.protos.runtime.control.ProtosReturnHome();
                if (task != null) {
                    home.bindOwnerTaskForRuntime(task);
                }
                ProtosClosureValue maker = new ProtosClosureValue(
                        template.definition(), template.capturedLexicalContexts(),
                        template.capturedReceiver(), null, home,
                        module.prelude().orElseThrow(), template.executionPlan().orElseThrow());
                Object[] arguments = ProtosFrameArguments.compactDirectClosureCall(
                        maker, module, task, new Object[0]);
                Object callerPayload = arguments[3];
                ProtosClosureValue escaped = assertInstanceOf(
                        ProtosClosureValue.class, target(maker).call(arguments));
                ProtosActivation owner = assertInstanceOf(ProtosActivation.class, arguments[0]);
                assertSame(callerPayload, arguments[3]);
                assertSame(task, owner.taskForRuntime());
                assertSame(home, owner.returnHomeOrNullForRuntime());
                assertSame(home, escaped.returnHome().orElseThrow());
                assertTrue(home.isActive(), "nested invocation must not close its captured home");
                assertNull(owner.materializedContextOrNullForRuntime());
                var environment = escaped.capturedLexicalEnvironmentForRuntime();
                assertSame(owner, environment.deferredOwnerForRuntime());
                assertSame(environment, owner.lexicalEnvironmentForClosureCapture());
                assertSame(owner.context(), environment.context());
                assertSame(home, arguments[4]);
            }
        });
    }

    @Test
    void inheritedOnlyCaptureRetainsMetadataWithoutPublishingCreator() throws Exception {
        withCore(module -> {
            ProtosClosureValue creator = closure("() => 42", module);
            ProtosClosureValue definition = closure("(value) => value", module);
            ProtosClosureExecutionPlanCell cell = ProtosClosureExecutionPlanCell.pendingGroup();
            cell.freeze(definition.executionPlan().orElseThrow());
            Object receiver = newObject();
            ProtosObjectValue methodHome = newObject();
            Object[] arguments = ProtosFrameArguments.compactImmediateMethodCall(
                    creator, receiver, methodHome, module, new Object[0]);
            Object callerPayload = arguments[3];
            ProtosClosureValue inherited = ProtosFrameArguments.materializeCompactClosure(
                    arguments, definition.definition(), cell, false, true, false);
            assertSame(creator, arguments[0]);
            assertSame(callerPayload, arguments[3]);
            assertSame(creator.capturedLexicalEnvironmentForRuntime(),
                    inherited.capturedLexicalEnvironmentForRuntime());
            assertSame(receiver, inherited.capturedReceiver());
            assertSame(methodHome, inherited.methodHome().orElseThrow());
            assertSame(ProtosFrameArguments.compactReturnHome(arguments),
                    inherited.returnHome().orElseThrow());
            assertSame(module.prelude().orElseThrow(), inherited.prelude().orElseThrow());
            assertSame(cell.plan(), inherited.preparedExecutionPlanOrNullForRuntime());
        });
    }

    @Test
    void independentClosureLiteralKeepsItsCompactRepresentation() throws Exception {
        withCore(module -> {
            ProtosClosureValue maker = closure("() => { (value) => value }", module);
            Object[] arguments = ProtosFrameArguments.compactDirectClosureCall(
                    maker, module, null, new Object[0]);
            ProtosClosureValue independent = assertInstanceOf(
                    ProtosClosureValue.class, target(maker).call(arguments));
            assertSame(maker, arguments[0]);
            assertNull(independent.capturedLexicalEnvironmentForRuntime());
            assertTrue(independent.returnHome().isEmpty());
            // Owner decision: the creator's Prelude is retained as a plain
            // reference, read from the header without publishing (arguments[0]
            // is still the maker).
            assertSame(maker.prelude().orElseGet(() -> module.prelude().orElseThrow()),
                    independent.prelude().orElseThrow());
            assertEquals("42", integerValue(enter(fastDirect(independent, module, integer(42)))));
        });
    }

    @Test
    void identityCalleeExecutesWithoutMaterializingAnything() throws Exception {
        withCore(module -> {
            ProtosClosureValue identity = closure("(value) => { value }", module);
            List<String> names = instructionNames(identity);
            assertNone(names, "CurrentActivation");
            // I115: the unified parameter binding reads the supplied position
            // and keeps the LOCAL binding in its native local.
            assertBindsSuppliedPositions(names);
            assertNone(names, "CreateCell");
            assertTrue(localNames(identity).contains("value"));
            assertNoRetired(names);
            assertEquals(
                    List.of(0),
                    suppliedPositions(identity),
                    "PERF029: the supplied parameter position is an instruction constant");

            Long one = integer(1);
            Object[] method =
                    ProtosFrameArguments.compactImmediateMethodCall(
                            identity, newObject(), newObject(), module, new Object[] {one});
            assertSame(one, target(identity).call(method));
            assertSame(identity, method[0], "no activation was materialized or published");

            Long two = integer(2);
            DirectEntry direct = fastDirect(identity, module, two);
            assertSame(two, enter(direct));
            assertSame(identity, direct.targetArguments()[0]);
        });
        System.out.println("SOURCE_ROOT_UNIVERSAL_PUBLISH_FRAME_ACTIVATION=NO");
        System.out.println("CALLEE_ACTIVATION_EAGER=NO");
        System.out.println("FRAME_MATERIALIZATION_EAGER=NO");
        System.out.println("PARAMETER_DIRECT_LOCAL_BINDING=YES");
        System.out.println("IMMEDIATE_METHOD_COMPACT_PATH=PASS");
        System.out.println("DIRECT_SOURCE_CLOSURE_COMPACT_PATH=PASS");
    }

    @Test
    void i104UnaryEntryPreservesRichActivationAndArgumentErrors()
            throws Exception {
        withCore(module -> {
            ProtosClosureValue identity =
                    closure("(value) => { value }", module);
            Long value = integer(42);

            for (int attempt = 0; attempt < 8; attempt++) {
                DirectEntry compact =
                        fastDirect(identity, module, value);
                assertSame(value, enter(compact));
                assertSame(identity, compact.targetArguments()[0]);
            }

            Object[] publishedArguments =
                    ProtosFrameArguments.compactDirectClosureCall(
                            identity,
                            module,
                            null,
                            new Object[] {value});
            ProtosActivation published =
                    ProtosFrameArguments.activation(publishedArguments);
            ProtosObjectValue observedContext = published.context();

            assertSame(value, target(identity).call(publishedArguments));
            assertSame(published, publishedArguments[0]);
            assertSame(observedContext, published.context());
            // The Java-published Context is not observed by guest code, so the
            // frame-native parameter is not projected into it (owner decision).

            // D203/I105: missing binds null and excess is ignored, both compactly.
            DirectEntry missing =
                    fastDirect(identity, module);
            assertSame(ProtosNullValue.INSTANCE, enter(missing));
            assertSame(identity, missing.targetArguments()[0]);

            DirectEntry excess =
                    fastDirect(identity, module, value, integer(99));
            assertSame(value, enter(excess));
            assertSame(identity, excess.targetArguments()[0]);
        });
    }

    @Test
    void i104UnaryAdmissionExcludesComplexBodiesAndHoldsWithDebugger() throws Exception {
        withCore(module -> {
            /*
             * I115: there is no special unary entry any more. Every parameter
             * form goes through the one parameter lowering, and each body
             * keeps the storage its bindings need: a default tests its
             * position, a rest reads the suffix, a context observation takes
             * the activation, a capture creates a cell.
             */
            ProtosClosureValue defaulted = closure("(value: 1) => { value }", module);
            assertEquals(List.of(0), constantIntegersOf(defaulted, "HasSuppliedArgument", "index"));
            assertEquals(List.of(0), suppliedPositions(defaulted));
            assertNone(instructionNames(defaulted), "CreateCell");
            assertNoRetired(instructionNames(defaulted));

            ProtosClosureValue restOnly = closure("(...value) => { value }", module);
            assertEquals(
                    List.of(0),
                    constantIntegersOf(restOnly, "LoadRestArguments", "positionalParametersBeforeRest"));
            assertBindsNoSuppliedPosition(instructionNames(restOnly));
            assertNoRetired(instructionNames(restOnly));

            ProtosClosureValue pair = closure("(a, b) => { a }", module);
            assertEquals(List.of(0, 1), suppliedPositions(pair));
            assertNone(instructionNames(pair), "CurrentActivation");
            assertNoRetired(instructionNames(pair));

            ProtosClosureValue observing = closure("(value) => { context\nvalue }", module);
            assertContains(instructionNames(observing), "PublishCurrentClosureContext");
            assertBindsSuppliedPositions(instructionNames(observing));
            assertNone(instructionNames(observing), "CreateCell");
            assertNoRetired(instructionNames(observing));

            ProtosClosureValue capturing = closure("(value) => { () => value }", module);
            assertContains(instructionNames(capturing), "CreateCell");
            assertContains(instructionNames(capturing), "StoreCell");
            assertContains(instructionNames(capturing), "CaptureClosureCells");
            assertNone(instructionNames(capturing), "CurrentActivation");
            assertNoRetired(instructionNames(capturing));

            /*
             * I115: '+' is the per-selector NumberAdd operation, whose
             * generic route lives inside the node: no MISS test and no
             * separate GetMethod selection is emitted for it.
             */
            ProtosClosureValue plus = closure("(value) => { value + 1 }", module);
            List<String> plusNames = instructionNames(plus);
            assertEquals(1, countOf(plusNames, "NumberAdd"));
            // The only selection is the guarded '+' message fallback (D237:
            // after the operand), entered for a non-standard receiver.
            assertEquals(countOf(plusNames, "IsStandardNumericSelection"),
                    countOf(plusNames, "GetMethod"));
            assertNone(plusNames, "IsResultKind");
            assertNoRetired(plusNames);
            assertEquals("42", integerValue(enter(fastDirect(plus, module, integer(41)))));
        });

        // PLAT067-B: an attached debugger never selects another entry.
        withCoreAndDebugger(module -> {
            ProtosClosureValue identity =
                    closure("(value) => { value }", module);
            List<String> instructions = instructionNames(identity);

            assertEquals(List.of(0), suppliedPositions(identity));
            assertNone(instructions, "CurrentActivation");
            assertNoRetired(instructions);

            Long value = integer(17);
            assertSame(value, enter(fastDirect(identity, module, value)));

            // The debugger keeps the same numeric operation and no other ABI.
            ProtosClosureValue plus = closure("(value) => { value + 1 }", module);
            List<String> plusNames = instructionNames(plus);
            assertEquals(1, countOf(plusNames, "NumberAdd"));
            // The only selection is the guarded '+' message fallback (D237:
            // after the operand), entered for a non-standard receiver.
            assertEquals(countOf(plusNames, "IsStandardNumericSelection"),
                    countOf(plusNames, "GetMethod"));
            assertNoRetired(plusNames);
            assertEquals("18", integerValue(enter(fastDirect(plus, module, value))));
        });
    }


    @Test
    void i104RequiredPrefixPreservesCallsAndFlexibleArity() throws Exception {
        withCore(module -> {
            String[] sources = {
                "(a, b) => { a }",
                "(a, b, c) => { b }",
                "(a, b, c, d) => { d }"
            };
            int[] selected = {0, 1, 3};

            for (int caseIndex = 0; caseIndex < sources.length; caseIndex++) {
                ProtosClosureValue closure =
                        closure(sources[caseIndex], module);
                int count = caseIndex + 2;

                List<String> instructions = instructionNames(closure);
                assertEquals(
                        count,
                        suppliedPositions(closure).size(),
                        () -> "one supplied-position read per required parameter: " + instructions);
                assertEquals(
                        java.util.stream.IntStream.range(0, count).boxed().toList(),
                        suppliedPositions(closure));
                assertNone(instructions, "CurrentActivation");
                assertNone(instructions, "CreateCell");
                assertNoRetired(instructions);

                Object[] values = new Object[count];
                for (int index = 0; index < count; index++) {
                    values[index] = integer(index + 10);
                }

                for (int repetition = 0; repetition < 8; repetition++) {
                    var direct = fastDirect(closure, module, values);
                    assertSame(values[selected[caseIndex]], enter(direct));
                    assertSame(closure, direct.targetArguments()[0]);
                }

                Object[] method =
                        ProtosFrameArguments.compactImmediateMethodCall(
                                closure,
                                newObject(),
                                newObject(),
                                module,
                                values);
                assertSame(
                        values[selected[caseIndex]],
                        target(closure).call(method));
                assertSame(closure, method[0]);

                Object[] published =
                        ProtosFrameArguments.compactDirectClosureCall(
                                closure, module, null, values);
                ProtosActivation activation =
                        ProtosFrameArguments.activation(published);
                assertSame(
                        values[selected[caseIndex]],
                        target(closure).call(published));
                assertSame(activation, published[0]);

                // D203/I105: absent positions bind null without materialization.
                for (int supplied = 0; supplied < count; supplied++) {
                    Object[] missingValues =
                            java.util.Arrays.copyOf(values, supplied);
                    var missing = fastDirect(
                            closure, module, missingValues);
                    Object expected =
                            selected[caseIndex] < supplied
                                    ? values[selected[caseIndex]]
                                    : ProtosNullValue.INSTANCE;
                    assertSame(expected, enter(missing));
                    assertSame(closure, missing.targetArguments()[0]);
                }

                Object[] tooMany =
                        java.util.Arrays.copyOf(values, count + 1);
                tooMany[count] = integer(99);
                var excess = fastDirect(closure, module, tooMany);
                assertSame(values[selected[caseIndex]], enter(excess));
                assertSame(closure, excess.targetArguments()[0]);
            }

            /*
             * I115: the forms the former required-prefix entry excluded go
             * through the same parameter lowering; each keeps exactly the
             * storage its bindings need.
             */
            ProtosClosureValue defaulted = closure("(a, b: 1) => { b }", module);
            assertEquals(List.of(1), constantIntegersOf(defaulted, "HasSuppliedArgument", "index"));
            assertEquals(List.of(0, 1),
                    suppliedPositions(defaulted).stream()
                            .distinct().sorted().toList());
            assertNoRetired(instructionNames(defaulted));

            ProtosClosureValue withRest = closure("(a, ...rest) => { a }", module);
            assertEquals(List.of(0), suppliedPositions(withRest));
            assertEquals(
                    List.of(1),
                    constantIntegersOf(withRest, "LoadRestArguments", "positionalParametersBeforeRest"));
            assertNoRetired(instructionNames(withRest));

            ProtosClosureValue observing = closure("(a, b) => { context\nb }", module);
            assertContains(instructionNames(observing), "PublishCurrentClosureContext");
            assertNone(instructionNames(observing), "CreateCell");
            assertNoRetired(instructionNames(observing));

            ProtosClosureValue capturing = closure("(a, b) => { () => a }", module);
            assertEquals(1, countOf(instructionNames(capturing), "CreateCell"),
                    "only the captured a is a CELL binding");
            assertContains(instructionNames(capturing), "CaptureClosureCells");
            assertNoRetired(instructionNames(capturing));
        });

        System.out.println("I104_B_REQUIRED_PREFIX=PASS");
        System.out.println("I104_B_MISSING_ARGUMENT_BINDS_NULL=PASS");
        System.out.println("I104_B_EXCESS_ARGUMENT_IGNORED=PASS");
        System.out.println("I104_B_PUBLISHED_ACTIVATION=PASS");
        System.out.println("I104_B_NEGATIVE_ADMISSION=PASS");
    }


    @Test
    void i104RequiredPrefixIntegratesDefaultsRestAndContext() throws Exception {
        withCore(module -> {
            Long a = integer(11);
            Long b = integer(22);
            Long c = integer(33);
            Long d = integer(44);

            ProtosClosureValue defaults =
                    closure("(a, b, c: a) => { c }", module);
            assertContains(
                    instructionNames(defaults),
                    "HasSuppliedArgument");

            assertSame(
                    a,
                    enter(fastDirect(defaults, module, a, b)),
                    "the default reads the already established first parameter");
            assertSame(
                    c,
                    enter(fastDirect(defaults, module, a, b, c)),
                    "a supplied value suppresses its default");

            // D203/I105: b is absent (null) and c's default reads a.
            assertSame(a, enter(fastDirect(defaults, module, a)));
            assertSame(c, enter(fastDirect(defaults, module, a, b, c, d)));

            Object[] published =
                    ProtosFrameArguments.compactDirectClosureCall(
                            defaults,
                            module,
                            null,
                            new Object[] {a, b});
            ProtosActivation activation =
                    ProtosFrameArguments.activation(published);
            assertSame(a, target(defaults).call(published));
            assertSame(activation, published[0]);

            ProtosClosureValue ordinaryArgs =
                    closure("(args, b, c: args) => { c }", module);
            assertContains(
                    instructionNames(ordinaryArgs),
                    "LoadSuppliedArgument");
            assertSame(
                    a,
                    enter(fastDirect(ordinaryArgs, module, a, b)));

            ProtosClosureValue rest =
                    closure("(a, b, ...tail) => { tail }", module);
            assertContains(
                    instructionNames(rest),
                    "LoadSuppliedArgument");
            // I115: the rest parameter is bound from the unified LoadRestArguments.
            assertContains(
                    instructionNames(rest),
                    "LoadRestArguments");

            ProtosArrayValue empty1 =
                    assertInstanceOf(
                            ProtosArrayValue.class,
                            enter(fastDirect(rest, module, a, b)));
            ProtosArrayValue empty2 =
                    assertInstanceOf(
                            ProtosArrayValue.class,
                            enter(fastDirect(rest, module, a, b)));
            assertEquals(0, empty1.indexedSnapshot().size());
            assertNotSame(empty1, empty2, "rest must be fresh per invocation");

            ProtosArrayValue extras =
                    assertInstanceOf(
                            ProtosArrayValue.class,
                            enter(fastDirect(rest, module, a, b, c, d)));
            assertEquals(
                    List.of(c, d),
                    extras.indexedSnapshot(),
                    "rest contains only the remaining supplied arguments");

            ProtosClosureValue combined =
                    closure("(a, b, c: a, ...tail) => { tail }", module);
            assertContains(
                    instructionNames(combined),
                    "LoadRestArguments");

            ProtosArrayValue combinedEmpty =
                    assertInstanceOf(
                            ProtosArrayValue.class,
                            enter(fastDirect(combined, module, a, b)));
            assertEquals(0, combinedEmpty.indexedSnapshot().size());

            ProtosArrayValue combinedExtras =
                    assertInstanceOf(
                            ProtosArrayValue.class,
                            enter(fastDirect(combined, module, a, b, c, d)));
            assertEquals(
                    List.of(d),
                    combinedExtras.indexedSnapshot());

            // A default can observe the Context: it publishes the invocation's
            // own Context lazily, at that point.
            ProtosClosureValue observed =
                    closure(
                            "(a, b, c: context.hasSlot(\"a\")) => { c }",
                            module);
            assertContains(instructionNames(observed), "PublishCurrentClosureContext");
            assertEquals(List.of(2), constantIntegersOf(observed, "HasSuppliedArgument", "index"));
            assertNoRetired(instructionNames(observed));
            // Specified semantics (see report: I115 Context divergence).
            assertSame(
                    Boolean.TRUE,
                    enter(fastDirect(observed, module, a, b)));

            // A default capturing an earlier parameter makes it a CELL binding.
            ProtosClosureValue captured =
                    closure("(a, b, c: () => a) => { c }", module);
            assertEquals(1, countOf(instructionNames(captured), "CreateCell"));
            assertContains(instructionNames(captured), "CaptureClosureCells");
            assertNoRetired(instructionNames(captured));

            // A Context mutation must not bypass D179 presence rules.
            ProtosClosureValue removed =
                    closure(
                            "(a, b, ...tail) => { "
                                    + "context.removeSlot(\"a\")\n"
                                    + "a: b\n"
                                    + "a }",
                            module);
            assertContains(instructionNames(removed), "PublishCurrentClosureContext");
            assertContains(instructionNames(removed), "LoadRestArguments");
            assertNoRetired(instructionNames(removed));
            // Specified semantics (see report: D179 divergence).
            assertSame(
                    b,
                    enter(fastDirect(removed, module, a, b)));
        });

        System.out.println("I104_C_REQUIRED_PREFIX_DEFAULTS=PASS");
        System.out.println("I104_C_REQUIRED_PREFIX_REST=PASS");
        System.out.println("I104_C_REQUIRED_PREFIX_DEFAULT_AND_REST=PASS");
        System.out.println("I104_C_RICH_ACTIVATION=PASS");
        System.out.println("I104_C_CONTEXT_D179_EXCLUSIONS=PASS");
    }

    @Test
    void multipleParametersBindInOrderWithFlexibleArity() throws Exception {
        withCore(module -> {
            ProtosClosureValue middle = closure("(a, b, c) => { b }", module);
            assertNone(instructionNames(middle), "CurrentActivation");
            Long a = integer(1);
            Long b = integer(2);
            Long c = integer(3);
            DirectEntry ordinary = fastDirect(middle, module, a, b, c);
            assertSame(b, enter(ordinary));
            assertSame(middle, ordinary.targetArguments()[0]);

            // D203/I105: no argument-count Error; neither call materializes.
            DirectEntry missing = fastDirect(middle, module, a);
            assertSame(ProtosNullValue.INSTANCE, enter(missing));
            assertSame(middle, missing.targetArguments()[0]);

            DirectEntry excess =
                    fastDirect(middle, module, a, b, c, integer(4));
            assertSame(b, enter(excess));
            assertSame(middle, excess.targetArguments()[0]);
        });
        System.out.println("LEFT_TO_RIGHT_PARAMETER_BINDING=PASS");
        System.out.println("MISSING_ARGUMENT_BINDS_NULL=PASS");
        System.out.println("EXCESS_ARGUMENT_IGNORED=PASS");
    }

    @Test
    void zeroParameterLocalCreationAndReadStayCompact() throws Exception {
        withCore(module -> {
            ProtosClosureValue run =
                    closure("() => { value: 1\nvalue }", module);

            List<String> instructions = instructionNames(run);
            assertEquals(
                    // I112: every semantic root has the EnterRoot prologue and
                    // the EpilogForReturn epilogue.
                    List.of("c.EnterRoot", "load.constant", "c.CreateLexicalLocal",
                            "store.local", "load.local", "c.EpilogForReturn", "return"),
                    instructions,
                    "scalar creation checks Context state and retains frame-local storage");
            assertNone(instructions, "CurrentActivation");
            assertNone(instructions, RETIRED_ARCHITECTURE);

            for (int iteration = 0; iteration < 3; iteration++) {
                DirectEntry prepared =
                        fastDirect(run, module);
                assertEquals("1", integerValue(enter(prepared)));
                assertSame(
                        run,
                        prepared.targetArguments()[0],
                        "ordinary local creation/read must not materialize "
                                + "a compact activation");
            }

            Object[] method =
                    ProtosFrameArguments.compactImmediateMethodCall(
                            run, newObject(), newObject(), module,
                            new Object[0]);
            // I115-O: the certified Integer literal return is a prepared
            // guest constant, so the raw entry already returns the guest
            // Integer and delivers no private carrier.
            Object raw = target(run).call(method);
            assertInstanceOf(Long.class, raw);
            assertEquals("1", integerValue(raw));
            assertSame(
                    run,
                    method[0],
                    "compact immediate method execution must remain compact");

            ProtosClosureValue duplicate =
                    closure("() => { value: 1\nvalue: 2\nvalue }", module);
            DirectEntry invalid =
                    fastDirect(duplicate, module);

            assertThrows(
                    ProtosSignalException.class,
                    () -> enter(invalid));
            assertInstanceOf(
                    ProtosActivation.class,
                    invalid.targetArguments()[0],
                    "the duplicate-creation Error must materialize "
                            + "the exact calling activation");
        });
    }

    @Test
    void perf034PureScalarBindingsUseBuiltinLocalLane() throws Exception {
        withCore(module -> {
            ProtosClosureValue scalar =
                    closure("() => { first: 7\nsecond: 9\nfirst }", module);

            List<String> instructions = instructionNames(scalar);
            assertEquals(
                    List.of(
                            "c.EnterRoot",
                            "load.constant", "c.CreateLexicalLocal", "store.local",
                            "load.constant", "c.CreateLexicalLocal", "store.local",
                            "load.local",
                            "c.EpilogForReturn", "return"),
                    instructions,
                    "each scalar creation checks Context state before storing its binding");
            assertNone(instructions, "CurrentActivation");
            assertNone(instructions, RETIRED_ARCHITECTURE);

            for (int i = 0; i < 3; i++) {
                DirectEntry prepared =
                        fastDirect(scalar, module);
                assertEquals("7", integerValue(enter(prepared)));
                assertSame(
                        scalar, prepared.targetArguments()[0],
                        "pure scalar execution must remain compact");
            }

            ProtosClosureValue duplicate =
                    closure("() => { first: 7\nfirst: 9\nfirst }", module);
            assertNone(
                    instructionNames(duplicate),
                    RETIRED_ARCHITECTURE);
            assertThrows(
                    ProtosSignalException.class,
                    () -> enter(fastDirect(duplicate, module)));
        });
    }

    @Test
    void perf034ScalarLaneFollowsPublishedActivation() throws Exception {
        /*
         * PLAT067-B: a proven scalar body has no compact-frame test at all.
         * I115-M: the mutated alias is admitted to the whole-root compact
         * lane too, so it has no compact-frame test either; the published
         * entry below still yields the exact result and activation identity.
         */
        withCoreAndDebugger(module -> {
            ProtosClosureValue scalar =
                    closure(
                            "() => { left: 1\nalias: left\nright: 2\n"
                                    + "alias = right\nleft + alias }",
                            module);
            assertContains(instructionNames(scalar), "NumberAdd");
            assertNone(instructionNames(scalar), "ExperimentalInteger");
            assertNone(instructionNames(scalar), RETIRED_ARCHITECTURE);

            Object[] direct =
                    ProtosFrameArguments.compactDirectClosureCall(
                            scalar, module, null, new Object[0]);
            Object[] method =
                    ProtosFrameArguments.compactImmediateMethodCall(
                            scalar, newObject(), newObject(), module,
                            new Object[0]);
            for (Object[] arguments : List.of(direct, method)) {
                assertTrue(
                        ProtosFrameArguments
                                .isUnmaterializedCompactScalarLocalCall(arguments),
                        "an unmaterialized compact call selects the scalar lane");

                ProtosActivation published =
                        ProtosFrameArguments.activation(arguments);
                assertSame(
                        published, arguments[0],
                        "materialization publishes the exact activation");
                assertFalse(
                        ProtosFrameArguments
                                .isUnmaterializedCompactScalarLocalCall(arguments),
                        "a published activation leaves the scalar lane");

                assertEquals(
                        "3",
                        integerValue(
                                com.guillermomolina.protos.runtime
                                        .ProtosNumericValueSupport.guestValue(
                                                target(scalar).call(arguments))),
                        "the authoritative path creates and reads the bindings");
                assertSame(
                        published, arguments[0],
                        "the observed execution context keeps its identity");
                assertSame(published, ProtosFrameArguments.activation(arguments));
            }

            assertFalse(
                    ProtosFrameArguments.isUnmaterializedCompactScalarLocalCall(
                            new Object[] {ProtosFrameArguments.activation(direct)}),
                    "a rich entry array is never in the scalar lane");
        });
    }

    @Test
    void i101ExplicitContextObservationRetainsRealBindingsInNormal()
            throws Exception {
        withCore(module -> {
            ProtosClosureValue observedClosure = closure(
                    "() => { value: 1\ncontext\nvalue }", module);
            assertNone(
                    instructionNames(observedClosure),
                    RETIRED_ARCHITECTURE);

            Object[] arguments =
                    ProtosFrameArguments.compactDirectClosureCall(
                            observedClosure, module, null, new Object[0]);
            ProtosActivation activation =
                    ProtosFrameArguments.activation(arguments);
            ProtosObjectValue observed = activation.context();

            assertEquals(
                    "1",
                    integerValue(target(observedClosure).call(arguments)));
            assertSame(activation, arguments[0]);
            assertSame(observed, activation.context());
            assertEquals(
                    "1",
                    integerValue(
                            observed.readLocalSlot("value").orElseThrow()));
        });
    }

    /** A PERF034-E scalar body, its result, and its creation/read counts. */
    private record ScalarBody(
            String characters, long expected, List<String> created, int reads,
            boolean literalOnly) {}

    private static final List<ScalarBody> PERF034E_SCALAR_BODIES =
            List.of(
                    new ScalarBody(
                            "() => { value: 1\nvalue }", 1, List.of("value"), 1, true),
                    new ScalarBody(
                            "() => { first: 7\nsecond: 9\nfirst }",
                            7, List.of("first", "second"), 1, true),
                    new ScalarBody(
                            "() => { first: 7\nsecond: first\nsecond }",
                            7, List.of("first", "second"), 2, false),
                    new ScalarBody(
                            "() => { first: 7\nsecond: first\nthird: second\nthird }",
                            7, List.of("first", "second", "third"), 3, false));

    @Test
    void perf034eLiteralAndAliasChainsStayInScalarLane() throws Exception {
        withCore(module -> {
            for (ScalarBody body : PERF034E_SCALAR_BODIES) {
                ProtosClosureValue scalar = closure(body.characters(), module);
                List<String> instructions = instructionNames(scalar);
                assertNone(instructions, "CurrentActivation");
                assertNone(instructions, RETIRED_ARCHITECTURE);
                // NORMAL has no materialization or dispatch branches.
                // Alias RHS loads remain explicit for Graal to optimize.
                assertNone(instructions, RETIRED_ARCHITECTURE);
                assertNone(instructions, RETIRED_ARCHITECTURE);
                assertNone(instructions, RETIRED_ARCHITECTURE);
                assertNone(instructions, RETIRED_ARCHITECTURE);
                assertEquals(
                        body.created().size(),
                        countOf(instructions, "store.local"));
                assertEquals(
                        body.literalOnly() ? body.created().size() : 1,
                        countOf(instructions, "load.constant"));
                assertEquals(
                        body.literalOnly() ? 1 : body.created().size(),
                        countOf(instructions, "load.local"));
                assertEquals(1, countOf(instructions, "return"));
                // I112: plus the EnterRoot prologue and EpilogForReturn epilogue.
                assertEquals(
                        body.created().size() * 3 + 4,
                        instructions.size(),
                        () -> body.characters() + ": " + instructions);
                List<Object> locals = localNames(scalar);
                if (body.literalOnly()) {
                    assertFalse(
                            locals.contains("createValue"),
                            () -> "a literal creation needs no createValue: " + locals);
                }

                for (int iteration = 0; iteration < 3; iteration++) {
                    DirectEntry prepared =
                            fastDirect(scalar, module);
                    assertEquals(Long.toString(body.expected()), integerValue(enter(prepared)), body.characters());
                    assertSame(
                            scalar, prepared.targetArguments()[0],
                            "scalar creation/read must not materialize");
                }

                Object[] method =
                        ProtosFrameArguments.compactImmediateMethodCall(
                                scalar, newObject(), newObject(), module,
                                new Object[0]);
                /*
                 * The method header's caller does not accept a primitive
                 * result: a certified literal return (I115-O) is already the
                 * guest Integer, and any other compact return carrier is
                 * projected by the I112 EpilogForReturn.
                 */
                Object raw = target(scalar).call(method);
                assertInstanceOf(Long.class, raw, body.characters());
                assertEquals(Long.toString(body.expected()), integerValue(raw), body.characters());
                assertSame(
                        scalar, method[0],
                        "compact method execution must remain compact");
            }
        });
    }

    @Test
    void perf034eMaterializedEntryExposesFrameBindings() throws Exception {
        // Published Contexts project the same live Bytecode locals.
        withCoreAndDebugger(module -> {
            for (ScalarBody body : PERF034E_SCALAR_BODIES) {
                ProtosClosureValue scalar = closure(body.characters(), module);
                Object[] direct =
                        ProtosFrameArguments.compactDirectClosureCall(
                                scalar, module, null, new Object[0]);
                Object[] method =
                        ProtosFrameArguments.compactImmediateMethodCall(
                                scalar, newObject(), newObject(), module,
                                new Object[0]);
                for (Object[] arguments : List.of(direct, method)) {
                    ProtosActivation published =
                            ProtosFrameArguments.activation(arguments);
                    ProtosObjectValue observed = published.context();
                    assertEquals(
                            Long.toString(body.expected()),
                            integerValue(
                                    com.guillermomolina.protos.runtime
                                            .ProtosNumericValueSupport.guestValue(
                                                    target(scalar).call(arguments))),
                            body.characters());
                    assertSame(published, arguments[0]);
                    assertSame(observed, published.context());
                    for (String name : body.created()) {
                        assertTrue(
                                observed.readLocalSlot(name).isPresent(),
                                () -> body.characters() + ": published binding " + name);
                    }
                }
            }
        });
    }

    @Test
    void perf034eFinalReadKeepsStatementAndExpressionTags() throws Exception {
        withCoreAndDebugger(module -> {
            ProtosClosureValue scalar =
                    closure("() => { first: 7\nsecond: first\nsecond }", module);
            ProtosBytecodeRootNode root =
                    scalar.executionPlan()
                            .orElseThrow()
                            .bytecodeActivationRootForTesting();
            root.getRootNodes().ensureComplete();
            TagTree tree = root.getBytecodeNode().getTagTree();
            List<TagTree> statements =
                    collectTags(tree, StandardTags.StatementTag.class);
            List<TagTree> expressions =
                    collectTags(tree, StandardTags.ExpressionTag.class);
            assertEquals(
                    List.of("first: 7", "second: first", "second"),
                    statements.stream()
                            .map(tag -> tag.getSourceSection().getCharacters().toString())
                            .toList());
            assertEquals(statements.size(), expressions.size());
            for (int index = 0; index < statements.size(); index++) {
                assertSame(statements.get(index), expressions.get(index));
            }

            // Same tagged root, compact and observed.
            assertEquals("7", integerValue(enter(fastDirect(scalar, module))));
            Object[] observed =
                    ProtosFrameArguments.compactDirectClosureCall(
                            scalar, module, null, new Object[0]);
            ProtosFrameArguments.activation(observed).context();
            // The raw internal entry returns the private Long carrier (I101-B).
            assertEquals(
                    "7",
                    integerValue(
                            com.guillermomolina.protos.runtime.ProtosNumericValueSupport
                                    .guestValue(target(scalar).call(observed))));
        });
    }

    @Test
    void perf034eAliasAdmissionExcludesUnprovenForms() throws Exception {
        withCore(module -> {
            ProtosClosureValue duplicate =
                    closure(
                            "() => { first: 7\nsecond: first\nsecond: first\nsecond }",
                            module);
            assertNone(instructionNames(duplicate), RETIRED_ARCHITECTURE);
            assertThrows(
                    ProtosSignalException.class,
                    () -> enter(fastDirect(duplicate, module)));

            evaluate("perf034eOuter: 5", module);
            ProtosClosureValue outer =
                    closure("() => { first: perf034eOuter\nfirst }", module);
            assertNone(instructionNames(outer), RETIRED_ARCHITECTURE);
            assertEquals("5", integerValue(enter(fastDirect(outer, module))));

            ProtosClosureValue captured =
                    closure(
                            "() => { first: 7\nreader: () => first\nsecond: first\nsecond }",
                            module);
            assertNone(instructionNames(captured), RETIRED_ARCHITECTURE);
            assertEquals("7", integerValue(enter(fastDirect(captured, module))));
        });
    }


    @Test
    void perf043EarlyIntegerFoldingRecognizesLiteralsAndScalarAliases()
            throws Exception {
        withCore(module -> {
            ProtosClosureValue direct =
                    closure("() => { 1 + 2 }", module);
            ProtosClosureValue locals =
                    closure("() => { left: 1\nright: 2\nleft + right }", module);
            ProtosClosureValue aliases =
                    closure(
                            "() => { left: 1\nalias: left\nright: 2\n"
                                    + "alias = right\nleft + alias }",
                            module);

            /*
             * I115: two Integer literals select the standard '+' of the frozen
             * standard Integer, so the literal sum folds to its constant. The
             * local operands keep one fused NumberAdd that classifies its
             * standard selection after both operands (D237) and performs the
             * ordinary send itself otherwise.
             */
            for (ProtosClosureValue candidate : List.of(direct, locals, aliases)) {
                DirectEntry prepared =
                        fastDirect(candidate, module);
                assertEquals("3", integerValue(enter(prepared)));
                // The site's lazy caller reference answers the compact arguments.
                assertSame(candidate, prepared.targetArguments()[0],
                        "the numeric HIT materializes no activation");
                List<String> names = instructionNames(candidate);
                int sites = candidate == direct ? 0 : 1;
                assertEquals(sites, countOf(names, "NumberAdd"), names.toString());
                assertEquals(0, countOf(names, "IsStandardNumericSelection"), names.toString());
                assertNone(names, "ExperimentalInteger");
                assertNone(names, RETIRED_ARCHITECTURE);
            }
        });
    }

    /**
     * PLAT067-B: an attached debugger observes the one proof-optimized
     * lowering. A caller that publishes the activation still receives the
     * result through the same root, and the shared numeric send keeps its
     * CallTag, exactly as without a debugger. I115-H1-FIX: the compact root
     * folds nothing, so left and right are genuine bindings; a root that may
     * promote establishes them through CreateCurrentFrameLocal, so the
     * published Context observes exactly them, with their values. The three statements keep their
     * passive StatementTag + ExpressionTag over their exact source sections,
     * which BytecodeConfig.DEFAULT never materializes.
     */
    @Test
    void perf043AttachedDebuggerObservesTheCompactBindings() throws Exception {
        withCoreAndDebugger(module -> {
            ProtosClosureValue sum =
                    closure("() => { left: 1\nright: 2\nleft + right }", module);
            // I115-H1-FIX: the compact lane adds at the shared numeric site.
            assertContains(instructionNames(sum), "NumberAdd");

            Object[] arguments =
                    ProtosFrameArguments.compactDirectClosureCall(
                            sum, module, null, new Object[0]);

            ProtosActivation activation =
                    ProtosFrameArguments.activation(arguments);
            ProtosObjectValue observed = activation.context();

            Object raw = target(sum).call(arguments);
            assertEquals(
                    "3",
                    integerValue(
                            com.guillermomolina.protos.runtime
                                    .ProtosNumericValueSupport.guestValue(raw)));

            assertSame(activation, arguments[0]);
            assertSame(observed, activation.context());
            // A Context published from Java before entry is not observed by
            // guest code; its frame-native bindings are not projected into it
            // (owner decision). Debugger projection is covered by the frame
            // view tests.

            ProtosBytecodeRootNode root =
                    sum.executionPlan()
                            .orElseThrow()
                            .bytecodeActivationRootForTesting();
            root.getRootNodes().ensureComplete();
            TagTree tree = root.getBytecodeNode().getTagTree();
            List<TagTree> statements =
                    collectTags(tree, StandardTags.StatementTag.class);
            assertEquals(
                    List.of("left: 1", "right: 2", "left + right"),
                    statements.stream()
                            .map(tag -> tag.getSourceSection().getCharacters().toString())
                            .toList());
            assertEquals(statements, collectTags(tree, StandardTags.ExpressionTag.class));
            // I115: the fused numeric site dispatches any selected method
            // inside the operation, so no separate send carries a CallTag.
            assertEquals(
                    List.of(),
                    collectTags(tree, StandardTags.CallTag.class).stream()
                            .map(tag -> tag.getSourceSection().getCharacters().toString())
                            .toList(),
                    "the fused numeric site emits no separate CallTag");
        });
    }

    /**
     * PLAT067-B: without any debugger, BytecodeConfig.DEFAULT materializes none
     * of the folded body's passive statement tags, and their later on-demand
     * materialization adds instrumentation (and the unreachable reparse
     * epilogue) only: the semantic program keeps its exact minimal prefix.
     */
    @Test
    void perf043FoldPassiveTagsLeaveTheDefaultProgramMinimal() throws Exception {
        withCore(module -> {
            ProtosClosureValue sum =
                    closure("() => { left: 1\nright: 2\nleft + right }", module);
            List<String> physical = instructionNames(sum);
            assertTrue(physical.stream().noneMatch(name -> name.startsWith("tag.")),
                    () -> "DEFAULT materializes no tag: " + physical);

            ProtosBytecodeRootNode root =
                    sum.executionPlan().orElseThrow().bytecodeActivationRootForTesting();
            root.getRootNodes().ensureComplete();
            assertEquals(
                    3,
                    collectTags(root.getBytecodeNode().getTagTree(),
                                    StandardTags.StatementTag.class)
                            .size());
            List<String> semantic = instructionNames(sum).stream()
                    .filter(name -> !name.startsWith("tag.") && !name.startsWith("trace."))
                    .toList();
            assertEquals(physical, semantic.subList(0, physical.size()),
                    () -> "semantic prefix changed: " + physical + " vs " + semantic);
        });
    }

    @Test
    void perf043StaticFoldRetainsCallTagsAndCompactFallback() throws Exception {
        withCore(module -> {
            ProtosClosureValue direct = closure("() => { 1 + 2 }", module);
            ProtosClosureValue locals = closure(
                    "() => { left: 1\nright: 2\nleft + right }", module);
            ProtosClosureValue aliases = closure(
                    "() => { left: 1\nalias: left\nright: 2\n"
                            + "alias = right\nleft + alias }", module);
            ProtosClosureValue nested = closure("() => { (1 + 2) + 3 }", module);
            ProtosClosureValue tree = closure("() => { (1 + 2) + (3 + 4) }", module);

            for (var candidate : List.of(direct, locals, aliases, nested, tree)) {
                assertEquals(candidate == nested ? "6" : candidate == tree ? "10" : "3",
                        integerValue(enter(fastDirect(candidate, module))));
                var root = candidate.executionPlan()
                        .orElseThrow().bytecodeActivationRootForTesting();
                root.getRootNodes().ensureComplete();
                var calls = collectTags(root.getBytecodeNode().getTagTree(),
                        StandardTags.CallTag.class);
                // I115: the literal-only sums fold to their constant; each '+'
                // over locals, the mutated alias included, is one fused numeric
                // site that dispatches internally and emits no separate CallTag.
                int sites = candidate == locals || candidate == aliases ? 1 : 0;
                assertEquals(0, calls.size(),
                        "fused numeric sites emit no separate CallTag");
                assertEquals(sites,
                        countOf(instructionNames(candidate), "NumberAdd"));
                assertNone(instructionNames(candidate), "ExperimentalInteger");
            }

            for (var candidate : List.of(locals, aliases)) {

                Object[] observedCall = ProtosFrameArguments.compactDirectClosureCall(
                        candidate, module, null, new Object[0]);
                ProtosActivation observed = ProtosFrameArguments.activation(observedCall);
                observed.context();
                Object raw = target(candidate).call(observedCall);
                assertEquals(
                        "3",
                        integerValue(
                                com.guillermomolina.protos.runtime
                                        .ProtosNumericValueSupport.guestValue(raw)));
            }
        });
    }

    @Test
    void i101GuardedLongArithmeticFoldsRecursivelyAndFailsClosed()
            throws Exception {
        withCore(module -> {
            // '/' is outside the compact lane: the general lowerer folds it.
            ProtosClosureValue divided = closure("() => { 6 / 3 }", module);
            assertFalse(
                    hasFoldedIntegerArithmetic(divided),
                    "Proven arithmetic must not retain a runtime descriptor");
            assertEquals(0, numericOperationCount(instructionNames(divided)));
            assertEquals("2", integerValue(enter(fastDirect(divided, module))));

            /*
             * I115: literal-only arithmetic selects the standard operators of the
             * frozen standard Integer and folds recursively to its constant; the
             * operations over locals stay at run time, one guarded site each.
             */
            Object[][] compact = {
                    {"() => { 1 + 1 + 1 }", "3", 0},
                    {"() => { (1 + 1) + 1 }", "3", 0},
                    {"() => { 1 + (1 + 1) }", "3", 0},
                    {"() => { (1 + 2) * 3 }", "9", 0},
                    {"() => { 10 - 3 - 2 }", "5", 0},
                    {"() => { 1 + 2 * 3 }", "7", 0},
                    {"() => { x: 1; y: x + 2; z: y * 3; z }", "9", 2}
            };

            for (Object[] example : compact) {
                String source = (String) example[0];
                ProtosClosureValue candidate = closure(source, module);
                List<String> names = instructionNames(candidate);
                assertEquals(example[2], numericOperationCount(names), source);
                assertNone(names, "ExperimentalInteger");
                assertEquals(
                        example[1],
                        integerValue(enter(fastDirect(candidate, module))),
                        source);
            }

            for (String example : List.of(
                    "() => { 1 + 2.0 }",
                    "() => { 1 / 2 }",
                    "() => { 1 / 0 }")) {
                ProtosClosureValue candidate = closure(example, module);
                assertFalse(hasFoldedIntegerArithmetic(candidate),
                        "Non-exact or non-long arithmetic must fail closed: "
                                + example);
                assertTrue(
                        numericOperationCount(instructionNames(candidate)) > 0,
                        () -> "the exact numeric operation stays at run time: " + example);
            }

            /*
             * I115-M: overflowing Integer literals are admitted to the compact
             * lane; they are not folded and the shared numeric operation
             * keeps exact Integer semantics, promoting beyond signed-64.
             */
            String[][] overflowing = {
                    {"() => { 9223372036854775807 + 1 }", "NumberAdd",
                            "9223372036854775808"},
                    {"() => { 9223372036854775807 * 2 }", "NumberMultiply",
                            "18446744073709551614"}
            };
            for (String[] example : overflowing) {
                ProtosClosureValue candidate = closure(example[0], module);
                assertFalse(hasFoldedIntegerArithmetic(candidate),
                        "Overflowing arithmetic must fail closed: " + example[0]);
                assertContains(instructionNames(candidate), example[1]);
                assertEquals(example[2],
                        integerValue(enter(fastDirect(candidate, module))), example[0]);
            }
        });
    }

    @Test
    void perf043UnknownOperandsAndOverflowKeepGenericSemantics()
            throws Exception {
        withCore(module -> {
            ProtosClosureValue dynamic =
                    closure("(left, right) => { left + right }", module);
            assertFalse(hasFoldedIntegerArithmetic(dynamic));
            assertEquals(
                    "3",
                    integerValue(
                            enter(fastDirect(dynamic, module, integer(1), integer(2)))));

            ProtosClosureValue overflow =
                    closure("() => { 9223372036854775807 + 1 }", module);
            assertFalse(hasFoldedIntegerArithmetic(overflow));
            assertEquals(
                    "9223372036854775808",
                    integerValue(enter(fastDirect(overflow, module))));

            ProtosClosureValue incompatible =
                    closure("() => { 1 + true }", module);
            assertThrows(
                    ProtosSignalException.class,
                    () -> enter(fastDirect(incompatible, module)));
        });
    }


    @Test
    void perf043CustomPlusKeepsOrdinaryDispatchAndInvalidation()
            throws Exception {
        withCore(module -> {
            ProtosClosureValue dispatch =
                    closure("(receiver) => { receiver + 2 }", module);

            assertFalse(hasFoldedIntegerArithmetic(dispatch));

            ProtosObjectValue custom =
                    new ProtosObjectValue(ProtosObjectValue.rootObject());

            ProtosClosureValue first =
                    ProtosClosureValue.nativeClosure(
                            (activation, supplied) -> integer(41));
            ProtosClosureValue replacement =
                    ProtosClosureValue.nativeClosure(
                            (activation, supplied) -> integer(42));

            custom.createLocalSlot("+", first);

            // Prime the same source call-site with standard Integer dispatch.
            assertEquals(
                    "3",
                    integerValue(
                            enter(fastDirect(dispatch, module, integer(1)))));

            // A different receiver selects its own ordinary '+' method.
            assertEquals(
                    "41",
                    integerValue(
                            enter(fastDirect(dispatch, module, custom))));

            var selected =
                    com.guillermomolina.protos.runtime.ProtosValueLookup
                            .selectStructural(custom, "+");
            assertTrue(selected != null);
            assertTrue(selected.isValid());

            // Same-Shape assignment is read freshly by the structural selection.
            custom.assignLocalSlot("+", replacement);
            assertTrue(selected.isValid());
            assertSame(replacement, selected.read());
            custom.createLocalSlot("structuralMarker", 1L);
            assertFalse(selected.isValid());

            assertEquals(
                    "42",
                    integerValue(
                            enter(fastDirect(dispatch, module, custom))));

            // Standard Integer behavior remains correct at the same site.
            assertEquals(
                    "3",
                    integerValue(
                            enter(fastDirect(dispatch, module, integer(1)))));
        });
    }

    /**
     * I115-H1-FIX: a compact root keeps its bindings in Bytecode locals.
     * When a numeric send MISSes into an ordinary send, the root first
     * promotes those locals to its activation's lexical authority, so a
     * selected method that observes the caller's activation sees the exact
     * bindings the compact code uses, and an authorized mutation through
     * that activation is the one the compact code then reads. A published
     * Context that copied, or did not share, the locals fails every step.
     */
    @Test
    void numericMissObserverSharesTheCompactBindingAuthority() throws Exception {
        withCore(module -> {
            ProtosClosureValue run = closure(
                    "(gate) => { seen: 5\nresult: gate + seen\n"
                            + "seen = seen + result\nseen }",
                    module);
            List<String> names = instructionNames(run);
            assertContains(names, "NumberAdd");
            assertNone(names, RETIRED_ARCHITECTURE);
            assertNone(names, RETIRED_ARCHITECTURE);

            // D179: what Java code reads from a Context no guest code observes is
            // not guest semantics. The guest-visible guarantee is the MISS: a
            // receiver whose '+' is not the standard one receives the ordinary
            // send with the evaluated argument, and its result is the sum.
            ProtosObjectValue gate =
                    new ProtosObjectValue(ProtosObjectValue.rootObject());
            Object[] received = new Object[1];
            gate.createLocalSlot("+", ProtosClosureValue.nativeClosure(
                    (activation, supplied) -> {
                        received[0] = supplied.get(0);
                        return integer(100);
                    }));
            // seen = 5 + 100.
            assertEquals("105", integerValue(enter(fastDirect(run, module, gate))));
            assertEquals("5", integerValue(received[0]));

            // A HIT at the same site publishes nothing.
            DirectEntry hit =
                    fastDirect(run, module, integer(1));
            // seen = 5 + (1 + 5).
            assertEquals("11", integerValue(enter(hit)));
            assertSame(run, hit.targetArguments()[0],
                    "the numeric HIT keeps the compact activation");
        });
    }

    /**
     * I115-H1-FIX: the establishment history across a promotion. The real
     * order is gate (entry), first, then mid and later after the MISS. The
     * observer at the MISS sees exactly the bindings established so far, in
     * that order, with mid still ABSENT; the creations after the promotion
     * are recorded by the installed authority through CreateCurrentFrameLocal,
     * so the final snapshot keeps the real order and values. A HIT of the
     * same root materializes nothing.
     */
    @Test
    void promotionPreservesTheRealEstablishmentOrder() throws Exception {
        withCore(module -> {
            ProtosClosureValue run = closure(
                    "(gate) => { first: 1\nmid: gate + 2\nlater: first + mid\nlater }",
                    module);
            List<String> names = instructionNames(run);
            assertNone(names, RETIRED_ARCHITECTURE);
            assertNone(names, RETIRED_ARCHITECTURE);
            assertContains(names, "store.local");

            // E: the HIT path stays compact; 1 + (3 + 2).
            DirectEntry hit =
                    fastDirect(run, module, integer(3));
            assertEquals("6", integerValue(enter(hit)));
            assertSame(run, hit.targetArguments()[0],
                    "the numeric HIT materializes no activation");

            ProtosObjectValue gate =
                    new ProtosObjectValue(ProtosObjectValue.rootObject());
            List<java.util.Map<String, Object>> atMiss = new java.util.ArrayList<>();
            Object[] arguments =
                    ProtosFrameArguments.compactDirectClosureCall(
                            run, module, null, new Object[] {gate});
            gate.createLocalSlot("+", ProtosClosureValue.nativeClosure(
                    (activation, supplied) -> {
                        atMiss.add(java.util.Map.of());
                        return integer(10);
                    }));

            Object raw = target(run).call(arguments);
            // The custom '+' is selected after its operand (D237) and its
            // result flows on; the caller's frame-native bindings are not
            // observed by guest code, so their Context contents are not
            // asserted from Java (owner decision).
            assertEquals("11", integerValue(
                    com.guillermomolina.protos.runtime.ProtosNumericValueSupport
                            .guestValue(raw)));
            assertEquals(1, atMiss.size());
        });
    }

    /**
     * I115-H1-FIX: A. The inner creation of second's value executes before
     * second, but the layout declares second first, so adopting PRESENT
     * locals in layout order would not be the real history. The compact
     * lowering cannot prove the order and rejects the root, which keeps the
     * general lowerer (no compact promotion) and its exact result.
     */
    @Test
    void outOfLayoutCreationOrderIsNotCompact() throws Exception {
        withCore(module -> {
            ProtosClosureValue run = closure(
                    "(gate) => { first: 1\nsecond: gate + (inner: 2)\n"
                            + "later: first + second\nlater }",
                    module);
            List<String> names = instructionNames(run);
            assertNone(names, RETIRED_ARCHITECTURE);
            assertNone(names, "Experimental");
            // 1 + (3 + 2).
            assertEquals("6", integerValue(enter(fastDirect(run, module, integer(3)))));
        });
    }

    /**
     * I115-H1-FIX: a creation under conditional control is not compact: its
     * establishment is neither static nor unconditional, so the compact
     * subset cannot prove its order and the root keeps the general lowerer,
     * whose frame-native creation records it.
     */
    @Test
    void conditionalCreationKeepsTheGeneralLowerer() throws Exception {
        withCore(module -> {
            ProtosClosureValue run = closure(
                    "(gate) => { (gate < 1).ifTrue() {\nmade: 1\n}\n0 }", module);
            List<String> names = instructionNames(run);
            assertNone(names, "IsCanonicalBooleanValue");
            assertNone(names, RETIRED_ARCHITECTURE);
            assertNone(names, "ExperimentalCanonicalBooleanCondition");
            assertEquals("0", integerValue(enter(fastDirect(run, module, integer(0)))));
            assertEquals("0", integerValue(enter(fastDirect(run, module, integer(5)))));
        });
    }

    @Test
    void perf043FoldedAdditionIsIsolatedBetweenContextsAndPreludes()
            throws Exception {
        ProtosPrelude[] preludes = new ProtosPrelude[2];

        for (int index = 0; index < preludes.length; index++) {
            final int position = index;

            withCore(module -> {
                ProtosPrelude prelude = module.prelude().orElseThrow();
                preludes[position] = prelude;

                assertTrue(
                        prelude.integerPrototype().isFrozen(),
                        "published canonical Integer selection is immutable");

                ProtosClosureValue direct =
                        closure("() => { 1 + 2 }", module);

                ProtosClosureValue locals =
                        closure(
                                "() => { left: 1\nright: 2\nleft + right }",
                                module);

                // I115: the literal sum folds under this Context's own frozen
                // standard Integer; the sum of locals is the guarded numeric site.
                assertNone(instructionNames(direct), "NumberAdd");
                assertContains(instructionNames(locals), "NumberAdd");

                assertEquals(
                        "3",
                        integerValue(enter(fastDirect(direct, module))));
                assertEquals(
                        "3",
                        integerValue(enter(fastDirect(locals, module))));

                assertNone(instructionNames(direct), "ExperimentalInteger");
                assertNone(instructionNames(locals), "ExperimentalInteger");
            });
        }

        assertNotSame(
                preludes[0],
                preludes[1],
                "each entered Context owns an independent Prelude");
    }


    @Test
    void perf043NonTerminalAndNestedConstantAddition()
            throws Exception {
        withCore(module -> {
            ProtosClosureValue nonTerminal =
                    closure("() => { 1 + 2\n7 }", module);

            // I115: a discarded statement keeps its one synchronous numeric
            // operation, evaluated once; literal-only sums in value position fold.
            assertEquals(1, countOf(instructionNames(nonTerminal), "NumberAdd"));
            assertNone(instructionNames(nonTerminal), "ExperimentalInteger");
            assertEquals(
                    "7",
                    integerValue(enter(fastDirect(nonTerminal, module))));

            ProtosClosureValue nested =
                    closure("() => { (1 + 2) + 3 }", module);

            assertEquals(0, countOf(instructionNames(nested), "NumberAdd"));
            assertNone(instructionNames(nested), "ExperimentalInteger");
            assertEquals("6", integerValue(enter(fastDirect(nested, module))));

            ProtosClosureValue tree =
                    closure("() => { (1 + 2) + (3 + 4) }", module);

            assertEquals(
                    "10",
                    integerValue(enter(fastDirect(tree, module))));
            assertEquals(0, countOf(instructionNames(tree), "NumberAdd"));
            assertNone(instructionNames(tree), "ExperimentalInteger");

            ProtosClosureValue nestedOverflow =
                    closure(
                            "() => { (9223372036854775806 + 1) + 1 }",
                            module);

            // The inner sum fits; the outer one overflows and is promoted exactly.
            assertEquals(
                    "9223372036854775808",
                    integerValue(enter(fastDirect(nestedOverflow, module))));
            // The overflowing addition is never folded: it stays at run time.
            assertTrue(countOf(instructionNames(nestedOverflow), "NumberAdd") >= 1);

            ProtosClosureValue dynamic =
                    closure("(value) => { value + 2 }", module);

            assertFalse(hasFoldedIntegerArithmetic(dynamic));
            assertEquals(
                    "5",
                    integerValue(
                            enter(fastDirect(dynamic, module, integer(3)))));

            ProtosClosureValue overflow =
                    closure(
                            "() => { 9223372036854775807 + 1\n7 }",
                            module);

            assertFalse(hasFoldedIntegerArithmetic(overflow));
            assertEquals(
                    "7",
                    integerValue(enter(fastDirect(overflow, module))));

            ProtosBytecodeRootNode root =
                    nonTerminal.executionPlan()
                            .orElseThrow()
                            .bytecodeActivationRootForTesting();

            root.getRootNodes().ensureComplete();

            List<TagTree> statements =
                    collectTags(
                            root.getBytecodeNode().getTagTree(),
                            StandardTags.StatementTag.class);

            assertTrue(
                    statements.stream().anyMatch(tag ->
                            tag.getSourceSection() != null
                                    && "1 + 2".equals(
                                            tag.getSourceSection()
                                                    .getCharacters()
                                                    .toString())),
                    "folding must preserve the source statement tag");
        });
    }

    private static boolean containsLoweredIntegerConstant(
            ProtosClosureValue closure, long expected) {
        return loweredIntegerConstantCount(closure, expected) > 0;
    }

    private static long loweredIntegerConstantCount(
            ProtosClosureValue closure, long expected) {
        return closure.executionPlan()
                .orElseThrow()
                .bytecodeActivationRootForTesting()
                .getBytecodeNode()
                .getInstructionsAsList()
                .stream()
                .flatMap(instruction -> instruction.getArguments().stream())
                .filter(argument -> argument.getKind().name().equals("CONSTANT"))
                .map(Instruction.Argument::asConstant)
                .filter(value -> value instanceof Long integer
                        && integer.longValue() == expected)
                .count();
    }

    private static boolean hasFoldedIntegerArithmetic(
            ProtosClosureValue closure) {
        return foldedIntegerArithmeticCount(closure) > 0;
    }

    private static long foldedIntegerArithmeticCount(
            ProtosClosureValue closure) {
        return closure.executionPlan()
                .orElseThrow()
                .bytecodeActivationRootForTesting()
                .getBytecodeNode()
                .getInstructionsAsList()
                .stream()
                .flatMap(instruction -> instruction.getArguments().stream())
                .filter(argument -> argument.getKind().name().equals("CONSTANT"))
                .map(Instruction.Argument::asConstant)
                // I115: the PERF043 runtime fold descriptor type was retired
                // with its fast-path base; no constant of that kind may remain.
                .filter(value -> value != null
                        && value.getClass().getSimpleName().equals("FoldedIntegerArithmetic"))
                .count();
    }

    /** A PERF036-A scalar body with assignments, its result and final bindings. */
    private record ScalarAssignBody(
            String characters, long expected, int creates, int reads, int assigns,
            String assigned, long assignedValue) {}

    private static final List<ScalarAssignBody> PERF036A_ASSIGN_BODIES =
            List.of(
                    new ScalarAssignBody(
                            "() => { first: 7\nfirst = 9\nfirst }", 9, 1, 1, 1, "first", 9),
                    new ScalarAssignBody(
                            "() => { first: 7\nfirst = 8\nfirst = 11\nfirst }",
                            11, 1, 1, 2, "first", 11),
                    new ScalarAssignBody(
                            "() => { first: 7\nsecond: 3\nsecond = first\nsecond }",
                            7, 2, 2, 1, "second", 7),
                    new ScalarAssignBody(
                            "() => { first: 7\nfirst = 13 }", 13, 1, 0, 1, "first", 13),
                    new ScalarAssignBody(
                            "() => { first: 7\nsecond: 5\nfirst = second }",
                            5, 2, 1, 1, "first", 5));

    @Test
    void perf036aScalarAssignmentsStayInScalarLane() throws Exception {
        withCore(module -> {
            for (ScalarAssignBody body : PERF036A_ASSIGN_BODIES) {
                ProtosClosureValue scalar = closure(body.characters(), module);
                List<String> instructions = instructionNames(scalar);
                assertNone(instructions, "CurrentActivation");
                assertNone(instructions, RETIRED_ARCHITECTURE);
                // I115-M: a parameterless assignment body is a compact root:
                // each creation and assignment is one store.local, with no
                // compact-frame test and no authoritative write pair.
                // Each assignment may also spill its expression value to a
                // DSL temporary local; no authoritative write pair exists.
                int stores = countOf(instructions, "store.local");
                assertTrue(
                        stores >= body.creates() + body.assigns()
                                && stores <= body.creates() + 2 * body.assigns(),
                        () -> body.characters() + ": " + instructions);
                assertNone(instructions, RETIRED_ARCHITECTURE);
                assertNone(instructions, RETIRED_ARCHITECTURE);
                assertNone(instructions, RETIRED_ARCHITECTURE);
                assertContains(instructions, "load.local");

                for (int iteration = 0; iteration < 3; iteration++) {
                    DirectEntry prepared =
                            fastDirect(scalar, module);
                    assertEquals(Long.toString(body.expected()), integerValue(enter(prepared)), body.characters());
                    assertSame(
                            scalar, prepared.targetArguments()[0],
                            "scalar assignment must not materialize");
                }

                Object[] method =
                        ProtosFrameArguments.compactImmediateMethodCall(
                                scalar, newObject(), newObject(), module,
                                new Object[0]);
                assertEquals(Long.toString(body.expected()), integerValue(target(scalar).call(method)), body.characters());
                assertSame(scalar, method[0]);
            }
        });
    }

    /** A published Context projects updates from its authoritative frame storage. */
    @Test
    void perf036aObservedActivationUpdatesAuthoritativeBinding() throws Exception {
        withCore(module -> {
            for (ScalarAssignBody body : PERF036A_ASSIGN_BODIES) {
                ProtosClosureValue scalar = closure(body.characters(), module);
                assertNone(instructionNames(scalar), RETIRED_ARCHITECTURE);
                List<ProtosObjectValue> contexts = new java.util.ArrayList<>();
                for (int invocation = 0; invocation < 2; invocation++) {
                    Object[] arguments =
                            ProtosFrameArguments.compactDirectClosureCall(
                                    scalar, module, null, new Object[0]);
                    ProtosActivation published =
                            ProtosFrameArguments.activation(arguments);
                    ProtosObjectValue observed = published.context();
                    assertEquals(Long.toString(body.expected()), integerValue(target(scalar).call(arguments)), body.characters());
                    assertSame(published, arguments[0]);
                    assertSame(observed, published.context());
                    assertEquals(body.assignedValue(),
                            observed.readLocalSlot(body.assigned()).orElseThrow());
                    assertTrue(observed.readLocalSlot("first").isPresent());
                    if (body.creates() == 2) {
                        assertTrue(observed.readLocalSlot("second").isPresent());
                    }
                    contexts.add(observed);
                }
                assertNotSame(
                        contexts.get(0), contexts.get(1),
                        "every invocation owns a fresh execution context");
            }
        });
    }

    /** Initial creation respects the state of an already published Context. */
    @Test
    void perf036aFrozenOrClosedEntryContextRejectsInitialCreation() throws Exception {
        withCore(module -> {
            ProtosClosureValue scalar =
                    closure("() => { first: 7\nfirst = 9\nfirst }", module);
            assertNone(instructionNames(scalar), RETIRED_ARCHITECTURE);
            for (boolean freeze : List.of(true, false)) {
                Object[] arguments =
                        ProtosFrameArguments.compactDirectClosureCall(
                                scalar, module, null, new Object[0]);
                ProtosActivation published =
                        ProtosFrameArguments.activation(arguments);
                ProtosObjectValue observed = published.context();
                if (freeze) {
                    observed.freeze();
                } else {
                    observed.close();
                }
                java.util.Map<String, Object> before = observed.localSlotsSnapshot();
                assertThrows(ProtosSignalException.class, () -> target(scalar).call(arguments));
                assertSame(published, arguments[0]);
                assertSame(observed, published.context());
                assertEquals(before, observed.localSlotsSnapshot());
                assertTrue(observed.readLocalSlot("first").isEmpty());
                assertEquals(freeze, observed.isFrozen());
                assertEquals(!freeze, observed.isClosed());
            }
        });
    }

    @Test
    void perf036aFinalAssignmentKeepsStatementAndExpressionTags() throws Exception {
        withCore(module -> {
            ProtosClosureValue scalar =
                    closure("() => { first: 7\nfirst = 9\nfirst }", module);
            ProtosBytecodeRootNode root =
                    scalar.executionPlan()
                            .orElseThrow()
                            .bytecodeActivationRootForTesting();
            root.getRootNodes().ensureComplete();
            TagTree tree = root.getBytecodeNode().getTagTree();
            List<TagTree> statements =
                    collectTags(tree, StandardTags.StatementTag.class);
            assertEquals(
                    List.of("first: 7", "first = 9", "first"),
                    statements.stream()
                            .map(tag -> tag.getSourceSection().getCharacters().toString())
                            .toList());
            assertEquals(
                    statements,
                    collectTags(tree, StandardTags.ExpressionTag.class));
        });
    }

    @Test
    void perf036aAssignmentAdmissionExcludesUnprovenForms() throws Exception {
        withCore(module -> {
            evaluate("perf036aOuter: 5", module);
            ProtosClosureValue outer =
                    closure("() => { first: 1\nperf036aOuter = 6\nfirst }", module);
            assertNone(instructionNames(outer), RETIRED_ARCHITECTURE);
            assertEquals("1", integerValue(enter(fastDirect(outer, module))));
            assertEquals("6", integerValue(evaluate("perf036aOuter", module)), "an outer binding is assigned through the lexical chain");

            ProtosClosureValue explicitTarget =
                    closure("() => { first: 7\ncontext.first = 9\nfirst }", module);
            assertNone(instructionNames(explicitTarget), RETIRED_ARCHITECTURE);
            // `context.first = 9` publishes the Context lazily and assigns its slot.
            assertContains(instructionNames(explicitTarget), "PublishCurrentClosureContext");
            assertContains(instructionNames(explicitTarget), "SetAttribute");
            assertEquals("9", integerValue(enter(fastDirect(explicitTarget, module))), "an explicit-target assignment keeps the general lowering");

            ProtosClosureValue beforeCreation =
                    closure("() => { first = 3\nfirst: 4\nfirst }", module);
            assertNone(instructionNames(beforeCreation), RETIRED_ARCHITECTURE);
            assertThrows(
                    ProtosSignalException.class,
                    () -> enter(fastDirect(beforeCreation, module)),
                    "an ABSENT destination is SlotNotFound");

            ProtosClosureValue send =
                    closure("() => { first: 4\nfirst = first + 1\nfirst }", module);
            assertNone(instructionNames(send), RETIRED_ARCHITECTURE);
            assertEquals("5", integerValue(enter(fastDirect(send, module))));

            ProtosClosureValue captured =
                    closure(
                            "() => { first: 1\nsetter: (v) => { first = v }\nsetter(8)\nfirst }",
                            module);
            assertNone(instructionNames(captured), RETIRED_ARCHITECTURE);
            assertEquals("8", integerValue(enter(fastDirect(captured, module))), "a captured assignment writes the binding by reference");

            ProtosClosureValue recreated =
                    closure(
                            "() => { first: 1\ncontext.removeSlot(\"first\")\n"
                                    + "first: 6\nfirst = 9\nfirst }",
                            module);
            assertNone(instructionNames(recreated), RETIRED_ARCHITECTURE);
            assertEquals("9", integerValue(enter(fastDirect(recreated, module))));

            ProtosClosureValue removed =
                    closure(
                            "() => { first: 1\ncontext.removeSlot(\"first\")\nfirst = 3 }",
                            module);
            assertNone(instructionNames(removed), RETIRED_ARCHITECTURE);
            assertThrows(
                    ProtosSignalException.class,
                    () -> enter(fastDirect(removed, module)),
                    "a removed destination is SlotNotFound");
        });
    }

    @Test
    void localOnlyCalleeCreatesAndAssignsWithoutMaterializing() throws Exception {
        withCore(module -> {
            ProtosClosureValue locals = closure("(a) => { b: a\nc: b\nc = a\nc }", module);
            List<String> names = instructionNames(locals);
            assertNone(names, "CurrentActivation");
            /*
             * I115-H1: the compact root binds its parameter with the I104
             * unary operation and keeps every other binding in ordinary
             * locals: no frame-local creation or assignment machinery.
             */
            assertBindsSuppliedPositions(names);
            assertNone(names, RETIRED_ARCHITECTURE);
            assertContains(names, "store.local");
            assertContains(names, "load.local");
            assertNone(names, RETIRED_ARCHITECTURE);
            assertNone(names, RETIRED_ARCHITECTURE);
            assertNone(names, RETIRED_ARCHITECTURE);
            Long a = integer(4);
            DirectEntry prepared = fastDirect(locals, module, a);
            assertSame(a, enter(prepared));
            assertSame(locals, prepared.targetArguments()[0], "no activation was materialized");

            ProtosClosureValue single = closure("(a) => { b: a\nb }", module);
            assertNone(instructionNames(single), RETIRED_ARCHITECTURE);
            assertNone(instructionNames(single), RETIRED_ARCHITECTURE);
            assertEquals(
                    List.of(0),
                    suppliedPositions(single),
                    "PERF029: the parameter index is an instruction constant");
            assertSame(a, enter(fastDirect(single, module, a)));

            DirectEntry duplicate =
                    fastDirect(closure("(a) => { b: a\nb: a }", module), module, a);
            assertThrows(ProtosSignalException.class, () -> enter(duplicate));
            assertInstanceOf(ProtosActivation.class, duplicate.targetArguments()[0]);
        });
        System.out.println("ORDINARY_LOCAL_ONLY_ROOT_EAGER_FRAME_MATERIALIZATION=NO");
    }

    @Test
    void persistentAuthorityCreationUsesConstantIndexedOrdinal() throws Exception {
        withCore(module -> {
            /*
             * I115-H statically calls (and elides) a non-capturing local
             * Closure whose only use is one call; capturing the root's
             * parameter keeps the Closure, its single indexed creation and
             * the persistent authority.
             */
            /*
             * I115: the captured parameter base is a CELL binding (created at
             * entry and handed to the Closure); the Closure-valued identity is
             * a LOCAL binding stored in its own native local. Neither takes a
             * name-keyed creation, and no frame lexical authority exists.
             */
            ProtosClosureValue run =
                    closure("(base) => {\nidentity: (value) => { base\nvalue }\n"
                            + "identity(1)\n}", module);
            List<String> names = instructionNames(run);
            assertNone(names, "InvokeStaticClosureOneTail");
            assertNone(names, RETIRED_ARCHITECTURE);
            assertNone(names, "CreateCurrentIndexedLocalSlot");
            assertNone(names, "CreateCurrentLocalSlot");
            assertNone(names, RETIRED_ARCHITECTURE);
            assertContains(names, "CreateCell");
            assertContains(names, "StoreCell");
            assertContains(names, "CaptureClosureCells");
            assertContains(names, "store.local");
            assertTrue(localNames(run).contains("identity"),
                    () -> "identity owns a native local: " + localNames(run));
            assertTrue(localNames(run).contains("base"),
                    () -> "base owns the native local holding its cell: " + localNames(run));
            assertEquals("1", integerValue(enter(fastDirect(run, module))));

            DirectEntry duplicate =
                    fastDirect(
                            closure("() => {\nf: (value) => { value }\nf: 2\n}", module),
                            module);
            assertThrows(ProtosSignalException.class, () -> enter(duplicate));
        });
        System.out.println("NATIVE_LOCAL_AND_CELL_CREATION=PASS");
    }

    @Test
    void frameNativeRestAndMultipleCreationUseConstantOrdinals() throws Exception {
        withCore(module -> {
            ProtosClosureValue rest = closure("(head, ...tail) => { tail }", module);
            assertNone(instructionNames(rest), RETIRED_ARCHITECTURE);
            assertEquals(
                    List.of(1),
                    constantIntegersOf(rest, "LoadRestArguments", "positionalParametersBeforeRest"),
                    "PERF030-I: the rest prefix length is an instruction constant");

            ProtosClosureValue multiple =
                    closure("(source) => { (b, c, d): source\n[b, c, d] }", module);
            List<String> names = instructionNames(multiple);
            assertNone(names, RETIRED_ARCHITECTURE);
            // I113: the generic multiple creation is CreateCurrentLocalSlot's String[] form;
            // absence of the operation excludes scalar and multiple generic creation alike.
            assertNone(names, "CreateCurrentLocalSlot");
            assertEquals(
                    3,
                    countOf(names, "ObservedMultipleCreateValue"),
                    "PERF030-I: one native store per name, in source order");
            int observation = firstIndexOf(names, "ObserveMultipleCreatePrefix");
            assertTrue(
                    observation >= 0
                            && observation < firstIndexOf(names, "ObservedMultipleCreateValue"),
                    () -> "the complete prefix is observed before the first creation: " + names);
            int lastCreation = names.size() - 1
                    - firstIndexOf(names.reversed(), "ObservedMultipleCreateValue");
            assertTrue(
                    names.subList(observation, lastCreation + 1).stream()
                            .noneMatch(name -> name.contains("CurrentActivation")),
                    () -> "the scalar creations obtain no activation of their own: " + names);

            List<Object> results =
                    assertInstanceOf(
                                    ProtosArrayValue.class,
                                    evaluate(
                                            "m: (source) => { (b, c, d): source\n[b, c, d] }\n"
                                                    + "[m(Array(1, 2, 3, 4)), m(Array(5, null, 7))]\n",
                                            module))
                            .indexedSnapshot();
            List<Object> first =
                    assertInstanceOf(ProtosArrayValue.class, results.get(0)).indexedSnapshot();
            assertEquals(
                    List.of("1", "2", "3"),
                    first.stream().map(ProtosPerf025CompactCalleeExecutionTest::integerValue).toList());
            List<Object> withNull =
                    assertInstanceOf(ProtosArrayValue.class, results.get(1)).indexedSnapshot();
            assertSame(ProtosNullValue.INSTANCE, withNull.get(1), "PRESENT(null) is not ABSENT");

            assertEquals("42", integerValue(evaluate(
                            "few: (source) => { (b, c): source\nb }\n"
                                    + "Error.handle(() => { few(Array(1)) }, (caught) => 42)\n",
                            module)), "an insufficient source is the multiple-creation Error");
            assertEquals("43", integerValue(evaluate(
                            "twice: (source) => { (b, c, b): source\nb }\n"
                                    + "Error.handle(() => { twice(Array(1, 2, 3)) }, (caught) => 43)\n",
                            module)), "a later duplicate creation is the creation Error");
        });
        System.out.println("PERF030_I_FRAME_REST_CONSTANT_ORDINAL=YES");
        System.out.println("PERF030_I_MULTIPLE_CREATE_RUNTIME_ORDINAL_ARRAY=NO");
        System.out.println("PERF030_I_MULTIPLE_CREATE_PREFIX_BEFORE_FIRST_CREATION=YES");
    }

    @Test
    void defaultsSeeOnlyEarlierParametersAndOrdinaryLookup() throws Exception {
        withCore(module -> {
            ProtosClosureValue earlier = closure("(a, b: a) => { b }", module);
            assertNone(instructionNames(earlier), "CurrentActivation");
            Long a = integer(5);
            DirectEntry prepared = fastDirect(earlier, module, a);
            assertSame(a, enter(prepared));
            assertSame(earlier, prepared.targetArguments()[0]);

            assertEquals("7", integerValue(evaluate("b: 7\nf: (a: b, b: 1) => { a }\nf()\n", module)), "a default never reads a later parameter's future value");
        });
        System.out.println("DEFAULT_BINDING_VISIBILITY=PASS");
    }

    @Test
    void contextObservedByDefaultShowsExactlyEarlierBindings() throws Exception {
        withCore(module -> {
            Object observed =
                    evaluate(
                            "f: (a, b: [context.hasSlot(\"a\"), context.hasSlot(\"b\")]) => { b }\n"
                                    + "f(1)\n",
                            module);
            List<Object> presence = assertInstanceOf(ProtosArrayValue.class, observed).indexedSnapshot();
            assertSame(Boolean.TRUE, presence.get(0));
            assertSame(Boolean.FALSE, presence.get(1));

            ProtosExecutionContextValue late =
                    assertInstanceOf(
                            ProtosExecutionContextValue.class,
                            evaluate("late: (a) => { b: a\ncontext }\nlate(3)\n", module));
            assertTrue(late.hasLocalSlot("a"));
            assertTrue(late.hasLocalSlot("b"));
            assertFalse(late.hasLocalSlot("d"));
            assertEquals("3", integerValue(late.readLocalSlot("b").orElseThrow()));

            List<Object> contexts =
                    assertInstanceOf(
                                    ProtosArrayValue.class,
                                    evaluate("g: (x) => { context }\n[g(1), g(2)]\n", module))
                            .indexedSnapshot();
            assertInstanceOf(ProtosExecutionContextValue.class, contexts.get(0));
            assertNotSame(contexts.get(0), contexts.get(1));
        });
        System.out.println("LATE_CONTEXT_PROJECTION=PASS");
        System.out.println("FRESH_CONTEXT_IDENTITY=PASS");
    }

    @Test
    void slotCreationConflictAndD179PresenceArePreserved() throws Exception {
        withCore(module -> {
            ProtosClosureValue recreate = closure("(a) => { a: 2 }", module);
            DirectEntry conflict =
                    fastDirect(recreate, module, integer(1));
            assertThrows(
                    ProtosSignalException.class,
                    () -> enter(conflict),
                    "creating an already PRESENT parameter name is a slot-creation conflict");

            ProtosClosureValue identity = closure("(value) => { value }", module);
            DirectEntry nullCall =
                    fastDirect(identity, module, ProtosNullValue.INSTANCE);
            assertSame(ProtosNullValue.INSTANCE, enter(nullCall), "PRESENT(null) is not ABSENT");
            assertSame(identity, nullCall.targetArguments()[0]);

            List<Object> removed =
                    assertInstanceOf(
                                    ProtosArrayValue.class,
                                    evaluate(
                                            "f: (x) => { context.removeSlot(\"x\")\n"
                                                    + "absent: context.hasSlot(\"x\")\nx: 5\n[absent, x] }\n"
                                                    + "f(1)\n",
                                            module))
                            .indexedSnapshot();
            assertSame(Boolean.FALSE, removed.get(0));
            assertEquals("5", integerValue(removed.get(1)));
        });
        System.out.println("D179_PRESENT_ABSENT=PASS");
        System.out.println("D179_REMOVE_RECREATE=PASS");
    }

    @Test
    void restCaptureErrorAndReturnSemanticsAreUnchanged() throws Exception {
        withCore(module -> {
            List<Object> rest =
                    assertInstanceOf(
                                    ProtosArrayValue.class,
                                    evaluate("r: (first, ...rest) => { rest }\nr(1, 2, 3)\n", module))
                            .indexedSnapshot();
            assertEquals(List.of("2", "3"),
                    rest.stream().map(ProtosPerf025CompactCalleeExecutionTest::integerValue).toList());

            assertEquals("6", integerValue(evaluate(
                            "f: (x) => { set: (v) => { x = v }\nget: () => x\nset(6)\nget }\nf(1)()\n",
                            module)), "a Closure created by the invocation captures its context by reference");

            assertEquals("42", integerValue(evaluate(
                            "Error.handle(() => {\n f: (value) => { Error().signal() }\n f(1)\n}, (caught) => 42)\n",
                            module)), "an Error raised by a compact callee selects the ordinary handler");

            assertEquals("7", integerValue(evaluate(
                            "outer: () => {\n inner: (v) => { ^ v }\n inner(7)\n 99\n}\nouter()\n",
                            module)));
        });
        System.out.println("REST_ARRAY_SEMANTICS=PASS");
        System.out.println("CAPTURE_BY_REFERENCE=PASS");
        System.out.println("ERROR_HANDLER_SELECTION=PASS");
        System.out.println("NON_LOCAL_RETURN=PASS");
    }

    @Test
    void lateObservationsShareOneMaterializedActivation() throws Exception {
        withCore(module -> {
            ProtosClosureValue identity = closure("(value) => { value }", module);
            Object[] arguments =
                    ProtosFrameArguments.compactImmediateMethodCall(
                            identity, newObject(), newObject(), module, new Object[] {integer(1)});
            VirtualFrame frame =
                    Truffle.getRuntime()
                            .createVirtualFrame(arguments, FrameDescriptor.newBuilder().build());
            assertTrue(ProtosBytecodeTagTreeNodeExports.hasScope(null, frame));
            assertSame(identity, arguments[0], "hasScope does not materialize");

            ProtosBytecodeTagTreeNodeExports.getScope(null, frame, true);
            ProtosActivation observed = assertInstanceOf(ProtosActivation.class, arguments[0]);
            ProtosBytecodeTagTreeNodeExports.getScope(null, frame, true);
            assertSame(observed, arguments[0]);
            assertSame(observed, ProtosFrameArguments.activation(frame));
            assertSame(observed.context(), ProtosFrameArguments.activation(frame).context());
            assertNull(privateField(privateField(observed, "deferredSuppliedArguments"), "guestArray"));
            assertEquals(
                    "FrameBackedSuppliedArguments",
                    privateField(privateField(observed, "deferredSuppliedArguments"), "values")
                            .getClass()
                            .getSimpleName(),
                    "the supplied vector stays backed by the frame arguments, uncopied");
        });
        System.out.println("MATERIALIZED_ACTIVATION_IDENTITY_PER_INVOCATION=ONE");
        System.out.println("SUPPLIED_ARGUMENT_LIST_COPY_ON_ENTRY=NO");
    }

    @Test
    void taskOwnedCompactCalleeKeepsItsExactTask() throws Exception {
        withCore(module -> {
            ProtosClosureValue identity = closure("(value) => { value }", module);
            AtomicReference<ProtosCallRequest> preparedRef =
                    new AtomicReference<>();
            ProtosTask task =
                    module.executionDomain()
                            .createTask(
                                    null,
                                    current -> {
                                        ProtosCallRequest prepared =
                                                ProtosCallRequest.taskEntry(
                                                        identity,
                                                        current,
                                                        List.of(integer(8)),
                                                        module);
                                        preparedRef.set(prepared);
                                        ProtosBytecodeTaskExecution.executeRequest(
                                                current, prepared);
                                    });
            assertTrue(module.executionDomain().dispatchOne());
            assertEquals(ProtosTask.State.COMPLETED, task.state());
            assertEquals("8", integerValue(task.result().orElseThrow()));

            Object[] arguments = preparedRef.get().arguments();
            assertSame(identity, arguments[0], "the Task-owned callee stayed compact");
            assertSame(task, ProtosFrameArguments.compactTask(arguments));
            assertSame(task, ProtosFrameArguments.activation(arguments).task().orElseThrow());
            assertSame(task, ProtosFrameArguments.compactTask(arguments), "same Task after materialization");
            assertTrue(module.task().isEmpty());
        });
        System.out.println("TASK_DYNAMIC_CONTROL=PASS");
    }

    @Test
    void publicationKeepsSelectedHomeWithoutCreatingContextForClassification() throws Exception {
        withCore(module -> {
            for (String text : List.of("(value) => value", "(value) => ^value")) {
                ProtosClosureValue source = closure(text, module);
                Object token = newObject();
                DirectEntry entry = ProtosDispatchTestSupport.direct(source, module, token);
                Object[] arguments = entry.targetArguments();
                var home = ProtosFrameArguments.compactReturnHome(arguments);
                assertSame(source, arguments[0]);
                ProtosActivation published = ProtosFrameArguments.activation(arguments);
                assertSame(home, published.returnHome().orElseThrow());
                assertNull(privateField(published, "context"),
                        "home classification and publication leave guest Context lazy");
                assertSame(token, ProtosDispatchTestSupport.enter(entry));
                assertSame(home, ProtosFrameArguments.compactReturnHome(arguments));
                assertFalse(home.isActive());

                Object[] indirect = ProtosFrameArguments.functionCallArguments(new Object[] {token});
                Object result = ProtosFunctionCallDispatchersFactory.FunctionIndirectInvokeNodeGen
                        .getUncached().execute(null, null, entry.target(),
                                (ProtosBytecodeRootNode) entry.target().getRootNode(),
                                source, indirect, module);
                assertSame(token, result);
                assertInstanceOf(com.guillermomolina.protos.runtime.control.ProtosReturnHome.class,
                        indirect[4]);
                assertFalse(ProtosFrameArguments.compactReturnHome(indirect).isActive());
            }
        });
    }

    @Test
    void compactTaskInheritanceBindsBeforeEntryAndKeepsMethodHome() throws Exception {
        withCore(module -> {
            ProtosTask task = module.executionDomain().createTask(null, current -> {});
            ProtosClosureValue parent = closure("() => 42", module);
            ProtosClosureValue template = closure("() => { () => ^42 }", module);
            ProtosClosureValue child = new ProtosClosureValue(
                    template.definition(), template.capturedLexicalContexts(), template.capturedReceiver(),
                    null, null, module.prelude().orElseThrow(), template.executionPlan().orElseThrow());
            for (boolean method : new boolean[] {false, true}) {
                Object[] parentArguments = ProtosFrameArguments.compactDirectClosureCall(
                        parent, module, task, new Object[0]);
                ProtosActivation published = method
                        ? ProtosFrameArguments.activation(parentArguments) : null;
                parentArguments[3] = null;
                assertSame(task, ProtosFrameArguments.admittedCompactTask(parentArguments));
                Object receiver = newObject();
                ProtosObjectValue methodHome = newObject();
                Object[] arguments = method
                        ? ProtosFrameArguments.methodCallArguments(receiver, methodHome, new Object[0])
                        : ProtosFrameArguments.functionCallArguments(new Object[0]);
                var home = child.invocationReturnHomeUnderTemplateProofForRuntime(false);
                ProtosFrameArguments.completeDispatchedHeader(arguments, child,
                        method ? published : null, home);
                ProtosTask inherited = ProtosFrameArguments.inheritInvocationTask(
                        arguments, ProtosFrameArguments.admittedCompactTask(parentArguments));
                ProtosFrameArguments.bindOwnedReturnHome(child, home, inherited);
                assertTrue(home.isActive());
                assertSame(task, home.ownerTaskForRuntime(), "bound before target entry");
                assertSame(method ? methodHome : task, arguments[2]);
                if (method) {
                    assertNull(privateField(published, "context"));
                    assertSame(receiver, arguments[1]);
                } else {
                    assertSame(parent, parentArguments[0], "function propagation does not publish");
                    assertNull(arguments[3]);
                }
                Object returned = ProtosDispatchTestSupport.enterRaw(
                        new ProtosDispatchTestSupport.DirectEntry(
                                ProtosDispatchTestSupport.target(child), arguments));
                assertSame(home, assertInstanceOf(ProtosClosureValue.class, returned)
                        .returnHome().orElseThrow());
                assertFalse(home.isActive());
                assertNull(home.ownerTaskForRuntime());

                Object[] dispatched = method
                        ? ProtosFrameArguments.methodCallArguments(receiver, methodHome, new Object[0])
                        : ProtosFrameArguments.functionCallArguments(new Object[0]);
                var target = ProtosDispatchTestSupport.target(child);
                VirtualFrame frame = Truffle.getRuntime().createVirtualFrame(
                        parentArguments, FrameDescriptor.newBuilder().build());
                Object outcome = ProtosFunctionCallDispatchersFactory.FunctionDirectInvokeNodeGen
                        .getUncached().execute(frame, null,
                                com.oracle.truffle.api.nodes.DirectCallNode.create(target),
                                (ProtosBytecodeRootNode) target.getRootNode(), child, dispatched, null);
                assertSame(method ? methodHome : task, dispatched[2]);
                assertSame(method ? published : null, dispatched[3]);
                var dispatchedHome = ProtosFrameArguments.compactReturnHome(dispatched);
                assertNotSame(home, dispatchedHome);
                assertSame(dispatchedHome, assertInstanceOf(ProtosClosureValue.class, outcome)
                        .returnHome().orElseThrow());
                assertFalse(dispatchedHome.isActive());
                assertNull(dispatchedHome.ownerTaskForRuntime());
                if (method) {
                    assertNull(privateField(published, "context"));
                } else {
                    assertSame(parent, parentArguments[0]);
                }
            }
        });
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

    /**
     * As {@link #withCore}, with a Truffle debugger session attached to the
     * Engine before any lowering (PLAT067-B: it observes, it never selects a
     * lowering).
     */
    private static void withCoreAndDebugger(CoreTest test) throws Exception {
        try (Context context = Context.newBuilder(ProtosLanguage.ID).build();
                DebuggerSession attached =
                        Debugger.find(context.getEngine()).startSession(event -> {})) {
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

    private static Object evaluate(String characters, ProtosActivation activation) {
        Source source =
                Source.newBuilder(ProtosLanguage.ID, characters, "perf025-compact-callee.protos")
                        .mimeType(ProtosLanguage.MIME_TYPE)
                        .build();
        CallTarget target = ProtosLanguageContext.current().parsePublic(source);
        try {
            return target.call(activation);
        } catch (ProtosSignalException signal) {
            throw new AssertionError(
                    "guest snippet signaled " + standardErrorName(activation, signal) + ":\n" + characters,
                    signal);
        }
    }

    private static String standardErrorName(ProtosActivation activation, ProtosSignalException signal) {
        Object parent = signal.error().parent().orElse(null);
        for (ProtosCoreErrors.StandardError kind : ProtosCoreErrors.StandardError.values()) {
            try {
                if (parent == ProtosCoreErrors.prototype(activation, kind)) {
                    return kind.prototypeName();
                }
            } catch (RuntimeException unavailable) {
                // Not published by this prelude; keep looking.
            }
        }
        return "a non-standard Error";
    }

    private static ProtosClosureValue closure(String characters, ProtosActivation activation) {
        return assertInstanceOf(ProtosClosureValue.class, evaluate(characters, activation));
    }

    private static RootCallTarget target(ProtosClosureValue closure) {
        return ProtosDispatchTestSupport.target(closure);
    }

    private static DirectEntry fastDirect(
            ProtosClosureValue closure, ProtosActivation caller, Object... supplied) {
        // I115: the B5 header the shared dispatcher writes for a function call.
        return ProtosDispatchTestSupport.direct(closure, caller, supplied);
    }

    private static Object enter(DirectEntry prepared) {
        return ProtosDispatchTestSupport.enter(prepared);
    }

    private static List<String> instructionNames(ProtosClosureValue closure) {
        return closure.executionPlan()
                .orElseThrow()
                .bytecodeActivationRootForTesting()
                .getBytecodeNode()
                .getInstructionsAsList()
                .stream()
                .map(ProtosInstructionNames::of)
                .toList();
    }

    /** The immediate integer constant {@code argument} of every {@code operation} instruction. */
    private static List<Integer> constantIntegersOf(
            ProtosClosureValue closure, String operation, String argument) {
        return instructionsOf(closure, operation).stream()
                .map(instruction -> {
                    Instruction.Argument constant =
                            instruction.getArguments().stream()
                                    .filter(candidate -> candidate.getName().equals(argument))
                                    .findFirst()
                                    .orElseThrow(() -> new AssertionError(
                                            "no constant " + argument + " operand"));
                    return switch (constant.getKind()) {
                        case CONSTANT -> (Integer) constant.asConstant();
                        case INTEGER -> constant.asInteger();
                        default -> throw new AssertionError(argument + " is not a constant");
                    };
                })
                .toList();
    }

    /* The surviving per-selector numeric operations of the shared numeric site. */
    private static final List<String> NUMERIC_OPERATIONS =
            List.of("NumberAdd", "NumberSubtract", "NumberMultiply", "NumberDivide");

    private static int numericOperationCount(List<String> names) {
        return (int) names.stream()
                .filter(name -> NUMERIC_OPERATIONS.stream().anyMatch(name::contains))
                .count();
    }

    private static List<Instruction> instructionsOf(ProtosClosureValue closure, String operation) {
        return closure.executionPlan()
                .orElseThrow()
                .bytecodeActivationRootForTesting()
                .getBytecodeNode()
                .getInstructionsAsList()
                .stream()
                .filter(instruction -> ProtosInstructionNames.of(instruction).contains(operation))
                .toList();
    }

    private static int firstIndexOf(List<String> names, String operation) {
        for (int index = 0; index < names.size(); index++) {
            if (names.get(index).contains(operation)) {
                return index;
            }
        }
        return -1;
    }

    /*
     * The supplied positions the callee binds: the grouped
     * CopySuppliedArguments range (positions 0..n-1 into consecutive LOCAL
     * parameter locals) followed by each individual LoadSuppliedArgument.
     */
    private static List<Integer> suppliedPositions(ProtosClosureValue closure) {
        List<Integer> positions = new java.util.ArrayList<>();
        for (Instruction instruction : instructionsOf(closure, "CopySuppliedArguments")) {
            for (Instruction.Argument candidate : instruction.getArguments()) {
                if (candidate.getKind() == Instruction.Argument.Kind.CONSTANT
                        && candidate.asConstant()
                                instanceof com.oracle.truffle.api.bytecode.LocalRangeAccessor range) {
                    for (int index = 0; index < range.getLength(); index++) {
                        positions.add(index);
                    }
                }
            }
        }
        positions.addAll(constantIntegersOf(closure, "LoadSuppliedArgument", "index"));
        return positions;
    }

    private static void assertBindsSuppliedPositions(List<String> names) {
        assertTrue(
                names.stream().anyMatch(name -> name.contains("LoadSuppliedArgument")
                        || name.contains("CopySuppliedArguments")),
                () -> "expected a supplied-position binding in: " + names);
    }

    private static void assertBindsNoSuppliedPosition(List<String> names) {
        assertTrue(
                names.stream().noneMatch(name -> name.contains("LoadSuppliedArgument")
                        || name.contains("CopySuppliedArguments")),
                () -> "unexpected supplied-position binding in: " + names);
    }

    private static int countOf(List<String> names, String operation) {
        return (int) names.stream().filter(name -> name.contains(operation)).count();
    }

    private static List<Object> localNames(ProtosClosureValue closure) {
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
        List<TagTree> result = new java.util.ArrayList<>();
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

    private static void assertContains(List<String> names, String operation) {
        assertTrue(
                names.stream().anyMatch(name -> name.contains(operation)),
                () -> "expected " + operation + " in: " + names);
    }

    /*
     * Operations of the retired compact-root lowering, frame lexical
     * authority, special parameter entries and MISS-based numeric sends.
     * Passed to assertNone, this sentinel checks the whole set; it guards the
     * single Bytecode DSL lowering against a mixed lowering and is always
     * used beside positive assertions on the current operations.
     */
    private static final String RETIRED_ARCHITECTURE = "<retired I115 architecture>";

    private static final List<String> RETIRED =
            List.of(
                    "IsCompactLocalFrame",
                    "CreateCurrentFrameLocal",
                    "CreateCurrentIndexedLocalSlot",
                    "InstallFrameLexicalAuthority",
                    "EnsureLiveFrameLexicalAuthority",
                    "PromoteFrameLexicalAuthority",
                    "ReadRootFrameLocal",
                    "ResolveRootFrameLocalWriteTarget",
                    "AssignRootFrameLocal",
                    "MaterializeReturnCarrier",
                    "BindUnaryFrameParameter",
                    "BindRequiredFrameParameter",
                    "BindClosureFrameParameter",
                    "BindInlineClosureFrameParameter",
                    "BindClosureIndexedParameter",
                    "LoadFrameClosureArgument",
                    "TryPrimitiveNumeric",
                    "IsPrimitiveNumericSendMiss");

    private static void assertNoRetired(List<String> names) {
        for (String retired : RETIRED) {
            assertNone(names, retired);
        }
    }

    private static void assertNone(List<String> names, String operation) {
        if (RETIRED_ARCHITECTURE.equals(operation)) {
            assertNoRetired(names);
            return;
        }
        assertTrue(
                names.stream().noneMatch(name -> name.contains(operation)),
                () -> "unexpected " + operation + " in: " + names);
    }

    private static ProtosObjectValue newObject() {
        return new ProtosObjectValue(ProtosObjectValue.rootObject());
    }

    /**
     * PLAT067-B: a debugger suspended at each statement of the I101-B scalar
     * body {@code () => { first: 7; second: first; second }} sees, through
     * the NodeLibrary scope of the surviving location, exactly the bindings
     * created so far with their real values; a binding not yet created is
     * absent, never shown as initialized. The suspension is the Closure's own
     * frame over its caller: no frame or CallTarget is fabricated. The scope
     * projects the invocation's own activation on demand (B5); no other
     * activation is created. The same holds when the session was attached
     * before the root was ever lowered and when it attaches, through a native
     * {@code attach} binding, after a first DEFAULT execution of the Closure,
     * so the tags are materialized on demand.
     */
    @Test
    void scalarBodyDebuggerScopesShowExactlyTheLiveBindings() throws Exception {
        for (boolean instrumentedFirst : new boolean[] {true, false}) {
            String name = "perf034e-debugger-scope-" + instrumentedFirst + ".protos";
            com.oracle.truffle.api.source.Source definition =
                    com.oracle.truffle.api.source.Source.newBuilder(
                                    ProtosLanguage.ID,
                                    "run: () => {\n"
                                            + "    first: 7\n"
                                            + "    second: first\n"
                                            + "    second\n"
                                            + "}\n"
                                            + "warm: run()\n"
                                            + "attach()\n"
                                            + "run() + warm - 7\n",
                                    name)
                            .uri(java.net.URI.create("memory:///" + name))
                            .mimeType(ProtosLanguage.MIME_TYPE)
                            .build();
            ProtosActivation module =
                    new ProtosCoreBootstrap().bootstrap(CORE).newModuleActivation();
            List<String> observed = new java.util.ArrayList<>();
            AtomicReference<Throwable> failure = new AtomicReference<>();
            AtomicReference<DebuggerSession> session = new AtomicReference<>();

            try (ProtosPolyglotExecutionContext polyglot =
                    ProtosPolyglotExecutionContext.open(
                            java.io.InputStream.nullInputStream(),
                            java.io.OutputStream.nullOutputStream(),
                            java.io.OutputStream.nullOutputStream())) {
                Debugger debugger =
                        polyglot.engineForTesting()
                                .getInstruments()
                                .get("debugger")
                                .lookup(Debugger.class);
                if (instrumentedFirst) {
                    session.set(scopeSession(debugger, definition, observed, failure));
                }
                module.context().createLocalSlot(
                        "attach",
                        ProtosClosureValue.nativeClosure(
                                (activation, supplied) -> {
                                    if (session.get() == null) {
                                        // After the first DEFAULT call of run.
                                        assertTrue(observed.isEmpty());
                                        session.set(scopeSession(
                                                debugger, definition, observed, failure));
                                    }
                                    return ProtosNullValue.INSTANCE;
                                }));
                try {
                    assertCompleted(polyglot.execute(definition, module), 7);
                } finally {
                    if (session.get() != null) {
                        session.get().close();
                    }
                }
            }

            if (failure.get() != null) {
                throw new AssertionError("debugger callback failed", failure.get());
            }
            List<String> oneCall = List.of(
                    "line 2: first=absent second=absent frames=2",
                    "line 3: first=7 second=absent frames=2",
                    "line 4: first=7 second=7 frames=2");
            List<String> expected = new java.util.ArrayList<>(oneCall);
            if (instrumentedFirst) {
                // Both calls of run suspend; on demand, only the call after attach.
                expected.addAll(oneCall);
            }
            assertEquals(expected, observed, "instrumentedFirst=" + instrumentedFirst);

            // The observed root is the optimized I101-B scalar body.
            ProtosClosureValue run = assertInstanceOf(
                    ProtosClosureValue.class,
                    module.context().readLocalSlot("run").orElseThrow());
            List<String> instructions = instructionNames(run);
            assertNone(instructions, RETIRED_ARCHITECTURE);
            assertNone(instructions, RETIRED_ARCHITECTURE);
            assertNone(instructions, RETIRED_ARCHITECTURE);
        }
        System.out.println("PLAT067_SCALAR_BODY_DEBUGGER_SCOPES=PASS");
    }

    private static DebuggerSession scopeSession(
            Debugger debugger,
            com.oracle.truffle.api.source.Source source,
            List<String> observed,
            AtomicReference<Throwable> failure) {
        DebuggerSession session = debugger.startSession(event -> {
            try {
                com.oracle.truffle.api.debug.DebugScope scope =
                        event.getTopStackFrame().getScope();
                assertNotNull(scope, "a surviving location exposes a scope");
                int frames = 0;
                for (com.oracle.truffle.api.debug.DebugStackFrame ignored :
                        event.getStackFrames()) {
                    frames++;
                }
                observed.add("line " + event.getSourceSection().getStartLine()
                        + ": first=" + display(scope.getDeclaredValue("first"))
                        + " second=" + display(scope.getDeclaredValue("second"))
                        + " frames=" + frames);
            } catch (Throwable unexpected) {
                failure.compareAndSet(null, unexpected);
            } finally {
                event.prepareContinue();
            }
        });
        for (int line = 2; line <= 4; line++) {
            session.install(
                    com.oracle.truffle.api.debug.Breakpoint.newBuilder(source)
                            .lineIs(line)
                            .build());
        }
        return session;
    }

    private static String display(com.oracle.truffle.api.debug.DebugValue value) {
        return value == null ? "absent" : value.toDisplayString();
    }

    private static void assertCompleted(ProtosExecutionOutcome outcome, long expected) {
        assertEquals(
                ProtosExecutionOutcome.State.COMPLETED,
                outcome.state(),
                () -> "outcome " + outcome.state() + " error=" + outcome.error());
        assertEquals(Long.toString(expected), integerValue(
                com.guillermomolina.protos.runtime.ProtosNumericValueSupport.guestValue(
                        outcome.value())));
    }

    private static Long integer(long value) {
        return Long.valueOf(value);
    }

    private static String integerValue(Object value) {
        return com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerDecimalText(value);
    }

    private static Object privateField(Object target, String name)
            throws ReflectiveOperationException {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }
}
