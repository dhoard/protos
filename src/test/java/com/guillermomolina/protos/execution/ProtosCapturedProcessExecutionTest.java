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

import static org.junit.jupiter.api.Assertions.*;

import com.guillermomolina.protos.runtime.ProtosEncodingValue;
import com.guillermomolina.protos.runtime.ProtosEnvironmentValue;
import com.guillermomolina.protos.runtime.ProtosIntegerValue;
import com.guillermomolina.protos.runtime.ProtosPrelude;
import com.oracle.truffle.api.source.Source;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

final class ProtosCapturedProcessExecutionTest {
    private static final Path CORE = Path.of("protos", "lib", "core");
    private static final Path STANDARD_LIBRARY = Path.of("protos", "lib");
    private static final Path CASE =
            Path.of("protos", "tests", "tooling", "tool002-c-single-case.protos");

    @Test
    void exactProtosCaseGetsPrivateStdoutAndStderrPerExecution() throws Exception {
        ProtosPrelude prelude =
                new ProtosCoreBootstrap()
                        .bootstrap(
                                CORE,
                                new ProtosStandardLibraryModuleResolver(STANDARD_LIBRARY));
        ProtosEncodingValue utf8 = encoding(prelude, "UTF8");
        Source entry =
                source(Files.readString(CASE, StandardCharsets.UTF_8), "<captured-case>");

        ProtosCapturedProcessExecution.Result first =
                ProtosCapturedProcessExecution.execute(
                        request(prelude, entry, utf8, "first-out", "first-err"));
        ProtosCapturedProcessExecution.Result second =
                ProtosCapturedProcessExecution.execute(
                        request(prelude, entry, utf8, "second-out", "second-err"));

        assertCompletedInteger(first, 42);
        assertCompletedInteger(second, 42);

        assertEquals(
                "first-out\n",
                new String(first.stdout(), StandardCharsets.UTF_8));
        assertEquals(
                "first-err\n",
                new String(first.stderr(), StandardCharsets.UTF_8));

        assertEquals(
                "second-out\n",
                new String(second.stdout(), StandardCharsets.UTF_8));
        assertEquals(
                "second-err\n",
                new String(second.stderr(), StandardCharsets.UTF_8));
    }

    @Test
    void semanticFailurePreservesAlreadyCommittedPrivateOutput() throws Exception {
        ProtosPrelude prelude =
                new ProtosCoreBootstrap()
                        .bootstrap(
                                CORE,
                                new ProtosStandardLibraryModuleResolver(STANDARD_LIBRARY));
        ProtosEncodingValue utf8 = encoding(prelude, "UTF8");
        Source entry =
                source(
                        "writer: TextWriter(process.stdout(), process.stdoutEncoding())\n"
                                + "writer.writeLine(\"before-failure\").value()\n"
                                + "1.definitelyMissing()",
                        "<captured-failure>");

        ProtosCapturedProcessExecution.Result result =
                ProtosCapturedProcessExecution.execute(
                        request(prelude, entry, utf8, "unused-out", "unused-err"));

        assertEquals(ProtosExecutionOutcome.State.FAILED, result.outcome().state());
        assertNotNull(result.outcome().error());
        assertEquals(
                "before-failure\n",
                new String(result.stdout(), StandardCharsets.UTF_8));
        assertEquals(0, result.stderr().length);
    }

    @Test
    void requestAndResultByteArraysAreDefensivelyDetached() throws Exception {
        ProtosPrelude prelude =
                new ProtosCoreBootstrap()
                        .bootstrap(
                                CORE,
                                new ProtosStandardLibraryModuleResolver(STANDARD_LIBRARY));
        ProtosEncodingValue utf8 = encoding(prelude, "UTF8");
        byte[] stdin = new byte[] {1, 2, 3};

        ProtosCapturedProcessExecution.Request request =
                new ProtosCapturedProcessExecution.Request(
                        prelude,
                        source("7", "<captured-detach>"),
                        List.of(),
                        exactEnvironmentDomain(),
                        List.of(),
                        stdin,
                        utf8,
                        utf8,
                        utf8,
                        null);

        stdin[0] = 99;
        assertArrayEquals(new byte[] {1, 2, 3}, request.stdin());

        ProtosCapturedProcessExecution.Result result =
                ProtosCapturedProcessExecution.execute(request);
        byte[] stdout = result.stdout();
        if (stdout.length > 0) {
            stdout[0] ^= 1;
        }
        assertArrayEquals(new byte[0], result.stdout());
    }

    private static ProtosCapturedProcessExecution.Request request(
            ProtosPrelude prelude,
            Source entry,
            ProtosEncodingValue utf8,
            String stdoutArgument,
            String stderrArgument) {
        return new ProtosCapturedProcessExecution.Request(
                prelude,
                entry,
                List.of(stdoutArgument, stderrArgument),
                exactEnvironmentDomain(),
                List.of(new ProtosEnvironmentValue.NativeEntry("TOOL002_C", "1")),
                new byte[0],
                utf8,
                utf8,
                utf8,
                null);
    }

    private static Source source(String characters, String name) {
        return Source.newBuilder(ProtosLanguage.ID, characters, name)
                .mimeType(ProtosLanguage.MIME_TYPE)
                .build();
    }

    private static void assertCompletedInteger(
            ProtosCapturedProcessExecution.Result result,
            long expected) {
        assertEquals(
                ProtosExecutionOutcome.State.COMPLETED,
                result.outcome().state());
        ProtosIntegerValue integer =
                assertInstanceOf(
                        ProtosIntegerValue.class,
                        result.outcome().value());
        assertEquals(Long.toString(expected), com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerDecimalText(integer));
        assertNull(result.outcome().error());
    }

    private static ProtosEnvironmentValue.NativeNameDomain exactEnvironmentDomain() {
        return new ProtosEnvironmentValue.NativeNameDomain() {
            @Override
            public boolean sameCapturedName(String left, String right) {
                return left.equals(right);
            }

            @Override
            public boolean isQueryRepresentable(String name) {
                return !name.contains("=") && name.indexOf('\0') < 0;
            }

            @Override
            public boolean matchesQuery(String captured, String query) {
                return captured.equals(query);
            }
        };
    }

    private static ProtosEncodingValue encoding(ProtosPrelude prelude, String name) {
        return assertInstanceOf(
                ProtosEncodingValue.class,
                prelude.encodingPrototype().readLocalSlot(name).orElseThrow());
    }
}
