package com.wenkrang.famara.render.kernel;

import com.wenkrang.famara.render.asset.*;
import com.wenkrang.famara.render.math.Vec3;
import com.wenkrang.famara.render.scene.*;

import java.util.*;
import java.util.function.BooleanSupplier;

/**
 * 每个 worker 独享。supercover 处理边角同 t，mailbox 防外伸实例重复求交。
 */
public final class VoxelTracer {
    public record Limits(int maxCells, int maxIntersections, int maxTests) {
        public Limits {
            if (maxCells <= 0 || maxIntersections <= 0 || maxTests <= 0)
                throw new IllegalArgumentException("射线预算必须为正");
        }
    }

    public record Hit(SceneSnapshot.Instance instance, int modelIndex, int quadIndex, BakedQuad quad, double t,
                      double u, double v) {
    }

    private final SceneSnapshot scene;
    private final Limits limits;
    private final SceneSnapshot.IndexCursor index;
    private final int[] visited;
    private int epoch;
    private int cells, tests;
    private double nearestOpaque;
    private final ArrayList<Hit> hits = new ArrayList<>();

    public VoxelTracer(SceneSnapshot scene, Limits limits) {
        this.scene = Objects.requireNonNull(scene);
        this.limits = Objects.requireNonNull(limits);index=scene.indexCursor();
        visited = new int[scene.instances().size()];
    }

    public List<Hit> trace(Ray ray, double maxDistance, BooleanSupplier cancelled) {
        if (!Double.isFinite(maxDistance) || maxDistance <= 0) throw new IllegalArgumentException("距离不合法");
        if (++epoch == 0) {
            Arrays.fill(visited, 0);
            epoch = 1;
        }
        cells = 0;
        tests = 0;
        hits.clear();
        nearestOpaque = maxDistance;
        Vec3 o = ray.origin(), d = ray.direction();
        double begin = 0, end = maxDistance;
        if (d.y() == 0) {
            if (o.y() < scene.minHeight() || o.y() >= scene.maxHeight()) return List.of();
        } else {
            double a = (scene.minHeight() - o.y()) / d.y(), b = (scene.maxHeight() - o.y()) / d.y();
            begin = Math.max(0, Math.min(a, b));
            end = Math.min(end, Math.max(a, b));
            if (begin > end || end < 0) return List.of();
        }
        double[] origin = {o.x(), o.y(), o.z()}, direction = {d.x(), d.y(), d.z()};
        int[] cell = new int[3], step = new int[3];
        double[] next = new double[3], delta = new double[3];
        for (int axis = 0; axis < 3; axis++) {
            double point = origin[axis] + direction[axis] * begin;
            step[axis] = direction[axis] > 0 ? 1 : direction[axis] < 0 ? -1 : 0;
            if (point < Integer.MIN_VALUE + 1.0 || point > Integer.MAX_VALUE - 1.0)
                throw new SceneException(SceneException.Code.GEOMETRY_LIMIT, "射线坐标超界");
            if (direction[axis] != 0) point = Math.nextAfter(point, point + step[axis]);
            cell[axis] = (int) Math.floor(point);
            delta[axis] = step[axis] == 0 ? Double.POSITIVE_INFINITY : 1 / Math.abs(direction[axis]);
            double boundary = step[axis] > 0 ? cell[axis] + 1.0 : cell[axis];
            next[axis] = step[axis] == 0 ? Double.POSITIVE_INFINITY : (boundary - origin[axis]) / direction[axis];
        }
        double entered = begin;
        while (entered <= end && entered <= nearestOpaque + 1e-9) {
            if (cancelled.getAsBoolean()) throw new SceneException(SceneException.Code.CANCELLED, "射线已取消");
            visit(cell[0], cell[1], cell[2], ray, end);
            double crossing = Math.min(next[0], Math.min(next[1], next[2]));
            if (crossing > end || crossing > nearestOpaque + 1e-9 || !Double.isFinite(crossing)) break;
            int[] tied = new int[3];
            int size = 0;
            for (int axis = 0; axis < 3; axis++)
                if (Math.abs(next[axis] - crossing) <= 1e-10 * Math.max(1, Math.abs(crossing))) tied[size++] = axis;
            if (size > 1) for (int mask = 1; mask < (1 << size) - 1; mask++) {
                int x = cell[0], y = cell[1], z = cell[2];
                for (int bit = 0; bit < size; bit++)
                    if ((mask & (1 << bit)) != 0) {
                        int axis = tied[bit];
                        if (axis == 0) x += step[axis];
                        else if (axis == 1) y += step[axis];
                        else z += step[axis];
                    }
                visit(x, y, z, ray, end);
            }
            for (int i = 0; i < size; i++) {
                int axis = tied[i];
                cell[axis] += step[axis];
                next[axis] += delta[axis];
            }
            entered = crossing;
        }
        hits.sort(Comparator.comparingDouble(Hit::t).thenComparingInt(h -> h.instance.id())
                .thenComparingInt(Hit::modelIndex).thenComparingInt(h -> h.quad.layer()));
        return List.copyOf(hits);
    }

    private void visit(int x, int y, int z, Ray ray, double maxDistance) {
        if (++cells > limits.maxCells()) throw limit("体素访问超过限额");
        index.enter(x,y,z);
        while(index.hasNext()) {SceneSnapshot.Instance instance=index.next();
            if (visited[instance.id()] == epoch) continue;
            visited[instance.id()] = epoch;
            for (int m = 0; m < instance.models().size(); m++) {
                BakedModel model = instance.models().get(m);
                boolean inside=model.containsInterior(ray.origin().subtract(new Vec3(instance.x(),instance.y(),instance.z())));
                for (int q = 0; q < model.quads().size(); q++) {
                    if (++tests > limits.maxTests()) throw limit("模型求交超过限额");
                    BakedQuad quad = model.quads().get(q);
                    if(!inside && quad.normal().dot(ray.direction())>=-1e-12)continue;
                    if(scene.shouldCull(instance,quad))continue;
                    double[] result = intersection(ray, instance, quad, maxDistance);
                    if (result == null) continue;
                    double t = result[0], s = result[1], v = result[2];
                    boolean duplicate=false;
                    if(quad.topology()==BakedQuad.Topology.TRIANGLE)for(Hit prior:hits) {
                        if(prior.instance.id()==instance.id() && prior.modelIndex==m
                                && prior.quad.surfaceGroup()==quad.surfaceGroup() && Math.abs(prior.t-t)<1e-9) {duplicate=true;break;}
                    }
                    if(duplicate)continue;
                    if (hits.size() >= limits.maxIntersections()) throw limit("射线交点超过限额");
                    BakedQuad.Uv p = quad.uv().get(0), a = quad.uv().get(1), b = quad.uv().get(3);
                    hits.add(new Hit(instance, m, q, quad, t, p.u() + s * (a.u() - p.u()) + v * (b.u() - p.u()),
                            p.v() + s * (a.v() - p.v()) + v * (b.v() - p.v())));
                    if (quad.opacity()==1 && !quad.forceTranslucent() && scene.texture(quad.texture()).opaque())
                        nearestOpaque = Math.min(nearestOpaque, t);
                }
            }
        }
    }

    /**
     * 模型元素经仿射变换后仍是平行四边形，无拆三角形的共享边二次计色。
     */
    public static double[] intersection(Ray ray, SceneSnapshot.Instance instance, BakedQuad quad, double maxDistance) {
        Vec3 p = quad.positions().get(0), a = quad.positions().get(1), b = quad.positions().get(3), n = quad.normal();
        double ox = ray.origin().x() - instance.x(), oy = ray.origin().y() - instance.y(), oz = ray.origin().z() - instance.z();
        Vec3 d = ray.direction();
        double denom = n.x() * d.x() + n.y() * d.y() + n.z() * d.z();
        if (Math.abs(denom) < 1e-12) return null;
        double t = (n.x() * (p.x() - ox) + n.y() * (p.y() - oy) + n.z() * (p.z() - oz)) / denom;
        if (t < -1e-9 || t > maxDistance + 1e-9) return null;
        t = Math.max(0, t);
        double px = ox + t * d.x() - p.x(), py = oy + t * d.y() - p.y(), pz = oz + t * d.z() - p.z();
        double ax = a.x() - p.x(), ay = a.y() - p.y(), az = a.z() - p.z();
        double bx = b.x() - p.x(), by = b.y() - p.y(), bz = b.z() - p.z();
        double aa = ax * ax + ay * ay + az * az, bb = bx * bx + by * by + bz * bz, ab = ax * bx + ay * by + az * bz;
        double pa = px * ax + py * ay + pz * az, pb = px * bx + py * by + pz * bz, det = aa * bb - ab * ab;
        if (Math.abs(det) < 1e-20) return null;
        double u = (pa * bb - pb * ab) / det, v = (pb * aa - pa * ab) / det;
        if (u < -1e-9 || u > 1 + 1e-9 || v < -1e-9 || v > 1 + 1e-9) return null;
        if(quad.topology()==BakedQuad.Topology.TRIANGLE && u+v>1+1e-9)return null;
        return new double[]{t, Math.max(0, Math.min(1, u)), Math.max(0, Math.min(1, v))};
    }

    private static SceneException limit(String detail) {
        return new SceneException(SceneException.Code.GEOMETRY_LIMIT, detail);
    }
}
