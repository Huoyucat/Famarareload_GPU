package com.wenkrang.famara.render.asset;

import com.wenkrang.famara.render.math.*;
import java.util.*;
import java.util.function.UnaryOperator;

/** 不可变表面。三角形使用显式拓扑，surfaceGroup用于共享边去重而不吞掉覆盖层。 */
public record BakedQuad(List<Vec3> positions,List<Uv> uv,ResourceKey texture,
                        Vec3 normal,Direction face,Direction cullFace,int tintIndex,
                        boolean shade,boolean forceTranslucent,Direction shadeDirectionOverride,
                        int lightEmission,int layer,Topology topology,int surfaceGroup,int colorMultiplier,double opacity) {
    public enum Topology { QUAD, TRIANGLE }
    public record Uv(double u,double v) {
        public Uv { if(!Double.isFinite(u) || !Double.isFinite(v))throw new IllegalArgumentException("UV必须有限"); }
    }
    public BakedQuad(List<Vec3> positions,List<Uv> uv,ResourceKey texture,Vec3 normal,Direction face,Direction cullFace,
                     int tintIndex,boolean shade,boolean forceTranslucent,Direction shadeDirectionOverride,int lightEmission,int layer) {
        this(positions,uv,texture,normal,face,cullFace,tintIndex,shade,forceTranslucent,shadeDirectionOverride,
                lightEmission,layer,Topology.QUAD,layer,0xffffff,1);
    }
    public BakedQuad {
        positions=List.copyOf(positions);uv=List.copyOf(uv);
        if(positions.size()!=4 || uv.size()!=4)throw new IllegalArgumentException("需要四个顶点/UV槽");
        Objects.requireNonNull(texture);Objects.requireNonNull(normal);Objects.requireNonNull(face);Objects.requireNonNull(topology);
        if((colorMultiplier&0xff000000)!=0 || !(opacity>=0 && opacity<=1) || lightEmission<0 || lightEmission>15)
            throw new IllegalArgumentException("材质参数不合法");
        if(topology==Topology.TRIANGLE && (!positions.get(2).equals(positions.get(3)) || !uv.get(2).equals(uv.get(3))))
            throw new IllegalArgumentException("三角形的最后一个槽必须重复第三个顶点");
    }
    public BakedQuad variant(int xDegrees,int yDegrees,boolean uvLock) {
        double rx=-Math.toRadians(xDegrees),ry=-Math.toRadians(yDegrees);
        UnaryOperator<Vec3> rotate=v->v.rotate(0,rx).rotate(1,ry);
        Direction newFace=Direction.closest(rotate.apply(face.vector()));
        List<Uv> locked=uv;
        if(uvLock) {
            Vec3 a=rotate.apply(tangentU(face)),b=rotate.apply(tangentV(face)),nu=tangentU(newFace),nv=tangentV(newFace);
            locked=uv.stream().map(t->new Uv(.5+(t.u-.5)*a.dot(nu)+(t.v-.5)*b.dot(nu),
                    .5+(t.u-.5)*a.dot(nv)+(t.v-.5)*b.dot(nv))).toList();
        }
        return transformed(rotate,locked,newFace);
    }
    public BakedQuad rotatedY(double degrees) {
        UnaryOperator<Vec3> rotate=v->v.rotate(1,-Math.toRadians(degrees));
        return transformed(rotate,uv,Direction.closest(rotate.apply(face.vector())));
    }
    private BakedQuad transformed(UnaryOperator<Vec3> rotate,List<Uv> coordinates,Direction facing) {
        Vec3 center=new Vec3(.5,.5,.5);
        List<Vec3> moved=positions.stream().map(v->rotate.apply(v.subtract(center)).add(center)).toList();
        Direction cull=cullFace==null?null:Direction.closest(rotate.apply(cullFace.vector()));
        return new BakedQuad(moved,coordinates,texture,rotate.apply(normal).normalized(),facing,cull,tintIndex,shade,
                forceTranslucent,shadeDirectionOverride,lightEmission,layer,topology,surfaceGroup,colorMultiplier,opacity);
    }
    private static Vec3 tangentU(Direction d) {
        return switch(d) { case NORTH->new Vec3(-1,0,0);case EAST->new Vec3(0,0,-1);case WEST->new Vec3(0,0,1);default->new Vec3(1,0,0); };
    }
    private static Vec3 tangentV(Direction d) {
        return switch(d) { case UP->new Vec3(0,0,1);case DOWN->new Vec3(0,0,-1);default->new Vec3(0,-1,0); };
    }
}
