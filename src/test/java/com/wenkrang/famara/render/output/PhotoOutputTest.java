package com.wenkrang.famara.render.output;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.*;
import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class PhotoOutputTest {
    @TempDir
    Path root;

    private MapPaletteSnapshot palette() {
        return new MapPaletteSnapshot("fixture-v1", List.of(new MapPaletteSnapshot.Entry(4, 0),
                new MapPaletteSnapshot.Entry(128, 0xff0000), new MapPaletteSnapshot.Entry(255, 0xffffff)));
    }

    private ImmutablePhoto photo() {
        int[] rgb = new int[16384];
        for (int i = 0; i < rgb.length; i++) rgb[i] = i % 2 == 0 ? 0 : 0xff0000;
        return new ImmutablePhoto(rgb, palette().quantize(rgb), Map.of("engine", "textured-v2-offline", "palette", palette().version(), "request", "fixture"));
    }

    @Test
    void unsignedIndicesAndRealBlackArePreserved() {
        var p = palette();
        assertEquals(4, p.nearest(0) & 255);
        assertEquals(128, p.nearest(0xff0000) & 255);
        assertEquals(255, p.nearest(0xffffff) & 255);
        assertEquals(0xff0000, p.color((byte) 128));
        assertThrows(IllegalArgumentException.class, () -> p.color((byte) 0));
        assertThrows(IllegalArgumentException.class, () -> new MapPaletteSnapshot.Entry(3, 0));
    }

    @Test
    void quantizationIsDeterministicAndMatchesScalarReference() {
        var p = palette();
        int[] rgb = new int[16384];
        Random random = new Random(88);
        for (int i = 0; i < rgb.length; i++) rgb[i] = random.nextInt(0x1000000);
        byte[] result = p.quantize(rgb);
        assertArrayEquals(result, p.quantize(rgb));
        for (int i = 0; i < rgb.length; i++) assertEquals(p.nearest(rgb[i]), result[i]);
    }

    @Test
    void publishedImageDoesNotExposeItsMutableArrays() {
        ImmutablePhoto p = photo();
        int[] rgb = p.copyRgb();
        byte[] map = p.copyMap();
        rgb[0] = 123;
        map[0] = 0;
        assertEquals(0, p.copyRgb()[0]);
        assertEquals(4, p.copyMap()[0]);
        assertThrows(UnsupportedOperationException.class, () -> p.metadata().clear());
    }

    @Test
    void committedPhotoCanBeRestoredWithoutReRendering() throws Exception {
        DiskPhotoStore store = new DiskPhotoStore(root);
        ImmutablePhoto original = photo();
        var stored = store.commit(42, original);
        assertTrue(Files.isRegularFile(stored.png()));
        assertFalse(store.isPending(42));
        ImmutablePhoto loaded = store.loadCommitted(42).orElseThrow();
        assertArrayEquals(original.copyRgb(), loaded.copyRgb());
        assertArrayEquals(original.copyMap(), loaded.copyMap());
        assertEquals(original.metadata(), loaded.metadata());
        assertEquals(List.of(42), store.committedIds());
    }

    @Test
    void existingHistoryIsNeverOverwrittenOrDeleted() throws Exception {
        Files.write(root.resolve("42.png"), new byte[]{1, 2, 3});
        DiskPhotoStore store = new DiskPhotoStore(root);
        assertThrows(FileAlreadyExistsException.class, () -> store.commit(42, photo()));
        assertArrayEquals(new byte[]{1, 2, 3}, Files.readAllBytes(root.resolve("42.png")));
        assertFalse(store.isPending(42));
    }

    @Test
    void errorBeforePublicationCannotExposeAZeroBytePhoto() throws Exception {
        DiskPhotoStore store = new DiskPhotoStore(root, p -> {
            if (p == DiskPhotoStore.Checkpoint.AFTER_ENCODE) throw new IOException("injected");
        });
        assertThrows(IOException.class, () -> store.commit(12, photo()));
        assertFalse(Files.exists(root.resolve("12.png")));
        assertFalse(store.isPending(12));
        assertTrue(store.loadCommitted(12).isEmpty());
    }

    @Test
    void interruptedMultiFileCommitRemainsPendingAndNotReadable() throws Exception {
        DiskPhotoStore store = new DiskPhotoStore(root, p -> {
            if (p == DiskPhotoStore.Checkpoint.AFTER_PNG) throw new IOException("injected");
        });
        assertThrows(IOException.class, () -> store.commit(13, photo()));
        assertTrue(Files.isRegularFile(root.resolve("13.png")));
        assertTrue(store.isPending(13));
        assertTrue(store.loadCommitted(13).isEmpty());
        assertTrue(store.committedIds().isEmpty());
    }

    @Test
    void tamperedPngOrMapBytesAreRejected() throws Exception {
        DiskPhotoStore store = new DiskPhotoStore(root);
        var stored = store.commit(5, photo());
        Files.write(stored.png(), new byte[]{0});
        assertThrows(IOException.class, () -> store.loadCommitted(5));
    }

    @Test
    void mapDimensionsAndReservedValuesAreChecked() {
        assertThrows(IllegalArgumentException.class, () -> new ImmutablePhoto(new int[1], new byte[1], Map.of()));
        assertThrows(IllegalArgumentException.class, () -> new ImmutablePhoto(new int[16384], new byte[16384], Map.of()));
    }

    @Test
    void currentApiPaletteCanBeCopiedWithoutAWorldOrServer() {
        var copied = com.wenkrang.famara.render.platform.MapPaletteAdapter.snapshot();
        assertFalse(copied.entries().isEmpty());
        int index = copied.nearest(0x123456) & 255;
        assertTrue(index >= 4 && index <= 255);
        assertTrue(copied.version().startsWith("bukkit-snapshot-"));
    }

}
