package com.wenkrang.famara.render.platform;

import com.wenkrang.famara.compat.CeCompat;
import com.wenkrang.famara.render.math.Vec3;
import com.wenkrang.famara.render.scene.LegacyEntity;
import org.bukkit.Chunk;
import org.bukkit.entity.Entity;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** 在chunk owner上保留旧CE家具/物品盒近似，绝不用于普通CE方块。 */
public final class LegacyEntityCapture implements ChunkCaptureService.Extras,AutoCloseable {
    public record Limits(int entitiesPerChunk,int maximumBoxes) {
        public Limits{if(entitiesPerChunk<=0 || maximumBoxes<=0)throw new IllegalArgumentException("旧实体采集限额无效");}
    }
    private final Limits limits;
    private final CeCompat.Session session;
    private final Set<UUID> seen=ConcurrentHashMap.newKeySet();
    private final AtomicInteger boxes=new AtomicInteger();
    private final Vec3 origin;
    private final double range;
    public LegacyEntityCapture(long epoch,Limits limits,Vec3 origin,double range) {
        this.session=CeCompat.frozenSession(epoch);this.limits=Objects.requireNonNull(limits);this.origin=Objects.requireNonNull(origin);
        if(!Double.isFinite(range) || range<=0)throw new IllegalArgumentException("实体范围无效");this.range=range;
    }
    @Override public void close(){session.close();seen.clear();}
    @Override public List<LegacyEntity> capture(Chunk chunk) {
        Entity[] entities=chunk.getEntities();if(entities.length>limits.entitiesPerChunk)throw new IllegalStateException("区块实体数超过显式限额，未输出缺失家具的照片");
        List<LegacyEntity> result=new ArrayList<>();
        for(Entity entity:entities) {
            var location=entity.getLocation();double dx=location.getX()-origin.x(),dy=location.getY()-origin.y(),dz=location.getZ()-origin.z();
            if(dx*dx+dy*dy+dz*dz>range*range)continue;
            var shape=CeCompat.captureFrozen(entity,session,seen);if(shape==null)continue;
            if(shape.color()==null)throw new IllegalStateException("旧CE实体缺少代表色："+shape.id());
            if(boxes.addAndGet(shape.boxes().size())>limits.maximumBoxes)throw new IllegalStateException("旧CE实体盒数量超过限额");
            List<LegacyEntity.Box> copy=new ArrayList<>();
            for(double[] box:shape.boxes())copy.add(new LegacyEntity.Box(new Vec3(box[0],box[1],box[2]),new Vec3(box[3],box[4],box[5])));
            var color=shape.color();int rgb=color.getR()<<16 | color.getG()<<8 | color.getB();result.add(new LegacyEntity(shape.id(),copy,rgb));
        }
        return List.copyOf(result);
    }
}
