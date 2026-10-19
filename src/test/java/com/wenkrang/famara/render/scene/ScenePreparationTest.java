package com.wenkrang.famara.render.scene;

import com.wenkrang.famara.render.asset.*;
import com.wenkrang.famara.render.kernel.*;
import com.wenkrang.famara.render.math.Vec3;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ScenePreparationTest {
    @Test void geometryBeyondBothWorldHeightLimitsRemainsVisibleAndBuildCanYield() throws Exception {
        RenderFixtures f=new RenderFixtures();f.png("test:tile",1,1,0xffffffff);
        f.json("assets/test/models/block/tall.json","""
                {"textures":{"all":"test:tile"},"elements":[{"from":[0,-16,0],"to":[16,32,16],"faces":{"north":{"texture":"#all"}}}]}
                """);f.block("test:tall","test:block/tall");var g=f.generation(1);
        assertTrue(g.hostHalo()>=2);
        for(int y:new int[]{0,15}) {
            var builder=new SceneSnapshot.Builder(g,0,16,10,1000);
            for(int cx=-1;cx<=1;cx++)for(int cz=-1;cz<=1;cz++)builder.availableSection(cx,0,cz);
            builder.put(0,y,0,RenderFixtures.vanilla("test:tall",1),new SceneSnapshot.Lighting(15,0,0xffffff),0);
            var cursor=builder.finish();assertFalse(cursor.step(1,Long.MAX_VALUE,System::nanoTime));
            while(!cursor.step(2,Long.MAX_VALUE,System::nanoTime)){}
            var scene=cursor.result();double height=y==0?-.5:16.5;
            assertFalse(new VoxelTracer(scene,new VoxelTracer.Limits(100,100,100)).trace(new Ray(new Vec3(.5,height,-1),new Vec3(0,0,1)),4,()->false).isEmpty());
        }
    }
    @Test void fluidsRequireExplicitFrozenNeighborStatesEvenInKnownSections() throws Exception {
        RenderFixtures f=new RenderFixtures();f.png("minecraft:block/water_still",1,1,0x800000ff);f.png("minecraft:block/water_flow",1,1,0x800000ff);
        f.json("assets/minecraft/blockstates/water.json","{\"variants\":{\"\":{\"model\":\"test:unused\"}}}");var g=f.generation(1);
        var builder=new SceneSnapshot.Builder(g,0,16,10,10000);
        for(int cx=-1;cx<=1;cx++)for(int cz=-1;cz<=1;cz++)builder.availableSection(cx,0,cz);
        builder.put(0,0,0,RenderFixtures.vanilla("minecraft:water[level=0]",1),new SceneSnapshot.Lighting(15,0,0xffffff),0);
        var broken=builder.finish();
        assertEquals(SceneException.Code.SCENE_INCOMPLETE,assertThrows(SceneException.class,()->broken.step(1000,Long.MAX_VALUE,System::nanoTime)).code());
        assertThrows(IllegalStateException.class,()->broken.step(1000,Long.MAX_VALUE,System::nanoTime));
        assertThrows(IllegalStateException.class,broken::result);
        var valid=new SceneSnapshot.Builder(g,0,16,10,10000);
        for(int cx=-1;cx<=1;cx++)for(int cz=-1;cz<=1;cz++)valid.availableSection(cx,0,cz);
        var air=CanonicalBlockState.parse("minecraft:air");
        for(int x=-1;x<=1;x++)for(int z=-1;z<=1;z++)for(int y=0;y<=1;y++)valid.neighbor(x,y,z,air,false);
        valid.put(0,0,0,RenderFixtures.vanilla("minecraft:water[level=0]",1),new SceneSnapshot.Lighting(15,0,0xffffff),0);
        var scene=valid.build();assertFalse(scene.instances().getFirst().models().getFirst().quads().isEmpty());
        var hits=new VoxelTracer(scene,new VoxelTracer.Limits(100,100,100)).trace(new Ray(new Vec3(.5,2,.5),new Vec3(0,-1,0)),4,()->false);
        assertEquals(1,hits.stream().filter(h->h.t()<2).count(),"共享对角不能重复累积透明度");
    }

    @Test void limitReportsTheSpecificBudgetActualCountAndMaximum()throws Exception {
        RenderFixtures f=new RenderFixtures();f.png("test:tile",1,1,0xffffffff);f.cube("test:model","test:tile");f.block("test:block","test:model");var g=f.generation(1);
        var instances=new SceneSnapshot.Builder(g,0,16,1,100);instances.availableSection(0,0,0);
        instances.put(0,0,0,RenderFixtures.vanilla("test:block",1),new SceneSnapshot.Lighting(15,0,0xffffff),0);
        var error=assertThrows(SceneException.class,()->instances.put(1,0,0,RenderFixtures.vanilla("test:block",1),new SceneSnapshot.Lighting(15,0,0xffffff),0));
        assertTrue(error.getMessage().contains("maximumInstances"));assertTrue(error.getMessage().contains("请求=2"));assertTrue(error.getMessage().contains("上限=1"));
        var index=new SceneSnapshot.Builder(g,0,16,10,2);index.availableSection(0,0,0);
        index.put(0,0,0,RenderFixtures.vanilla("test:block",1),new SceneSnapshot.Lighting(15,0,0xffffff),0);
        var overflow=assertThrows(SceneException.class,index::build);assertTrue(overflow.getMessage().contains("maximumIndexEntries"));
    }

    @Test void approvedInstanceLimitAccepts8192AndRejectsThe8193rdWithoutGrowingUnbounded()throws Exception {
        RenderFixtures f=new RenderFixtures();f.png("test:tile",1,1,0xffffffff);f.cube("test:model","test:tile");f.block("test:block","test:model");var assets=f.generation(1);
        var builder=new SceneSnapshot.Builder(assets,0,16,8192,65536);var descriptor=RenderFixtures.vanilla("test:block",1);var light=new SceneSnapshot.Lighting(15,0,0xffffff);
        for(int x=0;x<8192;x++)builder.put(x,0,0,descriptor,light,0);
        var error=assertThrows(SceneException.class,()->builder.put(8192,0,0,descriptor,light,0));assertTrue(error.getMessage().contains("请求=8193"));assertTrue(error.getMessage().contains("上限=8192"));
    }

    @Test void sparseSectionHeadsAreChargedBeforeCompactIndexAllocation()throws Exception {
        RenderFixtures f=new RenderFixtures();f.png("test:tile",1,1,0xffffffff);f.cube("test:model","test:tile");f.block("test:block","test:model");var assets=f.generation(1);
        var budget=new com.wenkrang.famara.render.schedule.RenderMemoryBudget(16*1024*1024);
        try(var scope=new com.wenkrang.famara.render.schedule.MemoryScope(budget,"scene",6*1024*1024)) {
            var builder=new SceneSnapshot.Builder(assets,0,16,1000,10000).memory(scope);
            for(int x=0;x<128;x++){builder.availableSection(x,0,0);builder.put(x*16,0,0,RenderFixtures.vanilla("test:block",1),new SceneSnapshot.Lighting(15,0,0xffffff),0);}
            assertThrows(java.util.concurrent.RejectedExecutionException.class,builder::build);
        }
        assertEquals(0,budget.snapshot().live());
    }
}
