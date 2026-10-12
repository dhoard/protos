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

class ProtosMapMatchProtocolTest {
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
    void matchesOpenSubsetsAndRejectsIneligibleSubjects() throws Exception {
        ProtosPrelude prelude = core();

        assertSame(
                ProtosBooleanValue.TRUE,
                evaluate(
                        prelude,
                        """
                        matcher: Map()
                        subject: Map()

                        matcher["a"] = 1
                        subject["a"] = 1
                        subject["extra"] = 2

                        matcher.match(subject)
                        """));

        assertSame(
                ProtosBooleanValue.FALSE,
                evaluate(
                        prelude,
                        """
                        matcher: Map()
                        subject: Map()

                        matcher["a"] = 1
                        subject["b"] = 1

                        matcher.match(subject)
                        """));

        assertSame(
                ProtosBooleanValue.TRUE,
                evaluate(prelude, "Map().match(Map())"));

        assertSame(
                ProtosBooleanValue.FALSE,
                evaluate(prelude, "Map().match([])"));
    }

    @Test
    void resolvesAllRequirementsBeforeInvokingAnyChild() throws Exception {
        ProtosPrelude prelude = core();

        Object result =
                evaluate(
                        prelude,
                        """
                        Bad: {
                            match: (subject) => null
                        }

                        matcher: Map()
                        subject: Map()

                        matcher["present"] = Bad
                        matcher["missing"] = 1

                        subject["present"] = 0

                        matcher.match(subject)
                        """);

        assertSame(ProtosBooleanValue.FALSE, result);
    }

    @Test
    void invokesChildrenInMatcherInsertionOrder() throws Exception {
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

                        matcher: Map()
                        subject: Map()

                        matcher["first"] = First
                        matcher["second"] = Second

                        subject["second"] = 0
                        subject["first"] = 0

                        matcher.match(subject)
                        """);

        assertSame(ProtosBooleanValue.TRUE, result);
    }

    @Test
    void snapshotsMatcherAndSubjectAssociationsBeforeChildren() throws Exception {
        ProtosPrelude prelude = core();

        Object result =
                evaluate(
                        prelude,
                        """
                        Replacement: {
                            match: (subject) => false
                        }

                        Second: {
                            match: (subject) => subject == 2
                        }

                        matcher: Map()
                        subject: Map()

                        First: {
                            match: (value) => {
                                matcher["second"] = Replacement
                                subject["second"] = 99
                                true
                            }
                        }

                        matcher["first"] = First
                        matcher["second"] = Second

                        subject["first"] = 1
                        subject["second"] = 2

                        matcher.match(subject)
                        """);

        assertSame(ProtosBooleanValue.TRUE, result);
    }

    @Test
    void usesQuerySideEqualityForSubjectAssociationSearch() throws Exception {
        ProtosPrelude prelude = core();

        Object result =
                evaluate(
                        prelude,
                        """
                        StoredBase: {
                            hash: () => 7
                            equalTo: (other) => false
                        }
                        Stored: StoredBase.alias("equalTo", "==")

                        QueryBase: {
                            hash: () => 7
                            equalTo: (other) => true
                        }
                        Query: QueryBase.alias("equalTo", "==")

                        matcher: Map()
                        subject: Map()

                        matcher[Query] = 1
                        subject[Stored] = 1

                        matcher.match(subject)
                        """);

        assertSame(ProtosBooleanValue.TRUE, result);
    }

    @Test
    void computesQueryHashExactlyOncePerRequirementSearch() throws Exception {
        ProtosPrelude prelude = core();

        Object result =
                evaluate(
                        prelude,
                        """
                        state: {
                            hashes: 0
                        }

                        QueryBase: {
                            hash: () => {
                                state.hashes = state.hashes + 1
                                11
                            }

                            equalTo: (other) => true
                        }
                        Query: QueryBase.alias("equalTo", "==")

                        StoredBase: {
                            hash: () => 11
                            equalTo: (other) => false
                        }
                        Stored: StoredBase.alias("equalTo", "==")

                        Child: {
                            match: (subject) => state.hashes == 1
                        }

                        matcher: Map()
                        subject: Map()

                        matcher[Query] = Child
                        subject[Stored] = 1

                        state.hashes = 0

                        matcher.match(subject)
                        """);

        assertSame(ProtosBooleanValue.TRUE, result);
    }

    @Test
    void composesCapturesWithoutFlatteningCapturedArrays() throws Exception {
        ProtosPrelude prelude = core();

        ProtosArrayValue captures =
                assertInstanceOf(
                        ProtosArrayValue.class,
                        evaluate(
                                prelude,
                                """
                                matcher: Map()
                                subject: Map()

                                matcher["first"] = Capture
                                matcher["second"] = Capture

                                subject["first"] = 1
                                subject["second"] = [2, 3]
                                subject["extra"] = 99

                                matcher.match(subject)
                                """));

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

                                matcher: Map()
                                subject: Map()

                                matcher["first"] = First
                                matcher["second"] = Second

                                subject["first"] = 0
                                subject["second"] = 0

                                matcher.match(subject)
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

                                matcher: Map()
                                subject: Map()

                                matcher["a"] = Bad
                                subject["a"] = 1

                                matcher.match(subject)
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

                                matcher: Map()
                                subject: Map()

                                matcher["a"] = Bad
                                subject["a"] = 1

                                matcher.match(subject)
                                """));
    }

    @Test
    void rejectsInheritedMapBehaviorOnIneligibleReceiver() throws Exception {
        ProtosPrelude prelude = core();

        assertThrows(
                ProtosSignalException.class,
                () ->
                        evaluate(
                                prelude,
                                """
                                fake: Map {
                                }

                                fake.match(Map())
                                """));
    }
}
