package com.vidsage.web.sse;

import com.vidsage.application.task.message.TaskRunProgress;
import com.vidsage.application.task.port.TaskProgressBroadcaster;
import com.vidsage.domain.task.model.RunRef;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * SSE 广播的实现：持有 {@code RunRef → List<SseEmitter>}，负责写事件、终态收口、断连 / 超时清理与心跳。
 * 本类是 {@code SseEmitter} 等 HTTP 类型唯一允许出现的地方，端口在 application 层。
 *
 * <p><b>清理</b>：客户端断连与空闲超时都从表里摘除 emitter；单个 emitter 写失败只摘它自己，
 * 不影响同一条 run 的其他连接。连接表按 {@code RunRef} 分桶，不同 run 的流互不波及。
 *
 * <p><b>心跳</b>：每 30s 向全部在线连接推一条注释行（{@code :ping}），防代理 / 客户端空闲断连。
 * 用自有的单线程调度器而不是 {@code @Scheduled}：线程是虚拟守护线程、{@code @PreDestroy} 即关，
 * 测试（切片不起调度上下文 / 单测直接 new）不会被它挂住。
 */
@Slf4j
@Component
public class SseTaskProgressBroadcaster implements TaskProgressBroadcaster {

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
     * run → 该 run 的全部在线 emitter。
     */
    private final ConcurrentHashMap<RunRef, CopyOnWriteArrayList<SseEmitter>> emitters = new ConcurrentHashMap<>();

    /**
     * 心跳调度器：单线程足够——心跳只是逐个 emitter 写注释行，没有并发压力。
     */
    private final ScheduledExecutorService heartbeatExecutor;

    public SseTaskProgressBroadcaster() {
        this.heartbeatExecutor = Executors.newSingleThreadScheduledExecutor(
                Thread.ofVirtual().name("vidsage-sse-heartbeat-", 0).factory());
        this.heartbeatExecutor.scheduleWithFixedDelay(
                this::beat, HEARTBEAT_INTERVAL_SECONDS, HEARTBEAT_INTERVAL_SECONDS, TimeUnit.SECONDS);
    }

    /**
     * 建立一条 run 的进度连接：建 emitter → 入表 → 挂断连清理 → 推快照；快照已终态则立即关。
     *
     * <p>返回 {@code SseEmitter}（HTTP 类型），故不上端口，由 controller 直接调本实现。
     *
     * @param run      目标 run
     * @param snapshot 连接建立即推的 DB 快照
     * @return 未初始化的 emitter（由 Spring MVC 在请求挂起时接管写出）
     */
    public SseEmitter open(RunRef run, TaskRunProgress snapshot) {
        SseEmitter emitter = new SseEmitter(EMITTER_TIMEOUT_MILLIS);
        emitters.computeIfAbsent(run, key -> new CopyOnWriteArrayList<>()).add(emitter);
        // 断连的收口只注册这一个回调：onCompletion 是单槽回调，注册第二个会覆盖掉它
        emitter.onCompletion(() -> remove(run, emitter));
        sendTo(run, emitter, snapshot);
        return emitter;
    }

    @Override
    public void publish(RunRef run, TaskRunProgress event) {
        List<SseEmitter> targets = emitters.get(run);
        if (targets == null) {
            return;
        }
        for (SseEmitter target : targets) {
            sendTo(run, target, event);
        }
    }

    @Override
    public void complete(RunRef run) {
        // 先整桶摘掉再关连接：关连接会触发 onCompletion 回调，不能在持有桶锁时做
        List<SseEmitter> targets = emitters.remove(run);
        if (targets == null) {
            return;
        }
        for (SseEmitter target : targets) {
            target.complete();
        }
    }

    private void sendTo(RunRef run, SseEmitter target, TaskRunProgress event) {
        try {
            target.send(SseEmitter.event().name(EVENT_NAME).data(event, MediaType.APPLICATION_JSON));
            if (event.terminal()) {
                target.complete();
                remove(run, target);
            }
        } catch (IOException | IllegalStateException e) {
            log.debug("SSE 事件写入失败，摘除连接: runNo={}, reason={}", run.runNo(), e.getMessage());
            remove(run, target);
        }
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
                    log.debug("SSE 心跳失败，摘除连接: runNo={}, reason={}", entry.getKey().runNo(), e.getMessage());
                    remove(entry.getKey(), emitter);
                }
            }
        }
    }

    /**
     * 摘除一个 emitter；该 run 最后一个连接被摘走时顺带清掉空表，不为死 run 留空桶。
     */
    private void remove(RunRef run, SseEmitter emitter) {
        emitters.computeIfPresent(run, (key, list) -> {
            list.remove(emitter);
            return list.isEmpty() ? null : list;
        });
    }

    int emitterCount(RunRef run) {
        List<SseEmitter> list = emitters.get(run);
        return list == null ? 0 : list.size();
    }

    boolean hasBucket(RunRef run) {
        return emitters.containsKey(run);
    }

    /**
     * 容器收尾：停掉心跳调度。调度线程是虚拟守护线程，测试上下文销毁也不会挂住 JVM。
     */
    @PreDestroy
    void shutdown() {
        heartbeatExecutor.shutdownNow();
    }
}
