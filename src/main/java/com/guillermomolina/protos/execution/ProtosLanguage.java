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

import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.Option;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.debug.DebuggerTags;
import com.oracle.truffle.api.instrumentation.ProvidedTags;
import com.oracle.truffle.api.instrumentation.StandardTags;
import org.graalvm.options.OptionCategory;
import org.graalvm.options.OptionDescriptors;
import org.graalvm.options.OptionKey;
import org.graalvm.options.OptionStability;

/**
 * Truffle language identity and per-polyglot-context entry boundary for Protos.
 *
 * <p>PLAT054: a standard Polyglot {@code Context.eval} executes a {@link ProtosHostEvalRootNode}
 * that places the module body in the Context's lazily created Protos Process, and {@code
 * Context.getBindings} returns the Context's read-only {@link ProtosHostBindingsScope}.
 */
@ProvidedTags({
    StandardTags.StatementTag.class,
    StandardTags.ExpressionTag.class,
    StandardTags.CallTag.class,
    StandardTags.RootTag.class,
    DebuggerTags.AlwaysHalt.class
})
@TruffleLanguage.Registration(
        id = ProtosLanguage.ID,
        name = "Protos",
        defaultMimeType = ProtosLanguage.MIME_TYPE,
        characterMimeTypes = ProtosLanguage.MIME_TYPE,
        internalResources = ProtosCoreResource.class)
@Option.Group(ProtosLanguage.ID)
public final class ProtosLanguage extends TruffleLanguage<ProtosLanguageContext> {
    public static final String ID = "protos";
    public static final String MIME_TYPE = "application/x-protos";

    @Option(
            name = "CoreRoot",
            help =
                    "Directory of the Protos Core library (protos/lib/core) used by a standard"
                            + " Polyglot embedding. When set it takes precedence over the language"
                            + " home and the Core packaged in the Protos JAR, and must name a Core"
                            + " directory.",
            category = OptionCategory.USER,
            stability = OptionStability.STABLE)
    static final OptionKey<String> CORE_ROOT = new OptionKey<>("");

    /*
     * PERF032: selects the immutable DEBUG execution mode of the Context (or, on an Engine, of
     * every Context it creates). It does not start DAP or any other debugger front end.
     */
    @Option(
            name = "Debug",
            help =
                    "Run this Context in DEBUG execution mode: every call transports activation"
                            + " provenance so debugger scopes are exact from the first call. The"
                            + " default NORMAL mode does not support debugging scopes. Fixed for the"
                            + " life of the Context.",
            category = OptionCategory.USER,
            stability = OptionStability.EXPERIMENTAL)
    static final OptionKey<Boolean> DEBUG = new OptionKey<>(false);

    private final ProtosSourceCompiler sourceCompiler = new ProtosSourceCompiler();

    @Override
    protected ProtosLanguageContext createContext(Env env) {
        return new ProtosLanguageContext(this, env);
    }

    @Override
    protected OptionDescriptors getOptionDescriptors() {
        return new ProtosLanguageOptionDescriptors();
    }

    /**
     * PLAT054 host binding scope. Querying it never creates the Process. A driver-owned Context
     * (CLI, Test Tool, hosted session, debug host) supports no standard host evaluation and keeps
     * having no language top scope, so debugger and tooling observation stay activation-local.
     */
    @Override
    protected Object getScope(ProtosLanguageContext context) {
        return context.hostExecutionContextOrNullForRuntime() == null
                ? context.hostBindingsScope()
                : null;
    }

    /**
     * Context close: terminates the embedded Process, if one was created, while the Context can
     * still be entered (PROCESS_IO.md §28 Process-Control Boundary).
     */
    @Override
    protected void finalizeContext(ProtosLanguageContext context) {
        context.finalizeEmbeddedProcess();
    }

    /**
     * Last close step, reached also when a cancelled or exiting close skips finalization: the
     * embedded Process admits nothing more and its carriers are stopped without waiting.
     */
    @Override
    protected void disposeContext(ProtosLanguageContext context) {
        context.disposeEmbeddedProcess();
    }

    String languageHomeForRuntime() {
        return getLanguageHome();
    }

    /**
     * Protos owns concurrency through its Actor/Task/P runtime rather than through carrier identity.
     * The language/context implementation state reached by Truffle is safe for concurrent external
     * carrier entry after the I026-A4B1 audit; semantic Actor/Task state remains explicit in
     * ProtosActivation and is not stored in a thread local.
     */
    @Override
    protected boolean isThreadAccessAllowed(Thread thread, boolean singleThreaded) {
        return true;
    }

    @Override
    protected CallTarget parse(ParsingRequest request) {
        if (!request.getArgumentNames().isEmpty()) {
            throw new IllegalArgumentException(
                    "Protos top-level parsing does not accept host argument names");
        }
        /*
         * I101-A (PLAT058-D): the ParsingRequest is served for the entered Context, whose
         * immutable profile selects the physical lowering of the parsed executable.
         */
        ProtosExecutionMode profile = ProtosLanguageContext.current().executionMode();
        return new ProtosHostEvalRootNode(
                        this, sourceCompiler.compileBytecode(request.getSource(), this, profile))
                .getCallTarget();
    }
}
