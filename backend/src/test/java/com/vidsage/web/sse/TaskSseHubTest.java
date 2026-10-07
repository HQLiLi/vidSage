package com.vidsage.web.sse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.vidsage.domain.shared.model.StageEvent;
import com.vidsage.domain.subtitle.model.common.SubtitleStageName;
import com.vidsage.application.task.TaskProgressMessage;
import com.vidsage.domain.task.model.TaskStatus;
import com.vidsage.domain.task.model.TranslationStatus;
import org.junit.jupiter.api.Test;

/**
 * {@link TaskSseHub} 的纯单元测试：不起 Spring 容器。
 *
 * <p>Spring 7 的 {@code ResponseBodyEmitter.Handler} 是包私有类型，单测无法从外部挂记录用
 * Handler，事件写出的字节形态与终态 complete 的回调链由 {@code TaskControllerTest} 的切片测试
 * （真 MockMvc + 真 hub/registry 接线）覆盖；本文件专测 hub 自身可从公开 API 观察的生命周期：
 * 登记 / 摘除、终态收口（含翻译子状态的判定，2026-10-02 两段式）、卫语句与心跳调度器的关停。
 */
class TaskSseHubTest {

    private static final String TASK_NO = "t0";

    private final TaskSseHub hub = new TaskSseHub();

    private static TaskProgressMessage event(TaskStatus status) {
        return new TaskProgressMessage(TASK_NO, status,
                new StageEvent(SubtitleStageName.TRANSCRIBE_AUDIO.getStageName(), 70, "开始转写", StageEvent.StageState.RUNNING), null);
    }

    /** 翻译事件：taskStatus 恒为主状态 SUCCEEDED（主管线终态），翻译子状态随给定值。 */
    private static TaskProgressMessage translationEvent(TranslationStatus translationStatus) {
        return new TaskProgressMessage(TASK_NO, TaskStatus.SUCCEEDED,
                new StageEvent(SubtitleStageName.TRANSLATE_SUBTITLES.getStageName(), 50, "第 2/3 批", StageEvent.StageState.RUNNING),
                translationStatus);
    }

    // ---------- 注册与摘除 ----------

    @Test
    void registerKeepsEmitterUntilCleanup() {
        hub.register(TASK_NO);

        assertEquals(1, hub.emitterCount(TASK_NO));
        assertEquals(0, hub.emitterCount("other"), "注册按 taskNo 隔离");
    }

    @Test
    void sendToTaskWithoutEmittersIsNoOp() {
        hub.send("ghost", event(TaskStatus.RUNNING));

        assertEquals(0, hub.emitterCount("ghost"));
    }

    @Test
    void sendToUninitializedEmitterIsNoOp() {
        // 未 initialize 的 emitter：early send 进缓冲区（容器初始化时补写），不得抛异常
        hub.register(TASK_NO);

        hub.send(TASK_NO, event(TaskStatus.RUNNING));

        assertEquals(1, hub.emitterCount(TASK_NO));
    }

    // ---------- 终态收口 ----------

    @Test
    void terminalSuccessEventRemovesEmitter() {
        hub.register(TASK_NO);

        hub.send(TASK_NO, event(TaskStatus.SUCCEEDED));

        assertEquals(0, hub.emitterCount(TASK_NO), "终态事件推完应立即收口并摘除");
    }

    @Test
    void terminalFailureEventAlsoRemovesEmitter() {
        hub.register(TASK_NO);

        hub.send(TASK_NO, event(TaskStatus.FAILED));

        assertEquals(0, hub.emitterCount(TASK_NO));
    }

    // ---------- 终态收口：翻译子状态判定（2026-10-02 两段式） ----------

    @Test
    void translationProgressEventKeepsEmitterDespiteTerminalMainStatus() {
        hub.register(TASK_NO);

        // 翻译事件的 taskStatus 恒为主状态 SUCCEEDED：若终态判定只看主状态，
        // 第一条翻译进度事件就会把连接收掉——翻译进行中（RUNNING）必须保持连接
        hub.send(TASK_NO, translationEvent(TranslationStatus.RUNNING));

        assertEquals(1, hub.emitterCount(TASK_NO), "翻译进行中的事件不得收口连接");
    }

    @Test
    void translationTerminalEventRemovesEmitter() {
        hub.register(TASK_NO);

        hub.send(TASK_NO, translationEvent(TranslationStatus.SUCCEEDED));

        assertEquals(0, hub.emitterCount(TASK_NO), "翻译终态事件推完应立即收口并摘除");
    }

    @Test
    void translationFailureTerminalEventAlsoRemovesEmitter() {
        hub.register(TASK_NO);

        hub.send(TASK_NO, translationEvent(TranslationStatus.FAILED));

        assertEquals(0, hub.emitterCount(TASK_NO), "翻译失败终态事件推完应立即收口并摘除");
    }

    // ---------- 卫语句与生命周期 ----------

    @Test
    void registerRejectsBlankTaskNo() {
        assertThrows(IllegalArgumentException.class, () -> hub.register(" "));
    }

    @Test
    void sendRejectsBlankTaskNoAndNullEvent() {
        assertThrows(IllegalArgumentException.class, () -> hub.send(" ", event(TaskStatus.RUNNING)));
        assertThrows(IllegalArgumentException.class, () -> hub.send(TASK_NO, null));
    }

    @Test
    void shutdownStopsHeartbeatWithoutError() {
        hub.shutdown();
    }
}
