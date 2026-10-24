package com.wenkrang.famara.render.platform;
import com.wenkrang.famara.render.math.*;
import com.wenkrang.famara.render.kernel.Camera;
import org.bukkit.util.BoundingBox;
import com.wenkrang.famara.render.schedule.*;
import org.bukkit.*;
import org.bukkit.entity.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class LivingEntityCaptureTest {
    private static Camera camera(){return new Camera(new Vec3(0,1,0),0,0,Camera.Projection.LEGACY_ANGULAR,1);}
    @Test void offscreenCrowdIsRejectedOnOwnerBeforeProfilesOrEquipmentAreRead()throws Exception{
        var memory=new RenderMemoryBudget(64L*1024*1024);var worker=new BudgetedWorkRunner(16,1_000_000,1_000_000,new CpuBudget(2000,100,System::nanoTime));
        try(var assets=new ActorAssets(memory,worker,new CpuBudget(2000,100,System::nanoTime))){
            long baseline=memory.snapshot().live();var world=mock(World.class);var worldId=UUID.randomUUID();when(world.getUID()).thenReturn(worldId);
            var entities=new ArrayList<Entity>();var callbacks=new ArrayList<Runnable>();
            for(int n=0;n<96;n++){
                var player=mock(Player.class);when(player.getUniqueId()).thenReturn(UUID.randomUUID());when(player.getType()).thenReturn(EntityType.PLAYER);when(player.isValid()).thenReturn(true);when(player.getWorld()).thenReturn(world);when(player.getGameMode()).thenReturn(GameMode.SURVIVAL);
                // Behind, sideways, below, above: all within the old 128-block radius.
                BoundingBox bounds=switch(n%4){case 0->new BoundingBox(-30,0,0,-29,2,1);case 1->new BoundingBox(10,0,60,11,2,61);case 2->new BoundingBox(10,-60,0,11,-58,1);default->new BoundingBox(10,60,0,11,62,1);};
                when(player.getBoundingBox()).thenReturn(bounds);entities.add(player);
            }
            var chunk=mock(Chunk.class);when(chunk.getEntities()).thenReturn(entities.toArray(Entity[]::new));
            try(var capture=new LivingEntityCapture(c->List.of(),assets.batch(),worldId,UUID.randomUUID(),camera(),128,(e,a,r)->callbacks.add(a))){
                capture.capture(chunk);for(Entity e:entities){verify(e,never()).getBoundingBox();verify(e,never()).getLocation();}
                callbacks.forEach(Runnable::run);assertTrue(capture.finishActors().toCompletableFuture().get(3,TimeUnit.SECONDS).isEmpty());
                assertEquals("96",capture.actorMetadata().get("actorFrustumCulled"));assertEquals("0",capture.actorMetadata().get("actorCapturedCount"));
                for(Entity e:entities){verify((Player)e,never()).getPlayerProfile();verify((Player)e,never()).getEquipment();}
            }assertEquals(baseline,memory.snapshot().live());
        }finally{worker.close();}assertEquals(0,memory.snapshot().live());
    }
    @Test void modelMarginPreservesEquipmentAndLargeMobEdges(){
        var f=new com.wenkrang.famara.render.kernel.CaptureFrustum(camera(),128);
        assertTrue(f.mayIntersect(10,-1,-1,11,3,1));
        // Feet outside the cone, but an arm/item can still cross the frame edge.
        assertFalse(f.mayIntersect(10,0,11,11,2,12));double m=LivingEntityCapture.modelMargin("PLAYER",1);
        assertTrue(f.mayIntersect(10-m,-m,11-m,11+m,2+m,12+m));
        assertEquals(32,LivingEntityCapture.modelMargin("ENDER_DRAGON",2));
    }
    @Test void deferredOwnerReadsRetirementAndCancellationDoNotRetainSceneBudget()throws Exception{
        var memory=new RenderMemoryBudget(64L*1024*1024);var worker=new BudgetedWorkRunner(16,1_000_000,1_000_000,new CpuBudget(2000,100,System::nanoTime));
        try(var assets=new ActorAssets(memory,worker,new CpuBudget(2000,100,System::nanoTime))){
            long baseline=memory.snapshot().live();var entity=mock(Zombie.class);when(entity.getUniqueId()).thenReturn(UUID.randomUUID());when(entity.getType()).thenReturn(EntityType.ZOMBIE);var chunk=mock(Chunk.class);when(chunk.getEntities()).thenReturn(new Entity[]{entity});var callbacks=new ArrayList<Runnable>();
            var capture=new LivingEntityCapture(c->List.of(),assets.batch(),UUID.randomUUID(),UUID.randomUUID(),camera(),16,(e,action,retired)->callbacks.add(retired));
            var shared=new SharedCaptureExtras(capture);shared.capture(chunk);verify(entity,never()).getLocation();verify(entity,never()).getWorld();var result=shared.finishActors().toCompletableFuture();assertFalse(result.isDone());callbacks.getFirst().run();assertTrue(result.get(3,TimeUnit.SECONDS).isEmpty());var ownership=shared.transferActorResources();shared.close();assertTrue(memory.snapshot().live()>baseline);ownership.close();assertEquals(baseline,memory.snapshot().live());
            callbacks.clear();var pending=new LivingEntityCapture(c->List.of(),assets.batch(),UUID.randomUUID(),UUID.randomUUID(),camera(),16,(e,action,retired)->callbacks.add(action));var session=new SharedCaptureExtras(pending);session.capture(chunk);var waiting=session.finishActors().toCompletableFuture();session.close();assertTrue(waiting.isDone());assertEquals(baseline,memory.snapshot().live());callbacks.getFirst().run();verify(entity,never()).getLocation();assertEquals(baseline,memory.snapshot().live());
        }finally{worker.close();}assertEquals(0,memory.snapshot().live());
    }
    @Test void photographerIsNeverScheduledOrIncluded()throws Exception{
        var memory=new RenderMemoryBudget(64L*1024*1024);var worker=new BudgetedWorkRunner(16,1_000_000,1_000_000,new CpuBudget(2000,100,System::nanoTime));try(var assets=new ActorAssets(memory,worker,new CpuBudget(2000,100,System::nanoTime))){
            var id=UUID.randomUUID();var player=mock(Player.class);when(player.getUniqueId()).thenReturn(id);var chunk=mock(Chunk.class);when(chunk.getEntities()).thenReturn(new Entity[]{player});try(var capture=new LivingEntityCapture(c->List.of(),assets.batch(),UUID.randomUUID(),id,camera(),16,(e,a,r)->fail("Photographer scheduled"))){capture.capture(chunk);assertTrue(capture.finishActors().toCompletableFuture().get(3,TimeUnit.SECONDS).isEmpty());}verify(player,never()).getPlayerProfile();
        }finally{worker.close();}assertEquals(0,memory.snapshot().live());
    }
}
