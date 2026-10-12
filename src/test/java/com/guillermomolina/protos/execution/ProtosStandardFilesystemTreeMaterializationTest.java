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
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.guillermomolina.protos.runtime.ProtosActivation;
import com.guillermomolina.protos.runtime.ProtosArrayValue;
import com.guillermomolina.protos.runtime.ProtosBooleanValue;
import com.guillermomolina.protos.runtime.ProtosFilesystemOpenFlow;
import com.guillermomolina.protos.runtime.ProtosFilesystemOpenOptions;
import com.guillermomolina.protos.runtime.ProtosFilesystemTreeObservationFlow;
import com.guillermomolina.protos.runtime.ProtosFutureValue;
import com.guillermomolina.protos.runtime.ProtosObjectValue;
import com.guillermomolina.protos.runtime.ProtosPathComponents;
import com.guillermomolina.protos.runtime.ProtosPathValue;
import com.guillermomolina.protos.runtime.ProtosPrelude;
import com.guillermomolina.protos.runtime.ProtosStringValue;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

final class ProtosStandardFilesystemTreeMaterializationTest {
    private static Fixture fixture() throws Exception {
        ProtosPrelude prelude =
                new ProtosCoreBootstrap().bootstrap(Path.of("protos", "lib", "core"));
        ProtosActivation activation = prelude.newModuleActivation();
        ProtosObjectValue bytesPrototype =
                new ProtosObjectValue(ProtosObjectValue.rootObject());
        ProtosStandardBytesProtocol.install(bytesPrototype);
        RecordingBackend backend = new RecordingBackend();
        ProtosObjectValue filesystem =
                ProtosStandardFilesystemProtocol.createCapability(
                        bytesPrototype, activation, backend);
        return new Fixture(prelude, activation, backend, filesystem);
    }

    @Test
    void entriesMaterializeFreshArrayAndFreshFrozenInertDescriptors() throws Exception {
        Fixture x = fixture();

        ProtosFutureValue firstFuture =
                invoke(x, x.filesystem, "entries", List.of(path(x.prelude, "root")));
        EntriesInvocation firstInvocation = x.backend.entriesInvocations.remove();
        firstInvocation.completion.succeeded(
                List.of(
                        entry("alpha", ProtosFilesystemTreeObservationFlow.EntryKind.REGULAR),
                        entry("nested", ProtosFilesystemTreeObservationFlow.EntryKind.DIRECTORY),
                        entry("shortcut", ProtosFilesystemTreeObservationFlow.EntryKind.LINK),
                        entry("special", ProtosFilesystemTreeObservationFlow.EntryKind.OTHER)));

        ProtosArrayValue firstArray = resolvedArray(firstFuture);
        assertSame(x.prelude.arrayPrototype(), firstArray.parent().orElseThrow());
        assertFalse(firstArray.isFrozen());
        assertEquals(4, firstArray.indexedSize());

        Map<String, ProtosObjectValue> first = descriptorsByName(firstArray);
        assertEquals(Set.of("alpha", "nested", "shortcut", "special"), first.keySet());
        assertDescriptor(first.get("alpha"), "alpha", "regular");
        assertDescriptor(first.get("nested"), "nested", "directory");
        assertDescriptor(first.get("shortcut"), "shortcut", "link");
        assertDescriptor(first.get("special"), "special", "other");

        ProtosFutureValue secondFuture =
                invoke(x, x.filesystem, "entries", List.of(path(x.prelude, "root")));
        x.backend.entriesInvocations
                .remove()
                .completion
                .succeeded(
                        List.of(
                                entry(
                                        "alpha",
                                        ProtosFilesystemTreeObservationFlow.EntryKind.REGULAR)));
        ProtosArrayValue secondArray = resolvedArray(secondFuture);
        ProtosObjectValue secondAlpha = descriptorsByName(secondArray).get("alpha");

        assertNotSame(firstArray, secondArray);
        assertNotSame(first.get("alpha"), secondAlpha);
        assertDescriptor(secondAlpha, "alpha", "regular");
    }

    @Test
    void hostCapturedMaterializationIsStructurallyReadOnlyFilesystemWithoutCloseOrCapture()
            throws Exception {
        Fixture x = fixture();
        RecordingCapturedBackend capturedBackend = new RecordingCapturedBackend();

        // D171: captured Filesystems are provisioned only by host/runtime custody (PLAT012).
        ProtosObjectValue captured =
                ProtosStandardFilesystemProtocol.createCapturedCapability(
                        x.activation, capturedBackend);
        assertNotSame(x.filesystem, captured);
        assertSame(ProtosObjectValue.rootObject(), captured.parent().orElseThrow());
        assertEquals(
                Set.of("open", "replace", "remove", "entries"),
                captured.localSlotsSnapshot().keySet());
        assertFalse(captured.hasLocalSlot("close"));
        assertFalse(captured.hasLocalSlot("captureTree"));

        ProtosFutureValue entries =
                invoke(x, captured, "entries", List.of(path(x.prelude, "child")));
        CapturedEntriesInvocation capturedEntries = capturedBackend.entriesInvocations.remove();
        capturedEntries.completion.succeeded(
                List.of(entry("leaf", ProtosFilesystemTreeObservationFlow.EntryKind.OTHER)));
        assertDescriptor(descriptorsByName(resolvedArray(entries)).get("leaf"), "leaf", "other");

        ProtosFutureValue replace =
                invoke(
                        x,
                        captured,
                        "replace",
                        List.of(path(x.prelude, "source"), path(x.prelude, "target")));
        ProtosFutureValue remove =
                invoke(x, captured, "remove", List.of(path(x.prelude, "target")));
        assertIoError(x, replace);
        assertIoError(x, remove);

        ProtosObjectValue writeOptions = new ProtosObjectValue(ProtosObjectValue.rootObject());
        writeOptions.createLocalSlot("read", ProtosBooleanValue.FALSE);
        writeOptions.createLocalSlot("write", ProtosBooleanValue.TRUE);
        ProtosFutureValue writeOpen =
                invoke(
                        x,
                        captured,
                        "open",
                        List.of(path(x.prelude, "leaf"), writeOptions));
        assertIoError(x, writeOpen);
        assertEquals(0, capturedBackend.openCalls.get());

        ProtosFutureValue readOpen =
                invoke(x, captured, "open", List.of(path(x.prelude, "leaf")));
        assertIoError(x, readOpen);
        assertEquals(1, capturedBackend.openCalls.get());
    }

    private static ProtosFutureValue invoke(
            Fixture x, ProtosObjectValue receiver, String selector, List<?> arguments) {
        return (ProtosFutureValue)
                ProtosInvocation.invokeMessage(receiver, selector, arguments, x.activation);
    }

    private static ProtosArrayValue resolvedArray(ProtosFutureValue future) {
        // I094: a completion outside the original backend call is delivered by the owner.
        while (future.domain().dispatchOne()) {
            // Owner-domain delivery.
        }
        assertEquals(ProtosFutureValue.State.RESOLVED, future.state());
        return assertInstanceOf(ProtosArrayValue.class, future.resolvedValue().orElseThrow());
    }

    private static ProtosFilesystemTreeObservationFlow.Entry entry(
            String name, ProtosFilesystemTreeObservationFlow.EntryKind kind) {
        return new ProtosFilesystemTreeObservationFlow.Entry(name, kind);
    }

    private static Map<String, ProtosObjectValue> descriptorsByName(ProtosArrayValue array) {
        LinkedHashMap<String, ProtosObjectValue> result = new LinkedHashMap<>();
        for (Object value : array.indexedSnapshot()) {
            ProtosObjectValue descriptor = assertInstanceOf(ProtosObjectValue.class, value);
            ProtosStringValue name =
                    assertInstanceOf(
                            ProtosStringValue.class,
                            descriptor.readLocalSlot("name").orElseThrow());
            assertFalse(result.containsKey(name.value()));
            result.put(name.value(), descriptor);
        }
        return result;
    }

    private static void assertDescriptor(
            ProtosObjectValue descriptor, String expectedName, String expectedKind) {
        assertNotNull(descriptor);
        assertSame(ProtosObjectValue.rootObject(), descriptor.parent().orElseThrow());
        assertTrue(descriptor.isFrozen());
        assertEquals(Set.of("name", "kind"), descriptor.localSlotsSnapshot().keySet());
        assertEquals(
                expectedName,
                assertInstanceOf(
                                ProtosStringValue.class,
                                descriptor.readLocalSlot("name").orElseThrow())
                        .value());
        assertEquals(
                expectedKind,
                assertInstanceOf(
                                ProtosStringValue.class,
                                descriptor.readLocalSlot("kind").orElseThrow())
                        .value());
    }

    private static void assertIoError(Fixture x, ProtosFutureValue future) {
        assertEquals(ProtosFutureValue.State.FAILED, future.state());
        assertSame(
                x.prelude.bindings().readLocalSlot("IOError").orElseThrow(),
                future.failedError().orElseThrow().parent().orElseThrow());
    }

    private static ProtosPathValue path(ProtosPrelude prelude, String name) {
        return new ProtosPathValue(
                prelude.pathPrototype(), List.of(name));
    }

    private record Fixture(
            ProtosPrelude prelude,
            ProtosActivation activation,
            RecordingBackend backend,
            ProtosObjectValue filesystem) {}

    private static final class RecordingBackend
            implements ProtosStandardFilesystemProtocol.Backend {
        private final ArrayDeque<EntriesInvocation> entriesInvocations = new ArrayDeque<>();

        @Override
        public ProtosFilesystemOpenFlow.Cancellation open(
                ProtosPathComponents path,
                ProtosFilesystemOpenOptions options,
                ProtosStandardFilesystemProtocol.OpenCompletion completion) {
            completion.failed();
            return () -> {};
        }

        @Override
        public ProtosFilesystemTreeObservationFlow.Cancellation entries(
                ProtosPathComponents path,
                ProtosFilesystemTreeObservationFlow.EntriesCompletion completion) {
            EntriesInvocation invocation = new EntriesInvocation(completion);
            entriesInvocations.add(invocation);
            return invocation.cancellations::incrementAndGet;
        }
    }

    private static final class EntriesInvocation {
        private final ProtosFilesystemTreeObservationFlow.EntriesCompletion completion;
        private final AtomicInteger cancellations = new AtomicInteger();

        private EntriesInvocation(
                ProtosFilesystemTreeObservationFlow.EntriesCompletion completion) {
            this.completion = completion;
        }
    }

    private static final class CapturedEntriesInvocation {
        private final ProtosFilesystemTreeObservationFlow.EntriesCompletion completion;
        private final AtomicInteger cancellations = new AtomicInteger();

        private CapturedEntriesInvocation(
                ProtosFilesystemTreeObservationFlow.EntriesCompletion completion) {
            this.completion = completion;
        }
    }

    private static final class RecordingCapturedBackend
            implements ProtosStandardFilesystemProtocol.CapturedBackend {
        private final AtomicInteger openCalls = new AtomicInteger();
        private final ArrayDeque<CapturedEntriesInvocation> entriesInvocations =
                new ArrayDeque<>();

        @Override
        public ProtosFilesystemOpenFlow.Cancellation open(
                ProtosPathComponents path,
                ProtosFilesystemOpenOptions options,
                ProtosStandardFilesystemProtocol.OpenCompletion completion) {
            openCalls.incrementAndGet();
            completion.failed();
            return () -> {};
        }

        @Override
        public ProtosFilesystemTreeObservationFlow.Cancellation entries(
                ProtosPathComponents path,
                ProtosFilesystemTreeObservationFlow.EntriesCompletion completion) {
            CapturedEntriesInvocation invocation = new CapturedEntriesInvocation(completion);
            entriesInvocations.add(invocation);
            return invocation.cancellations::incrementAndGet;
        }
    }
}
