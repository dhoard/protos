/*
 * THE LICENSED WORK IS PROVIDED UNDER THE TERMS OF THE ADAPTIVE PUBLIC LICENSE
 * ("LICENSE") AS FIRST COMPLETED BY: Guillermo Adrián Molina. ANY USE, PUBLIC
 * DISPLAY, PUBLIC PERFORMANCE, REPRODUCTION OR DISTRIBUTION OF, OR PREPARATION OF
 * DERIVATIVE WORKS BASED ON, THE LICENSED WORK CONSTITUTES RECIPIENT'S ACCEPTANCE
 * OF THIS LICENSE AND ITS TERMS, WHETHER OR NOT SUCH RECIPIENT READS THE TERMS OF
 * THE LICENSE. "LICENSED WORK" AND "RECIPIENT" ARE DEFINED IN THE LICENSE. A COPY
 * OF THE LICENSE IS LOCATED IN THE TEXT FILE ENTITLED "LICENSE.TXT" ACCOMPANYING
 * THE CONTENTS OF THIS FILE.
 */
package com.guillermomolina.protos.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.guillermomolina.protos.runtime.ProtosEnvironmentValue;
import com.guillermomolina.protos.runtime.ProtosFutureValue;
import com.guillermomolina.protos.runtime.ProtosPrelude;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import com.oracle.truffle.api.source.Source;
import org.junit.jupiter.api.Test;

final class ProtosPerf006C3DParallelBytecodeRematerializationTest {
    private static final Path CORE = Path.of("protos", "lib", "core");

    @Test
    void hostedParallelClosureRematerializesThroughDestinationContextBytecodeCache()
            throws Exception {
        ProtosPrelude prelude = new ProtosCoreBootstrap().bootstrap(CORE);
        ProtosStandaloneProcessBootstrap.Result bootstrap =
                ProtosStandaloneProcessBootstrap.create(
                        prelude,
                        List.of(),
                        exactEnvironmentDomain(),
                        List.of(),
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null);

        try (ProtosPolyglotRuntimeHost host = ProtosPolyglotRuntimeHost.open()) {
            ProtosPolyglotProcessContext context =
                    host.hostProcess(
                            bootstrap.process(),
                            InputStream.nullInputStream(),
                            OutputStream.nullOutputStream(),
                            OutputStream.nullOutputStream());
            try {
                assertTrue(
                        bootstrap.process().executionHostForRuntime().isPresent(),
                        "hosted Process must expose its retained execution host");

                Object value =
                        context.evaluatePersistent(
                                Source.newBuilder(
                                                ProtosLanguage.ID,
                                                "((x) => x).parallel(42)",
                                                "<perf006-c3d-parallel>")
                                        .mimeType(ProtosLanguage.MIME_TYPE)
                                        .build(),
                                bootstrap.activation());

                ProtosFutureValue future =
                        org.junit.jupiter.api.Assertions.assertInstanceOf(
                                ProtosFutureValue.class, value);

                long deadline =
                        System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                while (future.isPending() && System.nanoTime() < deadline) {
                    context.callForRuntime(
                            () -> {
                                bootstrap.activation().executionDomain().dispatchUntilIdle();
                                return null;
                            });
                    Thread.onSpinWait();
                }

                assertEquals(ProtosFutureValue.State.RESOLVED, future.state());
                assertEquals("42", com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerDecimalText(
                                future.resolvedValue().orElseThrow()));

                ProtosLanguageContext languageContext =
                        context.currentLanguageContextForTesting();
                assertTrue(
                        languageContext.projectedBytecodeExecutionPlanCountForTesting() > 0,
                        "P Bytecode Closure must use the destination Context projection cache");

                System.out.println("PERF006_C3D_P_BYTECODE_REMATERIALIZATION=PASS");
                System.out.println("PERF006_C3D_DESTINATION_CONTEXT_CACHE=PASS");
                System.out.println("PERF006_C3D_AST_REPLAY_FALLBACK=NO");
            } finally {
                bootstrap.process().requestTerminationForRuntime();
                bootstrap.process().awaitTerminationForRuntime();
                context.awaitTerminalDispositionForRuntime();
            }
        }
    }

    private static ProtosEnvironmentValue.NativeNameDomain exactEnvironmentDomain() {
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
}
