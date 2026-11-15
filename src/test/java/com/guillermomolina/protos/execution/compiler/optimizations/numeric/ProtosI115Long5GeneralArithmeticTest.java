/* I115-LONG-5 EXPERIMENTAL: general arithmetic uses specialized operations. */
package com.guillermomolina.protos.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

final class ProtosI115Long5GeneralArithmeticTest {
    private static final Path CORE = Path.of("protos", "lib", "core");
    private static final Path OUTPUT = Path.of("target", "i115-long5-general-arithmetic");
    private static final String SOURCE = """
            add: (x, y) => { x + y }
            sub: (x, y) => { x - y }
            mul: (x, y) => { x * y }
            run: () => { add(9, 4) + sub(9, 4) + mul(9, 4) }
            runFallback: () => { add("a", "b") }
            runOverflow: () => {
                BigInteger.recognizes(add(9223372036854775807, 1)) && BigInteger.recognizes(sub(0 - 9223372036854775807, 2)) && BigInteger.recognizes(mul(9223372036854775807, 2))
            }
            """;

    @Test
    void generalTwoArgumentClosuresUseDirectArithmeticOperations() throws Exception {
        try (ProtosStandaloneHostedSession session = open()) {
            ProtosBytecodeRootNode run = (ProtosBytecodeRootNode)
                    session.prepareTopLevel("run").hostExecutableForTesting()
                            .target.getRootNode();
            List<ProtosBytecodeRootNode> roots = run.getRootNodes().getNodes().stream()
                    .map(ProtosBytecodeRootNode.class::cast).toList();
            for (String operation : List.of("NumberAdd", "NumberSubtract", "NumberMultiply")) {
                List<ProtosBytecodeRootNode> matches = roots.stream()
                        .filter(root -> names(root).stream().anyMatch(name ->
                                name.startsWith("c." + operation)))
                        .toList();
                assertFalse(matches.isEmpty(), "missing c." + operation);
                // A run/caller root may contain an unrelated materialization
                // after a closure call. Inspect the operation's own two-argument
                // closure root, not every root that happens to use this operator.
                assertTrue(matches.stream().anyMatch(root -> {
                    List<String> instructions = names(root);
                    return instructions.stream().noneMatch(name ->
                            name.contains("TryPrimitiveNumeric")
                                    || name.contains("IsPrimitiveNumericSendMiss")
                                    || name.contains("MaterializePrimitiveNumericCarrier")
                                    || name.startsWith("c.CallBinaryMethod")
                                    || name.startsWith("c.IsStandardNumericSelection")
                                    || name.startsWith("c.GetMethod")
                                    || name.startsWith("c.CallUnaryMethod")
                                    || name.contains("yield"));
                }), "no direct general-operator root for c." + operation
                        + "; matching roots: "
                        + matches.stream().map(ProtosI115Long5GeneralArithmeticTest::names)
                                .toList());
            }
            assertEquals(54L, session.prepareTopLevel("run")
                    .executable().execute().asLong());
        }
    }

    @Test
    void genericFallbackAndSigned64OverflowAreStillReachable() throws Exception {
        try (ProtosStandaloneHostedSession session = open()) {
            assertEquals("ab", session.prepareTopLevel("runFallback")
                    .executable().execute().asString());
            assertTrue(session.prepareTopLevel("runOverflow")
                    .executable().execute().asBoolean());
        }
    }

    private static List<String> names(ProtosBytecodeRootNode root) {
        return root.getBytecodeNode().getInstructionsAsList().stream()
                .map(ProtosInstructionNames::of).toList();
    }

    private static ProtosStandaloneHostedSession open() throws Exception {
        Files.createDirectories(OUTPUT);
        Path file = OUTPUT.resolve("general-arithmetic.protos");
        Files.writeString(file, SOURCE, StandardCharsets.UTF_8);
        ProtosStandaloneHostedSession session = ProtosStandaloneHostedSession.open(CORE, file);
        if (session.initialOutcome().state() != ProtosExecutionOutcome.State.COMPLETED) {
            session.close();
            throw new AssertionError("Module initialization failed");
        }
        return session;
    }
}
