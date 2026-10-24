package com.wenkrang.famara.render.gpu;

import com.wenkrang.famara.render.asset.*;
import com.wenkrang.famara.render.kernel.*;
import com.wenkrang.famara.render.math.*;
import com.wenkrang.famara.render.scene.*;
import com.wenkrang.famara.render.schedule.RenderMemoryBudget;
import java.lang.foreign.Arena;
import java.nio.*;
import java.util.*;
import java.util.function.*;
import static com.wenkrang.famara.render.gpu.GpuScene.BufferKind.*;
import static com.wenkrang.famara.render.gpu.GpuScene.Feature.*;

/** No Bukkit access, resource parsing or native calls. Every large allocation is prepaid. */
public final class GpuSceneSerializer {
    private GpuSceneSerializer() {}
    public static Cursor cursor(SceneSnapshot scene,Camera camera,CpuPhotoRenderer.Settings settings,GpuConfiguration config,RenderMemoryBudget memory,BooleanSupplier cancelled) {
        return new Cursor(scene,camera,settings,config,memory,cancelled);
    }
    public static GpuScene serialize(SceneSnapshot scene,Camera camera,CpuPhotoRenderer.Settings settings,GpuConfiguration config,RenderMemoryBudget memory) {
        try(var cursor=cursor(scene,camera,settings,config,memory,()->false)){while(!cursor.step(256,Long.MAX_VALUE,System::nanoTime)){}return cursor.takeResult();}
    }
    private record Tex(int id,Texture texture,Texture.GpuLayout layout) {}
    public static final class Cursor implements AutoCloseable {
        private SceneSnapshot scene;
        private final Camera camera;
        private final CpuPhotoRenderer.Settings settings;
        private final GpuConfiguration config;
        private final RenderMemoryBudget memory;
        private final BooleanSupplier cancelled;
        private final SceneSnapshot.SpatialIndexLayout index;
        private final long limit,planningBytes;
        private final int baseX,baseY,baseZ;
        private final int[] instanceFaces;
        private final Map<ResourceKey,Tex> textures=new LinkedHashMap<>();
        private final EnumSet<GpuScene.Feature> features=EnumSet.noneOf(GpuScene.Feature.class);
        private final EnumMap<GpuScene.BufferKind,Integer> counts=new EnumMap<>(GpuScene.BufferKind.class);
        private final EnumMap<GpuScene.BufferKind,ByteBuffer> buffers=new EnumMap<>(GpuScene.BufferKind.class);
        private RenderMemoryBudget.Lease planning,data;
        private Arena arena;
        private int instance,model,quad,models,faces,boxes,levels,times;
        private int modelOut,faceOut,instanceFaceStart,instanceModelStart,modelFaceStart,textureOut;
        private long rgbaBytes,gpuBytes,reservationBytes;
        private Iterator<Map.Entry<ResourceKey,Tex>> textureIterator;
        private Texture.GpuExport textureExport;
        private IntBuffer iw,mi,fi,tw,levelWriter,timeWriter;
        private FloatBuffer mf,ff,pixelWriter;
        private DoubleBuffer preciseLight;
        private SceneSnapshot.SpatialIndexExport indexExport;
        private String phase="COUNT";
        private GpuScene result;
        private boolean closed;

        private Cursor(SceneSnapshot scene,Camera camera,CpuPhotoRenderer.Settings settings,GpuConfiguration config,RenderMemoryBudget memory,BooleanSupplier cancelled) {
            this.scene=Objects.requireNonNull(scene);this.camera=Objects.requireNonNull(camera);this.settings=Objects.requireNonNull(settings);this.config=Objects.requireNonNull(config);this.memory=Objects.requireNonNull(memory);this.cancelled=Objects.requireNonNull(cancelled);
            limit=config.stagingMemoryMiB()*1024L*1024;planningBytes=Math.min(4L*1024*1024,limit/4);
            planning=reserve(memory,"gpu-serialize-planning",planningBytes);
            try {
                baseX=base(camera.origin().x());baseY=base(camera.origin().y());baseZ=base(camera.origin().z());index=scene.spatialIndexLayout();
                if(64L*(index.sections()+index.knownSections()+index.occludedChunks())+scene.instances().size()*4L>planningBytes)throw oom("Index export planning exceeds staging envelope");
                instanceFaces=new int[scene.instances().size()];
                for(var entity:scene.entities())boxes=Math.addExact(boxes,entity.boxes().size());if(boxes>0)features.add(LEGACY_ENTITY);
                if((long)boxes*40>limit)throw oom("Entity boxes exceed staging envelope");
            }catch(RuntimeException | Error failure){planning.close();throw failure;}
        }
        public synchronized String phase(){return phase;}
        public synchronized boolean step(int operations,long deadline,LongSupplier clock) {
            if(closed)throw new IllegalStateException("Serializer cursor closed");if(operations<=0)throw new IllegalArgumentException("Positive serialization quantum required");
            try {
                for(int op=0;op<operations && clock.getAsLong()<deadline;op++) {
                    if(cancelled.getAsBoolean())throw new BackendException(BackendException.Code.CANCELLED,"Scene serialization cancelled");
                    switch(phase) {
                        case "COUNT" -> count();
                        case "ALLOCATE" -> allocate();
                        case "GEOMETRY" -> geometry();
                        case "TEXTURES" -> {if(!textureStep(1,deadline,clock))continue;phase="INDEX";}
                        case "INDEX" -> {if(!indexExport.step(1,deadline,clock))continue;phase="FINISH";}
                        case "FINISH" -> {finish();return true;}
                        case "READY" -> {return true;}
                        default -> throw new IllegalStateException(phase);
                    }
                }
                return phase.equals("READY");
            }catch(ArithmeticException overflow){close();throw new BackendException(BackendException.Code.OUT_OF_MEMORY,"GPU serialization length overflow",overflow);}
            catch(RuntimeException | Error failure){close();throw failure;}
        }
        private void count() {
            if(instance==scene.instances().size()) {
                var entries=new ArrayList<>(textures.entrySet());entries.sort(Comparator.comparing(e->e.getKey().toString()));textures.clear();
                int id=0;for(var entry:entries){Tex t=entry.getValue();textures.put(entry.getKey(),new Tex(id++,t.texture,t.layout));}
                phase="ALLOCATE";instance=0;model=0;quad=0;return;
            }
            var current=scene.instances().get(instance);
            if(model==0 && quad==0) {
                if(current.descriptor().kind()==VisualBlockDescriptor.Kind.CE)features.add(CE);
                if(current.farColors()!=null)features.add(FAR_LOD);
                if(FluidMesher.type(current.descriptor().visualState())!=null)features.add(FLUID);
                if(current.models().size()!=1 || current.models().getFirst().quads().size()!=6 || !OpaqueShapes.fullCube(current.models(),scene::texture))features.add(NON_CUBE);
            }
            if(model==current.models().size()){instance++;model=0;quad=0;return;}
            var baked=current.models().get(model);
            if(quad==0)models=Math.addExact(models,1);
            if(quad==baked.quads().size()){model++;quad=0;return;}
            var face=baked.quads().get(quad++);faces=Math.addExact(faces,1);
            instanceFaces[instance]++;
            if((long)faces*(GpuScene.FACE_FLOAT_STRIDE+GpuScene.FACE_INT_STRIDE)*4>limit)throw oom("Geometry exceeds staging limit");
            if(face.topology()==BakedQuad.Topology.TRIANGLE)features.add(TRIANGLE);
            Tex tex=textures.get(face.texture());
            if(tex==null) {
                if((textures.size()+1L)*1024+instanceFaces.length*4L+64L*(index.sections()+index.knownSections()+index.occludedChunks())>planningBytes)throw oom("Texture metadata exceeds planning reservation");
                Texture texture=scene.texture(face.texture());var layout=texture.gpuLayout();tex=new Tex(-1,texture,layout);textures.put(face.texture(),tex);
                rgbaBytes=Math.addExact(rgbaBytes,layout.rgbaBytes());levels=Math.addExact(levels,Math.multiplyExact(layout.frames(),layout.levels()));times=Math.addExact(times,layout.timelineEntries());
                if(rgbaBytes>limit)throw oom("Texture atlas exceeds staging limit");
                if(layout.frames()>1 || layout.timelineEntries()>1 || layout.interpolate())features.add(ANIMATED);
            }
            var mode=alpha(face,tex.texture);if(mode==CpuPhotoRenderer.AlphaMode.TRANSLUCENT)features.add(TRANSLUCENT);if(mode==CpuPhotoRenderer.AlphaMode.CUTOUT)features.add(CUTOUT);
        }
        private void size(GpuScene.BufferKind key,long values){counts.put(key,Math.toIntExact(values));}
        private void allocate() {
            size(HEADER_INT,32);size(HEADER_FLOAT,16);size(INSTANCES,(long)scene.instances().size()*GpuScene.INSTANCE_STRIDE);
            size(MODELS_INT,(long)models*GpuScene.MODEL_INT_STRIDE);size(MODELS_FLOAT,(long)models*GpuScene.MODEL_FLOAT_STRIDE);
            size(FACES_INT,(long)faces*GpuScene.FACE_INT_STRIDE);size(FACES_FLOAT,(long)faces*GpuScene.FACE_FLOAT_STRIDE);size(LIGHTING_DOUBLE,(long)faces*6);
            size(TEXTURES,(long)textures.size()*GpuScene.TEXTURE_STRIDE);size(LEVELS,(long)levels*4);size(TIMELINE,(long)times*2);size(TEXELS,rgbaBytes/4);
            size(SECTIONS,(long)index.sections()*6);size(HEADS,(long)index.sections()*4096);size(NODES,(long)index.nodes()*2);
            size(KNOWN,(long)index.knownSections()*3);size(OCCLUDED,(long)index.occludedChunks()*3);size(ENTITY_INT,(long)boxes*2);size(ENTITY_FLOAT,(long)boxes*8);
            gpuBytes=0;for(int count:counts.values())gpuBytes=Math.addExact(gpuBytes,Math.max(4,Math.multiplyExact((long)count,4)));
            long metadata=Math.addExact(65536L,32L*faces+64L*scene.instances().size()+128L*models+1024L*textures.size()+64L*(index.sections()+index.knownSections()+index.occludedChunks()));
            reservationBytes=Math.addExact(gpuBytes,Math.max(planningBytes,metadata));
            if(reservationBytes>limit-1024*1024)throw oom("Scene staging plus native I/O headroom exceeds configured cap: "+reservationBytes+" > "+limit);
            if(gpuBytes>config.vramBudgetMiB()*1024L*1024)throw oom("Scene exceeds plugin VRAM budget");
            data=reserve(memory,"gpu-serialize-data",reservationBytes-planningBytes);arena=Arena.ofShared();
            for(var key:GpuScene.BufferKind.values())buffers.put(key,arena.allocate(Math.max(4,counts.get(key)*4L),16).asByteBuffer().order(ByteOrder.nativeOrder()));
            iw=wi(INSTANCES);mi=wi(MODELS_INT);mf=wf(MODELS_FLOAT);fi=wi(FACES_INT);ff=wf(FACES_FLOAT);tw=wi(TEXTURES);pixelWriter=wf(TEXELS);levelWriter=wi(LEVELS);timeWriter=wi(TIMELINE);
            preciseLight=buffers.get(LIGHTING_DOUBLE).asDoubleBuffer();
            header();entities();indexExport=scene.spatialIndexExport(wi(SECTIONS),wi(HEADS),wi(NODES),wi(KNOWN),wi(OCCLUDED));
            phase="GEOMETRY";
        }
        private IntBuffer wi(GpuScene.BufferKind kind){return buffers.get(kind).asIntBuffer();}
        private FloatBuffer wf(GpuScene.BufferKind kind){return buffers.get(kind).asFloatBuffer();}
        private void header() {
            IntBuffer h=wi(HEADER_INT);int featureBits=0;for(var feature:features)featureBits|=1<<feature.ordinal();
            h.put(GpuScene.VERSION).put(scene.instances().size()).put(models).put(faces).put(textures.size()).put(levels).put(times).put(index.sections()).put(index.nodes()).put(index.knownSections()).put(index.occludedChunks()).put(boxes)
                    .put(baseX).put(baseY).put(baseZ).put(scene.minHeight()).put(scene.maxHeight()).put(scene.worldMinHeight()).put(scene.worldMaxHeight()).put(settings.samples()).put(settings.filter().ordinal()).put(camera.projection().ordinal())
                    .put(settings.directionalLight()?1:0).put(settings.traceLimits().maxCells()).put(settings.traceLimits().maxIntersections()).put(settings.traceLimits().maxTests()).put(settings.maxTransparentLayers()).put(4096)
                    .put((int)settings.animationTicks()).put((int)(settings.animationTicks()>>>32)).put(featureBits).put(scene.entities().size());
            wf(HEADER_FLOAT).put(f(camera.origin().x()-baseX)).put(f(camera.origin().y()-baseY)).put(f(camera.origin().z()-baseZ)).put(f(camera.yaw())).put(f(camera.pitch())).put(f(camera.horizontalFov())).put(f(settings.distance())).put(f(settings.ambientFloor())).put(f(settings.cutoutThreshold()))
                    .put(f(Texture.linear(settings.backgroundRgb()>>16&255))).put(f(Texture.linear(settings.backgroundRgb()>>8&255))).put(f(Texture.linear(settings.backgroundRgb()&255))).put(f((double)scene.minHeight()-baseY)).put(f((double)scene.maxHeight()-baseY));
            wi(HEADER_FLOAT).put(14,settings.backgroundRgb());
        }
        private void entities() {
            var integers=wi(ENTITY_INT);var values=wf(ENTITY_FLOAT);int id=0;
            for(var entity:scene.entities()){for(var box:entity.boxes()){integers.put(id).put(entity.rgb());position(values,box.min(),0,0,0);values.put(0);position(values,box.max(),0,0,0);values.put(0);}id++;}
        }
        // Geometry and texture copying below each perform one bounded unit per step.
        private void geometry() {
            if(instance==scene.instances().size()){phase="TEXTURES";textureIterator=textures.entrySet().iterator();return;}
            var current=scene.instances().get(instance);
            if(model==0 && quad==0) {
                instanceFaceStart=faceOut;instanceModelStart=modelOut;
                iw.put(current.id()).put(relative(current.x(),baseX)).put(relative(current.y(),baseY)).put(relative(current.z(),baseZ)).put(instanceFaceStart).put(instanceFaces[instance]).put(instanceModelStart).put(current.models().size())
                        .put(current.descriptor().kind().ordinal()).put(current.farColors()==null?0:1).put(0).put(0);
            }
            if(model==current.models().size()){instance++;model=0;quad=0;return;}
            var baked=current.models().get(model);
            if(quad==0) {
                modelFaceStart=faceOut;mi.put(current.id()).put(modelFaceStart).put(baked.quads().size()).put(model);
                position(mf,baked.min(),current.x(),current.y(),current.z());mf.put(0);position(mf,baked.max(),current.x(),current.y(),current.z());mf.put(0);modelOut++;
            }
            if(quad==baked.quads().size()){model++;quad=0;return;}
            int quadIndex=quad++;BakedQuad face=baked.quads().get(quadIndex);Tex tex=textures.get(face.texture());
            var lighting=current.lighting();var light=lighting.face(face.face());
            int flags=(face.shade()?1:0)|(face.forceTranslucent()?2:0)|(scene.shouldCull(current,face)?4:0)|(current.farColors()!=null?8:0);
            fi.put(current.id()).put(model).put(quadIndex).put(tex.id).put(face.face().ordinal()).put(face.cullFace()==null?-1:face.cullFace().ordinal()).put(face.topology().ordinal()).put(face.surfaceGroup()).put(face.layer()).put(flags)
                    .put(face.tintIndex()).put(face.colorMultiplier()).put(face.lightEmission()).put(light.sky()).put(light.block()).put(alpha(face,tex.texture).ordinal());
            for(var vertex:face.positions()){position(ff,vertex,current.x(),current.y(),current.z());ff.put(0);}
            for(var uv:face.uv())ff.put(f(uv.u())).put(f(uv.v()));
            ff.put(f(face.normal().x())).put(f(face.normal().y())).put(f(face.normal().z())).put(f(face.opacity()));
            Vec3 shade=face.shadeDirectionOverride()==null?face.normal():face.shadeDirectionOverride().vector();
            double intensity=Math.max(light.sky(),Math.max(light.block(),face.lightEmission()))/15.0;
            intensity=settings.ambientFloor()+(1-settings.ambientFloor())*intensity;
            if(settings.directionalLight() && face.shade())intensity*=shade.x()*shade.x()*.6+shade.z()*shade.z()*.8+shade.y()*shade.y()*(shade.y()>0?1:.5);
            int tint=face.tintIndex()<0?0xffffff:lighting.tintRgb();
            for(int shift=16;shift>=0;shift-=8){double lightValue=intensity*Texture.linear(tint>>shift&255)*Texture.linear(face.colorMultiplier()>>shift&255);ff.put(f(lightValue));preciseLight.put(lightValue);}ff.put(0);
            if(current.farColors()==null)ff.put(0).put(0).put(0).put(0);
            else{var c=current.farColors().face(face.face());ff.put(f(c.r())).put(f(c.g())).put(f(c.b())).put(f(c.a()));}
            ff.put(f(shade.x())).put(f(shade.y())).put(f(shade.z())).put(0);faceOut++;
        }
        private boolean textureStep(int operations,long deadline,LongSupplier clock) {
            if(textureExport!=null){if(!textureExport.step(operations,deadline,clock))return false;textureExport=null;textureOut++;}
            if(!textureIterator.hasNext())return true;
            Tex texture=textureIterator.next().getValue();var layout=texture.layout;
            if(texture.id!=textureOut)throw new IllegalStateException("Unstable texture IDs");
            tw.put(levelWriter.position()/4).put(layout.frames()).put(layout.levels()).put(timeWriter.position()/2).put(layout.timelineEntries()).put(layout.interpolate()?1:0).put(layout.opaque()?1:0).put(layout.fractionalAlpha()?1:0)
                    .put(layout.width()).put(layout.height()).put((int)layout.duration()).put((int)(layout.duration()>>>32));
            textureExport=texture.texture.gpuExport(pixelWriter,levelWriter,timeWriter);return false;
        }
        private void finish() {
            if(faceOut!=faces || modelOut!=models || iw.position()!=counts.get(INSTANCES) || mi.position()!=counts.get(MODELS_INT) || mf.position()!=counts.get(MODELS_FLOAT)
                    || preciseLight.position()!=faces*3 || fi.position()!=counts.get(FACES_INT) || ff.position()!=counts.get(FACES_FLOAT) || pixelWriter.position()!=counts.get(TEXELS) || levelWriter.position()!=counts.get(LEVELS) || timeWriter.position()!=counts.get(TIMELINE))
                throw new IllegalStateException("GPU serialization length mismatch");
            String atlasKey=atlasKey(textures.keySet());
            result=new GpuScene(arena,planning,data,buffers,new GpuScene.Metadata(scene.assets().id(),scene.assets().fingerprint(),gpuBytes,reservationBytes,scene.instances().size(),models,faces,textures.size(),boxes,atlasKey,1e-4),features,new GpuScene.PhotoCamera(camera.yaw(),camera.pitch(),camera.horizontalFov()));phase="READY";
        }
        private static int relative(int value,int origin){try{return Math.subtractExact(value,origin);}catch(ArithmeticException failure){throw new BackendException(BackendException.Code.UNSUPPORTED,"Camera-relative integer coordinate overflow",failure);}}
        private void position(FloatBuffer target,Vec3 value,int x,int y,int z){target.put(f((long)x-baseX+value.x())).put(f((long)y-baseY+value.y())).put(f((long)z-baseZ+value.z()));}
        private static int base(double value){if(!Double.isFinite(value) || value<Integer.MIN_VALUE+1.0 || value>Integer.MAX_VALUE-1.0)throw new BackendException(BackendException.Code.UNSUPPORTED,"Camera origin outside integer grid");return (int)Math.floor(value);}
        private static float f(double value){float result=(float)value;if(!Float.isFinite(result) || Math.abs((double)result-value)>1e-4)throw new BackendException(BackendException.Code.UNSUPPORTED,"float32 precision envelope exceeded");return result;}
        private CpuPhotoRenderer.AlphaMode alpha(BakedQuad face,Texture texture){return settings.materialOverrides().getOrDefault(face.texture(),face.opacity()<1 || face.forceTranslucent() || texture.fractionalAlpha()?CpuPhotoRenderer.AlphaMode.TRANSLUCENT:texture.opaque()?CpuPhotoRenderer.AlphaMode.OPAQUE:CpuPhotoRenderer.AlphaMode.CUTOUT);}
        public synchronized GpuScene takeResult(){if(closed || result==null)throw new IllegalStateException("Serialization is not ready");var ready=result;result=null;arena=null;planning=null;data=null;close();return ready;}
        @Override public synchronized void close(){if(closed)return;closed=true;if(result!=null){result.close();result=null;arena=null;planning=null;data=null;}try{if(arena!=null)arena.close();}finally{try{if(data!=null)data.close();}finally{if(planning!=null)planning.close();}}textures.clear();buffers.clear();scene=null;}
    }
    private static String atlasKey(Set<ResourceKey> keys) {
        try{var digest=java.security.MessageDigest.getInstance("SHA-256");for(var key:keys){digest.update(key.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));digest.update((byte)0);}return HexFormat.of().formatHex(digest.digest());}
        catch(java.security.NoSuchAlgorithmException error){throw new IllegalStateException(error);}
    }
    private static BackendException oom(String message){return new BackendException(BackendException.Code.OUT_OF_MEMORY,message);}
    private static RenderMemoryBudget.Lease reserve(RenderMemoryBudget memory,String domain,long bytes) {
        try{return memory.reserve(domain,bytes);}
        catch(java.util.concurrent.RejectedExecutionException failure){throw new BackendException(BackendException.Code.OUT_OF_MEMORY,"CPU staging ledger refused "+domain,failure);}
    }
}
