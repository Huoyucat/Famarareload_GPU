package com.wenkrang.famara.compat;

import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class RuntimeColorRootsTest {
    @Test void runtimeRootsIgnoreUnrelatedItemsButRecursiveParentsRemainReadableAndBounded()throws Exception {
        var work=new ColorWorkBudget();String item="assets/test/items/real.json",legacy="assets/test/models/item/real.json";AtomicInteger unwanted=new AtomicInteger(),parentReads=new AtomicInteger();byte[] modern="{\"model\":{\"type\":\"minecraft:model\",\"model\":\"test:item/real\"}}".getBytes(StandardCharsets.UTF_8);byte[] model="{\"textures\":{\"all\":\"minecraft:block/stone\"},\"parent\":\"test:shared\"}".getBytes(StandardCharsets.UTF_8);byte[] parent=new byte[1000000];Arrays.fill(parent,(byte)' ');byte[] empty="{}".getBytes(StandardCharsets.UTF_8);System.arraycopy(empty,0,parent,0,empty.length);
        var source=new PackColorIndex.PackSource(){public Collection<String> list(){return List.of(item,legacy,"assets/bettermodel/items/not-owned-by-ce.json");}public byte[] read(String path){if(path.contains("not-owned")){unwanted.incrementAndGet();throw new AssertionError("未引用BetterModel物品不应解析");}if(path.equals(item))return modern;if(path.equals(legacy))return model;if(path.equals("assets/test/models/shared.json")){parentReads.incrementAndGet();return parent;}return null;}};
        var mapping=Map.of("test:real",new CeCompat.ItemModel("test:real",PackColorIndex.ItemState.EMPTY));var roots=new ColorRootSelector(mapping,work);while(!roots.step(1,Long.MAX_VALUE,System::nanoTime,()->true)){}
        try(roots;var cache=new ColorSourceCache(source,key->null,roots,()->true,work,true)) {
            var cursor=PackColorIndex.cursor(cache,key->key.equals("STONE")?0x778899:null,work);while(!cursor.step(1,Long.MAX_VALUE,System::nanoTime,()->true)){}
            assertEquals(0x778899,cursor.result().itemColor("test:real",PackColorIndex.ItemState.EMPTY));assertEquals(0,unwanted.get());assertEquals(1,parentReads.get());assertTrue(work.peak()<=72L*1024*1024);
        }assertEquals(0,work.bytes("cache"));
    }
    @Test void multipleMegabyteRootsCanCrossTheOldCumulativeLimitWithoutRetainingEverySource()throws Exception {
        var work=new ColorWorkBudget();List<String> roots=new ArrayList<>();for(int i=0;i<24;i++)roots.add("assets/test/items/item_"+i+".json");byte[] json=new byte[1048576];Arrays.fill(json,(byte)' ');byte[] prefix="{\"model\":{\"type\":\"minecraft:empty\"}}".getBytes(StandardCharsets.UTF_8);System.arraycopy(prefix,0,json,0,prefix.length);
        var source=new PackColorIndex.PackSource(){public Collection<String> list(){return roots;}public byte[] read(String path){return path.contains("/items/")?json:null;}};
        try(var cache=new ColorSourceCache(source,key->null,new HashSet<>(roots),()->true,work)) {
            var cursor=PackColorIndex.cursor(cache,key->null,work);while(!cursor.step(1,Long.MAX_VALUE,System::nanoTime,()->true)){}
            assertEquals(24,cursor.result().items().size());assertTrue(cache.trafficBytes()>16L*1024*1024);assertTrue(cache.cachedBytes()<=4194304);assertTrue(work.peak()<=72L*1024*1024);
        }
    }
}
