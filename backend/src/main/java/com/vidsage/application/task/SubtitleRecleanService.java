package com.vidsage.application.task;

import com.vidsage.application.media.port.WorkspaceDirectoryManager;
import com.vidsage.application.subtitle.SubtitleArtifactStore;
import com.vidsage.application.subtitle.pipeline.SubtitleCleanupCoordinator;
import com.vidsage.application.task.exception.TaskConflictException;
import com.vidsage.application.task.exception.TaskNotFoundException;
import com.vidsage.application.task.port.TaskConcurrencyGate;
import com.vidsage.domain.subtitle.SubtitleArtifact;
import com.vidsage.domain.subtitle.model.ArtifactType;
import com.vidsage.domain.subtitle.model.keyword.SubtitleKeywords;
import com.vidsage.domain.subtitle.port.SubtitleKeywordRepository;
import com.vidsage.domain.task.SubtitleTask;
import com.vidsage.domain.task.TaskRun;
import com.vidsage.domain.task.model.RunRef;
import com.vidsage.domain.task.model.TaskRunType;
import com.vidsage.domain.workspace.Workspace;
import com.vidsage.shared.error.ThrowableUtils;
import com.vidsage.shared.media.MediaInputUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.List;

/**
 * 重新生成字幕（RECLEAN run）的完整流程：守卫段与执行段都住在这里。
 *
 * <p>守卫段在同步调用里做完——校验关键词、按 taskNo 取任务与工作区、要求有一版完好的原始产物、
 * 插一条 RUNNING run 抢互斥、把关键词落库或读回。执行段交给并发闸门排队后异步跑：占位一版新
 * CLEANED → 重跑清洗 → 落定并<b>无条件</b>作废下游译文 → 收口。
 *
 * <p>RECLEAN 是用户显式触发的「重新生成字幕」，语义上就是真重跑：不看复用判据、不比对产出，
 * 产出与旧版逐字节相同也照样作废下游译文。
 *
 * <p>未知 taskNo 抛 {@link TaskNotFoundException} → 404；没有完好的原始产物抛
 * {@link IllegalArgumentException} → 400；该任务已有一次执行在跑抛 {@link TaskConflictException} → 409。
 */
@Slf4j
@Service
public class SubtitleRecleanService {

    private final SubtitleTaskFinder finder;
    private final SubtitleKeywordRepository keywordRepository;
    private final TaskRunSupervisor supervisor;
    private final TaskConcurrencyGate concurrencyGate;
    private final SubtitleCleanupCoordinator cleanupStage;
    private final SubtitleArtifactStore artifactStore;
    private final WorkspaceDirectoryManager workspaceDirectoryManager;

    public SubtitleRecleanService(SubtitleTaskFinder finder,
                                  SubtitleKeywordRepository keywordRepository,
                                  TaskRunSupervisor supervisor,
                                  TaskConcurrencyGate concurrencyGate,
                                  SubtitleCleanupCoordinator cleanupStage,
                                  SubtitleArtifactStore artifactStore,
                                  WorkspaceDirectoryManager workspaceDirectoryManager
    ) {
        this.finder = finder;
        this.keywordRepository = keywordRepository;
        this.supervisor = supervisor;
        this.concurrencyGate = concurrencyGate;
        this.cleanupStage = cleanupStage;
        this.artifactStore = artifactStore;
        this.workspaceDirectoryManager = workspaceDirectoryManager;
    }

    /**
     * 重新生成字幕（RECLEAN run）：按当前关键词只重跑清洗，不重跑转写、不重抽音频。
     *
     * @param taskNo   对外任务号；不可为空白
     * @param keywords 请求体里的关键词：{@code null} = 不改变已有列表；{@code []} = 清空
     * @return 本次触发的 {@code {taskNo, runNo}}
     * @throws IllegalArgumentException taskNo 为空白、关键词超限、或原始字幕尚未就绪（400）
     * @throws TaskNotFoundException    任务不存在（404）
     * @throws TaskConflictException    该任务已有一次执行在跑（409）
     */
    public TaskRunRef regenerate(String taskNo, List<String> keywords) {
        SubtitleKeywords validated = keywords == null ? null : new SubtitleKeywords(keywords);
        SubtitleTask task = finder.require(taskNo);
        Workspace workspace = finder.requireWorkspace(task);
        SubtitleArtifact originalArtifact = requireIntactArtifact(task, workspace, ArtifactType.ORIGINAL,
                "原始转写产物（ORIGINAL）");
        TaskRun run = supervisor.start(task, TaskRunType.RECLEAN, null).orElseThrow(() -> supervisor.buildTaskConflictException(task));
        if (validated != null) {
            keywordRepository.replace(task.getId(), validated);
        }
        SubtitleKeywords effectiveKeywords = validated != null ? validated : keywordRepository.findByTaskId(task.getId());
        RunRef ref = RunRef.of(task.getTaskNo(), run);
        log.info("字幕重新生成已触发: taskNo={}, runNo={}, keywordsSupplied={}",
                ref.taskNo().value(), ref.runNo(), validated != null);
        concurrencyGate.submit(() -> runPipeline(run, ref, workspace, originalArtifact, effectiveKeywords),
                () -> {
                    log.warn("重新生成等待执行配额时被中断: taskNo={}, runNo={}",
                            ref.taskNo().value(), ref.runNo());
                    supervisor.finish(ref, run, "重新生成在等待执行配额时被中断：请重新触发");
                });
        return new TaskRunRef(ref.taskNo().value(), ref.runNo());
    }

    /**
     * 重新生成的执行体：先落占位，再真重跑清洗，最后落定并作废下游译文；收口是无条件的。
     */
    private void runPipeline(TaskRun run, RunRef ref, Workspace workspace,
                             SubtitleArtifact originalArtifact, SubtitleKeywords effectiveKeywords) {
        try {
            Path workDir = workspaceDirectoryManager.ensureWorkspaceDirectory(workspace.getSourceKey());
            // 占位先落：这一版从这一刻起就存在了，写文件之前版本号已经定下来
            SubtitleArtifact cleaned = artifactStore.createCreatingPlaceholder(run.getTaskId(), run.getId(),
                    ArtifactType.CLEANED, "", originalArtifact.getId(), effectiveKeywords.computeFingerprint());
            cleanupStage.regenerate(MediaInputUtils.from(workspace, workDir), workDir, effectiveKeywords,
                    supervisor.listener(ref, run));
            artifactStore.settleCleanedAndSupersedeDownstream(cleaned, workDir);
            supervisor.finish(ref, run, null);
            log.info("字幕重新生成完成: taskNo={}, runNo={}", ref.taskNo().value(), ref.runNo());
        } catch (Throwable t) {
            // 接住之后 JVM 不再打这条异常的默认堆栈，自己补一次；只打类型与调用栈
            log.error("字幕重新生成失败: taskNo={}, runNo={}\n{}", ref.taskNo().value(), ref.runNo(),
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
