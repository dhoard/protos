/* APL-1.0 licensed work; see LICENSE.TXT. */
package com.guillermomolina.protos.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.guillermomolina.protos.runtime.ProtosBooleanValue;
import com.guillermomolina.protos.runtime.ProtosClosureValue;
import com.guillermomolina.protos.runtime.ProtosObjectValue;
import com.guillermomolina.protos.runtime.ProtosPathValue;
import com.guillermomolina.protos.runtime.ProtosPrelude;
import com.guillermomolina.protos.runtime.ProtosSignalException;
import com.guillermomolina.protos.runtime.ProtosValueLookup;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ProtosStandardPathProtocolTest {
    private static ProtosPrelude core() throws Exception {
        return new ProtosCoreBootstrap().bootstrap(Path.of("protos", "lib", "core"));
    }

    private static Object evaluate(ProtosPrelude prelude, String source) {
        return ProtosTestExecutionSupport.evaluate(source, prelude.newModuleActivation());
    }

    private static boolean truth(ProtosPrelude prelude, String source) {
        Object result = evaluate(prelude, source);
        assertTrue(
                result == ProtosBooleanValue.TRUE || result == ProtosBooleanValue.FALSE,
                source);
        return result == ProtosBooleanValue.TRUE;
    }

    // Deliberately Java-side: this verifies the represented Path component model
    // (ordered normal component Strings, D169) behind source-visible construction.
    @Test
    void constructionPreservesOrderedNormalStringComponents() throws Exception {
        var prelude = core();
        var represented =
                (ProtosPathValue)
                        ProtosTestExecutionSupport.evaluate(
                                "Path.relative().child(\"a/b\").child(\"c\\\\d\").child(\"e:f\")",
                                prelude.newModuleActivation());

        assertEquals(List.of("a/b", "c\\d", "e:f"), represented.components());

        var empty =
                (ProtosPathValue)
                        ProtosTestExecutionSupport.evaluate(
                                "Path.relative()",
                                prelude.newModuleActivation());
        assertTrue(empty.components().isEmpty());
    }

    // Deliberately Java-side: lookup-home identity is an implementation/representation
    // invariant of the represented Path value.
    @Test
    void representedLookupHomeIsPathPrototype() throws Exception {
        var prelude = core();
        var value =
                (ProtosPathValue)
                        ProtosTestExecutionSupport.evaluate(
                                "Path.relative().child(\"a\")",
                                prelude.newModuleActivation());

        assertSame(
                prelude.pathPrototype(),
                ProtosValueLookup.lookup(value, "child", prelude).orElseThrow().home());
    }

    // Equality and hash are source behavior: evaluated only through Protos messages.
    @Test
    void sourceEqualityIsStructuralOverOrderedComponents() throws Exception {
        var prelude = core();

        assertTrue(truth(prelude, "Path.relative().child(\"a\").child(\"b\") == "
                + "Path.relative().child(\"a\").child(\"b\")"));
        assertTrue(truth(prelude, "Path.relative() == Path.relative()"));
        assertFalse(truth(prelude, "Path.relative().child(\"a\").child(\"b\") == "
                + "Path.relative().child(\"b\").child(\"a\")"));
        assertFalse(truth(prelude, "Path.relative().child(\"a\") == Path.relative()"));
        assertFalse(truth(prelude, "Path.relative().child(\"a/b\") == "
                + "Path.relative().child(\"a\").child(\"b\")"));
        assertFalse(truth(prelude, "Path.relative() == 1"));
        assertFalse(truth(prelude, "Path.relative().child(\"a\") == \"a\""));
    }

    @Test
    void equalPathsKeepDistinctIdentity() throws Exception {
        var prelude = core();

        assertTrue(truth(prelude, "a: Path.relative().child(\"same\")\n"
                + "b: Path.relative().child(\"same\")\n"
                + "(a == b) && (a !== b)"));
    }

    @Test
    void sourceHashMatchesThe32BitComponentSequenceHash() throws Exception {
        var prelude = core();

        assertTrue(truth(prelude, "Path.relative().hash() == 1"));
        assertTrue(truth(prelude, "Path.relative().child(\"a\").hash() == 128"));
        assertTrue(truth(prelude, "Path.relative().child(\"a\").child(\"b\").hash() == 4066"));
        assertTrue(truth(prelude, "Path.relative().child(\"zzzzzzzz\").hash() == 0 - 1910022881"));
    }

    @Test
    void emptyRelativePathIsACanonicalCarrier() throws Exception {
        var prelude = core();
        var empty = (ProtosPathValue) evaluate(prelude, "Path.relative()");

        assertTrue(empty.components().isEmpty());
        assertSame(
                prelude.pathPrototype(),
                ProtosValueLookup.lookup(empty, "child", prelude).orElseThrow().home());
    }

    @Test
    void delegatedFactoryMintsACanonicalCarrier() throws Exception {
        var prelude = core();
        var minted =
                (ProtosPathValue)
                        evaluate(prelude, "factory: Path {}\nfactory.relative().child(\"x\")");

        assertEquals(List.of("x"), minted.components());
        assertSame(
                prelude.pathPrototype(),
                ProtosValueLookup.lookup(minted, "hash", prelude).orElseThrow().home());
    }

    @Test
    void nonPathReceiversSignalError() throws Exception {
        var prelude = core();

        for (String source :
                List.of(
                        "Path.child(\"x\")",
                        "Path == Path.relative()",
                        "Path.hash()",
                        "fake: Path {}\nfake.child(\"x\")",
                        "fake: Path {}\nfake.hash()")) {
            assertThrows(ProtosSignalException.class, () -> evaluate(prelude, source), source);
        }
    }

    // Deliberately Java-side: the bootstrap surface shape is not observable through messages.
    @Test
    void pathSurfaceIsExactlyFourSourceClosuresWithPrivateKernel() throws Exception {
        var prelude = core();
        var path = prelude.pathPrototype();

        assertEquals(
                Set.of("relative", "child", "==", "hash"),
                path.localSlotsSnapshot().keySet());
        assertFalse(path.hasLocalSlot("_corePathEquals"));
        for (String selector : List.of("relative", "child", "==", "hash")) {
            var closure =
                    assertInstanceOf(
                            ProtosClosureValue.class, path.readLocalSlot(selector).orElseThrow());
            assertNotNull(closure.definition(), selector);
            assertTrue(closure.executionPlan().isPresent(), selector);
            assertTrue(closure.nativeBody().isEmpty(), selector);
        }
        assertTrue(prelude.bindings().readLocalSlot("_corePathKernel").isEmpty());
    }

    // Deliberately Java-side: this checks bootstrap object identity/frozen state,
    // not ordinary Path message behavior.
    @Test
    void pathPrototypeIsFrozenPreludeBinding() throws Exception {
        var prelude = core();

        assertTrue(prelude.bindings().isFrozen());
        assertSame(
                prelude.pathPrototype(),
                prelude.bindings().readLocalSlot("Path").orElseThrow());
        assertSame(
                ProtosObjectValue.rootObject(),
                prelude.pathPrototype().parent().orElseThrow());
    }
}
