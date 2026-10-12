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
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.guillermomolina.protos.runtime.ProtosActivation;
import com.guillermomolina.protos.runtime.ProtosArrayValue;
import com.guillermomolina.protos.runtime.ProtosObjectValue;
import com.guillermomolina.protos.runtime.ProtosPrelude;
import com.guillermomolina.protos.runtime.ProtosStringValue;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * Explicit JSON parser implementation-shape stress evidence.
 *
 * <p>This class intentionally does not match Surefire's ordinary *Test naming
 * convention. Run it explicitly when validating parser implementation scale.
 */
final class ProtosJsonParserStress {
    private static final Path CORE = Path.of("protos", "lib", "core");
    private static final Path STANDARD_LIBRARY = Path.of("protos", "lib");


    @Test
    void deeplyNestedContainersUseExplicitJsonStackRatherThanRecursiveDescent()
            throws Exception {
        int depth = 2048;
        String input = "[".repeat(depth) + "0" + "]".repeat(depth);

        ProtosObjectValue node = parse(input);
        for (int index = 0; index < depth; index++) {
            ProtosArrayValue array =
                    assertInstanceOf(
                            ProtosArrayValue.class,
                            value(node, "array"));
            assertEquals(1, array.indexedSize());
            node =
                    assertInstanceOf(
                            ProtosObjectValue.class,
                            array.indexedAt(0));
        }
        assertDecimal(node, 0, 0);
    }

    @Test
    void largeArrayUsesBalancedChunkMaterializationAndPreservesEveryElement()
            throws Exception {
        int size = 2048;
        StringBuilder input = new StringBuilder("[");
        for (int index = 0; index < size; index++) {
            if (index != 0) {
                input.append(',');
            }
            input.append(index);
        }
        input.append(']');

        ProtosArrayValue array =
                assertInstanceOf(
                        ProtosArrayValue.class,
                        value(
                                parse(input.toString()),
                                "array"));

        assertTrue(array.isFrozen());
        assertEquals(size, array.indexedSize());

        for (int index : new int[] {0, 1, 2, 31, 32, 511, 1024, 2047}) {
            assertDecimal(
                    assertInstanceOf(
                            ProtosObjectValue.class,
                            array.indexedAt(index)),
                    index,
                    0);
        }
    }

    private static ProtosObjectValue parse(String input) throws Exception {
        ProtosStandardLibraryModuleResolver resolver =
                new ProtosStandardLibraryModuleResolver(STANDARD_LIBRARY);
        ProtosPrelude prelude = new ProtosCoreBootstrap().bootstrap(CORE, resolver);
        ProtosActivation activation = prelude.newModuleActivation();
        activation.context().createLocalSlot("input", new ProtosStringValue(input));
        return assertInstanceOf(
                ProtosObjectValue.class,
                ProtosTestExecutionSupport.evaluate(
                        """
                        JSON: import("std:json/JSON")
                        JSON.parse(input)
                        """,
                        activation));
    }

    private static Object value(ProtosObjectValue node, String expectedKind) {
        assertTrue(node.isOpen());
        assertEquals(expectedKind, kind(node));
        return node.readLocalSlot("value").orElseThrow();
    }

    private static String kind(ProtosObjectValue node) {
        return assertInstanceOf(
                        ProtosStringValue.class,
                        node.readLocalSlot("kind").orElseThrow())
                .value();
    }

    private static void assertDecimal(
            ProtosObjectValue numberNode, long coefficient, long exponent) {
        ProtosObjectValue decimal =
                assertInstanceOf(
                        ProtosObjectValue.class,
                        value(numberNode, "number"));
        assertTrue(decimal.isOpen());
        assertEquals(Long.toString(coefficient), com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerDecimalText(decimal.readLocalSlot("coefficient").orElseThrow()));
        assertEquals(Long.toString(exponent), com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerDecimalText(decimal.readLocalSlot("exponent").orElseThrow()));
    }
}
