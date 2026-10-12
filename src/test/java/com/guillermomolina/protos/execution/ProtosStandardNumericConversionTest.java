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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.guillermomolina.protos.runtime.ProtosBinary64Rounding;
import com.guillermomolina.protos.runtime.ProtosFloatValue;
import com.guillermomolina.protos.runtime.ProtosIntegerValue;
import com.guillermomolina.protos.runtime.ProtosLargeIntegerValue;
import com.guillermomolina.protos.runtime.ProtosPrelude;

import java.math.BigInteger;
import org.junit.jupiter.api.Test;

class ProtosStandardNumericConversionTest {
    // Deliberately Java-side: this tests the representation helper used by
    // numeric conversion/equality bridges, not observable source-level behavior.
    @Test
    void exactIntegralBinary64ExtractionUsesActualBinaryValue() throws java.io.IOException {
        assertEquals((new BigInteger("99999999999999991611392")).toString(), com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerDecimalText(
                        ProtosBinary64Rounding.integralBinary64(
                                1e23,
                                new ProtosCoreBootstrap()
                                        .bootstrap(java.nio.file.Path.of("protos", "lib", "core")))));
        assertEquals(String.valueOf("0"), com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerDecimalText(
                        ProtosBinary64Rounding.integralBinary64(-0.0d, null)));
        assertEquals(String.valueOf("1"), com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerDecimalText(
                        ProtosBinary64Rounding.integralBinary64(1.0d, null)));
        assertEquals(
                null,
                ProtosBinary64Rounding.integralBinary64(1.5d, null));
        assertEquals(
                null,
                ProtosBinary64Rounding.integralBinary64(
                        Double.POSITIVE_INFINITY, null));
        assertEquals(
                null,
                ProtosBinary64Rounding.integralBinary64(Double.NaN, null));
    }

    @Test
    void factoriesNormalizeIntegersAndRoundFloatsExactlyOnce() throws java.io.IOException {
        ProtosPrelude prelude = new ProtosCoreBootstrap()
                .bootstrap(java.nio.file.Path.of("protos", "lib", "core"));
        var toInteger = ProtosStandardNumericConversionProtocol.FactoryKind.INTEGER;
        var toFloat = ProtosStandardNumericConversionProtocol.FactoryKind.FLOAT;

        assertEquals(Long.MIN_VALUE, assertInstanceOf(ProtosIntegerValue.class,
                ProtosStandardNumericConversionProtocol.convert(
                        toInteger, new ProtosFloatValue(-0x1p63), prelude)).longValue());
        assertEquals(1L << 62, assertInstanceOf(ProtosIntegerValue.class,
                ProtosStandardNumericConversionProtocol.convert(
                        toInteger, new ProtosFloatValue(0x1p62), null)).longValue());
        assertEquals(0L, assertInstanceOf(ProtosIntegerValue.class,
                ProtosStandardNumericConversionProtocol.convert(
                        toInteger, new ProtosFloatValue(-0.0d), null)).longValue());
        for (double value : new double[] {0x1p63, -0x1.0000000000001p63, 0x1p1023}) {
            ProtosLargeIntegerValue large = assertInstanceOf(ProtosLargeIntegerValue.class,
                    ProtosStandardNumericConversionProtocol.convert(
                            toInteger, new ProtosFloatValue(value), prelude));
            assertSame(prelude.bigIntegerPrototype(), large.parent().orElseThrow());
            assertEquals(String.valueOf(new java.math.BigDecimal(value).toBigIntegerExact()), com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerDecimalText(large));
        }
        // A large result never degrades into a missing value: without a Prelude it fails.
        assertThrows(NullPointerException.class,
                () -> ProtosStandardNumericConversionProtocol.convert(
                        toInteger, new ProtosFloatValue(0x1p63), null));
        for (double value : new double[] {
                0.5d, -1.5d, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            assertNull(ProtosStandardNumericConversionProtocol.convert(
                    toInteger, new ProtosFloatValue(value), prelude));
        }
        Object integer = new ProtosIntegerValue(7L);
        assertSame(integer,
                ProtosStandardNumericConversionProtocol.convert(toInteger, integer, prelude));
        assertNull(ProtosStandardNumericConversionProtocol.convert(toInteger, "7", prelude));
        assertNull(ProtosStandardNumericConversionProtocol.convert(
                toInteger, Long.valueOf(7L), prelude));

        // Integer -> Float rounds the exact value once, ties to even, at and beyond signed-64.
        BigInteger[] exact = {
            BigInteger.valueOf(Long.MAX_VALUE),
            BigInteger.ONE.shiftLeft(53).add(BigInteger.ONE),
            BigInteger.ONE.shiftLeft(53).add(BigInteger.valueOf(3L)),
            BigInteger.ONE.shiftLeft(100).add(BigInteger.ONE.shiftLeft(47)),
            BigInteger.ONE.shiftLeft(100).add(BigInteger.ONE.shiftLeft(47)).add(BigInteger.ONE),
            BigInteger.ONE.shiftLeft(1024).negate(),
        };
        for (BigInteger value : exact) {
            ProtosFloatValue floating = assertInstanceOf(ProtosFloatValue.class,
                    ProtosStandardNumericConversionProtocol.convert(
                            toFloat, com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerFromHost(value, prelude), prelude));
            assertEquals(new java.math.BigDecimal(value).doubleValue(), floating.value(),
                    value.toString());
        }
        ProtosFloatValue negativeZero = new ProtosFloatValue(-0.0d);
        assertSame(negativeZero,
                ProtosStandardNumericConversionProtocol.convert(toFloat, negativeZero, prelude));
        assertNull(ProtosStandardNumericConversionProtocol.convert(toFloat, "1.0", prelude));
    }
}
