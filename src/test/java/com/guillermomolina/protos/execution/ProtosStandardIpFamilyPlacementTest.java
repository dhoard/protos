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

import com.guillermomolina.protos.runtime.ProtosTestIntegers;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.guillermomolina.protos.runtime.ProtosActivation;
import com.guillermomolina.protos.runtime.ProtosActorModuleState;
import com.guillermomolina.protos.runtime.ProtosActorValueTransfer;
import com.guillermomolina.protos.runtime.ProtosBooleanValue;
import com.guillermomolina.protos.runtime.ProtosFutureValue;
import com.guillermomolina.protos.runtime.ProtosModuleKey;
import com.guillermomolina.protos.runtime.ProtosNumericValueSupport;
import com.guillermomolina.protos.runtime.ProtosObjectValue;
import com.guillermomolina.protos.runtime.ProtosPrelude;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * I066/D172 placement guards for the canonical IP families.
 *
 * <p>The families are runtime-retained FROZEN standard objects exposed only as initial members of
 * {@code std:network/IpAddresses} and {@code std:network/IpEndpoints}. Isolation transfer keeps
 * them as exact anchors and never imports a module to recover them; the counting resolver below
 * makes any such import deterministically observable.
 */
final class ProtosStandardIpFamilyPlacementTest {
    private static final Path CORE = Path.of("protos", "lib", "core");
    private static final Path STANDARD_LIBRARY = Path.of("protos", "lib");
    private static final ProtosModuleKey IP_ADDRESSES =
            new ProtosModuleKey("std:network/IpAddresses");
    private static final ProtosModuleKey IP_ENDPOINTS =
            new ProtosModuleKey("std:network/IpEndpoints");
    private static final String SOURCE_ENDPOINT =
            """
            IpAddresses: import("std:network/IpAddresses")
            IpEndpoints: import("std:network/IpEndpoints")
            IpAddress: IpAddresses.IpAddress
            IpEndpoint: IpEndpoints.IpEndpoint
            address: IpAddress(6, 340282366920938463463374607431768211455)
            endpoint: IpEndpoint(address, 65535)
            endpoint
            """;

    /** Delegating resolver that counts every resolve and loadSource request. */
    private static final class CountingResolver implements ProtosModuleResolver {
        private final ProtosModuleResolver delegate =
                new ProtosStandardLibraryModuleResolver(STANDARD_LIBRARY);
        final AtomicInteger resolveCount = new AtomicInteger();
        final AtomicInteger loadSourceCount = new AtomicInteger();

        @Override
        public ProtosModuleKey resolve(
                String exactSpecifier, Optional<ProtosModuleKey> importingModule)
                throws Exception {
            resolveCount.incrementAndGet();
            return delegate.resolve(exactSpecifier, importingModule);
        }

        @Override
        public ProtosModuleSource loadSource(ProtosModuleKey key) throws Exception {
            loadSourceCount.incrementAndGet();
            return delegate.loadSource(key);
        }
    }

    @Test
    void preludeNoLongerPublishesIpFamilies() throws Exception {
        ProtosPrelude prelude = new ProtosCoreBootstrap().bootstrap(CORE);

        assertTrue(prelude.bindings().readLocalSlot("IpAddress").isEmpty());
        assertTrue(prelude.bindings().readLocalSlot("IpEndpoint").isEmpty());
        // The private bootstrap anchors captured by the source IP closures are not Prelude surface.
        assertTrue(prelude.bindings().readLocalSlot("_coreIpAddressCanonical").isEmpty());
        assertTrue(prelude.bindings().readLocalSlot("_coreIpEndpointCanonical").isEmpty());
        assertTrue(prelude.ipAddressPrototypeForRuntime().isFrozen());
        assertTrue(prelude.ipEndpointPrototypeForRuntime().isFrozen());
        assertSame(
                ProtosObjectValue.rootObject(),
                prelude.ipAddressPrototypeForRuntime().parent().orElseThrow());
        assertSame(
                ProtosObjectValue.rootObject(),
                prelude.ipEndpointPrototypeForRuntime().parent().orElseThrow());
    }

    @Test
    void standardModuleMemberSeamIsGeneralAndKeyed() throws Exception {
        ProtosPrelude core = new ProtosCoreBootstrap().bootstrap(CORE);
        ProtosModuleKey key = new ProtosModuleKey("std:example/Members");
        ProtosObjectValue member = new ProtosObjectValue(ProtosObjectValue.rootObject());
        member.freeze();
        ProtosPrelude prelude =
                new ProtosPrelude(
                        core.bindings(),
                        core.contextPrototype(),
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        Map.of(key, Map.of("Member", member)));

        ProtosObjectValue registered = prelude.newExecutionContext();
        prelude.installStandardModuleMembersForRuntime(key, registered);
        assertSame(member, registered.readLocalSlot("Member").orElseThrow());

        ProtosObjectValue unregistered = prelude.newExecutionContext();
        prelude.installStandardModuleMembersForRuntime(
                new ProtosModuleKey("std:example/Other"), unregistered);
        prelude.installStandardModuleMembersForRuntime(null, unregistered);
        assertTrue(unregistered.localSlotsSnapshot().isEmpty());

        assertThrows(
                IllegalStateException.class,
                () -> prelude.installStandardModuleMembersForRuntime(key, registered));

        ProtosObjectValue mutable = new ProtosObjectValue(ProtosObjectValue.rootObject());
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new ProtosPrelude(
                                core.bindings(),
                                core.contextPrototype(),
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                Map.of(key, Map.of("Member", mutable))));
    }

    @Test
    void moduleLifecycleHasNoIpSpecificKnowledge() throws Exception {
        Path execution =
                Path.of("src", "main", "java", "com", "guillermomolina", "protos", "execution");
        for (String file :
                new String[] {
                    "ProtosModuleRuntime.java", "ProtosCanonicalInitialModuleExecution.java"
                }) {
            String source = Files.readString(execution.resolve(file));
            assertFalse(source.contains("std:network"), file);
            assertFalse(source.contains("IpAddress"), file);
            assertFalse(source.contains("IpEndpoint"), file);
            assertTrue(source.contains("installStandardModuleMembersForRuntime"), file);
        }
    }

    @Test
    void actorTransferKeepsCanonicalFamiliesWithoutImporting() throws Exception {
        CountingResolver resolver = new CountingResolver();
        ProtosPrelude prelude = new ProtosCoreBootstrap().bootstrap(CORE, resolver);
        ProtosActivation activation = prelude.newModuleActivation();
        ProtosObjectValue endpoint =
                assertInstanceOf(
                        ProtosObjectValue.class,
                        ProtosTestExecutionSupport.evaluate(SOURCE_ENDPOINT, activation));
        ProtosActorModuleState modules = activation.actorModuleState();
        ProtosActorModuleState.ModuleRecord addresses = modules.lookup(IP_ADDRESSES).orElseThrow();
        ProtosActorModuleState.ModuleRecord endpoints = modules.lookup(IP_ENDPOINTS).orElseThrow();
        int resolves = resolver.resolveCount.get();
        int loads = resolver.loadSourceCount.get();

        ProtosObjectValue transferred =
                assertInstanceOf(
                        ProtosObjectValue.class,
                        ProtosActorValueTransfer.snapshotValue(endpoint, activation));

        assertEquals(resolves, resolver.resolveCount.get());
        assertEquals(loads, resolver.loadSourceCount.get());
        assertSame(addresses, modules.lookup(IP_ADDRESSES).orElseThrow());
        assertSame(endpoints, modules.lookup(IP_ENDPOINTS).orElseThrow());
        assertCanonicalCopy(prelude, endpoint, transferred);
    }

    @Test
    void parallelTransferKeepsCanonicalFamiliesWithoutImporting() throws Exception {
        CountingResolver resolver = new CountingResolver();
        ProtosPrelude prelude = new ProtosCoreBootstrap().bootstrap(CORE, resolver);
        try (var hosted = ProtosHostedExecutionTestFixture.open(prelude)) {
            ProtosObjectValue endpoint =
                    assertInstanceOf(
                            ProtosObjectValue.class,
                            hosted.evaluatePersistent("<d172-p-source>", SOURCE_ENDPOINT));
            int resolves = resolver.resolveCount.get();
            int loads = resolver.loadSourceCount.get();

            var domain = hosted.activation().executionDomain();
            ProtosFutureValue future =
                    assertInstanceOf(
                            ProtosFutureValue.class,
                            hosted.evaluatePersistent(
                                    "<d172-p-transfer>",
                                    "((value) => { value }).parallel(endpoint)"));
            while (future.isPending()) {
                hosted.callEntered(
                        () -> {
                            domain.dispatchUntilIdle();
                            return null;
                        });
                Thread.onSpinWait();
            }
            assertEquals(ProtosFutureValue.State.RESOLVED, future.state());
            ProtosObjectValue transferred =
                    assertInstanceOf(
                            ProtosObjectValue.class, future.resolvedValue().orElseThrow());

            // Both the P worker and the result copy-back ran without any module request.
            assertEquals(resolves, resolver.resolveCount.get());
            assertEquals(loads, resolver.loadSourceCount.get());
            assertCanonicalCopy(prelude, endpoint, transferred);
        }
    }

    private static final String IP_ADDRESS =
            "IpAddress: import(\"std:network/IpAddresses\").IpAddress\n";
    private static final BigInteger THIRTY_ONE = BigInteger.valueOf(31);

    private static BigInteger expectedHash(BigInteger bits, int version) {
        return bits.multiply(THIRTY_ONE).add(BigInteger.valueOf(version));
    }

    private static Object guestHash(ProtosPrelude prelude, int version, BigInteger bits) {
        return ProtosTestExecutionSupport.evaluate(
                IP_ADDRESS + "IpAddress(" + version + ", " + bits + ").hash()",
                prelude.newModuleActivation());
    }

    @Test
    void addressHashIsTheExactUnboundedValueForBothVersions() throws Exception {
        ProtosPrelude prelude = new ProtosCoreBootstrap().bootstrap(CORE, new CountingResolver());
        BigInteger ipv6Max = BigInteger.ONE.shiftLeft(128).subtract(BigInteger.ONE);
        for (BigInteger bits : java.util.List.of(
                BigInteger.ZERO, BigInteger.valueOf(0x7f000001L), BigInteger.valueOf(0xffffffffL))) {
            assertEquals(expectedHash(bits, 4), ProtosTestIntegers.exact(guestHash(prelude, 4, bits)));
        }
        for (BigInteger bits : java.util.List.of(
                BigInteger.ONE,
                // bits * 31 leaves the signed-64 range although bits itself does not.
                BigInteger.ONE.shiftLeft(62),
                BigInteger.valueOf(Long.MAX_VALUE),
                BigInteger.ONE.shiftLeft(127),
                ipv6Max)) {
            Object hash = guestHash(prelude, 6, bits);
            assertEquals(expectedHash(bits, 6), ProtosTestIntegers.exact(hash), bits.toString(16));
            if (expectedHash(bits, 6).bitLength() >= Long.SIZE) {
                assertSame(prelude.bigIntegerPrototype(),
                        ((ProtosObjectValue) hash).parent().orElseThrow(), bits.toString(16));
            }
        }
    }

    @Test
    void addressesSharingLowBitsAreDistinctAndRematerializedCopiesAreEqual() throws Exception {
        ProtosPrelude prelude = new ProtosCoreBootstrap().bootstrap(CORE, new CountingResolver());
        assertSame(ProtosBooleanValue.TRUE, ProtosTestExecutionSupport.evaluate(
                IP_ADDRESS
                        + "low: IpAddress(6, 1)\n"
                        + "wide: IpAddress(6, 18446744073709551617)\n"
                        + "same: IpAddress(6, 18446744073709551616 + 1)\n"
                        + "!(low == wide) && !(low.hash() == wide.hash()) &&\n"
                        + "    (wide == same) && (wide.hash() == same.hash())",
                prelude.newModuleActivation()));

        ProtosActivation activation = prelude.newModuleActivation();
        ProtosObjectValue address = (ProtosObjectValue) ProtosTestExecutionSupport.evaluate(
                IP_ADDRESS + "IpAddress(6, 170141183460469231731687303715884105728)", activation);
        ProtosObjectValue copy = (ProtosObjectValue)
                ProtosActorValueTransfer.snapshotValue(address, activation);
        assertNotSame(address, copy);
        assertSame(ProtosBooleanValue.TRUE,
                ProtosInvocation.invokeMessage(address, "==", java.util.List.of(copy), activation));
        assertTrue(ProtosNumericValueSupport.sameInteger(
                ProtosInvocation.invokeMessage(address, "hash", java.util.List.of(), activation),
                ProtosInvocation.invokeMessage(copy, "hash", java.util.List.of(), activation)));
    }

    @Test
    void eachPreludeOwnsTheLargeHashesItComputes() throws Exception {
        ProtosPrelude left = new ProtosCoreBootstrap().bootstrap(CORE, new CountingResolver());
        ProtosPrelude right = new ProtosCoreBootstrap().bootstrap(CORE, new CountingResolver());
        BigInteger bits = BigInteger.ONE.shiftLeft(127);
        Object leftHash = guestHash(left, 6, bits);
        Object rightHash = guestHash(right, 6, bits);
        assertSame(left.bigIntegerPrototype(), ((ProtosObjectValue) leftHash).parent().orElseThrow());
        assertSame(right.bigIntegerPrototype(), ((ProtosObjectValue) rightHash).parent().orElseThrow());
        assertEquals(ProtosTestIntegers.exact(leftHash), ProtosTestIntegers.exact(rightHash));
    }

    @Test
    void largeHashIsComputedBySourceInTheExecutingPrelude() throws Exception {
        ProtosPrelude prelude = new ProtosCoreBootstrap().bootstrap(CORE, new CountingResolver());
        ProtosActivation activation = prelude.newModuleActivation();
        BigInteger bits = BigInteger.ONE.shiftLeft(62);
        ProtosObjectValue address = (ProtosObjectValue) ProtosTestExecutionSupport.evaluate(
                IP_ADDRESS + "IpAddress(6, " + bits + ")", activation);
        Object hash = ProtosInvocation.invokeMessage(address, "hash", java.util.List.of(), activation);
        assertTrue(hash != null, "IP hash must never be a guest null");
        assertTrue(ProtosNumericValueSupport.isCurrentInteger(hash));
        assertEquals(expectedHash(bits, 6), ProtosTestIntegers.exact(hash));
        assertTrue(expectedHash(bits, 6).bitLength() >= Long.SIZE);
        assertSame(prelude.bigIntegerPrototype(), ((ProtosObjectValue) hash).parent().orElseThrow());
    }

    private static void assertCanonicalCopy(
            ProtosPrelude prelude, ProtosObjectValue source, ProtosObjectValue copy) {
        ProtosObjectValue addressPrototype = prelude.ipAddressPrototypeForRuntime();
        ProtosObjectValue endpointPrototype = prelude.ipEndpointPrototypeForRuntime();
        assertNotSame(source, copy);
        assertSame(endpointPrototype, copy.parent().orElseThrow());
        assertTrue(copy.isFrozen());
        assertTrue(
                ProtosStandardIpEndpointProtocol.recognizesValue(
                        copy, endpointPrototype, addressPrototype));
        assertEquals(
                ProtosTestIntegers.exact(source.readLocalSlot("port").orElseThrow()),
                ProtosTestIntegers.exact(copy.readLocalSlot("port").orElseThrow()));

        ProtosObjectValue sourceAddress =
                (ProtosObjectValue) source.readLocalSlot("address").orElseThrow();
        ProtosObjectValue copyAddress =
                (ProtosObjectValue) copy.readLocalSlot("address").orElseThrow();
        assertNotSame(sourceAddress, copyAddress);
        assertSame(addressPrototype, copyAddress.parent().orElseThrow());
        ProtosActivation activation = prelude.newModuleActivation();
        assertSame(
                ProtosBooleanValue.TRUE,
                ProtosInvocation.invokeMessage(
                        sourceAddress, "==", java.util.List.of(copyAddress), activation));
        assertTrue(
                ProtosNumericValueSupport.sameInteger(
                        ProtosInvocation.invokeMessage(
                                sourceAddress, "hash", java.util.List.of(), activation),
                        ProtosInvocation.invokeMessage(
                                copyAddress, "hash", java.util.List.of(), activation)));
        assertSame(
                ProtosBooleanValue.TRUE,
                ProtosInvocation.invokeMessage(source, "==", java.util.List.of(copy), activation));
    }
}
