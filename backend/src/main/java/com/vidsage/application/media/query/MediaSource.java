package com.vidsage.application.media.query;

import java.nio.file.Path;

/**
 * 一次媒体源播放请求定位到的东西：文件在哪、叫什么、多大、是什么格式。
 *
 * @param path        工作区里的媒体源文件路径
 * @param fileName    工作区内的文件名（只用于响应头里的文件名，不是完整路径）
 * @param contentType 按文件头嗅探出的 MIME 字符串（认不出文件头时退回扩展名）
 * @param sizeBytes   文件字节数，一定大于 0
 */
public record MediaSource(Path path, String fileName, String contentType, long sizeBytes) {
}
