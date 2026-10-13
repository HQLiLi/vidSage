package com.vidsage.domain.shared.model;

import java.nio.file.Path;

/**
 * 输入身份的规范化串（值对象）：内容寻址的哈希输入。
 * 同一输入必须规范化出同一串；本类不承诺不同输入一定规范化出不同串
 * （哈希碰撞无法排除）。工作区 key（{@link WorkspaceKey#ofHash}）由它哈希而来。
 *
 * <p>有两条路线：远程 URL 以 URL 原文为身份；本地文件以
 * {@code 绝对路径|大小|最后修改时间毫秒} 为身份。具体公式、占位与参数语义
 * 见 {@link #ofRemoteUrl} 与 {@link #ofLocalFile}。
 *
 * <p><b>本类不做文件 I/O</b>：stat 由 application 读取后以三元组传入。
 *
 * <p>与落库的 {@code source_uri} 列（原始来源描述：URL / 原始文件名）是两个概念：
 * 本类只用于计算 key，不落库、不出境。
 *
 * @param value 规范化后的身份串
 */
public record SourceUri(String value) {

    /** 文件不存在或不可读时，大小 / 时间戳的占位值。 */
    public static final long UNAVAILABLE = -1L;

    /** 路径本身为 {@code null} 时的路径段占位值。 */
    private static final String NULL_PATH_PLACEHOLDER = "<null>";

    /**
     * 远程 URL 路线的身份：URL 原文即规范化串。
     *
     * @param url 完整 URL；{@code null} 按空串参与哈希，不做改写
     * @return 规范化后的身份值对象
     */
    public static SourceUri ofRemoteUrl(String url) {
        return new SourceUri(url == null ? "" : url);
    }

    /**
     * 本地文件路线的身份：绝对路径 + 大小 + 最后修改时间毫秒，以 {@code |} 连接。
     *
     * @param path          本地文件路径；为 {@code null} 时整条身份为占位串
     *                      （{@code "<null>|-1|-1"}）；相对路径先转绝对并 normalize
     * @param size          文件大小（字节）；文件不存在 / 不可读时传 {@link #UNAVAILABLE}
     * @param lastModifiedMs 最后修改时间（毫秒）；文件不存在 / 不可读时传 {@link #UNAVAILABLE}
     * @return 规范化后的身份值对象
     */
    public static SourceUri ofLocalFile(Path path, long size, long lastModifiedMs) {
        if (path == null) {
            return new SourceUri(NULL_PATH_PLACEHOLDER + "|" + UNAVAILABLE + "|" + UNAVAILABLE);
        }
        Path absolute = path.toAbsolutePath().normalize();
        return new SourceUri(absolute + "|" + size + "|" + lastModifiedMs);
    }
}
