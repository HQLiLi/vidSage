package com.vidsage.application.subtitle.query;

import com.vidsage.domain.subtitle.model.ArtifactFileKind;
import java.nio.file.Path;

/**
 * 一次产物下载的解析结果：正文要发什么 + 响应头要用的信息。
 *
 * <p>正文有两种来源，二选一：{@code content} 为 {@code null} 时发磁盘上的文件，
 * 否则发 {@code content} 这份字节。<b>路径任何时候都要带</b>——`Content-Disposition` 里的
 * 下载文件名从它取，与正文是否重新渲染无关。
 *
 * @param path    工作区里的产物文件路径（也决定下载文件名）
 * @param kind    文件位（决定响应的 Content-Type）
 * @param content 套了纠错之后要发的字节；{@code null} 表示直发磁盘文件
 */
public record ArtifactDownload(Path path, ArtifactFileKind kind, byte[] content) {

    /**
     * 直发磁盘文件：没有任何纠错要叠，内容必须与原文件一个字节都不差。
     *
     * @param path 产物文件路径
     * @param kind 文件位
     * @return 发文件的结果
     */
    public static ArtifactDownload ofFile(Path path, ArtifactFileKind kind) {
        return new ArtifactDownload(path, kind, null);
    }

    /**
     * 发重新渲染出来的字节（覆盖了纠错的那一版）。
     *
     * @param path    产物文件路径（只用来取文件名）
     * @param kind    文件位
     * @param content 渲染后的正文
     * @return 发字节的结果
     */
    public static ArtifactDownload ofContent(Path path, ArtifactFileKind kind, byte[] content) {
        return new ArtifactDownload(path, kind, content);
    }
}
