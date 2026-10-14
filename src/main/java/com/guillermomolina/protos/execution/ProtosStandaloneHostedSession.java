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

import com.guillermomolina.protos.execution.ProtosWorkspacePackageApplicationExecution.NetworkGrant;
import com.guillermomolina.protos.runtime.ProtosActivation;
import com.guillermomolina.protos.runtime.ProtosActorModuleState;
import com.guillermomolina.protos.runtime.ProtosClosureValue;
import com.guillermomolina.protos.runtime.ProtosObjectValue;
import com.guillermomolina.protos.runtime.ProtosPrelude;
import com.guillermomolina.protos.runtime.ProtosProcessRuntime;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import org.graalvm.polyglot.Value;

/**
 * Supported reusable JVM embedding session: one live standalone Protos Process whose entry source
 * has been executed once and whose top-level Closures can then be invoked repeatedly.
 *
 * <p>Opening performs the same sequence as {@link ProtosStandaloneHostedExecution#executeFile}
 * (Core bootstrap, standalone Process bootstrap, Process-scoped Polyglot Context binding,
 * canonical direct-file execution) but keeps the Process, its Polyglot Context (and therefore
 * Engine and JIT/profiling state), and the runtime host alive until {@link #close()}. The
 * terminal outcome of that first execution is {@link #initialOutcome()}.
 *
 * <p>{@link #invokeTopLevel} reads the named slot of the entry module instance and invokes the
 * Closure it holds as a RootActor-local task in that same Process; no source text is parsed per
 * call. {@link #prepareTopLevel} instead resolves that slot once and retains the Closure for
 * repeated invocation.
 *
 * <p>Guest operations run on the thread that calls the session operation (PLAT046 Candidate B),
 * through the Process execution host and Polyglot Context entry; the session starts no guest
 * thread of its own. A session may be shared between threads: a session-local gate admits at most
 * one guest operation at a time. {@link #close()} makes its cutover visible first, so no operation
 * begins afterwards, then waits at the same gate for an already-started operation to finish before
 * tearing down.
 */
public final class ProtosStandaloneHostedSession implements AutoCloseable {
    private final ReentrantLock gate = new ReentrantLock();
    private final ProtosDirectFileModuleResolver resolver;
    private final ProtosProcessRuntime process;
    private final ProtosPolyglotRuntimeHost runtimeHost;
    private final ProtosPolyglotProcessContext processContext;
    private final ProtosExecutionOutcome initialOutcome;
    private final ProtosObjectValue entryModule;
    private final ProtosActivation entryActivation;
    private final AtomicBoolean closeStarted = new AtomicBoolean();

    private ProtosStandaloneHostedSession(
            ProtosDirectFileModuleResolver resolver,
            ProtosProcessRuntime process,
            ProtosPolyglotRuntimeHost runtimeHost,
            ProtosPolyglotProcessContext processContext,
            ProtosExecutionOutcome initialOutcome,
            ProtosObjectValue entryModule,
            ProtosActivation entryActivation) {
        this.resolver = resolver;
        this.process = process;
        this.runtimeHost = runtimeHost;
        this.processContext = processContext;
        this.initialOutcome = initialOutcome;
        this.entryModule = entryModule;
        this.entryActivation = entryActivation;
    }

    /**
     * Opens a session over one standalone source file and executes it with normal direct-file
     * semantics. Unreadable or unparsable sources and host failures are thrown, and nothing is left
     * open; guest failure is reported by {@link #initialOutcome()}.
     *
     * @param coreRoot the {@code protos/lib/core} directory of the Protos distribution
     * @param sourceFile the entry source file, read as UTF-8
     * @param applicationArguments the Process {@code args()} snapshot
     * @param in backing stream of the Process standard input
     * @param out backing stream of the Process standard output
     * @param err backing stream of the Process standard error
     */
    public static ProtosStandaloneHostedSession open(
            Path coreRoot,
            Path sourceFile,
            List<String> applicationArguments,
            InputStream in,
            OutputStream out,
            OutputStream err)
            throws IOException {
        return open(
                coreRoot, sourceFile, applicationArguments, in, out, err, NetworkGrant.NONE);
    }

    /**
     * As {@link #open(Path, Path, List, InputStream, OutputStream, OutputStream)}, under the
     * embedder's explicit Network selection (D047/D173); the other overloads select {@link
     * NetworkGrant#NONE}, which leaves the entry module's initial {@code network} slot absent.
     * With {@link NetworkGrant#HOST_NETWORK} the capability is provisioned from this session's
     * application Prelude on this session's own RuntimeHost and is released with it by {@link
     * #close()}.
     *
     * @param networkGrant whether the session's Process receives its initial {@code network}
     */
    public static ProtosStandaloneHostedSession open(
            Path coreRoot,
            Path sourceFile,
            List<String> applicationArguments,
            InputStream in,
            OutputStream out,
            OutputStream err,
            NetworkGrant networkGrant)
            throws IOException {
        Objects.requireNonNull(coreRoot, "coreRoot");
        Objects.requireNonNull(sourceFile, "sourceFile");
        Objects.requireNonNull(applicationArguments, "applicationArguments");
        Objects.requireNonNull(in, "in");
        Objects.requireNonNull(out, "out");
        Objects.requireNonNull(err, "err");
        Objects.requireNonNull(networkGrant, "networkGrant");

        return openSession(
                coreRoot,
                sourceFile,
                List.copyOf(applicationArguments),
                in,
                out,
                err,
                networkGrant,
                ProtosExecutionMode.NORMAL);
    }

    /**
     * Test-only hook: the convenience form on a RuntimeHost opened in the supplied immutable
     * execution mode, without DAP.
     */
    static ProtosStandaloneHostedSession openForTesting(
            Path coreRoot, Path sourceFile, ProtosExecutionMode executionMode)
            throws IOException {
        return openSession(
                Objects.requireNonNull(coreRoot, "coreRoot"),
                Objects.requireNonNull(sourceFile, "sourceFile"),
                List.of(),
                InputStream.nullInputStream(),
                OutputStream.nullOutputStream(),
                OutputStream.nullOutputStream(),
                NetworkGrant.NONE,
                Objects.requireNonNull(executionMode, "executionMode"));
    }

    /** Convenience form: no application arguments, empty stdin, discarded stdout and stderr. */
    public static ProtosStandaloneHostedSession open(Path coreRoot, Path sourceFile)
            throws IOException {
        return open(
                coreRoot,
                sourceFile,
                List.of(),
                InputStream.nullInputStream(),
                OutputStream.nullOutputStream(),
                OutputStream.nullOutputStream());
    }

    private static ProtosStandaloneHostedSession openSession(
            Path coreRoot,
            Path sourceFile,
            List<String> arguments,
            InputStream in,
            OutputStream out,
            OutputStream err,
            NetworkGrant networkGrant,
            ProtosExecutionMode executionMode)
            throws IOException {
        Path sourcePath = sourceFile.toAbsolutePath().normalize();
        String characters = Files.readString(sourcePath, StandardCharsets.UTF_8);
        ProtosDirectFileModuleResolver resolver =
                new ProtosDirectFileModuleResolver(
                        sourcePath,
                        characters,
                        new ProtosStandardLibraryModuleResolver(coreRoot.getParent()));
        ProtosProcessRuntime process = null;
        ProtosPolyglotRuntimeHost runtimeHost = null;
        try {
            /*
             * The RuntimeHost opens before the Process is bootstrapped: a granted Network must be
             * provisioned on the same host that will host this Process, from its exact Prelude.
             */
            runtimeHost =
                    executionMode == ProtosExecutionMode.NORMAL
                            ? ProtosPolyglotRuntimeHost.open()
                            : ProtosPolyglotRuntimeHost.openForTesting(executionMode);
            ProtosStandaloneProcessBootstrap.Result bootstrap =
                    ProtosStandaloneHostedExecution.bootstrapProcess(
                            coreRoot,
                            resolver,
                            arguments,
                            ProtosStandaloneHostedExecution.readableBackend(in),
                            ProtosStandaloneHostedExecution.writableBackend(out),
                            ProtosStandaloneHostedExecution.writableBackend(err),
                            networkGrant,
                            runtimeHost);
            process = bootstrap.process();
            // bindProcess terminates the Process and closes the host itself when binding fails.
            ProtosPolyglotProcessContext processContext =
                    ProtosStandaloneHostedExecution.bindProcess(
                            bootstrap, runtimeHost, in, out, err);
            ProtosExecutionOutcome initial =
                    ProtosStandaloneHostedExecution.executeDirectFile(
                            resolver, bootstrap.activation());

            ProtosObjectValue entryModule = null;
            ProtosActivation entryActivation = null;
            if (initial.state() == ProtosExecutionOutcome.State.COMPLETED) {
                ProtosActivation initialActivation = bootstrap.activation();
                ProtosActorModuleState state = initialActivation.actorModuleState();
                entryModule =
                        state.lookup(resolver.entryModule())
                                .orElseThrow(
                                        () ->
                                                new IllegalStateException(
                                                        "completed entry module is not cached"))
                                .instance();
                ProtosPrelude prelude = initialActivation.prelude().orElseThrow();
                entryActivation =
                        prelude.newModuleActivation(
                                state,
                                resolver.entryModule(),
                                entryModule,
                                initialActivation.executionDomain());
            }
            return new ProtosStandaloneHostedSession(
                    resolver,
                    process,
                    runtimeHost,
                    processContext,
                    initial,
                    entryModule,
                    entryActivation);
        } catch (IOException | RuntimeException | Error failure) {
            Throwable cleanup = shutdown(process, runtimeHost, resolver);
            if (cleanup != null) failure.addSuppressed(cleanup);
            throw failure;
        }
    }

    /** The terminal outcome of executing the entry source when the session was opened. */
    public ProtosExecutionOutcome initialOutcome() {
        return initialOutcome;
    }

    /**
     * Invokes the Closure currently held by the entry module's top-level slot {@code name} with no
     * arguments, inside this session's live Process, and returns its terminal outcome.
     *
     * <p>Guest failure is reported as a {@code FAILED} outcome. The slot is read on each call, so
     * reassignment by the guest is observed exactly as for an in-language call, but nothing is
     * parsed. Throws {@link IllegalStateException} when the entry source did not complete or the
     * session is closed, and {@link IllegalArgumentException} when the slot is absent or does not
     * hold a source-backed Closure.
     */
    public ProtosExecutionOutcome invokeTopLevel(String name) throws IOException {
        Objects.requireNonNull(name, "name");
        gate.lock();
        try {
            return invokeResolved(resolveTopLevelClosure(name));
        } finally {
            gate.unlock();
        }
    }

    /**
     * Resolves the entry module's top-level slot {@code name} once and returns a reusable handle
     * that invokes exactly the Closure selected now, without reading the slot again.
     *
     * <p>Complementary to {@link #invokeTopLevel}: later guest reassignment of the slot is not
     * observed by the returned handle. Nothing is executed or parsed by preparation. Throws the same
     * exceptions as {@link #invokeTopLevel} for an incomplete entry source, a closed session, or an
     * absent or non-source-backed slot.
     */
    public PreparedTopLevel prepareTopLevel(String name) throws IOException {
        Objects.requireNonNull(name, "name");
        gate.lock();
        try {
            ProtosClosureValue closure = resolveTopLevelClosure(name);
            ProtosHostExecutableClosure executable =
                    processContext.prepareHostExecutableForRuntime(closure, entryActivation);
            return new PreparedTopLevel(
                    closure, executable, processContext.asValueForRuntime(executable));
        } finally {
            gate.unlock();
        }
    }

    /**
     * A no-argument top-level Closure resolved by {@link #prepareTopLevel}. It is tied to its owning
     * session: it uses that session's gate, Process, and Polyglot Context, owns no resources of
     * its own, and cannot be invoked once the session is closed.
     */
    public final class PreparedTopLevel {
        private final ProtosClosureValue closure;
        private final ProtosHostExecutableClosure hostExecutable;
        private final Value executable;

        private PreparedTopLevel(
                ProtosClosureValue closure,
                ProtosHostExecutableClosure hostExecutable,
                Value executable) {
            this.closure = closure;
            this.hostExecutable = hostExecutable;
            this.executable = executable;
        }

        /**
         * PERF033-A canonical Polyglot executable for the captured Closure, created once at
         * preparation and bound to this session's live Process Context.
         *
         * <p>{@code executable().execute()} is ordinary activation of exactly the captured
         * Closure, entered through the framework host-to-guest boundary as a synchronous
         * RootActor-local segment that creates no Task: its result is the exact Protos result
         * value and a guest Error surfaces as a guest {@code PolyglotException}. Only zero
         * arguments are accepted. Unlike {@link #invoke()}, it does not take the session gate;
         * the embedder must not execute it concurrently from several threads, nor concurrently
         * with other session operations, because those would be concurrent entries into the same
         * RootActor. After the session is closed the Context is closed and execution fails; the
         * Value does not keep the Process or Context alive.
         */
        public Value executable() {
            return executable;
        }

        ProtosHostExecutableClosure hostExecutableForTesting() {
            return hostExecutable;
        }

        ProtosClosureValue closureForTesting() {
            return closure;
        }

        /**
         * Invokes the captured Closure with no arguments and returns its terminal outcome; guest
         * failure is a {@code FAILED} outcome. Throws {@link IllegalStateException} when the owning
         * session is closed.
         */
        public ProtosExecutionOutcome invoke() throws IOException {
            gate.lock();
            try {
                return invokeResolved(closure);
            } finally {
                gate.unlock();
            }
        }
    }

    /** Gate held: the dynamic slot read and source-backed Closure validation. */
    private ProtosClosureValue resolveTopLevelClosure(String name) {
        requireOpen();
        if (entryModule == null) {
            throw new IllegalStateException(
                    "entry source did not complete; no top-level entries exist");
        }
        Object entry =
                entryModule
                        .readLocalSlot(name)
                        .orElseThrow(
                                () ->
                                        new IllegalArgumentException(
                                                "entry module has no top-level slot " + name));
        if (!(entry instanceof ProtosClosureValue closure) || closure.nativeBody().isPresent()) {
            throw new IllegalArgumentException(
                    "top-level slot " + name + " is not a source-backed Closure");
        }
        return closure;
    }

    /** Gate held: runs a resolved Closure as a fresh RootActor-local task of this Process. */
    private ProtosExecutionOutcome invokeResolved(ProtosClosureValue closure) {
        requireOpen();
        return process.callInExecutionHostForRuntime(
                () -> ProtosRootTaskExecution.executeClosure(closure, List.of(), entryActivation));
    }

    private void requireOpen() {
        if (closeStarted.get()) {
            throw new IllegalStateException("standalone hosted session is closed");
        }
    }

    /**
     * Makes the close cutover visible, so no further guest operation begins, waits for an
     * already-started operation to finish, then requests Process termination, waits for terminal
     * Process completion and Context disposition, and closes the runtime host and module resolver.
     * Idempotent; a failure is thrown after every step has been attempted.
     */
    @Override
    public void close() throws IOException {
        if (!closeStarted.compareAndSet(false, true)) return;
        Throwable failure;
        gate.lock();
        try {
            failure = shutdown(process, runtimeHost, resolver);
        } finally {
            gate.unlock();
        }
        if (failure instanceof RuntimeException runtime) throw runtime;
        if (failure instanceof Error error) throw error;
    }

    /**
     * Attempts every cleanup step in the established order and returns the first failure with the
     * later ones suppressed into it, or null. Null arguments are skipped.
     */
    private static Throwable shutdown(
            ProtosProcessRuntime process,
            ProtosPolyglotRuntimeHost runtimeHost,
            ProtosDirectFileModuleResolver resolver) {
        Throwable[] first = new Throwable[1];
        java.util.function.Consumer<Throwable> record =
                failure -> {
                    if (first[0] == null) first[0] = failure;
                    else first[0].addSuppressed(failure);
                };
        if (process != null) {
            try {
                process.requestTerminationForRuntime();
                process.awaitTerminationForRuntime();
                process.executionHostForRuntime()
                        .ifPresent(host -> host.awaitTerminalDispositionForRuntime());
            } catch (RuntimeException | Error failure) {
                record.accept(failure);
            }
        }
        if (runtimeHost != null) {
            try {
                runtimeHost.close();
            } catch (RuntimeException | Error failure) {
                record.accept(failure);
            }
        }
        try {
            resolver.close();
        } catch (RuntimeException | Error failure) {
            record.accept(failure);
        }
        return first[0];
    }

    ProtosProcessRuntime processForTesting() {
        return process;
    }

    ProtosPolyglotRuntimeHost runtimeHostForTesting() {
        return runtimeHost;
    }

    ProtosPolyglotProcessContext processContextForTesting() {
        return processContext;
    }

    boolean hasQueuedGuestOperationForTesting(Thread thread) {
        return gate.hasQueuedThread(thread);
    }
}
