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
import com.guillermomolina.protos.runtime.ProtosCoreErrors;
import com.guillermomolina.protos.runtime.ProtosDynamicControlState;
import com.guillermomolina.protos.runtime.ProtosExecutionContextValue;
import com.guillermomolina.protos.runtime.ProtosIntegerValue;
import com.guillermomolina.protos.runtime.ProtosPrelude;
import com.guillermomolina.protos.runtime.ProtosReturnHome;
import com.guillermomolina.protos.runtime.ProtosSignalException;
import com.guillermomolina.protos.runtime.ProtosTask;
import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.nodes.IndirectCallNode;
import com.oracle.truffle.api.source.Source;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;

/**
 * PERF025 / PLAT040 F′ coverage for direct source-backed Closure invocation.
 *
 * <p>A stable direct Closure-call hit and a Task-owned direct source Closure
 * entry (the path {@code PreparedTopLevel.invoke()} takes) must reach the
 * source target through the compact direct-Closure frame ABI: no pre-target
 * rich activation, no eager guest execution Context and no eager guest
 * supplied-argument Array. The callee-materialized activation must be exactly
 * the one the eager {@code forClosureInvocation} shape built.
 */
final class ProtosPerf025DirectSourceClosureCompactInvocationTest {
    private static final Path CORE = Path.of("protos", "lib", "core");

    @Test
    void stableDirectHitEntersSourceTargetThroughCompactAbiWithDeferredState()
            throws Exception {
        try (Context context = Context.newBuilder(ProtosLanguage.ID).build()) {
            context.initialize(ProtosLanguage.ID);
            context.enter();
            try {
                ProtosActivation module = coreModule();
                ProtosClosureValue add =
                        parsedClosure("(a, b) => { a + b }", "perf025-add.protos", module);
                ProtosDynamicControlState callerControl = module.dynamicControlState();

                ProtosIntegerValue two = integer(2);
                ProtosIntegerValue three = integer(3);
                ProtosBytecodeRootNode.PreparedClosureCall prepared =
                        fastDirect(add, module, two, three);

                RootCallTarget stableTarget =
                        ProtosBytecodeRootNode.PrepareSendArguments.fastOrdinarySendTarget(
                                add, ProtosLanguageContext.currentIfEnteredForRuntime());
                assertSame(
                        stableTarget,
                        prepared.bodyTarget(),
                        "the stable direct hit keeps the Context-owned source target");
                Object[] targetArguments = prepared.targetArguments();
                assertSame(add, targetArguments[0]);
                assertTrue(
                        java.util.Arrays.stream(targetArguments)
                                .filter(ProtosActivation.class::isInstance)
                                .allMatch(candidate -> candidate == module),
                        "only the caller's provenance activation exists before the source target");
                assertThrows(IllegalStateException.class, prepared::activation);

                Object result = enter(prepared);
                assertEquals("5", integerValue(result));

                ProtosActivation callee =
                        assertInstanceOf(ProtosActivation.class, targetArguments[0]);
                assertDeferred(callee);
                assertSame(add.capturedReceiver(), callee.receiver());
                assertEquals(add.methodHome(), callee.methodHome());
                assertEquals(add.capturedLexicalContexts(), callee.capturedLexicalContexts());
                assertSame(module.actorModuleState(), callee.actorModuleState());
                assertSame(module.executionDomain(), callee.executionDomain());
                assertEquals(module.currentModuleKey(), callee.currentModuleKey());
                assertTrue(callee.ownsReturnHome());
                assertFalse(
                        callee.returnHome().orElseThrow().isActive(),
                        "the owned invocation home completes with the call");
                assertTrue(callee.task().isEmpty());
                assertSame(
                        callerControl,
                        callee.dynamicControlStateIfPresent().orElseThrow(),
                        "the callee inherits the caller's dynamic-control flow");

                List<?> supplied = callee.suppliedArgumentsForRuntime();
                assertEquals(2, supplied.size());
                assertSame(two, supplied.get(0));
                assertSame(three, supplied.get(1));
                ProtosExecutionContextValue observed =
                        assertInstanceOf(ProtosExecutionContextValue.class, callee.context());
                assertSame(observed, callee.context(), "one guest Context identity");
                assertSame(callee, targetArguments[0], "one activation identity");
            } finally {
                context.leave();
            }
        }

        System.out.println("PERF025_DIRECT_SOURCE_CLOSURE_COMPACT_ABI=PASS");
        System.out.println("PERF025_DIRECT_SOURCE_EAGER_GUEST_CONTEXT=NO");
        System.out.println("PERF025_DIRECT_SOURCE_EAGER_GUEST_ARGUMENT_ARRAY=NO");
    }

    @Test
    void successiveDirectInvocationsGetDistinctActivationsAndContexts() throws Exception {
        try (Context context = Context.newBuilder(ProtosLanguage.ID).build()) {
            context.initialize(ProtosLanguage.ID);
            context.enter();
            try {
                ProtosActivation module = coreModule();
                ProtosClosureValue identity =
                        parsedClosure("(value) => { value }", "perf025-identity.protos", module);

                ProtosBytecodeRootNode.PreparedClosureCall first =
                        fastDirect(identity, module, integer(1));
                ProtosBytecodeRootNode.PreparedClosureCall second =
                        fastDirect(identity, module, integer(2));
                assertEquals("1", integerValue(enter(first)));
                assertEquals("2", integerValue(enter(second)));

                /*
                 * PERF025-H1: the ordinary parameter path never materializes
                 * the callee activation; observing it afterwards materializes
                 * each invocation's own exact activation on demand.
                 */
                assertSame(identity, first.targetArguments()[0]);
                assertSame(identity, second.targetArguments()[0]);
                ProtosActivation firstCallee =
                        ProtosFrameArguments.activation(first.targetArguments());
                ProtosActivation secondCallee =
                        ProtosFrameArguments.activation(second.targetArguments());
                assertNotSame(firstCallee, secondCallee);
                // PERF025: `(value) => { value }` is proven return-home
                // unobservable, so neither invocation materializes a home.
                assertSame(ProtosReturnHome.unobservable(), firstCallee.returnHome().orElseThrow());
                assertSame(ProtosReturnHome.unobservable(), secondCallee.returnHome().orElseThrow());
                assertNotSame(firstCallee.context(), secondCallee.context());
                assertEquals(
                        firstCallee.capturedLexicalContexts(),
                        secondCallee.capturedLexicalContexts(),
                        "both invocations share the same Closure capture state");

                Object contexts =
                        evaluate(
                                "f: () => { context }\n[f(), f()]\n",
                                "perf025-fresh-context.protos",
                                module);
                List<Object> observed =
                        assertInstanceOf(ProtosArrayValue.class, contexts).indexedSnapshot();
                assertInstanceOf(ProtosExecutionContextValue.class, observed.get(0));
                assertInstanceOf(ProtosExecutionContextValue.class, observed.get(1));
                assertNotSame(observed.get(0), observed.get(1));
            } finally {
                context.leave();
            }
        }

        System.out.println("PERF025_FRESH_CONTEXT_SEMANTICS=PASS");
    }

    @Test
    void restParameterStillMaterializesItsGuestArray() throws Exception {
        try (Context context = Context.newBuilder(ProtosLanguage.ID).build()) {
            context.initialize(ProtosLanguage.ID);
            context.enter();
            try {
                ProtosActivation module = coreModule();
                ProtosClosureValue rest =
                        parsedClosure(
                                "(first, ...rest) => { rest }", "perf025-rest.protos", module);

                ProtosBytecodeRootNode.PreparedClosureCall prepared =
                        fastDirect(rest, module, integer(1), integer(2), integer(3));
                ProtosArrayValue bound =
                        assertInstanceOf(ProtosArrayValue.class, enter(prepared));
                assertEquals(bigIntegers(2, 3), integerValues(bound.indexedSnapshot()));

                Object guestResult =
                        evaluate(
                                "r: (first, ...rest) => { rest }\nr(1, 2, 3)\n",
                                "perf025-rest-guest.protos",
                                module);
                assertEquals(bigIntegers(2, 3), integerValues(
                                assertInstanceOf(ProtosArrayValue.class, guestResult)
                                        .indexedSnapshot()));
            } finally {
                context.leave();
            }
        }

        System.out.println("PERF025_REST_ARGUMENT_SEMANTICS=PASS");
    }

    @Test
    void taskOwnedDirectSourceClosureUsesCompactPathWithExactTask() throws Exception {
        try (Context context = Context.newBuilder(ProtosLanguage.ID).build()) {
            context.initialize(ProtosLanguage.ID);
            context.enter();
            try {
                ProtosActivation module = coreModule();
                ProtosClosureValue add =
                        parsedClosure("(a, b) => { a + b }", "perf025-task-add.protos", module);
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
                                                                    add,
                                                                    List.of(integer(4), integer(5)),
                                                                    module,
                                                                    current);
                                            preparedRef.set(prepared);
                                            assertSame(current, prepared.taskForRuntime());
                                            assertThrows(
                                                    IllegalStateException.class,
                                                    prepared::activation);
                                            ProtosBytecodeTaskExecution.executePreparedClosure(
                                                    current, prepared);
                                        });
                assertTrue(module.executionDomain().dispatchOne());

                assertEquals(ProtosTask.State.COMPLETED, task.state());
                assertEquals("9", integerValue(task.result().orElseThrow()));
                ProtosActivation callee =
                        assertInstanceOf(
                                ProtosActivation.class,
                                preparedRef.get().targetArguments()[0]);
                assertSame(task, callee.task().orElseThrow());
                assertTrue(module.task().isEmpty(), "the creator is never mutated");
                assertDeferred(callee);
                assertFalse(callee.returnHome().orElseThrow().isActive());
            } finally {
                context.leave();
            }
        }

        System.out.println("PERF025_TASK_OWNED_DIRECT_SOURCE_COMPACT_PATH=PASS");
    }

    @Test
    void rootTaskClosureExecutionKeepsNonLocalReturnAndFailureSemantics() throws Exception {
        try (Context context = Context.newBuilder(ProtosLanguage.ID).build()) {
            context.initialize(ProtosLanguage.ID);
            context.enter();
            try {
                ProtosActivation module = coreModule();
                ProtosClosureValue outer =
                        parsedClosure(
                                "() => {\n    inner: () => { ^ 7 }\n    inner()\n    99\n}",
                                "perf025-nlr.protos",
                                module);
                ProtosExecutionOutcome completed =
                        ProtosRootTaskExecution.executeClosure(outer, List.of(), module);
                assertEquals("7", integerValue(completed.value()), "a nested Closure's non-local return reaches its owning invocation");

                ProtosClosureValue escaped =
                        assertInstanceOf(
                                ProtosClosureValue.class,
                                evaluate(
                                        "make: () => { () => { ^ 1 } }\nmake()\n",
                                        "perf025-escaped.protos",
                                        module));
                ProtosExecutionOutcome failed =
                        ProtosRootTaskExecution.executeClosure(escaped, List.of(), module);
                assertSame(
                        ProtosCoreErrors.prototype(
                                module, ProtosCoreErrors.StandardError.INVALID_RETURN),
                        failed.error().parent().orElseThrow(),
                        "an escaped return to a completed home is InvalidReturn");
            } finally {
                context.leave();
            }
        }

        System.out.println("PERF025_RETURN_HOME_SEMANTICS=PASS");
        System.out.println("PERF025_NON_LOCAL_RETURN=PASS");
    }

    @Test
    void guestDirectCallsKeepCaptureThisAndNonLocalReturnSemantics() throws Exception {
        try (Context context = Context.newBuilder(ProtosLanguage.ID).build()) {
            context.initialize(ProtosLanguage.ID);
            context.enter();
            try {
                ProtosActivation module = coreModule();

                assertEquals("2", integerValue(
                                evaluate(
                                        "n: 0\ninc: () => { n = n + 1 }\ninc()\ninc()\nn\n",
                                        "perf025-capture.protos",
                                        module)), "captured bindings are shared by reference");
                assertSame(
                        module.receiver(),
                        evaluate(
                                "receiverOf: () => { this }\nreceiverOf()\n",
                                "perf025-this.protos",
                                module));
                assertEquals("7", integerValue(
                                evaluate(
                                        "outer: () => {\n    inner: () => { ^ 7 }\n"
                                                + "    inner()\n    99\n}\nouter()\n",
                                        "perf025-guest-nlr.protos",
                                        module)));
                ProtosSignalException invalid =
                        assertThrows(
                                ProtosSignalException.class,
                                () ->
                                        evaluate(
                                                "make: () => { () => { ^ 1 } }\n"
                                                        + "escaped: make()\nescaped()\n",
                                                "perf025-guest-escaped.protos",
                                                module));
                assertSame(
                        ProtosCoreErrors.prototype(
                                module, ProtosCoreErrors.StandardError.INVALID_RETURN),
                        invalid.error().parent().orElseThrow());
            } finally {
                context.leave();
            }
        }

        System.out.println("PERF025_CAPTURE_BY_REFERENCE=PASS");
        System.out.println("PERF025_THIS_SEMANTICS=PASS");
    }

    @Test
    void compactDirectNonLocalReturnIsConsumedByTheOwnedHome() throws Exception {
        try (Context context = Context.newBuilder(ProtosLanguage.ID).build()) {
            context.initialize(ProtosLanguage.ID);
            context.enter();
            try {
                ProtosActivation module = coreModule();
                ProtosClosureValue returning =
                        parsedClosure("() => { ^ 5 }", "perf025-owned-nlr.protos", module);

                ProtosBytecodeRootNode.PreparedClosureCall prepared =
                        fastDirect(returning, module);
                ProtosReturnHome home =
                        ProtosFrameArguments.compactReturnHome(prepared.targetArguments());
                assertTrue(home.isActive());
                assertEquals("5", integerValue(enter(prepared)));
                assertFalse(home.isActive());
                assertSame(
                        home,
                        ((ProtosActivation) prepared.targetArguments()[0])
                                .returnHome()
                                .orElseThrow(),
                        "the callee observes the caller-established invocation home");
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

    private static void assertDeferred(ProtosActivation callee) throws Exception {
        assertNull(
                privateField(callee, "context"),
                "the guest execution Context is not materialized before observation");
        assertNull(
                privateField(callee, "arguments"),
                "no eager guest supplied-argument Array");
        Object deferred = privateField(callee, "deferredSuppliedArguments");
        assertNull(
                privateField(deferred, "guestArray"),
                "supplied values are not transported as a guest Array");
    }

    private static ProtosActivation coreModule() throws Exception {
        ProtosPrelude prelude = new ProtosCoreBootstrap().bootstrap(CORE);
        return prelude.newModuleActivation();
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

    private static List<String> integerValues(List<Object> values) {
        return values.stream()
                .map(ProtosPerf025DirectSourceClosureCompactInvocationTest::integerValue)
                .toList();
    }

    private static List<String> bigIntegers(long... values) {
        return java.util.Arrays.stream(values).mapToObj(Long::toString).toList();
    }

    private static Object privateField(Object target, String name)
            throws ReflectiveOperationException {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }
}
