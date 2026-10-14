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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.guillermomolina.protos.runtime.ProtosActivation;
import com.guillermomolina.protos.runtime.ProtosArrayValue;
import com.guillermomolina.protos.runtime.ProtosBooleanValue;
import com.guillermomolina.protos.runtime.ProtosClosureValue;
import com.guillermomolina.protos.runtime.ProtosCoreErrors;
import com.guillermomolina.protos.runtime.ProtosExecutionContextValue;
import com.guillermomolina.protos.runtime.ProtosIntegerValue;
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
    void identityCalleeExecutesWithoutMaterializingAnything() throws Exception {
        withCore(module -> {
            ProtosClosureValue identity = closure("(value) => { value }", module);
            List<String> names = instructionNames(identity);
            assertNone(names, "CurrentActivation");
            assertNone(names, "InstallFrameLexicalAuthority");
            assertNone(names, "BindClosureParameter");
            assertContains(names, "BindClosureFrameParameter");
            assertContains(names, "ReadRootFrameLocal");
            assertEquals(
                    0,
                    constantOrdinalOf(identity, "BindClosureFrameParameter"),
                    "PERF029: the LocalRangeAccessor index is an instruction constant");

            ProtosIntegerValue one = integer(1);
            Object[] method =
                    ProtosFrameArguments.compactImmediateMethodCall(
                            identity, newObject(), newObject(), module, new Object[] {one});
            assertSame(one, target(identity).call(method));
            assertSame(identity, method[0], "no activation was materialized or published");

            ProtosIntegerValue two = integer(2);
            ProtosBytecodeRootNode.PreparedClosureCall direct = fastDirect(identity, module, two);
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
    void multipleParametersBindInOrderAndCountErrorsMaterializeOnDemand() throws Exception {
        withCore(module -> {
            ProtosClosureValue middle = closure("(a, b, c) => { b }", module);
            assertNone(instructionNames(middle), "CurrentActivation");
            ProtosIntegerValue a = integer(1);
            ProtosIntegerValue b = integer(2);
            ProtosIntegerValue c = integer(3);
            ProtosBytecodeRootNode.PreparedClosureCall ordinary = fastDirect(middle, module, a, b, c);
            assertSame(b, enter(ordinary));
            assertSame(middle, ordinary.targetArguments()[0]);

            ProtosBytecodeRootNode.PreparedClosureCall missing = fastDirect(middle, module, a, b);
            assertThrows(ProtosSignalException.class, () -> enter(missing));
            assertInstanceOf(
                    ProtosActivation.class,
                    missing.targetArguments()[0],
                    "the argument-count Error materialized the exact activation");

            ProtosBytecodeRootNode.PreparedClosureCall excess =
                    fastDirect(middle, module, a, b, c, integer(4));
            assertThrows(ProtosSignalException.class, () -> enter(excess));
            /*
             * The grammar requires required parameters to precede defaulted
             * ones, so no default effect can precede a missing required
             * argument; the earlier-parameter effect is the binding itself.
             */
        });
        System.out.println("LEFT_TO_RIGHT_PARAMETER_BINDING=PASS");
        System.out.println("MISSING_ARGUMENT_PRECEDENCE=PASS");
        System.out.println("EXCESS_ARGUMENT_PRECEDENCE=PASS");
    }

    @Test
    void zeroParameterLocalCreationAndReadStayCompact() throws Exception {
        withCore(module -> {
            ProtosClosureValue run =
                    closure("() => { value: 1\nvalue }", module);

            List<String> instructions = instructionNames(run);
            assertEquals(
                    List.of("load.constant", "store.local", "load.local", "return"),
                    instructions,
                    "NORMAL must emit only the proven local Builder");
            assertNone(instructions, "CurrentActivation");
            assertNone(instructions, "InstallFrameLexicalAuthority");

            for (int iteration = 0; iteration < 3; iteration++) {
                ProtosBytecodeRootNode.PreparedClosureCall prepared =
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
            // A raw CallTarget exposes the private carrier. Guest calls
            // project it through PreparedClosureCall.finish().
            assertEquals(Long.valueOf(1L), target(run).call(method));
            assertSame(
                    run,
                    method[0],
                    "compact immediate method execution must remain compact");

            ProtosClosureValue duplicate =
                    closure("() => { value: 1\nvalue: 2\nvalue }", module);
            ProtosBytecodeRootNode.PreparedClosureCall invalid =
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
                            "load.constant", "store.local",
                            "load.constant", "store.local",
                            "load.local", "return"),
                    instructions,
                    "NORMAL emits the minimal two-binding Builder");
            assertNone(instructions, "CurrentActivation");
            assertNone(instructions, "InstallFrameLexicalAuthority");

            for (int i = 0; i < 3; i++) {
                ProtosBytecodeRootNode.PreparedClosureCall prepared =
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
                    "IsCompactLocalFrame");
            assertThrows(
                    ProtosSignalException.class,
                    () -> enter(fastDirect(duplicate, module)));
        });
    }

    @Test
    void perf034ScalarLaneFollowsPublishedActivation() throws Exception {
        withCoreDebug(module -> {
            ProtosClosureValue scalar =
                    closure("() => { value: 1\nvalue }", module);
            assertContains(instructionNames(scalar), "IsCompactLocalFrame");

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

                assertEquals("1", integerValue(target(scalar).call(arguments)), "the authoritative path creates and reads the binding");
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
            assertContains(
                    instructionNames(observedClosure),
                    "InstallFrameLexicalAuthority");

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
                assertNone(instructions, "InstallFrameLexicalAuthority");
                // NORMAL has no materialization or dispatch branches.
                // Alias RHS loads remain explicit for Graal to optimize.
                assertNone(instructions, "IsCompactLocalFrame");
                assertNone(instructions, "CreateCurrentFrameLocal");
                assertNone(instructions, "ReadRootFrameLocal");
                assertNone(instructions, "MaterializeReturnCarrier");
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
                assertEquals(
                        body.created().size() * 2 + 2,
                        instructions.size(),
                        () -> body.characters() + ": " + instructions);
                List<Object> locals = localNames(scalar);
                if (body.literalOnly()) {
                    assertFalse(
                            locals.contains("createValue"),
                            () -> "a literal creation needs no createValue: " + locals);
                }

                for (int iteration = 0; iteration < 3; iteration++) {
                    ProtosBytecodeRootNode.PreparedClosureCall prepared =
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
                assertEquals(
                        Long.valueOf(body.expected()),
                        target(scalar).call(method),
                        "raw internal entry returns the private Long carrier");
                assertSame(
                        scalar, method[0],
                        "compact method execution must remain compact");
            }
        });
    }

    @Test
    void perf034eMaterializedEntryTakesAuthoritativeLanes() throws Exception {
        withCoreDebug(module -> {
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
                    assertEquals(Long.toString(body.expected()), integerValue(target(scalar).call(arguments)), body.characters());
                    assertSame(published, arguments[0]);
                    assertSame(observed, published.context());
                    for (String name : body.created()) {
                        assertTrue(
                                observed.readLocalSlot(name).isPresent(),
                                () -> body.characters()
                                        + ": the observed context holds " + name);
                    }
                }
            }
        });
    }

    @Test
    void perf034eFinalReadKeepsStatementAndExpressionTags() throws Exception {
        withCoreDebug(module -> {
            ProtosClosureValue scalar =
                    closure("() => { first: 7\nsecond: first\nsecond }", module);
            ProtosSemanticBytecodeRootNode root =
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
            assertEquals("7", integerValue(target(scalar).call(observed)));
        });
    }

    @Test
    void perf034eAliasAdmissionExcludesUnprovenForms() throws Exception {
        withCore(module -> {
            ProtosClosureValue duplicate =
                    closure(
                            "() => { first: 7\nsecond: first\nsecond: first\nsecond }",
                            module);
            assertNone(instructionNames(duplicate), "IsCompactLocalFrame");
            assertThrows(
                    ProtosSignalException.class,
                    () -> enter(fastDirect(duplicate, module)));

            evaluate("perf034eOuter: 5", module);
            ProtosClosureValue outer =
                    closure("() => { first: perf034eOuter\nfirst }", module);
            assertNone(instructionNames(outer), "IsCompactLocalFrame");
            assertEquals("5", integerValue(enter(fastDirect(outer, module))));

            ProtosClosureValue captured =
                    closure(
                            "() => { first: 7\nreader: () => first\nsecond: first\nsecond }",
                            module);
            assertNone(instructionNames(captured), "IsCompactLocalFrame");
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

            for (ProtosClosureValue candidate : List.of(direct, locals, aliases)) {
                assertEquals("3", integerValue(enter(fastDirect(candidate, module))));
                assertTrue(containsLoweredIntegerConstant(candidate, 3L),
                        "lowerer must emit the calculated result as an operand");
            }
            assertNone(instructionNames(direct), "TryPrimitiveNumericSendOne");
            assertContains(instructionNames(locals), "TryPrimitiveNumericSendOne");
            assertContains(instructionNames(aliases), "TryPrimitiveNumericSendOne");

            assertContains(instructionNames(locals), "IsCompactLocalFrame");
            assertContains(instructionNames(aliases), "IsCompactLocalFrame");

            assertNone(instructionNames(locals), "InstallFrameLexicalAuthority");
        });
    }

    @Test
    void perf043FoldKeepsObservedActivationAndSourceTags() throws Exception {
        withCore(module -> {
            ProtosClosureValue sum =
                    closure("() => { left: 1\nright: 2\nleft + right }", module);

            Object[] arguments =
                    ProtosFrameArguments.compactDirectClosureCall(
                            sum, module, null, new Object[0]);
            ProtosActivation activation = ProtosFrameArguments.activation(arguments);
            ProtosObjectValue observed = activation.context();

            assertEquals("3", integerValue(target(sum).call(arguments)));
            assertEquals(
                    "1", integerValue(observed.readLocalSlot("left").orElseThrow()));
            assertEquals(
                    "2", integerValue(observed.readLocalSlot("right").orElseThrow()));

            ProtosSemanticBytecodeRootNode root =
                    sum.executionPlan()
                            .orElseThrow()
                            .bytecodeActivationRootForTesting();
            root.getRootNodes().ensureComplete();
            List<TagTree> statements =
                    collectTags(
                            root.getBytecodeNode().getTagTree(),
                            StandardTags.StatementTag.class);
            assertEquals(
                    List.of("left: 1", "right: 2", "left + right"),
                    statements.stream()
                            .map(tag -> tag.getSourceSection().getCharacters().toString())
                            .toList());
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
                assertTrue(containsLoweredIntegerConstant(candidate,
                        candidate == nested ? 6L : candidate == tree ? 10L : 3L));
                assertEquals(candidate == nested ? "6" : candidate == tree ? "10" : "3",
                        integerValue(enter(fastDirect(candidate, module))));
                var root = candidate.executionPlan()
                        .orElseThrow().bytecodeActivationRootForTesting();
                root.getRootNodes().ensureComplete();
                var calls = collectTags(root.getBytecodeNode().getTagTree(),
                        StandardTags.CallTag.class);
                int count = candidate == locals || candidate == aliases ? 1 : 0;
                assertEquals(count, calls.size(),
                        "folded sends retain each source-visible CallTag");
                assertTrue(calls.stream().allMatch(tag ->
                        tag.getSourceSection() != null
                                && tag.getSourceSection().getCharacters().toString().contains("+")));
            }

            assertNone(instructionNames(direct), "TryPrimitiveNumericSendOne");
            assertNone(instructionNames(nested), "TryPrimitiveNumericSendOne");
            assertNone(instructionNames(tree), "TryPrimitiveNumericSendOne");
            // A compact fast arm contains the literal result, and the
            // observed/materialized frame retains ordinary send semantics.
            for (var candidate : List.of(locals, aliases)) {
                assertContains(instructionNames(candidate), "TryPrimitiveNumericSendOne");
                assertTrue(loweredIntegerConstantCount(candidate, 3L) >= 1,
                        "a guarded arithmetic candidate must retain its folded operand");

                Object[] observedCall = ProtosFrameArguments.compactDirectClosureCall(
                        candidate, module, null, new Object[0]);
                ProtosActivation observed = ProtosFrameArguments.activation(observedCall);
                observed.context();
                assertEquals("3", integerValue(target(candidate).call(observedCall)));
            }
        });
    }

    @Test
    void i101GuardedLongArithmeticFoldsRecursivelyAndFailsClosed()
            throws Exception {
        withCore(module -> {
            String[][] accepted = {
                    {"() => { 1 + 1 + 1 }", "3"},
                    {"() => { (1 + 1) + 1 }", "3"},
                    {"() => { 1 + (1 + 1) }", "3"},
                    {"() => { (1 + 2) * 3 }", "9"},
                    {"() => { 10 - 3 - 2 }", "5"},
                    {"() => { 6 / 3 }", "2"},
                    {"() => { 1 + 2 * 3 }", "7"},
                    {"() => { x: 1; y: x + 2; z: y * 3; z }", "9"}
            };

            for (String[] example : accepted) {
                ProtosClosureValue candidate = closure(example[0], module);
                assertFalse(
                        hasFoldedIntegerArithmetic(candidate),
                        "Proven arithmetic must not retain a runtime descriptor");
                assertNone(
                        instructionNames(candidate),
                        "TryPrimitiveNumericSendOne");
                assertEquals(
                        example[1],
                        integerValue(enter(fastDirect(candidate, module))),
                        example[0]);
            }

            for (String example : List.of(
                    "() => { 1 + 2.0 }",
                    "() => { 1 / 2 }",
                    "() => { 9223372036854775807 + 1 }",
                    "() => { 9223372036854775807 * 2 }",
                    "() => { 1 / 0 }")) {
                ProtosClosureValue candidate = closure(example, module);
                assertFalse(hasFoldedIntegerArithmetic(candidate),
                        "Non-exact or non-long arithmetic must fail closed: "
                                + example);
                assertContains(
                        instructionNames(candidate), "TryPrimitiveNumericSendOne");
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
                            .lookupGuarded(custom, "+", null);
            assertTrue(selected != null);
            assertTrue(selected.stability().isValid());

            // Mutation invalidates the original mutable-object selection.
            custom.assignLocalSlot("+", replacement);
            assertFalse(selected.stability().isValid());

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

                assertTrue(containsLoweredIntegerConstant(direct, 3L));
                assertTrue(containsLoweredIntegerConstant(locals, 3L));

                assertEquals(
                        "3",
                        integerValue(enter(fastDirect(direct, module))));
                assertEquals(
                        "3",
                        integerValue(enter(fastDirect(locals, module))));

                assertNone(instructionNames(direct), "TryPrimitiveNumericSendOne");
                assertContains(instructionNames(locals), "TryPrimitiveNumericSendOne");
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

            assertTrue(containsLoweredIntegerConstant(nonTerminal, 3L));
            assertContains(instructionNames(nonTerminal), "TryPrimitiveNumericSendOne");
            assertEquals(
                    "7",
                    integerValue(enter(fastDirect(nonTerminal, module))));

            ProtosClosureValue nested =
                    closure("() => { (1 + 2) + 3 }", module);

            assertTrue(containsLoweredIntegerConstant(nested, 6L));
            assertNone(instructionNames(nested), "TryPrimitiveNumericSendOne");
            assertEquals("6", integerValue(enter(fastDirect(nested, module))));

            ProtosClosureValue tree =
                    closure("() => { (1 + 2) + (3 + 4) }", module);

            assertEquals(
                    "10",
                    integerValue(enter(fastDirect(tree, module))));
            assertTrue(containsLoweredIntegerConstant(tree, 10L));
            assertNone(instructionNames(tree), "TryPrimitiveNumericSendOne");

            ProtosClosureValue nestedOverflow =
                    closure(
                            "() => { (9223372036854775806 + 1) + 1 }",
                            module);

            assertEquals(
                    "9223372036854775808",
                    integerValue(enter(fastDirect(nestedOverflow, module))));
            assertTrue(containsLoweredIntegerConstant(nestedOverflow, Long.MAX_VALUE));
            assertContains(instructionNames(nestedOverflow), "TryPrimitiveNumericSendOne");

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

            ProtosSemanticBytecodeRootNode root =
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
                .filter(value ->
                        value instanceof
                                ProtosSemanticBytecodeRootNode.TryPrimitiveNumericSendOne
                                        .FoldedIntegerArithmetic)
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
                assertNone(instructions, "InstallFrameLexicalAuthority");
                // Every creation, read and assignment owns its own compact
                // check; the resolved write pair survives only as the
                // authoritative fallback of each assignment.
                assertEquals(
                        body.creates() + body.reads() + body.assigns(),
                        countOf(instructions, "IsCompactLocalFrame"),
                        () -> body.characters() + ": " + instructions);
                assertEquals(
                        body.assigns(),
                        countOf(instructions, "ResolveRootFrameLocalWriteTarget"),
                        () -> body.characters() + ": " + instructions);
                assertEquals(
                        body.assigns(),
                        countOf(instructions, "AssignRootFrameLocal"),
                        () -> body.characters() + ": " + instructions);
                assertContains(instructions, "store.local");
                assertContains(instructions, "load.local");

                for (int iteration = 0; iteration < 3; iteration++) {
                    ProtosBytecodeRootNode.PreparedClosureCall prepared =
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

    @Test
    void perf036aObservedActivationUpdatesAuthoritativeBinding() throws Exception {
        withCore(module -> {
            for (ScalarAssignBody body : PERF036A_ASSIGN_BODIES) {
                ProtosClosureValue scalar = closure(body.characters(), module);
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
                    assertEquals(Long.toString(body.assignedValue()), integerValue(observed.readLocalSlot(body.assigned()).orElseThrow()), () -> body.characters()
                                    + ": the observed context holds the assigned value");
                    contexts.add(observed);
                }
                assertNotSame(
                        contexts.get(0), contexts.get(1),
                        "every invocation owns a fresh execution context");
            }
        });
    }

    @Test
    void perf036aFrozenOrClosedEntryContextRejectsInitialCreation() throws Exception {
        withCore(module -> {
            // Scope: this rejects the body's initial *creation*, not an
            // assignment to an existing binding. An admitted body has no
            // effect that could freeze or close its own context between its
            // creation and its assignment, so a FROZEN/CLOSED existing
            // destination is unreachable in an admitted root; it stays
            // covered only by the unchanged general assignment path.
            ProtosClosureValue scalar =
                    closure("() => { first: 7\nfirst = 9\nfirst }", module);
            for (boolean freeze : List.of(true, false)) {
                Object[] arguments =
                        ProtosFrameArguments.compactDirectClosureCall(
                                scalar, module, null, new Object[0]);
                ProtosObjectValue observed =
                        ProtosFrameArguments.activation(arguments).context();
                if (freeze) {
                    observed.freeze();
                } else {
                    observed.close();
                }
                assertThrows(
                        ProtosSignalException.class,
                        () -> target(scalar).call(arguments));
                assertTrue(observed.readLocalSlot("first").isEmpty());
            }
        });
    }

    @Test
    void perf036aFinalAssignmentKeepsStatementAndExpressionTags() throws Exception {
        withCore(module -> {
            ProtosClosureValue scalar =
                    closure("() => { first: 7\nfirst = 9\nfirst }", module);
            ProtosSemanticBytecodeRootNode root =
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
            assertNone(instructionNames(outer), "IsCompactLocalFrame");
            assertEquals("1", integerValue(enter(fastDirect(outer, module))));
            assertEquals("6", integerValue(evaluate("perf036aOuter", module)), "an outer binding is assigned through the lexical chain");

            ProtosClosureValue explicitTarget =
                    closure("() => { first: 7\ncontext.first = 9\nfirst }", module);
            assertNone(instructionNames(explicitTarget), "IsCompactLocalFrame");
            assertContains(instructionNames(explicitTarget), "AssignLocalSlot");
            assertEquals("9", integerValue(enter(fastDirect(explicitTarget, module))), "an explicit-target assignment keeps the general lowering");

            ProtosClosureValue beforeCreation =
                    closure("() => { first = 3\nfirst: 4\nfirst }", module);
            assertNone(instructionNames(beforeCreation), "IsCompactLocalFrame");
            assertThrows(
                    ProtosSignalException.class,
                    () -> enter(fastDirect(beforeCreation, module)),
                    "an ABSENT destination is SlotNotFound");

            ProtosClosureValue send =
                    closure("() => { first: 4\nfirst = first + 1\nfirst }", module);
            assertNone(instructionNames(send), "IsCompactLocalFrame");
            assertEquals("5", integerValue(enter(fastDirect(send, module))));

            ProtosClosureValue captured =
                    closure(
                            "() => { first: 1\nsetter: (v) => { first = v }\nsetter(8)\nfirst }",
                            module);
            assertNone(instructionNames(captured), "IsCompactLocalFrame");
            assertEquals("8", integerValue(enter(fastDirect(captured, module))), "a captured assignment writes the binding by reference");

            ProtosClosureValue recreated =
                    closure(
                            "() => { first: 1\ncontext.removeSlot(\"first\")\n"
                                    + "first: 6\nfirst = 9\nfirst }",
                            module);
            assertNone(instructionNames(recreated), "IsCompactLocalFrame");
            assertEquals("9", integerValue(enter(fastDirect(recreated, module))));

            ProtosClosureValue removed =
                    closure(
                            "() => { first: 1\ncontext.removeSlot(\"first\")\nfirst = 3 }",
                            module);
            assertNone(instructionNames(removed), "IsCompactLocalFrame");
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
            assertContains(names, "CreateCurrentFrameLocal");
            assertContains(names, "ResolveRootFrameLocalWriteTarget");
            assertContains(names, "AssignRootFrameLocal");
            ProtosIntegerValue a = integer(4);
            ProtosBytecodeRootNode.PreparedClosureCall prepared = fastDirect(locals, module, a);
            assertSame(a, enter(prepared));
            assertSame(locals, prepared.targetArguments()[0], "no activation was materialized");

            ProtosClosureValue single = closure("(a) => { b: a\nb }", module);
            assertNotEquals(
                    constantOrdinalOf(single, "BindClosureFrameParameter"),
                    constantOrdinalOf(single, "CreateCurrentFrameLocal"),
                    "PERF030: the creation index is its own instruction constant");
            assertSame(a, enter(fastDirect(single, module, a)));

            ProtosBytecodeRootNode.PreparedClosureCall duplicate =
                    fastDirect(closure("(a) => { b: a\nb: a }", module), module, a);
            assertThrows(ProtosSignalException.class, () -> enter(duplicate));
            assertInstanceOf(ProtosActivation.class, duplicate.targetArguments()[0]);
        });
        System.out.println("ORDINARY_LOCAL_ONLY_ROOT_EAGER_FRAME_MATERIALIZATION=NO");
    }

    @Test
    void persistentAuthorityCreationUsesConstantIndexedOrdinal() throws Exception {
        withCore(module -> {
            ProtosClosureValue run =
                    closure("() => {\nidentity: (value) => { value }\nidentity(1)\n}", module);
            List<String> names = instructionNames(run);
            assertContains(names, "InstallFrameLexicalAuthority");
            assertContains(names, "CreateCurrentIndexedLocalSlot");
            assertNone(names, "CreateCurrentLocalSlot");
            assertNone(names, "CreateCurrentFrameLocal");
            ProtosFrameLexicalLayout layout =
                    assertInstanceOf(
                            ProtosFrameLexicalLayout.class,
                            constantArgumentOf(run, "InstallFrameLexicalAuthority", "frameBackedLayout"));
            assertSame(
                    layout,
                    constantArgumentOf(run, "CreateCurrentIndexedLocalSlot", "frameBackedLayout"),
                    "the indexed creation carries the installed persistent layout");
            assertEquals(
                    layout.offsetOf("identity"),
                    constantOrdinalOf(run, "CreateCurrentIndexedLocalSlot"));
            assertEquals("1", integerValue(enter(fastDirect(run, module))));

            ProtosBytecodeRootNode.PreparedClosureCall duplicate =
                    fastDirect(
                            closure("() => {\nf: (value) => { value }\nf: 2\n}", module),
                            module);
            assertThrows(ProtosSignalException.class, () -> enter(duplicate));

            ProtosFrameLexicalLayout foreign =
                    ProtosFrameLexicalLayout.of(
                            new String[] {"perf030Fallback"}, new int[] {0});
            ProtosIntegerValue value = integer(3);
            assertSame(
                    value,
                    ProtosBytecodeRootNode.createIndexedCurrentLocalSlot(
                            foreign, module, 0, "perf030Fallback", value));
            assertSame(value, evaluate("perf030Fallback", module), "named fallback created the slot");
            assertThrows(
                    ProtosSignalException.class,
                    () -> ProtosBytecodeRootNode.createIndexedCurrentLocalSlot(
                            foreign, module, 0, "perf030Fallback", value),
                    "the named fallback keeps the duplicate-creation Error");
        });
        System.out.println("PERSISTENT_AUTHORITY_INDEXED_CREATION=PASS");
    }

    @Test
    void frameNativeRestAndMultipleCreationUseConstantOrdinals() throws Exception {
        withCore(module -> {
            ProtosClosureValue rest = closure("(head, ...tail) => { tail }", module);
            assertNone(instructionNames(rest), "InstallFrameLexicalAuthority");
            ProtosFrameLexicalLayout restLayout = layoutOf(rest, "BindClosureFrameRest");
            assertEquals(
                    List.of(restLayout.offsetOf("tail")),
                    constantOrdinalsOf(rest, "BindClosureFrameRest"),
                    "PERF030-I: the rest index is an instruction constant");

            ProtosClosureValue multiple =
                    closure("(source) => { (b, c, d): source\n[b, c, d] }", module);
            List<String> names = instructionNames(multiple);
            assertNone(names, "InstallFrameLexicalAuthority");
            assertNone(names, "MultipleCreateLocalSlots");
            ProtosFrameLexicalLayout layout = layoutOf(multiple, "CreateCurrentFrameLocal");
            assertEquals(
                    List.of(layout.offsetOf("b"), layout.offsetOf("c"), layout.offsetOf("d")),
                    constantOrdinalsOf(multiple, "CreateCurrentFrameLocal"),
                    "PERF030-I: one scalar creation per name, in source order, "
                            + "each owning its constant ordinal");
            int observation = firstIndexOf(names, "ObserveMultipleCreatePrefix");
            assertTrue(
                    observation >= 0 && observation < firstIndexOf(names, "CreateCurrentFrameLocal"),
                    () -> "the complete prefix is observed before the first creation: " + names);
            int lastCreation = names.size() - 1
                    - firstIndexOf(names.reversed(), "CreateCurrentFrameLocal");
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
            ProtosClosureValue earlier = closure("(a, b = a) => { b }", module);
            assertNone(instructionNames(earlier), "CurrentActivation");
            ProtosIntegerValue a = integer(5);
            ProtosBytecodeRootNode.PreparedClosureCall prepared = fastDirect(earlier, module, a);
            assertSame(a, enter(prepared));
            assertSame(earlier, prepared.targetArguments()[0]);

            assertEquals("7", integerValue(evaluate("b: 7\nf: (a = b, b = 1) => { a }\nf()\n", module)), "a default never reads a later parameter's future value");
        });
        System.out.println("DEFAULT_BINDING_VISIBILITY=PASS");
    }

    @Test
    void contextObservedByDefaultShowsExactlyEarlierBindings() throws Exception {
        withCore(module -> {
            Object observed =
                    evaluate(
                            "f: (a, b = [context.hasSlot(\"a\"), context.hasSlot(\"b\")]) => { b }\n"
                                    + "f(1)\n",
                            module);
            List<Object> presence = assertInstanceOf(ProtosArrayValue.class, observed).indexedSnapshot();
            assertSame(ProtosBooleanValue.TRUE, presence.get(0));
            assertSame(ProtosBooleanValue.FALSE, presence.get(1));

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
            ProtosBytecodeRootNode.PreparedClosureCall conflict =
                    fastDirect(recreate, module, integer(1));
            assertThrows(
                    ProtosSignalException.class,
                    () -> enter(conflict),
                    "creating an already PRESENT parameter name is a slot-creation conflict");

            ProtosClosureValue identity = closure("(value) => { value }", module);
            ProtosBytecodeRootNode.PreparedClosureCall nullCall =
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
            assertSame(ProtosBooleanValue.FALSE, removed.get(0));
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
                            "Error.handle(() => {\n f: (value) => { value }\n f()\n}, (caught) => 42)\n",
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
            AtomicReference<ProtosBytecodeRootNode.PreparedClosureCall> preparedRef =
                    new AtomicReference<>();
            ProtosTask task =
                    module.executionDomain()
                            .createTask(
                                    null,
                                    current -> {
                                        ProtosBytecodeRootNode.PreparedClosureCall prepared =
                                                ProtosBytecodeRootNode
                                                        .prepareTaskOwnedDirectClosureIfBytecode(
                                                                identity,
                                                                List.of(integer(8)),
                                                                module,
                                                                current);
                                        preparedRef.set(prepared);
                                        ProtosBytecodeTaskExecution.executePreparedClosure(
                                                current, prepared);
                                    });
            assertTrue(module.executionDomain().dispatchOne());
            assertEquals(ProtosTask.State.COMPLETED, task.state());
            assertEquals("8", integerValue(task.result().orElseThrow()));

            Object[] arguments = preparedRef.get().targetArguments();
            assertSame(identity, arguments[0], "the Task-owned callee stayed compact");
            assertSame(task, preparedRef.get().taskForRuntime());
            assertSame(task, ProtosFrameArguments.activation(arguments).task().orElseThrow());
            assertSame(task, preparedRef.get().taskForRuntime(), "same Task after materialization");
            assertTrue(module.task().isEmpty());
        });
        System.out.println("TASK_DYNAMIC_CONTROL=PASS");
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

    private static void withCoreDebug(CoreTest test) throws Exception {
        try (Context context = Context.newBuilder(ProtosLanguage.ID)
                .allowExperimentalOptions(true)
                .option("protos.Debug", "true")
                .build()) {
            context.initialize(ProtosLanguage.ID);
            context.enter();
            try {
                assertEquals(
                        ProtosExecutionMode.DEBUG,
                        ProtosLanguageContext.current().executionMode());
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

    private static List<String> instructionNames(ProtosClosureValue closure) {
        return closure.executionPlan()
                .orElseThrow()
                .bytecodeActivationRootForTesting()
                .getBytecodeNode()
                .getInstructionsAsList()
                .stream()
                .map(Instruction::getName)
                .toList();
    }

    /**
     * The {@code ordinal} of the single {@code operation} instruction, which
     * must be encoded as an immediate constant operand rather than a stack
     * value, so it is a partial-evaluation constant.
     */
    private static int constantOrdinalOf(ProtosClosureValue closure, String operation) {
        List<Integer> ordinals = constantOrdinalsOf(closure, operation);
        assertEquals(1, ordinals.size(), () -> "expected one " + operation);
        return ordinals.get(0);
    }

    /** The immediate constant {@code ordinal} of every {@code operation} instruction, in order. */
    private static List<Integer> constantOrdinalsOf(ProtosClosureValue closure, String operation) {
        return instructionsOf(closure, operation).stream()
                .map(instruction -> {
                    Instruction.Argument ordinal =
                            instruction.getArguments().stream()
                                    .filter(argument -> argument.getName().equals("ordinal"))
                                    .findFirst()
                                    .orElseThrow(() -> new AssertionError("no constant ordinal operand"));
                    return switch (ordinal.getKind()) {
                        case CONSTANT -> (Integer) ordinal.asConstant();
                        case INTEGER -> ordinal.asInteger();
                        default -> throw new AssertionError("ordinal is not a constant: " + ordinal);
                    };
                })
                .toList();
    }

    /** The one {@code frameBackedLayout} constant shared by every {@code operation} instruction. */
    private static ProtosFrameLexicalLayout layoutOf(ProtosClosureValue closure, String operation) {
        List<Object> layouts =
                instructionsOf(closure, operation).stream()
                        .map(instruction -> instruction.getArguments().stream()
                                .filter(argument -> argument.getName().equals("frameBackedLayout"))
                                .findFirst()
                                .orElseThrow(() -> new AssertionError("no constant layout operand"))
                                .asConstant())
                        .distinct()
                        .toList();
        assertEquals(1, layouts.size(), () -> "expected one layout for " + operation);
        return assertInstanceOf(ProtosFrameLexicalLayout.class, layouts.get(0));
    }

    private static List<Instruction> instructionsOf(ProtosClosureValue closure, String operation) {
        return closure.executionPlan()
                .orElseThrow()
                .bytecodeActivationRootForTesting()
                .getBytecodeNode()
                .getInstructionsAsList()
                .stream()
                .filter(instruction -> instruction.getName().contains(operation))
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

    /** The object constant operand {@code argument} of the single {@code operation} instruction. */
    private static Object constantArgumentOf(
            ProtosClosureValue closure, String operation, String argument) {
        List<Instruction> matching =
                closure.executionPlan()
                        .orElseThrow()
                        .bytecodeActivationRootForTesting()
                        .getBytecodeNode()
                        .getInstructionsAsList()
                        .stream()
                        .filter(instruction -> instruction.getName().contains(operation))
                        .toList();
        assertEquals(1, matching.size(), () -> "expected one " + operation);
        return matching.get(0).getArguments().stream()
                .filter(candidate -> candidate.getName().equals(argument))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no constant " + argument + " operand"))
                .asConstant();
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

    private static void assertNone(List<String> names, String operation) {
        assertTrue(
                names.stream().noneMatch(name -> name.contains(operation)),
                () -> "unexpected " + operation + " in: " + names);
    }

    private static ProtosObjectValue newObject() {
        return new ProtosObjectValue(ProtosObjectValue.rootObject());
    }

    private static ProtosIntegerValue integer(long value) {
        return new ProtosIntegerValue(value);
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
