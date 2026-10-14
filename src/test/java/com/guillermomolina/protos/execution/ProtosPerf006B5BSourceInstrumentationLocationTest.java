/*
 * THE LICENSED WORK IS PROVIDED UNDER THE TERMS OF THE ADAPTIVE PUBLIC LICENSE
 * ("LICENSE") AS FIRST COMPLETED BY: Guillermo Adrián Molina. ANY USE, PUBLIC
 * DISPLAY, PUBLIC PERFORMANCE, REPRODUCTION OR DISTRIBUTION OF, OR PREPARATION OF
 * DERIVATIVE WORKS BASED ON THE LICENSED WORK CONSTITUTES RECIPIENT'S ACCEPTANCE
 * OF THIS LICENSE AND ITS TERMS, WHETHER OR NOT SUCH RECIPIENT READS THE TERMS OF
 * THE LICENSE. "LICENSED WORK" AND "RECIPIENT" ARE DEFINED IN THE LICENSE. A COPY
 * OF THE LICENSE IS LOCATED IN THE TEXT FILE ENTITLED "LICENSE.TXT" ACCOMPANYING
 * THE CONTENTS OF THIS FILE. IF A COPY OF THE LICENSE DOES NOT ACCOMPANY THIS
 * FILE, A COPY OF THE LICENSE MAY ALSO BE OBTAINED AT THE FOLLOWING WEB SITE:
 * https://github.com/guillermomolina/protos
 *
 * Software distributed under the License is distributed on an "AS IS" basis,
 * WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License for
 * the specific language governing rights and limitations under the LICENSE.
 */

package com.guillermomolina.protos.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.guillermomolina.protos.parser.ProtosParser;
import com.guillermomolina.protos.runtime.ProtosActivation;
import com.guillermomolina.protos.runtime.ProtosClosureValue;
import com.guillermomolina.protos.runtime.ProtosNullValue;
import com.guillermomolina.protos.runtime.ProtosObjectValue;
import com.guillermomolina.protos.runtime.ProtosPrelude;
import com.guillermomolina.protos.semantic.Canonicalizer;
import com.guillermomolina.protos.semantic.ast.CanonicalClosure;
import com.guillermomolina.protos.semantic.ast.CanonicalSequence;
import com.guillermomolina.protos.source.SourceSpan;
import com.oracle.truffle.api.TruffleLanguage.LanguageReference;
import com.oracle.truffle.api.bytecode.BytecodeConfig;
import com.oracle.truffle.api.bytecode.BytecodeLocation;
import com.oracle.truffle.api.bytecode.BytecodeRootNodes;
import com.oracle.truffle.api.bytecode.ContinuationResult;
import com.oracle.truffle.api.bytecode.TagTree;
import com.oracle.truffle.api.debug.DebuggerTags;
import com.oracle.truffle.api.instrumentation.StandardTags;
import com.oracle.truffle.api.source.Source;
import com.oracle.truffle.api.source.SourceSection;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;

final class ProtosPerf006B5BSourceInstrumentationLocationTest {
    private static final LanguageReference<ProtosLanguage> LANGUAGE_REF =
            LanguageReference.create(ProtosLanguage.class);

    @Test
    void bytecodeSourceAndTagTreePreserveExactMinimalToolingProjection()
            throws Exception {
        try (LanguageScope scope = languageScope()) {
            String characters = "target()\nnull";
            Source source =
                    Source.newBuilder(
                                    ProtosLanguage.ID,
                                    characters,
                                    "perf006-b5b-tags.protos")
                            .build();
            CanonicalSequence sequence = canonicalize(characters);
            ProtosSemanticBytecodeRootNode root =
                    new CanonicalToBytecodeLowerer(scope.language(), source, ProtosExecutionMode.NORMAL)
                            .lowerRoot(sequence);

            root.getRootNodes().ensureComplete();
            SourceSection rootSection = root.ensureSourceSection();
            assertSame(source, rootSection.getSource());
            assertEquals(sequence.span().startOffset(), rootSection.getCharIndex());
            assertEquals(sequence.span().length(), rootSection.getCharLength());
            assertEquals(
                    characters.substring(
                            sequence.span().startOffset(),
                            sequence.span().endOffset()),
                    rootSection.getCharacters().toString());

            TagTree tree = root.getBytecodeNode().getTagTree();
            List<TagTree> statements =
                    collectTags(tree, StandardTags.StatementTag.class);
            List<TagTree> expressions =
                    collectTags(tree, StandardTags.ExpressionTag.class);
            List<TagTree> calls = collectTags(tree, StandardTags.CallTag.class);

            assertEquals(2, statements.size());
            assertEquals(
                    List.of("target()", "null"),
                    sourceCharacters(statements));
            assertTrue(
                    statements.stream()
                            .allMatch(tag -> tag.getSourceSection().getSource() == source));

            assertEquals(
                    statements.size(),
                    expressions.size(),
                    "PLAT034 Expression membership must equal Statement membership");
            assertEquals(
                    sourceCharacters(statements),
                    sourceCharacters(expressions),
                    "Expression and Statement tags must own identical source ranges");
            for (int index = 0; index < statements.size(); index++) {
                assertSame(
                        statements.get(index),
                        expressions.get(index),
                        "Bytecode DSL must use one shared tag region for Statement+Expression");
            }

            assertEquals(1, calls.size());
            assertEquals("target()", calls.get(0).getSourceSection().getCharacters().toString());
            assertSame(source, calls.get(0).getSourceSection().getSource());

            // PLAT042 B′: the source root is the tagged semantic root itself.
            assertEquals(1, collectTags(tree, StandardTags.RootTag.class).size());
            assertEquals(0, collectTags(tree, StandardTags.RootBodyTag.class).size());
            assertEquals(
                    0,
                    collectTags(tree, DebuggerTags.AlwaysHalt.class).size(),
                    "PLAT034 provides AlwaysHalt compatibility but no guest halt locations");
        }

        System.out.println("PERF006_B5B_ROOT_SOURCE_IDENTITY=PASS");
        System.out.println("PERF006_B5B_STATEMENT_TAG_SOURCE_EXACT=PASS");
        System.out.println("PERF006_B5B_CALL_TAG_SOURCE_EXACT=PASS");
        System.out.println("PERF006_B5B_PLAT034_EXPRESSION_EQUALS_STATEMENT=PASS");
        System.out.println("PERF006_B5B_PLAT034_ALWAYS_HALT_LOCATIONS=ZERO");
    }

    @Test
    void composedContinuationRetainsStableCallerAndCalleeLocationsWithoutPrefixReplay()
            throws Exception {
        try (LanguageScope scope = languageScope()) {
            ProtosPrelude prelude = core();
            ProtosActivation module = prelude.newModuleActivation();
            AtomicInteger prefixCalls = new AtomicInteger();
            ProtosObjectValue marker =
                    new ProtosObjectValue(ProtosObjectValue.rootObject());

            module.context().createLocalSlot(
                    "probe",
                    ProtosClosureValue.nativeClosure(
                            (activation, supplied) -> {
                                prefixCalls.incrementAndGet();
                                return ProtosNullValue.INSTANCE;
                            }));

            String childCharacters = "() => { null }";
            Source childSource =
                    Source.newBuilder(
                                    ProtosLanguage.ID,
                                    childCharacters,
                                    "perf006-b5b-child.protos")
                            .build();
            CanonicalClosure childDefinition = closureDefinition(childCharacters);
            ProtosSemanticBytecodeRootNode childRoot =
                    yieldingRoot(
                            scope.language(),
                            childSource,
                            childDefinition.body().span(),
                            marker);
            childRoot.getRootNodes().ensureComplete();
            ProtosClosureExecutionPlan childPlan =
                    ProtosClosureExecutionPlan.bytecode(
                            childDefinition,
                            scope.language(),
                            childSource,
                            childRoot, ProtosExecutionMode.NORMAL);
            ProtosSemanticBytecodeRootNode childSemanticRoot =
                    assertInstanceOf(
                            ProtosSemanticBytecodeRootNode.class,
                            childPlan
                                    .bytecodeActivationTargetForComposition()
                                    .getRootNode());
            module.context().createLocalSlot(
                    "entry",
                    semanticClosure(
                            childDefinition,
                            childPlan,
                            module));

            String parentCharacters = "probe()\nentry()";
            Source parentSource =
                    Source.newBuilder(
                                    ProtosLanguage.ID,
                                    parentCharacters,
                                    "perf006-b5b-parent.protos")
                            .build();
            ProtosSemanticBytecodeRootNode parentRoot =
                    new CanonicalToBytecodeLowerer(scope.language(), parentSource, ProtosExecutionMode.NORMAL)
                            .lowerRoot(canonicalize(parentCharacters));
            parentRoot.getRootNodes().ensureComplete();

            Object first = parentRoot.getCallTarget().call(module);
            assertEquals(1, prefixCalls.get());

            ContinuationResult parent =
                    assertInstanceOf(ContinuationResult.class, first);
            ContinuationResult child =
                    assertInstanceOf(ContinuationResult.class, parent.getResult());
            activationOf(child);

            BytecodeLocation parentLocation = parent.getBytecodeLocation();
            BytecodeLocation childLocation = child.getBytecodeLocation();
            assertNotNull(parentLocation);
            assertNotNull(childLocation);
            parentLocation = parentLocation.ensureSourceInformation();
            childLocation = childLocation.ensureSourceInformation();

            SourceSection parentSection = parentLocation.getSourceLocation();
            SourceSection childSection = childLocation.getSourceLocation();
            assertNotNull(parentSection);
            assertNotNull(childSection);
            assertSame(parentSource, parentSection.getSource());
            assertEquals("entry()", parentSection.getCharacters().toString());
            assertSame(childSource, childSection.getSource());
            assertEquals(
                    childCharacters.substring(
                            childDefinition.body().span().startOffset(),
                            childDefinition.body().span().endOffset()),
                    childSection.getCharacters().toString());

            assertSame(
                    parentRoot,
                    parentLocation.getBytecodeNode().getBytecodeRootNode(),
                    "logical caller location must remain owned by the same Bytecode root");
            assertSame(
                    childSemanticRoot,
                    childLocation.getBytecodeNode().getBytecodeRootNode(),
                    "logical callee location must remain owned by the semantic Bytecode composition root");

            Object completed = parent.continueWith(ProtosNullValue.INSTANCE);
            assertSame(marker, completed);
            assertEquals(
                    1,
                    prefixCalls.get(),
                    "completed parent statement before suspension must not replay");

            BytecodeLocation updatedParentLocation = parentLocation.update();
            BytecodeLocation updatedChildLocation = childLocation.update();
            assertSame(
                    parentRoot,
                    updatedParentLocation.getBytecodeNode().getBytecodeRootNode());
            assertSame(
                    childSemanticRoot,
                    updatedChildLocation.getBytecodeNode().getBytecodeRootNode());

            SourceSection updatedParentSection = updatedParentLocation.getSourceLocation();
            SourceSection updatedChildSection = updatedChildLocation.getSourceLocation();
            assertNotNull(updatedParentSection);
            assertNotNull(updatedChildSection);
            assertSame(parentSource, updatedParentSection.getSource());
            assertEquals(parentSection.getCharIndex(), updatedParentSection.getCharIndex());
            assertEquals(parentSection.getCharLength(), updatedParentSection.getCharLength());
            assertEquals(
                    parentSection.getCharacters().toString(),
                    updatedParentSection.getCharacters().toString());
            assertSame(childSource, updatedChildSection.getSource());
            assertEquals(childSection.getCharIndex(), updatedChildSection.getCharIndex());
            assertEquals(childSection.getCharLength(), updatedChildSection.getCharLength());
            assertEquals(
                    childSection.getCharacters().toString(),
                    updatedChildSection.getCharacters().toString());
        }

        System.out.println("PERF006_B5B_CONTINUATION_CALLER_LOCATION=PASS");
        System.out.println("PERF006_B5B_CONTINUATION_CALLEE_LOCATION=PASS");
        System.out.println("PERF006_B5B_LOGICAL_LOCATION_STABLE_AFTER_RESUME=PASS");
        System.out.println("PERF006_B5B_COMPLETED_PREFIX_REPLAY=NO");
    }

    private static ProtosSemanticBytecodeRootNode yieldingRoot(
            ProtosLanguage language,
            Source source,
            SourceSpan span,
            Object finalValue) {
        BytecodeRootNodes<ProtosSemanticBytecodeRootNode> roots =
                ProtosSemanticBytecodeRootNodeGen.create(
                        language,
                        BytecodeConfig.DEFAULT,
                        builder -> {
                            builder.beginSource(source);
                            builder.beginSourceSection(span.startOffset(), span.length());
                            builder.beginRoot();
                            builder.beginTag(StandardTags.StatementTag.class);
                            builder.beginYield();
                            builder.emitLoadArgument(0);
                            builder.endYield();
                            builder.endTag(StandardTags.StatementTag.class);
                            builder.beginReturn();
                            builder.emitLoadConstant(finalValue);
                            builder.endReturn();
                            builder.endRoot();
                            builder.endSourceSection();
                            builder.endSource();
                        });
        return roots.getNode(0);
    }

    private static List<TagTree> collectTags(
            TagTree tree,
            Class<? extends com.oracle.truffle.api.instrumentation.Tag> tag) {
        List<TagTree> result = new ArrayList<>();
        if (tree == null) {
            return result;
        }
        if (tree.hasTag(tag)) {
            result.add(tree);
        }
        for (TagTree child : tree.getTreeChildren()) {
            result.addAll(collectTags(child, tag));
        }
        return result;
    }

    private static List<String> sourceCharacters(List<TagTree> tags) {
        return tags.stream()
                .map(tag -> tag.getSourceSection().getCharacters().toString())
                .toList();
    }

    private static ProtosClosureValue semanticClosure(
            CanonicalClosure definition,
            ProtosClosureExecutionPlan plan,
            ProtosActivation creator) {
        return new ProtosClosureValue(
                definition,
                creator.lexicalContextsForClosureCapture(),
                creator.receiver(),
                creator.methodHome().orElse(null),
                creator.returnHome().orElse(null),
                creator.prelude().orElseThrow(),
                plan);
    }

    private static CanonicalClosure closureDefinition(String characters) {
        return assertInstanceOf(
                CanonicalClosure.class,
                canonicalize(characters).expressions().get(0));
    }

    private static CanonicalSequence canonicalize(String characters) {
        return (CanonicalSequence)
                new Canonicalizer()
                        .canonicalize(new ProtosParser(characters).parseProgram());
    }

    private static ProtosPrelude core() throws Exception {
        return new ProtosCoreBootstrap().bootstrap(Path.of("protos", "lib", "core"));
    }

    private static LanguageScope languageScope() {
        Context context = Context.newBuilder(ProtosLanguage.ID).build();
        context.initialize(ProtosLanguage.ID);
        context.enter();
        return new LanguageScope(context, LANGUAGE_REF.get(null));
    }

    private record LanguageScope(Context context, ProtosLanguage language)
            implements AutoCloseable {
        @Override
        public void close() {
            context.leave();
            context.close();
        }
    }

    private static ProtosActivation activationOf(ContinuationResult continuation) {
        Object[] arguments = continuation.getFrame().getArguments();
        if (arguments.length == 0) {
            throw new AssertionError("ContinuationResult frame has no invocation activation");
        }
        return assertInstanceOf(ProtosActivation.class, arguments[0]);
    }

}
