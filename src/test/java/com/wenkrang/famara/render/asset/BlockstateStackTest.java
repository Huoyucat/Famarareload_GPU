package com.wenkrang.famara.render.asset;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class BlockstateStackTest {
    private RenderFixtures base()throws Exception {
        RenderFixtures f=new RenderFixtures();f.png("test:base",1,1,0xff00ff00);f.png("test:custom",1,1,0xffff0000);
        f.cube("test:base_model","test:base");f.cube("test:custom_model","test:custom");
        f.json("assets/minecraft/blockstates/oak_fence_gate.json","{\"variants\":{\"facing=east,in_wall=false,open=false\":{\"model\":\"test:base_model\"}}}");return f;
    }
    private AssetGeneration layered(RenderFixtures base,String override) {
        RenderFixtures custom=new RenderFixtures();custom.json("assets/minecraft/blockstates/oak_fence_gate.json",override);
        ResourcePack pack=ResourcePack.layer(List.of(custom.pack().withRole(ResourcePack.Role.CUSTOM),base.pack().withRole(ResourcePack.Role.BASE)),RenderFixtures.LIMITS);
        assertEquals(2,pack.blockstateStack("assets/minecraft/blockstates/oak_fence_gate.json").size());
        return AssetGeneration.compile(1,pack,RenderFixtures.LIMITS);
    }
    private VisualBlockDescriptor state(boolean powered){return RenderFixtures.vanilla("minecraft:oak_fence_gate[facing=east,in_wall=false,open=false,powered="+powered+"]",1);}
    @Test void partialOverrideWinsItsStatesAndUnassignedStatesKeepBaseModels()throws Exception {
        var assets=layered(base(),"{\"variants\":{\"powered=true\":{\"model\":\"test:custom_model\"}}}");
        assertEquals(ResourceKey.parse("test:custom_model"),assets.resolve(state(true),0,0,0,0).getFirst().key());
        assertEquals(ResourceKey.parse("test:base_model"),assets.resolve(state(false),0,0,0,0).getFirst().key());
        assertTrue(assets.usesCustomResources(state(true),0,0,0,0));assertFalse(assets.usesCustomResources(state(false),0,0,0,0));
    }
    @Test void matchingMissingHighModelIsNotHiddenByValidBaseModel()throws Exception {
        var assets=layered(base(),"{\"variants\":{\"powered=true\":{\"model\":\"test:missing\"}}}");
        assertEquals(AssetException.Code.MISSING,assertThrows(AssetException.class,()->assets.resolve(state(true),0,0,0,0)).code());
        assertEquals(ResourceKey.parse("test:base_model"),assets.resolve(state(false),0,0,0,0).getFirst().key());
    }
    @Test void malformedHighDefinitionStillFailsInsteadOfPretendingItWasAbsent()throws Exception {
        RenderFixtures base=base();assertThrows(AssetException.class,()->layered(base,"{\"variants\":5}"));
    }
    @Test void highWildcardOverridesMoreSpecificLowerRulesRegardlessOfPropertyCount()throws Exception {
        var assets=layered(base(),"{\"variants\":{\"\":{\"model\":\"test:custom_model\"}}}");
        assertEquals(ResourceKey.parse("test:custom_model"),assets.resolve(state(false),0,0,0,0).getFirst().key());
    }

    @Test void editorColorMetadataDoesNotRecolorGeometryOrMakeZeroSizedCubeVisible()throws Exception {
        RenderFixtures fixture=base();fixture.json("assets/test/models/empty.json","""
                {"textures":{"particle":"test:base"},"elements":[{"from":[0,0,0],"to":[0,0,0],"color":0,
                "faces":{"north":{"texture":"#particle","uv":[0,0,0,0]}}}]}
                """);
        var compiler=new ModelCompiler(fixture.pack(),RenderFixtures.LIMITS);
        assertTrue(compiler.compile(ResourceKey.parse("test:empty")).quads().isEmpty());
        String path="assets/test/models/base_model.json";String model=new String(fixture.files.get(path),java.nio.charset.StandardCharsets.UTF_8);
        fixture.json(path,model.replace("\"from\"","\"color\":0,\"from\""));
        var actual=new ModelCompiler(fixture.pack(),RenderFixtures.LIMITS).compile(ResourceKey.parse("test:base_model"));
        var reference=new ModelCompiler(base().pack(),RenderFixtures.LIMITS).compile(ResourceKey.parse("test:base_model"));
        assertEquals(reference.quads(),actual.quads());
    }
}
