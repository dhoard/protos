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

import com.guillermomolina.protos.runtime.ProtosArrayValue;
import com.guillermomolina.protos.runtime.ProtosPrelude;
import com.guillermomolina.protos.runtime.ProtosStringValue;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

final class ProtosTestToolResourceRequirementsJoinTest {
    private static final Path CORE = Path.of("protos", "lib", "core");
    private static final Path STANDARD_LIBRARY = Path.of("protos", "lib");
    private static final Path TOOL_ROOT = Path.of("protos", "tools", "test");
    private static final Path SHARED_ROOT = Path.of("protos", "tools", "shared");

    @Test
    void completePlanJoinPreservesCaseAndRequirementOrderWithoutCreatingCases() throws Exception {
        String source =
                "Manifest: import(\"self:Manifest\")\n"
                        + "Requirements: import(\"self:ResourceRequirements\")\n"
                        + "first: Manifest.caseSpec(Array(\"a.protos\", \"boolean\", \"true\"))\n"
                        + "second: Manifest.caseSpec(Array(\"b.protos\", \"boolean\", \"true\"))\n"
                        + "plan: Array(Array(first, second))\n"
                        + "declarations: Array(\n"
                        + "  Requirements.declaration(\"b.protos\", Manifest.requirement(\"gpu\", \"shared\", 2)),\n"
                        + "  Requirements.declaration(\"b.protos\", Manifest.requirement(\"db/integration\", \"exclusive\", null))\n"
                        + ")\n"
                        + "joined: Requirements.attachToPlan(plan, declarations)\n"
                        + "cases: Manifest.planCases(joined)\n"
                        + "firstRequirements: Manifest.caseRequirements(cases[0])\n"
                        + "secondRequirements: Manifest.caseRequirements(cases[1])\n"
                        + "Array(joined, cases, cases.size(), Manifest.caseId(cases[0]), firstRequirements, "
                        + "Manifest.caseId(cases[1]), secondRequirements, "
                        + "Manifest.requirementKey(secondRequirements[0]), "
                        + "Manifest.requirementKey(secondRequirements[1]))";

        ProtosArrayValue observed = assertInstanceOf(ProtosArrayValue.class, completed(source));
        ProtosArrayValue joined = arrayAt(observed, 0);
        ProtosArrayValue cases = arrayAt(observed, 1);
        ProtosArrayValue firstRequirements = arrayAt(observed, 4);
        ProtosArrayValue secondRequirements = arrayAt(observed, 6);

        assertTrue(joined.isFrozen());
        assertTrue(cases.isFrozen());
        assertEquals(2, integerLikeAt(observed, 2));
        assertEquals("a.protos", stringAt(observed, 3));
        assertEquals(0, firstRequirements.indexedSize());
        assertTrue(firstRequirements.isFrozen());
        assertEquals("b.protos", stringAt(observed, 5));
        assertEquals(2, secondRequirements.indexedSize());
        assertTrue(secondRequirements.isFrozen());
        assertEquals("gpu", stringAt(observed, 7));
        assertEquals("db/integration", stringAt(observed, 8));
    }

    @Test
    void orphanDeclarationFailsClosedBeforeAnyLaterSelection() throws Exception {
        String source =
                "Manifest: import(\"self:Manifest\")\n"
                        + "Requirements: import(\"self:ResourceRequirements\")\n"
                        + "only: Manifest.caseSpec(Array(\"a.protos\", \"boolean\", \"true\"))\n"
                        + "plan: Array(Array(only))\n"
                        + "declarations: Array(Requirements.declaration(\"missing.protos\", Manifest.requirement(\"gpu\", \"exclusive\", null)))\n"
                        + "Requirements.attachToPlan(plan, declarations)";
        assertFailed(source);
    }

    @Test
    void referencedAmbiguousCaseIdentityFailsBecauseD091RequiresExactlyOneCaseSpec() throws Exception {
        String source =
                "Manifest: import(\"self:Manifest\")\n"
                        + "Requirements: import(\"self:ResourceRequirements\")\n"
                        + "left: Manifest.caseSpec(Array(\"dup.protos\", \"boolean\", \"true\"))\n"
                        + "right: Manifest.caseSpec(Array(\"dup.protos\", \"error\", \"-\"))\n"
                        + "plan: Array(Array(left, right))\n"
                        + "declarations: Array(Requirements.declaration(\"dup.protos\", Manifest.requirement(\"gpu\", \"exclusive\", null)))\n"
                        + "Requirements.attachToPlan(plan, declarations)";
        assertFailed(source);
    }

    @Test
    void requirementForExistingButLaterUnselectedCaseIsNotOrphan() throws Exception {
        String source =
                "Manifest: import(\"self:Manifest\")\n"
                        + "Requirements: import(\"self:ResourceRequirements\")\n"
                        + "first: Manifest.caseSpec(Array(\"selected.protos\", \"boolean\", \"true\"))\n"
                        + "second: Manifest.caseSpec(Array(\"not-selected.protos\", \"boolean\", \"true\"))\n"
                        + "plan: Array(Array(first, second))\n"
                        + "declarations: Array(Requirements.declaration(\"not-selected.protos\", Manifest.requirement(\"gpu\", \"exclusive\", null)))\n"
                        + "joined: Requirements.attachToPlan(plan, declarations)\n"
                        + "completeCases: Manifest.planCases(joined)\n"
                        + "selectedSubset: Array(completeCases[0])\n"
                        + "Array(selectedSubset.size(), Manifest.caseId(selectedSubset[0]), Manifest.caseRequirements(completeCases[1]).size())";

        ProtosArrayValue observed = assertInstanceOf(ProtosArrayValue.class, completed(source));
        assertEquals(1, integerLikeAt(observed, 0));
        assertEquals("selected.protos", stringAt(observed, 1));
        assertEquals(1, integerLikeAt(observed, 2));
    }

    private static Object completed(String source) throws Exception {
        Fixture fixture = fixture();
        ProtosExecutionOutcome outcome =
                com.guillermomolina.protos.execution.ProtosTestExecutionSupport.execute(
source,
fixture.prelude().newModuleActivation());
        assertEquals(
                ProtosExecutionOutcome.State.COMPLETED,
                outcome.state(),
                () -> "state=" + outcome.state() + ", error=" + outcome.error());
        return outcome.value();
    }

    private static void assertFailed(String source) throws Exception {
        Fixture fixture = fixture();
        ProtosExecutionOutcome outcome =
                com.guillermomolina.protos.execution.ProtosTestExecutionSupport.execute(
source,
fixture.prelude().newModuleActivation());
        assertEquals(ProtosExecutionOutcome.State.FAILED, outcome.state());
    }

    private static Fixture fixture() throws Exception {
        ProtosBundledToolModuleResolver resolver =
                new ProtosBundledToolModuleResolver(
                        "test",
                        TOOL_ROOT,
                        SHARED_ROOT,
                        new ProtosStandardLibraryModuleResolver(STANDARD_LIBRARY));
        ProtosPrelude prelude = new ProtosCoreBootstrap().bootstrap(CORE, resolver);
        return new Fixture(prelude);
    }

    private static ProtosArrayValue arrayAt(ProtosArrayValue array, int index) {
        return assertInstanceOf(
                ProtosArrayValue.class, array.indexedAt(index));
    }

    private static String stringAt(ProtosArrayValue array, int index) {
        return assertInstanceOf(
                        ProtosStringValue.class,
                        array.indexedAt(index))
                .value();
    }

    private static int integerLikeAt(ProtosArrayValue array, int index) {
        return com.guillermomolina.protos.runtime.ProtosNumericValueSupport.exactInt(
                assertInstanceOf(
                        com.guillermomolina.protos.runtime.ProtosIntegerValue.class,
                        array.indexedAt(index)));
    }

    private record Fixture(ProtosPrelude prelude) {}
}
