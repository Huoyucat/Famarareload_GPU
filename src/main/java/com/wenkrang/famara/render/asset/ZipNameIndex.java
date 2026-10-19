package com.wenkrang.famara.render.asset;

import java.nio.charset.StandardCharsets;
import java.util.function.LongConsumer;
import java.util.function.ToLongFunction;

/** 只保存固定宽度指纹。相同指纹必须由调用者对不可变ZIP分片复核完整名称。 */
final class ZipNameIndex {
    private final long[] slots;
    private final ToLongFunction<String> fingerprint;
    ZipNameIndex(int entries,LongConsumer reserve){this(entries,reserve,null);}
    ZipNameIndex(int entries,LongConsumer reserve,ToLongFunction<String> fingerprint) {
        if(entries<0 || entries>1<<28)throw new IllegalArgumentException("ZIP名称索引数量无效");
        int capacity=2;while(capacity<entries*2L)capacity<<=1;
        reserve.accept(64L+capacity*8L);slots=new long[capacity];this.fingerprint=fingerprint==null?sha256():fingerprint;
    }
    boolean add(String name) {
        long hash=fingerprint.applyAsLong(name);if(hash==0)hash=1;
        int at=(int)(hash^(hash>>>32))&(slots.length-1);
        for(int probes=0;probes<slots.length;probes++) {
            if(slots[at]==0){slots[at]=hash;return true;}
            if(slots[at]==hash)return false;at=(at+1)&(slots.length-1);
        }
        throw new IllegalStateException("ZIP名称索引超过预付容量");
    }
    private static ToLongFunction<String> sha256() {
        var digest=ResourcePack.sha256();
        return name->{byte[] bytes=digest.digest(name.getBytes(StandardCharsets.UTF_8));long value=0;for(int i=0;i<8;i++)value=(value<<8)|(bytes[i]&255L);return value;};
    }
}
