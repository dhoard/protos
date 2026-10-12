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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.guillermomolina.protos.runtime.ProtosActivation;
import com.guillermomolina.protos.runtime.ProtosArrayValue;
import com.guillermomolina.protos.runtime.ProtosEncodingValue;
import com.guillermomolina.protos.runtime.ProtosEnvironmentValue;
import com.guillermomolina.protos.runtime.ProtosFutureValue;
import com.guillermomolina.protos.runtime.ProtosNullValue;
import com.guillermomolina.protos.runtime.ProtosObjectValue;
import com.guillermomolina.protos.runtime.ProtosPrelude;
import com.guillermomolina.protos.runtime.ProtosProcessCapabilityValue;
import com.guillermomolina.protos.runtime.ProtosProcessRuntime;
import com.guillermomolina.protos.runtime.ProtosProcessStandardStreamValue;
import com.guillermomolina.protos.runtime.ProtosSignalException;
import com.guillermomolina.protos.runtime.ProtosStringValue;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

final class ProtosStandardProcessProtocolTest {
    private static final Path CORE = Path.of("protos", "lib", "core");

    @Test
    void processPrototypeIsFrozenAuthorityFreeAndHasExactlyEightAccessors()
            throws Exception {
        ProtosPrelude prelude = new ProtosCoreBootstrap().bootstrap(CORE);
        ProtosObjectValue process = prelude.processPrototype();

        assertSame(ProtosObjectValue.rootObject(), process.parent().orElseThrow());
        assertTrue(process.isFrozen());
        assertEquals(
                Set.of(
                        "args",
                        "environment",
                        "stdin",
                        "stdinEncoding",
                        "stdout",
                        "stdoutEncoding",
                        "stderr",
                        "stderrEncoding"),
                process.localSlotsSnapshot().keySet());
        assertFalse(process.hasLocalSlot("call"));
        assertFalse(process.hasLocalSlot("filesystem"));
    }

    // args/environment content and normal access are executable Protos conformance.
    // Keep the resource-backed standard stream surface and the args representation here.
    @Test
    void argsAccessorExposesStableContentAsFrozenOrdinaryArray() throws Exception {
        Fixture fixture = fixture(true, true, true);

        ProtosArrayValue first =
                assertInstanceOf(ProtosArrayValue.class, fixture.invoke("args"));
        ProtosArrayValue second =
                assertInstanceOf(ProtosArrayValue.class, fixture.invoke("args"));

        // D168: only content is portable; no identity relation between acquisitions is asserted.
        assertSame(fixture.prelude.arrayPrototype(), first.parent().orElseThrow());
        assertTrue(first.isFrozen());
        assertTrue(second.isFrozen());
        assertEquals(List.of("one", "two"), strings(first));
        assertEquals(List.of("one", "two"), strings(second));
        assertThrows(
                IllegalStateException.class,
                () -> first.indexedPut(0, new ProtosStringValue("changed")));
        assertEquals(List.of("one", "two"), strings(first));
    }

    @Test
    void delegatedProcessCapabilityReacquiresArgumentContent() throws Exception {
        Fixture fixture = fixture(true, true, true);
        ProtosProcessCapabilityValue delegated =
                assertInstanceOf(
                        ProtosProcessCapabilityValue.class,
                        com.guillermomolina.protos.runtime.ProtosActorValueTransfer
                                .snapshotValue(fixture.capability, fixture.activation));

        ProtosArrayValue arguments =
                assertInstanceOf(
                        ProtosArrayValue.class,
                        ProtosInvocation.invokeMessage(
                                delegated, "args", List.of(), fixture.activation));

        assertTrue(arguments.isFrozen());
        assertEquals(List.of("one", "two"), strings(arguments));
    }

    private static List<String> strings(ProtosArrayValue array) {
        return array.indexedSnapshot().stream()
                .map(element -> ((ProtosStringValue) element).value())
                .toList();
    }

    @Test
    void successfulStreamAndEncodingAccessorsExposeEstablishedBootstrapState()
            throws Exception {
        Fixture fixture = fixture(true, true, true);

        Object stdin1 = fixture.invoke("stdin");
        Object stdin2 = fixture.invoke("stdin");
        assertInstanceOf(ProtosProcessStandardStreamValue.class, stdin1);
        assertInstanceOf(ProtosProcessStandardStreamValue.class, stdin2);
        assertNotSame(stdin1, stdin2);

        assertInstanceOf(
                ProtosProcessStandardStreamValue.class, fixture.invoke("stdout"));
        assertInstanceOf(
                ProtosProcessStandardStreamValue.class, fixture.invoke("stderr"));

        assertSame(fixture.utf8, fixture.invoke("stdinEncoding"));
        assertSame(fixture.utf8, fixture.invoke("stdinEncoding"));
        assertSame(fixture.latin1, fixture.invoke("stdoutEncoding"));
        assertSame(fixture.utf16be, fixture.invoke("stderrEncoding"));

        assertFalse(fixture.invoke("stdin") instanceof ProtosFutureValue);
        assertFalse(fixture.invoke("stdoutEncoding") instanceof ProtosFutureValue);
    }

    @Test
    void unavailableStreamAndEncodingFailWithFreshOrdinaryErrors()
            throws Exception {
        Fixture fixture = fixture(true, false, true);

        ProtosSignalException first =
                assertThrows(
                        ProtosSignalException.class,
                        () -> fixture.invoke("stdout"));
        ProtosSignalException second =
                assertThrows(
                        ProtosSignalException.class,
                        () -> fixture.invoke("stdout"));
        ProtosSignalException encoding =
                assertThrows(
                        ProtosSignalException.class,
                        () -> fixture.invoke("stdoutEncoding"));

        assertNotSame(first.error(), second.error());
        assertSame(
                fixture.prelude.errorPrototype(),
                first.error().parent().orElseThrow());
        assertSame(
                fixture.prelude.errorPrototype(),
                encoding.error().parent().orElseThrow());
    }

    @Test
    void invalidEncodingAssociationFailsInsteadOfInferringDefault()
            throws Exception {
        ProtosPrelude prelude = new ProtosCoreBootstrap().bootstrap(CORE);
        ProtosProcessRuntime process = processRuntime();

        establishSnapshots(prelude, process);
        establishStreams(prelude, process, true, false, false);
        ProtosEncodingValue utf8 = encoding(prelude, "UTF8");

        process.establishStandardStreamEncodingsForRuntime(null, utf8, null);

        ProtosProcessCapabilityValue capability =
                process.provisionCapabilityForRuntime(prelude.processPrototype());
        ProtosActivation activation = prelude.newModuleActivation();

        assertThrows(
                ProtosSignalException.class,
                () ->
                        ProtosInvocation.invokeMessage(
                                capability,
                                "stdinEncoding",
                                List.of(),
                                activation));
        assertThrows(
                ProtosSignalException.class,
                () ->
                        ProtosInvocation.invokeMessage(
                                capability,
                                "stdoutEncoding",
                                List.of(),
                                activation));

        assertInstanceOf(
                ProtosProcessStandardStreamValue.class,
                ProtosInvocation.invokeMessage(
                        capability, "stdin", List.of(), activation));
    }

    @Test
    void everyAccessorRejectsAllExistingProxiesAfterProcessTerminationCutover()
            throws Exception {
        Fixture fixture = fixture(true, true, true);

        ProtosProcessCapabilityValue delegated =
                assertInstanceOf(
                        ProtosProcessCapabilityValue.class,
                        com.guillermomolina.protos.runtime.ProtosActorValueTransfer
                                .snapshotValue(
                                        fixture.capability,
                                        fixture.activation));

        assertTrue(fixture.process.requestTerminationForRuntime());

        for (String selector :
                List.of(
                        "args",
                        "environment",
                        "stdin",
                        "stdinEncoding",
                        "stdout",
                        "stdoutEncoding",
                        "stderr",
                        "stderrEncoding")) {
            assertThrows(
                    ProtosSignalException.class,
                    () ->
                            ProtosInvocation.invokeMessage(
                                    fixture.capability,
                                    selector,
                                    List.of(),
                                    fixture.activation),
                    selector);
            assertThrows(
                    ProtosSignalException.class,
                    () ->
                            ProtosInvocation.invokeMessage(
                                    delegated,
                                    selector,
                                    List.of(),
                                    fixture.activation),
                    "delegated " + selector);
        }
    }

    // args/environment arity is executable Process snapshot conformance.
    @Test
    void streamAccessorArityIsExactAndSynchronous() throws Exception {
        Fixture fixture = fixture(true, true, true);

        assertThrows(
                ProtosSignalException.class,
                () ->
                        ProtosInvocation.invokeMessage(
                                fixture.capability,
                                "stdin",
                                List.of(ProtosNullValue.INSTANCE),
                                fixture.activation));
    }

    private static Fixture fixture(
            boolean stdin, boolean stdout, boolean stderr) throws Exception {
        ProtosPrelude prelude = new ProtosCoreBootstrap().bootstrap(CORE);
        ProtosProcessRuntime process = processRuntime();

        establishSnapshots(prelude, process);
        establishStreams(prelude, process, stdin, stdout, stderr);

        ProtosEncodingValue utf8 = encoding(prelude, "UTF8");
        ProtosEncodingValue latin1 = encoding(prelude, "Latin1");
        ProtosEncodingValue utf16be = encoding(prelude, "UTF16BE");
        process.establishStandardStreamEncodingsForRuntime(
                stdin ? utf8 : null,
                stdout ? latin1 : null,
                stderr ? utf16be : null);

        ProtosProcessCapabilityValue capability =
                process.provisionCapabilityForRuntime(prelude.processPrototype());
        return new Fixture(
                prelude,
                process,
                capability,
                prelude.newModuleActivation(),
                utf8,
                latin1,
                utf16be);
    }

    private static void establishSnapshots(
            ProtosPrelude prelude, ProtosProcessRuntime process) {
        process.establishArgumentsForRuntime(List.of("one", "two"));
        process.establishEnvironmentForRuntime(
                ProtosStandardEnvironmentProtocol.createPrototype(),
                exactEnvironmentDomain(),
                List.of(new ProtosEnvironmentValue.NativeEntry("A", "one")));
    }

    private static void establishStreams(
            ProtosPrelude prelude,
            ProtosProcessRuntime process,
            boolean stdin,
            boolean stdout,
            boolean stderr) {
        ProtosObjectValue bytesPrototype =
                new ProtosObjectValue(ProtosObjectValue.rootObject());
        ProtosStandardBytesProtocol.install(bytesPrototype);
        process.establishStandardStreamsForRuntime(
                ProtosStandardProcessStreamProtocol.createReadablePrototype(),
                ProtosStandardProcessStreamProtocol.createWritablePrototype(),
                bytesPrototype,
                stdin ? (maxBytes, completion) -> () -> {} : null,
                stdout ? (bytes, completion) -> () -> {} : null,
                stderr ? (bytes, completion) -> () -> {} : null);
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

    private static ProtosEncodingValue encoding(
            ProtosPrelude prelude, String name) {
        return assertInstanceOf(
                ProtosEncodingValue.class,
                prelude.encodingPrototype().readLocalSlot(name).orElseThrow());
    }

    private static ProtosProcessRuntime processRuntime() {
        return new ProtosProcessRuntime(
                new ProtosObjectValue(ProtosObjectValue.rootObject()).freeze());
    }

    private record Fixture(
            ProtosPrelude prelude,
            ProtosProcessRuntime process,
            ProtosProcessCapabilityValue capability,
            ProtosActivation activation,
            ProtosEncodingValue utf8,
            ProtosEncodingValue latin1,
            ProtosEncodingValue utf16be) {
        Object invoke(String selector) {
            return ProtosInvocation.invokeMessage(
                    capability, selector, List.of(), activation);
        }
    }
}
