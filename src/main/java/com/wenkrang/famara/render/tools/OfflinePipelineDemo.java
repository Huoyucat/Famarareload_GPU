package com.wenkrang.famara.render.tools;

import com.google.gson.*;
import com.wenkrang.famara.render.asset.*;
import com.wenkrang.famara.render.kernel.*;
import com.wenkrang.famara.render.scene.SceneSnapshot;
import com.wenkrang.famara.render.schedule.*;
import com.wenkrang.famara.render.output.*;
import com.wenkrang.famara.render.platform.MapPaletteAdapter;

import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/**
 * 手写离线场景的完整管线演示，不连接游戏服务器，不伪造世界快照或实际 CE 映射。
 */
public final class OfflinePipelineDemo {
    public record Section(int x, int y, int z) {
    }

    public record Block(int x, int y, int z, String logicalId, String visualState, boolean ce, int sky, int block,
                        int tintRgb) {
    }

    public record Scene(int minHeight, int maxHeight, int maxInstances, int maxIndexEntries, List<Section> sections,
                        List<Block> blocks) {
        public Scene {
            sections = List.copyOf(sections);
            blocks = List.copyOf(blocks);
        }
    }

    public record Shot(Camera camera, CpuPhotoRenderer.Settings settings, long reservationBytes, int outputId) {
    }

    public record Configuration(AssetLimits assets, CpuPhotoPipeline.Limits pipeline, BudgetedAssetLoader.Limits assetBuild, Scene scene, Shot shot,
                                List<String> cePacks) {
        public Configuration {
            Objects.requireNonNull(assets);
            Objects.requireNonNull(pipeline);
            Objects.requireNonNull(assetBuild);
            Objects.requireNonNull(scene);
            Objects.requireNonNull(shot);
            cePacks = List.copyOf(cePacks);
        }
    }

    private OfflinePipelineDemo() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 3) throw new IllegalArgumentException("参数：显式配置JSON 基础资源JAR/目录 新输出目录");
        Path configPath = Path.of(args[0]), source = Path.of(args[1]), output = Path.of(args[2]).toAbsolutePath().normalize();
        if (Files.exists(output)) throw new FileAlreadyExistsException("离线输出目录必须全新：" + output);
        Configuration config = configuration(configPath);
        CpuBudget shared=new CpuBudget(config.pipeline.cpuMillisPerSecond(),config.pipeline.burstMillis(),System::nanoTime);
        try(AssetRepository repository=new AssetRepository(2,config.assets.maxTotalBytes());
            BudgetedAssetLoader loader=new BudgetedAssetLoader(repository,config.assets,config.assetBuild,shared)) {
        List<BudgetedAssetLoader.Source> sources=new ArrayList<>();
        for(String ce:config.cePacks)sources.add(new BudgetedAssetLoader.Source(Path.of(ce),ResourcePack.Role.CUSTOM,true));
        sources.add(new BudgetedAssetLoader.Source(source,ResourcePack.Role.BASE,true));
        BudgetedAssetLoader.Result build=loader.reloadSources(sources,()->true).toCompletableFuture().get();
        AssetGeneration generation;
        try(var lease=repository.acquire()){generation=lease.generation();}
        MapPaletteSnapshot palette = MapPaletteAdapter.snapshot();
        Files.createDirectories(output);
        DiskPhotoStore store = new DiskPhotoStore(output.resolve("pictures"));
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("kind", "MANUAL_OFFLINE_SCENE_NOT_SERVER_OR_CE_RUNTIME_VERIFICATION");
        report.put("configuration", new Gson().toJsonTree(config));
        report.put("sourceFingerprint", generation.fingerprint());
        report.put("java", System.getProperty("java.version"));
        report.put("palette", palette.version());
        report.put("paletteProvenance", "当前classpath的Bukkit API快照，不是目标服务器实测");
        report.put("assetBuildWallMs",build.wallNanos()/1e6);
        report.put("assetBuildCpuMs",build.cpuNanos()/1e6);
        report.put("assetBuildSlices",build.slices());
        report.put("assetWorkingReservation",build.estimatedBytes());
        report.put("assetCompilationBudgeted",true);
        report.put("parsedDefinitions", generation.blockKeys().size());
        report.put("completeDefinitions", generation.completeBlockDefinitions());
        report.put("issues", generation.issues());
            try (CpuPhotoPipeline pipeline = new CpuPhotoPipeline(config.pipeline, store, (stored, photo, current) -> {
                if (!current.getAsBoolean())
                    return CompletableFuture.failedFuture(new CancellationException("离线发布已失效"));
                try {
                    int[] mapRgb = new int[16384];
                    byte[] indices = photo.copyMap();
                    for (int i = 0; i < indices.length; i++) mapRgb[i] = palette.color(indices[i]);
                    BufferedImage image = new BufferedImage(128, 128, BufferedImage.TYPE_INT_RGB);
                    image.setRGB(0, 0, 128, 128, mapRgb, 0, 128);
                    try (var out = Files.newOutputStream(output.resolve("offline-map-preview.png"), StandardOpenOption.CREATE_NEW)) {
                        if (!ImageIO.write(image, "png", out)) throw new IllegalStateException("PNG编码器不可用");
                    }
                    return CompletableFuture.completedFuture(null);
                } catch (Exception e) {
                    return CompletableFuture.failedFuture(e);
                }
            },shared)) {
                long submitted = System.nanoTime();
                UUID player = UUID.nameUUIDFromBytes("synthetic-offline-observer".getBytes(StandardCharsets.UTF_8));
                var handle = pipeline.submit(player, UUID.randomUUID(), config.shot.outputId, config.shot.reservationBytes, () -> {
                    var lease = repository.acquire();
                    try {
                        Scene s = config.scene;
                        SceneSnapshot.Builder builder = new SceneSnapshot.Builder(lease.generation(), s.minHeight, s.maxHeight, s.maxInstances, s.maxIndexEntries);
                        for (Section section : s.sections) builder.availableSection(section.x, section.y, section.z);
                        for (Block block : s.blocks) {
                            var state = CanonicalBlockState.parse(block.visualState);
                            var visual = new VisualBlockDescriptor(block.ce ? VisualBlockDescriptor.Kind.CE : VisualBlockDescriptor.Kind.VANILLA,
                                    ResourceKey.parse(block.logicalId), state, lease.generation().id());
                            builder.put(block.x, block.y, block.z, visual, new SceneSnapshot.Lighting(block.sky, block.block, block.tintRgb), 0);
                        }
                        return new CpuPhotoPipeline.Prepared(builder.build(), config.shot.camera, config.shot.settings, palette, lease);
                    } catch (RuntimeException e) {
                        lease.close();
                        throw e;
                    }
                });
                try {
                    var result = handle.result().toCompletableFuture().get();
                    report.put("status", handle.state().stage().toString());
                    report.put("renderPipelineWallMs", (System.nanoTime() - submitted) / 1e6);
                    report.put("workerCpuMs", result.cpuNanos() / 1e6);
                    report.put("cpuMeasurement", result.cpuMeasurement());
                    report.put("admissionAfter", pipeline.admissionSnapshot());
                    report.put("budgetAfter", pipeline.cpuSnapshot());
                    report.put("storedPng", output.relativize(result.stored().png()).toString());
                    report.put("preview", "offline-map-preview.png");
                } catch (ExecutionException e) {
                    report.put("status", "FAILED");
                    report.put("failure", e.getCause().toString());
                }
            }
        String json = new GsonBuilder().setPrettyPrinting().serializeNulls().create().toJson(report);
        Files.writeString(output.resolve("offline-report.json"), json, StandardOpenOption.CREATE_NEW);
        System.out.println("离线管线状态：" + report.get("status") + "；完整报告：" + output.resolve("offline-report.json"));
        if (!"SUCCEEDED".equals(report.get("status"))) throw new IllegalStateException("离线测试未成功，请查看报告");
        }
    }

    static Configuration configuration(Path path) throws Exception {
        if (Files.size(path) > 1024 * 1024) throw new IllegalArgumentException("离线配置过大");
        AssetLimits jsonLimits = new AssetLimits(1, 1024 * 1024, 2 * 1024 * 1024, 1, 1, 1, 1, 32, 20000, 1);
        ResourcePack one = ResourcePack.memory("offline-config", Map.of("config.json", Files.readAllBytes(path)), jsonLimits);
        JsonObject root = BoundedJson.read(one.require("config.json"), "config.json", jsonLimits);
        required(root, Configuration.class);
        required(root.getAsJsonObject("assets"), AssetLimits.class);
        required(root.getAsJsonObject("pipeline"), CpuPhotoPipeline.Limits.class);
        required(root.getAsJsonObject("assetBuild"), BudgetedAssetLoader.Limits.class);
        required(root.getAsJsonObject("scene"), Scene.class);
        for (JsonElement section : root.getAsJsonObject("scene").getAsJsonArray("sections"))
            required(section.getAsJsonObject(), Section.class);
        for (JsonElement block : root.getAsJsonObject("scene").getAsJsonArray("blocks"))
            required(block.getAsJsonObject(), Block.class);
        JsonObject shot = root.getAsJsonObject("shot");
        required(shot, Shot.class);
        required(shot.getAsJsonObject("camera"), Camera.class);
        required(shot.getAsJsonObject("camera").getAsJsonObject("origin"), com.wenkrang.famara.render.math.Vec3.class);
        required(shot.getAsJsonObject("settings"), CpuPhotoRenderer.Settings.class);
        required(shot.getAsJsonObject("settings").getAsJsonObject("traceLimits"), VoxelTracer.Limits.class);
        Gson gson = new GsonBuilder().registerTypeAdapter(ResourceKey.class, (JsonDeserializer<ResourceKey>) (e, t, c) -> ResourceKey.parse(e.getAsString())).create();
        return gson.fromJson(root, Configuration.class);
    }

    private static void required(JsonObject object, Class<?> record) {
        Set<String> fields = new HashSet<>();
        for (var component : record.getRecordComponents()) {
            String name = component.getName();
            fields.add(name);
            if (!object.has(name) && (record==CpuPhotoPipeline.Limits.class && name.equals("scanBatchCpuNanos") || record==BudgetedAssetLoader.Limits.class && name.equals("maximumSourceBytes")))continue;
            if (!object.has(name) || object.get(name).isJsonNull())
                throw new IllegalArgumentException("缺少显式配置：" + record.getSimpleName() + "." + name);
        }
        for (String name : object.keySet())
            if (!fields.contains(name)) throw new IllegalArgumentException("未知配置：" + name);
    }
}
