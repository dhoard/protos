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
import com.guillermomolina.protos.runtime.ProtosComplexArithmetic;
import com.guillermomolina.protos.runtime.ProtosComplexNumericSupport;
import com.guillermomolina.protos.runtime.ProtosCoreErrors;
import com.guillermomolina.protos.runtime.ProtosNativeClosureBody;
import com.guillermomolina.protos.runtime.ProtosNumericValueSupport;
import com.guillermomolina.protos.runtime.ProtosObjectValue;
import com.guillermomolina.protos.runtime.ProtosPrelude;
import com.guillermomolina.protos.runtime.ProtosSignalException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Core bootstrap bridge for the source-backed Complex prototype (D197-C, D198, I090).
 *
 * <p>Complex negation and arithmetic are source closures of {@code lib/core/Complex.protos}, and
 * the component selectors are source closures of {@code lib/core/Number.protos}. This bridge
 * moves the operator closures to their operator slots and installs the two behaviors that must
 * not dispatch on their operands: the exact trusted {@code recognizes}, and the
 * {@code Complex(real, imaginary)} factory, which admits only genuine real components and applies
 * only Complex canonical form. It also fills the private {@code _coreComplexKernel} with the
 * physical primitives of {@link ProtosComplexArithmetic} and the component reads; none of them
 * sends a guest message.
 */
public final class ProtosStandardComplexProtocol {
    /* Source selector moved to its operator slot; a slot target cannot spell an operator. */
    private static final Map<String, String> SOURCE_OPERATORS =
            Map.of(
                    "_coreComplexPlus", "+",
                    "_coreComplexMinus", "-",
                    "_coreComplexTimes", "*",
                    "_coreComplexDivide", "/");
    private static final Set<String> SOURCE_SELECTORS =
            Set.of(
                    "negated",
                    "_coreComplexPlus",
                    "_coreComplexMinus",
                    "_coreComplexTimes",
                    "_coreComplexDivide");

    private static final Map<String, ProtosComplexArithmetic.Operation> KERNEL_OPERATIONS =
            Map.of(
                    "plus", ProtosComplexArithmetic.Operation.ADD,
                    "minus", ProtosComplexArithmetic.Operation.SUBTRACT,
                    "times", ProtosComplexArithmetic.Operation.MULTIPLY,
                    "divide", ProtosComplexArithmetic.Operation.DIVIDE);

    /* One kernel primitive: the supplied arguments, already arity-checked, and the caller's Prelude. */
    private interface KernelPrimitive {
        Object apply(List<?> supplied, ProtosPrelude prelude);
    }

    private ProtosStandardComplexProtocol() {}

    public static void install(
            ProtosObjectValue prototype,
            ProtosObjectValue numberPrototype,
            ProtosObjectValue kernel) {
        Objects.requireNonNull(prototype, "prototype");
        Objects.requireNonNull(numberPrototype, "numberPrototype");
        Objects.requireNonNull(kernel, "kernel");
        if (prototype.parent().orElse(null) != numberPrototype) {
            throw new IllegalStateException(
                    "standard Complex prototype must delegate directly to Number");
        }
        if (!prototype.isOpen()
                || !prototype.localSlotsSnapshot().keySet().equals(SOURCE_SELECTORS)) {
            throw new IllegalStateException(
                    "standard Complex prototype must be an open source object with exactly "
                            + SOURCE_SELECTORS);
        }
        for (String selector : SOURCE_SELECTORS) {
            requireSourceBacked(prototype, selector, "Complex");
        }
        requireSourceBacked(numberPrototype, "real", "Number");
        requireSourceBacked(numberPrototype, "imaginary", "Number");
        for (Map.Entry<String, String> alias : SOURCE_OPERATORS.entrySet()) {
            Object closure = prototype.readLocalSlot(alias.getKey()).orElseThrow();
            prototype.removeLocalSlot(alias.getKey());
            prototype.createLocalSlot(alias.getValue(), closure);
        }
        installNative(prototype, "recognizes", 1, (supplied, prelude) ->
                ProtosBooleanValue.of(ProtosComplexNumericSupport.isComplex(supplied.get(0))));
        // D198: the arguments become the components exactly; only an exact Integer zero
        // imaginary part collapses to the real part. No conversion or callback occurs.
        installNative(prototype, "call", 2, (supplied, prelude) ->
                ProtosNumericValueSupport.isCurrentRealNumber(supplied.get(0))
                                && ProtosNumericValueSupport.isCurrentRealNumber(supplied.get(1))
                        ? ProtosComplexNumericSupport.normalize(
                                supplied.get(0), supplied.get(1), prelude)
                        : null);

        if (!kernel.isOpen()
                || kernel.parent().orElse(null) != ProtosObjectValue.rootObject()
                || !kernel.localSlotsSnapshot().isEmpty()) {
            throw new IllegalStateException(
                    "Core _coreComplexKernel must be an empty open ordinary object");
        }
        // Component reads for the Number selectors: a real value is its own real part and has
        // the exact SmallInteger 0 as imaginary part.
        installNative(kernel, "real", 1, (supplied, prelude) -> component(supplied.get(0), true));
        installNative(
                kernel, "imaginary", 1, (supplied, prelude) -> component(supplied.get(0), false));
        installNative(kernel, "negated", 1, (supplied, prelude) ->
                ProtosComplexArithmetic.negated(supplied.get(0), prelude));
        for (Map.Entry<String, ProtosComplexArithmetic.Operation> operation
                : KERNEL_OPERATIONS.entrySet()) {
            installNative(kernel, operation.getKey(), 2, (supplied, prelude) ->
                    ProtosComplexArithmetic.binary(
                            operation.getValue(), supplied.get(0), supplied.get(1), prelude));
        }
        kernel.freeze();
    }

    private static Object component(Object value, boolean real) {
        if (ProtosComplexNumericSupport.isComplex(value)) {
            return real
                    ? ProtosComplexNumericSupport.real(value)
                    : ProtosComplexNumericSupport.imaginary(value);
        }
        if (!ProtosNumericValueSupport.isCurrentRealNumber(value)) {
            return null;
        }
        return real ? value : ProtosNumericValueSupport.integer(0L);
    }

    /*
     * Installs a native behavior that requires its exact owner as receiver and exactly the given
     * arity. A null primitive result, or the ArithmeticException of an exact zero divisor,
     * signals the standard Error.
     */
    private static void installNative(
            ProtosObjectValue owner, String selector, int arity, KernelPrimitive primitive) {
        ProtosNativeClosureBody body = (activation, supplied) -> {
            if (activation.receiver() != owner || supplied.size() != arity) {
                throw invalid(activation);
            }
            // Only minting consults the Prelude; recognition never needs one.
            ProtosPrelude prelude = activation.prelude().orElse(null);
            Object result;
            try {
                result = primitive.apply(supplied, prelude);
            } catch (ArithmeticException divisionByZero) {
                throw invalid(activation);
            }
            if (result == null) {
                throw invalid(activation);
            }
            return result;
        };
        owner.createLocalSlot(selector, ProtosClosureValue.nativeClosure(body));
    }

    private static void requireSourceBacked(
            ProtosObjectValue prototype, String selector, String owner) {
        Object value = prototype.readLocalSlot(selector).orElse(null);
        if (!(value instanceof ProtosClosureValue closure)
                || closure.definition() == null
                || closure.executionPlan().isEmpty()
                || closure.nativeBody().isPresent()) {
            throw new IllegalStateException(
                    "standard " + owner + " " + selector + " must be a source-backed Closure");
        }
    }

    private static ProtosSignalException invalid(ProtosActivation activation) {
        return new ProtosSignalException(ProtosCoreErrors.newError(activation));
    }
}
