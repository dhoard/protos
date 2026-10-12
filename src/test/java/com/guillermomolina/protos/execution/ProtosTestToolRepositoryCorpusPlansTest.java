/*
 * THE LICENSED WORK IS PROVIDED UNDER THE TERMS OF THE ADAPTIVE PUBLIC LICENSE
 * ("LICENSE") AS FIRST COMPLETED BY: Guillermo Adrián Molina.
 * See LICENSE.TXT for the complete terms.
 */
package com.guillermomolina.protos.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import com.guillermomolina.protos.runtime.ProtosPrelude;
import com.guillermomolina.protos.runtime.ProtosStringValue;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

final class ProtosTestToolRepositoryCorpusPlansTest {
    private static final Path CORE = Path.of("protos", "lib", "core");
    private static final Path STANDARD_LIBRARY = Path.of("protos", "lib");
    private static final Path TOOL_ROOT = Path.of("protos", "tools", "test");

    @Test
    void ordinaryLibraryPlansHaveExactExplicitMembership() throws Exception {
        assertPlan("protos/corpus/library/uri", 3, "uri/parse.protos", "uri/resolve.protos");
        assertPlan("protos/corpus/library/csv", 5, "csv/parse.protos", "csv/integration-and-scale.protos");
        assertPlan("protos/corpus/library/cli", 5, "cli/specification-and-result.protos", "cli/closure.protos");
        assertPlan("protos/corpus/library/math/integer", 4, "math/integer/gcd-lcm.protos", "math/integer/integrated-closure.protos");
        assertPlan("protos/corpus/library/crypto/sha256", 2, "crypto/sha256/vectors-and-boundaries.protos", "crypto/sha256/ownership-and-domain.protos");
        assertPlan("protos/corpus/library/network/ip-addresses", 3, "network/ip-addresses/surface.protos", "network/ip-addresses/validation.protos");
        assertPlan("protos/corpus/library/network/ip-endpoints", 1, "network/ip-endpoints/parse-format-and-validation.protos", "network/ip-endpoints/parse-format-and-validation.protos");
        assertPlan("protos/corpus/library/semver", 4, "semver/parse.protos", "semver/equality.protos");
        assertPlan("protos/corpus/library/datetime", 10, "datetime/construction.protos", "datetime/rfc3339.protos");
        assertPlan("protos/corpus/library/regex", 13, "regex/compile.protos", "regex/split.protos");
        assertPlan("protos/corpus/library/logging", 7, "logging/levels.protos", "logging/sinks.protos");
    }

    @Test
    void csvAndShaCasesRemainOrderedAndAreSuiteNative() throws Exception {
        assertEquals("csv/row-parser.protos", path("protos/corpus/library/csv", 2));
        assertEquals("csv/text-adapter.protos", path("protos/corpus/library/csv", 3));
        assertEquals("csv/integration-and-scale.protos", path("protos/corpus/library/csv", 4));
        assertEquals(
                "crypto/sha256/ownership-and-domain.protos",
                path("protos/corpus/library/crypto/sha256", 1));

        assertEquals("suite-native", expectation("protos/corpus/library/csv", 2));
        assertEquals("suite-native", expectation("protos/corpus/library/csv", 3));
        assertEquals("suite-native", expectation("protos/corpus/library/csv", 4));
        assertEquals("suite-native", expectation("protos/corpus/library/crypto/sha256", 1));
    }

    @Test
    void unknownCorpusFailsClosed() throws Exception {
        ProtosExecutionOutcome outcome =
                execute(
                        "Plans: import(\"self:RepositoryCorpusPlans\")\n"
                                + "Plans.load(\"protos/corpus/library/unknown\")");
        assertEquals(ProtosExecutionOutcome.State.FAILED, outcome.state());
    }

    private static void assertPlan(
            String corpusId, int count, String firstPath, String lastPath) throws Exception {
        String prefix =
                "Manifest: import(\"self:Manifest\")\n"
                        + "Plans: import(\"self:RepositoryCorpusPlans\")\n"
                        + "cases: Manifest.planCases(Plans.load(\""
                        + corpusId
                        + "\"))\n";
        assertEquals(count, completedInteger(prefix + "cases.size()"));
        assertEquals(firstPath, completedString(prefix + "Manifest.casePath(cases[0])"));
        assertEquals(
                lastPath,
                completedString(
                        prefix + "Manifest.casePath(cases[cases.size() - 1])"));
    }

    private static String expectation(String corpusId, int index) throws Exception {
        return completedString(
                "Manifest: import(\"self:Manifest\")\n"
                        + "Plans: import(\"self:RepositoryCorpusPlans\")\n"
                        + "cases: Manifest.planCases(Plans.load(\""
                        + corpusId
                        + "\"))\n"
                        + "Manifest.caseExpectation(cases["
                        + index
                        + "])");
    }

    private static String path(String corpusId, int index) throws Exception {
        return completedString(
                "Manifest: import(\"self:Manifest\")\n"
                        + "Plans: import(\"self:RepositoryCorpusPlans\")\n"
                        + "cases: Manifest.planCases(Plans.load(\""
                        + corpusId
                        + "\"))\n"
                        + "Manifest.casePath(cases["
                        + index
                        + "])");
    }

    private static int completedInteger(String source) throws Exception {
        ProtosExecutionOutcome outcome = execute(source);
        assertEquals(ProtosExecutionOutcome.State.COMPLETED, outcome.state());
        return Integer.parseInt(com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerDecimalText(outcome.value()));
    }

    private static String completedString(String source) throws Exception {
        ProtosExecutionOutcome outcome = execute(source);
        assertEquals(ProtosExecutionOutcome.State.COMPLETED, outcome.state());
        return assertInstanceOf(ProtosStringValue.class, outcome.value()).value();
    }

    private static ProtosExecutionOutcome execute(String source) throws Exception {
        ProtosBundledToolModuleResolver resolver =
                new ProtosBundledToolModuleResolver(
                        "test",
                        TOOL_ROOT,
                        TOOL_ROOT.resolveSibling("shared"),
                        new ProtosStandardLibraryModuleResolver(STANDARD_LIBRARY));
        ProtosPrelude prelude = new ProtosCoreBootstrap().bootstrap(CORE, resolver);
        return ProtosTestExecutionSupport.execute(source, prelude.newModuleActivation());
    }
}
