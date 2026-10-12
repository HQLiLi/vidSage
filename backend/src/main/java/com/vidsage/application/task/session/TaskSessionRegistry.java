package com.vidsage.application.task.session;

import com.vidsage.domain.shared.model.WorkspaceKey;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * 后置会话的在途登记：回答「哪个工作区的哪个后置会话正在跑」。
 *
 * <p><b>它是一把锁，不是状态</b>（design 决策 6 / PRD D6）：跑会话的虚拟线程与登记它的表同生共死，
 * 不落库、不跨重启、不进 SSE 快照的持久化部分，只在组装快照时被查一次。
 * 不上 Redis——搬进分布式锁反而要多补 TTL / 启动清理去擦自己造的谎。
 *
 * <p><b>两条纪律</b>：
 * <ol>
 *   <li><b>{@link #finish} 必须写在调用方的 {@code finally} 里</b>，任何异常路径都不能漏。
 *       漏了会留下永远 RUNNING 的幽灵：前端卡 loading，且该工作区的互斥再也释放不掉
 *       ——比卡 loading 更严重（PRD AC9 / AC10 钉的就是这两条）。</li>
 *   <li>互斥判定在<b>同步段</b>（202 之前）：抢不到就 409，不排队等锁——用户看到 202 以为成功了，
 *       实际在等别人跑完，语义上是骗人。</li>
 * </ol>
 *
 * <p>落位 {@code application/task/session/}：读它的是 {@code TaskProgressNotifier}（快照组装），
 * 写它的是字幕管线，与订阅表「管线广播 / web 挂回调」的分工同形。
 */
@Component
public class TaskSessionRegistry {

    /**
     * 工作区 → 正在跑的阶段名（如 CLEAN_SUBTITLES）。原子 putIfAbsent 就是抢锁。
     */
    private final ConcurrentHashMap<WorkspaceKey, String> running =
            new ConcurrentHashMap<>();

    /**
     * 尝试登记一个后置会话：该工作区没有在跑的会话就登记上，返回 {@code true}；
     * 已经有一个在跑就什么都不动，返回 {@code false}（调用方据此 409）。
     *
     * <p>必须与 {@link #finish} 成对出现在 {@code try/finally} 中——finally 里的 finish 是硬要求。
     *
     * @param key       工作区短哈希
     * @param stageName 本次会话占用的阶段名（快照还原时用它渲染 RUNNING 事件）
     * @return 抢到登记为 {@code true}；已有在跑会话为 {@code false}
     */
    public boolean tryStart(WorkspaceKey key, String stageName) {
        return running.putIfAbsent(key, stageName) == null;
    }

    /**
     * 查该工作区有没有在跑的后置会话，有就返回它的阶段名。
     *
     * <p>原名是名词式取值器（{@code runningStage}），2026-10-12 命名收口按「动词开头」改为动词——
     * 它答的是「给我查一下」而不是「这是什么」。
     *
     * @param key 工作区短哈希
     * @return 在跑的阶段名；没在跑为 {@code null}（不是空串，调用方判 null 即可）
     */
    public String findRunningStage(WorkspaceKey key) {
        return running.get(key);
    }

    /**
     * 退登记。重复调用无害（会话已经退了，再退一次仍是空操作）。
     *
     * <p><b>调用方必须把它写在 finally 里</b>（见类注释纪律 1）。
     *
     * @param key 工作区短哈希
     */
    public void finish(WorkspaceKey key) {
        running.remove(key);
    }
}
