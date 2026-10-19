package com.vidsage.infrastructure.persistence.repository;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.vidsage.domain.subtitle.SubtitleArtifact;
import com.vidsage.domain.subtitle.model.ArtifactType;
import com.vidsage.domain.subtitle.model.SubtitleArtifactStatus;
import com.vidsage.domain.subtitle.port.SubtitleArtifactRepository;
import com.vidsage.infrastructure.persistence.entity.SubtitleArtifactEntity;
import com.vidsage.infrastructure.persistence.mapper.SubtitleArtifactMapper;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/**
 * {@code subtitle_artifact} 表的 MyBatis-Plus 实现：包装 {@link SubtitleArtifactMapper}，做
 * 「实体 ↔ 领域对象」的双向转换（转换点全项目只此一处）。
 */
@Repository
public class MybatisSubtitleArtifactRepository implements SubtitleArtifactRepository {

    private final SubtitleArtifactMapper mapper;

    public MybatisSubtitleArtifactRepository(SubtitleArtifactMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * 插一条 {@code CREATING} 占位：先按 {@code (task, type, lang)} 的 {@code MAX+1} 生成
     * {@code revision} 并回填，再 INSERT。
     *
     * @param artifact 待插的占位产物（经 {@code SubtitleArtifact.createCreatingArtifact} 创建）
     * @return 回填了数据库 id 与 revision 的产物
     */
    public SubtitleArtifact insertCreating(SubtitleArtifact artifact) {
        Integer maxRevision = mapper.selectMaxRevision(artifact.getTaskId(), artifact.getArtifactType(),
                artifact.getLang());
        SubtitleArtifactEntity entity = toEntity(artifact);
        entity.setRevision((maxRevision == null ? 0 : maxRevision) + 1);
        mapper.insert(entity);
        return toDomain(entity);
    }

    /**
     * 查某一版 {@code READY} 产物（复用判据 / 读路径）。
     *
     * @param taskId 所属字幕任务
     * @param type   产物类型
     * @param lang   语种；无语言维度传 {@code null} 或空串（一律按空串匹配库里那一列）
     * @return 该 (task,type,lang) 的 READY 产物；没有时为空
     */
    public Optional<SubtitleArtifact> findReadyByTaskIdAndTypeAndLang(long taskId, ArtifactType type, String lang) {
        SubtitleArtifactEntity entity = mapper.selectOne(Wrappers.<SubtitleArtifactEntity>lambdaQuery()
                .eq(SubtitleArtifactEntity::getTaskId, taskId)
                .eq(SubtitleArtifactEntity::getArtifactType, type)
                .eq(SubtitleArtifactEntity::getLang, lang == null ? "" : lang)
                .eq(SubtitleArtifactEntity::getStatus, SubtitleArtifactStatus.READY));
        return entity == null ? Optional.empty() : Optional.of(toDomain(entity));
    }

    /**
     * 查该 task 当前全部 {@code READY} 产物。
     *
     * @param taskId 所属字幕任务
     * @return 全部 READY 产物；没有时是空列表
     */
    public List<SubtitleArtifact> findReadyByTaskId(long taskId) {
        return mapper.selectList(Wrappers.<SubtitleArtifactEntity>lambdaQuery()
                        .eq(SubtitleArtifactEntity::getTaskId, taskId)
                        .eq(SubtitleArtifactEntity::getStatus, SubtitleArtifactStatus.READY))
                .stream()
                .map(MybatisSubtitleArtifactRepository::toDomain)
                .toList();
    }

    /**
     * 换版落定：一条 UPDATE 写四列文件列 + 四列哈希列 + 置 READY。
     *
     * @param artifact 已 {@code completeCreation} 的产物（携带 id 与八个文件/哈希值）
     * @return 受影响行数
     */
    public int updateReady(SubtitleArtifact artifact) {
        return mapper.updateReady(toEntity(artifact));
    }

    /**
     * 覆盖型取代：清空文件列与哈希列，停在 {@code SUPERSEDED}。
     *
     * @param artifactId 被取代的旧产物行 id
     * @return 受影响行数
     */
    public int markSupersededAndClearFiles(long artifactId) {
        return mapper.markSupersededAndClearFiles(artifactId);
    }

    /**
     * 孤儿型取代（级联）：某任务某类型当前有效的产物一次全标掉，文件列留着。
     *
     * @param taskId 所属字幕任务
     * @param type   产物类型
     * @return 受影响行数
     */
    public int markSupersededByTaskIdAndType(long taskId, ArtifactType type) {
        return mapper.markSupersededByTaskIdAndType(taskId, type);
    }

    /**
     * 标已回收（两阶段第二步）。
     *
     * @param artifactId 产物行 id
     * @return 受影响行数
     */
    public int markDeleted(long artifactId) {
        return mapper.markDeleted(artifactId);
    }

    /**
     * 收口置位：本次 run 插的、仍停在 {@code CREATING} 的行 → {@code FAILED}。
     *
     * @param runId 产出这些占位行的 run
     * @return 受影响行数
     */
    public int markCreatingAsFailedByRunId(long runId) {
        return mapper.markCreatingAsFailedByRunId(runId);
    }

    /**
     * 启动收口第二条：残留 {@code CREATING} → {@code FAILED}（无差别批量）。
     *
     * @return 受影响行数
     */
    public int markCreatingAsFailed() {
        return mapper.markCreatingAsFailed();
    }

    private static SubtitleArtifact toDomain(SubtitleArtifactEntity entity) {
        return SubtitleArtifact.restore(entity.getId(), entity.getTaskId(), entity.getDerivedFromRunId(),
                entity.getArtifactType(), entity.getLang(), entity.getRevision(), entity.getStatus(),
                entity.getDerivedFromArtifactId(), entity.getKeywordsFingerprint(), entity.getSegmentsFileName(),
                entity.getSubtitlesFileName(), entity.getContextFileName(), entity.getTermsFileName(),
                entity.getSegmentsSha256(), entity.getSubtitlesSha256(), entity.getContextSha256(),
                entity.getTermsSha256(), entity.getCreatedAt());
    }

    private static SubtitleArtifactEntity toEntity(SubtitleArtifact artifact) {
        SubtitleArtifactEntity entity = new SubtitleArtifactEntity();
        entity.setId(artifact.getId());
        entity.setTaskId(artifact.getTaskId());
        entity.setDerivedFromRunId(artifact.getDerivedFromRunId());
        entity.setArtifactType(artifact.getArtifactType());
        entity.setLang(artifact.getLang());
        entity.setRevision(artifact.getRevision());
        entity.setStatus(artifact.getStatus());
        entity.setDerivedFromArtifactId(artifact.getDerivedFromArtifactId());
        entity.setKeywordsFingerprint(artifact.getKeywordsFingerprint());
        entity.setSegmentsFileName(artifact.getSegmentsFileName());
        entity.setSubtitlesFileName(artifact.getSubtitlesFileName());
        entity.setContextFileName(artifact.getContextFileName());
        entity.setTermsFileName(artifact.getTermsFileName());
        entity.setSegmentsSha256(artifact.getSegmentsSha256());
        entity.setSubtitlesSha256(artifact.getSubtitlesSha256());
        entity.setContextSha256(artifact.getContextSha256());
        entity.setTermsSha256(artifact.getTermsSha256());
        entity.setCreatedAt(artifact.getCreatedAt());
        return entity;
    }
}
