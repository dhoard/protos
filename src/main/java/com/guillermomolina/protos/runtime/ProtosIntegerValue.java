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

import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.UnsupportedMessageException;
import com.oracle.truffle.api.library.ExportLibrary;
import com.oracle.truffle.api.library.ExportMessage;

import java.math.BigInteger;

/**
 * I091 / PLAT056 Candidate C: a semantic Integer within the signed-64 range, represented by its
 * exact {@code long} value only.
 *
 * <p>Under D197-C (I090) such a value belongs to the concrete {@code SmallInteger} family: its
 * semantic delegation parent is the {@code SmallInteger} prototype of the observing Prelude, which
 * delegates to the common {@code Integer} prototype. The Java class name is representation only.
 *
 * <p>An exact Integer outside that range is a {@link ProtosLargeIntegerValue}, a frozen ordinary
 * guest object. The two never denote the same value: every exact result is normalized through
 * {@link ProtosNumericValueSupport}, so a value within the signed-64 range is always a
 * {@code ProtosIntegerValue}. Generic clients recognize both through that boundary.
 */
@ExportLibrary(InteropLibrary.class)
public final class ProtosIntegerValue implements ProtosRepresentedValue {
    private final long value;

    public ProtosIntegerValue(long value) {
        this.value = value;
    }

    /** The exact value. */
    public long longValue() {
        return value;
    }

    int signumForRuntime() {
        return Long.signum(value);
    }

    boolean fitsInIntForRuntime() {
        return value >= Integer.MIN_VALUE && value <= Integer.MAX_VALUE;
    }

    int intValueExactForRuntime() {
        if (!fitsInIntForRuntime()) {
            throw new ArithmeticException("Integer does not fit in int");
        }
        return (int) value;
    }

    /** Exact unsigned-width check for bounded host protocols. */
    boolean fitsUnsignedBitsForRuntime(int bits) {
        if (bits < 0) {
            throw new IllegalArgumentException("negative unsigned width");
        }
        if (value < 0) {
            return false;
        }
        return bits >= Long.SIZE - 1 || (value >>> bits) == 0L;
    }

    /**
     * Unsigned big-endian encoding of exactly {@code width} octets. The value must be
     * non-negative and fit {@code width * 8} bits; the encoding never truncates.
     */
    byte[] toUnsignedBigEndianForRuntime(int width) {
        if (width < 0 || !fitsUnsignedBitsForRuntime(
                ProtosNumericValueSupport.unsignedBitsOfOctets(width))) {
            throw new ArithmeticException("Integer does not fit the unsigned width");
        }
        byte[] result = new byte[width];
        long remaining = value;
        for (int index = width - 1; index >= 0 && remaining != 0L; index--) {
            result[index] = (byte) remaining;
            remaining >>>= Byte.SIZE;
        }
        return result;
    }

    /**
     * Hash of the exact value, identical to the canonical arbitrary-precision host hash of the
     * same value, so the observable identity hash never depends on the representation.
     */
    int exactHashCodeForRuntime() {
        if (value == 0L) {
            return 0;
        }
        long magnitude = value < 0L ? -value : value;
        int high = (int) (magnitude >>> Integer.SIZE);
        int low = (int) magnitude;
        int hash = high != 0 ? 31 * high + low : low;
        return value < 0L ? -hash : hash;
    }

    /** Canonical decimal spelling of the exact value. */
    String decimalTextForRuntime() {
        return Long.toString(value);
    }

    @Override
    public Object representedDelegationParent(ProtosPrelude prelude) {
        return ProtosRepresentedValue.requirePrelude(prelude, "SmallInteger")
                .smallIntegerPrototype();
    }

    @ExportMessage
    boolean isNumber() {
        return true;
    }

    @ExportMessage
    boolean fitsInByte() {
        return value >= Byte.MIN_VALUE && value <= Byte.MAX_VALUE;
    }

    @ExportMessage
    boolean fitsInShort() {
        return value >= Short.MIN_VALUE && value <= Short.MAX_VALUE;
    }

    @ExportMessage
    boolean fitsInInt() {
        return fitsInIntForRuntime();
    }

    @ExportMessage
    boolean fitsInLong() {
        return true;
    }

    @ExportMessage
    boolean fitsInBigInteger() {
        return true;
    }

    @ExportMessage
    boolean fitsInFloat() {
        return ProtosIntegralInteropSupport.fitsInFloat(value);
    }

    @ExportMessage
    boolean fitsInDouble() {
        return ProtosIntegralInteropSupport.fitsInDouble(value);
    }

    @ExportMessage
    byte asByte() throws UnsupportedMessageException {
        if (!fitsInByte()) {
            throw UnsupportedMessageException.create();
        }
        return (byte) value;
    }

    @ExportMessage
    short asShort() throws UnsupportedMessageException {
        if (!fitsInShort()) {
            throw UnsupportedMessageException.create();
        }
        return (short) value;
    }

    @ExportMessage
    int asInt() throws UnsupportedMessageException {
        if (!fitsInInt()) {
            throw UnsupportedMessageException.create();
        }
        return (int) value;
    }

    @ExportMessage
    long asLong() {
        return value;
    }

    /* Truffle interop contract: a temporary host projection, never retained as state. */
    @ExportMessage
    BigInteger asBigInteger() {
        return BigInteger.valueOf(value);
    }

    @ExportMessage
    float asFloat() throws UnsupportedMessageException {
        if (!fitsInFloat()) {
            throw UnsupportedMessageException.create();
        }
        return (float) value;
    }

    @ExportMessage
    double asDouble() throws UnsupportedMessageException {
        if (!fitsInDouble()) {
            throw UnsupportedMessageException.create();
        }
        return (double) value;
    }

    @ExportMessage
    String toDisplayString(@SuppressWarnings("unused") boolean allowSideEffects) {
        return decimalTextForRuntime();
    }
}
