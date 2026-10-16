package com.wenkrang.famara.render;

import com.wenkrang.famara.render.lib.WorldSnapshot;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.map.MapView;

import java.awt.image.BufferedImage;
import java.io.File;

/**
 * 渲染上下文的内容
 * 必须在玩家所在的区域线程上创建，创建时会记录渲染所需的全部世界状态，
 * 之后渲染在异步线程进行，不再访问世界
 */
public class RenderContext {
    public final Location eyes;
    public final double eyeX;
    public final double eyeY;
    public final double eyeZ;
    public final double pitch;
    public final double yaw;
    public final double fieldOfView;
    public final String id;
    public final BufferedImage image;
    public final Player player;
    public final File picture;
    public final MapView map;

    /** 光线追踪的最远距离 */
    public final double maxDistance;
    /** 拍照时刻的世界状态 */
    public final World world;
    public final World.Environment environment;
    public final long time;
    public final boolean storm;
    public final boolean hasSkyLight;
    /** 天空变暗程度（0 ~ 11），用于计算方块的综合亮度 */
    public final int skyDarken;
    /** 拍照范围内的区块快照 */
    public final WorldSnapshot snapshot;

    public RenderContext(String id, BufferedImage image, Player player, File picture, MapView map, double maxDistance) {
        this.player = player;

        eyes = player.getEyeLocation();
        eyeX = eyes.getX();
        eyeY = eyes.getY();
        eyeZ = eyes.getZ();
        pitch = Math.toRadians(-eyes.getPitch());
        yaw = Math.toRadians(eyes.getYaw() + 90);
        fieldOfView = 1.0 / 128.0;

        this.id = id;
        this.image = image;
        this.picture = picture;
        this.map = map;
        this.maxDistance = maxDistance;

        world = player.getWorld();
        environment = world.getEnvironment();
        time = world.getTime();
        storm = world.hasStorm();
        hasSkyLight = com.wenkrang.famara.compat.RuntimeCompat.hasSkyLight(world);
        skyDarken = hasSkyLight ? computeSkyDarken(time, storm, world.isThundering()) : 0;
        snapshot = new WorldSnapshot(world.getMinHeight(), world.getMaxHeight(), hasSkyLight, skyDarken);
    }

    /**
     * 按原版公式计算天空变暗程度，等价于服务端 Level#getSkyDarken
     */
    private static int computeSkyDarken(long dayTime, boolean rain, boolean thunder) {
        double d = (dayTime / 24000.0 - 0.25) % 1.0;
        if (d < 0) d += 1.0;
        double timeOfDay = (d * 2.0 + (0.5 - Math.cos(d * Math.PI) / 2.0)) / 3.0;

        double rainFactor = 1.0 - (rain ? 1.0 : 0.0) * 5.0 / 16.0;
        double thunderFactor = 1.0 - (thunder ? 1.0 : 0.0) * 5.0 / 16.0;
        double sun = 0.5 + 2.0 * Math.max(-0.25, Math.min(0.25, Math.cos(timeOfDay * Math.PI * 2.0)));
        return (int) ((1.0 - sun * rainFactor * thunderFactor) * 11.0);
    }
}
