package com.wenkrang.famara.render.gpu;

import com.wenkrang.famara.render.asset.*;
import com.wenkrang.famara.render.kernel.*;
import com.wenkrang.famara.render.scene.SceneSnapshot;
import java.util.*;
import java.util.function.LongSupplier;

/** GPU-first routing with resource guards; timing estimates are diagnostic only. */
public final class GpuAutoPolicy {
    public record Input(long faces,long textureBytes,long sceneBytes,int instances,int samples,double distance,Texture.Filter filter,boolean complex,boolean uncertain,String fingerprint,int cpuLanes,int batchPixels) {}
    public record Decision(boolean gpu,String reason,double cpuMillis,double gpuMillis) {}
    private record Key(int faces,int textures,int distance,int samples,Texture.Filter filter,boolean complex,String fingerprint) {}
    private static final class History {double cpu=Double.NaN,gpu=Double.NaN;long gpuAt,disabledUntil;}
    private final LinkedHashMap<Key,History> history=new LinkedHashMap<>(16,.75f,true);
    private final LongSupplier clock;
    public GpuAutoPolicy(){this(System::nanoTime);}
    GpuAutoPolicy(LongSupplier clock){this.clock=Objects.requireNonNull(clock);}
    public static Input inspect(SceneSnapshot scene,CpuPhotoRenderer.Settings settings,int lanes,int pixels) {
        long faces=0,textureBytes=0;int sampled=0,models=0,faceSamples=0;boolean complex=!scene.entities().isEmpty(),uncertain=false;Set<ResourceKey> textures=new HashSet<>();
        outer:for(var instance:scene.instances()) {
            if(sampled==128)break;sampled++;
            if(instance.models().size()>64){uncertain=true;break;}
            complex|=instance.descriptor().kind()==VisualBlockDescriptor.Kind.CE || instance.farColors()!=null || instance.models().size()!=1;
            for(var model:instance.models()) {
                if(++models>256){uncertain=true;break outer;}faces+=model.quads().size();complex|=model.quads().size()!=6;
                for(var face:model.quads()) {
                    if(faceSamples++>=1024)break;
                    complex|=face.topology()==BakedQuad.Topology.TRIANGLE || face.opacity()<1 || face.forceTranslucent();
                    if(textures.size()<64 && textures.add(face.texture())){var texture=scene.texture(face.texture());var layout=texture.gpuLayout();textureBytes=Math.addExact(textureBytes,layout.rgbaBytes());complex|=!texture.opaque() || layout.frames()>1;}
                }
            }
        }
        long projected=sampled==0?0:(long)Math.ceil((double)faces*scene.instances().size()/sampled);
        return new Input(projected,textureBytes,scene.estimatedBytes(),scene.instances().size(),settings.samples(),settings.distance(),settings.filter(),complex,uncertain,scene.assets().fingerprint(),lanes,pixels);
    }
    private static int bucket(long value){return value<=0?0:64-Long.numberOfLeadingZeros(value);}
    private static Key key(Input input){return new Key(bucket(input.faces),bucket(input.textureBytes),bucket((long)Math.ceil(input.distance)),input.samples,input.filter,input.complex,input.fingerprint);}
    private static double mix(double old,double value){return Double.isFinite(old)?old*.75+value*.25:value;}
    private History entry(Input input){var key=key(input);var value=history.computeIfAbsent(key,k->new History());while(history.size()>128)history.remove(history.keySet().iterator().next());return value;}
    public synchronized Decision select(Input input,GpuConfiguration config,boolean available,String device,int activeJobs,long hostHeadroom,long vramHeadroom) {
        if(!available)return new Decision(false,"OPENCL_UNAVAILABLE",0,0);
        if(activeJobs>0)return new Decision(true,"AUTO_GPU_QUEUE",0,0);
        double sceneBytes=input.faces*248.0+input.textureBytes+input.sceneBytes*1.25+7*1024*1024;
        if(sceneBytes>config.stagingMemoryMiB()*1024.0*1024 || sceneBytes>hostHeadroom)return new Decision(false,"AUTO_HOST_MEMORY",0,0);
        if(sceneBytes+(input.complex?64L*1024*1024:0)>vramHeadroom)return new Decision(false,"AUTO_VRAM_HEADROOM",0,0);
        boolean dense=!input.complex && input.faces>=1024 && input.distance>=64;
        double cpu=(dense?8:input.samples==1 && input.distance>=64?7:14)+input.samples*(dense?.13:.28)*input.distance;
        cpu*=2.0/Math.max(1,input.cpuLanes);cpu+=Math.max(0,Math.ceil(16384.0/(Math.max(1,input.cpuLanes)*input.batchPixels))-8);
        double gpu=2.5+input.samples*(1.4+(dense?.12:.18)*input.distance)+input.faces*.0003+input.textureBytes/(1024.0*1024)*.5;
        var observed=history.get(key(input));long now=clock.getAsLong();
        if(observed!=null){if(now<observed.disabledUntil)return new Decision(false,"AUTO_SCENE_BACKOFF",cpu,gpu);if(Double.isFinite(observed.cpu))cpu=observed.cpu;if(Double.isFinite(observed.gpu) && now-observed.gpuAt<30_000_000_000L)gpu=observed.gpu;}
        return new Decision(true,"AUTO_GPU_FIRST",cpu,gpu);
    }
    public synchronized void observe(Input input,boolean gpu,long wallNanos){if(wallNanos<0)return;var value=entry(input);if(gpu){value.gpu=mix(value.gpu,wallNanos/1e6);value.gpuAt=clock.getAsLong();}else value.cpu=mix(value.cpu,wallNanos/1e6);}
    public synchronized void failed(Input input){entry(input).disabledUntil=clock.getAsLong()+30_000_000_000L;}
    synchronized int entries(){return history.size();}
}
