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

import com.guillermomolina.protos.runtime.ProtosArrayValue;
import com.guillermomolina.protos.runtime.ProtosPrelude;
import com.guillermomolina.protos.runtime.ProtosStringValue;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

final class ProtosTestToolFileOptionTest {
    private static final Path CORE = Path.of("protos", "lib", "core");
    private static final Path STANDARD_LIBRARY = Path.of("protos", "lib");
    private static final Path TOOL_ROOT = Path.of("protos", "tools", "test");
    private static final Path SHARED_ROOT = Path.of("protos", "tools", "shared");

    @Test
    void absenceSelectsNoFileOrDirectoryLocators() throws Exception {
        ProtosExecutionOutcome outcome =
                execute(
                        """
                        Options: import("self:Options")
                        [
                            Options.filePaths(["test"]),
                            Options.directoryPaths(["test"])
                        ]
                        """);

        assertEquals(ProtosExecutionOutcome.State.COMPLETED, outcome.state());

        ProtosArrayValue observed =
                assertInstanceOf(ProtosArrayValue.class, outcome.value());

        assertStringArray(
                assertInstanceOf(
                        ProtosArrayValue.class,
                        observed.indexedAt(0)));

        assertStringArray(
                assertInstanceOf(
                        ProtosArrayValue.class,
                        observed.indexedAt(1)));
    }

    @Test
    void exactSeparateFileTokenReturnsLiteralLocator() throws Exception {
        ProtosExecutionOutcome outcome =
                execute(
                        """
                        Options: import("self:Options")
                        Options.filePaths([
                            "test",
                            "--file",
                            "protos/tests/library/uri/parse-components.protos"
                        ])
                        """);

        assertEquals(ProtosExecutionOutcome.State.COMPLETED, outcome.state());

        assertStringArray(
                assertInstanceOf(ProtosArrayValue.class, outcome.value()),
                "protos/tests/library/uri/parse-components.protos");
    }

    @Test
    void selectedFileValueIsLiteralEvenWhenItLooksLikeAnotherOption()
            throws Exception {
        ProtosExecutionOutcome outcome =
                execute(
                        """
                        Options: import("self:Options")
                        Options.filePaths([
                            "test",
                            "--file",
                            "--jobs"
                        ])
                        """);

        assertEquals(ProtosExecutionOutcome.State.COMPLETED, outcome.state());

        assertStringArray(
                assertInstanceOf(ProtosArrayValue.class, outcome.value()),
                "--jobs");
    }

    @Test
    void repeatedFileOccurrencesAreRetainedInCliOrder() throws Exception {
        ProtosExecutionOutcome outcome =
                execute(
                        """
                        Options: import("self:Options")
                        Options.filePaths([
                            "test",
                            "--file",
                            "second.protos",
                            "--file",
                            "first.protos"
                        ])
                        """);

        assertEquals(ProtosExecutionOutcome.State.COMPLETED, outcome.state());

        assertStringArray(
                assertInstanceOf(ProtosArrayValue.class, outcome.value()),
                "second.protos",
                "first.protos");
    }

    @Test
    void repeatedDirectoryOccurrencesAreRetainedInCliOrder()
            throws Exception {
        ProtosExecutionOutcome outcome =
                execute(
                        """
                        Options: import("self:Options")
                        Options.directoryPaths([
                            "test",
                            "--directory",
                            "group",
                            "--directory",
                            "other/nested"
                        ])
                        """);

        assertEquals(ProtosExecutionOutcome.State.COMPLETED, outcome.state());

        assertStringArray(
                assertInstanceOf(ProtosArrayValue.class, outcome.value()),
                "group",
                "other/nested");
    }

    @Test
    void missingSelectorValuesFailClosed() throws Exception {
        String[] expressions = {
            "[\"test\", \"--file\"]",
            "[\"test\", \"--directory\"]"
        };

        for (String expression : expressions) {
            ProtosExecutionOutcome outcome =
                    execute(
                            "Options: import(\"self:Options\")\n"
                                    + "Options.filePaths("
                                    + expression
                                    + ")\n");

            assertEquals(
                    ProtosExecutionOutcome.State.FAILED,
                    outcome.state(),
                    () -> "expected fail-closed selector: " + expression);
        }
    }

    @Test
    void positionalAndAttachedFormsRemainIgnored() throws Exception {
        String[] expressions = {
            "[\"test\", \"case.protos\"]",
            "[\"test\", \"--file=case.protos\"]",
            "[\"test\", \"--directory=group\"]"
        };

        for (String expression : expressions) {
            ProtosExecutionOutcome outcome =
                    execute(
                            "Options: import(\"self:Options\")\n"
                                    + "arguments: "
                                    + expression
                                    + "\n"
                                    + "["
                                    + "Options.filePaths(arguments), "
                                    + "Options.directoryPaths(arguments)"
                                    + "]\n");

            assertEquals(ProtosExecutionOutcome.State.COMPLETED, outcome.state());

            ProtosArrayValue observed =
                    assertInstanceOf(ProtosArrayValue.class, outcome.value());

            assertStringArray(
                    assertInstanceOf(
                            ProtosArrayValue.class,
                            observed.indexedAt(0)));

            assertStringArray(
                    assertInstanceOf(
                            ProtosArrayValue.class,
                            observed.indexedAt(1)));
        }
    }

    @Test
    void selectorsJobsAndResourceCatalogRemainOrthogonal()
            throws Exception {
        ProtosExecutionOutcome outcome =
                execute(
                        """
                        Options: import("self:Options")

                        arguments: [
                            "test",
                            "--jobs", "3",
                            "--file", "one.protos",
                            "--directory", "group",
                            "--file", "two.protos",
                            "--resource-catalog", "catalog.toml"
                        ]

                        [
                            Options.jobs(arguments),
                            Options.resourceCatalogPath(arguments),
                            Options.filePaths(arguments),
                            Options.directoryPaths(arguments)
                        ]
                        """);

        assertEquals(ProtosExecutionOutcome.State.COMPLETED, outcome.state());

        ProtosArrayValue observed =
                assertInstanceOf(ProtosArrayValue.class, outcome.value());

        assertEquals(String.valueOf("3"), com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerDecimalText(observed.indexedAt(0)));

        assertEquals(
                "catalog.toml",
                assertInstanceOf(
                                ProtosStringValue.class,
                                observed.indexedAt(1))
                        .value());

        assertStringArray(
                assertInstanceOf(
                        ProtosArrayValue.class,
                        observed.indexedAt(2)),
                "one.protos",
                "two.protos");

        assertStringArray(
                assertInstanceOf(
                        ProtosArrayValue.class,
                        observed.indexedAt(3)),
                "group");
    }

    private static void assertStringArray(
            ProtosArrayValue values,
            String... expected) {
        assertEquals(expected.length,
                values.indexedSize());

        for (int index = 0; index < expected.length; index++) {
            assertEquals(
                    expected[index],
                    assertInstanceOf(
                                    ProtosStringValue.class,
                                    values.indexedAt(
                                            index))
                            .value());
        }
    }

    private static ProtosExecutionOutcome execute(String source) throws Exception {
        ProtosBundledToolModuleResolver resolver =
                new ProtosBundledToolModuleResolver(
                        "test",
                        TOOL_ROOT,
                        SHARED_ROOT,
                        new ProtosStandardLibraryModuleResolver(STANDARD_LIBRARY));

        ProtosPrelude prelude =
                new ProtosCoreBootstrap().bootstrap(CORE, resolver);

        return ProtosTestExecutionSupport.execute(
                source,
                prelude.newModuleActivation());
    }
}
