package com.wenkrang.famara.render.schedule;

import com.wenkrang.famara.render.asset.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class BudgetedAssetLoaderTest {
    @TempDir Path root;
    private Path fixture() throws Exception {
        RenderFixtures f=new RenderFixtures();f.png("test:tile",1,1,0xffff0000);f.cube("test:block/model","test:tile");f.block("test:block","test:block/model");
        Path folder=root.resolve("pack");Files.createDirectories(folder);
        for(var e:f.files.entrySet()) {Path p=folder.resolve(e.getKey());Files.createDirectories(p.getParent());Files.write(p,e.getValue());}
        return folder;
    }
    private BudgetedAssetLoader.Limits limits() { return new BudgetedAssetLoader.Limits(1,1_000_000,1_000_000,5_000_000,10_000_000_000L); }
    @Test void allReadAndCompileSlicesAreChargedToTheSharedBudget() throws Exception {
        Path folder=fixture();CpuBudget budget=new CpuBudget(5000,100,System::nanoTime);
        try(AssetRepository repository=new AssetRepository(2);BudgetedAssetLoader loader=new BudgetedAssetLoader(repository,RenderFixtures.LIMITS,limits(),budget)) {
            var result=loader.reload(List.of(folder),()->true).toCompletableFuture().get(10,TimeUnit.SECONDS);
            assertTrue(result.slices()>5);
            try(var lease=repository.acquire()){assertEquals(result.generation(),lease.generation().id());}
            assertEquals(0,budget.snapshot().reservedNanos());assertTrue(budget.snapshot().settledBatches()>=result.slices());
        }
    }
    @Test void invalidatedInputsAndFailedRebuildKeepTheLastGeneration() throws Exception {
        Path folder=fixture();CpuBudget budget=new CpuBudget(5000,100,System::nanoTime);
        try(AssetRepository repository=new AssetRepository(2);BudgetedAssetLoader loader=new BudgetedAssetLoader(repository,RenderFixtures.LIMITS,limits(),budget)) {
            var first=loader.reload(List.of(folder),()->true).toCompletableFuture().get(10,TimeUnit.SECONDS);
            assertThrows(ExecutionException.class,()->loader.reload(List.of(folder),()->false).toCompletableFuture().get(10,TimeUnit.SECONDS));
            Files.delete(folder.resolve("assets/test/models/block/model.json"));
            assertThrows(ExecutionException.class,()->loader.reload(List.of(folder),()->true).toCompletableFuture().get(10,TimeUnit.SECONDS));
            try(var lease=repository.acquire()) {assertEquals(first.generation(),lease.generation().id());}
        }
    }
    @Test void closeCompletesWaitingRequestWithoutBorrowingAnotherTasksCpuReservation() throws Exception {
        Path folder=fixture();CpuBudget budget=new CpuBudget(1,1,System::nanoTime);var held=budget.reserve(1_000_000).orElseThrow();
        try(AssetRepository repository=new AssetRepository(2)) {
            BudgetedAssetLoader loader=new BudgetedAssetLoader(repository,RenderFixtures.LIMITS,limits(),budget);
            var waiting=loader.reload(List.of(folder),()->true);loader.close();
            assertTrue(waiting.handle((r,e)->e!=null).toCompletableFuture().get(5,TimeUnit.SECONDS));
            assertEquals(1_000_000,budget.snapshot().reservedNanos());
        } finally {held.close();}
        assertEquals(0,budget.snapshot().reservedNanos());
    }

    @Test void closedOptionalZipFilesystemCannotBlockAValidPersistentBase() throws Exception {
        Path folder=fixture(),temporary=root.resolve("temporary.zip"),stale;
        try(var fs=FileSystems.newFileSystem(java.net.URI.create("jar:"+temporary.toUri()),Map.of("create","true"))) {
            stale=fs.getPath("/ephemeral");Files.createDirectories(stale);
        }
        CpuBudget budget=new CpuBudget(5000,100,System::nanoTime);
        try(var repository=new AssetRepository(2);var loader=new BudgetedAssetLoader(repository,RenderFixtures.LIMITS,limits(),budget)) {
            var result=loader.reloadSources(List.of(new BudgetedAssetLoader.Source(stale,ResourcePack.Role.CUSTOM,false),
                    new BudgetedAssetLoader.Source(folder,ResourcePack.Role.BASE,true)),()->true).toCompletableFuture().get(10,TimeUnit.SECONDS);
            try(var lease=repository.acquire()){assertEquals(result.generation(),lease.generation().id());}
            assertEquals(0,budget.snapshot().reservedNanos());
        }
    }
    @Test void existingButCorruptUpperZipStillFailsRatherThanFallingBack()throws Exception {
        Path folder=fixture(),corrupt=root.resolve("bad.zip");Files.writeString(corrupt,"not a zip");CpuBudget budget=new CpuBudget(5000,100,System::nanoTime);
        try(var repository=new AssetRepository(2);var loader=new BudgetedAssetLoader(repository,RenderFixtures.LIMITS,limits(),budget)) {
            var first=loader.reload(List.of(folder),()->true).toCompletableFuture().get(10,TimeUnit.SECONDS);
            assertThrows(ExecutionException.class,()->loader.reloadSources(List.of(new BudgetedAssetLoader.Source(corrupt,ResourcePack.Role.CUSTOM,false),
                    new BudgetedAssetLoader.Source(folder,ResourcePack.Role.BASE,true)),()->true).toCompletableFuture().get(10,TimeUnit.SECONDS));
            try(var lease=repository.acquire()){assertEquals(first.generation(),lease.generation().id());}
        }
    }

    @Test void stagedAssetsDoNotReplaceRepositoryUntilTheVisualTableHasAlsoSucceeded()throws Exception {
        Path source=fixture();CpuBudget budget=new CpuBudget(5000,100,System::nanoTime);
        try(var repository=new AssetRepository(2);var loader=new BudgetedAssetLoader(repository,RenderFixtures.LIMITS,limits(),budget)) {
            var staged=loader.prepareSources(List.of(new BudgetedAssetLoader.Source(source,ResourcePack.Role.BASE,true)),()->true).toCompletableFuture().get(10,TimeUnit.SECONDS);
            assertThrows(IllegalStateException.class,repository::acquire);assertEquals(0,repository.retainedGenerations());
            assertTrue(repository.publish(staged.metrics().generation(),staged.generation()));
            try(var lease=repository.acquire()){assertEquals(staged.metrics().generation(),lease.generation().id());}
        }
    }

    @Test void completionCallbacksNeverRunInsideAssetLoaderMonitor()throws Exception {
        Path source=fixture();CpuBudget budget=new CpuBudget(5000,100,System::nanoTime);
        try(var repository=new AssetRepository(2);var loader=new BudgetedAssetLoader(repository,RenderFixtures.LIMITS,limits(),budget)) {
            var result=loader.prepareSources(List.of(new BudgetedAssetLoader.Source(source,ResourcePack.Role.BASE,true)),()->true)
                    .thenApply(staged->{assertFalse(Thread.holdsLock(loader),"平台锁与loader锁不能发生反向嵌套");return staged;});
            result.toCompletableFuture().get(10,TimeUnit.SECONDS);
        }
    }
}
