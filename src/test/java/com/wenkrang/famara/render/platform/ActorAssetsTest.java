package com.wenkrang.famara.render.platform;
import com.wenkrang.famara.render.asset.*;
import com.wenkrang.famara.render.scene.*;
import com.wenkrang.famara.render.schedule.*;
import com.wenkrang.famara.render.math.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.*;
import java.nio.file.*;
import java.util.zip.*;
import java.io.*;
import javax.imageio.ImageIO;
import static org.junit.jupiter.api.Assertions.*;

class ActorAssetsTest {
    @Test void profileUrlsCannotRedirectTheDownloaderToOtherHosts(){
        String hash="a".repeat(64);assertEquals("https://textures.minecraft.net/texture/"+hash,ActorAssets.skinUri("http://textures.minecraft.net/texture/"+hash).toString());
        for(String url:List.of("http://localhost/a","https://textures.minecraft.net.evil/texture/"+hash,"https://textures.minecraft.net@localhost/texture/"+hash,"https://textures.minecraft.net:443/texture/"+hash,"https://textures.minecraft.net/texture/"+hash+"?next=secret","file:///etc/passwd"))assertNull(ActorAssets.skinUri(url));
    }
    @Test void skinsForceOpaqueBaseWhileKeepingOverlayAlphaAndRejectOversizedDimensions()throws Exception{
        var f=new RenderFixtures();f.png("test:skin",64,64,0x00112233);byte[] input=f.files.get("assets/test/textures/skin.png");var image=ImageIO.read(new ByteArrayInputStream(ActorAssets.normalizeSkin(input)));assertEquals(255,image.getRGB(8,8)>>>24);assertEquals(0,image.getRGB(40,8)>>>24);assertEquals(0,image.getRGB(8,40)>>>24);
        f.png("test:large",128,128,0xffffffff);assertThrows(IOException.class,()->ActorAssets.normalizeSkin(f.files.get("assets/test/textures/large.png")));
    }
    @Test void texturesShareOneDecodedCopyPerPhotoAndAllActorMemoryIsReleased()throws Exception{
        var f=new RenderFixtures();f.png("minecraft:entity/player/wide/steve",64,64,0xffffa080);
        Path zip=Files.createTempFile("actor-base",".zip");try(var out=new ZipOutputStream(Files.newOutputStream(zip))){for(var entry:f.files.entrySet()){out.putNextEntry(new ZipEntry(entry.getKey()));out.write(entry.getValue());out.closeEntry();}}
        var memory=new RenderMemoryBudget(64L*1024*1024);var worker=new BudgetedWorkRunner(16,1_000_000,1_000_000,new CpuBudget(2000,100,System::nanoTime));
        try(var resources=new ActorAssets(memory,worker,new CpuBudget(2000,100,System::nanoTime))){
            resources.base(zip);resources.prepare();long baseline=memory.snapshot().live();var batch=resources.batch();var a=LivingActorTest.actor("PLAYER",0,0);var b=LivingActorTest.actor("PLAYER",1,0);var models=batch.bake(List.of(a,b)).toCompletableFuture().get(10,TimeUnit.SECONDS);assertSame(models.get(0).textures().values().iterator().next(),models.get(1).textures().values().iterator().next());assertTrue(memory.snapshot().live()>baseline);batch.close();batch.close();assertEquals(baseline,memory.snapshot().live());assertThrows(CancellationException.class,()->batch.bake(List.of(a)));
            try(var crowd=resources.batch()){
                var actors=java.util.stream.IntStream.range(0,65).mapToObj(n->LivingActorTest.actor("PLAYER",n,0)).toList();
                assertEquals(65,crowd.bake(actors).toCompletableFuture().get(20,TimeUnit.SECONDS).size());
            }assertEquals(baseline,memory.snapshot().live());
            try(var empty=resources.batch()){assertTrue(empty.bake(List.of()).toCompletableFuture().join().isEmpty());}
        }finally{worker.close();Files.delete(zip);}assertEquals(0,memory.snapshot().live());
    }
    @Test void customCeEquipmentJsonAndTextureAreResolvedFromTheCurrentPackWithoutCopyingThePack()throws Exception{
        var vanilla=new RenderFixtures();vanilla.png("minecraft:entity/player/wide/steve",64,64,0xffffa080);Path base=zip(vanilla.files);
        var ce=new RenderFixtures();ce.json("assets/kaleidoscopecookery/equipment/farmer.json","""
                {"layers":{"humanoid":[{"texture":"kaleidoscopecookery_farmer"}],"humanoid_leggings":[{"texture":"kaleidoscopecookery_farmer"}]}}
                """);ce.png("minecraft:entity/equipment/humanoid/kaleidoscopecookery_farmer",16,16,0xff52a557);Path custom=zip(ce.files);
        var original=LivingActorTest.actor("PLAYER",0,0);var armor=new FrozenItem("minecraft:iron_chestplate","",0xffffff,"","",false,0,"","kaleidoscopecookery:farmer",false);
        var actor=new FrozenActor(original.id(),original.type(),original.position(),original.bodyYaw(),original.headYaw(),original.pitch(),original.pose(),original.baby(),original.scale(),original.limbSwing(),original.variant(),original.tintRgb(),original.skinUrl(),original.slim(),original.sheared(),original.sky(),original.block(),original.appearance(),new FrozenEquipment(Map.of("CHEST",armor),false));
        var memory=new RenderMemoryBudget(64L*1024*1024);var worker=new BudgetedWorkRunner(16,1_000_000,1_000_000,new CpuBudget(2000,100,System::nanoTime));var texture=ResourceKey.parse("minecraft:entity/equipment/humanoid/kaleidoscopecookery_farmer");
        try(var resources=new ActorAssets(memory,worker,new CpuBudget(2000,100,System::nanoTime))){resources.base(base);resources.prepare();try(var batch=resources.batch(List.of(custom))){var geometry=batch.bake(List.of(actor)).toCompletableFuture().get(10,TimeUnit.SECONDS).getFirst();assertTrue(geometry.textures().containsKey(texture));}assertEquals(0,memory.snapshot().domains().getOrDefault("actor-scene",0L));}
        finally{worker.close();Files.deleteIfExists(base);Files.deleteIfExists(custom);}assertEquals(0,memory.snapshot().live());
    }
    private static Path zip(Map<String,byte[]> files)throws Exception{Path path=Files.createTempFile("actor-resource-source",".zip");try(var out=new ZipOutputStream(Files.newOutputStream(path))){for(var entry:files.entrySet()){out.putNextEntry(new ZipEntry(entry.getKey()));out.write(entry.getValue());out.closeEntry();}}return path;}
}
