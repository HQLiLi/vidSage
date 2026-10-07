package com.vidsage.domain.subtitle.model.common;

import com.vidsage.domain.subtitle.policy.Bcp47Language;
import lombok.Getter;

import java.util.Arrays;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 字幕登记产出表，叫什么 / 能不能下载 / 是不是检查点
 */
public enum SubtitleArtifactSpec {
    /**
     * 原始转写检查点。
     */
    SEGMENTS_RAW("segments-{route}.json", true, true),

    /**
     * 原始 SRT：由 JSON 渲染的派生视图，不参与幂等判定。
     */
    SRT_RAW("subtitles-{route}.srt", true, false),

    /**
     * 清洗后检查点。
     */
    SEGMENTS_CLEAN("segments-{route}-clean.json", true, true),
    SRT_CLEAN("subtitles-{route}-clean.srt", true, false),

    /**
     * 翻译检查点。带目标语种指纹——换语种绝不拿上一份凑数。
     */
    SEGMENTS_TRANSLATED("segments-{route}-translate-{lang}.json", true, true),
    SRT_TRANSLATED("subtitles-{route}-translate-{lang}.srt", true, false),


    /**
     * 上下文摘要：清洗与翻译共用。故意不带语种指纹——主题/术语/源语种与译成什么语无关。
     */
    CONTEXT("context-{route}.json", false, true),

    /**
     * 术语查证结论：删文件即重查；查证失败的词不进文件，下次自然补查。
     */
    TERMS("terms-{route}-{lang}.json", false, true);


    private final String template; // 名称模板
    @Getter
    private final boolean downloadable; // 是否可下载
    @Getter
    private final boolean checkpoint; // 是否为检查点

    private final Pattern pattern;

    SubtitleArtifactSpec(String template, boolean downloadable, boolean checkpoint) {
        this.template = template;
        this.downloadable = downloadable;
        this.checkpoint = checkpoint;
        this.pattern = compileTemplate(template);
    }


    public String fileName(SubtitleRoute route, String lang) {
        String name = template.replace("{route}", routeToken(route));
        return name.contains("{lang}") ? name.replace("{lang}", Bcp47Language.resolveTargetLanguage(lang)) : name;
    }

    /**
     * 枚举名 → 产物 token：{@code ASR_CLOUD → asr-cloud}。
     *
     * @param route 产物路线
     * @return kebab 形式的路线 token
     */
    private static String routeToken(SubtitleRoute route) {
        return route.name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    /**
     * 传入的文件是否可下载
     *
     * @param fileName 传入文件
     * @return 是否可下载
     */
    public static boolean isDownloadable(String fileName) {
        for (SubtitleArtifactSpec spec : values()) {
            if (spec.downloadable && spec.matches(fileName)) {
                return true;
            }
        }
        return false;
    }

    public boolean matches(String fileName) {
        return pattern.matcher(fileName).matches();
    }

    /**
     * 模板 → 正则：字面量按正则转义，{route} 换已知 token 的交替，{lang} 换语种段。
     */
    private static Pattern compileTemplate(String template) {
        StringBuilder regex = new StringBuilder();
        int cursor = 0;
        while (cursor < template.length()) {
            int slotStart = template.indexOf('{', cursor);
            if (slotStart < 0) {
                regex.append(Pattern.quote(template.substring(cursor)));
                break;
            }
            regex.append(Pattern.quote(template.substring(cursor, slotStart)));
            int slotEnd = template.indexOf('}', slotStart);
            String slot = template.substring(slotStart, slotEnd + 1);
            regex.append(switch (slot) {
                case "{route}" -> RouteAlternation.VALUE;
                case "{lang}" -> "[a-z0-9-]+";
                // 占位符名字写错时早失败：否则它会被当成字面量，永远匹配不到任何文件，且不报错
                default -> throw new IllegalStateException("未知的模板占位符：" + slot);
            });
            cursor = slotEnd + 1;
        }
        return Pattern.compile(regex.toString());
    }

    /**
     * 路线 token 交替的懒加载持有类
     */
    private static final class RouteAlternation {
        static final String VALUE = Arrays.stream(SubtitleRoute.values())
                .map(route -> Pattern.quote(routeToken(route)))
                .collect(Collectors.joining("|"));

        private RouteAlternation() {
        }
    }


}
