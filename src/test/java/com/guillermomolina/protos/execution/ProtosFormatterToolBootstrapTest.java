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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.guillermomolina.protos.analysis.ProtosDocumentSnapshot;
import com.guillermomolina.protos.analysis.ProtosSourceLayoutView;
import com.guillermomolina.protos.analysis.ProtosStaticAnalysisCore;
import com.guillermomolina.protos.runtime.ProtosArrayValue;
import com.guillermomolina.protos.runtime.ProtosModuleKey;
import com.guillermomolina.protos.runtime.ProtosObjectValue;
import com.guillermomolina.protos.runtime.ProtosPrelude;
import com.guillermomolina.protos.runtime.ProtosProcessRuntime;
import com.guillermomolina.protos.runtime.ProtosStringValue;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

final class ProtosFormatterToolBootstrapTest {
    private static final Path CORE =
            Path.of("protos", "lib", "core");
    private static final Path STANDARD_LIBRARY =
            Path.of("protos", "lib");
    private static final Path TOOL_ROOT =
            Path.of("protos", "tools", "formatter");

    private static ProtosPolyglotRuntimeHost runtimeHost;

    @BeforeAll
    static void openRuntimeHost() {
        runtimeHost = ProtosPolyglotRuntimeHost.open();
    }

    @AfterAll
    static void closeRuntimeHost() {
        runtimeHost.close();
    }

    @Test
    void loadsExactBundledFormatterInFreshProcessWithoutProjectAuthority()
            throws Exception {
        ProtosStandardLibraryModuleResolver standardLibraryResolver =
                new ProtosStandardLibraryModuleResolver(
                        STANDARD_LIBRARY);

        ProtosBundledToolModuleResolver resolver =
                new ProtosBundledToolModuleResolver(
                        "formatter",
                        TOOL_ROOT,
                        TOOL_ROOT.resolveSibling("shared"),
                        standardLibraryResolver);

        ProtosModuleKey entry =
                resolver.entryModule("Main");

        assertEquals(
                new ProtosModuleKey(
                        "bundled-tool:formatter/Main"),
                entry);

        assertTrue(
                resolver.loadSource(entry)
                        .characters()
                        .contains("toolId: \"TOOL010\""));

        AtomicReference<ProtosProcessRuntime> observed =
                new AtomicReference<>();

        ProtosFormatterToolBootstrap.verifyAvailable(
                CORE,
                TOOL_ROOT,
                standardLibraryResolver,
                observed::set,
                runtimeHost);

        ProtosProcessRuntime process = observed.get();

        assertNotNull(process);
        assertEquals(
                ProtosProcessRuntime.LifecycleState.TERMINATED,
                process.lifecycleState());
        assertTrue(
                process.rootFilesystemForRuntime().isEmpty());
    }

    @Test
    void transfersExactB1ProjectionWithoutExecutingUserSource()
            throws Exception {
        String source =
                "Error().signal()\n"
                        + "(café); [1_000]\n"
                        + "%{\"k\": 0xFF}\n"
                        + "x => x\n"
                        + "(y) => y\n"
                        + "foo() /* tail */ { body() }";

        ProtosSourceLayoutView view =
                new ProtosStaticAnalysisCore()
                        .sourceLayout(
                                new ProtosDocumentSnapshot(
                                        "memory:tool010-bridge",
                                        1L,
                                        source));

        ProtosStandardLibraryModuleResolver standardLibraryResolver =
                new ProtosStandardLibraryModuleResolver(
                        STANDARD_LIBRARY);

        ProtosBundledToolModuleResolver resolver =
                new ProtosBundledToolModuleResolver(
                        "formatter",
                        TOOL_ROOT,
                        TOOL_ROOT.resolveSibling("shared"),
                        standardLibraryResolver);

        ProtosPrelude prelude =
                new ProtosCoreBootstrap().bootstrap(
                        CORE,
                        resolver);

        ProtosObjectValue projected =
                ProtosSourceLayoutToolBridge.project(
                        view,
                        prelude);

        assertNotSame(
                projected,
                ProtosSourceLayoutToolBridge.project(
                        view,
                        prelude));

        assertEquals(
                Set.of(
                        "exactSource",
                        "program",
                        "tokens",
                        "comments",
                        "structuralForms",
                        "sequenceSeparators",
                        "closureForms",
                        "trailingClosures"),
                projected.localSlotsSnapshot().keySet());

        assertEquals(
                source,
                string(slot(projected, "exactSource")));

        ProtosObjectValue program =
                object(slot(projected, "program"));

        assertEquals(
                "SEQUENCE",
                string(slot(program, "kind")));

        assertEquals(
                "program",
                string(
                        slot(
                                object(slot(program, "path")),
                                "root")));

        List<Object> programExpressions =
                array(slot(program, "expressions"))
                        .indexedSnapshot();

        assertEquals(7, programExpressions.size());

        ProtosObjectValue groupedExpression =
                object(programExpressions.get(1));

        assertEquals(
                "GROUP",
                string(slot(groupedExpression, "kind")));

        ProtosObjectValue groupedName =
                object(slot(groupedExpression, "expression"));

        assertEquals(
                "NAME",
                string(slot(groupedName, "kind")));

        assertEquals(
                "café",
                string(slot(groupedName, "rawText")));

        ProtosObjectValue arrayExpression =
                object(programExpressions.get(2));

        assertEquals(
                "ARRAY_CONSTRUCTION",
                string(slot(arrayExpression, "kind")));

        ProtosObjectValue arrayArgument =
                object(
                        array(slot(arrayExpression, "arguments"))
                                .indexedSnapshot()
                                .get(0));

        ProtosObjectValue arrayLiteral =
                object(slot(arrayArgument, "expression"));

        assertEquals(
                "LITERAL",
                string(slot(arrayLiteral, "kind")));

        assertEquals(
                "1_000",
                string(slot(arrayLiteral, "rawText")));

        ProtosObjectValue trailingCall =
                object(programExpressions.get(6));

        assertEquals(
                "CALL",
                string(slot(trailingCall, "kind")));

        List<String> argumentKinds =
                array(slot(trailingCall, "arguments"))
                        .indexedSnapshot()
                        .stream()
                        .map(ProtosFormatterToolBootstrapTest::object)
                        .map(
                                argument ->
                                        object(
                                                slot(
                                                        argument,
                                                        "expression")))
                        .map(
                                argument ->
                                        string(
                                                slot(
                                                        argument,
                                                        "kind")))
                        .toList();

        assertTrue(argumentKinds.contains("CLOSURE"));

        List<String> rawTokens =
                array(slot(projected, "tokens"))
                        .indexedSnapshot()
                        .stream()
                        .map(ProtosFormatterToolBootstrapTest::object)
                        .map(
                                token ->
                                        string(
                                                slot(
                                                        token,
                                                        "rawText")))
                        .toList();

        assertTrue(rawTokens.contains("café"));
        assertTrue(rawTokens.contains("1_000"));
        assertTrue(rawTokens.contains("\"k\""));
        assertTrue(rawTokens.contains("0xFF"));

        List<ProtosObjectValue> structuralForms =
                array(slot(projected, "structuralForms"))
                        .indexedSnapshot()
                        .stream()
                        .map(ProtosFormatterToolBootstrapTest::object)
                        .toList();

        assertTrue(
                structuralForms.stream()
                        .map(
                                form ->
                                        string(
                                                slot(
                                                        form,
                                                        "kind")))
                        .toList()
                        .containsAll(
                                List.of(
                                        "GROUP",
                                        "ARRAY_CONSTRUCTION",
                                        "MAP_CONSTRUCTION",
                                        "CLOSURE_EXPRESSION_BODY",
                                        "CLOSURE_BLOCK_BODY")));

        ProtosObjectValue groupForm =
                structuralForms.stream()
                        .filter(
                                form ->
                                        string(
                                                        slot(
                                                                form,
                                                                "kind"))
                                                .equals("GROUP"))
                        .findFirst()
                        .orElseThrow();

        ProtosObjectValue groupPath =
                object(slot(groupForm, "path"));

        assertEquals(
                "program",
                string(slot(groupPath, "root")));

        ProtosObjectValue firstStep =
                object(
                        array(slot(groupPath, "steps"))
                                .indexedSnapshot()
                                .get(0));

        assertEquals(
                "expression",
                string(slot(firstStep, "role")));

        assertEquals(String.valueOf(1), String.valueOf(Integer.parseInt(com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerDecimalText(slot(firstStep, "index")))));

        List<String> separatorKinds =
                array(slot(projected, "sequenceSeparators"))
                        .indexedSnapshot()
                        .stream()
                        .map(ProtosFormatterToolBootstrapTest::object)
                        .map(
                                separator ->
                                        string(
                                                slot(
                                                        separator,
                                                        "kind")))
                        .toList();

        assertTrue(separatorKinds.contains("SEMICOLON"));
        assertTrue(separatorKinds.contains("LOGICAL_NEWLINE"));

        List<String> closureForms =
                array(slot(projected, "closureForms"))
                        .indexedSnapshot()
                        .stream()
                        .map(ProtosFormatterToolBootstrapTest::object)
                        .map(
                                form ->
                                        string(
                                                slot(
                                                        form,
                                                        "form")))
                        .toList();

        assertTrue(
                closureForms.containsAll(
                        List.of(
                                "BARE",
                                "PARENTHESIZED")));

        List<String> rawComments =
                array(slot(projected, "comments"))
                        .indexedSnapshot()
                        .stream()
                        .map(ProtosFormatterToolBootstrapTest::object)
                        .map(
                                comment ->
                                        string(
                                                slot(
                                                        comment,
                                                        "rawText")))
                        .toList();

        assertEquals(
                List.of("/* tail */"),
                rawComments);

        assertEquals(
                1,
                array(slot(projected, "trailingClosures"))
                        .indexedSize());

        AtomicReference<ProtosProcessRuntime> observed =
                new AtomicReference<>();

        ProtosFormatterToolBootstrap.acceptSourceLayout(
                CORE,
                TOOL_ROOT,
                standardLibraryResolver,
                view,
                observed::set,
                runtimeHost);

        ProtosProcessRuntime process = observed.get();

        assertNotNull(process);
        assertEquals(
                ProtosProcessRuntime.LifecycleState.TERMINATED,
                process.lifecycleState());
        assertTrue(
                process.rootFilesystemForRuntime().isEmpty());
    }

    @Test
    void formatsBasicStructuralPolicyInBundledProtos()
            throws Exception {
        String source =
                "café:1_000;hex:0xFF\n"
                        + "value:foo( 1,2 ).bar[ 3 ]  +   -x\n"
                        + "items:[1, 2,3]\n"
                        + "mapping:%{\"a\":1;\"b\":2}\n"
                        + "(a,b):source\n"
                        + "target=other\n"
                        + "super.move(x,y)";

        String expected =
                "café: 1_000; hex: 0xFF\n"
                        + "value: foo(1, 2).bar[3] + -x\n"
                        + "items: [1, 2, 3]\n"
                        + "mapping: %{ \"a\": 1; \"b\": 2 }\n"
                        + "(a, b): source\n"
                        + "target = other\n"
                        + "super.move(x, y)";

        ProtosSourceLayoutView view =
                new ProtosStaticAnalysisCore()
                        .sourceLayout(
                                new ProtosDocumentSnapshot(
                                        "memory:tool010-structural",
                                        2L,
                                        source));

        AtomicReference<ProtosProcessRuntime> observed =
                new AtomicReference<>();

        String formatted =
                ProtosFormatterToolBootstrap.formatStructural(
                        CORE,
                        TOOL_ROOT,
                        new ProtosStandardLibraryModuleResolver(
                                STANDARD_LIBRARY),
                        view,
                        observed::set,
                        runtimeHost);

        assertEquals((expected) + "\n", formatted);

        ProtosProcessRuntime process = observed.get();

        assertNotNull(process);
        assertEquals(
                ProtosProcessRuntime.LifecycleState.TERMINATED,
                process.lifecycleState());
        assertTrue(
                process.rootFilesystemForRuntime().isEmpty());
    }

    @Test
    void formatsObjectsClosuresAndTrailingClosuresInBundledProtos()
            throws Exception {
        String source =
                "object:{...base;name:\"Rex\"}\n"
                        + "dog:animal{name:\"Rex\"\nage:2}\n"
                        + "bare:x=>x+1\n"
                        + "paren:(y)=>y*2\n"
                        + "multi:(a,b=2,...rest)=>{result:a+b\n^result}\n"
                        + "run(1){first\nsecond}\n"
                        + "noop:()=>{}\n"
                        + "mapping:%{\"a\":1\n\"b\":2}";

        String expected =
                "object: { ...base; name: \"Rex\" }\n"
                        + "dog: animal {\n"
                        + "    name: \"Rex\"\n"
                        + "    age: 2\n"
                        + "}\n"
                        + "bare: x => x + 1\n"
                        + "paren: (y) => y * 2\n"
                        + "multi: (a, b = 2, ...rest) => {\n"
                        + "    result: a + b\n"
                        + "    ^result\n"
                        + "}\n"
                        + "run(1) {\n"
                        + "    first\n"
                        + "    second\n"
                        + "}\n"
                        + "noop: () => {}\n"
                        + "mapping: %{\n"
                        + "    \"a\": 1\n"
                        + "    \"b\": 2\n"
                        + "}";

        ProtosSourceLayoutView view =
                new ProtosStaticAnalysisCore()
                        .sourceLayout(
                                new ProtosDocumentSnapshot(
                                        "memory:tool010-structural-blocks",
                                        3L,
                                        source));

        AtomicReference<ProtosProcessRuntime> observed =
                new AtomicReference<>();

        String formatted =
                ProtosFormatterToolBootstrap.formatStructural(
                        CORE,
                        TOOL_ROOT,
                        new ProtosStandardLibraryModuleResolver(
                                STANDARD_LIBRARY),
                        view,
                        observed::set,
                        runtimeHost);

        assertEquals((expected) + "\n", formatted);

        ProtosProcessRuntime process = observed.get();

        assertNotNull(process);
        assertEquals(
                ProtosProcessRuntime.LifecycleState.TERMINATED,
                process.lifecycleState());
        assertTrue(
                process.rootFilesystemForRuntime().isEmpty());
    }

    @Test
    void wrapsBinaryAndMemberChainsAtSoftHundredColumns()
            throws Exception {
        String binaryLeft = "a".repeat(60);
        String binaryRight = "b".repeat(50);
        String memberReceiver = "r".repeat(96);

        String source =
                binaryLeft
                        + "+"
                        + binaryRight
                        + "\n"
                        + memberReceiver
                        + ".alpha.beta";

        String expected =
                binaryLeft
                        + " +\n"
                        + "    "
                        + binaryRight
                        + "\n"
                        + memberReceiver
                        + "\n"
                        + "    .alpha\n"
                        + "    .beta";

        ProtosSourceLayoutView view =
                new ProtosStaticAnalysisCore()
                        .sourceLayout(
                                new ProtosDocumentSnapshot(
                                        "memory:tool010-soft-width",
                                        4L,
                                        source));

        AtomicReference<ProtosProcessRuntime> observed =
                new AtomicReference<>();

        String formatted =
                ProtosFormatterToolBootstrap.formatStructural(
                        CORE,
                        TOOL_ROOT,
                        new ProtosStandardLibraryModuleResolver(
                                STANDARD_LIBRARY),
                        view,
                        observed::set,
                        runtimeHost);

        assertEquals((expected) + "\n", formatted);

        ProtosProcessRuntime process = observed.get();

        assertNotNull(process);
        assertEquals(
                ProtosProcessRuntime.LifecycleState.TERMINATED,
                process.lifecycleState());
        assertTrue(
                process.rootFilesystemForRuntime().isEmpty());
    }

    @Test
    void wrapsDelimitedListsAtSoftHundredColumns()
            throws Exception {
        String receiver = "r".repeat(96);
        String callLeft = "a".repeat(30);
        String callRight = "b".repeat(30);

        String arrayLeft = "c".repeat(50);
        String arrayRight = "d".repeat(50);

        String parameterLeft = "e".repeat(50);
        String parameterRight = "f".repeat(50);

        String source =
                receiver
                        + "("
                        + callLeft
                        + ","
                        + callRight
                        + ")\n"
                        + "["
                        + arrayLeft
                        + ","
                        + arrayRight
                        + "]\n"
                        + "("
                        + parameterLeft
                        + ","
                        + parameterRight
                        + ")=>"
                        + parameterLeft;

        String expected =
                receiver
                        + "(\n"
                        + "    "
                        + callLeft
                        + ",\n"
                        + "    "
                        + callRight
                        + "\n"
                        + ")\n"
                        + "[\n"
                        + "    "
                        + arrayLeft
                        + ",\n"
                        + "    "
                        + arrayRight
                        + "\n"
                        + "]\n"
                        + "(\n"
                        + "    "
                        + parameterLeft
                        + ",\n"
                        + "    "
                        + parameterRight
                        + "\n"
                        + ") => "
                        + parameterLeft;

        ProtosSourceLayoutView view =
                new ProtosStaticAnalysisCore()
                        .sourceLayout(
                                new ProtosDocumentSnapshot(
                                        "memory:tool010-delimited-width",
                                        5L,
                                        source));

        AtomicReference<ProtosProcessRuntime> observed =
                new AtomicReference<>();

        String formatted =
                ProtosFormatterToolBootstrap.formatStructural(
                        CORE,
                        TOOL_ROOT,
                        new ProtosStandardLibraryModuleResolver(
                                STANDARD_LIBRARY),
                        view,
                        observed::set,
                        runtimeHost);

        assertEquals((expected) + "\n", formatted);

        ProtosProcessRuntime process = observed.get();

        assertNotNull(process);
        assertEquals(
                ProtosProcessRuntime.LifecycleState.TERMINATED,
                process.lifecycleState());
        assertTrue(
                process.rootFilesystemForRuntime().isEmpty());
    }

    @Test
    void wrapsColonAssignmentAndClosureArrowAtSoftHundredColumns()
            throws Exception {
        String slotTarget = "s".repeat(96);
        String assignmentTarget = "a".repeat(96);
        String right = "valuevalue";

        String firstTarget = "m".repeat(45);
        String secondTarget = "n".repeat(45);

        String closureParameter = "p".repeat(96);

        String source =
                slotTarget
                        + ":"
                        + right
                        + "\n"
                        + assignmentTarget
                        + "="
                        + right
                        + "\n"
                        + "("
                        + firstTarget
                        + ","
                        + secondTarget
                        + "):"
                        + right
                        + "\n"
                        + closureParameter
                        + "=>"
                        + right;

        String expected =
                slotTarget
                        + ":\n"
                        + "    "
                        + right
                        + "\n"
                        + assignmentTarget
                        + " =\n"
                        + "    "
                        + right
                        + "\n"
                        + "("
                        + firstTarget
                        + ", "
                        + secondTarget
                        + "):\n"
                        + "    "
                        + right
                        + "\n"
                        + closureParameter
                        + " =>\n"
                        + "    "
                        + right;

        ProtosSourceLayoutView view =
                new ProtosStaticAnalysisCore()
                        .sourceLayout(
                                new ProtosDocumentSnapshot(
                                        "memory:tool010-operator-width",
                                        6L,
                                        source));

        AtomicReference<ProtosProcessRuntime> observed =
                new AtomicReference<>();

        String formatted =
                ProtosFormatterToolBootstrap.formatStructural(
                        CORE,
                        TOOL_ROOT,
                        new ProtosStandardLibraryModuleResolver(
                                STANDARD_LIBRARY),
                        view,
                        observed::set,
                        runtimeHost);

        assertEquals((expected) + "\n", formatted);

        ProtosProcessRuntime process = observed.get();

        assertNotNull(process);
        assertEquals(
                ProtosProcessRuntime.LifecycleState.TERMINATED,
                process.lifecycleState());
        assertTrue(
                process.rootFilesystemForRuntime().isEmpty());
    }

    @Test
    void preservesExactLexicalPayloadSpelling()
            throws Exception {
        String triple =
                "\"\"\"alpha\r\n\tbeta\n  gamma\"\"\"";

        String source =
                "'a'\n"
                        + "\"a\"\n"
                        + "\"\\\\n\"\n"
                        + "\"\\\\u{0A}\"\n"
                        + "1000\n"
                        + "1_000\n"
                        + "0xff\n"
                        + "0xFF\n"
                        + "2e3\n"
                        + "2E3\n"
                        + "café\n"
                        + triple;

        ProtosSourceLayoutView view =
                new ProtosStaticAnalysisCore()
                        .sourceLayout(
                                new ProtosDocumentSnapshot(
                                        "memory:tool010-lexical-payload",
                                        7L,
                                        source));

        AtomicReference<ProtosProcessRuntime> observed =
                new AtomicReference<>();

        String formatted =
                ProtosFormatterToolBootstrap.formatStructural(
                        CORE,
                        TOOL_ROOT,
                        new ProtosStandardLibraryModuleResolver(
                                STANDARD_LIBRARY),
                        view,
                        observed::set,
                        runtimeHost);

        assertEquals((source) + "\n", formatted);

        ProtosProcessRuntime process = observed.get();

        assertNotNull(process);
        assertEquals(
                ProtosProcessRuntime.LifecycleState.TERMINATED,
                process.lifecycleState());
        assertTrue(
                process.rootFilesystemForRuntime().isEmpty());
    }

    @Test
    void preservesOwnLineAndEndOfLineSequenceComments()
            throws Exception {
        String source =
                "a:1 // eol  keep\ttext\n"
                        + "// own  keep\ttext\n"
                        + "b:2";

        String expected =
                "a: 1 // eol  keep\ttext\n"
                        + "// own  keep\ttext\n"
                        + "b: 2";

        ProtosSourceLayoutView view =
                new ProtosStaticAnalysisCore()
                        .sourceLayout(
                                new ProtosDocumentSnapshot(
                                        "memory:tool010-sequence-comments",
                                        8L,
                                        source));

        AtomicReference<ProtosProcessRuntime> observed =
                new AtomicReference<>();

        String formatted =
                ProtosFormatterToolBootstrap.formatStructural(
                        CORE,
                        TOOL_ROOT,
                        new ProtosStandardLibraryModuleResolver(
                                STANDARD_LIBRARY),
                        view,
                        observed::set,
                        runtimeHost);

        assertEquals((expected) + "\n", formatted);

        ProtosProcessRuntime process = observed.get();

        assertNotNull(process);
        assertEquals(
                ProtosProcessRuntime.LifecycleState.TERMINATED,
                process.lifecycleState());
        assertTrue(
                process.rootFilesystemForRuntime().isEmpty());
    }

    @Test
    void preservesMultilineCommentBeforeTrailingClosure()
            throws Exception {
        String source =
                "foo() /* comment\n"
                        + "still  comment\t*/ {body()}";

        String expected =
                "foo() /* comment\n"
                        + "still  comment\t*/ { body() }";

        ProtosSourceLayoutView view =
                new ProtosStaticAnalysisCore()
                        .sourceLayout(
                                new ProtosDocumentSnapshot(
                                        "memory:tool010-trailing-comment",
                                        9L,
                                        source));

        AtomicReference<ProtosProcessRuntime> observed =
                new AtomicReference<>();

        String formatted =
                ProtosFormatterToolBootstrap.formatStructural(
                        CORE,
                        TOOL_ROOT,
                        new ProtosStandardLibraryModuleResolver(
                                STANDARD_LIBRARY),
                        view,
                        observed::set,
                        runtimeHost);

        assertEquals((expected) + "\n", formatted);

        ProtosProcessRuntime process = observed.get();

        assertNotNull(process);
        assertEquals(
                ProtosProcessRuntime.LifecycleState.TERMINATED,
                process.lifecycleState());
        assertTrue(
                process.rootFilesystemForRuntime().isEmpty());
    }

    @Test
    void preservesEmbeddedBetweenTokensComment()
            throws Exception {
        String source =
                "foo /* embedded  keep\ttext */+bar";

        String expected =
                "foo /* embedded  keep\ttext */ + bar";

        ProtosSourceLayoutView view =
                new ProtosStaticAnalysisCore()
                        .sourceLayout(
                                new ProtosDocumentSnapshot(
                                        "memory:tool010-embedded-comment",
                                        10L,
                                        source));

        AtomicReference<ProtosProcessRuntime> observed =
                new AtomicReference<>();

        String formatted =
                ProtosFormatterToolBootstrap.formatStructural(
                        CORE,
                        TOOL_ROOT,
                        new ProtosStandardLibraryModuleResolver(
                                STANDARD_LIBRARY),
                        view,
                        observed::set,
                        runtimeHost);

        assertEquals((expected) + "\n", formatted);

        ProtosProcessRuntime process = observed.get();

        assertNotNull(process);
        assertEquals(
                ProtosProcessRuntime.LifecycleState.TERMINATED,
                process.lifecycleState());
        assertTrue(
                process.rootFilesystemForRuntime().isEmpty());
    }

    @Test
    void collapsesBlankLineRunsToExactlyOne()
            throws Exception {
        String source =
                "a\n"
                        + "\n"
                        + "\n"
                        + "b\n"
                        + "c";

        String expected =
                "a\n"
                        + "\n"
                        + "b\n"
                        + "c";

        ProtosSourceLayoutView view =
                new ProtosStaticAnalysisCore()
                        .sourceLayout(
                                new ProtosDocumentSnapshot(
                                        "memory:tool010-blank-lines",
                                        11L,
                                        source));

        AtomicReference<ProtosProcessRuntime> observed =
                new AtomicReference<>();

        String formatted =
                ProtosFormatterToolBootstrap.formatStructural(
                        CORE,
                        TOOL_ROOT,
                        new ProtosStandardLibraryModuleResolver(
                                STANDARD_LIBRARY),
                        view,
                        observed::set,
                        runtimeHost);

        assertEquals((expected) + "\n", formatted);

        ProtosProcessRuntime process = observed.get();

        assertNotNull(process);
        assertEquals(
                ProtosProcessRuntime.LifecycleState.TERMINATED,
                process.lifecycleState());
        assertTrue(
                process.rootFilesystemForRuntime().isEmpty());
    }

    @Test
    void preservesBlankLineSideAroundOwnLineComments()
            throws Exception {
        String source =
                "a\n"
                        + "\n"
                        + "// before\n"
                        + "b\n"
                        + "// after\n"
                        + "\n"
                        + "c\n"
                        + "\n"
                        + "// both\n"
                        + "\n"
                        + "d";

        ProtosSourceLayoutView view =
                new ProtosStaticAnalysisCore()
                        .sourceLayout(
                                new ProtosDocumentSnapshot(
                                        "memory:tool010-comment-blank-lines",
                                        12L,
                                        source));

        AtomicReference<ProtosProcessRuntime> observed =
                new AtomicReference<>();

        String formatted =
                ProtosFormatterToolBootstrap.formatStructural(
                        CORE,
                        TOOL_ROOT,
                        new ProtosStandardLibraryModuleResolver(
                                STANDARD_LIBRARY),
                        view,
                        observed::set,
                        runtimeHost);

        assertEquals((source) + "\n", formatted);

        ProtosProcessRuntime process = observed.get();

        assertNotNull(process);
        assertEquals(
                ProtosProcessRuntime.LifecycleState.TERMINATED,
                process.lifecycleState());
        assertTrue(
                process.rootFilesystemForRuntime().isEmpty());
    }

    @Test
    void canonicalizesWholeDocumentWhitespaceAndFinalLf()
            throws Exception {
        String lexical =
                "\"\"\"alpha\r\n\tbeta\n  gamma\"\"\"";

        String source =
                "\r\n"
                        + "   \t\r\n"
                        + "text:" + lexical + " \t\r\n"
                        + "\r\n"
                        + "\r\n"
                        + "value:2\t \r\n"
                        + "   \t\r\n"
                        + "\r\n";

        String expected =
                "text: " + lexical + "\n"
                        + "\n"
                        + "value: 2";

        ProtosSourceLayoutView view =
                new ProtosStaticAnalysisCore()
                        .sourceLayout(
                                new ProtosDocumentSnapshot(
                                        "memory:tool010-document-contract",
                                        13L,
                                        source));

        AtomicReference<ProtosProcessRuntime> observed =
                new AtomicReference<>();

        String formatted =
                ProtosFormatterToolBootstrap.formatStructural(
                        CORE,
                        TOOL_ROOT,
                        new ProtosStandardLibraryModuleResolver(
                                STANDARD_LIBRARY),
                        view,
                        observed::set,
                        runtimeHost);

        assertEquals((expected) + "\n", formatted);

        ProtosProcessRuntime process = observed.get();

        assertNotNull(process);
        assertEquals(
                ProtosProcessRuntime.LifecycleState.TERMINATED,
                process.lifecycleState());
        assertTrue(
                process.rootFilesystemForRuntime().isEmpty());
    }

    @Test
    void reparsesDeterministicallyAndIsExactlyIdempotent()
            throws Exception {
        String source =
                "alpha:1\n"
                        + "\n"
                        + "// keep  text\t\n"
                        + "beta:foo /* embedded */+bar\n"
                        + "gamma:[1,2,3]\n"
                        + "delta:(x)=>x+1";

        ProtosSourceLayoutView originalView =
                new ProtosStaticAnalysisCore()
                        .sourceLayout(
                                new ProtosDocumentSnapshot(
                                        "memory:tool010-correctness-original",
                                        14L,
                                        source));

        String first =
                ProtosFormatterToolBootstrap.formatStructural(
                        CORE,
                        TOOL_ROOT,
                        new ProtosStandardLibraryModuleResolver(
                                STANDARD_LIBRARY),
                        originalView,
                        ignored -> {},
                        runtimeHost);

        String repeated =
                ProtosFormatterToolBootstrap.formatStructural(
                        CORE,
                        TOOL_ROOT,
                        new ProtosStandardLibraryModuleResolver(
                                STANDARD_LIBRARY),
                        originalView,
                        ignored -> {},
                        runtimeHost);

        assertEquals(first, repeated);
        assertTrue(first.endsWith("\n"));

        ProtosSourceLayoutView formattedView =
                new ProtosStaticAnalysisCore()
                        .sourceLayout(
                                new ProtosDocumentSnapshot(
                                        "memory:tool010-correctness-formatted",
                                        15L,
                                        first));

        String second =
                ProtosFormatterToolBootstrap.formatStructural(
                        CORE,
                        TOOL_ROOT,
                        new ProtosStandardLibraryModuleResolver(
                                STANDARD_LIBRARY),
                        formattedView,
                        ignored -> {},
                        runtimeHost);

        assertEquals(first, second);
    }

    @Test
    void preservesPositionIndependentSourceProjection()
            throws Exception {
        String[] sources = {
            "group:(1+2)\n"
                    + "array:[1_000,0xFF]\n"
                    + "mapping:%{\"a\":\"\\\\n\"}\n"
                    + "single:'a'\n"
                    + "double:\"a\"\n"
                    + "unicodeEscape:\"\\\\u{0A}\"\n"
                    + "café:2E3",

            "bare:x=>x+1\n"
                    + "paren:(y)=>y*2\n"
                    + "block:(z)=>{z}",

            "a:1 // eol  keep\ttext\n"
                    + "// own  keep\ttext\n"
                    + "b:2",

            "foo /* embedded  keep\ttext */+bar",

            "foo() /* comment\n"
                    + "still  comment\t*/ {body()}"
        };

        long version = 20L;

        for (String source : sources) {
            ProtosSourceLayoutView originalView =
                    new ProtosStaticAnalysisCore()
                            .sourceLayout(
                                    new ProtosDocumentSnapshot(
                                            "memory:tool010-preservation-original",
                                            version++,
                                            source));

            ProtosSourceLayoutView.SourcePreservationProjection before =
                    originalView.preservationProjection();

            String formatted =
                    ProtosFormatterToolBootstrap.formatStructural(
                            CORE,
                            TOOL_ROOT,
                            new ProtosStandardLibraryModuleResolver(
                                    STANDARD_LIBRARY),
                            originalView,
                            ignored -> {},
                            runtimeHost);

            ProtosSourceLayoutView formattedView =
                    new ProtosStaticAnalysisCore()
                            .sourceLayout(
                                    new ProtosDocumentSnapshot(
                                            "memory:tool010-preservation-formatted",
                                            version++,
                                            formatted));

            ProtosSourceLayoutView.SourcePreservationProjection after =
                    formattedView.preservationProjection();

            assertSourcePreservationInvariants(
                    before,
                    after,
                    source,
                    formatted);
        }
    }

    private static void assertSourcePreservationInvariants(
            ProtosSourceLayoutView.SourcePreservationProjection before,
            ProtosSourceLayoutView.SourcePreservationProjection after,
            String source,
            String formatted) {
        String context =
                "source-preservation invariant changed"
                        + "\nSOURCE:\n"
                        + source
                        + "\nFORMATTED:\n"
                        + formatted;

        assertEquals(
                before.tokens()
                        .stream()
                        .filter(
                                token ->
                                        !"NEWLINE".equals(
                                                token.type().name()))
                        .toList(),
                after.tokens()
                        .stream()
                        .filter(
                                token ->
                                        !"NEWLINE".equals(
                                                token.type().name()))
                        .toList(),
                context);

        assertEquals(
                before.comments().size(),
                after.comments().size(),
                context);

        for (int index = 0;
                index < before.comments().size();
                index++) {
            ProtosSourceLayoutView.CommentProjection left =
                    before.comments().get(index);
            ProtosSourceLayoutView.CommentProjection right =
                    after.comments().get(index);

            assertEquals(
                    left.kind(),
                    right.kind(),
                    context);
            assertEquals(
                    left.rawText(),
                    right.rawText(),
                    context);
            assertEquals(
                    left.placement(),
                    right.placement(),
                    context);
            assertEquals(
                    left.preceding(),
                    right.preceding(),
                    context);
            assertEquals(
                    left.following(),
                    right.following(),
                    context);
            assertEquals(
                    left.boundaryKind(),
                    right.boundaryKind(),
                    context);
            assertEquals(
                    left.sequenceSeparatorKind(),
                    right.sequenceSeparatorKind(),
                    context);
        }

        assertEquals(
                before.structuralForms(),
                after.structuralForms(),
                context);

        assertEquals(
                before.sequenceSeparators().size(),
                after.sequenceSeparators().size(),
                context);

        for (int index = 0;
                index < before.sequenceSeparators().size();
                index++) {
            ProtosSourceLayoutView.SequenceSeparatorProjection left =
                    before.sequenceSeparators().get(index);
            ProtosSourceLayoutView.SequenceSeparatorProjection right =
                    after.sequenceSeparators().get(index);

            assertEquals(
                    left.context(),
                    right.context(),
                    context);
            assertEquals(
                    left.preceding(),
                    right.preceding(),
                    context);
            assertEquals(
                    left.kind(),
                    right.kind(),
                    context);
            assertEquals(
                    left.following(),
                    right.following(),
                    context);
        }

        assertEquals(
                before.closureForms(),
                after.closureForms(),
                context);

        assertEquals(
                before.trailingClosures(),
                after.trailingClosures(),
                context);
    }

    @Test
    void preservesCanonicalSemanticAstWithoutExecutingSource()
            throws Exception {
        String[] sources = {
            "group:(1+2)\n"
                    + "array:[1_000,0xFF]\n"
                    + "mapping:%{\"a\":\"\\\\n\"}\n"
                    + "café:2E3",

            "bare:x=>x+1\n"
                    + "paren:(y)=>y*2\n"
                    + "block:(z)=>{z}",

            "dog:animal{\n"
                    + "name:\"Rex\"\n"
                    + "age:2\n"
                    + "}",

            "a:1;b:2",

            "foo /* embedded */+bar",

            "foo() /* trailing\n"
                    + "comment */ {body()}"
        };

        long version = 40L;

        for (String source : sources) {
            ProtosSourceLayoutView originalView =
                    new ProtosStaticAnalysisCore()
                            .sourceLayout(
                                    new ProtosDocumentSnapshot(
                                            "memory:tool010-semantic-original",
                                            version++,
                                            source));

            String before =
                    canonicalSemanticProjection(
                            new com.guillermomolina.protos.semantic
                                    .Canonicalizer()
                                    .canonicalize(
                                            originalView.program()));

            String formatted =
                    ProtosFormatterToolBootstrap.formatStructural(
                            CORE,
                            TOOL_ROOT,
                            new ProtosStandardLibraryModuleResolver(
                                    STANDARD_LIBRARY),
                            originalView,
                            ignored -> {},
                            runtimeHost);

            ProtosSourceLayoutView formattedView =
                    new ProtosStaticAnalysisCore()
                            .sourceLayout(
                                    new ProtosDocumentSnapshot(
                                            "memory:tool010-semantic-formatted",
                                            version++,
                                            formatted));

            String after =
                    canonicalSemanticProjection(
                            new com.guillermomolina.protos.semantic
                                    .Canonicalizer()
                                    .canonicalize(
                                            formattedView.program()));

            assertEquals(
                    before,
                    after,
                    () ->
                            "canonical semantic AST changed"
                                    + "\nSOURCE:\n"
                                    + source
                                    + "\nFORMATTED:\n"
                                    + formatted);
        }
    }

    private static String canonicalSemanticProjection(
            Object value)
            throws ReflectiveOperationException {
        if (value == null) {
            return "null";
        }

        if (value instanceof String string) {
            return "string("
                    + string.length()
                    + "):"
                    + string;
        }

        if (value instanceof Boolean
                || value instanceof Number) {
            return value.getClass().getName()
                    + ":"
                    + value;
        }

        if (value instanceof Enum<?> enumValue) {
            return "enum:"
                    + enumValue.getDeclaringClass().getName()
                    + ":"
                    + enumValue.name();
        }

        if (value instanceof java.util.Optional<?> optional) {
            if (optional.isEmpty()) {
                return "optional:empty";
            }

            return "optional:"
                    + canonicalSemanticProjection(
                            optional.orElseThrow());
        }

        if (value instanceof java.util.List<?> list) {
            StringBuilder result =
                    new StringBuilder("list[");

            for (Object element : list) {
                String projected =
                        canonicalSemanticProjection(
                                element);

                result.append(projected.length())
                        .append(':')
                        .append(projected);
            }

            return result.append(']')
                    .toString();
        }

        Class<?> type = value.getClass();

        if (type.isRecord()) {
            StringBuilder result =
                    new StringBuilder("record:")
                            .append(type.getName())
                            .append('{');

            for (java.lang.reflect.RecordComponent component
                    : type.getRecordComponents()) {
                if ("span".equals(component.getName())
                        || component.getType().getName().equals(
                                "com.guillermomolina.protos.source."
                                        + "SourceSpan")) {
                    continue;
                }

                Object componentValue =
                        component.getAccessor()
                                .invoke(value);

                String projected =
                        canonicalSemanticProjection(
                                componentValue);

                result.append(component.getName())
                        .append('=')
                        .append(projected.length())
                        .append(':')
                        .append(projected)
                        .append(';');
            }

            return result.append('}')
                    .toString();
        }

        throw new AssertionError(
                "Unsupported canonical semantic value: "
                        + type.getName());
    }

    @Test
    void wholeDocumentFormattingFailsClosedForInvalidSource()
            throws Exception {
        AtomicReference<ProtosProcessRuntime> validProcess =
                new AtomicReference<>();

        ProtosWholeDocumentFormatter.Result valid =
                ProtosWholeDocumentFormatter.format(
                        CORE,
                        TOOL_ROOT,
                        new ProtosStandardLibraryModuleResolver(
                                STANDARD_LIBRARY),
                        new ProtosDocumentSnapshot(
                                "memory:tool010-whole-document-valid",
                                60L,
                                "value:1"),
                        validProcess::set,
                        runtimeHost);

        assertEquals(
                ProtosWholeDocumentFormatter.Status.SUCCESS,
                valid.status());
        assertEquals(
                "value: 1\n",
                valid.source());
        assertTrue(
                valid.reason().isEmpty());
        assertNotNull(
                validProcess.get());

        String[] rejected = {
            "foo(",
            "value: )"
        };

        long version = 61L;

        for (String source : rejected) {
            AtomicReference<ProtosProcessRuntime> observed =
                    new AtomicReference<>();

            ProtosWholeDocumentFormatter.Result result =
                    ProtosWholeDocumentFormatter.format(
                            CORE,
                            TOOL_ROOT,
                            new ProtosStandardLibraryModuleResolver(
                                    STANDARD_LIBRARY),
                            new ProtosDocumentSnapshot(
                                    "memory:tool010-whole-document-invalid",
                                    version++,
                                    source),
                            observed::set,
                            runtimeHost);

            assertEquals(
                    ProtosWholeDocumentFormatter.Status.FAILURE,
                    result.status());
            assertEquals(
                    source,
                    result.source());
            assertTrue(
                    result.reason().isPresent());

            org.junit.jupiter.api.Assertions.assertNull(
                    observed.get());
        }
    }

    private static Object slot(
            ProtosObjectValue object,
            String name) {
        return object.readLocalSlot(name).orElseThrow();
    }

    private static ProtosObjectValue object(Object value) {
        return assertInstanceOf(
                ProtosObjectValue.class,
                value);
    }

    private static ProtosArrayValue array(Object value) {
        return assertInstanceOf(
                ProtosArrayValue.class,
                value);
    }

    private static String string(Object value) {
        return assertInstanceOf(
                        ProtosStringValue.class,
                        value)
                .value();
    }
}
