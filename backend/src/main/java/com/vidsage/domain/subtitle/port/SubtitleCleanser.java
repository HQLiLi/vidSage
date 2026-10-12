package com.vidsage.domain.subtitle.port;

import com.vidsage.domain.shared.port.ProgressListener;
import com.vidsage.domain.subtitle.model.cleanup.CleanseResult;
import com.vidsage.domain.subtitle.model.common.SubtitleContext;
import com.vidsage.domain.subtitle.model.keyword.SubtitleKeywords;
import com.vidsage.domain.transcription.model.TranscriptionSegment;
import java.util.List;

/**
 * 字幕纠错的出口：把 ASR 出来的错字、口头语、乱标点修掉，只做最小改动。
 */
public interface SubtitleCleanser {

    /**
     * 清洗字幕，并在每批清洗完成后回调一次进度。
     *
     * @param segments 拆分后的字幕列表
     * @param context  字幕上下文（主题 / 源语种 / 术语表）
     * @param keywords 用户手填的关键词（专有名词的正确写法；没有就传空清单，不要传 {@code null}）
     *                 ——<b>它不进术语表</b>：术语表要 {@code asr → fixed} 的对照关系，而关键词
     *                 只有「正确写法」，硬编成 {@code asr = fixed} 会被校验里「无需替换就跳过」
     *                 的分支全部跳过，等于失效。
     * @param listener 批级进度回调；不可为 {@code null}
     * @return 清洗结果
     * @throws IllegalArgumentException segments 或 listener 为 {@code null}
     */
    CleanseResult clean(List<TranscriptionSegment> segments,
                        SubtitleContext context,
                        SubtitleKeywords keywords,
                        ProgressListener listener);
}
