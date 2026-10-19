package com.wenkrang.famara.compat;

import com.wenkrang.famara.render.schedule.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.zip.*;
import static org.junit.jupiter.api.Assertions.*;

class LegacyLargePackTest {
    @TempDir Path root;
    @AfterEach void reset(){CeColorService.closeQueue();CeCompat.backendForTests(null);}
    private byte[] png()throws Exception {var image=new BufferedImage(1,1,BufferedImage.TYPE_INT_ARGB);image.setRGB(0,0,0xff2244aa);var stream=new ByteArrayOutputStream();assertTrue(ImageIO.write(image,"png",stream));return stream.toByteArray();}
    private void entry(ZipOutputStream output,String name,byte[] bytes)throws Exception {output.putNextEntry(new ZipEntry(name));output.write(bytes);output.closeEntry();}
    @Test void productionSizedUnrelatedZipAndTwentyThousandSharedItemBindingsFitTheIndependentEnvelope()throws Exception {
        Path zip=root.resolve("merged.zip");
        try(var output=new ZipOutputStream(Files.newOutputStream(zip))) {
            entry(output,"assets/test/items/shared.json","{\"model\":{\"type\":\"minecraft:model\",\"model\":\"bettermodel:entity/shared\"}}".getBytes(StandardCharsets.UTF_8));
            entry(output,"assets/bettermodel/models/entity/shared.json","{\"textures\":{\"layer0\":\"nameplates:font/shared\"}}".getBytes(StandardCharsets.UTF_8));
            entry(output,"assets/nameplates/textures/font/shared.png",png());
            for(int i=0;i<167000;i++)entry(output,"assets/bettermodel/items/unrelated_"+i+".json",new byte[]{0});
        }
        var models=new HashMap<String,CeCompat.ItemModel>();for(int i=0;i<20000;i++)models.put("test:binding_"+i,new CeCompat.ItemModel("test:shared",new PackColorIndex.ItemState(List.of(i%2==0),List.of("variant_"+(i%8)),List.of((float)(i%4)),"fixed",0,1)));
        CeCompat.backendForTests(new CeCompat.Backend(){public boolean enabled(){return true;}});CeColorService.configure(List.of(),List.of(),path->null);CeColorService.external(List.of(),List.of());CeColorService.models(models);long ticket=CeColorService.generated(null,zip);
        var v2=new RenderMemoryBudget(536870912);
        try(var full=v2.reserve("v2-full",536870912)) {
            CeCompat.memoryBudget(v2);CeCompat.loadingBudget(new CpuBudget(5000,100,System::nanoTime),new BudgetedAssetLoader.Limits(128,1000000,1000000,8000000,60000000000L));
            CeColorService.schedule(ticket).toCompletableFuture().get(60,TimeUnit.SECONDS);var snapshot=CeColorService.snapshot();assertTrue(CeColorService.ready(CeCompat.visualEpoch()));assertTrue(snapshot.result.issues().isEmpty(),()->snapshot.result.issues().toString());assertEquals(1,snapshot.result.items().size());assertEquals(0x2244aa,snapshot.result.itemColor("test:shared",PackColorIndex.ItemState.EMPTY));
            for(String id:List.of("test:binding_0","test:binding_9999","test:binding_19999")){var model=snapshot.models.get(id);assertNotNull(model);assertEquals(0x2244aa,snapshot.result.itemColor(model.resource(),model.state()));}
            assertEquals(536870912,v2.snapshot().live());assertTrue(CeColorService.independentMemory().peak()<=ColorWorkBudget.MAXIMUM);
            System.out.println("LEGACY_LARGE entries=167003 mappings=20000 roots=1 color=2244aa retained="+CeColorService.independentMemory().live()+" peak="+CeColorService.independentMemory().peak()+" v2Live="+v2.snapshot().live());
            CeColorService.closeQueue();assertEquals(0,CeColorService.independentMemory().live());
        }
        Files.delete(zip);assertEquals(0,v2.snapshot().live());
    }
}
