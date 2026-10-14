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
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.guillermomolina.protos.parser.ProtosParser;
import com.guillermomolina.protos.runtime.ProtosActivation;
import com.guillermomolina.protos.runtime.ProtosClosureValue;
import com.guillermomolina.protos.runtime.ProtosIntegerValue;
import com.guillermomolina.protos.runtime.ProtosObjectValue;
import com.guillermomolina.protos.runtime.ProtosPrelude;
import com.guillermomolina.protos.runtime.ProtosReturnHome;
import com.guillermomolina.protos.runtime.ProtosSignalException;
import com.guillermomolina.protos.semantic.Canonicalizer;
import com.guillermomolina.protos.semantic.ast.CanonicalSequence;
import com.oracle.truffle.api.TruffleLanguage.LanguageReference;
import com.oracle.truffle.api.bytecode.BytecodeNode;
import com.oracle.truffle.api.bytecode.Instruction;
import com.oracle.truffle.api.frame.MaterializedFrame;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.source.Source;
import java.lang.reflect.Field;
import java.util.List;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;

/**
 * PERF025 frame-materialization slice focal evidence: declaring a local or a
 * parameter no longer forces a Closure root to install its materialized-frame
 * lexical authority. Roots that may need their bindings outside their live
 * frame (captures, Object construction, {@code context}, non-{@code Resolved}
 * access to an own name) keep the BUG013 escape-safe authority; an admitted
 * root whose activation is nonetheless already observed installs that same
 * authority in-frame on its first binding establishment.
 */
final class ProtosPerf025FrameMaterializationSliceTest {
    private static final LanguageReference<ProtosLanguage> LANGUAGE_REF =
            LanguageReference.create(ProtosLanguage.class);

    private static final String INSTALL = "InstallFrameLexicalAuthority";

    @Test
    void parameterOnlyClosureRunsWithoutMaterializedFrameAuthority() throws Exception {
        withFixture(
                fixture -> {
                    ClosureUnderTest plan = closurePlan(fixture, "(value) => value");
                    List<String> names = instructionNames(activationNode(plan));
                    assertNoInstall(names);
                    assertContains(names, "BindClosureFrameParameter");
                    assertContains(names, "ReadRootFrameLocal");

                    ProtosObjectValue argument = newObject();
                    ProtosActivation invocation = deferredInvocation(fixture, plan.closure, List.of(argument));
                    assertSame(argument, plan.plan.executeBytecodeActivationForTesting(invocation));
                    assertNull(privateField(invocation, "context"));
                    assertNull(privateField(invocation, "deferredContextAuthority"));
                });
        System.out.println("PARAMETER_ONLY_REQUIRES_MATERIALIZED_FRAME=NO");
    }

    @Test
    void perf034ScalarBodyWithObservedActivationPreservesFrameAuthority()
            throws Exception {
        withFixture(ProtosExecutionMode.DEBUG, fixture -> {
            ClosureUnderTest plan =
                    closurePlan(
                            fixture,
                            "() => { first: 7\nsecond: 9\nfirst }");

            assertEquals(
                    ProtosExecutionMode.DEBUG,
                    plan.plan.bytecodeProfileForTesting(),
                    "Observed activation must use the DEBUG lowering profile");

            // An unobserved rich carrier still owes no lexical authority.
            ProtosActivation unobserved =
                    deferredInvocation(
                            fixture, plan.closure, List.of());
            assertEquals(
                    7L,
                    unwrapNumber(
                            plan.plan.executeBytecodeActivationForTesting(
                                    unobserved)));
            assertNull(privateField(unobserved, "context"));
            assertNull(
                    unobserved.currentLexicalBindingAuthorityForRuntime());

            // DEBUG observation before entry requires lexical authority.
            ProtosActivation invocation =
                    deferredInvocation(
                            fixture, plan.closure, List.of());
            ProtosObjectValue observedContext = invocation.context();

            assertEquals(
                    7L,
                    unwrapNumber(
                            plan.plan.executeBytecodeActivationForTesting(
                                    invocation)));

            assertNotNull(
                    invocation.currentLexicalBindingAuthorityForRuntime());
            assertSame(observedContext, invocation.context());
            assertEquals(
                    7L,
                    unwrapNumber(
                            invocation.context()
                                    .readLocalSlot("first")
                                    .orElseThrow()));
            assertEquals(
                    9L,
                    unwrapNumber(
                            invocation.context()
                                    .readLocalSlot("second")
                                    .orElseThrow()));
        });
    }

    @Test
    void localOnlyClosureStaysInOrdinaryFrameLocals() throws Exception {
        withFixture(
                fixture -> {
                    ClosureUnderTest plan =
                            closurePlan(fixture, "(a) => { b: a\n" + "c: b\n" + "c = b\n" + "c }");
                    List<String> names = instructionNames(activationNode(plan));
                    assertNoInstall(names);
                    assertContains(names, "CreateCurrentFrameLocal");
                    assertContains(names, "AssignRootFrameLocal");

                    ProtosObjectValue argument = newObject();
                    ProtosActivation invocation = deferredInvocation(fixture, plan.closure, List.of(argument));
                    assertSame(argument, plan.plan.executeBytecodeActivationForTesting(invocation));
                    assertNull(privateField(invocation, "context"));
                    assertNull(privateField(invocation, "deferredContextAuthority"));
                });
        System.out.println("LOCAL_ONLY_REQUIRES_MATERIALIZED_FRAME=NO");
    }

    @Test
    void sequentialDefaultsSeeOnlyEarlierEstablishedParameters() throws Exception {
        withFixture(
                fixture -> {
                    ClosureUnderTest plan = closurePlan(fixture, "(a, b = a, c = b) => c");
                    assertNoInstall(instructionNames(activationNode(plan)));
                    ProtosObjectValue first = newObject();
                    ProtosObjectValue second = newObject();
                    assertSame(first, plan.plan.executeBytecodeActivationForTesting(
                            deferredInvocation(fixture, plan.closure, List.of(first))));
                    assertSame(second, plan.plan.executeBytecodeActivationForTesting(
                            deferredInvocation(fixture, plan.closure, List.of(first, second))));
                    assertThrows(
                            ProtosSignalException.class,
                            () -> plan.plan.executeBytecodeActivationForTesting(
                                    deferredInvocation(fixture, plan.closure, List.of(first, second, first, second))));
                });
        System.out.println("SEQUENTIAL_PARAMETERS_DEFAULTS=PASS");
    }

    @Test
    void duplicateFrameNativeCreationIsTheExactCreationError() throws Exception {
        withFixture(
                fixture -> {
                    ClosureUnderTest plan = closurePlan(fixture, "(a) => { b: a\n" + "b: a }");
                    assertNoInstall(instructionNames(activationNode(plan)));
                    ProtosSignalException signal =
                            assertThrows(
                                    ProtosSignalException.class,
                                    () -> plan.plan.executeBytecodeActivationForTesting(
                                            deferredInvocation(fixture, plan.closure, List.of(newObject()))));
                    assertSame(fixture.errorPrototype, signal.error().parent().orElseThrow());
                });
        System.out.println("DUPLICATE_CREATE_ERROR=PASS");
    }

    @Test
    void observedActivationTransitionsInFrameAndPreservesBindings() throws Exception {
        withFixture(
                fixture -> {
                    ClosureUnderTest plan = closurePlan(fixture, "(value) => { other: value\n" + "other }");
                    ProtosObjectValue argument = newObject();
                    ProtosActivation invocation =
                            ProtosActivation.forClosureInvocation(
                                    plan.closure,
                                    List.of(argument),
                                    fixture.module.prelude().orElseThrow(),
                                    fixture.module.actorModuleState(),
                                    fixture.module.currentModuleKey().orElse(null),
                                    fixture.module.executionDomain());
                    assertSame(argument, plan.plan.executeBytecodeActivationForTesting(invocation));
                    ProtosFrameLexicalBindingAuthority authority =
                            assertInstanceOf(
                                    ProtosFrameLexicalBindingAuthority.class,
                                    invocation.currentLexicalBindingAuthorityForRuntime());
                    assertSame(argument, invocation.context().readLocalSlot("value").orElseThrow());
                    assertSame(argument, invocation.context().readLocalSlot("other").orElseThrow());
                    assertEquals(List.of("value", "other"), List.copyOf(authority.bindingsSnapshot().keySet()));
                });
        System.out.println("OBSERVED_ACTIVATION_IN_FRAME_TRANSITION=PASS");
    }

    @Test
    void capturedEscapingClosureKeepsPersistentAuthority() throws Exception {
        withFixture(
                fixture -> {
                    ClosureUnderTest plan = closurePlan(fixture, "(x) => { g: () => x\n" + "x = 5\n" + "g }");
                    assertContains(instructionNames(activationNode(plan)), INSTALL);
                    assertEquals(
                            6L,
                            unwrapNumber(run(fixture,
                                    "f: (x) => { set: (v) => { x = v }\n" + "get: () => x\n" + "set(6)\n" + "get }\n"
                                            + "f(1)()")));
                });
        System.out.println("CAPTURED_ESCAPE_SEMANTICS=PASS");
    }

    @Test
    void explicitContextObservationKeepsPersistentAuthority() throws Exception {
        withFixture(
                fixture -> {
                    ClosureUnderTest plan = closurePlan(fixture, "(a) => context");
                    assertContains(instructionNames(activationNode(plan)), INSTALL);
                    assertEquals(
                            3L,
                            unwrapNumber(run(fixture,
                                    "f: (a) => { b: a\n" + "context }\n" + "c: f(3)\n" + "c.slotValue(\"b\")")));
                });
        System.out.println("CONTEXT_OBSERVATION=PASS");
    }

    @Test
    void nonResolvedAccessToOwnNameKeepsPersistentAuthority() throws Exception {
        withFixture(
                fixture -> {
                    ClosureUnderTest plan =
                            closurePlan(fixture, "x: 7\n" + "(a) => { y: x\n" + "x: 2\n" + "y }");
                    assertContains(instructionNames(activationNode(plan)), INSTALL);
                    assertEquals(7L, unwrapNumber(plan.plan.executeBytecodeActivationForTesting(
                            deferredInvocation(fixture, plan.closure, List.of(newObject())))));
                });
        System.out.println("CANDIDATE_DYNAMIC_FALLBACK=PASS");
    }

    @Test
    void frameAuthorityBookkeepingStaysCompactUntilGeneralHistoryIsNeeded()
            throws Exception {
        withFixture(
                fixture -> {
                    ClosureUnderTest plan =
                            closurePlan(
                                    fixture,
                                    "(value) => { other: value\n context }");
                    ProtosObjectValue argument = newObject();
                    ProtosActivation invocation =
                            ProtosActivation.forClosureInvocation(
                                    plan.closure,
                                    List.of(argument),
                                    fixture.module.prelude().orElseThrow(),
                                    fixture.module.actorModuleState(),
                                    fixture.module.currentModuleKey().orElse(null),
                                    fixture.module.executionDomain());

                    ProtosObjectValue observed =
                            assertInstanceOf(
                                    ProtosObjectValue.class,
                                    plan.plan.executeBytecodeActivationForTesting(
                                            invocation));
                    ProtosFrameLexicalBindingAuthority authority =
                            assertInstanceOf(
                                    ProtosFrameLexicalBindingAuthority.class,
                                    invocation.currentLexicalBindingAuthorityForRuntime());

                    assertSame(
                            authority,
                            ((com.guillermomolina.protos.runtime.ProtosExecutionContextValue)
                                            observed)
                                    .lexicalBindingAuthorityForRuntime());
                    assertNull(
                            privateField(authority, "dynamicOverflow"),
                            "static-only authority must not allocate dynamic overflow");
                    assertNull(
                            privateField(authority, "establishmentOrder"),
                            "monotonic static establishment stays in compact mode");
                    assertEquals(
                            List.of("value", "other"),
                            List.copyOf(authority.bindingsSnapshot().keySet()));
                    assertFalse(authority.isEmpty());

                    assertSame(
                            argument,
                            observed.removeLocalSlot("other"));
                    assertFalse(authority.isEmpty());
                    assertSame(
                            argument,
                            observed.removeLocalSlot("value"));
                    assertTrue(authority.isEmpty());
                    assertNull(
                            privateField(authority, "establishmentOrder"),
                            "empty compact authority must stay compact");
                    assertEquals(
                            -1,
                            privateField(authority, "lastCompactFrameOrdinal"));

                    observed.createLocalSlot("value", argument);
                    assertFalse(authority.isEmpty());
                    observed.createLocalSlot("other", argument);
                    assertFalse(authority.isEmpty());
                    assertNull(
                            privateField(authority, "establishmentOrder"),
                            "ascending compact recreation must stay compact");
                    assertEquals(
                            List.of("value", "other"),
                            List.copyOf(authority.bindingsSnapshot().keySet()));

                    Object dynamicValue = newObject();
                    observed.createLocalSlot("dynamic", dynamicValue);

                    assertNotNull(privateField(authority, "dynamicOverflow"));
                    assertNotNull(privateField(authority, "establishmentOrder"));
                    assertEquals(
                            List.of("value", "other", "dynamic"),
                            List.copyOf(authority.bindingsSnapshot().keySet()));
                    assertSame(
                            dynamicValue,
                            observed.readLocalSlot("dynamic").orElseThrow());
                    assertFalse(authority.isEmpty());

                    assertSame(
                            argument,
                            observed.removeLocalSlot("value"));
                    observed.createLocalSlot("value", argument);

                    assertEquals(
                            List.of("other", "dynamic", "value"),
                            List.copyOf(authority.bindingsSnapshot().keySet()));

                    assertSame(
                            argument,
                            observed.removeLocalSlot("other"));
                    assertSame(
                            dynamicValue,
                            observed.removeLocalSlot("dynamic"));
                    assertSame(
                            argument,
                            observed.removeLocalSlot("value"));
                    assertTrue(authority.isEmpty());
                    assertNotNull(
                            privateField(authority, "establishmentOrder"),
                            "general authority must remain in general mode when empty");
                    assertNull(
                            privateField(authority, "dynamicOverflow"),
                            "removing the final dynamic binding must release overflow");

                    observed.createLocalSlot("value", argument);
                    assertFalse(authority.isEmpty());
                    assertEquals(
                            List.of("value"),
                            List.copyOf(authority.bindingsSnapshot().keySet()));
                });
        System.out.println("FRAME_AUTHORITY_PAY_AS_YOU_GROW=PASS");
    }

    @Test
    void noLexicalAuthorityOrToolingViewRetainsAVirtualFrame() {
        for (Class<?> owner :
                List.of(
                        ProtosFrameLexicalBindingAuthority.class,
                        ProtosSemanticBytecodeRootNode.class,
                        ProtosDebuggerScope.class,
                        ProtosActivation.class)) {
            for (Field field : owner.getDeclaredFields()) {
                // MaterializedFrame is itself a VirtualFrame subtype; only a
                // non-materialized frame type would be a raw VirtualFrame.
                assertFalse(
                        VirtualFrame.class.isAssignableFrom(field.getType())
                                && !MaterializedFrame.class.isAssignableFrom(field.getType()),
                        () -> owner.getSimpleName() + " retains a VirtualFrame: " + field.getName());
            }
        }
        System.out.println("RAW_VIRTUALFRAME_RETAINED_ACROSS_ROOT_LIFETIME=NO");
    }

    private static void assertNoInstall(List<String> names) {
        assertTrue(
                names.stream().noneMatch(name -> name.contains(INSTALL)),
                () -> "root still installs a materialized frame authority: " + names);
    }

    private static void assertContains(List<String> names, String operation) {
        assertTrue(
                names.stream().anyMatch(name -> name.contains(operation)),
                () -> "expected " + operation + " in: " + names);
    }

    private static List<String> instructionNames(BytecodeNode node) {
        return node.getInstructionsAsList().stream().map(Instruction::getName).toList();
    }

    private static BytecodeNode activationNode(ClosureUnderTest closure) {
        return closure.plan.bytecodeActivationRootForTesting().getBytecodeNode();
    }

    private static ProtosActivation deferredInvocation(
            Fixture fixture, ProtosClosureValue closure, List<?> supplied) {
        return ProtosActivation.forImmediateMethodInvocationWithReturnHomeForRuntime(
                closure,
                supplied,
                newObject(),
                newObject(),
                fixture.module.prelude().orElseThrow(),
                fixture.module.actorModuleState(),
                fixture.module.currentModuleKey().orElse(null),
                fixture.module.executionDomain(),
                new ProtosReturnHome());
    }

    private record ClosureUnderTest(ProtosClosureValue closure, ProtosClosureExecutionPlan plan) {}

    private record Fixture(
            ProtosLanguage language,
            ProtosActivation module,
            ProtosObjectValue errorPrototype) {}

    private interface FixtureTest {
        void run(Fixture fixture) throws Exception;
    }

    private static void withFixture(FixtureTest test) throws Exception {
        withFixture(ProtosExecutionMode.NORMAL, test);
    }

    private static void withFixture(
            ProtosExecutionMode mode, FixtureTest test) throws Exception {
        Context.Builder builder = Context.newBuilder(ProtosLanguage.ID);
        if (mode == ProtosExecutionMode.DEBUG) {
            builder.allowExperimentalOptions(true)
                    .option("protos.Debug", "true");
        }

        try (Context context = builder.build()) {
            context.initialize(ProtosLanguage.ID);
            context.enter();
            try {
                ProtosStandardObjectProtocol.install();

                ProtosObjectValue contextPrototype = newObject();
                ProtosObjectValue bindings = new ProtosObjectValue(contextPrototype);
                ProtosObjectValue errorPrototype = newObject();
                bindings.createLocalSlot("Context", contextPrototype);
                bindings.createLocalSlot("Error", errorPrototype);
                bindings.createLocalSlot("SlotNotFound", new ProtosObjectValue(errorPrototype));
                bindings.createLocalSlot("Array", newObject());
                bindings.freeze();

                test.run(
                        new Fixture(
                                LANGUAGE_REF.get(null),
                                new ProtosPrelude(bindings, contextPrototype).newModuleActivation(),
                                errorPrototype));
            } finally {
                context.leave();
            }
        }
    }

    /** Runs a module source whose final expression is the Closure under test. */
    private static ClosureUnderTest closurePlan(Fixture fixture, String characters) {
        ProtosClosureValue closure =
                assertInstanceOf(ProtosClosureValue.class, run(fixture, characters));
        return new ClosureUnderTest(closure, closure.executionPlan().orElseThrow());
    }

    private static Object run(Fixture fixture, String characters) {
        Source source = Source.newBuilder(ProtosLanguage.ID, characters, "perf025-frame.protos").build();
        CanonicalSequence canonical =
                (CanonicalSequence) new Canonicalizer().canonicalize(new ProtosParser(characters).parseProgram());
        return new CanonicalToBytecodeLowerer(
                        fixture.language,
                        source,
                        ProtosLanguageContext.current().executionMode())
                .lowerRoot(canonical)
                .getCallTarget()
                .call(fixture.module);
    }

    private static ProtosObjectValue newObject() {
        return new ProtosObjectValue(ProtosObjectValue.rootObject());
    }

    private static Object privateField(Object target, String name) throws ReflectiveOperationException {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static long unwrapNumber(Object value) {
        if (value instanceof Long longValue) {
            return longValue;
        }
        if (value instanceof ProtosIntegerValue integerValue) {
            return integerValue.longValue();
        }
        throw new AssertionError("expected a Protos integer result, got: " + value);
    }
}
