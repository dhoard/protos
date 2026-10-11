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

import com.guillermomolina.protos.runtime.ProtosActorScheduler;
import com.guillermomolina.protos.runtime.ProtosNetworkCapabilityValue;
import com.guillermomolina.protos.runtime.ProtosPrelude;
import com.guillermomolina.protos.runtime.ProtosProcessRuntime;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.URLClassLoader;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.graalvm.polyglot.Engine;

/**
 * Explicit host owner of one shareable Truffle Engine and its Process-scoped Contexts.
 *
 * <p>This object is not a Protos Node or another semantic runtime entity. Its lifetime is selected
 * by the embedding driver; A4B2 deliberately does not make it a global singleton or decide the
 * permanent number of Engines per JVM.
 */
public final class ProtosPolyglotRuntimeHost implements AutoCloseable {
    private final Engine engine;
    /** PERF032: fixed at construction; every Context hosted here inherits it from the Engine. */
    private final ProtosExecutionMode executionMode;
    private final ProtosGraalDapReadinessAdapter debugReadiness;
    private final ProtosForeignProviderRegistry foreignProviders;
    /** Class loader of external provider plugins, or null when none were configured. */
    private final URLClassLoader foreignProviderLoader;
    private final int actorCarrierParallelism;
    private final AtomicInteger activeProcessContexts = new AtomicInteger();
    private final AtomicInteger actorCarrierThreadSequence = new AtomicInteger();
    private final AtomicReference<Throwable> contextCloseFailure = new AtomicReference<>();
    private ExecutorService actorCarrierExecutor;
    private ProtosActorScheduler actorScheduler;
    private ProtosNioNetworkHost networkHost;
    private boolean closed;

    private ProtosPolyglotRuntimeHost(
            Engine engine,
            ProtosGraalDapReadinessAdapter debugReadiness,
            ProtosForeignProviderRegistry foreignProviders) {
        this(engine, debugReadiness, foreignProviders, null);
    }

    private ProtosPolyglotRuntimeHost(
            Engine engine,
            ProtosExecutionMode executionMode,
            ProtosGraalDapReadinessAdapter debugReadiness,
            ProtosForeignProviderRegistry foreignProviders) {
        this(engine, executionMode, debugReadiness, foreignProviders, null);
    }

    private ProtosPolyglotRuntimeHost(
            Engine engine,
            ProtosGraalDapReadinessAdapter debugReadiness,
            ProtosForeignProviderRegistry foreignProviders,
            URLClassLoader foreignProviderLoader) {
        this(
                engine,
                ProtosExecutionMode.NORMAL,
                debugReadiness,
                foreignProviders,
                foreignProviderLoader);
    }

    private ProtosPolyglotRuntimeHost(
            Engine engine,
            ProtosExecutionMode executionMode,
            ProtosGraalDapReadinessAdapter debugReadiness,
            ProtosForeignProviderRegistry foreignProviders,
            URLClassLoader foreignProviderLoader) {
        this.engine = Objects.requireNonNull(engine, "engine");
        this.executionMode = Objects.requireNonNull(executionMode, "executionMode");
        this.debugReadiness = debugReadiness;
        this.foreignProviders = Objects.requireNonNull(foreignProviders, "foreignProviders");
        this.foreignProviderLoader = foreignProviderLoader;
        this.actorCarrierParallelism =
                Math.max(1, Runtime.getRuntime().availableProcessors());
    }

    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    public static ProtosPolyglotRuntimeHost open() {
        return new ProtosPolyglotRuntimeHost(
                Engine.create(ProtosLanguage.ID), null, ProtosForeignProviderRegistry.EMPTY);
    }

    /**
     * Opens a normal host whose only foreign provider is the restricted host-Java provider of the
     * {@code java:} scheme over exactly {@code catalogue} (I082-G). The provider configuration is
     * fixed for the life of the host; {@link #open()} configures no Java authority at all.
     */
    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    public static ProtosPolyglotRuntimeHost openWithHostJava(ProtosHostJavaCatalogue catalogue) {
        return new ProtosPolyglotRuntimeHost(
                Engine.create(ProtosLanguage.ID),
                null,
                ProtosForeignProviderRegistry.of(
                        List.of(ProtosHostJavaProvider.descriptor(catalogue))));
    }

    /**
     * Opens a normal host whose foreign providers are exactly the external plugins discovered on
     * the explicitly configured provider paths (I085-A), fixed for the life of the host. An empty
     * configuration behaves exactly like {@link #open()}: no discovery and no class loader.
     * Plugins are registered without initializing any foreign runtime or session.
     *
     * @throws IllegalArgumentException when a path, plugin, identity, or scheme is invalid or
     *     duplicated
     */
    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    public static ProtosPolyglotRuntimeHost openWithForeignProviders(
            ProtosForeignProviderConfiguration configuration) {
        Objects.requireNonNull(configuration, "configuration");
        if (configuration.providerPaths().isEmpty()) {
            return open();
        }
        ProtosExternalProviderPluginLoader.Loaded loaded =
                ProtosExternalProviderPluginLoader.load(configuration);
        try {
            return new ProtosPolyglotRuntimeHost(
                    Engine.create(ProtosLanguage.ID),
                    null,
                    ProtosForeignProviderRegistry.of(loaded.descriptors()),
                    loaded.classLoader());
        } catch (RuntimeException | Error failure) {
            try {
                loaded.classLoader().close();
            } catch (IOException closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            throw failure;
        }
    }

    /** Test-only hook: opens a normal host owning the supplied immutable provider registry. */
    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    static ProtosPolyglotRuntimeHost openWithForeignProvidersForTesting(
            ProtosForeignProviderRegistry foreignProviders) {
        return new ProtosPolyglotRuntimeHost(
                Engine.create(ProtosLanguage.ID), null, foreignProviders);
    }

    /**
     * Test-only hook: opens a host in the supplied immutable execution mode without starting DAP,
     * so a DEBUG host can be observed through the in-process Truffle debugger.
     */
    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    static ProtosPolyglotRuntimeHost openForTesting(ProtosExecutionMode executionMode) {
        return new ProtosPolyglotRuntimeHost(
                executionModeEngine(Engine.newBuilder(ProtosLanguage.ID), executionMode).build(),
                executionMode,
                null,
                ProtosForeignProviderRegistry.EMPTY);
    }

    /**
     * Selects the execution mode of every Context created on the Engine being built. NORMAL is the
     * option default and is left implicit.
     */
    private static Engine.Builder executionModeEngine(
            Engine.Builder builder, ProtosExecutionMode executionMode) {
        if (executionMode == ProtosExecutionMode.DEBUG) {
            builder.allowExperimentalOptions(true).option(ProtosLanguage.ID + ".Debug", "true");
        }
        return builder;
    }

    /**
     * Opens one PLAT018 debug invocation using the real GraalVM DAP instrument.
     *
     * <p>The debugger binds only IPv4 loopback and asks the operating system for an ephemeral
     * port. Graal-specific startup text is captured by a version-bounded adapter rather than
     * becoming public launcher protocol. Normal {@link #open()} hosts do not enable DAP.
     *
     * <p>PERF032: the host runs in the immutable DEBUG execution mode, so every call transports
     * activation provenance from the first one and a late DAP client needs no reconstruction.
     */
    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    public static ProtosPolyglotRuntimeHost openDebug(OutputStream diagnostics) {
        Objects.requireNonNull(diagnostics, "diagnostics");
        ProtosGraalDapReadinessAdapter readiness =
                new ProtosGraalDapReadinessAdapter(diagnostics);
        Engine engine =
                executionModeEngine(
                                Engine.newBuilder(ProtosLanguage.ID),
                                ProtosExecutionMode.DEBUG)
                        .option("dap", "127.0.0.1:0")
                        .option("dap.Suspend", "false")
                        .option("dap.WaitAttached", "true")
                        .out(readiness)
                        .err(diagnostics)
                        .build();
        return new ProtosPolyglotRuntimeHost(
                engine,
                ProtosExecutionMode.DEBUG,
                readiness,
                ProtosForeignProviderRegistry.EMPTY);
    }

    /**
     * PERF032: whether this host was opened in the immutable DEBUG execution mode. Only {@link
     * #openDebug} selects it; every other factory opens a NORMAL host, which does not support
     * debugging scopes and never starts DAP.
     */
    public boolean isDebugMode() {
        return executionMode == ProtosExecutionMode.DEBUG;
    }

    ProtosExecutionMode executionMode() {
        return executionMode;
    }

    /**
     * Returns the real bound endpoint for this debug RuntimeHost.
     *
     * <p>GraalVM 25.4.4.1.1 publishes the endpoint while the DAP instrument is initialized during
     * Engine startup. Failing rather than guessing here protects D060 from an incompatible future
     * Graal readiness format.
     */
    public DebugEndpoint debugEndpoint() {
        if (debugReadiness == null) {
            throw new IllegalStateException("Polyglot runtime host is not debug-enabled");
        }
        return debugReadiness.requireEndpoint();
    }

    /** Stable implementation-neutral endpoint value consumed by later D060 launcher wiring. */
    public record DebugEndpoint(String host, int port) {
        public DebugEndpoint {
            host = Objects.requireNonNull(host, "host");
            if (host.isBlank()) {
                throw new IllegalArgumentException("debug endpoint host must not be blank");
            }
            if (port <= 0 || port > 65535) {
                throw new IllegalArgumentException("debug endpoint port is outside the TCP range");
            }
        }
    }

    public ProtosPolyglotProcessContext hostProcess(
            ProtosProcessRuntime process,
            InputStream in,
            OutputStream out,
            OutputStream err) {
        Objects.requireNonNull(process, "process");
        Objects.requireNonNull(in, "in");
        Objects.requireNonNull(out, "out");
        Objects.requireNonNull(err, "err");

        synchronized (this) {
            if (closed) {
                throw new IllegalStateException("Polyglot runtime host is closed");
            }
            activeProcessContexts.incrementAndGet();
        }

        ProtosPolyglotExecutionContext context = null;
        try {
            context =
                    ProtosPolyglotExecutionContext.open(
                            engine,
                            in,
                            out,
                            err,
                            activeProcessContexts::decrementAndGet);
            ProtosPolyglotProcessContext hosted =
                    new ProtosPolyglotProcessContext(this, process, context);
            process.bindExecutionHostForRuntime(hosted);
            return hosted;
        } catch (RuntimeException | Error failure) {
            if (context != null) {
                try {
                    context.close();
                } catch (RuntimeException | Error closeFailure) {
                    failure.addSuppressed(closeFailure);
                }
            } else {
                activeProcessContexts.decrementAndGet();
            }
            throw failure;
        }
    }

    /**
     * Returns the one normal-Actor scheduler owned by this RuntimeHost.
     *
     * <p>The fixed executor is created lazily so a host that never schedules a child Actor pays no
     * Actor-carrier thread cost. Its platform threads are shared across every local Process Context
     * hosted here; Process termination never owns this executor's lifetime.
     */
    synchronized ProtosActorScheduler actorSchedulerForRuntime() {
        if (closed) {
            throw new IllegalStateException("Polyglot runtime host is closed");
        }
        if (actorScheduler == null) {
            actorCarrierExecutor =
                    Executors.newFixedThreadPool(
                            actorCarrierParallelism,
                            command -> {
                                Thread carrier =
                                        new Thread(
                                                command,
                                                "protos-actor-carrier-"
                                                        + actorCarrierThreadSequence.incrementAndGet());
                                carrier.setDaemon(true);
                                return carrier;
                            });
            actorScheduler =
                    new ProtosActorScheduler(actorCarrierExecutor, actorCarrierParallelism);
        }
        return actorScheduler;
    }

    boolean actorCarrierSubstrateInitializedForTesting() {
        synchronized (this) {
            return actorScheduler != null;
        }
    }

    int actorCarrierParallelismForTesting() {
        return actorCarrierParallelism;
    }

    /**
     * Explicitly provisions one host Network capability for the supplied Prelude.
     *
     * <p>This operation does not install the capability into a Process or module. The caller owns
     * the existing B3 grant decision. The underlying NIO plane is lazy and shared by every
     * capability explicitly provisioned from this RuntimeHost.
     */
    public synchronized ProtosNetworkCapabilityValue provisionHostNetwork(ProtosPrelude prelude)
            throws IOException {
        Objects.requireNonNull(prelude, "prelude");
        if (closed) {
            throw new IllegalStateException("Polyglot runtime host is closed");
        }
        if (networkHost == null) {
            networkHost = new ProtosNioNetworkHost();
        }
        return networkHost.provision(prelude);
    }

    boolean networkHostInitializedForTesting() {
        synchronized (this) {
            return networkHost != null;
        }
    }

    /** Returns the immutable host-supplied foreign provider registry fixed at construction. */
    ProtosForeignProviderRegistry foreignProvidersForRuntime() {
        return foreignProviders;
    }

    ClassLoader foreignProviderLoaderForTesting() {
        return foreignProviderLoader;
    }

    void recordContextCloseFailure(Throwable failure) {
        contextCloseFailure.compareAndSet(null, Objects.requireNonNull(failure, "failure"));
    }

    Engine engineForTesting() {
        return engine;
    }

    int activeProcessContextCountForTesting() {
        return activeProcessContexts.get();
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        if (activeProcessContexts.get() != 0) {
            throw new IllegalStateException(
                    "Polyglot runtime host cannot close while Process Contexts are active");
        }
        Throwable failure = contextCloseFailure.get();
        if (actorCarrierExecutor != null) {
            actorCarrierExecutor.close();
        }
        if (networkHost != null) {
            networkHost.close();
        }
        if (failure != null) {
            throw new IllegalStateException("A Process Context failed to close", failure);
        }
        engine.close();
        closed = true;
        if (foreignProviderLoader != null) {
            // Every Context that could reference plugin classes is closed by now.
            try {
                foreignProviderLoader.close();
            } catch (IOException closeFailure) {
                throw new UncheckedIOException(closeFailure);
            }
        }
    }
}
