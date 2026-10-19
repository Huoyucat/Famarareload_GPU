package com.wenkrang.famara.render.asset;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import static org.junit.jupiter.api.Assertions.*;

class BlockResourceCursorTest {
    @TempDir Path root;
    private void write(Path directory,RenderFixtures f)throws Exception {for(var entry:f.files.entrySet()){Path p=directory.resolve(entry.getKey());Files.createDirectories(p.getParent());Files.write(p,entry.getValue());}}
    @Test void layeredSelectorsKeepLowerVariantsAndCanReferenceATextureFromAnotherNamespace()throws Exception {
        var low=new RenderFixtures();low.png("bettermodel:tile",1,1,0xffabcdef);low.cube("test:old","bettermodel:tile");low.block("minecraft:stone","test:old");
        var high=new RenderFixtures();high.json("assets/minecraft/blockstates/stone.json","{\"variants\":{\"custom=1\":{\"model\":\"test:old\"}}}");Path a=root.resolve("low"),b=root.resolve("high");write(a,low);write(b,high);
        try(var cursor=new BlockResourceCursor(List.of(new BlockResourceCursor.Input(b,ResourcePack.Role.CUSTOM,true,null),new BlockResourceCursor.Input(a,ResourcePack.Role.BASE,true,null)),RenderFixtures.LIMITS,1073741824)) {
            while(!cursor.step(1,Long.MAX_VALUE,System::nanoTime)){}var pack=cursor.result();assertEquals(2,pack.blockstateStack("assets/minecraft/blockstates/stone.json").size());assertNotNull(pack.find("assets/bettermodel/textures/tile.png"));
            var assets=AssetGeneration.compile(1,pack,RenderFixtures.LIMITS);assertFalse(assets.usesCustomResources(RenderFixtures.vanilla("minecraft:stone",1),0,0,0,0));assertTrue(assets.usesCustomResources(RenderFixtures.vanilla("minecraft:stone[custom=1]",1),0,0,0,0));
        }
    }
    @Test void selectedDirectoryChangeOrBadPinnedZipNeverPublishesAPartialResult()throws Exception {
        var f=new RenderFixtures();f.png("test:tile",1,1,0xffffffff);f.cube("test:model","test:tile");f.block("test:block","test:model");Path directory=root.resolve("directory");write(directory,f);
        try(var cursor=new BlockResourceCursor(List.of(new BlockResourceCursor.Input(directory,ResourcePack.Role.CUSTOM,true,null)),RenderFixtures.LIMITS,1073741824)) {
            while(cursor.metrics().selectedEntries()==0)assertFalse(cursor.step(1,Long.MAX_VALUE,System::nanoTime));Files.writeString(directory.resolve("assets/test/blockstates/block.json"),"{\"changed\":true}");assertThrows(AssetException.class,()->{while(!cursor.step(1,Long.MAX_VALUE,System::nanoTime)){}});assertThrows(IllegalStateException.class,cursor::result);
        }
        Path zip=root.resolve("pack.zip");try(var output=new ZipOutputStream(Files.newOutputStream(zip))){for(var entry:f.files.entrySet()){output.putNextEntry(new ZipEntry(entry.getKey()));output.write(entry.getValue());output.closeEntry();}}
        assertTrue(ZipDirectoryBudget.estimatedBytes(zip)>65536);
        try(var cursor=new BlockResourceCursor(List.of(new BlockResourceCursor.Input(zip,ResourcePack.Role.BASE,true,"0".repeat(64))),RenderFixtures.LIMITS,1073741824)) {assertThrows(java.io.IOException.class,()->{while(!cursor.step(1,Long.MAX_VALUE,System::nanoTime)){}});assertThrows(IllegalStateException.class,cursor::result);}
    }
    private Path zip(String name,RenderFixtures f)throws Exception {
        Path path=root.resolve(name);try(var output=new ZipOutputStream(Files.newOutputStream(path))){for(var entry:f.files.entrySet()){output.putNextEntry(new ZipEntry(entry.getKey()));output.write(entry.getValue());output.closeEntry();}}return path;
    }
    @Test void zipLayersKeepLowerSelectorsAndResolveCrossNamespaceOverridesAnimationsAndAliases()throws Exception {
        var low=new RenderFixtures();low.png("minecraft:block/iron_chain",1,1,0xff123456);low.cube("test:old","minecraft:block/chain");low.block("minecraft:stone","test:old");
        low.png("nameplates:font/selected",1,1,0xff0000ff);low.cube("bettermodel:item/shared/parent","nameplates:font/selected");
        var high=new RenderFixtures();high.json("assets/minecraft/blockstates/stone.json","{\"variants\":{\"custom=1\":{\"model\":\"test:child\"}}}");
        high.json("assets/test/models/child.json","{\"parent\":\"bettermodel:item/shared/parent\"}");high.png("nameplates:font/selected",1,1,0xffff0000);
        high.json("assets/nameplates/textures/font/selected.png.mcmeta","{\"animation\":{\"frames\":[0]}}");
        Path a=zip("low.zip",low),b=zip("high.zip",high);
        try(var cursor=new BlockResourceCursor(List.of(new BlockResourceCursor.Input(b,ResourcePack.Role.CUSTOM,true,null),new BlockResourceCursor.Input(a,ResourcePack.Role.BASE,true,null)),RenderFixtures.LIMITS,1073741824)) {
            while(!cursor.step(1,Long.MAX_VALUE,System::nanoTime)){}var pack=cursor.result();assertEquals(2,pack.blockstateStack("assets/minecraft/blockstates/stone.json").size());
            assertArrayEquals(high.files.get("assets/nameplates/textures/font/selected.png"),pack.require("assets/nameplates/textures/font/selected.png").copyBytes());assertNotNull(pack.find("assets/nameplates/textures/font/selected.png.mcmeta"));assertNotNull(pack.find("assets/minecraft/textures/block/chain.png"));
            var assets=AssetGeneration.compile(1,pack,RenderFixtures.LIMITS);assertEquals(1,assets.completeBlockDefinitions());assertFalse(assets.usesCustomResources(RenderFixtures.vanilla("minecraft:stone",1),0,0,0,0));assertTrue(assets.usesCustomResources(RenderFixtures.vanilla("minecraft:stone[custom=1]",1),0,0,0,0));
        }
        Files.delete(a);Files.delete(b);
    }

}
