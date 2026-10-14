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
import com.guillermomolina.protos.runtime.ProtosArrayValue;
import com.guillermomolina.protos.runtime.ProtosCoreErrors;
import com.guillermomolina.protos.runtime.ProtosPrelude;
import com.guillermomolina.protos.runtime.ProtosSignalException;
import com.guillermomolina.protos.semantic.ast.CanonicalAssign;
import com.guillermomolina.protos.semantic.ast.CanonicalClosure;
import com.guillermomolina.protos.semantic.ast.CanonicalCreate;
import com.guillermomolina.protos.semantic.ast.CanonicalMultipleCreate;
import com.guillermomolina.protos.semantic.ast.CanonicalDerivedInequality;
import com.guillermomolina.protos.semantic.ast.CanonicalExpression;
import com.guillermomolina.protos.semantic.ast.CanonicalIdentity;
import com.guillermomolina.protos.semantic.ast.CanonicalIndexedAssign;
import com.guillermomolina.protos.semantic.ast.CanonicalIntrinsic;
import com.guillermomolina.protos.semantic.ast.CanonicalLiteral;
import com.guillermomolina.protos.semantic.ast.CanonicalLookup;
import com.guillermomolina.protos.semantic.ast.CanonicalMember;
import com.guillermomolina.protos.semantic.ast.CanonicalNotIdentity;
import com.guillermomolina.protos.semantic.ast.CanonicalObject;
import com.guillermomolina.protos.semantic.ast.CanonicalParameter;
import com.guillermomolina.protos.semantic.ast.CanonicalReturn;
import com.guillermomolina.protos.semantic.ast.CanonicalCall;
import com.guillermomolina.protos.semantic.ast.CanonicalSend;
import com.guillermomolina.protos.semantic.ast.CanonicalSuperSend;
import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.source.Source;
import java.util.Objects;

/**
 * Internal Bytecode DSL execution plan for the first Closure migration seam.
 *
 * <p>PERF006-B2D2 keeps one Bytecode Closure activation root and extends
 * call/send composition recursively through argument expressions in both the
 * ordinary body and default-parameter paths. Supplied arguments still suppress
 * defaults, argument evaluation remains left-to-right/exactly-once, and completed
 * argument/default effects are not replayed across Bytecode suspension.</p>
 */
final class ProtosBytecodeClosureExecutionPlan {
    private final CanonicalClosure definition;
    private final ProtosLanguage language;
    private final Source source;
    private final CanonicalBindingAnalysis bindingAnalysis;
    private final ProtosSemanticBytecodeRootNode activationRoot;
    private final RootCallTarget activationTarget;
    /**
     * I101-A (PLAT058-D): the immutable physical compilation profile under which {@link
     * #activationRoot} was lowered; the profile of the Context owning this executable projection.
     */
    private final ProtosExecutionMode profile;
    /** PERF025: see {@link CanonicalReturnHomeAnalysis}; fixed per definition. */
    private final boolean mayObserveReturnHome;

    ProtosBytecodeClosureExecutionPlan(
            CanonicalClosure definition,
            ProtosLanguage language,
            Source source,
            ProtosExecutionMode profile) {
        this(
                definition,
                language,
                source,
                CanonicalBindingAnalyzer.analyzeClosure(
                        Objects.requireNonNull(definition, "definition")),
                profile);
    }

    ProtosBytecodeClosureExecutionPlan(
            CanonicalClosure definition,
            ProtosLanguage language,
            Source source,
            CanonicalBindingAnalysis bindingAnalysis,
            ProtosExecutionMode profile) {
        this(
                definition,
                language,
                source,
                Objects.requireNonNull(bindingAnalysis, "bindingAnalysis"),
                new CanonicalToBytecodeLowerer(
                                Objects.requireNonNull(language, "language"),
                                Objects.requireNonNull(source, "source"),
                                bindingAnalysis,
                                Objects.requireNonNull(profile, "profile"))
                        .lowerClosureActivationRoot(
                                Objects.requireNonNull(
                                        definition,
                                        "definition")),
                profile);
    }

    ProtosBytecodeClosureExecutionPlan(
            CanonicalClosure definition,
            ProtosLanguage language,
            Source source,
            ProtosSemanticBytecodeRootNode activationRoot,
            ProtosExecutionMode profile) {
        this(
                definition,
                language,
                source,
                CanonicalBindingAnalyzer.analyzeClosure(
                        Objects.requireNonNull(definition, "definition")),
                activationRoot,
                profile);
    }

    /**
     * PERF013 Slice A: exposed (widened from {@code private}) so a lexically
     * nested Closure root built by the shared-group lowering path
     * (backend-private grouping metadata proves the exact whole-tree {@link
     * CanonicalBindingAnalysis} at lowering time) can be wrapped without
     * re-deriving that analysis in isolation, which would lose captured
     * owner/depth metadata (see {@link CanonicalToBytecodeLowerer
     * #bindingAnalysisForNestedClosure}). The {@code (definition, language,
     * source, activationRoot)} overload above intentionally keeps its
     * isolated re-analysis for callers (e.g. focused tests) that hand in a
     * standalone root with no outer lexical context to lose.
     */
    ProtosBytecodeClosureExecutionPlan(
            CanonicalClosure definition,
            ProtosLanguage language,
            Source source,
            CanonicalBindingAnalysis bindingAnalysis,
            ProtosSemanticBytecodeRootNode activationRoot,
            ProtosExecutionMode profile) {
        this.definition =
                Objects.requireNonNull(definition, "definition");
        this.profile = Objects.requireNonNull(profile, "profile");
        this.language =
                Objects.requireNonNull(language, "language");
        this.source =
                Objects.requireNonNull(source, "source");
        this.bindingAnalysis =
                Objects.requireNonNull(bindingAnalysis, "bindingAnalysis");

        for (int index = 0; index < definition.parameters().size(); index++) {
            CanonicalParameter parameter = definition.parameters().get(index);
            if (parameter.rest() && index != definition.parameters().size() - 1) {
                throw new IllegalArgumentException("rest parameter must be trailing");
            }
            if (parameter.defaultValue().isPresent()) {
                CanonicalExpression defaultExpression =
                        parameter.defaultValue().orElseThrow();
                if (!(defaultExpression instanceof CanonicalLiteral)
                        && !(defaultExpression instanceof CanonicalClosure)
                        && !(defaultExpression instanceof CanonicalObject)
                        && !(defaultExpression instanceof CanonicalLookup)
                        && !(defaultExpression instanceof CanonicalIntrinsic)
                        && !(defaultExpression instanceof CanonicalMember)
                        && !(defaultExpression instanceof CanonicalIdentity)
                        && !(defaultExpression instanceof CanonicalNotIdentity)
                        && !(defaultExpression instanceof CanonicalDerivedInequality)
                        && !(defaultExpression instanceof CanonicalCall)
                        && !(defaultExpression instanceof CanonicalSend)
                        && !(defaultExpression instanceof CanonicalReturn)
                        && !(defaultExpression instanceof CanonicalCreate)
                        && !(defaultExpression instanceof CanonicalMultipleCreate)
                        && !(defaultExpression instanceof CanonicalAssign)
                        && !(defaultExpression instanceof CanonicalIndexedAssign)
                        && !(defaultExpression instanceof CanonicalSuperSend)) {
                    throw new UnsupportedOperationException(
                            "PERF006-B2C3B3 default expression is not migrated: "
                                    + defaultExpression.getClass().getSimpleName());
                }
            }
        }

        this.activationRoot =
                Objects.requireNonNull(
                        activationRoot,
                        "activationRoot");
        /*
         * PLAT042 B′: the activation root is itself the tagged semantic
         * Closure root, so its CallTarget is entered directly (one CallTarget
         * per ordinary source Closure call).
         */
        this.activationTarget = activationRoot.getCallTarget();
        this.mayObserveReturnHome =
                CanonicalReturnHomeAnalysis.mayObserveReturnHome(definition);
    }

    CanonicalClosure definition() {
        return definition;
    }

    ProtosLanguage language() {
        return language;
    }

    Source source() {
        return source;
    }

    ProtosExecutionMode profile() {
        return profile;
    }

    ProtosBytecodeClosureExecutionPlan rebuildForLanguage(
            CanonicalClosure newDefinition,
            ProtosLanguage newLanguage,
            ProtosExecutionMode newProfile) {
        CanonicalClosure requiredDefinition =
                Objects.requireNonNull(newDefinition, "newDefinition");
        CanonicalBindingAnalysis analysisForDefinition =
                requiredDefinition == definition
                        ? bindingAnalysis
                        : bindingAnalysis.rematerializeForReparsedClosure(
                                requiredDefinition);
        return new ProtosBytecodeClosureExecutionPlan(
                requiredDefinition,
                Objects.requireNonNull(newLanguage, "newLanguage"),
                source,
                analysisForDefinition,
                Objects.requireNonNull(newProfile, "newProfile"));
    }

    CanonicalBindingAnalysis bindingAnalysisForTesting() {
        return bindingAnalysis;
    }

    ProtosSemanticBytecodeRootNode activationRootForTesting() {
        return activationRoot;
    }

    RootCallTarget activationTargetForComposition() {
        return activationTarget;
    }

    boolean mayObserveReturnHome() {
        return mayObserveReturnHome;
    }

    void bind(ProtosActivation activation) {
        bindParameters(definition, activation);
    }

    static void bindParameters(
            CanonicalClosure definition,
            ProtosActivation activation) {
        Objects.requireNonNull(definition, "definition");
        Objects.requireNonNull(activation, "activation");
        java.util.List<CanonicalParameter> parameters = definition.parameters();
        for (int parameterIndex = 0; parameterIndex < parameters.size(); parameterIndex++) {
            CanonicalParameter parameter = parameters.get(parameterIndex);
            if (parameter.defaultValue().isPresent()) {
                throw new UnsupportedOperationException(
                        "PERF006-B2C3B2 direct Java bind helper does not execute default expressions");
            }
        }
        java.util.List<?> supplied =
                activation.suppliedArgumentsForRuntime();
        ProtosPrelude prelude =
                activation.prelude()
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "parameter binding requires an owning Core prelude"));

        int suppliedIndex = 0;
        for (int parameterIndex = 0; parameterIndex < parameters.size(); parameterIndex++) {
            CanonicalParameter parameter = parameters.get(parameterIndex);
            if (parameter.rest()) {
                ProtosArrayValue rest =
                        prelude.newFrozenArray(
                                supplied.subList(
                                        suppliedIndex,
                                        supplied.size()));
                createParameterSlot(
                        activation,
                        parameter.name(),
                        rest);
                suppliedIndex = supplied.size();
                continue;
            }

            if (suppliedIndex >= supplied.size()) {
                throw argumentCountError(activation);
            }

            createParameterSlot(
                    activation,
                    parameter.name(),
                    supplied.get(suppliedIndex));
            suppliedIndex++;
        }

        if (suppliedIndex < supplied.size()) {
            throw argumentCountError(activation);
        }
    }

    private static void createParameterSlot(
            ProtosActivation activation,
            String name,
            Object value) {
        try {
            activation.createCurrentLocalSlotForRuntime(name, value);
        } catch (IllegalStateException invalidCreation) {
            throw new ProtosSignalException(
                    ProtosCoreErrors.newError(activation));
        }
    }

    private static ProtosSignalException argumentCountError(
            ProtosActivation activation) {
        return new ProtosSignalException(
                ProtosCoreErrors.newError(activation));
    }

    /**
     * Execute with the same activation calling convention as every existing
     * Protos AST root: frame argument 0 is the exact invocation activation.
     * PERF032-G6: the entered target is selected exactly as for an ordinary
     * source call, so a Closure declaring no parameters still rejects
     * supplied arguments.
     */
    Object executeActivation(ProtosActivation activation) {
        Objects.requireNonNull(activation, "activation");
        try {
            return ProtosSemanticBytecodeRootNode.selectSourceEntryTarget(
                            activationTarget,
                            activation.suppliedArgumentCountForRuntime())
                    .call(activation);
        } catch (ProtosBytecodeControlTransferException bridged) {
            throw bridged.transfer();
        }
    }
}
