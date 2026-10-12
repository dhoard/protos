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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.guillermomolina.protos.execution.ProtosForeignAdmissionDescriptor.Capability;
import com.guillermomolina.protos.execution.ProtosForeignValueFixture.Binary64;
import com.guillermomolina.protos.execution.ProtosForeignValueFixture.Fake;
import com.guillermomolina.protos.execution.ProtosForeignValueFixture.Integral;
import com.guillermomolina.protos.execution.ProtosForeignValueFixture.Marker;
import com.guillermomolina.protos.execution.ProtosForeignValueFixture.TestForeignFailure;
import com.guillermomolina.protos.runtime.ProtosBooleanValue;
import com.guillermomolina.protos.runtime.ProtosClosureValue;
import com.guillermomolina.protos.runtime.ProtosNullValue;
import com.guillermomolina.protos.runtime.ProtosObjectValue;
import com.guillermomolina.protos.runtime.ProtosTask;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** I082-E D189 synchronous foreign callbacks: identity, admission, reentrancy, and lifetime. */
class ProtosForeignCallbackTest {
    private static final String M = "m: import(\"test:root\")\n";

    /** Root module with one executable {@code fn} whose provider behavior each test supplies. */
    private static final class Setup implements AutoCloseable {
        final Fake root = new Fake(false);
        final Fake fn = new Fake(false, Capability.EXECUTABLE);
        final Fake stable = new Fake(true);
        final ProtosForeignValueFixture fixture;

        Setup() throws Exception {
            fixture = new ProtosForeignValueFixture();
            root.member("fn", fn).member("stable", stable);
            fixture.provider.modules.put("root", root);
        }

        Object eval(String source) {
            return fixture.eval(M + source);
        }

        ProtosObjectValue failure(String source) {
            return fixture.failure(M + source);
        }

        /** Installs a native Closure on the facade; the program reads it as {@code m.probe}. */
        void probe(ProtosClosureValue probe) {
            fixture.facade("root").createLocalSlot("probe", probe);
        }

        private boolean closed;

        @Override
        public void close() {
            if (!closed) {
                closed = true;
                fixture.close();
            }
        }
    }

    /** Provider-side invocation; provider failures and carriers propagate unchanged. */
    private static ProtosForeignArgument invoke(ProtosForeignArgument callback, Object... values) {
        try {
            return callback.callback().invoke(List.of(values));
        } catch (RuntimeException unchanged) {
            throw unchanged;
        } catch (Exception checked) {
            throw new IllegalStateException(checked);
        }
    }

    /** Hands a projected callback result back to Protos as the provider's own value. */
    private static Object back(ProtosForeignArgument result) {
        return switch (result.kind()) {
            case INTEGER -> new Integral((BigInteger) ProtosForeignValueFixture.scalar(result));
            case BINARY64 -> new Binary64((Double) result.value());
            case NULL -> Marker.NULL;
            default -> result.value();
        };
    }

    private static long integer(Object value) {
        return Long.parseLong(com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerDecimalText(value));
    }

    @Test
    void closureCrossesAsTheExactValueWithD188AdmittedArgumentsAndLosslessResult()
            throws Exception {
        try (Setup setup = new Setup()) {
            AtomicReference<Object> seen = new AtomicReference<>();
            setup.fn.body =
                    arguments -> {
                        ProtosForeignArgument callback = arguments.get(0);
                        assertTrue(callback.isCallback());
                        assertInstanceOf(ProtosForeignCallback.class, callback.value());
                        seen.set(callback.callback().callableOrNullForRuntime());
                        return back(
                                invoke(
                                        callback,
                                        new Integral(BigInteger.TWO),
                                        "s",
                                        true,
                                        Marker.NULL,
                                        new Binary64(1.5),
                                        setup.stable));
                    };
            Object result =
                    setup.eval(
                            "n: 10\n"
                                    + "ok: false\n"
                                    + "c: (a, s, b, z, f, r) => {\n"
                                    + "    ok = (s == \"s\").and() { b == true }.and() { z == null }"
                                    + ".and() { f == 1.5 }.and() { r === m.stable }\n"
                                    + "    n = n + a\n"
                                    + "    n\n"
                                    + "}\n"
                                    + "m.keep: c\n"
                                    + "(m.fn(c) == 12).and() { ok }.and() { n == 12 }");
            assertSame(ProtosBooleanValue.TRUE, result);
            // The capability denoted the exact Closure; nothing was copied or wrapped.
            assertSame(
                    setup.fixture.facade("root").readLocalSlot("keep").orElseThrow(), seen.get());
            assertTrue(setup.fixture.provider.leaks.isEmpty());
        }
    }

    @Test
    void ordinaryInvocationKeepsReceiverAndLexicalEnvironment() throws Exception {
        try (Setup setup = new Setup()) {
            setup.fn.body = arguments -> back(invoke(arguments.get(0), new Integral(BigInteger.ONE)));
            assertEquals(
                    6,
                    integer(
                            setup.eval(
                                    "o: {\n    k: 5\n    f: () => m.fn((x) => x + this.k)\n}\n"
                                            + "o.f()")));
        }
    }

    @Test
    void rawResultReturnsOnlyFaithfullyAndIdentityBearingResultsAreNeverExported()
            throws Exception {
        try (Setup setup = new Setup()) {
            List<Object> results = new ArrayList<>();
            List<RuntimeException> rejections = new ArrayList<>();
            setup.fn.body =
                    arguments -> {
                        try {
                            ProtosForeignArgument result = invoke(arguments.get(0));
                            results.add(result);
                            return back(result);
                        } catch (ProtosForeignCallback.Rejection rejected) {
                            rejections.add(rejected);
                            throw rejected;
                        }
                    };
            assertSame(ProtosBooleanValue.TRUE, setup.eval("m.fn(() => m.stable) === m.stable"));
            ProtosForeignArgument raw = (ProtosForeignArgument) results.get(0);
            assertEquals(ProtosForeignAdmissionDescriptor.Kind.RAW, raw.kind());
            assertSame(setup.stable, raw.value());

            for (String result :
                    List.of("Object()", "Array(1)", "Map()", "() => 1", "(() => 1).future()", "m")) {
                ProtosObjectValue error = setup.failure("m.fn(() => " + result + ")");
                assertSame(
                        setup.fixture.standardError("ForeignError"),
                        error.parent().orElseThrow(),
                        result);
            }
            assertEquals(6, rejections.size());
            assertEquals(1, results.size());
        }
    }

    @Test
    void sequentialRecursiveAndNestedCallbacksShareOneLogicalExecution() throws Exception {
        try (Setup setup = new Setup()) {
            setup.fn.body =
                    arguments -> {
                        if (arguments.size() == 1) {
                            BigInteger sum = BigInteger.ZERO;
                            for (int i = 1; i <= 3; i++) {
                                sum =
                                        sum.add(
                                                (BigInteger)
                                                        ProtosForeignValueFixture.scalar(
                                                                invoke(
                                                                        arguments.get(0),
                                                                        new Integral(
                                                                                BigInteger.valueOf(i)))));
                            }
                            return new Integral(sum);
                        }
                        return back(
                                invoke(
                                        arguments.get(0),
                                        new Integral(
                                                (BigInteger)
                                                        ProtosForeignValueFixture.scalar(
                                                                arguments.get(1)))));
                    };
            assertEquals(12, integer(setup.eval("m.fn((i) => i * 2)")));
            // Each level is a nested foreign call whose callback re-enters the same execution.
            assertEquals(
                    15,
                    integer(
                            setup.eval(
                                    "f: (k) => (k == 0).ifTrueIfFalse(() => 0, () => k + m.fn(f, k - 1))\n"
                                            + "m.fn(f, 5)")));
        }
    }

    @Test
    void callbackRunsInTheCurrentTaskAndCreatesNoTaskOrFuture() throws Exception {
        try (Setup setup = new Setup()) {
            List<ProtosTask> tasks = new ArrayList<>();
            List<Integer> children = new ArrayList<>();
            setup.probe(
                    ProtosClosureValue.nativeClosure(
                            (activation, supplied) -> {
                                ProtosTask task = activation.task().orElseThrow();
                                tasks.add(task);
                                children.add(task.children().size());
                                return ProtosNullValue.INSTANCE;
                            }));
            setup.fn.body = arguments -> back(invoke(arguments.get(0)));
            setup.eval("m.probe()\nm.fn(m.probe)\nm.fn(() => m.probe())");
            assertEquals(3, tasks.size());
            assertNotNull(tasks.get(0));
            assertSame(tasks.get(0), tasks.get(1));
            assertSame(tasks.get(0), tasks.get(2));
            assertEquals(List.of(0, 0, 0), children);
        }
    }

    @Test
    void foreignThreadsAndConcurrentEntryAreRejectedBeforeGuestCode() throws Exception {
        try (Setup setup = new Setup()) {
            AtomicReference<ProtosForeignArgument> held = new AtomicReference<>();
            List<Throwable> rejected = new ArrayList<>();
            setup.probe(
                    ProtosClosureValue.nativeClosure(
                            (activation, supplied) -> {
                                // Guest code of the live callback is running: try to enter it again.
                                rejected.add(fromThread(held.get()));
                                return ProtosNullValue.INSTANCE;
                            }));
            setup.fn.body =
                    arguments -> {
                        held.set(arguments.get(0));
                        rejected.add(fromThread(arguments.get(0)));
                        return back(invoke(arguments.get(0)));
                    };
            assertEquals(
                    1,
                    integer(setup.eval("hits: 0\nm.fn(() => { hits = hits + 1\n m.probe() })\nhits")));
            assertEquals(2, rejected.size());
            for (Throwable failure : rejected) {
                assertInstanceOf(ProtosForeignCallback.Rejection.class, failure);
            }
        }
    }

    private static Throwable fromThread(ProtosForeignArgument callback) {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread thread = new Thread(() -> failure.set(assertThrows(Throwable.class, () -> invoke(callback))));
        thread.start();
        try {
            thread.join();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
        return failure.get();
    }

    @Test
    void retainedCallbackExpiresWithItsOperationOnEveryExitAndRootsNothing() throws Exception {
        try (Setup setup = new Setup()) {
            List<ProtosForeignArgument> retained = new ArrayList<>();
            setup.fn.body =
                    arguments -> {
                        retained.add(arguments.get(0));
                        if (arguments.size() > 1) {
                            throw new TestForeignFailure("after retaining");
                        }
                        return back(invoke(arguments.get(0)));
                    };
            assertEquals(1, integer(setup.eval("hits: 0\nm.fn(() => { hits = hits + 1 })")));
            ProtosObjectValue failed = setup.failure("m.fn(() => 1, 0)");
            assertSame(setup.fixture.standardError("ForeignError"), failed.parent().orElseThrow());
            // The next operation on the same session has its own, independent lifetime.
            assertEquals(1, integer(setup.eval("m.fn(() => 1)")));
            // Same thread as the Protos segment: only the expired lifetime rejects.
            for (ProtosForeignArgument callback : retained) {
                assertThrows(ProtosForeignCallback.Rejection.class, () -> invoke(callback));
                assertNull(callback.callback().callableOrNullForRuntime());
            }
            int sessions = setup.fixture.provider.sessions.size();
            setup.close();
            for (ProtosForeignArgument callback : retained) {
                assertThrows(ProtosForeignCallback.Rejection.class, () -> invoke(callback));
            }
            assertEquals(sessions, setup.fixture.provider.sessions.size());
        }
    }

    @Test
    void closedSessionGenerationRejectsAndNeverRebinds() throws Exception {
        try (Setup setup = new Setup()) {
            AtomicReference<Throwable> rejected = new AtomicReference<>();
            ProtosForeignProviderSessionBinding session =
                    setup.fixture.facade("root").attachmentForRuntime().orElseThrow().session();
            int sessions = setup.fixture.provider.sessions.size();
            setup.fn.body =
                    arguments -> {
                        session.closeForRuntime();
                        rejected.set(assertThrows(Throwable.class, () -> invoke(arguments.get(0))));
                        return Marker.NULL;
                    };
            setup.eval("hits: 0\nm.fn(() => { hits = hits + 1 })\nhits");
            assertInstanceOf(ProtosForeignCallback.Rejection.class, rejected.get());
            assertEquals(sessions, setup.fixture.provider.sessions.size());
        }
    }

    @Test
    void protosErrorRoundTripsExactlyAndForeignReplacementIsAForeignError() throws Exception {
        try (Setup setup = new Setup()) {
            AtomicReference<RuntimeException> stale = new AtomicReference<>();
            setup.fn.body =
                    arguments -> {
                        String mode = (String) arguments.get(1).value();
                        if (mode.equals("stale")) {
                            throw stale.get();
                        }
                        try {
                            return back(invoke(arguments.get(0)));
                        } catch (RuntimeException outcome) {
                            if (mode.equals("swallow")) {
                                stale.set(outcome);
                                return Marker.NULL;
                            }
                            if (mode.equals("wrap")) {
                                throw new TestForeignFailure("wrapped");
                            }
                            throw outcome;
                        }
                    };
            assertSame(
                    ProtosBooleanValue.TRUE,
                    setup.eval(
                            "boom: Error()\n"
                                    + "Error.handle(() => m.fn(() => boom.signal(), \"same\"),"
                                    + " (caught) => caught === boom)"));
            // Errors raised while the callback is nested in another operation round-trip too.
            assertSame(
                    ProtosBooleanValue.TRUE,
                    setup.eval(
                            "boom: Error()\n"
                                    + "Error.handle(() => m.fn(() => m.fn(() => boom.signal(),"
                                    + " \"same\"), \"same\"), (caught) => caught === boom)"));
            ProtosObjectValue wrapped =
                    (ProtosObjectValue)
                            setup.eval(
                                    "got: null\n"
                                            + "Error.handle(() => m.fn(() => Error().signal(), \"wrap\"),"
                                            + " (caught) => { got = caught })\n"
                                            + "got");
            assertSame(setup.fixture.standardError("ForeignError"), wrapped.parent().orElseThrow());
            // A swallowed carrier leaves later operations as an ordinary foreign failure.
            assertSame(
                    ProtosNullValue.INSTANCE,
                    setup.eval("m.fn(() => Error().signal(), \"swallow\")"));
            ProtosObjectValue replayed = setup.failure("m.fn(() => 1, \"stale\")");
            assertSame(setup.fixture.standardError("ForeignError"), replayed.parent().orElseThrow());
        }
    }

    @Test
    void nonLocalReturnKeepsClosureSemanticsAcrossTheForeignFrames() throws Exception {
        try (Setup setup = new Setup()) {
            setup.fn.body = arguments -> back(invoke(arguments.get(0)));
            assertEquals(42, integer(setup.eval("f: () => {\n    m.fn(() => ^42)\n    0\n}\nf()")));
            ProtosObjectValue invalid =
                    setup.failure("g: () => () => ^1\nescaped: g()\nm.fn(escaped)");
            assertSame(setup.fixture.standardError("InvalidReturn"), invalid.parent().orElseThrow());
        }
    }

    @Test
    void actualSuspensionIsRejectedAtItsPointButSynchronousWorkIsValid() throws Exception {
        try (Setup setup = new Setup()) {
            setup.fn.body = arguments -> back(invoke(arguments.get(0)));
            // The rejected wait is an ordinary Error at the suspension point; no waiter remains.
            assertEquals(
                    8,
                    integer(
                            setup.eval(
                                    "f: (() => 1).future()\n"
                                            + "r: m.fn(() => Error.handle(() => f.value(), (c) => 7))\n"
                                            + "r + f.value()")));
            ProtosObjectValue rejected = setup.failure("p: (() => 1).future()\nm.fn(() => p.value())");
            assertSame(setup.fixture.standardError("Error"), rejected.parent().orElseThrow());
            // An already-terminal Future does not suspend.
            assertEquals(
                    3, integer(setup.eval("d: (() => 3).future()\nd.value()\nm.fn(() => d.value())")));
        }
    }

    @Test
    void providersSeeOnlyTheNarrowCapabilityAndCallbackEntryAddsNoCheckpoint() throws Exception {
        for (Method method : ProtosForeignCallback.class.getDeclaredMethods()) {
            if (Modifier.isPrivate(method.getModifiers())) {
                continue;
            }
            for (Class<?> type : method.getParameterTypes()) {
                assertFalse(type.getName().contains(".runtime."), method.getName());
            }
            assertFalse(method.getReturnType().getName().contains(".runtime."), method.getName());
        }
        assertFalse(ProtosClosureValue.class.isAssignableFrom(ProtosForeignArgument.class));
        String scope =
                Files.readString(
                        Path.of(
                                "src/main/java/com/guillermomolina/protos/execution/"
                                        + "ProtosForeignCallbackScope.java"));
        for (String forbidden :
                List.of("cancellationRequested", "observeCancellation", "createTask", "new Thread")) {
            assertFalse(scope.contains(forbidden), forbidden);
        }
        // An operation without a Closure argument creates no callback scope at all.
        try (Setup setup = new Setup()) {
            setup.fn.body = arguments -> {
                for (ProtosForeignArgument argument : arguments) {
                    assertFalse(argument.isCallback());
                }
                return Marker.NULL;
            };
            setup.eval("m.fn(1, \"x\")");
        }
    }
}
