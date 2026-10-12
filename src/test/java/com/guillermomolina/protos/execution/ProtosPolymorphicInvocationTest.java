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
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.guillermomolina.protos.runtime.ProtosActivation;
import com.guillermomolina.protos.runtime.ProtosArrayValue;
import com.guillermomolina.protos.runtime.ProtosObjectValue;
import com.guillermomolina.protos.runtime.ProtosPrelude;
import java.io.IOException;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class ProtosPolymorphicInvocationTest {
    /**
     * Deliberately Java-side: executable Protos conformance covers freshness,
     * init execution, and inherited behavior, while this assertion preserves the
     * exact represented immediate-parent materialization performed by Object.call.
     * Source parent() reflection is not used as a test oracle here.
     */
    @Test
    void objectCallMaterializesDirectChildOfInvocationReceiver() throws IOException {
        ProtosPrelude prelude = corePrelude();
        ProtosActivation activation = prelude.newModuleActivation();

        Object result =
                ProtosTestExecutionSupport.evaluate(
                        """
                        Thing: {}
                        Thing()
                        """,
                        activation);

        ProtosObjectValue thing =
                assertInstanceOf(
                        ProtosObjectValue.class,
                        activation.context().readLocalSlot("Thing").orElseThrow());
        ProtosObjectValue instance =
                assertInstanceOf(ProtosObjectValue.class, result);
        assertSame(thing, instance.parent().orElseThrow());
    }

    /**
     * Deliberately Java-side: this verifies call-spread lowering into the exact
     * frozen rest-Array representation while preserving injected host object
     * identities. Language-visible rest/spread behavior has independent .protos
     * conformance.
     */
    @Test
    void callSpreadFlattensBeforeClosureActivation() throws IOException {
        ProtosPrelude prelude = corePrelude();
        ProtosActivation activation = prelude.newModuleActivation();
        Object first = new ProtosObjectValue(ProtosObjectValue.rootObject());
        Object second = new ProtosObjectValue(ProtosObjectValue.rootObject());
        activation.context().createLocalSlot("xs", prelude.newArray(List.of(first, second)));

        Object result =
                ProtosTestExecutionSupport.evaluate(
                        """
                        f: (...items) => items
                        f(...xs)
                        """,
                        activation);

        ProtosArrayValue rest = assertInstanceOf(ProtosArrayValue.class, result);
        assertEquals(2, rest.indexedSize());
        assertSame(first, rest.indexedAt(0));
        assertSame(second, rest.indexedAt(1));
        assertTrue(rest.isFrozen());
    }

    /**
     * Deliberately Java-side: the purpose of this test is the canonical Bytecode
     * lowering path for a nested call expression inside a Closure, not merely
     * the source result 99.
     */
    @Test
    void nestedCallInsideClosureUsesCallableLowering() throws IOException {
        Object result =
                execute(
                        corePrelude(),
                        """
                        identity: (x) => x
                        outer: () => identity(99)
                        outer()
                        """);

        assertEquals(String.valueOf("99"), com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerDecimalText(result));
    }

    private static Object execute(ProtosPrelude prelude, String source) {
        return ProtosTestExecutionSupport.evaluate(
                source,
                prelude.newModuleActivation());
    }

    private static ProtosPrelude corePrelude() throws IOException {
        return new ProtosCoreBootstrap()
                .bootstrap(Path.of("protos", "lib", "core"));
    }
}
