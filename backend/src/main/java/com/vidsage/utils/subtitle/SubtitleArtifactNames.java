package com.vidsage.utils.subtitle;

import com.vidsage.model.subtitle.SubtitleRoute;
import java.util.Locale;

/**
 * 字幕产物的文件名规则：路线名转 kebab token，以及各产物名（原始 / 清洗 × JSON / SRT，外加上下文摘要缓存）。
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
}
