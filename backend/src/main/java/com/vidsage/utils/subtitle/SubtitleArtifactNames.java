package com.vidsage.utils.subtitle;

import com.vidsage.model.subtitle.SubtitleRoute;
import java.util.Locale;
import org.springframework.util.StringUtils;

/**
 * 字幕产物的文件名规则：路线名转 kebab token，以及各产物名（原始 / 清洗 / 翻译 × JSON / SRT，
 * 外加上下文摘要缓存）。
 *
 * <p>全静态、无状态，构造器私有，不要 new 它。
 *
 * <p><b>文件名规则只有这里知道</b>：工作目录按媒体内容寻址，改这里的字符串会让已有缓存全部失配，
 * 等于把存量产物从「已完成」打回「未完成」。
 */
public final class SubtitleArtifactNames {

    private SubtitleArtifactNames() {
    }

    /**
     * 枚举名 → 产物 token：{@code ASR_CLOUD → asr-cloud}。
     *
     * <p>对照 {@code AudioPreparationService.audioFileName} 从 {@code name()} 派生扩展名——
     * 派生规则只有定名字的人知道，不写进契约类型。token 用 kebab（与 JSON 里的枚举名风格不同
     * 不是疏忽，与 {@code AudioContainer} 同名同源、两种媒材各自的惯例，design 决策 4）。
     *
     * @param route 字幕路线
     * @return kebab 形式的路线 token
     */
    public static String routeToken(SubtitleRoute route) {
        return route.name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    /**
     * 原始 SRT 产物文件名，带路线指纹：{@code subtitles-asr-cloud.srt}。
     *
     * <p><b>为什么必须带路线指纹</b>：工作目录按媒体内容寻址，同一媒体下唯一会变的就是「谁
     * 转写的」。若叫 {@code subtitles.srt}，换 provider 后重跑会命中上一次云端产物被当成已完成而
     * 跳过，返回与当前配置不符的字幕且不报错。指纹防止跨 provider 误用（design 决策 4）。
     */
    public static String srtFileName(SubtitleRoute route) {
        return "subtitles-" + routeToken(route) + ".srt";
    }

    /**
     * 原始 segments JSON 产物文件名，带路线指纹：{@code segments-asr-cloud.json}。这是检查点。
     */
    public static String segmentsFileName(SubtitleRoute route) {
        return "segments-" + routeToken(route) + ".json";
    }

    /**
     * 清洗后的 SRT 产物文件名：{@code subtitles-asr-cloud-clean.srt}。
     */
    public static String cleanSrtFileName(SubtitleRoute route) {
        return "subtitles-" + routeToken(route) + "-clean.srt";
    }

    /**
     * 清洗后的 segments JSON 产物文件名（清洗阶段自己的检查点）：{@code segments-asr-cloud-clean.json}。
     */
    public static String cleanSegmentsFileName(SubtitleRoute route) {
        return "segments-" + routeToken(route) + "-clean.json";
    }

    /**
     * 上下文摘要缓存文件名：{@code context-asr-cloud.json}。清洗与翻译共用同一份。
     *
     * <p><b>故意不带目标语种指纹</b>——本文件里别的产物名都带指纹，这不是漏了：上下文就三样
     * （主题、术语、源语种），与译成哪种语言无关，同一份对任何目标语种都成立。带指纹反而会让
     * 换目标语种时白重跑一次摘要。
     */
    public static String contextFileName(SubtitleRoute route) {
        return "context-" + routeToken(route) + ".json";
    }

    /**
     * 翻译后的 segments JSON 产物文件名：{@code segments-asr-cloud-translate-zh.json}。这是检查点。
     *
     * <p><b>为什么必须带目标语种指纹</b>：理由与 {@link #srtFileName(SubtitleRoute)} 的路线指纹同源
     * ——工作目录按媒体内容寻址，目标语种是配置驱动的，同一媒体下唯一会变的就是「译成什么语」。
     * 若不带指纹，改了 {@code vidsage.subtitle.translate.target-language} 再跑会命中上一次的产物被
     * 当成已完成而跳过，返回与当前配置不符的字幕且不报错。
     */
    public static String translateSegmentsFileName(SubtitleRoute route, String targetLanguage) {
        return "segments-" + routeToken(route) + "-translate-" + normalizeLanguage(targetLanguage) + ".json";
    }

    /**
     * 双语 SRT 产物文件名：{@code subtitles-asr-cloud-translate-zh.srt}。这是派生视图。
     *
     * <p>带目标语种指纹的理由见 {@link #translateSegmentsFileName(SubtitleRoute, String)}。
     */
    public static String translateSrtFileName(SubtitleRoute route, String targetLanguage) {
        return "subtitles-" + routeToken(route) + "-translate-" + normalizeLanguage(targetLanguage) + ".srt";
    }

    /**
     * 目标语种归一化：去掉首尾空白并转小写，让 {@code ZH} 与 {@code zh} 指向同一个文件。
     *
     * <p>不归一化的话，配置里的大小写一变就会静默产出第二份产物、绕开断点判据——旧的「已完成」
     * 与新的「未完成」并存，同一媒体下出现两份互相不知道对方存在的字幕。
     *
     * <p><b>为空时直接抛错，不兜底</b>：空语种若落成 {@code translate-.json} 之类的名字，任何两个
     * 未配置或配置为空的语种都会撞到同一份产物，正是上面要防的静默冲突；这里用早失败替代它。
     *
     * <p><b>正常路径上这道守卫够不到</b>：{@code SubtitleTranslationService} 已在入口用
     * {@code resolveTargetLanguage} 提前挡下空语种（早于建工作目录的 I/O）。这里保留作<b>防御性兜底</b>
     * ——只兜「有人绕过 service 直接拼名」的情况，不是第一道防线。
     *
     * @param targetLanguage 目标语种（BCP-47 短码，如 zh / en）
     * @return 归一化后的语种 token
     * @throws IllegalStateException 语种为 {@code null} 或空白（该值来自配置，属「环境不对」而非调用方传参不合法）
     */
    private static String normalizeLanguage(String targetLanguage) {
        if (!StringUtils.hasText(targetLanguage)) {
            // 用 ISE 而非 IAE：这个值来自配置（vidsage.subtitle.translate.target-language），
            // 不是请求体。按 error-handling 的「非法值是谁产生的」判据，配置问题属「环境不对」→ ISE → 500。
            // 抛 IAE 会被映射成 400，等于告诉调用方「你的请求有问题」——而请求完全合法，是部署配置错了。
            throw new IllegalStateException(
                    "目标语种不能为空：请设置 vidsage.subtitle.translate.target-language"
                            + "（BCP-47 短码，如 zh / en）");
        }
        return targetLanguage.trim().toLowerCase(Locale.ROOT);
    }
}
