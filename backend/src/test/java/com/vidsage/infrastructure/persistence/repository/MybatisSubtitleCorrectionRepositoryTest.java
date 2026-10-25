package com.vidsage.infrastructure.persistence.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.vidsage.domain.subtitle.model.correction.SubtitleCorrection;
import com.vidsage.domain.subtitle.model.correction.SubtitleCorrectionField;
import com.vidsage.infrastructure.persistence.entity.SubtitleCorrectionEntity;
import com.vidsage.infrastructure.persistence.mapper.SubtitleCorrectionMapper;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

/**
 * {@link MybatisSubtitleCorrectionRepository} 的纯单元测试：Mapper 用 Mockito mock，<b>不连真库</b>。
 *
 * <p>重点钉住「数据库形态 ↔ 领域形态」的转换：field 落成枚举名、原文纠错的 lang 落成空串、
 * 读回来还原成枚举与空串；保存是 upsert 一条语句，写完按唯一键读回真实那一行；
 * 列表查询显式按 id 升序（首次保存顺序）。
 */
class MybatisSubtitleCorrectionRepositoryTest {

    /** 任取的任务行 id：查询与写入都只按它定位。 */
    private static final long TASK_ID = 7L;

    /** 任取的段落 id，形状与真实产物一致。 */
    private static final String SEGMENT_ID = "seg-0-1440";

    private final SubtitleCorrectionMapper mapper = Mockito.mock(SubtitleCorrectionMapper.class);
    private final MybatisSubtitleCorrectionRepository repository = new MybatisSubtitleCorrectionRepository(mapper);

    @BeforeAll
    static void initTableInfoOnce() {
        // LambdaQueryWrapper 的列名解析走 TableInfo 缓存；容器外手工注册一次
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                SubtitleCorrectionEntity.class);
    }

    @Test
    void writesTextCorrectionWithEmptyLang() {
        stubReadBack(row(SubtitleCorrectionField.TEXT, ""));

        repository.upsert(correction(SubtitleCorrectionField.TEXT, null, "改前原文", "改后原文"));

        verify(mapper).upsertByUniqueKey(eq(TASK_ID), eq(SEGMENT_ID),
                eq(SubtitleCorrectionField.TEXT), eq(""), eq("改前原文"), eq("改后原文"), any());
    }

    @Test
    void writesTranslationCorrectionWithLang() {
        stubReadBack(row(SubtitleCorrectionField.TRANSLATION, "en"));

        repository.upsert(correction(SubtitleCorrectionField.TRANSLATION, "en", "原文", "译文"));

        verify(mapper).upsertByUniqueKey(eq(TASK_ID), eq(SEGMENT_ID),
                eq(SubtitleCorrectionField.TRANSLATION), eq("en"), eq("原文"), eq("译文"), any());
    }

    @Test
    void returnsRowReadBackAfterUpsert() {
        SubtitleCorrectionEntity stored = row(SubtitleCorrectionField.TRANSLATION, "en");
        stored.setId(42L);
        stored.setSourceText("原文");
        stored.setCorrectedValue("译文");
        stored.setCreatedAt(LocalDateTime.of(2026, 10, 25, 10, 0));
        stored.setUpdatedAt(LocalDateTime.of(2026, 10, 25, 10, 5));
        stubReadBack(stored);

        SubtitleCorrection saved = repository.upsert(
                correction(SubtitleCorrectionField.TRANSLATION, "en", "原文", "译文"));

        assertEquals(42L, saved.id(), "返回值该带库里的自增 id");
        assertEquals(SubtitleCorrectionField.TRANSLATION, saved.field());
        assertEquals(LocalDateTime.of(2026, 10, 25, 10, 5), saved.updatedAt(),
                "时间戳取库里那一行，不是调用方手里的 now");
    }

    @Test
    void throwsWhenSavedRowCannotBeReadBack() {
        when(mapper.selectOne(any())).thenReturn(null);

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> repository.upsert(correction(SubtitleCorrectionField.TEXT, "", "原文", "改后")));

        assertTrue(e.getMessage().contains(SEGMENT_ID), "报错要带段落 id，便于定位");
    }

    @Test
    void mapsRowsBackWithEnumAndLang() {
        when(mapper.selectList(any())).thenReturn(List.of(
                row(SubtitleCorrectionField.TEXT, ""),
                row(SubtitleCorrectionField.TRANSLATION, "en")));

        List<SubtitleCorrection> rows = repository.findByTaskId(TASK_ID);

        assertEquals(List.of(SubtitleCorrectionField.TEXT, SubtitleCorrectionField.TRANSLATION),
                rows.stream().map(SubtitleCorrection::field).toList(),
                "库里的枚举名要原样还原成枚举");
        assertEquals(List.of("", "en"), rows.stream().map(SubtitleCorrection::lang).toList(),
                "原文纠错的空串要原样读回，不能变成 null");
    }

    @Test
    void ordersListByFirstSavedId() {
        // 顺序是接口的一部分（列表直接透出）：稳定的「首次保存顺序」靠 id 升序，不信数据库默认顺序
        when(mapper.selectList(any())).thenReturn(List.of(row(SubtitleCorrectionField.TEXT, "")));

        repository.findByTaskId(TASK_ID);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Wrapper<SubtitleCorrectionEntity>> captor =
                ArgumentCaptor.forClass(Wrapper.class);
        verify(mapper).selectList(captor.capture());
        assertTrue(captor.getValue().getSqlSegment().toUpperCase(Locale.ROOT).contains("ORDER BY"),
                "查询必须显式按 id 排序，不能依赖数据库返回顺序");
        assertTrue(captor.getValue().getSqlSegment().contains("id ASC"));
    }

    @Test
    void deletesOneCorrectionByUniqueKey() {
        when(mapper.delete(any())).thenReturn(1);

        int removed = repository.deleteByTaskIdAndSegmentIdAndFieldAndLang(
                TASK_ID, SEGMENT_ID, SubtitleCorrectionField.TEXT, null);

        assertEquals(1, removed);
        verify(mapper).delete(any());
    }

    @Test
    void deletesByIdsInOneStatement() {
        when(mapper.delete(any())).thenReturn(3);

        int removed = repository.deleteByIds(List.of(1L, 2L, 3L));

        assertEquals(3, removed);
        verify(mapper).delete(any());
    }

    @Test
    void skipsDeleteWhenIdListIsEmpty() {
        assertEquals(0, repository.deleteByIds(List.of()));
        verifyNoInteractions(mapper);
    }

    private void stubReadBack(SubtitleCorrectionEntity entity) {
        when(mapper.selectOne(any())).thenReturn(entity);
    }

    private static SubtitleCorrection correction(SubtitleCorrectionField field, String lang,
                                                 String sourceText, String correctedValue) {
        return new SubtitleCorrection(null, TASK_ID, SEGMENT_ID, field, lang,
                sourceText, correctedValue, null, null);
    }

    private static SubtitleCorrectionEntity row(SubtitleCorrectionField field, String lang) {
        SubtitleCorrectionEntity entity = new SubtitleCorrectionEntity();
        entity.setId(1L);
        entity.setTaskId(TASK_ID);
        entity.setSegmentId(SEGMENT_ID);
        entity.setFieldType(field);
        entity.setLang(lang);
        entity.setSourceText("原文");
        entity.setCorrectedValue("改后");
        return entity;
    }
}
