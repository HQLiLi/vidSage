package com.vidsage.model.media;

import java.nio.file.Path;

/**
 * 媒体输入来源：契约只描述「媒体从哪里来」，不绑定任何实现方的获取方式。
 *
 * <p>两条路线（远程 URL 下载 / 本地文件采用）对上层是同一个契约——上层按子类型
 * 分派适配器，不感知是下载还是直接采用。
 *
 * <p>负面约束：本类型只承载「位置」，不含工作目录、进度监听等编排参数（那些由
 * 调用方另行传入）；也不要用它表达尚未实现的来源（如抖音）。
 */
public sealed interface MediaInput {

    /**
     * 公网可访问的媒体页面地址，由下载器负责解析出可下载的媒体流。
     *
     * @param value 完整 URL；<b>不要传本地文件路径</b>（本地文件请用 {@link LocalPath}）
     */
    record RemoteUrl(String value) implements MediaInput {
    }

    /**
     * 本机可读的媒体文件路径，直接采用，不下载、不拷贝。
     *
     * @param value 绝对或相对路径；调用方负责保证其存在且可读
     */
    record LocalPath(Path value) implements MediaInput {
    }
}
