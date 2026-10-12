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
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.guillermomolina.protos.runtime.ProtosActivation;
import com.guillermomolina.protos.runtime.ProtosArrayValue;
import com.guillermomolina.protos.runtime.ProtosFloatValue;
import com.guillermomolina.protos.runtime.ProtosIntegerValue;
import com.guillermomolina.protos.runtime.ProtosNumericValueSupport;
import com.guillermomolina.protos.runtime.ProtosModuleKey;
import com.guillermomolina.protos.runtime.ProtosObjectValue;
import com.guillermomolina.protos.runtime.ProtosPrelude;
import com.guillermomolina.protos.runtime.ProtosSignalException;
import com.guillermomolina.protos.runtime.ProtosStringValue;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.nio.file.Path;
import java.util.List;
import java.util.SplittableRandom;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** LIB015-B1 and LIB015-C1 evidence for the private runtime facilities of std:logging. */
final class ProtosLoggingFacilityTest {
    private static final Path CORE = Path.of("protos", "lib", "core");
    private static final Path STANDARD_LIBRARY = Path.of("protos", "lib");

    @Test
    void facilitiesAreFrozenStatelessMembersOfTheirExactModulesOnly() throws Exception {
        ProtosPrelude prelude = core();
        ProtosObjectValue events = eventFacility(prelude);
        ProtosObjectValue text = textFacility(prelude);
        ProtosObjectValue json = jsonFacility(prelude);

        assertTrue(events.isFrozen());
        assertTrue(text.isFrozen());
        assertTrue(json.isFrozen());
        assertNotSame(events, text);
        assertNotSame(text, json);
        assertEquals(Set.of("recognizes", "isAttachableError"), events.localSlotsSnapshot().keySet());
        assertEquals(Set.of("shortestDecimal"), text.localSlotsSnapshot().keySet());
        assertEquals(Set.of("shortestDecimal"), json.localSlotsSnapshot().keySet());
        assertTrue(prelude.isStandardModuleMemberForRuntime(events));
        assertTrue(prelude.isStandardModuleMemberForRuntime(text));
        assertTrue(prelude.isStandardModuleMemberForRuntime(json));

        ProtosObjectValue otherContext = prelude.newExecutionContext();
        prelude.installStandardModuleMembersForRuntime(
                new ProtosModuleKey("std:logging/Logger"), otherContext);
        assertFalse(otherContext.hasLocalSlot(ProtosLoggingFacility.EVENT_BOOTSTRAP_SLOT));
        assertFalse(otherContext.hasLocalSlot(ProtosLoggingFacility.TEXT_BOOTSTRAP_SLOT));
        assertFalse(otherContext.hasLocalSlot(ProtosLoggingFacility.JSON_BOOTSTRAP_SLOT));
    }

    @Test
    void jsonFormatterFacilityAnswersTheSameShortestDecimal() throws Exception {
        ProtosPrelude prelude = core();
        ProtosActivation activation = prelude.newModuleActivation();
        ProtosObjectValue json = jsonFacility(prelude);

        assertDecimal(json, activation, 1.0, "1", 0);
        assertDecimal(json, activation, 1.23, "123", -2);
        assertDecimal(json, activation, -1.5, "15", -1);
        assertDecimal(json, activation, Double.MIN_VALUE, "5", -324);
        assertThrows(
                ProtosSignalException.class,
                () -> ProtosInvocation.invokeMessage(
                        json,
                        "shortestDecimal",
                        List.of(new ProtosFloatValue(Double.NaN)),
                        activation));
    }

    @Test
    void shortestDecimalAnswersCoefficientAndExponentWithoutTrailingZeros() throws Exception {
        ProtosPrelude prelude = core();
        ProtosActivation activation = prelude.newModuleActivation();
        ProtosObjectValue text = textFacility(prelude);

        assertDecimal(text, activation, 1.0, "1", 0);
        assertDecimal(text, activation, -1.5, "15", -1);
        assertDecimal(text, activation, 100.0, "1", 2);
        assertDecimal(text, activation, 0.1, "1", -1);
        assertDecimal(text, activation, 0.1 + 0.2, "30000000000000004", -17);
        assertDecimal(text, activation, 1e21, "1", 21);
        assertDecimal(text, activation, Double.MIN_VALUE, "5", -324);
        assertDecimal(text, activation, Double.MAX_VALUE, "17976931348623157", 292);
        assertDecimal(text, activation, Double.MIN_NORMAL, "22250738585072014", -324);
    }

    @Test
    void shortestDecimalRejectsZeroNonFiniteAndNonFloatArguments() throws Exception {
        ProtosPrelude prelude = core();
        ProtosActivation activation = prelude.newModuleActivation();
        ProtosObjectValue text = textFacility(prelude);

        for (Object invalid :
                List.of(
                        new ProtosFloatValue(0.0),
                        new ProtosFloatValue(-0.0),
                        new ProtosFloatValue(Double.NaN),
                        new ProtosFloatValue(Double.POSITIVE_INFINITY),
                        new ProtosFloatValue(Double.NEGATIVE_INFINITY),
                        new ProtosIntegerValue(1),
                        new ProtosStringValue("1.0"))) {
            assertThrows(
                    ProtosSignalException.class,
                    () -> ProtosInvocation.invokeMessage(
                            text, "shortestDecimal", List.of(invalid), activation));
        }
    }

    @Test
    void shortestDecimalRoundTripsWithMinimalDigitsAndNearestChoice() {
        SplittableRandom random = new SplittableRandom(0x1B015L);
        for (int index = 0; index < 20_000; index++) {
            double value = Double.longBitsToDouble(random.nextLong() & Long.MAX_VALUE);
            if (!Double.isFinite(value) || value == 0.0) {
                continue;
            }
            assertShortest(value);
        }
        for (double value :
                new double[] {
                    Double.MIN_VALUE, Double.MIN_NORMAL, Double.MAX_VALUE, 1.0, 2.0, 0.5,
                    9007199254740993.0, 2e23, 1e-7, 1e21, 1e22, 123456.789, 5e-324, 4.9e-324
                }) {
            assertShortest(value);
        }
    }

    private static void assertShortest(double value) {
        BigDecimal decimal = ProtosLoggingFacility.shortestDecimal(value);
        assertEquals(value, decimal.doubleValue(), () -> "round trip of " + value);
        int digits = decimal.stripTrailingZeros().precision();
        if (digits > 1) {
            BigDecimal exact = new BigDecimal(value);
            MathContext shorter = new MathContext(digits - 1, RoundingMode.FLOOR);
            MathContext shorterUp = new MathContext(digits - 1, RoundingMode.CEILING);
            assertFalse(exact.round(shorter).doubleValue() == value, () -> "shorter below " + value);
            assertFalse(exact.round(shorterUp).doubleValue() == value, () -> "shorter above " + value);
        }
        BigDecimal toStringDigits = new BigDecimal(Double.toString(value)).stripTrailingZeros();
        assertTrue(digits <= toStringDigits.precision(), () -> "not shorter than JDK for " + value);
    }

    private static void assertDecimal(
            ProtosObjectValue text,
            ProtosActivation activation,
            double value,
            String coefficient,
            int exponent)
            throws Exception {
        ProtosArrayValue result =
                assertInstanceOf(
                        ProtosArrayValue.class,
                        ProtosInvocation.invokeMessage(
                                text,
                                "shortestDecimal",
                                List.of(new ProtosFloatValue(value)),
                                activation));
        assertTrue(result.isFrozen());
        assertEquals(2, result.indexedSize());
        assertEquals(String.valueOf(coefficient), com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerDecimalText(result.indexedAt(0)));
        assertEquals(
                exponent,
                ProtosNumericValueSupport.exactInt(
                        assertInstanceOf(ProtosIntegerValue.class, result.indexedAt(1))));
    }

    private static ProtosObjectValue eventFacility(ProtosPrelude prelude) {
        return ProtosStandardModuleMemberTestSupport.member(
                prelude,
                ProtosLoggingFacility.EVENT_MODULE_KEY.canonicalId(),
                ProtosLoggingFacility.EVENT_BOOTSTRAP_SLOT);
    }

    private static ProtosObjectValue textFacility(ProtosPrelude prelude) {
        return ProtosStandardModuleMemberTestSupport.member(
                prelude,
                ProtosLoggingFacility.TEXT_MODULE_KEY.canonicalId(),
                ProtosLoggingFacility.TEXT_BOOTSTRAP_SLOT);
    }

    private static ProtosObjectValue jsonFacility(ProtosPrelude prelude) {
        return ProtosStandardModuleMemberTestSupport.member(
                prelude,
                ProtosLoggingFacility.JSON_MODULE_KEY.canonicalId(),
                ProtosLoggingFacility.JSON_BOOTSTRAP_SLOT);
    }

    private static ProtosPrelude core() throws Exception {
        return new ProtosCoreBootstrap()
                .bootstrap(CORE, new ProtosStandardLibraryModuleResolver(STANDARD_LIBRARY));
    }
}
