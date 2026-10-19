package com.wenkrang.famara.compat;

import com.wenkrang.famara.render.schedule.*;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class CeColorOwnershipTest {
    @TempDir Path root;
    @AfterEach void reset(){CeColorService.closeQueue();CeCompat.backendForTests(null);}
    private void setup(java.util.function.Function<String,byte[]> presets)throws Exception {
        CeColorService.closeQueue();CeCompat.backendForTests(new CeCompat.Backend(){public boolean enabled(){return true;}});
        Files.createDirectories(root.resolve("assets/test/items"));Files.writeString(root.resolve("assets/test/items/a.json"),"{\"model\":{\"type\":\"minecraft:model\",\"model\":\"test:item/a\"}}");
        CeColorService.configure(List.of(root),List.of(),presets);CeColorService.external(List.of(),List.of());CeColorService.generated(null,null);CeColorService.models(Map.of("test:a",new CeCompat.ItemModel("test:a",PackColorIndex.ItemState.EMPTY)));
        CeCompat.loadingBudget(new CpuBudget(5000,100,System::nanoTime),new BudgetedAssetLoader.Limits(1,1000000,1000000,8000000,30000000000L));
    }
    private byte[] model(){return "{\"textures\":{\"layer0\":\"test:a\"}}".getBytes(java.nio.charset.StandardCharsets.UTF_8);}
    @Test void oldSessionRetainsItsIndependentLeaseAcrossTwoRebuilds()throws Exception {
        var image=new BufferedImage(1,1,BufferedImage.TYPE_INT_ARGB);image.setRGB(0,0,0xff123456);var output=new ByteArrayOutputStream();assertTrue(ImageIO.write(image,"png",output));byte[] png=output.toByteArray();
        setup(path->path.equals("assets/test/models/item/a.json")?model():path.equals("assets/test/textures/a.png")?png:null);
            CeColorService.schedule(CeColorService.request()).toCompletableFuture().get(10,TimeUnit.SECONDS);long first=CeColorService.independentMemory().live();assertTrue(first>0);
            var session=CeCompat.session();CeColorService.schedule(CeColorService.request()).toCompletableFuture().get(10,TimeUnit.SECONDS);assertTrue(CeColorService.independentMemory().live()>first);
            CeColorService.closeQueue();assertEquals(first,CeColorService.independentMemory().live());session.close();session.close();assertEquals(0,CeColorService.independentMemory().live());
    }
    @Test void closeDoesNotReleaseAWorkerBlockedInSourceReadUntilItExits()throws Exception {
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var failed=new AtomicBoolean();
        setup(path->{if(path.equals("assets/test/models/item/a.json")){entered.countDown();try{assertTrue(release.await(5,TimeUnit.SECONDS));}catch(InterruptedException e){throw new RuntimeException(e);}failed.set(true);throw new CancellationException("cancelled read");}return null;});
        try {
            var ready=CeColorService.schedule(CeColorService.request());assertTrue(entered.await(5,TimeUnit.SECONDS));long live=CeColorService.independentMemory().live();assertTrue(live>=ColorWorkBudget.TRANSIENT);CeColorService.closeQueue();assertEquals(live,CeColorService.independentMemory().live());
            release.countDown();assertThrows(Exception.class,()->ready.toCompletableFuture().get(5,TimeUnit.SECONDS));assertTrue(failed.get());assertEquals(0,CeColorService.independentMemory().live());
        } finally{release.countDown();}
    }
    @Test void sharedItemPayloadsDoNotEraseDifferentStates() {
        var models=new HashMap<String,CeCompat.ItemModel>();var yes=new PackColorIndex.ItemState(List.of(true),List.of(),List.of(),"fixed",0,1);var no=PackColorIndex.ItemState.EMPTY;
        models.put("test:one",new CeCompat.ItemModel("test:a",yes));models.put("test:two",new CeCompat.ItemModel("test:a",yes));models.put("test:three",new CeCompat.ItemModel("test:a",no));
        var budget=new ColorWorkBudget();try(var roots=new ColorRootSelector(models,budget)){while(!roots.step(1,Long.MAX_VALUE,System::nanoTime,()->true)){}assertSame(roots.models().get("test:one"),roots.models().get("test:two"));assertNotSame(roots.models().get("test:one"),roots.models().get("test:three"));assertTrue(roots.test("assets/test/items/a.json"));}
    }
}
