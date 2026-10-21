package com.vidsage.infrastructure.persistence.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.vidsage.domain.workspace.Workspace;
import com.vidsage.domain.workspace.model.SourceKey;
import com.vidsage.domain.workspace.model.SourceType;
import com.vidsage.infrastructure.persistence.entity.WorkspaceEntity;
import com.vidsage.infrastructure.persistence.mapper.WorkspaceMapper;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Optional;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

/**
 * {@link MybatisWorkspaceRepository} 的纯单元测试：Mapper 用 Mockito mock，<b>不连真库</b>。
 *
 * <p>覆盖「实体 ↔ 领域对象逐字段双向转换」「insert 回填自增 id」「查询条件」「锁定读带 FOR SHARE」
 * 「指纹写入把 now 传给 SQL」，外加 {@link Workspace} 的出生状态与 {@code updateFingerprint} 行为
 * （它只有这一个变更入口，不单开测试类，随本类一起验）。
 */
class MybatisWorkspaceRepositoryTest {

    private final WorkspaceMapper mapper = Mockito.mock(WorkspaceMapper.class);
    private final MybatisWorkspaceRepository repository = new MybatisWorkspaceRepository(mapper);

    @BeforeAll
    static void initTableInfoOnce() {
        // LambdaQueryWrapper 的列名解析走 TableInfo 缓存；容器外手工注册一次
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                WorkspaceEntity.class);
    }

    // ---------- insert ----------

    @Test
    void insertMapsDomainFieldsOntoEntityAndBackfillsId() {
        Workspace workspace = Workspace.of(SourceType.URL, new SourceKey("BV11QMd6WEJp|1"),
                "https://www.bilibili.com/video/BV11QMd6WEJp", "https://www.bilibili.com/video/BV11QMd6WEJp");
        when(mapper.insert(any(WorkspaceEntity.class))).thenAnswer(invocation -> {
            invocation.getArgument(0, WorkspaceEntity.class).setId(42L);
            return 1;
        });

        Workspace inserted = repository.insert(workspace);

        assertEquals(42L, inserted.getId(), "insert 应把数据库生成的自增 id 回填进领域对象");
        ArgumentCaptor<WorkspaceEntity> captor = ArgumentCaptor.forClass(WorkspaceEntity.class);
        verify(mapper).insert(captor.capture());
        WorkspaceEntity entity = captor.getValue();
        assertEquals(SourceType.URL, entity.getSourceType());
        assertEquals("BV11QMd6WEJp|1", entity.getSourceKey());
        assertEquals("https://www.bilibili.com/video/BV11QMd6WEJp", entity.getSourceUri());
        assertEquals("https://www.bilibili.com/video/BV11QMd6WEJp", entity.getCanonicalUrl());
        assertNull(entity.getDurationMs(), "首跑指纹两列为空");
        assertNull(entity.getPublishedAt());
        assertNotNull(entity.getCreatedAt());
        assertNotNull(entity.getUpdatedAt());
    }

    @Test
    void ofLeavesFingerprintColumnsNullAndUpdateFingerprintFillsThem() {
        Workspace workspace = Workspace.of(SourceType.URL, new SourceKey("BV11QMd6WEJp|1"), "u", "c");
        assertNull(workspace.getDurationMs());
        assertNull(workspace.getPublishedAt());

        LocalDateTime published = LocalDateTime.of(2026, 1, 2, 3, 4);
        workspace.updateFingerprint(123456, published);

        assertEquals(123456, workspace.getDurationMs());
        assertEquals(published, workspace.getPublishedAt());
        assertNotNull(workspace.getUpdatedAt());
    }

    // ---------- 查询与转换 ----------

    @Test
    void findByIdMapsEntityBackToDomainVerbatim() {
        WorkspaceEntity entity = entity(7L);
        when(mapper.selectById(anyLong())).thenReturn(entity);

        Optional<Workspace> found = repository.findById(7L);

        assertTrue(found.isPresent());
        Workspace workspace = found.get();
        assertEquals(7L, workspace.getId());
        assertEquals(SourceType.UPLOAD, workspace.getSourceType());
        assertEquals("a".repeat(64), workspace.getSourceKey().value());
        assertEquals("演示视频.mp4", workspace.getSourceUri());
        assertEquals("https://example.com/v", workspace.getCanonicalUrl());
        assertEquals(120000, workspace.getDurationMs());
    }

    @Test
    void findByIdReturnsEmptyWhenMissing() {
        when(mapper.selectById(anyLong())).thenReturn(null);

        assertTrue(repository.findById(99L).isEmpty());
    }

    @Test
    void findBySourceTypeAndSourceKeyFiltersByBothColumns() {
        when(mapper.selectOne(any())).thenReturn(null);

        repository.findBySourceTypeAndSourceKey(SourceType.URL, new SourceKey("BV11QMd6WEJp|1"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<AbstractWrapper<WorkspaceEntity, ?, ?>> captor =
                ArgumentCaptor.forClass(AbstractWrapper.class);
        verify(mapper).selectOne(captor.capture());
        String segment = captor.getValue().getSqlSegment().toLowerCase(java.util.Locale.ROOT);
        assertTrue(segment.contains("source_type"), () -> "查询条件必须按 source_type 过滤：" + segment);
        assertTrue(segment.contains("source_key"), () -> "查询条件必须按 source_key 过滤：" + segment);
        Collection<Object> params = captor.getValue().getParamNameValuePairs().values();
        assertTrue(params.contains(SourceType.URL), () -> "参数应含来源类型：" + params);
        assertTrue(params.contains("BV11QMd6WEJp|1"), () -> "参数应含身份键：" + params);
    }

    @Test
    void findBySourceTypeAndSourceKeyReturnsEmptyWhenMissing() {
        when(mapper.selectOne(any())).thenReturn(null);

        assertTrue(repository.findBySourceTypeAndSourceKey(SourceType.URL, new SourceKey("k")).isEmpty());
    }

    // ---------- 锁定读 ----------

    @Test
    void findForShareUsesShareLockNotUpdateLock() {
        when(mapper.selectOne(any())).thenReturn(null);

        repository.findForShareBySourceTypeAndSourceKey(SourceType.URL, new SourceKey("BV11QMd6WEJp|1"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<AbstractWrapper<WorkspaceEntity, ?, ?>> captor =
                ArgumentCaptor.forClass(AbstractWrapper.class);
        verify(mapper).selectOne(captor.capture());
        // getSqlSegment() 已把 .last() 追加的片段拼在末尾（MP 3.5 AbstractWrapper#getSqlSegment），无需碰内部字段
        String segment = captor.getValue().getSqlSegment().toUpperCase(java.util.Locale.ROOT);
        assertTrue(segment.contains("FOR SHARE"), () -> "锁定读必须用 FOR SHARE：" + segment);
        assertFalse(segment.contains("FOR UPDATE"), () -> "FOR UPDATE 会 S→X 升级死锁：" + segment);
    }

    // ---------- updateFingerprint ----------

    @Test
    void updateFingerprintPassesNowIntoTheSameUpdate() {
        LocalDateTime now = LocalDateTime.of(2026, 10, 17, 12, 0);
        when(mapper.updateFingerprint(eq(7L), any(), any(), any())).thenReturn(1);

        int affected = repository.updateFingerprint(7L, 120000, LocalDateTime.of(2026, 1, 1, 0, 0), now);

        assertEquals(1, affected);
        verify(mapper).updateFingerprint(7L, 120000, LocalDateTime.of(2026, 1, 1, 0, 0), now);
    }

    private static WorkspaceEntity entity(Long id) {
        WorkspaceEntity entity = new WorkspaceEntity();
        entity.setId(id);
        entity.setSourceType(SourceType.UPLOAD);
        entity.setSourceKey("a".repeat(64));
        entity.setSourceUri("演示视频.mp4");
        entity.setCanonicalUrl("https://example.com/v");
        entity.setDurationMs(120000);
        entity.setPublishedAt(LocalDateTime.of(2026, 1, 1, 0, 0));
        entity.setCreatedAt(LocalDateTime.of(2026, 1, 1, 0, 0));
        entity.setUpdatedAt(LocalDateTime.of(2026, 1, 2, 0, 0));
        return entity;
    }
}
