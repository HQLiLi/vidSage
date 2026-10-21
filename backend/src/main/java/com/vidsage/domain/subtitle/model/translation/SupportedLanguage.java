package com.vidsage.domain.subtitle.model.translation;


import com.vidsage.domain.exception.UnsupportedLanguageException;
import lombok.Getter;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * 翻译支持的语言语种
 */
@Getter
public enum SupportedLanguage {

    SIMPLE_CHINESE("zh-Hans", "简体中文", List.of("zh", "zh-CN", "zh-SG", "zh-Hans")),
    TRADITIONAL_CHINESE("zh-Hant", "繁体中文", List.of("zh-TW", "zh-HK", "zh-MO", "zh-Hant")),
    ENGLISH("en", "英语", List.of("en")),
    JAPANESE("ja", "日语", List.of("ja"));


    // 编号，用于告诉LLM翻译语种
    private final String code;
    // 展示标签
    private final String label;
    // 匹配这个语种的key列表
    private final List<String> matchKeys;


    SupportedLanguage(String code, String label, List<String> matchKeys) {
        this.code = code;
        this.label = label;
        this.matchKeys = matchKeys;
    }

    /**
     * 输入判断是否匹配语种
     *
     * @param input 输入Key
     * @return 是否匹配
     */
    public boolean matches(String input) {
        if (input == null) {
            return false;
        }
        String inputLowerCase = input.toLowerCase().trim();
        return matchKeys.stream().anyMatch(key -> key.toLowerCase().equals(inputLowerCase));
    }

    /**
     * 将输入语种解析成支持语种
     *
     * @param input 输入语种
     * @return 支持的语种
     * @throws UnsupportedLanguageException 当输入语种不支持时抛出
     */
    public static SupportedLanguage parse(String input) {
        Optional<SupportedLanguage> firstMatchLang = getFirstMatchLang(input);
        if (firstMatchLang.isEmpty()) {
            throw new UnsupportedLanguageException(input);
        }
        return firstMatchLang.get();
    }

    /**
     * 获取匹配输入Key的第一个语种
     *
     * @param input 输入Key
     * @return 第一个匹配的语种，可能为null
     */
    public static Optional<SupportedLanguage> getFirstMatchLang(String input) {
        return Arrays.stream(values()).filter(lang -> lang.matches(input)).findFirst();
    }

    /**
     * 判断是否是相同的语种
     *
     * @param target 目标语种
     * @return 是否相同
     */
    public boolean isSameLanguage(SupportedLanguage target) {
        if (target == null) {
            return false;
        }
        return code.equals(target.code);
    }

}
