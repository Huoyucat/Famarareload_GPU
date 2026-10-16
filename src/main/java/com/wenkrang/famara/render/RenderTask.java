package com.wenkrang.famara.render;

import com.wenkrang.famara.lib.Sched;
import com.wenkrang.famara.lib.Translation;
import com.wenkrang.famara.render.lib.FaColor;
import com.wenkrang.famara.render.stage.*;

import javax.imageio.ImageIO;
import java.io.IOException;

import static com.wenkrang.famara.Famara.renderService;

/**
 * 渲染任务
 */
public class RenderTask {
    public final RenderPipe pipe;
    public final RenderContext ctx;
    private final RenderTemp rt;
    public volatile boolean handled = false;

    public boolean isHandled() {
        return handled;
    }

    /**
     * 区块快照是否已抓取完毕，完毕后才能开始渲染
     */
    public boolean isReady() {
        return ctx.snapshot.isReady();
    }

    /**
     * 将照片写入文件，在渲染线程（异步）上直接执行
     */
    public void write(){
        //写入照片
        try {
            ImageIO.write(ctx.image, "png", ctx.picture);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * 获取照片的平均亮度
     * @return 照片的平均亮度
     */
    public float getAverageBrightness() {
        float Brightness = 0;
        for (int x = 0; x < 128; x++) {
            for (int y = 0; y < 128; y++) {
                // 调用FaColor获取亮度的方法
                Brightness += FaColor.getBrightness(ctx.image.getRGB(x, y));
            }
        }
        // 取平均值
        return Brightness / (128 * 128);
    }

    public int applyColorContrast(int rgb, int contrast,int brightness) {
        // 获取各分量
        int r = (rgb >> 16) & 0xFF;
        int g = (rgb >> 8) & 0xFF;
        int b = rgb & 0xFF;

        int newR = (r - brightness) * (1 + contrast) + brightness;
        int newG = (g - brightness) * (1 + contrast) + brightness;
        int newB = (b - brightness) * (1 + contrast) + brightness;

        // 应用对比度公式
        return (255 << 24) | (newR << 16) | (newG << 8) | newB;
    }

    public void applyContrast(float contrast, float brightness) {
        for (int x = 0; x < 128; x++) {
            for (int y = 0; y < 128; y++) {
                ctx.image.setRGB(x, y, applyColorContrast(ctx.image.getRGB(x, y), (int) contrast, (int) brightness));
            }
        }
    }

    /**
     * 完成渲染任务
     */
    public void finish() {
        // 应用对比度
        applyContrast(0.5f, getAverageBrightness());
        // 写入照片
        write();
        // 释放区块快照
        ctx.snapshot.clear();
        // 输出错误信息，回到玩家所在的区域线程发送
        if (!rt.failedBlocks.isEmpty()) {
            com.wenkrang.famara.compat.CeCompat.writeDiagnostics(ctx.snapshot.colors);
            String message = Translation.CURRENT.of("renderError1") + " " + rt.failedBlocks;
            Sched.entity(ctx.player, () -> ctx.player.sendMessage(message));
        }
    }

    public RenderTask(RenderContext ctx) {
        this.ctx = ctx;

        // 初始化渲染临时变量
        rt = new RenderTemp();

        rt.x = 0;
        rt.y = 0;

        pipe = new RenderPipe(rt);

        // 添加渲染阶段
        pipe.addStage(new RayTraceStage())
                .addStage(new SkyColorStage())
                .addStage(new BlockFaceLightStage())
                .addStage(new LightColorStage())
                .addStage(new MaskColorStage());
    }

    public void updateTraceData() {
        // progress
        renderService.progress.merge(ctx.id, 1, Integer::sum);

        // render speed
        renderService.renderSpeeds.merge(ctx.id, 1, Integer::sum);
    }

    public void step() {
        // 重置渲染临时变量的所有状态
        rt.isSkipped = false;
        rt.hit = false;
        rt.hitType = null;
        rt.hitFace = null;
        rt.hitEntity = null;
        rt.color.set(0, 0, 0);

        pipe.render(ctx);

        rt.x++;
        // 当x坐标超出范围时，重置x坐标并增加y坐标
        if (rt.x >= 128) {
            rt.x = 0;
            rt.y++;
        }

        updateTraceData();

        handled = true;
    }

    /**
     * 检查渲染任务是否完成
     *
     * @return 当y坐标达到或超过128时返回true，否则返回false
     */
    public boolean isFinished(){
        return rt.y >= 128;
    }


}
