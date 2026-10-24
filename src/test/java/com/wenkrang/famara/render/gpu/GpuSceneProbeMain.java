package com.wenkrang.famara.render.gpu;

import com.wenkrang.famara.TestPaths;
import com.google.gson.GsonBuilder;
import com.wenkrang.famara.render.asset.*;
import com.wenkrang.famara.render.kernel.*;
import com.wenkrang.famara.render.math.*;
import com.wenkrang.famara.render.scene.*;
import com.wenkrang.famara.render.schedule.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Actual-device differential harness. Never runs as a unit test or in a server plugin. */
public final class GpuSceneProbeMain {
    public static void main(String[] args)throws Exception {
        Path server=TestPaths.ROOT,out=Path.of(args[0]).toAbsolutePath().normalize();
        if(!Path.of("").toAbsolutePath().normalize().equals(server) || !out.startsWith(server.resolve("diagnostics/famara-p3")) || Files.exists(out))throw new IllegalArgumentException("New test-server diagnostics/famara-p3 directory required");
        Files.createDirectories(out);var report=new LinkedHashMap<String,Object>();var samples=new ArrayList<Object>();
        var memory=new RenderMemoryBudget(536870912);var d=GpuConfiguration.disabled();
        var config=new GpuConfiguration(GpuConfiguration.Mode.OPENCL,d.preferredDevice(),384,1,64,128,10000,true,1.2);
        var probe=new GpuDeviceProbe(config,new CpuBudget(2000,100,System::nanoTime),memory);
        try {
            var status=probe.initialization().toCompletableFuture().get(15,TimeUnit.SECONDS);report.put("device",status);
            if(!status.available())throw new AssertionError(status.reason());
            var fixture=new RenderFixtures();fixture.png("test:tile",2,2,0xffff0000,0xff00ff00,0xff0000ff,0xffffffff);fixture.cube("test:cube","test:tile");fixture.block("test:block","test:cube");var assets=fixture.generation(1);
            int cellsChecked=0,raysChecked=0,hitsChecked=0;double maxT=0,maxUv=0;
            for(int x:new int[]{0,-17,4_100_000,256}) {
                int z=-17;var scene=x==256?denseScene(assets,x,z):GpuSceneSerializerTest.scene(assets,x,z);var camera=GpuSceneSerializerTest.camera(x,z);var settings=GpuSceneSerializerTest.settings();
                long start=System.nanoTime();
                try(var packed=GpuSceneSerializer.serialize(scene,camera,settings,config,memory)) {
                    long serialized=System.nanoTime()-start;long expectedHost=memory.snapshot().live();
                    int[] queries=new int[3003];var random=new Random(620);
                    for(int i=0;i<1000;i++){queries[i*3]=x+random.nextInt(9)-4;queries[i*3+1]=random.nextInt(5);queries[i*3+2]=z+random.nextInt(9)-4;}
                    queries[3000]=x+1000;queries[3001]=0;queries[3002]=z;
                    for(int repeat=0;repeat<20;repeat++) {
                        var batch=probe.probeCandidates(packed,queries).toCompletableFuture().get(15,TimeUnit.SECONDS);int[] rows=batch.rows();
                        for(int i=0;i<queries.length/3;i++) {
                            int[] expected;
                            try{expected=packed.candidates(queries[i*3],queries[i*3+1],queries[i*3+2]);}
                            catch(SceneException missing){if(rows[i*4]!=1)throw new AssertionError("Missing section not preserved");cellsChecked++;continue;}
                            int hash=0x811c9dc5;for(int id:expected)hash=(hash^id)*16777619;
                            if(rows[i*4]!=0 || rows[i*4+1]!=expected.length || rows[i*4+2]!=hash || rows[i*4+3]!=(expected.length==0?-1:expected[0]))throw new AssertionError("Candidate mismatch at "+i+" "+Arrays.toString(Arrays.copyOfRange(rows,i*4,i*4+4)));
                            cellsChecked++;
                        }
                        if(probe.buffers().live()!=0 || memory.snapshot().live()!=expectedHost)throw new AssertionError("Candidate allocation leaked");
                    }
                    var rayList=new ArrayList<Ray>();
                    for(int i=0;i<1000;i++)rayList.add(camera.ray(random.nextInt(128),random.nextInt(128),.5,.5));
                    for(var origin:List.of(new Vec3(x+.5,.5,z+.5),new Vec3(x,0,z),new Vec3(x-1,0,z-1),new Vec3(x+1,1,z+1)))
                        for(var dir:List.of(new Vec3(1,0,0),new Vec3(-1,-0.0,0),new Vec3(0,1,0),new Vec3(0,-1,0),new Vec3(0,0,1),new Vec3(0,0,-1),new Vec3(1,0,1),new Vec3(1,1,1),new Vec3(-1,-1,-1)))rayList.add(new Ray(origin,dir));
                    float[] rays=new float[rayList.size()*8];var h=packed.ints(GpuScene.BufferKind.HEADER_INT);int bx=h.get(12),by=h.get(13),bz=h.get(14);
                    for(int i=0;i<rayList.size();i++){var ray=rayList.get(i);int at=i*8;rays[at]=(float)(ray.origin().x()-bx);rays[at+1]=(float)(ray.origin().y()-by);rays[at+2]=(float)(ray.origin().z()-bz);rays[at+4]=(float)ray.direction().x();rays[at+5]=(float)ray.direction().y();rays[at+6]=(float)ray.direction().z();rays[at+7]=8;}
                    var tracer=new VoxelTracer(scene,settings.traceLimits());
                    var selected=new ArrayList<Integer>();for(int i=0;i<64;i++)selected.add(i);for(int i=1000;i<rayList.size();i++)selected.add(i);
                    float[] listRays=new float[selected.size()*8];for(int i=0;i<selected.size();i++)System.arraycopy(rays,selected.get(i)*8,listRays,i*8,8);
                    var lists=probe.probeHitLists(packed,listRays).toCompletableFuture().get(15,TimeUnit.SECONDS);
                    var listIds=lists.ids();var listValues=lists.hits();var counts=lists.counts();
                    for(int query=0;query<selected.size();query++) {
                        var expected=tracer.trace(rayList.get(selected.get(query)),8,()->false);
                        if(counts[query*2]!=0 || counts[query*2+1]!=expected.size())throw new AssertionError("List size/error mismatch "+query);
                        var order=new ArrayList<Integer>();for(int hit=0;hit<expected.size();hit++)order.add(query*64*4+hit*4);
                        order.sort(Comparator.<Integer>comparingDouble(at->listValues[at]).thenComparingInt(at->listIds[at]).thenComparingInt(at->listIds[at+1]).thenComparingInt(at->listIds[at+3]).thenComparingInt(at->listIds[at+2]));
                        for(int i=0;i<expected.size();i++) {
                            var hit=expected.get(i);int at=order.get(i);
                            if(listIds[at]!=hit.instance().id() || listIds[at+1]!=hit.modelIndex() || listIds[at+2]!=hit.quadIndex() || Math.abs(listValues[at]-hit.t())>1e-4 || Math.abs(listValues[at+1]-hit.u())>1e-4 || Math.abs(listValues[at+2]-hit.v())>1e-4)throw new AssertionError("Full hit list mismatch query="+query+" hit="+i);
                        }
                    }
                    report.put("fullHitListsCompared",true);
                    for(int repeat=0;repeat<20;repeat++) {
                        var batch=probe.probeRays(packed,rays).toCompletableFuture().get(15,TimeUnit.SECONDS);var ids=batch.ids();var values=batch.hits();
                        for(int i=0;i<rayList.size();i++) {
                            var expected=tracer.trace(rayList.get(i),8,()->false);int at=i*4;
                            if(ids[at]!=0)throw new AssertionError("Trace error "+ids[at]+" at "+i);
                            if((int)values[at+3]!=expected.size())throw new AssertionError("Hit count mismatch "+i+" expected "+expected.size()+" got "+values[at+3]);
                            if(expected.isEmpty()){if(ids[at+1]!=-1)throw new AssertionError("Unexpected hit "+i);}
                            else {var hit=expected.getFirst();if(ids[at+1]!=hit.instance().id() || ids[at+2]!=hit.modelIndex() || ids[at+3]!=hit.quadIndex())throw new AssertionError("Hit identity mismatch "+i+" expected "+hit+" got "+Arrays.toString(Arrays.copyOfRange(ids,at,at+4)));
                                double t=Math.abs(values[at]-hit.t()),uv=Math.max(Math.abs(values[at+1]-hit.u()),Math.abs(values[at+2]-hit.v()));maxT=Math.max(maxT,t);maxUv=Math.max(maxUv,uv);
                                if(t>1e-4 || uv>1e-4)throw new AssertionError("Hit precision mismatch "+i+" t="+t+" uv="+uv);hitsChecked++;}
                            raysChecked++;
                        }
                        if(probe.buffers().live()!=0 || memory.snapshot().live()!=expectedHost)throw new AssertionError("Trace allocation leaked");
                        samples.add(Map.of("baseX",x,"repeat",repeat,"serializeNanos",serialized,"traceUploadKernelReadNanos",batch.wallNanos(),"sceneGpuBytes",packed.metadata().gpuBytes()));
                    }
                }
                if(memory.snapshot().live()!=0)throw new AssertionError("Scene staging leaked");
            }
            var normal=GpuSceneSerializerTest.settings();
            var limited=new CpuPhotoRenderer.Settings(normal.distance(),normal.samples(),normal.filter(),normal.directionalLight(),normal.ambientFloor(),normal.cutoutThreshold(),normal.maxTransparentLayers(),normal.backgroundRgb(),normal.animationTicks(),new VoxelTracer.Limits(10000,1000,1),Map.of());
            var limitScene=GpuSceneSerializerTest.scene(assets,0,-17);
            try(var packed=GpuSceneSerializer.serialize(limitScene,GpuSceneSerializerTest.camera(0,-17),limited,config,memory)) {
                var result=probe.probeRays(packed,new float[]{.5f,.5f,0,0,0,0,1,8}).toCompletableFuture().get(15,TimeUnit.SECONDS);
                if(result.ids()[0]!=4)throw new AssertionError("Native face-test limit was not enforced");
            }
            if(probe.buffers().live()!=0 || memory.snapshot().live()!=0)throw new AssertionError("Limit rejection leaked resources");
            report.put("nativeWorkLimitVerified",true);
            report.put("cellsChecked",cellsChecked);report.put("raysChecked",raysChecked);report.put("hitsChecked",hitsChecked);report.put("maxDistanceError",maxT);report.put("maxUvError",maxUv);report.put("samples",samples);report.put("buffersBeforeClose",probe.buffers());report.put("status","PASS");
        }catch(Throwable failure){report.put("status","FAIL");report.put("error",failure.toString());throw failure;}
        finally{probe.close();probe.whenClosed().toCompletableFuture().get(15,TimeUnit.SECONDS);report.put("hostAfterClose",memory.snapshot());Files.writeString(out.resolve("report.json"),new GsonBuilder().setPrettyPrinting().create().toJson(report)+"\n");System.out.println(out.resolve("report.json"));}
    }
    private static SceneSnapshot denseScene(AssetGeneration assets,int x,int z) {
        var builder=new SceneSnapshot.Builder(assets,0,32,1000,100000);
        for(int cx=(x>>4)-1;cx<=(x>>4)+1;cx++)for(int cz=(z>>4)-1;cz<=(z>>4)+1;cz++)for(int cy=0;cy<2;cy++)builder.availableSection(cx,cy,cz);
        for(int dx=-6;dx<=6;dx++)for(int dz=0;dz<=12;dz++)for(int y=0;y<(dz==10?5:1);y++)
            builder.put(x+dx,y,z+dz,RenderFixtures.vanilla("test:block",assets.id()),new SceneSnapshot.Lighting(15,0,0xffffff),0);
        return builder.build();
    }
}
