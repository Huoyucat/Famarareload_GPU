package com.wenkrang.famara.render.kernel;

import com.wenkrang.famara.render.asset.*;
import com.wenkrang.famara.render.math.*;
import com.wenkrang.famara.render.scene.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ConservativeOcclusionTest {
    private record Cell(int x,int y,int z,String state) {}
    private static final VoxelTracer.Limits TRACE=new VoxelTracer.Limits(10000,1000,10000);
    private AssetGeneration assets()throws Exception {
        RenderFixtures f=new RenderFixtures();f.png("test:solid",2,2,0xffaa6633,0xff55aa44);f.png("test:glass",2,2,0x800000ff,0x8000ff00);f.png("test:hole",2,2,0,0xff00ff00,0xff00ff00,0);
        for(String name:List.of("solid","glass","hole")){f.cube("test:"+name,"test:"+name);f.block("test:"+name,"test:"+name);}return f.generation(1);
    }
    private SceneSnapshot scene(AssetGeneration assets,List<Cell> cells,Camera camera,int samples,boolean cull) {
        ConservativeOcclusion visibility=new ConservativeOcclusion(camera,samples,16);Map<String,Cell> map=new HashMap<>();cells.forEach(c->map.put(c.x+","+c.y+","+c.z,c));
        visibility.beginUpdate();while(!visibility.update((x,y,z)->{Cell cell=map.get(x+","+y+","+z);if(cell==null)return 0;return OpaqueShapes.fullCube(assets.resolve(RenderFixtures.vanilla(cell.state,1),x,y,z,0),assets)?1:0;},512,Long.MAX_VALUE)){}
        var builder=new SceneSnapshot.Builder(assets,-16,32,200,20000);
        for(int x=-2;x<=2;x++)for(int z=-2;z<=2;z++)for(int y=-1;y<2;y++)builder.availableSection(x,y,z);
        for(Cell cell:cells)if(!cull || !visibility.hidden(cell.x-1e-6,cell.y-1e-6,cell.z-1e-6,cell.x+1.000001,cell.y+1.000001,cell.z+1.000001))
            builder.put(cell.x,cell.y,cell.z,RenderFixtures.vanilla(cell.state,1),new SceneSnapshot.Lighting(15,0,0xffffff),0);
        return builder.build();
    }
    private int[] render(SceneSnapshot scene,Camera camera,int samples,Texture.Filter filter) {
        var renderer=new CpuPhotoRenderer(scene,camera,new CpuPhotoRenderer.Settings(16,samples,filter,false,0,.5,32,0x556677,0,TRACE,Map.of()));var cursor=renderer.cursor();
        while(renderer.renderBatch(cursor,256,Long.MAX_VALUE,System::nanoTime,()->false).status()!=CpuPhotoRenderer.Status.DONE){}return cursor.copyRgb();
    }
    @Test void opaqueWallCullsItsHiddenObjectsWithoutChangingAnyFinalSample()throws Exception {
        var assets=assets();List<Cell> cells=new ArrayList<>();for(int x=-2;x<=2;x++)for(int y=-2;y<=2;y++)cells.add(new Cell(x,y,1,"test:solid"));cells.add(new Cell(0,0,3,"test:glass"));
        for(var projection:Camera.Projection.values())for(int samples:new int[]{1,4}) {
            var camera=new Camera(new Vec3(.5,.5,-3),Math.PI/2,0,projection,1);var full=scene(assets,cells,camera,samples,false);var compact=scene(assets,cells,camera,samples,true);
            assertTrue(compact.instances().size()<full.instances().size());
            for(var filter:List.of(Texture.Filter.NEAREST,Texture.Filter.MIPMAP))assertArrayEquals(render(full,camera,samples,filter),render(compact,camera,samples,filter),projection+" samples="+samples+" filter="+filter);
        }
    }
    @Test void glassAndLeafHolesNeverEraseTheObjectsBehindThem()throws Exception {
        var assets=assets();
        for(var projection:Camera.Projection.values())for(int samples:new int[]{1,4}) {
            var camera=new Camera(new Vec3(.5,.5,-2),Math.PI/2,0,projection,1);
            for(String front:List.of("test:glass","test:hole")) {
                var cells=List.of(new Cell(0,0,0,front),new Cell(0,0,2,"test:solid"),new Cell(1,0,1,"test:glass"));
                var full=scene(assets,cells,camera,samples,false);var compact=scene(assets,cells,camera,samples,true);
                assertEquals(full.instances().size(),compact.instances().size());
                for(var filter:List.of(Texture.Filter.NEAREST,Texture.Filter.MIPMAP))assertArrayEquals(render(full,camera,samples,filter),render(compact,camera,samples,filter));
            }
        }
    }
    @Test void unknownCellsAndInsideOrCoincidentBoundsAreNotOcclusionEvidence() {
        var camera=new Camera(new Vec3(.5,.5,.5),0,0,Camera.Projection.LEGACY_ANGULAR,1);var visibility=new ConservativeOcclusion(camera,1,16);
        while(!visibility.update((x,y,z)->-1,1024,Long.MAX_VALUE)){}
        assertFalse(visibility.hidden(3,0,0,4,1,1));assertFalse(visibility.hidden(0,0,0,1,1,1));
        visibility.beginUpdate();while(!visibility.update((x,y,z)->x==2?1:0,1024,Long.MAX_VALUE)){}
        assertFalse(visibility.hidden(2,0,0,3,1,1));assertTrue(visibility.hidden(5,0,0,6,1,1));
    }

    @Test void incrementalFrontiersMatchFullViewsWithoutRewalkingKnownTransparentCells() {
        for(var projection:Camera.Projection.values())for(int samples:new int[]{1,4}) {
            Camera camera=new Camera(new Vec3(-.5,.5,-.5),.21,-.12,projection,1);
            var incremental=new ConservativeOcclusion(camera,samples,32);var full=new ConservativeOcclusion(camera,samples,32);
            for(int radius=0;radius<=34;radius+=2) {
                final int available=radius;
                incremental.beginUpdate();while(!incremental.update((x,y,z)->{
                    if(Math.max(Math.abs(x),Math.max(Math.abs(y),Math.abs(z)))>available)return -1;
                    return x==12?1:0;
                },1009,Long.MAX_VALUE)){}
            }
            while(!full.update((x,y,z)->x==12?1:0,1009,Long.MAX_VALUE)){}
            for(int x=-30;x<=30;x+=3)for(int y=-15;y<=15;y+=5)for(int z=-30;z<=30;z+=3)
                assertEquals(full.hidden(x,y,z,x+1,y+1,z+1),incremental.hidden(x,y,z,x+1,y+1,z+1));
            assertEquals(full.progress().advanced(),incremental.progress().advanced());
            long visits=incremental.progress().visits();incremental.beginUpdate();while(!incremental.update((x,y,z)->{throw new AssertionError("已完成样本不得重读");},1024,Long.MAX_VALUE)){}
            assertEquals(visits,incremental.progress().visits());assertEquals(16384*samples,incremental.progress().completed());
        }
    }
    @Test void unknownFrontierAndTinySlicesPreserveNegativeBoundaryAndPolarSemantics() {
        for(double yaw:new double[]{0,Math.PI/2,Math.PI})for(double pitch:new double[]{0,Math.PI/2,-Math.PI/2}) {
            Camera camera=new Camera(new Vec3(-16,0,-16),yaw,pitch,Camera.Projection.PERSPECTIVE,1);
            var small=new ConservativeOcclusion(camera,1,4);var large=new ConservativeOcclusion(camera,1,4);
            while(!small.update((x,y,z)->-1,17,Long.MAX_VALUE)){}
            small.beginUpdate();while(!small.update((x,y,z)->y==-2?1:0,17,Long.MAX_VALUE)){}
            while(!large.update((x,y,z)->y==-2?1:0,65536,Long.MAX_VALUE)){}
            assertEquals(large.progress().advanced(),small.progress().advanced());
            assertEquals(large.hidden(-17,-4,-17,-15,-3,-15),small.hidden(-17,-4,-17,-15,-3,-15));
            assertFalse(small.hidden(-17,-1,-17,-15,1,-15));
        }
    }


    @Test void completedSamplesLeaveTheActiveListAndUnknownSamplesKeepTheirFrontier() {
        var camera=new Camera(new Vec3(.5,.5,.5),0,0,Camera.Projection.LEGACY_ANGULAR,1);var occlusion=new ConservativeOcclusion(camera,4,8);int[] visits={0};
        while(!occlusion.update((x,y,z)->{visits[0]++;return -1;},1,Long.MAX_VALUE)){}assertEquals(0,occlusion.progress().completed());occlusion.beginUpdate();
        while(!occlusion.update((x,y,z)->{visits[0]++;return 1;},1,Long.MAX_VALUE)){}assertEquals(65536,occlusion.progress().completed());int before=visits[0];occlusion.beginUpdate();assertTrue(occlusion.update((x,y,z)->{throw new AssertionError("完成射线不可再读");},1,Long.MAX_VALUE));assertEquals(before,visits[0]);assertEquals(65536,occlusion.progress().processed());
    }
}
