package com.wenkrang.famara.render.platform;
import com.wenkrang.famara.render.schedule.RenderMemoryBudget;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.zip.ZipFile;
import static org.junit.jupiter.api.Assertions.*;
class BundledActorResourcesTest {
    @TempDir Path folder;
    @Test void exact262DataPackIsVerifiedStreamedAndReusedWithoutRetainingIoBudget()throws Exception{
        var memory=new RenderMemoryBudget(1024*1024);var loader=BundledActorResources.class.getClassLoader();var first=BundledActorResources.prepare(loader,folder,memory,()->true);assertEquals("26.2",first.manifest().version());assertEquals(first.manifest().bytes(),Files.size(first.path()));assertEquals(0,memory.snapshot().live());try(var zip=new ZipFile(first.path().toFile())){assertFalse(zip.stream().anyMatch(e->e.getName().endsWith(".class") || e.getName().endsWith(".jar")));assertNotNull(zip.getEntry("assets/minecraft/equipment/diamond.json"));assertNotNull(zip.getEntry("assets/minecraft/items/trident.json"));}
        var second=BundledActorResources.prepare(loader,folder,memory,()->true);assertEquals(first.path(),second.path());assertEquals(0,memory.snapshot().live());
    }
    @Test void corruptedCacheCannotBecomeAReadyActorResourcePack()throws Exception{
        var memory=new RenderMemoryBudget(1024*1024);var loader=BundledActorResources.class.getClassLoader();var first=BundledActorResources.prepare(loader,folder,memory,()->true);Files.write(first.path(),new byte[]{0,1,2});var repaired=BundledActorResources.prepare(loader,folder,memory,()->true);assertEquals(first.manifest().bytes(),Files.size(repaired.path()));assertEquals(0,memory.snapshot().live());try(var files=Files.list(repaired.path().getParent())){assertTrue(files.anyMatch(p->p.getFileName().toString().contains(".bad-")));}
    }
}
