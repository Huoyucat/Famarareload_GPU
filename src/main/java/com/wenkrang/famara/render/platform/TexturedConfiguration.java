package com.wenkrang.famara.render.platform;

import com.google.gson.*;
import com.wenkrang.famara.render.asset.*;
import com.wenkrang.famara.render.kernel.*;
import com.wenkrang.famara.render.output.CameraLedger;
import com.wenkrang.famara.render.schedule.*;
import java.nio.file.*;
import java.util.*;

/** 不可变运行策略；正式默认由单文件YAML提供，旧JSON仅供迁移与离线工具。 */
public record TexturedConfiguration(String baseResources,String resourceVersion,AssetLimits assets,Cache cache,
                                    BudgetedAssetLoader.Limits assetBuild,CpuPhotoPipeline.Limits pipeline,
                                    CeVisualResourceService.Limits visuals,BiomeCatalog.Limits biomes,
                                    ChunkCaptureService.Limits capture,WorldPreparation.Limits preparation,
                                    LegacyEntityCapture.Limits entities,CameraLedger.Limits ledger,Io io,Loading loading,
                                    Memory memory,Maps maps,com.wenkrang.famara.render.scene.FarLod.Policy lod,CpuPhotoRenderer.Settings shot,List<String> allowVanillaCe,
                                    com.wenkrang.famara.render.gpu.GpuConfiguration gpu) {
    public record Memory(long maximumBytes,boolean occlusion) {
        public Memory{if(maximumBytes<=0 || maximumBytes>536870912L)throw new IllegalArgumentException("V2共享总预算必须大于0且不超过512MiB");}
    }
    public record Cache(int generations,long retainedBytes){public Cache{if(generations<=0 || retainedBytes<=0)throw new IllegalArgumentException("资源缓存限额无效");}}
    public record Io(int maximum,long intervalNanos,long quantumNanos){public Io{if(maximum<=0 || intervalNanos<1_000_000 || quantumNanos<=0)throw new IllegalArgumentException("IO限额无效");}}
    public record Loading(double cpuMillisPerSecond,double burstMillis) {
        public Loading {if(!Double.isFinite(cpuMillisPerSecond) || cpuMillisPerSecond<=0 || !Double.isFinite(burstMillis) || burstMillis<=0 || burstMillis>Long.MAX_VALUE/1e6 || burstMillis*1e6<1)throw new IllegalArgumentException("加载CPU预算无效");}
    }
    public record Maps(int maximumCanvases,int maximumRecoveryRequests,int maximumCachedFrames){public Maps{if(maximumCanvases<=0 || maximumRecoveryRequests<=0 || maximumCachedFrames<=0)throw new IllegalArgumentException("地图限额无效");}}
    public TexturedConfiguration {
        gpu=gpu==null?com.wenkrang.famara.render.gpu.GpuConfiguration.disabled():gpu;
        lod=lod==null?com.wenkrang.famara.render.scene.FarLod.Policy.disabled():lod;
        Objects.requireNonNull(baseResources);Objects.requireNonNull(resourceVersion);Objects.requireNonNull(assets);Objects.requireNonNull(cache);
        Objects.requireNonNull(assetBuild);Objects.requireNonNull(pipeline);Objects.requireNonNull(visuals);Objects.requireNonNull(biomes);
        Objects.requireNonNull(capture);Objects.requireNonNull(preparation);Objects.requireNonNull(entities);Objects.requireNonNull(ledger);
        Objects.requireNonNull(io);Objects.requireNonNull(loading);Objects.requireNonNull(memory);Objects.requireNonNull(maps);Objects.requireNonNull(shot);allowVanillaCe=List.copyOf(allowVanillaCe);
        if(baseResources.isBlank() || allowVanillaCe.size()>visuals.maximumStates() || entities.maximumBoxes()>preparation.maximumIndexEntries())throw new IllegalArgumentException("资源路径、CE白名单或实体盒预算无效");
        VersionStateAdapter.Version.parse(resourceVersion);allowVanillaCe.forEach(ResourceKey::parse);
        double burst=Math.min(pipeline.burstMillis(),loading.burstMillis())*1e6;
        if(Math.max(io.quantumNanos,Math.max(assetBuild.quantumCpuNanos(),Math.max(visuals.quantumCpuNanos(),Math.max(biomes.quantumCpuNanos(),capture.quantumCpuNanos()))))>burst)
            throw new IllegalArgumentException("阶段CPU预约大于共享突发预算");
        if(cache.retainedBytes<assets.maxTotalBytes())throw new IllegalArgumentException("资源缓存不足以容纳一代资产的工作预约");
        if(minimumSceneBytes(preparation)>pipeline.sceneMemoryBytes())throw new IllegalArgumentException("场景内存预算不足以准入一张照片");
    }
    public static long minimumSceneBytes(WorldPreparation.Limits limits){return WorldPreparation.sceneWorkspaceBytes();}
    public static JsonObject readObject(Path path)throws Exception {
        AssetLimits bounds=new AssetLimits(1,1024*1024,16*1024*1024,1,1,1,1,32,40000,1);
        byte[] bytes;try(var input=Files.newInputStream(path)){bytes=input.readNBytes(1024*1024+1);}
        if(bytes.length>1024*1024)throw new IllegalArgumentException("V2配置过大："+path);
        return BoundedJson.read(ResourcePack.memory("config-migration",Map.of("config.json",bytes),bounds).require("config.json"),path.toString(),bounds);
    }
    public boolean bundledBase(){return baseResources.equals("bundled");}
    public Path base(Path folder){if(bundledBase())return folder.resolve("render-cache/bundled-pending.zip");Path path=Path.of(baseResources);return path.isAbsolute()?path:folder.resolve(path).normalize();}
    public static TexturedConfiguration read(Path path) throws Exception {
        AssetLimits bounds=new AssetLimits(1,1024*1024,16*1024*1024,1,1,1,1,32,40000,1);
        byte[] bytes;try(var input=Files.newInputStream(path)){bytes=input.readNBytes(1024*1024+1);}
        if(bytes.length>1024*1024)throw new IllegalArgumentException("V2配置过大");
        JsonObject root;
        try {root=BoundedJson.read(ResourcePack.memory("textured-config",Map.of("config.json",bytes),bounds).require("config.json"),"config.json",bounds);}
        catch(RuntimeException error){throw new IllegalArgumentException("V2配置必须是严格JSON对象，不是YAML或带注释的配置："+path+"；请使用render-v2.json.example并保持双引号/无注释",error);}
        return parse(root);
    }
    public static TexturedConfiguration parse(JsonObject root) {
        check(root,TexturedConfiguration.class);
        Gson gson=new GsonBuilder().registerTypeAdapter(ResourceKey.class,(JsonDeserializer<ResourceKey>)(e,t,c)->ResourceKey.parse(e.getAsString())).create();
        return gson.fromJson(root,TexturedConfiguration.class);
    }
    private static void check(JsonObject value,Class<?> type) {
        Set<String> keys=new HashSet<>();
        for(var field:type.getRecordComponents()) {
            keys.add(field.getName());JsonElement child=value.get(field.getName());
            if(child==null && ((field.getName().equals("lod") || field.getName().equals("gpu")) && type==TexturedConfiguration.class || field.getName().equals("scanBatchCpuNanos") && type==CpuPhotoPipeline.Limits.class || field.getName().equals("maximumSourceBytes") && type==BudgetedAssetLoader.Limits.class || field.getName().equals("windowChunks") && type==ChunkCaptureService.Limits.class))continue;
            if(child==null || child.isJsonNull())throw new IllegalArgumentException("缺少显式配置："+type.getSimpleName()+"."+field.getName());
            Class<?> childType=field.getType();
            if(type==com.wenkrang.famara.render.gpu.GpuConfiguration.class && childType==double.class && (!child.isJsonPrimitive() || !child.getAsJsonPrimitive().isNumber()))throw new IllegalArgumentException("GPU配置需要数值："+field.getName());
            if(childType.isRecord())check(child.getAsJsonObject(),childType);
            else if(childType==int.class || childType==long.class || childType==Long.class || childType==Integer.class) {
                if(!child.isJsonPrimitive() || !child.getAsJsonPrimitive().isNumber())throw new IllegalArgumentException("配置需要整数："+field.getName());
                try{var number=new java.math.BigDecimal(child.getAsString());if(childType==int.class || childType==Integer.class)number.intValueExact();else number.longValueExact();}
                catch(ArithmeticException error){throw new IllegalArgumentException("配置不是有界整数："+field.getName(),error);}
            } else if(childType==boolean.class && (!child.isJsonPrimitive() || !child.getAsJsonPrimitive().isBoolean()))throw new IllegalArgumentException("配置需要布尔值："+field.getName());
            else if(childType==String.class && (!child.isJsonPrimitive() || !child.getAsJsonPrimitive().isString()))throw new IllegalArgumentException("配置需要字符串："+field.getName());
        }
        for(String key:value.keySet())if(!keys.contains(key))throw new IllegalArgumentException("未知V2配置："+type.getSimpleName()+"."+key);
    }
}
