package com.wenkrang.famara.render.stage;

import com.wenkrang.famara.compat.CeCompat;
import com.wenkrang.famara.lib.ColorManager;
import com.wenkrang.famara.render.RenderContext;
import com.wenkrang.famara.render.RenderTemp;
import com.wenkrang.famara.render.lib.EntityShape;
import com.wenkrang.famara.render.lib.WorldSnapshot;
import org.bukkit.Material;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.Slab;
import org.bukkit.block.data.type.Snow;
import org.bukkit.block.data.type.TrapDoor;

import java.util.HashSet;
import java.util.Set;


/**
 * 射线追踪部分
 * <p>
 * 在区块快照上进行体素步进（DDA），不访问世界，因此可以在异步线程运行。
 * 大部分方块按整格处理，台阶、雪层、地毯、活板门等常见非整格方块按其实际高度处理。
 */
public class RayTraceStage implements RenderStage{
    /**
     * 判断是否为可被光线击中的方块
     * @param type 方块类型
     * @return 是否为方块
     */
    public static boolean isBlock(Material type) {
        return type != null && type.isBlock() && !type.isAir()
                && type != Material.LIGHT && type != Material.MOVING_PISTON;
    }

    /**
     * 获取颜色（方块、CE 家具或物品）
     * @param rt 渲染临时变量
     * @param world 世界快照
     */
    public static void getBlockColor(RenderTemp rt, WorldSnapshot world) {
        if (!rt.hit) return;

        // 击中 CE 家具或物品
        if (rt.hitEntity != null) {
            if (rt.hitEntity.color() != null) {
                rt.color.set(rt.hitEntity.color());
            } else {
                // CE 对象没有颜色，用洋红提示
                rt.color.set(255, 0, 255);
                if (!rt.failedBlocks.contains(rt.hitEntity.id())) {
                    rt.failedBlocks.add(rt.hitEntity.id());
                }
            }
            return;
        }

        // 击中方块，先尝试 CE 自定义方块
        if (isBlock(rt.hitType)) {
            BlockData data = world.getData(rt.hitX, rt.hitY, rt.hitZ);
            CeCompat.BlockColor ce = world.colors.block(data);
            int ceColor = ce.rgb();
            if (ceColor != CeCompat.NOT_CUSTOM) {
                if (ceColor == CeCompat.NO_COLOR) {
                    rt.color.set(255, 0, 255);
                    String id = ce.id();
                    if (id != null && !rt.failedBlocks.contains(id)) rt.failedBlocks.add(id);
                } else {
                    rt.color.set(CeCompat.rgbToColor(ceColor));
                }
                return;
            }
            // 普通方块
            ColorManager.getBlockColor(data, rt, world.colors.palette);
        }
    }

    /**
     * 计算一张照片的所有光线会经过的区块
     * 只在水平面上做区块级的步进，开销远小于完整的光线追踪
     *
     * @param ctx 渲染上下文
     * @return 区块键集合，见 {@link WorldSnapshot#key(int, int)}
     */
    public static Set<Long> collectChunks(RenderContext ctx) {
        Set<Long> result = new HashSet<>();
        for (int y = 0; y < 128; y++) {
            double angleY = ctx.pitch - (y - 64) * ctx.fieldOfView;
            double cos = Math.cos(angleY);
            double horizontal = Math.abs(cos) * ctx.maxDistance;
            for (int x = 0; x < 128; x++) {
                double angleX = ctx.yaw + (x - 64) * ctx.fieldOfView;
                double dx = Math.cos(angleX) * cos;
                double dz = Math.sin(angleX) * cos;
                walkChunks(ctx.eyeX, ctx.eyeZ, dx, dz, horizontal, result);
            }
        }
        return result;
    }

    /**
     * 在水平区块网格上步进一条光线，记录经过的区块
     */
    private static void walkChunks(double ox, double oz, double dx, double dz, double maxDist, Set<Long> out) {
        int cx = Math.floorDiv((int) Math.floor(ox), 16);
        int cz = Math.floorDiv((int) Math.floor(oz), 16);
        out.add(WorldSnapshot.key(cx, cz));

        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 1e-9) return;
        dx /= len;
        dz /= len;

        int stepX = dx > 0 ? 1 : -1;
        int stepZ = dz > 0 ? 1 : -1;
        double tDeltaX = dx == 0 ? Double.POSITIVE_INFINITY : 16 / Math.abs(dx);
        double tDeltaZ = dz == 0 ? Double.POSITIVE_INFINITY : 16 / Math.abs(dz);
        double nextX = (stepX > 0 ? (cx + 1) * 16.0 : cx * 16.0);
        double nextZ = (stepZ > 0 ? (cz + 1) * 16.0 : cz * 16.0);
        double tMaxX = dx == 0 ? Double.POSITIVE_INFINITY : (nextX - ox) / dx;
        double tMaxZ = dz == 0 ? Double.POSITIVE_INFINITY : (nextZ - oz) / dz;

        while (true) {
            if (tMaxX < tMaxZ) {
                if (tMaxX > maxDist) return;
                cx += stepX;
                tMaxX += tDeltaX;
            } else {
                if (tMaxZ > maxDist) return;
                cz += stepZ;
                tMaxZ += tDeltaZ;
            }
            out.add(WorldSnapshot.key(cx, cz));
        }
    }

    /**
     * 在快照上追踪一条光线，结果写入 rt
     */
    public static void trace(RenderContext ctx, RenderTemp rt) {
        rt.hit = false;
        rt.hitType = null;
        rt.hitFace = null;
        rt.hitEntity = null;

        WorldSnapshot world = ctx.snapshot;
        double ox = ctx.eyeX, oy = ctx.eyeY, oz = ctx.eyeZ;
        double dx = rt.direction.getX(), dy = rt.direction.getY(), dz = rt.direction.getZ();

        // 先检测实体，找到最近的命中点
        double closestT = ctx.maxDistance;
        EntityShape closestEntity = null;
        BlockFace closestEntityFace = null;

        for (EntityShape entity : world.entities()) {
            for (double[] box : entity.boxes()) {
                RayHit hit = rayBoxIntersection(ox, oy, oz, dx, dy, dz, box[0], box[1], box[2], box[3], box[4], box[5]);
                if (hit != null && hit.t < closestT) {
                    closestT = hit.t;
                    closestEntity = entity;
                    closestEntityFace = hit.face;
                }
            }
        }

        int x = (int) Math.floor(ox);
        int y = (int) Math.floor(oy);
        int z = (int) Math.floor(oz);

        int stepX = dx > 0 ? 1 : (dx < 0 ? -1 : 0);
        int stepY = dy > 0 ? 1 : (dy < 0 ? -1 : 0);
        int stepZ = dz > 0 ? 1 : (dz < 0 ? -1 : 0);
        double tDeltaX = stepX == 0 ? Double.POSITIVE_INFINITY : 1 / Math.abs(dx);
        double tDeltaY = stepY == 0 ? Double.POSITIVE_INFINITY : 1 / Math.abs(dy);
        double tDeltaZ = stepZ == 0 ? Double.POSITIVE_INFINITY : 1 / Math.abs(dz);
        double tMaxX = stepX == 0 ? Double.POSITIVE_INFINITY : ((stepX > 0 ? x + 1 : x) - ox) / dx;
        double tMaxY = stepY == 0 ? Double.POSITIVE_INFINITY : ((stepY > 0 ? y + 1 : y) - oy) / dy;
        double tMaxZ = stepZ == 0 ? Double.POSITIVE_INFINITY : ((stepZ > 0 ? z + 1 : z) - oz) / dz;

        // 光线起点所在方块的进入面，取与主方向相反的面
        BlockFace face = dominantFace(dx, dy, dz);
        double t = 0;

        while (t <= closestT) {
            // 已离开世界高度范围且不会再回来
            if ((y >= world.maxHeight && stepY >= 0) || (y < world.minHeight && stepY <= 0)) break;

            Material type = world.getType(x, y, z);
            if (isBlock(type) && testShape(world, type, x, y, z, ox, oy, oz, dx, dy, dz, t, face, closestT, rt)) {
                rt.hit = true;
                rt.hitType = type;
                rt.hitX = x;
                rt.hitY = y;
                rt.hitZ = z;
                return;
            }

            if (tMaxX < tMaxY && tMaxX < tMaxZ) {
                x += stepX;
                t = tMaxX;
                tMaxX += tDeltaX;
                face = stepX > 0 ? BlockFace.WEST : BlockFace.EAST;
            } else if (tMaxY < tMaxZ) {
                y += stepY;
                t = tMaxY;
                tMaxY += tDeltaY;
                face = stepY > 0 ? BlockFace.DOWN : BlockFace.UP;
            } else {
                z += stepZ;
                t = tMaxZ;
                tMaxZ += tDeltaZ;
                face = stepZ > 0 ? BlockFace.NORTH : BlockFace.SOUTH;
            }
        }

        // 方块都没打中，但实体打中了
        if (closestEntity != null) {
            rt.hit = true;
            rt.hitEntity = closestEntity;
            rt.hitFace = closestEntityFace;
        }
    }

    private record RayHit(double t, BlockFace face) {
    }

    /**
     * 光线与轴对齐包围盒求交（慢板法）
     *
     * @return 命中记录（t 和进入面），未命中返回 null
     */
    private static RayHit rayBoxIntersection(double ox, double oy, double oz, double dx, double dy, double dz,
                                             double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        double tNear = Double.NEGATIVE_INFINITY, tFar = Double.POSITIVE_INFINITY;
        BlockFace nearFace = BlockFace.NORTH;

        double[] o = {ox, oy, oz};
        double[] d = {dx, dy, dz};
        double[] mn = {minX, minY, minZ};
        double[] mx = {maxX, maxY, maxZ};
        BlockFace[][] faces = {
                {BlockFace.WEST, BlockFace.EAST},
                {BlockFace.DOWN, BlockFace.UP},
                {BlockFace.NORTH, BlockFace.SOUTH}
        };

        for (int i = 0; i < 3; i++) {
            if (Math.abs(d[i]) < 1e-12) {
                if (o[i] < mn[i] || o[i] > mx[i]) return null;
                continue;
            }
            double t1 = (mn[i] - o[i]) / d[i];
            double t2 = (mx[i] - o[i]) / d[i];
            BlockFace f = d[i] > 0 ? faces[i][0] : faces[i][1];
            if (t1 > t2) {
                double tmp = t1;
                t1 = t2;
                t2 = tmp;
            }
            if (t1 > tNear) {
                tNear = t1;
                nearFace = f;
            }
            if (t2 < tFar) tFar = t2;
            if (tNear > tFar || tFar < 0) return null;
        }
        if (tNear < 0) tNear = 0;
        return new RayHit(tNear, nearFace);
    }

    private static BlockFace dominantFace(double dx, double dy, double dz) {
        double ax = Math.abs(dx), ay = Math.abs(dy), az = Math.abs(dz);
        if (ax >= ay && ax >= az) return dx > 0 ? BlockFace.WEST : BlockFace.EAST;
        if (ay >= az) return dy > 0 ? BlockFace.DOWN : BlockFace.UP;
        return dz > 0 ? BlockFace.NORTH : BlockFace.SOUTH;
    }

    /**
     * 检测光线是否击中方块的实际形状，击中时写入 rt.hitFace
     * 整格方块直接使用进入面；非整格方块与其包围盒求交
     */
    private static boolean testShape(WorldSnapshot world, Material type, int x, int y, int z,
                                     double ox, double oy, double oz, double dx, double dy, double dz,
                                     double tEnter, BlockFace enterFace, double maxDistance, RenderTemp rt) {
        double[] box = shapeOf(world, type, x, y, z);
        if (box == null) {
            rt.hitFace = enterFace;
            return true;
        }
        // 慢板法（slab method）求光线与包围盒的交点
        double minX = x + box[0], minY = y + box[1], minZ = z + box[2];
        double maxX = x + box[3], maxY = y + box[4], maxZ = z + box[5];
        double tNear = Double.NEGATIVE_INFINITY, tFar = Double.POSITIVE_INFINITY;
        BlockFace nearFace = enterFace;

        double[] o = {ox, oy, oz};
        double[] d = {dx, dy, dz};
        double[] mn = {minX, minY, minZ};
        double[] mx = {maxX, maxY, maxZ};
        BlockFace[][] faces = {
                {BlockFace.WEST, BlockFace.EAST},
                {BlockFace.DOWN, BlockFace.UP},
                {BlockFace.NORTH, BlockFace.SOUTH}
        };
        for (int i = 0; i < 3; i++) {
            if (Math.abs(d[i]) < 1e-12) {
                if (o[i] < mn[i] || o[i] > mx[i]) return false;
                continue;
            }
            double t1 = (mn[i] - o[i]) / d[i];
            double t2 = (mx[i] - o[i]) / d[i];
            BlockFace f = d[i] > 0 ? faces[i][0] : faces[i][1];
            if (t1 > t2) {
                double tmp = t1;
                t1 = t2;
                t2 = tmp;
            }
            if (t1 > tNear) {
                tNear = t1;
                nearFace = f;
            }
            if (t2 < tFar) tFar = t2;
            if (tNear > tFar || tFar < 0) return false;
        }
        if (tNear > maxDistance) return false;
        rt.hitFace = tNear < tEnter - 1e-9 ? enterFace : nearFace;
        return true;
    }

    /**
     * 获取非整格方块的包围盒（方块内的相对坐标 minX,minY,minZ,maxX,maxY,maxZ）
     *
     * @return 包围盒，整格方块返回null
     */
    private static double[] shapeOf(WorldSnapshot world, Material type, int x, int y, int z) {
        String name = type.name();
        if (name.endsWith("_CARPET") || type == Material.MOSS_CARPET) return new double[]{0, 0, 0, 1, 1 / 16.0, 1};
        if (name.endsWith("_PRESSURE_PLATE")) return new double[]{1 / 16.0, 0, 1 / 16.0, 15 / 16.0, 1 / 16.0, 15 / 16.0};
        if (type == Material.DIRT_PATH || type == Material.FARMLAND) return new double[]{0, 0, 0, 1, 15 / 16.0, 1};
        if (type == Material.LILY_PAD) return new double[]{1 / 16.0, 0, 1 / 16.0, 15 / 16.0, 1.5 / 16.0, 15 / 16.0};
        if (type == Material.DAYLIGHT_DETECTOR) return new double[]{0, 0, 0, 1, 6 / 16.0, 1};
        if (type == Material.REPEATER || type == Material.COMPARATOR) return new double[]{0, 0, 0, 1, 2 / 16.0, 1};
        if (type == Material.STONECUTTER) return new double[]{0, 0, 0, 1, 9 / 16.0, 1};
        if (type == Material.ENCHANTING_TABLE) return new double[]{0, 0, 0, 1, 12 / 16.0, 1};
        if (name.endsWith("_BED")) return new double[]{0, 0, 0, 1, 9 / 16.0, 1};

        boolean slab = name.endsWith("_SLAB");
        boolean snow = type == Material.SNOW;
        boolean trapdoor = name.endsWith("_TRAPDOOR");
        if (!slab && !snow && !trapdoor) return null;

        BlockData data = world.getData(x, y, z);
        if (data instanceof Slab s) {
            return switch (s.getType()) {
                case BOTTOM -> new double[]{0, 0, 0, 1, 0.5, 1};
                case TOP -> new double[]{0, 0.5, 0, 1, 1, 1};
                case DOUBLE -> null;
            };
        }
        if (data instanceof Snow s) {
            // 雪层的外形高度为 层数 * 2 像素
            return new double[]{0, 0, 0, 1, s.getLayers() * 2 / 16.0, 1};
        }
        if (data instanceof TrapDoor tr) {
            double th = 3 / 16.0;
            if (!tr.isOpen()) {
                return tr.getHalf() == Bisected.Half.TOP
                        ? new double[]{0, 1 - th, 0, 1, 1, 1}
                        : new double[]{0, 0, 0, 1, th, 1};
            }
            return switch (tr.getFacing()) {
                case NORTH -> new double[]{0, 0, 1 - th, 1, 1, 1};
                case SOUTH -> new double[]{0, 0, 0, 1, 1, th};
                case WEST -> new double[]{1 - th, 0, 0, 1, 1, 1};
                default -> new double[]{0, 0, 0, th, 1, 1};
            };
        }
        return null;
    }

    @Override
    public void render(RenderContext ctx, RenderTemp rt) {
        // 初始化渲染向量
        rt.cos = Math.cos(ctx.pitch - (rt.y - 64) * ctx.fieldOfView);
        rt.direction.setX(Math.cos(ctx.yaw + (rt.x - 64) * ctx.fieldOfView) * rt.cos);
        rt.direction.setY(Math.sin(ctx.pitch - (rt.y - 64) * ctx.fieldOfView));
        rt.direction.setZ(Math.sin(ctx.yaw + (rt.x - 64) * ctx.fieldOfView) * rt.cos);

        // 光线追踪
        trace(ctx, rt);

        if (rt.hit) {
            // 获取颜色
            getBlockColor(rt, ctx.snapshot);
        }
    }
}
