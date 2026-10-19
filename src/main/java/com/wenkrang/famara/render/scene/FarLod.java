package com.wenkrang.famara.render.scene;

import com.wenkrang.famara.render.asset.*;
import com.wenkrang.famara.render.kernel.*;
import com.wenkrang.famara.render.math.*;
import com.wenkrang.famara.render.schedule.MemoryScope;
import java.util.*;

/** 仅合并普通静态不透明满块的2×2×2并集；远景六面色为明确的线性近似。 */
public final class FarLod implements AutoCloseable {
    public record Policy(boolean enabled,double minDistance,double maxProjectedPixels) {
        public Policy{if(!Double.isFinite(minDistance) || minDistance<16 || minDistance>256 || !Double.isFinite(maxProjectedPixels) || maxProjectedPixels<=0 || maxProjectedPixels>16)throw new IllegalArgumentException("远景LOD策略无效");}
        public static Policy disabled(){return new Policy(false,64,4);}
    }
    public record Host(VisualBlockDescriptor descriptor,List<BakedModel> models,SceneSnapshot.Lighting light) {}
    @FunctionalInterface public interface Source {Host at(int x,int y,int z);}
    @FunctionalInterface public interface Visible {boolean test(int x,int y,int z);}
    private record Section(int x,int y,int z) {}
    private final Camera camera;
    private final CpuPhotoRenderer.Settings settings;
    private final Policy policy;
    private final AssetGeneration assets;
    private final MemoryScope memory;
    private Map<Section,byte[]> decisions=new HashMap<>();
    private boolean closed;
    private BakedModel cube;
    private long cacheBytes,groups,hidden;
    public FarLod(Camera camera,CpuPhotoRenderer.Settings settings,Policy policy,AssetGeneration assets,MemoryScope memory){this.camera=camera;this.settings=settings;this.policy=policy;this.assets=assets;this.memory=memory;}
    private void claim(long bytes){if(memory!=null)memory.claim(bytes);}
    public long groups(){return groups;}
    public long hiddenGroups(){return hidden;}
    public boolean emit(int x,int y,int z,Source source,Visible visible,SceneSnapshot.Builder builder) {
        if(closed)throw new IllegalStateException("远景LOD已关闭");if(!policy.enabled)return false;
        int ax=x&~1,ay=y&~1,az=z&~1;
        Section key=new Section(ax>>4,ay>>4,az>>4);byte[] table=decisions.get(key);
        int index=((ax&15)>>1)|(((az&15)>>1)<<3)|(((ay&15)>>1)<<6);
        if(table!=null && table[index]!=0)return table[index]==2;
        if(!farEnough(camera,ax,ay,az,policy.minDistance) || projectedPixels(camera,ax,ay,az)>policy.maxProjectedPixels)return false;
        if(table==null){claim(1024);cacheBytes+=1024;table=new byte[512];decisions.put(key,table);}
        long window=32768;claim(window);
        try {
            Host[] hosts=new Host[8];
            for(int dy=0;dy<2;dy++)for(int dz=0;dz<2;dz++)for(int dx=0;dx<2;dx++) {
                Host host=source.at(ax+dx,ay+dy,az+dz);
                if(host==null || !eligible(host,assets,settings)){table[index]=1;return false;}
                hosts[dy*4+dz*2+dx]=host;
            }
            if(!visible.test(ax,ay,az)){table[index]=2;hidden++;return true;}
            List<Texture.Sample> colors=new ArrayList<>(6);
            for(Direction face:Direction.values()) {
                double r=0,g=0,b=0;int count=0;
                for(int dy=0;dy<2;dy++)for(int dz=0;dz<2;dz++)for(int dx=0;dx<2;dx++) {
                    if(face==Direction.DOWN && dy!=0 || face==Direction.UP && dy!=1 || face==Direction.NORTH && dz!=0 || face==Direction.SOUTH && dz!=1 || face==Direction.WEST && dx!=0 || face==Direction.EAST && dx!=1)continue;
                    Host host=hosts[dy*4+dz*2+dx];BakedQuad quad=host.models.getFirst().quads().stream().filter(q->q.face()==face).findFirst().orElseThrow();
                    Texture.Sample color=faceColor(quad,host.light,assets,settings);r+=color.r();g+=color.g();b+=color.b();count++;
                }
                colors.add(new Texture.Sample(r/count,g/count,b/count,1));
            }
            if(cube==null) {
                claim(8192);List<BakedQuad> quads=new ArrayList<>();ResourceKey texture=hosts[0].models.getFirst().quads().getFirst().texture();
                for(Direction face:Direction.values())ModelGeometry.face(quads,texture,face,ModelGeometry.points(face,0,0,0,32,32,32),new double[]{0,0,16,16},16,16,0xffffff,0);
                cube=new BakedModel(ResourceKey.parse("famara:builtin/lod_cube"),quads,false);
            }
            builder.proxy(ax,ay,az,hosts[0].descriptor,cube,new SceneSnapshot.FarColors(colors));table[index]=2;groups++;return true;
        }finally{if(memory!=null)memory.release(window);}
    }
    public static boolean eligible(Host host,AssetGeneration assets,CpuPhotoRenderer.Settings settings) {
        var descriptor=host.descriptor;
        if(descriptor.kind()!=VisualBlockDescriptor.Kind.VANILLA || !descriptor.visualState().block().namespace().equals("minecraft") || !descriptor.logicalId().equals(descriptor.visualState().block()) || FluidMesher.type(descriptor.visualState())!=null || host.models.size()!=1)return false;
        List<BakedQuad> quads=host.models.getFirst().quads();if(quads.size()!=6 || !OpaqueShapes.fullCube(host.models,assets))return false;
        EnumSet<Direction> faces=EnumSet.noneOf(Direction.class);
        for(BakedQuad quad:quads) {
            if(!faces.add(quad.face()) || quad.topology()!=BakedQuad.Topology.QUAD || quad.opacity()!=1 || quad.forceTranslucent() || !assets.texture(quad.texture()).opaque() || assets.texture(quad.texture()).frameCount()!=1)return false;
            if(settings.materialOverrides().getOrDefault(quad.texture(),CpuPhotoRenderer.AlphaMode.OPAQUE)!=CpuPhotoRenderer.AlphaMode.OPAQUE)return false;
            for(var uv:quad.uv())if(uv.u()<0 || uv.u()>1 || uv.v()<0 || uv.v()>1)return false;
        }
        return true;
    }
    private static Texture.Sample faceColor(BakedQuad quad,SceneSnapshot.Lighting lighting,AssetGeneration assets,CpuPhotoRenderer.Settings settings) {
        Texture texture=assets.texture(quad.texture());double u=0,v=0,u0=1,v0=1,u1=0,v1=0;
        for(var uv:quad.uv()){u+=uv.u();v+=uv.v();u0=Math.min(u0,uv.u());u1=Math.max(u1,uv.u());v0=Math.min(v0,uv.v());v1=Math.max(v1,uv.v());}
        double lod=Math.max(0,Math.log(Math.max(1,Math.max(texture.width()*(u1-u0),texture.height()*(v1-v0))))/Math.log(2));
        Texture.Sample color=texture.sample(u/4,v/4,lod,settings.animationTicks(),Texture.Filter.MIPMAP);var light=lighting.face(quad.face());
        double intensity=Math.max(light.sky(),Math.max(light.block(),quad.lightEmission()))/15.0;intensity=settings.ambientFloor()+(1-settings.ambientFloor())*intensity;
        if(settings.directionalLight() && quad.shade()){Vec3 n=quad.shadeDirectionOverride()==null?quad.normal():quad.shadeDirectionOverride().vector();intensity*=n.x()*n.x()*.6+n.z()*n.z()*.8+n.y()*n.y()*(n.y()>0?1:.5);}
        int tint=quad.tintIndex()<0?0xffffff:lighting.tintRgb(),multiply=quad.colorMultiplier();
        return new Texture.Sample(color.r()*intensity*Texture.linear(tint>>16&255)*Texture.linear(multiply>>16&255),color.g()*intensity*Texture.linear(tint>>8&255)*Texture.linear(multiply>>8&255),color.b()*intensity*Texture.linear(tint&255)*Texture.linear(multiply&255),1);
    }
    public static boolean farEnough(Camera camera,int x,int y,int z,double minimum) {
        Vec3 origin=camera.origin();double dx=Math.max(x-origin.x(),Math.max(0,origin.x()-x-2)),dy=Math.max(y-origin.y(),Math.max(0,origin.y()-y-2)),dz=Math.max(z-origin.z(),Math.max(0,origin.z()-z-2));return dx*dx+dy*dy+dz*dz>=minimum*minimum;
    }
    public static double projectedPixels(Camera camera,int x,int y,int z) {
        Vec3 origin=camera.origin(),forward=new Vec3(Math.cos(camera.yaw())*Math.cos(camera.pitch()),Math.sin(camera.pitch()),Math.sin(camera.yaw())*Math.cos(camera.pitch()));
        Vec3 right=new Vec3(-Math.sin(camera.yaw()),0,Math.cos(camera.yaw())),up=right.cross(forward).normalized();double x0=Double.POSITIVE_INFINITY,y0=x0,x1=Double.NEGATIVE_INFINITY,y1=x1;
        for(int dx=0;dx<=2;dx+=2)for(int dy=0;dy<=2;dy+=2)for(int dz=0;dz<=2;dz+=2) {
            Vec3 point=new Vec3(x+dx-origin.x(),y+dy-origin.y(),z+dz-origin.z());double depth=point.dot(forward);if(depth<=1e-6)return Double.POSITIVE_INFINITY;
            double px,py;
            if(camera.projection()==Camera.Projection.LEGACY_ANGULAR) {
                double yaw=Math.atan2(point.z(),point.x())-camera.yaw();yaw=Math.atan2(Math.sin(yaw),Math.cos(yaw));if(Math.abs(yaw)>Math.PI/2)return Double.POSITIVE_INFINITY;
                px=yaw*128;py=Math.asin(Math.clamp(point.y()/Math.sqrt(point.dot(point)),-1,1))*128;
                // 球面竖直极点附近不能仅以角点包络证明组投影足够小。
                if(Math.abs(point.y())/Math.sqrt(point.dot(point))>.99)return Double.POSITIVE_INFINITY;
            }else{double scale=64/Math.tan(camera.horizontalFov()/2);px=point.dot(right)/depth*scale;py=point.dot(up)/depth*scale;}
            x0=Math.min(x0,px);x1=Math.max(x1,px);y0=Math.min(y0,py);y1=Math.max(y1,py);
        }
        if(camera.projection()==Camera.Projection.LEGACY_ANGULAR) {
            double dx=Math.max(x-origin.x(),Math.max(0,origin.x()-x-2)),dz=Math.max(z-origin.z(),Math.max(0,origin.z()-z-2));
            double h0=Math.hypot(dx,dz),h1=Math.hypot(Math.max(Math.abs(x-origin.x()),Math.abs(x+2-origin.x())),Math.max(Math.abs(z-origin.z()),Math.abs(z+2-origin.z())));
            for(double height:new double[]{y-origin.y(),y+2-origin.y()})for(double horizontal:new double[]{h0,h1}){double angle=Math.atan2(height,horizontal)*128;y0=Math.min(y0,angle);y1=Math.max(y1,angle);}
        }
        return Math.max(x1-x0,y1-y0)+1e-6;
    }
    @Override public void close(){if(closed)return;closed=true;decisions=Map.of();if(memory!=null)memory.release(cacheBytes);cacheBytes=0;}
}
