package com.wenkrang.famara.render.schedule;

import com.wenkrang.famara.render.asset.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class SelectiveBlockResourcesTest {
    @TempDir Path root;
    private BudgetedAssetLoader.Limits limits(){return new BudgetedAssetLoader.Limits(128,1000000,1000000,8000000,30000000000L,1073741824L);}
    private RenderFixtures fixture()throws Exception {var f=new RenderFixtures();f.png("bettermodel:block/actually_used",1,1,0xff123456);f.cube("bettermodel:block/parent","bettermodel:block/actually_used");f.json("assets/test/models/child.json","{\"parent\":\"bettermodel:block/parent\"}");f.block("test:block","test:child");return f;}
    @Test void mergedStoredZipAbove128MiBLoadsOnlyBlockClosureAndStillReadsReferencedBettermodelAssets()throws Exception {
        var fixture=fixture();Path zip=root.resolve("large-merged.zip");byte[] unrelated=new byte[2*1024*1024];Arrays.fill(unrelated,(byte)' ');
        try(var output=new ZipOutputStream(Files.newOutputStream(zip))) {
            for(var entry:fixture.files.entrySet()){output.putNextEntry(new ZipEntry(entry.getKey()));output.write(entry.getValue());output.closeEntry();}
            for(int i=0;i<70;i++){var entry=new ZipEntry("assets/bettermodel/items/unrelated_"+i+".json");entry.setMethod(ZipEntry.STORED);entry.setSize(unrelated.length);var crc=new CRC32();crc.update(unrelated);entry.setCrc(crc.getValue());output.putNextEntry(entry);output.write(unrelated);output.closeEntry();}
        }
        assertTrue(Files.size(zip)>134217728);var memory=new RenderMemoryBudget(536870912);
        try(var repository=new AssetRepository(2);var loader=new BudgetedAssetLoader(repository,RenderFixtures.LIMITS,limits(),new CpuBudget(5000,100,System::nanoTime),memory)) {
            loader.reloadSources(List.of(new BudgetedAssetLoader.Source(zip,ResourcePack.Role.CUSTOM,true)),()->true).toCompletableFuture().get(30,TimeUnit.SECONDS);
            try(var lease=repository.acquire()){assertTrue(lease.generation().issues().isEmpty());var descriptor=RenderFixtures.vanilla("test:block",lease.generation().id());assertEquals("bettermodel:block/actually_used",lease.generation().resolve(descriptor,0,0,0,0).getFirst().quads().getFirst().texture().toString());assertEquals(1,lease.generation().completeBlockDefinitions());}
        }
        assertEquals(0,memory.snapshot().live());assertTrue(memory.snapshot().peak()<=536870912);
    }
    @Test void unreferencedOversizeDirectoryItemIsIgnoredButReferencedOversizeModelIsExplicitlyRejected()throws Exception {
        var fixture=fixture();Path folder=root.resolve("pack");for(var entry:fixture.files.entrySet()){Path file=folder.resolve(entry.getKey());Files.createDirectories(file.getParent());Files.write(file,entry.getValue());}
        Path unused=folder.resolve("assets/bettermodel/items/oversize.json");Files.createDirectories(unused.getParent());try(var file=new java.io.RandomAccessFile(unused.toFile(),"rw")){file.setLength(5000000);}
        try(var repository=new AssetRepository(2);var loader=new BudgetedAssetLoader(repository,RenderFixtures.LIMITS,limits(),new CpuBudget(5000,100,System::nanoTime))) {
            loader.reloadSources(List.of(new BudgetedAssetLoader.Source(folder,ResourcePack.Role.CUSTOM,true)),()->true).toCompletableFuture().get(10,TimeUnit.SECONDS);
            Path selected=folder.resolve("assets/test/models/child.json");try(var file=new java.io.RandomAccessFile(selected.toFile(),"rw")){file.setLength(5000000);}
            var error=assertThrows(ExecutionException.class,()->loader.reloadSources(List.of(new BudgetedAssetLoader.Source(folder,ResourcePack.Role.CUSTOM,true)),()->true).toCompletableFuture().get(10,TimeUnit.SECONDS));assertInstanceOf(AssetException.class,error.getCause());assertTrue(error.getCause().getMessage().contains("实际方块依赖单文件"));
        }
    }
}
