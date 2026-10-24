package com.wenkrang.famara.render.scene;

import com.wenkrang.famara.render.asset.*;
import com.wenkrang.famara.render.math.*;
import java.util.*;
import java.util.function.UnaryOperator;

/** Minecraft cuboid UV layout, transformed into the existing indexed quad path. Units are pixels. */
public final class ActorModelBuilder {
    private final FrozenActor actor;
    private final int x,y,z;
    private final double modelScale;
    private final List<BakedQuad> faces=new ArrayList<>();
    private final Map<ResourceKey,Texture> textures=new HashMap<>();
    public ActorModelBuilder(FrozenActor actor,double modelScale){this(actor,modelScale,true);}
    public ActorModelBuilder(FrozenActor actor,double modelScale,boolean scaleBaby){this.actor=actor;this.modelScale=modelScale*actor.scale()*(scaleBaby && actor.baby()?.5:1);x=(int)Math.floor(actor.position().x());y=(int)Math.floor(actor.position().y());z=(int)Math.floor(actor.position().z());}
    public void texture(ResourceKey key,Texture image){textures.put(key,image);}
    public void box(ResourceKey texture,double px,double py,double pz,double minX,double minY,double minZ,double width,double height,double depth,int u,int v,double inflate,double rx,double ry,int tint,int layer) {
        double a=minX-inflate,b=minY-inflate,c=minZ-inflate,d=minX+width+inflate,e=minY+height+inflate,f=minZ+depth+inflate;
        Vec3[] p={new Vec3(a,b,c),new Vec3(d,b,c),new Vec3(d,e,c),new Vec3(a,e,c),new Vec3(a,b,f),new Vec3(d,b,f),new Vec3(d,e,f),new Vec3(a,e,f)};
        UnaryOperator<Vec3> transform=point->{Vec3 q=point.rotate(0,rx).rotate(1,ry).add(new Vec3(px,py,pz)).multiply(modelScale/16).rotate(1,-Math.toRadians(actor.bodyYaw()));Vec3 world=q.add(actor.position().subtract(new Vec3(x,y,z)));return new Vec3(grid(world.x()),grid(world.y()),grid(world.z()));};
        // Outward winding; front of a yaw-zero actor faces +Z.
        quad(texture,p,new int[]{4,5,6,7},u+depth,v+depth,width,height,transform,tint,layer);
        quad(texture,p,new int[]{1,0,3,2},u+2*depth+width,v+depth,width,height,transform,tint,layer);
        quad(texture,p,new int[]{0,4,7,3},u,v+depth,depth,height,transform,tint,layer);
        quad(texture,p,new int[]{5,1,2,6},u+depth+width,v+depth,depth,height,transform,tint,layer);
        quad(texture,p,new int[]{7,6,2,3},u+depth,v,width,depth,transform,tint,layer);
        quad(texture,p,new int[]{0,1,5,4},u+depth+width,v,width,depth,transform,tint,layer);
    }
    // A representable sub-texel offset gives skin seams a consistent half-open side on CPU/OpenCL.
    private static double uv(double pixel,int size){return (float)((pixel+1.0/65536)/size);}
    // Bound actor geometry to a sub-millimetre grid shared exactly by float32 GPU uploads.
    private static double grid(double value){return Math.rint(value*4096)/4096;}
    private void quad(ResourceKey key,Vec3[] points,int[] order,double u,double v,double w,double h,UnaryOperator<Vec3> transform,int tint,int layer) {
        Texture texture=Objects.requireNonNull(textures.get(key));List<Vec3> positions=Arrays.stream(order).mapToObj(i->transform.apply(points[i])).toList();Vec3 normal=positions.get(1).subtract(positions.get(0)).cross(positions.get(2).subtract(positions.get(0))).normalized();
        normal=new Vec3((float)normal.x(),(float)normal.y(),(float)normal.z());
        List<BakedQuad.Uv> uv=List.of(new BakedQuad.Uv(uv(u,texture.width()),uv(v+h,texture.height())),new BakedQuad.Uv(uv(u+w,texture.width()),uv(v+h,texture.height())),new BakedQuad.Uv(uv(u+w,texture.width()),uv(v,texture.height())),new BakedQuad.Uv(uv(u,texture.width()),uv(v,texture.height())));
        faces.add(new BakedQuad(positions,uv,key,normal,Direction.closest(normal),null,-1,true,false,null,0,layer,BakedQuad.Topology.QUAD,faces.size(),tint,1));
    }
    public void face(ResourceKey key,List<Vec3> pixels,List<BakedQuad.Uv> coordinates,int tint,int layer,double opacity){face(key,pixels,coordinates,tint,layer,opacity,false,0);}
    public void face(ResourceKey key,List<Vec3> pixels,List<BakedQuad.Uv> coordinates,int tint,int layer,double opacity,boolean translucent,int emission){
        if(faces.size()>=4096)throw new IllegalStateException("Actor geometry exceeds 4096 faces");
        if(pixels.size()!=4 || coordinates.size()!=4)throw new IllegalArgumentException("Actor quad needs four vertices");
        var positions=pixels.stream().map(p->p.multiply(modelScale/16).rotate(1,-Math.toRadians(actor.bodyYaw())).add(actor.position().subtract(new Vec3(x,y,z)))).map(p->new Vec3(grid(p.x()),grid(p.y()),grid(p.z()))).toList();
        Vec3 cross=positions.get(1).subtract(positions.get(0)).cross(positions.get(2).subtract(positions.get(0)));if(cross.dot(cross)<1e-18)return;Vec3 n=cross.normalized();n=new Vec3((float)n.x(),(float)n.y(),(float)n.z());
        var image=Objects.requireNonNull(textures.get(key));var uv=coordinates.stream().map(p->new BakedQuad.Uv(uv(p.u()*image.width(),image.width()),uv(p.v()*image.height(),image.height()))).toList();
        faces.add(new BakedQuad(positions,uv,key,n,Direction.closest(n),null,-1,true,translucent,null,emission,layer,BakedQuad.Topology.QUAD,faces.size(),tint,opacity));
    }
    public int faceCount(){return faces.size();}
    public ActorGeometry build(){return new ActorGeometry(actor,x,y,z,new BakedModel(new ResourceKey("famara","actor/"+actor.id()),faces,false),textures);}
}
