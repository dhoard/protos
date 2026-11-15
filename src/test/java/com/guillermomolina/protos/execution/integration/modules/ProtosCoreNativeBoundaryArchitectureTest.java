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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.guillermomolina.protos.runtime.ProtosActivation;
import com.guillermomolina.protos.runtime.ProtosClosureValue;
import com.guillermomolina.protos.runtime.ProtosObjectValue;
import com.guillermomolina.protos.runtime.ProtosPrelude;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * I018 closure guard.
 *
 * <p>The implementation may keep native standard behavior only at an explicitly audited
 * host/representation/concurrency/resource boundary. Ordinary derived Core behavior must remain
 * source-backed. This test intentionally makes expansion of the Java-native standard boundary an
 * explicit review event instead of letting it grow silently.
 */
final class ProtosCoreNativeBoundaryArchitectureTest {
    private static final Path CORE = Path.of("protos", "lib", "core");
    private static final Path JAVA_ROOT =
            Path.of("src", "main", "java", "com", "guillermomolina", "protos");

    private static final Map<String, Integer> EXPECTED_NATIVE_PROVIDERS =
            Map.ofEntries(
                    Map.entry("execution/protocols/values/ProtosStandardMapProtocol.java", 9),
                    Map.entry("execution/protocols/values/ProtosStandardHashSupport.java", 3),
                    Map.entry("execution/protocols/io/ProtosStandardFileProtocol.java", 10),
                    // D241-B: per-capability replace/remove/entries plus the private open bridge;
                    // the public open is source-backed.
                    Map.entry("execution/protocols/io/ProtosStandardFilesystemProtocol.java", 2),
                    Map.entry("execution/protocols/io/ProtosStandardPathProtocol.java", 3),
                    Map.entry("execution/protocols/io/ProtosStandardIpAddressProtocol.java", 1),
                    Map.entry("execution/protocols/io/ProtosStandardIpEndpointProtocol.java", 1),
                    // D241-B: public connectTcp plus the private listen bridge; listenTcp is source.
                    Map.entry("execution/protocols/io/ProtosStandardNetworkProtocol.java", 2),
                    Map.entry("execution/protocols/io/ProtosStandardTcpConnectionProtocol.java", 7),
                    Map.entry("execution/protocols/io/ProtosStandardTcpAcceptorProtocol.java", 1),
                    Map.entry("execution/protocols/io/ProtosStandardTcpListenerProtocol.java", 4),
                    Map.entry("execution/protocols/values/ProtosStandardArrayProtocol.java", 7),
                    // LIB024 std:math/linear_algebra Vector/Matrix protocols through one define helper.
                    Map.entry("execution/protocols/values/ProtosStandardLinearAlgebraProtocol.java", 1),
                    Map.entry("execution/protocols/io/ProtosStandardEnvironmentProtocol.java", 3),
                    Map.entry("execution/protocols/io/ProtosStandardEncodingProtocol.java", 2),
                    Map.entry("execution/protocols/io/ProtosStandardTextReaderProtocol.java", 2),
                    Map.entry("execution/protocols/io/ProtosStandardTextWriterProtocol.java", 2),
                    Map.entry("execution/protocols/io/ProtosStandardProcessStreamProtocol.java", 2),
                    Map.entry("execution/protocols/io/ProtosStandardProcessProtocol.java", 1),
                    Map.entry("execution/protocols/io/ProtosStandardBytesProtocol.java", 7),
                    Map.entry("execution/protocols/io/ProtosStandardByteIoProtocol.java", 12),
                    Map.entry("execution/protocols/values/ProtosStandardObjectProtocol.java", 17),
                    Map.entry("execution/protocols/actors/ProtosStandardActorProtocol.java", 9),
                    Map.entry("execution/protocols/values/ProtosStandardIdentityMapProtocol.java", 8),
                    Map.entry("execution/protocols/values/ProtosStandardStringProtocol.java", 5),
                    Map.entry("execution/protocols/io/ProtosStandardBufferedByteIoProtocol.java", 4),
                    Map.entry("execution/protocols/actors/ProtosStandardErrorProtocol.java", 2),
                    // I167: private import bridge primitives (resolve, lookup, begin, execute)
                    // behind the source-backed public import.call; no public native import.
                    Map.entry("execution/protocols/actors/ProtosStandardImportProtocol.java", 4),
                    Map.entry("execution/protocols/numeric/ProtosStandardIntegerProtocol.java", 3),
                    Map.entry("execution/protocols/actors/ProtosStandardFutureProtocol.java", 2),
                    Map.entry("execution/protocols/numeric/ProtosStandardFloatProtocol.java", 2),
                    Map.entry("execution/protocols/numeric/ProtosStandardFractionProtocol.java", 5),
                    Map.entry("execution/protocols/numeric/ProtosStandardComplexProtocol.java", 1),
                    Map.entry("execution/protocols/values/ProtosStandardBooleanProtocol.java", 1),
                    Map.entry("execution/protocols/numeric/ProtosStandardNumberEqualityProtocol.java", 1),
                    Map.entry("execution/runtime/concurrency/ProtosParallelRuntime.java", 1),
                    Map.entry("execution/protocols/numeric/ProtosStandardNumberOrderingProtocol.java", 1),
                    Map.entry("execution/protocols/numeric/ProtosStandardNumericConversionProtocol.java", 1),
                    // D203/I105: private Object.protos argument-count Error helper.
                    Map.entry("execution/host/embedding/ProtosCoreBootstrap.java", 1));

    private static final Map<String, Integer> EXPECTED_NON_CORE_NATIVE_PROVIDERS =
            Map.ofEntries(
                    Map.entry("execution/facilities/ProtosCommandLineArrayConstructionFacility.java", 3),
                    Map.entry("execution/host/embedding/ProtosExactExecutionFacility.java", 2),
                    // LIB021-A std:interop explicit foreign operations.
                    Map.entry("execution/foreign/api/ProtosForeignInteropFacility.java", 4),
                    // I082-D2 adds the projected pull each to call/at/atPut.
                    Map.entry("execution/foreign/api/ProtosForeignProjectedOperations.java", 4),
                    Map.entry("execution/facilities/ProtosLoggingFacility.java", 3),
                    // I090-C std:math/math private sqrt facility.
                    Map.entry("execution/facilities/ProtosMathFacility.java", 1),
                    // LIB022-A std:runtime private runtime-metadata facility.
                    Map.entry("execution/facilities/ProtosRuntimeMetadataFacility.java", 1),
                    // LIB022-B std:inspect private inspection facility.
                    Map.entry("execution/facilities/ProtosInspectFacility.java", 4),
                    // LIB025 std:http/server private asynchronous bridge.
                    Map.entry("execution/facilities/ProtosHttpAsyncFacility.java", 1),
                    // I130-B std:tls/client private backend bridge.
                    Map.entry("execution/facilities/ProtosTlsBackendFacility.java", 1),
                    // I132-A std:network/resolvers private host Resolver bridge (D223-A1).
                    Map.entry("execution/facilities/ProtosResolverHostFacility.java", 1),
                    // D243-C / I157: private host Entropy bridge, not a Core selector.
                    Map.entry("execution/facilities/ProtosEntropyHostFacility.java", 1),
                    Map.entry("execution/facilities/ProtosClockHostFacility.java", 1),
                    // D244-C / I159: private HMAC-SHA256 JCA primitive,
                    // not a native Core selector or ambient capability.
                    Map.entry("execution/facilities/ProtosHmacSha256Facility.java", 1),
                    // I160: private Argon2id admission facility, not a Core selector.
                    Map.entry("execution/facilities/ProtosPasswordArgon2Facility.java", 1),
                    Map.entry("execution/facilities/ProtosRegexUnicodeFacility.java", 3),
                    Map.entry("execution/facilities/ProtosRegexSemanticTransferFamily.java", 3),
                    Map.entry("execution/facilities/ProtosSealedFamilyFacility.java", 3),
                    Map.entry("execution/tools/test/catalog/ProtosTestToolCatalogAcquisitionFacility.java", 1),
                    Map.entry("execution/tools/test/catalog/ProtosTestToolFileSelectionFacility.java", 2),
                    Map.entry("execution/tools/test/reporting/ProtosTestToolStalledCaseDiagnosticFacility.java", 1));

    @Test
    void clockHasOnlyOneSourceBackedPublicOperationAndNoPublicGrantFactory() throws IOException {
        ProtosPrelude prelude = new ProtosCoreBootstrap().bootstrap(CORE);
        ProtosObjectValue clock = prelude.clockPrototypeForRuntime();
        assertSourceBacked(clock, "epochNanoseconds");
        assertNativeSelectors("Clock", clock, Set.of());
        assertEquals(Set.of("epochNanoseconds"), clock.localSlotsSnapshot().keySet());
        for (String name : Set.of("Clock", "clock", "_coreClockBridge")) {
            assertFalse(prelude.bindings().hasLocalSlot(name), name);
        }
        assertFalse(prelude.isStandardModuleMemberForRuntime(clock));
    }

    @Test
    void javaNativeClosureProvidersMatchTheAuditedBoundaryExactly() throws IOException {
        Map<String, Integer> actualCore = new TreeMap<>();
        Map<String, Integer> actualNonCore = new TreeMap<>();
        try (Stream<Path> files = Files.walk(JAVA_ROOT)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                String source = Files.readString(file);
                int count = occurrences(source, "ProtosClosureValue.nativeClosure(");
                if (count > 0) {
                    String relative =
                            JAVA_ROOT.relativize(file).toString().replace('\\', '/');
                    if (relative.startsWith("cli/")
                            || relative.equals(
                                    "execution/host/embedding/ProtosExactExecutionFacility.java")
                            // I082-D D188 host-interop projection, not derived Core behavior.
                            || relative.equals(
                                    "execution/foreign/api/ProtosForeignProjectedOperations.java")
                            // LIB021-A explicit host-interop operations, not Core behavior.
                            || relative.equals(
                                    "execution/foreign/api/ProtosForeignInteropFacility.java")
                            || relative.equals(
                                    "execution/tools/test/catalog/ProtosTestToolCatalogAcquisitionFacility.java")
                            || relative.equals(
                                    "execution/tools/test/catalog/ProtosTestToolFileSelectionFacility.java")
                            || relative.equals(
                                    "execution/tools/test/reporting/ProtosTestToolStalledCaseDiagnosticFacility.java")
                            || relative.equals(
                                    "execution/facilities/ProtosCommandLineArrayConstructionFacility.java")
                            || relative.equals(
                                    "execution/facilities/ProtosRegexUnicodeFacility.java")
                            || relative.equals(
                                    "execution/facilities/ProtosRegexSemanticTransferFamily.java")
                            || relative.equals(
                                    "execution/facilities/ProtosLoggingFacility.java")
                            || relative.equals(
                                    "execution/facilities/ProtosMathFacility.java")
                            || relative.equals(
                                    "execution/facilities/ProtosRuntimeMetadataFacility.java")
                            // LIB022-B std:inspect private facility, not Core behavior.
                            || relative.equals(
                                    "execution/facilities/ProtosInspectFacility.java")
                            // LIB025 std:http/server private facility, not Core behavior.
                            || relative.equals(
                                    "execution/facilities/ProtosHttpAsyncFacility.java")
                            // I130-B std:tls/client private facility, not Core behavior.
                            || relative.equals(
                                    "execution/facilities/ProtosTlsBackendFacility.java")
                            // I132-A std:network/resolvers private host bridge, not Core
                            // behavior: it confers no authority and only forwards to the
                            // independently granted Resolver capability.
                            || relative.equals(
                                    "execution/facilities/ProtosResolverHostFacility.java")
                            // D243-C / I157: private host Entropy bridge.
                            || relative.equals(
                                    "execution/facilities/ProtosEntropyHostFacility.java")
                            || relative.equals("execution/facilities/ProtosClockHostFacility.java")
                            // D244-C / I159: private HMAC-SHA256 backend.
                            || relative.equals(
                                    "execution/facilities/ProtosHmacSha256Facility.java")
                            // I160: private Password facility, not a Core selector.
                            || relative.equals(
                                    "execution/facilities/ProtosPasswordArgon2Facility.java")
                            || relative.equals(
                                    "execution/facilities/ProtosSealedFamilyFacility.java")) {
                        actualNonCore.put(relative, count);
                    } else {
                        actualCore.put(relative, count);
                    }
                }
                assertFalse(
                        source.contains(
                                "import static com.guillermomolina.protos.runtime.ProtosClosureValue.nativeClosure"),
                        () -> "static nativeClosure import would evade the provider guard: " + file);
            }
        }

        assertEquals(EXPECTED_NATIVE_PROVIDERS, actualCore);
        assertEquals(39, actualCore.size());
        assertEquals(150, actualCore.values().stream().mapToInt(Integer::intValue).sum());
        assertEquals(EXPECTED_NON_CORE_NATIVE_PROVIDERS, actualNonCore);

    }

    @Test
    void staticCoreSurfaceKeepsOnlyTheAuditedNativeSelectors() throws Exception {
        ProtosPrelude prelude = new ProtosCoreBootstrap().bootstrap(CORE);

        assertTrue(
                prelude.bindings().readLocalSlot("Filesystem").isEmpty(),
                "Filesystem authority must remain host-provisioned, not a Core-prelude binding");
        // D241-B: the private source method and both bridges are never public bindings.
        for (String privateName :
                List.of(
                        "_coreFilesystemOpenMethod",
                        "_coreFilesystemOpenBridge",
                        "_coreNetworkListenBridge",
                        "_coreNetworkListenMissing")) {
            assertTrue(
                    prelude.bindings().readLocalSlot(privateName).isEmpty(),
                    privateName + " must not be a Prelude binding");
        }
        assertTrue(
                prelude.bindings().readLocalSlot("Environment").isEmpty(),
                "Environment is outside the required Core prelude");

        assertNativeSelectors(
                "Object",
                ProtosObjectValue.rootObject(),
                Set.of(
                        "call",
                        "construct",
                        "hasSlot",
                        "slotValue",
                        "slotNames",
                        "removeSlot",
                        "close",
                        "freeze",
                        "without",
                        "alias",
                        "not",
                        "ifTrue",
                        "ifFalse",
                        "ifTrueIfFalse",
                        "and",
                        "or",
                        "hash",
                        "future",
                        "parallel",
                        "parent",
                        "ensure",
                        "whileTrue",
                        "whileFalse",
                        "untilTrue",
                        "untilFalse",
                        "caseOf"));
        assertTrue(
                ProtosObjectValue.rootObject().readLocalSlot("identityHash").isEmpty(),
                "standard Object must not publish identityHash");
        assertTrue(
                ProtosObjectValue.rootObject().readLocalSlot("while").isEmpty(),
                "standard Object must not publish while");
        assertTrue(
                ProtosObjectValue.rootObject().readLocalSlot("if").isEmpty(),
                "standard Object must not publish if");
        assertTrue(
                ProtosObjectValue.rootObject().readLocalSlot("else").isEmpty(),
                "standard Object must not publish else");
        assertSourceBacked(ProtosObjectValue.rootObject(), "init");
        assertSourceBacked(ProtosObjectValue.rootObject(), "==");
        assertSourceBacked(ProtosObjectValue.rootObject(), "match");
        assertSourceBacked(ProtosObjectValue.rootObject(), "ifNull");
        assertSourceBacked(ProtosObjectValue.rootObject(), "ifNotNull");

        assertNativeSelectors(
                "Number",
                prelude.numberPrototype(),
                Set.of("==", "<", "<=", ">", ">=", "hash"));
        // D198: the component selectors are source-backed.
        assertSourceBacked(prelude.numberPrototype(), "real");
        assertSourceBacked(prelude.numberPrototype(), "imaginary");

        assertNativeSelectors(
                "Integer",
                prelude.integerPrototype(),
                Set.of(
                        "construct",
                        "+",
                        "-",
                        "*",
                        "/",
                        "div",
                        "mod",
                        "recognizes",
                        "bitAnd",
                        "bitOr",
                        "bitXor",
                        "bitNot",
                        "shiftLeft",
                        "shiftRight"));
        assertSourceBacked(prelude.integerPrototype(), "negated");
        assertSourceBacked(prelude.integerPrototype(), "%");
        // D197-C: the concrete Integer families own only their trusted recognizers.
        assertNativeSelectors(
                "SmallInteger", prelude.smallIntegerPrototype(), Set.of("recognizes"));
        assertNativeSelectors(
                "BigInteger", prelude.bigIntegerPrototype(), Set.of("recognizes"));

        // D197-C: Fraction arithmetic is source-backed; only the exact recognizer is native.
        assertNativeSelectors(
                "Fraction", prelude.fractionPrototype(), Set.of("recognizes"));
        for (String selector :
                Set.of(
                        "negated",
                        "+",
                        "-",
                        "*",
                        "/")) {
            assertSourceBacked(prelude.fractionPrototype(), selector);
        }

        assertNativeSelectors(
                "Float",
                prelude.floatPrototype(),
                Set.of("construct", "+", "-", "*", "/", "recognizes"));
        assertSourceBacked(prelude.floatPrototype(), "negated");

        // D198 (I090): Complex negation and arithmetic are source-backed; only the exact
        // recognizer and the non-dispatching factory are native.
        assertNativeSelectors(
                "Complex", prelude.complexPrototype(), Set.of("recognizes", "construct"));
        for (String selector : Set.of("negated", "+", "-", "*", "/")) {
            assertSourceBacked(prelude.complexPrototype(), selector);
        }

        for (String removed :
                Set.of(
                        "UInt8",
                        "Int8",
                        "UInt16",
                        "Int16",
                        "UInt32",
                        "Int32",
                        "UInt64",
                        "Int64")) {
            assertTrue(
                    prelude.bindings().readLocalSlot(removed).isEmpty(),
                    () -> removed + " must not remain a Core-prelude binding");
        }

        assertNativeSelectors("Context", prelude.contextPrototype(), Set.of());
        assertNativeSelectors("Error", prelude.errorPrototype(), Set.of("signal", "handle"));
        assertNativeSelectors(
                "Array",
                prelude.arrayPrototype(),
                Set.of(
                        "construct",
                        "at",
                        "atPut",
                        "size",
                        "each",
                        "match",
                        "recognizes"));
        assertNativeSelectors(
                "String",
                prelude.stringPrototype(),
                Set.of("size", "at", "+", "concat", "recognizes", "hash"));
        assertNativeSelectors(
                "Encoding",
                prelude.encodingPrototype(),
                Set.of("encode", "decode"));
        assertNativeSelectors(
                "Map",
                prelude.mapPrototype(),
                Set.of("construct", "at", "atIfAbsent", "atPut", "containsKey", "remove", "size", "each", "match"));
        assertNativeSelectors(
                "IdentityMap",
                prelude.identityMapPrototype(),
                Set.of("construct", "at", "atIfAbsent", "atPut", "containsKey", "remove", "size", "each"));
        // I094-A1: Path behavior is source-backed; only the private kernel is native.
        assertNativeSelectors("Path", prelude.pathPrototype(), Set.of());
        assertEquals(
                Set.of("relative", "child", "==", "hash"),
                prelude.pathPrototype().localSlotsSnapshot().keySet(),
                "unexpected standard Path prototype surface");
        assertSourceBacked(prelude.pathPrototype(), "relative");
        assertSourceBacked(prelude.pathPrototype(), "child");
        assertSourceBacked(prelude.pathPrototype(), "==");
        assertSourceBacked(prelude.pathPrototype(), "hash");
        ProtosObjectValue ipAddressPrototype = prelude.ipAddressPrototypeForRuntime();
        assertTrue(ipAddressPrototype.isFrozen(), "standard IpAddress prototype must be frozen");
        assertTrue(
                ipAddressPrototype.parent().orElse(null) == ProtosObjectValue.rootObject(),
                "standard IpAddress prototype must delegate directly to Object");
        assertNativeSelectors("IpAddress", ipAddressPrototype, Set.of("recognizes"));
        assertEquals(
                Set.of("construct", "==", "hash", "recognizes"),
                ipAddressPrototype.localSlotsSnapshot().keySet(),
                "unexpected standard IpAddress prototype surface");
        assertSourceBacked(ipAddressPrototype, "construct");
        assertSourceBacked(ipAddressPrototype, "==");
        assertSourceBacked(ipAddressPrototype, "hash");
        ProtosObjectValue ipEndpointPrototype = prelude.ipEndpointPrototypeForRuntime();
        assertTrue(ipEndpointPrototype.isFrozen(), "standard IpEndpoint prototype must be frozen");
        assertTrue(
                ipEndpointPrototype.parent().orElse(null) == ProtosObjectValue.rootObject(),
                "standard IpEndpoint prototype must delegate directly to Object");
        assertNativeSelectors("IpEndpoint", ipEndpointPrototype, Set.of("recognizes"));
        assertEquals(
                Set.of("construct", "==", "hash", "recognizes"),
                ipEndpointPrototype.localSlotsSnapshot().keySet(),
                "unexpected standard IpEndpoint prototype surface");
        assertSourceBacked(ipEndpointPrototype, "construct");
        assertSourceBacked(ipEndpointPrototype, "==");
        assertSourceBacked(ipEndpointPrototype, "hash");
        // D241-B: connectTcp is the only published native Network selector; listenTcp is a
        // source Closure that reaches a private bridge, which is not a public native selector.
        assertNativeSelectors("Network", prelude.networkPrototype(), Set.of("connectTcp"));
        assertEquals(
                Set.of("connectTcp", "listenTcp"),
                prelude.networkPrototype().localSlotsSnapshot().keySet(),
                "unexpected standard Network prototype surface");
        assertTrue(
                prelude.networkPrototype().parent().orElse(null) == ProtosObjectValue.rootObject(),
                "standard Network prototype must delegate directly to Object");
        assertSourceBacked(prelude.networkPrototype(), "listenTcp");
        ProtosObjectValue filesystem =
                ProtosStandardFilesystemProtocol.createCapability(
                        prelude.bytesPrototypeForRuntime(),
                        prelude.newModuleActivation(),
                        (path, options, completion) -> {
                            completion.failed();
                            return () -> {};
                        });
        assertEquals(
                Set.of("open", "replace", "remove", "entries"),
                filesystem.localSlotsSnapshot().keySet(),
                "unexpected host-provisioned Filesystem surface");
        assertTrue(
                filesystem.parent().orElse(null) == ProtosObjectValue.rootObject(),
                "a Filesystem capability must delegate directly to Object");
        assertNativeSelectors("Filesystem", filesystem, Set.of("replace", "remove", "entries"));
        assertSourceBacked(filesystem, "open");
        assertNativeSelectors(
                "TcpListener",
                prelude.tcpListenerPrototypeForRuntime(),
                Set.of("accept", "acceptCapability", "localPort", "close"));
        ProtosObjectValue tcpAcceptorPrototype = prelude.tcpAcceptorPrototypeForRuntime();
        assertTrue(tcpAcceptorPrototype.isFrozen(), "standard TcpAcceptor prototype must be frozen");
        assertTrue(
                tcpAcceptorPrototype.parent().orElse(null) == ProtosObjectValue.rootObject(),
                "standard TcpAcceptor prototype must delegate directly to Object");
        assertNativeSelectors("TcpAcceptor", tcpAcceptorPrototype, Set.of("accept"));
        assertNativeSelectors(
                "Future",
                prelude.futurePrototype(),
                Set.of("value", "cancel", "then", "all"));
        assertNativeSelectors(
                "Actor",
                ordinaryBinding(prelude, "Actor"),
                Set.of("spawn", "current", "group"));
        assertNativeSelectors(
                "Process",
                prelude.processPrototype(),
                Set.of(
                        "args",
                        "environment",
                        "stdin",
                        "stdinEncoding",
                        "stdout",
                        "stdoutEncoding",
                        "stderr",
                        "stderrEncoding"));
        assertNativeSelectors(
                "TextReader",
                ordinaryBinding(prelude, "TextReader"),
                Set.of("construct", "owning"));
        assertNativeSelectors(
                "TextWriter",
                ordinaryBinding(prelude, "TextWriter"),
                Set.of("construct", "owning"));
        assertNativeSelectors(
                "BufferedReader",
                ProtosStandardModuleMemberTestSupport.bufferedReaderFactory(prelude),
                Set.of("construct", "owning"));
        assertNativeSelectors(
                "BufferedWriter",
                ProtosStandardModuleMemberTestSupport.bufferedWriterFactory(prelude),
                Set.of("construct", "owning"));
        // I167: the public import is source-backed; its private bridge and tokens are never
        // public members or Prelude bindings.
        ProtosObjectValue importFacility = ordinaryBinding(prelude, "import");
        assertNativeSelectors("import", importFacility, Set.of());
        assertEquals(
                Set.of("call"),
                importFacility.localSlotsSnapshot().keySet(),
                "unexpected standard import surface");
        assertTrue(importFacility.isFrozen(), "standard import facility must be frozen");
        assertSourceBacked(importFacility, "call");
        for (String privateName : List.of("_coreImportBridge", "resolve", "lookup", "begin", "execute")) {
            assertFalse(
                    importFacility.hasLocalSlot(privateName),
                    privateName + " must not be a public import member");
        }
        assertTrue(
                prelude.bindings().readLocalSlot("_coreImportBridge").isEmpty(),
                "_coreImportBridge must not be a Prelude binding");
    }

    @Test
    void internalAndHelperBackedStandardSurfacesStayExplicit() throws Exception {
        ProtosObjectValue environmentPrototype =
                ProtosStandardEnvironmentProtocol.createPrototype();
        assertNativeSelectors(
                "Environment",
                environmentPrototype,
                Set.of("get", "contains", "each"));

        ProtosObjectValue standardInputPrototype =
                ProtosStandardProcessStreamProtocol.createReadablePrototype();
        ProtosObjectValue standardOutputPrototype =
                ProtosStandardProcessStreamProtocol.createWritablePrototype();
        assertNativeSelectors(
                "ProcessStandardInput",
                standardInputPrototype,
                Set.of("read"));
        assertNativeSelectors(
                "ProcessStandardOutput",
                standardOutputPrototype,
                Set.of("write"));

        ProtosPrelude helperPrelude = new ProtosCoreBootstrap().bootstrap(CORE);
        ProtosActivation textActivation = helperPrelude.newModuleActivation();
        ProtosObjectValue textSource =
                new ProtosObjectValue(ProtosObjectValue.rootObject());
        textSource.createLocalSlot(
                "read",
                ProtosClosureValue.nativeClosure(
                        (activation, arguments) -> activation.receiver()));
        Object utf8 =
                helperPrelude.encodingPrototype().readLocalSlot("UTF8").orElseThrow();
        ProtosObjectValue textReader =
                (ProtosObjectValue)
                        ProtosInvocation.invokeMessage(
                                ordinaryBinding(helperPrelude, "TextReader"),
                                "call",
                                java.util.List.of(textSource, utf8),
                                textActivation);
        assertNativeSelectors(
                "TextReaderWrapper",
                textReader,
                Set.of("readText", "readLine", "close"));

        ProtosObjectValue textTarget =
                new ProtosObjectValue(ProtosObjectValue.rootObject());
        textTarget.createLocalSlot(
                "write",
                ProtosClosureValue.nativeClosure(
                        (activation, arguments) -> activation.receiver()));
        ProtosObjectValue textWriter =
                (ProtosObjectValue)
                        ProtosInvocation.invokeMessage(
                                ordinaryBinding(helperPrelude, "TextWriter"),
                                "call",
                                java.util.List.of(textTarget, utf8),
                                textActivation);
        assertNativeSelectors(
                "TextWriterWrapper",
                textWriter,
                Set.of("writeText", "writeLine", "flush", "close"));

        ProtosObjectValue bytesPrototype =
                new ProtosObjectValue(ProtosObjectValue.rootObject());
        ProtosStandardBytesProtocol.install(bytesPrototype);
        assertNativeSelectors(
                "Bytes",
                bytesPrototype,
                Set.of("construct", "size", "at", "atPut", "each", "add", "removeAt"));

        ProtosObjectValue actorRefPrototype =
                new ProtosObjectValue(ProtosObjectValue.rootObject());
        ProtosObjectValue groupRefPrototype =
                new ProtosObjectValue(ProtosObjectValue.rootObject());
        ProtosObjectValue sendOperationPrototype =
                new ProtosObjectValue(ProtosObjectValue.rootObject());
        ProtosObjectValue actorObject =
                new ProtosObjectValue(ProtosObjectValue.rootObject());

        ProtosStandardActorProtocol actorProtocol =
                new ProtosStandardActorProtocol(
                        new ProtosModuleRuntime(ProtosModuleResolver.rejecting()),
                        Runnable::run,
                        actorRefPrototype,
                        sendOperationPrototype);
        actorProtocol.installGroupRefPrototype(groupRefPrototype);
        actorProtocol.installActorObject(actorObject);

        assertNativeSelectors(
                "ActorRef", actorRefPrototype, Set.of("send", "request", "stop", "termination"));
        assertNativeSelectors(
                "GroupRef", groupRefPrototype, Set.of("send", "request"));
        assertNativeSelectors(
                "SendOperation", sendOperationPrototype, Set.of("cancel", "retry"));
        assertNativeSelectors("Actor", actorObject, Set.of("spawn", "current", "group"));

        String parallel =
                Files.readString(
                        JAVA_ROOT.resolve("execution").resolve("runtime/concurrency/ProtosParallelRuntime.java"));
        assertEquals(0, occurrences(parallel, "slot(p,"));
        assertEquals(0, occurrences(parallel, "slot(r,"));

        String future =
                Files.readString(
                        JAVA_ROOT.resolve("execution").resolve("protocols/actors/ProtosStandardFutureProtocol.java"));
        int ordinaryFutureNativeSlots =
                occurrences(future, "slot(futurePrototype,");
        int suspensionCapableFutureNativeSlots =
                occurrences(
                        future,
                        "suspensionSlot(\n                futurePrototype,");
        assertEquals(
                3,
                ordinaryFutureNativeSlots,
                "Future keeps three ordinary native helper-backed surfaces");
        assertEquals(
                1,
                suspensionCapableFutureNativeSlots,
                "Future.value is the single PLAT019 suspension-capable native surface");
        assertEquals(
                4,
                ordinaryFutureNativeSlots + suspensionCapableFutureNativeSlots,
                "Future native/helper-backed standard surface count must not expand silently");

        String bootstrap =
                Files.readString(
                        JAVA_ROOT.resolve("execution").resolve("host/embedding/ProtosCoreBootstrap.java"));
        assertEquals(
                2,
                occurrences(
                        bootstrap,
                        "new ProtosObjectValue(ProtosObjectValue.rootObject())"),
                "Core bootstrap may allocate only its two host construction contexts directly");
        assertFalse(bootstrap.contains("preludeBindings.createLocalSlot("));
    }

    private static ProtosObjectValue ordinaryBinding(ProtosPrelude prelude, String name) {
        Object value = prelude.bindings().readLocalSlot(name).orElseThrow();
        assertTrue(value instanceof ProtosObjectValue, "standard binding is not ordinary: " + name);
        return (ProtosObjectValue) value;
    }

    private static void assertNativeSelectors(
            String name, ProtosObjectValue object, Set<String> expected) {
        Set<String> actual =
                object.localSlotsSnapshot().entrySet().stream()
                        .filter(
                                entry ->
                                        entry.getValue() instanceof ProtosClosureValue closure
                                                && closure.nativeBody().isPresent())
                        .map(Map.Entry::getKey)
                        .collect(java.util.stream.Collectors.toSet());
        assertEquals(expected, actual, "unexpected native standard surface for " + name);
    }

    private static void assertSourceBacked(ProtosObjectValue object, String selector) {
        Object value = object.readLocalSlot(selector).orElseThrow();
        assertTrue(value instanceof ProtosClosureValue, selector + " is not a Closure");
        ProtosClosureValue closure = (ProtosClosureValue) value;
        assertNotNull(closure.definition(), selector + " lost source definition provenance");
        assertTrue(
                closure.executionPlan().isPresent(),
                selector + " lost its source execution plan");
        assertTrue(
                closure.nativeBody().isEmpty(),
                selector + " regressed to native-only behavior");
    }

    private static int occurrences(String source, String needle) {
        int count = 0;
        int index = 0;
        while ((index = source.indexOf(needle, index)) >= 0) {
            count++;
            index += needle.length();
        }
        return count;
    }
}
