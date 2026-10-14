/*
 * THE LICENSED WORK IS PROVIDED UNDER THE TERMS OF THE ADAPTIVE PUBLIC LICENSE
 * ("LICENSE") AS FIRST COMPLETED BY: Guillermo Adrián Molina. ANY USE, PUBLIC
 * DISPLAY, PUBLIC PERFORMANCE, REPRODUCTION OR DISTRIBUTION OF, OR PREPARATION OF
 * DERIVATIVE WORKS BASED ON, THE LICENSED WORK CONSTITUTES RECIPIENT'S ACCEPTANCE
 * OF THIS LICENSE AND ITS TERMS, WHETHER OR NOT SUCH RECIPIENT READS THE TERMS
 * OF THE LICENSE. "LICENSED WORK" AND "RECIPIENT" ARE DEFINED IN THE LICENSE.
 * A COPY OF THE LICENSE IS LOCATED IN THE TEXT FILE ENTITLED "LICENSE.TXT"
 * ACCOMPANYING THE CONTENTS OF THIS FILE. IF A COPY OF THE LICENSE DOES NOT
 * ACCOMPANY THE CONTENTS OF THIS FILE, A COPY OF THE LICENSE MAY ALSO BE
 * OBTAINED AT THE FOLLOWING WEB SITE:
 * https://github.com/guillermomolina/protos
 *
 * Software distributed under the License is distributed on an "AS IS" basis,
 * WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
 * for the specific language governing rights and limitations under the License.
 */
package com.guillermomolina.protos.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.oracle.truffle.api.bytecode.BytecodeTier;
import com.oracle.truffle.api.bytecode.Instruction;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.graalvm.polyglot.Value;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * I101 opt-in JVM compiler diagnostic. The ordinary test suite skips this test.
 * Run through tools/i101_local_binding_graph.py: one target and one compiler tier per JVM.
 */
final class ProtosI101CanonicalLocalGraphTest {
    private static final Path CORE = Path.of("protos", "lib", "core");
    private static final Path ARTIFACTS = Path.of("target", "truffle-compilation", "i101-local-binding");
    private static final String LITERAL = "run: () => { 1 }\n0\n";
    private static final String CREATE_ONLY = "run: () => { x: 1 }\n0\n";
    private static final String CREATE_AND_READ = "run: () => { x: 1; x }\n0\n";

    @Test
    void captureOneRealCanonicalClosureAtOneExplicitCompilerTier() throws IOException {
        Assumptions.assumeTrue(Boolean.getBoolean("protos.i101.graphCapture"),
                "Compiler graph acquisition is explicitly opt-in");
        String scenario = System.getProperty("protos.i101.graphCase", "");
        String tierText = System.getProperty("protos.i101.graphTier", "");
        assertTrue(scenario.equals("literal")
                        || scenario.equals("create_only")
                        || scenario.equals("create_and_read"),
                "graphCase must be literal, create_only or create_and_read");
        assertTrue(tierText.equals("1") || tierText.equals("2"),
                "graphTier must be 1 or 2");
        assertEquals("false", System.getProperty("polyglot.engine.BackgroundCompilation"),
                "Require synchronous Truffle compilation");
        assertEquals("true", System.getProperty("protos.diagnostic.stableRootIdentity"),
                "Require a stable identity for BGV attribution");
        int requestedTier = Integer.parseInt(tierText);

        Path sources = ARTIFACTS.resolve("sources");
        Files.createDirectories(sources);
        Path sourceFile = sources.resolve(scenario + ".protos");
        String sourceText = switch (scenario) {
            case "literal" -> LITERAL;
            case "create_only" -> CREATE_ONLY;
            case "create_and_read" -> CREATE_AND_READ;
            default -> throw new IllegalArgumentException("Unknown I101 case: " + scenario);
        };
        Files.writeString(sourceFile, sourceText, StandardCharsets.UTF_8);

        try (ProtosStandaloneHostedSession session = ProtosStandaloneHostedSession.openForTesting(
                CORE, sourceFile, ProtosExecutionMode.NORMAL)) {
            assertEquals(ProtosExecutionOutcome.State.COMPLETED, session.initialOutcome().state());
            ProtosStandaloneHostedSession.PreparedTopLevel prepared = session.prepareTopLevel("run");
            ProtosHostExecutableClosure host = prepared.hostExecutableForTesting();
            ProtosSemanticBytecodeRootNode root = assertInstanceOf(
                    ProtosSemanticBytecodeRootNode.class, host.target.getRootNode());
            // Reflective access keeps OptimizedCallTarget out of class verification: the
            // ordinary suite cannot link it, and this test is skipped there.
            Object target = host.target;
            assertTrue(isOptimizedCallTarget(target.getClass()),
                    "JVM should use the optimizing Truffle runtime, not the Native fallback");

            List<String> typedBytecode = session.processContextForTesting().callForRuntime(() -> {
                root.getRootNodes().ensureComplete();
                return completeSignature(root);
            });
            String targetName = root.toString();
            assertTrue(targetName.matches("protos-root:[0-9a-f]{16}\\[CLOSURE\\|[^\\] ]+\\]"),
                    "Expected a single stable semantic Closure root: " + targetName);
            assertEquals(target, root.getCallTarget(), "Compile the selected Closure's real target");

            // Capture input before Graal compilation, without filtering instructions or operands.
            Path signatureFile = ARTIFACTS.resolve("bytecode-" + scenario + ".txt");
            Files.write(signatureFile, typedBytecode, StandardCharsets.UTF_8);
            Value run = prepared.executable();
            // The Bytecode DSL generated RootNode refuses Graal compilation while its
            // interpreter is still UNCACHED. NORMAL enables the uncached interpreter
            // (default transition threshold 16). Exercise the real guest entry until
            // that root naturally transitions; do not change its interpreter policy.
            BytecodeTier initialBytecodeTier = root.getBytecodeNode().getTier();
            System.out.println("I101_BYTECODE_TIER_INITIAL=" + initialBytecodeTier);
            int guestCalls = 0;
            while (guestCalls < 32) {
                assertEquals(1, run.execute().asInt(),
                        "All three canonical programs must return 1");
                guestCalls++;
                if (root.getBytecodeNode().getTier() == BytecodeTier.CACHED) {
                    break;
                }
            }
            BytecodeTier readyBytecodeTier = root.getBytecodeNode().getTier();
            System.out.println("I101_BYTECODE_TIER_READY=" + readyBytecodeTier);
            System.out.println("I101_BYTECODE_TRANSITION_GUEST_CALLS=" + guestCalls);
            assertEquals(BytecodeTier.CACHED, readyBytecodeTier,
                    "Selected Closure root did not naturally leave the uncached interpreter "
                            + "after " + guestCalls + " real guest calls");

            // Unlike engine.CompileImmediately, this API can select each tier independently.
            // compile(false) is not itself evidence of a Graal exception: capture target state
            // before and after the request, including rejection and asynchronous submission.
            System.out.println("I101_COMPILER_PRE=" + compilerState(target));
            boolean compiled = session.processContextForTesting().callForRuntime(
                    () -> (Boolean) invoke(target, "compile", requestedTier == 2));
            String compilerPost = compilerState(target);
            System.out.println("I101_COMPILER_COMPILE_RETURN=" + compiled);
            System.out.println("I101_COMPILER_POST=" + compilerPost);
            assertTrue(compiled, "Explicit Truffle compilation was not completed: " + compilerPost);
            assertEquals(requestedTier, invoke(target, "highestCompiledTier"),
                    "Compilation did not reach the requested tier");
            assertTrue((Boolean) invoke(target, "isValid"), "Target is not valid after compilation");

            System.out.println("I101_LOCAL_GRAPH_CASE=" + scenario);
            System.out.println("I101_LOCAL_GRAPH_TARGET=" + targetName);
            System.out.println("I101_LOCAL_GRAPH_TIER=" + invoke(target, "highestCompiledTier"));
            System.out.println("I101_LOCAL_GRAPH_RESULT=1");
            System.out.println("I101_LOCAL_GRAPH_GROUP_ROOTS=" + root.getRootNodes().getNodes().size());
            System.out.println("I101_LOCAL_GRAPH_BYTECODE=" + signatureFile.toAbsolutePath());
            System.out.println("I101_LOCAL_GRAPH_COMPILED=YES");
        }
    }

    private static List<String> completeSignature(ProtosSemanticBytecodeRootNode root) {
        List<String> result = new ArrayList<>();
        for (Instruction instruction : root.getBytecodeNode().getInstructionsAsList()) {
            StringBuilder item = new StringBuilder(instruction.getName());
            for (Instruction.Argument argument : instruction.getArguments()) {
                item.append(' ').append(argument.getName()).append(':').append(argument.getKind())
                        .append('=').append(typedOperand(argument));
            }
            result.add(item.toString());
        }
        return result;
    }

    private static String typedOperand(Instruction.Argument argument) {
        return switch (argument.getKind()) {
            case CONSTANT -> typedConstant(argument.asConstant());
            case BYTECODE_INDEX -> Integer.toString(argument.asBytecodeIndex());
            case INTEGER -> Integer.toString(argument.asInteger());
            case LOCAL_OFFSET -> Integer.toString(argument.asLocalOffset());
            case LOCAL_INDEX -> Integer.toString(argument.asLocalIndex());
            case TAG_NODE -> argument.asTagNode() == null ? "none"
                    : argument.asTagNode().getTags().stream().map(Class::getName).sorted().toList().toString();
            case NODE_PROFILE, BRANCH_PROFILE -> "profile";
        };
    }

    private static String typedConstant(Object constant) {
        if (constant == null) {
            return "host-null";
        }
        if (constant instanceof Number || constant instanceof String || constant instanceof Boolean) {
            return constant.getClass().getName() + "(" + constant + ")";
        }
        if (constant instanceof Class<?> type) {
            return "Class(" + type.getName() + ")";
        }
        // Object addresses must not enter the canonical bytecode comparison.
        return "Metadata(" + constant.getClass().getName() + ")";
    }

    /** Observes the selected target without changing Graal's compilation policy. */
    private static String compilerState(Object target) {
        return "wasExecuted=" + invoke(target, "wasExecuted")
                + " isInitialized=" + invoke(target, "isInitialized")
                + " isTrivial=" + invoke(target, "isTrivial")
                + " shouldCompile=" + invoke(target, "shouldCompile")
                + " submitted=" + invoke(target, "isSubmittedForCompilation")
                + " valid=" + invoke(target, "isValid")
                + " tier=" + invoke(target, "highestCompiledTier")
                + " successfulCompilations=" + invoke(target, "getSuccessfulCompilationCount");
    }

    private static boolean isOptimizedCallTarget(Class<?> type) {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            if (c.getName().equals("com.oracle.truffle.runtime.OptimizedCallTarget")) {
                return true;
            }
        }
        return false;
    }

    private static Object invoke(Object target, String name, Object... args) {
        try {
            for (Class<?> c = target.getClass(); c != null; c = c.getSuperclass()) {
                for (java.lang.reflect.Method m : c.getDeclaredMethods()) {
                    if (m.getName().equals(name) && m.getParameterCount() == args.length) {
                        m.setAccessible(true);
                        return m.invoke(target, args);
                    }
                }
            }
            throw new AssertionError("Missing OptimizedCallTarget method " + name);
        } catch (IllegalAccessException e) {
            throw new AssertionError(e);
        } catch (InvocationTargetException e) {
            throw new AssertionError(e.getCause());
        }
    }
}
