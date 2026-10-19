package com.wenkrang.famara.render.asset;

import com.google.gson.*;
import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.LongSupplier;
import java.util.function.ToLongFunction;
import java.util.stream.Stream;
import java.util.zip.*;

/** 先索引再按方块依赖闭包读取。大磁盘ZIP不等于内存工作集，不读取无关物品/实体资源。 */
public final class BlockResourceCursor implements AutoCloseable {
    public record Input(Path path,ResourcePack.Role role,boolean required,String sha256) {}
    public record Metrics(long indexedBytes,long selectedBytes,int indexedEntries,int selectedEntries,long indexPeakBytes,int retainedPaths) {}
    private record Stamp(long size,Object key,java.nio.file.attribute.FileTime modified) {}
    private static final class Layer implements AutoCloseable {
        final Input input;final TreeMap<String,Stamp> names=new TreeMap<>();final TreeMap<String,byte[]> selected=new TreeMap<>();
        ResourceFileSnapshot copy;ZipFile zip;Path directory;Stream<Path> walk;Iterator<Path> files;Enumeration<? extends ZipEntry> entries;
        Layer(Input input){this.input=input;}
        public void close()throws IOException {IOException failure=null;if(walk!=null)walk.close();walk=null;if(zip!=null)try{zip.close();}catch(IOException error){failure=error;}zip=null;if(copy!=null)try{copy.close();}catch(IOException error){if(failure==null)failure=error;else failure.addSuppressed(error);}copy=null;names.clear();selected.clear();files=null;entries=null;if(failure!=null)throw failure;}
    }
    private enum Stage { INDEX, ROOTS, DEPENDENCIES, VERIFY, FINISH, DONE }
    private final List<Input> inputs;
    private final List<Layer> layers=new ArrayList<>();
    private final AssetLimits limits;
    private final long maximumArchiveBytes,indexLimit;
    private final ToLongFunction<String> zipFingerprint;
    private final ArrayDeque<String> pending=new ArrayDeque<>();
    private final Set<String> requested=new HashSet<>();
    private Stage stage=Stage.INDEX;
    private Layer current;
    private int source,entries,selectedEntries,verifyLayer,retainedPaths;
    private long indexBytes,selectedBytes,requestBytes,temporaryJson,diskBytes,indexPeakBytes,nameIndexBytes;
    private Iterator<String> rootIterator;
    private Iterator<Map.Entry<String,byte[]>> verification;
    private ResourcePack result;
    private boolean closed,failed;
    public BlockResourceCursor(List<Input> inputs,AssetLimits limits,long maximumArchiveBytes) {this(inputs,limits,maximumArchiveBytes,null);}
    BlockResourceCursor(List<Input> inputs,AssetLimits limits,long maximumArchiveBytes,ToLongFunction<String> zipFingerprint) {
        this.zipFingerprint=zipFingerprint;
        this.inputs=List.copyOf(inputs);this.limits=Objects.requireNonNull(limits);if(maximumArchiveBytes<=0)throw new IllegalArgumentException("来源磁盘大小限额无效");this.maximumArchiveBytes=maximumArchiveBytes;
        indexLimit=Math.min(64L*1024*1024,limits.maxTotalBytes()/2);indexBytes=inputs.size()*2048L;if(indexBytes>indexLimit)throw limit("sources","来源数量/元数据",indexBytes,indexLimit);indexPeakBytes=indexBytes;
    }
    public Metrics metrics(){return new Metrics(indexBytes,selectedBytes,entries,selectedEntries,indexPeakBytes,retainedPaths);}
    public boolean step(int operations,long deadline,LongSupplier clock)throws IOException {
        if(closed || failed || operations<=0)throw new IllegalStateException("方块依赖来源已关闭、失败或步长无效");
        try{for(int n=0;n<operations && stage!=Stage.DONE && clock.getAsLong()<deadline;n++)advance(deadline,clock);return stage==Stage.DONE;}
        catch(IOException | RuntimeException | Error error){failed=true;throw error;}
    }
    private void advance(long deadline,LongSupplier clock)throws IOException {
        switch(stage) {
            case INDEX -> index(deadline,clock);
            case ROOTS -> {if(rootIterator==null)rootIterator=layers.stream().flatMap(layer->layer.names.keySet().stream().filter(name->blockstate(name) || builtinRoot(name,layer.input.role))).iterator();if(rootIterator.hasNext())request(rootIterator.next());else{rootIterator=null;stage=Stage.DEPENDENCIES;}}
            case DEPENDENCIES -> {if(pending.isEmpty()){stage=Stage.VERIFY;return;}try{readDependency(pending.removeFirst());}finally{temporaryJson=0;}}
            case VERIFY -> verify();
            case FINISH -> finish();
            case DONE -> {}
        }
    }
    private void index(long deadline,LongSupplier clock)throws IOException {
        if(collisionEntries!=null){verifyCollision();return;}
        if(current==null) {
            if(source==inputs.size()){stage=Stage.ROOTS;return;}
            Input input=inputs.get(source++);
            if(!input.required && (!input.path.getFileSystem().isOpen() || Files.notExists(input.path)))return;
            if(input.sha256!=null && !Files.isRegularFile(input.path,LinkOption.NOFOLLOW_LINKS))throw new IOException("内置基础来源不是普通文件");
            current=new Layer(input);layers.add(current);
            if(Files.isDirectory(input.path)) {current.directory=input.path.toRealPath();current.walk=Files.walk(current.directory);current.files=current.walk.iterator();}
            else {long size=Files.size(input.path);if(size>maximumArchiveBytes-diskBytes)throw limit(input.path.toString(),"来源压缩文件累计磁盘字节",diskBytes+size,maximumArchiveBytes);diskBytes+=size;current.copy=new ResourceFileSnapshot(input.path,maximumArchiveBytes,input.sha256);}
            return;
        }
        if(current.directory==null && current.zip==null) {
            if(!current.copy.step(1,deadline,clock))return;
            String path=current.input.path.toString();long catalog=ZipDirectoryBudget.estimatedBytes(current.copy.path());chargeIndex(path,"ZIP中央目录工作集",catalog);indexBytes+=catalog;
            current.zip=new ZipFile(current.copy.path().toFile());int count=current.zip.size();
            if(count>limits.maxEntries()-entries)throw limit(path,"来源目录项",(long)entries+count,limits.maxEntries());
            zipNames=new ZipNameIndex(count,bytes->{chargeIndex(path,"ZIP紧凑重名索引",bytes);nameIndexBytes=bytes;},zipFingerprint);
            current.entries=current.zip.entries();return;
        }
        if(current.directory!=null) {
            if(!current.files.hasNext()){current.walk.close();current.walk=null;current.files=null;current=null;return;}
            Path file=current.files.next();count();if(!Files.isRegularFile(file))return;
            String name=current.directory.relativize(file).toString().replace(File.separatorChar,'/');ResourcePack.validatePath(name);
            if(!file.toRealPath().startsWith(current.directory))throw new AssetException(AssetException.Code.INVALID,name,"资源链接逃出来源目录");
            if(candidate(name))putName(current,name,stamp(file));
        } else {
            if(!current.entries.hasMoreElements()){current.entries=null;zipNames=null;nameIndexBytes=0;current=null;return;}
            ZipEntry entry=current.entries.nextElement();count();if(entry.isDirectory())return;String name=entry.getName();ResourcePack.validatePath(name);
            // 全包校验不保留完整名称；指纹相同时分片复核原名，不能靠哈希判重。
            if(!zipNames.add(name)){collisionName=name;collisionMatches=0;collisionEntries=current.zip.entries();}
            if(blockstate(name) || builtinRoot(name,current.input.role))putName(current,name,new Stamp(entry.getSize(),null,null));
        }
    }
    private ZipNameIndex zipNames;
    private Enumeration<? extends ZipEntry> collisionEntries;
    private String collisionName;
    private int collisionMatches;
    private void verifyCollision() {
        if(!collisionEntries.hasMoreElements()){collisionEntries=null;collisionName=null;return;}
        ZipEntry entry=collisionEntries.nextElement();
        if(!entry.isDirectory() && collisionName.equals(entry.getName()) && ++collisionMatches>1)
            throw new AssetException(AssetException.Code.INVALID,collisionName,"重复ZIP条目");
    }
    private void chargeIndex(String name,String domain,long bytes) {
        long used=indexBytes+nameIndexBytes;
        if(bytes>indexLimit-used)throw limit(name,domain,used+bytes,indexLimit);
        indexPeakBytes=Math.max(indexPeakBytes,used+bytes);
    }
    private long workingBytes(){return indexBytes+nameIndexBytes+selectedBytes+requestBytes+temporaryJson;}
    private void requireWorking(String name,String domain,long bytes) {
        long used=workingBytes();if(bytes>limits.maxTotalBytes()-used)throw limit(name,domain,used+bytes,limits.maxTotalBytes());
    }
    private void count(){if(++entries>limits.maxEntries())throw limit("resource-index","来源目录项",entries,limits.maxEntries());}
    private void putName(Layer layer,String name,Stamp stamp) {
        if(layer.names.containsKey(name))throw new AssetException(AssetException.Code.INVALID,name,"重复资源条目");
        long size=512L+name.length()*4L;chargeIndex(name,"来源索引字节",size);requireWorking(name,"来源路径存活工作集",size);indexBytes+=size;
        layer.names.put(name,stamp);retainedPaths++;
    }
    private Stamp find(Layer layer,String name) {
        Stamp indexed=layer.names.get(name);if(indexed!=null || layer.zip==null)return indexed;
        ZipEntry entry=layer.zip.getEntry(name);
        return entry==null || entry.isDirectory()?null:new Stamp(entry.getSize(),null,null);
    }
    private void request(String path) {
        ResourcePack.validatePath(path);if(requested.contains(path))return;
        long cost=256L+path.length()*4L;requireWorking(path,"依赖队列/路径存活工作集",cost);requestBytes+=cost;requested.add(path);
        if(requested.size()>limits.maxEntries())throw limit(path,"依赖路径数",requested.size(),limits.maxEntries());pending.addLast(path);
    }
    private void readDependency(String name)throws IOException {
        if(blockstate(name)) {
            for(Layer layer:layers)if(layer.names.containsKey(name)){byte[] data=select(layer,name);JsonObject json=json(name,data);JsonElement variants=json.get("variants");if(variants!=null)for(var entry:variants.getAsJsonObject().entrySet())models(entry.getValue());JsonElement multipart=json.get("multipart");if(multipart!=null)for(var part:multipart.getAsJsonArray())models(part.getAsJsonObject().get("apply"));}
            return;
        }
        Layer selected=null;
        for(Layer layer:layers)if(!(layer.input.role==ResourcePack.Role.CUSTOM && (name.equals(NativeModelDataReader.DATA) || name.endsWith(".class"))) && find(layer,name)!=null){selected=layer;break;}
        if(selected==null){String alias=ResourcePack.aliasPath(name);if(alias!=null)request(alias);return;}
        byte[] data=select(selected,name);
        if(name.contains("/models/") && name.endsWith(".json")) {
            JsonObject json=json(name,data);if(json.has("parent")){String key=json.get("parent").getAsString();if(!key.startsWith("builtin/") && !key.startsWith("minecraft:builtin/"))request(ResourceKey.parse(key).file("models",".json"));}
            if(json.has("textures"))for(var entry:json.getAsJsonObject("textures").entrySet())texture(entry.getValue());
            if(json.has("elements"))for(var element:json.getAsJsonArray("elements")){JsonObject item=element.getAsJsonObject();if(item.has("faces"))for(var entry:item.getAsJsonObject("faces").entrySet())texture(entry.getValue().getAsJsonObject().get("texture"));}
        } else if(name.endsWith(".png"))request(name+".mcmeta");
    }
    private JsonObject json(String name,byte[] data) {
        // 闭包发现只是临时解析，编译阶段另有持久预约；单次树仍受相同节点/深度/字节上限。
        var one=ResourcePack.owned("dependency",Map.of(name,data),"dependency");long available=limits.maxTotalBytes()-workingBytes();if(available<=0)throw limit(name,"依赖临时JSON空间",1,Math.max(0,available));
        var budget=new AssetMemoryBudget(available);JsonObject parsed=BoundedJson.read(one.require(name),name,limits,budget);temporaryJson=budget.reservedBytes();return parsed;
    }
    private void models(JsonElement value) {
        if(value==null)return;if(value.isJsonArray()){for(var child:value.getAsJsonArray())models(child);return;}
        if(value.getAsJsonObject().has("model"))request(ResourceKey.parse(value.getAsJsonObject().get("model").getAsString()).file("models",".json"));
    }
    private void texture(JsonElement value) {
        if(value==null)return;String key;
        if(value.isJsonObject()){JsonElement sprite=value.getAsJsonObject().get("sprite");if(sprite==null)return;key=sprite.getAsString();}else key=value.getAsString();
        if(!key.startsWith("#"))request(ResourceKey.parse(key).file("textures",".png"));
    }
    private byte[] select(Layer layer,String name)throws IOException {
        byte[] cached=layer.selected.get(name);if(cached!=null)return cached;Stamp expected=find(layer,name);
        if(expected==null)throw new AssetException(AssetException.Code.MISSING,name,"资源不存在");
        if(expected.size>limits.maxEntryBytes())throw limit(name,"实际方块依赖单文件字节",expected.size,limits.maxEntryBytes());
        if(!layer.names.containsKey(name))putName(layer,name,expected);
        requireWorking(name,"依赖内容加来源索引",Math.max(0,expected.size));
        byte[] data=read(layer,name,expected);requireWorking(name,"依赖内容加来源索引",data.length);
        layer.selected.put(name,data);selectedEntries++;selectedBytes+=data.length;return data;
    }
    private byte[] read(Layer layer,String name,Stamp expected)throws IOException {
        if(layer.directory!=null) {
            Path file=layer.directory.resolve(name);if(!file.toRealPath().startsWith(layer.directory) || !expected.equals(stamp(file)))throw changed(name);
            byte[] data;try(var input=Files.newInputStream(file)){data=bounded(input,name);}
            if(!expected.equals(stamp(file)))throw changed(name);return data;
        }
        ZipEntry entry=layer.zip.getEntry(name);try(var input=layer.zip.getInputStream(entry)){return bounded(input,name);}
    }
    private byte[] bounded(InputStream input,String name)throws IOException {byte[] value=input.readNBytes(limits.maxEntryBytes()+1);if(value.length>limits.maxEntryBytes())throw limit(name,"依赖读取字节",value.length,limits.maxEntryBytes());return value;}
    private int verifiedNames;
    private void verify()throws IOException {
        if(verifyLayer==layers.size()){stage=Stage.FINISH;return;}Layer layer=layers.get(verifyLayer);
        if(layer.directory==null){verifyLayer++;return;}
        if(layer.walk==null){layer.walk=Files.walk(layer.directory);layer.files=layer.walk.iterator();verifiedNames=0;}
        if(!layer.files.hasNext()) {
            layer.walk.close();layer.walk=null;layer.files=null;if(verifiedNames!=layer.names.size())throw changed(layer.input.path.toString());verifyLayer++;return;
        }
        Path path=layer.files.next();if(!Files.isRegularFile(path))return;String name=layer.directory.relativize(path).toString().replace(File.separatorChar,'/');ResourcePack.validatePath(name);
        if(!candidate(name))return;verifiedNames++;Stamp old=layer.names.get(name);
        if(old==null || !old.equals(stamp(path)) || !path.toRealPath().startsWith(layer.directory))throw changed(name);
        byte[] selected=layer.selected.get(name);if(selected!=null && !Arrays.equals(selected,read(layer,name,old)))throw changed(name);
    }
    private void finish()throws IOException {
        long overlap=selectedEntries*512L+layers.size()*2048L;requireWorking("source-freeze","索引与资源封存容器重叠",overlap);
        List<ResourcePack> selected=new ArrayList<>();
        for(Layer layer:layers) {
            MessageDigest hash=ResourcePack.sha256();for(var entry:layer.selected.entrySet()){hash.update(entry.getKey().getBytes(java.nio.charset.StandardCharsets.UTF_8));hash.update((byte)0);hash.update(entry.getValue());}
            selected.add(ResourcePack.owned(layer.input.path.toString(),layer.selected,HexFormat.of().formatHex(hash.digest())).withRole(layer.input.role));
        }
        result=ResourcePack.layer(selected,limits);for(Layer layer:layers)layer.close();layers.clear();zipNames=null;collisionEntries=null;collisionName=null;requested.clear();rootIterator=null;stage=Stage.DONE;
    }
    private static boolean candidate(String name){return name.equals("pack.mcmeta") || name.equals("version.json") || name.equals(NativeModelDataReader.DATA) || name.equals(NativeModelDataReader.OWNER+".class") || name.startsWith("assets/") && (name.contains("/blockstates/") && name.endsWith(".json") || name.contains("/models/") && name.endsWith(".json") || name.contains("/textures/") && (name.endsWith(".png") || name.endsWith(".png.mcmeta")));}
    private static boolean blockstate(String name){return name.matches("assets/[^/]+/blockstates/.+[.]json");}
    private static boolean builtinRoot(String name,ResourcePack.Role role) {
        if(name.equals("pack.mcmeta") || name.equals("version.json"))return true;
        if(role!=ResourcePack.Role.CUSTOM && (name.equals(NativeModelDataReader.DATA) || name.equals(NativeModelDataReader.OWNER+".class")))return true;
        if(name.startsWith("assets/minecraft/textures/colormap/"))return true;
        // 这些是BuiltinBlockModels明确使用的非JSON模型纹理，不包括任意namespace实体包。
        return name.startsWith("assets/minecraft/textures/") && (name.endsWith(".png") || name.endsWith(".png.mcmeta")) && (name.matches("assets/minecraft/textures/block/(water|lava)_(still|flow)[.]png(?:[.]mcmeta)?") || name.matches("assets/minecraft/textures/entity/(chest/.*|shulker/.*|banner/.*|conduit/base[.]png.*|end_portal/end_portal[.]png.*|decorated_pot/.*|copper_golem/.*|skeleton/skeleton[.]png.*|skeleton/wither_skeleton[.]png.*|zombie/zombie[.]png.*|creeper/creeper[.]png.*|player/wide/steve[.]png.*|piglin/piglin[.]png.*|enderdragon/dragon[.]png.*)"));
    }
    public ResourcePack result(){if(result==null || failed || closed)throw new IllegalStateException("方块依赖来源尚未完成");return result;}
    private static Stamp stamp(Path file)throws IOException {BasicFileAttributes a=Files.readAttributes(file,BasicFileAttributes.class);if(!a.isRegularFile())throw changed(file.toString());return new Stamp(a.size(),a.fileKey(),a.lastModifiedTime());}
    private static AssetException changed(String name){return new AssetException(AssetException.Code.SOURCE_CHANGED,name,"选中方块资源在冻结期间变化");}
    private static AssetException limit(String name,String domain,long actual,long maximum){return new AssetException(AssetException.Code.LIMIT,name,domain+"超过限额：请求="+actual+"，上限="+maximum);}
    @Override public void close()throws IOException {if(closed)return;closed=true;IOException error=null;for(Layer layer:layers)try{layer.close();}catch(IOException failure){if(error==null)error=failure;else error.addSuppressed(failure);}layers.clear();requested.clear();pending.clear();zipNames=null;nameIndexBytes=0;collisionEntries=null;collisionName=null;rootIterator=null;current=null;result=null;if(error!=null)throw error;}
}
