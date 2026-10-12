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
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

final class ProtosTextReaderLineProtocolTest {
    private static final Path CORE = Path.of("protos", "lib", "core");

    @Test
    void lineTooLongWinsBeforeLaterMalformedInputAndPermanentlyFailsReader()
            throws Exception {
        Fixture f = fixture("UTF8");
        f.source.bytes('a', 'b', 0xc0, 0xaf);

        ProtosFutureValue first =
                readLine(
                        f.reader,
                        f.activation,
                        new ProtosIntegerValue(1L));
        assertEquals(ProtosFutureValue.State.FAILED, first.state());
        ProtosObjectValue lineError = first.failedError().orElseThrow();
        assertErrorParent(f.prelude, lineError, "LineTooLong");

        ProtosFutureValue repeated = readLine(f.reader, f.activation);
        assertEquals(ProtosFutureValue.State.FAILED, repeated.state());
        assertSame(lineError, repeated.failedError().orElseThrow());
        assertEquals(1, f.source.reads);

        Fixture malformed = fixture("UTF8");
        malformed.source.bytes('a', 'b', 0xc0, 0xaf);
        ProtosFutureValue decoding =
                readLine(
                        malformed.reader,
                        malformed.activation,
                        new ProtosIntegerValue(2));
        assertEquals(ProtosFutureValue.State.FAILED, decoding.state());
        assertErrorParent(
                malformed.prelude,
                decoding.failedError().orElseThrow(),
                "EncodingError");
    }

    @Test
    void partialLineIsNotReturnedWhenUnderlyingFailurePrecedesTerminator()
            throws Exception {
        Fixture f = fixture("UTF8");
        ProtosObjectValue ioError =
                ProtosCoreErrors.newOccurrence(
                        f.activation, ProtosCoreErrors.StandardError.I_O_ERROR);
        f.source.bytes('a');
        f.source.failure(ioError);

        ProtosFutureValue line = readLine(f.reader, f.activation);
        assertEquals(ProtosFutureValue.State.FAILED, line.state());
        assertSame(ioError, line.failedError().orElseThrow());

        ProtosFutureValue repeated = readLine(f.reader, f.activation);
        assertEquals(ProtosFutureValue.State.FAILED, repeated.state());
        assertSame(ioError, repeated.failedError().orElseThrow());
        assertEquals(2, f.source.reads);
    }

    @Test
    void successfulCancellationPreservesWholeLineForNextOperation() throws Exception {
        Fixture f = fixture("UTF8");
        Pending pending = f.source.pending(true);

        ProtosFutureValue cancelled = readLine(f.reader, f.activation);
        assertEquals(ProtosFutureValue.State.PENDING, cancelled.state());
        assertTrue(cancelled.cancelRequest());
        assertEquals(ProtosFutureValue.State.CANCELLED, cancelled.state());

        pending.resolve(bytes(f.prelude, 'A', '\n'), f.activation);

        assertEquals("A", stringResult(readLine(f.reader, f.activation)));
        assertEquals(
                1,
                f.source.reads,
                "late read-ahead from cancelled line read must be retained");
    }

    @Test
    void queuedHandoffAdvancesImmediatelyTerminalRequestsIterativelyInOrder() throws Exception {
        Fixture f = fixture("UTF8");
        Pending pending = f.source.pending(true);
        int count = 64;

        ProtosFutureValue first = readLine(f.reader, f.activation);
        List<ProtosFutureValue> lines = new ArrayList<>();
        long[] lineDepths = new long[count];
        for (int index = 0; index < count; index++) {
            lines.add(observedDepth(readLine(f.reader, f.activation), lineDepths, index));
        }
        ProtosFutureValue tooLong =
                readLine(f.reader, f.activation, new ProtosIntegerValue(1L));
        List<ProtosFutureValue> followers = new ArrayList<>();
        long[] followerDepths = new long[count];
        for (int index = 0; index < count; index++) {
            followers.add(
                    observedDepth(readLine(f.reader, f.activation), followerDepths, index));
        }
        assertEquals(ProtosFutureValue.State.PENDING, followers.getLast().state());

        StringBuilder payload = new StringBuilder("F\n");
        for (int index = 0; index < count; index++) {
            payload.append('L').append(index).append('\n');
        }
        payload.append("xyz");
        pending.resolve(bytes(f.prelude, payload.chars().toArray()), f.activation);

        assertEquals("F", stringResult(first));
        for (int index = 0; index < count; index++) {
            assertEquals("L" + index, stringResult(lines.get(index)));
        }
        assertEquals(ProtosFutureValue.State.FAILED, tooLong.state());
        ProtosObjectValue lineError = tooLong.failedError().orElseThrow();
        assertErrorParent(f.prelude, lineError, "LineTooLong");
        for (ProtosFutureValue follower : followers) {
            assertEquals(ProtosFutureValue.State.FAILED, follower.state());
            assertSame(lineError, follower.failedError().orElseThrow());
        }
        assertEquals(1, f.source.reads);
        for (int index = 1; index < count; index++) {
            assertEquals(lineDepths[0], lineDepths[index],
                    "queued line handoff must not grow the Java stack");
            assertEquals(followerDepths[0], followerDepths[index],
                    "queued failure handoff must not grow the Java stack");
        }
    }

    // I091: the line limit is a primitive bound; any Integer beyond signed-64 bounds nothing.
    @Test
    void lineLimitsAreExactAtEveryMagnitudeAndExcludeTheTerminator() throws Exception {
        Fixture f = fixture("UTF8");
        Object beyondLong =
                com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerFromHost(java.math.BigInteger.ONE.shiftLeft(64), f.prelude);
        // "é" is two UTF-8 octets; "a" one; the terminator never counts.
        f.source.bytes('a', '\n', 0xc3, 0xa9, '\n', 'x', 'y', '\n', 'z', '\n', 'w', '\n');
        assertEquals("a", stringResult(readLine(f.reader, f.activation, new ProtosIntegerValue(1L))));
        assertEquals("\u00e9",
                stringResult(readLine(f.reader, f.activation, new ProtosIntegerValue(2L))));
        assertEquals("xy",
                stringResult(readLine(f.reader, f.activation, new ProtosIntegerValue(Long.MAX_VALUE))));
        assertEquals("z", stringResult(readLine(f.reader, f.activation, beyondLong)));
        assertEquals("w", stringResult(readLine(f.reader, f.activation)));

        Fixture multibyte = fixture("UTF8");
        multibyte.source.bytes(0xc3, 0xa9, '\n');
        ProtosFutureValue tooLong =
                readLine(multibyte.reader, multibyte.activation, new ProtosIntegerValue(1L));
        assertEquals(ProtosFutureValue.State.FAILED, tooLong.state());
        assertErrorParent(multibyte.prelude, tooLong.failedError().orElseThrow(), "LineTooLong");
    }

    @Test
    void nonPositiveAndNonIntegerLimitsAreInvalidWithoutReading() throws Exception {
        Fixture f = fixture("UTF8");
        f.source.bytes('a', '\n');
        Object largeNegative =
                com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerFromHost(java.math.BigInteger.ONE.shiftLeft(64).negate(), f.prelude);
        for (Object limit : List.of(
                new ProtosIntegerValue(0L), new ProtosIntegerValue(-1L),
                new ProtosIntegerValue(Long.MIN_VALUE), largeNegative, new ProtosFloatValue(1.0))) {
            assertInvalid(f, readLine(f.reader, f.activation, limit));
        }
        assertEquals(0, f.source.reads);
        assertEquals("a", stringResult(readLine(f.reader, f.activation, new ProtosIntegerValue(1L))));
    }

    @Test
    void cancelledBoundedReadPreservesInputForTheNextRead() throws Exception {
        Fixture f = fixture("UTF8");
        Pending pending = f.source.pending(true);
        Object beyondLong =
                com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerFromHost(java.math.BigInteger.ONE.shiftLeft(70), f.prelude);
        ProtosFutureValue cancelled = readLine(f.reader, f.activation, beyondLong);
        assertTrue(cancelled.cancelRequest());
        assertEquals(ProtosFutureValue.State.CANCELLED, cancelled.state());
        pending.resolve(bytes(f.prelude, 0xc3, 0xa9, '\n'), f.activation);
        assertEquals("\u00e9",
                stringResult(readLine(f.reader, f.activation, new ProtosIntegerValue(2L))));
    }

    private static ProtosFutureValue observedDepth(
            ProtosFutureValue future, long[] depths, int index) {
        assertEquals(ProtosFutureValue.State.PENDING, future.state());
        future.observe(
                ignored ->
                        depths[index] =
                                StackWalker.getInstance().walk(frames -> frames.count()));
        return future;
    }

    private static void assertInvalid(Fixture f, ProtosFutureValue future) {
        assertEquals(ProtosFutureValue.State.FAILED, future.state());
        assertErrorParent(
                f.prelude,
                future.failedError().orElseThrow(),
                "InvalidIOArgument");
    }

    private static Fixture fixture(String encodingName) throws Exception {
        ProtosPrelude prelude = new ProtosCoreBootstrap().bootstrap(CORE);
        ProtosActivation activation = prelude.newModuleActivation();
        ScriptedSource source = new ScriptedSource(activation);
        ProtosObjectValue factory =
                assertInstanceOf(
                        ProtosObjectValue.class,
                        prelude.bindings().readLocalSlot("TextReader").orElseThrow());
        ProtosEncodingValue encoding =
                assertInstanceOf(
                        ProtosEncodingValue.class,
                        prelude.encodingPrototype()
                                .readLocalSlot(encodingName)
                                .orElseThrow());
        ProtosObjectValue reader =
                assertInstanceOf(
                        ProtosObjectValue.class,
                        ProtosInvocation.invokeMessage(
                                factory,
                                "call",
                                List.of(source.source, encoding),
                                activation));
        return new Fixture(prelude, activation, source, reader);
    }

    private static ProtosFutureValue readLine(
            ProtosObjectValue reader, ProtosActivation activation, Object... arguments) {
        return assertInstanceOf(
                ProtosFutureValue.class,
                ProtosInvocation.invokeMessage(
                        reader,
                        "readLine",
                        List.of(arguments),
                        activation));
    }

    private static ProtosFutureValue readText(
            ProtosObjectValue reader, ProtosActivation activation) {
        return assertInstanceOf(
                ProtosFutureValue.class,
                ProtosInvocation.invokeMessage(
                        reader, "readText", List.of(), activation));
    }

    private static String stringResult(ProtosFutureValue future) {
        assertEquals(ProtosFutureValue.State.RESOLVED, future.state());
        return assertInstanceOf(
                        ProtosStringValue.class,
                        future.resolvedValue().orElseThrow())
                .value();
    }

    private static void assertNullResult(ProtosFutureValue future) {
        assertEquals(ProtosFutureValue.State.RESOLVED, future.state());
        assertSame(ProtosNullValue.INSTANCE, future.resolvedValue().orElseThrow());
    }

    private static void assertErrorParent(
            ProtosPrelude prelude, ProtosObjectValue error, String parentName) {
        assertSame(
                prelude.bindings().readLocalSlot(parentName).orElseThrow(),
                error.parent().orElseThrow());
    }

    private static ProtosBytesValue bytes(ProtosPrelude prelude, int... values) {
        ProtosBytesValue bytes =
                new ProtosBytesValue(prelude.bytesPrototypeForRuntime());
        for (int value : values) {
            bytes.indexedAdd(
                    new ProtosIntegerValue(value & 0xff));
        }
        return bytes;
    }

    private record Fixture(
            ProtosPrelude prelude,
            ProtosActivation activation,
            ScriptedSource source,
            ProtosObjectValue reader) {}

    private static final class Pending {
        final ProtosFutureValue future;
        int cancelRequests;

        Pending(ProtosFutureValue future, boolean ignoreCancellation) {
            this.future = future;
            future.attachCancellationProducer(
                    () -> {
                        cancelRequests++;
                        if (!ignoreCancellation) {
                            future.cancelTerminal();
                        }
                    });
        }

        void resolve(Object value, ProtosActivation activation) {
            future.resolve(value, activation);
        }
    }

    private static final class ScriptedSource {
        private sealed interface Step
                permits BytesStep, EofStep, FailureStep, PendingStep {}

        private record BytesStep(int[] values) implements Step {}
        private record EofStep() implements Step {}
        private record FailureStep(ProtosObjectValue error) implements Step {}
        private record PendingStep(Pending pending) implements Step {}

        final ProtosObjectValue source =
                new ProtosObjectValue(ProtosObjectValue.rootObject());
        final ProtosActivation constructionActivation;
        final ArrayDeque<Step> steps = new ArrayDeque<>();
        int reads;

        ScriptedSource(ProtosActivation activation) {
            constructionActivation = activation;
            source.createLocalSlot(
                    "read",
                    ProtosClosureValue.nativeClosure(
                            (callActivation, supplied) -> {
                                reads++;
                                if (supplied.size() != 1) {
                                    throw new AssertionError(
                                            "unexpected source read arity");
                                }
                                Step step = steps.pollFirst();
                                if (step == null) {
                                    throw new AssertionError(
                                            "no scripted read step");
                                }
                                if (step instanceof PendingStep pending) {
                                    return pending.pending().future;
                                }

                                ProtosFutureValue future =
                                        new ProtosFutureValue(
                                                callActivation
                                                        .prelude()
                                                        .orElseThrow()
                                                        .futurePrototype(),
                                                callActivation.executionDomain());
                                if (step instanceof BytesStep bytes) {
                                    future.resolve(
                                            ProtosTextReaderLineProtocolTest.bytes(
                                                    callActivation
                                                            .prelude()
                                                            .orElseThrow(),
                                                    bytes.values()),
                                            callActivation);
                                } else if (step instanceof EofStep) {
                                    future.resolve(
                                            ProtosNullValue.INSTANCE,
                                            callActivation);
                                } else if (step instanceof FailureStep failure) {
                                    future.fail(failure.error());
                                }
                                return future;
                            }));
        }

        void bytes(int... values) {
            steps.addLast(new BytesStep(values.clone()));
        }

        void eof() {
            steps.addLast(new EofStep());
        }

        void failure(ProtosObjectValue error) {
            steps.addLast(new FailureStep(error));
        }

        Pending pending(boolean ignoreCancellation) {
            Pending pending =
                    new Pending(
                            new ProtosFutureValue(
                                    constructionActivation
                                            .prelude()
                                            .orElseThrow()
                                            .futurePrototype(),
                                    constructionActivation.executionDomain()),
                            ignoreCancellation);
            steps.addLast(new PendingStep(pending));
            return pending;
        }
    }
}
