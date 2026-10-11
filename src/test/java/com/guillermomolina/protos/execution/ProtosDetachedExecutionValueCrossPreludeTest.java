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
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.guillermomolina.protos.runtime.ProtosActivation;
import com.guillermomolina.protos.runtime.ProtosClosureValue;
import com.guillermomolina.protos.runtime.ProtosCoreErrors;
import com.guillermomolina.protos.runtime.ProtosFloatValue;
import com.guillermomolina.protos.runtime.ProtosIdentity;
import com.guillermomolina.protos.runtime.ProtosIntegerValue;
import com.guillermomolina.protos.runtime.ProtosNumericValueSupport;
import com.guillermomolina.protos.runtime.ProtosObjectValue;
import com.guillermomolina.protos.runtime.ProtosPrelude;
import com.guillermomolina.protos.runtime.ProtosSignalException;
import com.guillermomolina.protos.runtime.ProtosTestIntegers;
import java.math.BigInteger;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

final class ProtosDetachedExecutionValueCrossPreludeTest {
    private static final Path CORE = Path.of("protos", "lib", "core");
    private static final Path STANDARD_LIBRARY = Path.of("protos", "lib");

    @Test
    void genericErrorOccurrenceGetsDestinationErrorPrototype() throws Exception {
        Pair pair = pair();

        ProtosObjectValue sourceError = ProtosCoreErrors.newError(pair.sourceActivation);
        ProtosObjectValue copied =
                (ProtosObjectValue)
                        ProtosDetachedExecutionValue.snapshot(
                                sourceError,
                                pair.sourcePrelude,
                                pair.destinationActivation);

        assertNotSame(sourceError, copied);
        assertSame(pair.destinationPrelude.errorPrototype(), copied.parent().orElseThrow());
        assertNotSame(pair.sourcePrelude.errorPrototype(), copied.parent().orElseThrow());
    }

    @Test
    void namedStandardErrorOccurrenceKeepsCategoryInDestinationPrelude() throws Exception {
        Pair pair = pair();

        ProtosObjectValue sourceError =
                ProtosCoreErrors.newOccurrence(
                        pair.sourceActivation,
                        ProtosCoreErrors.StandardError.SLOT_NOT_FOUND);
        ProtosObjectValue copied =
                (ProtosObjectValue)
                        ProtosDetachedExecutionValue.snapshot(
                                sourceError,
                                pair.sourcePrelude,
                                pair.destinationActivation);

        assertNotSame(sourceError, copied);
        assertSame(
                ProtosCoreErrors.prototype(
                        pair.destinationActivation,
                        ProtosCoreErrors.StandardError.SLOT_NOT_FOUND),
                copied.parent().orElseThrow());
        assertNotSame(sourceError.parent().orElseThrow(), copied.parent().orElseThrow());
    }

    @Test
    void explicitStandardErrorPrototypeMapsToDestinationPrototype() throws Exception {
        Pair pair = pair();

        Object copied =
                ProtosDetachedExecutionValue.snapshot(
                        pair.sourcePrelude.errorPrototype(),
                        pair.sourcePrelude,
                        pair.destinationActivation);

        assertSame(pair.destinationPrelude.errorPrototype(), copied);
        assertNotSame(pair.sourcePrelude.errorPrototype(), copied);
    }

    @Test
    void integersAndFloatsCrossPreludesExactlyWithDestinationOwnedLargeIntegers()
            throws Exception {
        Pair pair = pair();
        BigInteger huge = BigInteger.ONE.shiftLeft(1000).add(BigInteger.ONE);
        Object shared = ProtosTestIntegers.integer(huge, pair.sourcePrelude);
        Object twin = ProtosTestIntegers.integer(huge, pair.sourcePrelude);
        Object positive =
                ProtosTestIntegers.integer(BigInteger.ONE.shiftLeft(64), pair.sourcePrelude);
        Object negative =
                ProtosTestIntegers.integer(
                        BigInteger.ONE.shiftLeft(64).negate(), pair.sourcePrelude);
        long quietNaN = 0x7ff8000000000001L;
        ProtosObjectValue inner = new ProtosObjectValue(ProtosObjectValue.rootObject());
        inner.createLocalSlot("shared", shared);
        inner.createLocalSlot("twin", twin);
        inner.freeze();
        ProtosObjectValue graph = new ProtosObjectValue(ProtosObjectValue.rootObject());
        graph.createLocalSlot("max", new ProtosIntegerValue(Long.MAX_VALUE));
        graph.createLocalSlot("min", new ProtosIntegerValue(Long.MIN_VALUE));
        graph.createLocalSlot("positive", positive);
        graph.createLocalSlot("negative", negative);
        graph.createLocalSlot("shared", shared);
        graph.createLocalSlot("inner", inner);
        graph.createLocalSlot("nan", new ProtosFloatValue(Double.longBitsToDouble(quietNaN)));
        graph.createLocalSlot("infinity", new ProtosFloatValue(Double.NEGATIVE_INFINITY));
        graph.createLocalSlot("negativeZero", new ProtosFloatValue(-0.0d));

        ProtosObjectValue copied =
                assertInstanceOf(
                        ProtosObjectValue.class,
                        ProtosDetachedExecutionValue.snapshot(
                                graph, pair.sourcePrelude, pair.destinationActivation));
        ProtosObjectValue copiedInner =
                assertInstanceOf(ProtosObjectValue.class, slot(copied, "inner"));

        assertEquals(BigInteger.valueOf(Long.MAX_VALUE), ProtosTestIntegers.exact(slot(copied, "max")));
        assertEquals(BigInteger.valueOf(Long.MIN_VALUE), ProtosTestIntegers.exact(slot(copied, "min")));
        assertTrue(ProtosNumericValueSupport.isIntegerInLongRange(slot(copied, "max")));
        assertEquals(BigInteger.ONE.shiftLeft(64), ProtosTestIntegers.exact(slot(copied, "positive")));
        assertEquals(
                BigInteger.ONE.shiftLeft(64).negate(),
                ProtosTestIntegers.exact(slot(copied, "negative")));
        for (String name : new String[] {"positive", "negative", "shared"}) {
            ProtosObjectValue large = assertInstanceOf(ProtosObjectValue.class, slot(copied, name));
            assertTrue(ProtosNumericValueSupport.isLargeInteger(large));
            assertTrue(large.isFrozen());
            assertNotSame(slot(graph, name), large);
            assertSame(pair.destinationPrelude.bigIntegerPrototype(), large.parent().orElseThrow());
        }
        assertSame(slot(copied, "shared"), slot(copiedInner, "shared"));
        assertNotSame(slot(copied, "shared"), slot(copiedInner, "twin"));
        assertTrue(ProtosIdentity.identical(slot(copied, "shared"), slot(copiedInner, "twin")));
        assertEquals(huge, ProtosTestIntegers.exact(slot(copiedInner, "twin")));
        assertTrue(copiedInner.isFrozen());
        assertEquals(quietNaN, rawBits(slot(copied, "nan")));
        assertEquals(
                Double.doubleToRawLongBits(Double.NEGATIVE_INFINITY),
                rawBits(slot(copied, "infinity")));
        assertEquals(Double.doubleToRawLongBits(-0.0d), rawBits(slot(copied, "negativeZero")));
    }

    @Test
    void hostNumericScalarsAndClosuresBesideNumbersAreNotTransferable() throws Exception {
        Pair pair = pair();
        Object large = ProtosTestIntegers.integer(BigInteger.ONE.shiftLeft(64), pair.sourcePrelude);
        ProtosObjectValue graph = new ProtosObjectValue(ProtosObjectValue.rootObject());
        graph.createLocalSlot("large", large);
        graph.createLocalSlot("code", ProtosClosureValue.nativeClosure((a, x) -> large));

        for (Object value :
                new Object[] {BigInteger.ONE.shiftLeft(64), Long.valueOf(1L), Double.valueOf(1.0d), graph}) {
            ProtosSignalException signal =
                    assertThrows(
                            ProtosSignalException.class,
                            () ->
                                    ProtosDetachedExecutionValue.snapshot(
                                            value, pair.sourcePrelude, pair.destinationActivation));
            assertSame(
                    ProtosCoreErrors.prototype(
                            pair.destinationActivation,
                            ProtosCoreErrors.StandardError.NON_TRANSFERABLE_VALUE),
                    signal.error().parent().orElseThrow());
        }
    }

    private static Object slot(ProtosObjectValue object, String name) {
        return object.readLocalSlot(name).orElseThrow();
    }

    private static long rawBits(Object value) {
        return Double.doubleToRawLongBits(
                assertInstanceOf(ProtosFloatValue.class, value).value());
    }

    private static Pair pair() throws Exception {
        ProtosPrelude sourcePrelude =
                new ProtosCoreBootstrap()
                        .bootstrap(
                                CORE,
                                new ProtosStandardLibraryModuleResolver(STANDARD_LIBRARY));
        ProtosPrelude destinationPrelude =
                new ProtosCoreBootstrap()
                        .bootstrap(
                                CORE,
                                new ProtosStandardLibraryModuleResolver(STANDARD_LIBRARY));
        return new Pair(
                sourcePrelude,
                sourcePrelude.newModuleActivation(),
                destinationPrelude,
                destinationPrelude.newModuleActivation());
    }

    private record Pair(
            ProtosPrelude sourcePrelude,
            ProtosActivation sourceActivation,
            ProtosPrelude destinationPrelude,
            ProtosActivation destinationActivation) {}
}
