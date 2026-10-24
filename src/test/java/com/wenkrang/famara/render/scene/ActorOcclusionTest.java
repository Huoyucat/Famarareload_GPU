package com.wenkrang.famara.render.scene;

import com.wenkrang.famara.render.kernel.*;
import com.wenkrang.famara.render.math.*;
import com.wenkrang.famara.render.platform.ActorAssets;
import com.wenkrang.famara.render.schedule.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class ActorOcclusionTest {
    @TempDir Path temporary;
    private Path source()throws Exception {Path p=temporary.resolve("actors.zip");try(var in=getClass().getResourceAsStream("/render-actors/vanilla-assets-26.2.zip")){Files.copy(Objects.requireNonNull(in),p,StandardCopyOption.REPLACE_EXISTING);}return p;}
    private static ConservativeOcclusion visibility(int samples,ConservativeOcclusion.View view){
        var v=new ConservativeOcclusion(new Camera(new Vec3(0,1,-10),Math.PI/2,0,Camera.Projection.PERSPECTIVE,1),samples,64);
        while(!v.update(view,65536,Long.MAX_VALUE)){}return v;
    }
    @Test void completeWallSkipsSkinAndModelResourcesButPartialOrUnknownWallsRetainActors()throws Exception {
        for(int samples:new int[]{1,4}){
            var memory=new RenderMemoryBudget(64L*1024*1024);var cpu=new CpuBudget(2000,100,System::nanoTime);
            try(var worker=new BudgetedWorkRunner(64,1_000_000,1_000_000,cpu);var assets=new ActorAssets(memory,worker,cpu)){
                assets.prepare();assets.base(temporary.resolve("missing-resource-proves-no-model-was-built.zip"));long baseline=memory.snapshot().live();
                var actor=LivingActorTest.actor("PLAYER",0,4);var full=visibility(samples,(x,y,z)->z==0?1:0);
                try(var batch=assets.batch()){assertTrue(batch.bake(List.of(actor),full).toCompletableFuture().get(10,TimeUnit.SECONDS).isEmpty());assertEquals(1,batch.earlyCulled());assertEquals(1,batch.occlusionCulled());}
                assertEquals(baseline,memory.snapshot().live());assets.base(source());
                for(var v:List.of(visibility(samples,(x,y,z)->z==0 && y<1?1:0),visibility(samples,(x,y,z)->0),visibility(samples,(x,y,z)->-1))){
                    try(var batch=assets.batch()){assertEquals(1,batch.bake(List.of(actor),v).toCompletableFuture().get(10,TimeUnit.SECONDS).size());assertEquals(0,batch.occlusionCulled());}
                    assertEquals(baseline,memory.snapshot().live());
                }
                // A held item's extent is unknown before baking. Verify the entire
                // resulting mesh before removing it; never test only the body/centre.
                var armed=ActorCoverageMain.equipment(actor,Map.of("MAINHAND",FrozenItem.vanilla("minecraft:diamond_sword")));
                assertNull(ActorModelCatalog.load().conservativeBounds(armed));
                try(var batch=assets.batch()){assertTrue(batch.bake(List.of(armed),full).toCompletableFuture().get(10,TimeUnit.SECONDS).isEmpty());assertEquals(0,batch.earlyCulled());assertEquals(1,batch.occlusionCulled());}
                assertEquals(baseline,memory.snapshot().live());
                var nearEdge=new FrozenActor(UUID.randomUUID(),"PLAYER",new Vec3(.8,0,5.5),180,180,0,"STANDING",false,3,0,"",0xffffff,"",false,false,15,0);var exposedWeapon=ActorCoverageMain.equipment(nearEdge,Map.of("MAINHAND",FrozenItem.vanilla("minecraft:trident")));
                var halfWall=visibility(samples,(x,y,z)->z==3?1:0);
                try(var diagnostic=assets.batch()){var mesh=diagnostic.bake(List.of(exposedWeapon)).toCompletableFuture().get(10,TimeUnit.SECONDS).getFirst();assertTrue(ActorBounds.of(mesh).z0()<3,"Weapon really protrudes in front of the wall, beyond the hidden body");}
                assertTrue(ActorModelCatalog.load().conservativeBounds(nearEdge).hiddenBy(halfWall),"Body is entirely behind the wall edge");
                try(var batch=assets.batch()){assertEquals(1,batch.bake(List.of(exposedWeapon),halfWall).toCompletableFuture().get(10,TimeUnit.SECONDS).size(),"An exposed weapon must preserve an otherwise hidden player");assertEquals(0,batch.occlusionCulled());}
                assertEquals(baseline,memory.snapshot().live());
            }assertEquals(0,memory.snapshot().live());
        }
    }
    @Test void earlyBoundsContainEveryEmittedVertexIncludingPoseScaleAndArmor()throws Exception {
        var memory=new RenderMemoryBudget(128L*1024*1024);var cpu=new CpuBudget(2000,100,System::nanoTime);var catalog=ActorModelCatalog.load();
        try(var worker=new BudgetedWorkRunner(64,1_000_000,1_000_000,cpu);var assets=new ActorAssets(memory,worker,cpu)){
            assets.base(source());assets.prepare();var actors=new ArrayList<FrozenActor>();
            for(String type:VanillaActorRegistry.types()){
                var a=LivingActorTest.actor(type,-13.2,4.7);actors.add(a);
                actors.add(new FrozenActor(a.id(),a.type(),a.position(),47,-53,28,"CROUCHING",true,1.7,.6,"",0xffffff,"",true,false,15,0,a.appearance(),a.equipment()));
            }
            var player=LivingActorTest.actor("PLAYER",0,4);
            for(String item:List.of("diamond_helmet","diamond_chestplate","diamond_leggings","diamond_boots","elytra")){
                String slot=item.endsWith("helmet")?"HEAD":item.endsWith("leggings")?"LEGS":item.endsWith("boots")?"FEET":"CHEST";
                actors.add(ActorCoverageMain.equipment(player,Map.of(slot,FrozenItem.vanilla("minecraft:"+item))));
            }
            for(var pair:List.of(new String[]{"HORSE","diamond_horse_armor"},new String[]{"SKELETON_HORSE","iron_horse_armor"},new String[]{"ZOMBIE_HORSE","iron_horse_armor"},new String[]{"WOLF","wolf_armor"},new String[]{"NAUTILUS","iron_nautilus_armor"},new String[]{"ZOMBIE_NAUTILUS","iron_nautilus_armor"},new String[]{"HAPPY_GHAST","red_harness"},new String[]{"LLAMA","red_carpet"},new String[]{"TRADER_LLAMA","red_carpet"}))actors.add(ActorCoverageMain.equipment(LivingActorTest.actor(pair[0],0,4),Map.of("BODY",FrozenItem.vanilla("minecraft:"+pair[1]))));
            for(var actor:actors)try(var batch=assets.batch()){
                var mesh=batch.bake(List.of(actor)).toCompletableFuture().get(10,TimeUnit.SECONDS).getFirst();var bounds=catalog.conservativeBounds(actor);assertNotNull(bounds,actor.type());
                for(var q:mesh.model().quads())for(var p:q.positions())assertTrue(bounds.contains(p.add(new Vec3(mesh.x(),mesh.y(),mesh.z()))),actor.type()+" "+actor.pose()+" "+actor.equipment()+" bounds="+bounds+" point="+p);
            }
        }assertEquals(0,memory.snapshot().live());
    }
}
