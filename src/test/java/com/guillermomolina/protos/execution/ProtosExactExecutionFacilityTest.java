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

import com.guillermomolina.protos.runtime.ProtosActivation;
import com.guillermomolina.protos.runtime.ProtosBooleanValue;
import com.guillermomolina.protos.runtime.ProtosBytesValue;
import com.guillermomolina.protos.runtime.ProtosCoreErrors;
import com.guillermomolina.protos.runtime.ProtosIntegerValue;
import com.guillermomolina.protos.runtime.ProtosModuleKey;
import com.guillermomolina.protos.runtime.ProtosNullValue;
import com.guillermomolina.protos.runtime.ProtosObjectValue;
import com.guillermomolina.protos.runtime.ProtosPrelude;
import com.guillermomolina.protos.runtime.ProtosStringValue;
import com.guillermomolina.protos.runtime.ProtosSignalException;
import java.io.IOException;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class ProtosExactExecutionFacilityTest {
    private static final Path CORE = Path.of("protos", "lib", "core");
    private static final Path STANDARD_LIBRARY = Path.of("protos", "lib");
    private static final Path FIXTURE =
            Path.of(
                    "protos",
                    "tests",
                    "tooling",
                    "tool002-d1-execution-facility.protos");
    private static final Path ACTOR_ROOT =
            Path.of("protos", "tests", "conformance", "actor");
    private static final Path GROUP_ROOT =
            Path.of("protos", "tests", "conformance", "group");
    private static final Path ACTOR_WORKERS = ACTOR_ROOT.resolve("modules").resolve("workers.protos");
    private static final Path GROUP_WORKERS = GROUP_ROOT.resolve("modules").resolve("workers.protos");

    // Legacy-route source fixtures kept as inline literals rather than read from
    // protos/tests/conformance/actor/spawn-request-echo.protos,
    // protos/tests/conformance/actor/ip-data-transfer.protos, and
    // protos/tests/conformance/group/request-selects-one-eligible-member.protos:
    // TOOL009-C Slice 3/Slice 4 migrated those files to suite-native Test Tool corpus
    // members whose raw module completion is no longer a bare Future, so this
    // whole-source legacy inspection route needs its own copy of the original behavior
    // it exercises.
    private static final String ACTOR_REQUEST_CASE_SOURCE =
            "worker: Actor.spawn(\"workers\", \"echo\")\n"
                    + "worker.request(\"echo\", 42)";
    private static final String ACTOR_CHAIN_CASE_SOURCE =
            "IpAddress: import(\"std:network/IpAddresses\").IpAddress\n"
                    + "IpEndpoint: import(\"std:network/IpEndpoints\").IpEndpoint\n"
                    + "address: IpAddress(6, 340282366920938463463374607431768211455)\n"
                    + "endpoint: IpEndpoint(address, 65535)\n"
                    + "worker: Actor.spawn(\"workers\", \"echo\")\n"
                    + "\n"
                    + "worker.request(\"echo\", endpoint).then((copy) => {\n"
                    + "    ok: true\n"
                    + "\n"
                    + "    IpEndpoint.recognizes(copy).ifFalse(() => { ok = false })\n"
                    + "    (copy.parent() === IpEndpoint).ifFalse(() => { ok = false })\n"
                    + "    (copy !== endpoint).ifFalse(() => { ok = false })\n"
                    + "    (copy.port == 65535).ifFalse(() => { ok = false })\n"
                    + "    (copy == endpoint).ifFalse(() => { ok = false })\n"
                    + "    (copy.hash() == endpoint.hash()).ifFalse(() => { ok = false })\n"
                    + "\n"
                    + "    IpAddress.recognizes(copy.address).ifFalse(() => { ok = false })\n"
                    + "    (copy.address.parent() === IpAddress).ifFalse(() => { ok = false })\n"
                    + "    (copy.address !== address).ifFalse(() => { ok = false })\n"
                    + "    (copy.address.version == 6).ifFalse(() => { ok = false })\n"
                    + "    (copy.address.bits =="
                    + " 340282366920938463463374607431768211455).ifFalse(() => { ok = false })\n"
                    + "    (copy.address == address).ifFalse(() => { ok = false })\n"
                    + "    (copy.address.hash() == address.hash()).ifFalse(() => { ok = false })\n"
                    + "\n"
                    + "    worker.stop()\n"
                    + "    ok\n"
                    + "})";
    private static final String GROUP_REQUEST_CASE_SOURCE =
            "first: Actor.spawn(\"workers\", \"tagged\", 1)\n"
                    + "second: Actor.spawn(\"workers\", \"tagged\", 2)\n"
                    + "group: Actor.group(first, second)\n"
                    + "group.request(\"tag\")";

    @Test
    void protosFixtureConsumesDetachedCompletedAndFailedOutcomes()
            throws Exception {
        Fixture fixture = fixture();
        Object result =
                evaluate(fixture,
                                Files.readString(
                                        FIXTURE,
                                        StandardCharsets.UTF_8));

        assertSame(ProtosBooleanValue.TRUE, result);
    }

    @Test
    void detachedFailedErrorAndCapturedStreamsRemainInspectable()
            throws Exception {
        Fixture fixture = fixture();

        Object errorValue =
                evaluate(fixture,
                                "execution(\"1.definitelyMissing()\").error");
        ProtosObjectValue error =
                assertInstanceOf(ProtosObjectValue.class, errorValue);
        assertSame(
                fixture.prelude
                        .bindings()
                        .readLocalSlot("SlotNotFound")
                        .orElseThrow(),
                error.parent().orElseThrow());

        Object stdoutValue =
                evaluate(fixture, "execution(\"42\").stdout");
        ProtosBytesValue stdout =
                assertInstanceOf(ProtosBytesValue.class, stdoutValue);
        assertEquals(0, stdout.indexedSize());

        Object stderrValue =
                evaluate(fixture, "execution(\"42\").stderr");
        ProtosBytesValue stderr =
                assertInstanceOf(ProtosBytesValue.class, stderrValue);
        assertEquals(0, stderr.indexedSize());
    }

    @Test
    void authorityBearingCompletedValueFailsClosedAtDetachedBoundary()
            throws Exception {
        Fixture fixture = fixture();

        ProtosSignalException signal =
                assertThrows(
                        ProtosSignalException.class,
                        () ->
                                evaluate(fixture, "execution(\"process\")"));

        assertSame(
                ProtosCoreErrors.prototype(
                        fixture.activation,
                        ProtosCoreErrors.StandardError.NON_TRANSFERABLE_VALUE),
                signal.error().parent().orElseThrow());
    }


    @Test
    void inspectionKeepsLiveSourceValueInsideFreshProcessUntilInspector()
            throws Exception {
        Fixture fixture = fixture();

        Object result =
                evaluate(fixture,
                                "observation: executionInspect("
                                        + "\"future: (() => { 42 }).future()\\n"
                                        + "() => { future.value() }\", "
                                        + "\"(subject) => { subject() }\""
                                        + ")\n"
                                        + "(observation.state === \"completed\") && "
                                        + "(observation.value === 42) && "
                                        + "(observation.error === null)");

        assertSame(ProtosBooleanValue.TRUE, result);
    }

    @Test
    void inspectionMaySuspendOnRetainedActorRequestThroughProductionScheduler()
            throws Exception {
        ProtosObjectValue observation =
                inspectionObservation(
                        fixture(ACTOR_WORKERS),
                        ACTOR_REQUEST_CASE_SOURCE,
                        "(subject) => { subject.value() }");

        assertCompletedObservation(observation);
        ProtosIntegerValue value =
                assertInstanceOf(
                        ProtosIntegerValue.class,
                        observation.readLocalSlot("value").orElseThrow());
        assertEquals(String.valueOf("42"), com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerDecimalText(value));
    }

    @Test
    void inspectionMayBeginWithReturnedActorFutureContinuationSuspended()
            throws Exception {
        ProtosObjectValue observation =
                inspectionObservation(
                        fixture(ACTOR_WORKERS),
                        ACTOR_CHAIN_CASE_SOURCE,
                        "(subject) => { subject.value() }");

        assertCompletedObservation(observation);
        assertSame(
                ProtosBooleanValue.TRUE,
                observation.readLocalSlot("value").orElseThrow());
    }

    @Test
    void inspectionMaySuspendOnRetainedGroupRequestWithoutSelectingRoutingMember()
            throws Exception {
        ProtosObjectValue observation =
                inspectionObservation(
                        fixture(GROUP_WORKERS),
                        GROUP_REQUEST_CASE_SOURCE,
                        "(subject) => { subject.value() }");

        assertCompletedObservation(observation);
        ProtosIntegerValue value =
                assertInstanceOf(
                        ProtosIntegerValue.class,
                        observation.readLocalSlot("value").orElseThrow());
        assertTrue(
                com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerDecimalText(value).equals("1")
                        || com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerDecimalText(value).equals("2"),
                "Group request must resolve through exactly one eligible retained fixture member");
    }

    @Test
    void facilityIsBootstrapLocalAndNotPreludeGlobal() throws Exception {
        Fixture fixture = fixture();

        assertTrue(
                fixture.activation
                        .context()
                        .hasLocalSlot(
                                ProtosExactExecutionFacility.BOOTSTRAP_SLOT));
        assertFalse(
                fixture.prelude
                        .bindings()
                        .hasLocalSlot(
                                ProtosExactExecutionFacility.BOOTSTRAP_SLOT));
        assertTrue(
                fixture.activation
                        .context()
                        .hasLocalSlot(
                                ProtosExactExecutionFacility.INSPECTION_BOOTSTRAP_SLOT));
        assertFalse(
                fixture.prelude
                        .bindings()
                        .hasLocalSlot(
                                ProtosExactExecutionFacility.INSPECTION_BOOTSTRAP_SLOT));
    }

    private static Object evaluate(Fixture fixture, String source) {
        return ProtosTestExecutionSupport.evaluate(
                source,
                fixture.activation);
    }

    private static ProtosObjectValue inspectionObservation(
            Fixture fixture,
            Path sourcePath,
            String inspectorSource)
            throws Exception {
        return inspectionObservation(
                fixture,
                Files.readString(sourcePath, StandardCharsets.UTF_8),
                inspectorSource);
    }

    private static ProtosObjectValue inspectionObservation(
            Fixture fixture,
            String source,
            String inspectorSource)
            throws Exception {
        Object inspector =
                fixture.activation
                        .context()
                        .readLocalSlot(ProtosExactExecutionFacility.INSPECTION_BOOTSTRAP_SLOT)
                        .orElseThrow();
        return assertInstanceOf(
                ProtosObjectValue.class,
                ProtosInvocation.invoke(
                        inspector,
                        List.of(
                                new ProtosStringValue(source),
                                new ProtosStringValue(inspectorSource)),
                        fixture.activation));
    }

    private static void assertCompletedObservation(ProtosObjectValue observation) {
        ProtosStringValue state =
                assertInstanceOf(
                        ProtosStringValue.class,
                        observation.readLocalSlot("state").orElseThrow());
        assertEquals("completed", state.value());
        assertSame(
                ProtosNullValue.INSTANCE,
                observation.readLocalSlot("error").orElseThrow());
    }

    private static Fixture fixture() throws Exception {
        return fixture(new ProtosStandardLibraryModuleResolver(STANDARD_LIBRARY));
    }

    private static Fixture fixture(Path workersSource) throws Exception {
        return fixture(new WorkersResolver(workersSource));
    }

    private static Fixture fixture(ProtosModuleResolver resolver) throws Exception {
        ProtosPrelude prelude = new ProtosCoreBootstrap().bootstrap(CORE, resolver);
        ProtosActivation activation = prelude.newModuleActivation();
        ProtosExactExecutionFacility.install(activation);
        ProtosExactExecutionFacility.installInspection(activation);
        return new Fixture(prelude, activation);
    }

    private static final class WorkersResolver implements ProtosModuleResolver {
        private static final String SPECIFIER = "workers";
        private static final ProtosModuleKey KEY = new ProtosModuleKey("tool002-g1:workers");
        private final Path source;
        // D172: Actor fixtures import the canonical IP families from std:network.
        private final ProtosModuleResolver standardLibrary =
                new ProtosStandardLibraryModuleResolver(STANDARD_LIBRARY);

        private WorkersResolver(Path source) {
            this.source = source;
        }

        @Override
        public ProtosModuleKey resolve(
                String exactSpecifier,
                Optional<ProtosModuleKey> importingModule)
                throws Exception {
            if (exactSpecifier.startsWith("std:")) {
                return standardLibrary.resolve(exactSpecifier, importingModule);
            }
            if (!SPECIFIER.equals(exactSpecifier)) {
                throw new IOException("unknown TOOL002-G1 fixture module: " + exactSpecifier);
            }
            return KEY;
        }

        @Override
        public ProtosModuleSource loadSource(ProtosModuleKey key) throws Exception {
            if (key.canonicalId().startsWith("std:")) {
                return standardLibrary.loadSource(key);
            }
            if (!KEY.equals(key)) {
                throw new IOException(
                        "foreign TOOL002-G1 fixture module key: " + key.canonicalId());
            }
            return ProtosModuleSource.fromPath(key, source);
        }
    }

    private record Fixture(
            ProtosPrelude prelude,
            ProtosActivation activation) {}
}
