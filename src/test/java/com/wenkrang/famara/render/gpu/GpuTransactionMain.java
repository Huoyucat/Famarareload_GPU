package com.wenkrang.famara.render.gpu;

import com.wenkrang.famara.TestPaths;
import com.google.gson.GsonBuilder;
import com.wenkrang.famara.render.asset.*;
import com.wenkrang.famara.render.kernel.*;
import com.wenkrang.famara.render.math.Vec3;
import com.wenkrang.famara.render.scene.*;
import com.wenkrang.famara.render.schedule.*;
import com.wenkrang.famara.render.output.*;
import com.wenkrang.famara.render.platform.MapPaletteAdapter;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Supplier;

/** Actual P620 -> original pipeline/store/ledger, restricted to the authorized test directory. */
public final class GpuTransactionMain {
    static <T> T checked(Callable<T> call){try{return call.call();}catch(Exception error){throw new CompletionException(error);}}
    static CpuPhotoPipeline.Limits limits(){return new CpuPhotoPipeline.Limits(2,2,1,128L*1024*1024,1_000_000,1_000_000,8_000_000,5000,100,2048,15_000_000_000L);}
    static SceneSnapshot scene(String name,AssetGeneration assets,Camera camera,CpuPhotoRenderer.Settings settings) {
        if(!name.equals("capacity-fallback"))return GpuFeatureMain.scene(name,assets,camera,settings);
        var b=new SceneSnapshot.Builder(assets,-16,32,100,20000);for(int x=-9;x<=9;x++)for(int z=-9;z<=9;z++)for(int y=-1;y<2;y++)b.availableSection(x,y,z);
        for(int z=0;z<65;z++)b.put(0,0,z,RenderFixtures.vanilla("minecraft:glass",assets.id()),new SceneSnapshot.Lighting(15,0,0xffffff),0);return b.build();
    }
    static CpuPhotoRenderer.Settings settings(String name,int aa) {
        if(!name.equals("capacity-fallback"))return GpuFeatureMain.settings(name,aa,aa==1?Texture.Filter.NEAREST:Texture.Filter.MIPMAP);
        return new CpuPhotoRenderer.Settings(80,1,Texture.Filter.NEAREST,true,.2,.5,32,0x182838,0,new VoxelTracer.Limits(10000,1000,10000),Map.of(ResourceKey.parse("test:glass"),CpuPhotoRenderer.AlphaMode.OPAQUE));
    }
    public static void main(String[] args)throws Exception {
        Path server=TestPaths.ROOT,out=Path.of(args[0]).toAbsolutePath().normalize();
        if(!Path.of("").toAbsolutePath().normalize().equals(server) || !out.startsWith(server.resolve("diagnostics/famara-p6")) || Files.exists(out))throw new IllegalArgumentException("New test diagnostics/famara-p6 directory required");
        Files.createDirectories(out);var report=new LinkedHashMap<String,Object>();var rows=new ArrayList<Object>();var host=new RenderMemoryBudget(536870912);var cpu=new CpuBudget(5000,100,System::nanoTime);var config=new GpuConfiguration(GpuConfiguration.Mode.OPENCL,"Quadro P620",384,1,64,128,10000,true,1.2);
        var device=new GpuDeviceProbe(config,cpu,host,out.resolve("gpu-owner.lock"));var captures=new AtomicInteger();var publishes=new AtomicInteger();var store=new DiskPhotoStore(out.resolve("photos"));
        try(var assets=new AssetRepository(2);var ledger=new CameraLedger(out.resolve("ledger"),new CameraLedger.Limits(100,100,1048576));var io=new BudgetedWorkRunner(8,1_000_000,1_000_000,cpu);var backend=new OpenClPhotoRenderer(device,config,host,io)) {
            try {
                var ready=device.initialization().toCompletableFuture().get(15,TimeUnit.SECONDS);if(!ready.available())throw new AssertionError(ready.reason());report.put("device",ready.device());try{GpuOwnerLock.acquire(out.resolve("gpu-owner.lock"));throw new AssertionError("Concurrent owner acquired active native session lock");}catch(BackendException expected){if(!expected.getMessage().equals("GPU_OWNER_BUSY"))throw expected;}report.put("ownerLockExclusionVerified",true);long ticket=assets.requestBuild();if(!assets.publish(ticket,GpuFeatureMain.assets()))throw new AssertionError("Fixture generation publication failed");var palette=MapPaletteAdapter.snapshot();
                CpuPhotoPipeline.Publisher publisher=(stored,photo,current)->{
                    if(!((CpuPhotoPipeline.CommitGate)current).beginSettlement())return CompletableFuture.failedFuture(new CancellationException("Settlement gate rejected"));publishes.incrementAndGet();UUID request=UUID.fromString(photo.metadata().get("request"));return io.submit(()->checked(()->{ledger.succeed(request);return (Void)null;}));
                };
                try(var pipeline=new CpuPhotoPipeline(limits(),store,publisher,cpu,config,backend)) {
                    int id=0;
                    for(String name:List.of("opaque","glass","cutout","water","night","ce-carrier","legacy-entity","lod","capacity-fallback"))for(int aa:name.equals("capacity-fallback")?new int[]{1}:new int[]{1,4}) {
                        int mapId=++id;UUID owner=UUID.randomUUID(),request=UUID.randomUUID(),cameraId=UUID.randomUUID();var camera=new Camera(new Vec3(.5,.6,-3),Math.PI/2,0,aa==1?Camera.Projection.PERSPECTIVE:Camera.Projection.LEGACY_ANGULAR,Math.PI/3);var settings=settings(name,aa);
                        io.submit(()->checked(()->{ledger.reserve(request,cameraId,owner,2,0);return null;})).toCompletableFuture().get(5,TimeUnit.SECONDS);
                        Supplier<CpuPhotoPipeline.Prepared> capture=()->{
                            captures.incrementAndGet();var lease=assets.acquire();var memory=new MemoryScope(host,"p6-photo-workspace",32L*1024*1024);
                            try {var frozen=scene(name,lease.generation(),camera,settings);memory.claim(4L*1024*1024+frozen.estimatedBytes());return new CpuPhotoPipeline.Prepared(frozen,camera,settings,palette,lease,Map.of("fixture",name),memory);}
                            catch(Throwable failure){lease.close();memory.close();throw failure;}
                        };
                        var prep=new CpuPhotoPipeline.Preparation(){CpuPhotoPipeline.Prepared prepared;public long minimumMemoryBytes(){return 32L*1024*1024;}public CpuPhotoPipeline.PreparationStep step(int operations,long deadline){prepared=capture.get();return CpuPhotoPipeline.PreparationStep.DONE;}public CpuPhotoPipeline.Prepared result(){return prepared;}};
                        var handle=pipeline.submitDeferred(owner,request,32L*1024*1024,prep);
                        // Bind after rendering has completed, exercising the GPU receipt -> deferred output wakeup.
                        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);while(handle.state().stage()!=PhotoJobState.Stage.ENCODING && !handle.state().stage().terminal() && System.nanoTime()<until)Thread.sleep(1);
                        if(handle.state().stage()!=PhotoJobState.Stage.ENCODING)throw new AssertionError("Render did not reach deferred output: "+handle.diagnostics());
                        io.submit(()->checked(()->ledger.bindOutput(request,mapId))).toCompletableFuture().get(5,TimeUnit.SECONDS);if(!handle.bindOutput(mapId))throw new AssertionError("Output binding rejected");
                        var result=handle.result().toCompletableFuture().get(10,TimeUnit.SECONDS);var photo=store.loadCommitted(mapId).orElseThrow();
                        try(var lease=assets.acquire()) {
                            var reference=new CpuPhotoRenderer(scene(name,lease.generation(),camera,settings),camera,settings);var cursor=reference.cursor();reference.renderBatch(cursor,16384,Long.MAX_VALUE,System::nanoTime,()->false);int[] expected=cursor.copyRgb();
                            if(!Arrays.equals(expected,photo.copyRgb()) || !Arrays.equals(palette.quantize(expected),photo.copyMap()))throw new AssertionError("Pipeline CPU golden mismatch: "+name+"-"+aa);
                        }
                        String expectedBackend=name.equals("capacity-fallback")?"CPU":"OPENCL";if(!photo.metadata().get("backend").equals(expectedBackend))throw new AssertionError("Wrong backend: "+photo.metadata());
                        if(ledger.snapshot().spent(cameraId)!=1 || ledger.snapshot().job(request).orElseThrow().status()!=CameraLedger.Status.SUCCEEDED)throw new AssertionError("Film ledger mismatch");
                        if(!photo.metadata().get("gpuFallbackCount").equals(name.equals("capacity-fallback")?"1":"0"))throw new AssertionError("Fallback count mismatch");
                        if(host.snapshot().live()!=GpuPhotoMvpMain.cachedHostBytes(host) || pipeline.admissionSnapshot().admitted()!=0)throw new AssertionError("Pipeline workspace/permit leaked");
                        rows.add(Map.of("case",name+"-"+aa,"mapId",mapId,"cpuNanos",result.cpuNanos(),"metadata",photo.metadata(),"filmSpent",ledger.snapshot().spent(cameraId),"status","PASS"));
                    }
                    if(captures.get()!=id || publishes.get()!=id || store.committedIds().size()!=id)throw new AssertionError("Duplicate capture/store/publish");report.put("photos",id);report.put("captures",captures.get());report.put("publishes",publishes.get());report.put("scope","OFFLINE_ACTUAL_GPU_PIPELINE_STORE_LEDGER; NOT_NETWORK_PLAYERS_OR_BUKKIT_MAP_INSTALLATION");report.put("status","PASS");report.put("buffersBeforeClose",device.buffers());
                }
            }catch(Throwable failure){report.put("status","FAIL");report.put("error",failure.toString());throw failure;}
            finally{device.close();device.whenClosed().toCompletableFuture().get(15,TimeUnit.SECONDS);try(var released=GpuOwnerLock.acquire(out.resolve("gpu-owner.lock"))){report.put("ownerLockReleaseVerified",true);}report.put("hostAfterClose",host.snapshot());report.put("cases",rows);Files.writeString(out.resolve("report.json"),new GsonBuilder().setPrettyPrinting().create().toJson(report)+"\n");System.out.println(out.resolve("report.json"));}
        }
    }
}
