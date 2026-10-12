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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import org.junit.jupiter.api.Test;

final class ProtosSemanticTransferPayloadTest {
    @Test
    void smallIntegerLeavesRemainLong() {
        ProtosSemanticTransferPayload payload =
                ProtosSemanticTransferPayload.of(
                        ProtosSemanticTransferPayload.integer(42L),
                        ProtosSemanticTransferPayload.integer(
                                new ProtosIntegerValue(-7L)));

        assertInstanceOf(Long.class, payload.get(0));
        assertInstanceOf(Long.class, payload.get(1));
        assertEquals(42L, payload.get(0));
        assertEquals(-7L, payload.get(1));
        assertTrue(payload.isInteger(0));
        assertTrue(payload.isInteger(1));
        assertEquals(42, payload.nonNegativeInt(0));
        assertEquals(-1, payload.nonNegativeInt(1));
        assertEquals(1, payload.integerSignum(0));
        assertEquals(-1, payload.integerSignum(1));
        assertEquals(
                42L,
                ((ProtosIntegerValue) payload.semanticInteger(0, null)).longValue());
    }

    @Test
    void largeLeavesRetainExactMagnitude() {
        BigInteger huge = BigInteger.ONE.shiftLeft(63);
        ProtosSemanticTransferPayload payload =
                ProtosSemanticTransferPayload.of(
                        ProtosSemanticTransferPayload.integer(Long.MAX_VALUE),
                        huge,
                        ProtosSemanticTransferPayload.integer(Long.MIN_VALUE));

        assertInstanceOf(Long.class, payload.get(0));
        assertInstanceOf(BigInteger.class, payload.get(1));
        assertTrue(payload.isInteger(1));
        assertEquals(1, payload.integerSignum(1));
        assertEquals(-1, payload.integerSignum(2));
        assertTrue(payload.integersDifferBy(1, 0, 1L));
        assertFalse(payload.integersDifferBy(1, 0, 2L));
    }

    @Test
    void primitiveDifferenceNeverWraps() {
        ProtosSemanticTransferPayload payload =
                ProtosSemanticTransferPayload.of(
                        ProtosSemanticTransferPayload.integer(Long.MAX_VALUE),
                        ProtosSemanticTransferPayload.integer(-1L),
                        ProtosSemanticTransferPayload.integer(10L),
                        ProtosSemanticTransferPayload.integer(7L));

        assertFalse(payload.integersDifferBy(0, 1, Long.MIN_VALUE));
        assertTrue(payload.integersDifferBy(2, 3, 3L));
        assertFalse(payload.integersDifferBy(2, 3, 4L));
    }

    @Test
    void nonIntegerLeavesAreNotAccidentallyAdmitted() {
        ProtosSemanticTransferPayload payload =
                ProtosSemanticTransferPayload.of("text", 1.0d);
        assertFalse(payload.isInteger(0));
        assertFalse(payload.isInteger(1));
        assertEquals(-1, payload.nonNegativeInt(0));
        assertThrows(IllegalArgumentException.class, () -> payload.integerSignum(0));
        assertThrows(
                IllegalArgumentException.class,
                () -> ProtosSemanticTransferPayload.of(Integer.valueOf(1)));
    }
}
