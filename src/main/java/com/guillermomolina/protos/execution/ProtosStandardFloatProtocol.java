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

import com.guillermomolina.protos.runtime.ProtosBinary64Rounding;
import com.guillermomolina.protos.runtime.ProtosBooleanValue;
import com.guillermomolina.protos.runtime.ProtosClosureValue;
import com.guillermomolina.protos.runtime.ProtosComplexArithmetic;
import com.guillermomolina.protos.runtime.ProtosComplexNumericSupport;
import com.guillermomolina.protos.runtime.ProtosCoreErrors;
import com.guillermomolina.protos.runtime.ProtosFloatValue;
import com.guillermomolina.protos.runtime.ProtosNativeClosureBody;
import com.guillermomolina.protos.runtime.ProtosActivation;
import com.guillermomolina.protos.runtime.ProtosPrelude;
import com.guillermomolina.protos.runtime.ProtosNumericValueSupport;
import com.guillermomolina.protos.runtime.ProtosObjectValue;
import com.guillermomolina.protos.runtime.ProtosSignalException;
import java.util.List;
import java.util.Objects;
import java.util.function.DoubleBinaryOperator;

public final class ProtosStandardFloatProtocol {
    private ProtosStandardFloatProtocol() {}

    public static void install(ProtosObjectValue floatPrototype) {
        Objects.requireNonNull(floatPrototype, "floatPrototype");
        requireSourceBackedClosure(floatPrototype, "negated");

        if (floatPrototype.hasLocalSlot("recognizes")) {
            throw new IllegalStateException(
                    "Core Float already defines a local recognizes slot");
        }
        floatPrototype.createLocalSlot(
                "recognizes",
                ProtosClosureValue.nativeClosure(
                        (activation, supplied) -> {
                            if (activation.receiver() != floatPrototype
                                    || supplied.size() != 1) {
                                throw new ProtosSignalException(
                                        ProtosCoreErrors.newError(activation));
                            }
                            return ProtosBooleanValue.of(
                                    ProtosNumericValueSupport.isCurrentFloat(
                                            supplied.get(0)));
                        }));

        installBinary(floatPrototype, "+", ProtosComplexArithmetic.Operation.ADD,
                (left, right) -> left + right);
        installBinary(floatPrototype, "-", ProtosComplexArithmetic.Operation.SUBTRACT,
                (left, right) -> left - right);
        installBinary(floatPrototype, "*", ProtosComplexArithmetic.Operation.MULTIPLY,
                (left, right) -> left * right);
        installBinary(floatPrototype, "/", ProtosComplexArithmetic.Operation.DIVIDE,
                (left, right) -> left / right);
    }

    enum CanonicalFloatOperation {
        ADD("+"), SUBTRACT("-"), MULTIPLY("*"), DIVIDE("/");

        private final String selector;

        CanonicalFloatOperation(String selector) {
            this.selector = selector;
        }

        static CanonicalFloatOperation forSelector(String selector) {
            for (CanonicalFloatOperation value : values()) {
                if (value.selector.equals(selector)) {
                    return value;
                }
            }
            throw new IllegalArgumentException(
                    "not a standard Float selector: " + selector);
        }
    }

    private static final class CanonicalFloatBody implements ProtosNativeClosureBody {
        private final ProtosObjectValue home;
        private final CanonicalFloatOperation operation;
        private final ProtosNativeClosureBody delegate;
        private ProtosClosureValue owner;

        CanonicalFloatBody(
                ProtosObjectValue home,
                String selector,
                ProtosNativeClosureBody delegate) {
            this.home = Objects.requireNonNull(home, "home");
            this.operation = CanonicalFloatOperation.forSelector(selector);
            this.delegate = Objects.requireNonNull(delegate, "delegate");
        }

        void bindOwner(ProtosClosureValue closure) {
            if (owner != null) {
                throw new IllegalStateException(
                        "Float native closure owner already bound");
            }
            owner = Objects.requireNonNull(closure, "closure");
        }

        boolean isCanonicalSelection(
                ProtosClosureValue selected,
                ProtosObjectValue methodHome,
                String selector,
                ProtosPrelude prelude) {
            return owner != null
                    && selected == owner
                    && methodHome == home
                    && prelude != null
                    && home == prelude.floatPrototype()
                    && operation.selector.equals(selector);
        }

        @Override
        public Object execute(ProtosActivation activation, List<?> supplied) {
            return delegate.execute(activation, supplied);
        }
    }

    static CanonicalFloatOperation canonicalOperationForSelection(
            ProtosClosureValue selected,
            ProtosObjectValue methodHome,
            String selector,
            ProtosPrelude prelude) {
        Objects.requireNonNull(selected, "selected");
        Objects.requireNonNull(methodHome, "methodHome");
        Objects.requireNonNull(selector, "selector");

        ProtosNativeClosureBody body = selected.nativeBody().orElse(null);
        if (!(body instanceof CanonicalFloatBody canonical)
                || !canonical.isCanonicalSelection(
                        selected, methodHome, selector, prelude)) {
            return null;
        }
        return canonical.operation;
    }

    private static void installBinary(
            ProtosObjectValue floatPrototype,
            String selector,
            ProtosComplexArithmetic.Operation complexOperation,
            DoubleBinaryOperator operation) {
        if (floatPrototype.hasLocalSlot(selector)) {
            throw new IllegalStateException(
                    "Core Float already defines a local " + selector + " slot");
        }
        ProtosNativeClosureBody delegate = (activation, supplied) -> {
            ProtosFloatValue receiver = requireFloatReceiver(activation);
            if (supplied.size() != 1) {
                throw new ProtosSignalException(
                        ProtosCoreErrors.newError(activation));
            }
            Object argument = supplied.get(0);
            double right;
            if (argument instanceof ProtosFloatValue floating) {
                right = floating.value();
            } else if (ProtosNumericValueSupport.isCurrentInteger(argument)) {
                right = ProtosBinary64Rounding.roundExactInteger(argument);
            } else if (ProtosNumericValueSupport.isFraction(argument)) {
                // D197: the whole exact quotient is rounded once, before the binary64 operation.
                right = ProtosNumericValueSupport.roundFractionToBinary64(argument);
            } else if (ProtosComplexNumericSupport.isComplex(argument)) {
                // D198: Float op Complex is standard Complex arithmetic. A native body cannot
                // call guest code, so the private Complex kernel computes it.
                return ProtosComplexArithmetic.binary(
                        complexOperation,
                        receiver,
                        argument,
                        activation.prelude().orElseThrow(() -> new IllegalStateException(
                                "Float arithmetic requires Core prelude")));
            } else {
                throw new ProtosSignalException(
                        ProtosCoreErrors.newError(activation));
            }
            return new ProtosFloatValue(
                    operation.applyAsDouble(receiver.value(), right));
        };
        CanonicalFloatBody canonical =
                new CanonicalFloatBody(floatPrototype, selector, delegate);
        ProtosClosureValue installed = ProtosClosureValue.nativeClosure(canonical);
        canonical.bindOwner(installed);
        floatPrototype.createLocalSlot(selector, installed);

    }

    private static void requireSourceBackedClosure(
            ProtosObjectValue prototype, String selector) {
        Object value =
                prototype
                        .readLocalSlot(selector)
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "Core Float source did not define " + selector));
        if (!(value instanceof ProtosClosureValue closure)
                || closure.definition() == null
                || closure.executionPlan().isEmpty()
                || closure.nativeBody().isPresent()) {
            throw new IllegalStateException(
                    "Core Float." + selector
                            + " must be installed from distributable Core source");
        }
    }

    private static ProtosFloatValue requireFloatReceiver(
            com.guillermomolina.protos.runtime.ProtosActivation activation) {
        if (!(activation.receiver() instanceof ProtosFloatValue floating)) {
            throw new ProtosSignalException(ProtosCoreErrors.newError(activation));
        }
        return floating;
    }
}
