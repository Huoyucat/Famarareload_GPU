package com.wenkrang.famara.render.scene;

import com.wenkrang.famara.render.asset.*;
import com.wenkrang.famara.render.kernel.*;
import com.wenkrang.famara.render.math.*;
import com.wenkrang.famara.render.schedule.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class FarLodTest {
    private AssetGeneration assets()throws Exception {
        RenderFixtures f=new RenderFixtures();f.png("test:stone",1,1,0xff778899);f.png("test:glass",2,2,0x800000ff,0);f.png("test:hole",2,2,0xff00ff00,0);
        f.png("minecraft:block/water_still",1,1,0x803f76e4);f.png("minecraft:block/water_flow",1,1,0x803f76e4);
        for(String name:List.of("stone","glass","hole")){f.cube("test:"+name,"test:"+name);f.block("minecraft:"+name,"test:"+name);}f.block("minecraft:water","test:stone");return f.generation(1);
    }
    private CpuPhotoRenderer.Settings settings(int samples,Texture.Filter filter){return new CpuPhotoRenderer.Settings(128,samples,filter,true,.3,.5,32,0x123456,0,new VoxelTracer.Limits(10000,1000,10000),Map.of());}
    private SceneSnapshot.Builder builder(AssetGeneration assets){var b=new SceneSnapshot.Builder(assets,-16,32,100,20000);for(int x=-9;x<=9;x++)for(int z=-9;z<=9;z++)for(int y=-1;y<2;y++)b.availableSection(x,y,z);return b;}
    private int[] render(SceneSnapshot scene,Camera camera,CpuPhotoRenderer.Settings settings){var r=new CpuPhotoRenderer(scene,camera,settings);var c=r.cursor();while(r.renderBatch(c,256,Long.MAX_VALUE,System::nanoTime,()->false).status()!=CpuPhotoRenderer.Status.DONE){}return c.copyRgb();}
    @Test void constantOpaqueGroupsPreserveSilhouetteAndRgbForBothProjectionsAndSamplingModes()throws Exception {
        var assets=assets();var descriptor=RenderFixtures.vanilla("minecraft:stone",1);var light=new SceneSnapshot.Lighting(15,0,0xffffff);
        for(var projection:Camera.Projection.values())for(int samples:new int[]{1,4})for(var filter:List.of(Texture.Filter.NEAREST,Texture.Filter.MIPMAP)) {
            var camera=new Camera(new Vec3(.5,.5,0),Math.PI/2,0,projection,1);var settings=settings(samples,filter);var full=builder(assets);var compact=builder(assets);
            for(int x=0;x<2;x++)for(int y=0;y<2;y++)for(int z=96;z<98;z++)full.put(x,y,z,descriptor,light,0);
            var global=new RenderMemoryBudget(536870912);
            try(var memory=new MemoryScope(global,"lod-test",201326592);var lod=new FarLod(camera,settings,new FarLod.Policy(true,64,4),assets,memory)) {
                FarLod.Source source=(x,y,z)->new FarLod.Host(descriptor,FluidMesher.type(descriptor.visualState())!=null?List.of():assets.resolve(descriptor,x,y,z,0),light);
                assertTrue(lod.emit(0,0,96,source,(x,y,z)->true,compact));assertTrue(lod.emit(1,1,97,source,(x,y,z)->true,compact));assertEquals(1,lod.groups());
                var scene=compact.build();assertEquals(1,scene.instances().size());assertNotNull(scene.instances().getFirst().farColors());assertArrayEquals(render(full.build(),camera,settings),render(scene,camera,settings));
            }
            assertEquals(0,global.snapshot().live());
        }
    }
    @Test void nearTransparentHolesFluidAndCeNeverBecomeOpaqueProxies()throws Exception {
        var assets=assets();var camera=new Camera(new Vec3(.5,.5,0),Math.PI/2,0,Camera.Projection.LEGACY_ANGULAR,1);var settings=settings(1,Texture.Filter.MIPMAP);var light=new SceneSnapshot.Lighting(15,0,0xffffff);
        for(String type:List.of("minecraft:glass","minecraft:hole","minecraft:water[level=0]")) {
            var descriptor=RenderFixtures.vanilla(type,1);try(var lod=new FarLod(camera,settings,new FarLod.Policy(true,64,4),assets,null)) {
                assertFalse(lod.emit(0,0,96,(x,y,z)->new FarLod.Host(descriptor,FluidMesher.type(descriptor.visualState())!=null?List.of():assets.resolve(descriptor,x,y,z,0),light),(x,y,z)->true,builder(assets)));
            }
        }
        var vanilla=RenderFixtures.vanilla("minecraft:stone",1);var ce=new VisualBlockDescriptor(VisualBlockDescriptor.Kind.CE,ResourceKey.parse("test:ce"),vanilla.visualState(),1);
        try(var lod=new FarLod(camera,settings,new FarLod.Policy(true,64,4),assets,null)) {
            assertFalse(lod.emit(0,0,20,(x,y,z)->{throw new AssertionError("近处不应进入粗化");},(x,y,z)->true,builder(assets)));
            assertFalse(lod.emit(0,0,96,(x,y,z)->new FarLod.Host(ce,assets.resolve(vanilla,x,y,z,0),light),(x,y,z)->true,builder(assets)));
        }
    }
    @Test void missingMembersAndCameraCrossingFailClosedWithoutConsumingTheGroup()throws Exception {
        var assets=assets();var camera=new Camera(new Vec3(.5,.5,0),Math.PI/2,0,Camera.Projection.PERSPECTIVE,1);var settings=settings(1,Texture.Filter.NEAREST);var d=RenderFixtures.vanilla("minecraft:stone",1);var light=new SceneSnapshot.Lighting(15,0,0xffffff);
        try(var lod=new FarLod(camera,settings,new FarLod.Policy(true,64,4),assets,null)) {assertFalse(lod.emit(0,0,96,(x,y,z)->x==1?null:new FarLod.Host(d,assets.resolve(d,x,y,z,0),light),(x,y,z)->true,builder(assets)));assertEquals(0,lod.groups());}
        assertEquals(Double.POSITIVE_INFINITY,FarLod.projectedPixels(camera,-2,-2,-2));assertFalse(FarLod.farEnough(camera,0,0,0,64));
        assertTrue(FarLod.projectedPixels(camera,-2,0,96)<4);assertTrue(FarLod.projectedPixels(camera,0,0,20)>4);
    }
}
