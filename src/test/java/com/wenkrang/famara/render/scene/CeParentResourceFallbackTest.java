package com.wenkrang.famara.render.scene;

import com.wenkrang.famara.render.asset.*;
import org.junit.jupiter.api.Test;
import java.io.*;
import java.util.*;
import java.util.zip.*;
import static org.junit.jupiter.api.Assertions.*;

class CeParentResourceFallbackTest {
    @Test void ceCanvasRugInheritsMissingVanillaThinBlockParentFromActorVersionAssets()throws Exception {
        var ce=new RenderFixtures();ce.png("farmersdelight:block/canvas_rug",2,2,0xff877250);
        ce.json("assets/farmersdelight/blockstates/canvas_rug.json","""
                {"variants":{"":{"model":"farmersdelight:block/canvas_rug"}}}
                """);
        ce.json("assets/farmersdelight/models/block/canvas_rug.json","""
                {"parent":"minecraft:block/thin_block","textures":{"particle":"farmersdelight:block/canvas_rug","rug":"farmersdelight:block/canvas_rug"},
                 "elements":[{"from":[0,0,0],"to":[16,1,16],"faces":{"up":{"texture":"#rug","uv":[0,0,16,16]}}}]}
                """);
        var actorAssets=new HashMap<String,byte[]>();
        try(var stream=Objects.requireNonNull(getClass().getResourceAsStream("/render-actors/vanilla-assets-26.2.zip"));var zip=new ZipInputStream(stream)) {
            for(ZipEntry entry;(entry=zip.getNextEntry())!=null;){String name=entry.getName();if(name.equals("assets/minecraft/models/block/thin_block.json") || name.equals("assets/minecraft/models/block/block.json"))actorAssets.put(name,zip.readAllBytes());}
        }
        assertEquals(Set.of("assets/minecraft/models/block/thin_block.json","assets/minecraft/models/block/block.json"),actorAssets.keySet());
        var vanilla26=ResourcePack.memory("Minecraft 26.2 fallback",actorAssets,RenderFixtures.LIMITS).withRole(ResourcePack.Role.BASE);
        var merged=ResourcePack.layer(List.of(ce.pack().withRole(ResourcePack.Role.CUSTOM),vanilla26),RenderFixtures.LIMITS);
        var generation=AssetGeneration.compile(1,merged,RenderFixtures.LIMITS);
        var server=CanonicalBlockState.parse("farmersdelight:canvas_rug");
        var entry=new VisualStateTable.Entry(server,ResourceKey.parse("farmersdelight:canvas_rug"),null,server,ResourceKey.parse("minecraft:carpet"));
        var table=new VisualStateTable(1,List.of(entry),100);var version=new VersionStateAdapter(VersionStateAdapter.Version.parse("26.2"),VersionStateAdapter.Version.parse("26.3"));
        var visible=table.resolve(server,generation,version);
        assertDoesNotThrow(()->table.validateResources(visible,generation,0,64,0,Set.of()));
        assertTrue(generation.usesCustomResources(visible.descriptor(),0,64,0,0));
        assertFalse(generation.resolve(visible.descriptor(),0,64,0,0).getFirst().quads().isEmpty());
    }
}
