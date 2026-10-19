package com.wenkrang.famara.render.asset;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.LongSupplier;
import java.util.stream.Stream;
import java.util.zip.*;

/** 按目录项/ZIP项/256KiB哈希片段让出的资源读取器，只由一个资产worker拥有。 */
public final class ResourcePackCursor implements AutoCloseable {
    private enum Stage { INIT, HASH_BEFORE, READ, VERIFY_DIRECTORY, HASH_AFTER, DIGEST_DIRECTORY, DONE }
    private final Path path;
    private final AssetLimits limits;
    private final Runnable beforeVerification;
    private final TreeMap<String,byte[]> files=new TreeMap<>();
    private final Set<String> names=new HashSet<>();
    private final byte[] hashBuffer=new byte[256*1024];
    private Stage stage=Stage.INIT;
    private Path directory;
    private Stream<Path> walk;
    private Iterator<Path> paths;
    private ZipFile zip;
    private Enumeration<? extends ZipEntry> entries;
    private InputStream hashInput;
    private MessageDigest hash;
    private String initialHash;
    private Iterator<Map.Entry<String,byte[]>> digestEntries;
    private int count,verified;
    private long bytes;
    private ResourcePack result;
    private boolean closed,poisoned;
    public ResourcePackCursor(Path path,AssetLimits limits) { this(path,limits,()->{}); }
    ResourcePackCursor(Path path,AssetLimits limits,Runnable beforeVerification) {
        this.path=Objects.requireNonNull(path);this.limits=Objects.requireNonNull(limits);this.beforeVerification=beforeVerification;
    }
    public boolean step(int maxOperations,long deadlineNanos,LongSupplier clock) throws IOException {
        if(closed || poisoned || maxOperations<=0)throw new IllegalStateException("资源读取游标已关闭或批次无效");
        try {
            for(int n=0;n<maxOperations && stage!=Stage.DONE;n++) {
                if(clock.getAsLong()>=deadlineNanos)return false;
                advance();
            }
            return stage==Stage.DONE;
        } catch(IOException | RuntimeException | Error error){poisoned=true;throw error;}
    }
    public ResourcePack result() {
        if(closed || result==null)throw new IllegalStateException("资源尚未读取完成");
        return result;
    }
    public long retainedBytes() { return bytes; }
    private void advance() throws IOException {
        switch(stage) {
            case INIT -> {
                if(Files.isDirectory(path)) {
                    directory=path.toRealPath();walk=Files.walk(directory);paths=walk.iterator();stage=Stage.READ;
                } else {
                    if(Files.size(path)>limits.maxTotalBytes())throw limit(path.toString());
                    hash=ResourcePack.sha256();hashInput=Files.newInputStream(path);stage=Stage.HASH_BEFORE;
                }
            }
            case HASH_BEFORE,HASH_AFTER -> {
                int n=hashInput.read(hashBuffer);
                if(n>=0) { hash.update(hashBuffer,0,n);return; }
                hashInput.close();hashInput=null;
                String value=HexFormat.of().formatHex(hash.digest());
                if(stage==Stage.HASH_BEFORE) {
                    initialHash=value;zip=new ZipFile(path.toFile());entries=zip.entries();stage=Stage.READ;
                } else {
                    if(!initialHash.equals(value))throw changed();
                    finish(initialHash);
                }
            }
            case READ -> {
                if(directory!=null)readDirectoryEntry(false);
                else readZipEntry();
            }
            case VERIFY_DIRECTORY -> readDirectoryEntry(true);
            case DIGEST_DIRECTORY -> {
                if(!digestEntries.hasNext()) { finish(HexFormat.of().formatHex(hash.digest()));return; }
                var entry=digestEntries.next();
                hash.update(entry.getKey().getBytes(StandardCharsets.UTF_8));hash.update((byte)0);
                hash.update(ByteBuffer.allocate(8).putLong(entry.getValue().length).array());hash.update(entry.getValue());
            }
            case DONE -> {}
        }
    }
    private void readZipEntry() throws IOException {
        if(!entries.hasMoreElements()) {
            zip.close();zip=null;entries=null;beforeVerification.run();
            hash=ResourcePack.sha256();hashInput=Files.newInputStream(path);stage=Stage.HASH_AFTER;return;
        }
        ZipEntry entry=entries.nextElement();checkCount();
        if(entry.isDirectory())return;
        String name=entry.getName();ResourcePack.validatePath(name);
        if(!names.add(name))throw new AssetException(AssetException.Code.INVALID,name,"重复ZIP条目");
        if(!ResourcePack.relevant(name))return;
        if(entry.getSize()>limits.maxEntryBytes())throw limit(name);
        try(InputStream in=zip.getInputStream(entry)) { put(name,read(in,name)); }
    }
    private void readDirectoryEntry(boolean verify) throws IOException {
        if(!paths.hasNext()) {
            walk.close();walk=null;paths=null;
            if(!verify) {
                beforeVerification.run();count=0;walk=Files.walk(directory);paths=walk.iterator();stage=Stage.VERIFY_DIRECTORY;
            } else {
                if(verified!=files.size())throw changed();
                hash=ResourcePack.sha256();digestEntries=files.entrySet().iterator();stage=Stage.DIGEST_DIRECTORY;
            }
            return;
        }
        Path file=paths.next();checkCount();
        if(!Files.isRegularFile(file))return;
        String name=directory.relativize(file).toString().replace(File.separatorChar,'/');ResourcePack.validatePath(name);
        if(!ResourcePack.relevant(name))return;
        if(!file.toRealPath().startsWith(directory))throw new AssetException(AssetException.Code.INVALID,name,"资源链接逃出目录");
        if(Files.size(file)>limits.maxEntryBytes())throw limit(name);
        byte[] data;
        try(InputStream in=Files.newInputStream(file)) { data=read(in,name); }
        if(verify) {
            if(!Arrays.equals(files.get(name),data))throw changed();
            verified++;
        } else put(name,data);
    }
    private byte[] read(InputStream in,String name) throws IOException {
        byte[] data=in.readNBytes(limits.maxEntryBytes()+1);
        if(data.length>limits.maxEntryBytes())throw limit(name);
        return data;
    }
    private void put(String name,byte[] data) {
        if(data.length>limits.maxTotalBytes()-bytes)throw limit(name);
        bytes+=data.length;files.put(name,data);
    }
    private void checkCount() { if(++count>limits.maxEntries())throw limit(path.getFileName().toString()); }
    private AssetException changed() { return new AssetException(AssetException.Code.SOURCE_CHANGED,path.getFileName().toString(),"构建期间资源来源变化"); }
    private static AssetException limit(String path) { return new AssetException(AssetException.Code.LIMIT,path,"资源读取超过限额"); }
    private void finish(String fingerprint) {
        result=ResourcePack.owned(path.getFileName().toString(),files,fingerprint);stage=Stage.DONE;
    }
    @Override public void close() throws IOException {
        if(closed)return;closed=true;
        IOException error=null;
        try { if(hashInput!=null)hashInput.close(); } catch(IOException e) { error=e; }
        try { if(zip!=null)zip.close(); } catch(IOException e) { if(error==null)error=e;else error.addSuppressed(e); }
        if(walk!=null)walk.close();
        hashInput=null;zip=null;walk=null;paths=null;entries=null;digestEntries=null;result=null;files.clear();names.clear();
        if(error!=null)throw error;
    }
}
