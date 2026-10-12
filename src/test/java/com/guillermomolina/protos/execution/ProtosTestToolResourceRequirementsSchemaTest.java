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

final class ProtosTestToolResourceRequirementsSchemaTest {
    private static final Path CORE = Path.of("protos", "lib", "core");
    private static final Path STANDARD_LIBRARY = Path.of("protos", "lib");
    private static final Path TOOL_ROOT = Path.of("protos", "tools", "test");
    private static final Path SHARED_ROOT = Path.of("protos", "tools", "shared");

    @Test
    void versionOnlyDocumentProducesFreshFrozenEmptyDeclarationArray() throws Exception {
        ProtosArrayValue first = parseCompleted("resource-requirements-version = 1\n");
        ProtosArrayValue second = parseCompleted("resource-requirements-version = 1\n");

        assertEquals(0, first.indexedSize());
        assertEquals(0, second.indexedSize());
        assertTrue(first.isFrozen());
        assertTrue(second.isFrozen());
        org.junit.jupiter.api.Assertions.assertNotSame(first, second);
    }

    @Test
    void validDocumentPreservesOrderAndBuildsCanonicalFrozenRequirements() throws Exception {
        String document =
                "resource-requirements-version = 1\n"
                        + "\n"
                        + "[[requirement]]\n"
                        + "case = \"integration/db.protos\"\n"
                        + "key = \"db/integration\"\n"
                        + "mode = \"exclusive\"\n"
                        + "\n"
                        + "[[requirement]]\n"
                        + "case = \"gpu/matrix.protos\"\n"
                        + "key = \"gpu\"\n"
                        + "mode = \"shared\"\n"
                        + "units = 2\n";

        Fixture fixture = fixture();
        String source =
                "Requirements: import(\"self:ResourceRequirements\")\n"
                        + "declarations: Requirements.parse("
                        + protosString(document)
                        + ")\n"
                        + "first: declarations[0]\n"
                        + "second: declarations[1]\n"
                        + "Array(declarations, first, second, "
                        + "Requirements.declarationCase(first), "
                        + "Requirements.declarationRequirement(first), "
                        + "Requirements.declarationCase(second), "
                        + "Requirements.declarationRequirement(second))";

        ProtosArrayValue observed =
                assertInstanceOf(ProtosArrayValue.class, completed(source, fixture));
        ProtosArrayValue declarations = arrayAt(observed, 0);
        ProtosArrayValue firstDeclaration = arrayAt(observed, 1);
        ProtosArrayValue secondDeclaration = arrayAt(observed, 2);
        ProtosArrayValue firstRequirement = arrayAt(observed, 4);
        ProtosArrayValue secondRequirement = arrayAt(observed, 6);

        assertEquals(2, declarations.indexedSize());
        assertTrue(declarations.isFrozen());
        assertTrue(firstDeclaration.isFrozen());
        assertTrue(secondDeclaration.isFrozen());
        assertTrue(firstRequirement.isFrozen());
        assertTrue(secondRequirement.isFrozen());

        assertEquals("integration/db.protos", stringAt(observed, 3));
        assertEquals("db/integration", stringAt(firstRequirement, 0));
        assertEquals("exclusive", stringAt(firstRequirement, 1));
        assertSame(ProtosNullValue.INSTANCE, firstRequirement.indexedAt(2));

        assertEquals("gpu/matrix.protos", stringAt(observed, 5));
        assertEquals("gpu", stringAt(secondRequirement, 0));
        assertEquals("shared", stringAt(secondRequirement, 1));
        assertEquals(String.valueOf(2), String.valueOf(Integer.parseInt(com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerDecimalText(secondRequirement.indexedAt(2)))));
    }

    @Test
    void sameLogicalKeyAcrossDifferentCasesRemainsValid() throws Exception {
        String document =
                "resource-requirements-version = 1\n"
                        + "[[requirement]]\ncase = \"a.protos\"\nkey = \"gpu\"\nmode = \"shared\"\nunits = 1\n"
                        + "[[requirement]]\ncase = \"b.protos\"\nkey = \"gpu\"\nmode = \"exclusive\"\n";
        ProtosArrayValue parsed = parseCompleted(document);
        assertEquals(2, parsed.indexedSize());
    }

    @Test
    void strictVersionFieldsTypesModesAndDuplicatesFailClosed() throws Exception {
        String[] invalidDocuments = {
            "[[requirement]]\ncase = \"a.protos\"\nkey = \"gpu\"\nmode = \"exclusive\"\n",
            "resource-requirements-version = \"1\"\n",
            "resource-requirements-version = 2\n",
            "resource-requirements-version = 1\nunknown = true\n",
            "resource-requirements-version = 1\nrequirement = \"x\"\n",
            "resource-requirements-version = 1\n[[requirement]]\nkey = \"gpu\"\nmode = \"exclusive\"\n",
            "resource-requirements-version = 1\n[[requirement]]\ncase = \"a.protos\"\nmode = \"exclusive\"\n",
            "resource-requirements-version = 1\n[[requirement]]\ncase = \"a.protos\"\nkey = \"gpu\"\n",
            "resource-requirements-version = 1\n[[requirement]]\ncase = \"a.protos\"\nkey = \"gpu\"\nmode = \"exclusive\"\nextra = true\n",
            "resource-requirements-version = 1\n[[requirement]]\ncase = 1\nkey = \"gpu\"\nmode = \"exclusive\"\n",
            "resource-requirements-version = 1\n[[requirement]]\ncase = \"../a.protos\"\nkey = \"gpu\"\nmode = \"exclusive\"\n",
            "resource-requirements-version = 1\n[[requirement]]\ncase = \"a.protos\"\nkey = \"GPU\"\nmode = \"exclusive\"\n",
            "resource-requirements-version = 1\n[[requirement]]\ncase = \"a.protos\"\nkey = \"gpu\"\nmode = \"shared\"\n",
            "resource-requirements-version = 1\n[[requirement]]\ncase = \"a.protos\"\nkey = \"gpu\"\nmode = \"shared\"\nunits = 0\n",
            "resource-requirements-version = 1\n[[requirement]]\ncase = \"a.protos\"\nkey = \"gpu\"\nmode = \"shared\"\nunits = -1\n",
            "resource-requirements-version = 1\n[[requirement]]\ncase = \"a.protos\"\nkey = \"gpu\"\nmode = \"shared\"\nunits = \"1\"\n",
            "resource-requirements-version = 1\n[[requirement]]\ncase = \"a.protos\"\nkey = \"gpu\"\nmode = \"exclusive\"\nunits = 1\n",
            "resource-requirements-version = 1\n[[requirement]]\ncase = \"a.protos\"\nkey = \"gpu\"\nmode = \"unknown\"\n",
            "resource-requirements-version = 1\n"
                    + "[[requirement]]\ncase = \"a.protos\"\nkey = \"gpu\"\nmode = \"shared\"\nunits = 1\n"
                    + "[[requirement]]\ncase = \"a.protos\"\nkey = \"gpu\"\nmode = \"shared\"\nunits = 2\n",
            "resource-requirements-version = 1\n"
                    + "[[requirement]]\ncase = \"a.protos\"\nkey = \"gpu\"\nmode = \"shared\"\nunits = 1\n"
                    + "[[requirement]]\ncase = \"a.protos\"\nkey = \"gpu\"\nmode = \"exclusive\"\n"
        };

        assertFailedTogether(invalidDocuments);
    }

    private static ProtosArrayValue parseCompleted(String document) throws Exception {
        Fixture fixture = fixture();
        Object result =
                completed(
                        "Requirements: import(\"self:ResourceRequirements\")\n"
                                + "Requirements.parse("
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
                        Requirements: import("self:ResourceRequirements")
                        passed: true

                        reject: (document) => {
                            Error.handle(
                                () => {
                                    Requirements.parse(document)
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
