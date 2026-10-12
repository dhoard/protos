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

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

final class ProtosTestToolResourceCatalogCompositionTest {
    private static final Path CORE = Path.of("protos", "lib", "core");
    private static final Path STANDARD_LIBRARY = Path.of("protos", "lib");
    private static final Path TOOL_ROOT = Path.of("protos", "tools", "test");
    private static final Path SHARED_ROOT = Path.of("protos", "tools", "shared");
    private static final Path MAIN = TOOL_ROOT.resolve("Main.protos");

    @Test
    void absenceBuildsFrozenEmptyCatalogWithoutInvokingAcquirer() throws Exception {
        String source =
                "Options: import(\"self:Options\")\n"
                        + "Catalog: import(\"self:ResourceCatalog\")\n"
                        + "arguments: Array(\"test\")\n"
                        + "path: Options.resourceCatalogPath(arguments)\n"
                        + "calls: 0\n"
                        + "acquirer: (selected) => {\n"
                        + "    calls = calls + 1\n"
                        + "    Encoding.UTF8.encode(\"should-not-be-read\")\n"
                        + "}\n"
                        + "catalog: Catalog.empty()\n"
                        + "(path === null).ifFalse(() => {\n"
                        + "    catalog = Catalog.parse(Encoding.UTF8.decode(acquirer(path)))\n"
                        + "})\n"
                        + "Array(calls, catalog, path)\n";

        ProtosArrayValue observed = completedArray(source);
        assertEquals(String.valueOf("0"), com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerDecimalText(integerAt(observed, 0)));

        ProtosArrayValue catalog = arrayAt(observed, 1);
        assertEquals(0, catalog.indexedSize());
        assertTrue(catalog.isFrozen());
        assertSame(ProtosNullValue.INSTANCE, observed.indexedAt(2));
    }

    @Test
    void selectedPathAcquiresDecodesAndParsesExactlyOnce() throws Exception {
        String document =
                "resource-catalog-version = 1\n"
                        + "[[resource]]\n"
                        + "key = \"gpu\"\n"
                        + "capacity = 4\n"
                        + "scope = \"placement\"\n"
                        + "provider = \"device/gpu\"\n"
                        + "profile = \"a100\"\n";

        String source =
                "Options: import(\"self:Options\")\n"
                        + "Catalog: import(\"self:ResourceCatalog\")\n"
                        + "arguments: Array("
                        + "\"test\", \"--resource-catalog\", \"catalog.toml\")\n"
                        + "path: Options.resourceCatalogPath(arguments)\n"
                        + "calls: 0\n"
                        + "acquirer: (selected) => {\n"
                        + "    calls = calls + 1\n"
                        + "    Encoding.UTF8.encode("
                        + protosString(document)
                        + ")\n"
                        + "}\n"
                        + "catalog: Catalog.empty()\n"
                        + "(path === null).ifFalse(() => {\n"
                        + "    catalog = Catalog.parse(Encoding.UTF8.decode(acquirer(path)))\n"
                        + "})\n"
                        + "entry: catalog[0]\n"
                        + "Array("
                        + "calls, path, catalog, "
                        + "Catalog.entryKey(entry), "
                        + "Catalog.entryCapacity(entry), "
                        + "Catalog.entryScope(entry), "
                        + "Catalog.entryProvider(entry), "
                        + "Catalog.entryProfile(entry))\n";

        ProtosArrayValue observed = completedArray(source);
        assertEquals(String.valueOf("1"), com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerDecimalText(integerAt(observed, 0)));
        assertEquals("catalog.toml", stringAt(observed, 1).value());

        ProtosArrayValue catalog = arrayAt(observed, 2);
        assertEquals(1, catalog.indexedSize());
        assertTrue(catalog.isFrozen());

        assertEquals("gpu", stringAt(observed, 3).value());
        assertEquals(String.valueOf("4"), com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerDecimalText(integerAt(observed, 4)));
        assertEquals("placement", stringAt(observed, 5).value());
        assertEquals("device/gpu", stringAt(observed, 6).value());
        assertEquals("a100", stringAt(observed, 7).value());
    }

    @Test
    void malformedCatalogStillFailsClosedAfterAcquisitionComposition() throws Exception {
        String source =
                "Options: import(\"self:Options\")\n"
                        + "Catalog: import(\"self:ResourceCatalog\")\n"
                        + "arguments: Array("
                        + "\"test\", \"--resource-catalog\", \"bad.toml\")\n"
                        + "path: Options.resourceCatalogPath(arguments)\n"
                        + "acquirer: (selected) => Encoding.UTF8.encode("
                        + protosString("resource-catalog-version = [\n")
                        + ")\n"
                        + "Catalog.parse(Encoding.UTF8.decode(acquirer(path)))\n";

        ProtosExecutionOutcome outcome = execute(source);
        assertEquals(ProtosExecutionOutcome.State.FAILED, outcome.state());
    }

    @Test
    void publicMainUsesOnlyTheRatifiedCompositionPath() throws Exception {
        String main = Files.readString(MAIN, StandardCharsets.UTF_8);

        assertTrue(main.contains("ResourceCatalog: import(\"self:ResourceCatalog\")"));
        assertTrue(main.contains("arguments: process.args()"));
        assertTrue(main.contains("jobs: Options.jobs(arguments)"));
        assertTrue(main.contains(
                "resourceCatalogPath: Options.resourceCatalogPath(arguments)"));
        assertTrue(main.contains("resourceCatalog: ResourceCatalog.empty()"));
        assertTrue(main.contains("(resourceCatalogPath === null).ifFalse(() => {"));
        assertTrue(main.contains("catalogAcquirer(resourceCatalogPath)"));
        assertTrue(main.contains("Encoding.UTF8.decode(resourceCatalogBytes)"));
        assertTrue(main.contains("ResourceCatalog.parse(resourceCatalogText)"));

        org.junit.jupiter.api.Assertions.assertFalse(main.contains("filesystem.open("));
        org.junit.jupiter.api.Assertions.assertFalse(main.contains("Path."));
        org.junit.jupiter.api.Assertions.assertFalse(main.contains("Files."));
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
