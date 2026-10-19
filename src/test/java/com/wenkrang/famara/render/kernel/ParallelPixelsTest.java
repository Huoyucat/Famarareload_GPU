package com.wenkrang.famara.render.kernel;

import com.wenkrang.famara.render.asset.*;
import com.wenkrang.famara.render.math.*;
import com.wenkrang.famara.render.scene.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class ParallelPixelsTest {
    private SceneSnapshot scene()throws Exception {
        RenderFixtures f=new RenderFixtures();f.png("test:solid",2,2,0xffcc8844,0xff229944);f.png("test:glass",2,2,0x800000ff,0x8000ff00);f.png("test:hole",2,2,0,0xff00ff00);
        f.png("minecraft:block/water_still",2,2,0x805555ff);f.png("minecraft:block/water_flow",2,2,0x805555ff);
        for(String type:List.of("solid","glass","hole")){f.cube("test:"+type,"test:"+type);f.block("test:"+type,"test:"+type);}f.block("minecraft:water","test:solid");f.block("minecraft:oak_slab","test:solid");
        var assets=f.generation(1);var builder=new SceneSnapshot.Builder(assets,-16,32,100,20000);var air=CanonicalBlockState.parse("minecraft:air");
        for(int x=-2;x<=2;x++)for(int z=-2;z<=2;z++)for(int y=-1;y<2;y++)builder.availableSection(x,y,z);
        for(int x=-4;x<=4;x++)for(int z=-4;z<=8;z++)for(int y=-2;y<=3;y++)builder.neighbor(x,y,z,air,false);
        var light=new SceneSnapshot.Lighting(15,0,0x55aa33,0x3f76e4);
        builder.put(0,0,0,RenderFixtures.vanilla("test:glass",1),light,0);builder.put(1,0,1,RenderFixtures.vanilla("test:hole",1),light,0);
        builder.put(0,0,3,RenderFixtures.vanilla("minecraft:water[level=3]",1),light,0);builder.put(-1,0,3,RenderFixtures.vanilla("minecraft:oak_slab[waterlogged=true]",1),light,0);
        builder.put(0,0,6,new VisualBlockDescriptor(VisualBlockDescriptor.Kind.CE,ResourceKey.parse("test:ce"),CanonicalBlockState.parse("test:solid"),1),light,0);
        return builder.build();
    }
    @Test void singleAndTwoIndependentPixelOwnersHaveIdenticalRgbAcrossTheRenderingMatrix()throws Exception {
        var scene=scene();
        try(var workers=Executors.newFixedThreadPool(2)) {
            for(var projection:Camera.Projection.values())for(int samples:new int[]{1,4})for(var filter:List.of(Texture.Filter.NEAREST,Texture.Filter.MIPMAP)) {
                var camera=new Camera(new Vec3(.5,.5,-3),Math.PI/2,0,projection,1);
                var settings=new CpuPhotoRenderer.Settings(16,samples,filter,true,.3,.5,32,0x556677,0,new VoxelTracer.Limits(10000,1000,10000),Map.of());
                var reference=new CpuPhotoRenderer(scene,camera,settings);var full=reference.cursor();while(reference.renderBatch(full,256,Long.MAX_VALUE,System::nanoTime,()->false).status()!=CpuPhotoRenderer.Status.DONE){}
                CyclicBarrier barrier=new CyclicBarrier(2);List<Future<int[]>> pending=new ArrayList<>();
                for(int lane=0;lane<2;lane++) {
                    final int part=lane;
                    pending.add(workers.submit(()->{var renderer=new CpuPhotoRenderer(scene,camera,settings);var cursor=renderer.cursor(part,2);boolean[] first={true};
                        while(renderer.renderBatch(cursor,256,Long.MAX_VALUE,()->{if(first[0]){first[0]=false;try{barrier.await(3,TimeUnit.SECONDS);}catch(Exception error){throw new CompletionException(error);}}return System.nanoTime();},()->false).status()!=CpuPhotoRenderer.Status.DONE){}return cursor.copyRgb();}));
                }
                int[] merged=new int[16384];for(int lane=0;lane<2;lane++){int[] data=pending.get(lane).get(10,TimeUnit.SECONDS);for(int i=0;i<data.length;i++)merged[i*2+lane]=data[i];}
                assertArrayEquals(full.copyRgb(),merged,projection+" samples="+samples+" filter="+filter);
            }
        }
    }
}
