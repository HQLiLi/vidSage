package com.vidsage.domain.shared.port;

import com.vidsage.domain.shared.model.StageEvent;

/**
 * 阶段进度回调：阶段服务在阶段开始 / 成功 / 跳过 / 失败时各回调一次。
 *
 * <p><b>刻意保持最小</b>：只有一个 {@link #onEvent}，形状随 {@link StageEvent} 一起在 MVP-1 的
 * SSE payload 定锤前是过渡态。
 *
 * <p><b>放在 {@code domain/shared/} 而非 {@code application/}</b>：事件与回调都是中立契约，
 * {@code infrastructure/} 与 {@code application/} 都可能依赖它。若把回调放进 {@code application/}，
 *  {@code infrastructure/} 引用它就构成「infrastructure 反向依赖 application」的分层倒置。
 *
 * <p>实现方应假定回调会被同步调用，且<b>不应长时间阻塞</b>——它位于外部工具调用的编排路径上。
 */
@FunctionalInterface
public interface ProgressListener {

    /**
     * 接收一个阶段事件。
     *
     * @param event 阶段事件；实现方不应假定其可变或可空（当前实现恒不为 {@code null}）
     */
    void onEvent(StageEvent event);

    /**
     * 空实现：不关心进度的调用方用它，避免在编排路径上到处判空。
     *
     * @return 什么都不做的监听器
     */
    static ProgressListener noop() {
        return event -> {
        };
    }
}
