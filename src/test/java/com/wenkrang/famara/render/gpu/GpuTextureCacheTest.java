package com.wenkrang.famara.render.gpu;

import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class GpuTextureCacheTest {
    @Test void pinnedGenerationCannotBeEvictedOrOverbookedAndReloadInvalidatesItAfterRelease() {
        var memory=new GpuMemoryBudget(100,100);var closes=new AtomicInteger();
        try(var cache=new GpuTextureCache<AutoCloseable>(100)) {
            var a=new GpuTextureCache.Key(1,"old","stone");var b=new GpuTextureCache.Key(2,"new","stone");
            var first=cache.acquire(a,60,()->{var lease=memory.reserve("textures",60);return ()->{closes.incrementAndGet();lease.close();};});
            var hit=cache.acquire(a,60,()->{throw new AssertionError("Expected cache hit");});assertSame(first.value(),hit.value());
            assertThrows(BackendException.class,()->cache.acquire(b,60,()->{throw new AssertionError("Must refuse before allocating");}));
            cache.invalidateExcept(2,"new");first.close();assertEquals(60,memory.snapshot().live());hit.close();assertEquals(0,memory.snapshot().live());
            var next=cache.acquire(b,60,()->{var lease=memory.reserve("textures",60);return ()->{closes.incrementAndGet();lease.close();};});next.close();
            assertEquals(1,cache.snapshot().hits());assertEquals(60,cache.snapshot().live());
        }
        assertEquals(0,memory.snapshot().live());assertEquals(2,closes.get());
    }
    @Test void closeDefersPinnedDisposalAndOverflowNeverBypassesSubBudget() {
        var cache=new GpuTextureCache<AutoCloseable>(Long.MAX_VALUE);var disposed=new AtomicInteger();
        var lease=cache.acquire(new GpuTextureCache.Key(1,"a","a"),Long.MAX_VALUE-4,()->disposed::incrementAndGet);
        assertThrows(BackendException.class,()->cache.acquire(new GpuTextureCache.Key(1,"a","b"),10,()->{throw new AssertionError("Overflow admitted");}));
        cache.close();assertEquals(0,disposed.get());lease.close();assertEquals(1,disposed.get());assertEquals(0,cache.snapshot().live());
    }
    @Test void tinyEntriesHaveAnIndependentMetadataCountLimitAndKeysAreBounded() {
        var disposed=new AtomicInteger();var cache=new GpuTextureCache<AutoCloseable>(1000000,2);
        var a=cache.acquire(new GpuTextureCache.Key(1,"a","1"),1,()->disposed::incrementAndGet);var b=cache.acquire(new GpuTextureCache.Key(1,"a","2"),1,()->disposed::incrementAndGet);
        assertThrows(BackendException.class,()->cache.acquire(new GpuTextureCache.Key(1,"a","3"),1,()->{throw new AssertionError("Pinned metadata cap bypassed");}));
        a.close();try(var c=cache.acquire(new GpuTextureCache.Key(1,"a","3"),1,()->disposed::incrementAndGet)){assertEquals(2,cache.snapshot().entries());assertEquals(1,disposed.get());}
        b.close();cache.close();assertEquals(3,disposed.get());assertThrows(IllegalArgumentException.class,()->new GpuTextureCache.Key(1,"a","x".repeat(257)));
    }
}
