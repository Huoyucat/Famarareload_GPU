package com.wenkrang.famara.render.asset;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;
import java.util.regex.*;

/** 编译后不可变。构建游标在状态定义边界让出，不把全部资源一次占满后台CPU。 */
public final class AssetGeneration {
    public record Issue(AssetException.Code code,String resource,String reason) {}
    private static final Pattern STATE=Pattern.compile("assets/([a-z0-9_.-]+)/blockstates/(.+)[.]json");
    private com.wenkrang.famara.render.schedule.RenderMemoryBudget.Lease memory;
    public synchronized void memory(com.wenkrang.famara.render.schedule.RenderMemoryBudget.Lease lease){if(memory!=null)throw new IllegalStateException("资源内存所有权重复");memory=lease;}
    public synchronized void releaseMemory(){if(memory!=null)memory.close();memory=null;}
    private final long id;
    private final String fingerprint;
    private final Map<ResourceKey,BlockDefinition> blocks;
    private final Map<ResourceKey,AssetException> failed;
    private final Map<ResourceKey,Texture> textures;
    private final List<Issue> issues;
    private final Set<ResourceKey> allKeys;
    private final Map<ResourceKey,ResourcePack.Role> definitionRoles,modelRoles,textureRoles;
    private final long retainedTextureBytes,workingBytes;
    private final int hostHalo;
    private AssetGeneration(long id,String fingerprint,Map<ResourceKey,BlockDefinition> blocks,
                            Map<ResourceKey,AssetException> failed,Map<ResourceKey,Texture> textures,
                            List<Issue> issues,long retainedTextureBytes,long workingBytes,Map<ResourceKey,ResourcePack.Role> definitionRoles,
                            Map<ResourceKey,ResourcePack.Role> modelRoles,Map<ResourceKey,ResourcePack.Role> textureRoles) {
        this.id=id;this.fingerprint=fingerprint;this.blocks=Map.copyOf(blocks);this.failed=Map.copyOf(failed);
        this.textures=Map.copyOf(textures);this.issues=List.copyOf(issues);
        this.retainedTextureBytes=retainedTextureBytes;this.workingBytes=workingBytes;
        this.definitionRoles=Map.copyOf(definitionRoles);this.modelRoles=Map.copyOf(modelRoles);this.textureRoles=Map.copyOf(textureRoles);
        double reach=0;
        for(BlockDefinition definition:blocks.values())for(BakedModel model:definition.possibleModels())for(int axis=0;axis<3;axis++)
            reach=Math.max(reach,Math.max(-model.min().component(axis),model.max().component(axis)-1));
        if(reach>Integer.MAX_VALUE-2)throw new IllegalArgumentException("模型外伸范围无法安全捕获");
        hostHalo=1+(int)Math.ceil(reach);
        Set<ResourceKey> known=new HashSet<>(blocks.keySet());known.addAll(failed.keySet());allKeys=Set.copyOf(known);
    }
    public static AssetGeneration compile(long id,ResourcePack pack,AssetLimits limits) {
        try(BuildCursor cursor=builder(id,pack,limits)) {
            while(!cursor.step(256,Long.MAX_VALUE,System::nanoTime)) {}
            return cursor.result();
        }
    }
    public static BuildCursor builder(long id,ResourcePack pack,AssetLimits limits) { return new BuildCursor(id,pack,limits); }
    public static final class BuildCursor implements AutoCloseable {
        private final long id;
        private ResourcePack pack;
        private final AssetLimits limits;
        private final AssetMemoryBudget memory;
        private ModelCompiler compiler;
        private Iterator<String> paths;
        private final Map<ResourceKey,BlockDefinition> blocks=new LinkedHashMap<>();
        private final Map<ResourceKey,AssetException> failed=new LinkedHashMap<>();
        private final List<Issue> issues=new ArrayList<>();
        private final Map<ResourceKey,ResourcePack.Role> definitionRoles=new HashMap<>();
        private final AtomicBoolean running=new AtomicBoolean();
        private AssetGeneration result;
        private boolean closed,poisoned;
        private int definitions;
        private BuildCursor(long id,ResourcePack pack,AssetLimits limits) {
            if(id<0)throw new IllegalArgumentException("非法资源代际");
            this.id=id;this.pack=Objects.requireNonNull(pack);this.limits=Objects.requireNonNull(limits);
            this.memory=new AssetMemoryBudget(limits.maxTotalBytes());
            memory.reserve(pack.retainedBytes(),"source-pack");
            this.compiler=new ModelCompiler(pack,limits,memory);this.paths=pack.paths().iterator();
        }
        public boolean step(int maxEntries,long deadlineNanos,LongSupplier clock) {
            if(maxEntries<=0)throw new IllegalStateException("无效资产构建步长");
            if(!running.compareAndSet(false,true))throw new IllegalStateException("资产构建不能重叠执行");
            try {
                if(closed || poisoned)throw new IllegalStateException("资产构建游标已关闭或失败");
                if(result!=null)return true;
                for(int work=0;work<maxEntries && paths.hasNext();work++) {
                    if(clock.getAsLong()>=deadlineNanos)return false;
                    String path=paths.next();
                    if(Set.of("assets/minecraft/textures/colormap/grass.png","assets/minecraft/textures/colormap/foliage.png","assets/minecraft/textures/colormap/dry_foliage.png").contains(path))
                        compiler.texture(ResourceKey.parse("minecraft:colormap/"+path.substring(path.lastIndexOf('/')+1,path.length()-4)));
                    Matcher match=STATE.matcher(path);
                    if(!match.matches())continue;
                    ResourceKey key=new ResourceKey(match.group(1),match.group(2));
                    definitionRoles.put(key,pack.require(path).role());
                    try {
                        List<BlockDefinition.Layer> layers=new ArrayList<>();
                        for(ResourcePack.Content content:pack.blockstateStack(path)) {
                            com.google.gson.JsonObject json=BoundedJson.read(content,path,limits,memory);
                            Texture.checkFields(json,Set.of("variants","multipart"),path);
                            BlockDefinition definition=BuiltinBlockModels.compile(key,compiler);
                            if(definition==null)definition=BlockDefinition.compile(json,path,compiler);
                            layers.add(new BlockDefinition.Layer(definition,content.role()));
                        }
                        BlockDefinition definition=BlockDefinition.layered(layers,path);
                        blocks.put(key,definition);
                        for(AssetException e:definition.issues())issues.add(new Issue(e.code(),e.resource(),e.getMessage()));
                    } catch(AssetException e) {
                        if(e.code()==AssetException.Code.LIMIT)throw e;
                        failed.put(key,e);issues.add(new Issue(e.code(),e.resource(),e.getMessage()));
                    } catch(RuntimeException e) {
                        AssetException wrapped=new AssetException(AssetException.Code.INVALID,path,"状态定义无法编译",e);
                        failed.put(key,wrapped);issues.add(new Issue(wrapped.code(),path,wrapped.getMessage()));
                    }
                    definitions++;
                }
                if(paths.hasNext())return false;
                if(blocks.isEmpty() || blocks.values().stream().noneMatch(BlockDefinition::hasUsableModel))
                    throw new AssetException(AssetException.Code.INVALID,"blockstates","没有可用的方块状态定义");
                result=new AssetGeneration(id,pack.fingerprint(),blocks,failed,compiler.textures(),issues,
                        compiler.retainedTextureBytes(),memory.reservedBytes(),definitionRoles,compiler.modelRoles(),compiler.textureRoles());
                return true;
            } catch(RuntimeException | Error error){poisoned=true;throw error;}
            finally { running.set(false); }
        }
        public int completedDefinitions() { return definitions; }
        public long reservedWorkingBytes() { return memory.reservedBytes(); }
        public AssetGeneration result() {
            if(closed || result==null)throw new IllegalStateException("资产尚未构建完成");
            return result;
        }
        @Override public void close() {
            if(!running.compareAndSet(false,true))throw new IllegalStateException("不能在另一线程关闭运行中的构建游标");
            try{closed=true;pack=null;compiler=null;paths=null;result=null;blocks.clear();failed.clear();issues.clear();definitionRoles.clear();}
            finally{running.set(false);}
        }
    }
    /** 包含随机模型的最大宿主外伸，以及流体/剔面所需一格邻域。 */
    public int hostHalo(){return hostHalo;}
    public boolean guaranteedOpaque(VisualBlockDescriptor descriptor,java.util.function.Predicate<List<BakedModel>> opaque) {
        validateReference(descriptor);return blocks.get(descriptor.visualState().block()).guaranteedOpaque(descriptor.visualState(),opaque);
    }
    public boolean confinedToHost(VisualBlockDescriptor descriptor) {
        validateReference(descriptor);
        for(BakedModel model:blocks.get(descriptor.visualState().block()).possibleModels())for(int axis=0;axis<3;axis++)
            if(model.min().component(axis)<-1e-9 || model.max().component(axis)>1+1e-9)return false;
        return true;
    }
    public record ModelBounds(com.wenkrang.famara.render.math.Vec3 min,com.wenkrang.famara.render.math.Vec3 max,boolean empty) {}
    public ModelBounds bounds(VisualBlockDescriptor descriptor) {
        validateReference(descriptor);double[] low={0,0,0},high={1,1,1};boolean empty=true;
        var key=descriptor.visualState().block();
        if(key.namespace().equals("minecraft") && (key.path().equals("water") || key.path().equals("lava")))empty=false;
        for(BakedModel model:blocks.get(key).possibleModels())if(!model.quads().isEmpty()) {
            empty=false;for(int axis=0;axis<3;axis++){low[axis]=Math.min(low[axis],model.min().component(axis));high[axis]=Math.max(high[axis],model.max().component(axis));}
        }
        return new ModelBounds(new com.wenkrang.famara.render.math.Vec3(low[0],low[1],low[2]),new com.wenkrang.famara.render.math.Vec3(high[0],high[1],high[2]),empty);
    }
    public long id() { return id; }
    public String fingerprint() { return fingerprint; }
    public Set<ResourceKey> blockKeys() { return allKeys; }
    public int failedBlockCount() { return failed.size(); }
    public long completeBlockDefinitions() { return blocks.values().stream().filter(d->d.issues().isEmpty()).count(); }
    public List<Issue> issues() { return issues; }
    public long retainedTextureBytes() { return retainedTextureBytes; }
    /** 保守预约，包含构建中间对象；不是JVM堆精确测量。 */
    public long estimatedRetainedBytes() { return workingBytes; }
    public Texture texture(ResourceKey key) {
        Texture value=textures.get(key);
        if(value==null)throw new AssetException(AssetException.Code.MISSING,key.toString(),"贴图未在准备阶段编译");
        return value;
    }
    public boolean usesCustomResources(VisualBlockDescriptor descriptor,int x,int y,int z,long seed) {
        validateReference(descriptor);
        ResourceKey key=descriptor.visualState().block();
        if(blocks.get(key).roleFor(descriptor.visualState())==ResourcePack.Role.CUSTOM)return true;
        if(key.namespace().equals("minecraft") && (key.path().equals("water") || key.path().equals("lava")))
            return textureRoles.get(ResourceKey.parse("minecraft:block/"+key.path()+"_still"))==ResourcePack.Role.CUSTOM
                    || textureRoles.get(ResourceKey.parse("minecraft:block/"+key.path()+"_flow"))==ResourcePack.Role.CUSTOM;
        for(BakedModel model:resolve(descriptor,x,y,z,seed)) {
            if(modelRoles.get(model.key())==ResourcePack.Role.CUSTOM)return true;
            for(BakedQuad quad:model.quads())if(textureRoles.get(quad.texture())==ResourcePack.Role.CUSTOM)return true;
        }
        return false;
    }

    public void validateReference(VisualBlockDescriptor descriptor) {
        if(descriptor.generation()!=id)throw new AssetException(AssetException.Code.GENERATION_MISMATCH,descriptor.logicalId().toString(),"视觉映射与资源不同代");
        ResourceKey key=descriptor.visualState().block();
        if(failed.containsKey(key))throw failed.get(key);
        if(!blocks.containsKey(key))throw new AssetException(AssetException.Code.MISSING,key.toString(),"没有可见状态定义");
    }
    /** 只判断状态条件，不执行模型选择；匹配的损坏CE覆盖不能被适配回退掩盖。 */
    public boolean matchesState(CanonicalBlockState state) {
        AssetException error=failed.get(state.block());if(error!=null)throw error;
        BlockDefinition definition=blocks.get(state.block());return definition!=null && definition.covers(state);
    }
    public List<BakedModel> resolve(VisualBlockDescriptor descriptor,int x,int y,int z,long seed) {
        validateReference(descriptor);
        if(descriptor.generation()!=id)throw new AssetException(AssetException.Code.GENERATION_MISMATCH,descriptor.logicalId().toString(),"视觉映射与资源不同代");
        ResourceKey key=descriptor.visualState().block();AssetException error=failed.get(key);
        if(error!=null)throw error;
        BlockDefinition definition=blocks.get(key);
        if(definition==null)throw new AssetException(AssetException.Code.MISSING,key.toString(),"没有可见状态定义");
        try{return definition.select(descriptor.visualState(),x,y,z,seed);}
        catch(AssetException missing) {
            if(missing.code()==AssetException.Code.MISSING && missing.resource().equals(key.file("blockstates",".json")))
                throw new AssetException(missing.code(),missing.resource(),"没有匹配的状态模型："+descriptor.visualState()+"；视觉类别="+descriptor.kind()+"；定义角色="+definitionRoles.getOrDefault(key,ResourcePack.Role.UNKNOWN),missing);
            throw missing;
        }
    }
}
