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
import static org.junit.jupiter.api.Assertions.assertSame;
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

final class ProtosTestToolLogicalCaseRunnerTest {
    private static final Path CORE =
            Path.of("protos", "lib", "core");
    private static final Path STANDARD_LIBRARY =
            Path.of("protos", "lib");
    private static final Path TOOL_ROOT =
            Path.of("protos", "tools", "test");
    private static final Path SHARED_ROOT =
            Path.of("protos", "tools", "shared");

    @Test
    void completedLanePullsNextCaseWithoutWaitingForUnrelatedRunningCase()
            throws Exception {
        ProtosBundledToolModuleResolver resolver =
                new ProtosBundledToolModuleResolver(
                        "test",
                        TOOL_ROOT,
                        SHARED_ROOT,
                        new ProtosStandardLibraryModuleResolver(
                                STANDARD_LIBRARY));

        ProtosPrelude prelude =
                new ProtosCoreBootstrap()
                        .bootstrap(
                                CORE,
                                resolver);

        ProtosActivation activation =
                prelude.newModuleActivation();

        ArrayList<String> admissions =
                new ArrayList<>();
        Map<String, ProtosFutureValue> pending =
                new LinkedHashMap<>();

        ProtosClosureValue executorAsync =
                ProtosClosureValue.nativeClosure(
                        (caller, arguments) -> {
                            assertEquals(3, arguments.size());

                            ProtosStringValue selector =
                                    assertInstanceOf(
                                            ProtosStringValue.class,
                                            arguments.get(2));

                            admissions.add(selector.value());

                            ProtosFutureValue future =
                                    new ProtosFutureValue(
                                            prelude.futurePrototype(),
                                            caller.executionDomain());

                            pending.put(
                                    selector.value(),
                                    future);

                            caller.executionDomain()
                                    .registerActorNonTaskFutureForRuntime(
                                            future);

                            return future;
                        });

        activation
                .context()
                .createLocalSlot(
                        "executorAsync",
                        executorAsync);

        String source =
                """
                Discovery: import("self:Discovery")
                Runner: import("self:LogicalCaseRunner")
                TestValue: import("std:test/Test")

                suite: {
                    tests: Array(
                        TestValue("first", () => null),
                        TestValue("second", () => null),
                        TestValue("third", () => null),
                        TestValue("fourth", () => null)
                    )
                }

                projection:
                    Discovery.projectionFromModule(
                        "suite.protos",
                        suite
                    )

                Runner.run(
                    Array(projection),
                    executorAsync,
                    2
                )
                """;

        Object runnerValue =
                ProtosTestExecutionSupport.evaluate(
                        source,
                        activation);

        ProtosFutureValue run =
                assertInstanceOf(
                        ProtosFutureValue.class,
                        runnerValue);

        ProtosTestExecutionSupport.dispatchUntilIdle(
                activation.executionDomain());

        assertEquals(
                List.of("first", "second"),
                admissions);

        assertEquals(
                ProtosFutureValue.State.PENDING,
                run.state());

        pending.get("first")
                .resolve(
                        new ProtosStringValue("done-first"),
                        activation);

        assertTrue(
                ProtosTestExecutionSupport.dispatchOne(activation.executionDomain()));

        assertEquals(
                List.of("first", "second", "third"),
                admissions,
                "a free lane must admit third without waiting for second");

        assertEquals(
                ProtosFutureValue.State.PENDING,
                pending.get("second").state());

        pending.get("third")
                .resolve(
                        new ProtosStringValue("done-third"),
                        activation);

        assertTrue(
                ProtosTestExecutionSupport.dispatchOne(activation.executionDomain()));

        assertEquals(
                List.of(
                        "first",
                        "second",
                        "third",
                        "fourth"),
                admissions);

        pending.get("second")
                .resolve(
                        new ProtosStringValue("done-second"),
                        activation);

        pending.get("fourth")
                .resolve(
                        new ProtosStringValue("done-fourth"),
                        activation);

        dispatchUntilTerminal(
                run,
                activation);

        assertEquals(
                ProtosFutureValue.State.RESOLVED,
                run.state());

        ProtosArrayValue results =
                assertInstanceOf(
                        ProtosArrayValue.class,
                        run.resolvedValue().orElseThrow());

        assertEquals(4,
                results.indexedSize());

        assertEquals(
                "done-first",
                stringAt(results, 0));
        assertEquals(
                "done-second",
                stringAt(results, 1));
        assertEquals(
                "done-third",
                stringAt(results, 2));
        assertEquals(
                "done-fourth",
                stringAt(results, 3));

        assertTrue(results.isFrozen());
    }

    @Test
    void lifecycleObserverKeepsPendingCaseIdentifiableUntilTerminal()
            throws Exception {
        ProtosBundledToolModuleResolver resolver =
                new ProtosBundledToolModuleResolver(
                        "test",
                        TOOL_ROOT,
                        SHARED_ROOT,
                        new ProtosStandardLibraryModuleResolver(
                                STANDARD_LIBRARY));

        ProtosPrelude prelude =
                new ProtosCoreBootstrap()
                        .bootstrap(
                                CORE,
                                resolver);

        ProtosActivation activation =
                prelude.newModuleActivation();

        Map<String, ProtosFutureValue> pending =
                new LinkedHashMap<>();

        ArrayList<String> lifecycleKinds =
                new ArrayList<>();
        ArrayList<Object> lifecycleRefs =
                new ArrayList<>();
        ArrayList<Object> lifecycleResults =
                new ArrayList<>();

        ProtosClosureValue executorAsync =
                ProtosClosureValue.nativeClosure(
                        (caller, arguments) -> {
                            assertEquals(3, arguments.size());

                            ProtosStringValue selector =
                                    assertInstanceOf(
                                            ProtosStringValue.class,
                                            arguments.get(2));

                            ProtosFutureValue future =
                                    new ProtosFutureValue(
                                            prelude.futurePrototype(),
                                            caller.executionDomain());

                            pending.put(
                                    selector.value(),
                                    future);

                            caller.executionDomain()
                                    .registerActorNonTaskFutureForRuntime(
                                            future);

                            return future;
                        });

        ProtosClosureValue lifecycleObserver =
                ProtosClosureValue.nativeClosure(
                        (caller, arguments) -> {
                            assertEquals(3, arguments.size());

                            ProtosStringValue kind =
                                    assertInstanceOf(
                                            ProtosStringValue.class,
                                            arguments.get(0));

                            lifecycleKinds.add(
                                    kind.value());
                            lifecycleRefs.add(
                                    arguments.get(1));
                            lifecycleResults.add(
                                    arguments.get(2));

                            return ProtosNullValue.INSTANCE;
                        });


        activation
                .context()
                .createLocalSlot(
                        "executorAsync",
                        executorAsync);

        activation
                .context()
                .createLocalSlot(
                        "lifecycleObserver",
                        lifecycleObserver);

        String source =
                """
                Discovery: import("self:Discovery")
                Runner: import("self:LogicalCaseRunner")
                TestValue: import("std:test/Test")

                suite: {
                    tests: Array(
                        TestValue("only", () => null)
                    )
                }

                projection:
                    Discovery.projectionFromModule(
                        "suite.protos",
                        suite
                    )

                Runner.run(
                    Array(projection),
                    executorAsync,
                    1,
                    lifecycleObserver
                )
                """;

        Object runnerValue =
                ProtosTestExecutionSupport.evaluate(
                        source,
                        activation);

        ProtosFutureValue run =
                assertInstanceOf(
                        ProtosFutureValue.class,
                        runnerValue);

        ProtosTestExecutionSupport.dispatchUntilIdle(
                activation.executionDomain());

        assertEquals(
                List.of("started"),
                lifecycleKinds);

        assertEquals(
                ProtosFutureValue.State.PENDING,
                run.state());

        assertEquals(
                1,
                lifecycleRefs.size());

        assertSame(
                ProtosNullValue.INSTANCE,
                lifecycleResults.get(0));

        pending.get("only")
                .resolve(
                        new ProtosStringValue("done-only"),
                        activation);

        dispatchUntilTerminal(
                run,
                activation);

        assertEquals(
                List.of(
                        "started",
                        "terminal"),
                lifecycleKinds);

        assertEquals(
                2,
                lifecycleRefs.size());

        assertSame(
                lifecycleRefs.get(0),
                lifecycleRefs.get(1));

        assertEquals(
                "done-only",
                assertInstanceOf(
                                ProtosStringValue.class,
                                lifecycleResults.get(1))
                        .value());
    }

    @Test
    void jobsCapacityIsGlobalAcrossSourceProjections()
            throws Exception {
        ProtosBundledToolModuleResolver resolver =
                new ProtosBundledToolModuleResolver(
                        "test",
                        TOOL_ROOT,
                        SHARED_ROOT,
                        new ProtosStandardLibraryModuleResolver(
                                STANDARD_LIBRARY));

        ProtosPrelude prelude =
                new ProtosCoreBootstrap()
                        .bootstrap(
                                CORE,
                                resolver);

        ProtosActivation activation =
                prelude.newModuleActivation();

        ArrayList<String> admissions =
                new ArrayList<>();
        Map<String, ProtosFutureValue> pending =
                new LinkedHashMap<>();

        ProtosClosureValue executorAsync =
                ProtosClosureValue.nativeClosure(
                        (caller, arguments) -> {
                            assertEquals(3, arguments.size());

                            ProtosStringValue sourceAssociation =
                                    assertInstanceOf(
                                            ProtosStringValue.class,
                                            arguments.get(0));

                            ProtosStringValue selector =
                                    assertInstanceOf(
                                            ProtosStringValue.class,
                                            arguments.get(2));

                            String key =
                                    sourceAssociation.value()
                                            + ":"
                                            + selector.value();

                            admissions.add(key);

                            ProtosFutureValue future =
                                    new ProtosFutureValue(
                                            prelude.futurePrototype(),
                                            caller.executionDomain());

                            pending.put(
                                    key,
                                    future);

                            caller.executionDomain()
                                    .registerActorNonTaskFutureForRuntime(
                                            future);

                            return future;
                        });


        activation
                .context()
                .createLocalSlot(
                        "executorAsync",
                        executorAsync);

        String source =
                """
                Discovery: import("self:Discovery")
                Runner: import("self:LogicalCaseRunner")
                TestValue: import("std:test/Test")

                suiteA: {
                    tests: Array(
                        TestValue("a-first", () => null),
                        TestValue("a-second", () => null)
                    )
                }

                suiteB: {
                    tests: Array(
                        TestValue("b-first", () => null),
                        TestValue("b-second", () => null)
                    )
                }

                projectionA:
                    Discovery.projectionFromModule(
                        "source-a.protos",
                        suiteA
                    )

                projectionB:
                    Discovery.projectionFromModule(
                        "source-b.protos",
                        suiteB
                    )

                Runner.run(
                    Array(
                        projectionA,
                        projectionB
                    ),
                    executorAsync,
                    2
                )
                """;

        Object runnerValue =
                ProtosTestExecutionSupport.evaluate(
                        source,
                        activation);

        ProtosFutureValue run =
                assertInstanceOf(
                        ProtosFutureValue.class,
                        runnerValue);

        ProtosTestExecutionSupport.dispatchUntilIdle(
                activation.executionDomain());

        assertEquals(
                List.of(
                        "source-a.protos:a-first",
                        "source-a.protos:a-second"),
                admissions);

        pending.get("source-a.protos:a-first")
                .resolve(
                        new ProtosStringValue("done-a-first"),
                        activation);

        ProtosTestExecutionSupport.dispatchUntilIdle(
                activation.executionDomain());

        assertEquals(
                List.of(
                        "source-a.protos:a-first",
                        "source-a.protos:a-second",
                        "source-b.protos:b-first"),
                admissions,
                "a free global lane must cross into the next source");

        assertEquals(
                ProtosFutureValue.State.PENDING,
                pending.get("source-a.protos:a-second").state());

        pending.get("source-b.protos:b-first")
                .resolve(
                        new ProtosStringValue("done-b-first"),
                        activation);

        ProtosTestExecutionSupport.dispatchUntilIdle(
                activation.executionDomain());

        assertEquals(
                List.of(
                        "source-a.protos:a-first",
                        "source-a.protos:a-second",
                        "source-b.protos:b-first",
                        "source-b.protos:b-second"),
                admissions);

        assertEquals(
                ProtosFutureValue.State.PENDING,
                pending.get("source-a.protos:a-second").state());

        pending.get("source-a.protos:a-second")
                .resolve(
                        new ProtosStringValue("done-a-second"),
                        activation);

        pending.get("source-b.protos:b-second")
                .resolve(
                        new ProtosStringValue("done-b-second"),
                        activation);

        dispatchUntilTerminal(
                run,
                activation);

        assertEquals(
                ProtosFutureValue.State.RESOLVED,
                run.state());

        ProtosArrayValue results =
                assertInstanceOf(
                        ProtosArrayValue.class,
                        run.resolvedValue().orElseThrow());

        assertEquals(4,
                results.indexedSize());

        assertEquals(
                "done-a-first",
                stringAt(results, 0));
        assertEquals(
                "done-a-second",
                stringAt(results, 1));
        assertEquals(
                "done-b-first",
                stringAt(results, 2));
        assertEquals(
                "done-b-second",
                stringAt(results, 3));

        assertTrue(results.isFrozen());
    }

    private static String stringAt(
            ProtosArrayValue values,
            int index) {
        return assertInstanceOf(
                        ProtosStringValue.class,
                        values.indexedAt(
                                index))
                .value();
    }

    private static void dispatchUntilTerminal(
            ProtosFutureValue future,
            ProtosActivation activation)
            throws Exception {
        int dispatches = 0;

        while (future.isPending()) {
            if (!ProtosTestExecutionSupport.dispatchOne(activation.executionDomain())) {
                Thread.sleep(1);
            }

            dispatches++;
            if (dispatches > 1000) {
                throw new AssertionError(
                        "logical Case runner did not become terminal");
            }
        }
    }
}
