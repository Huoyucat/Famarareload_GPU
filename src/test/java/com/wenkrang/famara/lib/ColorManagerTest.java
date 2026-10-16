package com.wenkrang.famara.lib;

import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.block.data.BlockData;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ColorManagerTest {
    @TempDir Path folder;

    @AfterEach void resetCallback() { ColorManager.onChange(() -> {}); }

    private static YamlConfiguration yaml(String text) throws Exception {
        YamlConfiguration yaml = ColorManager.yaml();
        yaml.loadFromString(text);
        return yaml;
    }

    private static YamlConfiguration builtin() throws Exception {
        try (var input = ColorManagerTest.class.getResourceAsStream("/colors.yml")) {
            assertNotNull(input);
            return yaml(new String(input.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"r: 1\ng: 2", "r: '1'\ng: 2\nb: 3", "r: 1.5\ng: 2\nb: 3",
            "r: -1\ng: 0\nb: 0", "r: 256\ng: 0\nb: 0", "r: true\ng: 0\nb: 0"})
    void rejectsIncompleteOrInvalidRgb(String channels) throws Exception {
        assertNull(ColorManager.readRgb(yaml(channels)));
    }

    @Test void blackIsValidAndCanonicalKeyWins() throws Exception {
        YamlConfiguration values = ColorManager.yaml();
        values.createSection("DEAD_BUSH", Map.of("r", 0, "g", 0, "b", 0));
        values.createSection("dead_bush", Map.of("r", 100, "g", 10, "b", 20));
        values.createSection("DARGON_HEAD", Map.of("r", 50, "g", 50, "b", 50));
        var result = ColorManager.parse(values, new ArrayList<>());
        assertEquals(0, result.get("DEAD_BUSH"));
        assertEquals(0x323232, result.get("DRAGON_HEAD"));
    }

    @Test void dottedAndSlashedCeIdsSurviveSaveAndLoad() throws Exception {
        YamlConfiguration values = ColorManager.yaml();
        values.createSection("test:chairs/wood.v2", Map.of("r", 1, "g", 2, "b", 3));
        var reloaded = yaml(values.saveToString());
        assertEquals(0x010203, ColorManager.parse(reloaded, new ArrayList<>()).get("test:chairs/wood.v2"));
    }

    @Test void builtinTablesAreValidAndHaveNoPlaceholderBlack() throws Exception {
        List<String> issues = new ArrayList<>();
        var colors = ColorManager.parse(builtin(), issues);
        assertTrue(issues.isEmpty());
        assertEquals(1242, colors.size());
        assertFalse(colors.containsValue(0));
        assertEquals(0x6b4e28, colors.get("DEAD_BUSH"));
        assertEquals(0xc4ab7a, colors.get("TALL_DRY_GRASS"));
        assertEquals(0x2c6d0d, colors.get("JUNGLE_LEAVES"));
        for (String name : colors.keySet()) assertTrue(name.matches("[A-Z][A-Z0-9_]*"), name);
        assertArrayEquals(Files.readAllBytes(Path.of("colors.yml")), Files.readAllBytes(Path.of("colors_new.yml")));
        assertArrayEquals(Files.readAllBytes(Path.of("colors.yml")), Files.readAllBytes(Path.of("src/main/resources/colors.yml")));
    }

    @Test void legacyMigrationRequiresExactPublishedBaselineAndCreatesBackup() throws Exception {
        try (var input = getClass().getResourceAsStream("/legacy-colors.yml")) {
            assertNotNull(input);
            Files.copy(input, folder.resolve("colors.yml"));
        }
        byte[] before = Files.readAllBytes(folder.resolve("colors.yml"));
        ColorManager.load(folder.toFile(), builtin(), Map.of("REDSTONE_WIRE", 0xff6600));
        assertEquals(8, yaml(Files.readString(folder.resolve("colors.yml"))).getInt("version"));
        try (var files = Files.list(folder)) {
            Path backup = files.filter(p -> p.getFileName().toString().endsWith(".bak")).findFirst().orElseThrow();
            assertArrayEquals(before, Files.readAllBytes(backup));
        }
        assertEquals(0x2c6d0d, ColorManager.view().configured("JUNGLE_LEAVES"));
    }

    @Test void pristineV7UpgradesToClientColorsAndKeepsIndependentOverrides() throws Exception {
        try (var input = getClass().getResourceAsStream("/legacy-colors-v7.yml")) {
            assertNotNull(input);
            Files.copy(input, folder.resolve("colors.yml"));
        }
        Files.writeString(folder.resolve("color-overrides.yml"), """
                test:custom:
                  r: 0
                  g: 0
                  b: 0
                """);
        byte[] original = Files.readAllBytes(folder.resolve("colors.yml"));
        ColorManager.load(folder.toFile(), builtin(), Map.of());
        assertEquals(0xe5acc2, ColorManager.view().configured("CHERRY_LEAVES"));
        assertEquals(0, ColorManager.view().configured("test:custom"));
        assertNotNull(ColorManager.view().configured("RED_CONCRETE_STAIRS"));
        try (var files = Files.list(folder)) {
            Path backup = files.filter(f -> f.getFileName().toString().endsWith(".bak")).findFirst().orElseThrow();
            assertArrayEquals(original, Files.readAllBytes(backup));
        }
        assertTrue(Files.readString(folder.resolve("color-upgrade-report.txt")).contains("条目：0"));
        ColorManager.load(folder.toFile(), builtin(), Map.of());
        try (var files = Files.list(folder)) {
            assertEquals(1, files.filter(f -> f.getFileName().toString().endsWith(".bak")).count());
        }
    }

    @Test void modifiedV7IsPreservedAndReportsWhichOldColorsShadowClientDefaults() throws Exception {
        YamlConfiguration modified;
        try (var input = getClass().getResourceAsStream("/legacy-colors-v7.yml")) {
            assertNotNull(input);
            modified = yaml(new String(input.readAllBytes(), StandardCharsets.UTF_8));
        }
        modified.createSection("test:custom", Map.of("r", 0, "g", 0, "b", 0));
        Files.writeString(folder.resolve("colors.yml"), modified.saveToString());
        byte[] original = Files.readAllBytes(folder.resolve("colors.yml"));
        ColorManager.load(folder.toFile(), builtin(), Map.of());
        assertArrayEquals(original, Files.readAllBytes(folder.resolve("colors.yml")));
        assertEquals(0xfccbe7, ColorManager.view().configured("CHERRY_LEAVES"));
        assertEquals(0, ColorManager.view().configured("test:custom"));
        String report = Files.readString(folder.resolve("color-upgrade-report.txt"));
        assertTrue(report.contains("CHERRY_LEAVES"));
        assertTrue(report.contains("#FCCBE7"));
        assertTrue(report.contains("#E5ACC2"));
        assertTrue(Files.readAllLines(folder.resolve("color-upgrade-report.txt")).size() > 4);
    }

    @Test void modifiedLegacyAndExplicitBlackAreNeverOverwritten() throws Exception {
        String custom = "version: 3\nSTONE:\n  r: 0\n  g: 0\n  b: 0\n";
        Files.writeString(folder.resolve("colors.yml"), custom);
        ColorManager.load(folder.toFile(), builtin(), Map.of("REDSTONE_WIRE", 0xff6600));
        assertEquals(custom, Files.readString(folder.resolve("colors.yml")));
        assertEquals(0, ColorManager.view().configured("STONE"));
        assertEquals(0xff6600, ColorManager.view().vanilla("REDSTONE_WIRE"));
        assertNotNull(ColorManager.view().configured("OAK_PLANKS"));
    }

    @Test void manualUpdatesPublishNewViewAndPersistWithoutTouchingLegacy() throws Exception {
        ColorManager.load(folder.toFile(), builtin(), Map.of());
        byte[] original = Files.readAllBytes(folder.resolve("colors.yml"));
        var old = ColorManager.view();
        Integer oldStone = old.configured("STONE");
        AtomicInteger changed = new AtomicInteger();
        ColorManager.onChange(changed::incrementAndGet);
        ColorManager.setColor("STONE", 1, 2, 3);
        assertEquals(0x010203, ColorManager.view().configured("STONE"));
        assertEquals(oldStone, old.configured("STONE"));
        assertEquals(1, changed.get());
        assertArrayEquals(original, Files.readAllBytes(folder.resolve("colors.yml")));
        ColorManager.setColor("test:chair.v2", 0, 0, 0);
        ColorManager.load(folder.toFile(), builtin(), Map.of());
        assertEquals(0x010203, ColorManager.view().configured("STONE"));
        assertEquals(0, ColorManager.view().configured("test:chair.v2"));
    }

    @Test void failedSaveDoesNotPublishHalfUpdatedColors() throws Exception {
        ColorManager.load(folder.toFile(), builtin(), Map.of());
        var before = ColorManager.view();
        Path target = folder.resolve("color-overrides.yml");
        Files.move(target, folder.resolve("old-overrides.yml"));
        Files.createDirectory(target);
        Files.writeString(target.resolve("keep.txt"), "阻止目录被替换");
        assertThrows(IllegalStateException.class, () -> ColorManager.setColor("STONE", 4, 5, 6));
        assertSame(before, ColorManager.view());
    }

    @Test void stateMapColorsDoNotCollapseToMaterialOrConfuseBlackWithMissing() {
        var view = new ColorManager.View(Map.of(), Map.of(), Map.of("STONE", 0x666666), List.of());
        BlockData first = mock(BlockData.class), second = mock(BlockData.class);
        when(first.getMaterial()).thenReturn(Material.STONE);
        when(second.getMaterial()).thenReturn(Material.STONE);
        try (var compat = mockStatic(com.wenkrang.famara.compat.RuntimeCompat.class)) {
            compat.when(() -> com.wenkrang.famara.compat.RuntimeCompat.mapColor(first)).thenReturn(0);
            compat.when(() -> com.wenkrang.famara.compat.RuntimeCompat.mapColor(second)).thenReturn(0xabcdef);
            assertEquals(0, view.block(first));
            assertEquals(0xabcdef, view.block(second));
            assertEquals(0, view.block(first));
            compat.verify(() -> com.wenkrang.famara.compat.RuntimeCompat.mapColor(first), times(1));
        }
        var manual = new ColorManager.View(Map.of("STONE", 0), Map.of("STONE", 0), Map.of(), List.of());
        assertEquals(0, manual.block(second));
    }

    @Test void invalidUserEntryFallsBackWithoutMutatingFile() throws Exception {
        String text = "STONE:\n  r: 1\n  g: 2\n";
        Files.writeString(folder.resolve("colors.yml"), text);
        ColorManager.load(folder.toFile(), builtin(), Map.of());
        assertFalse(ColorManager.view().issues().isEmpty());
        assertNotNull(ColorManager.view().configured("STONE"));
        assertEquals(text, Files.readString(folder.resolve("colors.yml")));
    }
}
