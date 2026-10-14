package com.vidsage.application.workspace;


import java.nio.file.Path;
import java.util.List;

/**
 * 视频转字幕任务工作区的文件路径列表
 * 暂时不包含临时tmp、part文件
 * 翻译相关的会保留每个语种最新的翻译文件
 *
 * @param rawSegmentsJsonFilePath        ASR提取的原始字幕Json
 * @param rawSrtFilePath                 ASR提取的原始字幕转化成SRT文件（后续可能会删除）
 * @param cleanSegmentsJsonFilePath      经过清洗后的字幕Json
 * @param cleanSrtFilePath               清洗后的字幕Json转化成SRT文件
 * @param translatedSegmentsJsonFilePath 翻译清洗字幕后的字幕Json （多语种）
 * @param translatedSrtFilePath          翻译翻译清洗字幕后的字幕Json转化成SRT文件 （多语种）
 * @param contextJsonFilePath            字幕上下文Json（存储了从字幕中提取的主题、语种、术语表）列表
 * @param translatedTermsJsonFilePath    翻译后的术语表Json
 * @param sourceMediaFilePath            用户上传/URL抓取的原始媒体文件
 * @param sourceInfoJsonFilePath         yt-dlp 解析URL源时产生的Json文件（暂时认为是临时文件，后续可能会删除）
 * @param audioFilePath                  从用户上传/URL抓取的原始媒体文件中提取的音频文件
 */
public record SubtitleWorkspaceFilePaths(
        Path rawSegmentsJsonFilePath,
        Path rawSrtFilePath,

        Path cleanSegmentsJsonFilePath,
        Path cleanSrtFilePath,

        List<Path> translatedSegmentsJsonFilePath,
        List<Path> translatedSrtFilePath,

        Path contextJsonFilePath,
        List<Path> translatedTermsJsonFilePath,

        Path sourceMediaFilePath,
        Path sourceInfoJsonFilePath,
        Path audioFilePath
) {

//    public static SubtitleWorkspaceFilePaths from(Path workDirectory) {
//
//    }


}
