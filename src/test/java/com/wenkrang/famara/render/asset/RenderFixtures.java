package com.wenkrang.famara.render.asset;

import java.util.*;
import java.nio.charset.StandardCharsets;
import java.io.ByteArrayOutputStream;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;

/**
 * 自建测试资源，不读取或分发客户端素材。
 */
public final class RenderFixtures {
    public final Map<String, byte[]> files = new LinkedHashMap<>();
    public static final AssetLimits LIMITS = new AssetLimits(10000, 2_000_000, 64_000_000,
            1_000_000, 100, 10000, 64, 64, 100000, 16);

    public void json(String path, String text) {
        files.put(path, text.getBytes(StandardCharsets.UTF_8));
    }

    public void png(String key, int width, int height, int... pixels) throws Exception {
        ResourceKey resource = ResourceKey.parse(key);
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < height; y++)
            for (int x = 0; x < width; x++) image.setRGB(x, y, pixels[(y * width + x) % pixels.length]);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (!ImageIO.write(image, "png", out)) throw new AssertionError("PNG编码失败");
        files.put(resource.file("textures", ".png"), out.toByteArray());
    }

    public void cube(String model, String texture) {
        json(ResourceKey.parse(model).file("models", ".json"), """
                {"textures":{"all":"%s"},"elements":[{"from":[0,0,0],"to":[16,16,16],"faces":{
                "north":{"texture":"#all"},"south":{"texture":"#all"},
                "east":{"texture":"#all"},"west":{"texture":"#all"},
                "up":{"texture":"#all"},"down":{"texture":"#all"}}}]}
                """.formatted(texture));
    }

    public void block(String state, String model) {
        json(ResourceKey.parse(state).file("blockstates", ".json"), "{\"variants\":{\"\":{\"model\":\"" + model + "\"}}}");
    }

    public ResourcePack pack() {
        return ResourcePack.memory("synthetic", files, LIMITS);
    }

    public AssetGeneration generation(long id) {
        return AssetGeneration.compile(id, pack(), LIMITS);
    }

    public static VisualBlockDescriptor vanilla(String state, long id) {
        return VisualBlockDescriptor.vanilla(CanonicalBlockState.parse(state), id);
    }
}
