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
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.guillermomolina.protos.runtime.ProtosActivation;
import com.guillermomolina.protos.runtime.ProtosPrelude;
import com.guillermomolina.protos.runtime.ProtosIntegerValue;
import com.guillermomolina.protos.runtime.ProtosObjectValue;
import java.io.IOException;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class ProtosSourceFileLoaderTest {
    @Test
    void loadsUtf8SourceThroughTheOrdinaryCompilerPipeline() throws IOException {
        Path file = Files.createTempFile("protos-source-loader-", ".protos");
        try {
            Files.writeString(file, "41\n42", StandardCharsets.UTF_8);

            Object result;
            try (ProtosPolyglotExecutionContext context =
                    ProtosPolyglotExecutionContext.open(
                            java.io.InputStream.nullInputStream(),
                            java.io.OutputStream.nullOutputStream(),
                            java.io.OutputStream.nullOutputStream())) {
                result =
                        context.callEntered(
                                () -> {
                                    try {
                                        return new ProtosSourceFileLoader()
                                                .load(file)
                                                .call(freshTopLevelActivation());
                                    } catch (IOException failure) {
                                        throw new java.io.UncheckedIOException(failure);
                                    }
                                });
            }

            ProtosIntegerValue integer =
                    assertInstanceOf(ProtosIntegerValue.class, result);
            assertEquals(String.valueOf("42"), com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerDecimalText(integer));
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    void missingSourceFilePropagatesHostLoadingFailure() {
        Path missing =
                Path.of(
                        "target",
                        "definitely-missing-protos-source-"
                                + System.nanoTime()
                                + ".protos");

        assertThrows(
                IOException.class,
                () -> new ProtosSourceFileLoader().load(missing));
    }

    private static ProtosActivation freshTopLevelActivation() {
        ProtosObjectValue contextPrototype =
                new ProtosObjectValue(ProtosObjectValue.rootObject());
        ProtosObjectValue bindings = new ProtosObjectValue(contextPrototype);
        bindings.createLocalSlot("Context", contextPrototype);
        bindings.createLocalSlot(
                "Error",
                new ProtosObjectValue(ProtosObjectValue.rootObject()));
        bindings.freeze();
        ProtosObjectValue context =
                new ProtosPrelude(bindings, contextPrototype)
                        .newExecutionContext();
        return new ProtosActivation(context, List.of(), context);
    }
}
