package com.wenkrang.famara.compat;

import com.wenkrang.famara.render.asset.ResourceFileSnapshot;
import com.wenkrang.famara.render.asset.ZipDirectoryBudget;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.*;
import java.util.stream.Stream;
import java.util.zip.*;

/** 分片冻结来源，最多驻留一份ZIP中央目录；私有副本与紧凑路径过滤器支持按需重开。 */
final class CePackSources implements AutoCloseable {
    private static final int MAXIMUM_ENTRIES=200000;
    record Metrics(int openZipFiles,int peakOpenZipFiles,long catalogPeakBytes,long catalogTotalBytes,long zipOpenings,long membershipBytes) {}
    private final class Layer implements AutoCloseable {
        final Path path;
        Path directory;
        ResourceFileSnapshot snapshot;
        ZipFile zip;
        Stream<Path> walk;
        Iterator<Path> files;
        Enumeration<? extends ZipEntry> entries;
        long catalog,membershipBytes;
        ZipPathFilter membership;
        boolean catalogPaid;
        int scanned;
        Layer(Path path){this.path=path;}
        public void close() {
            if(walk!=null)try{walk.close();}catch(RuntimeException ignored){}walk=null;files=null;entries=null;
            if(activeZip==this)try{closeActiveZip();}catch(IOException ignored){}
            if(snapshot!=null)try{snapshot.close();}catch(IOException ignored){}snapshot=null;
            membership=null;if(memory!=null && membershipBytes>0)memory.release("zip-membership",membershipBytes);membershipLive-=membershipBytes;membershipBytes=0;
        }
        byte[] read(String name)throws IOException {
            if(directory!=null) {
                Path target=directory.resolve(name).normalize();if(!target.startsWith(directory))throw new IOException("资源路径超出来源目录："+name);
                if(!Files.isRegularFile(target))return null;if(!target.toRealPath().startsWith(directory))throw new IOException("资源链接超出来源目录："+name);
                if(Files.size(target)>4194304)throw new java.util.concurrent.RejectedExecutionException("旧取色单文件超过4MiB："+name);
                try(var input=Files.newInputStream(target)){return boundedRead(input);}
            }
            if(membership==null || !membership.mightContain(name))return null;
            ZipFile owned=openZip(this);ZipEntry entry=owned.getEntry(name);if(entry==null || entry.isDirectory())return null;
            if(entry.getSize()>4194304)throw new java.util.concurrent.RejectedExecutionException("旧取色条目超过4MiB："+name);
            try(var input=owned.getInputStream(entry)){return boundedRead(input);}
        }
    }
    private final ColorWorkBudget memory;
    private final Predicate<String> roots;
    private final BooleanSupplier current;
    private final Iterator<Path> inputs;
    private final List<Layer> layers=new ArrayList<>();
    private final List<Path> opened=new ArrayList<>();
    private final List<String> issues=new ArrayList<>();
    private final Set<Layer> unavailable=new HashSet<>();
    private final SortedSet<String> names=new TreeSet<>();
    private final List<String> added=new ArrayList<>();
    private Layer indexing,activeZip;
    private int openZipFiles,peakOpenZipFiles;
    private long catalogPeakBytes,catalogTotalBytes,zipOpenings,membershipLive;
    private boolean done,closed;
    private long indexBytes;
    private CePackSources(Collection<Path> paths,BooleanSupplier current,ColorWorkBudget memory,Predicate<String> roots) {
        this.memory=memory;this.roots=roots==null?name->blockstate(name) || name.matches("assets/[^/]+/(items/.+[.]json|models/item/.+[.]json)"):roots;this.current=current;
        Set<Path> unique=new LinkedHashSet<>();for(Path path:paths)if(path!=null)try{unique.add(path.toAbsolutePath().normalize());}catch(RuntimeException error){issue("资源来源已失效："+path+"："+error);}inputs=unique.iterator();
    }
    static CePackSources begin(Collection<Path> paths,BooleanSupplier current,ColorWorkBudget memory,Predicate<String> roots){return new CePackSources(paths,current,memory,roots);}
    static CePackSources open(Collection<Path> paths){return open(paths,()->true,null,null);}
    static CePackSources open(Collection<Path> paths,BooleanSupplier current){return open(paths,current,null,null);}
    static CePackSources open(Collection<Path> paths,BooleanSupplier current,ColorWorkBudget memory){return open(paths,current,memory,null);}
    static CePackSources open(Collection<Path> paths,BooleanSupplier current,ColorWorkBudget memory,Set<String> itemRoots) {
        var result=begin(paths,current,memory,itemRoots==null?null:name->blockstate(name) || itemRoots.contains(name));
        try{while(!result.step(256,Long.MAX_VALUE,System::nanoTime)){}return result;}catch(RuntimeException | Error error){result.close();throw error;}
    }
    private void check(){if(closed || !current.getAsBoolean())throw new java.util.concurrent.CancellationException("CE来源构建已关闭或过期");}
    boolean step(int operations,long deadline,LongSupplier clock) {
        check();if(operations<=0)throw new IllegalArgumentException("旧色来源步长必须为正");
        for(int n=0;n<operations && !done && clock.getAsLong()<deadline;n++) {
            check();try{advance(deadline,clock);}
            catch(java.util.concurrent.CancellationException | java.util.concurrent.RejectedExecutionException error){close();throw error;}
            catch(IOException | RuntimeException error){issue("无法读取资源来源 "+(indexing==null?"<初始化>":indexing.path)+"："+error);discard();}
        }
        return done;
    }
    private void advance(long deadline,LongSupplier clock)throws IOException {
        if(indexing==null) {
            if(!inputs.hasNext()){done=true;return;}Path path=inputs.next();
            if(!Files.exists(path)){issue("资源来源不存在："+path);return;}
            indexing=new Layer(path);added.clear();
            if(Files.isDirectory(path)){indexing.directory=path.toRealPath();indexing.walk=Files.walk(indexing.directory);indexing.files=indexing.walk.iterator();}
            else indexing.snapshot=new ResourceFileSnapshot(path,1073741824L);
            return;
        }
        if(indexing.directory==null && indexing.zip==null) {
            if(!indexing.snapshot.step(1,deadline,clock))return;
            indexing.catalog=ZipDirectoryBudget.estimatedBytes(indexing.snapshot.path());catalogTotalBytes=Math.addExact(catalogTotalBytes,indexing.catalog);
            ZipFile owned=openZip(indexing);if(owned.size()>MAXIMUM_ENTRIES)throw new java.util.concurrent.RejectedExecutionException("旧取色单来源目录项超过200000："+indexing.path);
            Layer layer=indexing;layer.membership=new ZipPathFilter(owned.size(),bytes->{if(memory!=null)memory.retain("zip-membership",bytes);layer.membershipBytes=bytes;membershipLive+=bytes;});
            indexing.entries=owned.entries();return;
        }
        String name;
        if(indexing.directory!=null) {
            if(!indexing.files.hasNext()){finishLayer();return;}
            Path path=indexing.files.next();if(++indexing.scanned>MAXIMUM_ENTRIES)throw new java.util.concurrent.RejectedExecutionException("旧取色单来源目录项超过200000："+indexing.path);
            if(!Files.isRegularFile(path))return;name=indexing.directory.relativize(path).toString().replace(File.separatorChar,'/');
            if(!path.toRealPath().startsWith(indexing.directory))throw new IOException("资源链接逃出来源目录："+name);
        } else {
            if(!indexing.entries.hasMoreElements()){finishLayer();return;}ZipEntry entry=indexing.entries.nextElement();indexing.scanned++;
            if(entry.isDirectory())return;name=entry.getName();
        }
        safe(name);if(indexing.membership!=null)indexing.membership.add(name);
        if(!roots.test(name) || names.contains(name))return;
        long bytes=nameBytes(name);if(memory!=null)memory.retain("index",bytes);indexBytes+=bytes;names.add(name);added.add(name);
    }
    private void issue(String message){if(memory!=null)memory.retain("source-issues",128L+message.length()*4L);issues.add(message);}
    private void finishLayer()throws IOException {
        if(indexing.walk!=null)indexing.walk.close();indexing.walk=null;indexing.files=null;indexing.entries=null;
        if(activeZip==indexing)closeActiveZip();
        if(memory!=null)memory.retain("source-meta",512);layers.add(indexing);opened.add(indexing.path);indexing=null;added.clear();
    }
    private ZipFile openZip(Layer layer)throws IOException {
        if(activeZip==layer)return layer.zip;
        // 最后一个输入流已退出才切换；关闭旧中央目录后再准入新目录，不能临时双开。
        closeActiveZip();check();
        try {if(memory!=null)memory.retain("zip-catalog",layer.catalog);}
        catch(java.util.concurrent.RejectedExecutionException error){throw new java.util.concurrent.RejectedExecutionException("旧取色ZIP目录无法准入：来源="+layer.path+"，单包目录="+layer.catalog+"，当前旧色独立账本="+memory.domains()+"，峰值="+memory.peak()+"；"+error.getMessage(),error);}
        layer.catalogPaid=true;
        try {
            layer.zip=new ZipFile(layer.snapshot.path().toFile());activeZip=layer;openZipFiles++;peakOpenZipFiles=Math.max(peakOpenZipFiles,openZipFiles);
            catalogPeakBytes=Math.max(catalogPeakBytes,layer.catalog);zipOpenings++;return layer.zip;
        } catch(IOException | RuntimeException | Error error) {
            if(memory!=null)memory.release("zip-catalog",layer.catalog);layer.catalogPaid=false;throw error;
        }
    }
    private void closeActiveZip()throws IOException {
        Layer owned=activeZip;if(owned==null)return;owned.entries=null;
        try{owned.zip.close();}
        finally {
            owned.zip=null;activeZip=null;openZipFiles--;
            if(owned.catalogPaid && memory!=null)memory.release("zip-catalog",owned.catalog);owned.catalogPaid=false;
        }
    }
    Metrics metrics(){return new Metrics(openZipFiles,peakOpenZipFiles,catalogPeakBytes,catalogTotalBytes,zipOpenings,membershipLive);}
    private void discard() {
        if(indexing!=null)indexing.close();indexing=null;
        for(String name:added){if(names.remove(name)){long bytes=nameBytes(name);indexBytes-=bytes;if(memory!=null)memory.release("index",bytes);}}
        added.clear();
    }
    private static long nameBytes(String name){return 320L+name.length()*4L;}
    private static boolean blockstate(String name){return name.matches("assets/[^/]+/blockstates/.+[.]json");}
    private static void safe(String name)throws IOException {
        if(name.isEmpty() || name.indexOf(':')>=0 || name.indexOf('\\')>=0 || name.indexOf(0)>=0)throw new IOException("旧取色非法资源路径："+name);
        for(String part:name.split("/",-1))if(part.isEmpty() || part.equals(".") || part.equals(".."))throw new IOException("旧取色资源路径逃逸："+name);
    }
    private static byte[] boundedRead(InputStream input)throws IOException {byte[] bytes=input.readNBytes(4194305);if(bytes.length>4194304)throw new java.util.concurrent.RejectedExecutionException("旧取色条目超过4MiB");return bytes;}
    PackColorIndex.PackSource source() {
        return new PackColorIndex.PackSource() {
            public Collection<String> list(){check();if(!done)throw new IllegalStateException("旧色来源尚未完成索引");return Collections.unmodifiableSortedSet(names);}
            public byte[] read(String path)throws IOException {
                check();safe(path);
                for(var layer:layers) {
                    if(unavailable.contains(layer))continue;
                    try{byte[] bytes=layer.read(path);if(bytes!=null)return bytes;}
                    catch(java.util.concurrent.CancellationException | java.util.concurrent.RejectedExecutionException error){throw error;}
                    catch(IOException | RuntimeException error){unavailable.add(layer);layer.close();issue("读取期间来源失效，继续其他来源："+path+"："+error);}
                }
                return null;
            }
        };
    }
    int rootCount(){return names.size();}
    List<Path> opened(){return List.copyOf(opened);}
    List<String> issues(){return List.copyOf(issues);}
    @Override public void close() {
        if(closed)return;closed=true;discard();for(Layer layer:layers)layer.close();layers.clear();unavailable.clear();opened.clear();issues.clear();names.clear();
        if(memory!=null){if(indexBytes>0)memory.release("index",indexBytes);memory.drop("source-issues");memory.drop("source-meta");}indexBytes=0;
    }
}
