package com.wenkrang.famara.compat;

import com.wenkrang.famara.render.schedule.RenderMemoryBudget;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ColorRootSelectorTest {
    @Test void largeModelViewBuildsOnlyExistingRootsAndDropsItsTemporaryIndexes() {
        var models=new LinkedHashMap<String,CeCompat.ItemModel>();
        for(int i=0;i<12000;i++)models.put("test:item_"+i,new CeCompat.ItemModel("bettermodel:item/generated_"+i,PackColorIndex.ItemState.EMPTY));
        var work=new ColorWorkBudget();var selector=new ColorRootSelector(Map.copyOf(models),work);int slices=0;
        while(!selector.step(128,Long.MAX_VALUE,System::nanoTime,()->true))slices++;
        assertTrue(slices>10);assertEquals(12000,selector.uniqueResources());
        assertTrue(work.bytes("root-index")>0);assertEquals(0,work.bytes("model-sharing"));assertTrue(work.bytes("item-model-view")<24L*1024*1024);
        assertTrue(selector.test("assets/test/items/item_11.json"));assertTrue(selector.test("assets/bettermodel/items/generated_11.json"));
        assertTrue(selector.test("assets/bettermodel/models/item/generated_11.json"));assertTrue(selector.test("assets/test/blockstates/stone.json"));
        assertFalse(selector.test("assets/bettermodel/items/not-a-ce-item.json"));selector.close();assertEquals(0,work.bytes("root-index"));
    }
    @Test void actualLegacyEnvelopeIs280MiBAndSeparateFromTheV2SharedLedger() {
        var v2=new RenderMemoryBudget(536870912);var separate=new RenderMemoryBudget(ColorWorkBudget.MAXIMUM);
        try(var full=v2.reserve("photos-and-assets",536870912);var build=separate.reserve("legacy-color-build",ColorWorkBudget.TRANSIENT)) {
            var work=new ColorWorkBudget(build::resize);work.retain("roots-and-results",256L*1024*1024);
            assertEquals(280L*1024*1024,work.peak());assertEquals(536870912,v2.snapshot().live());assertEquals(280L*1024*1024,separate.snapshot().live());
            assertThrows(java.util.concurrent.RejectedExecutionException.class,()->work.retain("overflow",1));
        }
        assertEquals(0,v2.snapshot().live());assertEquals(0,separate.snapshot().live());
    }
    @Test void resizingLegacyLeaseBeforeAdmittingDataNeverUsesTheV2Budget() {
        var v2=new RenderMemoryBudget(536870912);var separate=new RenderMemoryBudget(ColorWorkBudget.MAXIMUM);var v2Lease=v2.reserve("full",536870912);
        try(var build=separate.reserve("legacy-color-build",ColorWorkBudget.TRANSIENT)) {
            var work=new ColorWorkBudget(build::resize);assertThrows(java.util.concurrent.RejectedExecutionException.class,()->work.retain("roots",ColorWorkBudget.RETAINED+1));assertEquals(ColorWorkBudget.TRANSIENT,separate.snapshot().live());assertEquals(536870912,v2.snapshot().live());
        } finally{v2Lease.close();}
        assertEquals(0,separate.snapshot().live());assertEquals(0,v2.snapshot().live());
    }
}
