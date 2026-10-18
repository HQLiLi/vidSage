package com.vidsage.infrastructure.persistence.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.vidsage.domain.subtitle.TaskRun;
import com.vidsage.domain.subtitle.model.TaskRunStatus;
import com.vidsage.domain.subtitle.model.TaskRunType;
import com.vidsage.infrastructure.persistence.entity.TaskRunEntity;
import com.vidsage.infrastructure.persistence.mapper.TaskRunMapper;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.dao.DuplicateKeyException;

/**
 * {@link MybatisTaskRunRepository} 的纯单元测试：Mapper 用 Mockito mock，<b>不连真库</b>。
 *
 * <p>覆盖「实体 ↔ 领域对象逐字段双向转换」「insertRunning 按 MAX+1 回填 run_no 与自增 id」
 * 「撞 uk_active_run 时返回空、不外抛」「查最近一条按 run_no 倒序」「启动收口先取后收」。
 */
class MybatisTaskRunRepositoryTest {

    private final TaskRunMapper mapper = Mockito.mock(TaskRunMapper.class);
    private final MybatisTaskRunRepository repository = new MybatisTaskRunRepository(mapper);

    @BeforeAll
    static void initTableInfoOnce() {
        // LambdaQueryWrapper 的列名解析走 TableInfo 缓存；容器外手工注册一次
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                TaskRunEntity.class);
    }

    // ---------- insertRunning ----------

    @Test
    void insertRunningGeneratesRunNoFromMaxPlusOneAndBackfillsId() {
        when(mapper.selectMaxRunNo(7L)).thenReturn(2);
        when(mapper.insert(any(TaskRunEntity.class))).thenAnswer(invocation -> {
            invocation.getArgument(0, TaskRunEntity.class).setId(42L);
            return 1;
        });

        Optional<TaskRun> inserted = repository.insertRunning(TaskRun.of(7L, TaskRunType.MAIN, null));

        assertTrue(inserted.isPresent());
        assertEquals(42L, inserted.get().getId());
        assertEquals(3, inserted.get().getRunNo(), "run_no = 该 task 当前 MAX + 1");
        ArgumentCaptor<TaskRunEntity> captor = ArgumentCaptor.forClass(TaskRunEntity.class);
        verify(mapper).insert(captor.capture());
        TaskRunEntity entity = captor.getValue();
        assertEquals(7L, entity.getTaskId());
        assertEquals(3, entity.getRunNo());
        assertEquals(TaskRunType.MAIN, entity.getRunType());
        assertEquals(TaskRunStatus.RUNNING, entity.getStatus());
        assertNotNull(entity.getStartedAt());
    }

    @Test
    void insertRunningStartsFromOneWhenTaskHasNoRunsYet() {
        when(mapper.selectMaxRunNo(7L)).thenReturn(null);
        when(mapper.insert(any(TaskRunEntity.class))).thenAnswer(invocation -> {
            invocation.getArgument(0, TaskRunEntity.class).setId(1L);
            return 1;
        });

        Optional<TaskRun> inserted = repository.insertRunning(TaskRun.of(7L, TaskRunType.MAIN, null));

        assertEquals(1, inserted.get().getRunNo(), "从未执行过时 run_no 从 1 起");
    }

    @Test
    void insertRunningReturnsEmptyWhenActiveRunConstraintIsHit() {
        when(mapper.selectMaxRunNo(7L)).thenReturn(1);
        when(mapper.insert(any(TaskRunEntity.class))).thenThrow(new DuplicateKeyException("uk_active_run"));

        Optional<TaskRun> inserted = repository.insertRunning(TaskRun.of(7L, TaskRunType.MAIN, null));

        assertTrue(inserted.isEmpty(), "撞 uk_active_run 是预期分支（抢互斥失败），返回空而不是抛异常");
    }

    // ---------- 查询与转换 ----------

    @Test
    void findLatestByTaskIdMapsEntityBackToDomainVerbatim() {
        TaskRunEntity entity = entity();
        when(mapper.selectOne(any())).thenReturn(entity);

        Optional<TaskRun> found = repository.findLatestByTaskId(7L);

        assertTrue(found.isPresent());
        TaskRun run = found.get();
        assertEquals(9L, run.getId());
        assertEquals(7L, run.getTaskId());
        assertEquals(3, run.getRunNo());
        assertEquals(TaskRunType.TRANSLATE, run.getRunType());
        assertEquals("zh-Hans", run.getTargetLanguage());
        assertEquals(TaskRunStatus.RUNNING, run.getStatus());
    }

    @Test
    void findLatestByTaskIdOrdersByRunNoDescending() {
        when(mapper.selectOne(any())).thenReturn(null);

        repository.findLatestByTaskId(7L);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<AbstractWrapper<TaskRunEntity, ?, ?>> captor =
                ArgumentCaptor.forClass(AbstractWrapper.class);
        verify(mapper).selectOne(captor.capture());
        String segment = captor.getValue().getSqlSegment().toLowerCase(java.util.Locale.ROOT);
        assertTrue(segment.contains("run_no"), () -> "最近一条按 run_no 定位：" + segment);
        assertTrue(segment.contains("order by") && segment.contains("desc"),
                () -> "必须按 run_no 倒序取一条：" + segment);
    }

    @Test
    void findLatestByTaskIdReturnsEmptyWhenNeverExecuted() {
        when(mapper.selectOne(any())).thenReturn(null);

        assertTrue(repository.findLatestByTaskId(7L).isEmpty());
    }

    // ---------- update ----------

    @Test
    void updateWritesMutatedDomainStateBackToEntity() {
        TaskRun run = TaskRun.restore(9L, 7L, 3, TaskRunType.MAIN, null, TaskRunStatus.RUNNING,
                "transcribe-audio", null, LocalDateTime.now(), null, LocalDateTime.now());
        run.fail("转写失败：请检查 DASHSCOPE_API_KEY");
        when(mapper.updateById(any(TaskRunEntity.class))).thenReturn(1);

        int affected = repository.update(run);

        assertEquals(1, affected);
        ArgumentCaptor<TaskRunEntity> captor = ArgumentCaptor.forClass(TaskRunEntity.class);
        verify(mapper).updateById(captor.capture());
        TaskRunEntity entity = captor.getValue();
        assertEquals(9L, entity.getId(), "update 应按 id 覆盖");
        assertEquals(TaskRunStatus.FAILED, entity.getStatus());
        assertEquals("转写失败：请检查 DASHSCOPE_API_KEY", entity.getErrorMessage());
        assertNotNull(entity.getFinishedAt());
    }

    // ---------- 启动收口 ----------

    @Test
    void interruptAllRunningReturnsInterruptedTaskIdsAfterOneUpdate() {
        LocalDateTime at = LocalDateTime.of(2026, 10, 17, 12, 0);
        when(mapper.selectRunningTaskIds()).thenReturn(List.of(7L, 8L));
        when(mapper.interruptRunning(eq(at))).thenReturn(2);

        List<Long> taskIds = repository.interruptAllRunning(at);

        assertEquals(List.of(7L, 8L), taskIds, "返回被收掉的 task_id（去重），供第三条 UPDATE 与日志");
        verify(mapper).interruptRunning(at);
    }

    private static TaskRunEntity entity() {
        TaskRunEntity entity = new TaskRunEntity();
        entity.setId(9L);
        entity.setTaskId(7L);
        entity.setRunNo(3);
        entity.setRunType(TaskRunType.TRANSLATE);
        entity.setTargetLanguage("zh-Hans");
        entity.setStatus(TaskRunStatus.RUNNING);
        entity.setCurrentStage("translate-llm");
        entity.setStartedAt(LocalDateTime.of(2026, 1, 1, 0, 0));
        entity.setCreatedAt(LocalDateTime.of(2026, 1, 1, 0, 0));
        return entity;
    }
}
