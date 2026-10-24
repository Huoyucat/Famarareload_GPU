package com.wenkrang.famara.render.gpu;

import com.wenkrang.famara.render.schedule.*;
import org.junit.jupiter.api.Test;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class GpuDeviceProbeTest {
    private GpuConfiguration enabled(long timeout){var d=GpuConfiguration.disabled();return new GpuConfiguration(GpuConfiguration.Mode.AUTO,d.preferredDevice(),384,1,64,128,timeout,true,1.2);}
    @Test void offNeverInvokesNativeFactory()throws Exception {
        var calls=new AtomicInteger();var probe=new GpuDeviceProbe(GpuConfiguration.disabled(),new CpuBudget(2000,100,System::nanoTime),new RenderMemoryBudget(536870912),(config,host)->{calls.incrementAndGet();throw new AssertionError("Native initialization in OFF mode");});
        assertFalse(probe.initialization().toCompletableFuture().get().available());assertEquals(0,calls.get());probe.close();probe.whenClosed().toCompletableFuture().get(1,TimeUnit.SECONDS);
    }
    @Test void missingDriverBecomesUnavailableWithoutThrowingIntoPluginEnable()throws Exception {
        try(var probe=new GpuDeviceProbe(enabled(1000),new CpuBudget(2000,100,System::nanoTime),new RenderMemoryBudget(536870912),(config,host)->{throw new UnsatisfiedLinkError("injected absent OpenCL ICD");})) {
            var result=probe.initialization().toCompletableFuture().get(2,TimeUnit.SECONDS);assertFalse(result.available());assertTrue(result.reason().contains("absent OpenCL ICD"));
            assertThrows(ExecutionException.class,()->probe.smoke().toCompletableFuture().get(1,TimeUnit.SECONDS));
        }
    }
    @Test void closeDuringNativeInitializationReturnsPromptlyAndReleasesOnItsOwner()throws Exception {
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var closes=new AtomicInteger();var owner=new AtomicReference<Thread>();
        var probe=new GpuDeviceProbe(enabled(5000),new CpuBudget(2000,100,System::nanoTime),new RenderMemoryBudget(536870912),(config,host)->{
            owner.set(Thread.currentThread());entered.countDown();try{assertTrue(release.await(3,TimeUnit.SECONDS));}catch(InterruptedException e){throw new CompletionException(e);}
            return new GpuDeviceProbe.Session(){public GpuDevice device(){return null;}public GpuMemoryBudget.Snapshot memory(){return new GpuMemoryBudget(1024,1024).snapshot();}
                public GpuDeviceProbe.Smoke smoke(){return new GpuDeviceProbe.Smoke(new byte[0],0,0,0,0);}public void close(){assertSame(owner.get(),Thread.currentThread());closes.incrementAndGet();}};
        });
        try {assertTrue(entered.await(2,TimeUnit.SECONDS));long start=System.nanoTime();probe.close();assertTrue(System.nanoTime()-start<TimeUnit.SECONDS.toNanos(1));assertFalse(probe.available());}
        finally{release.countDown();probe.close();}
        probe.whenClosed().toCompletableFuture().get(3,TimeUnit.SECONDS);assertEquals(1,closes.get());
    }
    @Test void initializationTimeoutReleasesLateNativeResultWithoutPublishingReady()throws Exception {
        var release=new CountDownLatch(1);var closes=new AtomicInteger();
        var probe=new GpuDeviceProbe(enabled(50),new CpuBudget(2000,100,System::nanoTime),new RenderMemoryBudget(536870912),(config,host)->{
            try{release.await(2,TimeUnit.SECONDS);}catch(InterruptedException e){throw new CompletionException(e);}
            return new GpuDeviceProbe.Session(){public GpuDevice device(){return null;}public GpuMemoryBudget.Snapshot memory(){return new GpuMemoryBudget(1024,1024).snapshot();}
                public GpuDeviceProbe.Smoke smoke(){return new GpuDeviceProbe.Smoke(new byte[0],0,0,0,0);}public void close(){closes.incrementAndGet();}};
        });
        try {var status=probe.initialization().toCompletableFuture().get(2,TimeUnit.SECONDS);assertFalse(status.available());assertTrue(status.reason().contains("TIMEOUT"));}
        finally{release.countDown();probe.close();}
        probe.whenClosed().toCompletableFuture().get(3,TimeUnit.SECONDS);assertEquals(1,closes.get());
    }
    @Test void cancellationPinsFrozenInputUntilNativeOwnerActuallyReturns()throws Exception {
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var owner=new AtomicReference<Thread>();var closes=new AtomicInteger();
        var memory=new RenderMemoryBudget(536870912);
        var scene=GpuSceneSerializer.serialize(GpuSceneSerializerTest.scene(GpuSceneSerializerTest.assets(2).generation(1),0,0),GpuSceneSerializerTest.camera(0,0),GpuSceneSerializerTest.settings(),enabled(5000),memory);
        var probe=new GpuDeviceProbe(enabled(5000),new CpuBudget(2000,100,System::nanoTime),memory,(config,host)->{
            owner.set(Thread.currentThread());
            return new GpuDeviceProbe.Session(){
                public GpuDevice device(){return null;}public GpuMemoryBudget.Snapshot memory(){return new GpuMemoryBudget(1024,1024).snapshot();}
                public GpuDeviceProbe.Smoke smoke(){return new GpuDeviceProbe.Smoke(new byte[0],0,0,0,0);}
                public GpuDeviceProbe.CandidateBatch candidates(GpuScene frozen,int[] xyz){
                    entered.countDown();try{assertTrue(release.await(3,TimeUnit.SECONDS));}catch(InterruptedException error){throw new CompletionException(error);}
                    assertEquals(1,frozen.ints(GpuScene.BufferKind.HEADER_INT).get(0));return new GpuDeviceProbe.CandidateBatch(new int[4],0);
                }
                public void close(){assertSame(owner.get(),Thread.currentThread());closes.incrementAndGet();}
            };
        });
        try {
            assertTrue(probe.initialization().toCompletableFuture().get(2,TimeUnit.SECONDS).available());
            var work=probe.probeCandidates(scene,new int[]{0,0,0}).toCompletableFuture();assertTrue(entered.await(2,TimeUnit.SECONDS));
            assertThrows(ExecutionException.class,()->probe.probeCandidates(scene,new int[]{0,0,0}).toCompletableFuture().get());
            scene.close();probe.close();assertTrue(work.isCompletedExceptionally());assertTrue(memory.snapshot().live()>0);assertFalse(probe.whenClosed().toCompletableFuture().isDone());
        }finally{release.countDown();scene.close();probe.close();}
        probe.whenClosed().toCompletableFuture().get(3,TimeUnit.SECONDS);assertEquals(0,memory.snapshot().live());assertEquals(1,closes.get());
    }
    @Test void completedOperationsRemoveWatchdogsInsteadOfRetainingEveryResultUntilTimeout()throws Exception {
        var cpu=new CpuBudget(5000,100,System::nanoTime);var memory=new RenderMemoryBudget(536870912);
        var probe=new GpuDeviceProbe(enabled(5000),cpu,memory,(config,host)->new GpuDeviceProbe.Session(){
            public GpuDevice device(){return null;}public GpuMemoryBudget.Snapshot memory(){return new GpuMemoryBudget(1024,1024).snapshot();}
            public GpuDeviceProbe.Smoke smoke(){return new GpuDeviceProbe.Smoke(new byte[1024],0,0,0,0);}public void close(){}
        });
        try {
            assertTrue(probe.initialization().toCompletableFuture().get(2,TimeUnit.SECONDS).available());
            for(int i=0;i<100;i++)probe.smoke().toCompletableFuture().get(2,TimeUnit.SECONDS);
            long until=System.nanoTime()+1_000_000_000L;while(probe.pendingTimeouts()!=0 && System.nanoTime()<until)Thread.sleep(1);
            assertEquals(0,probe.pendingTimeouts());assertEquals(100,probe.operationSequence());assertTrue(probe.lastOperationCpuNanos()>=0);
        }finally{probe.close();}
        probe.whenClosed().toCompletableFuture().get(2,TimeUnit.SECONDS);assertEquals(0,memory.snapshot().live());
    }
    @Test void retiringOwnerIsRetriedWithoutOpeningASecondNativeSession()throws Exception {
        var attempts=new AtomicInteger();var closes=new AtomicInteger();
        var probe=new GpuDeviceProbe(enabled(2000),new CpuBudget(5000,100,System::nanoTime),new RenderMemoryBudget(536870912),(config,host)->{
            if(attempts.incrementAndGet()<3)throw new BackendException(BackendException.Code.UNAVAILABLE,"GPU_OWNER_BUSY");
            return new GpuDeviceProbe.Session(){public GpuDevice device(){return null;}public GpuMemoryBudget.Snapshot memory(){return new GpuMemoryBudget(1024,1024).snapshot();}public GpuDeviceProbe.Smoke smoke(){return new GpuDeviceProbe.Smoke(new byte[0],0,0,0,0);}public void close(){closes.incrementAndGet();}};
        });
        try{assertTrue(probe.initialization().toCompletableFuture().get(2,TimeUnit.SECONDS).available());assertEquals(3,attempts.get());}finally{probe.close();}
        probe.whenClosed().toCompletableFuture().get(2,TimeUnit.SECONDS);assertEquals(1,closes.get());
    }
}
