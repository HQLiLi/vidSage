package com.vidsage.infrastructure.fs;

import com.vidsage.application.media.port.FileStateReader;
import com.vidsage.domain.media.model.MediaFileInfo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;


/**
 * 文件状态阅读实现
 */
@Slf4j
@Component
public class NioFileStateReader implements FileStateReader {

    @Override
    public MediaFileInfo getFileState(Path path) {
        if (path == null) {
            return MediaFileInfo.NOT_APPLICABLE;
        }
        try {
            if (Files.isRegularFile(path)) {
                return new MediaFileInfo(path, Files.size(path),
                        Files.getLastModifiedTime(path).toMillis());
            }
        } catch (IOException e) {
            log.debug("读取本地文件属性失败: file={}, reason={}",
                    path.getFileName(), e.getMessage());
        }
        return new MediaFileInfo(path, MediaFileInfo.UNAVAILABLE, MediaFileInfo.UNAVAILABLE);
    }
}
