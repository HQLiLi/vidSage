package com.vidsage.application.subtitle.query;

import java.time.LocalDateTime;

/**
 * 一条纠错的读模型：纠错本身，外加两条「还对不对得上当前字幕」的判据。
 *
 * <p>{@code id} 只给「清理失效纠错」用来批量删；对外响应里不出现内部行 id。
 *
 * <p>{@code field} 存的是<b>对外词形</b>（{@code text} / {@code translation}，取自
 * {@code SubtitleCorrectionField#token()}），不是领域枚举：读模型是给 web 层直接用的，
 * 不必让 web 层认识 domain 的类型。
 *
 * @param id            内部行 id；清理失效纠错时按它批量删
 * @param segmentId     字幕段 id，形如 {@code seg-0-3520}
 * @param field         改的是原文还是译文，取值 {@code text} / {@code translation}
 * @param lang          目标语种短码；原文纠错为空串
 * @param sourceText    保存这条纠错时机器给出的源文本
 * @param value         用户改后的文本
 * @param matched       当前 CLEANED 字幕里还有没有这个 segment id（{@code false} = 这条纠错已失效）
 * @param sourceChanged 机器源文本是否已经与保存时不同（{@code true} = 这条纠错需要复核）
 * @param updatedAt     最近一次保存时间
 */
public record CorrectionView(Long id, String segmentId, String field, String lang,
                             String sourceText, String value, boolean matched, boolean sourceChanged,
                             LocalDateTime updatedAt) {
}
