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

import com.guillermomolina.protos.runtime.ProtosActivation;
import com.guillermomolina.protos.runtime.ProtosIntegerValue;
import com.guillermomolina.protos.runtime.ProtosPrelude;
import com.guillermomolina.protos.runtime.ProtosProcessRuntime;
import com.oracle.truffle.api.source.Source;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ProtosPolyglotNestedCallTargetSharingTest {
    private static final Path CORE = Path.of("protos", "lib", "core");

    @Test
    void closureCreatedByHostedProcessParseInvokesInsideExactProcessSharingLayer() throws Exception {
        assertHostedResult(
                "identity: (value) => { value }\nidentity(42)",
                "<hosted-closure-sharing>",
                42);
    }

    @Test
    void closureNestedInsideHostedClosureUsesExactEnteredContextLanguage() throws Exception {
        assertHostedResult(
                "outer: (value) => { inner: () => { value }\ninner() }\nouter(42)",
                "<hosted-nested-closure-sharing>",
                42);
    }

    @Test
    void objectBodyCreatedByHostedProcessParseExecutesInsideExactProcessSharingLayer() throws Exception {
        assertHostedResult(
                "box: { value: 43 }\nbox.value",
                "<hosted-object-sharing>",
                43);
    }

    @Test
    void coreClosurePreparedBeforeHostingIsProjectedIntoEnteredProcessContext() throws Exception {
        assertHostedResult(
                "absolute: (n) => { result: n\n(n < 0).ifTrue() { result = 0 - n }\nresult }\nabsolute(-42)",
                "<hosted-precontext-core-closure-sharing>",
                42);
    }

    private static void assertHostedResult(String program, String name, long expected)
            throws Exception {
        ProtosPrelude prelude = new ProtosCoreBootstrap().bootstrap(CORE);
        ProtosProcessRuntime process =
                new ProtosProcessRuntime(prelude.actorRefPrototypeForRuntime());
        ProtosActivation activation =
                ProtosActorBootstrap.newStandaloneInitialActivation(process, prelude);
        Source source =
                Source.newBuilder(ProtosLanguage.ID, program, name)
                        .mimeType(ProtosLanguage.MIME_TYPE)
                        .build();

        try (ProtosPolyglotRuntimeHost host = ProtosPolyglotRuntimeHost.open()) {
            ProtosPolyglotProcessContext context =
                    host.hostProcess(
                            process,
                            InputStream.nullInputStream(),
                            OutputStream.nullOutputStream(),
                            OutputStream.nullOutputStream());
            ProtosExecutionOutcome outcome = context.execute(source, activation);

            assertEquals(ProtosExecutionOutcome.State.COMPLETED, outcome.state());
            ProtosIntegerValue result = assertInstanceOf(ProtosIntegerValue.class, outcome.value());
            assertEquals(Long.toString(expected), com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerDecimalText(result));

            process.requestTerminationForRuntime();
            assertEquals(ProtosProcessRuntime.LifecycleState.TERMINATED, process.lifecycleState());
        }
    }
}
