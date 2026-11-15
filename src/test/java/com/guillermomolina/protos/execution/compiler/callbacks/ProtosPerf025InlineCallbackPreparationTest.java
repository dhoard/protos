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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.guillermomolina.protos.parser.ProtosParser;
import com.guillermomolina.protos.runtime.ProtosActivation;
import com.guillermomolina.protos.runtime.ProtosClosureValue;
import com.guillermomolina.protos.runtime.ProtosNullValue;
import com.guillermomolina.protos.runtime.ProtosObjectValue;
import com.guillermomolina.protos.runtime.ProtosNumericValueSupport;
import com.guillermomolina.protos.semantic.Canonicalizer;
import com.guillermomolina.protos.semantic.ast.CanonicalSequence;
import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.TruffleLanguage.LanguageReference;
import com.oracle.truffle.api.bytecode.Instruction;
import com.oracle.truffle.api.nodes.RootNode;
import com.oracle.truffle.api.source.Source;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;

/**
 * PERF025 / PLAT044 B′ focal structural evidence for pay-as-you-grow inline
 * callback preparation.
 *
 * <p>An eligible immediate literal callback must first perform authoritative
 * ordinary {@code call} selection and retain only its compact invocation
 * carrier. The generated source root must test PLAT044 admission before
 * materializing the callback activation, and the physical
 * {@code PreparedClosureCall} fallback must exist only on the miss branch.
 *
 * <p>This is deliberately structural rather than a benchmark: PERF026 already
 * owns the observable Boolean/while/each semantics, tooling, control-transfer
 * and suspension regressions. This test freezes only the PERF025 preparation
 * boundary shared by those families.
 */
final class ProtosPerf025InlineCallbackPreparationTest {
    private static final Path CORE = Path.of("protos", "lib", "core");

    private static final LanguageReference<ProtosLanguage> LANGUAGE_REF =
            LanguageReference.create(ProtosLanguage.class);

    /**
     * A literal one-parameter callback whose own bindings are proven LOCAL
     * runs in the physical root of {@code run}: the element is stored by
     * the iteration operation straight into the parameter local, the other
     * own binding is reset to ABSENT on every iteration, and the loop body
     * neither creates a Closure or activation nor dispatches any message.
     */
    @Test
    void admittedInlineCallbackUsesStaticFrameBindingsWithoutEntryActivation()
            throws Exception {
        try (Context context = Context.newBuilder(ProtosLanguage.ID).build()) {
            context.initialize(ProtosLanguage.ID);
            context.enter();
            try {
                ProtosActivation module = new Probe().module();
                Object outcome =
                        lowerRoot(
                                """
                                run: () => {
                                    last: 0
                                    values: Array(1, 2, 3)
                                    returned: values.each((element) => {
                                        local: element
                                        local = element
                                        last = local
                                    })
                                    (returned === values).ifTrueIfFalse(() => { last }, () => { 0 })
                                }
                                run()
                                """)
                                .getCallTarget()
                                .call(module);

                assertEquals(
                        "3",
                        ProtosNumericValueSupport.integerDecimalText(outcome),
                        "each must visit every element and answer its original receiver");

                List<String> names = instructionNames(physicalRun(module));

                int selection =
                        indexOfContainingAfter(names, "c.IsStandardArrayEachSelection", -1);
                int snapshot =
                        indexOfContainingAfter(names, "c.GetArrayEachSnapshot", selection);
                int next =
                        indexOfContainingAfter(names, "c.ArrayEachNext", snapshot);
                int backward =
                        indexOfContainingAfter(names, "branch.backward", next);

                assertTrue(selection >= 0,
                        () -> "missing standard each selection guard: " + names);
                assertTrue(snapshot > selection,
                        () -> "snapshot must follow the selection guard: " + names);
                assertTrue(next > snapshot,
                        () -> "missing native iteration step: " + names);
                assertTrue(backward > next,
                        () -> "iteration must be a Bytecode DSL loop: " + names);

                List<String> perElement = names.subList(next, backward + 1);
                assertEquals(
                        1L,
                        perElement.stream()
                                .filter(name -> name.contains("c.ResetInlineCallbackLocal"))
                                .count(),
                        () -> "each iteration must reset exactly the callback's own "
                                + "creation, never its parameter: " + perElement);
                assertFalse(
                        perElement.stream().anyMatch(
                                name -> name.startsWith("c.Call")
                                        || name.contains("GetMethod")
                                        || name.contains("MaterializeClosure")
                                        || name.contains("MaterializeCurrentClosure")
                                        || name.contains("CaptureClosureCells")
                                        || name.contains("Activation")),
                        () -> "an admitted callback must not create a Closure or "
                                + "activation nor dispatch per element: " + perElement);
            } finally {
                context.leave();
            }
        }

        System.out.println("PERF025_INLINE_EACH_PARAMETER_LOCAL_STORE=YES");
        System.out.println("PERF025_INLINE_EACH_PER_ITERATION_RESET=YES");
        System.out.println("PERF025_INLINE_EACH_PER_ELEMENT_DISPATCH=NO");
    }

    @Test
    void contextObservingInlineCallbackPublishesLazyContext()
            throws Exception {
        try (Context context = Context.newBuilder(ProtosLanguage.ID).build()) {
            context.initialize(ProtosLanguage.ID);
            context.enter();
            try {
                Probe probe = new Probe();
                ProtosActivation module = probe.module();

                ProtosBytecodeRootNode entry =
                        lowerRoot(
                                """
                                run: () => {
                                    Array(1).each((element) => {
                                        probe(context)
                                        element
                                    })
                                }
                                run()
                                """);

                entry.getCallTarget().call(module);

                // Translated guarantee: observing `context` no longer forces a
                // separate Closure root. The callback stays inline in its host
                // and its Context is published lazily on first observation.
                ProtosBytecodeRootNode hostRoot =
                        probe.callbackHost.get();

                assertNotNull(
                        hostRoot,
                        "context observer must execute in an inline callback host");

                List<String> names = instructionNames(hostRoot);

                assertTrue(
                        names.stream().anyMatch(
                                name -> name.contains("c.PublishInlineCallbackContext")),
                        () -> "observable inline callback must publish its Context lazily: "
                                + names);

                assertTrue(
                        names.stream().anyMatch(
                                name -> name.contains("c.DetachInlineCallbackActivation")),
                        () -> "observable inline callback must detach its activation on exit: "
                                + names);

                assertFalse(
                        names.stream().anyMatch(
                                name -> name.contains("c.PublishCurrentClosureContext")),
                        () -> "inline callback must not fall back to a Closure activation: "
                                + names);

                ProtosObjectValue callbackContext =
                        probe.observedContext.get();

                assertNotNull(
                        callbackContext,
                        "probe must receive the observable callback Context");

                assertTrue(
                        callbackContext != module.context(),
                        "callback Context must be distinct from module Context");

                assertEquals(
                        "1",
                        ProtosNumericValueSupport.integerDecimalText(
                                callbackContext.readLocalSlot("element")
                                        .orElseThrow()),
                        "the callback parameter must remain visible in its Context");
            } finally {
                context.leave();
            }
        }

        System.out.println("PERF025_CALLBACK_CONTEXT_PUBLISHED=PASS");
    }

    @Test
    void booleanLiteralAdmissionPrecedesActivationAndPhysicalFallback() throws Exception {
        assertNativeInlineControl(
                """
                run: () => {
                    true.ifTrue(() => {
                        local: 1
                        local
                    })
                }
                run()
                """,
                "1",
                "ResetInlineCallbackLocal");

        System.out.println("PERF025_INLINE_BOOLEAN_BYTECODE=PASS");
    }

    @Test
    void whileLiteralAdmissionPrecedesActivationAndPhysicalFallback() throws Exception {
        assertNativeInlineControl(
                """
                run: () => {
                    n: 0
                    (() => { n < 2 }).whileTrue() {
                        n = n + 1
                    }
                    n
                }
                run()
                """,
                "2",
                "RichCompareLt",
                "CanonicalControlCondition",
                "NumberAdd",
                "branch.backward");

        System.out.println("PERF025_INLINE_WHILE_BYTECODE=PASS");
    }

    /**
     * Inspect the physical Closure root, not the source entry root or
     * a callback root reached only through a dynamically resolved probe.
     */
    private static void assertNativeInlineControl(
            String characters,
            String expectedResult,
            String... requiredOperations) throws Exception {
        try (Context context = Context.newBuilder(ProtosLanguage.ID).build()) {
            context.initialize(ProtosLanguage.ID);
            context.enter();
            try {
                ProtosActivation module = new Probe().module();
                ProtosBytecodeRootNode entry = lowerRoot(characters);
                Object outcome = entry.getCallTarget().call(module);

                assertEquals(
                        expectedResult,
                        ProtosNumericValueSupport.integerDecimalText(outcome),
                        "native callback must preserve its numerical result");

                ProtosClosureValue run =
                        (ProtosClosureValue)
                                module.context().readLocalSlot("run").orElseThrow();

                ProtosBytecodeRootNode physicalRoot =
                        run.executionPlan()
                                .orElseThrow()
                                .bytecodeActivationRootForTesting();

                List<String> names = instructionNames(physicalRoot);

                for (String operation : requiredOperations) {
                    assertTrue(
                            names.stream().anyMatch(
                                    name -> name.contains(operation)),
                            () -> "missing native operation " + operation
                                    + ": " + names);
                }

                assertFalse(
                        names.stream().anyMatch(
                                name -> name.contains("MaterializeCurrentClosure")),
                        () -> "inline callback must not materialize a Closure: "
                                + names);

                assertFalse(
                        names.stream().anyMatch(
                                name -> name.contains("c.CallNilaryMethod")),
                        () -> "native control must not dispatch nilary messages: "
                                + names);

                long guardedNumericSites = names.stream()
                        .filter(name -> name.contains("c.IsStandardNumericSelection"))
                        .count();

                if (guardedNumericSites > 0) {
                    assertOrdered(
                            names,
                            "c.IsStandardNumericSelection",
                            "c.GetMethod",
                            "c.CallUnaryMethod");

                    assertTrue(
                            names.stream()
                                    .filter(name -> name.contains("c.GetMethod"))
                                    .count() == guardedNumericSites,
                            () -> "each guarded numeric site owns one method selection: "
                                    + names);

                    assertTrue(
                            names.stream()
                                    .filter(name -> name.contains("c.CallUnaryMethod"))
                                    .count() == guardedNumericSites,
                            () -> "each guarded numeric site owns one custom-dispatch branch: "
                                    + names);
                } else {
                    assertFalse(
                            names.stream().anyMatch(
                                    name -> name.contains("c.GetMethod")
                                            || name.contains("c.CallUnaryMethod")),
                            () -> "unguarded native control dispatched a message: "
                                    + names);
                }

                assertFalse(
                        names.stream().anyMatch(
                                name -> name.contains("StructuredPrepareInlineChildCall")
                                        || name.contains("MaterializeInlineCallbackActivation")),
                        () -> "retired inline machinery must not return: "
                                + names);
            } finally {
                context.leave();
            }
        }
    }

    /**
     * The admitted site observes the ordinary {@code each} selection before
     * the native loop, keeps the ordinary GetMethod/CallUnaryMethod send for
     * any other selection, keeps the callback a genuine Closure whose
     * redefined {@code call} is honoured, and never repeats an element or effect when
     * a selection changes in the middle of a native iteration.
     */
    @Test
    void eachLiteralAdmissionPrecedesActivationAndPhysicalFallback() throws Exception {
        try (Context context = Context.newBuilder(ProtosLanguage.ID).build()) {
            context.initialize(ProtosLanguage.ID);
            context.enter();
            try {
                // Ordinary selection on the receiver: its own each is invoked
                // with the materialized callback, and the native loop is not.
                ProtosActivation module = new Probe().module();
                List<Object> visits = recordingHook(module, null);
                lowerRoot(
                                """
                                hook: recorder
                                run: () => {
                                    values: Array(1, 2)
                                    values.each: (callback) => { callback(7) }
                                    values.each((element) => {
                                        seen: element
                                        hook(seen)
                                    })
                                }
                                run()
                                """)
                        .getCallTarget()
                        .call(module);
                assertEquals(List.of(7L), visits,
                        "a receiver-defined each must be selected and must receive "
                                + "the genuine callback");

                List<String> names = instructionNames(physicalRun(module));
                assertOrdered(
                        names,
                        "c.IsStandardArrayEachSelection",
                        "c.GetArrayEachSnapshot",
                        "c.ArrayEachNext",
                        "c.ResetInlineCallbackLocal",
                        "c.MaterializeCurrentClosure",
                        "c.GetMethod",
                        "c.CallUnaryMethod");

                // A selection change during the native loop neither repeats
                // nor skips an element: the selection was made once.
                ProtosActivation deoptModule = new Probe().module();
                List<Object> deoptVisits =
                        recordingHook(deoptModule, (values, element) -> {
                            if (Long.valueOf(1L).equals(element)) {
                                values.createLocalSlot(
                                        "each",
                                        ProtosClosureValue.nativeClosure(
                                                (activation, supplied) ->
                                                        ProtosNullValue.INSTANCE));
                            }
                        });
                lowerRoot(
                        """
                        hook: recorder
                        run: () => {
                            values: Array(1, 2, 3)
                            values.each((element) => {
                                hook(values, element)
                            })
                        }
                        run()
                        run()
                        """)
                        .getCallTarget()
                        .call(deoptModule);
                assertEquals(List.of(1L, 2L, 3L, 1L, 2L, 3L), deoptVisits,
                        "every snapshot element must be visited exactly once per each");

                // The root Object is frozen, so the reachable call
                // redefinition is a local call slot on the callback itself.
                // The literal must remain a genuine Closure whose ordinary
                // invocation selects that slot instead of its own body.
                ProtosActivation callModule = new Probe().module();
                List<Object> callVisits = recordingHook(callModule, null);
                lowerRoot(
                        """
                        hook: recorder
                        run: () => {
                            values: Array(1, 2)
                            values.each: (callback) => {
                                callback.call: (argument) => { hook(argument + 100) }
                                callback(1)
                                callback(2)
                            }
                            values.each((element) => {
                                hook(element)
                            })
                        }
                        run()
                        """)
                        .getCallTarget()
                        .call(callModule);
                assertEquals(List.of(101L, 102L), callVisits,
                        "a redefined call must be selected by the ordinary invocation");
            } finally {
                context.leave();
            }
        }

        System.out.println("PERF025_INLINE_EACH_ORDINARY_SELECTION=PASS");
        System.out.println("PERF025_INLINE_EACH_CALL_REDEFINITION=PASS");
        System.out.println("PERF025_INLINE_EACH_SELECTION_CHANGE_NO_REPEAT=PASS");
    }

    /** What a recording hook does besides recording its element. */
    @FunctionalInterface
    private interface HookEffect {
        void apply(ProtosObjectValue values, Object element);
    }

    /**
     * Installs the module binding {@code recorder}: a native callable that
     * records its last argument and applies {@code effect} to its first
     * argument when it receives two.
     */
    private static List<Object> recordingHook(ProtosActivation module, HookEffect effect) {
        List<Object> visits = new java.util.ArrayList<>();
        module.context().createLocalSlot(
                "recorder",
                ProtosClosureValue.nativeClosure(
                        (activation, supplied) -> {
                            Object element = supplied.get(supplied.size() - 1);
                            visits.add(element);
                            if (effect != null && supplied.size() == 2) {
                                effect.apply((ProtosObjectValue) supplied.get(0), element);
                            }
                            return ProtosNullValue.INSTANCE;
                        }));
        return visits;
    }

    /** The physical Bytecode root of the module binding {@code run}. */
    private static ProtosBytecodeRootNode physicalRun(ProtosActivation module) {
        ProtosClosureValue run =
                (ProtosClosureValue) module.context().readLocalSlot("run").orElseThrow();
        return run.executionPlan().orElseThrow().bytecodeActivationRootForTesting();
    }

    /**
     * The linear Bytecode instruction order reflects branch construction:
     * preparation and admission are common-prefix operations, the activation
     * load is emitted only in the admitted then-branch, and fallback
     * materialization is emitted in the later else-branch.
     */
    private static void assertOrdered(
            List<String> names,
            String... operations) {
        int previous = -1;

        for (String operation : operations) {
            int expectedAfter = previous;
            int current =
                    indexOfContainingAfter(
                            names,
                            operation,
                            expectedAfter);

            assertTrue(
                    current >= 0,
                    () ->
                            "missing "
                                    + operation
                                    + " after instruction "
                                    + expectedAfter
                                    + " in "
                                    + names);

            previous = current;
        }
    }

    private static int indexOfContainingAfter(
            List<String> names,
            String operation,
            int previous) {
        for (int index = previous + 1; index < names.size(); index++) {
            if (names.get(index).contains(operation)) {
                return index;
            }
        }
        return -1;
    }

    private static List<String> instructionNames(
            ProtosBytecodeRootNode root) {
        root.getRootNodes().ensureComplete();

        return root.getBytecodeNode()
                .getInstructionsAsList()
                .stream()
                .map(ProtosInstructionNames::of)
                .toList();
    }

    private static ProtosBytecodeRootNode lowerRoot(
            String characters) {
        Source source =
                Source.newBuilder(
                                ProtosLanguage.ID,
                                characters,
                                "perf025-inline-callback-preparation.protos")
                        .build();

        CanonicalSequence sequence =
                (CanonicalSequence)
                        new Canonicalizer()
                                .canonicalize(
                                        new ProtosParser(characters)
                                                .parseProgram());

        return new CanonicalToBytecodeLowerer(
                        LANGUAGE_REF.get(null),
                        source)
                .lowerRoot(sequence);
    }

    /**
     * Native callback used only to recover the semantic source root that owns
     * the admitted inline region. PLAT044 removes the callback frame itself, so
     * the first semantic frame observed here is the enclosing Closure root.
     */
    private static final class Probe {
        private final AtomicReference<ProtosBytecodeRootNode>
                callbackHost = new AtomicReference<>();
        private final AtomicReference<ProtosObjectValue>
                observedContext = new AtomicReference<>();

        ProtosActivation module() throws Exception {
            ProtosActivation module =
                    new ProtosCoreBootstrap()
                            .bootstrap(CORE)
                            .newModuleActivation();

            module.context()
                    .createLocalSlot(
                            "probe",
                            ProtosClosureValue.nativeClosure(
                                    (activation, supplied) -> {
                                        if (supplied.size() == 1
                                                && supplied.get(0) instanceof ProtosObjectValue observed) {
                                            observedContext.compareAndSet(null, observed);
                                        }
                                        Truffle.getRuntime()
                                                .iterateFrames(
                                                        frame -> {
                                                            RootNode root =
                                                                    ((RootCallTarget)
                                                                                    frame
                                                                                            .getCallTarget())
                                                                            .getRootNode();

                                                            if (root
                                                                    instanceof
                                                                    ProtosBytecodeRootNode
                                                                            semantic) {
                                                                callbackHost
                                                                        .compareAndSet(
                                                                                null,
                                                                                semantic);
                                                                return semantic;
                                                            }

                                                            return null;
                                                        });

                                        return ProtosNullValue.INSTANCE;
                                    }));

            return module;
        }
    }
}
