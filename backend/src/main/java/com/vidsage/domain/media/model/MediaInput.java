package com.vidsage.domain.media.model;

import com.vidsage.domain.shared.model.SourceUri;
import com.vidsage.domain.shared.model.WorkspaceKey;
import com.vidsage.domain.task.model.TaskInputType;

import java.nio.file.Path;
import java.util.Objects;

/**
 * 媒体输入来源：远程 URL、本机文件路径、或已经落在工作区的文件（三个嵌套 record 各一种）。
 */
public sealed interface MediaInput {

    /**
     * 算出这条输入的内容寻址 key，它决定这份工作落在哪个工作区目录。
     *
     * @param mediaFileInfo 本机观测到的文件快照；只有本地文件路线用它算身份，其余路线忽略
     * @return 工作区短哈希
     */
    WorkspaceKey getContentKey(MediaFileInfo mediaFileInfo);

    /**
     * 目录名里给人看的那半段标签；区分工作区靠 key 后缀，本值只供日志与调试阅读。
     */
    String dirLabel();

    String SOURCE_FILE_PREFIX = "source.";
    String SOURCE_INFO_JSON_NAME = "source.info.json";

    String UPLOAD_SOURCE_FILE_NAME = "source.mp4";


    static MediaInput ofTaskInput(TaskInputType inputType, String sourceUri,
                                  Path workspaceDir, WorkspaceKey workspaceKey) {
        return switch (inputType) {
            case URL -> new RemoteUrl(sourceUri);
            case UPLOAD -> new InWorkspace(workspaceDir.resolve(UPLOAD_SOURCE_FILE_NAME), workspaceKey,
                    sourceUri);
        };
    }

    /**
     * 公网可访问的媒体页面地址，由下载器负责解析出可下载的媒体流。
     *
     * @param value 完整 URL
     */
    record RemoteUrl(String value) implements MediaInput {
        @Override
        public WorkspaceKey getContentKey(MediaFileInfo mediaFileInfo) {
            return WorkspaceKey.ofHash(value == null ? "" : value);
        }

        @Override
        public String dirLabel() {
            return value == null ? "" : value;
        }
    }

    /**
     * 本机可读的媒体文件路径，直接采用，不下载、不拷贝。
     *
     * @param value 绝对或相对路径
     */
    record LocalPath(Path value) implements MediaInput {
        @Override
        public WorkspaceKey getContentKey(MediaFileInfo mediaFileInfo) {
            if (mediaFileInfo == null || !Objects.equals(value, mediaFileInfo.path())) {
                throw new IllegalArgumentException(
                        "文件快照与输入不一致: input=" + value + ", snapshot="
                                + (mediaFileInfo == null ? null : mediaFileInfo.path()));
            }
            return WorkspaceKey.ofHash(SourceUri.ofLocalFile(mediaFileInfo.path(), mediaFileInfo.sizeBytes(),
                    mediaFileInfo.modifyTimeMs()).value());
        }

        @Override
        public String dirLabel() {
            Path fileName = value == null ? null : value.getFileName();
            return fileName == null ? "" : fileName.toString();
        }
    }

    /**
     * 已经在工作区的媒体文件。
     *
     * @param value    工作区里的文件路径
     * @param key      工作区短哈希
     * @param dirLabel 目录名里给人看的标签
     */
    record InWorkspace(Path value, WorkspaceKey key, String dirLabel) implements MediaInput {
        @Override
        public WorkspaceKey getContentKey(MediaFileInfo mediaFileInfo) {
            return key;
        }

    }
}
