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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.guillermomolina.protos.runtime.ProtosActivation;
import com.guillermomolina.protos.runtime.ProtosActorValueTransfer;
import com.guillermomolina.protos.runtime.ProtosArrayValue;
import com.guillermomolina.protos.runtime.ProtosMapValue;
import com.guillermomolina.protos.runtime.ProtosObjectValue;
import com.guillermomolina.protos.runtime.ProtosPrelude;
import com.guillermomolina.protos.runtime.ProtosSignalException;
import com.guillermomolina.protos.runtime.ProtosStringValue;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ProtosJsonDataModelModuleTest {
    private static final Path CORE = Path.of("protos", "lib", "core");
    private static final Path STANDARD_LIBRARY = Path.of("protos", "lib");

    // Deliberately Java-side: this verifies the Actor graph-transfer/runtime
    // boundary and mutation-state preservation rather than ordinary JSON usage.
    @Test
    void dataTransfersAcrossActorsWhileTheModuleRemainsActorLocal() throws Exception {
        ProtosStandardLibraryModuleResolver resolver =
                new ProtosStandardLibraryModuleResolver(STANDARD_LIBRARY);
        ProtosPrelude prelude = new ProtosCoreBootstrap().bootstrap(CORE, resolver);
        ProtosActivation activation = prelude.newModuleActivation();
        Object module =
                ProtosTestExecutionSupport.evaluate(
                        "import(\"std:json/JSON\")",
                        activation);
        assertThrows(
                ProtosSignalException.class,
                () -> ProtosActorValueTransfer.snapshotValue(module, activation));

        ProtosObjectValue source =
                assertInstanceOf(
                        ProtosObjectValue.class,
                        ProtosTestExecutionSupport.evaluate(
                                """
                                JSON: import("std:json/JSON")
                                JSON.object(
                                    "name", JSON.string("Ada"),
                                    "numbers", JSON.array(
                                        JSON.number(10, -1),
                                        JSON.number(1, 0)
                                    )
                                )
                                """,
                                activation));

        ProtosObjectValue copied =
                assertInstanceOf(
                        ProtosObjectValue.class,
                        ProtosActorValueTransfer.snapshotValue(source, activation));

        assertNotSame(source, copied);
        assertTrue(source.isOpen());
        assertTrue(copied.isOpen());
        assertEquals("object", kind(copied));

        ProtosMapValue copiedMap =
                assertInstanceOf(
                        ProtosMapValue.class,
                        copied.readLocalSlot("value").orElseThrow());
        assertTrue(copiedMap.isOpen());
        assertEquals(2, copiedMap.keyedSize());

        ProtosObjectValue copiedNumbers =
                assertInstanceOf(
                        ProtosObjectValue.class,
                        copiedMap.keyedSnapshot().get(1).value());
        assertEquals("array", kind(copiedNumbers));
        ProtosArrayValue copiedArray =
                assertInstanceOf(
                        ProtosArrayValue.class,
                        copiedNumbers.readLocalSlot("value").orElseThrow());
        assertTrue(copiedArray.isFrozen());
        assertEquals(2, copiedArray.indexedSize());
    }

    private static String kind(ProtosObjectValue node) {
        return assertInstanceOf(
                        ProtosStringValue.class,
                        node.readLocalSlot("kind").orElseThrow())
                .value();
    }
}
