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

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.UnsupportedMessageException;
import com.oracle.truffle.api.library.ExportLibrary;
import com.oracle.truffle.api.library.ExportMessage;

import java.math.BigInteger;
import java.util.Objects;

/**
 * I091 / PLAT056 Candidate C: an exact semantic Integer outside the signed-64 range, materialized
 * as a FROZEN ordinary Protos object.
 *
 * <p>The value delegates ordinarily to the {@code BigInteger} prototype of the Prelude that minted
 * it, has no local slots, and cannot be mutated. Its exact magnitude is the narrowly encapsulated numeric
 * payload PLAT056 permits. The value performs the exact operations on its own magnitude (order,
 * equality, hash, spelling, unsigned encoding, interop projection); they are package-private and
 * reached only through {@link ProtosNumericValueSupport}, which owns dispatch between the
 * signed-64 and large representations, arithmetic and normalization, so a value within the
 * signed-64 range is never represented here.
 *
 * <p>Under D197-C (I090) this is a member of the concrete {@code BigInteger} family, recognized by
 * both {@code Integer.recognizes} and {@code BigInteger.recognizes}. The family is determined by
 * the exact magnitude alone; the Java payload is unobservable.
 *
 * <p>Instances are minted per Prelude and are never shared across isolated execution domains:
 * transfer rematerializes them with the destination Prelude's prototype.
 */
@ExportLibrary(InteropLibrary.class)
public final class ProtosLargeIntegerValue extends ProtosObjectValue {
    private static final int FLOAT_PRECISION = 24;
    private static final int FLOAT_MAX_BITS = 128;
    private static final int DOUBLE_PRECISION = 53;
    private static final int DOUBLE_MAX_BITS = 1024;

    private final BigInteger value;

    ProtosLargeIntegerValue(ProtosObjectValue bigIntegerPrototype, BigInteger value) {
        super(Objects.requireNonNull(bigIntegerPrototype, "bigIntegerPrototype"));
        this.value = Objects.requireNonNull(value, "value");
        if (value.bitLength() < Long.SIZE) {
            throw new IllegalArgumentException("a signed-64 Integer is not a large Integer");
        }
        freeze();
    }

    /** The exact value, for arbitrary-precision arithmetic inside the numeric boundary only. */
    BigInteger exactValue() {
        return value;
    }

    int signum() {
        return value.signum();
    }

    @TruffleBoundary
    int compareTo(ProtosLargeIntegerValue other) {
        return value.compareTo(other.value);
    }

    @TruffleBoundary
    boolean sameValue(ProtosLargeIntegerValue other) {
        return value.equals(other.value);
    }

    /** Hash of the exact value, equal to the canonical arbitrary-precision host hash. */
    @TruffleBoundary
    int exactHashCode() {
        return value.hashCode();
    }

    @TruffleBoundary
    String decimalText() {
        return value.toString();
    }

    /** Whether the value is non-negative and has at most {@code bits} bits. */
    @TruffleBoundary
    boolean fitsUnsignedBits(int bits) {
        if (bits < 0) {
            throw new IllegalArgumentException("negative unsigned width");
        }
        return value.signum() >= 0 && value.bitLength() <= bits;
    }

    /** The unsigned big-endian encoding in exactly {@code width} octets; requires a fit. */
    @TruffleBoundary
    byte[] toUnsignedBigEndian(int width) {
        if (width < 0 || !fitsUnsignedBits(ProtosNumericValueSupport.unsignedBitsOfOctets(width))) {
            throw new ArithmeticException("Integer does not fit the unsigned width");
        }
        byte[] raw = value.toByteArray();
        int offset = raw.length > 1 && raw[0] == 0 ? 1 : 0;
        int length = raw.length - offset;
        byte[] result = new byte[width];
        System.arraycopy(raw, offset, result, width - length, length);
        return result;
    }

    /*
     * Exact representability in a binary floating format of the given precision whose finite
     * magnitudes have at most maxBits bits. Negation preserves the lowest set bit, and the
     * two's-complement bit length of -m equals the bit length of m except when m is a power of
     * two, where it is one less; so the magnitude is measured without materializing it.
     */
    private boolean exactlyRepresentable(int precision, int maxBits) {
        int lowest = value.getLowestSetBit();
        int bits = value.bitLength();
        if (value.signum() < 0 && lowest == bits) {
            bits++;
        }
        return bits <= maxBits && bits - lowest <= precision;
    }

    @ExportMessage
    boolean isNumber() {
        return true;
    }

    /* Outside the signed-64 range no fixed host integral width can hold the value. */
    @ExportMessage
    boolean fitsInByte() {
        return false;
    }

    @ExportMessage
    boolean fitsInShort() {
        return false;
    }

    @ExportMessage
    boolean fitsInInt() {
        return false;
    }

    @ExportMessage
    boolean fitsInLong() {
        return false;
    }

    @ExportMessage
    boolean fitsInBigInteger() {
        return true;
    }

    @ExportMessage
    @TruffleBoundary
    boolean fitsInFloat() {
        return exactlyRepresentable(FLOAT_PRECISION, FLOAT_MAX_BITS);
    }

    @ExportMessage
    @TruffleBoundary
    boolean fitsInDouble() {
        return exactlyRepresentable(DOUBLE_PRECISION, DOUBLE_MAX_BITS);
    }

    @ExportMessage
    byte asByte() throws UnsupportedMessageException {
        throw UnsupportedMessageException.create();
    }

    @ExportMessage
    short asShort() throws UnsupportedMessageException {
        throw UnsupportedMessageException.create();
    }

    @ExportMessage
    int asInt() throws UnsupportedMessageException {
        throw UnsupportedMessageException.create();
    }

    @ExportMessage
    long asLong() throws UnsupportedMessageException {
        throw UnsupportedMessageException.create();
    }

    @ExportMessage
    BigInteger asBigInteger() {
        return value;
    }

    @ExportMessage
    @TruffleBoundary
    float asFloat() throws UnsupportedMessageException {
        if (!fitsInFloat()) {
            throw UnsupportedMessageException.create();
        }
        return value.floatValue();
    }

    @ExportMessage
    @TruffleBoundary
    double asDouble() throws UnsupportedMessageException {
        if (!fitsInDouble()) {
            throw UnsupportedMessageException.create();
        }
        return value.doubleValue();
    }

    @Override
    @ExportMessage
    @TruffleBoundary
    String toDisplayString(@SuppressWarnings("unused") boolean allowSideEffects) {
        return decimalText();
    }
}
