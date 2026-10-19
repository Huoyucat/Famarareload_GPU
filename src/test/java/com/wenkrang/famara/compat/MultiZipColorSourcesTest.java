package com.wenkrang.famara.compat;

import com.wenkrang.famara.render.asset.RenderFixtures;
import com.wenkrang.famara.render.asset.ZipDirectoryBudget;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import static org.junit.jupiter.api.Assertions.*;

class MultiZipColorSourcesTest {
    @TempDir Path root;
    private static final long PRODUCTION_NON_CATALOG=18701664;
    private Path zip(String filename,int unrelated,Map<String,byte[]> files)throws Exception {
        Path path=root.resolve(filename);
        try(var output=new ZipOutputStream(Files.newOutputStream(path))) {
            for(var entry:files.entrySet()){output.putNextEntry(new ZipEntry(entry.getKey()));output.write(entry.getValue());output.closeEntry();}
            String prefix="assets/noise/textures/font/"+"padding_".repeat(20);
            for(int i=0;i<unrelated;i++){output.putNextEntry(new ZipEntry(prefix+i+".png"));output.closeEntry();}
        }
        return path;
    }
    @Test void largeZipCatalogsDoNotAccumulateBesideTheProductionNonCatalogWorkspace()throws Exception {
        var high=new RenderFixtures();high.json("assets/test/items/panel.json","{\"model\":{\"type\":\"model\",\"model\":\"test:item/panel\"}}");
        high.json("assets/test/models/item/panel.json","{\"parent\":\"lower:entity/parent\"}");high.png("nameplates:font/shared",1,1,0xff2244aa);
        var low=new RenderFixtures();low.json("assets/lower/models/entity/parent.json","{\"textures\":{\"layer0\":\"nameplates:font/shared\"}}");low.png("nameplates:font/shared",1,1,0xffee1100);
        Path a=zip("high.zip",40000,high.files),b=zip("low.zip",30000,low.files);
        long ca=ZipDirectoryBudget.estimatedBytes(a),cb=ZipDirectoryBudget.estimatedBytes(b);
        assertTrue(PRODUCTION_NON_CATALOG+ca+cb>48L*1024*1024,"测试必须使旧累加目录实现失败");
        var work=new ColorWorkBudget();work.retain("production-non-catalog",PRODUCTION_NON_CATALOG);
        long peak;
        try(var sources=CePackSources.open(List.of(a,b),()->true,work,Set.of("assets/test/items/panel.json","assets/test/models/item/panel.json"))) {
            assertEquals(0,work.bytes("zip-catalog"),"扫描后没有ZIP中央目录仍常驻");
            var parser=PackColorIndex.cursor(sources.source(),key->null,work);while(!parser.step(128,Long.MAX_VALUE,System::nanoTime,()->true)){}
            assertEquals(0x2244aa,parser.result().itemColor("test:panel",PackColorIndex.ItemState.EMPTY));assertTrue(parser.result().issues().isEmpty(),()->parser.result().issues().toString());
            for(int i=0;i<4;i++) {
                assertArrayEquals(high.files.get("assets/test/items/panel.json"),sources.source().read("assets/test/items/panel.json"));
                assertArrayEquals(low.files.get("assets/lower/models/entity/parent.json"),sources.source().read("assets/lower/models/entity/parent.json"));
                assertArrayEquals(high.files.get("assets/nameplates/textures/font/shared.png"),sources.source().read("assets/nameplates/textures/font/shared.png"));
                assertNull(sources.source().read("assets/absent/models/missing.json"));
                assertTrue(work.bytes("zip-catalog")<=Math.max(ca,cb));
            }
            peak=work.peak();assertTrue(peak<=ColorWorkBudget.MAXIMUM);var metrics=sources.metrics();
            assertEquals(1,metrics.peakOpenZipFiles());assertTrue(metrics.openZipFiles()<=1);assertEquals(Math.max(ca,cb),metrics.catalogPeakBytes());assertEquals(ca+cb,metrics.catalogTotalBytes());
            assertTrue(metrics.membershipBytes()<1048576);assertTrue(metrics.zipOpenings()>2);
            long opened=metrics.zipOpenings();assertNull(sources.source().read("assets/absent/models/completely_missing.json"));assertEquals(opened,sources.metrics().zipOpenings(),"过滤器阴性不能导致遍历重开所有ZIP");
            System.out.println("MULTI_ZIP sources=2 baseline="+PRODUCTION_NON_CATALOG+" catalogSum="+(ca+cb)+" largestCatalog="+Math.max(ca,cb)+" peak="+peak+" maxOpen="+metrics.peakOpenZipFiles()+" memberships="+metrics.membershipBytes()+" openings="+metrics.zipOpenings()+" color=2244aa");
        }
        assertEquals(0,work.bytes("zip-catalog"));assertEquals(0,work.bytes("index"));assertEquals(0,work.bytes("zip-membership"));
        Files.delete(a);Files.delete(b);
    }
    @Test void filterFalsePositiveStillFallsThroughToExactLookupInTheNextLayer()throws Exception {
        Path a=zip("false-positive.zip",0,Map.of("assets/other/models/unrelated.json",new byte[]{1}));String target="assets/test/models/parent.json";byte[] expected={2,3,4};Path b=zip("real.zip",0,Map.of(target,expected));
        var work=new ColorWorkBudget();
        try(var sources=CePackSources.open(List.of(a,b),()->true,work,Set.of())) {
            var layers=CePackSources.class.getDeclaredField("layers");layers.setAccessible(true);Object first=((List<?>)layers.get(sources)).getFirst();var membership=first.getClass().getDeclaredField("membership");membership.setAccessible(true);Object filter=membership.get(first);
            var array=ZipPathFilter.class.getDeclaredField("bits");array.setAccessible(true);Arrays.fill((long[])array.get(filter),-1L);
            assertArrayEquals(expected,sources.source().read(target));assertEquals(1,sources.metrics().peakOpenZipFiles());assertEquals(4,sources.metrics().zipOpenings());
        }
        assertEquals(0,work.retainedBytes());Files.delete(a);Files.delete(b);
    }
    @Test void cancelledMidScanAndDuplicateCloseReleaseCatalogFilterAndPrivateCopy()throws Exception {
        Path path=zip("cancel.zip",10,Map.of("assets/test/items/a.json",new byte[]{1}));var current=new java.util.concurrent.atomic.AtomicBoolean(true);var work=new ColorWorkBudget();
        try(var sources=CePackSources.begin(List.of(path),current::get,work,name->true)) {
            while(sources.metrics().openZipFiles()==0)assertFalse(sources.step(1,Long.MAX_VALUE,System::nanoTime));
            assertTrue(work.bytes("zip-catalog")>0);assertTrue(work.bytes("zip-membership")>0);current.set(false);
            assertThrows(java.util.concurrent.CancellationException.class,()->sources.step(1,Long.MAX_VALUE,System::nanoTime));sources.close();sources.close();assertEquals(0,sources.metrics().openZipFiles());assertEquals(0,work.retainedBytes());
        }
        Files.delete(path);
    }
    @Test void rejectedSingleCatalogReportsTheSourceAndNeverSilentlyUsesLowerPack()throws Exception {
        Path path=zip("too-much-catalog.zip",2,Map.of("assets/test/items/a.json",new byte[]{1}));var work=new ColorWorkBudget();work.retain("persistent-data",ColorWorkBudget.RETAINED-100);
        var error=assertThrows(java.util.concurrent.RejectedExecutionException.class,()->CePackSources.open(List.of(path),()->true,work,Set.of()));
        assertTrue(error.getMessage().contains("too-much-catalog.zip"));assertTrue(error.getMessage().contains("单包目录="));assertEquals(0,work.bytes("zip-catalog"));assertEquals(0,work.bytes("zip-membership"));assertEquals(ColorWorkBudget.RETAINED-100,work.retainedBytes());
        Files.delete(path);
    }
    @Test void corruptUpperSourceCanBeDiscardedWithoutLeavingDirectoryOrFilterDebt()throws Exception {
        Path bad=root.resolve("bad.zip");Files.writeString(bad,"broken zip");String target="assets/test/models/parent.json";Path good=zip("good.zip",0,Map.of(target,new byte[]{7}));var work=new ColorWorkBudget();
        try(var sources=CePackSources.open(List.of(bad,good),()->true,work,Set.of())) {
            assertEquals(0,work.bytes("zip-catalog"));assertFalse(sources.issues().isEmpty());assertArrayEquals(new byte[]{7},sources.source().read(target));assertEquals(1,sources.metrics().peakOpenZipFiles());
        }
        assertEquals(0,work.retainedBytes());Files.delete(bad);Files.delete(good);
    }

}
