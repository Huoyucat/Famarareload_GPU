package com.wenkrang.famara.render.platform;

import com.google.gson.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import java.io.*;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** 单文件配置的有界、严格、幂等迁移。只操作配置文件，不接触相机账本或照片。 */
public final class UnifiedRendererConfig {
    public record Result(YamlConfiguration yaml,TexturedConfiguration renderer,PhotoRuntime.Mode mode,List<String> changes) {
        public Result{changes=List.copyOf(changes);}
    }
    public enum Checkpoint { AFTER_BACKUP, BEFORE_REPLACE }
    @FunctionalInterface public interface Faults {void at(Checkpoint checkpoint)throws IOException;}
    private static final int MAXIMUM=1024*1024;
    private UnifiedRendererConfig() {}
    public static Result load(Path directory,String defaults)throws Exception {return load(directory,defaults,p->{});}
    public static Result load(Path directory,String defaults,Faults faults)throws Exception {
        Path file=directory.resolve("config.yml");if(Files.isSymbolicLink(file))throw new IOException("拒绝覆盖符号链接配置："+file);
        byte[] original=Files.exists(file)?bytes(file):null;
        Map<String,Object> bundled=yaml(defaults),existing=original==null?new LinkedHashMap<>():yaml(new String(original,StandardCharsets.UTF_8));
        Map<String,Object> merged=copy(existing);List<String> changes=new ArrayList<>();
        int schema=existing.containsKey("render-v2-schema")?integer(existing.get("render-v2-schema"),"render-v2-schema"):0;
        if(schema<0 || schema>11)throw new IllegalArgumentException("不支持的render-v2-schema");
        boolean migrated=schema>0;
        Map<String,Object> imported=new LinkedHashMap<>();
        if(!migrated && original!=null) {
            Object setting=existing.getOrDefault("textured-config","render-v2.json");
            if(!(setting instanceof String name) || name.isBlank())throw new IllegalArgumentException("旧textured-config不是有效路径");
            Path old=directory.resolve(name).normalize();
            if(Files.exists(old)) {
                try {imported=object(jsonValue(TexturedConfiguration.readObject(old)),"旧JSON");}
                catch(Exception error){throw new IllegalArgumentException("旧V2配置迁移失败，原件未修改："+old,error);}
                changes.add("从旧JSON导入缺失字段，保留原JSON："+old);
            }
        }
        Map<String,Object> explicit=existing.containsKey("render-v2")?object(existing.get("render-v2"),"render-v2"):new LinkedHashMap<>();
        Map<String,Object> v2=merge(copy(imported),explicit);
        if(!migrated && original!=null) {
            upgrade(v2,"capture","maximumSnapshotBytes",67108864L,402653184L,changes);
            upgrade(v2,"capture","maxChunksPerRequest",64,192,changes);
            upgrade(v2,"preparation","maximumInstances",4000,8192,changes);
            upgrade(v2,"visuals","entriesPerTick",64,512,changes);
            upgrade(v2,"assetBuild","dispatchNanos",5000000,1000000,changes);
        }
        if(schema<2 && original!=null) {
            upgrade(v2,"capture","maximumSnapshotBytes",268435456L,402653184L,changes);
            upgrade(v2,"capture","maxChunksPerRequest",128,192,changes);
        }
        if(schema<3 && original!=null) {
            upgrade(v2,"preparation","maximumInstances",8192,20480,changes);
            upgrade(v2,"preparation","maximumIndexEntries",65536,196608,changes);
        }
        if(schema<4 && original!=null) {
            upgrade(v2,"pipeline","cpuMillisPerSecond",200,1000,changes);
            upgrade(v2,"pipeline","burstMillis",25,50,changes);
            upgrade(v2,"pipeline","dispatchIntervalNanos",5000000,1000000,changes);
        }
        if(schema<5 && original!=null) {
            Map<String,Object> policy=v2.containsKey("pipeline")?object(v2.get("pipeline"),"pipeline"):Map.of();
            boolean templateCpu=!policy.containsKey("cpuMillisPerSecond") || new BigDecimal(policy.get("cpuMillisPerSecond").toString()).compareTo(BigDecimal.valueOf(1000))==0;
            boolean templateBurst=!policy.containsKey("burstMillis") || new BigDecimal(policy.get("burstMillis").toString()).compareTo(BigDecimal.valueOf(50))==0;
            if(templateCpu && templateBurst)upgrade(v2,"pipeline","workers",1,2,changes);
            upgrade(v2,"pipeline","cpuMillisPerSecond",1000,2000,changes);
            upgrade(v2,"pipeline","burstMillis",50,100,changes);
        }
        if(schema<6 && original!=null) {
            upgrade(v2,"capture","maxChunksPerRequest",192,384,changes);
            upgrade(v2,"preparation","maximumScannedCells",500000,4000000,changes);
            upgrade(v2,"preparation","maximumInstances",20480,65536,changes);
            upgrade(v2,"preparation","maximumIndexEntries",196608,786432,changes);
        }
        if(schema<7) {
            Map<String,Object> policy=v2.containsKey("pipeline")?object(v2.get("pipeline"),"pipeline"):new LinkedHashMap<>();
            if(!policy.containsKey("scanBatchCpuNanos") && policy.containsKey("maxBatchCpuNanos")) {
                Object batch=policy.get("maxBatchCpuNanos");Object burst=policy.getOrDefault("burstMillis",100);
                boolean custom=!(batch instanceof Number n) || new BigDecimal(n.toString()).compareTo(BigDecimal.valueOf(8000000))!=0;
                boolean low=burst instanceof Number n && n.doubleValue()<10;
                if(custom || low){policy.put("scanBatchCpuNanos",batch);v2.put("pipeline",policy);}
            }
        }
        if(schema<8 && "resources/base-client.jar".equals(v2.get("baseResources")) && Files.notExists(directory.resolve("resources/base-client.jar"))) {v2.put("baseResources","bundled");changes.add("缺失的旧默认客户端来源切换为插件内置资源；自定义路径与已有文件未覆盖");}
        if(schema<10 && original!=null){
            upgrade(v2,"capture","maxInflight",1,2,changes);upgrade(v2,"capture","startsPerTick",1,2,changes);
            if(v2.containsKey("capture")) {
                Map<String,Object> policy=object(v2.get("capture"),"capture");
                if(!policy.containsKey("windowChunks")) {
                    long chunks=policy.get("maxChunksPerRequest") instanceof Number n?n.longValue():384;
                    long perChunk=policy.get("bytesPerChunk") instanceof Number n?n.longValue():2097152;
                    long total=policy.get("maximumSnapshotBytes") instanceof Number n?n.longValue():402653184;
                    if(chunks<2 || perChunk>0 && total/perChunk<2){policy.put("windowChunks",1);v2.put("capture",policy);changes.add("自定义快照预算仅容纳一块，捕获窗口保留1，未扩大内存");}
                }
            }
        }
        if(schema<11 && original!=null) {
            upgrade(v2,"visuals","maximumStates",50000,100000,changes);
            upgrade(v2,"visuals","maximumFrozenBytes",67108864L,134217728L,changes);
        }
        Map<String,Object> oldShot=v2.containsKey("shot")?object(v2.get("shot"),"render-v2.shot"):new LinkedHashMap<>();
        Object oldDistance=oldShot.remove("distance");if(v2.containsKey("shot"))v2.put("shot",oldShot);
        Object distance=existing.containsKey("render-distance")?existing.get("render-distance"):oldDistance!=null?oldDistance:bundled.get("render-distance");
        int range=integer(distance,"render-distance");if(range<16 || range>256)throw new IllegalArgumentException("render-distance必须为16–256格");
        if(oldDistance!=null && integer(oldDistance,"旧shot.distance")!=range)changes.add("距离统一使用config.yml的render-distance="+range+"，移除旧shot.distance重复来源");
        v2=defaults(v2,object(bundled.get("render-v2"),"内置render-v2"));
        merged=defaults(merged,bundled);merged.put("render-v2",v2);merged.put("render-distance",range);merged.put("render-v2-schema",11);merged.remove("textured-config");
        Object selection=merged.get("renderer-mode");if(!(selection instanceof String))throw new IllegalArgumentException("renderer-mode必须为TEXTURED或LEGACY字符串");
        PhotoRuntime.Mode mode;
        try{mode=PhotoRuntime.Mode.valueOf(((String)selection).toUpperCase(Locale.ROOT));}catch(IllegalArgumentException error){throw new IllegalArgumentException("renderer-mode只允许TEXTURED/LEGACY",error);}
        JsonObject runtime=new Gson().toJsonTree(v2).getAsJsonObject();runtime.getAsJsonObject("shot").addProperty("distance",range);
        TexturedConfiguration renderer=TexturedConfiguration.parse(runtime);
        boolean changed=original==null || !merged.equals(existing);
        YamlConfiguration output=new YamlConfiguration();output.options().parseComments(true);
        output.loadFromString(original==null?defaults:new String(original,StandardCharsets.UTF_8));
        if(changed)for(var entry:merged.entrySet())output.set(entry.getKey(),entry.getValue());
        output.set("textured-config",null);
        if(changed) {
            output.setComments("render-v2",List.of("如果你不清楚这是什么，请不要修改参数。"));
            if(renderer.bundledBase())output.setComments("render-v2.baseResources",List.of("默认使用插件内置26.3纯资源数据，无需上传客户端JAR；显式自定义路径仍可覆盖。"));
            if(original!=null) {
                Path backup=file.resolveSibling("config.yml.pre-v2-"+UUID.randomUUID()+".bak");Files.write(backup,original,StandardOpenOption.CREATE_NEW);
                changes.add("原YAML已备份："+backup);faults.at(Checkpoint.AFTER_BACKUP);
            }
            Files.createDirectories(directory);Path temporary=Files.createTempFile(directory,"config-v2-",".tmp");
            try {
                byte[] content=output.saveToString().getBytes(StandardCharsets.UTF_8);
                try(FileChannel channel=FileChannel.open(temporary,StandardOpenOption.WRITE)) {
                    ByteBuffer buffer=ByteBuffer.wrap(content);while(buffer.hasRemaining())channel.write(buffer);channel.force(true);
                }
                faults.at(Checkpoint.BEFORE_REPLACE);
                if(original==null?Files.exists(file):!Arrays.equals(original,bytes(file)))throw new IOException("迁移期间config.yml被其它操作修改，未覆盖新版本");
                Files.move(temporary,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
            } finally {Files.deleteIfExists(temporary);}
            changes.add("配置已统一到config.yml，renderer-mode="+mode+"，距离="+range);
        }
        return new Result(output,renderer,mode,changes);
    }
    private static Object jsonValue(JsonElement value) {
        if(value.isJsonNull())return null;
        if(value.isJsonObject()){Map<String,Object> result=new LinkedHashMap<>();value.getAsJsonObject().entrySet().forEach(e->result.put(e.getKey(),jsonValue(e.getValue())));return result;}
        if(value.isJsonArray()){List<Object> result=new ArrayList<>();value.getAsJsonArray().forEach(e->result.add(jsonValue(e)));return result;}
        var primitive=value.getAsJsonPrimitive();if(primitive.isBoolean())return primitive.getAsBoolean();if(primitive.isString())return primitive.getAsString();
        BigDecimal number=new BigDecimal(primitive.getAsString());
        if(number.stripTrailingZeros().scale()<=0)return number.longValueExact();
        return number.doubleValue();
    }
    private static void upgrade(Map<String,Object> root,String section,String key,long previous,long next,List<String> changes) {
        if(!root.containsKey(section))return;Map<String,Object> values=object(root.get(section),section);
        if(values.containsKey(key) && values.get(key) instanceof Number number && new BigDecimal(number.toString()).compareTo(BigDecimal.valueOf(previous))==0) {
            values.put(key,next);root.put(section,values);changes.add("升级旧模板默认：render-v2."+section+"."+key+"="+next);
        }
    }
    private static int integer(Object value,String name) {
        if(!(value instanceof Number number))throw new IllegalArgumentException(name+"必须为整数，不能是引号字符串");
        try{return new BigDecimal(number.toString()).intValueExact();}catch(ArithmeticException error){throw new IllegalArgumentException(name+"不是有效整数",error);}
    }
    private static byte[] bytes(Path path)throws IOException {
        try(InputStream input=Files.newInputStream(path)){byte[] value=input.readNBytes(MAXIMUM+1);if(value.length>MAXIMUM)throw new IOException("配置超过1MiB："+path);return value;}
    }
    private static Map<String,Object> yaml(String text) {
        if(text.length()>MAXIMUM)throw new IllegalArgumentException("配置超过1MiB");
        LoaderOptions options=new LoaderOptions();options.setAllowDuplicateKeys(false);options.setNestingDepthLimit(32);options.setCodePointLimit(MAXIMUM);options.setMaxAliasesForCollections(0);
        Object value=new Yaml(new SafeConstructor(options)).load(text);validateNodes(value,0,new int[]{0});return value==null?new LinkedHashMap<>():object(value,"config.yml");
    }
    private static void validateNodes(Object value,int depth,int[] count) {
        if(depth>32 || ++count[0]>40000)throw new IllegalArgumentException("YAML结构超过限额");
        if(value instanceof Map<?,?> map)for(var entry:map.entrySet()){if(!(entry.getKey() instanceof String))throw new IllegalArgumentException("配置键必须为字符串");validateNodes(entry.getValue(),depth+1,count);}
        else if(value instanceof List<?> list)for(Object child:list)validateNodes(child,depth+1,count);
        else if(value!=null && !(value instanceof String || value instanceof Number || value instanceof Boolean))throw new IllegalArgumentException("配置只允许基本数据类型");
    }
    private static Map<String,Object> object(Object value,String name) {
        if(!(value instanceof Map<?,?> map))throw new IllegalArgumentException(name+"必须为配置对象，不能是null或标量");
        Map<String,Object> result=new LinkedHashMap<>();for(var entry:map.entrySet()){if(!(entry.getKey() instanceof String key))throw new IllegalArgumentException(name+"键不是字符串");result.put(key,entry.getValue());}return result;
    }
    private static Map<String,Object> copy(Map<String,Object> source) {
        Map<String,Object> result=new LinkedHashMap<>();source.forEach((k,v)->result.put(k,v instanceof Map<?,?>?copy(object(v,k)):v));return result;
    }
    private static Map<String,Object> merge(Map<String,Object> base,Map<String,Object> explicit) {
        explicit.forEach((k,v)->{Object old=base.get(k);base.put(k,old instanceof Map<?,?> && v instanceof Map<?,?>?merge(object(old,k),object(v,k)):v);});return base;
    }
    private static Map<String,Object> defaults(Map<String,Object> values,Map<String,Object> base) {
        base.forEach((k,v)->{if(!values.containsKey(k))values.put(k,v instanceof Map<?,?>?copy(object(v,k)):v);
            else if(v instanceof Map<?,?> && values.get(k) instanceof Map<?,?>)values.put(k,defaults(object(values.get(k),k),object(v,k)));});return values;
    }
}
