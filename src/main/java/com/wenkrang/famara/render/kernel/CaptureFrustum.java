package com.wenkrang.famara.render.kernel;

import com.wenkrang.famara.render.math.Vec3;
import java.util.*;

/** 保守候选剔除，只排除整个包围盒都不可能被任何照片射线命中的宿主。 */
public final class CaptureFrustum {
    private final Vec3 origin,forward;
    private final List<Vec3> planes;
    private final double distance;
    public CaptureFrustum(Camera camera,double distance) {
        if(!Double.isFinite(distance) || distance<=0)throw new IllegalArgumentException("候选距离无效");
        this.distance=distance;origin=camera.origin();
        forward=new Vec3(Math.cos(camera.yaw())*Math.cos(camera.pitch()),Math.sin(camera.pitch()),Math.sin(camera.yaw())*Math.cos(camera.pitch()));
        Vec3 right=new Vec3(-Math.sin(camera.yaw()),0,Math.cos(camera.yaw())),up=right.cross(forward).normalized();
        // 旧投影的像素/样本角偏移各不超过64.5/128。球面角距不大于两轴偏移路径长度。
        double tan=camera.projection()==Camera.Projection.LEGACY_ANGULAR?Math.tan(Math.hypot(64.5/128,64.5/128)+1e-9):Math.tan(camera.horizontalFov()/2);
        planes=List.of(forward,right.multiply(-1).add(forward.multiply(tan)),right.add(forward.multiply(tan)),
                up.multiply(-1).add(forward.multiply(tan)),up.add(forward.multiply(tan)));
    }
    public boolean mayIntersect(Vec3 min,Vec3 max){return mayIntersect(min.x(),min.y(),min.z(),max.x(),max.y(),max.z());}
    public boolean mayIntersect(double x0,double y0,double z0,double x1,double y1,double z1) {
        if(x0>x1 || y0>y1 || z0>z1)throw new IllegalArgumentException("候选包围盒无效");
        double dx=Math.max(x0-origin.x(),Math.max(0,origin.x()-x1)),dy=Math.max(y0-origin.y(),Math.max(0,origin.y()-y1)),dz=Math.max(z0-origin.z(),Math.max(0,origin.z()-z1));
        if(dx*dx+dy*dy+dz*dz>distance*distance+1e-8)return false;
        double cx=(x0+x1)/2-origin.x(),cy=(y0+y1)/2-origin.y(),cz=(z0+z1)/2-origin.z();
        double hx=(x1-x0)/2,hy=(y1-y0)/2,hz=(z1-z0)/2;
        for(Vec3 plane:planes) {
            double center=plane.x()*cx+plane.y()*cy+plane.z()*cz;
            double radius=Math.abs(plane.x())*hx+Math.abs(plane.y())*hy+Math.abs(plane.z())*hz;
            if(center+radius < -1e-8)return false;
        }
        return true;
    }
}
