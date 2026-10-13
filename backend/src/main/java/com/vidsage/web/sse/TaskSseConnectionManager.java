package com.vidsage.web.sse;

import com.vidsage.application.task.message.TaskProgressMessage;
import com.vidsage.application.task.subscribe.Subscription;
import com.vidsage.application.task.subscribe.TaskSubscriptionRegistry;
import jakarta.annotation.PreDestroy;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * SSE 连接管理：持有 {@code taskNo → List<SseEmitter>}，负责写事件、终态收口、断连 / 超时清理与心跳。
 * 本类是 {@code SseEmitter} 等 HTTP 类型<b>唯一</b>允许出现的包——订阅机制本体在 {@code application/task/}
 * 的中立注册表里，管线只认识注册表不认识 emitter。
 *
 * <p><b>清理</b>：客户端断连（onError / onCompletion）与空闲超时（onTimeout）都从表中摘除 emitter；
 * 单个 emitter 写失败只摘除它自己，不影响同任务的其他订阅者。
 *
 * <p><b>心跳</b>：每 30s 向全部在线连接推一条注释行（{@code :ping}），防代理 / 客户端空闲断连。
 * 用自有的单线程调度器而不是 {@code @Scheduled}：线程是虚拟守护线程、{@code @PreDestroy} 即关，
 * 测试（切片不起调度上下文 / 单测直接 new）不会被它挂住。
 */
@Slf4j
@Component
public class TaskSseConnectionManager {

    /**
     * SSE 事件名：本任务所有进度事件都叫它（前端按名分发）。
     */
    static final String EVENT_NAME = "progress";

    /**
     * 心跳间隔（秒）：30s 是防代理空闲断连的常规值。
     */
    private static final int HEARTBEAT_INTERVAL_SECONDS = 30;

    /**
     * 连接空闲超时（毫秒）：心跳失败本会主动断开，这里是最后兜底（容器默认值不可控，显式给定）。
     */
    private static final long EMITTER_TIMEOUT_MILLIS = TimeUnit.MINUTES.toMillis(30);

    /**
     * taskNo → 该任务的全部在线 emitter。
     */
    private final ConcurrentHashMap<String, CopyOnWriteArrayList<SseEmitter>> emitters =
            new ConcurrentHashMap<>();

    /**
     * 心跳调度器：单线程足够——心跳只是逐个 emitter 写注释行，没有并发压力。
     */
    private final ScheduledExecutorService heartbeatExecutor;

    private final TaskSubscriptionRegistry subscriptionRegistry;

    public TaskSseConnectionManager(TaskSubscriptionRegistry subscriptionRegistry) {
        this.subscriptionRegistry = subscriptionRegistry;
        this.heartbeatExecutor = Executors.newSingleThreadScheduledExecutor(
                Thread.ofVirtual().name("vidsage-sse-heartbeat-", 0).factory());
        this.heartbeatExecutor.scheduleWithFixedDelay(
                this::beat, HEARTBEAT_INTERVAL_SECONDS, HEARTBEAT_INTERVAL_SECONDS, TimeUnit.SECONDS);
    }

    /**
     * 建立一个任务进度订阅
     *
     * @param taskNo           任务号
     * @param snapshotSupplier 任务进度快照读取
     * @return 未初始化的 emitter
     */
    public SseEmitter open(String taskNo, Supplier<TaskProgressMessage> snapshotSupplier) {
        requireTaskNo(taskNo);
        SseEmitter emitter = register(taskNo);
        Subscription subscription = subscriptionRegistry.subscribe(taskNo, event -> sendTo(taskNo, emitter, event));
        emitter.onCompletion(subscription::close);
        try {
            TaskProgressMessage taskProgressMessage = snapshotSupplier.get();
            sendTo(taskNo, emitter, taskProgressMessage);
            if (taskProgressMessage.terminal()) {
                subscription.close();
            }
        } catch (RuntimeException e) {
            subscription.close();
            remove(taskNo, emitter);
            throw e;
        }
        return emitter;
    }


    /**
     * 建立一条 SSE 连接：创建 emitter 并挂进表，同时登记断连 / 超时 / 出错三类清理钩子。
     *
     * @param taskNo 对外任务号；不可为空白
     * @return 未初始化的 emitter（由 Spring MVC 在请求挂起时接管写出）
     * @throws IllegalArgumentException taskNo 为空白
     */
    SseEmitter register(String taskNo) {
        requireTaskNo(taskNo);
        SseEmitter emitter = new SseEmitter(EMITTER_TIMEOUT_MILLIS);
        emitters.computeIfAbsent(taskNo, key -> new CopyOnWriteArrayList<>()).add(emitter);
        // 三类钩子都只做摘除；onTimeout / onError 之后容器还会触发 onCompletion，摘除是幂等的
        emitter.onCompletion(() -> remove(taskNo, emitter));
        emitter.onTimeout(() -> {
            log.debug("SSE 连接超时，摘除: taskNo={}", taskNo);
            remove(taskNo, emitter);
        });
        emitter.onError(e -> {
            log.debug("SSE 连接出错，摘除: taskNo={}, reason={}", taskNo, e.getMessage());
            remove(taskNo, emitter);
        });
        return emitter;
    }

    private void sendTo(String taskNo, SseEmitter target, TaskProgressMessage event) {
        try {
            target.send(SseEmitter.event().name(EVENT_NAME).data(event, MediaType.APPLICATION_JSON));
            boolean isTerminal = event.terminal();
            if (isTerminal) {
                target.complete();
                remove(taskNo, target);
            }
        } catch (IOException | IllegalStateException e) {
            log.debug("SSE 事件写入失败，摘除连接: taskNo={}, reason={}", taskNo, e.getMessage());
            remove(taskNo, target);
        }
    }

    int emitterCount(String taskNo) {
        List<SseEmitter> list = emitters.get(taskNo);
        return list == null ? 0 : list.size();
    }

    boolean hasBucket(String taskNo) {
        return emitters.containsKey(taskNo);
    }

    /**
     * 心跳：推的注释行不产生 progress 事件，客户端无感——只为保活连接。
     */
    private void beat() {
        for (var entry : emitters.entrySet()) {
            for (SseEmitter emitter : entry.getValue()) {
                try {
                    emitter.send(SseEmitter.event().comment("ping"));
                } catch (IOException | IllegalStateException e) {
                    // 心跳探到死连接：摘除，等它的 onCompletion 钩子做余下清理
                    log.debug("SSE 心跳失败，摘除连接: taskNo={}, reason={}", entry.getKey(), e.getMessage());
                    remove(entry.getKey(), emitter);
                }
            }
        }
    }

    /**
     * 摘除一个 emitter；该任务最后一个连接被摘走时顺带清掉空表，不为死任务留空桶。
     */
    private void remove(String taskNo, SseEmitter emitter) {
        emitters.computeIfPresent(taskNo, (key, list) -> {
            list.remove(emitter);
            return list.isEmpty() ? null : list;
        });
    }


    /**
     * taskNo 前置校验（卫语句先于一切状态变更）。
     */
    private static void requireTaskNo(String taskNo) {
        if (!StringUtils.hasText(taskNo)) {
            throw new IllegalArgumentException("taskNo 不能为空：请传提交任务时响应里的 taskNo");
        }
    }

    /**
     * 容器收尾：停掉心跳调度。调度线程是虚拟守护线程，测试上下文销毁也不会挂住 JVM。
     */
    @PreDestroy
    void shutdown() {
        heartbeatExecutor.shutdownNow();
    }
}
