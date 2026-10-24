package com.wenkrang.famara.compat;

import com.wenkrang.famara.render.asset.*;
import com.wenkrang.famara.render.platform.*;
import com.wenkrang.famara.render.schedule.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class CeVisualResourceServiceTest {
    @TempDir Path directory;
    @AfterEach void reset(){CeCompat.backendForTests(null);}
    private Path fixture() throws Exception {
        RenderFixtures f=new RenderFixtures();f.png("test:tile",1,1,0xff00ff00);f.cube("test:block/model","test:tile");f.block("test:block","test:block/model");
        for(var e:f.files.entrySet()){Path file=directory.resolve(e.getKey());Files.createDirectories(file.getParent());Files.write(file,e.getValue());}return directory;
    }
    private void driveUntilSettled(CeVisualResourceService service,int seconds)throws Exception {
        // Like the real timer, drive later ticks after a wall-budget yield. Keep the test's deadline.
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(seconds);
        while(!service.readiness().toCompletableFuture().isDone() && System.nanoTime()<deadline){service.tick();Thread.sleep(1);}
    }
    @Test void freezesRegistryInBoundedSlicesAndPublishesOnlyMatchingAssetEpoch() throws Exception {
        Path source=fixture();CeCompat.backendForTests(new CeCompat.Backend() {
            public boolean enabled(){return true;}
            public CeCompat.VisualCursor visualCursor(){return new CeCompat.VisualCursor() {
                int n;public boolean done(){return n==3;}
                public List<CeCompat.VisualState> next(int maximum) {
                    assertEquals(1,maximum);
                    if(n++==2)return List.of(new CeCompat.VisualState("minecraft:oak_leaves[distance=4,persistent=false,waterlogged=false]","minecraft:oak_leaves","minecraft:oak_leaves",null,"minecraft:oak_leaves[distance=7,persistent=true,waterlogged=false]",false));
                    return List.of(new CeCompat.VisualState("minecraft:note_block[note="+(n-1)+"]","minecraft:note_block","test:logical",null,"test:block"));
                }
            };}
        });
        CpuBudget budget=new CpuBudget(5000,100,System::nanoTime);List<Throwable> errors=new CopyOnWriteArrayList<>();
        try(var repository=new AssetRepository(2);
            var loader=new BudgetedAssetLoader(repository,RenderFixtures.LIMITS,new BudgetedAssetLoader.Limits(1,1_000_000,1_000_000,5_000_000,10_000_000_000L),budget);
            var background=new BudgetedWorkRunner(4,1_000_000,1_000_000,budget);
            var service=new CeVisualResourceService(source,RenderFixtures.LIMITS,new CeVisualResourceService.Limits(10,1024*1024,1,50_000_000,1_000_000),repository,loader,background,budget,errors::add)) {
            service.tick();assertFalse(service.readiness().toCompletableFuture().isDone());service.tick();assertFalse(service.readiness().toCompletableFuture().isDone());service.tick();
            driveUntilSettled(service,10);var ready=service.readiness().toCompletableFuture().get(0,TimeUnit.MILLISECONDS);assertEquals(3,ready.table().size());assertEquals(1,ready.table().vanillaRemapCount());
            try(var input=service.acquire()){assertEquals(ready.generation(),input.lease().generation().id());assertSame(ready.table(),input.table());}
            CeCompat.invalidateVisualResources();assertTrue(service.ready().isEmpty());assertThrows(IllegalStateException.class,service::acquire);
            assertTrue(errors.isEmpty());
        }
    }
    @Test void installedButIncompatibleCeNeverFallsBackToVanillaCarriers() throws Exception {
        Path source=fixture();CeCompat.backendForTests(new CeCompat.Backend() {});CpuBudget budget=new CpuBudget(5000,100,System::nanoTime);
        try(var repository=new AssetRepository(2);
            var loader=new BudgetedAssetLoader(repository,RenderFixtures.LIMITS,new BudgetedAssetLoader.Limits(1,1_000_000,1_000_000,5_000_000,10_000_000_000L),budget);
            var background=new BudgetedWorkRunner(2,1_000_000,1_000_000,budget);
            var service=new CeVisualResourceService(source,RenderFixtures.LIMITS,new CeVisualResourceService.Limits(10,1024*1024,1,50_000_000,1_000_000),repository,loader,background,budget,error->{})) {
            service.tick();assertThrows(ExecutionException.class,()->service.readiness().toCompletableFuture().get(5,TimeUnit.SECONDS));assertTrue(service.ready().isEmpty());assertTrue(service.status().contains("资源准备失败"));assertTrue(service.status().contains("CE存在但视觉适配不可用"));
        }
    }

    @Test void disablingCeInvalidatesItsVisualEpochAndCannotBecomeVanillaModeSilently() {
        java.util.concurrent.atomic.AtomicBoolean closed=new java.util.concurrent.atomic.AtomicBoolean();
        CeCompat.backendForTests(new CeCompat.Backend(){public boolean enabled(){return !closed.get();}public void close(){closed.set(true);}});
        long before=CeCompat.visualEpoch();var plugin=org.mockito.Mockito.mock(org.bukkit.plugin.Plugin.class);
        org.mockito.Mockito.when(plugin.getName()).thenReturn("CraftEngine");var event=org.mockito.Mockito.mock(org.bukkit.event.server.PluginDisableEvent.class);
        org.mockito.Mockito.when(event.getPlugin()).thenReturn(plugin);new CeLifecycle().disabled(event);
        assertTrue(closed.get());assertFalse(CeCompat.enabled());assertTrue(CeCompat.visualEpoch()>before);
        assertTrue(CeCompat.visualSources().cePresent());assertFalse(CeCompat.visualSources().adapterAvailable());
    }

    @Test void diskPreparationStartsBeforeRegistryFreezeEndsButPublicationWaitsForBoth()throws Exception {
        Path source=fixture();java.util.concurrent.atomic.AtomicBoolean frozen=new java.util.concurrent.atomic.AtomicBoolean();
        CeCompat.backendForTests(new CeCompat.Backend(){public boolean enabled(){return true;}public CeCompat.VisualCursor visualCursor(){return new CeCompat.VisualCursor(){
            public boolean done(){return frozen.get();}public List<CeCompat.VisualState> next(int maximum){return List.of();}
        };}});
        CpuBudget budget=new CpuBudget(5000,100,System::nanoTime);
        try(var repository=new AssetRepository(2);var background=new BudgetedWorkRunner(2,1000000,1000000,budget)) {
            var loader=org.mockito.Mockito.mock(BudgetedAssetLoader.class);CompletableFuture<BudgetedAssetLoader.Staged> disk=new CompletableFuture<>();
            org.mockito.Mockito.when(loader.prepareSources(org.mockito.ArgumentMatchers.anyList(),org.mockito.ArgumentMatchers.any())).thenReturn(disk);
            try(var service=new CeVisualResourceService(source,RenderFixtures.LIMITS,new CeVisualResourceService.Limits(10,1048576,1,10000000,1000000),repository,loader,background,budget,error->{})) {
                service.tick();org.mockito.Mockito.verify(loader).prepareSources(org.mockito.ArgumentMatchers.anyList(),org.mockito.ArgumentMatchers.any());
                RenderFixtures f=new RenderFixtures();f.png("test:tile",1,1,0xffffffff);f.cube("test:model","test:tile");f.block("test:block","test:model");long ticket=repository.requestBuild();var assets=f.generation(ticket);
                disk.complete(new BudgetedAssetLoader.Staged(new BudgetedAssetLoader.Result(ticket,1,1,1,assets.estimatedRetainedBytes()),assets));
                assertThrows(IllegalStateException.class,repository::acquire);assertFalse(service.readiness().toCompletableFuture().isDone());
                frozen.set(true);service.tick();var ready=service.readiness().toCompletableFuture().get(5,TimeUnit.SECONDS);
                try(var input=service.acquire()){assertEquals(ticket,input.lease().generation().id());assertEquals(ticket,ready.generation());}
            }
        }
    }
    @Test void invalidVisualTableCannotPublishEvenWhenDiskAlreadyFinished()throws Exception {
        Path source=fixture();CeCompat.backendForTests(new CeCompat.Backend(){public boolean enabled(){return true;}public CeCompat.VisualCursor visualCursor(){return new CeCompat.VisualCursor(){
            boolean done;public boolean done(){return done;}public List<CeCompat.VisualState> next(int maximum){done=true;return List.of(new CeCompat.VisualState("invalid:State","minecraft:stone","test:id",null,"test:block"));}
        };}});
        CpuBudget budget=new CpuBudget(5000,100,System::nanoTime);
        try(var repository=new AssetRepository(2);var background=new BudgetedWorkRunner(2,1000000,1000000,budget)) {
            RenderFixtures f=new RenderFixtures();f.png("test:tile",1,1,0xffffffff);f.cube("test:model","test:tile");f.block("test:block","test:model");
            long old=repository.requestBuild();repository.publish(old,f.generation(old));long ticket=repository.requestBuild();var candidate=f.generation(ticket);
            var loader=org.mockito.Mockito.mock(BudgetedAssetLoader.class);
            org.mockito.Mockito.when(loader.prepareSources(org.mockito.ArgumentMatchers.anyList(),org.mockito.ArgumentMatchers.any())).thenReturn(CompletableFuture.completedFuture(new BudgetedAssetLoader.Staged(new BudgetedAssetLoader.Result(ticket,1,1,1,candidate.estimatedRetainedBytes()),candidate)));
            try(var service=new CeVisualResourceService(source,RenderFixtures.LIMITS,new CeVisualResourceService.Limits(10,1048576,1,10000000,1000000),repository,loader,background,budget,error->{})) {
                service.tick();driveUntilSettled(service,5);
                assertThrows(ExecutionException.class,()->service.readiness().toCompletableFuture().get(0,TimeUnit.MILLISECONDS));
                try(var lease=repository.acquire()){assertEquals(old,lease.generation().id());}assertTrue(service.ready().isEmpty());
            }
        }
    }
}
