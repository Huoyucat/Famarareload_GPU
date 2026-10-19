package com.wenkrang.famara.render.asset;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import static org.junit.jupiter.api.Assertions.*;

class ResourceFileSnapshotTest {
    @TempDir Path root;
    private Path pack()throws Exception {
        RenderFixtures f=new RenderFixtures();f.png("test:tile",1,1,0xff00ff00);f.cube("test:model","test:tile");f.block("test:block","test:model");
        Path zip=root.resolve("resource_pack.zip");
        try(ZipOutputStream out=new ZipOutputStream(Files.newOutputStream(zip))){for(var entry:f.files.entrySet()){out.putNextEntry(new ZipEntry(entry.getKey()));out.write(entry.getValue());out.closeEntry();}}
        return zip;
    }
    @Test void privateZipDoesNotPreventWindowsReplacementOfExternalSourceAndDeletesOnClose()throws Exception {
        Path original=pack(),copy;
        try(var snapshot=new ResourceFileSnapshot(original,RenderFixtures.LIMITS.maxTotalBytes())) {
            while(!snapshot.step(1,Long.MAX_VALUE,System::nanoTime)){}
            copy=snapshot.path();assertNotEquals(original,copy);assertEquals(Files.size(original),snapshot.copiedBytes());assertEquals(snapshot.copiedBytes(),snapshot.verifiedBytes());
            try(var cursor=new ResourcePackCursor(copy,RenderFixtures.LIMITS)) {
                // HASH_BEFORE完成后READ阶段持有的ZipFile必须是副本；Windows同样允许替换原文件。
                for(int i=0;i<4;i++)cursor.step(1,Long.MAX_VALUE,System::nanoTime);
                Path retired=root.resolve("retired.zip");Files.move(original,retired);Files.writeString(original,"CE replacement");Files.delete(retired);
                while(!cursor.step(1,Long.MAX_VALUE,System::nanoTime)){}
                assertTrue(cursor.result().paths().contains("assets/test/blockstates/block.json"));
            }
            assertTrue(Files.exists(copy));
        }
        assertFalse(Files.exists(copy));assertEquals("CE replacement",Files.readString(original));
    }
    @Test void copyingAndVerificationYieldWithNoExternalFileHandleBetweenSteps()throws Exception {
        Path original=root.resolve("large.zip");byte[] data=new byte[700000];new Random(2).nextBytes(data);Files.write(original,data);
        try(var snapshot=new ResourceFileSnapshot(original,data.length)) {
            assertFalse(snapshot.step(1,0,()->0));assertEquals(0,snapshot.copiedBytes());assertFalse(snapshot.step(1,Long.MAX_VALUE,System::nanoTime));
            assertFalse(snapshot.step(1,Long.MAX_VALUE,System::nanoTime));assertTrue(snapshot.copiedBytes()>0 && snapshot.copiedBytes()<=256*1024);
            Path moved=root.resolve("moved.zip");Files.move(original,moved);Files.move(moved,original);
            while(!snapshot.step(1,Long.MAX_VALUE,System::nanoTime)){}
            assertArrayEquals(data,Files.readAllBytes(snapshot.path()));
        }
    }
    @Test void sameSizeAndRestoredTimestampCannotHideChangedBytesFromSecondHash()throws Exception {
        Path original=root.resolve("changing.zip");byte[] data=new byte[300000];Arrays.fill(data,(byte)1);Files.write(original,data);var modified=Files.getLastModifiedTime(original);
        try(var snapshot=new ResourceFileSnapshot(original,data.length)) {
            while(snapshot.copiedBytes()<data.length)snapshot.step(1,Long.MAX_VALUE,System::nanoTime);
            snapshot.step(1,Long.MAX_VALUE,System::nanoTime); // COPY -> VERIFY
            data[0]=2;Files.write(original,data);Files.setLastModifiedTime(original,modified);
            var error=assertThrows(AssetException.class,()->{while(!snapshot.step(1,Long.MAX_VALUE,System::nanoTime)){};});
            assertEquals(AssetException.Code.SOURCE_CHANGED,error.code());assertThrows(IllegalStateException.class,snapshot::path);
            assertThrows(IllegalStateException.class,()->snapshot.step(1,Long.MAX_VALUE,System::nanoTime));
        }
    }
    @Test void sourceFileLimitIsCheckedBeforeCreatingAUsableCopy()throws Exception {
        Path original=root.resolve("oversized.zip");Files.write(original,new byte[32]);
        try(var snapshot=new ResourceFileSnapshot(original,16)) {
            assertEquals(AssetException.Code.LIMIT,assertThrows(AssetException.class,()->snapshot.step(1,Long.MAX_VALUE,System::nanoTime)).code());
            assertThrows(IllegalStateException.class,snapshot::path);
        }
    }
}
