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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.guillermomolina.protos.runtime.ProtosActivation;
import com.guillermomolina.protos.runtime.ProtosArrayValue;
import com.guillermomolina.protos.runtime.ProtosBooleanValue;
import com.guillermomolina.protos.runtime.ProtosIdentityMapValue;
import com.guillermomolina.protos.runtime.ProtosIntegerValue;
import com.guillermomolina.protos.runtime.ProtosMapValue;
import com.guillermomolina.protos.runtime.ProtosObjectValue;
import com.guillermomolina.protos.runtime.ProtosPrelude;
import com.guillermomolina.protos.runtime.ProtosSignalException;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class ProtosCollectionsSetMutationModuleTest {
    private static final Path CORE = Path.of("protos", "lib", "core");
    private static final Path STANDARD_LIBRARY = Path.of("protos", "lib");

    @Test
    void mutationStateIsExactlyTheUnderlyingMapState() throws Exception {
        for (String module : new String[] {"Set", "IdentitySet"}) {
            ProtosArrayValue allowed =
                    runWithState(
                            module,
                            false,
                            """
                            added: Collection.add(values, 1)
                            Array(
                                added === values,
                                Collection.size(values),
                                Collection.contains(values, 1),
                                values.at(1) === true
                            )
                            """);

            assertSame(ProtosBooleanValue.TRUE, allowed.indexedAt(0), module);
            assertInteger(1L, allowed.indexedAt(1));
            assertSame(ProtosBooleanValue.TRUE, allowed.indexedAt(2), module);
            assertSame(
                    ProtosBooleanValue.TRUE,
                    allowed.indexedAt(3),
                    module);

            assertStateSignals(module, false, "Collection.add(values, 2)");
            assertStateSignals(module, false, "Collection.remove(values, 1)");
            assertStateSignals(module, true, "Collection.add(values, 1)");
            assertStateSignals(module, true, "Collection.remove(values, 1)");
        }
    }

    private static ProtosArrayValue runWithState(
            String module, boolean frozen, String body) throws Exception {
        ProtosStandardLibraryModuleResolver resolver =
                new ProtosStandardLibraryModuleResolver(STANDARD_LIBRARY);
        ProtosPrelude prelude = new ProtosCoreBootstrap().bootstrap(CORE, resolver);
        ProtosActivation activation = prelude.newModuleActivation();
        ProtosObjectValue values =
                module.equals("Set")
                        ? new ProtosMapValue(prelude.mapPrototype())
                        : new ProtosIdentityMapValue(prelude.identityMapPrototype());

        ProtosInvocation.invokeMessage(
                values,
                "atPut",
                List.of(
                        new ProtosIntegerValue(1L),
                        ProtosBooleanValue.TRUE),
                activation);
        if (frozen) {
            values.freeze();
        } else {
            values.close();
        }
        activation.context().createLocalSlot("values", values);

        Object result =
                ProtosTestExecutionSupport.evaluate(
                        "Collection: import(\"std:collections/"
                                + module
                                + "\")\n"
                                + body,
                        activation);
        return assertInstanceOf(ProtosArrayValue.class, result);
    }

    private static void assertStateSignals(
            String module, boolean frozen, String body) {
        assertThrows(
                ProtosSignalException.class,
                () ->
                        runWithState(
                                module,
                                frozen,
                                body + "\nArray()\n"));
    }

    private static void assertInteger(long expected, Object value) {
        assertEquals(String.valueOf(String.valueOf(expected)), com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerDecimalText(value));
    }
}
