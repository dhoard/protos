/* APL-1.0 licensed work; see LICENSE.TXT. */
package com.guillermomolina.protos.execution;

import static org.junit.jupiter.api.Assertions.*;
import com.guillermomolina.protos.runtime.*;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class ProtosStandardByteIoProtocolTest {
    private static ProtosPrelude core() throws Exception{return new ProtosCoreBootstrap().bootstrap(Path.of("protos","lib","core"));}
    private static ProtosIntegerValue i(long n){return new ProtosIntegerValue(n);}
    private static ProtosBytesValue bytes(ProtosObjectValue p,int...v){var b=new ProtosBytesValue(p);for(int x:v)b.indexedAdd(i(x));return b;}
    @Test void readIsFuturePartialOrderedAndEof()throws Exception{
        var p=core();var a=p.newModuleActivation();var bp=new ProtosObjectValue(ProtosObjectValue.rootObject());ProtosStandardBytesProtocol.install(bp);
        var r=new ProtosObjectValue(ProtosObjectValue.rootObject());var pending=new ArrayDeque<ProtosByteIoFlow.ReadCompletion>();
        ProtosStandardByteIoProtocol.install(r,bp,a,new BackendAdapter(){public ProtosByteIoFlow.Cancellation read(int n,ProtosByteIoFlow.ReadCompletion c){pending.add(c);return()->{};}});
        var f1=(ProtosFutureValue)ProtosInvocation.invokeMessage(r,"read",List.of(i(4)),a);var f2=(ProtosFutureValue)ProtosInvocation.invokeMessage(r,"read",List.of(i(4)),a);
        assertEquals(ProtosFutureValue.State.PENDING,f1.state());assertEquals(1,pending.size());pending.remove().data(new byte[]{1,2});
        assertEquals(String.valueOf(List.of(com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerDecimalText(i(1)),com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerDecimalText(i(2)))), String.valueOf(((ProtosBytesValue)f1.resolvedValue().orElseThrow()).indexedSnapshot().stream().map(x->com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerDecimalText(x)).toList()));
        assertEquals(1,pending.size());pending.remove().eof();assertSame(ProtosNullValue.INSTANCE,f2.resolvedValue().orElseThrow());
    }
    @Test void invalidReadAndWriteFailThroughFuture()throws Exception{
        var p=core();var a=p.newModuleActivation();var bp=new ProtosObjectValue(ProtosObjectValue.rootObject());ProtosStandardBytesProtocol.install(bp);var r=new ProtosObjectValue(ProtosObjectValue.rootObject());
        ProtosStandardByteIoProtocol.install(r,bp,a,new BackendAdapter(){});
        var fr=(ProtosFutureValue)ProtosInvocation.invokeMessage(r,"read",List.of(i(0)),a);var fw=(ProtosFutureValue)ProtosInvocation.invokeMessage(r,"write",List.of(i(1)),a);
        assertEquals(ProtosFutureValue.State.FAILED,fr.state());assertEquals(ProtosFutureValue.State.FAILED,fw.state());
    }
    @Test void writeSnapshotsAndSerializesAndResolvesReceiver()throws Exception{
        var p=core();var a=p.newModuleActivation();var bp=new ProtosObjectValue(ProtosObjectValue.rootObject());ProtosStandardBytesProtocol.install(bp);var r=new ProtosObjectValue(ProtosObjectValue.rootObject());
        var payloads=new ArrayList<byte[]>();var completions=new ArrayDeque<ProtosByteIoFlow.WriteCompletion>();
        ProtosStandardByteIoProtocol.install(r,bp,a,new BackendAdapter(){public ProtosByteIoFlow.Cancellation write(byte[] b,ProtosByteIoFlow.WriteCompletion c){payloads.add(b);completions.add(c);return()->{};}});
        var b=bytes(bp,7,8);var f1=(ProtosFutureValue)ProtosInvocation.invokeMessage(r,"write",List.of(b),a);b.indexedPut(0,i(9));var f2=(ProtosFutureValue)ProtosInvocation.invokeMessage(r,"write",List.of(bytes(bp,3)),a);
        assertArrayEquals(new byte[]{7,8},payloads.get(0));assertEquals(1,payloads.size());completions.remove().succeeded();assertSame(r,f1.resolvedValue().orElseThrow());assertEquals(2,payloads.size());completions.remove().succeeded();assertSame(r,f2.resolvedValue().orElseThrow());
    }
    @Test void cancellationBeforeReadCommitPreservesReturnedBytes()throws Exception{
        var p=core();var a=p.newModuleActivation();var bp=new ProtosObjectValue(ProtosObjectValue.rootObject());ProtosStandardBytesProtocol.install(bp);var r=new ProtosObjectValue(ProtosObjectValue.rootObject());var c=new AtomicReference<ProtosByteIoFlow.ReadCompletion>();
        ProtosStandardByteIoProtocol.install(r,bp,a,new BackendAdapter(){public ProtosByteIoFlow.Cancellation read(int n,ProtosByteIoFlow.ReadCompletion x){c.set(x);return()->{};}});
        var f=(ProtosFutureValue)ProtosInvocation.invokeMessage(r,"read",List.of(i(2)),a);assertTrue(f.cancelRequest());c.get().data(new byte[]{4,5});
        var next=(ProtosFutureValue)ProtosInvocation.invokeMessage(r,"read",List.of(i(2)),a);var got=(ProtosBytesValue)next.resolvedValue().orElseThrow();assertEquals(String.valueOf("4"), com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerDecimalText(got.indexedAt(0)));
    }
    private static class BackendAdapter implements ProtosByteIoFlow.Backend{
        public ProtosByteIoFlow.Cancellation read(int n,ProtosByteIoFlow.ReadCompletion c){return()->{};}
        public ProtosByteIoFlow.Cancellation write(byte[] b,ProtosByteIoFlow.WriteCompletion c){return()->{};}
    }
}
