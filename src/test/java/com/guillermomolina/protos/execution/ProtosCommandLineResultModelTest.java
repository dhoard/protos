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
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.guillermomolina.protos.runtime.ProtosArrayValue;
import com.guillermomolina.protos.runtime.ProtosBooleanValue;
import com.guillermomolina.protos.runtime.ProtosNullValue;
import com.guillermomolina.protos.runtime.ProtosObjectValue;
import com.guillermomolina.protos.runtime.ProtosPrelude;
import com.guillermomolina.protos.runtime.ProtosSignalException;
import com.guillermomolina.protos.runtime.ProtosStringValue;

import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Test;

final class ProtosCommandLineResultModelTest {
    private static final Path CORE = Path.of("protos", "lib", "core");
    private static final Path STANDARD_LIBRARY = Path.of("protos", "lib");

    @Test
    void a2DoesNotPublishResultConstructionBeforeParseExists() throws Exception {
        ProtosStandardLibraryModuleResolver resolver =
                new ProtosStandardLibraryModuleResolver(STANDARD_LIBRARY);
        ProtosPrelude prelude = new ProtosCoreBootstrap().bootstrap(CORE, resolver);

        Object imported =
                ProtosTestExecutionSupport.evaluate(
                        "import(\"std:cli/CommandLine\")",
                        prelude.newModuleActivation());
        ProtosObjectValue module = assertInstanceOf(ProtosObjectValue.class, imported);

        assertEquals(Set.of("option", "positional", "command", "parse", "renderHelp"), module.localSlotsSnapshot().keySet());
    }

    @Test
    void approvedRecursiveResultShapeIsFreshFrozenAndLossless() throws Exception {
        ProtosObjectValue result =
                assertInstanceOf(
                        ProtosObjectValue.class,
                        evaluate(
                                """
                                argumentsInput: Array(
                                    "--verbose",
                                    "package",
                                    "install",
                                    "--",
                                    "-literal"
                                )
                                arguments: Array(...argumentsInput)
                                arguments.freeze()

                                verbose: {
                                    kind: "optionOccurrence"
                                    key: "verbose"
                                    spelling: "--verbose"
                                    tokenIndex: 0
                                    value: null
                                    valueTokenIndex: null
                                }
                                verbose.freeze()
                                rootOptions: Array(verbose)
                                rootOptions.freeze()
                                rootPositionals: Array()
                                rootPositionals.freeze()

                                leafPositional: {
                                    kind: "positionalOccurrence"
                                    key: "files"
                                    value: "-literal"
                                    tokenIndex: 4
                                }
                                leafPositional.freeze()
                                leafOptions: Array()
                                leafOptions.freeze()
                                leafPositionals: Array(leafPositional)
                                leafPositionals.freeze()
                                leaf: {
                                    kind: "commandResult"
                                    name: "install"
                                    tokenIndex: 2
                                    endOfOptionsIndex: 3
                                    options: leafOptions
                                    positionals: leafPositionals
                                    subcommand: null
                                }
                                leaf.freeze()

                                packageOptions: Array()
                                packageOptions.freeze()
                                packagePositionals: Array()
                                packagePositionals.freeze()
                                packageResult: {
                                    kind: "commandResult"
                                    name: "package"
                                    tokenIndex: 1
                                    endOfOptionsIndex: null
                                    options: packageOptions
                                    positionals: packagePositionals
                                    subcommand: leaf
                                }
                                packageResult.freeze()

                                root: {
                                    kind: "commandResult"
                                    name: "tool"
                                    tokenIndex: null
                                    endOfOptionsIndex: null
                                    options: rootOptions
                                    positionals: rootPositionals
                                    subcommand: packageResult
                                }
                                root.freeze()

                                parseResult: {
                                    kind: "parseResult"
                                    arguments: arguments
                                    command: root
                                }
                                parseResult.freeze()

                                argumentsInput[0] = "changed-after-snapshot"
                                parseResult
                                """));

        assertTrue(result.isFrozen());
        assertEquals(Set.of("kind", "arguments", "command"), result.localSlotsSnapshot().keySet());
        assertEquals("parseResult", stringSlot(result, "kind"));

        ProtosArrayValue arguments = arraySlot(result, "arguments");
        assertTrue(arguments.isFrozen());
        assertEquals(5, arguments.indexedSize());
        assertEquals("--verbose", stringValue(arguments.indexedAt(0)));
        assertEquals("package", stringValue(arguments.indexedAt(1)));
        assertEquals("install", stringValue(arguments.indexedAt(2)));
        assertEquals("--", stringValue(arguments.indexedAt(3)));
        assertEquals("-literal", stringValue(arguments.indexedAt(4)));

        ProtosObjectValue root = objectSlot(result, "command");
        assertCommandResultShape(root);
        assertEquals("tool", stringSlot(root, "name"));
        assertSame(ProtosNullValue.INSTANCE, root.localSlotsSnapshot().get("tokenIndex"));
        assertSame(ProtosNullValue.INSTANCE, root.localSlotsSnapshot().get("endOfOptionsIndex"));

        ProtosArrayValue rootOptions = arraySlot(root, "options");
        ProtosArrayValue rootPositionals = arraySlot(root, "positionals");
        assertTrue(rootOptions.isFrozen());
        assertTrue(rootPositionals.isFrozen());
        assertEquals(1, rootOptions.indexedSize());
        assertEquals(0, rootPositionals.indexedSize());

        ProtosObjectValue verbose =
                assertInstanceOf(ProtosObjectValue.class, rootOptions.indexedAt(0));
        assertTrue(verbose.isFrozen());
        assertEquals(
                Set.of("kind", "key", "spelling", "tokenIndex", "value", "valueTokenIndex"),
                verbose.localSlotsSnapshot().keySet());
        assertEquals("optionOccurrence", stringSlot(verbose, "kind"));
        assertEquals("verbose", stringSlot(verbose, "key"));
        assertEquals("--verbose", stringSlot(verbose, "spelling"));
        assertEquals("0", integerSlot(verbose, "tokenIndex"));
        assertSame(ProtosNullValue.INSTANCE, verbose.localSlotsSnapshot().get("value"));
        assertSame(ProtosNullValue.INSTANCE, verbose.localSlotsSnapshot().get("valueTokenIndex"));

        ProtosObjectValue packageResult = objectSlot(root, "subcommand");
        assertCommandResultShape(packageResult);
        assertEquals("package", stringSlot(packageResult, "name"));
        assertEquals("1", integerSlot(packageResult, "tokenIndex"));
        assertSame(
                ProtosNullValue.INSTANCE,
                packageResult.localSlotsSnapshot().get("endOfOptionsIndex"));

        ProtosObjectValue leaf = objectSlot(packageResult, "subcommand");
        assertCommandResultShape(leaf);
        assertEquals("install", stringSlot(leaf, "name"));
        assertEquals("2", integerSlot(leaf, "tokenIndex"));
        assertEquals("3", integerSlot(leaf, "endOfOptionsIndex"));
        assertSame(ProtosNullValue.INSTANCE, leaf.localSlotsSnapshot().get("subcommand"));

        ProtosArrayValue leafPositionals = arraySlot(leaf, "positionals");
        assertTrue(leafPositionals.isFrozen());
        assertEquals(1, leafPositionals.indexedSize());
        ProtosObjectValue positional =
                assertInstanceOf(
                        ProtosObjectValue.class,
                        leafPositionals.indexedAt(0));
        assertTrue(positional.isFrozen());
        assertEquals(
                Set.of("kind", "key", "value", "tokenIndex"),
                positional.localSlotsSnapshot().keySet());
        assertEquals("positionalOccurrence", stringSlot(positional, "kind"));
        assertEquals("files", stringSlot(positional, "key"));
        assertEquals("-literal", stringSlot(positional, "value"));
        assertEquals("4", integerSlot(positional, "tokenIndex"));
    }

    @Test
    void literalDoubleDashValueRemainsDistinctFromStructuralDelimiter() throws Exception {
        Object result =
                evaluate(
                        """
                        arguments: Array("--output", "--", "--", "tail")
                        arguments.freeze()

                        occurrence: {
                            kind: "optionOccurrence"
                            key: "output"
                            spelling: "--output"
                            tokenIndex: 0
                            value: "--"
                            valueTokenIndex: 1
                        }
                        occurrence.freeze()
                        options: Array(occurrence)
                        options.freeze()

                        positional: {
                            kind: "positionalOccurrence"
                            key: "tail"
                            value: "tail"
                            tokenIndex: 3
                        }
                        positional.freeze()
                        positionals: Array(positional)
                        positionals.freeze()

                        command: {
                            kind: "commandResult"
                            name: "tool"
                            tokenIndex: null
                            endOfOptionsIndex: 2
                            options: options
                            positionals: positionals
                            subcommand: null
                        }
                        command.freeze()

                        parsed: {
                            kind: "parseResult"
                            arguments: arguments
                            command: command
                        }
                        parsed.freeze()

                        (parsed.arguments[1] == "--") &&
                            (parsed.arguments[2] == "--") &&
                            (parsed.command.options[0].value == "--") &&
                            (parsed.command.options[0].valueTokenIndex == 1) &&
                            (parsed.command.endOfOptionsIndex == 2) &&
                            (parsed.command.positionals[0].tokenIndex == 3)
                        """);

        assertSame(ProtosBooleanValue.TRUE, result);
    }

    @Test
    void frozenResultGraphRejectsMutationAtRetainedBoundaries() throws Exception {
        assertSignals(
                """
                arguments: Array("x")
                arguments.freeze()
                commandOptions: Array()
                commandOptions.freeze()
                commandPositionals: Array()
                commandPositionals.freeze()
                command: {
                    kind: "commandResult"
                    name: "tool"
                    tokenIndex: null
                    endOfOptionsIndex: null
                    options: commandOptions
                    positionals: commandPositionals
                    subcommand: null
                }
                command.freeze()
                parsed: {
                    kind: "parseResult"
                    arguments: arguments
                    command: command
                }
                parsed.freeze()
                parsed.arguments[0] = "changed"
                """);

        assertSignals(
                """
                arguments: Array()
                arguments.freeze()
                commandOptions: Array()
                commandOptions.freeze()
                commandPositionals: Array()
                commandPositionals.freeze()
                command: {
                    kind: "commandResult"
                    name: "tool"
                    tokenIndex: null
                    endOfOptionsIndex: null
                    options: commandOptions
                    positionals: commandPositionals
                    subcommand: null
                }
                command.freeze()
                command.subcommand = null
                """);
    }

    private static void assertCommandResultShape(ProtosObjectValue command) {
        assertTrue(command.isFrozen());
        assertEquals(
                Set.of(
                        "kind",
                        "name",
                        "tokenIndex",
                        "endOfOptionsIndex",
                        "options",
                        "positionals",
                        "subcommand"),
                command.localSlotsSnapshot().keySet());
        assertEquals("commandResult", stringSlot(command, "kind"));
        assertTrue(arraySlot(command, "options").isFrozen());
        assertTrue(arraySlot(command, "positionals").isFrozen());
    }

    private static void assertSignals(String source) throws Exception {
        assertThrows(
                ProtosSignalException.class,
                () -> evaluate(source),
                source);
    }

    private static Object evaluate(String source) throws Exception {
        ProtosStandardLibraryModuleResolver resolver =
                new ProtosStandardLibraryModuleResolver(STANDARD_LIBRARY);
        ProtosPrelude prelude = new ProtosCoreBootstrap().bootstrap(CORE, resolver);
        return ProtosTestExecutionSupport.evaluate(source, prelude.newModuleActivation());
    }

    private static ProtosObjectValue objectSlot(ProtosObjectValue object, String name) {
        return assertInstanceOf(
                ProtosObjectValue.class,
                object.localSlotsSnapshot().get(name),
                name);
    }

    private static ProtosArrayValue arraySlot(ProtosObjectValue object, String name) {
        return assertInstanceOf(
                ProtosArrayValue.class,
                object.localSlotsSnapshot().get(name),
                name);
    }

    private static String stringSlot(ProtosObjectValue object, String name) {
        return stringValue(object.localSlotsSnapshot().get(name));
    }

    private static String stringValue(Object value) {
        return assertInstanceOf(ProtosStringValue.class, value).value();
    }

    private static String integerSlot(ProtosObjectValue object, String name) {
        return com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerDecimalText(object.localSlotsSnapshot().get(name));
    }
}
