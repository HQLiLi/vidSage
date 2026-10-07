package com.vidsage.domain.subtitle.port;

import com.vidsage.domain.shared.port.ProgressListener;
import com.vidsage.domain.subtitle.model.cleanup.CleanseResult;
import com.vidsage.domain.subtitle.model.common.SubtitleContext;
import com.vidsage.domain.transcription.model.TranscriptionSegment;
import java.util.List;

/**
 * 字幕纠错的出口：把 ASR 出来的错字、口头语、乱标点修掉，只做最小改动。
 *
 * <p>实现在 {@code infrastructure/provider/llm/}（当前是调 LLM 的 {@code LlmSubtitleCleanser}），
 * 由 Spring 注进来。上层只认这个接口，不认识具体怎么纠的。
 *
 * <p>「纠成什么样才算数」的判据（键集必须与原文完全一致、改写幅度不能超过相似度阈值）属于
 * 实现细节，住实现类里，不进本接口。
 *
 * <p>只声明上层真正调用的那一个重载；不带 listener 的便捷重载留在实现类内部给测试用。
 */
public interface SubtitleCleanser {

    /**
     * 清洗字幕，并在每批清洗完成后回调一次进度。
     *
     * @param segments 拆分后的字幕列表
     * @param context  字幕上下文（主题 / 源语种 / 术语表）
     * @param reference 术语表
     * @param listener 批级进度回调；不可为 {@code null}
     * @return 清洗结果
     * @throws IllegalArgumentException segments 或 listener 为 {@code null}
     */
    CleanseResult clean(List<TranscriptionSegment> segments,
                        SubtitleContext context,
                        String reference,
                        ProgressListener listener);
}
