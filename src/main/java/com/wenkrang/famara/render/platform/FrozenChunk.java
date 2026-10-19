package com.wenkrang.famara.render.platform;

import com.wenkrang.famara.render.asset.*;
import com.wenkrang.famara.render.scene.*;
import com.wenkrang.famara.render.schedule.MemoryScope;
import org.bukkit.ChunkSnapshot;
import java.util.*;

/** 只保留目标高度带的palette/primitive纯数据。转换后立即释放原始Bukkit快照。 */
public final class FrozenChunk implements AutoCloseable {
    private static final CanonicalBlockState AIR=CanonicalBlockState.parse("minecraft:air");
    private static final int SECTION_BYTES=4096*(4+1+4+4)+512;
    private record Section(IntValues states,ByteValues light,IntValues tint,IntValues water) {
        long bytes(){return 256+light.bytes()+(states==null?0:states.bytes()+tint.bytes()+water.bytes());}
    }
    /** 常量、小palette或完整数组三者均无损；状态ID用unsigned short且不改CE身份。 */
    private record IntValues(int constant,int[] raw,short[] shorts,byte[] indices,int[] palette) {
        int get(int index){return raw!=null?raw[index]:shorts!=null?shorts[index]&65535:indices!=null?palette[indices[index]&255]:constant;}
        long bytes(){return 96+(raw==null?0:raw.length*4L+32)+(shorts==null?0:shorts.length*2L+32)+(indices==null?0:indices.length+32L+palette.length*4L+32);}
        static IntValues pack(int[] values,boolean state) {
            boolean same=true;for(int value:values)if(value!=values[0]){same=false;break;}
            if(same)return new IntValues(values[0],null,null,null,null);
            if(state){short[] compact=new short[values.length];for(int i=0;i<values.length;i++){if(values[i]<0 || values[i]>65535)throw new IllegalStateException("状态palette ID超限");compact[i]=(short)values[i];}return new IntValues(0,null,compact,null,null);}
            Map<Integer,Integer> ids=new HashMap<>();byte[] compact=new byte[values.length];
            for(int i=0;i<values.length;i++) {
                Integer id=ids.get(values[i]);if(id==null){if(ids.size()==256)return new IntValues(0,values,null,null,null);id=ids.size();ids.put(values[i],id);}compact[i]=(byte)(int)id;
            }
            int[] palette=new int[ids.size()];ids.forEach((color,id)->palette[id]=color);return new IntValues(0,null,null,compact,palette);
        }
    }
    private record ByteValues(byte constant,byte[] raw) {
        int get(int index){return (raw==null?constant:raw[index])&255;}
        long bytes(){return 96+(raw==null?0:raw.length+32L);}
        static ByteValues pack(byte[] values){for(byte value:values)if(value!=values[0])return new ByteValues((byte)0,values);return new ByteValues(values[0],null);}
    }
    private final int cx,cz,minHeight,maxHeight;
    private final MemoryScope memory;
    private Section[] sections;
    private final int firstSection;
    private final Map<String,Integer> ids=new HashMap<>();
    private final List<CanonicalBlockState> palette=new ArrayList<>();
    private final List<LegacyEntity> entities;
    private long retained;
    private FrozenChunk(int cx,int cz,int minHeight,int maxHeight,MemoryScope memory,List<LegacyEntity> entities) {
        this.cx=cx;this.cz=cz;this.minHeight=minHeight;this.maxHeight=maxHeight;this.memory=memory;this.entities=List.copyOf(entities);
        firstSection=minHeight>>4;long count=((long)maxHeight-minHeight)/16;if(count<=0 || count>Integer.MAX_VALUE-8)throw new IllegalArgumentException("冻结section数量超限");
        charge(1056+count*8+entities.stream().mapToLong(e->128L+e.boxes().size()*128L).sum());sections=new Section[(int)count];
    }
    private void charge(long bytes){if(memory!=null)memory.claim(bytes);retained+=bytes;}
    private void release(long bytes){if(memory!=null)memory.release(bytes);retained-=bytes;}
    public static Cursor cursor(ChunkSnapshot snapshot,int cx,int cz,int minimumHeight,int maximumHeight,int y0,int y1,
                                WorldPreparation.LightingResolver lighting,VisualStateTable visuals,AssetGeneration assets,VersionStateAdapter version,
                                MemoryScope memory,List<LegacyEntity> entities) {
        return new Cursor(snapshot,new FrozenChunk(cx,cz,minimumHeight,maximumHeight,memory,entities),y0,y1,lighting,visuals,assets,version);
    }
    public static final class Cursor implements AutoCloseable {
        private ChunkSnapshot raw;
        private FrozenChunk value;
        private final int lastSection;
        private final WorldPreparation.LightingResolver lighting;
        private final VisualStateTable visuals;
        private final AssetGeneration assets;
        private final VersionStateAdapter version;
        private int sy,index;
        private boolean sectionOpen,empty;
        private int[] stateIds,tints,waters;
        private byte[] lights;
        private long stagingBytes;
        private final List<CanonicalBlockState> visualStates=new ArrayList<>();
        private boolean done,transferred;
        private Cursor(ChunkSnapshot raw,FrozenChunk value,int y0,int y1,WorldPreparation.LightingResolver lighting,VisualStateTable visuals,AssetGeneration assets,VersionStateAdapter version) {
            this.raw=raw;this.value=value;this.lighting=lighting.forChunk(value.memory);this.visuals=visuals;this.assets=assets;this.version=version;
            sy=Math.floorDiv(Math.max(value.minHeight,y0),16);lastSection=Math.floorDiv(Math.min(value.maxHeight-1,y1),16);
        }
        public boolean step(int operations,long deadline) {
            if(done)return true;
            for(int work=0;work<operations && System.nanoTime()<deadline;work++) {
                if(sy>lastSection){done=true;raw=null;visualStates.clear();lighting.close();return true;}
                if(!sectionOpen) {
                    value.charge(96);empty=raw.isSectionEmpty(WorldPreparation.sectionIndex(sy*16,value.minHeight));
                    stagingBytes=empty?4608:SECTION_BYTES;value.charge(stagingBytes);
                    stateIds=empty?null:new int[4096];tints=empty?null:new int[4096];waters=empty?null:new int[4096];lights=new byte[4096];index=0;sectionOpen=true;
                }
                int x=index&15,z=(index>>4)&15,y=sy*16+(index>>8);
                if(empty) {
                    lights[index]=(byte)(raw.getBlockSkyLight(x,y,z)<<4 | raw.getBlockEmittedLight(x,y,z));
                    if(++index==4096)finishSection();continue;
                }
                String text=raw.getBlockData(x,y,z).getAsString();Integer id=value.ids.get(text);
                if(id==null) {
                    if(value.ids.size()>=65536)throw new IllegalStateException("冻结区块状态palette超限");
                    value.charge(512L+text.length()*4L);id=value.palette.size();value.ids.put(text,id);value.palette.add(CanonicalBlockState.parse(text));
                    value.charge(1024);visualStates.add(visuals.resolve(value.palette.get(id),assets,version).descriptor().visualState());
                }
                CanonicalBlockState visual=visualStates.get(id);
                SceneSnapshot.Lighting color=lighting.resolve(raw,x,y,z,visual);
                stateIds[index]=id;lights[index]=(byte)(color.sky()<<4 | color.block());tints[index]=color.tintRgb();waters[index]=color.waterTintRgb();
                if(++index==4096)finishSection();
            }
            return false;
        }
        private void finishSection() {
            // 新旧数组重叠和最多257项临时palette先预付；fallback原数组同样按保留量结算。
            long window=128L*1024;value.charge(window);
            Section packed=new Section(empty?null:IntValues.pack(stateIds,true),ByteValues.pack(lights),empty?null:IntValues.pack(tints,false),empty?null:IntValues.pack(waters,false));
            long kept=packed.bytes();if(kept>window)throw new IllegalStateException("section压缩超出预付窗口");
            value.sections[sy-value.firstSection]=packed;stateIds=null;tints=null;waters=null;lights=null;
            value.release(window-kept+stagingBytes);stagingBytes=0;sectionOpen=false;sy++;
        }
        public FrozenChunk result(){if(!done || transferred)throw new IllegalStateException("冻结数据未就绪或已转移");transferred=true;FrozenChunk out=value;value=null;return out;}
        public String progress(){return "转换section "+sy+"/"+lastSection+"，体素 "+index+"/4096";}
        @Override public void close(){raw=null;stateIds=null;tints=null;waters=null;lights=null;visualStates.clear();lighting.close();if(value!=null)value.close();value=null;}
    }
    private Section section(int y) {
        int at=(y>>4)-firstSection;
        if(sections==null || at<0 || at>=sections.length || sections[at]==null)throw new SceneException(SceneException.Code.SCENE_INCOMPLETE,"未转换冻结section："+cx+","+(y>>4)+","+cz);
        return sections[at];
    }
    boolean sameInteriorLocal(int x,int y,int z,CanonicalBlockState expected) {
        int ly=y&15;if(x<=0 || x>=15 || z<=0 || z>=15 || ly==0 || ly==15)return false;
        Section section=section(y);if(section.states==null)return false;int at=index(x,y,z),id=section.states.get(at);
        return palette.get(id).equals(expected) && section.states.get(at-1)==id && section.states.get(at+1)==id && section.states.get(at-16)==id && section.states.get(at+16)==id && section.states.get(at-256)==id && section.states.get(at+256)==id;
    }
    private static int index(int x,int y,int z){return (y&15)*256+(z&15)*16+(x&15);}
    public CanonicalBlockState state(int x,int y,int z){CanonicalBlockState value=stateOrNull(x,y,z);return value==null?AIR:value;}
    CanonicalBlockState stateOrNull(int x,int y,int z){Section s=section(y);return s.states==null?null:palette.get(s.states.get(index(x,y,z)));}
    boolean matches(int x,int y,int z,CanonicalBlockState expected){return available(y) && expected.equals(stateOrNull(x,y,z));}
    public SceneSnapshot.Lighting light(int x,int y,int z) {
        Section s=section(y);if(s==null)return new SceneSnapshot.Lighting(15,0,0xffffff,0x3f76e4);
        int i=index(x,y,z),light=s.light.get(i);return new SceneSnapshot.Lighting(light>>4,light&15,s.tint==null?0xffffff:s.tint.get(i),s.water==null?0x3f76e4:s.water.get(i));
    }
    public boolean available(int y){int at=(y>>4)-firstSection;return sections!=null && at>=0 && at<sections.length && sections[at]!=null;}
    public boolean empty(int y){Section s=section(y);return s==null || s.states==null;}
    public List<LegacyEntity> entities(){return entities;}
    public long retainedBytes(){return retained;}
    @Override public void close(){sections=null;ids.clear();palette.clear();if(memory!=null && retained>0)memory.release(retained);retained=0;}
}
