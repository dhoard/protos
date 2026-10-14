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
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.guillermomolina.protos.parser.ProtosParser;
import com.guillermomolina.protos.runtime.ProtosActivation;
import com.guillermomolina.protos.runtime.ProtosArrayValue;
import com.guillermomolina.protos.runtime.ProtosBooleanValue;
import com.guillermomolina.protos.runtime.ProtosBytesValue;
import com.guillermomolina.protos.runtime.ProtosClosureValue;
import com.guillermomolina.protos.runtime.ProtosNullValue;
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
 * PERF026-D1 (PLAT044 B′) focal evidence: a standard {@code Array.each} or
 * {@code Bytes.each} whose sole argument is an eligible immediate
 * one-required-parameter literal is sequenced in its semantic source root,
 * and each fresh per-element activation runs inline with its own RootTag,
 * binding the formal from that activation; every non-admitted call keeps the
 * structured-dispatch each (or its own ordinary dispatch) and physical
 * callback roots.
 *
 * <p>Eligible sites are placed inside a Closure ({@code run}) because literals
 * at module top level own a fresh return home, a shape D1 keeps on the
 * structured path.
 */
final class ProtosPerf026D1IndexedEachInlineCallbackTest {
    private static final Path CORE = Path.of("protos", "lib", "core");

    private static final LanguageReference<ProtosLanguage> LANGUAGE_REF =
            LanguageReference.create(ProtosLanguage.class);

    @Test
    void eligibleArrayEachRunsAsLocalLoopWithExactElements() throws Exception {
        try (Context context = Context.newBuilder(ProtosLanguage.ID).build()) {
            context.initialize(ProtosLanguage.ID);
            context.enter();
            try {
                Probe probe = new Probe();
                ProtosSemanticBytecodeRootNode root =
                        lowerRoot(
                                """
                                run: () => {
                                    first: {}
                                    second: {}
                                    Array(first, second).each((element) => {
                                        probe(element)
                                        element
                                    })
                                }
                                run()
                                """);

                ProtosArrayValue array =
                        assertInstanceOf(
                                ProtosArrayValue.class,
                                root.getCallTarget().call(probe.module()));

                List<Object> elements = array.indexedSnapshot();
                assertEquals(2, probe.arguments.size());
                assertSame(elements.get(0), probe.arguments.get(0));
                assertSame(elements.get(1), probe.arguments.get(1));
                assertNotSame(elements.get(0), elements.get(1));
                assertInlineTopology(root, probe, 2);
            } finally {
                context.leave();
            }
        }
        System.out.println("PERF026_D1_ARRAY_EACH_LOCAL_LOOP=YES");
        System.out.println("PERF026_D1_ARRAY_HELPER_ROOT_REMOVED=YES");
        System.out.println("PERF026_D1_ARRAY_CALLBACK_ROOT_REMOVED=YES");
        System.out.println("PERF026_D1_ARRAY_EXACT_ELEMENT_ARGUMENT=YES");
    }

    @Test
    void eligibleBytesEachRunsAsLocalLoopWithSemanticIntegerOctets() throws Exception {
        try (Context context = Context.newBuilder(ProtosLanguage.ID).build()) {
            context.initialize(ProtosLanguage.ID);
            context.enter();
            try {
                Probe probe = new Probe();
                ProtosSemanticBytecodeRootNode root =
                        lowerRoot(
                                """
                                run: () => {
                                    Encoding.UTF8.encode("ab").each((octet) => {
                                        probe(octet)
                                        octet
                                    })
                                }
                                run()
                                """);

                assertInstanceOf(
                        ProtosBytesValue.class,
                        root.getCallTarget().call(probe.module()));

                assertEquals(2, probe.arguments.size());
                assertInteger(97, probe.arguments.get(0));
                assertInteger(98, probe.arguments.get(1));
                assertInlineTopology(root, probe, 2);
            } finally {
                context.leave();
            }
        }
        System.out.println("PERF026_D1_BYTES_EACH_LOCAL_LOOP=YES");
        System.out.println("PERF026_D1_BYTES_HELPER_ROOT_REMOVED=YES");
        System.out.println("PERF026_D1_BYTES_CALLBACK_ROOT_REMOVED=YES");
        System.out.println("PERF026_D1_BYTES_SEMANTIC_INTEGER_OCTET=YES");
    }

    @Test
    void inlineArrayEachKeepsSnapshotOrderResultIdentityAndFreshActivations()
            throws Exception {
        assertInlineTrue(
                """
                run: () => {
                    a: Array(1, 2, 3)
                    acc: 0
                    result: a.each((element) => {
                        probe(element)
                        a.atPut(1, 9)
                        acc = acc * 10 + element
                    })
                    (result === a) && (acc == 123) && (a.at(1) == 9)
                }
                run()
                """,
                3);
        assertInlineTrue(
                """
                run: () => {
                    outer: context
                    previous: null
                    current: null
                    Array(1, 2).each((element) => {
                        probe(element)
                        previous = current
                        current = context
                    })
                    (previous !== current) && (current !== outer) && (previous !== null)
                }
                run()
                """,
                2);
        System.out.println("PERF026_D1_ARRAY_SNAPSHOT_PRESERVED=YES");
        System.out.println("PERF026_D1_ARRAY_ASCENDING_ORDER_PRESERVED=YES");
        System.out.println("PERF026_D1_ARRAY_CALLBACK_RESULT_IGNORED=YES");
        System.out.println("PERF026_D1_ARRAY_RECEIVER_RESULT_IDENTITY=YES");
        System.out.println("PERF026_D1_CAPTURE_BY_REFERENCE_PRESERVED=YES");
        System.out.println("PERF026_D1_FRESH_ACTIVATION_PER_CALLBACK=YES");
    }

    @Test
    void inlineBytesEachKeepsSnapshotOrderResultIdentityAndFreshActivations()
            throws Exception {
        assertInlineTrue(
                """
                run: () => {
                    b: Encoding.UTF8.encode("abc")
                    acc: 0
                    result: b.each((octet) => {
                        probe(octet)
                        b.atPut(1, 0)
                        b.add(1)
                        acc = acc * 1000 + octet
                    })
                    (result === b) && (acc == 97098099) && (b.size() == 6)
                }
                run()
                """,
                3);
        assertInlineTrue(
                """
                run: () => {
                    outer: context
                    previous: null
                    current: null
                    Encoding.UTF8.encode("ab").each((octet) => {
                        probe(octet)
                        previous = current
                        current = context
                    })
                    (previous !== current) && (current !== outer) && (previous !== null)
                }
                run()
                """,
                2);
        System.out.println("PERF026_D1_BYTES_SNAPSHOT_PRESERVED=YES");
        System.out.println("PERF026_D1_BYTES_ASCENDING_ORDER_PRESERVED=YES");
        System.out.println("PERF026_D1_BYTES_CALLBACK_RESULT_IGNORED=YES");
        System.out.println("PERF026_D1_BYTES_RECEIVER_RESULT_IDENTITY=YES");
    }

    @Test
    void inlineEachNonLocalReturnAndErrorStopLaterVisits() throws Exception {
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
                                            Array(1, 2, 3).each((element) => {
                                                probe(element)
                                                ^element + 40
                                            })
                                            100
                                        }
                                        run() + 1
                                        """)
                                .getCallTarget()
                                .call(nonLocalReturn.module()));
                assertEquals(1, nonLocalReturn.calls.get(), "NLR stops later visits");
                assertEquals(List.of(2), nonLocalReturn.depths());

                Probe bytesNonLocalReturn = new Probe();
                assertInteger(
                        98,
                        lowerRoot(
                                        """
                                        run: () => {
                                            Encoding.UTF8.encode("abc").each((octet) => {
                                                probe(octet)
                                                ^octet + 1
                                            })
                                            100
                                        }
                                        run()
                                        """)
                                .getCallTarget()
                                .call(bytesNonLocalReturn.module()));
                assertEquals(1, bytesNonLocalReturn.calls.get(), "NLR stops later visits");
                assertEquals(List.of(2), bytesNonLocalReturn.depths());

                Probe error = new Probe();
                assertInteger(
                        42,
                        lowerRoot(
                                        """
                                        run: () => {
                                            Array(1, 2, 3).each((element) => {
                                                probe(element)
                                                Error().signal()
                                            })
                                        }
                                        Error.handle(() => { run() }, (caught) => { 42 })
                                        """)
                                .getCallTarget()
                                .call(error.module()));
                assertEquals(1, error.calls.get(), "an Error stops later visits");

                Probe bytesError = new Probe();
                assertInteger(
                        42,
                        lowerRoot(
                                        """
                                        run: () => {
                                            Encoding.UTF8.encode("abc").each((octet) => {
                                                probe(octet)
                                                Error().signal()
                                            })
                                        }
                                        Error.handle(() => { run() }, (caught) => { 42 })
                                        """)
                                .getCallTarget()
                                .call(bytesError.module()));
                assertEquals(1, bytesError.calls.get(), "an Error stops later visits");
            } finally {
                context.leave();
            }
        }
        System.out.println("PERF026_D1_NLR_PRESERVED=YES");
        System.out.println("PERF026_D1_ERROR_PROPAGATION_PRESERVED=YES");
    }

    /**
     * Every callback suspends; a replayed completed prefix would add an
     * earlier element (and visit) again, changing the result.
     */
    @Test
    void callbackSuspensionResumesInsideTheLocalLoopWithoutReplay() throws Exception {
        assertCompletedWith(
                1233,
                execute(
                        "perf026-d1-array-suspension.protos",
                        """
                        run: () => {
                            step: (() => { 1 }).future()
                            acc: 0
                            visits: 0
                            Array(1, 2, 3).each((element) => {
                                visits = visits + 1
                                acc = acc * 10 + element * step.value()
                            })
                            acc * 10 + visits
                        }
                        run()
                        """));
        assertCompletedWith(
                970980993,
                execute(
                        "perf026-d1-bytes-suspension.protos",
                        """
                        run: () => {
                            step: (() => { 1 }).future()
                            acc: 0
                            visits: 0
                            Encoding.UTF8.encode("abc").each((octet) => {
                                visits = visits + 1
                                acc = acc * 1000 + octet * step.value()
                            })
                            acc * 10 + visits
                        }
                        run()
                        """));
        System.out.println("PERF026_D1_SUSPENSION_RESUMPTION_PRESERVED=YES");
        System.out.println("PERF026_D1_COMPLETED_PREFIX_REPLAY=NO");
    }

    @Test
    void dynamicCallbackRetainsStructuredEach() throws Exception {
        assertStructuredEachPreserved(
                """
                run: () => {
                    block: (element) => { probe() }
                    Array(1).each(block)
                }
                run()
                """);
        assertStructuredEachPreserved(
                """
                run: () => {
                    block: (octet) => { probe() }
                    Encoding.UTF8.encode("a").each(block)
                }
                run()
                """);
        System.out.println("PERF026_D1_DYNAMIC_CALLBACK_FALLBACK=YES");
    }

    @Test
    void unsupportedLiteralShapesRetainStructuredEach() throws Exception {
        assertStructuredEachPreserved(
                """
                run: () => {
                    Array(1).each((element) => {
                        probe()
                        step: () => { 1 }
                        step.call()
                    })
                }
                run()
                """);
        assertStructuredEachPreserved(
                """
                run: () => {
                    Array(1).each((element = 7) => { probe() })
                }
                run()
                """);
        assertStructuredEachPreserved(
                """
                run: () => {
                    Encoding.UTF8.encode("a").each((...octets) => { probe() })
                }
                run()
                """);
        System.out.println("PERF026_D1_NESTED_CLOSURE_LITERAL_FALLBACK=YES");
        System.out.println("PERF026_D1_DEFAULT_REST_PARAMETER_FALLBACK=YES");
    }

    /** A zero- or two-parameter literal keeps its ordinary arity failure. */
    @Test
    void mismatchedArityLiteralsKeepOrdinaryArityErrors() throws Exception {
        try (Context context = Context.newBuilder(ProtosLanguage.ID).build()) {
            context.initialize(ProtosLanguage.ID);
            context.enter();
            try {
                for (String callback :
                        List.of("() => { probe() }", "(element, extra) => { probe() }")) {
                    for (String receiver :
                            List.of("Array(1, 2)", "Encoding.UTF8.encode(\"ab\")")) {
                        Probe probe = new Probe();
                        assertInteger(
                                42,
                                lowerRoot(
                                                "run: () => {\n    "
                                                        + receiver
                                                        + ".each("
                                                        + callback
                                                        + ")\n}\n"
                                                        + "Error.handle(() => { run() }, (caught) => { 42 })\n")
                                        .getCallTarget()
                                        .call(probe.module()));
                        assertEquals(0, probe.calls.get(), receiver + " " + callback);
                    }
                }
            } finally {
                context.leave();
            }
        }
        System.out.println("PERF026_D1_UNSUPPORTED_ARITY_ORDINARY_ERROR=YES");
    }

    @Test
    void ownedReturnHomeLiteralRetainsStructuredEach() throws Exception {
        try (Context context = Context.newBuilder(ProtosLanguage.ID).build()) {
            context.initialize(ProtosLanguage.ID);
            context.enter();
            try {
                Probe probe = new Probe();
                ProtosSemanticBytecodeRootNode root =
                        lowerRoot(
                                """
                                Array(1).each((element) => { probe() })
                                """);

                assertInstanceOf(
                        ProtosArrayValue.class,
                        root.getCallTarget().call(probe.module()));
                assertEquals(List.of(3), probe.depths(), () -> "stacks=" + probe.stacks);
                List<RootNode> stack = probe.stacks.get(0);
                assertInstanceOf(ProtosSemanticBytecodeRootNode.class, stack.get(0));
                assertInstanceOf(ProtosBytecodeRootNode.class, stack.get(1));
                assertSame(root, stack.get(2));
            } finally {
                context.leave();
            }
        }
        System.out.println("PERF026_D1_OWNED_RETURN_HOME_FALLBACK=YES");
    }

    /**
     * A non-Closure invokable callback and a custom {@code each} receive the
     * staged literal through their ordinary paths: the probing body runs in
     * its own physical root.
     */
    @Test
    void nonClosureInvokableAndCustomEachStayOrdinary() throws Exception {
        assertPhysicalCallback(
                """
                run: () => {
                    callable: {
                        call: (element) => { probe() }
                    }
                    Array(1).each(callable)
                }
                run()
                """);
        assertPhysicalCallback(
                """
                run: () => {
                    custom: {
                        each: (block) => { block(1) }
                    }
                    custom.each((element) => { probe() })
                }
                run()
                """);
        System.out.println("PERF026_D1_NONCLOSURE_INVOKABLE_FALLBACK=YES");
        System.out.println("PERF026_D1_CUSTOM_EACH_FALLBACK=YES");
    }

    /**
     * The approved PLAT044 tooling delta: no distinct callback debugger frame,
     * but a breakpoint inside the inline body suspends once per element and
     * its scope is that element's activation: the callback formal, its own
     * local and a captured binding.
     */
    @Test
    void debuggerScopeInsideInlineCallbackProjectsTheCallbackActivation()
            throws Exception {
        Source source =
                Source.newBuilder(
                                ProtosLanguage.ID,
                                """
                                run: () => {
                                    outerMarker: 1
                                    total: 0
                                    Array(5, 6).each((element) => {
                                        innerMarker: 2
                                        total = total + element + innerMarker + outerMarker
                                    })
                                    total
                                }
                                run()
                                """,
                                "perf026-d1-debugger.protos")
                        .uri(URI.create("memory:///perf026-d1-debugger.protos"))
                        .mimeType(ProtosLanguage.MIME_TYPE)
                        .build();
        ProtosActivation module = new ProtosCoreBootstrap().bootstrap(CORE).newModuleActivation();
        AtomicReference<Throwable> callbackFailure = new AtomicReference<>();
        List<String> elements = new ArrayList<>();
        List<String> inners = new ArrayList<>();
        List<String> outers = new ArrayList<>();

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
                                    DebugValue inner = scope.getDeclaredValue("innerMarker");
                                    DebugValue outer = scope.getDeclaredValue("outerMarker");
                                    assertNotNull(element, "callback formal must be visible");
                                    assertNotNull(inner, "callback-owned binding must be visible");
                                    assertNotNull(outer, "captured binding must be visible");
                                    elements.add(element.toDisplayString());
                                    inners.add(inner.toDisplayString());
                                    outers.add(outer.toDisplayString());
                                } catch (Throwable failure) {
                                    callbackFailure.compareAndSet(null, failure);
                                } finally {
                                    event.prepareContinue();
                                }
                            })) {
                session.install(Breakpoint.newBuilder(source).lineIs(6).build());
                assertCompletedWith(17, polyglot.execute(source, module));
            }
        }

        if (callbackFailure.get() != null) {
            throw new AssertionError("debugger callback failed", callbackFailure.get());
        }
        assertEquals(List.of("5", "6"), elements, "one suspension per element, in order");
        assertEquals(List.of("2", "2"), inners);
        assertEquals(List.of("1", "1"), outers);
        System.out.println("PERF026_D1_INLINE_SCOPE_PROJECTION=YES");
    }

    /**
     * An admitted site inside {@code run}: every probe call sees only the
     * {@code run} root over the module root, and {@code run} carries its own
     * automatic RootTag plus the single inline callback RootTag.
     */
    private static void assertInlineTopology(
            ProtosSemanticBytecodeRootNode root,
            Probe probe,
            int expectedCalls) {
        assertEquals(expectedCalls, probe.calls.get());
        assertEquals(List.of(2), probe.depths(), () -> "stacks=" + probe.stacks);
        ProtosSemanticBytecodeRootNode runRoot =
                assertInstanceOf(
                        ProtosSemanticBytecodeRootNode.class,
                        probe.stacks.get(0).get(0));
        for (List<RootNode> stack : probe.stacks) {
            assertSame(runRoot, stack.get(0));
            assertSame(root, stack.get(1));
        }
        runRoot.getRootNodes().ensureComplete();
        assertEquals(
                2,
                rootTags(runRoot.getBytecodeNode()),
                "automatic RootTag of run plus the inline callback RootTag");
    }

    private static void assertInlineTrue(String characters, int expectedCalls)
            throws Exception {
        try (Context context = Context.newBuilder(ProtosLanguage.ID).build()) {
            context.initialize(ProtosLanguage.ID);
            context.enter();
            try {
                Probe probe = new Probe();
                ProtosSemanticBytecodeRootNode root = lowerRoot(characters);
                assertSame(
                        ProtosBooleanValue.TRUE,
                        root.getCallTarget().call(probe.module()),
                        characters);
                /*
                 * Only the stack shape: the result expression's && callbacks
                 * may add their own Boolean inline RootTags to run.
                 */
                assertEquals(expectedCalls, probe.calls.get(), characters);
                assertEquals(List.of(2), probe.depths(), () -> characters + " stacks=" + probe.stacks);
            } finally {
                context.leave();
            }
        }
    }

    /**
     * A non-admitted standard each inside {@code run}: the probing callback
     * runs in its own physical root entered from the structured-dispatch
     * helper root.
     */
    private static void assertStructuredEachPreserved(String characters) throws Exception {
        try (Context context = Context.newBuilder(ProtosLanguage.ID).build()) {
            context.initialize(ProtosLanguage.ID);
            context.enter();
            try {
                Probe probe = new Probe();
                ProtosSemanticBytecodeRootNode root = lowerRoot(characters);

                root.getCallTarget().call(probe.module());

                assertEquals(1, probe.calls.get(), characters);
                assertEquals(List.of(4), probe.depths(), () -> characters + " stacks=" + probe.stacks);
                List<RootNode> stack = probe.stacks.get(0);
                ProtosSemanticBytecodeRootNode callback =
                        assertInstanceOf(ProtosSemanticBytecodeRootNode.class, stack.get(0));
                assertInstanceOf(ProtosBytecodeRootNode.class, stack.get(1));
                assertSame(root, stack.get(3));

                callback.getRootNodes().ensureComplete();
                assertEquals(1, rootTags(callback.getBytecodeNode()), characters);
            } finally {
                context.leave();
            }
        }
    }

    /**
     * The probing body runs in its own physical semantic root (carrying only
     * its automatic RootTag) below the {@code run} root.
     */
    private static void assertPhysicalCallback(String characters) throws Exception {
        try (Context context = Context.newBuilder(ProtosLanguage.ID).build()) {
            context.initialize(ProtosLanguage.ID);
            context.enter();
            try {
                Probe probe = new Probe();
                ProtosSemanticBytecodeRootNode root = lowerRoot(characters);

                root.getCallTarget().call(probe.module());

                assertEquals(1, probe.calls.get(), characters);
                List<RootNode> stack = probe.stacks.get(0);
                assertTrue(stack.size() > 3, () -> characters + " stack=" + stack);
                assertSame(root, stack.get(stack.size() - 1));
                ProtosSemanticBytecodeRootNode callback =
                        assertInstanceOf(ProtosSemanticBytecodeRootNode.class, stack.get(0));
                callback.getRootNodes().ensureComplete();
                assertEquals(1, rootTags(callback.getBytecodeNode()), characters);
            } finally {
                context.leave();
            }
        }
    }

    /**
     * A native {@code probe} binding recording the guest CallTarget stack and
     * the first supplied argument (if any) of every call.
     */
    private static final class Probe {
        final List<List<RootNode>> stacks = new ArrayList<>();
        final List<Object> arguments = new ArrayList<>();
        final AtomicInteger calls = new AtomicInteger();

        /** The distinct stack depths observed, in first-seen order. */
        List<Integer> depths() {
            return stacks.stream().map(List::size).distinct().toList();
        }

        ProtosActivation module() throws Exception {
            ProtosActivation module =
                    new ProtosCoreBootstrap().bootstrap(CORE).newModuleActivation();
            module.context().createLocalSlot(
                    "probe",
                    ProtosClosureValue.nativeClosure(
                            (activation, supplied) -> {
                                calls.incrementAndGet();
                                if (!supplied.isEmpty()) {
                                    arguments.add(supplied.get(0));
                                }
                                List<RootNode> stack = new ArrayList<>();
                                Truffle.getRuntime()
                                        .iterateFrames(
                                                frame -> {
                                                    stack.add(
                                                            ((RootCallTarget) frame.getCallTarget())
                                                                    .getRootNode());
                                                    return null;
                                                });
                                stacks.add(stack);
                                return ProtosNullValue.INSTANCE;
                            }));
            return module;
        }
    }

    private static ProtosExecutionOutcome execute(String name, String characters)
            throws Exception {
        return ProtosTestExecutionSupport.execute(
                name,
                characters,
                new ProtosCoreBootstrap().bootstrap(CORE).newModuleActivation());
    }

    private static ProtosSemanticBytecodeRootNode lowerRoot(String characters) {
        Source source =
                Source.newBuilder(ProtosLanguage.ID, characters, "perf026-d1.protos").build();
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
