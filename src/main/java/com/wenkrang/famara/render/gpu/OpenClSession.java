package com.wenkrang.famara.render.gpu;

import com.wenkrang.famara.render.schedule.RenderMemoryBudget;
import org.jocl.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.lang.management.ManagementFactory;
import java.util.*;
import static org.jocl.CL.*;

/** All native operations are confined to the device worker that creates this session. */
final class OpenClSession implements GpuDeviceProbe.Session {
    private GpuOwnerLock.Lease ownerLock;
    private final Thread owner=Thread.currentThread();
    private final RenderMemoryBudget host;
    private final GpuMemoryBudget memory;
    private final GpuDevice description;
    private cl_context context;
    private cl_command_queue queue;
    private cl_program program;
    private cl_kernel kernel;
    private cl_device_id nativeDevice;
    private cl_program sceneProgram;
    private cl_kernel candidateKernel,traceKernel,listKernel,photoKernel,featureKernel;
    private final long textureCacheLimit;
    private GpuTextureCache<NativeAtlas> textureCache,geometryCache;
    private RenderMemoryBudget.Lease cacheMetadata;
    private long featurePrivatePerPixel=4096;
    private NativeBuffer photoRgb,photoErrors,linearTable;

    static GpuDeviceProbe.Session open(GpuConfiguration config,RenderMemoryBudget host,java.nio.file.Path lockPath) {
        var lease=GpuOwnerLock.acquire(lockPath);
        try{var session=(OpenClSession)open(config,host);session.ownerLock=lease;return session;}
        catch(Throwable failure){try{lease.close();}catch(Throwable cleanup){failure.addSuppressed(cleanup);}throw failure;}
    }
    static GpuDeviceProbe.Session open(GpuConfiguration config,RenderMemoryBudget host) {
        // JOCL supports unique extraction names, preventing JNI collisions with retiring loaders.
        System.getProperties().putIfAbsent("uniqueLibraryNames","true");
        CL.setExceptionsEnabled(false);
        int[] count=new int[1];check(clGetPlatformIDs(0,null,count),"platform discovery");
        if(count[0]<1 || count[0]>32)throw unavailable("No bounded OpenCL platform list");
        var platforms=new cl_platform_id[count[0]];check(clGetPlatformIDs(platforms.length,platforms,null),"platform list");
        for(var platform:platforms) {
            if(!platformString(platform,CL_PLATFORM_VENDOR).toLowerCase(Locale.ROOT).contains("nvidia"))continue;
            int error=clGetDeviceIDs(platform,CL_DEVICE_TYPE_GPU,0,null,count);if(error==CL_DEVICE_NOT_FOUND)continue;check(error,"GPU discovery");
            if(count[0]<1 || count[0]>32)continue;
            var devices=new cl_device_id[count[0]];check(clGetDeviceIDs(platform,CL_DEVICE_TYPE_GPU,devices.length,devices,null),"GPU list");
            for(var device:devices) {
                String name=deviceString(device,CL_DEVICE_NAME);
                if(!name.toLowerCase(Locale.ROOT).contains(config.preferredDevice().strip().toLowerCase(Locale.ROOT)))continue;
                if(uint(device,CL_DEVICE_AVAILABLE)==0 || uint(device,CL_DEVICE_COMPILER_AVAILABLE)==0 || uint(device,CL_DEVICE_ADDRESS_BITS)!=64)
                    throw unavailable("Device unavailable, lacks compiler, or is not 64-bit");
                String version=deviceString(device,CL_DEVICE_VERSION);
                var parsed=java.util.regex.Pattern.compile("OpenCL (\\d+)\\.(\\d+)").matcher(version);
                if(!parsed.find() || Integer.parseInt(parsed.group(1))<1 || Integer.parseInt(parsed.group(1))==1 && Integer.parseInt(parsed.group(2))<2)
                    throw unavailable("OpenCL 1.2 or newer is required");
                var description=new GpuDevice(platformString(platform,CL_PLATFORM_NAME),deviceString(device,CL_DEVICE_VENDOR),name,version,deviceString(device,CL_DRIVER_VERSION),
                        ulong(device,CL_DEVICE_GLOBAL_MEM_SIZE),ulong(device,CL_DEVICE_MAX_MEM_ALLOC_SIZE),uint(device,CL_DEVICE_ADDRESS_BITS),ulong(device,CL_DEVICE_SINGLE_FP_CONFIG),ulong(device,CL_DEVICE_DOUBLE_FP_CONFIG));
                if(description.floatCapabilities()==0)throw unavailable("float32 unavailable");
                long bytes=config.vramBudgetMiB()*1024L*1024;
                if(bytes>description.globalMemoryBytes())throw unavailable("Configured VRAM budget exceeds device memory");
                var session=new OpenClSession(host,new GpuMemoryBudget(bytes,description.maximumAllocationBytes()),description,config.textureCacheMiB()*1024L*1024);
                try{session.initialize(platform,device);return session;}
                catch(Throwable failure){try{session.close();}catch(Throwable cleanup){failure.addSuppressed(cleanup);}throw failure;}
            }
        }
        throw unavailable("No matching NVIDIA GPU: "+config.preferredDevice());
    }
    private OpenClSession(RenderMemoryBudget host,GpuMemoryBudget memory,GpuDevice description,long textureCacheLimit){this.host=host;this.memory=memory;this.description=description;this.textureCacheLimit=textureCacheLimit;}
    private void initialize(cl_platform_id platform,cl_device_id device) {
        nativeDevice=device;
        int[] error=new int[1];var properties=new cl_context_properties();properties.addProperty(CL_CONTEXT_PLATFORM,platform);
        context=clCreateContext(properties,1,new cl_device_id[]{device},null,null,error);check(error[0],"context creation");
        // OpenCL 1.2 in-order queue avoids depending on optional OpenCL 2.x device features.
        queue=clCreateCommandQueue(context,device,CL_QUEUE_PROFILING_ENABLE,error);check(error[0],"queue creation");
        String source;
        try(InputStream in=OpenClSession.class.getResourceAsStream("/opencl/smoke.cl")) {
            if(in==null)throw unavailable("Missing bundled smoke kernel");byte[] data=in.readNBytes(262145);
            if(data.length>262144)throw unavailable("Oversized kernel source");source=new String(data,StandardCharsets.UTF_8);
        }catch(IOException failure){throw new BackendException(BackendException.Code.UNAVAILABLE,"Kernel source read failed",failure);}
        program=clCreateProgramWithSource(context,1,new String[]{source},null,error);check(error[0],"program creation");
        int built=clBuildProgram(program,1,new cl_device_id[]{device},"-cl-std=CL1.2",null,null);
        if(built!=CL_SUCCESS) {
            long[] size=new long[1];clGetProgramBuildInfo(program,device,CL_PROGRAM_BUILD_LOG,0,null,size);
            byte[] log=new byte[(int)Math.min(Math.max(1,size[0]),65536)];
            clGetProgramBuildInfo(program,device,CL_PROGRAM_BUILD_LOG,log.length,Pointer.to(log),null);
            throw new BackendException(BackendException.Code.UNAVAILABLE,"OpenCL kernel compile failed: "+new String(log,StandardCharsets.UTF_8));
        }
        kernel=clCreateKernel(program,"famara_rgb_smoke",error);check(error[0],"kernel creation");
    }
    @Override public GpuDevice device(){return description;}
    @Override public GpuMemoryBudget.Snapshot memory(){return memory.snapshot();}
    @Override public GpuDeviceProbe.Smoke smoke() {
        owner();if(context==null)throw new BackendException(BackendException.Code.CLOSED,"OpenCL session closed");
        final int bytes=128*128*3;
        long wall=System.nanoTime();var meter=ManagementFactory.getThreadMXBean();long cpu=meter.getCurrentThreadCpuTime();
        byte[] output;long kernelNanos,readbackNanos;
        try(var staging=host.reserve("gpu-smoke-staging",192*1024);var allocation=memory.reserve("smoke-output",bytes)) {
            cl_mem buffer=null;cl_event event=new cl_event();boolean enqueued=false;
            try {
                int[] error=new int[1];buffer=clCreateBuffer(context,CL_MEM_WRITE_ONLY,bytes,null,error);check(error[0],"buffer allocation");
                check(clSetKernelArg(kernel,0,Sizeof.cl_mem,Pointer.to(buffer)),"kernel argument");
                check(clEnqueueNDRangeKernel(queue,kernel,1,null,new long[]{16384},null,0,null,event),"kernel submission");enqueued=true;
                byte[] rgb=new byte[bytes];long readStart=System.nanoTime();
                check(clEnqueueReadBuffer(queue,buffer,true,0,bytes,Pointer.to(rgb),0,null,null),"RGB readback");long readback=System.nanoTime()-readStart;
                long[] start=new long[1],end=new long[1];check(clGetEventProfilingInfo(event,CL_PROFILING_COMMAND_START,Sizeof.cl_ulong,Pointer.to(start),null),"profiling start");
                check(clGetEventProfilingInfo(event,CL_PROFILING_COMMAND_END,Sizeof.cl_ulong,Pointer.to(end),null),"profiling end");
                for(int i=0;i<16384;i++)if((rgb[3*i]&255)!=(i&127) || (rgb[3*i+1]&255)!=(i>>7) || rgb[3*i+2]!=42)
                    throw new BackendException(BackendException.Code.DEVICE_ERROR,"OpenCL smoke RGB mismatch at pixel "+i);
                output=rgb;kernelNanos=end[0]-start[0];readbackNanos=readback;
            }finally {
                try{if(enqueued)check(clReleaseEvent(event),"event release");}
                finally{if(buffer!=null)check(clReleaseMemObject(buffer),"buffer release");}
            }
        }
        return new GpuDeviceProbe.Smoke(output,kernelNanos,readbackNanos,System.nanoTime()-wall,meter.getCurrentThreadCpuTime()-cpu);
    }
    @Override public void warm(){sceneKernels();}
    private void sceneKernels() {
        owner();if(traceKernel!=null && candidateKernel!=null && listKernel!=null && photoKernel!=null && featureKernel!=null)return;
        if(photoKernel!=null){check(clReleaseKernel(photoKernel),"failed photo release");photoKernel=null;}
        if(listKernel!=null){check(clReleaseKernel(listKernel),"failed list release");listKernel=null;}
        if(traceKernel!=null){check(clReleaseKernel(traceKernel),"failed trace release");traceKernel=null;}
        if(candidateKernel!=null){check(clReleaseKernel(candidateKernel),"failed candidate release");candidateKernel=null;}
        if(sceneProgram!=null){check(clReleaseProgram(sceneProgram),"failed program release");sceneProgram=null;}
        String source;
        try(var in=OpenClSession.class.getResourceAsStream("/opencl/trace.cl")) {
            if(in==null)throw unavailable("Missing trace kernel");byte[] bytes=in.readNBytes(262145);
            if(bytes.length>262144)throw unavailable("Oversized trace kernel");source=new String(bytes,StandardCharsets.UTF_8);
        }catch(IOException error){throw new BackendException(BackendException.Code.UNAVAILABLE,"Trace kernel read failed",error);}
        int[] error=new int[1];
        sceneProgram=clCreateProgramWithSource(context,1,new String[]{source},null,error);check(error[0],"trace program");
        int built=clBuildProgram(sceneProgram,1,new cl_device_id[]{nativeDevice},"-cl-std=CL1.2",null,null);
        if(built!=CL_SUCCESS) {
            long[] size=new long[1];clGetProgramBuildInfo(sceneProgram,nativeDevice,CL_PROGRAM_BUILD_LOG,0,null,size);
            byte[] log=new byte[(int)Math.min(65536,Math.max(1,size[0]))];
            clGetProgramBuildInfo(sceneProgram,nativeDevice,CL_PROGRAM_BUILD_LOG,log.length,Pointer.to(log),null);
            throw unavailable("Trace compile failed: "+new String(log,StandardCharsets.UTF_8));
        }
        candidateKernel=clCreateKernel(sceneProgram,"famara_candidates",error);check(error[0],"candidate kernel");
        traceKernel=clCreateKernel(sceneProgram,"famara_trace_probe",error);check(error[0],"trace kernel");
        listKernel=clCreateKernel(sceneProgram,"famara_hit_lists",error);check(error[0],"hit list kernel");
        photoKernel=clCreateKernel(sceneProgram,"famara_opaque_photo",error);check(error[0],"photo kernel");
        featureKernel=clCreateKernel(sceneProgram,"famara_feature_photo",error);check(error[0],"feature kernel");
        long[] privateBytes=new long[1];check(clGetKernelWorkGroupInfo(featureKernel,nativeDevice,CL_KERNEL_PRIVATE_MEM_SIZE,Sizeof.cl_ulong,Pointer.to(privateBytes),null),"feature private memory");featurePrivatePerPixel=Math.max(4096,privateBytes[0]);
    }
    private final class NativeBuffer implements AutoCloseable {
        cl_mem buffer;GpuMemoryBudget.Lease lease;
        NativeBuffer(String domain,long bytes,Pointer input) {
            lease=memory.reserve(domain,bytes);int[] error=new int[1];
            try{buffer=clCreateBuffer(context,input==null?CL_MEM_READ_WRITE:CL_MEM_READ_ONLY|CL_MEM_COPY_HOST_PTR,bytes,input,error);check(error[0],"scene buffer "+domain);}
            catch(Throwable failure){close();throw failure;}
        }
        @Override public void close(){try{if(buffer!=null){var release=buffer;buffer=null;check(clReleaseMemObject(release),"scene buffer release");}}finally{if(lease!=null){lease.close();lease=null;}}}
    }
    private static final Set<GpuScene.BufferKind> ATLAS_KINDS=EnumSet.of(GpuScene.BufferKind.TEXTURES,GpuScene.BufferKind.LEVELS,GpuScene.BufferKind.TIMELINE,GpuScene.BufferKind.TEXELS);
    private static final Set<GpuScene.BufferKind> GEOMETRY_KINDS=EnumSet.complementOf(EnumSet.of(GpuScene.BufferKind.HEADER_INT,GpuScene.BufferKind.HEADER_FLOAT,GpuScene.BufferKind.TEXTURES,GpuScene.BufferKind.LEVELS,GpuScene.BufferKind.TIMELINE,GpuScene.BufferKind.TEXELS));
    private final class NativeAtlas implements AutoCloseable {
        final EnumMap<GpuScene.BufferKind,NativeBuffer> buffers=new EnumMap<>(GpuScene.BufferKind.class);
        NativeAtlas(GpuScene scene){this(scene,ATLAS_KINDS);}
        NativeAtlas(GpuScene scene,Set<GpuScene.BufferKind> kinds){try{for(var kind:kinds){var bytes=scene.buffer(kind);buffers.put(kind,new NativeBuffer("atlas-"+kind,bytes.remaining(),Pointer.to(bytes)));}}catch(Throwable failure){try{close();}catch(Throwable cleanup){failure.addSuppressed(cleanup);}throw failure;}}
        public void close(){RuntimeException failure=null;for(var buffer:buffers.values())try{buffer.close();}catch(RuntimeException error){if(failure==null)failure=error;else failure.addSuppressed(error);}buffers.clear();if(failure!=null)throw failure;}
    }
    private final class NativeScene implements AutoCloseable {
        final EnumMap<GpuScene.BufferKind,NativeBuffer> buffers=new EnumMap<>(GpuScene.BufferKind.class);
        GpuTextureCache<NativeAtlas>.Lease atlas,geometry;
        NativeScene(GpuScene scene){this(scene,false);}
        NativeScene(GpuScene scene,boolean cache) {
            try{
                if(cache){
                    if(textureCache==null)textureCache=new GpuTextureCache<>(textureCacheLimit);
                    var meta=scene.metadata();textureCache.invalidateExcept(meta.generation(),meta.fingerprint());long bytes=0;
                    for(var kind:ATLAS_KINDS)bytes+=scene.buffer(kind).remaining();
                    atlas=textureCache.acquire(new GpuTextureCache.Key(meta.generation(),meta.fingerprint(),meta.atlasKey()),bytes,()->new NativeAtlas(scene));
                    if(geometryCache==null)geometryCache=new GpuTextureCache<>(Math.min(64L*1024*1024,memory.snapshot().maximum()/4),64);
                    geometryCache.invalidateExcept(meta.generation(),meta.fingerprint());bytes=0;for(var kind:GEOMETRY_KINDS)bytes+=scene.buffer(kind).remaining();
                    geometry=geometryCache.acquire(new GpuTextureCache.Key(meta.generation(),meta.fingerprint(),geometryKey(scene)+":"+meta.atlasKey()),bytes,()->new NativeAtlas(scene,GEOMETRY_KINDS));
                }
                for(var kind:GpuScene.BufferKind.values())if(!cache || kind==GpuScene.BufferKind.HEADER_INT || kind==GpuScene.BufferKind.HEADER_FLOAT){var bytes=scene.buffer(kind);buffers.put(kind,new NativeBuffer("scene-"+kind,bytes.remaining(),Pointer.to(bytes)));}
            }
            catch(Throwable failure){try{close();}catch(Throwable cleanup){failure.addSuppressed(cleanup);}throw failure;}
        }
        NativeBuffer get(GpuScene.BufferKind kind){var value=buffers.get(kind);if(value==null && atlas!=null)value=atlas.value().buffers.get(kind);if(value==null && geometry!=null)value=geometry.value().buffers.get(kind);if(value==null)throw new IllegalStateException("Missing native buffer "+kind);return value;}
        int bind(cl_kernel target,GpuScene.BufferKind... kinds) {
            int index=0;for(var kind:kinds)bindArg(target,index++,get(kind));return index;
        }
        @Override public void close(){Throwable failure=null;for(var buffer:buffers.values())try{buffer.close();}catch(Throwable error){if(failure==null)failure=error;else failure.addSuppressed(error);}buffers.clear();try{if(geometry!=null){geometry.close();geometry=null;}}catch(Throwable error){if(failure==null)failure=error;else failure.addSuppressed(error);}try{if(atlas!=null){atlas.close();atlas=null;}}catch(Throwable error){if(failure==null)failure=error;else failure.addSuppressed(error);}if(failure!=null)throw new BackendException(BackendException.Code.DEVICE_ERROR,"Scene cleanup failed",failure);}
    }
    private String geometryKey(GpuScene scene) {
        try{var hash=java.security.MessageDigest.getInstance("SHA-256");hash.update(scene.buffer(GpuScene.BufferKind.HEADER_INT).limit(19*4));
            for(var kind:GEOMETRY_KINDS){var data=scene.buffer(kind);hash.update(java.nio.ByteBuffer.allocate(8).putInt(kind.ordinal()).putInt(data.remaining()).array());hash.update(data);}return HexFormat.of().formatHex(hash.digest());}
        catch(java.security.NoSuchAlgorithmException error){throw new IllegalStateException(error);}
    }
    private void bindArg(cl_kernel target,int index,NativeBuffer buffer){check(clSetKernelArg(target,index,Sizeof.cl_mem,Pointer.to(buffer.buffer)),"scene argument "+index);}
    private void run(cl_kernel target,int count){check(clEnqueueNDRangeKernel(queue,target,1,null,new long[]{count},null,0,null,null),"scene kernel submission");}
    private void read(NativeBuffer buffer,long bytes,Pointer target){check(clEnqueueReadBuffer(queue,buffer.buffer,true,0,bytes,target,0,null,null),"scene readback");}
    @Override public GpuDeviceProbe.CandidateBatch candidates(GpuScene scene,int[] xyz) {
        owner();sceneKernels();long start=System.nanoTime();int count=xyz.length/3;
        try(var staging=host.reserve("gpu-candidate-readback",count*32L);
            var uploaded=new NativeScene(scene);
            var input=new NativeBuffer("query-input",xyz.length*4L,Pointer.to(xyz));
            var output=new NativeBuffer("query-output",count*16L,null)) {
            int arg=uploaded.bind(candidateKernel,GpuScene.BufferKind.HEADER_INT,GpuScene.BufferKind.SECTIONS,GpuScene.BufferKind.HEADS,GpuScene.BufferKind.NODES,GpuScene.BufferKind.KNOWN,GpuScene.BufferKind.OCCLUDED);
            bindArg(candidateKernel,arg++,input);bindArg(candidateKernel,arg,output);run(candidateKernel,count);
            int[] rows=new int[count*4];read(output,rows.length*4L,Pointer.to(rows));return new GpuDeviceProbe.CandidateBatch(rows,System.nanoTime()-start);
        }
    }
    @Override public GpuDeviceProbe.HitBatch trace(GpuScene scene,float[] rays) {
        owner();sceneKernels();long start=System.nanoTime();int count=rays.length/8;
        try(var staging=host.reserve("gpu-hit-readback",count*64L);
            var uploaded=new NativeScene(scene);
            var input=new NativeBuffer("ray-input",rays.length*4L,Pointer.to(rays));
            var ids=new NativeBuffer("hit-ids",count*16L,null);
            var hits=new NativeBuffer("hit-values",count*16L,null)) {
            int arg=uploaded.bind(traceKernel,GpuScene.BufferKind.HEADER_INT,GpuScene.BufferKind.HEADER_FLOAT,GpuScene.BufferKind.INSTANCES,GpuScene.BufferKind.MODELS_INT,GpuScene.BufferKind.MODELS_FLOAT,GpuScene.BufferKind.FACES_INT,GpuScene.BufferKind.FACES_FLOAT,GpuScene.BufferKind.TEXTURES,GpuScene.BufferKind.SECTIONS,GpuScene.BufferKind.HEADS,GpuScene.BufferKind.NODES,GpuScene.BufferKind.KNOWN,GpuScene.BufferKind.OCCLUDED);
            bindArg(traceKernel,arg++,input);bindArg(traceKernel,arg++,ids);bindArg(traceKernel,arg,hits);run(traceKernel,count);
            int[] identifiers=new int[count*4];float[] values=new float[count*4];
            read(ids,identifiers.length*4L,Pointer.to(identifiers));read(hits,values.length*4L,Pointer.to(values));
            return new GpuDeviceProbe.HitBatch(identifiers,values,System.nanoTime()-start);
        }
    }
    @Override public GpuDeviceProbe.HitListBatch hitLists(GpuScene scene,float[] rays) {
        owner();sceneKernels();long start=System.nanoTime();int count=rays.length/8;
        try(var staging=host.reserve("gpu-hit-list-readback",count*4112L);
            var uploaded=new NativeScene(scene);
            var input=new NativeBuffer("list-ray-input",rays.length*4L,Pointer.to(rays));
            var ids=new NativeBuffer("list-hit-ids",count*64L*16,null);
            var hits=new NativeBuffer("list-hit-values",count*64L*16,null);
            var counts=new NativeBuffer("list-counts",count*8L,null)) {
            int arg=uploaded.bind(listKernel,GpuScene.BufferKind.HEADER_INT,GpuScene.BufferKind.HEADER_FLOAT,GpuScene.BufferKind.INSTANCES,GpuScene.BufferKind.MODELS_INT,GpuScene.BufferKind.MODELS_FLOAT,GpuScene.BufferKind.FACES_INT,GpuScene.BufferKind.FACES_FLOAT,GpuScene.BufferKind.TEXTURES,GpuScene.BufferKind.SECTIONS,GpuScene.BufferKind.HEADS,GpuScene.BufferKind.NODES,GpuScene.BufferKind.KNOWN,GpuScene.BufferKind.OCCLUDED);
            bindArg(listKernel,arg++,input);bindArg(listKernel,arg++,ids);bindArg(listKernel,arg++,hits);bindArg(listKernel,arg,counts);run(listKernel,count);
            int[] identifiers=new int[count*64*4],sizes=new int[count*2];float[] values=new float[count*64*4];
            read(ids,identifiers.length*4L,Pointer.to(identifiers));read(hits,values.length*4L,Pointer.to(values));read(counts,sizes.length*4L,Pointer.to(sizes));
            return new GpuDeviceProbe.HitListBatch(identifiers,values,sizes,System.nanoTime()-start);
        }
    }
    /** Keep the owner and all native resources pinned while yielding CPU between event queries. */
    private void awaitPhotoEvent(cl_event event) {
        int flush=clFlush(queue);
        if(flush!=CL_SUCCESS){check(clWaitForEvents(1,new cl_event[]{event}),"photo drain after flush error");check(flush,"photo flush");}
        int[] status=new int[1];Pointer pointer=Pointer.to(status);boolean interrupted=false;
        try {
            while(true) {
                int code=clGetEventInfo(event,CL_EVENT_COMMAND_EXECUTION_STATUS,Sizeof.cl_int,pointer,null);
                if(code!=CL_SUCCESS){check(clWaitForEvents(1,new cl_event[]{event}),"photo drain after query error");check(code,"photo event query");}
                if(status[0]==CL_COMPLETE)return;
                if(status[0]<0)throw new BackendException(BackendException.Code.DEVICE_ERROR,"Photo event failed: "+status[0]);
                if(Thread.interrupted())interrupted=true;
                java.util.concurrent.locks.LockSupport.parkNanos(1_000_000L);
            }
        }finally{if(interrupted)Thread.currentThread().interrupt();}
    }
    @Override public GpuDeviceProbe.PhotoFrame photo(GpuScene scene) {
        owner();if(description.doubleCapabilities()==0)throw unavailable("MVP camera requires float64");sceneKernels();GpuPhotoSupport.require(scene);
        long wall=System.nanoTime(),cpu=ManagementFactory.getThreadMXBean().getCurrentThreadCpuTime(),uploadStart=wall;
        long hitsBefore=textureCache==null?0:textureCache.snapshot().hits(),geometryHitsBefore=geometryCache==null?0:geometryCache.snapshot().hits();
        if(cacheMetadata==null)cacheMetadata=host.reserve("gpu-cache-metadata",1024*1024);
        try(var staging=host.reserve("gpu-photo-readback",1024*1024);
            var uploaded=new NativeScene(scene,true);
            var privateMemory=scene.features().isEmpty()?null:memory.reserve("kernel-private-memory-estimate",Math.multiplyExact(16384L,featurePrivatePerPixel))) {
            if(photoRgb==null)photoRgb=new NativeBuffer("photo-rgb-reusable",16384*12L,null);
            if(photoErrors==null)photoErrors=new NativeBuffer("photo-errors-reusable",16384*4L,null);
            boolean full=!scene.features().isEmpty();cl_kernel selected=full?featureKernel:photoKernel;
            int arg=uploaded.bind(selected,GpuScene.BufferKind.HEADER_INT,GpuScene.BufferKind.HEADER_FLOAT,GpuScene.BufferKind.INSTANCES,GpuScene.BufferKind.MODELS_INT,GpuScene.BufferKind.MODELS_FLOAT,GpuScene.BufferKind.FACES_INT,GpuScene.BufferKind.FACES_FLOAT,GpuScene.BufferKind.TEXTURES,GpuScene.BufferKind.SECTIONS,GpuScene.BufferKind.HEADS,GpuScene.BufferKind.NODES,GpuScene.BufferKind.KNOWN,GpuScene.BufferKind.OCCLUDED,GpuScene.BufferKind.LEVELS,GpuScene.BufferKind.TEXELS,GpuScene.BufferKind.LIGHTING_DOUBLE);
            if(full){
                for(var kind:new GpuScene.BufferKind[]{GpuScene.BufferKind.TIMELINE,GpuScene.BufferKind.ENTITY_INT,GpuScene.BufferKind.ENTITY_FLOAT})bindArg(selected,arg++,uploaded.get(kind));
                if(linearTable==null){double[] values=new double[256];for(int i=0;i<values.length;i++)values[i]=com.wenkrang.famara.render.asset.Texture.linear(i);linearTable=new NativeBuffer("srgb-linear-table",2048,Pointer.to(values));}
                bindArg(selected,arg++,linearTable);
            }
            bindArg(selected,arg++,photoRgb);bindArg(selected,arg++,photoErrors);
            for(double angle:new double[]{scene.camera().yaw(),scene.camera().pitch(),scene.camera().fov()})check(clSetKernelArg(selected,arg++,Sizeof.cl_double,Pointer.to(new double[]{angle})),"photo camera argument");
            long upload=System.nanoTime()-uploadStart;var event=new cl_event();boolean submitted=false;
            try {
                check(clEnqueueNDRangeKernel(queue,selected,1,null,new long[]{16384},null,0,null,event),"photo submission");submitted=true;
                awaitPhotoEvent(event);
                long readStart=System.nanoTime();float[] rgb=new float[16384*3];int[] errors=new int[16384];
                read(photoRgb,rgb.length*4L,Pointer.to(rgb));read(photoErrors,errors.length*4L,Pointer.to(errors));long readback=System.nanoTime()-readStart;
                for(int i=0;i<errors.length;i++)if(errors[i]!=0)throw new BackendException(errors[i]>=4 && errors[i]<=6?BackendException.Code.UNSUPPORTED:BackendException.Code.DEVICE_ERROR,"GPU photo pixel "+i+" failed with scene error "+errors[i]);
                for(float value:rgb)if(!Float.isFinite(value) || value<0)throw new BackendException(BackendException.Code.DEVICE_ERROR,"Invalid GPU photo output");
                long[] start=new long[1],end=new long[1];check(clGetEventProfilingInfo(event,CL_PROFILING_COMMAND_START,8,Pointer.to(start),null),"photo kernel start");check(clGetEventProfilingInfo(event,CL_PROFILING_COMMAND_END,8,Pointer.to(end),null),"photo kernel end");
                return new GpuDeviceProbe.PhotoFrame(rgb,upload,end[0]-start[0],readback,System.nanoTime()-wall,ManagementFactory.getThreadMXBean().getCurrentThreadCpuTime()-cpu,textureCache.snapshot().hits()>hitsBefore,memory.snapshot().peak(),geometryCache.snapshot().hits()>geometryHitsBefore);
            }finally{if(submitted)check(clReleaseEvent(event),"photo event release");}
        }
    }
    private void owner(){if(Thread.currentThread()!=owner)throw new IllegalStateException("OpenCL session accessed outside its worker");}
    @Override public void close() {
        owner();try{closeNative();}finally{if(ownerLock!=null){var held=ownerLock;ownerLock=null;held.close();}}
    }
    private void closeNative() {
        owner();int failure=CL_SUCCESS;
        RuntimeException resourceFailure=null;
        for(AutoCloseable resource:new AutoCloseable[]{textureCache,geometryCache,photoRgb,photoErrors,linearTable,cacheMetadata})if(resource!=null)try{resource.close();}catch(Exception error){if(resourceFailure==null)resourceFailure=new BackendException(BackendException.Code.DEVICE_ERROR,"Photo resource cleanup failed",error);else resourceFailure.addSuppressed(error);}
        textureCache=null;geometryCache=null;cacheMetadata=null;photoRgb=null;photoErrors=null;linearTable=null;
        if(featureKernel!=null){failure=clReleaseKernel(featureKernel);featureKernel=null;}
        if(photoKernel!=null){int e=clReleaseKernel(photoKernel);photoKernel=null;if(failure==CL_SUCCESS)failure=e;}
        if(listKernel!=null){int e=clReleaseKernel(listKernel);listKernel=null;if(failure==CL_SUCCESS)failure=e;}
        if(candidateKernel!=null){int e=clReleaseKernel(candidateKernel);candidateKernel=null;if(failure==CL_SUCCESS)failure=e;}
        if(traceKernel!=null){int e=clReleaseKernel(traceKernel);traceKernel=null;if(failure==CL_SUCCESS)failure=e;}
        if(sceneProgram!=null){int e=clReleaseProgram(sceneProgram);sceneProgram=null;if(failure==CL_SUCCESS)failure=e;}
        if(kernel!=null){int e=clReleaseKernel(kernel);kernel=null;if(failure==CL_SUCCESS)failure=e;}
        if(program!=null){int e=clReleaseProgram(program);program=null;if(failure==CL_SUCCESS)failure=e;}
        if(queue!=null){int e=clReleaseCommandQueue(queue);queue=null;if(failure==CL_SUCCESS)failure=e;}
        if(context!=null){int e=clReleaseContext(context);context=null;if(failure==CL_SUCCESS)failure=e;}
        if(resourceFailure!=null)throw resourceFailure;
        check(failure,"OpenCL cleanup");
        if(memory.snapshot().live()!=0)throw new IllegalStateException("OpenCL buffers still reserved after close");
    }
    private static BackendException unavailable(String message){return new BackendException(BackendException.Code.UNAVAILABLE,message);}
    private static void check(int error,String action) {
        if(error==CL_SUCCESS)return;
        var code=error==CL_MEM_OBJECT_ALLOCATION_FAILURE || error==CL_OUT_OF_RESOURCES || error==CL_OUT_OF_HOST_MEMORY?BackendException.Code.OUT_OF_MEMORY:BackendException.Code.DEVICE_ERROR;
        throw new BackendException(code,action+": "+CL.stringFor_errorCode(error));
    }
    private static int uint(cl_device_id device,int key){int[] value=new int[1];check(clGetDeviceInfo(device,key,Sizeof.cl_uint,Pointer.to(value),null),"device integer");return value[0];}
    private static long ulong(cl_device_id device,int key){long[] value=new long[1];check(clGetDeviceInfo(device,key,Sizeof.cl_ulong,Pointer.to(value),null),"device integer64");return value[0];}
    private static String deviceString(cl_device_id device,int key){long[] size=new long[1];check(clGetDeviceInfo(device,key,0,null,size),"device string size");byte[] value=stringBuffer(size[0]);check(clGetDeviceInfo(device,key,value.length,Pointer.to(value),null),"device string");return text(value);}
    private static String platformString(cl_platform_id platform,int key){long[] size=new long[1];check(clGetPlatformInfo(platform,key,0,null,size),"platform string size");byte[] value=stringBuffer(size[0]);check(clGetPlatformInfo(platform,key,value.length,Pointer.to(value),null),"platform string");return text(value);}
    private static byte[] stringBuffer(long size){if(size<1 || size>4096)throw unavailable("Unbounded device information string");return new byte[(int)size];}
    private static String text(byte[] value){int length=0;while(length<value.length && value[length]!=0)length++;return new String(value,0,length,StandardCharsets.UTF_8);}
}
