package com.wenkrang.famara.render.platform;

import com.wenkrang.famara.compat.*;
import com.wenkrang.famara.itemSystem.ItemSystem;
import com.wenkrang.famara.lib.*;
import com.wenkrang.famara.render.asset.*;
import com.wenkrang.famara.render.kernel.*;
import com.wenkrang.famara.render.math.Vec3;
import com.wenkrang.famara.render.output.*;
import com.wenkrang.famara.render.schedule.*;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.*;
import org.bukkit.map.*;
import org.bukkit.plugin.java.JavaPlugin;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

/** 显式模式桥接。所有物品/MapView操作在entity上下文，磁盘和渲染只在有界后台执行。 */
public final class PhotoRuntime implements AutoCloseable {
    public enum Mode { LEGACY, TEXTURED }
    public record Progress(boolean tracked,boolean success,boolean failed,int pixels,String message) {}
    private static volatile PhotoRuntime current;
    private static volatile Mode mode=Mode.TEXTURED;
    private static volatile String unavailable="V2服务尚未启动";
    private static volatile long startup;
    private final JavaPlugin plugin;
    private final TexturedConfiguration config;
    private final RenderMemoryBudget memory;
    private final RenderMemoryBudget.Lease platformMemory;
    private final RenderMemoryBudget.Lease businessMemory;
    private final CpuBudget cpu;
    private final BudgetedWorkRunner io;
    private final AssetRepository assets;
    private final BudgetedAssetLoader loader;
    private final CeVisualResourceService visuals;
    private final BiomeCatalog biomes;
    private final ChunkCaptureService capture;
    private final MapPaletteSnapshot palette;
    private final MapFrameCache frames;
    private final ConcurrentHashMap<UUID,Run> jobs=new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID,String> releases=new ConcurrentHashMap<>();
    private final Set<UUID> releasing=ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<UUID,Long> claims=new ConcurrentHashMap<>();
    private final VersionStateAdapter version;
    private volatile DiskPhotoStore store;
    private volatile CameraLedger ledger;
    private volatile CpuPhotoPipeline pipeline;
    private volatile boolean closed;
    private final java.util.concurrent.atomic.AtomicBoolean startupBoost=new java.util.concurrent.atomic.AtomicBoolean();
    private final long startupAt=System.nanoTime();
    private volatile String startupFailure="";
    private org.bukkit.event.Listener reloadListener;
    private Sched.Task maintenance;
    private static final class Run {
        final UUID id;final CompletableFuture<Void> start=new CompletableFuture<>();
        volatile CpuPhotoPipeline.Handle handle;volatile ImmutableMapRenderer renderer;
        Run(UUID id){this.id=id;}
    }
    private static final class Gated implements CpuPhotoPipeline.Preparation {
        final CpuPhotoPipeline.Preparation delegate;final CompletableFuture<Void> ready;
        Gated(CpuPhotoPipeline.Preparation delegate,CompletableFuture<Void> ready){this.delegate=delegate;this.ready=ready;}
        public long minimumMemoryBytes(){return delegate.minimumMemoryBytes();}
        public boolean scanning(){return ready.isDone() && delegate.scanning();}
        public void wakeup(Runnable wake){delegate.wakeup(wake);ready.whenComplete((v,e)->wake.run());}
        public CpuPhotoPipeline.PreparationStep step(int operations,long deadline)throws Exception {
            if(!ready.isDone())return CpuPhotoPipeline.PreparationStep.WAITING;ready.getNow(null);return delegate.step(operations,deadline);
        }
        public CpuPhotoPipeline.PreparationProgress progress(){return ready.isDone()?delegate.progress():new CpuPhotoPipeline.PreparationProgress("等待预约/地图ID",0,0,"");}
        public CpuPhotoPipeline.Prepared result(){return delegate.result();}
        public void close(){delegate.close();}
    }
    private PhotoRuntime(JavaPlugin plugin,TexturedConfiguration config) {
        this.plugin=plugin;this.config=config;memory=new RenderMemoryBudget(config.memory().maximumBytes());
        platformMemory=memory.reserve("biome-and-runtime",16L*1024*1024);businessMemory=memory.reserve("ledger-and-io",64L*1024*1024);
        palette=MapPaletteAdapter.snapshot();frames=new MapFrameCache(config.maps().maximumCachedFrames());frames.memory(memory);
        version=new VersionStateAdapter(VersionStateAdapter.Version.parse(Bukkit.getMinecraftVersion()),VersionStateAdapter.Version.parse(config.resourceVersion()));
        if(!version.auditedRuntime())plugin.getLogger().warning("当前Minecraft版本未完成兼容审计："+Bukkit.getMinecraftVersion()+"；仅尝试已匹配资源的状态，未知方块/状态会拒绝照片，不保证未来26.x兼容");
        cpu=new CpuBudget(config.pipeline().cpuMillisPerSecond(),config.pipeline().burstMillis(),System::nanoTime);
        plugin.getLogger().info("V2拍照配置：workers="+config.pipeline().workers()+"，每照片渲染路数="+Math.min(2,config.pipeline().workers())+"，共享CPU="+config.pipeline().cpuMillisPerSecond()+"ms/s，burst="+config.pipeline().burstMillis()+"ms，工作封套192MiB，总额="+config.memory().maximumBytes());
        if(mode==Mode.TEXTURED){cpu.reconfigure(config.loading().cpuMillisPerSecond(),config.loading().burstMillis());startupBoost.set(true);}
        CeCompat.loadingBudget(cpu,config.assetBuild());CeCompat.memoryBudget(memory);
        io=new BudgetedWorkRunner(config.io().maximum(),config.io().intervalNanos(),config.io().quantumNanos(),cpu);
        assets=new AssetRepository(config.cache().generations(),config.cache().retainedBytes());loader=new BudgetedAssetLoader(assets,config.assets(),config.assetBuild(),cpu,memory);
        visuals=new CeVisualResourceService(config.base(com.wenkrang.famara.lib.DataFolders.of(plugin).toPath()),config.assets(),config.visuals(),assets,loader,io,cpu,error->{finishBoost("资源准备失败");plugin.getLogger().log(java.util.logging.Level.WARNING,"V2视觉资源未就绪："+reason(error),error);}).memory(memory);
        biomes=new BiomeCatalog(config.biomes(),cpu);capture=new ChunkCaptureService(config.capture(),cpu,memory);
        biomes.readiness().whenComplete((view,error)->{if(error!=null)finishBoost("biome准备失败");});
    }
    /** 配置只读取已校验/迁移的主YAML，不再在运行时竞争加载旧JSON。 */
    public static void start(JavaPlugin plugin) {
        ++startup;
        if(!Config.rendererFailure.isEmpty() || Config.renderer==null){mode=Mode.TEXTURED;unavailable="config.yml未就绪："+Config.rendererFailure;return;}
        var frozen=Config.renderer;mode=frozen.mode();
        try{PhotoRuntime runtime=new PhotoRuntime(plugin,frozen.renderer());current=runtime;runtime.initialize(mode==Mode.TEXTURED);}
        catch(RuntimeException error){unavailable=reason(error);plugin.getLogger().log(java.util.logging.Level.SEVERE,"V2启动失败，未退回旧拍摄："+unavailable,error);}
    }
    private void finishBoost(String reason) {
        if(startupBoost.compareAndSet(true,false)) {
            cpu.reconfigure(config.pipeline().cpuMillisPerSecond(),config.pipeline().burstMillis());
            plugin.getLogger().info("初次加载加速结束（"+reason+"），恢复合计拍照CPU预算="+config.pipeline().cpuMillisPerSecond()+"ms/s；准备总wall="+(System.nanoTime()-startupAt)/1e6+"ms；欠账保留；V2内存="+memory.snapshot());
        }
    }
    private void initialize(boolean render){initialize(render,System.nanoTime());}
    private void initialize(boolean render,long firstAttempt) {
        if(closed)return;
        io.submit(()->checked(()->{
            DiskPhotoStore disk=new DiskPhotoStore(com.wenkrang.famara.lib.DataFolders.of(plugin).toPath().resolve("pictures"));
            CameraLedger book=new CameraLedger(com.wenkrang.famara.lib.DataFolders.of(plugin).toPath().resolve("camera-ledger-v2"),config.ledger(),businessMemory.bytes());
            synchronized(this){if(closed){book.close();throw new CancellationException("插件已关闭");}store=disk;ledger=book;}
            if(render)PlatformCapabilities.require();
            if(render && config.bundledBase()) {
                var source=BundledBaseResources.prepare(plugin.getClass().getClassLoader(),com.wenkrang.famara.lib.DataFolders.of(plugin).toPath(),memory,()->!closed);
                if(!source.manifest().clientVersion().equals(config.resourceVersion()))throw new IllegalStateException("内置资源版本与resourceVersion不一致");
                if(closed)throw new CancellationException("基础源准备后插件已关闭");visuals.base(source.path(),source.manifest().zipSha256());
                plugin.getLogger().info("V2内置基础资源已就绪："+source.manifest().clientVersion()+"，SHA256="+source.manifest().zipSha256()+"，无需外置客户端JAR");
            }
            return null;
        })).whenComplete((ignored,error)->{
            if(error!=null) {
                if(!closed && reason(error).contains("账本文件锁不可用") && System.nanoTime()-firstAttempt<config.pipeline().timeoutNanos()) {
                    Sched.globalLater(()->initialize(render,firstAttempt),1);return;
                }
                unavailable=reason(error);startupFailure=unavailable;finishBoost("基础资源/存储初始化失败");plugin.getLogger().severe("V2初始化未完成："+unavailable);return;
            }
            if(closed)return;
            synchronized(this){if(closed)return;pipeline=render?new CpuPhotoPipeline(config.pipeline(),store,this::publish,cpu):null;}
            Sched.global(()->{
                if(closed)return;
                if(render) {
                    visuals.start();biomes.start();capture.start();
                    reloadListener=new org.bukkit.event.Listener() {
                        @org.bukkit.event.EventHandler public void resources(io.papermc.paper.event.server.ServerResourcesReloadedEvent event) {
                            biomes.invalidate();
                            Sched.global(()->{if(!closed)biomes.reload();});
                        }
                    };
                    Bukkit.getPluginManager().registerEvents(reloadListener,plugin);
                }
                maintenance=Sched.globalTimer(()->{
                    claims.entrySet().removeIf(e->System.nanoTime()-e.getValue()>=0);releases.keySet().forEach(this::releaseLedger);
                    if(startupBoost.get()) {
                        if(System.nanoTime()-startupAt>config.assetBuild().timeoutNanos())finishBoost("初次准备超过总时限");
                        else readiness();
                    }
                },1,1);
            });
        });
    }
    public static boolean textured(){return mode==Mode.TEXTURED;}
    public static int pixelLimit(int requested) {
        PhotoRuntime runtime=current;if(runtime==null || runtime.pipeline==null)throw new IllegalStateException("V2管线未就绪");return runtime.pipeline.pixelLimit(requested);
    }
    public static String status(){PhotoRuntime runtime=current;return runtime==null?unavailable:runtime.readiness();}
    private String readiness() {
        if(!startupFailure.isEmpty())return "V2初始化失败："+startupFailure;
        if(closed || ledger==null || store==null)return "照片存储/账本尚未准备好";
        if(mode==Mode.LEGACY)return "LEGACY（保留V2胶片领取）";
        if(pipeline==null)return "V2照片管线尚未启动";
        if(visuals.ready().isEmpty())return "V2"+visuals.status();
        try{if(biomes.view()==null)return "服务端biome元数据正在冻结";}catch(RuntimeException error){return "biome冻结失败："+reason(error);}
        long epoch=CeCompat.visualEpoch();String failure=CeCompat.colorsFailure(epoch);
        if(failure!=null){finishBoost("旧实体取色失败");return "CE家具取色准备失败："+failure;}
        if(!CeCompat.colorsReady(epoch))return "等待同代CE家具/物品取色索引";
        finishBoost("READY");return "READY";
    }
    /** 始终拦截V2标记，即使管理员已切回LEGACY。旧胶片从不进入此路径。 */
    public static boolean interact(PlayerInteractEvent event) {
        Player player=event.getPlayer();ItemStack held=player.getInventory().getItemInMainHand();
        if(held.getItemMeta()==null)return false;
        ItemMeta meta=held.getItemMeta();PhotoRuntime runtime=current;
        try {
            Optional<UUID> request=CameraIdentity.request(meta);
            boolean camera=ItemUtils.isCamera(held);
            if(request.isPresent()) {
                event.setCancelled(true);
                if(runtime==null || runtime.ledger==null || runtime.closed){player.sendMessage("§cV2胶片服务不可用，物品已保留："+status());return true;}
                if(camera){if(player.isSneaking())runtime.pull(player,held,request.get());else player.sendMessage("§7Shift + 右键取出V2撕拉片；"+runtime.progress(request.get()).message());}
                else if(CeItems.is(held,CeItems.PHOTO))runtime.redeem(player,held,request.get());
                else player.sendMessage("§c物品携带V2请求标记但不是相机或撕拉片，未修改物品");
                return true;
            }
            if(camera && CameraIdentity.account(meta).isPresent() && (runtime==null || runtime.ledger==null || runtime.closed)) {
                event.setCancelled(true);player.sendMessage("§c相机账本未就绪，未扣胶卷："+status());return true;
            }
            if(camera && ItemUtils.checkName(held,"§f相机（Shift + 右键拉出撕拉片）"))return false;
            if(!camera || !textured())return false;
            event.setCancelled(true);held=ItemUtils.normalizeCamera(held);
            if(held.getAmount()!=1){player.sendMessage("§c请把相机拆分为单件后使用，避免复制同一个相机身份");return true;}
            if(player.isSneaking())return true;
            if(!ItemUtils.isViewfinderOpen(held.getItemMeta())) {
                held.setItemMeta(ItemUtils.setViewfinderOpen(held.getItemMeta(),true));player.getInventory().setItemInMainHand(held);
                player.getWorld().playSound(player.getLocation(),"famara:famara.viewfinder",1,1);return true;
            }
            if(runtime==null || !runtime.readiness().equals("READY")){player.sendMessage("§c未受理拍摄（不扣胶卷）："+status());return true;}
            runtime.take(player,held);return true;
        } catch(RuntimeException error){player.sendMessage("§c照片操作失败，未替换物品："+reason(error));return true;}
    }
    private void take(Player player,ItemStack held) {
        if(!releases.isEmpty())throw new IllegalStateException("故障预约正在释放，未受理新拍摄");
        held=synchronize(held);int film=ItemUtils.rawFilmAmount(held);if(film<=0){player.sendMessage("§c相机没有胶卷");return;}
        ItemMeta meta=held.getItemMeta();CameraIdentity.Account account=CameraIdentity.assign(meta);held.setItemMeta(meta);
        var input=visuals.acquire();WorldPreparation preparation=null;Run run=new Run(UUID.randomUUID());
        try {
            var eye=player.getEyeLocation();World world=player.getWorld();
            Camera camera=new Camera(new Vec3(eye.getX(),eye.getY(),eye.getZ()),Math.toRadians(eye.getYaw()+90),Math.toRadians(-eye.getPitch()),Camera.Projection.LEGACY_ANGULAR,1);
            var info=new WorldPreparation.WorldInfo(world.getUID(),world.getMinHeight(),world.getMaxHeight(),world.getSeed(),RuntimeCompat.hasSkyLight(world),RuntimeCompat.skyDarken(world));
            var biome=Objects.requireNonNull(biomes.view());AssetGeneration generation=input.lease().generation();
            var configured=config.shot();
            int sky=skyColor(world.getEnvironment().name(),world.getTime(),world.hasStorm());
            var settings=new CpuPhotoRenderer.Settings(configured.distance(),configured.samples(),configured.filter(),configured.directionalLight(),configured.ambientFloor(),configured.cutoutThreshold(),configured.maxTransparentLayers(),sky,world.getFullTime(),configured.traceLimits(),configured.materialOverrides());
            preparation=new WorldPreparation(capture,world,info,input,camera,settings,palette,config.preparation(),version,
                    config.allowVanillaCe().stream().map(ResourceKey::parse).collect(java.util.stream.Collectors.toUnmodifiableSet()),
                    biome.resolver(generation));
            preparation.memory(memory);preparation.window(config.capture().windowChunks());preparation.lod(config.lod());preparation.occlusion(config.memory().occlusion());preparation.guard(biome::current);
            preparation.extras(new LegacyEntityCapture(input.table().epoch(),config.entities(),camera.origin(),config.shot().distance()));
            ItemStack pendingCamera=ItemSystem.get("camera_filmed");CameraIdentity.copy(held,pendingCamera,false);
            pendingCamera=ItemUtils.writeRawFilm(pendingCamera,film);ItemMeta pendingMeta=pendingCamera.getItemMeta();
            CameraIdentity.request(pendingMeta,run.id);List<String> pendingLore=new ArrayList<>(Objects.requireNonNull(pendingMeta.getLore()));
            while(pendingLore.size()<4)pendingLore.add("");pendingLore.set(3,"§7照片编号：正在分配（V2）");pendingMeta.setLore(pendingLore);pendingCamera.setItemMeta(pendingMeta);
            Gated gate=new Gated(preparation,run.start);
            run.handle=pipeline.submitDeferred(player.getUniqueId(),run.id,preparation.minimumMemoryBytes(),gate);jobs.put(run.id,run);
            player.getInventory().setItemInMainHand(pendingCamera);
            run.handle.result().whenComplete((result,error)->{
                if(error!=null){plugin.getLogger().warning("V2拍摄失败："+run.handle.diagnostics()+"，原因="+reason(error));run.start.completeExceptionally(error);failLedger(run.id,reason(error));}
                else plugin.getLogger().info("V2拍摄完成："+run.handle.diagnostics());
                jobs.remove(run.id,run);
            });
            final int raw=film;UUID owner=player.getUniqueId();
            io.submit(()->checked(()->{if(closed || run.handle.state().stage().terminal())throw new CancellationException("预约前拍摄已失效");return ledger.reserve(run.id,account.camera(),owner,raw,account.appliedSpent());})).whenComplete((reserved,error)->{
                if(error!=null){run.handle.fail(error);return;}
                if(closed || run.handle.state().stage().terminal()){failLedger(run.id,"拍摄已取消");return;}
                Sched.entity(player,()->bindMap(player,world,run));
            });
            player.getWorld().playSound(player.getLocation(),"famara:famara.shutter",1,1);player.sendMessage("§7拍摄已排队，胶卷仅预约；出片成功后计费，失败不扣。");
        } catch(Throwable error) {
            if(run.handle!=null)run.handle.fail(error);else {if(preparation!=null)preparation.close();else input.close();}
            throw error;
        }
    }
    private static int skyColor(String environment,long time,boolean rain) {
        if(environment.equals("NETHER"))return 0x841616;if(environment.equals("THE_END"))return 0;
        int r=0,g=0,b=0;
        if(time<6000 || time>=23300){r=100;g=150;b=255;}else if(time<12000){r=135;g=206;b=250;}else if(time<23000){r=20;g=20;b=40;}
        if(rain){r=Math.max(0,r-30);g=Math.max(0,g-30);b=Math.max(0,b-50);}return r<<16 | g<<8 | b;
    }
    private void bindMap(Player player,World world,Run run) {
        if(closed || run.handle.state().stage().terminal())return;
        try {
            MapView view=Bukkit.createMap(world);view.setLocked(true);for(MapRenderer renderer:view.getRenderers())view.removeRenderer(renderer);
            ImmutableMapRenderer renderer=renderer(view.getId(),config.maps().maximumCanvases());view.addRenderer(renderer);run.renderer=renderer;
            io.submit(()->checked(()->ledger.bindOutput(run.id,view.getId()))).whenComplete((job,error)->{
                if(error!=null)run.handle.fail(error);
                else if(!closed && run.handle.bindOutput(job.mapId()))run.start.complete(null);
                else failLedger(run.id,"地图绑定时任务已失效");
            });
        } catch(Throwable error){run.handle.fail(error);if(error instanceof VirtualMachineError fatal)throw fatal;}
    }
    private CompletionStage<Void> publish(DiskPhotoStore.Stored stored,ImmutablePhoto photo,BooleanSupplier current) {
        UUID request=UUID.fromString(photo.metadata().get("request"));Run run=jobs.get(request);
        if(run==null || run.renderer==null || !current.getAsBoolean())return CompletableFuture.failedFuture(new CancellationException("地图发布已失效"));
        synchronized(this) {
            if(closed)return CompletableFuture.failedFuture(new CancellationException("照片服务关闭"));
            if(!run.renderer.publish(photo.copyMap(),current))return CompletableFuture.failedFuture(new CancellationException("地图索引未发布"));
            if(closed || !(current instanceof CpuPhotoPipeline.CommitGate gate) || !gate.beginSettlement())return CompletableFuture.failedFuture(new CancellationException("胶卷最终结算未开始"));
            try{return io.submit(()->checked(()->{ledger.succeed(request);return (Void)null;}));}
            catch(RuntimeException error){return CompletableFuture.failedFuture(error);}
        }
    }
    private void failLedger(UUID request,String reason) {
        releases.putIfAbsent(request,reason.substring(0,Math.min(512,reason.length())));releaseLedger(request);
    }
    private void releaseLedger(UUID request) {
        String reason=releases.get(request);if(reason==null || !releasing.add(request))return;
        try {
            io.submit(()->checked(()->{CameraLedger book=ledger;if(book!=null && book.snapshot().job(request).isPresent())book.fail(request,reason);return null;}))
                    .whenComplete((ignored,error)->{
                        releasing.remove(request);CameraLedger book=ledger;
                        var job=book==null?Optional.<CameraLedger.Job>empty():book.snapshot().job(request);
                        if(job.isEmpty() || job.get().status()!=CameraLedger.Status.RESERVED)releases.remove(request);
                        if(error!=null)plugin.getLogger().warning("胶卷失败预约释放/持久化诊断："+reason(error));
                    });
        } catch(RejectedExecutionException error){releasing.remove(request);if(closed)plugin.getLogger().warning("胶卷失败预约留待重启释放："+request);}
    }
    public static ItemStack synchronizeCamera(ItemStack item) {
        if(item==null || item.getItemMeta()==null || CameraIdentity.account(item.getItemMeta()).isEmpty())return item;
        PhotoRuntime runtime=current;if(runtime==null || runtime.ledger==null || runtime.closed)throw new IllegalStateException("带V2身份的相机等待持久账本加载，未重置消费计数");return runtime.synchronize(item);
    }
    private ItemStack synchronize(ItemStack item) {
        if(item.getItemMeta()==null || CameraIdentity.account(item.getItemMeta()).isEmpty())return item;
        var account=CameraIdentity.account(item.getItemMeta()).orElseThrow();
        CameraLedger.Snapshot snapshot=ledger.snapshot();snapshot.balance(account.camera(),ItemUtils.rawFilmAmount(item),account.appliedSpent());
        if(snapshot.spent(account.camera())==account.appliedSpent())return item;
        ItemStack copy=item.clone();ItemMeta meta=copy.getItemMeta();var film=CameraIdentity.synchronize(meta,ItemUtils.rawFilmAmount(copy),snapshot);
        copy.setItemMeta(meta);return ItemUtils.writeRawFilm(copy,film.balance().loaded());
    }
    private void pull(Player player,ItemStack held,UUID request) {
        CameraLedger.Job job=ledger.snapshot().job(request).orElse(null);if(job==null){if(jobs.containsKey(request))player.sendMessage("§7胶卷预约尚在落盘，请稍后取片");else{clearCamera(player,held);player.sendMessage("§c拍摄预约未成立，未扣胶卷，已清除失效请求");}return;}
        held=synchronize(held);
        if(job.status()==CameraLedger.Status.FAILED){clearCamera(player,held);player.sendMessage("§c拍摄失败，预约已释放、没有扣胶卷："+job.reason());return;}
        if(job.mapId()<0){player.sendMessage("§7尚未分配地图，请稍后取片");return;}
        ItemStack film=ItemSystem.get("photo_unPull");ItemMeta meta=film.getItemMeta();List<String> lore=new ArrayList<>(Objects.requireNonNull(meta.getLore()));
        while(lore.size()<4)lore.add("");lore.set(3,"§7照片编号："+job.mapId());meta.setLore(lore);CameraIdentity.request(meta,request);film.setItemMeta(meta);
        ItemStack camera=normalCamera(held);int empty=player.getInventory().firstEmpty();
        if(empty<0 || empty>=36) {
            var dropped=player.getWorld().dropItem(player.getLocation(),film);
            if(!dropped.isValid()){player.sendMessage("§c撕拉片掉落被取消，原相机请求已保留");return;}
        } else player.getInventory().setItem(empty,film);
        player.getInventory().setItemInMainHand(camera);
        player.getWorld().playSound(player.getLocation(),"famara:famara.pull.film",1,1);
    }
    private void clearCamera(Player player,ItemStack held){player.getInventory().setItemInMainHand(normalCamera(held));}
    private ItemStack normalCamera(ItemStack held) {
        ItemStack camera=ItemSystem.get("camera");CameraIdentity.copy(held,camera,false);camera=ItemUtils.writeRawFilm(camera,ItemUtils.rawFilmAmount(held));
        camera.setItemMeta(ItemUtils.setViewfinderOpen(camera.getItemMeta(),ItemUtils.isViewfinderOpen(held.getItemMeta())));return camera;
    }
    private void redeem(Player player,ItemStack held,UUID request) {
        CameraLedger.Job job=ledger.snapshot().job(request).orElse(null);
        if(job==null || job.status()==CameraLedger.Status.RESERVED){player.sendMessage("§7照片尚未完成："+progress(request).message());return;}
        if(job.status()!=CameraLedger.Status.SUCCEEDED){player.sendMessage("§c拍摄失败且未扣胶卷；此撕拉片保留供核对："+job.reason());return;}
        if(held.getAmount()!=1){player.sendMessage("§c请将撕拉片拆分为单件再领取");return;}
        if(claims.size()>=config.maps().maximumRecoveryRequests() || claims.putIfAbsent(request,System.nanoTime()+config.pipeline().timeoutNanos())!=null){player.sendMessage("§7照片正在读取或领取队列已满，请稍后重试");return;}
        try {
            io.submit(()->checked(()->{
                ImmutablePhoto photo=store.loadCommitted(job.mapId()).orElseThrow(()->new IllegalStateException("照片收据未完成，未替换胶片"));
                if(!request.toString().equals(photo.metadata().get("request")))throw new IllegalStateException("胶片请求与存储收据不匹配");return photo;
            })).whenComplete((photo,error)->{
                if(error!=null){claims.remove(request);Sched.entity(player,()->player.sendMessage("§c照片读取失败，胶片已保留："+reason(error)));return;}
                Sched.entity(player,()->{
                    try {
                        if(closed)return;
                        ItemStack now=player.getInventory().getItemInMainHand();
                        if(now.getAmount()!=1 || now.getItemMeta()==null || !CameraIdentity.request(now.getItemMeta()).equals(Optional.of(request)) || !CeItems.is(now,CeItems.PHOTO))return;
                        MapView view=Bukkit.getMap(job.mapId());if(view==null){player.sendMessage("§c原Map ID已不存在，未创建替代地图或销毁胶片");return;}
                        install(view,photo,config.maps().maximumCanvases());
                        ItemStack item=ItemSystem.get("photo");MapMeta meta=(MapMeta)item.getItemMeta();meta.setMapView(view);item.setItemMeta(meta);
                        player.getInventory().setItemInMainHand(item);player.getWorld().playSound(player.getLocation(),"famara:famara.film",1,1);
                    } finally{claims.remove(request);}
                });
            });
        } catch(RuntimeException error){claims.remove(request);throw error;}
    }
    private ImmutableMapRenderer renderer(int mapId,int maximumCanvases) {
        var reference=new java.util.concurrent.atomic.AtomicReference<ImmutableMapRenderer>();
        var weak=new java.lang.ref.WeakReference<>(this);
        ImmutableMapRenderer renderer=new ImmutableMapRenderer(maximumCanvases,frames,()->{
            PhotoRuntime active=weak.get();
            if(active==null || active.closed){reference.get().loadFinished();return;}
            try{active.io.submit(()->checked(()->active.store.loadCommitted(mapId).orElseThrow(()->new IllegalStateException("照片未提交"))))
                    .whenComplete((photo,error)->{try{if(error==null && !active.closed)reference.get().publish(photo.copyMap(),()->!active.closed);}finally{reference.get().loadFinished();}});}
            catch(RuntimeException error){reference.get().loadFinished();}
        });reference.set(renderer);return renderer;
    }
    public static void install(MapView view,ImmutablePhoto photo,int maximumCanvases) {
        ImmutableMapRenderer renderer=null;
        for(MapRenderer existing:view.getRenderers())if(existing instanceof ImmutableMapRenderer immutable){renderer=immutable;break;}
        if(renderer==null) {
            for(MapRenderer existing:view.getRenderers())if(existing.getClass().getName().startsWith("com.wenkrang.famara."))view.removeRenderer(existing);
            PhotoRuntime runtime=current;renderer=runtime==null?new ImmutableMapRenderer(maximumCanvases):runtime.renderer(view.getId(),maximumCanvases);view.addRenderer(renderer);
        }
        view.setLocked(true);renderer.publish(photo.copyMap(),()->true);
    }
    private Progress progress(UUID request) {
        CameraLedger.Job job=ledger==null?null:ledger.snapshot().job(request).orElse(null);Run run=jobs.get(request);
        if(job!=null && job.status()==CameraLedger.Status.SUCCEEDED)return new Progress(true,true,false,16384,"出片与记账成功，可以撕开");
        if(job!=null && job.status()==CameraLedger.Status.FAILED)return new Progress(true,false,true,0,"失败，未扣胶卷："+job.reason());
        int pixels=run==null?0:Math.min(16383,run.handle.completedPixels());
        return new Progress(true,false,false,pixels,run==null?"等待账本回执":run.handle.progressMessage());
    }
    public static Progress progress(ItemStack item) {
        if(item==null || item.getItemMeta()==null)return new Progress(false,false,false,0,"");
        try {
            var request=CameraIdentity.request(item.getItemMeta());if(request.isEmpty())return new Progress(false,false,false,0,"");
            PhotoRuntime runtime=current;return runtime==null?new Progress(true,false,false,0,status()):runtime.progress(request.get());
        } catch(RuntimeException error){return new Progress(true,false,true,0,"V2物品标记无效，已保留");}
    }
    @FunctionalInterface private interface Checked<T>{T run()throws Exception;}
    private static <T>T checked(Checked<T> work){try{return work.run();}catch(Exception error){throw new CompletionException(error);}}
    private static String reason(Throwable error){while((error instanceof CompletionException || error instanceof ExecutionException) && error.getCause()!=null)error=error.getCause();return error.toString();}
    public static void stop(){startup++;PhotoRuntime runtime=current;current=null;if(runtime!=null)runtime.close();}
    @Override public synchronized void close() {
        if(closed)return;closed=true;finishBoost("服务关闭");if(reloadListener!=null)org.bukkit.event.HandlerList.unregisterAll(reloadListener);if(maintenance!=null)maintenance.cancel();visuals.close();biomes.close();capture.close();loader.close();
        if(pipeline!=null)pipeline.close();assets.close();claims.clear();frames.close();
        // 结算已在IO串行队列中排在关闭之前；不在游戏线程join或进行文件操作。
        try{io.submit(()->checked(()->{if(ledger!=null)ledger.close();businessMemory.close();platformMemory.close();return null;})).whenComplete((v,e)->io.close());}
        catch(RejectedExecutionException error){io.closeAfter(()->checked(()->{if(ledger!=null)ledger.close();businessMemory.close();platformMemory.close();return null;}));}
    }
}
