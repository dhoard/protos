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

package com.guillermomolina.protos.runtime;

import static org.junit.jupiter.api.Assertions.*;

import com.guillermomolina.protos.execution.ProtosCoreBootstrap;
import com.guillermomolina.protos.execution.ProtosStandardBytesProtocol;
import com.guillermomolina.protos.execution.ProtosStandardProcessStreamProtocol;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

final class ProtosProcessStandardStreamBindingTest {
    private static final Path CORE = Path.of("protos", "lib", "core");

    @Test
    void bootstrapAvailabilityIsIndependentStableAndRepeatedAccessSharesBinding()
            throws Exception {
        new ProtosCoreBootstrap().bootstrap(CORE);
        ProtosProcessRuntime process =
                new ProtosProcessRuntime(
                        new ProtosObjectValue(ProtosObjectValue.rootObject()).freeze());
        ProtosObjectValue bytesPrototype = bytesPrototype();
        ProtosObjectValue readablePrototype =
                ProtosStandardProcessStreamProtocol.createReadablePrototype();
        ProtosObjectValue writablePrototype =
                ProtosStandardProcessStreamProtocol.createWritablePrototype();

        ControlledReadBackend stdin = new ControlledReadBackend();
        ControlledWriteBackend stderr = new ControlledWriteBackend();

        process.establishStandardStreamsForRuntime(
                readablePrototype,
                writablePrototype,
                bytesPrototype,
                stdin,
                null,
                stderr);

        ProtosProcessStandardStreamValue first =
                process.stdinForRuntime().orElseThrow();
        ProtosProcessStandardStreamValue second =
                process.stdinForRuntime().orElseThrow();

        assertNotSame(first, second);
        assertTrue(first.denotesSameBindingForTesting(second));
        assertTrue(process.stdoutForRuntime().isEmpty());
        assertTrue(process.stderrForRuntime().isPresent());
        assertThrows(
                IllegalStateException.class,
                () ->
                        process.establishStandardStreamsForRuntime(
                                readablePrototype,
                                writablePrototype,
                                bytesPrototype,
                                stdin,
                                new ControlledWriteBackend(),
                                stderr));

        assertEquals(java.util.Set.of("read"), readablePrototype.localSlotsSnapshot().keySet());
        assertEquals(java.util.Set.of("write"), writablePrototype.localSlotsSnapshot().keySet());
        assertFalse(readablePrototype.hasLocalSlot("close"));
        assertFalse(readablePrototype.hasLocalSlot("flush"));
        assertFalse(writablePrototype.hasLocalSlot("close"));
        assertFalse(writablePrototype.hasLocalSlot("flush"));
    }

    @Test
    void stdinViewsAcrossActorDomainsConsumeOneSharedOrderedSequence() throws Exception {
        ProtosPrelude prelude = new ProtosCoreBootstrap().bootstrap(CORE);
        ProtosProcessRuntime process =
                new ProtosProcessRuntime(
                        new ProtosObjectValue(ProtosObjectValue.rootObject()).freeze());
        ControlledReadBackend stdin = new ControlledReadBackend();
        process.establishStandardStreamsForRuntime(
                ProtosStandardProcessStreamProtocol.createReadablePrototype(),
                ProtosStandardProcessStreamProtocol.createWritablePrototype(),
                bytesPrototype(),
                stdin,
                null,
                null);

        ProtosProcessStandardStreamValue first = process.stdinForRuntime().orElseThrow();
        ProtosProcessStandardStreamValue second = process.stdinForRuntime().orElseThrow();
        ProtosActivation actorA = prelude.newModuleActivation();
        ProtosActivation actorB = prelude.newModuleActivation();

        ProtosFutureValue a =
                first.readForRuntime(actorA, new ProtosIntegerValue(1L));
        ProtosFutureValue b =
                second.readForRuntime(actorB, new ProtosIntegerValue(1L));

        assertEquals(1, stdin.started.get());
        assertEquals(ProtosFutureValue.State.PENDING, a.state());
        assertEquals(ProtosFutureValue.State.PENDING, b.state());

        stdin.completeNext(new byte[] {11});
        assertEquals(ProtosFutureValue.State.RESOLVED, a.state());
        assertEquals(2, stdin.started.get());
        assertEquals(ProtosFutureValue.State.PENDING, b.state());

        stdin.completeNext(new byte[] {22});
        assertEquals(
                11L,
                assertInstanceOf(
                        ProtosIntegerValue.class,
                        ((ProtosBytesValue) a.resolvedValue().orElseThrow())
                                .indexedAt(0))
                        .longValue());
        assertEquals(
                22L,
                assertInstanceOf(
                        ProtosIntegerValue.class,
                        ((ProtosBytesValue) b.resolvedValue().orElseThrow())
                                .indexedAt(0))
                        .longValue());
    }

    @Test
    void delegatedStdoutProxySharesOneNonInterleavingOutputQueue() throws Exception {
        ProtosPrelude prelude = new ProtosCoreBootstrap().bootstrap(CORE);
        ProtosActivation actorA = prelude.newModuleActivation();
        ProtosActivation actorB = prelude.newModuleActivation();
        ProtosProcessRuntime process =
                new ProtosProcessRuntime(
                        new ProtosObjectValue(ProtosObjectValue.rootObject()).freeze());
        ControlledWriteBackend stdout = new ControlledWriteBackend();
        ProtosObjectValue bytesPrototype = bytesPrototype();

        process.establishStandardStreamsForRuntime(
                ProtosStandardProcessStreamProtocol.createReadablePrototype(),
                ProtosStandardProcessStreamProtocol.createWritablePrototype(),
                bytesPrototype,
                null,
                stdout,
                null);
        ProtosProcessStandardStreamValue source = process.stdoutForRuntime().orElseThrow();
        ProtosProcessStandardStreamValue delegated =
                assertInstanceOf(
                        ProtosProcessStandardStreamValue.class,
                        ProtosActorValueTransfer.snapshotValue(source, actorA));

        assertNotSame(source, delegated);
        assertTrue(source.denotesSameBindingForTesting(delegated));

        ProtosFutureValue first =
                source.writeForRuntime(actorA, bytes(bytesPrototype, 1, 2));
        ProtosFutureValue second =
                delegated.writeForRuntime(actorB, bytes(bytesPrototype, 3, 4));

        assertEquals(1, stdout.started.get());
        assertArrayEquals(new byte[] {1, 2}, stdout.startedBytes.get(0));
        assertEquals(ProtosFutureValue.State.PENDING, second.state());

        stdout.succeedNext();
        assertSame(source, first.resolvedValue().orElseThrow());
        assertEquals(2, stdout.started.get());
        assertArrayEquals(new byte[] {3, 4}, stdout.startedBytes.get(1));

        stdout.succeedNext();
        assertSame(delegated, second.resolvedValue().orElseThrow());
    }

    @Test
    void cancelledReadDoesNotConsumeBytesReturnedAfterCancellation() throws Exception {
        ProtosPrelude prelude = new ProtosCoreBootstrap().bootstrap(CORE);
        ProtosActivation activation = prelude.newModuleActivation();
        ProtosProcessRuntime process =
                new ProtosProcessRuntime(
                        new ProtosObjectValue(ProtosObjectValue.rootObject()).freeze());
        ControlledReadBackend stdin = new ControlledReadBackend();
        process.establishStandardStreamsForRuntime(
                ProtosStandardProcessStreamProtocol.createReadablePrototype(),
                ProtosStandardProcessStreamProtocol.createWritablePrototype(),
                bytesPrototype(),
                stdin,
                null,
                null);

        ProtosProcessStandardStreamValue stream = process.stdinForRuntime().orElseThrow();
        ProtosFutureValue cancelled =
                stream.readForRuntime(activation, new ProtosIntegerValue(1L));
        assertTrue(cancelled.cancelRequest());
        assertEquals(1, stdin.cancelled.get());

        stdin.completeNext(new byte[] {77});
        ProtosFutureValue next =
                stream.readForRuntime(activation, new ProtosIntegerValue(1L));

        assertEquals(ProtosFutureValue.State.RESOLVED, next.state());
        assertEquals(
                77L,
                assertInstanceOf(
                        ProtosIntegerValue.class,
                        ((ProtosBytesValue) next.resolvedValue().orElseThrow())
                                .indexedAt(0))
                        .longValue());
        assertEquals(1, stdin.started.get());
    }

    @Test
    void terminationCutoverRejectsNewStandardStreamOperations() throws Exception {
        ProtosPrelude prelude = new ProtosCoreBootstrap().bootstrap(CORE);
        ProtosActivation activation = prelude.newModuleActivation();
        ProtosProcessRuntime process =
                new ProtosProcessRuntime(
                        new ProtosObjectValue(ProtosObjectValue.rootObject()).freeze());
        ControlledWriteBackend stdout = new ControlledWriteBackend();
        ProtosObjectValue bytesPrototype = bytesPrototype();
        process.establishStandardStreamsForRuntime(
                ProtosStandardProcessStreamProtocol.createReadablePrototype(),
                ProtosStandardProcessStreamProtocol.createWritablePrototype(),
                bytesPrototype,
                null,
                stdout,
                null);
        ProtosProcessStandardStreamValue stream = process.stdoutForRuntime().orElseThrow();

        assertTrue(process.requestTerminationForRuntime());

        ProtosFutureValue rejected =
                stream.writeForRuntime(activation, bytes(bytesPrototype, 1));
        assertEquals(ProtosFutureValue.State.FAILED, rejected.state());
        assertEquals(0, stdout.started.get());
        assertSame(
                prelude.bindings().readLocalSlot("IOLifecycleError").orElseThrow(),
                rejected.failedError().orElseThrow().parent().orElseThrow());
    }

    // I091: the read limit is an exact positive Integer in the int range, checked before any read.
    @Test
    void invalidReadLimitsConsumeNothingAndAValidReadFollowsExactly() throws Exception {
        ProtosPrelude prelude = new ProtosCoreBootstrap().bootstrap(CORE);
        ProtosActivation activation = prelude.newModuleActivation();
        ProtosProcessRuntime process =
                new ProtosProcessRuntime(
                        new ProtosObjectValue(ProtosObjectValue.rootObject()).freeze());
        ControlledReadBackend stdin = new ControlledReadBackend();
        process.establishStandardStreamsForRuntime(
                ProtosStandardProcessStreamProtocol.createReadablePrototype(),
                ProtosStandardProcessStreamProtocol.createWritablePrototype(),
                bytesPrototype(),
                stdin,
                null,
                null);
        ProtosProcessStandardStreamValue stream = process.stdinForRuntime().orElseThrow();
        Object[] invalid = {
            new ProtosIntegerValue(0L),
            new ProtosIntegerValue(-1L),
            new ProtosIntegerValue(Integer.MAX_VALUE + 1L),
            new ProtosIntegerValue((1L << 32) + 1),
            com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerFromHost(com.guillermomolina.protos.runtime.ProtosNumberLiteral.parse("18446744073709551616"), prelude),
            com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerFromHost(com.guillermomolina.protos.runtime.ProtosNumberLiteral.parse("18446744073709551617"), prelude),
            new ProtosFloatValue(1.0d),
            Long.valueOf(1L),
        };
        for (Object limit : invalid) {
            ProtosFutureValue rejected = stream.readForRuntime(activation, limit);
            assertEquals(ProtosFutureValue.State.FAILED, rejected.state(), String.valueOf(limit));
            assertSame(
                    prelude.bindings().readLocalSlot("InvalidIOArgument").orElseThrow(),
                    rejected.failedError().orElseThrow().parent().orElseThrow());
        }
        assertEquals(0, stdin.started.get());

        ProtosFutureValue read =
                stream.readForRuntime(activation, new ProtosIntegerValue(Integer.MAX_VALUE));
        assertEquals(1, stdin.started.get());
        assertEquals(Integer.MAX_VALUE, stdin.lastMaxBytes);
        stdin.completeNext(new byte[] {0, (byte) 0xff});

        ProtosBytesValue result = (ProtosBytesValue) read.resolvedValue().orElseThrow();
        assertEquals(
                0L,
                assertInstanceOf(ProtosIntegerValue.class, result.indexedAt(0))
                        .longValue());
        assertEquals(
                255L,
                assertInstanceOf(ProtosIntegerValue.class, result.indexedAt(1))
                        .longValue());

        assertTrue(process.requestTerminationForRuntime());
        ProtosFutureValue afterTermination =
                stream.readForRuntime(activation, new ProtosIntegerValue(1L));
        assertEquals(ProtosFutureValue.State.FAILED, afterTermination.state());
        assertEquals(1, stdin.started.get());
    }

    private static ProtosObjectValue bytesPrototype() {
        ProtosObjectValue prototype =
                new ProtosObjectValue(ProtosObjectValue.rootObject());
        ProtosStandardBytesProtocol.install(prototype);
        return prototype;
    }

    private static ProtosBytesValue bytes(ProtosObjectValue prototype, int... values) {
        ProtosBytesValue bytes = new ProtosBytesValue(prototype);
        for (int value : values) {
            bytes.indexedAdd(new ProtosIntegerValue(value));
        }
        return bytes;
    }

    private static final class ControlledReadBackend
            implements ProtosProcessStandardStreamBinding.ReadableBackend {
        private final ArrayDeque<ProtosByteIoFlow.ReadCompletion> pending =
                new ArrayDeque<>();
        private final AtomicInteger started = new AtomicInteger();
        private final AtomicInteger cancelled = new AtomicInteger();
        private int lastMaxBytes;

        @Override
        public ProtosByteIoFlow.Cancellation read(
                int maxBytes, ProtosByteIoFlow.ReadCompletion completion) {
            started.incrementAndGet();
            lastMaxBytes = maxBytes;
            pending.addLast(completion);
            return cancelled::incrementAndGet;
        }

        private void completeNext(byte[] bytes) {
            pending.removeFirst().data(bytes);
        }
    }

    private static final class ControlledWriteBackend
            implements ProtosProcessStandardStreamBinding.WritableBackend {
        private final ArrayDeque<ProtosByteIoFlow.WriteCompletion> pending =
                new ArrayDeque<>();
        private final AtomicInteger started = new AtomicInteger();
        private final java.util.ArrayList<byte[]> startedBytes =
                new java.util.ArrayList<>();

        @Override
        public ProtosByteIoFlow.Cancellation write(
                byte[] bytes, ProtosByteIoFlow.WriteCompletion completion) {
            started.incrementAndGet();
            startedBytes.add(bytes.clone());
            pending.addLast(completion);
            return () -> {};
        }

        private void succeedNext() {
            pending.removeFirst().succeeded();
        }
    }
}
