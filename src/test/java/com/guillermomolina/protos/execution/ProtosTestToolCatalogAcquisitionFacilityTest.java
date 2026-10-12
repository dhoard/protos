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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.guillermomolina.protos.runtime.ProtosActivation;
import com.guillermomolina.protos.runtime.ProtosBytesValue;
import com.guillermomolina.protos.runtime.ProtosClosureValue;
import com.guillermomolina.protos.runtime.ProtosIntegerValue;
import com.guillermomolina.protos.runtime.ProtosPrelude;
import com.guillermomolina.protos.runtime.ProtosSignalException;
import com.guillermomolina.protos.runtime.ProtosStringValue;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class ProtosTestToolCatalogAcquisitionFacilityTest {
    private static final Path CORE = Path.of("protos", "lib", "core");
    private static final Path STANDARD_LIBRARY = Path.of("protos", "lib");

    @TempDir Path tempDir;

    @Test
    void relativePathUsesCapturedInvocationWorkingDirectoryAndReturnsFrozenRawBytes()
            throws Exception {
        Path nested = Files.createDirectories(tempDir.resolve("nested"));
        byte[] expected = new byte[] {0x00, (byte) 0xff, 0x0a, 0x41};
        Files.write(nested.resolve("catalog.toml"), expected);

        Fixture fixture = fixture(tempDir);
        ProtosBytesValue observed =
                assertInstanceOf(
                        ProtosBytesValue.class,
                        invoke(fixture, new ProtosStringValue("nested/catalog.toml")));

        assertTrue(observed.isFrozen());
        assertArrayEquals(expected, octets(observed));
    }

    @Test
    void absolutePathIsAcceptedWithoutRebasing() throws Exception {
        Path catalog = tempDir.resolve("absolute.toml").toAbsolutePath();
        byte[] expected = "resource-catalog-version = 1\n".getBytes(StandardCharsets.UTF_8);
        Files.write(catalog, expected);

        Fixture fixture = fixture(tempDir.resolve("unrelated-cwd"));
        ProtosBytesValue observed =
                assertInstanceOf(
                        ProtosBytesValue.class,
                        invoke(fixture, new ProtosStringValue(catalog.toString())));

        assertArrayEquals(expected, octets(observed));
    }

    @Test
    void ordinarySymbolicLinkResolutionIsAllowed() throws Exception {
        Path target = tempDir.resolve("target.toml");
        byte[] expected = new byte[] {1, 2, 3, 4};
        Files.write(target, expected);

        Path link = tempDir.resolve("catalog-link.toml");
        try {
            Files.createSymbolicLink(link, target.getFileName());
        } catch (UnsupportedOperationException | IOException | SecurityException unsupported) {
            Assumptions.assumeTrue(false, "symbolic links unavailable: " + unsupported);
        }

        Fixture fixture = fixture(tempDir);
        ProtosBytesValue observed =
                assertInstanceOf(
                        ProtosBytesValue.class,
                        invoke(fixture, new ProtosStringValue(link.getFileName().toString())));

        assertArrayEquals(expected, octets(observed));
    }

    @Test
    void missingDirectoryAndInvalidPathsFailClosed() throws Exception {
        Fixture missing = fixture(tempDir);
        assertThrows(
                ProtosSignalException.class,
                () -> invoke(missing, new ProtosStringValue("missing.toml")));

        Path directory = Files.createDirectories(tempDir.resolve("catalog-dir"));
        Fixture nonRegular = fixture(tempDir);
        assertThrows(
                ProtosSignalException.class,
                () -> invoke(nonRegular, new ProtosStringValue(directory.toString())));

        Fixture invalid = fixture(tempDir);
        assertThrows(
                ProtosSignalException.class,
                () -> invoke(invalid, new ProtosStringValue("\u0000")));
    }

    @Test
    void authorityIsOneShotAndAFailedAttemptAlsoConsumesIt() throws Exception {
        Path first = tempDir.resolve("first.toml");
        Path second = tempDir.resolve("second.toml");
        Files.write(first, new byte[] {10});
        Files.write(second, new byte[] {20});

        Fixture successfulFirst = fixture(tempDir);
        assertInstanceOf(
                ProtosBytesValue.class,
                invoke(successfulFirst, new ProtosStringValue(first.toString())));
        assertThrows(
                ProtosSignalException.class,
                () -> invoke(successfulFirst, new ProtosStringValue(second.toString())));

        Fixture failedFirst = fixture(tempDir);
        Path initiallyMissing = tempDir.resolve("created-after-failure.toml");
        assertThrows(
                ProtosSignalException.class,
                () -> invoke(failedFirst, new ProtosStringValue(initiallyMissing.toString())));
        Files.write(initiallyMissing, new byte[] {30});
        assertThrows(
                ProtosSignalException.class,
                () -> invoke(failedFirst, new ProtosStringValue(initiallyMissing.toString())));
    }

    @Test
    void capabilityIsBootstrapLocalAndDoesNotCreateAGeneralFilesystemBinding()
            throws Exception {
        ProtosPrelude prelude = prelude();
        ProtosActivation activation = prelude.newModuleActivation();

        ProtosTestToolCatalogAcquisitionFacility.install(activation, tempDir);

        assertTrue(
                activation
                        .context()
                        .hasLocalSlot(ProtosTestToolCatalogAcquisitionFacility.BOOTSTRAP_SLOT));
        assertFalse(activation.context().hasLocalSlot("filesystem"));

        ProtosActivation independentActivation = prelude.newModuleActivation();
        assertFalse(
                independentActivation
                        .context()
                        .hasLocalSlot(ProtosTestToolCatalogAcquisitionFacility.BOOTSTRAP_SLOT));
    }

    @Test
    void wrongArityOrValueFamilyFailsClosed() throws Exception {
        Fixture wrongArity = fixture(tempDir);
        assertThrows(
                ProtosSignalException.class,
                () ->
                        wrongArity
                                .closure()
                                .nativeBody()
                                .orElseThrow()
                                .execute(wrongArity.activation(), List.of()));

        Fixture wrongType = fixture(tempDir);
        assertThrows(
                ProtosSignalException.class,
                () ->
                        invoke(
                                wrongType,
                                new ProtosIntegerValue(1L)));
    }

    private static Fixture fixture(Path cwd) throws Exception {
        ProtosPrelude prelude = prelude();
        ProtosActivation activation = prelude.newModuleActivation();
        ProtosTestToolCatalogAcquisitionFacility.install(activation, cwd);
        ProtosClosureValue closure =
                assertInstanceOf(
                        ProtosClosureValue.class,
                        activation
                                .context()
                                .readLocalSlot(
                                        ProtosTestToolCatalogAcquisitionFacility.BOOTSTRAP_SLOT)
                                .orElseThrow());
        return new Fixture(activation, closure);
    }

    private static ProtosPrelude prelude() throws Exception {
        return new ProtosCoreBootstrap()
                .bootstrap(
                        CORE,
                        new ProtosStandardLibraryModuleResolver(STANDARD_LIBRARY));
    }

    private static Object invoke(Fixture fixture, Object argument) {
        return fixture
                .closure()
                .nativeBody()
                .orElseThrow()
                .execute(fixture.activation(), List.of(argument));
    }

    private static byte[] octets(ProtosBytesValue bytes) {
        byte[] result = new byte[bytes.indexedSize()];
        for (int index = 0; index < result.length; index++) {
            ProtosIntegerValue octet =
                    assertInstanceOf(
                            ProtosIntegerValue.class,
                            bytes.indexedAt(index));
            result[index] = (byte) Integer.parseInt(com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerDecimalText(octet));
        }
        return result;
    }

    private record Fixture(
            ProtosActivation activation,
            ProtosClosureValue closure) {}
}
