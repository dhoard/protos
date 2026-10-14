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

import com.guillermomolina.protos.runtime.ProtosActivation;
import com.guillermomolina.protos.runtime.ProtosClosureValue;
import com.guillermomolina.protos.runtime.ProtosCoreErrors;
import com.guillermomolina.protos.runtime.ProtosFutureValue;
import com.guillermomolina.protos.runtime.ProtosHostIpEndpoint;
import com.guillermomolina.protos.runtime.ProtosNetworkCapabilityValue;
import com.guillermomolina.protos.runtime.ProtosNetworkConnectFlow;
import com.guillermomolina.protos.runtime.ProtosNetworkListenFlow;
import com.guillermomolina.protos.runtime.ProtosNullValue;
import com.guillermomolina.protos.runtime.ProtosNumericValueSupport;
import com.guillermomolina.protos.runtime.ProtosObjectValue;
import com.guillermomolina.protos.runtime.ProtosPrelude;
import com.guillermomolina.protos.runtime.ProtosTcpConnectionValue;
import com.guillermomolina.protos.runtime.ProtosTcpListenerFlow;
import com.guillermomolina.protos.runtime.ProtosTcpListenerValue;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Objects;

/** D047/PLAT002/PLAT003 standard Network acquisition bridge. */
public final class ProtosStandardNetworkProtocol {
    private static final Set<String> LISTEN_REQUEST_SLOTS = Set.of("ipVersion", "address", "port");

    private ProtosStandardNetworkProtocol() {}

    public static void install(ProtosObjectValue prototype) {
        Objects.requireNonNull(prototype, "prototype");
        if (prototype.parent().orElse(null) != ProtosObjectValue.rootObject()) {
            throw new IllegalArgumentException(
                    "standard Network prototype must delegate directly to Object");
        }
        if (!prototype.isOpen() || !prototype.localSlotsSnapshot().isEmpty()) {
            throw new IllegalStateException(
                    "standard Network prototype must be fresh and open");
        }

        prototype.createLocalSlot(
                "connectTcp",
                ProtosClosureValue.nativeClosure(
                        ProtosStandardNetworkProtocol::connectTcp));
        prototype.createLocalSlot(
                "listenTcp",
                ProtosClosureValue.nativeClosure(
                        ProtosStandardNetworkProtocol::listenTcp));
    }

    private static ProtosFutureValue connectTcp(
            ProtosActivation activation, List<?> supplied) {
        ProtosPrelude prelude = activation.prelude().orElseThrow();
        ProtosObjectValue prototype = prelude.networkPrototype();

        if (supplied.size() != 1
                || !(activation.receiver() instanceof ProtosNetworkCapabilityValue network)
                || network.representedDelegationParent(prelude) != prototype) {
            return failedFuture(
                    activation, ProtosCoreErrors.StandardError.INVALID_I_O_ARGUMENT);
        }

        Object endpointValue = supplied.get(0);
        if (!(endpointValue instanceof ProtosObjectValue endpoint)
                || !ProtosStandardIpEndpointProtocol.recognizesValue(
                        endpoint,
                        prelude.ipEndpointPrototypeForRuntime(),
                        prelude.ipAddressPrototypeForRuntime())) {
            return failedFuture(
                    activation, ProtosCoreErrors.StandardError.INVALID_I_O_ARGUMENT);
        }

        // Canonical recognition has completed before any host acquisition.
        ProtosHostIpEndpoint hostEndpoint;
        try {
            ProtosObjectValue address =
                    (ProtosObjectValue) endpoint.readLocalSlot("address").orElseThrow();
            int version = boundedFieldOrInvalid(
                    address.readLocalSlot("version").orElseThrow());
            int port = boundedFieldOrInvalid(
                    endpoint.readLocalSlot("port").orElseThrow());
            byte[] octets = ProtosNumericValueSupport.unsignedBigEndianOrNull(
                    address.readLocalSlot("bits").orElseThrow(),
                    version == 4 ? 4 : 16);
            hostEndpoint = new ProtosHostIpEndpoint(version, octets, port);
        } catch (RuntimeException invalid) {
            return failedFuture(
                    activation, ProtosCoreErrors.StandardError.INVALID_I_O_ARGUMENT);
        }

        Object target = network.authorityTargetForRuntime();
        if (!(target instanceof ProtosNetworkConnectFlow.Backend backend)) {
            return failedFuture(activation, ProtosCoreErrors.StandardError.I_O_ERROR);
        }

        ProtosNetworkConnectFlow flow =
                new ProtosNetworkConnectFlow(
                        network,
                        activation,
                        backend,
                        (materializationActivation,
                                        requestedEndpoint,
                                        resourceState,
                                        localEndpoint,
                                        connectionBackend) ->
                                materializeConnection(
                                        prelude,
                                        materializationActivation,
                                        requestedEndpoint,
                                        resourceState,
                                        localEndpoint,
                                        connectionBackend));
        return flow.connect(activation, endpoint, hostEndpoint);
    }

    private record ListenRequestSlots(
            Object ipVersion,
            Object address,
            Object port) {}

    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    private static ListenRequestSlots captureListenRequestSlotsForHost(
            ProtosObjectValue request) {
        Map<String, Object> slots = request.localSlotsSnapshot();
        if (slots.size() != 3 || !slots.keySet().equals(LISTEN_REQUEST_SLOTS)) {
            return null;
        }
        return new ListenRequestSlots(
                slots.get("ipVersion"),
                slots.get("address"),
                slots.get("port"));
    }

    private static ProtosFutureValue listenTcp(
            ProtosActivation activation, List<?> supplied) {
        ProtosPrelude prelude = activation.prelude().orElseThrow();
        ProtosObjectValue prototype = prelude.networkPrototype();
        if (supplied.size() != 1
                || !(activation.receiver() instanceof ProtosNetworkCapabilityValue network)
                || network.representedDelegationParent(prelude) != prototype
                || !(supplied.get(0) instanceof ProtosObjectValue request)) {
            return failedFuture(activation, ProtosCoreErrors.StandardError.INVALID_I_O_ARGUMENT);
        }

        ListenRequestSlots slots = captureListenRequestSlotsForHost(request);
        if (slots == null) {
            return failedFuture(activation, ProtosCoreErrors.StandardError.INVALID_I_O_ARGUMENT);
        }

        int ipVersion = boundedFieldOrInvalid(slots.ipVersion());
        if (ipVersion != 4 && ipVersion != 6) {
            return failedFuture(activation, ProtosCoreErrors.StandardError.INVALID_I_O_ARGUMENT);
        }

        ProtosObjectValue addressConstraint = null;
        Object addressValue = slots.address();
        if (addressValue != ProtosNullValue.INSTANCE) {
            if (!(addressValue instanceof ProtosObjectValue address)
                    || !ProtosStandardIpAddressProtocol.recognizesValue(
                            address, prelude.ipAddressPrototypeForRuntime())) {
                return failedFuture(activation, ProtosCoreErrors.StandardError.INVALID_I_O_ARGUMENT);
            }
            if (boundedFieldOrInvalid(address.readLocalSlot("version").orElse(null)) != ipVersion) {
                return failedFuture(activation, ProtosCoreErrors.StandardError.INVALID_I_O_ARGUMENT);
            }
            addressConstraint = address;
        }

        Integer portConstraint = null;
        Object portValue = slots.port();
        if (portValue != ProtosNullValue.INSTANCE) {
            int port = boundedFieldOrInvalid(portValue);
            if (port < 1 || port > 65535) {
                return failedFuture(activation, ProtosCoreErrors.StandardError.INVALID_I_O_ARGUMENT);
            }
            portConstraint = port;
        }

        ProtosNetworkListenFlow.ListenRequest captured =
                new ProtosNetworkListenFlow.ListenRequest(ipVersion, addressConstraint, portConstraint);
        Object target = network.authorityTargetForRuntime();
        if (!(target instanceof ProtosNetworkListenFlow.Backend backend)) {
            return failedFuture(activation, ProtosCoreErrors.StandardError.I_O_ERROR);
        }
        ProtosNetworkListenFlow flow =
                new ProtosNetworkListenFlow(network, activation, backend, ProtosStandardNetworkProtocol::materializeListener);
        return flow.listen(activation, captured);
    }

    /*
     * The exact value of a bounded listen-request field, read once. Every admitted field domain
     * (version 4 or 6, port 1..65535) excludes -1, so -1 stands for any non-Integer or any Integer
     * outside the int range, including a large Integer; nothing is truncated.
     */
    private static int boundedFieldOrInvalid(Object value) {
        return ProtosNumericValueSupport.isIntegerInIntRange(value)
                ? ProtosNumericValueSupport.exactInt(value)
                : -1;
    }

    private static ProtosTcpListenerValue materializeListener(
            ProtosActivation activation,
            ProtosNetworkListenFlow.ListenRequest request,
            Object resourceState,
            int localPort,
            ProtosTcpListenerFlow.Backend listenerBackend) {
        if (request.portConstraint() != null
                && (request.portConstraint() != localPort)) {
            throw new IllegalArgumentException("backend listener port does not match fixed listen request");
        }
        ProtosPrelude prelude = activation.prelude().orElseThrow();
        return new ProtosTcpListenerValue(
                prelude,
                resourceState,
                activation,
                listenerBackend,
                localPort,
                ProtosStandardTcpListenerProtocol::materializeAcceptedConnection);
    }

    private static ProtosTcpConnectionValue materializeConnection(
            ProtosPrelude prelude,
            ProtosActivation activation,
            ProtosObjectValue requestedEndpoint,
            Object resourceState,
            ProtosHostIpEndpoint localDescriptor,
            com.guillermomolina.protos.runtime.ProtosTcpConnectionFlow.Backend connectionBackend) {
        ProtosObjectValue requestedAddress = (ProtosObjectValue)
                requestedEndpoint.readLocalSlot("address").orElseThrow();
        int requestedVersion = boundedFieldOrInvalid(
                requestedAddress.readLocalSlot("version").orElseThrow());
        if (localDescriptor.version() != requestedVersion)
            throw new IllegalArgumentException("local IP version changed");

        // Private semantic materialization mirrors the canonical source
        // constructor without invoking source closures inside a Task.
        ProtosObjectValue localAddress =
                new ProtosObjectValue(prelude.ipAddressPrototypeForRuntime());
        localAddress.createLocalSlot(
                "version", ProtosNumericValueSupport.integer(localDescriptor.version()));
        localAddress.createLocalSlot(
                "bits", ProtosNumericValueSupport.integerFromUnsignedBigEndian(
                        localDescriptor.octets(), prelude));
        localAddress.freeze();

        ProtosObjectValue localEndpoint =
                new ProtosObjectValue(prelude.ipEndpointPrototypeForRuntime());
        localEndpoint.createLocalSlot("address", localAddress);
        localEndpoint.createLocalSlot(
                "port", ProtosNumericValueSupport.integer(localDescriptor.port()));
        localEndpoint.freeze();

        if (!ProtosStandardIpEndpointProtocol.recognizesValue(
                localEndpoint,
                prelude.ipEndpointPrototypeForRuntime(),
                prelude.ipAddressPrototypeForRuntime()))
            throw new IllegalArgumentException("invalid materialized endpoint");
        return new ProtosTcpConnectionValue(
                prelude,
                resourceState,
                activation,
                connectionBackend,
                localEndpoint,
                requestedEndpoint);
    }

    private static ProtosFutureValue failedFuture(
            ProtosActivation activation, ProtosCoreErrors.StandardError error) {
        ProtosFutureValue future =
                new ProtosFutureValue(
                        activation.prelude().orElseThrow().futurePrototype(),
                        activation.executionDomain());
        future.fail(ProtosCoreErrors.newOccurrence(activation, error));
        return future;
    }
}
