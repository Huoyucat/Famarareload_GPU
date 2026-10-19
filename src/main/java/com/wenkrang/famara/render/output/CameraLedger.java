package com.wenkrang.famara.render.output;

import com.google.gson.*;
import com.wenkrang.famara.render.asset.*;
import java.io.*;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** 相机唯一ID账本。所有写入及打开/关闭操作必须交给串行后台IO队列。 */
public final class CameraLedger implements AutoCloseable {
    public record Limits(int maxCameras,int maxJobs,int maxFileBytes) {
        public Limits {
            if(maxCameras<=0 || maxJobs<=0 || maxFileBytes<1024 || maxFileBytes>64*1024*1024)
                throw new IllegalArgumentException("胶卷账本限额无效");
        }
    }
    public enum Status { RESERVED, SUCCEEDED, FAILED }
    public record Job(UUID request,UUID camera,UUID owner,int mapId,Status status,String reason) {
        public Job {
            Objects.requireNonNull(request);Objects.requireNonNull(camera);Objects.requireNonNull(owner);Objects.requireNonNull(status);
            Objects.requireNonNull(reason);
            if(mapId < -1 || status==Status.SUCCEEDED && mapId<0 || reason.length()>512)throw new IllegalArgumentException("账本任务数据无效");
        }
    }
    public record Balance(int loaded,int pending,int available,long totalSpent) {}
    public static final class Snapshot {
        private final Map<UUID,Long> spent;
        private final Map<UUID,Job> jobs;
        private final Map<UUID,Integer> pending;
        private Snapshot(Map<UUID,Long> spent,Map<UUID,Job> jobs) {
            this.spent=Map.copyOf(spent);this.jobs=Map.copyOf(jobs);
            Map<UUID,Integer> counts=new HashMap<>();
            for(Job job:jobs.values())if(job.status==Status.RESERVED)counts.merge(job.camera,1,Math::addExact);
            pending=Map.copyOf(counts);
        }
        public long spent(UUID camera){return spent.getOrDefault(camera,0L);}
        public Optional<Job> job(UUID request){return Optional.ofNullable(jobs.get(request));}
        public Collection<Job> jobs(){return jobs.values();}
        public Balance balance(UUID camera,int rawFilm,long appliedSpent) {
            if(rawFilm<0 || rawFilm>16 || appliedSpent<0)throw new IllegalArgumentException("相机胶卷元数据无效");
            long total=spent(camera),delta=Math.subtractExact(total,appliedSpent);
            if(delta<0 || delta>rawFilm)throw new IllegalStateException("相机与持久账本不一致，拒绝重置消费计数");
            int loaded=rawFilm-(int)delta,reserved=pending.getOrDefault(camera,0);
            if(reserved>loaded)throw new IllegalStateException("相机剩余胶卷少于已预约数量");
            return new Balance(loaded,reserved,loaded-reserved,total);
        }
    }
    private final long workspace;
    private final Path file;
    private final Limits limits;
    private final FileChannel lockChannel;
    private final FileLock lock;
    private volatile Snapshot snapshot;
    private boolean closed;

    public CameraLedger(Path directory,Limits limits)throws IOException{this(directory,limits,Long.MAX_VALUE);}
    public CameraLedger(Path directory,Limits limits,long workspace)throws IOException {
        this.workspace=workspace;this.limits=Objects.requireNonNull(limits);
        Files.createDirectories(directory);file=directory.resolve("ledger.json");
        if(Files.isSymbolicLink(file))throw new IOException("拒绝符号链接账本");
        boolean previouslyOpened=Files.exists(directory.resolve("ledger.lock"));
        lockChannel=FileChannel.open(directory.resolve("ledger.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);
        FileLock acquired;
        try {
            acquired=lockChannel.tryLock();
            if(acquired==null)throw new IOException("胶卷账本已被其它实例占用");
        } catch(IOException | RuntimeException error){lockChannel.close();throw new IOException("胶卷账本文件锁不可用",error);}
        lock=acquired;
        try {
            boolean exists=Files.exists(file);
            if(!exists && previouslyOpened)throw new IOException("账本文件缺失但存在已用标记，拒绝重置相机消费计数");
            snapshot=exists?read():new Snapshot(Map.of(),Map.of());
            if(!exists)persist(snapshot);
            Map<UUID,Job> recovered=new HashMap<>(snapshot.jobs);
            boolean changed=false;
            for(Job job:snapshot.jobs.values())if(job.status==Status.RESERVED) {
                recovered.put(job.request,new Job(job.request,job.camera,job.owner,job.mapId,Status.FAILED,"进程重启，未结算的预约已释放"));changed=true;
            }
            if(changed)persist(new Snapshot(snapshot.spent,recovered));
        } catch(IOException | RuntimeException error){lock.release();lockChannel.close();throw error;}
    }
    public Snapshot snapshot(){return snapshot;}
    public synchronized Job reserve(UUID request,UUID camera,UUID owner,int rawFilm,long appliedSpent) throws IOException {
        ensureOpen();Objects.requireNonNull(request);Objects.requireNonNull(camera);Objects.requireNonNull(owner);
        Job existing=snapshot.jobs.get(request);
        if(existing!=null) {
            if(!existing.camera.equals(camera) || !existing.owner.equals(owner))throw new IllegalStateException("胶卷请求身份冲突");
            return existing;
        }
        if(snapshot.jobs.size()>=limits.maxJobs || !snapshot.spent.containsKey(camera) && snapshot.spent.size()>=limits.maxCameras)
            throw new IOException("胶卷账本容量不足，未受理新拍摄");
        if(snapshot.balance(camera,rawFilm,appliedSpent).available<1)throw new IllegalStateException("没有可预约胶卷");
        Map<UUID,Long> accounts=new HashMap<>(snapshot.spent);accounts.putIfAbsent(camera,0L);
        Map<UUID,Job> jobs=new HashMap<>(snapshot.jobs);
        Job job=new Job(request,camera,owner,-1,Status.RESERVED,"");jobs.put(request,job);persist(new Snapshot(accounts,jobs));return job;
    }
    public synchronized Job bindOutput(UUID request,int mapId) throws IOException {
        ensureOpen();if(mapId<0)throw new IllegalArgumentException("地图ID不能为负");
        Job job=required(request);
        if(job.status!=Status.RESERVED)throw new IllegalStateException("胶卷预约已经结束");
        if(job.mapId==mapId)return job;
        if(job.mapId>=0)throw new IllegalStateException("预约已经绑定其它地图");
        for(Job other:snapshot.jobs.values())if(other.mapId==mapId)throw new IllegalStateException("地图ID已被其它预约使用");
        return replace(new Job(request,job.camera,job.owner,mapId,job.status,job.reason),snapshot.spent);
    }
    /** 仅在照片存储及地图发布都成功后调用。重复回调不重复消费。 */
    public synchronized Job succeed(UUID request) throws IOException {
        ensureOpen();Job job=required(request);
        if(job.status==Status.SUCCEEDED)return job;
        if(job.status!=Status.RESERVED || job.mapId<0)throw new IllegalStateException("没有已绑定地图的有效预约");
        Map<UUID,Long> accounts=new HashMap<>(snapshot.spent);
        accounts.put(job.camera,Math.addExact(snapshot.spent(job.camera),1));
        return replace(new Job(request,job.camera,job.owner,job.mapId,Status.SUCCEEDED,""),accounts);
    }
    public synchronized Job fail(UUID request,String reason) throws IOException {
        ensureOpen();Job job=required(request);
        if(job.status!=Status.RESERVED)return job;
        String text=Objects.requireNonNull(reason);if(text.length()>512)text=text.substring(0,512);
        Job failed=new Job(request,job.camera,job.owner,job.mapId,Status.FAILED,text);
        try{return replace(failed,snapshot.spent);}catch(IOException error) {
            // 失败不产生消费。即使磁盘暂不可写，也可安全释放内存预约；磁盘旧RESERVED在重启时仍按失败恢复。
            Map<UUID,Job> jobs=new HashMap<>(snapshot.jobs);jobs.put(request,failed);snapshot=new Snapshot(snapshot.spent,jobs);throw error;
        }
    }
    private Job replace(Job job,Map<UUID,Long> accounts) throws IOException {
        Map<UUID,Job> jobs=new HashMap<>(snapshot.jobs);jobs.put(job.request,job);persist(new Snapshot(accounts,jobs));return job;
    }
    private Job required(UUID request) {Job job=snapshot.jobs.get(request);if(job==null)throw new IllegalArgumentException("未知胶卷预约");return job;}
    private void ensureOpen(){if(closed)throw new IllegalStateException("胶卷账本已关闭");}
    private void persist(Snapshot next) throws IOException {
        long bound=64L+next.spent.size()*256L+next.jobs.size()*4096L;
        if(bound>workspace/4)throw new IOException("胶卷账本事务超过V2共享IO工作集，未修改计数");
        if(bound>limits.maxFileBytes)throw new IOException("账本序列化工作量超出文件预算，未修改消费计数");
        JsonObject root=new JsonObject();root.addProperty("version",1);
        JsonObject accounts=new JsonObject();next.spent.forEach((id,count)->accounts.addProperty(id.toString(),count));root.add("cameras",accounts);
        Gson gson=new Gson();JsonArray jobs=new JsonArray();for(Job job:next.jobs.values())jobs.add(gson.toJsonTree(job));root.add("jobs",jobs);
        byte[] bytes=root.toString().getBytes(StandardCharsets.UTF_8);
        if(bytes.length>limits.maxFileBytes)throw new IOException("胶卷账本文件超过限额");
        Path temporary=file.resolveSibling("ledger.tmp-"+UUID.randomUUID());
        try {
            try(FileChannel out=FileChannel.open(temporary,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE)) {
                ByteBuffer buffer=ByteBuffer.wrap(bytes);while(buffer.hasRemaining())out.write(buffer);out.force(true);
            }
            // 不支持原子替换的平台明确失败，不能退化成先删除旧账本。
            Files.move(temporary,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
            snapshot=next;
        } finally {
            try{Files.deleteIfExists(temporary);}catch(IOException cleanup){if(snapshot!=next)throw cleanup;}
        }
    }
    private Snapshot read() throws IOException {
        long size=Files.size(file);if(size>workspace/64)throw new IOException("账本解析工作集超过V2共享预算，拒绝重置旧账本");if(size>limits.maxFileBytes)throw new IOException("胶卷账本文件超过限额");
        byte[] bytes;
        try(InputStream input=Files.newInputStream(file)){bytes=input.readNBytes(limits.maxFileBytes+1);}
        if(bytes.length>limits.maxFileBytes)throw new IOException("胶卷账本读取超过限额");
        AssetLimits bounds=new AssetLimits(1,limits.maxFileBytes,Math.min(workspace,limits.maxFileBytes*64L),1,1,1,1,16,
                (int)Math.min(1_000_000L,64L+limits.maxCameras*3L+limits.maxJobs*20L),1);
        JsonObject root=BoundedJson.read(ResourcePack.memory("camera-ledger",Map.of("ledger.json",bytes),bounds).require("ledger.json"),"ledger.json",bounds);
        try {
            if(!root.keySet().equals(Set.of("version","cameras","jobs")) || integer(root.get("version"))!=1)throw new IllegalArgumentException("未知账本格式");
            Map<UUID,Long> accounts=new HashMap<>();
            for(var entry:root.getAsJsonObject("cameras").entrySet()) {
                long value=integer(entry.getValue());if(value<0)throw new IllegalArgumentException("消费计数不能为负");
                accounts.put(UUID.fromString(entry.getKey()),value);
            }
            Map<UUID,Job> jobs=new HashMap<>();Map<UUID,Long> successes=new HashMap<>();Set<Integer> outputs=new HashSet<>();
            for(JsonElement element:root.getAsJsonArray("jobs")) {
                JsonObject value=element.getAsJsonObject();
                if(!value.keySet().equals(Set.of("request","camera","owner","mapId","status","reason")))throw new IllegalArgumentException("未知账本字段");
                Job job=new Job(UUID.fromString(string(value,"request")),UUID.fromString(string(value,"camera")),UUID.fromString(string(value,"owner")),
                        Math.toIntExact(integer(value.get("mapId"))),Status.valueOf(string(value,"status")),string(value,"reason"));
                if(jobs.putIfAbsent(job.request,job)!=null || !accounts.containsKey(job.camera) || job.mapId>=0 && !outputs.add(job.mapId))throw new IllegalArgumentException("账本任务冲突");
                if(job.status==Status.SUCCEEDED)successes.merge(job.camera,1L,Math::addExact);
            }
            if(accounts.size()>limits.maxCameras || jobs.size()>limits.maxJobs)throw new IllegalArgumentException("账本记录超过限额");
            for(var account:accounts.entrySet())if(!account.getValue().equals(successes.getOrDefault(account.getKey(),0L)))throw new IllegalArgumentException("消费总数与成功记录不一致");
            return new Snapshot(accounts,jobs);
        } catch(RuntimeException error){throw new IOException("胶卷账本无效，拒绝重置计数",error);}
    }
    private static String string(JsonObject value,String key) {
        JsonElement element=value.get(key);if(!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString())throw new IllegalArgumentException("字段不是字符串");return element.getAsString();
    }
    private static long integer(JsonElement element) {
        if(!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber())throw new IllegalArgumentException("字段不是整数");
        return new BigDecimal(element.getAsString()).longValueExact();
    }
    @Override public synchronized void close() throws IOException {if(closed)return;closed=true;try{lock.release();}finally{lockChannel.close();}}
}
