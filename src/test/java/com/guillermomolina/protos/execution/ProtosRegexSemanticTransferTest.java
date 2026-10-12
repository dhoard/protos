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

import static org.junit.jupiter.api.Assertions.*;

import com.guillermomolina.protos.runtime.*;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * PLAT051-B: {@code std:regex/Regex} Pattern and Match are values of the single Regex semantic
 * transfer family. They cross Actor and P boundaries as inert snapshots (Pattern: source and
 * canonical flags; Match: captures, bounds, count and names) and are rebuilt by the destination's
 * own Regex module, so no compiled program, subject or source Closure crosses.
 */
final class ProtosRegexSemanticTransferTest {
    private static final Path CORE = Path.of("protos", "lib", "core");
    private static final Path STANDARD_LIBRARY = Path.of("protos", "lib");
    private static final Path MAIN = Path.of("src", "main", "java", "com", "guillermomolina", "protos");
    private static final ProtosModuleKey HOLDER_KEY = new ProtosModuleKey("plat051b-test:holder");

    private static final String HOLDER_SOURCE =
            "describePattern: (p) => {\n"
                    + "    [p.source, p.flags, p.captureCount, p.captureNames.containsKey(\"w\"),\n"
                    + "        p.search(\"--ÉÉ9--\").group(\"w\"), p.search(\"--ÉÉ9--\").start(2),\n"
                    + "        p.fullMatch(\"abc\") === null, p.replaceAll(\"é1 É\", \"<${w}>\"),\n"
                    + "        p.split(\"aé1bÉc\").size()]\n"
                    + "}\n"
                    + "describeMatch: (m) => {\n"
                    + "    [m.captureCount, m.group(0), m.group(\"w\"), m.start(\"w\"), m.end(\"w\"),\n"
                    + "        m.group(2), m.start(2), m.group(3), m.start(3), m.end(3),\n"
                    + "        m.groups().size(), m.namedGroups()[\"w\"]]\n"
                    + "}\n"
                    + "holder: (initial) => {\n"
                    + "    {\n"
                    + "        initialPattern: () => { describePattern(initial) }\n"
                    + "        pattern: (p) => { describePattern(p) }\n"
                    + "        match: (m) => { describeMatch(m) }\n"
                    + "        repeated: (r) => { [r.group(1), r.start(1), r.end(0)] }\n"
                    + "        graph: (g) => {\n"
                    + "            [g.a === g.b, g.a !== g.c, g.x === g.y, g.x !== g.z,\n"
                    + "                g.c.search(\"É\").group(0), g.z.group(\"w\")]\n"
                    + "        }\n"
                    + "        echo: (v) => { v }\n"
                    + "    }\n"
                    + "}\n";

    // Guest source: two backslashes in the guest String literal denote one regex backslash.
    private static final String SETUP =
            "Regex: import(\"std:regex/Regex\")\n"
                    + "H: import(\"plat051b-holder\")\n"
                    + "p: Regex.compileWithFlags(\"(?<w>é+)(\\\\d)?\", \"i\")\n"
                    + "p2: Regex.compileWithFlags(\"(?<w>é+)(\\\\d)?\", \"i\")\n"
                    + "m: Regex.compile(\"(?<w>\\\\p{L}+)(\\\\d)?(x*)\").search(subject)\n"
                    + "m2: Regex.compile(\"(?<w>\\\\p{L}+)(\\\\d)?(x*)\").search(subject)\n"
                    + "r: Regex.compile(\"(a|b)+\").fullMatch(\"abab\")\n"
                    + "null";

    /** Two non-BMP scalars, so scalar offsets differ from UTF-16 offsets, then "Été-". */
    private static final String SUBJECT = "😀😀Été-";

    private static final List<Object> PATTERN_FACTS =
            Arrays.asList("(?<w>é+)(\\d)?", "i", 2L, true, "ÉÉ", 4L, true,
                    "<é> <É>", 3L);
    private static final List<Object> MATCH_FACTS =
            Arrays.asList(3L, "Été", "Été", 2L, 5L, null, null, "", 5L, 5L, 4L,
                    "Été");

    private static final List<String> PATTERN_SURFACE =
            List.of("source", "flags", "captureCount", "captureNames", "fullMatch", "search",
                    "searchFrom", "eachMatch", "findAll", "replaceFirst", "replaceAll", "split");
    private static final List<String> MATCH_SURFACE =
            List.of("captureCount", "group", "start", "end", "groups", "namedGroups");

    @Test
    void regexMintsFrozenTrustedFamilyValuesWithUnchangedPrivateSurface(@TempDir Path dir)
            throws Exception {
        try (ProtosHostedExecutionTestFixture hosted = open(dir)) {
            ProtosActivation root = hosted.activation();
            ProtosPrelude prelude = root.prelude().orElseThrow();
            ProtosSemanticTransferValue pattern = slot(root, "p");
            ProtosSemanticTransferValue match = slot(root, "m");

            ProtosSemanticTransferFamily family = pattern.family();
            assertInstanceOf(ProtosRegexSemanticTransferFamily.class, family);
            assertSame(family, match.family(), "one family serves Pattern and Match");
            assertEquals(ProtosRegexUnicodeFacility.MODULE_KEY, family.ownerModule());
            assertTrue(prelude.authorizesSemanticTransferFamilyForRuntime(family));
            assertTrue(pattern.isFrozen());
            assertTrue(match.isFrozen());
            assertEquals(Set.copyOf(PATTERN_SURFACE), pattern.localSlotsSnapshot().keySet());
            assertEquals(Set.copyOf(MATCH_SURFACE), match.localSlotsSnapshot().keySet());
            assertEquals(PATTERN_FACTS, java(hosted.evaluatePersistent("<b>", "H.describePattern(p)")));
            assertEquals(MATCH_FACTS, java(hosted.evaluatePersistent("<b>", "H.describeMatch(m)")));

            ProtosObjectValue module =
                    assertInstanceOf(ProtosObjectValue.class, root.context().readLocalSlot("Regex").orElseThrow());
            for (String name : module.localSlotsSnapshot().keySet()) {
                assertFalse(name.startsWith("_regex"), "bootstrap facility leaked: " + name);
            }
            for (String name : List.of("compileWithFlags", "compile", "escape", "escapeReplacement")) {
                assertTrue(module.hasLocalSlot(name), name);
            }
            assertTrue(
                    root.actorModuleState()
                            .lookup(ProtosRegexUnicodeFacility.MODULE_KEY)
                            .flatMap(ProtosActorModuleState.ModuleRecord::semanticTransferFactory)
                            .isPresent(),
                    "the module installed its Actor-local factory");
        }
    }

    @Test
    void actorSpawnRequestAndReplyRebuildPatternAndMatchInTheirDestination(@TempDir Path dir)
            throws Exception {
        try (ProtosHostedExecutionTestFixture hosted = open(dir)) {
            ProtosActivation root = hosted.activation();
            assertEquals(
                    PATTERN_FACTS,
                    java(await(hosted,
                            "worker: Actor.spawn(\"plat051b-holder\", \"holder\", p)\n"
                                    + "worker.request(\"initialPattern\")")));
            assertEquals(PATTERN_FACTS, java(await(hosted, "worker.request(\"pattern\", p)")));
            assertEquals(MATCH_FACTS, java(await(hosted, "worker.request(\"match\", m)")));
            assertEquals(Arrays.asList("b", 3L, 4L), java(await(hosted, "worker.request(\"repeated\", r)")));

            for (String name : List.of("p", "m")) {
                ProtosSemanticTransferValue source = slot(root, name);
                ProtosSemanticTransferValue reply =
                        assertInstanceOf(
                                ProtosSemanticTransferValue.class,
                                await(hosted, "worker.request(\"echo\", " + name + ")"));
                assertRebuilt(source, reply);
            }
            assertEquals(PATTERN_FACTS,
                    java(await(hosted, "worker.request(\"echo\", p).then((copy) => { H.describePattern(copy) })")));
            assertEquals(MATCH_FACTS,
                    java(await(hosted, "worker.request(\"echo\", m).then((copy) => { H.describeMatch(copy) })")));
        }
    }

    @Test
    void parallelInputsAndResultsRebuildPatternAndMatch(@TempDir Path dir) throws Exception {
        try (ProtosHostedExecutionTestFixture hosted = open(dir)) {
            ProtosActivation root = hosted.activation();
            // An isolated P computation sees no Core bindings such as Array, so each probe
            // answers one scalar instead of an Array literal.
            for (Map.Entry<String, Object> probe :
                    List.<Map.Entry<String, Object>>of(
                            Map.entry("x.flags", "i"),
                            Map.entry("x.captureCount", 2L),
                            Map.entry("x.search(\"--\u00C9\u00C99--\").group(\"w\")", "\u00C9\u00C9"),
                            Map.entry("x.search(\"--\u00C9\u00C99--\").start(2)", 4L),
                            Map.entry("x.replaceAll(\"\u00E91 \u00C9\", \"<${w}>\")", "<\u00E9> <\u00C9>"))) {
                assertEquals(probe.getValue(),
                        java(await(hosted, "((x) => { " + probe.getKey() + " }).parallel(p)")), probe.getKey());
            }
            for (Map.Entry<String, Object> probe :
                    List.<Map.Entry<String, Object>>of(
                            Map.entry("x.group(\"w\")", "\u00C9t\u00E9"),
                            Map.entry("x.start(1)", 2L),
                            Map.entry("x.group(2) === null", true),
                            Map.entry("x.start(2) === null", true),
                            Map.entry("x.group(3)", ""),
                            Map.entry("x.end(3)", 5L),
                            Map.entry("x.groups().size()", 4L),
                            Map.entry("x.namedGroups()[\"w\"]", "\u00C9t\u00E9"),
                            Map.entry("x.captureCount", 3L))) {
                assertEquals(probe.getValue(),
                        java(await(hosted, "((x) => { " + probe.getKey() + " }).parallel(m)")), probe.getKey());
            }
            assertEquals("b", java(await(hosted, "((x) => { x.group(1) }).parallel(r)")));
            assertEquals(3L, java(await(hosted, "((x) => { x.start(1) }).parallel(r)")));
            for (String name : List.of("p", "m")) {
                ProtosSemanticTransferValue result =
                        assertInstanceOf(
                                ProtosSemanticTransferValue.class,
                                await(hosted, "((x) => { x }).parallel(" + name + ")"));
                assertRebuilt(slot(root, name), result);
            }
            assertEquals(MATCH_FACTS,
                    java(await(hosted, "((x) => { x }).parallel(m).then((copy) => { H.describeMatch(copy) })")));
        }
    }

    @Test
    void recordsMaterializeIntoAFreshModuleStateLikeAPWorker(@TempDir Path dir) throws Exception {
        try (ProtosHostedExecutionTestFixture hosted = open(dir)) {
            ProtosActivation root = hosted.activation();
            ProtosPrelude prelude = root.prelude().orElseThrow();
            for (String name : List.of("m", "p")) {
                ProtosSemanticTransferValue source = slot(root, name);
                ProtosSemanticTransferRecord record =
                        ProtosSemanticTransferRecord.prepareForRuntime(source, prelude);
                assertNotNull(record, name);
                ProtosActorModuleState fresh = new ProtosActorModuleState();
                ProtosSemanticTransferValue rebuilt =
                        hosted.callEntered(
                                () ->
                                        record.materializeForRuntime(
                                                prelude.newModuleActivation(
                                                        fresh,
                                                        null,
                                                        prelude.newExecutionContext(),
                                                        root.executionDomain())));
                assertRebuilt(source, rebuilt);
                assertTrue(fresh.lookup(ProtosRegexUnicodeFacility.MODULE_KEY).isPresent());
            }
        }
    }

    @Test
    void aliasesStayAliasesAndEqualContentStaysDistinct(@TempDir Path dir) throws Exception {
        try (ProtosHostedExecutionTestFixture hosted = open(dir)) {
            hosted.evaluatePersistent(
                    "<b>",
                    "worker: Actor.spawn(\"plat051b-holder\", \"holder\", 0)\n"
                            + "g: { a: p; b: p; c: p2; x: m; y: m; z: m2 }\n"
                            + "null");
            assertEquals(
                    Arrays.asList(true, true, true, true, "É", "Été"),
                    java(await(hosted, "worker.request(\"graph\", g)")));
            assertEquals(
                    true,
                    java(await(hosted,
                            "((x) => { (x.a === x.b) && (x.a !== x.c) && (x.x === x.y) && (x.x !== x.z) })"
                                    + ".parallel(g)")));
        }
    }

    @Test
    void payloadsAreInertSnapshotsWithoutSubjectProgramOrClosures(@TempDir Path dir) throws Exception {
        try (ProtosHostedExecutionTestFixture hosted = open(dir)) {
            ProtosActivation root = hosted.activation();
            ProtosSemanticTransferValue pattern = slot(root, "p");
            ProtosSemanticTransferValue match = slot(root, "m");
            ProtosRegexSemanticTransferFamily family =
                    (ProtosRegexSemanticTransferFamily) pattern.family();

            ProtosSemanticTransferPayload patternPayload = family.extract(pattern);
            assertEquals(3, patternPayload.size());
            assertEquals(List.of("Pattern", "(?<w>é+)(\\d)?", "i"),
                    List.of(patternPayload.get(0), patternPayload.get(1), patternPayload.get(2)));
            assertTrue(family.acceptsPayload(patternPayload));

            ProtosSemanticTransferPayload matchPayload = family.extract(match);
            assertEquals("Match", matchPayload.get(0));
            assertEquals(3L, matchPayload.get(1));
            ProtosSemanticTransferPayload groups = (ProtosSemanticTransferPayload) matchPayload.get(2);
            assertEquals(4, groups.size());
            assertEquals(List.of(true, "Été", 2L, 5L), leaves(groups.get(0)));
            assertEquals(List.of(true, "Été", 2L, 5L), leaves(groups.get(1)));
            assertEquals(List.of(false), leaves(groups.get(2)), "nonparticipating");
            assertEquals(List.of(true, "", 5L, 5L), leaves(groups.get(3)),
                    "participating empty");
            ProtosSemanticTransferPayload names = (ProtosSemanticTransferPayload) matchPayload.get(3);
            assertEquals(1, names.size());
            assertEquals(List.of("w", 1L), leaves(names.get(0)));
            assertTrue(family.acceptsPayload(matchPayload));
            assertFalse(allLeaves(matchPayload).contains(SUBJECT), "the subject never crosses");
            for (Object leaf : allLeaves(matchPayload)) {
                assertFalse(leaf instanceof String text && text.contains("\uD83D"), "no subject text outside captures");
            }

            assertFalse(family.acceptsPayload(ProtosSemanticTransferPayload.of("Pattern", "a", "si")));
            assertFalse(family.acceptsPayload(ProtosSemanticTransferPayload.of("Pattern", "a", "ii")));
            assertFalse(family.acceptsPayload(ProtosSemanticTransferPayload.of("Match", BigInteger.ZERO,
                    ProtosSemanticTransferPayload.of(ProtosSemanticTransferPayload.of(false)),
                    ProtosSemanticTransferPayload.of())), "group 0 always participates");
            assertFalse(family.acceptsPayload(ProtosSemanticTransferPayload.of("Match", BigInteger.ZERO,
                    ProtosSemanticTransferPayload.of(ProtosSemanticTransferPayload.of(
                            true, "ab", BigInteger.ZERO, BigInteger.ONE)),
                    ProtosSemanticTransferPayload.of())), "bounds must cover the captured scalars");
            assertFalse(family.acceptsPayload(ProtosSemanticTransferPayload.of("Other", "a", "")));

            // Bounds beyond signed-64 stay exact guest Integers; only their difference is checked.
            BigInteger beyond = BigInteger.ONE.shiftLeft(70);
            assertTrue(family.acceptsPayload(match(group0("ab", beyond, beyond.add(BigInteger.TWO)))));
            assertFalse(family.acceptsPayload(match(group0("ab", beyond, beyond.add(BigInteger.ONE)))));
            assertFalse(family.acceptsPayload(match(group0("", BigInteger.ONE.negate(),
                    BigInteger.ONE.negate()))), "negative start");
            // A non-BMP scalar is one position, not two UTF-16 units.
            assertTrue(family.acceptsPayload(match(group0("😀é", BigInteger.TEN, BigInteger.valueOf(12)))));
            assertFalse(family.acceptsPayload(match(group0("😀é", BigInteger.TEN, BigInteger.valueOf(13)))));
            assertFalse(family.acceptsPayload(ProtosSemanticTransferPayload.of("Match", BigInteger.ZERO,
                    ProtosSemanticTransferPayload.of(ProtosSemanticTransferPayload.of(
                            true, "a", 0.0d, BigInteger.ONE)),
                    ProtosSemanticTransferPayload.of())), "a Float bound is not an Integer leaf");

            ProtosSemanticTransferPayload twoGroups = ProtosSemanticTransferPayload.of(
                    ProtosSemanticTransferPayload.of(true, "a", BigInteger.ZERO, BigInteger.ONE),
                    ProtosSemanticTransferPayload.of(false));
            assertTrue(family.acceptsPayload(ProtosSemanticTransferPayload.of("Match", BigInteger.ONE,
                    twoGroups, ProtosSemanticTransferPayload.of(
                            ProtosSemanticTransferPayload.of("w", BigInteger.ONE)))));
            for (BigInteger badGroup : new BigInteger[] {
                    BigInteger.ZERO, BigInteger.TWO, BigInteger.ONE.shiftLeft(32).add(BigInteger.ONE),
                    BigInteger.ONE.shiftLeft(64).add(BigInteger.ONE), BigInteger.ONE.negate()}) {
                assertFalse(family.acceptsPayload(ProtosSemanticTransferPayload.of("Match",
                        BigInteger.ONE, twoGroups, ProtosSemanticTransferPayload.of(
                                ProtosSemanticTransferPayload.of("w", badGroup)))), badGroup.toString());
            }
            assertFalse(family.acceptsPayload(ProtosSemanticTransferPayload.of("Match", BigInteger.ONE,
                    twoGroups, ProtosSemanticTransferPayload.of(
                            ProtosSemanticTransferPayload.of("w", BigInteger.ONE),
                            ProtosSemanticTransferPayload.of("v", BigInteger.ONE)))), "one name per group");
            for (BigInteger badCount : new BigInteger[] {
                    BigInteger.TWO, BigInteger.ONE.shiftLeft(30), BigInteger.ONE.shiftLeft(64).add(BigInteger.ONE),
                    BigInteger.ONE.negate()}) {
                assertFalse(family.acceptsPayload(ProtosSemanticTransferPayload.of("Match", badCount,
                        twoGroups, ProtosSemanticTransferPayload.of())), badCount.toString());
            }
        }
    }

    private static ProtosSemanticTransferPayload group0(String text, BigInteger start, BigInteger end) {
        return ProtosSemanticTransferPayload.of(true, text, start, end);
    }

    private static ProtosSemanticTransferPayload match(ProtosSemanticTransferPayload group0) {
        return ProtosSemanticTransferPayload.of("Match", BigInteger.ZERO,
                ProtosSemanticTransferPayload.of(group0), ProtosSemanticTransferPayload.of());
    }

    @Test
    void ordinaryLookalikesAndImpostorDescriptorsGainNoRegexAuthority(@TempDir Path dir) throws Exception {
        try (ProtosHostedExecutionTestFixture hosted = open(dir)) {
            ProtosActivation root = hosted.activation();
            ProtosPrelude prelude = root.prelude().orElseThrow();
            Object copy =
                    await(hosted,
                            "worker: Actor.spawn(\"plat051b-holder\", \"holder\", 0)\n"
                                    + "fake: { source: \"a\"; flags: \"\"; captureCount: 0; group: \"a\"; start: 0; end: 1 }\n"
                                    + "worker.request(\"echo\", fake)");
            assertEquals(ProtosObjectValue.class, copy.getClass(), "a lookalike stays an ordinary object");

            ProtosRegexSemanticTransferFamily impostor = new ProtosRegexSemanticTransferFamily();
            assertEquals(ProtosRegexUnicodeFacility.MODULE_KEY, impostor.ownerModule());
            assertFalse(prelude.authorizesSemanticTransferFamilyForRuntime(impostor));
            ProtosObjectValue template = new ProtosObjectValue(ProtosObjectValue.rootObject());
            template.createLocalSlot("source", new ProtosStringValue("a"));
            template.createLocalSlot("flags", new ProtosStringValue(""));
            ProtosSemanticTransferValue forged =
                    assertInstanceOf(
                            ProtosSemanticTransferValue.class,
                            ProtosInvocation.invokeMessage(
                                    impostor.createFacility(), "mintPattern", List.of(template), root));
            assertNull(ProtosSemanticTransferRecord.prepareForRuntime(forged, prelude));
        }
    }

    @Test
    void regexFamilyIsRegisteredOnceAndOrdinaryTransferNeverLoadsRegex(@TempDir Path dir) throws Exception {
        assertEquals(1, occurrencesUnder(MAIN, "new ProtosRegexSemanticTransferFamily("));
        Path holder = Files.writeString(dir.resolve("holder.protos"), HOLDER_SOURCE);
        try (ProtosHostedExecutionTestFixture hosted = ProtosHostedExecutionTestFixture.open(prelude(holder))) {
            ProtosActivation root = hosted.activation();
            assertEquals(String.valueOf("7"), com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerDecimalText(await(hosted, "((v) => { v }).parallel(7)")));
            assertEquals(String.valueOf("42"), com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerDecimalText(await(hosted,
                                    "worker: Actor.spawn(\"plat051b-holder\", \"holder\", 0)\n"
                                            + "worker.request(\"echo\", 42)")));
            assertTrue(root.actorModuleState().lookup(ProtosRegexUnicodeFacility.MODULE_KEY).isEmpty());
        }
    }

    private static void assertRebuilt(ProtosSemanticTransferValue source, ProtosSemanticTransferValue rebuilt) {
        assertNotSame(source, rebuilt);
        assertSame(source.family(), rebuilt.family());
        assertTrue(rebuilt.isFrozen());
        assertEquals(source.localSlotsSnapshot().keySet(), rebuilt.localSlotsSnapshot().keySet());
        for (Map.Entry<String, Object> entry : source.localSlotsSnapshot().entrySet()) {
            Object copied = rebuilt.readLocalSlot(entry.getKey()).orElseThrow();
            if (entry.getValue() instanceof ProtosClosureValue) {
                assertNotSame(entry.getValue(), copied, "source Closure crossed: " + entry.getKey());
            }
        }
    }

    private static ProtosHostedExecutionTestFixture open(Path dir) throws Exception {
        Path holder = Files.writeString(dir.resolve("holder.protos"), HOLDER_SOURCE);
        ProtosHostedExecutionTestFixture hosted = ProtosHostedExecutionTestFixture.open(prelude(holder));
        hosted.activation().context().createLocalSlot("subject", new ProtosStringValue(SUBJECT));
        hosted.evaluatePersistent("<plat051-b>", SETUP);
        return hosted;
    }

    private static ProtosPrelude prelude(Path holder) throws Exception {
        ProtosModuleResolver resolver =
                new ProtosExactModuleOverlayResolver(
                        Map.of("plat051b-holder", new ProtosExactModuleOverlayResolver.ExactModule(HOLDER_KEY, holder)),
                        new ProtosStandardLibraryModuleResolver(STANDARD_LIBRARY));
        return new ProtosCoreBootstrap().bootstrap(CORE, resolver);
    }

    private static ProtosSemanticTransferValue slot(ProtosActivation root, String name) {
        return assertInstanceOf(
                ProtosSemanticTransferValue.class, root.context().readLocalSlot(name).orElseThrow());
    }

    /** Diagnostic only: Core bindings of the current fixture, to name Error prototypes. */
    private static Map<String, Object> PRELUDE_BINDINGS = Map.of();

    private static Object await(ProtosHostedExecutionTestFixture hosted, String source) {
        PRELUDE_BINDINGS =
                hosted.activation().prelude().orElseThrow().bindings().localSlotsSnapshot();
        ProtosFutureValue future =
                assertInstanceOf(ProtosFutureValue.class, hosted.evaluatePersistent("<plat051-b>", source));
        ProtosActorExecutionDomain domain = hosted.activation().executionDomain();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (future.isPending()) {
            hosted.callEntered(
                    () -> {
                        domain.dispatchUntilIdle();
                        return null;
                    });
            if (System.nanoTime() > deadline) {
                fail("PLAT051-B hosted Future did not settle: " + source);
            }
            Thread.onSpinWait();
        }
        assertEquals(
                ProtosFutureValue.State.RESOLVED,
                future.state(),
                () -> source + " failed with " + future.failedError().map(ProtosRegexSemanticTransferTest::describeError).orElse("?"));
        return future.resolvedValue().orElseThrow();
    }

    private static String describeError(Object error) {
        StringBuilder text = new StringBuilder();
        Object current = error;
        while (current instanceof ProtosObjectValue object && !object.isRootObject()) {
            text.append(object.localSlotsSnapshot().keySet());
            for (Map.Entry<String, Object> binding : PRELUDE_BINDINGS.entrySet()) {
                if (binding.getValue() == object) {
                    text.append('=').append(binding.getKey());
                }
            }
            text.append(" <- ");
            current = object.parent().orElse(null);
        }
        return text.toString();
    }

    /** Projects guest Arrays, Strings, Integers, Booleans and null onto host values. */
    private static Object java(Object value) {
        if (value instanceof ProtosArrayValue array) {
            List<Object> items = new ArrayList<>();
            for (Object item : array.indexedSnapshot()) {
                items.add(java(item));
            }
            return items;
        }
        if (value instanceof ProtosStringValue string) {
            return string.value();
        }
        if (value instanceof ProtosIntegerValue integer) {
            return Long.parseLong(com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerDecimalText(integer));
        }
        if (value == ProtosBooleanValue.TRUE || value == ProtosBooleanValue.FALSE) {
            return value == ProtosBooleanValue.TRUE;
        }
        if (value == ProtosNullValue.INSTANCE) {
            return null;
        }
        throw new AssertionError("unexpected guest value " + value);
    }

    private static List<Object> leaves(Object node) {
        ProtosSemanticTransferPayload payload = (ProtosSemanticTransferPayload) node;
        List<Object> leaves = new ArrayList<>();
        for (int index = 0; index < payload.size(); index++) {
            leaves.add(payload.get(index));
        }
        return leaves;
    }

    private static List<Object> allLeaves(Object node) {
        List<Object> leaves = new ArrayList<>();
        if (node instanceof ProtosSemanticTransferPayload payload) {
            for (int index = 0; index < payload.size(); index++) {
                leaves.addAll(allLeaves(payload.get(index)));
            }
        } else {
            leaves.add(node);
        }
        return leaves;
    }

    private static long occurrencesUnder(Path root, String needle) throws IOException {
        long count = 0;
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                String source = Files.readString(file);
                for (int at = source.indexOf(needle); at >= 0; at = source.indexOf(needle, at + 1)) {
                    count++;
                }
            }
        }
        return count;
    }
}
