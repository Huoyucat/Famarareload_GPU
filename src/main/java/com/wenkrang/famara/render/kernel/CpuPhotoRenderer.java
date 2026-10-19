package com.wenkrang.famara.render.kernel;

import com.wenkrang.famara.render.asset.*;
import com.wenkrang.famara.render.math.Vec3;
import com.wenkrang.famara.render.scene.*;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.*;

/**
 * 纯 CPU 分批渲染。像素缓冲只有一个 owner，完成前不可发布为照片。
 */
public final class CpuPhotoRenderer {
    public enum AlphaMode {OPAQUE, CUTOUT, TRANSLUCENT}

    public record Settings(double distance, int samples, Texture.Filter filter, boolean directionalLight,
                           double ambientFloor, double cutoutThreshold, int maxTransparentLayers, int backgroundRgb,
                           long animationTicks, VoxelTracer.Limits traceLimits,
                           Map<ResourceKey, AlphaMode> materialOverrides) {
        public Settings {
            if (!Double.isFinite(distance) || distance <= 0 || (samples != 1 && samples != 4) || filter == null
                    || !(ambientFloor >= 0 && ambientFloor <= 1) || !(cutoutThreshold >= 0 && cutoutThreshold <= 1)
                    || maxTransparentLayers <= 0 || (backgroundRgb & 0xff000000) != 0 || traceLimits == null)
                throw new IllegalArgumentException("渲染策略不合法");
            materialOverrides = Map.copyOf(materialOverrides);
        }
    }

    public enum Status {YIELDED, DONE, CANCELLED}

    public record BatchResult(Status status, int completedPixels) {
    }

    public static final class Cursor {
        private final CpuPhotoRenderer owner;
        private final AtomicBoolean running = new AtomicBoolean();
        private final float[] linear;
        private final int start,stride,size;
        private volatile int next;
        private boolean failed;

        private Cursor(CpuPhotoRenderer owner,int start,int stride) {
            this.owner=owner;this.start=start;this.stride=stride;size=(Camera.SIZE*Camera.SIZE-start+stride-1)/stride;linear=new float[size*3];
        }

        public int completedPixels() {
            return next;
        }

        public int[] copyRgb() {
            if (running.get() || failed || next != size)
                throw new IllegalStateException("图像尚未完成");
            int[] rgb = new int[size];
            for (int i = 0; i < rgb.length; i++)
                rgb[i] = Texture.srgb(linear[i * 3]) << 16
                        | Texture.srgb(linear[i * 3 + 1]) << 8 | Texture.srgb(linear[i * 3 + 2]);
            return rgb;
        }
    }

    private final SceneSnapshot scene;
    private final Camera camera;
    private final Settings settings;
    private final VoxelTracer tracer;
    private final AtomicBoolean rendering = new AtomicBoolean();

    public CpuPhotoRenderer(SceneSnapshot scene, Camera camera, Settings settings) {
        this.scene = Objects.requireNonNull(scene);
        this.camera = Objects.requireNonNull(camera);
        this.settings = Objects.requireNonNull(settings);
        tracer = new VoxelTracer(scene, settings.traceLimits);
    }

    public Cursor cursor() {
        return new Cursor(this,0,1);
    }

    /** 每个lane必须使用独立renderer/tracer；仅共享冻结scene和资源，全图坐标不变。 */
    public Cursor cursor(int lane,int lanes) {
        if(lanes<1 || lanes>2 || lane<0 || lane>=lanes)throw new IllegalArgumentException("像素分片无效");
        return new Cursor(this,lane,lanes);
    }
    public BatchResult renderBatch(Cursor cursor, int maxPixels, long deadlineNanos, LongSupplier clock, BooleanSupplier cancelled) {
        if (cursor.owner != this || maxPixels <= 0 || cursor.failed)
            throw new IllegalArgumentException("无效渲染游标/批次");
        if (!rendering.compareAndSet(false, true)) throw new IllegalStateException("渲染器不能重叠执行");
        if (!cursor.running.compareAndSet(false, true)) {
            rendering.set(false);
            throw new IllegalStateException("游标已有owner");
        }
        try {
            int rendered = 0;
            while (cursor.next < cursor.size && rendered < maxPixels) {
                if (cancelled.getAsBoolean()) return new BatchResult(Status.CANCELLED, cursor.next);
                if (clock.getAsLong() >= deadlineNanos) return new BatchResult(Status.YIELDED, cursor.next);
                int pixel=cursor.start+cursor.next*cursor.stride;int x=pixel%Camera.SIZE,y=pixel/Camera.SIZE;
                double r = 0, g = 0, b = 0;
                for (int sample = 0; sample < settings.samples; sample++) {
                    double ox = settings.samples == 1 ? .5 : (sample % 2 + .5) / 2;
                    double oy = settings.samples == 1 ? .5 : (sample / 2 + .5) / 2;
                    Texture.Sample color = sample(camera.ray(x, y, ox, oy), x, y, ox, oy, cancelled);
                    r += color.r();
                    g += color.g();
                    b += color.b();
                }
                cursor.linear[cursor.next * 3] = (float) (r / settings.samples);
                cursor.linear[cursor.next * 3 + 1] = (float) (g / settings.samples);
                cursor.linear[cursor.next * 3 + 2] = (float) (b / settings.samples);
                cursor.next++;
                rendered++;
            }
            return new BatchResult(cursor.next == cursor.size ? Status.DONE : Status.YIELDED, cursor.next);
        } catch (SceneException e) {
            if (e.code() == SceneException.Code.CANCELLED) return new BatchResult(Status.CANCELLED, cursor.next);
            cursor.failed = true;
            throw e;
        } catch (RuntimeException e) {
            cursor.failed = true;
            throw e;
        } finally {
            cursor.running.set(false);
            rendering.set(false);
        }
    }

    public Texture.Sample sampleRay(Ray ray, BooleanSupplier cancelled) {
        if (!rendering.compareAndSet(false, true)) throw new IllegalStateException("渲染器不能重叠执行");
        try {
            return sample(ray, -1, -1, 0, 0, cancelled);
        } finally {
            rendering.set(false);
        }
    }

    private Texture.Sample sample(Ray ray, int x, int y, double offsetX, double offsetY, BooleanSupplier cancelled) {
        double entityDistance=Double.POSITIVE_INFINITY;Integer entityColor=null;int entityTests=0;
        for(var entity:scene.entities()) {
            if((entityTests+=entity.boxes().size())>settings.traceLimits.maxTests())throw new SceneException(SceneException.Code.GEOMETRY_LIMIT,"旧实体求交超过限额");
            double t=entity.hit(ray.origin(),ray.direction(),settings.distance);
            if(t<entityDistance){entityDistance=t;entityColor=entity.rgb();}
        }
        List<VoxelTracer.Hit> hits=entityDistance==0?List.of():tracer.trace(ray,Math.min(settings.distance,entityDistance),cancelled);
        double r = 0, g = 0, b = 0, transmission = 1;
        int transparent = 0;
        for (int first = 0; first < hits.size() && transmission > 1e-6; ) {
            if(hits.get(first).t()>entityDistance)break;
            int end = first + 1;
            while (end < hits.size() && Math.abs(hits.get(end).t() - hits.get(first).t()) <= 1e-9) end++;
            // 同面后定义的覆盖层位于底层之前合成，不能被底层不透明提前终止。
            for (int i = end - 1; i >= first && transmission > 1e-6; i--) {
                VoxelTracer.Hit hit = hits.get(i);
                BakedQuad quad = hit.quad();
                if(hit.instance().farColors()!=null) {
                    Texture.Sample flat=hit.instance().farColors().face(quad.face());r+=transmission*flat.r();g+=transmission*flat.g();b+=transmission*flat.b();transmission=0;continue;
                }
                Texture texture = scene.assets().texture(quad.texture());
                double lod = x < 0 ? 0 : lod(hit, x, y, offsetX, offsetY, texture);
                Texture.Sample color = texture.sample(hit.u(), hit.v(), lod, settings.animationTicks, settings.filter);
                if (color.a() <= 0) continue;
                AlphaMode mode = settings.materialOverrides.getOrDefault(quad.texture(), (quad.opacity()<1 || quad.forceTranslucent() || texture.fractionalAlpha()) ? AlphaMode.TRANSLUCENT :
                        texture.opaque() ? AlphaMode.OPAQUE : AlphaMode.CUTOUT);
                if (mode == AlphaMode.CUTOUT && color.a() < settings.cutoutThreshold) continue;
                double alpha = mode == AlphaMode.TRANSLUCENT ? color.a()*quad.opacity() : 1;
                if (alpha < 1 && ++transparent > settings.maxTransparentLayers)
                    throw new SceneException(SceneException.Code.TRANSPARENCY_LIMIT, "透明层数超过限额");
                double factor = mode == AlphaMode.TRANSLUCENT ? quad.opacity() : 1 / color.a();
                SceneSnapshot.Lighting lighting = hit.instance().lighting();
                SceneSnapshot.FaceLight faceLight=lighting.face(quad.face());
                double intensity=Math.max(faceLight.sky(),Math.max(faceLight.block(),quad.lightEmission()))/15.0;
                intensity = settings.ambientFloor + (1 - settings.ambientFloor) * intensity;
                if (settings.directionalLight && quad.shade()) {
                    Vec3 n = quad.shadeDirectionOverride() == null ? quad.normal() : quad.shadeDirectionOverride().vector();
                    intensity *= n.x() * n.x() * .6 + n.z() * n.z() * .8 + n.y() * n.y() * (n.y() > 0 ? 1 : .5);
                }
                int tint = quad.tintIndex() < 0 ? 0xffffff : lighting.tintRgb();
                r += transmission * color.r() * factor * intensity * Texture.linear((tint >> 16) & 255)*Texture.linear((quad.colorMultiplier()>>16)&255);
                g += transmission * color.g() * factor * intensity * Texture.linear((tint >> 8) & 255)*Texture.linear((quad.colorMultiplier()>>8)&255);
                b += transmission * color.b() * factor * intensity * Texture.linear(tint & 255)*Texture.linear(quad.colorMultiplier()&255);
                transmission *= 1 - alpha;
            }
            first = end;
        }
        int bg=entityColor==null?settings.backgroundRgb:entityColor;
        return new Texture.Sample(r + transmission * Texture.linear((bg >> 16) & 255), g + transmission * Texture.linear((bg >> 8) & 255),
                b + transmission * Texture.linear(bg & 255), 1);
    }

    private double lod(VoxelTracer.Hit hit, int x, int y, double ox, double oy, Texture texture) {
        if (settings.filter != Texture.Filter.MIPMAP) return 0;
        double[] a = planeUv(camera.ray(x == 127 ? x - 1 : x + 1, y, ox, oy), hit);
        double[] b = planeUv(camera.ray(x, y == 127 ? y - 1 : y + 1, ox, oy), hit);
        if (a == null || b == null) return Math.log(Math.max(texture.width(), texture.height())) / Math.log(2);
        double dx = Math.hypot((a[0] - hit.u()) * texture.width(), (a[1] - hit.v()) * texture.height());
        double dy = Math.hypot((b[0] - hit.u()) * texture.width(), (b[1] - hit.v()) * texture.height());
        return Math.max(0, Math.log(Math.max(1, Math.max(dx, dy))) / Math.log(2));
    }

    private static double[] planeUv(Ray ray, VoxelTracer.Hit hit) {
        BakedQuad q = hit.quad();
        Vec3 p = q.positions().get(0), a = q.positions().get(1), b = q.positions().get(3), n = q.normal();
        Vec3 local = ray.origin().subtract(new Vec3(hit.instance().x(), hit.instance().y(), hit.instance().z()));
        double denom = n.dot(ray.direction());
        if (Math.abs(denom) < 1e-10) return null;
        double t = n.dot(p.subtract(local)) / denom;
        if (t < 0) return null;
        Vec3 delta = local.add(ray.direction().multiply(t)).subtract(p), u = a.subtract(p), v = b.subtract(p);
        double aa = u.dot(u), bb = v.dot(v), ab = u.dot(v), det = aa * bb - ab * ab;
        if (Math.abs(det) < 1e-20) return null;
        double s = (delta.dot(u) * bb - delta.dot(v) * ab) / det, w = (delta.dot(v) * aa - delta.dot(u) * ab) / det;
        BakedQuad.Uv uv = q.uv().get(0), uvA = q.uv().get(1), uvB = q.uv().get(3);
        return new double[]{uv.u() + s * (uvA.u() - uv.u()) + w * (uvB.u() - uv.u()), uv.v() + s * (uvA.v() - uv.v()) + w * (uvB.v() - uv.v())};
    }
}
