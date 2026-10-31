package com.vidsage.domain.subtitle.port;

import com.vidsage.domain.subtitle.model.correction.SubtitleCorrection;
import com.vidsage.domain.subtitle.model.correction.SubtitleCorrectionField;
import java.util.List;

/**
 * 字幕纠错的存取出口：领域侧只说「能做什么」，底下是 MySQL 还是别的存储，领域层不关心。
 *
 * <p>一条纠错按 {@code (task, segment, field, lang)} 唯一——一个段落的一项改动只有一行，
 * 重复保存是覆盖，不追加历史版本。这也正是「切分变了纠错就失效」能被表达出来的原因：
 * 旧 segment id 还在表里，只是不再对应当前产物。
 */
public interface SubtitleCorrectionRepository {

    /**
     * 取该任务的全部纠错（原文与各语种译文混在一起，由调用方按 field + lang 过滤）。
     *
     * <p>顺序有约定：<b>按首次保存顺序（行 id 升序）</b>返回。列表接口直接透出这个顺序，
     * 重复保存只刷新 {@code updated_at}、不改 id，所以改过的条目不会跳到末尾。
     *
     * @param taskId 字幕任务的内部行 id
     * @return 该任务的纠错，按 id 升序；一条都没有时是空列表（不是 {@code null}）
     */
    List<SubtitleCorrection> findByTaskId(long taskId);

    /**
     * 按唯一键插入或更新，返回落库后读回的那一行（带 id 与真实时间戳）。
     *
     * <p>时间戳由本方法取当前时刻写库，入参对象上的 {@code createdAt} / {@code updatedAt} 不参与；
     * {@code id} 同理被忽略——是插还是改由唯一键决定。
     *
     * @param correction 要保存的纠错
     * @return 落库后读回的对象
     */
    SubtitleCorrection upsert(SubtitleCorrection correction);

    /**
     * 删掉一条纠错。删不存在的条目是 0 行，不报错（调用方按幂等处理）。
     *
     * @param taskId    字幕任务的内部行 id
     * @param segmentId 字幕段 id
     * @param field     原文还是译文
     * @param lang      目标语种；原文纠错传空串或 {@code null}（按空串处理）
     * @return 实际删掉的行数（0 或 1）
     */
    int deleteByTaskIdAndSegmentIdAndFieldAndLang(long taskId, String segmentId,
                                                  SubtitleCorrectionField field, String lang);

    /**
     * 按行 id 批量删（清理失效纠错用：调用方先算出哪些失效，再一次性删）。
     *
     * @param ids 要删的行 id；空列表就是什么都不删
     * @return 实际删掉的行数
     */
    int deleteByIds(List<Long> ids);
}
