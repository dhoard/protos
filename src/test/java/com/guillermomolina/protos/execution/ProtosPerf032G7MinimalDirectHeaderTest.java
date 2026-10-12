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
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.guillermomolina.protos.runtime.ProtosActivation;
import com.guillermomolina.protos.runtime.ProtosArrayValue;
import com.guillermomolina.protos.runtime.ProtosClosureValue;
import com.guillermomolina.protos.runtime.ProtosIntegerValue;
import com.guillermomolina.protos.runtime.ProtosObjectValue;
import com.guillermomolina.protos.runtime.ProtosPrelude;
import com.guillermomolina.protos.runtime.ProtosReturnHome;
import com.guillermomolina.protos.runtime.ProtosTask;
import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.IndirectCallNode;
import com.oracle.truffle.api.source.Source;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;

/**
 * PERF032-G7 minimal direct Closure call headers.
 *
 * <p>A direct source Closure call with zero supplied arguments carries only
 * the slots it needs: {@code [closure, caller]} (A), {@code [closure, caller,
 * returnHome]} (B), {@code [closure, task, caller]} (C) or {@code [closure,
 * task, caller, returnHome]} (D). Calls with supplied arguments and immediate
 * method calls retain their required metadata; unobservable zero-argument
 * immediate methods use a four-slot header.
 */
final class ProtosPerf032G7MinimalDirectHeaderTest {
    private static final Path CORE = Path.of("protos", "lib", "core");

    @Test
    void variantAUsesTwoSlotsWithTheUnobservableMarker() throws Exception {
        withCore(module -> {
            ProtosClosureValue literal = parsedClosure("() => { 1 }", "g7-a.protos", module);
            ProtosBytecodeRootNode.PreparedClosureCall prepared = fastDirect(literal, module);
            Object[] arguments = prepared.targetArguments();

            assertEquals(2, arguments.length);
            assertSame(literal, arguments[0]);
            assertSame(module, arguments[1]);
            assertSame(ProtosReturnHome.unobservable(),
                    ProtosFrameArguments.compactReturnHome(arguments));
            assertTrue(ProtosFrameArguments.compactOwnsReturnHome(arguments));
            assertSame(module, ProtosFrameArguments.compactCaller(arguments));
            assertNull(ProtosFrameArguments.compactTask(arguments));
            assertEquals(0, ProtosFrameArguments.compactSuppliedArgumentCount(arguments));
            assertSame(module, ProtosFrameArguments.compactInheritedProvenanceCaller(arguments));
            assertThrows(IllegalStateException.class, prepared::activation);

            assertEquals("1", integerValue(enter(prepared)));
            assertSame(literal, arguments[0], "execution creates no eager activation");
            assertTrue(ProtosFrameArguments.isUnmaterializedCompactCall(arguments));
            assertTrue(ProtosFrameArguments.isUnmaterializedCompactScalarLocalCall(arguments));

            ProtosActivation callee = ProtosFrameArguments.activation(arguments);
            assertSame(callee, arguments[0], "the activation is published into argument 0");
            assertSame(callee, ProtosFrameArguments.activation(arguments));
            assertEquals(2, arguments.length);
            assertSame(ProtosReturnHome.unobservable(), callee.returnHome().orElseThrow());
            assertTrue(callee.task().isEmpty());
            assertEquals(0, callee.suppliedArgumentCountForRuntime());
            assertFalse(ProtosFrameArguments.isUnmaterializedCompactCall(arguments));
            assertFalse(ProtosFrameArguments.isUnmaterializedCompactScalarLocalCall(arguments));
            assertTrue(ProtosFrameArguments.hasActivation(arguments));
            assertEquals(0, ProtosFrameArguments.compactSuppliedArgumentCount(arguments));
            assertSame(module, ProtosFrameArguments.compactCaller(arguments));
        });
        System.out.println("PERF032_G7_VARIANT_A=PASS");
    }

    @Test
    void variantBKeepsTheExactPhysicalHome() throws Exception {
        withCore(module -> {
            ProtosClosureValue returning =
                    parsedClosure("() => { ^ 42 }", "g7-b.protos", module);
            ProtosBytecodeRootNode.PreparedClosureCall prepared = fastDirect(returning, module);
            Object[] arguments = prepared.targetArguments();

            assertEquals(3, arguments.length);
            assertSame(returning, arguments[0]);
            assertSame(module, arguments[1]);
            ProtosReturnHome home = assertInstanceOf(ProtosReturnHome.class, arguments[2]);
            assertNotSame(ProtosReturnHome.unobservable(), home);
            assertSame(home, ProtosFrameArguments.compactReturnHome(arguments));
            assertSame(home, ProtosFrameArguments.compactReturnHome(arguments),
                    "reconstruction never recomputes a home");
            assertTrue(home.isActive());

            assertEquals("42", integerValue(enter(prepared)));
            assertFalse(home.isActive());
            ProtosActivation callee = ProtosFrameArguments.activation(arguments);
            assertSame(home, callee.returnHome().orElseThrow());
            assertSame(callee, ProtosFrameArguments.activation(arguments));
        });
        System.out.println("PERF032_G7_VARIANT_B=PASS");
    }

    @Test
    void variantCKeepsTheExactTaskWithTheUnobservableMarker() throws Exception {
        withCore(module -> {
            ProtosClosureValue literal = parsedClosure("() => { 1 }", "g7-c.protos", module);
            AtomicReference<Object[]> argumentsRef = new AtomicReference<>();
            ProtosTask task =
                    module.executionDomain().createTask(null, current -> {
                        ProtosBytecodeRootNode.PreparedClosureCall prepared =
                                ProtosBytecodeRootNode.prepareTaskOwnedDirectClosureIfBytecode(
                                        literal, List.of(), module, current);
                        Object[] arguments = prepared.targetArguments();
                        argumentsRef.set(arguments);
                        assertEquals(3, arguments.length);
                        assertSame(literal, arguments[0]);
                        assertSame(current, arguments[1]);
                        assertSame(module, arguments[2]);
                        assertSame(current, ProtosFrameArguments.compactTask(arguments));
                        assertSame(module, ProtosFrameArguments.compactCaller(arguments));
                        assertSame(ProtosReturnHome.unobservable(),
                                ProtosFrameArguments.compactReturnHome(arguments));
                        assertNull(
                                ProtosFrameArguments.compactInheritedProvenanceCaller(arguments),
                                "an explicit Task is never a no-Task invocation");
                        ProtosBytecodeTaskExecution.executePreparedClosure(current, prepared);
                    });
            assertTrue(module.executionDomain().dispatchOne());

            assertEquals(ProtosTask.State.COMPLETED, task.state());
            assertEquals("1", integerValue(task.result().orElseThrow()));
            Object[] arguments = argumentsRef.get();
            ProtosActivation callee = ProtosFrameArguments.activation(arguments);
            assertSame(task, callee.task().orElseThrow());
            assertSame(task, ProtosFrameArguments.compactTask(arguments));
            assertSame(ProtosReturnHome.unobservable(), callee.returnHome().orElseThrow());
            assertTrue(module.task().isEmpty(), "the creator is never mutated");
        });
        System.out.println("PERF032_G7_VARIANT_C=PASS");
    }

    @Test
    void variantDKeepsTheExactTaskAndPhysicalHome() throws Exception {
        withCore(module -> {
            ProtosClosureValue returning =
                    parsedClosure("() => { ^ 42 }", "g7-d.protos", module);
            AtomicReference<Object[]> argumentsRef = new AtomicReference<>();
            AtomicReference<ProtosReturnHome> homeRef = new AtomicReference<>();
            ProtosTask task =
                    module.executionDomain().createTask(null, current -> {
                        ProtosBytecodeRootNode.PreparedClosureCall prepared =
                                ProtosBytecodeRootNode.prepareTaskOwnedDirectClosureIfBytecode(
                                        returning, List.of(), module, current);
                        Object[] arguments = prepared.targetArguments();
                        argumentsRef.set(arguments);
                        assertEquals(4, arguments.length);
                        assertSame(returning, arguments[0]);
                        assertSame(current, arguments[1]);
                        assertSame(module, arguments[2]);
                        ProtosReturnHome home =
                                assertInstanceOf(ProtosReturnHome.class, arguments[3]);
                        assertNotSame(ProtosReturnHome.unobservable(), home);
                        assertSame(home, ProtosFrameArguments.compactReturnHome(arguments));
                        assertTrue(home.isActive());
                        homeRef.set(home);
                        ProtosBytecodeTaskExecution.executePreparedClosure(current, prepared);
                    });
            assertTrue(module.executionDomain().dispatchOne());

            assertEquals(ProtosTask.State.COMPLETED, task.state());
            assertEquals("42", integerValue(task.result().orElseThrow()));
            ProtosActivation callee = ProtosFrameArguments.activation(argumentsRef.get());
            assertSame(task, callee.task().orElseThrow());
            assertSame(homeRef.get(), callee.returnHome().orElseThrow());
            assertFalse(homeRef.get().isActive());
        });
        System.out.println("PERF032_G7_VARIANT_D=PASS");
    }

    @Test
    void variantAScopeObservationMaterializesOneActivation() throws Exception {
        withCore(module -> {
            ProtosClosureValue literal = parsedClosure("() => { 1 }", "g7-scope-a.protos", module);
            Object[] arguments =
                    ProtosFrameArguments.compactDirectClosureCall(
                            literal, module, null, new Object[0]);
            assertEquals(2, arguments.length);

            ProtosActivation observed = observeScopeTwice(literal, arguments);
            assertSame(module, ProtosFrameArguments.compactCaller(arguments));
            assertTrue(observed.task().isEmpty());
            assertSame(ProtosReturnHome.unobservable(), observed.returnHome().orElseThrow());
        });
        System.out.println("PERF032_G7_SCOPE_A=PASS");
    }

    @Test
    void variantDScopeObservationMaterializesOneActivation() throws Exception {
        withCore(module -> {
            ProtosClosureValue returning =
                    parsedClosure("() => { ^ 42 }", "g7-scope-d.protos", module);
            AtomicReference<Object[]> argumentsRef = new AtomicReference<>();
            ProtosTask task =
                    module.executionDomain().createTask(null, current -> {
                        Object[] arguments =
                                ProtosFrameArguments.compactDirectClosureCall(
                                        returning, module, current, new Object[0]);
                        assertEquals(4, arguments.length);
                        argumentsRef.set(arguments);
                    });
            assertTrue(module.executionDomain().dispatchOne());

            Object[] arguments = argumentsRef.get();
            ProtosReturnHome home = assertInstanceOf(ProtosReturnHome.class, arguments[3]);
            ProtosActivation observed = observeScopeTwice(returning, arguments);
            assertSame(module, ProtosFrameArguments.compactCaller(arguments));
            assertSame(task, observed.task().orElseThrow());
            assertSame(task, ProtosFrameArguments.compactTask(arguments));
            assertSame(home, observed.returnHome().orElseThrow());
        });
        System.out.println("PERF032_G7_SCOPE_D=PASS");
    }

    /**
     * Observes the NodeLibrary scope of a minimal-header frame twice and
     * returns the one activation both observations published.
     */
    private static ProtosActivation observeScopeTwice(
            ProtosClosureValue closure, Object[] arguments) throws Exception {
        int length = arguments.length;
        VirtualFrame frame =
                Truffle.getRuntime()
                        .createVirtualFrame(arguments, FrameDescriptor.newBuilder().build());
        assertTrue(ProtosBytecodeTagTreeNodeExports.hasScope(null, frame));
        assertSame(closure, arguments[0], "hasScope does not materialize");

        ProtosBytecodeTagTreeNodeExports.getScope(null, frame, true);
        ProtosActivation observed = assertInstanceOf(ProtosActivation.class, arguments[0]);
        ProtosBytecodeTagTreeNodeExports.getScope(null, frame, true);
        assertSame(observed, arguments[0], "a second observation reuses the activation");
        assertSame(observed, ProtosFrameArguments.activation(frame));
        assertEquals(length, arguments.length);
        assertEquals(0, observed.suppliedArgumentCountForRuntime());
        assertTrue(observed.suppliedArgumentsForRuntime().isEmpty());
        assertEquals(0, ProtosFrameArguments.compactSuppliedArgumentCount(arguments));
        return observed;
    }

    @Test
    void capturedHomeOwnershipIsPreserved() throws Exception {
        withCore(module -> {
            ProtosClosureValue escaped =
                    assertInstanceOf(ProtosClosureValue.class,
                            evaluate("make: () => { () => { ^ 1 } }\nmake()\n",
                                    "g7-captured.protos", module));
            ProtosReturnHome captured = escaped.returnHome().orElseThrow();
            Object[] arguments =
                    ProtosFrameArguments.compactDirectClosureCall(
                            escaped, module, null, new Object[0]);
            assertEquals(3, arguments.length);
            assertSame(captured, arguments[2]);
            assertSame(captured, ProtosFrameArguments.compactReturnHome(arguments));
            assertFalse(ProtosFrameArguments.compactOwnsReturnHome(arguments));
            assertSame(captured,
                    ProtosFrameArguments.activation(arguments).returnHome().orElseThrow());
        });
    }

    @Test
    void suppliedArgumentsAndImmediateMethodsKeepTheFullHeader() throws Exception {
        withCore(module -> {
            ProtosClosureValue identity =
                    parsedClosure("(value) => { value }", "g7-full.protos", module);
            ProtosIntegerValue seven = integer(7);
            ProtosBytecodeRootNode.PreparedClosureCall prepared =
                    fastDirect(identity, module, seven);
            Object[] direct = prepared.targetArguments();
            assertEquals(6, direct.length);
            assertSame(module, direct[3]);
            assertEquals(1, ProtosFrameArguments.compactSuppliedArgumentCount(direct));
            assertSame(seven, ProtosFrameArguments.compactSuppliedArgument(direct, 0));
            assertEquals("7", integerValue(enter(prepared)));

            ProtosClosureValue literal = parsedClosure("() => { 1 }", "g7-method.protos", module);
            ProtosObjectValue methodHome = new ProtosObjectValue(literal);
            Object[] method =
                    ProtosFrameArguments.compactImmediateMethodCall(
                            literal, literal, methodHome, module, new Object[0]);

            assertEquals(4, method.length);
            assertSame(literal, method[0]);
            assertSame(literal, method[1]);
            assertSame(methodHome, method[2]);
            assertSame(module, method[3]);
            assertSame(module, ProtosFrameArguments.compactCaller(method));
            assertSame(
                    ProtosReturnHome.unobservable(),
                    ProtosFrameArguments.compactReturnHome(method));
            assertEquals(0, ProtosFrameArguments.compactSuppliedArgumentCount(method));

            ProtosActivation published = ProtosFrameArguments.activation(method);
            assertSame(literal, published.receiver());
            assertSame(published, method[0]);
            assertSame(published, ProtosFrameArguments.activation(method));
            assertEquals(4, method.length);
            assertSame(module, ProtosFrameArguments.compactCaller(method));
            assertSame(
                    ProtosReturnHome.unobservable(),
                    ProtosFrameArguments.compactReturnHome(method));
            assertFalse(
                    ProtosFrameArguments.isUnmaterializedInheritingFullHeader(method));
        });
    }

    @Test
    void perf038HDirectCallZeroOnePreparedEntries() throws Exception {
        withCore(module -> {
            ProtosClosureValue literal =
                    parsedClosure("() => { 1 }", "perf038h-call01.protos", module);
            RootCallTarget target = fastDirect(literal, module).bodyTarget();

            ProtosBytecodeRootNode.PreparedClosureCall zero =
                    ProtosBytecodeRootNode.finishDirectClosureCallZero(
                            literal, target, module);

            assertSame(target, zero.bodyTarget());
            assertEquals(2, zero.targetArguments().length);
            assertSame(literal, zero.targetArguments()[0]);
            assertSame(module, zero.targetArguments()[1]);
            assertSame(
                    ProtosReturnHome.unobservable(),
                    ProtosFrameArguments.compactReturnHome(
                            zero.targetArguments()));

            ProtosIntegerValue value = integer(9);
            ProtosBytecodeRootNode.PreparedClosureCall one =
                    ProtosBytecodeRootNode.finishDirectClosureCallOne(
                            literal, target, value, module);

            RootCallTarget rejection =
                    assertInstanceOf(
                            ProtosSemanticBytecodeRootNode.class,
                            target.getRootNode())
                            .arityRejectionTarget();

            assertSame(rejection, one.bodyTarget());
            assertEquals(6, one.targetArguments().length);
            assertSame(literal, one.targetArguments()[0]);
            assertSame(module, one.targetArguments()[3]);
            assertSame(value, one.targetArguments()[5]);
            assertEquals(
                    1,
                    ProtosFrameArguments.compactSuppliedArgumentCount(
                            one.targetArguments()));

            ProtosClosureValue returning =
                    parsedClosure(
                            "() => { ^ 42 }",
                            "perf038h-call0-home.protos",
                            module);
            RootCallTarget returningTarget =
                    fastDirect(returning, module).bodyTarget();
            ProtosBytecodeRootNode.PreparedClosureCall owned =
                    ProtosBytecodeRootNode.finishDirectClosureCallZero(
                            returning, returningTarget, module);

            assertEquals(3, owned.targetArguments().length);
            ProtosReturnHome physicalHome =
                    assertInstanceOf(
                            ProtosReturnHome.class,
                            owned.targetArguments()[2]);
            assertNotSame(ProtosReturnHome.unobservable(), physicalHome);
            assertTrue(
                    assertInstanceOf(
                            ProtosBytecodeRootNode.OrdinarySourceCall.class,
                            owned).hasReturnHomeLifecycle());
        });
    }

    @Test
    void zeroParameterExcessArgumentsSelectTheRejectionRoot() throws Exception {
        withCore(module -> {
            ProtosClosureValue literal = parsedClosure("() => { 1 }", "g7-arity.protos", module);
            RootCallTarget normal = fastDirect(literal, module).bodyTarget();
            RootCallTarget rejection =
                    assertInstanceOf(ProtosSemanticBytecodeRootNode.class, normal.getRootNode())
                            .arityRejectionTarget();
            ProtosBytecodeRootNode.PreparedClosureCall excess =
                    fastDirect(literal, module, integer(1));
            assertEquals(6, excess.targetArguments().length);
            assertSame(rejection, excess.bodyTarget());
        });
    }

    @Test
    void richActivationArraysAreNotCompact() throws Exception {
        withCore(module -> {
            Object[] rich = {module};
            assertFalse(ProtosFrameArguments.isUnmaterializedCompactCall(rich));
            assertTrue(ProtosFrameArguments.hasActivation(rich));
            assertSame(module, ProtosFrameArguments.activation(rich));
            assertThrows(IllegalStateException.class,
                    () -> ProtosFrameArguments.compactReturnHome(rich));
        });
    }

    @Test
    void perf038HLiteralOnlyCallHasProvenNonSuspendingEntry()
            throws Exception {
        withCore(module -> {
            ProtosClosureValue constant =
                    parsedClosure(
                            "() => { 1 }",
                            "perf038h-literal-proof.protos",
                            module);

            ProtosBytecodeRootNode.OrdinarySourceCall prepared =
                    assertInstanceOf(
                            ProtosBytecodeRootNode.OrdinarySourceCall.class,
                            fastDirect(constant, module));

            assertTrue(prepared.provablyNonSuspending());
            assertFalse(
                    ProtosSemanticBytecodeRootNode.IsContinuationForCall
                            .provenNonSuspending(prepared, integer(1)));

            assertEquals("1", integerValue(enter(prepared)));
        });
    }

    @Test
    void perf038HDynamicCallsKeepGeneralAndSimpleParametersUseProof()
            throws Exception {
        withCore(module -> {
            ProtosClosureValue withSend =
                    parsedClosure(
                            "() => { 1 + 2 }",
                            "perf038h-dynamic-body.protos",
                            module);

            ProtosBytecodeRootNode.OrdinarySourceCall dynamic =
                    assertInstanceOf(
                            ProtosBytecodeRootNode.OrdinarySourceCall.class,
                            fastDirect(withSend, module));

            assertFalse(dynamic.provablyNonSuspending());
            assertEquals("3", integerValue(enter(dynamic)));

            ProtosClosureValue withParameter =
                    parsedClosure(
                            "(x) => { 1 }",
                            "perf038h-parameter-body.protos",
                            module);

            ProtosBytecodeRootNode.OrdinarySourceCall parameterCall =
                    assertInstanceOf(
                            ProtosBytecodeRootNode.OrdinarySourceCall.class,
                            fastDirect(withParameter, module, integer(9)));

            assertTrue(parameterCall.provablyNonSuspending());
            assertEquals("1", integerValue(enter(parameterCall)));

            ProtosClosureValue identity =
                    parsedClosure(
                            "(x) => { x }",
                            "perf038h-parameter-read.protos",
                            module);

            ProtosBytecodeRootNode.OrdinarySourceCall identityCall =
                    assertInstanceOf(
                            ProtosBytecodeRootNode.OrdinarySourceCall.class,
                            fastDirect(identity, module, integer(7)));

            assertTrue(identityCall.provablyNonSuspending());
            assertEquals("7", integerValue(enter(identityCall)));

            ProtosClosureValue twoParameters =
                    parsedClosure(
                            "(x, y) => { x }",
                            "perf038h-two-parameters.protos",
                            module);

            ProtosBytecodeRootNode.OrdinarySourceCall twoArgumentCall =
                    assertInstanceOf(
                            ProtosBytecodeRootNode.OrdinarySourceCall.class,
                            fastDirect(
                                    twoParameters, module,
                                    integer(8), integer(9)));

            assertTrue(twoArgumentCall.provablyNonSuspending());
            assertEquals("8", integerValue(enter(twoArgumentCall)));

            ProtosClosureValue dynamicParameter =
                    parsedClosure(
                            "(x) => { x + 1 }",
                            "perf038h-dynamic-parameter.protos",
                            module);

            ProtosBytecodeRootNode.OrdinarySourceCall dynamicParameterCall =
                    assertInstanceOf(
                            ProtosBytecodeRootNode.OrdinarySourceCall.class,
                            fastDirect(dynamicParameter, module, integer(2)));

            assertFalse(dynamicParameterCall.provablyNonSuspending());
            assertEquals("3", integerValue(enter(dynamicParameterCall)));
        });
    }

    @Test
    void perf038HStraightLineInvocationPreservesDirectResults()
            throws Exception {
        withCore(module -> {
            ProtosClosureValue literal =
                    parsedClosure(
                            "() => { 1 }",
                            "perf038h-straight-line-zero.protos",
                            module);

            ProtosBytecodeRootNode.OrdinarySourceCall zero =
                    assertInstanceOf(
                            ProtosBytecodeRootNode.OrdinarySourceCall.class,
                            fastDirect(literal, module));

            assertTrue(
                    ProtosSemanticBytecodeRootNode
                            .AdmitsStraightLineSourceCall.perform(zero));
            assertEquals("1", integerValue(
                            ProtosSemanticBytecodeRootNode
                                    .EnterStraightLineSourceCall
                                    .ordinaryIndirect(
                                            zero, IndirectCallNode.create())));

            ProtosClosureValue identity =
                    parsedClosure(
                            "(x) => { x }",
                            "perf038h-straight-line-one.protos",
                            module);

            ProtosBytecodeRootNode.OrdinarySourceCall one =
                    assertInstanceOf(
                            ProtosBytecodeRootNode.OrdinarySourceCall.class,
                            fastDirect(identity, module, integer(7)));

            assertTrue(
                    ProtosSemanticBytecodeRootNode
                            .AdmitsStraightLineSourceCall.perform(one));
            assertEquals("7", integerValue(
                            ProtosSemanticBytecodeRootNode
                                    .EnterStraightLineSourceCall
                                    .ordinaryIndirect(
                                            one, IndirectCallNode.create())));
        });
    }

    @Test
    void perf038HStraightLineRejectsDynamicReturnAndArityEntries()
            throws Exception {
        withCore(module -> {
            ProtosClosureValue dynamic =
                    parsedClosure(
                            "() => { 1 + 2 }",
                            "perf038h-straight-line-dynamic.protos",
                            module);

            assertFalse(
                    ProtosSemanticBytecodeRootNode
                            .AdmitsStraightLineSourceCall.perform(
                                    assertInstanceOf(
        ProtosBytecodeRootNode.OrdinarySourceCall.class,
        fastDirect(dynamic, module))));

            ProtosClosureValue returning =
                    parsedClosure(
                            "() => { ^ 42 }",
                            "perf038h-straight-line-return.protos",
                            module);

            assertFalse(
                    ProtosSemanticBytecodeRootNode
                            .AdmitsStraightLineSourceCall.perform(
                                    assertInstanceOf(
        ProtosBytecodeRootNode.OrdinarySourceCall.class,
        fastDirect(returning, module))));

            ProtosClosureValue literal =
                    parsedClosure(
                            "() => { 1 }",
                            "perf038h-straight-line-arity.protos",
                            module);

            assertFalse(
                    ProtosSemanticBytecodeRootNode
                            .AdmitsStraightLineSourceCall.perform(
                                    assertInstanceOf(
        ProtosBytecodeRootNode.OrdinarySourceCall.class,
        fastDirect(
                                            literal, module, integer(3)))));
        });
    }

    @Test
    void perf038HFusedSendOnePreservesRebindingAndFallback()
            throws Exception {
        withCore(module -> {
            Object result = evaluate(
                    "receiver: { identity: (value) => { value } }\n"
                            + "run: (value) => { receiver.identity(value) }\n"
                            + "first: run(1)\n"
                            + "second: run(2)\n"
                            + "receiver.identity = (value) => { value + 10 }\n"
                            + "third: run(3)\n"
                            + "first * 100 + second * 10 + third\n",
                    "perf038h-fused-send-one.protos",
                    module);

            assertEquals("133", integerValue(result));
        });
    }

    @Test
    void perf038HFusedCallZeroAndOnePreserveResults()
            throws Exception {
        withCore(module -> {
            Object result = evaluate(
                    "constant: () => { 1 }\n"
                            + "identity: (x) => { x }\n"
                            + "constant() * 100"
                            + " + identity(2) * 10"
                            + " + identity(3)\n",
                    "perf038h-fused-call-arity.protos",
                    module);

            assertEquals("123", integerValue(result));
        });
    }

    @Test
    void perf038HFusedCallsRespectArityAndDynamicBodies()
            throws Exception {
        withCore(module -> {
            Object result = evaluate(
                    "zero: () => { 4 }\n"
                            + "dynamic: (x) => { x + 1 }\n"
                            + "zero() * 10 + dynamic(2)\n",
                    "perf038h-fused-call-fallback.protos",
                    module);

            assertEquals("43", integerValue(result));
        });
    }

    @Test
    void perf038HFusedSendZeroPreservesSelectionAndRebinding()
            throws Exception {
        withCore(module -> {
            Object result = evaluate(
                    "receiver: { value: () => { 7 } }\n"
                            + "run: () => { receiver.value() }\n"
                            + "first: run()\n"
                            + "second: run()\n"
                            + "receiver.value = () => { 2 + 3 }\n"
                            + "third: run()\n"
                            + "receiver.value = () => { 9 }\n"
                            + "fourth: run()\n"
                            + "[first, second, third, fourth]\n",
                    "perf038h-fused-send-zero.protos",
                    module);

            java.util.List<Object> values =
                    assertInstanceOf(
                            ProtosArrayValue.class, result).indexedSnapshot();

            assertEquals(4, values.size());
            assertEquals("7", integerValue(values.get(0)));
            assertEquals("7", integerValue(values.get(1)));
            assertEquals("5", integerValue(values.get(2)));
            assertEquals("9", integerValue(values.get(3)));
        });
    }

    @Test
    void perf038HSendAdmissionIsAritySpecific() throws Exception {
        withCore(module -> {
            Object receiver = evaluate(
                    "receiver: {\n"
                            + "    zero: () => { 7 }\n"
                            + "    one: (x) => { x }\n"
                            + "}\n"
                            + "receiver\n",
                    "perf038h-send-admission.protos",
                    module);

            ProtosPrelude prelude = module.preludeOrNullForRuntime();
            ProtosLanguageContext entered =
                    ProtosLanguageContext.currentIfEnteredForRuntime();

            var zero =
                    ProtosBytecodeRootNode.PrepareSendArguments
                            .createGuardedSendForPrelude(
                                    receiver, "zero", prelude, entered);

            var one =
                    ProtosBytecodeRootNode.PrepareSendArguments
                            .createGuardedSendForPrelude(
                                    receiver, "one", prelude, entered);

            assertTrue(zero != null, "Send0 guarded selection");
            assertTrue(one != null, "Send1 guarded selection");

            assertTrue(
                    ProtosSemanticBytecodeRootNode
                            .TryDirectSendZero.admitted(zero),
                    "zero-argument source method must be admitted");

            assertFalse(
                    ProtosSemanticBytecodeRootNode
                            .TryDirectSendOne.admitted(zero),
                    "zero-argument source method rejects Send1");

            assertTrue(
                    ProtosSemanticBytecodeRootNode
                            .TryDirectSendOne.admitted(one),
                    "one-argument source method must be admitted");
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

    private static ProtosBytecodeRootNode.PreparedClosureCall fastDirect(
            ProtosClosureValue closure, ProtosActivation caller, Object... supplied) {
        ProtosLanguageContext entered = ProtosLanguageContext.currentIfEnteredForRuntime();
        ProtosClosureValue selected =
                ProtosBytecodeRootNode.directClosureCallSelectionOrNull(closure, caller);
        assertSame(closure, selected, "canonical direct Closure-call selection");
        RootCallTarget target =
                ProtosBytecodeRootNode.PrepareSendArguments.fastOrdinarySendTarget(
                        closure, entered);
        return ProtosBytecodeRootNode.PrepareClosureCallArguments.fastDirect(
                closure,
                caller,
                supplied,
                selected,
                selected.definition(),
                entered,
                selected.definition(),
                entered,
                target);
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

    private static ProtosIntegerValue integer(long value) {
        return new ProtosIntegerValue(value);
    }

    private static String integerValue(Object value) {
        return com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerDecimalText(value);
    }
}
