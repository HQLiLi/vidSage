package com.vidsage.application.workspace;

import com.vidsage.domain.subtitle.model.translation.SupportedLanguage;

import java.nio.file.Path;
import java.util.Optional;

/**
 * @param langCode                       语种，对应{@link SupportedLanguage#getCode()}
 * @param translatedSegmentsJsonFilePath 译文字幕Json（翻译清洗后的字幕得到的）
 * @param translatedSrtFilePath          译文双语 SRT（由译文字幕 Json 渲染）
 * @param translatedTermsJsonFilePath    翻译后的术语表Json
 */
public record TranslationFilePaths(
                String langCode,
                Optional<Path> translatedSegmentsJsonFilePath,
                Optional<Path> translatedSrtFilePath,
                Optional<Path> translatedTermsJsonFilePath) {
}
