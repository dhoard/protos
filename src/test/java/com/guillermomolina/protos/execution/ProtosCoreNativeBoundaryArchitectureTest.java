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
                    Map.entry("execution/ProtosStandardMapProtocol.java", 9),
                    Map.entry("execution/ProtosStandardHashSupport.java", 3),
                    Map.entry("execution/ProtosStandardFileProtocol.java", 10),
                    Map.entry("execution/ProtosStandardFilesystemProtocol.java", 1),
                    Map.entry("execution/ProtosStandardPathProtocol.java", 3),
                    Map.entry("execution/ProtosStandardIpAddressProtocol.java", 1),
                    Map.entry("execution/ProtosStandardIpEndpointProtocol.java", 1),
                    Map.entry("execution/ProtosStandardNetworkProtocol.java", 2),
                    Map.entry("execution/ProtosStandardTcpConnectionProtocol.java", 7),
                    Map.entry("execution/ProtosStandardTcpListenerProtocol.java", 3),
                    Map.entry("execution/ProtosStandardArrayProtocol.java", 7),
                    Map.entry("execution/ProtosStandardEnvironmentProtocol.java", 3),
                    Map.entry("execution/ProtosStandardEncodingProtocol.java", 2),
                    Map.entry("execution/ProtosStandardTextReaderProtocol.java", 2),
                    Map.entry("execution/ProtosStandardTextWriterProtocol.java", 2),
                    Map.entry("execution/ProtosStandardProcessStreamProtocol.java", 2),
                    Map.entry("execution/ProtosStandardProcessProtocol.java", 1),
                    Map.entry("execution/ProtosStandardBytesProtocol.java", 7),
                    Map.entry("execution/ProtosStandardByteIoProtocol.java", 12),
                    Map.entry("execution/ProtosStandardObjectProtocol.java", 13),
                    Map.entry("execution/ProtosStandardActorProtocol.java", 9),
                    Map.entry("execution/ProtosStandardIdentityMapProtocol.java", 8),
                    Map.entry("execution/ProtosStandardStringProtocol.java", 5),
                    Map.entry("execution/ProtosStandardBufferedByteIoProtocol.java", 4),
                    Map.entry("execution/ProtosStandardErrorProtocol.java", 2),
                    Map.entry("execution/ProtosStandardImportProtocol.java", 1),
                    Map.entry("execution/ProtosStandardIntegerProtocol.java", 2),
                    Map.entry("execution/ProtosStandardFutureProtocol.java", 2),
                    Map.entry("execution/ProtosStandardFloatProtocol.java", 2),
                    Map.entry("execution/ProtosStandardFractionProtocol.java", 5),
                    Map.entry("execution/ProtosStandardComplexProtocol.java", 1),
                    Map.entry("execution/ProtosStandardBooleanProtocol.java", 1),
                    Map.entry("execution/ProtosStandardNumberEqualityProtocol.java", 1),
                    Map.entry("execution/ProtosParallelRuntime.java", 1),
                    Map.entry("execution/ProtosStandardNumberOrderingProtocol.java", 1),
                    Map.entry("execution/ProtosStandardNumericConversionProtocol.java", 1));

    private static final Map<String, Integer> EXPECTED_NON_CORE_NATIVE_PROVIDERS =
            Map.ofEntries(
                    Map.entry("execution/ProtosCommandLineArrayConstructionFacility.java", 3),
                    Map.entry("execution/ProtosExactExecutionFacility.java", 2),
                    // LIB021-A std:interop explicit foreign operations.
                    Map.entry("execution/ProtosForeignInteropFacility.java", 4),
                    // I082-D2 adds the projected pull each to call/at/atPut.
                    Map.entry("execution/ProtosForeignProjectedOperations.java", 4),
                    Map.entry("execution/ProtosLoggingFacility.java", 3),
                    Map.entry("execution/ProtosRegexUnicodeFacility.java", 3),
                    Map.entry("execution/ProtosRegexSemanticTransferFamily.java", 3),
                    Map.entry("execution/ProtosSealedFamilyFacility.java", 3),
                    Map.entry("execution/ProtosTestToolCatalogAcquisitionFacility.java", 1),
                    Map.entry("execution/ProtosTestToolFileSelectionFacility.java", 2),
                    Map.entry("execution/ProtosTestToolStalledCaseDiagnosticFacility.java", 1));

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
                                    "execution/ProtosExactExecutionFacility.java")
                            // I082-D D188 host-interop projection, not derived Core behavior.
                            || relative.equals(
                                    "execution/ProtosForeignProjectedOperations.java")
                            // LIB021-A explicit host-interop operations, not Core behavior.
                            || relative.equals(
                                    "execution/ProtosForeignInteropFacility.java")
                            || relative.equals(
                                    "execution/ProtosTestToolCatalogAcquisitionFacility.java")
                            || relative.equals(
                                    "execution/ProtosTestToolFileSelectionFacility.java")
                            || relative.equals(
                                    "execution/ProtosTestToolStalledCaseDiagnosticFacility.java")
                            || relative.equals(
                                    "execution/ProtosCommandLineArrayConstructionFacility.java")
                            || relative.equals(
                                    "execution/ProtosRegexUnicodeFacility.java")
                            || relative.equals(
                                    "execution/ProtosRegexSemanticTransferFamily.java")
                            || relative.equals(
                                    "execution/ProtosLoggingFacility.java")
                            || relative.equals(
                                    "execution/ProtosSealedFamilyFacility.java")) {
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
        assertEquals(36, actualCore.size());
        assertEquals(137, actualCore.values().stream().mapToInt(Integer::intValue).sum());
        assertEquals(EXPECTED_NON_CORE_NATIVE_PROVIDERS, actualNonCore);

    }

    @Test
    void staticCoreSurfaceKeepsOnlyTheAuditedNativeSelectors() throws Exception {
        ProtosPrelude prelude = new ProtosCoreBootstrap().bootstrap(CORE);

        assertTrue(
                prelude.bindings().readLocalSlot("Filesystem").isEmpty(),
                "Filesystem authority must remain host-provisioned, not a Core-prelude binding");
        assertTrue(
                prelude.bindings().readLocalSlot("Environment").isEmpty(),
                "Environment is outside the required Core prelude");

        assertNativeSelectors(
                "Object",
                ProtosObjectValue.rootObject(),
                Set.of(
                        "call",
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
                        "if",
                        "else",
                        "and",
                        "or",
                        "hash",
                        "future",
                        "parallel",
                        "parent",
                        "ensure",
                        "whileTrue",
                        "caseOf"));
        assertTrue(
                ProtosObjectValue.rootObject().readLocalSlot("identityHash").isEmpty(),
                "standard Object must not publish identityHash");
        assertTrue(
                ProtosObjectValue.rootObject().readLocalSlot("while").isEmpty(),
                "standard Object must not publish the removed while loop selector");
        assertTrue(
                ProtosObjectValue.rootObject().readLocalSlot("whileFalse").isEmpty(),
                "standard Object must not publish an inverse whileFalse loop selector");
        assertSourceBacked(ProtosObjectValue.rootObject(), "init");
        assertSourceBacked(ProtosObjectValue.rootObject(), "==");
        assertSourceBacked(ProtosObjectValue.rootObject(), "match");
        assertSourceBacked(ProtosObjectValue.rootObject(), "ifNull");
        assertSourceBacked(ProtosObjectValue.rootObject(), "ifNotNull");

        assertNativeSelectors(
                "Number",
                prelude.numberPrototype(),
                Set.of("==", "<", "<=", ">", ">=", "hash"));

        assertNativeSelectors(
                "Integer",
                prelude.integerPrototype(),
                Set.of("call", "+", "-", "*", "/", "div", "mod", "recognizes"));
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
                Set.of("call", "+", "-", "*", "/", "recognizes"));
        assertSourceBacked(prelude.floatPrototype(), "negated");

        // D197-C (I090-C1): Complex owns only its trusted recognizer.
        assertNativeSelectors(
                "Complex", prelude.complexPrototype(), Set.of("recognizes"));

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
                        "call",
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
                Set.of("call", "at", "atIfAbsent", "atPut", "containsKey", "remove", "size", "each", "match"));
        assertNativeSelectors(
                "IdentityMap",
                prelude.identityMapPrototype(),
                Set.of("call", "at", "atIfAbsent", "atPut", "containsKey", "remove", "size", "each"));
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
                Set.of("call", "==", "hash", "recognizes"),
                ipAddressPrototype.localSlotsSnapshot().keySet(),
                "unexpected standard IpAddress prototype surface");
        assertSourceBacked(ipAddressPrototype, "call");
        assertSourceBacked(ipAddressPrototype, "==");
        assertSourceBacked(ipAddressPrototype, "hash");
        ProtosObjectValue ipEndpointPrototype = prelude.ipEndpointPrototypeForRuntime();
        assertTrue(ipEndpointPrototype.isFrozen(), "standard IpEndpoint prototype must be frozen");
        assertTrue(
                ipEndpointPrototype.parent().orElse(null) == ProtosObjectValue.rootObject(),
                "standard IpEndpoint prototype must delegate directly to Object");
        assertNativeSelectors("IpEndpoint", ipEndpointPrototype, Set.of("recognizes"));
        assertEquals(
                Set.of("call", "==", "hash", "recognizes"),
                ipEndpointPrototype.localSlotsSnapshot().keySet(),
                "unexpected standard IpEndpoint prototype surface");
        assertSourceBacked(ipEndpointPrototype, "call");
        assertSourceBacked(ipEndpointPrototype, "==");
        assertSourceBacked(ipEndpointPrototype, "hash");
        assertNativeSelectors(
                "Network",
                prelude.networkPrototype(),
                Set.of("connectTcp", "listenTcp"));
        assertNativeSelectors(
                "TcpListener",
                prelude.tcpListenerPrototypeForRuntime(),
                Set.of("accept", "localPort", "close"));
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
                Set.of("call", "owning"));
        assertNativeSelectors(
                "TextWriter",
                ordinaryBinding(prelude, "TextWriter"),
                Set.of("call", "owning"));
        assertNativeSelectors(
                "BufferedReader",
                ProtosStandardModuleMemberTestSupport.bufferedReaderFactory(prelude),
                Set.of("call", "owning"));
        assertNativeSelectors(
                "BufferedWriter",
                ProtosStandardModuleMemberTestSupport.bufferedWriterFactory(prelude),
                Set.of("call", "owning"));
        assertNativeSelectors(
                "import",
                ordinaryBinding(prelude, "import"),
                Set.of("call"));
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
                Set.of("call", "size", "at", "atPut", "each", "add", "removeAt"));

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
                        JAVA_ROOT.resolve("execution").resolve("ProtosParallelRuntime.java"));
        assertEquals(0, occurrences(parallel, "slot(p,"));
        assertEquals(0, occurrences(parallel, "slot(r,"));

        String future =
                Files.readString(
                        JAVA_ROOT.resolve("execution").resolve("ProtosStandardFutureProtocol.java"));
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
                        JAVA_ROOT.resolve("execution").resolve("ProtosCoreBootstrap.java"));
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
