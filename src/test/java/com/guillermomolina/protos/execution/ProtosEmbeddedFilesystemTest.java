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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.guillermomolina.protos.runtime.ProtosFileFlow;
import com.guillermomolina.protos.runtime.ProtosFilesystemOpenOptions;
import com.guillermomolina.protos.runtime.ProtosFilesystemValue;
import com.guillermomolina.protos.runtime.ProtosObjectValue;
import com.guillermomolina.protos.runtime.ProtosPathComponents;import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.AccessMode;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.CopyOption;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileAttribute;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Value;
import org.graalvm.polyglot.io.FileSystem;
import org.graalvm.polyglot.io.IOAccess;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * PLAT054-3E3 / I086: the default Filesystem of the standard Polyglot embedding ({@code
 * PROCESS_IO.md}, Embedding filesystem grant and Embedding filesystem base; {@code FILESYSTEM.md}
 * §18 and §20) through the public {@code Context} API. Providers are the physical host filesystem
 * and {@link MappedFileSystem}, a deterministic Polyglot provider whose namespace has no physical
 * counterpart and which injects restrictions, rejections, races, and an unsafe base on demand.
 * Every wait is a bounded condition, never a sleep.
 */
final class ProtosEmbeddedFilesystemTest {
    private static final String CORE = Path.of("protos", "lib", "core").toAbsolutePath().toString();
    private static final long SAFETY_SECONDS = 5;

    /** The initial module: helpers are Closures so later host entries reach its {@code filesystem}. */
    private static final String PROGRAM =
            """
            ioFails: (work) => {
                failed: false
                IOError.handle(() => {
                    work()
                    null
                }, (error) => {
                    failed = true
                    null
                })
                failed
            }
            invalidFails: (work) => {
                failed: false
                InvalidIOArgument.handle(() => {
                    work()
                    null
                }, (error) => {
                    failed = true
                    null
                })
                failed
            }
            at: (name) => { Path.relative().child(name) }
            inside: (directory, name) => { Path.relative().child(directory).child(name) }
            writeOptions: {
                read: false
                write: true
                create: true
                truncate: true
            }
            readCreateOptions: {
                read: true
                create: true
            }
            createNewOptions: {
                read: false
                write: true
                createNew: true
            }
            truncateOptions: {
                read: false
                write: true
                truncate: true
            }
            readWriteOptions: {
                read: true
                write: true
            }
            held: null
            trivial: () => { 1 }
            writeText: (name, text) => {
                file: filesystem.open(at(name), writeOptions).value()
                file.write(Encoding.Latin1.encode(text)).value()
                file.close().value()
                0
            }
            readFile: (file) => {
                reader: TextReader.owning(file, Encoding.UTF8)
                text: reader.readText().value()
                reader.close().value()
                text
            }
            readText: (name) => { readFile(filesystem.open(at(name)).value()) }
            readInside: (directory, name) => { readFile(filesystem.open(inside(directory, name)).value()) }
            openFails: (name) => { ioFails(() => filesystem.open(at(name)).value()) }
            openInsideFails: (directory, name) => { ioFails(() => filesystem.open(inside(directory, name)).value()) }
            writeFails: (name) => { ioFails(() => filesystem.open(at(name), writeOptions).value()) }
            createNew: (name) => {
                filesystem.open(at(name), createNewOptions).value().close().value()
                0
            }
            createNewFails: (name) => { ioFails(() => filesystem.open(at(name), createNewOptions).value()) }
            readCreate: (name) => { readFile(filesystem.open(at(name), readCreateOptions).value()) }
            truncateFile: (name) => {
                filesystem.open(at(name), truncateOptions).value().close().value()
                0
            }
            positioned: (name) => {
                file: filesystem.open(at(name), readWriteOptions).value()
                file.seek(4).value()
                file.write(Encoding.Latin1.encode("Z")).value()
                position: file.position().value()
                size: file.size().value()
                file.seek(0).value()
                bytes: file.read(10).value()
                file.truncate(2).value()
                truncated: file.size().value()
                file.close().value()
                (position == 5) &&
                    (size == 5) &&
                    (bytes.size() == 5) &&
                    (bytes[0] == 65) &&
                    (bytes[2] == 0) &&
                    (bytes[3] == 0) &&
                    (bytes[4] == 90) &&
                    (truncated == 2)
            }
            stableAcrossReplace: (name, replacement) => {
                file: filesystem.open(at(name)).value()
                filesystem.replace(at(replacement), at(name)).value()
                readFile(file)
            }
            stableAcrossRemove: (name) => {
                file: filesystem.open(at(name)).value()
                filesystem.remove(at(name)).value()
                readFile(file)
            }
            replaceEntry: (source, target) => { filesystem.replace(at(source), at(target)).value() === filesystem }
            replaceFails: (source, target) => { ioFails(() => filesystem.replace(at(source), at(target)).value()) }
            removeEntry: (name) => { filesystem.remove(at(name)).value() === filesystem }
            removeFails: (name) => { ioFails(() => filesystem.remove(at(name)).value()) }
            removeBaseFails: () => { ioFails(() => filesystem.remove(Path.relative()).value()) }
            entryCount: () => { filesystem.entries(Path.relative()).value().size() }
            kindOf: (name) => {
                kind: "absent"
                filesystem.entries(Path.relative()).value().each((entry) => {
                    (entry.name == name).ifTrue(() => { kind = entry.kind })
                })
                kind
            }
            entriesFails: (name) => { ioFails(() => filesystem.entries(at(name)).value()) }
            invalidOpen: () => { invalidFails(() => filesystem.open("plain.txt").value()) }
            invalidOptions: () => {
                options: {
                    read: false
                    truncate: true
                }
                invalidFails(() => filesystem.open(at("plain.txt"), options).value())
            }
            cancelledCreate: (name) => {
                future: filesystem.open(at(name), createNewOptions)
                future.cancel()
                outcome: 0
                Cancelled.handle(() => {
                    future.value().close().value()
                    outcome = 2
                    null
                }, (error) => {
                    outcome = 1
                    null
                })
                outcome
            }
            holdOpen: (name) => {
                held = filesystem.open(at(name)).value()
                0
            }
            spin: () => {
                process.stdout().write(process.stdoutEncoding().encode("go")).value()
                (() => true).whileTrue() { held.read(1).value() }
                0
            }
            0
            """;

    @TempDir Path temporary;

    private static Context.Builder builder() {
        return Context.newBuilder(ProtosLanguage.ID).option("protos.CoreRoot", CORE);
    }

    private static Context hostFiles(Path workingDirectory) {
        return builder()
                .allowIO(IOAccess.newBuilder().allowHostFileAccess(true).build())
                .currentWorkingDirectory(workingDirectory)
                .build();
    }

    private static Context provided(FileSystem provider) {
        return builder().allowIO(IOAccess.newBuilder().fileSystem(provider).build()).build();
    }

    private static ProtosEmbeddedProcess embedded(Context context) {
        context.enter();
        try {
            return ProtosLanguageContext.current().embeddedProcessOrNull();
        } finally {
            context.leave();
        }
    }

    private static ProtosEmbeddedFilesystemCustody custody(Context context) {
        return embedded(context).filesystemCustodyForTesting();
    }

    private static Value call(Context context, String name, Object... arguments) {
        return context.getBindings(ProtosLanguage.ID).getMember(name).execute(arguments);
    }

    private static boolean check(Context context, String name, Object... arguments) {
        return call(context, name, arguments).asBoolean();
    }

    private static String text(Path file) throws IOException {
        return Files.readString(file, StandardCharsets.UTF_8);
    }

    private static ProtosPathComponents path(String... components) {
        return new ProtosPathComponents(List.of(components));
    }

    private static void awaitCondition(BooleanSupplier condition, String description) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(SAFETY_SECONDS);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                fail("timed out waiting until " + description);
            }
            Thread.onSpinWait();
        }
    }

    // ---------------------------------------------------------------- grant and bootstrap

    @ParameterizedTest(name = "files={0} sockets={1} threads={2}")
    @CsvSource({
        "false, false, false",
        "false, false, true",
        "false, true,  false",
        "false, true,  true",
        "true,  false, false",
        "true,  false, true",
        "true,  true,  false",
        "true,  true,  true",
    })
    void effectiveFileAccessAloneGrantsTheInitialFilesystem(
            boolean files, boolean sockets, boolean threads) throws Exception {
        Context.Builder configured =
                builder()
                        .allowIO(
                                IOAccess.newBuilder()
                                        .allowHostFileAccess(files)
                                        .allowHostSocketAccess(sockets)
                                        .build())
                        .allowCreateThread(threads);
        if (files) {
            configured.currentWorkingDirectory(temporary);
        }
        try (Context context = configured.build()) {
            Value bindings = context.getBindings(ProtosLanguage.ID);
            assertNull(embedded(context), "building the Context creates no Process");
            context.eval(ProtosLanguage.ID, "trivial: () => { 1 }\n0");
            ProtosEmbeddedProcess process = embedded(context);
            assertEquals(files, bindings.hasMember("filesystem"), "present only when granted");
            assertEquals(sockets, bindings.hasMember("network"), "Network stays independent");
            assertEquals(files, process.filesystemCustodyForTesting() != null);
            if (!files) {
                assertFalse(
                        process.selectedModuleContext().orElseThrow().hasLocalSlot("filesystem"),
                        "absent, never bound to null");
                return;
            }
            ProtosObjectValue module = process.selectedModuleContext().orElseThrow();
            ProtosFilesystemValue filesystem =
                    assertInstanceOf(
                            ProtosFilesystemValue.class,
                            module.readLocalSlot("filesystem").orElseThrow());
            assertTrue(filesystem.hasLocalSlot("open") && filesystem.hasLocalSlot("entries"));
            assertSame(process.filesystemCustodyForTesting(), custody(context));
            assertFalse(
                    process.filesystemCustodyForTesting().operationalStateMaterializedForTesting());
        }
    }

    @Test
    void grantedFilesystemIsUsableAndBasedOnTheWorkingDirectory() throws Exception {
        Files.writeString(temporary.resolve("plain.txt"), "hello");
        try (Context context = hostFiles(temporary)) {
            context.eval(ProtosLanguage.ID, PROGRAM);
            assertEquals("hello", call(context, "readText", "plain.txt").asString());
            call(context, "writeText", "written.txt", "fresh");
            assertEquals("fresh", text(temporary.resolve("written.txt")));
            assertFalse(
                    embedded(context).actorCarrierSubstrateInitializedForTesting(),
                    "Filesystem needs no guest thread or Actor carrier");
        }
    }

    @Test
    void laterHostEvaluationsReceiveNoImplicitFilesystem() {
        try (Context context = hostFiles(temporary)) {
            Value bindings = context.getBindings(ProtosLanguage.ID);
            context.eval(ProtosLanguage.ID, "a: 1\n0");
            assertTrue(bindings.hasMember("filesystem"));
            context.eval(ProtosLanguage.ID, "b: 2\n0");
            assertFalse(bindings.hasMember("filesystem"), "bootstrap slots only on the first module");
        }
    }

    @Test
    void unsafeBaseAbortsBootstrapBeforeGuestCodeAndALaterAttemptMayBootstrap()
            throws Exception {
        MappedFileSystem provider = new MappedFileSystem(temporary);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (Context context =
                builder()
                        .allowIO(IOAccess.newBuilder().fileSystem(provider).build())
                        .out(out)
                        .build()) {
            provider.unsafeWorkingDirectory = true;
            String program =
                    """
                    process.stdout().write(process.stdoutEncoding().encode("ran")).value()
                    0
                    """;
            PolyglotException aborted =
                    assertThrows(
                            PolyglotException.class,
                            () -> context.eval(ProtosLanguage.ID, program));
            assertTrue(
                    aborted.getMessage().contains("default Filesystem base"),
                    aborted.getMessage());
            assertFalse(aborted.isCancelled());
            assertNull(embedded(context), "a failed bootstrap establishes no Process");
            assertEquals(0, out.size(), "no source expression of the initial module executed");
            assertTrue(
                    context.getBindings(ProtosLanguage.ID).getMemberKeys().isEmpty(),
                    "no partially established module or capability is reachable");

            // The same unsafe base aborts again; it is never a bootstrap without the slot.
            assertThrows(PolyglotException.class, () -> context.eval(ProtosLanguage.ID, program));
            assertNull(embedded(context));

            provider.unsafeWorkingDirectory = false;
            context.eval(ProtosLanguage.ID, program);
            assertEquals("ran", out.toString(StandardCharsets.UTF_8));
            assertTrue(context.getBindings(ProtosLanguage.ID).hasMember("filesystem"));
        }
    }

    @Test
    void anEstablishedTerminatedProcessIsNeverRecreatedAndItsFilesystemIsRevoked()
            throws Exception {
        try (Context context = hostFiles(temporary)) {
            context.eval(ProtosLanguage.ID, PROGRAM);
            ProtosEmbeddedProcess process = embedded(context);
            ProtosEmbeddedFilesystemCustody custody = process.filesystemCustodyForTesting();
            Files.writeString(temporary.resolve("plain.txt"), "held");
            call(context, "holdOpen", "plain.txt");
            assertEquals(1, custody.openResourceCountForTesting());
            assertThrows(
                    PolyglotException.class,
                    () -> context.eval(ProtosLanguage.ID, "Error().signal()\n0"));
            assertFalse(process.isLive());
            awaitCondition(custody::isClosedForTesting, "Process termination revokes Filesystem");
            assertEquals(0, custody.openResourceCountForTesting(), "open Files were released");
            assertThrows(PolyglotException.class, () -> context.eval(ProtosLanguage.ID, "0"));
            assertSame(process, embedded(context), "the Process is not recreated");
        }
    }

    // ---------------------------------------------------------------- operations

    @Test
    void openCreationTruncationAndPositionedFileOperations() throws Exception {
        try (Context context = hostFiles(temporary)) {
            context.eval(ProtosLanguage.ID, PROGRAM);
            Path plain = temporary.resolve("plain.txt");

            assertTrue(check(context, "openFails", "plain.txt"), "existing requires a target");
            call(context, "createNew", "plain.txt");
            assertEquals(0, Files.size(plain), "a created file starts empty");
            assertTrue(check(context, "createNewFails", "plain.txt"), "createNew is exclusive");

            assertTrue(
                    call(context, "readCreate", "made.txt").isNull(),
                    "a created file is empty, so text reading reaches EOF at once");
            assertTrue(Files.exists(temporary.resolve("made.txt")), "read-only create creates");
            Files.writeString(temporary.resolve("made.txt"), "kept");
            assertEquals("kept", call(context, "readCreate", "made.txt").asString());

            call(context, "writeText", "plain.txt", "longer content");
            call(context, "writeText", "plain.txt", "short");
            assertEquals("short", text(plain), "create with truncate replaces all content");
            call(context, "truncateFile", "plain.txt");
            assertEquals(0, Files.size(plain));

            Files.writeString(plain, "AB");
            assertTrue(check(context, "positioned", "plain.txt"));
            assertEquals("AB", text(plain), "truncate shrinks to the requested size");
        }
    }

    @Test
    void entriesReplaceAndRemoveActOnFinalEntries() throws Exception {
        Path outside = Files.createDirectories(temporary.resolve("outside"));
        Path base = Files.createDirectories(temporary.resolve("base"));
        Files.writeString(outside.resolve("target.txt"), "outside");
        Files.writeString(base.resolve("source.txt"), "new");
        Files.writeString(base.resolve("target.txt"), "old");
        Files.createDirectories(base.resolve("nested"));
        Files.writeString(base.resolve("nested").resolve("child.txt"), "child");
        Files.createSymbolicLink(base.resolve("link"), outside.resolve("target.txt"));
        try (Context context = hostFiles(base)) {
            context.eval(ProtosLanguage.ID, PROGRAM);
            assertEquals(4, call(context, "entryCount").asInt());
            assertEquals("regular", call(context, "kindOf", "source.txt").asString());
            assertEquals("directory", call(context, "kindOf", "nested").asString());
            assertEquals("link", call(context, "kindOf", "link").asString(), "no-follow kind");
            assertEquals("child", call(context, "readInside", "nested", "child.txt").asString());
            assertTrue(check(context, "entriesFails", "absent"));

            assertTrue(check(context, "replaceEntry", "source.txt", "target.txt"));
            assertFalse(Files.exists(base.resolve("source.txt")));
            assertEquals("new", text(base.resolve("target.txt")), "atomic replacement");
            assertTrue(check(context, "replaceEntry", "target.txt", "target.txt"), "same-entry no-op");
            assertEquals("new", text(base.resolve("target.txt")));
            assertTrue(check(context, "replaceFails", "absent", "target.txt"));

            assertTrue(check(context, "removeEntry", "link"));
            assertFalse(Files.exists(base.resolve("link"), LinkOption.NOFOLLOW_LINKS));
            assertEquals("outside", text(outside.resolve("target.txt")), "the referent remains");
            assertTrue(check(context, "removeFails", "nested"), "removal is never recursive");
            assertTrue(Files.exists(base.resolve("nested").resolve("child.txt")));
            assertTrue(check(context, "removeFails", "absent"));
            assertTrue(check(context, "removeBaseFails"), "the base has no final entry");
            assertTrue(Files.isDirectory(base));
        }
    }

    @Test
    void anOpenFileStaysBoundToItsSelectedResource() throws Exception {
        Files.writeString(temporary.resolve("bound.txt"), "selected");
        Files.writeString(temporary.resolve("other.txt"), "replacement");
        try (Context context = hostFiles(temporary)) {
            context.eval(ProtosLanguage.ID, PROGRAM);
            assertEquals(
                    "selected",
                    call(context, "stableAcrossReplace", "bound.txt", "other.txt").asString());
            assertEquals("replacement", text(temporary.resolve("bound.txt")));
            assertEquals(
                    "replacement", call(context, "stableAcrossRemove", "bound.txt").asString());
            assertFalse(Files.exists(temporary.resolve("bound.txt")));
        }
    }

    @Test
    void componentsAreSingleNamesAndInvalidArgumentsFailBeforeTheBackend() throws Exception {
        Files.createDirectories(temporary.resolve("a"));
        Files.writeString(temporary.resolve("a").resolve("b"), "two components");
        Files.writeString(temporary.resolve("plain.txt"), "unchanged");
        try (Context context = hostFiles(temporary)) {
            context.eval(ProtosLanguage.ID, PROGRAM);
            assertTrue(
                    check(context, "openFails", "a/b"),
                    "a separator inside one component is not reinterpreted as two names");
            assertTrue(check(context, "invalidOpen"));
            assertTrue(check(context, "invalidOptions"));
            assertEquals("unchanged", text(temporary.resolve("plain.txt")));
            assertEquals(0, custody(context).acquisitionCountForTesting());
        }
    }

    @Test
    void cancellationNeverSplitsACommittedCreationFromItsOutcome() throws Exception {
        try (Context context = hostFiles(temporary)) {
            context.eval(ProtosLanguage.ID, PROGRAM);
            int outcome = call(context, "cancelledCreate", "raced.txt").asInt();
            boolean created = Files.exists(temporary.resolve("raced.txt"));
            if (outcome == 1) {
                assertFalse(created, "a cancelled open published no creation");
            } else {
                assertEquals(2, outcome);
                assertTrue(created, "a committed creation resolves successfully");
            }
            assertEquals(0, custody(context).openResourceCountForTesting());
        }
    }

    // ---------------------------------------------------------------- providers

    @Test
    void aVirtualProviderWithoutPhysicalBasePathIsHonoured() throws Exception {
        MappedFileSystem provider = new MappedFileSystem(temporary);
        try (Context context = provided(provider)) {
            context.eval(ProtosLanguage.ID, PROGRAM);
            assertEquals(
                    provider.workingDirectory.toString(),
                    custody(context).baseForTesting().getPath());
            assertFalse(Files.exists(provider.workingDirectory), "no physical counterpart");
            call(context, "writeText", "virtual.txt", "mapped");
            assertEquals("mapped", text(provider.physicalWork().resolve("virtual.txt")));
            assertEquals("mapped", call(context, "readText", "virtual.txt").asString());
            assertEquals("regular", call(context, "kindOf", "virtual.txt").asString());
            assertTrue(check(context, "replaceEntry", "virtual.txt", "moved.txt"));
            assertTrue(check(context, "removeEntry", "moved.txt"));
            assertEquals(0, call(context, "entryCount").asInt());
        }
    }

    @Test
    void aRestrictedProviderConfinesLinkTraversal() throws Exception {
        Path secret = Files.createDirectories(temporary.resolve("secret"));
        Files.writeString(secret.resolve("key.txt"), "secret");
        MappedFileSystem provider = new MappedFileSystem(temporary.resolve("namespace"));
        provider.confineLinks = true;
        Files.writeString(provider.physicalWork().resolve("inside.txt"), "inside");
        Files.createSymbolicLink(provider.physicalWork().resolve("escape"), secret);
        Files.createSymbolicLink(
                provider.physicalWork().resolve("escape.txt"), secret.resolve("key.txt"));
        try (Context context = provided(provider)) {
            context.eval(ProtosLanguage.ID, PROGRAM);
            assertEquals("inside", call(context, "readText", "inside.txt").asString());
            assertTrue(check(context, "openInsideFails", "escape", "key.txt"));
            assertTrue(check(context, "openFails", "escape.txt"));
            assertTrue(check(context, "entriesFails", "escape"));
            assertTrue(check(context, "writeFails", "escape.txt"));
            assertEquals("secret", text(secret.resolve("key.txt")), "nothing leaked or changed");
        }
    }

    @Test
    void aReadOnlyProviderAllowsReadsAndRejectsEveryMutation() throws Exception {
        MappedFileSystem mapped = new MappedFileSystem(temporary);
        Files.writeString(mapped.physicalWork().resolve("plain.txt"), "read only");
        try (Context context = provided(FileSystem.newReadOnlyFileSystem(mapped))) {
            context.eval(ProtosLanguage.ID, PROGRAM);
            assertEquals("read only", call(context, "readText", "plain.txt").asString());
            assertEquals("regular", call(context, "kindOf", "plain.txt").asString());
            assertTrue(check(context, "writeFails", "plain.txt"));
            assertTrue(check(context, "createNewFails", "fresh.txt"));
            assertTrue(check(context, "replaceFails", "plain.txt", "other.txt"));
            assertTrue(check(context, "removeFails", "plain.txt"));
            assertEquals("read only", text(mapped.physicalWork().resolve("plain.txt")));
            assertFalse(Files.exists(mapped.physicalWork().resolve("fresh.txt")));
        }
    }

    @Test
    void providerRejectionsFailClosedWithoutCommitting() throws Exception {
        MappedFileSystem provider = new MappedFileSystem(temporary);
        provider.atomicMoveUnsupported = true;
        provider.deleteDenied = true;
        Path work = provider.physicalWork();
        Files.writeString(work.resolve("source.txt"), "source");
        Files.writeString(work.resolve("target.txt"), "target");
        try (Context context = provided(provider)) {
            context.eval(ProtosLanguage.ID, PROGRAM);
            assertTrue(
                    check(context, "replaceFails", "source.txt", "target.txt"),
                    "no copy/delete fallback without an atomic transition");
            assertEquals("source", text(work.resolve("source.txt")));
            assertEquals("target", text(work.resolve("target.txt")));
            assertTrue(check(context, "removeFails", "source.txt"));
            assertTrue(Files.exists(work.resolve("source.txt")));
            assertEquals("source", call(context, "readText", "source.txt").asString());
        }
    }

    @Test
    void openOrCreateReselectsTheTargetOfAConcurrentCreator() throws Exception {
        MappedFileSystem provider = new MappedFileSystem(temporary);
        provider.racedCreateName = "raced.txt";
        try (Context context = provided(provider)) {
            context.eval(ProtosLanguage.ID, PROGRAM);
            assertEquals(
                    "winner",
                    call(context, "readCreate", "raced.txt").asString(),
                    "the concurrent creator's file is selected, not a spurious failure");
            assertTrue(provider.raced);
        }
    }

    @Test
    void twoContextsWithDifferentProvidersAreIndependent() throws Exception {
        Path physical = Files.createDirectories(temporary.resolve("physical"));
        MappedFileSystem virtual = new MappedFileSystem(temporary.resolve("virtual"));
        try (Context survivor = provided(virtual)) {
            survivor.eval(ProtosLanguage.ID, PROGRAM);
            call(survivor, "writeText", "same.txt", "virtual");
            call(survivor, "holdOpen", "same.txt");
            ProtosEmbeddedFilesystemCustody closedCustody;
            try (Context closed = hostFiles(physical)) {
                closed.eval(ProtosLanguage.ID, PROGRAM);
                call(closed, "writeText", "same.txt", "physical");
                call(closed, "holdOpen", "same.txt");
                closedCustody = custody(closed);
                assertNotSame(custody(survivor), closedCustody, "no shared custody");
            }
            assertTrue(closedCustody.isClosedForTesting());
            assertEquals(0, closedCustody.openResourceCountForTesting());
            assertFalse(custody(survivor).isClosedForTesting());
            assertEquals(1, custody(survivor).openResourceCountForTesting());
            assertEquals("physical", text(physical.resolve("same.txt")));
            assertEquals("virtual", call(survivor, "readText", "same.txt").asString());
        }
    }

    // ---------------------------------------------------------------- lifecycle

    @Test
    void contextCloseReleasesOpenFilesAndRevokesTheCapability() throws Exception {
        Files.writeString(temporary.resolve("plain.txt"), "held");
        ProtosEmbeddedFilesystemCustody custody;
        try (Context context = hostFiles(temporary)) {
            context.eval(ProtosLanguage.ID, PROGRAM);
            call(context, "holdOpen", "plain.txt");
            custody = custody(context);
            assertEquals(1, custody.openResourceCountForTesting());
        }
        assertTrue(custody.isClosedForTesting());
        assertEquals(0, custody.openResourceCountForTesting());
        custody.close(); // idempotent
        assertTrue(custody.isClosedForTesting());
    }

    @Test
    void revocationClosesAcquiredResourcesAndRefusesLaterOperations() throws Exception {
        Files.writeString(temporary.resolve("plain.txt"), "held");
        try (Context context = hostFiles(temporary)) {
            context.eval(ProtosLanguage.ID, PROGRAM);
            ProtosEmbeddedFilesystemCustody custody = custody(context);
            RecordingOpen first = new RecordingOpen();
            context.enter();
            try {
                custody.open(path("plain.txt"), ProtosFilesystemOpenOptions.defaults(), first);
            } finally {
                context.leave();
            }
            assertNotNull(first.resource, "a direct backend open acquires the resource");
            assertTrue(custody.operationalStateMaterializedForTesting());

            custody.close();
            RecordingRead read = new RecordingRead();
            ((ProtosFileFlow.ReadableResource) first.resource).readAt(0L, 1, read);
            assertTrue(read.failed, "a revoked File never regains usability");

            RecordingOpen late = new RecordingOpen();
            context.enter();
            try {
                custody.open(path("plain.txt"), ProtosFilesystemOpenOptions.defaults(), late);
            } finally {
                context.leave();
            }
            assertTrue(late.failed, "no operation is accepted after revocation");
            assertNull(late.resource);
            assertEquals(0, custody.openResourceCountForTesting());
        }
    }

    @Test
    void cancellingABusyContextReleasesItsFilesWithoutBlocking() throws Exception {
        Files.writeString(temporary.resolve("plain.txt"), "held");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Context context =
                builder()
                        .allowIO(IOAccess.newBuilder().allowHostFileAccess(true).build())
                        .currentWorkingDirectory(temporary)
                        .out(out)
                        .build();
        context.eval(ProtosLanguage.ID, PROGRAM);
        call(context, "holdOpen", "plain.txt");
        ProtosEmbeddedFilesystemCustody custody = custody(context);
        CompletableFuture<Throwable> busy =
                CompletableFuture.supplyAsync(
                        () -> {
                            try {
                                call(context, "spin");
                                return null;
                            } catch (PolyglotException cancelled) {
                                return cancelled;
                            }
                        });
        awaitCondition(() -> out.size() > 0, "the guest is reading in a loop");
        CompletableFuture.runAsync(() -> context.close(true)).get(10, TimeUnit.SECONDS);
        Throwable failure = busy.get(SAFETY_SECONDS, TimeUnit.SECONDS);
        assertTrue(failure instanceof PolyglotException cancelled && cancelled.isCancelled());
        assertTrue(custody.isClosedForTesting());
        assertEquals(0, custody.openResourceCountForTesting());
    }

    // ---------------------------------------------------------------- pay as you grow

    @Test
    void noGrantCreatesNoFilesystemBackend() {
        try (Context context = builder().build()) {
            context.eval(ProtosLanguage.ID, PROGRAM);
            assertNull(custody(context), "no custody without effective file access");
            assertFalse(context.getBindings(ProtosLanguage.ID).hasMember("filesystem"));
        }
    }

    @Test
    void anUnusedGrantedFilesystemMaterializesNoOperationalState() throws Exception {
        Files.writeString(temporary.resolve("plain.txt"), "lazy");
        try (Context context = hostFiles(temporary)) {
            context.eval(ProtosLanguage.ID, PROGRAM);
            ProtosEmbeddedFilesystemCustody custody = custody(context);
            assertNotNull(custody);
            for (int i = 0; i < 3; i++) {
                assertEquals(1, call(context, "trivial").asInt());
            }
            assertFalse(custody.operationalStateMaterializedForTesting(), "no File custody yet");
            assertEquals(0, custody.acquisitionCountForTesting());
            assertFalse(embedded(context).actorCarrierSubstrateInitializedForTesting());

            assertEquals("lazy", call(context, "readText", "plain.txt").asString());
            assertTrue(custody.operationalStateMaterializedForTesting(), "first use materializes");
            assertEquals(1, custody.acquisitionCountForTesting());
            assertEquals(0, custody.openResourceCountForTesting(), "closed Files leave custody");
        }
    }

    // ---------------------------------------------------------------- fixtures

    private static final class RecordingOpen
            implements ProtosStandardFilesystemProtocol.OpenCompletion {
        ProtosFileFlow.Resource resource;
        boolean failed;

        @Override
        public boolean commitPortableEffect() {
            return true;
        }

        @Override
        public void succeeded(
                ProtosFileFlow.Resource resource,
                ProtosFileFlow.Capabilities capabilities,
                Runnable releaseIfUntransferred) {
            this.resource = resource;
        }

        @Override
        public void failed() {
            failed = true;
        }
    }

    private static final class RecordingRead implements ProtosFileFlow.ReadCompletion {
        boolean failed;

        @Override
        public void data(byte[] bytes) {}

        @Override
        public void eof() {}

        @Override
        public void failed() {
            failed = true;
        }
    }

    /**
     * A Polyglot provider whose namespace lives under {@code /protos-virtual-namespace}, a path
     * that does not exist on the host, mapped onto a test directory. Its working directory is the
     * virtual {@code work} directory. Flags inject an unrepresentable working directory, link
     * confinement (the restricted provider), a provider without atomic moves, a provider denying
     * deletion, and a concurrent creator racing one exclusive creation.
     */
    static final class MappedFileSystem implements FileSystem {
        private final FileSystem host = FileSystem.newDefaultFileSystem();
        private final Path physicalRoot;
        final Path virtualRoot = Path.of("/protos-virtual-namespace");
        final Path workingDirectory = virtualRoot.resolve("work");
        volatile boolean unsafeWorkingDirectory;
        volatile boolean confineLinks;
        volatile boolean atomicMoveUnsupported;
        volatile boolean deleteDenied;
        volatile String racedCreateName;
        volatile boolean raced;

        MappedFileSystem(Path physicalRoot) throws IOException {
            Files.createDirectories(physicalRoot.resolve("work"));
            this.physicalRoot = physicalRoot.toRealPath();
        }

        Path physicalWork() {
            return physicalRoot.resolve("work");
        }

        private Path physical(Path path) throws IOException {
            Path absolute = toAbsolutePath(path).normalize();
            if (!absolute.startsWith(virtualRoot)) {
                throw new AccessDeniedException(path.toString(), null, "outside the namespace");
            }
            Path mapped = physicalRoot.resolve(virtualRoot.relativize(absolute).toString());
            if (confineLinks) {
                Path existing = mapped;
                while (existing != null && !Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) {
                    existing = existing.getParent();
                }
                if (existing == null || !existing.toRealPath().startsWith(physicalRoot)) {
                    throw new AccessDeniedException(path.toString(), null, "link escapes");
                }
            }
            return mapped;
        }

        @Override
        public Path parsePath(URI uri) {
            return Path.of(uri);
        }

        @Override
        public Path parsePath(String path) {
            return Path.of(path);
        }

        @Override
        public Path toAbsolutePath(Path path) {
            if (path.isAbsolute()) {
                return path;
            }
            if (unsafeWorkingDirectory) {
                throw new UnsupportedOperationException("no representable working directory");
            }
            return workingDirectory.resolve(path);
        }

        @Override
        public Path toRealPath(Path path, LinkOption... options) throws IOException {
            Path real = host.toRealPath(physical(path), options);
            if (!real.startsWith(physicalRoot)) {
                throw new AccessDeniedException(path.toString(), null, "outside the namespace");
            }
            return virtualRoot.resolve(physicalRoot.relativize(real).toString());
        }

        @Override
        public void checkAccess(Path path, Set<? extends AccessMode> modes, LinkOption... options)
                throws IOException {
            host.checkAccess(physical(path), modes, options);
        }

        @Override
        public void createDirectory(Path dir, FileAttribute<?>... attrs) throws IOException {
            host.createDirectory(physical(dir), attrs);
        }

        @Override
        public void delete(Path path) throws IOException {
            if (deleteDenied) {
                throw new AccessDeniedException(path.toString(), null, "deletion denied");
            }
            host.delete(physical(path));
        }

        @Override
        public SeekableByteChannel newByteChannel(
                Path path, Set<? extends OpenOption> options, FileAttribute<?>... attrs)
                throws IOException {
            Path mapped = physical(path);
            if (options.contains(StandardOpenOption.CREATE_NEW)
                    && mapped.getFileName().toString().equals(racedCreateName)
                    && !raced) {
                raced = true;
                Files.writeString(mapped, "winner");
            }
            return host.newByteChannel(mapped, options, attrs);
        }

        @Override
        public DirectoryStream<Path> newDirectoryStream(
                Path dir, DirectoryStream.Filter<? super Path> filter) throws IOException {
            Path virtualDirectory = toAbsolutePath(dir);
            List<Path> children = new ArrayList<>();
            try (DirectoryStream<Path> stream =
                    host.newDirectoryStream(physical(dir), entry -> true)) {
                for (Path child : stream) {
                    Path virtual = virtualDirectory.resolve(child.getFileName().toString());
                    if (filter.accept(virtual)) {
                        children.add(virtual);
                    }
                }
            }
            return new DirectoryStream<>() {
                @Override
                public Iterator<Path> iterator() {
                    return children.iterator();
                }

                @Override
                public void close() {}
            };
        }

        @Override
        public Map<String, Object> readAttributes(
                Path path, String attributes, LinkOption... options) throws IOException {
            return host.readAttributes(physical(path), attributes, options);
        }

        @Override
        public void move(Path source, Path target, CopyOption... options) throws IOException {
            if (atomicMoveUnsupported
                    && Arrays.asList(options).contains(StandardCopyOption.ATOMIC_MOVE)) {
                throw new AtomicMoveNotSupportedException(
                        source.toString(), target.toString(), "no atomic move");
            }
            host.move(physical(source), physical(target), options);
        }
    }
}
