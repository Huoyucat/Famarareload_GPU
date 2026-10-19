package com.wenkrang.famara.render.platform;

import com.wenkrang.famara.lib.Sched;
import com.wenkrang.famara.render.asset.*;
import com.wenkrang.famara.render.scene.*;
import com.wenkrang.famara.render.schedule.CpuBudget;
import org.bukkit.*;
import org.bukkit.block.Biome;
import java.lang.management.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;

/** 在Global上下文冻结服务器registry；NMS引用只用于准备阶段的不可变、纯数学草色修饰器。 */
public final class BiomeCatalog implements AutoCloseable {
    public record Limits(int maximum,int entriesPerTick,long nanosPerTick,long quantumCpuNanos) {
        public Limits{if(maximum<=0 || entriesPerTick<=0 || nanosPerTick<=0 || quantumCpuNanos<=0)throw new IllegalArgumentException("biome冻结限额无效");}
    }
    @FunctionalInterface interface Modifier {int apply(int x,int z,int base);}
    record Entry(BiomeColors.Climate climate,Modifier modifier) {}
    public static final class View {
        private final Map<String,Entry> entries;
        private final java.util.function.BooleanSupplier current;
        private View(Map<String,Entry> entries,java.util.function.BooleanSupplier current){this.entries=Map.copyOf(entries);this.current=current;}
        public boolean current(){return current.getAsBoolean();}
        public SceneSnapshot.Lighting lighting(AssetGeneration assets,ChunkSnapshot snapshot,int x,int y,int z,CanonicalBlockState state) {
            if(!current())throw new IllegalStateException("服务端biome registry在捕获期间重载");
            // Keyed是稳定接口，不能用旧Biome enum的invokevirtual getKey字节码调用现代接口实现。
            Biome biome=snapshot.getBiome(x,y,z);String key=((Keyed)biome).getKey().toString();
            Entry entry=entries.get(key);if(entry==null)throw new IllegalStateException("快照生物群系不在冻结registry："+key);
            BiomeColors.Climate climate=entry.climate;
            int modified=0xffffff;
            if(BiomeColors.usesGrass(state))modified=entry.modifier.apply((snapshot.getX()<<4)+x,(snapshot.getZ()<<4)+z,BiomeColors.grass(assets,climate));
            return new SceneSnapshot.Lighting(snapshot.getBlockSkyLight(x,y,z),snapshot.getBlockEmittedLight(x,y,z),
                    BiomeColors.tint(assets,state,climate,modified),climate.water());
        }
        /** 每个原始区块独占缓存；固定资源代，转换完成立即释放，不共享mutable视图。 */
        public WorldPreparation.LightingResolver resolver(AssetGeneration assets) {
            return new WorldPreparation.LightingResolver() {
                public SceneSnapshot.Lighting resolve(ChunkSnapshot snapshot,int x,int y,int z,CanonicalBlockState state){return lighting(assets,snapshot,x,y,z,state);}
                public WorldPreparation.LightingResolver forChunk(com.wenkrang.famara.render.schedule.MemoryScope memory){return new CachedLighting(View.this,assets,memory);}
            };
        }
        public int size(){return entries.size();}
    }
    static final class CachedLighting implements WorldPreparation.LightingResolver {
        private static final int MAX_BIOMES=256,MAX_TINTS=4096;
        private final View view;
        private final AssetGeneration assets;
        private final com.wenkrang.famara.render.schedule.MemoryScope memory;
        private final Map<Biome,Colors> colors=new IdentityHashMap<>();
        private long retained;
        private int tints;
        private boolean closed;
        private static final class Colors {
            final Entry entry;
            final int[] grass=new int[256];
            final BitSet known=new BitSet(256);
            final Map<CanonicalBlockState,Integer> tint=new HashMap<>();
            Integer base;
            Colors(Entry entry){this.entry=entry;}
        }
        CachedLighting(View view,AssetGeneration assets,com.wenkrang.famara.render.schedule.MemoryScope memory){this.view=view;this.assets=assets;this.memory=memory;}
        private void charge(long bytes){if(memory!=null)memory.claim(bytes);retained+=bytes;}
        public SceneSnapshot.Lighting resolve(ChunkSnapshot snapshot,int x,int y,int z,CanonicalBlockState state) {
            if(closed || !view.current())throw new IllegalStateException("biome缓存已失效");
            Biome biome=snapshot.getBiome(x,y,z);Colors cached=colors.get(biome);
            if(cached==null) {
                String key=((Keyed)biome).getKey().toString();Entry entry=view.entries.get(key);
                if(entry==null)throw new IllegalStateException("快照生物群系不在冻结registry："+key);
                if(colors.size()>=MAX_BIOMES)return view.lighting(assets,snapshot,x,y,z,state);
                charge(4096);cached=new Colors(entry);colors.put(biome,cached);
            }
            int tint;
            if(BiomeColors.usesGrass(state)) {
                int column=z*16+x;
                if(!cached.known.get(column)) {
                    if(cached.base==null)cached.base=BiomeColors.grass(assets,cached.entry.climate);
                    cached.grass[column]=cached.entry.modifier.apply(snapshot.getX()*16+x,snapshot.getZ()*16+z,cached.base);cached.known.set(column);
                }
                tint=BiomeColors.tint(assets,state,cached.entry.climate,cached.grass[column]);
            } else {
                Integer value=cached.tint.get(state);
                if(value==null) {
                    value=BiomeColors.tint(assets,state,cached.entry.climate,0xffffff);
                    if(tints<MAX_TINTS){charge(256);cached.tint.put(state,value);tints++;}
                }
                tint=value;
            }
            return new SceneSnapshot.Lighting(snapshot.getBlockSkyLight(x,y,z),snapshot.getBlockEmittedLight(x,y,z),tint,cached.entry.climate.water());
        }
        @Override public void close(){if(closed)return;closed=true;colors.clear();if(memory!=null)memory.release(retained);retained=0;}
    }
    private final Limits limits;
    private final CpuBudget budget;
    private volatile CompletableFuture<View> ready=new CompletableFuture<>();
    private volatile long epoch;
    private final Map<String,Entry> entries=new HashMap<>();
    private final ThreadMXBean meter=ManagementFactory.getThreadMXBean();
    private Iterator<Biome> iterator;
    private Sched.Task timer;
    private volatile boolean closed;
    public BiomeCatalog(Limits limits,CpuBudget budget){this.limits=Objects.requireNonNull(limits);this.budget=Objects.requireNonNull(budget);budget.retryAfterNanos(limits.quantumCpuNanos);}
    public synchronized void start(){if(closed || timer!=null)throw new IllegalStateException("biome服务已启动或关闭");timer=Sched.globalTimer(this::tick,1,1);}
    public synchronized void tick() {
        if(closed || ready.isDone())return;
        var allowed=budget.reserve(limits.quantumCpuNanos);if(allowed.isEmpty())return;
        long wall=System.nanoTime(),cpu=cpu();
        try {
            if(iterator==null){Registry<Biome> registry=Bukkit.getRegistry(Biome.class);if(registry==null)throw new IllegalStateException("缺少服务端biome registry");iterator=registry.iterator();}
            long deadline=wall+limits.nanosPerTick;
            for(int n=0;n<limits.entriesPerTick && System.nanoTime()<deadline && iterator.hasNext();n++) {
                Biome biome=iterator.next();String key=((Keyed)biome).getKey().toString();
                if(key.equals("minecraft:custom"))continue;
                if(entries.size()>=limits.maximum)throw new IllegalStateException("服务端biome registry超过限额");
                try{entries.put(key,freeze(biome));}
                catch(RuntimeException error){throw new IllegalStateException("无法冻结生物群系 "+key+"："+error.getMessage(),error);}
            }
            if(!iterator.hasNext()){long captured=epoch;ready.complete(new View(entries,()->!closed && epoch==captured));entries.clear();iterator=null;if(timer!=null)timer.cancel();}
        } catch(Throwable error){ready.completeExceptionally(error);entries.clear();iterator=null;if(timer!=null)timer.cancel();if(error instanceof VirtualMachineError fatal)throw fatal;}
        finally {long end=cpu();allowed.get().settle(cpu>=0 && end>=cpu?end-cpu:Math.max(0,System.nanoTime()-wall));}
    }
    static Entry freeze(Object biome) {
        try {
            Object handle=call(biome,"getHandle");
            // 新版CraftBiome返回Holder；旧版直接返回Biome。
            if(!handle.getClass().getName().equals("net.minecraft.world.level.biome.Biome"))handle=call(handle,"value");
            return freezeHandle(handle);
        } catch(ReflectiveOperationException error){throw new IllegalStateException("不支持的服务端biome元数据接口，拒绝猜测客户端默认值",error);}
    }
    static Entry freezeHandle(Object handle) throws ReflectiveOperationException {
        Object effects=call(handle,"getSpecialEffects");Object climate=field(handle,"climateSettings");
        double temperature=((Number)value(climate,"temperature")).doubleValue(),downfall=((Number)value(climate,"downfall")).doubleValue();
        int water=rgb(((Number)first(effects,"waterColor","getWaterColor")).intValue(),"waterColor");
        Integer grass=optionalRgb(first(effects,"grassColorOverride","getGrassColorOverride"),"grassColorOverride");
        Integer foliage=optionalRgb(first(effects,"foliageColorOverride","getFoliageColorOverride"),"foliageColorOverride");
        Integer dry=null;try{dry=optionalRgb(first(effects,"dryFoliageColorOverride","getDryFoliageColorOverride"),"dryFoliageColorOverride");}catch(NoSuchMethodException ignored){}
        Object modifier=first(effects,"grassColorModifier","getGrassColorModifier");
        Class<?> publicType=modifier instanceof Enum<?> enumeration?enumeration.getDeclaringClass():modifier.getClass();
        Method modify=publicType.getMethod("modifyColor",double.class,double.class,int.class);
        // 此调用只执行服务端不可变修饰器的纯数学；不调用Biome.getGrassColor，避免服务器未加载客户端LUT。
        Modifier operation=(x,z,base)->{try{return rgb(((Number)modify.invoke(modifier,(double)x,(double)z,base)).intValue(),"grassColorModifier");}
            catch(ReflectiveOperationException error){throw new IllegalStateException("biome草色修饰器失败",error);}};
        return new Entry(new BiomeColors.Climate(temperature,downfall,water,grass,foliage,dry),operation);
    }
    /** 旧版24位RGB和新版不透明ARGB具有相同染色含义；内核仍只保存24位RGB。 */
    private static int rgb(int value,String field) {
        int alpha=value>>>24;
        if(alpha!=0 && alpha!=255)throw new IllegalArgumentException("biome颜色字段 "+field+" 不是RGB或不透明ARGB：0x"+String.format(Locale.ROOT,"%08X",value));
        return value&0xffffff;
    }
    private static Integer optionalRgb(Object value,String field) {
        Integer color=optional(value);return color==null?null:rgb(color,field);
    }
    private static Integer optional(Object value) {
        if(value instanceof Optional<?> optional)return optional.isEmpty()?null:((Number)optional.get()).intValue();
        if(value instanceof OptionalInt optional)return optional.isEmpty()?null:optional.getAsInt();
        throw new IllegalStateException("biome颜色覆盖类型不受支持");
    }
    private static Object first(Object target,String... names) throws ReflectiveOperationException {
        for(String name:names)try{return call(target,name);}catch(NoSuchMethodException ignored){}
        throw new NoSuchMethodException(Arrays.toString(names));
    }
    private static Object call(Object target,String name) throws ReflectiveOperationException {
        Method method=target.getClass().getMethod(name);if(!method.canAccess(target))method.trySetAccessible();return method.invoke(target);
    }
    private static Object value(Object target,String name) throws ReflectiveOperationException {try{return call(target,name);}catch(NoSuchMethodException e){return field(target,name);}}
    private static Object field(Object target,String name) throws ReflectiveOperationException {
        Field field=target.getClass().getDeclaredField(name);if(!field.trySetAccessible())throw new IllegalAccessException(name);return field.get(target);
    }
    private long cpu(){return meter.isCurrentThreadCpuTimeSupported() && meter.isThreadCpuTimeEnabled()?meter.getCurrentThreadCpuTime():-1;}
    public synchronized void invalidate() {
        if(closed)return;epoch++;ready.completeExceptionally(new CancellationException("biome registry已重载"));ready=new CompletableFuture<>();
        iterator=null;entries.clear();if(timer!=null)timer.cancel();timer=null;
    }
    public synchronized void reload(){if(closed)return;invalidate();start();}
    public CompletionStage<View> readiness(){return ready.minimalCompletionStage();}
    public View view(){return ready.getNow(null);}
    @Override public synchronized void close(){closed=true;if(timer!=null)timer.cancel();iterator=null;entries.clear();ready.completeExceptionally(new CancellationException("biome服务关闭"));}
}
