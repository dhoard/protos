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
package com.guillermomolina.protos.embedding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.guillermomolina.protos.execution.ProtosExecutionOutcome;
import com.guillermomolina.protos.execution.ProtosStandaloneHostedSession;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Exercises the reusable JVM embedding session exactly as an external Java consumer would. */
final class ProtosStandaloneHostedSessionEmbeddingTest {
    private static final Path CORE = Path.of("protos", "lib", "core");
    // PERF021 owns benchmark-scale inputs; this JUnit proves repeated session execution.
    private static final int REPEATS = 2;

    @TempDir Path directory;

    @Test
    void recursiveFibonacciRunIsInvokedRepeatedlyInOneSession() throws Exception {
        assertRepeatedRun(
                """
                fibonacci: (n) => {
                    result: n

                    (n >= 2).ifTrue() {
                        result = fibonacci(n - 1) + fibonacci(n - 2)
                    }

                    result
                }

                run: () => {
                    fibonacci(10)
                }

                run()
                """,
                55L);
    }

    @Test
    void recursiveFactorialRunIsInvokedRepeatedlyInOneSession() throws Exception {
        assertRepeatedRun(
                """
                factorial: (n) => {
                    result: 1

                    (n > 1).ifTrue() {
                        result = n * factorial(n - 1)
                    }

                    result
                }

                run: () => {
                    factorial(8)
                }

                run()
                """,
                40320L);
    }

    /**
     * Mutable module state survives across calls only when every call enters the one live entry
     * module of one live Process; a fresh {@code executeFile} per call would restart at 1.
     */
    @Test
    void repeatedInvocationsShareTheLiveEntryModuleState() throws Exception {
        try (ProtosStandaloneHostedSession session = open("counter: 0\n"
                + "run: () => {\n    counter = counter + 1\n    counter\n}\n"
                + "run()\n")) {
            assertInteger(1, session.initialOutcome());
            for (int expected = 2; expected < 2 + REPEATS; expected++) {
                assertInteger(expected, session.invokeTopLevel("run"));
            }
        }
    }

    @Test
    void absentOrNonClosureTopLevelSlotIsRejected() throws Exception {
        try (ProtosStandaloneHostedSession session = open("value: 7\nvalue\n")) {
            assertInteger(7, session.initialOutcome());
            assertThrows(IllegalArgumentException.class, () -> session.invokeTopLevel("missing"));
            assertThrows(IllegalArgumentException.class, () -> session.invokeTopLevel("value"));
        }
    }

    @Test
    void closedSessionRejectsInvocationAndCloseIsIdempotent() throws Exception {
        ProtosStandaloneHostedSession session = open("run: () => {\n    1\n}\nrun()\n");
        session.close();
        session.close();
        assertThrows(IllegalStateException.class, () -> session.invokeTopLevel("run"));
    }

    @Test
    void preparedTopLevelIsInvokedRepeatedlyAndSharesLiveModuleState() throws Exception {
        try (ProtosStandaloneHostedSession session = open("counter: 0\n"
                + "run: () => {\n    counter = counter + 1\n    counter\n}\n"
                + "run()\n")) {
            assertInteger(1, session.initialOutcome());
            ProtosStandaloneHostedSession.PreparedTopLevel run = session.prepareTopLevel("run");
            // Preparation does not execute the Closure.
            assertInteger(2, run.invoke());
            assertInteger(3, run.invoke());
            // Dynamic and prepared invocation address the same live entry module.
            assertInteger(4, session.invokeTopLevel("run"));
            assertInteger(5, run.invoke());
        }
    }

    /**
     * The prepared handle keeps the Closure selected at preparation; {@code invokeTopLevel} reads
     * the slot on every call and therefore observes the guest's reassignment.
     */
    @Test
    void preparedTopLevelDoesNotFollowReassignmentButInvokeTopLevelDoes() throws Exception {
        try (ProtosStandaloneHostedSession session = open("run: () => {\n    1\n}\n"
                + "swap: () => {\n    run = () => {\n        2\n    }\n    0\n}\n"
                + "run()\n")) {
            assertInteger(1, session.initialOutcome());
            ProtosStandaloneHostedSession.PreparedTopLevel prepared = session.prepareTopLevel("run");
            assertInteger(1, prepared.invoke());

            assertInteger(0, session.invokeTopLevel("swap"));

            assertInteger(1, prepared.invoke());
            assertInteger(2, session.invokeTopLevel("run"));
            assertInteger(1, prepared.invoke());
            // Preparing again selects the replacement.
            assertInteger(2, session.prepareTopLevel("run").invoke());
        }
    }

    @Test
    void preparationRejectsAbsentAndNonClosureTopLevelSlots() throws Exception {
        try (ProtosStandaloneHostedSession session = open("value: 7\nvalue\n")) {
            assertInteger(7, session.initialOutcome());
            assertThrows(IllegalArgumentException.class, () -> session.prepareTopLevel("missing"));
            assertThrows(IllegalArgumentException.class, () -> session.prepareTopLevel("value"));
        }
    }

    @Test
    void preparedTopLevelIsRejectedAfterSessionClose() throws Exception {
        ProtosStandaloneHostedSession session = open("run: () => {\n    1\n}\nrun()\n");
        ProtosStandaloneHostedSession.PreparedTopLevel run = session.prepareTopLevel("run");
        assertInteger(1, run.invoke());
        session.close();
        assertThrows(IllegalStateException.class, run::invoke);
        assertThrows(IllegalStateException.class, () -> session.prepareTopLevel("run"));
    }

    @Test
    void preparedTopLevelReportsGuestFailureAsFailedOutcomeAndStaysUsable() throws Exception {
        try (ProtosStandaloneHostedSession session = open("mode: 0\n"
                + "run: () => {\n    mode = mode + 1\n    (mode == 2).ifTrue() {\n"
                + "        1.definitelyMissing()\n    }\n    mode\n}\n"
                + "run()\n")) {
            assertInteger(1, session.initialOutcome());
            ProtosStandaloneHostedSession.PreparedTopLevel run = session.prepareTopLevel("run");
            ProtosExecutionOutcome failed = run.invoke();
            assertEquals(ProtosExecutionOutcome.State.FAILED, failed.state());
            assertInteger(3, run.invoke());
        }
    }

    private void assertRepeatedRun(String source, long expected) throws Exception {
        try (ProtosStandaloneHostedSession session = open(source)) {
            assertEquals(ProtosExecutionOutcome.State.COMPLETED, session.initialOutcome().state());
            assertEquals(String.valueOf(String.valueOf(expected)), com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerDecimalText(session.initialOutcome().value()));
            for (int call = 0; call < REPEATS; call++) {
                ProtosExecutionOutcome outcome = session.invokeTopLevel("run");
                assertEquals(ProtosExecutionOutcome.State.COMPLETED, outcome.state());
                assertEquals(String.valueOf(String.valueOf(expected)), com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerDecimalText(outcome.value()));
            }
        }
    }

    private static void assertInteger(long expected, ProtosExecutionOutcome outcome) {
        assertEquals(ProtosExecutionOutcome.State.COMPLETED, outcome.state());
        assertEquals(Long.toString(expected), com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerDecimalText(outcome.value()));
    }

    private ProtosStandaloneHostedSession open(String source) throws Exception {
        Path file = directory.resolve("workload.protos");
        Files.writeString(file, source, StandardCharsets.UTF_8);
        return ProtosStandaloneHostedSession.open(CORE, file);
    }
}
