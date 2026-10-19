package com.wenkrang.famara.render.asset;

import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

class ZipNameIndexTest {
    @Test void reservesCompactArrayBeforeAllocationAndDetectsRepeatedFingerprints() {
        var charged=new AtomicLong();var names=new ZipNameIndex(200000,charged::set);
        assertEquals(4194368,charged.get());
        for(int i=0;i<200000;i++)assertTrue(names.add("assets/nameplates/textures/font/nameplates/entry_"+i+".png"));
        assertFalse(names.add("assets/nameplates/textures/font/nameplates/entry_17.png"));
    }
    @Test void fingerprintCollisionsRequireCallerVerificationIncludingZeroSentinel() {
        var names=new ZipNameIndex(5,n->{},s->0);
        assertTrue(names.add("a"));assertFalse(names.add("b"));assertFalse(names.add("a"));
    }
    @Test void rejectedReservationNeverAttemptsTheArrayAllocation() {
        assertThrows(IllegalStateException.class,()->new ZipNameIndex(200000,n->{throw new IllegalStateException("预算拒绝");}));
        assertThrows(IllegalArgumentException.class,()->new ZipNameIndex(-1,n->fail()));
    }
}
