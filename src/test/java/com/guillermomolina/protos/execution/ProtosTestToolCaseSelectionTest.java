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

import com.guillermomolina.protos.runtime.ProtosArrayValue;
import com.guillermomolina.protos.runtime.ProtosBooleanValue;
import com.guillermomolina.protos.runtime.ProtosPrelude;
import com.guillermomolina.protos.runtime.ProtosStringValue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;

/** TOOL009-G2 / D185: CaseRef V1 identity, exact selection and listing projection. */
final class ProtosTestToolCaseSelectionTest {
    private static final Path CORE = Path.of("protos", "lib", "core");
    private static final Path STANDARD_LIBRARY = Path.of("protos", "lib");
    private static final Path TOOL_ROOT = Path.of("protos", "tools", "test");
    private static final Path SHARED_ROOT = Path.of("protos", "tools", "shared");

    /*
     * Three-Case suite-native source whose bodies fail and count if ever invoked. Discovery,
     * ref derivation, selection and listing must never call them.
     */
    private static final String SUITE =
            """
            Discovery: import("self:Discovery")
            CaseRef: import("self:CaseRef")
            Selection: import("self:CaseSelection")
            TestValue: import("std:test/Test")

            calls: {
                value: 0
            }

            body: () => {
                calls.value = calls.value + 1
                Error().signal()
            }

            suite: {
                tests: Array(
                    TestValue("first", body),
                    TestValue("second", body),
                    TestValue("third", body)
                )
            }

            projection:
                Discovery.projectionFromModule(
                    Array("corpus", "suite.protos"),
                    suite
                )
            cases: Discovery.projectionPlan(projection)
            """;

    @Test
    void v1SpellingIsFrozen() throws Exception {
        assertEquals(
                "v1.5b5b22636f72707573222c2273756974652e70726f746f73225d2c226669727374225d",
                evaluateString(
                        """
                        CaseRef: import("self:CaseRef")
                        CaseRef.derive(Array("corpus", "suite.protos"), "first")
                        """));

        assertEquals(
                "v1.5b5b22636f72707573222c22c3b1616e64c3ba2fc3bc2e70726f746f73225d2c22"
                        + "70727565626120e29c93225d",
                evaluateString(
                        """
                        CaseRef: import("self:CaseRef")
                        CaseRef.derive(Array("corpus", "ñandú/ü.protos"), "prueba ✓")
                        """));

        assertEquals(
                ref("corpus", "ñandú/ü.protos", "prueba ✓"),
                evaluateString(
                        """
                        CaseRef: import("self:CaseRef")
                        CaseRef.derive(Array("corpus", "ñandú/ü.protos"), "prueba ✓")
                        """));
    }

    @Test
    void identityIsSourceIdentityPlusSelectorOnly() throws Exception {
        assertTrue(
                """
                CaseRef: import("self:CaseRef")
                LogicalCasePlan: import("self:LogicalCasePlan")

                source: Array("corpus", "suite.protos")
                other: Array("corpus", "other.protos")

                sameSame: CaseRef.derive(source, "first") == CaseRef.derive(source, "first")
                sameOtherSelector: CaseRef.derive(source, "first") == CaseRef.derive(source, "second")
                otherSameSelector: CaseRef.derive(source, "first") == CaseRef.derive(other, "first")

                // Declaration position and declaration signature are not inputs.
                wide: LogicalCasePlan.build(source, Array("a", "b", "c"))
                narrow: LogicalCasePlan.build(source, Array("b"))
                positionFree: CaseRef.ref(wide[1]) == CaseRef.ref(narrow[0])

                sameSame &&
                    sameOtherSelector.not() &&
                    otherSameSelector.not() &&
                    positionFree
                """);
    }

    @Test
    void authorityDistinguishingAssociationDataChangesRefButNotDisplay()
            throws Exception {
        assertTrue(
                """
                CaseRef: import("self:CaseRef")
                LogicalCasePlan: import("self:LogicalCasePlan")

                plain: Array("corpus", "fixture.protos")
                projectA: Array(
                    "corpus",
                    "fixture.protos",
                    Array("project-tree", "project-a", "case", "case")
                )
                projectB: Array(
                    "corpus",
                    "fixture.protos",
                    Array("project-tree", "project-b", "case", "case")
                )

                plainEntry: LogicalCasePlan.build(plain, Array("t"))[0]
                aEntry: LogicalCasePlan.build(projectA, Array("t"))[0]
                bEntry: LogicalCasePlan.build(projectB, Array("t"))[0]

                (CaseRef.ref(plainEntry) == CaseRef.ref(aEntry)).not() &&
                    (CaseRef.ref(aEntry) == CaseRef.ref(bEntry)).not() &&
                    (CaseRef.ref(plainEntry) == CaseRef.ref(bEntry)).not() &&
                    (CaseRef.display(plainEntry) == CaseRef.display(aEntry)) &&
                    (CaseRef.display(aEntry) == CaseRef.display(bEntry))
                """);
    }

    @Test
    void malformedAndUnsupportedRefsFailClosed() throws Exception {
        String[] refs = {
            "",
            "v1.",
            "v1",
            "v1ab",
            "v.ab",
            "x1.ab",
            "V1.ab",
            "v1.abc",
            "v1.AB",
            "v1.zz",
            "v1.a b0",
            "v2.ab",
            "v10.ab",
            "v01.ab"
        };

        for (String text : refs) {
            ProtosExecutionOutcome outcome =
                    execute(
                            "CaseRef: import(\"self:CaseRef\")\n"
                                    + "CaseRef.requireSupported(\""
                                    + text
                                    + "\")\n");

            assertEquals(
                    ProtosExecutionOutcome.State.FAILED,
                    outcome.state(),
                    () -> "expected fail-closed CaseRef: " + text);
        }

        assertEquals(
                "v1.ab",
                evaluateString(
                        """
                        CaseRef: import("self:CaseRef")
                        CaseRef.requireSupported("v1.ab")
                        """));
    }

    @Test
    void listingProjectsCasePlanOrderWithoutInvokingBodies() throws Exception {
        ProtosExecutionOutcome outcome =
                execute(
                        SUITE
                                + """

                                selection: Selection.begin(Array())
                                retained: Selection.retain(selection, cases)
                                Selection.requireComplete(selection)

                                Array(
                                    Selection.listing(Array(projection)),
                                    calls.value
                                )
                                """);

        assertEquals(ProtosExecutionOutcome.State.COMPLETED, outcome.state());
        ProtosArrayValue observed =
                assertInstanceOf(ProtosArrayValue.class, outcome.value());

        assertEquals(
                "{\"schema\":\"protos.test.cases/v1\",\"cases\":["
                        + listed("first")
                        + ","
                        + listed("second")
                        + ","
                        + listed("third")
                        + "]}",
                assertInstanceOf(
                                ProtosStringValue.class,
                                observed.indexedAt(0))
                        .value());
        assertEquals(String.valueOf("0"), com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerDecimalText(observed.indexedAt(1)));
    }

    /*
     * PERF031-I: the listing accumulates entries across several projections in projection
     * order, then CasePlan order within each, exactly as the former prefix-copy construction.
     * Exact refs are supplied in reverse order and still list in CasePlan order.
     */
    @Test
    void listingSpansProjectionsInOrderAfterReverseExactSelection() throws Exception {
        ProtosExecutionOutcome outcome =
                execute(
                        SUITE
                                + """

                                otherSuite: {
                                    tests: Array(
                                        TestValue("alpha", body),
                                        TestValue("beta", body)
                                    )
                                }
                                otherProjection:
                                    Discovery.projectionFromModule(
                                        Array("corpus", "other.protos"),
                                        otherSuite
                                    )
                                otherCases: Discovery.projectionPlan(otherProjection)

                                selection: Selection.begin(Array(
                                    CaseRef.ref(otherCases[1]),
                                    CaseRef.ref(otherCases[0]),
                                    CaseRef.ref(cases[2]),
                                    CaseRef.ref(cases[0])
                                ))
                                retained: Selection.retain(selection, cases)
                                otherRetained: Selection.retain(selection, otherCases)
                                Selection.requireComplete(selection)

                                Array(
                                    Selection.listing(Array(
                                        Selection.projection(projection, retained),
                                        Selection.projection(otherProjection, otherRetained)
                                    )),
                                    Selection.listing(Array()),
                                    calls.value
                                )
                                """);

        assertEquals(ProtosExecutionOutcome.State.COMPLETED, outcome.state());
        ProtosArrayValue observed =
                assertInstanceOf(ProtosArrayValue.class, outcome.value());

        assertEquals(
                "{\"schema\":\"protos.test.cases/v1\",\"cases\":["
                        + listed("first")
                        + ","
                        + listed("third")
                        + ","
                        + listedIn("other.protos", "alpha")
                        + ","
                        + listedIn("other.protos", "beta")
                        + "]}",
                assertInstanceOf(
                                ProtosStringValue.class,
                                observed.indexedAt(0))
                        .value());
        assertEquals(
                "{\"schema\":\"protos.test.cases/v1\",\"cases\":[]}",
                assertInstanceOf(
                                ProtosStringValue.class,
                                observed.indexedAt(1))
                        .value());
        assertEquals(String.valueOf("0"), com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerDecimalText(observed.indexedAt(2)));
    }

    /*
     * PERF031-I: the accumulator yields a fresh, unfrozen Array holding exactly the appended
     * references, in append order, for sizes on both sides of every carry boundary up to 17;
     * independent accumulators never share state and finishing leaves the accumulator intact.
     */
    @Test
    void arrayAccumulatorPreservesOrderIdentityAndIndependence() throws Exception {
        assertTrue(
                """
                ArrayAccumulator: import("self:ArrayAccumulator")

                values: Array()
                count: 0
                (() => count < 17).whileTrue() {
                    value: {
                        id: count
                    }
                    values = Array(...values, value)
                    count = count + 1
                }

                ok: true
                size: 0
                (() => size <= 17).whileTrue() {
                    accumulator: ArrayAccumulator.create()
                    other: ArrayAccumulator.create()
                    index: 0
                    (() => index < size).whileTrue() {
                        ArrayAccumulator.append(accumulator, values[index])
                        index = index + 1
                    }
                    ArrayAccumulator.append(other, values[0])

                    result: ArrayAccumulator.finish(accumulator)
                    again: ArrayAccumulator.finish(accumulator)
                    (result.size() == size).ifFalse(() => {
                        ok = false
                    })
                    (again.size() == size).ifFalse(() => {
                        ok = false
                    })
                    (result === again).ifTrue(() => {
                        ok = false
                    })
                    // Replacing an existing element would fail on a frozen Array.
                    (size > 0).ifTrue(() => {
                        result[0] = values[0]
                    })
                    check: 0
                    (() => check < size).whileTrue() {
                        (result[check] === values[check]).ifFalse(() => {
                            ok = false
                        })
                        (again[check] === values[check]).ifFalse(() => {
                            ok = false
                        })
                        check = check + 1
                    }
                    (ArrayAccumulator.finish(other).size() == 1).ifFalse(() => {
                        ok = false
                    })
                    size = size + 1
                }

                ok
                """);
    }

    @Test
    void exactSelectionRetainsExistingEntriesInCasePlanOrder() throws Exception {
        assertTrue(
                SUITE
                        + """

                        selection: Selection.begin(Array(
                            CaseRef.ref(cases[2]),
                            CaseRef.ref(cases[0])
                        ))
                        retained: Selection.retain(selection, cases)
                        Selection.requireComplete(selection)

                        selected: Selection.projection(projection, retained)

                        (retained.size() == 2) &&
                            (retained[0] === cases[0]) &&
                            (retained[1] === cases[2]) &&
                            (Discovery.projectionSignature(selected) ===
                                Discovery.projectionSignature(projection)) &&
                            (Discovery.projectionPlan(selected)[1] === cases[2]) &&
                            (calls.value == 0)
                        """);
    }

    @Test
    void inactiveSelectionRetainsEveryDiscoveredCase() throws Exception {
        assertTrue(
                SUITE
                        + """

                        selection: Selection.begin(Array())
                        retained: Selection.retain(selection, cases)
                        Selection.requireComplete(selection)

                        selection.active.not() &&
                            (retained.size() == 3) &&
                            (retained[0] === cases[0]) &&
                            (retained[1] === cases[1]) &&
                            (retained[2] === cases[2])
                        """);
    }

    @Test
    void repeatedExactRefFailsInsteadOfDeduplicating() throws Exception {
        assertFails(
                SUITE
                        + """

                        Selection.begin(Array(
                            CaseRef.ref(cases[1]),
                            CaseRef.ref(cases[1])
                        ))
                        """);
    }

    @Test
    void wellFormedUnknownRefFailsCompleteness() throws Exception {
        assertFails(
                SUITE
                        + """

                        selection: Selection.begin(Array(
                            CaseRef.derive(Array("corpus", "suite.protos"), "missing")
                        ))
                        retained: Selection.retain(selection, cases)
                        Selection.requireComplete(selection)
                        """);
    }

    @Test
    void refOfUndiscoveredSourceFailsCompleteness() throws Exception {
        assertFails(
                SUITE
                        + """

                        LogicalCasePlan: import("self:LogicalCasePlan")
                        outsideEntry: LogicalCasePlan.build(
                            Array("corpus", "outside.protos"),
                            Array("first")
                        )[0]

                        selection: Selection.begin(Array(CaseRef.ref(outsideEntry)))
                        retained: Selection.retain(selection, cases)
                        Selection.requireComplete(selection)
                        """);
    }

    @Test
    void canonicalRefCollisionIsToolAuthorityError() throws Exception {
        assertFails(
                SUITE
                        + """

                        selection: Selection.begin(Array())
                        first: Selection.retain(selection, cases)
                        second: Selection.retain(selection, cases)
                        """);
    }

    @Test
    void optionsRetainListFlagAndRepeatedCaseRefsInCliOrder() throws Exception {
        assertTrue(
                """
                Options: import("self:Options")

                arguments: [
                    "test",
                    "--case", "v1.bb",
                    "--file", "one.protos",
                    "--list-cases",
                    "--case", "v1.aa"
                ]
                refs: Options.caseRefs(arguments)

                Options.listCases(arguments) &&
                    Options.listCases(["test"]).not() &&
                    (Options.caseRefs(["test"]).size() == 0) &&
                    (refs.size() == 2) &&
                    (refs[0] == "v1.bb") &&
                    (refs[1] == "v1.aa") &&
                    (Options.filePaths(arguments)[0] == "one.protos")
                """);
    }

    private static String listed(String selector) {
        return "{\"ref\":\""
                + ref("corpus", "suite.protos", selector)
                + "\",\"display\":\"corpus:suite.protos::"
                + selector
                + "\"}";
    }

    private static String listedIn(String path, String selector) {
        return "{\"ref\":\""
                + ref("corpus", path, selector)
                + "\",\"display\":\"corpus:"
                + path
                + "::"
                + selector
                + "\"}";
    }

    /** Test-side V1 reference for simple strings that need no JSON escaping. */
    static String ref(String corpus, String path, String selector) {
        String payload =
                "[[\"" + corpus + "\",\"" + path + "\"],\"" + selector + "\"]";
        return "v1."
                + HexFormat.of()
                        .formatHex(payload.getBytes(StandardCharsets.UTF_8));
    }

    private static void assertTrue(String source) throws Exception {
        ProtosExecutionOutcome outcome = execute(source);

        assertEquals(ProtosExecutionOutcome.State.COMPLETED, outcome.state());
        assertSame(ProtosBooleanValue.TRUE, outcome.value());
    }

    private static void assertFails(String source) throws Exception {
        assertEquals(
                ProtosExecutionOutcome.State.FAILED,
                execute(source).state());
    }

    private static String evaluateString(String source) throws Exception {
        ProtosExecutionOutcome outcome = execute(source);

        assertEquals(ProtosExecutionOutcome.State.COMPLETED, outcome.state());
        return assertInstanceOf(ProtosStringValue.class, outcome.value()).value();
    }

    private static ProtosExecutionOutcome execute(String source) throws Exception {
        ProtosBundledToolModuleResolver resolver =
                new ProtosBundledToolModuleResolver(
                        "test",
                        TOOL_ROOT,
                        SHARED_ROOT,
                        new ProtosStandardLibraryModuleResolver(STANDARD_LIBRARY));

        ProtosPrelude prelude =
                new ProtosCoreBootstrap().bootstrap(CORE, resolver);

        return ProtosTestExecutionSupport.execute(
                source,
                prelude.newModuleActivation());
    }
}
