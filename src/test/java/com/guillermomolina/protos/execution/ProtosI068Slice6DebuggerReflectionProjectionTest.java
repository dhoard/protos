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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.guillermomolina.protos.parser.ProtosParser;
import com.guillermomolina.protos.runtime.ProtosActivation;
import com.guillermomolina.protos.runtime.ProtosArrayValue;
import com.guillermomolina.protos.runtime.ProtosClosureValue;
import com.guillermomolina.protos.runtime.ProtosExecutionContextValue;
import com.guillermomolina.protos.runtime.ProtosObjectValue;
import com.guillermomolina.protos.runtime.ProtosPrelude;
import com.guillermomolina.protos.runtime.ProtosSignalException;
import com.guillermomolina.protos.runtime.ProtosStringValue;
import com.guillermomolina.protos.semantic.Canonicalizer;
import com.guillermomolina.protos.semantic.ast.CanonicalClosure;
import com.guillermomolina.protos.semantic.ast.CanonicalSequence;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.TruffleLanguage.LanguageReference;
import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.source.Source;
import java.util.ArrayList;
import java.util.List;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;

/**
 * I068 Slice 6 ("debugger/reflection projection"): focused evidence that the
 * existing semantic tooling surfaces observe the single frame-backed lexical
 * authority introduced by Slices 2-5, including dynamic overflow and semantic
 * presence, without exposing Bytecode-only temporaries.
 */
final class ProtosI068Slice6DebuggerReflectionProjectionTest {
    private static final LanguageReference<ProtosLanguage> LANGUAGE_REF =
            LanguageReference.create(ProtosLanguage.class);

    private final InteropLibrary interop = InteropLibrary.getUncached();

    @Test
    void debuggerAndCoreReflectionProjectFrameBindingsAndDynamicOverflow()
            throws Exception {
        withEnteredLanguage(
                (language, module) -> {
                    ProtosStringValue frameValue =
                            assertInstanceOf(
                                    ProtosStringValue.class,
                                    runRoot(
                                            language,
                                            module,
                                            "frameBinding: \"frame\"\n"
                                                    + "frameBinding"));

                    ProtosExecutionContextValue context =
                            assertInstanceOf(
                                    ProtosExecutionContextValue.class,
                                    module.context());
                    ProtosFrameLexicalBindingAuthority authority =
                            assertInstanceOf(
                                    ProtosFrameLexicalBindingAuthority.class,
                                    context.lexicalBindingAuthorityForRuntime());

                    assertTrue(
                            authority.hasFrameBackedBindingAt(
                                    "frameBinding", 0));

                    ProtosStringValue overflowValue =
                            new ProtosStringValue("overflow");
                    context.createLocalSlot(
                            "dynamicOverflow",
                            overflowValue);

                    assertFalse(
                            authority.hasFrameBackedBindingAt(
                                    "dynamicOverflow", 0));
                    assertSame(
                            frameValue,
                            context.readLocalSlot("frameBinding")
                                    .orElseThrow());
                    assertSame(
                            overflowValue,
                            context.readLocalSlot("dynamicOverflow")
                                    .orElseThrow());

                    assertEquals(
                            List.of("dynamicOverflow", "frameBinding"),
                            reflectedSlotNames(context, module));
                    assertSame(
                            frameValue,
                            ProtosInvocation.invokeMessage(
                                    context,
                                    "slotValue",
                                    List.of(
                                            new ProtosStringValue(
                                                    "frameBinding")),
                                    module));
                    assertSame(
                            overflowValue,
                            ProtosInvocation.invokeMessage(
                                    context,
                                    "slotValue",
                                    List.of(
                                            new ProtosStringValue(
                                                    "dynamicOverflow")),
                                    module));

                    Object scope = debuggerScope(module);
                    List<String> names = memberNames(scope);

                    assertTrue(names.contains("frameBinding"));
                    assertTrue(names.contains("dynamicOverflow"));
                    assertSame(
                            frameValue,
                            interop.readMember(
                                    scope, "frameBinding"));
                    assertSame(
                            overflowValue,
                            interop.readMember(
                                    scope, "dynamicOverflow"));

                    assertBackendTemporariesHidden(names);
                });
    }

    @Test
    void semanticallyAbsentFrameLocalIsHiddenFromReflectionAndDebugger()
            throws Exception {
        withEnteredLanguage(
                (language, module) -> {
                    String characters =
                            "(first = \"first\", later = missing) => first";
                    Source source =
                            Source.newBuilder(
                                            ProtosLanguage.ID,
                                            characters,
                                            "i068-slice6-absent-parameter.protos")
                                    .build();
                    CanonicalClosure definition =
                            closureDefinition(characters);
                    ProtosActivation invocation =
                            invocationActivation(
                                    definition,
                                    module,
                                    List.of());

                    assertThrows(
                            ProtosSignalException.class,
                            () ->
                                    new ProtosBytecodeClosureExecutionPlan(
                                                    definition,
                                                    language,
                                                    source, ProtosExecutionMode.NORMAL)
                                            .executeActivation(
                                                    invocation));

                    ProtosExecutionContextValue context =
                            assertInstanceOf(
                                    ProtosExecutionContextValue.class,
                                    invocation.context());
                    ProtosFrameLexicalBindingAuthority authority =
                            assertInstanceOf(
                                    ProtosFrameLexicalBindingAuthority.class,
                                    context.lexicalBindingAuthorityForRuntime());

                    assertTrue(
                            authority.hasFrameBackedBindingAt(
                                    "first", 0));
                    assertFalse(
                            authority.hasFrameBackedBindingAt(
                                    "later", 1));

                    assertTrue(context.hasLocalSlot("first"));
                    assertFalse(context.hasLocalSlot("later"));
                    assertEquals(
                            List.of("first"),
                            reflectedSlotNames(context, module));

                    Object scope = debuggerScope(invocation);
                    List<String> names = memberNames(scope);

                    assertTrue(names.contains("first"));
                    assertFalse(names.contains("later"));
                    assertSame(
                            context.readLocalSlot("first")
                                    .orElseThrow(),
                            interop.readMember(scope, "first"));

                    assertBackendTemporariesHidden(names);
                });
    }

    @Test
    void removedStaticFrameLocalStaysOnStableLayoutButIsSemanticallyAbsent()
            throws Exception {
        withEnteredLanguage(
                (language, module) -> {
                    runRoot(
                            language,
                            module,
                            "removed: \"before\"\n"
                                    + "context.removeSlot(\"removed\")\n"
                                    + "null");

                    ProtosExecutionContextValue context =
                            assertInstanceOf(
                                    ProtosExecutionContextValue.class,
                                    module.context());
                    ProtosFrameLexicalBindingAuthority authority =
                            assertInstanceOf(
                                    ProtosFrameLexicalBindingAuthority.class,
                                    context.lexicalBindingAuthorityForRuntime());

                    assertFalse(
                            authority.hasFrameBackedBindingAt(
                                    "removed", 0));
                    assertFalse(context.hasLocalSlot("removed"));
                    assertFalse(
                            reflectedSlotNames(context, module)
                                    .contains("removed"));

                    Object absentScope = debuggerScope(module);
                    assertFalse(
                            memberNames(absentScope)
                                    .contains("removed"));

                    ProtosStringValue replacement =
                            new ProtosStringValue("after");
                    context.createLocalSlot(
                            "removed",
                            replacement);

                    /*
                     * Re-creation uses the same statically allocated ordinal:
                     * semantic presence changed, physical binding identity did
                     * not migrate to dynamic overflow or a second authority.
                     */
                    assertTrue(
                            authority.hasFrameBackedBindingAt(
                                    "removed", 0));
                    assertSame(
                            replacement,
                            context.readLocalSlot("removed")
                                    .orElseThrow());
                    assertTrue(
                            reflectedSlotNames(context, module)
                                    .contains("removed"));

                    Object presentScope = debuggerScope(module);
                    assertTrue(
                            memberNames(presentScope)
                                    .contains("removed"));
                    assertSame(
                            replacement,
                            interop.readMember(
                                    presentScope, "removed"));
                });
    }

    @Test
    void capturedFrameBackedBindingRemainsLiveInDebuggerProjection()
            throws Exception {
        withEnteredLanguage(
                (language, module) -> {
                    ProtosClosureValue closure =
                            assertInstanceOf(
                                    ProtosClosureValue.class,
                                    runRoot(
                                            language,
                                            module,
                                            "outer: \"before\"\n"
                                                    + "() => outer"));

                    ProtosExecutionContextValue outerContext =
                            assertInstanceOf(
                                    ProtosExecutionContextValue.class,
                                    module.context());
                    ProtosFrameLexicalBindingAuthority authority =
                            assertInstanceOf(
                                    ProtosFrameLexicalBindingAuthority.class,
                                    outerContext
                                            .lexicalBindingAuthorityForRuntime());
                    assertTrue(
                            authority.hasFrameBackedBindingAt(
                                    "outer", 0));

                    ProtosActivation invocation =
                            ProtosActivation.forClosureInvocation(
                                    closure,
                                    List.of(),
                                    module.prelude().orElseThrow(),
                                    module.actorModuleState(),
                                    module.currentModuleKey()
                                            .orElse(null),
                                    module.executionDomain());

                    Object scope = debuggerScope(invocation);
                    List<String> names = memberNames(scope);
                    Object original =
                            outerContext.readLocalSlot("outer")
                                    .orElseThrow();

                    assertTrue(names.contains("outer"));
                    assertSame(
                            original,
                            interop.readMember(scope, "outer"));

                    ProtosStringValue replacement =
                            new ProtosStringValue("after");
                    outerContext.assignLocalSlot(
                            "outer",
                            replacement);

                    assertSame(
                            replacement,
                            outerContext.readLocalSlot("outer")
                                    .orElseThrow());
                    assertSame(
                            replacement,
                            interop.readMember(scope, "outer"));

                    assertBackendTemporariesHidden(names);
                });
    }

    private Object debuggerScope(ProtosActivation activation)
            throws Exception {
        VirtualFrame frame =
                Truffle.getRuntime()
                        .createVirtualFrame(
                                new Object[] {activation},
                                FrameDescriptor.newBuilder().build());
        assertTrue(
                ProtosBytecodeTagTreeNodeExports.hasScope(
                        null, frame));
        return ProtosBytecodeTagTreeNodeExports.getScope(
                null, frame, true);
    }

    private List<String> memberNames(Object scope)
            throws Exception {
        Object members = interop.getMembers(scope);
        long size = interop.getArraySize(members);
        ArrayList<String> result =
                new ArrayList<>((int) size);

        for (long index = 0; index < size; index++) {
            result.add(
                    interop.asString(
                            interop.readArrayElement(
                                    members, index)));
        }

        return List.copyOf(result);
    }

    private static List<String> reflectedSlotNames(
            ProtosObjectValue context,
            ProtosActivation caller) {
        ProtosArrayValue names =
                assertInstanceOf(
                        ProtosArrayValue.class,
                        ProtosInvocation.invokeMessage(
                                context,
                                "slotNames",
                                List.of(),
                                caller));

        return names.indexedSnapshot().stream()
                .map(
                        value ->
                                assertInstanceOf(
                                                ProtosStringValue.class,
                                                value)
                                        .value())
                .toList();
    }

    private static void assertBackendTemporariesHidden(
            List<String> names) {
        assertFalse(names.contains("sequenceResult"));
        assertFalse(names.contains("preparedClosureCall"));
        assertFalse(names.contains("childResult"));
        assertFalse(names.contains("resumeValue"));
        assertFalse(names.contains("structuredEnsureCall"));
        assertFalse(names.contains("structuredWhileCall"));
    }

    private static Object runRoot(
            ProtosLanguage language,
            ProtosActivation module,
            String characters) {
        Source source =
                Source.newBuilder(
                                ProtosLanguage.ID,
                                characters,
                                "i068-slice6-projection.protos")
                        .build();
        ProtosSemanticBytecodeRootNode root =
                new CanonicalToBytecodeLowerer(
                                language, source, ProtosExecutionMode.NORMAL)
                        .lowerRoot(
                                canonicalize(characters));
        return root.getCallTarget().call(module);
    }

    private static ProtosActivation invocationActivation(
            CanonicalClosure definition,
            ProtosActivation module,
            List<?> supplied) {
        ProtosClosureValue closure =
                new ProtosClosureValue(
                        definition,
                        module.lexicalContextsForClosureCapture(),
                        module.receiver(),
                        module.methodHome().orElse(null),
                        module.returnHome().orElse(null),
                        module.prelude().orElseThrow());

        return ProtosActivation.forClosureInvocation(
                closure,
                supplied,
                module.prelude().orElseThrow(),
                module.actorModuleState(),
                module.currentModuleKey().orElse(null),
                module.executionDomain());
    }

    private static CanonicalClosure closureDefinition(
            String characters) {
        CanonicalSequence sequence =
                canonicalize(characters);
        assertEquals(1, sequence.expressions().size());
        return assertInstanceOf(
                CanonicalClosure.class,
                sequence.expressions().get(0));
    }

    private static CanonicalSequence canonicalize(
            String characters) {
        return (CanonicalSequence)
                new Canonicalizer()
                        .canonicalize(
                                new ProtosParser(characters)
                                        .parseProgram());
    }

    private interface LanguageTest {
        void run(
                ProtosLanguage language,
                ProtosActivation module)
                throws Exception;
    }

    private static void withEnteredLanguage(
            LanguageTest test)
            throws Exception {
        try (Context context =
                Context.newBuilder(
                                ProtosLanguage.ID)
                        .build()) {
            context.initialize(ProtosLanguage.ID);
            context.enter();
            try {
                test.run(
                        LANGUAGE_REF.get(null),
                        moduleActivation());
            } finally {
                context.leave();
            }
        }
    }

    private static ProtosActivation moduleActivation() {
        ProtosStandardObjectProtocol.install();

        ProtosObjectValue contextPrototype =
                new ProtosObjectValue(
                        ProtosObjectValue.rootObject());
        ProtosObjectValue bindings =
                new ProtosObjectValue(contextPrototype);
        ProtosObjectValue errorPrototype =
                new ProtosObjectValue(
                        ProtosObjectValue.rootObject());
        bindings.createLocalSlot(
                "Context", contextPrototype);
        bindings.createLocalSlot(
                "Error", errorPrototype);
        bindings.createLocalSlot(
                "SlotNotFound",
                new ProtosObjectValue(errorPrototype));
        bindings.createLocalSlot(
                "Array",
                new ProtosObjectValue(
                        ProtosObjectValue.rootObject()));
        bindings.freeze();

        return new ProtosPrelude(
                        bindings, contextPrototype)
                .newModuleActivation();
    }
}
