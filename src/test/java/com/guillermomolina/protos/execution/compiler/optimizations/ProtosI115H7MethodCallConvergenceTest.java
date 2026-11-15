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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.guillermomolina.protos.runtime.ProtosActivation;
import com.guillermomolina.protos.runtime.ProtosClosureValue;
import com.guillermomolina.protos.runtime.ProtosLargeIntegerValue;
import com.guillermomolina.protos.runtime.ProtosLexicalEnvironment;
import com.guillermomolina.protos.runtime.ProtosModuleBindingLocation;
import com.guillermomolina.protos.runtime.ProtosObjectValue;
import com.guillermomolina.protos.runtime.ProtosPrelude;
import com.guillermomolina.protos.runtime.control.ProtosReturnHome;
import com.oracle.truffle.api.Assumption;
import com.oracle.truffle.api.bytecode.Instruction;
import com.oracle.truffle.api.interop.InteropException;
import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.object.DynamicObject;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Value;
import org.junit.jupiter.api.Test;

/**
 * I115-H7 EXPERIMENTAL: physical convergence of the
 * {@code method-call} workload (Bytecode While, ReadGlobal, GetMethod,
 * CallUnaryMethod, NumberAdd, RichCompareLt and the grouped parameter
 * binding CopySuppliedArguments into LOCAL locals, or LoadSuppliedArgument
 * into a CELL; I115 retired the former CopyArguments, Lt, NumberInPlaceAdd, the CallBinaryMethod direct tier,
 * compact roots and the reduced H11 entries), and
 * I115-H7-R1 regression protection for the contracts those operations must
 * keep (caller protocol, rebinding, numeric exactness, strict conditions,
 * evaluation order and side effects).
 *
 * <p>These tests pin the physical Bytecode DSL and the behavior actually
 * implemented. They do not claim conformance of the recorded experimental
 * divergences (docs/experiments/I115-H7-GRAALPY-PHYSICAL-CONVERGENCE.md) and
 * produce no Tier 1/Tier 2 data.
 */
final class ProtosI115H7MethodCallConvergenceTest {

    private static final Path CORE = Path.of("protos", "lib", "core");

    private static final Path OUTPUT = Path.of("target", "i115-h7-method-call");

    private static final int WARM = 32;

    private static final String METHOD_CALL = """
            receiver: {
                identity: (value) => { value }
            }

            run: () => {
                i: 0
                sink: 0

                (() => i < 1000).whileTrue() {
                    sink = sink + receiver.identity(i)
                    i = i + 1
                }

                sink
            }
            """;

    /**
     * The run root of METHOD_CALL in the shared Bytecode DSL lowering. The
     * loop condition and body are inline callbacks of the native while, so i
     * and sink are frame-native locals of run (no cells); the condition is
     * RichCompareLt whose primitive result flows into the canonical control
     * condition and the native branch; the send is ReadGlobal, GetMethod, then
     * one CallBinaryMethod (receiver plus one argument); both
     * {@code x = x + e} are NumberAdd behind IsStandardNumericSelection, whose
     * message fallback is a guarded GetMethod; a backward branch closes the
     * loop. No fixed instruction budget is asserted.
     */
    private static void assertRunArchitecture(List<String> names) {
        assertTrue(names.get(0).equals("c.EnterRoot"), names.toString());
        assertEquals(0, count(names, "c.CreateCell"), "i and sink are frame locals: " + names);
        assertFalse(names.contains("c.LoadCell") || names.contains("c.StoreCell"),
                names.toString());
        assertEquals(1, count(names, "c.RichCompareLt"), names.toString());
        assertEquals(1, count(names, "c.ReadGlobal"), names.toString());
        assertEquals(1, count(names, "c.CallBinaryMethod"), names.toString());
        assertEquals(2, count(names, "c.NumberAdd"), names.toString());
        // Numeric operations verify their selection internally: no separate
        // selection or fallback lookup per numeric site.
        assertEquals(0, count(names, "c.IsStandardNumericSelection"), names.toString());
        assertEquals(1, count(names, "c.GetMethod"),
                "only the ordinary send selects a method: " + names);
        int compare = names.indexOf("c.RichCompareLt");
        int condition = indexAfter(names, "c.CanonicalControlCondition", compare);
        int branch = indexAfter(names, "branch.false", condition);
        assertTrue(compare >= 0 && condition > compare && branch > condition,
                "comparison, canonical condition, branch: " + names);
        int select = indexAfter(names, "c.GetMethod", names.indexOf("c.ReadGlobal"));
        int call = names.indexOf("c.CallBinaryMethod");
        assertTrue(names.indexOf("c.ReadGlobal") < select && select < call,
                "receiver read, method selection, then its call: " + names);
        assertTrue(names.contains("branch.backward"), names.toString());
        assertEquals("return", names.get(names.size() - 1), names.toString());
        assertTrue(names.contains("c.EpilogForReturn"), names.toString());
    }

    private static int indexAfter(List<String> names, String name, int from) {
        if (from < 0) {
            return -1;
        }
        for (int index = from + 1; index < names.size(); index++) {
            if (names.get(index).equals(name)) {
                return index;
            }
        }
        return -1;
    }

    /**
     * The identity root: the one parameter lowering (supplied position 0
     * stored into the parameter's native local) and one direct local read.
     */
    private static final List<String> IDENTITY_DSL = List.of(
            "c.EnterRoot",
            "c.CopySuppliedArguments",
            "load.local",
            "c.EpilogForReturn",
            "return");

    /**
     * Retired machinery never emitted by the shared Bytecode DSL lowering.
     * (MaterializePrimitiveNumericCarrier and
     * ExperimentalCanonicalBooleanCondition are active current operations.)
     */
    private static final List<String> REPLACED = List.of(
            "PrepareStructuredCall",
            "StructuredPrepareInlineChildCall",
            "AdmitsInlineLiteralWhile",
            "StructuredWhileCondition",
            "StructuredWhileRetainBodyResult",
            "StructuredFinishWithChildResult",
            "CompleteClosureCall",
            "SelectInlineCapturedMaterializedOwnerFrame",
            "ResolveInlineCapturedMaterializedWritableLexicalTarget",
            "load.local.mat",
            "PrepareInlineSendOne",
            "PrepareSendOne",
            "IsStructuredCall",
            "RequiresStructuredDispatch",
            "EnterStraightLineSourceCall",
            "IsPrimitiveNumericSendMiss",
            "TryPrimitiveNumeric",
            "TryDirectSendOne",
            "PromoteFrameLexicalAuthority",
            "InstallFrameLexicalAuthority",
            "BindUnaryFrameParameter",
            // I115: the retired compact-root, reduced-ABI and H7 numeric lane.
            "CopyArguments",
            "BindClosureFrameParameter",
            "BindRequiredFrameParameter",
            "ReadRootFrameLocal",
            "IsCompactLocalFrame",
            "ExperimentalInteger",
            "c.Lt",
            "NumberInPlaceAdd");

    // ---- A. Result and the census ---------------------------------------------------

    @Test
    void methodCallResult() throws Exception {
        try (ProtosStandaloneHostedSession session = open("result", METHOD_CALL)) {
            Value run = session.prepareTopLevel("run").executable();
            for (int call = 0; call < WARM; call++) {
                Value result = run.execute();
                assertTrue(result.isNumber());
                assertTrue(result.fitsInInt());
                assertEquals(499500L, result.asLong());
            }
            System.out.println("I115_H7_RESULT=499500");
        }
    }

    @Test
    void runUsesTheSharedBytecodeLowering() throws Exception {
        try (ProtosStandaloneHostedSession session = open("compact", METHOD_CALL)) {
            ProtosBytecodeRootNode run = root(session, "run");
            List<String> names = unquickened(names(run));
            System.out.println("I115_H7_RUN_INSTRUCTIONS=" + names);
            assertRunArchitecture(names);
            assertReplacedAbsent("run", names);
            assertDirectLoop(run);
            assertTrue(run.getBytecodeNode().getLocals().stream()
                            .noneMatch(local -> "compactWhileResult".equals(local.getName())),
                    "a statement whileTrue keeps no result local");
            assertEquals(499500L,
                    session.prepareTopLevel("run").executable().execute().asLong());
            // The executed (possibly quickened) bytecode keeps the same operations.
            assertEquals(names, unquickened(names(run)));
            assertRunArchitecture(unquickened(names(run)));
        }
    }

    @Test
    void identityIsOneDirectRoot() throws Exception {
        try (ProtosStandaloneHostedSession session = open("identity", METHOD_CALL)) {
            ProtosBytecodeRootNode run = root(session, "run");
            ProtosBytecodeRootNode identity = methodRoot(session, "run", "receiver", "identity");
            assertSame(run.getRootNodes(), identity.getRootNodes(),
                    "identity is a root of the examined compilation");
            assertNotSame(run, identity);
            assertEquals(IDENTITY_DSL, unquickened(names(identity)));
            // I115: one B5 header; the parameter is supplied position 0.
            assertEquals(List.of(0), suppliedPositions(identity));
            assertTrue(localNames(identity).contains("value"));
            assertTrue(identity.provablyNonSuspendingBody(),
                    "identity is a proven non-suspending root of the shared lowering");
            assertEquals(499500L,
                    session.prepareTopLevel("run").executable().execute().asLong());
        }
    }

    @Test
    void completeDslCensus() throws Exception {
        try (ProtosStandaloneHostedSession session = open("census", METHOD_CALL)) {
            ProtosBytecodeRootNode run = root(session, "run");
            ProtosBytecodeRootNode identity = methodRoot(session, "run", "receiver", "identity");
            String report = session.processContextForTesting()
                    .callForRuntime(() -> census(run, identity));
            Path output = OUTPUT.resolve("dsl-full.txt");
            Files.writeString(output, report, StandardCharsets.UTF_8);
            System.out.println("I115_H7_DSL_FILE=" + output.toAbsolutePath());
            assertEquals(499500L,
                    session.prepareTopLevel("run").executable().execute().asLong());
        }
    }

    // ---- Evaluation order and per-operation fixtures ---------------------------------

    @Test
    void receiverArgumentAndCallAreEvaluatedOnceInOrder() throws Exception {
        try (ProtosStandaloneHostedSession session = open("order", """
                trace: 0

                target: {
                    record: (value) => {
                        trace = trace * 10 + 3
                        value
                    }
                }

                probe: {
                    pick: (unused) => {
                        trace = trace * 10 + 1
                        target
                    }
                    argument: (value) => {
                        trace = trace * 10 + 2
                        value
                    }
                }

                run: () => {
                    result: probe.pick(0).record(probe.argument(7))
                    result * 1000 + trace
                }
                """)) {
            List<String> names = unquickened(names(root(session, "run")));
            // Three ordinary one-argument sends, each GetMethod then
            // CallBinaryMethod (method arity counts the receiver). The
            // remaining selections are the guarded '+'/'*' message fallbacks.
            // Every selection is followed by its call (CallBinaryMethod for a
            // direct send, CallUnaryMethod for a bound method or a guarded
            // fallback); three sends plus one guarded '+' fallback.
            assertEquals(3 + count(names, "c.IsStandardNumericSelection"),
                    count(names, "c.GetMethod"), "three ordinary sends: " + names);
            assertSelectionPrecedesEachCall(names);
            assertReplacedAbsent("run", names);
            // receiver (1), then argument (2), then the call itself (3), once each.
            assertEquals(7123L,
                    session.prepareTopLevel("run").executable().execute().asLong());
        }
    }

    @Test
    void eachOperationHasItsOwnPhysicalSite() throws Exception {
        try (ProtosStandaloneHostedSession session = open("operations", """
                accumulator: {
                    twice: (n) => { n + n }
                }

                plainSum: () => {
                    a: 40
                    b: 2
                    a + b
                }

                accumulate: () => {
                    t: 0
                    t = t + 5
                    t
                }

                loopCompare: () => {
                    k: 0
                    (() => k < 3).whileTrue() {
                        k = 5
                    }
                    k
                }

                sendOne: () => {
                    accumulator.twice(4)
                }

                renamedLoop: () => {
                    k: 0
                    total: 0
                    (() => k < 5).whileTrue() {
                        total = total + accumulator.twice(k)
                        k = k + 1
                    }
                    total
                }
                """)) {
            List<String> sum = unquickened(names(root(session, "plainSum")));
            assertEquals(1, count(sum, "c.NumberAdd"), "a + b is NumberAdd: " + sum);
            assertReplacedAbsent("plainSum", sum);
            assertNumber(session, "plainSum", 42L);

            // I115: x = x + e is the same per-selector NumberAdd, stored natively.
            List<String> accumulate = unquickened(names(root(session, "accumulate")));
            assertEquals(1, count(accumulate, "c.NumberAdd"),
                    "t = t + 5 is NumberAdd: " + accumulate);
            // The sum is stored into t's native storage (a local or a cell);
            // native locals also stage operands, so no store.local count is asserted.
            int add = accumulate.indexOf("c.NumberAdd");
            assertTrue(accumulate.subList(add + 1, accumulate.size()).stream()
                            .anyMatch(name -> name.equals("store.local") || name.equals("c.StoreCell")),
                    "the sum is stored: " + accumulate);
            assertReplacedAbsent("accumulate", accumulate);
            assertNumber(session, "accumulate", 5L);

            ProtosBytecodeRootNode loopRoot = root(session, "loopCompare");
            List<String> loop = unquickened(names(loopRoot));
            assertEquals(1, count(loop, "c.RichCompareLt"), "k < 3 is RichCompareLt: " + loop);
            int compare = loop.indexOf("c.RichCompareLt");
            int condition = indexAfter(loop, "c.CanonicalControlCondition", compare);
            assertTrue(condition > compare
                            && indexAfter(loop, "branch.false", condition) > condition,
                    "comparison, Boolean validation, then the native branch: " + loop);
            assertEquals(0, count(loop, "c.NumberAdd"));
            assertReplacedAbsent("loopCompare", loop);
            assertDirectLoop(loopRoot);
            assertNumber(session, "loopCompare", 5L);

            List<String> send = unquickened(names(root(session, "sendOne")));
            int read = send.indexOf("c.ReadGlobal");
            int get = send.indexOf("c.GetMethod");
            int call = send.indexOf("c.CallBinaryMethod");
            assertTrue(read >= 0 && get > read && call > get,
                    "receiver read, GetMethod, then CallBinaryMethod: " + send);
            assertEquals(1, count(send, "c.CallBinaryMethod"), send.toString());
            assertReplacedAbsent("sendOne", send);
            assertNumber(session, "sendOne", 8L);

            // Different names and selector: the same general operations.
            ProtosBytecodeRootNode renamedRoot = root(session, "renamedLoop");
            List<String> renamed = unquickened(names(renamedRoot));
            for (String required : List.of("c.ReadGlobal", "c.GetMethod",
                    "c.CallBinaryMethod", "c.NumberAdd", "c.RichCompareLt")) {
                assertTrue(renamed.contains(required), required + ": " + renamed);
            }
            assertReplacedAbsent("renamedLoop", renamed);
            assertDirectLoop(renamedRoot);
            assertNumber(session, "renamedLoop", 20L);

            ProtosBytecodeRootNode twice =
                    methodRoot(session, "renamedLoop", "accumulator", "twice");
            List<String> twiceNames = unquickened(names(twice));
            assertEquals(List.of(0), suppliedPositions(twice), twiceNames.toString());
            assertEquals(1, count(twiceNames, "c.NumberAdd"), twiceNames.toString());
            assertReplacedAbsent("twice", twiceNames);
        }
    }

    // ---- I115-H7-R5 return representation ---------------------------------------------

    /**
     * A source root of the shared lowering and its host entry must BOTH
     * return the signed-64 Integer as a Java Long. Checking Value.asLong
     * alone would miss a wrong representation at either boundary.
     */
    @Test
    void longResultRemainsLongAcrossRootAndHostBoundary() throws Exception {
        try (ProtosStandaloneHostedSession session =
                open("r5-raw-integer", METHOD_CALL)) {
            ProtosStandaloneHostedSession.PreparedTopLevel prepared =
                    session.prepareTopLevel("run");
            ProtosHostExecutableClosure host = prepared.hostExecutableForTesting();
            ProtosBytecodeRootNode run = assertInstanceOf(
                    ProtosBytecodeRootNode.class, host.target.getRootNode());

            // I115: one shared return epilogue. sink is a CELL, so the
            // returned value is read with LoadCell (or a local read).
            List<String> runNames = unquickened(names(run));
            assertEquals(List.of("c.EpilogForReturn", "return"),
                    runNames.subList(runNames.size() - 2, runNames.size()), runNames.toString());
            String returned = runNames.get(runNames.size() - 3);
            assertTrue(returned.equals("c.LoadCell") || returned.equals("load.local"),
                    runNames.toString());

            for (int call = 0; call < WARM; call++) {
                assertEquals(499500L, prepared.executable().execute().asLong());
            }

            session.processContextForTesting().callForRuntime(() -> {
                Object rootResult = host.target.call(
                        ProtosFrameArguments.compactDirectClosureCallZeroNoHome(
                                host.closure, host.caller));

                assertEquals(499500L,
                        assertInstanceOf(Long.class, rootResult).longValue(),
                        "EpilogForReturn must not allocate Long");

                Object hostResult;
                try {
                    hostResult = InteropLibrary.getUncached().execute(host);
                } catch (InteropException unexpected) {
                    throw new AssertionError(unexpected);
                }

                assertEquals(499500L,
                        assertInstanceOf(Long.class, hostResult).longValue(),
                        "the Polyglot host boundary must preserve the raw Long");
                return null;
            });
        }
    }

    /**
     * The R5 admission is narrow: ordinary guest-number returns still use
     * the guest representation, and a result exceeding 32 bits is the same
     * raw signed-64 Long, never truncated.
     */
    @Test
    void r5RawIntegerAdmissionDoesNotChangeOtherNumericResults() throws Exception {
        try (ProtosStandaloneHostedSession session =
                open("r5-representation-boundaries", """
                        plain: () => { 7 }

                        wide: () => {
                            a: 2147483647
                            a + 1
                        }
                        """)) {
            ProtosStandaloneHostedSession.PreparedTopLevel plain =
                    session.prepareTopLevel("plain");
            ProtosStandaloneHostedSession.PreparedTopLevel wide =
                    session.prepareTopLevel("wide");

            // I115: both roots share one lowering; only wide adds.
            List<String> plainNames = unquickened(names(root(session, "plain")));
            List<String> wideNames = unquickened(names(root(session, "wide")));
            assertEquals(0, count(plainNames, "c.NumberAdd"), plainNames.toString());
            assertEquals(1, count(wideNames, "c.NumberAdd"), wideNames.toString());
            assertReplacedAbsent("plain", plainNames);
            assertReplacedAbsent("wide", wideNames);

            session.processContextForTesting().callForRuntime(() -> {
                InteropLibrary interop = InteropLibrary.getUncached();
                try {
                    Object plainResult =
                            interop.execute(plain.hostExecutableForTesting());
                    Object wideResult =
                            interop.execute(wide.hostExecutableForTesting());

                    assertEquals(7L,
                            assertInstanceOf(
                                    Long.class, plainResult).longValue(),
                            "ordinary results retain their guest representation");
                    assertEquals(2147483648L,
                            assertInstanceOf(Long.class, wideResult).longValue(),
                            "a result beyond 32 bits remains the raw signed-64 Long");
                } catch (InteropException unexpected) {
                    throw new AssertionError(unexpected);
                }
                return null;
            });

            assertEquals(7L, plain.executable().execute().asLong());
            assertEquals(2147483648L, wide.executable().execute().asLong());
        }
    }

    // ---- I115 long/double numeric representation -------------------------------------

    /** The compact long, double and mixed operations of the regressions below. */
    private static final String LONG_DOUBLE = """
            sumLong: () => {
                a: 40
                b: 2
                a + b
            }

            sumBeyond32: () => {
                a: 2147483647
                b: 2147483647
                a + b
            }

            storedSum: () => {
                a: 40
                b: 1
                c: a + b
                c + 1
            }

            inPlace: () => {
                t: 2147483647
                t = t + 5
                t
            }

            sumDouble: () => {
                a: 0.1
                b: 0.2
                a + b
            }

            sumLongDouble: () => {
                a: 16777216
                b: 1.0
                a + b
            }

            sumDoubleLong: () => {
                a: 1.0
                b: 16777216
                a + b
            }

            lessLongDouble: () => {
                k: 0
                (() => k < 2.5).whileTrue() {
                    k = k + 1
                }
                k
            }

            lessMixed: () => {
                a: 2
                b: 2.5
                (a < b) && !(b < a)
            }

            lessDoubles: () => {
                a: 16777216.0
                b: 16777217.0
                (a < b) && !(b < a)
            }
            """;

    /*
     * 1, 4, 7, 8, 10: long results of NumberAdd stay Java Longs through
     * native storage, EpilogForReturn and the host boundary, both before
     * and after the operations specialize. Native locals also stage
     * operands and continuation state, so no store.local count is asserted.
     */
    @Test
    void longResultsStayRawLongs() throws Exception {
        try (ProtosStandaloneHostedSession session =
                open("long-results", LONG_DOUBLE)) {
            List<String> stored = unquickened(names(root(session, "storedSum")));
            assertEquals(2, count(stored, "c.NumberAdd"),
                    "the intermediate sum feeds the second NumberAdd: " + stored);
            int first = stored.indexOf("c.NumberAdd");
            assertTrue(stored.subList(first + 1, stored.size()).stream()
                            .anyMatch(name -> name.equals("store.local") || name.equals("c.StoreCell")),
                    "the intermediate sum is stored natively: " + stored);
            assertReplacedAbsent("storedSum", stored);
            List<String> inPlace = unquickened(names(root(session, "inPlace")));
            assertEquals(1, count(inPlace, "c.NumberAdd"), inPlace.toString());
            assertReplacedAbsent("inPlace", inPlace);

            for (int call = 0; call < WARM; call++) {
                assertRawLong(session, "sumLong", 42L);
                assertRawLong(session, "sumBeyond32", 4294967294L);
                assertRawLong(session, "storedSum", 42L);
                assertRawLong(session, "inPlace", 2147483652L);
            }
            assertNumber(session, "sumBeyond32", 4294967294L);
            assertNumber(session, "inPlace", 2147483652L);
        }
    }

    /*
     * 6: double and mixed long/double operations keep double precision.
     * Float literals are not admitted to the compact lane, so the double
     * fast paths of NumberAdd/NumberInPlaceAdd and Lt are checked directly;
     * the source cases check the observable values.
     */
    @Test
    void doubleOperationsKeepDoublePrecision() throws Exception {
        // 16777217 has no Java float representation.
        assertEquals(16777217.0, ProtosNumberAddFastPathsBase.doLD(null, false, 16777216L, 1.0, null));
        assertEquals(16777217.0, ProtosNumberAddFastPathsBase.doDL(null, false, 1.0, 16777216L, null));
        assertEquals(0.1 + 0.2, ProtosNumberAddFastPathsBase.doDD(null, false, 0.1, 0.2, null));
        // I115: RichCompareLt's double lane yields the primitive boolean that a
        // conditional branch consumes directly;
        // mixed operands and guest results are checked by execution.
        assertTrue(ProtosRichCompareLtNode.doDD(null, 16777216.0, 16777217.0, null));
        assertFalse(ProtosRichCompareLtNode.doDD(null, 16777217.0, 16777216.0, null));
        try (ProtosStandaloneHostedSession session =
                open("double-results", LONG_DOUBLE)) {
            /*
             * I115: `&&` and `!` are internal Boolean control. A canonical
             * receiver is decided by CanonicalControlCondition and the
             * right-hand comparison runs inline in lessMixed's own root, so both
             * comparisons are RichCompareLt here and no Closure is materialized.
             * Any other receiver takes the guarded ordinary send: one unary
             * `&&` send and one nilary `!` send, each selected before its call.
             */
            List<String> mixed = unquickened(names(root(session, "lessMixed")));
            assertEquals(2, count(mixed, "c.RichCompareLt"), mixed.toString());
            assertTrue(count(mixed, "c.CanonicalControlCondition") >= 2, mixed.toString());
            assertEquals(2, count(mixed, "c.GetMethod"), mixed.toString());
            assertEquals(1, count(mixed, "c.CallUnaryMethod"), mixed.toString());
            assertEquals(1, count(mixed, "c.CallNilaryMethod"), mixed.toString());
            assertSelectionPrecedesEachCall(mixed);
            assertEquals(0, count(mixed, "c.MaterializeCurrentClosure"), mixed.toString());
            assertEquals(0, count(mixed, "c.CaptureClosureCells"), mixed.toString());
            assertReplacedAbsent("lessMixed", mixed);
            for (int call = 0; call < WARM; call++) {
                assertDouble(session, "sumDouble", 0.1 + 0.2);
                // 16777217 has no Java float representation.
                assertDouble(session, "sumLongDouble", 16777217.0);
                assertDouble(session, "sumDoubleLong", 16777217.0);
                assertNumber(session, "lessLongDouble", 3L);
                assertTrue(session.prepareTopLevel("lessMixed").executable().execute()
                        .asBoolean(), "2 < 2.5 and not 2.5 < 2");
                assertTrue(session.prepareTopLevel("lessDoubles").executable().execute()
                        .asBoolean(), "doubles beyond float precision order exactly");
            }
        }
    }

    /*
     * 2, 3: addition, subtraction and multiplication return a Java long on
     * their primitive paths (the surviving NumberAdd/NumberSubtract/
     * NumberMultiply doLL lanes), never an int; beyond signed-64 the guest
     * result is the exact large Integer and no host ArithmeticException
     * escapes into ordinary guest overflow.
     */
    @Test
    void compactIntegerOperationsReturnLongs() throws Exception {
        assertEquals(42L, ProtosNumberAddFastPathsBase.doLL(null, false, 40L, 2L, null));
        assertEquals(38L, ProtosNumberSubtractFastPathsBase.doLL(null, false, 40L, 2L, null));
        assertEquals(80L, ProtosNumberMultiplyFastPathsBase.doLL(null, false, 40L, 2L, null));
        // The primitive lane rewrites on overflow; the generalization promotes.
        assertThrows(ArithmeticException.class,
                () -> ProtosNumberMultiplyFastPathsBase.doLL(null, false, Long.MIN_VALUE, -1L, null));
        try (ProtosStandaloneHostedSession session = open("integer-operations", """
                addWide: () => {
                    a: 2147483647
                    a + 2147483647
                }
                subtractWide: () => {
                    a: 0 - 2147483648
                    a - 2147483648
                }
                multiplyWide: () => {
                    a: 2147483647
                    a * 2147483647
                }
                multiplyEdge: () => {
                    a: 0 - 9223372036854775807 - 1
                    a * 1
                }
                multiplyOverflow: () => {
                    a: 0 - 9223372036854775807 - 1
                    a * (0 - 1)
                }
                """)) {
            for (String name : List.of(
                    "addWide", "subtractWide", "multiplyWide", "multiplyEdge")) {
                assertReplacedAbsent(name, unquickened(names(root(session, name))));
            }
            for (int call = 0; call < WARM; call++) {
                assertRawLong(session, "addWide", 4294967294L);
                assertRawLong(session, "subtractWide", -4294967296L);
                assertRawLong(session, "multiplyWide", 4611686014132420609L);
                assertRawLong(session, "multiplyEdge", Long.MIN_VALUE);
                assertLarge(session, "multiplyOverflow", "9223372036854775808");
            }
        }
    }

    /* 5: a real signed-64 overflow of +, - and * is the exact large Integer. */
    @Test
    void signed64OverflowPromotesExactly() throws Exception {
        try (ProtosStandaloneHostedSession session = open("signed-64-overflow", """
                sumDown: (n) => {
                    result: 9223372036854775807

                    (n > 0).ifTrue() {
                        result = sumDown(n - 1) + n
                    }

                    result
                }

                differenceDown: (n) => {
                    result: 9223372036854775807

                    (n > 0).ifTrue() {
                        result = n - differenceDown(n - 1)
                    }

                    result
                }

                factorial: (n) => {
                    result: 1

                    (n > 1).ifTrue() {
                        result = n * factorial(n - 1)
                    }

                    result
                }

                inPlaceOverflow: () => {
                    j: 9223372036854775807
                    j = j + 1
                    j
                }

                runSum: () => { sumDown(2) }
                runDifference: () => { differenceDown(2) }
                runProduct: () => { factorial(21) }
                runFits: () => { factorial(20) }
                """)) {
            for (int call = 0; call < WARM; call++) {
                assertLarge(session, "runSum", "9223372036854775810");
                assertLarge(session, "runDifference", "9223372036854775808");
                assertLarge(session, "runProduct", "51090942171709440000");
                assertLarge(session, "inPlaceOverflow", "9223372036854775808");
                assertNumber(session, "runFits", 2432902008176640000L);
            }
        }
    }

    // ---- Numbers ----------------------------------------------------------------------

    @Test
    void primitiveIntegersCrossBoundariesAsGuestNumbers() throws Exception {
        try (ProtosStandaloneHostedSession session = open("integers", """
                receiver: {
                    identity: (value) => { value }
                }

                compareLong: () => {
                    n: 2999999998
                    (() => n < 3000000000).whileTrue() {
                        n = n + 1
                    }
                    n
                }

                sumOverflow: () => {
                    a: 2147483647
                    b: 1
                    a + b
                }

                inPlaceOverflow: () => {
                    i: 2147483647
                    i = i + 1
                    i
                }

                overflowLong: () => {
                    j: 9223372036854775807
                    j = j + 1
                    j
                }

                identityResult: () => {
                    receiver.identity(41) + 1
                }

                identityDirect: () => {
                    receiver.identity(7)
                }

                identityLarge: () => {
                    receiver.identity(2147483647) + 2147483647
                }
                """)) {
            assertNumber(session, "compareLong", 3000000000L);
            // C: exceeding int keeps the exact value and a guest representation.
            assertNumber(session, "sumOverflow", 2147483648L);
            assertNumber(session, "inPlaceOverflow", 2147483648L);
            assertNumber(session, "identityResult", 42L);
            assertNumber(session, "identityDirect", 7L);
            assertNumber(session, "identityLarge", 4294967294L);
            for (int call = 0; call < WARM; call++) {
                assertNumber(session, "sumOverflow", 2147483648L);
            }

            Value large = session.prepareTopLevel("overflowLong").executable().execute();
            assertTrue(large.isNumber(), "large Integer is a guest number");
            assertFalse(large.fitsInLong(), "overflow promotes beyond signed-64");
            assertTrue(large.toString().contains("9223372036854775808"),
                    "exact large Integer: " + large);
        }
    }

    // ---- Conditions -------------------------------------------------------------------

    /** D: a redefined {@code <} returning a non-Boolean is the standard Error. */
    @Test
    void nonBooleanLessSignalsStandardErrorWithoutRunningTheBody() throws Exception {
        try (ProtosStandaloneHostedSession session = open("non-boolean", """
                visits: 0

                tracker: {
                    mark: (n) => {
                        visits = visits + n
                        visits
                    }
                }

                notBoolean: { compare: (other) => { "neither" } }
                notBooleanGate: notBoolean.alias("compare", "<")

                falseSource: { compare: (other) => { false } }
                falseGate: falseSource.alias("compare", "<")

                probeNotBoolean: () => {
                    (() => notBooleanGate < 1).whileTrue() {
                        tracker.mark(1)
                    }
                    0
                }

                probeFalse: () => {
                    (() => falseGate < 1).whileTrue() {
                        tracker.mark(1)
                    }
                    0
                }

                readVisits: () => { visits }
                """)) {
            /*
             * I115: the condition and body are Closure callbacks here (the
             * receiver is not a statically admitted comparison), invoked by
             * two CallNilaryMethod sites; the condition result passes the
             * unified canonical Boolean validation, which signals the
             * standard Error for a non-Boolean result before any body runs.
             */
            List<String> names = unquickened(names(root(session, "probeNotBoolean")));
            assertEquals(2, count(names, "c.CallNilaryMethod"), names.toString());
            assertTrue(count(names, "c.CanonicalControlCondition") >= 1,
                    names.toString());
            int condition = names.indexOf("c.CanonicalControlCondition");
            assertTrue(names.indexOf("c.CallNilaryMethod") < condition,
                    "the condition callback runs before its validation: " + names);
            assertReplacedAbsent("probeNotBoolean", names);

            assertNumber(session, "probeFalse", 0L);
            assertNumber(session, "readVisits", 0L);

            Value probe = session.prepareTopLevel("probeNotBoolean").executable();
            for (int attempt = 0; attempt < 3; attempt++) {
                PolyglotException failure =
                        assertThrows(PolyglotException.class, probe::execute);
                assertTrue(failure.isGuestException(), "a guest Error: " + failure);
                assertFalse(failure.isInternalError(), "not an internal error: " + failure);
            }
            assertNumber(session, "readVisits", 0L);
        }
    }

    /** E: the condition prefix runs once although the body runs zero times. */
    @Test
    void conditionSideEffectRunsWithZeroIterations() throws Exception {
        try (ProtosStandaloneHostedSession session = open("condition-effect", """
                sideEffect: () => {
                    probes: 0
                    n: 5
                    (() => {
                        probes = probes + 1
                        n < 3
                    }).whileTrue() {
                        n = n + 1
                    }
                    probes * 100 + n
                }
                """)) {
            ProtosBytecodeRootNode root = root(session, "sideEffect");
            List<String> names = unquickened(names(root));
            assertEquals(1, count(names, "c.RichCompareLt"), names.toString());
            assertReplacedAbsent("sideEffect", names);
            int prefix = names.indexOf("c.NumberAdd");
            int test = names.indexOf("c.RichCompareLt");
            assertTrue(prefix >= 0 && prefix < test, "the prefix precedes the test: " + names);
            assertEquals(2, root.getRootNodes().getNodes().size(),
                    "module and sideEffect only: no callback roots");
            assertNumber(session, "sideEffect", 105L);
        }
    }

    // ---- Method selection and calls ---------------------------------------------------

    @Test
    void methodSelectionCacheBehavior() throws Exception {
        try (ProtosStandaloneHostedSession session = open("cache", """
                first: {
                    value: (x) => { 1 }
                    succ: (u) => { second }
                }
                second: {
                    value: (x) => { 2 }
                    succ: (u) => { third }
                }
                third: {
                    value: (x) => { 3 }
                    succ: (u) => { fourth }
                }
                fourth: {
                    value: (x) => { 4 }
                    succ: (u) => { first }
                }

                monomorphic: () => {
                    n: 0
                    total: 0
                    (() => n < 8).whileTrue() {
                        total = total + first.value(n)
                        n = n + 1
                    }
                    total
                }

                polymorphic: () => {
                    n: 0
                    total: 0
                    current: first
                    (() => n < 8).whileTrue() {
                        total = total + current.value(n)
                        current = current.succ(0)
                        n = n + 1
                    }
                    total
                }

                plain: { f: 5 }

                useNonClosure: () => { plain.f(1) }
                """)) {
            for (int call = 0; call < WARM; call++) {
                assertNumber(session, "monomorphic", 8L);
                // Four receivers at one site: beyond the PIC limit of three.
                assertNumber(session, "polymorphic", 20L);
            }
            for (String name : List.of("monomorphic", "polymorphic")) {
                ProtosBytecodeRootNode root = root(session, name);
                List<String> names = unquickened(names(root));
                assertTrue(names.contains("c.GetMethod"), name + ": " + names);
                assertSelectionPrecedesEachCall(names);
                assertReplacedAbsent(name, names);
                // The selection cache keeps one stable lowering across the PIC states.
                assertSame(root, root(session, name), name);
                assertEquals(names, unquickened(names(root)), name);
            }
            // Past the PIC limit the current selection is still exact.
            assertNumber(session, "polymorphic", 20L);

            /*
             * Generic tier: a selected non-Closure value is sent ordinarily by
             * the shared call operation, never as an internal error.
             */
            Value nonClosure = session.prepareTopLevel("useNonClosure").executable();
            try {
                System.out.println("I115_H7_NON_CLOSURE_SELECTION_OUTCOME="
                        + nonClosure.execute());
            } catch (PolyglotException failure) {
                assertTrue(failure.isGuestException(), "a guest outcome: " + failure);
                assertFalse(failure.isInternalError(), failure.toString());
                System.out.println("I115_H7_NON_CLOSURE_SELECTION_OUTCOME=guest-error");
            }
        }
    }

    /** B: a hot call then redefined uses the new method, not the cached target. */
    @Test
    void redefinedMethodReplacesTheCachedTarget() throws Exception {
        try (ProtosStandaloneHostedSession session = open("redefined", """
                swap: { f: (x) => { x } }

                hot: () => {
                    n: 0
                    total: 0
                    (() => n < 100).whileTrue() {
                        total = total + swap.f(n)
                        n = n + 1
                    }
                    total
                }

                replaceSwap: () => {
                    swap.f = (x) => { 100 }
                    0
                }
                """)) {
            Value hot = session.prepareTopLevel("hot").executable();
            for (int call = 0; call < WARM; call++) {
                assertEquals(4950L, hot.execute().asLong());
            }
            session.prepareTopLevel("replaceSwap").executable().execute();
            for (int call = 0; call < WARM; call++) {
                assertEquals(10000L, hot.execute().asLong(), "the redefined method is called");
            }
        }
    }

    /**
     * A: nested B5 Prelude selection and transport. inner
     * ({@code target.identity(7)}) and outer ({@code target.nextCall()})
     * are Closures that inherit their prelude, and each performs an ordinary
     * method selection (GetMethod, then a call). I115: GetMethod selects with
     * the caller's effective Prelude
     * ({@code ProtosFunctionCallDispatchers.admittedCallerPrelude}); when a
     * root's B5 header inherits an untransported Prelude, that selection
     * registers NEEDS_CALLER_PRELUDE on the root, so both inner and outer
     * register it and their callers then transport the Prelude. The selected
     * Closure still executes with its own Prelude. run's need may reflect
     * that nested transport and is only required to be stable. After the
     * initial warm-up the result stays 7, no root's needs are invalidated
     * again, and every need state is unchanged.
     */
    @Test
    void inheritedPreludeSendRegistersAndConverges() throws Exception {
        try (ProtosStandaloneHostedSession session = open("caller-prelude", """
                makeInner: () => { (target) => { target.identity(7) } }
                inner: makeInner()

                holder: {
                    identity: (value) => { value }
                    nextCall: () => { inner }
                }

                makeOuter: () => { (target) => {
                    next: target.nextCall()
                    next(target)
                } }
                outer: makeOuter()

                run: () => { outer(holder) }
                """)) {
            ProtosBytecodeRootNode innerRoot = root(session, "inner");
            ProtosBytecodeRootNode outerRoot = root(session, "outer");
            ProtosBytecodeRootNode runRoot = root(session, "run");
            List<String> innerNames = unquickened(names(innerRoot));
            assertTrue(innerNames.contains("c.GetMethod")
                            && innerNames.contains("c.CallBinaryMethod"),
                    "inner uses the ordinary unary send: " + innerNames);
            assertSelectionPrecedesEachCall(innerNames);
            assertReplacedAbsent("inner", innerNames);
            int need = ProtosBytecodeRootNode.NEEDS_CALLER_PRELUDE;

            Value run = session.prepareTopLevel("run").executable();
            for (int call = 0; call < WARM; call++) {
                assertEquals(7L, run.execute().asLong());
            }
            boolean innerNeed = innerRoot.hasCallerNeed(need);
            boolean outerNeed = outerRoot.hasCallerNeed(need);
            boolean runNeed = runRoot.hasCallerNeed(need);
            System.out.println("I115 inherited-prelude NEEDS_CALLER_PRELUDE: inner=" + innerNeed
                    + " outer=" + outerNeed + " run=" + runNeed);
            // Owner decision: a non-capturing Closure retains its creator's
            // Prelude, so inner's GetMethod needs no caller transport.
            assertFalse(innerNeed,
                    "inner retains its Prelude and requests no caller prelude");
            assertFalse(outerNeed,
                    "outer retains its Prelude and requests no caller prelude");
            // run's need may reflect nested inherited-Prelude transport; it is
            // recorded here and only required to stay unchanged below.

            List<ProtosBytecodeRootNode> roots = List.of(innerRoot, outerRoot, runRoot);
            List<Assumption> settled = new ArrayList<>();
            for (ProtosBytecodeRootNode root : roots) {
                settled.add(root.callerNeedsStability());
            }
            for (int call = 0; call < WARM; call++) {
                assertEquals(7L, run.execute().asLong());
            }
            for (int index = 0; index < roots.size(); index++) {
                assertSame(settled.get(index), roots.get(index).callerNeedsStability(),
                        "root " + index + ": no repeated invalidation");
                assertTrue(settled.get(index).isValid());
            }
            assertFalse(innerRoot.hasCallerNeed(need), "inner still needs no caller prelude");
            assertFalse(outerRoot.hasCallerNeed(need), "outer still needs no caller prelude");
            assertEquals(runNeed, runRoot.hasCallerNeed(need), "run's need state is unchanged");
        }
    }

    /**
     * R2: the experimental B5 method-call header uses an unobservable
     * ReturnHome. A selected Closure with a materialized captured home
     * must therefore bypass the direct and indirect H7 tiers.
     */
    @Test
    void observableReturnHomeIsNotAdmittedToH7DirectHeader() throws Exception {
        try (ProtosStandaloneHostedSession session =
                open("return-home-admission", METHOD_CALL)) {
            ProtosClosureValue anchor =
                    session.prepareTopLevel("run").closureForTesting();

            session.processContextForTesting().callForRuntime(() -> {
                ProtosLexicalEnvironment module =
                        anchor.capturedLexicalEnvironmentForRuntime();
                ProtosObjectValue receiver =
                        assertInstanceOf(ProtosObjectValue.class,
                                module.readLocalSlotForRuntime("receiver").orElseThrow());
                ProtosClosureValue identity =
                        assertInstanceOf(ProtosClosureValue.class,
                                receiver.readLocalSlot("identity").orElseThrow());

                ProtosLanguageContext context = ProtosLanguageContext.current();

                assertTrue(identity.hasUnobservableInvocationReturnHomeForRuntime(),
                        "identity retains the unobservable-home fast path");
                assertTrue(
                        ProtosClosureCallPreparation.functionCallTarget(identity, context) != null,
                        "identity must still admit a direct target");

                ProtosReturnHome capturedHome = new ProtosReturnHome();
                ProtosClosureValue captured =
                        new ProtosClosureValue(
                                identity.definition(),
                                identity.capturedLexicalContexts(),
                                identity.capturedReceiver(),
                                identity.methodHome().orElse(null),
                                capturedHome,
                                identity.prelude().orElse(null),
                                identity.executionPlan().orElseThrow());

                assertFalse(
                        captured.hasUnobservableInvocationReturnHomeForRuntime(),
                        "a real captured ReturnHome is observable");
                // I115: the dispatcher's B5 header carries the captured home by
                // identity, never the unobservable marker.
                assertSame(
                        capturedHome,
                        ProtosDispatchTestSupport.direct(captured, null).targetArguments()[4],
                        "the unobservable B5 header must not receive a real home");
                assertTrue(capturedHome.isActive(),
                        "rejected admission must not complete the captured home");
                return null;
            });
        }
    }

    // ---- I115-H7-R3 physical contract ------------------------------------------------

    /** GetMethod answers the selected value itself; no descriptor type remains. */
    @Test
    void getMethodReturnsTheSelectedValue() throws Exception {
        assertTrue(java.util.Arrays.stream(
                        ProtosExperimentalGetMethodNode.class.getDeclaredClasses())
                        .noneMatch(nested -> nested.getSimpleName().equals("Method")),
                "the six-field Method descriptor is removed");
        try (ProtosStandaloneHostedSession session = open("get-method-value", METHOD_CALL)) {
            ProtosClosureValue anchor = session.prepareTopLevel("run").closureForTesting();
            session.processContextForTesting().callForRuntime(() -> {
                ProtosObjectValue receiver = assertInstanceOf(ProtosObjectValue.class,
                        anchor.capturedLexicalEnvironmentForRuntime()
                                .readLocalSlotForRuntime("receiver").orElseThrow());
                Object identity = receiver.readLocalSlot("identity").orElseThrow();
                // I115: GetMethod answers a bound method on the receiver.
                ProtosBoundMethod bound = assertInstanceOf(ProtosBoundMethod.class,
                        ProtosExperimentalGetMethodNode.select(receiver, "identity", null,
                                anchor.prelude().orElseThrow().newModuleActivation()));
                assertSame(identity, bound.callable());
                assertSame(identity, ProtosExperimentalGetMethodNode.selectOrNull(
                                receiver,
                                com.oracle.truffle.api.strings.TruffleString.fromJavaStringUncached(
                                        "identity",
                                        com.oracle.truffle.api.strings.TruffleString.Encoding.UTF_16))
                        .read(),
                        "the cached selection is installed without a prelude");
                assertSame(receiver, bound.receiver());
                assertSame(receiver, bound.methodHome());
                return null;
            });
        }
    }

    @Test
    void h11UnaryEntryOmitsClosureWithoutReadGlobal() throws Exception {
        try (ProtosStandaloneHostedSession session = open(
                "h11-entry-selection",
                """
                shared: 7

                holder: {
                    identity: (x) => { x }
                    captured: (x) => { shared }
                }

                runIdentity: () => { holder.identity(1) }
                runCaptured: () => { holder.captured(0) }
                """)) {
            ProtosBytecodeRootNode identity =
                    methodRoot(session, "runIdentity", "holder", "identity");
            ProtosBytecodeRootNode captured =
                    methodRoot(session, "runCaptured", "holder", "captured");

            /*
             * I115: both are entered with the one B5 header (the Closure in
             * slot 0, the argument at the supplied offset), and bind their
             * parameter the same way; only captured reads the module global,
             * at captured depth >= 1 from the Closure in its header.
             */
            List<String> identityNames = unquickened(names(identity));
            List<String> capturedNames = unquickened(names(captured));
            assertEquals(List.of(0), suppliedPositions(identity));
            assertEquals(List.of(0), suppliedPositions(captured));
            assertEquals(0, count(identityNames, "c.ReadGlobal"), identityNames.toString());
            assertEquals(1, count(capturedNames, "c.ReadGlobal"), capturedNames.toString());
            assertTrue(constantIntegers(captured, "ReadGlobal", "lexicalDepth").get(0) >= 1,
                    capturedNames.toString());
            assertReplacedAbsent("identity", identityNames);
            assertReplacedAbsent("captured", capturedNames);

            assertNumber(session, "runIdentity", 1L);
            assertNumber(session, "runCaptured", 7L);
        }
    }

    @Test
    void h11CapturedUnaryAbiRetainsTheGlobalReadOwner() throws Exception {
        try (ProtosStandaloneHostedSession session = open(
                "h11-captured-unary",
                "global: 7\nrun: (value) => { global }\n")) {
            ProtosClosureValue closure =
                    session.prepareTopLevel("run").closureForTesting();
            Object supplied = Long.valueOf(42L);

            session.processContextForTesting().callForRuntime(() -> {
                ProtosActivation caller =
                        closure.prelude().orElseThrow().newModuleActivation();
                // I115: the one B5 header; the global-read owner is the
                // invoked Closure in slot 0, never a guest argument.
                Object[] arguments =
                        ProtosFrameArguments.compactDirectClosureCallOne(
                                closure, caller, null, supplied);

                assertEquals(ProtosFrameArguments.SUPPLIED_ARGUMENT_OFFSET + 1,
                        arguments.length);
                assertSame(closure, arguments[0]);
                assertSame(supplied,
                        arguments[ProtosFrameArguments.SUPPLIED_ARGUMENT_OFFSET]);
                assertSame(supplied,
                        ProtosFrameArguments.compactSuppliedArgumentOrNull(arguments, 0));
                assertEquals(1, ProtosFrameArguments.suppliedArgumentCount(arguments));
                assertTrue(ProtosFrameArguments.isUnmaterializedCompactCall(arguments));
                assertSame(closure,
                        ProtosExperimentalReadGlobalNode.compactClosureOrNull(arguments));
                assertSame(
                        closure.capturedLexicalEnvironmentForRuntime(),
                        ProtosExperimentalReadGlobalNode.capturedModuleOrNull(
                                arguments, 1));
                assertEquals(7L, ((Number) ProtosExperimentalReadGlobalNode.readModuleBinding(
                        arguments,
                        com.oracle.truffle.api.strings.TruffleString.fromJavaStringUncached(
                                "global",
                                com.oracle.truffle.api.strings.TruffleString.Encoding.UTF_16),
                        1,
                        false)).longValue());
                return null;
            });
            assertNumber(session, "run", 7L);
        }
    }

    private static final String H7_OVERFLOW = """
            limit: 9223372036854775807

            holder: {
                grow: (n) => { n + 9223372036854775807 }
                growCaptured: (n) => { n + limit }
                same: (n) => { n }
                plusOne: (n) => { n + 1 }
                escape: (n) => { ^n }
            }

            runGrow: () => { holder.grow(1) }
            runGrowCaptured: () => { holder.growCaptured(1) }
            runKeep: () => { holder.same(41) + 1 }
            runPlusOne: () => { holder.plusOne(41) }
            runEscape: () => { holder.escape(5) }
            """;

    /**
     * I115: a signed-64 overflow in a unary-method callee, without
     * ReadGlobal (grow) and with it (growCaptured), reached through
     * GetMethod and CallUnaryMethod. The B5 header carries the invoked
     * Closure, so the overflow is exact, uses the Closure's own Prelude and
     * registers no caller need.
     */
    @Test
    void methodOverflowUsesTheClosurePrelude() throws Exception {
        try (ProtosStandaloneHostedSession session = open("h7-method-overflow", H7_OVERFLOW)) {
            for (String[] fixture : List.of(
                    new String[] {"runGrow", "grow"},
                    new String[] {"runGrowCaptured", "growCaptured"})) {
                String run = fixture[0];
                String selector = fixture[1];
                boolean captured = "growCaptured".equals(selector);
                ProtosClosureValue closure = methodClosure(session, run, "holder", selector);
                // Before the first call the Context target does not exist yet; the
                // template root has the same lowering and ABI facts.
                ProtosBytecodeRootNode template = methodRoot(session, run, "holder", selector);
                ProtosBytecodeRootNode caller = root(session, run);
                Object ownPrelude = session.processContextForTesting().callForRuntime(
                        closure::preludeOrNullForRuntime);

                List<String> calleeNames = unquickened(names(template));
                List<String> runNames = unquickened(names(caller));
                assertEquals(List.of(0), suppliedPositions(template), calleeNames.toString());
                assertEquals(1, count(calleeNames, "c.NumberAdd"), calleeNames.toString());
                assertEquals(1, count(runNames, "c.GetMethod"), runNames.toString());
                assertEquals(1, count(runNames, "c.CallBinaryMethod"), runNames.toString());
                assertSelectionPrecedesEachCall(runNames);
                for (List<String> names : List.of(calleeNames, runNames)) {
                    assertTrue(names.stream().noneMatch(
                                    name -> name.contains("MaterializeCurrentClosure")),
                            names.toString());
                    assertReplacedAbsent(selector, names);
                }
                // Only growCaptured reads the module global.
                assertEquals(captured ? 1 : 0, count(calleeNames, "c.ReadGlobal"), selector);
                assertFalse(template.hasCallerNeed(ProtosBytecodeRootNode.NEEDS_CALLER_PRELUDE));

                /*
                 * I115: the reduced H11 entries are retired; every callee is
                 * entered with the dispatcher's B5 header, which carries the
                 * invoked Closure, so the overflow uses the Closure's own
                 * Prelude and registers no caller need.
                 */
                Object first = rawResult(session, run);
                ProtosLargeIntegerValue large =
                        assertInstanceOf(ProtosLargeIntegerValue.class, first);
                assertLarge(session, run, "9223372036854775808");
                ProtosBytecodeRootNode callee = admittedCallee(session, closure);
                assertEquals(calleeNames, unquickened(names(callee)), "same lowering");
                if (ownPrelude != null) {
                    assertSame(((ProtosPrelude) ownPrelude).bigIntegerPrototype(),
                            large.parent().orElseThrow(), selector);
                }
                assertFalse(callee.hasCallerNeed(ProtosBytecodeRootNode.NEEDS_CALLER_PRELUDE),
                        selector);

                Assumption stable = callee.callerNeedsStability();
                for (int call = 0; call < WARM; call++) {
                    assertLarge(session, run, "9223372036854775808");
                }
                assertSame(stable, callee.callerNeedsStability(), "no repeated registration");
                assertTrue(stable.isValid());
                assertSame(callee, admittedCallee(session, closure), "the same direct target");
                assertEquals(calleeNames, unquickened(names(callee)));
                assertEquals(calleeNames, unquickened(names(template)));
                assertEquals(runNames, unquickened(names(caller)));
            }
        }
    }

    /**
     * I115: a method without arithmetic binds its parameter from supplied
     * argument 0 of the B5 header and never requests the caller Prelude; a
     * non-overflowing NumberAdd in the caller returns a raw Long and
     * requests nothing either.
     */
    @Test
    void methodWithoutOverflowRequestsNoPrelude() throws Exception {
        try (ProtosStandaloneHostedSession session = open("h7-method-keep", H7_OVERFLOW)) {
            ProtosClosureValue closure = methodClosure(session, "runKeep", "holder", "same");
            assertEquals(42L, assertInstanceOf(Long.class, rawResult(session, "runKeep")));
            ProtosBytecodeRootNode callee = admittedCallee(session, closure);
            List<String> calleeNames = unquickened(names(callee));
            assertEquals(List.of(0), suppliedPositions(callee), calleeNames.toString());
            assertEquals(0, count(calleeNames, "c.ReadGlobal"), calleeNames.toString());
            assertEquals(0, count(calleeNames, "c.NumberAdd"), calleeNames.toString());
            assertReplacedAbsent("same", calleeNames);
            ProtosBytecodeRootNode caller = root(session, "runKeep");

            Assumption calleeStable = callee.callerNeedsStability();
            Assumption callerStable = caller.callerNeedsStability();
            for (int call = 0; call < WARM; call++) {
                assertEquals(42L, assertInstanceOf(Long.class, rawResult(session, "runKeep")));
            }
            for (ProtosBytecodeRootNode root : List.of(callee, caller)) {
                assertFalse(root.hasCallerNeed(ProtosBytecodeRootNode.NEEDS_CALLER_PRELUDE));
            }
            assertSame(calleeStable, callee.callerNeedsStability());
            assertSame(callerStable, caller.callerNeedsStability());
            assertTrue(calleeStable.isValid() && callerStable.isValid());
        }
    }

    /**
     * I115: an arithmetic method (NumberAdd) is reached by the ordinary
     * unary send (GetMethod, then CallUnaryMethod) with the B5 header; its
     * parameter is supplied argument 0 and its long result comes back as a
     * raw Long through the root and host boundary.
     */
    @Test
    void arithmeticMethodReturnsRawLongThroughUnarySend() throws Exception {
        try (ProtosStandaloneHostedSession session = open("h7-method-plus-one", H7_OVERFLOW)) {
            ProtosClosureValue closure =
                    methodClosure(session, "runPlusOne", "holder", "plusOne");
            ProtosBytecodeRootNode template =
                    methodRoot(session, "runPlusOne", "holder", "plusOne");
            ProtosBytecodeRootNode caller = root(session, "runPlusOne");
            List<String> calleeNames = unquickened(names(template));
            List<String> runNames = unquickened(names(caller));
            assertEquals(List.of(0), suppliedPositions(template), calleeNames.toString());
            assertEquals(0, count(calleeNames, "c.ReadGlobal"), calleeNames.toString());
            assertEquals(1, count(calleeNames, "c.NumberAdd"), calleeNames.toString());
            assertEquals(1, count(runNames, "c.GetMethod"), runNames.toString());
            assertEquals(1, count(runNames, "c.CallBinaryMethod"), runNames.toString());
            assertSelectionPrecedesEachCall(runNames);
            for (List<String> names : List.of(calleeNames, runNames)) {
                assertTrue(names.stream().noneMatch(
                                name -> name.contains("MaterializeCurrentClosure")),
                        names.toString());
                assertReplacedAbsent("plusOne", names);
            }

            assertEquals(42L, assertInstanceOf(Long.class, rawResult(session, "runPlusOne")));
            ProtosBytecodeRootNode callee = admittedCallee(session, closure);
            assertEquals(calleeNames, unquickened(names(callee)), "the template's lowering");

            Assumption stable = callee.callerNeedsStability();
            for (int call = 0; call < WARM; call++) {
                assertEquals(42L,
                        assertInstanceOf(Long.class, rawResult(session, "runPlusOne")));
            }
            assertFalse(callee.hasCallerNeed(ProtosBytecodeRootNode.NEEDS_CALLER_PRELUDE));
            assertSame(stable, callee.callerNeedsStability());
            assertTrue(stable.isValid());
            assertSame(callee, admittedCallee(session, closure));
            assertEquals(calleeNames, unquickened(names(callee)), "after quickening");
            assertEquals(runNames, unquickened(names(caller)), "after quickening");
            /*
             * I115: the CallBinaryMethod direct tier is retired. The ordinary
             * unary send is GetMethod followed by CallUnaryMethod; the direct
             * target is evidenced by the stable admitted callee above and by
             * the raw Long result, not by a quickened instruction name.
             */
            List<String> quickened = names(caller);
            assertTrue(quickened.stream().anyMatch(name -> name.startsWith("c.CallBinaryMethod")),
                    "the call site keeps CallBinaryMethod after quickening: " + quickened);
            // CallBinaryMethod is the current one-argument method send.
        }
    }

    /**
     * I115: there is no H7 admission subset any more. A method with a
     * non-local return (observable ReturnHome) and plusOne in the same
     * holder are both dispatched through the shared function PIC to their
     * single lowerings, with correct results.
     */
    @Test
    void nonLocalReturnMethodUsesTheSharedDispatch() throws Exception {
        try (ProtosStandaloneHostedSession session = open("h7-method-fallback", H7_OVERFLOW)) {
            assertNumber(session, "runEscape", 5L);
            assertNumber(session, "runPlusOne", 42L);
            ProtosClosureValue escape = methodClosure(session, "runEscape", "holder", "escape");
            ProtosClosureValue plusOne = methodClosure(session, "runPlusOne", "holder", "plusOne");
            // I115: the H7 admission tier is retired; every source method is
            // dispatched by the shared function PIC to its own single lowering.
            ProtosBytecodeRootNode plusOneCallee = admittedCallee(session, plusOne);
            assertEquals(
                    unquickened(names(methodRoot(session, "runPlusOne", "holder", "plusOne"))),
                    unquickened(names(plusOneCallee)));
            ProtosBytecodeRootNode escapeRoot =
                    methodRoot(session, "runEscape", "holder", "escape");
            assertEquals(List.of(0), suppliedPositions(escapeRoot));
            assertReplacedAbsent("escape", unquickened(names(escapeRoot)));
            assertNotNull(escape);
            for (int call = 0; call < WARM; call++) {
                assertNumber(session, "runEscape", 5L);
            }
        }
    }

    /**
     * B5 invariant for a guest argument that is itself a Closure: in both the
     * direct-call and the immediate-method-call forms of the one header, the
     * invoked Closure (the global-read owner and Prelude source) is slot 0,
     * the guest argument is kept by identity at the supplied offset, and the
     * header is never read as the argument nor the argument as the owner.
     */
    @Test
    void b5HeaderKeepsAClosureArgumentDistinctFromTheInvocation() throws Exception {
        try (ProtosStandaloneHostedSession session = open("b5-closure-argument", H7_OVERFLOW)) {
            ProtosClosureValue owner = methodClosure(session, "runGrow", "holder", "growCaptured");
            ProtosClosureValue argument = methodClosure(session, "runGrow", "holder", "grow");
            session.processContextForTesting().callForRuntime(() -> {
                ProtosPrelude prelude = owner.prelude().orElseThrow();
                ProtosActivation caller = prelude.newModuleActivation();
                ProtosObjectValue receiver = new ProtosObjectValue(ProtosObjectValue.rootObject());
                Object[] direct =
                        ProtosFrameArguments.compactDirectClosureCallOne(
                                owner, caller, null, argument);
                Object[] method =
                        ProtosFrameArguments.compactImmediateMethodCallOne(
                                owner, receiver, receiver, caller,
                                owner.invocationReturnHomeForRuntime(), argument);

                for (Object[] entry : List.of(direct, method)) {
                    assertEquals(ProtosFrameArguments.SUPPLIED_ARGUMENT_OFFSET + 1, entry.length);
                    assertSame(owner, entry[0], "slot 0 is the invoked Closure");
                    assertSame(argument,
                            ProtosFrameArguments.compactSuppliedArgumentOrNull(entry, 0));
                    assertEquals(1, ProtosFrameArguments.suppliedArgumentCount(entry));
                    assertSame(owner, ProtosExperimentalReadGlobalNode.compactClosureOrNull(entry));
                    assertSame(owner.capturedLexicalEnvironmentForRuntime(),
                            ProtosExperimentalReadGlobalNode.capturedModuleOrNull(entry, 1));
                    assertSame(prelude, ProtosFrameArguments.preludeOrNull(entry),
                            "the Prelude is the invocation's, never the argument");
                    assertNotSame(argument, ProtosFrameArguments.preludeOrNull(entry));
                }
                return null;
            });
            assertLarge(session, "runGrowCaptured", "9223372036854775808");
        }
    }

    /**
     * B5 invariant: the fixed five-slot header is followed by exactly the
     * supplied positional values in order, by identity; a position the call
     * did not supply reads canonical null (D203), never a header slot.
     */
    @Test
    void b5HeaderSuppliesArgumentsAfterTheFixedHeader() throws Exception {
        try (ProtosStandaloneHostedSession session = open("b5-supplied", H7_OVERFLOW)) {
            ProtosClosureValue closure = methodClosure(session, "runKeep", "holder", "same");
            session.processContextForTesting().callForRuntime(() -> {
                ProtosActivation caller = closure.prelude().orElseThrow().newModuleActivation();
                Object first = Long.valueOf(42L);
                Object second = new ProtosObjectValue(ProtosObjectValue.rootObject());

                Object[] none = ProtosFrameArguments.compactDirectClosureCall(
                        closure, caller, null, new Object[0]);
                Object[] one = ProtosFrameArguments.compactDirectClosureCall(
                        closure, caller, null, new Object[] {first});
                Object[] two = ProtosFrameArguments.compactDirectClosureCall(
                        closure, caller, null, new Object[] {first, second});

                assertEquals(ProtosFrameArguments.HEADER_LENGTH, none.length);
                assertEquals(ProtosFrameArguments.SUPPLIED_ARGUMENT_OFFSET + 1, one.length);
                assertEquals(ProtosFrameArguments.SUPPLIED_ARGUMENT_OFFSET + 2, two.length);
                assertEquals(0, ProtosFrameArguments.suppliedArgumentCount(none));
                assertEquals(2, ProtosFrameArguments.suppliedArgumentCount(two));
                assertSame(first, ProtosFrameArguments.compactSuppliedArgumentOrNull(one, 0));
                assertSame(first, ProtosFrameArguments.compactSuppliedArgumentOrNull(two, 0));
                assertSame(second, ProtosFrameArguments.compactSuppliedArgumentOrNull(two, 1));
                assertSame(com.guillermomolina.protos.runtime.ProtosNullValue.INSTANCE,
                        ProtosFrameArguments.compactSuppliedArgumentOrNull(none, 0),
                        "an unsupplied position is canonical null");
                assertSame(com.guillermomolina.protos.runtime.ProtosNullValue.INSTANCE,
                        ProtosFrameArguments.compactSuppliedArgumentOrNull(one, 1));
                for (Object[] entry : List.of(none, one, two)) {
                    assertSame(closure, entry[0]);
                }
                return null;
            });
            assertNumber(session, "runKeep", 42L);
        }
    }

    /**
     * CopySuppliedArguments (grouped binding) is one
     * specialization copying the supplied positions from the frame arguments
     * straight into the consecutive LOCAL parameter locals, with no
     * fallback; a captured (CELL) parameter keeps LoadSuppliedArgument stored
     * into its cell. Argument identity is exact for generic and primitive
     * values.
     */
    @Test
    void copyArgumentsStoresTheSingleArgumentWithoutFallback() throws Exception {
        List<String> specializations = new ArrayList<>();
        for (java.lang.reflect.Method method :
                ProtosBytecodeRootNode.CopySuppliedArguments.class.getDeclaredMethods()) {
            assertNull(method.getAnnotation(com.oracle.truffle.api.dsl.Fallback.class),
                    "no fallback: " + method.getName());
            if (method.getAnnotation(com.oracle.truffle.api.dsl.Specialization.class) != null) {
                specializations.add(method.getName());
            }
        }
        assertEquals(List.of("perform"), specializations);
        try (ProtosStandaloneHostedSession session = open("copy-arguments", METHOD_CALL
                + """

                holder: {
                    same: (value) => { value }
                    keep: (value) => { () => value }
                }
                identityOfObject: () => {
                    probe: { tag: 1 }
                    holder.same(probe) === probe
                }
                capturedValue: () => { holder.keep(9)() }
                """)) {
            ProtosBytecodeRootNode identity = methodRoot(session, "run", "receiver", "identity");
            assertEquals(IDENTITY_DSL, unquickened(names(identity)));
            assertEquals(List.of(0), suppliedPositions(identity));
            assertTrue(localNames(identity).contains("value"), "a LOCAL parameter");

            ProtosBytecodeRootNode keep = methodRoot(session, "capturedValue", "holder", "keep");
            List<String> keepNames = unquickened(names(keep));
            assertEquals(List.of(0), suppliedPositions(keep), keepNames.toString());
            assertTrue(keepNames.contains("c.CreateCell") && keepNames.contains("c.StoreCell"),
                    "a captured parameter is stored into its cell: " + keepNames);
            assertReplacedAbsent("keep", keepNames);

            for (int call = 0; call < WARM; call++) {
                assertEquals(499500L,
                        session.prepareTopLevel("run").executable().execute().asLong());
                assertTrue(session.prepareTopLevel("identityOfObject").executable().execute()
                        .asBoolean(), "a generic argument keeps its identity");
                assertNumber(session, "capturedValue", 9L);
            }
        }
    }

    /**
     * I115: identity is reached by the ordinary unary send (GetMethod, then
     * CallUnaryMethod) to one stable function target, and the caller
     * transports nothing because identity requested nothing. The retired
     * CallBinaryMethod direct quickening is no longer the evidence; the
     * stable target identity is.
     */
    @Test
    void identityIsCalledThroughStableUnarySendWithoutCallerTransport() throws Exception {
        try (ProtosStandaloneHostedSession session = open("direct-identity", METHOD_CALL)) {
            ProtosBytecodeRootNode run = root(session, "run");
            ProtosBytecodeRootNode identity = methodRoot(session, "run", "receiver", "identity");
            ProtosClosureValue identityClosure =
                    methodClosure(session, "run", "receiver", "identity");
            Value executable = session.prepareTopLevel("run").executable();
            assertEquals(499500L, executable.execute().asLong());
            ProtosBytecodeRootNode target = admittedCallee(session, identityClosure);
            List<String> identityNames = unquickened(names(identity));
            assertEquals(identityNames, unquickened(names(target)), "the single lowering");
            for (int call = 0; call < WARM; call++) {
                assertEquals(499500L, executable.execute().asLong());
            }
            assertSame(target, admittedCallee(session, identityClosure), "stable target");
            assertFalse(identity.hasCallerNeed(ProtosBytecodeRootNode.NEEDS_CALLER_PRELUDE));
            assertFalse(identity.hasCallerNeed(ProtosBytecodeRootNode.NEEDS_EXACT_ACTIVATION));
            assertFalse(run.hasCallerNeed(ProtosBytecodeRootNode.NEEDS_EXACT_ACTIVATION),
                    "run never published its activation for the send");
            List<String> executed = unquickened(names(run));
            assertEquals(1, count(executed, "c.CallBinaryMethod"), executed.toString());
            assertSelectionPrecedesEachCall(executed);
            // CallBinaryMethod is the current one-argument method send.
        }
    }

    /** The cached ReadGlobal location observes a rebinding without another lookup. */
    @Test
    void cachedGlobalLocationObservesRebinding() throws Exception {
        try (ProtosStandaloneHostedSession session = open("global-location", """
                first: { f: (x) => { x } }
                second: { f: (x) => { x + 100 } }
                target: first

                hot: () => {
                    n: 0
                    total: 0
                    (() => n < 100).whileTrue() {
                        total = total + target.f(n)
                        n = n + 1
                    }
                    total
                }

                retarget: () => {
                    target = second
                    0
                }
                """)) {
            ProtosClosureValue anchor = session.prepareTopLevel("hot").closureForTesting();
            ProtosModuleBindingLocation location =
                    session.processContextForTesting().callForRuntime(() -> {
                        assertNull(ProtosExperimentalReadGlobalNode.locationOrNull(
                                        anchor, utf16("absentName"), 1),
                                "a missing name does not resolve as a module slot");
                        return ProtosExperimentalReadGlobalNode.locationOrNull(
                                anchor, utf16("target"), 1);
                    });
            assertNotNull(location, "target has a module storage location");
            Object before = session.processContextForTesting().callForRuntime(
                    () -> location.readOrNull(DynamicObject.GetNode.getUncached()));

            Value hot = session.prepareTopLevel("hot").executable();
            for (int call = 0; call < WARM; call++) {
                assertEquals(4950L, hot.execute().asLong());
            }
            session.prepareTopLevel("retarget").executable().execute();
            for (int call = 0; call < WARM; call++) {
                assertEquals(14950L, hot.execute().asLong(), "the rebound global is read");
            }
            session.processContextForTesting().callForRuntime(() -> {
                Object after = location.readOrNull(DynamicObject.GetNode.getUncached());
                assertNotSame(before, after, "the location reads the storage, not a copy");
                assertSame(moduleBinding(anchor, "target"), after);
                return null;
            });
        }
    }

    // ---- I115-H7-R4 Object global location --------------------------------------------

    /** A compact global read retains the original Object after quickening. */
    @Test
    void objectGlobalLocationReadsCurrentValueAfterQuickening() throws Exception {
        try (ProtosStandaloneHostedSession session = open("r4-object-location", METHOD_CALL)) {
            ProtosClosureValue anchor =
                    session.prepareTopLevel("run").closureForTesting();
            Value run = session.prepareTopLevel("run").executable();

            for (int call = 0; call < WARM; call++) {
                assertEquals(499500L, run.execute().asLong());
            }

            session.processContextForTesting().callForRuntime(() -> {
                Object receiver = moduleBinding(anchor, "receiver");
                ProtosModuleBindingLocation location =
                        ProtosExperimentalReadGlobalNode.locationOrNull(
                                anchor, utf16("receiver"), 1);

                assertNotNull(location, "the Object location is resolved");
                assertTrue(location.isPresent(DynamicObject.GetNode.getUncached()));
                assertSame(receiver, location.readOrNull(DynamicObject.GetNode.getUncached()),
                        "the location returns the original Protos object");
                return null;
            });

            assertEquals(499500L, run.execute().asLong());
        }
    }

    /** The cached physical location reads a rebound Object, not a cached value. */
    @Test
    void objectGlobalLocationObservesRebinding() throws Exception {
        try (ProtosStandaloneHostedSession session = open("r4-object-rebinding", """
                first: { f: (x) => { x } }
                second: { f: (x) => { x + 100 } }
                target: first

                hot: () => { target.f(7) }

                retarget: () => {
                    target = second
                    0
                }
                """)) {
            ProtosClosureValue anchor =
                    session.prepareTopLevel("hot").closureForTesting();
            Value hot = session.prepareTopLevel("hot").executable();

            for (int call = 0; call < WARM; call++) {
                assertEquals(7L, hot.execute().asLong());
            }

            ProtosModuleBindingLocation location =
                    session.processContextForTesting().callForRuntime(() ->
                            ProtosModuleBindingLocation.ofOrNull(
                                    anchor.capturedLexicalEnvironmentForRuntime().context(),
                                    "target"));
            assertNotNull(location, "initial Object location is resolved");
            Object before = session.processContextForTesting().callForRuntime(() -> {
                Object value = moduleBinding(anchor, "target");
                assertSame(value, location.readOrNull(DynamicObject.GetNode.getUncached()));
                return value;
            });

            session.prepareTopLevel("retarget").executable().execute();

            for (int call = 0; call < WARM; call++) {
                assertEquals(107L, hot.execute().asLong());
            }

            session.processContextForTesting().callForRuntime(() -> {
                Object after = moduleBinding(anchor, "target");
                assertNotSame(before, after, "the global binding was rebound");
                assertSame(after, location.readOrNull(DynamicObject.GetNode.getUncached()),
                        "the location observes the current stored value");
                return null;
            });
        }
    }

    /**
     * I115 (replaces the retired frame-layout validation): a module global
     * lives in the module context's own storage, not in any frame slot. The
     * location resolved for a captured read addresses exactly that context's
     * binding: it reads the original binding, a missing name has no location,
     * and the same address observes removal and recreation in that context.
     */
    @Test
    void globalLocationAddressesTheModuleStorage() throws Exception {
        try (ProtosStandaloneHostedSession session = open("r4-module-storage", METHOD_CALL)) {
            ProtosClosureValue anchor =
                    session.prepareTopLevel("run").closureForTesting();

            session.processContextForTesting().callForRuntime(() -> {
                ProtosLexicalEnvironment module =
                        anchor.capturedLexicalEnvironmentForRuntime();
                ProtosObjectValue moduleContext = module.context();
                Object receiver = moduleContext.readLocalSlot("receiver").orElseThrow();
                DynamicObject.GetNode get = DynamicObject.GetNode.getUncached();

                ProtosModuleBindingLocation location =
                        ProtosExperimentalReadGlobalNode.locationOrNull(
                                anchor, utf16("receiver"), 1);
                assertNotNull(location, "the module binding has a storage location");
                assertSame(receiver, location.readOrNull(get),
                        "the location holds the original binding");
                assertNull(ProtosModuleBindingLocation.ofOrNull(moduleContext, "wrong-name"),
                        "a missing name has no module location");
                assertNull(ProtosExperimentalReadGlobalNode.locationOrNull(
                                null, utf16("receiver"), 1),
                        "no Closure, no captured module location");

                // The same address follows the binding through removal and recreation.
                assertSame(receiver, moduleContext.removeLocalSlot("receiver"));
                assertFalse(location.isPresent(get));
                assertNull(location.readOrNull(get));
                ProtosObjectValue replacement = new ProtosObjectValue(ProtosObjectValue.rootObject());
                moduleContext.createLocalSlot("receiver", replacement);
                assertTrue(location.isPresent(get));
                assertSame(replacement, location.readOrNull(get));
                moduleContext.assignLocalSlot("receiver", receiver);
                assertSame(receiver, location.readOrNull(get));
                return null;
            });
            assertEquals(499500L, session.prepareTopLevel("run").executable().execute().asLong());
        }
    }

    /** A published B5 invocation never enters the compact Closure PIC. */
    @Test
    void publishedHeaderDoesNotUseCompactGlobalCache() throws Exception {
        try (ProtosStandaloneHostedSession session =
                open("r4-published-header", METHOD_CALL)) {
            ProtosClosureValue anchor =
                    session.prepareTopLevel("run").closureForTesting();

            session.processContextForTesting().callForRuntime(() -> {
                ProtosActivation activation =
                        ProtosActivation.forClosureInvocation(
                                anchor,
                                List.of(),
                                anchor.prelude().orElseThrow());

                Object[] published =
                        ProtosFrameArguments.publishedInvocation(
                                activation, ProtosDispatchTestSupport.target(anchor));

                assertTrue(ProtosFrameArguments.hasPublishedActivation(published));
                assertNull(
                        ProtosExperimentalReadGlobalNode.compactClosureOrNull(published),
                        "a published activation is not a compact Closure");

                Object[] compact =
                        ProtosFrameArguments.compactDirectClosureCallZeroNoHome(
                                anchor, activation);

                assertSame(
                        anchor,
                        ProtosExperimentalReadGlobalNode.compactClosureOrNull(compact),
                        "the original compact Closure remains admissible");

                Object expected = moduleBinding(anchor, "receiver");

                assertFalse(ProtosFrameArguments.isUnmaterializedCompactCall(published));
                assertSame(
                        expected,
                        ProtosExperimentalReadGlobalNode.readModuleBinding(
                                published, utf16("receiver"), 1, false),
                        "the published header resolves through the generic path");
                assertSame(
                        expected,
                        ProtosExperimentalReadGlobalNode.readModuleBinding(
                                compact, utf16("receiver"), 1, false),
                        "the compact header resolves the same module binding");

                return null;
            });
        }
    }

    /**
     * I115 (replaces the retired frame-slot representation transition): a
     * module global holding an Integer, then an Object, then an Integer
     * again is returned as the exact guest value through the cached read,
     * the storage location and the generic published-header read alike.
     */
    @Test
    void numericGlobalReadsReturnGuestIntegers() throws Exception {
        try (ProtosStandaloneHostedSession session =
                open("r4-numeric-global", """
                        target: 41
                        reader: () => { target }
                        toObject: () => {
                            target = { f: 5 }
                            0
                        }
                        toWide: () => {
                            target = 4294967296
                            0
                        }
                        """)) {
            ProtosClosureValue anchor =
                    session.prepareTopLevel("reader").closureForTesting();
            for (int call = 0; call < WARM; call++) {
                assertNumber(session, "reader", 41L);
            }

            session.processContextForTesting().callForRuntime(() -> {
                ProtosModuleBindingLocation location =
                        ProtosExperimentalReadGlobalNode.locationOrNull(
                                anchor, utf16("target"), 1);
                assertNotNull(location);
                assertEquals(41L, ((Number) location.readOrNull(
                        DynamicObject.GetNode.getUncached())).longValue());

                ProtosActivation activation =
                        ProtosActivation.forClosureInvocation(
                                anchor, List.of(), anchor.prelude().orElseThrow());
                Object[] published = ProtosFrameArguments.publishedInvocation(
                        activation, ProtosDispatchTestSupport.target(anchor));
                assertEquals(41L, ((Number) ProtosExperimentalReadGlobalNode.readModuleBinding(
                        published, utf16("target"), 1, false)).longValue(),
                        "the generic route returns the guest Integer");
                return null;
            });

            session.prepareTopLevel("toObject").executable().execute();
            Value object = session.prepareTopLevel("reader").executable().execute();
            assertFalse(object.isNumber(), "the rebound Object is read after quickening");

            session.prepareTopLevel("toWide").executable().execute();
            for (int call = 0; call < WARM; call++) {
                assertNumber(session, "reader", 4294967296L);
            }
        }
    }

    /** A Closure outside the H7 admission and a missing selector use the generic tier. */
    @Test
    void genericFallbackRemainsReachable() throws Exception {
        try (ProtosStandaloneHostedSession session = open("generic-fallback", """
                plain: { f: 5 }
                missing: () => { plain.absent(1) }
                """)) {
            Value missing = session.prepareTopLevel("missing").executable();
            for (int attempt = 0; attempt < 3; attempt++) {
                PolyglotException failure = assertThrows(PolyglotException.class, missing::execute);
                assertTrue(failure.isGuestException(), "a guest Error: " + failure);
                assertFalse(failure.isInternalError(), failure.toString());
            }
        }
        try (ProtosStandaloneHostedSession session =
                open("generic-unadmitted", METHOD_CALL)) {
            ProtosClosureValue anchor = session.prepareTopLevel("run").closureForTesting();
            // I115: no H7 admission exists; run keeps its own root.
            assertNotNull(session.processContextForTesting().callForRuntime(() ->
                    ProtosClosureCallPreparation.functionCallTarget(
                            anchor, ProtosLanguageContext.current())));
        }
    }

    // ---- Helpers ----------------------------------------------------------------------

    /*
     * The physical roots of the examined compilation: run, identity (the
     * root of the selected Closure's own execution plan) and exactly one
     * further semantic activation root, the module. No root is hidden.
     */
    private static String census(ProtosBytecodeRootNode run, ProtosBytecodeRootNode identity) {
        run.getRootNodes().ensureSourceInformation();
        List<ProtosBytecodeRootNode> roots = run.getRootNodes().getNodes().stream()
                .map(ProtosBytecodeRootNode.class::cast)
                .toList();
        StringBuilder report = new StringBuilder();
        int runCount = 0;
        int identityCount = 0;
        List<ProtosBytecodeRootNode> others = new ArrayList<>();
        for (int index = 0; index < roots.size(); index++) {
            ProtosBytecodeRootNode current = roots.get(index);
            assertSame(run.getRootNodes(), current.getRootNodes());
            String role;
            if (current == run) {
                role = "run";
                runCount++;
            } else if (current == identity) {
                role = "identity";
                identityCount++;
            } else {
                role = "module";
                others.add(current);
            }
            List<Instruction> instructions =
                    current.getBytecodeNode().getInstructionsAsList();
            report.append("ROOT_INDEX=").append(index)
                    .append(" ROLE=").append(role)
                    .append(" ROOT=").append(current)
                    .append(" SOURCE=").append(current.getSourceSection())
                    .append(" DSL_FULL=").append(instructions.size())
                    .append('\n');
            for (int n = 0; n < instructions.size(); n++) {
                Instruction instruction = instructions.get(n);
                report.append("INSTRUCTION[").append(index).append("][").append(n)
                        .append("]=").append(ProtosInstructionNames.of(instruction))
                        .append(" | ").append(instruction)
                        .append('\n');
            }
            System.out.println("I115_H7_ROOT[" + index + "]_ROLE=" + role
                    + " DSL_FULL=" + instructions.size());
        }
        assertEquals(1, runCount, "run root: " + report);
        assertEquals(1, identityCount, "identity root: " + report);
        assertEquals(1, others.size(),
                "exactly one module root; no callback roots for the loop: " + report);
        assertTrue(others.get(0).isSemanticActivationRoot(), "the module is a semantic root");
        assertEquals(3, roots.size(), "module, run and identity roots: " + report);
        assertRunArchitecture(unquickened(names(run)));
        assertEquals(IDENTITY_DSL, unquickened(names(identity)));
        return report.toString();
    }

    /*
     * The root of the Closure selected by {@code holder.selector}, read from
     * the module captured by the top-level Closure {@code topLevel}.
     */
    private static ProtosBytecodeRootNode methodRoot(
            ProtosStandaloneHostedSession session,
            String topLevel,
            String holder,
            String selector) throws Exception {
        ProtosClosureValue anchor =
                session.prepareTopLevel(topLevel).closureForTesting();
        return session.processContextForTesting().callForRuntime(() -> {
            ProtosLexicalEnvironment module = anchor.capturedLexicalEnvironmentForRuntime();
            ProtosObjectValue object = assertInstanceOf(ProtosObjectValue.class,
                    module.readLocalSlotForRuntime(holder).orElseThrow());
            ProtosClosureValue method = assertInstanceOf(ProtosClosureValue.class,
                    object.readLocalSlot(selector).orElseThrow());
            return method.executionPlan().orElseThrow().bytecodeActivationRootForTesting();
        });
    }

    /*
     * The Context-owned function target CallUnaryMethod enters for closure;
     * it exists once the method has been called in this Context.
     */
    private static ProtosBytecodeRootNode admittedCallee(
            ProtosStandaloneHostedSession session, ProtosClosureValue closure) {
        return session.processContextForTesting().callForRuntime(() ->
                assertInstanceOf(ProtosBytecodeRootNode.class,
                        ProtosClosureCallPreparation.functionCallTarget(
                                        closure, ProtosLanguageContext.current())
                                .getRootNode()));
    }

    /* The raw result of a zero-argument top-level root, inside its Context. */
    private static Object rawResult(ProtosStandaloneHostedSession session, String name)
            throws Exception {
        ProtosHostExecutableClosure host = session.prepareTopLevel(name).hostExecutableForTesting();
        return session.processContextForTesting().callForRuntime(() ->
                host.target.call(ProtosFrameArguments.compactDirectClosureCallZeroNoHome(
                        host.closure, host.caller)));
    }

    private static ProtosClosureValue methodClosure(
            ProtosStandaloneHostedSession session,
            String topLevel,
            String holder,
            String selector) throws Exception {
        ProtosClosureValue anchor =
                session.prepareTopLevel(topLevel).closureForTesting();
        return session.processContextForTesting().callForRuntime(() -> {
            ProtosLexicalEnvironment module = anchor.capturedLexicalEnvironmentForRuntime();
            ProtosObjectValue object = assertInstanceOf(ProtosObjectValue.class,
                    module.readLocalSlotForRuntime(holder).orElseThrow());
            return assertInstanceOf(ProtosClosureValue.class,
                    object.readLocalSlot(selector).orElseThrow());
        });
    }

    /* The While: one conditional exit and one backward edge, no callback call. */
    private static void assertDirectLoop(ProtosBytecodeRootNode root) {
        List<Instruction> instructions = root.getBytecodeNode().getInstructionsAsList();
        boolean exit = false;
        boolean backward = false;
        for (Instruction instruction : instructions) {
            exit |= instruction.getName().startsWith("branch.false");
            for (Instruction.Argument argument : instruction.getArguments()) {
                if (argument.getKind() == Instruction.Argument.Kind.BYTECODE_INDEX
                        && argument.asBytecodeIndex() < instruction.getBytecodeIndex()) {
                    backward = true;
                }
            }
        }
        assertTrue(exit, "the While tests its condition");
        assertTrue(backward, "the While has a backward edge");
    }

    private static void assertReplacedAbsent(String root, List<String> names) {
        for (String replaced : REPLACED) {
            assertTrue(names.stream().noneMatch(name -> name.contains(replaced)),
                    root + " emits " + replaced + ": " + names);
        }
    }

    private static void assertNumber(
            ProtosStandaloneHostedSession session, String name, long expected)
            throws Exception {
        Value result = session.prepareTopLevel(name).executable().execute();
        assertTrue(result.isNumber(), name + " is a guest number: " + result);
        assertTrue(result.fitsInLong(), name + ": " + result);
        assertEquals(expected, result.asLong(), name);
    }

    /*
     * The host-boundary result of a source root: the raw signed-64 Long,
     * through the one shared return epilogue (I115: no root-specific
     * return mode exists).
     */
    private static void assertRawLong(
            ProtosStandaloneHostedSession session, String name, long expected)
            throws Exception {
        ProtosHostExecutableClosure host =
                session.prepareTopLevel(name).hostExecutableForTesting();
        ProtosBytecodeRootNode target = assertInstanceOf(
                ProtosBytecodeRootNode.class, host.target.getRootNode());
        assertTrue(unquickened(names(target)).contains("c.EpilogForReturn"), name);
        Object result = session.processContextForTesting().callForRuntime(() -> {
            try {
                return InteropLibrary.getUncached().execute(host);
            } catch (InteropException unexpected) {
                throw new AssertionError(unexpected);
            }
        });
        assertEquals(expected, assertInstanceOf(Long.class, result,
                name + " must not carry Integer or Long").longValue(), name);
    }

    private static void assertDouble(
            ProtosStandaloneHostedSession session, String name, double expected)
            throws Exception {
        Value result = session.prepareTopLevel(name).executable().execute();
        assertTrue(result.isNumber(), name + " is a guest number: " + result);
        assertTrue(result.fitsInDouble(), name + ": " + result);
        assertEquals(expected, result.asDouble(), 0.0, name);
    }

    private static void assertLarge(
            ProtosStandaloneHostedSession session, String name, String expected)
            throws Exception {
        Value result = session.prepareTopLevel(name).executable().execute();
        assertTrue(result.isNumber(), name + " is a guest number: " + result);
        assertFalse(result.fitsInLong(), name + " promotes beyond signed-64: " + result);
        assertTrue(result.toString().contains(expected),
                name + " exact large Integer: " + result);
    }

    /* Every CallUnaryMethod send site is preceded by its own GetMethod selection. */
    private static void assertSelectionPrecedesEachCall(List<String> names) {
        int selections = 0;
        for (String name : names) {
            if (name.equals("c.GetMethod")) {
                selections++;
            } else if (name.equals("c.CallUnaryMethod") || name.equals("c.CallBinaryMethod")) {
                assertTrue(selections > 0, "a call without a preceding selection: " + names);
                selections--;
            }
        }
    }

    /*
     * The supplied positions the root binds, in order: the grouped
     * CopySuppliedArguments range (positions 0..n-1 copied into consecutive
     * parameter locals) followed by any individual LoadSuppliedArgument.
     */
    private static List<Integer> suppliedPositions(ProtosBytecodeRootNode root) {
        List<Integer> positions = new ArrayList<>();
        for (Instruction instruction : root.getBytecodeNode().getInstructionsAsList()) {
            if (!ProtosInstructionNames.of(instruction).contains("CopySuppliedArguments")) {
                continue;
            }
            for (Instruction.Argument candidate : instruction.getArguments()) {
                if (candidate.getKind() == Instruction.Argument.Kind.CONSTANT
                        && candidate.asConstant()
                                instanceof com.oracle.truffle.api.bytecode.LocalRangeAccessor range) {
                    for (int index = 0; index < range.getLength(); index++) {
                        positions.add(index);
                    }
                }
            }
        }
        positions.addAll(constantIntegers(root, "LoadSuppliedArgument", "index"));
        return positions;
    }

    /* The immediate integer constant {@code argument} of every {@code operation} instruction. */
    private static List<Integer> constantIntegers(
            ProtosBytecodeRootNode root, String operation, String argument) {
        List<Integer> values = new ArrayList<>();
        for (Instruction instruction : root.getBytecodeNode().getInstructionsAsList()) {
            if (!ProtosInstructionNames.of(instruction).contains(operation)) {
                continue;
            }
            for (Instruction.Argument candidate : instruction.getArguments()) {
                if (candidate.getName().equals(argument)) {
                    values.add(switch (candidate.getKind()) {
                        case INTEGER -> candidate.asInteger();
                        case CONSTANT -> (Integer) candidate.asConstant();
                        default -> throw new AssertionError(argument + " is not a constant");
                    });
                }
            }
        }
        return values;
    }

    /* The names of the root's native Bytecode DSL locals. */
    private static List<Object> localNames(ProtosBytecodeRootNode root) {
        return root.getBytecodeNode().getLocals().stream()
                .map(local -> local.getName())
                .toList();
    }

    private static com.oracle.truffle.api.strings.TruffleString utf16(String name) {
        return com.oracle.truffle.api.strings.TruffleString.fromJavaStringUncached(
                name, com.oracle.truffle.api.strings.TruffleString.Encoding.UTF_16);
    }

    /* The current value of module binding {@code name} of {@code anchor}'s module. */
    private static Object moduleBinding(ProtosClosureValue anchor, String name) {
        return anchor.capturedLexicalEnvironmentForRuntime()
                .readLocalSlotForRuntime(name).orElseThrow();
    }

    private static int count(List<String> names, String name) {
        return (int) names.stream().filter(name::equals).count();
    }

    private static ProtosBytecodeRootNode root(
            ProtosStandaloneHostedSession session, String name) throws Exception {
        return assertInstanceOf(
                ProtosBytecodeRootNode.class,
                session.prepareTopLevel(name).hostExecutableForTesting()
                        .target.getRootNode());
    }

    private static List<String> names(ProtosBytecodeRootNode root) {
        return root.getBytecodeNode().getInstructionsAsList().stream()
                .map(ProtosInstructionNames::of)
                .toList();
    }

    /* Instruction names without their quickening suffix. */
    private static List<String> unquickened(List<String> names) {
        return names.stream()
                .map(name -> {
                    int quickened = name.indexOf('$');
                    return quickened < 0 ? name : name.substring(0, quickened);
                })
                .toList();
    }

    private static ProtosStandaloneHostedSession open(String name, String source)
            throws Exception {
        Files.createDirectories(OUTPUT);
        Path sourceFile = OUTPUT.resolve(name + ".protos");
        Files.writeString(sourceFile, source, StandardCharsets.UTF_8);
        ProtosStandaloneHostedSession session =
                ProtosStandaloneHostedSession.open(CORE, sourceFile);
        if (session.initialOutcome().state() != ProtosExecutionOutcome.State.COMPLETED) {
            session.close();
            throw new AssertionError("Module initialization failed: " + name);
        }
        return session;
    }
}
