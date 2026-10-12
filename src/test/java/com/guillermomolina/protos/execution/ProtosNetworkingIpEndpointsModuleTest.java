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
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.guillermomolina.protos.runtime.ProtosActivation;
import com.guillermomolina.protos.runtime.ProtosActorValueTransfer;
import com.guillermomolina.protos.runtime.ProtosBooleanValue;
import com.guillermomolina.protos.runtime.ProtosNumericValueSupport;
import com.guillermomolina.protos.runtime.ProtosObjectValue;
import com.guillermomolina.protos.runtime.ProtosPrelude;
import com.guillermomolina.protos.runtime.ProtosSignalException;

import java.math.BigInteger;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * LIB005-C IpEndpoints harness. Observable endpoint parsing/formatting behavior
 * is owned by ordinary Protos fixtures. Java only supplies real Core/std
 * bootstrap and observes the exact local module export surface.
 */
final class ProtosNetworkingIpEndpointsModuleTest {
    private static final Path CORE = Path.of("protos", "lib", "core");
    private static final Path STANDARD_LIBRARY = Path.of("protos", "lib");

    @Test
    void importedModuleExportsExactlyApprovedSurface() throws Exception {
        ProtosStandardLibraryModuleResolver resolver =
                new ProtosStandardLibraryModuleResolver(STANDARD_LIBRARY);
        ProtosPrelude prelude = new ProtosCoreBootstrap().bootstrap(CORE, resolver);

        Object imported =
                ProtosTestExecutionSupport.evaluate(
                        "import(\"std:network/IpEndpoints\")",
                        prelude.newModuleActivation());
        ProtosObjectValue module = assertInstanceOf(ProtosObjectValue.class, imported);

        assertEquals(Set.of("IpEndpoint", "parse", "format"), module.localSlotsSnapshot().keySet());
    }

    /**
     * D172: the module member is the runtime-retained canonical family, shared by Actor-local
     * module instances that are themselves distinct.
     */
    @Test
    void moduleMemberIsRuntimeCanonicalFamilyAcrossActorLocalInstances() throws Exception {
        ProtosStandardLibraryModuleResolver resolver =
                new ProtosStandardLibraryModuleResolver(STANDARD_LIBRARY);
        ProtosPrelude prelude = new ProtosCoreBootstrap().bootstrap(CORE, resolver);

        // Each newModuleActivation() owns a fresh Actor-local module state.
        ProtosObjectValue first =
                assertInstanceOf(
                        ProtosObjectValue.class,
                        ProtosTestExecutionSupport.evaluate(
                                "import(\"std:network/IpEndpoints\")", prelude.newModuleActivation()));
        ProtosObjectValue second =
                assertInstanceOf(
                        ProtosObjectValue.class,
                        ProtosTestExecutionSupport.evaluate(
                                "import(\"std:network/IpEndpoints\")", prelude.newModuleActivation()));

        assertNotSame(first, second);
        assertSame(
                prelude.ipEndpointPrototypeForRuntime(),
                first.readLocalSlot("IpEndpoint").orElseThrow());
        assertSame(
                prelude.ipEndpointPrototypeForRuntime(),
                second.readLocalSlot("IpEndpoint").orElseThrow());
        assertEquals(false, prelude.bindings().hasLocalSlot("IpEndpoint"));
    }

    private static final String FAMILIES =
            "IpAddress: import(\"std:network/IpAddresses\").IpAddress\n"
                    + "IpEndpoint: import(\"std:network/IpEndpoints\").IpEndpoint\n";
    private static final BigInteger THIRTY_ONE = BigInteger.valueOf(31);

    private static ProtosPrelude prelude() throws Exception {
        return new ProtosCoreBootstrap().bootstrap(
                CORE, new ProtosStandardLibraryModuleResolver(STANDARD_LIBRARY));
    }

    /* (bits * 31 + version) * 31 + port: the exact address hash folded with the port. */
    private static BigInteger expectedHash(int version, BigInteger bits, int port) {
        return bits.multiply(THIRTY_ONE).add(BigInteger.valueOf(version))
                .multiply(THIRTY_ONE).add(BigInteger.valueOf(port));
    }

    private static Object endpointHash(
            ProtosPrelude prelude, int version, BigInteger bits, int port) {
        return ProtosTestExecutionSupport.evaluate(
                FAMILIES + "IpEndpoint(IpAddress(" + version + ", " + bits + "), " + port
                        + ").hash()",
                prelude.newModuleActivation());
    }

    @Test
    void endpointHashIsExactForIpv4AndLargeIpv6() throws Exception {
        ProtosPrelude prelude = prelude();
        BigInteger ipv4 = BigInteger.valueOf(0xc0a80001L);
        assertEquals(String.valueOf(expectedHash(4, ipv4, 1)), com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerDecimalText(endpointHash(prelude, 4, ipv4, 1)));
        assertEquals(String.valueOf(expectedHash(4, ipv4, 65535)), com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerDecimalText(endpointHash(prelude, 4, ipv4, 65535)));
        for (BigInteger bits : List.of(
                BigInteger.ONE.shiftLeft(57),
                BigInteger.ONE.shiftLeft(127),
                BigInteger.ONE.shiftLeft(128).subtract(BigInteger.ONE))) {
            Object hash = endpointHash(prelude, 6, bits, 443);
            assertEquals(String.valueOf(expectedHash(6, bits, 443)), com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerDecimalText(hash));
            assertSame(prelude.bigIntegerPrototype(),
                    ((ProtosObjectValue) hash).parent().orElseThrow());
        }
    }

    @Test
    void endpointPortsAreValidatedAndNeverLargeIntegers() throws Exception {
        ProtosPrelude prelude = prelude();
        for (String port : List.of("0", "0 - 1", "65536", "18446744073709551696", "80.0")) {
            assertThrows(ProtosSignalException.class,
                    () -> ProtosTestExecutionSupport.evaluate(
                            FAMILIES + "IpEndpoint(IpAddress(4, 1), " + port + ")",
                            prelude.newModuleActivation()),
                    port);
        }
        // An IpAddress is not an IpEndpoint, nor the reverse.
        assertSame(ProtosBooleanValue.TRUE, ProtosTestExecutionSupport.evaluate(
                FAMILIES
                        + "address: IpAddress(6, 1)\n"
                        + "endpoint: IpEndpoint(address, 1)\n"
                        + "!IpEndpoint.recognizes(address) && !IpAddress.recognizes(endpoint)",
                prelude.newModuleActivation()));
    }

    @Test
    void transferredEndpointKeepsEqualityAndExactHash() throws Exception {
        ProtosPrelude prelude = prelude();
        ProtosActivation activation = prelude.newModuleActivation();
        ProtosObjectValue endpoint = (ProtosObjectValue) ProtosTestExecutionSupport.evaluate(
                FAMILIES + "IpEndpoint(IpAddress(6, 340282366920938463463374607431768211455), 1)",
                activation);
        ProtosObjectValue copy =
                (ProtosObjectValue) ProtosActorValueTransfer.snapshotValue(endpoint, activation);
        assertNotSame(endpoint, copy);
        assertSame(ProtosBooleanValue.TRUE,
                ProtosInvocation.invokeMessage(endpoint, "==", List.of(copy), activation));
        Object hash = ProtosInvocation.invokeMessage(copy, "hash", List.of(), activation);
        assertTrue(ProtosNumericValueSupport.sameInteger(
                ProtosInvocation.invokeMessage(endpoint, "hash", List.of(), activation), hash));
        assertEquals((expectedHash(6, BigInteger.ONE.shiftLeft(128).subtract(BigInteger.ONE), 1)).toString(), com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerDecimalText(hash));

        // The same endpoint state built in another Prelude hashes identically, in its own domain.
        ProtosPrelude other = prelude();
        Object otherHash = endpointHash(
                other, 6, BigInteger.ONE.shiftLeft(128).subtract(BigInteger.ONE), 1);
        assertEquals(com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerDecimalText(hash), com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerDecimalText(otherHash));
        assertSame(other.bigIntegerPrototype(),
                ((ProtosObjectValue) otherHash).parent().orElseThrow());
    }
}
