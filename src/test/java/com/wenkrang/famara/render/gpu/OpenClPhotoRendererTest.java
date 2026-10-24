package com.wenkrang.famara.render.gpu;

import com.wenkrang.famara.render.schedule.*;
import org.junit.jupiter.api.Test;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class OpenClPhotoRendererTest {
    static GpuConfiguration config(){return new GpuConfiguration(GpuConfiguration.Mode.OPENCL,"Quadro P620",384,1,64,128,5000,true,1.2);}
    static GpuDeviceProbe.Session session(AtomicInteger photos,CountDownLatch entered,CountDownLatch release) {
        return new GpuDeviceProbe.Session(){
            public GpuDevice device(){return null;}public GpuMemoryBudget.Snapshot memory(){return new GpuMemoryBudget(1024,1024).snapshot();}
            public GpuDeviceProbe.Smoke smoke(){return new GpuDeviceProbe.Smoke(new byte[0],0,0,0,0);}
            public GpuDeviceProbe.PhotoFrame photo(GpuScene scene){
                GpuPhotoSupport.require(scene);photos.incrementAndGet();if(entered!=null)entered.countDown();
                if(release!=null)try{assertTrue(release.await(3,TimeUnit.SECONDS));}catch(InterruptedException e){throw new CompletionException(e);}
                float[] rgb=new float[16384*3];java.util.Arrays.fill(rgb,1);return new GpuDeviceProbe.PhotoFrame(rgb,1,1,1,3,1,false,1024);
            }
            public void close(){}
        };
    }
    @Test void outputUsesReferenceSrgbAndIsDeliveredAfterStagingAndCpuReservationsEnd()throws Exception {
        var memory=new RenderMemoryBudget(536870912);var cpu=new CpuBudget(2000,100,System::nanoTime);var calls=new AtomicInteger();
        try(var probe=new GpuDeviceProbe(config(),cpu,memory,(c,h)->session(calls,null,null));var runner=new BudgetedWorkRunner(1,1_000_000,1_000_000,cpu);var renderer=new OpenClPhotoRenderer(probe,config(),memory,runner)) {
            var scene=GpuSceneSerializerTest.scene(GpuSceneSerializerTest.assets(2).generation(1),0,0);
            var result=renderer.submitAsync(renderer.prepare(scene,GpuSceneSerializerTest.camera(0,0),GpuSceneSerializerTest.settings()),()->false).toCompletableFuture().get(5,TimeUnit.SECONDS);
            assertEquals(1,calls.get());for(int pixel:result.rgb())assertEquals(0xffffff,pixel);assertEquals("OPENCL",result.metadata().get("backend"));assertTrue(Long.parseLong(result.metadata().get("renderCpuNanos"))>0);assertEquals(0,memory.snapshot().live());assertEquals(0,cpu.snapshot().reservedNanos());
        }
    }
    @Test void onePendingJobAndCancellationNeverPublishesPixelsOrFreesNativeInputEarly()throws Exception {
        var memory=new RenderMemoryBudget(536870912);var cpu=new CpuBudget(2000,100,System::nanoTime);var calls=new AtomicInteger();var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        try(var probe=new GpuDeviceProbe(config(),cpu,memory,(c,h)->session(calls,entered,release));var runner=new BudgetedWorkRunner(1,1_000_000,1_000_000,cpu);var renderer=new OpenClPhotoRenderer(probe,config(),memory,runner)) {
            var scene=GpuSceneSerializerTest.scene(GpuSceneSerializerTest.assets(2).generation(1),0,0);var prepared=renderer.prepare(scene,GpuSceneSerializerTest.camera(0,0),GpuSceneSerializerTest.settings());
            var result=renderer.submitAsync(prepared,()->false).toCompletableFuture();
            try {
                assertTrue(entered.await(2,TimeUnit.SECONDS));assertThrows(ExecutionException.class,()->renderer.submitAsync(prepared,()->false).toCompletableFuture().get());
                renderer.cancel(prepared.id());assertTrue(memory.snapshot().live()>0);assertFalse(result.isDone());
            }finally{release.countDown();}
            assertThrows(ExecutionException.class,()->result.get(3,TimeUnit.SECONDS));assertEquals(0,memory.snapshot().live());assertEquals(1,calls.get());
        }
    }
    @Test void transparentScenePassesTheFullShaderGateWhileMvpGateStillRejectsIt()throws Exception {
        var memory=new RenderMemoryBudget(536870912);var cpu=new CpuBudget(2000,100,System::nanoTime);var calls=new AtomicInteger();
        try(var probe=new GpuDeviceProbe(config(),cpu,memory,(c,h)->session(calls,null,null));var runner=new BudgetedWorkRunner(1,1_000_000,1_000_000,cpu);var renderer=new OpenClPhotoRenderer(probe,config(),memory,runner)) {
            var f=GpuSceneSerializerTest.assets(2);f.png("test:tile",2,2,0x80ff0000);var scene=GpuSceneSerializerTest.scene(f.generation(1),0,0);
            try(var packed=GpuSceneSerializer.serialize(scene,GpuSceneSerializerTest.camera(0,0),GpuSceneSerializerTest.settings(),config(),memory)) {
                assertEquals(BackendException.Code.UNSUPPORTED,assertThrows(BackendException.class,()->GpuMvpSupport.require(packed)).code());GpuPhotoSupport.require(packed);
            }
            var result=renderer.submitAsync(renderer.prepare(scene,GpuSceneSerializerTest.camera(0,0),GpuSceneSerializerTest.settings()),()->false).toCompletableFuture().get(5,TimeUnit.SECONDS);
            assertEquals("OPENCL",result.metadata().get("backend"));assertEquals(1,calls.get());assertEquals(0,memory.snapshot().live());
        }
    }
    @Test void repeatedDeviceErrorsOpenBoundedCircuitAndHealthyRetryClosesIt()throws Exception {
        var clock=new java.util.concurrent.atomic.AtomicLong();var fail=new java.util.concurrent.atomic.AtomicBoolean(true);var calls=new AtomicInteger();var host=new RenderMemoryBudget(536870912);var cpu=new CpuBudget(5000,100,System::nanoTime);
        try(var probe=new GpuDeviceProbe(config(),cpu,host,(c,h)->new GpuDeviceProbe.Session(){
            public GpuDevice device(){return null;}public GpuMemoryBudget.Snapshot memory(){return new GpuMemoryBudget(1024,1024).snapshot();}public GpuDeviceProbe.Smoke smoke(){return new GpuDeviceProbe.Smoke(new byte[0],0,0,0,0);}
            public GpuDeviceProbe.PhotoFrame photo(GpuScene scene){calls.incrementAndGet();if(fail.get())throw new BackendException(BackendException.Code.DEVICE_ERROR,"injected device error");float[] pixels=new float[16384*3];java.util.Arrays.fill(pixels,1);return new GpuDeviceProbe.PhotoFrame(pixels,1,1,1,3,1,false,1);}public void close(){}
        });var runner=new BudgetedWorkRunner(1,1_000_000,1_000_000,cpu);var backend=new OpenClPhotoRenderer(probe,config(),host,runner,clock::get)) {
            assertTrue(probe.initialization().toCompletableFuture().get(2,TimeUnit.SECONDS).available());var scene=GpuSceneSerializerTest.scene(GpuSceneSerializerTest.assets(2).generation(1),0,0);
            for(int i=0;i<3;i++)assertThrows(ExecutionException.class,()->backend.submitAsync(backend.prepare(scene,GpuSceneSerializerTest.camera(0,0),GpuSceneSerializerTest.settings()),()->false).toCompletableFuture().get(3,TimeUnit.SECONDS));
            assertFalse(backend.capabilities().available());assertEquals("GPU_CIRCUIT_OPEN",backend.capabilities().unavailableReason());assertEquals(3,calls.get());
            assertThrows(ExecutionException.class,()->backend.submitAsync(backend.prepare(scene,GpuSceneSerializerTest.camera(0,0),GpuSceneSerializerTest.settings()),()->false).toCompletableFuture().get());assertEquals(3,calls.get());assertEquals(0,host.snapshot().live());
            clock.addAndGet(5_000_000_001L);fail.set(false);assertTrue(backend.capabilities().available());backend.submitAsync(backend.prepare(scene,GpuSceneSerializerTest.camera(0,0),GpuSceneSerializerTest.settings()),()->false).toCompletableFuture().get(3,TimeUnit.SECONDS);assertEquals(4,calls.get());assertTrue(backend.capabilities().available());assertEquals(0,host.snapshot().live());
        }
    }
}
