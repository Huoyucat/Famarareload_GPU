package com.wenkrang.famara.render.platform;

import com.wenkrang.famara.render.asset.*;
import com.wenkrang.famara.render.scene.*;
import com.wenkrang.famara.render.schedule.*;
import org.bukkit.*;
import org.bukkit.block.data.BlockData;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class FrozenChunkCompressionTest {
    private AssetGeneration assets()throws Exception {var f=new RenderFixtures();f.png("test:tile",1,1,0xffffffff);f.cube("test:model","test:tile");f.block("minecraft:stone","test:model");f.block("minecraft:air","test:model");return f.generation(1);}
    @Test void constantAndPalettedAndWideColorsRemainExactAndReturnTheirReservations()throws Exception {
        var assets=assets();var version=new VersionStateAdapter(VersionStateAdapter.Version.parse("26.3"),VersionStateAdapter.Version.parse("26.3"));var visuals=new VisualStateTable(0,List.of(),100);
        for(int mode=0;mode<3;mode++) {
            final int pattern=mode;var global=new RenderMemoryBudget(536870912);var raw=mock(ChunkSnapshot.class);var stone=mock(BlockData.class);var air=mock(BlockData.class);when(stone.getAsString()).thenReturn("minecraft:stone");when(air.getAsString()).thenReturn("minecraft:air");
            when(raw.getBlockData(anyInt(),anyInt(),anyInt())).thenAnswer(call->pattern==0 || (int)call.getArgument(0)%2==0?stone:air);
            WorldPreparation.LightingResolver lighting=(s,x,y,z,v)->new SceneSnapshot.Lighting(pattern==0?7:(x+y)&15,pattern==0?3:z&15,pattern==0?0x123456:pattern==1?((x+z)&255)*257:((y*256+z*16+x)*31)&0xffffff,y%2==0?0x3f76e4:0x445566);
            try(var memory=new MemoryScope(global,"test",2097152);var cursor=FrozenChunk.cursor(raw,-1,2,0,16,0,15,lighting,visuals,assets,version,memory,List.of())) {
                while(!cursor.step(127,Long.MAX_VALUE)){}
                try(var chunk=cursor.result()) {
                    for(int y=0;y<16;y++)for(int z=0;z<16;z++)for(int x=0;x<16;x++) {
                        assertEquals(pattern==0 || x%2==0?"minecraft:stone":"minecraft:air",chunk.state(x,y,z).toString());var expected=lighting.resolve(raw,x,y,z,null);assertEquals(expected,chunk.light(x,y,z));
                    }
                    assertTrue(chunk.retainedBytes()<53248,"压缩不能比原始数组更大");
                }
                assertEquals(0,memory.live());
            }
            assertEquals(0,global.snapshot().live());
        }
    }
    @Test void emptySectionsPreserveUndergroundLightInsteadOfAssumingSkyFifteen()throws Exception {
        var raw=mock(ChunkSnapshot.class);when(raw.isSectionEmpty(0)).thenReturn(true);when(raw.getBlockSkyLight(anyInt(),anyInt(),anyInt())).thenReturn(2);when(raw.getBlockEmittedLight(anyInt(),anyInt(),anyInt())).thenReturn(9);
        var global=new RenderMemoryBudget(536870912);try(var memory=new MemoryScope(global,"empty",2097152);var cursor=FrozenChunk.cursor(raw,0,0,-64,-48,-64,-49,(s,x,y,z,v)->{throw new AssertionError();},new VisualStateTable(0,List.of(),10),assets(),new VersionStateAdapter(VersionStateAdapter.Version.parse("26.3"),VersionStateAdapter.Version.parse("26.3")),memory,List.of())) {
            while(!cursor.step(256,Long.MAX_VALUE)){}try(var chunk=cursor.result()){assertTrue(chunk.empty(-64));assertEquals(2,chunk.light(0,-64,0).sky());assertEquals(9,chunk.light(15,-49,15).block());assertTrue(chunk.retainedBytes()<2048);}
        }assertEquals(0,global.snapshot().live());
    }
    @Test void unsignedPaletteIdsAndFullColorFallbackNeverLoseBits()throws Exception {
        Class<?> values=Class.forName(FrozenChunk.class.getName()+"$IntValues");var pack=values.getDeclaredMethod("pack",int[].class,boolean.class);pack.setAccessible(true);var get=values.getDeclaredMethod("get",int.class);get.setAccessible(true);
        int[] ids={0,32767,32768,65535};Object data=pack.invoke(null,ids,true);for(int i=0;i<ids.length;i++)assertEquals(ids[i],get.invoke(data,i));
        int[] colors=new int[4096];for(int i=0;i<colors.length;i++)colors[i]=(i*251)&0xffffff;data=pack.invoke(null,colors,false);for(int i=0;i<colors.length;i++)assertEquals(colors[i],get.invoke(data,i));
    }
}
