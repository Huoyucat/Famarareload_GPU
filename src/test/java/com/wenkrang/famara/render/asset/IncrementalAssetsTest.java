package com.wenkrang.famara.render.asset;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import static org.junit.jupiter.api.Assertions.*;

class IncrementalAssetsTest {
    @TempDir Path root;
    private RenderFixtures fixture() throws Exception {
        RenderFixtures f=new RenderFixtures();f.png("test:tile",2,2,0xffff0000,0xff00ff00);
        f.cube("test:block/model","test:tile");f.block("test:block","test:block/model");return f;
    }
    @Test void readCursorYieldsAndHasNoOpenDependencyAfterResultTransfer() throws Exception {
        RenderFixtures f=fixture();Path zip=root.resolve("pack.zip");
        try(ZipOutputStream out=new ZipOutputStream(Files.newOutputStream(zip))) {
            for(var e:f.files.entrySet()) {out.putNextEntry(new ZipEntry(e.getKey()));out.write(e.getValue());out.closeEntry();}
        }
        ResourcePack result;int slices=0;
        try(ResourcePackCursor cursor=new ResourcePackCursor(zip,RenderFixtures.LIMITS)) {
            assertFalse(cursor.step(1,0,()->0));assertThrows(IllegalStateException.class,cursor::result);
            while(!cursor.step(1,Long.MAX_VALUE,System::nanoTime))assertTrue(++slices<100);
            result=cursor.result();
        }
        Files.delete(zip);
        assertTrue(slices>3);assertEquals(f.pack().paths(),result.paths());
        assertEquals(6,new ModelCompiler(result,RenderFixtures.LIMITS).compile(ResourceKey.parse("test:block/model")).quads().size());
    }
    @Test void compilerYieldsAndFrozenResultSurvivesCursorClose() throws Exception {
        RenderFixtures f=fixture();AssetGeneration generation;
        try(var cursor=AssetGeneration.builder(1,f.pack(),RenderFixtures.LIMITS)) {
            assertFalse(cursor.step(1,0,()->0));assertEquals(0,cursor.completedDefinitions());
            while(!cursor.step(1,Long.MAX_VALUE,System::nanoTime)) {}
            generation=cursor.result();assertTrue(cursor.reservedWorkingBytes()>f.pack().retainedBytes());
        }
        var actual=generation.resolve(RenderFixtures.vanilla("test:block",1),0,0,0,0).getFirst();
        var reference=f.generation(1).resolve(RenderFixtures.vanilla("test:block",1),0,0,0,0).getFirst();
        assertEquals(reference.quads(),actual.quads());
    }
    @Test void individuallySmallJsonFilesCannotBypassSharedWorkingMemoryLimit() throws Exception {
        RenderFixtures f=fixture();
        for(int i=0;i<50;i++)f.block("test:block_"+i,"test:block/model");
        AssetLimits limits=new AssetLimits(1000,4096,20000,100,100,1000,64,64,10000,16);
        var e=assertThrows(AssetException.class,()->AssetGeneration.compile(1,f.pack(),limits));
        assertEquals(AssetException.Code.LIMIT,e.code());
    }
    @Test void repositoryByteBudgetDoesNotEvictAnOutstandingLease() throws Exception {
        RenderFixtures f=fixture();AssetGeneration first=f.generation(1),second=f.generation(2);
        try(AssetRepository repository=new AssetRepository(2,first.estimatedRetainedBytes()+second.estimatedRetainedBytes()-1)) {
            assertTrue(repository.publish(repository.requestBuild(),first));
            var lease=repository.acquire();long next=repository.requestBuild();
            assertFalse(repository.publish(next,second));assertEquals(1,lease.generation().id());
            lease.close();assertTrue(repository.publish(next,second));assertEquals(1,repository.retainedGenerations());
        }
    }
}
