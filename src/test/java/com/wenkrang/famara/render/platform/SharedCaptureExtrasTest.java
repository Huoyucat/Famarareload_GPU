package com.wenkrang.famara.render.platform;

import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import com.wenkrang.famara.render.scene.LegacyEntity;
import org.bukkit.Chunk;
import static org.junit.jupiter.api.Assertions.*;

class SharedCaptureExtrasTest {
    @Test void closingPhotoDoesNotCloseEntitySessionWhileAnotherRegionIsUsingIt()throws Exception {
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);AtomicInteger closed=new AtomicInteger();
        class Owned implements ChunkCaptureService.Extras,AutoCloseable {public List<LegacyEntity> capture(Chunk chunk){entered.countDown();try{if(!release.await(3,TimeUnit.SECONDS))throw new AssertionError();}catch(InterruptedException e){throw new CompletionException(e);}assertEquals(0,closed.get());return List.of();}public void close(){closed.incrementAndGet();}}
        try(var pool=Executors.newSingleThreadExecutor()){var extras=new SharedCaptureExtras(new Owned());var done=pool.submit(()->extras.capture(null));assertTrue(entered.await(3,TimeUnit.SECONDS));extras.close();extras.close();assertEquals(0,closed.get());release.countDown();assertEquals(List.of(),done.get(3,TimeUnit.SECONDS));assertEquals(1,closed.get());assertThrows(CancellationException.class,()->extras.capture(null));}finally{release.countDown();}
    }
}
