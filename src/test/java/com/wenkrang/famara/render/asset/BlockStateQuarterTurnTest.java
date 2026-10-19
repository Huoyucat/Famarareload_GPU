package com.wenkrang.famara.render.asset;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class BlockStateQuarterTurnTest {
    private static final String PATH="assets/minecraft/blockstates/note_block.json";
    private RenderFixtures fixture()throws Exception {
        var f=new RenderFixtures();f.png("test:tile",2,2,0xffff0000,0xff00ff00,0xff0000ff,0xffffffff);
        f.json("assets/test/models/block/asymmetric.json","""
            {"textures":{"all":"test:tile"},"elements":[{"from":[1,2,3],"to":[7,10,14],"faces":{
            "north":{"texture":"#all","cullface":"north"},"south":{"texture":"#all"},
            "east":{"texture":"#all"},"west":{"texture":"#all"},"up":{"texture":"#all"},"down":{"texture":"#all"}}}]}
            """);
        f.cube("test:block/other","test:tile");f.block("test:valid","test:block/asymmetric");return f;
    }
    private BakedModel select(AssetGeneration g,int note,int x){return g.resolve(RenderFixtures.vanilla("minecraft:note_block[note="+note+"]",1),x,2,3,22).getFirst();}
    @Test void negativeAndWrappedQuarterTurnsUseTheCanonicalGeometryAndCacheWithOrWithoutUvLock()throws Exception {
        int[][] cases={{-90,0,270,0},{0,-90,0,270},{360,720,0,0},{-450,810,270,90},{-2147483610,2147483610,270,90},{90,-720,90,0}};
        for(boolean lock:new boolean[]{false,true})for(int[] c:cases) {
            var f=fixture();f.json(PATH,"{\"variants\":{\"note=0\":{\"model\":\"test:block/asymmetric\",\"x\":"+c[0]+",\"y\":"+c[1]+",\"uvlock\":"+lock+"},\"note=1\":{\"model\":\"test:block/asymmetric\",\"x\":"+c[2]+",\"y\":"+c[3]+",\"uvlock\":"+lock+"}}}");
            var g=f.generation(1);assertEquals(0,g.failedBlockCount());assertTrue(g.issues().isEmpty());assertSame(select(g,1,0),select(g,0,0),Arrays.toString(c)+" uvlock="+lock);
        }
    }
    @Test void multipartKeepsBothPartsAndNormalizesEquivalentTurns()throws Exception {
        var f=fixture();f.json(PATH,"""
            {"multipart":[{"apply":{"model":"test:block/asymmetric","x":-90,"y":450,"uvlock":true}},
            {"apply":{"model":"test:block/asymmetric","x":270,"y":90,"uvlock":true}}]}
            """);
        var g=f.generation(1);var models=g.resolve(RenderFixtures.vanilla("minecraft:note_block[note=0]",1),0,0,0,0);assertEquals(2,models.size());assertSame(models.get(0),models.get(1));
    }
    @Test void weightedVariantsKeepTheSameDeterministicChoicesAfterNormalization()throws Exception {
        var f=fixture();f.json(PATH,"""
            {"variants":{"note=0":[{"model":"test:block/asymmetric","x":-90,"y":360,"weight":1},{"model":"test:block/other","x":720,"y":450,"weight":3}],
            "note=1":[{"model":"test:block/asymmetric","x":270,"y":0,"weight":1},{"model":"test:block/other","x":0,"y":90,"weight":3}]}}
            """);
        var g=f.generation(1);var chosen=new HashSet<ResourceKey>();for(int x=-64;x<64;x++){var model=select(g,0,x);chosen.add(model.key());assertSame(select(g,1,x),model);}assertEquals(2,chosen.size());
    }
    @Test void invalidAnglesRemainRejectedWithModelRuleAndRawValues()throws Exception {
        for(int[] c:new int[][]{{45,0},{0,-45},{Integer.MIN_VALUE,0}}) {
            var f=fixture();f.json(PATH,"{\"variants\":{\"note=7\":{\"model\":\"test:block/asymmetric\",\"x\":"+c[0]+",\"y\":"+c[1]+",\"weight\":2}}}");
            var g=f.generation(1);var error=assertThrows(AssetException.class,()->select(g,7,0));assertEquals(AssetException.Code.INVALID,error.code());assertEquals(PATH,error.resource());
            for(String detail:List.of("variants[note=7]","test:block/asymmetric","x="+c[0],"y="+c[1],"weight=2"))assertTrue(error.getMessage().contains(detail),error::getMessage);
        }
    }
    @Test void zeroAndNegativeWeightsRemainErrorsInsteadOfBecomingOne()throws Exception {
        for(int weight:new int[]{0,-1,Integer.MIN_VALUE}) {
            var f=fixture();f.json(PATH,"{\"variants\":{\"note=7\":{\"model\":\"test:block/asymmetric\",\"y\":360,\"weight\":"+weight+"}}}");
            var g=f.generation(1);var error=assertThrows(AssetException.class,()->select(g,7,0));assertEquals(AssetException.Code.INVALID,error.code());assertTrue(error.getMessage().contains("weight="+weight),error::getMessage);
        }
    }
    @Test void invalidMultipartReportsThePartIndexAndRawValues()throws Exception {
        var f=fixture();f.json(PATH,"""
            {"multipart":[{"apply":{"model":"test:block/asymmetric"}},{"when":{"note":"7"},"apply":{"model":"test:block/other","y":45}}]}
            """);
        var g=f.generation(1);var error=assertThrows(AssetException.class,()->select(g,7,0));assertTrue(error.getMessage().contains("multipart[1]"),error::getMessage);assertTrue(error.getMessage().contains("y=45"),error::getMessage);
    }
}
