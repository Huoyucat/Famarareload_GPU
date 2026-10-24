package com.wenkrang.famara.render.gpu;

import com.wenkrang.famara.render.asset.*;
import com.wenkrang.famara.render.kernel.*;
import com.wenkrang.famara.render.math.*;
import com.wenkrang.famara.render.scene.*;
import com.wenkrang.famara.render.schedule.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class CpuPhotoRenderBackendTest {
    private SceneSnapshot scene()throws Exception {
        var f=new RenderFixtures();f.png("test:tile",2,2,0xffff0000,0xff00ff00,0xff0000ff,0xffffffff);f.cube("test:cube","test:tile");f.block("test:block","test:cube");var g=f.generation(1);
        var b=new SceneSnapshot.Builder(g,0,32,10,10000);for(int x=-1;x<=1;x++)for(int z=-1;z<=1;z++)for(int y=0;y<2;y++)b.availableSection(x,y,z);
        return b.put(0,0,0,RenderFixtures.vanilla("test:block",1),new SceneSnapshot.Lighting(15,0,0xffffff),0).build();
    }
    private CpuPhotoRenderer.Settings settings(int samples,Texture.Filter filter){return new CpuPhotoRenderer.Settings(8,samples,filter,true,.3,.5,32,0x123456,0,new VoxelTracer.Limits(10000,1000,10000),Map.of());}
    @Test void budgetedAsyncAdapterMatchesEveryReferencePixelAcrossProjectionSamplingAndFiltering()throws Exception {
        var scene=scene();var budget=new CpuBudget(2000,100,System::nanoTime);
        try(var runner=new BudgetedWorkRunner(4,1_000_000,1_000_000,budget);var backend=new CpuPhotoRenderBackend(runner)) {
            for(var projection:Camera.Projection.values())for(int samples:new int[]{1,4})for(var filter:List.of(Texture.Filter.NEAREST,Texture.Filter.MIPMAP)) {
                var camera=new Camera(new Vec3(.5,.5,-2),Math.PI/2,0,projection,1);var settings=settings(samples,filter);
                var reference=new CpuPhotoRenderer(scene,camera,settings);var cursor=reference.cursor();while(reference.renderBatch(cursor,256,Long.MAX_VALUE,System::nanoTime,()->false).status()!=CpuPhotoRenderer.Status.DONE){}
                var result=backend.submitAsync(backend.prepare(scene,camera,settings),()->false).toCompletableFuture().get(20,TimeUnit.SECONDS);
                assertArrayEquals(cursor.copyRgb(),result.rgb());int first=result.rgb()[0];var escaped=result.rgb();escaped[0]^=0xffffff;assertEquals(first,result.rgb()[0]);
            }
        }
    }
    @Test void cancellationAndCloseNeverReturnPublishablePixels()throws Exception {
        var scene=scene();var camera=new Camera(new Vec3(.5,.5,-2),Math.PI/2,0,Camera.Projection.LEGACY_ANGULAR,1);
        try(var runner=new BudgetedWorkRunner(2,1_000_000,1_000_000,new CpuBudget(2000,100,System::nanoTime));var backend=new CpuPhotoRenderBackend(runner)) {
            var prepared=backend.prepare(scene,camera,settings(4,Texture.Filter.MIPMAP));
            var future=backend.submitAsync(prepared,()->true).toCompletableFuture();assertThrows(ExecutionException.class,()->future.get(5,TimeUnit.SECONDS));
            var next=backend.prepare(scene,camera,settings(4,Texture.Filter.MIPMAP));var pending=backend.submitAsync(next,()->false).toCompletableFuture();backend.close();
            assertThrows(ExecutionException.class,()->pending.get(5,TimeUnit.SECONDS));assertFalse(backend.capabilities().available());
        }
    }
}
