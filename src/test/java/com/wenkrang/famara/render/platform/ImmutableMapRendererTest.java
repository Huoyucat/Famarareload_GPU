package com.wenkrang.famara.render.platform;

import org.bukkit.map.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class ImmutableMapRendererTest {
    @Test void everyCanvasGetsItsOwnImmutableFrameAndNewVersionsRedraw() {
        var renderer=new ImmutableMapRenderer(2);MapCanvas one=mock(MapCanvas.class),two=mock(MapCanvas.class);
        byte[] pixels=new byte[16384];Arrays.fill(pixels,(byte)200);assertTrue(renderer.publish(pixels,()->true));pixels[0]=4;
        renderer.render(null,one,null);renderer.render(null,two,null);renderer.render(null,one,null);
        verify(one,times(16384)).setPixel(anyInt(),anyInt(),eq((byte)200));verify(two,times(16384)).setPixel(anyInt(),anyInt(),eq((byte)200));
        Arrays.fill(pixels,(byte)255);assertFalse(renderer.publish(pixels,()->false));renderer.render(null,one,null);
        verify(one,times(16384)).setPixel(anyInt(),anyInt(),anyByte());
        assertTrue(renderer.publish(pixels,()->true));renderer.render(null,one,null);verify(one,times(16384)).setPixel(anyInt(),anyInt(),eq((byte)255));
    }

    @Test void sharedSourceCacheEvictsBuffersAndRequestsReloadOnlyForNewCanvas() {
        var cache=new MapFrameCache(1);java.util.concurrent.atomic.AtomicInteger reloads=new java.util.concurrent.atomic.AtomicInteger();
        var first=new ImmutableMapRenderer(2,cache,reloads::incrementAndGet);var second=new ImmutableMapRenderer(2,cache,()->{});
        byte[] pixels=new byte[16384];Arrays.fill(pixels,(byte)5);MapCanvas shown=mock(MapCanvas.class),fresh=mock(MapCanvas.class);
        first.publish(pixels,()->true);first.render(null,shown,null);second.publish(pixels,()->true);
        assertEquals(1,cache.size());first.render(null,shown,null);assertEquals(0,reloads.get());
        first.render(null,fresh,null);first.render(null,fresh,null);assertEquals(1,reloads.get());
        first.publish(pixels,()->true);first.render(null,fresh,null);verify(fresh,times(16384)).setPixel(anyInt(),anyInt(),eq((byte)5));
        assertEquals(1,cache.size());cache.clear();assertEquals(0,cache.size());
    }
}
