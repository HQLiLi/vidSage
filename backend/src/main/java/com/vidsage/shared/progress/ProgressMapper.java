package com.vidsage.shared.progress;

import com.vidsage.domain.shared.ProgressListener;
import com.vidsage.domain.shared.StageEvent;

/**
 * 进度区间线性映射：把某段服务自报的 {@code 0..100} 进度压缩进管线分配给它的子区间
 * {@code [from, to]}。纯函数（无状态、无 I/O、无 Spring 类型），符合 utils 的准入判据。
 *
 * <p>从 {@code SubtitlePreparationStage} 的私有 {@code remap} 上提（2026-09-30，MVP-1）：MVP-1 的异步管线
 * 是第二个使用者（对 clean 做区间映射；2026-10-02 两段式后翻译事件自成 0..100 段、不再经过映射），
 * 符合「同一判据只有一份」的搬迁判据——搬的是纯函数，不是流程骨架。
 *
 * <p>映射公式保证<b>单调不倒退</b>：上游事件按序到达时，映射后的进度不会回退
 * （上游各阶段边界在 0/50/100 等整数点拼接，线性映射后边界重合）。
 */
public final class ProgressMapper {

    /**
     * 私有构造：纯静态工具类，不实例化。
     */
    private ProgressMapper() {
    }

    /**
     * 把 {@code delegate} 收到的进度线性映射进 {@code [from, to]} 区间，其余字段原样透传。
     *
     * @param delegate 实际接收映射后事件的监听器
     * @param from     目标区间起点（含），如 0
     * @param to       目标区间终点（含），如 60；应 ≥ from
     * @return 带区间映射的监听器
     */
    public static ProgressListener remap(ProgressListener delegate, int from, int to) {
        return event -> {
            int progress = from + (int) ((long) event.progress() * (to - from) / 100L);
            delegate.onEvent(new StageEvent(event.stage(), progress, event.message(), event.state()));
        };
    }
}
