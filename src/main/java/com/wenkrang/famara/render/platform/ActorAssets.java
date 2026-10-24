package com.wenkrang.famara.render.platform;

import com.wenkrang.famara.render.asset.*;
import com.wenkrang.famara.render.scene.*;
import com.wenkrang.famara.render.schedule.*;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.io.*;
import java.net.*;
import java.nio.file.Path;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.ZipFile;

/** Runtime-wide compressed skin cache. All image decoding is on the existing budgeted IO worker. */
public final class ActorAssets implements AutoCloseable {
    private static final int CACHE_BYTES=4*1024*1024,MAX_PNG=128*1024,MAX_SKIN=32*1024;
    private static final AssetLimits LIMITS=new AssetLimits(8,MAX_PNG,8*1024*1024,65536,4096,8192,32,32,10000,64);
    private record Cached(byte[] png,long expires){}
    private final LinkedHashMap<String,Cached> skins=new LinkedHashMap<>(16,.75f,true);
    private final Map<String,CompletableFuture<byte[]>> pending=new HashMap<>();
    private final ThreadPoolExecutor network=new ThreadPoolExecutor(2,2,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(16),r->{Thread t=new Thread(r,"Famara-skin-download");t.setDaemon(true);return t;}){ @Override protected void terminated(){cacheMemory.close();} };
    private final BudgetedWorkRunner worker;
    private final CpuBudget cpu;
    private final java.lang.management.ThreadMXBean meter=java.lang.management.ManagementFactory.getThreadMXBean();
    private final RenderMemoryBudget memory;
    private final RenderMemoryBudget.Lease cacheMemory;
    private volatile Path base;
    private ActorModelCatalog catalog;
    private RenderMemoryBudget.Lease catalogMemory;
    private int batches;
    private boolean closed;
    private int cacheBytes;
    public ActorAssets(RenderMemoryBudget memory,BudgetedWorkRunner worker,CpuBudget cpu){this.memory=memory;this.worker=worker;this.cpu=cpu;cacheMemory=memory.reserve("actor-skin-cache-and-network",5L*1024*1024);}
    public void base(Path path){base=Objects.requireNonNull(path);}
    public synchronized void prepare(){
        if(catalog!=null)return;if(closed)throw new CancellationException("Actor resources closed");var lease=memory.reserve("actor-model-catalog",8L*1024*1024);
        try{catalog=ActorModelCatalog.load();lease.resize(catalog.retainedBytes());catalogMemory=lease;}catch(Throwable error){lease.close();throw error;}
    }
    private synchronized void releaseBatch(){if(--batches<0)throw new IllegalStateException("Actor batch owner underflow");releaseCatalog();}
    private void releaseCatalog(){if(closed && batches==0 && catalogMemory!=null){catalogMemory.close();catalogMemory=null;catalog=null;}}
    public Batch batch(){synchronized(this){if(closed)throw new IllegalStateException("Actor resources closed");var lease=memory.reserve("actor-scene",512L*1024);batches++;return new Batch(lease);}}
    /** Restrict server-supplied profile URLs; never follow redirects to another host. */
    static URI skinUri(String source){
        try{URI u=URI.create(source);if(!Set.of("http","https").contains(u.getScheme()) || !"textures.minecraft.net".equalsIgnoreCase(u.getHost()) || u.getPort()!=-1 || u.getRawUserInfo()!=null || u.getRawQuery()!=null || u.getRawFragment()!=null || !u.getPath().matches("/texture/[0-9a-fA-F]{32,64}"))return null;return new URI("https",null,"textures.minecraft.net",-1,u.getPath(),null,null);}catch(Exception e){return null;}
    }
    private synchronized CompletionStage<byte[]> skin(String source){
        URI uri=skinUri(source);if(uri==null || closed)return CompletableFuture.completedFuture(null);String key=uri.toString();Cached cached=skins.get(key);
        if(cached!=null && cached.expires>System.nanoTime())return CompletableFuture.completedFuture(cached.png);
        if(cached!=null){skins.remove(key);cacheBytes-=cached.png==null?0:cached.png.length;}
        var existing=pending.get(key);if(existing!=null)return existing;
        CompletableFuture<byte[]> future=new CompletableFuture<>();pending.put(key,future);
        try{network.execute(()->{long began=cpuTime();byte[] png=null;try{HttpURLConnection c=(HttpURLConnection)uri.toURL().openConnection();c.setConnectTimeout(2500);c.setReadTimeout(2500);c.setInstanceFollowRedirects(false);try{if(c.getResponseCode()==200 && c.getContentLengthLong()<=MAX_SKIN)try(InputStream in=c.getInputStream()){png=in.readNBytes(MAX_SKIN+1);if(png.length>MAX_SKIN)png=null;}}finally{c.disconnect();}}catch(IOException ignored){}
            synchronized(ActorAssets.this){pending.remove(key);if(!closed){while(skins.size()>=128 || cacheBytes+(png==null?0:png.length)>CACHE_BYTES){var iterator=skins.entrySet().iterator();if(!iterator.hasNext())break;Cached old=iterator.next().getValue();cacheBytes-=old.png==null?0:old.png.length;iterator.remove();}skins.put(key,new Cached(png,System.nanoTime()+TimeUnit.SECONDS.toNanos(png==null?60:3600)));cacheBytes+=png==null?0:png.length;}}
            long ended=cpuTime();if(began>=0 && ended>=began)cpu.chargeMaintenance(ended-began);future.complete(png);
        });}catch(RejectedExecutionException error){pending.remove(key);future.complete(null);}return future;
    }
    private long cpuTime(){return meter.isCurrentThreadCpuTimeSupported() && meter.isThreadCpuTimeEnabled()?meter.getCurrentThreadCpuTime():-1;}
    public final class Batch implements AutoCloseable,ActorResources {
        private final RenderMemoryBudget.Lease lease;
        private final Map<ResourceKey,Texture> decoded=new HashMap<>();
        private long retained;
        private int occlusionCulled,earlyCulled;
        public synchronized int occlusionCulled(){return occlusionCulled;}
        public synchronized int earlyCulled(){return earlyCulled;}
        private boolean stopped,baking;
        private long incoming;
        private ZipFile source;
        private ItemModelRenderer items;
        @Override public ItemModelRenderer items(){if(items==null)items=new ItemModelRenderer(this);return items;}
        @Override public Texture cached(ResourceKey key){return decoded.get(key);}
        private Batch(RenderMemoryBudget.Lease lease){this.lease=lease;}
        public CompletionStage<List<ActorGeometry>> bake(List<FrozenActor> actors){
            return bake(actors,null);
        }
        public CompletionStage<List<ActorGeometry>> bake(List<FrozenActor> actors,com.wenkrang.famara.render.kernel.ConservativeOcclusion visibility){
            if(actors.size()>512)throw new IllegalArgumentException("镜头候选生物/玩家超过512个采集限额");
            synchronized(this){if(stopped || baking)throw new CancellationException("Actor scene closed or already baking");incoming=actors.stream().filter(a->a.type().equals("PLAYER") || a.type().equals("MANNEQUIN")).count()*MAX_SKIN;lease.resize(512L*1024+incoming);baking=true;}
            CompletionStage<List<FrozenActor>> selected=CompletableFuture.completedFuture(new ArrayList<>());
            for(FrozenActor actor:actors)selected=selected.thenCompose(list->worker.submit(()->{synchronized(this){if(stopped)throw new CancellationException("Actor scene closed");if(visibility!=null){prepare();ActorBounds bounds=catalog.conservativeBounds(actor);if(bounds!=null && bounds.hiddenBy(visibility)){earlyCulled++;occlusionCulled++;return list;}}list.add(actor);return list;}}));
            return selected.thenCompose(filtered->bakeSelected(filtered,visibility)).whenComplete((value,error)->{synchronized(this){baking=false;if(stopped)release();}});
        }
        private CompletionStage<List<ActorGeometry>> bakeSelected(List<FrozenActor> actors,com.wenkrang.famara.render.kernel.ConservativeOcclusion visibility){
            synchronized(this){if(stopped)throw new CancellationException("Actor scene closed");incoming=actors.stream().filter(a->a.type().equals("PLAYER") || a.type().equals("MANNEQUIN")).count()*MAX_SKIN;lease.resize(512L*1024+incoming);}
            List<CompletableFuture<byte[]>> downloads=new ArrayList<>(actors.stream().map(a->(a.type().equals("PLAYER") || a.type().equals("MANNEQUIN"))?skin(a.skinUrl()).toCompletableFuture():CompletableFuture.<byte[]>completedFuture(null)).toList());
            return CompletableFuture.allOf(downloads.toArray(CompletableFuture[]::new)).thenCompose(ignored->{
                CompletionStage<List<ActorGeometry>> chain=CompletableFuture.completedFuture(new ArrayList<>());
                for(int i=0;i<actors.size();i++){int index=i;chain=chain.thenCompose(list->worker.submit(()->{synchronized(this){if(stopped)throw new CancellationException("Actor scene closed");lease.resize(3L*1024*1024+retained+incoming);try(var scratch=memory.reserve("actor-item-build",actors.get(index).equipment().slots().isEmpty()?0:8L*1024*1024)){ActorGeometry geometry=model(actors.get(index),downloads.get(index).join());downloads.set(index,null);if(actors.get(index).type().equals("PLAYER") || actors.get(index).type().equals("MANNEQUIN"))incoming-=MAX_SKIN;ActorBounds bounds=visibility==null?null:ActorBounds.of(geometry);if(bounds!=null && bounds.hiddenBy(visibility)){occlusionCulled++;}else{retained+=2048L+geometry.model().quads().size()*768L;list.add(geometry);}checkSize();lease.resize(3L*1024*1024+retained+incoming);return list;}}}));}
                return chain.thenApply(List::copyOf);
            });
        }
        private void checkSize(){if(retained>12L*1024*1024)throw new IllegalStateException("生物模型超过照片12MiB限额，未输出缺失生物的照片");}
        private ActorGeometry model(FrozenActor a,byte[] skin){
            prepare();try(ZipFile zip=new ZipFile(Objects.requireNonNull(base).toFile())){source=zip;return modelPrepared(a,skin);}catch(IOException error){throw new CompletionException(error);}finally{source=null;}
        }
        private ActorGeometry modelPrepared(FrozenActor a,byte[] skin){
            ResourceKey key;Texture image;
            if(a.type().equals("PLAYER") || a.type().equals("MANNEQUIN")){
                if(skin!=null){try{byte[] normalized=normalizeSkin(skin);key=new ResourceKey("famara","skin/"+HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(normalized)));image=decode(key,normalized);return VanillaActorRenderer.bake(a,key,image,this);}catch(IOException|NoSuchAlgorithmException|AssetException ignored){}}
                key=new ResourceKey("minecraft","entity/player/"+(a.slim()?"slim/alex":"wide/steve"));image=baseTexture(key);return VanillaActorRenderer.bake(a,key,image,this);
            }
            return VanillaActorRenderer.bake(a,null,null,this);
        }
        @Override public ActorModelCatalog catalog(){return catalog;}
        @Override public boolean has(String path){ResourcePack.validatePath(path);return source!=null && source.getEntry(path)!=null;}
        @Override public byte[] read(String path){ResourcePack.validatePath(path);if(source==null)throw new IllegalStateException("Actor source read outside worker slice");var entry=source.getEntry(path);if(entry==null)return null;if(entry.getSize()>MAX_PNG)throw new IllegalStateException("Actor resource too large: "+path);try(InputStream in=source.getInputStream(entry)){byte[] bytes=in.readNBytes(MAX_PNG+1);if(bytes.length>MAX_PNG)throw new IOException("Actor resource exceeds entry limit");return bytes;}catch(IOException error){throw new CompletionException(error);}}
        @Override public Texture texture(ResourceKey key){return baseTexture(key);}
        @Override public Texture generated(ResourceKey key,byte[] png){return decode(key,png);}
        private Texture baseTexture(ResourceKey key){if(decoded.containsKey(key))return decoded.get(key);byte[] png=read(key.file("textures",".png"));if(png==null)throw new IllegalStateException("Missing actor texture "+key);return decode(key,png);}
        private Texture decode(ResourceKey key,byte[] png){Texture existing=decoded.get(key);if(existing!=null)return existing;var files=new HashMap<String,byte[]>();String path=key.file("textures",".png");files.put(path,png);if(source!=null){byte[] meta=read(path+".mcmeta");if(meta!=null)files.put(path+".mcmeta",meta);}Texture texture=Texture.decode(ResourcePack.memory("actor",files,LIMITS),key,LIMITS);retained+=texture.retainedBytes();checkSize();decoded.put(key,texture);return texture;}
        private void release(){decoded.clear();lease.close();releaseBatch();}
        @Override public synchronized void close(){if(stopped)return;stopped=true;if(!baking)release();}
    }
    /** Validate dimensions before allocating, force the vanilla opaque skin regions, retain overlay alpha. */
    static byte[] normalizeSkin(byte[] png)throws IOException {
        BufferedImage image;
        try(ImageInputStream in=ImageIO.createImageInputStream(new ByteArrayInputStream(png))){if(in==null)throw new IOException("Invalid skin");Iterator<ImageReader> readers=ImageIO.getImageReaders(in);if(!readers.hasNext())throw new IOException("Invalid skin PNG");ImageReader reader=readers.next();try{reader.setInput(in,true,true);int w=reader.getWidth(0),h=reader.getHeight(0);if(!reader.getFormatName().equalsIgnoreCase("png") || w!=64 || h!=32 && h!=64)throw new IOException("Skin must be 64x32/64");image=reader.read(0);}finally{reader.dispose();}}
        if(image.getHeight()==32){
            BufferedImage expanded=new BufferedImage(64,64,BufferedImage.TYPE_INT_ARGB);for(int y=0;y<32;y++)for(int x=0;x<64;x++)expanded.setRGB(x,y,image.getRGB(x,y));image=expanded;
            int[][] copies={{4,16,16,32,4,4},{8,16,16,32,4,4},{0,20,24,32,4,12},{4,20,16,32,4,12},{8,20,8,32,4,12},{12,20,16,32,4,12},{44,16,-8,32,4,4},{48,16,-8,32,4,4},{40,20,0,32,4,12},{44,20,-8,32,4,12},{48,20,-16,32,4,12},{52,20,-8,32,4,12}};
            for(int[] c:copies)for(int y=0;y<c[5];y++)for(int x=0;x<c[4];x++)image.setRGB(c[0]+c[2]+x,c[1]+c[3]+y,image.getRGB(c[0]+c[4]-1-x,c[1]+y));
            boolean opaqueHat=true;for(int y=0;y<32;y++)for(int x=32;x<64;x++)if((image.getRGB(x,y)>>>24)<128)opaqueHat=false;
            if(opaqueHat)for(int y=0;y<32;y++)for(int x=32;x<64;x++)image.setRGB(x,y,image.getRGB(x,y)&0xffffff);
        }
        for(int y=0;y<image.getHeight();y++)for(int x=0;x<64;x++)if(y<16 && x<32 || y>=16 && y<32 || y>=48 && (x>=16 && x<48))image.setRGB(x,y,image.getRGB(x,y)|0xff000000);
        ByteArrayOutputStream out=new ByteArrayOutputStream();ImageIO.write(image,"png",out);return out.toByteArray();
    }
    @Override public void close(){List<CompletableFuture<byte[]>> downloads;synchronized(this){if(closed)return;closed=true;downloads=List.copyOf(pending.values());pending.clear();skins.clear();cacheBytes=0;releaseCatalog();}network.shutdownNow();downloads.forEach(f->f.complete(null));}
}
