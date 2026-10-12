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
import org.graalvm.polyglot.Context;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

final class ProtosStandardTextWriterProtocolTest {
    private static final Path CORE = Path.of("protos", "lib", "core");

    @Test
    void ordinaryInvocationInsideEnteredContextStaysOnOrdinaryTextWriterPath()
            throws Exception {
        try (Context context = Context.newBuilder(ProtosLanguage.ID).build()) {
            context.initialize(ProtosLanguage.ID);
            context.enter();
            try {
                assertNotNull(
                        ProtosLanguageContext.currentIfEnteredForRuntime(),
                        "test requires an entered Protos language Context");

                ProtosPrelude prelude = core();
                ProtosActivation activation = prelude.newModuleActivation();
                ScriptedTarget target = new ScriptedTarget(activation, false, false);
                ProtosObjectValue writer =
                        writer(
                                prelude,
                                activation,
                                target.target,
                                encoding(prelude, "UTF8"),
                                false);

                ProtosFutureValue result =
                        assertInstanceOf(
                                ProtosFutureValue.class,
                                ProtosInvocation.invokeMessage(
                                        writer,
                                        "writeLine",
                                        List.of(new ProtosStringValue("once")),
                                        activation));

                assertEquals(ProtosFutureValue.State.RESOLVED, result.state());
                assertEquals(
                        1,
                        target.writes,
                        "ordinary Java invocation must not be rerouted merely because a Truffle Context is entered");
                assertEquals(1, target.payloads.size());
                assertArrayEquals(
                        new byte[] {'o', 'n', 'c', 'e', '\n'},
                        target.payloads.get(0));
                assertEquals(
                        0,
                        activation.executionDomain().runnableCount(),
                        "ordinary TextWriter invocation must not schedule a C-prime operation");
            } finally {
                context.leave();
            }
        }
    }

    @Test
    void factoryIsSourceBackedFrozenFreshAndValidatesCapabilitiesSynchronously()
            throws Exception {
        ProtosPrelude prelude = core();
        ProtosActivation activation = prelude.newModuleActivation();
        ProtosObjectValue factory = factory(prelude);
        ProtosEncodingValue utf8 = encoding(prelude, "UTF8");

        assertSame(ProtosObjectValue.rootObject(), factory.parent().orElseThrow());
        assertTrue(factory.isFrozen());
        assertEquals(Set.of("call", "owning"), factory.localSlotsSnapshot().keySet());

        ScriptedTarget target = new ScriptedTarget(activation, true, true);
        ProtosObjectValue first =
                writer(prelude, activation, target.target, utf8, false);
        ProtosObjectValue second =
                writer(prelude, activation, target.target, utf8, false);
        assertNotSame(first, second);
        assertEquals(
                Set.of("writeText", "writeLine", "flush", "close"),
                first.localSlotsSnapshot().keySet());

        ScriptedTarget noClose = new ScriptedTarget(activation, false, false);
        assertThrows(
                ProtosSignalException.class,
                () -> writer(prelude, activation, noClose.target, utf8, true));

        ProtosObjectValue fakeEncoding =
                new ProtosObjectValue(prelude.encodingPrototype());
        assertThrows(
                ProtosSignalException.class,
                () ->
                        com.guillermomolina.protos.execution.ProtosTestExecutionSupport.callEnteredAndDrain(activation, () -> ProtosInvocation.invokeMessage(
                                factory,
                                "call",
                                List.of(target.target, fakeEncoding),
                                activation)));
        assertEquals(0, target.writes);
    }

    @Test
    void emptyWriteDoesNoEncodingOrTargetIoButStaysBehindEarlierOperation()
            throws Exception {
        ProtosPrelude prelude = core();
        ProtosActivation activation = prelude.newModuleActivation();
        ScriptedTarget target = new ScriptedTarget(activation, false, false);
        Pending firstLower = target.pendingWrite();

        ProtosObjectValue writer =
                writer(
                        prelude,
                        activation,
                        target.target,
                        encoding(prelude, "UTF8"),
                        false);

        ProtosFutureValue first = writeText(writer, activation, "A");
        ProtosFutureValue empty = writeText(writer, activation, "");

        assertEquals(ProtosFutureValue.State.PENDING, empty.state());
        assertEquals(1, target.writes);

        firstLower.resolve(target.target, activation);

        assertEquals(ProtosFutureValue.State.RESOLVED, first.state());
        assertEquals(ProtosFutureValue.State.RESOLVED, empty.state());
        assertEquals(
                1,
                target.writes,
                "writeText(\"\") must contribute no target write");
    }

    @Test
    void downstreamWriteFailurePermanentlyPoisonsLaterOutput() throws Exception {
        ProtosPrelude prelude = core();
        ProtosActivation activation = prelude.newModuleActivation();
        ScriptedTarget target = new ScriptedTarget(activation, true, false);
        ProtosObjectValue ioError =
                ProtosCoreErrors.newOccurrence(
                        activation, ProtosCoreErrors.StandardError.I_O_ERROR);
        target.failWrite(ioError);

        ProtosObjectValue writer =
                writer(
                        prelude,
                        activation,
                        target.target,
                        encoding(prelude, "UTF8"),
                        false);

        ProtosFutureValue failed = writeText(writer, activation, "A");
        assertEquals(ProtosFutureValue.State.FAILED, failed.state());
        assertSame(ioError, failed.failedError().orElseThrow());

        ProtosFutureValue later = writeText(writer, activation, "B");
        assertEquals(ProtosFutureValue.State.FAILED, later.state());
        assertSame(ioError, later.failedError().orElseThrow());

        ProtosFutureValue flush = flush(writer, activation);
        assertEquals(ProtosFutureValue.State.FAILED, flush.state());
        assertSame(ioError, flush.failedError().orElseThrow());
        assertEquals(1, target.writes);
        assertEquals(0, target.flushes);
    }

    @Test
    void cancellationBeforeAdmissionContributesNothingAndDoesNotPoison()
            throws Exception {
        ProtosPrelude prelude = core();
        ProtosActivation activation = prelude.newModuleActivation();
        ScriptedTarget target = new ScriptedTarget(activation, false, false);
        Pending firstLower = target.pendingWrite();

        ProtosObjectValue writer =
                writer(
                        prelude,
                        activation,
                        target.target,
                        encoding(prelude, "UTF8"),
                        false);

        ProtosFutureValue first = writeText(writer, activation, "A");
        ProtosFutureValue queued = writeText(writer, activation, "B");
        assertTrue(queued.cancelRequest());
        assertEquals(ProtosFutureValue.State.CANCELLED, queued.state());

        firstLower.resolve(target.target, activation);
        assertEquals(ProtosFutureValue.State.RESOLVED, first.state());
        assertEquals(1, target.writes);

        ProtosFutureValue later = writeText(writer, activation, "C");
        assertEquals(ProtosFutureValue.State.RESOLVED, later.state());
        assertEquals(2, target.writes);
        assertArrayEquals(new byte[] {'C'}, target.payloads.get(1));
    }

    @Test
    void closeCutsOverQueuedUncommittedWorkAndWaitsForCommittedWrite()
            throws Exception {
        ProtosPrelude prelude = core();
        ProtosActivation activation = prelude.newModuleActivation();
        ScriptedTarget target = new ScriptedTarget(activation, false, false);
        Pending firstLower = target.pendingWrite();

        ProtosObjectValue writer =
                writer(
                        prelude,
                        activation,
                        target.target,
                        encoding(prelude, "UTF8"),
                        false);

        ProtosFutureValue first = writeText(writer, activation, "A");
        ProtosFutureValue queued = writeText(writer, activation, "B");
        ProtosFutureValue close = close(writer, activation);

        assertEquals(ProtosFutureValue.State.PENDING, first.state());
        assertEquals(ProtosFutureValue.State.FAILED, queued.state());
        assertErrorParent(
                prelude, queued.failedError().orElseThrow(), "IOLifecycleError");
        assertEquals(ProtosFutureValue.State.PENDING, close.state());

        firstLower.resolve(target.target, activation);

        assertEquals(ProtosFutureValue.State.RESOLVED, first.state());
        assertEquals(ProtosFutureValue.State.RESOLVED, close.state());
        assertEquals(
                ProtosFutureValue.State.FAILED,
                writeText(writer, activation, "later").state());
        assertEquals(1, target.writes);
    }

    @Test
    void owningClosePreservesEarlierWrapperFailureAsPrimaryButStillClosesTarget()
            throws Exception {
        ProtosPrelude prelude = core();
        ProtosActivation activation = prelude.newModuleActivation();
        ScriptedTarget target = new ScriptedTarget(activation, false, true);
        ProtosObjectValue writeError =
                ProtosCoreErrors.newOccurrence(
                        activation, ProtosCoreErrors.StandardError.I_O_ERROR);
        ProtosObjectValue closeError =
                ProtosCoreErrors.newOccurrence(
                        activation, ProtosCoreErrors.StandardError.ERROR);
        target.failWrite(writeError);
        target.failClose(closeError);

        ProtosObjectValue writer =
                writer(
                        prelude,
                        activation,
                        target.target,
                        encoding(prelude, "UTF8"),
                        true);

        ProtosFutureValue write = writeText(writer, activation, "A");
        assertSame(writeError, write.failedError().orElseThrow());

        ProtosFutureValue close = close(writer, activation);
        assertEquals(ProtosFutureValue.State.FAILED, close.state());
        assertSame(writeError, close.failedError().orElseThrow());
        assertEquals(1, target.closes);
    }

    private static ProtosPrelude core() throws Exception {
        return new ProtosCoreBootstrap().bootstrap(CORE);
    }

    private static ProtosObjectValue factory(ProtosPrelude prelude) {
        return assertInstanceOf(
                ProtosObjectValue.class,
                prelude.bindings().readLocalSlot("TextWriter").orElseThrow());
    }

    private static ProtosEncodingValue encoding(ProtosPrelude prelude, String name) {
        return assertInstanceOf(
                ProtosEncodingValue.class,
                prelude.encodingPrototype().readLocalSlot(name).orElseThrow());
    }

    private static ProtosObjectValue writer(
            ProtosPrelude prelude,
            ProtosActivation activation,
            Object target,
            ProtosEncodingValue encoding,
            boolean owning) {
        return assertInstanceOf(
                ProtosObjectValue.class,
                com.guillermomolina.protos.execution.ProtosTestExecutionSupport.callEnteredAndDrain(activation, () -> ProtosInvocation.invokeMessage(
                        factory(prelude),
                        owning ? "owning" : "call",
                        List.of(target, encoding),
                        activation)));
    }

    private static ProtosFutureValue writeText(
            ProtosObjectValue writer, ProtosActivation activation, String text) {
        return assertInstanceOf(
                ProtosFutureValue.class,
                com.guillermomolina.protos.execution.ProtosTestExecutionSupport.callEnteredAndDrain(activation, () -> ProtosInvocation.invokeMessage(
                        writer,
                        "writeText",
                        List.of(new ProtosStringValue(text)),
                        activation)));
    }

    private static ProtosFutureValue writeLine(
            ProtosObjectValue writer, ProtosActivation activation, String text) {
        return assertInstanceOf(
                ProtosFutureValue.class,
                com.guillermomolina.protos.execution.ProtosTestExecutionSupport.callEnteredAndDrain(activation, () -> ProtosInvocation.invokeMessage(
                        writer,
                        "writeLine",
                        List.of(new ProtosStringValue(text)),
                        activation)));
    }

    private static ProtosFutureValue flush(
            ProtosObjectValue writer, ProtosActivation activation) {
        return assertInstanceOf(
                ProtosFutureValue.class,
                com.guillermomolina.protos.execution.ProtosTestExecutionSupport.callEnteredAndDrain(activation, () -> ProtosInvocation.invokeMessage(
                        writer, "flush", List.of(), activation)));
    }

    private static ProtosFutureValue close(
            ProtosObjectValue writer, ProtosActivation activation) {
        return assertInstanceOf(
                ProtosFutureValue.class,
                com.guillermomolina.protos.execution.ProtosTestExecutionSupport.callEnteredAndDrain(activation, () -> ProtosInvocation.invokeMessage(
                        writer, "close", List.of(), activation)));
    }

    private static void assertErrorParent(
            ProtosPrelude prelude, ProtosObjectValue error, String parentName) {
        assertSame(
                prelude.bindings().readLocalSlot(parentName).orElseThrow(),
                error.parent().orElseThrow());
    }

    private static final class Pending {
        final ProtosFutureValue future;

        Pending(ProtosFutureValue future) {
            this.future = future;
        }

        void resolve(Object value, ProtosActivation activation) {
            future.resolve(value, activation);
            com.guillermomolina.protos.execution.ProtosTestExecutionSupport
                    .dispatchUntilIdle(activation.executionDomain());
        }
    }

    private static final class ScriptedTarget {
        private sealed interface WriteStep permits PendingWrite, FailedWrite {}
        private record PendingWrite(Pending pending) implements WriteStep {}
        private record FailedWrite(ProtosObjectValue error) implements WriteStep {}

        final ProtosObjectValue target =
                new ProtosObjectValue(ProtosObjectValue.rootObject());
        final ProtosActivation constructionActivation;
        final ArrayDeque<WriteStep> writeSteps = new ArrayDeque<>();
        final ArrayList<byte[]> payloads = new ArrayList<>();
        boolean failCloseConfigured;
        ProtosObjectValue closeFailure;
        int writes;
        int flushes;
        int closes;

        ScriptedTarget(
                ProtosActivation activation,
                boolean flushable,
                boolean closable) {
            constructionActivation = activation;
            target.createLocalSlot(
                    "write",
                    ProtosClosureValue.nativeClosure(
                            (callActivation, supplied) -> {
                                writes++;
                                ProtosBytesValue bytes =
                                        assertInstanceOf(
                                                ProtosBytesValue.class,
                                                supplied.get(0));
                                payloads.add(snapshot(bytes));

                                WriteStep step = writeSteps.pollFirst();
                                if (step instanceof PendingWrite pending) {
                                    return pending.pending().future;
                                }
                                ProtosFutureValue future = future(callActivation);
                                if (step instanceof FailedWrite failed) {
                                    future.fail(failed.error());
                                } else {
                                    future.resolve(target, callActivation);
                                }
                                return future;
                            }));

            if (flushable) {
                target.createLocalSlot(
                        "flush",
                        ProtosClosureValue.nativeClosure(
                                (callActivation, supplied) -> {
                                    flushes++;
                                    ProtosFutureValue future = future(callActivation);
                                    future.resolve(target, callActivation);
                                    return future;
                                }));
            }

            if (closable) {
                target.createLocalSlot(
                        "close",
                        ProtosClosureValue.nativeClosure(
                                (callActivation, supplied) -> {
                                    closes++;
                                    ProtosFutureValue future = future(callActivation);
                                    if (failCloseConfigured) {
                                        future.fail(closeFailure);
                                    } else {
                                        future.resolve(target, callActivation);
                                    }
                                    return future;
                                }));
            }
        }

        Pending pendingWrite() {
            Pending pending =
                    new Pending(future(constructionActivation));
            writeSteps.addLast(new PendingWrite(pending));
            return pending;
        }

        void failWrite(ProtosObjectValue error) {
            writeSteps.addLast(new FailedWrite(error));
        }

        void failClose(ProtosObjectValue error) {
            failCloseConfigured = true;
            closeFailure = error;
        }

        private ProtosFutureValue future(ProtosActivation activation) {
            return new ProtosFutureValue(
                    activation.prelude().orElseThrow().futurePrototype(),
                    activation.executionDomain());
        }

        private static byte[] snapshot(ProtosBytesValue bytes) {
            List<Object> values = bytes.indexedSnapshot();
            byte[] result = new byte[values.size()];
            for (int i = 0; i < values.size(); i++) {
                int value = Integer.parseInt(com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerDecimalText(values.get(i)));
                result[i] = (byte) value;
            }
            return result;
        }
    }
}
