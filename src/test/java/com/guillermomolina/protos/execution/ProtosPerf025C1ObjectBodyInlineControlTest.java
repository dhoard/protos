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

import com.guillermomolina.protos.runtime.ProtosIntegerValue;
import com.guillermomolina.protos.runtime.ProtosPrelude;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * PERF025-C1b (PLAT041 C′) focal evidence: an object-construction body is an
 * inline resumable region of its enclosing root. Suspension inside the body
 * resumes exactly once through the enclosing root's own continuation, and
 * Error signaling/handler selection, ensure, and non-local return cross the
 * inline body exactly as they crossed the former object-body root.
 */
final class ProtosPerf025C1ObjectBodyInlineControlTest {
    private static final Path CORE = Path.of("protos", "lib", "core");

    @Test
    void objectBodySuspendsAndResumesExactlyOnceInsideEnclosingRoot() throws Exception {
        assertInteger(
                811,
                """
                work: () => {
                    count: 0
                    entered: 0
                    enter: () => {
                        entered = entered + 1
                    }
                    dependency: (() => 7).future()
                    built: {
                        before: 1
                        entering: enter()
                        waited: dependency.value()
                        after: waited + before
                        bump: () => {
                            count = count + 1
                        }
                    }
                    built.bump()
                    built.after * 100 + count * 10 + entered
                }
                work.future().value()
                """);
    }

    @Test
    void nestedObjectBodiesSelectTheirOwnConstructionActivation() throws Exception {
        assertInteger(
                21,
                """
                outer: {
                    name: 1
                    inner: {
                        name: 2
                        own: name
                    }
                    own: name
                }
                outer.inner.own * 10 + outer.own
                """);
    }

    @Test
    void nonLocalReturnCrossesInlineObjectBody() throws Exception {
        assertInteger(
                42,
                """
                f: () => {
                    ignored: {
                        x: ^42
                    }
                    0
                }
                f()
                """);
    }

    @Test
    void errorSignaledInObjectBodyReachesEnclosingHandler() throws Exception {
        assertInteger(
                43,
                """
                Error.handle(() => {
                    ignored: {
                        x: Error().signal()
                    }
                    0
                }, (caught) => 43)
                """);
    }

    @Test
    void handlerInstalledInsideObjectBodyHandlesItsOwnBody() throws Exception {
        assertInteger(
                44,
                """
                built: {
                    x: Error.handle(() => Error().signal(), (caught) => 44)
                }
                built.x
                """);
    }

    @Test
    void ensureRunsOnceWhenNonLocalReturnCrossesObjectBody() throws Exception {
        assertInteger(
                421,
                """
                count: 0
                f: () => {
                    (() => {
                        ignored: {
                            x: ^42
                        }
                        0
                    }).ensure(() => {
                        count = count + 1
                    })
                    0
                }
                f() * 10 + count
                """);
    }

    private static void assertInteger(long expected, String source) throws Exception {
        ProtosPrelude prelude = new ProtosCoreBootstrap().bootstrap(CORE);
        ProtosExecutionOutcome outcome =
                ProtosTestExecutionSupport.execute(
                        "perf025-c1b-object-body-inline-control.protos",
                        source,
                        prelude.newModuleActivation());
        assertEquals(
                ProtosExecutionOutcome.State.COMPLETED,
                outcome.state(),
                () -> "outcome=" + outcome.state() + ", error=" + outcome.error());
        ProtosIntegerValue integer =
                assertInstanceOf(ProtosIntegerValue.class, outcome.value());
        assertEquals(Long.toString(expected), com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerDecimalText(integer));
    }
}
