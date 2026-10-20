package com.vidsage.application.workspace;

import com.vidsage.application.media.IntermediateFileStore;
import com.vidsage.application.subtitle.SubtitleArtifactStore;
import com.vidsage.domain.intermediate.IntermediateFile;
import com.vidsage.domain.subtitle.SubtitleArtifact;
import com.vidsage.domain.subtitle.model.ArtifactType;
import com.vidsage.domain.subtitle.port.SubtitleArtifactRepository;
import com.vidsage.domain.workspace.model.ContentFingerprint;
import com.vidsage.domain.workspace.port.WorkspaceRepository;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 媒体内容指纹的写入，与「媒体源变了」的级联作废。
 *
 * <p>两件事共用一份指纹：首次探测只写两列；探测到媒体源变了则把旧产物全部作废、再写新指纹。
 * 「媒体源变了」就是指纹对不上——同一个身份键（{@code bvid|p}）底下的片源被换掉了。
 * 后者必须是一个事务——作废没做全就写新指纹，下一次提交会以为「指纹一致」而跳过级联，
 * 捡到还没作废的旧产物当当前版用，这是静默交付错内容。
 *
 * <p><b>文件删除在事务提交之后</b>（两阶段）：译文与中间产物没有新版本会去写它们的路径，
 * 不删就是长期孤儿；删不动只记 WARN，那一行停在 {@code SUPERSEDED} 留给对账。原始与清洗产物
 * 不清文件——这次主管线会用同名文件覆盖它们，文件列一清就表达「我不再管这个文件」。
 */
@Slf4j
@Component
public class WorkspaceFingerprintStore {

    private final WorkspaceRepository workspaceRepository;
    private final SubtitleArtifactRepository artifactRepository;
    private final SubtitleArtifactStore artifactStore;
    private final IntermediateFileStore intermediateFileStore;
    private final TransactionTemplate transactionTemplate;

    public WorkspaceFingerprintStore(WorkspaceRepository workspaceRepository,
                                     SubtitleArtifactRepository artifactRepository,
                                     SubtitleArtifactStore artifactStore,
                                     IntermediateFileStore intermediateFileStore,
                                     PlatformTransactionManager transactionManager) {
        this.workspaceRepository = workspaceRepository;
        this.artifactRepository = artifactRepository;
        this.artifactStore = artifactStore;
        this.intermediateFileStore = intermediateFileStore;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /**
     * 首次探测：一条 UPDATE 写两列指纹。
     *
     * @param workspaceId 媒体身份的内部行 id
     * @param fingerprint 探测到的内容指纹
     */
    public void writeFingerprint(long workspaceId, ContentFingerprint fingerprint) {
        workspaceRepository.updateFingerprint(workspaceId, fingerprint.durationMs(), fingerprint.publishedAt(),
                LocalDateTime.now());
    }

    /**
     * 身份信息能对上，但是源媒体变了：如B站up换了视频源，用的还是原先的url和指纹，但是视频已经变了
     * 作废旧产物 + 写新指纹（同一事务），事务提交后回收译文与中间产物的文件。
     *
     * <p>作废按类型分流：原始与清洗清空文件列、文件留着给新版本覆盖；译文留着文件列，
     * 事务后按列删文件再标已回收。中间产物没有文件列可清，同样是事务后删文件再标已回收。
     *
     * <p>这里<b>不捕获异常</b>：级联失败必须让本次执行失败，否则旧内容会继续被当成当前版。
     *
     * @param taskId      所属字幕任务
     * @param workspaceId 媒体身份的内部行 id
     * @param fingerprint 新探测到的内容指纹
     * @param workDir     工作区目录（事务提交后回收文件用）
     */
    public void supersedeOnSourceMediaChange(long taskId, long workspaceId, ContentFingerprint fingerprint,
                                            Path workDir) {
        ReclaimPlan plan = transactionTemplate.execute(status -> {
            LocalDateTime now = LocalDateTime.now();
            List<SubtitleArtifact> translations = new ArrayList<>();
            for (SubtitleArtifact artifact : artifactRepository.findReadyByTaskId(taskId)) {
                switch (artifact.getArtifactType()) {
                    case TRANSLATED -> translations.add(artifact);
                    case ORIGINAL, CLEANED -> artifactRepository.markSupersededAndClearFiles(artifact.getId());
                }
            }
            if (!translations.isEmpty()) {
                artifactRepository.markSupersededByTaskIdAndType(taskId, ArtifactType.TRANSLATED);
            }
            List<IntermediateFile> intermediates = intermediateFileStore.findReady(taskId);
            intermediateFileStore.supersedeAll(taskId, now);
            workspaceRepository.updateFingerprint(workspaceId, fingerprint.durationMs(), fingerprint.publishedAt(), now);
            log.info("内容指纹变化，已作废旧产物: taskId={}, workspaceId={}, translations={}, intermediates={}",
                    taskId, workspaceId, translations.size(), intermediates.size());
            return new ReclaimPlan(translations, intermediates);
        });

        if (plan == null) {
            return;
        }
        // 事务已经提交：回收再出意外也不能把这一趟判成失败，留在 SUPERSEDED 给对账
        for (SubtitleArtifact translation : plan.translations()) {
            try {
                artifactStore.reclaimSuperseded(translation, workDir);
            } catch (RuntimeException e) {
                log.warn("译文回收失败，留在待回收状态: taskId={}, artifactId={}, reason={}",
                        taskId, translation.getId(), e.getClass().getSimpleName());
            }
        }
        for (IntermediateFile file : plan.intermediates()) {
            try {
                intermediateFileStore.reclaimSuperseded(file, workDir);
            } catch (RuntimeException e) {
                log.warn("中间产物回收失败，留在待回收状态: taskId={}, fileId={}, reason={}",
                        taskId, file.getId(), e.getClass().getSimpleName());
            }
        }
    }

    /**
     * 事务内收集、事务外回收的那两份行。
     */
    private record ReclaimPlan(List<SubtitleArtifact> translations, List<IntermediateFile> intermediates) {
    }
}
