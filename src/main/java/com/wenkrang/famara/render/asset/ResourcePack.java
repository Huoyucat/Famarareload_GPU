package com.wenkrang.famara.render.asset;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.zip.*;

/**
 * 一次有界读取后封存，渲染资源不再依赖文件句柄。列表按高优先级在前叠加。
 */
public final class ResourcePack {
    public enum Role { UNKNOWN, BASE, CUSTOM }
    public static final class Content {
        private final byte[] bytes;
        private final String source;
        private final Role role;
        private Content(byte[] bytes,String source) {this(bytes,source,Role.UNKNOWN);}
        private Content(byte[] bytes,String source,Role role) {this.bytes=bytes;this.source=source;this.role=role;}
        public Role role(){return role;}

        public InputStream open() {
            return new ByteArrayInputStream(bytes);
        }

        public byte[] copyBytes() {
            return bytes.clone();
        }

        public String source() {
            return source;
        }

        public int size() {
            return bytes.length;
        }
    }

    private final Map<String, Content> files;
    private final Map<String,List<Content>> blockstates;
    private final String fingerprint;
    private final long bytes;

    private ResourcePack(Map<String, Content> files, String fingerprint) {
        this(files,fingerprint,Map.of());
    }
    private ResourcePack(Map<String,Content> files,String fingerprint,Map<String,List<Content>> stacks) {
        this.files=Collections.unmodifiableMap(new TreeMap<>(files));this.fingerprint=fingerprint;
        Map<String,List<Content>> definitions=new TreeMap<>();
        files.forEach((name,content)->{if(blockstate(name))definitions.put(name,List.copyOf(stacks.getOrDefault(name,List.of(content))));});
        blockstates=Map.copyOf(definitions);
        this.bytes=files.values().stream().mapToLong(Content::size).sum()+blockstates.values().stream().mapToLong(stack->stack.stream().skip(1).mapToLong(Content::size).sum()).sum();
    }

    public ResourcePack withRole(Role role){return retag(null,Objects.requireNonNull(role));}
    public ResourcePack withSource(String source){return retag(Objects.requireNonNull(source),null);}
    private ResourcePack retag(String source,Role role) {
        Map<String,Content> tagged=new TreeMap<>();
        Map<String,List<Content>> stacks=new TreeMap<>();
        files.forEach((name,content)->tagged.put(name,new Content(content.bytes,source==null?content.source:source,role==null?content.role:role)));
        blockstates.forEach((name,stack)->stacks.put(name,stack.stream().map(c->new Content(c.bytes,source==null?c.source:source,role==null?c.role:role)).toList()));
        return new ResourcePack(tagged,fingerprint,stacks);
    }
    private static boolean blockstate(String path){return path.matches("assets/[a-z0-9_.-]+/blockstates/.+[.]json");}
    /** 客户端blockstate加载器逐状态叠加；模型/贴图等其它资源仍是最高层整文件覆盖。 */
    public List<Content> blockstateStack(String path){validatePath(path);return blockstates.getOrDefault(path,List.of());}
    public Set<String> paths() {
        return files.keySet();
    }

    public Content find(String path) {
        validatePath(path);
        Content exact=files.get(path);if(exact!=null)return exact;
        // 已知原版改名的引用别名；仅缺文件才补，不能覆盖CE提供的旧名称资源。
        String replacement=aliasPath(path);
        return replacement==null?null:files.get(replacement);
    }

    public static String aliasPath(String path) {
        return switch(path) {
            case "assets/minecraft/models/block/chain.json" -> "assets/minecraft/models/block/iron_chain.json";
            case "assets/minecraft/textures/block/chain.png" -> "assets/minecraft/textures/block/iron_chain.png";
            case "assets/minecraft/textures/block/chain.png.mcmeta" -> "assets/minecraft/textures/block/iron_chain.png.mcmeta";
            case "assets/minecraft/models/block/creaking_heart_active.json" -> "assets/minecraft/models/block/creaking_heart_awake.json";
            case "assets/minecraft/models/block/creaking_heart_active_horizontal.json" -> "assets/minecraft/models/block/creaking_heart_awake_horizontal.json";
            default -> null;
        };
    }

    public Content require(String path) {
        Content value = find(path);
        if (value == null) throw new AssetException(AssetException.Code.MISSING, path, "资源不存在");
        return value;
    }

    public String fingerprint() {
        return fingerprint;
    }

    public long retainedBytes() {
        return bytes;
    }

    public static ResourcePack memory(String source, Map<String, byte[]> input, AssetLimits limits) {
        if (input.size() > limits.maxEntries()) throw limit(source);
        Map<String, Content> values = new TreeMap<>();
        long total = 0;
        for (var entry : input.entrySet()) {
            validatePath(entry.getKey());
            byte[] data = Objects.requireNonNull(entry.getValue());
            total = Math.addExact(total, data.length);
            if (data.length > limits.maxEntryBytes() || total > limits.maxTotalBytes()) throw limit(source);
            values.put(entry.getKey(), new Content(data.clone(), source));
        }
        return new ResourcePack(values, digestContents(values));
    }

    public static ResourcePack layer(List<ResourcePack> highToLow, AssetLimits limits) {
        Map<String, Content> values = new TreeMap<>();Map<String,List<Content>> stacks=new TreeMap<>();
        long total = 0;
        MessageDigest digest = sha256();
        for (ResourcePack pack : highToLow) {
            digest.update(pack.fingerprint.getBytes(StandardCharsets.UTF_8));
            for(var entry:pack.blockstates.entrySet())stacks.computeIfAbsent(entry.getKey(),k->new ArrayList<>()).addAll(entry.getValue());
            for (var entry : pack.files.entrySet())
                if (!values.containsKey(entry.getKey()) && !((entry.getKey().endsWith(".class") || entry.getKey().equals(NativeModelDataReader.DATA)) && entry.getValue().role==Role.CUSTOM)) {
                    total = Math.addExact(total, entry.getValue().size());
                    if (values.size() >= limits.maxEntries() || total > limits.maxTotalBytes()) throw limit("layered");
                    values.put(entry.getKey(), entry.getValue());
                }
        }
        long lowerBytes=stacks.values().stream().mapToLong(stack->stack.stream().skip(1).mapToLong(Content::size).sum()).sum();
        if(lowerBytes>limits.maxTotalBytes()-total)throw limit("blockstate-stacks");
        return new ResourcePack(values,HexFormat.of().formatHex(digest.digest()),stacks);
    }

    public static ResourcePack load(Path path, AssetLimits limits) throws IOException {
        return load(path, limits, () -> {
        });
    }

    static ResourcePack load(Path path,AssetLimits limits,Runnable beforeVerification) throws IOException {
        try(ResourcePackCursor cursor=new ResourcePackCursor(path,limits,beforeVerification)) {
            while(!cursor.step(256,Long.MAX_VALUE,System::nanoTime)) {}
            return cursor.result();
        }
    }

    static ResourcePack owned(String source,Map<String,byte[]> owned,String fingerprint) {
        Map<String,Content> entries=new TreeMap<>();
        owned.forEach((name,data)->entries.put(name,new Content(data,source)));
        return new ResourcePack(entries,fingerprint);
    }

    private static Map<String, byte[]> readDirectory(Path root, AssetLimits limits) throws IOException {
        Map<String, byte[]> result = new TreeMap<>();
        long total = 0;
        int count = 0;
        try (var paths = Files.walk(root)) {
            Iterator<Path> it = paths.iterator();
            while (it.hasNext()) {
                Path file = it.next();
                if (++count > limits.maxEntries()) throw limit(root.getFileName().toString());
                if (!Files.isRegularFile(file)) continue;
                String name = root.relativize(file).toString().replace(File.separatorChar, '/');
                validatePath(name);
                if (!relevant(name)) continue;
                if (!file.toRealPath().startsWith(root))
                    throw new AssetException(AssetException.Code.INVALID, name, "符号链接逃出资源根目录");
                if (Files.size(file) > limits.maxEntryBytes()) throw limit(name);
                byte[] data;
                try (InputStream in = Files.newInputStream(file)) {
                    data = bounded(in, limits, name);
                }
                total = Math.addExact(total, data.length);
                if (total > limits.maxTotalBytes()) throw limit(name);
                result.put(name, data);
            }
        }
        return result;
    }

    static boolean relevant(String path) {
        return path.equals("pack.mcmeta") || path.equals("version.json")
                || path.equals("net/minecraft/client/model/animal/golem/CopperGolemModel.class")
                ||
                path.startsWith("assets/") && (path.endsWith(".json") || path.endsWith(".png") || path.endsWith(".png.mcmeta"));
    }

    static void validatePath(String path) {
        if (path.isEmpty() || path.indexOf(':') >= 0 || path.indexOf((char) 92) >= 0 || path.indexOf((char) 0) >= 0)
            throw new AssetException(AssetException.Code.INVALID, path, "非法资源路径");
        for (String part : path.split("/", -1))
            if (part.isEmpty() || part.equals(".") || part.equals(".."))
                throw new AssetException(AssetException.Code.INVALID, path, "资源路径逃逸或空分段");
    }

    private static byte[] bounded(InputStream in, AssetLimits limits, String path) throws IOException {
        byte[] data = in.readNBytes(limits.maxEntryBytes() + 1);
        if (data.length > limits.maxEntryBytes()) throw limit(path);
        return data;
    }

    private static AssetException limit(String path) {
        return new AssetException(AssetException.Code.LIMIT, path, "资源读取超过显式限额");
    }

    static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }

    private static String digestFile(Path path) throws IOException {
        MessageDigest digest = sha256();
        try (InputStream in = Files.newInputStream(path)) {
            byte[] buffer = new byte[8192];
            int n;
            while ((n = in.read(buffer)) != -1) digest.update(buffer, 0, n);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String digestContents(Map<String, Content> values) {
        MessageDigest digest = sha256();
        values.forEach((name, data) -> {
            digest.update(name.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(java.nio.ByteBuffer.allocate(8).putLong(data.bytes.length).array());
            digest.update(data.bytes);
        });
        return HexFormat.of().formatHex(digest.digest());
    }
}
