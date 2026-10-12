package com.vidsage.domain.shared.port;

import com.vidsage.domain.shared.model.StageProgress;

/**
 * 阶段进度回调：阶段服务在阶段开始 / 成功 / 跳过 / 失败时各回调一次。
 *
 * <p>实现方应假定回调会被同步调用，且<b>不应长时间阻塞</b>——它位于外部工具调用的编排路径上。
 */
@FunctionalInterface
public interface ProgressListener {

    /**
     * 接收一次阶段进度。
     *
     * @param stageProgress 阶段进度；实现方不应假定其可变或可空（当前实现恒不为 {@code null}）
     */
    void onProgress(StageProgress stageProgress);

    /**
     * 空实现：不关心进度的调用方用它，避免在编排路径上到处判空。
     *
     * @return 什么都不做的监听器
     */
    static ProgressListener noop() {
        return stageProgress -> {
        };
    }
}
