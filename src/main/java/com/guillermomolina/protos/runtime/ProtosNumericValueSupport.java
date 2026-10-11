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

import java.math.BigInteger;
import java.util.Objects;

/**
 * Internal boundary for the currently published Core numeric carriers.
 *
 * <p>A semantic Integer is a {@link ProtosIntegerValue} within the signed-64
 * range or a {@link ProtosLargeIntegerValue} outside it (I091 / PLAT056
 * Candidate C); every exact Integer result is normalized here, so the two never
 * denote the same value. A Float is a {@link ProtosFloatValue}. Host Long,
 * Double, BigInteger, and arbitrary frozen guest objects do not thereby become
 * semantic Protos numbers. A Fraction (D197-C, I090) is a frozen
 * {@link ProtosSealedValue} of a private family token held only here, whose
 * private state is its canonical numerator and denominator Integers; an
 * ordinary object delegating to {@code Fraction} is never a Fraction. A Complex (I090-C1) is
 * represented by {@link ProtosComplexNumericSupport}; this class only bridges it into the
 * generic Number domain, identity and identity hash.
 *
 * <p>Ordinary copies are created only at explicit transfer boundaries.
 * This class installs no guest methods, global registry, or rich-value
 * materialization in ordinary arithmetic.
 */
public final class ProtosNumericValueSupport {
    private ProtosNumericValueSupport() {}

    public static boolean isCurrentInteger(Object value) {
        return value instanceof ProtosIntegerValue || value instanceof ProtosLargeIntegerValue;
    }

    /** Whether {@code value} is a semantic Integer outside the signed-64 range. */
    public static boolean isLargeInteger(Object value) {
        return value instanceof ProtosLargeIntegerValue;
    }

    public static boolean isCurrentFloat(Object value) {
        return value instanceof ProtosFloatValue;
    }

    /** Whether {@code value} is a genuine Core Number of any family, Complex included. */
    public static boolean isCurrentNumber(Object value) {
        return isCurrentRealNumber(value) || ProtosComplexNumericSupport.isComplex(value);
    }

    /**
     * Whether {@code value} is a genuine Core real number: an Integer, a Fraction or a Float.
     * Real-only services (ordering, Complex components) admit exactly this domain.
     */
    public static boolean isCurrentRealNumber(Object value) {
        return isCurrentInteger(value) || isCurrentFloat(value) || isFraction(value);
    }

    /*
     * D197-C exact rational service (I090). The family token is private and immutable, so only
     * this boundary mints or authenticates a Fraction; recognition reads the host
     * representation alone and never sends a message to the candidate. The canonical state
     * invariant is denominator > 1 and gcd(|numerator|, denominator) = 1, both normalized
     * semantic Integers; zero and integral quotients are Integers, never Fractions.
     */

    private static final Object FRACTION_FAMILY = new Object();

    /** Canonical immutable components of one Fraction; never reachable from guest code. */
    private record FractionState(Object numerator, Object denominator) {}

    /** Whether {@code value} is a genuine canonical Fraction. */
    public static boolean isFraction(Object value) {
        return value instanceof ProtosSealedValue sealed && sealed.belongsTo(FRACTION_FAMILY);
    }

    /** The canonical numerator Integer of a value admitted by {@link #isFraction}. */
    public static Object fractionNumerator(Object fraction) {
        return fractionState(fraction).numerator();
    }

    /** The canonical, always greater than one, denominator Integer of a Fraction. */
    public static Object fractionDenominator(Object fraction) {
        return fractionState(fraction).denominator();
    }

    private static FractionState fractionState(Object fraction) {
        if (!isFraction(fraction)) {
            throw new IllegalArgumentException("value is not a canonical Fraction");
        }
        return (FractionState) ((ProtosSealedValue) fraction).stateFor(FRACTION_FAMILY);
    }

    /**
     * The canonical exact value of {@code numerator / denominator} for two semantic Integers:
     * an Integer when the quotient is integral, otherwise one fresh Fraction owned by
     * {@code prelude}. A zero denominator throws {@link ArithmeticException}. Without a Prelude
     * a result that needs one (a Fraction or a large Integer) answers null, so a primitive fast
     * path keeps its ordinary path.
     */
    public static Object normalizeExactRational(
            Object numerator, Object denominator, ProtosPrelude prelude) {
        requireCurrentInteger(numerator);
        requireCurrentInteger(denominator);
        if (numerator instanceof ProtosIntegerValue a && denominator instanceof ProtosIntegerValue b) {
            long x = a.longValue();
            long y = b.longValue();
            if (y == 0L) {
                throw new ArithmeticException("division by zero");
            }
            if (x != Long.MIN_VALUE && y != Long.MIN_VALUE) {
                long divisor = gcd(Math.abs(x), Math.abs(y));
                long n = x / divisor;
                long d = y / divisor;
                if (d < 0L) {
                    n = -n;
                    d = -d;
                }
                if (d == 1L) {
                    return integer(n);
                }
                return prelude == null
                        ? null
                        : mintFraction(integer(n), integer(d), prelude);
            }
        }
        if (integerSignum(denominator) == 0) {
            throw new ArithmeticException("division by zero");
        }
        return normalizeLargeRational(numerator, denominator, prelude);
    }

    /** D197-C {@code /} of two semantic Integers: the canonical exact quotient. */
    public static Object divideIntegersExactly(Object left, Object right, ProtosPrelude prelude) {
        return normalizeExactRational(left, right, prelude);
    }

    /** The binary64 nearest to a Fraction's exact quotient, ties to even, rounded once. */
    public static double roundFractionToBinary64(Object fraction) {
        FractionState state = fractionState(fraction);
        return ProtosBinary64Rounding.divideExactIntegers(state.numerator(), state.denominator());
    }

    private static long gcd(long a, long b) {
        while (b != 0L) {
            long remainder = a % b;
            a = b;
            b = remainder;
        }
        return a;
    }

    /* Rare arbitrary-precision normalization; BigInteger has no partial-evaluation value. */
    @TruffleBoundary
    private static Object normalizeLargeRational(
            Object numerator, Object denominator, ProtosPrelude prelude) {
        BigInteger n = exactBigInteger(numerator);
        BigInteger d = exactBigInteger(denominator);
        BigInteger divisor = n.gcd(d);
        n = n.divide(divisor);
        d = d.divide(divisor);
        if (d.signum() < 0) {
            n = n.negate();
            d = d.negate();
        }
        if (d.equals(BigInteger.ONE)) {
            return prelude == null && n.bitLength() >= Long.SIZE ? null : integer(n, prelude);
        }
        if (prelude == null) {
            return null;
        }
        return mintFraction(integer(n, prelude), integer(d, prelude), prelude);
    }

    private static Object mintFraction(Object numerator, Object denominator, ProtosPrelude prelude) {
        return ProtosSealedValue.mint(
                FRACTION_FAMILY,
                prelude.fractionPrototype(),
                new FractionState(numerator, denominator));
    }

    /** Whether two canonical Fractions denote the same rational value. */
    public static boolean sameFraction(Object left, Object right) {
        FractionState a = fractionState(left);
        FractionState b = fractionState(right);
        return sameInteger(a.numerator(), b.numerator())
                && sameInteger(a.denominator(), b.denominator());
    }

    /**
     * Exact order of two exact real values, each a semantic Integer or a Fraction: negative,
     * zero or positive. Integer pairs keep their primitive comparison.
     */
    public static int compareExactRationals(Object left, Object right) {
        if (isCurrentInteger(left) && isCurrentInteger(right)) {
            return compareIntegers(left, right);
        }
        return compareRationalsByCrossProduct(left, right);
    }

    /* a/b <=> c/d with positive denominators compares a*d against c*b, without rounding. */
    @TruffleBoundary
    private static int compareRationalsByCrossProduct(Object left, Object right) {
        BigInteger a = exactBigInteger(isFraction(left) ? fractionNumerator(left) : left);
        BigInteger b = isFraction(left)
                ? exactBigInteger(fractionDenominator(left))
                : BigInteger.ONE;
        BigInteger c = exactBigInteger(isFraction(right) ? fractionNumerator(right) : right);
        BigInteger d = isFraction(right)
                ? exactBigInteger(fractionDenominator(right))
                : BigInteger.ONE;
        return a.multiply(d).compareTo(c.multiply(b));
    }

    /*
     * Staged isolation transfer (as Path, I094): the source stage replaces a Fraction with an
     * inert descriptor of exact host scalars, holding no guest object and no source Prelude;
     * the destination stage mints one Fraction with the destination's Fraction prototype.
     */
    private record FractionTransferDescriptor(Object numerator, Object denominator) {}

    /** The inert transfer descriptor of a value admitted by {@link #isFraction}. */
    public static Object fractionTransferDescriptor(Object fraction) {
        FractionState state = fractionState(fraction);
        return new FractionTransferDescriptor(
                hostInteger(state.numerator()), hostInteger(state.denominator()));
    }

    public static boolean isFractionTransferDescriptor(Object value) {
        return value instanceof FractionTransferDescriptor;
    }

    /** One destination Fraction for a descriptor of {@link #fractionTransferDescriptor}. */
    public static Object materializeFractionTransferDescriptor(
            Object descriptor, ProtosPrelude destination) {
        if (!(descriptor instanceof FractionTransferDescriptor inert)) {
            throw new IllegalArgumentException("value is not a Fraction transfer descriptor");
        }
        Objects.requireNonNull(destination, "a Fraction requires its owning Prelude");
        return mintFraction(
                integerFromHost(inert.numerator(), destination),
                integerFromHost(inert.denominator(), destination),
                destination);
    }

    /** Deterministic hash of a Fraction's canonical components. */
    public static int fractionComponentHash(Object fraction) {
        FractionState state = fractionState(fraction);
        return 31 * integerHashCode(state.numerator()) + integerHashCode(state.denominator());
    }

    /**
     * I091 internal primitive carriers: a raw {@code Long} (signed-64 Integer) or
     * {@code Double} (binary64 Float) produced by guarded lowered numeric execution and
     * retained in operand and compact frame storage.
     *
     * <p>Such a carrier is never a guest value. It is unambiguous inside guest frames because
     * no host scalar reaches them unconverted: host executable arguments are admitted as
     * Protos values, and every other foreign value is wrapped. Every frame observer that can
     * expose a binding outside the lowered carrier chain materializes through {@link
     * #guestValue}.
     */
    public static boolean isPrimitiveCarrier(Object value) {
        return value instanceof Long || value instanceof Double;
    }

    /** The guest value denoted by {@code value}; identity for every non-carrier. */
    public static Object guestValue(Object value) {
        if (value instanceof Long small) {
            return new ProtosIntegerValue(small.longValue());
        }
        if (value instanceof Double floating) {
            return new ProtosFloatValue(floating.doubleValue());
        }
        return value;
    }

    /*
     * I091 semantic Integer capabilities for generic clients (I/O, network,
     * collections), so they need not depend on the physical Integer class.
     */

    /** The guest Integer denoting {@code value}. */
    public static Object integer(long value) {
        // A fresh carrier keeps ordinary results eligible for escape analysis.
        return new ProtosIntegerValue(value);
    }

    /** The guest Integer of an unsigned octet {@code 0..255}. */
    public static Object octet(int value) {
        if (value < 0 || value >= Octets.VALUES.length) {
            throw new IllegalArgumentException("octet out of range: " + value);
        }
        return Octets.VALUES[value];
    }

    /*
     * Integer identity, equality and hashing are by value, so the octet Integers that
     * byte-oriented I/O produces per byte can be shared. The holder is initialized, once and
     * safely, only when byte I/O first asks for an octet.
     */
    private static final class Octets {
        static final ProtosIntegerValue[] VALUES = new ProtosIntegerValue[256];

        static {
            for (int octet = 0; octet < VALUES.length; octet++) {
                VALUES[octet] = new ProtosIntegerValue(octet);
            }
        }
    }

    /** Whether {@code value} is a current Integer within the Java {@code int} range. */
    public static boolean isIntegerInIntRange(Object value) {
        return value instanceof ProtosIntegerValue integer && integer.fitsInIntForRuntime();
    }

    /** The exact {@code int} of a value admitted by {@link #isIntegerInIntRange}. */
    public static int exactInt(Object value) {
        if (!isIntegerInIntRange(value)) {
            throw new IllegalArgumentException("value is not an Integer in the int range");
        }
        return ((ProtosIntegerValue) value).intValueExactForRuntime();
    }

    /** Whether {@code value} is a current Integer within the signed-64 range. */
    public static boolean isIntegerInLongRange(Object value) {
        return value instanceof ProtosIntegerValue;
    }

    /** The exact {@code long} of a value admitted by {@link #isIntegerInLongRange}. */
    public static long exactLong(Object value) {
        if (!(value instanceof ProtosIntegerValue integer)) {
            throw new IllegalArgumentException("value is not an Integer in the long range");
        }
        return integer.longValue();
    }

    /** Whether {@code value} is a non-negative current Integer of at most {@code bits} bits. */
    public static boolean isUnsignedIntegerWithin(Object value, int bits) {
        if (value instanceof ProtosIntegerValue integer) {
            return integer.fitsUnsignedBitsForRuntime(bits);
        }
        return value instanceof ProtosLargeIntegerValue large && large.fitsUnsignedBits(bits);
    }

    /**
     * The unsigned big-endian encoding of a non-negative current Integer in exactly
     * {@code width} octets, or {@code null} when {@code value} is not such an Integer.
     */
    public static byte[] unsignedBigEndianOrNull(Object value, int width) {
        if (width < 0 || !isUnsignedIntegerWithin(value, unsignedBitsOfOctets(width))) {
            return null;
        }
        if (value instanceof ProtosIntegerValue integer) {
            return integer.toUnsignedBigEndianForRuntime(width);
        }
        return ((ProtosLargeIntegerValue) value).toUnsignedBigEndian(width);
    }

    /**
     * The bit width of {@code width} octets, saturated at {@link Integer#MAX_VALUE} so that an
     * octet width beyond {@code Integer.MAX_VALUE / 8} never wraps into a different, smaller
     * bit width. Every current Integer that can exist fits the saturated width.
     */
    static int unsignedBitsOfOctets(int width) {
        if (width < 0) {
            throw new IllegalArgumentException("negative unsigned width");
        }
        return width > Integer.MAX_VALUE / Byte.SIZE ? Integer.MAX_VALUE : width * Byte.SIZE;
    }

    /**
     * The guest Integer whose unsigned big-endian encoding is {@code octets}. Only encodings
     * beyond the signed-64 range need {@code prelude}, which mints the large Integer.
     */
    public static Object integerFromUnsignedBigEndian(byte[] octets, ProtosPrelude prelude) {
        return integerFromUnsignedBigEndian(
                octets, prelude == null ? null : prelude.bigIntegerPrototype());
    }

    /**
     * The guest Integer whose unsigned big-endian encoding is {@code octets}; only encodings
     * beyond the signed-64 range need {@code bigIntegerPrototype}, the {@code BigInteger}
     * prototype of the owning Prelude, which mints the large Integer.
     */
    public static Object integerFromUnsignedBigEndian(
            byte[] octets, ProtosObjectValue bigIntegerPrototype) {
        Objects.requireNonNull(octets, "octets");
        int first = 0;
        while (first < octets.length && octets[first] == 0) {
            first++;
        }
        int significant = octets.length - first;
        // The unsigned sign of the leading significant octet decides the signed-64 fit: Java's
        // signed byte comparison would misread 0x80..0xFF as negative and wrap 2^63..2^64-1.
        if (significant < Long.BYTES
                || (significant == Long.BYTES && (octets[first] & 0x80) == 0)) {
            long value = 0L;
            for (int index = first; index < octets.length; index++) {
                value = (value << Byte.SIZE) | (octets[index] & 0xffL);
            }
            return integer(value);
        }
        return integerWithPrototype(unsignedBig(octets), bigIntegerPrototype);
    }

    /** The semantic Integer {@code value} admitted by {@link #isCurrentInteger}, unchanged. */
    public static Object requireCurrentInteger(Object value) {
        if (isCurrentInteger(value)) {
            return value;
        }
        throw new IllegalArgumentException(
                "value is not a current exact-integer family");
    }

    /*
     * I091 / PLAT056 Candidate C exact Integer service. Every exact Integer result is
     * normalized: a value within the signed-64 range is a ProtosIntegerValue, every other value
     * a ProtosLargeIntegerValue minted with the BigInteger prototype of the supplied Prelude
     * (D197-C: a signed-64 value is a SmallInteger, every other value a BigInteger).
     * Without a Prelude, an arithmetic result outside the signed-64 range answers null, so a
     * primitive fast path falls back to its ordinary path, which always supplies one.
     */

    /**
     * The normalized semantic Integer denoting {@code value}. {@code prelude} is consulted only
     * for a value outside the signed-64 range.
     */
    static Object integer(BigInteger value, ProtosPrelude prelude) {
        Objects.requireNonNull(value, "value");
        if (value.bitLength() < Long.SIZE) {
            return integer(value.longValue());
        }
        Objects.requireNonNull(prelude, "a large Integer requires its owning Core prelude");
        return new ProtosLargeIntegerValue(prelude.bigIntegerPrototype(), value);
    }

    /*
     * The normalized semantic Integer denoting {@code value}, minting a large value with the
     * explicit concrete large-family prototype {@code bigIntegerPrototype} (the owning Prelude's
     * {@code BigInteger}, not the common {@code Integer}).
     */
    static Object integerWithPrototype(
            BigInteger value, ProtosObjectValue bigIntegerPrototype) {
        Objects.requireNonNull(value, "value");
        if (value.bitLength() < Long.SIZE) {
            return integer(value.longValue());
        }
        Objects.requireNonNull(
                bigIntegerPrototype, "a large Integer requires its owning BigInteger prototype");
        return new ProtosLargeIntegerValue(bigIntegerPrototype, value);
    }

    /**
     * The exact value of a semantic Integer as a temporary host BigInteger. Package-private: only
     * the exact numeric boundary reads a large Integer's payload; clients use capabilities.
     */
    static BigInteger exactBigInteger(Object integer) {
        if (integer instanceof ProtosIntegerValue small) {
            return BigInteger.valueOf(small.longValue());
        }
        if (integer instanceof ProtosLargeIntegerValue large) {
            return large.exactValue();
        }
        throw new IllegalArgumentException("value is not a current exact-integer family");
    }

    /*
     * I091 host exact-Integer scalar convention, shared by the foreign boundary and literal
     * lowering: a semantic Integer crosses as a Long within the signed-64 range and as a host
     * BigInteger only beyond it. Clients pass such scalars through without inspecting them.
     */

    /** The normalized host scalar of a semantic Integer: a Long, or a BigInteger beyond. */
    public static Object hostInteger(Object integer) {
        if (integer instanceof ProtosIntegerValue small) {
            return small.longValue();
        }
        return large(integer).exactValue();
    }

    /**
     * The normalized host scalar of a host integral {@code scalar} (Byte, Short, Integer, Long,
     * or BigInteger), or {@code null} when {@code scalar} is not host integral.
     */
    public static Object normalizedHostInteger(Object scalar) {
        if (scalar instanceof Long) {
            return scalar;
        }
        if (scalar instanceof Integer || scalar instanceof Short || scalar instanceof Byte) {
            return ((Number) scalar).longValue();
        }
        if (scalar instanceof BigInteger exact) {
            return exact.bitLength() < Long.SIZE ? (Object) exact.longValue() : exact;
        }
        return null;
    }

    /** Whether {@code scalar} is already a normalized host scalar. */
    public static boolean isNormalizedHostInteger(Object scalar) {
        return scalar != null && normalizedHostInteger(scalar) == scalar;
    }

    /**
     * The semantic Integer denoted by a host integral {@code scalar}; {@code prelude} is
     * consulted only for a value outside the signed-64 range.
     */
    public static Object integerFromHost(Object scalar, ProtosPrelude prelude) {
        Object normalized = normalizedHostInteger(scalar);
        if (normalized instanceof Long small) {
            return integer(small.longValue());
        }
        if (normalized == null) {
            throw new IllegalArgumentException("value is not a host integral scalar");
        }
        return integer((BigInteger) normalized, prelude);
    }

    public static int integerSignum(Object integer) {
        if (integer instanceof ProtosIntegerValue small) {
            return small.signumForRuntime();
        }
        return large(integer).signum();
    }

    /** Canonical decimal spelling of a semantic Integer. */
    public static String integerDecimalText(Object integer) {
        if (integer instanceof ProtosIntegerValue small) {
            return small.decimalTextForRuntime();
        }
        return large(integer).decimalText();
    }

    /** Exact mathematical order of two semantic Integers. */
    public static int compareIntegers(Object left, Object right) {
        if (left instanceof ProtosIntegerValue a && right instanceof ProtosIntegerValue b) {
            return Long.compare(a.longValue(), b.longValue());
        }
        /* A large Integer lies beyond every signed-64 value, on the side of its sign. */
        if (left instanceof ProtosIntegerValue) {
            return -large(right).signum();
        }
        if (right instanceof ProtosIntegerValue) {
            return large(left).signum();
        }
        return large(left).compareTo(large(right));
    }

    /** Whether two semantic Integers denote the same value. */
    public static boolean sameInteger(Object left, Object right) {
        if (left instanceof ProtosIntegerValue a && right instanceof ProtosIntegerValue b) {
            return a.longValue() == b.longValue();
        }
        if (left instanceof ProtosLargeIntegerValue a
                && right instanceof ProtosLargeIntegerValue b) {
            return a.sameValue(b);
        }
        return false;
    }

    /** Hash of the exact value, equal to the canonical arbitrary-precision host hash. */
    public static int integerHashCode(Object integer) {
        if (integer instanceof ProtosIntegerValue small) {
            return small.exactHashCodeForRuntime();
        }
        return large(integer).exactHashCode();
    }

    public static Object addIntegers(Object left, Object right, ProtosPrelude prelude) {
        if (left instanceof ProtosIntegerValue a && right instanceof ProtosIntegerValue b) {
            long x = a.longValue();
            long y = b.longValue();
            long sum = x + y;
            if (((x ^ sum) & (y ^ sum)) >= 0L) {
                return integer(sum);
            }
        }
        return bigResult(Operation.ADD, left, right, prelude);
    }

    public static Object subtractIntegers(Object left, Object right, ProtosPrelude prelude) {
        if (left instanceof ProtosIntegerValue a && right instanceof ProtosIntegerValue b) {
            long x = a.longValue();
            long y = b.longValue();
            long difference = x - y;
            if (((x ^ y) & (x ^ difference)) >= 0L) {
                return integer(difference);
            }
        }
        return bigResult(Operation.SUBTRACT, left, right, prelude);
    }

    public static Object multiplyIntegers(Object left, Object right, ProtosPrelude prelude) {
        if (left instanceof ProtosIntegerValue a && right instanceof ProtosIntegerValue b) {
            long x = a.longValue();
            long y = b.longValue();
            long high = Math.multiplyHigh(x, y);
            long low = x * y;
            if ((high == 0L && low >= 0L) || (high == -1L && low < 0L)) {
                return integer(low);
            }
        }
        return bigResult(Operation.MULTIPLY, left, right, prelude);
    }

    /** Truncating quotient; the divisor must not be zero. */
    public static Object quotientIntegers(Object left, Object right, ProtosPrelude prelude) {
        if (left instanceof ProtosIntegerValue a && right instanceof ProtosIntegerValue b) {
            long x = a.longValue();
            long y = b.longValue();
            if (y == 0L) {
                throw new ArithmeticException("BigInteger divide by zero");
            }
            if (x != Long.MIN_VALUE || y != -1L) {
                return integer(x / y);
            }
        } else if (left instanceof ProtosIntegerValue a) {
            return integer(divisionByLargeIsMinusOne(a.longValue(), large(right)) ? -1L : 0L);
        }
        return bigResult(Operation.QUOTIENT, left, right, prelude);
    }

    /** Truncating remainder, signed like the dividend; the divisor must not be zero. */
    public static Object remainderIntegers(Object left, Object right, ProtosPrelude prelude) {
        if (left instanceof ProtosIntegerValue a && right instanceof ProtosIntegerValue b) {
            long y = b.longValue();
            if (y == 0L) {
                throw new ArithmeticException("BigInteger divide by zero");
            }
            return integer(a.longValue() % y);
        }
        if (left instanceof ProtosIntegerValue a) {
            return divisionByLargeIsMinusOne(a.longValue(), large(right)) ? integer(0L) : a;
        }
        return bigResult(Operation.REMAINDER, left, right, prelude);
    }

    private enum Operation {
        ADD,
        SUBTRACT,
        MULTIPLY,
        QUOTIENT,
        REMAINDER
    }

    /*
     * A large divisor lies outside the signed-64 range, so its magnitude is at least 2^63 and
     * every signed-64 dividend truncates to quotient 0 with itself as remainder. The single
     * exception is Long.MIN_VALUE divided by +2^63, whose quotient is -1 with remainder 0.
     */
    @TruffleBoundary
    private static boolean divisionByLargeIsMinusOne(
            long dividend, ProtosLargeIntegerValue divisor) {
        BigInteger exact = divisor.exactValue();
        return dividend == Long.MIN_VALUE
                && exact.signum() > 0
                && exact.bitLength() == Long.SIZE
                && exact.getLowestSetBit() == Long.SIZE - 1;
    }

    /*
     * TEST009-E: arbitrary-precision results (signed-64 overflow or a large operand) are rare
     * and BigInteger arithmetic has no partial-evaluation value.
     */
    @TruffleBoundary
    private static Object bigResult(
            Operation operation, Object left, Object right, ProtosPrelude prelude) {
        Object identity = mixedIdentityResult(operation, left, right);
        if (identity != null) {
            return identity;
        }
        if (operation == Operation.REMAINDER && right instanceof ProtosIntegerValue divisor) {
            // |remainder| < |divisor| <= 2^63, so the result is always a signed-64 Integer.
            return integer(large(left).exactValue()
                    .remainder(BigInteger.valueOf(divisor.longValue()))
                    .longValue());
        }
        if (prelude == null
                && left instanceof ProtosIntegerValue
                && right instanceof ProtosIntegerValue) {
            // Signed-64 overflow always lies outside the signed-64 range.
            return null;
        }
        BigInteger x = exactBigInteger(left);
        BigInteger y = exactBigInteger(right);
        BigInteger result = switch (operation) {
            case ADD -> x.add(y);
            case SUBTRACT -> x.subtract(y);
            case MULTIPLY -> x.multiply(y);
            case QUOTIENT -> x.divide(y);
            case REMAINDER -> x.remainder(y);
        };
        return prelude == null && result.bitLength() >= Long.SIZE
                ? null
                : integer(result, prelude);
    }

    /*
     * Mixed signed-64 / large operations whose result is an operand or a signed-64 constant: an
     * additive or multiplicative identity, a zero factor, or a unit divisor. The large operand
     * is already normalized, so returning it unchanged preserves normalization and its owning
     * prototype. Answers null when arbitrary-precision arithmetic is required.
     */
    private static Object mixedIdentityResult(Operation operation, Object left, Object right) {
        long small;
        boolean smallOnRight;
        if (right instanceof ProtosIntegerValue b && left instanceof ProtosLargeIntegerValue) {
            small = b.longValue();
            smallOnRight = true;
        } else if (left instanceof ProtosIntegerValue a
                && right instanceof ProtosLargeIntegerValue) {
            small = a.longValue();
            smallOnRight = false;
        } else {
            return null;
        }
        Object large = smallOnRight ? left : right;
        return switch (operation) {
            case ADD -> small == 0L ? large : null;
            case SUBTRACT -> small == 0L && smallOnRight ? large : null;
            case MULTIPLY -> small == 0L ? integer(0L) : small == 1L ? large : null;
            case QUOTIENT -> small == 1L && smallOnRight ? large : null;
            case REMAINDER ->
                    smallOnRight && (small == 1L || small == -1L) ? integer(0L) : null;
        };
    }

    private static ProtosLargeIntegerValue large(Object integer) {
        if (integer instanceof ProtosLargeIntegerValue large) {
            return large;
        }
        throw new IllegalArgumentException("value is not a current exact-integer family");
    }

    @TruffleBoundary
    private static BigInteger unsignedBig(byte[] octets) {
        return new BigInteger(1, octets);
    }

    /** The guest Float denoting {@code value}. */
    public static Object floating(double value) {
        return new ProtosFloatValue(value);
    }

    public static double currentFloatValue(Object value) {
        if (value instanceof ProtosFloatValue floating) {
            return floating.value();
        }
        throw new IllegalArgumentException(
                "value is not a current Float family");
    }

    /**
     * I091 / PLAT056 Candidate C: whether {@code value} is a FROZEN ordinary object minted by a
     * rich numeric family. Membership comes only from the minting family, never from slots,
     * delegation or shape, so guest code cannot forge it.
     */
    public static boolean isRichNumericValue(Object value) {
        return value instanceof ProtosSemanticTransferValue minted
                && minted.family() instanceof ProtosRichNumericFamily
                && minted.isFrozen();
    }

    /**
     * Non-overridable value identity of two rich numeric values: the same minting family and the
     * same value of that family. Any other pair answers false.
     */
    public static boolean sameRichNumericIdentity(Object left, Object right) {
        if (!isRichNumericValue(left) || !isRichNumericValue(right)) {
            return false;
        }
        ProtosSemanticTransferValue a = (ProtosSemanticTransferValue) left;
        ProtosSemanticTransferValue b = (ProtosSemanticTransferValue) right;
        return a.family() == b.family()
                && ((ProtosRichNumericFamily) a.family())
                        .sameValue(a.familyState(), b.familyState());
    }

    /** Identity hash of a value admitted by {@link #isRichNumericValue}. */
    public static long richNumericIdentityHash(Object value) {
        if (!isRichNumericValue(value)) {
            throw new IllegalArgumentException("value is not a rich numeric value");
        }
        ProtosSemanticTransferValue minted = (ProtosSemanticTransferValue) value;
        ProtosRichNumericFamily family = (ProtosRichNumericFamily) minted.family();
        return tagged(family.identityTag(), family.valueHash(minted.familyState()));
    }

    /**
     * Current non-overridable numeric value identity, excluding the initial
     * reference-equality shortcut owned by ProtosIdentity.
     */
    public static boolean sameCurrentFamilyIdentity(Object left, Object right) {
        if (isCurrentInteger(left) && isCurrentInteger(right)) {
            return sameInteger(left, right);
        }
        if (left instanceof ProtosFloatValue a
                && right instanceof ProtosFloatValue b) {
            double av = a.value();
            double bv = b.value();
            if (Double.isNaN(av) && Double.isNaN(bv)) {
                return true;
            }
            return Double.doubleToRawLongBits(av)
                    == Double.doubleToRawLongBits(bv);
        }
        if (isFraction(left) && isFraction(right)) {
            return sameFraction(left, right);
        }
        if (ProtosComplexNumericSupport.isComplex(left)
                && ProtosComplexNumericSupport.isComplex(right)) {
            return ProtosComplexNumericSupport.sameComplex(left, right);
        }
        return false;
    }

    /**
     * Current numeric identity hash of a value admitted by {@link #isCurrentNumber};
     * callers retain their generic-object fallback for every other value.
     */
    public static long currentNumericIdentityHash(Object value) {
        if (isCurrentInteger(value)) {
            return tagged(1, integerHashCode(value));
        }
        if (value instanceof ProtosFloatValue floating) {
            double number = floating.value();
            long bits = Double.isNaN(number)
                    ? 0x7ff8000000000000L
                    : Double.doubleToRawLongBits(number);
            return tagged(30, Long.hashCode(bits));
        }
        if (isFraction(value)) {
            return tagged(37, fractionComponentHash(value));
        }
        if (ProtosComplexNumericSupport.isComplex(value)) {
            return tagged(41, ProtosComplexNumericSupport.componentIdentityHash(value));
        }
        throw new IllegalArgumentException("value is not a current numeric family");
    }

    /**
     * Explicit detached copy for today's numeric carriers only, owned by {@code destination}.
     *
     * <p>A signed-64 Integer or a Float is a context-free immutable value. A large Integer is an
     * ordinary guest object delegating to its minting Prelude's BigInteger prototype, so it is
     * rematerialized with the destination's prototype and never shared across isolated domains.
     * A Fraction likewise is rematerialized by value with the destination's Fraction prototype
     * (D197-C); no other sealed value is copied here. A Complex is deliberately absent: it
     * crosses isolation only through the staged descriptor of {@link ProtosComplexNumericSupport}. This method is not called on ordinary arithmetic, lookup, or numeric dispatch paths.
     */
    public static Object copyCurrentNumberOrNull(Object value, ProtosPrelude destination) {
        if (value instanceof ProtosIntegerValue integer) {
            return new ProtosIntegerValue(integer.longValue());
        }
        if (value instanceof ProtosLargeIntegerValue large) {
            return integer(large.exactValue(), destination);
        }
        if (value instanceof ProtosFloatValue floating) {
            return new ProtosFloatValue(floating.value());
        }
        if (isFraction(value)) {
            FractionState state = fractionState(value);
            return mintFraction(
                    copyCurrentNumberOrNull(state.numerator(), destination),
                    copyCurrentNumberOrNull(state.denominator(), destination),
                    Objects.requireNonNull(destination, "a Fraction requires its owning Prelude"));
        }
        return null;
    }

    private static long tagged(int family, int hash) {
        return (((long) family) << 32) ^ Integer.toUnsignedLong(hash);
    }
}
