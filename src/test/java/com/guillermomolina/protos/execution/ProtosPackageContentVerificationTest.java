/* APL-1.0 licensed work; see LICENSE.TXT. */
package com.guillermomolina.protos.execution;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.guillermomolina.protos.runtime.ProtosActivation;
import com.guillermomolina.protos.runtime.ProtosBytesValue;
import com.guillermomolina.protos.runtime.ProtosFilesystemValue;
import com.guillermomolina.protos.runtime.ProtosFutureValue;
import com.guillermomolina.protos.runtime.ProtosIntegerValue;
import com.guillermomolina.protos.runtime.ProtosNullValue;
import com.guillermomolina.protos.runtime.ProtosObjectValue;
import com.guillermomolina.protos.runtime.ProtosPathValue;
import com.guillermomolina.protos.runtime.ProtosPrelude;
import com.guillermomolina.protos.runtime.ProtosProcessRuntime;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class ProtosPackageContentVerificationTest {
    private static final Path CORE = Path.of("protos", "lib", "core");
    private static final Path STANDARD_LIBRARY = Path.of("protos", "lib");
    private static final Path TOOL_ROOT = Path.of("protos", "tools", "package");
    private static final String METHOD = "protos-package-tree-v1";
    private static final String ALGORITHM = "sha256";
    private static final String V1_HEX =
            "beb503347f64442d909b4848e69333a72f4d049d7f5b37c32f453f409641ef04";
    private static final String V3_HEX =
            "2de3f9f78fb1355348861e08cb549919d10e4b50f8965f3fc933eac72fff8870";

    @TempDir Path temporaryRoot;

    @Test
    void verifiedCustodySurvivesToolTerminationAndSourceDeletionForLaterDomain()
            throws Exception {
        Path selectedRoot = createV3("positive");
        assumeSecureConfinement(selectedRoot);
        AtomicReference<ProtosProcessRuntime> observed = new AtomicReference<>();

        try (ProtosCapturedFilesystemCustody custody =
                ProtosPackageContentVerification.captureAndVerify(
                        CORE,
                        TOOL_ROOT,
                        selectedRoot,
                        standardLibraryResolver(),
                        METHOD,
                        ALGORITHM,
                        V3_HEX,
                        observed::set)) {
            ProtosProcessRuntime process = observed.get();
            assertNotNull(process);
            assertEquals(ProtosProcessRuntime.LifecycleState.TERMINATED, process.lifecycleState());
            assertTrue(process.rootFilesystemForRuntime().isEmpty());

            Files.delete(selectedRoot.resolve("Main.protos"));
            Files.delete(selectedRoot.resolve("assets/data.bin"));
            Files.delete(selectedRoot.resolve("assets"));
            Files.delete(selectedRoot.resolve("protos.toml"));
            Files.writeString(
                    selectedRoot.resolve("after-only.protos"),
                    "later",
                    StandardCharsets.US_ASCII);

            ProtosPrelude applicationPrelude =
                    new ProtosCoreBootstrap().bootstrap(CORE);
            ProtosActivation applicationActivation = applicationPrelude.newModuleActivation();
            ProtosFilesystemValue applicationFilesystem =
                    custody.materialize(applicationActivation);

            assertArrayEquals(
                    "value: 42\n".getBytes(StandardCharsets.US_ASCII),
                    readAll(
                            applicationFilesystem,
                            applicationPrelude,
                            applicationActivation,
                            "Main.protos"));
            assertArrayEquals(
                    new byte[] {0, 1, 2, (byte) 0xff, 0x7f, (byte) 0x80},
                    readAll(
                            applicationFilesystem,
                            applicationPrelude,
                            applicationActivation,
                            "assets",
                            "data.bin"));
            assertMissing(
                    applicationFilesystem,
                    applicationPrelude,
                    applicationActivation,
                    "after-only.protos");
        }
    }

    @Test
    void digestMismatchFailsClosedAndStillTerminatesToolProcess() throws Exception {
        Path selectedRoot = createMinimal("mismatch");
        assumeSecureConfinement(selectedRoot);
        AtomicReference<ProtosProcessRuntime> observed = new AtomicReference<>();

        assertThrows(
                java.io.IOException.class,
                () ->
                        ProtosPackageContentVerification.captureAndVerify(
                                CORE,
                                TOOL_ROOT,
                                selectedRoot,
                                standardLibraryResolver(),
                                METHOD,
                                ALGORITHM,
                                "0".repeat(64),
                                observed::set));

        ProtosProcessRuntime process = observed.get();
        assertNotNull(process);
        assertEquals(ProtosProcessRuntime.LifecycleState.TERMINATED, process.lifecycleState());
        assertTrue(process.rootFilesystemForRuntime().isEmpty());
    }

    @Test
    void unsupportedIdentityShapeAndInvalidCapturedTreeFailThroughProtosPolicy()
            throws Exception {
        Path methodRoot = createMinimal("method");
        assumeSecureConfinement(methodRoot);
        assertRejected(methodRoot, "future-package-tree-v2", ALGORITHM, V1_HEX);

        Path algorithmRoot = createMinimal("algorithm");
        assertRejected(algorithmRoot, METHOD, "sha512", V1_HEX);

        Path invalidTree = Files.createDirectories(temporaryRoot.resolve("invalid-tree"));
        Files.writeString(
                invalidTree.resolve("Main.protos"),
                "value: 1\n",
                StandardCharsets.US_ASCII);
        assertRejected(invalidTree, METHOD, ALGORITHM, V1_HEX);
    }

    private void assertRejected(Path root, String method, String algorithm, String hex) {
        assertThrows(
                java.io.IOException.class,
                () ->
                        ProtosPackageContentVerification.captureAndVerify(
                                CORE,
                                TOOL_ROOT,
                                root,
                                standardLibraryResolver(),
                                method,
                                algorithm,
                                hex));
    }

    private Path createMinimal(String name) throws Exception {
        Path root = Files.createDirectories(temporaryRoot.resolve(name));
        Files.write(root.resolve("protos.toml"), new byte[0]);
        return root;
    }

    private Path createV3(String name) throws Exception {
        Path root = Files.createDirectories(temporaryRoot.resolve(name));
        Files.writeString(
                root.resolve("Main.protos"),
                "value: 42\n",
                StandardCharsets.US_ASCII);
        Path assets = Files.createDirectories(root.resolve("assets"));
        Files.write(
                assets.resolve("data.bin"),
                new byte[] {0, 1, 2, (byte) 0xff, 0x7f, (byte) 0x80});
        Files.writeString(
                root.resolve("protos.toml"),
                "id = \"pkg\"\n",
                StandardCharsets.US_ASCII);
        return root;
    }

    private static ProtosStandardLibraryModuleResolver standardLibraryResolver() {
        return new ProtosStandardLibraryModuleResolver(STANDARD_LIBRARY);
    }

    private static void assumeSecureConfinement(Path root) throws Exception {
        try (ProtosNioReadOnlyTreeFilesystemBackend backend =
                new ProtosNioReadOnlyTreeFilesystemBackend(root)) {
            assumeTrue(
                    backend.secureConfinementAvailable(),
                    "host provider has no SecureDirectoryStream");
        }
    }

    private static byte[] readAll(
            ProtosFilesystemValue filesystem,
            ProtosPrelude prelude,
            ProtosActivation activation,
            String... names) {
        ProtosFutureValue opened =
                assertFuture(
                        ProtosInvocation.invokeMessage(
                                filesystem,
                                "open",
                                List.of(path(prelude, names)),
                                activation));
        assertEquals(ProtosFutureValue.State.RESOLVED, opened.state());
        ProtosObjectValue file =
                assertInstanceOf(ProtosObjectValue.class, opened.resolvedValue().orElseThrow());

        ArrayList<Byte> result = new ArrayList<>();
        while (true) {
            ProtosFutureValue read =
                    assertFuture(
                            ProtosInvocation.invokeMessage(
                                    file,
                                    "read",
                                    List.of(new ProtosIntegerValue(4096)),
                                    activation));
            assertEquals(ProtosFutureValue.State.RESOLVED, read.state());
            Object readValue = read.resolvedValue().orElseThrow();
            if (readValue == ProtosNullValue.INSTANCE) {
                break;
            }
            ProtosBytesValue bytes = assertInstanceOf(ProtosBytesValue.class, readValue);
            for (Object octet : bytes.indexedSnapshot()) {
                result.add(
                        (byte)
                                Integer.parseInt(com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerDecimalText(octet)));
            }
        }
        ProtosFutureValue close =
                assertFuture(ProtosInvocation.invokeMessage(file, "close", List.of(), activation));
        assertEquals(ProtosFutureValue.State.RESOLVED, close.state());

        byte[] raw = new byte[result.size()];
        for (int index = 0; index < raw.length; index++) {
            raw[index] = result.get(index);
        }
        return raw;
    }

    private static void assertMissing(
            ProtosFilesystemValue filesystem,
            ProtosPrelude prelude,
            ProtosActivation activation,
            String... names) {
        ProtosFutureValue opened =
                assertFuture(
                        ProtosInvocation.invokeMessage(
                                filesystem,
                                "open",
                                List.of(path(prelude, names)),
                                activation));
        assertEquals(ProtosFutureValue.State.FAILED, opened.state());
    }

    private static ProtosFutureValue assertFuture(Object value) {
        return assertInstanceOf(ProtosFutureValue.class, value);
    }

    private static ProtosPathValue path(ProtosPrelude prelude, String... names) {
        return new ProtosPathValue(prelude.pathPrototype(), List.of(names));
    }
}
