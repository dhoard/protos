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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.guillermomolina.protos.execution.ProtosBytecodeRootNode.PreparedInlineLiteralCall;
import com.guillermomolina.protos.parser.ProtosParser;
import com.guillermomolina.protos.runtime.ProtosActivation;
import com.guillermomolina.protos.runtime.ProtosClosureValue;
import com.guillermomolina.protos.runtime.ProtosNullValue;
import com.guillermomolina.protos.runtime.ProtosObjectValue;
import com.guillermomolina.protos.semantic.Canonicalizer;
import com.guillermomolina.protos.semantic.ast.CanonicalSequence;
import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.TruffleLanguage.LanguageReference;
import com.oracle.truffle.api.bytecode.BytecodeFrame;
import com.oracle.truffle.api.bytecode.BytecodeNode;
import com.oracle.truffle.api.bytecode.ContinuationRootNode;
import com.oracle.truffle.api.bytecode.Instruction;
import com.oracle.truffle.api.bytecode.TagTreeNode;
import com.oracle.truffle.api.debug.Breakpoint;
import com.oracle.truffle.api.debug.DebugScope;
import com.oracle.truffle.api.debug.DebugStackFrame;
import com.oracle.truffle.api.debug.DebugValue;
import com.oracle.truffle.api.debug.Debugger;
import com.oracle.truffle.api.debug.DebuggerSession;
import com.oracle.truffle.api.frame.Frame;
import com.oracle.truffle.api.frame.FrameInstance;
import com.oracle.truffle.api.nodes.RootNode;
import com.oracle.truffle.api.source.Source;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Instrument;
import org.junit.jupiter.api.Test;

/**
 * PERF025 lazy inline callback activation (PLAT044 B′ Candidate, Slice 2)
 * focal evidence.
 *
 * <p>An admitted frame-native inline callback enters its region holding only
 * the invocation's {@link PreparedInlineLiteralCall} carrier. Its parameters
 * and current locals are bound, read and written through carrier-operand
 * operations; the fresh semantic activation is materialized, once, by the
 * first operation or tooling query that needs it, and that materialization
 * moves the frame-native bindings to a durable activation-owned authority
 * before the activation reaches its consumer, so no Context can observe, or
 * keep aliasing, the reused block locals.
 *
 * <p>Laziness at entry is proven structurally: {@code
 * MaterializeInlineCallbackActivation} and {@code LoadInlineCallbackActivation}
 * are the only guest operations that hand out the callback activation, and a
 * carrier operation materializes it only on an Error or fallback path, so a
 * region without them never materializes one on its ordinary path.
 * Runtime identity is observed through the live region's carrier local.
 */
final class ProtosPerf025LazyInlineCallbackActivationTest {
    private static final Path CORE = Path.of("protos", "lib", "core");

    private static final LanguageReference<ProtosLanguage> LANGUAGE_REF =
            LanguageReference.create(ProtosLanguage.class);

    private static final String PLAIN_LOAD = "LoadInlineCallbackActivation";
    private static final String MATERIALIZE = "MaterializeInlineCallbackActivation";

    @Test
    void frameNativeZeroParameterCallbackEntersWithoutActivation() throws Exception {
        /*
         * I101-A: in NORMAL this literal ifTrue site is the admitted I095
         * inline lowering; the B-prime frame-native callback region of its
         * ordinary send is the lowering of the DEBUG profile.
         */
        List<String> names =
                assertRegionWithoutActivation(
                        true,
                        """
                        run: () => {
                            probe()
                            true.ifTrue(() => {
                                local: 1
                                local = local
                                local
                            })
                        }
                        run()
                        """);
        assertContains(names, "CheckInlineClosureArgumentUpperBound");
        assertContains(names, "CreateInlineCurrentFrameLocal");
        assertContains(names, "ReadInlineFrameLocal");
        assertContains(names, "ResolveInlineFrameLocalWriteTarget");
        assertContains(names, "AssignInlineFrameLocal");
        System.out.println("PERF025_INLINE_CALLBACK_ENTRY_MATERIALIZES_ACTIVATION=NO");
        System.out.println(
                "PERF025_FRAME_NATIVE_CALLBACK_ENTRY_HAS_LOAD_INLINE_CALLBACK_ACTIVATION=NO");
        System.out.println("PERF025_FRAME_NATIVE_ZERO_PARAMETER_CALLBACK_STAYS_LAZY=YES");
        System.out.println("PERF025_FRAME_NATIVE_CURRENT_READ_WITHOUT_ACTIVATION=YES");
        System.out.println("PERF025_FRAME_NATIVE_CURRENT_WRITE_WITHOUT_ACTIVATION=YES");
        System.out.println("PERF025_FRAME_NATIVE_CREATE_WITHOUT_ACTIVATION=YES");
    }

    @Test
    void frameNativeOneParameterEachEntersWithoutActivation() throws Exception {
        List<String> names =
                assertRegionWithoutActivation(
                        """
                        run: () => {
                            probe()
                            last: 0
                            Array(4, 5).each((element) => {
                                copy: element
                                copy = element
                                copy
                            })
                        }
                        run()
                        """);
        assertContains(names, "LoadInlineClosureArgument");
        assertContains(names, "BindInlineClosureFrameParameter");
        System.out.println("PERF025_FRAME_NATIVE_ONE_PARAMETER_EACH_STAYS_LAZY=YES");
        System.out.println("PERF025_FRAME_NATIVE_PARAMETER_PATH_WITHOUT_ACTIVATION=YES");
    }

    @Test
    void frameNativeTwoParameterEachEntersWithoutActivation() throws Exception {
        List<String> names =
                assertRegionWithoutActivation(
                        """
                        run: () => {
                            probe()
                            m: Map()
                            m["a"] = 1
                            m["b"] = 2
                            m.each((key, value) => {
                                pair: value
                                pair = key
                                pair
                            })
                        }
                        run()
                        """);
        assertEquals(
                2,
                names.stream().filter(name -> name.contains("BindInlineClosureFrameParameter")).count(),
                () -> "both formals must bind from the carrier: " + names);
        System.out.println("PERF025_FRAME_NATIVE_TWO_PARAMETER_EACH_STAYS_LAZY=YES");
    }

    @Test
    void frameNativeEstablishmentOrdinalsAreInstructionConstants() throws Exception {
        List<Instruction> instructions =
                assertRegionInstructionsWithoutActivation(
                        """
                        run: () => {
                            probe()
                            pair: Array(4, 5, 6)
                            Array(1).each((element) => {
                                copy: element
                                (first, second): pair
                                first
                            })
                        }
                        run()
                        """);
        List<String> names = instructions.stream().map(Instruction::getName).toList();
        List<Integer> parameter = constantOrdinals(instructions, "BindInlineClosureFrameParameter");
        List<Integer> creations = constantOrdinals(instructions, "CreateInlineCurrentFrameLocal");
        assertEquals(1, parameter.size(), () -> "one formal: " + names);
        assertEquals(3, creations.size(), () -> "copy, then one creation per multiple target: " + names);
        assertEquals(
                4,
                java.util.stream.Stream.concat(parameter.stream(), creations.stream()).distinct().count(),
                "each establishment owns its own block-local ordinal");
        int copy = firstIndexOf(names, "CreateInlineCurrentFrameLocal");
        int observation = firstIndexOf(names, "ObserveInlineMultipleCreatePrefix");
        int firstTarget =
                copy + 1 + firstIndexOf(names.subList(copy + 1, names.size()), "CreateInlineCurrentFrameLocal");
        assertTrue(
                copy < observation && observation < firstTarget,
                () -> "the complete prefix is observed before the first multiple target: " + names);
        System.out.println("PERF030_I_INLINE_PARAMETER_CONSTANT_ORDINAL=YES");
        System.out.println("PERF030_I_INLINE_CREATION_CONSTANT_ORDINAL=YES");
        System.out.println("PERF030_I_INLINE_MULTIPLE_CREATE_RUNTIME_ORDINAL_ARRAY=NO");
    }

    /**
     * The first send materializes the activation; a second observer of the
     * same invocation receives the identical instance, and each invocation
     * has its own.
     */
    @Test
    void firstObserverMaterializesOncePerFreshInvocation() throws Exception {
        try (Context context = Context.newBuilder(ProtosLanguage.ID).build()) {
            context.initialize(ProtosLanguage.ID);
            context.enter();
            try {
                CarrierProbe probe = new CarrierProbe();
                lowerRoot(
                                """
                                run: () => {
                                    Array(1, 2).each((element) => {
                                        copy: element
                                        probe(copy)
                                        probe(copy)
                                    })
                                }
                                run()
                                """)
                        .getCallTarget()
                        .call(probe.module());

                assertEquals(4, probe.calls.size());
                ProtosSemanticBytecodeRootNode host = probe.host.get();
                host.getRootNodes().ensureComplete();
                List<String> names =
                        host.getBytecodeNode().getInstructionsAsList().stream()
                                .map(Instruction::getName)
                                .toList();
                assertContains(names, MATERIALIZE);
                assertFalse(
                        names.stream().anyMatch(name -> name.contains(PLAIN_LOAD)),
                        () -> "frame-native region must not use the plain entry load: " + names);
                for (Observation observation : probe.calls) {
                    assertTrue(
                            observation.materializedAtProbe(),
                            "the probe send is the first semantic observer");
                }
                assertSame(probe.calls.get(0).call(), probe.calls.get(1).call());
                assertSame(probe.calls.get(0).activation(), probe.calls.get(1).activation());
                assertSame(probe.calls.get(2).call(), probe.calls.get(3).call());
                assertSame(probe.calls.get(2).activation(), probe.calls.get(3).activation());
                assertNotSame(probe.calls.get(0).call(), probe.calls.get(2).call());
                assertNotSame(
                        probe.calls.get(0).activation(), probe.calls.get(2).activation());
            } finally {
                context.leave();
            }
        }
        System.out.println(
                "PERF025_FRAME_NATIVE_CALLBACK_HAS_MATERIALIZE_INLINE_CALLBACK_ACTIVATION=YES");
        System.out.println("PERF025_FIRST_SEMANTIC_OBSERVER_MATERIALIZES=YES");
        System.out.println("PERF025_MATERIALIZATION_AT_MOST_ONCE=YES");
        System.out.println("PERF025_FRESH_IDENTITY_PER_INVOCATION=YES");
        System.out.println("PERF025_PREPARED_INLINE_LITERAL_CALL_IS_IDENTITY_CARRIER=YES");
    }

    /**
     * The observing send is the invocation's first materialization and is
     * followed by no binding operation: the Context it observes already holds
     * the formal and the local, and keeps them after the next invocation has
     * reused the region's block locals.
     */
    @Test
    void firstMaterializationMakesBindingsDurableBeforeTheObserverRuns() throws Exception {
        try (Context context = Context.newBuilder(ProtosLanguage.ID).build()) {
            context.initialize(ProtosLanguage.ID);
            context.enter();
            try {
                CarrierProbe probe = new CarrierProbe();
                lowerRoot(
                                """
                                run: () => {
                                    Array(10, 20).each((element) => {
                                        copy: element
                                        observe()
                                    })
                                }
                                run()
                                """)
                        .getCallTarget()
                        .call(probe.module());

                assertEquals(2, probe.calls.size());
                for (Observation observation : probe.calls) {
                    assertTrue(observation.materializedAtProbe());
                    assertTrue(
                            observation.durableAtProbe(),
                            "bindings must be durable before the observer runs");
                }
                assertBindings(probe.observedAtProbe.get(0), 10);
                assertBindings(probe.observedAtProbe.get(1), 20);
                assertNotSame(probe.observed.get(0), probe.observed.get(1));
                assertBindings(probe.observed.get(0).localSlotsSnapshot(), 10);
                assertBindings(probe.observed.get(1).localSlotsSnapshot(), 20);
            } finally {
                context.leave();
            }
        }
        System.out.println("PERF025_FIRST_OBSERVER_MATERIALIZES=YES");
        System.out.println("PERF025_DURABLE_TRANSFER_BEFORE_OBSERVER_RETURN=YES");
        System.out.println("PERF025_CONTEXT_OBSERVER_SEES_DURABLE_BINDINGS=YES");
        System.out.println("PERF025_POST_MATERIALIZATION_BINDING_OPERATION_REQUIRED=NO");
        System.out.println("PERF025_BLOCK_LOCAL_ALIAS_AFTER_ESCAPE=NO");
        System.out.println("PERF025_MATERIALIZATION_AT_MOST_ONCE=YES");
    }

    private static void assertBindings(java.util.Map<String, Object> bindings, long value) {
        assertEquals(List.of("element", "copy"), List.copyOf(bindings.keySet()));
        assertInteger(value, bindings.get("element"));
        assertInteger(value, bindings.get("copy"));
    }

    /**
     * After the durable transfer the guest keeps using its bindings through
     * the activation: writes and creations land in the observed Context, the
     * retained Context keeps exactly its own invocation's bindings after the
     * region's block locals are reused, and D179 removal from that Context
     * makes the binding ABSENT for the guest (captured fallback).
     */
    @Test
    void observedContextReceivesDurableBindingsWithoutAliasingBlockLocals()
            throws Exception {
        try (Context context = Context.newBuilder(ProtosLanguage.ID).build()) {
            context.initialize(ProtosLanguage.ID);
            context.enter();
            try {
                CarrierProbe probe = new CarrierProbe();
                Object result =
                        lowerRoot(
                                        """
                                        run: () => {
                                            copy: 99
                                            seen: 0
                                            Array(10, 20).each((element) => {
                                                copy: element
                                                observe()
                                                copy = copy + 1
                                                later: copy
                                                forget()
                                                seen = seen * 1000 + copy
                                            })
                                            seen
                                        }
                                        run()
                                        """)
                                .getCallTarget()
                                .call(probe.module());

                assertInteger(99099, result);
                assertEquals(2, probe.observed.size());
                ProtosObjectValue first = probe.observed.get(0);
                ProtosObjectValue second = probe.observed.get(1);
                assertNotSame(first, second);
                assertInteger(10, first.readLocalSlot("element").orElseThrow());
                assertInteger(11, first.readLocalSlot("later").orElseThrow());
                assertFalse(first.hasLocalSlot("copy"), "D179 removal stays ABSENT");
                assertInteger(20, second.readLocalSlot("element").orElseThrow());
                assertInteger(21, second.readLocalSlot("later").orElseThrow());
                for (Observation observation : probe.calls) {
                    assertTrue(observation.durableAtProbe());
                }
                assertInteger(10, probe.observedAtProbe.get(0).get("copy"));
                assertInteger(20, probe.observedAtProbe.get(1).get("copy"));
            } finally {
                context.leave();
            }
        }
        System.out.println("PERF025_DURABLE_AUTHORITY_TRANSITION=YES");
        System.out.println("PERF025_DURABLE_AUTHORITY_DOES_NOT_ALIAS_REUSED_BLOCK_LOCALS=YES");
        System.out.println("PERF025_D179_PRESENT_ABSENT_PRESERVED=YES");
    }

    @Test
    void suspensionResumesWithTheSameMaterializedActivation() throws Exception {
        ProtosActivation module = new ProtosCoreBootstrap().bootstrap(CORE).newModuleActivation();
        CarrierProbe probe = new CarrierProbe();
        probe.install(module);
        ProtosExecutionOutcome outcome =
                ProtosTestExecutionSupport.execute(
                        "perf025-lazy-inline-suspension.protos",
                        """
                        run: () => {
                            step: (() => { 1 }).future()
                            acc: 0
                            visits: 0
                            Array(1, 2, 3).each((element) => {
                                local: element
                                visits = visits + 1
                                probe(local)
                                local = local * step.value()
                                probe(local)
                                acc = acc * 10 + local
                            })
                            acc * 10 + visits
                        }
                        run()
                        """,
                        module);
        assertEquals(
                ProtosExecutionOutcome.State.COMPLETED,
                outcome.state(),
                () -> "outcome=" + outcome.state() + ", error=" + outcome.error());
        assertInteger(1233, outcome.value());
        assertEquals(6, probe.calls.size());
        for (int visit = 0; visit < 3; visit++) {
            Observation beforeSuspension = probe.calls.get(visit * 2);
            Observation afterResumption = probe.calls.get(visit * 2 + 1);
            assertSame(beforeSuspension.call(), afterResumption.call());
            assertSame(beforeSuspension.activation(), afterResumption.activation());
        }
        System.out.println("PERF025_SUSPEND_RESUME_DOES_NOT_REMATERIALIZE=YES");
    }

    @Test
    void nonLocalReturnFromFrameNativeCallbackIsPreserved() throws Exception {
        ProtosExecutionOutcome outcome =
                ProtosTestExecutionSupport.execute(
                        "perf025-lazy-inline-nlr.protos",
                        """
                        run: () => {
                            visits: 0
                            Array(1, 2, 3).each((element) => {
                                local: element
                                visits = visits + 1
                                ^local * 10 + visits
                            })
                            0
                        }
                        run()
                        """,
                        new ProtosCoreBootstrap().bootstrap(CORE).newModuleActivation());
        assertEquals(
                ProtosExecutionOutcome.State.COMPLETED,
                outcome.state(),
                () -> "outcome=" + outcome.state() + ", error=" + outcome.error());
        assertInteger(11, outcome.value());
        System.out.println("PERF025_NLR_SEMANTICS_PRESERVED=YES");
    }

    /**
     * Runtime evidence for an invocation without observers. Suspended on the
     * callback's last statement, the raw frame (read without requesting a
     * debugger scope, which would itself be an observer) shows each
     * invocation's live carrier still unmaterialized and without a durable
     * authority, while its bindings are PRESENT in the block locals.
     */
    @Test
    void unobservedInvocationNeverMaterializesNorCreatesDurableAuthority()
            throws Exception {
        Source source =
                Source.newBuilder(
                                ProtosLanguage.ID,
                                """
                                run: () => {
                                    total: 0
                                    Array(5, 6).each((element) => {
                                        inner: element
                                        inner = inner
                                        inner
                                    })
                                    total
                                }
                                run()
                                """,
                                "perf025-lazy-inline-unobserved.protos")
                        .uri(URI.create("memory:///perf025-lazy-inline-unobserved.protos"))
                        .mimeType(ProtosLanguage.MIME_TYPE)
                        .build();
        ProtosActivation module = new ProtosCoreBootstrap().bootstrap(CORE).newModuleActivation();
        AtomicReference<Throwable> callbackFailure = new AtomicReference<>();
        List<PreparedInlineLiteralCall> carriers = new ArrayList<>();
        List<Boolean> materialized = new ArrayList<>();
        List<Boolean> durable = new ArrayList<>();
        List<Object> inners = new ArrayList<>();

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
                                    DebugStackFrame top = event.getTopStackFrame();
                                    TagTreeNode node =
                                            assertInstanceOf(
                                                    TagTreeNode.class,
                                                    top.getRawNode(ProtosLanguage.class));
                                    Frame frame =
                                            top.getRawFrame(
                                                    ProtosLanguage.class,
                                                    FrameInstance.FrameAccess.READ_ONLY);
                                    BytecodeNode bytecode = node.getBytecodeNode();
                                    int bytecodeIndex = node.getEnterBytecodeIndex();
                                    Object[] names = bytecode.getLocalNames(bytecodeIndex);
                                    for (int offset = 0; offset < names.length; offset++) {
                                        Object value =
                                                bytecode.getLocalValue(bytecodeIndex, frame, offset);
                                        if (CanonicalToBytecodeLowerer.INLINE_CALLBACK_CALL_LOCAL
                                                        .equals(names[offset])
                                                && value instanceof PreparedInlineLiteralCall call) {
                                            carriers.add(call);
                                            materialized.add(call.isActivationMaterialized());
                                            durable.add(call.frameBindingsTransferred());
                                        } else if ((CanonicalToBytecodeLowerer
                                                                .INLINE_CALLBACK_BINDING_LOCAL_PREFIX
                                                        + "inner")
                                                .equals(names[offset])) {
                                            inners.add(value);
                                        }
                                    }
                                } catch (Throwable failure) {
                                    callbackFailure.compareAndSet(null, failure);
                                } finally {
                                    event.prepareContinue();
                                }
                            })) {
                session.install(Breakpoint.newBuilder(source).lineIs(6).build());
                ProtosExecutionOutcome outcome = polyglot.execute(source, module);
                assertEquals(ProtosExecutionOutcome.State.COMPLETED, outcome.state());
                assertInteger(0, outcome.value());
            }
        }

        if (callbackFailure.get() != null) {
            throw new AssertionError("debugger callback failed", callbackFailure.get());
        }
        assertEquals(2, carriers.size(), "one suspension per invocation");
        assertNotSame(carriers.get(0), carriers.get(1));
        assertEquals(List.of(false, false), materialized);
        assertEquals(List.of(false, false), durable);
        assertEquals(2, inners.size());
        assertInteger(5, inners.get(0));
        assertInteger(6, inners.get(1));
        System.out.println("PERF025_UNOBSERVED_INVOCATION_ACTIVATION_MATERIALIZED=NO");
        System.out.println("PERF025_UNOBSERVED_INVOCATION_DURABLE_AUTHORITY_CREATED=NO");
    }

    /**
     * Suspended on a statement no guest operation has needed the activation
     * for yet, the debugger materializes it on demand, through the same
     * durable transfer, and sees the formal and the callback-owned local.
     */
    @Test
    void debuggerMaterializesOnDemandAndSeesParameterAndLocal() throws Exception {
        Source source =
                Source.newBuilder(
                                ProtosLanguage.ID,
                                """
                                run: () => {
                                    total: 0
                                    Array(5, 6).each((element) => {
                                        inner: element
                                        inner = inner
                                        total = total + inner
                                    })
                                    total
                                }
                                run()
                                """,
                                "perf025-lazy-inline-debugger.protos")
                        .uri(URI.create("memory:///perf025-lazy-inline-debugger.protos"))
                        .mimeType(ProtosLanguage.MIME_TYPE)
                        .build();
        ProtosActivation module = new ProtosCoreBootstrap().bootstrap(CORE).newModuleActivation();
        AtomicReference<Throwable> callbackFailure = new AtomicReference<>();
        List<String> elements = new ArrayList<>();
        List<String> inners = new ArrayList<>();

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
                                    DebugScope scope = event.getTopStackFrame().getScope();
                                    DebugValue element = scope.getDeclaredValue("element");
                                    DebugValue inner = scope.getDeclaredValue("inner");
                                    assertNotNull(element, "callback formal must be visible");
                                    assertNotNull(inner, "callback-owned local must be visible");
                                    elements.add(element.toDisplayString());
                                    inners.add(inner.toDisplayString());
                                } catch (Throwable failure) {
                                    callbackFailure.compareAndSet(null, failure);
                                } finally {
                                    event.prepareContinue();
                                }
                            })) {
                session.install(Breakpoint.newBuilder(source).lineIs(5).build());
                ProtosExecutionOutcome outcome = polyglot.execute(source, module);
                assertEquals(ProtosExecutionOutcome.State.COMPLETED, outcome.state());
                assertInteger(11, outcome.value());
            }
        }

        if (callbackFailure.get() != null) {
            throw new AssertionError("debugger callback failed", callbackFailure.get());
        }
        assertEquals(List.of("5", "6"), elements);
        assertEquals(List.of("5", "6"), inners);
        System.out.println("PERF025_DEBUGGER_MATERIALIZES_ON_DEMAND=YES");
        System.out.println("PERF025_DEBUGGER_SEES_PARAMETER_AND_LOCAL=YES");
    }

    /**
     * Lowers and runs {@code characters} (whose {@code run} calls {@code
     * probe()} before its single inline site) and returns the instruction
     * names of {@code run}, asserting that no operation materializes the
     * callback activation.
     */
    private static List<String> assertRegionWithoutActivation(String characters)
            throws Exception {
        return assertRegionWithoutActivation(false, characters);
    }

    private static List<String> assertRegionWithoutActivation(boolean debug, String characters)
            throws Exception {
        return assertRegionInstructionsWithoutActivation(debug, characters).stream()
                .map(Instruction::getName)
                .toList();
    }

    private static List<Instruction> assertRegionInstructionsWithoutActivation(String characters)
            throws Exception {
        return assertRegionInstructionsWithoutActivation(false, characters);
    }

    private static List<Instruction> assertRegionInstructionsWithoutActivation(
            boolean debug, String characters) throws Exception {
        Context.Builder builder = Context.newBuilder(ProtosLanguage.ID);
        if (debug) {
            builder.allowExperimentalOptions(true).option("protos.Debug", "true");
        }
        try (Context context = builder.build()) {
            context.initialize(ProtosLanguage.ID);
            context.enter();
            try {
                CarrierProbe probe = new CarrierProbe();
                lowerRoot(characters).getCallTarget().call(probe.module());
                ProtosSemanticBytecodeRootNode host = probe.host.get();
                assertNotNull(host, "probe must observe the run root");
                host.getRootNodes().ensureComplete();
                List<Instruction> instructions = host.getBytecodeNode().getInstructionsAsList();
                List<String> names = instructions.stream().map(Instruction::getName).toList();
                assertContains(names, "CheckInlineClosureArgumentUpperBound");
                assertFalse(
                        names.stream().anyMatch(name -> name.contains(PLAIN_LOAD)),
                        () -> "frame-native region must not use the plain entry load: " + names);
                assertFalse(
                        names.stream().anyMatch(name -> name.contains(MATERIALIZE)),
                        () -> "a region without observers needs no materialization point: " + names);
                return instructions;
            } finally {
                context.leave();
            }
        }
    }

    /** The immediate constant {@code ordinal} of every {@code operation} instruction, in order. */
    private static List<Integer> constantOrdinals(List<Instruction> instructions, String operation) {
        return instructions.stream()
                .filter(instruction -> instruction.getName().contains(operation))
                .map(instruction -> {
                    Instruction.Argument ordinal =
                            instruction.getArguments().stream()
                                    .filter(argument -> argument.getName().equals("ordinal"))
                                    .findFirst()
                                    .orElseThrow(() -> new AssertionError(
                                            "no constant ordinal operand of " + operation));
                    return switch (ordinal.getKind()) {
                        case CONSTANT -> (Integer) ordinal.asConstant();
                        case INTEGER -> ordinal.asInteger();
                        default -> throw new AssertionError("ordinal is not a constant: " + ordinal);
                    };
                })
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

    private static void assertContains(List<String> names, String operation) {
        assertTrue(
                names.stream().anyMatch(name -> name.contains(operation)),
                () -> "missing " + operation + " in " + names);
    }

    private static ProtosSemanticBytecodeRootNode lowerRoot(String characters) {
        Source source =
                Source.newBuilder(ProtosLanguage.ID, characters, "perf025-lazy-inline.protos")
                        .build();
        CanonicalSequence sequence =
                (CanonicalSequence)
                        new Canonicalizer()
                                .canonicalize(new ProtosParser(characters).parseProgram());
        // I101-A: lowered under the immutable profile of the entered Context.
        return new CanonicalToBytecodeLowerer(
                        LANGUAGE_REF.get(null),
                        source,
                        ProtosLanguageContext.current().executionMode())
                .lowerRoot(sequence);
    }

    private static void assertInteger(long expected, Object value) {
        assertEquals(Long.toString(expected), com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerDecimalText(value));
    }

    /** One probe call made from inside a live inline callback region. */
    private record Observation(
            PreparedInlineLiteralCall call,
            boolean materializedAtProbe,
            boolean durableAtProbe,
            ProtosActivation activation) {}

    /**
     * Native bindings that inspect the innermost live inline callback region
     * through its carrier local, exactly as tooling locates it.
     *
     * <ul>
     *   <li>{@code probe(...)} records the semantic host root and, inside a
     *       region, the carrier and its activation;</li>
     *   <li>{@code observe()} additionally makes the callback's Context
     *       observable, out of band, and records its bindings at that
     *       instant;</li>
     *   <li>{@code forget()} removes {@code copy} from that observed Context.</li>
     * </ul>
     */
    private static final class CarrierProbe {
        final AtomicReference<ProtosSemanticBytecodeRootNode> host = new AtomicReference<>();
        final List<Observation> calls = new ArrayList<>();
        final List<ProtosObjectValue> observed = new ArrayList<>();
        final List<java.util.Map<String, Object>> observedAtProbe = new ArrayList<>();

        ProtosActivation module() throws Exception {
            ProtosActivation module =
                    new ProtosCoreBootstrap().bootstrap(CORE).newModuleActivation();
            install(module);
            return module;
        }

        void install(ProtosActivation module) {
            module.context().createLocalSlot(
                    "probe",
                    ProtosClosureValue.nativeClosure(
                            (activation, supplied) -> {
                                record();
                                return ProtosNullValue.INSTANCE;
                            }));
            module.context().createLocalSlot(
                    "observe",
                    ProtosClosureValue.nativeClosure(
                            (activation, supplied) -> {
                                Observation observation = record();
                                ProtosObjectValue observedContext =
                                        observation.activation().context();
                                observed.add(observedContext);
                                observedAtProbe.add(observedContext.localSlotsSnapshot());
                                return ProtosNullValue.INSTANCE;
                            }));
            module.context().createLocalSlot(
                    "forget",
                    ProtosClosureValue.nativeClosure(
                            (activation, supplied) -> {
                                observed.get(observed.size() - 1).removeLocalSlot("copy");
                                return ProtosNullValue.INSTANCE;
                            }));
        }

        private Observation record() {
            AtomicReference<PreparedInlineLiteralCall> found = new AtomicReference<>();
            Truffle.getRuntime()
                    .iterateFrames(
                            frame -> {
                                RootNode root =
                                        ((RootCallTarget) frame.getCallTarget()).getRootNode();
                                Object source =
                                        root instanceof ContinuationRootNode continuation
                                                ? continuation.getSourceRootNode()
                                                : root;
                                if (!(source instanceof ProtosSemanticBytecodeRootNode semantic)) {
                                    return null;
                                }
                                host.compareAndSet(null, semantic);
                                found.set(liveCarrier(frame, semantic));
                                return semantic;
                            });
            PreparedInlineLiteralCall call = found.get();
            if (call == null) {
                return null;
            }
            boolean materialized = call.isActivationMaterialized();
            boolean durable = call.frameBindingsTransferred();
            Observation observation =
                    new Observation(call, materialized, durable, call.activation());
            calls.add(observation);
            return observation;
        }

        /**
         * The carrier live in {@code frame}: read at the frame's own location
         * when Truffle can resolve it, otherwise at the index of the region's
         * entry check (each probed root here holds exactly one region).
         */
        private static PreparedInlineLiteralCall liveCarrier(
                FrameInstance frame,
                ProtosSemanticBytecodeRootNode semantic) {
            BytecodeFrame located = BytecodeFrame.get(frame, FrameInstance.FrameAccess.READ_ONLY);
            if (located != null) {
                Object[] names = located.getLocalNames();
                for (int offset = names.length - 1; offset >= 0; offset--) {
                    if (CanonicalToBytecodeLowerer.INLINE_CALLBACK_CALL_LOCAL.equals(names[offset])
                            && located.getLocalValue(offset)
                                    instanceof PreparedInlineLiteralCall call) {
                        return call;
                    }
                }
                return null;
            }
            BytecodeNode bytecode = semantic.getBytecodeNode();
            Frame values = sourceFrame(frame.getFrame(FrameInstance.FrameAccess.READ_ONLY), semantic);
            if (values == null) {
                return null;
            }
            for (Instruction instruction : bytecode.getInstructionsAsList()) {
                if (!instruction.getName().contains("CheckInlineClosureArgumentUpperBound")) {
                    continue;
                }
                int bytecodeIndex = instruction.getBytecodeIndex();
                Object[] names = bytecode.getLocalNames(bytecodeIndex);
                for (int offset = names.length - 1; offset >= 0; offset--) {
                    if (CanonicalToBytecodeLowerer.INLINE_CALLBACK_CALL_LOCAL.equals(names[offset])
                            && bytecode.getLocalValue(bytecodeIndex, values, offset)
                                    instanceof PreparedInlineLiteralCall call) {
                        return call;
                    }
                }
            }
            return null;
        }

        /**
         * The source root's own frame: the frame itself, or, for a resumed
         * continuation, the materialized source frame it carries as an
         * argument.
         */
        private static Frame sourceFrame(Frame frame, ProtosSemanticBytecodeRootNode semantic) {
            if (frame.getFrameDescriptor() == semantic.getFrameDescriptor()) {
                return frame;
            }
            for (Object argument : frame.getArguments()) {
                if (argument instanceof Frame candidate
                        && candidate.getFrameDescriptor() == semantic.getFrameDescriptor()) {
                    return candidate;
                }
            }
            return null;
        }
    }
}
