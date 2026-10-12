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

import com.guillermomolina.protos.runtime.ProtosArrayValue;
import com.guillermomolina.protos.runtime.ProtosIntegerValue;
import com.guillermomolina.protos.runtime.ProtosNullValue;
import com.guillermomolina.protos.runtime.ProtosPrelude;
import com.guillermomolina.protos.runtime.ProtosStringValue;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

final class ProtosTestToolResourceAdmissionBindingTest {
    private static final Path CORE = Path.of("protos", "lib", "core");
    private static final Path STANDARD_LIBRARY = Path.of("protos", "lib");
    private static final Path TOOL_ROOT = Path.of("protos", "tools", "test");
    private static final Path SHARED_ROOT = Path.of("protos", "tools", "shared");

    @Test
    void resourceFreeRequirementsBindToFrozenEmptySet() throws Exception {
        ProtosExecutionOutcome outcome =
                execute(
                        "Binding: import(\"self:ResourceBinding\")\n"
                                + "Catalog: import(\"self:ResourceCatalog\")\n"
                                + "bindings: Binding.bindRequirements(Array(), Catalog.empty())\n"
                                + "bindings\n");

        assertEquals(ProtosExecutionOutcome.State.COMPLETED, outcome.state());
        ProtosArrayValue bindings =
                assertInstanceOf(ProtosArrayValue.class, outcome.value());
        assertEquals(0, bindings.indexedSize());
        assertTrue(bindings.isFrozen());
    }

    @Test
    void sharedAndExclusiveRequirementsBindInDeclarationOrder() throws Exception {
        String catalog =
                "resource-catalog-version = 1\n"
                        + "[[resource]]\n"
                        + "key = \"gpu\"\n"
                        + "capacity = 4\n"
                        + "scope = \"placement\"\n"
                        + "provider = \"device/gpu\"\n"
                        + "profile = \"a100\"\n"
                        + "[[resource]]\n"
                        + "key = \"db/integration\"\n"
                        + "capacity = 1\n"
                        + "scope = \"run\"\n"
                        + "provider = \"service/db\"\n";

        String source =
                "Manifest: import(\"self:Manifest\")\n"
                        + "Catalog: import(\"self:ResourceCatalog\")\n"
                        + "Binding: import(\"self:ResourceBinding\")\n"
                        + "catalog: Catalog.parse(" + protosString(catalog) + ")\n"
                        + "requirements: Array("
                        + "Manifest.requirement(\"gpu\", \"shared\", 2), "
                        + "Manifest.requirement(\"db/integration\", \"exclusive\", null))\n"
                        + "bindings: Binding.bindRequirements(requirements, catalog)\n"
                        + "first: bindings[0]\n"
                        + "second: bindings[1]\n"
                        + "firstRequirement: Binding.bindingRequirement(first)\n"
                        + "firstEntry: Binding.bindingCatalogEntry(first)\n"
                        + "secondRequirement: Binding.bindingRequirement(second)\n"
                        + "secondEntry: Binding.bindingCatalogEntry(second)\n"
                        + "Array("
                        + "bindings, "
                        + "Manifest.requirementKey(firstRequirement), "
                        + "Manifest.requirementMode(firstRequirement), "
                        + "Manifest.requirementUnits(firstRequirement), "
                        + "Catalog.entryKey(firstEntry), "
                        + "Catalog.entryCapacity(firstEntry), "
                        + "Catalog.entryScope(firstEntry), "
                        + "Catalog.entryProvider(firstEntry), "
                        + "Catalog.entryProfile(firstEntry), "
                        + "Manifest.requirementKey(secondRequirement), "
                        + "Manifest.requirementMode(secondRequirement), "
                        + "Manifest.requirementUnits(secondRequirement), "
                        + "Catalog.entryKey(secondEntry), "
                        + "Catalog.entryScope(secondEntry), "
                        + "Catalog.entryProvider(secondEntry), "
                        + "Catalog.entryProfile(secondEntry))\n";

        ProtosArrayValue observed = completedArray(source);
        ProtosArrayValue bindings = arrayAt(observed, 0);
        assertEquals(2, bindings.indexedSize());
        assertTrue(bindings.isFrozen());
        assertTrue(arrayAt(bindings, 0).isFrozen());
        assertTrue(arrayAt(bindings, 1).isFrozen());

        assertEquals("gpu", stringAt(observed, 1).value());
        assertEquals("shared", stringAt(observed, 2).value());
        assertEquals(String.valueOf("2"), com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerDecimalText(integerAt(observed, 3)));
        assertEquals("gpu", stringAt(observed, 4).value());
        assertEquals(String.valueOf("4"), com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerDecimalText(integerAt(observed, 5)));
        assertEquals("placement", stringAt(observed, 6).value());
        assertEquals("device/gpu", stringAt(observed, 7).value());
        assertEquals("a100", stringAt(observed, 8).value());

        assertEquals("db/integration", stringAt(observed, 9).value());
        assertEquals("exclusive", stringAt(observed, 10).value());
        assertSame(ProtosNullValue.INSTANCE, observed.indexedAt(11));
        assertEquals("db/integration", stringAt(observed, 12).value());
        assertEquals("run", stringAt(observed, 13).value());
        assertEquals("service/db", stringAt(observed, 14).value());
        assertSame(ProtosNullValue.INSTANCE, observed.indexedAt(15));
    }

    @Test
    void sharedRequirementMayConsumeExactlyTheCatalogCapacity() throws Exception {
        String source =
                "Manifest: import(\"self:Manifest\")\n"
                        + "Catalog: import(\"self:ResourceCatalog\")\n"
                        + "Binding: import(\"self:ResourceBinding\")\n"
                        + "catalog: Catalog.parse("
                        + protosString(
                                "resource-catalog-version = 1\n"
                                        + "[[resource]]\n"
                                        + "key = \"gpu\"\n"
                                        + "capacity = 4\n"
                                        + "scope = \"run\"\n"
                                        + "provider = \"device/gpu\"\n")
                        + ")\n"
                        + "bindings: Binding.bindRequirements("
                        + "Array(Manifest.requirement(\"gpu\", \"shared\", 4)), "
                        + "catalog)\n"
                        + "bindings\n";

        ProtosExecutionOutcome outcome = execute(source);
        assertEquals(ProtosExecutionOutcome.State.COMPLETED, outcome.state());
        assertEquals(
                1,
                assertInstanceOf(ProtosArrayValue.class, outcome.value())
                        .indexedSize());
    }

    @Test
    void missingKeyAndOversizedSharedRequirementFailClosed() throws Exception {
        String catalog =
                "resource-catalog-version = 1\n"
                        + "[[resource]]\n"
                        + "key = \"gpu\"\n"
                        + "capacity = 2\n"
                        + "scope = \"run\"\n"
                        + "provider = \"device/gpu\"\n";

        String[] requirementExpressions = {
            "Manifest.requirement(\"missing\", \"exclusive\", null)",
            "Manifest.requirement(\"gpu\", \"shared\", 3)"
        };

        for (String requirementExpression : requirementExpressions) {
            String source =
                    "Manifest: import(\"self:Manifest\")\n"
                            + "Catalog: import(\"self:ResourceCatalog\")\n"
                            + "Binding: import(\"self:ResourceBinding\")\n"
                            + "catalog: Catalog.parse(" + protosString(catalog) + ")\n"
                            + "Binding.bindRequirements("
                            + "Array(" + requirementExpression + "), catalog)\n";

            ProtosExecutionOutcome outcome = execute(source);
            assertEquals(
                    ProtosExecutionOutcome.State.FAILED,
                    outcome.state(),
                    () -> "expected static admission failure for " + requirementExpression);
        }
    }

    @Test
    void duplicateCatalogKeysFailClosedDefensively() throws Exception {
        String source =
                "Manifest: import(\"self:Manifest\")\n"
                        + "Catalog: import(\"self:ResourceCatalog\")\n"
                        + "Binding: import(\"self:ResourceBinding\")\n"
                        + "entry: Catalog.catalogEntry("
                        + "\"gpu\", 1, \"run\", \"device/gpu\", null)\n"
                        + "Binding.bindRequirements("
                        + "Array(Manifest.requirement(\"gpu\", \"shared\", 1)), "
                        + "Array(entry, entry))\n";

        ProtosExecutionOutcome outcome = execute(source);
        assertEquals(ProtosExecutionOutcome.State.FAILED, outcome.state());
    }

    @Test
    void bindCaseReadsOnlyTheExistingCaseSpecRequirementCarrier() throws Exception {
        String source =
                "Manifest: import(\"self:Manifest\")\n"
                        + "Catalog: import(\"self:ResourceCatalog\")\n"
                        + "Binding: import(\"self:ResourceBinding\")\n"
                        + "base: Manifest.caseSpec(Array(\"sample.protos\", \"boolean\", \"true\"))\n"
                        + "spec: Manifest.caseSpecWithRequirements("
                        + "base, Array(Manifest.requirement(\"gpu\", \"shared\", 1)))\n"
                        + "catalog: Catalog.parse("
                        + protosString(
                                "resource-catalog-version = 1\n"
                                        + "[[resource]]\n"
                                        + "key = \"gpu\"\n"
                                        + "capacity = 1\n"
                                        + "scope = \"placement\"\n"
                                        + "provider = \"device/gpu\"\n")
                        + ")\n"
                        + "Binding.bindCase(spec, catalog)\n";

        ProtosExecutionOutcome outcome = execute(source);
        assertEquals(ProtosExecutionOutcome.State.COMPLETED, outcome.state());
        ProtosArrayValue bindings =
                assertInstanceOf(ProtosArrayValue.class, outcome.value());
        assertEquals(1, bindings.indexedSize());
        assertTrue(bindings.isFrozen());
    }

    private static ProtosArrayValue completedArray(String source) throws Exception {
        ProtosExecutionOutcome outcome = execute(source);
        assertEquals(
                ProtosExecutionOutcome.State.COMPLETED,
                outcome.state(),
                () -> "state=" + outcome.state() + ", error=" + outcome.error());
        return assertInstanceOf(ProtosArrayValue.class, outcome.value());
    }

    private static ProtosExecutionOutcome execute(String source) throws Exception {
        ProtosBundledToolModuleResolver resolver =
                new ProtosBundledToolModuleResolver(
                        "test",
                        TOOL_ROOT,
                        SHARED_ROOT,
                        new ProtosStandardLibraryModuleResolver(STANDARD_LIBRARY));
        ProtosPrelude prelude = new ProtosCoreBootstrap().bootstrap(CORE, resolver);
        return com.guillermomolina.protos.execution.ProtosTestExecutionSupport.execute(
source,
prelude.newModuleActivation());
    }

    private static ProtosArrayValue arrayAt(ProtosArrayValue array, int index) {
        return assertInstanceOf(
                ProtosArrayValue.class,
                array.indexedAt(index));
    }

    private static ProtosIntegerValue integerAt(ProtosArrayValue array, int index) {
        return assertInstanceOf(
                ProtosIntegerValue.class,
                array.indexedAt(index));
    }

    private static ProtosStringValue stringAt(ProtosArrayValue array, int index) {
        return assertInstanceOf(
                ProtosStringValue.class,
                array.indexedAt(index));
    }

    private static String protosString(String value) {
        return "\""
                + value.replace("\\", "\\\\")
                        .replace("\"", "\\\"")
                        .replace("\r", "\\r")
                        .replace("\n", "\\n")
                        .replace("\t", "\\t")
                + "\"";
    }
}
