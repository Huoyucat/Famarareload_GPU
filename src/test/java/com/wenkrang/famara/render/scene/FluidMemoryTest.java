package com.wenkrang.famara.render.scene;

import com.wenkrang.famara.render.asset.*;
import com.wenkrang.famara.render.schedule.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class FluidMemoryTest {
    private AssetGeneration assets()throws Exception {
        RenderFixtures f=new RenderFixtures();f.png("minecraft:block/water_still",1,1,0x803f76e4);f.png("minecraft:block/water_flow",1,1,0x803f76e4);
        f.png("test:tile",1,1,0xffffffff);f.cube("test:model","test:tile");f.block("minecraft:water","test:model");f.block("minecraft:oak_slab","test:model");return f.generation(1);
    }
    @Test void twentyThousandInternalWaterAndWaterloggedHostsDoNotRetainConstructionWindows()throws Exception {
        var assets=assets();var water=CanonicalBlockState.parse("minecraft:water[level=0]");
        for(String host:List.of("minecraft:water[level=0]","minecraft:oak_slab[waterlogged=true]")) {
            var global=new RenderMemoryBudget(536870912);
            try(var scope=new MemoryScope(global,"test",201326592)) {
                var builder=new SceneSnapshot.Builder(assets,-16,64,20480,196608).memory(scope);
                for(int x=-1;x<=2;x++)for(int z=-1;z<=2;z++)for(int y=-1;y<4;y++)builder.availableSection(x,y,z);
                for(int x=-1;x<=32;x++)for(int z=-1;z<=32;z++)for(int y=-1;y<=20;y++)builder.neighbor(x,y,z,water,false);
                var state=CanonicalBlockState.parse(host);var descriptor=VisualBlockDescriptor.vanilla(state,1);
                for(int x=0;x<32;x++)for(int z=0;z<32;z++)for(int y=0;y<20;y++)builder.put(x,y,z,descriptor,new SceneSnapshot.Lighting(15,0,0xffffff,0x123456),0);
                long before=scope.live();var cursor=builder.finish();
                for(int i=0;i<20480;i++)assertFalse(cursor.step(1,Long.MAX_VALUE,System::nanoTime));
                assertEquals(before,scope.live(),"无面动态模型不能累计永久费用");assertTrue(scope.peak()>=before+32768);
                while(!cursor.step(2048,Long.MAX_VALUE,System::nanoTime)){}
                var scene=cursor.result();assertEquals(20480,scene.instances().size());
                for(int i=0;i<scene.instances().size();i++){var instance=scene.instances().get(i);assertEquals(i,instance.id());assertEquals(host.contains("slab")?1:0,instance.models().size());}
                assertEquals(0,cursor.progress().retainedBytes());assertEquals(0,cursor.progress().triangles());
            }
            assertEquals(0,global.snapshot().live());
        }
    }
    @Test void actualVisibleMeshHasBoundedRetainedCostAndFailedBuildReturnsItsTemporaryReservation()throws Exception {
        var assets=assets();var water=CanonicalBlockState.parse("minecraft:water[level=0]");var air=CanonicalBlockState.parse("minecraft:air");
        var global=new RenderMemoryBudget(536870912);
        try(var scope=new MemoryScope(global,"test",201326592)) {
            var b=new SceneSnapshot.Builder(assets,-16,16,2,10000).memory(scope);
            for(int x=-1;x<=0;x++)for(int y=-1;y<=0;y++)for(int z=-1;z<=0;z++)b.availableSection(x,y,z);
            for(int x=-1;x<=1;x++)for(int y=-1;y<=1;y++)for(int z=-1;z<=1;z++)b.neighbor(x,y,z,air,false);
            b.put(0,0,0,VisualBlockDescriptor.vanilla(water,1),new SceneSnapshot.Lighting(15,0,0xffffff,0x123456),0);
            long before=scope.live();var cursor=b.finish();assertFalse(cursor.step(1,Long.MAX_VALUE,System::nanoTime));
            assertEquals(12,cursor.progress().triangles());assertTrue(cursor.progress().retainedBytes()<32768);
            assertEquals(before+cursor.progress().retainedBytes(),scope.live());assertTrue(scope.peak()>=before+32768);
        }
        try(var scope=new MemoryScope(global,"failure",201326592)) {
            var b=new SceneSnapshot.Builder(assets,-16,16,2,10000).memory(scope);b.availableSection(0,0,0);b.put(0,0,0,VisualBlockDescriptor.vanilla(water,1),new SceneSnapshot.Lighting(15,0,0xffffff),0);
            long before=scope.live();var cursor=b.finish();assertThrows(SceneException.class,()->cursor.step(1,Long.MAX_VALUE,System::nanoTime));
            assertEquals(before,scope.live());assertThrows(IllegalStateException.class,cursor::result);assertThrows(IllegalStateException.class,()->cursor.step(1,Long.MAX_VALUE,System::nanoTime));
        }
        assertEquals(0,global.snapshot().live());
    }
    @Test void insufficientConstructionPeakIsRejectedBeforeReadingNeighborView()throws Exception {
        var assets=assets();var global=new RenderMemoryBudget(536870912);
        try(var scope=new MemoryScope(global,"small",32000)) {
            var b=new SceneSnapshot.Builder(assets,0,16,1,10000).memory(scope);b.availableSection(0,0,0);b.put(0,0,0,VisualBlockDescriptor.vanilla(CanonicalBlockState.parse("minecraft:water[level=0]"),1),new SceneSnapshot.Lighting(15,0,0xffffff),0);
            long before=scope.live();var cursor=b.finish();assertThrows(java.util.concurrent.RejectedExecutionException.class,()->cursor.step(1,Long.MAX_VALUE,System::nanoTime));assertEquals(before,scope.live());assertThrows(IllegalStateException.class,cursor::result);
        }
        assertEquals(0,global.snapshot().live());
    }
}
