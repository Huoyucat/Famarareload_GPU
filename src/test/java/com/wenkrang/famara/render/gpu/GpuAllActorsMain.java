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

/** Exhaustive real-P620 photo comparisons over all 26.2 living types and equipment families. */
public final class GpuAllActorsMain {
    record Case(String name,FrozenActor actor){}
    public static void main(String[] args)throws Exception{
        Path server=TestPaths.ROOT,out=Path.of(args[0]).toAbsolutePath().normalize();if(!Path.of("").toAbsolutePath().equals(server) || !out.startsWith(server.resolve("diagnostics/famara-p10-all")) || Files.exists(out))throw new IllegalArgumentException("New test output required");Files.createDirectories(out);
        var report=new LinkedHashMap<String,Object>();var rows=new ArrayList<Map<String,Object>>();var memory=new RenderMemoryBudget(536870912);var cpu=new CpuBudget(2000,100,System::nanoTime);var worker=new BudgetedWorkRunner(128,1_000_000,1_000_000,cpu);var config=new GpuConfiguration(GpuConfiguration.Mode.OPENCL,"Quadro P620",384,1,64,128,10000,true,1.2);var probe=new GpuDeviceProbe(config,cpu,memory);
        int failures=0,photos=0,unsupported=0;
        try(var resources=new ActorAssets(memory,worker,cpu)){
            resources.base(Path.of(args[1]));resources.prepare();if(!probe.initialization().toCompletableFuture().get(15,TimeUnit.SECONDS).available())throw new AssertionError("P620 unavailable");var assets=LivingActorTest.assets();var palette=MapPaletteAdapter.snapshot();var cases=new ArrayList<Case>();
            for(String type:new TreeSet<>(VanillaActorRegistry.types()))cases.add(new Case(type,LivingActorTest.actor(type,0,0)));
            for(String metal:List.of("leather","chainmail","copper","iron","golden","diamond","netherite")){
                var slots=new HashMap<String,FrozenItem>();for(var entry:Map.of("HEAD","helmet","CHEST","chestplate","LEGS","leggings","FEET","boots").entrySet())slots.put(entry.getKey(),FrozenItem.vanilla("minecraft:"+metal+"_"+entry.getValue()));slots.put("MAINHAND",FrozenItem.vanilla("minecraft:diamond_sword"));slots.put("OFFHAND",FrozenItem.vanilla("minecraft:shield"));cases.add(new Case("armor_"+metal,ActorCoverageMain.equipment(LivingActorTest.actor("PLAYER",0,0),slots)));
            }
            for(String item:List.of("bow","crossbow","trident","diamond_spear","stone","oak_stairs","glass","red_stained_glass","red_bed","white_banner","player_head","dragon_head","enchanted_book","potion","filled_map","copper_chest","white_shulker_box","copper_golem_statue","decorated_pot","conduit","elytra","turtle_helmet"))cases.add(new Case("hand_"+item,ActorCoverageMain.equipment(LivingActorTest.actor("PLAYER",0,0),Map.of("MAINHAND",FrozenItem.vanilla("minecraft:"+item)))));
            for(var spec:List.of(new String[]{"HORSE","minecraft:diamond_horse_armor"},new String[]{"WOLF","minecraft:wolf_armor"},new String[]{"NAUTILUS","minecraft:iron_nautilus_armor"},new String[]{"HAPPY_GHAST","minecraft:red_harness"},new String[]{"LLAMA","minecraft:red_carpet"}))cases.add(new Case("body_"+spec[0],ActorCoverageMain.equipment(LivingActorTest.actor(spec[0],0,0),Map.of("BODY",FrozenItem.vanilla(spec[1])))));
            cases.add(new Case("worn_elytra",ActorCoverageMain.equipment(LivingActorTest.actor("PLAYER",0,0),Map.of("CHEST",FrozenItem.vanilla("minecraft:elytra")))));
            cases.add(new Case("worn_turtle",ActorCoverageMain.equipment(LivingActorTest.actor("PLAYER",0,0),Map.of("HEAD",FrozenItem.vanilla("minecraft:turtle_helmet")))));
            var leftBase=ActorCoverageMain.equipment(LivingActorTest.actor("PLAYER",0,0),Map.of("MAINHAND",FrozenItem.vanilla("minecraft:diamond_sword"),"OFFHAND",FrozenItem.vanilla("minecraft:shield")));var left=new FrozenActor(leftBase.id(),leftBase.type(),leftBase.position(),135,135,0,"CROUCHING",false,1,0,"",0xffffff,"",true,false,15,0,ActorAppearance.EMPTY,new FrozenEquipment(leftBase.equipment().slots(),true));cases.add(new Case("left_slim_crouching",left));
            var trimmed=new FrozenItem("minecraft:diamond_chestplate","",0xffffff,"minecraft:silence","minecraft:amethyst",true,0,"","",false);cases.add(new Case("armor_trim_glint",ActorCoverageMain.equipment(LivingActorTest.actor("PLAYER",0,0),Map.of("CHEST",trimmed))));
            for(Case c:cases){
                try(var batch=resources.batch()){
                    var geometry=batch.bake(List.of(c.actor)).toCompletableFuture().get(15,TimeUnit.SECONDS).getFirst();var builder=new SceneSnapshot.Builder(assets,-64,128,100,200000);for(int x=-10;x<=10;x++)for(int z=-10;z<=10;z++)for(int y=-4;y<8;y++)builder.availableSection(x,y,z);builder.actor(geometry).actorResources(batch);
                    try(var scene=builder.build()){
                        Vec3 low=geometry.model().min(),high=geometry.model().max();Vec3 center=low.add(high).multiply(.5);double width=high.x()-low.x(),height=high.y()-low.y(),distance=Math.max(2,Math.max(width,height)*1.5);Camera camera=new Camera(new Vec3(center.x(),center.y(),low.z()-distance),Math.PI/2,0,Camera.Projection.PERSPECTIVE,Math.PI/3);
                        for(int aa:new int[]{1,4})for(var filter:Texture.Filter.values()){
                            var row=new LinkedHashMap<String,Object>();row.put("case",c.name);row.put("aa",aa);row.put("filter",filter);rows.add(row);
                            try{
                                var settings=new CpuPhotoRenderer.Settings(128,aa,filter,true,.3,.5,32,0x123456,6000,new VoxelTracer.Limits(20000,10000,100000),Map.of());var renderer=new CpuPhotoRenderer(scene,camera,settings);var cursor=renderer.cursor();renderer.renderBatch(cursor,16384,Long.MAX_VALUE,System::nanoTime,()->false);int[] expected=cursor.copyRgb();long visible=Arrays.stream(expected).filter(pixel->pixel!=0x123456).count();if(visible==0)throw new AssertionError("Empty actor image");
                                if(aa==1 && filter==Texture.Filter.NEAREST)GpuPhotoMvpMain.save(out.resolve(c.name+"-cpu.png"),expected);
                                try(var packed=GpuSceneSerializer.serialize(scene,camera,settings,config,memory)){
                                    for(int repeat=0;repeat<2;repeat++){
                                        GpuDeviceProbe.PhotoFrame frame;
                                        try{frame=probe.renderPhoto(packed).toCompletableFuture().get(15,TimeUnit.SECONDS);}catch(ExecutionException error){if(error.getCause() instanceof BackendException b && b.code()==BackendException.Code.UNSUPPORTED){unsupported++;row.put("status","CPU_FALLBACK_REQUIRED");row.put("reason",b.getMessage());break;}throw error;}
                                        int[] actual=GpuPhotoMvpMain.rgb(frame.linear());photos++;int different=0,mapDifferent=0,max=0;byte[] cpuMap=palette.quantize(expected),gpuMap=palette.quantize(actual);for(int i=0;i<actual.length;i++){if(actual[i]!=expected[i])different++;if(cpuMap[i]!=gpuMap[i])mapDifferent++;for(int sh:new int[]{0,8,16})max=Math.max(max,Math.abs((actual[i]>>sh&255)-(expected[i]>>sh&255)));}
                                        row.put("rgbDifferentPixels",different);row.put("mapDifferentPixels",mapDifferent);row.put("maxChannelError",max);row.put("visiblePixels",visible);row.put("status",different==0 && mapDifferent==0?"PASS":"FAIL");
                                        if(aa==1 && filter==Texture.Filter.NEAREST || different!=0)GpuPhotoMvpMain.save(out.resolve(c.name+"-gpu.png"),actual);
                                        if(different!=0 || mapDifferent!=0)throw new AssertionError("CPU/GPU mismatch");
                                    }
                                }
                            }catch(Throwable error){row.put("status","FAIL");row.put("error",error.toString());failures++;}
                        }
                    }
                }
                if(memory.snapshot().domains().containsKey("actor-scene") || memory.snapshot().domains().containsKey("actor-item-build"))throw new AssertionError("Actor reservation leaked");
                Files.writeString(out.resolve("progress.json"),new GsonBuilder().setPrettyPrinting().create().toJson(rows)+"\n");
            }
            report.put("status",failures==0?"PASS":"FAIL");report.put("gpuPhotoCount",photos);report.put("failedCases",failures);report.put("fallbackRequiredCases",unsupported);
        }catch(Throwable error){report.put("status","FAIL");report.put("error",error.toString());throw error;}
        finally{worker.close();probe.close();probe.whenClosed().toCompletableFuture().get(15,TimeUnit.SECONDS);report.put("cases",rows);report.put("memoryAfterClose",memory.snapshot());Files.writeString(out.resolve("report.json"),new GsonBuilder().setPrettyPrinting().create().toJson(report)+"\n");System.out.println("GPU photos="+photos+" failures="+failures+" fallback="+unsupported+" live="+memory.snapshot().live());}
        if(failures>0 || memory.snapshot().live()!=0)throw new AssertionError("Actor GPU differential failed");
    }
}
