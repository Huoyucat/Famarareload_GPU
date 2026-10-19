package com.wenkrang.famara.render.tools;

import com.google.gson.GsonBuilder;
import com.wenkrang.famara.render.asset.*;
import com.wenkrang.famara.render.kernel.*;
import com.wenkrang.famara.render.math.Vec3;
import com.wenkrang.famara.render.output.*;
import com.wenkrang.famara.render.scene.*;
import com.wenkrang.famara.render.schedule.*;
import jdk.jfr.Recording;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** 20480流体/含水宿主的受限堆实测；不是实际服务端IO或RSS硬限制证明。 */
public final class FluidMemoryHarness {
    public static void main(String[] args)throws Exception {
        if(args.length!=3)throw new IllegalArgumentException("参数：基础JAR CE包 新证据目录");Path output=Path.of(args[2]);Files.createDirectory(output);
        var recording=new Recording();recording.enable("jdk.GCHeapSummary");recording.enable("jdk.ObjectAllocationSample");recording.start();
        var global=new RenderMemoryBudget(536870912);var platform=global.reserve("runtime-and-io",80L*1024*1024);var cpu=new CpuBudget(2000,100,System::nanoTime);List<Object> reports=new ArrayList<>();
        var limits=new AssetLimits(200000,4194304,134217728,1048576,4096,20000,64,64,200000,64);
        try(var repository=new AssetRepository(2,268435456);var loader=new BudgetedAssetLoader(repository,limits,new BudgetedAssetLoader.Limits(128,1000000,1000000,8000000,180000000000L),cpu,global)) {
            loader.reloadSources(List.of(new BudgetedAssetLoader.Source(Path.of(args[1]),ResourcePack.Role.CUSTOM,true),new BudgetedAssetLoader.Source(Path.of(args[0]),ResourcePack.Role.BASE,true)),()->true).toCompletableFuture().get(180,TimeUnit.SECONDS);
            for(String name:List.of("water-internal","water-surface","waterlogged-slab")) {
                long started=System.nanoTime();
                try(var lease=repository.acquire();var scope=new MemoryScope(global,name,201326592)) {
                    var assets=lease.generation();var builder=new SceneSnapshot.Builder(assets,-16,64,20480,196608).memory(scope);var water=CanonicalBlockState.parse("minecraft:water[level=0]");var air=CanonicalBlockState.parse("minecraft:air");
                    for(int x=-5;x<=6;x++)for(int z=-5;z<=6;z++)for(int y=-1;y<4;y++)builder.availableSection(x,y,z);
                    for(int x=-1;x<=32;x++)for(int z=-1;z<=32;z++)for(int y=-1;y<=20;y++)builder.neighbor(x,y,z,name.equals("water-internal") || x>=0 && x<32 && z>=0 && z<32 && y>=0 && y<20?water:air,false);
                    String state=name.equals("waterlogged-slab")?"minecraft:oak_slab[type=bottom,waterlogged=true]":"minecraft:water[level=0]";var visual=VisualBlockDescriptor.vanilla(CanonicalBlockState.parse(state),assets.id());var light=new SceneSnapshot.Lighting(15,0,0xffffff,0x3f76e4);
                    for(int x=0;x<32;x++)for(int z=0;z<32;z++)for(int y=0;y<20;y++)builder.put(x,y,z,visual,light,0);
                    var cursor=builder.finish();while(!cursor.step(1024,Long.MAX_VALUE,System::nanoTime)){}
                    var scene=cursor.result();if(scene.instances().size()!=20480)throw new IllegalStateException("宿主被删除");for(int i=0;i<20480;i++)if(scene.instances().get(i).id()!=i)throw new IllegalStateException("实例ID不连续");
                    var camera=new Camera(new Vec3(15.5,23,-6),Math.PI/2,-.3,Camera.Projection.LEGACY_ANGULAR,1);var settings=new CpuPhotoRenderer.Settings(64,1,Texture.Filter.MIPMAP,true,.3,.5,64,0x556677,0,new VoxelTracer.Limits(10000,1000,10000),Map.of());
                    var palette=new MapPaletteSnapshot("synthetic",List.of(new MapPaletteSnapshot.Entry(4,0),new MapPaletteSnapshot.Entry(5,0xffffff)));int[] hash={0};var metadata=new java.util.concurrent.atomic.AtomicReference<Map<String,String>>(Map.of());
                    var policy=new CpuPhotoPipeline.Limits(2,1,1,536870912,1000000,1000000,8000000,2000,100,256,120000000000L);
                    try(var pipeline=new CpuPhotoPipeline(policy,new DiskPhotoStore(output.resolve(name)),(stored,photo,current)->{hash[0]=Arrays.hashCode(photo.copyRgb());metadata.set(photo.metadata());return CompletableFuture.completedFuture(null);},cpu)) {
                        var handle=pipeline.submit(UUID.randomUUID(),UUID.randomUUID(),10,201326592,()->new CpuPhotoPipeline.Prepared(scene,camera,settings,palette,lease,Map.of(),scope));handle.result().toCompletableFuture().get(120,TimeUnit.SECONDS);
                        reports.add(Map.of("case",name,"instances",scene.instances().size(),"fluid",cursor.progress(),"scopePeak",scope.peak(),"budget",global.snapshot(),"rgbHash",hash[0],"metadata",metadata.get(),"wallNanos",System.nanoTime()-started));
                    }
                }
                System.gc();
            }
        }finally{platform.close();System.gc();recording.stop();recording.dump(output.resolve("fluids.jfr"));recording.close();}
        var result=Map.of("kind","OFFLINE_20480_FLUIDS_XMX512_NOT_SERVER_RSS","cases",reports,"finalBudget",global.snapshot());Files.writeString(output.resolve("fluids.json"),new GsonBuilder().setPrettyPrinting().create().toJson(result));
        if(global.snapshot().live()!=0)throw new IllegalStateException("共享预约没有归零");System.out.println(new GsonBuilder().setPrettyPrinting().create().toJson(result));
    }
}
