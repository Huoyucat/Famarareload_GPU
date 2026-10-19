package com.wenkrang.famara.render.asset;

import com.google.gson.*;
import java.nio.file.*;
import java.io.*;
import java.security.*;
import java.util.*;
import java.util.zip.*;

/** 构建工具：只读客户端数据，静态提取白名单形状；输出不含任何游戏class。 */
public final class BundledBaseBuilder {
    private BundledBaseBuilder() {}
    private static String digest(Path file,String algorithm)throws Exception {
        MessageDigest digest=MessageDigest.getInstance(algorithm);try(var in=Files.newInputStream(file)){byte[] buffer=new byte[65536];for(int n;(n=in.read(buffer))>=0;)if(n>0)digest.update(buffer,0,n);}return HexFormat.of().formatHex(digest.digest());
    }
    public static void main(String[] args)throws Exception {
        if(args.length!=2)throw new IllegalArgumentException("参数：有权读取的26.3客户端JAR 新输出目录");Path source=Path.of(args[0]),directory=Path.of(args[1]);
        if(Files.exists(directory))throw new IOException("输出目录必须全新，未覆盖旧资源："+directory);Files.createDirectories(directory);Path out=directory.resolve("render-base");Files.createDirectory(out);
        if(!digest(source,"SHA-1").equals("e877b6a07acd633fb3bb475002175cec036e7b87"))throw new IOException("客户端与已验证的官方26.3来源不同，未生成内置包");
        var limits=new AssetLimits(200000,4194304,134217728,1048576,4096,20000,64,64,200000,64);ResourcePack pack=ResourcePack.load(source,limits).withRole(ResourcePack.Role.BASE);
        JsonObject version=JsonParser.parseString(new String(pack.require("version.json").copyBytes(),java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();if(!version.get("id").getAsString().equals("26.3"))throw new IOException("本构建器只验证过26.3客户端");
        var reader=new NativeModelDataReader(pack,limits,new AssetMemoryBudget(134217728));JsonObject methods=new JsonObject();Gson gson=new GsonBuilder().disableHtmlEscaping().create();
        for(String method:List.of("createBodyLayer","createRunningPoseBodyLayer","createSittingPoseBodyLayer","createStarPoseBodyLayer"))methods.add(method,gson.toJsonTree(reader.read(method)));
        JsonObject shapes=new JsonObject();shapes.addProperty("format",1);shapes.addProperty("clientVersion","26.3");shapes.add("methods",methods);
        Path zip=out.resolve("base-26.3.zip");int count=0;long bytes=0;
        try(var output=new ZipOutputStream(Files.newOutputStream(zip,StandardOpenOption.CREATE_NEW))) {
            output.setLevel(9);
            for(String path:new TreeSet<>(pack.paths())) {
                if(!(path.startsWith("assets/minecraft/") || path.equals("version.json") || path.equals("pack.mcmeta")) || path.endsWith(".class"))continue;
                byte[] data=pack.require(path).copyBytes();put(output,path,data);count++;bytes+=data.length;
            }
            byte[] shape=gson.toJson(shapes).getBytes(java.nio.charset.StandardCharsets.UTF_8);put(output,NativeModelDataReader.DATA,shape);count++;bytes+=shape.length;
        }
        var manifest=new LinkedHashMap<String,Object>();manifest.put("format",1);manifest.put("clientVersion","26.3");manifest.put("resourceMajor",97);manifest.put("resourceMinor",1);manifest.put("sourceSha1",digest(source,"SHA-1"));manifest.put("sourceSha256",digest(source,"SHA-256"));manifest.put("zipSha256",digest(zip,"SHA-256"));manifest.put("zipBytes",Files.size(zip));manifest.put("entries",count);manifest.put("uncompressedBytes",bytes);manifest.put("containsGameClasses",false);manifest.put("sourceNotice","Minecraft assets belong to their respective rights holders; inclusion does not grant redistribution rights.");
        Files.writeString(out.resolve("manifest.json"),new GsonBuilder().setPrettyPrinting().create().toJson(manifest),StandardOpenOption.CREATE_NEW);
        Files.writeString(out.resolve("base-26.3.zip.sha256"),digest(zip,"SHA-256")+System.lineSeparator(),StandardOpenOption.CREATE_NEW);
        Files.writeString(out.resolve("manifest.json.sha256"),digest(out.resolve("manifest.json"),"SHA-256")+System.lineSeparator(),StandardOpenOption.CREATE_NEW);
        try(var archive=new ZipFile(zip.toFile())){if(archive.stream().anyMatch(e->e.getName().endsWith(".class") || e.getName().endsWith(".jar")))throw new IOException("资源包混入可执行内容");}
        System.out.println(new GsonBuilder().setPrettyPrinting().create().toJson(manifest));
    }
    private static void put(ZipOutputStream out,String path,byte[] data)throws IOException {ResourcePack.validatePath(path);ZipEntry entry=new ZipEntry(path);entry.setTime(0);out.putNextEntry(entry);out.write(data);out.closeEntry();}
}
