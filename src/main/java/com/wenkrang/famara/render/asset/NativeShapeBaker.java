package com.wenkrang.famara.render.asset;

import com.wenkrang.famara.render.math.*;
import org.joml.Matrix3d;
import org.joml.Matrix4d;
import org.joml.Vector3d;
import java.util.*;

/** 把受限解析得到的静态部件树转换为普通不可变面，渲染内核不持有ASM或客户端对象。 */
final class NativeShapeBaker {
    private NativeShapeBaker() {}
    static BakedModel statue(ResourceKey key,ResourceKey texture,NativeModelDataReader.Shape shape) {
        List<BakedQuad> quads=new ArrayList<>();
        bake(shape.root(),new Matrix4d().translation(.5,0,.5),shape,texture,quads,true);
        return new BakedModel(key,quads,false);
    }
    private static void bake(NativeModelDataReader.Part part,Matrix4d parent,NativeModelDataReader.Shape shape,
                             ResourceKey texture,List<BakedQuad> output,boolean root) {
        NativeModelDataReader.Pose pose=part.pose();Vec3 offset=pose.offset(),rotation=pose.rotation();
        if(root){offset=new Vec3(offset.x(),0,offset.z());rotation=new Vec3(rotation.x(),rotation.y(),Math.PI);}
        Matrix4d matrix=new Matrix4d(parent).translate(offset.x()/16,offset.y()/16,offset.z()/16)
                .rotateZYX(rotation.z(),rotation.y(),rotation.x()).scale(pose.scale().x(),pose.scale().y(),pose.scale().z());
        Matrix3d normals=new Matrix3d(matrix).invert().transpose();
        for(var cube:part.cubes()) {
            List<BakedQuad> local=new ArrayList<>();
            ModelGeometry.entityBox(local,texture,cube.x(),cube.y(),cube.z(),cube.w(),cube.h(),cube.d(),cube.u(),cube.v(),
                    shape.width(),shape.height(),Set.of(),0xffffff,cube.inflation());
            for(BakedQuad q:local) {
                List<Vec3> points=new ArrayList<>();
                for(Vec3 p:q.positions()) {
                    double x=cube.mirror()?(2*cube.x()+cube.w())/16-p.x():p.x();
                    Vector3d value=matrix.transformPosition(new Vector3d(x,p.y(),p.z()));points.add(new Vec3(value.x,value.y,value.z));
                }
                Vec3 n=q.normal();Vector3d normal=normals.transform(new Vector3d(cube.mirror()?-n.x():n.x(),n.y(),n.z()));
                Vec3 unit=new Vec3(normal.x,normal.y,normal.z).normalized();int layer=output.size();
                output.add(new BakedQuad(points,q.uv(),texture,unit,Direction.closest(unit),null,-1,true,false,null,0,layer));
            }
        }
        for(var child:part.children())bake(child,matrix,shape,texture,output,false);
    }
}
