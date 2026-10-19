package com.wenkrang.famara.render.asset;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;
import static org.junit.jupiter.api.Assertions.*;

class ZipIndexSafetyTest {
    @TempDir Path root;
    private Path zip(String a,String b,boolean duplicate,boolean sameContents)throws Exception {
        Path path=root.resolve(UUID.randomUUID()+".zip");
        try(var output=new ZipOutputStream(Files.newOutputStream(path))) {
            output.putNextEntry(new ZipEntry(a));output.write(new byte[]{1});output.closeEntry();
            output.putNextEntry(new ZipEntry(b));output.write(new byte[]{(byte)(sameContents?1:2)});output.closeEntry();
        }
        if(duplicate) {
            byte[] bytes=Files.readAllBytes(path),old=b.getBytes(StandardCharsets.UTF_8),name=a.getBytes(StandardCharsets.UTF_8);assertEquals(old.length,name.length);int replaced=0;
            for(int i=0;i<=bytes.length-old.length;i++){boolean match=true;for(int j=0;j<old.length;j++)if(bytes[i+j]!=old[j]){match=false;break;}if(match){System.arraycopy(name,0,bytes,i,name.length);replaced++;i+=old.length-1;}}
            assertEquals(2,replaced);Files.write(path,bytes);
        }
        return path;
    }
    private BlockResourceCursor cursor(Path zip,boolean collide) {
        return new BlockResourceCursor(List.of(new BlockResourceCursor.Input(zip,ResourcePack.Role.CUSTOM,true,null)),RenderFixtures.LIMITS,1073741824,collide?s->0:null);
    }
    @Test void evenUnrelatedDuplicateNamesAreRejectedRegardlessOfContent()throws Exception {
        for(boolean same:List.of(true,false))for(String kind:List.of("textures/font/","models/item/","unrelated/")) {
            Path zip=zip("assets/test/"+kind+"a.png","assets/test/"+kind+"b.png",true,same);
            try(var cursor=cursor(zip,false)){var error=assertThrows(AssetException.class,()->{while(!cursor.step(1,Long.MAX_VALUE,System::nanoTime)){}});assertEquals(AssetException.Code.INVALID,error.code());assertTrue(error.getMessage().contains("重复ZIP"));assertThrows(IllegalStateException.class,cursor::result);}
            Files.delete(zip);
        }
    }
    @Test void forcedHashCollisionsYieldAndDoNotRejectDifferentNames()throws Exception {
        Path zip=zip("assets/nameplates/textures/a.png","assets/nameplates/textures/b.png",false,true);int slices=0;
        try(var cursor=cursor(zip,true)) {
            assertFalse(cursor.step(1,0,()->0));
            while(!cursor.step(1,Long.MAX_VALUE,System::nanoTime))assertTrue(++slices<100);
            assertTrue(slices>10);assertTrue(cursor.result().paths().isEmpty());assertEquals(2,cursor.metrics().indexedEntries());
        }
        Files.delete(zip);
        Path duplicate=zip("assets/nameplates/textures/a.png","assets/nameplates/textures/b.png",true,true);
        try(var cursor=cursor(duplicate,true)){assertThrows(AssetException.class,()->{while(!cursor.step(1,Long.MAX_VALUE,System::nanoTime)){}});}
    }
    @Test void unrelatedUnsafePathsAndEntryLimitAreStillRejected()throws Exception {
        Path bad=zip("assets/test/unrelated/../a.png","assets/test/unrelated/b.png",false,true);
        try(var cursor=cursor(bad,false)){var error=assertThrows(AssetException.class,()->{while(!cursor.step(1,Long.MAX_VALUE,System::nanoTime)){}});assertEquals(AssetException.Code.INVALID,error.code());}
        Path many=zip("a","b",false,true);var limits=new AssetLimits(1,100,1000000,100,100,100,10,10,100,16);
        try(var cursor=new BlockResourceCursor(List.of(new BlockResourceCursor.Input(many,ResourcePack.Role.CUSTOM,true,null)),limits,1073741824)){var error=assertThrows(AssetException.class,()->{while(!cursor.step(1,Long.MAX_VALUE,System::nanoTime)){}});assertEquals(AssetException.Code.LIMIT,error.code());assertTrue(error.getMessage().contains("来源目录项"));}
    }
    @Test void catalogAndCompactTableBothRemainInsideTheOriginalIndexBudget()throws Exception {
        Path path=zip("a","b",false,true);long catalog=ZipDirectoryBudget.estimatedBytes(path);
        for(long total:new long[]{130000,(catalog+2048+8)*2}) {
            var limits=new AssetLimits(10,100,total,100,100,100,10,10,100,16);
            try(var cursor=new BlockResourceCursor(List.of(new BlockResourceCursor.Input(path,ResourcePack.Role.CUSTOM,true,null)),limits,1073741824)) {
                var error=assertThrows(AssetException.class,()->{while(!cursor.step(1,Long.MAX_VALUE,System::nanoTime)){}});
                assertEquals(AssetException.Code.LIMIT,error.code());assertTrue(error.getMessage().contains(total==130000?"ZIP中央目录工作集":"ZIP紧凑重名索引"));
            }
        }
    }
    @Test void closingDuringCollisionVerificationDropsAllZipHandles()throws Exception {
        Path path=zip("assets/nameplates/textures/a.png","assets/nameplates/textures/b.png",false,true);
        try(var cursor=cursor(path,true)){while(cursor.metrics().indexedEntries()<2)assertFalse(cursor.step(1,Long.MAX_VALUE,System::nanoTime));assertFalse(cursor.step(1,Long.MAX_VALUE,System::nanoTime));}
        Files.delete(path);
    }

}
