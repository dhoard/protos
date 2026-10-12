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

import static org.junit.jupiter.api.Assertions.*;
import com.guillermomolina.protos.runtime.*;
import java.nio.file.Path;import java.util.*;
import org.junit.jupiter.api.Test;
class ProtosParallelExecutionTest{
 private static ProtosPrelude core()throws Exception{return new ProtosCoreBootstrap().bootstrap(Path.of("protos","lib","core"));}
 private static Object evalHosted(ProtosHostedExecutionTestFixture h,String s){return h.evaluatePersistent("<parallel-test>",s);}
 private static void dispatchHosted(ProtosHostedExecutionTestFixture h,ProtosActorExecutionDomain d){h.callEntered(()->{d.dispatchUntilIdle();return null;});}
 @Test void closedPublicSurface()throws Exception{var p=core();assertFalse(p.bindings().hasLocalSlot("P"));assertFalse(p.arrayPrototype().hasLocalSlot("parallelEach"));assertTrue(ProtosObjectValue.rootObject().hasLocalSlot("parallel"));for(String n:List.of("parallelMap","parallelFilter","parallelFindIndex","parallelReduce","parallelSort"))assertFalse(p.arrayPrototype().hasLocalSlot(n),n+" is Standard Library policy (D160), not Core Array");}
 @Test void standardPublicationFreezesSharedPrototypesWithoutFreezingChildren()throws Exception{var p=core();assertTrue(ProtosObjectValue.rootObject().isFrozen());assertTrue(p.integerPrototype().isFrozen());ProtosObjectValue child=new ProtosObjectValue(p.integerPrototype());assertFalse(child.isFrozen());child.createLocalSlot("probe",ProtosNullValue.INSTANCE);assertTrue(child.hasLocalSlot("probe"));}
 @Test void executorBounded()throws Exception{core();assertTrue(ProtosParallelRuntime.configuredCarrierLimit()>=2);assertTrue(ProtosParallelRuntime.liveCarrierCountForTesting()<=ProtosParallelRuntime.configuredCarrierLimit());}
 @Test void parallelReturnsCallerDomainFuture()throws Exception{var p=core();try(var h=ProtosHostedExecutionTestFixture.open(p)){var a=h.activation();var d=a.executionDomain();var f=(ProtosFutureValue)evalHosted(h,"((x) => x).parallel(42)");assertSame(d,f.domain());while(f.isPending()){dispatchHosted(h,d);Thread.onSpinWait();}assertEquals(String.valueOf("42"), com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerDecimalText(f.resolvedValue().orElseThrow()));}}

 @Test void identityMapSnapshotCaptureIsIsolatedFromLaterSourceMutation()throws Exception{
  var p=core();
  try(var h=ProtosHostedExecutionTestFixture.open(p)){
   var d=h.activation().executionDomain();
   var f=assertInstanceOf(
       ProtosFutureValue.class,
       evalHosted(
           h,
           "m: IdentityMap()\n"
               + "m[1] = 10\n"
               + "((copy) => copy[1]).parallel(m)"));
   assertEquals(String.valueOf("20"), com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerDecimalText(evalHosted(h,"m[1] = 20")));
   while(f.isPending()){
    dispatchHosted(h,d);
    Thread.onSpinWait();
   }
   assertEquals(ProtosFutureValue.State.RESOLVED,f.state());
   assertEquals(String.valueOf("10"), com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerDecimalText(f.resolvedValue().orElseThrow()));
   dispatchHosted(h,d);
   assertEquals(0,d.liveTaskCount());
  }
 }


 @Test void ipDataTransferConformanceSourceRoundTripsThroughP()throws Exception{
  // D172: the fixture imports the canonical IP families from std:network.
  var p=new ProtosCoreBootstrap().bootstrap(
      Path.of("protos","lib","core"),
      new ProtosStandardLibraryModuleResolver(Path.of("protos","lib")));
  try(var h=ProtosHostedExecutionTestFixture.open(p)){
   var d=h.activation().executionDomain();
   var source=java.nio.file.Files.readString(
       Path.of("protos","tests","tooling","parallel-execution-ip-data-transfer.protos"),
       java.nio.charset.StandardCharsets.UTF_8);
   var f=assertInstanceOf(ProtosFutureValue.class,evalHosted(h,source));
   while(f.isPending()){dispatchHosted(h,d);Thread.onSpinWait();}
   assertEquals(ProtosFutureValue.State.RESOLVED,f.state());
   assertSame(ProtosBooleanValue.TRUE,f.resolvedValue().orElseThrow());
   dispatchHosted(h,d);
   assertEquals(0,d.liveTaskCount());
  }
 }

 /*
  * BUG015: the P outcome becomes ready after the producer's pending check but before suspend().
  * suspend() returns false and the producer stays RUNNING; it must consume the outcome rather than
  * return as though it had suspended.
  */
 @Test void readyBeforeSuspendCompletionIsConsumedWithoutLostWakeup()throws Exception{
  var p=core();
  try(var h=ProtosHostedExecutionTestFixture.open(p)){
   var d=h.activation().executionDomain();
   int[] observedPending={0};
   var f=h.callEntered(()->ProtosParallelRuntime.ownedFutureForTesting(h.activation(),(current,resolve)->{
    observedPending[0]++;
    assertEquals(ProtosTask.State.RUNNING,current.state());
    resolve.accept(new ProtosIntegerValue(42));
    assertEquals(ProtosTask.State.RUNNING,current.state());
   }));
   var producer=f.producerTask().orElseThrow();
   dispatchHosted(h,d);
   assertEquals(1,observedPending[0]);
   assertEquals(ProtosFutureValue.State.RESOLVED,f.state());
   assertEquals(String.valueOf("42"), com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerDecimalText(f.resolvedValue().orElseThrow()));
   assertEquals(ProtosTask.State.COMPLETED,producer.state());
   assertEquals(0,d.liveTaskCount());
  }
 }

 /*
  * BUG015: when cancellation wins the same window, suspend() also returns false but the producer
  * is RUNNABLE; the ready outcome must not be consumed and the Future is cancelled.
  */
 @Test void readyBeforeSuspendCancellationWinsWithoutConsumingOutcome()throws Exception{
  var p=core();
  try(var h=ProtosHostedExecutionTestFixture.open(p)){
   var d=h.activation().executionDomain();
   int[] observedPending={0};
   var f=h.callEntered(()->ProtosParallelRuntime.ownedFutureForTesting(h.activation(),(current,resolve)->{
    observedPending[0]++;
    assertTrue(current.requestCancellation());
    resolve.accept(new ProtosIntegerValue(42));
   }));
   var producer=f.producerTask().orElseThrow();
   dispatchHosted(h,d);
   assertEquals(1,observedPending[0]);
   assertEquals(ProtosFutureValue.State.CANCELLED,f.state());
   assertTrue(f.resolvedValue().isEmpty());
   assertEquals(ProtosTask.State.CANCELLED,producer.state());
   assertEquals(0,d.liveTaskCount());
  }
 }

 /*
  * BUG017: an unexpected host failure escaping P execution on a carrier must still settle the
  * Completion. Before the fix the carrier exited without an outcome and the Future stayed pending
  * forever; the bounded wait turns that hang into a failure.
  */
 @Test void hostExecutionFailureSettlesFutureAsGenericError()throws Exception{
  var p=core();
  try(var h=ProtosHostedExecutionTestFixture.open(p)){
   var d=h.activation().executionDomain();
   var f=h.callEntered(()->ProtosParallelRuntime.hostFailingParallelForTesting(
       h.activation(),new IllegalStateException("BUG017 injected host failure")));
   var producer=f.producerTask().orElseThrow();
   long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
   while(f.isPending()&&System.nanoTime()<deadline){dispatchHosted(h,d);Thread.onSpinWait();}
   assertEquals(ProtosFutureValue.State.FAILED,f.state(),"P Future must not remain pending after a host failure");
   var error=f.failedError().orElseThrow();
   assertSame(p.errorPrototype(),error.parent().orElseThrow());
   assertEquals(ProtosTask.State.FAILED,producer.state());
   dispatchHosted(h,d);
   assertEquals(0,d.liveTaskCount());
  }
 }
}
