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
import com.guillermomolina.protos.runtime.ProtosBooleanValue;
import com.guillermomolina.protos.runtime.ProtosClosureValue;
import com.guillermomolina.protos.runtime.ProtosIdentityMapValue;
import com.guillermomolina.protos.runtime.ProtosMapValue;
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
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Instrument;
import org.junit.jupiter.api.Test;

/**
 * PERF026-D2 (PLAT044 B′) focal evidence: a standard {@code Map.each} or
 * {@code IdentityMap.each} whose sole argument is an eligible immediate
 * literal with exactly two ordinary required positional parameters is
 * sequenced in its semantic source root, and each fresh per-association
 * activation runs inline with its own RootTag, binding {@code key} and
 * {@code value} from that activation; every non-admitted call keeps the
 * structured-dispatch each (or its own ordinary dispatch) and physical
 * callback roots.
 *
 * <p>Eligible sites are placed inside a Closure ({@code run}) because literals
 * at module top level own a fresh return home, a shape D2 keeps on the
 * structured path.
 */
final class ProtosPerf026D2AssociationEachInlineCallbackTest {
    private static final Path CORE = Path.of("protos", "lib", "core");

    private static final LanguageReference<ProtosLanguage> LANGUAGE_REF =
            LanguageReference.create(ProtosLanguage.class);

    private static final List<String> RECEIVERS = List.of("Map()", "IdentityMap()");

    @Test
    void eligibleMapEachRunsAsLocalLoopWithExactKeysAndValues() throws Exception {
        try (Context context = Context.newBuilder(ProtosLanguage.ID).build()) {
            context.initialize(ProtosLanguage.ID);
            context.enter();
            try {
                Probe probe = new Probe();
                ProtosSemanticBytecodeRootNode root =
                        lowerRoot(
                                """
                                run: () => {
                                    m: Map()
                                    m["a"] = {}
                                    m["b"] = {}
                                    m.each((key, value) => {
                                        probe(key, value)
                                        value
                                    })
                                }
                                run()
                                """);

                ProtosMapValue map =
                        assertInstanceOf(
                                ProtosMapValue.class,
                                root.getCallTarget().call(probe.module()));

                assertExactAssociations(map.associationSnapshot(), probe);
                assertInlineTopology(root, probe, 2);
            } finally {
                context.leave();
            }
        }
        System.out.println("PERF026_D2_MAP_EACH_LOCAL_LOOP=YES");
        System.out.println("PERF026_D2_MAP_HELPER_ROOT_REMOVED=YES");
        System.out.println("PERF026_D2_MAP_CALLBACK_ROOT_REMOVED=YES");
        System.out.println("PERF026_D2_MAP_EXACT_KEY_VALUE_ARGUMENTS=YES");
    }

    /**
     * The keys count their own {@code hash}/{@code equals} callbacks; none may
     * run while iterating, so the inline loop performs no identity re-search.
     */
    @Test
    void eligibleIdentityMapEachRunsAsLocalLoopWithoutReSearch() throws Exception {
        try (Context context = Context.newBuilder(ProtosLanguage.ID).build()) {
            context.initialize(ProtosLanguage.ID);
            context.enter();
            try {
                Probe probe = new Probe();
                ProtosSemanticBytecodeRootNode root =
                        lowerRoot(
                                """
                                run: () => {
                                    state: { keyCallbacks: 0 }
                                    source: {
                                        hash: () => {
                                            state.keyCallbacks = state.keyCallbacks + 1
                                            7
                                        }
                                        equals: (other) => {
                                            state.keyCallbacks = state.keyCallbacks + 1
                                            true
                                        }
                                    }
                                    view: source.alias("equals", "==")
                                    first: view {}
                                    second: view {}
                                    m: IdentityMap()
                                    m[first] = {}
                                    m[second] = {}
                                    state.keyCallbacks = 0
                                    result: m.each((key, value) => {
                                        probe(key, value)
                                        value
                                    })
                                    probe(state.keyCallbacks)
                                    result
                                }
                                run()
                                """);

                ProtosIdentityMapValue identityMap =
                        assertInstanceOf(
                                ProtosIdentityMapValue.class,
                                root.getCallTarget().call(probe.module()));

                assertEquals(3, probe.calls.get());
                assertInteger(0, probe.arguments.get(2).get(0));
                probe.arguments.remove(2);
                probe.stacks.remove(2);
                probe.calls.decrementAndGet();
                assertExactAssociations(identityMap.associationSnapshot(), probe);
                assertInlineTopology(root, probe, 2);
            } finally {
                context.leave();
            }
        }
        System.out.println("PERF026_D2_IDENTITY_MAP_EACH_LOCAL_LOOP=YES");
        System.out.println("PERF026_D2_IDENTITY_MAP_HELPER_ROOT_REMOVED=YES");
        System.out.println("PERF026_D2_IDENTITY_MAP_CALLBACK_ROOT_REMOVED=YES");
        System.out.println("PERF026_D2_IDENTITY_MAP_EXACT_KEY_VALUE_ARGUMENTS=YES");
        System.out.println("PERF026_D2_IDENTITY_MAP_IDENTITY_RESEARCH_INTRODUCED=NO");
    }

    /**
     * On the first visit the callback (through a helper, keeping the literal
     * free of nested Closures) replaces a later value, removes and
     * reinserts the first key, removes the last key and adds a new one; the
     * snapshot still yields exactly the original three associations in
     * insertion order, the callback result is ignored and the receiver is
     * the result.
     */
    @Test
    void inlineMapEachKeepsSnapshotOrderResultIdentityAndFreshActivations()
            throws Exception {
        assertInlineTrue(
                """
                run: () => {
                    m: Map()
                    m["a"] = 1
                    m["b"] = 2
                    m["c"] = 3
                    acc: 0
                    mutated: false
                    mutateOnce: () => {
                        mutated.ifFalse(() => {
                            mutated = true
                            m["b"] = 20
                            m.remove("a")
                            m["a"] = 5
                            m.remove("c")
                            m["d"] = 4
                        })
                    }
                    result: m.each((key, value) => {
                        probe(key, value)
                        mutateOnce()
                        acc = acc * 10 + value
                    })
                    (result === m) && (acc == 123) && (m.size() == 3) && (m["b"] == 20)
                }
                run()
                """,
                3);
        assertFreshActivations("Map()", "\"a\"", "\"b\"");
        System.out.println("PERF026_D2_MAP_ASSOCIATION_SNAPSHOT_PRESERVED=YES");
        System.out.println("PERF026_D2_MAP_INSERTION_ORDER_PRESERVED=YES");
        System.out.println("PERF026_D2_MAP_CALLBACK_RESULT_IGNORED=YES");
        System.out.println("PERF026_D2_MAP_RECEIVER_RESULT_IDENTITY=YES");
        System.out.println("PERF026_D2_CAPTURE_BY_REFERENCE_PRESERVED=YES");
        System.out.println("PERF026_D2_FRESH_ACTIVATION_PER_CALLBACK=YES");
    }

    @Test
    void inlineIdentityMapEachKeepsSnapshotOrderResultIdentityAndFreshActivations()
            throws Exception {
        assertInlineTrue(
                """
                run: () => {
                    ka: {}
                    kb: {}
                    kc: {}
                    m: IdentityMap()
                    m[ka] = 1
                    m[kb] = 2
                    m[kc] = 3
                    late: {}
                    acc: 0
                    mutated: false
                    mutateOnce: () => {
                        mutated.ifFalse(() => {
                            mutated = true
                            m[kb] = 20
                            m.remove(ka)
                            m[ka] = 5
                            m.remove(kc)
                            m[late] = 4
                        })
                    }
                    result: m.each((key, value) => {
                        probe(key, value)
                        mutateOnce()
                        acc = acc * 10 + value
                    })
                    (result === m) && (acc == 123) && (m.size() == 3) && (m[kb] == 20)
                }
                run()
                """,
                3);
        assertFreshActivations("IdentityMap()", "{}", "{}");
        System.out.println("PERF026_D2_IDENTITY_MAP_ASSOCIATION_SNAPSHOT_PRESERVED=YES");
        System.out.println("PERF026_D2_IDENTITY_MAP_INSERTION_ORDER_PRESERVED=YES");
        System.out.println("PERF026_D2_IDENTITY_MAP_CALLBACK_RESULT_IGNORED=YES");
        System.out.println("PERF026_D2_IDENTITY_MAP_RECEIVER_RESULT_IDENTITY=YES");
    }

    @Test
    void inlineEachNonLocalReturnAndErrorStopLaterVisits() throws Exception {
        try (Context context = Context.newBuilder(ProtosLanguage.ID).build()) {
            context.initialize(ProtosLanguage.ID);
            context.enter();
            try {
                for (String receiver : RECEIVERS) {
                    String fill =
                            "    m: " + receiver + "\n"
                                    + "    m[1] = 10\n"
                                    + "    m[2] = 20\n"
                                    + "    m[3] = 30\n";

                    Probe nonLocalReturn = new Probe();
                    assertInteger(
                            53,
                            lowerRoot(
                                            "run: () => {\n"
                                                    + fill
                                                    + "    m.each((key, value) => {\n"
                                                    + "        probe(key, value)\n"
                                                    + "        ^value + key + 41\n"
                                                    + "    })\n"
                                                    + "    100\n"
                                                    + "}\n"
                                                    + "run() + 1\n")
                                    .getCallTarget()
                                    .call(nonLocalReturn.module()));
                    assertEquals(1, nonLocalReturn.calls.get(), receiver + " NLR stops later visits");
                    assertEquals(List.of(2), nonLocalReturn.depths(), receiver);

                    Probe error = new Probe();
                    assertInteger(
                            42,
                            lowerRoot(
                                            "run: () => {\n"
                                                    + fill
                                                    + "    m.each((key, value) => {\n"
                                                    + "        probe(key, value)\n"
                                                    + "        Error().signal()\n"
                                                    + "    })\n"
                                                    + "}\n"
                                                    + "Error.handle(() => { run() }, (caught) => { 42 })\n")
                                    .getCallTarget()
                                    .call(error.module()));
                    assertEquals(1, error.calls.get(), receiver + " an Error stops later visits");
                }
            } finally {
                context.leave();
            }
        }
        System.out.println("PERF026_D2_NLR_PRESERVED=YES");
        System.out.println("PERF026_D2_ERROR_PROPAGATION_PRESERVED=YES");
    }

    /**
     * Every callback suspends; a replayed completed prefix would add an
     * earlier association (and visit) again, changing the result.
     */
    @Test
    void callbackSuspensionResumesInsideTheLocalLoopWithoutReplay() throws Exception {
        for (String receiver : RECEIVERS) {
            assertCompletedWith(
                    1233,
                    execute(
                            "perf026-d2-suspension.protos",
                            "run: () => {\n"
                                    + "    step: (() => { 1 }).future()\n"
                                    + "    m: " + receiver + "\n"
                                    + "    m[10] = 1\n"
                                    + "    m[20] = 2\n"
                                    + "    m[30] = 3\n"
                                    + "    acc: 0\n"
                                    + "    visits: 0\n"
                                    + "    m.each((key, value) => {\n"
                                    + "        visits = visits + 1\n"
                                    + "        acc = acc * 10 + value * step.value()\n"
                                    + "    })\n"
                                    + "    acc * 10 + visits\n"
                                    + "}\n"
                                    + "run()\n"));
        }
        System.out.println("PERF026_D2_SUSPENSION_RESUMPTION_PRESERVED=YES");
        System.out.println("PERF026_D2_COMPLETED_PREFIX_REPLAY=NO");
    }

    @Test
    void dynamicCallbackRetainsStructuredEach() throws Exception {
        for (String receiver : RECEIVERS) {
            assertStructuredEachPreserved(
                    "run: () => {\n"
                            + "    block: (key, value) => { probe() }\n"
                            + "    m: " + receiver + "\n"
                            + "    m[1] = 2\n"
                            + "    m.each(block)\n"
                            + "}\n"
                            + "run()\n");
        }
        System.out.println("PERF026_D2_DYNAMIC_CALLBACK_FALLBACK=YES");
    }

    @Test
    void unsupportedLiteralShapesRetainStructuredEach() throws Exception {
        for (String receiver : RECEIVERS) {
            for (String callback :
                    List.of(
                            "(key, value) => {\n        probe()\n        step: () => { 1 }\n        step.call()\n    }",
                            "(key, value = 7) => { probe() }",
                            "(key, ...rest) => { probe() }",
                            "(...rest) => { probe() }")) {
                assertStructuredEachPreserved(
                        "run: () => {\n"
                                + "    m: " + receiver + "\n"
                                + "    m[1] = 2\n"
                                + "    m.each(" + callback + ")\n"
                                + "}\n"
                                + "run()\n");
            }
        }
        System.out.println("PERF026_D2_NESTED_CLOSURE_LITERAL_FALLBACK=YES");
        System.out.println("PERF026_D2_DEFAULT_REST_PARAMETER_FALLBACK=YES");
    }

    /**
     * A zero-, one- or three-parameter literal keeps its ordinary arity
     * failure at the actual callback invocation: over a non-empty receiver
     * the probing body never runs, and over an empty receiver no invocation
     * happens, so no early arity validation signals and the receiver is the
     * result.
     */
    @Test
    void mismatchedArityLiteralsKeepOrdinaryInvocationTimeArityErrors() throws Exception {
        try (Context context = Context.newBuilder(ProtosLanguage.ID).build()) {
            context.initialize(ProtosLanguage.ID);
            context.enter();
            try {
                for (String callback :
                        List.of(
                                "() => { probe() }",
                                "(key) => { probe() }",
                                "(key, value, extra) => { probe() }")) {
                    for (String receiver : RECEIVERS) {
                        Probe probe = new Probe();
                        assertInteger(
                                42,
                                lowerRoot(
                                                "run: () => {\n"
                                                        + "    m: " + receiver + "\n"
                                                        + "    m[1] = 2\n"
                                                        + "    m.each(" + callback + ")\n"
                                                        + "}\n"
                                                        + "Error.handle(() => { run() }, (caught) => { 42 })\n")
                                        .getCallTarget()
                                        .call(probe.module()));
                        assertEquals(0, probe.calls.get(), receiver + " " + callback);

                        Probe empty = new Probe();
                        assertSame(
                                ProtosBooleanValue.TRUE,
                                lowerRoot(
                                                "run: () => {\n"
                                                        + "    m: " + receiver + "\n"
                                                        + "    m.each(" + callback + ") === m\n"
                                                        + "}\n"
                                                        + "run()\n")
                                        .getCallTarget()
                                        .call(empty.module()),
                                receiver + " empty " + callback);
                        assertEquals(0, empty.calls.get(), receiver + " empty " + callback);
                    }
                }
            } finally {
                context.leave();
            }
        }
        System.out.println("PERF026_D2_UNSUPPORTED_ARITY_FALLBACK=YES");
        System.out.println("PERF026_D2_ARITY_PREVALIDATION_INTRODUCED=NO");
    }

    @Test
    void ownedReturnHomeLiteralRetainsStructuredEach() throws Exception {
        try (Context context = Context.newBuilder(ProtosLanguage.ID).build()) {
            context.initialize(ProtosLanguage.ID);
            context.enter();
            try {
                for (String receiver : RECEIVERS) {
                    Probe probe = new Probe();
                    ProtosSemanticBytecodeRootNode root =
                            lowerRoot(
                                    "m: " + receiver + "\n"
                                            + "m[1] = 2\n"
                                            + "m.each((key, value) => { probe() })\n");

                    root.getCallTarget().call(probe.module());
                    assertEquals(List.of(3), probe.depths(), () -> receiver + " stacks=" + probe.stacks);
                    List<RootNode> stack = probe.stacks.get(0);
                    assertInstanceOf(ProtosSemanticBytecodeRootNode.class, stack.get(0));
                    assertInstanceOf(ProtosBytecodeRootNode.class, stack.get(1));
                    assertSame(root, stack.get(2));
                }
            } finally {
                context.leave();
            }
        }
        System.out.println("PERF026_D2_OWNED_RETURN_HOME_FALLBACK=YES");
    }

    /**
     * A non-Closure invokable callback and a custom {@code each} receive the
     * staged literal through their ordinary paths: the probing body runs in
     * its own physical root.
     */
    @Test
    void nonClosureInvokableAndCustomEachStayOrdinary() throws Exception {
        for (String receiver : RECEIVERS) {
            assertPhysicalCallback(
                    "run: () => {\n"
                            + "    callable: {\n"
                            + "        call: (key, value) => { probe() }\n"
                            + "    }\n"
                            + "    m: " + receiver + "\n"
                            + "    m[1] = 2\n"
                            + "    m.each(callable)\n"
                            + "}\n"
                            + "run()\n");
        }
        assertPhysicalCallback(
                """
                run: () => {
                    custom: {
                        each: (block) => { block(1, 2) }
                    }
                    custom.each((key, value) => { probe() })
                }
                run()
                """);
        System.out.println("PERF026_D2_NONCLOSURE_INVOKABLE_FALLBACK=YES");
        System.out.println("PERF026_D2_CUSTOM_EACH_FALLBACK=YES");
    }

    /** D1's one-parameter indexed each keeps its local loop beside D2. */
    @Test
    void indexedEachKeepsItsOneParameterLocalLoop() throws Exception {
        try (Context context = Context.newBuilder(ProtosLanguage.ID).build()) {
            context.initialize(ProtosLanguage.ID);
            context.enter();
            try {
                Probe probe = new Probe();
                ProtosSemanticBytecodeRootNode root =
                        lowerRoot(
                                """
                                run: () => {
                                    Array(1, 2).each((element) => { probe(element) })
                                }
                                run()
                                """);
                root.getCallTarget().call(probe.module());
                assertInlineTopology(root, probe, 2);
            } finally {
                context.leave();
            }
        }
        System.out.println("PERF026_D2_INDEXED_EACH_D1_PRESERVED=YES");
    }

    /**
     * The approved PLAT044 tooling delta: no distinct callback debugger frame,
     * but a breakpoint inside the inline body suspends once per association
     * and its scope is that association's activation: both callback formals,
     * its own local and a captured binding.
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
                                    m: Map()
                                    m[5] = 50
                                    m[6] = 60
                                    m.each((key, value) => {
                                        innerMarker: 2
                                        total = total + key + value + innerMarker + outerMarker
                                    })
                                    total
                                }
                                run()
                                """,
                                "perf026-d2-debugger.protos")
                        .uri(URI.create("memory:///perf026-d2-debugger.protos"))
                        .mimeType(ProtosLanguage.MIME_TYPE)
                        .build();
        ProtosActivation module = new ProtosCoreBootstrap().bootstrap(CORE).newModuleActivation();
        AtomicReference<Throwable> callbackFailure = new AtomicReference<>();
        List<String> keys = new ArrayList<>();
        List<String> values = new ArrayList<>();
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
                                    DebugValue key = scope.getDeclaredValue("key");
                                    DebugValue value = scope.getDeclaredValue("value");
                                    DebugValue inner = scope.getDeclaredValue("innerMarker");
                                    DebugValue outer = scope.getDeclaredValue("outerMarker");
                                    assertNotNull(key, "key formal must be visible");
                                    assertNotNull(value, "value formal must be visible");
                                    assertNotNull(inner, "callback-owned binding must be visible");
                                    assertNotNull(outer, "captured binding must be visible");
                                    keys.add(key.toDisplayString());
                                    values.add(value.toDisplayString());
                                    inners.add(inner.toDisplayString());
                                    outers.add(outer.toDisplayString());
                                } catch (Throwable failure) {
                                    callbackFailure.compareAndSet(null, failure);
                                } finally {
                                    event.prepareContinue();
                                }
                            })) {
                session.install(Breakpoint.newBuilder(source).lineIs(9).build());
                assertCompletedWith(127, polyglot.execute(source, module));
            }
        }

        if (callbackFailure.get() != null) {
            throw new AssertionError("debugger callback failed", callbackFailure.get());
        }
        assertEquals(List.of("5", "6"), keys, "one suspension per association, in order");
        assertEquals(List.of("50", "60"), values);
        assertEquals(List.of("2", "2"), inners);
        assertEquals(List.of("1", "1"), outers);
        System.out.println("PERF026_D2_INLINE_ROOTTAG=YES");
        System.out.println("PERF026_D2_INLINE_SCOPE_PROJECTION=YES");
    }

    /**
     * Every probe call received exactly the representative key and value
     * objects of the corresponding snapshot association, in order.
     */
    private static void assertExactAssociations(
            List<Map.Entry<Object, Object>> associations,
            Probe probe) {
        assertEquals(2, associations.size());
        assertEquals(2, probe.arguments.size());
        for (int index = 0; index < 2; index++) {
            List<Object> supplied = probe.arguments.get(index);
            assertEquals(2, supplied.size());
            assertSame(associations.get(index).getKey(), supplied.get(0));
            assertSame(associations.get(index).getValue(), supplied.get(1));
        }
        assertNotSame(associations.get(0).getValue(), associations.get(1).getValue());
    }

    /** Each association runs in its own fresh activation, distinct from run's. */
    private static void assertFreshActivations(
            String receiver,
            String firstKey,
            String secondKey) throws Exception {
        assertInlineTrue(
                "run: () => {\n"
                        + "    m: " + receiver + "\n"
                        + "    m[" + firstKey + "] = 1\n"
                        + "    m[" + secondKey + "] = 2\n"
                        + "    outer: context\n"
                        + "    previous: null\n"
                        + "    current: null\n"
                        + "    m.each((key, value) => {\n"
                        + "        probe(key, value)\n"
                        + "        previous = current\n"
                        + "        current = context\n"
                        + "    })\n"
                        + "    (previous !== current) && (current !== outer) && (previous !== null)\n"
                        + "}\n"
                        + "run()\n",
                2);
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
     * the supplied arguments of every call.
     */
    private static final class Probe {
        final List<List<RootNode>> stacks = new ArrayList<>();
        final List<List<Object>> arguments = new ArrayList<>();
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
                                arguments.add(List.copyOf(supplied));
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
                Source.newBuilder(ProtosLanguage.ID, characters, "perf026-d2.protos").build();
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
