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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.guillermomolina.protos.parser.ProtosParser;
import com.guillermomolina.protos.runtime.ProtosActivation;
import com.guillermomolina.protos.runtime.ProtosActorExecutionDomain;
import com.guillermomolina.protos.runtime.ProtosActorModuleState;
import com.guillermomolina.protos.runtime.ProtosClosureValue;
import com.guillermomolina.protos.runtime.ProtosCoreErrors;
import com.guillermomolina.protos.runtime.ProtosFutureValue;
import com.guillermomolina.protos.runtime.ProtosIntegerValue;
import com.guillermomolina.protos.runtime.ProtosNullValue;
import com.guillermomolina.protos.runtime.ProtosObjectValue;
import com.guillermomolina.protos.runtime.ProtosPrelude;
import com.guillermomolina.protos.runtime.ProtosSignalException;
import com.guillermomolina.protos.runtime.ProtosTask;
import com.guillermomolina.protos.semantic.Canonicalizer;
import com.guillermomolina.protos.semantic.ast.CanonicalSequence;
import com.oracle.truffle.api.TruffleLanguage.LanguageReference;
import com.oracle.truffle.api.source.Source;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;

final class ProtosPerf006B6A4OrdinaryNativeFastPathTest {
    private static final LanguageReference<ProtosLanguage> LANGUAGE_REF =
            LanguageReference.create(ProtosLanguage.class);

    @Test
    void ordinaryNativeClosureRunsInsideTaskWithoutSuspensionCapability() throws Exception {
        try (LanguageScope scope = languageScope()) {
            ProtosPrelude prelude = core();
            ProtosActorExecutionDomain domain = new ProtosActorExecutionDomain();
            ProtosActivation module = activation(prelude, domain);
            ProtosObjectValue token = new ProtosObjectValue(ProtosObjectValue.rootObject());
            module.context().createLocalSlot(
                    "nativeLeaf",
                    ProtosClosureValue.nativeClosure((activation, supplied) -> token));

            ProtosTask task = executeTask(
                    domain,
                    module,
                    lowerRoot(scope.language(), "nativeLeaf()", "b6a4-native-closure.protos"));
            assertTrue(domain.dispatchOne());
            assertEquals(ProtosTask.State.COMPLETED, task.state());
            assertSame(token, task.result().orElseThrow());
        }

        System.out.println("PERF006_B6A4_ORDINARY_NATIVE_TASK_CALL=PASS");
    }

    @Test
    void ordinaryNativeMethodPreservesReceiverAndPhysicalHome() throws Exception {
        try (LanguageScope scope = languageScope()) {
            ProtosPrelude prelude = core();
            ProtosActorExecutionDomain domain = new ProtosActorExecutionDomain();
            ProtosActivation module = activation(prelude, domain);
            ProtosObjectValue receiver = new ProtosObjectValue(ProtosObjectValue.rootObject());
            ProtosObjectValue token = new ProtosObjectValue(ProtosObjectValue.rootObject());
            AtomicReference<ProtosActivation> seen = new AtomicReference<>();
            receiver.createLocalSlot(
                    "probe",
                    ProtosClosureValue.nativeClosure(
                            (activation, supplied) -> {
                                seen.set(activation);
                                return token;
                            }));
            module.context().createLocalSlot("receiver", receiver);

            ProtosTask task = executeTask(
                    domain,
                    module,
                    lowerRoot(scope.language(), "receiver.probe()", "b6a4-native-method.protos"));
            assertTrue(domain.dispatchOne());
            assertEquals(ProtosTask.State.COMPLETED, task.state());
            assertSame(token, task.result().orElseThrow());
            assertSame(receiver, seen.get().receiver());
            assertSame(receiver, seen.get().methodHome().orElseThrow());
        }

        System.out.println("PERF006_B6A4_NATIVE_METHOD_RECEIVER_HOME=PASS");
    }

    @Test
    void realCoreIntegerNativeProtocolRunsThroughBytecodeTask() throws Exception {
        try (LanguageScope scope = languageScope()) {
            ProtosPrelude prelude = core();
            ProtosActorExecutionDomain domain = new ProtosActorExecutionDomain();
            ProtosActivation module = activation(prelude, domain);

            ProtosTask task = executeTask(
                    domain,
                    module,
                    lowerRoot(scope.language(), "1 + 2", "b6a4-core-integer-native.protos"));
            assertTrue(domain.dispatchOne());
            assertEquals(ProtosTask.State.COMPLETED, task.state());
            ProtosIntegerValue result = (ProtosIntegerValue) task.result().orElseThrow();
            assertEquals(String.valueOf("3"), com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerDecimalText(result));
        }

        System.out.println("PERF006_B6A4_CORE_NATIVE_PROTOCOL=PASS");
    }

    @Test
    void ordinaryNativeSignalKeepsExactErrorIdentity() throws Exception {
        try (LanguageScope scope = languageScope()) {
            ProtosPrelude prelude = core();
            ProtosActorExecutionDomain domain = new ProtosActorExecutionDomain();
            ProtosActivation module = activation(prelude, domain);
            ProtosObjectValue error = ProtosCoreErrors.newError(module);
            module.context().createLocalSlot(
                    "boom",
                    ProtosClosureValue.nativeClosure(
                            (activation, supplied) -> {
                                throw new ProtosSignalException(error);
                            }));

            ProtosTask task = executeTask(
                    domain,
                    module,
                    lowerRoot(scope.language(), "boom()", "b6a4-native-error.protos"));
            assertTrue(domain.dispatchOne());
            assertEquals(ProtosTask.State.FAILED, task.state());
            assertSame(error, task.failure().orElseThrow());
        }

        System.out.println("PERF006_B6A4_NATIVE_ERROR_IDENTITY=PASS");
    }

    @Test
    void completedOrdinaryNativePrefixIsNotReplayedAcrossFutureSuspension() throws Exception {
        try (LanguageScope scope = languageScope()) {
            ProtosPrelude prelude = core();
            ProtosActorExecutionDomain domain = new ProtosActorExecutionDomain();
            ProtosActivation module = activation(prelude, domain);
            ProtosFutureValue future = new ProtosFutureValue(prelude.futurePrototype(), domain);
            AtomicInteger calls = new AtomicInteger();
            module.context().createLocalSlot("f", future);
            module.context().createLocalSlot(
                    "probe",
                    ProtosClosureValue.nativeClosure(
                            (activation, supplied) -> {
                                calls.incrementAndGet();
                                return ProtosNullValue.INSTANCE;
                            }));

            ProtosTask task = executeTask(
                    domain,
                    module,
                    lowerRoot(
                            scope.language(),
                            "probe()\nf.value()\nprobe()",
                            "b6a4-native-prefix-suspension.protos"));

            assertTrue(domain.dispatchOne());
            assertEquals(ProtosTask.State.SUSPENDED, task.state());
            assertEquals(1, calls.get());

            assertTrue(future.resolve(ProtosNullValue.INSTANCE, module));
            assertTrue(domain.dispatchOne());
            assertEquals(ProtosTask.State.COMPLETED, task.state());
            assertEquals(2, calls.get());
        }

        System.out.println("PERF006_B6A4_NATIVE_PREFIX_REPLAY=NO");
        System.out.println("PERF006_B6A4_PLAT019_SUSPENSION_CAPABILITY_RETAINED=YES");
    }

    private static ProtosTask executeTask(
            ProtosActorExecutionDomain domain,
            ProtosActivation activation,
            ProtosSemanticBytecodeRootNode root) {
        return domain.createTask(
                null,
                current ->
                        ProtosBytecodeTaskExecution.execute(
                                current,
                                root.getCallTarget(),
                                activation));
    }

    private static LanguageScope languageScope() {
        Context context = Context.newBuilder(ProtosLanguage.ID).build();
        context.initialize(ProtosLanguage.ID);
        context.enter();
        return new LanguageScope(context, LANGUAGE_REF.get(null));
    }

    private static ProtosPrelude core() throws Exception {
        return new ProtosCoreBootstrap().bootstrap(Path.of("protos", "lib", "core"));
    }

    private static ProtosActivation activation(
            ProtosPrelude prelude,
            ProtosActorExecutionDomain domain) {
        return prelude.newModuleActivation(
                new ProtosActorModuleState(),
                null,
                prelude.newExecutionContext(),
                domain);
    }

    private static CanonicalSequence canonicalize(String characters) {
        return (CanonicalSequence)
                new Canonicalizer().canonicalize(new ProtosParser(characters).parseProgram());
    }

    private static ProtosSemanticBytecodeRootNode lowerRoot(
            ProtosLanguage language,
            String characters,
            String sourceName)
            throws Exception {
        Source source = Source.newBuilder(ProtosLanguage.ID, characters, sourceName).build();
        return new CanonicalToBytecodeLowerer(language, source, ProtosExecutionMode.NORMAL)
                .lowerRoot(canonicalize(characters));
    }

    private record LanguageScope(Context context, ProtosLanguage language)
            implements AutoCloseable {
        @Override
        public void close() {
            context.leave();
            context.close();
        }
    }
}
