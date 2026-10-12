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
import java.math.BigInteger;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * I091: ProtosIdentity and its identity hash are by numeric value and family, independent of the
 * physical carrier (signed-64 or large) and of the Java wrapper object.
 */
final class ProtosI091IdentityRepresentationTest {
    private static final Path CORE = Path.of("protos", "lib", "core");
    private static final BigInteger HUGE = BigInteger.ONE.shiftLeft(1000).add(BigInteger.ONE);

    @Test
    void independentlyMintedLargeIntegersAreIdenticalWithAWrapperIndependentHash() {
        Object first = com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerWithPrototype(HUGE, com.guillermomolina.protos.runtime.ProtosObjectValue.rootObject());
        Object second = com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerWithPrototype(new BigInteger(HUGE.toString()), com.guillermomolina.protos.runtime.ProtosObjectValue.rootObject());

        assertNotSame(first, second);
        assertTrue(ProtosIdentity.identical(first, second));
        assertTrue(ProtosIdentity.identical(second, first));
        assertEquals(ProtosIdentity.identityHash(first), ProtosIdentity.identityHash(second));
        assertEquals(
                ProtosNumericHashKey.fromIdentity(first), ProtosNumericHashKey.fromIdentity(second));
    }

    @Test
    void largeIntegersSharingLowBitsAreDistinct() {
        BigInteger low = BigInteger.valueOf(5);
        Object a = com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerWithPrototype(BigInteger.ONE.shiftLeft(64).add(low), com.guillermomolina.protos.runtime.ProtosObjectValue.rootObject());
        Object b = com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerWithPrototype(BigInteger.ONE.shiftLeft(65).add(low), com.guillermomolina.protos.runtime.ProtosObjectValue.rootObject());
        Object negated = com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerWithPrototype(BigInteger.ONE.shiftLeft(64).add(low).negate(), com.guillermomolina.protos.runtime.ProtosObjectValue.rootObject());

        assertFalse(ProtosIdentity.identical(a, b));
        assertFalse(ProtosIdentity.identical(a, negated));
        assertFalse(ProtosIdentity.identical(a, new ProtosIntegerValue(5)));
    }

    @Test
    void identityAndHashSurviveRematerializationIntoAnotherPrelude() throws Exception {
        ProtosPrelude source = new ProtosCoreBootstrap().bootstrap(CORE);
        ProtosPrelude destination = new ProtosCoreBootstrap().bootstrap(CORE);
        Object original = com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerFromHost(HUGE, source);

        Object copy = ProtosNumericValueSupport.copyCurrentNumberOrNull(original, destination);

        assertNotSame(original, copy);
        assertSame(
                destination.bigIntegerPrototype(),
                ((ProtosObjectValue) copy).parent().orElseThrow());
        assertTrue(ProtosIdentity.identical(original, copy));
        assertEquals(ProtosIdentity.identityHash(original), ProtosIdentity.identityHash(copy));
    }

    @Test
    void integerAndFloatKeepDistinctIdentityFamilies() {
        Object one = new ProtosIntegerValue(1);
        Object oneFloat = new ProtosFloatValue(1.0d);

        assertFalse(ProtosIdentity.identical(one, oneFloat));
        assertFalse(ProtosIdentity.identical(oneFloat, one));
        assertNotEquals(ProtosIdentity.identityHash(one), ProtosIdentity.identityHash(oneFloat));
        Object twoTo64 = com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerWithPrototype(BigInteger.ONE.shiftLeft(64), com.guillermomolina.protos.runtime.ProtosObjectValue.rootObject());
        assertFalse(
                ProtosIdentity.identical(twoTo64, new ProtosFloatValue(0x1p64)));
    }

    @Test
    void nanIsOneIdentityAcrossPayloadsWhileSignedZerosStayDistinct() {
        Object quiet = new ProtosFloatValue(Double.longBitsToDouble(0x7ff8000000000000L));
        Object payload = new ProtosFloatValue(Double.longBitsToDouble(0x7ff8000000000123L));
        Object positiveZero = new ProtosFloatValue(0.0d);
        Object negativeZero = new ProtosFloatValue(-0.0d);

        assertTrue(ProtosIdentity.identical(quiet, payload));
        assertEquals(ProtosIdentity.identityHash(quiet), ProtosIdentity.identityHash(payload));
        assertFalse(ProtosIdentity.identical(positiveZero, negativeZero));
        assertTrue(ProtosIdentity.identical(negativeZero, new ProtosFloatValue(-0.0d)));
    }

    @Test
    void hostNumericObjectsDoNotAcquireGuestValueIdentity() {
        Object[][] pairs = {
            {Long.valueOf(1_000_000L), Long.valueOf(1_000_000L)},
            {Double.valueOf(1.5d), Double.valueOf(1.5d)},
            {BigInteger.ONE.shiftLeft(64), BigInteger.ONE.shiftLeft(64)},
        };
        for (Object[] pair : pairs) {
            assertNotSame(pair[0], pair[1]);
            assertFalse(ProtosIdentity.identical(pair[0], pair[1]));
            assertFalse(ProtosNumericValueSupport.isCurrentNumber(pair[0]));
        }
        assertFalse(ProtosIdentity.identical(Long.valueOf(1L), new ProtosIntegerValue(1)));
        assertFalse(
                ProtosIdentity.identical(
                        BigInteger.ONE.shiftLeft(64),
                        com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerWithPrototype(BigInteger.ONE.shiftLeft(64), com.guillermomolina.protos.runtime.ProtosObjectValue.rootObject())));
    }

    @Test
    void stringIdentityHashIsUnchanged() {
        ProtosStringValue text = new ProtosStringValue("protos");
        long expected = (31L << 32) ^ Integer.toUnsignedLong("protos".hashCode());

        assertEquals(expected, ProtosIdentity.identityHash(text));
        assertTrue(ProtosIdentity.identical(text, new ProtosStringValue("protos")));
    }
}
