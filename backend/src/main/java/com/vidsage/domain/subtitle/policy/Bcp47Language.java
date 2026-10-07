package com.vidsage.domain.subtitle.policy;

import com.vidsage.domain.subtitle.model.common.SubtitleContext;
import com.vidsage.domain.subtitle.model.translation.TranslatedSegment;
import com.vidsage.domain.transcription.model.TranscriptionSegment;

import java.util.List;
import java.util.Locale;

import org.springframework.util.StringUtils;

/**
 * BCP-47 语种政策（纯函数领域服务）：翻译的「跳不跳、按什么语种跳、跳了产出什么」三件事的单源。
 *
 * <p>三块规则各管一件事：
 * <ul>
 *   <li>{@link #resolveTargetLanguage}：目标语种取配置值并归一化（trim + 小写），产物文件名与
 *       提示词都吃这个口径，空白即抛（配置错误属「环境不对」，见 error-handling 的 ISE 判据）；</li>
 *   <li>{@link #resolveSourceLanguage}：源语种优先摘要推断（主路径），为空回退 ASR 的 language
 *       （实测多为 null，兜底），两者皆空返回空串——调用方据此<b>不跳过</b>；</li>
 *   <li>{@link #isSameMainLanguage}：只比主语言子标签（zh-CN 陷阱），语种未知不算同一门语言。</li>
 * </ul>
 *
 * <p>无状态、零 I/O：不做文件读写、不调模型，语种知识随调用入参而来。
 */
public final class Bcp47Language {

    private Bcp47Language() {
    }

    /**
     * 解析目标语种：空白抛 ISE，否则返回归一化（去空白 + 转小写）后的值。
     *
     * <p><b>为什么必须在编排入口做、且零 I/O</b>：产物名
     * （{@code SubtitleArtifactNames#translateSegmentsFileName}）里也有一道同样的守卫，但它排在
     * 工作目录解析之后——那只会在<b>建出工作目录之后</b>才报错，属「卫语句晚于 I/O」：配置错却先
     * 产生副作用。error-handling 的硬规则要求边界上可判定的配置错误零 I/O 返回。产物名那道守卫保留作
     * <b>防御性兜底</b>，正常路径已被这里挡住。
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
     * 判定跳过时采用的源语种：摘要推断的优先（主路径），为空回退 ASR 给的 language（兜底），
     * 两者皆空则返回空串（调用方据此<b>不跳过</b>）。
     *
     * @param context       字幕上下文（可能为空上下文对象）
     * @param trackLanguage ASR 识别出的语种，可能为 {@code null} 或空白
     * @return 源语种；两者皆空时为空串
     */
    public static String resolveSourceLanguage(SubtitleContext context, String trackLanguage) {
        if (context != null && StringUtils.hasText(context.sourceLanguage())) {
            return context.sourceLanguage().trim();
        }
        return StringUtils.hasText(trackLanguage) ? trackLanguage.trim() : "";
    }

    /**
     * 两份语种标签是不是同一门语言：<b>只比连字符前的主语言子标签</b>，大小写不敏感。
     *
     * <p><b>为什么不直接 {@code equals}</b>：LLM 可能返回 {@code zh-CN} 而不是 {@code zh}，直接比对会失配
     * → 本该跳过的中文视频被送去翻译一遍，<b>中文译成中文</b>，白烧一轮 LLM 还可能改坏原文；而这个失败
     * 是静默的（不报错、日志里也看不出异常）。{@code zh-CN} / {@code zh-Hant} 对「要不要跳过翻译」这个
     * 判断而言都还是中文，跳过是对的。
     *
     * <p><b>任一方为空即为 {@code false}</b>：两边都取不到语种时，主语言子标签都是空串，
     * 直接比会得出 {@code "" .equals("")} = {@code true}，把「两个都不知道是什么语言」判成同一门语言。
     * 而「语种未知则不跳过、照译」是本任务的既定取舍（宁可多跑一趟，不可静默漏译），
     * 故这里把「未知」显式排除，让这个方法单独拿去用也是对的。
     *
     * @param a 语种标签，可为 {@code null} 或空白
     * @param b 语种标签，可为 {@code null} 或空白
     * @return 主语言子标签相同且都非空时为 {@code true}；任一方取不到语种时为 {@code false}
     */
    public static boolean isSameMainLanguage(String a, String b) {
        String left = mainLanguageSubtag(a);
        String right = mainLanguageSubtag(b);
        return !left.isEmpty() && !right.isEmpty() && left.equals(right);
    }

    /**
     * 取 BCP-47 标签的主语言子标签：去空白、转小写、截到第一个连字符之前。
     *
     * @param language 语种标签，可为 {@code null}
     * @return 主语言子标签；{@code null} 时为空串
     */
    private static String mainLanguageSubtag(String language) {
        if (language == null) {
            return "";
        }
        String trimmed = language.trim().toLowerCase(Locale.ROOT);
        int dash = trimmed.indexOf('-');
        return dash < 0 ? trimmed : trimmed.substring(0, dash);
    }

    /**
     * 跳过翻译时的逐条产物：译文照抄原文，时间戳原样带过。
     *
     * @param segments 源段落
     * @return 逐条「译文 = 原文」的双语段落（双语渲染器会把相同的两行并成一行）
     */
    public static List<TranslatedSegment> skipAll(List<TranscriptionSegment> segments) {
        return segments.stream()
                .map(s -> new TranslatedSegment(s.beginTimeMs(), s.endTimeMs(), s.text(), s.text()))
                .toList();
    }
}
