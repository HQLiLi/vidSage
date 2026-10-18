package com.vidsage.shared.progress;

import com.vidsage.domain.shared.model.StageProgress;
import com.vidsage.domain.shared.port.ProgressListener;
import lombok.extern.slf4j.Slf4j;

/**
 * 发一个阶段事件的助手：把阶段名、进度、描述、状态四个字段拼成 {@link StageProgress} 交给监听器。
 *
 * <p>几个管线编排件都要发阶段事件，拼装与投递的写法收在这一处——将来给进度事件加字段只改这里，
 * 不会出现「四处各写一份、漏改一处就静默不一致」。
 */
@Slf4j
public final class StageEvents {

    private StageEvents() {
    }

    /**
     * 发一条阶段进度。
     *
     * @param listener 接收方
     * @param stage    阶段名
     * @param progress <b>阶段内</b>进度，取值 0–100
     * @param message  面向用户的中文描述
     * @param state    阶段状态
     */
    public static void emit(ProgressListener listener, String stage, int progress, String message,
                            StageProgress.State state) {
        log.debug("阶段事件: stage={}, progress={}, state={}", stage, progress, state);
        listener.onProgress(new StageProgress(stage, progress, message, state));
    }
}
