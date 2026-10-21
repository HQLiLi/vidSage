package com.vidsage.infrastructure.persistence.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.vidsage.domain.task.SubtitleTask;
import com.vidsage.domain.task.model.TaskNo;
import com.vidsage.infrastructure.persistence.entity.SubtitleTaskEntity;
import com.vidsage.infrastructure.persistence.mapper.SubtitleTaskMapper;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.apache.ibatis.annotations.Update;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.SqlSource;
import org.apache.ibatis.scripting.xmltags.XMLLanguageDriver;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

/**
 * {@link MybatisSubtitleTaskRepository} 的纯单元测试：Mapper 用 Mockito mock，<b>不连真库</b>。
 *
 * <p>覆盖「实体 ↔ 领域对象逐字段双向转换」「insert 回填自增 id」「查询条件」「锁定读带 FOR SHARE」
 * 「批量刷 updated_at 走一条 IN 语句、空集合不发语句」，外加 {@link SubtitleTask} 的出生状态与
 * {@code recordRunFinished}（它没有状态列，不单开测试类，随本类一起验）。
 */
class MybatisSubtitleTaskRepositoryTest {

    private final SubtitleTaskMapper mapper = Mockito.mock(SubtitleTaskMapper.class);
    private final MybatisSubtitleTaskRepository repository = new MybatisSubtitleTaskRepository(mapper);

    @BeforeAll
    static void initTableInfoOnce() {
        // LambdaQueryWrapper 的列名解析走 TableInfo 缓存；容器外手工注册一次
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                SubtitleTaskEntity.class);
    }

    // ---------- insert ----------

    @Test
    void insertMapsDomainFieldsOntoEntityAndBackfillsId() {
        SubtitleTask task = SubtitleTask.of(7L);
        when(mapper.insert(any(SubtitleTaskEntity.class))).thenAnswer(invocation -> {
            invocation.getArgument(0, SubtitleTaskEntity.class).setId(42L);
            return 1;
        });

        SubtitleTask inserted = repository.insert(task);

        assertEquals(42L, inserted.getId(), "insert 应把数据库生成的自增 id 回填进领域对象");
        ArgumentCaptor<SubtitleTaskEntity> captor = ArgumentCaptor.forClass(SubtitleTaskEntity.class);
        verify(mapper).insert(captor.capture());
        SubtitleTaskEntity entity = captor.getValue();
        assertEquals(task.getTaskNo().value(), entity.getTaskNo());
        assertEquals(7L, entity.getWorkspaceId());
        assertNotNull(entity.getCreatedAt());
        assertNotNull(entity.getUpdatedAt());
    }

    @Test
    void ofGeneratesStableTaskNoAndRecordRunFinishedRefreshesIt() {
        SubtitleTask task = SubtitleTask.of(7L);
        assertTrue(task.getTaskNo().value().matches("[0-9a-f]{32}"),
                () -> "taskNo 应为 32 位十六进制：" + task.getTaskNo().value());
        assertEquals(task.getCreatedAt(), task.getUpdatedAt());

        LocalDateTime at = task.getCreatedAt().plusMinutes(5);
        task.recordRunFinished(at);

        assertEquals(at, task.getUpdatedAt());
    }

    // ---------- 查询与转换 ----------

    @Test
    void findByTaskNoMapsEntityBackToDomainVerbatim() {
        when(mapper.selectOne(any())).thenReturn(entity());

        Optional<SubtitleTask> found = repository.findByTaskNo(new TaskNo("abc123"));

        assertTrue(found.isPresent());
        SubtitleTask task = found.get();
        assertEquals(7L, task.getId());
        assertEquals("abc123", task.getTaskNo().value());
        assertEquals(3L, task.getWorkspaceId());
    }

    @Test
    void findByTaskNoReturnsEmptyWhenMissing() {
        when(mapper.selectOne(any())).thenReturn(null);

        assertTrue(repository.findByTaskNo(new TaskNo("nope")).isEmpty());
    }

    @Test
    void findByWorkspaceIdFiltersByWorkspaceId() {
        when(mapper.selectOne(any())).thenReturn(null);

        repository.findByWorkspaceId(3L);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<AbstractWrapper<SubtitleTaskEntity, ?, ?>> captor =
                ArgumentCaptor.forClass(AbstractWrapper.class);
        verify(mapper).selectOne(captor.capture());
        String segment = captor.getValue().getSqlSegment().toLowerCase(java.util.Locale.ROOT);
        assertTrue(segment.contains("workspace_id"), () -> "查询条件必须按 workspace_id 过滤：" + segment);
        assertTrue(captor.getValue().getParamNameValuePairs().values().contains(3L));
    }

    @Test
    void findByWorkspaceIdReturnsEmptyWhenMissing() {
        when(mapper.selectOne(any())).thenReturn(null);

        assertTrue(repository.findByWorkspaceId(3L).isEmpty());
    }

    // ---------- 锁定读 ----------

    @Test
    void findForShareUsesShareLockNotUpdateLock() {
        when(mapper.selectOne(any())).thenReturn(null);

        repository.findForShareByWorkspaceId(3L);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<AbstractWrapper<SubtitleTaskEntity, ?, ?>> captor =
                ArgumentCaptor.forClass(AbstractWrapper.class);
        verify(mapper).selectOne(captor.capture());
        // getSqlSegment() 已把 .last() 追加的片段拼在末尾（MP 3.5 AbstractWrapper#getSqlSegment），无需碰内部字段
        String segment = captor.getValue().getSqlSegment().toUpperCase(java.util.Locale.ROOT);
        assertTrue(segment.contains("FOR SHARE"), () -> "锁定读必须用 FOR SHARE：" + segment);
        assertFalse(segment.contains("FOR UPDATE"), () -> "FOR UPDATE 会 S→X 升级死锁：" + segment);
    }

    // ---------- touchUpdatedAt ----------

    @Test
    void touchUpdatedAtDelegatesOneStatementForBatch() {
        LocalDateTime at = LocalDateTime.of(2026, 10, 17, 12, 0);
        when(mapper.touchUpdatedAt(anyCollection(), eq(at))).thenReturn(2);

        int affected = repository.touchUpdatedAt(List.of(1L, 2L), at);

        assertEquals(2, affected);
        verify(mapper).touchUpdatedAt(List.of(1L, 2L), at);
    }

    @Test
    void touchUpdatedAtWithEmptyCollectionIsNoOp() {
        assertEquals(0, repository.touchUpdatedAt(List.of(), LocalDateTime.now()));

        verifyNoInteractions(mapper);
    }

    @Test
    void touchUpdatedAtMapperScriptParsesAndBuildsAnInClause() {
        // 纯单测里 Mapper 是 mock，@Update 的 <script> 只有真启动才被解析；这里手工解析一遍，
        // 钉住「脚本以 <script> 起头、foreach 拼出一条 IN」——写错的话服务起不来。
        String script = annotationScriptOfTouchUpdatedAt();
        SqlSource sqlSource = new XMLLanguageDriver()
                .createSqlSource(new MybatisConfiguration(), script, Map.class);
        BoundSql boundSql = sqlSource.getBoundSql(
                Map.of("taskIds", List.of(1L, 2L), "at", LocalDateTime.now()));

        String sql = boundSql.getSql().replaceAll("\\s+", " ").toUpperCase(java.util.Locale.ROOT);
        assertTrue(sql.contains("UPDATE SUBTITLE_TASK"), sql);
        assertTrue(sql.contains("SET UPDATED_AT"), sql);
        assertTrue(sql.contains("WHERE ID IN ("), () -> "批量刷要走一条 IN 语句：" + sql);
    }

    /**
     * 读 {@code SubtitleTaskMapper#touchUpdatedAt} 上 {@code @Update} 的脚本原文。
     */
    private static String annotationScriptOfTouchUpdatedAt() {
        try {
            Update update = SubtitleTaskMapper.class
                    .getMethod("touchUpdatedAt", java.util.Collection.class, LocalDateTime.class)
                    .getAnnotation(Update.class);
            return update.value()[0];
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException("找不到 touchUpdatedAt 方法", e);
        }
    }

    private static SubtitleTaskEntity entity() {
        SubtitleTaskEntity entity = new SubtitleTaskEntity();
        entity.setId(7L);
        entity.setTaskNo("abc123");
        entity.setWorkspaceId(3L);
        entity.setCreatedAt(LocalDateTime.of(2026, 1, 1, 0, 0));
        entity.setUpdatedAt(LocalDateTime.of(2026, 1, 2, 0, 0));
        return entity;
    }
}
