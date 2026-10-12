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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.guillermomolina.protos.runtime.ProtosEnvironmentValue;
import com.guillermomolina.protos.runtime.ProtosIntegerValue;
import com.guillermomolina.protos.runtime.ProtosMapValue;
import com.guillermomolina.protos.runtime.ProtosPrelude;
import com.guillermomolina.protos.runtime.ProtosSignalException;
import com.guillermomolina.protos.runtime.ProtosStringValue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class ProtosTestToolI8D3RootActorResourceBundleTest {
    private static final Path CORE = Path.of("protos", "lib", "core");
    private static final Path MAIN = Path.of("protos", "tools", "test", "Main.protos");
    private static final Path RUNNER = Path.of("protos", "tools", "test", "Runner.protos");

    @Test
    void bundleUsesCanonicalCoreMapProtocolAndIsFrozen() throws Exception {
        ProtosPrelude prelude = new ProtosCoreBootstrap().bootstrap(CORE);
        Object gpu = new ProtosIntegerValue(41);
        Object db = new ProtosStringValue("db-capability");

        LinkedHashMap<String, Object> capabilities = new LinkedHashMap<>();
        capabilities.put("gpu", gpu);
        capabilities.put("db/integration", db);

        ProtosMapValue resources =
                ProtosTestResourceCapabilityBundle.createResourceful(prelude, capabilities);

        assertTrue(resources.isFrozen());
        assertSame(prelude.mapPrototype(), resources.parent().orElseThrow());
        assertEquals(2, resources.keyedSize());

        assertSame(gpu, lookup(prelude, resources, "gpu"));
        assertSame(db, lookup(prelude, resources, "db/integration"));

        assertThrows(
                ProtosSignalException.class,
                () ->
                        ProtosInvocation.invokeMessage(
                                resources,
                                "atPut",
                                List.of(
                                        new ProtosStringValue("other"),
                                        new ProtosIntegerValue(1L)),
                                prelude.newModuleActivation()));
    }

    @Test
    void resourcefulStandaloneRootActorReceivesExactFrozenResourcesLocal()
            throws Exception {
        ProtosPrelude prelude = new ProtosCoreBootstrap().bootstrap(CORE);
        ProtosIntegerValue gpu = new ProtosIntegerValue(42);
        ProtosMapValue resources =
                ProtosTestResourceCapabilityBundle.createResourceful(
                        prelude, Map.of("gpu", gpu));

        ProtosStandaloneProcessBootstrap.Result bootstrap =
                ProtosStandaloneProcessBootstrap.createWithRootResources(
                        prelude,
                        List.of(),
                        exactDomain(),
                        List.of(),
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        resources);

        assertSame(
                resources,
                bootstrap.activation().context().readLocalSlot("resources").orElseThrow());
        assertSame(
                resources,
                bootstrap.process().rootResourcesForRuntime().orElseThrow());

        ProtosExecutionOutcome outcome =
                com.guillermomolina.protos.execution.ProtosTestExecutionSupport.execute(
"resources[\"gpu\"]",
bootstrap.activation());

        assertEquals(ProtosExecutionOutcome.State.COMPLETED, outcome.state());
        ProtosIntegerValue observed =
                assertInstanceOf(ProtosIntegerValue.class, outcome.value());
        assertEquals(String.valueOf("42"), com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerDecimalText(observed));
    }

    @Test
    void ordinaryResourceFreeStandaloneBootstrapHasNoResourcesLocal()
            throws Exception {
        ProtosPrelude prelude = new ProtosCoreBootstrap().bootstrap(CORE);

        ProtosStandaloneProcessBootstrap.Result bootstrap =
                ProtosStandaloneProcessBootstrap.create(
                        prelude,
                        List.of(),
                        exactDomain(),
                        List.of(),
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null);

        assertTrue(bootstrap.process().rootResourcesForRuntime().isEmpty());
        assertFalse(bootstrap.activation().context().hasLocalSlot("resources"));
    }

    @Test
    void emptyResourceBundleIsRejectedRatherThanCreatingAmbientEmptyRegistry()
            throws Exception {
        ProtosPrelude prelude = new ProtosCoreBootstrap().bootstrap(CORE);
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        ProtosTestResourceCapabilityBundle.createResourceful(
                                prelude, Map.of()));
    }

    @Test
    void bootstrapRejectsUnfrozenOrForeignPreludeResourceMaps() throws Exception {
        ProtosPrelude first = new ProtosCoreBootstrap().bootstrap(CORE);
        ProtosPrelude second = new ProtosCoreBootstrap().bootstrap(CORE);

        ProtosMapValue unfrozen = first.newMap();
        assertThrows(
                IllegalStateException.class,
                () -> standaloneWithResources(first, unfrozen));

        ProtosMapValue foreign =
                ProtosTestResourceCapabilityBundle.createResourceful(
                        second,
                        Map.of(
                                "gpu",
                                new ProtosIntegerValue(1L)));
        assertThrows(
                IllegalStateException.class,
                () -> standaloneWithResources(first, foreign));
    }

    @Test
    void i8d3StillDoesNotWireRunnerOrPublicMainToProviderTransaction()
            throws Exception {
        String main = Files.readString(MAIN, StandardCharsets.UTF_8);
        String runner = Files.readString(RUNNER, StandardCharsets.UTF_8);

        assertFalse(main.contains("ProtosTestResourceProviderCoordinator"));
        assertFalse(main.contains("createWithRootResources"));
        assertFalse(runner.contains("ProtosTestResourceProviderCoordinator"));
        assertFalse(runner.contains("createWithRootResources"));
    }

    private static Object lookup(
            ProtosPrelude prelude, ProtosMapValue resources, String key) {
        return ProtosInvocation.invokeMessage(
                resources,
                "at",
                List.of(new ProtosStringValue(key)),
                prelude.newModuleActivation());
    }

    private static void standaloneWithResources(
            ProtosPrelude prelude, ProtosMapValue resources) {
        ProtosStandaloneProcessBootstrap.createWithRootResources(
                prelude,
                List.of(),
                exactDomain(),
                List.of(),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                resources);
    }

    private static ProtosEnvironmentValue.NativeNameDomain exactDomain() {
        return new ProtosEnvironmentValue.NativeNameDomain() {
            @Override
            public boolean sameCapturedName(String left, String right) {
                return left.equals(right);
            }

            @Override
            public boolean isQueryRepresentable(String name) {
                return !name.contains("=") && name.indexOf('\0') < 0;
            }

            @Override
            public boolean matchesQuery(String captured, String query) {
                return captured.equals(query);
            }
        };
    }
}
