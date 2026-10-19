package com.wenkrang.famara.render.scene;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class VisualTableBuilderTest {
    @Test void repeatedAliasesShareParsedStatesAndPayloadWithoutChangingIdentity() {
        var builder=new VisualStateTable.Builder(1,100,1000000,n->{});
        builder.add("craftengine:custom_1","minecraft:note_block","test:logical",null,"minecraft:stone",true);
        long one=builder.estimatedRetainedBytes();builder.add("test:logical[variant=1]","minecraft:note_block","test:logical",null,"minecraft:stone",true);
        assertTrue(builder.estimatedRetainedBytes()-one<2000);
        builder.add("test:logical[variant=1]","minecraft:note_block","test:logical",null,"minecraft:stone",true);assertEquals(2,builder.size());
        long working=builder.estimatedWorkingBytes();var table=builder.finish();assertEquals(2,table.size());assertEquals(1,table.nativeSlotEntries());assertTrue(table.estimatedRetainedBytes()<working);
        assertThrows(IllegalStateException.class,builder::finish);assertThrows(IllegalStateException.class,()->builder.add("a","a","a",null,"a",true));assertThrows(UnsupportedOperationException.class,()->table.carrierMaterials().clear());
    }
    @Test void conflictOrRetainedLimitNeverSilentlyDropsAMapping() {
        var builder=new VisualStateTable.Builder(1,100,1000000,n->{});builder.add("craftengine:custom_1","minecraft:stone","test:a",null,"minecraft:stone",true);
        assertThrows(IllegalArgumentException.class,()->builder.add("craftengine:custom_1","minecraft:stone","test:b",null,"minecraft:dirt",true));
        var small=new VisualStateTable.Builder(1,100,5000,n->{});var error=assertThrows(IllegalStateException.class,()->small.add("craftengine:custom_1","minecraft:stone","test:a",null,"minecraft:stone",true));assertTrue(error.getMessage().contains("常驻字节="));assertTrue(error.getMessage().contains("上限=5000"));
    }
}
