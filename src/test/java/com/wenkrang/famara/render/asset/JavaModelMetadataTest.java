package com.wenkrang.famara.render.asset;

import com.google.gson.*;
import com.wenkrang.famara.render.kernel.*;
import com.wenkrang.famara.render.math.*;
import com.wenkrang.famara.render.scene.*;
import com.wenkrang.famara.render.schedule.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class JavaModelMetadataTest {
    @TempDir Path directory;
    private static final String PATH="assets/herbalbrews/models/block/stove.json";
    private RenderFixtures fixture()throws Exception {
        var fixture=new RenderFixtures();fixture.png("herbalbrews:block/front",2,2,0xffff0000,0xff00ff00,0xff0000ff,0xffffffff);fixture.png("herbalbrews:block/side",1,1,0xffaa8844);
        fixture.json(PATH,"""
            {"textures":{"front":"herbalbrews:block/front","side":"herbalbrews:block/side"},"elements":[
              {"from":[0,0,0],"to":[16,16,12],"faces":{"north":{"uv":[0,0,16,16],"texture":"#front"},"up":{"texture":"#side"},"south":{"texture":"#side"},"east":{"texture":"#side"},"west":{"texture":"#side"},"down":{"texture":"#side"}}},
              {"from":[3,16,3],"to":[13,20,10],"rotation":{"origin":[8,16,8],"axis":"y","angle":22.5},"faces":{"north":{"uv":[0,0,16,16],"rotation":90,"texture":"#front"},"up":{"texture":"#side"}}}
            ]}
            """);fixture.block("minecraft:note_block","herbalbrews:block/stove");return fixture;
    }
    private JsonObject json(RenderFixtures fixture){return JsonParser.parseString(new String(fixture.files.get(PATH),StandardCharsets.UTF_8)).getAsJsonObject();}
    private BakedModel model(RenderFixtures fixture){return new ModelCompiler(fixture.pack(),RenderFixtures.LIMITS).compile(ResourceKey.parse("herbalbrews:block/stove"));}
    private AssetGeneration generation(RenderFixtures fixture){return AssetGeneration.compile(1,fixture.pack().withRole(ResourcePack.Role.CUSTOM),RenderFixtures.LIMITS);}
    private SceneSnapshot scene(AssetGeneration assets) {
        var builder=new SceneSnapshot.Builder(assets,-16,32,10,10000);for(int x=-1;x<=1;x++)for(int z=-1;z<=1;z++)for(int y=-1;y<2;y++)builder.availableSection(x,y,z);
        builder.put(0,0,0,new VisualBlockDescriptor(VisualBlockDescriptor.Kind.CE,ResourceKey.parse("herbalbrews:stove"),CanonicalBlockState.parse("minecraft:note_block[note=2]"),1),new SceneSnapshot.Lighting(15,0,0xffffff),0);return builder.build();
    }
    private int[] render(SceneSnapshot scene,Camera.Projection projection,int samples,Texture.Filter filter) {
        var renderer=new CpuPhotoRenderer(scene,new Camera(new Vec3(.5,.5,-2),Math.PI/2,0,projection,1),new CpuPhotoRenderer.Settings(16,samples,filter,true,.3,.5,32,0x123456,0,new VoxelTracer.Limits(10000,1000,10000),Map.of()));var cursor=renderer.cursor();while(renderer.renderBatch(cursor,256,Long.MAX_VALUE,System::nanoTime,()->false).status()!=CpuPhotoRenderer.Status.DONE){}return cursor.copyRgb();
    }
    @Test void versionAndCreditDoNotChangeAnyGeometryUvTextureOrPixels()throws Exception {
        var fixture=fixture();var expected=model(fixture);var plain=generation(fixture);var object=json(fixture);object.addProperty("format_version","1.9.0");object.addProperty("credit","Made with Blockbench");fixture.json(PATH,object.toString());var actual=model(fixture);assertEquals(expected,actual);assertEquals(8,actual.quads().size());var declared=generation(fixture);
        for(var projection:Camera.Projection.values())for(int samples:new int[]{1,4})for(var filter:List.of(Texture.Filter.NEAREST,Texture.Filter.MIPMAP))assertArrayEquals(render(scene(plain),projection,samples,filter),render(scene(declared),projection,samples,filter));
    }
    @Test void parentMetadataAndChildTextureOverridesStillResolveNormally()throws Exception {
        var fixture=fixture();var parent=json(fixture);parent.addProperty("format_version","1.21.6");parent.addProperty("credit","Synthetic test author");fixture.json("assets/herbalbrews/models/block/base.json",parent.toString());fixture.json(PATH,"{\"format_version\":\"1.9.0\",\"credit\":\"Child metadata\",\"parent\":\"herbalbrews:block/base\",\"textures\":{\"front\":\"herbalbrews:block/side\"}}");var actual=model(fixture);assertEquals(8,actual.quads().size());assertTrue(actual.quads().stream().allMatch(q->q.texture().toString().equals("herbalbrews:block/side")));
    }
    @Test void malformedMetadataIsRejectedWithoutCoercion()throws Exception {
        for(String version:List.of("null","1.9","{}","[]","true","\"\"","\"one.two\"","\""+"1".repeat(65)+".0\"")) {
            var fixture=fixture();var object=json(fixture);object.add("format_version",JsonParser.parseString(version));fixture.json(PATH,object.toString());var error=assertThrows(AssetException.class,()->model(fixture));assertEquals(AssetException.Code.INVALID,error.code());assertEquals(PATH,error.resource());
        }
        for(JsonElement credit:List.of(JsonNull.INSTANCE,new JsonObject(),new JsonPrimitive(1),new JsonPrimitive("x".repeat(4097)))) {
            var fixture=fixture();var object=json(fixture);object.add("credit",credit);fixture.json(PATH,object.toString());assertEquals(AssetException.Code.INVALID,assertThrows(AssetException.class,()->model(fixture)).code());
        }
    }
    @Test void bedrockGeometryAndUnknownRenderExtensionsRemainUnsupported()throws Exception {
        for(String field:List.of("minecraft:geometry","bones","loader","render_type","unknown_visual_extension")) {
            var fixture=fixture();var object=json(fixture);object.addProperty("format_version","1.12.0");object.add(field,new JsonObject());fixture.json(PATH,object.toString());var error=assertThrows(AssetException.class,()->model(fixture));assertEquals(AssetException.Code.UNSUPPORTED,error.code());assertTrue(error.getMessage().contains(field));
        }
        var fixture=fixture();fixture.json(PATH,"{\"format_version\":\"1.12.0\",\"textures\":{}}");assertEquals(AssetException.Code.UNSUPPORTED,assertThrows(AssetException.class,()->model(fixture)).code());
    }
    @Test void explicitEmptyJavaGeometryIsStillAValidInvisibleModel()throws Exception {
        var fixture=fixture();fixture.json(PATH,"{\"format_version\":\"1.9.0\",\"credit\":\"Intentional empty fixture\",\"elements\":[]}");assertTrue(model(fixture).quads().isEmpty());
    }
    @Test void selectiveAssetLoaderKeepsDeclaredStoveAndItsRealTextureDependencies()throws Exception {
        var fixture=fixture();var object=json(fixture);object.addProperty("format_version","1.9.0");object.addProperty("credit","Made with Blockbench");fixture.json(PATH,object.toString());for(var entry:fixture.files.entrySet()){Path target=directory.resolve(entry.getKey());Files.createDirectories(target.getParent());Files.write(target,entry.getValue());}
        var memory=new RenderMemoryBudget(536870912);try(var repository=new AssetRepository(2);var loader=new BudgetedAssetLoader(repository,RenderFixtures.LIMITS,new BudgetedAssetLoader.Limits(128,1000000,1000000,8000000,10000000000L),new CpuBudget(5000,100,System::nanoTime),memory)) {
            loader.reloadSources(List.of(new BudgetedAssetLoader.Source(directory,ResourcePack.Role.CUSTOM,true)),()->true).toCompletableFuture().get(10,TimeUnit.SECONDS);try(var lease=repository.acquire()){var assets=lease.generation();var descriptor=new VisualBlockDescriptor(VisualBlockDescriptor.Kind.CE,ResourceKey.parse("herbalbrews:stove"),CanonicalBlockState.parse("minecraft:note_block[note=2]"),assets.id());assertTrue(assets.issues().isEmpty());assertTrue(assets.usesCustomResources(descriptor,0,0,0,0));assertEquals(8,assets.resolve(descriptor,0,0,0,0).getFirst().quads().size());}
        }assertEquals(0,memory.snapshot().live());
    }
}
