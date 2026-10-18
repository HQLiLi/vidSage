package com.vidsage.infrastructure.persistence.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.vidsage.domain.subtitle.IntermediateFile;
import com.vidsage.domain.subtitle.model.IntermediateFileStatus;
import com.vidsage.domain.subtitle.model.IntermediateFileType;
import com.vidsage.infrastructure.persistence.entity.IntermediateFileEntity;
import com.vidsage.infrastructure.persistence.mapper.IntermediateFileMapper;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

/**
 * {@link MybatisIntermediateFileRepository} 的纯单元测试：Mapper 用 Mockito mock，<b>不连真库</b>。
 *
 * <p>覆盖「实体 ↔ 领域对象逐字段双向转换」「insertReady 回填自增 id」「READY 查询条件」
 * 「三条定向 UPDATE 的委派并同句带上 updated_at 的时刻」。
 */
class MybatisIntermediateFileRepositoryTest {

    private final IntermediateFileMapper mapper = Mockito.mock(IntermediateFileMapper.class);
    private final MybatisIntermediateFileRepository repository = new MybatisIntermediateFileRepository(mapper);

    @BeforeAll
    static void initTableInfoOnce() {
        // LambdaQueryWrapper 的列名解析走 TableInfo 缓存；容器外手工注册一次
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                IntermediateFileEntity.class);
    }

    // ---------- insertReady ----------

    @Test
    void insertReadyMapsDomainFieldsOntoEntityAndBackfillsId() {
        IntermediateFile file = IntermediateFile.ofReady(7L, "source.mp4", IntermediateFileType.SOURCE_MEDIA, 3L);
        when(mapper.insert(any(IntermediateFileEntity.class))).thenAnswer(invocation -> {
            invocation.getArgument(0, IntermediateFileEntity.class).setId(42L);
            return 1;
        });

        IntermediateFile inserted = repository.insertReady(file);

        assertEquals(42L, inserted.getId(), "insert 应把数据库生成的自增 id 回填进领域对象");
        ArgumentCaptor<IntermediateFileEntity> captor = ArgumentCaptor.forClass(IntermediateFileEntity.class);
        verify(mapper).insert(captor.capture());
        IntermediateFileEntity entity = captor.getValue();
        assertEquals(7L, entity.getTaskId());
        assertEquals("source.mp4", entity.getFileName());
        assertEquals(IntermediateFileType.SOURCE_MEDIA, entity.getFileType());
        assertEquals(IntermediateFileStatus.READY, entity.getStatus());
        assertEquals(3L, entity.getDerivedFromRunId());
        assertTrue(entity.getCreatedAt() != null && entity.getUpdatedAt() != null);
    }

    // ---------- 查询与转换 ----------

    @Test
    void findByTaskIdMapsAllRowsToDomainAndFiltersByTaskId() {
        when(mapper.selectList(any())).thenReturn(List.of(entity()));

        List<IntermediateFile> files = repository.findByTaskId(7L);

        assertEquals(1, files.size());
        IntermediateFile file = files.getFirst();
        assertEquals(11L, file.getId());
        assertEquals(7L, file.getTaskId());
        assertEquals("source.mp4", file.getFileName());
        assertEquals(IntermediateFileType.SOURCE_MEDIA, file.getFileType());
        assertEquals(IntermediateFileStatus.READY, file.getStatus());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<AbstractWrapper<IntermediateFileEntity, ?, ?>> captor =
                ArgumentCaptor.forClass(AbstractWrapper.class);
        verify(mapper).selectList(captor.capture());
        // 读 getSqlSegment() 会物化参数（MP 3.5 把值懒填进 paramNameValuePairs），必须先读它再读参数
        String segment = captor.getValue().getSqlSegment().toLowerCase(java.util.Locale.ROOT);
        assertTrue(segment.contains("task_id"), () -> "查询条件必须按 task_id 过滤：" + segment);
        assertTrue(captor.getValue().getParamNameValuePairs().values().contains(7L),
                () -> "params=" + captor.getValue().getParamNameValuePairs());
    }

    @Test
    void findReadyByTaskIdAndFileNameFiltersByFileNameAndReadyStatus() {
        when(mapper.selectOne(any())).thenReturn(null);

        repository.findReadyByTaskIdAndFileName(7L, "source.mp4");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<AbstractWrapper<IntermediateFileEntity, ?, ?>> captor =
                ArgumentCaptor.forClass(AbstractWrapper.class);
        verify(mapper).selectOne(captor.capture());
        String segment = captor.getValue().getSqlSegment().toLowerCase(java.util.Locale.ROOT);
        assertTrue(segment.contains("task_id") && segment.contains("file_name") && segment.contains("status"),
                () -> "复用判据必须按 task_id / file_name / status 过滤：" + segment);
        Collection<Object> params = captor.getValue().getParamNameValuePairs().values();
        assertTrue(params.contains("source.mp4") && params.contains(IntermediateFileStatus.READY));
    }

    @Test
    void findReadyByTaskIdAndFileNameReturnsEmptyWhenMissing() {
        when(mapper.selectOne(any())).thenReturn(null);

        assertTrue(repository.findReadyByTaskIdAndFileName(7L, "nope.mp4").isEmpty());
    }

    // ---------- 定向 UPDATE ----------

    @Test
    void markReadyDelegatesWithGivenTimestamp() {
        LocalDateTime at = LocalDateTime.of(2026, 10, 17, 12, 0);
        when(mapper.markReady(11L, at)).thenReturn(1);

        assertEquals(1, repository.markReady(11L, at));
        verify(mapper).markReady(11L, at);
    }

    @Test
    void markSupersededByTaskIdDelegatesWithGivenTimestamp() {
        LocalDateTime at = LocalDateTime.of(2026, 10, 17, 12, 0);
        when(mapper.markSupersededByTaskId(7L, at)).thenReturn(2);

        assertEquals(2, repository.markSupersededByTaskId(7L, at));
        verify(mapper).markSupersededByTaskId(7L, at);
    }

    @Test
    void markDeletedDelegatesWithGivenTimestamp() {
        LocalDateTime at = LocalDateTime.of(2026, 10, 17, 12, 0);
        when(mapper.markDeleted(11L, at)).thenReturn(1);

        assertEquals(1, repository.markDeleted(11L, at));
        verify(mapper).markDeleted(11L, at);
    }

    private static IntermediateFileEntity entity() {
        IntermediateFileEntity entity = new IntermediateFileEntity();
        entity.setId(11L);
        entity.setTaskId(7L);
        entity.setFileName("source.mp4");
        entity.setFileType(IntermediateFileType.SOURCE_MEDIA);
        entity.setStatus(IntermediateFileStatus.READY);
        entity.setDerivedFromRunId(3L);
        entity.setCreatedAt(LocalDateTime.of(2026, 1, 1, 0, 0));
        entity.setUpdatedAt(LocalDateTime.of(2026, 1, 2, 0, 0));
        return entity;
    }
}
