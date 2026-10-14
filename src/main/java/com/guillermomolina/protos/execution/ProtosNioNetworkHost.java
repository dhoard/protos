/*
 * THE LICENSED WORK IS PROVIDED UNDER THE TERMS OF THE ADAPTIVE PUBLIC LICENSE
 * ("LICENSE") AS FIRST COMPLETED BY: Guillermo Adrián Molina. ANY USE, PUBLIC
 * DISPLAY, PUBLIC PERFORMANCE, REPRODUCTION OR DISTRIBUTION OF, OR PREPARATION OF
 * DERIVATIVE WORKS BASED ON, THE LICENSED WORK CONSTITUTES RECIPIENT'S ACCEPTANCE
 * OF THIS LICENSE AND ITS TERMS, WHETHER OR NOT SUCH RECIPIENT READS THE TERMS.
 * "LICENSED WORK" AND "RECIPIENT" ARE DEFINED IN THE LICENSE. A COPY OF THE
 * LICENSE IS LOCATED IN THE TEXT FILE ENTITLED "LICENSE.TXT" ACCOMPANYING THE
 * CONTENTS OF THIS FILE. IF A COPY OF THE LICENSE DOES NOT ACCOMPANY THIS FILE, A
 * COPY OF THE LICENSE MAY ALSO BE OBTAINED AT THE FOLLOWING WEB SITE:
 * https://github.com/guillermomolina/protos
 *
 * Software distributed under the License is distributed on an "AS IS" basis,
 * WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License for
 * the specific language governing rights and limitations under the License.
 */
package com.guillermomolina.protos.execution;

import com.guillermomolina.protos.runtime.ProtosNetworkCapabilityValue;
import com.guillermomolina.protos.runtime.ProtosPrelude;
import java.io.IOException;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * RuntimeHost-owned production NIO Network plane.
 *
 * <p>The host is lazy and semantically invisible. One current NIO poller multiplexes all Network
 * capabilities provisioned from this host; PLAT006 deliberately keeps exact poller cardinality and
 * sharding as replaceable operational policy rather than Protos semantics.
 */
final class ProtosNioNetworkHost implements AutoCloseable {
    private final ProtosNioHostIoPoller poller;

    ProtosNioNetworkHost() throws IOException {
        poller = new ProtosNioHostIoPoller("protos-network-nio");
    }

    ProtosNetworkCapabilityValue provision(ProtosPrelude prelude) {
        ProtosPrelude owningPrelude = Objects.requireNonNull(prelude, "prelude");
        // Throws IllegalStateException when the Prelude does not retain the canonical families.
        owningPrelude.ipAddressPrototypeForRuntime();
        owningPrelude.ipEndpointPrototypeForRuntime();
        return new ProtosNetworkCapabilityValue(owningPrelude, backend());
    }

    /** Pure host networking backend, independent of the guest numeric hierarchy. */
    ProtosNioNetworkBackend backend() {
        return new ProtosNioNetworkBackend(
                poller,
                ProtosNioNetworkHost::resolveIpv6,
                ProtosNioNetworkHost::authorizedIpv6ListenAddresses);
    }

    ProtosNioHostIoPoller pollerForTesting() {
        return poller;
    }

    @Override
    public void close() {
        poller.close();
    }

    /**
     * Resolves IPv6 scope strictly inside the host Network authority.
     *
     * <p>Non-link-local addresses need no interface identity. For link-local addresses, an exact
     * assigned address wins only when unique; otherwise a single active IPv6-link-local interface
     * may provide the scope. Ambiguous or unavailable scope fails closed.
     */
    private static Inet6Address resolveIpv6(byte[] supplied) throws IOException {
        byte[] bytes = Objects.requireNonNull(supplied, "IPv6 address bytes").clone();
        if (bytes.length != 16 || isIpv4Mapped(bytes)) {
            throw new IOException("IPv6 address has invalid host representation");
        }
        InetAddress raw = InetAddress.getByAddress(bytes);
        if (!(raw instanceof Inet6Address unscoped)) {
            throw new IOException("IPv6 address did not materialize as IPv6");
        }
        if (!unscoped.isLinkLocalAddress()) {
            return unscoped;
        }

        ArrayList<Inet6Address> exact = new ArrayList<>();
        ArrayList<NetworkInterface> scopedCandidates = new ArrayList<>();
        for (NetworkInterface networkInterface : activeInterfaces()) {
            boolean hasLinkLocalIpv6 = false;
            Enumeration<InetAddress> addresses = networkInterface.getInetAddresses();
            while (addresses.hasMoreElements()) {
                InetAddress address = addresses.nextElement();
                if (!(address instanceof Inet6Address ipv6)) {
                    continue;
                }
                if (ipv6.isLinkLocalAddress()) {
                    hasLinkLocalIpv6 = true;
                }
                if (Arrays.equals(bytes, ipv6.getAddress())) {
                    exact.add(ipv6);
                }
            }
            if (hasLinkLocalIpv6) {
                scopedCandidates.add(networkInterface);
            }
        }

        if (exact.size() == 1) {
            return exact.get(0);
        }
        if (exact.size() > 1) {
            throw new IOException("IPv6 link-local address has ambiguous Network scope");
        }
        if (scopedCandidates.size() != 1) {
            throw new IOException("IPv6 link-local address has no unambiguous Network scope");
        }
        return Inet6Address.getByAddress(null, bytes, scopedCandidates.get(0));
    }

    /**
     * Captures the current concrete authorized IPv6 listen-address set for one acquisition.
     *
     * <p>No cache or dynamic rebind policy is selected here. PLAT007 owns the acquisition-time
     * snapshot rule; future native backends may replace this public-JDK enumeration mechanism.
     */
    private static List<Inet6Address> authorizedIpv6ListenAddresses() throws IOException {
        Map<String, Inet6Address> unique = new LinkedHashMap<>();
        for (NetworkInterface networkInterface : activeInterfaces()) {
            Enumeration<InetAddress> addresses = networkInterface.getInetAddresses();
            while (addresses.hasMoreElements()) {
                InetAddress address = addresses.nextElement();
                if (!(address instanceof Inet6Address ipv6)
                        || ipv6.isAnyLocalAddress()
                        || ipv6.isMulticastAddress()
                        || isIpv4Mapped(ipv6.getAddress())) {
                    continue;
                }
                unique.putIfAbsent(addressKey(ipv6, networkInterface), ipv6);
            }
        }
        return List.copyOf(unique.values());
    }

    private static List<NetworkInterface> activeInterfaces() throws IOException {
        final Enumeration<NetworkInterface> interfaces;
        try {
            interfaces = NetworkInterface.getNetworkInterfaces();
        } catch (SocketException failure) {
            throw new IOException("cannot enumerate host Network interfaces", failure);
        }
        if (interfaces == null) {
            return List.of();
        }

        ArrayList<NetworkInterface> active = new ArrayList<>();
        while (interfaces.hasMoreElements()) {
            NetworkInterface networkInterface = interfaces.nextElement();
            try {
                if (networkInterface.isUp()) {
                    active.add(networkInterface);
                }
            } catch (SocketException failure) {
                throw new IOException("cannot inspect host Network interface state", failure);
            }
        }
        return List.copyOf(active);
    }

    private static String addressKey(Inet6Address address, NetworkInterface networkInterface) {
        StringBuilder key = new StringBuilder(40);
        for (byte value : address.getAddress()) {
            key.append(Character.forDigit((value >>> 4) & 0xf, 16));
            key.append(Character.forDigit(value & 0xf, 16));
        }
        if (address.isLinkLocalAddress()) {
            key.append('%').append(networkInterface.getName());
        }
        return key.toString();
    }

    private static boolean isIpv4Mapped(byte[] bytes) {
        if (bytes.length != 16) {
            return false;
        }
        for (int index = 0; index < 10; index++) {
            if (bytes[index] != 0) {
                return false;
            }
        }
        return (bytes[10] & 0xff) == 0xff && (bytes[11] & 0xff) == 0xff;
    }
}
