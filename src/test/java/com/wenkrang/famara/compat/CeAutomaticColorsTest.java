package com.wenkrang.famara.compat;

import com.wenkrang.famara.lib.ColorManager;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class CeAutomaticColorsTest {
    @TempDir Path root;

    @BeforeEach void resetSources() {
        CeColorService.configure(List.of(), List.of(), path -> null);
        CeColorService.external(List.of(), List.of());
        CeColorService.generated(null, null);
        CeColorService.models(Map.of());
        CeColorService.diagnostics(root.resolve("ce-color-index.txt"));
    }

    private byte[] png(int rgb) throws Exception {
        BufferedImage image = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, 0xff000000 | rgb);
        var stream = new ByteArrayOutputStream();
        ImageIO.write(image, "png", stream);
        return stream.toByteArray();
    }
    private byte[] json(String text) { return text.getBytes(StandardCharsets.UTF_8); }
    private void file(Path folder, String name, byte[] bytes) throws Exception {
        Path path = folder.resolve(name);
        Files.createDirectories(path.getParent());
        Files.write(path, bytes);
    }
    private void zip(Path path, Map<String, byte[]> files) throws Exception {
        try (var stream = new ZipOutputStream(Files.newOutputStream(path))) {
            for (var entry : files.entrySet()) {
                stream.putNextEntry(new ZipEntry(entry.getKey()));
                stream.write(entry.getValue()); stream.closeEntry();
            }
        }
    }
    private PackColorIndex.Result valid(int rgb) {
        return new PackColorIndex.Result(Map.of("test:old", state -> rgb), Map.of(), List.of());
    }
    private ColorManager.View palette(Map<String, Integer> colors) {
        return new ColorManager.View(colors, colors, Map.of(), List.of());
    }

    @Test void generatedBlockstateCanResolvePlantTextureInConfiguredSourceFolder() throws Exception {
        Path output = root.resolve("generated"), configured = root.resolve("source-pack");
        file(output, "assets/minecraft/blockstates/note_block.json", json("""
                {"variants":{"note=1":{"model":"natures_spirit:block/gardenia_bottom"}}}
                """));
        file(configured, "assets/natures_spirit/models/block/gardenia_bottom.json", json("""
                {"parent":"minecraft:block/cross","textures":{"cross":"natures_spirit:block/gardenia_bottom"}}
                """));
        file(configured, "assets/natures_spirit/textures/block/gardenia_bottom.png", png(0x72ab34));
        CeColorService.configure(List.of(configured), List.of(), path -> null);
        long ticket = CeColorService.generated(output, null);
        CeColorService.rebuild(ticket);
        var snapshot = CeColorService.snapshot();
        assertEquals(0x72ab34, snapshot.result.stateColor("minecraft:note_block[note=1,instrument=harp,powered=false]"));
        assertTrue(snapshot.sources.contains(configured.toAbsolutePath()));
        assertTrue(Files.readString(root.resolve("ce-color-index.txt")).contains("source-pack"));
    }

    @Test void emptyGeneratedFolderDoesNotPreventZipAndExternalPackFallback() throws Exception {
        Path empty = Files.createDirectory(root.resolve("empty")), zip = root.resolve("external.zip");
        zip(zip, Map.of(
                "assets/natures_spirit/items/lush_fern.json", json("{\"model\":{\"type\":\"model\",\"model\":\"natures_spirit:item/lush_fern\"}}"),
                "assets/natures_spirit/models/item/lush_fern.json", json("{\"parent\":\"minecraft:item/generated\",\"textures\":{\"layer0\":\"natures_spirit:item/lush_fern\"}}"),
                "assets/natures_spirit/textures/item/lush_fern.png", png(0x12a456)));
        CeColorService.external(List.of(), List.of(zip));
        long ticket = CeColorService.generated(empty, root.resolve("missing-generated.zip"));
        CeColorService.rebuild(ticket);
        assertEquals(0x12a456, CeColorService.snapshot().result.itemColor("natures_spirit:lush_fern", PackColorIndex.ItemState.EMPTY));
    }

    @Test void finalZipOverridesSourceFileButSourceSuppliesMissingAssets() throws Exception {
        Path folder = root.resolve("source"), zip = root.resolve("final.zip");
        file(folder, "assets/test/models/item/plant.json", json("{\"textures\":{\"all\":\"test:plant\"}}"));
        file(folder, "assets/test/textures/plant.png", png(0xff0000));
        zip(zip, Map.of("assets/test/textures/plant.png", png(0x0000ff)));
        try (CePackSources sources = CePackSources.open(List.of(zip, folder))) {
            var result = PackColorIndex.build(sources.source(), name -> null);
            assertEquals(0x0000ff, result.itemColor("test:plant", PackColorIndex.ItemState.EMPTY));
        }
    }

    @Test void emptyOrTotallyUnresolvableItemResultsCannotEraseValidIndex() throws Exception {
        assertTrue(CeCompat.publish(valid(0x224422), CeCompat.requestRebuild()));
        var old = CeColorService.snapshot();
        assertFalse(CeCompat.publish(PackColorIndex.Result.EMPTY, CeCompat.requestRebuild()));
        assertSame(old, CeColorService.snapshot());
        var broken = new PackColorIndex.Result(Map.of("test:bad", state -> null), Map.of(), List.of("缺少纹理"));
        assertFalse(CeCompat.publish(broken, CeCompat.requestRebuild()));
        assertSame(old, CeColorService.snapshot());
        Path empty = Files.createDirectory(root.resolve("empty"));
        CeCompat.rebuildFromFolder(empty, CeCompat.requestRebuild());
        assertSame(old, CeColorService.snapshot());
    }

    @Test void corruptZipDoesNotPreventUsableFolderFallback() throws Exception {
        Path bad = root.resolve("bad.zip"), source = root.resolve("source");
        Files.writeString(bad, "坏压缩包");
        file(source, "assets/test/models/item/plant.json", json("{\"textures\":{\"layer0\":\"test:green\"}}"));
        file(source, "assets/test/textures/green.png", png(0x336633));
        try (CePackSources sources = CePackSources.open(List.of(bad, source))) {
            assertFalse(sources.issues().isEmpty());
            var result = PackColorIndex.build(sources.source(), name -> null);
            assertEquals(0x336633, result.itemColor("test:plant", PackColorIndex.ItemState.EMPTY));
        }
    }

    @Test void conditionalItemWithOnlyNonDefaultBranchStillCountsAsUsable() throws Exception {
        Path source = root.resolve("source");
        file(source, "assets/test/items/plant.json", json("""
                {"model":{"type":"minecraft:condition","property":"minecraft:custom_model_data","index":0,
                "on_true":{"type":"minecraft:model","model":"test:item/green"},"on_false":{"type":"minecraft:empty"}}}
                """));
        file(source, "assets/test/models/item/green.json", json("{\"textures\":{\"all\":\"test:green\"}}"));
        file(source, "assets/test/textures/green.png", png(0x123456));
        try (CePackSources sources = CePackSources.open(List.of(source))) {
            var result = PackColorIndex.build(sources.source(), name -> null);
            assertTrue(result.hasUsableColors());
            assertTrue(result.items().get("test:plant").usable());
            assertNull(result.itemColor("test:plant", PackColorIndex.ItemState.EMPTY));
            assertEquals(0x123456, result.itemColor("test:plant", new PackColorIndex.ItemState(List.of(true), List.of(), List.of(), "fixed", 0, 1)));
        }
    }

    @Test void associatedItemIdAndActualModelKeyCanBothDifferFromBlockId() {
        String block = "natures_spirit:large_lush_fern_upper", item = "natures_spirit:large_lush_fern";
        var result = new PackColorIndex.Result(Map.of("generated:custom_42", state -> 0x227733), Map.of(), List.of());
        var snapshot = new CeColorService.Snapshot(result, List.of(), List.of(),
                Map.of(item, new CeCompat.ItemModel("generated:custom_42", PackColorIndex.ItemState.EMPTY)));
        var session = new CeCompat.Session(palette(Map.of()), snapshot);
        assertEquals(0x227733, CeCompat.resolvePalette(block, "minecraft:note_block[note=1]", item, null, session).rgb());
        var manual = new CeCompat.Session(palette(Map.of(block, 0)), snapshot);
        assertEquals(0, CeCompat.resolvePalette(block, null, item, 0xffffff, manual).rgb());
    }

    @Test void missingCeObjectKeepsDiagnosticIdInsteadOfVanillaCarrierColor() throws Exception {
        assertTrue(CeCompat.publish(valid(0x223344), CeCompat.requestRebuild()));
        var snapshot = CeColorService.snapshot();
        var session = new CeCompat.Session(palette(Map.of("NOTE_BLOCK", 0x775533)), snapshot);
        var result = CeCompat.resolvePalette("natures_spirit:gardenia", "minecraft:note_block[note=2]", "natures_spirit:gardenia", null, session);
        assertEquals(CeCompat.NO_COLOR, result.rgb());
        assertEquals("natures_spirit:gardenia", result.id());
        CeCompat.writeDiagnostics(session);
        String text = Files.readString(root.resolve("ce-color-index.txt"));
        assertTrue(text.contains("natures_spirit:gardenia"));
        assertTrue(text.contains("note=2"));
        assertTrue(text.contains("关联物品="));
    }

    @Test void newerSourcesAndItemMappingsRemainTogetherWhenOlderBuildFinishesLate() {
        long old = CeCompat.requestRebuild(), current = CeCompat.requestRebuild();
        Path newSource = root.resolve("new-source");
        CeColorService.models(Map.of("test:item", new CeCompat.ItemModel("test:real", PackColorIndex.ItemState.EMPTY)));
        assertTrue(CeColorService.publish(valid(0x123456), current, List.of(newSource), List.of()));
        assertFalse(CeColorService.publish(valid(0xffffff), old, List.of(root.resolve("old-source")), List.of()));
        assertEquals(List.of(newSource), CeColorService.snapshot().sources);
        assertEquals("test:real", CeColorService.snapshot().models.get("test:item").resource());
    }

    @Test void explicitModernEmptyModelIsNotReplacedByLegacyAlias() throws Exception {
        Path source = root.resolve("source");
        file(source, "assets/test/items/plant.json", json("{\"model\":{\"type\":\"minecraft:empty\"}}"));
        file(source, "assets/test/models/item/plant.json", json("{\"textures\":{\"all\":\"test:green\"}}"));
        file(source, "assets/test/textures/green.png", png(0x123456));
        try (CePackSources sources = CePackSources.open(List.of(source))) {
            var result = PackColorIndex.build(sources.source(), name -> null);
            assertNull(result.itemColor("test:plant", PackColorIndex.ItemState.EMPTY));
            assertFalse(result.items().get("test:plant").usable());
            assertTrue(result.legacyItems().get("test:plant").usable());
        }
    }

    @Test void closedTemporaryGeneratedFilesystemCannotBlockValidDiskPack() throws Exception {
        Path temporaryZip = root.resolve("temporary.zip");
        java.nio.file.Path stale;
        try (var fs = java.nio.file.FileSystems.newFileSystem(
                java.net.URI.create("jar:" + temporaryZip.toUri()), Map.of("create", "true"))) {
            stale = fs.getPath("/temporary-generated");
            Files.createDirectories(stale);
        }
        Path source = root.resolve("source");
        file(source, "assets/test/models/item/plant.json", json("{\"textures\":{\"all\":\"test:green\"}}"));
        file(source, "assets/test/textures/green.png", png(0x123456));
        try (CePackSources sources = CePackSources.open(List.of(stale, source))) {
            var result = PackColorIndex.build(sources.source(), name -> null);
            assertEquals(0x123456, result.itemColor("test:plant", PackColorIndex.ItemState.EMPTY));
        }
    }

}
