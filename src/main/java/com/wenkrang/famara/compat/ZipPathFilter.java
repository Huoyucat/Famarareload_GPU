package com.wenkrang.famara.compat;

import java.util.function.LongConsumer;
import java.util.function.ToLongFunction;

/** 仅排除不存在的ZIP路径；阳性仍必须getEntry精确确认，碰撞不得决定资源存在性。 */
final class ZipPathFilter {
    private final long[] bits;
    private final int mask;
    private final ToLongFunction<String> hash;
    ZipPathFilter(int entries,LongConsumer reserve){this(entries,reserve,ZipPathFilter::fingerprint);}
    ZipPathFilter(int entries,LongConsumer reserve,ToLongFunction<String> hash) {
        if(entries<0 || entries>200000)throw new IllegalArgumentException("旧色ZIP路径过滤器目录项无效");
        int words=1;while(words*64L<entries*16L)words<<=1;
        reserve.accept(128L+words*8L);bits=new long[words];mask=words*64-1;this.hash=hash;
    }
    void add(String path){probe(path,true);}
    boolean mightContain(String path){return probe(path,false);}
    private boolean probe(String path,boolean add) {
        long value=hash.applyAsLong(path);int at=(int)value,step=(int)(value>>>32)|1;
        for(int i=0;i<7;i++,at+=step) {
            int bit=at&mask;long flag=1L<<(bit&63);
            if(add)bits[bit>>>6]|=flag;else if((bits[bit>>>6]&flag)==0)return false;
        }
        return true;
    }
    private static long fingerprint(String path) {
        long hash=0xcbf29ce484222325L;
        for(int i=0;i<path.length();i++){hash^=path.charAt(i);hash*=0x100000001b3L;}
        hash^=hash>>>33;hash*=0xff51afd7ed558ccdL;hash^=hash>>>33;hash*=0xc4ceb9fe1a85ec53L;return hash^(hash>>>33);
    }
}
