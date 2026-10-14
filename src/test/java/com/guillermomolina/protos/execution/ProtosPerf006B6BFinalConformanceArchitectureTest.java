/*
 * THE LICENSED WORK IS PROVIDED UNDER THE TERMS OF THE ADAPTIVE PUBLIC LICENSE
 * ("LICENSE") AS FIRST COMPLETED BY: Guillermo Adrián Molina. See LICENSE.TXT.
 */
package com.guillermomolina.protos.execution;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Durable PERF006-B closure gate.
 *
 * <p>This class intentionally tests architecture/evidence rather than behavior duplication. The
 * semantic behavior suite remains the authority for calls, suspension, callbacks, handlers,
 * loops, cancellation and I/O. This gate prevents the retired replay backend from silently
 * becoming reachable again while PERF006-C/D and final PERF006 validation proceed.
 */
final class ProtosPerf006B6BFinalConformanceArchitectureTest {
    private static final Path MAIN =
            Path.of("src/main/java/com/guillermomolina/protos");
    private static final Path TEST =
            Path.of("src/test/java/com/guillermomolina/protos");

    @Test
    void productionTreeContainsNoLegacyReplayBackend() throws Exception {
        assertFalse(Files.exists(MAIN.resolve("execution/ProtosEvaluatorBridge.java")));
        assertFalse(Files.exists(MAIN.resolve("execution/ProtosEvaluatorSuspension.java")));
        assertFalse(Files.exists(MAIN.resolve("runtime/ProtosEvaluatorContinuation.java")));

        String mainSources = allJavaUnder(MAIN);
        assertFalse(mainSources.contains("ProtosEvaluatorBridge"));
        assertFalse(mainSources.contains("ProtosEvaluatorSuspension"));
        assertFalse(mainSources.contains("ProtosEvaluatorContinuation"));
        assertFalse(mainSources.contains(".executeProtos("));
        assertFalse(mainSources.contains(".executeAction("));
        assertFalse(mainSources.contains("evaluatorContinuation("));
    }

    @Test
    void publicParseAndCooperativeRootTaskAreBytecodeOnly() throws Exception {
        String language = readMain("execution/ProtosLanguage.java");
        String rootTask = readMain("execution/ProtosRootTaskExecution.java");

        // PLAT054-3: public parse still compiles only Bytecode; the host Context.eval entry wraps
        // that exact target, and runtime parsing unwraps it back to the Bytecode root.
        String languageContext = readMain("execution/ProtosLanguageContext.java");
        assertTrue(
                language.contains(
                        "new ProtosHostEvalRootNode(\n"
                                + "                        this, sourceCompiler.compileBytecode("
                                + "request.getSource(), this, profile))"));
        assertTrue(languageContext.contains("return hostEntry.bytecodeTarget();"));
        assertTrue(
                rootTask.contains(
                        "cooperative RootTask execution requires a semantic Bytecode root"));
        assertTrue(rootTask.contains("ProtosBytecodeTaskExecution.execute("));
        assertFalse(rootTask.contains("executeProtos("));
    }

    @Test
    void taskClosureAndSelectedCallEntryPointsAreCPrimeOnly() throws Exception {
        String closureInvoker = readMain("execution/ProtosClosureInvoker.java");
        String invocation = readMain("execution/ProtosInvocation.java");

        assertTrue(closureInvoker.contains("prepareTaskOwnedDirectClosureIfBytecode("));
        assertTrue(closureInvoker.contains("ProtosBytecodeTaskExecution.executePreparedClosure("));
        assertTrue(closureInvoker.contains("prepareTaskOwnedDirectNativeForCPrime("));
        assertTrue(closureInvoker.contains("ProtosTaskCPrimeEntryExecution.execute("));
        assertTrue(
                closureInvoker.contains(
                        "synchronous Closure invocation cannot execute inside a Task; use C-prime"));

        assertTrue(invocation.contains("prepareTaskOwnedSelectedCallIfBytecode("));
        assertTrue(invocation.contains("prepareTaskOwnedSelectedNativeForCPrime("));
        assertTrue(invocation.contains("ProtosBytecodeTaskExecution.executePreparedClosure("));
        assertTrue(invocation.contains("ProtosTaskCPrimeEntryExecution.execute("));
        assertTrue(
                invocation.contains("return ProtosClosureInvoker.invokeImmediateMethod("));
        assertTrue(
                closureInvoker.contains(
                        "synchronous method invocation cannot execute inside a Task; use C-prime"));
    }

    @Test
    void plat032KeepsLogicalWrapperIdentityWhileCPrimeOwnsSuspendibleControl()
            throws Exception {
        String objectProtocol = readMain("execution/ProtosStandardObjectProtocol.java");
        String bytecodeRoot = readMain("execution/ProtosBytecodeRootNode.java");

        assertTrue(objectProtocol.contains("isStandardCallImplementation("));
        assertTrue(objectProtocol.contains("isCanonicalStandardCallSelection("));
        assertTrue(bytecodeRoot.contains("structuredObjectCall"));
        assertTrue(
                bytecodeRoot.contains(
                        "prepared Closure call has no PLAT032 Object.call capability"));
        assertTrue(
                bytecodeRoot.contains(
                        "structured control native must execute through Bytecode control operations"));
    }

    @Test
    void cliPrintBackpressureRemainsOnCPrimeContinuationPath() throws Exception {
        String cliPrint = readMain("cli/ProtosCliPrintFacility.java");
        String futureProtocol = readMain("execution/ProtosStandardFutureProtocol.java");

        assertTrue(cliPrint.contains("ProtosClosureValue.suspensionCapableNativeClosure("));
        assertTrue(cliPrint.contains("awaitTaskFutureThenForContinuationForRuntime("));
        assertTrue(
                cliPrint.contains(
                        "instanceof ProtosSuspensionCapableNativeClosureBody cPrimeBody"));
        assertTrue(
                futureProtocol.contains(
                        "public static Object awaitTaskFutureThenForContinuationForRuntime("));
        assertTrue(futureProtocol.contains("ProtosNativeSuspension.pending("));
    }

    @Test
    void finalSemanticConformanceEvidenceRemainsPresent() {
        List<String> required =
                List.of(
                        "execution/ProtosPerf006B2D3BSelectedStandardObjectCallIntrinsicTest.java",
                        "execution/ProtosPerf006B2D4AComposedCallTargetTest.java",
                        "execution/ProtosPerf006B2D4BComposedSendReceiverTest.java",
                        "execution/ProtosPerf006B3CSuspensionCapableNativeLeafTest.java",
                        "execution/ProtosPerf006B3EStandardFutureValueCPrimeTest.java",
                        "execution/ProtosPerf006B4CStructuredEnsureTest.java",
                        "execution/ProtosPerf006B4DErrorHandlersTest.java",
                        "execution/ProtosPerf006B4ECancellationUnwindTest.java",
                        "execution/ProtosPerf006B4FStructuredWhileTest.java",
                        "execution/ProtosPerf006B4GControlUnwindClosureTest.java",
                        "execution/ProtosPerf006B6A6A1TaskOwnedClosureDispatchTest.java",
                        "runtime/ProtosPerf006B6A6A2TaskTerminalLifecycleTest.java",
                        "runtime/ProtosPerf006B6A6A3ActorGroupLifecycleIntegrationTest.java",
                        "execution/ProtosStandardFutureProtocolTest.java",
                        "runtime/ProtosIoLifecycleTest.java");

        for (String relative : required) {
            assertTrue(
                    Files.exists(TEST.resolve(relative)),
                    () -> "missing retained PERF006-B conformance evidence: " + relative);
        }

        assertFalse(
                Files.exists(TEST.resolve("execution/ProtosEvaluatorSuspensionBridgeTest.java")));
        assertFalse(
                Files.exists(TEST.resolve("runtime/ProtosWhileReplayCompactionTest.java")));
    }

    private static String readMain(String relative) throws IOException {
        return Files.readString(MAIN.resolve(relative));
    }

    private static String allJavaUnder(Path root) throws IOException {
        try (Stream<Path> paths = Files.walk(root)) {
            List<Path> javaFiles =
                    paths.filter(path -> path.toString().endsWith(".java"))
                            .sorted()
                            .collect(Collectors.toList());
            StringBuilder combined = new StringBuilder();
            for (Path path : javaFiles) {
                combined.append(Files.readString(path)).append('\n');
            }
            return combined.toString();
        }
    }
}
