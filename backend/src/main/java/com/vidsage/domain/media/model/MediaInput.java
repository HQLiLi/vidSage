package com.vidsage.domain.media.model;

import java.nio.file.Path;

/**
 * 媒体输入来源：远程 URL、本机文件路径、或已经落在工作区的文件（三个嵌套 record 各一种）。
 *
 * <p>身份键（工作区归属）不在这里算——提交时由 URL 规范化策略或内容 sha256 一次算定，
 * 目录名是它的纯函数；管线从任务持有的身份重建输入时，按来源类型挑变体即可。
 */
public sealed interface MediaInput {

    /*源媒体文件前缀*/
    String SOURCE_FILE_PREFIX = "source.";
    /*源媒体信息*/
    String SOURCE_INFO_JSON_NAME = SOURCE_FILE_PREFIX + "info.json";
    /*用户上传源文件落盘名称*/
    String UPLOAD_SOURCE_FILE_NAME = SOURCE_FILE_PREFIX + "mp4";

    /**
     * 公网可访问的媒体页面地址，由下载器负责解析出可下载的媒体流。
     *
     * @param value 完整 URL
     */
    record RemoteUrl(String value) implements MediaInput {
    }

    /**
     * 本机可读的媒体文件路径，直接采用，不下载、不拷贝。
     *
     * @param value 绝对或相对路径
     */
    record LocalPath(Path value) implements MediaInput {
    }

    /**
     * 已经在工作区的媒体文件（上传落盘后的 {@code source.mp4}）。
     *
     * @param value 工作区里的文件路径
     */
    record InWorkspace(Path value) implements MediaInput {
    }
}
