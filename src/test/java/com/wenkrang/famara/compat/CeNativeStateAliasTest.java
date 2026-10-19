package com.wenkrang.famara.compat;

import com.wenkrang.famara.render.asset.*;
import com.wenkrang.famara.render.scene.*;
import com.wenkrang.famara.render.kernel.*;
import com.wenkrang.famara.render.math.*;
import com.wenkrang.famara.render.platform.*;
import com.wenkrang.famara.render.schedule.*;
import org.bukkit.*;
import org.bukkit.block.data.BlockData;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CeNativeStateAliasTest {
    private List<VisualStateTable.Entry> entries(List<CeCompat.VisualState> rows){return rows.stream().map(r->new VisualStateTable.Entry(CanonicalBlockState.parse(r.serverState()),ResourceKey.parse(r.id()),null,CanonicalBlockState.parse(r.visualState()),ResourceKey.parse(r.material()))).toList();}
    @Test void raw220And223ResolveToTheirActualCeVisualModelsAndSampleDifferentUvColors()throws Exception {
        var f=new RenderFixtures();f.png("test:tile",2,2,0xffff0000,0xff00ff00,0xff0000ff,0xffffffff);f.cube("test:model","test:tile");f.block("minecraft:note_block","test:model");f.block("minecraft:air","test:model");var assets=AssetGeneration.compile(1,f.pack().withRole(ResourcePack.Role.CUSTOM),RenderFixtures.LIMITS);
        var first=CeVisualStateAliases.freeze("test:logical[rotation=0]","craftengine:custom_220","minecraft:note_block","test:real_identity",null,"minecraft:note_block[note=2]");var second=CeVisualStateAliases.freeze("test:logical[rotation=1]","craftengine:custom_223","minecraft:note_block","test:real_identity",null,"minecraft:note_block[note=3]");assertEquals(2,first.size());
        List<CeCompat.VisualState> rows=new ArrayList<>(first);rows.addAll(second);var table=new VisualStateTable(1,entries(rows),100);var version=new VersionStateAdapter(VersionStateAdapter.Version.parse("26.3"),VersionStateAdapter.Version.parse("26.3"));
        var raw=mock(ChunkSnapshot.class);var air=mock(BlockData.class);var a=mock(BlockData.class);var b=mock(BlockData.class);when(air.getAsString()).thenReturn("minecraft:air");when(a.getAsString()).thenReturn("craftengine:custom_220");when(b.getAsString()).thenReturn("craftengine:custom_223");when(raw.getBlockData(anyInt(),anyInt(),anyInt())).thenReturn(air);when(raw.getBlockData(0,0,0)).thenReturn(a);when(raw.getBlockData(1,0,0)).thenReturn(b);
        var global=new RenderMemoryBudget(536870912);try(var scope=new MemoryScope(global,"test",201326592);var cursor=FrozenChunk.cursor(raw,0,0,0,16,0,15,(s,x,y,z,v)->new SceneSnapshot.Lighting(15,0,0xffffff),table,assets,version,scope,List.of())) {
            while(!cursor.step(256,Long.MAX_VALUE)){}try(var frozen=cursor.result()) {
                var resolved=table.resolve(frozen.state(0,0,0),assets,version);assertEquals(table.resolve(CanonicalBlockState.parse("test:logical[rotation=0]"),assets,version),resolved);assertEquals(VisualBlockDescriptor.Kind.CE,resolved.descriptor().kind());assertEquals("test:real_identity",resolved.descriptor().logicalId().toString());table.validateResources(resolved,assets,0,0,0,Set.of());
                var builder=new SceneSnapshot.Builder(assets,0,16,10,10000).memory(scope);for(int x=-1;x<=1;x++)for(int z=-1;z<=1;z++)builder.availableSection(x,0,z);for(int x=0;x<2;x++)builder.put(x,0,0,table.resolve(frozen.state(x,0,0),assets,version).descriptor(),frozen.light(x,0,0),0);
                var renderer=new CpuPhotoRenderer(builder.build(),new Camera(new Vec3(.5,.5,-2),Math.PI/2,0,Camera.Projection.LEGACY_ANGULAR,1),new CpuPhotoRenderer.Settings(8,1,Texture.Filter.NEAREST,false,0,.5,32,0,0,new VoxelTracer.Limits(1000,1000,10000),Map.of()));
                assertEquals(1,renderer.sampleRay(new Ray(new Vec3(.75,.75,-2),new Vec3(0,0,1)),()->false).r());assertEquals(1,renderer.sampleRay(new Ray(new Vec3(.25,.75,-2),new Vec3(0,0,1)),()->false).g());
                assertEquals("minecraft:note_block[note=3]",table.resolve(frozen.state(1,0,0),assets,version).descriptor().visualState().toString());assertThrows(AssetException.class,()->table.resolve(CanonicalBlockState.parse("craftengine:custom_999"),assets,version));
            }
        }assertEquals(0,global.snapshot().live());
    }
    @Test void identicalRepresentationsDeduplicateAndConflictsFailRatherThanGuessing() {
        var one=CeVisualStateAliases.freeze("craftengine:custom_220","craftengine:custom_220","minecraft:note_block","test:a",null,"minecraft:stone");assertEquals(1,one.size());var other=CeVisualStateAliases.freeze("craftengine:custom_220","craftengine:custom_220","minecraft:note_block","test:b",null,"minecraft:dirt");List<CeCompat.VisualState> rows=new ArrayList<>(one);rows.addAll(other);assertThrows(IllegalArgumentException.class,()->new VisualStateTable(1,entries(rows),100));
    }
}
