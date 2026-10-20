package com.vidsage.application.subtitle.pipeline;

import com.vidsage.application.media.IntermediateFileOwner;
import com.vidsage.application.media.port.WorkspaceDirectoryManager;
import com.vidsage.application.subtitle.SubtitleArtifactStore;
import com.vidsage.application.task.TaskRunSupervisor;
import com.vidsage.application.task.port.TaskConcurrencyGate;
import com.vidsage.domain.media.model.MediaInput;
import com.vidsage.domain.shared.model.StageProgress;
import com.vidsage.domain.shared.port.ProgressListener;
import com.vidsage.domain.subtitle.SubtitleArtifact;
import com.vidsage.domain.subtitle.model.ArtifactType;
import com.vidsage.domain.subtitle.model.common.SubtitleStageName;
import com.vidsage.domain.task.TaskRun;
import com.vidsage.domain.subtitle.model.keyword.SubtitleKeywords;
import com.vidsage.domain.subtitle.model.translation.SupportedLanguage;
import com.vidsage.domain.subtitle.port.SubtitleKeywordRepository;
import com.vidsage.domain.task.model.RunRef;
import com.vidsage.domain.workspace.Workspace;
import com.vidsage.shared.progress.StageEvents;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.Optional;

/**
 * 媒体转字幕任务的主管线：一次执行经并发闸门排队后按顺序驱动各阶段，并收口终态。
 *
 * <p>三条执行体各对应一种 run：主管线（获取媒体 → 转写 → 清洗）、重新生成字幕（只重洗）、
 * 翻译。任一阶段抛异常 → 收口置 FAILED；走到底 → 收口置 SUCCEEDED。收口一律委托
 * {@link TaskRunSupervisor}，本类只负责按顺序调用阶段。
 *
 * <p>工作目录与媒体输入都从任务持有的 {@link Workspace} 现推：目录名是身份键的纯函数，
 * 输入按来源类型挑变体。变体选择与上传落点单源就在 {@link #toMediaInput}。
 */
@Slf4j
@Component
public class SubtitlePipeline {

    /**
     * 翻译阶段的进度终点（0..100 区间自报口径）。
     */
    private static final int STAGE_DONE = 100;

    private final TaskConcurrencyGate concurrencyGate;
    private final SubtitleKeywordRepository keywordRepository;
    private final SubtitlePreparationCoordinator subtitlePreparationCoordinator;
    private final SubtitleCleanupCoordinator cleanupStage;
    private final SubtitleTranslationCoordinator translationStage;
    private final SubtitleArtifactStore artifactStore;
    private final SubtitleFingerprintCoordinator fingerprintCoordinator;
    private final TaskRunSupervisor supervisor;
    private final WorkspaceDirectoryManager workspaceDirectoryManager;

    /**
     * @param keywordRepository              关键词持久化入口（按任务读用户手填的关键词）
     * @param subtitlePreparationCoordinator 阶段 1：音频准备 + 转写 + 写原始字幕
     * @param cleanupStage                   阶段 2：字幕清洗，也是重新生成字幕的执行体
     * @param translationStage               翻译执行体
     * @param artifactStore                  产物版本读写门面（占位与落定）
     * @param fingerprintCoordinator         内容指纹探测阶段（媒体获取之前）
     * @param supervisor                     收口与广播的机制类
     * @param concurrencyGate                并行任务闸门
     * @param workspaceDirectoryManager      工作区目录管理器
     */
    public SubtitlePipeline(SubtitleKeywordRepository keywordRepository,
                            SubtitlePreparationCoordinator subtitlePreparationCoordinator,
                            SubtitleCleanupCoordinator cleanupStage,
                            SubtitleTranslationCoordinator translationStage,
                            SubtitleArtifactStore artifactStore,
                            SubtitleFingerprintCoordinator fingerprintCoordinator,
                            TaskRunSupervisor supervisor,
                            TaskConcurrencyGate concurrencyGate,
                            WorkspaceDirectoryManager workspaceDirectoryManager
    ) {
        this.keywordRepository = keywordRepository;
        this.subtitlePreparationCoordinator = subtitlePreparationCoordinator;
        this.cleanupStage = cleanupStage;
        this.translationStage = translationStage;
        this.artifactStore = artifactStore;
        this.fingerprintCoordinator = fingerprintCoordinator;
        this.supervisor = supervisor;
        this.concurrencyGate = concurrencyGate;
        this.workspaceDirectoryManager = workspaceDirectoryManager;
    }

    /**
     * 主管线（MAIN run）：获取媒体 → 转写 → 清洗。异步执行，调用方拿到 202 即返回。
     *
     * @param run       本次执行（已落库、状态 RUNNING）
     * @param ref       这条 run 的对外引用
     * @param workspace 任务所属的媒体身份
     */
    public void run(TaskRun run, RunRef ref, Workspace workspace) {
        concurrencyGate.submit(() -> runPipeline(run, ref, workspace),
                () -> {
                    log.warn("主管线等待执行配额时被中断: taskNo={}, runNo={}", ref.taskNo().value(), ref.runNo());
                    supervisor.finish(ref, run, "任务在等待执行配额时被中断：请重新提交");
                });
    }

    /**
     * 重新生成字幕（RECLEAN run）：按给定关键词只重跑清洗，不重跑转写、不重抽音频。
     * 同步段的判定（404 / 400 未就绪 / 409 互斥 / 关键词落库）在 {@code SubtitleTaskService} 完成。
     *
     * @param run                本次执行（已落库、状态 RUNNING）
     * @param ref                这条 run 的对外引用
     * @param workspace          任务所属的媒体身份
     * @param keywords           本次要用的关键词（调用方已落库或读回）
     * @param originalArtifactId 当前有效的那一版原始产物，记在新清洗产物的溯源字段上
     */
    public void regenerate(TaskRun run, RunRef ref, Workspace workspace, SubtitleKeywords keywords,
                           long originalArtifactId) {
        concurrencyGate.submit(() -> {
            try {
                Path workDir = workDir(workspace);
                // 占位先落：这一版从这一刻起就存在了，写文件之前版本号已经定下来
                SubtitleArtifact cleaned = artifactStore.createCreatingPlaceholder(run.getTaskId(), run.getId(),
                        ArtifactType.CLEANED, "", originalArtifactId, keywords.computeFingerprint());
                cleanupStage.regenerate(toMediaInput(workspace, workDir), workDir, keywords,
                        progressListener(ref, run));
                artifactStore.settleCleanedAndSupersedeDownstream(cleaned, workDir);
                supervisor.finish(ref, run, null);
                log.info("字幕重新生成完成: taskNo={}, runNo={}", ref.taskNo().value(), ref.runNo());
            } catch (Throwable t) {
                // 接住之后 JVM 不再打这条异常的默认堆栈，自己补一次；只打类型与调用栈
                log.error("字幕重新生成失败: taskNo={}, runNo={}\n{}", ref.taskNo().value(), ref.runNo(),
                        stackOf(t));
                supervisor.finish(ref, run, reason(t));
            }
        }, () -> {
            log.warn("重新生成等待执行配额时被中断: taskNo={}, runNo={}", ref.taskNo().value(), ref.runNo());
            supervisor.finish(ref, run, "重新生成在等待执行配额时被中断：请重新触发");
        });
    }

    /**
     * 翻译（TRANSLATE run）：在已有清洗产物上产出译文。当前目标语种已有完好的 READY 译文时直接收尾，
     * 不调翻译模型、不落新版本。
     *
     * @param run                本次执行（已落库、状态 RUNNING，带目标语种）
     * @param ref                这条 run 的对外引用
     * @param workspace          任务所属的媒体身份
     * @param source             源语种
     * @param target             目标语种
     * @param cleanedArtifactId  当前有效的那一版清洗产物，记在新译文的溯源字段上
     */
    public void translate(TaskRun run, RunRef ref, Workspace workspace,
                          SupportedLanguage source, SupportedLanguage target, long cleanedArtifactId) {
        concurrencyGate.submit(() -> {
            try {
                Path workDir = workDir(workspace);
                ProgressListener listener = progressListener(ref, run);

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

                translationStage.translate(toMediaInput(workspace, workDir), workDir, source, target, listener);

                // 翻译成功之后才落占位：LLM 失败时库里不留垃圾行
                SubtitleArtifact translated = artifactStore.createCreatingPlaceholder(run.getTaskId(), run.getId(),
                        ArtifactType.TRANSLATED, target.getCode(), cleanedArtifactId, null);
                artifactStore.settleArtifact(translated, workDir);

                supervisor.finish(ref, run, null);
                log.info("任务翻译完成: taskNo={}, runNo={}", ref.taskNo().value(), ref.runNo());
            } catch (Throwable t) {
                // 接住之后 JVM 不再打这条异常的默认堆栈，自己补一次；只打类型与调用栈
                log.error("翻译失败: taskNo={}, runNo={}\n{}", ref.taskNo().value(), ref.runNo(), stackOf(t));
                supervisor.finish(ref, run, reason(t));
            }
        }, () -> {
            log.warn("翻译等待执行配额时被中断: taskNo={}, runNo={}", ref.taskNo().value(), ref.runNo());
            supervisor.finish(ref, run, "翻译在等待执行配额时被中断：请重新触发翻译");
        });
    }

    /**
     * 同步执行完整主管线。包内可见——单测直接调它绕开虚拟线程的时序，生产路径走 {@link #run}。
     *
     * <p>收口是无条件的：正常走到底收 SUCCEEDED，任一阶段抛出收 FAILED（把异常消息当失败原因，
     * 拿不到消息就退回异常类名）。
     */
    void runPipeline(TaskRun run, RunRef ref, Workspace workspace) {
        try {
            Path workDir = workDir(workspace);
            MediaInput input = toMediaInput(workspace, workDir);
            log.info("任务开始执行: taskNo={}, runNo={}, sourceType={}",
                    ref.taskNo().value(), ref.runNo(), workspace.getSourceType());
            ProgressListener listener = progressListener(ref, run);

            // 指纹探测在媒体获取之前：内容变了就先把旧产物作废，后面每一步都不会再拿旧文件复用
            fingerprintCoordinator.detectContentChange(run.getTaskId(), workspace, input, workDir, listener);
            IntermediateFileOwner owner = new IntermediateFileOwner(run.getTaskId(), run.getId());

            // 阶段 1：先占位，再让 prepare 写文件，最后落定原始产物。
            // 「产物表里有没有完好的原始产物」先算出来：没有就不许走盘上的文件检查点
            boolean originalIntact = artifactStore
                    .findIntactArtifact(run.getTaskId(), ArtifactType.ORIGINAL, "", workDir).isPresent();
            SubtitleArtifact original = artifactStore.createCreatingPlaceholder(run.getTaskId(), run.getId(),
                    ArtifactType.ORIGINAL, "", null, null);
            subtitlePreparationCoordinator.prepare(input, workDir, listener, originalIntact, owner);
            original = artifactStore.settleArtifact(original, workDir);

            // 阶段 2：清洗字幕文件（关键词按任务读，跟产物同粒度），清洗产物继承自刚落定的原始产物
            SubtitleKeywords keywords = keywordRepository.findByTaskId(run.getTaskId());
            boolean cleanedIntact = artifactStore
                    .findIntactArtifact(run.getTaskId(), ArtifactType.CLEANED, "", workDir).isPresent();
            SubtitleArtifact cleaned = artifactStore.createCreatingPlaceholder(run.getTaskId(), run.getId(),
                    ArtifactType.CLEANED, "", original.getId(), keywords.computeFingerprint());
            cleanupStage.clean(input, workDir, keywords, listener, cleanedIntact);
            // 清洗是唯一有下游的产物：换版时要把基于旧清洗的译文一起作废
            artifactStore.settleCleanedAndSupersedeDownstream(cleaned, workDir);
            supervisor.finish(ref, run, null);
            log.info("任务执行成功: taskNo={}, runNo={}", ref.taskNo().value(), ref.runNo());
        } catch (Throwable t) {
            // 接住之后 JVM 不再打这条异常的默认堆栈，自己补一次；只打类型与调用栈
            log.error("任务执行失败: taskNo={}, runNo={}\n{}", ref.taskNo().value(), ref.runNo(), stackOf(t));
            supervisor.finish(ref, run, reason(t));
        }
    }

    /**
     * 异常的类名与调用栈帧（<b>不含 message</b>）：异常消息可能带 URL（下载器会把外部命令的 stderr 拼进去），
     * 而日志有「来源原文不得落盘」的红线；消息本身已落在 {@code run.error_message} 里，排查时去那儿看。
     */
    private static String stackOf(Throwable t) {
        StringBuilder out = new StringBuilder();
        for (Throwable cur = t; cur != null; cur = cur.getCause()) {
            if (out.length() > 0) {
                out.append("\nCaused by: ");
            }
            out.append(cur.getClass().getName());
            for (StackTraceElement frame : cur.getStackTrace()) {
                out.append("\n\tat ").append(frame);
            }
        }
        return out.toString();
    }

    /**
     * 进度回调：把阶段事件交给收口机制类落 {@code current_stage} 并广播。
     */
    private ProgressListener progressListener(RunRef ref, TaskRun run) {
        return stageProgress -> {
            log.debug("任务阶段事件: taskNo={}, runNo={}, stage={}, progress={}, state={}, message={}",
                    ref.taskNo().value(), ref.runNo(), stageProgress.stage(), stageProgress.progress(),
                    stageProgress.state(), stageProgress.message());
            supervisor.advance(ref, run, stageProgress);
        };
    }

    /**
     * 定位这条任务的工作区目录（不存在则创建）：目录名是身份键的纯函数。
     * 算一次传给各阶段，阶段不再自己算。
     */
    private Path workDir(Workspace workspace) {
        return workspaceDirectoryManager.ensureWorkspaceDirectory(workspace.getSourceKey());
    }

    /**
     * 从任务持有的媒体身份重建管线输入：URL 路线给下载器一条链接，上传路线给本机已经落盘的
     * {@code source.mp4}。变体选择与上传落点单源就在这一处。
     */
    private MediaInput toMediaInput(Workspace workspace, Path workDir) {
        return switch (workspace.getSourceType()) {
            case URL -> new MediaInput.RemoteUrl(workspace.getSourceUri());
            case UPLOAD -> new MediaInput.InWorkspace(workDir.resolve(MediaInput.UPLOAD_SOURCE_FILE_NAME));
        };
    }

    /**
     * 失败原因：异常消息优先，拿不到时退回异常类名（消息为 null 时不传播字面量 "null"）。
     */
    private static String reason(Throwable cause) {
        return cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
    }
}
