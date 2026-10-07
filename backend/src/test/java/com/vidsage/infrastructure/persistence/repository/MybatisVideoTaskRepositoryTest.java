package com.vidsage.infrastructure.persistence.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.vidsage.domain.shared.model.WorkspaceKey;
import com.vidsage.domain.task.model.TaskNo;
import com.vidsage.domain.task.VideoTask;
import com.vidsage.domain.task.model.TaskInputType;
import com.vidsage.domain.task.model.TaskStatus;
import com.vidsage.domain.task.model.TranslationStatus;
import com.vidsage.infrastructure.persistence.entity.VideoTaskEntity;
import com.vidsage.infrastructure.persistence.mapper.VideoTaskMapper;
import java.util.Collection;
import java.util.Optional;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * {@link MybatisVideoTaskRepository} 的纯单元测试：Mapper 用 Mockito mock，<b>不连真库</b>
 * （真库连通由 {@code VidSageApplicationTests} 上下文冒烟在 local profile 下验证）。
 *
 * <p>覆盖「实体 ↔ 领域对象双向转换的逐字段对齐」「insert 回填自增 id」「查重查询的过滤条件」。
 * LambdaQueryWrapper 解析列名依赖 TableInfo 缓存，纯单测需先手工初始化。
 */
class MybatisVideoTaskRepositoryTest {

    private final VideoTaskMapper mapper = org.mockito.Mockito.mock(VideoTaskMapper.class);
    private final MybatisVideoTaskRepository repository = new MybatisVideoTaskRepository(mapper);

    @BeforeAll
    static void initTableInfoOnce() {
        // LambdaQueryWrapper 的列名解析走 TableInfo 缓存；容器外手工注册一次
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                VideoTaskEntity.class);
    }

    // ---------- insert ----------

    @Test
    void insertMapsDomainFieldsOntoEntityAndBackfillsId() {
        VideoTask task = VideoTask.newPending(TaskInputType.UPLOAD, "演示视频.mp4",
                new WorkspaceKey("a1b2c3d4e5f6"), "dir-a1b2c3d4e5f6");
        when(mapper.insert(any(VideoTaskEntity.class))).thenAnswer(invocation -> {
            invocation.getArgument(0, VideoTaskEntity.class).setId(42L);
            return 1;
        });

        VideoTask inserted = repository.insert(task);

        assertEquals(42L, inserted.getId(), "insert 应把数据库生成的自增 id 回填进领域对象");
        ArgumentCaptor<VideoTaskEntity> captor = ArgumentCaptor.forClass(VideoTaskEntity.class);
        verify(mapper).insert(captor.capture());
        VideoTaskEntity entity = captor.getValue();
        assertEquals(task.getTaskNo().value(), entity.getTaskNo());
        assertEquals(TaskInputType.UPLOAD, entity.getInputType());
        assertEquals("演示视频.mp4", entity.getSourceUri());
        assertEquals("a1b2c3d4e5f6", entity.getWorkspaceKey());
        assertEquals("dir-a1b2c3d4e5f6", entity.getWorkspaceDir());
        assertEquals(TaskStatus.PENDING, entity.getStatus());
        assertNotNull(entity.getCreatedAt());
        assertNotNull(entity.getUpdatedAt());
    }

    // ---------- 查询与转换 ----------

    @Test
    void findByTaskNoMapsEntityBackToDomainVerbatim() {
        VideoTaskEntity entity = new VideoTaskEntity();
        entity.setId(7L);
        entity.setTaskNo("abc123");
        entity.setInputType(TaskInputType.URL);
        entity.setSourceUri("https://example.com/v");
        entity.setWorkspaceKey("a1b2c3d4e5f6");
        entity.setWorkspaceDir("dir-a1b2c3d4e5f6");
        entity.setStatus(TaskStatus.RUNNING);
        entity.setCurrentStage("transcribe");
        entity.setTranslationStatus(TranslationStatus.SUCCEEDED);
        entity.setErrorMessage(null);
        when(mapper.selectOne(any())).thenReturn(entity);

        Optional<VideoTask> found = repository.findByTaskNo(new TaskNo("abc123"));

        assertTrue(found.isPresent());
        VideoTask task = found.get();
        assertEquals(7L, task.getId());
        assertEquals("abc123", task.getTaskNo().value());
        assertEquals(TaskInputType.URL, task.getInputType());
        assertEquals("https://example.com/v", task.getSourceUri());
        assertEquals("a1b2c3d4e5f6", task.getWorkspaceKey().value());
        assertEquals("dir-a1b2c3d4e5f6", task.getWorkspaceDir());
        assertEquals(TaskStatus.RUNNING, task.getStatus());
        assertEquals("transcribe", task.getCurrentStage());
        assertEquals(TranslationStatus.SUCCEEDED, task.getTranslationStatus(),
                "翻译子状态应随行双向转换（NULL=从未触发 → null）");
        // 重建出的领域对象可直接继续走状态机（RUNNING → FAILED）
        task.fail("失败详情");
        assertEquals(TaskStatus.FAILED, task.getStatus());
    }

    @Test
    void findByTaskNoReturnsEmptyWhenMissing() {
        when(mapper.selectOne(any())).thenReturn(null);

        assertTrue(repository.findByTaskNo(new TaskNo("nope")).isEmpty());
    }

    // ---------- 查重查询 ----------

    @Test
    void activeWorkspaceKeyQueryFiltersBySameKeyAndPendingOrRunningStatus() {
        when(mapper.selectOne(any())).thenReturn(null);

        repository.findActiveByWorkspaceKey(new WorkspaceKey("a1b2c3d4e5f6"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<AbstractWrapper<VideoTaskEntity, ?, ?>> captor =
                ArgumentCaptor.forClass(AbstractWrapper.class);
        verify(mapper).selectOne(captor.capture());
        String segment = captor.getValue().getSqlSegment().toLowerCase(java.util.Locale.ROOT);
        assertTrue(segment.contains("workspace_key"), () -> "查重条件必须按 workspace_key 过滤：" + segment);
        assertTrue(segment.contains("status in"), () -> "查重条件必须按 status IN 过滤：" + segment);
        Collection<Object> params = captor.getValue().getParamNameValuePairs().values();
        assertTrue(params.contains("a1b2c3d4e5f6"), () -> "参数应含工作区 key：" + params);
        assertTrue(params.contains(TaskStatus.PENDING) && params.contains(TaskStatus.RUNNING),
                () -> "查重只挡 PENDING / RUNNING，不应把终态算进去：" + params);
    }

    // ---------- update ----------

    @Test
    void updateWritesMutatedDomainStateBackToEntity() {
        VideoTask task = VideoTask.restore(9L, new TaskNo("abc123"), TaskInputType.URL, "https://example.com/v",
                new WorkspaceKey("a1b2c3d4e5f6"), "dir", TaskStatus.RUNNING, "clean", null, null,
                java.time.LocalDateTime.now(), java.time.LocalDateTime.now(),
                java.time.LocalDateTime.now(), null);
        task.fail("翻译失败：请检查 DEEPSEEK_API_KEY");

        repository.update(task);

        ArgumentCaptor<VideoTaskEntity> captor = ArgumentCaptor.forClass(VideoTaskEntity.class);
        verify(mapper).updateById(captor.capture());
        VideoTaskEntity entity = captor.getValue();
        assertEquals(9L, entity.getId(), "update 应按 id 覆盖");
        assertEquals(TaskStatus.FAILED, entity.getStatus());
        assertEquals("翻译失败：请检查 DEEPSEEK_API_KEY", entity.getErrorMessage());
        assertNotNull(entity.getFinishedAt());
    }

    @Test
    void updateCarriesTranslationStatusTransition() {
        VideoTask task = VideoTask.restore(11L, new TaskNo("def456"), TaskInputType.URL, "https://example.com/v",
                new WorkspaceKey("a1b2c3d4e5f6"), "dir", TaskStatus.SUCCEEDED, "clean", null, null,
                java.time.LocalDateTime.now(), java.time.LocalDateTime.now(),
                java.time.LocalDateTime.now(), java.time.LocalDateTime.now());
        task.translationStart();

        repository.update(task);

        ArgumentCaptor<VideoTaskEntity> captor = ArgumentCaptor.forClass(VideoTaskEntity.class);
        verify(mapper).updateById(captor.capture());
        assertEquals(TranslationStatus.RUNNING, captor.getValue().getTranslationStatus(),
                "翻译子状态迁移应写回实体（NULL → RUNNING）");
        assertEquals(TaskStatus.SUCCEEDED, captor.getValue().getStatus(), "主状态不被翻译迁移改动");
    }
}
