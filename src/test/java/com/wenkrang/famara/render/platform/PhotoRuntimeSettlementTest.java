package com.wenkrang.famara.render.platform;

import com.google.gson.*;
import com.wenkrang.famara.render.asset.*;
import com.wenkrang.famara.render.kernel.*;
import com.wenkrang.famara.render.math.Vec3;
import com.wenkrang.famara.render.output.*;
import com.wenkrang.famara.render.scene.SceneSnapshot;
import com.wenkrang.famara.render.schedule.*;
import org.bukkit.Bukkit;
import org.bukkit.map.MapCanvas;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import java.util.logging.Logger;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

/** 真实后台管线/磁盘/胶卷事务 + 模拟地图端口；不是服务器线程验收。 */
class PhotoRuntimeSettlementTest {
    @TempDir Path directory;
    private void set(Object target,String name,Object value)throws Exception {Field f=target.getClass().getDeclaredField(name);f.setAccessible(true);f.set(target,value);}
    private <T>T get(Object target,String name,Class<T> type)throws Exception {Field f=target.getClass().getDeclaredField(name);f.setAccessible(true);return type.cast(f.get(target));}
    private TexturedConfiguration configuration()throws Exception {
        JsonObject json=JsonParser.parseString(Files.readString(Path.of("docs/render-v2/runtime-experiment.json"))).getAsJsonObject();
        json.getAsJsonObject("pipeline").addProperty("cpuMillisPerSecond",5000);Path file=directory.resolve("config.json");Files.writeString(file,json.toString());return TexturedConfiguration.read(file);
    }
    private CpuPhotoPipeline.Prepared prepare(AssetRepository repository) {
        var lease=repository.acquire();var g=lease.generation();var builder=new SceneSnapshot.Builder(g,0,16,4,10000);
        for(int x=-1;x<=1;x++)for(int z=-1;z<=1;z++)builder.availableSection(x,0,z);
        builder.put(0,0,0,RenderFixtures.vanilla("test:block",g.id()),new SceneSnapshot.Lighting(15,0,0xffffff),0);
        return new CpuPhotoPipeline.Prepared(builder.build(),new Camera(new Vec3(.5,.5,-2),Math.PI/2,0,Camera.Projection.LEGACY_ANGULAR,1),
                new CpuPhotoRenderer.Settings(8,1,Texture.Filter.NEAREST,false,0,.5,10,0,0,new VoxelTracer.Limits(1000,1000,1000),Map.of()),
                new MapPaletteSnapshot("test",List.of(new MapPaletteSnapshot.Entry(4,0),new MapPaletteSnapshot.Entry(5,0xffffff))),lease);
    }
    @Test void actualRuntimePublisherChargesOnlyAfterStoredPhotoAndRejectsMissingMapRenderer()throws Exception {
        JavaPlugin plugin=mock(JavaPlugin.class);when(plugin.getDataFolder()).thenReturn(directory.toFile());when(plugin.getLogger()).thenReturn(Logger.getAnonymousLogger());
        TexturedConfiguration config=configuration();PhotoRuntime runtime;
        try(var bukkit=mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getMinecraftVersion).thenReturn("26.3");var ctor=PhotoRuntime.class.getDeclaredConstructor(JavaPlugin.class,TexturedConfiguration.class);ctor.setAccessible(true);runtime=ctor.newInstance(plugin,config);
        }
        var repository=get(runtime,"assets",AssetRepository.class);RenderFixtures f=new RenderFixtures();f.png("test:tile",1,1,0xffffffff);f.cube("test:model","test:tile");f.block("test:block","test:model");long id=repository.requestBuild();repository.publish(id,f.generation(id));
        DiskPhotoStore store=new DiskPhotoStore(directory.resolve("pictures"));CameraLedger ledger=new CameraLedger(directory.resolve("camera-ledger-v2"),config.ledger());set(runtime,"store",store);set(runtime,"ledger",ledger);
        Method publish=PhotoRuntime.class.getDeclaredMethod("publish",DiskPhotoStore.Stored.class,ImmutablePhoto.class,BooleanSupplier.class);publish.setAccessible(true);
        var cpu=get(runtime,"cpu",CpuBudget.class);
        try(var pipeline=new CpuPhotoPipeline(config.pipeline(),store,(stored,photo,current)->{
            try{return (CompletionStage<Void>)publish.invoke(runtime,stored,photo,current);}catch(Exception error){return CompletableFuture.failedFuture(error);}
        },cpu)) {
            set(runtime,"pipeline",pipeline);UUID camera=UUID.randomUUID(),owner=UUID.randomUUID(),request=UUID.randomUUID();
            Class<?> type=Class.forName(PhotoRuntime.class.getName()+"$Run");var ctor=type.getDeclaredConstructor(UUID.class);ctor.setAccessible(true);Object run=ctor.newInstance(request);
            var renderer=new ImmutableMapRenderer(10);set(run,"renderer",renderer);
            Map<UUID,Object> jobs=get(runtime,"jobs",Map.class);jobs.put(request,run);ledger.reserve(request,camera,owner,16,0);ledger.bindOutput(request,81);
            assertEquals(0,ledger.snapshot().spent(camera));var result=pipeline.submit(owner,request,81,8*1024*1024,()->prepare(repository));set(run,"handle",result);
            result.result().toCompletableFuture().get(10,TimeUnit.SECONDS);
            assertEquals(1,ledger.snapshot().spent(camera));assertEquals(CameraLedger.Status.SUCCEEDED,ledger.snapshot().job(request).orElseThrow().status());
            assertTrue(store.loadCommitted(81).isPresent());MapCanvas canvas=mock(MapCanvas.class);renderer.render(null,canvas,null);verify(canvas,times(16384)).setPixel(anyInt(),anyInt(),anyByte());
            UUID failed=UUID.randomUUID();Object missing=ctor.newInstance(failed);jobs.put(failed,missing);ledger.reserve(failed,camera,owner,15,1);ledger.bindOutput(failed,82);
            var second=pipeline.submit(owner,failed,82,8*1024*1024,()->prepare(repository));set(missing,"handle",second);
            assertThrows(ExecutionException.class,()->second.result().toCompletableFuture().get(10,TimeUnit.SECONDS));assertEquals(1,ledger.snapshot().spent(camera));
            ledger.fail(failed,"地图未安装");assertEquals(15,ledger.snapshot().balance(camera,15,1).available());
        } finally {
            runtime.close();var worker=get(runtime,"io",BudgetedWorkRunner.class);assertTrue(get(worker,"executor",ScheduledThreadPoolExecutor.class).awaitTermination(10,TimeUnit.SECONDS));ledger.close();
        }
    }
}
