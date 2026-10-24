package com.wenkrang.famara.render.gpu;

import com.wenkrang.famara.TestPaths;
import com.google.gson.GsonBuilder;
import com.wenkrang.famara.render.asset.*;
import com.wenkrang.famara.render.kernel.*;
import com.wenkrang.famara.render.math.*;
import com.wenkrang.famara.render.scene.*;
import com.wenkrang.famara.render.schedule.*;
import com.wenkrang.famara.render.platform.MapPaletteAdapter;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Feature-by-feature device/CPU comparison, deliberately outside the unit-test lifecycle. */
public final class GpuFeatureMain {
    static AssetGeneration assets()throws Exception {return assets(false,1);}
    static AssetGeneration assets(long id)throws Exception {return assets(false,id);}
    static AssetGeneration assets(boolean flowPattern)throws Exception {return assets(flowPattern,1);}
    static AssetGeneration assets(boolean flowPattern,long id)throws Exception {
        var f=new RenderFixtures();f.png("test:tile",2,2,0xffbb6633,0xff55aa66,0xff3344cc,0xffdddddd);f.png("test:solid",1,1,0xff778899);f.png("test:glass",1,1,0x8088ccff);f.png("test:cutout",2,2,0xff44bb22,0,0,0xff44bb22);f.png("minecraft:block/water_still",1,1,0x803f76e4);f.png("minecraft:block/water_flow",flowPattern?4:1,flowPattern?4:1,0x803f76e4,flowPattern?0x8099cc55:0x803f76e4,flowPattern?0x80cc6666:0x803f76e4);
        for(String key:List.of("tile","solid","glass","cutout")){f.cube("test:"+key,"test:"+key);f.block("minecraft:"+key,"test:"+key);}f.block("minecraft:water","test:solid");
        f.cube("test:rotated","test:tile");String path="assets/test/models/rotated.json";String model=new String(f.files.get(path),StandardCharsets.UTF_8).replace("\"from\":[0,0,0]","\"rotation\":{\"origin\":[8,8,8],\"axis\":\"y\",\"angle\":22.5,\"rescale\":false},\"from\":[0,0,0]");f.json(path,model);f.block("minecraft:rotated","test:rotated");
        f.png("test:animated",16,48,0xffff0000,0xff00ff00,0xff0000ff);f.json("assets/test/textures/animated.png.mcmeta","{\"animation\":{\"frametime\":3,\"interpolate\":true}}");f.cube("test:animated","test:animated");f.block("minecraft:animated","test:animated");
        f.png("test:animated-step",8,24,0xffff0000,0xff00ff00,0xff0000ff);f.json("assets/test/textures/animated-step.png.mcmeta","{\"animation\":{\"frames\":[{\"index\":2,\"time\":2},{\"index\":0,\"time\":4},1]}}");f.cube("test:animated-step","test:animated-step");f.block("minecraft:animated-step","test:animated-step");
        f.cube("test:tinted","test:tile");path="assets/test/models/tinted.json";f.json(path,new String(f.files.get(path),StandardCharsets.UTF_8).replace("\"from\":[0,0,0]","\"light_emission\":9,\"from\":[0,0,0]").replace("\"texture\":\"#all\"","\"texture\":\"#all\",\"tintindex\":0"));f.block("minecraft:tinted","test:tinted");
        f.json("assets/minecraft/blockstates/overlay.json","{\"multipart\":[{\"apply\":{\"model\":\"test:tile\"}},{\"apply\":{\"model\":\"test:glass\"}}]}");return f.generation(id);
    }
    static CpuPhotoRenderer.Settings settings(String name,int aa,Texture.Filter filter) {
        return new CpuPhotoRenderer.Settings(name.equals("lod")?128:12,aa,filter,true,.2,.5,name.equals("layer-limit")?1:32,0x182838,name.equals("animated-negative")?-1:name.equals("animated")?1:name.equals("animated-max")?Long.MAX_VALUE:name.equals("animated-min")?Long.MIN_VALUE:0,new VoxelTracer.Limits(10000,1000,10000),Map.of(ResourceKey.parse("test:glass"),CpuPhotoRenderer.AlphaMode.TRANSLUCENT,ResourceKey.parse("test:cutout"),CpuPhotoRenderer.AlphaMode.CUTOUT));
    }
    static SceneSnapshot scene(String name,AssetGeneration g,Camera camera,CpuPhotoRenderer.Settings settings) {
        var b=new SceneSnapshot.Builder(g,-16,32,100,20000);for(int x=-9;x<=9;x++)for(int z=-9;z<=9;z++)for(int y=-1;y<2;y++)b.availableSection(x,y,z);
        var light=new SceneSnapshot.Lighting(name.equals("night") || name.equals("tinted")?0:15,name.equals("night") || name.equals("tinted")?2:0,0x68a748,0x3f76e4);var tile=RenderFixtures.vanilla("minecraft:tile",g.id());
        if(name.equals("lod")) {
            var solid=RenderFixtures.vanilla("minecraft:solid",g.id());try(var lod=new FarLod(camera,settings,new FarLod.Policy(true,64,4),g,null)){if(!lod.emit(0,0,96,(x,y,z)->new FarLod.Host(solid,g.resolve(solid,x,y,z,0),light),(x,y,z)->true,b))throw new AssertionError("LOD fixture refused");}
        }else {
            b.put(0,name.equals("negative-y")?-2:0,2,tile,light,0);
            switch(name) {
                case "ce-carrier" -> b.put(0,0,0,new VisualBlockDescriptor(VisualBlockDescriptor.Kind.CE,ResourceKey.parse("ce:fixture"),tile.visualState(),g.id()),light,0);
                case "legacy-entity" -> b.entity(new LegacyEntity("fixture-furniture",List.of(new LegacyEntity.Box(new Vec3(0,0,0),new Vec3(1,1,g.id()))),0xcc9944));
                case "water","water-flow" -> {for(int x=-1;x<=1;x++)for(int z=-1;z<=1;z++)for(int y=-1;y<=1;y++)b.neighbor(x,y,z,CanonicalBlockState.parse("minecraft:air"),false);if(name.equals("water-flow")){b.neighbor(1,0,0,CanonicalBlockState.parse("minecraft:water[level=4]"),false);b.neighbor(-1,0,0,CanonicalBlockState.parse("minecraft:water[level=7]"),false);}
                    b.put(0,0,0,RenderFixtures.vanilla("minecraft:water[level=0]",g.id()),light,0);}
                case "layers","layer-limit" -> {b.put(0,0,0,RenderFixtures.vanilla("minecraft:glass",g.id()),light,0);b.put(0,0,1,RenderFixtures.vanilla("minecraft:glass",g.id()),light,0);}
                default -> b.put(0,name.equals("negative-y")?-2:0,0,RenderFixtures.vanilla("minecraft:"+switch(name){case "glass","cutout","rotated","overlay","tinted"->name;case "animated","animated-negative"->"animated";case "animated-max","animated-min"->"animated-step";default->"tile";},g.id()),light,0);
            }
        }
        return b.build();
    }
    public static void main(String[] args)throws Exception {
        var server=TestPaths.ROOT;var out=Path.of(args[0]).toAbsolutePath().normalize();if(!Path.of("").toAbsolutePath().normalize().equals(server) || !out.startsWith(server.resolve("diagnostics/famara-p5")) || Files.exists(out))throw new IllegalArgumentException("New diagnostics/famara-p5 output required");Files.createDirectories(out);
        var report=new LinkedHashMap<String,Object>();var rows=new ArrayList<Object>();var host=new RenderMemoryBudget(536870912);var config=new GpuConfiguration(GpuConfiguration.Mode.OPENCL,"Quadro P620",384,1,64,128,10000,true,1.2);var probe=new GpuDeviceProbe(config,new CpuBudget(2000,100,System::nanoTime),host);
        try {
            var status=probe.initialization().toCompletableFuture().get(15,TimeUnit.SECONDS);if(!status.available())throw new AssertionError(status.reason());var assets=assets();var patterned=assets(true);var palette=MapPaletteAdapter.snapshot();int failures=0;
            for(String name:List.of("opaque","glass","cutout","water","night","ce-carrier","legacy-entity","lod","layers","rotated","overlay","animated","animated-negative","animated-max","animated-min","tinted","negative-y","water-flow"))for(int aa:new int[]{1,4})for(var filter:Texture.Filter.values()) {
                String label=name+"-"+aa+"-"+filter;var row=new LinkedHashMap<String,Object>();row.put("case",label);rows.add(row);
                try {
                    var camera=new Camera(new Vec3(.5,name.equals("negative-y")?-1.4:.6,-3),Math.PI/2,0,aa==1?Camera.Projection.PERSPECTIVE:Camera.Projection.LEGACY_ANGULAR,Math.PI/3);var settings=settings(name,aa,filter);var scene=scene(name,name.equals("water-flow")?patterned:assets,camera,settings);var ref=new CpuPhotoRenderer(scene,camera,settings);var cursor=ref.cursor();ref.renderBatch(cursor,16384,Long.MAX_VALUE,System::nanoTime,()->false);int[] expected=cursor.copyRgb();
                    try(var packed=GpuSceneSerializer.serialize(scene,camera,settings,config,host)) {
                        for(int repeat=0;repeat<5;repeat++) {
                        var frame=probe.renderPhoto(packed).toCompletableFuture().get(15,TimeUnit.SECONDS);int[] actual=GpuPhotoMvpMain.rgb(frame.linear());int changed=0,max=0,mapChanged=0;byte[] cpuMap=palette.quantize(expected),gpuMap=palette.quantize(actual);
                        for(int i=0;i<actual.length;i++){if(actual[i]!=expected[i])changed++;if(cpuMap[i]!=gpuMap[i])mapChanged++;for(int shift:new int[]{0,8,16})max=Math.max(max,Math.abs((actual[i]>>shift&255)-(expected[i]>>shift&255)));}
                        long live=probe.buffers().live();if(repeat==0)row.put("retainedGpuBytes",live);else if(!row.get("retainedGpuBytes").equals(live))throw new AssertionError("GPU reservations accumulated within repeated feature job");
                        if(probe.buffers().domains().containsKey("kernel-private-memory-estimate"))throw new AssertionError("Kernel working reservation leaked");
                        row.put("features",packed.features());row.put("rgbDifferentPixels",changed);row.put("mapDifferentPixels",mapChanged);row.put("maxChannelError",max);row.put("kernelNanos",frame.kernelNanos());row.put("gpuWallNanos",frame.wallNanos());row.put("status",changed==0 && mapChanged==0?"PASS":"FAIL");row.put("repeats",repeat+1);
                        if(repeat==0){GpuPhotoMvpMain.save(out.resolve(label+"-cpu.png"),expected);GpuPhotoMvpMain.save(out.resolve(label+"-gpu.png"),actual);Files.write(out.resolve(label+"-cpu.map"),cpuMap);Files.write(out.resolve(label+"-gpu.map"),gpuMap);}
                        if(changed!=0 || mapChanged!=0)throw new AssertionError("RGB/map mismatch at repeat "+repeat);
                        }
                    }
                }catch(Throwable error){row.put("status","FAIL");row.put("error",error.toString());failures++;}
                if(host.snapshot().live()!=GpuPhotoMvpMain.cachedHostBytes(host))throw new AssertionError("Feature staging leaked");
            }
            var limitCamera=new Camera(new Vec3(.5,.6,-3),Math.PI/2,0,Camera.Projection.PERSPECTIVE,Math.PI/3);var limitSettings=settings("layer-limit",1,Texture.Filter.NEAREST);var limitScene=scene("layer-limit",assets,limitCamera,limitSettings);
            try{new CpuPhotoRenderer(limitScene,limitCamera,limitSettings).sampleRay(limitCamera.ray(64,64,.5,.5),()->false);throw new AssertionError("CPU transparency fixture did not hit its limit");}catch(SceneException expected){if(expected.code()!=SceneException.Code.TRANSPARENCY_LIMIT)throw expected;}
            try(var packed=GpuSceneSerializer.serialize(limitScene,limitCamera,limitSettings,config,host)) {
                try{probe.renderPhoto(packed).toCompletableFuture().get(15,TimeUnit.SECONDS);throw new AssertionError("GPU published an over-limit photo");}
                catch(java.util.concurrent.ExecutionException expected){if(!(expected.getCause() instanceof BackendException b) || b.code()!=BackendException.Code.UNSUPPORTED)throw expected;}
            }
            if(host.snapshot().live()!=GpuPhotoMvpMain.cachedHostBytes(host))throw new AssertionError("Layer-limit failure leaked staging");report.put("transparencyLimitVerified",true);
            var complexBuilder=new SceneSnapshot.Builder(assets,-16,32,100,20000);
            for(int cx=-9;cx<=9;cx++)for(int cz=-9;cz<=9;cz++)for(int cy=-1;cy<2;cy++)complexBuilder.availableSection(cx,cy,cz);
            for(int z=0;z<65;z++)complexBuilder.put(0,0,z,RenderFixtures.vanilla("minecraft:glass",1),new SceneSnapshot.Lighting(15,0,0xffffff),0);
            var complex=complexBuilder.build();var complexSettings=new CpuPhotoRenderer.Settings(80,1,Texture.Filter.NEAREST,true,.2,.5,32,0x182838,0,new VoxelTracer.Limits(10000,1000,10000),Map.of(ResourceKey.parse("test:glass"),CpuPhotoRenderer.AlphaMode.OPAQUE));
            new CpuPhotoRenderer(complex,limitCamera,complexSettings).sampleRay(limitCamera.ray(64,64,.5,.5),()->false);
            try(var packed=GpuSceneSerializer.serialize(complex,limitCamera,complexSettings,config,host)) {
                try{probe.renderPhoto(packed).toCompletableFuture().get(15,TimeUnit.SECONDS);throw new AssertionError("Over-capacity hit list published partial image");}
                catch(java.util.concurrent.ExecutionException expected){if(!(expected.getCause() instanceof BackendException b) || b.code()!=BackendException.Code.UNSUPPORTED)throw expected;}
            }
            report.put("complexityOverflowVerified",true);
            var tiny=new GpuConfiguration(GpuConfiguration.Mode.OPENCL,"Quadro P620",1,1,64,1,10000,true,1.2);var tinyProbe=new GpuDeviceProbe(tiny,new CpuBudget(2000,100,System::nanoTime),host);
            try {
                if(!tinyProbe.initialization().toCompletableFuture().get(15,TimeUnit.SECONDS).available())throw new AssertionError("Tiny-budget device fixture unavailable");
                try(var packed=GpuSceneSerializer.serialize(limitScene,limitCamera,settings("layers",1,Texture.Filter.NEAREST),tiny,host)) {
                    try{tinyProbe.renderPhoto(packed).toCompletableFuture().get(15,TimeUnit.SECONDS);throw new AssertionError("Private kernel memory bypassed VRAM cap");}
                    catch(java.util.concurrent.ExecutionException expected){if(!(expected.getCause() instanceof BackendException b) || b.code()!=BackendException.Code.OUT_OF_MEMORY)throw expected;}
                }
            }finally{tinyProbe.close();tinyProbe.whenClosed().toCompletableFuture().get(15,TimeUnit.SECONDS);}
            if(host.snapshot().live()!=GpuPhotoMvpMain.cachedHostBytes(host))throw new AssertionError("VRAM-denied job leaked host staging");report.put("nativeVramCapVerified",true);report.put("photoCount",rows.size()*5);
            report.put("failedCases",failures);report.put("buffersBeforeClose",probe.buffers());if(failures>0)throw new AssertionError(failures+" feature cases failed");report.put("status","PASS");
        }catch(Throwable error){report.put("status","FAIL");report.put("error",error.toString());throw error;}
        finally{probe.close();probe.whenClosed().toCompletableFuture().get(15,TimeUnit.SECONDS);report.put("cases",rows);report.put("hostAfterClose",host.snapshot());Files.writeString(out.resolve("report.json"),new GsonBuilder().setPrettyPrinting().create().toJson(report)+"\n");System.out.println(out.resolve("report.json"));}
    }
}
