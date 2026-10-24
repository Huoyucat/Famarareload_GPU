package com.wenkrang.famara.render.tools;

import com.google.gson.GsonBuilder;
import com.wenkrang.famara.render.gpu.*;
import com.wenkrang.famara.render.schedule.*;
import com.wenkrang.famara.render.asset.*;
import com.wenkrang.famara.render.kernel.*;
import com.wenkrang.famara.render.math.Vec3;
import com.wenkrang.famara.render.scene.SceneSnapshot;
import com.wenkrang.famara.render.output.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.io.ByteArrayOutputStream;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Offline device validation only, deliberately restricted to the authorized test directory. */
public final class OpenClSmokeMain {
    public static void main(String[] args)throws Exception {
        if(!Path.of("").toAbsolutePath().normalize().equals(Path.of(System.getProperty("famara.test.root", ".")).toAbsolutePath().normalize()) || args.length<1 || args.length>3)
            throw new IllegalArgumentException("Run from the configured test root: new-output-directory [iterations] [normal|unavailable|off]");
        Path output=Path.of(args[0]).toAbsolutePath().normalize();
        if(!output.startsWith(Path.of(System.getProperty("famara.test.root", ".")).toAbsolutePath().normalize().resolve("diagnostics/famara-p2")) || Files.exists(output))throw new IllegalArgumentException("New output directory under test diagnostics/famara-p2 required");
        int iterations=args.length>=2?Integer.parseInt(args[1]):500;
        if(iterations<1 || iterations>10000)throw new IllegalArgumentException("Invalid iteration count");
        String expectation=args.length==3?args[2]:"normal";
        if(!Set.of("normal","unavailable","off").contains(expectation))throw new IllegalArgumentException("Invalid expected result");
        Files.createDirectories(output);
        var host=new RenderMemoryBudget(536870912);var defaults=GpuConfiguration.disabled();
        var config=new GpuConfiguration(expectation.equals("off")?GpuConfiguration.Mode.OFF:GpuConfiguration.Mode.OPENCL,
                defaults.preferredDevice(),defaults.vramBudgetMiB(),1,defaults.stagingMemoryMiB(),defaults.textureCacheMiB(),defaults.kernelTimeoutMillis(),true,defaults.minEstimatedSpeedup());
        var probe=new GpuDeviceProbe(config,new CpuBudget(2000,100,System::nanoTime),host);
        Map<String,Object> report=new LinkedHashMap<>();List<Map<String,Long>> samples=new ArrayList<>();
        try {
            var status=probe.initialization().toCompletableFuture().get(15,TimeUnit.SECONDS);report.put("probe",status);
            if(expectation.equals("normal")) {
                if(!status.available())throw new IllegalStateException("OpenCL unavailable: "+status.reason());
                String expectedHash=null;
                for(int i=0;i<iterations;i++) {
                    var sample=probe.smoke().toCompletableFuture().get(15,TimeUnit.SECONDS);
                    String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(sample.rgb()));
                    if(expectedHash==null){expectedHash=hash;Files.write(output.resolve("smoke.rgb"),sample.rgb());}
                    else if(!hash.equals(expectedHash))throw new AssertionError("GPU smoke output changed");
                    if(probe.buffers().live()!=0 || host.snapshot().live()!=0)throw new AssertionError("Buffer/staging reservation leaked");
                    samples.add(Map.of("kernelNanos",sample.kernelNanos(),"readbackWallNanos",sample.readbackWallNanos(),"wallNanos",sample.wallNanos(),"threadCpuNanos",sample.threadCpuNanos()));
                }
                report.put("rgbSha256",expectedHash);report.put("iterations",iterations);report.put("samples",samples);report.put("buffersBeforeClose",probe.buffers());
            } else {
                if(status.available())throw new AssertionError("Device unexpectedly available in "+expectation+" test");
                cpuFallbackFrame(output,config,host);report.put("cpuFallbackFrameVerified",true);
            }
            report.put("status","PASS");
        }catch(Throwable failure){report.put("status","FAIL");report.put("error",failure.toString());throw failure;}
        finally {
            probe.close();probe.whenClosed().toCompletableFuture().get(15,TimeUnit.SECONDS);
            report.put("closed",true);report.put("hostMemoryAfterClose",host.snapshot());
            report.put("scope","DEVICE_SMOKE_ONLY_NOT_GPU_PHOTO_RENDERING");
            Files.writeString(output.resolve("report.json"),new GsonBuilder().setPrettyPrinting().serializeNulls().create().toJson(report)+"\n");
            System.out.println("OpenCL smoke "+report.get("status")+": "+output.resolve("report.json"));
        }
    }
    private static void cpuFallbackFrame(Path output,GpuConfiguration config,RenderMemoryBudget host)throws Exception {
        // Reuse the existing CPU reference fixture envelope; PNG decoding has bounded scratch overhead.
        // This does not change the server's asset policy or its shared 512 MiB limit.
        var assets=new AssetLimits(10000,2_000_000,64_000_000,1_000_000,100,10000,64,64,100000,16);
        try(var repository=new AssetRepository(1)) {
            var tile=new BufferedImage(1,1,BufferedImage.TYPE_INT_ARGB);tile.setRGB(0,0,0xffffffff);var png=new ByteArrayOutputStream();
            if(!ImageIO.write(tile,"png",png))throw new IllegalStateException("PNG encoder unavailable");
            var files=Map.of("assets/test/textures/white.png",png.toByteArray(),
                    "assets/test/models/block.json","{\"textures\":{\"all\":\"test:white\"},\"elements\":[{\"from\":[0,0,0],\"to\":[16,16,16],\"faces\":{\"north\":{\"texture\":\"#all\"}}}]}".getBytes(StandardCharsets.UTF_8),
                    "assets/test/blockstates/block.json","{\"variants\":{\"\":{\"model\":\"test:block\"}}}".getBytes(StandardCharsets.UTF_8));
            long id=repository.requestBuild();repository.publish(id,AssetGeneration.compile(id,ResourcePack.memory("synthetic-white-face",files,assets),assets));
            var store=new DiskPhotoStore(output.resolve("cpu-fallback-photo"));
            var limits=new CpuPhotoPipeline.Limits(2,2,1,67108864,1_000_000,1_000_000,8_000_000,2000,100,1024,10_000_000_000L);
            try(var pipeline=new CpuPhotoPipeline(limits,store,(file,photo,current)->java.util.concurrent.CompletableFuture.completedFuture(null),new CpuBudget(2000,100,System::nanoTime),config)) {
                var handle=pipeline.submit(UUID.randomUUID(),UUID.randomUUID(),0,32L*1024*1024,()->{
                    var lease=repository.acquire();var scope=new MemoryScope(host,"gpu-unavailable-cpu-photo",32L*1024*1024);
                    try {
                        var builder=new SceneSnapshot.Builder(lease.generation(),0,32,10,1000).memory(scope);
                        for(int x=-1;x<=1;x++)for(int z=-1;z<=1;z++)for(int y=0;y<2;y++)builder.availableSection(x,y,z);
                        builder.put(0,0,0,VisualBlockDescriptor.vanilla(CanonicalBlockState.parse("test:block"),lease.generation().id()),new SceneSnapshot.Lighting(15,0,0xffffff),0);
                        return new CpuPhotoPipeline.Prepared(builder.build(),new Camera(new Vec3(.5,.5,-2),Math.PI/2,0,Camera.Projection.LEGACY_ANGULAR,1),
                                new CpuPhotoRenderer.Settings(8,1,Texture.Filter.NEAREST,false,0,.5,16,0x345678,0,new VoxelTracer.Limits(1000,1000,1000),Map.of()),
                                new MapPaletteSnapshot("offline-fixture",List.of(new MapPaletteSnapshot.Entry(4,0x345678),new MapPaletteSnapshot.Entry(5,0xffffff))),lease,Map.of("kind","OFFLINE_NO_GPU_CPU_FALLBACK"),scope);
                    }catch(Throwable e){lease.close();scope.close();throw e;}
                });
                handle.result().toCompletableFuture().get(10,TimeUnit.SECONDS);var photo=store.loadCommitted(0).orElseThrow();
                int[] pixels=photo.copyRgb();
                if(pixels[64*128+64]!=0xffffff || pixels[0]!=0x345678)throw new AssertionError("CPU fallback geometry/background mismatch");
                for(int pixel:pixels)if(pixel!=0x345678 && pixel!=0xffffff)throw new AssertionError("CPU fallback image mismatch");
                if(!photo.metadata().get("backend").equals("CPU"))throw new AssertionError("CPU fallback backend metadata missing");
            }
        }
        if(host.snapshot().live()!=0)throw new AssertionError("CPU fallback scope leaked");
    }
}
