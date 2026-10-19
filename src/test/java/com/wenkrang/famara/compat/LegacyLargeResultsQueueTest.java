package com.wenkrang.famara.compat;

import com.wenkrang.famara.render.schedule.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.zip.*;
import static org.junit.jupiter.api.Assertions.*;

class LegacyLargeResultsQueueTest {
    @TempDir Path root;
    @AfterEach void reset(){CeColorService.closeQueue();CeCompat.memoryBudget(null);CeCompat.backendForTests(null);}
    private void entry(ZipOutputStream output,String name,byte[] data)throws Exception {output.putNextEntry(new ZipEntry(name));output.write(data);output.closeEntry();}
    @Test void realAsyncQueuePublishesFortyThousandUniqueRootsBesideAFullV2Ledger()throws Exception {
        int count=40000;Path zip=root.resolve("many-results.zip");var models=new HashMap<String,CeCompat.ItemModel>();
        byte[] item="{\"model\":{\"type\":\"minecraft:model\",\"model\":\"shared:entity/panel\"}}".getBytes(StandardCharsets.UTF_8),model="{\"textures\":{\"layer0\":\"shared:entity/panel\"}}".getBytes(StandardCharsets.UTF_8);
        var image=new BufferedImage(1,1,BufferedImage.TYPE_INT_ARGB);image.setRGB(0,0,0xff2244aa);var png=new ByteArrayOutputStream();assertTrue(ImageIO.write(image,"png",png));
        try(var output=new ZipOutputStream(Files.newOutputStream(zip))) {
            entry(output,"assets/shared/models/entity/panel.json",model);entry(output,"assets/shared/textures/entity/panel.png",png.toByteArray());
            for(int i=0;i<count;i++){String id="test:result_"+i;models.put(id,new CeCompat.ItemModel(id,PackColorIndex.ItemState.EMPTY));entry(output,"assets/test/items/result_"+i+".json",item);}
        }
        CeCompat.backendForTests(new CeCompat.Backend(){public boolean enabled(){return true;}});CeColorService.configure(List.of(),List.of(),path->null);CeColorService.external(List.of(),List.of());CeColorService.models(models);long ticket=CeColorService.generated(null,zip);
        var v2=new RenderMemoryBudget(536870912);
        try(var full=v2.reserve("full-v2",536870912)) {
            CeCompat.memoryBudget(v2);CeCompat.loadingBudget(new CpuBudget(5000,100,System::nanoTime),new BudgetedAssetLoader.Limits(128,1000000,1000000,8000000,60000000000L));
            CeColorService.schedule(ticket).toCompletableFuture().get(60,TimeUnit.SECONDS);var snapshot=CeColorService.snapshot();
            assertTrue(CeColorService.ready(CeCompat.visualEpoch()),()->CeColorService.failure(CeCompat.visualEpoch()));assertEquals(count,snapshot.result.items().size());assertTrue(snapshot.result.issues().isEmpty(),()->snapshot.result.issues().toString());
            for(int i:new int[]{0,19999,39999})assertEquals(0x2244aa,snapshot.result.itemColor("test:result_"+i,PackColorIndex.ItemState.EMPTY));
            assertTrue(CeColorService.independentMemory().peak()>72L*1024*1024);assertTrue(CeColorService.independentMemory().peak()<=ColorWorkBudget.MAXIMUM);assertEquals(536870912,v2.snapshot().live());
            System.out.println("LEGACY_280_QUEUE items="+count+" ready=true retained="+CeColorService.independentMemory().live()+" peak="+CeColorService.independentMemory().peak()+" v2Live="+v2.snapshot().live()+" color=2244aa");
            CeColorService.closeQueue();assertEquals(0,CeColorService.independentMemory().live());
        }
        Files.delete(zip);assertEquals(0,v2.snapshot().live());
    }
}
