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

package com.guillermomolina.protos.runtime;

import java.util.Arrays;
import java.util.Objects;

/**
 * Internal immutable network-order TCP endpoint.
 * Contains no guest values or host routing authority.
 */
public final class ProtosHostIpEndpoint {
    private final int version;
    private final byte[] octets;
    private final int port;

    public ProtosHostIpEndpoint(int version, byte[] octets, int port) {
        if (version != 4 && version != 6)
            throw new IllegalArgumentException("invalid IP version");
        Objects.requireNonNull(octets, "octets");
        if (octets.length != (version == 4 ? 4 : 16))
            throw new IllegalArgumentException("invalid IP octet width");
        if (port < 1 || port > 65535)
            throw new IllegalArgumentException("invalid concrete port");
        this.version = version;
        this.octets = octets.clone();
        this.port = port;
    }

    public int version() { return version; }
    public byte[] octets() { return octets.clone(); }
    public int port() { return port; }

    @Override
    public boolean equals(Object other) {
        return other instanceof ProtosHostIpEndpoint endpoint
                && version == endpoint.version
                && port == endpoint.port
                && Arrays.equals(octets, endpoint.octets);
    }

    @Override
    public int hashCode() {
        return 31 * (31 * version + Arrays.hashCode(octets)) + port;
    }
}
