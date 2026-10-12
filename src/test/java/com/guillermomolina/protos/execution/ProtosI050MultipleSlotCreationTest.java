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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.guillermomolina.protos.runtime.ProtosActivation;
import com.guillermomolina.protos.runtime.ProtosIntegerValue;
import com.guillermomolina.protos.runtime.ProtosPrelude;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

final class ProtosI050MultipleSlotCreationTest {
    private static final Path CORE =
            Path.of("protos", "lib", "core");

    @Test
    void shortArrayFailsBeforeAnyTargetSlotIsCreated() throws Exception {
        ProtosActivation activation = activation();

        ProtosExecutionOutcome outcome =
                ProtosTestExecutionSupport.execute(
                        "(first, second, third): Array(1, 2)",
                        activation);

        assertEquals(ProtosExecutionOutcome.State.FAILED, outcome.state());
        assertFalse(activation.context().hasLocalSlot("first"));
        assertFalse(activation.context().hasLocalSlot("second"));
        assertFalse(activation.context().hasLocalSlot("third"));
    }

    @Test
    void wrongSourceDomainFailsBeforeAnyTargetSlotIsCreated() throws Exception {
        ProtosActivation activation = activation();

        ProtosExecutionOutcome outcome =
                ProtosTestExecutionSupport.execute(
                        "(first, second): {}",
                        activation);

        assertEquals(ProtosExecutionOutcome.State.FAILED, outcome.state());
        assertFalse(activation.context().hasLocalSlot("first"));
        assertFalse(activation.context().hasLocalSlot("second"));
    }

    @Test
    void duplicateTargetFailureDoesNotRollBackEarlierCreation() throws Exception {
        ProtosActivation activation = activation();

        ProtosExecutionOutcome outcome =
                ProtosTestExecutionSupport.execute(
                        "(first, first): Array(1, 2)",
                        activation);

        assertEquals(ProtosExecutionOutcome.State.FAILED, outcome.state());
        assertTrue(activation.context().hasLocalSlot("first"));
        assertIntegerSlot(activation, "first", 1);
    }

    @Test
    void preexistingLaterConflictPreservesEarlierCreation() throws Exception {
        ProtosActivation activation = activation();

        activation.context().createLocalSlot(
                "existing",
                new ProtosIntegerValue(99));

        ProtosExecutionOutcome outcome =
                ProtosTestExecutionSupport.execute(
                        "(first, existing): Array(1, 2)",
                        activation);

        assertEquals(ProtosExecutionOutcome.State.FAILED, outcome.state());

        // D143 deliberately does not preflight target-name conflicts and does
        // not roll back successful earlier ':' creations.
        assertIntegerSlot(activation, "first", 1);
        assertIntegerSlot(activation, "existing", 99);
    }

    private static ProtosActivation activation() throws Exception {
        ProtosPrelude prelude =
                new ProtosCoreBootstrap().bootstrap(CORE);
        return prelude.newModuleActivation();
    }

    private static void assertIntegerSlot(
            ProtosActivation activation,
            String name,
            long expected) {
        ProtosIntegerValue value =
                assertInstanceOf(
                        ProtosIntegerValue.class,
                        activation.context()
                                .readLocalSlot(name)
                                .orElseThrow());
        assertEquals(Long.toString(expected), com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerDecimalText(value));
    }
}
