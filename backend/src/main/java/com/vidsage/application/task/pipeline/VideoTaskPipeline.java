package com.vidsage.application.task.pipeline;

import com.vidsage.application.media.WorkspaceService;
import com.vidsage.application.subtitle.pipeline.SubtitleCleanupStage;
import com.vidsage.application.subtitle.pipeline.SubtitlePreparationStage;
import com.vidsage.application.subtitle.pipeline.SubtitleTranslationStage;
import com.vidsage.application.task.TaskProgressMessage;
import com.vidsage.application.task.VideoTaskService;
import com.vidsage.application.task.exception.TaskNotFoundException;
import com.vidsage.application.task.subscribe.TaskSubscriptionRegistry;
import com.vidsage.config.TaskProperties;
import com.vidsage.domain.task.model.TaskNo;
import com.vidsage.domain.task.VideoTask;
import com.vidsage.domain.media.model.MediaInput;
import com.vidsage.domain.shared.port.ProgressListener;
import com.vidsage.domain.task.model.TaskStatus;
import com.vidsage.domain.task.model.TranslationStatus;
import com.vidsage.domain.task.policy.TaskStageEvent;
import com.vidsage.domain.task.port.VideoTaskRepository;
import com.vidsage.shared.progress.ProgressMapper;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 异步任务管线：按阶段顺序编排各阶段构件，把「一条已落库的任务」跑成终态。
 *
 * <p>阶段顺序（design.md「数据流·执行」；区间为任务级进度权重；2026-10-02 两段式裁定后管线
 * 到清洗 / 原文字幕为止，翻译拆出为按需触发，见 {@link #translateTask}）：
 * <ol>
 *   <li>{@code SubtitlePreparationStage.prepare}（0..70，内部含 acquire / extract-audio / transcribe /
 *       write-subtitles 四个阶段，阶段事件由回调驱动）</li>
 *   <li>{@code SubtitleCleanupStage.clean}（70..100，批级进度 0..100 由 remap 压进该区间）</li>
 * </ol>
 *
 * <p>进度回调是<b>复合监听器</b>：① 更新 DB {@code current_stage}（先落库再广播——订阅者看到的
 * 状态有持久化背书）；② 组 {@link TaskProgressMessage}(taskNo, taskStatus, stage, translationStatus)
 * 经 {@link TaskSubscriptionRegistry} 广播给全部订阅者（注册表单订阅者失败自兜底，不会连累管线）。
 * 广播路径上不落库事件本体——「DB 快照 + 重连 snapshot」即权威（design.md「SSE 契约」）。
 * 复合监听器里落库的异常刻意不吞：落库失败说明存储不可用，继续跑只会产出「库与实况脱节」的任务。
 *
 * <p>终态收口：SUCCEEDED / FAILED 落库后广播一条终态 {@link TaskProgressMessage}（阶段形状与
 * {@code VideoTaskService} 的快照同源），再 {@link TaskSubscriptionRegistry#complete} 清空该任务全部
 * 订阅者——SSE 连接的收口由订阅方（web/sse/TaskSseHub）看到终态事件后自行完成。
 *
 * <p>执行模型：每次执行占一根虚拟线程（项目已全局开启虚拟线程，阻塞等待不占平台线程）；
 * {@link Semaphore} 按 {@code vidsage.task.max-concurrent} 限流（翻译执行与主管线共用同一配额），
 * <b>超出者保持 PENDING 排队</b>——所以先抢配额、后做 PENDING → RUNNING 迁移，排队中的任务状态保持诚实。
 *
 * <p>任一阶段抛异常 → RUNNING → FAILED（落中文 error_message，广播终态后不再继续后续阶段）；
 * 全部完成 → SUCCEEDED + finished_at。产物按阶段落工作区，失败后重新提交同一输入自动断点复用。
 */
@Slf4j
@Component
public class VideoTaskPipeline {

    /** prepare 全包阶段（acquire / extract-audio / transcribe / write-subtitles）映射进 0..70。 */
    private static final int PREPARE_END = 70;

    /** clean 批级进度映射进 70..100（2026-10-02 两段式：翻译拆出后管线权重只剩这两段）。 */
    private static final int CLEAN_START = 70;
    private static final int CLEAN_END = 100;

    private final VideoTaskRepository repository;
    private final WorkspaceService workspaceService;
    private final SubtitlePreparationStage subtitlePreparationStage;
    private final SubtitleCleanupStage cleanupStage;
    private final SubtitleTranslationStage translationStage;
    private final TaskSubscriptionRegistry subscriptionRegistry;
    private final Semaphore semaphore;
    private final ExecutorService executor;

    /**
     * 构造函数注入，保证可测试性（见 quality-guidelines）。
     *
     * @param repository               任务持久化入口（管线按 taskNo 装载任务并回写状态）
     * @param workspaceService         工作区根解析（上传路线按持久化的 workspace_dir 还原媒体路径）
     * @param subtitlePreparationStage 阶段 1：音频准备 + 转写 + 写原始字幕
     * @param cleanupStage             阶段 2：字幕清洗（管线的最后一个自动阶段）
     * @param translationStage         按需翻译：{@link #translateTask} 触发后执行（不随主管线自动跑）
     * @param subscriptionRegistry     中立订阅注册表（进度事件的广播落点）
     * @param properties               管线配置（并发上限）
     */
    public VideoTaskPipeline(VideoTaskRepository repository, WorkspaceService workspaceService,
                             SubtitlePreparationStage subtitlePreparationStage, SubtitleCleanupStage cleanupStage,
                             SubtitleTranslationStage translationStage,
                             TaskSubscriptionRegistry subscriptionRegistry, TaskProperties properties) {
        if (properties.maxConcurrent() < 1) {
            throw new IllegalStateException("vidsage.task.max-concurrent 必须 ≥ 1：当前为 "
                    + properties.maxConcurrent() + "；请修正配置");
        }
        this.repository = repository;
        this.workspaceService = workspaceService;
        this.subtitlePreparationStage = subtitlePreparationStage;
        this.cleanupStage = cleanupStage;
        this.translationStage = translationStage;
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
    public void execute(TaskNo taskNo) {
        executor.execute(() -> runPipeline(taskNo));
    }

    /**
     * 按需触发翻译（2026-10-02 两段式）：主任务 SUCCEEDED 后由用户显式触发——不看翻译的人不花
     * LLM 钱。触发判定按序（design.md「翻译按需触发」）：
     * <ol>
     *   <li>任务不存在 → {@link TaskNotFoundException}（404）；</li>
     *   <li>主状态非 SUCCEEDED → {@link IllegalArgumentException}（400，「字幕尚未就绪」——
     *       错在调用方触发太早，按 error-handling.md「错在调用方 → IAE → 400」走既有映射，
     *       不为一次映射新建异常类型）；</li>
     *   <li>翻译进行中 → 直接返回，不重入（同步守卫 + {@code translationStart} 的状态机拒绝双保险）；</li>
     *   <li>翻译检查点已在（上一次成功的翻译或两段式改造前的存量产物）→ <b>幂等收敛</b>：
     *       {@code translationSucceed} 落库后返回，绝不发 LLM；</li>
     *   <li>正常路径：同步段 {@code translationStart} 落库（RUNNING，202 响应视图因此反映触发后
     *       的状态）→ 异步执行翻译，事件自成 0..100 段广播。</li>
     * </ol>
     * 同步段只做判定与状态落库；LLM 翻译在虚拟线程异步执行，配额与主管线共用同一 {@link Semaphore}。
     *
     * @param taskNo 对外任务号
     * @throws TaskNotFoundException    任务不存在
     * @throws IllegalArgumentException 主任务尚未成功（字幕尚未就绪）
     */
    public void translateTask(TaskNo taskNo) {
        VideoTask task = repository.findByTaskNo(taskNo)
                .orElseThrow(() -> new TaskNotFoundException(taskNo.value()));
        if (task.getStatus() != TaskStatus.SUCCEEDED) {
            throw new IllegalArgumentException("字幕尚未就绪：任务状态为 " + task.getStatus()
                    + "，翻译只能在任务成功（SUCCEEDED）后触发；请先等主管线完成（可查 "
                    + "GET /api/tasks/" + taskNo.value() + " 或订阅其 SSE 事件）再试");
        }
        if (task.getTranslationStatus() == TranslationStatus.RUNNING) {
            log.info("翻译进行中，重复触发不重入: taskNo={}", taskNo.value());
            return;
        }
        MediaInput input = toMediaInput(task);
        if (translationStage.hasTranslationArtifact(input)) {
            // 检查点已在,收敛为SUCCEEDED，不看翻译的人不花钱，看过的人不重复花钱
            if (task.getTranslationStatus() != TranslationStatus.SUCCEEDED) {
                task.translationSucceed();
                repository.update(task);
                log.info("翻译产物已存在，幂等收敛为 SUCCEEDED（未调用 LLM）: taskNo={}", taskNo);
            }
            return;
        }
        try {
            task.translationStart();
        } catch (IllegalStateException e) {
            // 并发重复触发的竞争兜底：先到者已把翻译置为 RUNNING，本次视同「不重入」，
            // 调用方重读任务得到的视图即反映进行中的翻译
            log.info("翻译启动竞争失败，视同重复触发不重入: taskNo={}", taskNo);
            return;
        }
        repository.update(task);
        // 不打 workspaceDir：目录名内嵌 URL / 原始文件名（用户资源，脱敏红线），按 taskNo 可查库还原
        log.info("翻译已触发: taskNo={}, inputType={}", task.getTaskNo().value(), task.getInputType());
        executor.execute(() -> runTranslationPipeline(task, input));
    }

    /**
     * 同步执行完整管线。包内可见——单测直接调它绕开虚拟线程的时序，生产路径走 {@link #execute}。
     */
    void runPipeline(TaskNo taskNo) {
        Optional<VideoTask> loaded = repository.findByTaskNo(taskNo);
        if (loaded.isEmpty()) {
            // 提交刚落库就执行，缺失说明存储出了问题：ERROR 可见，不让任务静默蒸发
            log.error("任务记录缺失，管线放弃执行: taskNo={}", taskNo.value());
            return;
        }
        VideoTask task = loaded.get();
        try {
            semaphore.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            // 排队被打断：保持 PENDING，重新提交同一输入即可再入队（内容寻址，代价低）
            log.warn("任务等待执行配额时被中断，保持 PENDING: taskNo={}", taskNo.value());
            return;
        }
        long start = System.currentTimeMillis();
        try {
            try {
                task.start();
            } catch (IllegalStateException e) {
                // 双重触发（同任务被 execute 两次）或状态已被推进：放弃本次执行，绝不把别人的执行打成 FAILED
                log.warn("任务不处于 PENDING，放弃本次执行: taskNo={}, status={}", taskNo.value(), task.getStatus());
                return;
            }
            repository.update(task);
            // 不打 workspaceDir：目录名内嵌 URL / 原始文件名（用户资源，脱敏红线），按 taskNo 可查库还原
            log.info("任务开始执行: taskNo={}, inputType={}",
                    task.getTaskNo().value(), task.getInputType());
            prepareAndClean(task);
            task.succeed();
            repository.update(task);
            broadcastTerminal(task);
            log.info("任务执行成功: taskNo={}, cost={}ms", task.getTaskNo().value(), System.currentTimeMillis() - start);
        } catch (Exception e) {
            // 通过SSE广播任务失败
            failTask(task, e);
        } finally {
            semaphore.release();
        }
    }

    /**
     * 按需翻译的异步执行体：与主管线共用同一信号量配额（LLM 翻译同样吃外部配额）。
     * 翻译阶段事件自成 0..100 段直通广播（无区间映射——主任务的 0..100 已在主管线跑完，
     * 翻译是独立的第二段）；翻译事件的 taskStatus 恒为主状态 SUCCEEDED。
     */
    private void runTranslationPipeline(VideoTask task, MediaInput input) {
        long start = System.currentTimeMillis();
        try {
            semaphore.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            // 翻译已置 RUNNING 才排队，被打断不能吞状态：标记失败（可重新触发），广播终态收口
            log.warn("翻译等待执行配额时被中断: taskNo={}", task.getTaskNo().value());
            failTranslation(task, "翻译在等待执行配额时被中断：请重新触发翻译", null);
            return;
        }
        try {
            translationStage.translate(input, translationProgressListener(task));
            task.translationSucceed();
            repository.update(task);
            broadcastTranslationTerminal(task);
            log.info("任务翻译完成: taskNo={}, cost={}ms", task.getTaskNo().value(), System.currentTimeMillis() - start);
        } catch (Exception e) {
            failTranslation(task, e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(), e);
        } finally {
            semaphore.release();
        }
    }

    /**
     * 按阶段顺序执行；prepare 的阶段事件与 clean 的批级事件都由回调驱动
     * （composite listener 落库 + 广播），各阶段自报的 0..100 进度按权重 remap 进任务级区间。
     */
    private void prepareAndClean(VideoTask task) {
        MediaInput input = toMediaInput(task);
        ProgressListener listener = progressListener(task);
        // 阶段 1：prepare 全包（0..70），阶段事件由 remap 后的回调驱动
        subtitlePreparationStage.prepare(input, ProgressMapper.remap(listener, 0, PREPARE_END));
        // 阶段 2：清洗（70..100），批级进度事件来自 SubtitleCleanser，开始 / 跳过 / 完成事件来自阶段构件
        cleanupStage.clean(input, "", ProgressMapper.remap(listener, CLEAN_START, CLEAN_END));
    }

    /**
     * 管线复合进度回调：阶段名写进 current_stage 并落库（先），再组 {@link TaskProgressMessage} 广播（后）——
     * 订阅者看到的事件有持久化背书。广播本身不上抛（注册表对单订阅者异常自兜底）；
     * 落库异常刻意不吞：存储不可用时继续跑只会产出「库与实况脱节」的任务。
     */
    private ProgressListener progressListener(VideoTask task) {
        return event -> {
            log.debug("任务阶段事件: taskNo={}, stage={}, progress={}, state={}, message={}",
                    task.getTaskNo().value(), event.stage(), event.progress(), event.state(), event.message());
            task.updateStage(event.stage());
            repository.update(task);
            subscriptionRegistry.broadcast(task.getTaskNo().value(), new TaskProgressMessage(
                    task.getTaskNo().value(), task.getStatus(), event, task.getTranslationStatus()));
        };
    }

    /**
     * 翻译进度回调（广播专用）：翻译阶段事件自成 0..100 段直通广播，<b>刻意不落 current_stage</b>——
     * current_stage 是主管线阶段词表的落点（updateStage 的 RUNNING 守卫也不允许主状态终态后推进）；
     * 重连快照的翻译子状态由 DB 的 translation_status 粗粒度还原（{@link TaskStageEvent#from}），
     * 精确进度由本回调的实时事件携带。
     */
    private ProgressListener translationProgressListener(VideoTask task) {
        return event -> {
            log.debug("翻译阶段事件: taskNo={}, stage={}, progress={}, state={}, message={}",
                    task.getTaskNo().value(), event.stage(), event.progress(), event.state(), event.message());
            subscriptionRegistry.broadcast(task.getTaskNo().value(), new TaskProgressMessage(
                    task.getTaskNo().value(), task.getStatus(), event, task.getTranslationStatus()));
        };
    }

    /**
     * 终态收口：广播一条终态 {@link TaskProgressMessage}（阶段形状与 {@link VideoTaskService} 的快照同源——
     * 在线订阅者与重连订阅者看到的终态渲染一致，两者都经 {@link TaskStageEvent#from} 渲染），
     * 随后清空该任务全部订阅者。
     * 广播在前、complete 在后（finally）：哪怕广播路径抛了意外异常，订阅关系也必须清掉。
     */
    private void broadcastTerminal(VideoTask task) {
        try {
            subscriptionRegistry.broadcast(task.getTaskNo().value(), new TaskProgressMessage(task.getTaskNo().value(),
                    task.getStatus(), TaskStageEvent.from(task), task.getTranslationStatus()));
        } finally {
            subscriptionRegistry.complete(task.getTaskNo().value());
        }
    }

    /**
     * 翻译终态收口（复用 {@link #broadcastTerminal} 模式）：终态 TaskProgressMessage 的 taskStatus
     * 用主状态 SUCCEEDED（主状态机不受翻译影响）、stage 用 translate（形状与快照的翻译子状态同源，
     * 见 {@link TaskStageEvent#fromTranslation}），translationStatus 携带终态子状态；
     * 推完即清空该任务全部订阅者——翻译终态后任务再无任何事件，连接到此自然结束。
     */
    private void broadcastTranslationTerminal(VideoTask task) {
        try {
            subscriptionRegistry.broadcast(task.getTaskNo().value(), new TaskProgressMessage(task.getTaskNo().value(),
                    task.getStatus(), TaskStageEvent.fromTranslation(task), task.getTranslationStatus()));
        } finally {
            subscriptionRegistry.complete(task.getTaskNo().value());
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
            log.error("任务执行失败: taskNo={}, status={}, message={}", task.getTaskNo().value(), task.getStatus(),
                    message, cause);
        } catch (RuntimeException e) {
            log.error("任务失败状态落库失败，任务可能停留在 RUNNING: taskNo={}, reason={}",
                    task.getTaskNo().value(), e.getMessage(), e);
        }
    }

    /**
     * 翻译失败 → 翻译子状态 FAILED（主状态保持 SUCCEEDED）：错误信息带「翻译失败：」前缀落
     * error_message，广播翻译终态事件并清空订阅者（失败可重新触发，收口后重连拿失败快照）。
     * 落库再失败只记 ERROR：虚拟线程无人接异常，吞掉等于静默。
     *
     * @param reason 失败原因（不含前缀）；{@code cause} 为 {@code null} 时按 WARN 记（无调用栈的主动标记，
     *               如排队被打断），否则按 ERROR 带栈记录
     */
    private void failTranslation(VideoTask task, String reason, Exception cause) {
        String message = "翻译失败：" + reason;
        try {
            task.translationFail(message);
            repository.update(task);
            broadcastTranslationTerminal(task);
            if (cause == null) {
                log.warn("任务翻译失败: taskNo={}, message={}", task.getTaskNo().value(), message);
            } else {
                log.error("任务翻译失败: taskNo={}, status={}, message={}",
                        task.getTaskNo().value(), task.getStatus(), message, cause);
            }
        } catch (RuntimeException e) {
            log.error("翻译失败状态落库失败，任务可能停留在翻译 RUNNING: taskNo={}, reason={}",
                    task.getTaskNo().value(), e.getMessage(), e);
        }
    }

    /**
     * 从持久化字段还原管线的媒体输入：变体选择与落点规则单源在 {@link MediaInput#ofTaskInput}
     * （提交路线共用同一工厂，重建与提交时身份一致）；本方法只负责取「工作区根 + 持久化的相对
     * 目录」这一文件系统事实传给工厂（URL 路线用不到它）。
     */
    private MediaInput toMediaInput(VideoTask task) {
        return MediaInput.ofTaskInput(task.getInputType(), task.getSourceUri(),
                workspaceService.workspaceRoot().resolve(task.getWorkspaceDir()), task.getWorkspaceKey());
    }
}
