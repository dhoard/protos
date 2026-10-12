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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.guillermomolina.protos.runtime.ProtosActivation;
import com.guillermomolina.protos.runtime.ProtosArrayValue;
import com.guillermomolina.protos.runtime.ProtosClosureValue;
import com.guillermomolina.protos.runtime.ProtosExecutionContextValue;
import com.guillermomolina.protos.runtime.ProtosIntegerValue;
import com.guillermomolina.protos.runtime.ProtosLexicalEnvironment;
import com.guillermomolina.protos.runtime.ProtosNullValue;
import com.guillermomolina.protos.runtime.ProtosObjectValue;
import com.guillermomolina.protos.runtime.ProtosPrelude;
import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.nodes.IndirectCallNode;
import com.oracle.truffle.api.source.Source;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.List;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;

/**
 * PERF025 lazy lexical capture: materializing a Closure literal captures its
 * creating activation's lexical environment by reference without creating the
 * guest execution Context or copying the outer capture chain, while every
 * observable capture-by-reference, D179 C0, object-body, debugger, reflection,
 * parallel-projection and method-rebinding behavior is preserved.
 */
final class ProtosPerf025LazyLexicalCaptureTest {
    private static final Path CORE = Path.of("protos", "lib", "core");

    @Test
    void trivialClosureDoesNotForceOuterContext() throws Exception {
        withCore(module -> {
            ProtosClosureValue make =
                    parsedClosure("() => { () => 1 }", "perf025-lazy-trivial.protos", module);
            Invocation outer = invokeDirect(make, module);
            ProtosClosureValue trivial =
                    assertInstanceOf(ProtosClosureValue.class, outer.result());

            if (outer.callee() != null) {
                assertNull(
                        privateField(outer.callee(), "context"),
                        "creating a Closure must not materialize the outer guest Context");
            }
            ProtosLexicalEnvironment captured =
                    trivial.capturedLexicalEnvironmentForRuntime();
            assertTrue(captured.isDeferredForRuntime());
            assertSame(
                    make.capturedLexicalEnvironmentForRuntime(),
                    captured.outer(),
                    "the outer chain is shared by reference, not copied");

            assertEquals("1", integerValue(invokeDirect(trivial, module).result()));
            if (outer.callee() != null) {
                assertNull(privateField(outer.callee(), "context"));
            }
            assertNull(
                    captured.materializedContextOrNullForRuntime(),
                    "trivial closure invocation must not observe the outer context");
            assertTrue(captured.isDeferredForRuntime());
        });
        System.out.println("TRIVIAL_CLOSURE_DOES_NOT_FORCE_OUTER_CONTEXT=PASS");
    }

    @Test
    void closuresFromOneActivationShareOneEnvironmentAndContextIdentity() throws Exception {
        withCore(module -> {
            ProtosClosureValue make =
                    parsedClosure(
                            "() => { [() => 1, () => 2] }",
                            "perf025-lazy-shared.protos",
                            module);
            Invocation outer = invokeDirect(make, module);
            List<Object> pair = elements(outer.result());
            ProtosClosureValue first = assertInstanceOf(ProtosClosureValue.class, pair.get(0));
            ProtosClosureValue second = assertInstanceOf(ProtosClosureValue.class, pair.get(1));

            assertNotSame(first, second, "each literal evaluation has fresh identity");
            assertSame(
                    first.capturedLexicalEnvironmentForRuntime(),
                    second.capturedLexicalEnvironmentForRuntime());
            assertNull(privateField(outer.callee(), "context"));

            ProtosObjectValue observedByFirst = first.capturedLexicalContexts().get(0);
            assertInstanceOf(ProtosExecutionContextValue.class, observedByFirst);
            assertSame(observedByFirst, second.capturedLexicalContexts().get(0));
            assertSame(observedByFirst, outer.callee().context());
            assertSame(
                    module.context(),
                    first.capturedLexicalContexts().get(1),
                    "the outer chain still reaches the module context");
        });
        System.out.println("MULTIPLE_CLOSURES_SHARE_ENVIRONMENT_IDENTITY=PASS");
    }

    @Test
    void contextIntrinsicObservesTheCapturedContextIdentity() throws Exception {
        withCore(module -> {
            List<Object> pair =
                    elements(evaluate(
                            "make: () => { [() => 1, context] }\nmake()",
                            "perf025-lazy-context.protos",
                            module));
            ProtosClosureValue closure = assertInstanceOf(ProtosClosureValue.class, pair.get(0));
            ProtosExecutionContextValue observed =
                    assertInstanceOf(ProtosExecutionContextValue.class, pair.get(1));
            assertSame(observed, closure.capturedLexicalContexts().get(0));
            assertSame(observed, closure.capturedLexicalEnvironmentForRuntime().context());
        });
        System.out.println("CONTEXT_INTRINSIC=PASS");
        System.out.println("CONTEXT_REFLECTION=PASS");
    }

    @Test
    void captureByReferenceSemanticsArePreserved() throws Exception {
        withCore(module -> {
            assertEquals("7", integerValue(evaluate(
                            "make: () => { x: 7\n () => x }\nf: make()\nf()",
                            "perf025-lazy-read.protos", freshModule(module))));
            assertEquals("2", integerValue(evaluate(
                            "make: () => { x: 1\n f: () => x\n x = 2\n f() }\nmake()",
                            "perf025-lazy-mutation.protos", freshModule(module))));
            assertEquals(List.of("1", "5"), integerValues(elements(evaluate(
                            "make: () => { x: 1\n [() => x, (v) => { x = v }] }\n"
                                    + "p: make()\n"
                                    + "before: p[0]()\n"
                                    + "p[1](5)\n"
                                    + "[before, p[0]()]",
                            "perf025-lazy-escaped.protos", freshModule(module)))));
            assertEquals("3", integerValue(evaluate(
                            "a: () => { x: 3\n () => { () => x } }\nb: a()\nc: b()\nc()",
                            "perf025-lazy-depth.protos", freshModule(module))));
            assertEquals("10", integerValue(evaluate(
                            "make: () => {\n x: 10\n mid: () => { () => x }\n mid\n}\n"
                                    + "m: make()\ng: m()\ng()",
                            "perf025-lazy-transitive.protos", freshModule(module))));
        });
        System.out.println("CAPTURE_BY_REFERENCE=PASS");
        System.out.println("LATER_MUTATION_VISIBLE=PASS");
        System.out.println("ESCAPED_CAPTURE_AFTER_OWNER_RETURN=PASS");
        System.out.println("MULTI_DEPTH_CAPTURE=PASS");
        System.out.println("TRANSITIVE_NESTED_CAPTURE=PASS");
    }

    @Test
    void d179PresenceSemanticsArePreservedThroughDeferredCaptures() throws Exception {
        withCore(module -> {
            assertEquals(List.of("1", "2"), integerValues(elements(evaluate(
                            "outer: () => {\n x: 1\n"
                                    + " mid: () => { r: () => x\n before: r()\n x: 2\n [before, r()] }\n"
                                    + " mid()\n}\nouter()",
                            "perf025-lazy-late.protos", freshModule(module)))));
            assertEquals(List.of("2", "1", "3"), integerValues(elements(evaluate(
                            "outer: () => {\n x: 1\n"
                                    + " mid: () => {\n  x: 2\n  r: () => x\n  before: r()\n"
                                    + "  context.removeSlot(\"x\")\n  removed: r()\n"
                                    + "  context.x: 3\n  [before, removed, r()]\n }\n"
                                    + " mid()\n}\nouter()",
                            "perf025-lazy-remove.protos", freshModule(module)))));
            assertSame(
                    ProtosNullValue.INSTANCE,
                    evaluate(
                            "outer: () => {\n x: 1\n mid: () => { x: null\n r: () => x\n r() }\n"
                                    + " mid()\n}\nouter()",
                            "perf025-lazy-present-null.protos", freshModule(module)));
        });
        System.out.println("LATE_NEARER_CREATION_RETARGETING=PASS");
        System.out.println("D179_C0_REMOVAL_FALLBACK=PASS");
        System.out.println("REMOVE_RECREATE=PASS");
        System.out.println("PRESENT_NULL_DISTINCT_FROM_ABSENT=PASS");
    }

    @Test
    void guardedMemberReadPreservesInvalidationFreshnessAndFallback()
            throws Exception {
        withCore(module -> {
            ProtosClosureValue scalarReader =
                    parsedClosure(
                            "(object) => object.value",
                            "perf025-member-read-scalar.protos",
                            module);

            ProtosObjectValue receiver =
                    new ProtosObjectValue(ProtosObjectValue.rootObject());
            Object firstValue = integer(1);
            Object secondValue = integer(2);
            receiver.createLocalSlot("value", firstValue);

            assertSame(
                    firstValue,
                    invokeDirect(
                                    scalarReader,
                                    module,
                                    receiver)
                            .result());

            receiver.assignLocalSlot("value", secondValue);

            assertSame(
                    secondValue,
                    invokeDirect(
                                    scalarReader,
                                    module,
                                    receiver)
                            .result());

            receiver.createLocalSlot("unrelated", integer(99));

            assertSame(
                    secondValue,
                    invokeDirect(
                                    scalarReader,
                                    module,
                                    receiver)
                            .result());

            ProtosClosureValue storedMethod =
                    parsedClosure(
                            "() => 7",
                            "perf025-member-read-method-value.protos",
                            module);
            receiver.createLocalSlot("method", storedMethod);

            ProtosClosureValue methodReader =
                    parsedClosure(
                            "(object) => object.method",
                            "perf025-member-read-method.protos",
                            module);

            ProtosClosureValue firstExtraction =
                    assertInstanceOf(
                            ProtosClosureValue.class,
                            invokeDirect(
                                            methodReader,
                                            module,
                                            receiver)
                                    .result());
            ProtosClosureValue secondExtraction =
                    assertInstanceOf(
                            ProtosClosureValue.class,
                            invokeDirect(
                                            methodReader,
                                            module,
                                            receiver)
                                    .result());

            assertNotSame(storedMethod, firstExtraction);
            assertNotSame(firstExtraction, secondExtraction);
            assertSame(receiver, firstExtraction.capturedReceiver());
            assertSame(receiver, secondExtraction.capturedReceiver());
            assertSame(receiver, firstExtraction.methodHome().orElseThrow());
            assertSame(receiver, secondExtraction.methodHome().orElseThrow());
            assertEquals("7", integerValue(
                            invokeDirect(firstExtraction, module).result()));
            assertEquals("7", integerValue(
                            invokeDirect(secondExtraction, module).result()));

            ProtosClosureValue polymorphicReader =
                    parsedClosure(
                            "(object) => object.value",
                            "perf025-member-read-polymorphic.protos",
                            module);

            java.util.ArrayList<ProtosObjectValue> receivers =
                    new java.util.ArrayList<>();
            for (int index = 0; index < 4; index++) {
                ProtosObjectValue candidate =
                        new ProtosObjectValue(
                                ProtosObjectValue.rootObject());
                candidate.createLocalSlot(
                        "value",
                        integer(index + 10));
                receivers.add(candidate);
            }

            for (int index = 0; index < receivers.size(); index++) {
                assertEquals(Long.toString(index + 10L), integerValue(
                                invokeDirect(
                                                polymorphicReader,
                                                module,
                                                receivers.get(index))
                                        .result()));
            }

            assertEquals("10", integerValue(
                            invokeDirect(
                                            polymorphicReader,
                                            module,
                                            receivers.get(0))
                                    .result()));

            ProtosObjectValue inheritedPrototype =
                    new ProtosObjectValue(
                            ProtosObjectValue.rootObject());
            Object inheritedFirst = integer(42);
            Object inheritedSecond = integer(43);
            inheritedPrototype.createLocalSlot(
                    "value",
                    inheritedFirst);

            java.util.ArrayList<ProtosObjectValue> inheritedReceivers =
                    new java.util.ArrayList<>();
            for (int index = 0; index < 6; index++) {
                inheritedReceivers.add(
                        new ProtosObjectValue(
                                inheritedPrototype));
            }

            for (ProtosObjectValue candidate : inheritedReceivers) {
                assertSame(
                        inheritedFirst,
                        invokeDirect(
                                        polymorphicReader,
                                        module,
                                        candidate)
                                .result());
            }

            ProtosObjectValue shadowed =
                    inheritedReceivers.get(0);
            Object shadowValue = integer(99);
            shadowed.createLocalSlot(
                    "value",
                    shadowValue);
            assertSame(
                    shadowValue,
                    invokeDirect(
                                    polymorphicReader,
                                    module,
                                    shadowed)
                            .result());

            shadowed.removeLocalSlot("value");
            assertSame(
                    inheritedFirst,
                    invokeDirect(
                                    polymorphicReader,
                                    module,
                                    shadowed)
                            .result());

            inheritedPrototype.assignLocalSlot(
                    "value",
                    inheritedSecond);
            for (ProtosObjectValue candidate : inheritedReceivers) {
                assertSame(
                        inheritedSecond,
                        invokeDirect(
                                        polymorphicReader,
                                        module,
                                        candidate)
                                .result());
            }

            ProtosObjectValue methodPrototype =
                    new ProtosObjectValue(
                            ProtosObjectValue.rootObject());
            methodPrototype.createLocalSlot(
                    "method",
                    storedMethod);
            ProtosObjectValue firstMethodReceiver =
                    new ProtosObjectValue(
                            methodPrototype);
            ProtosObjectValue secondMethodReceiver =
                    new ProtosObjectValue(
                            methodPrototype);

            ProtosClosureValue inheritedFirstExtraction =
                    assertInstanceOf(
                            ProtosClosureValue.class,
                            invokeDirect(
                                            methodReader,
                                            module,
                                            firstMethodReceiver)
                                    .result());
            ProtosClosureValue inheritedSecondExtraction =
                    assertInstanceOf(
                            ProtosClosureValue.class,
                            invokeDirect(
                                            methodReader,
                                            module,
                                            secondMethodReceiver)
                                    .result());
            ProtosClosureValue inheritedRepeatedExtraction =
                    assertInstanceOf(
                            ProtosClosureValue.class,
                            invokeDirect(
                                            methodReader,
                                            module,
                                            firstMethodReceiver)
                                    .result());

            assertNotSame(
                    inheritedFirstExtraction,
                    inheritedSecondExtraction);
            assertNotSame(
                    inheritedFirstExtraction,
                    inheritedRepeatedExtraction);
            assertSame(
                    firstMethodReceiver,
                    inheritedFirstExtraction.capturedReceiver());
            assertSame(
                    secondMethodReceiver,
                    inheritedSecondExtraction.capturedReceiver());
            assertSame(
                    firstMethodReceiver,
                    inheritedRepeatedExtraction.capturedReceiver());
            assertSame(
                    methodPrototype,
                    inheritedFirstExtraction.methodHome().orElseThrow());
            assertSame(
                    methodPrototype,
                    inheritedSecondExtraction.methodHome().orElseThrow());
            assertSame(
                    methodPrototype,
                    inheritedRepeatedExtraction.methodHome().orElseThrow());
        });

        System.out.println("GUARDED_MEMBER_READ_INVALIDATION=PASS");
        System.out.println("GUARDED_MEMBER_READ_FRESH_CLOSURE=PASS");
        System.out.println("GUARDED_MEMBER_READ_GENERIC_FALLBACK=PASS");
    }

    @Test
    void objectBodyIsNotALexicalCaptureScope() throws Exception {
        withCore(module -> {
            ProtosClosureValue make =
                    parsedClosure(
                            "() => {\n x: 1\n o: { x: 2\n m: () => x }\n [o.m, o.m()]\n}",
                            "perf025-lazy-object-body.protos",
                            module);
            Invocation outer = invokeDirect(make, module);
            List<Object> pair = elements(outer.result());
            ProtosClosureValue method = assertInstanceOf(ProtosClosureValue.class, pair.get(0));
            assertEquals("1", integerValue(pair.get(1)));
            assertSame(
                    outer.callee().lexicalEnvironmentForClosureCapture(),
                    method.capturedLexicalEnvironmentForRuntime(),
                    "a method created in an object body captures the enclosing genuine scope");
        });
        System.out.println("OBJECT_BODY_LEXICAL_BOUNDARY=PASS");
    }

    @Test
    void debuggerProjectsDeferredCapturesLive() throws Exception {
        withCore(module -> {
            List<Object> pair =
                    elements(evaluate(
                            "make: () => { x: 1\n [() => x, (v) => { x = v }] }\nmake()",
                            "perf025-lazy-debugger.protos", module));
            ProtosClosureValue reader = assertInstanceOf(ProtosClosureValue.class, pair.get(0));
            ProtosClosureValue writer = assertInstanceOf(ProtosClosureValue.class, pair.get(1));
            ProtosActivation invocation =
                    ProtosActivation.forClosureInvocation(
                            reader,
                            List.of(),
                            module.prelude().orElseThrow(),
                            module.actorModuleState(),
                            module.currentModuleKey().orElse(null),
                            module.executionDomain());

            InteropLibrary interop = InteropLibrary.getUncached();
            Object scope = debuggerScope(invocation);
            assertEquals("1", integerValue(interop.readMember(scope, "x")));
            invokeDirect(writer, module, integer(9));
            assertEquals("9", integerValue(interop.readMember(scope, "x")));
        });
        System.out.println("DEBUGGER_SCOPE_PROJECTION=PASS");
    }

    @Test
    void bindMethodAndParallelProjectionKeepTheirCaptureBoundaries() throws Exception {
        withCore(module -> {
            ProtosClosureValue make =
                    parsedClosure("() => { x: 4\n () => x }", "perf025-lazy-rebind.protos", module);
            Invocation outer = invokeDirect(make, module);
            ProtosClosureValue closure =
                    assertInstanceOf(ProtosClosureValue.class, outer.result());
            ProtosLexicalEnvironment captured = closure.capturedLexicalEnvironmentForRuntime();

            ProtosObjectValue receiver = new ProtosObjectValue(ProtosObjectValue.rootObject());
            ProtosClosureValue bound = closure.bindMethod(receiver, receiver);
            assertSame(captured, bound.capturedLexicalEnvironmentForRuntime());
            assertSame(receiver, bound.capturedReceiver());

            ProtosPrelude prelude = module.prelude().orElseThrow();
            ProtosObjectValue isolatedRoot = prelude.newExecutionContext();
            ProtosClosureValue projected =
                    closure.parallelProjection(
                            List.of(isolatedRoot),
                            receiver,
                            prelude,
                            closure.executionPlan().orElse(null));
            assertEquals(List.of(isolatedRoot), projected.capturedLexicalContexts());
            assertNotSame(captured, projected.capturedLexicalEnvironmentForRuntime());
            assertTrue(
                    captured.isDeferredForRuntime(),
                    "rebinding and projection never materialize the caller's environment");
            assertNull(privateField(outer.callee(), "context"));
        });
        System.out.println("BIND_METHOD_CAPTURE_PRESERVED=PASS");
        System.out.println("PARALLEL_PROJECTION_BOUNDARY_PRESERVED=PASS");
    }

    private record Invocation(ProtosActivation callee, Object result) {}

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

    private static Invocation invokeDirect(
            ProtosClosureValue closure, ProtosActivation caller, Object... supplied) {
        ProtosLanguageContext entered = ProtosLanguageContext.currentIfEnteredForRuntime();
        ProtosClosureValue selected =
                ProtosBytecodeRootNode.directClosureCallSelectionOrNull(closure, caller);
        assertSame(closure, selected, "canonical direct Closure-call selection");
        RootCallTarget target =
                ProtosBytecodeRootNode.PrepareSendArguments.fastOrdinarySendTarget(
                        closure, entered);
        ProtosBytecodeRootNode.PreparedClosureCall prepared =
                ProtosBytecodeRootNode.PrepareClosureCallArguments.fastDirect(
                        closure,
                        caller,
                        supplied,
                        selected,
                        selected.definition(),
                        entered,
                        selected.definition(),
                        entered,
                        target);
        Object entry =
                ProtosBytecodeRootNode.EnterClosureCall.ordinaryIndirect(
                        assertInstanceOf(ProtosBytecodeRootNode.OrdinarySourceCall.class, prepared),
                        IndirectCallNode.create());
        Object result = prepared.finish(entry);
        /*
         * PERF025-H1: a callee whose body never needs its rich activation
         * (for example a trivial Closure) leaves it unmaterialized.
         */
        ProtosActivation callee =
                prepared.targetArguments()[0] instanceof ProtosActivation materialized
                        ? materialized
                        : null;
        assertNotNull(result);
        return new Invocation(callee, result);
    }

    private static Object debuggerScope(ProtosActivation activation) throws Exception {
        VirtualFrame frame =
                Truffle.getRuntime()
                        .createVirtualFrame(
                                new Object[] {activation},
                                FrameDescriptor.newBuilder().build());
        assertTrue(ProtosBytecodeTagTreeNodeExports.hasScope(null, frame));
        return ProtosBytecodeTagTreeNodeExports.getScope(null, frame, true);
    }

    private static Object evaluate(String characters, String name, ProtosActivation activation) {
        Source source =
                Source.newBuilder(ProtosLanguage.ID, characters, name)
                        .mimeType(ProtosLanguage.MIME_TYPE)
                        .build();
        CallTarget target = ProtosLanguageContext.current().parsePublic(source);
        return target.call(activation);
    }

    /** Each guest program gets its own module scope, so top-level names do not collide. */
    private static ProtosActivation freshModule(ProtosActivation module) {
        return module.prelude().orElseThrow().newModuleActivation();
    }

    private static ProtosClosureValue parsedClosure(
            String characters, String name, ProtosActivation activation) {
        return assertInstanceOf(
                ProtosClosureValue.class, evaluate(characters, name, activation));
    }

    private static List<Object> elements(Object value) {
        return assertInstanceOf(ProtosArrayValue.class, value).indexedSnapshot();
    }

    private static ProtosIntegerValue integer(long value) {
        return new ProtosIntegerValue(value);
    }

    private static String integerValue(Object value) {
        return com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerDecimalText(value);
    }

    private static List<String> integerValues(List<Object> values) {
        return values.stream()
                .map(ProtosPerf025LazyLexicalCaptureTest::integerValue)
                .toList();
    }

    private static Object privateField(Object target, String name)
            throws ReflectiveOperationException {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }
}
