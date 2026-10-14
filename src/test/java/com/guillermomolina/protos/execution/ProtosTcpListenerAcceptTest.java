/* APL-1.0 licensed work; see LICENSE.TXT. */
package com.guillermomolina.protos.execution;

import static org.junit.jupiter.api.Assertions.*;
import com.guillermomolina.protos.runtime.*;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

final class ProtosTcpListenerAcceptTest {
    private static final Path CORE=Path.of("protos","lib","core");

    @Test void multipleAcceptsRemainIndependentAndMayCompleteOutOfOrder() throws Exception {
        Fixture x=fixture();
        ProtosFutureValue first=accept(x); ProtosFutureValue second=accept(x);
        assertEquals(2,x.backend.accepts.size());
        assertEquals(ProtosFutureValue.State.PENDING,first.state());
        assertEquals(ProtosFutureValue.State.PENDING,second.state());
        ProtosObjectValue local2=endpoint(x,0x7f000001L,51000), remote2=endpoint(x,0x08080808L,42002);
        ProtosObjectValue local1=endpoint(x,0x7f000001L,51000), remote1=endpoint(x,0x01010101L,42001);
        AtomicInteger release1=new AtomicInteger(), release2=new AtomicInteger();
        x.backend.accepts.get(1).completion.succeeded(new Object(),host(local2),host(remote2),new NoOpConnectionBackend(),release2::incrementAndGet);
        assertEquals(ProtosFutureValue.State.PENDING, second.state());
        assertTrue(second.domain().dispatchOne());
        x.backend.accepts.get(0).completion.succeeded(new Object(),host(local1),host(remote1),new NoOpConnectionBackend(),release1::incrementAndGet);
        assertEquals(ProtosFutureValue.State.PENDING, first.state());
        assertTrue(first.domain().dispatchOne());
        ProtosTcpConnectionValue c1=assertInstanceOf(ProtosTcpConnectionValue.class,first.resolvedValue().orElseThrow());
        ProtosTcpConnectionValue c2=assertInstanceOf(ProtosTcpConnectionValue.class,second.resolvedValue().orElseThrow());
        assertNotSame(c1,c2); assertEquals(0,release1.get()); assertEquals(0,release2.get());
        assertEndpointEquals(x,ProtosInvocation.invokeMessage(c1,"localEndpoint",List.of(),x.activation),local1);
        assertEndpointEquals(x,ProtosInvocation.invokeMessage(c1,"remoteEndpoint",List.of(),x.activation),remote1);
    }

    @Test void precommitCancellationAndDuplicateCompletionReleaseCustody() throws Exception {
        Fixture x=fixture(); ProtosFutureValue cancelled=accept(x); AcceptInvocation ci=x.backend.accepts.get(0);
        assertTrue(cancelled.cancelRequest()); assertEquals(ProtosFutureValue.State.CANCELLED,cancelled.state()); assertEquals(1,ci.cancels.get());
        AtomicInteger lateRelease=new AtomicInteger(); ci.completion.succeeded(new Object(),host(endpoint(x,0x7f000001L,51000)),host(endpoint(x,0x01010101L,43000)),new NoOpConnectionBackend(),lateRelease::incrementAndGet);
        assertEquals(1,lateRelease.get());

        ProtosFutureValue completed=accept(x); AcceptInvocation ok=x.backend.accepts.get(1); AtomicInteger heldRelease=new AtomicInteger();
        ok.completion.succeeded(new Object(),host(endpoint(x,0x7f000001L,51000)),host(endpoint(x,0x01010101L,43001)),new NoOpConnectionBackend(),heldRelease::incrementAndGet);
        assertEquals(ProtosFutureValue.State.PENDING, completed.state());
        assertTrue(completed.domain().dispatchOne());
        assertEquals(ProtosFutureValue.State.RESOLVED,completed.state()); assertEquals(0,heldRelease.get());
        AtomicInteger duplicateRelease=new AtomicInteger();
        ok.completion.succeeded(new Object(),host(endpoint(x,0x7f000001L,51000)),host(endpoint(x,0x01010101L,43002)),new NoOpConnectionBackend(),duplicateRelease::incrementAndGet);
        assertEquals(1,duplicateRelease.get());
    }

    @Test void closeCutsOverPendingAcceptAndLateSuccessIsReleased() throws Exception {
        Fixture x=fixture(); ProtosFutureValue accepted=accept(x); AcceptInvocation ai=x.backend.accepts.get(0);
        ProtosFutureValue close=future(x.listener,"close",List.of(),x.activation);
        assertFailedAs(accepted,x.prelude,"IOLifecycleError"); assertEquals(1,ai.cancels.get()); assertEquals(1,x.backend.closeStarts);
        AtomicInteger lateRelease=new AtomicInteger();
        ai.completion.succeeded(new Object(),host(endpoint(x,0x7f000001L,51000)),host(endpoint(x,0x08080808L,44000)),new NoOpConnectionBackend(),lateRelease::incrementAndGet);
        assertEquals(1,lateRelease.get()); assertEquals(ProtosFutureValue.State.PENDING,close.state());
        x.backend.closeCompletion.succeeded(); assertEquals(ProtosFutureValue.State.RESOLVED,close.state());
    }

    @Test void actorTerminationRacingCancellationRegistrationStillCancelsBackend() throws Exception {
        ProtosPrelude p=core(); ProtosActivation a=p.newModuleActivation(); AtomicInteger cancels=new AtomicInteger();
        ProtosTcpListenerFlow.Backend b=new ProtosTcpListenerFlow.Backend(){
            @Override public void close(ProtosTcpListenerFlow.CloseCompletion completion){ completion.succeeded(); }
            @Override public ProtosTcpListenerFlow.Cancellation accept(ProtosTcpListenerFlow.AcceptCompletion completion){ a.executionDomain().actorTerminated(); return cancels::incrementAndGet; }
        };
        ProtosTcpListenerValue listener=new ProtosTcpListenerValue(p,new Object(),a,b,51000,ProtosStandardTcpListenerProtocol::materializeAcceptedConnection);
        ProtosFutureValue f=future(listener,"accept",List.of(),a); assertEquals(ProtosFutureValue.State.CANCELLED,f.state()); assertEquals(1,cancels.get());
    }

    @Test void backendFailureExceptionAndInvalidDescriptorMapToIoErrorWithCustody() throws Exception {
        Fixture x=fixture(); ProtosFutureValue failed=accept(x); x.backend.accepts.get(0).completion.failed();
        assertEquals(ProtosFutureValue.State.PENDING, failed.state());
        assertTrue(failed.domain().dispatchOne());
        assertFailedAs(failed,x.prelude,"IOError");
        ProtosFutureValue malformed=accept(x); AtomicInteger release=new AtomicInteger();
        x.backend.accepts.get(1).completion.succeeded(new Object(),null,host(endpoint(x,0x01010101L,45000)),new NoOpConnectionBackend(),release::incrementAndGet);
        assertEquals(ProtosFutureValue.State.PENDING, malformed.state());
        assertTrue(malformed.domain().dispatchOne());
        assertFailedAs(malformed,x.prelude,"IOError"); assertEquals(1,release.get());

        ProtosTcpListenerFlow.Backend throwing=new ProtosTcpListenerFlow.Backend(){
            @Override public void close(ProtosTcpListenerFlow.CloseCompletion completion){ completion.succeeded(); }
            @Override public ProtosTcpListenerFlow.Cancellation accept(ProtosTcpListenerFlow.AcceptCompletion completion){ throw new IllegalStateException("backend boom"); }
        };
        ProtosTcpListenerValue listener=new ProtosTcpListenerValue(x.prelude,new Object(),x.activation,throwing,51000,ProtosStandardTcpListenerProtocol::materializeAcceptedConnection);
        assertFailedAs(future(listener,"accept",List.of(),x.activation),x.prelude,"IOError");
    }

    @Test void asynchronousAcceptCompletionRunsOnOwningActor() throws Exception {
        Fixture x = fixture();
        ProtosFutureValue future = accept(x);
        AcceptInvocation invocation = x.backend.accepts.get(0);
        AtomicInteger released = new AtomicInteger();

        ProtosHostIpEndpoint local =
                new ProtosHostIpEndpoint(4, new byte[] {127, 0, 0, 1}, 51000);
        ProtosHostIpEndpoint remote =
                new ProtosHostIpEndpoint(4, new byte[] {1, 1, 1, 1}, 43000);

        java.util.concurrent.CompletableFuture.runAsync(
                () -> invocation.completion.succeeded(
                        new Object(), local, remote,
                        new NoOpConnectionBackend(), released::incrementAndGet)).get();

        assertEquals(ProtosFutureValue.State.PENDING, future.state());
        assertTrue(future.domain().dispatchOne());
        assertEquals(ProtosFutureValue.State.RESOLVED, future.state());
        assertEquals(0, released.get());
    }

    @Test void terminationDropsUntransferredAcceptedConnectionOnce() throws Exception {
        Fixture x = fixture();
        ProtosFutureValue future = accept(x);
        AcceptInvocation invocation = x.backend.accepts.get(0);
        AtomicInteger released = new AtomicInteger();

        java.util.concurrent.CompletableFuture.runAsync(
                () -> invocation.completion.succeeded(
                        new Object(),
                        new ProtosHostIpEndpoint(4, new byte[] {127, 0, 0, 1}, 51000),
                        new ProtosHostIpEndpoint(4, new byte[] {1, 1, 1, 1}, 43000),
                        new NoOpConnectionBackend(), released::incrementAndGet)).get();

        assertEquals(ProtosFutureValue.State.PENDING, future.state());
        assertTrue(future.cancelRequest());
        assertEquals(ProtosFutureValue.State.PENDING, future.state());
        assertEquals(0, released.get());
        future.domain().actorTerminated();
        assertEquals(1, released.get());
        assertEquals(ProtosFutureValue.State.PENDING, future.state());
        assertFalse(future.domain().dispatchOne());
    }

    @Test void acceptRequiresActualAcceptEnabledFamilyAndZeroArguments() throws Exception {
        Fixture x=fixture(); ProtosObjectValue child=new ProtosObjectValue(x.listener);
        assertThrows(ProtosSignalException.class,()->ProtosInvocation.invokeMessage(child,"accept",List.of(),x.activation));
        assertThrows(ProtosSignalException.class,()->ProtosInvocation.invokeMessage(x.listener,"accept",List.of(new ProtosIntegerValue(1L)),x.activation));
        ProtosTcpListenerValue lifecycleOnly=new ProtosTcpListenerValue(x.prelude,new Object(),x.activation,x.backend,51000);
        assertThrows(ProtosSignalException.class,()->ProtosInvocation.invokeMessage(lifecycleOnly,"accept",List.of(),x.activation));
        assertTrue(x.backend.accepts.isEmpty());
    }

    private static Fixture fixture() throws Exception {
        ProtosPrelude p=core(); ProtosActivation a=p.newModuleActivation(); RecordingBackend b=new RecordingBackend();
        ProtosTcpListenerValue l=new ProtosTcpListenerValue(p,new Object(),a,b,51000,ProtosStandardTcpListenerProtocol::materializeAcceptedConnection);
        return new Fixture(p,a,l,b);
    }
    private static ProtosPrelude core() throws Exception { return new ProtosCoreBootstrap().bootstrap(CORE); }
    private static ProtosFutureValue accept(Fixture x){ return future(x.listener,"accept",List.of(),x.activation); }
    private static ProtosFutureValue future(Object receiver,String selector,List<?> args,ProtosActivation a){ return assertInstanceOf(ProtosFutureValue.class,ProtosInvocation.invokeMessage(receiver,selector,args,a)); }
    private static ProtosObjectValue endpoint(Fixture x,long bits,long port){
        Object af=x.prelude.ipAddressPrototypeForRuntime(); Object ef=x.prelude.ipEndpointPrototypeForRuntime();
        ProtosObjectValue addr=(ProtosObjectValue)ProtosInvocation.invoke(af,List.of(integer(4),new ProtosIntegerValue(bits)),x.activation);
        return (ProtosObjectValue)ProtosInvocation.invoke(ef,List.of(addr,integer(port)),x.activation);
    }
    private static ProtosHostIpEndpoint host(ProtosObjectValue endpoint) {
        ProtosObjectValue address = (ProtosObjectValue)
                endpoint.readLocalSlot("address").orElseThrow();
        int version = ProtosNumericValueSupport.exactInt(
                address.readLocalSlot("version").orElseThrow());
        int port = ProtosNumericValueSupport.exactInt(
                endpoint.readLocalSlot("port").orElseThrow());
        byte[] bytes = ProtosNumericValueSupport.unsignedBigEndianOrNull(
                address.readLocalSlot("bits").orElseThrow(),
                version == 4 ? 4 : 16);
        return new ProtosHostIpEndpoint(version, bytes, port);
    }

    private static ProtosIntegerValue integer(long v){ return new ProtosIntegerValue(v); }
    private static void assertEndpointEquals(Fixture x,Object actual,Object expected){ assertSame(ProtosBooleanValue.TRUE,ProtosInvocation.invokeMessage(actual,"==",List.of(expected),x.activation)); }
    private static void assertFailedAs(ProtosFutureValue f,ProtosPrelude p,String name){ assertEquals(ProtosFutureValue.State.FAILED,f.state()); assertSame(p.bindings().readLocalSlot(name).orElseThrow(),f.failedError().orElseThrow().parent().orElseThrow()); }

    private record Fixture(ProtosPrelude prelude,ProtosActivation activation,ProtosTcpListenerValue listener,RecordingBackend backend) {}
    private static final class RecordingBackend implements ProtosTcpListenerFlow.Backend {
        final List<AcceptInvocation> accepts=new ArrayList<>(); int closeStarts; ProtosTcpListenerFlow.CloseCompletion closeCompletion;
        @Override public void close(ProtosTcpListenerFlow.CloseCompletion completion){ closeStarts++; closeCompletion=completion; }
        @Override public ProtosTcpListenerFlow.Cancellation accept(ProtosTcpListenerFlow.AcceptCompletion completion){ AcceptInvocation i=new AcceptInvocation(completion); accepts.add(i); return i.cancels::incrementAndGet; }
    }
    private static final class AcceptInvocation { final ProtosTcpListenerFlow.AcceptCompletion completion; final AtomicInteger cancels=new AtomicInteger(); AcceptInvocation(ProtosTcpListenerFlow.AcceptCompletion c){completion=c;} }
    private static final class NoOpConnectionBackend implements ProtosTcpConnectionFlow.Backend {
        @Override public ProtosByteIoFlow.Cancellation read(int maxBytes,ProtosByteIoFlow.ReadCompletion completion){ return ()->{}; }
        @Override public ProtosByteIoFlow.Cancellation write(byte[] bytes,ProtosByteIoFlow.WriteCompletion completion){ return ()->{}; }
        @Override public ProtosByteIoFlow.Cancellation shutdownRead(ProtosByteIoFlow.ShutdownCompletion completion){ return ()->{}; }
        @Override public ProtosByteIoFlow.Cancellation shutdownWrite(ProtosByteIoFlow.ShutdownCompletion completion){ return ()->{}; }
        @Override public void close(ProtosByteIoFlow.ReceiverCompletion completion){ completion.succeeded(); }
    }
}
