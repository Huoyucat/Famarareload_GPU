package com.wenkrang.famara.render.platform;

import com.wenkrang.famara.render.asset.*;
import com.wenkrang.famara.render.kernel.*;
import com.wenkrang.famara.render.output.MapPaletteSnapshot;
import com.wenkrang.famara.render.scene.*;
import com.wenkrang.famara.render.schedule.CpuPhotoPipeline;
import org.bukkit.World;
import org.bukkit.ChunkSnapshot;
import java.util.*;
import java.util.concurrent.*;

/** 活世界只由ChunkCaptureService读取。worker在冻结快照上分批扫描宿主、邻域与外伸索引。 */
public final class WorldPreparation implements CpuPhotoPipeline.Preparation {
    public record WorldInfo(UUID id,int minHeight,int maxHeight,long seed,boolean hasSkyLight,int skyDarken) {
        public WorldInfo(UUID id,int minHeight,int maxHeight,long seed){this(id,minHeight,maxHeight,seed,true,0);}
        public WorldInfo{Objects.requireNonNull(id);if(minHeight>=maxHeight || skyDarken<0 || skyDarken>15 || (minHeight&15)!=0 || (maxHeight&15)!=0)throw new IllegalArgumentException("世界高度无效");}
    }
    public record Limits(long maximumScannedCells,int maximumStates,int maximumInstances,int maximumIndexEntries) {
        public Limits{if(maximumScannedCells<=0 || maximumStates<=0 || maximumInstances<=0 || maximumIndexEntries<=0)throw new IllegalArgumentException("场景准备限额无效");}
    }
    /** 实现必须只访问传入的冻结快照与预先冻结的纯元数据，不能读取活世界或玩家。 */
    @FunctionalInterface public interface LightingResolver extends AutoCloseable {
        default LightingResolver forChunk(com.wenkrang.famara.render.schedule.MemoryScope memory){return this;}
        @Override default void close() {}
        SceneSnapshot.Lighting resolve(ChunkSnapshot snapshot,int localX,int y,int localZ,CanonicalBlockState visual);
    }
    private record State(VisualStateTable.Resolved resolved,boolean solid,boolean confined,AssetGeneration.ModelBounds bounds,CanonicalBlockState server) {}
    private static final State EMPTY=new State(null,false,true,null,null);
    private static final CanonicalBlockState AIR=CanonicalBlockState.parse("minecraft:air");
    private final ChunkCaptureService capture;
    private World world;
    private final WorldInfo info;
    private final Camera camera;
    private final CaptureFrustum frustum;
    private final int modelHalo;
    private record ScanSection(int x,int y,int z) {}
    private final Set<ScanSection> candidateSections;
    private final long candidateCells;
    private final CpuPhotoRenderer.Settings settings;
    private final MapPaletteSnapshot palette;
    private final Limits limits;
    private final VersionStateAdapter version;
    private final Set<ResourceKey> allowedVanilla;
    private final VisualStateTable visual;
    private final LightingResolver lighting;
    private java.util.function.BooleanSupplier inputsCurrent=()->true;
    private ChunkCaptureService.Extras extras;
    private Iterator<LegacyEntity> entityIterator;
    private java.util.concurrent.CompletableFuture<List<ActorGeometry>> actorsReady;
    private Iterator<ActorGeometry> actorIterator;
    private AutoCloseable actorResources;
    private int livingActorCount;
    private Map<String,String> actorMetadata=Map.of();
    private boolean addingEntities;
    private int entityCount;
    private AutoCloseable tableMemory;
    private AssetRepository.Lease lease;
    private final Map<CanonicalBlockState,State> states=new HashMap<>();
    private final int x0,x1,y0,y1,z0,z1;
    private int x,y,z;
    private long scanned,culled,skippedCells,fastInterior,scanCpuNanos,scanWallNanos,scanSlices,scanStarted,scanCompleted;
    private final java.lang.management.ThreadMXBean scanMeter=java.lang.management.ManagementFactory.getThreadMXBean();
    private boolean scanWallFallback;
    private int segmentX=Integer.MIN_VALUE,segmentY,segmentZ;
    private long[] lookupKeys;private boolean[] lookupValid;private FrozenChunk[] lookupValues;
    private FrozenChunk segmentChunk;
    private boolean segmentCandidate,segmentOccluded;
    @Override public boolean scanning(){return capturedAll && sectionChunks==null && !addingEntities && building==null && result==null && y<=y1;}
    private long scanCpu(){return scanMeter.isCurrentThreadCpuTimeSupported() && scanMeter.isThreadCpuTimeEnabled()?scanMeter.getCurrentThreadCpuTime():-1;}
    private ChunkCaptureService.Request request;
    private com.wenkrang.famara.render.schedule.RenderMemoryBudget global;
    private com.wenkrang.famara.render.schedule.MemoryScope memory;
    private final Map<ChunkCaptureService.Coordinate,FrozenChunk> frozen=new HashMap<>();
    private Iterator<ChunkCaptureService.Coordinate> pendingChunks;
    private ChunkCaptureService.Coordinate convertingChunk,deferredChunk;
    private final ArrayDeque<ChunkCaptureService.Coordinate> captureBatch=new ArrayDeque<>(2);
    private int captureWindow=1;
    private long waitingSince,waitingNanos,rawPeak,localInterior,gapRuns;
    private long dispatchNanos,loadingNanos,ownerQueueNanos,copyNanos,copyCpuNanos;
    private int captureRetries;
    public WorldPreparation window(int chunks){if(chunks<1 || chunks>2 || pendingChunks!=null)throw new IllegalArgumentException("捕获窗口无效或已开始");captureWindow=chunks;return this;}
    private final BitSet candidateBits;
    private final int candidateMinX,candidateMinY,candidateMinZ,candidateWidth,candidateDepth;
    private FrozenChunk.Cursor converting;
    private ChunkCaptureService.Extras streamingExtras;
    private long captureStart=Long.MAX_VALUE,captureEnd;
    private boolean capturedAll;
    private int totalChunks,completedChunks;
    private ConservativeOcclusion occlusion;
    private FarLod.Policy lodPolicy=FarLod.Policy.disabled();
    private FarLod farLod;
    private long lodGroups,lodHidden,frozenPeak;
    public WorldPreparation lod(FarLod.Policy policy){if(pendingChunks!=null || capturedAll)throw new IllegalStateException("准备已开始");lodPolicy=Objects.requireNonNull(policy);return this;}
    private boolean occlusionEnabled=true,updatingOcclusion,metadataOnly;
    private final Set<ChunkCaptureService.Coordinate> occludedChunks=new HashSet<>();
    private final List<LegacyEntity> metadataEntities=new ArrayList<>();
    private long occludedHosts;
    public WorldPreparation occlusion(boolean enabled){occlusionEnabled=enabled;return this;}
    public WorldPreparation memory(com.wenkrang.famara.render.schedule.RenderMemoryBudget global){this.global=Objects.requireNonNull(global);return this;}
    public static long sceneWorkspaceBytes(){return 192L*1024*1024;}
    private Map<ChunkCaptureService.Coordinate,ChunkCaptureService.Captured> snapshots;
    private SceneSnapshot.Builder builder;
    private SceneSnapshot.Builder.BuildCursor building;
    private Iterator<ChunkCaptureService.Coordinate> sectionChunks;
    private ChunkCaptureService.Coordinate sectionChunk;
    private int sectionY;
    private CpuPhotoPipeline.Prepared result;
    private volatile Runnable wakeup=()->{};
    private volatile boolean closed;

    /** 此构造器必须在请求所属entity线程捕获WorldInfo之后调用，且调用方保有输入租约直到准入成功。 */
    public WorldPreparation(ChunkCaptureService capture,World world,WorldInfo info,CeVisualResourceService.Input input,
                            Camera camera,CpuPhotoRenderer.Settings settings,MapPaletteSnapshot palette,Limits limits,
                            VersionStateAdapter version,Set<ResourceKey> allowedVanilla,LightingResolver lighting) {
        this.capture=Objects.requireNonNull(capture);this.world=Objects.requireNonNull(world);this.info=Objects.requireNonNull(info);
        this.lease=Objects.requireNonNull(input).lease();visual=input.table();tableMemory=input.tableMemory();this.camera=Objects.requireNonNull(camera);
        this.settings=Objects.requireNonNull(settings);this.palette=Objects.requireNonNull(palette);this.limits=Objects.requireNonNull(limits);
        this.version=Objects.requireNonNull(version);this.allowedVanilla=Set.copyOf(allowedVanilla);this.lighting=Objects.requireNonNull(lighting);
        int halo=lease.generation().hostHalo();modelHalo=halo-1;frustum=new CaptureFrustum(camera,settings.distance());double reach=settings.distance()+halo;
        x0=coordinate(camera.origin().x()-reach);x1=coordinate(camera.origin().x()+reach);
        z0=coordinate(camera.origin().z()-reach);z1=coordinate(camera.origin().z()+reach);
        y0=Math.max(info.minHeight,coordinate(camera.origin().y()-reach));y1=Math.min(info.maxHeight-1,coordinate(camera.origin().y()+reach));
        long cells;
        try{cells=y0>y1?0:Math.multiplyExact(Math.multiplyExact((long)x1-x0+1,(long)z1-z0+1),(long)y1-y0+1);}
        catch(ArithmeticException error){throw new IllegalArgumentException("快照候选范围溢出",error);}
        Set<ScanSection> sections=new HashSet<>();long visibleCells=0;
        int sectionX0=Math.floorDiv(x0,16),sectionX1=Math.floorDiv(x1,16),sectionY0=Math.floorDiv(y0,16),sectionY1=Math.floorDiv(y1,16),sectionZ0=Math.floorDiv(z0,16),sectionZ1=Math.floorDiv(z1,16);
        for(int sy=sectionY0;sy<=sectionY1;sy++)for(int sz=sectionZ0;sz<=sectionZ1;sz++)for(int sx=sectionX0;sx<=sectionX1;sx++) {
            double minX=Math.max(x0,sx*16.0)-modelHalo,minY=Math.max(y0,sy*16.0)-modelHalo,minZ=Math.max(z0,sz*16.0)-modelHalo;
            double maxX=Math.min(x1+1.0,(sx+1)*16.0)+modelHalo,maxY=Math.min(y1+1.0,(sy+1)*16.0)+modelHalo,maxZ=Math.min(z1+1.0,(sz+1)*16.0)+modelHalo;
            if(frustum.mayIntersect(minX,minY,minZ,maxX,maxY,maxZ)) {
                sections.add(new ScanSection(sx,sy,sz));
                long dx=Math.max(0,Math.min(x1,(sx+1)*16-1)-Math.max(x0,sx*16)+1L),dy=Math.max(0,Math.min(y1,(sy+1)*16-1)-Math.max(y0,sy*16)+1L),dz=Math.max(0,Math.min(z1,(sz+1)*16-1)-Math.max(z0,sz*16)+1L);
                visibleCells=Math.addExact(visibleCells,Math.multiplyExact(Math.multiplyExact(dx,dy),dz));
            }
        }
        candidateSections=Set.copyOf(sections);candidateCells=visibleCells;
        candidateMinX=sectionX0;candidateMinY=sectionY0;candidateMinZ=sectionZ0;candidateWidth=sectionX1-sectionX0+1;candidateDepth=sectionZ1-sectionZ0+1;
        long bitCount=Math.multiplyExact(Math.multiplyExact((long)candidateWidth,candidateDepth),sectionY1-sectionY0+1L);if(bitCount>Integer.MAX_VALUE)throw new IllegalArgumentException("候选section索引超限");candidateBits=new BitSet((int)bitCount);
        for(ScanSection section:sections)candidateBits.set(candidateIndex(section.x,section.y,section.z));
        if(visibleCells>limits.maximumScannedCells)throw new IllegalArgumentException("候选扫描量超过preparation.maximumScannedCells：视锥请求="+visibleCells+"，完整包围盒="+cells+"，上限="+limits.maximumScannedCells);
        x=x0;y=y0;z=z0;
    }
    public WorldPreparation guard(java.util.function.BooleanSupplier current){if(request!=null)throw new IllegalStateException("采集已开始");inputsCurrent=Objects.requireNonNull(current);return this;}
    public WorldPreparation extras(ChunkCaptureService.Extras extras) {if(request!=null)throw new IllegalStateException("采集已开始");this.extras=new SharedCaptureExtras(Objects.requireNonNull(extras));return this;}
    private static int coordinate(double value) {
        if(!Double.isFinite(value) || value<Integer.MIN_VALUE+32.0 || value>Integer.MAX_VALUE-32.0)throw new IllegalArgumentException("快照坐标越界");
        return (int)Math.floor(value);
    }
    @Override public long minimumMemoryBytes(){return sceneWorkspaceBytes();}
    @Override public void wakeup(Runnable callback){wakeup=Objects.requireNonNull(callback);}
    @Override public CpuPhotoPipeline.PreparationStep step(int operations,long deadline) {
        if(closed || operations<=0)throw new IllegalStateException("场景准备已关闭或步长无效");
        if(result!=null)return CpuPhotoPipeline.PreparationStep.DONE;
        if(!inputsCurrent.getAsBoolean())throw new IllegalStateException("场景输入在准备期间更新");
        if(com.wenkrang.famara.compat.CeCompat.visualEpoch()!=visual.epoch())throw new IllegalStateException("世界快照期间CE视觉代已变化，未混用映射");
        if(!capturedAll && pendingChunks==null) {
            if(global!=null && memory==null){memory=com.wenkrang.famara.render.schedule.MemoryScope.incremental(global,"photo-workspace",sceneWorkspaceBytes());memory.claim(256L+candidateBits.size()/8L);}
            if(lookupKeys==null){if(memory!=null)memory.claim(1280);lookupKeys=new long[64];lookupValid=new boolean[64];lookupValues=new FrozenChunk[64];}
            if(occlusionEnabled){if(memory!=null)memory.claim(ConservativeOcclusion.estimatedBytes(settings.samples()));occlusion=new ConservativeOcclusion(camera,settings.samples(),settings.distance());}
            Set<ChunkCaptureService.Coordinate> chunkSet=new LinkedHashSet<>();
            int chunkHalo=Math.max(1,(modelHalo+16)/16);
            for(ScanSection section:candidateSections)for(int dx=-chunkHalo;dx<=chunkHalo;dx++)for(int dz=-chunkHalo;dz<=chunkHalo;dz++)
                chunkSet.add(new ChunkCaptureService.Coordinate(section.x()+dx,section.z()+dz));
            if(chunkSet.isEmpty())chunkSet.add(new ChunkCaptureService.Coordinate(Math.floorDiv((int)Math.floor(camera.origin().x()),16),Math.floorDiv((int)Math.floor(camera.origin().z()),16)));
            if(chunkSet.size()>capture.maximumChunksPerRequest())throw new IllegalStateException("视锥候选区块超过capture.maxChunksPerRequest：请求="+chunkSet.size()+"，上限="+capture.maximumChunksPerRequest());
            List<ChunkCaptureService.Coordinate> chunks=new ArrayList<>(chunkSet);
            chunks.sort(Comparator.comparingDouble(c->Math.pow(c.x()*16+8-camera.origin().x(),2)+Math.pow(c.z()*16+8-camera.origin().z(),2)));
            totalChunks=chunks.size();pendingChunks=chunks.iterator();streamingExtras=extras;extras=null;
        }
        if(!capturedAll) {
            if(updatingOcclusion) {
                if(!occlusion.update(this::occluder,operations,deadline))return CpuPhotoPipeline.PreparationStep.CONTINUE;
                updatingOcclusion=false;return CpuPhotoPipeline.PreparationStep.CONTINUE;
            }
            if(converting!=null) {
                if(!converting.step(operations,deadline))return CpuPhotoPipeline.PreparationStep.CONTINUE;
                frozen.put(convertingChunk,converting.result());Arrays.fill(lookupValid,false);Arrays.fill(lookupValues,null);frozenPeak=Math.max(frozenPeak,frozen.values().stream().mapToLong(FrozenChunk::retainedBytes).sum());converting.close();converting=null;consumeCurrent();
                if(occlusion!=null){occlusion.beginUpdate();updatingOcclusion=true;}
                return CpuPhotoPipeline.PreparationStep.CONTINUE;
            }
            if(request!=null) {
                var future=request.result().toCompletableFuture();if(!future.isDone())return CpuPhotoPipeline.PreparationStep.WAITING;
                future.getNow(null);if(waitingSince!=0){waitingNanos+=System.nanoTime()-waitingSince;waitingSince=0;}
                if(convertingChunk==null)convertingChunk=captureBatch.removeFirst();
                var raw=request.snapshots().get(convertingChunk);
                if(raw==null)throw new SceneException(SceneException.Code.SCENE_INCOMPLETE,"捕获请求缺少目标区块");
                captureStart=Math.min(captureStart,raw.startedNanos());captureEnd=Math.max(captureEnd,raw.completedNanos());
                if(metadataOnly) {
                    metadataEntities.addAll(raw.entities());if(memory!=null)memory.claim(raw.entities().stream().mapToLong(e->256L+e.boxes().size()*128L).sum());
                    consumeCurrent();return CpuPhotoPipeline.PreparationStep.CONTINUE;
                }
                converting=FrozenChunk.cursor(raw.snapshot(),convertingChunk.x(),convertingChunk.z(),info.minHeight,info.maxHeight,y0-1,y1+1,lighting,visual,lease.generation(),version,memory,raw.entities());
                return CpuPhotoPipeline.PreparationStep.CONTINUE;
            }
            if(deferredChunk!=null || pendingChunks.hasNext()) {
                var first=deferredChunk!=null?deferredChunk:pendingChunks.next();deferredChunk=null;metadataOnly=canSkip(first);captureBatch.add(first);
                while(captureBatch.size()<captureWindow && pendingChunks.hasNext()) {
                    var next=pendingChunks.next();if(canSkip(next)!=metadataOnly){deferredChunk=next;break;}captureBatch.add(next);
                }
                var coordinates=List.copyOf(captureBatch);if(metadataOnly)occludedChunks.addAll(coordinates);
                if(metadataOnly)request=capture.metadata(world,coordinates,streamingExtras==null?chunk->List.of():streamingExtras);
                else request=streamingExtras==null?capture.capture(world,coordinates):capture.capture(world,coordinates,streamingExtras);
                rawPeak=Math.max(rawPeak,request.reservedBytes());waitingSince=System.nanoTime();
                request.result().whenComplete((ready,error)->{if(!closed)wakeup.run();});return CpuPhotoPipeline.PreparationStep.WAITING;
            }
            if(actorsReady==null){actorsReady=streamingExtras==null?java.util.concurrent.CompletableFuture.completedFuture(List.of()):streamingExtras.finishActors(occlusion).toCompletableFuture();actorsReady.whenComplete((value,error)->{if(!closed)wakeup.run();});}
            if(!actorsReady.isDone())return CpuPhotoPipeline.PreparationStep.WAITING;
            var actors=actorsReady.join();actorIterator=actors.iterator();
            if(streamingExtras!=null){actorResources=streamingExtras.transferActorResources();actorMetadata=streamingExtras.actorMetadata();}
            capturedAll=true;pendingChunks=null;closeExtras();world=null;
            builder=new SceneSnapshot.Builder(lease.generation(),info.minHeight,info.maxHeight,limits.maximumInstances,limits.maximumIndexEntries).memory(memory);
            if(actorResources!=null)builder.actorResources(actorResources);
            if(lodPolicy.enabled())farLod=new FarLod(camera,settings,lodPolicy,lease.generation(),memory);
            occludedChunks.forEach(c->builder.occludedChunk(c.x(),c.z()));
            entityIterator=java.util.stream.Stream.concat(frozen.values().stream().flatMap(c->c.entities().stream()),metadataEntities.stream()).iterator();addingEntities=true;
            sectionChunks=frozen.keySet().iterator();sectionY=info.minHeight>>4;
        }
        if(sectionChunks!=null) {
            for(int work=0;work<operations && System.nanoTime()<deadline;work++) {
                if(sectionChunk==null) {
                    if(!sectionChunks.hasNext()){sectionChunks=null;break;}
                    sectionChunk=sectionChunks.next();sectionY=info.minHeight>>4;
                }
                builder.availableSection(sectionChunk.x(),sectionY,sectionChunk.z());
                if(++sectionY>=(info.maxHeight>>4))sectionChunk=null;
            }
            return CpuPhotoPipeline.PreparationStep.CONTINUE;
        }
        if(addingEntities) {
            for(int n=0;n<operations && System.nanoTime()<deadline;n++) {
                if(!entityIterator.hasNext()){addingEntities=false;entityIterator=null;break;}
                builder.entity(entityIterator.next());entityCount++;
            }
            return CpuPhotoPipeline.PreparationStep.CONTINUE;
        }
        if(actorIterator!=null) {
            for(int n=0;n<Math.min(operations,8) && System.nanoTime()<deadline;n++){if(!actorIterator.hasNext()){actorIterator=null;break;}builder.actor(actorIterator.next(),info.hasSkyLight?info.skyDarken:15);livingActorCount++;}
            return CpuPhotoPipeline.PreparationStep.CONTINUE;
        }
        if(building!=null) {
            if(!building.step(operations,deadline,System::nanoTime))return CpuPhotoPipeline.PreparationStep.CONTINUE;
            Map<String,String> metadata=new HashMap<>();
            metadata.putAll(actorMetadata);metadata.put("actorSkinTextures",Long.toString(building.result().actorSkinTextures()));metadata.put("livingActors",Integer.toString(livingActorCount));metadata.put("world",info.id.toString());metadata.put("visualEpoch",Long.toString(visual.epoch()));
            metadata.put("captureStartedNanos",Long.toString(captureStart==Long.MAX_VALUE?0:captureStart));metadata.put("captureCompletedNanos",Long.toString(captureEnd));
            metadata.put("scannedCells",Long.toString(scanned));metadata.put("candidateCells",Long.toString(candidateCells));metadata.put("skippedCells",Long.toString(skippedCells));
            metadata.put("culledHosts",Long.toString(culled));metadata.put("occludedHosts",Long.toString(occludedHosts));metadata.put("skippedBlockSnapshots",Integer.toString(occludedChunks.size()));metadata.put("entities","LEGACY_ENTITY_APPROXIMATION:"+entityCount);
            if(occlusion!=null){metadata.put("occlusionVisits",Long.toString(occlusion.progress().visits()));metadata.put("occlusionAdvanced",Long.toString(occlusion.progress().advanced()));}
            metadata.put("captureWaitingNanos",Long.toString(waitingNanos));metadata.put("captureDispatchNanos",Long.toString(dispatchNanos));metadata.put("captureLoadNanos",Long.toString(loadingNanos));metadata.put("captureOwnerQueueNanos",Long.toString(ownerQueueNanos));metadata.put("captureCopyNanos",Long.toString(copyNanos));metadata.put("captureCopyCpuNanos",Long.toString(copyCpuNanos));metadata.put("captureBudgetRetries",Integer.toString(captureRetries));metadata.put("rawSnapshotPeakBytes",Long.toString(rawPeak));metadata.put("captureWindowChunks",Integer.toString(captureWindow));metadata.put("localInterior",Long.toString(localInterior));metadata.put("scanGapRuns",Long.toString(gapRuns));
            metadata.put("scanCpuNanos",Long.toString(scanCpuNanos));metadata.put("scanWorkWallNanos",Long.toString(scanWallNanos));metadata.put("scanStageWallNanos",Long.toString(scanCompleted-scanStarted));metadata.put("scanSlices",Long.toString(scanSlices));metadata.put("fastInterior",Long.toString(fastInterior));metadata.put("scanMeasurement",scanWallFallback?"WALL_FALLBACK":"THREAD_CPU");
            metadata.put("frozenPeakBytes",Long.toString(frozenPeak));metadata.put("lodGroups",Long.toString(lodGroups));metadata.put("lodHiddenGroups",Long.toString(lodHidden));metadata.put("capturedChunks",Integer.toString(completedChunks));metadata.put("indexBytes",Long.toString(building.result().indexBytes()));metadata.put("sceneInstances",Integer.toString(building.result().instances().size()));metadata.put("indexSections",Integer.toString(building.result().indexSections()));
            var mesh=building.progress();metadata.put("fluidCount",Long.toString(mesh.fluids()));metadata.put("fluidTriangles",Long.toString(mesh.triangles()));metadata.put("fluidRetainedBytes",Long.toString(mesh.retainedBytes()));
            if(memory!=null){metadata.put("workspacePeak",Long.toString(memory.peak()));metadata.put("preparationWorkspacePeak",Long.toString(memory.peak()));}
            result=new CpuPhotoPipeline.Prepared(building.result(),camera,settings,palette,lease,metadata,memory);actorResources=null;
            frozen.values().forEach(FrozenChunk::close);frozen.clear();states.clear();builder=null;building=null;
            return CpuPhotoPipeline.PreparationStep.DONE;
        }
        long wall=System.nanoTime(),cpu=scanCpu();if(scanStarted==0)scanStarted=wall;scanSlices++;
        try {
        for(int work=0;work<operations && y<=y1 && System.nanoTime()<deadline;work++) {
            int cx=x>>4,cy=y>>4,cz=z>>4;
            if(cx!=segmentX || cy!=segmentY || cz!=segmentZ) {
                segmentX=cx;segmentY=cy;segmentZ=cz;segmentCandidate=candidateBits.get(candidateIndex(cx,cy,cz));
                segmentOccluded=segmentCandidate && occludedChunks.contains(new ChunkCaptureService.Coordinate(cx,cz));segmentChunk=segmentCandidate && !segmentOccluded?chunk(x,z):null;
            }
            if(!segmentCandidate) {
                int row=candidateIndex(candidateMinX,cy,cz);int next=candidateBits.nextSetBit(row+cx-candidateMinX);int end=next<0 || next>=row+candidateWidth?x1:Math.min(x1,(candidateMinX+next-row)*16-1);skippedCells+=end-x+1L;x=end;gapRuns++;
                if(++x>x1){x=x0;if(++z>z1){z=z0;y++;}}continue;
            }
            if(segmentOccluded) {
                int end=Math.min(x1,x|15);skippedCells+=end-x+1L;x=end;if(++x>x1){x=x0;if(++z>z1){z=z0;y++;}}continue;
            }
            FrozenChunk chunk=segmentChunk;int sx=Math.floorMod(x,16),sz=Math.floorMod(z,16);
            if(chunk.empty(y)) {
                int end=Math.min(x1,(x|15));scanned+=end-x+1;x=end;
            } else {
                State state=frustum.mayIntersect(x-(double)modelHalo,y-(double)modelHalo,z-(double)modelHalo,x+1.0+modelHalo,y+1.0+modelHalo,z+1.0+modelHalo)?state(x,y,z):null;
                if(state==null)culled++;
                boolean buried=state!=null && sameInterior(x,y,z,state);if(buried)fastInterior++;
                if(state!=null && !buried && visible(x,y,z,state) && !interior(x,y,z,state)) {
                    visual.validateResources(state.resolved,lease.generation(),x,y,z,allowedVanilla);
                    var descriptor=state.resolved.descriptor();
                    boolean grouped=farLod!=null && farLod.emit(x,y,z,this::lodHost,(ax,ay,az)->frustum.mayIntersect(ax,ay,az,ax+2.0,ay+2.0,az+2.0) && (occlusion==null || !occlusion.hidden(ax-1e-6,ay-1e-6,az-1e-6,ax+2.000001,ay+2.000001,az+2.000001)),builder);
                    if(!grouped){
                        builder.put(x,y,z,descriptor,withFaceLighting(x,y,z,chunk.light(sx,y,sz)),info.seed);
                        // 流体角点/流向需要对角邻居；其它模型的cullface需要六邻域。
                        if(FluidMesher.type(descriptor.visualState())!=null) {
                            for(int dx=-1;dx<=1;dx++)for(int dz=-1;dz<=1;dz++)for(int dy=-1;dy<=1;dy++)neighbor(x+dx,y+dy,z+dz);
                        } else {
                            neighbor(x-1,y,z);neighbor(x+1,y,z);neighbor(x,y-1,z);neighbor(x,y+1,z);neighbor(x,y,z-1);neighbor(x,y,z+1);
                        }
                    }
                }
                scanned++;
            }
            if(scanned>limits.maximumScannedCells)throw new IllegalStateException("场景扫描超过preparation.maximumScannedCells：实际="+scanned+"，上限="+limits.maximumScannedCells);
            if(++x>x1){x=x0;if(++z>z1){z=z0;y++;}}
        }
        }finally{long end=scanCpu();scanWallNanos+=Math.max(0,System.nanoTime()-wall);if(cpu>=0 && end>=cpu)scanCpuNanos+=end-cpu;else{scanWallFallback=true;scanCpuNanos+=Math.max(0,System.nanoTime()-wall);}}
        if(y>y1) {
            scanCompleted=System.nanoTime();Arrays.fill(lookupValid,false);Arrays.fill(lookupValues,null);segmentChunk=null;
            // 流体/剔面/光照需要的邻域已复制到builder，紧凑世界的最后消费者已退出。
            frozen.values().forEach(FrozenChunk::close);frozen.clear();
            if(farLod!=null){lodGroups=farLod.groups();lodHidden=farLod.hiddenGroups();farLod.close();farLod=null;}
            building=builder.finish();
        }
        return CpuPhotoPipeline.PreparationStep.CONTINUE;
    }
    private int candidateIndex(int sx,int sy,int sz){return ((sy-candidateMinY)*candidateDepth+sz-candidateMinZ)*candidateWidth+sx-candidateMinX;}
    private void consumeCurrent() {
        if(captureWindow>1)request.consume(convertingChunk);convertingChunk=null;completedChunks++;
        if(captureBatch.isEmpty()) {
            var t=request.timings();dispatchNanos+=t.dispatchWaitNanos();loadingNanos+=t.loadingNanos();ownerQueueNanos+=t.ownerQueueNanos();copyNanos+=t.copyWallNanos();copyCpuNanos+=t.copyCpuNanos();captureRetries+=t.budgetRetries();
            request.close();request=null;metadataOnly=false;
        }
    }
    private FarLod.Host lodHost(int x,int y,int z) {
        if(x<x0 || x>x1 || y<y0 || y>y1 || z<z0 || z>z1)return null;
        var frozenChunk=frozen.get(new ChunkCaptureService.Coordinate(x>>4,z>>4));if(frozenChunk==null || !frozenChunk.available(y))return null;
        for(var direction:com.wenkrang.famara.render.math.Direction.values()) {
            var offset=direction.vector();int nx=x+(int)offset.x(),ny=y+(int)offset.y(),nz=z+(int)offset.z();
            if(ny>=info.minHeight && ny<info.maxHeight){var neighbor=frozen.get(new ChunkCaptureService.Coordinate(nx>>4,nz>>4));if(neighbor==null || !neighbor.available(ny))return null;}
        }
        State state=state(x,y,z);if(state==null || !state.solid || !state.confined || state.resolved.descriptor().kind()!=VisualBlockDescriptor.Kind.VANILLA)return null;
        var descriptor=state.resolved.descriptor();var models=lease.generation().resolve(descriptor,x,y,z,info.seed);
        return new FarLod.Host(descriptor,models,withFaceLighting(x,y,z,frozenChunk.light(x&15,y,z&15)));
    }
    private void closeExtras(){if(streamingExtras instanceof AutoCloseable owned)try{owned.close();}catch(Exception error){throw new IllegalStateException(error);}streamingExtras=null;}
    private boolean visible(int x,int y,int z,State state) {
        var bounds=state.bounds;boolean visible=!bounds.empty() && frustum.mayIntersect(x+bounds.min().x(),y+bounds.min().y(),z+bounds.min().z(),x+bounds.max().x(),y+bounds.max().y(),z+bounds.max().z());
        if(!visible){culled++;return false;}
        if(occlusion!=null && occlusion.hidden(x+bounds.min().x()-1e-6,y+bounds.min().y()-1e-6,z+bounds.min().z()-1e-6,x+bounds.max().x()+1e-6,y+bounds.max().y()+1e-6,z+bounds.max().z()+1e-6)){occludedHosts++;return false;}
        return true;
    }
    private int occluder(int x,int y,int z) {
        if(y<info.minHeight || y>=info.maxHeight)return 0;
        if(findChunk(x,z)==null)return -1;
        State value=state(x,y,z);return value!=null && value.solid && value.confined?1:0;
    }
    private boolean canSkip(ChunkCaptureService.Coordinate coordinate) {
        if(occlusion==null)return false;
        for(int dx=-1;dx<=1;dx++)for(int dz=-1;dz<=1;dz++) {
            var neighbor=new ChunkCaptureService.Coordinate(coordinate.x()+dx,coordinate.z()+dz);
            if(frozen.containsKey(neighbor) && !chunkHidden(neighbor))return false;
        }
        return chunkHidden(coordinate);
    }
    private boolean chunkHidden(ChunkCaptureService.Coordinate coordinate) {
        double halo=modelHalo+2;
        return occlusion.hidden(coordinate.x()*16.0-halo,y0-1.0,coordinate.z()*16.0-halo,(coordinate.x()+1)*16.0+halo,y1+2.0,(coordinate.z()+1)*16.0+halo);
    }
    private SceneSnapshot.Lighting withFaceLighting(int x,int y,int z,SceneSnapshot.Lighting base) {
        List<SceneSnapshot.FaceLight> faces=new ArrayList<>();
        for(var direction:com.wenkrang.famara.render.math.Direction.values()) {
            var offset=direction.vector();int nx=x+(int)offset.x(),ny=y+(int)offset.y(),nz=z+(int)offset.z();
            int sky=base.sky(),block=base.block();
            if(ny<info.minHeight)sky=0;else if(ny>=info.maxHeight)sky=info.hasSkyLight?15:0;
            else {var neighbor=chunk(nx,nz).light(nx&15,ny,nz&15);sky=Math.max(sky,neighbor.sky());block=Math.max(block,neighbor.block());}
            faces.add(new SceneSnapshot.FaceLight(info.hasSkyLight?Math.max(0,sky-info.skyDarken):0,block));
        }
        return new SceneSnapshot.Lighting(info.hasSkyLight?Math.max(0,base.sky()-info.skyDarken):0,base.block(),base.tintRgb(),base.waterTintRgb(),faces);
    }
    public static int sectionIndex(int y,int minimumHeight){return Math.floorDiv(y-minimumHeight,16);}
    private boolean interior(int x,int y,int z,State current) {
        if(!current.solid || !current.confined)return false;
        return solid(x-1,y,z) && solid(x+1,y,z) && solid(x,y-1,z) && solid(x,y+1,z) && solid(x,y,z-1) && solid(x,y,z+1);
    }
    private boolean solid(int x,int y,int z){State state=state(x,y,z);return state!=null && state.solid;}
    private void neighbor(int x,int y,int z) {
        State state=state(x,y,z);builder.neighbor(x,y,z,state==null?AIR:state.resolved.descriptor().visualState(),state!=null && state.solid);
    }
    private State state(int x,int y,int z) {
        if(y<info.minHeight || y>=info.maxHeight)return null;
        FrozenChunk chunk=chunk(x,z);
        CanonicalBlockState server=chunk.stateOrNull(x&15,y,z&15);if(server==null)return null;
        State cached=states.get(server);if(cached!=null)return cached==EMPTY?null:cached;
        if(memory!=null)memory.claim(1024L+server.toString().length()*4L);
        // 先查CE映射再识别空气；不能用Material.isAir吞掉自定义服务端注册类型。
        VisualStateTable.Resolved resolved=visual.resolve(server,lease.generation(),version);
        String kind=resolved.descriptor().visualState().block().toString();
        if(resolved.descriptor().kind()==VisualBlockDescriptor.Kind.VANILLA && Set.of("minecraft:air","minecraft:cave_air","minecraft:void_air").contains(kind)){if(states.size()>=limits.maximumStates)throw new IllegalStateException("场景状态缓存超过限额");states.put(server,EMPTY);return null;}
        boolean solid=lease.generation().guaranteedOpaque(resolved.descriptor(),models->OpaqueShapes.fullCube(models,lease.generation()));
        if(states.size()>=limits.maximumStates)throw new IllegalStateException("场景状态缓存超过限额");
        State value=new State(resolved,solid,lease.generation().confinedToHost(resolved.descriptor()),lease.generation().bounds(resolved.descriptor()),server);states.put(server,value);return value;
    }
    private boolean sameInterior(int x,int y,int z,State current) {
        if(!current.solid || !current.confined)return false;
        FrozenChunk local=findChunk(x,z);if(local!=null && local.sameInteriorLocal(x&15,y,z&15,current.server)){localInterior++;return true;}
        return sameKnown(x-1,y,z,current.server) && sameKnown(x+1,y,z,current.server) && sameKnown(x,y-1,z,current.server) && sameKnown(x,y+1,z,current.server) && sameKnown(x,y,z-1,current.server) && sameKnown(x,y,z+1,current.server);
    }
    private boolean sameKnown(int x,int y,int z,CanonicalBlockState expected) {
        if(y<info.minHeight || y>=info.maxHeight)return false;FrozenChunk chunk=findChunk(x,z);return chunk!=null && chunk.matches(x&15,y,z&15,expected);
    }
    private FrozenChunk findChunk(int x,int z) {
        int cx=x>>4,cz=z>>4,mixed=cx*0x9E3779B9+cz*0x85EBCA6B;mixed^=mixed>>>16;int slot=mixed&63;
        long key=((long)cx<<32)^(cz&0xffffffffL);
        if(lookupValid[slot] && lookupKeys[slot]==key)return lookupValues[slot];
        FrozenChunk value=frozen.get(new ChunkCaptureService.Coordinate(cx,cz));
        lookupKeys[slot]=key;lookupValues[slot]=value;lookupValid[slot]=true;return value;
    }
    private FrozenChunk chunk(int x,int z) {
        FrozenChunk value=findChunk(x,z);if(value==null)throw new SceneException(SceneException.Code.SCENE_INCOMPLETE,"没有捕获所需区块："+(x>>4)+","+(z>>4));return value;
    }
    @Override public CpuPhotoPipeline.PreparationProgress progress() {
        if(result!=null)return new CpuPhotoPipeline.PreparationProgress("场景已就绪",completedChunks,totalChunks,"");
        String detail="区块 "+completedChunks+"/"+totalChunks;
        if(!capturedAll) {
            if(updatingOcclusion){var p=occlusion.progress();return new CpuPhotoPipeline.PreparationProgress("增量遮挡",p.processed(),p.samples(),detail+"，已终止样本 "+p.completed());}
            if(converting!=null)return new CpuPhotoPipeline.PreparationProgress("冻结转换",completedChunks,totalChunks,converting.progress());
            if(request!=null)return new CpuPhotoPipeline.PreparationProgress("等待区块",completedChunks,totalChunks,request.progress());
            return new CpuPhotoPipeline.PreparationProgress("选择区块",completedChunks,totalChunks,"");
        }
        if(sectionChunks!=null)return new CpuPhotoPipeline.PreparationProgress("登记section",completedChunks,totalChunks,"");
        if(addingEntities)return new CpuPhotoPipeline.PreparationProgress("实体元数据",entityCount,0,"");
        if(building!=null){var p=building.progress();return new CpuPhotoPipeline.PreparationProgress(p.phase(),p.completed(),p.total(),"流体 "+p.fluids()+"，三角形 "+p.triangles()+"，动态字节 "+p.retainedBytes());}
        return new CpuPhotoPipeline.PreparationProgress("扫描可见宿主",scanned,candidateCells,"");
    }
    @Override public CpuPhotoPipeline.Prepared result(){if(result==null)throw new IllegalStateException("世界场景尚未准备完毕");lease=null;memory=null;return result;}
    private static Throwable closeResource(Throwable failure,AutoCloseable resource) {
        if(resource==null)return failure;
        try{resource.close();}catch(Throwable error){if(failure==null)failure=error;else if(failure!=error)failure.addSuppressed(error);}return failure;
    }
    @Override public void close() {
        if(closed)return;closed=true;lookupKeys=null;lookupValid=null;lookupValues=null;segmentChunk=null;Throwable failure=null;
        failure=closeResource(failure,actorResources);actorResources=null;actorIterator=null;actorsReady=null;
        failure=closeResource(failure,tableMemory);tableMemory=null;world=null;
        if(extras instanceof AutoCloseable owned)failure=closeResource(failure,owned);extras=null;entityIterator=null;
        failure=closeResource(failure,farLod);farLod=null;
        failure=closeResource(failure,converting);converting=null;failure=closeResource(failure,request);request=null;
        for(var chunk:frozen.values())failure=closeResource(failure,chunk);frozen.clear();pendingChunks=null;
        if(streamingExtras instanceof AutoCloseable owned)failure=closeResource(failure,owned);streamingExtras=null;
        snapshots=null;captureBatch.clear();deferredChunk=null;candidateBits.clear();metadataEntities.clear();occludedChunks.clear();occlusion=null;sectionChunks=null;sectionChunk=null;states.clear();builder=null;building=null;
        failure=closeResource(failure,lease);lease=null;failure=closeResource(failure,memory);memory=null;
        if(failure instanceof Error fatal)throw fatal;if(failure!=null)throw new IllegalStateException("场景准备清理异常，所有owner均已尝试释放",failure);
    }
}
