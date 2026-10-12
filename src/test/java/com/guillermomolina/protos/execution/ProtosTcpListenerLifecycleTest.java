/* APL-1.0 licensed work; see LICENSE.TXT. */
package com.guillermomolina.protos.execution;

import static org.junit.jupiter.api.Assertions.*;

import com.guillermomolina.protos.runtime.*;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

final class ProtosTcpListenerLifecycleTest {
    private static final Path CORE=Path.of("protos","lib","core");

    @Test void localPortIsSynchronousSnapshotWithoutBackendEffect() throws Exception {
        Fixture x=fixture(43210); Object value=ProtosInvocation.invokeMessage(x.listener,"localPort",List.of(),x.activation);
        assertEquals(String.valueOf("43210"), com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerDecimalText(value));
        assertEquals(0,x.backend.closeStarts); assertTrue(x.listener.isOpen());
    }

    @Test void closeUsesOneSharedLifecycleAndDoesNotStructurallyCloseObject() throws Exception {
        Fixture x=fixture(51000); ProtosFutureValue first=assertInstanceOf(ProtosFutureValue.class,ProtosInvocation.invokeMessage(x.listener,"close",List.of(),x.activation));
        assertEquals(1,x.backend.closeStarts); assertEquals(ProtosFutureValue.State.PENDING,first.state()); assertTrue(x.listener.isOpen());
        x.backend.completion.succeeded(); assertEquals(ProtosFutureValue.State.RESOLVED,first.state()); assertSame(x.listener,first.resolvedValue().orElseThrow()); assertTrue(x.listener.isOpen());
        ProtosFutureValue second=assertInstanceOf(ProtosFutureValue.class,ProtosInvocation.invokeMessage(x.listener,"close",List.of(),x.activation));
        assertNotSame(first,second); assertEquals(ProtosFutureValue.State.RESOLVED,second.state()); assertSame(x.listener,second.resolvedValue().orElseThrow()); assertEquals(1,x.backend.closeStarts);
    }

    @Test void closeBackendFailureMapsToPortableIoError() throws Exception {
        Fixture x=fixture(52000); ProtosFutureValue future=assertInstanceOf(ProtosFutureValue.class,ProtosInvocation.invokeMessage(x.listener,"close",List.of(),x.activation));
        x.backend.completion.failed(); assertEquals(ProtosFutureValue.State.FAILED,future.state());
        assertSame(x.prelude.bindings().readLocalSlot("IOError").orElseThrow(),future.failedError().orElseThrow().parent().orElseThrow());
    }

    @Test void inheritedBehaviorCannotManufactureListenerAuthority() throws Exception {
        Fixture x=fixture(53000); ProtosObjectValue child=new ProtosObjectValue(x.listener);
        assertThrows(ProtosSignalException.class,()->ProtosInvocation.invokeMessage(child,"localPort",List.of(),x.activation));
        assertThrows(ProtosSignalException.class,()->ProtosInvocation.invokeMessage(child,"close",List.of(),x.activation));
        assertEquals(0,x.backend.closeStarts);
        ProtosTcpListenerValue inert=new ProtosTcpListenerValue(x.prelude,new Object());
        assertThrows(ProtosSignalException.class,()->ProtosInvocation.invokeMessage(inert,"localPort",List.of(),x.activation));
        assertThrows(ProtosSignalException.class,()->ProtosInvocation.invokeMessage(inert,"close",List.of(),x.activation));
    }

    @Test void localPortAndCloseRequireZeroArguments() throws Exception {
        Fixture x=fixture(54000); Object extra=new ProtosIntegerValue(1L);
        assertThrows(ProtosSignalException.class,()->ProtosInvocation.invokeMessage(x.listener,"localPort",List.of(extra),x.activation));
        assertThrows(ProtosSignalException.class,()->ProtosInvocation.invokeMessage(x.listener,"close",List.of(extra),x.activation));
        assertEquals(0,x.backend.closeStarts);
    }

    private static Fixture fixture(int port) throws Exception {
        ProtosPrelude p=new ProtosCoreBootstrap().bootstrap(CORE); ProtosActivation a=p.newModuleActivation(); RecordingBackend b=new RecordingBackend();
        ProtosTcpListenerValue l=new ProtosTcpListenerValue(p,new Object(),a,b,port); return new Fixture(p,a,l,b);
    }
    private record Fixture(ProtosPrelude prelude,ProtosActivation activation,ProtosTcpListenerValue listener,RecordingBackend backend) {}
    private static final class RecordingBackend implements ProtosTcpListenerFlow.Backend {
        int closeStarts; ProtosTcpListenerFlow.CloseCompletion completion;
        @Override public void close(ProtosTcpListenerFlow.CloseCompletion completion){ closeStarts++; this.completion=completion; }
    }
}
