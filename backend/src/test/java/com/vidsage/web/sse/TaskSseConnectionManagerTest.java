package com.vidsage.web.sse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vidsage.application.task.exception.TaskNotFoundException;
import com.vidsage.application.task.message.TaskProgressMessage;
import com.vidsage.application.task.subscribe.Subscription;
import com.vidsage.application.task.subscribe.TaskSubscriptionRegistry;
import com.vidsage.domain.shared.model.StageProgress;
import com.vidsage.domain.subtitle.model.common.SubtitleStageName;
import com.vidsage.domain.task.model.TaskStatus;
import com.vidsage.domain.task.model.TranslationStatus;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * {@link TaskSseConnectionManager} 的纯单元测试：不起 Spring 容器。
 *
 * <p>Spring 7 的 {@code ResponseBodyEmitter.Handler} 是包私有类型，单测无法从外部挂记录用
 * Handler，事件写出的字节形态由 {@code TaskControllerTest} 的切片测试（真 MockMvc + 真
 * manager/registry 接线）覆盖；本文件专测本类自身可从公开 / 包内 API 观察的行为：
 *
 * <ul>
 *   <li>连接生命周期——登记 / 摘除、终态收口（含翻译子状态的判定，2026-10-02 两段式）、空桶回收；</li>
 *   <li>{@code open()} 的接线顺序——登记连接 → 挂订阅 → 推快照 → 终态收口，以及对「订阅时任务
 *       已终态」这条窗口竞态的退订防御、读快照失败时的回滚。</li>
 * </ul>
 *
 * <p>推送走的是真实链路：把该连接自己那个消费者（{@code open()} 挂到注册表上的）捞出来直接喂事件——
 * 注册表本身的行为由 {@code TaskSubscriptionRegistryTest} 钉住，这里不重复。
 */
class TaskSseConnectionManagerTest {

    private static final String TASK_NO = "t0";

    /**
     * 退订是否真的发生过。{@link Subscription} 是 final 类、退订动作由注册表在创建时注入，
     * 故这里不 mock 它，而是让被 mock 的注册表返回一个绑定本标志位的真实句柄。
     */
    private final AtomicBoolean unsubscribed = new AtomicBoolean(false);

    private final TaskSubscriptionRegistry registry = mock(TaskSubscriptionRegistry.class);

    private final TaskSseConnectionManager manager = new TaskSseConnectionManager(registry);

    /** {@code open()} 每次挂到注册表上的消费者，按连接建立顺序排列。 */
    private final List<Consumer<TaskProgressMessage>> consumers = new ArrayList<>();

    @BeforeEach
    void stubSubscribeReturnsTrackedHandle() {
        when(registry.subscribe(anyString(), any())).thenAnswer(invocation -> {
            consumers.add(invocation.getArgument(1));
            return new Subscription(() -> unsubscribed.set(true));
        });
    }

    /** 模拟管线广播一条事件给第 index 条连接——生产里是注册表遍历订阅者把它送进来。 */
    private void push(int index, TaskProgressMessage event) {
        consumers.get(index).accept(event);
    }

    private static TaskProgressMessage event(TaskStatus status) {
        return TaskProgressMessage.of(TASK_NO, status,
                new StageProgress(SubtitleStageName.TRANSCRIBE_AUDIO.getStageName(), 70, "开始转写", StageProgress.State.RUNNING), null);
    }

    /** 翻译事件：taskStatus 恒为主状态 SUCCEEDED（主管线终态），翻译子状态随给定值。 */
    private static TaskProgressMessage translationEvent(TranslationStatus translationStatus) {
        return TaskProgressMessage.of(TASK_NO, TaskStatus.SUCCEEDED,
                new StageProgress(SubtitleStageName.TRANSLATE_SUBTITLES.getStageName(), 50, "第 2/3 批", StageProgress.State.RUNNING),
                translationStatus);
    }

    // ---------- 注册与摘除 ----------

    @Test
    void registerKeepsEmitterUntilCleanup() {
        manager.register(TASK_NO);

        assertEquals(1, manager.emitterCount(TASK_NO));
        assertEquals(0, manager.emitterCount("other"), "注册按 taskNo 隔离");
    }

    @Test
    void openWritesSnapshotToNotYetInitializedEmitterWithoutError() {
        // 未 initialize 的 emitter：early send 进缓冲区（容器初始化时补写），不得抛异常
        manager.open(TASK_NO, () -> event(TaskStatus.RUNNING));

        assertEquals(1, manager.emitterCount(TASK_NO));
    }

    // ---------- 终态收口 ----------

    @Test
    void terminalEventPushedToALiveConnectionCompletesAndRemovesIt() {
        manager.open(TASK_NO, () -> event(TaskStatus.RUNNING));

        push(0, event(TaskStatus.SUCCEEDED));

        assertEquals(0, manager.emitterCount(TASK_NO), "终态事件推完应立即收口并摘除");
    }

    @Test
    void terminalFailureEventAlsoRemovesEmitter() {
        manager.open(TASK_NO, () -> event(TaskStatus.RUNNING));

        push(0, event(TaskStatus.FAILED));

        assertEquals(0, manager.emitterCount(TASK_NO));
    }

    // ---------- 终态收口：翻译子状态判定（2026-10-02 两段式） ----------

    @Test
    void translationProgressEventKeepsEmitterDespiteTerminalMainStatus() {
        manager.open(TASK_NO, () -> event(TaskStatus.RUNNING));

        // 翻译事件的 taskStatus 恒为主状态 SUCCEEDED：若终态判定只看主状态，
        // 第一条翻译进度事件就会把连接收掉——翻译进行中（RUNNING）必须保持连接
        push(0, translationEvent(TranslationStatus.RUNNING));

        assertEquals(1, manager.emitterCount(TASK_NO), "翻译进行中的事件不得收口连接");
    }

    @Test
    void translationTerminalEventRemovesEmitter() {
        manager.open(TASK_NO, () -> event(TaskStatus.RUNNING));

        push(0, translationEvent(TranslationStatus.SUCCEEDED));

        assertEquals(0, manager.emitterCount(TASK_NO), "翻译终态事件推完应立即收口并摘除");
    }

    @Test
    void translationFailureTerminalEventAlsoRemovesEmitter() {
        manager.open(TASK_NO, () -> event(TaskStatus.RUNNING));

        push(0, translationEvent(TranslationStatus.FAILED));

        assertEquals(0, manager.emitterCount(TASK_NO), "翻译失败终态事件推完应立即收口并摘除");
    }

    // ---------- open()：配对与收口 ----------

    @Test
    void openPushesSnapshotAndKeepsSubscriptionWhileStreamIsLive() {
        manager.open(TASK_NO, () -> event(TaskStatus.RUNNING));

        verify(registry).subscribe(eq(TASK_NO), any());
        assertEquals(1, manager.emitterCount(TASK_NO), "非终态快照推完，连接应保持在线");
        assertFalse(unsubscribed.get(), "流尚未收敛，订阅不得退订");
    }

    @Test
    void openClosesSubscriptionWhenSnapshotIsAlreadyTerminal() {
        // 窗口竞态的历史缺陷路径（MVP-1 真实抓到过）：任务恰在「读快照 → 挂订阅」之间到达终态，
        // 注册表此后不会为该 taskNo 调用 complete，订阅必须由 open 自己收掉，否则永久泄漏
        manager.open(TASK_NO, () -> event(TaskStatus.FAILED));

        assertTrue(unsubscribed.get(), "订阅时任务已终态 → 必须主动退订防泄漏");
        assertEquals(0, manager.emitterCount(TASK_NO), "终态快照推完即收口，不留在线连接");
    }

    @Test
    void openKeepsSubscriptionWhenOnlyTranslationIsStillRunning() {
        // 「主状态终态但翻译还在跑」不得按终态收口：连接要活到翻译终态，订阅也不能退
        manager.open(TASK_NO, () -> translationEvent(TranslationStatus.RUNNING));

        assertEquals(1, manager.emitterCount(TASK_NO), "翻译进行中，连接必须保持");
        assertFalse(unsubscribed.get(), "翻译进行中，订阅不得退订");
    }

    @Test
    void openRejectsBlankTaskNoBeforeRegisteringAnything() {
        assertThrows(IllegalArgumentException.class,
                () -> manager.open(" ", () -> event(TaskStatus.RUNNING)));
    }

    @Test
    void openUnwindsEmitterAndSubscriptionWhenSnapshotReadFails() {
        // 读快照抛异常时这条连接还没交给 MVC，三个钩子永远不会触发——不主动收尾就会在 emitters
        // 与注册表里各留一份永不释放的条目（那是本类唯一会「进去就出不来」的路径）
        assertThrows(IllegalStateException.class,
                () -> manager.open(TASK_NO, () -> {
                    throw new IllegalStateException("读快照失败：DB 故障");
                }));

        assertEquals(0, manager.emitterCount(TASK_NO), "读快照失败不得留下悬挂连接");
        assertFalse(manager.hasBucket(TASK_NO), "空桶要被回收");
        assertTrue(unsubscribed.get(), "订阅必须退掉，否则注册表永久泄漏");
    }

    @Test
    void openUnwindsEmitterAndSubscriptionWhenTaskDisappearsInTheWindow() {
        // 更贴近生产的失败类型：controller 的 404 预检之后、open() 里重读快照之前任务被清掉，
        // snapshot() 抛的是 TaskNotFoundException（继承 RuntimeException，但不是 ISE）——
        // 回滚若只捕某一种异常，这条真实分支就会从指缝里溜走
        assertThrows(TaskNotFoundException.class,
                () -> manager.open(TASK_NO, () -> {
                    throw new TaskNotFoundException(TASK_NO);
                }));

        assertEquals(0, manager.emitterCount(TASK_NO), "不得留下悬挂连接");
        assertFalse(manager.hasBucket(TASK_NO), "空桶要被回收");
        assertTrue(unsubscribed.get(), "订阅必须退掉，否则注册表永久泄漏");
    }

    // ---------- 空桶回收（R2：Javadoc 曾承诺清桶，实现却从不删） ----------

    @Test
    void lastEmitterRemovedAlsoDropsTheBucket() {
        manager.open(TASK_NO, () -> event(TaskStatus.RUNNING));

        push(0, event(TaskStatus.SUCCEEDED));

        assertFalse(manager.hasBucket(TASK_NO),
                "最后一个连接摘除后，该 taskNo 的桶必须一并删掉，不留空桶");
    }

    @Test
    void bucketStaysWhileOtherEmittersOfTheSameTaskRemain() {
        SseEmitter dead = manager.open(TASK_NO, () -> event(TaskStatus.RUNNING));
        manager.open(TASK_NO, () -> event(TaskStatus.RUNNING));
        // 把第一条连接置为「已完结」态：再往它写必抛 IllegalStateException，
        // 于是只摘除它自己——正好造出「同任务还剩别的连接」这一半场景
        dead.complete();

        push(0, event(TaskStatus.RUNNING));

        assertEquals(1, manager.emitterCount(TASK_NO), "写失败的那个被摘除，另一个仍在");
        assertTrue(manager.hasBucket(TASK_NO), "同任务仍有在线连接，桶不得被删");
    }

    // ---------- 卫语句与生命周期 ----------

    @Test
    void registerRejectsBlankTaskNo() {
        assertThrows(IllegalArgumentException.class, () -> manager.register(" "));
    }

    @Test
    void shutdownStopsHeartbeatWithoutError() {
        manager.shutdown();
    }
}
