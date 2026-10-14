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

import com.guillermomolina.protos.runtime.ProtosNumericValueSupport;
import com.guillermomolina.protos.runtime.*;
import java.util.List;
import java.util.Objects;

/** D052 shared protocol bridge for TcpListener accept, observation and Closable lifecycle. */
public final class ProtosStandardTcpListenerProtocol {
    private ProtosStandardTcpListenerProtocol() {}
    public static void install(ProtosObjectValue prototype) {
        Objects.requireNonNull(prototype,"prototype");
        if(prototype.parent().orElse(null)!=ProtosObjectValue.rootObject()) throw new IllegalArgumentException("standard TcpListener protocol prototype must delegate directly to Object");
        if(!prototype.isOpen()||!prototype.localSlotsSnapshot().isEmpty()) throw new IllegalStateException("standard TcpListener protocol prototype must be fresh and open");
        prototype.createLocalSlot("accept",ProtosClosureValue.nativeClosure((activation,supplied)->requireAcceptingListener(activation,supplied,prototype).acceptForRuntime(activation)));
        prototype.createLocalSlot("localPort",ProtosClosureValue.nativeClosure((activation,supplied)->{
            ProtosTcpListenerValue listener=requireListener(activation,supplied,prototype);
            return ProtosNumericValueSupport.integer(listener.localPortForRuntime());
        }));
        prototype.createLocalSlot("close",ProtosClosureValue.nativeClosure((activation,supplied)->requireListener(activation,supplied,prototype).closeForRuntime(activation)));
    }

    public static ProtosTcpConnectionValue materializeAcceptedConnection(
            ProtosActivation activation,
            Object resourceState,
            ProtosHostIpEndpoint localDescriptor,
            ProtosHostIpEndpoint remoteDescriptor,
            ProtosTcpConnectionFlow.Backend connectionBackend) {
        Objects.requireNonNull(activation, "activation");
        ProtosPrelude prelude = activation.prelude().orElseThrow();
        if (localDescriptor.version() != remoteDescriptor.version()) {
            throw new IllegalArgumentException("accepted endpoints have different IP families");
        }
        ProtosObjectValue localEndpoint =
                ProtosStandardNetworkProtocol.materializeHostEndpoint(
                        prelude, localDescriptor);
        ProtosObjectValue remoteEndpoint =
                ProtosStandardNetworkProtocol.materializeHostEndpoint(
                        prelude, remoteDescriptor);
        return new ProtosTcpConnectionValue(
                prelude,
                Objects.requireNonNull(resourceState,"resourceState"),
                activation,
                Objects.requireNonNull(connectionBackend,"connectionBackend"),
                localEndpoint,
                remoteEndpoint);
    }

    private static ProtosTcpListenerValue requireAcceptingListener(ProtosActivation activation,List<?> supplied,ProtosObjectValue prototype) {
        ProtosTcpListenerValue listener=requireListener(activation,supplied,prototype);
        if(!listener.hasAcceptForRuntime()) throw invalid(activation);
        return listener;
    }
    private static ProtosTcpListenerValue requireListener(ProtosActivation activation,List<?> supplied,ProtosObjectValue prototype) {
        if(!supplied.isEmpty() || !(activation.receiver() instanceof ProtosTcpListenerValue listener) || listener.parent().orElse(null)!=prototype || !listener.hasProtocolFlowForRuntime()) throw invalid(activation);
        return listener;
    }
    private static ProtosSignalException invalid(ProtosActivation activation){ return new ProtosSignalException(ProtosCoreErrors.newError(activation)); }
}
