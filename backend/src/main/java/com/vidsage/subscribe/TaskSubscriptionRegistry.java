package com.vidsage.subscribe;

import com.vidsage.model.progress.TaskProgressMessage;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 任务进度的中立发布-订阅注册表：{@code taskNo → List<Consumer<TaskProgressMessage>>}（design.md「SSE 契约」，
 * 2026-09-30 定调的顶层包 {@code subscribe/}）。
 *
 * <p><b>包内禁止出现的东西</b>：HTTP 类型（{@code SseEmitter} 等只活在 {@code web/sse/}）、
 * 业务编排（阶段顺序、状态迁移都归 service/）。本类只知道「按 taskNo 把事件交给订阅者」，
 * 判据是「写得出它不许装什么」——把 Spring 容器拿掉它照样成立，事件流向：
 * 管线 → {@link #broadcast} → 订阅者（web 层注册的「写 emitter」Consumer 只是其中一种）。
 *
 * <p><b>并发模型</b>：每个 taskNo 一条 {@link CopyOnWriteArrayList}——订阅 / 退订改副本，
 * 广播遍历快照，读写互不阻塞；管线的广播跑在虚拟线程上，与 HTTP 线程的订阅并发发生。
 * 事件量小（阶段边界 + 批级），写时复制的代价可忽略。
 *
 * <p><b>单个订阅者失败不连累别人</b>：broadcast 对每个 Consumer 单独捕获异常记 WARN 后继续，
 * 绝不上抛——回调在管线的编排路径上，一个写失败的 emitter 不能把整条任务拖成 FAILED。
 *
 * <p><b>生命周期</b>：任务到达终态（SUCCEEDED / FAILED）后管线先广播终态事件、再调
 * {@link #complete} 清空该 taskNo 的全部订阅者——事件不落库，订阅关系也不为死任务保留；
 * 迟来的订阅者拿 DB 快照（{@code TaskService.snapshot}）即可恢复进度。
 */
@Slf4j
@Component
public class TaskSubscriptionRegistry {

    /**
     * taskNo → 该任务的全部订阅者。列表元素移除是 O(n) 拷贝，量小无碍。
     * 这里用List是因为可能出现多个页面看同一个任务、刷新页面旧的连接没来得及退订，新的就连接进来等情况
     * */
    private final ConcurrentHashMap<String, CopyOnWriteArrayList<Consumer<TaskProgressMessage>>> subscribers =
            new ConcurrentHashMap<>();

    /**
     * 订阅一个任务的进度事件。
     *
     * @param taskNo   对外任务号；不可为空白
     * @param consumer 事件回调；不可为 {@code null}。同一 Consumer 可重复订阅同一任务（每次都是独立订阅）
     * @return 退订句柄（close 幂等）
     * @throws IllegalArgumentException taskNo 为空白或 consumer 为 {@code null}
     */
    public Subscription subscribe(String taskNo, Consumer<TaskProgressMessage> consumer) {
        requireTaskNo(taskNo);
        if (consumer == null) {
            throw new IllegalArgumentException("订阅回调不能为 null：请传一个 Consumer<TaskProgressMessage>");
        }
        subscribers.computeIfAbsent(taskNo, key -> new CopyOnWriteArrayList<>()).add(consumer);
        return new Subscription(() -> remove(taskNo, consumer));
    }

    /**
     * 把事件广播给该任务当前的全部订阅者。无人订阅（或已被 {@link #complete} 清空）时是无害空操作。
     *
     * <p>单个订阅者回调抛异常时记 WARN 并继续派发给其余订阅者，本方法本身不上抛。
     *
     * @param taskNo 对外任务号；不可为空白
     * @param event  事件本体；不可为 {@code null}
     * @throws IllegalArgumentException taskNo 为空白或 event 为 {@code null}
     */
    public void broadcast(String taskNo, TaskProgressMessage event) {
        requireTaskNo(taskNo);
        if (event == null) {
            throw new IllegalArgumentException("广播事件不能为 null：请传 TaskProgressMessage");
        }
        List<Consumer<TaskProgressMessage>> listeners = subscribers.get(taskNo);
        if (listeners == null) {
            return;
        }
        for (Consumer<TaskProgressMessage> listener : listeners) {
            try {
                listener.accept(event);
            } catch (RuntimeException e) {
                // 订阅者（如写 emitter）失败只影响它自己；广播方（管线）必须继续跑
                log.warn("任务进度订阅者回调失败，跳过该订阅者: taskNo={}, reason={}", taskNo, e.getMessage());
            }
        }
    }

    /**
     * 终态收口：清空该 taskNo 的全部订阅者（任务 SUCCEEDED / FAILED 广播完终态事件后调用）。
     * 之后再广播该 taskNo 的事件是无害空操作；新订阅会重新建表，由调用方保证不再发生（任务已终态）。
     *
     * @param taskNo 对外任务号；不可为空白
     */
    public void complete(String taskNo) {
        requireTaskNo(taskNo);
        List<Consumer<TaskProgressMessage>> removed = subscribers.remove(taskNo);
        if (removed != null) {
            log.debug("任务终态，已清空订阅者: taskNo={}, count={}", taskNo, removed.size());
        }
    }

    /**
     * 退订一个 Consumer（订阅句柄 close 的落点）。Consumer 可能已被 complete 整体清走，删除前先探一眼，
     * 避免为已消失的 taskNo 重新建出空表（空表无人清理，等于内存泄漏的种子）。
     */
    private void remove(String taskNo, Consumer<TaskProgressMessage> consumer) {
        CopyOnWriteArrayList<Consumer<TaskProgressMessage>> listeners = subscribers.get(taskNo);
        if (listeners != null) {
            listeners.remove(consumer);
        }
    }

    /**
     * taskNo 前置校验（卫语句先于一切状态变更）。
     */
    private static void requireTaskNo(String taskNo) {
        if (!StringUtils.hasText(taskNo)) {
            throw new IllegalArgumentException("taskNo 不能为空：请传提交任务时响应里的 taskNo");
        }
    }
}
