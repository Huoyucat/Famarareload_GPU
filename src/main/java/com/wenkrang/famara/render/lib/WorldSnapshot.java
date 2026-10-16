package com.wenkrang.famara.render.lib;

import com.wenkrang.famara.Famara;
import com.wenkrang.famara.compat.CeCompat;
import org.bukkit.Bukkit;
import com.wenkrang.famara.lib.Sched;
import com.wenkrang.famara.compat.RuntimeCompat;
import org.bukkit.Chunk;
import org.bukkit.ChunkSnapshot;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Entity;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 拍照范围内的区块快照
 * <p>
 * Folia 中每个区块只能由其所属的区域线程访问，因此拍照时把每个区块的抓取任务
 * 分派到对应的区域线程,抓取完成后渲染线程只读取快照,不再访问世界。
 * 未加载的区块会以”仅已生成”的方式异步加载后再抓取,与原先同步光线追踪的效果保持一致。
 * <p>
 * CE 家具和 CE 物品的形状和颜色也在抓取时记录。
 */
public class WorldSnapshot {
    /** 区块快照，键为 {@link #key(int, int)}，未生成的区块不在其中 */
    private final ConcurrentHashMap<Long, ChunkSnapshot> chunks = new ConcurrentHashMap<>();
    /** 拍照范围内的实体形状快照 */
    private final List<EntityShape> collectedShapes = new ArrayList<>();
    private volatile List<EntityShape> entities = List.of();
    private final Set<java.util.UUID> furnitureSeen = ConcurrentHashMap.newKeySet();
    public final CeCompat.Session colors = CeCompat.session();
    private final AtomicInteger pending = new AtomicInteger();
    private volatile boolean ready = false;
    private volatile long startTime;

    public final int minHeight;
    public final int maxHeight;
    private final boolean hasSkyLight;
    private final int skyDarken;


    public WorldSnapshot(int minHeight, int maxHeight, boolean hasSkyLight, int skyDarken) {
        this.minHeight = minHeight;
        this.maxHeight = maxHeight;
        this.hasSkyLight = hasSkyLight;
        this.skyDarken = skyDarken;
    }

    public static long key(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
    }

    /**
     * 开始抓取指定区块的快照,可在任意线程调用
     *
     * @param world 世界
     * @param chunkKeys 需要抓取的区块
     * @param centerX 拍照中心 X
     * @param centerY 拍照中心 Y
     * @param centerZ 拍照中心 Z
     * @param range 抓取半径（格）
     */
    public void capture(World world, Set<Long> chunkKeys, double centerX, double centerY, double centerZ, double range) {
        startTime = System.currentTimeMillis();
        if (chunkKeys.isEmpty()) {
            ready = true;
            return;
        }
        pending.set(chunkKeys.size());
        for (long k : chunkKeys) {
            int cx = (int) (k >> 32);
            int cz = (int) k;
            Sched.region(world, cx, cz, () -> {
                try {
                    if (world.isChunkLoaded(cx, cz)) {
                        storeChunk(k, world.getChunkAt(cx, cz), centerX, centerY, centerZ, range);
                    } else {
                        // 只加载已生成的区块，不生成新地形
                        world.getChunkAtAsync(cx, cz, false).whenComplete((chunk, error) -> {
                            if (chunk == null || error != null) {
                                done();
                                return;
                            }
                            // 加载回调通常已在区块所属的区域线程上，此时直接抓取，避免临时 ticket 过期导致区块被卸载
                            if (Sched.owns(world, cx, cz)) {
                                try {
                                    storeChunk(k, chunk, centerX, centerY, centerZ, range);
                                } catch (Exception ignored) {
                                } finally {
                                    done();
                                }
                                return;
                            }
                            // 否则回到区块所属的区域线程再抓取快照
                            Sched.region(world, cx, cz, () -> {
                                try {
                                    if (world.isChunkLoaded(cx, cz)) storeChunk(k, chunk, centerX, centerY, centerZ, range);
                                } catch (Exception ignored) {
                                } finally {
                                    done();
                                }
                            });
                        });
                        return;
                    }
                } catch (Exception ignored) {
                }
                done();
            });
        }
    }

    private void storeChunk(long k, Chunk chunk, double centerX, double centerY, double centerZ, double range) {
        if (ready) return;
        ChunkSnapshot snapshot = RuntimeCompat.snapshot(chunk);
        List<EntityShape> shapes = new ArrayList<>();
        if (CeCompat.enabled()) for (Entity entity : chunk.getEntities()) {
            org.bukkit.Location location = entity.getLocation();
            double dx = location.getX() - centerX, dy = location.getY() - centerY, dz = location.getZ() - centerZ;
            if (dx * dx + dy * dy + dz * dz > range * range) continue;
            try {
                EntityShape shape = CeCompat.capture(entity, colors, furnitureSeen);
                if (shape != null) shapes.add(shape);
            } catch (RuntimeException e) {
                java.util.logging.Logger.getLogger("Famara").warning("CE 内容采集失败：" + entity.getUniqueId() + "：" + e.getMessage());
            }
        }
        // 超时或完成后不再接受迟到快照，避免渲染期间内容改变。
        synchronized (this) {
            if (ready) return;
            chunks.put(k, snapshot);
            collectedShapes.addAll(shapes);
        }
    }

    private synchronized void seal() {
        if (!ready) {
            entities = List.copyOf(collectedShapes);
            collectedShapes.clear();
            ready = true;
        }
    }

    private void done() {
        if (pending.decrementAndGet() <= 0) seal();
    }

    /** 渲染完成后释放快照；迟到回调不能重新写入。 */
    public synchronized void clear() {
        ready = true;
        chunks.clear();
        collectedShapes.clear();
        entities = List.of();
        furnitureSeen.clear();
    }

    public Collection<EntityShape> entities() { return entities; }

    /** 超过 30 秒按已采集的快照继续，不再接受后续写入。 */
    public boolean isReady() {
        if (!ready && System.currentTimeMillis() - startTime > 30_000) seal();
        return ready;
    }

    private ChunkSnapshot chunkAt(int x, int z) {
        return chunks.get(key(x >> 4, z >> 4));
    }

    /**
     * 获取方块类型，超出世界高度或区块未生成时返回空气
     */
    public Material getType(int x, int y, int z) {
        if (y < minHeight || y >= maxHeight) return Material.AIR;
        ChunkSnapshot snapshot = chunkAt(x, z);
        if (snapshot == null) return Material.AIR;
        return snapshot.getBlockType(x & 15, y, z & 15);
    }

    /**
     * 获取方块数据，超出世界高度或区块未生成时返回null
     */
    public BlockData getData(int x, int y, int z) {
        if (y < minHeight || y >= maxHeight) return null;
        ChunkSnapshot snapshot = chunkAt(x, z);
        if (snapshot == null) return null;
        return snapshot.getBlockData(x & 15, y, z & 15);
    }

    /**
     * 获取方块的综合亮度，等价于 {@link org.bukkit.block.Block#getLightLevel()}：
     * max(天空光照 - 天空变暗程度, 方块光照)
     */
    public int getLightLevel(int x, int y, int z) {
        int skyMax = hasSkyLight ? Math.max(0, 15 - skyDarken) : 0;
        if (y >= maxHeight) return skyMax;
        if (y < minHeight) return 0;
        ChunkSnapshot snapshot = chunkAt(x, z);
        if (snapshot == null) return skyMax;
        int sky = hasSkyLight ? Math.max(0, snapshot.getBlockSkyLight(x & 15, y, z & 15) - skyDarken) : 0;
        int block = snapshot.getBlockEmittedLight(x & 15, y, z & 15);
        return Math.max(sky, block);
    }
}
