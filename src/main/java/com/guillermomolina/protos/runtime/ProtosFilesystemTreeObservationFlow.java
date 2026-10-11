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
package com.guillermomolina.protos.runtime;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/**
 * I024-A host-neutral asynchronous substrate for Filesystem {@code entries} observation.
 *
 * <p>This flow does not install {@code entries} on a public Filesystem capability and does not
 * provide a host filesystem implementation. It owns only Path-domain preflight, independent
 * Future/cancellation/Actor-lifecycle mechanics, and defensive backend-result snapshot. D171
 * retired the public {@code captureTree} selector; immutable capture is host/runtime custody
 * machinery outside this flow.
 */
public final class ProtosFilesystemTreeObservationFlow {
    @FunctionalInterface
    public interface Cancellation {
        void cancel();
    }

    public enum EntryKind {
        REGULAR,
        DIRECTORY,
        LINK,
        OTHER
    }

    /** Inert exact-name/no-follow-kind backend observation. It carries no authority. */
    public record Entry(String name, EntryKind kind) {
        public Entry {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(kind, "kind");
            if (name.isEmpty() || name.equals(".") || name.equals("..")) {
                throw new IllegalArgumentException("entry name is not one exact stored child");
            }
        }
    }

    public interface EntriesCompletion {
        void succeeded(List<Entry> entries);

        void failed();
    }

    @FunctionalInterface
    public interface EntriesBackend {
        Cancellation entries(ProtosPathComponents path, EntriesCompletion completion);
    }

    /**
     * Semantic result bridge injected by the caller.
     *
     * <p>I024-B owns the standard Array/descriptor materialization.
     */
    @FunctionalInterface
    public interface ResultMaterializer {
        ProtosObjectValue entries(List<Entry> entries);
    }

    private final ProtosObjectValue filesystemCapability;
    private final ProtosActorExecutionDomain domain;
    private final ProtosObjectValue futurePrototype;
    private final EntriesBackend entriesBackend;
    private final ResultMaterializer materializer;

    public ProtosFilesystemTreeObservationFlow(
            ProtosObjectValue filesystemCapability,
            ProtosActivation bootstrapActivation,
            EntriesBackend entriesBackend,
            ResultMaterializer materializer) {
        this.filesystemCapability =
                Objects.requireNonNull(filesystemCapability, "filesystemCapability");
        Objects.requireNonNull(bootstrapActivation, "bootstrapActivation");
        this.domain = bootstrapActivation.executionDomain();
        this.futurePrototype = bootstrapActivation.prelude().orElseThrow().futurePrototype();
        this.entriesBackend = Objects.requireNonNull(entriesBackend, "entriesBackend");
        this.materializer = Objects.requireNonNull(materializer, "materializer");
    }

    public ProtosFutureValue entries(ProtosActivation activation, Object pathValue) {
        Objects.requireNonNull(activation, "activation");
        requireDomain(activation);
        if (!(pathValue instanceof ProtosPathValue path)) {
            return failedFuture(
                    activation, ProtosCoreErrors.StandardError.INVALID_I_O_ARGUMENT);
        }
        ProtosPathComponents capturedPath = new ProtosPathComponents(path.components());

        Invocation invocation = begin(activation);
        if (invocation.terminal()) {
            return invocation.future();
        }

        invocation.enterBackendCall();
        try {
            invocation.install(
                    entriesBackend.entries(
                            capturedPath,
                            new EntriesCompletion() {
                                @Override
                                public void succeeded(List<Entry> entries) {
                                    // The host callback captures only the inert snapshot.
                                    List<Entry> snapshot;
                                    try {
                                        snapshot = snapshotEntries(entries);
                                    } catch (RuntimeException invalidBackendResult) {
                                        invocation.deliver(invocation::fail);
                                        return;
                                    }
                                    if (invocation.terminal()) {
                                        return;
                                    }
                                    invocation.deliver(() -> publish(invocation, snapshot));
                                }

                                @Override
                                public void failed() {
                                    invocation.deliver(invocation::fail);
                                }
                            }));
        } catch (RuntimeException backendFailure) {
            invocation.fail();
        } finally {
            invocation.exitBackendCall();
        }
        return invocation.future();
    }

    /** Owning-domain construction of the guest entries Array, only while it can be accepted. */
    private boolean publish(Invocation invocation, List<Entry> snapshot) {
        if (invocation.terminal()) {
            return false;
        }
        ProtosObjectValue value;
        try {
            value =
                    Objects.requireNonNull(
                            materializer.entries(snapshot), "entries materializer result");
        } catch (RuntimeException invalidBackendResult) {
            return invocation.fail();
        }
        return invocation.succeed(value);
    }

    private Invocation begin(ProtosActivation activation) {
        ProtosIoLifecycle lifecycle =
                new ProtosIoLifecycle(
                        filesystemCapability,
                        futurePrototype,
                        domain,
                        completion -> completion.succeeded());
        return new Invocation(activation, lifecycle);
    }

    private final class Invocation {
        private final ProtosActivation activation;
        private final ProtosIoLifecycle lifecycle;
        private final ProtosIoOperation operation;
        private final CancellationBridge cancellationBridge = new CancellationBridge();
        private volatile Thread synchronousCaller;

        Invocation(ProtosActivation activation, ProtosIoLifecycle lifecycle) {
            this.activation = activation;
            this.lifecycle = lifecycle;
            this.operation = lifecycle.beginOperation(activation);
            if (operation.future().isPending()) {
                operation.onCancellation(cancellationBridge::requestCancellation);
            }
        }

        ProtosFutureValue future() {
            return operation.future();
        }

        boolean terminal() {
            return operation.terminal();
        }

        void install(Cancellation cancellation) {
            cancellationBridge.install(cancellation);
        }

        void enterBackendCall() {
            synchronousCaller = Thread.currentThread();
        }

        void exitBackendCall() {
            synchronousCaller = null;
        }

        /**
         * Runs {@code action} directly only inside the owner's synchronous backend call. Any other
         * completion is a host callback: the guest-visible outcome is built by a targeted runtime
         * completion of this operation's Future inside the owning Actor. A terminating owner
         * cancels the operation itself, so a rejected delivery has nothing left to publish.
         */
        void deliver(BooleanSupplier action) {
            if (synchronousCaller == Thread.currentThread()) {
                action.getAsBoolean();
                return;
            }
            try {
                domain.enqueueTargetedFutureCompletionForRuntime(operation.future(), action);
            } catch (IllegalStateException ownerTerminating) {
                // Termination cancellation owns the outcome; no guest value is built here.
            }
        }

        boolean succeed(ProtosObjectValue value) {
            Objects.requireNonNull(value, "value");
            synchronized (lifecycle) {
                return !operation.terminal() && operation.commit() && operation.resolve(value);
            }
        }

        boolean fail() {
            synchronized (lifecycle) {
                return !operation.terminal()
                        && operation.fail(
                                ProtosCoreErrors.newOccurrence(
                                        activation, ProtosCoreErrors.StandardError.I_O_ERROR));
            }
        }
    }

    private void requireDomain(ProtosActivation activation) {
        if (activation.executionDomain() != domain) {
            throw new IllegalArgumentException(
                    "Filesystem tree-observation flow belongs to another Actor domain");
        }
    }

    private ProtosFutureValue failedFuture(
            ProtosActivation activation, ProtosCoreErrors.StandardError error) {
        ProtosFutureValue future = new ProtosFutureValue(futurePrototype, domain);
        future.fail(ProtosCoreErrors.newOccurrence(activation, error));
        return future;
    }

    private static List<Entry> snapshotEntries(List<Entry> entries) {
        List<Entry> snapshot = List.copyOf(Objects.requireNonNull(entries, "entries"));
        HashSet<String> exactNames = new HashSet<>();
        for (Entry entry : snapshot) {
            Objects.requireNonNull(entry, "entry");
            if (!exactNames.add(entry.name())) {
                throw new IllegalArgumentException("duplicate exact direct-child name");
            }
        }
        return snapshot;
    }

    private static final class CancellationBridge {
        private Cancellation cancellation;
        private boolean cancellationRequested;

        void requestCancellation() {
            Cancellation toCancel;
            synchronized (this) {
                cancellationRequested = true;
                toCancel = cancellation;
            }
            if (toCancel != null) {
                toCancel.cancel();
            }
        }

        void install(Cancellation installed) {
            boolean cancelNow;
            synchronized (this) {
                cancellation = installed;
                cancelNow = cancellationRequested && installed != null;
            }
            if (cancelNow) {
                installed.cancel();
            }
        }
    }
}
