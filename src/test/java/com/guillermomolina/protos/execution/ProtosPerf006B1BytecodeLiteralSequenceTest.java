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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.guillermomolina.protos.parser.ProtosParser;
import com.guillermomolina.protos.parser.ast.SurfaceSequence;
import com.guillermomolina.protos.runtime.ProtosBooleanValue;
import com.guillermomolina.protos.runtime.ProtosFloatValue;
import com.guillermomolina.protos.runtime.ProtosNullValue;
import com.guillermomolina.protos.runtime.ProtosStringValue;
import com.guillermomolina.protos.semantic.Canonicalizer;
import com.guillermomolina.protos.semantic.ast.CanonicalSequence;
import com.guillermomolina.protos.semantic.ast.CanonicalSpread;
import com.guillermomolina.protos.source.SourceSpan;
import com.oracle.truffle.api.TruffleLanguage.LanguageReference;
import com.oracle.truffle.api.source.Source;
import com.oracle.truffle.api.source.SourceSection;
import java.util.List;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;

final class ProtosPerf006B1BytecodeLiteralSequenceTest {
    private static final LanguageReference<ProtosLanguage> LANGUAGE_REF =
            LanguageReference.create(ProtosLanguage.class);

    /**
     * I091: an Integer literal beyond the signed-64 range is an ordinary guest object minted with
     * the executing Prelude, so it is evaluated where one exists rather than in a bare root.
     */
    @Test
    void largeIntegerLiteralIsMintedByTheExecutingPrelude() throws Exception {
        try (ProtosHostedExecutionTestFixture hosted =
                ProtosHostedExecutionTestFixture.open(
                        new ProtosCoreBootstrap()
                                .bootstrap(java.nio.file.Path.of("protos", "lib", "core")))) {
            Object large =
                    hosted.evaluatePersistent(
                            "<perf006-b1-large-literal>", "92233720368547758081234567890");
            assertEquals("92233720368547758081234567890", com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerDecimalText(large));
            assertInstanceOf(
                    com.guillermomolina.protos.runtime.ProtosLargeIntegerValue.class, large);
        }
    }

    @Test
    void canonicalLiteralAndSequenceSubsetExecutesThroughBytecode() throws Exception {
        try (Context context = Context.newBuilder(ProtosLanguage.ID).build()) {
            context.initialize(ProtosLanguage.ID);
            context.enter();
            try {
                assertSame(ProtosBooleanValue.TRUE, executeBytecodeValue("true"));
                assertSame(ProtosBooleanValue.FALSE, executeBytecodeValue("false"));
                assertSame(ProtosNullValue.INSTANCE, executeBytecodeValue("null"));

                assertEquals(
                        "hello",
                        assertInstanceOf(
                                        ProtosStringValue.class,
                                        executeBytecodeValue("\"hello\""))
                                .value());


                assertEquals(Long.toString(255), com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerDecimalText(executeBytecodeValue("0xFF")));

                assertEquals(
                        Double.doubleToRawLongBits(1.25d),
                        Double.doubleToRawLongBits(
                                assertInstanceOf(
                                                ProtosFloatValue.class,
                                                executeBytecodeValue("1.25"))
                                        .value()));

                assertEquals(Long.toString(42), com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerDecimalText(executeBytecodeValue(
                                                "\"discarded\"\nfalse\n0x2A")));
            } finally {
                context.leave();
            }
        }

        System.out.println("PERF006_B1_LITERAL_BYTECODE=PASS");
        System.out.println("PERF006_B1_SEQUENCE_BYTECODE=PASS");
    }

    @Test
    void emptyCanonicalSequenceRetainsCanonicalNullResult() throws Exception {
        try (Context context = Context.newBuilder(ProtosLanguage.ID).build()) {
            context.initialize(ProtosLanguage.ID);
            context.enter();
            try {
                ProtosLanguage language = LANGUAGE_REF.get(null);
                Source source =
                        Source.newBuilder(
                                        ProtosLanguage.ID,
                                        "",
                                        "perf006-b1-empty.protos")
                                .build();

                CanonicalSequence sequence =
                        new CanonicalSequence(List.of(), new SourceSpan(0, 0));

                Object bytecode =
                        new CanonicalToBytecodeLowerer(language, source, ProtosExecutionMode.NORMAL)
                                .lower(sequence)
                                .call();

                assertSame(ProtosNullValue.INSTANCE, bytecode);
            } finally {
                context.leave();
            }
        }

        System.out.println("PERF006_B1_EMPTY_SEQUENCE=PASS");
    }

    @Test
    void bytecodeRootRetainsExactLazySourceOwnership() throws Exception {
        try (Context context = Context.newBuilder(ProtosLanguage.ID).build()) {
            context.initialize(ProtosLanguage.ID);
            context.enter();
            try {
                ProtosLanguage language = LANGUAGE_REF.get(null);
                String characters = "1\n2\n3";
                Source source =
                        Source.newBuilder(
                                        ProtosLanguage.ID,
                                        characters,
                                        "perf006-b1-source.protos")
                                .build();
                CanonicalSequence sequence = canonicalize(characters);

                ProtosSemanticBytecodeRootNode root =
                        new CanonicalToBytecodeLowerer(language, source, ProtosExecutionMode.NORMAL)
                                .lowerRoot(sequence);

                SourceSection section = root.ensureSourceSection();
                assertSame(source, section.getSource());
                assertEquals(sequence.span().startOffset(), section.getCharIndex());
                assertEquals(sequence.span().length(), section.getCharLength());
                assertEquals(
                        characters.substring(
                                sequence.span().startOffset(),
                                sequence.span().endOffset()),
                        section.getCharacters().toString());
            } finally {
                context.leave();
            }
        }

        System.out.println("PERF006_B1_ROOT_SOURCE_SECTION=PASS");
    }

    @Test
    void unsupportedCanonicalExpressionFailsClosedBeforePartialLowering() throws Exception {
        try (Context context = Context.newBuilder(ProtosLanguage.ID).build()) {
            context.initialize(ProtosLanguage.ID);
            context.enter();
            try {
                ProtosLanguage language = LANGUAGE_REF.get(null);
                CanonicalSequence supported = canonicalize("1");
                Source source =
                        Source.newBuilder(
                                        ProtosLanguage.ID,
                                        "1",
                                        "perf006-b1-unsupported-root-spread.protos")
                                .build();

                CanonicalSequence sequence =
                        new CanonicalSequence(
                                List.of(
                                        new CanonicalSpread(
                                                supported.expressions().get(0),
                                                supported.expressions().get(0).span())),
                                supported.span());

                UnsupportedOperationException failure =
                        assertThrows(
                                UnsupportedOperationException.class,
                                () ->
                                        new CanonicalToBytecodeLowerer(
                                                        language, source, ProtosExecutionMode.NORMAL)
                                                .lower(sequence));

                String unsupportedKind =
                        sequence.expressions().get(0).getClass().getSimpleName();
                assertTrue(
                        failure.getMessage().contains(unsupportedKind),
                        () ->
                                "fail-closed message did not report canonical kind "
                                        + unsupportedKind
                                        + ": "
                                        + failure.getMessage());
            } finally {
                context.leave();
            }
        }

        System.out.println("PERF006_B1_UNSUPPORTED_FAIL_CLOSED=PASS");
    }

    private static Object executeBytecodeValue(String characters) throws Exception {
        ProtosLanguage language = LANGUAGE_REF.get(null);
        Source source =
                Source.newBuilder(
                                ProtosLanguage.ID,
                                characters,
                                "perf006-b1-bytecode.protos")
                        .build();
        CanonicalSequence sequence = canonicalize(characters);
        return new CanonicalToBytecodeLowerer(language, source, ProtosExecutionMode.NORMAL)
                .lower(sequence)
                .call();
    }

    private static CanonicalSequence canonicalize(String characters) {
        SurfaceSequence surface =
                new ProtosParser(characters).parseProgram();
        return (CanonicalSequence)
                new Canonicalizer().canonicalize(surface);
    }
}
