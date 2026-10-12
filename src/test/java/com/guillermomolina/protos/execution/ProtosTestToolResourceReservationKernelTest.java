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
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.guillermomolina.protos.runtime.ProtosArrayValue;
import com.guillermomolina.protos.runtime.ProtosBooleanValue;
import com.guillermomolina.protos.runtime.ProtosIntegerValue;
import com.guillermomolina.protos.runtime.ProtosNullValue;
import com.guillermomolina.protos.runtime.ProtosPrelude;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

final class ProtosTestToolResourceReservationKernelTest {
    private static final Path CORE = Path.of("protos", "lib", "core");
    private static final Path STANDARD_LIBRARY = Path.of("protos", "lib");
    private static final Path TOOL_ROOT = Path.of("protos", "tools", "test");
    private static final Path SHARED_ROOT = Path.of("protos", "tools", "shared");

    @Test
    void resourceFreeReservationIsFrozenAndHasNoLiveRegistryCost() throws Exception {
        ProtosArrayValue observed =
                completedArray(
                        commonImports()
                                + "catalog: Catalog.empty()\n"
                                + "state: Reservation.newState(catalog)\n"
                                + "bindings: Binding.bindRequirements(Array(), catalog)\n"
                                + "held: Reservation.tryReserve(bindings, state)\n"
                                + "released: Reservation.release(held, state)\n"
                                + "Array("
                                + "held, "
                                + "Reservation.activeReservationCount(state), "
                                + "released)\n");

        ProtosArrayValue held = arrayAt(observed, 0);
        assertEquals(0, held.indexedSize());
        assertTrue(held.isFrozen());
        assertEquals(String.valueOf("0"), com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerDecimalText(integerAt(observed, 1)));
        assertSame(ProtosBooleanValue.TRUE, observed.indexedAt(2));
    }

    @Test
    void sharedReservationsConsumeCapacityAndReleaseRestoresIt() throws Exception {
        String source =
                commonImports()
                        + catalog("gpu", 4, "run")
                        + "state: Reservation.newState(catalog)\n"
                        + "two: Binding.bindRequirements("
                        + "Array(Manifest.requirement(\"gpu\", \"shared\", 2)), catalog)\n"
                        + "one: Binding.bindRequirements("
                        + "Array(Manifest.requirement(\"gpu\", \"shared\", 1)), catalog)\n"
                        + "first: Reservation.tryReserve(two, state)\n"
                        + "second: Reservation.tryReserve(two, state)\n"
                        + "blocked: Reservation.tryReserve(one, state)\n"
                        + "usedAtCapacity: Reservation.sharedUsed(state, \"gpu\")\n"
                        + "activeAtCapacity: Reservation.activeReservationCount(state)\n"
                        + "Reservation.release(first, state)\n"
                        + "third: Reservation.tryReserve(one, state)\n"
                        + "Array("
                        + "first, second, blocked, usedAtCapacity, activeAtCapacity, "
                        + "third, Reservation.sharedUsed(state, \"gpu\"), "
                        + "Reservation.activeReservationCount(state))\n";

        ProtosArrayValue observed = completedArray(source);
        assertInstanceOf(ProtosArrayValue.class, observed.indexedAt(0));
        assertInstanceOf(ProtosArrayValue.class, observed.indexedAt(1));
        assertSame(ProtosNullValue.INSTANCE, observed.indexedAt(2));
        assertEquals(String.valueOf("4"), com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerDecimalText(integerAt(observed, 3)));
        assertEquals(String.valueOf("2"), com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerDecimalText(integerAt(observed, 4)));
        assertInstanceOf(
                ProtosArrayValue.class, observed.indexedAt(5));
        assertEquals(String.valueOf("3"), com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerDecimalText(integerAt(observed, 6)));
        assertEquals(String.valueOf("2"), com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerDecimalText(integerAt(observed, 7)));
    }

    @Test
    void exclusiveReservationRequiresSoleOccupancyAndBlocksShared() throws Exception {
        String source =
                commonImports()
                        + catalog("db/integration", 3, "run")
                        + "state: Reservation.newState(catalog)\n"
                        + "shared: Binding.bindRequirements("
                        + "Array(Manifest.requirement("
                        + "\"db/integration\", \"shared\", 1)), catalog)\n"
                        + "exclusive: Binding.bindRequirements("
                        + "Array(Manifest.requirement("
                        + "\"db/integration\", \"exclusive\", null)), catalog)\n"
                        + "sharedHeld: Reservation.tryReserve(shared, state)\n"
                        + "exclusiveBlocked: Reservation.tryReserve(exclusive, state)\n"
                        + "Reservation.release(sharedHeld, state)\n"
                        + "exclusiveHeld: Reservation.tryReserve(exclusive, state)\n"
                        + "sharedBlocked: Reservation.tryReserve(shared, state)\n"
                        + "exclusiveFlag: Reservation.exclusiveHeld(state, \"db/integration\")\n"
                        + "Reservation.release(exclusiveHeld, state)\n"
                        + "sharedAfterRelease: Reservation.tryReserve(shared, state)\n"
                        + "Array("
                        + "exclusiveBlocked, sharedBlocked, exclusiveFlag, "
                        + "sharedAfterRelease, "
                        + "Reservation.sharedUsed(state, \"db/integration\"))\n";

        ProtosArrayValue observed = completedArray(source);
        assertSame(ProtosNullValue.INSTANCE, observed.indexedAt(0));
        assertSame(ProtosNullValue.INSTANCE, observed.indexedAt(1));
        assertSame(ProtosBooleanValue.TRUE, observed.indexedAt(2));
        assertInstanceOf(
                ProtosArrayValue.class, observed.indexedAt(3));
        assertEquals(String.valueOf("1"), com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerDecimalText(integerAt(observed, 4)));
    }

    @Test
    void multiResourceReservationIsAtomicWhenOnePoolIsContended() throws Exception {
        String catalog =
                "catalog: Catalog.parse("
                        + protosString(
                                "resource-catalog-version = 1\n"
                                        + "[[resource]]\n"
                                        + "key = \"gpu\"\n"
                                        + "capacity = 4\n"
                                        + "scope = \"placement\"\n"
                                        + "provider = \"device/gpu\"\n"
                                        + "[[resource]]\n"
                                        + "key = \"db/integration\"\n"
                                        + "capacity = 1\n"
                                        + "scope = \"run\"\n"
                                        + "provider = \"service/db\"\n")
                        + ")\n";

        String source =
                commonImports()
                        + catalog
                        + "state: Reservation.newState(catalog)\n"
                        + "dbOnly: Binding.bindRequirements("
                        + "Array(Manifest.requirement("
                        + "\"db/integration\", \"exclusive\", null)), catalog)\n"
                        + "dbHeld: Reservation.tryReserve(dbOnly, state)\n"
                        + "combined: Binding.bindRequirements("
                        + "Array("
                        + "Manifest.requirement(\"gpu\", \"shared\", 2), "
                        + "Manifest.requirement("
                        + "\"db/integration\", \"exclusive\", null)), catalog)\n"
                        + "blocked: Reservation.tryReserve(combined, state)\n"
                        + "Array("
                        + "blocked, "
                        + "Reservation.sharedUsed(state, \"gpu\"), "
                        + "Reservation.exclusiveHeld(state, \"db/integration\"), "
                        + "Reservation.activeReservationCount(state))\n";

        ProtosArrayValue observed = completedArray(source);
        assertSame(ProtosNullValue.INSTANCE, observed.indexedAt(0));
        assertEquals(String.valueOf("0"), com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerDecimalText(integerAt(observed, 1)));
        assertSame(ProtosBooleanValue.TRUE, observed.indexedAt(2));
        assertEquals(String.valueOf("1"), com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerDecimalText(integerAt(observed, 3)));
    }

    @Test
    void staleDuplicateReleaseFailsBeforeTouchingANewerReservation() throws Exception {
        String source =
                commonImports()
                        + catalog("gpu", 4, "run")
                        + "state: Reservation.newState(catalog)\n"
                        + "bindings: Binding.bindRequirements("
                        + "Array(Manifest.requirement(\"gpu\", \"shared\", 2)), catalog)\n"
                        + "first: Reservation.tryReserve(bindings, state)\n"
                        + "Reservation.release(first, state)\n"
                        + "second: Reservation.tryReserve(bindings, state)\n"
                        + "duplicateFailed: false\n"
                        + "Error.handle(() => {\n"
                        + "    Reservation.release(first, state)\n"
                        + "    null\n"
                        + "}, (error) => {\n"
                        + "    duplicateFailed = true\n"
                        + "    null\n"
                        + "})\n"
                        + "Array("
                        + "duplicateFailed, "
                        + "Reservation.sharedUsed(state, \"gpu\"), "
                        + "Reservation.activeReservationCount(state), "
                        + "second)\n";

        ProtosArrayValue observed = completedArray(source);
        assertSame(ProtosBooleanValue.TRUE, observed.indexedAt(0));
        assertEquals(String.valueOf("2"), com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerDecimalText(integerAt(observed, 1)));
        assertEquals(String.valueOf("1"), com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerDecimalText(integerAt(observed, 2)));
        assertInstanceOf(
                ProtosArrayValue.class, observed.indexedAt(3));
    }

    @Test
    void stateAndBindingsFromDifferentCatalogSnapshotsFailClosed() throws Exception {
        String source =
                commonImports()
                        + "firstCatalog: Catalog.parse("
                        + catalogText("gpu", 2, "run")
                        + ")\n"
                        + "secondCatalog: Catalog.parse("
                        + catalogText("gpu", 2, "run")
                        + ")\n"
                        + "state: Reservation.newState(firstCatalog)\n"
                        + "bindings: Binding.bindRequirements("
                        + "Array(Manifest.requirement(\"gpu\", \"shared\", 1)), "
                        + "secondCatalog)\n"
                        + "Reservation.tryReserve(bindings, state)\n";

        ProtosExecutionOutcome outcome = execute(source);
        assertEquals(ProtosExecutionOutcome.State.FAILED, outcome.state());
    }

    private static String commonImports() {
        return "Manifest: import(\"self:Manifest\")\n"
                + "Catalog: import(\"self:ResourceCatalog\")\n"
                + "Binding: import(\"self:ResourceBinding\")\n"
                + "Reservation: import(\"self:ResourceReservation\")\n";
    }

    private static String catalog(String key, int capacity, String scope) {
        return "catalog: Catalog.parse("
                + catalogText(key, capacity, scope)
                + ")\n";
    }

    private static String catalogText(String key, int capacity, String scope) {
        return protosString(
                "resource-catalog-version = 1\n"
                        + "[[resource]]\n"
                        + "key = \"" + key + "\"\n"
                        + "capacity = " + capacity + "\n"
                        + "scope = \"" + scope + "\"\n"
                        + "provider = \"provider/example\"\n");
    }

    private static ProtosArrayValue completedArray(String source) throws Exception {
        ProtosExecutionOutcome outcome = execute(source);
        assertEquals(
                ProtosExecutionOutcome.State.COMPLETED,
                outcome.state(),
                () -> "state=" + outcome.state() + ", error=" + outcome.error());
        return assertInstanceOf(ProtosArrayValue.class, outcome.value());
    }

    private static ProtosExecutionOutcome execute(String source) throws Exception {
        ProtosBundledToolModuleResolver resolver =
                new ProtosBundledToolModuleResolver(
                        "test",
                        TOOL_ROOT,
                        SHARED_ROOT,
                        new ProtosStandardLibraryModuleResolver(STANDARD_LIBRARY));
        ProtosPrelude prelude = new ProtosCoreBootstrap().bootstrap(CORE, resolver);
        return com.guillermomolina.protos.execution.ProtosTestExecutionSupport.execute(
source,
prelude.newModuleActivation());
    }

    private static ProtosArrayValue arrayAt(ProtosArrayValue array, int index) {
        return assertInstanceOf(
                ProtosArrayValue.class,
                array.indexedAt(index));
    }

    private static ProtosIntegerValue integerAt(ProtosArrayValue array, int index) {
        return assertInstanceOf(
                ProtosIntegerValue.class,
                array.indexedAt(index));
    }

    private static String protosString(String value) {
        return "\""
                + value.replace("\\", "\\\\")
                        .replace("\"", "\\\"")
                        .replace("\r", "\\r")
                        .replace("\n", "\\n")
                        .replace("\t", "\\t")
                + "\"";
    }
}
