package com.wenkrang.famara.compat;

import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

class ZipPathFilterTest {
    @Test void everyAddedPathRemainsPossibleAndTheArrayIsPrepaid() {
        var bytes=new AtomicLong();var filter=new ZipPathFilter(200000,bytes::set);assertEquals(524416,bytes.get());
        for(int i=0;i<200000;i++)filter.add("assets/test/textures/font/image_"+i+".png");
        for(int i=0;i<200000;i++)assertTrue(filter.mightContain("assets/test/textures/font/image_"+i+".png"));
        int negative=0;for(int i=0;i<1000;i++)if(!filter.mightContain("assets/absent/models/unknown_"+i+".json"))negative++;
        assertTrue(negative>900,"该过滤器必须有效减少无关ZIP重开，而不是仅全部放行");
    }
    @Test void collisionsAreOnlyFalsePositivesAndNeverAnExactResourceDecision() {
        var filter=new ZipPathFilter(10,n->{},path->0);filter.add("assets/a/models/a.json");
        assertTrue(filter.mightContain("assets/a/models/a.json"));assertTrue(filter.mightContain("assets/missing/models/b.json"));
    }
    @Test void reservationFailurePreventsAllocationAndEntryLimitRemainsBounded() {
        assertThrows(IllegalStateException.class,()->new ZipPathFilter(200000,n->{throw new IllegalStateException("refused");}));
        assertThrows(IllegalArgumentException.class,()->new ZipPathFilter(200001,n->fail()));
        var empty=new ZipPathFilter(0,n->{});assertFalse(empty.mightContain("assets/a/models/a.json"));
    }
}
