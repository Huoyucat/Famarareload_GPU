package com.wenkrang.famara.render.platform;

import com.google.gson.*;
import com.wenkrang.famara.render.schedule.RenderMemoryBudget;
import java.io.*;
import java.net.URLConnection;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/** 本地内置数据包的后台流式准备；无网络/主线程等待，不覆盖用户外置资源。 */
public final class BundledBaseResources {
    public static final String MANIFEST="render-base/manifest.json",ZIP="render-base/base-26.3.zip";
    public record Manifest(int format,String clientVersion,int resourceMajor,int resourceMinor,String sourceSha1,String sourceSha256,String zipSha256,long zipBytes,int entries,long uncompressedBytes,boolean containsGameClasses,String sourceNotice) {
        public Manifest {
            if(format!=1 || !"26.3".equals(clientVersion) || resourceMajor!=97 || resourceMinor!=1 || zipSha256==null || !zipSha256.matches("[0-9a-f]{64}") || zipBytes<=0 || zipBytes>16L*1024*1024 || entries<=0 || entries>200000 || uncompressedBytes<=0 || uncompressedBytes>134217728 || containsGameClasses)throw new IllegalArgumentException("内置基础资源清单无效");
        }
    }
    public record Ready(Path path,Manifest manifest) {}
    private BundledBaseResources() {}
    private static InputStream open(ClassLoader loader,String path)throws IOException {
        var url=loader.getResource(path);if(url==null)throw new IOException("插件缺少内置基础资源："+path);URLConnection connection=url.openConnection();connection.setUseCaches(false);return connection.getInputStream();
    }
    public static Manifest manifest(ClassLoader loader)throws IOException {
        try(InputStream input=open(loader,MANIFEST)){byte[] json=input.readNBytes(16385);if(json.length>16384)throw new IOException("内置资源清单超限");try{return new Gson().fromJson(new String(json,java.nio.charset.StandardCharsets.UTF_8),Manifest.class);}catch(RuntimeException error){throw new IOException("内置资源清单无法解析",error);}}
    }
    public static synchronized Ready prepare(ClassLoader loader,Path dataFolder,RenderMemoryBudget memory,BooleanSupplier current)throws Exception {
        try(var lease=memory.reserve("bundled-base-io",262144)) {
            Manifest manifest=manifest(loader);if(manifest==null)throw new IOException("内置清单为空");Path parent=dataFolder.toAbsolutePath().normalize();
            if(Files.isSymbolicLink(parent))throw new IOException("插件数据目录是符号链接，拒绝准备内置缓存");Files.createDirectories(parent);
            Path cache=parent.resolve("render-cache");if(Files.isSymbolicLink(cache))throw new IOException("内置缓存目录是符号链接");Files.createDirectories(cache);
            Path target=cache.resolve("base-26.3-"+manifest.zipSha256()+".zip");if(Files.isSymbolicLink(target))throw new IOException("内置缓存文件是符号链接");
            if(Files.isRegularFile(target) && Files.size(target)==manifest.zipBytes() && digest(target,current).equals(manifest.zipSha256()))return new Ready(target,manifest);
            Path temporary=Files.createTempFile(cache,"base-copy-",".tmp");
            try {
                MessageDigest hash=MessageDigest.getInstance("SHA-256");long copied=0;
                try(InputStream input=open(loader,ZIP);OutputStream output=Files.newOutputStream(temporary)) {
                    byte[] bytes=new byte[65536];for(int n;(n=input.read(bytes))>=0;)if(n>0){check(current);copied=Math.addExact(copied,n);if(copied>manifest.zipBytes())throw new IOException("内置资源压缩体积不符");output.write(bytes,0,n);hash.update(bytes,0,n);}
                }
                if(copied!=manifest.zipBytes() || !HexFormat.of().formatHex(hash.digest()).equals(manifest.zipSha256()))throw new IOException("内置资源SHA256校验不符");
                try(FileChannel file=FileChannel.open(temporary,StandardOpenOption.WRITE)){file.force(true);}check(current);
                if(Files.exists(target)) {
                    if(Files.isSymbolicLink(target) || !Files.isRegularFile(target))throw new IOException("内置缓存目标类型不符");
                    if(Files.size(target)==manifest.zipBytes() && digest(target,current).equals(manifest.zipSha256()))return new Ready(target,manifest);
                    Files.move(target,target.resolveSibling(target.getFileName()+".bad-"+UUID.randomUUID()),StandardCopyOption.ATOMIC_MOVE);
                }
                try{Files.move(temporary,target,StandardCopyOption.ATOMIC_MOVE);}
                catch(FileAlreadyExistsException raced){if(!Files.isRegularFile(target,LinkOption.NOFOLLOW_LINKS) || Files.size(target)!=manifest.zipBytes() || !digest(target,current).equals(manifest.zipSha256()))throw raced;}
                return new Ready(target,manifest);
            }finally{Files.deleteIfExists(temporary);}
        }
    }
    private static void check(BooleanSupplier current){if(!current.getAsBoolean())throw new CancellationException("内置基础资源准备已取消");}
    private static String digest(Path path,BooleanSupplier current)throws Exception {
        MessageDigest digest=MessageDigest.getInstance("SHA-256");try(InputStream input=Files.newInputStream(path)){byte[] bytes=new byte[65536];for(int n;(n=input.read(bytes))>=0;)if(n>0){check(current);digest.update(bytes,0,n);}}return HexFormat.of().formatHex(digest.digest());
    }
}
