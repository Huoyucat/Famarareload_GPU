package com.wenkrang.famara.compat;

import com.wenkrang.famara.render.schedule.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class LegacyColorBuildIntegrationTest {
    @TempDir Path root;
    private byte[] png()throws Exception {var image=new BufferedImage(1,1,BufferedImage.TYPE_INT_ARGB);image.setRGB(0,0,0xffff4020);var output=new ByteArrayOutputStream();assertTrue(ImageIO.write(image,"png",output));return output.toByteArray();}
    private void file(String name,byte[] bytes)throws Exception {Path path=root.resolve(name);Files.createDirectories(path.getParent());Files.write(path,bytes);}
    @org.junit.jupiter.api.AfterEach void reset(){CeColorService.closeQueue();CeCompat.memoryBudget(null);CeCompat.backendForTests(null);}
    @Test void actualAsyncBuildWithTwelveThousandMappingsSucceedsBesideFullV2Budget()throws Exception {
        CeCompat.backendForTests(new CeCompat.Backend(){public boolean enabled(){return true;}});
        file("assets/test/items/plant.json","{\"model\":{\"type\":\"minecraft:model\",\"model\":\"test:item/plant\"}}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        file("assets/test/models/item/plant.json","{\"textures\":{\"layer0\":\"test:item/plant\"}}".getBytes(java.nio.charset.StandardCharsets.UTF_8));file("assets/test/textures/item/plant.png",png());
        var models=new HashMap<String,CeCompat.ItemModel>();for(int i=0;i<12000;i++){String id="test:item_"+i;models.put(id,new CeCompat.ItemModel("test:item/generated_"+i,PackColorIndex.ItemState.EMPTY));}models.put("test:plant",new CeCompat.ItemModel("test:item/plant",PackColorIndex.ItemState.EMPTY));
        var fullV2=new RenderMemoryBudget(536870912);var held=fullV2.reserve("v2-already-full",536870912);CeCompat.memoryBudget(fullV2);
        CeColorService.configure(List.of(root),List.of(),path->null);CeColorService.external(List.of(),List.of());CeColorService.models(models);CeColorService.generated(null,null);
        CeCompat.loadingBudget(new CpuBudget(5000,100,System::nanoTime),new BudgetedAssetLoader.Limits(128,1000000,1000000,8000000,60000000000L));
        long ticket=CeColorService.request();CeColorService.schedule(ticket).toCompletableFuture().get(60,TimeUnit.SECONDS);
        assertTrue(CeColorService.ready(CeCompat.visualEpoch()),"failure="+CeColorService.failure(CeCompat.visualEpoch()));
        assertEquals(0x00ff4020,CeColorService.snapshot().result.itemColor("test:plant",PackColorIndex.ItemState.EMPTY));
        assertTrue(CeColorService.independentMemory().live()<=ColorWorkBudget.MAXIMUM);assertEquals(536870912,fullV2.snapshot().live());
        assertTrue(CeColorService.independentMemory().peak()<=ColorWorkBudget.MAXIMUM);
        System.out.println("LEGACY_COLOR mappings="+models.size()+" ready=true retained="+CeColorService.independentMemory().live()+" peak="+CeColorService.independentMemory().peak()+" v2Live="+fullV2.snapshot().live());
        CeColorService.closeQueue();assertEquals(0,CeColorService.independentMemory().live());held.close();assertEquals(0,fullV2.snapshot().live());
    }
}
