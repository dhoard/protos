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

import com.guillermomolina.protos.runtime.ProtosNullValue;
import com.oracle.truffle.api.bytecode.BytecodeLocal;

/**
 * PLAT042 Candidate B′ structured prepared-invocation lowering.
 *
 * <p>This is the only lowering that targets the untagged {@link
 * ProtosBytecodeRootNode} interpreter. It owns the complete structured
 * dispatcher (while, each, ensure, Error.handle, match/case, import,
 * structured collection callbacks and the other structured prepared calls)
 * used by the Task C-prime entry root, the I/O C-prime roots and nested
 * structured dispatch. Ordinary canonical source lowering targets the tagged
 * {@link ProtosSemanticBytecodeRootNode} interpreter instead and enters this
 * dispatcher through exactly one helper CallTarget per structured invocation
 * (see {@code ProtosSemanticBytecodeRootNode.EnterNestedStructuredDispatch}).
 */
final class ProtosStructuredDispatchLowerer {
    private ProtosStructuredDispatchLowerer() {}

    /**
     * Entry point for infrastructure (C-prime) roots, whose current activation
     * is always frame argument 0.
     */
    static void emitPreparedInvocationForRuntime(
            ProtosBytecodeRootNodeGen.Builder builder,
            BytecodeLocal result,
            BytecodeLocal preparedCall,
            BytecodeLocal childResult,
            BytecodeLocal resumeValue) {
        emitPreparedInvocation(
                builder,
                activationBuilder -> activationBuilder.emitLoadArgument(0),
                result,
                preparedCall,
                childResult,
                resumeValue);
    }

    private static void emitPreparedInvocation(
            ProtosBytecodeRootNodeGen.Builder builder,
            java.util.function.Consumer<ProtosBytecodeRootNodeGen.Builder> emitActivation,
            BytecodeLocal result,
            BytecodeLocal preparedCall,
            BytecodeLocal childResult,
            BytecodeLocal resumeValue) {
        CanonicalToBytecodeLowerer.requireDefaultScratch(
                result,
                preparedCall,
                childResult,
                resumeValue);

        BytecodeLocal structuredMapMatch =
                builder.createLocal("structuredMapMatchCall", null);
        BytecodeLocal structuredMapMatchChild =
                builder.createLocal("structuredMapMatchChildCall", null);
        BytecodeLocal structuredCaseOf =
                builder.createLocal("structuredCaseOfCall", null);
        BytecodeLocal structuredCaseOfMatcher =
                builder.createLocal("structuredCaseOfMatcherCall", null);
        BytecodeLocal structuredCaseOfAction =
                builder.createLocal("structuredCaseOfActionCall", null);
        BytecodeLocal structuredObjectCall =
                builder.createLocal("structuredObjectCall", null);
        BytecodeLocal structuredObjectCallChild =
                builder.createLocal("structuredObjectCallChild", null);
        BytecodeLocal structuredImportCall =
                builder.createLocal("structuredImportCall", null);
        BytecodeLocal structuredImportCallChild =
                builder.createLocal("structuredImportCallChild", null);
        BytecodeLocal structuredEnsure =
                builder.createLocal("structuredEnsureCall", null);
        BytecodeLocal structuredEnsureCancellationWasUnwinding =
                builder.createLocal("structuredEnsureCancellationWasUnwinding", null);
        BytecodeLocal structuredChild =
                builder.createLocal("structuredEnsureChildCall", null);
        BytecodeLocal structuredHandler =
                builder.createLocal("structuredErrorHandlerCall", null);
        BytecodeLocal structuredHandlerChild =
                builder.createLocal("structuredErrorHandlerChildCall", null);
        BytecodeLocal structuredWhile =
                builder.createLocal("structuredWhileCall", null);
        BytecodeLocal structuredWhileChild =
                builder.createLocal("structuredWhileChildCall", null);
        BytecodeLocal structuredWhileConditionResult =
                builder.createLocal("structuredWhileConditionResult", null);
        BytecodeLocal structuredWhileBodyResult =
                builder.createLocal("structuredWhileBodyResult", null);
        BytecodeLocal structuredBoolean =
                builder.createLocal("structuredBooleanCall", null);
        BytecodeLocal structuredBooleanChild =
                builder.createLocal("structuredBooleanChildCall", null);
        BytecodeLocal structuredArrayEach =
                builder.createLocal("structuredArrayEachCall", null);
        BytecodeLocal structuredArrayEachChild =
                builder.createLocal("structuredArrayEachChildCall", null);
        BytecodeLocal structuredArrayMatch =
                builder.createLocal("structuredArrayMatchCall", null);
        BytecodeLocal structuredArrayMatchChild =
                builder.createLocal("structuredArrayMatchChildCall", null);
        BytecodeLocal structuredBytesEach =
                builder.createLocal("structuredBytesEachCall", null);
        BytecodeLocal structuredBytesEachChild =
                builder.createLocal("structuredBytesEachChildCall", null);
        BytecodeLocal structuredEnvironmentEach =
                builder.createLocal("structuredEnvironmentEachCall", null);
        BytecodeLocal structuredEnvironmentEachChild =
                builder.createLocal("structuredEnvironmentEachChildCall", null);
        BytecodeLocal structuredForeignEach =
                builder.createLocal("structuredForeignEachCall", null);
        BytecodeLocal structuredForeignEachChild =
                builder.createLocal("structuredForeignEachChildCall", null);
        BytecodeLocal structuredIdentityMapAtIfAbsent =
                builder.createLocal("structuredIdentityMapAtIfAbsentCall", null);
        BytecodeLocal structuredIdentityMapAtIfAbsentChild =
                builder.createLocal("structuredIdentityMapAtIfAbsentChildCall", null);
        BytecodeLocal structuredIdentityMapEach =
                builder.createLocal("structuredIdentityMapEachCall", null);
        BytecodeLocal structuredIdentityMapEachChild =
                builder.createLocal("structuredIdentityMapEachChildCall", null);
        BytecodeLocal structuredMapEach =
                builder.createLocal("structuredMapEachCall", null);
        BytecodeLocal structuredMapEachChild =
                builder.createLocal("structuredMapEachChildCall", null);
        BytecodeLocal structuredMapReadLookup =
                builder.createLocal("structuredMapReadLookupCall", null);
        BytecodeLocal structuredMapReadLookupChild =
                builder.createLocal("structuredMapReadLookupChildCall", null);
        BytecodeLocal structuredMapAtPut =
                builder.createLocal("structuredMapAtPutCall", null);
        BytecodeLocal structuredMapAtPutChild =
                builder.createLocal("structuredMapAtPutChildCall", null);
        BytecodeLocal structuredMapRemove =
                builder.createLocal("structuredMapRemoveCall", null);
        BytecodeLocal structuredMapRemoveChild =
                builder.createLocal("structuredMapRemoveChildCall", null);

        builder.beginIfThenElse();

        builder.beginIsStructuredMapMatchCall();
        builder.emitLoadLocal(preparedCall);
        builder.endIsStructuredMapMatchCall();

        builder.beginBlock();
        builder.beginTryFinally(
                () -> {
                    builder.beginCompleteClosureCall();
                    builder.emitLoadLocal(preparedCall);
                    builder.endCompleteClosureCall();
                });
        builder.beginBlock();

        builder.beginStoreLocal(structuredMapMatch);
        builder.beginPrepareStructuredMapMatchCall();
        builder.emitLoadLocal(preparedCall);
        builder.endPrepareStructuredMapMatchCall();
        builder.endStoreLocal();

        /*
         * Phase 1: resolve and freeze every required subject association.
         * No nested value matcher is executed before this loop completes.
         */
        builder.beginWhile();
        builder.beginStructuredMapMatchHasRequirement();
        builder.emitLoadLocal(structuredMapMatch);
        builder.endStructuredMapMatchHasRequirement();

        builder.beginBlock();

        /* Hash callback under the subject comparison scope. */
        builder.beginEnterStructuredMapMatchComparison();
        builder.emitLoadLocal(structuredMapMatch);
        builder.endEnterStructuredMapMatchComparison();

        builder.beginTryFinally(
                () -> {
                    builder.beginLeaveStructuredMapMatchComparison();
                    builder.emitLoadLocal(structuredMapMatch);
                    builder.endLeaveStructuredMapMatchComparison();
                });
        builder.beginBlock();

        builder.beginStoreLocal(structuredMapMatchChild);
        builder.beginPrepareStructuredMapMatchHashCall();
        builder.emitLoadLocal(structuredMapMatch);
        builder.endPrepareStructuredMapMatchHashCall();
        builder.endStoreLocal();

        emitScopedPreparedInvocation(
                builder,
                childResult,
                structuredMapMatchChild,
                childResult,
                resumeValue);

        builder.endBlock();
        builder.endTryFinally();

        builder.beginAcceptStructuredMapMatchHashResult();
        builder.emitLoadLocal(structuredMapMatch);
        builder.emitLoadLocal(childResult);
        builder.endAcceptStructuredMapMatchHashResult();

        /* Same-hash candidates are tested in subject insertion order. */
        builder.beginWhile();
        builder.beginStructuredMapMatchNeedsEquality();
        builder.emitLoadLocal(structuredMapMatch);
        builder.endStructuredMapMatchNeedsEquality();

        builder.beginBlock();

        builder.beginEnterStructuredMapMatchComparison();
        builder.emitLoadLocal(structuredMapMatch);
        builder.endEnterStructuredMapMatchComparison();

        builder.beginTryFinally(
                () -> {
                    builder.beginLeaveStructuredMapMatchComparison();
                    builder.emitLoadLocal(structuredMapMatch);
                    builder.endLeaveStructuredMapMatchComparison();
                });
        builder.beginBlock();

        builder.beginStoreLocal(structuredMapMatchChild);
        builder.beginPrepareStructuredMapMatchEqualityCall();
        builder.emitLoadLocal(structuredMapMatch);
        builder.endPrepareStructuredMapMatchEqualityCall();
        builder.endStoreLocal();

        emitScopedPreparedInvocation(
                builder,
                childResult,
                structuredMapMatchChild,
                childResult,
                resumeValue);

        builder.endBlock();
        builder.endTryFinally();

        builder.beginAcceptStructuredMapMatchEqualityResult();
        builder.emitLoadLocal(structuredMapMatch);
        builder.emitLoadLocal(childResult);
        builder.endAcceptStructuredMapMatchEqualityResult();

        builder.endBlock();
        builder.endWhile();

        builder.beginFinishStructuredMapMatchRequirement();
        builder.emitLoadLocal(structuredMapMatch);
        builder.endFinishStructuredMapMatchRequirement();

        builder.endBlock();
        builder.endWhile();

        /*
         * Phase 2: only after all associations are fixed do value matchers run.
         */
        builder.beginWhile();
        builder.beginStructuredMapMatchHasChildMatcher();
        builder.emitLoadLocal(structuredMapMatch);
        builder.endStructuredMapMatchHasChildMatcher();

        builder.beginBlock();

        builder.beginStoreLocal(structuredMapMatchChild);
        builder.beginPrepareStructuredMapMatchChildCall();
        builder.emitLoadLocal(structuredMapMatch);
        builder.endPrepareStructuredMapMatchChildCall();
        builder.endStoreLocal();

        emitScopedPreparedInvocation(
                builder,
                childResult,
                structuredMapMatchChild,
                childResult,
                resumeValue);

        builder.beginAcceptStructuredMapMatchChildOutcome();
        builder.emitLoadLocal(structuredMapMatch);
        builder.emitLoadLocal(childResult);
        builder.endAcceptStructuredMapMatchChildOutcome();

        builder.endBlock();
        builder.endWhile();

        builder.beginStoreLocal(result);
        builder.beginFinishStructuredMapMatch();
        builder.emitLoadLocal(structuredMapMatch);
        builder.endFinishStructuredMapMatch();
        builder.endStoreLocal();

        builder.endBlock();
        builder.endTryFinally();
        builder.endBlock();

        builder.beginBlock();

        builder.beginIfThenElse();

        builder.beginIsStructuredCaseOfCall();
        builder.emitLoadLocal(preparedCall);
        builder.endIsStructuredCaseOfCall();

        builder.beginBlock();
        builder.beginTryFinally(
                () -> {
                    builder.beginCompleteClosureCall();
                    builder.emitLoadLocal(preparedCall);
                    builder.endCompleteClosureCall();
                });
        builder.beginBlock();

        builder.beginStoreLocal(structuredCaseOf);
        builder.beginPrepareStructuredCaseOfCall();
        builder.emitLoadLocal(preparedCall);
        builder.endPrepareStructuredCaseOfCall();
        builder.endStoreLocal();

        builder.beginWhile();
        builder.beginStructuredCaseOfNeedsMatcher();
        builder.emitLoadLocal(structuredCaseOf);
        builder.endStructuredCaseOfNeedsMatcher();

        builder.beginBlock();

        builder.beginStoreLocal(structuredCaseOfMatcher);
        builder.beginPrepareStructuredCaseOfMatcherCall();
        builder.emitLoadLocal(structuredCaseOf);
        builder.endPrepareStructuredCaseOfMatcherCall();
        builder.endStoreLocal();

        emitScopedPreparedInvocation(
                builder,
                childResult,
                structuredCaseOfMatcher,
                childResult,
                resumeValue);

        builder.beginAcceptStructuredCaseOfMatcherOutcome();
        builder.emitLoadLocal(structuredCaseOf);
        builder.emitLoadLocal(childResult);
        builder.endAcceptStructuredCaseOfMatcherOutcome();

        builder.endBlock();
        builder.endWhile();

        builder.beginStoreLocal(structuredCaseOfAction);
        builder.beginPrepareStructuredCaseOfActionCall();
        builder.emitLoadLocal(structuredCaseOf);
        builder.endPrepareStructuredCaseOfActionCall();
        builder.endStoreLocal();

        emitScopedPreparedInvocation(
                builder,
                result,
                structuredCaseOfAction,
                childResult,
                resumeValue);

        builder.endBlock();
        builder.endTryFinally();
        builder.endBlock();

        builder.beginBlock();

        builder.beginIfThenElse();

        builder.beginIsStructuredArrayMatchCall();
        builder.emitLoadLocal(preparedCall);
        builder.endIsStructuredArrayMatchCall();

        builder.beginBlock();
        builder.beginTryFinally(
                () -> {
                    builder.beginCompleteClosureCall();
                    builder.emitLoadLocal(preparedCall);
                    builder.endCompleteClosureCall();
                });
        builder.beginBlock();

        builder.beginStoreLocal(structuredArrayMatch);
        builder.beginPrepareStructuredArrayMatchCall();
        builder.emitLoadLocal(preparedCall);
        builder.endPrepareStructuredArrayMatchCall();
        builder.endStoreLocal();

        builder.beginWhile();
        builder.beginStructuredArrayMatchHasNext();
        builder.emitLoadLocal(structuredArrayMatch);
        builder.endStructuredArrayMatchHasNext();

        builder.beginBlock();

        builder.beginStoreLocal(structuredArrayMatchChild);
        builder.beginPrepareStructuredArrayMatchElementCall();
        builder.emitLoadLocal(structuredArrayMatch);
        builder.endPrepareStructuredArrayMatchElementCall();
        builder.endStoreLocal();

        emitScopedPreparedInvocation(
                builder,
                childResult,
                structuredArrayMatchChild,
                childResult,
                resumeValue);

        builder.beginAcceptStructuredArrayMatchOutcome();
        builder.emitLoadLocal(structuredArrayMatch);
        builder.emitLoadLocal(childResult);
        builder.endAcceptStructuredArrayMatchOutcome();

        builder.endBlock();
        builder.endWhile();

        builder.beginStoreLocal(result);
        builder.beginFinishStructuredArrayMatch();
        builder.emitLoadLocal(structuredArrayMatch);
        builder.endFinishStructuredArrayMatch();
        builder.endStoreLocal();

        builder.endBlock();
        builder.endTryFinally();
        builder.endBlock();

        builder.beginBlock();

        builder.beginIfThenElse();

        builder.beginIsStructuredObjectCall();
        builder.emitLoadLocal(preparedCall);
        builder.endIsStructuredObjectCall();

        builder.beginBlock();
        builder.beginTryFinally(
                () -> {
                    builder.beginCompleteClosureCall();
                    builder.emitLoadLocal(preparedCall);
                    builder.endCompleteClosureCall();
                });
        builder.beginBlock();

        builder.beginStoreLocal(structuredObjectCall);
        builder.beginPrepareStructuredObjectCall();
        builder.emitLoadLocal(preparedCall);
        builder.endPrepareStructuredObjectCall();
        builder.endStoreLocal();

        builder.beginStoreLocal(structuredObjectCallChild);
        builder.beginLoadStructuredObjectCallChild();
        builder.emitLoadLocal(structuredObjectCall);
        builder.endLoadStructuredObjectCallChild();
        builder.endStoreLocal();

        emitScopedPreparedInvocation(
                builder,
                childResult,
                structuredObjectCallChild,
                childResult,
                resumeValue);

        builder.beginStoreLocal(result);
        builder.beginFinishStructuredObjectCall();
        builder.emitLoadLocal(structuredObjectCall);
        builder.emitLoadLocal(childResult);
        builder.endFinishStructuredObjectCall();
        builder.endStoreLocal();

        builder.endBlock();
        builder.endTryFinally();
        builder.endBlock();

        builder.beginBlock();
        builder.beginIfThenElse();

        builder.beginIsStructuredImportCall();
        builder.emitLoadLocal(preparedCall);
        builder.endIsStructuredImportCall();

        builder.beginBlock();
        builder.beginTryFinally(
                () -> {
                    builder.beginCompleteClosureCall();
                    builder.emitLoadLocal(preparedCall);
                    builder.endCompleteClosureCall();
                });
        builder.beginBlock();

        builder.beginStoreLocal(structuredImportCall);
        builder.beginPrepareStructuredImportCall();
        builder.emitLoadLocal(preparedCall);
        builder.endPrepareStructuredImportCall();
        builder.endStoreLocal();

        builder.beginStoreLocal(structuredImportCallChild);
        builder.beginLoadStructuredImportCallChild();
        builder.emitLoadLocal(structuredImportCall);
        builder.endLoadStructuredImportCallChild();
        builder.endStoreLocal();

        emitScopedPreparedInvocation(
                builder,
                childResult,
                structuredImportCallChild,
                childResult,
                resumeValue);

        builder.beginStoreLocal(result);
        builder.beginFinishStructuredImportCall();
        builder.emitLoadLocal(structuredImportCall);
        builder.emitLoadLocal(childResult);
        builder.endFinishStructuredImportCall();
        builder.endStoreLocal();

        builder.endBlock();
        builder.endTryFinally();
        builder.endBlock();

        builder.beginBlock();
        builder.beginIfThenElse();

        builder.beginIsStructuredEnsureCall();
        builder.emitLoadLocal(preparedCall);
        builder.endIsStructuredEnsureCall();

        builder.beginBlock();
        builder.beginTryFinally(
                () -> {
                    builder.beginCompleteClosureCall();
                    builder.emitLoadLocal(preparedCall);
                    builder.endCompleteClosureCall();
                });
        builder.beginBlock();

        /* Validation precedes the protected semantic extent. */
        builder.beginStoreLocal(structuredEnsure);
        builder.beginPrepareStructuredEnsureCall();
        builder.emitLoadLocal(preparedCall);
        builder.endPrepareStructuredEnsureCall();
        builder.endStoreLocal();

        builder.beginTryFinally(
                () -> {
                    /*
                     * Snapshot cancellation ownership before cleanup runs.
                     * A cancellation initiated by cleanup is itself the later
                     * transfer and must supersede the body's pending
                     * error/return/normal outcome. Only cancellation that was
                     * already UNWINDING on entry to cleanup may be superseded
                     * by a still-later cleanup transfer.
                     */
                    builder.beginStoreLocal(
                            structuredEnsureCancellationWasUnwinding);
                    builder.beginIsCancellationUnwindActive();
                    emitActivation.accept(builder);
                    builder.endIsCancellationUnwindActive();
                    builder.endStoreLocal();

                    /*
                     * TryCatch is deliberately inside the generated finally.
                     * It sees only a later transfer escaping cleanup; the
                     * original pending transfer is rethrown by the outer
                     * TryFinally after this generator returns.
                     */
                    builder.beginTryCatch();

                    builder.beginBlock();
                    builder.beginStoreLocal(structuredChild);
                    builder.beginLoadStructuredEnsureCleanupCall();
                    builder.emitLoadLocal(structuredEnsure);
                    builder.endLoadStructuredEnsureCleanupCall();
                    builder.endStoreLocal();
                    emitScopedPreparedInvocation(
                            builder,
                            childResult,
                            structuredChild,
                            childResult,
                            resumeValue);
                    builder.endBlock();

                    builder.beginBlock();
                    builder.beginSupersedeCancellationUnwindIfActive();
                    emitActivation.accept(builder);
                    builder.emitLoadLocal(
                            structuredEnsureCancellationWasUnwinding);
                    builder.endSupersedeCancellationUnwindIfActive();
                    builder.beginRethrowTruffleException();
                    builder.emitLoadException();
                    builder.endRethrowTruffleException();
                    builder.endBlock();

                    builder.endTryCatch();
                });
        builder.beginBlock();
        builder.beginStoreLocal(structuredChild);
        builder.beginLoadStructuredEnsureBodyCall();
        builder.emitLoadLocal(structuredEnsure);
        builder.endLoadStructuredEnsureBodyCall();
        builder.endStoreLocal();
        emitScopedPreparedInvocation(
                builder,
                result,
                structuredChild,
                childResult,
                resumeValue);
        builder.endBlock();
        builder.endTryFinally();

        builder.endBlock();
        builder.endTryFinally();
        builder.endBlock();

        builder.beginBlock();
        builder.beginIfThenElse();

        builder.beginIsStructuredErrorHandlerCall();
        builder.emitLoadLocal(preparedCall);
        builder.endIsStructuredErrorHandlerCall();

        builder.beginBlock();
        builder.beginTryFinally(
                () -> {
                    builder.beginCompleteClosureCall();
                    builder.emitLoadLocal(preparedCall);
                    builder.endCompleteClosureCall();
                });
        builder.beginBlock();

        /*
         * Receiver/arity/body/handler validation happens before frame
         * installation. The returned descriptor owns exactly one Task/direct
         * dynamic handler token for the protected extent.
         */
        builder.beginStoreLocal(structuredHandler);
        builder.beginPrepareStructuredErrorHandlerCall();
        builder.emitLoadLocal(preparedCall);
        builder.endPrepareStructuredErrorHandlerCall();
        builder.endStoreLocal();

        builder.beginTryFinally(
                () -> {
                    builder.beginLeaveStructuredErrorHandlerFrame();
                    builder.emitLoadLocal(structuredHandler);
                    builder.endLeaveStructuredErrorHandlerFrame();
                });
        builder.beginBlock();

        /*
         * Bytecode DSL TryCatch is a void operation. Each branch writes the
         * semantic Error.handle result directly into the shared result local;
         * the TryCatch itself must not be used as a value-producing child.
         */
        builder.beginTryCatch();

        builder.beginBlock();
        builder.beginStoreLocal(structuredHandlerChild);
        builder.beginLoadStructuredErrorHandlerBodyCall();
        builder.emitLoadLocal(structuredHandler);
        builder.endLoadStructuredErrorHandlerBodyCall();
        builder.endStoreLocal();
        emitScopedPreparedInvocation(
                builder,
                result,
                structuredHandlerChild,
                childResult,
                resumeValue);
        builder.endBlock();

        builder.beginBlock();
        builder.beginStoreLocal(structuredHandlerChild);
        builder.beginPrepareSelectedStructuredErrorHandlerCall();
        builder.emitLoadLocal(structuredHandler);
        builder.emitLoadException();
        builder.endPrepareSelectedStructuredErrorHandlerCall();
        builder.endStoreLocal();
        emitScopedPreparedInvocation(
                builder,
                result,
                structuredHandlerChild,
                childResult,
                resumeValue);
        builder.endBlock();

        builder.endTryCatch();

        builder.endBlock();
        builder.endTryFinally();

        builder.endBlock();
        builder.endTryFinally();
        builder.endBlock();

        builder.beginBlock();
        builder.beginIfThenElse();

        builder.beginIsStructuredWhileCall();
        builder.emitLoadLocal(preparedCall);
        builder.endIsStructuredWhileCall();

        builder.beginBlock();
        builder.beginTryFinally(
                () -> {
                    builder.beginCompleteClosureCall();
                    builder.emitLoadLocal(preparedCall);
                    builder.endCompleteClosureCall();
                });
        builder.beginBlock();

        /*
         * Validate the standard whileTrue/while receiver/body before the first
         * condition activation.
         */
        builder.beginStoreLocal(structuredWhile);
        builder.beginPrepareStructuredWhileCall();
        builder.emitLoadLocal(preparedCall);
        builder.endPrepareStructuredWhileCall();
        builder.endStoreLocal();

        /*
         * D201: the last normal body result for whileTrue. It starts as
         * canonical null (zero iterations) and is overwritten only by a body
         * invocation that completes normally; an abnormal condition/body exit
         * propagates without reaching the result store below. For while each
         * body result is replaced by null right after the body completes, so
         * the terminal condition (even while suspended) keeps no body object
         * reachable through this local.
         */
        builder.beginStoreLocal(structuredWhileBodyResult);
        builder.emitLoadConstant(ProtosNullValue.INSTANCE);
        builder.endStoreLocal();

        /*
         * PLAT021: loop phase lives in Bytecode control state. Every logical
         * condition/body activation is prepared fresh so each invocation owns
         * its own activation/ReturnHome; suspension resumes at the exact loop
         * PC without a replay checkpoint or callback compaction cursor.
         */
        builder.beginWhile();

        builder.beginBlock();
        builder.beginStoreLocal(structuredWhileChild);
        builder.beginPrepareStructuredWhileConditionCall();
        builder.emitLoadLocal(structuredWhile);
        builder.endPrepareStructuredWhileConditionCall();
        builder.endStoreLocal();
        emitScopedPreparedInvocation(
                builder,
                structuredWhileConditionResult,
                structuredWhileChild,
                childResult,
                resumeValue);
        builder.beginStructuredWhileCondition();
        builder.emitLoadLocal(structuredWhile);
        builder.emitLoadLocal(structuredWhileConditionResult);
        builder.endStructuredWhileCondition();
        builder.endBlock();

        builder.beginBlock();
        builder.beginStoreLocal(structuredWhileChild);
        builder.beginPrepareStructuredWhileBodyCall();
        builder.emitLoadLocal(structuredWhile);
        builder.endPrepareStructuredWhileBodyCall();
        builder.endStoreLocal();
        emitScopedPreparedInvocation(
                builder,
                structuredWhileBodyResult,
                structuredWhileChild,
                childResult,
                resumeValue);
        builder.beginStoreLocal(structuredWhileBodyResult);
        builder.beginStructuredWhileRetainBodyResult();
        builder.emitLoadLocal(structuredWhile);
        builder.emitLoadLocal(structuredWhileBodyResult);
        builder.endStructuredWhileRetainBodyResult();
        builder.endStoreLocal();
        builder.endBlock();

        builder.endWhile();

        builder.beginStoreLocal(result);
        builder.beginStructuredWhileResult();
        builder.emitLoadLocal(structuredWhile);
        builder.emitLoadLocal(structuredWhileBodyResult);
        builder.endStructuredWhileResult();
        builder.endStoreLocal();

        builder.endBlock();
        builder.endTryFinally();
        builder.endBlock();

        builder.beginBlock();
        builder.beginIfThenElse();

        builder.beginIsStructuredBooleanCall();
        builder.emitLoadLocal(preparedCall);
        builder.endIsStructuredBooleanCall();

        builder.beginBlock();
        builder.beginTryFinally(
                () -> {
                    builder.beginCompleteClosureCall();
                    builder.emitLoadLocal(preparedCall);
                    builder.endCompleteClosureCall();
                });
        builder.beginBlock();

        builder.beginStoreLocal(structuredBoolean);
        builder.beginPrepareStructuredBooleanCall();
        builder.emitLoadLocal(preparedCall);
        builder.endPrepareStructuredBooleanCall();
        builder.endStoreLocal();

        builder.beginIfThenElse();
        builder.beginStructuredBooleanHasCallback();
        builder.emitLoadLocal(structuredBoolean);
        builder.endStructuredBooleanHasCallback();

        builder.beginBlock();
        builder.beginStoreLocal(structuredBooleanChild);
        builder.beginPrepareStructuredBooleanCallbackCall();
        builder.emitLoadLocal(structuredBoolean);
        builder.endPrepareStructuredBooleanCallbackCall();
        builder.endStoreLocal();
        emitScopedPreparedInvocation(
                builder,
                childResult,
                structuredBooleanChild,
                childResult,
                resumeValue);
        builder.beginStoreLocal(result);
        builder.beginFinishStructuredBooleanCallback();
        builder.emitLoadLocal(structuredBoolean);
        builder.emitLoadLocal(childResult);
        builder.endFinishStructuredBooleanCallback();
        builder.endStoreLocal();
        builder.endBlock();

        builder.beginBlock();
        builder.beginStoreLocal(result);
        builder.beginStructuredBooleanImmediateResult();
        builder.emitLoadLocal(structuredBoolean);
        builder.endStructuredBooleanImmediateResult();
        builder.endStoreLocal();
        builder.endBlock();

        builder.endIfThenElse();
        builder.endBlock();
        builder.endTryFinally();
        builder.endBlock();

        builder.beginBlock();
        builder.beginIfThenElse();

        builder.beginIsStructuredArrayEachCall();
        builder.emitLoadLocal(preparedCall);
        builder.endIsStructuredArrayEachCall();

        builder.beginBlock();
        builder.beginTryFinally(
                () -> {
                    builder.beginCompleteClosureCall();
                    builder.emitLoadLocal(preparedCall);
                    builder.endCompleteClosureCall();
                });
        builder.beginBlock();

        builder.beginStoreLocal(structuredArrayEach);
        builder.beginPrepareStructuredArrayEachCall();
        builder.emitLoadLocal(preparedCall);
        builder.endPrepareStructuredArrayEachCall();
        builder.endStoreLocal();

        builder.beginWhile();
        builder.beginStructuredArrayEachHasNext();
        builder.emitLoadLocal(structuredArrayEach);
        builder.endStructuredArrayEachHasNext();

        builder.beginBlock();
        builder.beginStoreLocal(structuredArrayEachChild);
        builder.beginPrepareStructuredArrayEachElementCall();
        builder.emitLoadLocal(structuredArrayEach);
        builder.endPrepareStructuredArrayEachElementCall();
        builder.endStoreLocal();
        emitScopedPreparedInvocation(
                builder,
                childResult,
                structuredArrayEachChild,
                childResult,
                resumeValue);
        builder.beginAdvanceStructuredArrayEach();
        builder.emitLoadLocal(structuredArrayEach);
        builder.endAdvanceStructuredArrayEach();
        builder.endBlock();

        builder.endWhile();

        builder.beginStoreLocal(result);
        builder.beginFinishStructuredArrayEach();
        builder.emitLoadLocal(structuredArrayEach);
        builder.endFinishStructuredArrayEach();
        builder.endStoreLocal();

        builder.endBlock();
        builder.endTryFinally();
        builder.endBlock();

        builder.beginBlock();
        builder.beginIfThenElse();

        builder.beginIsStructuredBytesEachCall();
        builder.emitLoadLocal(preparedCall);
        builder.endIsStructuredBytesEachCall();

        builder.beginBlock();
        builder.beginTryFinally(
                () -> {
                    builder.beginCompleteClosureCall();
                    builder.emitLoadLocal(preparedCall);
                    builder.endCompleteClosureCall();
                });
        builder.beginBlock();

        builder.beginStoreLocal(structuredBytesEach);
        builder.beginPrepareStructuredBytesEachCall();
        builder.emitLoadLocal(preparedCall);
        builder.endPrepareStructuredBytesEachCall();
        builder.endStoreLocal();

        builder.beginWhile();
        builder.beginStructuredBytesEachHasNext();
        builder.emitLoadLocal(structuredBytesEach);
        builder.endStructuredBytesEachHasNext();

        builder.beginBlock();
        builder.beginStoreLocal(structuredBytesEachChild);
        builder.beginPrepareStructuredBytesEachElementCall();
        builder.emitLoadLocal(structuredBytesEach);
        builder.endPrepareStructuredBytesEachElementCall();
        builder.endStoreLocal();
        emitScopedPreparedInvocation(
                builder,
                childResult,
                structuredBytesEachChild,
                childResult,
                resumeValue);
        builder.beginAdvanceStructuredBytesEach();
        builder.emitLoadLocal(structuredBytesEach);
        builder.endAdvanceStructuredBytesEach();
        builder.endBlock();

        builder.endWhile();

        builder.beginStoreLocal(result);
        builder.beginFinishStructuredBytesEach();
        builder.emitLoadLocal(structuredBytesEach);
        builder.endFinishStructuredBytesEach();
        builder.endStoreLocal();

        builder.endBlock();
        builder.endTryFinally();
        builder.endBlock();

        builder.beginBlock();
        builder.beginIfThenElse();

        builder.beginIsStructuredEnvironmentEachCall();
        builder.emitLoadLocal(preparedCall);
        builder.endIsStructuredEnvironmentEachCall();

        builder.beginBlock();
        builder.beginTryFinally(
                () -> {
                    builder.beginCompleteClosureCall();
                    builder.emitLoadLocal(preparedCall);
                    builder.endCompleteClosureCall();
                });
        builder.beginBlock();

        builder.beginStoreLocal(structuredEnvironmentEach);
        builder.beginPrepareStructuredEnvironmentEachCall();
        builder.emitLoadLocal(preparedCall);
        builder.endPrepareStructuredEnvironmentEachCall();
        builder.endStoreLocal();

        builder.beginWhile();
        builder.beginStructuredEnvironmentEachHasNext();
        builder.emitLoadLocal(structuredEnvironmentEach);
        builder.endStructuredEnvironmentEachHasNext();

        builder.beginBlock();
        builder.beginStoreLocal(structuredEnvironmentEachChild);
        builder.beginPrepareStructuredEnvironmentEachEntryCall();
        builder.emitLoadLocal(structuredEnvironmentEach);
        builder.endPrepareStructuredEnvironmentEachEntryCall();
        builder.endStoreLocal();
        emitScopedPreparedInvocation(
                builder,
                childResult,
                structuredEnvironmentEachChild,
                childResult,
                resumeValue);
        builder.beginAdvanceStructuredEnvironmentEach();
        builder.emitLoadLocal(structuredEnvironmentEach);
        builder.endAdvanceStructuredEnvironmentEach();
        builder.endBlock();

        builder.endWhile();

        builder.beginStoreLocal(result);
        builder.beginFinishStructuredEnvironmentEach();
        builder.emitLoadLocal(structuredEnvironmentEach);
        builder.endFinishStructuredEnvironmentEach();
        builder.endStoreLocal();

        builder.endBlock();
        builder.endTryFinally();
        builder.endBlock();

        builder.beginBlock();
        builder.beginIfThenElse();

        /*
         * D188 projected foreign each: a Protos-side pull loop. Prevalidation happens in the
         * prepare step; each iteration asks the provider for has-next, pulls and admits one
         * element, and invokes the block as an ordinary scoped child, so suspension resumes the
         * same visit and an Error or non-local exit leaves no later pull.
         */
        builder.beginIsStructuredForeignEachCall();
        builder.emitLoadLocal(preparedCall);
        builder.endIsStructuredForeignEachCall();

        builder.beginBlock();
        builder.beginTryFinally(
                () -> {
                    builder.beginCompleteClosureCall();
                    builder.emitLoadLocal(preparedCall);
                    builder.endCompleteClosureCall();
                });
        builder.beginBlock();

        builder.beginStoreLocal(structuredForeignEach);
        builder.beginPrepareStructuredForeignEachCall();
        builder.emitLoadLocal(preparedCall);
        builder.endPrepareStructuredForeignEachCall();
        builder.endStoreLocal();

        builder.beginWhile();
        builder.beginStructuredForeignEachHasNext();
        builder.emitLoadLocal(structuredForeignEach);
        builder.endStructuredForeignEachHasNext();

        builder.beginBlock();
        builder.beginStoreLocal(structuredForeignEachChild);
        builder.beginPrepareStructuredForeignEachElementCall();
        builder.emitLoadLocal(structuredForeignEach);
        builder.endPrepareStructuredForeignEachElementCall();
        builder.endStoreLocal();
        emitScopedPreparedInvocation(
                builder,
                childResult,
                structuredForeignEachChild,
                childResult,
                resumeValue);
        builder.beginAdvanceStructuredForeignEach();
        builder.emitLoadLocal(structuredForeignEach);
        builder.endAdvanceStructuredForeignEach();
        builder.endBlock();

        builder.endWhile();

        builder.beginStoreLocal(result);
        builder.beginFinishStructuredForeignEach();
        builder.emitLoadLocal(structuredForeignEach);
        builder.endFinishStructuredForeignEach();
        builder.endStoreLocal();

        builder.endBlock();
        builder.endTryFinally();
        builder.endBlock();

        builder.beginBlock();
        builder.beginIfThenElse();

        builder.beginIsStructuredIdentityMapAtIfAbsentCall();
        builder.emitLoadLocal(preparedCall);
        builder.endIsStructuredIdentityMapAtIfAbsentCall();

        builder.beginBlock();
        builder.beginTryFinally(
                () -> {
                    builder.beginCompleteClosureCall();
                    builder.emitLoadLocal(preparedCall);
                    builder.endCompleteClosureCall();
                });
        builder.beginBlock();

        builder.beginStoreLocal(structuredIdentityMapAtIfAbsent);
        builder.beginPrepareStructuredIdentityMapAtIfAbsentCall();
        builder.emitLoadLocal(preparedCall);
        builder.endPrepareStructuredIdentityMapAtIfAbsentCall();
        builder.endStoreLocal();

        builder.beginIfThenElse();

        builder.beginStructuredIdentityMapAtIfAbsentNeedsFallback();
        builder.emitLoadLocal(structuredIdentityMapAtIfAbsent);
        builder.endStructuredIdentityMapAtIfAbsentNeedsFallback();

        builder.beginBlock();

        builder.beginStoreLocal(structuredIdentityMapAtIfAbsentChild);
        builder.beginPrepareStructuredIdentityMapAtIfAbsentFallbackCall();
        builder.emitLoadLocal(structuredIdentityMapAtIfAbsent);
        builder.endPrepareStructuredIdentityMapAtIfAbsentFallbackCall();
        builder.endStoreLocal();

        emitScopedPreparedInvocation(
                builder,
                childResult,
                structuredIdentityMapAtIfAbsentChild,
                childResult,
                resumeValue);

        builder.beginStoreLocal(result);
        builder.beginFinishStructuredIdentityMapAtIfAbsentFallback();
        builder.emitLoadLocal(structuredIdentityMapAtIfAbsent);
        builder.emitLoadLocal(childResult);
        builder.endFinishStructuredIdentityMapAtIfAbsentFallback();
        builder.endStoreLocal();

        builder.endBlock();

        builder.beginBlock();

        builder.beginStoreLocal(result);
        builder.beginFinishStructuredIdentityMapAtIfAbsentPresent();
        builder.emitLoadLocal(structuredIdentityMapAtIfAbsent);
        builder.endFinishStructuredIdentityMapAtIfAbsentPresent();
        builder.endStoreLocal();

        builder.endBlock();

        builder.endIfThenElse();

        builder.endBlock();
        builder.endTryFinally();
        builder.endBlock();

        builder.beginBlock();
        builder.beginIfThenElse();

        builder.beginIsStructuredIdentityMapEachCall();
        builder.emitLoadLocal(preparedCall);
        builder.endIsStructuredIdentityMapEachCall();

        builder.beginBlock();
        builder.beginTryFinally(
                () -> {
                    builder.beginCompleteClosureCall();
                    builder.emitLoadLocal(preparedCall);
                    builder.endCompleteClosureCall();
                });
        builder.beginBlock();

        builder.beginStoreLocal(structuredIdentityMapEach);
        builder.beginPrepareStructuredIdentityMapEachCall();
        builder.emitLoadLocal(preparedCall);
        builder.endPrepareStructuredIdentityMapEachCall();
        builder.endStoreLocal();

        builder.beginWhile();
        builder.beginStructuredIdentityMapEachHasNext();
        builder.emitLoadLocal(structuredIdentityMapEach);
        builder.endStructuredIdentityMapEachHasNext();

        builder.beginBlock();
        builder.beginStoreLocal(structuredIdentityMapEachChild);
        builder.beginPrepareStructuredIdentityMapEachEntryCall();
        builder.emitLoadLocal(structuredIdentityMapEach);
        builder.endPrepareStructuredIdentityMapEachEntryCall();
        builder.endStoreLocal();
        emitScopedPreparedInvocation(
                builder,
                childResult,
                structuredIdentityMapEachChild,
                childResult,
                resumeValue);
        builder.beginAdvanceStructuredIdentityMapEach();
        builder.emitLoadLocal(structuredIdentityMapEach);
        builder.endAdvanceStructuredIdentityMapEach();
        builder.endBlock();

        builder.endWhile();

        builder.beginStoreLocal(result);
        builder.beginFinishStructuredIdentityMapEach();
        builder.emitLoadLocal(structuredIdentityMapEach);
        builder.endFinishStructuredIdentityMapEach();
        builder.endStoreLocal();

        builder.endBlock();
        builder.endTryFinally();
        builder.endBlock();

        builder.beginBlock();
        builder.beginIfThenElse();

        builder.beginIsStructuredMapEachCall();
        builder.emitLoadLocal(preparedCall);
        builder.endIsStructuredMapEachCall();

        builder.beginBlock();
        builder.beginTryFinally(
                () -> {
                    builder.beginCompleteClosureCall();
                    builder.emitLoadLocal(preparedCall);
                    builder.endCompleteClosureCall();
                });
        builder.beginBlock();

        builder.beginStoreLocal(structuredMapEach);
        builder.beginPrepareStructuredMapEachCall();
        builder.emitLoadLocal(preparedCall);
        builder.endPrepareStructuredMapEachCall();
        builder.endStoreLocal();

        builder.beginWhile();
        builder.beginStructuredMapEachHasNext();
        builder.emitLoadLocal(structuredMapEach);
        builder.endStructuredMapEachHasNext();

        builder.beginBlock();
        builder.beginStoreLocal(structuredMapEachChild);
        builder.beginPrepareStructuredMapEachEntryCall();
        builder.emitLoadLocal(structuredMapEach);
        builder.endPrepareStructuredMapEachEntryCall();
        builder.endStoreLocal();
        emitScopedPreparedInvocation(
                builder,
                childResult,
                structuredMapEachChild,
                childResult,
                resumeValue);
        builder.beginAdvanceStructuredMapEach();
        builder.emitLoadLocal(structuredMapEach);
        builder.endAdvanceStructuredMapEach();
        builder.endBlock();

        builder.endWhile();

        builder.beginStoreLocal(result);
        builder.beginFinishStructuredMapEach();
        builder.emitLoadLocal(structuredMapEach);
        builder.endFinishStructuredMapEach();
        builder.endStoreLocal();

        builder.endBlock();
        builder.endTryFinally();
        builder.endBlock();

        builder.beginBlock();
        builder.beginIfThenElse();

        builder.beginIsStructuredMapReadLookupCall();
        builder.emitLoadLocal(preparedCall);
        builder.endIsStructuredMapReadLookupCall();

        builder.beginBlock();
        builder.beginTryFinally(
                () -> {
                    builder.beginCompleteClosureCall();
                    builder.emitLoadLocal(preparedCall);
                    builder.endCompleteClosureCall();
                });
        builder.beginBlock();

        builder.beginStoreLocal(structuredMapReadLookup);
        builder.beginPrepareStructuredMapReadLookupCall();
        builder.emitLoadLocal(preparedCall);
        builder.endPrepareStructuredMapReadLookupCall();
        builder.endStoreLocal();

        /* Hash callback: comparison scope spans suspension but not result validation. */
        builder.beginEnterStructuredMapReadLookupComparison();
        builder.emitLoadLocal(structuredMapReadLookup);
        builder.endEnterStructuredMapReadLookupComparison();
        builder.beginTryFinally(
                () -> {
                    builder.beginLeaveStructuredMapReadLookupComparison();
                    builder.emitLoadLocal(structuredMapReadLookup);
                    builder.endLeaveStructuredMapReadLookupComparison();
                });
        builder.beginBlock();
        builder.beginStoreLocal(structuredMapReadLookupChild);
        builder.beginPrepareStructuredMapReadLookupHashCall();
        builder.emitLoadLocal(structuredMapReadLookup);
        builder.endPrepareStructuredMapReadLookupHashCall();
        builder.endStoreLocal();
        emitScopedPreparedInvocation(
                builder,
                childResult,
                structuredMapReadLookupChild,
                childResult,
                resumeValue);
        builder.endBlock();
        builder.endTryFinally();

        builder.beginAcceptStructuredMapReadLookupHashResult();
        builder.emitLoadLocal(structuredMapReadLookup);
        builder.emitLoadLocal(childResult);
        builder.endAcceptStructuredMapReadLookupHashResult();

        /* Candidate equality callbacks repeat in insertion order over the hash-filtered snapshot. */
        builder.beginWhile();
        builder.beginStructuredMapReadLookupNeedsEquality();
        builder.emitLoadLocal(structuredMapReadLookup);
        builder.endStructuredMapReadLookupNeedsEquality();

        builder.beginBlock();
        builder.beginEnterStructuredMapReadLookupComparison();
        builder.emitLoadLocal(structuredMapReadLookup);
        builder.endEnterStructuredMapReadLookupComparison();
        builder.beginTryFinally(
                () -> {
                    builder.beginLeaveStructuredMapReadLookupComparison();
                    builder.emitLoadLocal(structuredMapReadLookup);
                    builder.endLeaveStructuredMapReadLookupComparison();
                });
        builder.beginBlock();
        builder.beginStoreLocal(structuredMapReadLookupChild);
        builder.beginPrepareStructuredMapReadLookupEqualityCall();
        builder.emitLoadLocal(structuredMapReadLookup);
        builder.endPrepareStructuredMapReadLookupEqualityCall();
        builder.endStoreLocal();
        emitScopedPreparedInvocation(
                builder,
                childResult,
                structuredMapReadLookupChild,
                childResult,
                resumeValue);
        builder.endBlock();
        builder.endTryFinally();

        builder.beginAcceptStructuredMapReadLookupEqualityResult();
        builder.emitLoadLocal(structuredMapReadLookup);
        builder.emitLoadLocal(childResult);
        builder.endAcceptStructuredMapReadLookupEqualityResult();
        builder.endBlock();

        builder.endWhile();

        builder.beginIfThenElse();

        builder.beginStructuredMapReadLookupNeedsFallback();
        builder.emitLoadLocal(structuredMapReadLookup);
        builder.endStructuredMapReadLookupNeedsFallback();

        builder.beginBlock();

        builder.beginStoreLocal(structuredMapReadLookupChild);
        builder.beginPrepareStructuredMapReadLookupFallbackCall();
        builder.emitLoadLocal(structuredMapReadLookup);
        builder.endPrepareStructuredMapReadLookupFallbackCall();
        builder.endStoreLocal();

        emitScopedPreparedInvocation(
                builder,
                childResult,
                structuredMapReadLookupChild,
                childResult,
                resumeValue);

        builder.beginStoreLocal(result);
        builder.beginFinishStructuredMapReadLookupFallback();
        builder.emitLoadLocal(structuredMapReadLookup);
        builder.emitLoadLocal(childResult);
        builder.endFinishStructuredMapReadLookupFallback();
        builder.endStoreLocal();

        builder.endBlock();

        builder.beginBlock();

        builder.beginStoreLocal(result);
        builder.beginFinishStructuredMapReadLookup();
        builder.emitLoadLocal(structuredMapReadLookup);
        builder.endFinishStructuredMapReadLookup();
        builder.endStoreLocal();

        builder.endBlock();

        builder.endIfThenElse();

        builder.endBlock();
        builder.endTryFinally();
        builder.endBlock();

        builder.beginBlock();
        builder.beginIfThenElse();

        builder.beginIsStructuredMapAtPutCall();
        builder.emitLoadLocal(preparedCall);
        builder.endIsStructuredMapAtPutCall();

        builder.beginBlock();
        builder.beginTryFinally(
                () -> {
                    builder.beginCompleteClosureCall();
                    builder.emitLoadLocal(preparedCall);
                    builder.endCompleteClosureCall();
                });
        builder.beginBlock();

        builder.beginStoreLocal(structuredMapAtPut);
        builder.beginPrepareStructuredMapAtPutCall();
        builder.emitLoadLocal(preparedCall);
        builder.endPrepareStructuredMapAtPutCall();
        builder.endStoreLocal();

        builder.beginEnterStructuredMapAtPutComparison();
        builder.emitLoadLocal(structuredMapAtPut);
        builder.endEnterStructuredMapAtPutComparison();
        builder.beginTryFinally(
                () -> {
                    builder.beginLeaveStructuredMapAtPutComparison();
                    builder.emitLoadLocal(structuredMapAtPut);
                    builder.endLeaveStructuredMapAtPutComparison();
                });
        builder.beginBlock();
        builder.beginStoreLocal(structuredMapAtPutChild);
        builder.beginPrepareStructuredMapAtPutHashCall();
        builder.emitLoadLocal(structuredMapAtPut);
        builder.endPrepareStructuredMapAtPutHashCall();
        builder.endStoreLocal();
        emitScopedPreparedInvocation(
                builder,
                childResult,
                structuredMapAtPutChild,
                childResult,
                resumeValue);
        builder.endBlock();
        builder.endTryFinally();

        builder.beginAcceptStructuredMapAtPutHashResult();
        builder.emitLoadLocal(structuredMapAtPut);
        builder.emitLoadLocal(childResult);
        builder.endAcceptStructuredMapAtPutHashResult();

        builder.beginWhile();
        builder.beginStructuredMapAtPutNeedsEquality();
        builder.emitLoadLocal(structuredMapAtPut);
        builder.endStructuredMapAtPutNeedsEquality();

        builder.beginBlock();
        builder.beginEnterStructuredMapAtPutComparison();
        builder.emitLoadLocal(structuredMapAtPut);
        builder.endEnterStructuredMapAtPutComparison();
        builder.beginTryFinally(
                () -> {
                    builder.beginLeaveStructuredMapAtPutComparison();
                    builder.emitLoadLocal(structuredMapAtPut);
                    builder.endLeaveStructuredMapAtPutComparison();
                });
        builder.beginBlock();
        builder.beginStoreLocal(structuredMapAtPutChild);
        builder.beginPrepareStructuredMapAtPutEqualityCall();
        builder.emitLoadLocal(structuredMapAtPut);
        builder.endPrepareStructuredMapAtPutEqualityCall();
        builder.endStoreLocal();
        emitScopedPreparedInvocation(
                builder,
                childResult,
                structuredMapAtPutChild,
                childResult,
                resumeValue);
        builder.endBlock();
        builder.endTryFinally();

        builder.beginAcceptStructuredMapAtPutEqualityResult();
        builder.emitLoadLocal(structuredMapAtPut);
        builder.emitLoadLocal(childResult);
        builder.endAcceptStructuredMapAtPutEqualityResult();
        builder.endBlock();

        builder.endWhile();

        builder.beginStoreLocal(result);
        builder.beginFinishStructuredMapAtPut();
        builder.emitLoadLocal(structuredMapAtPut);
        builder.endFinishStructuredMapAtPut();
        builder.endStoreLocal();

        builder.endBlock();
        builder.endTryFinally();
        builder.endBlock();

        builder.beginBlock();
        builder.beginIfThenElse();

        builder.beginIsStructuredMapRemoveCall();
        builder.emitLoadLocal(preparedCall);
        builder.endIsStructuredMapRemoveCall();

        builder.beginBlock();
        builder.beginTryFinally(
                () -> {
                    builder.beginCompleteClosureCall();
                    builder.emitLoadLocal(preparedCall);
                    builder.endCompleteClosureCall();
                });
        builder.beginBlock();

        builder.beginStoreLocal(structuredMapRemove);
        builder.beginPrepareStructuredMapRemoveCall();
        builder.emitLoadLocal(preparedCall);
        builder.endPrepareStructuredMapRemoveCall();
        builder.endStoreLocal();

        builder.beginEnterStructuredMapRemoveComparison();
        builder.emitLoadLocal(structuredMapRemove);
        builder.endEnterStructuredMapRemoveComparison();
        builder.beginTryFinally(
                () -> {
                    builder.beginLeaveStructuredMapRemoveComparison();
                    builder.emitLoadLocal(structuredMapRemove);
                    builder.endLeaveStructuredMapRemoveComparison();
                });
        builder.beginBlock();
        builder.beginStoreLocal(structuredMapRemoveChild);
        builder.beginPrepareStructuredMapRemoveHashCall();
        builder.emitLoadLocal(structuredMapRemove);
        builder.endPrepareStructuredMapRemoveHashCall();
        builder.endStoreLocal();
        emitScopedPreparedInvocation(
                builder,
                childResult,
                structuredMapRemoveChild,
                childResult,
                resumeValue);
        builder.endBlock();
        builder.endTryFinally();

        builder.beginAcceptStructuredMapRemoveHashResult();
        builder.emitLoadLocal(structuredMapRemove);
        builder.emitLoadLocal(childResult);
        builder.endAcceptStructuredMapRemoveHashResult();

        builder.beginWhile();
        builder.beginStructuredMapRemoveNeedsEquality();
        builder.emitLoadLocal(structuredMapRemove);
        builder.endStructuredMapRemoveNeedsEquality();

        builder.beginBlock();
        builder.beginEnterStructuredMapRemoveComparison();
        builder.emitLoadLocal(structuredMapRemove);
        builder.endEnterStructuredMapRemoveComparison();
        builder.beginTryFinally(
                () -> {
                    builder.beginLeaveStructuredMapRemoveComparison();
                    builder.emitLoadLocal(structuredMapRemove);
                    builder.endLeaveStructuredMapRemoveComparison();
                });
        builder.beginBlock();
        builder.beginStoreLocal(structuredMapRemoveChild);
        builder.beginPrepareStructuredMapRemoveEqualityCall();
        builder.emitLoadLocal(structuredMapRemove);
        builder.endPrepareStructuredMapRemoveEqualityCall();
        builder.endStoreLocal();
        emitScopedPreparedInvocation(
                builder,
                childResult,
                structuredMapRemoveChild,
                childResult,
                resumeValue);
        builder.endBlock();
        builder.endTryFinally();

        builder.beginAcceptStructuredMapRemoveEqualityResult();
        builder.emitLoadLocal(structuredMapRemove);
        builder.emitLoadLocal(childResult);
        builder.endAcceptStructuredMapRemoveEqualityResult();
        builder.endBlock();

        builder.endWhile();

        builder.beginStoreLocal(result);
        builder.beginFinishStructuredMapRemove();
        builder.emitLoadLocal(structuredMapRemove);
        builder.endFinishStructuredMapRemove();
        builder.endStoreLocal();

        builder.endBlock();
        builder.endTryFinally();
        builder.endBlock();

        builder.beginBlock();
        emitOrdinaryPreparedInvocation(
                builder,
                result,
                preparedCall,
                childResult,
                resumeValue);
        builder.endBlock();

        builder.endIfThenElse();
        builder.endBlock();

        builder.endIfThenElse();
        builder.endBlock();

        builder.endIfThenElse();
        builder.endBlock();

        builder.endIfThenElse();
        builder.endBlock();

        builder.endIfThenElse();
        builder.endBlock();

        builder.endIfThenElse();
        builder.endBlock();

        builder.endIfThenElse();
        builder.endBlock();

        builder.endIfThenElse();
        builder.endBlock();

        builder.endIfThenElse();
        builder.endBlock();

        builder.endIfThenElse();
        builder.endBlock();

        builder.endIfThenElse();
        builder.endBlock();

        builder.endIfThenElse();
        builder.endBlock();

        builder.endIfThenElse();
        builder.endBlock();

        builder.endIfThenElse();
        builder.endBlock();

        builder.endIfThenElse();
        builder.endBlock();

        builder.endIfThenElse();
        builder.endBlock();

        builder.endIfThenElse();
        builder.endBlock();

        builder.endIfThenElse();
        builder.endBlock();

        builder.endIfThenElse();
    }

    private static void emitScopedPreparedInvocation(
            ProtosBytecodeRootNodeGen.Builder builder,
            BytecodeLocal result,
            BytecodeLocal preparedCall,
            BytecodeLocal childResult,
            BytecodeLocal resumeValue) {
        builder.beginIfThenElse();

        builder.beginRequiresStructuredDispatch();
        builder.emitLoadLocal(preparedCall);
        builder.endRequiresStructuredDispatch();

        builder.beginBlock();
        builder.beginStoreLocal(childResult);
        builder.beginEnterNestedStructuredDispatch();
        builder.emitLoadLocal(preparedCall);
        builder.endEnterNestedStructuredDispatch();
        builder.endStoreLocal();

        builder.beginWhile();
        builder.beginIsContinuation();
        builder.emitLoadLocal(childResult);
        builder.endIsContinuation();

        builder.beginBlock();
        builder.beginStoreLocal(resumeValue);
        builder.beginYield();
        builder.emitLoadLocal(childResult);
        builder.endYield();
        builder.endStoreLocal();

        builder.beginStoreLocal(childResult);
        builder.beginResumeContinuation();
        builder.emitLoadLocal(preparedCall);
        builder.emitLoadLocal(childResult);
        builder.emitLoadLocal(resumeValue);
        builder.endResumeContinuation();
        builder.endStoreLocal();
        builder.endBlock();

        builder.endWhile();

        builder.beginStoreLocal(result);
        builder.emitLoadLocal(childResult);
        builder.endStoreLocal();
        builder.endBlock();

        builder.beginBlock();
        builder.beginTryFinally(
                () -> {
                    builder.beginCompleteClosureCall();
                    builder.emitLoadLocal(preparedCall);
                    builder.endCompleteClosureCall();
                });
        builder.beginBlock();
        emitOrdinaryPreparedInvocation(
                builder,
                result,
                preparedCall,
                childResult,
                resumeValue);
        builder.endBlock();
        builder.endTryFinally();
        builder.endBlock();

        builder.endIfThenElse();
    }

    private static void emitOrdinaryPreparedInvocation(
            ProtosBytecodeRootNodeGen.Builder builder,
            BytecodeLocal result,
            BytecodeLocal preparedCall,
            BytecodeLocal childResult,
            BytecodeLocal resumeValue) {
        builder.beginStoreLocal(childResult);
        builder.beginEnterClosureCall();
        builder.emitLoadLocal(preparedCall);
        builder.endEnterClosureCall();
        builder.endStoreLocal();

        builder.beginWhile();
        builder.beginIsContinuation();
        builder.emitLoadLocal(childResult);
        builder.endIsContinuation();
        builder.beginBlock();
        builder.beginStoreLocal(resumeValue);
        builder.beginYield();
        builder.emitLoadLocal(childResult);
        builder.endYield();
        builder.endStoreLocal();
        builder.beginStoreLocal(childResult);
        builder.beginResumeContinuation();
        builder.emitLoadLocal(preparedCall);
        builder.emitLoadLocal(childResult);
        builder.emitLoadLocal(resumeValue);
        builder.endResumeContinuation();
        builder.endStoreLocal();
        builder.endBlock();
        builder.endWhile();

        builder.beginStoreLocal(result);
        builder.beginFinishClosureCall();
        builder.emitLoadLocal(preparedCall);
        builder.emitLoadLocal(childResult);
        builder.endFinishClosureCall();
        builder.endStoreLocal();
    }
}
