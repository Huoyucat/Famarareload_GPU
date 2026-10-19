package com.wenkrang.famara.render.scene;

import com.wenkrang.famara.render.math.Vec3;
import java.util.*;

/** 明确标记的旧家具/展示物品近似，不用于CE方块纹理路径。 */
public record LegacyEntity(String id,List<Box> boxes,int rgb) {
    public record Box(Vec3 min,Vec3 max) {
        public Box{Objects.requireNonNull(min);Objects.requireNonNull(max);if(min.x()>=max.x() || min.y()>=max.y() || min.z()>=max.z())throw new IllegalArgumentException("实体盒无效");}
        public double hit(Vec3 origin,Vec3 direction,double distance) {
            double near=Double.NEGATIVE_INFINITY,far=distance;
            for(int axis=0;axis<3;axis++) {
                double o=origin.component(axis),d=direction.component(axis);
                if(d==0){if(o<min.component(axis) || o>max.component(axis))return Double.NaN;continue;}
                double a=(min.component(axis)-o)/d,b=(max.component(axis)-o)/d;
                near=Math.max(near,Math.min(a,b));far=Math.min(far,Math.max(a,b));if(near>far)return Double.NaN;
            }
            double value=near>=0?near:far;return value>=0 && value<=distance?value:Double.NaN;
        }
    }
    public LegacyEntity{Objects.requireNonNull(id);boxes=List.copyOf(boxes);if(id.length()>256 || boxes.isEmpty() || (rgb&0xff000000)!=0)throw new IllegalArgumentException("旧实体数据无效");}
    public double hit(Vec3 origin,Vec3 direction,double distance) {
        double nearest=Double.POSITIVE_INFINITY;for(Box box:boxes){double t=box.hit(origin,direction,distance);if(Double.isFinite(t))nearest=Math.min(nearest,t);}return nearest;
    }
}
