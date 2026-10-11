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

import java.util.Objects;

/**
 * I021-A host-neutral asynchronous Filesystem namespace-mutation substrate.
 *
 * <p>Each invocation owns an independent operation lifecycle so unrelated namespace operations do
 * not acquire a Filesystem-wide sequencing lock. A backend that is ready to publish one indivisible
 * namespace effect supplies that effect to {@link MutationCompletion#commitPortableEffect}. The
 * effect and the Protos commitment transition execute under the same per-operation lifecycle lock:
 * pre-commit cancellation therefore prevents the effect, while a successful atomic effect becomes
 * committed before cancellation can observe an intermediate state.
 *
 * <p>The backend effect itself must satisfy the D041 all-or-nothing namespace contract. An effect
 * that throws is treated as an uncommitted {@code IOError}; a backend must not expose an effect
 * implementation that can throw after publishing a partial or uncertain namespace transition.
 */
public final class ProtosFilesystemNamespaceMutationFlow {
    @FunctionalInterface
    public interface Cancellation {
        void cancel();
    }

    @FunctionalInterface
    public interface NamespaceEffect {
        void run() throws Exception;
    }

    public interface MutationCompletion {
        /**
         * Executes one failure-atomic namespace effect and commits it as one cancellation cutover.
         *
         * <p>A false result means cancellation or another terminal outcome already won and the
         * supplied effect was not executed. If the supplied effect throws, this method establishes a
         * failed {@code IOError} Future and returns false.
         */
        boolean commitPortableEffect(NamespaceEffect effect);

        /** Completes a successful namespace no-op, such as replace of one entry by itself. */
        void succeeded();

        /** Reports an ordinary uncommitted backend/namespace failure. */
        void failed();
    }

    @FunctionalInterface
    public interface ReplaceBackend {
        Cancellation replace(
                ProtosPathComponents sourcePath,
                ProtosPathComponents targetPath,
                MutationCompletion completion);
    }

    @FunctionalInterface
    public interface RemoveBackend {
        Cancellation remove(ProtosPathComponents path, MutationCompletion completion);
    }

    @FunctionalInterface
    private interface BackendInvocation {
        Cancellation start(MutationCompletion completion);
    }

    private final ProtosObjectValue filesystemCapability;
    private final ProtosActorExecutionDomain domain;
    private final ProtosObjectValue futurePrototype;
    private final ReplaceBackend replaceBackend;
    private final RemoveBackend removeBackend;

    public ProtosFilesystemNamespaceMutationFlow(
            ProtosObjectValue filesystemCapability,
            ProtosActivation bootstrapActivation,
            ReplaceBackend replaceBackend,
            RemoveBackend removeBackend) {
        this.filesystemCapability =
                Objects.requireNonNull(filesystemCapability, "filesystemCapability");
        Objects.requireNonNull(bootstrapActivation, "bootstrapActivation");
        this.domain = bootstrapActivation.executionDomain();
        this.futurePrototype = bootstrapActivation.prelude().orElseThrow().futurePrototype();
        this.replaceBackend = Objects.requireNonNull(replaceBackend, "replaceBackend");
        this.removeBackend = Objects.requireNonNull(removeBackend, "removeBackend");
    }

    public ProtosFutureValue replace(
            ProtosActivation activation, Object sourcePathValue, Object targetPathValue) {
        Objects.requireNonNull(activation, "activation");
        requireDomain(activation);
        if (!(sourcePathValue instanceof ProtosPathValue sourcePath)
                || !(targetPathValue instanceof ProtosPathValue targetPath)) {
            return failedFuture(
                    activation, ProtosCoreErrors.StandardError.INVALID_I_O_ARGUMENT);
        }
        ProtosPathComponents capturedSource = new ProtosPathComponents(sourcePath.components());
        ProtosPathComponents capturedTarget = new ProtosPathComponents(targetPath.components());
        return invoke(
                activation,
                completion ->
                        replaceBackend.replace(capturedSource, capturedTarget, completion));
    }

    public ProtosFutureValue remove(ProtosActivation activation, Object pathValue) {
        Objects.requireNonNull(activation, "activation");
        requireDomain(activation);
        if (!(pathValue instanceof ProtosPathValue path)) {
            return failedFuture(
                    activation, ProtosCoreErrors.StandardError.INVALID_I_O_ARGUMENT);
        }
        ProtosPathComponents capturedPath = new ProtosPathComponents(path.components());
        return invoke(activation, completion -> removeBackend.remove(capturedPath, completion));
    }

    private ProtosFutureValue invoke(
            ProtosActivation activation, BackendInvocation backendInvocation) {
        /*
         * Filesystem is not Closable. A fresh hidden lifecycle per namespace operation gives the
         * existing Actor-termination/Future-cancellation machinery a commitment lock without
         * serializing unrelated namespace operations through one Filesystem-wide lifecycle.
         */
        ProtosIoLifecycle lifecycle =
                new ProtosIoLifecycle(
                        filesystemCapability,
                        futurePrototype,
                        domain,
                        completion -> completion.succeeded());
        ProtosIoOperation operation = lifecycle.beginOperation(activation);
        if (!operation.future().isPending()) {
            return operation.future();
        }

        CancellationBridge cancellationBridge = new CancellationBridge();
        operation.onCancellation(cancellationBridge::requestCancellation);

        MutationCompletion completion =
                new MutationCompletion() {
                    @Override
                    public boolean commitPortableEffect(NamespaceEffect effect) {
                        Objects.requireNonNull(effect, "effect");
                        synchronized (lifecycle) {
                            if (operation.terminal() || operation.committed()) {
                                return false;
                            }
                            try {
                                effect.run();
                            } catch (Exception failure) {
                                if (failure instanceof InterruptedException) {
                                    Thread.currentThread().interrupt();
                                }
                                operation.fail(ioError(activation));
                                return false;
                            }

                            /*
                             * Cancellation uses this same lifecycle monitor. Because this lifecycle
                             * is private to this operation and never enters a close lifecycle, a
                             * successful effect cannot lose the immediately following commit.
                             */
                            if (!operation.commit()) {
                                throw new AssertionError(
                                        "namespace effect completed without a commit cutover");
                            }
                            if (!operation.resolve(filesystemCapability)) {
                                throw new AssertionError(
                                        "committed namespace effect did not resolve its Future");
                            }
                            return true;
                        }
                    }

                    @Override
                    public void succeeded() {
                        synchronized (lifecycle) {
                            if (operation.terminal()) {
                                return;
                            }
                            if (!operation.committed() && !operation.commit()) {
                                return;
                            }
                            operation.resolve(filesystemCapability);
                        }
                    }

                    @Override
                    public void failed() {
                        synchronized (lifecycle) {
                            if (!operation.terminal()) {
                                operation.fail(ioError(activation));
                            }
                        }
                    }
                };

        try {
            cancellationBridge.install(backendInvocation.start(completion));
        } catch (RuntimeException backendFailure) {
            synchronized (lifecycle) {
                if (!operation.terminal()) {
                    operation.fail(ioError(activation));
                }
            }
        }
        return operation.future();
    }

    private ProtosObjectValue ioError(ProtosActivation activation) {
        return ProtosCoreErrors.newOccurrence(
                activation, ProtosCoreErrors.StandardError.I_O_ERROR);
    }

    private void requireDomain(ProtosActivation activation) {
        if (activation.executionDomain() != domain) {
            throw new IllegalArgumentException(
                    "Filesystem namespace-mutation flow belongs to another Actor domain");
        }
    }

    private ProtosFutureValue failedFuture(
            ProtosActivation activation, ProtosCoreErrors.StandardError error) {
        ProtosFutureValue future = new ProtosFutureValue(futurePrototype, domain);
        future.fail(ProtosCoreErrors.newOccurrence(activation, error));
        return future;
    }

    /** Handles cancellation racing backend registration without losing the cancellation request. */
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
