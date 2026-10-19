package com.wenkrang.famara.render.asset;

import com.wenkrang.famara.render.math.Vec3;
import java.util.*;

/** 模型共享且不可变；包围范围只计算一次，避免每条射线重复遍历所有顶点。 */
public final class BakedModel {
    private final ResourceKey key;
    private final List<BakedQuad> quads;
    private final boolean ambientOcclusion;
    private final Vec3 min,max;
    public BakedModel(ResourceKey key,List<BakedQuad> quads,boolean ambientOcclusion) {
        this.key=Objects.requireNonNull(key);this.quads=List.copyOf(quads);this.ambientOcclusion=ambientOcclusion;
        double[] lo={Double.POSITIVE_INFINITY,Double.POSITIVE_INFINITY,Double.POSITIVE_INFINITY};
        double[] hi={Double.NEGATIVE_INFINITY,Double.NEGATIVE_INFINITY,Double.NEGATIVE_INFINITY};
        for(BakedQuad q:this.quads)for(Vec3 p:q.positions())for(int axis=0;axis<3;axis++) {
            lo[axis]=Math.min(lo[axis],p.component(axis));hi[axis]=Math.max(hi[axis],p.component(axis));
        }
        if(this.quads.isEmpty()){Arrays.fill(lo,0);Arrays.fill(hi,0);}
        min=new Vec3(lo[0],lo[1],lo[2]);max=new Vec3(hi[0],hi[1],hi[2]);
    }
    public ResourceKey key(){return key;}
    public List<BakedQuad> quads(){return quads;}
    public boolean ambientOcclusion(){return ambientOcclusion;}
    public Vec3 min(){return min;}
    public Vec3 max(){return max;}
    public boolean containsInterior(Vec3 point) {
        return point.x()>min.x()+1e-9 && point.x()<max.x()-1e-9 && point.y()>min.y()+1e-9 && point.y()<max.y()-1e-9
                && point.z()>min.z()+1e-9 && point.z()<max.z()-1e-9;
    }
    public BakedModel variant(int x,int y,boolean uvLock) {
        return new BakedModel(key,quads.stream().map(q->q.variant(x,y,uvLock)).toList(),ambientOcclusion);
    }
    @Override public boolean equals(Object value) {
        return value==this || value instanceof BakedModel b && key.equals(b.key) && ambientOcclusion==b.ambientOcclusion && quads.equals(b.quads);
    }
    @Override public int hashCode(){return Objects.hash(key,quads,ambientOcclusion);}
    @Override public String toString(){return key+"[faces="+quads.size()+"]";}
}
