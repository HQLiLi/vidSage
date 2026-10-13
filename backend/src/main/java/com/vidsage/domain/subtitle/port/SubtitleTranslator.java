package com.vidsage.domain.subtitle.port;

import com.vidsage.domain.shared.port.ProgressListener;
import com.vidsage.domain.subtitle.model.common.SubtitleContext;
import com.vidsage.domain.subtitle.model.term.VerifiedTerm;
import com.vidsage.domain.subtitle.model.translation.TranslateResult;
import com.vidsage.domain.transcription.model.TranscriptionSegment;
import java.util.List;

/**
 * 字幕翻译的出口：把清洗后的每条字幕译成目标语种，术语与称谓全片一致。
 */
public interface SubtitleTranslator {

    /**
     * 翻译字幕，并在每批翻译完成后回调一次进度。
     *
     * @param segments       待翻译的段落（清洗后的 {@code -clean} 内容）
     * @param context        字幕上下文（主题 / 源语种 / 术语表）
     * @param targetLanguage 目标语种（BCP-47 短码）
     * @param officialTerms  术语官方译名查证产物，可为空表
     * @param listener       批级进度回调；不可为 {@code null}
     * @return 逐条双语段落 + 重试轮数 + 未翻译段数
     * @throws IllegalArgumentException segments 或 listener 为 {@code null}
     */
    TranslateResult translate(List<TranscriptionSegment> segments,
                              SubtitleContext context,
                              String targetLanguage,
                              List<VerifiedTerm> officialTerms,
                              ProgressListener listener);
}
