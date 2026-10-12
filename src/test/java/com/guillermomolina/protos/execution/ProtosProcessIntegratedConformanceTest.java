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

import static org.junit.jupiter.api.Assertions.*;

import com.guillermomolina.protos.runtime.*;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import java.nio.file.Path;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * I017-F cross-slice closure conformance.
 *
 * <p>This deliberately exercises the published I017-A/B/C/D1/D2/E1/E2/E3 surfaces together
 * instead of introducing another runtime abstraction. It verifies the authority/identity boundary
 * that would be easiest to regress when the individual slices are later refactored independently.
 */
final class ProtosProcessIntegratedConformanceTest {
    private static final Path CORE = Path.of("protos", "lib", "core");
    private static final List<String> PROCESS_ACCESSORS =
            List.of(
                    "args",
                    "environment",
                    "stdin",
                    "stdinEncoding",
                    "stdout",
                    "stdoutEncoding",
                    "stderr",
                    "stderrEncoding");

    @Test
    void delegatedProcessReacquiresSnapshotContentAndTransferredArgsAreOrdinaryArrayCopy()
            throws Exception {
        Fixture fixture = fixture(new ProtosFilesystemValue());

        ProtosProcessCapabilityValue delegated =
                assertInstanceOf(
                        ProtosProcessCapabilityValue.class,
                        ProtosActorValueTransfer.snapshotValue(
                                fixture.processCapability, fixture.activation));

        assertNotSame(fixture.processCapability, delegated);
        assertFalse(ProtosIdentity.identical(fixture.processCapability, delegated));
        assertSame(fixture.process, delegated.processForRuntime());

        // D168: reacquisition through a delegated proxy preserves content; no identity relation
        // between separately acquired containers is asserted.
        ProtosArrayValue argsFromRoot =
                assertInstanceOf(
                        ProtosArrayValue.class,
                        invoke(fixture.processCapability, "args", fixture.activation));
        ProtosArrayValue argsFromDelegated =
                assertInstanceOf(
                        ProtosArrayValue.class, invoke(delegated, "args", fixture.activation));
        assertTrue(argsFromRoot.isFrozen());
        assertTrue(argsFromDelegated.isFrozen());
        assertEquals(argumentStrings(argsFromRoot), argumentStrings(argsFromDelegated));
        assertEquals(List.of("alpha", "beta"), argumentStrings(argsFromDelegated));

        Object environmentFromRoot =
                invoke(fixture.processCapability, "environment", fixture.activation);
        Object environmentFromDelegated =
                invoke(delegated, "environment", fixture.activation);
        for (Object environment : List.of(environmentFromRoot, environmentFromDelegated)) {
            assertEquals(
                    "one",
                    assertInstanceOf(
                                    ProtosStringValue.class,
                                    ProtosInvocation.invokeMessage(
                                            environment,
                                            "get",
                                            List.of(new ProtosStringValue("A")),
                                            fixture.activation))
                            .value());
        }

        Object copiedArgs =
                ProtosActorValueTransfer.snapshotValue(argsFromRoot, fixture.activation);
        assertNotSame(argsFromRoot, copiedArgs);
        assertFalse(ProtosIdentity.identical(argsFromRoot, copiedArgs));
        assertEquals(String.valueOf("2"), com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerDecimalText(invoke(copiedArgs, "size", fixture.activation)));
        assertEquals(
                "beta",
                assertInstanceOf(
                                ProtosStringValue.class,
                                ProtosInvocation.invokeMessage(
                                        copiedArgs,
                                        "at",
                                        List.of(new ProtosIntegerValue(1L)),
                                        fixture.activation))
                        .value());

        assertEquals(List.of("alpha", "beta"), argumentStrings(copiedArgs));
    }

    @Test
    void bootstrapAuthorityIsSeparatedFromPreludeAndFromImportedOrTransferableAuthority()
            throws Exception {
        ProtosFilesystemValue filesystem = new ProtosFilesystemValue();
        Fixture fixture = fixture(filesystem);

        assertSame(
                filesystem,
                fixture.activation.context().readLocalSlot("filesystem").orElseThrow());
        assertSame(
                fixture.process,
                fixture.processCapability.processForRuntime());
        assertSame(
                fixture.prelude.processPrototype(),
                fixture.processCapability.representedDelegationParent(fixture.prelude));

        assertFalse(fixture.prelude.processPrototype().hasLocalSlot("call"));
        assertFalse(fixture.prelude.processPrototype().hasLocalSlot("filesystem"));
        assertTrue(fixture.prelude.bindings().readLocalSlot("Filesystem").isEmpty());
        assertTrue(fixture.prelude.bindings().readLocalSlot("Environment").isEmpty());
        assertTrue(fixture.prelude.bindings().readLocalSlot("Bytes").isEmpty());
        assertTrue(fixture.prelude.bindings().readLocalSlot("_coreActorRefPrototype").isEmpty());

        ProtosObjectValue actorRefPrototype = fixture.prelude.actorRefPrototypeForRuntime();
        assertEquals(
                Set.of("send", "request", "stop", "termination"),
                actorRefPrototype.localSlotsSnapshot().keySet());
        assertSame(
                ProtosObjectValue.rootObject(),
                fixture.prelude.bytesPrototypeForRuntime().parent().orElseThrow());

        assertPRejected(fixture.processCapability, fixture.activation);
        assertPRejected(invoke(fixture.processCapability, "stdin", fixture.activation), fixture.activation);
        assertPRejected(invoke(fixture.processCapability, "stdout", fixture.activation), fixture.activation);
        assertPRejected(filesystem, fixture.activation);
    }

    @Test
    void standardBindingsRemainIndependentByteCapabilitiesWithStableEncodingAssociations()
            throws Exception {
        Fixture fixture = fixture(null);

        Object stdinFirst = invoke(fixture.processCapability, "stdin", fixture.activation);
        Object stdinSecond = invoke(fixture.processCapability, "stdin", fixture.activation);
        Object stdout = invoke(fixture.processCapability, "stdout", fixture.activation);
        Object stderr = invoke(fixture.processCapability, "stderr", fixture.activation);

        assertInstanceOf(ProtosProcessStandardStreamValue.class, stdinFirst);
        assertInstanceOf(ProtosProcessStandardStreamValue.class, stdinSecond);
        assertInstanceOf(ProtosProcessStandardStreamValue.class, stdout);
        assertInstanceOf(ProtosProcessStandardStreamValue.class, stderr);
        assertNotSame(stdinFirst, stdinSecond);
        assertNotSame(stdout, stderr);

        assertSame(fixture.utf8, invoke(fixture.processCapability, "stdinEncoding", fixture.activation));
        assertSame(fixture.utf8, invoke(fixture.processCapability, "stdoutEncoding", fixture.activation));
        assertSame(fixture.utf8, invoke(fixture.processCapability, "stderrEncoding", fixture.activation));

        ProtosObjectValue stdinParent =
                assertInstanceOf(
                        ProtosObjectValue.class,
                        ((ProtosProcessStandardStreamValue) stdinFirst)
                                .representedDelegationParent(fixture.prelude));
        ProtosObjectValue stdoutParent =
                assertInstanceOf(
                        ProtosObjectValue.class,
                        ((ProtosProcessStandardStreamValue) stdout)
                                .representedDelegationParent(fixture.prelude));
        assertEquals(Set.of("read"), stdinParent.localSlotsSnapshot().keySet());
        assertEquals(Set.of("write"), stdoutParent.localSlotsSnapshot().keySet());
        assertFalse(stdinParent.hasLocalSlot("close"));
        assertFalse(stdoutParent.hasLocalSlot("flush"));
        assertFalse(stdoutParent.hasLocalSlot("close"));
    }

    @Test
    void processTerminationRevokesAllExistingProcessProxiesAndRejectsNewStreamWork()
            throws Exception {
        Fixture fixture = fixture(null);
        ProtosProcessCapabilityValue delegated =
                assertInstanceOf(
                        ProtosProcessCapabilityValue.class,
                        ProtosActorValueTransfer.snapshotValue(
                                fixture.processCapability, fixture.activation));
        Object existingStdin =
                invoke(fixture.processCapability, "stdin", fixture.activation);

        assertTrue(fixture.process.requestTerminationForRuntime());
        assertNotEquals(
                ProtosProcessRuntime.LifecycleState.RUNNING,
                fixture.process.lifecycleState());

        for (String selector : PROCESS_ACCESSORS) {
            assertThrows(
                    ProtosSignalException.class,
                    () -> invoke(fixture.processCapability, selector, fixture.activation),
                    "root proxy " + selector);
            assertThrows(
                    ProtosSignalException.class,
                    () -> invoke(delegated, selector, fixture.activation),
                    "delegated proxy " + selector);
        }

        assertThrows(
                IllegalStateException.class,
                () ->
                        fixture.process.provisionCapabilityForRuntime(
                                fixture.prelude.processPrototype()));

        // Existing live stream authority is also outside P and the D1 focal test below verifies
        // that new byte operations are rejected after this same Process cutover.
        assertPRejected(existingStdin, fixture.activation);
    }



    // LIB004-D-PROCESS-STREAMS-CONFORMANCE
    @Test
    void processStreamsLibraryAdaptersAreDrivenByProtosCases() throws Exception {
        for (String file :
                List.of(
                        "process-streams-fresh-wrappers.protos",
                        "process-streams-process-encodings.protos",
                        "process-streams-borrowing-close.protos",
                        "process-streams-explicit-process.protos")) {
            LibraryFixture fixture = libraryFixture();
            Object result = executeLibraryCase(file, fixture.activation());
            assertSame(ProtosBooleanValue.TRUE, result, file);
        }
    }


    // LM006-C-PROCESS-STREAM-MATURITY
    @Test
    void lm006CProcessAndStandardStreamMaturityIsDrivenByProtosCases() throws Exception {
        for (String file :
                List.of(
                        "delegated-process-shares-stdin-sequence.protos",
                        "root-delegated-processstreams-share-stdout.protos",
                        "raw-and-text-share-stdout.protos",
                        "process-bootstrap-data-to-stdout.protos")) {
            MaturityFixture fixture = maturityFixture(true);
            Object result = executeMaturityCase(file, fixture.activation());
            assertSame(ProtosBooleanValue.TRUE, result, file);
        }

        MaturityFixture unavailableStdin = maturityFixture(false);
        assertSame(
                ProtosBooleanValue.TRUE,
                executeMaturityCase(
                        "unavailable-stdin-independent-stdout.protos",
                        unavailableStdin.activation()));
    }

    private static MaturityFixture maturityFixture(boolean stdinAvailable) throws Exception {
        ProtosPrelude prelude =
                new ProtosCoreBootstrap()
                        .bootstrap(
                                CORE,
                                new ProtosStandardLibraryModuleResolver(
                                        Path.of("protos", "lib")));
        ProtosEncodingValue latin1 =
                assertInstanceOf(
                        ProtosEncodingValue.class,
                        prelude.encodingPrototype().readLocalSlot("Latin1").orElseThrow());
        ProtosEncodingValue utf16be =
                assertInstanceOf(
                        ProtosEncodingValue.class,
                        prelude.encodingPrototype().readLocalSlot("UTF16BE").orElseThrow());
        ProtosBytesValue stdoutCapture =
                new ProtosBytesValue(prelude.bytesPrototypeForRuntime());
        ProtosBytesValue stderrCapture =
                new ProtosBytesValue(prelude.bytesPrototypeForRuntime());
        byte[] stdinBytes = new byte[] {65, 66};
        java.util.concurrent.atomic.AtomicInteger stdinOffset =
                new java.util.concurrent.atomic.AtomicInteger();

        ProtosProcessStandardStreamBinding.ReadableBackend stdinBackend =
                stdinAvailable
                        ? (maxBytes, completion) -> {
                            int start = stdinOffset.get();
                            if (start >= stdinBytes.length) {
                                completion.eof();
                                return () -> {};
                            }
                            int length = Math.min(maxBytes, stdinBytes.length - start);
                            int end = start + length;
                            byte[] delivered = java.util.Arrays.copyOfRange(stdinBytes, start, end);
                            stdinOffset.set(end);
                            completion.data(delivered);
                            return () -> {};
                        }
                        : null;

        ProtosStandaloneProcessBootstrap.Result bootstrap =
                ProtosStandaloneProcessBootstrap.create(
                        prelude,
                        List.of("alpha"),
                        exactEnvironmentDomain(),
                        List.of(new ProtosEnvironmentValue.NativeEntry("MODE", "beta")),
                        stdinBackend,
                        (bytes, completion) -> {
                            appendCapturedBytes(stdoutCapture, bytes);
                            completion.succeeded();
                            return () -> {};
                        },
                        (bytes, completion) -> {
                            appendCapturedBytes(stderrCapture, bytes);
                            completion.succeeded();
                            return () -> {};
                        },
                        stdinAvailable ? latin1 : null,
                        utf16be,
                        latin1,
                        null);

        ProtosProcessCapabilityValue processCapability =
                assertInstanceOf(
                        ProtosProcessCapabilityValue.class,
                        bootstrap.activation()
                                .context()
                                .readLocalSlot("process")
                                .orElseThrow());
        ProtosProcessCapabilityValue delegatedProcess =
                assertInstanceOf(
                        ProtosProcessCapabilityValue.class,
                        ProtosActorValueTransfer.snapshotValue(
                                processCapability, bootstrap.activation()));

        bootstrap.activation().context().createLocalSlot("delegatedProcess", delegatedProcess);
        bootstrap.activation().context().createLocalSlot("stdoutCapture", stdoutCapture);
        bootstrap.activation().context().createLocalSlot("stderrCapture", stderrCapture);
        return new MaturityFixture(bootstrap.activation());
    }

    private static Object executeMaturityCase(String file, ProtosActivation activation)
            throws Exception {
        String source =
                java.nio.file.Files.readString(
                        Path.of(
                                "protos",
                                "tests",
                                "conformance",
                                "maturity",
                                "process",
                                file),
                        java.nio.charset.StandardCharsets.UTF_8);
        ProtosExecutionOutcome outcome =
                com.guillermomolina.protos.execution.ProtosTestExecutionSupport.execute(
source,
activation);
        return switch (outcome.state()) {
            case COMPLETED -> outcome.value();
            case FAILED -> throw new AssertionError(
                    "LM006-C Protos case failed: " + file,
                    new ProtosSignalException(outcome.error()));
            case CANCELLED -> throw new AssertionError(
                    "LM006-C Protos case unexpectedly cancelled: " + file);
        };
    }

    private record MaturityFixture(ProtosActivation activation) {}

    private static LibraryFixture libraryFixture() throws Exception {
        ProtosPrelude prelude =
                new ProtosCoreBootstrap()
                        .bootstrap(
                                CORE,
                                new ProtosStandardLibraryModuleResolver(
                                        Path.of("protos", "lib")));
        ProtosEncodingValue latin1 =
                assertInstanceOf(
                        ProtosEncodingValue.class,
                        prelude.encodingPrototype().readLocalSlot("Latin1").orElseThrow());
        ProtosEncodingValue utf16be =
                assertInstanceOf(
                        ProtosEncodingValue.class,
                        prelude.encodingPrototype().readLocalSlot("UTF16BE").orElseThrow());
        ProtosBytesValue stdoutCapture =
                new ProtosBytesValue(prelude.bytesPrototypeForRuntime());
        ProtosBytesValue stderrCapture =
                new ProtosBytesValue(prelude.bytesPrototypeForRuntime());
        java.util.concurrent.atomic.AtomicBoolean stdinDelivered =
                new java.util.concurrent.atomic.AtomicBoolean();

        ProtosStandaloneProcessBootstrap.Result bootstrap =
                ProtosStandaloneProcessBootstrap.create(
                        prelude,
                        List.of(),
                        exactEnvironmentDomain(),
                        List.of(),
                        (maxBytes, completion) -> {
                            if (stdinDelivered.compareAndSet(false, true)) {
                                completion.data(new byte[] {(byte) 0xe9});
                            } else {
                                completion.eof();
                            }
                            return () -> {};
                        },
                        (bytes, completion) -> {
                            appendCapturedBytes(stdoutCapture, bytes);
                            completion.succeeded();
                            return () -> {};
                        },
                        (bytes, completion) -> {
                            appendCapturedBytes(stderrCapture, bytes);
                            completion.succeeded();
                            return () -> {};
                        },
                        latin1,
                        utf16be,
                        latin1,
                        null);

        bootstrap.activation().context().createLocalSlot("stdoutCapture", stdoutCapture);
        bootstrap.activation().context().createLocalSlot("stderrCapture", stderrCapture);
        return new LibraryFixture(bootstrap.activation());
    }

    private static void appendCapturedBytes(ProtosBytesValue capture, byte[] bytes) {
        for (byte value : bytes) {
            capture.indexedAdd(new ProtosIntegerValue(value & 0xff));
        }
    }

    private static Object executeLibraryCase(String file, ProtosActivation activation)
            throws Exception {
        String source =
                java.nio.file.Files.readString(
                        Path.of("protos", "tests", "conformance", "library", "io", file),
                        java.nio.charset.StandardCharsets.UTF_8);
        ProtosExecutionOutcome outcome =
                com.guillermomolina.protos.execution.ProtosTestExecutionSupport.execute(
source,
activation);
        return switch (outcome.state()) {
            case COMPLETED -> outcome.value();
            case FAILED -> throw new AssertionError(
                    "LIB004-D Protos case failed: " + file,
                    new ProtosSignalException(outcome.error()));
            case CANCELLED -> throw new AssertionError(
                    "LIB004-D Protos case unexpectedly cancelled: " + file);
        };
    }

    private record LibraryFixture(ProtosActivation activation) {}

    private static Fixture fixture(ProtosFilesystemValue filesystem) throws Exception {
        ProtosPrelude prelude = new ProtosCoreBootstrap().bootstrap(CORE);
        ProtosEncodingValue utf8 =
                assertInstanceOf(
                        ProtosEncodingValue.class,
                        prelude.encodingPrototype().readLocalSlot("UTF8").orElseThrow());

        ProtosStandaloneProcessBootstrap.Result bootstrap =
                ProtosStandaloneProcessBootstrap.create(
                        prelude,
                        List.of("alpha", "beta"),
                        exactEnvironmentDomain(),
                        List.of(
                                new ProtosEnvironmentValue.NativeEntry("A", "one"),
                                new ProtosEnvironmentValue.NativeEntry("B", "two")),
                        (maxBytes, completion) -> () -> {},
                        (bytes, completion) -> () -> {},
                        (bytes, completion) -> () -> {},
                        utf8,
                        utf8,
                        utf8,
                        filesystem);

        ProtosProcessCapabilityValue processCapability =
                assertInstanceOf(
                        ProtosProcessCapabilityValue.class,
                        bootstrap.activation()
                                .context()
                                .readLocalSlot("process")
                                .orElseThrow());

        return new Fixture(
                prelude,
                bootstrap.process(),
                bootstrap.activation(),
                processCapability,
                utf8);
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

    private static List<String> argumentStrings(Object array) {
        return assertInstanceOf(ProtosArrayValue.class, array).indexedSnapshot().stream()
                .map(element -> ((ProtosStringValue) element).value())
                .toList();
    }

    private static Object invoke(
            Object receiver, String selector, ProtosActivation activation) {
        return ProtosInvocation.invokeMessage(receiver, selector, List.of(), activation);
    }

    private static void assertPRejected(Object value, ProtosActivation activation)
            throws Exception {
        Class<?> transfer =
                Class.forName(
                        "com.guillermomolina.protos.execution.ProtosParallelRuntime$Transfer");
        Method copy =
                transfer.getDeclaredMethod(
                        "copy", Object.class, ProtosActivation.class, IdentityHashMap.class);
        copy.setAccessible(true);

        InvocationTargetException failure =
                assertThrows(
                        InvocationTargetException.class,
                        () ->
                                copy.invoke(
                                        null,
                                        value,
                                        activation,
                                        new IdentityHashMap<Object, Object>()));
        assertNotNull(failure.getCause());
        assertEquals("NonParallel", failure.getCause().getClass().getSimpleName());
    }

    private record Fixture(
            ProtosPrelude prelude,
            ProtosProcessRuntime process,
            ProtosActivation activation,
            ProtosProcessCapabilityValue processCapability,
            ProtosEncodingValue utf8) {}
}
