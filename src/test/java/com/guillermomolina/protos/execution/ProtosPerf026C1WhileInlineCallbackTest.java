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
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.guillermomolina.protos.parser.ProtosParser;
import com.guillermomolina.protos.runtime.ProtosActivation;
import com.guillermomolina.protos.runtime.ProtosBooleanValue;
import com.guillermomolina.protos.runtime.ProtosClosureValue;
import com.guillermomolina.protos.runtime.ProtosNullValue;
import com.guillermomolina.protos.semantic.Canonicalizer;
import com.guillermomolina.protos.semantic.ast.CanonicalSequence;
import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.TruffleLanguage.LanguageReference;
import com.oracle.truffle.api.bytecode.BytecodeNode;
import com.oracle.truffle.api.bytecode.TagTree;
import com.oracle.truffle.api.debug.Breakpoint;
import com.oracle.truffle.api.debug.DebugScope;
import com.oracle.truffle.api.debug.DebugValue;
import com.oracle.truffle.api.debug.Debugger;
import com.oracle.truffle.api.debug.DebuggerSession;
import com.oracle.truffle.api.instrumentation.StandardTags;
import com.oracle.truffle.api.nodes.RootNode;
import com.oracle.truffle.api.source.Source;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Instrument;
import org.junit.jupiter.api.Test;

/**
 * PERF026-C1 (PLAT044 B′) focal evidence: a standard {@code whileTrue} whose
 * condition receiver and body argument are both eligible immediate literals is
 * sequenced in its semantic source root, and each fresh condition/body
 * activation runs inline with its own RootTag and projected tooling scope;
 * every non-admitted pair keeps the structured-dispatch while and its physical
 * callback roots.
 *
 * <p>Eligible sites are placed inside a Closure ({@code run}) because literals
 * at module top level own a fresh return home, a shape C1 keeps on the
 * structured path.
 */
final class ProtosPerf026C1WhileInlineCallbackTest {
    private static final Path CORE = Path.of("protos", "lib", "core");

    private static final LanguageReference<ProtosLanguage> LANGUAGE_REF =
            LanguageReference.create(ProtosLanguage.class);

    @Test
    void eligibleLiteralPairRunsAsLocalLoopInItsSourceRoot() throws Exception {
        try (Context context = Context.newBuilder(ProtosLanguage.ID).build()) {
            context.initialize(ProtosLanguage.ID);
            context.enter();
            try {
                Probe probe = new Probe();
                ProtosSemanticBytecodeRootNode root =
                        lowerRoot(
                                """
                                run: () => {
                                    n: 0
                                    (() => {
                                        probe()
                                        n < 3
                                    }).whileTrue() {
                                        probe()
                                        n = n + 1
                                    }
                                }
                                run()
                                """);

                // D201: the last body result is the value of its final assignment.
                assertInteger(3, root.getCallTarget().call(probe.module()));

                assertEquals(7, probe.calls.get(), "four conditions and three bodies");
                assertEquals(List.of(2), probe.depths(), () -> "stacks=" + probe.stacks);
                ProtosSemanticBytecodeRootNode runRoot =
                        assertInstanceOf(
                                ProtosSemanticBytecodeRootNode.class,
                                probe.stacks.get(0).get(0));
                for (List<RootNode> stack : probe.stacks) {
                    assertSame(runRoot, stack.get(0));
                    assertSame(root, stack.get(1));
                }

                runRoot.getRootNodes().ensureComplete();
                assertEquals(
                        3,
                        rootTags(runRoot.getBytecodeNode()),
                        "automatic RootTag of run plus the condition and body RootTags");
            } finally {
                context.leave();
            }
        }
        System.out.println("PERF026_C1_WHILE_HELPER_ROOT_REMOVED=YES");
        System.out.println("PERF026_C1_WHILE_CALLBACK_ROOTS_REMOVED=YES");
        System.out.println("PERF026_C1_WHILE_INLINE_ROOTTAGS=YES");
        System.out.println("D201_INLINE_WHILE_TRUE_RETURNS_LAST_BODY=YES");
    }

    @Test
    void eligibleLiteralPairOfStandardWhileRunsLocallyAndReturnsFalse() throws Exception {
        try (Context context = Context.newBuilder(ProtosLanguage.ID).build()) {
            context.initialize(ProtosLanguage.ID);
            context.enter();
            try {
                Probe probe = new Probe();
                ProtosSemanticBytecodeRootNode root =
                        lowerRoot(
                                """
                                run: () => {
                                    n: 0
                                    (() => {
                                        probe()
                                        n < 3
                                    }).while() {
                                        probe()
                                        n = n + 1
                                    }
                                }
                                run()
                                """);

                assertSame(ProtosBooleanValue.FALSE, root.getCallTarget().call(probe.module()));
                assertEquals(7, probe.calls.get(), "four conditions and three bodies");
                assertEquals(List.of(2), probe.depths(), () -> "stacks=" + probe.stacks);
            } finally {
                context.leave();
            }
        }
        System.out.println("D201_INLINE_WHILE_RETURNS_FINAL_FALSE=YES");
    }

    /**
     * D201 path equivalence: the inline local loop, the structured-dispatch
     * loop (dynamic condition) and an inline candidate that falls back to the
     * structured loop (nested Closure literal) produce the same normal results
     * for both selectors.
     */
    @Test
    void inlineStructuredAndFallbackLoopsAgreeOnBothResultPolicies() throws Exception {
        ProtosExecutionOutcome outcome =
                ProtosTestExecutionSupport.execute(
                        "d201-path-equivalence.protos",
                        """
                        run: () => {
                            box: {}
                            n: 0
                            inlineTrue: (() => { n < 2 }).whileTrue() {
                                n = n + 1
                                box
                            }
                            n = 0
                            condition: () => { n < 2 }
                            structuredTrue: condition.whileTrue() {
                                n = n + 1
                                box
                            }
                            n = 0
                            fallbackTrue: (() => { n < 2 }).whileTrue() {
                                step: () => { 1 }
                                n = n + step()
                                box
                            }
                            n = 0
                            inlineWhile: (() => { n < 2 }).while() {
                                n = n + 1
                                box
                            }
                            n = 0
                            structuredWhile: condition.while() {
                                n = n + 1
                                box
                            }
                            n = 0
                            fallbackWhile: (() => { n < 2 }).while() {
                                step: () => { 1 }
                                n = n + step()
                                box
                            }
                            zeroTrue: (() => { false }).whileTrue() { box }
                            zeroWhile: (() => { false }).while() { box }
                            ((inlineTrue === box) &&
                                (structuredTrue === box) &&
                                (fallbackTrue === box) &&
                                (inlineWhile === false) &&
                                (structuredWhile === false) &&
                                (fallbackWhile === false) &&
                                (zeroTrue === null) &&
                                (zeroWhile === false)).ifTrueIfFalse(() => { 1 }, () => { 0 })
                        }
                        run()
                        """,
                        new ProtosCoreBootstrap().bootstrap(CORE).newModuleActivation());
        assertCompletedWith(1, outcome);
        System.out.println("D201_INLINE_STRUCTURED_FALLBACK_RESULTS_EQUAL=YES");
    }

    @Test
    void dynamicConditionRetainsStructuredWhile() throws Exception {
        assertStructuredWhilePreserved(
                """
                run: () => {
                    n: 0
                    condition: () => {
                        probe()
                        n < 1
                    }
                    condition.whileTrue() {
                        n = n + 1
                    }
                }
                run()
                """);
        System.out.println("PERF026_C1_DYNAMIC_CONDITION_FALLBACK=YES");
    }

    @Test
    void dynamicBodyRetainsStructuredWhile() throws Exception {
        assertStructuredWhilePreserved(
                """
                run: () => {
                    n: 0
                    body: () => {
                        probe()
                        n = n + 1
                    }
                    (() => { n < 1 }).whileTrue(body)
                }
                run()
                """);
        System.out.println("PERF026_C1_DYNAMIC_BODY_FALLBACK=YES");
    }

    @Test
    void nestedClosureLiteralRetainsStructuredWhile() throws Exception {
        assertStructuredWhilePreserved(
                """
                run: () => {
                    n: 0
                    (() => { n < 1 }).whileTrue() {
                        probe()
                        step: () => { 1 }
                        n = n + step()
                    }
                }
                run()
                """,
                // The callback's own RootTag plus the I096 inline body of step().
                2);
        System.out.println("PERF026_C1_NESTED_CLOSURE_LITERAL_FALLBACK=YES");
    }

    @Test
    void ownedReturnHomeLiteralsRetainStructuredWhile() throws Exception {
        try (Context context = Context.newBuilder(ProtosLanguage.ID).build()) {
            context.initialize(ProtosLanguage.ID);
            context.enter();
            try {
                Probe probe = new Probe();
                ProtosSemanticBytecodeRootNode root =
                        lowerRoot(
                                """
                                n: 0
                                (() => { n < 1 }).whileTrue() {
                                    probe()
                                    n = n + 1
                                }
                                """);

                assertInteger(1, root.getCallTarget().call(probe.module()));
                assertEquals(List.of(3), probe.depths(), () -> "stacks=" + probe.stacks);
                List<RootNode> stack = probe.stacks.get(0);
                assertInstanceOf(ProtosSemanticBytecodeRootNode.class, stack.get(0));
                assertInstanceOf(ProtosBytecodeRootNode.class, stack.get(1));
                assertSame(root, stack.get(2));
            } finally {
                context.leave();
            }
        }
        System.out.println("PERF026_C1_OWNED_RETURN_HOME_FALLBACK=YES");
    }

    @Test
    void nonWhileSelectionOfLiteralPairStaysOrdinary() throws Exception {
        try (Context context = Context.newBuilder(ProtosLanguage.ID).build()) {
            context.initialize(ProtosLanguage.ID);
            context.enter();
            try {
                Probe probe = new Probe();
                ProtosSemanticBytecodeRootNode root =
                        lowerRoot(
                                """
                                run: () => {
                                    (() => {
                                        probe()
                                        1
                                    }).ensure(() => { 2 })
                                }
                                run()
                                """);

                assertInteger(1, root.getCallTarget().call(probe.module()));
                assertEquals(1, probe.calls.get());
                assertEquals(List.of(4), probe.depths(), () -> "stacks=" + probe.stacks);
                assertInstanceOf(ProtosBytecodeRootNode.class, probe.stacks.get(0).get(1));
            } finally {
                context.leave();
            }
        }
        System.out.println("PERF026_C1_NON_WHILE_SELECTION_ORDINARY=YES");
    }

    @Test
    void inlineLoopKeepsCaptureNonLocalReturnAndFreshActivations() throws Exception {
        try (Context context = Context.newBuilder(ProtosLanguage.ID).build()) {
            context.initialize(ProtosLanguage.ID);
            context.enter();
            try {
                Probe nonLocalReturn = new Probe();
                assertInteger(
                        42,
                        lowerRoot(
                                        """
                                        run: () => {
                                            (() => { true }).whileTrue() {
                                                probe()
                                                ^41
                                            }
                                            100
                                        }
                                        run() + 1
                                        """)
                                .getCallTarget()
                                .call(nonLocalReturn.module()));
                assertEquals(List.of(2), nonLocalReturn.depths());

                Probe capture = new Probe();
                assertInteger(
                        5,
                        lowerRoot(
                                        """
                                        run: () => {
                                            n: 0
                                            (() => { n < 5 }).whileTrue() {
                                                probe()
                                                n = n + 1
                                            }
                                            n
                                        }
                                        run()
                                        """)
                                .getCallTarget()
                                .call(capture.module()));
                assertEquals(5, capture.calls.get());
                assertEquals(List.of(2), capture.depths());

                Probe activation = new Probe();
                assertSame(
                        ProtosBooleanValue.TRUE,
                        lowerRoot(
                                        """
                                        run: () => {
                                            outer: context
                                            conditionContext: null
                                            previous: null
                                            current: null
                                            n: 0
                                            (() => {
                                                conditionContext = context
                                                n < 2
                                            }).whileTrue() {
                                                probe()
                                                previous = current
                                                current = context
                                                n = n + 1
                                            }
                                            (previous !== current) && (current !== outer) && (current !== conditionContext)
                                        }
                                        run()
                                        """)
                                .getCallTarget()
                                .call(activation.module()));
                assertEquals(List.of(2), activation.depths());
            } finally {
                context.leave();
            }
        }
        System.out.println("PERF026_C1_NLR_PRESERVED=YES");
        System.out.println("PERF026_C1_CAPTURE_BY_REFERENCE_PRESERVED=YES");
        System.out.println("PERF026_C1_FRESH_ACTIVATION_PER_ITERATION=YES");
    }

    @Test
    void strictConditionAndBodyErrorKeepLoopAuthority() throws Exception {
        try (Context context = Context.newBuilder(ProtosLanguage.ID).build()) {
            context.initialize(ProtosLanguage.ID);
            context.enter();
            try {
                Probe nonBoolean = new Probe();
                assertInteger(
                        42,
                        lowerRoot(
                                        """
                                        run: () => {
                                            (() => { 1 }).whileTrue() {
                                                probe()
                                            }
                                        }
                                        Error.handle(() => { run() }, (caught) => { 42 })
                                        """)
                                .getCallTarget()
                                .call(nonBoolean.module()));
                assertEquals(0, nonBoolean.calls.get(), "invalid condition skips the body");

                Probe bodyError = new Probe();
                assertInteger(
                        42,
                        lowerRoot(
                                        """
                                        run: () => {
                                            (() => {
                                                probe()
                                                true
                                            }).whileTrue() {
                                                Error().signal()
                                            }
                                        }
                                        Error.handle(() => { run() }, (caught) => { 42 })
                                        """)
                                .getCallTarget()
                                .call(bodyError.module()));
                assertEquals(1, bodyError.calls.get(), "no condition activation after a body Error");
            } finally {
                context.leave();
            }
        }
        System.out.println("PERF026_C1_STRICT_BOOLEAN_CONDITION_PRESERVED=YES");
        System.out.println("PERF026_C1_BODY_ERROR_STOPS_LOOP=YES");
    }

    @Test
    void conditionAndBodySuspensionResumeInsideTheLocalLoop() throws Exception {
        ProtosExecutionOutcome outcome =
                ProtosTestExecutionSupport.execute(
                        "perf026-c1-suspension.protos",
                        """
                        run: () => {
                            limit: (() => { 3 }).future()
                            step: (() => { 1 }).future()
                            n: 0
                            (() => { n < limit.value() }).whileTrue() {
                                n = n + step.value()
                            }
                            n
                        }
                        run()
                        """,
                        new ProtosCoreBootstrap().bootstrap(CORE).newModuleActivation());
        assertCompletedWith(3, outcome);
        System.out.println("PERF026_C1_CONDITION_SUSPENSION_PRESERVED=YES");
        System.out.println("PERF026_C1_BODY_SUSPENSION_PRESERVED=YES");
    }

    /**
     * The approved PLAT044 tooling delta: no distinct callback debugger frame,
     * but a breakpoint inside the inline body suspends once per iteration and
     * its scope is the body activation (its own binding plus captured ones).
     */
    @Test
    void debuggerScopeInsideInlineBodyProjectsTheBodyActivation() throws Exception {
        Source source =
                Source.newBuilder(
                                ProtosLanguage.ID,
                                """
                                run: () => {
                                    outerMarker: 1
                                    n: 0
                                    (() => { n < 2 }).whileTrue() {
                                        innerMarker: 2
                                        n = n + innerMarker + outerMarker
                                    }
                                    n
                                }
                                run()
                                """,
                                "perf026-c1-debugger.protos")
                        .uri(URI.create("memory:///perf026-c1-debugger.protos"))
                        .mimeType(ProtosLanguage.MIME_TYPE)
                        .build();
        ProtosActivation module = new ProtosCoreBootstrap().bootstrap(CORE).newModuleActivation();
        AtomicReference<Throwable> callbackFailure = new AtomicReference<>();
        AtomicReference<String> inner = new AtomicReference<>();
        AtomicReference<String> outer = new AtomicReference<>();
        AtomicInteger suspensions = new AtomicInteger();

        try (ProtosPolyglotExecutionContext polyglot =
                ProtosPolyglotExecutionContext.open(
                        InputStream.nullInputStream(),
                        OutputStream.nullOutputStream(),
                        OutputStream.nullOutputStream())) {
            Instrument instrument =
                    polyglot.engineForTesting().getInstruments().get("debugger");
            assertNotNull(instrument);
            Debugger debugger = instrument.lookup(Debugger.class);
            try (DebuggerSession session =
                    debugger.startSession(
                            event -> {
                                try {
                                    suspensions.incrementAndGet();
                                    DebugScope scope = event.getTopStackFrame().getScope();
                                    DebugValue innerValue = scope.getDeclaredValue("innerMarker");
                                    DebugValue outerValue = scope.getDeclaredValue("outerMarker");
                                    assertNotNull(innerValue, "body-owned binding must be visible");
                                    assertNotNull(outerValue, "captured binding must be visible");
                                    inner.set(innerValue.toDisplayString());
                                    outer.set(outerValue.toDisplayString());
                                } catch (Throwable failure) {
                                    callbackFailure.compareAndSet(null, failure);
                                } finally {
                                    event.prepareContinue();
                                }
                            })) {
                session.install(Breakpoint.newBuilder(source).lineIs(6).build());
                assertCompletedWith(3, polyglot.execute(source, module));
            }
        }

        if (callbackFailure.get() != null) {
            throw new AssertionError("debugger callback failed", callbackFailure.get());
        }
        assertEquals(1, suspensions.get(), "one body iteration, one breakpoint suspension");
        assertEquals("2", inner.get());
        assertEquals("1", outer.get());
        System.out.println("PERF026_C1_WHILE_INLINE_SCOPE_PROJECTION=YES");
    }

    /**
     * A non-admitted pair inside {@code run}: the probing callback runs in its
     * own physical root entered from the structured-dispatch helper root.
     */
    private static void assertStructuredWhilePreserved(String characters) throws Exception {
        assertStructuredWhilePreserved(characters, 1);
    }

    /** As above; {@code callbackRootTags} counts the callback's own inline sites. */
    private static void assertStructuredWhilePreserved(
            String characters, int callbackRootTags) throws Exception {
        try (Context context = Context.newBuilder(ProtosLanguage.ID).build()) {
            context.initialize(ProtosLanguage.ID);
            context.enter();
            try {
                Probe probe = new Probe();
                ProtosSemanticBytecodeRootNode root = lowerRoot(characters);

                // Every preserved shape runs exactly one body ending in n = 1 (D201).
                assertInteger(1, root.getCallTarget().call(probe.module()));

                assertEquals(List.of(4), probe.depths(), () -> characters + " stacks=" + probe.stacks);
                List<RootNode> stack = probe.stacks.get(0);
                ProtosSemanticBytecodeRootNode callback =
                        assertInstanceOf(ProtosSemanticBytecodeRootNode.class, stack.get(0));
                assertInstanceOf(ProtosBytecodeRootNode.class, stack.get(1));
                assertSame(root, stack.get(3));

                callback.getRootNodes().ensureComplete();
                assertEquals(callbackRootTags, rootTags(callback.getBytecodeNode()), characters);
            } finally {
                context.leave();
            }
        }
    }

    /** A native {@code probe} binding recording the guest CallTarget stack of every call. */
    private static final class Probe {
        final List<List<RootNode>> stacks = new ArrayList<>();
        final AtomicInteger calls = new AtomicInteger();

        /** The distinct stack depths observed, in first-seen order. */
        List<Integer> depths() {
            return stacks.stream().map(List::size).distinct().toList();
        }

        ProtosActivation module() throws Exception {
            ProtosActivation module =
                    new ProtosCoreBootstrap().bootstrap(CORE).newModuleActivation();
            module.context().createLocalSlot(
                    "probe",
                    ProtosClosureValue.nativeClosure(
                            (activation, supplied) -> {
                                calls.incrementAndGet();
                                List<RootNode> stack = new ArrayList<>();
                                Truffle.getRuntime()
                                        .iterateFrames(
                                                frame -> {
                                                    stack.add(
                                                            ((RootCallTarget) frame.getCallTarget())
                                                                    .getRootNode());
                                                    return null;
                                                });
                                stacks.add(stack);
                                return ProtosNullValue.INSTANCE;
                            }));
            return module;
        }
    }

    private static ProtosSemanticBytecodeRootNode lowerRoot(String characters) {
        Source source =
                Source.newBuilder(ProtosLanguage.ID, characters, "perf026-c1.protos").build();
        CanonicalSequence sequence =
                (CanonicalSequence)
                        new Canonicalizer()
                                .canonicalize(new ProtosParser(characters).parseProgram());
        return new CanonicalToBytecodeLowerer(LANGUAGE_REF.get(null), source, ProtosExecutionMode.NORMAL)
                .lowerRoot(sequence);
    }

    private static void assertCompletedWith(long expected, ProtosExecutionOutcome outcome) {
        assertEquals(
                ProtosExecutionOutcome.State.COMPLETED,
                outcome.state(),
                () -> "outcome=" + outcome.state() + ", error=" + outcome.error());
        assertInteger(expected, outcome.value());
    }

    private static void assertInteger(long expected, Object value) {
        assertEquals(Long.toString(expected), com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerDecimalText(value));
    }

    private static int rootTags(BytecodeNode bytecodeNode) {
        return countRootTags(bytecodeNode.getTagTree());
    }

    private static int countRootTags(TagTree tree) {
        if (tree == null) {
            return 0;
        }
        int count = tree.hasTag(StandardTags.RootTag.class) ? 1 : 0;
        for (TagTree child : tree.getTreeChildren()) {
            count += countRootTags(child);
        }
        return count;
    }
}
