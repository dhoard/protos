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

package com.guillermomolina.protos.runtime;

import java.util.Objects;

/**
 * Physical boundary of the Core Complex family (D197-C, PLAT056-C, I090-C1).
 *
 * <p>A Complex is a frozen {@link ProtosSealedValue} of a private family token held only here,
 * delegating to the {@code Complex} prototype of its owning Prelude. Its private state is a real
 * and an imaginary component, each a genuine semantic real number (Integer, Fraction or Float)
 * kept exactly as supplied: no exact component is rounded to binary64. Neither component is a
 * slot, and an ordinary object delegating to {@code Complex} is never a Complex.
 *
 * <p>Canonical form: an exact Integer zero imaginary part is never retained, so such a value is
 * its real part; a Float zero imaginary part of either sign is retained, because its sign is
 * observable through identity. This class owns only representation, authentication, identity and
 * staged transfer; mathematical equality and normal hashing stay with the Number relations, and
 * no Complex arithmetic is defined here. Nothing here sends a message to a guest value.
 */
public final class ProtosComplexNumericSupport {
    /*
     * I091: Complex family identity is initialized only when a genuine
     * sealed Complex needs authentication or construction.
     */
    private static final class ComplexFamilyToken {
        static final Object VALUE = new Object();
    }

    /** The immutable components of one Complex; never reachable from guest code. */
    private record ComplexState(Object real, Object imaginary) {}

    private ProtosComplexNumericSupport() {}

    /** Whether {@code value} is a genuine canonical Complex. */
    public static boolean isComplex(Object value) {
        return value instanceof ProtosSealedValue sealed && sealed.belongsTo(ComplexFamilyToken.VALUE);
    }

    /** The real component of a value admitted by {@link #isComplex}. */
    public static Object real(Object complex) {
        return state(complex).real();
    }

    /** The imaginary component of a value admitted by {@link #isComplex}; never Integer zero. */
    public static Object imaginary(Object complex) {
        return state(complex).imaginary();
    }

    private static ComplexState state(Object complex) {
        if (!isComplex(complex)) {
            throw new IllegalArgumentException("value is not a canonical Complex");
        }
        return (ComplexState) ((ProtosSealedValue) complex).stateFor(ComplexFamilyToken.VALUE);
    }

    /**
     * The canonical value with components {@code real} and {@code imaginary}: {@code real} itself
     * when {@code imaginary} is an exact Integer zero, otherwise one fresh Complex owned by
     * {@code prelude}. Both components must be semantic real numbers already owned by
     * {@code prelude}.
     *
     * <p>I090-C1 internal precondition, not a public Complex rule: a Float component must be
     * finite. NaN and infinite components are not yet defined for Complex (D197).
     */
    public static Object normalize(Object real, Object imaginary, ProtosPrelude prelude) {
        requireComponent(real);
        requireComponent(imaginary);
        if (ProtosNumericValueSupport.isCurrentInteger(imaginary)
                && ProtosNumericValueSupport.integerSignum(imaginary) == 0) {
            return real;
        }
        Objects.requireNonNull(prelude, "a Complex requires its owning Prelude");
        // mint answers a frozen value whose parent is exactly the owning Complex prototype.
        return ProtosSealedValue.mint(
                ComplexFamilyToken.VALUE, prelude.complexPrototype(), new ComplexState(real, imaginary));
    }

    private static void requireComponent(Object component) {
        if (!ProtosNumericValueSupport.isCurrentRealNumber(component)
                || (component instanceof ProtosFloatValue floating
                        && !Double.isFinite(floating.value()))) {
            throw new IllegalArgumentException(
                    "a Complex component must be a finite semantic real number");
        }
    }

    /** Value identity of two Complex values: component-wise real-number identity. */
    static boolean sameComplex(Object left, Object right) {
        ComplexState a = state(left);
        ComplexState b = state(right);
        return ProtosNumericValueSupport.sameCurrentFamilyIdentity(a.real(), b.real())
                && ProtosNumericValueSupport.sameCurrentFamilyIdentity(
                        a.imaginary(), b.imaginary());
    }

    /** Deterministic hash of the components' identity hashes, consistent with sameComplex. */
    static int componentIdentityHash(Object complex) {
        ComplexState state = state(complex);
        return Long.hashCode(
                31L * ProtosNumericValueSupport.currentNumericIdentityHash(state.real())
                        + ProtosNumericValueSupport.currentNumericIdentityHash(
                                state.imaginary()));
    }

    /*
     * Staged isolation transfer (as Fraction): the source stage replaces a Complex with an inert
     * descriptor holding only host scalars and inert component descriptors, never a guest
     * object, prototype or Prelude; the destination stage rebuilds each component in the
     * destination Prelude and mints one Complex with the destination's Complex prototype.
     */
    private record ComplexTransferDescriptor(Object real, Object imaginary) {}

    /** The raw binary64 pattern of a Float component, so a signed zero keeps its sign. */
    private record FloatBits(long bits) {}

    /** The inert transfer descriptor of a value admitted by {@link #isComplex}. */
    public static Object transferDescriptor(Object complex) {
        ComplexState state = state(complex);
        return new ComplexTransferDescriptor(
                componentDescriptor(state.real()), componentDescriptor(state.imaginary()));
    }

    public static boolean isTransferDescriptor(Object value) {
        return value instanceof ComplexTransferDescriptor;
    }

    /** One destination Complex for a descriptor of {@link #transferDescriptor}. */
    public static Object materializeTransferDescriptor(
            Object descriptor, ProtosPrelude destination) {
        if (!(descriptor instanceof ComplexTransferDescriptor inert)) {
            throw new IllegalArgumentException("value is not a Complex transfer descriptor");
        }
        Objects.requireNonNull(destination, "a Complex requires its owning Prelude");
        return normalize(
                materializeComponent(inert.real(), destination),
                materializeComponent(inert.imaginary(), destination),
                destination);
    }

    private static Object componentDescriptor(Object component) {
        if (component instanceof ProtosFloatValue floating) {
            return new FloatBits(Double.doubleToRawLongBits(floating.value()));
        }
        if (ProtosNumericValueSupport.isFraction(component)) {
            return ProtosNumericValueSupport.fractionTransferDescriptor(component);
        }
        return ProtosNumericValueSupport.hostInteger(component);
    }

    private static Object materializeComponent(Object descriptor, ProtosPrelude destination) {
        if (descriptor instanceof FloatBits floating) {
            return ProtosNumericValueSupport.floating(Double.longBitsToDouble(floating.bits()));
        }
        if (ProtosNumericValueSupport.isFractionTransferDescriptor(descriptor)) {
            return ProtosNumericValueSupport.materializeFractionTransferDescriptor(
                    descriptor, destination);
        }
        return ProtosNumericValueSupport.integerFromHost(descriptor, destination);
    }
}
