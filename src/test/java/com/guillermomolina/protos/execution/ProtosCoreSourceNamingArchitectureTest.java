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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class ProtosCoreSourceNamingArchitectureTest {
    private static final Path CORE = Path.of("protos", "lib", "core");

    private static final Set<String> CANONICAL_ONE_OWNER_SOURCES =
            Set.of(
                    "Object.protos",
                    "Context.protos",
                    "Number.protos",
                    "Integer.protos",
                    "SmallInteger.protos",
                    "BigInteger.protos",
                    "Fraction.protos",
                    "Float.protos",
                    "Error.protos",
                    "InvalidReturn.protos",
                    "Array.protos",
                    "Any.protos",
                    "Capture.protos",
                    "String.protos",
                    "Encoding.protos",
                    "Map.protos",
                    "IdentityMap.protos",
                    "Path.protos",
                    "IpAddress.protos",
                    "IpEndpoint.protos",
                    "Network.protos",
                    "TcpConnection.protos",
                    "TcpListener.protos",
                    "Future.protos",
                    "Actor.protos",
                    "Process.protos",
                    "TextReader.protos",
                    "TextWriter.protos",
                    "Bytes.protos");

    private static final Set<String> REVIEWED_RESPONSIBILITY_SOURCES =
            Set.of(
                    "error_taxonomy.protos",
                    "import.protos",
                    "prelude.protos");

    @Test
    void everyCoreSourceNameIsEitherCanonicalOneOwnerOrExplicitlyReviewed()
            throws IOException {
        Set<String> expected = new HashSet<>(CANONICAL_ONE_OWNER_SOURCES);
        expected.addAll(REVIEWED_RESPONSIBILITY_SOURCES);

        Set<String> actual;
        try (var paths = Files.list(CORE)) {
            actual =
                    paths.filter(Files::isRegularFile)
                            .map(path -> path.getFileName().toString())
                            .filter(name -> name.endsWith(".protos"))
                            .collect(Collectors.toSet());
        }

        assertEquals(expected, actual);
    }

    @Test
    void oneOwnerSourcesCarryTheRequiredPartFiveNotice() throws IOException {
        String required =
                "// THE LICENSED WORK IS PROVIDED UNDER THE TERMS OF THE ADAPTIVE PUBLIC LICENSE";
        for (String name : CANONICAL_ONE_OWNER_SOURCES) {
            String source = Files.readString(CORE.resolve(name), StandardCharsets.UTF_8);
            assertTrue(source.startsWith(required), name);
        }
    }
}
