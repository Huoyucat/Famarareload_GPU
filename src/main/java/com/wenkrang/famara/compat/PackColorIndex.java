package com.wenkrang.famara.compat;

import com.google.gson.*;
import com.wenkrang.famara.lib.ColorManager;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 从资源包解析代表色；不模拟完整客户端模型网格或 UV。 */
public final class PackColorIndex {
    public interface PackSource {
        Collection<String> list() throws IOException;
        byte[] read(String path) throws IOException;
    }

    public record ItemState(List<Boolean> flags, List<String> strings, List<Float> floats,
                            String displayContext, float damage, int count, Integer legacyCustomModelData) {
        public ItemState(List<Boolean> flags, List<String> strings, List<Float> floats,
                         String displayContext, float damage, int count) {
            this(flags, strings, floats, displayContext, damage, count, null);
        }
        public static final ItemState EMPTY = new ItemState(List.of(), List.of(), List.of(), "fixed", 0, 1);
        public ItemState {
            flags = List.copyOf(flags);
            strings = List.copyOf(strings);
            floats = List.copyOf(floats);
        }
    }

    @FunctionalInterface
    public interface ItemColor {
        Integer color(ItemState state);
        default boolean usable() {
            try { return color(ItemState.EMPTY) != null; }
            catch (RuntimeException e) { return false; }
        }
    }
    private static ItemColor compiled(Function<ItemState, Integer> evaluator, boolean usable) {
        return new ItemColor() {
            public Integer color(ItemState state) { return evaluator.apply(state); }
            public boolean usable() { return usable; }
        };
    }
    public record Rule(Predicate<Map<String, String>> condition, int specificity, int rgb) {}
    public record BlockColors(List<Rule> variants, List<Rule> multipart) {
        public BlockColors { variants = List.copyOf(variants); multipart = List.copyOf(multipart); }
        public Integer color(Map<String, String> props) {
            Rule best = null;
            for (Rule rule : variants) {
                if (rule.condition.test(props) && (best == null || rule.specificity > best.specificity)) best = rule;
            }
            List<Integer> colors = new ArrayList<>();
            if (best != null) colors.add(best.rgb);
            for (Rule rule : multipart) if (rule.condition.test(props)) colors.add(rule.rgb);
            return average(colors);
        }
    }
    public record Result(Map<String, ItemColor> items, Map<String, BlockColors> blockstates, List<String> issues,
                         Map<String, ItemColor> legacyItems) {
        public Result(Map<String, ItemColor> items, Map<String, BlockColors> blockstates, List<String> issues) {
            this(items, blockstates, issues, Map.of());
        }
        public static final Result EMPTY = new Result(Map.of(), Map.of(), List.of());
        public Result {
            items = Map.copyOf(items); blockstates = Map.copyOf(blockstates);
            issues = List.copyOf(issues); legacyItems = Map.copyOf(legacyItems);
        }
        public long usableItems() { return items.values().stream().filter(ItemColor::usable).count(); }
        public boolean hasUsableColors() { return !blockstates.isEmpty() || usableItems() > 0
                || legacyItems.values().stream().anyMatch(ItemColor::usable); }
        public Integer itemColor(String key, ItemState state) {
            ItemColor color = state.legacyCustomModelData != null ? legacyItems.get(key) : null;
            if (color == null) color = items.get(key);
            return color == null ? null : color.color(state);
        }
        public Integer stateColor(String state) {
            if (state == null) return null;
            String normalized = state.replace("Block{", "").replace("}", "");
            int start = normalized.indexOf('[');
            String name = start < 0 ? normalized : normalized.substring(0, start);
            BlockColors colors = blockstates.get(name);
            if (colors == null) return null;
            String props = start < 0 ? "" : normalized.substring(start + 1, normalized.lastIndexOf(']'));
            return colors.color(properties(props));
        }
    }

    private static final Pattern ITEM = Pattern.compile("assets/([^/]+)/items/(.+)\\.json");
    private static final Pattern BLOCKSTATE = Pattern.compile("assets/([^/]+)/blockstates/(.+)\\.json");
    private static final Pattern LEGACY_ITEM = Pattern.compile("assets/([^/]+)/models/item/(.+)\\.json");
    private static final String[] SUFFIXES = {"_top", "_side", "_bottom", "_front", "_end", "_inner", "_outer", "_back"};
    private final PackSource source;
    private final Function<String, Integer> vanilla;
    private final Map<String, Optional<Integer>> models = new HashMap<>();
    private final Map<String, Optional<Integer>> textures = new HashMap<>();
    private final Set<String> issues = new LinkedHashSet<>();

    private PackColorIndex(PackSource source, Function<String, Integer> vanilla) {
        this.source = source;
        this.vanilla = vanilla;
    }

    public static Result build(PackSource source) throws IOException {
        ColorManager.View palette = ColorManager.view();
        return build(source, palette::vanilla);
    }

    public static Result build(PackSource source, Function<String, Integer> vanilla) throws IOException {
        PackColorIndex parser = new PackColorIndex(source, vanilla);
        Map<String, ItemColor> items = new HashMap<>();
        Map<String, ItemColor> legacyItems = new HashMap<>();
        Map<String, BlockColors> states = new HashMap<>();
        for (String path : source.list()) {
            Matcher item = ITEM.matcher(path);
            Matcher block = BLOCKSTATE.matcher(path);
            Matcher legacy = LEGACY_ITEM.matcher(path);
            if (!item.matches() && !block.matches() && !legacy.matches()) continue;
            try {
                JsonObject json = parser.json(path);
                if (json == null) continue;
                if (item.matches()) {
                    ItemColor color = parser.itemNode(json.get("model"), path, 0);
                    items.put(item.group(1) + ":" + item.group(2), color);
                } else if (legacy.matches()) {
                    String modern = "assets/" + legacy.group(1) + "/items/" + legacy.group(2) + ".json";
                    ItemColor color = parser.legacyModel(legacy.group(1) + ":item/" + legacy.group(2), new HashSet<>());
                    String key = legacy.group(1) + ":" + legacy.group(2);
                    legacyItems.put(key, color);
                    // 两套格式可以同时存在，旧 CMD 按旧 overrides 解析；现代 empty 不被别名覆盖。
                    if (source.read(modern) == null) items.putIfAbsent(key, color);
                } else {
                    List<Rule> variants = new ArrayList<>();
                    List<Rule> multipart = new ArrayList<>();
                    if (json.has("variants")) {
                        for (var entry : json.getAsJsonObject("variants").entrySet()) {
                            Integer color = parser.variantColor(entry.getValue());
                            if (color == null) continue;
                            Map<String, String> props = properties(entry.getKey());
                            variants.add(new Rule(p -> matches(props, p), props.size(), color));
                        }
                    }
                    if (json.has("multipart")) {
                        for (JsonElement part : json.getAsJsonArray("multipart")) {
                            JsonObject o = part.getAsJsonObject();
                            Integer color = parser.variantColor(o.get("apply"));
                            if (color != null) multipart.add(new Rule(parser.condition(o.get("when")), 0, color));
                        }
                    }
                    if (!variants.isEmpty() || !multipart.isEmpty())
                        states.put(block.group(1) + ":" + block.group(2), new BlockColors(variants, multipart));
                }
            } catch (RuntimeException e) { parser.issues.add(path + "：" + e.getMessage()); }
        }
        return new Result(items, states, new ArrayList<>(parser.issues), legacyItems);
    }

    public static Map<String, String> properties(String props) {
        Map<String, String> result = new HashMap<>();
        if (props != null && !props.isBlank()) {
            for (String property : props.split(",")) {
                String[] kv = property.trim().split("=", 2);
                if (kv.length != 2) throw new IllegalArgumentException("非法状态属性：" + property);
                result.put(kv[0], kv[1]);
            }
        }
        return Map.copyOf(result);
    }

    private static boolean matches(Map<String, String> required, Map<String, String> actual) {
        return required.entrySet().stream().allMatch(e -> Arrays.asList(e.getValue().split("\\|"))
                .contains(actual.get(e.getKey())));
    }

    private Predicate<Map<String, String>> condition(JsonElement element) {
        if (element == null) return p -> true;
        JsonObject object = element.getAsJsonObject();
        List<Predicate<Map<String, String>>> checks = new ArrayList<>();
        for (var entry : object.entrySet()) {
            if (entry.getKey().equals("OR") || entry.getKey().equals("AND")) {
                List<Predicate<Map<String, String>>> children = new ArrayList<>();
                for (JsonElement child : entry.getValue().getAsJsonArray()) children.add(condition(child));
                checks.add(entry.getKey().equals("OR")
                        ? p -> children.stream().anyMatch(c -> c.test(p))
                        : p -> children.stream().allMatch(c -> c.test(p)));
            } else {
                Map<String, String> required = Map.of(entry.getKey(), entry.getValue().getAsString());
                checks.add(p -> matches(required, p));
            }
        }
        return p -> checks.stream().allMatch(c -> c.test(p));
    }

    private Integer variantColor(JsonElement element) {
        if (element == null) return null;
        if (!element.isJsonArray()) return modelColor(element.getAsJsonObject().get("model").getAsString());
        long red = 0, green = 0, blue = 0, total = 0;
        for (JsonElement child : element.getAsJsonArray()) {
            JsonObject o = child.getAsJsonObject();
            Integer rgb = variantColor(child);
            long weight = Math.max(1, o.has("weight") ? o.get("weight").getAsInt() : 1);
            if (rgb == null) continue;
            red += ((rgb >> 16) & 255) * weight;
            green += ((rgb >> 8) & 255) * weight;
            blue += (rgb & 255) * weight;
            total += weight;
        }
        return total == 0 ? null : (int) (red / total) << 16 | (int) (green / total) << 8 | (int) (blue / total);
    }

    /** 旧版模型 overrides 采用阈值匹配，后出现的匹配项覆盖前项。 */
    private ItemColor legacyModel(String resource, Set<String> chain) {
        boolean root = chain.isEmpty();
        if (!chain.add(resource) || chain.size() > 64) {
            issues.add(resource + "：旧物品模型 overrides 循环或过深");
            return state -> null;
        }
        String[] location = location(resource);
        JsonObject definition = json("assets/" + location[0] + "/models/" + location[1] + ".json");
        Integer base = modelColor(resource);
        if (definition == null || !definition.has("overrides")) return state ->
                root && state.legacyCustomModelData != null && state.legacyCustomModelData != 0 ? null : base;
        record Override(Map<String, Float> predicates, ItemColor model) {}
        List<Override> overrides = new ArrayList<>();
        for (JsonElement entry : definition.getAsJsonArray("overrides")) {
            JsonObject object = entry.getAsJsonObject();
            Map<String, Float> predicates = new HashMap<>();
            boolean supported = true;
            for (var predicate : object.getAsJsonObject("predicate").entrySet()) {
                if (!Set.of("custom_model_data", "damage", "damaged").contains(predicate.getKey())) {
                    issues.add(resource + "：旧物品模型谓词暂不能静态确定：" + predicate.getKey());
                    supported = false;
                } else predicates.put(predicate.getKey(), predicate.getValue().getAsFloat());
            }
            if (!supported) continue;
            String target = object.get("model").getAsString();
            ItemColor child = legacyModel(target, new HashSet<>(chain));
            overrides.add(new Override(Map.copyOf(predicates), child));
        }
        return compiled(state -> {
            ItemColor selected = null;
            for (Override override : overrides) {
                boolean matches = override.predicates.entrySet().stream().allMatch(predicate -> {
                    float actual = switch (predicate.getKey()) {
                        case "custom_model_data" -> state.legacyCustomModelData == null ? 0 : state.legacyCustomModelData;
                        case "damage" -> state.damage;
                        case "damaged" -> state.damage > 0 ? 1 : 0;
                        default -> Float.NEGATIVE_INFINITY;
                    };
                    return actual >= predicate.getValue();
                });
                if (matches) selected = override.model;
            }
            if (selected != null) return selected.color(state);
            // 有自定义 CMD 但没有命中，不以原版载体颜色伪装成自定义物品取色成功。
            return state.legacyCustomModelData != null && state.legacyCustomModelData != 0 ? null : base;
        }, base != null || overrides.stream().anyMatch(override -> override.model.usable()));
    }

    private ItemColor itemNode(JsonElement element, String path, int depth) {
        if (element == null || depth > 32 || !element.isJsonObject()) return state -> null;
        JsonObject node = element.getAsJsonObject();
        String type = node.has("type") ? node.get("type").getAsString().replace("minecraft:", "") : "model";
        switch (type) {
            case "model": {
                Integer rgb = node.has("model") ? modelColor(node.get("model").getAsString()) : null;
                return state -> rgb;
            }
            case "empty": return state -> null;
            case "composite": {
                List<ItemColor> children = new ArrayList<>();
                for (JsonElement child : node.getAsJsonArray("models")) children.add(itemNode(child, path, depth + 1));
                return compiled(state -> average(children.stream().map(c -> c.color(state)).filter(Objects::nonNull).toList()),
                        children.stream().anyMatch(ItemColor::usable));
            }
            case "condition": {
                String property = node.get("property").getAsString().replace("minecraft:", "");
                int index = node.has("index") ? node.get("index").getAsInt() : 0;
                ItemColor yes = itemNode(node.get("on_true"), path, depth + 1);
                ItemColor no = itemNode(node.get("on_false"), path, depth + 1);
                if (!Set.of("custom_model_data", "damaged", "broken", "using_item").contains(property)) break;
                return compiled(state -> {
                    boolean value = switch (property) {
                        case "custom_model_data" -> index >= 0 && index < state.flags.size() && state.flags.get(index);
                        case "damaged" -> state.damage > 0;
                        case "broken" -> state.damage >= 1;
                        default -> false;
                    };
                    return (value ? yes : no).color(state);
                }, yes.usable() || no.usable());
            }
            case "select": {
                String property = node.get("property").getAsString().replace("minecraft:", "");
                if (!Set.of("custom_model_data", "display_context").contains(property)) break;
                int index = node.has("index") ? node.get("index").getAsInt() : 0;
                Map<String, ItemColor> cases = new HashMap<>();
                for (JsonElement entry : node.getAsJsonArray("cases")) {
                    JsonObject o = entry.getAsJsonObject();
                    ItemColor model = itemNode(o.get("model"), path, depth + 1);
                    JsonElement when = o.get("when");
                    if (when.isJsonArray()) for (JsonElement value : when.getAsJsonArray()) cases.put(value.getAsString(), model);
                    else cases.put(when.getAsString(), model);
                }
                ItemColor fallback = itemNode(node.get("fallback"), path, depth + 1);
                return compiled(state -> {
                    String value = property.equals("display_context") ? state.displayContext
                            : index >= 0 && index < state.strings.size() ? state.strings.get(index) : "";
                    return cases.getOrDefault(value, fallback).color(state);
                }, fallback.usable() || cases.values().stream().anyMatch(ItemColor::usable));
            }
            case "range_dispatch": {
                String property = node.get("property").getAsString().replace("minecraft:", "");
                if (!Set.of("custom_model_data", "damage", "count").contains(property)) break;
                int index = node.has("index") ? node.get("index").getAsInt() : 0;
                float scale = node.has("scale") ? node.get("scale").getAsFloat() : 1;
                NavigableMap<Float, ItemColor> ranges = new TreeMap<>();
                for (JsonElement entry : node.getAsJsonArray("entries")) {
                    JsonObject o = entry.getAsJsonObject();
                    ranges.put(o.get("threshold").getAsFloat(), itemNode(o.get("model"), path, depth + 1));
                }
                ItemColor fallback = itemNode(node.get("fallback"), path, depth + 1);
                return compiled(state -> {
                    float value = switch (property) {
                        case "damage" -> state.damage;
                        case "count" -> state.count;
                        default -> index >= 0 && index < state.floats.size() ? state.floats.get(index) : 0;
                    };
                    var selected = ranges.floorEntry(value * scale);
                    return (selected == null ? fallback : selected.getValue()).color(state);
                }, fallback.usable() || ranges.values().stream().anyMatch(ItemColor::usable));
            }
            default: break;
        }
        issues.add(path + "：暂不支持此物品模型节点/属性 " + node);
        return state -> null;
    }

    private JsonObject json(String path) {
        try {
            byte[] data = source.read(path);
            if (data == null) return null;
            return JsonParser.parseString(new String(data, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (Exception e) { issues.add(path + "：" + e.getMessage()); return null; }
    }

    private static String[] location(String loc) {
        int i = loc.indexOf(':');
        return i < 0 ? new String[]{"minecraft", loc} : new String[]{loc.substring(0, i), loc.substring(i + 1)};
    }

    private Integer modelColor(String model) {
        Optional<Integer> cached = models.get(model);
        if (cached != null) return cached.orElse(null);
        Map<String, String> textureVars = new LinkedHashMap<>();
        Set<String> visited = new HashSet<>();
        String current = model;
        boolean brokenParent = false;
        while (current != null) {
            if (!visited.add(current) || visited.size() > 64) {
                issues.add(model + "：模型 parent 循环或过深");
                brokenParent = true;
                break;
            }
            String[] loc = location(current);
            JsonObject object = json("assets/" + loc[0] + "/models/" + loc[1] + ".json");
            if (object == null) {
                if (!current.startsWith("builtin/") && !current.startsWith("minecraft:builtin/"))
                    issues.add(model + "：缺少模型/父模型 " + current);
                break;
            }
            if (object.has("textures")) for (var entry : object.getAsJsonObject("textures").entrySet()) {
                JsonElement value = entry.getValue();
                if (value.isJsonObject()) value = value.getAsJsonObject().get("sprite");
                if (value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString())
                    textureVars.putIfAbsent(entry.getKey(), value.getAsString());
                else issues.add(model + "：无法解析贴图变量 " + entry.getKey());
            }
            current = object.has("parent") ? object.get("parent").getAsString() : null;
        }
        List<Integer> colors = new ArrayList<>();
        Set<String> used = new HashSet<>();
        Integer particle = null;
        for (var entry : textureVars.entrySet()) {
            String texture = entry.getValue();
            Set<String> vars = new HashSet<>();
            while (texture != null && texture.startsWith("#")) {
                if (!vars.add(texture)) { issues.add(model + "：贴图变量循环"); texture = null; break; }
                texture = textureVars.get(texture.substring(1));
            }
            if (texture == null) continue;
            Integer rgb = textureColor(texture);
            if (rgb == null) continue;
            if (entry.getKey().equals("particle")) particle = rgb;
            else if (used.add(texture)) colors.add(rgb);
        }
        if (colors.isEmpty() && particle != null) colors.add(particle);
        Integer result = brokenParent ? null : average(colors);
        if (result == null && !brokenParent) {
            String[] loc = location(current == null ? model : current);
            if (loc[0].equals("minecraft")) result = vanillaColor(loc[1]);
        }
        if (result == null) issues.add(model + "：无法解析模型贴图颜色");
        models.put(model, Optional.ofNullable(result));
        return result;
    }

    private Integer textureColor(String texture) {
        Optional<Integer> cached = textures.get(texture);
        if (cached != null) return cached.orElse(null);
        String[] loc = location(texture);
        String path = "assets/" + loc[0] + "/textures/" + loc[1] + ".png";
        Integer result = null;
        boolean supplied = false;
        try {
            byte[] data = source.read(path);
            supplied = data != null;
            if (supplied) result = imageColor(ImageIO.read(new ByteArrayInputStream(data)), json(path + ".mcmeta"));
        } catch (Exception e) { issues.add(path + "：" + e.getMessage()); }
        // 透明自带贴图不是缺失贴图，不能擅自替换为原版颜色。
        if (result == null && !supplied && loc[0].equals("minecraft")) result = vanillaColor(loc[1]);
        if (result == null) issues.add(path + "：缺失、全透明或无法读取");
        textures.put(texture, Optional.ofNullable(result));
        return result;
    }

    private Integer vanillaColor(String path) {
        String name = path.substring(path.lastIndexOf('/') + 1).toUpperCase(Locale.ROOT);
        Integer value = vanilla.apply(name);
        if (value != null) return value;
        for (String suffix : SUFFIXES) {
            String s = suffix.toUpperCase(Locale.ROOT);
            if (name.endsWith(s)) {
                value = vanilla.apply(name.substring(0, name.length() - s.length()));
                if (value != null) return value;
            }
        }
        return null;
    }

    /** 静态贴图读取完整区域；动画按 mcmeta 的首个实际帧，透明像素按 alpha 加权。 */
    static Integer imageColor(BufferedImage image, JsonObject metadata) {
        if (image == null) return null;
        int w = image.getWidth(), h = image.getHeight(), x0 = 0, y0 = 0;
        if (metadata != null && metadata.has("animation")) {
            JsonObject animation = metadata.getAsJsonObject("animation");
            int size = Math.min(w, h);
            w = animation.has("width") ? animation.get("width").getAsInt() : animation.has("height") ? w : size;
            h = animation.has("height") ? animation.get("height").getAsInt() : animation.has("width") ? h : size;
            int frame = 0;
            if (animation.has("frames") && !animation.getAsJsonArray("frames").isEmpty()) {
                JsonElement first = animation.getAsJsonArray("frames").get(0);
                frame = first.isJsonObject() ? first.getAsJsonObject().get("index").getAsInt() : first.getAsInt();
            }
            if (w <= 0 || h <= 0 || image.getWidth() % w != 0 || image.getHeight() % h != 0 || frame < 0) return null;
            x0 = frame % (image.getWidth() / w) * w;
            y0 = frame / (image.getWidth() / w) * h;
            if (y0 + h > image.getHeight()) return null;
        }
        long r = 0, g = 0, b = 0, total = 0;
        for (int y = y0; y < y0 + h; y++) for (int x = x0; x < x0 + w; x++) {
            int argb = image.getRGB(x, y), a = argb >>> 24;
            r += ((argb >> 16) & 255) * a;
            g += ((argb >> 8) & 255) * a;
            b += (argb & 255) * a;
            total += a;
        }
        return total == 0 ? null : (int) (r / total) << 16 | (int) (g / total) << 8 | (int) (b / total);
    }

    private static Integer average(List<Integer> colors) {
        if (colors.isEmpty()) return null;
        long r = 0, g = 0, b = 0;
        for (int c : colors) { r += (c >> 16) & 255; g += (c >> 8) & 255; b += c & 255; }
        return (int) (r / colors.size()) << 16 | (int) (g / colors.size()) << 8 | (int) (b / colors.size());
    }
}
