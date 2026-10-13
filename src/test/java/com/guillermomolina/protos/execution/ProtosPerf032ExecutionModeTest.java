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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.bytecode.TagTreeNode;
import com.oracle.truffle.api.debug.Breakpoint;
import com.oracle.truffle.api.debug.DebugException;
import com.oracle.truffle.api.debug.DebugScope;
import com.oracle.truffle.api.debug.DebugValue;
import com.oracle.truffle.api.debug.Debugger;
import com.oracle.truffle.api.debug.DebuggerSession;
import com.oracle.truffle.api.interop.ArityException;
import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.nodes.IndirectCallNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Instrument;
import org.graalvm.polyglot.Value;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * PERF032: the immutable NORMAL/DEBUG execution mode, not instrumentation or debugger state,
 * selects the host-entry ABI of the eligible literal Closure. NORMAL enters it headerless and
 * rejects debugger scopes on that frame explicitly; DEBUG carries provenance from the first call.
 */
final class ProtosPerf032ExecutionModeTest {
    private static final Path CORE = Path.of("protos", "lib", "core");
    private static final String SOURCE =
            """
            marker: "perf032-marker"
            run: () => {
                1
            }
            0
            """;
    private static final int BODY_LINE = 3;

    @TempDir
    Path directory;

    @Test
    void i095B6aOwnScalarNormalDebugParity() throws Exception {
        String source = """
                run: () => {
                    value: 9
                    inside: true.ifTrue(() => {
                        value: 41
                        value = 42
                        value
                    })
                    inside + value
                }
                0
                """;
        for (ProtosExecutionMode mode : new ProtosExecutionMode[] {
                ProtosExecutionMode.NORMAL, ProtosExecutionMode.DEBUG}) {
            Path file = directory.resolve("i095-b6a-" + mode + ".protos");
            Files.writeString(file, source, StandardCharsets.UTF_8);
            try (ProtosStandaloneHostedSession session =
                    ProtosStandaloneHostedSession.openForTesting(CORE, file, mode)) {
                assertEquals(ProtosExecutionOutcome.State.COMPLETED,
                        session.initialOutcome().state(), mode.toString());
                Value run = session.prepareTopLevel("run").executable();
                for (int i = 0; i < 3; i++) {
                    assertEquals(51, run.execute().asInt(), mode.toString());
                }
                assertNoTask(session);
            }
        }
        System.out.println("I095_B6A_NORMAL_DEBUG=PASS");
    }

    @Test
    void i095B6bMixedCaptureNormalDebugParity() throws Exception {
        String source = """
                run: () => {
                    outer: 40
                    shadow: 11
                    inside: true.ifTrue(() => {
                        shadow: 1
                        outer = outer + shadow
                        shadow = 2
                        outer + shadow
                    })
                    inside + outer + shadow
                }
                0
                """;
        for (ProtosExecutionMode mode : new ProtosExecutionMode[] {
                ProtosExecutionMode.NORMAL, ProtosExecutionMode.DEBUG}) {
            Path file = directory.resolve("i095-b6b-" + mode + ".protos");
            Files.writeString(file, source, StandardCharsets.UTF_8);
            try (ProtosStandaloneHostedSession session =
                    ProtosStandaloneHostedSession.openForTesting(CORE, file, mode)) {
                assertEquals(ProtosExecutionOutcome.State.COMPLETED,
                        session.initialOutcome().state(), mode.toString());
                Value run = session.prepareTopLevel("run").executable();
                for (int i = 0; i < 3; i++) {
                    assertEquals(95, run.execute().asInt(), mode.toString());
                }
                assertNoTask(session);
            }
        }
        System.out.println("I095_B6B_NORMAL_DEBUG=PASS");
    }

    @Test
    void normalModeEntersTheEligibleClosureHeaderless() throws Exception {
        try (ProtosStandaloneHostedSession session = open(ProtosExecutionMode.NORMAL)) {
            assertFalse(session.runtimeHostForTesting().isDebugMode());
            ProtosStandaloneHostedSession.PreparedTopLevel prepared =
                    session.prepareTopLevel("run");
            assertEquals(1, prepared.executable().execute().asInt());
            assertEquals(0, arguments(session, prepared.hostExecutableForTesting()).length);
            assertNoTask(session);
        }
        System.out.println("PERF032_NORMAL_HEADERLESS=PASS");
    }

    @Test
    void debugModeEntersTheSameClosureWithProvenance() throws Exception {
        try (ProtosStandaloneHostedSession session = open(ProtosExecutionMode.DEBUG)) {
            assertTrue(session.runtimeHostForTesting().isDebugMode());
            ProtosStandaloneHostedSession.PreparedTopLevel prepared =
                    session.prepareTopLevel("run");
            ProtosHostExecutableClosure adapter = prepared.hostExecutableForTesting();
            assertEquals(1, prepared.executable().execute().asInt());
            Object[] arguments = arguments(session, adapter);
            assertEquals(2, arguments.length, "DEBUG uses the ordinary compact header");
            assertSame(adapter.closure, arguments[0]);
            assertSame(adapter.caller, arguments[1]);
            assertNoTask(session);
        }
        System.out.println("PERF032_DEBUG_HEADER=PASS");
    }

    @Test
    void normalHostDoesNotStartDap() {
        try (ProtosPolyglotRuntimeHost host = ProtosPolyglotRuntimeHost.open()) {
            assertFalse(host.isDebugMode());
            assertThrows(IllegalStateException.class, host::debugEndpoint);
        }
        System.out.println("PERF032_NORMAL_NO_DAP=PASS");
    }

    @Test
    void debugModeDebuggerObservesTheExactLexicalScope() throws Exception {
        try (ProtosStandaloneHostedSession session = open(ProtosExecutionMode.DEBUG)) {
            Value executable = session.prepareTopLevel("run").executable();
            assertEquals(1, executable.execute().asInt(), "first call precedes the debugger");

            ScopeObservation observation = new ScopeObservation();
            try (DebuggerSession debugSession = startSession(session, observation)) {
                assertEquals(1, executable.execute().asInt());
            }
            observation.assertObserved("perf032-marker");
            assertNoTask(session);
        }
        System.out.println("PERF032_DEBUG_SCOPE=PASS");
    }

    @Test
    void debugModeCallPreparedBeforeTheDebuggerKeepsItsScope() throws Exception {
        try (ProtosStandaloneHostedSession session = open(ProtosExecutionMode.DEBUG)) {
            ProtosHostExecutableClosure adapter =
                    session.prepareTopLevel("run").hostExecutableForTesting();
            ProtosBytecodeRootNode.OrdinarySourceCall prepared =
                    session.processContextForTesting()
                            .callForRuntime(() -> prepareNoArguments(adapter));
            assertEquals(2, prepared.targetArguments().length);

            ScopeObservation observation = new ScopeObservation();
            try (DebuggerSession debugSession = startSession(session, observation)) {
                Object result =
                        session.processContextForTesting()
                                .callForRuntime(
                                        () ->
                                                prepared.finish(
                                                        ProtosBytecodeRootNode.EnterClosureCall
                                                                .ordinaryIndirect(
                                                                        prepared,
                                                                        IndirectCallNode.create())));
                assertEquals(1, InteropLibrary.getUncached().asInt(result));
            }
            observation.assertObserved("perf032-marker");
            assertNoTask(session);
        }
        System.out.println("PERF032_DEBUG_PREPARED_BEFORE_ATTACH=PASS");
    }

    @Test
    void normalModeScopeRequestFailsExplicitly() throws Exception {
        try (ProtosStandaloneHostedSession session = open(ProtosExecutionMode.NORMAL)) {
            ProtosHostExecutableClosure adapter =
                    session.prepareTopLevel("run").hostExecutableForTesting();

            // The Protos-controlled boundary: a headerless frame has a scope that cannot be built.
            session.processContextForTesting()
                    .callForRuntime(
                            () -> {
                                ProtosSemanticBytecodeRootNode root =
                                        (ProtosSemanticBytecodeRootNode)
                                                adapter.target.getRootNode();
                                root.getRootNodes().ensureComplete();
                                TagTreeNode location =
                                        (TagTreeNode) root.getBytecodeNode().getTagTree();
                                assertNotNull(location);
                                Object[] arguments = prepareNoArguments(adapter).targetArguments();
                                assertEquals(0, arguments.length, "tags do not change the ABI");
                                var frame =
                                        Truffle.getRuntime()
                                                .createVirtualFrame(
                                                        arguments, root.getFrameDescriptor());
                                assertTrue(ProtosBytecodeTagTreeNodeExports.hasScope(location, frame));
                                IllegalStateException rejected =
                                        assertThrows(
                                                IllegalStateException.class,
                                                () ->
                                                        ProtosBytecodeTagTreeNodeExports.getScope(
                                                                location, frame, true));
                                assertTrue(
                                        rejected.getMessage().contains("NORMAL execution mode"),
                                        rejected.getMessage());
                                return null;
                            });

            // The real Truffle debugger: the session itself is framework-owned and starts, but the
            // scope request fails instead of returning a silent null scope.
            AtomicInteger suspensions = new AtomicInteger();
            AtomicReference<Throwable> scopeFailure = new AtomicReference<>();
            AtomicReference<DebugScope> silentScope = new AtomicReference<>();
            Value executable = session.prepareTopLevel("run").executable();
            try (DebuggerSession debugSession =
                    debugger(session)
                            .startSession(
                                    event -> {
                                        suspensions.incrementAndGet();
                                        try {
                                            silentScope.set(event.getTopStackFrame().getScope());
                                        } catch (Throwable failure) {
                                            scopeFailure.set(failure);
                                        } finally {
                                            event.prepareContinue();
                                        }
                                    })) {
                debugSession.install(breakpoint(ProtosExecutionMode.NORMAL));
                assertEquals(1, executable.execute().asInt());
            }
            assertEquals(1, suspensions.get());
            assertNull(silentScope.get(), "NORMAL must not return a scope without provenance");
            assertTrue(
                    scopeFailure.get() instanceof DebugException,
                    "NORMAL scope request must fail explicitly: " + scopeFailure.get());
            assertNoTask(session);
        }
        System.out.println("PERF032_NORMAL_SCOPE_REJECTED=PASS");
    }

    @Test
    void normalAndDebugExecuteTheSameWholeInlineBody()
            throws Exception {
        String program = """
                run: () => {
                    true.ifTrue(() => {
                        1 + 2
                        3 * 4
                    })
                }
                0
                """;

        Path normalFile = directory.resolve(
                "i095-b2-normal.protos");
        Path debugFile = directory.resolve(
                "i095-b2-debug.protos");

        Files.writeString(
                normalFile, program, StandardCharsets.UTF_8);
        Files.writeString(
                debugFile, program, StandardCharsets.UTF_8);

        try (ProtosStandaloneHostedSession normal =
                        ProtosStandaloneHostedSession.openForTesting(
                                CORE, normalFile, ProtosExecutionMode.NORMAL);
                ProtosStandaloneHostedSession debug =
                        ProtosStandaloneHostedSession.openForTesting(
                                CORE, debugFile, ProtosExecutionMode.DEBUG)) {

            assertEquals(
                    ProtosExecutionOutcome.State.COMPLETED,
                    normal.initialOutcome().state());
            assertEquals(
                    ProtosExecutionOutcome.State.COMPLETED,
                    debug.initialOutcome().state());

            Value normalRun =
                    normal.prepareTopLevel("run").executable();
            Value debugRun =
                    debug.prepareTopLevel("run").executable();

            for (int iteration = 0; iteration < 3; iteration++) {
                assertEquals(12, normalRun.execute().asInt());
                assertEquals(12, debugRun.execute().asInt());
            }

            assertNoTask(normal);
            assertNoTask(debug);
        }

        System.out.println(
                "I095_B2_NORMAL_DEBUG_WHOLE_BODY=PASS");
    }

    @Test
    void i095WholeBlockCallerProvenanceParity() throws Exception {
        record Case(String name, String program, int expected) {}

        var cases = java.util.List.of(
                new Case(
                        "arithmetic",
                        """
                        run: () => {
                            true.ifTrue(() => {
                                1 + 2
                                3 * 4
                            })
                        }
                        0
                        """,
                        12),
                new Case(
                        "receiver",
                        """
                        run: () => {
                            obj: {
                                read: () => {
                                    (true.ifTrue(() => { this }) === this)
                                        .ifTrueIfFalse(() => { 41 }, () => { 0 })
                                }
                            }
                            obj.read()
                        }
                        0
                        """,
                        41),
                new Case(
                        "super",
                        """
                        run: () => {
                            base: {
                                read: () => { 41 }
                            }
                            middle: base {
                                read: () => {
                                    true.ifTrue(() => {
                                        super.read() + 1
                                    })
                                }
                            }
                            middle.read()
                        }
                        0
                        """,
                        42),
                new Case(
                        "handled-error",
                        """
                        run: () => {
                            Error.handle(
                                () => {
                                    true.ifTrue(() => { 1 / 0 })
                                    0
                                },
                                (caught) => { 73 })
                        }
                        0
                        """,
                        73));

        for (Case scenario : cases) {
            Path normalFile = directory.resolve(
                    "i095-b3-" + scenario.name() + "-normal.protos");
            Path debugFile = directory.resolve(
                    "i095-b3-" + scenario.name() + "-debug.protos");

            Files.writeString(
                    normalFile, scenario.program(),
                    StandardCharsets.UTF_8);
            Files.writeString(
                    debugFile, scenario.program(),
                    StandardCharsets.UTF_8);

            try (ProtosStandaloneHostedSession normal =
                            ProtosStandaloneHostedSession.openForTesting(
                                    CORE,
                                    normalFile,
                                    ProtosExecutionMode.NORMAL);
                    ProtosStandaloneHostedSession debug =
                            ProtosStandaloneHostedSession.openForTesting(
                                    CORE,
                                    debugFile,
                                    ProtosExecutionMode.DEBUG)) {

                assertEquals(
                        ProtosExecutionOutcome.State.COMPLETED,
                        normal.initialOutcome().state(),
                        scenario.name());
                assertEquals(
                        ProtosExecutionOutcome.State.COMPLETED,
                        debug.initialOutcome().state(),
                        scenario.name());

                Value normalRun =
                        normal.prepareTopLevel("run").executable();
                Value debugRun =
                        debug.prepareTopLevel("run").executable();

                for (int iteration = 0; iteration < 3; iteration++) {
                    assertEquals(
                            scenario.expected(),
                            normalRun.execute().asInt(),
                            "NORMAL: " + scenario.name());
                    assertEquals(
                            scenario.expected(),
                            debugRun.execute().asInt(),
                            "DEBUG: " + scenario.name());
                }

                assertNoTask(normal);
                assertNoTask(debug);
            }

            System.out.println(
                    "I095_B3_" + scenario.name()
                            .toUpperCase(java.util.Locale.ROOT)
                            .replace('-', '_')
                            + "=PASS");
        }
    }

    @Test
    void simultaneousNormalAndDebugHostsDoNotShareTheirMode() throws Exception {
        try (ProtosStandaloneHostedSession normal = open(ProtosExecutionMode.NORMAL);
                ProtosStandaloneHostedSession debug = open(ProtosExecutionMode.DEBUG)) {
            ProtosStandaloneHostedSession.PreparedTopLevel normalRun =
                    normal.prepareTopLevel("run");
            ProtosStandaloneHostedSession.PreparedTopLevel debugRun =
                    debug.prepareTopLevel("run");
            for (int index = 0; index < 3; index++) {
                assertEquals(1, normalRun.executable().execute().asInt());
                assertEquals(1, debugRun.executable().execute().asInt());
                assertEquals(
                        0, arguments(normal, normalRun.hostExecutableForTesting()).length);
                assertEquals(2, arguments(debug, debugRun.hostExecutableForTesting()).length);
            }
            assertFalse(normal.runtimeHostForTesting().isDebugMode());
            assertTrue(debug.runtimeHostForTesting().isDebugMode());
            assertNoTask(normal);
            assertNoTask(debug);
        }
        System.out.println("PERF032_MODE_ISOLATION=PASS");
    }

    @Test
    void standardEmbeddingSelectsTheModeThroughTheContextOption() {
        String core = CORE.toAbsolutePath().toString();
        try (Context normal =
                        Context.newBuilder(ProtosLanguage.ID)
                                .option("protos.CoreRoot", core)
                                .build();
                Context debug =
                        Context.newBuilder(ProtosLanguage.ID)
                                .option("protos.CoreRoot", core)
                                .allowExperimentalOptions(true)
                                .option("protos.Debug", "true")
                                .build()) {
            assertEquals(ProtosExecutionMode.NORMAL, mode(normal));
            assertEquals(ProtosExecutionMode.DEBUG, mode(debug));
            for (Context context : new Context[] {normal, debug}) {
                context.eval(ProtosLanguage.ID, "run: () => {\n    1\n}\n0\n");
                Value run = context.getBindings(ProtosLanguage.ID).getMember("run");
                assertEquals(1, run.execute().asInt());
            }
        }
        System.out.println("PERF032_EMBEDDING_MODE_OPTION=PASS");
    }

    private static ProtosExecutionMode mode(Context context) {
        context.initialize(ProtosLanguage.ID);
        context.enter();
        try {
            return ProtosLanguageContext.current().executionMode();
        } finally {
            context.leave();
        }
    }

    private ProtosStandaloneHostedSession open(ProtosExecutionMode mode) throws Exception {
        Path file = sourceFile(mode);
        Files.writeString(file, SOURCE, StandardCharsets.UTF_8);
        ProtosStandaloneHostedSession session =
                ProtosStandaloneHostedSession.openForTesting(CORE, file, mode);
        assertEquals(ProtosExecutionOutcome.State.COMPLETED, session.initialOutcome().state());
        return session;
    }

    private static Debugger debugger(ProtosStandaloneHostedSession session) {
        Instrument instrument =
                session.runtimeHostForTesting().engineForTesting().getInstruments().get("debugger");
        assertNotNull(instrument);
        Debugger debugger = instrument.lookup(Debugger.class);
        assertNotNull(debugger);
        return debugger;
    }

    private Path sourceFile(ProtosExecutionMode mode) {
        return directory.resolve("perf032-" + mode.name().toLowerCase() + ".protos");
    }

    private Breakpoint breakpoint(ProtosExecutionMode mode) {
        return Breakpoint.newBuilder(sourceFile(mode).toUri())
                .lineIs(BODY_LINE)
                .build();
    }

    private DebuggerSession startSession(
            ProtosStandaloneHostedSession session, ScopeObservation observation) {
        DebuggerSession debugSession = debugger(session).startSession(observation::onSuspend);
        debugSession.install(breakpoint(ProtosExecutionMode.DEBUG));
        return debugSession;
    }

    private static Object[] arguments(
            ProtosStandaloneHostedSession session, ProtosHostExecutableClosure adapter) {
        return session.processContextForTesting()
                .callForRuntime(() -> prepareNoArguments(adapter).targetArguments());
    }

    private static ProtosBytecodeRootNode.OrdinarySourceCall prepareNoArguments(
            ProtosHostExecutableClosure adapter) {
        try {
            return adapter.prepare(new Object[0], null);
        } catch (ArityException unexpected) {
            throw new AssertionError(unexpected);
        }
    }

    private static void assertNoTask(ProtosStandaloneHostedSession session) {
        assertEquals(
                0,
                session.processForTesting()
                        .rootActorForRuntime()
                        .executionDomain()
                        .liveTaskCount(),
                "no RootTask or ProtosTask may be created");
    }

    /** Records one breakpoint suspension and the captured lexical binding it observed. */
    private static final class ScopeObservation {
        private final AtomicInteger suspensions = new AtomicInteger();
        private final AtomicReference<String> marker = new AtomicReference<>();
        private final AtomicReference<Throwable> failure = new AtomicReference<>();

        void onSuspend(com.oracle.truffle.api.debug.SuspendedEvent event) {
            suspensions.incrementAndGet();
            try {
                assertEquals(BODY_LINE, event.getSourceSection().getStartLine());
                DebugScope scope = event.getTopStackFrame().getScope();
                assertNotNull(scope);
                DebugValue value = scope.getDeclaredValue("marker");
                assertNotNull(value, "captured lexical binding must be visible");
                marker.set(value.toDisplayString());
            } catch (Throwable unexpected) {
                failure.compareAndSet(null, unexpected);
            } finally {
                event.prepareContinue();
            }
        }

        void assertObserved(String expectedMarker) {
            if (failure.get() != null) {
                throw new AssertionError("debugger callback failed", failure.get());
            }
            assertEquals(1, suspensions.get());
            assertEquals(expectedMarker, marker.get());
        }
    }
}
