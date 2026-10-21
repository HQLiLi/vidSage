package com.vidsage.infrastructure.persistence.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.vidsage.domain.subtitle.SubtitleArtifact;
import com.vidsage.domain.subtitle.model.ArtifactType;
import com.vidsage.domain.subtitle.model.SubtitleArtifactStatus;
import com.vidsage.infrastructure.persistence.entity.SubtitleArtifactEntity;
import com.vidsage.infrastructure.persistence.mapper.SubtitleArtifactMapper;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

/**
 * {@link MybatisSubtitleArtifactRepository} 的纯单元测试：Mapper 用 Mockito mock，<b>不连真库</b>。
 *
 * <p>覆盖「实体 ↔ 领域对象逐字段双向转换」「insertCreating 按 MAX+1 回填 revision 与自增 id」
 * 「READY 查询条件」「updateReady 把八个文件/哈希值交给同一条 UPDATE」「六个定向 UPDATE 的委派」。
 */
class MybatisSubtitleArtifactRepositoryTest {

    private final SubtitleArtifactMapper mapper = Mockito.mock(SubtitleArtifactMapper.class);
    private final MybatisSubtitleArtifactRepository repository = new MybatisSubtitleArtifactRepository(mapper);

    @BeforeAll
    static void initTableInfoOnce() {
        // LambdaQueryWrapper 的列名解析走 TableInfo 缓存；容器外手工注册一次
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                SubtitleArtifactEntity.class);
    }

    // ---------- insertCreating ----------

    @Test
    void insertCreatingGeneratesRevisionFromMaxPlusOneAndBackfillsId() {
        when(mapper.selectMaxRevision(7L, ArtifactType.CLEANED, "")).thenReturn(2);
        when(mapper.insert(any(SubtitleArtifactEntity.class))).thenAnswer(invocation -> {
            invocation.getArgument(0, SubtitleArtifactEntity.class).setId(42L);
            return 1;
        });

        SubtitleArtifact inserted = repository.insertCreating(
                SubtitleArtifact.createCreatingArtifact(7L, 3L, ArtifactType.CLEANED, null, 5L, "fp-1"));

        assertEquals(42L, inserted.getId());
        assertEquals(3, inserted.getRevision(), "revision = 同 (task,type,lang) 当前 MAX + 1");
        ArgumentCaptor<SubtitleArtifactEntity> captor = ArgumentCaptor.forClass(SubtitleArtifactEntity.class);
        verify(mapper).insert(captor.capture());
        SubtitleArtifactEntity entity = captor.getValue();
        assertEquals(7L, entity.getTaskId());
        assertEquals(3L, entity.getDerivedFromRunId());
        assertEquals(ArtifactType.CLEANED, entity.getArtifactType());
        assertEquals("", entity.getLang(), "无语言维度落成空串，不是 null");
        assertEquals(3, entity.getRevision());
        assertEquals(SubtitleArtifactStatus.CREATING, entity.getStatus());
        assertNull(entity.getSegmentsFileName(), "CREATING 行永远全 NULL");
        assertNull(entity.getSegmentsSha256());
    }

    @Test
    void insertCreatingStartsFromRevisionOneWhenNoPriorVersion() {
        when(mapper.selectMaxRevision(7L, ArtifactType.ORIGINAL, "")).thenReturn(null);
        when(mapper.insert(any(SubtitleArtifactEntity.class))).thenAnswer(invocation -> {
            invocation.getArgument(0, SubtitleArtifactEntity.class).setId(1L);
            return 1;
        });

        SubtitleArtifact inserted = repository.insertCreating(
                SubtitleArtifact.createCreatingArtifact(7L, 3L, ArtifactType.ORIGINAL, "", null, null));

        assertEquals(1, inserted.getRevision(), "该组合从未产出时 revision 从 1 起");
    }

    // ---------- 查询与转换 ----------

    @Test
    void findReadyByTaskIdAndTypeAndLangMapsEntityBackToDomainVerbatim() {
        when(mapper.selectOne(any())).thenReturn(entity());

        Optional<SubtitleArtifact> found = repository.findReadyByTaskIdAndTypeAndLang(7L, ArtifactType.CLEANED, "");

        assertTrue(found.isPresent());
        SubtitleArtifact artifact = found.get();
        assertEquals(11L, artifact.getId());
        assertEquals(7L, artifact.getTaskId());
        assertEquals(ArtifactType.CLEANED, artifact.getArtifactType());
        assertEquals("", artifact.getLang());
        assertEquals(2, artifact.getRevision());
        assertEquals(SubtitleArtifactStatus.READY, artifact.getStatus());
        assertEquals("segments.json", artifact.getSegmentsFileName());
        assertEquals("aaa", artifact.getSegmentsSha256());
    }

    @Test
    void findReadyByTaskIdAndTypeAndLangFiltersByTypeLangAndReadyStatus() {
        when(mapper.selectOne(any())).thenReturn(null);

        repository.findReadyByTaskIdAndTypeAndLang(7L, ArtifactType.TRANSLATED, "zh-Hans");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<AbstractWrapper<SubtitleArtifactEntity, ?, ?>> captor =
                ArgumentCaptor.forClass(AbstractWrapper.class);
        verify(mapper).selectOne(captor.capture());
        String segment = captor.getValue().getSqlSegment().toLowerCase(java.util.Locale.ROOT);
        assertTrue(segment.contains("task_id") && segment.contains("artifact_type")
                && segment.contains("lang") && segment.contains("status"),
                () -> "READY 查询必须按 task_id / artifact_type / lang / status 过滤：" + segment);
        Collection<Object> params = captor.getValue().getParamNameValuePairs().values();
        assertTrue(params.contains(ArtifactType.TRANSLATED) && params.contains("zh-Hans")
                && params.contains(SubtitleArtifactStatus.READY), () -> "参数应含类型 / 语种 / READY：" + params);
    }

    @Test
    void findReadyByTaskIdAndTypeAndLangTreatsNullLangAsEmptyString() {
        when(mapper.selectOne(any())).thenReturn(null);

        repository.findReadyByTaskIdAndTypeAndLang(7L, ArtifactType.ORIGINAL, null);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<AbstractWrapper<SubtitleArtifactEntity, ?, ?>> captor =
                ArgumentCaptor.forClass(AbstractWrapper.class);
        verify(mapper).selectOne(captor.capture());
        // 读 getSqlSegment() 会物化参数（MP 3.5 把值懒填进 paramNameValuePairs），必须先读它再读参数
        captor.getValue().getSqlSegment();
        assertTrue(captor.getValue().getParamNameValuePairs().values().contains(""),
                () -> "null 语种按空串匹配库里那一列 params=" + captor.getValue().getParamNameValuePairs());
    }

    @Test
    void findReadyByTaskIdAndTypeAndLangReturnsEmptyWhenMissing() {
        when(mapper.selectOne(any())).thenReturn(null);

        assertTrue(repository.findReadyByTaskIdAndTypeAndLang(7L, ArtifactType.CLEANED, "").isEmpty());
    }

    @Test
    void findReadyByTaskIdMapsAllRowsToDomain() {
        when(mapper.selectList(any())).thenReturn(List.of(entity()));

        List<SubtitleArtifact> ready = repository.findReadyByTaskId(7L);

        assertEquals(1, ready.size());
        assertEquals(11L, ready.getFirst().getId());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<AbstractWrapper<SubtitleArtifactEntity, ?, ?>> captor =
                ArgumentCaptor.forClass(AbstractWrapper.class);
        verify(mapper).selectList(captor.capture());
        // 读 getSqlSegment() 会物化参数（MP 3.5 把值懒填进 paramNameValuePairs），必须先读它再读参数
        captor.getValue().getSqlSegment();
        assertTrue(captor.getValue().getParamNameValuePairs().values().contains(SubtitleArtifactStatus.READY),
                () -> "params=" + captor.getValue().getParamNameValuePairs());
    }

    // ---------- 定向 UPDATE ----------

    @Test
    void updateReadyHandsTheEightFileAndHashValuesToTheUpdate() {
        SubtitleArtifact artifact = SubtitleArtifact.createCreatingArtifact(7L, 3L, ArtifactType.TRANSLATED, "zh-Hans", 5L, null);
        artifact.completeCreation("segments.json", "subtitles.srt", null, "terms.json",
                "s1", "s2", null, "s4");

        repository.updateReady(artifact);

        ArgumentCaptor<SubtitleArtifactEntity> captor = ArgumentCaptor.forClass(SubtitleArtifactEntity.class);
        verify(mapper).updateReady(captor.capture());
        SubtitleArtifactEntity entity = captor.getValue();
        assertEquals("segments.json", entity.getSegmentsFileName());
        assertEquals("terms.json", entity.getTermsFileName());
        assertNull(entity.getContextFileName(), "TRANSLATED 没有 context 文件");
        assertEquals("s1", entity.getSegmentsSha256());
        assertEquals("s4", entity.getTermsSha256());
        assertNull(entity.getContextSha256());
        assertEquals(SubtitleArtifactStatus.READY, entity.getStatus());
    }

    @Test
    void markSupersededAndClearFilesDelegatesById() {
        when(mapper.markSupersededAndClearFiles(11L)).thenReturn(1);

        assertEquals(1, repository.markSupersededAndClearFiles(11L));
        verify(mapper).markSupersededAndClearFiles(11L);
    }

    @Test
    void markSupersededByTaskIdAndTypeDelegatesWithSameArguments() {
        when(mapper.markSupersededByTaskIdAndType(7L, ArtifactType.TRANSLATED)).thenReturn(2);

        assertEquals(2, repository.markSupersededByTaskIdAndType(7L, ArtifactType.TRANSLATED));
        verify(mapper).markSupersededByTaskIdAndType(7L, ArtifactType.TRANSLATED);
    }

    @Test
    void markDeletedDelegatesById() {
        when(mapper.markDeleted(11L)).thenReturn(1);

        assertEquals(1, repository.markDeleted(11L));
        verify(mapper).markDeleted(11L);
    }

    @Test
    void markCreatingAsFailedByRunIdDelegates() {
        when(mapper.markCreatingAsFailedByRunId(3L)).thenReturn(2);

        assertEquals(2, repository.markCreatingAsFailedByRunId(3L));
        verify(mapper).markCreatingAsFailedByRunId(3L);
    }

    @Test
    void markCreatingAsFailedDelegatesWithoutFilter() {
        when(mapper.markCreatingAsFailed()).thenReturn(1);

        assertEquals(1, repository.markCreatingAsFailed());
        verify(mapper).markCreatingAsFailed();
    }

    private static SubtitleArtifactEntity entity() {
        SubtitleArtifactEntity entity = new SubtitleArtifactEntity();
        entity.setId(11L);
        entity.setTaskId(7L);
        entity.setDerivedFromRunId(3L);
        entity.setArtifactType(ArtifactType.CLEANED);
        entity.setLang("");
        entity.setRevision(2);
        entity.setStatus(SubtitleArtifactStatus.READY);
        entity.setKeywordsFingerprint("fp-1");
        entity.setSegmentsFileName("segments.json");
        entity.setSubtitlesFileName("subtitles.srt");
        entity.setSegmentsSha256("aaa");
        entity.setCreatedAt(LocalDateTime.of(2026, 1, 1, 0, 0));
        return entity;
    }
}
