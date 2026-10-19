package com.wenkrang.famara.render.asset;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.image.BufferedImage;
import java.io.*;
import java.util.*;

import com.google.gson.*;

/**
 * 只读线性预乘 RGBA 金字塔。动画帧分开缩小，避免串帧和透明黑边。
 */
public final class Texture {
    public enum Filter {NEAREST, BILINEAR, MIPMAP}

    public record Sample(double r, double g, double b, double a) {
        public Sample mix(Sample other, double t) {
            return new Sample(r + (other.r - r) * t, g + (other.g - g) * t,
                    b + (other.b - b) * t, a + (other.a - a) * t);
        }
    }

    private record Level(int width, int height, float[] values) {
    }

    private record Frame(int index, int duration) {
    }

    private final List<List<Level>> images;
    private final List<Frame> timeline;
    private final long duration;
    private final boolean interpolate;
    private final boolean opaque;
    private final boolean fractionalAlpha;
    private final int width;
    private final int height;
    private final long bytes;
    private static final double[] LINEAR = new double[256];

    static {
        for (int i = 0; i < 256; i++) {
            double c = i / 255.0;
            LINEAR[i] = c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
        }
    }

    private Texture(List<List<Level>> images, List<Frame> timeline, boolean interpolate, boolean opaque, boolean fractionalAlpha) {
        this.images = List.copyOf(images);
        this.timeline = List.copyOf(timeline);
        this.duration = timeline.stream().mapToLong(Frame::duration).sum();
        this.interpolate = interpolate;
        this.opaque = opaque;
        this.fractionalAlpha = fractionalAlpha;
        this.width = images.getFirst().getFirst().width;
        this.height = images.getFirst().getFirst().height;
        this.bytes = images.stream().flatMap(Collection::stream).mapToLong(l -> l.values.length * 4L).sum();
    }

    public boolean opaque() {
        return opaque;
    }

    public boolean fractionalAlpha() {
        return fractionalAlpha;
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    public long retainedBytes() {
        return bytes;
    }

    public int frameCount() {
        return images.size();
    }

    public static Texture decode(ResourcePack pack, ResourceKey key, AssetLimits limits) {
        AssetMemoryBudget memory=new AssetMemoryBudget(limits.maxTotalBytes());
        memory.reserve(pack.retainedBytes(),"source-pack");
        return decode(pack,key,limits,memory);
    }

    static Texture decode(ResourcePack pack,ResourceKey key,AssetLimits limits,AssetMemoryBudget memory) {
        String path = key.file("textures", ".png");
        ResourcePack.Content content = pack.require(path);
        try (InputStream input = content.open(); ImageInputStream imageInput = ImageIO.createImageInputStream(input)) {
            if (imageInput == null) throw invalid(path, "PNG输入无法打开");
            Iterator<ImageReader> readers = ImageIO.getImageReaders(imageInput);
            if (!readers.hasNext()) throw invalid(path, "无法识别PNG");
            ImageReader reader = readers.next();
            try {
                if (!reader.getFormatName().equalsIgnoreCase("png")) throw invalid(path, "贴图不是PNG");
                reader.setInput(imageInput, true, true);
                int w = reader.getWidth(0), h = reader.getHeight(0);
                if (w <= 0 || h <= 0 || (long) w * h > limits.maxTexturePixels()) throw limit(path);
                JsonObject animation = null;
                ResourcePack.Content metadata = pack.find(path + ".mcmeta");
                if (metadata != null) {
                    JsonObject root = BoundedJson.read(metadata, path + ".mcmeta", limits,memory);
                    if (root.has("animation")) animation = root.getAsJsonObject("animation");
                }
                int fw = w, fh = h;
                boolean interpolate = false;
                List<Frame> timeline = new ArrayList<>();
                if (animation != null) {
                    checkFields(animation, Set.of("width", "height", "frametime", "frames", "interpolate"), path);
                    int size = Math.min(w, h);
                    fw = animation.has("width") ? JsonValues.integer(animation.get("width")) : animation.has("height") ? w : size;
                    fh = animation.has("height") ? JsonValues.integer(animation.get("height")) : animation.has("width") ? h : size;
                    interpolate = animation.has("interpolate") && animation.get("interpolate").getAsBoolean();
                }
                if (fw <= 0 || fh <= 0 || w % fw != 0 || h % fh != 0) throw invalid(path, "动画帧尺寸不合法");
                long countLong = (long) (w / fw) * (h / fh);
                if (countLong > limits.maxAnimationFrames()) throw limit(path);
                int count = (int) countLong;
                if ((long) w * h * 24 > limits.maxTotalBytes()) throw limit(path);
                int defaultTime = animation != null && animation.has("frametime") ? JsonValues.integer(animation.get("frametime")) : 1;
                if (defaultTime <= 0) throw invalid(path, "动画时长必须为正");
                if (animation != null && animation.has("frames")) {
                    JsonArray sequence = animation.getAsJsonArray("frames");
                    if (sequence.isEmpty() || sequence.size() > limits.maxAnimationFrames()) throw limit(path);
                    for (JsonElement e : sequence) {
                        int index, time = defaultTime;
                        if (e.isJsonObject()) {
                            JsonObject frame = e.getAsJsonObject();
                            checkFields(frame, Set.of("index", "time"), path);
                            index = JsonValues.integer(frame.get("index"));
                            if (frame.has("time")) time = JsonValues.integer(frame.get("time"));
                        } else index = JsonValues.integer(e);
                        if (index < 0 || index >= count || time <= 0) throw invalid(path, "动画帧索引或时长不合法");
                        timeline.add(new Frame(index, time));
                    }
                } else for (int i = 0; i < count; i++) timeline.add(new Frame(i, defaultTime));
                long allocation=(long)w*h*4+timeline.size()*32L+1024;
                int mw=fw,mh=fh;
                while(true) {
                    allocation=Math.addExact(allocation,Math.addExact((long)mw*mh*16,128L)*count);
                    if(mw==1 && mh==1)break;
                    mw=Math.max(1,(mw+1)/2);mh=Math.max(1,(mh+1)/2);
                }
                memory.reserve(allocation,path);
                BufferedImage image = reader.read(0);
                List<List<Level>> frames = new ArrayList<>();
                boolean opaque = true;
                boolean fractional = false;
                for (int frame = 0; frame < count; frame++) {
                    int x0 = frame % (w / fw) * fw, y0 = frame / (w / fw) * fh;
                    float[] pixels = new float[Math.multiplyExact(Math.multiplyExact(fw, fh), 4)];
                    for (int y = 0; y < fh; y++)
                        for (int x = 0; x < fw; x++) {
                            int argb = image.getRGB(x0 + x, y0 + y), offset = (y * fw + x) * 4;
                            double alpha = (argb >>> 24) / 255.0;
                            opaque &= (argb >>> 24) == 255;
                            fractional |= (argb >>> 24) != 0 && (argb >>> 24) != 255;
                            pixels[offset] = (float) (LINEAR[(argb >> 16) & 255] * alpha);
                            pixels[offset + 1] = (float) (LINEAR[(argb >> 8) & 255] * alpha);
                            pixels[offset + 2] = (float) (LINEAR[argb & 255] * alpha);
                            pixels[offset + 3] = (float) alpha;
                        }
                    List<Level> levels = new ArrayList<>();
                    Level level = new Level(fw, fh, pixels);
                    levels.add(level);
                    while (level.width > 1 || level.height > 1) {
                        int nw = Math.max(1, (level.width + 1) / 2), nh = Math.max(1, (level.height + 1) / 2);
                        float[] next = new float[Math.multiplyExact(Math.multiplyExact(nw, nh), 4)];
                        for (int y = 0; y < nh; y++)
                            for (int x = 0; x < nw; x++) {
                                int samples = 0;
                                for (int dy = 0; dy < 2; dy++)
                                    for (int dx = 0; dx < 2; dx++) {
                                        int sx = x * 2 + dx, sy = y * 2 + dy;
                                        if (sx >= level.width || sy >= level.height) continue;
                                        samples++;
                                        for (int c = 0; c < 4; c++)
                                            next[(y * nw + x) * 4 + c] += level.values[(sy * level.width + sx) * 4 + c];
                                    }
                                for (int c = 0; c < 4; c++) next[(y * nw + x) * 4 + c] /= samples;
                            }
                        level = new Level(nw, nh, next);
                        levels.add(level);
                    }
                    frames.add(List.copyOf(levels));
                }
                return new Texture(frames, timeline, interpolate, opaque, fractional);
            } finally {
                reader.dispose();
            }
        } catch (AssetException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            throw new AssetException(AssetException.Code.INVALID, path, "贴图无法编译", e);
        }
    }

    public Sample sample(double u, double v, double lod, long ticks, Filter filter) {
        if (!Double.isFinite(u) || !Double.isFinite(v) || !Double.isFinite(lod))
            throw new IllegalArgumentException("采样坐标必须有限");
        long phase = Math.floorMod(ticks, duration);
        int position = 0;
        while (phase >= timeline.get(position).duration) {
            phase -= timeline.get(position).duration;
            position++;
        }
        Frame frame = timeline.get(position);
        Sample current = sampleFrame(frame.index, u, v, lod, filter);
        if (!interpolate) return current;
        Frame next = timeline.get((position + 1) % timeline.size());
        return current.mix(sampleFrame(next.index, u, v, lod, filter), (double) phase / frame.duration);
    }

    private Sample sampleFrame(int frame, double u, double v, double lod, Filter filter) {
        List<Level> levels = images.get(frame);
        if (filter == Filter.NEAREST) return texel(levels.getFirst(), u, v);
        if (filter == Filter.BILINEAR) return bilinear(levels.getFirst(), u, v);
        if (lod <= 0) return texel(levels.getFirst(), u, v);
        double clamped = Math.max(0, Math.min(levels.size() - 1, lod));
        int low = (int) clamped, high = Math.min(levels.size() - 1, low + 1);
        return bilinear(levels.get(low), u, v).mix(bilinear(levels.get(high), u, v), clamped - low);
    }

    private static Sample texel(Level l, double u, double v) {
        int x = Math.min(l.width - 1, Math.max(0, (int) Math.floor(u * l.width)));
        int y = Math.min(l.height - 1, Math.max(0, (int) Math.floor(v * l.height)));
        return at(l, x, y);
    }

    private static Sample bilinear(Level l, double u, double v) {
        double x = Math.max(0, Math.min(l.width - 1, u * l.width - 0.5));
        double y = Math.max(0, Math.min(l.height - 1, v * l.height - 0.5));
        int ix = (int) x, iy = (int) y;
        Sample a = at(l, ix, iy).mix(at(l, Math.min(ix + 1, l.width - 1), iy), x - ix);
        Sample b = at(l, ix, Math.min(iy + 1, l.height - 1)).mix(at(l, Math.min(ix + 1, l.width - 1), Math.min(iy + 1, l.height - 1)), x - ix);
        return a.mix(b, y - iy);
    }

    private static Sample at(Level l, int x, int y) {
        int p = (y * l.width + x) * 4;
        return new Sample(l.values[p], l.values[p + 1], l.values[p + 2], l.values[p + 3]);
    }

    public static double linear(int srgb) {
        return LINEAR[srgb];
    }

    public static int srgb(double linear) {
        double c = Math.max(0, Math.min(1, linear));
        return (int) Math.round(255 * (c <= 0.0031308 ? 12.92 * c : 1.055 * Math.pow(c, 1 / 2.4) - 0.055));
    }

    static void checkFields(JsonObject o, Set<String> allowed, String path) {
        for (String key : o.keySet())
            if (!allowed.contains(key))
                throw new AssetException(AssetException.Code.UNSUPPORTED, path, "尚未支持字段 " + key);
    }

    private static AssetException invalid(String path, String message) {
        return new AssetException(AssetException.Code.INVALID, path, message);
    }

    private static AssetException limit(String path) {
        return new AssetException(AssetException.Code.LIMIT, path, "贴图/动画超过限额");
    }
}
