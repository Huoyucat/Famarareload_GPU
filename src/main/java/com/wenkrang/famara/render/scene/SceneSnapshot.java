package com.wenkrang.famara.render.scene;

import com.wenkrang.famara.render.asset.*;
import com.wenkrang.famara.render.math.Vec3;

import java.util.*;
import java.nio.IntBuffer;

/**
 * 已知 section 与空气分开；几何实例共享模型，不为每格复制网格。
 */
public final class SceneSnapshot implements AutoCloseable {
    public record Cell(int x, int y, int z) {
    }

    public record FaceLight(int sky,int block) {
        public FaceLight{if(sky<0 || sky>15 || block<0 || block>15)throw new IllegalArgumentException("面光照无效");}
    }
    public record Lighting(int sky,int block,int tintRgb,int waterTintRgb,List<FaceLight> faces) {
        public Lighting(int sky,int block,int tintRgb){this(sky,block,tintRgb,tintRgb);}
        public Lighting(int sky,int block,int tintRgb,int waterTintRgb){this(sky,block,tintRgb,waterTintRgb,List.of());}
        public Lighting {
            if(sky<0 || sky>15 || block<0 || block>15 || (tintRgb&0xff000000)!=0 || (waterTintRgb&0xff000000)!=0)
                throw new IllegalArgumentException("场景光照/染色不合法");
            faces=List.copyOf(faces);if(!faces.isEmpty() && faces.size()!=6)throw new IllegalArgumentException("面光照需要六个方向");
        }
        public FaceLight face(com.wenkrang.famara.render.math.Direction face){return faces.isEmpty()?new FaceLight(sky,block):faces.get(face.ordinal());}
    }

    public record FarColors(List<Texture.Sample> faces) {
        public FarColors{faces=List.copyOf(faces);if(faces.size()!=6 || faces.stream().anyMatch(c->c.a()!=1 || !Double.isFinite(c.r()) || !Double.isFinite(c.g()) || !Double.isFinite(c.b()) || c.r()<0 || c.r()>1 || c.g()<0 || c.g()>1 || c.b()<0 || c.b()>1))throw new IllegalArgumentException("LOD面色无效");}
        public Texture.Sample face(com.wenkrang.famara.render.math.Direction direction){return faces.get(direction.ordinal());}
    }
    public record Instance(int id, int x, int y, int z, VisualBlockDescriptor descriptor,
                           List<BakedModel> models, Lighting lighting,FarColors farColors) {
        public Instance(int id,int x,int y,int z,VisualBlockDescriptor descriptor,List<BakedModel> models,Lighting lighting){this(id,x,y,z,descriptor,models,lighting,null);}
        public Instance {
            models = List.copyOf(models);
        }
    }

    private final Set<Cell> occludedChunks;
    private final AssetGeneration assets;
    private final int minHeight, maxHeight,worldMinHeight,worldMaxHeight;
    private final Set<Cell> sections;
    private record IndexSection(int[] head,int[] next,int[] ids) {}
    private final Map<Cell,IndexSection> grid;
    private final List<Instance> instances;
    private final List<LegacyEntity> entities;
    private final long meshBytes;
    private final Map<ResourceKey,Texture> actorTextures;
    private final int actorCount;
    private final AutoCloseable actorResources;
    private final java.util.concurrent.atomic.AtomicBoolean actorsReleased=new java.util.concurrent.atomic.AtomicBoolean();
    private final Map<Cell,FluidMesher.Cell> neighbors;

    private SceneSnapshot(Builder b,Map<Cell,IndexSection> grid,int lower,int upper) {
        occludedChunks=Set.copyOf(b.occludedChunks);assets=b.assets;entities=List.copyOf(b.entities);actorTextures=Map.copyOf(b.actorTextures);actorCount=b.actorCount;actorResources=b.actorResources;
        worldMinHeight=b.minHeight;worldMaxHeight=b.maxHeight;minHeight=lower;maxHeight=upper;
        sections=Set.copyOf(b.sections);neighbors=Map.copyOf(b.neighbors);instances=List.copyOf(b.instances);this.grid=Map.copyOf(grid);
        Set<BakedModel> meshes=Collections.newSetFromMap(new IdentityHashMap<>());
        instances.forEach(i->meshes.addAll(i.models));meshBytes=meshes.stream().mapToLong(m->m.quads().size()*512L).sum();
    }
    public long indexBytes(){return grid.values().stream().mapToLong(s->128L+4L*(s.head.length+s.next.length+s.ids.length)).sum();}
    public int indexSections(){return grid.size();}
    public record SpatialIndexLayout(int sections,int nodes,int knownSections,int occludedChunks) {}
    public int worldMinHeight(){return worldMinHeight;}
    public int worldMaxHeight(){return worldMaxHeight;}
    private static int used(IndexSection section){int count=0;for(int head:section.head)count=Math.max(count,head+1);return count;}
    public SpatialIndexLayout spatialIndexLayout() {
        long nodes=0;for(var section:grid.values())nodes=Math.addExact(nodes,used(section));
        return new SpatialIndexLayout(grid.size(),Math.toIntExact(nodes),sections.size(),occludedChunks.size());
    }
    private static final Comparator<Cell> CELL_ORDER=Comparator.comparingInt(Cell::x).thenComparingInt(Cell::y).thenComparingInt(Cell::z);
    /** Copies compact links into caller-owned storage; the CPU index is never exposed or changed.
     * Section rows: x,y,z,head offset,node offset,node count. Node rows: next-global,id.
     * Known/occluded rows: x,y,z. All section tables are lexicographically sorted. */
    public void copySpatialIndex(IntBuffer sectionRows,IntBuffer heads,IntBuffer nodes,IntBuffer known,IntBuffer occluded) {
        var export=spatialIndexExport(sectionRows,heads,nodes,known,occluded);while(!export.step(16384,Long.MAX_VALUE,System::nanoTime)){}
    }
    public SpatialIndexExport spatialIndexExport(IntBuffer sectionRows,IntBuffer heads,IntBuffer nodes,IntBuffer known,IntBuffer occluded) {
        return new SpatialIndexExport(sectionRows,heads,nodes,known,occluded);
    }
    public final class SpatialIndexExport {
        private final IntBuffer rows,heads,nodes,known,hidden;
        private final List<Cell> keys,available,occluded;
        private int section,head,node,base,count,knownIndex,hiddenIndex;
        private boolean opened;
        private SpatialIndexExport(IntBuffer sectionRows,IntBuffer heads,IntBuffer nodes,IntBuffer known,IntBuffer occluded) {
        var layout=spatialIndexLayout();
        if(sectionRows.remaining()<layout.sections*6L || heads.remaining()<layout.sections*4096L || nodes.remaining()<layout.nodes*2L
                || known.remaining()<layout.knownSections*3L || occluded.remaining()<layout.occludedChunks*3L)
            throw new IllegalArgumentException("Insufficient spatial index export storage");
            rows=sectionRows;this.heads=heads;this.nodes=nodes;this.known=known;hidden=occluded;
            keys=new ArrayList<>(grid.keySet());keys.sort(CELL_ORDER);
            available=new ArrayList<>(sections);available.sort(CELL_ORDER);
            this.occluded=new ArrayList<>(occludedChunks);this.occluded.sort(CELL_ORDER);
        }
        public boolean step(int operations,long deadline,java.util.function.LongSupplier clock) {
            if(operations<=0)throw new IllegalArgumentException("Positive export quantum required");
            for(int op=0;op<operations && clock.getAsLong()<deadline;op++) {
                if(section<keys.size()) {
                    Cell key=keys.get(section);IndexSection data=grid.get(key);
                    if(!opened){count=used(data);rows.put(key.x).put(key.y).put(key.z).put(heads.position()).put(base).put(count);opened=true;}
                    if(head<4096){int end=Math.min(4096,head+128);while(head<end){int value=data.head[head++];heads.put(value<0?-1:base+value);}}
                    else if(node<count){int end=Math.min(count,node+128);while(node<end){nodes.put(data.next[node]<0?-1:base+data.next[node]).put(data.ids[node]);node++;}}
                    else{base=Math.addExact(base,count);section++;head=0;node=0;opened=false;}
                }else if(knownIndex<available.size()){Cell c=available.get(knownIndex++);known.put(c.x).put(c.y).put(c.z);}
                else if(hiddenIndex<occluded.size()){Cell c=occluded.get(hiddenIndex++);hidden.put(c.x).put(c.y).put(c.z);}
                else return true;
            }
            return section==keys.size() && knownIndex==available.size() && hiddenIndex==occluded.size();
        }
    }

    public Texture texture(ResourceKey key){Texture image=actorTextures.get(key);return image==null?assets.texture(key):image;}
    public int actorCount(){return actorCount;}
    public long actorSkinTextures(){return actorTextures.keySet().stream().filter(key->key.namespace().equals("famara") && key.path().startsWith("skin/")).count();}
    @Override public void close(){if(actorResources!=null && actorsReleased.compareAndSet(false,true))try{actorResources.close();}catch(RuntimeException error){throw error;}catch(Exception error){throw new IllegalStateException("Actor resources failed to close",error);}}

    public AssetGeneration assets() {
        return assets;
    }

    public long estimatedBytes() {
        return actorTextures.values().stream().mapToLong(Texture::retainedBytes).sum()+128L*neighbors.size()+meshBytes+64L*sections.size()+512L*instances.size()
                +grid.values().stream().mapToLong(s->128L+4L*(s.head.length+s.next.length+s.ids.length)).sum();
    }
    /** 每条渲染线程私有的primitive索引游标，不为每格创建Cell/List。 */
    public final class IndexCursor {
        int sx=Integer.MIN_VALUE,sy=Integer.MIN_VALUE,sz=Integer.MIN_VALUE,node=-1;
        IndexSection section;
        public void enter(int x,int y,int z) {
            node=-1;if(y<minHeight || y>=maxHeight)return;
            int cx=x>>4,cy=y>>4,cz=z>>4;
            if(cx!=sx || cy!=sy || cz!=sz) {
                sx=cx;sy=cy;sz=cz;
                int hostY=Math.max(worldMinHeight,Math.min(worldMaxHeight-1,y));
                if(occludedChunks.contains(new Cell(cx,0,cz)))throw new SceneException(SceneException.Code.SCENE_INCOMPLETE,"射线意外触及未捕获的遮挡区块");
                if(!sections.contains(new Cell(cx,hostY>>4,cz)))throw new SceneException(SceneException.Code.SCENE_INCOMPLETE,"没有捕获section："+cx+","+cy+","+cz);
                section=grid.get(new Cell(cx,cy,cz));
            }
            if(section!=null)node=section.head[((y&15)<<8)|((z&15)<<4)|(x&15)];
        }
        public boolean hasNext(){return node>=0;}
        public Instance next(){int id=section.ids[node];node=section.next[node];return instances.get(id);}
    }
    public IndexCursor indexCursor(){return new IndexCursor();}

    public int minHeight() {
        return minHeight;
    }

    public int maxHeight() {
        return maxHeight;
    }

    public boolean shouldCull(Instance instance,BakedQuad quad) {
        if(quad.cullFace()==null)return false;
        Vec3 direction=quad.cullFace().vector();
        FluidMesher.Cell neighbor=neighbors.get(new Cell(instance.x+(int)direction.x(),instance.y+(int)direction.y(),instance.z+(int)direction.z()));
        if(neighbor==null)return false;
        if(neighbor.solid())return true;
        var key=instance.descriptor.visualState().block();String name=key.path();
        boolean joined=name.equals("glass") || name.endsWith("_glass") || name.endsWith("_glass_pane") || name.equals("glass_pane")
                || name.equals("ice") || name.equals("slime_block") || name.equals("honey_block");
        return joined && key.equals(neighbor.state().block());
    }

    public List<LegacyEntity> entities(){return entities;}
    public List<Instance> instances() {
        return instances;
    }

    public List<Instance> at(int x, int y, int z) {
        if (y < minHeight || y >= maxHeight) return List.of();
        if(occludedChunks.contains(new Cell(x>>4,0,z>>4)))throw new SceneException(SceneException.Code.SCENE_INCOMPLETE,"射线意外触及有遮挡证据但未捕获的区块，未当空气");
        int hostY=Math.max(worldMinHeight,Math.min(worldMaxHeight-1,y));
        if (!sections.contains(new Cell(x >> 4, hostY >> 4, z >> 4)))
            throw new SceneException(SceneException.Code.SCENE_INCOMPLETE, "没有捕获section：" + (x >> 4) + "," + (y >> 4) + "," + (z >> 4));
        IndexSection section=grid.get(new Cell(x>>4,y>>4,z>>4));if(section==null)return List.of();
        List<Instance> found=new ArrayList<>();int node=section.head[((y&15)<<8)|((z&15)<<4)|(x&15)];
        while(node>=0){found.add(instances.get(section.ids[node]));node=section.next[node];}return List.copyOf(found);
    }

    public static final class Builder {
        private final Set<Cell> occludedChunks=new HashSet<>();
        public Builder occludedChunk(int x,int z){checkOpen();occludedChunks.add(new Cell(x,0,z));return this;}
        private final AssetGeneration assets;
        private final int minHeight, maxHeight, maxInstances, maxIndexEntries;
        private final Set<Cell> sections = new HashSet<>();
        private final List<Instance> instances = new ArrayList<>();
        private final List<LegacyEntity> entities=new ArrayList<>();
        private int entityBoxes,actorCount;
        private final Map<ResourceKey,Texture> actorTextures=new HashMap<>();
        private AutoCloseable actorResources;
        public Builder actorResources(AutoCloseable resources){checkOpen();if(actorResources!=null)throw new IllegalStateException("Actor resources already assigned");actorResources=Objects.requireNonNull(resources);return this;}
        public Builder actor(ActorGeometry actor){return actor(actor,0);}
        public Builder actor(ActorGeometry actor,int skyDarken) {
            if(skyDarken<0 || skyDarken>15)throw new IllegalArgumentException("Actor sky darkening outside 0..15");
            checkOpen();if(instances.size()>=maxInstances)throw limit("Actor instances",instances.size()+1L,maxInstances);charge(2048);
            for(var entry:actor.textures().entrySet()){Texture previous=actorTextures.putIfAbsent(entry.getKey(),entry.getValue());if(previous!=null && previous!=entry.getValue())throw new IllegalArgumentException("Actor texture identity conflict");}
            var descriptor=VisualBlockDescriptor.vanilla(CanonicalBlockState.parse("famara:living_actor"),assets.id());
            var a=actor.actor();instances.add(new Instance(instances.size(),actor.x(),actor.y(),actor.z(),descriptor,List.of(actor.model()),new Lighting(Math.max(0,a.sky()-skyDarken),a.block(),0xffffff)));actorCount++;return this;
        }
        private final Set<Cell> occupied = new HashSet<>();
        private final Map<Cell,FluidMesher.Cell> neighbors=new HashMap<>();
        private boolean sealed;
        private com.wenkrang.famara.render.schedule.MemoryScope memory;
        public Builder memory(com.wenkrang.famara.render.schedule.MemoryScope memory){checkOpen();this.memory=memory;return this;}
        private void charge(long bytes){if(memory!=null)memory.claim(bytes);}

        public Builder(AssetGeneration assets, int minHeight, int maxHeight, int maxInstances, int maxIndexEntries) {
            if (maxHeight <= minHeight || maxInstances <= 0 || maxIndexEntries <= 0)
                throw new IllegalArgumentException("场景限制不合法");
            this.assets = Objects.requireNonNull(assets);
            this.minHeight = minHeight;
            this.maxHeight = maxHeight;
            this.maxInstances = maxInstances;
            this.maxIndexEntries = maxIndexEntries;
        }

        public Builder entity(LegacyEntity entity) {
            checkOpen();if(entity.boxes().size()>maxIndexEntries-entityBoxes)throw limit("实体盒",(long)entityBoxes+entity.boxes().size(),maxIndexEntries);charge(256L+entity.boxes().size()*128L);entityBoxes+=entity.boxes().size();entities.add(entity);return this;
        }
        public Builder availableSection(int x, int y, int z) {
            checkOpen();
            if(sections.size()>=maxIndexEntries && !sections.contains(new Cell(x,y,z)))throw limit("已捕获section",(long)sections.size()+1,maxIndexEntries);
            if(!sections.contains(new Cell(x,y,z)))charge(128);sections.add(new Cell(x, y, z));
            return this;
        }

        public Builder put(int x, int y, int z, VisualBlockDescriptor visual, Lighting lighting, long seed) {
            checkOpen();
            if(y<minHeight || y>=maxHeight)throw new SceneException(SceneException.Code.GEOMETRY_LIMIT,"宿主高度越界："+y);
            if(instances.size()>=maxInstances)throw limit("方块实例/preparation.maximumInstances",(long)instances.size()+1,maxInstances);
            charge(1536);
            if (!occupied.add(new Cell(x, y, z))) throw new IllegalArgumentException("同一方块重复添加");
            assets.validateReference(visual);
            String kind=FluidMesher.type(visual.visualState());
            boolean onlyFluid=visual.visualState().block().namespace().equals("minecraft")
                    && (visual.visualState().block().path().equals("water") || visual.visualState().block().path().equals("lava"));
            List<BakedModel> models=onlyFluid?List.of():assets.resolve(visual,x,y,z,seed);
            neighbors.put(new Cell(x,y,z),new FluidMesher.Cell(visual.visualState(),kind==null && OpaqueShapes.fullCube(models,assets)));
            instances.add(new Instance(instances.size(), x, y, z, visual, models, Objects.requireNonNull(lighting)));
            return this;
        }

        /** 来源已证明为8个静态不透明普通满块；proxy颜色与几何所有权在此封存。 */
        public Builder proxy(int x,int y,int z,VisualBlockDescriptor visual,BakedModel model,FarColors colors) {
            checkOpen();if(y<minHeight || y+2>maxHeight)throw limit("LOD高度",y,maxHeight);
            if(instances.size()>=maxInstances)throw limit("LOD/方块实例",instances.size()+1L,maxInstances);charge(2304);
            if(!occupied.add(new Cell(x,y,z)))throw new IllegalArgumentException("LOD宿主重复");assets.validateReference(visual);
            instances.add(new Instance(instances.size(),x,y,z,visual,List.of(model),new Lighting(15,0,0xffffff),Objects.requireNonNull(colors)));return this;
        }

        public Builder neighbor(int x,int y,int z,CanonicalBlockState state,boolean solid) {
            checkOpen();if(neighbors.size()>=maxIndexEntries && !neighbors.containsKey(new Cell(x,y,z)))throw limit("邻域状态/preparation.maximumIndexEntries",(long)neighbors.size()+1,maxIndexEntries);
            if(!neighbors.containsKey(new Cell(x,y,z)))charge(192);neighbors.put(new Cell(x,y,z),new FluidMesher.Cell(state,solid));return this;
        }

        public SceneSnapshot build() {
            BuildCursor cursor=finish();while(!cursor.step(1024,Long.MAX_VALUE,System::nanoTime)){}
            return cursor.result();
        }
        /** 封存后分批生成流体与外伸索引，避免一个Supplier独占worker完成整片场景。 */
        public BuildCursor finish() {checkOpen();sealed=true;return new BuildCursor();}
        public final class BuildCursor {
            private final Map<Cell,IndexSection> grid=new HashMap<>();
            private final Set<Cell> touched=new HashSet<>();
            private final java.util.concurrent.atomic.AtomicBoolean running=new java.util.concurrent.atomic.AtomicBoolean();
            private int fluidIndex,instanceIndex,modelIndex,x,y,z,x0,y0,z0,x1,y1,z1;
            private boolean indexing,failed;
            private int touchedCapacity,indexMin=minHeight,indexMax=maxHeight;
            private long references,fluidCount,fluidTriangles,fluidRetained;
            private final int totalInstances=instances.size();
            public record Progress(String phase,int completed,int total,long fluids,long triangles,long retainedBytes) {}
            public Progress progress(){return new Progress(fluidIndex<totalInstances?"流体网格生成":"空间索引构建",fluidIndex<totalInstances?fluidIndex:instanceIndex,totalInstances,fluidCount,fluidTriangles,fluidRetained);}
            private void fluid(Instance current,FluidMesher.View view) {
                long peak=FluidMemory.peak(current.models.size()),keep=0;charge(peak);
                try {keep=installFluid(current,view);fluidCount++;fluidRetained+=keep;}
                finally {if(memory!=null)memory.release(peak-keep);}
            }
            private long installFluid(Instance current,FluidMesher.View view) {
                BakedModel dynamic=FluidMesher.build(current.x,current.y,current.z,view,current.lighting.waterTintRgb);
                if(dynamic.quads().isEmpty())return 0;
                long retained=FluidMemory.retained(dynamic,current.models.size());
                if(retained>FluidMemory.peak(current.models.size()))throw new IllegalStateException("流体对象图超过构建预约");
                List<BakedModel> all=new ArrayList<>(current.models);all.add(dynamic);
                Instance replacement=new Instance(current.id,current.x,current.y,current.z,current.descriptor,all,current.lighting);
                instances.set(current.id,replacement);fluidTriangles+=dynamic.quads().size();return retained;
            }
            private SceneSnapshot result;
            public boolean step(int operations,long deadline,java.util.function.LongSupplier clock) {
                if(operations<=0)throw new IllegalArgumentException("场景构建步长必须为正");
                if(!running.compareAndSet(false,true))throw new IllegalStateException("场景构建不能重叠执行");
                try {
                    if(failed)throw new IllegalStateException("场景构建已失败，不能继续或取部分结果");
                    if(result!=null)return true;
                    for(int work=0;work<operations && clock.getAsLong()<deadline;work++) {
                        if(fluidIndex<instances.size()) {
                            Instance current=instances.get(fluidIndex);
                            if(FluidMesher.type(current.descriptor.visualState())!=null) {
                                FluidMesher.View fluids=(fx,fy,fz)->{
                                    if(fy<minHeight || fy>=maxHeight)return new FluidMesher.Cell(CanonicalBlockState.parse("minecraft:air"),false);
                                    if(!sections.contains(new Cell(fx>>4,fy>>4,fz>>4)))throw new SceneException(SceneException.Code.SCENE_INCOMPLETE,"流体邻域未捕获");
                                    FluidMesher.Cell neighbor=neighbors.get(new Cell(fx,fy,fz));
                                    if(neighbor==null)throw new SceneException(SceneException.Code.SCENE_INCOMPLETE,"流体邻居状态未冻结："+fx+","+fy+","+fz);
                                    return neighbor;
                                };
                                fluid(current,fluids);
                            }
                            fluidIndex++;continue;
                        }
                        if(instanceIndex>=instances.size()){
                            // 仅封存引用容器的重叠峰值，primitive索引数组已逐次在分配前收费。
                            charge(4L*1024*1024+grid.size()*256L+neighbors.size()*128L+sections.size()*128L+instances.size()*32L);
                            result=new SceneSnapshot(Builder.this,grid,indexMin,indexMax);
                            grid.clear();counts.clear();touched.clear();instances.clear();neighbors.clear();occupied.clear();entities.clear();sections.clear();return true;
                        }
                        Instance instance=instances.get(instanceIndex);
                        if(modelIndex>=instance.models.size()){instanceIndex++;modelIndex=0;touched.clear();continue;}
                        BakedModel model=instance.models.get(modelIndex);
                        if(!indexing) {
                            if(model.quads().isEmpty()){modelIndex++;continue;}
                            Vec3 min=model.min(),max=model.max();
                            x0=floor(instance.x+min.x());y0=floor(instance.y+min.y());z0=floor(instance.z+min.z());
                            x1=floor(instance.x+max.x());y1=floor(instance.y+max.y());z1=floor(instance.z+max.z());
                            long count;
                            try{count=Math.multiplyExact(Math.multiplyExact((long)x1-x0+1,(long)y1-y0+1),(long)z1-z0+1);}
                            catch(ArithmeticException overflow){throw limit();}
                            if(count<0 || count>maxIndexEntries)throw limit("单模型体素索引/preparation.maximumIndexEntries",count,maxIndexEntries);
                            x=x0;y=y0;z=z0;indexing=true;
                        }
                        Cell cell=new Cell(x,y,z);
                        if(!touched.contains(cell)) {
                            if(touched.size()>=touchedCapacity){int next=Math.min(maxIndexEntries,Math.max(16,touchedCapacity*2));charge((next-touchedCapacity)*192L);touchedCapacity=next;}
                            touched.add(cell);
                            if(++references>maxIndexEntries)throw limit("实例索引引用/preparation.maximumIndexEntries",references,maxIndexEntries);
                            int hostY=Math.max(minHeight,Math.min(maxHeight-1,y));
                            if(!sections.contains(new Cell(x>>4,hostY>>4,z>>4)))throw new SceneException(SceneException.Code.SCENE_INCOMPLETE,"外伸模型需要未捕获邻域："+cell);
                            index(cell,instance.id);
                        }
                        if(++x>x1){x=x0;if(++z>z1){z=z0;if(++y>y1){indexing=false;modelIndex++;}}}
                    }
                    return false;
                } catch(RuntimeException | Error error){failed=true;throw error;}
                finally{running.set(false);}
            }
            private void index(Cell cell,int id) {
                Cell key=new Cell(cell.x>>4,cell.y>>4,cell.z>>4);IndexSection section=grid.get(key);
                if(section==null){charge(17408+192);int[] head=new int[4096];Arrays.fill(head,-1);section=new IndexSection(head,new int[16],new int[16]);grid.put(key,section);}
                int used=counts.getOrDefault(key,0);
                if(used==section.ids.length) {
                    int capacity=Math.min(maxIndexEntries,Math.multiplyExact(section.ids.length,2));long before=64L+section.ids.length*8L;
                    charge(64L+capacity*8L);section=new IndexSection(section.head,Arrays.copyOf(section.next,capacity),Arrays.copyOf(section.ids,capacity));grid.put(key,section);if(memory!=null)memory.release(before);
                }
                int local=((cell.y&15)<<8)|((cell.z&15)<<4)|(cell.x&15);
                section.ids[used]=id;section.next[used]=section.head[local];section.head[local]=used;counts.put(key,used+1);
                indexMin=Math.min(indexMin,cell.y);indexMax=Math.max(indexMax,Math.addExact(cell.y,1));
            }
            private final Map<Cell,Integer> counts=new HashMap<>();
            public SceneSnapshot result(){if(result==null)throw new IllegalStateException("场景尚未构建完毕");return result;}
        }

        private static int floor(double value) {
            if (!Double.isFinite(value) || value < Integer.MIN_VALUE + 1.0 || value > Integer.MAX_VALUE - 1.0)
                throw limit();
            return (int) Math.floor(value);
        }

        private static SceneException limit(String kind,long actual,long maximum) {
            return new SceneException(SceneException.Code.GEOMETRY_LIMIT,kind+"超过显式限额：请求="+actual+"，上限="+maximum);
        }
        private static SceneException limit() {
            return new SceneException(SceneException.Code.GEOMETRY_LIMIT, "场景/实例索引超过显式限额");
        }

        private void checkOpen() {
            if (sealed) throw new IllegalStateException("场景已封存");
        }
    }
}
