package com.vidsage.application.subtitle;

import com.vidsage.application.subtitle.query.LanguageOptionSnapshot;
import com.vidsage.domain.subtitle.model.translation.SupportedLanguage;
import java.util.Arrays;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * 翻译语种清单的供给：把白名单枚举摊成前端下拉要用的两项（短码 + 中文名）。
 *
 * <p>为什么要这一层，而不让 web 直接读枚举：本项目禁 web 依赖 domain（main 源码里 web
 * 一个 domain 类型都不引），而白名单必须住 domain（解析与跳过判据都在那儿）。这层映射就是那条规则的出口。
 *
 * <p>源语种与目标语种共用这一份清单：两个参数都过同一个 {@link SupportedLanguage#parse}，
 * 可选集合本来就是同一个。
 */
@Service
public class SubtitleLanguageService {

    /**
     * 列出支持的语种，顺序即枚举声明顺序（简体中文 / 繁体中文 / 英语 / 日语）。
     *
     * @return 每项只有短码与中文名；恒非空
     */
    public List<LanguageOptionSnapshot> listSupportedLanguages() {
        return Arrays.stream(SupportedLanguage.values())
                .map(language -> new LanguageOptionSnapshot(language.getCode(), language.getLabel()))
                .toList();
    }
}
