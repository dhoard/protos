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

package com.guillermomolina.protos.documentation;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.guillermomolina.protos.documentation.ProtosDocumentationModel.StandardModuleIdentity;
import com.guillermomolina.protos.documentation.ProtosDocumentationModel.Symbol;
import com.guillermomolina.protos.lexer.ProtosLexer;
import com.guillermomolina.protos.lexer.ProtosLexer.LineCommentOccurrence;
import com.guillermomolina.protos.lexer.TokenOccurrence;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProtosStandardLibraryDocumentationExtractorTest {
    private static final String REVISION =
            "0123456789abcdef0123456789abcdef01234567";

    @TempDir
    Path temp;

    @Test
    void lineCommentObservationIsOptInAndTokenStable() {
        String source = "value: 1 // ordinary\n//! module docs\n";
        List<TokenOccurrence> ordinary = new ProtosLexer(source).tokenizeOccurrences();

        List<LineCommentOccurrence> comments = new ArrayList<>();
        List<TokenOccurrence> observed =
                new ProtosLexer(source).tokenizeOccurrences(comments::add);

        assertEquals(ordinary, observed);
        assertEquals(2, comments.size());
        assertEquals(" ordinary", comments.get(0).text());
        assertEquals("! module docs", comments.get(1).text());
        assertEquals(source.indexOf("// ordinary"), comments.get(0).span().startOffset());
    }

    @Test
    void extractsOnlyImportableStandardLibrarySurfaceWithCoverage() throws Exception {
        writeModule(
                "collections/Set",
                """
                // legal preamble
                //! Set utilities.
                //!
                //! Canonical module documentation.
                /// Creates a set containing `elements`.
                call: (...elements) => {
                    elements
                }
                helper: 1
                """);
        writeCore(
                "Object",
                """
                ignored: 1
                """);

        ProtosStandardLibraryDocumentationExtractor.Extraction extraction =
                ProtosStandardLibraryDocumentationExtractor.extract(temp, REVISION);

        assertEquals(1, extraction.artifact().modules().size());
        assertEquals(2, extraction.artifact().symbols().size());
        assertEquals(
                "std:collections/Set",
                ((StandardModuleIdentity) extraction.artifact().modules().get(0).identity()).name());
        assertEquals(
                "Set utilities.\n\nCanonical module documentation.",
                extraction.artifact().modules().get(0).documentation());

        Symbol call = symbol(extraction, "call");
        assertEquals("Creates a set containing `elements`.", call.documentation());
        assertNotNull(call.callable());
        assertEquals(List.of(), call.callable().parameters());
        assertEquals("elements", call.callable().restParameter());
        assertEquals(6, call.source().range().startLine());
        assertEquals(1, call.source().range().startColumn());

        Symbol helper = symbol(extraction, "helper");
        assertNull(helper.documentation());
        assertNull(helper.callable());

        ProtosStandardLibraryDocumentationExtractor.Coverage coverage =
                extraction.coverage();
        assertEquals(1, coverage.moduleCount());
        assertEquals(1, coverage.documentedModuleCount());
        assertEquals(List.of(), coverage.missingModuleDocumentation());
        assertEquals(2, coverage.symbolCount());
        assertEquals(1, coverage.documentedSymbolCount());
        assertEquals(
                List.of("std:collections/Set::helper"),
                coverage.missingSymbolDocumentation());

        byte[] first = ProtosDocumentationJson.serialize(extraction.artifact());
        byte[] second = ProtosDocumentationJson.serialize(
                ProtosStandardLibraryDocumentationExtractor.extract(temp, REVISION).artifact());
        assertArrayEquals(first, second);
        String json = new String(first, StandardCharsets.UTF_8);
        assertTrue(json.contains("\"name\":\"std:collections/Set\""));
        assertTrue(json.contains("\"documentation\":null"));
        assertFalse(json.contains("std:core"));
    }

    @Test
    void directlyRemovedPrivateModuleSlotIsNotPublished() throws Exception {
        writeModule(
                "collections/Private",
                """
                //! Private helper fixture.
                _helper: 1
                /// Published Closure using the captured helper.
                published: () => { _helper }
                context.removeSlot("_helper")
                """);

        ProtosStandardLibraryDocumentationExtractor.Extraction extraction =
                ProtosStandardLibraryDocumentationExtractor.extract(temp, REVISION);

        assertEquals(1, extraction.artifact().symbols().size());
        assertEquals("published",
                extraction.artifact().symbols().get(0).identity().slotName());
        assertEquals(List.of(), extraction.coverage().missingSymbolDocumentation());
    }

    @Test
    void nestedRemovalDoesNotHideTopLevelSlot() throws Exception {
        writeModule(
                "collections/NestedRemoval",
                """
                //! Nested removal fixture.
                /// Documented helper.
                helper: 1
                /// Deferred removal, not executed at module initialization.
                deferred: () => { context.removeSlot("helper") }
                """);

        ProtosStandardLibraryDocumentationExtractor.Extraction extraction =
                ProtosStandardLibraryDocumentationExtractor.extract(temp, REVISION);

        assertEquals(2, extraction.artifact().symbols().size());
        assertEquals(List.of(), extraction.coverage().missingSymbolDocumentation());
    }

    @Test
    void recreationAfterDirectRemovalIsPublished() throws Exception {
        writeModule(
                "collections/Recreated",
                """
                //! Recreated binding fixture.
                helper: 1
                context.removeSlot("helper")
                /// The final public binding.
                helper: 2
                """);

        ProtosStandardLibraryDocumentationExtractor.Extraction extraction =
                ProtosStandardLibraryDocumentationExtractor.extract(temp, REVISION);

        assertEquals(1, extraction.artifact().symbols().size());
        assertEquals("helper",
                extraction.artifact().symbols().get(0).identity().slotName());
        assertEquals("The final public binding.",
                extraction.artifact().symbols().get(0).documentation());
        assertEquals(List.of(), extraction.coverage().missingSymbolDocumentation());
    }

    @Test
    void groupedTopLevelSlotCanReceiveDocumentation() throws Exception {
        writeModule(
                "collections/Grouped",
                """
                /// Calls with `value`.
                (call: (value) => value)
                """);

        ProtosStandardLibraryDocumentationExtractor.Extraction extraction =
                ProtosStandardLibraryDocumentationExtractor.extract(temp, REVISION);

        Symbol call = symbol(extraction, "call");
        assertEquals("Calls with `value`.", call.documentation());
        assertEquals(List.of("value"), call.callable().parameters());
        assertNull(call.callable().restParameter());
        assertEquals(2, call.source().range().startLine());
        assertEquals(1, call.source().range().startColumn());
    }

    @Test
    void blankLineBreaksSymbolDocumentationAssociation() throws Exception {
        writeModule(
                "collections/Blank",
                """
                /// Documentation.

                call: 1
                """);

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> ProtosStandardLibraryDocumentationExtractor.extract(temp, REVISION));

        assertTrue(error.getMessage().contains(
                "`///` must immediately precede a documentable named slot creation"));
    }

    @Test
    void ordinaryCommentBreaksSymbolDocumentationAssociation() throws Exception {
        writeModule(
                "collections/Ordinary",
                """
                /// Documentation.
                // implementation note
                call: 1
                """);

        assertThrows(
                IllegalArgumentException.class,
                () -> ProtosStandardLibraryDocumentationExtractor.extract(temp, REVISION));
    }

    @Test
    void nestedDocumentationIsValidButNotPublishedAsTopLevelSymbol() throws Exception {
        writeModule(
                "collections/Nested",
                """
                holder: {
                    /// Nested documentation.
                    nested: 1
                }
                next: 1
                """);

        ProtosStandardLibraryDocumentationExtractor.Extraction extraction =
                ProtosStandardLibraryDocumentationExtractor.extract(temp, REVISION);

        assertEquals(2, extraction.artifact().symbols().size());
        assertTrue(extraction.artifact().symbols().stream()
                .anyMatch(symbol -> symbol.identity().slotName().equals("holder")));
        assertTrue(extraction.artifact().symbols().stream()
                .anyMatch(symbol -> symbol.identity().slotName().equals("next")));
        assertFalse(extraction.artifact().symbols().stream()
                .anyMatch(symbol -> symbol.identity().slotName().equals("nested")));
    }

    @Test
    void inlineDocumentationMarkerIsRejected() throws Exception {
        writeModule(
                "collections/Inline",
                """
                first: 1 /// not a documentation line
                next: 1
                """);

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> ProtosStandardLibraryDocumentationExtractor.extract(temp, REVISION));

        assertTrue(error.getMessage().contains(
                "documentation markers must begin a documentation line"));
    }

    @Test
    void moduleDocumentationAfterFirstConstructIsRejected() throws Exception {
        writeModule(
                "collections/LateModule",
                """
                call: 1
                //! Too late.
                """);

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> ProtosStandardLibraryDocumentationExtractor.extract(temp, REVISION));

        assertTrue(error.getMessage().contains(
                "`//!` is only valid in the module preamble"));
    }

    @Test
    void moreThanOneModuleDocumentationBlockIsRejected() throws Exception {
        writeModule(
                "collections/TwoModules",
                """
                //! First.
                // ordinary separator
                //! Second.
                call: 1
                """);

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> ProtosStandardLibraryDocumentationExtractor.extract(temp, REVISION));

        assertTrue(error.getMessage().contains(
                "at most one `//!` module block is permitted"));
    }

    @Test
    void ambiguousCaseFoldSiblingSpellingIsRejected() throws Exception {
        writeModule("collections/Set", "call: 1\n");
        writeModule("Collections/Other", "call: 1\n");

        IOException error = assertThrows(
                IOException.class,
                () -> ProtosStandardLibraryDocumentationExtractor.extract(temp, REVISION));

        assertTrue(error.getMessage().contains(
                "ambiguous Standard Library path spelling"));
    }

    @Test
    void invalidOrAbbreviatedRevisionIsRejectedBeforeExtraction() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> ProtosStandardLibraryDocumentationExtractor.extract(temp, "abc"));

        assertTrue(error.getMessage().contains("full Git object id"));
    }

    @Test
    void currentRepositoryStandardLibraryIsExtractableWithoutCoreIdentity() throws Exception {
        ProtosStandardLibraryDocumentationExtractor.Extraction extraction =
                ProtosStandardLibraryDocumentationExtractor.extract(
                        Path.of("."),
                        "0000000000000000000000000000000000000000");

        assertTrue(extraction.artifact().modules().size() > 0);
        assertTrue(extraction.artifact().symbols().size() > 0);
        assertEquals(
                extraction.artifact().modules().size(),
                extraction.coverage().moduleCount());
        assertEquals(
                extraction.artifact().symbols().size(),
                extraction.coverage().symbolCount());

        extraction.artifact().modules().forEach(module -> {
            String identity = module.identity().toString();
            assertFalse(identity.contains("std:core"));
            assertTrue(module.source().path().startsWith("protos/lib/"));
        });
        extraction.artifact().symbols().forEach(symbol ->
                assertTrue(symbol.source().path().startsWith("protos/lib/")));

        ProtosStandardLibraryDocumentationExtractor.Coverage coverage =
                extraction.coverage();
        assertEquals(List.of(), coverage.missingModuleDocumentation());
        assertEquals(List.of(), coverage.missingSymbolDocumentation());
        assertEquals(coverage.moduleCount(), coverage.documentedModuleCount());
        assertEquals(coverage.symbolCount(), coverage.documentedSymbolCount());
    }

    private Symbol symbol(
            ProtosStandardLibraryDocumentationExtractor.Extraction extraction,
            String name) {
        return extraction.artifact().symbols().stream()
                .filter(symbol -> symbol.identity().slotName().equals(name))
                .findFirst()
                .orElseThrow();
    }

    private void writeModule(String logicalName, String source) throws IOException {
        Path file = temp.resolve("protos/lib").resolve(logicalName + ".protos");
        Files.createDirectories(file.getParent());
        Files.writeString(file, source, StandardCharsets.UTF_8);
    }

    private void writeCore(String name, String source) throws IOException {
        Path file = temp.resolve("protos/lib/core").resolve(name + ".protos");
        Files.createDirectories(file.getParent());
        Files.writeString(file, source, StandardCharsets.UTF_8);
    }
}
