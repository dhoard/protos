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
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.guillermomolina.protos.runtime.ProtosActivation;
import com.guillermomolina.protos.runtime.ProtosActorExecutionDomain;
import com.guillermomolina.protos.runtime.ProtosActorModuleState;
import com.guillermomolina.protos.runtime.ProtosFutureValue;
import com.guillermomolina.protos.runtime.ProtosNumericValueSupport;
import com.guillermomolina.protos.runtime.ProtosPrelude;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * I115: a numeric operator whose selected behavior is not the standard one
 * runs that behavior from inside the numeric operation, and a suspension of
 * that behavior is composed by the Bytecode DSL continuation of the site.
 *
 * <p>Suspension is real: the redefined {@code +} awaits a host-resolved
 * Future gate inside a Task of an Actor execution domain, so the scheduler
 * suspends the Task and resumes it only after the gate resolves. Every
 * scenario checks the result and that the selected method ran exactly once
 * with operands evaluated exactly once.
 */
final class ProtosI115NumericOverloadContinuationTest {
    private static final Path CORE = Path.of("protos", "lib", "core");

    private static final String BOX = """
            log: {
                calls: 0
                after: 0
                trace: 0
            }
            step: (digit, value) => {
                log.trace = log.trace * 10 + digit
                value
            }
            behavior: {
                plus: (other) => {
                    log.calls = log.calls + 1
                    gate.value() + other
                }
            }
            box: { ...behavior.alias("plus", "+") }
            """;

    /* 1. A redefined operator that returns immediately. */
    @Test
    void redefinedOperatorReturningImmediately() throws Exception {
        Harness h = new Harness();
        Object result = h.eval("""
                log: { calls: 0 }
                behavior: { plus: (other) => {
                    log.calls = log.calls + 1
                    other * 10
                } }
                box: { ...behavior.alias("plus", "+") }
                site: (x, y) => { x + y }
                (site(box, 4) == 40) && (log.calls == 1)
                """);
        assertEquals(Boolean.TRUE, result);
    }

    /* 2. A suspending redefined operator in a terminal sum. */
    @Test
    void suspendingOperatorInTerminalSum() throws Exception {
        Harness h = new Harness();
        ProtosFutureValue result = h.task(BOX + """
                terminal: (b, n) => { b + n }
                (() => terminal(box, 1)).future()
                """);
        h.idle();
        assertEquals(ProtosFutureValue.State.PENDING, result.state());
        h.open(h.gate, 5L);
        assertResolved(result, "6");
        h.assertLog(1, 0);
    }

    /*
     * 2b. The same terminal site runs a standard sum before and after the
     * suspending redefined operator: the standard sums never invoke a guest
     * method, the redefined one suspends and resumes with its result, and the
     * later standard sum is not classified as the redefined selection.
     */
    @Test
    void standardSumsAroundSuspendingOperatorInTerminalSum() throws Exception {
        Harness h = new Harness();
        ProtosFutureValue result = h.task(BOX + """
                terminal: (b, n) => { b + n }
                (() => {
                    before: terminal(2, 3)
                    callsBefore: log.calls
                    r: terminal(box, 1)
                    callsResumed: log.calls
                    after: terminal(4, 5)
                    callsAfter: log.calls
                    last: terminal(box, 2)
                    callsLast: log.calls
                    later: terminal(6, 7)
                    again: terminal(box, 3)
                    callsAgain: log.calls
                    big: terminal(9223372036854775807, 1)
                    callsBig: log.calls
                    (before == 5) && (callsBefore == 0) &&
                        (r == 6) && (callsResumed == 1) &&
                        (after == 9) && (callsAfter == 1) &&
                        (last == 7) && (callsLast == 2) &&
                        (later == 13) &&
                        (again == 8) && (callsAgain == 3) &&
                        BigInteger.recognizes(big) && ((big - 1) == 9223372036854775807) &&
                        (callsBig == 3)
                }).future()
                """);
        h.idle();
        assertEquals(ProtosFutureValue.State.PENDING, result.state());
        // Only the redefined selection ran, and it is suspended on the gate.
        h.assertLog(1, 0);
        h.open(h.gate, 5L);
        assertEquals(ProtosFutureValue.State.RESOLVED, result.state());
        assertEquals(Boolean.TRUE, result.resolvedValue().orElseThrow());
        // Three redefined sends, none repeated by the resumption; standard sums send nothing.
        h.assertLog(3, 0);
    }

    /* 3. A suspending redefined operator in a non-terminal sum. */
    @Test
    void suspendingOperatorInNonTerminalSum() throws Exception {
        Harness h = new Harness();
        ProtosFutureValue result = h.task(BOX + """
                nonTerminal: (b, n) => {
                    r: b + n
                    log.after = log.after + 1
                    r * 2
                }
                (() => nonTerminal(box, 1)).future()
                """);
        h.idle();
        assertEquals(ProtosFutureValue.State.PENDING, result.state());
        h.assertLog(1, 0);
        h.open(h.gate, 5L);
        assertResolved(result, "12");
        h.assertLog(1, 1);
    }

    /* 4. A nested sum whose inner operation suspends. */
    @Test
    void nestedSumWithSuspendingInnerOperation() throws Exception {
        Harness h = new Harness();
        ProtosFutureValue result = h.task(BOX + """
                nested: (b, n, m) => { (b + n) + m }
                asReceiver: (b) => { (b + 1) == 6 }
                (() => {
                    r: nested(box, 1, 2)
                    q: asReceiver(box)
                    (r == 8) && q
                }).future()
                """);
        h.idle();
        h.open(h.gate, 5L);
        // (5 + 1) + 2 = 8; as the receiver of another send: 5 + 1 = 6.
        assertEquals(ProtosFutureValue.State.RESOLVED, result.state());
        assertEquals(Boolean.TRUE, result.resolvedValue().orElseThrow());
        h.assertLog(2, 0);
    }

    /* 5. A resumption that suspends again. */
    @Test
    void resumptionThatSuspendsAgain() throws Exception {
        Harness h = new Harness();
        ProtosFutureValue second = h.gate("second");
        ProtosFutureValue result = h.task("""
                log: { calls: 0 }
                behavior: { plus: (other) => {
                    log.calls = log.calls + 1
                    gate.value() + second.value() + other
                } }
                box: { ...behavior.alias("plus", "+") }
                site: (b, n) => {
                    r: b + n
                    r + 1
                }
                (() => site(box, 1)).future()
                """);
        h.idle();
        h.open(h.gate, 5L);
        assertEquals(ProtosFutureValue.State.PENDING, result.state(),
                "the resumed method suspends again");
        h.open(second, 10L);
        assertResolved(result, "17");
        assertEquals("1", h.integer("log", "calls"));
    }

    /* 6. A guest Error after the resumption. */
    @Test
    void guestErrorAfterResumption() throws Exception {
        Harness h = new Harness();
        ProtosFutureValue result = h.task("""
                log: { calls: 0 }
                behavior: { plus: (other) => {
                    log.calls = log.calls + 1
                    gate.value()
                    Error().signal()
                } }
                box: { ...behavior.alias("plus", "+") }
                site: (b, n) => {
                    r: b + n
                    r + 1
                }
                (() => site(box, 1)).future()
                """);
        h.idle();
        h.open(h.gate, 5L);
        assertEquals(ProtosFutureValue.State.FAILED, result.state());
        assertEquals("1", h.integer("log", "calls"));
    }

    /* 7. Cancellation and abandonment never repeat the invocation. */
    @Test
    void cancellationAndAbandonmentNeverRepeatTheInvocation() throws Exception {
        Harness cancelled = new Harness();
        ProtosFutureValue result = cancelled.task(BOX + """
                site: (b, n) => {
                    r: b + n
                    log.after = log.after + 1
                    r
                }
                (() => site(box, 1)).future()
                """);
        cancelled.idle();
        assertTrue(result.cancelRequest());
        cancelled.idle();
        cancelled.open(cancelled.gate, 5L);
        assertEquals(ProtosFutureValue.State.CANCELLED, result.state());
        assertEquals("1", cancelled.integer("log", "calls"));

        Harness abandoned = new Harness();
        ProtosFutureValue pending = abandoned.task(BOX + """
                site: (b, n) => {
                    r: b + n
                    log.after = log.after + 1
                    r
                }
                (() => site(box, 1)).future()
                """);
        abandoned.idle();
        abandoned.idle();
        assertEquals(ProtosFutureValue.State.PENDING, pending.state());
        abandoned.assertLog(1, 0);
    }

    /* 8. Receiver and argument are evaluated exactly once. */
    @Test
    void operandsEvaluatedExactlyOnce() throws Exception {
        Harness h = new Harness();
        ProtosFutureValue result = h.task(BOX + """
                site: () => {
                    r: step(1, box) + step(2, 10)
                    log.trace * 100 + r
                }
                (() => site()).future()
                """);
        h.idle();
        h.open(h.gate, 5L);
        assertResolved(result, "1215");
        h.assertLog(1, 0);
    }

    /* 9. A selection change between invocations of one site. */
    @Test
    void selectionChangeBetweenInvocations() throws Exception {
        Harness h = new Harness();
        Object result = h.eval("""
                first: { plus: (other) => { other + 100 } }
                second: { plus: (other) => { other + 200 } }
                box: { ...first.alias("plus", "+") }
                site: (x, y) => { x + y }
                a: site(box, 1)
                box = { ...second.alias("plus", "+") }
                b: site(box, 1)
                (a == 101) && (b == 201) && (site(1, 2) == 3)
                """);
        assertEquals(Boolean.TRUE, result);
    }

    /* 10. Two independent Cores, each with its own selected behavior. */
    @Test
    void twoCoresWithDistinctSelections() throws Exception {
        Harness first = new Harness();
        Harness second = new Harness();
        String source = """
                behavior: { plus: (other) => { other + OFFSET } }
                box: { ...behavior.alias("plus", "+") }
                site: (x, y) => { x + y }
                site(box, 1) * 1000 + site(1, 2)
                """;
        Object a = first.eval(source.replace("OFFSET", "10"));
        Object b = second.eval(source.replace("OFFSET", "20"));
        assertEquals("11003", ProtosNumericValueSupport.integerDecimalText(a));
        assertEquals("21003", ProtosNumericValueSupport.integerDecimalText(b));
    }

    /* 11. The standard operator after a redefined one at the same site. */
    @Test
    void standardOperatorAfterRedefinedAtTheSameSite() throws Exception {
        Harness h = new Harness();
        ProtosFutureValue result = h.task(BOX + """
                site: (x, y) => {
                    r: x + y
                    r
                }
                (() => site(box, 1) * 1000 + site(40, 2)).future()
                """);
        h.idle();
        h.open(h.gate, 5L);
        assertResolved(result, "6042");
    }

    /* 12. Overflow with the standard and with a redefined selection. */
    @Test
    void overflowWithStandardAndRedefinedSelection() throws Exception {
        Harness h = new Harness();
        Object result = h.eval("""
                behavior: { plus: (other) => { 7 } }
                box: { ...behavior.alias("plus", "+") }
                site: (x, y) => { x + y }
                """
                + "BigInteger.recognizes(site(9223372036854775807, 1))"
                + " && (site(box, 9223372036854775807) == 7)"
                + " && (site(9223372036854775807, 0) == 9223372036854775807)\n");
        assertEquals(Boolean.TRUE, result);
    }

    private static void assertResolved(ProtosFutureValue future, String decimal) {
        assertEquals(ProtosFutureValue.State.RESOLVED, future.state());
        assertEquals(decimal, ProtosNumericValueSupport.integerDecimalText(
                future.resolvedValue().orElseThrow()));
    }

    /* One Core, one Actor execution domain and a host-controlled gate. */
    private static final class Harness {
        final ProtosActivation activation;
        final ProtosActorExecutionDomain domain;
        final ProtosPrelude prelude;
        final ProtosFutureValue gate;

        Harness() throws Exception {
            prelude = new ProtosCoreBootstrap().bootstrap(CORE);
            domain = new ProtosActorExecutionDomain();
            activation = prelude.newModuleActivation(
                    new ProtosActorModuleState(), null, prelude.newExecutionContext(), domain);
            gate = gate("gate");
        }

        ProtosFutureValue gate(String name) {
            ProtosFutureValue future = new ProtosFutureValue(prelude.futurePrototype(), domain);
            activation.context().createLocalSlot(name, future);
            return future;
        }

        Object eval(String source) {
            return ProtosTestExecutionSupport.evaluate(source, activation);
        }

        ProtosFutureValue task(String source) {
            return assertInstanceOf(ProtosFutureValue.class, eval(source));
        }

        void idle() {
            ProtosTestExecutionSupport.dispatchUntilIdle(domain);
        }

        void open(ProtosFutureValue future, long value) {
            future.resolve(ProtosNumericValueSupport.guestValue(Long.valueOf(value)), activation);
            idle();
        }

        String integer(String object, String slot) {
            Object owner = activation.context().readLocalSlot(object).orElseThrow();
            return ProtosNumericValueSupport.integerDecimalText(
                    ((com.guillermomolina.protos.runtime.ProtosObjectValue) owner)
                            .readLocalSlot(slot).orElseThrow());
        }

        void assertLog(long calls, long after) {
            assertEquals(Long.toString(calls), integer("log", "calls"), "selected method calls");
            assertEquals(Long.toString(after), integer("log", "after"), "statements after the sum");
        }
    }
}
