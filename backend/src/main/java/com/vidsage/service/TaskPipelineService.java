package com.vidsage.service;

import com.vidsage.config.TaskProperties;
import com.vidsage.domain.task.VideoTask;
import com.vidsage.model.media.MediaInput;
import com.vidsage.model.progress.ProgressListener;
import com.vidsage.model.progress.TaskProgressMessage;
import com.vidsage.persistence.repository.VideoTaskRepository;
import com.vidsage.subscribe.TaskSubscriptionRegistry;
import com.vidsage.utils.progress.ProgressMapper;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 异步任务管线：按阶段顺序编排既有服务，把「一条已落库的任务」跑成终态。
 *
 * <p>阶段顺序（design.md「数据流·执行」；区间为任务级进度权重）：
 * <ol>
 *   <li>{@code SubtitleService.prepare}（0..60，内部含 acquire / extract-audio / transcribe /
 *       write-subtitles 四个阶段，阶段事件由回调驱动）</li>
 *   <li>{@code SubtitleCleanupService.clean}（60..80，批级进度 0..100 由 remap 压进该区间）</li>
 *   <li>{@code SubtitleTranslationService.translate}（80..100，同上）</li>
 * </ol>
 *
 * <p>进度回调是<b>复合监听器</b>：① 更新 DB {@code current_stage}（先落库再广播——订阅者看到的
 * 状态有持久化背书）；② 组 {@link TaskProgressMessage}(taskNo, taskStatus, stage) 经
 * {@link TaskSubscriptionRegistry} 广播给全部订阅者（注册表单订阅者失败自兜底，不会连累管线）。
 * 广播路径上不落库事件本体——「DB 快照 + 重连 snapshot」即权威（design.md「SSE 契约」）。
 * 复合监听器里落库的异常刻意不吞：落库失败说明存储不可用，继续跑只会产出「库与实况脱节」的任务。
 *
 * <p>终态收口：SUCCEEDED / FAILED 落库后广播一条终态 {@link TaskProgressMessage}（阶段形状与
 * {@code TaskService} 的快照同源），再 {@link TaskSubscriptionRegistry#complete} 清空该任务全部
 * 订阅者——SSE 连接的收口由订阅方（web/sse/TaskSseHub）看到终态事件后自行完成。
 *
 * <p>执行模型：每次执行占一根虚拟线程（项目已全局开启虚拟线程，阻塞等待不占平台线程）；
 * {@link Semaphore} 按 {@code vidsage.task.max-concurrent} 限流，<b>超出者保持 PENDING 排队</b>——
 * 所以先抢配额、后做 PENDING → RUNNING 迁移，排队中的任务状态保持诚实。
 *
 * <p>任一阶段抛异常 → RUNNING → FAILED（落中文 error_message，广播终态后不再继续后续阶段）；
 * 全部完成 → SUCCEEDED + finished_at。产物按阶段落工作区，失败后重新提交同一输入自动断点复用。
 */
@Slf4j
@Service
public class TaskPipelineService {

    /** prepare 全包阶段（acquire / extract-audio / transcribe / write-subtitles）映射进 0..60。 */
    private static final int PREPARE_END = 60;

    /** clean 批级进度映射进 60..80（design.md「数据流·执行」的区间权重）。 */
    private static final int CLEAN_START = 60;
    private static final int CLEAN_END = 80;

    /** translate 批级进度映射进 80..100。 */
    private static final int TRANSLATE_START = 80;
    private static final int TRANSLATE_END = 100;

    private final VideoTaskRepository repository;
    private final WorkspaceService workspaceService;
    private final SubtitleService subtitleService;
    private final SubtitleCleanupService cleanupService;
    private final SubtitleTranslationService translationService;
    private final TaskSubscriptionRegistry subscriptionRegistry;
    private final Semaphore semaphore;
    private final ExecutorService executor;

    /**
     * 构造函数注入，保证可测试性（见 quality-guidelines）。
     *
     * @param repository          任务持久化入口（管线按 taskNo 装载任务并回写状态）
     * @param workspaceService    工作区根解析（上传路线按持久化的 workspace_dir 还原媒体路径）
     * @param subtitleService     阶段 1：音频准备 + 转写 + 写原始字幕
     * @param cleanupService      阶段 2：字幕清洗
     * @param translationService  阶段 3：字幕翻译
     * @param subscriptionRegistry 中立订阅注册表（进度事件的广播落点）
     * @param properties          管线配置（并发上限）
     */
    public TaskPipelineService(VideoTaskRepository repository, WorkspaceService workspaceService,
                               SubtitleService subtitleService, SubtitleCleanupService cleanupService,
                               SubtitleTranslationService translationService,
                               TaskSubscriptionRegistry subscriptionRegistry, TaskProperties properties) {
        if (properties.maxConcurrent() < 1) {
            throw new IllegalStateException("vidsage.task.max-concurrent 必须 ≥ 1：当前为 "
                    + properties.maxConcurrent() + "；请修正配置");
        }
        this.repository = repository;
        this.workspaceService = workspaceService;
        this.subtitleService = subtitleService;
        this.cleanupService = cleanupService;
        this.translationService = translationService;
        this.subscriptionRegistry = subscriptionRegistry;
        this.semaphore = new Semaphore(properties.maxConcurrent());
        // 命名虚拟线程：日志与线程转储里能认出「这是哪个任务的管线」
        this.executor = Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name("vidsage-task-", 0).factory());
    }

    /**
     * 异步启动管线：立即返回（调用方 HTTP 202 不等执行）。
     *
     * @param taskNo 已落库任务的对外任务号
     */
    public void execute(String taskNo) {
        executor.execute(() -> run(taskNo));
    }

    /**
     * 同步执行完整管线。包内可见——单测直接调它绕开虚拟线程的时序，生产路径走 {@link #execute}。
     */
    void run(String taskNo) {
        Optional<VideoTask> loaded = repository.findByTaskNo(taskNo);
        if (loaded.isEmpty()) {
            // 提交刚落库就执行，缺失说明存储出了问题：ERROR 可见，不让任务静默蒸发
            log.error("任务记录缺失，管线放弃执行: taskNo={}", taskNo);
            return;
        }
        VideoTask task = loaded.get();
        try {
            semaphore.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            // 排队被打断：保持 PENDING，重新提交同一输入即可再入队（内容寻址，代价低）
            log.warn("任务等待执行配额时被中断，保持 PENDING: taskNo={}", taskNo);
            return;
        }
        long start = System.currentTimeMillis();
        try {
            try {
                task.start();
            } catch (IllegalStateException e) {
                // 双重触发（同任务被 execute 两次）或状态已被推进：放弃本次执行，绝不把别人的执行打成 FAILED
                log.warn("任务不处于 PENDING，放弃本次执行: taskNo={}, status={}", taskNo, task.getStatus());
                return;
            }
            repository.update(task);
            // 不打 workspaceDir：目录名内嵌 URL / 原始文件名（用户资源，脱敏红线），按 taskNo 可查库还原
            log.info("任务开始执行: taskNo={}, inputType={}",
                    task.getTaskNo(), task.getInputType());
            runStages(task);
            task.succeed();
            repository.update(task);
            broadcastTerminal(task);
            log.info("任务执行成功: taskNo={}, cost={}ms", task.getTaskNo(), System.currentTimeMillis() - start);
        } catch (Exception e) {
            // 通过SEE广播任务失败
            failTask(task, e);
        } finally {
            semaphore.release();
        }
    }

    /**
     * 按阶段顺序执行；prepare 的阶段事件与 clean / translate 的批级事件都由回调驱动
     * （composite listener 落库 + 广播），各阶段自报的 0..100 进度按权重 remap 进任务级区间。
     */
    private void runStages(VideoTask task) {
        MediaInput input = toMediaInput(task);
        ProgressListener listener = progressListener(task);
        // 阶段 1：prepare 全包（0..60），阶段事件由 remap 后的回调驱动
        subtitleService.prepare(input, ProgressMapper.remap(listener, 0, PREPARE_END));
        // 阶段 2：清洗（60..80），批级进度事件来自 SubtitleCleanser，开始 / 跳过 / 完成事件来自服务层
        cleanupService.clean(input, "", ProgressMapper.remap(listener, CLEAN_START, CLEAN_END));
        // 阶段 3：翻译（80..100），批级进度事件来自 SubtitleTranslator
        translationService.translate(input, ProgressMapper.remap(listener, TRANSLATE_START, TRANSLATE_END));
    }

    /**
     * 管线复合进度回调：阶段名写进 current_stage 并落库（先），再组 {@link TaskProgressMessage} 广播（后）——
     * 订阅者看到的事件有持久化背书。广播本身不上抛（注册表对单订阅者异常自兜底）；
     * 落库异常刻意不吞：存储不可用时继续跑只会产出「库与实况脱节」的任务。
     */
    private ProgressListener progressListener(VideoTask task) {
        return event -> {
            log.debug("任务阶段事件: taskNo={}, stage={}, progress={}, state={}, message={}",
                    task.getTaskNo(), event.stage(), event.progress(), event.state(), event.message());
            task.updateStage(event.stage());
            repository.update(task);
            subscriptionRegistry.broadcast(task.getTaskNo(),
                    new TaskProgressMessage(task.getTaskNo(), task.getStatus(), event));
        };
    }

    /**
     * 终态收口：广播一条终态 {@link TaskProgressMessage}（阶段形状与 {@link TaskService} 的快照同源——
     * 在线订阅者与重连订阅者看到的终态渲染一致），随后清空该任务全部订阅者。
     * 广播在前、complete 在后（finally）：哪怕广播路径抛了意外异常，订阅关系也必须清掉。
     */
    private void broadcastTerminal(VideoTask task) {
        try {
            subscriptionRegistry.broadcast(task.getTaskNo(),
                    new TaskProgressMessage(task.getTaskNo(), task.getStatus(), TaskService.stageEventOf(task)));
        } finally {
            subscriptionRegistry.complete(task.getTaskNo());
        }
    }

    /**
     * 任一阶段异常 → FAILED：落中文错误信息（含失败所在阶段），广播终态事件并清空订阅者。
     * 落库本身再失败时只记 ERROR——虚拟线程无人接异常，吞掉等于静默，必须留下日志痕迹；
     * 此时任务可能停留在 RUNNING，不广播终态（库与实况已脱节，不传播脱节的状态）。
     */
    private void failTask(VideoTask task, Exception cause) {
        String reason = cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
        String stage = task.getCurrentStage();
        String message = stage == null
                ? "任务执行失败：" + reason
                : "任务在 " + stage + " 阶段失败：" + reason;
        try {
            task.fail(message);
            repository.update(task);
            broadcastTerminal(task);
            log.error("任务执行失败: taskNo={}, status={}, message={}", task.getTaskNo(), task.getStatus(), message,
                    cause);
        } catch (RuntimeException e) {
            log.error("任务失败状态落库失败，任务可能停留在 RUNNING: taskNo={}, reason={}",
                    task.getTaskNo(), e.getMessage(), e);
        }
    }

    /**
     * 从持久化字段还原管线的媒体输入：URL 路线直接还原 RemoteUrl（各阶段服务按同一公式重算
     * 工作区，落点一致）；上传路线还原 InWorkspace——工作区目录与内容 key 都在库里，还原为
     * 随输入携带、各阶段服务零重算（若包成 LocalPath，会被按「路径+大小+mtime」重算出另一个目录，
     * 任务 workspace_dir 与产物落点分家，见 design.md「权衡记录·上传路线的工作区归属」）。
     */
    private MediaInput toMediaInput(VideoTask task) {
        return switch (task.getInputType()) {
            case URL -> new MediaInput.RemoteUrl(task.getSourceUri());
            case UPLOAD -> {
                Path dir = workspaceService.workspaceRoot().resolve(task.getWorkspaceDir());
                Path source = dir.resolve(TaskService.UPLOAD_SOURCE_FILE_NAME);
                yield new MediaInput.InWorkspace(source, dir, task.getWorkspaceKey());
            }
        };
    }
}
