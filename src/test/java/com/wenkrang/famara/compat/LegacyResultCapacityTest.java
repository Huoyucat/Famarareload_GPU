package com.wenkrang.famara.compat;

import com.wenkrang.famara.render.schedule.RenderMemoryBudget;
import org.junit.jupiter.api.Test;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class LegacyResultCapacityTest {
    private static final long OLD_RETAINED=48L*1024*1024;
    private static final long PRODUCTION_RETAINED=50331182,PRODUCTION_RESULTS=6442420;
    @Test void productionDomainBreakdownAdmitsTheFailedResultAndHasRoomFor128MiBOfResults() {
        var v2=new RenderMemoryBudget(536870912);var separate=new RenderMemoryBudget(ColorWorkBudget.MAXIMUM);
        assertEquals(256L*1024*1024,ColorWorkBudget.RETAINED);assertEquals(24L*1024*1024,ColorWorkBudget.TRANSIENT);assertEquals(280L*1024*1024,ColorWorkBudget.MAXIMUM);
        try(var full=v2.reserve("full-v2",536870912);var build=separate.reserve("legacy-color-build",ColorWorkBudget.TRANSIENT)) {
            var work=new ColorWorkBudget(build::resize);
            var domains=Map.ofEntries(Map.entry("cache",4194212L),Map.entry("model-colors",623680L),Map.entry("source-issues",1964L),Map.entry("source-meta",9216L),Map.entry("index",6359176L),Map.entry("zip-membership",526336L),Map.entry("zip-catalog",18732142L),Map.entry("root-index",3039900L),Map.entry("item-model-view",9291920L),Map.entry("issues",630972L),Map.entry("texture-colors",479244L),Map.entry("results",PRODUCTION_RESULTS));
            domains.forEach(work::retain);assertEquals(PRODUCTION_RETAINED,work.retainedBytes());assertTrue(work.retainedBytes()+512>OLD_RETAINED);
            work.retain("results",512);work.retain("results",128L*1024*1024-work.bytes("results"));
            assertEquals(128L*1024*1024,work.bytes("results"));assertEquals(PRODUCTION_RETAINED-PRODUCTION_RESULTS+128L*1024*1024,work.retainedBytes());assertTrue(work.peak()<ColorWorkBudget.MAXIMUM);assertEquals(536870912,v2.snapshot().live());
            System.out.println("LEGACY_280_PRODUCTION baseline="+PRODUCTION_RETAINED+" failedAppend=512 results="+work.bytes("results")+" retained="+work.retainedBytes()+" peak="+work.peak()+" maximum="+ColorWorkBudget.MAXIMUM);
            work.retain("capacity-boundary",work.available());assertEquals(ColorWorkBudget.MAXIMUM,separate.snapshot().live());var before=work.domains();
            assertThrows(java.util.concurrent.RejectedExecutionException.class,()->work.retain("overflow",1));assertEquals(before,work.domains());assertEquals(ColorWorkBudget.MAXIMUM,separate.snapshot().live());
            for(String domain:List.copyOf(work.domains().keySet()))work.drop(domain);assertEquals(ColorWorkBudget.TRANSIENT,separate.snapshot().live());
        }
        assertEquals(0,separate.snapshot().live());assertEquals(0,v2.snapshot().live());
    }
    @Test void aHundredThousandActualItemResultsFitBesideTheProductionNonResultWorkspace()throws Exception {
        int count=100000;var image=new BufferedImage(1,1,BufferedImage.TYPE_INT_ARGB);image.setRGB(0,0,0xff2244aa);var output=new ByteArrayOutputStream();assertTrue(ImageIO.write(image,"png",output));byte[] texture=output.toByteArray();
        byte[] item="{\"model\":{\"type\":\"minecraft:model\",\"model\":\"shared:entity/panel\"}}".getBytes(StandardCharsets.UTF_8),model="{\"textures\":{\"layer0\":\"shared:entity/panel\"}}".getBytes(StandardCharsets.UTF_8);
        var source=new PackColorIndex.PackSource() {
            public Collection<String> list(){return new AbstractList<>() {public int size(){return count;}public String get(int i){return "assets/test/items/result_"+i+".json";}};}
            public byte[] read(String path){if(path.startsWith("assets/test/items/"))return item;if(path.equals("assets/shared/models/entity/panel.json"))return model;if(path.equals("assets/shared/textures/entity/panel.png"))return texture;return null;}
        };
        var separate=new RenderMemoryBudget(ColorWorkBudget.MAXIMUM);
        try(var build=separate.reserve("legacy-color-build",ColorWorkBudget.TRANSIENT)) {
            var work=new ColorWorkBudget(build::resize);work.retain("production-non-results",PRODUCTION_RETAINED-PRODUCTION_RESULTS);
            var cursor=PackColorIndex.cursor(source,key->null,work);while(!cursor.step(512,Long.MAX_VALUE,System::nanoTime,()->true)){}
            var result=cursor.result();assertEquals(count,result.items().size());assertTrue(result.issues().isEmpty());assertTrue(work.bytes("results")>OLD_RETAINED);assertTrue(work.peak()<ColorWorkBudget.MAXIMUM);
            for(int i:new int[]{0,49999,99999})assertEquals(0x2244aa,result.itemColor("test:result_"+i,PackColorIndex.ItemState.EMPTY));
            System.out.println("LEGACY_280_RESULTS items="+count+" results="+work.bytes("results")+" retained="+work.retainedBytes()+" peak="+work.peak()+" color=2244aa");
        }
        assertEquals(0,separate.snapshot().live());
    }
}
