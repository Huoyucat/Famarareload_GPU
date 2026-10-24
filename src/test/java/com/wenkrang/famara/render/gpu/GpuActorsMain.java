package com.wenkrang.famara.render.gpu;
import com.wenkrang.famara.TestPaths;
import com.google.gson.GsonBuilder;
import com.wenkrang.famara.render.platform.*;
import com.wenkrang.famara.render.scene.*;
import com.wenkrang.famara.render.asset.*;
import com.wenkrang.famara.render.kernel.*;
import com.wenkrang.famara.render.math.*;
import com.wenkrang.famara.render.output.*;
import com.wenkrang.famara.render.schedule.*;
import java.util.*;
import java.util.concurrent.*;
import java.nio.file.*;

/** Real P620 differential tests with the same bundled textures and meshes used by live capture. */
public final class GpuActorsMain {
    public static void main(String[] args)throws Exception{
        Path server=TestPaths.ROOT,out=Path.of(args[0]).toAbsolutePath().normalize();if(!Path.of("").toAbsolutePath().equals(server) || !out.startsWith(server.resolve("diagnostics/famara-p9")) || Files.exists(out))throw new IllegalArgumentException("Test directory only");Files.createDirectories(out);
        var report=new LinkedHashMap<String,Object>();var rows=new ArrayList<Object>();var memory=new RenderMemoryBudget(536870912);var cpu=new CpuBudget(2000,100,System::nanoTime);var worker=new BudgetedWorkRunner(128,1_000_000,1_000_000,cpu);var config=new GpuConfiguration(GpuConfiguration.Mode.OPENCL,"Quadro P620",384,1,64,128,10000,true,1.2);var probe=new GpuDeviceProbe(config,cpu,memory);
        try(var resources=new ActorAssets(memory,worker,cpu)){
            resources.base(Path.of(args[1]));if(!probe.initialization().toCompletableFuture().get(15,TimeUnit.SECONDS).available())throw new AssertionError("P620 unavailable");var assets=LivingActorTest.assets();var palette=MapPaletteAdapter.snapshot();int photos=0;
            for(String type:List.of("PLAYER","ZOMBIE","HUSK","DROWNED","SKELETON","STRAY","BOGGED","WITHER_SKELETON","CREEPER","COW","PIG","SHEEP","CHICKEN","SPIDER","CAVE_SPIDER","ENDERMAN","PLAYER_CROUCH","PLAYER_SLIM","PLAYER_BABY","PLAYER_TURNED","PLAYER_WALL")){
                var a=LivingActorTest.actor(type.startsWith("PLAYER")?"PLAYER":type,0,0);a=new FrozenActor(a.id(),a.type(),a.position(),type.equals("PLAYER_TURNED")?120:180,type.equals("PLAYER_TURNED")?170:180,type.equals("PLAYER_TURNED")?25:0,type.equals("PLAYER_CROUCH")?"CROUCHING":"STANDING",type.equals("PLAYER_BABY"),1,.25,"",0xc74ebd,"",type.equals("PLAYER_SLIM"),false,15,0);
                try(var batch=resources.batch()){
                    var geometry=batch.bake(List.of(a)).toCompletableFuture().get(15,TimeUnit.SECONDS).getFirst();var builder=new SceneSnapshot.Builder(assets,-16,32,100,20000);for(int x=-2;x<=2;x++)for(int z=-2;z<=2;z++)for(int y=-1;y<2;y++)builder.availableSection(x,y,z);builder.actor(geometry);
                    if(type.equals("PLAYER_WALL"))for(int x=-1;x<=1;x++)for(int y=0;y<4;y++)builder.put(x,y,-1,RenderFixtures.vanilla("test:block",1),new SceneSnapshot.Lighting(15,0,0xffffff),0);
                    try(var scene=builder.build()){
                        var camera=LivingActorTest.camera();
                        for(int aa:new int[]{1,4})for(var filter:Texture.Filter.values()){
                            var settings=new CpuPhotoRenderer.Settings(12,aa,filter,true,.3,.5,32,0x123456,0,new VoxelTracer.Limits(10000,1000,10000),Map.of());var renderer=new CpuPhotoRenderer(scene,camera,settings);var cursor=renderer.cursor();renderer.renderBatch(cursor,16384,Long.MAX_VALUE,System::nanoTime,()->false);int[] expected=cursor.copyRgb();long visible=Arrays.stream(expected).filter(pixel->pixel!=0x123456).count();if(visible==0)throw new AssertionError("Empty actor image: "+type);
                            try(var packed=GpuSceneSerializer.serialize(scene,camera,settings,config,memory)){
                                for(int repeat=0;repeat<3;repeat++){var result=probe.renderPhoto(packed).toCompletableFuture().get(15,TimeUnit.SECONDS);int[] actual=GpuPhotoMvpMain.rgb(result.linear());if(!Arrays.equals(expected,actual) || !Arrays.equals(palette.quantize(expected),palette.quantize(actual))){GpuPhotoMvpMain.save(out.resolve(type+"-mismatch-cpu.png"),expected);GpuPhotoMvpMain.save(out.resolve(type+"-mismatch-gpu.png"),actual);int different=0,max=0;for(int i=0;i<actual.length;i++){if(actual[i]!=expected[i])different++;for(int sh:new int[]{0,8,16})max=Math.max(max,Math.abs((actual[i]>>sh&255)-(expected[i]>>sh&255)));}throw new AssertionError("Actor CPU/GPU mismatch "+type+" "+aa+" "+filter+" pixels="+different+" maxChannel="+max);}photos++;if(aa==1 && filter==Texture.Filter.NEAREST && repeat==0){GpuPhotoMvpMain.save(out.resolve(type+"-cpu.png"),expected);GpuPhotoMvpMain.save(out.resolve(type+"-gpu.png"),actual);}}
                            }
                            rows.add(Map.of("actor",type,"aa",aa,"filter",filter,"status","PASS","visiblePixels",visible,"rgbExact",true,"mapExact",true,"repeats",3));
                        }
                    }
                }
                if(memory.snapshot().domains().containsKey("actor-scene"))throw new AssertionError("Actor scene reservation leaked");
            }
            report.put("photoCount",photos);report.put("status","PASS");
        }catch(Throwable error){report.put("status","FAIL");report.put("error",error.toString());throw error;}
        finally{worker.close();probe.close();probe.whenClosed().toCompletableFuture().get(15,TimeUnit.SECONDS);report.put("cases",rows);report.put("memoryAfterClose",memory.snapshot());Files.writeString(out.resolve("report.json"),new GsonBuilder().setPrettyPrinting().create().toJson(report)+"\n");System.out.println(out.resolve("report.json"));}
        if(memory.snapshot().live()!=0)throw new AssertionError("Actor runtime reservations leaked");
    }
}
