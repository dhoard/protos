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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.guillermomolina.protos.runtime.ProtosActivation;
import com.guillermomolina.protos.runtime.ProtosBooleanValue;
import com.guillermomolina.protos.runtime.ProtosFloatValue;
import com.guillermomolina.protos.runtime.ProtosIntegerValue;
import com.guillermomolina.protos.runtime.ProtosNumericValueSupport;
import com.guillermomolina.protos.runtime.ProtosObjectValue;
import com.guillermomolina.protos.runtime.ProtosPrelude;
import com.guillermomolina.protos.runtime.ProtosSignalException;
import com.guillermomolina.protos.runtime.ProtosStringValue;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class ProtosStandardNumberEqualityTest {
    // Deliberately Java-side: normal Protos operator syntax fixes == to one
    // right operand, so this probes the host/runtime protocol boundary directly.
    @Test
    void wrongAritySignalsError() throws IOException {
        ProtosPrelude prelude = corePrelude();

        assertThrows(
                ProtosSignalException.class,
                () ->
                        ProtosInvocation.invokeMessage(
                                new ProtosIntegerValue(1L),
                                "==",
                                List.of(
                                        new ProtosIntegerValue(1L),
                                        new ProtosIntegerValue(2L)),
                                prelude.newModuleActivation()));
    }

    // I091: Number.== is exact mathematical equality across Integer carriers and Float.
    @Test
    void integerFloatEqualityIsExactAtBinary64AndSigned64Boundaries() throws IOException {
        ProtosPrelude prelude = corePrelude();
        ProtosActivation a = prelude.newModuleActivation();
        BigInteger twoTo53 = BigInteger.ONE.shiftLeft(53);
        BigInteger twoTo1000 = BigInteger.ONE.shiftLeft(1000);

        assertEquality(true, integer(twoTo53, prelude), new ProtosFloatValue(0x1p53), a);
        assertEquality(
                false, integer(twoTo53.add(BigInteger.ONE), prelude), new ProtosFloatValue(0x1p53), a);
        assertEquality(false, new ProtosIntegerValue(Long.MAX_VALUE), new ProtosFloatValue(0x1p63), a);
        assertEquality(true, integer(BigInteger.ONE.shiftLeft(63), prelude), new ProtosFloatValue(0x1p63), a);
        assertEquality(true, new ProtosIntegerValue(Long.MIN_VALUE), new ProtosFloatValue(-0x1p63), a);
        assertEquality(true, integer(twoTo1000, prelude), new ProtosFloatValue(0x1p1000), a);
        assertEquality(true, integer(twoTo1000.negate(), prelude), new ProtosFloatValue(-0x1p1000), a);
        assertEquality(
                false, integer(twoTo1000.add(BigInteger.ONE), prelude), new ProtosFloatValue(0x1p1000), a);
        assertEquality(true, integer(twoTo1000, prelude), integer(twoTo1000, prelude), a);
        assertEquality(false, integer(twoTo1000, prelude), integer(twoTo1000.negate(), prelude), a);
        assertEquality(false, new ProtosIntegerValue(1), integer(twoTo53.shiftLeft(11), prelude), a);
        assertEquality(false, integer(twoTo1000, prelude), new ProtosFloatValue(Double.POSITIVE_INFINITY), a);
    }

    @Test
    void nanIsUnequalAndSignedZerosAreNumericallyEqual() throws IOException {
        ProtosPrelude prelude = corePrelude();
        ProtosActivation a = prelude.newModuleActivation();
        ProtosFloatValue nan = new ProtosFloatValue(Double.NaN);

        assertEquality(false, nan, nan, a);
        assertEquality(false, nan, new ProtosIntegerValue(0), a);
        assertEquality(true, new ProtosFloatValue(0.0d), new ProtosFloatValue(-0.0d), a);
        assertEquality(true, new ProtosIntegerValue(0), new ProtosFloatValue(-0.0d), a);
    }

    @Test
    void equalNumbersHaveEqualNormalHashes() throws IOException {
        ProtosPrelude prelude = corePrelude();
        ProtosActivation a = prelude.newModuleActivation();
        Object[][] equalPairs = {
            {integer(BigInteger.ONE.shiftLeft(53), prelude), new ProtosFloatValue(0x1p53)},
            {integer(BigInteger.ONE.shiftLeft(1000), prelude), new ProtosFloatValue(0x1p1000)},
            {new ProtosIntegerValue(0), new ProtosFloatValue(-0.0d)},
            {integer(BigInteger.ONE.shiftLeft(64), prelude), integer(BigInteger.ONE.shiftLeft(64), prelude)},
        };
        for (Object[] pair : equalPairs) {
            assertEquality(true, pair[0], pair[1], a);
            assertEquals(com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerDecimalText(ProtosInvocation.invokeMessage(pair[0], "hash", List.of(), a)), com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerDecimalText(ProtosInvocation.invokeMessage(pair[1], "hash", List.of(), a)));
        }
    }

    @Test
    void nonNumberArgumentsAnswerFalseAndNonNumberReceiversSignal() throws IOException {
        ProtosPrelude prelude = corePrelude();
        ProtosActivation a = prelude.newModuleActivation();
        Object large = integer(BigInteger.ONE.shiftLeft(64), prelude);

        assertEquality(false, large, new ProtosStringValue("18446744073709551616"), a);
        assertEquality(false, new ProtosIntegerValue(1), new ProtosObjectValue(ProtosObjectValue.rootObject()), a);
        assertEquality(false, large, BigInteger.ONE.shiftLeft(64), a);

        ProtosObjectValue impostor = new ProtosObjectValue(prelude.numberPrototype());
        assertThrows(
                ProtosSignalException.class,
                () -> ProtosInvocation.invokeMessage(impostor, "==", List.of(large), a));
        assertThrows(
                ProtosSignalException.class,
                () -> ProtosInvocation.invokeMessage(large, "==", List.of(), a));
    }

    private static Object integer(BigInteger value, ProtosPrelude prelude) {
        return com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerFromHost(value, prelude);
    }

    private static void assertEquality(
            boolean expected, Object left, Object right, ProtosActivation a) {
        Object expectedValue = expected ? ProtosBooleanValue.TRUE : ProtosBooleanValue.FALSE;
        assertSame(expectedValue, ProtosInvocation.invokeMessage(left, "==", List.of(right), a));
        if (ProtosNumericValueSupport.isCurrentNumber(right)) {
            assertSame(expectedValue, ProtosInvocation.invokeMessage(right, "==", List.of(left), a));
        }
    }

    private static ProtosPrelude corePrelude() throws IOException {
        return new ProtosCoreBootstrap().bootstrap(Path.of("protos", "lib", "core"));
    }
}
