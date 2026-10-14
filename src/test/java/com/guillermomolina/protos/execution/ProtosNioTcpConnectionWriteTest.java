/* APL-1.0 licensed work; see LICENSE.TXT. */
package com.guillermomolina.protos.execution;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.guillermomolina.protos.runtime.ProtosActivation;
import com.guillermomolina.protos.runtime.ProtosBytesValue;
import com.guillermomolina.protos.runtime.ProtosFutureValue;
import com.guillermomolina.protos.runtime.ProtosIntegerValue;
import com.guillermomolina.protos.runtime.ProtosNetworkCapabilityValue;
import com.guillermomolina.protos.runtime.ProtosObjectValue;
import com.guillermomolina.protos.runtime.ProtosPrelude;
import com.guillermomolina.protos.runtime.ProtosTcpConnectionValue;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.StandardProtocolFamily;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

final class ProtosNioTcpConnectionWriteTest {
    private static final Path CORE=Path.of("protos","lib","core");
    private static final Duration WAIT=Duration.ofSeconds(10);

    @Test
    void connectedWriteDeliversCapturedBytes() throws Exception {
        try (Fixture x=connected("protos-test-nio-write-immediate")) {
            byte[] payload={1,2,3,4,5};
            ProtosFutureValue write=write(x,payload);
            assertArrayEquals(payload,readExactly(x.peer,payload.length));
            awaitTerminal(write);
            assertEquals(ProtosFutureValue.State.RESOLVED,write.state());
            assertSame(x.connection,write.resolvedValue().orElseThrow());
        }
    }

    @Test
    void largeWriteRetainsOrderedProgressAcrossPostFirstEffectCancellation() throws Exception {
        try (Fixture x=connected("protos-test-nio-write-partial")) {
            byte[] payload=payload(1024*1024);
            ProtosFutureValue write=write(x,payload);
            byte[] received=new byte[payload.length];
            readIntoExactly(x.peer,received,0,1);
            write.cancelRequest();
            assertNotEquals(ProtosFutureValue.State.CANCELLED,write.state());
            readIntoExactly(x.peer,received,1,received.length-1);
            awaitTerminal(write);
            assertEquals(ProtosFutureValue.State.RESOLVED,write.state());
            assertArrayEquals(payload,received);
        }
    }

    @Test
    void readAndWriteProgressRemainIndependent() throws Exception {
        try (Fixture x=connected("protos-test-nio-write-duplex")) {
            ProtosFutureValue read=(ProtosFutureValue)ProtosInvocation.invokeMessage(
                    x.connection,"read",List.of(new ProtosIntegerValue(4)),x.activation);
            assertEquals(ProtosFutureValue.State.PENDING,read.state());
            byte[] outgoing=payload(1024*1024);
            ProtosFutureValue write=write(x,outgoing);
            writeFully(x.peer,new byte[]{7,8,9,10});
            awaitTerminal(read);
            assertEquals(List.of(7,8,9,10),resolvedBytes(read));
            byte[] received=new byte[outgoing.length];
            readIntoExactly(x.peer,received,0,received.length);
            awaitTerminal(write);
            assertEquals(ProtosFutureValue.State.RESOLVED,write.state());
            assertArrayEquals(outgoing,received);
        }
    }

    private static Fixture connected(String name) throws Exception {
        ProtosNioHostIoPoller poller=new ProtosNioHostIoPoller(name);
        ServerSocketChannel server=ServerSocketChannel.open(StandardProtocolFamily.INET);
        SocketChannel peer=null;
        try {
            Inet4Address loopback=(Inet4Address)InetAddress.getByAddress(new byte[]{127,0,0,1});
            server.bind(new InetSocketAddress(loopback,0)); server.configureBlocking(false);
            ProtosPrelude prelude=new ProtosCoreBootstrap().bootstrap(CORE);
            ProtosActivation activation=prelude.newModuleActivation();
            ProtosNioNetworkBackend backend=new ProtosNioNetworkBackend(
                    poller,bytes->{throw new AssertionError("IPv6 resolver used by IPv4");});
            ProtosNetworkCapabilityValue network=new ProtosNetworkCapabilityValue(prelude,backend);
            int port=((InetSocketAddress)server.getLocalAddress()).getPort();
            ProtosFutureValue connect=(ProtosFutureValue)ProtosInvocation.invokeMessage(
                    network,"connectTcp",List.of(endpoint(prelude,activation,port)),activation);
            awaitTerminal(connect);
            assertEquals(ProtosFutureValue.State.RESOLVED,connect.state());
            peer=awaitAccept(server); peer.configureBlocking(true);
            ProtosTcpConnectionValue connection=assertInstanceOf(
                    ProtosTcpConnectionValue.class,connect.resolvedValue().orElseThrow());
            return new Fixture(poller,server,peer,prelude,activation,connection);
        } catch(Throwable e) {
            if(peer!=null) try{peer.close();}catch(Exception ignored){}
            try{server.close();}catch(Exception ignored){}
            poller.close(); throw e;
        }
    }

    private static ProtosObjectValue endpoint(ProtosPrelude p,ProtosActivation a,int port) {
        Object af=p.ipAddressPrototypeForRuntime();
        ProtosObjectValue address=(ProtosObjectValue)ProtosInvocation.invoke(af,List.of(
                new ProtosIntegerValue(4),
                new ProtosIntegerValue(0x7f000001L)),a);
        Object ef=p.ipEndpointPrototypeForRuntime();
        return (ProtosObjectValue)ProtosInvocation.invoke(
                ef,List.of(address,new ProtosIntegerValue(port)),a);
    }

    private static ProtosFutureValue write(Fixture x,byte[] bytes) {
        ProtosBytesValue value=new ProtosBytesValue(x.prelude.bytesPrototypeForRuntime());
        for(byte b:bytes)value.indexedAdd(new ProtosIntegerValue(b&0xff));
        return (ProtosFutureValue)ProtosInvocation.invokeMessage(
                x.connection,"write",List.of(value),x.activation);
    }
    private static byte[] payload(int n) {
        byte[] b=new byte[n]; for(int i=0;i<n;i++)b[i]=(byte)(i*31+17); return b;
    }
    private static List<Integer> unsigned(byte[] b) {
        ArrayList<Integer> r=new ArrayList<>(b.length); for(byte v:b)r.add(v&0xff); return r;
    }
    private static List<Integer> resolvedBytes(ProtosFutureValue f) {
        ProtosBytesValue b=assertInstanceOf(ProtosBytesValue.class,f.resolvedValue().orElseThrow());
        ArrayList<Integer> r=new ArrayList<>();
        for(Object v:b.indexedSnapshot())r.add(Integer.parseInt(com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerDecimalText(v)));
        return r;
    }
    private static SocketChannel awaitAccept(ServerSocketChannel s)throws Exception {
        long d=System.nanoTime()+WAIT.toNanos();
        while(System.nanoTime()<d){SocketChannel c=s.accept();if(c!=null)return c;Thread.sleep(5);}
        throw new AssertionError("timed out waiting for accept");
    }
    private static byte[] readExactly(SocketChannel c,int n)throws Exception {
        byte[] b=new byte[n];readIntoExactly(c,b,0,n);return b;
    }
    private static void readIntoExactly(SocketChannel c,byte[] b,int o,int n)throws Exception {
        ByteBuffer x=ByteBuffer.wrap(b,o,n);while(x.hasRemaining()){if(c.read(x)<0)throw new AssertionError("unexpected EOF");}
    }
    private static void writeFully(SocketChannel c,byte[] b)throws Exception {
        ByteBuffer x=ByteBuffer.wrap(b);while(x.hasRemaining())c.write(x);
    }
    private static void awaitTerminal(ProtosFutureValue f)throws Exception {
        long d=System.nanoTime()+WAIT.toNanos();
        while(f.state()==ProtosFutureValue.State.PENDING&&System.nanoTime()<d)
            if (!f.domain().dispatchOne()) Thread.sleep(5);
        if(f.state()==ProtosFutureValue.State.PENDING)throw new AssertionError("timed out waiting for Future");
    }

    private static final class Fixture implements AutoCloseable {
        final ProtosNioHostIoPoller poller; final ServerSocketChannel server; final SocketChannel peer;
        final ProtosPrelude prelude; final ProtosActivation activation; final ProtosTcpConnectionValue connection;
        Fixture(ProtosNioHostIoPoller p,ServerSocketChannel s,SocketChannel q,ProtosPrelude pr,
                ProtosActivation a,ProtosTcpConnectionValue c){
            poller=p;server=s;peer=q;prelude=pr;activation=a;connection=c;
        }
        public void close()throws IOException{
            try{peer.close();}finally{try{server.close();}finally{poller.close();}}
        }
    }
}
