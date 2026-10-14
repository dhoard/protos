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
import com.guillermomolina.protos.semantic.ast.CanonicalClosure;
import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.source.Source;
import java.util.Objects;
import java.util.Optional;

public final class ProtosClosureExecutionPlan {
    private final ProtosBytecodeClosureExecutionPlan bytecodePlan;

    private ProtosClosureExecutionPlan(
            ProtosBytecodeClosureExecutionPlan bytecodePlan) {
        this.bytecodePlan =
                Objects.requireNonNull(bytecodePlan, "bytecodePlan");
    }

    static ProtosClosureExecutionPlan bytecode(
            CanonicalClosure definition,
            ProtosLanguage language,
            Source source,
            ProtosExecutionMode profile) {
        return new ProtosClosureExecutionPlan(
                new ProtosBytecodeClosureExecutionPlan(
                        definition,
                        language,
                        source,
                        profile));
    }

    static ProtosClosureExecutionPlan bytecode(
            CanonicalClosure definition,
            ProtosLanguage language,
            Source source,
            CanonicalBindingAnalysis bindingAnalysis,
            ProtosExecutionMode profile) {
        return new ProtosClosureExecutionPlan(
                new ProtosBytecodeClosureExecutionPlan(
                        definition,
                        language,
                        source,
                        bindingAnalysis,
                        profile));
    }

    static ProtosClosureExecutionPlan bytecode(
            CanonicalClosure definition,
            ProtosLanguage language,
            Source source,
            ProtosSemanticBytecodeRootNode bodyRoot,
            ProtosExecutionMode profile) {
        return new ProtosClosureExecutionPlan(
                new ProtosBytecodeClosureExecutionPlan(
                        definition,
                        language,
                        source,
                        bodyRoot,
                        profile));
    }

    /**
     * PERF013 Slice A: variant for a Closure root built by the shared-group
     * lowering path, which already proved the exact whole-tree {@code
     * bindingAnalysis} while lowering {@code bodyRoot} and must not have it
     * re-derived in isolation.
     */
    static ProtosClosureExecutionPlan bytecode(
            CanonicalClosure definition,
            ProtosLanguage language,
            Source source,
            CanonicalBindingAnalysis bindingAnalysis,
            ProtosSemanticBytecodeRootNode bodyRoot,
            ProtosExecutionMode profile) {
        return new ProtosClosureExecutionPlan(
                new ProtosBytecodeClosureExecutionPlan(
                        definition,
                        language,
                        source,
                        bindingAnalysis,
                        bodyRoot,
                        profile));
    }

    CanonicalBindingAnalysis bytecodeBindingAnalysisForTesting() {
        return bytecodePlan.bindingAnalysisForTesting();
    }

    Object executeBytecodeActivationForTesting(
            com.guillermomolina.protos.runtime.ProtosActivation activation) {
        return bytecodePlan.executeActivation(activation);
    }

    ProtosSemanticBytecodeRootNode bytecodeActivationRootForTesting() {
        return bytecodePlan.activationRootForTesting();
    }

    RootCallTarget bytecodeActivationTargetForComposition() {
        return bytecodePlan.activationTargetForComposition();
    }

    /**
     * PERF025: true only when the source definition is statically proven to
     * never let a non-local return target the return home an owning
     * invocation establishes, so that home need not be materialized.
     */
    public boolean returnHomeProvenUnobservableForRuntime() {
        return !bytecodePlan.mayObserveReturnHome();
    }

    /**
     * Context-local executable projection of this plan's semantic definition, lowered under the
     * destination Context's immutable {@code profile} (I101-A, PLAT058-D).
     */
    ProtosClosureExecutionPlan rebuildBytecodeForLanguage(
            CanonicalClosure definition,
            ProtosLanguage language,
            ProtosExecutionMode profile) {
        Objects.requireNonNull(definition, "definition");
        Objects.requireNonNull(language, "language");
        Objects.requireNonNull(profile, "profile");
        return new ProtosClosureExecutionPlan(
                bytecodePlan.rebuildForLanguage(definition, language, profile));
    }

    /** I101-A: the physical compilation profile the activation root was lowered under. */
    ProtosExecutionMode bytecodeProfileForTesting() {
        return bytecodePlan.profile();
    }

    Optional<ProtosLanguage> language() {
        return Optional.of(bytecodePlan.language());
    }

    Optional<Source> source() {
        return Optional.of(bytecodePlan.source());
    }

    public void bind(ProtosActivation activation) {
        Objects.requireNonNull(activation, "activation");
        bytecodePlan.bind(activation);
    }

    public Object executeBody(ProtosActivation activation) {
        Objects.requireNonNull(activation, "activation");
        return bytecodePlan.executeActivation(activation);
    }
}
