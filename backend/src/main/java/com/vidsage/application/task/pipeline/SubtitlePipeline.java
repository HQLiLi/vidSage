package com.vidsage.application.task.pipeline;

import com.vidsage.application.media.port.WorkspaceDirectoryManager;
import com.vidsage.application.subtitle.pipeline.SubtitleCleanupCoordinator;
import com.vidsage.application.subtitle.pipeline.SubtitlePreparationCoordinator;
import com.vidsage.application.subtitle.pipeline.SubtitleTranslationCoordinator;
import com.vidsage.application.task.exception.TaskNotFoundException;
import com.vidsage.application.task.notify.TaskProgressNotifier;
import com.vidsage.application.task.port.TaskConcurrencyGate;
import com.vidsage.application.task.session.TaskSessionRegistry;
import com.vidsage.domain.media.model.MediaInput;
import com.vidsage.domain.shared.port.ProgressListener;
import com.vidsage.domain.subtitle.model.keyword.SubtitleKeywords;
import com.vidsage.domain.subtitle.model.translation.SupportedLanguage;
import com.vidsage.domain.subtitle.port.SubtitleKeywordRepository;
import com.vidsage.domain.task.VideoTask;
import com.vidsage.domain.task.model.TaskNo;
import com.vidsage.domain.task.policy.TaskStageEvent;
import com.vidsage.domain.task.port.VideoTaskRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.Optional;

/**
 * 媒体转字幕任务的主管线：任务经并发闸门排队后执行，按顺序驱动各阶段，并收口终态。
 *
 * <p>任一阶段抛异常 → FAILED（落中文 error_message，不再继续后续阶段）；全部完成 → SUCCEEDED。
 * 产物按阶段落工作区，失败后重新提交同一输入自动断点复用。
 */
@Slf4j
@Component
public class SubtitlePipeline {


    private final TaskConcurrencyGate concurrencyGate;
    private final VideoTaskRepository repository;
    private final SubtitleKeywordRepository keywordRepository;
    private final SubtitlePreparationCoordinator subtitlePreparationCoordinator;
    private final SubtitleCleanupCoordinator cleanupStage;
    private final SubtitleTranslationCoordinator translationStage;
    private final TaskProgressNotifier taskProgressNotifier;
    private final TaskSessionRegistry taskSessionRegistry;
    private final WorkspaceDirectoryManager workspaceDirectoryManager;

    /**
     * @param repository                     任务持久化入口（管线按 taskNo 装载任务并回写状态）
     * @param keywordRepository              关键词持久化入口（按工作区读用户手填的关键词）
     * @param subtitlePreparationCoordinator 阶段 1：音频准备 + 转写 + 写原始字幕
     * @param cleanupStage                   阶段 2：字幕清洗（管线的最后一个自动阶段）
     * @param translationStage               按需翻译：{@link #translateTask} 触发后执行（不随主管线自动跑）
     * @param taskProgressNotifier           任务进度通知器
     * @param taskSessionRegistry            后置会话在途登记（互斥抢锁在调用方，本类只负责 finally 退登记）
     * @param concurrencyGate                并行任务闸门
     * @param workspaceDirectoryManager      工作区目录管理器
     */
    public SubtitlePipeline(VideoTaskRepository repository,
                            SubtitleKeywordRepository keywordRepository,
                            SubtitlePreparationCoordinator subtitlePreparationCoordinator, SubtitleCleanupCoordinator cleanupStage,
                            SubtitleTranslationCoordinator translationStage,
                            TaskProgressNotifier taskProgressNotifier,
                            TaskSessionRegistry taskSessionRegistry,
                            TaskConcurrencyGate concurrencyGate,
                            WorkspaceDirectoryManager workspaceDirectoryManager
    ) {
        this.repository = repository;
        this.keywordRepository = keywordRepository;
        this.subtitlePreparationCoordinator = subtitlePreparationCoordinator;
        this.cleanupStage = cleanupStage;
        this.translationStage = translationStage;
        this.taskProgressNotifier = taskProgressNotifier;
        this.taskSessionRegistry = taskSessionRegistry;
        this.concurrencyGate = concurrencyGate;
        this.workspaceDirectoryManager = workspaceDirectoryManager;
    }

    /**
     * 异步启动管线：立即返回（调用方 HTTP 202 不等执行）。
     *
     * @param taskNo 已落库任务的对外任务号
     */
    public void execute(TaskNo taskNo) {
        concurrencyGate.submit(() -> runPipeline(taskNo),
                () -> log.warn("任务等待执行配额时被中断，保持 PENDING: taskNo={}", taskNo.value()));
    }

    /**
     * 后置会话：按当前关键词重新生成一遍字幕。同步段判定（404 / 400 未就绪 / 409 互斥 / 关键词落库）
     * 在 {@code VideoTaskService} 完成后才调进来——那边要 409 附既有任务快照，快照组装只有 service 有。
     *
     * <p>异步体三条纪律：
     * <ul>
     *   <li><b>先成功、后删</b>：翻译与术语产物的删除在 {@link SubtitleCleanupCoordinator#regenerate}
     *       内部、清洗成功之后才做——重跑失败时旧字幕和译文一个都不少；</li>
     *   <li><b>{@code finally} 退登记</b>：任何异常路径都不许漏，否则留下永远在跑的幽灵，
     *       该工作区的互斥再也释放不掉；</li>
     *   <li><b>不写 {@code current_stage}</b>：进度走只广播不落库的通道，重连快照由登记表还原。</li>
     * </ul>
     *
     * @param task     主状态已 SUCCEEDED 的任务（调用方已校验）
     * @param keywords 本次要用的关键词（调用方已落库或读回）
     */
    public void regenerateSubtitles(VideoTask task, SubtitleKeywords keywords) {
        MediaInput input = toMediaInput(task);
        long startTimeMs = System.currentTimeMillis();
        concurrencyGate.submit(() -> {
            try {
                cleanupStage.regenerate(input, workDir(task), keywords, regenerationProgressListener(task));
                // 成功收口前清掉上一次重生成的失败原因（只认前缀，不动翻译/主管线的错误记录）
                task.clearRegenerateFailure();
                taskProgressNotifier.completeTask(task);
                log.info("字幕重新生成完成: taskNo={}, cost={}ms",
                        task.getTaskNo().value(), System.currentTimeMillis() - startTimeMs);
            } catch (Exception e) {
                String reason = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                taskProgressNotifier.failRegenerate(task, reason);
            } finally {
                // 纪律：登记必须在 finally 退——漏了会留下永远 RUNNING 的幽灵
                taskSessionRegistry.finish(task.getWorkspaceKey());
            }
        }, () -> {
            // 排队等配额时被打断（202 已发）：释放登记 + 落失败原因广播收口，用户可重新触发
            taskSessionRegistry.finish(task.getWorkspaceKey());
            taskProgressNotifier.failRegenerate(task, "重新生成在等待执行配额时被中断：请重新触发");
            log.warn("重新生成等待执行配额时被中断: taskNo={}", task.getTaskNo().value());
        });
    }

    /**
     * 后置会话的进度回调
     */
    private ProgressListener regenerationProgressListener(VideoTask task) {
        return stageProgress -> {
            log.debug("重新生成阶段事件: taskNo={}, stage={}, progress={}, state={}, message={}",
                    task.getTaskNo().value(), stageProgress.stage(), stageProgress.progress(),
                    stageProgress.state(), stageProgress.message());
            taskProgressNotifier.broadcastStage(task, stageProgress);
        };
    }

    /**
     * 翻译
     * 媒体转字幕 主任务完成后可调用
     *
     * @param task           任务
     * @param sourceLanguage 源语种
     * @param targetLanguage 翻译目标语种
     * @throws TaskNotFoundException    任务不存在
     * @throws IllegalArgumentException 主任务尚未成功（字幕尚未就绪）
     */
    public void translateTask(VideoTask task, SupportedLanguage sourceLanguage, SupportedLanguage targetLanguage) {
        MediaInput input = toMediaInput(task);
        // 翻译开始起始时间，用于计算翻译耗时（含排队）
        long startTimeMs = System.currentTimeMillis();
        concurrencyGate.submit(() -> runTranslationPipeline(task, input, sourceLanguage, targetLanguage, startTimeMs),
                () -> {
                    log.warn("翻译等待执行配额时被中断: taskNo={}", task.getTaskNo().value());
                    taskSessionRegistry.finish(task.getWorkspaceKey());
                    taskProgressNotifier.failTranslation(task, "翻译在等待执行配额时被中断：请重新触发翻译");
                });
    }

    /**
     * 同步执行完整管线。包内可见——单测直接调它绕开虚拟线程的时序，生产路径走 {@link #execute}。
     */
    void runPipeline(TaskNo taskNo) {
        Optional<VideoTask> loaded = repository.findByTaskNo(taskNo);
        if (loaded.isEmpty()) {
            log.error("任务记录缺失，管线放弃执行: taskNo={}", taskNo.value());
            return;
        }
        VideoTask task = loaded.get();
        long start = System.currentTimeMillis();
        try {
            try {
                task.start();
            } catch (IllegalStateException e) {
                log.warn("任务不处于 PENDING，放弃本次执行: taskNo={}, status={}", taskNo.value(), task.getStatus());
                return;
            }
            repository.update(task);
            log.info("任务开始执行: taskNo={}, inputType={}",
                    task.getTaskNo().value(), task.getInputType());
            prepareAndClean(task);
            task.succeed();
            taskProgressNotifier.completeTask(task);
            log.info("任务执行成功: taskNo={}, cost={}ms", task.getTaskNo().value(), System.currentTimeMillis() - start);
        } catch (Exception e) {
            // 通过SSE广播任务失败
            failTask(task, e);
        }
    }

    /**
     * 按需翻译的异步执行体。
     * 翻译阶段事件自成 0..100 段直通广播（无区间映射——主任务的 0..100 已在主管线跑完，
     * 翻译是独立的第二段）；翻译事件的 taskStatus 恒为主状态 SUCCEEDED。
     *
     * @param sourceLanguage 源语种
     * @param targetLanguage 翻译目标语种
     * @param startTimeMs    排队前的时刻——成本日志的口径是「含排队时间」，所以要由调用方在提交前取好传进来
     *                       （进到本方法时排队已经结束，在这里取就晚了）
     */
    private void runTranslationPipeline(VideoTask task, MediaInput input, SupportedLanguage sourceLanguage, SupportedLanguage targetLanguage, long startTimeMs) {
        try {
            translationStage.translate(input, workDir(task), sourceLanguage, targetLanguage, translationProgressListener(task));
            taskProgressNotifier.completeTranslation(task);
            log.info("任务翻译完成: taskNo={}, cost={}ms", task.getTaskNo().value(), System.currentTimeMillis() - startTimeMs);
        } catch (Exception e) {
            taskProgressNotifier.failTranslation(task, e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        } finally {
            taskSessionRegistry.finish(task.getWorkspaceKey());
        }
    }

    /**
     * 按阶段顺序执行；prepare 的阶段事件与 clean 的批级事件都由回调驱动
     *
     * <p>关键词从仓储按<b>工作区</b>读（不按 taskNo）：关键词是字幕产物的生成输入，跟产物同粒度。
     */
    private void prepareAndClean(VideoTask task) {
        MediaInput input = toMediaInput(task);
        Path workDir = workDir(task);
        ProgressListener listener = createProgressListener(task);
        // 阶段 1：prepare 准备字幕文件
        subtitlePreparationCoordinator.prepare(input, workDir, listener);
        // 阶段 2：清洗字幕文件
        cleanupStage.clean(input, workDir, keywordRepository.findByWorkspaceKey(task.getWorkspaceKey()), listener);
    }

    /**
     * 创建进度回调监听器
     */
    private ProgressListener createProgressListener(VideoTask task) {
        return stageProgress -> {
            log.debug("任务阶段事件: taskNo={}, stage={}, progress={}, state={}, message={}",
                    task.getTaskNo().value(), stageProgress.stage(), stageProgress.progress(),
                    stageProgress.state(), stageProgress.message());
            taskProgressNotifier.saveCurrentStageAndBroadcast(task, stageProgress);
        };
    }

    /**
     * 翻译进度回调
     */
    private ProgressListener translationProgressListener(VideoTask task) {
        return stageProgress -> {
            log.debug("翻译阶段事件: taskNo={}, stage={}, progress={}, state={}, message={}",
                    task.getTaskNo().value(), stageProgress.stage(), stageProgress.progress(),
                    stageProgress.state(), stageProgress.message());
            taskProgressNotifier.broadcastTranslationStage(task, stageProgress);
        };
    }


    /**
     * 任一阶段异常 → FAILED：落中文错误信息，广播终态事件并清空订阅者。
     * 落库本身再失败时只记 ERROR——虚拟线程无人接异常，吞掉等于静默，必须留下日志痕迹；
     * 此时任务可能停留在 RUNNING，不广播终态（库与实况已脱节，不传播脱节的状态）。
     */
    private void failTask(VideoTask task, Exception cause) {
        String reason = cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
        // 文案不带阶段名：事件与快照的 stage 字段已经给了，中文句子里再嵌一遍标识符没有必要
        String message = VideoTask.TASK_FAILURE_PREFIX + reason;
        try {
            task.fail(message);
            taskProgressNotifier.completeTask(task);
            log.error("任务执行失败: taskNo={}, status={}, message={}", task.getTaskNo().value(), task.getStatus(),
                    message, cause);
        } catch (RuntimeException e) {
            log.error("任务失败状态落库失败，任务可能停留在 RUNNING: taskNo={}, reason={}",
                    task.getTaskNo().value(), e.getMessage(), e);
        }
    }


    /**
     * 定位这条任务的工作区目录（不存在则创建）：目录名由持久化的输入原文与内容 key 推出，
     * 和产物写在同一个目录。调用方算一次传给各阶段，阶段不再自己算。
     */
    private Path workDir(VideoTask task) {
        return workspaceDirectoryManager.ensureWorkspaceDirectory(task.getSourceUri(), task.getWorkspaceKey());
    }

    /**
     * 从持久化字段还原管线的媒体输入：变体选择与落点规则单源在 {@link MediaInput#ofTaskInput}，
     * 本方法只负责取「工作区根 + 持久化的相对目录」这一文件系统事实传给工厂（URL 路线用不到它）。
     */
    private MediaInput toMediaInput(VideoTask task) {
        return MediaInput.ofTaskInput(task.getInputType(), task.getSourceUri(),
                workspaceDirectoryManager.getWorkspaceRoot().resolve(task.getWorkspaceDir()), task.getWorkspaceKey());
    }
}
