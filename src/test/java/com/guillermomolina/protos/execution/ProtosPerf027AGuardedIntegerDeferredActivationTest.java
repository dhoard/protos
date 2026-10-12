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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.guillermomolina.protos.execution.ProtosBytecodeRootNode.PrepareSendArguments;
import com.guillermomolina.protos.execution.ProtosBytecodeRootNode.PreparedClosureCall;
import com.guillermomolina.protos.runtime.ProtosActivation;
import com.guillermomolina.protos.runtime.ProtosArrayValue;
import com.guillermomolina.protos.runtime.ProtosBooleanValue;
import com.guillermomolina.protos.runtime.ProtosClosureValue;
import com.guillermomolina.protos.runtime.ProtosExecutionContextValue;
import com.guillermomolina.protos.runtime.ProtosIntegerValue;
import com.guillermomolina.protos.runtime.ProtosNumericValueSupport;
import com.guillermomolina.protos.runtime.ProtosPrelude;
import com.guillermomolina.protos.runtime.ProtosSignalException;
import com.guillermomolina.protos.runtime.ProtosValueLookup;
import java.lang.reflect.Field;
import java.math.BigInteger;
import java.nio.file.Path;
import java.util.List;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;

/**
 * PERF027-A established the deferred native path after exact PERF016 guarded
 * Integer selection. The PERF025 pay-as-you-grow extension covered here executes
 * successful exact canonical local Integer arithmetic through an immediate
 * result shape with no rich method Activation. Error-producing, wrong-domain,
 * inherited and otherwise non-canonical cases retain the PERF027-A deferred
 * selected-Closure path. Guest-level Integer semantics remain covered by
 * protos/tests/conformance/integer.
 */
final class ProtosPerf027AGuardedIntegerDeferredActivationTest {
    private static final BigInteger HUGE = BigInteger.TWO.pow(200).add(BigInteger.ONE);
    private static final BigInteger LONG_MAX = BigInteger.valueOf(Long.MAX_VALUE);

    @Test
    void successfulCanonicalArithmeticExecutesWithoutGeneralNativeInvocationState()
            throws Exception {
        inEnteredContext(prelude -> {
            ProtosIntegerValue receiver = new ProtosIntegerValue(Long.MAX_VALUE);
            PreparedClosureCall prepared = guardedSend(prelude, receiver, "+", integer(1));

            PrepareSendArguments.GuardedIntegerSend cached =
                    PrepareSendArguments.createGuardedIntegerSend(
                            receiver,
                            "+",
                            prelude);
            assertNotNull(cached);
            assertSame(
                    ProtosStandardIntegerProtocol.CanonicalIntegerOperation.ADD,
                    cached.operation());

            ProtosClosureValue selected =
                    (ProtosClosureValue)
                            ProtosValueLookup.lookup(receiver, "+", prelude)
                                    .orElseThrow()
                                    .value();
            assertTrue(selected.nativeBody().isPresent());

            assertTrue(prepared.isImmediate());
            assertTrue(!prepared.isNative());
            assertThrows(
                    IllegalStateException.class,
                    prepared::activation,
                    "successful canonical Integer hit must not own a rich Activation");

            Object entered = prepared.enterImmediate();
            Object result = prepared.finish(entered);
            assertEquals(String.valueOf(LONG_MAX.add(BigInteger.ONE)), integerValue(result));
        });
    }

    @Test
    void representativeOperationsRetainExactResults() throws Exception {
        inEnteredContext(prelude -> {
            assertEquals(String.valueOf(HUGE.subtract(BigInteger.ONE)), integerValue(run(prelude, com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerFromHost(HUGE, prelude), "-", integer(1))));
            assertEquals(String.valueOf(HUGE.multiply(HUGE)), integerValue(run(prelude, com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerFromHost(HUGE, prelude), "*",
                            com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerFromHost(HUGE, prelude))));
            assertEquals(String.valueOf(HUGE.divide(BigInteger.valueOf(7))), integerValue(run(prelude, com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerFromHost(HUGE, prelude), "div", integer(7))));
            assertEquals(String.valueOf(HUGE.mod(BigInteger.valueOf(7))), integerValue(run(prelude, com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerFromHost(HUGE, prelude), "mod", integer(7))));
            assertSame(ProtosBooleanValue.TRUE,
                    run(prelude, com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerFromHost(HUGE, prelude), ">", integer(0)));
            assertSame(ProtosBooleanValue.FALSE, run(prelude, integer(0), ">", integer(0)));
        });
    }

    /**
     * D197-C: one cached guard serves both concrete Integer families, because
     * the inherited canonical operation has the same Closure and Integer home
     * whether lookup starts at SmallInteger or at BigInteger.
     */
    @Test
    void oneGuardServesBothConcreteIntegerFamilies() throws Exception {
        inEnteredContext(prelude -> {
            Object big = com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerFromHost(HUGE, prelude);
            PrepareSendArguments.GuardedIntegerSend fromSmall =
                    PrepareSendArguments.createGuardedIntegerSend(integer(7), "+", prelude);
            PrepareSendArguments.GuardedIntegerSend fromBig =
                    PrepareSendArguments.createGuardedIntegerSend(big, "+", prelude);
            assertNotNull(fromSmall);
            assertNotNull(fromBig);
            assertSame(prelude.integerPrototype(), fromSmall.methodHome());
            assertSame(fromSmall.closure(), fromBig.closure());
            assertSame(fromSmall.methodHome(), fromBig.methodHome());
            assertSame(
                    ProtosStandardIntegerProtocol.CanonicalIntegerOperation.ADD,
                    fromBig.operation());

            ProtosLanguageContext entered = ProtosLanguageContext.currentIfEnteredForRuntime();
            ProtosActivation caller =
                    ProtosActivation.forClosureInvocation(fromSmall.closure(), List.of(), prelude);
            PreparedClosureCall prepared =
                    PrepareSendArguments.guardedIntegerSend(
                            big, "+", caller, new Object[] {integer(1)},
                            entered, prelude, "+", entered, prelude, fromSmall);
            assertTrue(prepared.isImmediate());
            Object result = prepared.finish(prepared.enterImmediate());
            assertEquals(String.valueOf(HUGE.add(BigInteger.ONE)), integerValue(result));
            assertSame(
                    prelude.bigIntegerPrototype(),
                    ((com.guillermomolina.protos.runtime.ProtosObjectValue) result)
                            .parent().orElseThrow());

            // A family-local slot is never shared by a guard.
            assertNull(PrepareSendArguments.createGuardedIntegerSend(
                    integer(7), "recognizes", prelude));
        });
    }

    @Test
    void exactQuotientStaysImmediateAndFractionArgumentDefersToSelectedMethod()
            throws Exception {
        inEnteredContext(prelude -> {
            // D197-C: an integral quotient is an immediate Integer, never a Float.
            PreparedClosureCall integral = guardedSend(prelude, integer(8), "/", integer(2));
            assertTrue(integral.isImmediate());
            assertEquals(4L, assertInstanceOf(
                    ProtosIntegerValue.class,
                    integral.finish(integral.enterImmediate())).longValue());

            PreparedClosureCall quotient = guardedSend(prelude, integer(7), "/", integer(2));
            assertTrue(quotient.isImmediate());
            Object half = quotient.finish(quotient.enterImmediate());
            assertTrue(ProtosNumericValueSupport.isFraction(half));

            // A Fraction operand is outside the immediate domain: the selected native
            // Integer method stays authoritative and computes the exact rational result.
            PreparedClosureCall mixed = guardedSend(prelude, integer(1), "+", half);
            assertTrue(!mixed.isImmediate());
        });
    }

    @Test
    void allCanonicalLocalIntegerOperationsCarryDirectCapability()
            throws Exception {
        inEnteredContext(prelude -> {
            for (String selector : new String[] {"+", "-", "*", "/", "div", "mod"}) {
                PrepareSendArguments.GuardedIntegerSend cached =
                        PrepareSendArguments.createGuardedIntegerSend(
                                integer(7),
                                selector,
                                prelude);
                assertNotNull(cached);
                assertNotNull(
                        cached.operation(),
                        selector + " must carry exact canonical operation capability");

                PreparedClosureCall prepared =
                        guardedSend(
                                prelude,
                                integer(7),
                                selector,
                                integer(2));
                assertTrue(
                        prepared.isImmediate(),
                        selector + " successful canonical hit must be immediate");
                assertTrue(!prepared.isNative());
            }

            PrepareSendArguments.GuardedIntegerSend ordering =
                    PrepareSendArguments.createGuardedIntegerSend(
                            integer(7),
                            ">",
                            prelude);
            assertNotNull(ordering);
            assertNull(
                    ordering.operation(),
                    "inherited Number ordering remains outside this bounded slice");

            PreparedClosureCall preparedOrdering =
                    guardedSend(
                            prelude,
                            integer(7),
                            ">",
                            integer(2));
            assertTrue(preparedOrdering.isNative());
            assertTrue(!preparedOrdering.isImmediate());
        });
    }

    @Test
    void zeroDivisionSignalsTheSelectedActivationsError() throws Exception {
        inEnteredContext(prelude -> {
            for (String selector : new String[] {"div", "mod"}) {
                PreparedClosureCall prepared =
                        guardedSend(prelude, integer(1), selector, integer(0));
                assertTrue(prepared.isNative());
                assertTrue(!prepared.isImmediate());
                ProtosSignalException signal =
                        assertThrows(ProtosSignalException.class, prepared::enterNative);
                assertSame(prelude.errorPrototype(), signal.error().parent().orElseThrow());
                // The deferred state stays observable after the Error path.
                assertNotNull(prepared.activation().context());
                assertEquals(1, prepared.activation().arguments().orElseThrow()
                        .indexedSnapshot().size());
            }
        });
    }

    @Test
    void wrongDomainArgumentSignalsError() throws Exception {
        inEnteredContext(prelude -> {
            PreparedClosureCall prepared =
                    guardedSend(prelude, integer(1), "+", ProtosBooleanValue.TRUE);
            assertTrue(prepared.isNative());
            assertTrue(!prepared.isImmediate());
            ProtosSignalException signal =
                    assertThrows(ProtosSignalException.class, prepared::enterNative);
            assertSame(prelude.errorPrototype(), signal.error().parent().orElseThrow());
        });
    }

    @Test
    void separateContextsKeepIndependentDeferredFallbackActivations() throws Exception {
        ProtosActivation[] activations = new ProtosActivation[2];
        for (int index = 0; index < activations.length; index++) {
            int slot = index;
            inEnteredContext(prelude -> {
                PreparedClosureCall prepared =
                        guardedSend(prelude, integer(5), ">", integer(1));
                assertTrue(prepared.isNative());
                assertSame(ProtosBooleanValue.TRUE, prepared.enterNative());
                assertSame(prelude, prepared.activation().prelude().orElseThrow());
                activations[slot] = prepared.activation();
            });
        }
        assertNotSame(
                activations[0].prelude().orElseThrow(),
                activations[1].prelude().orElseThrow());
    }

    private interface EnteredBody {
        void run(ProtosPrelude prelude) throws Exception;
    }

    private static void inEnteredContext(EnteredBody body) throws Exception {
        try (Context context = Context.newBuilder(ProtosLanguage.ID).build()) {
            context.initialize(ProtosLanguage.ID);
            context.enter();
            try {
                body.run(new ProtosCoreBootstrap().bootstrap(Path.of("protos", "lib", "core")));
            } finally {
                context.leave();
            }
        }
    }

    private static Object run(
            ProtosPrelude prelude, Object receiver, String selector, Object argument) {
        PreparedClosureCall prepared = guardedSend(prelude, receiver, selector, argument);
        Object result =
                prepared.isImmediate()
                        ? prepared.enterImmediate()
                        : prepared.enterNative();
        return prepared.finish(result);
    }

    private static PreparedClosureCall guardedSend(
            ProtosPrelude prelude, Object receiver, String selector, Object argument) {
        ProtosLanguageContext entered = ProtosLanguageContext.currentIfEnteredForRuntime();
        assertNotNull(entered);
        PrepareSendArguments.GuardedIntegerSend cached =
                PrepareSendArguments.createGuardedIntegerSend(receiver, selector, prelude);
        assertNotNull(cached, "standard native Integer selection must stay guarded");
        var generic = ProtosValueLookup.lookup(receiver, selector, prelude).orElseThrow();
        assertSame(generic.value(), cached.closure());
        assertSame(generic.home(), cached.methodHome());

        ProtosActivation caller =
                ProtosActivation.forClosureInvocation(cached.closure(), List.of(), prelude);
        return PrepareSendArguments.guardedIntegerSend(
                receiver, selector, caller, new Object[] {argument},
                entered, prelude, selector, entered, prelude, cached);
    }

    private static void assertDeferred(ProtosActivation activation) throws Exception {
        assertNull(privateField(activation, "context"),
                "guarded Integer success must not materialize the guest Context");
        assertNull(privateField(activation, "arguments"));
        Object deferred = privateField(activation, "deferredSuppliedArguments");
        assertNotNull(deferred, "guarded Integer send must use the PLAT040 deferred activation");
        assertNull(privateField(deferred, "guestArray"),
                "guarded Integer success must not materialize the guest argument Array");
    }

    private static ProtosIntegerValue integer(long value) {
        return new ProtosIntegerValue(value);
    }

    private static String integerValue(Object value) {
        return com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerDecimalText(value);
    }

    private static Object privateField(Object target, String name)
            throws ReflectiveOperationException {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }
}
