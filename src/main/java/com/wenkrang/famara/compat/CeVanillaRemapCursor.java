package com.wenkrang.famara.compat;

import com.wenkrang.famara.render.asset.CanonicalBlockState;
import java.util.*;
import java.util.function.*;

/** 按注册表ID分批复制CE真正发给原版客户端的映射；只记录变化，不猜测模型规则。 */
final class CeVanillaRemapCursor implements CeCompat.VisualCursor {
    private final int count,registrySize;
    private final IntUnaryOperator target;
    private final IntFunction<String> state;
    private int index;
    private boolean failed;
    CeVanillaRemapCursor(int count,int registrySize,IntUnaryOperator target,IntFunction<String> state) {
        if(count<0 || registrySize<count)throw new IllegalArgumentException("CE方块注册表范围无效");
        this.count=count;this.registrySize=registrySize;this.target=Objects.requireNonNull(target);this.state=Objects.requireNonNull(state);
    }
    @Override public List<CeCompat.VisualState> next(int maximum) {
        if(maximum<=0 || failed)throw new IllegalStateException("CE原版映射游标已失败或步长无效");
        List<CeCompat.VisualState> rows=new ArrayList<>();
        try {
            for(int work=0;work<maximum && index<count;work++) {
                int source=index++,visible=target.applyAsInt(source);
                if(visible<0 || visible>=registrySize)throw new IllegalStateException("CE原版客户端映射目标无效："+source+" -> "+visible);
                if(source==visible)continue;
                String server=Objects.requireNonNull(state.apply(source)),client=Objects.requireNonNull(state.apply(visible));
                String block=CanonicalBlockState.parse(server).block().toString();
                rows.add(new CeCompat.VisualState(server,block,block,server,client,false));
            }
            return List.copyOf(rows);
        } catch(RuntimeException | Error error){failed=true;throw error;}
    }
    @Override public boolean done(){return index>=count;}
}
