package com.vidsage.application.subtitle.query;

/**
 * 一个可选语种：短码 + 中文名。
 *
 * <p>本类不带任何 web 注解——对外注解集中在 {@code web/dto/LanguageResponse}，
 * 本层只给零注解的读模型。
 *
 * @param code  回传给翻译端点用的短码（标准大小写，如 {@code zh-Hant}）
 * @param label 给前端下拉直接显示的中文名（如「繁体中文」）
 */
public record LanguageOptionSnapshot(String code, String label) {
}
