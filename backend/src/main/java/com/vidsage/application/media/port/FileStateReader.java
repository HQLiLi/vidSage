package com.vidsage.application.media.port;

import com.vidsage.domain.media.model.MediaFileInfo;

import java.nio.file.Path;

/***
 * 文件状态阅读器
 */
public interface FileStateReader {

    /**
     * 获取媒体状态
     *
     * @param path 媒体路径
     * @return 媒体状态快照
     */
    MediaFileInfo getFileState(Path path);
}
