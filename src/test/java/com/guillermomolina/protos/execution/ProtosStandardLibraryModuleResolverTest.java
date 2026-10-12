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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.guillermomolina.protos.runtime.ProtosActivation;
import com.guillermomolina.protos.runtime.ProtosIntegerValue;
import com.guillermomolina.protos.runtime.ProtosModuleKey;
import com.guillermomolina.protos.runtime.ProtosObjectValue;
import com.guillermomolina.protos.runtime.ProtosPrelude;
import com.guillermomolina.protos.runtime.ProtosSignalException;
import java.io.IOException;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProtosStandardLibraryModuleResolverTest {
    private static final Path CORE = Path.of("protos", "lib", "core");
    private static final Path STANDARD_LIBRARY = Path.of("protos", "lib");

    @TempDir Path libraryRoot;

    @Test
    void canonicalIdentityPreservesExactCaseAndIsIndependentOfImporterAndInstallationRoot()
            throws Exception {
        Path otherRoot = Files.createTempDirectory("protos-stdlib-other-");
        try {
            writeModule(libraryRoot, "collections/Probe", "value: 1");
            writeModule(otherRoot, "collections/Probe", "value: 2");

            ProtosStandardLibraryModuleResolver first =
                    new ProtosStandardLibraryModuleResolver(libraryRoot);
            ProtosStandardLibraryModuleResolver second =
                    new ProtosStandardLibraryModuleResolver(otherRoot);
            Optional<ProtosModuleKey> importer =
                    Optional.of(new ProtosModuleKey("file:/application/main.protos"));

            ProtosModuleKey fromTopLevel =
                    first.resolve("std:collections/Probe", Optional.empty());
            ProtosModuleKey fromImporter =
                    first.resolve("std:collections/Probe", importer);
            ProtosModuleKey afterRelocation =
                    second.resolve("std:collections/Probe", Optional.empty());

            assertEquals(new ProtosModuleKey("std:collections/Probe"), fromTopLevel);
            assertEquals(fromTopLevel, fromImporter);
            assertEquals(fromTopLevel, afterRelocation);
        } finally {
            deleteTree(otherRoot);
        }
    }

    @Test
    void sourceLookupUsesHiddenLowercaseProtosExtensionAndUtf8() throws Exception {
        writeModule(libraryRoot, "collections/TextProbe", "value: \"olá\"");
        ProtosStandardLibraryModuleResolver resolver =
                new ProtosStandardLibraryModuleResolver(libraryRoot);

        ProtosModuleKey key =
                resolver.resolve("std:collections/TextProbe", Optional.empty());
        assertEquals("value: \"olá\"", resolver.loadSource(key).characters());
    }

    @Test
    void exactDistributedCaseIsRequiredForEveryPathComponent() throws Exception {
        writeModule(libraryRoot, "collections/Probe", "value: 1");
        ProtosStandardLibraryModuleResolver resolver =
                new ProtosStandardLibraryModuleResolver(libraryRoot);

        assertEquals(
                new ProtosModuleKey("std:collections/Probe"),
                resolver.resolve("std:collections/Probe", Optional.empty()));
        assertThrows(
                IOException.class,
                () -> resolver.resolve("std:collections/probe", Optional.empty()));
        assertThrows(
                IOException.class,
                () -> resolver.resolve("std:Collections/Probe", Optional.empty()));
    }

    @Test
    void caseFoldEquivalentSiblingNamesAreRejectedAsAmbiguous() throws Exception {
        writeModule(libraryRoot, "collections/Probe", "value: 1");
        writeModule(libraryRoot, "collections/probe", "value: 2");
        Path collections = libraryRoot.resolve("collections");
        long foldedMatches;
        try (var children = Files.list(collections)) {
            foldedMatches =
                    children
                            .filter(
                                    child ->
                                            child.getFileName()
                                                    .toString()
                                                    .equalsIgnoreCase("Probe.protos"))
                            .count();
        }
        if (foldedMatches < 2) {
            return; // This host filesystem cannot materialize the invalid collision.
        }

        ProtosStandardLibraryModuleResolver resolver =
                new ProtosStandardLibraryModuleResolver(libraryRoot);
        assertThrows(
                IOException.class,
                () -> resolver.resolve("std:collections/Probe", Optional.empty()));
        assertThrows(
                IOException.class,
                () -> resolver.resolve("std:collections/probe", Optional.empty()));
    }

    @Test
    void reservedCoreAndWindowsDeviceSegmentsAreRejected() {
        ProtosStandardLibraryModuleResolver resolver =
                new ProtosStandardLibraryModuleResolver(libraryRoot);

        for (String specifier :
                List.of(
                        "std:core/Object",
                        "std:Core/Object",
                        "std:CORE/Object",
                        "std:collections/NUL",
                        "std:collections/nul",
                        "std:collections/Com1")) {
            IOException failure =
                    assertThrows(
                            IOException.class,
                            () -> resolver.resolve(specifier, Optional.empty()),
                            specifier);
            assertEquals("invalid standard-library module name", failure.getMessage());
        }
    }

    @Test
    void invalidOrNonStandardSpellingsNeverFallBackToSearchPaths() {
        ProtosStandardLibraryModuleResolver resolver =
                new ProtosStandardLibraryModuleResolver(libraryRoot);
        List<String> invalid =
                List.of(
                        "collections/Set",
                        "std:",
                        "std:collections/Set.protos",
                        "std:collections/../Set",
                        "std:/collections/Set",
                        "std:collections//Set",
                        "std:collections/set-name",
                        "std:collections\\Set");

        for (String specifier : invalid) {
            assertThrows(
                    IOException.class,
                    () -> resolver.resolve(specifier, Optional.empty()),
                    specifier);
        }
    }

    @Test
    void ordinaryImportCachingAndActorLocalInstancesUseTheCanonicalStandardKey()
            throws Exception {
        writeModule(libraryRoot, "collections/Probe", "value: 7");
        ProtosStandardLibraryModuleResolver resolver =
                new ProtosStandardLibraryModuleResolver(libraryRoot);
        ProtosPrelude prelude = new ProtosCoreBootstrap().bootstrap(CORE, resolver);
        ProtosActivation actorA = prelude.newModuleActivation();
        ProtosActivation actorB = prelude.newModuleActivation();

        ProtosObjectValue first =
                (ProtosObjectValue)
                        ProtosTestExecutionSupport.evaluate(
                                "import(\"std:collections/Probe\")",
                                actorA);
        ProtosObjectValue repeated =
                (ProtosObjectValue)
                        ProtosTestExecutionSupport.evaluate(
                                "import(\"std:collections/Probe\")",
                                actorA);
        ProtosObjectValue otherActor =
                (ProtosObjectValue)
                        ProtosTestExecutionSupport.evaluate(
                                "import(\"std:collections/Probe\")",
                                actorB);

        assertSame(first, repeated);
        assertNotSame(first, otherActor);
        ProtosIntegerValue value =
                (ProtosIntegerValue) first.readLocalSlot("value").orElseThrow();
        assertEquals(String.valueOf("7"), com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerDecimalText(value));
    }

    @Test
    void missingStandardModuleBecomesTheExistingCoreImportError() throws Exception {
        ProtosStandardLibraryModuleResolver resolver =
                new ProtosStandardLibraryModuleResolver(libraryRoot);
        ProtosPrelude prelude = new ProtosCoreBootstrap().bootstrap(CORE, resolver);

        assertThrows(
                ProtosSignalException.class,
                () ->
                        ProtosTestExecutionSupport.evaluate(
                                "import(\"std:collections/Missing\")",
                                prelude.newModuleActivation()));
    }

    @Test
    void distributedLibraryHasNoCaseFoldEquivalentSiblingNames() throws Exception {
        assertNoCaseFoldEquivalentSiblings(STANDARD_LIBRARY);
    }

    private static void assertNoCaseFoldEquivalentSiblings(Path directory) throws IOException {
        Map<String, Path> seen = new HashMap<>();
        try (var children = Files.list(directory)) {
            for (Path child : children.toList()) {
                String folded =
                        child.getFileName().toString().toLowerCase(Locale.ROOT);
                Path previous = seen.putIfAbsent(folded, child);
                if (previous != null) {
                    throw new AssertionError(
                            "case-fold-equivalent distribution siblings: "
                                    + previous
                                    + " and "
                                    + child);
                }
                assertNotNull(child.getFileName());
                if (Files.isDirectory(child)) {
                    assertNoCaseFoldEquivalentSiblings(child);
                }
            }
        }
    }

    private static void writeModule(Path root, String logicalName, String source)
            throws IOException {
        Path file = root.resolve(logicalName + ".protos");
        Files.createDirectories(file.getParent());
        Files.writeString(file, source, StandardCharsets.UTF_8);
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            paths.sorted((left, right) -> right.compareTo(left))
                    .forEach(
                            path -> {
                                try {
                                    Files.deleteIfExists(path);
                                } catch (IOException failure) {
                                    throw new RuntimeException(failure);
                                }
                            });
        } catch (RuntimeException failure) {
            if (failure.getCause() instanceof IOException) {
                throw (IOException) failure.getCause();
            }
            throw failure;
        }
    }
}
