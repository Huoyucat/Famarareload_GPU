package com.wenkrang.famara.render.platform;

import com.wenkrang.famara.compat.CeCompat;
import java.util.*;
import java.util.function.LongConsumer;

/** 只由冻结owner或转换owner持有；本代字符串共享，不使用全局intern或跨代缓存。 */
final class FrozenVisualBuffer {
    private final ArrayDeque<CeCompat.VisualState> rows=new ArrayDeque<>();
    private Map<String,String> strings=new HashMap<>();
    private long bytes=1024,peak=1024;
    private int count;
    void add(CeCompat.VisualState state,int maximumStates,long maximumBytes,LongConsumer reserve) {
        if(strings==null)throw new IllegalStateException("CE冻结缓冲已封存");
        Objects.requireNonNull(state.serverState());Objects.requireNonNull(state.visualState());
        String[] fields={state.serverState(),state.material(),state.id(),state.vanillaState(),state.visualState()};long next=bytes+128;
        for(int i=0;i<fields.length;i++) {
            String value=fields[i];if(value==null || strings.containsKey(value))continue;
            boolean duplicate=false;for(int j=0;j<i;j++)if(value.equals(fields[j])){duplicate=true;break;}
            if(!duplicate)next=Math.addExact(next,128L+value.length()*4L);
        }
        if(count>=maximumStates)throw new IllegalStateException("CE视觉冻结条目超过显式限额：阶段=冻结，条目="+((long)count+1)+"，上限="+maximumStates+"，冻结字节="+next+"，字节上限="+maximumBytes);
        if(next>maximumBytes)throw new IllegalStateException("CE视觉冻结字节超过显式限额：阶段=冻结，条目="+((long)count+1)+"，条目上限="+maximumStates+"，冻结字节="+next+"，上限="+maximumBytes);
        reserve.accept(next);
        rows.addLast(new CeCompat.VisualState(shared(fields[0]),shared(fields[1]),shared(fields[2]),shared(fields[3]),shared(fields[4]),state.customBlock()));
        bytes=next;peak=Math.max(peak,bytes);count++;
    }
    private String shared(String value){if(value==null)return null;String old=strings.putIfAbsent(value,value);return old==null?value:old;}
    void seal(){strings=null;}
    CeCompat.VisualState remove(){var state=rows.removeFirst();bytes-=128;return state;}
    boolean isEmpty(){return rows.isEmpty();}
    int count(){return count;}
    long bytes(){return bytes;}
    long peak(){return peak;}
}
