package com.wenkrang.famara.render.platform;

import com.wenkrang.famara.compat.CeCompat;
import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

class FrozenVisualBufferTest {
    private CeCompat.VisualState row(String server){return new CeCompat.VisualState(server,new String("minecraft:note_block"),new String("test:logical"),null,new String("minecraft:stone"));}
    @Test void sharedStringsAreLocalToTheBuildAndLimitsAreCheckedBeforeReservation() {
        var buffer=new FrozenVisualBuffer();var reserved=new AtomicLong();buffer.add(row("craftengine:custom_1"),2,100000,reserved::set);long one=reserved.get();
        buffer.add(row("craftengine:custom_2"),2,100000,reserved::set);assertTrue(reserved.get()-one<500);buffer.seal();
        var a=buffer.remove();var b=buffer.remove();assertSame(a.id(),b.id());assertSame(a.material(),b.material());assertSame(a.visualState(),b.visualState());assertTrue(buffer.isEmpty());
        assertThrows(IllegalStateException.class,()->buffer.add(row("craftengine:custom_3"),10,100000,n->fail()));
    }
    @Test void reportsStatesAndBytesSeparatelyWithoutRetainingRejectedInput() {
        var states=new FrozenVisualBuffer();states.add(row("craftengine:a"),1,100000,n->{});
        var error=assertThrows(IllegalStateException.class,()->states.add(row("craftengine:b"),1,100000,n->fail()));assertTrue(error.getMessage().contains("条目=2"));assertTrue(error.getMessage().contains("上限=1"));assertEquals(1,states.count());
        var bytes=new FrozenVisualBuffer();error=assertThrows(IllegalStateException.class,()->bytes.add(row("craftengine:a"),100,1024,n->fail()));assertTrue(error.getMessage().contains("冻结字节="));assertTrue(error.getMessage().contains("上限=1024"));assertEquals(0,bytes.count());
    }
}
