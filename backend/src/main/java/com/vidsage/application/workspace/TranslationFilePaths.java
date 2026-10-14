package com.vidsage.application.workspace;

import com.vidsage.domain.subtitle.model.translation.SupportedLanguage;

import java.nio.file.Path;
import java.util.Optional;

/**
 * @param langCode                       语种，对应{@link SupportedLanguage#getCode()}
 * @param translatedSegmentsJsonFilePath 翻译清洗字幕后的字幕Json
 * @param translatedSrtFilePath          翻译翻译清洗字幕后的字幕Json转化成SRT文件
 * @param translatedTermsJsonFilePath    翻译后的术语表Json
 */
public record TranslationFilePaths(
        String langCode,
        Optional<Path> translatedSegmentsJsonFilePath,
        Optional<Path> translatedSrtFilePath,
        Optional<Path> translatedTermsJsonFilePath
) {
}
