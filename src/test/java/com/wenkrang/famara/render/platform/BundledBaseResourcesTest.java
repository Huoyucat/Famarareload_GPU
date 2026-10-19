package com.wenkrang.famara.render.platform;

import com.wenkrang.famara.render.schedule.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.concurrent.*;
import java.util.zip.*;
import static org.junit.jupiter.api.Assertions.*;

class BundledBaseResourcesTest {
    @TempDir Path directory;
    private ClassLoader loader(){return BundledBaseResources.class.getClassLoader();}
    @Test void cleanInstallNeedsNoClientAndRepairsOwnedCacheKeepingTheBadCopy()throws Exception {
        var budget=new RenderMemoryBudget(536870912);var ready=BundledBaseResources.prepare(loader(),directory,budget,()->true);
        assertTrue(Files.isRegularFile(ready.path()));assertEquals(0,budget.snapshot().live());assertEquals("26.3",ready.manifest().clientVersion());assertFalse(ready.manifest().containsGameClasses());
        try(var zip=new ZipFile(ready.path().toFile())){assertEquals(ready.manifest().entries(),zip.size());assertFalse(zip.stream().anyMatch(e->e.getName().endsWith(".class") || e.getName().endsWith(".jar")));assertNotNull(zip.getEntry("assets/famara/native_models/copper_golem.json"));}
        var again=BundledBaseResources.prepare(loader(),directory,budget,()->true);assertEquals(ready.path(),again.path());
        Files.writeString(ready.path(),"damaged-cache");BundledBaseResources.prepare(loader(),directory,budget,()->true);
        try(var files=Files.list(ready.path().getParent())){var bad=files.filter(p->p.getFileName().toString().contains(".bad-")).findFirst().orElseThrow();assertEquals("damaged-cache",Files.readString(bad));}
        assertEquals(0,budget.snapshot().live());
    }
    @Test void cancellationAndMissingBundleReturnTheirReservations()throws Exception {
        var budget=new RenderMemoryBudget(536870912);assertThrows(CancellationException.class,()->BundledBaseResources.prepare(loader(),directory,budget,()->false));assertEquals(0,budget.snapshot().live());
        ClassLoader missing=new ClassLoader(null){};assertThrows(java.io.IOException.class,()->BundledBaseResources.prepare(missing,directory,budget,()->true));assertEquals(0,budget.snapshot().live());
        if(Files.exists(directory.resolve("render-cache")))try(var files=Files.list(directory.resolve("render-cache"))){assertFalse(files.anyMatch(p->p.toString().endsWith(".tmp")));}
    }
    @Test void concurrentPreparationsAndPlatformChecksAreBounded()throws Exception {
        var budget=new RenderMemoryBudget(536870912);
        try(var pool=Executors.newFixedThreadPool(2)){var a=pool.submit(()->BundledBaseResources.prepare(loader(),directory,budget,()->true));var b=pool.submit(()->BundledBaseResources.prepare(loader(),directory,budget,()->true));assertEquals(a.get(5,TimeUnit.SECONDS).path(),b.get(5,TimeUnit.SECONDS).path());}
        assertEquals(0,budget.snapshot().live());PlatformCapabilities.require();assertThrows(IllegalStateException.class,()->PlatformCapabilities.require(Object.class,"getChunkAtAsync"));
    }
}
