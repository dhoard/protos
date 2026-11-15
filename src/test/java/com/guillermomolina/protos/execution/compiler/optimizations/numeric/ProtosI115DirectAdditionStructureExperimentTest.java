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

/*
 * I115 EXPERIMENTAL: '+' is lowered as a direct NumberAdd operation that
 * verifies the selected behavior itself. Terminal sites keep the direct
 * NumberAdd-to-return shape; non-terminal sites compose a possibly
 * suspended selected invocation with Yield, but never reintroduce the
 * separate selection and dispatch tree.
 */
package com.guillermomolina.protos.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

final class ProtosI115DirectAdditionStructureExperimentTest {
    private static final Path CORE = Path.of("protos", "lib", "core");
    private static final Path OUTPUT = Path.of("target", "i115-direct-addition-structure");
    private static final List<String> FORBIDDEN_PREFIXES = List.of(
            "c.IsStandardNumericSelection",
            "c.GetMethod",
            "c.CallUnaryMethod",
            "c.CallBinaryMethod");

    @Test
    void twoLocalsAddDirectly() throws Exception {
        try (ProtosStandaloneHostedSession session = open("two-locals", """
                run: () => {
                    left: 1
                    right: 2
                    left + right
                }
                """)) {
            List<String> instructions = runInstructions(session);
            assertDirect(instructions, 1);
            assertTerminal(instructions);
            assertEquals(3L, session.prepareTopLevel("run")
                    .executable().execute().asLong());
        }
    }

    @Test
    void nestedAdditionAddsDirectly() throws Exception {
        try (ProtosStandaloneHostedSession session = open("nested", """
                run: () => {
                    a: 1
                    b: 2
                    c: 3
                    (a + b) + c
                }
                """)) {
            List<String> instructions = runInstructions(session);
            // The inner sum is not terminal: it may compose a suspension.
            assertDirect(instructions, 2);
            assertEquals(6L, session.prepareTopLevel("run")
                    .executable().execute().asLong());
        }
    }

    @Test
    void argumentAdditionReturnsDirectly() throws Exception {
        try (ProtosStandaloneHostedSession session = open("arguments", """
                run: (a, b) => {
                    a + b
                }
                """)) {
            List<String> instructions = runInstructions(session);
            assertDirect(instructions, 1);
            assertTerminal(instructions);
        }
    }

    @Test
    void nonTerminalAdditionComposesWithoutDispatchTree() throws Exception {
        try (ProtosStandaloneHostedSession session = open("non-terminal", """
                run: () => {
                    a: 1
                    b: 2
                    result: a + b
                    result * 2
                }
                """)) {
            List<String> instructions = runInstructions(session);
            assertDirect(instructions, 1);
            assertTrue(instructions.stream().anyMatch(name -> name.startsWith("c.NumberMultiply")),
                    "NumberMultiply must remain a proxied operation: " + instructions);
            assertEquals(6L, session.prepareTopLevel("run")
                    .executable().execute().asLong());
        }
    }

    /*
     * The numeric operations stay proxied operations, and no separate
     * selection, method lookup or dispatched call precedes them.
     */
    private static void assertDirect(List<String> instructions, int minimumAdds) {
        long adds = instructions.stream()
                .filter(name -> name.startsWith("c.NumberAdd")).count();
        assertTrue(adds >= minimumAdds,
                "expected at least " + minimumAdds + " c.NumberAdd: " + instructions);
        for (String name : instructions) {
            assertTrue(FORBIDDEN_PREFIXES.stream().noneMatch(name::startsWith),
                    "separate selection or dispatch in run root: " + instructions);
        }
    }

    /* A terminal sum flows directly to return and needs no Yield. */
    private static void assertTerminal(List<String> instructions) {
        assertTrue(instructions.stream().noneMatch(name -> name.contains("yield")),
                "terminal sum must use the terminal transport: " + instructions);
        int add = lastIndexOfPrefix(instructions, "c.NumberAdd");
        int ret = lastIndexOfPrefix(instructions, "return");
        assertTrue(add >= 0 && ret > add,
                "NumberAdd must precede return: " + instructions);
        for (int i = add + 1; i < ret; i++) {
            String name = instructions.get(i);
            assertTrue(!name.startsWith("store.local") && !name.startsWith("load.local"),
                    "NumberAdd result must flow directly to return: " + instructions);
        }
    }

    private static int lastIndexOfPrefix(List<String> instructions, String prefix) {
        for (int i = instructions.size() - 1; i >= 0; i--) {
            if (instructions.get(i).startsWith(prefix)) {
                return i;
            }
        }
        return -1;
    }

    private static List<String> runInstructions(ProtosStandaloneHostedSession session)
            throws Exception {
        ProtosBytecodeRootNode run = (ProtosBytecodeRootNode)
                session.prepareTopLevel("run").hostExecutableForTesting()
                        .target.getRootNode();
        return run.getBytecodeNode().getInstructionsAsList().stream()
                .map(ProtosInstructionNames::of).toList();
    }

    private static ProtosStandaloneHostedSession open(String name, String source)
            throws Exception {
        Files.createDirectories(OUTPUT);
        Path file = OUTPUT.resolve(name + ".protos");
        Files.writeString(file, source, StandardCharsets.UTF_8);
        ProtosStandaloneHostedSession session = ProtosStandaloneHostedSession.open(CORE, file);
        if (session.initialOutcome().state() != ProtosExecutionOutcome.State.COMPLETED) {
            session.close();
            throw new AssertionError("Module initialization failed");
        }
        return session;
    }
}
