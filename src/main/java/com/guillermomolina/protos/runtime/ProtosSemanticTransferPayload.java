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

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Inert, immutable semantic payload of a {@link ProtosSemanticTransferFamily} value (PLAT051).
 *
 * <p>A payload is an ordered acyclic tree whose leaves are host scalars only: {@link String},
 * {@link Long}, {@link BigInteger}, {@link Boolean} and {@link Double}; inner nodes are nested payloads. It can
 * hold no Protos runtime value, so no Closure, prototype, execution state or authority can cross
 * through it, and a future Process transport could encode the same logical content. It defines
 * no wire format.
 */
public final class ProtosSemanticTransferPayload {
    private final List<Object> elements;

    private ProtosSemanticTransferPayload(List<Object> elements) {
        this.elements = elements;
    }

    /** Builds a payload, rejecting any element that is not an inert leaf or nested payload. */
    public static ProtosSemanticTransferPayload of(Object... elements) {
        ArrayList<Object> copy = new ArrayList<>(elements.length);
        for (Object element : elements) {
            if (!(element instanceof String
                    || element instanceof Long
                    || element instanceof BigInteger
                    || element instanceof Boolean
                    || element instanceof Double
                    || element instanceof ProtosSemanticTransferPayload)) {
                throw new IllegalArgumentException("semantic transfer payload element is not inert");
            }
            // I091 / PLAT056-C: preserve the canonical inert
            // Integer representation even when a family supplies a
            // host BigInteger directly instead of using integer(...).
            // Signed-64 values become Long; genuinely large values
            // retain their exact BigInteger magnitude.
            copy.add(
                    element instanceof BigInteger
                            ? ProtosNumericValueSupport.normalizedHostInteger(element)
                            : element);
        }
        return new ProtosSemanticTransferPayload(Collections.unmodifiableList(copy));
    }

    public int size() {
        return elements.size();
    }

    public Object get(int index) {
        return elements.get(index);
    }

    /*
     * Exact Integer leaves. The leaf encoding stays private to the payload: families build and
     * read Integer leaves through these operations, never through the host representation.
     */

    /** The Integer leaf denoting {@code value}. */
    public static Object integer(long value) {
        return value;
    }

    /** The Integer leaf denoting the semantic Integer {@code integer}. */
    public static Object integer(Object integer) {
        return ProtosNumericValueSupport.hostInteger(integer);
    }

    /** Whether the element at {@code index} is an Integer leaf. */
    public boolean isInteger(int index) {
        Object value = elements.get(index);
        return value instanceof Long || value instanceof BigInteger;
    }

    /**
     * The value of the Integer leaf at {@code index} when it lies in [0, Integer.MAX_VALUE], or
     * -1 for any other leaf value or element.
     */
    public int nonNegativeInt(int index) {
        Object value = elements.get(index);
        if (value instanceof Long small) {
            return small >= 0L && small <= Integer.MAX_VALUE
                    ? small.intValue()
                    : -1;
        }
        // Every remaining BigInteger is outside signed-64,
        // so none can be a non-negative int.
        return -1;
    }

    /** The sign of the Integer leaf at {@code index}. */
    public int integerSignum(int index) {
        Object value = leaf(index);
        return value instanceof Long small
                ? Long.compare(small, 0L)
                : ((BigInteger) value).signum();
    }

    /**
     * Whether the Integer leaves at {@code minuend} and {@code subtrahend} differ by exactly
     * {@code difference}.
     */
    public boolean integersDifferBy(int minuend, int subtrahend, long difference) {
        Object left = leaf(minuend);
        Object right = leaf(subtrahend);
        if (left instanceof Long x && right instanceof Long y) {
            long result = x - y;
            return ((x ^ y) & (x ^ result)) >= 0L
                    && result == difference;
        }
        BigInteger x = left instanceof Long small
                ? BigInteger.valueOf(small)
                : (BigInteger) left;
        BigInteger y = right instanceof Long small
                ? BigInteger.valueOf(small)
                : (BigInteger) right;
        return x.subtract(y).equals(BigInteger.valueOf(difference));
    }

    /**
     * The semantic Integer denoted by the Integer leaf at {@code index}; {@code prelude} is
     * consulted only for a value outside the signed-64 range.
     */
    public Object semanticInteger(int index, ProtosPrelude prelude) {
        return ProtosNumericValueSupport.integerFromHost(leaf(index), prelude);
    }

    private Object leaf(int index) {
        Object value = elements.get(index);
        if (!(value instanceof Long || value instanceof BigInteger)) {
            throw new IllegalArgumentException("payload element is not an Integer leaf");
        }
        return value;
    }
}
