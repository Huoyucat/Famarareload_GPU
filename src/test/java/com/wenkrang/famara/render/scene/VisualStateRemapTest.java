package com.wenkrang.famara.render.scene;

import com.wenkrang.famara.render.asset.*;
import com.wenkrang.famara.render.kernel.*;
import com.wenkrang.famara.render.math.Vec3;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** 合成CE覆盖定义复现普通树叶distance=4在客户端被映到distance=7，不读取真实服素材。 */
class VisualStateRemapTest {
    private static final CanonicalBlockState SERVER=CanonicalBlockState.parse("minecraft:oak_leaves[distance=4,persistent=false,waterlogged=false]");
    private static final CanonicalBlockState CLIENT=CanonicalBlockState.parse("minecraft:oak_leaves[distance=7,persistent=true,waterlogged=false]");
    private final VersionStateAdapter version=new VersionStateAdapter(VersionStateAdapter.Version.parse("26.3"),VersionStateAdapter.Version.parse("26.3"));
    private AssetGeneration assets(boolean override) throws Exception {
        RenderFixtures f=new RenderFixtures();f.png("test:leaf",2,2,0xff00ff00,0xffffff00,0xff00ff00,0xffffff00);f.png("test:custom",1,1,0xff0000ff);
        f.cube("test:leaf_model","test:leaf");f.cube("test:custom_model","test:custom");f.block("minecraft:oak_leaves","test:leaf_model");
        ResourcePack base=f.pack().withRole(ResourcePack.Role.BASE);
        if(!override)return AssetGeneration.compile(1,base,RenderFixtures.LIMITS);
        RenderFixtures custom=new RenderFixtures();custom.json("assets/minecraft/blockstates/oak_leaves.json","""
                {"variants":{
                  "distance=7,persistent=true,waterlogged=false":{"model":"test:leaf_model"},
                  "distance=1,persistent=true,waterlogged=false":{"model":"test:custom_model"}
                }}
                """);
        return AssetGeneration.compile(1,ResourcePack.layer(List.of(custom.pack().withRole(ResourcePack.Role.CUSTOM),base),RenderFixtures.LIMITS),RenderFixtures.LIMITS);
    }
    @Test void vanillaBaseDefinitionStillMatchesAllNonvisualLeafPropertiesWithoutCe() throws Exception {
        var resources=assets(false);var table=new VisualStateTable(1,List.of(),100);
        var resolved=table.resolve(SERVER,resources,version);assertEquals(SERVER,resolved.descriptor().visualState());
        assertEquals(ResourceKey.parse("test:leaf_model"),resources.resolve(resolved.descriptor(),0,0,0,0).getFirst().key());
    }
    @Test void partialBlockstateOverridePreservesUnassignedBaseStatesLikeTheClientStack() throws Exception {
        var resources=assets(true);var table=new VisualStateTable(1,List.of(),100);
        var resolved=table.resolve(SERVER,resources,version);
        assertEquals(ResourceKey.parse("test:leaf_model"),resources.resolve(resolved.descriptor(),0,0,0,0).getFirst().key());
        assertFalse(resources.usesCustomResources(resolved.descriptor(),0,0,0,0));
    }
    @Test void frozenVanillaRemapUsesCustomPackRulesAndSamplesActualLeafTexture() throws Exception {
        var resources=assets(true);var table=new VisualStateTable(1,List.of(),List.of(new VisualStateTable.VanillaRemap(SERVER,CLIENT)),100);
        var resolved=table.resolve(SERVER,resources,version);
        assertEquals(VisualBlockDescriptor.Kind.VANILLA,resolved.descriptor().kind());assertEquals(CLIENT,resolved.descriptor().visualState());
        assertEquals(1,table.vanillaRemapCount());assertFalse(resolved.customResourceRequired());
        assertTrue(resources.usesCustomResources(resolved.descriptor(),0,0,0,0));
        var builder=new SceneSnapshot.Builder(resources,0,16,10,1000);
        for(int x=-1;x<=1;x++)for(int z=-1;z<=1;z++)builder.availableSection(x,0,z);
        builder.put(0,0,0,resolved.descriptor(),new SceneSnapshot.Lighting(15,0,0xffffff),0);
        var renderer=new CpuPhotoRenderer(builder.build(),new Camera(new Vec3(.5,.5,-2),Math.PI/2,0,Camera.Projection.LEGACY_ANGULAR,1),
                new CpuPhotoRenderer.Settings(8,1,Texture.Filter.NEAREST,false,0,.5,10,0,0,new VoxelTracer.Limits(1000,1000,1000),Map.of()));
        var a=renderer.sampleRay(new Ray(new Vec3(.75,.75,-2),new Vec3(0,0,1)),()->false);
        var b=renderer.sampleRay(new Ray(new Vec3(.25,.75,-2),new Vec3(0,0,1)),()->false);
        assertEquals(1,a.g(),1e-6);assertEquals(1,b.g(),1e-6);assertNotEquals(a.r(),b.r(),"不能用同一个平均色代替两处UV");
    }
    @Test void customIdentityTakesPriorityAndVisualTargetsAreNotRecursivelyRemapped() throws Exception {
        var resources=assets(true);var nativeCustom=CanonicalBlockState.parse("test:native_block[axis=x]");
        var reserved=CanonicalBlockState.parse("minecraft:oak_leaves[distance=1,persistent=true,waterlogged=false]");
        var entry=new VisualStateTable.Entry(nativeCustom,ResourceKey.parse("test:custom_leaf"),null,reserved,ResourceKey.parse("minecraft:oak_leaves"));
        var alias=new VisualStateTable.Entry(SERVER,ResourceKey.parse("test:reserved_slot"),null,reserved,ResourceKey.parse("minecraft:oak_leaves"));
        var table=new VisualStateTable(1,List.of(entry,alias),List.of(new VisualStateTable.VanillaRemap(SERVER,CLIENT),new VisualStateTable.VanillaRemap(reserved,CLIENT)),100);
        var custom=table.resolve(nativeCustom,resources,version);
        assertEquals(VisualBlockDescriptor.Kind.CE,custom.descriptor().kind());assertEquals(reserved,custom.descriptor().visualState());
        assertEquals(ResourceKey.parse("test:custom_model"),resources.resolve(custom.descriptor(),0,0,0,0).getFirst().key());
        assertEquals(CLIENT,table.resolve(reserved,resources,version).descriptor().visualState());
        assertEquals(VisualBlockDescriptor.Kind.CE,table.resolve(SERVER,resources,version).descriptor().kind());
        assertEquals(reserved,table.resolve(SERVER,resources,version).descriptor().visualState());
    }
    @Test void conflictingOrOverbudgetMappingsNeverPublishAnArbitraryTarget() {
        var one=new VisualStateTable.VanillaRemap(SERVER,CLIENT);var two=new VisualStateTable.VanillaRemap(SERVER,CanonicalBlockState.parse("minecraft:stone"));
        assertThrows(IllegalArgumentException.class,()->new VisualStateTable(1,List.of(),List.of(one,two),100));
        assertThrows(IllegalArgumentException.class,()->new VisualStateTable(1,List.of(),List.of(one,two),1));
    }
}
