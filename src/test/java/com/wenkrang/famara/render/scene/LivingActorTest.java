package com.wenkrang.famara.render.scene;

import com.wenkrang.famara.render.asset.*;
import com.wenkrang.famara.render.math.*;
import com.wenkrang.famara.render.kernel.*;
import com.wenkrang.famara.render.gpu.*;
import com.wenkrang.famara.render.schedule.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

public class LivingActorTest {
    public static FrozenActor actor(String type,double x,double z){return new FrozenActor(UUID.randomUUID(),type,new Vec3(x,0,z),180,180,0,"STANDING",false,1,0,"",0xffffff,"",false,false,15,0);}
    public static Texture texture()throws Exception{var f=new RenderFixtures();f.png("test:skin",64,64,0xffffa040);return Texture.decode(f.pack(),ResourceKey.parse("test:skin"),RenderFixtures.LIMITS);}
    public static AssetGeneration assets()throws Exception{var f=new RenderFixtures();f.png("test:solid",1,1,0xff335577);f.cube("test:cube","test:solid");f.block("test:block","test:cube");return f.generation(1);}
    public static SceneSnapshot scene(String type,boolean wall)throws Exception{
        var b=new SceneSnapshot.Builder(assets(),-16,32,100,20000);
        for(int cx=-2;cx<=2;cx++)for(int cz=-2;cz<=2;cz++)for(int cy=-1;cy<2;cy++)b.availableSection(cx,cy,cz);
        var a=actor(type,0,0);var key=ResourceKey.parse("test:skin");var texture=texture();b.actor(type.equals("PLAYER")?PlayerModelRenderer.bake(a,key,texture):VanillaMobModels.bake(a,key,texture,key,texture));
        if(wall)for(int x=-2;x<=2;x++)for(int y=0;y<4;y++)b.put(x,y,-1,RenderFixtures.vanilla("test:block",1),new SceneSnapshot.Lighting(15,0,0xffffff),0);
        return b.build();
    }
    public static Camera camera(){return new Camera(new Vec3(0,1,-4),Math.PI/2,0,Camera.Projection.PERSPECTIVE,Math.PI/3);}
    public static CpuPhotoRenderer.Settings settings(){return new CpuPhotoRenderer.Settings(12,1,Texture.Filter.NEAREST,true,.3,.5,32,0x123456,0,new VoxelTracer.Limits(10000,1000,10000),Map.of());}
    @Test void actorsUseIndependentTexturesAndCanShareTheSameHostCell()throws Exception{
        var a=actor("PLAYER",.5,.5);var texture=texture();var key=ResourceKey.parse("test:skin");var b=new SceneSnapshot.Builder(assets(),-16,32,100,20000);
        for(int x=-1;x<=1;x++)for(int z=-1;z<=1;z++)for(int y=-1;y<=1;y++)b.availableSection(x,y,z);
        b.put(0,0,0,RenderFixtures.vanilla("test:block",1),new SceneSnapshot.Lighting(15,0,0xffffff),0);
        b.actor(PlayerModelRenderer.bake(a,key,texture));b.actor(PlayerModelRenderer.bake(actor("PLAYER",.2,.3),key,texture));
        var releases=new AtomicInteger();b.actorResources(releases::incrementAndGet);var scene=b.build();assertEquals(3,scene.instances().size());assertEquals(2,scene.actorCount());assertSame(texture,scene.texture(key));
        var index=scene.indexCursor();index.enter(0,1,0);int actors=0;while(index.hasNext()){index.next();actors++;}assertTrue(actors>=2,"limbs and heads must be indexed beyond their feet cell");scene.close();scene.close();assertEquals(1,releases.get());
    }
    @Test void playerAndEveryRegisteredMobRenderThroughCpuAndGpuSerializer()throws Exception{
        for(String type:List.of("PLAYER","ZOMBIE","HUSK","DROWNED","SKELETON","STRAY","BOGGED","WITHER_SKELETON","CREEPER","COW","PIG","SHEEP","CHICKEN","SPIDER","CAVE_SPIDER","ENDERMAN")){
            try(var scene=scene(type,false)){
                var face=scene.instances().getFirst().models().getFirst().quads().getFirst();
                double targetX=face.positions().stream().mapToDouble(Vec3::x).average().orElseThrow(),targetY=face.positions().stream().mapToDouble(Vec3::y).average().orElseThrow();
                var ray=new Ray(new Vec3(targetX,targetY,-4),new Vec3(0,0,1));
                assertFalse(new VoxelTracer(scene,settings().traceLimits()).trace(ray,12,()->false).isEmpty(),type);
                var hit=new CpuPhotoRenderer(scene,camera(),settings()).sampleRay(ray,()->false);assertTrue(hit.r()>hit.b(),type);
                var memory=new RenderMemoryBudget(536870912);try(var packed=GpuSceneSerializer.serialize(scene,camera(),settings(),GpuConfiguration.disabled(),memory)){assertTrue(packed.metadata().faces()>=30,type);assertEquals(1,packed.metadata().textures());}assertEquals(0,memory.snapshot().live());
            }
        }
    }
    @Test void actorSkyLightingUsesTheSameFrozenWorldDarkeningAsBlocks()throws Exception{
        var a=actor("PLAYER",0,0);var key=ResourceKey.parse("test:skin");var b=new SceneSnapshot.Builder(assets(),-16,32,100,20000);for(int x=-1;x<=1;x++)for(int z=-1;z<=1;z++)for(int y=-1;y<=1;y++)b.availableSection(x,y,z);b.actor(PlayerModelRenderer.bake(a,key,texture()),11);try(var scene=b.build()){assertEquals(4,scene.instances().getFirst().lighting().sky());assertEquals(0,scene.instances().getFirst().lighting().block());}
    }
    @Test void opaqueWallOccludesTheActorUsingTheSameDepthPath()throws Exception{
        try(var clear=scene("PLAYER",false);var wall=scene("PLAYER",true)){
            var ray=new Ray(new Vec3(0,1,-4),new Vec3(0,0,1));var a=new CpuPhotoRenderer(clear,camera(),settings()).sampleRay(ray,()->false);var b=new CpuPhotoRenderer(wall,camera(),settings()).sampleRay(ray,()->false);assertNotEquals(a,b);
            var first=new VoxelTracer(wall,settings().traceLimits()).trace(ray,12,()->false).getFirst();assertEquals("test:block",first.instance().descriptor().visualState().block().toString());
        }
    }
}
