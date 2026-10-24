#pragma OPENCL EXTENSION cl_khr_fp64 : enable
// Layout v1. Integer indexes retain the CPU's compact links; geometry is camera-relative float32.
inline int find_cell(__global const int *table, int count, int stride, int x, int y, int z) {
    int low=0, high=count-1;
    while(low<=high) {
        int mid=(low+high)>>1, at=mid*stride;
        int c=table[at]<x?-1:table[at]>x?1:table[at+1]<y?-1:table[at+1]>y?1:table[at+2]<z?-1:table[at+2]>z?1:0;
        if(c==0)return mid; if(c<0)low=mid+1;else high=mid-1;
    }
    return -1;
}
inline int cell_head(__global const int *h, __global const int *sections, __global const int *heads,
                     __global const int *known, __global const int *hidden, int3 cell, __private int *error) {
    if(cell.y<h[15] || cell.y>=h[16])return -1;
    int3 key=(int3)(cell.x>>4,cell.y>>4,cell.z>>4);
    int hostY=clamp(cell.y,h[17],h[18]-1)>>4;
    if(find_cell(hidden,h[10],3,key.x,0,key.z)>=0 || find_cell(known,h[9],3,key.x,hostY,key.z)<0){*error=1;return -1;}
    int row=find_cell(sections,h[7],6,key.x,key.y,key.z);if(row<0)return -1;
    return heads[sections[row*6+3]+((cell.y&15)<<8)+((cell.z&15)<<4)+(cell.x&15)];
}
__kernel void famara_candidates(__global const int *h, __global const int *sections, __global const int *heads,
    __global const int *nodes, __global const int *known, __global const int *hidden,
    __global const int *xyz, __global int *answers) {
    int query=get_global_id(0), error=0, count=0, first=-1;uint hash=2166136261u;
    if(h[0]!=1)error=3;
    int node=error?-1:cell_head(h,sections,heads,known,hidden,vload3(query,xyz),&error);
    while(node>=0) {
        if(node>=h[8] || count>=h[8]){error=2;break;}
        int id=nodes[node*2+1];if(id<0 || id>=h[1]){error=2;break;}
        if(count==0)first=id;hash=(hash^(uint)id)*16777619u;count++;node=nodes[node*2];
    }
    int at=query*4;answers[at]=error;answers[at+1]=count;answers[at+2]=(int)hash;answers[at+3]=first;
}

#define SCENE_ARGS __global const int *h, __global const float *hf, __global const int *instances, __global const int *models, __global const float *bounds, __global const int *fi, __global const float *ff, __global const int *textures, __global const int *sections, __global const int *heads, __global const int *nodes, __global const int *known, __global const int *hidden
#define SCENE_PASS h,hf,instances,models,bounds,fi,ff,textures,sections,heads,nodes,known,hidden
typedef struct {int error,cells,tests,hits,visited,id,model,quad,face,layer;float t,u,v,opaque; __global int *listIds; __global float *listHits; int capacity,reverse;int faceList[64];float distanceList[64];} Trace;
inline int before_hit(float t,int id,int model,int layer,int quad,__private Trace *s) {
    float eps=1e-6f*fmax(1.0f,fabs(t));
    if(s->id<0 || t<s->t-eps)return 1;
    if(fabs(t-s->t)>eps)return 0;
    if(s->reverse&1)return id>s->id || (id==s->id && (model>s->model || (model==s->model && (layer>s->layer || (layer==s->layer && quad>s->quad)))));
    return id<s->id || (id==s->id && (model<s->model || (model==s->model && (layer<s->layer || (layer==s->layer && quad<s->quad)))));
}
inline int intersection(int q,float3 origin,float3 direction,float maximum,__private float3 *hit,__global const int *fi,__global const float *ff) {
    int f=q*40;float3 p=vload3(0,ff+f),a=vload3(0,ff+f+4),b=vload3(0,ff+f+12),n=vload3(0,ff+f+24);
    float denominator=dot(n,direction);if(fabs(denominator)<1e-12f)return 0;
    float t=dot(n,p-origin)/denominator;if(t< -1e-6f || t>maximum+1e-6f)return 0;t=fmax(0.0f,t);
    float3 point=origin+t*direction-p,edgeA=a-p,edgeB=b-p;
    float aa=dot(edgeA,edgeA),bb=dot(edgeB,edgeB),ab=dot(edgeA,edgeB),det=aa*bb-ab*ab;
    if(fabs(det)<1e-20f)return 0;
    float pa=dot(point,edgeA),pb=dot(point,edgeB),u=(pa*bb-pb*ab)/det,v=(pb*aa-pa*ab)/det;
    if(u< -1e-6f || u>1.0f+1e-6f || v< -1e-6f || v>1.0f+1e-6f)return 0;
    if(fi[q*16+6]==1 && u+v>1.0f+1e-6f)return 0;
    *hit=(float3)(t,clamp(u,0.0f,1.0f),clamp(v,0.0f,1.0f));return 1;
}
inline void visit_voxel(int3 cell,float3 origin,float3 direction,float end,__private Trace *s,__private int *mailbox,SCENE_ARGS) {
    if(++s->cells>h[23]){s->error=4;return;}
    int node=cell_head(h,sections,heads,known,hidden,cell+(int3)(h[12],h[13],h[14]),&s->error),links=0;
    while(node>=0 && !s->error) {
        if(node>=h[8] || ++links>h[8]){s->error=2;return;}
        int id=nodes[node*2+1];node=nodes[node*2];if(id<0 || id>=h[1]){s->error=2;return;}
        int slot=((uint)id*2654435761u)&127,duplicate=0;
        for(int tries=0;tries<128;tries++) {
            if(mailbox[slot]==id){duplicate=1;break;}
            if(mailbox[slot]==-1){mailbox[slot]=id;s->visited++;break;}
            slot=(slot+1)&127;if(tries==127){s->error=4;return;}
        }
        if(duplicate)continue;
        int first=instances[id*12+6],count=instances[id*12+7];
        for(int m=first;m<first+count && !s->error;m++) {
            float3 low=vload3(0,bounds+m*8),high=vload3(0,bounds+m*8+4);
            int inside=all(origin>low+1e-9f) && all(origin<high-1e-9f);
            int begin=models[m*4+1],faces=models[m*4+2];
            for(int q=begin;q<begin+faces;q++) {
                if(++s->tests>h[25]){s->error=4;return;}
                int i=q*16,f=q*40;float3 normal=vload3(0,ff+f+24);
                if((!inside && dot(normal,direction)>=-1e-12f) || (fi[i+9]&4))continue;
                float3 hit;if(!intersection(q,origin,direction,end,&hit,fi,ff))continue;
                if((s->reverse&2) && fi[i+6]==1) {
                    int duplicate=0;
                    for(int prior=0;prior<s->hits;prior++){int old=s->faceList[prior]*16;if(fi[old]==id && fi[old+1]==fi[i+1] && fi[old+7]==fi[i+7] && fabs(s->distanceList[prior]-hit.x)<1e-6f){duplicate=1;break;}}
                    if(duplicate)continue;
                }
                if(++s->hits>h[24]){s->error=4;return;}
                if(s->reverse&2){if(s->hits>64){s->error=4;return;}s->faceList[s->hits-1]=q;s->distanceList[s->hits-1]=hit.x;}
                float uvU=ff[f+16]+hit.y*(ff[f+18]-ff[f+16])+hit.z*(ff[f+22]-ff[f+16]);
                float uvV=ff[f+17]+hit.y*(ff[f+19]-ff[f+17])+hit.z*(ff[f+23]-ff[f+17]);
                if(s->listIds) {
                    if(s->hits>s->capacity){s->error=4;return;}
                    vstore4((int4)(id,fi[i+1],fi[i+2],fi[i+8]),s->hits-1,s->listIds);
                    vstore4((float4)(hit.x,uvU,uvV,0),s->hits-1,s->listHits);
                }
                if(before_hit(hit.x,id,fi[i+1],fi[i+8],fi[i+2],s)) {
                    s->id=id;s->model=fi[i+1];s->quad=fi[i+2];s->face=q;s->layer=fi[i+8];s->t=hit.x;
                    s->u=ff[f+16]+hit.y*(ff[f+18]-ff[f+16])+hit.z*(ff[f+22]-ff[f+16]);
                    s->v=ff[f+17]+hit.y*(ff[f+19]-ff[f+17])+hit.z*(ff[f+23]-ff[f+17]);
                }
                if(ff[f+27]==1.0f && !(fi[i+9]&2) && textures[fi[i+3]*12+6])s->opaque=fmin(s->opaque,hit.x);
            }
        }
    }
}
inline Trace trace_ray(float3 origin,float3 direction,float maximum,__global int *listIds,__global float *listHits,int capacity,int reverse,SCENE_ARGS) {
    Trace s={0};s.id=-1;s.model=-1;s.quad=-1;s.face=-1;s.t=INFINITY;s.opaque=maximum;s.listIds=listIds;s.listHits=listHits;s.capacity=capacity;s.reverse=reverse;
    if(h[0]!=1){s.error=3;return s;}
    // Triangle shared-edge hit-list de-duplication is an explicitly gated later feature.
    if((h[30]&(1<<4)) && !(reverse&2)){s.error=5;return s;}
    float begin=0.0f,end=maximum,minY=hf[12],maxY=hf[13];
    if(direction.y==0.0f){if(origin.y<minY || origin.y>=maxY)return s;}
    else {float a=(minY-origin.y)/direction.y,b=(maxY-origin.y)/direction.y;begin=fmax(0.0f,fmin(a,b));end=fmin(end,fmax(a,b));if(begin>end || end<0.0f)return s;}
    int3 step=(int3)(direction.x>0?1:direction.x<0?-1:0,direction.y>0?1:direction.y<0?-1:0,direction.z>0?1:direction.z<0?-1:0),cell;
    float3 next,delta;
    for(int axis=0;axis<3;axis++) {
        float point=origin[axis]+direction[axis]*begin;
        if(step[axis])point=nextafter(point,step[axis]>0?INFINITY:-INFINITY);
        cell[axis]=(int)floor(point);delta[axis]=step[axis]?1.0f/fabs(direction[axis]):INFINITY;
        float boundary=step[axis]>0?cell[axis]+1.0f:cell[axis];next[axis]=step[axis]?(boundary-origin[axis])/direction[axis]:INFINITY;
    }
    int mailbox[128];for(int i=0;i<128;i++)mailbox[i]=-1;
    float entered=begin;
    while(entered<=end && entered<=s.opaque+1e-5f && !s.error) {
        visit_voxel(cell,origin,direction,end,&s,mailbox,SCENE_PASS);
        float crossing=fmin(next.x,fmin(next.y,next.z));if(crossing>end || crossing>s.opaque+1e-5f || !isfinite(crossing))break;
        int tied[3],size=0;
        for(int axis=0;axis<3;axis++)if(fabs(next[axis]-crossing)<=4.0f*FLT_EPSILON*fmax(1.0f,fabs(crossing)))tied[size++]=axis;
        if(size>1)for(int mask=1;mask<(1<<size)-1;mask++) {
            int3 other=cell;for(int bit=0;bit<size;bit++)if(mask&(1<<bit))other[tied[bit]]+=step[tied[bit]];
            visit_voxel(other,origin,direction,end,&s,mailbox,SCENE_PASS);if(s.error)break;
        }
        if(size==0){s.error=2;break;}
        for(int i=0;i<size;i++){int axis=tied[i];cell[axis]+=step[axis];next[axis]+=delta[axis];}entered=crossing;
    }
    return s;
}
__kernel void famara_trace_probe(SCENE_ARGS,__global const float *rays,__global int *ids,__global float *hits) {
    int query=get_global_id(0),at=query*8;Trace result=trace_ray(vload3(0,rays+at),vload3(0,rays+at+4),rays[at+7],0,0,0,0,SCENE_PASS);
    vstore4((int4)(result.error,result.id,result.model,result.quad),query,ids);
    vstore4((float4)(result.t,result.u,result.v,(float)result.hits),query,hits);
}

// Bounded diagnostic output in traversal order; host sorts with the reference stable keys.
__kernel void famara_hit_lists(SCENE_ARGS,__global const float *rays,__global int *ids,__global float *hits,__global int *counts) {
    int query=get_global_id(0),at=query*8;
    Trace result=trace_ray(vload3(0,rays+at),vload3(0,rays+at+4),rays[at+7],ids+query*64*4,hits+query*64*4,64,0,SCENE_PASS);
    counts[query*2]=result.error;counts[query*2+1]=result.hits;
}

#pragma OPENCL EXTENSION cl_khr_fp64 : enable
// Double camera angles avoid changing the sign of exact-axis rays when yaw is rounded.
#pragma OPENCL FP_CONTRACT OFF
inline double3 unit_double(double3 value) {
    double scale=fmax(fabs(value.x),fmax(fabs(value.y),fabs(value.z)));double3 scaled=value/scale;
    double length=sqrt(scaled.x*scaled.x+scaled.y*scaled.y+scaled.z*scaled.z);return scaled/length;
}
inline double3 camera_double(int x,int y,double ox,double oy,double yaw,double pitch,double fov,int projection) {
    double3 direction;
    if(projection==0) {
        double ax=yaw+(x+ox-0.5-64.0)/128.0,ay=pitch-(y+oy-0.5-64.0)/128.0;
        direction=(double3)(cos(ax)*cos(ay),sin(ay),sin(ax)*cos(ay));
    }else {
        double3 forward=(double3)(cos(yaw)*cos(pitch),sin(pitch),sin(yaw)*cos(pitch));
        double3 right=(double3)(-sin(yaw),0,cos(yaw)),up=unit_double(cross(right,forward));
        double sx=2.0*(x+ox)/128.0-1.0,sy=1.0-2.0*(y+oy)/128.0,tanF=tan(fov/2.0);
        direction=forward+right*(sx*tanF)+up*(sy*tanF);
    }
    return unit_double(direction);
}
inline float3 camera_direction(int x,int y,double ox,double oy,double yaw,double pitch,double fov,int projection) {
    return convert_float3(camera_double(x,y,ox,oy,yaw,pitch,fov,projection));
}
inline int plane_hit_double(int q,double3 origin,double3 direction,__private double2 *uv,__private double *distance,__global const float *ff,__global const int *fi,__global const int *instances) {
    int f=q*40,id=fi[q*16];double3 block=(double3)(instances[id*12+1],instances[id*12+2],instances[id*12+3]);
    double3 p=convert_double3(vload3(0,ff+f))-block,a=convert_double3(vload3(0,ff+f+4))-block,b=convert_double3(vload3(0,ff+f+12))-block,n=convert_double3(vload3(0,ff+f+24)),localOrigin=origin-block;
    double denom=n.x*direction.x+n.y*direction.y+n.z*direction.z;if(fabs(denom)<1e-10)return 0;
    double3 diff=p-localOrigin;double t=(n.x*diff.x+n.y*diff.y+n.z*diff.z)/denom;if(t<0)return 0;
    *distance=t;double3 delta=localOrigin+direction*t-p,u=a-p,v=b-p;
    double aa=u.x*u.x+u.y*u.y+u.z*u.z,bb=v.x*v.x+v.y*v.y+v.z*v.z,ab=u.x*v.x+u.y*v.y+u.z*v.z,det=aa*bb-ab*ab;
    if(fabs(det)<1e-20)return 0;
    double du=delta.x*u.x+delta.y*u.y+delta.z*u.z,dv=delta.x*v.x+delta.y*v.y+delta.z*v.z;
    double ss=(du*bb-dv*ab)/det,w=(dv*aa-du*ab)/det;
    *uv=(double2)(ff[f+16]+ss*(ff[f+18]-ff[f+16])+w*(ff[f+22]-ff[f+16]),ff[f+17]+ss*(ff[f+19]-ff[f+17])+w*(ff[f+23]-ff[f+17]));return 1;
}
inline float4 texel(__global const float *texels,int offset,int width,int height,int x,int y) {
    return vload4(0,texels+offset+(clamp(y,0,height-1)*width+clamp(x,0,width-1))*4);
}
inline int plane_uv_double(int q,double3 origin,double3 direction,__private double2 *uv,__global const float *ff,__global const int *fi,__global const int *instances) {
    double distance;return plane_hit_double(q,origin,direction,uv,&distance,ff,fi,instances);
}
inline double4 blend_double(double4 a,double4 b,double t){return a+(b-a)*t;}
inline double4 level_sample(double u,double v,int row,int bilinear,__global const int *levels,__global const float *texels) {
    int offset=levels[row*4],width=levels[row*4+1],height=levels[row*4+2];
    if(!bilinear)return convert_double4(texel(texels,offset,width,height,(int)floor(u*width),(int)floor(v*height)));
    double px=clamp(u*width-.5,0.0,(double)(width-1)),py=clamp(v*height-.5,0.0,(double)(height-1));int x=(int)floor(px),y=(int)floor(py);
    return blend_double(blend_double(convert_double4(texel(texels,offset,width,height,x,y)),convert_double4(texel(texels,offset,width,height,x+1,y)),px-x),blend_double(convert_double4(texel(texels,offset,width,height,x,y+1)),convert_double4(texel(texels,offset,width,height,x+1,y+1)),px-x),py-y);
}
inline int plane_uv(int q,float3 origin,float3 direction,__private float2 *uv,__global const float *ff) {
    int f=q*40;float3 p=vload3(0,ff+f),a=vload3(0,ff+f+4)-p,b=vload3(0,ff+f+12)-p,n=vload3(0,ff+f+24);
    float denom=dot(n,direction);if(fabs(denom)<1e-10f)return 0;float t=dot(n,p-origin)/denom;if(t<0)return 0;
    float3 d=origin+t*direction-p;float aa=dot(a,a),bb=dot(b,b),ab=dot(a,b),det=aa*bb-ab*ab;if(fabs(det)<1e-20f)return 0;
    float u=(dot(d,a)*bb-dot(d,b)*ab)/det,v=(dot(d,b)*aa-dot(d,a)*ab)/det;
    *uv=(float2)(ff[f+16]+u*(ff[f+18]-ff[f+16])+v*(ff[f+22]-ff[f+16]),ff[f+17]+u*(ff[f+19]-ff[f+17])+v*(ff[f+23]-ff[f+17]));return 1;
}
__kernel void famara_opaque_photo(SCENE_ARGS,__global const int *levels,__global const float *texels,
    __global const double *lighting,__global float *rgb,__global int *errors,double yaw,double pitch,double fov) {
    int pixel=get_global_id(0),x=pixel&127,y=pixel>>7,error=0;double3 sum=(double3)(0);float3 origin=vload3(0,hf);
    for(int sample=0;sample<h[19];sample++) {
        double ox=h[19]==1?.5:((sample%2+.5)/2.0),oy=h[19]==1?.5:((sample/2+.5)/2.0);
        float3 direction=camera_direction(x,y,ox,oy,yaw,pitch,fov,h[21]);
        Trace hit=trace_ray(origin,direction,hf[6],0,0,0,1,SCENE_PASS);
        if(hit.error){error=hit.error;break;}
        if(hit.id<0){sum+=convert_double3(vload3(0,hf+9));continue;}
        int q=hit.face,texture=fi[q*16+3],row=textures[texture*12],filter=h[20];double lod=0;double2 centerUv;
        if(!plane_uv_double(q,convert_double3(origin),camera_double(x,y,ox,oy,yaw,pitch,fov,h[21]),&centerUv,ff,fi,instances)){error=5;break;}
        if(filter==2) {
            double2 a,b,uv;int width=textures[texture*12+8],height=textures[texture*12+9];double3 o=convert_double3(origin);
            if(!plane_uv_double(q,o,camera_double(x,y,ox,oy,yaw,pitch,fov,h[21]),&uv,ff,fi,instances) || !plane_uv_double(q,o,camera_double(x==127?x-1:x+1,y,ox,oy,yaw,pitch,fov,h[21]),&a,ff,fi,instances) || !plane_uv_double(q,o,camera_double(x,y==127?y-1:y+1,ox,oy,yaw,pitch,fov,h[21]),&b,ff,fi,instances))lod=log((double)max(width,height))/log(2.0);
            else {double dx=hypot((a.x-uv.x)*width,(a.y-uv.y)*height),dy=hypot((b.x-uv.x)*width,(b.y-uv.y)*height);lod=fmax(0.0,log(fmax(1.0,fmax(dx,dy)))/log(2.0));}
        }
        double4 color;
        if(filter==0 || (filter==2 && lod<=0))color=level_sample(centerUv.x,centerUv.y,row,0,levels,texels);
        else if(filter==1)color=level_sample(centerUv.x,centerUv.y,row,1,levels,texels);
        else {lod=clamp(lod,0.0,(double)(textures[texture*12+2]-1));int low=(int)floor(lod),high=min(low+1,textures[texture*12+2]-1);color=blend_double(level_sample(centerUv.x,centerUv.y,row+low,1,levels,texels),level_sample(centerUv.x,centerUv.y,row+high,1,levels,texels),lod-low);}
        if(color.w<1.0f-1e-6f){error=5;break;}
        sum+=color.xyz*vload3(0,lighting+q*3);
    }
    vstore3(convert_float3(sum/(double)h[19]),pixel,rgb);errors[pixel]=error;
}

inline double4 frame_sample(double2 uv,double lod,int filter,int row,int count,__global const int *levels,__global const float *texels) {
    if(filter==0 || (filter==2 && lod<=0))return level_sample(uv.x,uv.y,row,0,levels,texels);
    if(filter==1)return level_sample(uv.x,uv.y,row,1,levels,texels);
    lod=clamp(lod,0.0,(double)(count-1));int low=(int)floor(lod),high=min(low+1,count-1);
    return blend_double(level_sample(uv.x,uv.y,row+low,1,levels,texels),level_sample(uv.x,uv.y,row+high,1,levels,texels),lod-low);
}
inline double4 material_sample(int texture,double2 uv,double lod,int filter,long ticks,__global const int *textures,__global const int *levels,__global const int *timeline,__global const float *texels) {
    int at=texture*12,row=textures[at],mips=textures[at+2],first=textures[at+3],count=textures[at+4],position=0;
    long duration=as_long(((ulong)(uint)textures[at+11]<<32)|(ulong)(uint)textures[at+10]),phase=ticks%duration;if(phase<0)phase+=duration;
    while(position<count-1 && phase>=timeline[(first+position)*2+1]){phase-=timeline[(first+position)*2+1];position++;}
    int frame=timeline[(first+position)*2];double4 color=frame_sample(uv,lod,filter,row+frame*mips,mips,levels,texels);
    if(textures[at+5]){int next=timeline[(first+(position+1)%count)*2];color=blend_double(color,frame_sample(uv,lod,filter,row+next*mips,mips,levels,texels),(double)phase/(double)timeline[(first+position)*2+1]);}
    return color;
}
inline double3 rgb_linear(int rgb,__global const double *linear){return (double3)(linear[(rgb>>16)&255],linear[(rgb>>8)&255],linear[rgb&255]);}
inline double box_hit(double3 origin,double3 direction,double3 low,double3 high,double maximum) {
    double near=-INFINITY,far=maximum;
    for(int axis=0;axis<3;axis++) {
        if(direction[axis]==0){if(origin[axis]<low[axis] || origin[axis]>high[axis])return INFINITY;continue;}
        double a=(low[axis]-origin[axis])/direction[axis],b=(high[axis]-origin[axis])/direction[axis];near=fmax(near,fmin(a,b));far=fmin(far,fmax(a,b));if(near>far)return INFINITY;
    }
    double result=near>=0?near:far;return result>=0 && result<=maximum?result:INFINITY;
}
inline int order_before(int a,int b,double ta,double tb,__global const int *fi) {
    if(ta!=tb)return ta<tb;int ia=a*16,ib=b*16;
    return fi[ia]<fi[ib] || (fi[ia]==fi[ib] && (fi[ia+1]<fi[ib+1] || (fi[ia+1]==fi[ib+1] && (fi[ia+8]<fi[ib+8] || (fi[ia+8]==fi[ib+8] && fi[ia+2]<fi[ib+2])))));
}
__kernel void famara_feature_photo(SCENE_ARGS,__global const int *levels,__global const float *texels,__global const double *lighting,
    __global const int *timeline,__global const int *entityIds,__global const float *entityBoxes,__global const double *linear,
    __global float *rgb,__global int *errors,double yaw,double pitch,double fov) {
    int pixel=get_global_id(0),x=pixel&127,y=pixel>>7,error=0;double3 sum=(double3)(0),origin=convert_double3(vload3(0,hf));
    long ticks=as_long(((ulong)(uint)h[29]<<32)|(ulong)(uint)h[28]);
    if(h[11]>h[25]){errors[pixel]=4;return;}
    for(int sample=0;sample<h[19];sample++) {
        double ox=h[19]==1?.5:((sample%2+.5)/2.0),oy=h[19]==1?.5:((sample/2+.5)/2.0);
        double3 direction=camera_double(x,y,ox,oy,yaw,pitch,fov,h[21]);double entityDistance=INFINITY;int entityColor=-1;
        for(int i=0;i<h[11];i++){double t=box_hit(origin,direction,convert_double3(vload3(0,entityBoxes+i*8)),convert_double3(vload3(0,entityBoxes+i*8+4)),hf[6]);if(t<entityDistance){entityDistance=t;entityColor=entityIds[i*2+1];}}
        Trace hits={0};if(entityDistance!=0)hits=trace_ray(convert_float3(origin),convert_float3(direction),(float)fmin((double)hf[6],entityDistance),0,0,0,2,SCENE_PASS);
        if(hits.error){error=hits.error;break;}
        int order[64],count=0;double times[64];double2 coords[64];
        for(int i=0;i<hits.hits;i++) {
            int q=hits.faceList[i];double t;double2 uv;
            if(!plane_hit_double(q,origin,direction,&uv,&t,ff,fi,instances)){error=5;break;}
            if(t>entityDistance || t>(double)hf[6]+1e-9)continue;
            int at=count;while(at>0 && order_before(q,order[at-1],t,times[at-1],fi)){order[at]=order[at-1];times[at]=times[at-1];coords[at]=coords[at-1];at--;}
            order[at]=q;times[at]=t;coords[at]=uv;count++;
        }
        if(error)break;
        double3 result=(double3)(0);double transmission=1;int transparent=0;
        for(int first=0;first<count && transmission>1e-6;) {
            int end=first+1;while(end<count && fabs(times[end]-times[first])<=1e-9)end++;
            for(int hit=end-1;hit>=first && transmission>1e-6;hit--) {
                int q=order[hit],face=q*16,f=q*40;
                if(fi[face+9]&8){result+=transmission*convert_double3(vload3(0,ff+f+32));transmission=0;continue;}
                int texture=fi[face+3];double lod=0;double2 uv=coords[hit];
                if(h[20]==2) {
                    double2 a,b;int width=textures[texture*12+8],height=textures[texture*12+9];
                    if(!plane_uv_double(q,origin,camera_double(x==127?x-1:x+1,y,ox,oy,yaw,pitch,fov,h[21]),&a,ff,fi,instances) || !plane_uv_double(q,origin,camera_double(x,y==127?y-1:y+1,ox,oy,yaw,pitch,fov,h[21]),&b,ff,fi,instances))lod=log((double)max(width,height))/log(2.0);
                    else lod=fmax(0.0,log(fmax(1.0,fmax(hypot((a.x-uv.x)*width,(a.y-uv.y)*height),hypot((b.x-uv.x)*width,(b.y-uv.y)*height))))/log(2.0));
                }
                double4 color=material_sample(texture,uv,lod,h[20],ticks,textures,levels,timeline,texels);if(color.w<=0)continue;
                int mode=fi[face+15];if(mode==1 && color.w<(double)hf[8])continue;
                double alpha=mode==2?color.w*(double)ff[f+27]:1.0,factor=mode==2?(double)ff[f+27]:1.0/color.w;
                if(alpha<1 && ++transparent>h[26]){error=6;break;}
                result+=transmission*color.xyz*factor*vload3(0,lighting+q*3);transmission*=1.0-alpha;
            }
            if(error)break;first=end;
        }
        int bg=entityColor<0?as_int(hf[14]):entityColor;sum+=result+transmission*rgb_linear(bg,linear);if(error)break;
    }
    vstore3(convert_float3(sum/(double)h[19]),pixel,rgb);errors[pixel]=error;
}
