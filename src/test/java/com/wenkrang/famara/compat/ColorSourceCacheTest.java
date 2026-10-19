package com.wenkrang.famara.compat;

import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.RejectedExecutionException;
import static org.junit.jupiter.api.Assertions.*;

class ColorSourceCacheTest {
    @Test void readTrafficBeyondSixteenMiBDoesNotBecomePermanentMemoryDebt()throws Exception {
        var work=new ColorWorkBudget();AtomicInteger reads=new AtomicInteger();var source=new PackColorIndex.PackSource(){public Collection<String> list(){return List.of("assets/test/items/real.json","assets/bettermodel/items/not-a-ce-item.json");}public byte[] read(String path){reads.incrementAndGet();return new byte[1048576];}};
        try(var cached=new ColorSourceCache(source,k->null,Set.of("assets/test/items/real.json"),()->true,work)) {
            assertEquals(List.of("assets/test/items/real.json"),new ArrayList<>(cached.list()));for(int i=0;i<64;i++)cached.read("assets/test/models/parent.json");assertEquals(1,reads.get());
            for(int i=0;i<24;i++)cached.read("assets/test/models/unique_"+i+".json");assertTrue(cached.trafficBytes()>16L*1024*1024);assertTrue(cached.cachedBytes()<=4194304);assertTrue(work.peak()<=ColorWorkBudget.MAXIMUM);
        }
        assertEquals(0,work.bytes("cache"));
    }
    @Test void actualRetainedRulesStillCannotExceedTheExpandedIndependentEnvelope() {
        var work=new ColorWorkBudget();work.retain("results",ColorWorkBudget.RETAINED);assertThrows(RejectedExecutionException.class,()->work.retain("results",1));assertEquals(ColorWorkBudget.MAXIMUM,work.peak());
    }
}
