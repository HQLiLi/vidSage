package com.vidsage.application.workspace;


import com.vidsage.application.exception.InvalidWorkDirectoryException;
import com.vidsage.domain.media.model.AudioArtifact;
import com.vidsage.domain.media.model.MediaInput;
import com.vidsage.domain.subtitle.model.common.SubtitleArtifactSpec;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * 视频转字幕任务工作区的文件路径列表
 * 暂时不包含临时tmp、part文件
 * 翻译相关的会保留每个语种最新的翻译文件
 *
 * @param originalSegmentsJsonFilePath ASR提取的原始字幕Json
 * @param originalSrtFilePath          ASR提取的原始字幕转化成SRT文件（后续可能会删除）
 * @param cleanSegmentsJsonFilePath    经过清洗后的字幕Json
 * @param cleanSrtFilePath             清洗后的字幕Json转化成SRT文件
 * @param translationFilePaths         翻译后的字幕相关（segmentJson,SRT文件，翻译术语）
 * @param contextJsonFilePath          字幕上下文Json（存储了从字幕中提取的主题、语种、术语表）列表
 * @param sourceMediaFilePath          用户上传/URL抓取的原始媒体文件
 * @param sourceInfoJsonFilePath       yt-dlp 解析URL源时产生的Json文件（暂时认为是临时文件，后续可能会删除）
 * @param audioFilePath                从用户上传/URL抓取的原始媒体文件中提取的音频文件
 */
public record SubtitleWorkspaceFilePaths(
        Optional<Path> originalSegmentsJsonFilePath,
        Optional<Path> originalSrtFilePath,

        Optional<Path> cleanSegmentsJsonFilePath,
        Optional<Path> cleanSrtFilePath,

        List<TranslationFilePaths> translationFilePaths,

        Optional<Path> contextJsonFilePath,

        Optional<Path> sourceMediaFilePath,
        Optional<Path> sourceInfoJsonFilePath,
        Optional<Path> audioFilePath
) {

    /**
     * 获取工作目录中的产物文件Path列表
     *
     * @param workDirectory 工作区目录
     * @return 视频转字幕任务工作区的文件路径列表
     */
    public static SubtitleWorkspaceFilePaths of(Path workDirectory) {
        if (workDirectory == null) {
            throw new InvalidWorkDirectoryException("工作区目录不能为空");
        }
        List<String> workspaceFileNames = getWorkspaceFileNames(workDirectory);

        return new SubtitleWorkspaceFilePaths(
                findPath(workDirectory, workspaceFileNames, SubtitleArtifactSpec.ORIGINAL_SEGMENTS),
                findPath(workDirectory, workspaceFileNames, SubtitleArtifactSpec.ORIGINAL_SRT),
                findPath(workDirectory, workspaceFileNames, SubtitleArtifactSpec.CLEAN_SEGMENTS),
                findPath(workDirectory, workspaceFileNames, SubtitleArtifactSpec.CLEAN_SRT),
                getTranslationFiles(workDirectory, workspaceFileNames),
                findPath(workDirectory, workspaceFileNames, SubtitleArtifactSpec.CONTEXT),
                getFirstMatchingFilePath(workDirectory, workspaceFileNames, fileName ->
                        fileName.startsWith(MediaInput.SOURCE_FILE_PREFIX) && !fileName.equals(MediaInput.SOURCE_INFO_JSON_NAME)),
                getFirstMatchingFilePath(workDirectory, workspaceFileNames, fileName ->
                        fileName.equals(MediaInput.SOURCE_INFO_JSON_NAME)),
                getFirstMatchingFilePath(workDirectory, workspaceFileNames, fileName ->
                        fileName.startsWith(AudioArtifact.AUDIO_PREFIX)
                )
        );
    }

    /**
     * 获取工作区目录下的文件名列表
     *
     * @param directory 工作区目录
     * @return 文件名列表
     */
    private static List<String> getWorkspaceFileNames(Path directory) {
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        try (Stream<Path> entries = Files.list(directory)) {
            return entries.filter(Files::isRegularFile)
                    .map(path -> path.getFileName().toString())
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("扫描工作区目录失败：" + directory + ";" + e.getMessage(), e);
        }
    }


    /**
     * 根据文件模板获取指定的Path 重载
     */
    private static Optional<Path> findPath(Path directory, List<String> fileNames, SubtitleArtifactSpec spec) {
        return findPath(directory, fileNames, spec, null);
    }

    /**
     * 根据文件模板获取指定的Path
     *
     * @param directory 工作区目录
     * @param fileNames 工作区目录下的文件名列表
     * @param spec      模板
     * @param lang      语种 （翻译会用到）
     * @return 找到的Path
     */
    private static Optional<Path> findPath(Path directory, List<String> fileNames, SubtitleArtifactSpec spec, String lang) {
        String fileName = spec.getFileName(lang);
        return fileNames.contains(fileName) ? Optional.of(directory.resolve(fileName)) : Optional.empty();
    }

    /**
     * 译文三件套：语种取自三条带语种产物的**并集**，字典序。
     */
    private static List<TranslationFilePaths> getTranslationFiles(Path dir, List<String> names) {
        Set<String> languages = new TreeSet<>();
        collectLanguages(languages, names, SubtitleArtifactSpec.TRANSLATED_SEGMENTS);
        collectLanguages(languages, names, SubtitleArtifactSpec.TRANSLATED_SRT);
        collectLanguages(languages, names, SubtitleArtifactSpec.TERMS);

        List<TranslationFilePaths> files = new ArrayList<>(languages.size());
        for (String language : languages) {
            files.add(new TranslationFilePaths(language,
                    findPath(dir, names, SubtitleArtifactSpec.TRANSLATED_SEGMENTS, language),
                    findPath(dir, names, SubtitleArtifactSpec.TRANSLATED_SRT, language),
                    findPath(dir, names, SubtitleArtifactSpec.TERMS, language)));
        }
        return files;
    }

    /**
     * 把某条带语种产物里出现过的语种收进 {@code languages}。
     */
    private static void collectLanguages(Set<String> languages, List<String> fileNames,
                                         SubtitleArtifactSpec spec) {
        for (String fileName : fileNames) {
            String language = spec.getLanguage(fileName);
            if (language != null) {
                languages.add(language);
            }
        }

    }

    /**
     * 按名字认媒体侧文件；同前缀命中多个（重跑残留）取字典序第一个
     */
    private static Optional<Path> getFirstMatchingFilePath(Path dir, List<String> names, Predicate<String> matches) {
        return names.stream().filter(matches).findFirst().map(dir::resolve);
    }
}
