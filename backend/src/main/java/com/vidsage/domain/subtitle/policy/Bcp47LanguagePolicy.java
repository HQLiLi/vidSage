package com.vidsage.domain.subtitle.policy;

import com.vidsage.domain.subtitle.model.translation.TranslatedSegment;
import com.vidsage.domain.transcription.model.TranscriptionSegment;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Locale;

/**
 * BCP-47 语种政策（纯函数领域服务）：翻译的「跳不跳、按什么语种跳、跳了产出什么」三件事的单源。
 */
public final class Bcp47LanguagePolicy {

    private Bcp47LanguagePolicy() {
    }

    /**
     * 解析目标语种：空白抛 ISE，否则返回归一化（去空白 + 转小写）后的值。
     *
     * <p><b>调用点要排在建工作目录那类 I/O 之前</b>：配置错了就该立刻抛，而不是先建了目录、
     * 甚至跑完一轮 ASR 再报错——那样错误信息会被前面的 I/O 失败盖掉。
     *
     * @param configuredTarget 配置的目标语种原始值；可空白
     * @return 归一化后的目标语种（trim + 小写），与产物名的归一化口径一致
     * @throws IllegalStateException 语种为 {@code null} 或空白（该值来自配置，属「环境不对」而非调用方传参不合法）
     */
    public static String resolveTargetLanguage(String configuredTarget) {
        if (!StringUtils.hasText(configuredTarget)) {
            throw new IllegalStateException(
                    "目标语种不能为空：请设置 vidsage.subtitle.translate.target-language"
                            + "（BCP-47 短码，如 zh / en）");
        }
        return configuredTarget.trim().toLowerCase(Locale.ROOT);
    }


    /**
     * 跳过翻译时的逐条产物：译文照抄原文，时间戳与 id 原样带过。
     *
     * @param segments 源段落
     * @return 逐条「译文 = 原文」的双语段落（双语渲染器会把相同的两行并成一行）
     */
    public static List<TranslatedSegment> skipAll(List<TranscriptionSegment> segments) {
        return segments.stream()
                .map(s -> new TranslatedSegment(s.beginTimeMs(), s.endTimeMs(), s.text(), s.text(), s.id()))
                .toList();
    }
}
