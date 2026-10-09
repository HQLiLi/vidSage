package com.vidsage.application.media;

import com.vidsage.application.media.port.FileStateReader;
import com.vidsage.domain.media.model.MediaFileInfo;
import com.vidsage.domain.media.model.MediaInput;
import com.vidsage.domain.shared.model.WorkspaceKey;
import org.springframework.stereotype.Component;

/**
 * 工作区key生成器
 */
@Component
public class WorkspaceKeyGenerator {
    private final FileStateReader fileStateReader;

    public WorkspaceKeyGenerator(FileStateReader fileStateReader) {
        this.fileStateReader = fileStateReader;
    }

    /**
     * 解析媒体并转化为工作区Key
     *
     * @param input 输入媒体
     * @return 工作区key
     */
    public WorkspaceKey parseMediaToWorkspaceKey(MediaInput input) {
        if (input == null) {
            throw new IllegalArgumentException("媒体输入不能为空：请传 RemoteUrl / LocalPath / InWorkspace");
        }
        return input.getContentKey(getMediaInfo(input));
    }

    /***
     * 获取媒体文件信息
     * @param input 输入媒体
     * @return 媒体快照信息
     */
    private MediaFileInfo getMediaInfo(MediaInput input) {
        if (!(input instanceof MediaInput.LocalPath local) || local.value() == null) {
            return MediaFileInfo.NOT_APPLICABLE;
        }
        return fileStateReader.getFileState(local.value());
    }
}
