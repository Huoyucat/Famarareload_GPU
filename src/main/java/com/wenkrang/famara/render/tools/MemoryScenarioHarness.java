package com.wenkrang.famara.render.tools;

import com.google.gson.GsonBuilder;
import com.wenkrang.famara.render.asset.*;
import com.wenkrang.famara.render.kernel.*;
import com.wenkrang.famara.render.math.Vec3;
import com.wenkrang.famara.render.scene.*;
import com.wenkrang.famara.render.schedule.*;
import jdk.jfr.Recording;
import java.lang.management.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/** headless受限堆工作集实测，不是服务器RSS上限或MSPT验收。JAR只当资源参数。 */
public final class MemoryScenarioHarness {
    public static void main(String[] args)throws Exception {
        if(args.length!=3)throw new IllegalArgumentException("参数：基础JAR CE包 新证据目录");
        Path output=Path.of(args[2]);Files.createDirectory(output);
        var recording=new Recording();recording.enable("jdk.GCHeapSummary");recording.enable("jdk.ObjectCountAfterGC");recording.enable("jdk.ObjectAllocationSample").withPeriod(Duration.ofMillis(20));recording.start();
        RenderMemoryBudget memory=new RenderMemoryBudget(536870912);var platform=memory.reserve("runtime-and-io",80L*1024*1024);
        var limits=new AssetLimits(200000,4194304,134217728,1048576,4096,20000,64,64,200000,64);CpuBudget cpu=new CpuBudget(1000,50,System::nanoTime);
        List<Map<String,Object>> cycles=new ArrayList<>();long peakObservedHeap=0;
        try(var repository=new AssetRepository(2,268435456);var loader=new BudgetedAssetLoader(repository,limits,new BudgetedAssetLoader.Limits(128,1000000,1000000,8000000,180000000000L),cpu,memory)) {
            var sources=List.of(new BudgetedAssetLoader.Source(Path.of(args[1]),ResourcePack.Role.CUSTOM,true),new BudgetedAssetLoader.Source(Path.of(args[0]),ResourcePack.Role.BASE,true));
            loader.reloadSources(sources,()->true).toCompletableFuture().get(180,TimeUnit.SECONDS);
            for(int cycle=0;cycle<3;cycle++) {
                long before=System.nanoTime();int instances;
                try(var lease=repository.acquire();var scope=new MemoryScope(memory,"stress-scene",192L*1024*1024)) {
                    var assets=lease.generation();var builder=new SceneSnapshot.Builder(assets,-16,64,20480,196608).memory(scope);
                    for(int x=-5;x<=12;x++)for(int z=-5;z<=8;z++)for(int y=-1;y<4;y++)builder.availableSection(x,y,z);
                    var visual=VisualBlockDescriptor.vanilla(CanonicalBlockState.parse("minecraft:stone"),assets.id());var light=new SceneSnapshot.Lighting(15,0,0xffffff);
                    for(int x=0;x<128;x++)for(int z=0;z<32;z++)for(int y=0;y<5;y++)builder.put(x,y,z,visual,light,0);
                    var cursor=builder.finish();while(!cursor.step(256,Long.MAX_VALUE,System::nanoTime)){}
                    SceneSnapshot scene=cursor.result();instances=scene.instances().size();if(instances!=20480)throw new IllegalStateException("实例场景不完整");
                    scope.claim(16L*1024*1024);
                    var camera=new Camera(new Vec3(.5,8,-6),Math.PI/2,-.25,Camera.Projection.LEGACY_ANGULAR,1);
                    var renderer=new CpuPhotoRenderer(scene,camera,new CpuPhotoRenderer.Settings(64,1,Texture.Filter.MIPMAP,true,.3,.5,32,0x556677,0,new VoxelTracer.Limits(10000,1000,10000),Map.of()));
                    var pixels=renderer.cursor();while(renderer.renderBatch(pixels,256,Long.MAX_VALUE,System::nanoTime,()->false).status()!=CpuPhotoRenderer.Status.DONE){}
                    int[] rgb=pixels.copyRgb();if(rgb.length!=16384)throw new IllegalStateException("图像未完成");
                    long heap=ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();peakObservedHeap=Math.max(peakObservedHeap,heap);
                    cycles.add(Map.of("cycle",cycle,"instances",instances,"sceneEstimatedBytes",scene.estimatedBytes(),"scopePeak",scope.peak(),"heapUsedAtScene",heap,"global",memory.snapshot(),"wallMs",(System.nanoTime()-before)/1e6));
                    // 活场景+旧代不能同时容纳另一个完整构建时明确拒绝，旧当前代继续有效。
                    boolean rejected=false;
                    try{loader.reloadSources(sources,()->true).toCompletableFuture().get(180,TimeUnit.SECONDS);}catch(ExecutionException error){rejected=true;}
                    if(!rejected)throw new IllegalStateException("重载不应突破共享预算");
                }
                System.gc();
                loader.reloadSources(sources,()->true).toCompletableFuture().get(180,TimeUnit.SECONDS);System.gc();
            }
        }finally{platform.close();System.gc();recording.stop();recording.dump(output.resolve("memory.jfr"));recording.close();}
        Map<String,Object> report=new LinkedHashMap<>();report.put("kind","OFFLINE_20480_INSTANCES_64_DISTANCE_XMX512_NOT_SERVER_RSS");report.put("java",System.getProperty("java.version"));report.put("jvmArguments",ManagementFactory.getRuntimeMXBean().getInputArguments());report.put("cycles",cycles);report.put("finalBudget",memory.snapshot());report.put("peakObservedHeapAtScenes",peakObservedHeap);report.put("heapAfterGC",ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed());
        Files.writeString(output.resolve("memory.json"),new GsonBuilder().setPrettyPrinting().create().toJson(report));
        if(memory.snapshot().live()!=0 || memory.snapshot().peak()>536870912)throw new IllegalStateException("预算未完全释放或超限");System.out.println(new GsonBuilder().setPrettyPrinting().create().toJson(report));
    }
}
