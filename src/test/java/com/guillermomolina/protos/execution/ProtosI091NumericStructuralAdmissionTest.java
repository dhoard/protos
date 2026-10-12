package com.guillermomolina.protos.execution;

import static org.junit.jupiter.api.Assertions.*;

import com.guillermomolina.protos.runtime.*;
import java.math.BigInteger;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

final class ProtosI091NumericStructuralAdmissionTest {

    @Test
    void semanticNumbersAreNotOrdinaryStructuralTargets()
            throws Exception {
        ProtosPrelude prelude =
                new ProtosCoreBootstrap().bootstrap(
                        Path.of("protos", "lib", "core"));

        ProtosActivation activation = prelude.newModuleActivation();

        Object small = new ProtosIntegerValue(7L);
        Object large = ProtosNumericValueSupport.integerFromHost(
                BigInteger.ONE.shiftLeft(90), prelude);
        Object floating = new ProtosFloatValue(-0.0d);

        Object fraction = ProtosNumericValueSupport.normalizeExactRational(
                new ProtosIntegerValue(1L),
                new ProtosIntegerValue(2L),
                prelude);

        Object complex = ProtosComplexNumericSupport.normalize(
                new ProtosIntegerValue(3L),
                new ProtosFloatValue(-0.0d),
                prelude);

        var reserved = new ProtosBytecodeRootNode.ComposeReservedNames(
                List.of());

        for (Object number : List.of(
                small, large, floating, fraction, complex)) {

            assertTrue(
                    ProtosNumericValueSupport.isCurrentNumber(number));

            assertThrows(
                    ProtosSignalException.class,
                    () -> ProtosBytecodeRootNode
                            .RequireObjectMutationTarget
                            .perform(activation, number));

            assertThrows(
                    ProtosSignalException.class,
                    () -> ProtosBytecodeRootNode
                            .ComposeLocalSlots
                            .perform(activation, number, reserved));
        }
    }

    @Test
    void ordinaryObjectsDelegatingToNumbersRetainStructuralRights()
            throws Exception {
        ProtosPrelude prelude =
                new ProtosCoreBootstrap().bootstrap(
                        Path.of("protos", "lib", "core"));

        ProtosActivation activation = prelude.newModuleActivation();

        Object fraction = ProtosNumericValueSupport.normalizeExactRational(
                new ProtosIntegerValue(1L),
                new ProtosIntegerValue(2L),
                prelude);

        ProtosObjectValue ordinary =
                new ProtosObjectValue((ProtosObjectValue) fraction);

        Object marker = new ProtosIntegerValue(17L);
        ordinary.createLocalSlot("marker", marker);

        assertFalse(ProtosNumericValueSupport.isCurrentNumber(ordinary));
        assertSame(fraction, ordinary.parent().orElseThrow());

        assertSame(
                ordinary,
                ProtosBytecodeRootNode.RequireObjectMutationTarget.perform(
                        activation, ordinary));

        var reserved = new ProtosBytecodeRootNode.ComposeReservedNames(
                List.of());

        assertSame(
                activation.context(),
                ProtosBytecodeRootNode.ComposeLocalSlots.perform(
                        activation, ordinary, reserved));

        assertSame(
                marker,
                activation.context().readLocalSlot("marker").orElseThrow());

        assertFalse(ProtosNumericValueSupport.isCurrentNumber(
                activation.context()));
    }
}
