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
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;

class ProtosStandardBufferedByteIoProtocolTest {
    private static ProtosPrelude core() throws Exception {
        return new ProtosCoreBootstrap().bootstrap(Path.of("protos", "lib", "core"));
    }

    private static ProtosIntegerValue i(long n) {
        return new ProtosIntegerValue(n);
    }

    private static ProtosBytesValue bytes(ProtosObjectValue prototype, int... values) {
        ProtosBytesValue bytes = new ProtosBytesValue(prototype);
        for (int value : values) bytes.indexedAdd(i(value));
        return bytes;
    }

    private static Object call(
            ProtosObjectValue object,
            String message,
            List<?> arguments,
            ProtosActivation activation) {
        return com.guillermomolina.protos.execution.ProtosTestExecutionSupport.callEnteredAndDrain(activation, () -> ProtosInvocation.invokeMessage(object, message, arguments, activation));
    }

    @Test
    void factoryProtocolInstallsIntoExactProvidedObjectsWithoutAllocatingReplacements()
            throws Exception {
        ProtosPrelude prelude = core();
        ProtosActivation activation = prelude.newModuleActivation();
        ProtosObjectValue bytesPrototype =
                new ProtosObjectValue(ProtosObjectValue.rootObject());
        ProtosObjectValue readerSource =
                new ProtosObjectValue(ProtosObjectValue.rootObject());
        ProtosObjectValue writerSource =
                new ProtosObjectValue(ProtosObjectValue.rootObject());

        ProtosObjectValue reader =
                ProtosStandardBufferedByteIoProtocol.installReaderFactory(
                        readerSource, bytesPrototype, activation);
        ProtosObjectValue writer =
                ProtosStandardBufferedByteIoProtocol.installWriterFactory(
                        writerSource, bytesPrototype, activation);

        assertSame(readerSource, reader);
        assertSame(writerSource, writer);
        for (ProtosObjectValue factory : List.of(reader, writer)) {
            assertSame(ProtosObjectValue.rootObject(), factory.parent().orElseThrow());
            assertTrue(factory.isFrozen());
            assertEquals(Set.of("call", "owning"), factory.localSlotsSnapshot().keySet());
            ProtosClosureValue call =
                    assertInstanceOf(
                            ProtosClosureValue.class,
                            factory.readLocalSlot("call").orElseThrow());
            ProtosClosureValue owning =
                    assertInstanceOf(
                            ProtosClosureValue.class,
                            factory.readLocalSlot("owning").orElseThrow());
            assertTrue(call.nativeBody().isPresent());
            assertTrue(owning.nativeBody().isPresent());
            assertNull(call.definition());
            assertNull(owning.definition());
        }
    }

    @Test
    void factoriesAreFrozenAndCreateFreshBorrowingWrappers() throws Exception {
        ProtosPrelude prelude = core();
        ProtosActivation activation = prelude.newModuleActivation();
        ProtosObjectValue factory =
                ProtosStandardModuleMemberTestSupport.bufferedReaderFactory(prelude);
        assertSame(ProtosObjectValue.rootObject(), factory.parent().orElseThrow());
        assertTrue(factory.isFrozen());
        assertEquals(Set.of("call", "owning"), factory.localSlotsSnapshot().keySet());

        ProtosObjectValue source = source(activation);
        ProtosObjectValue first =
                (ProtosObjectValue)
                        call(factory, "call", List.of(source), activation);
        ProtosObjectValue second =
                (ProtosObjectValue)
                        call(factory, "call", List.of(source), activation);

        assertNotSame(first, second);
        assertTrue(first.hasLocalSlot("read"));
        assertTrue(first.hasLocalSlot("close"));
        assertFalse(first.hasLocalSlot("write"));
    }

    @Test
    void readerReturnsBufferedPrefixBeforeReenteringSourceAndFreshBytes()
            throws Exception {
        ProtosPrelude prelude = core();
        ProtosActivation activation = prelude.newModuleActivation();
        ProtosObjectValue source = source(activation);
        ProtosObjectValue factory =
                ProtosStandardModuleMemberTestSupport.bufferedReaderFactory(prelude);
        ProtosObjectValue reader =
                (ProtosObjectValue)
                        call(factory, "call", List.of(source), activation);

        ProtosFutureValue first =
                (ProtosFutureValue)
                        call(reader, "read", List.of(i(2)), activation);
        ProtosFutureValue second =
                (ProtosFutureValue)
                        call(reader, "read", List.of(i(2)), activation);

        assertEquals(
                List.of(1, 2),
                ints((ProtosBytesValue) first.resolvedValue().orElseThrow()));
        assertEquals(
                List.of(3, 4),
                ints((ProtosBytesValue) second.resolvedValue().orElseThrow()));
        assertEquals(
                1,
                ((Counter) source.readLocalSlot("counter").orElseThrow()).reads);
    }

    @Test
    void writerBuffersUntilFlushAndFlushesNestedFlushableTarget()
            throws Exception {
        ProtosPrelude prelude = core();
        ProtosActivation activation = prelude.newModuleActivation();
        ProtosObjectValue target = target(activation);
        ProtosObjectValue factory =
                ProtosStandardModuleMemberTestSupport.bufferedWriterFactory(prelude);
        ProtosObjectValue writer =
                (ProtosObjectValue)
                        call(factory, "call", List.of(target), activation);

        ProtosFutureValue write =
                (ProtosFutureValue)
                        call(
                                writer,
                                "write",
                                List.of(
                                        bytes(
                                                (ProtosObjectValue)
                                                        target.readLocalSlot("bp")
                                                                .orElseThrow(),
                                                7,
                                                8)),
                                activation);
        assertSame(writer, write.resolvedValue().orElseThrow());

        Counter counter =
                (Counter) target.readLocalSlot("counter").orElseThrow();
        assertEquals(0, counter.writes);

        ProtosFutureValue flush =
                (ProtosFutureValue)
                        call(writer, "flush", List.of(), activation);
        assertSame(writer, flush.resolvedValue().orElseThrow());
        assertEquals(1, counter.writes);
        assertEquals(1, counter.flushes);
    }

    @Test
    void borrowingCloseDoesNotCloseTargetButOwningCloseDoes()
            throws Exception {
        ProtosPrelude prelude = core();
        ProtosActivation activation = prelude.newModuleActivation();
        ProtosObjectValue factory =
                ProtosStandardModuleMemberTestSupport.bufferedWriterFactory(prelude);

        ProtosObjectValue firstTarget = target(activation);
        ProtosObjectValue borrowed =
                (ProtosObjectValue)
                        call(factory, "call", List.of(firstTarget), activation);
        call(borrowed, "close", List.of(), activation);
        assertEquals(
                0,
                ((Counter) firstTarget.readLocalSlot("counter").orElseThrow())
                        .closes);

        ProtosObjectValue secondTarget = target(activation);
        ProtosObjectValue owned =
                (ProtosObjectValue)
                        call(factory, "owning", List.of(secondTarget), activation);
        call(
                owned,
                "write",
                List.of(
                        bytes(
                                (ProtosObjectValue)
                                        secondTarget.readLocalSlot("bp")
                                                .orElseThrow(),
                                9)),
                activation);
        ProtosFutureValue close =
                (ProtosFutureValue)
                        call(owned, "close", List.of(), activation);

        assertSame(owned, close.resolvedValue().orElseThrow());
        Counter counter =
                (Counter) secondTarget.readLocalSlot("counter").orElseThrow();
        assertEquals(1, counter.writes);
        assertEquals(1, counter.flushes);
        assertEquals(1, counter.closes);
    }

    @Test
    void owningFactoriesRejectStructuralObjectCloseAsResourceCapability()
            throws Exception {
        ProtosPrelude prelude = core();
        ProtosActivation activation = prelude.newModuleActivation();

        ProtosObjectValue readOnly =
                new ProtosObjectValue(ProtosObjectValue.rootObject());
        readOnly.createLocalSlot(
                "read",
                ProtosClosureValue.nativeClosure(
                        (x, args) -> ProtosNullValue.INSTANCE));
        ProtosObjectValue writeOnly =
                new ProtosObjectValue(ProtosObjectValue.rootObject());
        writeOnly.createLocalSlot(
                "write",
                ProtosClosureValue.nativeClosure(
                        (x, args) -> ProtosNullValue.INSTANCE));

        assertSame(
                ProtosObjectValue.rootObject(),
                ProtosValueLookup.lookup(readOnly, "close", prelude)
                        .orElseThrow()
                        .home());
        assertSame(
                ProtosObjectValue.rootObject(),
                ProtosValueLookup.lookup(writeOnly, "close", prelude)
                        .orElseThrow()
                        .home());

        ProtosObjectValue readerFactory =
                ProtosStandardModuleMemberTestSupport.bufferedReaderFactory(prelude);
        ProtosObjectValue writerFactory =
                ProtosStandardModuleMemberTestSupport.bufferedWriterFactory(prelude);

        assertThrows(
                ProtosSignalException.class,
                () -> call(readerFactory, "owning", List.of(readOnly), activation));
        assertThrows(
                ProtosSignalException.class,
                () -> call(writerFactory, "owning", List.of(writeOnly), activation));
    }

    @Test
    void invalidArgumentsUseFailedFutureAfterDispatch() throws Exception {
        ProtosPrelude prelude = core();
        ProtosActivation activation = prelude.newModuleActivation();
        ProtosObjectValue factory =
                ProtosStandardModuleMemberTestSupport.bufferedReaderFactory(prelude);
        ProtosObjectValue reader =
                (ProtosObjectValue)
                        call(
                                factory,
                                "call",
                                List.of(source(activation)),
                                activation);
        ProtosFutureValue future =
                (ProtosFutureValue)
                        call(reader, "read", List.of(i(0)), activation);
        assertEquals(ProtosFutureValue.State.FAILED, future.state());
    }

    // I091: read maximum is an exact positive Integer in the int range, checked before any effect.
    @Test
    void readMaximumOutsideItsExactDomainFailsWithoutTouchingTheSource() throws Exception {
        ProtosPrelude prelude = core();
        ProtosActivation activation = prelude.newModuleActivation();
        ProtosObjectValue source = source(activation);
        Counter counter = (Counter) source.readLocalSlot("counter").orElseThrow();
        ProtosObjectValue reader =
                (ProtosObjectValue)
                        call(
                                ProtosStandardModuleMemberTestSupport.bufferedReaderFactory(prelude),
                                "call",
                                List.of(source),
                                activation);
        Object[] invalid = {
            i(-1),
            i(0),
            i((1L << 32) + 1),
            i(Long.MIN_VALUE),
            com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerFromHost(java.math.BigInteger.ONE.shiftLeft(64), prelude),
            com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerFromHost(java.math.BigInteger.ONE.shiftLeft(64).add(java.math.BigInteger.ONE), prelude),
            new ProtosFloatValue(2.0d),
            Long.valueOf(2L),
        };
        for (Object maximum : invalid) {
            ProtosFutureValue future =
                    (ProtosFutureValue) call(reader, "read", List.of(maximum), activation);
            assertEquals(ProtosFutureValue.State.FAILED, future.state(), String.valueOf(maximum));
            assertSame(
                    prelude.bindings().readLocalSlot("InvalidIOArgument").orElseThrow(),
                    future.failedError().orElseThrow().parent().orElseThrow());
        }
        assertEquals(0, counter.reads);

        ProtosFutureValue one = (ProtosFutureValue) call(reader, "read", List.of(i(1)), activation);
        ProtosFutureValue rest =
                (ProtosFutureValue) call(reader, "read", List.of(i(Integer.MAX_VALUE)), activation);

        assertEquals(List.of(1), ints((ProtosBytesValue) one.resolvedValue().orElseThrow()));
        // The int maximum is a bound, not an allocation: only the three buffered octets return.
        assertEquals(List.of(2, 3, 4), ints((ProtosBytesValue) rest.resolvedValue().orElseThrow()));
        assertEquals(1, counter.reads);
    }

    @Test
    void readReturnsExtremeOctetsExactly() throws Exception {
        ProtosPrelude prelude = core();
        ProtosActivation activation = prelude.newModuleActivation();
        ProtosObjectValue source = new ProtosObjectValue(ProtosObjectValue.rootObject());
        source.createLocalSlot(
                "read",
                ProtosClosureValue.nativeClosure(
                        (x, args) -> {
                            ProtosFutureValue future =
                                    new ProtosFutureValue(
                                            x.prelude().orElseThrow().futurePrototype(),
                                            x.executionDomain());
                            future.resolve(
                                    bytes(new ProtosObjectValue(ProtosObjectValue.rootObject()), 0, 255),
                                    x);
                            return future;
                        }));
        ProtosObjectValue reader =
                (ProtosObjectValue)
                        call(
                                ProtosStandardModuleMemberTestSupport.bufferedReaderFactory(prelude),
                                "call",
                                List.of(source),
                                activation);

        ProtosFutureValue read = (ProtosFutureValue) call(reader, "read", List.of(i(2)), activation);

        ProtosBytesValue result = (ProtosBytesValue) read.resolvedValue().orElseThrow();
        assertEquals(List.of(0, 255), ints(result));
        assertTrue(ProtosNumericValueSupport.isIntegerInLongRange(result.indexedAt(1)));
    }

    @Test
    void closeCutoverTerminatesUncommittedReadsWithoutWaitingForDiscardedLowerAftermath()
            throws Exception {
        ProtosPrelude prelude = core();
        ProtosActivation activation = prelude.newModuleActivation();
        PendingReadSource pending = pendingReadSource(activation, true);
        ProtosObjectValue factory =
                ProtosStandardModuleMemberTestSupport.bufferedReaderFactory(prelude);
        ProtosObjectValue reader =
                (ProtosObjectValue)
                        call(factory, "call", List.of(pending.source), activation);

        ProtosFutureValue first =
                (ProtosFutureValue)
                        call(reader, "read", List.of(i(1)), activation);
        ProtosFutureValue second =
                (ProtosFutureValue)
                        call(reader, "read", List.of(i(1)), activation);

        ProtosFutureValue close =
                (ProtosFutureValue)
                        call(reader, "close", List.of(), activation);

        assertEquals(ProtosFutureValue.State.FAILED, first.state());
        assertEquals(ProtosFutureValue.State.FAILED, second.state());
        assertEquals(ProtosFutureValue.State.RESOLVED, close.state());
        assertSame(reader, close.resolvedValue().orElseThrow());
        assertEquals(1, pending.cancelRequests);

        ProtosBytesValue result =
                bytes(new ProtosObjectValue(ProtosObjectValue.rootObject()), 41);
        pending.lower.resolve(result, activation);
        com.guillermomolina.protos.execution.ProtosTestExecutionSupport.dispatchUntilIdle(activation.executionDomain());

        assertEquals(ProtosFutureValue.State.RESOLVED, close.state());
        assertSame(reader, close.resolvedValue().orElseThrow());
    }
    @Test
    void cancellingActiveReadPropagatesToLowerFutureAndPreservesCancelledOutcome()
            throws Exception {
        ProtosPrelude prelude = core();
        ProtosActivation activation = prelude.newModuleActivation();
        PendingReadSource pending = pendingReadSource(activation, false);
        ProtosObjectValue factory =
                ProtosStandardModuleMemberTestSupport.bufferedReaderFactory(prelude);
        ProtosObjectValue reader =
                (ProtosObjectValue)
                        call(factory, "call", List.of(pending.source), activation);

        ProtosFutureValue read =
                (ProtosFutureValue)
                        call(reader, "read", List.of(i(2)), activation);

        assertTrue(read.cancelRequest());
        com.guillermomolina.protos.execution.ProtosTestExecutionSupport.dispatchUntilIdle(activation.executionDomain());
        assertEquals(1, pending.cancelRequests);
        assertEquals(ProtosFutureValue.State.CANCELLED, read.state());
    }

    @Test
    void closeCutoverDoesNotDuplicateAnInFlightBufferedFlush()
            throws Exception {
        ProtosPrelude prelude = core();
        ProtosActivation activation = prelude.newModuleActivation();
        PendingWriteTarget pending = pendingWriteTarget(activation);
        ProtosObjectValue factory =
                ProtosStandardModuleMemberTestSupport.bufferedWriterFactory(prelude);
        ProtosObjectValue writer =
                (ProtosObjectValue)
                        call(factory, "call", List.of(pending.target), activation);

        call(
                writer,
                "write",
                List.of(bytes(pending.bytesPrototype, 1, 2, 3)),
                activation);
        ProtosFutureValue flush =
                (ProtosFutureValue)
                        call(writer, "flush", List.of(), activation);
        assertEquals(ProtosFutureValue.State.PENDING, flush.state());

        ProtosFutureValue close =
                (ProtosFutureValue)
                        call(writer, "close", List.of(), activation);

        assertEquals(ProtosFutureValue.State.PENDING, flush.state());
        assertEquals(ProtosFutureValue.State.PENDING, close.state());
        assertEquals(1, pending.counter.writes);
        assertEquals(1, pending.cancelRequests);

        pending.lowerWrite.resolve(pending.target, activation);
        com.guillermomolina.protos.execution.ProtosTestExecutionSupport.dispatchUntilIdle(activation.executionDomain());

        assertEquals(ProtosFutureValue.State.RESOLVED, flush.state());
        assertSame(writer, flush.resolvedValue().orElseThrow());
        assertEquals(ProtosFutureValue.State.RESOLVED, close.state());
        assertEquals(1, pending.counter.writes);
        assertEquals(1, pending.counter.flushes);
    }
    @Test
    void owningCloseWaitsForOwnedTargetCloseEvenWhenWrapperFinalizationFailed()
            throws Exception {
        ProtosPrelude prelude = core();
        ProtosActivation activation = prelude.newModuleActivation();
        FailingWritePendingCloseTarget target =
                failingWritePendingCloseTarget(activation);
        ProtosObjectValue factory =
                ProtosStandardModuleMemberTestSupport.bufferedWriterFactory(prelude);
        ProtosObjectValue writer =
                (ProtosObjectValue)
                        call(
                                factory,
                                "owning",
                                List.of(target.target),
                                activation);

        call(
                writer,
                "write",
                List.of(bytes(target.bytesPrototype, 9, 8)),
                activation);
        ProtosFutureValue close =
                (ProtosFutureValue)
                        call(writer, "close", List.of(), activation);

        assertEquals(1, target.counter.writes);
        assertEquals(1, target.counter.closes);
        assertEquals(ProtosFutureValue.State.PENDING, close.state());

        target.lowerClose.resolve(target.target, activation);
        com.guillermomolina.protos.execution.ProtosTestExecutionSupport.dispatchUntilIdle(activation.executionDomain());

        assertEquals(ProtosFutureValue.State.FAILED, close.state());
    }

    @Test
    void lowerCancelledWithoutOuterCutoverIsBufferedFlushFailureNotOuterCancellation()
            throws Exception {
        ProtosPrelude prelude = core();
        ProtosActivation activation = prelude.newModuleActivation();
        PendingWriteTarget pending = pendingWriteTarget(activation);
        ProtosObjectValue factory =
                ProtosStandardModuleMemberTestSupport.bufferedWriterFactory(prelude);
        ProtosObjectValue writer =
                (ProtosObjectValue)
                        call(factory, "call", List.of(pending.target), activation);

        call(
                writer,
                "write",
                List.of(bytes(pending.bytesPrototype, 7, 8)),
                activation);
        ProtosFutureValue flush =
                (ProtosFutureValue)
                        call(writer, "flush", List.of(), activation);

        pending.lowerWrite.cancelTerminal();
        com.guillermomolina.protos.execution.ProtosTestExecutionSupport.dispatchUntilIdle(activation.executionDomain());

        assertEquals(ProtosFutureValue.State.FAILED, flush.state());
        assertSame(
                prelude.bindings().readLocalSlot("IOError").orElseThrow(),
                flush.failedError().orElseThrow().parent().orElseThrow());
        assertEquals(1, pending.counter.writes);
        assertEquals(0, pending.counter.flushes);
    }

    @Test
    void closeCutoverRetriesBufferedReleaseOnlyAfterLowerProvesZeroEffect()
            throws Exception {
        ProtosPrelude prelude = core();
        ProtosActivation activation = prelude.newModuleActivation();
        PendingWriteTarget pending = pendingWriteTarget(activation);
        ProtosObjectValue factory =
                ProtosStandardModuleMemberTestSupport.bufferedWriterFactory(prelude);
        ProtosObjectValue writer =
                (ProtosObjectValue)
                        call(factory, "call", List.of(pending.target), activation);

        call(
                writer,
                "write",
                List.of(bytes(pending.bytesPrototype, 1, 2, 3)),
                activation);
        ProtosFutureValue flush =
                (ProtosFutureValue)
                        call(writer, "flush", List.of(), activation);
        ProtosFutureValue firstLower = pending.lowerWrite;
        ProtosFutureValue close =
                (ProtosFutureValue)
                        call(writer, "close", List.of(), activation);

        firstLower.cancelTerminal();
        com.guillermomolina.protos.execution.ProtosTestExecutionSupport.dispatchUntilIdle(activation.executionDomain());

        assertEquals(ProtosFutureValue.State.FAILED, flush.state());
        assertSame(
                prelude.bindings().readLocalSlot("IOLifecycleError").orElseThrow(),
                flush.failedError().orElseThrow().parent().orElseThrow());
        assertEquals(2, pending.counter.writes);
        assertNotSame(firstLower, pending.lowerWrite);
        assertEquals(ProtosFutureValue.State.PENDING, close.state());

        pending.lowerWrite.resolve(pending.target, activation);
        com.guillermomolina.protos.execution.ProtosTestExecutionSupport.dispatchUntilIdle(activation.executionDomain());

        assertEquals(ProtosFutureValue.State.RESOLVED, close.state());
        assertEquals(1, pending.counter.flushes);
    }

    @Test
    void unknownLowerWriteFailureBeatsCloseCutoverAndPoisonsWithoutReplay()
            throws Exception {
        ProtosPrelude prelude = core();
        ProtosActivation activation = prelude.newModuleActivation();
        PendingWriteTarget pending = pendingWriteTarget(activation);
        ProtosObjectValue factory =
                ProtosStandardModuleMemberTestSupport.bufferedWriterFactory(prelude);
        ProtosObjectValue writer =
                (ProtosObjectValue)
                        call(factory, "call", List.of(pending.target), activation);

        call(
                writer,
                "write",
                List.of(bytes(pending.bytesPrototype, 4, 5, 6)),
                activation);
        ProtosFutureValue flush =
                (ProtosFutureValue)
                        call(writer, "flush", List.of(), activation);
        ProtosFutureValue close =
                (ProtosFutureValue)
                        call(writer, "close", List.of(), activation);
        ProtosObjectValue failure =
                ProtosCoreErrors.newOccurrence(
                        activation, ProtosCoreErrors.StandardError.I_O_ERROR);

        pending.lowerWrite.fail(failure);
        com.guillermomolina.protos.execution.ProtosTestExecutionSupport.dispatchUntilIdle(activation.executionDomain());

        assertEquals(ProtosFutureValue.State.FAILED, flush.state());
        assertSame(failure, flush.failedError().orElseThrow());
        assertEquals(ProtosFutureValue.State.FAILED, close.state());
        assertSame(failure, close.failedError().orElseThrow());
        assertEquals(1, pending.counter.writes);
        assertEquals(0, pending.counter.flushes);
        assertEquals(1, pending.cancelRequests);
    }

    private static final class Counter {
        int reads;
        int writes;
        int flushes;
        int closes;
    }

    private static final class PendingReadSource {
        final ProtosObjectValue source;
        final ProtosFutureValue lower;
        int cancelRequests;

        PendingReadSource(ProtosObjectValue source, ProtosFutureValue lower) {
            this.source = source;
            this.lower = lower;
        }
    }

    private static PendingReadSource pendingReadSource(
            ProtosActivation activation, boolean ignoreCancellation) {
        ProtosObjectValue source =
                new ProtosObjectValue(ProtosObjectValue.rootObject());
        ProtosFutureValue lower =
                new ProtosFutureValue(
                        activation.prelude().orElseThrow().futurePrototype(),
                        activation.executionDomain());
        PendingReadSource pending = new PendingReadSource(source, lower);
        lower.attachCancellationProducer(
                () -> {
                    pending.cancelRequests++;
                    if (!ignoreCancellation) lower.cancelTerminal();
                });
        source.createLocalSlot(
                "read",
                ProtosClosureValue.nativeClosure((x, args) -> lower));
        return pending;
    }

    private static final class PendingWriteTarget {
        final ProtosObjectValue target;
        final ProtosObjectValue bytesPrototype;
        final Counter counter;
        ProtosFutureValue lowerWrite;
        int cancelRequests;

        PendingWriteTarget(
                ProtosObjectValue target,
                ProtosObjectValue bytesPrototype,
                Counter counter) {
            this.target = target;
            this.bytesPrototype = bytesPrototype;
            this.counter = counter;
        }
    }

    private static PendingWriteTarget pendingWriteTarget(
            ProtosActivation activation) {
        ProtosObjectValue target =
                new ProtosObjectValue(ProtosObjectValue.rootObject());
        ProtosObjectValue bytesPrototype =
                new ProtosObjectValue(ProtosObjectValue.rootObject());
        ProtosStandardBytesProtocol.install(bytesPrototype);
        Counter counter = new Counter();
        PendingWriteTarget pending =
                new PendingWriteTarget(target, bytesPrototype, counter);

        target.createLocalSlot(
                "write",
                ProtosClosureValue.nativeClosure(
                        (x, args) -> {
                            counter.writes++;
                            ProtosFutureValue future =
                                    new ProtosFutureValue(
                                            x.prelude()
                                                    .orElseThrow()
                                                    .futurePrototype(),
                                            x.executionDomain());
                            pending.lowerWrite = future;
                            future.attachCancellationProducer(
                                    () -> pending.cancelRequests++);
                            return future;
                        }));
        target.createLocalSlot(
                "flush",
                ProtosClosureValue.nativeClosure(
                        (x, args) -> {
                            counter.flushes++;
                            ProtosFutureValue future =
                                    new ProtosFutureValue(
                                            x.prelude()
                                                    .orElseThrow()
                                                    .futurePrototype(),
                                            x.executionDomain());
                            future.resolve(target, x);
                            return future;
                        }));
        return pending;
    }

    private static final class FailingWritePendingCloseTarget {
        final ProtosObjectValue target;
        final ProtosObjectValue bytesPrototype;
        final Counter counter;
        ProtosFutureValue lowerClose;

        FailingWritePendingCloseTarget(
                ProtosObjectValue target,
                ProtosObjectValue bytesPrototype,
                Counter counter) {
            this.target = target;
            this.bytesPrototype = bytesPrototype;
            this.counter = counter;
        }
    }

    private static FailingWritePendingCloseTarget failingWritePendingCloseTarget(
            ProtosActivation activation) {
        ProtosObjectValue target =
                new ProtosObjectValue(ProtosObjectValue.rootObject());
        ProtosObjectValue bytesPrototype =
                new ProtosObjectValue(ProtosObjectValue.rootObject());
        ProtosStandardBytesProtocol.install(bytesPrototype);
        Counter counter = new Counter();
        FailingWritePendingCloseTarget result =
                new FailingWritePendingCloseTarget(
                        target, bytesPrototype, counter);

        target.createLocalSlot(
                "write",
                ProtosClosureValue.nativeClosure(
                        (x, args) -> {
                            counter.writes++;
                            ProtosFutureValue future =
                                    new ProtosFutureValue(
                                            x.prelude()
                                                    .orElseThrow()
                                                    .futurePrototype(),
                                            x.executionDomain());
                            future.fail(
                                    ProtosCoreErrors.newOccurrence(
                                            x,
                                            ProtosCoreErrors.StandardError.I_O_ERROR));
                            return future;
                        }));
        target.createLocalSlot(
                "flush",
                ProtosClosureValue.nativeClosure(
                        (x, args) -> {
                            counter.flushes++;
                            ProtosFutureValue future =
                                    new ProtosFutureValue(
                                            x.prelude()
                                                    .orElseThrow()
                                                    .futurePrototype(),
                                            x.executionDomain());
                            future.resolve(target, x);
                            return future;
                        }));
        target.createLocalSlot(
                "close",
                ProtosClosureValue.nativeClosure(
                        (x, args) -> {
                            counter.closes++;
                            ProtosFutureValue future =
                                    new ProtosFutureValue(
                                            x.prelude()
                                                    .orElseThrow()
                                                    .futurePrototype(),
                                            x.executionDomain());
                            result.lowerClose = future;
                            future.attachCancellationProducer(() -> {});
                            return future;
                        }));
        return result;
    }

    private static ProtosObjectValue source(ProtosActivation activation) {
        ProtosObjectValue source =
                new ProtosObjectValue(ProtosObjectValue.rootObject());
        Counter counter = new Counter();
        source.createLocalSlot("counter", counter);
        source.createLocalSlot(
                "read",
                ProtosClosureValue.nativeClosure(
                        (x, args) -> {
                            counter.reads++;
                            ProtosFutureValue future =
                                    new ProtosFutureValue(
                                            x.prelude()
                                                    .orElseThrow()
                                                    .futurePrototype(),
                                            x.executionDomain());
                            ProtosBytesValue bytes =
                                    new ProtosBytesValue(
                                            new ProtosObjectValue(
                                                    ProtosObjectValue
                                                            .rootObject()));
                            for (int n : new int[] {1, 2, 3, 4}) {
                                bytes.indexedAdd(i(n));
                            }
                            future.resolve(bytes, x);
                            return future;
                        }));
        source.createLocalSlot(
                "close",
                ProtosClosureValue.nativeClosure(
                        (x, args) -> {
                            counter.closes++;
                            ProtosFutureValue future =
                                    new ProtosFutureValue(
                                            x.prelude()
                                                    .orElseThrow()
                                                    .futurePrototype(),
                                            x.executionDomain());
                            future.resolve(source, x);
                            return future;
                        }));
        return source;
    }

    private static ProtosObjectValue target(ProtosActivation activation) {
        ProtosObjectValue target =
                new ProtosObjectValue(ProtosObjectValue.rootObject());
        ProtosObjectValue bytesPrototype =
                new ProtosObjectValue(ProtosObjectValue.rootObject());
        ProtosStandardBytesProtocol.install(bytesPrototype);
        Counter counter = new Counter();
        target.createLocalSlot("bp", bytesPrototype);
        target.createLocalSlot("counter", counter);
        target.createLocalSlot(
                "write",
                ProtosClosureValue.nativeClosure(
                        (x, args) -> {
                            counter.writes++;
                            ProtosFutureValue future =
                                    new ProtosFutureValue(
                                            x.prelude()
                                                    .orElseThrow()
                                                    .futurePrototype(),
                                            x.executionDomain());
                            future.resolve(target, x);
                            return future;
                        }));
        target.createLocalSlot(
                "flush",
                ProtosClosureValue.nativeClosure(
                        (x, args) -> {
                            counter.flushes++;
                            ProtosFutureValue future =
                                    new ProtosFutureValue(
                                            x.prelude()
                                                    .orElseThrow()
                                                    .futurePrototype(),
                                            x.executionDomain());
                            future.resolve(target, x);
                            return future;
                        }));
        target.createLocalSlot(
                "close",
                ProtosClosureValue.nativeClosure(
                        (x, args) -> {
                            counter.closes++;
                            ProtosFutureValue future =
                                    new ProtosFutureValue(
                                            x.prelude()
                                                    .orElseThrow()
                                                    .futurePrototype(),
                                            x.executionDomain());
                            future.resolve(target, x);
                            return future;
                        }));
        return target;
    }

    private static List<Integer> ints(ProtosBytesValue bytes) {
        ArrayList<Integer> result = new ArrayList<>();
        for (int j = 0, n = bytes.indexedSize(); j < n; j++) {
            result.add(
                    Integer.parseInt(com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerDecimalText(bytes.indexedAt(j))));
        }
        return result;
    }
}