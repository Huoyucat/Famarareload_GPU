package com.wenkrang.famara.render.asset;

import com.google.gson.*;
import com.wenkrang.famara.render.math.*;

import java.util.*;
import java.util.function.UnaryOperator;

/**
 * 编译阶段唯一持有 JSON；BakedModel 不含文件、平台对象或可变 JSON。
 */
public final class ModelCompiler {
    private record Raw(Map<String, JsonElement> textures, JsonArray elements, boolean ambient,ResourcePack.Role role) {
    }

    private record Binding(ResourceKey texture, boolean translucent) {
    }

    private java.util.function.Function<ResourceKey,Texture> suppliedTextures;
    public ModelCompiler(ResourcePack pack,AssetLimits limits,java.util.function.Function<ResourceKey,Texture> textures){this(pack,limits);suppliedTextures=Objects.requireNonNull(textures);}
    private final ResourcePack pack;
    private final AssetLimits limits;
    private final AssetMemoryBudget memory;
    private final Map<ResourceKey, Raw> inherited = new HashMap<>();
    private final Map<ResourceKey, BakedModel> models = new HashMap<>();
    private final Map<ResourceKey, Texture> textures = new HashMap<>();
    private long decodedBytes;
    private long allocatedFaces;
    private NativeModelDataReader nativeData;

    private record VariantKey(ResourceKey key, int x, int y, boolean lock) {
    }

    private final Map<VariantKey, BakedModel> variants = new HashMap<>();

    public ModelCompiler(ResourcePack pack, AssetLimits limits) {
        this(pack,limits,new AssetMemoryBudget(limits.maxTotalBytes()));
        memory.reserve(pack.retainedBytes(),"source-pack");
    }

    ModelCompiler(ResourcePack pack,AssetLimits limits,AssetMemoryBudget memory) {
        this.pack=pack;this.limits=limits;this.memory=memory;
    }

    public Texture texture(ResourceKey key) {
        Texture cached=textures.get(key);if(cached!=null)return cached;
        Texture decoded=suppliedTextures==null?Texture.decode(pack,key,limits,memory):suppliedTextures.apply(key);
        long next=Math.addExact(decodedBytes,decoded.retainedBytes());
        if(next+allocatedFaces*512>limits.maxTotalBytes())throw new AssetException(AssetException.Code.LIMIT,key.toString(),"累计资产内存超过限额");
        textures.put(key,decoded);decodedBytes=next;return decoded;
    }

    public void reserveExternalGeometry(int faces,String name) { reserveFaces(faces,name); }
    NativeModelDataReader.Shape nativeShape(String method) {
        if(nativeData==null)nativeData=new NativeModelDataReader(pack,limits,memory);
        return nativeData.read(method);
    }

    Map<ResourceKey,ResourcePack.Role> modelRoles() {
        Map<ResourceKey,ResourcePack.Role> roles=new HashMap<>();inherited.forEach((key,raw)->roles.put(key,raw.role));return Map.copyOf(roles);
    }
    Map<ResourceKey,ResourcePack.Role> textureRoles() {
        Map<ResourceKey,ResourcePack.Role> roles=new HashMap<>();textures.keySet().forEach(key->roles.put(key,pack.require(key.file("textures",".png")).role()));return Map.copyOf(roles);
    }

    public Map<ResourceKey, Texture> textures() {
        return Map.copyOf(textures);
    }

    public long retainedTextureBytes() {
        return decodedBytes;
    }

    public BakedModel variant(ResourceKey key, int x, int y, boolean lock) {
        if (x == 0 && y == 0) return compile(key);
        VariantKey variant = new VariantKey(key, x, y, lock);
        BakedModel cached = variants.get(variant);
        if (cached != null) return cached;
        BakedModel original = compile(key);
        reserveFaces(original.quads().size(), key.toString());
        BakedModel result = original.variant(x, y, lock);
        variants.put(variant, result);
        return result;
    }

    private void reserveFaces(int count, String path) {
        if (count < 0 || (allocatedFaces + count) * 512 + decodedBytes > limits.maxTotalBytes())
            throw new AssetException(AssetException.Code.LIMIT, path, "累计模型面内存预约超过限额");
        memory.reserve(Math.multiplyExact(count,512L),path);
        allocatedFaces += count;
    }

    public BakedModel compile(ResourceKey key) {
        BakedModel cached = models.get(key);
        if (cached != null) return cached;
        String path = key.file("models", ".json");
        try {
            Raw raw = raw(key, new LinkedHashSet<>());
            if(raw.elements==null) {
                BakedModel invisible=new BakedModel(key,List.of(),raw.ambient);models.put(key,invisible);return invisible;
            }
            List<BakedQuad> quads = new ArrayList<>();
            for (JsonElement element : raw.elements) compileElement(element.getAsJsonObject(), raw, path, quads);
            BakedModel model = new BakedModel(key, quads, raw.ambient);
            Vec3 min = model.min(), max = model.max();
            for (int axis = 0; axis < 3; axis++)
                if (max.component(axis) - min.component(axis) > limits.maxModelSpan())
                    throw new AssetException(AssetException.Code.LIMIT, path, "模型范围过大");
            models.put(key, model);
            return model;
        } catch (AssetException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new AssetException(AssetException.Code.INVALID, path, "模型字段不合法 " + e.getClass().getSimpleName() + ": " + e.getMessage(), e);
        }
    }

    private Raw raw(ResourceKey key, Set<ResourceKey> chain) {
        Raw cached = inherited.get(key);
        if (cached != null) return cached;
        String path = key.file("models", ".json");
        if (!chain.add(key)) throw new AssetException(AssetException.Code.INVALID, path, "parent循环：" + chain);
        if (chain.size() > limits.maxReferenceDepth())
            throw new AssetException(AssetException.Code.LIMIT, path, "parent引用过深");
        JsonObject json = BoundedJson.read(pack.require(path), path, limits,memory);
        Texture.checkFields(json, Set.of("parent", "textures", "elements", "ambientocclusion", "display", "gui_light", "groups", "texture_size", "__comment", "format_version", "credit"), path);
        metadata(json,path);
        Map<String, JsonElement> vars = new LinkedHashMap<>();
        JsonArray elements = null;
        boolean ambient = true;
        ResourcePack.Role role=pack.require(path).role();
        if (json.has("parent")) {
            String parent = json.get("parent").getAsString();
            if (parent.startsWith("builtin/") || parent.startsWith("minecraft:builtin/"))
                throw new AssetException(AssetException.Code.UNSUPPORTED, path, "内置特殊模型需要适配：" + parent);
            Raw base = raw(ResourceKey.parse(parent), chain);
            vars.putAll(base.textures);
            elements = base.elements;
            ambient = base.ambient;
            if(base.role==ResourcePack.Role.CUSTOM)role=ResourcePack.Role.CUSTOM;
        }
        if (json.has("textures")) vars.putAll(json.getAsJsonObject("textures").asMap());
        if (json.has("elements")) elements = json.getAsJsonArray("elements");
        if (json.has("ambientocclusion")) ambient = json.get("ambientocclusion").getAsBoolean();
        Raw result = new Raw(Map.copyOf(vars), elements, ambient,role);
        inherited.put(key, result);
        chain.remove(key);
        return result;
    }

    /** Java模型导出声明不参与几何，其它格式的实际渲染字段仍由严格白名单拒绝。 */
    private static void metadata(JsonObject json,String path) {
        if(json.has("format_version")) {
            JsonElement value=json.get("format_version");
            if(!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString() || value.getAsString().length()>64 || !value.getAsString().matches("[0-9]+(?:[.][0-9]+){1,3}"))
                throw new AssetException(AssetException.Code.INVALID,path,"Java模型format_version需要有界的点分版本字符串");
            if(!json.has("elements") && !json.has("parent"))
                throw new AssetException(AssetException.Code.UNSUPPORTED,path,"带format_version的模型缺少Java elements或parent，不能把其它几何格式当空模型");
        }
        if(json.has("credit")) {
            JsonElement value=json.get("credit");
            if(!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString() || value.getAsString().length()>4096)
                throw new AssetException(AssetException.Code.INVALID,path,"Java模型credit需要有界作者声明字符串");
        }
    }

    private void compileElement(JsonObject element, Raw raw, String path, List<BakedQuad> output) {
        Texture.checkFields(element, Set.of("from", "to", "faces", "rotation", "shade", "light_emission", "shade_direction_override", "name", "__comment", "color"), path);
        double[] from = JsonValues.vector(element.get("from"), 3), to = JsonValues.vector(element.get("to"), 3);
        UnaryOperator<Vec3> transform = v -> v.multiply(1 / 16.0);
        if (element.has("rotation")) {
            JsonObject rotation = element.getAsJsonObject("rotation");
            Texture.checkFields(rotation, Set.of("origin", "axis", "angle", "rescale", "x", "y", "z"), path);
            double[] origin = JsonValues.vector(rotation.get("origin"), 3);
            Vec3 pivot = new Vec3(origin[0], origin[1], origin[2]);
            if (rotation.has("x") || rotation.has("y") || rotation.has("z")) {
                if (rotation.has("axis") || rotation.has("angle") || rotation.has("rescale"))
                    throw new IllegalArgumentException("不能混用多轴旋转与axis/angle/rescale");
                double ax = rotation.has("x") ? JsonValues.number(rotation.get("x")) : 0;
                double ay = rotation.has("y") ? JsonValues.number(rotation.get("y")) : 0;
                double az = rotation.has("z") ? JsonValues.number(rotation.get("z")) : 0;
                if (Math.abs(ax) > 360 || Math.abs(ay) > 360 || Math.abs(az) > 360)
                    throw new IllegalArgumentException("旋转角度超界");
                transform = v -> v.subtract(pivot).rotate(0, Math.toRadians(ax)).rotate(1, Math.toRadians(ay))
                        .rotate(2, Math.toRadians(az)).add(pivot).multiply(1 / 16.0);
            } else {
                int axis = switch (rotation.get("axis").getAsString()) {
                    case "x" -> 0;
                    case "y" -> 1;
                    case "z" -> 2;
                    default -> throw new IllegalArgumentException("未知旋转轴");
                };
                double degrees = JsonValues.number(rotation.get("angle"));
                if (Math.abs(degrees) > 360)
                    throw new AssetException(AssetException.Code.INVALID, path, "element旋转角度超界");
                double angle = Math.toRadians(degrees);
                boolean rescale = rotation.has("rescale") && rotation.get("rescale").getAsBoolean();
                if (rescale && Math.abs(Math.cos(angle)) < 1e-6) throw new IllegalArgumentException("rescale旋转退化");
                double scale = rescale ? 1 / Math.cos(angle) : 1;
                transform = v -> {
                    Vec3 local = v.subtract(pivot).rotate(axis, angle);
                    local = new Vec3(local.x() * (axis == 0 ? 1 : scale), local.y() * (axis == 1 ? 1 : scale), local.z() * (axis == 2 ? 1 : scale));
                    return local.add(pivot).multiply(1 / 16.0);
                };
            }
        }
        boolean shade = !element.has("shade") || element.get("shade").getAsBoolean();
        Direction shadeOverride = element.has("shade_direction_override") ? Direction.parse(element.get("shade_direction_override").getAsString()) : null;
        int emission = element.has("light_emission") ? JsonValues.integer(element.get("light_emission")) : 0;
        if (emission < 0 || emission > 15) throw new IllegalArgumentException("非法自发光强度");
        JsonObject faces = element.getAsJsonObject("faces");
        for (var entry : faces.entrySet()) {
            Direction face = Direction.parse(entry.getKey());
            JsonObject definition = entry.getValue().getAsJsonObject();
            Texture.checkFields(definition, Set.of("uv", "texture", "cullface", "rotation", "tintindex"), path);
            Binding binding = binding(definition.get("texture").getAsString(), raw.textures, path);
            List<Vec3> points = points(face, from, to).stream().map(transform).toList();
            for (Vec3 p : points)
                for (int axis = 0; axis < 3; axis++)
                    if (Math.abs(p.component(axis)) > limits.maxModelSpan())
                        throw new AssetException(AssetException.Code.LIMIT, path, "模型顶点离宿主过远");
            Vec3 geometricNormal = points.get(3).subtract(points.get(0)).cross(points.get(1).subtract(points.get(0)));
            if (geometricNormal.dot(geometricNormal) < 1e-24) continue;
            geometricNormal = geometricNormal.normalized();
            double[] uv = definition.has("uv") ? JsonValues.vector(definition.get("uv"), 4) : defaultUv(face, from, to);
            List<BakedQuad.Uv> coordinates = new ArrayList<>(List.of(new BakedQuad.Uv(uv[0] / 16, uv[1] / 16),
                    new BakedQuad.Uv(uv[2] / 16, uv[1] / 16), new BakedQuad.Uv(uv[2] / 16, uv[3] / 16), new BakedQuad.Uv(uv[0] / 16, uv[3] / 16)));
            int rotation = definition.has("rotation") ? JsonValues.integer(definition.get("rotation")) : 0;
            if (rotation < 0 || rotation > 270 || rotation % 90 != 0) throw new IllegalArgumentException("非法UV旋转");
            Collections.rotate(coordinates, rotation / 90);
            Direction cull = definition.has("cullface") ? Direction.parse(definition.get("cullface").getAsString()) : null;
            int tint = definition.has("tintindex") ? JsonValues.integer(definition.get("tintindex")) : -1;
            if (output.size() >= limits.maxModelFaces())
                throw new AssetException(AssetException.Code.LIMIT, path, "模型面数过多");
            texture(binding.texture);
            reserveFaces(1, path);
            output.add(new BakedQuad(points, coordinates, binding.texture, geometricNormal,
                    face, cull, tint, shade, binding.translucent, shadeOverride, emission, output.size()));
        }
    }

    private Binding binding(String value, Map<String, JsonElement> vars, String path) {
        Set<String> chain = new LinkedHashSet<>();
        String slot = value.startsWith("#") ? value.substring(1) : value;
        chain.add(slot);
        JsonElement current = vars.get(slot);
        if (current == null)
            throw new AssetException(AssetException.Code.MISSING, path, "面引用未定义的材质槽 " + slot);
        while (current.isJsonPrimitive() && current.getAsString().startsWith("#")) {
            String name = current.getAsString().substring(1);
            if (!chain.add(name) || chain.size() > limits.maxReferenceDepth())
                throw new AssetException(AssetException.Code.INVALID, path, "纹理变量循环或过深 " + chain);
            current = vars.get(name);
            if (current == null) throw new AssetException(AssetException.Code.MISSING, path, "纹理变量未定义 " + name);
        }
        if (current.isJsonObject()) {
            JsonObject object = current.getAsJsonObject();
            Texture.checkFields(object, Set.of("sprite", "force_translucent"), path);
            return new Binding(ResourceKey.parse(object.get("sprite").getAsString()),
                    object.has("force_translucent") && object.get("force_translucent").getAsBoolean());
        }
        return new Binding(ResourceKey.parse(current.getAsString()), false);
    }

    private static List<Vec3> points(Direction d, double[] a, double[] b) {
        double x = a[0], y = a[1], z = a[2], X = b[0], Y = b[1], Z = b[2];
        return switch (d) {
            case NORTH -> List.of(new Vec3(X, Y, z), new Vec3(x, Y, z), new Vec3(x, y, z), new Vec3(X, y, z));
            case SOUTH -> List.of(new Vec3(x, Y, Z), new Vec3(X, Y, Z), new Vec3(X, y, Z), new Vec3(x, y, Z));
            case WEST -> List.of(new Vec3(x, Y, z), new Vec3(x, Y, Z), new Vec3(x, y, Z), new Vec3(x, y, z));
            case EAST -> List.of(new Vec3(X, Y, Z), new Vec3(X, Y, z), new Vec3(X, y, z), new Vec3(X, y, Z));
            case UP -> List.of(new Vec3(x, Y, z), new Vec3(X, Y, z), new Vec3(X, Y, Z), new Vec3(x, Y, Z));
            case DOWN -> List.of(new Vec3(x, y, Z), new Vec3(X, y, Z), new Vec3(X, y, z), new Vec3(x, y, z));
        };
    }

    private static double[] defaultUv(Direction d, double[] a, double[] b) {
        return switch (d) {
            case DOWN -> new double[]{a[0], 16 - b[2], b[0], 16 - a[2]};
            case UP -> new double[]{a[0], a[2], b[0], b[2]};
            case NORTH -> new double[]{16 - b[0], 16 - b[1], 16 - a[0], 16 - a[1]};
            case SOUTH -> new double[]{a[0], 16 - b[1], b[0], 16 - a[1]};
            case WEST -> new double[]{a[2], 16 - b[1], b[2], 16 - a[1]};
            case EAST -> new double[]{16 - b[2], 16 - b[1], 16 - a[2], 16 - a[1]};
        };
    }
}
