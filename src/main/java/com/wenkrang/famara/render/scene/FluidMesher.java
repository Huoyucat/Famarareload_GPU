package com.wenkrang.famara.render.scene;

import com.wenkrang.famara.render.asset.*;
import com.wenkrang.famara.render.math.*;
import java.util.*;

/** 从已冻结的邻域生成流体表面；不访问 Bukkit，缺失邻域由 View 明确报错。 */
public final class FluidMesher {
    public record Cell(CanonicalBlockState state,boolean solid) { public Cell { Objects.requireNonNull(state); } }
    @FunctionalInterface public interface View { Cell at(int x,int y,int z); }
    private FluidMesher() {}
    public static String type(CanonicalBlockState state) {
        if(state.block().namespace().equals("minecraft") && (state.block().path().equals("water") || state.block().path().equals("lava")))return state.block().path();
        return "true".equals(state.properties().get("waterlogged"))?"water":null;
    }
    public static BakedModel build(int x,int y,int z,View view,int waterColor) {
        if((waterColor&0xff000000)!=0)throw new IllegalArgumentException("非法水色");
        BakedModel plain=build(x,y,z,view);
        if(!"water".equals(type(view.at(x,y,z).state)))return plain;
        return new BakedModel(plain.key(),plain.quads().stream().map(q->new BakedQuad(q.positions(),q.uv(),q.texture(),q.normal(),q.face(),q.cullFace(),
                -1,q.shade(),q.forceTranslucent(),q.shadeDirectionOverride(),q.lightEmission(),q.layer(),q.topology(),q.surfaceGroup(),waterColor,q.opacity())).toList(),false);
    }
    public static BakedModel build(int x,int y,int z,View view) {
        Cell center=view.at(x,y,z);String kind=type(center.state);
        if(kind==null)throw new IllegalArgumentException("此方块没有流体");
        boolean water=kind.equals("water");
        ResourceKey still=ResourceKey.parse("minecraft:block/"+kind+"_still"),flow=ResourceKey.parse("minecraft:block/"+kind+"_flow");
        double nw=corner(x,y,z,-1,-1,kind,view),ne=corner(x,y,z,1,-1,kind,view),se=corner(x,y,z,1,1,kind,view),sw=corner(x,y,z,-1,1,kind,view);
        List<BakedQuad> faces=new ArrayList<>();int group=0;
        if(!same(view.at(x,y+1,z),kind)) {
            double dx=height(x-1,y,z,kind,view)-height(x+1,y,z,kind,view);
            double dz=height(x,y,z-1,kind,view)-height(x,y,z+1,kind,view);
            boolean flowing=Math.abs(dx)+Math.abs(dz)>1e-9;
            List<BakedQuad.Uv> uv=new ArrayList<>();
            for(double[] p:new double[][]{{0,0},{1,0},{1,1},{0,1}}) {
                if(!flowing)uv.add(new BakedQuad.Uv(p[0],p[1]));
                else {
                    double angle=Math.atan2(dz,dx),a=(p[0]-.5)*.5,b=(p[1]-.5)*.5;
                    uv.add(new BakedQuad.Uv(.5+a*Math.cos(angle)-b*Math.sin(angle),.5+a*Math.sin(angle)+b*Math.cos(angle)));
                }
            }
            surface(faces,flowing?flow:still,Direction.UP,List.of(new Vec3(0,nw,0),new Vec3(1,ne,0),new Vec3(1,se,1),new Vec3(0,sw,1)),uv,group++,water);
        }
        if(!same(view.at(x,y-1,z),kind) && !view.at(x,y-1,z).solid)
            surface(faces,still,Direction.DOWN,ModelGeometry.points(Direction.DOWN,0,0,0,16,0,16),rectangle(0,0,1,1),group++,water);
        if(exposed(view.at(x,y,z-1),kind))surface(faces,flow,Direction.NORTH,
                List.of(new Vec3(1,ne,0),new Vec3(0,nw,0),new Vec3(0,0,0),new Vec3(1,0,0)),sideUv(ne,nw),group++,water);
        if(exposed(view.at(x,y,z+1),kind))surface(faces,flow,Direction.SOUTH,
                List.of(new Vec3(0,sw,1),new Vec3(1,se,1),new Vec3(1,0,1),new Vec3(0,0,1)),sideUv(sw,se),group++,water);
        if(exposed(view.at(x-1,y,z),kind))surface(faces,flow,Direction.WEST,
                List.of(new Vec3(0,nw,0),new Vec3(0,sw,1),new Vec3(0,0,1),new Vec3(0,0,0)),sideUv(nw,sw),group++,water);
        if(exposed(view.at(x+1,y,z),kind))surface(faces,flow,Direction.EAST,
                List.of(new Vec3(1,se,1),new Vec3(1,ne,0),new Vec3(1,0,0),new Vec3(1,0,1)),sideUv(se,ne),group,water);
        return new BakedModel(ResourceKey.parse("minecraft:builtin/fluid/"+kind),faces,false);
    }
    private static boolean same(Cell cell,String kind) { return kind.equals(type(cell.state)); }
    private static boolean exposed(Cell cell,String kind) { return !same(cell,kind) && !cell.solid; }
    private static double height(int x,int y,int z,String kind,View view) {
        Cell cell=view.at(x,y,z);
        if(!same(cell,kind))return cell.solid?-1:0;
        if(same(view.at(x,y+1,z),kind))return 1;
        if(!cell.state.block().path().equals(kind))return 8/9.0;
        int level=Integer.parseInt(cell.state.properties().getOrDefault("level","0"));
        if(level<0 || level>15)throw new IllegalArgumentException("非法流体液位");
        return level>=8?8/9.0:(8-level)/9.0;
    }
    private static double corner(int x,int y,int z,int sx,int sz,String kind,View view) {
        double a=height(x,y,z,kind,view),b=height(x+sx,y,z,kind,view),c=height(x,y,z+sz,kind,view);
        if(a>=1 || b>=1 || c>=1)return 1;
        List<Double> values=new ArrayList<>(List.of(a,b,c));
        if(b>0 || c>0) {
            double diagonal=height(x+sx,y,z+sz,kind,view);
            if(diagonal>=1)return 1;values.add(diagonal);
        }
        double sum=0,weight=0;
        for(double value:values)if(value>=0) { double w=value>=.8?10:1;sum+=value*w;weight+=w; }
        return weight==0?0:sum/weight;
    }
    private static List<BakedQuad.Uv> rectangle(double u,double v,double U,double V) {
        return List.of(new BakedQuad.Uv(u,v),new BakedQuad.Uv(U,v),new BakedQuad.Uv(U,V),new BakedQuad.Uv(u,V));
    }
    private static List<BakedQuad.Uv> sideUv(double a,double b) {
        return List.of(new BakedQuad.Uv(0,(1-a)*.5),new BakedQuad.Uv(.5,(1-b)*.5),new BakedQuad.Uv(.5,.5),new BakedQuad.Uv(0,.5));
    }
    private static void surface(List<BakedQuad> out,ResourceKey texture,Direction face,List<Vec3> p,List<BakedQuad.Uv> uv,int group,boolean water) {
        triangle(out,texture,face,p.get(0),p.get(1),p.get(2),uv.get(0),uv.get(1),uv.get(2),group,water);
        triangle(out,texture,face,p.get(0),p.get(2),p.get(3),uv.get(0),uv.get(2),uv.get(3),group,water);
    }
    private static void triangle(List<BakedQuad> out,ResourceKey texture,Direction face,Vec3 a,Vec3 b,Vec3 c,
                                 BakedQuad.Uv ua,BakedQuad.Uv ub,BakedQuad.Uv uc,int group,boolean water) {
        Vec3 normal=c.subtract(a).cross(b.subtract(a));if(normal.dot(normal)<1e-24)return;
        out.add(new BakedQuad(List.of(a,b,c,c),List.of(ua,ub,uc,uc),texture,normal.normalized(),face,null,-1,true,water,face,
                water?0:15,group,BakedQuad.Topology.TRIANGLE,group,0xffffff,1));
    }
}
