package com.wenkrang.famara.compat;

import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class CeVanillaRemapCursorTest {
    private static final String SOURCE="minecraft:oak_leaves[distance=4,persistent=false,waterlogged=false]";
    private static final String TARGET="minecraft:oak_leaves[distance=7,persistent=true,waterlogged=false]";
    @Test void identityRegistrySlotsYieldWithoutResolvingOrCopyingStates() {
        AtomicInteger reads=new AtomicInteger();
        var cursor=new CeVanillaRemapCursor(3,3,id->id,id->{reads.incrementAndGet();throw new AssertionError();});
        assertTrue(cursor.next(1).isEmpty());assertFalse(cursor.done());assertEquals(0,reads.get());
        assertTrue(cursor.next(1).isEmpty());assertFalse(cursor.done());assertTrue(cursor.next(1).isEmpty());assertTrue(cursor.done());
    }
    @Test void freezesExactClientTargetWithoutGuessingLeafProperties() {
        var cursor=new CeVanillaRemapCursor(2,2,id->1,id->id==0?SOURCE:TARGET);
        var row=cursor.next(1).getFirst();assertFalse(row.customBlock());assertEquals(SOURCE,row.serverState());assertEquals(TARGET,row.visualState());
        assertEquals("minecraft:oak_leaves",row.id());assertEquals(SOURCE,row.vanillaState());assertFalse(cursor.done());
        assertTrue(cursor.next(1).isEmpty());assertTrue(cursor.done());
    }
    @Test void invalidOrUnreadyNetworkTargetCannotTurnIntoAirOrAChosenVariant() {
        for(int target:new int[]{-1,2}) {
            var cursor=new CeVanillaRemapCursor(1,2,id->target,id->SOURCE);
            assertThrows(IllegalStateException.class,()->cursor.next(1));assertThrows(IllegalStateException.class,()->cursor.next(1));
        }
    }
}
