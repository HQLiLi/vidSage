package com.vidsage.web.sse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.vidsage.application.task.message.TaskRunProgress;
import com.vidsage.domain.shared.model.StageProgress;
import com.vidsage.domain.subtitle.TaskRun;
import com.vidsage.domain.subtitle.model.TaskRunStatus;
import com.vidsage.domain.subtitle.model.TaskRunType;
import com.vidsage.domain.task.model.RunRef;
import com.vidsage.domain.task.model.TaskNo;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedConstruction;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * {@link SseTaskProgressBroadcaster} 的纯单元测试：不起 Spring 容器。
 *
 * <p>连接表行为用真实 {@code SseEmitter} 观察（未初始化的 emitter 写事件进缓冲区、不抛）；
 * 投递与断连收口用 {@code mockConstruction} 截获构造出的 emitter——Spring 的完成回调没有公开触发口，
 * 只能这样把回调捞出来手动跑。
 */
class SseTaskProgressBroadcasterTest {

    private static final String TASK_NO = "t7";

    private static final long TASK_ID = 7L;

    private SseTaskProgressBroadcaster broadcaster;

    @BeforeEach
    void setUp() {
        broadcaster = new SseTaskProgressBroadcaster();
    }

    @AfterEach
    void tearDown() {
        broadcaster.shutdown();
    }

    // ---------- 连接表 ----------

    @Test
    void openKeepsEmitterUntilCleanup() {
        broadcaster.open(ref(1), runningEvent(ref(1)));

        assertEquals(1, broadcaster.emitterCount(ref(1)));
        assertEquals(0, broadcaster.emitterCount(ref(2)), "连接按 RunRef 隔离");
    }

    @Test
    void terminalSnapshotClosesAndRemovesConnection() {
        broadcaster.open(ref(1), terminalEvent(ref(1)));

        assertEquals(0, broadcaster.emitterCount(ref(1)), "终态快照推完即收口");
        assertFalse(broadcaster.hasBucket(ref(1)), "空桶要被回收");
    }

    @Test
    void differentRunsDoNotAffectEachOther() {
        broadcaster.open(ref(1), runningEvent(ref(1)));
        broadcaster.open(ref(2), runningEvent(ref(2)));

        broadcaster.publish(ref(1), terminalEvent(ref(1)));

        assertEquals(0, broadcaster.emitterCount(ref(1)));
        assertFalse(broadcaster.hasBucket(ref(1)));
        assertEquals(1, broadcaster.emitterCount(ref(2)), "run 2 的连接不受 run 1 终态影响");
    }

    @Test
    void publishDropsOnlyTheFailingConnection() {
        SseEmitter dead = broadcaster.open(ref(1), runningEvent(ref(1)));
        broadcaster.open(ref(1), runningEvent(ref(1)));
        // 把第一条置为「已完结」态：再往它写必抛 IllegalStateException，于是只摘它自己
        dead.complete();

        broadcaster.publish(ref(1), runningEvent(ref(1)));

        assertEquals(1, broadcaster.emitterCount(ref(1)), "写失败的那个被摘除，另一个仍在");
        assertTrue(broadcaster.hasBucket(ref(1)));
    }

    @Test
    void completeClosesEveryConnectionOfTheRunAndDropsBucket() {
        broadcaster.open(ref(1), runningEvent(ref(1)));
        broadcaster.open(ref(1), runningEvent(ref(1)));
        broadcaster.open(ref(2), runningEvent(ref(2)));

        broadcaster.complete(ref(1));

        assertFalse(broadcaster.hasBucket(ref(1)), "complete 无条件摘掉该 run 的桶");
        assertEquals(0, broadcaster.emitterCount(ref(1)));
        assertEquals(1, broadcaster.emitterCount(ref(2)), "别的 run 的桶不动");
    }

    @Test
    void completeOnUnknownRunIsNoOp() {
        broadcaster.complete(ref(9));

        assertFalse(broadcaster.hasBucket(ref(9)));
    }

    // ---------- 投递 ----------

    @Test
    void publishesToEveryConnectionOfTheSameRun() throws IOException {
        try (MockedConstruction<SseEmitter> mocked = mockConstruction(SseEmitter.class)) {
            RunRef run = ref(1);
            broadcaster.open(run, runningEvent(run));
            broadcaster.open(run, runningEvent(run));
            List<SseEmitter> emitters = mocked.constructed();

            broadcaster.publish(run, runningEvent(run));

            for (SseEmitter emitter : emitters) {
                // 一次连接快照 + 一次广播 = 2 次写出
                verify(emitter, times(2)).send(any(SseEmitter.SseEventBuilder.class));
            }
        }
    }

    // ---------- 断连收口（onCompletion 覆盖 bug 的回归） ----------

    @Test
    void openRegistersSingleCompletionCallbackThatRemovesEmitterOnDisconnect() {
        try (MockedConstruction<SseEmitter> mocked = mockConstruction(SseEmitter.class)) {
            RunRef run = ref(1);
            SseEmitter emitter = broadcaster.open(run, runningEvent(run));

            ArgumentCaptor<Runnable> callback = ArgumentCaptor.forClass(Runnable.class);
            verify(emitter, times(1)).onCompletion(callback.capture());
            assertEquals(1, broadcaster.emitterCount(run));

            callback.getValue().run();   // 模拟客户端断连时容器回调

            assertEquals(0, broadcaster.emitterCount(run), "断连回调必须把 emitter 从连接表摘除");
            assertFalse(broadcaster.hasBucket(run));
        }
    }

    // ---------- 夹具 ----------

    private static RunRef ref(int runNo) {
        return new RunRef(new TaskNo(TASK_NO), runNo);
    }

    private static TaskRunProgress runningEvent(RunRef run) {
        return event(run, TaskRunStatus.RUNNING,
                new StageProgress("transcribe-audio", 50, "转写中", StageProgress.State.RUNNING));
    }

    private static TaskRunProgress terminalEvent(RunRef run) {
        return event(run, TaskRunStatus.SUCCEEDED, null);
    }

    private static TaskRunProgress event(RunRef run, TaskRunStatus status, StageProgress stage) {
        TaskRun taskRun = TaskRun.restore(1L, TASK_ID, run.runNo(), TaskRunType.MAIN, null, status,
                "transcribe-audio", null, LocalDateTime.now(), null, LocalDateTime.now());
        return TaskRunProgress.of(run, taskRun, stage);
    }
}
