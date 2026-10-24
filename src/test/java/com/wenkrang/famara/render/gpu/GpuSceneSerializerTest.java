package com.wenkrang.famara.render.gpu;

import com.wenkrang.famara.render.asset.*;
import com.wenkrang.famara.render.kernel.*;
import com.wenkrang.famara.render.math.*;
import com.wenkrang.famara.render.scene.*;
import com.wenkrang.famara.render.schedule.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;
import static com.wenkrang.famara.render.gpu.GpuScene.BufferKind.*;

class GpuSceneSerializerTest {
    static RenderFixtures assets(int width)throws Exception {
        var f=new RenderFixtures();f.png("test:tile",width,width,0xffff0000,0xff00ff00,0xff0000ff,0xffffffff);f.cube("test:cube","test:tile");f.block("test:block","test:cube");return f;
    }
    static SceneSnapshot scene(AssetGeneration assets,int x,int z) {
        var b=new SceneSnapshot.Builder(assets,0,32,100,20000);
        for(int cx=(x>>4)-1;cx<=(x>>4)+1;cx++)for(int cz=(z>>4)-1;cz<=(z>>4)+1;cz++)for(int cy=0;cy<2;cy++)b.availableSection(cx,cy,cz);
        for(int dx=0;dx<2;dx++)b.put(x+dx,0,z,RenderFixtures.vanilla("test:block",assets.id()),new SceneSnapshot.Lighting(15,0,0xffffff),0);
        return b.build();
    }
    static Camera camera(int x,int z){return new Camera(new Vec3(x+.5,.5,z-2),Math.PI/2,0,Camera.Projection.LEGACY_ANGULAR,1);}
    static CpuPhotoRenderer.Settings settings(){return new CpuPhotoRenderer.Settings(8,1,Texture.Filter.MIPMAP,true,.3,.5,32,0x123456,0,new VoxelTracer.Limits(10000,1000,10000),Map.of());}
    @Test void packedIndexMatchesReferenceAtNegativeAndLargeCoordinatesAndKeepsMissingSectionsDistinct()throws Exception {
        var assets=assets(2).generation(1);var memory=new RenderMemoryBudget(536870912);
        for(int origin:new int[]{-17,4_100_000}) {
            var scene=scene(assets,origin,-17);
            try(var packed=GpuSceneSerializer.serialize(scene,camera(origin,-17),settings(),GpuConfiguration.disabled(),memory)) {
                var random=new Random(0x62);var index=scene.indexCursor();
                for(int i=0;i<1000;i++) {
                    int x=origin+random.nextInt(9)-4,y=random.nextInt(5),z=-17+random.nextInt(9)-4;
                    index.enter(x,y,z);var ids=new ArrayList<Integer>();while(index.hasNext())ids.add(index.next().id());
                    assertArrayEquals(ids.stream().mapToInt(Integer::intValue).toArray(),packed.candidates(x,y,z));
                }
                assertThrows(SceneException.class,()->packed.candidates(origin+1000,0,-17));
                assertTrue(packed.buffer(FACES_FLOAT).isReadOnly());
                var first=scene.instances().getFirst().models().getFirst().quads().getFirst().positions().getFirst();
                assertEquals((float)first.x(),packed.floats(FACES_FLOAT).get(0),1e-6);assertEquals(.5f,packed.floats(HEADER_FLOAT).get(0));
                assertEquals(12,packed.metadata().faces());assertEquals(1,packed.metadata().textures());
            }
            assertEquals(0,memory.snapshot().live());
        }
    }
    @Test void texturePyramidsAreCopiedAsPremultipliedLinearFloatsWithoutChangingReferenceStorage()throws Exception {
        var f=assets(2);var generation=f.generation(1);var texture=generation.texture(ResourceKey.parse("test:tile"));var memory=new RenderMemoryBudget(536870912);
        try(var packed=GpuSceneSerializer.serialize(scene(generation,0,0),camera(0,0),settings(),GpuConfiguration.disabled(),memory)) {
            var pixels=packed.floats(TEXELS);assertEquals(1,pixels.get(0));assertEquals(0,pixels.get(1));assertEquals(1,pixels.get(3));
            var levels=packed.ints(LEVELS);assertEquals(2,levels.get(1));assertEquals(2,levels.get(2));assertEquals(1,levels.get(5));
            var expected=texture.sample(.5,.5,1,0,Texture.Filter.MIPMAP);int offset=levels.get(4);
            assertEquals(expected.r(),pixels.get(offset),1e-7);assertEquals(expected.g(),pixels.get(offset+1),1e-7);assertEquals(expected.b(),pixels.get(offset+2),1e-7);
        }
        assertEquals(1,texture.sample(.25,.25,0,0,Texture.Filter.NEAREST).r());assertEquals(0,memory.snapshot().live());
    }
    @Test void slicedCancellationAndBudgetRefusalReleaseAllStagingWithoutChangingCpuScene()throws Exception {
        var scene=scene(assets(2).generation(1),0,0);var memory=new RenderMemoryBudget(536870912);var cancelled=new AtomicBoolean();
        try(var cursor=GpuSceneSerializer.cursor(scene,camera(0,0),settings(),GpuConfiguration.disabled(),memory,cancelled::get)) {
            assertFalse(cursor.step(1,Long.MAX_VALUE,System::nanoTime));cancelled.set(true);
            assertEquals(BackendException.Code.CANCELLED,assertThrows(BackendException.class,()->cursor.step(100,Long.MAX_VALUE,System::nanoTime)).code());
        }
        assertEquals(0,memory.snapshot().live());
        var tiny=new RenderMemoryBudget(1024);
        assertEquals(BackendException.Code.OUT_OF_MEMORY,assertThrows(BackendException.class,()->GpuSceneSerializer.serialize(scene,camera(0,0),settings(),GpuConfiguration.disabled(),tiny)).code());
        assertEquals(0,tiny.snapshot().live());assertFalse(new VoxelTracer(scene,settings().traceLimits()).trace(camera(0,0).ray(64,64,.5,.5),8,()->false).isEmpty());
    }
    @Test void borrowedNativeOwnerPinsArenaAfterCallerCancellation()throws Exception {
        var memory=new RenderMemoryBudget(536870912);var packed=GpuSceneSerializer.serialize(scene(assets(2).generation(1),0,0),camera(0,0),settings(),GpuConfiguration.disabled(),memory);
        var borrowed=packed.retain();var view=packed.buffer(HEADER_INT);packed.close();assertTrue(memory.snapshot().live()>0);assertEquals(GpuScene.VERSION,view.order(java.nio.ByteOrder.nativeOrder()).getInt(0));
        borrowed.close();borrowed.close();assertEquals(0,memory.snapshot().live());assertThrows(IllegalStateException.class,()->view.getInt(0));assertThrows(IllegalStateException.class,packed::retain);
    }
    @Test void vramLimitIsCheckedBeforeDirectBuffersAreAllocated()throws Exception {
        var scene=scene(assets(256).generation(1),0,0);var memory=new RenderMemoryBudget(536870912);
        var config=new GpuConfiguration(GpuConfiguration.Mode.OPENCL,"Quadro P620",1,1,64,1,10000,true,1.2);
        assertEquals(BackendException.Code.OUT_OF_MEMORY,assertThrows(BackendException.class,()->GpuSceneSerializer.serialize(scene,camera(0,0),settings(),config,memory)).code());
        assertEquals(0,memory.snapshot().live());
    }
}
