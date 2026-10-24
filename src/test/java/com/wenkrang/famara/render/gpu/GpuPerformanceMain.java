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
import java.lang.management.ManagementFactory;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Full budgeted backend/pipeline timing, with 30 CPU/GPU cold/hot samples per frozen case. */
public final class GpuPerformanceMain {
    static final int SAMPLES=30;
    static SceneSnapshot terrain(AssetGeneration assets) {
        var b=new SceneSnapshot.Builder(assets,-16,32,1000,100000);for(int x=-9;x<=9;x++)for(int z=-9;z<=9;z++)for(int y=-1;y<2;y++)b.availableSection(x,y,z);
        for(int x=-6;x<=6;x++)for(int z=0;z<=12;z++)for(int y=0;y<(z==10?5:1);y++)b.put(x,y,z,RenderFixtures.vanilla("minecraft:tile",assets.id()),new SceneSnapshot.Lighting(15,0,0xffffff),0);return b.build();
    }
    static CpuPhotoRenderer.Settings settings(String name,int aa) {
        if(!name.equals("terrain128"))return GpuFeatureMain.settings(name,aa,aa==1?Texture.Filter.NEAREST:Texture.Filter.MIPMAP);
        return new CpuPhotoRenderer.Settings(128,aa,aa==1?Texture.Filter.NEAREST:Texture.Filter.MIPMAP,true,.2,.5,32,0x182838,0,new VoxelTracer.Limits(10000,1000,10000),Map.of());
    }
    static SceneSnapshot scene(String name,AssetGeneration assets,Camera camera,CpuPhotoRenderer.Settings settings){return name.equals("terrain128")?terrain(assets):GpuFeatureMain.scene(name,assets,camera,settings);}
    static Camera camera(String name,int aa){return name.equals("terrain128")?new Camera(new Vec3(.5,2.5,-2),Math.PI/2+.071,-.17,Camera.Projection.LEGACY_ANGULAR,1):new Camera(new Vec3(.5,.6,-3),Math.PI/2,0,aa==1?Camera.Projection.PERSPECTIVE:Camera.Projection.LEGACY_ANGULAR,Math.PI/3);}
    static Map<String,Object> percentiles(List<Long> input){var values=new ArrayList<>(input);Collections.sort(values);return Map.of("p50Ms",values.get(values.size()/2)/1e6,"p95Ms",values.get((int)Math.ceil(values.size()*.95)-1)/1e6,"meanMs",values.stream().mapToLong(Long::longValue).average().orElseThrow()/1e6);}
    public static void main(String[] args)throws Exception {
        var server=TestPaths.ROOT;var out=Path.of(args[0]).toAbsolutePath().normalize();if(!Path.of("").toAbsolutePath().normalize().equals(server) || !out.startsWith(server.resolve("diagnostics/famara-p7")) || Files.exists(out))throw new IllegalArgumentException("New test diagnostics/famara-p7 directory required");Files.createDirectories(out);
        var report=new LinkedHashMap<String,Object>();var groups=new ArrayList<Object>();var host=new RenderMemoryBudget(536870912);var cpu=new CpuBudget(2000,100,System::nanoTime);var gpuConfig=new GpuConfiguration(GpuConfiguration.Mode.OPENCL,"Quadro P620",384,1,64,128,10000,true,1.2);long initAt=System.nanoTime();var probe=new GpuDeviceProbe(gpuConfig,cpu,host);var palette=MapPaletteAdapter.snapshot();int mapId=0;
        try(var assets=new AssetRepository(2);var io=new BudgetedWorkRunner(8,1_000_000,1_000_000,cpu);var gpu=new OpenClPhotoRenderer(probe,gpuConfig,host,io)) {
            try {
                var status=probe.initialization().toCompletableFuture().get(15,TimeUnit.SECONDS);if(!status.available())throw new AssertionError(status.reason());report.put("device",status.device());report.put("initializationWallNanos",System.nanoTime()-initAt);
                for(String name:List.of("opaque","glass","cutout","water","night","ce-carrier","legacy-entity","lod","terrain128"))for(int aa:new int[]{1,4})for(String backend:List.of("CPU","OPENCL"))for(String cache:List.of("COLD","HOT")) {
                    String label=name+"-"+aa+"-"+backend+"-"+cache;var settings=settings(name,aa);var camera=camera(name,aa);var rows=new ArrayList<Map<String,Object>>();var store=new DiskPhotoStore(out.resolve("photos").resolve(label));
                    var config=backend.equals("CPU")?GpuConfiguration.disabled():gpuConfig;var limits=new CpuPhotoPipeline.Limits(2,2,1,128L*1024*1024,1_000_000,1_000_000,8_000_000,2000,100,1024,15_000_000_000L);
                    SceneSnapshot hot=null;long assetNanos=0,sceneNanos=0,setupCpu=0;
                    try(var pipeline=new CpuPhotoPipeline(limits,store,(file,photo,current)->CompletableFuture.completedFuture(null),cpu,config,gpu)) {
                        for(int i=cache.equals("HOT")?-3:0;i<SAMPLES;i++) {
                            if(hot==null || cache.equals("COLD")) {
                                long started=System.nanoTime(),threadCpu=ManagementFactory.getThreadMXBean().getCurrentThreadCpuTime();long ticket=assets.requestBuild();var generation=GpuFeatureMain.assets(ticket);if(!assets.publish(ticket,generation))throw new AssertionError("Cold generation not published");assetNanos=System.nanoTime()-started;started=System.nanoTime();hot=scene(name,generation,camera,settings);sceneNanos=System.nanoTime()-started;setupCpu=ManagementFactory.getThreadMXBean().getCurrentThreadCpuTime()-threadCpu;
                            }
                            var frozen=hot;int id=++mapId;long started=System.nanoTime();
                            var handle=pipeline.submit(UUID.randomUUID(),UUID.randomUUID(),id,32L*1024*1024,()->{
                                var lease=assets.acquire();var scope=new MemoryScope(host,"p7-photo-workspace",32L*1024*1024);try{scope.claim(4L*1024*1024+frozen.estimatedBytes());return new CpuPhotoPipeline.Prepared(frozen,camera,settings,palette,lease,Map.of("fixture",name),scope);}catch(Throwable failure){lease.close();scope.close();throw failure;}
                            });
                            var result=handle.result().toCompletableFuture().get(15,TimeUnit.SECONDS);long pipelineNanos=System.nanoTime()-started;var photo=store.loadCommitted(id).orElseThrow();
                            if(!photo.metadata().get("backend").equals(backend))throw new AssertionError("Benchmark backend silently fell back: "+photo.metadata());
                            if(host.snapshot().live()!=GpuPhotoMvpMain.cachedHostBytes(host) || pipeline.admissionSnapshot().admitted()!=0)throw new AssertionError("Benchmark staging/permit leak");
                            if(i<0)continue;
                            var reference=new CpuPhotoRenderer(frozen,camera,settings);var cursor=reference.cursor();reference.renderBatch(cursor,16384,Long.MAX_VALUE,System::nanoTime,()->false);int[] expected=cursor.copyRgb();if(!Arrays.equals(expected,photo.copyRgb()) || !Arrays.equals(palette.quantize(expected),photo.copyMap()))throw new AssertionError("Benchmark golden mismatch "+label);
                            if(backend.equals("OPENCL") && cache.equals("HOT") && (!photo.metadata().get("textureCacheHit").equals("true") || !photo.metadata().get("geometryCacheHit").equals("true")))throw new AssertionError("Hot cache re-uploaded frozen buffers");
                            if(backend.equals("OPENCL") && cache.equals("COLD") && (!photo.metadata().get("textureCacheHit").equals("false") || !photo.metadata().get("geometryCacheHit").equals("false")))throw new AssertionError("Cold generation incorrectly reused a cache");
                            var row=new LinkedHashMap<String,Object>();row.put("iteration",i);row.put("pipelineNanos",pipelineNanos);row.put("assetNanos",cache.equals("COLD")?assetNanos:0L);row.put("scenePreparationNanos",cache.equals("COLD")?sceneNanos:0L);row.put("setupThreadCpuNanos",cache.equals("COLD")?setupCpu:0L);row.put("pipelineThreadCpuNanos",result.cpuNanos());row.put("metadata",photo.metadata());row.put("hostLedger",host.snapshot());row.put("gpuLedger",probe.buffers());row.put("heapUsedBytes",ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed());rows.add(row);
                        }
                    }
                    var summary=new LinkedHashMap<String,Object>();summary.put("case",name);summary.put("samples",aa);summary.put("backend",backend);summary.put("cache",cache);summary.put("observations",rows.size());summary.put("pipeline",percentiles(rows.stream().map(r->(Long)r.get("pipelineNanos")).toList()));summary.put("render",percentiles(rows.stream().map(r->{@SuppressWarnings("unchecked")var meta=(Map<String,String>)r.get("metadata");return Long.parseLong(meta.get("renderWallNanos"));}).toList()));summary.put("rows",rows);groups.add(summary);Files.writeString(out.resolve(label+".json"),new GsonBuilder().setPrettyPrinting().create().toJson(summary)+"\n");Files.writeString(out.resolve("progress.json"),new GsonBuilder().setPrettyPrinting().create().toJson(Map.of("groups",groups.size(),"last",label))+"\n");System.out.println(label+" PASS "+summary.get("render"));
                }
                report.put("groups",groups);report.put("photoSamples",9*2*2*2*SAMPLES);report.put("status","PASS");report.put("scope","FROZEN_SCENE_FULL_PIPELINE_NOT_WORLD_CAPTURE_OR_NETWORK_TPS");report.put("coldDefinition","New asset generation and GPU texture/model/index caches per sample; OS/JIT caches warm; initialization measured separately");
            }catch(Throwable failure){report.put("status","FAIL");report.put("error",failure.toString());throw failure;}
            finally{probe.close();probe.whenClosed().toCompletableFuture().get(15,TimeUnit.SECONDS);report.put("hostAfterClose",host.snapshot());Files.writeString(out.resolve("report.json"),new GsonBuilder().setPrettyPrinting().create().toJson(report)+"\n");}
        }
    }
}
