package com.vidsage.infrastructure.persistence.repository;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.vidsage.domain.subtitle.model.keyword.SubtitleKeywords;
import com.vidsage.domain.subtitle.port.SubtitleKeywordRepository;
import com.vidsage.infrastructure.persistence.entity.SubtitleKeywordEntity;
import com.vidsage.infrastructure.persistence.mapper.SubtitleKeywordMapper;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import org.springframework.stereotype.Repository;

/**
 * {@code subtitle_keyword} 表的 MyBatis-Plus 实现：包装 {@link SubtitleKeywordMapper}，
 * 做「整份清单 ↔ 一列字符串」的成对转换——<b>分隔符只在本类出现</b>
 */
@Repository
public class MybatisSubtitleKeywordRepository implements SubtitleKeywordRepository {

    private final SubtitleKeywordMapper mapper;

    public MybatisSubtitleKeywordRepository(SubtitleKeywordMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * 按任务读回清单。该任务从没存过时返回<b>空清单</b>，不返回 {@code null}——
     * 调用方（清洗编排、管线）拿到的永远是合法的一份，不必判空。
     *
     * @param taskId 字幕任务的内部行 id
     * @return 该任务的关键词清单；未存过或存的是空串时都是空清单
     */
    public SubtitleKeywords findByTaskId(long taskId) {
        SubtitleKeywordEntity entity = mapper.selectOne(
                Wrappers.<SubtitleKeywordEntity>lambdaQuery().eq(SubtitleKeywordEntity::getTaskId, taskId));
        return new SubtitleKeywords(splitToValues(entity == null ? null : entity.getKeywords()));
    }

    /**
     * 覆盖式写入整份清单（一行 upsert）。空清单落成空串：行还在、值是空，
     * 这样「清空」与「从没填过」在库里是同一个可查询状态，不必区分。
     *
     * @param taskId   字幕任务的内部行 id
     * @param keywords 要落库的清单
     */
    public void replace(long taskId, SubtitleKeywords keywords) {
        mapper.replaceByTaskId(taskId, joinValues(keywords), LocalDateTime.now());
    }

    /**
     * 清单 → 一列字符串：以 {@code U+0001} 连接，顺序即用户填写顺序；空清单得空串。
     *
     * <p>分隔符安全：它是 ISO 控制字符，{@link SubtitleKeywords} 的归一化早就把它换成了空格，
     * 真术语里不可能还留着它，所以不需要转义。
     */
    private static String joinValues(SubtitleKeywords keywords) {
        return String.join(SubtitleKeywords.STORAGE_SEPARATOR, keywords.values());
    }

    /**
     * 一列字符串 → 清单。{@code null}（没存过）与 {@code ''}（存过但是空清单）都拆成空列表；
     * 空串会 split 出 {@code [""]}，但 {@link SubtitleKeywords} 的紧凑构造器本来就丢弃空串，
     * 交给上层的结果是对的——去重、trim、上限校验一律在那儿做，本类不重复一遍。
     *
     * @param stored 库里读出的一列字符串，可为 {@code null}
     * @return 清单的原始元素（未归一化；归一化由 {@link SubtitleKeywords} 负责）
     */
    private static List<String> splitToValues(String stored) {
        if (stored == null || stored.isEmpty()) {
            return List.of();
        }
        // -1 保住尾部空串，行为可预期（该字符不是正则元字符，直接 split 即可）
        return Arrays.asList(stored.split(SubtitleKeywords.STORAGE_SEPARATOR, -1));
    }
}
