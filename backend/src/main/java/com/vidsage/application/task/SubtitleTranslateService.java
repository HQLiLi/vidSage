package com.vidsage.application.task;

import com.vidsage.application.media.port.WorkspaceDirectoryManager;
import com.vidsage.application.subtitle.SubtitleArtifactStore;
import com.vidsage.application.subtitle.pipeline.SubtitleTranslationCoordinator;
import com.vidsage.application.task.exception.TaskConflictException;
import com.vidsage.application.task.exception.TaskNotFoundException;
import com.vidsage.application.task.port.TaskConcurrencyGate;
import com.vidsage.domain.shared.model.StageProgress;
import com.vidsage.domain.shared.port.ProgressListener;
import com.vidsage.domain.subtitle.SubtitleArtifact;
import com.vidsage.domain.subtitle.model.ArtifactType;
import com.vidsage.domain.subtitle.model.common.SubtitleStageName;
import com.vidsage.domain.subtitle.model.translation.SupportedLanguage;
import com.vidsage.domain.task.SubtitleTask;
import com.vidsage.domain.task.TaskRun;
import com.vidsage.domain.task.model.RunRef;
import com.vidsage.domain.task.model.TaskRunType;
import com.vidsage.domain.workspace.Workspace;
import com.vidsage.shared.error.ThrowableUtils;
import com.vidsage.shared.media.MediaInputUtils;
import com.vidsage.shared.progress.StageEvents;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.Optional;

/**
 * 翻译用例（TRANSLATE run）：在已有的清洗产物上产出一版译文。
 *
 * <p>入口先把守卫做齐（语种在白名单内、任务与工作区存在、清洗产物完好、抢到互斥），
 * 守卫过了才把执行体交给并发闸门排队；排队期间被打断时收口置 FAILED。
 *
 * <p>目标语种已经有完好的当前译文时，这一趟什么都不做：不调翻译模型、不落新版本、
 * 文件一个字节不动，只广播一条 SKIPPED 事件就收口 SUCCEEDED。占位落在翻译成功之后——
 * LLM 失败时库里不留垃圾行。
 */
@Slf4j
@Service
public class SubtitleTranslateService {

    /**
     * 翻译阶段的进度终点（0..100 区间自报口径）。
     */
    private static final int STAGE_DONE = 100;

    private final SubtitleTaskFinder finder;
    private final TaskRunSupervisor supervisor;
    private final TaskConcurrencyGate concurrencyGate;
    private final SubtitleTranslationCoordinator translationStage;
    private final SubtitleArtifactStore artifactStore;
    private final WorkspaceDirectoryManager workspaceDirectoryManager;

    public SubtitleTranslateService(SubtitleTaskFinder finder,
                                    TaskRunSupervisor supervisor,
                                    TaskConcurrencyGate concurrencyGate,
                                    SubtitleTranslationCoordinator translationStage,
                                    SubtitleArtifactStore artifactStore,
                                    WorkspaceDirectoryManager workspaceDirectoryManager
    ) {
        this.finder = finder;
        this.supervisor = supervisor;
        this.concurrencyGate = concurrencyGate;
        this.translationStage = translationStage;
        this.artifactStore = artifactStore;
        this.workspaceDirectoryManager = workspaceDirectoryManager;
    }

    /**
     * 翻译（TRANSLATE run）：在已有清洗产物上产出译文。
     *
     * @param taskNo         对外任务号；不可为空白
     * @param sourceLanguage 源语种
     * @param targetLanguage 目标语种
     * @return 本次触发的 {@code {taskNo, runNo}}
     * @throws IllegalArgumentException taskNo 为空白、语种不在白名单、或清洗字幕尚未就绪（400）
     * @throws TaskNotFoundException    任务不存在（404）
     * @throws TaskConflictException    该任务已有一次执行在跑（409）
     */
    public TaskRunRef translate(String taskNo, String sourceLanguage, String targetLanguage) {
        SupportedLanguage source = SupportedLanguage.parse(sourceLanguage);
        SupportedLanguage target = SupportedLanguage.parse(targetLanguage);
        SubtitleTask task = finder.require(taskNo);
        Workspace workspace = finder.requireWorkspace(task);
        SubtitleArtifact cleaned = requireIntactArtifact(task, workspace, ArtifactType.CLEANED,
                "清洗后的字幕产物（CLEANED）");
        TaskRun run = supervisor.start(task, TaskRunType.TRANSLATE, target.getCode())
                .orElseThrow(() -> supervisor.buildTaskConflictException(task));
        RunRef ref = RunRef.of(task.getTaskNo(), run);
        log.info("字幕翻译已触发: taskNo={}, runNo={}, source={}, target={}",
                ref.taskNo().value(), ref.runNo(), source.getCode(), target.getCode());
        concurrencyGate.submit(() -> runPipeline(run, ref, workspace, source, target, cleaned),
                () -> {
                    log.warn("翻译等待执行配额时被中断: taskNo={}, runNo={}",
                            ref.taskNo().value(), ref.runNo());
                    supervisor.finish(ref, run, "翻译在等待执行配额时被中断：请重新触发翻译");
                });
        return new TaskRunRef(ref.taskNo().value(), ref.runNo());
    }

    /**
     * 翻译的执行体：当前语种已有完好译文就直接收口，否则真翻译，成功之后才落占位；收口是无条件的。
     */
    private void runPipeline(TaskRun run, RunRef ref, Workspace workspace,
                             SupportedLanguage source, SupportedLanguage target, SubtitleArtifact cleaned) {
        try {
            Path workDir = workspaceDirectoryManager.ensureWorkspaceDirectory(workspace.getSourceKey());
            ProgressListener listener = supervisor.listener(ref, run);

            // 当前语种已经有完好的 READY 译文：不调 LLM、不落新版本，文件一个字节不动
            Optional<SubtitleArtifact> current = artifactStore.findIntactArtifact(
                    run.getTaskId(), ArtifactType.TRANSLATED, target.getCode(), workDir);
            if (current.isPresent()) {
                StageEvents.emit(listener, SubtitleStageName.TRANSLATE_SUBTITLES.getStageName(), STAGE_DONE,
                        "已有当前译文，跳过翻译", StageProgress.State.SKIPPED);
                log.info("译文已是最新，跳过翻译: taskNo={}, runNo={}, lang={}",
                        ref.taskNo().value(), ref.runNo(), target.getCode());
                supervisor.finish(ref, run, null);
                return;
            }

            translationStage.translate(MediaInputUtils.from(workspace, workDir), workDir, source, target, listener);

            // 翻译成功之后才落占位：LLM 失败时库里不留垃圾行
            SubtitleArtifact translated = artifactStore.createCreatingPlaceholder(run.getTaskId(), run.getId(),
                    ArtifactType.TRANSLATED, target.getCode(), cleaned.getId(), null);
            artifactStore.settleArtifact(translated, workDir);

            supervisor.finish(ref, run, null);
            log.info("任务翻译完成: taskNo={}, runNo={}", ref.taskNo().value(), ref.runNo());
        } catch (Throwable t) {
            // 接住之后 JVM 不再打这条异常的默认堆栈，自己补一次；只打类型与调用栈
            log.error("翻译失败: taskNo={}, runNo={}\n{}", ref.taskNo().value(), ref.runNo(),
                    ThrowableUtils.stackOf(t));
            supervisor.fail(ref, run, t);
        }
    }

    /**
     * 前置守卫：该任务必须有一版完好的当前产物（行是 READY，而且该带的文件都在盘上），并把它交出来。
     *
     * <p>返回的是<b>当下</b>那一版，调用方拿它的 id 填新产物的溯源字段；那个字段只用于审计，
     * 不参与任何读取判据。
     *
     * <p>读路径只把缺文件的产物当没有，<b>不做自愈重跑</b>——重新生成由用户重新触发。
     */
    private SubtitleArtifact requireIntactArtifact(SubtitleTask task, Workspace workspace,
                                                   ArtifactType type, String description) {
        Path workDir = finder.workspaceDir(workspace);
        return artifactStore.findIntactArtifact(task.getId(), type, "", workDir)
                .orElseThrow(() -> new IllegalArgumentException("字幕尚未就绪：该任务还没有完好的 " + description
                        + "；请先等主管线跑完（可查 GET /api/tasks/{taskNo} 或订阅其 SSE 事件）再试"));
    }

}
