/* APL-1.0 licensed work; see LICENSE.TXT. */
package com.guillermomolina.protos.execution;

import com.guillermomolina.protos.runtime.ProtosTestIntegers;
import static org.junit.jupiter.api.Assertions.*;
import com.guillermomolina.protos.runtime.*;
import java.math.BigInteger;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;

class ProtosStandardByteIoPositioningProtocolTest {
    private static ProtosPrelude core() throws Exception{return new ProtosCoreBootstrap().bootstrap(Path.of("protos","lib","core"));}
    private static ProtosIntegerValue i(long n){return new ProtosIntegerValue(n);}
    private static ProtosBytesValue bytes(ProtosObjectValue p,int...v){var b=new ProtosBytesValue(p);for(int x:v)b.indexedAdd(i(x));return b;}

    @Test void extendedSurfaceReturnsFuturesAndValidatesArguments()throws Exception{
        var p=core();var a=p.newModuleActivation();var bp=new ProtosObjectValue(ProtosObjectValue.rootObject());ProtosStandardBytesProtocol.install(bp);
        var r=new ProtosObjectValue(ProtosObjectValue.rootObject());var b=new MemoryBackend();
        ProtosStandardByteIoProtocol.installExtended(r,bp,a,b);
        for(String m:List.of("flush","position","seekToEnd","size")){
            var f=(ProtosFutureValue)ProtosInvocation.invokeMessage(r,m,List.of(),a);assertNotNull(f);
        }
        var bad=(ProtosFutureValue)ProtosInvocation.invokeMessage(r,"seek",List.of(i(-1)),a);
        assertEquals(ProtosFutureValue.State.FAILED,bad.state());
        var badTruncate=(ProtosFutureValue)ProtosInvocation.invokeMessage(r,"truncate",List.of(i(-1)),a);
        assertEquals(ProtosFutureValue.State.FAILED,badTruncate.state());
    }

    @Test void writeFlushAndSizeShareOneOrderingDomain()throws Exception{
        var p=core();var a=p.newModuleActivation();var bp=new ProtosObjectValue(ProtosObjectValue.rootObject());ProtosStandardBytesProtocol.install(bp);
        var r=new ProtosObjectValue(ProtosObjectValue.rootObject());var b=new MemoryBackend();b.holdWrite=true;
        ProtosStandardByteIoProtocol.installExtended(r,bp,a,b);
        var write=(ProtosFutureValue)ProtosInvocation.invokeMessage(r,"write",List.of(bytes(bp,1,2,3)),a);
        var flush=(ProtosFutureValue)ProtosInvocation.invokeMessage(r,"flush",List.of(),a);
        var size=(ProtosFutureValue)ProtosInvocation.invokeMessage(r,"size",List.of(),a);
        assertEquals(ProtosFutureValue.State.PENDING,write.state());
        assertEquals(0,b.flushCalls);assertEquals(0,b.sizeCalls);
        b.completeHeldWrite();
        assertSame(r,write.resolvedValue().orElseThrow());
        assertEquals(1,b.flushCalls);assertSame(r,flush.resolvedValue().orElseThrow());
        assertEquals(BigInteger.valueOf(3),ProtosTestIntegers.exact(size.resolvedValue().orElseThrow()));
    }

    @Test void seekReadWriteAndPositionObserveLogicalOrder()throws Exception{
        var p=core();var a=p.newModuleActivation();var bp=new ProtosObjectValue(ProtosObjectValue.rootObject());ProtosStandardBytesProtocol.install(bp);
        var r=new ProtosObjectValue(ProtosObjectValue.rootObject());var b=new MemoryBackend(new byte[]{10,11,12,13});
        ProtosStandardByteIoProtocol.installExtended(r,bp,a,b);
        assertEquals(BigInteger.valueOf(2),value(ProtosInvocation.invokeMessage(r,"seek",List.of(i(2)),a)));
        var read=(ProtosFutureValue)ProtosInvocation.invokeMessage(r,"read",List.of(i(1)),a);
        assertEquals(12,ProtosTestIntegers.exact(((ProtosBytesValue)read.resolvedValue().orElseThrow()).indexedAt(0)).intValue());
        assertEquals(BigInteger.valueOf(3),value(ProtosInvocation.invokeMessage(r,"position",List.of(),a)));
        ProtosInvocation.invokeMessage(r,"write",List.of(bytes(bp,99)),a);
        assertEquals(BigInteger.valueOf(4),value(ProtosInvocation.invokeMessage(r,"position",List.of(),a)));
    }

    @Test void truncateShrinksDoesNotExtendAndPreservesPosition()throws Exception{
        var p=core();var a=p.newModuleActivation();var bp=new ProtosObjectValue(ProtosObjectValue.rootObject());ProtosStandardBytesProtocol.install(bp);
        var r=new ProtosObjectValue(ProtosObjectValue.rootObject());var b=new MemoryBackend(new byte[]{1,2,3,4});
        ProtosStandardByteIoProtocol.installExtended(r,bp,a,b);
        ProtosInvocation.invokeMessage(r,"seek",List.of(i(4)),a);
        var t=(ProtosFutureValue)ProtosInvocation.invokeMessage(r,"truncate",List.of(i(2)),a);assertSame(r,t.resolvedValue().orElseThrow());
        assertEquals(BigInteger.valueOf(2),value(ProtosInvocation.invokeMessage(r,"size",List.of(),a)));
        assertEquals(BigInteger.valueOf(4),value(ProtosInvocation.invokeMessage(r,"position",List.of(),a)));
        ProtosInvocation.invokeMessage(r,"truncate",List.of(i(9)),a);
        assertEquals(BigInteger.valueOf(2),value(ProtosInvocation.invokeMessage(r,"size",List.of(),a)));
    }

    @Test void cancelledQueuedSeekHasNoEffect()throws Exception{
        var p=core();var a=p.newModuleActivation();var bp=new ProtosObjectValue(ProtosObjectValue.rootObject());ProtosStandardBytesProtocol.install(bp);
        var r=new ProtosObjectValue(ProtosObjectValue.rootObject());var b=new MemoryBackend();b.holdWrite=true;
        ProtosStandardByteIoProtocol.installExtended(r,bp,a,b);
        ProtosInvocation.invokeMessage(r,"write",List.of(bytes(bp,1)),a);
        var seek=(ProtosFutureValue)ProtosInvocation.invokeMessage(r,"seek",List.of(i(20)),a);
        assertTrue(seek.cancelRequest());b.completeHeldWrite();
        assertEquals(ProtosFutureValue.State.CANCELLED,seek.state());
        assertEquals(BigInteger.ONE,value(ProtosInvocation.invokeMessage(r,"position",List.of(),a)));
    }

    // I091: ProtosFileFlow keeps exact guest positions and hands its backend only host-representable ones.
    private static final BigInteger TWO_POW_63=BigInteger.ONE.shiftLeft(63);

    private record FileFixture(ProtosPrelude prelude,ProtosActivation activation,ProtosObjectValue bytesPrototype,RecordingFile resource,ProtosFileFlow flow){}

    private static FileFixture file()throws Exception{
        ProtosPrelude prelude=core();
        ProtosActivation activation=prelude.newModuleActivation();
        ProtosObjectValue bytesPrototype=new ProtosObjectValue(ProtosObjectValue.rootObject());
        ProtosStandardBytesProtocol.install(bytesPrototype);
        RecordingFile resource=new RecordingFile();
        ProtosFileFlow flow=new ProtosFileFlow(new ProtosObjectValue(ProtosObjectValue.rootObject()),bytesPrototype,activation,resource,
                new ProtosFileFlow.Capabilities(true,true,true,true,true,false));
        return new FileFixture(prelude,activation,bytesPrototype,resource,flow);
    }

    private static Object resolved(ProtosFutureValue f){
        assertEquals(ProtosFutureValue.State.RESOLVED,f.state());
        return f.resolvedValue().orElseThrow();
    }

    private static BigInteger position(FileFixture x){return ProtosTestIntegers.exact(resolved(x.flow().position(x.activation())));}

    @Test void filePositionsStayExactAcrossTheSignedLongBoundary()throws Exception{
        FileFixture x=file();
        assertEquals(BigInteger.valueOf(Long.MAX_VALUE),ProtosTestIntegers.exact(resolved(x.flow().seek(x.activation(),i(Long.MAX_VALUE)))));
        Object crossed=resolved(x.flow().seekBy(x.activation(),i(1)));
        assertEquals(TWO_POW_63,ProtosTestIntegers.exact(crossed));
        assertSame(x.prelude().bigIntegerPrototype(),((ProtosObjectValue)crossed).parent().orElseThrow());
        assertEquals(TWO_POW_63,position(x));
        assertEquals(BigInteger.valueOf(Long.MAX_VALUE),ProtosTestIntegers.exact(resolved(x.flow().seekBy(x.activation(),i(-1)))));
        assertEquals(TWO_POW_63.shiftLeft(1),ProtosTestIntegers.exact(resolved(x.flow().seek(x.activation(),ProtosTestIntegers.integer(TWO_POW_63.shiftLeft(1),x.prelude())))));
        assertEquals(TWO_POW_63,ProtosTestIntegers.exact(resolved(x.flow().seekBy(x.activation(),ProtosTestIntegers.integer(TWO_POW_63.negate(),x.prelude())))));
        // A relative seek below zero fails and leaves the exact position; an absolute negative seek is invalid.
        assertEquals(ProtosFutureValue.State.FAILED,x.flow().seekBy(x.activation(),ProtosTestIntegers.integer(TWO_POW_63.negate().subtract(BigInteger.ONE),x.prelude())).state());
        assertEquals(TWO_POW_63,position(x));
        assertEquals(ProtosFutureValue.State.FAILED,x.flow().seek(x.activation(),i(-1)).state());
        assertEquals(TWO_POW_63,position(x));
    }

    @Test void positionsBeyondTheBackendRangeNeverReachIt()throws Exception{
        FileFixture x=file();
        resolved(x.flow().seek(x.activation(),ProtosTestIntegers.integer(TWO_POW_63,x.prelude())));
        assertEquals(ProtosFutureValue.State.FAILED,x.flow().read(x.activation(),i(1)).state());
        assertEquals(ProtosFutureValue.State.FAILED,x.flow().write(x.activation(),bytes(x.bytesPrototype(),1)).state());
        assertTrue(x.resource().reads.isEmpty());
        assertTrue(x.resource().writes.isEmpty());
        assertEquals(TWO_POW_63,position(x));
    }

    @Test void partialReadAndWriteAdvanceExactlyPastSignedLong()throws Exception{
        FileFixture x=file();
        resolved(x.flow().seek(x.activation(),i(Long.MAX_VALUE-1)));
        ProtosFutureValue write=x.flow().write(x.activation(),bytes(x.bytesPrototype(),1,2,3));
        assertEquals(Long.MAX_VALUE-1,x.resource().writes.remove().longValue());
        ProtosFileFlow.WriteCompletion written=x.resource().writeCompletions.remove();
        assertTrue(written.commitFirstContribution());
        written.failed(2);
        assertEquals(ProtosFutureValue.State.FAILED,write.state());
        assertEquals(TWO_POW_63,position(x));

        resolved(x.flow().seek(x.activation(),i(Long.MAX_VALUE-1)));
        ProtosFutureValue read=x.flow().read(x.activation(),i(4));
        assertEquals(Long.MAX_VALUE-1,x.resource().reads.remove().longValue());
        x.resource().readCompletions.remove().data(new byte[]{9,8});
        assertEquals(2,((ProtosBytesValue)resolved(read)).indexedSize());
        assertEquals(TWO_POW_63,position(x));
    }

    @Test void truncateHandsTheBackendOnlyAnEquivalentHostSize()throws Exception{
        FileFixture x=file();
        resolved(x.flow().seek(x.activation(),i(7)));
        ProtosFutureValue small=x.flow().truncate(x.activation(),i(5));
        ProtosFutureValue huge=x.flow().truncate(x.activation(),ProtosTestIntegers.integer(TWO_POW_63.shiftLeft(4),x.prelude()));
        ProtosFutureValue boundary=x.flow().truncate(x.activation(),i(Long.MAX_VALUE));
        for(int k=0;k<3;k++){
            ProtosFileFlow.ChangeCompletion change=x.resource().truncateCompletions.remove();
            assertTrue(change.commitChange());
            change.succeeded();
        }
        // Any size beyond the signed-64 range is at least every host size, exactly like Long.MAX_VALUE: no change.
        assertEquals(List.of(5L,Long.MAX_VALUE,Long.MAX_VALUE),x.resource().truncates);
        assertEquals(ProtosFutureValue.State.RESOLVED,small.state());
        assertEquals(ProtosFutureValue.State.RESOLVED,huge.state());
        assertEquals(ProtosFutureValue.State.RESOLVED,boundary.state());
        assertEquals(BigInteger.valueOf(7),position(x));
        assertEquals(ProtosFutureValue.State.FAILED,x.flow().truncate(x.activation(),i(-1)).state());
    }

    @Test void cancellationBeforeCommitLeavesTheExactPosition()throws Exception{
        FileFixture x=file();
        resolved(x.flow().seek(x.activation(),i(Long.MAX_VALUE)));
        ProtosFutureValue read=x.flow().read(x.activation(),i(1));
        x.activation().executionDomain().actorTerminated();
        assertEquals(ProtosFutureValue.State.CANCELLED,read.state());
        assertEquals(1,x.resource().cancellations);
        assertEquals(BigInteger.valueOf(Long.MAX_VALUE),position(x));
    }

    private static final class RecordingFile implements ProtosFileFlow.ReadableResource,ProtosFileFlow.WritableResource,
            ProtosFileFlow.SeekableResource,ProtosFileFlow.SizedResource,ProtosFileFlow.TruncatableResource{
        final ArrayDeque<Long> reads=new ArrayDeque<>();
        final ArrayDeque<ProtosFileFlow.ReadCompletion> readCompletions=new ArrayDeque<>();
        final ArrayDeque<Long> writes=new ArrayDeque<>();
        final ArrayDeque<ProtosFileFlow.WriteCompletion> writeCompletions=new ArrayDeque<>();
        final List<Long> truncates=new ArrayList<>();
        final ArrayDeque<ProtosFileFlow.ChangeCompletion> truncateCompletions=new ArrayDeque<>();
        int cancellations;
        public ProtosFileFlow.Cancellation readAt(long p,int n,ProtosFileFlow.ReadCompletion c){reads.add(p);readCompletions.add(c);return ()->cancellations++;}
        public ProtosFileFlow.Cancellation writeAt(long p,byte[] b,ProtosFileFlow.WriteCompletion c){writes.add(p);writeCompletions.add(c);return ()->cancellations++;}
        public ProtosFileFlow.Cancellation endPosition(ProtosFileFlow.IntegerCompletion c){c.succeeded(0);return ()->{};}
        public ProtosFileFlow.Cancellation size(ProtosFileFlow.IntegerCompletion c){c.succeeded(0);return ()->{};}
        public ProtosFileFlow.Cancellation truncate(long size,ProtosFileFlow.ChangeCompletion c){truncates.add(size);truncateCompletions.add(c);return ()->cancellations++;}
        public void close(ProtosFileFlow.CloseCompletion c){c.succeeded();}
    }

    private static BigInteger value(Object x){
        var f=(ProtosFutureValue)x;return ProtosTestIntegers.exact(f.resolvedValue().orElseThrow());
    }

    private static final class MemoryBackend implements ProtosByteIoFlow.ExtendedBackend{
        byte[]data;int position;boolean holdWrite;byte[]held;ProtosByteIoFlow.WriteCompletion heldCompletion;
        int flushCalls,sizeCalls;
        MemoryBackend(){this(new byte[0]);}
        MemoryBackend(byte[]d){data=d.clone();}
        public ProtosByteIoFlow.Cancellation read(int max,ProtosByteIoFlow.ReadCompletion c){
            if(position>=data.length){c.eof();return()->{};}
            int n=Math.min(max,data.length-position);byte[]out=Arrays.copyOfRange(data,position,position+n);position+=n;c.data(out);return()->{};
        }
        public ProtosByteIoFlow.Cancellation write(byte[]b,ProtosByteIoFlow.WriteCompletion c){
            if(holdWrite){held=b.clone();heldCompletion=c;return()->{};}writeNow(b);c.succeeded();return()->{};
        }
        void completeHeldWrite(){var b=held;var c=heldCompletion;held=null;heldCompletion=null;holdWrite=false;writeNow(b);c.succeeded();}
        private void writeNow(byte[]b){
            int end=position+b.length;if(end>data.length)data=Arrays.copyOf(data,end);
            System.arraycopy(b,0,data,position,b.length);position=end;
        }
        public ProtosByteIoFlow.Cancellation flush(ProtosByteIoFlow.ReceiverCompletion c){flushCalls++;c.succeeded();return()->{};}
        public ProtosByteIoFlow.Cancellation position(ProtosByteIoFlow.IntegerCompletion c){c.succeeded(new ProtosIntegerValue(position));return()->{};}
        public ProtosByteIoFlow.Cancellation seek(Object p,ProtosByteIoFlow.IntegerCompletion c){position=ProtosNumericValueSupport.exactInt(p);c.succeeded(p);return()->{};}
        public ProtosByteIoFlow.Cancellation seekBy(Object o,ProtosByteIoFlow.IntegerCompletion c){
            ProtosIntegerValue p=new ProtosIntegerValue(Math.addExact(position,((ProtosIntegerValue)o).longValue()));if(p.longValue()<0){c.failed();return()->{};}position=ProtosNumericValueSupport.exactInt(p);c.succeeded(p);return()->{};
        }
        public ProtosByteIoFlow.Cancellation seekToEnd(ProtosByteIoFlow.IntegerCompletion c){position=data.length;c.succeeded(new ProtosIntegerValue(position));return()->{};}
        public ProtosByteIoFlow.Cancellation size(ProtosByteIoFlow.IntegerCompletion c){sizeCalls++;c.succeeded(new ProtosIntegerValue(data.length));return()->{};}
        public ProtosByteIoFlow.Cancellation truncate(Object n,ProtosByteIoFlow.ReceiverCompletion c){
            int x=ProtosNumericValueSupport.exactInt(n);if(x<data.length)data=Arrays.copyOf(data,x);c.succeeded();return()->{};
        }
    }
}
