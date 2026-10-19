package com.wenkrang.famara.render.asset;

import org.junit.jupiter.api.Test;
import java.util.*;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

class EmbeddedShapeDataTest {
    private byte[] data()throws Exception {try(var in=getClass().getClassLoader().getResourceAsStream("render-base/base-26.3.zip")){try(var zip=new java.util.zip.ZipInputStream(in)){for(var entry=zip.getNextEntry();entry!=null;entry=zip.getNextEntry())if(entry.getName().equals(NativeModelDataReader.DATA))return zip.readAllBytes();}}throw new AssertionError();}
    @Test void pureDataHasAllFourPosesAndCustomLayerCannotReplaceItsBaseIdentity()throws Exception {
        var limits=new AssetLimits(1000,4194304,134217728,1048576,4096,20000,64,64,200000,64);byte[] bytes=data();
        var base=ResourcePack.memory("bundled",Map.of(NativeModelDataReader.DATA,bytes),limits).withRole(ResourcePack.Role.BASE);var custom=ResourcePack.memory("untrusted-ce",Map.of(NativeModelDataReader.DATA,"{}".getBytes(StandardCharsets.UTF_8)),limits).withRole(ResourcePack.Role.CUSTOM);
        var layer=ResourcePack.layer(List.of(custom,base),limits);assertEquals(ResourcePack.Role.BASE,layer.require(NativeModelDataReader.DATA).role());var reader=new NativeModelDataReader(layer,limits,new AssetMemoryBudget(134217728));
        for(String pose:List.of("createBodyLayer","createRunningPoseBodyLayer","createSittingPoseBodyLayer","createStarPoseBodyLayer")){var shape=reader.read(pose);assertEquals(64,shape.width());assertFalse(shape.root().children().isEmpty());}
    }
    @Test void malformedNativeDataIsRejectedRatherThanLoadingGameCode() {
        var pack=ResourcePack.memory("bad",Map.of(NativeModelDataReader.DATA,"{\"format\":2,\"clientVersion\":\"26.3\",\"methods\":{}}".getBytes(StandardCharsets.UTF_8)),RenderFixtures.LIMITS).withRole(ResourcePack.Role.BASE);
        assertThrows(AssetException.class,()->new NativeModelDataReader(pack,RenderFixtures.LIMITS,new AssetMemoryBudget(64000000)));
    }
}
