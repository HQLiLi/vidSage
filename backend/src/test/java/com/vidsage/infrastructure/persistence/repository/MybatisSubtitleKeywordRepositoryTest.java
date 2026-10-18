package com.vidsage.infrastructure.persistence.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.vidsage.domain.subtitle.model.keyword.SubtitleKeywords;
import com.vidsage.infrastructure.persistence.entity.SubtitleKeywordEntity;
import com.vidsage.infrastructure.persistence.mapper.SubtitleKeywordMapper;
import java.util.List;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

/**
 * {@link MybatisSubtitleKeywordRepository} 的纯单元测试：Mapper 用 Mockito mock，<b>不连真库</b>。
 *
 * <p>覆盖「整份清单 ↔ 一列字符串」的成对转换：分隔符连接、顺序保真、null 与空串都读回空清单、
 * 覆盖写是整份替换而不是追加；锚点是任务行 id。
 */
class MybatisSubtitleKeywordRepositoryTest {

    /** 任取的任务行 id：查询与写入都只按它定位一行。 */
    private static final long TASK_ID = 7L;

    private final SubtitleKeywordMapper mapper = Mockito.mock(SubtitleKeywordMapper.class);
    private final MybatisSubtitleKeywordRepository repository = new MybatisSubtitleKeywordRepository(mapper);

    @BeforeAll
    static void initTableInfoOnce() {
        // LambdaQueryWrapper 的列名解析走 TableInfo 缓存；容器外手工注册一次
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                SubtitleKeywordEntity.class);
    }

    @Test
    void returnsEmptyKeywordsWhenTaskHasNoRow() {
        when(mapper.selectOne(any())).thenReturn(null);

        assertTrue(repository.findByTaskId(TASK_ID).isEmpty(),
                "从没存过的任务应读回空清单，不是 null——调用方不必判空");
    }

    @Test
    void readsBackEmptyKeywordsWhenStoredValueIsBlank() {
        when(mapper.selectOne(any())).thenReturn(entityWith(null));

        assertTrue(repository.findByTaskId(TASK_ID).isEmpty(), "存过但值是 null，按空清单读回");

        when(mapper.selectOne(any())).thenReturn(entityWith(""));

        assertTrue(repository.findByTaskId(TASK_ID).isEmpty(), "存过但是空串，也按空清单读回");
    }

    @Test
    void splitsStoredColumnIntoKeywordsInFillingOrder() {
        String separator = SubtitleKeywords.STORAGE_SEPARATOR;
        when(mapper.selectOne(any())).thenReturn(entityWith("影视飓风" + separator + "Dr. Smith, Jr."));

        assertEquals(List.of("影视飓风", "Dr. Smith, Jr."), repository.findByTaskId(TASK_ID).values(),
                "顺序即用户填写顺序；含逗号的真术语不能被逗号分隔符切碎");
    }

    @Test
    void readsBackSingleKeywordWithoutSeparator() {
        when(mapper.selectOne(any())).thenReturn(entityWith("Tim"));

        assertEquals(List.of("Tim"), repository.findByTaskId(TASK_ID).values());
    }

    @Test
    void joinsKeywordsIntoOneColumnWithSeparator() {
        repository.replace(TASK_ID, new SubtitleKeywords(List.of("影视飓风", "Tim")));

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(mapper).replaceByTaskId(anyLong(), captor.capture(), any());
        assertEquals("影视飓风" + SubtitleKeywords.STORAGE_SEPARATOR + "Tim", captor.getValue());
    }

    @Test
    void storesEmptyKeywordsAsEmptyString() {
        repository.replace(TASK_ID, new SubtitleKeywords(List.of()));

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(mapper).replaceByTaskId(anyLong(), captor.capture(), any());
        assertEquals("", captor.getValue(), "空清单落成空串：行还在，「清空」与「没填过」在库里同形");
    }

    @Test
    void replaceWritesWholeListInOneUpsert() {
        repository.replace(TASK_ID, new SubtitleKeywords(List.of("A", "B", "C")));

        // 覆盖全量就是一条 upsert：没有 delete、没有批量 insert
        verify(mapper).replaceByTaskId(anyLong(), anyString(), any());
        Mockito.verifyNoMoreInteractions(mapper);
    }

    private static SubtitleKeywordEntity entityWith(String keywords) {
        SubtitleKeywordEntity entity = new SubtitleKeywordEntity();
        entity.setId(1L);
        entity.setTaskId(TASK_ID);
        entity.setKeywords(keywords);
        return entity;
    }
}
