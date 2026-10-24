package com.wenkrang.famara.render.gpu;

import com.wenkrang.famara.render.asset.*;
import com.wenkrang.famara.render.kernel.*;
import com.wenkrang.famara.render.math.Vec3;
import com.wenkrang.famara.render.scene.*;
import com.wenkrang.famara.render.schedule.*;
import com.wenkrang.famara.render.output.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class GpuTimeoutFallbackTest {
    @TempDir Path directory;
    @Test void nativeTimeoutCanFinishCpuTransactionBeforeLateOwnerReleasesFrozenInput()throws Exception {
        var host=new RenderMemoryBudget(536870912);var cpu=new CpuBudget(5000,100,System::nanoTime);var config=new GpuConfiguration(GpuConfiguration.Mode.OPENCL,"Quadro P620",384,1,64,128,150,true,1.2);
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var nativeReadAfterCpuSuccess=new AtomicBoolean();var closes=new AtomicInteger();
        var device=new GpuDeviceProbe(config,cpu,host,(c,h)->{
            Thread owner=Thread.currentThread();return new GpuDeviceProbe.Session(){
                public GpuDevice device(){return null;}public GpuMemoryBudget.Snapshot memory(){return new GpuMemoryBudget(1024,1024).snapshot();}
                public GpuDeviceProbe.Smoke smoke(){return new GpuDeviceProbe.Smoke(new byte[0],0,0,0,0);}
                public GpuDeviceProbe.PhotoFrame photo(GpuScene frozen){entered.countDown();try{assertTrue(release.await(5,TimeUnit.SECONDS));}catch(InterruptedException error){throw new CompletionException(error);}assertEquals(GpuScene.VERSION,frozen.ints(GpuScene.BufferKind.HEADER_INT).get(0));nativeReadAfterCpuSuccess.set(true);float[] blue=new float[16384*3];for(int i=2;i<blue.length;i+=3)blue[i]=1;return new GpuDeviceProbe.PhotoFrame(blue,1,1,1,3,1,false,1);}
                public void close(){assertSame(owner,Thread.currentThread());closes.incrementAndGet();}
            };
        });
        try(var assets=new AssetRepository(2);var ledger=new CameraLedger(directory.resolve("ledger"),new CameraLedger.Limits(10,10,1048576));var io=new BudgetedWorkRunner(8,1_000_000,1_000_000,cpu);var backend=new OpenClPhotoRenderer(device,config,host,io)) {
            assertTrue(device.initialization().toCompletableFuture().get(2,TimeUnit.SECONDS).available());var f=new RenderFixtures();f.png("test:white",1,1,0xffffffff);f.cube("test:cube","test:white");f.block("test:block","test:cube");long ticket=assets.requestBuild();assets.publish(ticket,f.generation(ticket));
            UUID request=UUID.randomUUID(),owner=UUID.randomUUID(),camera=UUID.randomUUID();ledger.reserve(request,camera,owner,2,0);ledger.bindOutput(request,1);var sourceLease=new AtomicReference<AssetRepository.Lease>();var captures=new AtomicInteger();var published=new AtomicInteger();var store=new DiskPhotoStore(directory.resolve("photos"));
            var limits=new CpuPhotoPipeline.Limits(2,2,1,67108864,1_000_000,1_000_000,8_000_000,5000,100,2048,5_000_000_000L);
            try(var pipeline=new CpuPhotoPipeline(limits,store,(file,photo,current)->{
                assertTrue(((CpuPhotoPipeline.CommitGate)current).beginSettlement());published.incrementAndGet();return io.submit(()->{try{ledger.succeed(request);return (Void)null;}catch(Exception error){throw new CompletionException(error);}});
            },cpu,config,backend)) {
                var handle=pipeline.submit(owner,request,1,32L*1024*1024,()->{
                    captures.incrementAndGet();var lease=assets.acquire();sourceLease.set(lease);var scope=new MemoryScope(host,"timeout-photo-source",32L*1024*1024);
                    try {
                        var b=new SceneSnapshot.Builder(lease.generation(),0,32,10,10000).memory(scope);for(int x=-1;x<=1;x++)for(int z=-1;z<=1;z++)for(int y=0;y<2;y++)b.availableSection(x,y,z);b.put(0,0,0,RenderFixtures.vanilla("test:block",ticket),new SceneSnapshot.Lighting(15,0,0xffffff),0);
                        return new CpuPhotoPipeline.Prepared(b.build(),new Camera(new Vec3(.5,.5,-2),Math.PI/2,0,Camera.Projection.LEGACY_ANGULAR,1),new CpuPhotoRenderer.Settings(8,1,Texture.Filter.NEAREST,false,0,.5,32,0x123456,0,new VoxelTracer.Limits(10000,1000,10000),Map.of()),new MapPaletteSnapshot("fixture",List.of(new MapPaletteSnapshot.Entry(4,0x123456),new MapPaletteSnapshot.Entry(5,0xffffff))),lease,Map.of(),scope);
                    }catch(Throwable error){lease.close();scope.close();throw error;}
                });
                try {
                    assertTrue(entered.await(2,TimeUnit.SECONDS));handle.result().toCompletableFuture().get(3,TimeUnit.SECONDS);var photo=store.loadCommitted(1).orElseThrow();assertEquals("CPU",photo.metadata().get("backend"));assertEquals("1",photo.metadata().get("gpuFallbackCount"));assertEquals("true",photo.metadata().get("gpuOwnerPending"));assertTrue(photo.metadata().get("fallbackReason").contains("TIMEOUT"));assertEquals(0xffffff,photo.copyRgb()[64*128+64]);assertEquals(1,ledger.snapshot().spent(camera));assertEquals(1,captures.get());assertEquals(1,published.get());assertThrows(IllegalStateException.class,()->sourceLease.get().generation());assertTrue(host.snapshot().live()>0);assertFalse(nativeReadAfterCpuSuccess.get());
                }finally{release.countDown();}
            }
        }finally{release.countDown();device.close();device.whenClosed().toCompletableFuture().get(3,TimeUnit.SECONDS);}
        assertTrue(nativeReadAfterCpuSuccess.get());assertEquals(1,closes.get());assertEquals(0,host.snapshot().live());
    }
}
