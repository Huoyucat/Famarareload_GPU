package com.wenkrang.famara.render.gpu;

import com.wenkrang.famara.render.scene.SceneException;
import com.wenkrang.famara.render.schedule.RenderMemoryBudget;
import java.lang.foreign.Arena;
import java.nio.*;
import java.util.*;

/** Versioned, native-order frozen buffers. Contains no world or asset-generation object. */
public final class GpuScene implements AutoCloseable {
    public static final int VERSION=1,INSTANCE_STRIDE=12,MODEL_INT_STRIDE=4,MODEL_FLOAT_STRIDE=8,
            FACE_INT_STRIDE=16,FACE_FLOAT_STRIDE=40,TEXTURE_STRIDE=12;
    public enum BufferKind { HEADER_INT,HEADER_FLOAT,INSTANCES,MODELS_INT,MODELS_FLOAT,FACES_INT,FACES_FLOAT,
        TEXTURES,LEVELS,TIMELINE,TEXELS,SECTIONS,HEADS,NODES,KNOWN,OCCLUDED,ENTITY_INT,ENTITY_FLOAT,LIGHTING_DOUBLE }
    public enum Feature { CE,TRANSLUCENT,CUTOUT,ANIMATED,TRIANGLE,NON_CUBE,FLUID,FAR_LOD,LEGACY_ENTITY }
    public record Metadata(long generation,String fingerprint,long gpuBytes,long hostReservationBytes,
                           int instances,int models,int faces,int textures,int boxes,String atlasKey,double precisionTolerance) {}
    public record PhotoCamera(double yaw,double pitch,double fov) {}
    private final PhotoCamera camera;
    public PhotoCamera camera(){return camera;}
    private final Arena arena;
    private final RenderMemoryBudget.Lease planning,data;
    private final Map<BufferKind,ByteBuffer> buffers;
    private final Metadata metadata;
    private final Set<Feature> features;
    private int references=1;
    private boolean released;
    GpuScene(Arena arena,RenderMemoryBudget.Lease planning,RenderMemoryBudget.Lease data,Map<BufferKind,ByteBuffer> buffers,Metadata metadata,Set<Feature> features,PhotoCamera camera) {
        this.arena=arena;this.planning=planning;this.data=data;this.buffers=Map.copyOf(buffers);this.metadata=metadata;this.features=Set.copyOf(features);this.camera=Objects.requireNonNull(camera);
    }
    public Metadata metadata(){return metadata;}
    public Set<Feature> features(){return features;}
    public synchronized ByteBuffer buffer(BufferKind kind) {
        if(references==0)throw new IllegalStateException("GPU scene storage is closed");
        return buffers.get(kind).asReadOnlyBuffer().order(ByteOrder.nativeOrder());
    }
    public IntBuffer ints(BufferKind kind){return buffer(kind).asIntBuffer();}
    public FloatBuffer floats(BufferKind kind){return buffer(kind).asFloatBuffer();}
    /** Pins the arena while native code can hold a pointer, even if cancellation releases its caller. */
    public synchronized Borrow retain(){if(released || references==0)throw new IllegalStateException("GPU scene owner released");references++;return new Borrow();}
    public final class Borrow implements AutoCloseable {
        private boolean closed;
        @Override public void close(){synchronized(GpuScene.this){if(closed)return;closed=true;release();}}
    }
    private void release() {
        if(--references!=0)return;
        try{arena.close();}finally{try{data.close();}finally{planning.close();}}
    }
    @Override public synchronized void close(){if(released)return;released=true;release();}

    /** CPU export oracle for index checks; preserves the reference missing-section distinction. */
    public int[] candidates(int x,int y,int z) {
        var header=ints(BufferKind.HEADER_INT);
        if(y<header.get(15) || y>=header.get(16))return new int[0];
        int sx=x>>4,sy=y>>4,sz=z>>4,hostY=Math.max(header.get(17),Math.min(header.get(18)-1,y));
        if(find(ints(BufferKind.OCCLUDED),header.get(10),3,sx,0,sz)>=0 || find(ints(BufferKind.KNOWN),header.get(9),3,sx,hostY>>4,sz)<0)
            throw new SceneException(SceneException.Code.SCENE_INCOMPLETE,"GPU export touched an uncaptured section");
        var sections=ints(BufferKind.SECTIONS);int row=find(sections,header.get(7),6,sx,sy,sz);
        if(row<0)return new int[0];
        int head=ints(BufferKind.HEADS).get(sections.get(row*6+3)+((y&15)<<8)+((z&15)<<4)+(x&15));
        var nodes=ints(BufferKind.NODES);var found=new ArrayList<Integer>();
        while(head>=0){if(found.size()>header.get(8))throw new IllegalStateException("Cyclic GPU index");found.add(nodes.get(head*2+1));head=nodes.get(head*2);}
        return found.stream().mapToInt(Integer::intValue).toArray();
    }
    private static int find(IntBuffer values,int count,int stride,int x,int y,int z) {
        int low=0,high=count-1;
        while(low<=high){int mid=(low+high)>>>1,at=mid*stride;int c=Integer.compare(values.get(at),x);if(c==0)c=Integer.compare(values.get(at+1),y);if(c==0)c=Integer.compare(values.get(at+2),z);if(c==0)return mid;if(c<0)low=mid+1;else high=mid-1;}
        return -1;
    }
}
