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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.guillermomolina.protos.runtime.ProtosActivation;
import com.guillermomolina.protos.runtime.ProtosFilesystemTreeObservationFlow;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class ProtosExternalPackageResourceScopeTest {
    private static final String METHOD = "protos-package-tree-v1";
    private static final String ALGORITHM = "sha256";
    private static final String A_HEX = "aa".repeat(32);
    private static final String B_HEX = "bb".repeat(32);
    private static final String C_HEX = "cc".repeat(32);
    private static final String D_HEX = "dd".repeat(32);
    private static final byte[] BINARY = {0, (byte) 0xff, (byte) 0x80, 0x0a, 0x0d, 0x7f, 0x00};

    @TempDir Path temporaryRoot;

    // ---- Host-neutral immutable-resource reader ----

    @Test
    void readsExactBinaryBytesFromCapturedBackingAfterSourceIsReplaced() throws Exception {
        Path source = temporaryRoot.resolve("source");
        Files.createDirectories(source.resolve("src/nested"));
        Files.write(source.resolve("src/nested/Data.bin"), BINARY);
        Files.writeString(source.resolve("protos.toml"), "manifest", StandardCharsets.UTF_8);
        assumeSecureConfinement(source);

        try (ProtosCapturedFilesystemCustody custody =
                ProtosCapturedFilesystemCustody.captureSelectedRoot(source)) {
            Files.delete(source.resolve("src/nested/Data.bin"));
            Files.delete(source.resolve("src/nested"));
            Files.createDirectories(source.resolve("src/nested"));
            Files.write(source.resolve("src/nested/Data.bin"), new byte[] {1, 2, 3});

            ProtosPackageResourceName name = ProtosPackageResourceName.parse("src/nested/Data.bin");
            byte[] first = custody.readResource(name);
            byte[] second = custody.readResource(name);
            assertArrayEquals(BINARY, first);
            assertArrayEquals(BINARY, second);
            assertNotSame(first, second);

            first[0] = 42;
            assertArrayEquals(BINARY, custody.readResource(name));
            assertArrayEquals(
                    "manifest".getBytes(StandardCharsets.UTF_8),
                    custody.readResource(ProtosPackageResourceName.parse("protos.toml")));
        }
    }

    @Test
    void missingDirectoryAndLinkResourcesFailClosed() throws Exception {
        Path source = temporaryRoot.resolve("source");
        Files.createDirectories(source.resolve("dir"));
        Files.write(source.resolve("target.bin"), BINARY);
        Files.createSymbolicLink(source.resolve("link.bin"), Path.of("target.bin"));
        Files.createSymbolicLink(source.resolve("linkdir"), Path.of("dir"));
        Files.write(source.resolve("dir/inner.bin"), BINARY);
        assumeSecureConfinement(source);

        try (ProtosCapturedFilesystemCustody custody =
                ProtosCapturedFilesystemCustody.captureSelectedRoot(source)) {
            assertReadFails(custody, "missing.bin");
            assertReadFails(custody, "dir");
            assertReadFails(custody, "link.bin");
            assertReadFails(custody, "linkdir/inner.bin");
            assertReadFails(custody, "target.bin/child");
            assertArrayEquals(
                    BINARY, custody.readResource(ProtosPackageResourceName.parse("dir/inner.bin")));
        }
    }

    @Test
    void opaqueOtherAndLinkEntriesAreNeverRegularData() throws Exception {
        ProtosNioCapturedTreeFilesystemBackend backend;
        try (ProtosNioCapturedTreeFilesystemBackend.Builder builder =
                new ProtosNioCapturedTreeFilesystemBackend.Builder()) {
            Map<String, ProtosNioCapturedTreeFilesystemBackend.EntryNode> children =
                    new LinkedHashMap<>();
            children.put("data.bin", ProtosNioCapturedTreeFilesystemBackend.EntryNode.regular(
                    builder.captureBlob(channelOf(BINARY))));
            children.put("other", ProtosNioCapturedTreeFilesystemBackend.EntryNode.opaque(
                    ProtosFilesystemTreeObservationFlow.EntryKind.OTHER));
            children.put("link", ProtosNioCapturedTreeFilesystemBackend.EntryNode.opaque(
                    ProtosFilesystemTreeObservationFlow.EntryKind.LINK));
            backend = builder.complete(
                    new ProtosNioCapturedTreeFilesystemBackend.DirectoryNode(children));
        }
        try (ProtosCapturedFilesystemCustody custody =
                new ProtosCapturedFilesystemCustody(backend, backend::releaseIfUntransferred)) {
            assertArrayEquals(
                    BINARY, custody.readResource(ProtosPackageResourceName.parse("data.bin")));
            assertReadFails(custody, "other");
            assertReadFails(custody, "link");
        }
    }

    @Test
    void malformedAbsoluteAndEscapingNamesAreRejected() {
        for (String malformed :
                List.of("", "/", "/abs.bin", "../escape.bin", "a/../b", "./a", "a//b", "a/",
                        "a/.", "nul\0name")) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> ProtosPackageResourceName.parse(malformed),
                    malformed);
        }
        assertThrows(
                IllegalArgumentException.class,
                () -> new ProtosPackageResourceName(List.of("a/b")));
        assertEquals(
                List.of("src", "Main.protos"),
                ProtosPackageResourceName.parse("src/Main.protos").components());
    }

    @Test
    void independentConcurrentReadsReturnExactBytes() throws Exception {
        Path source = temporaryRoot.resolve("source");
        Files.createDirectories(source);
        byte[] large = new byte[200_000];
        for (int index = 0; index < large.length; index++) {
            large[index] = (byte) (index * 31);
        }
        Files.write(source.resolve("large.bin"), large);
        Files.write(source.resolve("small.bin"), BINARY);
        assumeSecureConfinement(source);

        ExecutorService executor = Executors.newFixedThreadPool(8);
        try (ProtosCapturedFilesystemCustody custody =
                ProtosCapturedFilesystemCustody.captureSelectedRoot(source)) {
            List<Callable<Boolean>> reads = new ArrayList<>();
            for (int index = 0; index < 64; index++) {
                boolean useLarge = index % 2 == 0;
                reads.add(() -> java.util.Arrays.equals(
                        useLarge ? large : BINARY,
                        custody.readResource(ProtosPackageResourceName.parse(
                                useLarge ? "large.bin" : "small.bin"))));
            }
            for (Future<Boolean> result : executor.invokeAll(reads, 30, TimeUnit.SECONDS)) {
                assertTrue(result.get());
            }
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void readFailsAfterCustodyCloseAndNoReadApiExposesPathOrActivation() throws Exception {
        Path source = temporaryRoot.resolve("source");
        Files.createDirectories(source);
        Files.write(source.resolve("data.bin"), BINARY);
        assumeSecureConfinement(source);

        ProtosCapturedFilesystemCustody custody =
                ProtosCapturedFilesystemCustody.captureSelectedRoot(source);
        ProtosPackageResourceName name = ProtosPackageResourceName.parse("data.bin");
        assertArrayEquals(BINARY, custody.readResource(name));
        custody.close();
        assertThrows(IllegalStateException.class, () -> custody.readResource(name));

        for (Class<?> type :
                List.of(
                        ProtosCapturedFilesystemCustody.class,
                        ProtosExternalPackageResourceScope.class,
                        ProtosPackageResourceName.class)) {
            for (Method method : type.getDeclaredMethods()) {
                assertFalse(Path.class.isAssignableFrom(method.getReturnType()), method.toString());
                if (method.getName().equals("readResource")) {
                    for (Class<?> parameter : method.getParameterTypes()) {
                        assertFalse(ProtosActivation.class.isAssignableFrom(parameter));
                        assertFalse(Path.class.isAssignableFrom(parameter));
                    }
                }
            }
        }
    }

    // ---- 1:1 scope reconciliation ----

    @Test
    void exactRegistryAndGitCustodiesReconcileAndLookUpTheirOwnAuthority() throws Exception {
        try (Packages packages = new Packages()) {
            ProtosExternalPackageResourceScope scope =
                    ProtosExternalPackageResourceScope.reconcile(
                            plan(packages.aNode(), packages.bNode(), packages.cNode()),
                            List.of(packages.a(), packages.b(), packages.c()));
            try (scope) {
                assertResource(scope, packages.a().identity(), "A");
                assertResource(scope, packages.b().identity(), "B");
                assertResource(scope, packages.c().identity(), "C");
                assertFalse(scope.contains(registry("root", "1.0.0", A_HEX)));
                assertThrows(
                        IllegalArgumentException.class,
                        () -> scope.readResource(
                                registry("unknown", "1.0.0", A_HEX),
                                ProtosPackageResourceName.parse("id.txt")));
            }
        }
    }

    @Test
    void missingExtraDuplicateAndMismatchedCustodiesAreRejected() throws Exception {
        try (Packages packages = new Packages()) {
            ProtosPackageExecutionPlanV2 plan =
                    plan(packages.aNode(), packages.bNode(), packages.cNode());
            var a = packages.a();
            var b = packages.b();
            var c = packages.c();
            var d = verifiedRegistry("pkg-d", "4.0.0", D_HEX, packages.d);

            assertRejected(plan, List.of(b, c));
            assertRejected(plan, List.of(a, c));
            assertRejected(plan, List.of(a, b));
            assertRejected(plan, List.of(a, b, c, d));
            assertRejected(plan, List.of(a, a, b, c));
            assertRejected(plan, List.of(
                    verifiedRegistry("pkg-a", "1.0.0", D_HEX, packages.a), b, c));
            assertRejected(plan, List.of(
                    a, b, verifiedGit("pkg-c", "abc123", D_HEX, packages.c)));
            assertRejected(plan, List.of(
                    verifiedGit("pkg-a", "1.0.0", A_HEX, packages.a), b, c));
            assertRejected(plan, List.of(
                    a, b, verifiedRegistry("pkg-c", "abc123", C_HEX, packages.c)));
            assertRejected(plan, List.of(
                    a, verifiedRegistry("pkg-b", "2.0.0", B_HEX, packages.a), c));

            packages.assertAllOpen();
        }
    }

    @Test
    void exactIdentitiesNeverCollapseOnPackageIdKindOrContent() throws Exception {
        try (Packages packages = new Packages()) {
            // Same PackageId at two exact versions, the same PackageId as Git, and one shared
            // ContentIdentity under two distinct logical identities.
            var v1 = verifiedRegistry("pkg", "1.0.0", A_HEX, packages.a);
            var v2 = verifiedRegistry("pkg", "2.0.0", A_HEX, packages.b);
            var git = verifiedGit("pkg", "1.0.0", A_HEX, packages.c);
            ProtosPackageExecutionPlanV2 plan =
                    plan(registryNode("pkg", 1, A_HEX), registryNode("pkg", 2, A_HEX),
                            gitNode("pkg", "1.0.0", A_HEX));

            try (ProtosExternalPackageResourceScope scope =
                    ProtosExternalPackageResourceScope.reconcile(plan, List.of(git, v2, v1))) {
                assertResource(scope, v1.identity(), "A");
                assertResource(scope, v2.identity(), "B");
                assertResource(scope, git.identity(), "C");
            }
        }
    }

    @Test
    void workspaceOnlyPlanNeedsNoCustody() throws Exception {
        try (ProtosExternalPackageResourceScope scope =
                ProtosExternalPackageResourceScope.reconcile(plan(), List.of())) {
            assertFalse(scope.contains(registry("root", "1.0.0", A_HEX)));
        }
    }

    // ---- Lifetime ----

    @Test
    void closeIsIdempotentAndReleasesEveryOwnedCustodyExactlyOnce() throws Exception {
        try (Packages packages = new Packages()) {
            ProtosExternalPackageResourceScope scope =
                    ProtosExternalPackageResourceScope.reconcile(
                            plan(packages.aNode(), packages.bNode(), packages.cNode()),
                            List.of(packages.a(), packages.b(), packages.c()));
            scope.close();
            scope.close();

            ProtosPackageResourceName name = ProtosPackageResourceName.parse("id.txt");
            assertThrows(
                    IllegalStateException.class,
                    () -> scope.readResource(packages.a().identity(), name));
            assertThrows(
                    IllegalStateException.class, () -> scope.contains(packages.a().identity()));
            for (ProtosCapturedFilesystemCustody custody :
                    List.of(packages.a, packages.b, packages.c)) {
                assertThrows(IllegalStateException.class, () -> custody.readResource(name));
            }
            assertDoesNotThrow(() -> packages.d.readResource(name));
        }
    }

    @Test
    void recordingCustodiesAreReleasedOncePerScopeClose() throws Exception {
        java.util.concurrent.atomic.AtomicInteger releases =
                new java.util.concurrent.atomic.AtomicInteger();
        ProtosStandardFilesystemProtocol.CapturedBackend unsupported = new UnsupportedBackend();
        var first = verifiedRegistry("pkg-a", "1.0.0", A_HEX,
                new ProtosCapturedFilesystemCustody(unsupported, releases::incrementAndGet));
        var second = verifiedGit("pkg-c", "abc123", C_HEX,
                new ProtosCapturedFilesystemCustody(unsupported, releases::incrementAndGet));
        ProtosExternalPackageResourceScope scope =
                ProtosExternalPackageResourceScope.reconcile(
                        plan(registryNode("pkg-a", 1, A_HEX), gitNode("pkg-c", "abc123", C_HEX)),
                        List.of(first, second));
        assertThrows(
                IOException.class,
                () -> scope.readResource(
                        first.identity(), ProtosPackageResourceName.parse("id.txt")));
        scope.close();
        scope.close();
        assertEquals(2, releases.get());
    }

    @Test
    void oneCustodySuppliedForTwoIdentitiesIsRejected() throws Exception {
        try (Packages packages = new Packages()) {
            assertRejected(
                    plan(registryNode("pkg", 1, A_HEX), registryNode("pkg", 2, A_HEX)),
                    List.of(
                            verifiedRegistry("pkg", "1.0.0", A_HEX, packages.a),
                            verifiedRegistry("pkg", "2.0.0", A_HEX, packages.a)));
            packages.assertAllOpen();
        }
    }

    // ---- Fixtures ----

    private void assertRejected(
            ProtosPackageExecutionPlanV2 plan,
            List<ProtosExternalPackagePlanningPreflight.VerifiedExternalPackage> verified) {
        assertThrows(
                IOException.class,
                () -> ProtosExternalPackageResourceScope.reconcile(plan, verified));
    }

    private static void assertResource(
            ProtosExternalPackageResourceScope scope,
            ProtosExactExternalPackageIdentity identity,
            String expected)
            throws IOException {
        assertTrue(scope.contains(identity));
        assertArrayEquals(
                expected.getBytes(StandardCharsets.UTF_8),
                scope.readResource(identity, ProtosPackageResourceName.parse("id.txt")));
    }

    private static void assertReadFails(ProtosCapturedFilesystemCustody custody, String name) {
        assertThrows(
                IOException.class,
                () -> custody.readResource(ProtosPackageResourceName.parse(name)),
                name);
    }

    private static SeekableByteChannel channelOf(byte[] bytes) {
        return new java.nio.channels.SeekableByteChannel() {
            private int position;

            @Override
            public int read(java.nio.ByteBuffer target) {
                if (position >= bytes.length) {
                    return -1;
                }
                int count = Math.min(target.remaining(), bytes.length - position);
                target.put(bytes, position, count);
                position += count;
                return count;
            }

            @Override
            public int write(java.nio.ByteBuffer source) {
                throw new UnsupportedOperationException();
            }

            @Override
            public long position() {
                return position;
            }

            @Override
            public SeekableByteChannel position(long newPosition) {
                position = (int) newPosition;
                return this;
            }

            @Override
            public long size() {
                return bytes.length;
            }

            @Override
            public SeekableByteChannel truncate(long size) {
                throw new UnsupportedOperationException();
            }

            @Override
            public boolean isOpen() {
                return true;
            }

            @Override
            public void close() {}
        };
    }

    private static void assumeSecureConfinement(Path root) throws Exception {
        try (ProtosNioReadOnlyTreeFilesystemBackend backend =
                new ProtosNioReadOnlyTreeFilesystemBackend(root)) {
            assumeTrue(
                    backend.secureConfinementAvailable(),
                    "host provider has no SecureDirectoryStream");
        }
    }

    private static ProtosPackageContentIdentity content(String hex) {
        return new ProtosPackageContentIdentity(METHOD, ALGORITHM, hex);
    }

    private static ProtosExactExternalPackageIdentity registry(
            String packageId, String version, String hex) {
        return new ProtosExactExternalPackageIdentity.Registry(packageId, version, content(hex));
    }

    private static ProtosExternalPackagePlanningPreflight.VerifiedExternalPackage verifiedRegistry(
            String packageId, String version, String hex, ProtosCapturedFilesystemCustody custody) {
        return ProtosExternalPackagePlanningPreflight.VerifiedExternalPackage.registry(
                packageId, version, METHOD, ALGORITHM, hex, custody);
    }

    private static ProtosExternalPackagePlanningPreflight.VerifiedExternalPackage verifiedGit(
            String packageId,
            String revision,
            String hex,
            ProtosCapturedFilesystemCustody custody) {
        return ProtosExternalPackagePlanningPreflight.VerifiedExternalPackage.git(
                packageId, revision, METHOD, ALGORITHM, hex, custody);
    }

    private static ProtosPackageExecutionPlanV2.ExternalPackage registryNode(
            String packageId, int major, String hex) {
        String text = major + ".0.0";
        return new ProtosPackageExecutionPlanV2.ExternalPackage(
                new ProtosPackageExecutionPlanV2.RegistryRef(
                        packageId,
                        new ProtosPackageExecutionPlanV2.ReleaseVersion(
                                String.valueOf(major),
                                "0",
                                "0",
                                List.of(),
                                text)),
                content(hex),
                Map.of());
    }

    private static ProtosPackageExecutionPlanV2.ExternalPackage gitNode(
            String packageId, String revision, String hex) {
        return new ProtosPackageExecutionPlanV2.ExternalPackage(
                new ProtosPackageExecutionPlanV2.GitRef(packageId, revision),
                content(hex),
                Map.of());
    }

    private static ProtosPackageExecutionPlanV2 plan(
            ProtosPackageExecutionPlanV2.ExternalPackage... externals) {
        ProtosPackageExecutionPlanV2.WorkspaceRef root =
                new ProtosPackageExecutionPlanV2.WorkspaceRef("root");
        List<ProtosPackageExecutionPlanV2.PackageNode> nodes = new ArrayList<>();
        nodes.add(new ProtosPackageExecutionPlanV2.WorkspacePackage(root, ".", Map.of()));
        nodes.addAll(List.of(externals));
        return new ProtosPackageExecutionPlanV2(2, root, nodes, List.of());
    }

    /** Four independently captured custodies whose {@code id.txt} is A, B, C and D. */
    private final class Packages implements AutoCloseable {
        private final ProtosCapturedFilesystemCustody a;
        private final ProtosCapturedFilesystemCustody b;
        private final ProtosCapturedFilesystemCustody c;
        private final ProtosCapturedFilesystemCustody d;

        Packages() throws Exception {
            a = capture("A");
            b = capture("B");
            c = capture("C");
            d = capture("D");
        }

        private ProtosCapturedFilesystemCustody capture(String id) throws Exception {
            Path root = temporaryRoot.resolve("pkg-" + id);
            Files.createDirectories(root);
            Files.writeString(root.resolve("id.txt"), id, StandardCharsets.UTF_8);
            assumeSecureConfinement(root);
            return ProtosCapturedFilesystemCustody.captureSelectedRoot(root);
        }

        ProtosExternalPackagePlanningPreflight.VerifiedExternalPackage a() {
            return verifiedRegistry("pkg-a", "1.0.0", A_HEX, a);
        }

        ProtosExternalPackagePlanningPreflight.VerifiedExternalPackage b() {
            return verifiedRegistry("pkg-b", "2.0.0", B_HEX, b);
        }

        ProtosExternalPackagePlanningPreflight.VerifiedExternalPackage c() {
            return verifiedGit("pkg-c", "abc123", C_HEX, c);
        }

        ProtosPackageExecutionPlanV2.ExternalPackage aNode() {
            return registryNode("pkg-a", 1, A_HEX);
        }

        ProtosPackageExecutionPlanV2.ExternalPackage bNode() {
            return registryNode("pkg-b", 2, B_HEX);
        }

        ProtosPackageExecutionPlanV2.ExternalPackage cNode() {
            return gitNode("pkg-c", "abc123", C_HEX);
        }

        void assertAllOpen() {
            ProtosPackageResourceName name = ProtosPackageResourceName.parse("id.txt");
            for (ProtosCapturedFilesystemCustody custody : List.of(a, b, c, d)) {
                assertDoesNotThrow(() -> custody.readResource(name));
            }
        }

        @Override
        public void close() {
            a.close();
            b.close();
            c.close();
            d.close();
        }
    }

    private static final class UnsupportedBackend
            implements ProtosStandardFilesystemProtocol.CapturedBackend {
        @Override
        public com.guillermomolina.protos.runtime.ProtosFilesystemOpenFlow.Cancellation open(
                com.guillermomolina.protos.runtime.ProtosPathComponents path,
                com.guillermomolina.protos.runtime.ProtosFilesystemOpenOptions options,
                ProtosStandardFilesystemProtocol.OpenCompletion completion) {
            completion.failed();
            return () -> {};
        }

        @Override
        public ProtosFilesystemTreeObservationFlow.Cancellation entries(
                com.guillermomolina.protos.runtime.ProtosPathComponents path,
                ProtosFilesystemTreeObservationFlow.EntriesCompletion completion) {
            completion.failed();
            return () -> {};
        }
    }
}
