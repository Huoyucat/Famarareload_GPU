package com.wenkrang.famara.render.stage;

import com.wenkrang.famara.render.RenderContext;
import com.wenkrang.famara.render.RenderTemp;

/** 逐通道钳制的棋盘颜色遮罩。 */
public class MaskColorStage implements RenderStage {
    @Override
    public void render(RenderContext renderContext, RenderTemp rt) {
        int delta = (rt.x % 2 == rt.y % 2) ? -2 : 2;
        rt.color.addRGB(delta, delta, delta);
    }
}
