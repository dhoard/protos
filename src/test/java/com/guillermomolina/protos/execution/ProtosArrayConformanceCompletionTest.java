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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.guillermomolina.protos.runtime.ProtosActivation;
import com.guillermomolina.protos.runtime.ProtosArrayValue;
import com.guillermomolina.protos.runtime.ProtosClosureValue;
import com.guillermomolina.protos.runtime.ProtosCoreErrors;
import com.guillermomolina.protos.runtime.ProtosIntegerValue;
import com.guillermomolina.protos.runtime.ProtosObjectValue;
import com.guillermomolina.protos.runtime.ProtosPrelude;
import com.guillermomolina.protos.runtime.ProtosSignalException;
import java.io.IOException;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Java-side Array coverage retained only where the current test must establish
 * represented lifecycle state directly or inspect host-visible control transfer
 * after a callback signal. Ordinary size/each semantics live in .protos.
 */
class ProtosArrayConformanceCompletionTest {
    @Test
    void representedLifecyclePreservesClosedReplacementFrozenRejectionAndSize()
            throws IOException {
        ProtosPrelude prelude = corePrelude();
        ProtosActivation activation = prelude.newModuleActivation();
        ProtosArrayValue xs =
                prelude.newArray(
                        List.of(
                                new ProtosIntegerValue(1L),
                                new ProtosIntegerValue(2L)));
        activation.context().createLocalSlot("xs", xs);

        assertEquals(String.valueOf("2"), com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerDecimalText(execute(activation, "xs.size()")));

        xs.close();
        assertEquals(String.valueOf("2"), com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerDecimalText(execute(activation, "xs[0] = 2")));
        assertEquals(String.valueOf("2"), com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerDecimalText(xs.indexedAt(0)));
        assertEquals(String.valueOf("2"), com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerDecimalText(execute(activation, "xs.size()")));

        xs.freeze();
        ProtosSignalException signal =
                assertThrows(
                        ProtosSignalException.class,
                        () -> execute(activation, "xs[0] = 3"));
        assertSame(prelude.errorPrototype(), signal.error().parent().orElseThrow());
        assertEquals(String.valueOf("2"), com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerDecimalText(xs.indexedAt(0)));
        assertEquals(String.valueOf("2"), com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerDecimalText(execute(activation, "xs.size()")));
    }

    @Test
    void eachPropagatesExactCallbackFailureAndStopsAtFailingElement()
            throws IOException {
        ProtosPrelude prelude = corePrelude();
        ProtosActivation activation = prelude.newModuleActivation();
        ProtosArrayValue xs =
                prelude.newArray(
                        List.of(
                                new ProtosIntegerValue(1L),
                                new ProtosIntegerValue(2L)));
        List<Object> seen = new ArrayList<>();
        ProtosObjectValue error = ProtosCoreErrors.newError(activation);
        ProtosClosureValue callback =
                ProtosClosureValue.nativeClosure(
                        (callbackActivation, supplied) -> {
                            seen.add(supplied.get(0));
                            throw new ProtosSignalException(error);
                        });
        activation.context().createLocalSlot("xs", xs);
        activation.context().createLocalSlot("callback", callback);

        ProtosSignalException signal =
                assertThrows(
                        ProtosSignalException.class,
                        () -> execute(activation, "xs.each(callback)"));

        assertSame(error, signal.error());
        assertEquals(1, seen.size());
        assertEquals(String.valueOf("1"), com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerDecimalText(seen.get(0)));
    }

    private static Object execute(ProtosActivation activation, String source) {
        return ProtosTestExecutionSupport.evaluate(
                source,
                activation);
    }

    private static ProtosPrelude corePrelude() throws IOException {
        return new ProtosCoreBootstrap().bootstrap(Path.of("protos", "lib", "core"));
    }
}
