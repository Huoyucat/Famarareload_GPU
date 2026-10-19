package com.wenkrang.famara.render.platform;

import com.wenkrang.famara.render.asset.*;
import com.wenkrang.famara.render.scene.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class BiomeCatalogTest {
    public enum Modifier { NONE,OPAQUE;public int modifyColor(double x,double z,int base){int value=base^(int)x^(int)z;return this==OPAQUE?0xff000000|value:value;} }
    public record Climate(float temperature,float downfall) {}
    public record Effects(int waterColor,Optional<Integer> grassColorOverride,Optional<Integer> foliageColorOverride,
                          Optional<Integer> dryFoliageColorOverride,Modifier grassColorModifier) {}
    public static class Handle {
        private final Climate climateSettings=new Climate(.25f,.8f);
        public Effects getSpecialEffects(){return new Effects(0x123456,Optional.of(0),Optional.of(0x654321),Optional.empty(),Modifier.NONE);}
        public int getGrassColor(double x,double z){throw new AssertionError("不能使用未加载客户端LUT的服务器草色");}
    }
    public record RawHandle(Climate climateSettings,Effects effects) {public Effects getSpecialEffects(){return effects;}}
    @Test void freezesRealHandleFieldsIncludingLegalBlackWithoutCallingServerLut() throws Exception {
        var entry=BiomeCatalog.freezeHandle(new Handle());var climate=entry.climate();
        assertEquals(.25,climate.temperature());assertEquals(.8,climate.downfall(),1e-6);
        assertEquals(0x123456,climate.water());assertEquals(0,climate.grass());assertEquals(0x654321,climate.foliage());
        assertNull(climate.dryFoliage());assertEquals(7,entry.modifier().apply(3,4,0));
    }
    @Test void resourceLutCoordinatesAndOverridesAreIndependentOfClientBiomeDefinitions() throws Exception {
        RenderFixtures f=new RenderFixtures();int[] colors=new int[256*256];Arrays.fill(colors,0xff112233);colors[0]=0xffabcdef;colors[colors.length-1]=0xff224466;
        f.png("minecraft:colormap/grass",256,256,colors);f.png("minecraft:colormap/foliage",256,256,0xff338855);f.png("minecraft:colormap/dry_foliage",256,256,0xff886633);
        f.png("test:tile",1,1,0xffffffff);f.cube("test:model","test:tile");f.block("test:block","test:model");var assets=f.generation(1);
        assertEquals(0xabcdef,BiomeColors.lookup(assets,"grass",1,1));assertEquals(0x224466,BiomeColors.lookup(assets,"grass",0,0));
        var climate=new BiomeColors.Climate(.5,.5,0x123456,0,0x123123,null);
        assertEquals(0,BiomeColors.grass(assets,climate));
        assertEquals(0x123123,BiomeColors.tint(assets,CanonicalBlockState.parse("minecraft:oak_leaves"),climate,0));
        assertEquals(0xffffff,BiomeColors.tint(assets,CanonicalBlockState.parse("example:unknown"),climate,0));
        assertEquals(0x619961,BiomeColors.tint(assets,CanonicalBlockState.parse("minecraft:spruce_leaves"),climate,0));
        assertEquals(0xff3200,BiomeColors.redstone(15));assertEquals(0x4c0000,BiomeColors.redstone(0));
    }

    @Test void reloadInvalidatesPublishedViewBeforeTheNextGlobalDispatch()throws Exception {
        var budget=new com.wenkrang.famara.render.schedule.CpuBudget(5000,100,System::nanoTime);
        try(var catalog=new BiomeCatalog(new BiomeCatalog.Limits(10,1,10000000,1000000),budget)) {
            // 直接驱动空的冻结游标，不初始化需要真实Bukkit服务器的Registry静态字段。
            var iterator=BiomeCatalog.class.getDeclaredField("iterator");iterator.setAccessible(true);iterator.set(catalog,Collections.emptyIterator());
            catalog.tick();var old=catalog.view();assertNotNull(old);assertTrue(old.current());
            catalog.invalidate();assertFalse(old.current());assertNull(catalog.view());
        }
    }

    @Test void opaqueArgbFromModernBiomeCodecIsNormalizedBeforeStrictSceneValidation()throws Exception {
        var handle=new RawHandle(new Climate(.25f,.8f),new Effects(0xff3f76e4,Optional.of(0xff000000),Optional.of(0xff123456),Optional.of(0xffffffff),Modifier.OPAQUE));
        var entry=BiomeCatalog.freezeHandle(handle);var climate=entry.climate();
        assertEquals(0x3f76e4,climate.water());assertEquals(0,climate.grass());assertEquals(0x123456,climate.foliage());assertEquals(0xffffff,climate.dryFoliage());
        assertEquals(0x234567,entry.modifier().apply(0,0,0x234567));
    }
    @Test void rgbAndOpaqueArgbHaveIdenticalClimateWhileAbsentOverridesStayAbsent()throws Exception {
        var rgb=new RawHandle(new Climate(-.5f,0),new Effects(0x3f76e4,Optional.empty(),Optional.of(0),Optional.empty(),Modifier.NONE));
        var argb=new RawHandle(new Climate(-.5f,0),new Effects(0xff3f76e4,Optional.empty(),Optional.of(0xff000000),Optional.empty(),Modifier.OPAQUE));
        assertEquals(BiomeCatalog.freezeHandle(rgb).climate(),BiomeCatalog.freezeHandle(argb).climate());
        assertNull(BiomeCatalog.freezeHandle(argb).climate().grass());assertNull(BiomeCatalog.freezeHandle(argb).climate().dryFoliage());
    }
    @Test void partiallyTransparentColorStillFailsWithFieldAndRawHexInsteadOfFallingBack() {
        var handle=new RawHandle(new Climate(.5f,.5f),new Effects(0x803f76e4,Optional.empty(),Optional.empty(),Optional.empty(),Modifier.NONE));
        var error=assertThrows(IllegalArgumentException.class,()->BiomeCatalog.freezeHandle(handle));
        assertTrue(error.getMessage().contains("waterColor"));assertTrue(error.getMessage().contains("803F76E4"));
    }
    @Test void nonfiniteClimateStillFailsWithSpecificValues() {
        var handle=new RawHandle(new Climate(Float.NaN,.5f),new Effects(0xff3f76e4,Optional.empty(),Optional.empty(),Optional.empty(),Modifier.NONE));
        var error=assertThrows(IllegalArgumentException.class,()->BiomeCatalog.freezeHandle(handle));assertTrue(error.getMessage().contains("temperature=NaN"));
        assertThrows(IllegalArgumentException.class,()->new BiomeColors.Climate(.5,Double.POSITIVE_INFINITY,0x3f76e4,null,null,null));
    }
    @Test void rendererClimateContinuesToRequirePure24BitRgb() {
        assertThrows(IllegalArgumentException.class,()->new BiomeColors.Climate(.5,.5,0xff3f76e4,null,null,null));
        assertThrows(IllegalArgumentException.class,()->new BiomeColors.Climate(.5,.5,0x3f76e4,0xff000000,null,null));
    }

    @Test void chunkCachePreservesBiomeYAndAbsoluteColumnsWithoutSharingStateTint()throws Exception {
        RenderFixtures f=new RenderFixtures();f.png("test:tile",1,1,0xffffffff);f.cube("test:model","test:tile");f.block("test:block","test:model");var assets=f.generation(1);
        var active=new java.util.concurrent.atomic.AtomicBoolean(true);var modifiers=new java.util.concurrent.atomic.AtomicInteger();
        var low=new BiomeCatalog.Entry(new BiomeColors.Climate(.5,.5,0x123456,0x123400,0x654321,null),(x,z,base)->{modifiers.incrementAndGet();return base^(x&255)^(z&255);});
        var high=new BiomeCatalog.Entry(new BiomeColors.Climate(.5,.5,0x111111,0x003400,0x332211,null),(x,z,base)->{modifiers.incrementAndGet();return base^(x&255)^(z&255);});
        var constructor=BiomeCatalog.View.class.getDeclaredConstructor(Map.class,java.util.function.BooleanSupplier.class);constructor.setAccessible(true);
        var view=constructor.newInstance(Map.of("minecraft:plains",low,"minecraft:desert",high),(java.util.function.BooleanSupplier)active::get);
        org.bukkit.ChunkSnapshot snapshot=org.mockito.Mockito.mock(org.bukkit.ChunkSnapshot.class);
        org.mockito.Mockito.when(snapshot.getX()).thenReturn(-2);org.mockito.Mockito.when(snapshot.getZ()).thenReturn(3);
        org.mockito.Mockito.when(snapshot.getBiome(org.mockito.ArgumentMatchers.anyInt(),org.mockito.ArgumentMatchers.anyInt(),org.mockito.ArgumentMatchers.anyInt())).thenAnswer(call->(int)call.getArgument(1)<8?org.bukkit.block.Biome.PLAINS:org.bukkit.block.Biome.DESERT);
        org.mockito.Mockito.when(snapshot.getBlockSkyLight(org.mockito.ArgumentMatchers.anyInt(),org.mockito.ArgumentMatchers.anyInt(),org.mockito.ArgumentMatchers.anyInt())).thenReturn(7);
        org.mockito.Mockito.when(snapshot.getBlockEmittedLight(org.mockito.ArgumentMatchers.anyInt(),org.mockito.ArgumentMatchers.anyInt(),org.mockito.ArgumentMatchers.anyInt())).thenReturn(3);
        var global=new com.wenkrang.famara.render.schedule.RenderMemoryBudget(1024*1024);
        try(var scope=new com.wenkrang.famara.render.schedule.MemoryScope(global,"test",1024*1024)) {
            var resolver=view.resolver(assets).forChunk(scope);var grass=CanonicalBlockState.parse("minecraft:grass_block");
            var first=resolver.resolve(snapshot,1,2,3,grass);assertEquals(0x123400^((-31)&255)^(51&255),first.tintRgb());assertEquals(7,first.sky());assertEquals(3,first.block());
            resolver.resolve(snapshot,1,7,3,grass);assertEquals(1,modifiers.get());
            assertEquals(0x003400^((-31)&255)^(51&255),resolver.resolve(snapshot,1,9,3,grass).tintRgb());assertEquals(2,modifiers.get());
            assertEquals(0x123456,first.waterTintRgb());assertEquals(0x111111,resolver.resolve(snapshot,1,9,3,grass).waterTintRgb());
            assertEquals(BiomeColors.redstone(15),resolver.resolve(snapshot,1,2,3,CanonicalBlockState.parse("minecraft:redstone_wire[power=15]")).tintRgb());
            assertEquals(BiomeColors.redstone(0),resolver.resolve(snapshot,1,2,3,CanonicalBlockState.parse("minecraft:redstone_wire[power=0]")).tintRgb());
            assertEquals(0xffffff,resolver.resolve(snapshot,1,2,3,CanonicalBlockState.parse("minecraft:stone")).tintRgb());assertEquals(2,modifiers.get());
            assertTrue(scope.live()>0);active.set(false);assertThrows(IllegalStateException.class,()->resolver.resolve(snapshot,1,2,3,grass));
            resolver.close();resolver.close();assertEquals(0,scope.live());
        }
        assertEquals(0,global.snapshot().live());
    }
}
