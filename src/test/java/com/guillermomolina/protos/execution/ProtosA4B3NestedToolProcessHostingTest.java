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
import com.guillermomolina.protos.runtime.ProtosClosureValue;
import com.guillermomolina.protos.runtime.ProtosEnvironmentValue;
import com.guillermomolina.protos.runtime.ProtosIntegerValue;
import com.guillermomolina.protos.runtime.ProtosObjectValue;
import com.guillermomolina.protos.runtime.ProtosPrelude;
import com.guillermomolina.protos.runtime.ProtosProcessRuntime;
import com.guillermomolina.protos.runtime.ProtosStringValue;
import java.io.InputStream;
import java.io.OutputStream;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

final class ProtosA4B3NestedToolProcessHostingTest {
    private static final Path CORE = Path.of("protos", "lib", "core");

    @Test
    void exactChildProcessUsesDistinctContextOnOwningToolRuntimeHost() throws Exception {
        ProtosPrelude prelude = new ProtosCoreBootstrap().bootstrap(CORE);
        ProtosStandaloneProcessBootstrap.Result outer =
                ProtosStandaloneProcessBootstrap.create(
                        prelude,
                        List.of(),
                        exactEnvironmentDomain(),
                        List.of(),
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null);
        ProtosProcessRuntime outerProcess = outer.process();

        try (ProtosPolyglotRuntimeHost runtimeHost = ProtosPolyglotRuntimeHost.open()) {
            ProtosPolyglotProcessContext outerContext =
                    runtimeHost.hostProcess(
                            outerProcess,
                            InputStream.nullInputStream(),
                            OutputStream.nullOutputStream(),
                            OutputStream.nullOutputStream());
            try {
                ProtosActivation activation = outer.activation();
                ProtosExactExecutionFacility.install(activation, runtimeHost);
                ProtosClosureValue execution =
                        assertInstanceOf(
                                ProtosClosureValue.class,
                                activation.context()
                                        .readLocalSlot(ProtosExactExecutionFacility.BOOTSTRAP_SLOT)
                                        .orElseThrow());

                Object rawObservation =
                        outerContext.callForRuntime(
                                () ->
                                        ProtosClosureInvoker.invoke(
                                                execution,
                                                List.of(
                                                        new ProtosStringValue(
                                                                "result: 0\n"
                                                                        + "true.ifTrue() { result = 42 }\n"
                                                                        + "result")),
                                                activation));
                ProtosObjectValue observation =
                        assertInstanceOf(ProtosObjectValue.class, rawObservation);
                ProtosStringValue state =
                        assertInstanceOf(
                                ProtosStringValue.class,
                                observation.readLocalSlot("state").orElseThrow());
                ProtosIntegerValue value =
                        assertInstanceOf(
                                ProtosIntegerValue.class,
                                observation.readLocalSlot("value").orElseThrow());

                assertEquals("completed", state.value());
                assertEquals(String.valueOf("42"), com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerDecimalText(value));
                assertEquals(
                        1,
                        runtimeHost.activeProcessContextCountForTesting(),
                        "the exact child Context must close before control returns to the tool");
            } finally {
                outerProcess.requestTerminationForRuntime();
            }
            assertEquals(0, runtimeHost.activeProcessContextCountForTesting());
        }
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
}
