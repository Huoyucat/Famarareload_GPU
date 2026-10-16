package com.wenkrang.famara.render;


import com.wenkrang.famara.lib.Sched;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;

public class RenderRunner {
    public RenderService renderService;

    /** 防止异步定时任务重叠执行，渲染任务本身不是线程安全的 */
    private final AtomicBoolean running = new AtomicBoolean(false);

    public RenderRunner(RenderService renderService) {
        this.renderService = renderService;
    }

    public void Runner() {
        Random random = new Random();
        //TODO:取消竞态渲染
        // 渲染只读取区块快照，因此整个过程在异步线程进行，不占用区域线程
        Sched.asyncTimer(() -> {
            if (!running.compareAndSet(false, true)) return;
            try {
                // 只处理区块快照已抓取完毕的任务
                List<RenderTask> ready = new ArrayList<>();
                for (RenderTask task : renderService.tasks) {
                    if (task.isReady()) ready.add(task);
                }
                // 根据配置的速度参数，执行相应数量的任务步骤
                for (int i = 0; i < renderService.speed && !ready.isEmpty(); i++) {
                    // 随机选择一个渲染任务并执行一步
                    RenderTask renderTask = ready.get(random.nextInt(ready.size()));
                    renderTask.step();
                    // 如果任务已完成，则从任务队列中移除
                    if (renderTask.isFinished()) {
                        renderTask.finish();
                        renderService.tasks.remove(renderTask);
                        ready.remove(renderTask);
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                running.set(false);
            }
        }, 1, 1);
    }

}
