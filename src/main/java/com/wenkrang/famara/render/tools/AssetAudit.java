package com.wenkrang.famara.render.tools;

import com.google.gson.GsonBuilder;
import com.wenkrang.famara.render.asset.*;
import com.wenkrang.famara.render.math.Vec3;
import com.wenkrang.famara.render.kernel.*;
import com.wenkrang.famara.render.scene.SceneSnapshot;

import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.nio.file.*;
import java.util.*;
import java.lang.management.*;

/**
 * 显式本地离线审计入口，不加载 Bukkit、CE 或客户端 class。样例不是服务器截图。
 */
public final class AssetAudit {
    private AssetAudit() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 4)
            throw new IllegalArgumentException("参数：基础资源JAR/目录 输出目录 最大读取MiB 最大贴图像素数");
        Path source = Path.of(args[0]), out = Path.of(args[1]);
        long bytes = Math.multiplyExact(Long.parseLong(args[2]), 1024 * 1024);
        int pixels = Integer.parseInt(args[3]);
        AssetLimits limits = new AssetLimits(200000, 4 * 1024 * 1024, bytes, pixels, 4096, 20000, 64, 64, 200000, 64);
        long start = System.nanoTime();
        ResourcePack pack = ResourcePack.load(source, limits);
        long readDone = System.nanoTime();
        AssetGeneration generation = AssetGeneration.compile(1, pack, limits);
        long compileDone = System.nanoTime();
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("kind", "OFFLINE_RESOURCE_AUDIT_NOT_SERVER_BENCHMARK");
        report.put("source", source.getFileName().toString());
        report.put("sha256", pack.fingerprint());
        report.put("java", System.getProperty("java.version"));
        report.put("processors", Runtime.getRuntime().availableProcessors());
        report.put("parameters", Map.of("maxReadBytes", bytes, "maxTexturePixels", pixels, "samples", 1, "distance", 8));
        report.put("readWallMs", (readDone - start) / 1e6);
        report.put("compileWallMs", (compileDone - readDone) / 1e6);
        report.put("sourceRetainedBytes", pack.retainedBytes());
        report.put("decodedTextureBytes", generation.retainedTextureBytes());
        report.put("parsedBlockDefinitions", generation.blockKeys().size());
        report.put("completeModelDefinitions", generation.completeBlockDefinitions());
        report.put("failedBlockDefinitions", generation.failedBlockCount());
        report.put("coverageDenominator", "资源包blockstate定义；不是服务端注册全状态或动态特殊内容");
        report.put("issues", generation.issues());
        Files.createDirectories(out);
        List<Map<String, Object>> samples = new ArrayList<>();
        List<String> states = List.of("minecraft:stone", "minecraft:oak_planks",
                "minecraft:oak_stairs[facing=east,half=bottom,shape=straight,waterlogged=false]",
                "minecraft:oak_fence[north=true,east=false,south=true,west=false,waterlogged=false]",
                "minecraft:chest[facing=north,type=single,waterlogged=false]","minecraft:red_banner[rotation=3]",
                "minecraft:copper_golem_statue[copper_golem_pose=star,facing=east,waterlogged=false]","minecraft:water[level=0]");
        ThreadMXBean cpu = ManagementFactory.getThreadMXBean();
        boolean metered = cpu.isCurrentThreadCpuTimeSupported();
        if (metered && !cpu.isThreadCpuTimeEnabled()) cpu.setThreadCpuTimeEnabled(true);
        for (String state : states) {
            Map<String, Object> sample = new LinkedHashMap<>();
            sample.put("state", state);
            try {
                SceneSnapshot.Builder builder = new SceneSnapshot.Builder(generation, 0, 32, 10, 10000);
                for (int x = -1; x <= 1; x++)
                    for (int z = -1; z <= 1; z++) for (int y = 0; y < 2; y++) builder.availableSection(x, y, z);
                if(com.wenkrang.famara.render.scene.FluidMesher.type(CanonicalBlockState.parse(state))!=null)
                    for(int nx=-1;nx<=1;nx++)for(int nz=-1;nz<=1;nz++)for(int ny=0;ny<=1;ny++)
                        builder.neighbor(nx,ny,nz,CanonicalBlockState.parse("minecraft:air"),false);
                builder.put(0, 0, 0, VisualBlockDescriptor.vanilla(CanonicalBlockState.parse(state), 1), new SceneSnapshot.Lighting(15, 0, 0xffffff), 0);
                CpuPhotoRenderer renderer = new CpuPhotoRenderer(builder.build(),
                        new Camera(new Vec3(2, 1.3, -2), Math.atan2(2.5, -1.5), -.2, Camera.Projection.PERSPECTIVE, Math.toRadians(40)),
                        new CpuPhotoRenderer.Settings(8, 1, Texture.Filter.NEAREST, true, .1, .5, 32, 0x8fb5d8, 0,
                                new VoxelTracer.Limits(10000, 1000, 10000), Map.of()));
                var cursor = renderer.cursor();
                long before = System.nanoTime(), cpuBefore = metered ? cpu.getCurrentThreadCpuTime() : 0;
                while (renderer.renderBatch(cursor, 256, Long.MAX_VALUE, System::nanoTime, () -> false).status() != CpuPhotoRenderer.Status.DONE) {
                }
                sample.put("wallMs", (System.nanoTime() - before) / 1e6);
                sample.put("cpuMs", metered ? (cpu.getCurrentThreadCpuTime() - cpuBefore) / 1e6 : null);
                sample.put("measurement", metered ? "CURRENT_THREAD_CPU" : "WALL_ONLY");
                int[] rgb = cursor.copyRgb();
                Set<Integer> colors = new HashSet<>();
                for (int color : rgb) colors.add(color);
                sample.put("rgbColors", colors.size());
                sample.put("pixels", rgb.length);
                BufferedImage image = new BufferedImage(128, 128, BufferedImage.TYPE_INT_RGB);
                image.setRGB(0, 0, 128, 128, rgb, 0, 128);
                String name = CanonicalBlockState.parse(state).block().path() + "-offline-rgb.png";
                Path target = out.resolve(name);
                try (var stream = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW)) {
                    if (!ImageIO.write(image, "png", stream)) throw new IllegalStateException("PNG编码器不可用");
                }
                sample.put("output", name);
                sample.put("status", "SUCCESS");
            } catch (RuntimeException e) {
                sample.put("status", "FAILED");
                sample.put("error", e.toString());
            }
            samples.add(sample);
        }
        report.put("samples", samples);
        String json = new GsonBuilder().setPrettyPrinting().serializeNulls().create().toJson(report);
        Files.writeString(out.resolve("coverage.json"), json, StandardOpenOption.CREATE_NEW);
        System.out.println(new GsonBuilder().setPrettyPrinting().serializeNulls().create().toJson(Map.of(
                "parsedDefinitions", generation.blockKeys().size(), "completeDefinitions", generation.completeBlockDefinitions(),
                "issueCount", generation.issues().size(), "samples", samples, "report", out.resolve("coverage.json").toString())));
    }
}
