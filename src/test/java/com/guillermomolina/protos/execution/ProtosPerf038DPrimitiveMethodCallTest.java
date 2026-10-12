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

import com.guillermomolina.protos.runtime.ProtosActivation;
import com.guillermomolina.protos.runtime.ProtosArrayValue;
import com.guillermomolina.protos.runtime.ProtosPrelude;
import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.source.Source;
import java.nio.file.Path;
import java.util.List;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;

/**
 * PERF038-D primitive-method-call convergence. A root-level send passes a
 * lazy caller reference, so a compact method root that inherits all of its
 * provenance hands its own caller to a guarded ordinary send instead of
 * materializing itself; and a root-level captured materialized read keeps a
 * monomorphic owner-frame cache guarded by the owner authority's
 * installation. These programs check that handler selection, Context
 * observation, rebinding and per-owner captures are unchanged.
 */
final class ProtosPerf038DPrimitiveMethodCallTest {
    private static final Path CORE = Path.of("protos", "lib", "core");

    /**
     * Compact method roots forward to a callee that signals; the handler
     * installed two roots outward is still selected, before and after the
     * sites are warm, and ordinary forwarding is unaffected.
     */
    @Test
    void provenanceCallerKeepsHandlerSelection() throws Exception {
        withCore(module -> {
            List<Object> results =
                    elements(evaluate(
                            "leaf: {\n"
                                    + "    identity: (v) => { v }\n"
                                    + "    fail: (v) => { Error().signal() }\n"
                                    + "}\n"
                                    + "middle: {\n"
                                    + "    forward: (v) => { leaf.identity(v) }\n"
                                    + "    failing: (v) => { leaf.fail(v) }\n"
                                    + "}\n"
                                    + "top: {\n"
                                    + "    run: (v) => {\n"
                                    + "        Error.handle(() => { middle.failing(v) },"
                                    + " (caught) => { 70 + v })\n"
                                    + "    }\n"
                                    + "}\n"
                                    + "warm: [middle.forward(1), middle.forward(2), top.run(3),"
                                    + " top.run(4)]\n"
                                    + "[warm[0], warm[1], warm[2], warm[3], middle.forward(5),"
                                    + " top.run(6)]\n",
                            "perf038d-provenance.protos", module));
            assertIntegers(results, 1, 2, 73, 74, 5, 76);
        });
        System.out.println("PERF038D_PROVENANCE_CALLER=PASS");
        System.out.println("PERF038D_HANDLER_SELECTION=PASS");
    }

    /**
     * The callee observes its Context after a provenance-caller send, and the
     * compact caller observes its own Context after the send returned.
     */
    @Test
    void contextObservationAroundProvenanceCallerSend() throws Exception {
        withCore(module -> {
            List<Object> results =
                    elements(evaluate(
                            "leaf: { observe: (v) => {\n"
                                    + "    seen: context\n"
                                    + "    v\n"
                                    + "} }\n"
                                    + "middle: { forward: (v) => {\n"
                                    + "    r: leaf.observe(v)\n"
                                    + "    here: context\n"
                                    + "    r + 10\n"
                                    + "} }\n"
                                    + "[middle.forward(1), middle.forward(2), middle.forward(3)]\n",
                            "perf038d-context.protos", module));
            assertIntegers(results, 11, 12, 13);
        });
        System.out.println("PERF038D_CONTEXT_OBSERVATION=PASS");
    }

    /**
     * The captured read of a module binding follows its rebinding after the
     * site is warm, and a read whose owner differs per enclosing invocation
     * selects each invocation's own owner.
     */
    @Test
    void capturedOwnerFrameCacheFollowsRebindingAndOwnerChange() throws Exception {
        withCore(module -> {
            List<Object> results =
                    elements(evaluate(
                            "receiver: { identity: (value) => { value } }\n"
                                    + "run: () => { receiver.identity(1) }\n"
                                    + "warm: [run(), run()]\n"
                                    + "receiver = { identity: (value) => { value + 10 } }\n"
                                    + "make: (seed) => {\n"
                                    + "    target: { identity: (value) => { value + seed } }\n"
                                    + "    () => { target.identity(1) }\n"
                                    + "}\n"
                                    + "a: make(100)\n"
                                    + "b: make(200)\n"
                                    + "[warm[0], warm[1], run(), run(), a(), a(), b(), a(), b()]\n",
                            "perf038d-owner.protos", module));
            assertIntegers(results, 1, 1, 11, 11, 101, 101, 201, 101, 201);
        });
        System.out.println("PERF038D_OWNER_FRAME_CACHE=PASS");
        System.out.println("PERF038D_OWNER_CHANGE=PASS");
    }

    /**
     * A Closure created in an inline callback reads the callback's binding
     * while each iteration runs (a different owner per iteration) and after
     * the callback's bindings became durable, when its owner's frame
     * authority has been replaced.
     */
    @Test
    void capturedReadSurvivesOwnerChangeAndAuthorityReplacement() throws Exception {
        withCore(module -> {
            List<Object> results =
                    elements(evaluate(
                            "run: () => {\n"
                                    + "    sum: 0\n"
                                    + "    last: () => { 0 }\n"
                                    + "    Array(10, 20, 30).each((element) => {\n"
                                    + "        copy: element\n"
                                    + "        reader: () => { copy }\n"
                                    + "        sum = sum + reader() + reader()\n"
                                    + "        last = reader\n"
                                    + "    })\n"
                                    + "    [sum, last(), last()]\n"
                                    + "}\n"
                                    + "first: run()\n"
                                    + "second: run()\n"
                                    + "[first[0], first[1], second[0], second[2]]\n",
                            "perf038d-durable.protos", module));
            assertIntegers(results, 120, 30, 120, 30);
        });
        System.out.println("PERF038D_OWNER_AUTHORITY_REPLACEMENT=PASS");
    }

    private static void assertIntegers(List<Object> results, long... expected) {
        assertEquals(expected.length, results.size());
        for (int index = 0; index < expected.length; index++) {
            assertEquals(Long.toString(expected[index]), integerValue(results.get(index)));
        }
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
