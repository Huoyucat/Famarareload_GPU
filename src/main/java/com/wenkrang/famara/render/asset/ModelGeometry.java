package com.wenkrang.famara.render.asset;

import com.wenkrang.famara.render.math.*;
import java.util.*;

/** 内置静态部件使用可见面与实体贴图展开，不使用碰撞盒取代表色。坐标单位为1/16格。 */
public final class ModelGeometry {
    private ModelGeometry() {}
    public static void entityBox(List<BakedQuad> out,ResourceKey texture,double x,double y,double z,
                                 double w,double h,double d,double u,double v,int sheetW,int sheetH,
                                 Set<Direction> omitted,int color) {
        entityBox(out,texture,x,y,z,w,h,d,u,v,sheetW,sheetH,omitted,color,new Vec3(0,0,0));
    }
    static void entityBox(List<BakedQuad> out,ResourceKey texture,double x,double y,double z,
                          double w,double h,double d,double u,double v,int sheetW,int sheetH,
                          Set<Direction> omitted,int color,Vec3 inflation) {
        for(Direction face:Direction.values()) {
            if(omitted.contains(face))continue;
            double[] rect=switch(face) {
                case DOWN->new double[]{u+d,v,u+d+w,v+d};
                case UP->new double[]{u+d+w,v+d,u+d+2*w,v};
                case WEST->new double[]{u,v+d,u+d,v+d+h};
                case NORTH->new double[]{u+d,v+d,u+d+w,v+d+h};
                case EAST->new double[]{u+d+w,v+d,u+2*d+w,v+d+h};
                case SOUTH->new double[]{u+2*d+w,v+d,u+2*d+2*w,v+d+h};
            };
            if(face!=Direction.UP && face!=Direction.DOWN){double swap=rect[1];rect[1]=rect[3];rect[3]=swap;}
            face(out,texture,face,points(face,x-inflation.x(),y-inflation.y(),z-inflation.z(),
                    x+w+inflation.x(),y+h+inflation.y(),z+d+inflation.z()),rect,sheetW,sheetH,color,0);
        }
    }
    public static void face(List<BakedQuad> out,ResourceKey texture,Direction face,List<Vec3> points,
                            double[] rect,int sheetW,int sheetH,int color,int emission) {
        List<BakedQuad.Uv> uv=List.of(new BakedQuad.Uv(rect[0]/sheetW,rect[1]/sheetH),new BakedQuad.Uv(rect[2]/sheetW,rect[1]/sheetH),
                new BakedQuad.Uv(rect[2]/sheetW,rect[3]/sheetH),new BakedQuad.Uv(rect[0]/sheetW,rect[3]/sheetH));
        Vec3 normal=points.get(3).subtract(points.get(0)).cross(points.get(1).subtract(points.get(0)));
        if(normal.dot(normal)<1e-24)return;
        int layer=out.size();
        out.add(new BakedQuad(points,uv,texture,normal.normalized(),face,null,-1,true,false,null,emission,layer,
                BakedQuad.Topology.QUAD,layer,color,1));
    }
    public static List<Vec3> points(Direction face,double x,double y,double z,double X,double Y,double Z) {
        List<Vec3> raw=switch(face) {
            case NORTH->List.of(new Vec3(X,Y,z),new Vec3(x,Y,z),new Vec3(x,y,z),new Vec3(X,y,z));
            case SOUTH->List.of(new Vec3(x,Y,Z),new Vec3(X,Y,Z),new Vec3(X,y,Z),new Vec3(x,y,Z));
            case WEST->List.of(new Vec3(x,Y,z),new Vec3(x,Y,Z),new Vec3(x,y,Z),new Vec3(x,y,z));
            case EAST->List.of(new Vec3(X,Y,Z),new Vec3(X,Y,z),new Vec3(X,y,z),new Vec3(X,y,Z));
            case UP->List.of(new Vec3(x,Y,z),new Vec3(X,Y,z),new Vec3(X,Y,Z),new Vec3(x,Y,Z));
            case DOWN->List.of(new Vec3(x,y,Z),new Vec3(X,y,Z),new Vec3(X,y,z),new Vec3(x,y,z));
        };
        return raw.stream().map(p->p.multiply(1/16.0)).toList();
    }
    public static BakedModel rotateY(BakedModel model,double degrees) {
        if(degrees==0)return model;
        return new BakedModel(model.key(),model.quads().stream().map(q->q.rotatedY(degrees)).toList(),model.ambientOcclusion());
    }
}
