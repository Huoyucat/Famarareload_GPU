package com.wenkrang.famara.render.scene;

import com.wenkrang.famara.render.asset.*;
import java.util.*;
import java.util.function.LongConsumer;

/** 纯数据的CE视觉表，生成后不再查询CE后端。 */
public final class VisualStateTable {
    public record Entry(CanonicalBlockState server,ResourceKey logical,CanonicalBlockState vanilla,
                        CanonicalBlockState visual,ResourceKey material) {}
    public record VanillaRemap(CanonicalBlockState server,CanonicalBlockState visual) {
        public VanillaRemap{Objects.requireNonNull(server);Objects.requireNonNull(visual);if(!server.block().namespace().equals("minecraft"))throw new IllegalArgumentException("原版重映射不能冒充自定义服务端状态");}
    }
    public record Resolved(VisualBlockDescriptor descriptor,boolean customResourceRequired) {}
    private record Payload(ResourceKey logical,CanonicalBlockState vanilla,CanonicalBlockState visual,ResourceKey material) {}
    private final long epoch,estimatedRetainedBytes;
    private final Map<CanonicalBlockState,Payload> entries;
    private final Set<ResourceKey> carriers;
    private final Map<CanonicalBlockState,CanonicalBlockState> vanillaRemaps;
    private final long nativeSlots;
    private VisualStateTable(Builder owned) {
        epoch=owned.epoch;estimatedRetainedBytes=owned.retained;nativeSlots=owned.nativeSlots;
        entries=Collections.unmodifiableMap(owned.entries);carriers=Collections.unmodifiableSet(owned.carriers);vanillaRemaps=Collections.unmodifiableMap(owned.remaps);
    }
    public VisualStateTable(long epoch,Collection<Entry> source,int maximum){this(epoch,source,List.of(),maximum);}
    public VisualStateTable(long epoch,Collection<Entry> source,Collection<VanillaRemap> remaps,int maximum) {
        if((long)source.size()+remaps.size()>maximum)throw new IllegalArgumentException("CE视觉表限额不合法");
        Builder builder=new Builder(epoch,maximum,Long.MAX_VALUE,n->{});
        for(var entry:source)builder.add(entry.server.toString(),entry.material.toString(),entry.logical.toString(),entry.vanilla==null?null:entry.vanilla.toString(),entry.visual.toString(),true);
        for(var remap:remaps)builder.add(remap.server.toString(),null,null,null,remap.visual.toString(),false);
        var table=builder.finish();this.epoch=table.epoch;this.estimatedRetainedBytes=table.estimatedRetainedBytes;this.nativeSlots=table.nativeSlots;this.entries=table.entries;this.carriers=table.carriers;this.vanillaRemaps=table.vanillaRemaps;
    }
    /** 单owner、逐行转换。reserve接收包括缓存与解析临时空间的工作集，必须在分配前准入。 */
    public static final class Builder {
        private final long epoch,maximumBytes;
        private final int maximumStates;
        private final LongConsumer reserve;
        private Map<CanonicalBlockState,Payload> entries=new HashMap<>();
        private Map<CanonicalBlockState,CanonicalBlockState> remaps=new HashMap<>();
        private Set<ResourceKey> carriers=new HashSet<>();
        private Map<String,CanonicalBlockState> states=new HashMap<>();
        private Map<String,ResourceKey> keys=new HashMap<>();
        private Map<Payload,Payload> payloads=new HashMap<>();
        private long retained=4096,caches=1024,scratch=4096,workingPeak,nativeSlots;
        private int inputs;
        private boolean finished;
        public Builder(long epoch,int maximumStates,long maximumBytes,LongConsumer reserve) {
            if(epoch<0 || maximumStates<=0 || maximumBytes<=0)throw new IllegalArgumentException("CE视觉表限额不合法");
            this.epoch=epoch;this.maximumStates=maximumStates;this.maximumBytes=maximumBytes;this.reserve=Objects.requireNonNull(reserve);grow(0,0,0);
        }
        private void check(){if(finished)throw new IllegalStateException("CE视觉表已封存");}
        private void grow(long persistent,long temporary,long parsing) {
            long next=Math.addExact(retained,persistent),cache=Math.addExact(caches,temporary),extra=Math.max(scratch,parsing);
            if(next>maximumBytes)throw new IllegalStateException("CE视觉常驻表超过显式限额：阶段=转换，输入条目="+inputs+"，唯一条目="+size()+"，常驻字节="+next+"，上限="+maximumBytes);
            long working=Math.addExact(Math.addExact(next,cache),extra);reserve.accept(working);
            retained=next;caches=cache;scratch=extra;workingPeak=Math.max(workingPeak,working);
        }
        private ResourceKey key(String value) {
            Objects.requireNonNull(value);ResourceKey old=keys.get(value);if(old!=null)return old;
            grow(192L+value.length()*8L,128L+value.length()*4L,2048L+value.length()*16L);
            ResourceKey parsed=ResourceKey.parse(value);keys.put(value,parsed);return parsed;
        }
        private CanonicalBlockState state(String value) {
            Objects.requireNonNull(value);CanonicalBlockState old=states.get(value);if(old!=null)return old;
            int properties=0;for(int i=0;i<value.length();i++)if(value.charAt(i)=='=')properties++;
            grow(384L+value.length()*8L+properties*192L,128L+value.length()*4L,4096L+value.length()*16L+properties*384L);
            CanonicalBlockState parsed=CanonicalBlockState.parse(value);
            parsed=new CanonicalBlockState(key(parsed.block().toString()),parsed.properties());states.put(value,parsed);return parsed;
        }
        public void add(String server,String material,String logical,String vanilla,String visual,boolean custom) {
            check();if(inputs>=maximumStates)throw new IllegalStateException("CE视觉转换输入条目超过显式限额：条目="+((long)inputs+1)+"，上限="+maximumStates);
            CanonicalBlockState source=state(server),target=state(visual);
            if(custom) {
                Payload value=new Payload(key(logical),vanilla==null?null:state(vanilla),target,key(material));Payload shared=payloads.get(value);
                if(shared==null){grow(128,96,0);payloads.put(value,value);shared=value;}
                Payload old=entries.get(source);
                if(old!=null && !old.equals(shared))throw new IllegalArgumentException("CE服务端状态发生重复分配："+server);
                if(old==null) {
                    grow(128,0,0);entries.put(source,shared);if(source.block().namespace().equals("craftengine"))nativeSlots++;
                    if(!carriers.contains(shared.material)){grow(128,0,0);carriers.add(shared.material);}
                }
            } else {
                if(!source.block().namespace().equals("minecraft"))throw new IllegalArgumentException("原版重映射不能冒充自定义服务端状态");
                CanonicalBlockState old=remaps.get(source);
                if(old!=null && !old.equals(target))throw new IllegalArgumentException("原版服务端状态存在冲突的客户端映射："+server);
                if(old==null){grow(128,0,0);remaps.put(source,target);}
            }
            inputs++;
        }
        public int size(){return finished?0:entries.size()+remaps.size();}
        public long estimatedRetainedBytes(){return retained;}
        public long estimatedWorkingBytes(){return retained+caches+scratch;}
        public long workingPeakBytes(){return workingPeak;}
        public VisualStateTable finish() {
            check();VisualStateTable table=new VisualStateTable(this);finished=true;
            entries=null;remaps=null;carriers=null;states=null;keys=null;payloads=null;return table;
        }
    }
    public long epoch(){return epoch;}
    public int size(){return entries.size()+vanillaRemaps.size();}
    public long estimatedRetainedBytes(){return estimatedRetainedBytes;}
    public long nativeSlotEntries(){return nativeSlots;}
    public int vanillaRemapCount(){return vanillaRemaps.size();}
    public Set<ResourceKey> carrierMaterials(){return carriers;}
    public Resolved resolve(CanonicalBlockState server,AssetGeneration resources,VersionStateAdapter version) {
        Payload entry=entries.get(server);
        if(entry!=null) {
            VisualBlockDescriptor descriptor=new VisualBlockDescriptor(VisualBlockDescriptor.Kind.CE,entry.logical,entry.visual.block().namespace().equals("minecraft")?version.adapt(entry.visual,resources):entry.visual,resources.id());
            return new Resolved(descriptor,entry.vanilla==null || entry.vanilla.block().equals(entry.visual.block()));
        }
        if(!server.block().namespace().equals("minecraft"))throw new AssetException(AssetException.Code.MISSING,server.toString(),"服务端自定义状态不在冻结的CE视觉表中（视觉代="+epoch+"，CE映射条目="+entries.size()+"）");
        // 不递归重映射目标：CE网络表已给出最终客户端状态，不能把它再次当成世界中的载体解释。
        CanonicalBlockState visual=version.adapt(vanillaRemaps.getOrDefault(server,server),resources);
        return new Resolved(new VisualBlockDescriptor(VisualBlockDescriptor.Kind.VANILLA,server.block(),visual,resources.id()),false);
    }
    public void validateResources(Resolved resolved,AssetGeneration resources,int x,int y,int z,Set<ResourceKey> allowedVanilla) {
        resources.validateReference(resolved.descriptor);
        if(resolved.customResourceRequired && !allowedVanilla.contains(resolved.descriptor.logicalId()) && !resources.usesCustomResources(resolved.descriptor,x,y,z,0))
            throw new AssetException(AssetException.Code.MISSING,resolved.descriptor.logicalId().toString(),"CE可见状态没有自定义资源，拒绝误用原版载体外观");
    }
}
