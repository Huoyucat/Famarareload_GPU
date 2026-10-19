package com.wenkrang.famara.render.asset;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class VersionPropertyCompatibilityTest {
    private RenderFixtures fixture()throws Exception {
        var f=new RenderFixtures();f.png("test:tile",1,1,0xffffffff);f.cube("test:model","test:tile");f.json("assets/minecraft/blockstates/creaking_heart.json","{\"variants\":{\"axis=y,creaking_heart_state=awake\":{\"model\":\"test:model\"},\"axis=y,creaking_heart_state=uprooted\":{\"model\":\"test:model\"}}}");return f;
    }
    @Test void inactiveMapsToItsRealUprootedModelRatherThanGuessingDormant()throws Exception {
        var assets=fixture().generation(1);var adapter=new VersionStateAdapter(VersionStateAdapter.Version.parse("1.21.4"),VersionStateAdapter.Version.parse("26.3"));
        assertEquals("minecraft:creaking_heart[axis=y,creaking_heart_state=uprooted,natural=true]",adapter.adapt(CanonicalBlockState.parse("minecraft:creaking_heart[axis=y,active=false,natural=true]"),assets).toString());
        assertEquals("awake",adapter.adapt(CanonicalBlockState.parse("minecraft:creaking_heart[axis=y,active=true]"),assets).properties().get("creaking_heart_state"));
    }
    @Test void matchingOldCeSelectorWinsEvenWhenItsModelIsBroken()throws Exception {
        var low=fixture();var high=new RenderFixtures();high.json("assets/minecraft/blockstates/creaking_heart.json","{\"variants\":{\"active=true\":{\"model\":\"missing:override\"}}}");var pack=ResourcePack.layer(List.of(high.pack().withRole(ResourcePack.Role.CUSTOM),low.pack().withRole(ResourcePack.Role.BASE)),RenderFixtures.LIMITS);var assets=AssetGeneration.compile(1,pack,RenderFixtures.LIMITS);
        var input=CanonicalBlockState.parse("minecraft:creaking_heart[axis=y,active=true]");var adapter=new VersionStateAdapter(VersionStateAdapter.Version.parse("1.21.4"),VersionStateAdapter.Version.parse("26.3"));assertEquals(input,adapter.adapt(input,assets));assertThrows(AssetException.class,()->assets.resolve(VisualBlockDescriptor.vanilla(input,1),0,0,0,0));
    }

    @Test void preReleaseHeartEnumUsesActualOldModelRatherThanMatchingItsWordToTheNewEnum()throws Exception {
        var assets=fixture().generation(1);var adapter=new VersionStateAdapter(VersionStateAdapter.Version.parse("1.21.2"),VersionStateAdapter.Version.parse("26.3"));
        for(String old:List.of("active","dormant"))assertEquals("awake",adapter.adapt(CanonicalBlockState.parse("minecraft:creaking_heart[axis=y,creaking="+old+"]"),assets).properties().get("creaking_heart_state"));
        assertEquals("uprooted",adapter.adapt(CanonicalBlockState.parse("minecraft:creaking_heart[axis=y,creaking=disabled]"),assets).properties().get("creaking_heart_state"));
        assertTrue(new VersionStateAdapter(VersionStateAdapter.Version.parse("26.1.2"),VersionStateAdapter.Version.parse("26.3")).auditedRuntime());
    }
}
