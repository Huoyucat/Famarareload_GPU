package com.wenkrang.famara.render.scene;

import com.wenkrang.famara.render.asset.*;

/** 有界流体对象图上界；按32B对象头、8B引用、16B对齐保守计，非物理堆实测。 */
public final class FluidMemory {
    private FluidMemory() {}
    public static long peak(int hostModels){return Math.addExact(32768L,Math.multiplyExact(64L,hostModels+1L));}
    public static long retained(BakedModel model,int hostModels) {
        if(model.quads().isEmpty())return 0;
        if(model.quads().size()>12)throw new IllegalStateException("流体网格超过已证明的12三角形上界");
        // model/bounds/quad容器/模型key；最终宿主models容器与替换Instance。
        long bytes=1024L+256L+8L*(hostModels+1);
        for(BakedQuad quad:model.quads()) {
            if(quad.topology()!=BakedQuad.Topology.TRIANGLE)throw new IllegalStateException("流体拓扑计量不匹配");
            // quad 160；两个4槽容器/引用数组各128；四槽Vec3按64、Uv按48、normal64。
            // 有意重复算共享顶点及贴图key，使不压缩引用也不低估；不按静态宿主网格收费。
            bytes=Math.addExact(bytes,160L+256+4*64+4*48+64+512+4L*(quad.texture().namespace().length()+quad.texture().path().length()));
        }
        return bytes;
    }
}
