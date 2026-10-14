/*
 * THE LICENSED WORK IS PROVIDED UNDER THE TERMS OF THE ADAPTIVE PUBLIC LICENSE
 * ("LICENSE") AS FIRST COMPLETED BY: Guillermo Adrián Molina. See LICENSE.TXT.
 */
package com.guillermomolina.protos.execution;

import com.guillermomolina.protos.runtime.ProtosActivation;
import com.guillermomolina.protos.runtime.ProtosActorModuleState;
import com.guillermomolina.protos.runtime.ProtosCoreErrors;
import com.guillermomolina.protos.runtime.ProtosModuleKey;
import com.guillermomolina.protos.runtime.ProtosObjectValue;
import com.guillermomolina.protos.runtime.ProtosPrelude;
import com.guillermomolina.protos.runtime.ProtosProcessRuntime;
import com.guillermomolina.protos.runtime.ProtosSignalException;
import com.guillermomolina.protos.runtime.ProtosStringValue;
import com.guillermomolina.protos.runtime.ProtosTask;
import com.oracle.truffle.api.RootCallTarget;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Core module semantics layered over the host-defined resolver boundary. */
public final class ProtosModuleRuntime {
    private final ProtosModuleResolver resolver;
    private final ProtosSourceCompiler compiler;

    public ProtosModuleRuntime(ProtosModuleResolver resolver) {
        this(resolver, new ProtosSourceCompiler());
    }

    ProtosModuleRuntime(ProtosModuleResolver resolver, ProtosSourceCompiler compiler) {
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        this.compiler = Objects.requireNonNull(compiler, "compiler");
    }

    /**
     * Backend-private PLAT025 lifecycle state for one exact standard import.
     *
     * <p>This is deliberately not a continuation. The child Bytecode root owns
     * all resumable interpreter state through the ordinary C-prime
     * ContinuationResult. This carrier only remembers how to commit or discard
     * the exact Actor-local module record when that child truly terminates.</p>
     */
    static final class PreparedModuleInitialization {
        private final ProtosModuleKey key;
        private final ProtosActorModuleState actorState;
        private final ProtosActorModuleState.ModuleRecord record;
        private final ProtosObjectValue instance;
        private final RootCallTarget bodyTarget;
        private final ProtosActivation activation;
        private final ProtosActivation caller;
        private final boolean ownsInitialization;
        private boolean finalized;

        private PreparedModuleInitialization(
                ProtosModuleKey key,
                ProtosActorModuleState actorState,
                ProtosActorModuleState.ModuleRecord record,
                ProtosObjectValue instance,
                RootCallTarget bodyTarget,
                ProtosActivation activation,
                ProtosActivation caller,
                boolean ownsInitialization) {
            this.key = Objects.requireNonNull(key, "key");
            this.actorState = Objects.requireNonNull(actorState, "actorState");
            this.record = Objects.requireNonNull(record, "record");
            this.instance = Objects.requireNonNull(instance, "instance");
            this.bodyTarget = bodyTarget;
            this.activation = Objects.requireNonNull(activation, "activation");
            this.caller = Objects.requireNonNull(caller, "caller");
            this.ownsInitialization = ownsInitialization;
            if (ownsInitialization != (bodyTarget != null)) {
                throw new IllegalArgumentException(
                        "module lifecycle ownership must match child-root presence");
            }
        }

        static PreparedModuleInitialization cached(
                ProtosModuleKey key,
                ProtosActorModuleState actorState,
                ProtosActorModuleState.ModuleRecord record,
                ProtosActivation caller) {
            return new PreparedModuleInitialization(
                    key,
                    actorState,
                    record,
                    record.instance(),
                    null,
                    caller,
                    caller,
                    false);
        }

        static PreparedModuleInitialization initializing(
                ProtosModuleKey key,
                ProtosActorModuleState actorState,
                ProtosActorModuleState.ModuleRecord record,
                ProtosObjectValue instance,
                RootCallTarget bodyTarget,
                ProtosActivation activation,
                ProtosActivation caller) {
            return new PreparedModuleInitialization(
                    key,
                    actorState,
                    record,
                    instance,
                    Objects.requireNonNull(bodyTarget, "bodyTarget"),
                    activation,
                    caller,
                    true);
        }

        boolean isImmediate() {
            return !ownsInitialization;
        }

        RootCallTarget bodyTarget() {
            return bodyTarget;
        }

        ProtosActivation activation() {
            return activation;
        }

        ProtosObjectValue immediateResult() {
            if (!isImmediate()) {
                throw new IllegalStateException(
                        "initializing module has a child root");
            }
            return instance;
        }

        Object finish(@SuppressWarnings("unused") Object bodyResult) {
            if (!ownsInitialization) {
                return instance;
            }
            if (finalized) {
                throw new IllegalStateException(
                        "module initialization lifecycle already finalized");
            }
            record.markReady();
            finalized = true;
            return instance;
        }

        void fail() {
            if (!ownsInitialization || finalized) {
                return;
            }
            actorState.removeIfSame(key, record);
            finalized = true;
        }

        RuntimeException mapUnexpectedHostFailure(RuntimeException failure) {
            Objects.requireNonNull(failure, "failure");
            if (!ownsInitialization) {
                return failure;
            }
            fail();
            return new ProtosSignalException(ProtosCoreErrors.newError(caller));
        }
    }

    PreparedModuleInitialization prepareBytecodeImport(
            List<?> supplied,
            ProtosActivation caller) {
        return prepareBytecodeImportWithTask(
                supplied,
                caller,
                caller.task().orElse(null));
    }

    PreparedModuleInitialization prepareBytecodeImportForTask(
            List<?> supplied,
            ProtosActivation caller,
            ProtosTask task) {
        Objects.requireNonNull(task, "task");
        return prepareBytecodeImportWithTask(
                supplied,
                caller,
                task);
    }

    /*
     * TEST009-E: import preparation (specifier resolution, Actor module-state lookup/insertion,
     * module activation creation) is host-side work with no partial-evaluation value. Without
     * this boundary the generic send operation's native-method dispatch inlines the resolver
     * and the JDK beneath it into every compiled send site (TooDeepInlining in JDK reflection).
     */
    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    private PreparedModuleInitialization prepareBytecodeImportWithTask(
            List<?> supplied,
            ProtosActivation caller,
            ProtosTask task) {
        Objects.requireNonNull(supplied, "supplied");
        Objects.requireNonNull(caller, "caller");
        if (supplied.size() != 1) {
            throw new ProtosSignalException(ProtosCoreErrors.newError(caller));
        }
        return prepareBytecodeCanonicalModule(
                resolveModuleKey(supplied.get(0), caller),
                caller,
                task);
    }

    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    private RootCallTarget compileUncachedModuleBody(ProtosModuleKey key) throws Exception {
        ProtosModuleSource source =
                Objects.requireNonNull(resolver.loadSource(key), "module source")
                        .requireKey(key);
        ProtosLanguageContext languageContext = ProtosLanguageContext.current();
        return compiler.compileBytecode(
                languageContext.materializeModuleSource(source),
                languageContext.languageForRuntime(),
                languageContext.executionMode());
    }

    private PreparedModuleInitialization prepareBytecodeCanonicalModule(
            ProtosModuleKey key,
            ProtosActivation caller,
            ProtosTask task) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(caller, "caller");

        ProtosActorModuleState actorState = caller.actorModuleState();
        ProtosActorModuleState.ModuleRecord existing =
                actorState.lookup(key).orElse(null);
        if (existing != null) {
            return PreparedModuleInitialization.cached(
                    key, actorState, existing, caller);
        }
        if (ProtosForeignModuleKey.owns(key)) {
            // Foreign initialization is provider acquisition with no Protos child root: it
            // completes (or evicts its record) during preparation, so the result is immediate.
            return PreparedModuleInitialization.cached(
                    key, actorState, initializeForeignModule(key, caller, actorState), caller);
        }

        ProtosPrelude prelude =
                caller.prelude()
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "module import requires an owning Core prelude"));
        ProtosObjectValue moduleInstance = prelude.newExecutionContext();
        prelude.installStandardModuleMembersForRuntime(key, moduleInstance);
        ProtosActorModuleState.ModuleRecord record =
                new ProtosActorModuleState.ModuleRecord(moduleInstance);
        actorState.put(key, record);

        try {
            ProtosActivation moduleActivation =
                    prelude.newModuleActivation(
                            actorState,
                            key,
                            moduleInstance,
                            caller.executionDomain());
            if (task != null) {
                moduleActivation.attachTask(task);
            } else {
                moduleActivation.inheritDynamicControlState(caller);
            }

            RootCallTarget bodyTarget =
                    compileUncachedModuleBody(key);
            return PreparedModuleInitialization.initializing(
                    key,
                    actorState,
                    record,
                    moduleInstance,
                    bodyTarget,
                    moduleActivation,
                    caller);
        } catch (ProtosSignalException signal) {
            actorState.removeIfSame(key, record);
            throw signal;
        } catch (Exception hostOrCompilerFailure) {
            actorState.removeIfSame(key, record);
            throw new ProtosSignalException(ProtosCoreErrors.newError(caller));
        }
    }

    public Object importModule(Object specifier, ProtosActivation caller) {
        return loadCanonicalModule(resolveModuleKey(specifier, caller), caller);
    }

    /** Resolves one exact semantic String in the caller's module-resolution environment. */
    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    public ProtosModuleKey resolveModuleKey(Object specifier, ProtosActivation caller) {
        Objects.requireNonNull(caller, "caller");
        if (!(specifier instanceof ProtosStringValue semanticString)) {
            throw new ProtosSignalException(ProtosCoreErrors.newError(caller));
        }

        try {
            ProtosModuleKey foreign =
                    ProtosForeignModuleImports.resolveOrNull(semanticString.value(), caller);
            if (foreign != null) {
                return foreign;
            }
            ProtosModuleKey key =
                    Objects.requireNonNull(
                            resolver.resolve(semanticString.value(), caller.currentModuleKey()),
                            "module resolver returned null ModuleKey");
            if (ProtosForeignModuleKey.owns(key)) {
                // The foreign key domain is exclusive; a source key can never alias a foreign one.
                throw new IllegalStateException(
                        "source module resolver returned a foreign ModuleKey");
            }
            return key;
        } catch (ProtosSignalException signal) {
            throw signal;
        } catch (Exception hostFailure) {
            throw new ProtosSignalException(ProtosCoreErrors.newError(caller));
        }
    }

    /**
     * Loads one already-canonical module identity in the caller's Actor-local module state.
     *
     * <p>This deliberately bypasses specifier resolution. Actor bootstrap resolves in the creator
     * before the creation cutover and the destination consumes only the resulting ModuleKey.
     */
    public ProtosObjectValue loadCanonicalModule(
            ProtosModuleKey key, ProtosActivation caller) {
        return loadCanonicalModuleInternal(key, caller, Map.of(), false);
    }

    /**
     * PLAT051-A2 destination-local Standard Library access: loads one already-canonical module in
     * {@code caller}'s Actor-local module state through the Process module runtime installed behind
     * the caller Prelude's standard import facility. No global registry is consulted.
     */
    public static ProtosObjectValue loadCanonicalModuleThroughPreludeForRuntime(
            ProtosModuleKey key, ProtosActivation caller) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(caller, "caller");
        ProtosPrelude prelude =
                caller.prelude()
                        .orElseThrow(
                                () -> new IllegalStateException("module loading requires a Core prelude"));
        ProtosModuleRuntime runtime = ProtosStandardImportProtocol.runtimeForPrelude(prelude);
        if (runtime == null) {
            throw new IllegalStateException("Core prelude has no standard import facility");
        }
        return runtime.loadCanonicalModule(key, caller);
    }

    /**
     * Loads the RootActor initial module after installing bootstrap-local slots and before the
     * first source expression executes.
     *
     * <p>This package-private entry is used only by Actor bootstrap. Ordinary imports always use
     * {@link #loadCanonicalModule} and therefore receive no ambient bootstrap locals. The initial
     * record is still inserted before source execution, preserving cache-before-execute and cycles.
     */
    ProtosObjectValue loadCanonicalInitialModule(
            ProtosModuleKey key,
            ProtosActivation caller,
            Map<String, ?> bootstrapLocals) {
        Objects.requireNonNull(bootstrapLocals, "bootstrapLocals");
        if (bootstrapLocals.isEmpty()) {
            throw new IllegalArgumentException(
                    "RootActor initial module requires bootstrap-local authority");
        }
        return loadCanonicalModuleInternal(key, caller, bootstrapLocals, true);
    }

    /**
     * Initializes one foreign module absent from the Actor cache, translating every host or
     * provider failure that crosses the import boundary into an ordinary Core Error.
     */
    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    private static ProtosActorModuleState.ModuleRecord initializeForeignModule(
            ProtosModuleKey key,
            ProtosActivation caller,
            ProtosActorModuleState actorState) {
        try {
            return ProtosForeignModuleImports.initialize(key, caller, actorState);
        } catch (ProtosSignalException signal) {
            throw signal;
        } catch (Exception hostOrProviderFailure) {
            throw new ProtosSignalException(ProtosCoreErrors.newError(caller));
        }
    }

    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    private void installBootstrapLocalsForRuntime(
            ProtosObjectValue moduleInstance,
            Map<String, ?> bootstrapLocals) {
        for (Map.Entry<String, ?> entry : bootstrapLocals.entrySet()) {
            String name = Objects.requireNonNull(entry.getKey(), "bootstrap local name");
            Object value = Objects.requireNonNull(entry.getValue(), "bootstrap local value");
            if (moduleInstance.hasLocalSlot(name)) {
                throw new IllegalStateException(
                        "duplicate RootActor bootstrap local: " + name);
            }
            moduleInstance.createLocalSlot(name, value);
        }
    }

    private ProtosObjectValue loadCanonicalModuleInternal(
            ProtosModuleKey key,
            ProtosActivation caller,
            Map<String, ?> bootstrapLocals,
            boolean initialBootstrap) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(caller, "caller");
        Objects.requireNonNull(bootstrapLocals, "bootstrapLocals");

        ProtosActorModuleState actorState = caller.actorModuleState();
        ProtosActorModuleState.ModuleRecord existing = actorState.lookup(key).orElse(null);
        if (existing != null) {
            if (initialBootstrap) {
                throw new IllegalStateException(
                        "RootActor initial module was cached before bootstrap-local provisioning");
            }
            return existing.instance();
        }
        if (ProtosForeignModuleKey.owns(key)) {
            if (initialBootstrap) {
                throw new IllegalStateException("RootActor initial module must be source-backed");
            }
            return initializeForeignModule(key, caller, actorState).instance();
        }

        ProtosPrelude prelude =
                caller.prelude()
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "module import requires an owning Core prelude"));
        ProtosObjectValue moduleInstance = prelude.newExecutionContext();
        prelude.installStandardModuleMembersForRuntime(key, moduleInstance);

        if (initialBootstrap) {
            installBootstrapLocalsForRuntime(moduleInstance, bootstrapLocals);
        }

        ProtosActorModuleState.ModuleRecord record =
                new ProtosActorModuleState.ModuleRecord(moduleInstance);
        actorState.put(key, record); // normative cache-before-execute point

        try {
            ProtosModuleSource source =
                    Objects.requireNonNull(resolver.loadSource(key), "module source")
                            .requireKey(key);
            ProtosActivation moduleActivation =
                    prelude.newModuleActivation(
                            actorState,
                            key,
                            moduleInstance,
                            caller.executionDomain());
            executeModuleSource(source, moduleActivation);
            record.markReady();
            return moduleInstance;
        } catch (ProtosSignalException signal) {
            actorState.removeIfSame(key, record);
            throw signal;
        } catch (Exception hostOrCompilerFailure) {
            actorState.removeIfSame(key, record);
            throw new ProtosSignalException(ProtosCoreErrors.newError(caller));
        }
    }

    /**
     * Executes one resolved module in the owning Process Context when that Process is hosted.
     *
     * <p>Module identity, Actor-local cache state and cache-before-execute remain entirely outside
     * this implementation placement helper. Process-backed production drivers bind their Process
     * before guest execution and therefore use public parse. Deliberately unhosted/non-Process
     * Java semantic harnesses enter a fresh host-owned Polyglot context and use the same
     * Bytecode-backed public parse path while preserving direct signal propagation.
     */
    private Object executeModuleSource(
            ProtosModuleSource source,
            ProtosActivation activation) {
        ProtosProcessRuntime process =
                activation.executionDomain()
                        .currentActorForRuntime()
                        .flatMap(actor -> actor.processForRuntime())
                        .orElse(null);
        if (process == null || process.executionHostForRuntime().isEmpty()) {
            try (ProtosPolyglotExecutionContext executionContext =
                    ProtosPolyglotExecutionContext.open(
                            InputStream.nullInputStream(),
                            OutputStream.nullOutputStream(),
                            OutputStream.nullOutputStream())) {
                return executionContext.callEntered(
                        () -> {
                            ProtosLanguageContext languageContext =
                                    ProtosLanguageContext.current();
                            return languageContext
                                    .parsePublic(
                                            languageContext.materializeModuleSource(source))
                                    .call(activation);
                        });
            }
        }
        return process.callInExecutionHostForRuntime(
                () -> {
                    ProtosLanguageContext languageContext = ProtosLanguageContext.current();
                    return languageContext
                            .parsePublic(languageContext.materializeModuleSource(source))
                            .call(activation);
                });
    }
}
