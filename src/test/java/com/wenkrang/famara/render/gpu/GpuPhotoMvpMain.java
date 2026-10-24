package com.wenkrang.famara.render.gpu;

import com.wenkrang.famara.TestPaths;
import com.google.gson.GsonBuilder;
import com.wenkrang.famara.render.asset.*;
import com.wenkrang.famara.render.kernel.*;
import com.wenkrang.famara.render.math.*;
import com.wenkrang.famara.render.scene.*;
import com.wenkrang.famara.render.schedule.*;
import com.wenkrang.famara.render.output.*;
import com.wenkrang.famara.render.platform.MapPaletteAdapter;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;

/** Offline MVP photographs, real device, original CPU and original map palette. */
public final class GpuPhotoMvpMain {
    static SceneSnapshot scene(AssetGeneration assets,int x) {
        var b=new SceneSnapshot.Builder(assets,-16,32,2000,1000000);
        for(int cx=(x>>4)-2;cx<=(x>>4)+2;cx++)for(int cz=-2;cz<=2;cz++)for(int cy=-1;cy<2;cy++)b.availableSection(cx,cy,cz);
        for(int dx=-6;dx<=6;dx++)for(int dz=0;dz<=12;dz++)for(int y=0;y<(dz==10?5:1);y++)b.put(x+dx,y,dz,RenderFixtures.vanilla("test:block",assets.id()),new SceneSnapshot.Lighting(12,3,0x8ac475),0);
        return b.build();
    }
    static long cachedHostBytes(RenderMemoryBudget memory){long bytes=memory.snapshot().domains().getOrDefault("gpu-cache-metadata",0L);if(bytes!=0 && bytes!=1024*1024)throw new AssertionError("Unexpected persistent cache metadata reservation");return bytes;}
    static int[] rgb(float[] values){int[] result=new int[16384];for(int i=0;i<result.length;i++)result[i]=Texture.srgb(values[i*3])<<16|Texture.srgb(values[i*3+1])<<8|Texture.srgb(values[i*3+2]);return result;}
    static void save(Path path,int[] pixels)throws Exception {var img=new BufferedImage(128,128,BufferedImage.TYPE_INT_RGB);img.setRGB(0,0,128,128,pixels,0,128);ImageIO.write(img,"png",path.toFile());}
    public static void main(String[] args)throws Exception {
        Path server=TestPaths.ROOT,out=Path.of(args[0]).toAbsolutePath().normalize();
        if(!Path.of("").toAbsolutePath().normalize().equals(server) || !out.startsWith(server.resolve("diagnostics/famara-p4")) || Files.exists(out))throw new IllegalArgumentException("New test diagnostics/famara-p4 directory required");
        Files.createDirectories(out);var report=new LinkedHashMap<String,Object>();var samples=new ArrayList<Object>();var memory=new RenderMemoryBudget(536870912);var config=new GpuConfiguration(GpuConfiguration.Mode.OPENCL,"Quadro P620",384,1,64,128,10000,true,1.2);var cpu=new CpuBudget(2000,100,System::nanoTime);var probe=new GpuDeviceProbe(config,cpu,memory);
        try {
            var status=probe.initialization().toCompletableFuture().get(15,TimeUnit.SECONDS);if(!status.available())throw new AssertionError(status.reason());report.put("device",status.device());
            var f=new RenderFixtures();f.png("test:tile",16,16,0xffc48c30,0xff3c9d5a,0xff4366b3,0xffdddddd);f.cube("test:cube","test:tile");f.block("test:block","test:cube");var assets=f.generation(1);var palette=MapPaletteAdapter.snapshot();
            int maxChannel=0,totalDifferent=0,totalMapDifferent=0;
            for(int x:new int[]{-17,4_100_000})for(var projection:Camera.Projection.values())for(var filter:Texture.Filter.values())for(int aa:new int[]{1,4}) {
                var scene=scene(assets,x);var camera=new Camera(new Vec3(x+.5,2.5,-2),Math.PI/2+.071,-.17,projection,1);
                var settings=new CpuPhotoRenderer.Settings(16,aa,filter,true,.3,.5,32,0x123456,0,new VoxelTracer.Limits(10000,1000,10000),Map.of());
                long cpuStart=System.nanoTime();var reference=new CpuPhotoRenderer(scene,camera,settings);var cursor=reference.cursor();while(reference.renderBatch(cursor,16384,Long.MAX_VALUE,System::nanoTime,()->false).status()!=CpuPhotoRenderer.Status.DONE){}int[] expected=cursor.copyRgb();long cpuNanos=System.nanoTime()-cpuStart;
                long serializeStart=System.nanoTime();
                try(var packed=GpuSceneSerializer.serialize(scene,camera,settings,config,memory)) {
                    long serialize=System.nanoTime()-serializeStart;GpuMvpSupport.require(packed);
                    for(int repeat=0;repeat<3;repeat++) {
                        var frame=probe.renderPhoto(packed).toCompletableFuture().get(15,TimeUnit.SECONDS);int[] actual=rgb(frame.linear());if(repeat>0 && !frame.geometryCacheHit())throw new AssertionError("Identical frozen model/index buffers were uploaded again");int differences=0,max=0,mapDifferences=0;var a=palette.quantize(actual);var e=palette.quantize(expected);
                        for(int i=0;i<actual.length;i++){if(actual[i]!=expected[i])differences++;if(a[i]!=e[i])mapDifferences++;for(int shift:new int[]{0,8,16})max=Math.max(max,Math.abs((actual[i]>>shift&255)-(expected[i]>>shift&255)));}
                        maxChannel=Math.max(maxChannel,max);totalDifferent+=differences;totalMapDifferent+=mapDifferences;
                        var row=new LinkedHashMap<String,Object>();row.put("baseX",x);row.put("projection",projection);row.put("filter",filter);row.put("samples",aa);row.put("repeat",repeat);row.put("cpuRenderNanos",cpuNanos);row.put("serializeNanos",serialize);row.put("uploadNanos",frame.uploadNanos());row.put("kernelNanos",frame.kernelNanos());row.put("readbackNanos",frame.readbackNanos());row.put("gpuWallNanos",frame.wallNanos());row.put("gpuThreadCpuNanos",frame.threadCpuNanos());row.put("textureCacheHit",frame.textureCacheHit());row.put("geometryCacheHit",frame.geometryCacheHit());row.put("rgbDifferentPixels",differences);row.put("mapDifferentPixels",mapDifferences);row.put("maxChannelError",max);samples.add(row);
                        if(repeat==0){String label=x+"-"+projection+"-"+filter+"-"+aa;save(out.resolve(label+"-cpu.png"),expected);save(out.resolve(label+"-gpu.png"),actual);Files.write(out.resolve(label+"-cpu.map"),e);Files.write(out.resolve(label+"-gpu.map"),a);}
                    }
                }
                if(memory.snapshot().live()!=cachedHostBytes(memory))throw new AssertionError("Photo staging leaked");
            }
            report.put("maxChannelError",maxChannel);report.put("rgbDifferentPixels",totalDifferent);report.put("mapDifferentPixels",totalMapDifferent);report.put("samples",samples);report.put("buffersBeforeClose",probe.buffers());
            if(maxChannel!=0 || totalMapDifferent!=0)throw new AssertionError("MVP requires exact RGB/map goldens; max channel="+maxChannel+", changed RGB="+totalDifferent+", map="+totalMapDifferent);
            var repeatScene=scene(assets,-17);var repeatCamera=new Camera(new Vec3(-16.5,2.5,-2),Math.PI/2+.071,-.17,Camera.Projection.LEGACY_ANGULAR,1);
            var repeatSettings=new CpuPhotoRenderer.Settings(16,1,Texture.Filter.NEAREST,true,.3,.5,32,0x123456,0,new VoxelTracer.Limits(10000,1000,10000),Map.of());
            try(var packed=GpuSceneSerializer.serialize(repeatScene,repeatCamera,repeatSettings,config,memory)) {
                long expectedHost=memory.snapshot().live(),retained=-1;int[] expected=null;
                for(int i=0;i<500;i++) {
                    var frame=probe.renderPhoto(packed).toCompletableFuture().get(15,TimeUnit.SECONDS);int[] actual=rgb(frame.linear());
                    if(expected==null)expected=actual;else if(!Arrays.equals(actual,expected))throw new AssertionError("Repeat photo changed");
                    if(retained==-1)retained=probe.buffers().live();else if(retained!=probe.buffers().live())throw new AssertionError("VRAM accumulated");
                    if(memory.snapshot().live()!=expectedHost)throw new AssertionError("Repeat staging accumulated");
                }
                report.put("stableRepeatPhotos",500);report.put("retainedGpuBytes",retained);
            }
            var updated=new RenderFixtures();updated.png("test:tile",16,16,0xff3366cc);updated.cube("test:cube","test:tile");updated.block("test:block","test:cube");var newAssets=updated.generation(2);var updatedScene=scene(newAssets,-17);
            var updatedReference=new CpuPhotoRenderer(updatedScene,repeatCamera,repeatSettings);var updatedCursor=updatedReference.cursor();updatedReference.renderBatch(updatedCursor,16384,Long.MAX_VALUE,System::nanoTime,()->false);
            try(var packed=GpuSceneSerializer.serialize(updatedScene,repeatCamera,repeatSettings,config,memory)) {
                var first=probe.renderPhoto(packed).toCompletableFuture().get(15,TimeUnit.SECONDS);if(first.textureCacheHit() || first.geometryCacheHit() || !Arrays.equals(rgb(first.linear()),updatedCursor.copyRgb()))throw new AssertionError("Generation update reused stale GPU textures");
                var hot=probe.renderPhoto(packed).toCompletableFuture().get(15,TimeUnit.SECONDS);if(!hot.textureCacheHit() || !hot.geometryCacheHit())throw new AssertionError("Updated atlas not cached");report.put("generationInvalidationVerified",true);
            }
            try(var runner=new BudgetedWorkRunner(1,1_000_000,1_000_000,cpu);var renderer=new OpenClPhotoRenderer(probe,config,memory,runner)) {
                var scene=scene(assets,-17);var camera=new Camera(new Vec3(-16.5,2.5,-2),Math.PI/2+.071,-.17,Camera.Projection.LEGACY_ANGULAR,1);
                var settings=new CpuPhotoRenderer.Settings(16,1,Texture.Filter.NEAREST,true,.3,.5,32,0x123456,0,new VoxelTracer.Limits(10000,1000,10000),Map.of());
                var result=renderer.submitAsync(renderer.prepare(scene,camera,settings),()->false).toCompletableFuture().get(15,TimeUnit.SECONDS);
                var pixels=result.rgb();var photo=new ImmutablePhoto(pixels,palette.quantize(pixels),result.metadata());var store=new DiskPhotoStore(out.resolve("map-store"));store.commit(0,photo);var loaded=store.loadCommitted(0).orElseThrow();
                if(!Arrays.equals(loaded.copyRgb(),pixels) || !Arrays.equals(loaded.copyMap(),photo.copyMap()))throw new AssertionError("GPU map-store roundtrip mismatch");
                report.put("backendAndMapStoreVerified",true);report.put("backendMetadata",result.metadata());
                var prepared=renderer.prepare(scene,camera,settings);var cancelled=renderer.submitAsync(prepared,()->true).toCompletableFuture();
                try{cancelled.get(15,TimeUnit.SECONDS);throw new AssertionError("Cancelled photo published");}catch(java.util.concurrent.ExecutionException expected) {if(!(expected.getCause() instanceof java.util.concurrent.CancellationException))throw expected;}
                if(memory.snapshot().live()!=cachedHostBytes(memory))throw new AssertionError("Cancelled backend staging leaked");report.put("cancelledJobVerified",true);
            }
            report.put("status","PASS");
        }catch(Throwable failure){report.put("status","FAIL");report.put("error",failure.toString());report.put("samples",samples);throw failure;}
        finally{probe.close();probe.whenClosed().toCompletableFuture().get(15,TimeUnit.SECONDS);report.put("hostAfterClose",memory.snapshot());Files.writeString(out.resolve("report.json"),new GsonBuilder().setPrettyPrinting().create().toJson(report)+"\n");System.out.println(out.resolve("report.json"));}
    }
}
