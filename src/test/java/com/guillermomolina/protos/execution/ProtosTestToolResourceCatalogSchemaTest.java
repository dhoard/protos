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
import com.guillermomolina.protos.runtime.ProtosBooleanValue;
import com.guillermomolina.protos.runtime.ProtosNullValue;
import com.guillermomolina.protos.runtime.ProtosPrelude;
import com.guillermomolina.protos.runtime.ProtosStringValue;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

final class ProtosTestToolResourceCatalogSchemaTest {
    private static final Path CORE = Path.of("protos", "lib", "core");
    private static final Path STANDARD_LIBRARY = Path.of("protos", "lib");
    private static final Path TOOL_ROOT = Path.of("protos", "tools", "test");
    private static final Path SHARED_ROOT = Path.of("protos", "tools", "shared");

    @Test
    void versionOnlyCatalogProducesFreshFrozenEmptyEntryArray() throws Exception {
        ProtosArrayValue first = parseCompleted("resource-catalog-version = 1\n");
        ProtosArrayValue second = parseCompleted("resource-catalog-version = 1\n");

        assertEquals(0, first.indexedSize());
        assertEquals(0, second.indexedSize());
        assertTrue(first.isFrozen());
        assertTrue(second.isFrozen());
        org.junit.jupiter.api.Assertions.assertNotSame(first, second);
    }

    @Test
    void validCatalogPreservesOrderAndCanonicalInertFields() throws Exception {
        String document =
                "resource-catalog-version = 1\n"
                        + "\n"
                        + "[[resource]]\n"
                        + "key = \"gpu\"\n"
                        + "capacity = 4\n"
                        + "scope = \"placement\"\n"
                        + "provider = \"device/gpu\"\n"
                        + "profile = \"a100\"\n"
                        + "\n"
                        + "[[resource]]\n"
                        + "key = \"license/ansys\"\n"
                        + "capacity = 20\n"
                        + "scope = \"run\"\n"
                        + "provider = \"license/flexlm\"\n";

        Fixture fixture = fixture();
        String source =
                "Catalog: import(\"self:ResourceCatalog\")\n"
                        + "entries: Catalog.parse("
                        + protosString(document)
                        + ")\n"
                        + "first: entries[0]\n"
                        + "second: entries[1]\n"
                        + "Array(entries, first, second, "
                        + "Catalog.entryKey(first), Catalog.entryCapacity(first), "
                        + "Catalog.entryScope(first), Catalog.entryProvider(first), "
                        + "Catalog.entryProfile(first), "
                        + "Catalog.entryKey(second), Catalog.entryCapacity(second), "
                        + "Catalog.entryScope(second), Catalog.entryProvider(second), "
                        + "Catalog.entryProfile(second))";

        ProtosArrayValue observed =
                assertInstanceOf(ProtosArrayValue.class, completed(source, fixture));

        ProtosArrayValue entries = arrayAt(observed, 0);
        ProtosArrayValue first = arrayAt(observed, 1);
        ProtosArrayValue second = arrayAt(observed, 2);

        assertEquals(2, entries.indexedSize());
        assertTrue(entries.isFrozen());
        assertTrue(first.isFrozen());
        assertTrue(second.isFrozen());

        assertEquals("gpu", stringAt(observed, 3));
        assertEquals(4, integerAt(observed, 4));
        assertEquals("placement", stringAt(observed, 5));
        assertEquals("device/gpu", stringAt(observed, 6));
        assertEquals("a100", stringAt(observed, 7));

        assertEquals("license/ansys", stringAt(observed, 8));
        assertEquals(20, integerAt(observed, 9));
        assertEquals("run", stringAt(observed, 10));
        assertEquals("license/flexlm", stringAt(observed, 11));
        assertSame(ProtosNullValue.INSTANCE, observed.indexedAt(12));
    }

    @Test
    void strictVersionShapeIdentityScopeCapacityAndDuplicatesFailClosed()
            throws Exception {
        String[] invalidDocuments = {
            "[[resource]]\nkey = \"gpu\"\ncapacity = 1\nscope = \"placement\"\nprovider = \"device/gpu\"\n",
            "resource-catalog-version = \"1\"\n",
            "resource-catalog-version = 2\n",
            "resource-catalog-version = 1\nunknown = true\n",
            "resource-catalog-version = 1\nresource = \"x\"\n",
            "resource-catalog-version = 1\n[[resource]]\ncapacity = 1\nscope = \"placement\"\nprovider = \"device/gpu\"\n",
            "resource-catalog-version = 1\n[[resource]]\nkey = \"gpu\"\nscope = \"placement\"\nprovider = \"device/gpu\"\n",
            "resource-catalog-version = 1\n[[resource]]\nkey = \"gpu\"\ncapacity = 1\nprovider = \"device/gpu\"\n",
            "resource-catalog-version = 1\n[[resource]]\nkey = \"gpu\"\ncapacity = 1\nscope = \"placement\"\n",
            "resource-catalog-version = 1\n[[resource]]\nkey = \"gpu\"\ncapacity = 1\nscope = \"placement\"\nprovider = \"device/gpu\"\nextra = true\n",
            "resource-catalog-version = 1\n[[resource]]\nkey = \"GPU\"\ncapacity = 1\nscope = \"placement\"\nprovider = \"device/gpu\"\n",
            "resource-catalog-version = 1\n[[resource]]\nkey = \"gpu\"\ncapacity = 0\nscope = \"placement\"\nprovider = \"device/gpu\"\n",
            "resource-catalog-version = 1\n[[resource]]\nkey = \"gpu\"\ncapacity = -1\nscope = \"placement\"\nprovider = \"device/gpu\"\n",
            "resource-catalog-version = 1\n[[resource]]\nkey = \"gpu\"\ncapacity = 1.5\nscope = \"placement\"\nprovider = \"device/gpu\"\n",
            "resource-catalog-version = 1\n[[resource]]\nkey = \"gpu\"\ncapacity = 1\nscope = \"host\"\nprovider = \"device/gpu\"\n",
            "resource-catalog-version = 1\n[[resource]]\nkey = \"gpu\"\ncapacity = 1\nscope = \"placement\"\nprovider = \"Device/GPU\"\n",
            "resource-catalog-version = 1\n[[resource]]\nkey = \"gpu\"\ncapacity = 1\nscope = \"placement\"\nprovider = \"device/gpu\"\nprofile = \"A100\"\n",
            "resource-catalog-version = 1\n"
                    + "[[resource]]\nkey = \"gpu\"\ncapacity = 1\nscope = \"placement\"\nprovider = \"device/gpu\"\n"
                    + "[[resource]]\nkey = \"gpu\"\ncapacity = 2\nscope = \"run\"\nprovider = \"other/provider\"\n"
        };

        assertFailedTogether(invalidDocuments);
    }

    private static ProtosArrayValue parseCompleted(String document) throws Exception {
        Fixture fixture = fixture();
        Object result =
                completed(
                        "Catalog: import(\"self:ResourceCatalog\")\n"
                                + "Catalog.parse("
                                + protosString(document)
                                + ")",
                        fixture);
        return assertInstanceOf(ProtosArrayValue.class, result);
    }

    private static void assertFailedTogether(String[] documents) throws Exception {
        Fixture fixture = fixture();
        StringBuilder source =
                new StringBuilder(
                        """
                        Catalog: import("self:ResourceCatalog")
                        passed: true

                        reject: (document) => {
                            Error.handle(
                                () => {
                                    Catalog.parse(document)
                                    passed = false
                                },
                                (error) => { null }
                            )
                        }
                        """);

        for (String document : documents) {
            source.append("reject(")
                    .append(protosString(document))
                    .append(")\n");
        }

        source.append("passed\n");

        assertSame(
                ProtosBooleanValue.TRUE,
                completed(source.toString(), fixture));
    }

    private static final class SharedPrelude {
        private static final ProtosPrelude INSTANCE = create();

        private static ProtosPrelude create() {
            try {
                ProtosBundledToolModuleResolver resolver =
                        new ProtosBundledToolModuleResolver(
                                "test",
                                TOOL_ROOT,
                                SHARED_ROOT,
                                new ProtosStandardLibraryModuleResolver(STANDARD_LIBRARY));
                return new ProtosCoreBootstrap().bootstrap(CORE, resolver);
            } catch (Exception exception) {
                throw new ExceptionInInitializerError(exception);
            }
        }
    }

    private static Fixture fixture() {
        return new Fixture(SharedPrelude.INSTANCE);
    }

    private static Object completed(String source, Fixture fixture) throws Exception {
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

    private static ProtosArrayValue arrayAt(ProtosArrayValue array, int index) {
        return assertInstanceOf(
                ProtosArrayValue.class,
                array.indexedAt(index));
    }

    private static String stringAt(ProtosArrayValue array, int index) {
        return assertInstanceOf(
                        ProtosStringValue.class,
                        array.indexedAt(index))
                .value();
    }

    private static int integerAt(ProtosArrayValue array, int index) {
        return Integer.parseInt(com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerDecimalText(array.indexedAt(index)));
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

    private record Fixture(ProtosPrelude prelude) {}
}
