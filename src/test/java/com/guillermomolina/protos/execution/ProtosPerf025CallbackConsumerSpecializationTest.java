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
 * the specific language governing rights and limitations under the LICENSE.
 */

package com.guillermomolina.protos.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.guillermomolina.protos.runtime.ProtosIntegerValue;

import com.guillermomolina.protos.execution.ProtosBytecodeRootNode.PreparedInlineLiteralCall;
import com.guillermomolina.protos.runtime.ProtosActivation;
import com.guillermomolina.protos.runtime.ProtosBooleanValue;
import com.guillermomolina.protos.runtime.ProtosClosureValue;
import com.guillermomolina.protos.runtime.ProtosLexicalBindingAuthority;
import com.oracle.truffle.api.bytecode.BytecodeNode;
import com.oracle.truffle.api.bytecode.Instruction;
import com.oracle.truffle.api.bytecode.TagTreeNode;
import com.oracle.truffle.api.debug.Breakpoint;
import com.oracle.truffle.api.debug.DebugStackFrame;
import com.oracle.truffle.api.debug.Debugger;
import com.oracle.truffle.api.debug.DebuggerSession;
import com.oracle.truffle.api.frame.Frame;
import com.oracle.truffle.api.frame.FrameInstance;
import com.oracle.truffle.api.source.Source;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.graalvm.polyglot.Instrument;
import org.junit.jupiter.api.Test;

/**
 * PERF025 slice 3 focal evidence: the common consumers of a frame-native
 * PLAT044 B′ inline callback (statically resolved captured reads and writes,
 * canonical Integer sends, direct source-Closure calls, {@code this}, member
 * reads and multiple creation) run their successful path without
 * materializing the callback activation, while observers, fallbacks and
 * Errors keep their exact semantics.
 *
 * <p>Runtime state is read at a breakpoint from the raw Bytecode frame, never
 * through a debugger scope, which would itself be an observer: the live
 * carrier reports whether the activation was materialized and whether the
 * frame bindings were transferred to a durable authority.
 */
final class ProtosPerf025CallbackConsumerSpecializationTest {
    private static final Path CORE = Path.of("protos", "lib", "core");

    @Test
    void capturedReadAndCanonicalIntegerSendStayLazy() throws Exception {
        Run run =
                runToLine(
                        """
                        run: () => {
                            limit: 3
                            Array(1).each((element) => {
                                result: element + limit
                                result
                            })
                        }
                        run()
                        """,
                        5);
        run.assertCompleted();
        Stop stop = run.single();
        stop.assertLazy();
        assertInteger(4, stop.binding("result"));
        stop.assertInstruction("ReadInlineCaptured");
        stop.assertInstruction("PrepareInlineSendOne");
        System.out.println("PERF025_S3_CAPTURED_READ_WITHOUT_ACTIVATION=YES");
        System.out.println("PERF025_S3_CANONICAL_INTEGER_SEND_WITHOUT_ACTIVATION=YES");
    }

    @Test
    void capturedAccumulatorUpdateStaysLazy() throws Exception {
        Run run =
                runToLine(
                        """
                        run: () => {
                            acc: 0
                            Array(1, 2, 3).each((element) => {
                                acc = acc + element
                                element
                            })
                            acc
                        }
                        run()
                        """,
                        5);
        run.assertCompleted();
        assertInteger(6, run.value());
        assertEquals(3, run.stops.size());
        for (Stop stop : run.stops) {
            stop.assertLazy();
            stop.assertInstruction("ResolveInlineCaptured");
            stop.assertInstruction("AssignInlineCaptured");
        }
        assertDistinctCarriers(run.stops);
        System.out.println("PERF025_S3_CAPTURED_WRITE_WITHOUT_ACTIVATION=YES");
    }

    /**
     * The RHS materializes the callback activation (a dynamic lookup is a
     * real observer); the captured destination selected before it is still
     * the one written (PERF028-A).
     */
    @Test
    void rhsMaterializationKeepsPreselectedCapturedDestination() throws Exception {
        Run run =
                runToLine(
                        """
                        run: () => {
                            acc: 0
                            Array(1, 2).each((element) => {
                                acc = acc + bump(element)
                                element
                            })
                            acc
                        }
                        run()
                        """,
                        5);
        run.assertCompleted();
        assertInteger(30, run.value());
        assertEquals(2, run.stops.size());
        for (Stop stop : run.stops) {
            assertTrue(stop.materialized(), "the dynamic lookup observes the activation");
            assertTrue(stop.durable(), "bindings must be durable once materialized");
        }
        System.out.println("PERF028_A_DESTINATION_SELECTED_BEFORE_RHS=YES");
        System.out.println("PERF028_A_NO_RETARGET_AFTER_RHS=YES");
    }

    @Test
    void canonicalIntegerWhileShapeStaysLazy() throws Exception {
        Run run =
                runToLine(
                        """
                        run: () => {
                            n: 0
                            (() => {
                                less: n < 3
                                less
                            }).whileTrue() {
                                n = n + 1
                                n
                            }
                            n
                        }
                        run()
                        """,
                        5,
                        8);
        run.assertCompleted();
        assertInteger(3, run.value());
        assertEquals(7, run.stops.size(), "four condition and three body stops");
        for (Stop stop : run.stops) {
            stop.assertLazy();
        }
        assertDistinctCarriers(run.stops);
        System.out.println("PERF025_S3_WHILE_INTEGER_OPERATIONS_WITHOUT_ACTIVATION=YES");
    }

    @Test
    void directSourceClosureCallStaysLazy() throws Exception {
        Run run =
                runToLine(
                        """
                        run: () => {
                            next: (value) => { value + 1 }
                            Array(1).each((element) => {
                                result: next(element)
                                result
                            })
                        }
                        run()
                        """,
                        5);
        run.assertCompleted();
        Stop stop = run.single();
        stop.assertLazy();
        assertInteger(2, stop.binding("result"));
        stop.assertInstruction("PrepareInlineClosureCall");
        System.out.println("PERF025_S3_DIRECT_SOURCE_CLOSURE_CALL_WITHOUT_ACTIVATION=YES");
    }

    /** A non-Closure receiver selects its own {@code call}: the exact generic path. */
    @Test
    void nonCanonicalInvocationKeepsGenericPath() throws Exception {
        Run run =
                runToLine(
                        """
                        run: () => {
                            target: { call: (value) => { value * 10 } }
                            Array(4).each((element) => {
                                result: target(element)
                                result
                            })
                        }
                        run()
                        """,
                        5);
        run.assertCompleted();
        Stop stop = run.single();
        assertTrue(stop.materialized(), "the generic invocation path takes the activation");
        assertTrue(stop.durable());
        assertInteger(40, stop.binding("result"));
        System.out.println("PERF025_S3_NONCANONICAL_CALL_FALLBACK_PRESERVED=YES");
    }

    @Test
    void thisStaysLazy() throws Exception {
        Run run =
                runToLine(
                        """
                        run: () => {
                            me: this
                            Array(1).each((element) => {
                                same: this === me
                                same
                            })
                        }
                        run()
                        """,
                        5);
        run.assertCompleted();
        Stop stop = run.single();
        stop.assertLazy();
        assertSame(ProtosBooleanValue.TRUE, stop.binding("same"));
        stop.assertInstruction("LoadInlineCallbackReceiver");
        System.out.println("PERF025_S3_THIS_WITHOUT_ACTIVATION=YES");
    }

    /** {@code context} remains a real observer: the callback is not frame-native. */
    @Test
    void contextRemainsAnObserver() throws Exception {
        Run run =
                runToLine(
                        """
                        run: () => {
                            me: this
                            Array(1).each((element) => {
                                current: context
                                same: this === me
                                same
                            })
                        }
                        run()
                        """,
                        6);
        run.assertCompleted();
        Stop stop = run.single();
        assertTrue(stop.materialized(), "context observes the callback activation");
        assertFalse(
                stop.instructions().stream()
                        .anyMatch(name -> name.contains("LoadInlineCallbackReceiver")),
                () -> "a context-observing callback is not carrier-native: "
                        + stop.instructions());
        System.out.println("PERF025_S3_CONTEXT_MATERIALIZES=YES");
    }

    @Test
    void memberReadStaysLazy() throws Exception {
        Run run =
                runToLine(
                        """
                        run: () => {
                            box: { value: 7 }
                            Array(1).each((element) => {
                                read: box.value
                                read
                            })
                        }
                        run()
                        """,
                        5);
        run.assertCompleted();
        Stop stop = run.single();
        stop.assertLazy();
        assertInteger(7, stop.binding("read"));
        stop.assertInstruction("ReadInlineMember");
        System.out.println("PERF025_S3_MEMBER_READ_WITHOUT_ACTIVATION=YES");
    }

    @Test
    void memberLookupFailureKeepsSlotNotFound() throws Exception {
        assertGuestTrue(
                """
                run: () => {
                    box: { value: 7 }
                    Error.handle(() => {
                        Array(1).each((element) => {
                            read: box.missing
                            read
                        })
                    }, (caught) => caught.parent() === SlotNotFound)
                }
                run()
                """);
        System.out.println("PERF025_S3_MEMBER_LOOKUP_ERROR_PRESERVED=YES");
    }

    @Test
    void multipleCreationStaysLazy() throws Exception {
        Run run =
                runToLine(
                        """
                        run: () => {
                            pair: Array(4, 5, 6)
                            Array(1).each((element) => {
                                (first, second): pair
                                first
                            })
                        }
                        run()
                        """,
                        5);
        run.assertCompleted();
        Stop stop = run.single();
        stop.assertLazy();
        assertInteger(4, stop.binding("first"));
        assertInteger(5, stop.binding("second"));
        stop.assertInstruction("ObserveInlineMultipleCreatePrefix");
        stop.assertInstruction("CreateInlineCurrentFrameLocal");
        System.out.println("PERF025_S3_MULTIPLE_CREATE_WITHOUT_ACTIVATION=YES");
    }

    @Test
    void multipleCreationErrorsArePreserved() throws Exception {
        assertGuestTrue(
                """
                run: () => {
                    Error.handle(() => {
                        Array(1).each((element) => {
                            (first, second): element
                            first
                        })
                    }, (caught) => caught.parent() === Error)
                }
                run()
                """);
        assertGuestTrue(
                """
                run: () => {
                    pair: Array(4)
                    Error.handle(() => {
                        Array(1).each((element) => {
                            (first, second): pair
                            first
                        })
                    }, (caught) => caught.parent() === Error)
                }
                run()
                """);
        System.out.println("PERF025_S3_MULTIPLE_CREATE_ERRORS_PRESERVED=YES");
    }

    private static void assertGuestTrue(String characters) throws Exception {
        ProtosExecutionOutcome outcome =
                ProtosTestExecutionSupport.execute(
                        "perf025-s3-error.protos",
                        characters,
                        new ProtosCoreBootstrap().bootstrap(CORE).newModuleActivation());
        assertEquals(
                ProtosExecutionOutcome.State.COMPLETED,
                outcome.state(),
                () -> "outcome=" + outcome.state() + ", error=" + outcome.error());
        assertSame(ProtosBooleanValue.TRUE, outcome.value());
    }

    private static void assertDistinctCarriers(List<Stop> stops) {
        for (int index = 1; index < stops.size(); index++) {
            assertTrue(
                    stops.get(index - 1).call() != stops.get(index).call(),
                    "each invocation has its own carrier");
        }
    }

    /*
     * Raw frame slots may retain an I091 primitive carrier; the binding's
     * guest value is what that carrier denotes.
     */
    private static void assertInteger(long expected, Object value) {
        assertEquals(Long.toString(expected), com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerDecimalText(com.guillermomolina.protos.runtime.ProtosNumericValueSupport
                                        .guestValue(value)));
    }

    /** The live inline callback region observed at one breakpoint hit. */
    private record Stop(
            PreparedInlineLiteralCall call,
            boolean materialized,
            boolean durable,
            Map<String, Object> bindings,
            List<String> instructions) {
        void assertLazy() {
            assertFalse(materialized, "callback activation must stay unmaterialized");
            assertFalse(durable, "no durable authority may exist without an observer");
        }

        Object binding(String name) {
            assertTrue(bindings.containsKey(name), () -> "missing block local " + name);
            return bindings.get(name);
        }

        void assertInstruction(String operation) {
            assertTrue(
                    instructions.stream().anyMatch(name -> name.contains(operation)),
                    () -> "missing " + operation + " in " + instructions);
        }
    }

    private record Run(ProtosExecutionOutcome outcome, List<Stop> stops) {
        void assertCompleted() {
            assertEquals(
                    ProtosExecutionOutcome.State.COMPLETED,
                    outcome.state(),
                    () -> "outcome=" + outcome.state() + ", error=" + outcome.error());
        }

        Object value() {
            return outcome.value();
        }

        Stop single() {
            assertEquals(1, stops.size(), "one breakpoint hit");
            return stops.get(0);
        }
    }

    /**
     * Executions of the trailing {@code run()} call per scenario. The Bytecode
     * DSL uncached interpreter (default threshold 16 calls/back-edges per
     * BytecodeNode) only runs each operation's generic specialization, which
     * by design materializes the callback activation; the specialized
     * consumers under test exist only in the cached tier. Earlier runs warm
     * {@code run}'s BytecodeNode past that threshold and only the final run's
     * stops are evaluated.
     */
    private static final int RUNS = 20;

    /**
     * Runs {@code characters}, whose last line must be {@code run()}, with
     * breakpoints on {@code lines}; that call is repeated {@link #RUNS} times
     * and only the final run's hits are recorded: at each, the innermost live
     * carrier and the region's block-local bindings, read from the raw frame.
     * Installs {@code bump(value)}, a native Closure answering
     * {@code value * 10}, reached by dynamic lookup.
     */
    private static Run runToLine(String characters, int... lines) throws Exception {
        assertTrue(characters.endsWith("run()\n"), "scenario must end with run()");
        Source source =
                Source.newBuilder(
                                ProtosLanguage.ID,
                                characters + "run()\n".repeat(RUNS - 1),
                                "perf025-s3.protos")
                        .uri(URI.create("memory:///perf025-s3.protos"))
                        .mimeType(ProtosLanguage.MIME_TYPE)
                        .build();
        ProtosActivation module = new ProtosCoreBootstrap().bootstrap(CORE).newModuleActivation();
        module.context().createLocalSlot(
                "bump",
                ProtosClosureValue.nativeClosure(
                        (activation, supplied) ->
                                new ProtosIntegerValue(
                                        Math.multiplyExact(
                                                assertInstanceOf(
                                                                ProtosIntegerValue.class,
                                                                supplied.get(0))
                                                        .longValue(),
                                                10L))));
        AtomicReference<Throwable> callbackFailure = new AtomicReference<>();
        List<Stop> stops = new ArrayList<>();
        ProtosExecutionOutcome outcome;

        try (ProtosPolyglotExecutionContext polyglot =
                ProtosPolyglotExecutionContext.open(
                        InputStream.nullInputStream(),
                        OutputStream.nullOutputStream(),
                        OutputStream.nullOutputStream())) {
            Instrument instrument =
                    polyglot.engineForTesting().getInstruments().get("debugger");
            Debugger debugger = instrument.lookup(Debugger.class);
            try (DebuggerSession session =
                    debugger.startSession(
                            event -> {
                                try {
                                    stops.add(stopOf(event.getTopStackFrame()));
                                } catch (Throwable failure) {
                                    callbackFailure.compareAndSet(null, failure);
                                } finally {
                                    event.prepareContinue();
                                }
                            })) {
                for (int line : lines) {
                    session.install(Breakpoint.newBuilder(source).lineIs(line).build());
                }
                outcome = polyglot.execute(source, module);
            }
        }

        if (callbackFailure.get() != null) {
            throw new AssertionError("debugger callback failed", callbackFailure.get());
        }
        assertEquals(0, stops.size() % RUNS, "every run must hit the same breakpoints");
        int finalRunStops = stops.size() / RUNS;
        return new Run(
                outcome, List.copyOf(stops.subList(stops.size() - finalRunStops, stops.size())));
    }

    private static Stop stopOf(DebugStackFrame top) {
        TagTreeNode node =
                assertInstanceOf(TagTreeNode.class, top.getRawNode(ProtosLanguage.class));
        Frame frame = top.getRawFrame(ProtosLanguage.class, FrameInstance.FrameAccess.READ_ONLY);
        BytecodeNode bytecode = node.getBytecodeNode();
        int bytecodeIndex = node.getEnterBytecodeIndex();
        Object[] names = bytecode.getLocalNames(bytecodeIndex);
        PreparedInlineLiteralCall call = null;
        Map<String, Object> bindings = new HashMap<>();
        for (int offset = 0; offset < names.length; offset++) {
            Object value = bytecode.getLocalValue(bytecodeIndex, frame, offset);
            String name = String.valueOf(names[offset]);
            if (CanonicalToBytecodeLowerer.INLINE_CALLBACK_CALL_LOCAL.equals(name)
                    && value instanceof PreparedInlineLiteralCall live) {
                call = live;
            } else if (name.startsWith(
                    CanonicalToBytecodeLowerer.INLINE_CALLBACK_BINDING_LOCAL_PREFIX)) {
                bindings.put(
                        name.substring(
                                CanonicalToBytecodeLowerer.INLINE_CALLBACK_BINDING_LOCAL_PREFIX
                                        .length()),
                        value);
            }
        }
        assertTrue(call != null, () -> "no live inline callback carrier at the breakpoint");
        if (call.frameBindingsTransferred()) {
            // Transferred bindings, and any established afterwards, live in the
            // durable authority installed on the materialized activation.
            ProtosLexicalBindingAuthority durable =
                    call.activation().currentLexicalBindingAuthorityForRuntime();
            bindings.replaceAll((name, value) -> durable.readBinding(name).orElse(null));
        }
        List<String> instructions =
                bytecode.getInstructionsAsList().stream().map(Instruction::getName).toList();
        return new Stop(
                call,
                call.isActivationMaterialized(),
                call.frameBindingsTransferred(),
                bindings,
                instructions);
    }
}
