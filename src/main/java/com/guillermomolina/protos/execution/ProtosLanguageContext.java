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

import com.guillermomolina.protos.runtime.ProtosClosureValue;
import com.guillermomolina.protos.semantic.ast.CanonicalClosure;
import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.TruffleFile;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.source.Source;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Host-side state owned by one initialized Polyglot Protos context. */
final class ProtosLanguageContext {
    private static final TruffleLanguage.ContextReference<ProtosLanguageContext> REFERENCE =
            TruffleLanguage.ContextReference.create(ProtosLanguage.class);

    private final ProtosLanguage language;
    private final TruffleLanguage.Env env;
    private final ProtosExecutionMode executionMode;
    /*
     * PLAT035 leaves one Context-local executable projection cache: Bytecode.
     * Semantic Closure identity remains independent from the executable projection.
     */
    private final ConcurrentMap<ProtosClosureExecutionPlan, ProtosClosureExecutionPlan>
            sharedBytecodeExecutionPlans = new ConcurrentHashMap<>();
    private volatile ProtosModuleResolver boundModuleResolver;
    /*
     * PERF033-A: the host wrapper owning this Context, bound once when the wrapper opens. It
     * replaces a per-entry thread-local marker so framework-owned entry (Value.execute) observes
     * the same Context-local platform services as wrapper-owned entry.
     */
    private volatile ProtosPolyglotExecutionContext hostExecutionContext;
    private volatile ProtosTaskCPrimeEntryExecution.Plan taskCPrimeEntryPlan;
    private volatile ProtosTextWriterCPrimeExecution.Plan textWriterCPrimePlan;
    private volatile ProtosTextReaderCPrimeExecution.Plan textReaderCPrimePlan;
    private volatile ProtosBufferedByteReaderCPrimeExecution.Plan bufferedByteReaderCPrimePlan;
    private volatile ProtosBufferedByteWriterCPrimeExecution.Plan bufferedByteWriterCPrimePlan;
    private volatile ProtosIoReleaseCPrimeExecution.Plan ioReleaseCPrimePlan;
    /*
     * PLAT054 standard Polyglot embedding. The scope exists from Context creation but is only a
     * view; the Process is created by the first host evaluation and never replaced afterwards.
     * standardEmbedding marks that this Context's Process is placed by the Context itself rather
     * than by a ProtosPolyglotExecutionContext host wrapper.
     */
    private final ProtosHostBindingsScope hostBindingsScope = new ProtosHostBindingsScope(this);
    private final Object embeddedProcessLock = new Object();
    private volatile boolean standardEmbedding;
    private volatile ProtosEmbeddedProcess embeddedProcess;
    /*
     * PLAT055 application module catalog: immutable, installed at most once, and only before the
     * Process bootstrap begins. Guarded by embeddedProcessLock, which the bootstrap also holds, so
     * installation is atomic with respect to it; null when the host installed no catalog.
     */
    private Map<String, String> applicationModules;

    ProtosLanguageContext(ProtosLanguage language, TruffleLanguage.Env env) {
        this.language = Objects.requireNonNull(language, "language");
        this.env = Objects.requireNonNull(env, "env");
        this.executionMode =
                env.getOptions().get(ProtosLanguage.DEBUG)
                        ? ProtosExecutionMode.DEBUG
                        : ProtosExecutionMode.NORMAL;
    }

    /** PERF032: the immutable execution mode selected when this Context was created. */
    ProtosExecutionMode executionMode() {
        return executionMode;
    }

    ProtosHostBindingsScope hostBindingsScope() {
        return hostBindingsScope;
    }

    ProtosEmbeddedProcess embeddedProcessOrNull() {
        return embeddedProcess;
    }

    boolean isStandardEmbeddingForRuntime() {
        return standardEmbedding;
    }

    /**
     * Returns the embedded Process of this Context, bootstrapping it on the first valid host
     * evaluation. A failed bootstrap leaves no Process, so a later evaluation may bootstrap again;
     * once created, the Process is never recreated, even after it terminates. A Context owned by a
     * Protos host driver (CLI, Test Tool, hosted session) has its Process placed by that driver and
     * rejects standard host evaluation.
     */
    @TruffleBoundary
    ProtosEmbeddedProcess embeddedProcessForHostEntry() {
        ProtosEmbeddedProcess existing = embeddedProcess;
        if (existing != null) {
            return existing;
        }
        synchronized (embeddedProcessLock) {
            if (embeddedProcess == null) {
                if (hostExecutionContext != null) {
                    throw new ProtosEmbeddingException(
                            "this Context hosts a driver-owned Protos Process; standard host"
                                    + " evaluation is not available");
                }
                standardEmbedding = true;
                embeddedProcess = ProtosEmbeddedProcess.bootstrap(this);
            }
            return embeddedProcess;
        }
    }

    /**
     * Installs the application module catalog ({@link ProtosEmbeddedModules}). Bootstrap marks
     * {@code standardEmbedding} under the same lock before it starts, so a catalog is either fully
     * seen by the bootstrap or rejected; a failed bootstrap also closes installation.
     */
    @TruffleBoundary
    void installApplicationModules(Map<String, String> catalog) {
        Objects.requireNonNull(catalog, "catalog");
        synchronized (embeddedProcessLock) {
            synchronized (this) {
                if (applicationModules != null) {
                    throw new IllegalStateException(
                            "application modules are already installed in this Context");
                }
                if (standardEmbedding || embeddedProcess != null) {
                    throw new IllegalStateException(
                            "application modules must be installed before the Protos Process"
                                    + " of this Context starts");
                }
                if (hostExecutionContext != null) {
                    throw new IllegalStateException(
                            "this Context hosts a driver-owned Protos Process; application"
                                    + " modules are not available");
                }
                applicationModules = catalog;
            }
        }
    }

    /** The installed catalog, or {@code null}; read by the bootstrap under embeddedProcessLock. */
    Map<String, String> applicationModulesForBootstrap() {
        return applicationModules;
    }

    void finalizeEmbeddedProcess() {
        ProtosEmbeddedProcess existing = embeddedProcess;
        if (existing != null) {
            existing.finalizeForContextClose();
        }
    }

    void disposeEmbeddedProcess() {
        ProtosEmbeddedProcess existing = embeddedProcess;
        if (existing != null) {
            existing.abandonForContextDisposal();
        }
    }

    static ProtosLanguageContext current() {
        return REFERENCE.get(null);
    }

    static ProtosLanguageContext current(Node node) {
        return REFERENCE.get(node);
    }

    /**
     * Runtime/host acquisition for callers without a Node. TEST009-K: a host boundary so the
     * Polyglot entered-Context probe is not expanded by partial evaluation; Node-owned paths use
     * {@link #current(Node)}.
     */
    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    static ProtosLanguageContext currentIfEnteredForRuntime() {
        try {
            org.graalvm.polyglot.Context.getCurrent();
        } catch (IllegalStateException noEnteredContext) {
            return null;
        }
        return REFERENCE.get(null);
    }

    /**
     * Binds the module resolver private to the Process hosted by this Context; see {@link
     * ProtosContextBoundModuleResolver}. A Context is bound at most once.
     */
    void bindModuleResolverForRuntime(ProtosModuleResolver resolver) {
        Objects.requireNonNull(resolver, "resolver");
        synchronized (this) {
            if (boundModuleResolver != null) {
                throw new IllegalStateException("Process module resolver is already bound");
            }
            boundModuleResolver = resolver;
        }
    }

    void bindHostExecutionContextForRuntime(ProtosPolyglotExecutionContext host) {
        Objects.requireNonNull(host, "host");
        synchronized (this) {
            // A driver-owned Process would silently ignore an installed catalog.
            if (hostExecutionContext != null || standardEmbedding || applicationModules != null) {
                throw new IllegalStateException("host execution context is already bound");
            }
            hostExecutionContext = host;
        }
    }

    ProtosPolyglotExecutionContext hostExecutionContextOrNullForRuntime() {
        return hostExecutionContext;
    }

    ProtosModuleResolver boundModuleResolverForRuntime() {
        return boundModuleResolver;
    }

    ProtosTaskCPrimeEntryExecution.Plan taskCPrimeEntryPlanForRuntime() {
        ProtosTaskCPrimeEntryExecution.Plan existing = taskCPrimeEntryPlan;
        if (existing != null) {
            return existing;
        }
        return createTaskCPrimeEntryPlanOnCacheMiss();
    }

    /** Lazy Context-local plan construction; kept out of partial evaluation on cache miss. */
    @TruffleBoundary
    private ProtosTaskCPrimeEntryExecution.Plan createTaskCPrimeEntryPlanOnCacheMiss() {
        synchronized (this) {
            if (taskCPrimeEntryPlan == null) {
                taskCPrimeEntryPlan = ProtosTaskCPrimeEntryExecution.createPlan(language);
            }
            return taskCPrimeEntryPlan;
        }
    }

    ProtosTextWriterCPrimeExecution.Plan textWriterCPrimePlanForRuntime() {
        ProtosTextWriterCPrimeExecution.Plan existing = textWriterCPrimePlan;
        if (existing != null) {
            return existing;
        }
        return createTextWriterCPrimePlanOnCacheMiss();
    }

    /** Lazy Context-local plan construction; kept out of partial evaluation on cache miss. */
    @TruffleBoundary
    private ProtosTextWriterCPrimeExecution.Plan createTextWriterCPrimePlanOnCacheMiss() {
        synchronized (this) {
            if (textWriterCPrimePlan == null) {
                textWriterCPrimePlan = ProtosTextWriterCPrimeExecution.createPlan(language);
            }
            return textWriterCPrimePlan;
        }
    }

    ProtosTextReaderCPrimeExecution.Plan textReaderCPrimePlanForRuntime() {
        ProtosTextReaderCPrimeExecution.Plan existing = textReaderCPrimePlan;
        if (existing != null) {
            return existing;
        }
        return createTextReaderCPrimePlanOnCacheMiss();
    }

    /** Lazy Context-local plan construction; kept out of partial evaluation on cache miss. */
    @TruffleBoundary
    private ProtosTextReaderCPrimeExecution.Plan createTextReaderCPrimePlanOnCacheMiss() {
        synchronized (this) {
            if (textReaderCPrimePlan == null) {
                textReaderCPrimePlan = ProtosTextReaderCPrimeExecution.createPlan(language);
            }
            return textReaderCPrimePlan;
        }
    }

    ProtosBufferedByteReaderCPrimeExecution.Plan bufferedByteReaderCPrimePlanForRuntime() {
        ProtosBufferedByteReaderCPrimeExecution.Plan existing = bufferedByteReaderCPrimePlan;
        if (existing != null) {
            return existing;
        }
        return createBufferedByteReaderCPrimePlanOnCacheMiss();
    }

    /** Lazy Context-local plan construction; kept out of partial evaluation on cache miss. */
    @TruffleBoundary
    private ProtosBufferedByteReaderCPrimeExecution.Plan createBufferedByteReaderCPrimePlanOnCacheMiss() {
        synchronized (this) {
            if (bufferedByteReaderCPrimePlan == null) {
                bufferedByteReaderCPrimePlan = ProtosBufferedByteReaderCPrimeExecution.createPlan(language);
            }
            return bufferedByteReaderCPrimePlan;
        }
    }

    ProtosBufferedByteWriterCPrimeExecution.Plan bufferedByteWriterCPrimePlanForRuntime() {
        ProtosBufferedByteWriterCPrimeExecution.Plan existing = bufferedByteWriterCPrimePlan;
        if (existing != null) {
            return existing;
        }
        return createBufferedByteWriterCPrimePlanOnCacheMiss();
    }

    /** Lazy Context-local plan construction; kept out of partial evaluation on cache miss. */
    @TruffleBoundary
    private ProtosBufferedByteWriterCPrimeExecution.Plan createBufferedByteWriterCPrimePlanOnCacheMiss() {
        synchronized (this) {
            if (bufferedByteWriterCPrimePlan == null) {
                bufferedByteWriterCPrimePlan = ProtosBufferedByteWriterCPrimeExecution.createPlan(language);
            }
            return bufferedByteWriterCPrimePlan;
        }
    }

    ProtosIoReleaseCPrimeExecution.Plan ioReleaseCPrimePlanForRuntime() {
        ProtosIoReleaseCPrimeExecution.Plan existing = ioReleaseCPrimePlan;
        if (existing != null) {
            return existing;
        }
        return createIoReleaseCPrimePlanOnCacheMiss();
    }

    /** Lazy Context-local plan construction; kept out of partial evaluation on cache miss. */
    @TruffleBoundary
    private ProtosIoReleaseCPrimeExecution.Plan createIoReleaseCPrimePlanOnCacheMiss() {
        synchronized (this) {
            if (ioReleaseCPrimePlan == null) {
                ioReleaseCPrimePlan = ProtosIoReleaseCPrimeExecution.createPlan(language);
            }
            return ioReleaseCPrimePlan;
        }
    }

    ProtosClosureExecutionPlan bytecodeExecutionPlanForEnteredClosure(
            ProtosClosureValue closure, ProtosClosureExecutionPlan template) {
        Objects.requireNonNull(closure, "closure");
        return bytecodeExecutionPlanForDefinition(
                Objects.requireNonNull(
                        closure.definition(),
                        "entered Closure definition"),
                template);
    }

    /**
     * Returns the Context-owned Bytecode projection for one semantic Closure definition/template.
     *
     * <p>PERF006-C3D uses this definition-based form for P transfer so a transferred Closure does
     * not retain the source Closure object merely to recover its immutable canonical definition.
     * The executable projection remains keyed by the semantic template inside this exact
     * ProtosLanguageContext, preserving PLAT001's one-Context-per-hosted-Process ownership while
     * allowing all P carriers of that Process to share the same executable projection.
     */
    ProtosClosureExecutionPlan bytecodeExecutionPlanForDefinition(
            CanonicalClosure definition, ProtosClosureExecutionPlan template) {
        Objects.requireNonNull(definition, "definition");
        Objects.requireNonNull(template, "template");

        ProtosClosureExecutionPlan existing = existingBytecodeExecutionPlan(template);
        if (existing != null) {
            return existing;
        }
        return bytecodeExecutionPlanForDefinitionMiss(definition, template);
    }

    /** TEST009-K: the host ConcurrentHashMap lookup is not part of partial evaluation. */
    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    private ProtosClosureExecutionPlan existingBytecodeExecutionPlan(
            ProtosClosureExecutionPlan template) {
        return sharedBytecodeExecutionPlans.get(template);
    }

    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    private synchronized ProtosClosureExecutionPlan bytecodeExecutionPlanForDefinitionMiss(
            CanonicalClosure definition, ProtosClosureExecutionPlan template) {
        ProtosClosureExecutionPlan existing = sharedBytecodeExecutionPlans.get(template);
        if (existing != null) {
            return existing;
        }

        /*
         * I101-A (PLAT058-D): the projection is lowered under this destination Context's profile,
         * never under the profile of the Context that created the template.
         */
        ProtosClosureExecutionPlan rebuilt =
                template.rebuildBytecodeForLanguage(definition, language, executionMode);
        sharedBytecodeExecutionPlans.put(template, rebuilt);
        return rebuilt;
    }

    int projectedBytecodeExecutionPlanCountForTesting() {
        return sharedBytecodeExecutionPlans.size();
    }

    ProtosLanguage languageForRuntime() {
        return language;
    }

    ProtosLanguage languageForTesting() {
        return languageForRuntime();
    }

    Source materializeModuleSource(ProtosModuleSource source) {
        Objects.requireNonNull(source, "source");
        Path physicalPath = source.physicalPath().orElse(null);
        if (physicalPath != null) {
            return materializeFileSource(physicalPath, source.characters());
        }
        return source.literalSource();
    }

    Source materializeFileSource(Path path, CharSequence characters) {
        Path exact = Objects.requireNonNull(path, "path").toAbsolutePath().normalize();
        Objects.requireNonNull(characters, "characters");
        if (hostExecutionContext == null && standardEmbedding) {
            /*
             * PLAT054: the host owns this Context's I/O policy, which Protos cannot widen. Core and
             * library characters were already read as implementation resources, so the Source is
             * built from them without a TruffleFile and the guest gains no filesystem authority.
             */
            return Source.newBuilder(ProtosLanguage.ID, characters, exact.toString())
                    .uri(exact.toUri())
                    .mimeType(ProtosLanguage.MIME_TYPE)
                    .build();
        }
        ProtosPolyglotExecutionContext.admitPhysicalSourceForRuntime(exact);
        TruffleFile file = env.getPublicTruffleFile(exact.toString());
        return Source.newBuilder(ProtosLanguage.ID, file)
                .canonicalizePath(false)
                .content(characters)
                .mimeType(ProtosLanguage.MIME_TYPE)
                .build();
    }

    CallTarget parsePublic(Source source) {
        Objects.requireNonNull(source, "source");
        if (!ProtosLanguage.ID.equals(source.getLanguage())) {
            throw new IllegalArgumentException("Source belongs to another language");
        }
        CallTarget parsed = env.parsePublic(source);
        // Runtime execution always enters the canonical Bytecode root, never the host eval entry.
        if (parsed instanceof RootCallTarget root
                && root.getRootNode() instanceof ProtosHostEvalRootNode hostEntry) {
            return hostEntry.bytecodeTarget();
        }
        return parsed;
    }

    TruffleLanguage.Env env() {
        return env;
    }
}
