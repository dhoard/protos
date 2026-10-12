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
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.guillermomolina.protos.runtime.ProtosActivation;
import com.guillermomolina.protos.runtime.ProtosArrayValue;
import com.guillermomolina.protos.runtime.ProtosClosureValue;
import com.guillermomolina.protos.runtime.ProtosFutureValue;
import com.guillermomolina.protos.runtime.ProtosNullValue;
import com.guillermomolina.protos.runtime.ProtosPrelude;
import com.guillermomolina.protos.runtime.ProtosStringValue;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * BUG010 regression: normal Test Tool progress must be observable from terminal logical Case
 * events while the invocation is still running, not only after every logical Case has completed.
 *
 * <p>Before the fix, {@code protos/tools/test/Main.protos} materialized every suite's D120
 * progress state and drove {@code Progress.observer(...)} only after {@code
 * LogicalCaseRunner.run(...).value()} had already awaited every logical Case, so the reporting
 * backend never received a milestone line while any Case was still in flight. This test drives the
 * exact production {@code Progress.protos} state machine through the new {@code
 * completionObserver} seam on {@code LogicalCaseRunner.run} — the same seam {@code Main.protos}
 * now wires to its per-suite {@code Progress.observer(...)} — using the same deterministic
 * manually-resolved-Future style as {@link ProtosTestToolLogicalCaseRunnerTest}, so the timing
 * claim rests on controlled synchronization rather than sleeps.
 *
 * <p>Guest case output is never part of this call path: {@code executorAsync} below is a native
 * host stub with no stdout of its own, exactly mirroring how {@code
 * protos/tools/test/Main.protos} keeps guest stdout/stderr private to each Case's own Process
 * execution (see {@code docs/guide/tools/test-tool.md}, "Standard output is private to the case
 * execution") while Test Tool progress travels this separate, presentation-only reporting path.
 */
final class ProtosTestToolBug010ProgressTemporalVisibilityTest {
    private static final Path CORE = Path.of("protos", "lib", "core");
    private static final Path STANDARD_LIBRARY = Path.of("protos", "lib");
    private static final Path TOOL_ROOT = Path.of("protos", "tools", "test");
    private static final Path SHARED_ROOT = Path.of("protos", "tools", "shared");

    @Test
    void progressMilestoneIsObservableBeforeRemainingLogicalCasesComplete() throws Exception {
        ProtosBundledToolModuleResolver resolver =
                new ProtosBundledToolModuleResolver(
                        "test",
                        TOOL_ROOT,
                        SHARED_ROOT,
                        new ProtosStandardLibraryModuleResolver(STANDARD_LIBRARY));

        ProtosPrelude prelude = new ProtosCoreBootstrap().bootstrap(CORE, resolver);
        ProtosActivation activation = prelude.newModuleActivation();

        ArrayList<String> reportedLines = new ArrayList<>();
        Map<String, ProtosFutureValue> pending = new LinkedHashMap<>();

        ProtosClosureValue emitLine =
                ProtosClosureValue.nativeClosure(
                        (caller, arguments) -> {
                            assertEquals(1, arguments.size());
                            reportedLines.add(
                                    assertInstanceOf(ProtosStringValue.class, arguments.get(0))
                                            .value());
                            return ProtosNullValue.INSTANCE;
                        });

        ProtosClosureValue executorAsync =
                ProtosClosureValue.nativeClosure(
                        (caller, arguments) -> {
                            assertEquals(3, arguments.size());

                            ProtosStringValue selector =
                                    assertInstanceOf(ProtosStringValue.class, arguments.get(2));

                            ProtosFutureValue future =
                                    new ProtosFutureValue(
                                            prelude.futurePrototype(), caller.executionDomain());

                            pending.put(selector.value(), future);
                            caller.executionDomain().registerActorNonTaskFutureForRuntime(future);

                            return future;
                        });

        activation.context().createLocalSlot("emitLine", emitLine);
        activation.context().createLocalSlot("executorAsync", executorAsync);

        String source =
                """
                Discovery: import("self:Discovery")
                Runner: import("self:LogicalCaseRunner")
                Progress: import("self:Progress")
                TestValue: import("std:test/Test")

                suite: {
                    tests: Array(
                        TestValue("first", () => null),
                        TestValue("second", () => null),
                        TestValue("third", () => null)
                    )
                }

                projection:
                    Discovery.projectionFromModule(
                        "suite.protos",
                        suite
                    )

                // Mirrors protos/tools/test/Main.protos exactly: one D120 progress
                // state/observer created before scheduling, then driven per Case
                // through the completionObserver seam LogicalCaseRunner exposes.
                state: Progress.begin("phase", 3, emitLine)
                observer: Progress.observer(state)

                completionObserver: (index, completion) => {
                    observer(null, null, false)
                    null
                }

                Runner.run(
                    Array(projection),
                    executorAsync,
                    1,
                    null,
                    completionObserver
                )
                """;

        Object runnerValue = ProtosTestExecutionSupport.evaluate(source, activation);
        ProtosFutureValue run = assertInstanceOf(ProtosFutureValue.class, runnerValue);

        ProtosTestExecutionSupport.dispatchUntilIdle(activation.executionDomain());

        // Progress.begin() emits its initial milestone synchronously, before any
        // logical Case is even admitted.
        assertEquals(List.of("[phase] 0/3"), reportedLines);
        assertEquals(ProtosFutureValue.State.PENDING, run.state());
        assertTrue(pending.containsKey("first"));
        assertFalse(pending.containsKey("second"));

        // Resolve only the first of three Cases. The invocation as a whole must
        // remain incomplete while this progress milestone is already observable
        // on the reporting backend.
        pending.get("first").resolve(new ProtosStringValue("done-first"), activation);
        ProtosTestExecutionSupport.dispatchUntilIdle(activation.executionDomain());

        assertEquals(
                List.of("[phase] 0/3", "[phase] 1/3"),
                reportedLines,
                "a progress milestone must reach the reporting backend while the"
                        + " second and third logical Cases are still pending");
        assertEquals(
                ProtosFutureValue.State.PENDING,
                run.state(),
                "the invocation must not have completed when this progress milestone"
                        + " became observable");
        assertTrue(pending.containsKey("second"));
        assertEquals(ProtosFutureValue.State.PENDING, pending.get("second").state());
        assertFalse(pending.containsKey("third"));

        pending.get("second").resolve(new ProtosStringValue("done-second"), activation);
        ProtosTestExecutionSupport.dispatchUntilIdle(activation.executionDomain());

        assertEquals(
                List.of("[phase] 0/3", "[phase] 1/3", "[phase] 2/3"),
                reportedLines);
        assertEquals(ProtosFutureValue.State.PENDING, run.state());
        assertTrue(pending.containsKey("third"));

        pending.get("third").resolve(new ProtosStringValue("done-third"), activation);
        dispatchUntilTerminal(run, activation);

        assertEquals(ProtosFutureValue.State.RESOLVED, run.state());

        // Stable logical order is unaffected by the incremental progress wiring:
        // completions remain in original TestPlan order regardless of physical
        // completion order.
        ProtosArrayValue results =
                assertInstanceOf(ProtosArrayValue.class, run.resolvedValue().orElseThrow());
        assertEquals(3, results.indexedSize());
        assertEquals("done-first", stringAt(results, 0));
        assertEquals("done-second", stringAt(results, 1));
        assertEquals("done-third", stringAt(results, 2));
    }

    private static String stringAt(ProtosArrayValue values, int index) {
        return assertInstanceOf(
                        ProtosStringValue.class, values.indexedAt(index))
                .value();
    }

    private static void dispatchUntilTerminal(ProtosFutureValue future, ProtosActivation activation)
            throws Exception {
        int dispatches = 0;

        while (future.isPending()) {
            if (!ProtosTestExecutionSupport.dispatchOne(activation.executionDomain())) {
                Thread.sleep(1);
            }

            dispatches++;
            if (dispatches > 1000) {
                throw new AssertionError("logical Case runner did not become terminal");
            }
        }
    }
}
