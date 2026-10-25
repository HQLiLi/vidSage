package com.vidsage.infrastructure.persistence.repository;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.vidsage.domain.subtitle.model.correction.SubtitleCorrection;
import com.vidsage.domain.subtitle.model.correction.SubtitleCorrectionField;
import com.vidsage.domain.subtitle.port.SubtitleCorrectionRepository;
import com.vidsage.infrastructure.persistence.entity.SubtitleCorrectionEntity;
import com.vidsage.infrastructure.persistence.mapper.SubtitleCorrectionMapper;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/**
 * {@code subtitle_correction} 表的 MyBatis-Plus 实现：包装 {@link SubtitleCorrectionMapper}，
 * 做「实体 ↔ 领域对象」的转换（转换点只此一处）。
 *
 * <p>保存走一条 upsert，写完按唯一键读回那一行交给调用方——时间戳是数据库里的真实值，
 * 不是调用方手里的 {@code now()}。
 */
@Repository
public class MybatisSubtitleCorrectionRepository implements SubtitleCorrectionRepository {

    private final SubtitleCorrectionMapper mapper;

    /**
     * @param mapper 表的数据访问接口
     */
    public MybatisSubtitleCorrectionRepository(SubtitleCorrectionMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * 取该任务的全部纠错；没有时是空列表。
     *
     * <p>按<b>首次保存顺序</b>（自增 id 升序）返回：列表接口直接透出这个顺序，前端据此稳定排列；
     * 重复保存只刷新 {@code updated_at}，不改 id，所以改过的条目不会跳到末尾。
     *
     * @param taskId 字幕任务的内部行 id
     * @return 该任务的纠错，按 id 升序
     */
    public List<SubtitleCorrection> findByTaskId(long taskId) {
        return mapper.selectList(Wrappers.<SubtitleCorrectionEntity>lambdaQuery()
                        .eq(SubtitleCorrectionEntity::getTaskId, taskId)
                        .orderByAsc(SubtitleCorrectionEntity::getId))
                .stream()
                .map(MybatisSubtitleCorrectionRepository::toDomain)
                .toList();
    }

    /**
     * 按唯一键插入或更新，然后读回落库的那一行。
     *
     * <p>读完是空的只可能是并发删掉了这一行——那是调用方看不见的竞态，宁可抛出来，
     * 也不要返回一个「假的」保存结果。
     *
     * @param correction 要保存的纠错
     * @return 落库后读回的对象
     */
    public SubtitleCorrection upsert(SubtitleCorrection correction) {
        mapper.upsertByUniqueKey(correction.taskId(), correction.segmentId(), correction.field(),
                correction.lang(), correction.sourceText(), correction.correctedValue(), LocalDateTime.now());
        return findByUniqueKey(correction)
                .orElseThrow(() -> new IllegalStateException(
                        "保存纠错后读不回落库行：taskId=" + correction.taskId()
                                + ", segmentId=" + correction.segmentId()
                                + "；可能被并发删除，请刷新后重试"));
    }

    /**
     * 删掉一条纠错；语种先按唯一键的存储形态归一（原文纠错一律空串、{@code null} 也当空串），
     * 否则调用方传 {@code null} 或给原文纠错带了个 lang，就会静默删 0 行。
     *
     * @param taskId    字幕任务的内部行 id
     * @param segmentId 字幕段 id
     * @param field     原文还是译文
     * @param lang      目标语种；原文纠错传什么都按空串处理
     * @return 实际删掉的行数（0 或 1）
     */
    public int deleteByTaskIdAndSegmentIdAndFieldAndLang(long taskId, String segmentId,
                                                         SubtitleCorrectionField field, String lang) {
        String storedLang = field == SubtitleCorrectionField.TEXT || lang == null ? "" : lang;
        return mapper.delete(Wrappers.<SubtitleCorrectionEntity>lambdaQuery()
                .eq(SubtitleCorrectionEntity::getTaskId, taskId)
                .eq(SubtitleCorrectionEntity::getSegmentId, segmentId)
                .eq(SubtitleCorrectionEntity::getFieldType, field)
                .eq(SubtitleCorrectionEntity::getLang, storedLang));
    }

    /**
     * 按行 id 批量删。空列表直接返回 0：{@code IN ()} 是语法错，不是「删零行」。
     *
     * @param ids 要删的行 id
     * @return 实际删掉的行数
     */
    public int deleteByIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return 0;
        }
        return mapper.delete(Wrappers.<SubtitleCorrectionEntity>lambdaQuery()
                .in(SubtitleCorrectionEntity::getId, ids));
    }

    /**
     * 按唯一键读回一行（保存后用它拿真实 id 与时间戳）。
     */
    private Optional<SubtitleCorrection> findByUniqueKey(SubtitleCorrection correction) {
        SubtitleCorrectionEntity entity = mapper.selectOne(Wrappers.<SubtitleCorrectionEntity>lambdaQuery()
                .eq(SubtitleCorrectionEntity::getTaskId, correction.taskId())
                .eq(SubtitleCorrectionEntity::getSegmentId, correction.segmentId())
                .eq(SubtitleCorrectionEntity::getFieldType, correction.field())
                .eq(SubtitleCorrectionEntity::getLang, correction.lang()));
        return entity == null ? Optional.empty() : Optional.of(toDomain(entity));
    }

    /**
     * 实体 → 领域对象。枚举与空串的还原都只在这里做：库里 {@code field_type} 是枚举名，
     * {@code lang} 原文纠错是空串；领域侧见到的就是 {@link SubtitleCorrectionField} 与空串。
     */
    private static SubtitleCorrection toDomain(SubtitleCorrectionEntity entity) {
        return new SubtitleCorrection(entity.getId(), entity.getTaskId(), entity.getSegmentId(),
                entity.getFieldType(), entity.getLang(), entity.getSourceText(),
                entity.getCorrectedValue(), entity.getCreatedAt(), entity.getUpdatedAt());
    }
}
