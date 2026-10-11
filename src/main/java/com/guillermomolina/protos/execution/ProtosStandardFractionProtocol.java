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

import com.guillermomolina.protos.runtime.ProtosActivation;
import com.guillermomolina.protos.runtime.ProtosBooleanValue;
import com.guillermomolina.protos.runtime.ProtosClosureValue;
import com.guillermomolina.protos.runtime.ProtosCoreErrors;
import com.guillermomolina.protos.runtime.ProtosFloatValue;
import com.guillermomolina.protos.runtime.ProtosNumericValueSupport;
import com.guillermomolina.protos.runtime.ProtosObjectValue;
import com.guillermomolina.protos.runtime.ProtosPrelude;
import com.guillermomolina.protos.runtime.ProtosSignalException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Core bootstrap bridge for the source-backed Fraction prototype (D197-C, I090).
 *
 * <p>Rational arithmetic and negation are source closures of {@code lib/core/Fraction.protos};
 * this bridge only moves the operator closures to their operator slots, installs the exact
 * trusted {@code recognizes}, and fills the private {@code _coreFractionKernel} with four
 * physical primitives. None of them dispatches guest behavior or exposes a host number:
 *
 * <ul>
 *   <li>{@code numerator(value)} and {@code denominator(value)} answer the canonical components
 *       of an exact real value: a Fraction's own, or an Integer and {@code 1};
 *   <li>{@code normalize(numerator, denominator)} answers the canonical exact quotient of two
 *       Integers, signaling the standard Error for a zero denominator;
 *   <li>{@code toFloat(fraction)} answers the Float nearest to a Fraction's exact quotient.
 * </ul>
 */
public final class ProtosStandardFractionProtocol {
    /* Source selector moved to its operator slot; a slot target cannot spell an operator. */
    private static final Map<String, String> SOURCE_OPERATORS =
            Map.of(
                    "_coreFractionPlus", "+",
                    "_coreFractionMinus", "-",
                    "_coreFractionTimes", "*",
                    "_coreFractionDivide", "/");
    private static final Set<String> SOURCE_SELECTORS =
            Set.of(
                    "negated",
                    "_coreFractionPlus",
                    "_coreFractionMinus",
                    "_coreFractionTimes",
                    "_coreFractionDivide");

    private ProtosStandardFractionProtocol() {}

    public static void install(
            ProtosObjectValue prototype,
            ProtosObjectValue numberPrototype,
            ProtosObjectValue kernel) {
        Objects.requireNonNull(prototype, "prototype");
        Objects.requireNonNull(numberPrototype, "numberPrototype");
        Objects.requireNonNull(kernel, "kernel");
        if (prototype.parent().orElse(null) != numberPrototype) {
            throw new IllegalStateException(
                    "standard Fraction prototype must delegate directly to Number");
        }
        if (!prototype.isOpen()
                || !prototype.localSlotsSnapshot().keySet().equals(SOURCE_SELECTORS)) {
            throw new IllegalStateException(
                    "standard Fraction prototype must be an open source object with exactly "
                            + SOURCE_SELECTORS);
        }
        for (String selector : SOURCE_SELECTORS) {
            Object value = prototype.readLocalSlot(selector).orElseThrow();
            if (!(value instanceof ProtosClosureValue closure)
                    || closure.definition() == null
                    || closure.executionPlan().isEmpty()
                    || closure.nativeBody().isPresent()) {
                throw new IllegalStateException(
                        "standard Fraction " + selector + " must be a source-backed Closure");
            }
        }
        for (Map.Entry<String, String> alias : SOURCE_OPERATORS.entrySet()) {
            Object closure = prototype.readLocalSlot(alias.getKey()).orElseThrow();
            prototype.removeLocalSlot(alias.getKey());
            prototype.createLocalSlot(alias.getValue(), closure);
        }
        prototype.createLocalSlot(
                "recognizes",
                ProtosClosureValue.nativeClosure(
                        (activation, supplied) -> {
                            if (activation.receiver() != prototype || supplied.size() != 1) {
                                throw invalid(activation);
                            }
                            return ProtosBooleanValue.of(
                                    ProtosNumericValueSupport.isFraction(supplied.get(0)));
                        }));

        if (!kernel.isOpen()
                || kernel.parent().orElse(null) != ProtosObjectValue.rootObject()
                || !kernel.localSlotsSnapshot().isEmpty()) {
            throw new IllegalStateException(
                    "Core _coreFractionKernel must be an empty open ordinary object");
        }
        kernel.createLocalSlot(
                "numerator",
                ProtosClosureValue.nativeClosure(
                        (activation, supplied) -> {
                            Object value = requireExactReal(activation, supplied, kernel);
                            return ProtosNumericValueSupport.isFraction(value)
                                    ? ProtosNumericValueSupport.fractionNumerator(value)
                                    : value;
                        }));
        kernel.createLocalSlot(
                "denominator",
                ProtosClosureValue.nativeClosure(
                        (activation, supplied) -> {
                            Object value = requireExactReal(activation, supplied, kernel);
                            return ProtosNumericValueSupport.isFraction(value)
                                    ? ProtosNumericValueSupport.fractionDenominator(value)
                                    : ProtosNumericValueSupport.integer(1L);
                        }));
        kernel.createLocalSlot(
                "normalize",
                ProtosClosureValue.nativeClosure(
                        (activation, supplied) -> normalize(activation, supplied, kernel)));
        kernel.createLocalSlot(
                "toFloat",
                ProtosClosureValue.nativeClosure(
                        (activation, supplied) -> {
                            if (activation.receiver() != kernel
                                    || supplied.size() != 1
                                    || !ProtosNumericValueSupport.isFraction(supplied.get(0))) {
                                throw invalid(activation);
                            }
                            return new ProtosFloatValue(
                                    ProtosNumericValueSupport.roundFractionToBinary64(
                                            supplied.get(0)));
                        }));
        kernel.freeze();
    }

    private static Object requireExactReal(
            ProtosActivation activation, List<?> supplied, ProtosObjectValue kernel) {
        if (activation.receiver() != kernel || supplied.size() != 1) {
            throw invalid(activation);
        }
        Object value = supplied.get(0);
        if (!ProtosNumericValueSupport.isCurrentInteger(value)
                && !ProtosNumericValueSupport.isFraction(value)) {
            throw invalid(activation);
        }
        return value;
    }

    /*
     * The narrowly permitted exact materialization boundary: both operands are semantic
     * Integers already computed by source arithmetic; the result is canonical and owned by the
     * caller's Prelude.
     */
    private static Object normalize(
            ProtosActivation activation, List<?> supplied, ProtosObjectValue kernel) {
        if (activation.receiver() != kernel
                || supplied.size() != 2
                || !ProtosNumericValueSupport.isCurrentInteger(supplied.get(0))
                || !ProtosNumericValueSupport.isCurrentInteger(supplied.get(1))
                || ProtosNumericValueSupport.integerSignum(supplied.get(1)) == 0) {
            throw invalid(activation);
        }
        ProtosPrelude prelude = activation.prelude().orElseThrow(
                () -> new IllegalStateException("Fraction normalization requires Core prelude"));
        return ProtosNumericValueSupport.normalizeExactRational(
                supplied.get(0), supplied.get(1), prelude);
    }

    private static ProtosSignalException invalid(ProtosActivation activation) {
        return new ProtosSignalException(ProtosCoreErrors.newError(activation));
    }
}
