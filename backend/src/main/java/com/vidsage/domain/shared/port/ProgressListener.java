package com.vidsage.domain.shared.port;

import com.vidsage.domain.shared.model.StageProgress;

/**
 * 阶段进度回调：阶段服务在阶段开始 / 成功 / 跳过 / 失败时各回调一次。
 *
 * <p>只有 {@link #onProgress} 这一个方法，形状与 {@link StageProgress} 一致。
 *
 * <p><b>放在 {@code domain/shared/} 而非 {@code application/}</b>：进度与回调是三层共用的——
 * {@code infrastructure/}（按批上报）、{@code application/}（四个 coordinator 与 {@code SubtitlePipeline}）、
 * {@code domain/} 端口都依赖它。若把回调放进 {@code application/}，{@code infrastructure/} 引用它就
 * 构成「infrastructure 反向依赖 application」的分层倒置。SSE 只是链尾那一跳
 * （{@code SubtitlePipeline} → {@code TaskProgressNotifier} → emitter），不是它的归属依据。
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
