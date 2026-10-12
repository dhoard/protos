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

import com.guillermomolina.protos.runtime.ProtosArrayValue;
import com.guillermomolina.protos.runtime.ProtosBooleanValue;
import com.guillermomolina.protos.runtime.ProtosIntegerValue;
import com.guillermomolina.protos.runtime.ProtosPrelude;
import com.guillermomolina.protos.runtime.ProtosSignalException;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ProtosArrayMatchProtocolTest {
    private static final Path CORE = Path.of("protos", "lib", "core");

    private static ProtosPrelude core() throws Exception {
        return new ProtosCoreBootstrap().bootstrap(CORE);
    }

    private static Object evaluate(ProtosPrelude prelude, String source) {
        return ProtosTestExecutionSupport.evaluate(
                source,
                prelude.newModuleActivation());
    }

    @Test
    void matchesOnlyEligibleArraysOfExactLength() throws Exception {
        ProtosPrelude prelude = core();

        assertSame(
                ProtosBooleanValue.TRUE,
                evaluate(prelude, "[].match([])"));
        assertSame(
                ProtosBooleanValue.TRUE,
                evaluate(prelude, "[1, 2].match([1, 2])"));
        assertSame(
                ProtosBooleanValue.FALSE,
                evaluate(prelude, "[1, 2].match([1])"));
        assertSame(
                ProtosBooleanValue.FALSE,
                evaluate(prelude, "[1].match(1)"));
    }

    @Test
    void rejectsLengthMismatchBeforeInvokingAnyChild() throws Exception {
        ProtosPrelude prelude = core();

        Object result =
                evaluate(
                        prelude,
                        """
                        Bad: {
                            match: (subject) => null
                        }

                        [Bad].match([])
                        """);

        assertSame(ProtosBooleanValue.FALSE, result);
    }

    @Test
    void invokesReachedChildrenLeftToRightExactlyOnce() throws Exception {
        ProtosPrelude prelude = core();

        Object result =
                evaluate(
                        prelude,
                        """
                        state: {
                            count: 0
                        }

                        First: {
                            match: (subject) => {
                                state.count = state.count + 1
                                state.count == 1
                            }
                        }

                        Second: {
                            match: (subject) => {
                                state.count = state.count + 1
                                state.count == 2
                            }
                        }

                        [First, Second].match([0, 0])
                        """);

        assertSame(ProtosBooleanValue.TRUE, result);
    }

    @Test
    void stopsAtFirstMismatchWithoutInvokingLaterChildren() throws Exception {
        ProtosPrelude prelude = core();

        Object result =
                evaluate(
                        prelude,
                        """
                        Bad: {
                            match: (subject) => null
                        }

                        [1, Bad].match([2, 0])
                        """);

        assertSame(ProtosBooleanValue.FALSE, result);
    }

    @Test
    void composesCapturesWithoutFlatteningCapturedArrays() throws Exception {
        ProtosPrelude prelude = core();

        ProtosArrayValue captures =
                assertInstanceOf(
                        ProtosArrayValue.class,
                        evaluate(
                                prelude,
                                "[Capture, Capture].match([1, [2, 3]])"));

        assertEquals(2, captures.indexedSize());

        ProtosIntegerValue first =
                assertInstanceOf(
                        ProtosIntegerValue.class,
                        captures.indexedAt(0));
        assertEquals(String.valueOf("1"), com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerDecimalText(first));

        ProtosArrayValue nested =
                assertInstanceOf(
                        ProtosArrayValue.class,
                        captures.indexedAt(1));
        assertEquals(2, nested.indexedSize());
    }

    @Test
    void snapshotsMatcherAndSubjectArraysBeforeInvokingChildren() throws Exception {
        ProtosPrelude prelude = core();

        Object result =
                evaluate(
                        prelude,
                        """
                        holder: Map()

                        Second: {
                            match: (value) => value == 2
                        }

                        Replacement: {
                            match: (value) => false
                        }

                        First: {
                            match: (value) => {
                                currentMatcher: holder["matcher"]
                                currentSubject: holder["subject"]

                                currentMatcher[1] = Replacement
                                currentSubject[1] = 99
                                true
                            }
                        }

                        matcher: [First, Second]
                        subject: [1, 2]

                        holder["matcher"] = matcher
                        holder["subject"] = subject

                        matcher.match(subject)
                        """);

        assertSame(ProtosBooleanValue.TRUE, result);
    }

    @Test
    void consumesEachChildCaptureCarrierBeforeContinuing() throws Exception {
        ProtosPrelude prelude = core();

        ProtosArrayValue captures =
                assertInstanceOf(
                        ProtosArrayValue.class,
                        evaluate(
                                prelude,
                                """
                                carrier: [1]

                                First: {
                                    match: (subject) => carrier
                                }

                                Second: {
                                    match: (subject) => {
                                        carrier[0] = 99
                                        true
                                    }
                                }

                                [First, Second].match([0, 0])
                                """));

        assertEquals(1, captures.indexedSize());

        ProtosIntegerValue captured =
                assertInstanceOf(
                        ProtosIntegerValue.class,
                        captures.indexedAt(0));
        assertEquals(String.valueOf("1"), com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerDecimalText(captured));
    }

    @Test
    void rejectsInvalidReachedMatcherOutcomes() throws Exception {
        ProtosPrelude prelude = core();

        assertThrows(
                ProtosSignalException.class,
                () ->
                        evaluate(
                                prelude,
                                """
                                Bad: {
                                    match: (subject) => null
                                }

                                [Bad].match([1])
                                """));

        assertThrows(
                ProtosSignalException.class,
                () ->
                        evaluate(
                                prelude,
                                """
                                Bad: {
                                    match: (subject) => []
                                }

                                [Bad].match([1])
                                """));
    }

    @Test
    void rejectsInheritedArrayBehaviorOnIneligibleReceiver() throws Exception {
        ProtosPrelude prelude = core();

        assertThrows(
                ProtosSignalException.class,
                () ->
                        evaluate(
                                prelude,
                                """
                                fake: Array {
                                }

                                fake.match([])
                                """));
    }
}
