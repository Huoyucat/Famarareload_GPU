package com.wenkrang.famara.lib;

import com.wenkrang.famara.Famara;
import com.wenkrang.famara.compat.RuntimeCompat;
import com.wenkrang.famara.compat.LegacyBlockColors;
import com.wenkrang.famara.render.RenderTemp;
import org.bukkit.Material;
import org.bukkit.block.data.BlockData;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Logger;

/** 用户覆盖、内置颜色和原版状态地图色的统一解析入口。 */
public final class ColorManager {
    private static final Logger LOG = Logger.getLogger("FamaraV2");
    private static final Map<String, String> ALIASES = Map.of(
            "DARGON_HEAD", "DRAGON_HEAD", "COBBLESOTNE_STAIRS", "COBBLESTONE_STAIRS",
            "BLACK_STATINED_GLASS_PANE", "BLACK_STAINED_GLASS_PANE",
            "BROWN_GLASS_STAINED_GLASS", "BROWN_STAINED_GLASS",
            "BROWN__STAINED_GLASS_PANE", "BROWN_STAINED_GLASS_PANE",
            "OAK_LEAVESC", "OAK_LEAVES", "CHAIN", "IRON_CHAIN", "GRASS", "SHORT_GRASS", "SCUTE", "TURTLE_SCUTE");
    public static final CopyOnWriteArrayList<String> excludingBlocks = new CopyOnWriteArrayList<>();
    private static volatile View current = new View(Map.of(), Map.of(), Map.of(), List.of());
    private static Runnable onChange = () -> {};
    private static YamlConfiguration legacy = yaml();
    private static YamlConfiguration overrides = yaml();
    private static Map<String, Integer> defaults = Map.of();
    private static Map<String, Integer> mapColors = Map.of();
    private static File folder;

    private ColorManager() {}

    /** 一张照片固定持有此视图；缓存归属视图，旧请求无法回填新代。 */
    public static final class View {
        private final Map<String, Integer> configured;
        private final Map<String, Integer> explicit;
        private final Map<String, Integer> fallback;
        private final List<String> issues;
        private final ConcurrentHashMap<BlockData, Integer> states = new ConcurrentHashMap<>();

        public View(Map<String, Integer> configured, Map<String, Integer> explicit,
                    Map<String, Integer> fallback, List<String> issues) {
            this.configured = Map.copyOf(configured);
            this.explicit = Map.copyOf(explicit);
            this.fallback = Map.copyOf(fallback);
            this.issues = List.copyOf(issues);
        }

        public Integer configured(String id) { return configured.get(normalize(id)); }
        public Integer vanilla(String name) {
            String key = normalize(name);
            Integer value = configured.get(key);
            return value != null ? value : fallback.get(key);
        }
        public Integer explicit(String id) { return explicit.get(normalize(id)); }
        public List<String> issues() { return issues; }

        /** BlockData 来自不可变区块快照；这里只读其状态，不读取世界或实体。 */
        public Integer block(BlockData data) {
            if (data == null) return null;
            Integer value = configured(data.getMaterial().name());
            if (value != null) return value;
            return states.computeIfAbsent(data, state -> {
                Integer rgb = RuntimeCompat.mapColor(state);
                if (rgb == null) rgb = LegacyBlockColors.color(state);
                return rgb != null ? rgb : fallback.get(state.getMaterial().name());
            });
        }
    }

    public static View view() { return current; }
    public static Integer configColor(String id) { return current.configured(id); }
    public static synchronized void onChange(Runnable action) { onChange = action; }

    public static String normalize(String id) {
        if (id.indexOf(':') >= 0) return id;
        String key = id.toUpperCase(Locale.ROOT);
        return ALIASES.getOrDefault(key, key);
    }

    /** 保留 CE ID 中的点和斜线，不把它们误当 YAML 路径。 */
    public static YamlConfiguration yaml() {
        YamlConfiguration result = new YamlConfiguration();
        result.options().pathSeparator((char) 1);
        return result;
    }

    public static Integer readRgb(ConfigurationSection section) {
        if (section == null) return null;
        int rgb = 0;
        for (String channel : List.of("r", "g", "b")) {
            Object value = section.get(channel);
            if (!(value instanceof Integer || value instanceof Long)) return null;
            long n = ((Number) value).longValue();
            if (n < 0 || n > 255) return null;
            rgb = (rgb << 8) | (int) n;
        }
        return rgb;
    }

    public static Map<String, Integer> parse(YamlConfiguration yaml, List<String> issues) {
        Map<String, Integer> result = new LinkedHashMap<>();
        List<String> keys = new ArrayList<>(yaml.getKeys(false));
        // 正确拼写优先；别名只补空缺，不覆盖合法纯黑。
        keys.sort(Comparator.comparing(k -> !normalize(k).equals(k)));
        for (String key : keys) {
            if (key.equals("version")) continue;
            Integer rgb = readRgb(yaml.getConfigurationSection(key));
            if (rgb == null) issues.add(key + "：r/g/b 必须完整且为 0–255 的整数");
            else result.putIfAbsent(normalize(key), rgb);
        }
        return Map.copyOf(result);
    }

    public static synchronized void loadColor() {
        try {
            YamlConfiguration builtIn = yaml();
            try (InputStream in = Famara.getPlugin().getResource("colors.yml")) {
                if (in == null) throw new IOException("缺少内置 colors.yml");
                builtIn.load(new InputStreamReader(in, StandardCharsets.UTF_8));
            }
            Map<String, Integer> maps = new HashMap<>();
            for (Material material : Material.values()) {
                if (material.isLegacy() || !material.isBlock() || material.isAir()) continue;
                if (defaults.containsKey(material.name())) continue;
                try {
                    BlockData state = material.createBlockData();
                    Integer rgb = RuntimeCompat.mapColor(state);
                    if (rgb == null) rgb = LegacyBlockColors.color(state);
                    if (rgb != null) maps.put(material.name(), rgb);
                } catch (RuntimeException e) { LOG.warning("无法初始化方块回退颜色：" + material); }
            }
            load(Famara.dataFolder, builtIn, maps);
        } catch (Exception e) { throw new IllegalStateException("加载颜色配置失败，原文件未删除", e); }
    }

    /** 文件事务与 Bukkit 初始化分离，便于离线验证迁移及用户颜色保护。 */
    static synchronized void load(File dataFolder, YamlConfiguration builtIn, Map<String, Integer> maps) throws Exception {
        folder = dataFolder;
        defaults = parse(builtIn, new ArrayList<>());
        File file = new File(folder, "colors.yml");
        YamlConfiguration loaded = yaml();
        if (file.isFile()) loaded.load(file);
        if (!file.exists() || isPristineLegacy(loaded)) {
            if (file.exists()) Files.copy(file.toPath(), uniqueBackup(file));
            saveAtomically(builtIn, file.toPath());
            loaded = builtIn;
        }
        YamlConfiguration loadedOverrides = yaml();
        File overrideFile = new File(folder, "color-overrides.yml");
        if (overrideFile.isFile()) loadedOverrides.load(overrideFile);
        else {
            loadedOverrides.options().setHeader(List.of("手动颜色覆盖，优先于 colors.yml 和内置默认；支持原版名称和 CE ID，纯黑有效。"));
            saveAtomically(loadedOverrides, overrideFile.toPath());
        }
        legacy = loaded;
        overrides = loadedOverrides;
        mapColors = Map.copyOf(maps);
        publish();
        writeUpgradeReport();
    }

    /** 混合旧表不猜测用户意图，列出遮盖新默认的条目供管理员核对。 */
    private static void writeUpgradeReport() {
        Map<String, Integer> existing = parse(legacy, new ArrayList<>());
        Map<String, Integer> manual = parse(overrides, new ArrayList<>());
        List<String> differences = new ArrayList<>();
        existing.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            Integer replacement = defaults.get(entry.getKey());
            if (replacement != null && !replacement.equals(entry.getValue())) {
                differences.add(entry.getKey() + "：colors.yml=" + hex(entry.getValue())
                        + "，客户端新默认=" + hex(replacement)
                        + (manual.containsKey(entry.getKey()) ? "，color-overrides.yml 另有更高优先级覆盖" : "，当前保留旧表值"));
            }
        });
        String text = String.join(System.lineSeparator(), List.of(
                "客户端默认色升级核对",
                "未修改的已知发布默认表会备份后自动更新；修改过的混合表不覆盖。",
                "旧表与新默认不同的条目：" + differences.size(),
                "若希望完全采用新默认，请先停服备份并改名旧 colors.yml，再启动生成新表；保留 color-overrides.yml。",
                "", String.join(System.lineSeparator(), differences), ""));
        try { Files.writeString(new File(folder, "color-upgrade-report.txt").toPath(), text, StandardCharsets.UTF_8); }
        catch (IOException e) { LOG.warning("写入颜色升级核对失败：" + e.getMessage()); }
        if (!differences.isEmpty()) LOG.warning("保留了 " + differences.size()
                + " 个与客户端新默认不同的旧表颜色，详情见 color-upgrade-report.txt");
    }

    private static String hex(int rgb) { return String.format("#%06X", rgb); }

    private static Path uniqueBackup(File file) {
        return file.toPath().resolveSibling(file.getName() + ".legacy-" + System.currentTimeMillis() + ".bak");
    }

    private static boolean isPristineLegacy(YamlConfiguration yaml) throws Exception {
        List<String> signature = new ArrayList<>();
        for (String key : yaml.getKeys(false)) {
            if (key.equals("version")) continue;
            Integer rgb = readRgb(yaml.getConfigurationSection(key));
            if (rgb == null) return false;
            signature.add(key + "=" + rgb);
        }
        Collections.sort(signature);
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(String.join("\n", signature).getBytes(StandardCharsets.UTF_8)));
        try (InputStream in = ColorManager.class.getClassLoader().getResourceAsStream("legacy-color-baselines.txt")) {
            return in != null && new String(in.readAllBytes(), StandardCharsets.UTF_8).lines().anyMatch(hash::equals);
        }
    }

    public static void saveAtomically(YamlConfiguration yaml, Path file) throws IOException {
        Path temp = Files.createTempFile(file.toAbsolutePath().getParent(), "famara-colors-", ".tmp");
        try {
            Files.writeString(temp, yaml.saveToString(), StandardCharsets.UTF_8);
            try { Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException e) { Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temp); }
    }

    private static void publish() {
        List<String> issues = new ArrayList<>();
        Map<String, Integer> explicit = new HashMap<>(parse(legacy, issues));
        explicit.putAll(parse(overrides, issues));
        Map<String, Integer> configured = new HashMap<>(defaults);
        configured.putAll(explicit);
        current = new View(configured, explicit, mapColors, issues);
        issues.forEach(LOG::warning);
        long ambiguous = explicit.entrySet().stream().filter(e -> e.getValue() == 0 && e.getKey().indexOf(':') < 0).count();
        if (ambiguous > 0) LOG.warning("保留了 " + ambiguous + " 个已有纯黑配色；无法确认其是否为旧占位，可在 color-overrides.yml 显式修正。");
    }

    public static synchronized void setColor(String name, int red, int green, int blue) {
        if (red < 0 || red > 255 || green < 0 || green > 255 || blue < 0 || blue > 255)
            throw new IllegalArgumentException("r/g/b 必须在 0–255 之间");
        String key = normalize(name);
        YamlConfiguration next = yaml();
        try {
            next.loadFromString(overrides.saveToString());
            next.createSection(key, Map.of("r", red, "g", green, "b", blue));
            saveAtomically(next, new File(folder, "color-overrides.yml").toPath());
        } catch (Exception e) { throw new IllegalStateException("保存颜色失败，现有配色保持不变", e); }
        overrides = next;
        publish();
        onChange.run();
    }

    public static void setColor(String name, String r, String g, String b) {
        setColor(name, Integer.parseInt(r), Integer.parseInt(g), Integer.parseInt(b));
    }

    public static void getBlockColor(BlockData data, RenderTemp rt, View palette) {
        Integer rgb = palette.block(data);
        if (rgb == null) {
            rt.color.set(255, 0, 255);
            String name = data == null ? "unknown:block" : data.getMaterial().name();
            if (!rt.failedBlocks.contains(name)) rt.failedBlocks.add(name);
        } else rt.color.set((rgb >> 16) & 255, (rgb >> 8) & 255, rgb & 255);
    }
}
