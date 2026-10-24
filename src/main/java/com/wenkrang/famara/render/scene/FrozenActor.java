package com.wenkrang.famara.render.scene;

import com.wenkrang.famara.render.math.Vec3;
import java.util.*;

/** Pure entity-owner snapshot: never retains Bukkit entities, profiles, inventories or worlds. */
public record FrozenActor(UUID id,String type,Vec3 position,double bodyYaw,double headYaw,double pitch,
                          String pose,boolean baby,double scale,double limbSwing,String variant,int tintRgb,
                          String skinUrl,boolean slim,boolean sheared,int sky,int block,ActorAppearance appearance,FrozenEquipment equipment) {
    public FrozenActor(UUID id,String type,Vec3 position,double bodyYaw,double headYaw,double pitch,String pose,boolean baby,double scale,double limbSwing,String variant,int tintRgb,String skinUrl,boolean slim,boolean sheared,int sky,int block){this(id,type,position,bodyYaw,headYaw,pitch,pose,baby,scale,limbSwing,variant,tintRgb,skinUrl,slim,sheared,sky,block,ActorAppearance.EMPTY,FrozenEquipment.EMPTY);}
    public FrozenActor {
        Objects.requireNonNull(appearance);Objects.requireNonNull(equipment);Objects.requireNonNull(id);Objects.requireNonNull(type);Objects.requireNonNull(position);Objects.requireNonNull(pose);
        variant=variant==null?"":variant;skinUrl=skinUrl==null?"":skinUrl;
        if(type.length()>64 || pose.length()>32 || variant.length()>64 || skinUrl.length()>512
                || !Double.isFinite(bodyYaw) || !Double.isFinite(headYaw) || !Double.isFinite(pitch)
                || !Double.isFinite(scale) || scale<=0 || scale>16 || !Double.isFinite(limbSwing)
                || (tintRgb&0xff000000)!=0 || sky<0 || sky>15 || block<0 || block>15)
            throw new IllegalArgumentException("Invalid frozen actor");
    }
}
