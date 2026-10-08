package com.vidsage.application.task.port;

/**
 * 并行任务闸门：同时最多放行 vidsage.task.max-concurrent 个
 * （翻译执行与主管线跑媒体转字幕任务共用这一份配额）。
 */
public interface TaskConcurrencyGate {

    /**
     * 提交任务到虚拟线程排队执行
     *
     * @param body               主任务，拿到配额后执行
     * @param onQueueInterrupted 排队等待时被打断时执行的回调
     */
    void submit(Runnable body, Runnable onQueueInterrupted);
}
