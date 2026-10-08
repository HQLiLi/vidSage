package com.vidsage.domain.media.model;

import com.vidsage.domain.shared.model.SourceUri;
import com.vidsage.domain.shared.model.WorkspaceKey;
import com.vidsage.domain.task.model.TaskInputType;

import java.nio.file.Path;
import java.util.Objects;

/**
 * 媒体输入来源：
 * 1.远程URL
 * 2.本地文件
 * 3.已经上传的文件
 */
public sealed interface MediaInput {

    /**
     * 获取媒体的内容寻址key，决定它是在哪个工作区目录
     *
     * @param mediaFileInfo 媒体信息
     * @return 内容寻址Key
     */
    WorkspaceKey getContentKey(MediaFileInfo mediaFileInfo);

    /**
     * 目录名前半段标签。唯一性完全由 key 保证。调试阅读用
     */
    String dirLabel();

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
     * 已经在工作区的媒体文件
     *
     * @param value    路径
     * @param key      工作区key
     * @param dirLabel 目录前半段标签（给人看的）
     */
    record InWorkspace(Path value, WorkspaceKey key, String dirLabel) implements MediaInput {
        @Override
        public WorkspaceKey getContentKey(MediaFileInfo mediaFileInfo) {
            return key;
        }

    }
}
