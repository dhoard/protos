package com.guillermomolina.protos.spi.foreign;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigInteger;
import org.junit.jupiter.api.Test;

final class ProtosForeignValueClassTest {

    @Test
    void boundedBigIntegerIsClassifiedWithASignedLongScalar() {
        long[] values = {
            Long.MIN_VALUE,
            Long.MIN_VALUE + 1,
            -1L,
            0L,
            1L,
            42L,
            Long.MAX_VALUE - 1,
            Long.MAX_VALUE
        };

        for (long value : values) {
            ProtosForeignValueClass descriptor =
                    ProtosForeignValueClass.integral(BigInteger.valueOf(value));

            assertSame(ProtosForeignValueClass.Kind.INTEGER, descriptor.kind());

            Long scalar = assertInstanceOf(Long.class, descriptor.scalar());
            assertEquals(value, scalar.longValue());
        }
    }

    @Test
    void genuinelyLargeIntegerPreservesExactHostPayload() {
        BigInteger positive = BigInteger.ONE.shiftLeft(63);
        BigInteger negative = BigInteger.valueOf(Long.MIN_VALUE)
                .subtract(BigInteger.ONE);
        BigInteger huge = BigInteger.ONE.shiftLeft(200).negate();

        for (BigInteger value : new BigInteger[] {
                positive, negative, huge}) {

            ProtosForeignValueClass descriptor =
                    ProtosForeignValueClass.integral(value);

            assertSame(ProtosForeignValueClass.Kind.INTEGER, descriptor.kind());
            assertSame(value, descriptor.scalar());
        }
    }

    @Test
    void directLongEntryAndNullContractRemainUnchanged() {
        ProtosForeignValueClass descriptor =
                ProtosForeignValueClass.integral(17L);

        assertEquals(17L, assertInstanceOf(Long.class, descriptor.scalar()));

        assertThrows(
                NullPointerException.class,
                () -> ProtosForeignValueClass.integral((BigInteger) null));
    }
}
