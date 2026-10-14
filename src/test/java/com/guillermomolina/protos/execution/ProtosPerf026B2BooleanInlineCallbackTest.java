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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.guillermomolina.protos.parser.ProtosParser;
import com.guillermomolina.protos.runtime.ProtosActivation;
import com.guillermomolina.protos.runtime.ProtosBooleanValue;
import com.guillermomolina.protos.runtime.ProtosClosureValue;
import com.guillermomolina.protos.runtime.ProtosNullValue;
import com.guillermomolina.protos.runtime.ProtosObjectValue;
import com.guillermomolina.protos.runtime.ProtosSignalException;
import com.guillermomolina.protos.semantic.Canonicalizer;
import com.guillermomolina.protos.semantic.ast.CanonicalSequence;
import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.TruffleLanguage.LanguageReference;
import com.oracle.truffle.api.bytecode.BytecodeNode;
import com.oracle.truffle.api.bytecode.TagTree;
import com.oracle.truffle.api.debug.Breakpoint;
import com.oracle.truffle.api.debug.DebugScope;
import com.oracle.truffle.api.debug.DebugValue;
import com.oracle.truffle.api.debug.Debugger;
import com.oracle.truffle.api.debug.DebuggerSession;
import com.oracle.truffle.api.instrumentation.StandardTags;
import com.oracle.truffle.api.nodes.RootNode;
import com.oracle.truffle.api.source.Source;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Instrument;
import org.junit.jupiter.api.Test;

/**
 * PERF026-B2 (PLAT044 B′) focal evidence: the B1 single-literal inline callback
 * path now also serves standard {@code ifFalse}, {@code and} and {@code or}
 * when their one callback is selected; unselected callbacks are never entered,
 * and AND/OR results still pass the standard Boolean-result validation
 * ({@code ifTrueIfFalse} is covered by {@link
 * ProtosPerf026B3BooleanInlineCallbackTest}).
 *
 * <p>Eligible sites are placed inside a Closure ({@code run}) because a literal
 * at module top level owns a fresh return home, a shape B′ keeps on the
 * physical path.
 */
final class ProtosPerf026B2BooleanInlineCallbackTest {
    private static final Path CORE = Path.of("protos", "lib", "core");

    private static final LanguageReference<ProtosLanguage> LANGUAGE_REF =
            LanguageReference.create(ProtosLanguage.class);

    @Test
    void eligibleIfFalseLiteralCallbackRunsInline() throws Exception {
        Object result =
                assertCallbackRootRemoved(
                        """
                        run: () => {
                            false.ifFalse(() => {
                                probe()
                                1
                            })
                        }
                        run()
                        """);
        assertInteger(1, result);
        System.out.println("PERF026_B2_IF_FALSE_LITERAL_CALLBACK_ROOT_REMOVED=YES");
    }

    @Test
    void eligibleAndLiteralCallbackRunsInline() throws Exception {
        Object result =
                assertCallbackRootRemoved(
                        """
                        run: () => {
                            true.and(() => {
                                probe()
                                true
                            })
                        }
                        run()
                        """);
        assertSame(ProtosBooleanValue.TRUE, result);
        System.out.println("PERF026_B2_AND_LITERAL_CALLBACK_ROOT_REMOVED=YES");
    }

    @Test
    void eligibleOrLiteralCallbackRunsInline() throws Exception {
        Object result =
                assertCallbackRootRemoved(
                        """
                        run: () => {
                            false.or(() => {
                                probe()
                                false
                            })
                        }
                        run()
                        """);
        assertSame(ProtosBooleanValue.FALSE, result);
        System.out.println("PERF026_B2_OR_LITERAL_CALLBACK_ROOT_REMOVED=YES");
    }

    @Test
    void unselectedCallbacksAreNotEntered() throws Exception {
        assertSame(
                ProtosNullValue.INSTANCE,
                assertCallbackNotEntered(
                        """
                        run: () => {
                            true.ifFalse(() => {
                                probe()
                                1
                            })
                        }
                        run()
                        """));
        System.out.println("PERF026_B2_TRUE_IF_FALSE_CALLBACK_ENTERED=NO");

        assertSame(
                ProtosBooleanValue.FALSE,
                assertCallbackNotEntered(
                        """
                        run: () => {
                            false.and(() => {
                                probe()
                                true
                            })
                        }
                        run()
                        """));
        System.out.println("PERF026_B2_FALSE_AND_CALLBACK_ENTERED=NO");

        assertSame(
                ProtosBooleanValue.TRUE,
                assertCallbackNotEntered(
                        """
                        run: () => {
                            true.or(() => {
                                probe()
                                false
                            })
                        }
                        run()
                        """));
        System.out.println("PERF026_B2_TRUE_OR_CALLBACK_ENTERED=NO");
    }

    /**
     * A non-Boolean result of an inline AND/OR callback still reaches
     * FinishStructuredBooleanCallback: it signals an Error with the same
     * prototype as the physical (dynamic-callback) path in the same module.
     */
    @Test
    void inlineAndOrCallbackResultsKeepBooleanValidation() throws Exception {
        assertSameInvalidResultError("true.and", "1");
        System.out.println("PERF026_B2_AND_BOOLEAN_RESULT_VALIDATION_PRESERVED=YES");
        assertSameInvalidResultError("false.or", "null");
        System.out.println("PERF026_B2_OR_BOOLEAN_RESULT_VALIDATION_PRESERVED=YES");
    }

    @Test
    void dynamicCallbackRetainsItsPhysicalRoot() throws Exception {
        for (String site :
                List.of(
                        "false.ifFalse(callback)",
                        "true.and(callback)",
                        "false.or(callback)")) {
            assertCallbackRootPreserved(
                    """
                    run: () => {
                        callback: () => {
                            probe()
                            true
                        }
                        %s
                    }
                    run()
                    """
                            .formatted(site));
        }
        System.out.println("PERF026_B2_DYNAMIC_CALLBACK_ROOT_PRESERVED=YES");
    }

    @Test
    void customSelectorAndNonClosureCallbacksStayOrdinary() throws Exception {
        try (Context context = Context.newBuilder(ProtosLanguage.ID).build()) {
            context.initialize(ProtosLanguage.ID);
            context.enter();
            try {
                Probe custom = new Probe();
                ProtosSemanticBytecodeRootNode customRoot =
                        lowerRoot(
                                """
                                run: () => {
                                    custom: {
                                        and: (callback) => {
                                            probe()
                                            5
                                        }
                                    }
                                    custom.and(() => { true })
                                }
                                run()
                                """);
                assertInteger(5, customRoot.getCallTarget().call(custom.module()));
                assertEquals(3, custom.stack.size(), () -> "stack=" + custom.stack);

                Probe callable = new Probe();
                ProtosSemanticBytecodeRootNode callableRoot =
                        lowerRoot(
                                """
                                run: () => {
                                    callable: {
                                        call: () => {
                                            probe()
                                            true
                                        }
                                    }
                                    false.or(callable)
                                }
                                run()
                                """);
                assertSame(
                        ProtosBooleanValue.TRUE,
                        callableRoot.getCallTarget().call(callable.module()));
                assertEquals(3, callable.stack.size(), () -> "stack=" + callable.stack);
            } finally {
                context.leave();
            }
        }
        System.out.println("PERF026_B2_CUSTOM_BOOLEAN_SELECTOR_FALLBACK_PRESERVED=YES");
        System.out.println("PERF026_B2_NONCLOSURE_INVOKABLE_FALLBACK_PRESERVED=YES");
    }

    @Test
    void inlineCallbackKeepsReturnHomeCaptureAndFreshActivation() throws Exception {
        try (Context context = Context.newBuilder(ProtosLanguage.ID).build()) {
            context.initialize(ProtosLanguage.ID);
            context.enter();
            try {
                Probe nonLocalReturn = new Probe();
                assertInteger(
                        42,
                        lowerRoot(
                                        """
                                        run: () => {
                                            false.ifFalse(() => {
                                                probe()
                                                ^41
                                            })
                                            100
                                        }
                                        run() + 1
                                        """)
                                .getCallTarget()
                                .call(nonLocalReturn.module()));
                assertEquals(2, nonLocalReturn.stack.size(), () -> "stack=" + nonLocalReturn.stack);

                Probe capture = new Probe();
                assertInteger(
                        42,
                        lowerRoot(
                                        """
                                        run: () => {
                                            count: 1
                                            true.and(() => {
                                                probe()
                                                count = count + 40
                                                true
                                            })
                                            count + 1
                                        }
                                        run()
                                        """)
                                .getCallTarget()
                                .call(capture.module()));
                assertEquals(2, capture.stack.size(), () -> "stack=" + capture.stack);

                Probe activation = new Probe();
                assertSame(
                        ProtosBooleanValue.TRUE,
                        lowerRoot(
                                        """
                                        run: () => {
                                            outer: context
                                            first: null
                                            false.or(() => {
                                                probe()
                                                first = context
                                                true
                                            })
                                            second: false.ifFalse(() => { context })
                                            (outer !== first) && (first !== second)
                                        }
                                        run()
                                        """)
                                .getCallTarget()
                                .call(activation.module()));
                assertEquals(2, activation.stack.size(), () -> "stack=" + activation.stack);
            } finally {
                context.leave();
            }
        }
        System.out.println("PERF026_B2_NLR_PRESERVED=YES");
        System.out.println("PERF026_B2_CAPTURE_BY_REFERENCE_PRESERVED=YES");
        System.out.println("PERF026_B2_FRESH_ACTIVATION_PRESERVED=YES");
    }

    @Test
    void errorAndSuspensionCrossTheInlineCallback() throws Exception {
        ProtosExecutionOutcome error =
                ProtosTestExecutionSupport.execute(
                        "perf026-b2-error.protos",
                        """
                        Error.handle(() => {
                            false.ifFalse(() => { Error().signal() })
                            0
                        }, (caught) => { 42 })
                        """,
                        new ProtosCoreBootstrap().bootstrap(CORE).newModuleActivation());
        assertCompletedWith(42, error);

        ProtosExecutionOutcome suspension =
                ProtosTestExecutionSupport.execute(
                        "perf026-b2-suspension.protos",
                        """
                        run: () => {
                            pending: (() => { 41 }).future()
                            result: 0
                            true.and(() => {
                                result = pending.value() + 1
                                true
                            })
                            result
                        }
                        run()
                        """,
                        new ProtosCoreBootstrap().bootstrap(CORE).newModuleActivation());
        assertCompletedWith(42, suspension);

        System.out.println("PERF026_B2_ERROR_PROPAGATION_PRESERVED=YES");
        System.out.println("PERF026_B2_FUTURE_WAIT_PRESERVED=YES");
    }

    /** The generic B1 inline RootTag/scope projection serves a B2 kind. */
    @Test
    void debuggerScopeInsideInlineOrCallbackProjectsTheCallbackActivation()
            throws Exception {
        Source source =
                Source.newBuilder(
                                ProtosLanguage.ID,
                                """
                                run: () => {
                                    outerMarker: 1
                                    false.or(() => {
                                        innerMarker: 2
                                        innerMarker + outerMarker
                                        true
                                    })
                                }
                                run()
                                """,
                                "perf026-b2-debugger.protos")
                        .uri(URI.create("memory:///perf026-b2-debugger.protos"))
                        .mimeType(ProtosLanguage.MIME_TYPE)
                        .build();
        ProtosActivation module = new ProtosCoreBootstrap().bootstrap(CORE).newModuleActivation();
        AtomicReference<Throwable> callbackFailure = new AtomicReference<>();
        AtomicReference<String> inner = new AtomicReference<>();
        AtomicReference<String> outer = new AtomicReference<>();
        AtomicInteger suspensions = new AtomicInteger();

        try (ProtosPolyglotExecutionContext polyglot =
                ProtosPolyglotExecutionContext.open(
                        InputStream.nullInputStream(),
                        OutputStream.nullOutputStream(),
                        OutputStream.nullOutputStream())) {
            Instrument instrument =
                    polyglot.engineForTesting().getInstruments().get("debugger");
            assertNotNull(instrument);
            Debugger debugger = instrument.lookup(Debugger.class);
            try (DebuggerSession session =
                    debugger.startSession(
                            event -> {
                                try {
                                    suspensions.incrementAndGet();
                                    DebugScope scope = event.getTopStackFrame().getScope();
                                    DebugValue innerValue = scope.getDeclaredValue("innerMarker");
                                    DebugValue outerValue = scope.getDeclaredValue("outerMarker");
                                    assertNotNull(innerValue, "callback-owned binding must be visible");
                                    assertNotNull(outerValue, "captured binding must be visible");
                                    inner.set(innerValue.toDisplayString());
                                    outer.set(outerValue.toDisplayString());
                                } catch (Throwable failure) {
                                    callbackFailure.compareAndSet(null, failure);
                                } finally {
                                    event.prepareContinue();
                                }
                            })) {
                session.install(Breakpoint.newBuilder(source).lineIs(5).build());
                ProtosExecutionOutcome outcome = polyglot.execute(source, module);
                assertEquals(
                        ProtosExecutionOutcome.State.COMPLETED,
                        outcome.state(),
                        () -> "error=" + outcome.error());
                assertSame(ProtosBooleanValue.TRUE, outcome.value());
            }
        }

        if (callbackFailure.get() != null) {
            throw new AssertionError("debugger callback failed", callbackFailure.get());
        }
        assertEquals(1, suspensions.get(), "exactly one inline-region breakpoint suspension");
        assertEquals("2", inner.get());
        assertEquals("1", outer.get());
        System.out.println("PERF026_B2_INLINE_SCOPE_PROJECTION_PRESERVED=YES");
    }

    private static Object assertCallbackRootRemoved(String characters) throws Exception {
        try (Context context = Context.newBuilder(ProtosLanguage.ID).build()) {
            context.initialize(ProtosLanguage.ID);
            context.enter();
            try {
                Probe probe = new Probe();
                ProtosSemanticBytecodeRootNode root = lowerRoot(characters);

                Object result = root.getCallTarget().call(probe.module());

                assertEquals(1, probe.calls.get(), characters);
                assertEquals(2, probe.stack.size(), () -> characters + " stack=" + probe.stack);
                ProtosSemanticBytecodeRootNode runRoot =
                        assertInstanceOf(ProtosSemanticBytecodeRootNode.class, probe.stack.get(0));
                assertSame(root, probe.stack.get(1));

                runRoot.getRootNodes().ensureComplete();
                assertEquals(
                        2,
                        rootTags(runRoot.getBytecodeNode()),
                        "automatic RootTag of run plus the inline callback RootTag");
                return result;
            } finally {
                context.leave();
            }
        }
    }

    private static Object assertCallbackNotEntered(String characters) throws Exception {
        try (Context context = Context.newBuilder(ProtosLanguage.ID).build()) {
            context.initialize(ProtosLanguage.ID);
            context.enter();
            try {
                Probe probe = new Probe();
                Object result = lowerRoot(characters).getCallTarget().call(probe.module());
                assertEquals(0, probe.calls.get(), characters);
                return result;
            } finally {
                context.leave();
            }
        }
    }

    private static void assertCallbackRootPreserved(String characters) throws Exception {
        try (Context context = Context.newBuilder(ProtosLanguage.ID).build()) {
            context.initialize(ProtosLanguage.ID);
            context.enter();
            try {
                Probe probe = new Probe();
                ProtosSemanticBytecodeRootNode root = lowerRoot(characters);

                root.getCallTarget().call(probe.module());

                assertEquals(1, probe.calls.get(), characters);
                assertEquals(3, probe.stack.size(), () -> characters + " stack=" + probe.stack);
                ProtosSemanticBytecodeRootNode callback =
                        assertInstanceOf(ProtosSemanticBytecodeRootNode.class, probe.stack.get(0));
                assertSame(root, probe.stack.get(2));

                callback.getRootNodes().ensureComplete();
                assertEquals(1, rootTags(callback.getBytecodeNode()), characters);
            } finally {
                context.leave();
            }
        }
    }

    /**
     * Runs {@code receiverSend(() => { probe(); value })} inline and the same
     * send with a dynamic callback physically, in one module, and requires both
     * to signal an Error of the same prototype; the inline run must have had
     * no callback root.
     */
    private static void assertSameInvalidResultError(String receiverSend, String value)
            throws Exception {
        try (Context context = Context.newBuilder(ProtosLanguage.ID).build()) {
            context.initialize(ProtosLanguage.ID);
            context.enter();
            try {
                Probe probe = new Probe();
                ProtosActivation module = probe.module();
                ProtosSemanticBytecodeRootNode inlineRoot =
                        lowerRoot(
                                """
                                inlineRun: () => {
                                    %s(() => {
                                        probe()
                                        %s
                                    })
                                }
                                inlineRun()
                                """
                                        .formatted(receiverSend, value));
                ProtosSignalException inline =
                        assertThrows(
                                ProtosSignalException.class,
                                () -> inlineRoot.getCallTarget().call(module));
                assertEquals(1, probe.calls.get());
                assertEquals(2, probe.stack.size(), () -> "stack=" + probe.stack);

                ProtosSemanticBytecodeRootNode physicalRoot =
                        lowerRoot(
                                """
                                physicalRun: () => {
                                    callback: () => {
                                        probe()
                                        %s
                                    }
                                    %s(callback)
                                }
                                physicalRun()
                                """
                                        .formatted(value, receiverSend));
                ProtosSignalException physical =
                        assertThrows(
                                ProtosSignalException.class,
                                () -> physicalRoot.getCallTarget().call(module));
                assertEquals(2, probe.calls.get());
                assertEquals(3, probe.stack.size(), () -> "stack=" + probe.stack);

                ProtosObjectValue inlineError = inline.error();
                ProtosObjectValue physicalError = physical.error();
                assertNotSame(inlineError, physicalError);
                assertTrue(inlineError.parent().isPresent());
                assertSame(physicalError.parent().orElseThrow(), inlineError.parent().orElseThrow());
            } finally {
                context.leave();
            }
        }
    }

    /** A native {@code probe} binding recording the guest CallTarget stack. */
    private static final class Probe {
        final List<RootNode> stack = new ArrayList<>();
        final AtomicInteger calls = new AtomicInteger();

        ProtosActivation module() throws Exception {
            ProtosActivation module =
                    new ProtosCoreBootstrap().bootstrap(CORE).newModuleActivation();
            module.context().createLocalSlot(
                    "probe",
                    ProtosClosureValue.nativeClosure(
                            (activation, supplied) -> {
                                calls.incrementAndGet();
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
    }

    private static ProtosSemanticBytecodeRootNode lowerRoot(String characters) {
        Source source =
                Source.newBuilder(ProtosLanguage.ID, characters, "perf026-b2.protos").build();
        CanonicalSequence sequence =
                (CanonicalSequence)
                        new Canonicalizer()
                                .canonicalize(new ProtosParser(characters).parseProgram());
        return new CanonicalToBytecodeLowerer(LANGUAGE_REF.get(null), source, ProtosExecutionMode.NORMAL)
                .lowerRoot(sequence);
    }

    private static void assertCompletedWith(long expected, ProtosExecutionOutcome outcome) {
        assertEquals(
                ProtosExecutionOutcome.State.COMPLETED,
                outcome.state(),
                () -> "outcome=" + outcome.state() + ", error=" + outcome.error());
        assertInteger(expected, outcome.value());
    }

    private static void assertInteger(long expected, Object value) {
        assertEquals(Long.toString(expected), com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerDecimalText(value));
    }

    private static int rootTags(BytecodeNode bytecodeNode) {
        return countRootTags(bytecodeNode.getTagTree());
    }

    private static int countRootTags(TagTree tree) {
        if (tree == null) {
            return 0;
        }
        int count = tree.hasTag(StandardTags.RootTag.class) ? 1 : 0;
        for (TagTree child : tree.getTreeChildren()) {
            count += countRootTags(child);
        }
        return count;
    }
}
