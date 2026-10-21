package com.vidsage.infrastructure.persistence.repository;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.vidsage.domain.workspace.Workspace;
import com.vidsage.domain.workspace.model.SourceKey;
import com.vidsage.domain.workspace.model.SourceType;
import com.vidsage.domain.workspace.port.WorkspaceRepository;
import com.vidsage.infrastructure.persistence.entity.WorkspaceEntity;
import com.vidsage.infrastructure.persistence.mapper.WorkspaceMapper;
import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/**
 * {@code workspace} 表的 MyBatis-Plus 实现：包装 {@link WorkspaceMapper}，做「实体 ↔ 领域对象」
 * 的双向转换（转换点全项目只此一处）。值对象 {@code SourceKey} ↔ String 列的转换也收敛在此。
 */
@Repository
public class MybatisWorkspaceRepository implements WorkspaceRepository {

    private final WorkspaceMapper mapper;

    public MybatisWorkspaceRepository(WorkspaceMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * 落一份新身份；MP 的 insert 会把数据库生成的自增 id 回填进实体，返回值携带该 id。
     *
     * @param workspace 待落库的新身份（经 {@code Workspace.of} 创建）
     * @return 回填了数据库 id 的身份
     */
    public Workspace insert(Workspace workspace) {
        WorkspaceEntity entity = toEntity(workspace);
        mapper.insert(entity);
        return toDomain(entity);
    }

    /**
     * 按内部行 id 查。
     *
     * @param id 内部自增行 id
     * @return 身份；不存在时为空
     */
    public Optional<Workspace> findById(long id) {
        WorkspaceEntity entity = mapper.selectById(id);
        return entity == null ? Optional.empty() : Optional.of(toDomain(entity));
    }

    /**
     * 按身份键查（提交路径命中分支）。
     *
     * @param sourceType 来源类型
     * @param sourceKey  身份键（值对象；查询条件取其 {@code value} 落到 DB 列）
     * @return 身份；不存在时为空
     */
    public Optional<Workspace> findBySourceTypeAndSourceKey(SourceType sourceType, SourceKey sourceKey) {
        WorkspaceEntity entity = mapper.selectOne(Wrappers.<WorkspaceEntity>lambdaQuery()
                .eq(WorkspaceEntity::getSourceType, sourceType)
                .eq(WorkspaceEntity::getSourceKey, sourceKey.value()));
        return entity == null ? Optional.empty() : Optional.of(toDomain(entity));
    }

    /**
     * 按身份键<b>锁定读</b>（提交路径并发分支）：{@code FOR SHARE} 绕过一致性快照读到对方刚提交的行。
     * 用 {@code FOR SHARE} 不是 {@code FOR UPDATE} 的理由见端口 Javadoc。
     *
     * @param sourceType 来源类型
     * @param sourceKey  身份键
     * @return 身份；不存在时为空
     */
    public Optional<Workspace> findForShareBySourceTypeAndSourceKey(SourceType sourceType, SourceKey sourceKey) {
        WorkspaceEntity entity = mapper.selectOne(Wrappers.<WorkspaceEntity>lambdaQuery()
                .eq(WorkspaceEntity::getSourceType, sourceType)
                .eq(WorkspaceEntity::getSourceKey, sourceKey.value())
                .last("FOR SHARE"));
        return entity == null ? Optional.empty() : Optional.of(toDomain(entity));
    }

    /**
     * 写入内容指纹。
     *
     * @param workspaceId 内部行 id
     * @param durationMs  媒体时长（毫秒）；取不到时为 {@code null}
     * @param publishedAt 发布时间；取不到时为 {@code null}
     * @param now         本次写入的时刻（同句刷 updated_at）
     * @return 受影响行数
     */
    public int updateFingerprint(long workspaceId, Integer durationMs, LocalDateTime publishedAt,
                                 LocalDateTime now) {
        return mapper.updateFingerprint(workspaceId, durationMs, publishedAt, now);
    }

    private static Workspace toDomain(WorkspaceEntity entity) {
        return Workspace.restore(entity.getId(), entity.getSourceType(), new SourceKey(entity.getSourceKey()),
                entity.getSourceUri(), entity.getCanonicalUrl(), entity.getDurationMs(), entity.getPublishedAt(),
                entity.getCreatedAt(), entity.getUpdatedAt());
    }

    private static WorkspaceEntity toEntity(Workspace workspace) {
        WorkspaceEntity entity = new WorkspaceEntity();
        entity.setId(workspace.getId());
        entity.setSourceType(workspace.getSourceType());
        entity.setSourceKey(workspace.getSourceKey().value());
        entity.setSourceUri(workspace.getSourceUri());
        entity.setCanonicalUrl(workspace.getCanonicalUrl());
        entity.setDurationMs(workspace.getDurationMs());
        entity.setPublishedAt(workspace.getPublishedAt());
        entity.setCreatedAt(workspace.getCreatedAt());
        entity.setUpdatedAt(workspace.getUpdatedAt());
        return entity;
    }
}
