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

import static org.junit.jupiter.api.Assertions.*;

import com.guillermomolina.protos.execution.ProtosCoreBootstrap;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** I091-B1: current numeric semantics and transfer boundary regressions. */
final class ProtosNumericValueSupportTest {

    @Test
    void onlyCurrentGuestNumericCarriersAreAdmitted() {
        assertTrue(ProtosNumericValueSupport.isCurrentInteger(
                new ProtosIntegerValue(42L)));
        assertTrue(ProtosNumericValueSupport.isCurrentFloat(
                new ProtosFloatValue(42.0)));
        assertTrue(ProtosNumericValueSupport.isCurrentNumber(
                new ProtosIntegerValue(42L)));

        assertFalse(ProtosNumericValueSupport.isCurrentNumber(42L));
        assertFalse(ProtosNumericValueSupport.isCurrentNumber(42.0));
        assertFalse(ProtosNumericValueSupport.isCurrentNumber(BigInteger.TEN));
        assertFalse(ProtosNumericValueSupport.isCurrentNumber(
                new ProtosObjectValue(ProtosObjectValue.rootObject())));

        assertThrows(
                IllegalArgumentException.class,
                () -> ProtosNumericValueSupport.requireCurrentInteger(42L));
        assertThrows(
                IllegalArgumentException.class,
                () -> ProtosNumericValueSupport.currentFloatValue(42.0));
    }

    @Test
    void currentNumericValueIdentityAndHashAreUnchanged() {
        ProtosIntegerValue first = new ProtosIntegerValue(42L);
        ProtosIntegerValue twin =
                new ProtosIntegerValue(42L);
        ProtosIntegerValue other = new ProtosIntegerValue(43L);

        assertTrue(ProtosNumericValueSupport.sameCurrentFamilyIdentity(first, twin));
        assertFalse(ProtosNumericValueSupport.sameCurrentFamilyIdentity(first, other));
        assertTrue(ProtosIdentity.identical(first, twin));
        assertEquals(
                ProtosNumericValueSupport.currentNumericIdentityHash(first),
                ProtosIdentity.identityHash(first));
        assertEquals(
                ProtosIdentity.identityHash(first),
                ProtosIdentity.identityHash(twin));

        ProtosFloatValue positiveZero = new ProtosFloatValue(0.0d);
        ProtosFloatValue negativeZero = new ProtosFloatValue(-0.0d);
        ProtosFloatValue nanOne = new ProtosFloatValue(Double.NaN);
        ProtosFloatValue nanTwo =
                new ProtosFloatValue(
                        Double.longBitsToDouble(0x7ff0000000000001L));

        assertFalse(ProtosIdentity.identical(positiveZero, negativeZero));
        assertNotEquals(
                ProtosIdentity.identityHash(positiveZero),
                ProtosIdentity.identityHash(negativeZero));
        assertTrue(ProtosIdentity.identical(nanOne, nanTwo));
        assertEquals(
                ProtosIdentity.identityHash(nanOne),
                ProtosIdentity.identityHash(nanTwo));

        assertFalse(ProtosIdentity.identical(first, new ProtosFloatValue(42.0)));
        assertThrows(
                IllegalArgumentException.class,
                () -> ProtosNumericValueSupport.currentNumericIdentityHash(
                        new ProtosObjectValue(ProtosObjectValue.rootObject())));
    }

    @Test
    void detachedNumberCopiesPreserveExactValuesAndIeeeBits() throws IOException {
        ProtosPrelude destination = new ProtosCoreBootstrap().bootstrap(Path.of("protos", "lib", "core"));
        ProtosIntegerValue small = new ProtosIntegerValue(Long.MIN_VALUE);
        Object large = com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerWithPrototype(BigInteger.ONE.shiftLeft(130), com.guillermomolina.protos.runtime.ProtosObjectValue.rootObject());
        ProtosFloatValue negativeZero = new ProtosFloatValue(-0.0d);

        ProtosIntegerValue smallCopy = assertInstanceOf(
                ProtosIntegerValue.class,
                ProtosNumericValueSupport.copyCurrentNumberOrNull(small, destination));
        ProtosLargeIntegerValue largeCopy = assertInstanceOf(
                ProtosLargeIntegerValue.class,
                ProtosNumericValueSupport.copyCurrentNumberOrNull(large, destination));
        ProtosFloatValue floatCopy = assertInstanceOf(
                ProtosFloatValue.class,
                ProtosNumericValueSupport.copyCurrentNumberOrNull(negativeZero, destination));

        assertNotSame(small, smallCopy);
        assertNotSame(large, largeCopy);
        assertNotSame(negativeZero, floatCopy);

        assertEquals(Long.MIN_VALUE, smallCopy.longValue());
        assertEquals(com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerDecimalText(large), com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerDecimalText(largeCopy));
        // I091: a large Integer is rematerialized with the destination's BigInteger prototype.
        assertSame(destination.bigIntegerPrototype(), largeCopy.parent().orElseThrow());
        assertTrue(largeCopy.isFrozen());
        assertTrue(ProtosIdentity.identical(large, largeCopy));
        assertEquals(ProtosIdentity.identityHash(large), ProtosIdentity.identityHash(largeCopy));
        assertEquals(
                Double.doubleToRawLongBits(negativeZero.value()),
                Double.doubleToRawLongBits(floatCopy.value()));

        assertNull(ProtosNumericValueSupport.copyCurrentNumberOrNull(12L, destination));
        assertNull(ProtosNumericValueSupport.copyCurrentNumberOrNull(
                ProtosBooleanValue.TRUE, destination));
    }
}
