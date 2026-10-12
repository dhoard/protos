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

import com.guillermomolina.protos.runtime.ProtosActivation;
import com.guillermomolina.protos.runtime.ProtosArrayValue;
import com.guillermomolina.protos.runtime.ProtosBooleanValue;
import com.guillermomolina.protos.runtime.ProtosPrelude;
import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.source.Source;
import java.nio.file.Path;
import java.util.List;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;

/**
 * PERF038-C compact call specialization. The root-level parameter, current
 * activation, root-local and captured-read operations split their compact
 * path and their published-activation path into separate specializations,
 * and both Bytecode roots compile guest-exception interception only after a
 * guest exception has crossed them. These programs move the same operation
 * sites between both frame states and drive Errors across warm roots, and
 * check that bindings, defaults, D179 retargeting, arity Errors, handler
 * selection and non-local return are unchanged.
 */
final class ProtosPerf038CCompactCallSpecializationTest {
    private static final Path CORE = Path.of("protos", "lib", "core");

    /**
     * The parameter sites of {@code c} first run in compact frames (the
     * default of {@code b} is skipped), then after the default of {@code b}
     * has published the activation, then compactly again.
     */
    @Test
    void parameterSitesFollowEachInvocationsFrameState() throws Exception {
        withCore(module -> {
            List<Object> results =
                    elements(evaluate(
                            "probe: { m: (a, b = context, c = 5) => { [a, c] } }\n"
                                    + "warm: [probe.m(1, 2, 3), probe.m(4, 6, 7)]\n"
                                    + "late: probe.m(8)\n"
                                    + "again: probe.m(9, 10, 11)\n"
                                    + "[warm[0], warm[1], late, again]\n",
                            "perf038c-parameters.protos", module));
            assertPair(results.get(0), 1, 3);
            assertPair(results.get(1), 4, 7);
            assertPair(results.get(2), 8, 5);
            assertPair(results.get(3), 9, 11);
        });
        System.out.println("PERF038C_PARAMETER_SPECIALIZATION=PASS");
        System.out.println("PERF038C_LATE_MATERIALIZATION=PASS");
    }

    /**
     * One captured-read site of {@code reader} runs in compact frames
     * ({@code c} supplied) and in published frames (the default observes
     * the Context), before and after a nearer late creation of {@code x}
     * retargets it (D179 C0).
     */
    @Test
    void capturedRootReadRetargetsInBothFrameStates() throws Exception {
        withCore(module -> {
            List<Object> results =
                    elements(evaluate(
                            "outer: (seed) => {\n"
                                    + "    x: seed\n"
                                    + "    middle: () => {\n"
                                    + "        reader: (c = context) => { x }\n"
                                    + "        before: [reader(0), reader()]\n"
                                    + "        x: \"middle\"\n"
                                    + "        [before[0], before[1], reader(0), reader()]\n"
                                    + "    }\n"
                                    + "    middle()\n"
                                    + "}\n"
                                    + "check: (r, seed) => {\n"
                                    + "    (r[0] == seed) && (r[1] == seed)"
                                    + " && (r[2] == \"middle\") && (r[3] == \"middle\")\n"
                                    + "}\n"
                                    + "[check(outer(\"a\"), \"a\"), check(outer(\"b\"), \"b\"),"
                                    + " check(outer(\"c\"), \"c\")]\n",
                            "perf038c-captured.protos", module));
            for (Object result : results) {
                assertSame(ProtosBooleanValue.TRUE, result);
            }
        });
        System.out.println("PERF038C_CAPTURED_READ_SPECIALIZATION=PASS");
        System.out.println("PERF038C_D179_RETARGETING=PASS");
    }

    /**
     * Guest Errors cross callee and caller roots that already ran without
     * any Error: an arity Error from a warm compact callee, an explicit
     * signal from a method, and a non-local return through a handled body,
     * after which the same sites still run ordinarily.
     */
    @Test
    void guestErrorsCrossWarmRootsAndAreHandled() throws Exception {
        withCore(module -> {
            List<Object> results =
                    elements(evaluate(
                            "receiver: { identity: (value) => { value } }\n"
                                    + "guarded: (n) => {\n"
                                    + "    Error.handle(() => { receiver.identity(n) }, (caught) => { 99 })\n"
                                    + "}\n"
                                    + "failing: (n) => {\n"
                                    + "    Error.handle(() => { receiver.identity(n, n) },"
                                    + " (caught) => { 99 })\n"
                                    + "}\n"
                                    + "signalling: { fail: (value) => { Error().signal() } }\n"
                                    + "early: (n) => {\n"
                                    + "    Error.handle(() => { ^ receiver.identity(n) },"
                                    + " (caught) => { 0 })\n"
                                    + "    98\n"
                                    + "}\n"
                                    + "warm: [guarded(1), guarded(2)]\n"
                                    + "[warm[0], warm[1], failing(3), failing(4),\n"
                                    + " Error.handle(() => { signalling.fail(1) }, (caught) => { 77 }),\n"
                                    + " guarded(5), early(6), receiver.identity(7)]\n",
                            "perf038c-errors.protos", module));
            long[] expected = {1, 2, 99, 99, 77, 5, 6, 7};
            assertEquals(expected.length, results.size());
            for (int index = 0; index < expected.length; index++) {
                assertEquals(Long.toString(expected[index]), integerValue(results.get(index)));
            }
        });
        System.out.println("PERF038C_ERROR_INTERCEPTION=PASS");
        System.out.println("PERF038C_WRONG_ARITY=PASS");
        System.out.println("PERF038C_NON_LOCAL_RETURN=PASS");
    }

    private static void assertPair(Object array, long first, long second) {
        List<Object> pair = elements(array);
        assertEquals(2, pair.size());
        assertEquals(Long.toString(first), integerValue(pair.get(0)));
        assertEquals(Long.toString(second), integerValue(pair.get(1)));
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
