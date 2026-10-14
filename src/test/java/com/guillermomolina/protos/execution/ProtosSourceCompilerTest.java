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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.guillermomolina.protos.parser.ParseError;
import com.guillermomolina.protos.runtime.ProtosActivation;
import com.guillermomolina.protos.runtime.ProtosPrelude;
import com.guillermomolina.protos.runtime.ProtosIntegerValue;
import com.guillermomolina.protos.runtime.ProtosObjectValue;
import com.oracle.truffle.api.source.Source;

import java.util.List;
import org.junit.jupiter.api.Test;

class ProtosSourceCompilerTest {
    @Test
    void compilesCompleteSourceThroughParserCanonicalizerAndBytecodeLowerer() {
        Object result = compileAndCall("1\n2");

        ProtosIntegerValue integer =
                assertInstanceOf(ProtosIntegerValue.class, result);
        assertEquals(String.valueOf("2"), com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerDecimalText(integer));
    }

    @Test
    void compiledObjectSourceUsesOrdinaryObjectExecution() {
        Object result = compileAndCall("{ value: 7 }");

        ProtosObjectValue object =
                assertInstanceOf(ProtosObjectValue.class, result);
        ProtosIntegerValue value =
                assertInstanceOf(
                        ProtosIntegerValue.class,
                        object.readLocalSlot("value").orElseThrow());
        assertEquals(String.valueOf("7"), com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerDecimalText(value));
        assertSame(
                ProtosObjectValue.rootObject(),
                object.parent().orElseThrow());
    }

    @Test
    void parserFailuresPropagateWithoutCreatingAnExecutionTarget() {
        assertThrows(
                ParseError.class,
                () ->
                        ProtosTestExecutionSupport.callEntered(
                                () -> {
                                    ProtosLanguageContext context =
                                            ProtosLanguageContext.current();
                                    Source source =
                                            Source.newBuilder(
                                                            ProtosLanguage.ID,
                                                            "name\n)",
                                                            "source-compiler-invalid.protos")
                                                    .mimeType(ProtosLanguage.MIME_TYPE)
                                                    .build();
                                    return new ProtosSourceCompiler()
                                            .compileBytecode(
                                                    source,
                                                    context.languageForTesting(), ProtosExecutionMode.NORMAL);
                                }));
    }

    private static Object compileAndCall(String characters) {
        ProtosActivation activation = freshTopLevelActivation();
        return ProtosTestExecutionSupport.callEntered(
                () -> {
                    ProtosLanguageContext context =
                            ProtosLanguageContext.current();
                    Source source =
                            Source.newBuilder(
                                            ProtosLanguage.ID,
                                            characters,
                                            "source-compiler-test.protos")
                                    .mimeType(ProtosLanguage.MIME_TYPE)
                                    .build();
                    return new ProtosSourceCompiler()
                            .compileBytecode(
                                    source,
                                    context.languageForTesting(), ProtosExecutionMode.NORMAL)
                            .call(activation);
                });
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
