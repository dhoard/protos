package com.guillermomolina.protos.execution;

import static org.junit.jupiter.api.Assertions.*;

import com.guillermomolina.protos.runtime.*;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

final class ProtosI091PackageNumericBoundaryTest {

    @Test
    void semanticNumericValuesAreNotPackageOrdinaryObjects()
            throws Exception {
        ProtosPrelude prelude = new ProtosCoreBootstrap()
                .bootstrap(Path.of("protos", "lib", "core"));

        Object small = new ProtosIntegerValue(7L);
        Object large = ProtosNumericValueSupport.integerFromHost(
                BigInteger.ONE.shiftLeft(80), prelude);
        Object floating = new ProtosFloatValue(1.5d);

        Object fraction = ProtosNumericValueSupport.normalizeExactRational(
                new ProtosIntegerValue(1L),
                new ProtosIntegerValue(3L),
                prelude);

        Object complex = ProtosComplexNumericSupport.normalize(
                new ProtosIntegerValue(2L),
                new ProtosFloatValue(-0.0d),
                prelude);

        for (Object number : List.of(
                small, large, floating, fraction, complex)) {
            assertTrue(ProtosNumericValueSupport.isCurrentNumber(number));

            assertThrows(
                    IOException.class,
                    () -> ProtosPackageExecutionPlanAdapter.requireObject(
                            number, "plan"));
        }
    }

    @Test
    void ordinaryObjectDelegatingToNumericParentRemainsOrdinary()
            throws Exception {
        ProtosPrelude prelude = new ProtosCoreBootstrap()
                .bootstrap(Path.of("protos", "lib", "core"));

        Object fraction = ProtosNumericValueSupport.normalizeExactRational(
                new ProtosIntegerValue(1L),
                new ProtosIntegerValue(2L),
                prelude);

        ProtosObjectValue ordinary = new ProtosObjectValue(fraction);
        ordinary.createLocalSlot(
                "generation", new ProtosIntegerValue(1L));

        assertFalse(ProtosNumericValueSupport.isCurrentNumber(ordinary));
        assertSame(fraction, ordinary.parent().orElseThrow());

        assertSame(
                ordinary,
                ProtosPackageExecutionPlanAdapter.requireObject(
                        ordinary, "plan"));
    }
}
