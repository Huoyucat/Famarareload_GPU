package com.wenkrang.famara.compat;

import java.util.*;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.LongConsumer;

/** 旧色独立封套：256MiB存活数据＋24MiB解码/解析窗口，不占V2共享账本。 */
final class ColorWorkBudget {
    static final long RETAINED=256L*1024*1024,TRANSIENT=24L*1024*1024,MAXIMUM=RETAINED+TRANSIENT;
    private static final long JSON_MAXIMUM=12L*1024*1024;
    private final Map<String,Long> domains=new HashMap<>();
    private final LongConsumer resize;
    private long retained,peak=TRANSIENT,jsonBytes;
    ColorWorkBudget(){this(n->{});}
    ColorWorkBudget(LongConsumer resize){this.resize=Objects.requireNonNull(resize);resize.accept(TRANSIENT);}
    void retain(String domain,long bytes) {
        if(bytes<0 || bytes>RETAINED-retained)throw new RejectedExecutionException("旧取色存活工作集不足：域="+domain+"，追加="+bytes+"，保留="+retained+"，存活上限="+RETAINED+"，解码/解析窗口="+TRANSIENT+"，独立总封套="+MAXIMUM+"，域分解="+domains);
        resize.accept(TRANSIENT+retained+bytes);retained+=bytes;domains.merge(domain,bytes,Long::sum);peak=Math.max(peak,TRANSIENT+retained);
    }
    void release(String domain,long bytes) {
        long old=domains.getOrDefault(domain,0L);if(bytes<0 || bytes>old)throw new IllegalStateException("旧取色工作集释放不符："+domain);
        resize.accept(TRANSIENT+retained-bytes);if(old==bytes)domains.remove(domain);else domains.put(domain,old-bytes);retained-=bytes;
    }
    void drop(String domain){long bytes=bytes(domain);if(bytes>0)release(domain,bytes);}
    long available(){return RETAINED-retained;}
    long retainedBytes(){return retained;}
    Map<String,Long> domains(){return Map.copyOf(domains);}
    long bytes(String domain){return domains.getOrDefault(domain,0L);}
    long peak(){return peak;}
    long jsonRemaining(){return JSON_MAXIMUM-jsonBytes;}
    void parsed(long bytes){if(bytes<0 || bytes>jsonRemaining())throw new RejectedExecutionException("旧取色当前物品解析树超过12MiB临时窗口");jsonBytes+=bytes;}
    void decoding(int width,int height,int sourceBytes){long required=jsonBytes+sourceBytes+(long)width*height*8L+width*32L+1048576;if(required>TRANSIENT)throw new RejectedExecutionException("旧取色PNG/JSON临时峰值超过24MiB：请求="+required);}
    void endItem(){jsonBytes=0;}
}
