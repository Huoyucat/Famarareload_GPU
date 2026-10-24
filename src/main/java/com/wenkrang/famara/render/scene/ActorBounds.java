package com.wenkrang.famara.render.scene;

import com.wenkrang.famara.render.kernel.ConservativeOcclusion;
import com.wenkrang.famara.render.math.Vec3;

/** World-space bounds, including a margin for the renderer's 1/4096 vertex grid. */
public record ActorBounds(double x0,double y0,double z0,double x1,double y1,double z1) {
    public boolean hiddenBy(ConservativeOcclusion visibility){return visibility!=null && visibility.hidden(x0-1.0/4096,y0-1.0/4096,z0-1.0/4096,x1+1.0/4096,y1+1.0/4096,z1+1.0/4096);}
    public boolean contains(Vec3 p){double e=1.0/4096;return p.x()>=x0-e && p.x()<=x1+e && p.y()>=y0-e && p.y()<=y1+e && p.z()>=z0-e && p.z()<=z1+e;}
    public static ActorBounds of(ActorGeometry geometry){
        double[] b={Double.POSITIVE_INFINITY,Double.POSITIVE_INFINITY,Double.POSITIVE_INFINITY,Double.NEGATIVE_INFINITY,Double.NEGATIVE_INFINITY,Double.NEGATIVE_INFINITY};
        for(var q:geometry.model().quads())for(var p:q.positions())include(b,p.x()+geometry.x(),p.y()+geometry.y(),p.z()+geometry.z());
        return finish(b);
    }
    static void include(double[] b,double x,double y,double z){b[0]=Math.min(b[0],x);b[1]=Math.min(b[1],y);b[2]=Math.min(b[2],z);b[3]=Math.max(b[3],x);b[4]=Math.max(b[4],y);b[5]=Math.max(b[5],z);}
    static ActorBounds finish(double[] b){return Double.isFinite(b[0])?new ActorBounds(b[0],b[1],b[2],b[3],b[4],b[5]):null;}
}
