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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.guillermomolina.protos.runtime.ProtosActorExecutionDomain;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.bytecode.TagTreeNode;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.interop.ArityException;
import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.UnsupportedMessageException;
import com.oracle.truffle.api.nodes.IndirectCallNode;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Value;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * PERF033-A: a prepared top-level Closure is exposed as one canonical Polyglot executable whose
 * {@code Value.execute()} enters through the framework host-to-guest boundary and the PERF025
 * compact direct-Closure ABI, without a RootTask, a ProtosTask, a session-owned Context entry, or
 * a rich callee activation for the literal workload.
 */
final class ProtosPerf033CanonicalCallableInteropTest {
    private static final Path CORE = Path.of("protos", "lib", "core");
    private static final String SOURCE =
            """
            run: () => {
                1
            }

            emit: () => {
                process.stdout().write(process.stdoutEncoding().encode("x")).value()
                2
            }

            owned: () => { ^ 5 }

            nested: () => {
                inner: () => { ^ 7 }
                inner()
                99
            }

            make: () => { () => { ^ 1 } }
            escaped: make()

            rebind: () => {
                run = () => { 42 }
                0
            }

            0
            """;

    /** Runtime frames that belong to the RootTask/session-entry envelope this path must avoid. */
    private static final List<String> FORBIDDEN_FRAMES =
            List.of(
                    ProtosRootTaskExecution.class.getName(),
                    "com.guillermomolina.protos.runtime.ProtosTask",
                    ProtosStandaloneHostedSession.class.getName(),
                    ProtosPolyglotExecutionContext.class.getName());

    @TempDir Path directory;

    @Test
    void preparedLiteralIsACanonicalExecutableValue() throws Exception {
        try (ProtosStandaloneHostedSession session = open(new RecordingStream())) {
            ProtosStandaloneHostedSession.PreparedTopLevel prepared =
                    session.prepareTopLevel("run");
            Value executable = prepared.executable();

            assertTrue(executable.canExecute());
            assertSame(executable, prepared.executable(), "created once at preparation");
            for (int index = 0; index < 3; index++) {
                assertEquals(1, executable.execute().asInt());
            }
            assertEquals(0, rootDomain(session).liveTaskCount());
        }
        System.out.println("CANONICAL_POLYGLOT_VALUE_EXECUTE=PASS");
    }

    @Test
    void literalCompactCallMaterializesNoActivationAndNoTask() throws Exception {
        try (ProtosStandaloneHostedSession session = open(new RecordingStream())) {
            ProtosHostExecutableClosure adapter =
                    session.prepareTopLevel("run").hostExecutableForTesting();
            Object[] arguments =
                    session.processContextForTesting()
                            .callForRuntime(
                                    () -> {
                                        ProtosBytecodeRootNode.OrdinarySourceCall call;
                                        try {
                                            call = adapter.prepare(new Object[0], null);
                                        } catch (ArityException unexpected) {
                                            throw new AssertionError(unexpected);
                                        }
                                        Object result =
                                                call.finish(
                                                        ProtosBytecodeRootNode.EnterClosureCall
                                                                .ordinaryIndirect(
                                                                        call,
                                                                        IndirectCallNode.create()));
                                        try {
                                            assertEquals(
                                                    1, InteropLibrary.getUncached().asInt(result));
                                        } catch (UnsupportedMessageException unexpected) {
                                            throw new AssertionError(unexpected);
                                        }
                                        assertSame(adapter.target, call.bodyTarget());
                                        return call.targetArguments();
                                    });
            // PERF032: a NORMAL-mode session enters the eligible literal root headerless.
            assertEquals(
                    0,
                    arguments.length,
                    "headerless literal call must not transport closure or caller");
            assertEquals(
                    0,
                    rootDomain(session).liveTaskCount(),
                    "literal call must not create a ProtosTask");
        }
        System.out.println("RICH_CALLEE_ACTIVATION_MATERIALIZED=NO");
        System.out.println("PROTOS_TASK_CREATED=NO");
    }

    private static ProtosBytecodeRootNode.OrdinarySourceCall prepareNoArguments(
            ProtosHostExecutableClosure adapter) {
        try {
            return adapter.prepare(new Object[0], null);
        } catch (ArityException unexpected) {
            throw new AssertionError(
                    "Call0 unexpectedly rejected zero arguments",
                    unexpected);
        }
    }

    @Test
    void debugModeTaggedLiteralUsesObservableCompactFrameAndExactScope() throws Exception {
        Path file = directory.resolve("perf033-debug.protos");
        Files.writeString(file, SOURCE, StandardCharsets.UTF_8);
        try (ProtosStandaloneHostedSession session =
                ProtosStandaloneHostedSession.openForTesting(
                        CORE, file, ProtosExecutionMode.DEBUG)) {
            assertEquals(
                    ProtosExecutionOutcome.State.COMPLETED, session.initialOutcome().state());
            ProtosHostExecutableClosure adapter =
                    session.prepareTopLevel("run").hostExecutableForTesting();

            session.processContextForTesting()
                    .callForRuntime(
                            () -> {
                                assertTrue(
                                        adapter.target.getRootNode()
                                                instanceof ProtosSemanticBytecodeRootNode);
                                ProtosSemanticBytecodeRootNode root =
                                        (ProtosSemanticBytecodeRootNode)
                                                adapter.target.getRootNode();

                                // DEBUG mode carries provenance before any tag tree exists.
                                assertEquals(
                                        2,
                                        prepareNoArguments(adapter).targetArguments().length,
                                        "DEBUG literal call carries the compact header");

                                // Simulates the Bytecode DSL tooling reparse.
                                root.getRootNodes().ensureComplete();

                                assertNotNull(
                                        root.getBytecodeNode().getTagTree(),
                                        "instrumented source must expose a tag tree");
                                assertTrue(
                                        root.getBytecodeNode().getTagTree()
                                                instanceof TagTreeNode);

                                TagTreeNode location =
                                        (TagTreeNode) root.getBytecodeNode().getTagTree();

                                ProtosBytecodeRootNode.OrdinarySourceCall prepared =
                                        prepareNoArguments(adapter);
                                Object[] arguments = prepared.targetArguments();

                                assertEquals(
                                        2,
                                        arguments.length,
                                        "DEBUG root must retain the compact header");
                                assertSame(adapter.closure, arguments[0]);
                                assertSame(adapter.caller, arguments[1]);

                                VirtualFrame frame =
                                        Truffle.getRuntime()
                                                .createVirtualFrame(
                                                        arguments,
                                                        root.getFrameDescriptor());

                                assertTrue(
                                        ProtosBytecodeTagTreeNodeExports.hasScope(
                                                location, frame));

                                try {
                                    Object first =
                                            ProtosBytecodeTagTreeNodeExports.getScope(
                                                    location, frame, true);
                                    Object second =
                                            ProtosBytecodeTagTreeNodeExports.getScope(
                                                    location, frame, true);

                                    assertTrue(InteropLibrary.getUncached().isScope(first));
                                    assertTrue(InteropLibrary.getUncached().isScope(second));

                                    Object published = arguments[0];
                                    assertSame(
                                            ProtosFrameArguments.activation(frame),
                                            published,
                                            "one exact activation is published");
                                    assertSame(
                                            published,
                                            ProtosFrameArguments.activation(arguments),
                                            "repeated observation reuses the activation");
                                } catch (UnsupportedMessageException unexpected) {
                                    throw new AssertionError(unexpected);
                                }

                                Object result =
                                        prepared.finish(
                                                ProtosBytecodeRootNode.EnterClosureCall
                                                        .ordinaryIndirect(
                                                                prepared,
                                                                IndirectCallNode.create()));

                                try {
                                    assertEquals(
                                            1,
                                            InteropLibrary.getUncached().asInt(result));
                                } catch (UnsupportedMessageException unexpected) {
                                    throw new AssertionError(unexpected);
                                }

                                assertEquals(0, rootDomain(session).liveTaskCount());
                                return null;
                            });
        }

        System.out.println("PERF032_TAGGED_LITERAL_SCOPE=PASS");
        System.out.println("PERF032_TAGGED_LITERAL_ACTIVATION_IDENTITY=PASS");
        System.out.println("PERF032_TAGGED_LITERAL_TASK=NONE");
    }

    @Test
    void executionEntersThroughTheFrameworkWithoutRootTaskOrSessionEntry() throws Exception {
        RecordingStream stdout = new RecordingStream();
        try (ProtosStandaloneHostedSession session = open(stdout)) {
            Value emit = session.prepareTopLevel("emit").executable();

            assertEquals(2, emit.execute().asInt());
            assertEquals("x", stdout.text());
            assertSame(Thread.currentThread(), stdout.writer);
            List<String> frames =
                    Arrays.stream(stdout.writerStack).map(StackTraceElement::getClassName).toList();
            assertTrue(
                    frames.stream()
                            .anyMatch(
                                    name ->
                                            name.startsWith(
                                                    ProtosHostExecutableClosure.class.getName())),
                    "entered through the interop adapter");
            for (String forbidden : FORBIDDEN_FRAMES) {
                assertFalse(frames.contains(forbidden), "unexpected frame " + forbidden);
            }
            assertEquals(0, rootDomain(session).liveTaskCount());
        }
        System.out.println("ROOT_TASK_CREATED=NO");
        System.out.println("PROTOS_EXPLICIT_CONTEXT_ENTER_LEAVE_PER_VALUE_CALL=NO");
    }

    @Test
    void preparedValueKeepsTheSelectedClosureAfterSlotReassignment() throws Exception {
        try (ProtosStandaloneHostedSession session = open(new RecordingStream())) {
            Value run = session.prepareTopLevel("run").executable();
            assertEquals(1, run.execute().asInt());
            assertEquals(
                    ProtosExecutionOutcome.State.COMPLETED,
                    session.invokeTopLevel("rebind").state());

            assertEquals(1, run.execute().asInt(), "no slot resolution per execution");
            assertEquals(42, session.prepareTopLevel("run").executable().execute().asInt());
        }
        System.out.println("CONTEXT_BOUND_VALUE_REUSED=PASS");
    }

    @Test
    void returnHomeAndControlSemanticsArePreserved() throws Exception {
        try (ProtosStandaloneHostedSession session = open(new RecordingStream())) {
            assertEquals(5, session.prepareTopLevel("owned").executable().execute().asInt());
            assertEquals(7, session.prepareTopLevel("nested").executable().execute().asInt());

            Value escaped = session.prepareTopLevel("escaped").executable();
            PolyglotException failure = assertThrows(PolyglotException.class, escaped::execute);
            assertTrue(failure.isGuestException());
            assertEquals(1, session.prepareTopLevel("run").executable().execute().asInt());
        }
        System.out.println("NON_LOCAL_RETURN=PASS");
    }

    @Test
    void unsupportedArgumentsFailThroughInteropArity() throws Exception {
        try (ProtosStandaloneHostedSession session = open(new RecordingStream())) {
            Value run = session.prepareTopLevel("run").executable();
            assertThrows(IllegalArgumentException.class, () -> run.execute(1));
            assertEquals(1, run.execute().asInt());
        }
        System.out.println("ARITY_REJECTED=PASS");
    }

    @Test
    void sessionCloseInvalidatesTheRetainedValue() throws Exception {
        ProtosStandaloneHostedSession session = open(new RecordingStream());
        Value run = session.prepareTopLevel("run").executable();
        assertEquals(1, run.execute().asInt());

        session.close();
        assertTrue(session.processContextForTesting().isClosedForTesting());
        assertThrows(IllegalStateException.class, run::execute);
        assertTrue(session.processContextForTesting().isClosedForTesting());
        System.out.println("CLOSED_SESSION_VALUE_REJECTED=PASS");
    }

    @Test
    void noPrivatePolyglotImplementationDependency() throws IOException {
        Path source =
                Path.of(
                        "src/main/java/com/guillermomolina/protos/execution",
                        "ProtosHostExecutableClosure.java");
        String text = Files.readString(source, StandardCharsets.UTF_8);
        assertNotNull(text);
        assertFalse(text.contains("com.oracle.truffle.polyglot"));
        assertFalse(text.contains("HostToGuestRootNode;"));
    }

    private static ProtosActorExecutionDomain rootDomain(ProtosStandaloneHostedSession session) {
        return session.processForTesting().rootActorForRuntime().executionDomain();
    }

    private ProtosStandaloneHostedSession open(OutputStream stdout) throws IOException {
        Path file = directory.resolve("perf033.protos");
        Files.writeString(file, SOURCE, StandardCharsets.UTF_8);
        ProtosStandaloneHostedSession session =
                ProtosStandaloneHostedSession.open(
                        CORE,
                        file,
                        List.of(),
                        InputStream.nullInputStream(),
                        stdout,
                        OutputStream.nullOutputStream());
        assertEquals(ProtosExecutionOutcome.State.COMPLETED, session.initialOutcome().state());
        return session;
    }

    /** Records the guest writer thread and its stack; guest stdout writes run synchronously. */
    private static final class RecordingStream extends OutputStream {
        private final StringBuilder text = new StringBuilder();
        private volatile Thread writer;
        private volatile StackTraceElement[] writerStack = new StackTraceElement[0];

        @Override
        public synchronized void write(int value) {
            write(new byte[] {(byte) value}, 0, 1);
        }

        @Override
        public synchronized void write(byte[] bytes, int offset, int length) {
            writer = Thread.currentThread();
            writerStack = writer.getStackTrace();
            text.append(new String(bytes, offset, length, StandardCharsets.UTF_8));
        }

        synchronized String text() {
            return text.toString();
        }
    }
}
