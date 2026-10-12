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
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.guillermomolina.protos.runtime.ProtosActivation;
import com.guillermomolina.protos.runtime.ProtosArrayValue;
import com.guillermomolina.protos.runtime.ProtosBooleanValue;
import com.guillermomolina.protos.runtime.ProtosClosureValue;
import com.guillermomolina.protos.runtime.ProtosExecutionContextValue;
import com.guillermomolina.protos.runtime.ProtosPrelude;
import com.guillermomolina.protos.runtime.ProtosSignalException;
import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.source.Source;
import java.nio.file.Path;
import java.util.List;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;

/**
 * PERF038-B compact method-call graph specialization. Root-level frame
 * operations discriminate a compact source call from a materialized one by
 * frame argument 0 alone, and {@code BindClosureFrameParameter} is split into
 * a guarded compact slot store and a boundary-isolated authoritative
 * fallback. These programs drive ordinary monomorphic sends through both
 * specializations of the same binding site and check that method selection,
 * receiver/method home, lookup invalidation, arity, Context observation,
 * extracted Closure identity and non-local return are unchanged.
 */
final class ProtosPerf038BCompactMethodCallTest {
    private static final Path CORE = Path.of("protos", "lib", "core");

    @Test
    void monomorphicMethodCallReturnsTheSuppliedArgument() throws Exception {
        withCore(module -> {
            List<Object> results =
                    elements(evaluate(
                            "receiver: { identity: (value) => { value } }\n"
                                    + "run: (n) => { receiver.identity(n) }\n"
                                    + "[run(1), run(2), run(3), run(4)]\n",
                            "perf038b-monomorphic.protos", module));
            for (int index = 0; index < 4; index++) {
                assertEquals(Long.toString(index + 1), integerValue(results.get(index)));
            }
        });
        System.out.println("PERF038B_MONOMORPHIC_METHOD_CALL=PASS");
    }

    @Test
    void inheritedMethodAndSuperKeepReceiverAndMethodHome() throws Exception {
        withCore(module -> {
            List<Object> results =
                    elements(evaluate(
                            "base: {\n"
                                    + "    value: 1\n"
                                    + "    read: (delta) => { value + delta }\n"
                                    + "}\n"
                                    + "middle: base {\n"
                                    + "    read: (delta) => { super.read(delta) + 10 }\n"
                                    + "}\n"
                                    + "leaf: middle { value: 7 }\n"
                                    + "run: (receiver, delta) => { receiver.read(delta) }\n"
                                    + "[run(leaf, 1), run(leaf, 2), run(base, 3), run(middle, 4)]\n",
                            "perf038b-super.protos", module));
            assertEquals("18", integerValue(results.get(0)));
            assertEquals("19", integerValue(results.get(1)));
            assertEquals("4", integerValue(results.get(2)));
            assertEquals("15", integerValue(results.get(3)));
        });
        System.out.println("PERF038B_INHERITANCE_AND_SUPER=PASS");
        System.out.println("PERF038B_RECEIVER_CHANGE=PASS");
    }

    @Test
    void replacingTheSelectedMethodInvalidatesTheSendSite() throws Exception {
        withCore(module -> {
            List<Object> results =
                    elements(evaluate(
                            "receiver: { pick: (value) => { value } }\n"
                                    + "run: (n) => { receiver.pick(n) }\n"
                                    + "before: [run(1), run(2)]\n"
                                    + "receiver.pick = (value) => { value + 100 }\n"
                                    + "[before[0], before[1], run(3), run(4)]\n",
                            "perf038b-invalidation.protos", module));
            assertEquals("1", integerValue(results.get(0)));
            assertEquals("2", integerValue(results.get(1)));
            assertEquals("103", integerValue(results.get(2)));
            assertEquals("104", integerValue(results.get(3)));
        });
        System.out.println("PERF038B_SELECTED_METHOD_INVALIDATION=PASS");
        System.out.println("PERF038B_DYNAMIC_SEND_FALLBACK=PASS");
    }

    @Test
    void wrongArityAfterCompactCallsStillSignals() throws Exception {
        withCore(module -> {
            evaluate(
                    "receiver: { identity: (value) => { value } }\n"
                            + "run: (n) => { receiver.identity(n) }\n"
                            + "run(1)\nrun(2)\n",
                    "perf038b-arity-warm.protos", module);
            assertThrows(
                    ProtosSignalException.class,
                    () -> evaluate("receiver.identity()\n", "perf038b-arity-few.protos", module));
            assertThrows(
                    ProtosSignalException.class,
                    () -> evaluate(
                            "receiver.identity(1, 2)\n", "perf038b-arity-many.protos", module));
            assertEquals("5", integerValue(evaluate("run(5)\n", "perf038b-arity-after.protos", module)));
        });
        System.out.println("PERF038B_WRONG_ARITY=PASS");
    }

    /**
     * The binding site of {@code c} first runs in compact unobserved frames
     * (the default of {@code b} is not evaluated), then in frames whose
     * Context the default of {@code b} has already observed, so the same site
     * takes the authoritative fallback after its compact specialization.
     */
    @Test
    void lateMaterializationAtAWarmBindingSiteKeepsBindingsAndContext() throws Exception {
        withCore(module -> {
            List<Object> results =
                    elements(evaluate(
                            "probe: { m: (a, b = context, c = b) => { [a, b, c, context] } }\n"
                                    + "warm: [probe.m(1, 2), probe.m(3, 4)]\n"
                                    + "late: probe.m(5)\n"
                                    + "again: probe.m(6, 7)\n"
                                    + "[warm[0], warm[1], late, again]\n",
                            "perf038b-late.protos", module));

            List<Object> first = elements(results.get(0));
            assertEquals("1", integerValue(first.get(0)));
            assertEquals("2", integerValue(first.get(1)));
            assertEquals("2", integerValue(first.get(2)));
            assertInstanceOf(ProtosExecutionContextValue.class, first.get(3));

            List<Object> late = elements(results.get(2));
            assertEquals("5", integerValue(late.get(0)));
            ProtosExecutionContextValue observed =
                    assertInstanceOf(ProtosExecutionContextValue.class, late.get(1));
            assertSame(observed, late.get(2), "c is bound to the same observed Context");
            assertSame(observed, late.get(3), "one Context per invocation");

            List<Object> again = elements(results.get(3));
            assertEquals("7", integerValue(again.get(2)));
            assertNotSame(observed, again.get(3), "a fresh Context per invocation");
        });
        System.out.println("PERF038B_LATE_MATERIALIZATION=PASS");
        System.out.println("PERF038B_CONTEXT_OBSERVATION=PASS");
    }

    @Test
    void extractedMethodFollowsExtractionIdentity() throws Exception {
        withCore(module -> {
            List<Object> results =
                    elements(evaluate(
                            "receiver: { identity: (value) => { value } }\n"
                                    + "receiver.identity(1)\n"
                                    + "first: receiver.identity\n"
                                    + "second: receiver.identity\n"
                                    + "alias: first\n"
                                    + "[first, second, first === second, first(9), alias === first]\n",
                            "perf038b-extracted.protos", module));
            ProtosClosureValue first = assertInstanceOf(ProtosClosureValue.class, results.get(0));
            // CALLABLES.md section 11: every extraction is a fresh Closure;
            // only an alias of one extraction keeps its identity.
            assertNotSame(first, assertInstanceOf(ProtosClosureValue.class, results.get(1)));
            assertSame(ProtosBooleanValue.FALSE, results.get(2));
            assertSame(ProtosBooleanValue.TRUE, results.get(4));
            assertEquals("9", integerValue(results.get(3)));
        });
        System.out.println("PERF038B_EXTRACTED_CLOSURE_IDENTITY=PASS");
    }

    @Test
    void nonLocalReturnFromAMethodArgumentBlock() throws Exception {
        withCore(module -> {
            assertEquals("7", integerValue(evaluate(
                            "receiver: { apply: (block) => { block() + 1000 } }\n"
                                    + "outer: (n) => {\n"
                                    + "    receiver.apply(() => { ^ n })\n"
                                    + "    99\n"
                                    + "}\n"
                                    + "outer(6)\nouter(7)\n",
                            "perf038b-non-local.protos", module)));
            assertEquals("1001", integerValue(evaluate(
                            "receiver.apply(() => { 1 })\n", "perf038b-local.protos", module)));
        });
        System.out.println("PERF038B_NON_LOCAL_RETURN=PASS");
    }

    private interface CoreTest {
        void run(ProtosActivation module) throws Exception;
    }

    private static void withCore(CoreTest test) throws Exception {
        try (Context context = Context.newBuilder(ProtosLanguage.ID).build()) {
            context.initialize(ProtosLanguage.ID);
            context.enter();
            try {
                ProtosPrelude prelude = new ProtosCoreBootstrap().bootstrap(CORE);
                test.run(prelude.newModuleActivation());
            } finally {
                context.leave();
            }
        }
    }

    private static Object evaluate(String characters, String name, ProtosActivation activation) {
        Source source =
                Source.newBuilder(ProtosLanguage.ID, characters, name)
                        .mimeType(ProtosLanguage.MIME_TYPE)
                        .build();
        CallTarget target = ProtosLanguageContext.current().parsePublic(source);
        return target.call(activation);
    }

    private static List<Object> elements(Object array) {
        return assertInstanceOf(ProtosArrayValue.class, array).indexedSnapshot();
    }

    private static String integerValue(Object value) {
        return com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerDecimalText(value);
    }
}
