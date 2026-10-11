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

import com.guillermomolina.protos.runtime.ProtosActivation;
import com.guillermomolina.protos.runtime.ProtosClosureValue;
import com.guillermomolina.protos.runtime.ProtosCoreErrors;
import com.guillermomolina.protos.runtime.ProtosFileFlow;
import com.guillermomolina.protos.runtime.ProtosFilesystemNamespaceMutationFlow;
import com.guillermomolina.protos.runtime.ProtosFilesystemOpenFlow;
import com.guillermomolina.protos.runtime.ProtosFilesystemOpenOptions;
import com.guillermomolina.protos.runtime.ProtosFilesystemTreeObservationFlow;
import com.guillermomolina.protos.runtime.ProtosFilesystemValue;
import com.guillermomolina.protos.runtime.ProtosFutureValue;
import com.guillermomolina.protos.runtime.ProtosObjectValue;
import com.guillermomolina.protos.runtime.ProtosPathComponents;
import com.guillermomolina.protos.runtime.ProtosPrelude;
import com.guillermomolina.protos.runtime.ProtosStringValue;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * I016-D1 / I021-A / I024-B host/resource bridge for one provisioned Filesystem authority
 * capability.
 *
 * <p>The returned object is deliberately not installed in the Core prelude and there is no Protos
 * constructor for it. Process/bootstrap policy may provision zero or more capabilities later under
 * I017.
 *
 * <p>The backend is bound to one authority capability. For every accepted open it owns complete
 * race-safe path resolution and confinement, including parent traversal, symlink/reparse/alias/mount
 * indirection and concurrent namespace changes. If confinement inside that capability cannot be
 * established, the backend must fail instead of using uncertain authority. It also owns race-free
 * existing/create/createNew selection, empty-file creation, failure-atomic truncate-on-open, and
 * acquisition of a stable selected resource that later File operations do not re-resolve by Path.
 *
 * <p>Any create/truncate effect crosses the portable open commitment handshake immediately before
 * the effect becomes observable. I021 namespace replacement/removal instead uses the dedicated
 * per-operation atomic effect/commit cutover so cancellation cannot split a successful namespace
 * transition from its Future outcome. I024-B reuses the host-neutral tree-observation flow for
 * {@code entries}. D171 retired the public {@code captureTree} selector: host/runtime custody
 * (PLAT012) captures trees internally, and captured backends are structurally adapted as read-only
 * Filesystem authorities that never acquire a public {@code close} or capture surface.
 * File capability descriptors must exactly match the captured read/write authority; optional
 * seek/size/truncate/sync surfaces may be advertised only when the selected backend resource
 * implements their complete standard contracts.
 */
public final class ProtosStandardFilesystemProtocol {
    private ProtosStandardFilesystemProtocol() {}

    public interface OpenCompletion {
        boolean commitPortableEffect();

        void succeeded(
                ProtosFileFlow.Resource resource,
                ProtosFileFlow.Capabilities capabilities,
                Runnable releaseIfUntransferred);

        void failed();
    }

    /**
     * Internal backend marker for one completed immutable captured tree.
     *
     * <p>This is implementation machinery, not a Protos-visible Directory or resource-lifetime
     * family. Materialization wraps it as a structurally read-only standard Filesystem: mutation
     * never delegates and write/create/truncate opens fail before this backend is exercised.
     */
    public interface CapturedBackend extends Backend {
        /**
         * Host-only immutable read of one exact regular resource (PLAT012).
         *
         * <p>This projection needs no Activation, Filesystem capability, Process, Actor or
         * Context, never follows links and never exposes backing representation. Each call
         * returns a fresh detached copy and holds no shared cursor, so independent reads may
         * proceed concurrently. A backend without host read support fails closed.
         *
         * @throws IOException when the backing is released or the name does not denote an exact
         *     captured regular resource
         */
        default byte[] readRegularResource(ProtosPackageResourceName name) throws IOException {
            Objects.requireNonNull(name, "name");
            throw new IOException("captured backend has no host-only resource read projection");
        }
    }

    @FunctionalInterface
    public interface Backend {
        ProtosFilesystemOpenFlow.Cancellation open(
                ProtosPathComponents path,
                ProtosFilesystemOpenOptions options,
                OpenCompletion completion);

        default ProtosFilesystemNamespaceMutationFlow.Cancellation replace(
                ProtosPathComponents sourcePath,
                ProtosPathComponents targetPath,
                ProtosFilesystemNamespaceMutationFlow.MutationCompletion completion) {
            completion.failed();
            return () -> {};
        }

        default ProtosFilesystemNamespaceMutationFlow.Cancellation remove(
                ProtosPathComponents path,
                ProtosFilesystemNamespaceMutationFlow.MutationCompletion completion) {
            completion.failed();
            return () -> {};
        }

        default ProtosFilesystemTreeObservationFlow.Cancellation entries(
                ProtosPathComponents path,
                ProtosFilesystemTreeObservationFlow.EntriesCompletion completion) {
            completion.failed();
            return () -> {};
        }
    }

    private enum Operation {
        OPEN,
        REPLACE,
        REMOVE,
        ENTRIES
    }

    public static ProtosObjectValue createCapability(
            ProtosObjectValue bytesPrototype,
            ProtosActivation constructionActivation,
            Backend backend) {
        ProtosFilesystemValue filesystem = new ProtosFilesystemValue();
        installOperations(filesystem, bytesPrototype, constructionActivation, backend);
        return filesystem;
    }

    /**
     * Installs the standard operations on an already-allocated, still-empty Filesystem marker.
     *
     * <p>Used when the marker must exist before the Actor domain that owns its operations, such as
     * a default Filesystem handed to a Process before its RootActor's bootstrap activation exists.
     * The caller must install the operations before any guest code can reach the marker.
     */
    static void installOperations(
            ProtosFilesystemValue filesystem,
            ProtosObjectValue bytesPrototype,
            ProtosActivation constructionActivation,
            Backend backend) {
        Objects.requireNonNull(filesystem, "filesystem");
        Objects.requireNonNull(bytesPrototype, "bytesPrototype");
        Objects.requireNonNull(constructionActivation, "constructionActivation");
        Objects.requireNonNull(backend, "backend");

        ProtosFilesystemOpenFlow flow =
                new ProtosFilesystemOpenFlow(
                        filesystem,
                        constructionActivation,
                        (path, options, completion) ->
                                backend.open(
                                        path,
                                        options,
                                        new OpenCompletion() {
                                            @Override
                                            public boolean commitPortableEffect() {
                                                return completion.commitPortableEffect();
                                            }

                                            @Override
                                            public void succeeded(
                                                    ProtosFileFlow.Resource resource,
                                                    ProtosFileFlow.Capabilities capabilities,
                                                    Runnable releaseIfUntransferred) {
                                                materialize(
                                                        bytesPrototype,
                                                        constructionActivation,
                                                        options,
                                                        resource,
                                                        capabilities,
                                                        releaseIfUntransferred,
                                                        completion);
                                            }

                                            @Override
                                            public void failed() {
                                                completion.failed();
                                            }
                                        }));

        ProtosFilesystemNamespaceMutationFlow namespaceMutationFlow =
                new ProtosFilesystemNamespaceMutationFlow(
                        filesystem,
                        constructionActivation,
                        backend::replace,
                        backend::remove);

        ProtosFilesystemTreeObservationFlow treeObservationFlow =
                new ProtosFilesystemTreeObservationFlow(
                        filesystem,
                        constructionActivation,
                        backend::entries,
                        entries -> materializeEntries(constructionActivation, entries));

        filesystem.createLocalSlot(
                "open",
                operationClosure(
                        filesystem,
                        flow,
                        namespaceMutationFlow,
                        treeObservationFlow,
                        Operation.OPEN));
        filesystem.createLocalSlot(
                "replace",
                operationClosure(
                        filesystem,
                        flow,
                        namespaceMutationFlow,
                        treeObservationFlow,
                        Operation.REPLACE));
        filesystem.createLocalSlot(
                "remove",
                operationClosure(
                        filesystem,
                        flow,
                        namespaceMutationFlow,
                        treeObservationFlow,
                        Operation.REMOVE));
        filesystem.createLocalSlot(
                "entries",
                operationClosure(
                        filesystem,
                        flow,
                        namespaceMutationFlow,
                        treeObservationFlow,
                        Operation.ENTRIES));
    }

    /** Host-internal rematerialization of one captured backend in a chosen Actor domain. */
    static ProtosFilesystemValue createCapturedCapability(
            ProtosActivation constructionActivation, CapturedBackend capturedBackend) {
        Objects.requireNonNull(constructionActivation, "constructionActivation");
        Objects.requireNonNull(capturedBackend, "capturedBackend");
        ProtosPrelude prelude =
                constructionActivation
                        .prelude()
                        .orElseThrow(
                                () ->
                                        new IllegalArgumentException(
                                                "captured Filesystem materialization requires a Core prelude"));
        return (ProtosFilesystemValue)
                createCapability(
                        prelude.bytesPrototypeForRuntime(),
                        constructionActivation,
                        readOnlyCapturedBackend(capturedBackend));
    }

    private static ProtosClosureValue operationClosure(
            ProtosObjectValue filesystem,
            ProtosFilesystemOpenFlow openFlow,
            ProtosFilesystemNamespaceMutationFlow namespaceMutationFlow,
            ProtosFilesystemTreeObservationFlow treeObservationFlow,
            Operation operation) {
        return ProtosClosureValue.nativeClosure(
                (activation, arguments) -> {
                    if (activation.receiver() != filesystem) {
                        return invalid(activation);
                    }
                    return switch (operation) {
                        case OPEN -> {
                            if (arguments.size() == 1) {
                                yield openFlow.open(activation, arguments.get(0));
                            }
                            if (arguments.size() == 2) {
                                yield openFlow.open(
                                        activation, arguments.get(0), arguments.get(1));
                            }
                            yield invalid(activation);
                        }
                        case REPLACE ->
                                arguments.size() == 2
                                        ? namespaceMutationFlow.replace(
                                                activation,
                                                arguments.get(0),
                                                arguments.get(1))
                                        : invalid(activation);
                        case REMOVE ->
                                arguments.size() == 1
                                        ? namespaceMutationFlow.remove(
                                                activation, arguments.get(0))
                                        : invalid(activation);
                        case ENTRIES ->
                                arguments.size() == 1
                                        ? treeObservationFlow.entries(
                                                activation, arguments.get(0))
                                        : invalid(activation);
                    };
                });
    }

    private static ProtosObjectValue materializeEntries(
            ProtosActivation constructionActivation,
            List<ProtosFilesystemTreeObservationFlow.Entry> entries) {
        Objects.requireNonNull(entries, "entries");
        ProtosPrelude prelude = constructionActivation.prelude().orElseThrow();
        ArrayList<ProtosObjectValue> descriptors = new ArrayList<>(entries.size());
        for (ProtosFilesystemTreeObservationFlow.Entry entry : entries) {
            Objects.requireNonNull(entry, "entry");
            ProtosObjectValue descriptor =
                    new ProtosObjectValue(ProtosObjectValue.rootObject());
            descriptor.createLocalSlot("name", new ProtosStringValue(entry.name()));
            descriptor.createLocalSlot("kind", new ProtosStringValue(semanticKind(entry.kind())));
            descriptor.freeze();
            descriptors.add(descriptor);
        }
        return prelude.newArray(descriptors);
    }

    private static String semanticKind(ProtosFilesystemTreeObservationFlow.EntryKind kind) {
        return switch (Objects.requireNonNull(kind, "kind")) {
            case REGULAR -> "regular";
            case DIRECTORY -> "directory";
            case LINK -> "link";
            case OTHER -> "other";
        };
    }

    private static Backend readOnlyCapturedBackend(CapturedBackend capturedBackend) {
        Objects.requireNonNull(capturedBackend, "capturedBackend");
        return new Backend() {
            @Override
            public ProtosFilesystemOpenFlow.Cancellation open(
                    ProtosPathComponents path,
                    ProtosFilesystemOpenOptions options,
                    OpenCompletion completion) {
                if (!readOnlyExisting(options)) {
                    completion.failed();
                    return () -> {};
                }
                return capturedBackend.open(path, options, completion);
            }

            @Override
            public ProtosFilesystemTreeObservationFlow.Cancellation entries(
                    ProtosPathComponents path,
                    ProtosFilesystemTreeObservationFlow.EntriesCompletion completion) {
                return capturedBackend.entries(path, completion);
            }
        };
    }

    private static boolean readOnlyExisting(ProtosFilesystemOpenOptions options) {
        Objects.requireNonNull(options, "options");
        return options.readAccess()
                && !options.writeAccess()
                && options.creation() == ProtosFilesystemOpenOptions.Creation.EXISTING
                && !options.truncateInitialContent();
    }

    private static void materialize(
            ProtosObjectValue bytesPrototype,
            ProtosActivation constructionActivation,
            ProtosFilesystemOpenOptions options,
            ProtosFileFlow.Resource resource,
            ProtosFileFlow.Capabilities capabilities,
            Runnable releaseIfUntransferred,
            ProtosFilesystemOpenFlow.OpenCompletion completion) {
        try {
            Objects.requireNonNull(resource, "resource");
            Objects.requireNonNull(capabilities, "capabilities");
            Objects.requireNonNull(releaseIfUntransferred, "releaseIfUntransferred");

            if (capabilities.readable() != options.readAccess()
                    || capabilities.writable() != options.writeAccess()) {
                throw new IllegalArgumentException(
                        "backend File capability shape does not match captured open authority");
            }

        } catch (RuntimeException invalidBackendDescriptor) {
            releaseSafely(releaseIfUntransferred);
            completion.failed();
            return;
        }
        // I094: only inert backend custody crosses here; the guest File is constructed by the
        // open flow inside the owning Actor execution.
        completion.acquired(
                () ->
                        ProtosStandardFileProtocol.create(
                                bytesPrototype, constructionActivation, resource, capabilities),
                releaseIfUntransferred);
    }

    private static void releaseSafely(Runnable release) {
        if (release == null) {
            return;
        }
        try {
            release.run();
        } catch (RuntimeException ignored) {
            // Invalid backend descriptors are never exposed as standard Files.
        }
    }

    private static ProtosFutureValue invalid(ProtosActivation activation) {
        ProtosFutureValue future =
                new ProtosFutureValue(
                        activation.prelude().orElseThrow().futurePrototype(),
                        activation.executionDomain());
        future.fail(
                ProtosCoreErrors.newOccurrence(
                        activation, ProtosCoreErrors.StandardError.INVALID_I_O_ARGUMENT));
        return future;
    }
}
