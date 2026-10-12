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
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.guillermomolina.protos.runtime.ProtosActivation;
import com.guillermomolina.protos.runtime.ProtosFloatValue;
import com.guillermomolina.protos.runtime.ProtosIntegerValue;
import com.guillermomolina.protos.runtime.ProtosNumericValueSupport;
import com.guillermomolina.protos.runtime.ProtosObjectValue;
import com.guillermomolina.protos.runtime.ProtosPrelude;
import com.guillermomolina.protos.runtime.ProtosSignalException;
import com.guillermomolina.protos.runtime.ProtosStringValue;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class ProtosStandardStringProtocolTest {
    // Deliberately Java-side: this verifies frozen-prelude/bootstrap topology,
    // not observable String message semantics.
    @Test
    void stringPrototypeIsFrozenPreludeBindingDelegatingDirectlyToObject()
            throws IOException {
        ProtosPrelude prelude = corePrelude();

        assertSame(
                ProtosObjectValue.rootObject(),
                prelude.stringPrototype().parent().orElseThrow());
        assertTrue(prelude.bindings().isFrozen());
        assertSame(
                prelude.stringPrototype(),
                prelude.bindings().readLocalSlot("String").orElseThrow());
    }

    @Test
    void unicode17GraphemeSegmentationRemainsAvailableBehindInternalBoundary() {
        assertEquals(1, ProtosUnicodeGraphemeSegmentation.count("e\u0301"));
        assertEquals(
                "e\u0301",
                ProtosUnicodeGraphemeSegmentation.at("e\u0301", 0));

        assertEquals(1, ProtosUnicodeGraphemeSegmentation.count("👨‍👩‍👧‍👦"));
        assertEquals(
                "👨‍👩‍👧‍👦",
                ProtosUnicodeGraphemeSegmentation.at("👨‍👩‍👧‍👦", 0));
    }

    // I091: String indices are exact Integer Unicode-scalar positions; size counts scalars.
    @Test
    void indicesAreExactScalarPositionsAndSizeCountsScalars() throws IOException {
        ProtosPrelude prelude = corePrelude();
        ProtosActivation a = prelude.newModuleActivation();
        ProtosStringValue ascii = new ProtosStringValue("abc");
        // U+1F600 occupies two UTF-16 units but one scalar position.
        ProtosStringValue mixed = new ProtosStringValue("a\uD83D\uDE00b");

        assertEquals("a", at(ascii, 0, a));
        assertEquals("c", at(ascii, 2, a));
        assertEquals("\uD83D\uDE00", at(mixed, 1, a));
        assertEquals("b", at(mixed, 2, a));
        Object size = ProtosInvocation.invokeMessage(mixed, "size", List.of(), a);
        assertTrue(ProtosNumericValueSupport.isIntegerInLongRange(size));
        assertEquals(String.valueOf("3"), com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerDecimalText(size));

        ProtosStringValue joined =
                (ProtosStringValue) ProtosInvocation.invokeMessage(ascii, "+", List.of(mixed), a);
        assertEquals("abca\uD83D\uDE00b", joined.value());
        assertEquals("\uD83D\uDE00", at(joined, 4, a));
    }

    @Test
    void invalidIndicesSignalWithoutTruncation() throws IOException {
        ProtosPrelude prelude = corePrelude();
        ProtosActivation a = prelude.newModuleActivation();
        ProtosStringValue mixed = new ProtosStringValue("a\uD83D\uDE00b");
        Object[] invalid = {
            new ProtosIntegerValue(-1),
            new ProtosIntegerValue(3),
            new ProtosIntegerValue(4),
            new ProtosIntegerValue((1L << 32) + 1),
            com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerFromHost(BigInteger.ONE.shiftLeft(64), prelude),
            com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerFromHost(BigInteger.ONE.shiftLeft(64).add(BigInteger.ONE), prelude),
            new ProtosFloatValue(1.0d),
            Long.valueOf(1L),
        };
        for (Object bad : invalid) {
            assertThrows(
                    ProtosSignalException.class,
                    () -> ProtosInvocation.invokeMessage(mixed, "at", List.of(bad), a),
                    String.valueOf(bad));
        }
    }

    private static String at(ProtosStringValue string, long index, ProtosActivation a) {
        return ((ProtosStringValue)
                        ProtosInvocation.invokeMessage(
                                string, "at", List.of(new ProtosIntegerValue(index)), a))
                .value();
    }

    private static ProtosPrelude corePrelude() throws IOException {
        return new ProtosCoreBootstrap()
                .bootstrap(Path.of("protos", "lib", "core"));
    }
}
