package com.wenkrang.famara.lib;

import com.google.gson.*;
import com.wenkrang.famara.compat.CeCompat;
import com.wenkrang.famara.compat.RuntimeCompat;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.jar.JarFile;
import java.util.logging.Logger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** 只导出本插件自有的可选模型包，不托管网络、不修改 server.properties。 */
public final class StandalonePack {
    public static final String FILE_NAME = "famara-standalone.zip";
    private static final String PREFIX = "craftengine/famara/resourcepack/";
    private static final Logger LOG = Logger.getLogger("FamaraV2");
    private StandalonePack() {}

    public static void export(Plugin plugin) {
        Path temporary = null;
        try {
            Path directory = com.wenkrang.famara.lib.DataFolders.of(plugin).toPath();
            Files.createDirectories(directory);
            temporary = Files.createTempFile(directory, "famara-pack-", ".tmp");
            File source = new File(plugin.getClass().getProtectionDomain().getCodeSource().getLocation().toURI());
            try (JarFile jar = new JarFile(source); ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(temporary))) {
                var entries = jar.entries();
                while (entries.hasMoreElements()) {
                    var entry = entries.nextElement();
                    if (entry.isDirectory() || !entry.getName().startsWith(PREFIX)) continue;
                    String relative = entry.getName().substring(PREFIX.length());
                    if (!relative.startsWith("assets/") || relative.contains("..") || relative.contains("\\")) continue;
                    try (InputStream input = jar.getInputStream(entry)) { put(output, relative, input.readAllBytes()); }
                }
                Format format = format();
                JsonObject pack = new JsonObject();
                pack.addProperty("pack_format", format.major);
                pack.addProperty("description", "Famara 独立资源包：无需 CraftEngine，仅影响物品外观");
                JsonArray version = new JsonArray(); version.add(format.major); version.add(format.minor);
                pack.add("min_format", version); pack.add("max_format", version.deepCopy());
                JsonObject meta = new JsonObject(); meta.add("pack", pack);
                json(output, "pack.mcmeta", meta);
                if (RuntimeCompat.modernItemModels()) {
                    definition(output, "camera", camera());
                    definition(output, "film_box", model("famara:item/film_box"));
                    definition(output, "photo", model("famara:item/photo"));
                } else {
                    legacy(output, "stick", "minecraft:item/handheld", Map.of("layer0", "minecraft:item/stick"), CeItems.CAMERA_MODEL, "famara:item/famara_close");
                    legacy(output, "paper", "minecraft:item/generated", Map.of("layer0", "minecraft:item/paper"), CeItems.FILM_MODEL, "famara:item/film_box");
                    legacy(output, "filled_map", "minecraft:item/generated", Map.of("layer0", "minecraft:item/map", "layer1", "minecraft:item/filled_map_markings"), CeItems.PHOTO_MODEL, "famara:item/photo");
                }
            }
            Path target = directory.resolve(FILE_NAME);
            if (Files.isRegularFile(target) && Arrays.equals(Files.readAllBytes(target), Files.readAllBytes(temporary))) {
                Files.delete(temporary); temporary = null;
            } else {
                try { Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
                catch (AtomicMoveNotSupportedException e) { Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING); }
                temporary = null;
            }
            if (!CeCompat.enabled()) LOG.info("独立物品资源包已导出：" + target + "；未加载仅显示原版外观，拍照仍可用。");
        } catch (Exception error) {
            LOG.warning("可选独立资源包导出失败，核心拍照功能继续可用：" + error.getMessage());
        } finally {
            if (temporary != null) try { Files.deleteIfExists(temporary); } catch (IOException ignored) { }
        }
    }

    private static void put(ZipOutputStream output, String path, byte[] data) throws IOException {
        ZipEntry entry = new ZipEntry(path); entry.setTime(0);
        output.putNextEntry(entry); output.write(data); output.closeEntry();
    }
    private static void json(ZipOutputStream output, String path, JsonObject value) throws IOException {
        put(output, path, value.toString().getBytes(StandardCharsets.UTF_8));
    }
    private static JsonObject model(String reference) {
        JsonObject value = new JsonObject();
        value.addProperty("type", "minecraft:model"); value.addProperty("model", reference);
        return value;
    }
    private static void definition(ZipOutputStream output, String id, JsonObject model) throws IOException {
        JsonObject value = new JsonObject(); value.add("model", model);
        json(output, "assets/famara/items/" + id + ".json", value);
    }
    private static JsonObject camera() {
        JsonObject empty = new JsonObject(); empty.addProperty("type", "minecraft:empty");
        JsonArray contexts = new JsonArray(); contexts.add("firstperson_righthand"); contexts.add("firstperson_lefthand");
        JsonObject entry = new JsonObject(); entry.add("when", contexts); entry.add("model", empty);
        JsonArray cases = new JsonArray(); cases.add(entry);
        JsonObject open = new JsonObject();
        open.addProperty("type", "minecraft:select"); open.addProperty("property", "minecraft:display_context");
        open.add("cases", cases); open.add("fallback", model("famara:item/famara_open"));
        JsonObject root = new JsonObject();
        root.addProperty("type", "minecraft:condition"); root.addProperty("property", "minecraft:custom_model_data");
        root.addProperty("index", 0); root.add("on_true", open); root.add("on_false", model("famara:item/famara_close"));
        return root;
    }
    private static void legacy(ZipOutputStream output, String material, String parent, Map<String, String> textures,
                               int code, String custom) throws IOException {
        JsonObject base = new JsonObject(); base.addProperty("parent", parent);
        JsonObject texture = new JsonObject(); textures.forEach(texture::addProperty); base.add("textures", texture);
        json(output, "assets/famara/models/item/base_" + material + ".json", base);
        JsonArray overrides = new JsonArray();
        for (int i = 0; i < 2; i++) {
            JsonObject predicate = new JsonObject(); predicate.addProperty("custom_model_data", code + i);
            JsonObject entry = new JsonObject(); entry.add("predicate", predicate);
            entry.addProperty("model", i == 0 ? custom : "famara:item/base_" + material);
            overrides.add(entry);
        }
        base.add("overrides", overrides);
        json(output, "assets/minecraft/models/item/" + material + ".json", base);
    }

    private record Format(int major,int minor) {}
    private static Format format() {
        try(InputStream input=Bukkit.getServer().getClass().getClassLoader().getResourceAsStream("version.json")) {
            if(input!=null) {
                JsonObject version=JsonParser.parseReader(new InputStreamReader(input,StandardCharsets.UTF_8)).getAsJsonObject();
                JsonObject formats=version.getAsJsonObject("pack_version");
                if(formats.has("resource_major"))return new Format(formats.get("resource_major").getAsInt(),formats.get("resource_minor").getAsInt());
                JsonElement value=formats.get("resource");
                if(value.isJsonArray())return new Format(value.getAsJsonArray().get(0).getAsInt(),value.getAsJsonArray().get(1).getAsInt());
                if(value.isJsonObject())return new Format(value.getAsJsonObject().get("major").getAsInt(),value.getAsJsonObject().get("minor").getAsInt());
                return new Format(value.getAsInt(),0);
            }
        }catch(Exception ignored){}
        String minecraftVersion=Bukkit.getMinecraftVersion();Format modern=formatForMinecraftVersion(minecraftVersion);if(modern!=null)return modern;
        String version=Bukkit.getBukkitVersion().split("-")[0];String[] parts=version.split("\\.");
        if(parts.length>=2 && parts[0].equals("1")) {
            int minor=Integer.parseInt(parts[1]),patch=parts.length>=3?Integer.parseInt(parts[2]):0;
            if(minor==20)return new Format(patch<=1?15:patch==2?18:patch<=4?22:32,0);
            if(minor==21)return new Format(patch<=1?34:patch<=3?42:patch==4?46:patch==5?55:patch==6?63:patch<=8?64:patch<=10?69:75,0);
        }
        LOG.warning("无法读取客户端资源格式版本；服务器版本="+minecraftVersion+"，独立包暂用兼容格式15。V2基础资源不受此独立物品包格式推断影响。");return new Format(15,0);
    }
    static Format formatForMinecraftVersion(String version) {
        if(version==null)return null;
        var match=java.util.regex.Pattern.compile("26[.]([123])(?:[.]([0-9]+))?(?:[-+].*)?").matcher(version);if(!match.matches())return null;
        int patch=match.group(2)==null?0:Integer.parseInt(match.group(2));if(patch>(match.group(1).equals("1")?2:0))return null;
        return switch(match.group(1)){case "1"->new Format(84,0);case "2"->new Format(88,0);default->new Format(97,1);};
    }
}
