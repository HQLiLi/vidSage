package com.vidsage.domain.subtitle.model.common;

import com.vidsage.domain.subtitle.model.translation.SupportedLanguage;
import com.vidsage.domain.subtitle.policy.Bcp47LanguagePolicy;
import lombok.Getter;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 字幕产物的登记表：生成产物名，并回答一个文件是不是检查点。
 */
public enum SubtitleArtifactSpec {
    /**
     * 原始转写检查点。
     */
    ORIGINAL_SEGMENTS("original-segments.json", true),

    /**
     * 原始 SRT：由 JSON 渲染的派生视图，不参与幂等判定。
     */
    ORIGINAL_SRT("original-subtitles.srt", false),

    /**
     * 清洗后检查点。
     */
    CLEAN_SEGMENTS("clean-segments.json", true),
    CLEAN_SRT("clean-subtitles.srt", false),

    /**
     * 翻译检查点。带目标语种指纹——换语种绝不拿上一份凑数。
     */
    TRANSLATED_SEGMENTS("translated-segments-{lang}.json", true),
    TRANSLATED_SRT("translated-subtitles-{lang}.srt", false),


    /**
     * 上下文摘要：清洗与翻译共用。故意不带语种指纹——主题/术语/源语种与译成什么语无关。
     */
    CONTEXT("context.json", true),

    /**
     * 术语查证结论：删文件即重查；查证失败的词不进文件，下次自然补查。
     */
    TERMS("terms-{lang}.json", true);


    private final String template; // 名称模板
    @Getter
    private final boolean checkpoint; // 是否为检查点

    private final Pattern pattern;

    SubtitleArtifactSpec(String template, boolean checkpoint) {
        this.template = template;
        this.checkpoint = checkpoint;
        this.pattern = compileTemplate(template);
    }


    public String getFileName(String lang) {
        return template.contains("{lang}")
                ? template.replace("{lang}", Bcp47LanguagePolicy.resolveTargetLanguage(lang))
                : template;
    }

    /**
     * 从文件名中获取语种:{@link SupportedLanguage#getCode()}。
     * 如果调用模板不含{lang}或文件名为null则返回null
     *
     * @param fileName 文件名 跟翻译相关的，如 translated-subtitles-en.srt
     * @return 语种code
     */
    public String getLanguage(String fileName) {
        if (fileName == null || !template.contains("{lang}")) {
            return null;
        }
        Matcher matcher = pattern.matcher(fileName);
        return matcher.matches() ? matcher.group(1) : null;
    }


    public boolean matches(String fileName) {
        return pattern.matcher(fileName).matches();
    }

    /**
     * 模板 → 正则：字面量按正则转义，{lang} 换语种段（模板里除 {lang} 外都是字面量）。
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
            if (slot.equals("{lang}")) {
                regex.append("([a-z0-9-]+)");
            } else {
                throw new IllegalStateException("未知的模板占位符：" + slot);
            }
            cursor = slotEnd + 1;
        }
        return Pattern.compile(regex.toString());
    }


}
