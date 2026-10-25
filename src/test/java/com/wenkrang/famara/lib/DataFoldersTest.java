package com.wenkrang.famara.lib;

import com.wenkrang.famara.render.platform.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class DataFoldersTest {
    @TempDir Path plugins;
    @Test void renamedPluginAutomaticallyUsesUniqueOldDataAndUpgradesConfigWithoutMovingPhotosOrLedger()throws Exception {
        Path old=Files.createDirectories(plugins.resolve("Famarareload"));String config="version: 3\nrender-distance: 32\nviewfinder-zoom-strength: 0.7\n";Files.writeString(old.resolve("config.yml"),config);
        Path pictures=Files.createDirectories(old.resolve("pictures"));Files.writeString(pictures.resolve("12.png"),"preserve-photo");Path ledger=Files.createDirectories(old.resolve("camera-ledger-v2"));Files.writeString(ledger.resolve("ledger.json"),"preserve-ledger");
        Files.createDirectories(plugins.resolve("Famara/clazz"));Path target=plugins.resolve("FamaraV2");assertEquals(old,DataFolders.select(target));
        var loaded=UnifiedRendererConfig.load(DataFolders.select(target),Files.readString(Path.of("src/main/resources/config.yml")));assertEquals(PhotoRuntime.Mode.TEXTURED,loaded.mode());assertEquals(32,loaded.renderer().shot().distance());assertEquals(0.7,loaded.yaml().getDouble("viewfinder-zoom-strength"));assertEquals(12,loaded.yaml().getInt("render-v2-schema"));assertEquals(2,loaded.renderer().capture().windowChunks());
        byte[] first=Files.readAllBytes(old.resolve("config.yml"));var again=UnifiedRendererConfig.load(DataFolders.select(target),Files.readString(Path.of("src/main/resources/config.yml")));assertTrue(again.changes().isEmpty());assertArrayEquals(first,Files.readAllBytes(old.resolve("config.yml")));assertEquals("preserve-photo",Files.readString(pictures.resolve("12.png")));assertEquals("preserve-ledger",Files.readString(ledger.resolve("ledger.json")));assertFalse(Files.exists(target));
        try(var files=Files.list(old)){assertEquals(1,files.filter(p->p.getFileName().toString().endsWith(".bak")).count());}
    }
    @Test void existingNewDataIsAuthoritativeAndAmbiguousLegacyFoldersAreNeverMerged()throws Exception {
        Path target=plugins.resolve("FamaraV2"),a=Files.createDirectories(plugins.resolve("Famarareload")),b=Files.createDirectories(plugins.resolve("Famara"));Files.writeString(a.resolve("config.yml"),"old-a");Files.writeString(b.resolve("config.yml"),"old-b");assertThrows(java.io.IOException.class,()->DataFolders.select(target));Files.createDirectories(target);Files.writeString(target.resolve("config.yml"),"new-authoritative");assertEquals(target,DataFolders.select(target));assertEquals("old-a",Files.readString(a.resolve("config.yml")));assertEquals("old-b",Files.readString(b.resolve("config.yml")));
    }
    @Test void freshInstallUsesNewNameAndUnrelatedExplicitPathsAreNotRedirected()throws Exception {
        assertEquals(plugins.resolve("FamaraV2"),DataFolders.select(plugins.resolve("FamaraV2")));Path unrelated=plugins.resolve("test-fixture");assertEquals(unrelated,DataFolders.select(unrelated));
    }
}
