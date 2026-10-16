package com.wenkrang.famara.render.stage;

import com.wenkrang.famara.render.RenderContext;
import com.wenkrang.famara.render.RenderTemp;

public class LightColorStage implements RenderStage{
    /**
     * 获取命中点的亮度等级
     * @param ctx 渲染上下文
     * @param rt 渲染临时变量
     * @return 亮度等级
     */
    public static int getBlockLightLevel(RenderContext ctx, RenderTemp rt) {
        if (!rt.hit) return 0;
        if (rt.hitEntity != null) {
            // 实体取其中心位置的亮度
            double cx = 0, cy = 0, cz = 0;
            int n = 0;
            for (double[] box : rt.hitEntity.boxes()) {
                cx += (box[0] + box[3]) / 2;
                cy += (box[1] + box[4]) / 2;
                cz += (box[2] + box[5]) / 2;
                n++;
            }
            if (n > 0) {
                cx /= n;
                cy /= n;
                cz /= n;
                return ctx.snapshot.getLightLevel((int) Math.floor(cx), (int) Math.floor(cy), (int) Math.floor(cz));
            }
            return 0;
        }
        // 方块取相邻面上的亮度
        if (rt.hitFace != null) {
            return ctx.snapshot.getLightLevel(
                    rt.hitX + rt.hitFace.getModX(),
                    rt.hitY + rt.hitFace.getModY(),
                    rt.hitZ + rt.hitFace.getModZ());
        }
        return 0;
    }
    @Override
    public void render(RenderContext renderContext, RenderTemp rt) {
        // 改进光照计算，增加对比度
        float lightFactor = (float) getBlockLightLevel(renderContext, rt) / 15.0f;
        // 使用零GC优化的批量光照应用
        rt.color.applyLightFactor(0.3f, lightFactor);
    }
}
