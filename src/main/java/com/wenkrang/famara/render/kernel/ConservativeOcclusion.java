package com.wenkrang.famara.render.kernel;

import com.wenkrang.famara.render.math.Vec3;
import java.util.*;

/** 只以真实完全不透明整格证明遮挡；每个最终样本独立保留深度，不以一条射线代表整个tile。 */
public final class ConservativeOcclusion {
    @FunctionalInterface public interface View {int kind(int x,int y,int z);}
    private final Ray[] rays;
    private final double[] depth;
    private record Group(int start,int end,Vec3 axis,double cos,double sin) {}
    private final Group[] groups;
    private final double distance;
    private int updating,activeCount;
    private final int[] activeIndices;
    private final int[] cellX,cellY,cellZ;
    private final double[] nextX,nextY,nextZ,entered;
    private final byte[] status;
    private long visits,advanced;
    private int completed;
    public record Progress(int samples,int processed,int completed,long visits,long advanced) {}
    public Progress progress(){return new Progress(rays.length,completed+updating,completed,visits,advanced);}
    public ConservativeOcclusion(Camera camera,int samples,double distance) {
        if(samples!=1 && samples!=4)throw new IllegalArgumentException("遮挡样本仅支持1或4");
        this.distance=distance;rays=new Ray[16384*samples];depth=new double[rays.length];Arrays.fill(depth,Double.POSITIVE_INFINITY);
        for(int p=0;p<16384;p++)for(int s=0;s<samples;s++)rays[p*samples+s]=camera.ray(p%128,p/128,samples==1?.5:(s%2+.5)/2,samples==1?.5:(s/2+.5)/2);
        activeIndices=new int[rays.length];activeCount=rays.length;for(int i=0;i<activeCount;i++)activeIndices[i]=i;
        cellX=new int[rays.length];cellY=new int[rays.length];cellZ=new int[rays.length];
        nextX=new double[rays.length];nextY=new double[rays.length];nextZ=new double[rays.length];entered=new double[rays.length];status=new byte[rays.length];
        groups=new Group[(rays.length+63)/64];
        for(int n=0;n<groups.length;n++) {
            int start=n*64,end=Math.min(rays.length,start+64);Vec3 axis=rays[start].direction().add(rays[end-1].direction()).normalized();double cos=1;
            for(int i=start;i<end;i++)cos=Math.min(cos,axis.dot(rays[i].direction()));groups[n]=new Group(start,end,axis,cos,Math.sqrt(Math.max(0,1-cos*cos)));
        }
    }
    /** 同一owner、同一epoch内冻结视图只增不改；未知格保留前沿，不能把新视图当替换或重置。 */
    public void beginUpdate(){updating=0;}
    public boolean update(View view,int operations,long deadline) {
        int work=0;
        while(work<operations && updating<activeCount && System.nanoTime()<deadline) {
            int i=activeIndices[updating];
            Ray ray=rays[i];Vec3 o=ray.origin(),d=ray.direction();
            int sx=d.x()>0?1:d.x()<0?-1:0,sy=d.y()>0?1:d.y()<0?-1:0,sz=d.z()>0?1:d.z()<0?-1:0;
            double dx=sx==0?Double.POSITIVE_INFINITY:1/Math.abs(d.x()),dy=sy==0?Double.POSITIVE_INFINITY:1/Math.abs(d.y()),dz=sz==0?Double.POSITIVE_INFINITY:1/Math.abs(d.z());
            if(status[i]==0) {
                cellX[i]=(int)Math.floor(o.x());cellY[i]=(int)Math.floor(o.y());cellZ[i]=(int)Math.floor(o.z());
                nextX[i]=sx==0?Double.POSITIVE_INFINITY:((sx>0?cellX[i]+1d:cellX[i])-o.x())/d.x();
                nextY[i]=sy==0?Double.POSITIVE_INFINITY:((sy>0?cellY[i]+1d:cellY[i])-o.y())/d.y();
                nextZ[i]=sz==0?Double.POSITIVE_INFINITY:((sz>0?cellZ[i]+1d:cellZ[i])-o.z())/d.z();status[i]=1;
            }
            while(work<operations && System.nanoTime()<deadline) {
                work++;
                int x=cellX[i],y=cellY[i],z=cellZ[i];double entry=entered[i],exit=Math.min(nextX[i],Math.min(nextY[i],nextZ[i]));
                if(entry>distance){finish(i);break;}
                visits++;int kind=view.kind(x,y,z);
                if(kind<0){updating++;break;}
                double middle=(entry+Math.min(exit,distance))/2;
                double px=o.x()+d.x()*middle,py=o.y()+d.y()*middle,pz=o.z()+d.z()*middle;
                boolean interior=px>x+1e-8 && px<x+1-1e-8 && py>y+1e-8 && py<y+1-1e-8 && pz>z+1e-8 && pz<z+1-1e-8;
                if(exit-entry>1e-7 && kind>0 && interior){depth[i]=exit+1e-6;finish(i);break;}
                if(!Double.isFinite(exit) || exit>distance){finish(i);break;}
                if(nextX[i]<=exit+1e-10){cellX[i]+=sx;nextX[i]+=dx;}
                if(nextY[i]<=exit+1e-10){cellY[i]+=sy;nextY[i]+=dy;}
                if(nextZ[i]<=exit+1e-10){cellZ[i]+=sz;nextZ[i]+=dz;}
                entered[i]=exit;advanced++;
            }
        }
        return updating==activeCount;
    }
    private void finish(int i){status[i]=2;completed++;activeIndices[updating]=activeIndices[--activeCount];}
    public boolean hidden(double x0,double y0,double z0,double x1,double y1,double z1) {
        Vec3 origin=rays[0].origin();double cx=(x0+x1)/2-origin.x(),cy=(y0+y1)/2-origin.y(),cz=(z0+z1)/2-origin.z();
        double length=Math.sqrt(cx*cx+cy*cy+cz*cz),radius=Math.sqrt((x1-x0)*(x1-x0)+(y1-y0)*(y1-y0)+(z1-z0)*(z1-z0))/2;
        if(length<=radius+1e-8)return false;
        double sin=radius/length,cos=Math.sqrt(Math.max(0,1-sin*sin));cx/=length;cy/=length;cz/=length;
        for(Group group:groups) {
            double dot=group.axis.x()*cx+group.axis.y()*cy+group.axis.z()*cz;
            if(dot+1e-8<group.cos*cos-group.sin*sin)continue;
            for(int i=group.start;i<group.end;i++) {
                double near=near(rays[i],x0,y0,z0,x1,y1,z1);if(Double.isNaN(near) || near>distance)continue;
                if(!(depth[i]+1e-5<near))return false;
            }
        }
        return true;
    }
    private static double near(Ray ray,double x0,double y0,double z0,double x1,double y1,double z1) {
        double low=0,high=Double.POSITIVE_INFINITY;Vec3 o=ray.origin(),d=ray.direction();
        for(int axis=0;axis<3;axis++) {
            double a=axis==0?x0:axis==1?y0:z0,b=axis==0?x1:axis==1?y1:z1,origin=o.component(axis),direction=d.component(axis);
            if(direction==0){if(origin<a-1e-8 || origin>b+1e-8)return Double.NaN;continue;}
            double first=(a-origin)/direction,last=(b-origin)/direction;low=Math.max(low,Math.min(first,last));high=Math.min(high,Math.max(first,last));if(low>high+1e-8)return Double.NaN;
        }
        return high>=0?low:Double.NaN;
    }
    public static long estimatedBytes(int samples){return 16384L*samples*232+2048;}
    public long estimatedBytes(){return estimatedBytes(rays.length/16384);}
}
