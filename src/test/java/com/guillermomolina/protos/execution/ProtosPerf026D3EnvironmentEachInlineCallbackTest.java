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
import com.guillermomolina.protos.runtime.ProtosActorExecutionDomain;
import com.guillermomolina.protos.runtime.ProtosActorModuleState;
import com.guillermomolina.protos.runtime.ProtosBooleanValue;
import com.guillermomolina.protos.runtime.ProtosClosureValue;
import com.guillermomolina.protos.runtime.ProtosEnvironmentValue;
import com.guillermomolina.protos.runtime.ProtosFutureValue;
import com.guillermomolina.protos.runtime.ProtosNullValue;
import com.guillermomolina.protos.runtime.ProtosObjectValue;
import com.guillermomolina.protos.runtime.ProtosPrelude;
import com.guillermomolina.protos.runtime.ProtosProcessRuntime;
import com.guillermomolina.protos.runtime.ProtosStringValue;
import com.guillermomolina.protos.runtime.ProtosTask;
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
 * PERF026-D3 (PLAT044 B′) focal evidence: a standard {@code Environment.each}
 * whose sole argument is an eligible immediate literal with exactly two
 * ordinary required positional parameters is sequenced in its semantic source
 * root, and each fresh per-entry activation runs inline with its own RootTag,
 * binding {@code name} and {@code value} from that activation; every
 * non-admitted call keeps the structured-dispatch each (or its own ordinary
 * dispatch) and physical callback roots. The complete portable snapshot is
 * still converted, validated and canonically ordered before callback #1.
 *
 * <p>Eligible sites are placed inside a Closure ({@code run}) because literals
 * at module top level own a fresh return home, a shape D3 keeps on the
 * structured path (as exercised by {@code
 * ProtosPerf006Plat028EnvironmentEachCallbackTest}).
 */
final class ProtosPerf026D3EnvironmentEachInlineCallbackTest {
    private static final Path CORE = Path.of("protos", "lib", "core");

    private static final LanguageReference<ProtosLanguage> LANGUAGE_REF =
            LanguageReference.create(ProtosLanguage.class);

    /** Native order; canonical scalar order is A, AB, B, b. */
    private static final List<ProtosEnvironmentValue.NativeEntry> UNSORTED =
            List.of(
                    new ProtosEnvironmentValue.NativeEntry("b", "lower"),
                    new ProtosEnvironmentValue.NativeEntry("B", "upper"),
                    new ProtosEnvironmentValue.NativeEntry("AB", "prefixed"),
                    new ProtosEnvironmentValue.NativeEntry("A", "prefix"));

    private static final List<ProtosEnvironmentValue.NativeEntry> TWO =
            List.of(
                    new ProtosEnvironmentValue.NativeEntry("B", "two"),
                    new ProtosEnvironmentValue.NativeEntry("A", "one"));

    private static final List<ProtosEnvironmentValue.NativeEntry> ONE =
            List.of(new ProtosEnvironmentValue.NativeEntry("A", "one"));

    @Test
    void eligibleEnvironmentEachRunsAsLocalLoopInCanonicalOrder() throws Exception {
        try (Context context = Context.newBuilder(ProtosLanguage.ID).build()) {
            context.initialize(ProtosLanguage.ID);
            context.enter();
            try {
                ProtosPrelude prelude = core();
                ProtosEnvironmentValue environment = standardEnvironment(prelude, UNSORTED);
                Probe probe = new Probe();
                ProtosSemanticBytecodeRootNode root =
                        lowerRoot(
                                """
                                run: () => {
                                    environment.each((name, value) => {
                                        probe(name, value)
                                        value
                                    })
                                }
                                run()
                                """);

                assertSame(
                        environment,
                        root.getCallTarget().call(probe.module(prelude, environment)));

                assertEquals(
                        List.of(
                                List.of("A", "prefix"),
                                List.of("AB", "prefixed"),
                                List.of("B", "upper"),
                                List.of("b", "lower")),
                        probe.stringArguments());
                assertInlineTopology(root, probe, 4);
            } finally {
                context.leave();
            }
        }
        System.out.println("PERF026_D3_ENVIRONMENT_EACH_LOCAL_LOOP=YES");
        System.out.println("PERF026_D3_ENVIRONMENT_HELPER_ROOT_REMOVED=YES");
        System.out.println("PERF026_D3_ENVIRONMENT_CALLBACK_ROOT_REMOVED=YES");
        System.out.println("PERF026_D3_EXACT_NAME_VALUE_ARGUMENTS=YES");
        System.out.println("PERF026_D3_TWO_PARAMETER_BINDING_FROM_PREPARED_ACTIVATION=YES");
        System.out.println("PERF026_D3_CANONICAL_NAME_ORDER_PRESERVED=YES");
        System.out.println("PERF026_D3_CALLBACK_RESULT_IGNORED=YES");
        System.out.println("PERF026_D3_RECEIVER_RESULT_IDENTITY=YES");
    }

    /**
     * The admitted site sees an invalid later value: the whole call fails
     * before any callback runs, so no valid prefix is exposed. An empty
     * Environment invokes nothing and returns the receiver.
     */
    @Test
    void inlineEnvironmentEachPrevalidatesWholePortableSnapshot() throws Exception {
        try (Context context = Context.newBuilder(ProtosLanguage.ID).build()) {
            context.initialize(ProtosLanguage.ID);
            context.enter();
            try {
                ProtosPrelude prelude = core();
                ProtosEnvironmentValue invalid =
                        standardEnvironment(
                                prelude,
                                List.of(
                                        new ProtosEnvironmentValue.NativeEntry("A", "ok"),
                                        new ProtosEnvironmentValue.NativeEntry(
                                                "B",
                                                String.valueOf((char) 0xD800))));
                Probe probe = new Probe();
                assertInteger(
                        42,
                        lowerRoot(
                                        """
                                        run: () => {
                                            environment.each((name, value) => { probe(name, value) })
                                        }
                                        Error.handle(() => { run() }, (caught) => { 42 })
                                        """)
                                .getCallTarget()
                                .call(probe.module(prelude, invalid)));
                assertEquals(0, probe.calls.get());

                ProtosEnvironmentValue empty = standardEnvironment(prelude, List.of());
                Probe emptyProbe = new Probe();
                assertSame(
                        empty,
                        lowerRoot(
                                        """
                                        run: () => {
                                            environment.each((name, value) => { probe(name, value) })
                                        }
                                        run()
                                        """)
                                .getCallTarget()
                                .call(emptyProbe.module(prelude, empty)));
                assertEquals(0, emptyProbe.calls.get());
            } finally {
                context.leave();
            }
        }
        System.out.println("PERF026_D3_WHOLE_PORTABLE_PREVALIDATION_PRESERVED=YES");
        System.out.println("PERF026_D3_CALLBACK_BEFORE_VALIDATION=NO");
    }

    @Test
    void inlineEnvironmentEachUsesFreshActivationsAndCapturesByReference()
            throws Exception {
        assertInlineTrue(
                """
                run: () => {
                    outer: context
                    previous: null
                    current: null
                    visits: 0
                    environment.each((name, value) => {
                        probe(name, value)
                        previous = current
                        current = context
                        visits = visits + 1
                    })
                    (previous !== current) && (current !== outer) && (previous !== null) && (visits == 2)
                }
                run()
                """,
                TWO,
                2);
        System.out.println("PERF026_D3_FRESH_ACTIVATION_PER_CALLBACK=YES");
        System.out.println("PERF026_D3_CAPTURE_BY_REFERENCE_PRESERVED=YES");
    }

    @Test
    void inlineEachNonLocalReturnAndErrorStopLaterVisits() throws Exception {
        try (Context context = Context.newBuilder(ProtosLanguage.ID).build()) {
            context.initialize(ProtosLanguage.ID);
            context.enter();
            try {
                ProtosPrelude prelude = core();

                Probe nonLocalReturn = new Probe();
                assertInteger(
                        8,
                        lowerRoot(
                                        """
                                        run: () => {
                                            environment.each((name, value) => {
                                                probe(name, value)
                                                ^7
                                            })
                                            100
                                        }
                                        run() + 1
                                        """)
                                .getCallTarget()
                                .call(nonLocalReturn.module(
                                        prelude,
                                        standardEnvironment(prelude, TWO))));
                assertEquals(1, nonLocalReturn.calls.get(), "NLR stops later visits");
                assertEquals(List.of(2), nonLocalReturn.depths());

                Probe error = new Probe();
                assertInteger(
                        42,
                        lowerRoot(
                                        """
                                        run: () => {
                                            environment.each((name, value) => {
                                                probe(name, value)
                                                Error().signal()
                                            })
                                        }
                                        Error.handle(() => { run() }, (caught) => { 42 })
                                        """)
                                .getCallTarget()
                                .call(error.module(
                                        prelude,
                                        standardEnvironment(prelude, TWO))));
                assertEquals(1, error.calls.get(), "an Error stops later visits");
            } finally {
                context.leave();
            }
        }
        System.out.println("PERF026_D3_NLR_PRESERVED=YES");
        System.out.println("PERF026_D3_ERROR_PROPAGATION_PRESERVED=YES");
    }

    /**
     * The first inline callback suspends on an unresolved Future inside a
     * Task; after resolution the same callback completes and only then is
     * the second entry visited, once.
     */
    @Test
    void callbackSuspensionResumesInsideTheLocalLoopWithoutReplay() throws Exception {
        try (Context context = Context.newBuilder(ProtosLanguage.ID).build()) {
            context.initialize(ProtosLanguage.ID);
            context.enter();
            try {
                ProtosPrelude prelude = core();
                ProtosActorExecutionDomain domain = new ProtosActorExecutionDomain();
                ProtosActivation module =
                        prelude.newModuleActivation(
                                new ProtosActorModuleState(),
                                null,
                                prelude.newExecutionContext(),
                                domain);
                ProtosEnvironmentValue environment = standardEnvironment(prelude, TWO);
                ProtosFutureValue gate = new ProtosFutureValue(prelude.futurePrototype(), domain);
                Probe probe = new Probe();
                probe.install(module, environment);
                module.context().createLocalSlot("gate", gate);
                ProtosSemanticBytecodeRootNode root =
                        lowerRoot(
                                """
                                run: () => {
                                    environment.each((name, value) => {
                                        probe(name, value)
                                        gate.value()
                                    })
                                }
                                run()
                                """);

                ProtosTask task =
                        domain.createTask(
                                null,
                                current ->
                                        ProtosBytecodeTaskExecution.execute(
                                                current, root.getCallTarget(), module));

                assertTrue(domain.dispatchOne());
                assertEquals(ProtosTask.State.SUSPENDED, task.state());
                assertEquals(1, probe.calls.get());
                List<RootNode> firstStack = probe.stacks.get(0);
                assertInstanceOf(ProtosSemanticBytecodeRootNode.class, firstStack.get(0));
                assertSame(root, firstStack.get(1), () -> "inline stack=" + firstStack);

                assertTrue(gate.resolve(ProtosNullValue.INSTANCE, module));
                assertTrue(domain.dispatchOne());

                assertEquals(ProtosTask.State.COMPLETED, task.state());
                assertSame(environment, task.result().orElseThrow());
                assertEquals(
                        List.of(List.of("A", "one"), List.of("B", "two")),
                        probe.stringArguments());
            } finally {
                context.leave();
            }
        }
        System.out.println("PERF026_D3_SUSPENSION_RESUMPTION_PRESERVED=YES");
        System.out.println("PERF026_D3_COMPLETED_PREFIX_REPLAY=NO");
    }

    @Test
    void dynamicCallbackAndUnsupportedLiteralShapesRetainStructuredEach()
            throws Exception {
        assertStructuredEachPreserved(
                """
                run: () => {
                    block: (name, value) => { probe() }
                    environment.each(block)
                }
                run()
                """);
        for (String callback :
                List.of(
                        "(name, value) => {\n        probe()\n        step: () => { 1 }\n        step.call()\n    }",
                        "(name, value = 7) => { probe() }",
                        "(name, ...rest) => { probe() }",
                        "(...rest) => { probe() }")) {
            assertStructuredEachPreserved(
                    "run: () => {\n"
                            + "    environment.each(" + callback + ")\n"
                            + "}\n"
                            + "run()\n");
        }
        System.out.println("PERF026_D3_DYNAMIC_CALLBACK_FALLBACK=YES");
        System.out.println("PERF026_D3_NESTED_CLOSURE_FALLBACK=YES");
        System.out.println("PERF026_D3_DEFAULT_REST_FALLBACK=YES");
    }

    /**
     * A zero-, one- or three-parameter literal keeps its ordinary arity
     * failure at the actual callback invocation: over a non-empty Environment
     * the probing body never runs, and over an empty one no invocation
     * happens, so no early arity validation signals and the receiver is the
     * result.
     */
    @Test
    void mismatchedArityLiteralsKeepOrdinaryInvocationTimeArityErrors() throws Exception {
        try (Context context = Context.newBuilder(ProtosLanguage.ID).build()) {
            context.initialize(ProtosLanguage.ID);
            context.enter();
            try {
                ProtosPrelude prelude = core();
                for (String callback :
                        List.of(
                                "() => { probe() }",
                                "(name) => { probe() }",
                                "(name, value, extra) => { probe() }")) {
                    Probe probe = new Probe();
                    assertInteger(
                            42,
                            lowerRoot(
                                            "run: () => {\n"
                                                    + "    environment.each(" + callback + ")\n"
                                                    + "}\n"
                                                    + "Error.handle(() => { run() }, (caught) => { 42 })\n")
                                    .getCallTarget()
                                    .call(probe.module(prelude, standardEnvironment(prelude, ONE))));
                    assertEquals(0, probe.calls.get(), callback);

                    Probe empty = new Probe();
                    assertSame(
                            ProtosBooleanValue.TRUE,
                            lowerRoot(
                                            "run: () => {\n"
                                                    + "    environment.each(" + callback + ") === environment\n"
                                                    + "}\n"
                                                    + "run()\n")
                                    .getCallTarget()
                                    .call(empty.module(prelude, standardEnvironment(prelude, List.of()))),
                            "empty " + callback);
                    assertEquals(0, empty.calls.get(), "empty " + callback);
                }
            } finally {
                context.leave();
            }
        }
        System.out.println("PERF026_D3_UNSUPPORTED_ARITY_FALLBACK=YES");
        System.out.println("PERF026_D3_ARITY_PREVALIDATION_INTRODUCED=NO");
    }

    @Test
    void ownedReturnHomeLiteralRetainsStructuredEach() throws Exception {
        try (Context context = Context.newBuilder(ProtosLanguage.ID).build()) {
            context.initialize(ProtosLanguage.ID);
            context.enter();
            try {
                ProtosPrelude prelude = core();
                Probe probe = new Probe();
                ProtosSemanticBytecodeRootNode root =
                        lowerRoot("environment.each((name, value) => { probe() })\n");

                root.getCallTarget().call(probe.module(prelude, standardEnvironment(prelude, ONE)));
                assertEquals(List.of(3), probe.depths(), () -> "stacks=" + probe.stacks);
                List<RootNode> stack = probe.stacks.get(0);
                assertInstanceOf(ProtosSemanticBytecodeRootNode.class, stack.get(0));
                assertInstanceOf(ProtosBytecodeRootNode.class, stack.get(1));
                assertSame(root, stack.get(2));
            } finally {
                context.leave();
            }
        }
        System.out.println("PERF026_D3_OWNED_RETURN_HOME_FALLBACK=YES");
    }

    /**
     * A non-Closure invokable callback, a custom {@code each} and the
     * standard {@code each} copied to another home receive the staged
     * literal through their ordinary paths: the probing body runs in its own
     * physical root.
     */
    @Test
    void nonClosureInvokableCustomAndCopiedEachStayOrdinary() throws Exception {
        assertPhysicalCallback(
                """
                run: () => {
                    callable: {
                        call: (name, value) => { probe() }
                    }
                    environment.each(callable)
                }
                run()
                """,
                false);
        assertPhysicalCallback(
                """
                run: () => {
                    custom: {
                        each: (block) => { block("n", "v") }
                    }
                    custom.each((name, value) => { probe() })
                }
                run()
                """,
                false);
        assertPhysicalCallback(
                """
                run: () => {
                    environment.each((name, value) => { probe() })
                }
                run()
                """,
                true);
        System.out.println("PERF026_D3_NONCLOSURE_INVOKABLE_FALLBACK=YES");
        System.out.println("PERF026_D3_CUSTOM_EACH_FALLBACK=YES");
        System.out.println("PERF026_D3_COPIED_STANDARD_HOME_FALLBACK=YES");
    }

    /** D1's indexed and D2's association each keep their local loops beside D3. */
    @Test
    void indexedAndAssociationEachKeepTheirLocalLoops() throws Exception {
        try (Context context = Context.newBuilder(ProtosLanguage.ID).build()) {
            context.initialize(ProtosLanguage.ID);
            context.enter();
            try {
                ProtosPrelude prelude = core();
                ProtosEnvironmentValue environment = standardEnvironment(prelude, ONE);
                for (String site :
                        List.of(
                                "Array(1, 2).each((element) => { probe(element) })",
                                "Encoding.UTF8.encode(\"ab\").each((octet) => { probe(octet) })",
                                "m: Map()\n    m[1] = 2\n    m[3] = 4\n    m.each((key, value) => { probe(key, value) })",
                                "m: IdentityMap()\n    m[1] = 2\n    m[3] = 4\n    m.each((key, value) => { probe(key, value) })")) {
                    Probe probe = new Probe();
                    ProtosSemanticBytecodeRootNode root =
                            lowerRoot("run: () => {\n    " + site + "\n}\nrun()\n");
                    root.getCallTarget().call(probe.module(prelude, environment));
                    assertInlineTopology(root, probe, 2);
                }
            } finally {
                context.leave();
            }
        }
        System.out.println("PERF026_D3_ARRAY_BYTES_D1_PRESERVED=YES");
        System.out.println("PERF026_D3_MAP_IDENTITYMAP_D2_PRESERVED=YES");
    }

    /**
     * The approved PLAT044 tooling delta: no distinct callback debugger frame,
     * but a breakpoint inside the inline body suspends once per entry, in
     * canonical order, and its scope is that entry's activation: both
     * callback formals, its own local and a captured binding.
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
                                    visits: 0
                                    environment.each((name, value) => {
                                        innerMarker: 2
                                        visits = visits + innerMarker + outerMarker
                                    })
                                    visits
                                }
                                run()
                                """,
                                "perf026-d3-debugger.protos")
                        .uri(URI.create("memory:///perf026-d3-debugger.protos"))
                        .mimeType(ProtosLanguage.MIME_TYPE)
                        .build();
        ProtosPrelude prelude = core();
        ProtosActivation module = prelude.newModuleActivation();
        module.context().createLocalSlot("environment", standardEnvironment(prelude, TWO));
        AtomicReference<Throwable> callbackFailure = new AtomicReference<>();
        List<String> names = new ArrayList<>();
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
                                    DebugValue name = scope.getDeclaredValue("name");
                                    DebugValue value = scope.getDeclaredValue("value");
                                    DebugValue inner = scope.getDeclaredValue("innerMarker");
                                    DebugValue outer = scope.getDeclaredValue("outerMarker");
                                    assertNotNull(name, "name formal must be visible");
                                    assertNotNull(value, "value formal must be visible");
                                    assertNotNull(inner, "callback-owned binding must be visible");
                                    assertNotNull(outer, "captured binding must be visible");
                                    names.add(name.toDisplayString());
                                    values.add(value.toDisplayString());
                                    inners.add(inner.toDisplayString());
                                    outers.add(outer.toDisplayString());
                                } catch (Throwable failure) {
                                    callbackFailure.compareAndSet(null, failure);
                                } finally {
                                    event.prepareContinue();
                                }
                            })) {
                session.install(Breakpoint.newBuilder(source).lineIs(6).build());
                assertCompletedWith(6, polyglot.execute(source, module));
            }
        }

        if (callbackFailure.get() != null) {
            throw new AssertionError("debugger callback failed", callbackFailure.get());
        }
        assertEquals(2, names.size(), "one suspension per entry");
        assertTrue(names.get(0).contains("A") && names.get(1).contains("B"), () -> "names=" + names);
        assertTrue(values.get(0).contains("one") && values.get(1).contains("two"), () -> "values=" + values);
        assertEquals(List.of("2", "2"), inners);
        assertEquals(List.of("1", "1"), outers);
        System.out.println("PERF026_D3_INLINE_ROOTTAG=YES");
        System.out.println("PERF026_D3_INLINE_SCOPE_PROJECTION=YES");
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

    private static void assertInlineTrue(
            String characters,
            List<ProtosEnvironmentValue.NativeEntry> entries,
            int expectedCalls) throws Exception {
        try (Context context = Context.newBuilder(ProtosLanguage.ID).build()) {
            context.initialize(ProtosLanguage.ID);
            context.enter();
            try {
                ProtosPrelude prelude = core();
                Probe probe = new Probe();
                ProtosSemanticBytecodeRootNode root = lowerRoot(characters);
                assertSame(
                        ProtosBooleanValue.TRUE,
                        root.getCallTarget().call(
                                probe.module(prelude, standardEnvironment(prelude, entries))),
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
     * A non-admitted standard Environment.each inside {@code run}: the probing
     * callback runs in its own physical root entered from the
     * structured-dispatch helper root.
     */
    private static void assertStructuredEachPreserved(String characters) throws Exception {
        try (Context context = Context.newBuilder(ProtosLanguage.ID).build()) {
            context.initialize(ProtosLanguage.ID);
            context.enter();
            try {
                ProtosPrelude prelude = core();
                Probe probe = new Probe();
                ProtosSemanticBytecodeRootNode root = lowerRoot(characters);

                root.getCallTarget().call(probe.module(prelude, standardEnvironment(prelude, ONE)));

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
     * The probing body runs in its own physical semantic root carrying only
     * its automatic RootTag; an inline body would instead appear as the
     * {@code run} root carrying its own plus the inline RootTag. Only the
     * innermost frame is examined: a callback invoked from a native {@code
     * each} (the copied-home case) may run on a guest carrier whose Truffle
     * stack does not include the {@code run} and module roots. {@code
     * copiedHome} installs an Environment whose {@code each} is the standard
     * one copied to another home.
     */
    private static void assertPhysicalCallback(String characters, boolean copiedHome)
            throws Exception {
        try (Context context = Context.newBuilder(ProtosLanguage.ID).build()) {
            context.initialize(ProtosLanguage.ID);
            context.enter();
            try {
                ProtosPrelude prelude = core();
                ProtosEnvironmentValue environment =
                        copiedHome
                                ? copiedHomeEnvironment(prelude)
                                : standardEnvironment(prelude, ONE);
                Probe probe = new Probe();
                ProtosSemanticBytecodeRootNode root = lowerRoot(characters);

                root.getCallTarget().call(probe.module(prelude, environment));

                assertEquals(1, probe.calls.get(), characters);
                List<RootNode> stack = probe.stacks.get(0);
                ProtosSemanticBytecodeRootNode callback =
                        assertInstanceOf(ProtosSemanticBytecodeRootNode.class, stack.get(0));
                assertNotSame(root, callback, () -> characters + " stack=" + describe(stack));
                callback.getRootNodes().ensureComplete();
                assertEquals(
                        1,
                        rootTags(callback.getBytecodeNode()),
                        () -> characters + " stack=" + describe(stack));
            } finally {
                context.leave();
            }
        }
    }

    private static List<String> describe(List<RootNode> stack) {
        return stack.stream()
                .map(node -> node.getName() + "@" + node.getSourceSection())
                .toList();
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

        /** Every call's arguments, each required to be a Protos String. */
        List<List<String>> stringArguments() {
            return arguments.stream()
                    .map(supplied ->
                            supplied.stream()
                                    .map(value ->
                                            assertInstanceOf(ProtosStringValue.class, value)
                                                    .value())
                                    .toList())
                    .toList();
        }

        ProtosActivation module(ProtosPrelude prelude, ProtosEnvironmentValue environment) {
            ProtosActivation module = prelude.newModuleActivation();
            install(module, environment);
            return module;
        }

        void install(ProtosActivation module, ProtosEnvironmentValue environment) {
            module.context().createLocalSlot("environment", environment);
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
        }
    }

    private static ProtosEnvironmentValue standardEnvironment(
            ProtosPrelude prelude,
            List<ProtosEnvironmentValue.NativeEntry> entries) {
        return environment(prelude, ProtosStandardEnvironmentProtocol.createPrototype(), entries);
    }

    /** The standard {@code each} Closure installed at a non-canonical home. */
    private static ProtosEnvironmentValue copiedHomeEnvironment(ProtosPrelude prelude) {
        ProtosClosureValue each =
                (ProtosClosureValue)
                        ProtosStandardEnvironmentProtocol.createPrototype()
                                .readLocalSlot("each")
                                .orElseThrow();
        ProtosObjectValue aliasHome = new ProtosObjectValue(ProtosObjectValue.rootObject());
        aliasHome.createLocalSlot("each", each);
        aliasHome.freeze();
        return environment(prelude, aliasHome, ONE);
    }

    private static ProtosEnvironmentValue environment(
            ProtosPrelude prelude,
            ProtosObjectValue prototype,
            List<ProtosEnvironmentValue.NativeEntry> entries) {
        ProtosProcessRuntime process =
                new ProtosProcessRuntime(prelude.actorRefPrototypeForRuntime());
        assertEquals(
                ProtosProcessRuntime.EnvironmentSnapshotState.AVAILABLE,
                process.establishEnvironmentForRuntime(prototype, exactDomain(), entries));
        return process.environmentSnapshotForRuntime().orElseThrow();
    }

    private static ProtosEnvironmentValue.NativeNameDomain exactDomain() {
        return new ProtosEnvironmentValue.NativeNameDomain() {
            @Override
            public boolean sameCapturedName(String left, String right) {
                return left.equals(right);
            }

            @Override
            public boolean isQueryRepresentable(String name) {
                return !name.contains("=") && name.indexOf('\0') < 0;
            }

            @Override
            public boolean matchesQuery(String captured, String query) {
                return captured.equals(query);
            }
        };
    }

    private static ProtosPrelude core() throws Exception {
        return new ProtosCoreBootstrap().bootstrap(CORE);
    }

    private static ProtosSemanticBytecodeRootNode lowerRoot(String characters) {
        Source source =
                Source.newBuilder(ProtosLanguage.ID, characters, "perf026-d3.protos").build();
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
